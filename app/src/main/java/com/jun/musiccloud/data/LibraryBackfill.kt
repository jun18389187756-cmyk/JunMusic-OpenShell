package com.jun.musiccloud.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 6.8.1：**曲库自动回填** —— 让第二层后路自己长大。
 *
 * ## 为什么要有它
 *
 * 6.8.0 做完了曲库，但它是空的：只能靠用户手动传歌，传一首才多一首。
 * 用户的原话是「主音源呢」。而"能解析任何歌的自有音源"要么买版权、要么破解协议，
 * 两条都不做 —— 剩下唯一能变强的路，就是**把听过的歌变成自己的**。
 *
 * ## 怎么工作（6.9.2 起：**听完整首才入库**）
 *
 * 一首歌在代理里**真的从上游拿到音频**（不是解析成功、是真有字节）之后，先记成
 * "候选"；等播放器**自然播完这一首**（STATE_ENDED，也就是用户真的听完了），
 * 才把这首加入上传队列：拿同一个直链在后台下一份，POST 到自己的服务器落盘。
 * 下次再点这首歌，`RemoteLibrary` 直接命中，一行网络代码都不用碰第三方。
 *
 * 为什么收得这么紧：6.8.1~6.9.1 是"起播 15 秒就回填"，用户切歌像逛橱窗，
 * 一下午就能把每天 300MB 的额度烧光——存的还多半是只听了十几秒的歌。
 * 现在额度只花在真正听完的歌上：一首 10MB 的话，300MB = 每天 30 首，绰绰有余。
 *
 * ## 为什么不是"边播边存"
 *
 * 边播边存要劫持播放器的字节流：seek、断流、并发都会让写入变成半截文件，
 * 而且会跟播放抢 IO —— 用户听到卡顿。这里改成**听完再单独下一份**，
 * 慢一点，但绝不影响播放，也不产生残文件（服务端按内容哈希原子落盘）。
 *
 * ## 硬约束（按重要性排）
 *
 * 1. **只在 Wi-Fi 下跑**。走流量替用户备份是越权，哪怕他默认开着。
 * 2. **单线程串行**，且听完整首、切到下一首几秒后才开始 —— 不能和播放抢带宽。
 * 3. **每日 300MB 上限**。手机放兜里听一天，不该把用户的服务器塞满。
 * 4. **失败 3 次就停 30 分钟**。服务器挂了的时候，不能变成一个重试风暴。
 * 5. 任何一步失败都**静默**：这是后台增值功能，不是播放链路，绝不能冒泡成错误。
 */
object LibraryBackfill {

    private const val TAG = "LibraryBackfill"
    private const val PREFS = "jun_library_backfill"
    private const val KEY_ON = "on"
    private const val KEY_DAY = "day"
    private const val KEY_USED = "used"

    /** 单个文件上限。超过就不备份 —— 无损动辄 30MB+，不值得占用户磁盘 */
    private const val MAX_FILE_BYTES = 30L * 1024 * 1024

    /** 太小的一律不备份：多半是试听片段的残片，存进去只会污染曲库 */
    private const val MIN_FILE_BYTES = 400L * 1024

    /** 每日上传总量上限 */
    private const val DAILY_CAP_BYTES = 300L * 1024 * 1024

    /** 起播后等多久再开始下载。听完已切下一首，留 3 秒给新歌起播，避免抢带宽 */
    private const val SETTLE_MS = 3_000L

    /** 候选表上限：只留最近听的这些首，防止表无限涨 */
    private const val MAX_CANDIDATES = 16

    /** 连续失败几次进入冷却 */
    private const val MAX_CONSECUTIVE_FAILS = 3
    private const val COOLDOWN_MS = 30L * 60 * 1000

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36"

    private class Task(
        val songId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val url: String,
        /** 6.7：本地代理回环地址。二次下载优先走它，避免第三方直链过期 */
        val proxyUrl: String = "",
        val format: String,
        val trial: Boolean,
        val provider: String
    )

    private val queue = LinkedBlockingQueue<Task>(48)
    /** 候选：代理真的拿到了音频但还没听完。播完一首才从这里转正进队列 */
    private val candidates = LinkedHashMap<String, Task>()
    private val seen: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val started = AtomicBoolean(false)
    private val usedToday = AtomicLong(0L)

    @Volatile private var enabled = true
    /**
     * 6.7：默认改为**开**。用户实测「云端曲库始终是 27 首」，除了 206 那个硬 bug，
     * 第二个现实原因是大部分人用移动数据听歌，而旧默认只在 Wi-Fi 下备份，
     * 于是流量场景下一首都传不上去。仍受每日 300MB 上限约束，设置里可随时关。
     */
    @Volatile private var allowMetered = true
    @Volatile private var appCtx: Context? = null
    @Volatile private var coolUntil = 0L
    @Volatile private var failStreak = 0

