package com.jun.musiccloud.playback

import android.content.Context
import android.util.Log
import com.jun.musiccloud.data.LibraryBackfill
import com.jun.musiccloud.data.LocalAudioIndex
import com.jun.musiccloud.data.OnlineMusicApi
import com.jun.musiccloud.data.RemoteLibrary
import com.jun.musiccloud.data.SourceHealth
import com.jun.musiccloud.data.SourceSwitches
import com.jun.musiccloud.data.ResolvedAudio
import fi.iki.elonen.NanoHTTPD
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地音频代理（纯联网播放的承载层）。
 *
 * 为什么需要它：网易云音频 CDN 对直链请求带防盗链校验（Referer/UA），且 VIP/无版权
 * 曲目需要用 Meting 302 解析真实地址。ExoPlayer 若直接用外层 outer/url，常出现
 * 403 或空流。这里让 ExoPlayer 播放一个稳定的 http://127.0.0.1:PORT/audio?id=…，
 * 由本代理在真正起播时才解析并代理音频流，因此：
 *  1. 列表/歌单加载不需要解析任何播放地址 —— 秒出；
 *  2. 起播时才解析真实地址，且带正确请求头绕过防盗链 —— 搜到就能放；
 *  3. 解析结果按 id 缓存，seek/重播不再重复解析。
 */
class AudioProxyServer(
    port: Int = OnlineMusicApi.PROXY_PORT,
    /** 6.9.3：只为「解析结果落盘」用；可为 null（单元测试/旧调用） */
    private val appCtx: Context? = null
) :
    NanoHTTPD("127.0.0.1", port) {

    /** [ACCESS_TOKEN] 的实例级读取（保持语义清晰，真值只有一个） */
    private val token: String get() = ACCESS_TOKEN

    /** id -> (解析结果, 过期时间)。缓存 10 分钟，避免同首歌反复解析 */
    private val urlCache = HashMap<String, Pair<ResolvedAudio, Long>>()
    private val cacheLock = Any()

    /**
     * 预解析线程池：4 路、守护、空闲 30 秒收摊。
     * 只提前解析「真实地址」（一次 HTTP 请求，不拉音频字节），
     * 目的是把起播那一下的解析 RTT 挪到上一首还在放的时候。
     *
     * 6.3.2 从单线程放到 3 路；6.4 放到 4 路，并把队列换成**按优先级**的：
     * 用户连点下一首时，同时在解析的可能有五六首，"用户现在就要的那首"必须插到
     * 队伍最前面，否则它会排在三个预取任务后面 —— 表现就是"点了没反应，过两秒才响"。
     */
    private class PrefetchTask(
        val priority: Int,
        val seq: Long,
        val block: () -> Unit
    ) : Runnable, Comparable<PrefetchTask> {
        override fun run() = block()
        override fun compareTo(other: PrefetchTask): Int {
            val c = priority.compareTo(other.priority)
            return if (c != 0) c else seq.compareTo(other.seq)
        }
    }

    private val prefetchSeq = java.util.concurrent.atomic.AtomicLong()

    private val prefetchPool: java.util.concurrent.ExecutorService =
        java.util.concurrent.ThreadPoolExecutor(
            // 6.7.2：4 → 6。4 路时"用户刚点的那首"经常要排队等前面 4 个预取任务
            // 各自跑完（每个最坏 6 秒），这就是"点歌要等好几秒"的真实来源之一。
            6, 6, 30L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.PriorityBlockingQueue(),
            java.util.concurrent.ThreadFactory { r ->
                Thread(r, "audio-prefetch").apply { isDaemon = true }
            }
        ).apply { allowCoreThreadTimeOut(true) }

    private fun cachedUrl(id: String): String? = cachedAudio(id)?.url

    /**
     * 6.6.5：预热上游连接（DNS + TCP + TLS 一次做完，保持 keep-alive）。
     *
     * 用 `HEAD` 而不是 `GET`：只要握手，不要字节。这样既不会多下载一份音频，
     * 又能让后面的正式 `/audio` 请求复用同一条已建好的连接。
     * 走独立的守护线程，绝不阻塞解析主链路。
     */
    private fun prewarm(url: String) {
        if (url.isBlank()) return
        runCatching {
            prewarmPool.execute {
                runCatching {
                    val c = URL(url).openConnection() as HttpURLConnection
                    try {
                        c.requestMethod = "HEAD"
                        c.connectTimeout = 4000
                        c.readTimeout = 4000
                        c.instanceFollowRedirects = true
                        c.setRequestProperty("User-Agent", UA)
                        c.setRequestProperty("Accept", "*/*")
                        c.setRequestProperty("Connection", "keep-alive")
                        c.setRequestProperty("Referer", refererOf(url))
                        c.responseCode  // 触发握手
                    } finally {
                        runCatching { c.inputStream?.close() }
                        // 不 disconnect：断开就白预热了
                    }
                }
            }
        }
    }

    private fun refererOf(url: String): String = when {
        url.contains("qqmusic") || url.contains("qq.com") -> "https://y.qq.com/"
        url.contains("kuwo") -> "http://www.kuwo.cn/"
        url.contains("kugou") -> "https://www.kugou.com/"
        url.contains("126.net") || url.contains("music.163") -> "https://music.163.com/"
        url.contains("migu") -> "https://music.migu.cn/"
        url.contains("joox") -> "https://www.joox.com/"
        else -> "https://y.qq.com/"
    }

    private val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private val prewarmPool: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
            Thread(r, "audio-prewarm").apply { isDaemon = true }
        }

    /**
     * 按 id 单飞（single-flight）解析：同一首歌同时只有一个解析任务在跑，
     * 后到的调用方**等那个任务的结果**，而不是自己再发一轮请求。
     *
     * 为什么这是"点歌要等好几秒"的关键之一：
     * 用户点歌那一瞬间，[PlayerViewModel] 会调 [prefetch] 让后台线程解析，
     * 紧接着播放器就来 `/audio` 要音频 —— 两边各自调 resolveAudio，
     * 一首歌走两遍解析链路（每遍 1~3 秒）。串在用户耳朵里就是双重等待。
     * 现在第二个调用方直接拿到第一个的结果，总耗时等于"一次解析"。
     *
     * [inFlight] 里存的是 CompletableFuture：谁先到谁负责算，后来的人 `.join()`。
     */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<
        String, java.util.concurrent.CompletableFuture<ResolvedAudio?>>()

    /**
     * 6.7.3：**失败阶梯**——一次拿不到，不等于"这首歌没版权"。
     *
     * 用户报"有些歌以前能听，现在显示无版权"。查下来几乎全是这三种情况，
     * 而它们都不该被翻译成"没版权"：
     *   ① 用户设的音质（无损 / 320k）那个源没有，脚本按档位要不到就直接返回空；
     *   ② 脚本链在预算内没跑完（被当成失败）；
     *   ③ 上一次网络抖动被记进负缓存，短时间内再点直接 null。
     *
     * 所以这里最多走三档，**前一档真的没有**才继续：
     *   1) 当前音质，正常链路（API 与脚本竞速）；
     *   2) 降到标准音质 + force（绕过所有负缓存）；
     *   3) 跳过快源，直接向 GD/官方要直链。
     *
     * 三档都空才算真的没有。代价是"真死歌"要多花几秒，但那种歌本来也放不出来；
     * 收益是所有"其实有源、只是档位/缓存卡住"的歌都能正常响。
     */
    private fun resolveWithLadder(
        id: String,
        title: String,
        artist: String,
        durationMs: Long,
        preferFormat: String? = null
    ): ResolvedAudio? {
        val startedAt = System.currentTimeMillis()

        // ────────────────────────────────────────────────────────────
        // 6.8.0：**第二层后路 —— 自建曲库**（你自己服务器上的文件）。
        //
        // 顺序刻意放在所有第三方之前：这是唯一"别人关不掉"的在线来源，
        // 只要有就直接用，不必再去赌第三方今今天还活不活。
        // lookup 是纯内存匹配（清单已在本地缓存），不产生任何网络往返，
        // 所以放在最前面也不会给起播加延迟。
        // ────────────────────────────────────────────────────────────
        if (SourceSwitches.library) runCatching {
            RemoteLibrary.lookup(id, title, artist)?.let { hit ->
                Log.i(TAG, "自建曲库命中：$id / ${hit.title} / ${hit.format}")
                return ResolvedAudio(
                    url = RemoteLibrary.fileUrl(hit),
                    level = if (hit.format == "flac") "lossless" else "320k",
                    format = hit.format.ifBlank { "mp3" },
                    sizeBytes = hit.bytes,
                    trial = false,
                    provider = "library"
                )
            }
        }

        val first = runCatching {
            OnlineMusicApi.resolveAudio(
                id, title, artist,
                preferFormat = preferFormat,
                durationMs = durationMs
            )
        }.getOrNull()
        if (first != null && first.url.isNotBlank()) return first

        // ────────────────────────────────────────────────────────────
        // 6.7.4：阶梯从三级砍到两级，并且加了**总时限**。
        //
        // 6.7.3 是「当前音质 8s → 降档 8s → 直链 8s」，最坏 24 秒。
        // 播放器等响应头的时间上限是 25 秒，一旦踩线就是 InvalidResponseCode /
        // 连接超时 → 播放失败 → 自动重试 → 再等 24 秒 → 全线雪崩。
        // 用户的原话「一个歌都听不了」就是这么来的。
        //
        // 现在：第一档最多 5 秒；已经花掉 4 秒以上就不再做第二档（直接快速失败，
        // 让播放侧立刻知道，而不是继续耗）。第二档只做"降档"，因为它是真正
        // 命中率最高的一步（很多"没版权"只是那个档位没货）。
        // ────────────────────────────────────────────────────────────
        if (System.currentTimeMillis() - startedAt > 4_000L) {
            val spent = System.currentTimeMillis() - startedAt
            Log.w(TAG, "首档耗时过久，快速失败交给播放侧重试：$id")
            // 6.8.0：这类失败以前完全不可见 —— 用户只觉得"卡了一下然后跳歌"，
            // 而后台看不到任何记录。上报之后才能判断是系统性变慢还是个别歌。
            SourceHealth.reportFail(id, title, artist, spent, "first-timeout")
            return null
        }

        val second = runCatching {
            OnlineMusicApi.resolveAudio(
                id, title, artist,
                quality = com.jun.musiccloud.data.AudioQuality.STANDARD,
                preferFormat = null,
                durationMs = durationMs,
                force = true
            )
        }.getOrNull()
        if (second != null && second.url.isNotBlank()) {
            Log.i(TAG, "降档重试成功：$id → ${second.level} / ${second.format}")
            // 6.8.0：这是**最有价值的一条日志** ——
            // "第一档没给、第二档给了"说明某个源已经不正常，
            // 但用户没受影响。后台按 provider 统计，就能看出哪个源在悄悄失效。
            SourceHealth.reportRescue(second.provider, System.currentTimeMillis() - startedAt, title)
            return second
        }

        val totalMs = System.currentTimeMillis() - startedAt
        Log.w(TAG, "两档都拿不到地址：$id / $title（耗时 ${totalMs}ms）")
        SourceHealth.reportFail(id, title, artist, totalMs, "all-failed")
        return null
    }

    private fun resolveOnce(
        id: String,
        resolve: () -> ResolvedAudio?
    ): ResolvedAudio? {
        val mine = java.util.concurrent.CompletableFuture<ResolvedAudio?>()
        val existing = inFlight.putIfAbsent(id, mine)
        if (existing != null) {
            // 已经有人在解析这一首 —— 等它，最多等 8 秒。
            //
            // 为什么必须有超时：预取线程池只有 4 路，如果这 4 路恰好都在解析
            // 别的歌、而它们又都在等某个更慢的结果，/audio 这条线程就会一直空等，
            // 用户的感受是"点了完全没反应"。超时之后**自己算一次兜底**，
            // 宁可多一次请求，也不能让播放器卡死。
            // 6.7.2：8 秒 → 4 秒。这个等待是**挡在播放器请求前面的**：
            // 4 秒还没结果，说明那次解析撞上了慢源，继续等只会让用户多盯 4 秒转圈。
            // 超时后自己走一遍链路（有单飞去重，不会真的重复请求太多）。
            // 6.7.4：4 秒 → 2.5 秒。这个等待是挡在播放器请求前面的；
            // 叠加首档 5 秒就是 7.5 秒，够触发播放器的响应超时了。
            val waited = runCatching {
                existing.get(2, java.util.concurrent.TimeUnit.SECONDS)
            }.getOrNull()
            if (waited != null) return waited
            // 超时或异常：拿缓存，没有就自己算一遍（此时 existing 仍挂在 map 上，
            // 我们不再往 map 里塞，只是独立走一次链路，结果照样会被 putAudio 落缓存）
            return cachedAudio(id) ?: runCatching { resolve() }.getOrNull()
        }
        try {
            val hit = resolve()
            mine.complete(hit)
            return hit
        } catch (t: Throwable) {
            mine.complete(null)
            throw t
        } finally {
            // 先落缓存再摘掉 in-flight（顺序反了会出现"摘掉了但还没缓存"的空窗）
            inFlight.remove(id, mine)
        }
    }

    /** 丢掉某首歌的解析结果：重试 / 失败后重播时用，下一次请求会重新解析 */
    fun invalidate(id: String) {
        if (id.isBlank()) return
        synchronized(cacheLock) { urlCache.remove(id) }
        dropAudioFromDisk(id)
    }

    // ===== 不可播判定（R26）=====
    //
    // 为什么不能只看 HTTP 状态码：解析不到地址时这里回的是 **204**，而 204 属于 2xx，
    // DefaultHttpDataSource 会当成"请求成功"放行，返回一个空流；ExoPlayer 接着报的是
    // "解不出容器格式 / EOF"，错误码里**根本拿不到 204**。于是播放器侧那段
    // `if (httpCode == 204) 自动跳下一首` 从来没执行过 —— 用户看到的就是这首永远停在 0:00。
    // 修法：解析失败由代理自己记账，播放器报错时反过来问一句"这首刚才是不是被判死过"。
    private val unplayableAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 代理确认所有音源都拿不到地址时打点；重复打点只更新时间 */
    private fun noteUnplayable(id: String) {
        if (id.isBlank()) return
        unplayableAt[id] = android.os.SystemClock.elapsedRealtime()
        // 表不能无限长：顺手清掉 1 小时前的旧账（正常播放路径也会 clear，这里只是兜底）
        val now = android.os.SystemClock.elapsedRealtime()
        if (unplayableAt.size > 256) {
            unplayableAt.entries.removeAll { now - it.value > 60 * 60 * 1000L }
        }
    }

    /** 丢掉失败打点：重新解析成功、或用户主动重试时调用 */
    private fun clearUnplayable(id: String) {
        if (id.isNotBlank()) unplayableAt.remove(id)
    }

    private fun cachedAudio(id: String): ResolvedAudio? = synchronized(cacheLock) {
        val e = urlCache[id] ?: run {
            // 6.9.3：内存里没有（进程刚重启）→ 试着从磁盘捞上次的解析结果。
            // 用户"按 Home 再回来"最常见的卡顿就是这里：地址其实还有效，
            // 却要重新走一遍四层取源。命中磁盘后只需一次上游请求就能出声。
            val disk = loadAudioFromDisk(id) ?: return null
            urlCache[id] = disk to (System.currentTimeMillis() + 10 * 60 * 1000)
            return disk
        }
        if (System.currentTimeMillis() > e.second) { urlCache.remove(id); return null }
        e.first
    }

    // ────────────────────────────────────────────────────────────
    // 6.9.3：解析结果落盘（最多 40 首、6 小时有效）
    //
    // 只存「歌曲 id → 直链 + 档位」这份小 JSON，不存音频。地址失效时
    // 上游会回 403/404，代理里已有的"重解析重试"会接住（见 proxyAudio），
    // 所以这里宁可多存一会儿 —— 命中一次就省掉一整轮四层取源。
    // ────────────────────────────────────────────────────────────
    private fun diskPrefs() = appCtx?.getSharedPreferences("jun_url_cache", Context.MODE_PRIVATE)

    private fun saveAudioToDisk(id: String, a: ResolvedAudio) {
        val p = diskPrefs() ?: return
        runCatching {
            val o = org.json.JSONObject().apply {
                put("u", a.url); put("l", a.level ?: ""); put("f", a.format ?: "")
                put("b", a.bitrate); put("s", a.sizeBytes); put("t", a.altTitle ?: "")
                put("r", a.trial); put("p", a.provider); put("at", System.currentTimeMillis())
            }
            val all = p.all.toMutableMap()
            all[id] = o.toString()
            // 超量：按写入时间留最新 40 条
            if (all.size > 40) {
                val keep = all.entries.sortedByDescending { (_, v) ->
                    runCatching { org.json.JSONObject(v as String).optLong("at") }.getOrDefault(0L)
                }.take(40).associate { it.key to it.value }
                p.edit().clear().apply()
                keep.forEach { (k, v) -> p.edit().putString(k, v as String).apply() }
            } else {
                p.edit().putString(id, o.toString()).apply()
            }
        }
    }

    private fun loadAudioFromDisk(id: String): ResolvedAudio? {
        val p = diskPrefs() ?: return null
        val raw = p.getString(id, null) ?: return null
        return runCatching {
            val o = org.json.JSONObject(raw)
            if (System.currentTimeMillis() - o.optLong("at") > 6 * 3600_000L) {
                p.edit().remove(id).apply(); return null
            }
            val url = o.optString("u")
            if (url.isBlank()) return null
            ResolvedAudio(
                url = url,
                level = o.optString("l").ifBlank { null },
                format = o.optString("f").ifBlank { null },
                bitrate = o.optInt("b"),
                sizeBytes = o.optLong("s"),
                altTitle = o.optString("t").ifBlank { null },
                trial = o.optBoolean("r"),
                provider = o.optString("p")
            )
        }.getOrNull()
    }

    private fun dropAudioFromDisk(id: String) {
        runCatching { diskPrefs()?.edit()?.remove(id)?.apply() }
    }

    /**
     * 6.6.9：**过期地址的"陈旧兜底"**。
     *
     * 用户现场（最严重的一轮）："歌听着好好的，进度条一拖就永久卡死，接着整张歌单
     * 全部'无版权'连跳。" 完整的连锁链是这样的：
     *
     *   解析结果只缓存 10 分钟 → 用户听了十几分钟后缓存过期 → 一拖进度条，
     *   播放器对 seek 偏移发起请求 → 代理发现地址缓存没了 → 现场重新解析
     *   → 而音源（尤其 QQ 脚本接口被连续预取限流时）恰好解析失败
     *   → 回 204 → 播放器无声卡死 → 判"无版权"跳下一首
     *   → 下一首的地址缓存同样过期、同样解析失败 → 连跳 → "整个歌单没版权"。
     *
     * 其实过期地址**大概率还能用**（CDN 直链的真实有效期通常是几小时，
     * 10 分钟只是我们保守的缓存 TTL）。所以：fresh 过期后不删条目，
     * 留着当"陈旧地址"再用 2 小时——能用就继续放，真失效了走下面的
     * 重解析重试，比"直接判死"强一万倍。
     *
     * 判断标准：过期不超过 [STALE_MS] 就返回该地址（调用方拿到 403/404
     * 会走 6.6.7 的重解析逻辑，那条路是安全的）。
     */
    private fun cachedAudioStale(id: String): ResolvedAudio? = synchronized(cacheLock) {
        val e = urlCache[id] ?: return null
        if (System.currentTimeMillis() > e.second + STALE_MS) {
            urlCache.remove(id)
            return null
        }
        e.first
    }

    private fun putAudio(id: String, audio: ResolvedAudio) {        // ────────────────────────────────────────────────────────────────
        // 6.6.9：**删掉了"地址变了就清磁盘缓存"（6.3.4 引入）——这是一条重伤链。**
        //
        // 原逻辑：新旧地址不一致 → PlaybackCache.evictSong(id) 把这首歌的磁盘分片全删。
        // 但 putAudio 最常见的触发时机，恰恰是**播放器正等着这首歌的字节**：
        // seek 偏移没命中缓存 → 播放器的 CacheDataSource 已经打开了这个缓存键、
        // 拿着写句柄在等代理回数据 → 代理这边重新解析完 putAudio → evictSong
        // 把写句柄底下的缓存条目整个抽掉 → CacheDataSource 报 "span not found"
        // → 播放层判成"缓存故障"→ 清空**全部**缓存 + 重试 → 重解析又赶上音源限流
        // → 整张歌单连跳"无版权"。用户拖一次进度条就全崩，根子在这里。
        //
        // 为什么可以安全删掉：缓存键里早就带了解析代次（`@<e>`）和音质档位，
        // App 层重试时会 bump 代次，新地址天然落在新键里，旧分片顶不掉新内容
        //（这正是 6.6.5 修"6 秒残片"的机制）。这里的同步清除只会带来
        // "抽掉活人脚下的地板"这一种效果，没有任何收益。
        // ────────────────────────────────────────────────────────────────
        // 6.7.1：解析成功 = 这首现在有地址了，必须立刻解除"不可播"记账，
        // 否则播放器一报错就先看到一条 5 分钟前的旧账，直接判无版权跳歌。
        clearUnplayable(id)
        synchronized(cacheLock) {
            // 6.3.2：解析成功就清掉"失败档位"退避，否则上一轮 502 之后三分钟内都会直接跳过
            runCatching { OnlineMusicApi.clearLevelFail(audio.level) }
            urlCache[id] = audio to (System.currentTimeMillis() + 10 * 60 * 1000)
            if (urlCache.size > 300) {
                val now = System.currentTimeMillis()
                // 6.6.9：陈旧兜底期内的条目不清理（见 STALE_MS）
                urlCache.entries.removeIf { it.value.second + STALE_MS < now }
            }
        }
        // 6.9.3：落一份到磁盘，进程被杀后再回来不必重新取源
        saveAudioToDisk(id, audio)
        // 让 UI 知道「这首歌实际拿到了什么档位」。回调在 socket 线程触发，
        // 消费方（PlayerViewModel）自己切主线程；它只做一次状态写入，不许干别的。
        // 放在锁外：回调内部会回到主线程写状态，持锁期间调用会拉长临界区。
        runCatching { onResolved?.invoke(id, audio) }

        // 6.6.5 起播提速：解析刚结束就**预热上游连接**。
        //
        // 用户点歌 → 代理拿到真实地址 → 播放器紧接着来 /audio 要字节。
        // 中间那一步要新建 TLS 握手的连接（QQ/咪咕 CDN 跨省 150~400ms，
        // 握手再加 1~2 个 RTT）。如果解析完立刻把连接建好、保持 keep-alive，
        // 播放器来取字节时就能直接复用 —— 用户听到的是"点了就响"。
        //
        // 只在后台跑，失败完全忽略：预热只是省钱，不是必须成功的步骤。
        runCatching { prewarm(audio.url) }
    }

    /**
     * 提前解析后续曲目地址（5.1 新增）。
     *
     * 已缓存的直接跳过，所以切歌时连着调三次也只对没解析过的歌发请求。
     * 队列顺序由调用方（[MusicService]）决定，本方法不碰播放器状态，
     * 因此不会影响正在播的曲目。
     */
    /**
     * 6.7.2：**用户点歌的那一刻，立即解析这一首，不排任何队。**
     *
     * 为什么不能复用 [prefetch]：它提交到只有 6 个线程的优先级队列里，
     * 队列中可能已经躺着十几首（列表切换时批量预取的）。优先级只决定"下一个被取出的",
     * 已经在跑的 6 个任务不会停——用户点的那首只能干等，最长要等 6 个任务跑完。
     * 这里用独立线程，点下去就开始解析，和其他预取完全隔离。
     */
    fun prefetchNow(ids: List<String>) {
        ids.forEach { id ->
            if (id.isBlank() || cachedUrl(id) != null) return@forEach
            val meta = songMeta[id]
            runCatching {
                Thread({
                    runCatching {
                        if (cachedUrl(id) == null) {
                            resolveOnce(id) {
                                resolveWithLadder(
                                    id,
                                    meta?.title.orEmpty(),
                                    meta?.artist.orEmpty(),
                                    meta?.durationMs ?: 0L
                                )
                            }?.let { putAudio(id, it) }
                        }
                    }
                }, "audio-urgent").apply { isDaemon = true }.start()
            }
        }
    }

    fun prefetch(ids: List<String>) {
        // 6.4：不再 take(4)。原来只处理前 4 首，用户点到第 5 首时它压根没被预取过。
        // 现在全部提交，靠队列顺序 + 已缓存跳过控制成本（已解析的直接 return，不占线程）。
        if (ids.isEmpty()) return
        ids.forEachIndexed { idx, id ->
            if (id.isBlank() || cachedUrl(id) != null) return@forEachIndexed
            // 6.6.6：把歌名歌手带上，否则脚本链路按名搜索时一张白纸，预取注定失败。
            val meta = songMeta[id]
            runCatching {
                // priority：列表里越靠前越优先（0 = 用户马上就要的那首）
                prefetchPool.execute(
                    PrefetchTask(idx, prefetchSeq.incrementAndGet()) {
                        runCatching {
                            if (cachedUrl(id) == null) {
                                resolveOnce(id) {
                                    resolveWithLadder(
                                        id,
                                        meta?.title.orEmpty(),
                                        meta?.artist.orEmpty(),
                                        meta?.durationMs ?: 0L
                                    )
                                }?.let { putAudio(id, it) }
                            }
                        }
                    }
                )
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        if (uri == "/audio" || uri.startsWith("/audio?")) {
            // 校验一次性令牌：不是本进程发出的请求一律拒绝
            val t = session.parameters["t"]?.firstOrNull()
            if (t.isNullOrBlank() || t != token) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "forbidden")
            }
            return proxyAudio(session)
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
    }

    /**
     * 6.6.7：从 Content-Range 头里解析**整首歌的总大小**。
     *
     * 头格式：`bytes 12345-67890/123456`（总长在斜杠后面）。
     * 解析不出 / 总长未知（`*`）时返回 -1 —— 未知就**不判**残片，
     * 宁可放过一个片段也不能再把一次正常 seek 判成"无版权"。
     */
    private fun parseTotalFromContentRange(header: String?): Long {
        if (header.isNullOrBlank()) return -1L
        val total = header.substringAfterLast('/', "").trim()
        if (total.isEmpty() || total == "*") return -1L
        return total.toLongOrNull() ?: -1L
    }

    /** 6.7.1：从 `Range: bytes=12345-` 里取起点；无 Range / 从 0 开始都返回 0 */
    private fun parseRangeStart(header: String?): Long {
        if (header.isNullOrBlank()) return 0L
        val v = header.substringAfter("bytes=", "").substringBefore('-').trim()
        return v.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    /** 6.7.1：从 `Content-Range: bytes 12345-67890/99999` 里取起点 */
    private fun parseStartFromContentRange(header: String?): Long {
        if (header.isNullOrBlank()) return -1L
        val body = header.substringAfter("bytes", "").trim()
        val start = body.substringBefore('-').trim()
        return start.toLongOrNull()?.coerceAtLeast(0L) ?: -1L
    }

    /**
     * 6.7.1：精确跳过 N 字节。
     *
     * 不用 [java.io.InputStream.skip]：HttpURLConnection 的流对 skip 的实现是
     * "能跳多少跳多少"，经常只跳一部分，调用方以为跳到了、实际差几百 KB，
     * 表现就是"拖动后声音和进度条差半句"。这里一律用 read 丢弃。
     */
    private fun skipFully(
        input: java.io.InputStream,
        bytes: Long,
        timeoutMs: Long = SKIP_TIMEOUT_MS
    ): Boolean {
        var left = bytes
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            // 4 秒还跳不过去就别耗了：让播放器快速失败去换源，
            // 总好过"拖完卡半分钟"，那正是用户最难受的体验。
            if (System.currentTimeMillis() > deadline) return false
            val want = minOf(buf.size.toLong(), left).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) return false
            left -= n
        }
        return true
    }

    /**
     * 6.8.0：**本地文件直发** —— 命中就完全绕开网络。
     *
     * 必须自己实现 Range，不能偷懒整文件发送：
     * ExoPlayer 拖进度条时会发 `Range: bytes=N-`，如果这里总是回 200 + 整个文件，
     * 播放器要么从头缓冲，要么判定服务器不支持 seek，用户看到的就是
     * "拖一下停好几秒" —— 那正是前面几个版本反复踩的坑。
     *
     * @return null 表示没有本地文件 / 文件已失效，调用方继续走在线解析
     */
    private fun serveFromLocal(id: String, session: IHTTPSession): Response? {
        val title = session.parameters["tn"]?.firstOrNull().orEmpty()
        val artist = session.parameters["an"]?.firstOrNull().orEmpty()

        val entry = runCatching { LocalAudioIndex.lookup(id, title, artist) }.getOrNull() ?: return null
        val opened = runCatching { LocalAudioIndex.openStream(entry) }.getOrNull()
        if (opened == null) {
            // 索引里有、文件却打不开（被系统清理 / 用户手动删了）：清掉这条，回退在线
            runCatching { LocalAudioIndex.forget(entry.path) }
            return null
        }

        val total = opened.length
        if (total <= 0L) {
            // content:// 拿不到长度时退回在线：算不出 Content-Range 就去猜，
            // 会让播放器的时间轴彻底错乱（比放不出来更糟）。
            runCatching { opened.stream.close() }
            Log.w(TAG, "本地文件长度未知，回退在线：${entry.path.takeLast(40)}")
            return null
        }

        val rangeHeader = session.headers["range"]
        var start = 0L
        var end = total - 1
        if (!rangeHeader.isNullOrBlank()) {
            val spec = rangeHeader.substringAfter("bytes=", "").trim()
            spec.substringBefore('-').trim().toLongOrNull()?.let { start = it.coerceIn(0L, total - 1) }
            spec.substringAfter('-', "").trim().toLongOrNull()?.let { end = it.coerceIn(start, total - 1) }
        }

        if (start >= total) {
            runCatching { opened.stream.close() }
            val r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
            r.addHeader("Content-Range", "bytes */$total")
            return r
        }

        if (start > 0L && !skipFully(opened.stream, start)) {
            runCatching { opened.stream.close() }
            return null
        }

        val len = end - start + 1
        val status = if (rangeHeader.isNullOrBlank()) Response.Status.OK else Response.Status.PARTIAL_CONTENT
        val resp = newFixedLengthResponse(status, mimeOfLocal(entry.path), opened.stream, len)
        resp.addHeader("Accept-Ranges", "bytes")
        resp.addHeader("Content-Range", "bytes $start-$end/$total")
        Log.i(TAG, "本地命中（零网络）：$id ${entry.title} $start-$end/$total")
        return resp
    }

    private fun mimeOfLocal(path: String): String {
        val ext = path.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (ext) {
            "flac" -> "audio/flac"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "wma" -> "audio/x-ms-wma"
            "ape" -> "audio/x-ape"
            else -> "audio/mpeg"
        }
    }

    private fun proxyAudio(session: IHTTPSession): Response {
        return try {
            val id = session.parameters["id"]?.firstOrNull()
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing id")

            // ────────────────────────────────────────────────────────
            // 6.8.0：**本地优先**。手机里已经有这个文件，就绝不走网络。
            //
            // 这一行是整个"后路"体系的地基：外面的音源全部关停、没网、
            // CDN 全 403，只要这一行命中，歌照样能响。
            //
            // 放在最前面而不是"解析失败之后再回退"，有两个理由：
            //   · 本地文件起播是毫秒级，没有任何理由先花几秒去问网络；
            //   · 先查网络再回退，等于每次都要先失败一次，用户会听到明显的空档。
            // ────────────────────────────────────────────────────────
            serveFromLocal(id, session)?.let { return it }

            var audio = cachedAudio(id)
            if (audio == null) {
                // 6.6.9：fresh 过期先问一句"有没有陈旧地址"。有就直接用，
                // 绝不因为缓存 TTL 到点就把一首正在听的歌送进重新解析的赌局
                //（音源限流时那一赌就是整张歌单连跳，见 cachedAudioStale 注释）。
                audio = cachedAudioStale(id)
            }
            if (audio == null) {
                // 歌名/歌手用于「网易云没版权 → jOOX 同名曲」兜底，列表构造 URL 时一并带上
                val title = session.parameters["tn"]?.firstOrNull().orEmpty()
                val artist = session.parameters["an"]?.firstOrNull().orEmpty()
                // 6.6.6：时长用于「换艺名认歌」（《知我》在 QQ 叫「谭渊」）
                val durationMs = session.parameters["du"]?.firstOrNull()?.toLongOrNull() ?: 0L
                // 5.1：按用户在设置里选的档位解析；AUTO / 未登录网易云时
                // resolveAudio 内部完全等价于 5.0 的 resolveAudioUrl
                //
                // 6.4：如果**预取线程正在解析同一首歌**，这里直接等它，不要重开一次。
                // 原来两边各解析各的：用户点歌 → 预取线程解析（1~3 秒），
                // 同时播放器来要音频 → 又解析一遍（再 1~3 秒），两次串在用户耳朵里
                // 就是"点了要等好几秒"。现在按 id 单飞，同一首歌全程只有一次网络请求。
                // 6.5.3：解析过程本身抛异常（三个源全炸）同样算"拿不到地址"，
                // 一并记账 —— 否则这类失败只会回 500，播放器侧读不到 204，
                // 又变成"一直卡在 0:00"。
                audio = runCatching {
                    resolveOnce(id) { resolveWithLadder(id, title, artist, durationMs) }
                }.onFailure { noteUnplayable(id) }.getOrNull()
                if (audio != null) putAudio(id, audio)
            }
            val realUrl: String = audio?.url.orEmpty()
            if (realUrl.isEmpty()) {
                // R26：把"代理确实判过死"记下来，播放器报错时据此识别真·不可播。
                noteUnplayable(id)
                // 6.6.9：状态码从 204 换成 502。
                //
                // 204 属于 2xx，DefaultHttpDataSource 当成"成功"放行、返回空流，
                // ExoPlayer 接着要么报解析错误、要么**无声卡死**——后者正是用户
                // "拖动后一直暂停不动、进度条一直零"的直接原因：错误根本不冒泡，
                // 自动跳过链路完全收不到通知。502 会让 InvalidResponseCodeException
                // 立刻抛出 → onPlayerError 马上触发 → 走 isUnplayable 判定跳歌，
                // 不再有"卡住不动"的中间态。
                val resp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "unplayable")
                resp.addHeader(HEADER_UNPLAYABLE, "1")
                return resp
            }

            // ────────────────────────────────────────────────────────────
            // 6.6.7：**地址过期自动重解析**（seek 场景的第二刀）。
            //
            // 解析结果缓存 10 分钟，但 CDN 地址可能更早失效。表现正好是用户报的：
            // 一首歌听着好好的，一拖进度条（ExoPlayer 对新偏移重新发起请求）
            // 上游 403/404 → 透传 502 → 播放器报错。以前这样就直接判失败了。
            //
            // 现在：403/404/410 且还没重试过时，把旧地址作废、带全凭据重新解析一次
            // （音质档位按上一次拿到的格式续用），拿新地址重发同一请求。
            // 重试还失败才按真正的上游错误处理 —— 不改变"真失效"的判定，
            // 只是给"地址先于缓存过期"这种情况一次机会。
            // ────────────────────────────────────────────────────────────
            var upstreamUrl: String = realUrl
            var code = 0
            var mime = "audio/mpeg"
            var conn: HttpURLConnection? = null
            var retried = false
            // 续用上一次拿到的容器：用户在听无损就别重解析回 mp3
            val prevFormat = audio?.format
            // ────────────────────────────────────────────────────────────
            // 6.7.1：**先判断这次是不是 seek**（用户报「拖到中间必停、必报失败」）。
            //
            // ExoPlayer 拖动进度条时发 `Range: bytes=<偏移>-`，偏移 > 0 就是一次 seek。
            // 以前这一段完全不区分「从头播」和「从中间播」，于是：
            //   · 残片判定把 seek 拿到的「剩余段长度」当成整首大小 → 判死 → 502；
            //   · 上游若忽略 Range 回 200，代理又从头把字节吐回去 → 播放器要读完
            //     0~偏移 的全部数据才出声 → 用户看到的就是"拖完停几十秒"。
            // 这两条叠在一起，就是"不管谁的歌，一拖就暂停然后失败"。
            // ────────────────────────────────────────────────────────────
            val rangeHeader = session.headers["range"]
            val rangeStart = parseRangeStart(rangeHeader)
            val isSeek = rangeStart > 0
            while (true) {
                val c = URL(upstreamUrl).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                // 4 秒够建连；再长只会让"拖完没反应"更久
                c.connectTimeout = 6000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", UA)
                // 6.6.5：**按域名给 Referer**。
                //
                // 原来这里只有两种：joox 用 joox.com，**其它一律给 music.163.com**。
                // 但脚本音源拿到的地址绝大多数是 QQ / 咪咕 / 酷我的 CDN，
                // 它们会校验 Referer —— 给网易云的 Referer 就是"防盗链拒绝"。
                // 表现：解析明明成功、地址明明能播，代理这一转发就 403，
                // 用户看到的是"这首放不了"，而同一个地址在浏览器里是好的。
                c.setRequestProperty("Referer", refererOf(upstreamUrl))
                c.setRequestProperty("Accept", "*/*")
                // 转发 Range，保证 seek 可用
                if (!rangeHeader.isNullOrBlank()) c.setRequestProperty("Range", rangeHeader)
                conn = c
                code = c.responseCode
                mime = c.contentType?.split(";")?.firstOrNull()?.trim() ?: "audio/mpeg"
                if ((code == 403 || code == 404 || code == 410) && !retried) {
                    retried = true
                    Log.w(TAG, "上游 $code，作废旧地址重解析一次：$id url=${upstreamUrl.take(60)}")
                    invalidate(id)
                    val meta = songMeta[id]
                    val fresh = runCatching {
                        resolveOnce(id) {
                            resolveWithLadder(
                                id,
                                meta?.title.orEmpty(),
                                meta?.artist.orEmpty(),
                                meta?.durationMs ?: 0L,
                                // 续用上一次拿到的容器：用户在听无损就别重解析回 mp3
                                preferFormat = prevFormat?.takeIf { it == "flac" || it == "mp3" }
                            )
                        }
                    }.getOrNull()
                    if (fresh != null && fresh.url != upstreamUrl) {
                        putAudio(id, fresh)
                        upstreamUrl = fresh.url
                        continue
                    }
                }
                break
            }
            val realConn = conn
                ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "audio error")
            val len = realConn.contentLengthLong
            if (code !in 200..299) {
                // 上游拒绝（链接过期 / 防盗链 / 空文件）：原样透传状态码，
                // 并打上"上游拒绝"标记 —— 这和"真的没版权"是两回事，提示要分开。
                // 状态统一用 502（上游错误）：真实原因放在头里，播放器侧据此分辨
                val err = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "text/plain", "upstream " + code
                )
                err.addHeader(HEADER_UPSTREAM, code.toString())
                return err
            }
            val stream = realConn.inputStream
            // 这次真拿到音频了：把这歌之前的"不可播"记账抹掉，避免上一轮的误判带进来
            clearUnplayable(id)

            // ────────────────────────────────────────────────────────────
            // 6.8.1：**曲库自动回填**（6.9.2 起只记"候选"）。
            //
            // 只有"从头请求整首 + 上游真的回了 200"才记账 —— 这正好是"用户真的在听"
            // 的那个时刻。seek 请求（206）不算，因为它只拿到一段，不能代表整首可用。
            //
            // 6.9.2 收紧：这里**只记候选，不进下载队列** —— 要等播放器自然播完
            // （PlayerViewModel 的 STATE_ENDED → markListenedThrough）才真正回填。
            // 用户切歌像逛橱窗，一下午烧光 300MB 额度的问题就是这么来的；
            // 现在额度只花在真正听完的歌上。
            //
            // 6.7 治根（云端曲库一直 27 首）：旧条件写的是 `code == 200`，但
            // ExoPlayer 起播时**永远带 `Range: bytes=0-`**，上游/CDN 对带 Range
            // 的请求一律回 **206 Partial Content** —— 200 几乎不出现。于是候选表
            // 长期为空，markListenedEnough 每 500ms 调一次也永远 remove 不到东西，
            // 上传队列一次都没进过。服务器上那 27 首全是 11-07 旧版本留下的，
            // 之后四个版本一首没涨，根因就在这里。改成 2xx 全收（206 也算）。
            //
            // 同时把「二次下载地址」从第三方临时直链换成本地代理回环地址：
            // 用户听完整首要 3~5 分钟，直链早过期了，旧代码拿它重新 GET 多半 403，
            // 三次失败就冷却 30 分钟。走回环时代理会自己重解析最新直链，不受时效影响。
            // ────────────────────────────────────────────────────────────
            if (!isSeek && code in 200..299) {
                runCatching {
                    val meta = songMeta[id]
                    // 回环地址带上歌名/歌手/时长，代理重解析时才有完整上下文
                    val loopback = AudioProxyServer.audioUrl(
                        id,
                        meta?.title.orEmpty(),
                        meta?.artist.orEmpty(),
                        meta?.durationMs ?: 0L
                    )
                    LibraryBackfill.offer(
                        songId = id,
                        title = meta?.title.orEmpty(),
                        artist = meta?.artist.orEmpty(),
                        durationMs = meta?.durationMs ?: 0L,
                        url = upstreamUrl,
                        proxyUrl = loopback,
                        format = audio?.format,
                        trial = audio?.trial == true,
                        provider = audio?.provider.orEmpty()
                    )
                }
            }

            var status = if (code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            var bodyLen: Long = len
            var contentRange: String? = realConn.getHeaderField("Content-Range")
            var totalBytes: Long = if (code == 206) {
                parseTotalFromContentRange(contentRange)
            } else {
                len
            }

            // ────────────────────────────────────────────────────────────
            // 6.7.1：**上游忽略 Range 时，代理自己补 206**
            //
            // 有些脚本音源给的直链是把文件动态拼出来的，不认 Range，永远回 200 全量。
            // 以前代理把 200 原样吐回去，播放器为了跳到第 3 分钟，必须先读完前 3 分钟
            // 的字节（几 MB）——用户耳朵里就是"拖完停十几秒才出声"，弱网下直接超时失败。
            //
            // 现在：从流派里精确跳过 offset，对外仍回 206 + 正确的 Content-Range。
            // 跳字节是要花流量的，所以设了上限（MAX_SELF_SEEK_BYTES），
            // 超出就快速失败让播放器换源，绝不把用户吊在那儿等。
            // ────────────────────────────────────────────────────────────
            if (code == 200 && isSeek) {
                val ok = rangeStart <= MAX_SELF_SEEK_BYTES &&
                    runCatching { skipFully(stream, rangeStart) }.getOrDefault(false)
                if (!ok) {
                    Log.w(TAG, "上游不支持 Range 且跳过失败：$id offset=$rangeStart")
                    runCatching { realConn.disconnect() }
                    val err = newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR, "text/plain", "seek unsupported"
                    )
                    err.addHeader(HEADER_UPSTREAM, "200")
                    return err
                }
                status = Response.Status.PARTIAL_CONTENT
                bodyLen = if (totalBytes > 0) totalBytes - rangeStart else -1L
                contentRange = if (totalBytes > 0) {
                    "bytes $rangeStart-${totalBytes - 1}/$totalBytes"
                } else null
            } else if (code == 206) {
                // 少数 CDN 会"就近对齐"返回比请求更早的起点：多出来的字节要丢掉，
                // 否则播放器听到的是提前几百毫秒的声音，和进度条对不上。
                val upStart = parseStartFromContentRange(contentRange)
                if (isSeek && upStart >= 0 && upStart < rangeStart) {
                    val drop = rangeStart - upStart
                    runCatching { skipFully(stream, drop) }
                    bodyLen = if (bodyLen > 0) (bodyLen - drop).coerceAtLeast(-1L) else -1L
                }
            }

            // ────────────────────────────────────────────────────────────
            // 6.6.7：**残片识别**（用户报「知我 / 樱花草 只响 6 秒」）。
            //
            // 有些渠道（咪咕的"全曲试听"、QQ 的试听片段）给的是一个**合法的 mp3 文件**，
            // 只是长度只有几秒到几十秒。它格式完全正确、也能播，播放器根本不知道
            // 后面还有没有 —— 于是进度条按歌曲时长走，声音几秒就没了。
            //
            // 【6.7.1 再修——seek 一律不判残片】
            // 用户现场："不管谁的歌，拖到中间就暂停、然后播放失败。"
            // 拖动时上游回 206，contentLengthLong 是**剩余段**长度，越往后拖越小；
            // 只要某次解析没拿到 Content-Range 的总长（部分 CDN 给 `*`），
            // 这段剩余长度就会被当成"整首只有 300KB" → 判死 → 502 → 播放器报错。
            // 现在 seek 请求**完全不参与残片判定**，只对"从 0 开始的整首请求"判。
            // ────────────────────────────────────────────────────────────
            if (!isSeek && totalBytes in 1..MIN_FULL_AUDIO_BYTES && audio?.format != "ogg") {
                Log.w(TAG, "疑似残片，跳过本次转发：$id total=$totalBytes url=${upstreamUrl.take(80)}")
                runCatching { realConn.disconnect() }
                // 6.7.1：**不再记 noteUnplayable**。
                // "这首只有试听片段"和"这首没版权"是两件事，记进不可播表会让播放器
                // 把所有后续错误都判成"无版权"，进而整单连跳。
                val resp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "unplayable")
                resp.addHeader(HEADER_UNPLAYABLE, "1")
                return resp
            }

            // NanoHTTPD 的 chunked 用线程池逐块转发；对离散 seek 而言足够
            val response = if (bodyLen > 0) {
                newFixedLengthResponse(status, mime, stream, bodyLen)
            } else {
                newChunkedResponse(status, mime, stream)
            }
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Accept-Ranges", "bytes")
            contentRange?.let { response.addHeader("Content-Range", it) }
            response
        } catch (e: Exception) {
            Log.w(TAG, "proxy audio failed: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "audio error")
        }
    }

    companion object {
        private const val TAG = "AudioProxy"

        /**
         * 6.6.5：完整曲目的最小体积门槛（字节）。
         *
         * 400KB ≈ 128kbps 下的 25 秒。正规歌曲（哪怕是最短的纯音乐）也在 2MB 以上，
         * 只有「试听片段」才会低到这个量级。低于它的整文件直接判残片。
         *
         * 只在**上游明确给了 Content-Length**（len > 0）时才判，chunked 响应不受影响。
         */
        private const val MIN_FULL_AUDIO_BYTES = 400L * 1024L

        /**
         * 6.7.1：上游不认 Range 时，代理自己最多愿意"读掉"多少字节来模拟 seek。
         *
         * 16MB 约等于一首无损整轨。超过还跳，说明这个源的文件异常大或偏移离谱，
         * 与其让用户等半分钟，不如快速失败、让播放器走重解析换源。
         */
        private const val MAX_SELF_SEEK_BYTES = 16L * 1024L * 1024L

        /** 6.7.1：模拟 seek 时最多愿意花多久（超过就快速失败，别让用户干等） */
        private const val SKIP_TIMEOUT_MS = 4_000L

        /**
         * 6.6.9：解析结果过期后，旧地址作为"陈旧兜底"还能再用的时长（2 小时）。
         *
         * 过期 ≠ 失效：脚本音源给的 CDN 直链真实有效期普遍是几小时，
         * 真失效了还有 403→重解析那条路兜着。绝不能因为缓存 TTL 到点，
         * 就把一首正在听的歌送回"重新解析（可能赶上限流）→ 判死 → 连跳"的赌局。
         */
        private const val STALE_MS = 2L * 60 * 60 * 1000

        /**
         * 6.3.2 起播提速：每首歌的「加速代次」。
         *
         * 问题：ExoPlayer 播放的是 `127.0.0.1/audio?id=…`，这个 URL 在列表构造时就固定了。
         * 一旦上一轮解析失败（或地址过期 → 502），这一轮的 URL 里还带着同样的参数，
         * 播放器重建数据源时拿到的还是同一个地址；而我们的缓存已经把"失败"记下来了，
         * 于是用户看到的是"点了歌要愣好几秒才出声/直接跳无版权"。
         *
         * 做法：起播/重试时把代次 +1，[audioUrl] 把它拼进查询串（代理不认这个参数，
         * 只影响 ExoPlayer 的缓存键），并顺手清掉该曲的失败缓存，下一次请求就是干净的。
         */
        private val urlEpoch = java.util.concurrent.ConcurrentHashMap<String, Int>()

        /**
         * 6.6.6：`歌曲 ID → 歌名/歌手/时长` 的进程级登记表。
         *
         * **这是「点歌要等好几秒」的真正根因。**
         *
         * [prefetch] 原来只拿到一串 ID（`prefetch(ids)`），调解析时只能写
         * `resolveAudio(id)` —— 而脚本链路是**按歌名搜**的，没有歌名就等于没法解析，
         * 直接返回 null。也就是说：**预取从来没成功过一次**，永远白跑。
         *
         * 更糟的是它还占着 in-flight 位：播放器来要音频时要先等这个注定失败的预取，
         * 等到 null 才自己重新完整解析一遍。用户耳朵里就是"点了要等好几秒"。
         *
         * 现在 [audioUrl] 构造播放地址时（列表装配阶段，歌名歌手都在手上）顺手登记；
         * 预取查这张表补齐歌名歌手，一次就能解析成功 —— 真正实现"点下去就有声"。
         */
        private val songMeta = java.util.concurrent.ConcurrentHashMap<String, SongMeta>()

        /** 一首歌的最小解析凭据：脚本链路按「歌名 + 歌手」搜，缺一不可 */
        data class SongMeta(
            val title: String = "",
            val artist: String = "",
            val durationMs: Long = 0L
        )

        private fun rememberSongMeta(songId: String, meta: SongMeta) {
            if (songId.isBlank() || meta.title.isBlank()) return
            songMeta[songId] = meta
            // 上限保护：长时间连续播放会累积，超了就把最早的一批清掉。
            // 只是优化用的缓存，清掉最多让预取退化回"现场解析"，不影响正确性。
            if (songMeta.size > 800) {
                runCatching {
                    val cursor = songMeta.keys.iterator()
                    repeat(200) { if (cursor.hasNext()) { cursor.next(); cursor.remove() } }
                }
            }
        }

        fun bumpEpoch(songId: String) {
            if (songId.isBlank()) return
            urlEpoch.merge(songId, 1) { a, b -> a + b }
            runCatching { instance?.invalidate(songId) }
        }

        /**
         * 6.7.0：App 回到前台时清掉"不可播"记账。
         *
         * 用户现场：后台待一会儿再进来，点什么都"无版权"，整张歌单连跳。
         * 这些判定本来是防抖用的，但它们把后台期间那几次网络抖动固化成了
         * "这首歌没有版权"，回到前台后必须重新判一次。
         *
         * 只清判定，不清地址缓存：地址本身大概率还有效，清了反而让下一次
         * 起播重新走一遍解析（更慢，而且可能又撞上限流）。
         */
        fun resetForForeground() {
            runCatching { instance?.unplayableAt?.clear() }
        }

        /**
         * 这首歌刚才是不是被代理判过"所有音源都拿不到地址"（R26）。
         *
         * 播放器侧的唯一可靠判据：204 会被 ExoPlayer 当成功吞掉，错误码里没有状态码，
         * 只能反过来问代理。5 分钟内有效——够覆盖"点了这首、报错、跳歌"这一小段时间，
         * 又不会把一次临时抽风永久记成无版权。
         */
        fun isUnplayable(songId: String): Boolean {
            if (songId.isBlank()) return false
            val t = instance?.unplayableAt?.get(songId) ?: return false
            // 6.7.1：窗口从 5 分钟缩到 30 秒。5 分钟太长——一次失败会让这首在
            // 整个"点它、报错、再点它"的过程中一直被判定为无版权。
            return android.os.SystemClock.elapsedRealtime() - t < 30_000L
        }

        /** 用户点「重试」：把这首歌的不可播记账与地址缓存一起清掉，重新给一次机会 */
        fun clearUnplayableFor(songId: String) {
            if (songId.isBlank()) return
            val s = instance ?: return
            s.clearUnplayable(songId)
            s.invalidate(songId)
        }

        /** 解析不到任何可用地址（真·无版权 / 全部源都空）时带的响应头 */
        const val HEADER_UNPLAYABLE = "X-Jun-Audio"

        /** 上游拒绝（链接失效、防盗链、空文件）时带的响应头，值是上游状态码 */
        const val HEADER_UPSTREAM = "X-Jun-Upstream"
        @Volatile private var instance: AudioProxyServer? = null

        /** 幂等启动：进程内只绑定一次端口 */
        /** 实际监听端口（可能与 PROXY_PORT 不同：被占用时会顺延） */
        @Volatile
        var actualPort: Int = OnlineMusicApi.PROXY_PORT
            private set

        /**
         * 进程级一次性访问令牌。
         *
         * 代理虽然只绑 127.0.0.1，但同设备上**任何** App 都能请求这个端口，
         * 等于"装了 Jun Music 的手机上，谁都能拿它的代理拉音频"。
         * 加一个进程内随机 token：播放 URL 里带上，代理校验不过直接 403。
         * 只在内存里，杀进程即失效——持久化等于把钥匙留在门上。
         *
         * 放在 companion 而不是实例里：歌曲 URL 可能在代理真正 start 之前就构造好了，
         * 令牌必须是"任何时候读到都一样"的进程级常量，否则先构造的 URL 会全部变成 403。
         */
        val ACCESS_TOKEN: String = java.util.UUID.randomUUID().toString().replace("-", "")

        /** 当前进程的一次性访问令牌，构造播放 URL 时必须带上 */
        val accessToken: String get() = ACCESS_TOKEN

        /** 构造带令牌的代理播放地址（端口用实际监听端口，不用固定端口） */
        fun audioUrl(
            songId: String,
            title: String = "",
            artist: String = "",
            /** 6.6.6：原平台时长（毫秒）。用于脚本链路「换艺名认歌」，0=不参与判定 */
            durationMs: Long = 0L
        ): String {
            val base = "http://127.0.0.1:$actualPort/audio?id=$songId&t=$ACCESS_TOKEN"
            // 6.6.6：列表装配阶段就把歌名歌手记下来，供后面的预取直接用（见 [songMeta]）
            rememberSongMeta(songId, SongMeta(title, artist, durationMs))
            // 代次：让"重试同一首"能拿到一个全新的 URL（见 [bumpEpoch]）
            val epoch = urlEpoch[songId] ?: 0
            val withEpoch = if (epoch == 0) base else "$base&e=$epoch"
            if (title.isBlank() && artist.isBlank()) return withEpoch
            // 歌名/歌手用于没版权时的跨曲库兜底；空格转 %20，避免 NanoHTTPD 把 + 解成空格
            val tn = java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20")
            val an = java.net.URLEncoder.encode(artist, "UTF-8").replace("+", "%20")
            val du = if (durationMs > 0L) "&du=$durationMs" else ""
            return "$withEpoch&tn=$tn&an=$an$du"
        }

        @Synchronized
        fun start(context: Context? = null) {
            if (instance != null) return
            // 固定端口可能被其它 App / 残留进程占着：从 PROXY_PORT 起最多试 20 个，
            // 占用就顺延。以前只试一次，起不来就整 App 没声音。
            for (p in OnlineMusicApi.PROXY_PORT until OnlineMusicApi.PROXY_PORT + 20) {
                val ok = runCatching {
                    val server = AudioProxyServer(p, context)
                    server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                    instance = server
                    actualPort = p
                    true
                }.getOrElse { Log.w(TAG, "start on $p failed: ${it.message}"); false }
                if (ok) return
            }
            Log.w(TAG, "proxy unavailable: all candidate ports busy")
        }

        @Synchronized
        fun stop() {
            runCatching { instance?.stop() }
            instance = null
        }

        /** 6.7.2：用户点歌时调用——立即解析，不排队（见 [AudioProxyServer.prefetchNow]） */
        fun prefetchNow(ids: List<String>) {
            runCatching { instance?.prefetchNow(ids) }
        }

        /** 提前解析后续曲目地址；代理没起来就什么都不做（不影响播放） */
        fun prefetch(ids: List<String>) {
            instance?.prefetch(ids) ?: run {
                // 代理未就绪：地址反正要在真正播放时解析，这里直接忽略
            }
        }

        /**
         * 某首歌上一次解析到的真实档位/格式（播放页徽章用）。
         * 未解析过返回 null，界面按「在线」显示，与 5.0 一致。
         */
        fun resolvedOf(songId: String): ResolvedAudio? =
            runCatching { instance?.cachedAudio(songId) }.getOrNull()

        /**
         * 解析完成回调（5.2 播放页音质切换动画用）：歌曲 ID + 真实拿到的档位/格式。
         *
         * 由 [PlayerViewModel] 注册；在 socket / 预取线程触发，注册方负责切主线程。
         * 用 null 注销，避免 ViewModel 销毁后还挂着一条引用。
         */
        @Volatile
        var onResolved: ((String, ResolvedAudio) -> Unit)? = null

        /**
         * 6.3.2：把「已经解析好的地址」直接塞进代理的查表缓存。
         *
         * 用途：切音质时先在后台把新档位解析出来（这一步可能 1~3 秒），
         * 解析成功后再让播放器换源。如果等播放器发请求时才解析，播放器只等 8 秒，
         * 高档位链路一长就直接判超时——也就是用户看到的「一换音质就网络断了」。
         * 先把结果喂进来，换源那一刻 /audio 是**内存命中**，毫秒级返回，不断音。
         */
        fun seedResolved(id: String, audio: ResolvedAudio) {
            if (id.isBlank()) return
            runCatching { instance?.putAudio(id, audio) }
        }

        /**
         * 清空解析结果（设置页改音质档位时调用）。
         *
         * 缓存里存的是「按旧档位解析出来的地址」，不清的话换档后前 10 分钟里
         *  prefetched 过的那几首还会按老地址播。正在播的曲目不受影响——
         * 它已经把地址交给 ExoPlayer 了，这里只动查表。
         */
        fun clearResolved() {
            runCatching {
                instance?.let { s ->
                    synchronized(s.cacheLock) { s.urlCache.clear() }
                }
            }
        }
    }
}