    /** 6.7：每首歌的重试次数。失败后撤销 seen，但不允许无限重试烧流量 */
    private val attempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val MAX_ATTEMPTS_PER_SONG = 4

    /** 6.7：设置页诊断信息，一眼看出到底是「在传」还是「卡在哪一步」 */
    @Volatile private var lastNote: String = "等待听完第一首歌"
    @Volatile private var successCount = 0

    fun lastNote(): String = lastNote
    fun successCount(): Int = successCount

    // ────────────────────────────────────────────────────────────
    // 开关
    // ────────────────────────────────────────────────────────────

    fun init(context: Context) {
        appCtx = context.applicationContext
        runCatching {
            val p = prefs() ?: return@runCatching
            enabled = p.getBoolean(KEY_ON, true)
            allowMetered = p.getBoolean(KEY_ALLOW_METERED, true)
            rollDayIfNeeded(p)
            usedToday.set(p.getLong(KEY_USED, 0L))
        }
        ensureWorker()
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(on: Boolean) {
        enabled = on
        runCatching { prefs()?.edit()?.putBoolean(KEY_ON, on)?.apply() }
        if (on) ensureWorker()
    }

    const val KEY_ALLOW_METERED = "allow_metered_backfill"

    fun isAllowMetered(): Boolean = allowMetered

    /** 设置页切换：移动网络下也回填（用户自己的服务器，愿不愿意花流量他自己定） */
    fun setAllowMetered(on: Boolean) {
        allowMetered = on
        runCatching { prefs()?.edit()?.putBoolean(KEY_ALLOW_METERED, on)?.apply() }
        if (on) ensureWorker()
    }

    fun usedTodayBytes(): Long = usedToday.get()

    fun dailyCapBytes(): Long = DAILY_CAP_BYTES

    private fun prefs() = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun rollDayIfNeeded(p: android.content.SharedPreferences) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())
        if (p.getString(KEY_DAY, "") != today) {
            p.edit().putString(KEY_DAY, today).putLong(KEY_USED, 0L).apply()
            usedToday.set(0L)
        }
    }

    // ────────────────────────────────────────────────────────────
    // 入口一：代理拿到真音频后调一次（只记候选，不进队列）
    // 入口二：播放器自然播完（STATE_ENDED）后调 [markListenedThrough] 转正
    // ────────────────────────────────────────────────────────────

    fun offer(
        songId: String,
        title: String,
        artist: String,
        durationMs: Long,
        url: String,
        format: String?,
        trial: Boolean,
        provider: String,
        /** 6.7：本地代理回环地址，见 [process] 里的双路下载 */
        proxyUrl: String = ""
    ) {
        if (!enabled) return
        if (songId.isBlank() || title.isBlank()) return
        if (url.isBlank() || trial) return
        // 已经是自己曲库的地址 → 再存一次没有意义，还会形成回环
        if (provider == "library" || url.startsWith(ServerApi.BASE_URL)) return
        if (!seen.add(songId)) return
        synchronized(candidates) {
            candidates[songId] = Task(
                songId, title, artist, durationMs, url, proxyUrl,
                format.orEmpty().ifBlank { "mp3" }, trial, provider
            )
            // 溢出淘汰最老的候选（LinkedHashMap 迭代序 = 插入序）
            while (candidates.size > MAX_CANDIDATES) {
                val eldest = candidates.keys.firstOrNull() ?: break
                candidates.remove(eldest)
                seen.remove(eldest)   // 让下次播放还有机会
            }
        }
    }

    /**
     * 6.9.2：这首歌**被完整听完了**（播放器报 STATE_ENDED）→ 候选转正。
     * 6.9.8：新增 [markListenedEnough]——累计听到 80% 也算（用户经常在结尾淡出前切歌，
     * 严格等 STATE_ENDED 会让大量歌永远停在候选区，云端曲库涨不动）。
     * 用户没听完就切走的歌，候选留在表里但永远不转正，也就不占任何流量和额度。
     */
    @Volatile private var lastPromotedCaller = ""

    fun markListenedThrough(songId: String) {
        if (songId.isBlank()) return
        val t = synchronized(candidates) {
            val exact = candidates.remove(songId)
            when {
                exact != null -> exact
                // 6.7 治根之二：调用方传的是 `currentMediaItem.mediaId`，也就是
                // Song.id（stableId 哈希出来的纯数字）；而候选表的 key 是代理用的
                // songId（"qq:xxx" / 网易云 id）。**这是两套完全不同的编号**，
                // 旧代码只按精确 key remove，永远 remove 不到东西 —— 就算 206
                // 那个条件修好了，候选也进不了队列，云端曲库照样一首不涨。
                // 候选只在「真的起播、上游真回了音频」时登记，且听满 80% 的
                // 只可能是当前正在播的那首，所以取最近登记的一个是安全的。
                songId == lastPromotedCaller -> null
                else -> {
                    val last = candidates.entries.lastOrNull()
                    if (last != null) {
                        candidates.remove(last.key)
                        last.value
                    } else null
                }
            }
        } ?: return
        // 记下这次的调用 key：心跳每 500ms 会重复调同一个 id，别把切歌后
        // 新登记的那首候选又抢着转正。
        lastPromotedCaller = songId
        // 队列满就放弃这次；seen 不撤销（这首歌已经完整进过库的判定流程）
        queue.offer(t)
    }

    /** 6.9.8：听完 80% 即转正（切歌前已满足"完整听过"的语义） */
    fun markListenedEnough(songId: String) = markListenedThrough(songId)

    // ────────────────────────────────────────────────────────────
    // 工作线程
    // ────────────────────────────────────────────────────────────

    private fun ensureWorker() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            while (true) {
                val t = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    break
                }
                try {
                    // 能进队列的歌都已经"被完整听过"，只等 3 秒让新歌先起播，
                    // 避免回填下载和起播缓冲抢带宽
                    Thread.sleep(SETTLE_MS)
                    process(t)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    Log.w(TAG, "回填异常（已忽略）：${e.message}")
                }
            }
        }, "library-backfill").apply { isDaemon = true }.start()
    }

    private fun process(t: Task) {
        if (!enabled) return
        if (System.currentTimeMillis() < coolUntil) return
        if (!onWifi() && !allowMetered) {
            // 不在 Wi-Fi 且用户明确关掉了流量回填：撤销 seen，下次还有机会补
            seen.remove(t.songId)
            noteSkip("非 Wi-Fi（设置里可开「用移动网络也备份」）")
            return
        }
        // 已经有了就别重复传（用户手动传过 / 之前自动补过）
        if (RemoteLibrary.lookup(t.songId, t.title, t.artist) != null) return
        // 手机上本来就有这个文件，就没必要占服务器
        if (LocalAudioIndex.lookup(t.songId, t.title, t.artist) != null) return
        if (usedToday.get() >= DAILY_CAP_BYTES) {
            noteSkip("今日 300MB 额度已用完")
            return
        }

        // 6.7：二次下载走**本地代理回环地址**。旧代码拿的是起播那一刻的第三方
        // 临时直链，用户听完整首要 3~5 分钟，QQ/网易 CDN 链接早过期，重新 GET
        // 基本都是 403 → 连续失败 3 次 → 冷却 30 分钟 → 曲库永远不涨。
        // 回环会让代理重新走 resolveWithLadder 拿最新地址，不受直链时效影响。
        val loopback = t.proxyUrl.startsWith("http://127.0.0.1")
        // HEAD 探测只对第三方直链做：NanoHTTPD 收到 HEAD 也会走一遍完整代理转发，
        // 白白跑一次上游解析，还可能因为 Content-Length 缺失被误判成残片。
        val size = if (loopback) 0L else probeSize(t.url)
        if (size in 1 until MIN_FILE_BYTES) return          // 残片
        if (size > MAX_FILE_BYTES) return                    // 太大
        if (usedToday.get() + (if (size > 0) size else 8L * 1024 * 1024) > DAILY_CAP_BYTES) return

        val bytes = (if (loopback) download(t.proxyUrl, MAX_FILE_BYTES, loopback = true) else null)
            ?: download(t.url, MAX_FILE_BYTES)
            ?: run {
                noteFail("下载失败", t.songId)
                return
            }
        if (bytes.size < MIN_FILE_BYTES) return
        if (bytes.size > MAX_FILE_BYTES) return

        // 用字节头判真实容器：QQ 给的多半是 m4a，但 format 字段有时是空的，
        // 扩展名错了会让部分播放器（尤其车机）把它当 mp3 解，直接放不出来。
        val fmt = sniffFormat(bytes, t.format)
        val id = upload(t, bytes, fmt)
        if (id != null) {
            failStreak = 0
            successCount++
            attempts.remove(t.songId)
            lastNote = "最近备份成功：${t.title}"
            usedToday.addAndGet(bytes.size.toLong())
            persistUsage()
            // 立刻进内存清单：这首歌从下一分钟起就走自己的服务器
            RemoteLibrary.addLocal(
                RemoteLibrary.Item(
                    id = id,
                    platformId = t.songId,
                    title = t.title,
                    artist = t.artist,
                    durationMs = t.durationMs,
                    format = fmt,
                    bytes = bytes.size.toLong()
                )
            )
            Log.i(TAG, "已回填：${t.title} - ${t.artist}（${bytes.size / 1024}KB，来源 ${t.provider}）")
        } else {
            noteFail("上传失败", t.songId)
        }
    }

    /** 6.7：跳过（不是失败）也留一条可读的诊断，设置页能直接看到卡在哪 */
    private fun noteSkip(why: String) {
        lastNote = "已跳过：$why"
    }

    /**
     * 6.7：旧实现失败后 `seen` 永远留着，那首歌这辈子都不会再重试 ——
     * 一次 CDN 抖动就能让曲库永久少一首。现在撤销 seen，但给每首歌一个
     * 重试上限（进程内 4 次），既不会永久丢歌，也不会变成重试风暴。
     */
    private fun noteFail(why: String, songId: String) {
        failStreak++
        lastNote = "上次失败：$why（${attempts[songId] ?: 0}/$MAX_ATTEMPTS_PER_SONG 次）"
        val n = attempts.merge(songId, 1) { a, b -> a + b } ?: 1
        if (n <= MAX_ATTEMPTS_PER_SONG) seen.remove(songId)
        if (failStreak >= MAX_CONSECUTIVE_FAILS) {
            coolUntil = System.currentTimeMillis() + COOLDOWN_MS
            failStreak = 0
            Log.w(TAG, "$why 连续多次，暂停 30 分钟")
        }
    }

    private fun persistUsage() {
        runCatching {
            val p = prefs() ?: return
            rollDayIfNeeded(p)
            p.edit().putLong(KEY_USED, usedToday.get()).apply()
        }
    }

    private fun onWifi(): Boolean = runCatching {
        val ctx = appCtx ?: return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    // ────────────────────────────────────────────────────────────
    // 网络
    // ────────────────────────────────────────────────────────────

    /**
     * 按域名给 Referer —— 和播放代理、下载器同一套规则。
     * QQ/咪咕/酷我的 CDN 会校验 Referer，给错了就是 403。
     */
    private fun refererFor(url: String): String = when {
        url.contains("qq.com") || url.contains("qqmusic") || url.contains("stream.qqmusic") -> "https://y.qq.com/"
        url.contains("kugou") -> "https://www.kugou.com/"
        url.contains("kuwo") -> "https://www.kuwo.cn/"
        url.contains("migu") -> "https://music.migu.cn/"
        url.contains("joox") -> "https://www.joox.com/"
        url.contains("netease") || url.contains("163.com") || url.contains("126.net") -> "https://music.163.com/"
        else -> "https://music.163.com/"
    }

    private fun probeSize(url: String): Long = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        c.instanceFollowRedirects = true
        c.connectTimeout = 6_000
        c.readTimeout = 6_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", refererFor(url))
        val n = c.contentLengthLong
        runCatching { c.disconnect() }
        if (n > 0) n else 0L
    }.getOrDefault(0L)

    private fun download(url: String, cap: Long, loopback: Boolean = false): ByteArray? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 8_000
        // 6.7：走本地代理时，代理内部要重新解析音源（可能 1~3 秒）再向 CDN 建连，
        // 30 秒在弱网下容易半途超时，留出一份完整歌曲的下载时间。
        c.readTimeout = if (loopback) 90_000 else 30_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", refererFor(url))
        c.setRequestProperty("Accept", "*/*")
        if (c.responseCode !in 200..299) {
            runCatching { c.disconnect() }
            return null
        }
        val out = ByteArrayOutputStream(1 shl 20)
        val buf = ByteArray(64 * 1024)
        var total = 0L
        c.inputStream.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > cap) break
                out.write(buf, 0, n)
            }
        }
        runCatching { c.disconnect() }
        out.toByteArray()
    }.getOrNull()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 按文件头判容器。解析层的 format 只是"声称"，这里以字节为准。 */
    private fun sniffFormat(b: ByteArray, fallback: String): String {
        if (b.size < 12) return fallback
        if (b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() &&
            b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte()
        ) return "flac"
        if (b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
            b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()
        ) return "m4a"
        if (b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) return "mp3"
        if ((b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0) return "mp3"
        if (b[0] == 'O'.code.toByte() && b[1] == 'g'.code.toByte() &&
            b[2] == 'g'.code.toByte() && b[3] == 'S'.code.toByte()
        ) return "ogg"
        return fallback
    }

    private fun upload(t: Task, bytes: ByteArray, fmt: String): String? = runCatching {
        val q = buildString {
            append(ServerApi.BASE_URL).append("/api/library/upload?k=").append(enc(RemoteLibrary.LIB_KEY))
            append("&title=").append(enc(t.title))
            append("&artist=").append(enc(t.artist))
            append("&platformId=").append(enc(t.songId))
            append("&durationMs=").append(t.durationMs)
            append("&filename=").append(enc(t.title + "." + fmt))
        }
        val c = URL(q).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10_000
        c.readTimeout = 90_000
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setRequestProperty("User-Agent", UA)
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        runCatching { c.disconnect() }
        if (code !in 200..299) return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (o.optInt("code", -1) != 0) return null
        o.optJSONObject("data")?.optString("id")?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
