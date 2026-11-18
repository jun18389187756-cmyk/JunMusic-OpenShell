package com.jun.musiccloud.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 在线音乐 API 适配层（纯联网）。
 *
 * 数据源优先级：
 *  1. xfabe（node.api.xfabe.com）—— 榜单 / 搜索 / 歌词 / 详情，返回结构稳定、速度快。
 *  2. Meting（api.qijieya.cn）—— 兜底搜索与播放地址 302 重定向解析（最可靠）。
 *  3. 网易云官方接口（music.163.com/api）—— 封面 / 歌词 兜底。
 *
 * 播放地址：不在 UI 线程同步解析，交给本地音频代理 [com.jun.musiccloud.playback.AudioProxyServer]
 * 在真正起播时才解析真实 CDN 地址并带 Referer/UA 代理流，因此列表加载瞬间完成。
 */
object OnlineMusicApi {

    /** 本地音频代理端口（AudioProxyServer 固定绑定此端口） */
    const val PROXY_PORT = 8089

    private const val XFABE = "https://node.api.xfabe.com/api/wangyi"
    private const val METING = "https://api.qijieya.cn/meting"

    /**
     * 第二快源（6.5 新增）：与 [METING] 并行竞速，谁先给地址用谁。
     *
     * 为什么需要它：6.4 把顺序改成「快源优先」后，起播已经在 0.3 秒量级，
     * 但整条链路只剩 meting 一个快源 —— 它一挂、或者它这台机器到网易云的路由抽风，
     * 就会整体落回 GD（1.9 秒），等于一夜回到 6.4 之前。
     *
     * 实测同一批 10 首（2026-11）：
     *   qijieya  9 命中/10，中位 262ms
     *   injahow  9 命中/10，中位 207ms   ← 命中率不输，还更快
     * 所以这里不是「主备」，是两个对等源并行：谁先回用谁，另一个直接丢弃。
     * 代价是未命中一首歌时多发一个请求，但只发生在冷缓存、且两个都在 300ms 内结束。
     */
    private const val METING_ALT = "https://api.injahow.cn/meting"

    // ────────────────────────────────────────────────────────────────
    // 6.7.4：**内置 QQ 直连**（不依赖音源脚本，也不依赖 meting）。
    //
    // 起因：2026-11-06 实测，三条公共 API 全部失效 ——
    //   api.qijieya.cn/meting  → 200 但**空响应**
    //   api.injahow.cn/meting  → 同上
    //   music-api.gdstudio.xyz → 503
    //   node.api.xfabe.com     → 404
    // 也就是说"API 快链"整条路是死的，所有歌只能走音源脚本；
    // 而脚本是 QuickJS 跑第三方接口，慢且不稳定 —— 一旦脚本也抖，
    // 用户看到的就是"所有歌都没版权"。
    //
    // 这条内置链路是实测可用的：QQ 搜索接口给 songmid（0.2 秒），
    // 再用同一个 apikey 换直链（0.2 秒），返回的是 isure6.stream.qqmusic.qq.com
    // 的 m4a，支持 Range（206），拖进度条也正常。
    // 它和脚本是**并行**的，谁先给地址用谁，脚本依旧负责 QQ 没有的 VIP 歌。
    // ────────────────────────────────────────────────────────────────
    /** 6.9.0：最近一次解析成功的通道名（播放页"正在从 XX 缓冲"文案用） */
    @Volatile
    var lastProviderName: String = ""
        private set

    private const val QQ_SEARCH = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
    private const val QQ_CYAPI = "https://cyapi.top/API/qq_music.php"
    private const val QQ_CYAPI_KEY =
        "1ffdf5733f5d538760e63d7e46ba17438d9f7b9dfc18c51be1109386fd74c3a1"

    // 6.8.3：竞速池加宽 —— 2026-11-07 实测全量源后仅剩的两条活路，全部挂进来：
    //   helloworld（103.79.184.97）: 200 返回 QQ mp3 直链，与 cyapi 互为备份；
    //   vkeys.cn:                    接口活着，mid+quality 直接换链。
    // 两者都与 qqBuiltinResolve 共用 qqMidCache（songmid 只搜一次），换链各自独立，
    // cyapi 哪天变慢/变挂，它们自动顶上，用户无感。
    private const val HELLO_TX = "http://103.79.184.97/api/music/url"
    private const val HELLO_KEY = "6C1F-53W0-GRKI-EVFG"
    private const val VKEYS_TX = "https://api.vkeys.cn/v2/music/tencent/geturl"

    private const val NETEASE = "https://music.163.com/api"

    /**
     * 第二曲库（jOOX，港台版权覆盖好）。
     *
     * 网易云搜不到的、或者搜到但没版权（VIP 灰掉）的歌，用 jOOX 补：
     *  - 搜索：把 jOOX 结果并入网易云结果（仅在网易云结果很少时，避免重复刷屏）
     *  - 播放：网易云解析不出真实地址时，按「歌名 + 歌手」去 jOOX 找同名曲再解析
     *  - 歌词 / 封面：jOOX 自带，直接可用
     *
     * 走 GD Studio 的公开聚合端点（无需 key）。返回的 id 带 `joox:` 前缀 +
     * URL-safe Base64，避免 jOOX 原始 id 里的 + / = 在代理 URL 查询串里出问题。
     */
    private const val GD = "https://music-api.gdstudio.xyz/api.php"
    private const val JOOX_PREFIX = "joox:"
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** 首页/曲库默认拉取的榜单集合，每个榜单作为一类「歌单」 */
    val chartNames = listOf(
        "热歌榜", "新歌榜", "飙升榜", "原创榜",
        "华语金曲榜", "欧美新歌榜", "日韩新歌榜", "影视金曲榜",
        "网络热歌榜", "DJ舞曲榜", "说唱榜", "电音榜",
        "民谣榜", "摇滚榜", "轻音乐榜", "古典榜"
    )

    /** 搜索页展示的热门关键词 */
    val hotKeywords = listOf(
        "周杰伦", "林俊杰", "邓紫棋", "陈奕迅", "薛之谦",
        "毛不易", "李荣浩", "华晨宇", "许嵩", "张杰"
    )

    /** 供 ExoPlayer / 缓存层复用的 UA，与音频解析保持同一身份（避免同 App 两套 UA 触发风控） */
    fun userAgentForExo(): String = UA

    /** API 侧的原始歌曲信息，由 [MusicRepository] 转成 UI 用的 [Song] */
    data class RawSong(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val cover: String,
        val durationMs: Long
    )

    // ==================== 榜单 / 歌单 ====================

    fun fetchChart(name: String): List<RawSong> {
        val json = httpGet("$XFABE/musicChart?list=${enc(name)}") ?: return emptyList()
        val data = json.optJSONObject("data") ?: return emptyList()
        val songs = parseSongs(data.optJSONArray("songs"))
        if (songs.isEmpty()) return emptyList()
        // 榜单歌曲同样需要补封面（xfabe 不带 picurl），与搜索走同一套 enrich
        return dedupe(enrich(songs))
    }

    /** 榜单封面图（歌手头像 / 歌单卡片用） */
    fun fetchChartCover(name: String): String {
        val json = httpGet("$XFABE/musicChart?list=${enc(name)}") ?: return ""
        return json.optJSONObject("data")?.optString("listImg").orEmpty()
    }

    private fun parseSongs(arr: JSONArray?): List<RawSong> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<RawSong>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optString("id")
            if (id.isEmpty()) continue
            out.add(
                RawSong(
                    id = id,
                    title = s.optString("name"),
                    artist = s.optString("artistsname"),
                    album = s.optString("album"),
                    cover = s.optString("picurl"),
                    durationMs = s.optLong("duration")
                )
            )
        }
        return out
    }

    // ==================== 搜索 ====================

    /** 一页搜索结果：歌曲 + 是否还有下一页 */
    data class RawSongPage(val songs: List<RawSong>, val hasMore: Boolean)

    /**
     * 搜索一页。
     *
     * 首选网易云官方云搜索（cloudsearch/pc）：按热度排序，带完整封面 / 歌手 / 专辑 / 时长，
     * 并支持 offset 翻页——这是「搜完有结果、继续翻就变 0、大部分没封面」的根治方案。
     * 云搜索不可用时才退回 xfabe + Meting 的旧组合（只保证第一页）。
     */
    fun searchPage(keyword: String, limit: Int = 30, offset: Int = 0): RawSongPage {
        if (keyword.isBlank()) return RawSongPage(emptyList(), false)
        val per = limit.coerceIn(1, 100)
        val cloud = runCatching { NeteaseApi.searchPage(keyword, per, offset) }.getOrNull()
        if (cloud != null && cloud.songs.isNotEmpty()) {
            val raw = cloud.songs.map { s ->
                RawSong(
                    id = s.id,
                    title = s.title,
                    artist = s.artist,
                    album = s.album,
                    cover = s.cover,
                    durationMs = s.durationMs
                )
            }
            // 6.3 大修版：不再只在"结果太少"时补第二曲库——用户要求搜周杰伦这类
        // 无版权歌手时结果也要**很多**。网易云正常返回时也用 jOOX 搜索补齐去重，
        // 凑到 20 条以上；jOOX 挂了就不补，主搜索不受影响。
        return RawSongPage(dedupe(supplementJoox(raw, keyword)), cloud.hasMore)
        }
        if (offset > 0) return RawSongPage(emptyList(), false)
        val legacy = legacySearch(keyword, maxOf(per, 100))
        return RawSongPage(dedupe(supplementJoox(legacy, keyword)), false)
    }

    /**
     * 用 jOOX 把搜索结果补齐。
     *
     * 6.3 大修版：阈值从「< 5 条才补」放宽到「< 20 条就补」——搜周杰伦这类
     * 网易云没版权的歌手，网易云只回零星几首，用户看到的是"平台没歌"。
     * jOOX（GD 聚合）里有大量周杰伦曲目，补进来后一页能有几十条。
     * 去重按规范化标题，不会出现「周杰伦 / 周杰倫」两行同歌。
     * 播放层面另有兜底——网易云搜到但没版权的歌，起播时会自动去 jOOX 找同一首。
     */
    private fun supplementJoox(list: List<RawSong>, keyword: String): List<RawSong> {
        // 6.3.1：网易云已经给足一页就直接返回。原来即使拿到 30 条，也要在
        // supplementJoox 里干等 jOOX 5 秒（GD 聚合经常超时），这才是"搜索慢半拍"的真凶。
        // 只有结果偏少（无版权歌手 / 冷门词）才去补第二曲库，那是它该上场的时候。
        val need = when {
            list.size >= 24 -> return list
            list.size >= 12 -> 0
            else -> 20 - list.size
        }
        // 同一关键词 3 分钟内复用（翻第二页时不再重复打 jOOX）
        val ck = "joox|" + keyword.trim().lowercase()
        val hit = jooxSearchCache[ck]
        val extra = if (hit != null && System.currentTimeMillis() - hit.second < 180_000L) {
            hit.first
        } else {
            val r = jooxSearchWithTimeout(keyword, 30)
            jooxSearchCache[ck] = r to System.currentTimeMillis()
            if (jooxSearchCache.size > 60) jooxSearchCache.clear()
            r
        }
        // jOOX 现在只剩「补条数」的价值（它的 url 解析已失效），条数够了就不用再塞
        if (need <= 0 || extra.isEmpty()) return list
        val seen = HashSet<String>(list.size * 2)
        for (s in list) seen.add(normTitle(s.title))
        return list + extra.filter { seen.add(normTitle(it.title)) }
    }

    /** 搜索第一页（保留旧调用方） */
    fun search(keyword: String, limit: Int = 100): List<RawSong> =
        searchPage(keyword, limit, 0).songs

    /**
     * 旧搜索链路：xfabe 取排序 + Meting 取封面。
     *
     * 数据源策略（2025 实测）：
     *  - xfabe 搜索结构稳定、按相关度排序，但 **不返回封面**（picurl 字段缺失）。
     *  - Meting 搜索返回的 `pic` 字段是带 picId 的有效封面 URL（302 跳转真实 CDN），
     *    但排序非热度序。
     */
    private fun legacySearch(keyword: String, limit: Int): List<RawSong> {
        // 并发：xfabe 负责排序，Meting 负责封面
        val (xfabe, meting) = runCatching {
            runBlocking {
                coroutineScope {
                    val xfabeDef = async(Dispatchers.IO) {
                        runCatching {
                            val json = httpGet("$XFABE/search?search=${enc(keyword)}&limit=$limit")
                            parseSongs(json?.optJSONObject("data")?.optJSONArray("songs"))
                        }.getOrDefault(emptyList())
                    }
                    val metingDef = async(Dispatchers.IO) { metingSearch(keyword, limit) }
                    xfabeDef.await() to metingDef.await()
                }
            }
        }.getOrDefault(emptyList<RawSong>() to emptyList())

        // 按 songId 建封面映射（Meting 的 pic 是唯一可靠的封面来源）
        val coverMap = HashMap<String, String>(meting.size * 2)
        for (m in meting) {
            if (m.cover.isNotEmpty()) coverMap[m.id] = m.cover
        }

        // 主结果：优先用 xfabe（排序好），把 Meting 的封面按 ID 合并进去
        val out = if (xfabe.isNotEmpty()) {
            xfabe.map { s ->
                s.copy(cover = s.cover.ifBlank { coverMap[s.id].orEmpty() })
            }
        } else {
            // xfabe 挂了：直接用 Meting（自带封面，排序稍差但能用）
            meting
        }

        if (out.isEmpty()) return emptyList()
        // 补全剩余缺失字段：封面走网易云 song/detail 兜底（实测仍可返回 picUrl），
        // 不再用 Meting pic + songId 的错误兜底（songId 会 404）。
        return dedupe(enrich(out))
    }

    /** 去重：同一「歌名 + 歌手」只保留排序最靠前的一条（即热度最高的一条）。 */
    private fun dedupe(list: List<RawSong>): List<RawSong> {
        val seen = HashSet<String>(list.size * 2)
        return list.filter {
            val key = (it.title + "|" + it.artist).lowercase().replace(" ", "")
            seen.add(key)
        }
    }

    /** 详情补全缓存：同一首歌重复搜索（换关键词/回退历史）不再打网络 */
    private val detailCache = java.util.concurrent.ConcurrentHashMap<String, RawSong>()

    /**
     * 用网易云 song/detail 批量补全缺失字段。
     *
     * ids 必须是 URL 编码过的 `%5B1,2,3%5D`——原始方括号会被网关判成非法请求。
     * 单次最多 50 首，超过则分片并发；失败整片跳过（保留 xfabe 原始数据，不阻断搜索）。
     */
    private fun enrich(list: List<RawSong>): List<RawSong> {
        val need = list.filter {
            it.cover.isEmpty() || it.artist.isEmpty() || it.album.isEmpty() || it.durationMs <= 0
        }
        if (need.isEmpty()) return list
        val patch = HashMap<String, RawSong>()
        for (raw in need) detailCache[raw.id]?.let { patch[raw.id] = it }

        val missing = need.map { it.id }.filter { it !in patch }
        val chunks = missing.chunked(50)
        if (chunks.isNotEmpty()) {
            val fetched = runCatching {
                runBlocking {
                    coroutineScope {
                        chunks.map { chunk ->
                            async(Dispatchers.IO) { fetchDetailChunk(chunk) }
                        }.awaitAll()
                    }
                }
            }.getOrDefault(emptyList())
            for (m in fetched) patch.putAll(m)
        }

        return list.map { s ->
            val p = patch[s.id]
            // 封面兜底：优先用搜索阶段已合并的 Meting 封面；其次用网易云 song/detail
            // 返回的 album.picUrl（实测仍可拿到真实 CDN 地址）。
            // 注意：不能用 "$METING/?server=netease&type=pic&id=${s.id}" —— Meting pic
            // 接口需要的是 picId（专辑图 ID），传 songId 会返回 404。
            s.copy(
                title = s.title.ifBlank { p?.title.orEmpty() },
                artist = s.artist.ifBlank { p?.artist.orEmpty() },
                album = s.album.ifBlank { p?.album.orEmpty() },
                cover = s.cover.ifBlank { p?.cover.orEmpty() },
                durationMs = if (s.durationMs > 0) s.durationMs else p?.durationMs ?: 0L
            )
        }
    }

    private fun fetchDetailChunk(ids: List<String>): Map<String, RawSong> {
        val enc = URLEncoder.encode("[" + ids.joinToString(",") + "]", "UTF-8")
        val songs = httpGet("$NETEASE/song/detail/?ids=$enc", referer = "https://music.163.com/")
            ?.optJSONArray("songs") ?: return emptyMap()
        val out = HashMap<String, RawSong>()
        for (i in 0 until songs.length()) {
            val o = songs.optJSONObject(i) ?: continue
            val sid = o.optString("id")
            if (sid.isEmpty()) continue
            val artist = o.optJSONArray("artists")?.let { a ->
                (0 until a.length())
                    .mapNotNull { a.optJSONObject(it)?.optString("name") }
                    .filter { it.isNotBlank() }
                    .joinToString(",")
            }.orEmpty()
            val albumObj = o.optJSONObject("album")
            val song = RawSong(
                id = sid,
                title = o.optString("name"),
                artist = artist,
                album = albumObj?.optString("name").orEmpty(),
                cover = albumObj?.optString("picUrl").orEmpty(),
                durationMs = o.optLong("duration")
            )
            out[sid] = song
            // 无论有没有封面都缓存：没封面的歌下次搜索不再重复打网络
            // （之前只缓存有封面的，导致无封面歌每次搜索都重查一次 detail）。
            detailCache[sid] = song
        }
        return out
    }

    private fun metingSearch(keyword: String, limit: Int): List<RawSong> {
        val body = rawGet("$METING/?server=netease&type=search&id=${enc(keyword)}&limit=$limit") ?: return emptyList()
        return runCatching {
            val arr = JSONArray(body)
            val out = ArrayList<RawSong>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = Regex("id=(\\d+)").find(o.optString("url"))?.groupValues?.get(1) ?: continue
                out.add(
                    RawSong(
                        id = id,
                        title = o.optString("name"),
                        artist = o.optString("artist"),
                        album = "",
                        cover = o.optString("pic"),
                        durationMs = 0L
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    // ==================== 播放地址 ====================

    /**
     * 解析可播放的真实 CDN 地址（供本地代理使用，多级降级）：
     *  1. jOOX 曲目：直接走 jOOX 解析
     *  2. 网易云：Meting 302 → xfabe 详情 → outer/url
     *  3. 网易云整条链路都拿不到（VIP / 无版权）时，按「歌名 + 歌手」去 jOOX
     *     找同名曲兜底——这是「周杰伦能搜到但点开不能听」的主要解法。
     */
    /**
     * 解析可播放的真实 CDN 地址（保持 5.0 的签名与行为不变，老调用方一字不改）。
     *
     * @see resolveAudio 需要档位与真实格式信息时用这个
     */
    fun resolveAudioUrl(id: String, title: String = "", artist: String = ""): String? =
        resolveAudio(id, title, artist, AudioQuality.AUTO)?.url

    /**
     * 按档位解析音频（5.1 新增）。
     *
     * 只有「用户显式选了档位」且「本机已登录网易云」两个条件同时成立，才走官方
     * eapi 高档位接口；否则完全走下面的原四级链路，与 5.0 行为一致。
     * 官方接口拿不到（无版权 / VIP 到期 / 风控 / 断网）时**逐级降级**，最后落回原链路，
     * 保证「选了无损结果放不出歌」这种情况不会出现。
     */
    fun resolveAudio(
        id: String,
        title: String = "",
        artist: String = "",
        quality: AudioQuality = AudioQualityStore.current(),
        /**
         * 6.3：下载时强制要求的容器格式（"mp3" / "flac" / null=不限制）。
         *
         * 为什么要单独一个参数而不是只改 [quality]：
         * 下载和试听是两件事。试听跟着"播放音质"走（省流量），下载跟着"下载格式"走
         * （用户可能就想存一份无损）。硬把下载塞进播放音质里，会出现
         * "我只想下 MP3，结果试听也被降到 320k"的副作用。
         *
         * 拿不到指定格式时**不报错**，退回原链路——宁可给用户一个 MP3，
         * 也不能让"选了 FLAC 就下不了歌"。界面上见 [DownloadManager] 的提示文案。
         */
        preferFormat: String? = null,
        /**
         * 6.3.2：跳过 meting 快源，直接从 GD 要完整文件。
         *
         * 用于「已经确认当前这条是 30/45 秒残片」时的补救：这种情况下再走一遍
         * meting 只会拿到同一个残片，白白多等 0.3 秒。
         */
        skipFast: Boolean = false,
        /**
         * 6.6.6：这首歌在原平台的时长（毫秒），0=未知。
         *
         * 只给脚本链路用，作用是「跨平台换艺名」时认人（见 SourcePluginResolver.sameRecording）。
         * 老链路不需要它，所以放在最后、默认 0，不影响任何现有调用。
         */
        durationMs: Long = 0L,
        /**
         * 6.7.3：强制重试。
         *
         * true 时**忽略所有"上次失败"的记忆**（60 秒的整体失败缓存、脚本的 12 秒
         * "没搜到"缓存），老老实实再解析一遍。用户点重试、或播放侧自动换源时用。
         * 没有它的话，一次网络抖动会让这首歌在接下来一分钟里"永远没版权"。
         */
        force: Boolean = false
    ): ResolvedAudio? {
        // ────────────────────────────────────────────────────────────────
        // 6.9.6：导入歌单带来的「平台原生 id」。
        //
        // 背景：QQ / 酷狗歌单以前只能拿到「歌名 + 歌手」，必须逐首回网易云搜一遍，
        // 搜不到就丢，导入上限因此被卡在 60 首。现在服务端把 songmid / 酷狗 hash
        // 一起带回来，id 形如 `qq:0039MnYb0qxYhV` / `kg:807F9F…`。
        //
        // 这里必须**在所有网易云请求之前**截住：
        //   · qq: → 直接拿 songmid 换直链（0.2~0.5 秒），跳过"搜歌名猜是哪首"这一步，
        //           串歌风险归零（以前按歌名搜，同名的翻唱/Live 版会被误当成原曲）；
        //   · kg: → 酷狗没有公开换链接口，只能按歌名走脚本 / QQ，但绝不能把 hash
        //           当网易云 id 去请求 —— 那会白打一轮，还会污染 60 秒失败缓存。
        // ────────────────────────────────────────────────────────────────
        if (id.startsWith("qq:")) {
            val mid = id.removePrefix("qq:").trim()
            // 6.9.x：点播换链的必经之路，顺手登记元信息供歌词兜底按 qq:<mid> 反查
            //（歌单/榜单/导入的 QQ 歌没经过 qqSearchSongs，靠这里补齐）。
            rememberQqLyricMeta(id, title, artist, durationMs)
            if (mid.isNotEmpty() && SourceSwitches.qq) {
                runCatching { qqResolveByMid(mid) }.getOrNull()?.let { return it }
            }
            return resolveByNameOnly(mid, title, artist, durationMs, quality, preferFormat, force)
        }
        if (id.startsWith("kg:")) {
            return resolveByNameOnly(
                id.removePrefix("kg:").trim(),
                title, artist, durationMs, quality, preferFormat, force
            )
        }

        // ────────────────────────────────────────────────────────────────
        // 6.7.0：这一段的策略已经**反过来了**，旧注释（6.6.2「脚本是唯一音源」）留作历史：
        //
        // 6.6.x：有脚本就只走脚本，老 API 全关 —— 结果起播要 600~3000ms，
        //        而且脚本后端一限流，所有歌一起显示"没版权"。
        // 6.7.0：按用户要求「优先使用 API」，改成 API 快链与脚本链**并行竞速**
        //        （见 resolveHybrid）。老链路（meting/GD/官方）不再是"隐藏兜底"，
        //        而是第一梯队；脚本负责 API 拿不到的（无版权/VIP/周杰伦那类）。
        // ────────────────────────────────────────────────────────────────
        // 6.7.0：先等脚本装载完（冷启动/后台被杀回来时 scripts 还是空的，
        // 那一刻直接判"没有音源"会让整张歌单连跳，见 LxScriptHost.awaitReady）
        // 6.7.2：2500 → 1200。这里挡在每一次解析的最前面，冷启动第一次点歌
        // 白等 2.5 秒就是它。1.2 秒足够覆盖"脚本刚装载完"的窗口；
        // 还没装载完也不会误判（下面 API 通道照样能出地址）。
        val scriptsReady = com.jun.musiccloud.data.sourceplugin.LxScriptHost.awaitReady(1_200L)
        if (scriptsReady) {
            val hybridStart = System.currentTimeMillis()
            // ────────────────────────────────────────────────────────────
            // 6.7.0：**API 与音源插件双通道并行**（用户要求「优先使用 API」）。
            //
            // 6.6.x 的写法是「有脚本就只走脚本」，于是：
            //   · 脚本要给 QQ/咪咕/酷我挨个搜索 → 600~3000ms 才出声；
            //   · 脚本后端被限流时，所有歌一起"没版权"，而网易云公开接口明明是好的。
            //
            // 现在的策略：
            //   1) 普通音质：API 快链（meting 302，实测 0.3s）和脚本链**同时发**，
            //      谁先给出可播地址用谁 —— 热门歌基本都走 API，点下去就响；
            //   2) 脚本先回来（周杰伦这类 API 拿不到的）也立刻用，不必等 API 超时；
            //   3) 无损：只有脚本能给，以脚本为准，不给 API 抢跑；
            //   4) API 给的是 30/45 秒残片 → 不采用，继续等脚本的完整版。
            // ────────────────────────────────────────────────────────────
            val hybrid = resolveHybrid(
                id = id,
                title = title,
                artist = artist,
                quality = quality,
                preferFormat = preferFormat,
                skipFast = skipFast,
                durationMs = durationMs,
                force = force
            )
            if (hybrid != null) return hybrid
            // 两边都没给：老链路最后兜一次。
            // 但只在前面没花太久时间时才兜（否则一首死歌要等 12s 脚本 + 9s 老链路，
            // 用户体感就是"点了半天没反应"）。
            val spent = System.currentTimeMillis() - hybridStart
            if (spent < 6_000L) {
                val legacy = runCatching { resolveAudioUrlLegacy(id, title, artist) }.getOrNull()
                if (legacy != null) {
                    return ResolvedAudio(url = legacy, format = sniffFormat(legacy), provider = "legacy")
                }
            }
            return null
        }

        // R27：整条链路都失败的短缓存（60 秒）。
        //
        // 一首拿不到的歌，走完 meting → GD → 官方 → legacy → jOOX 要 10 秒上下；
        // 用户在这期间连点几次，每次都重新走一遍，界面看着就是"点了完全没反应"。
        // 记 60 秒，第二次点瞬间返回"放不了"，交给播放侧跳过 + 提示。
        // skipFast（残片补救）不查这张表：那条路是为了换一个结果，不能被旧失败挡住。
        //
        // 6.6.3：**只有在没有任何启用脚本时才会走到这里**（用户全停用了，或者脚本全挂了）。
        // 这时老链路是最后的兜底 —— 总比"一首都不能放"强。
        // 用户要求关掉老 API，指的是"脚本能用时不要偷偷回退"；
        // 脚本一个都没有的时候再关，等于让 App 变砖。
        val failKey = "$id|${quality.name}|${preferFormat.orEmpty()}"
        if (!skipFast && !force) {
            val until = hardFailUntil[failKey]
            if (until != null) {
                if (System.currentTimeMillis() < until) return null
                hardFailUntil.remove(failKey)
            }
        }
        val cookie = if (id.startsWith(JOOX_PREFIX)) "" else NeteaseStore.cookie
        val wantBr = when {
            preferFormat.equals("flac", ignoreCase = true) -> GD_BR_LOSSLESS
            preferFormat.equals("mp3", ignoreCase = true) -> GD_BR_320
            // 跟音质走：按当前播放音质映射到 GD 的 br
            else -> gdBrForQuality(quality)
        }

        // ────────────────────────────────────────────────────────────────
        // 6.4 起播提速（用户原话：「很多音乐点开之后很长时间才能播放，
        //       以前随便一点就是无缝衔接」）
        //
        // 6.3 的顺序是「GD 优先」，理由写在这里的旧注释里：GD 一次请求就给直链，
        // 比 meting → xfabe → outer/url 三次串行快。**这个判断在实测里是错的**：
        //
        //   同一批 6 首歌实测（本机 → 公网，取 3 次平均）
        //     meting 302 : 0.25 ~ 0.31 秒   命中 4/6
        //     GD  br=320 : 1.04 ~ 1.92 秒   命中 4/6
        //
        //   命中率一模一样，GD 慢 4~7 倍。而 meting 一旦 302 就直接给 CDN 直链，
        //   一次请求就够了 —— 根本不存在"三次串行"。
        //   更糟的是 GD 不返回 url 时我们还要再等它 1.3 秒才去问 meting，
        //   等于**每一首放不出来的歌都白白多等 1 秒多**。
        //
        // 新顺序：
        //   1. 只要「能放就行」(AUTO / 标准 / 高) → **meting 先问**，0.3 秒出结果；
        //   2. 用户明确要无损/母带 → GD 先问（只有它免登录给 FLAC），本来就要多等一次；
        //   3. meting 不命中 → GD 320 → 官方 eapi → xfabe/outer → jOOX 同名兜底。
        //
        // 每一步都是"上一步真的没结果才继续"，不会叠加等待。
        // ────────────────────────────────────────────────────────────────

        val wantsLossless = wantBr >= GD_BR_LOSSLESS ||
            quality == AudioQuality.LOSSLESS || quality == AudioQuality.HI_RES

        // 6.3.2：先记住「能听但是残片」的结果。
        // 它不会被直接返回，而是继续往下找完整版；全都找不到时再拿它兜底，
        // 保证用户至少能听，而不是从「45 秒残片」变成「直接放不出来」。
        var trialHit: ResolvedAudio? = null
        // 6.3.2：探测失败的结果单独放。它可能完整、也可能残片，
        // 不能当成完整版直接用（那样残片就漏了），也不能当成残片报给用户。
        var unknownHit: ResolvedAudio? = null

        if (wantsLossless) {
            // 无损档：只有 GD / 官方 eapi 能给，先问 GD（免登录也有效）
            gdResolve(id, GD_BR_LOSSLESS)?.let { return it }
        } else if (!id.startsWith(JOOX_PREFIX) && !skipFast) {
            // 试听主路径：meting 直连 302，实测 0.3 秒级
            val fast = fastDirectResolve(id)
            if (fast != null) {
                when {
                    // 确认是完整版（探测成功且文件够大）：直接给，和 6.3.1 一样快
                    !fast.trial && fast.sizeBytes > 0L -> return fast
                    // 确认是 30/45 秒残片：继续往下找完整版
                    fast.trial -> trialHit = fast
                    // 探测没成功（超时/被拒）：直接放行。
                    //
                    // 6.3.4 调整理由：主源已经换成「实测给完整文件」的 qijieya，
                    // 探测失败绝大多数是 CDN 抖动，不是残片。为了这 10% 的抖动
                    // 每次都多等 GD 的 1~2 秒，用户感知就是「点歌要等两秒」，
                    // 比偶尔听错更难受。真正的残片由播放侧「实际时长 vs 元数据」
                    // 兜底修正，最多响 2 秒就自动换完整版，用户几乎无感。
                    else -> return fast
                }
            }
        }

        if (wantBr > 0) {
            gdResolve(id, wantBr)?.let { return it }
        } else if (!id.startsWith(JOOX_PREFIX)) {
            gdResolve(id, GD_BR_320)?.let { return it }
        }
        // 下载要 FLAC 时再单独试一次官方无损档（有登录态时音源更准）
        if (preferFormat.equals("flac", ignoreCase = true) && cookie.isNotBlank()) {
            highQualityFallback(id, "lossless")?.let { return it }
        }
        // 下载要 MP3 时同理，直取 320k（exhigh）
        if (preferFormat.equals("mp3", ignoreCase = true) && cookie.isNotBlank()) {
            highQualityFallback(id, "exhigh")?.let { return it }
        }
        if (quality != AudioQuality.AUTO && cookie.isNotBlank()) {
            for (cand in AudioQuality.candidates(quality)) {
                if (cand.level.isBlank()) continue
                val hit = NeteaseEapi.songUrl(id, cand.level, cookie) ?: continue
                return ResolvedAudio(
                    url = hit.url,
                    level = hit.level ?: cand.level,
                    format = hit.format,
                    bitrate = hit.bitrate,
                    sizeBytes = hit.sizeBytes,
                    provider = "official"
                )
            }
        }
        // 6.3.2：走到这里说明「残片在手、完整版没找到」。
        // legacy / 同名替代两条链路本身要 2~4 秒，而用户此刻其实已经能听了——
        // 为了一个不保证存在的完整版把起播拖成 5 秒，比听 45 秒残片更难受。
        // 所以这里立刻交出手里的残片，由 UI 明确告诉用户「这首只有试听版」。
        // 手里有「能听」的结果时不再往下试慢链路：
        // 确认残片 → 明确告诉用户只有试听版；探测失败 → 当完整版放（不能误报）
        trialHit?.let { return it }
        unknownHit?.let { return it }
        val legacy = resolveAudioUrlLegacy(id, title, artist)
        if (legacy != null) return ResolvedAudio(url = legacy, format = sniffFormat(legacy))
        // ────────────────────────────────────────────────────────────────
        // R27：**不再自动换同名替代版本**。
        //
        // 6.3.1 加这一步的初衷是「周杰伦原版拿不到，给个同名版总比听不到强」。
        // 实际效果是用户按《青花瓷》点播放，出声的却是别人的翻唱/AI 版，
        // 界面上还显示周杰伦 —— 用户原话：「青花瓷是周杰伦唱的吗？怎么是别人唱的」。
        //
        // 在音乐 App 里「播错歌」比「播不了」严重得多：播不了会跳过下一首，
        // 播错歌用户会以为整个曲库都是假的。所以这里一刀切掉自动替代：
        // 按 ID 拿不到就是拿不到，交给播放侧判无版权 → 跳过 → 尾部给「搜其他版本」，
        // 让用户自己挑，而不是替用户决定。
        //
        // [altVersionResolve] 保留但不再被自动调用（只给显式入口/后续功能用）。
        // ────────────────────────────────────────────────────────────────
        // 6.7.3：60 秒 → 15 秒。它只该挡住"连点五次"这种高频重复，
        // 不该让一首歌在一分钟内都翻不了身。
        hardFailUntil[failKey] = System.currentTimeMillis() + 15_000L
        if (hardFailUntil.size > 200) {
            val now = System.currentTimeMillis()
            hardFailUntil.entries.removeIf { it.value < now }
        }
        return null
    }

    /**
     * 最快的解析路径：meting 302 → 直接给 CDN 直链。实测 0.25~0.31 秒。
     *
     * 与 [resolveAudioUrlLegacy] 里那一步的区别：这里**只做 meting**，不做后续兜底，
     * 所以调用方可以放心把它放在最前面——不命中就是 null，立刻进下一条链路，
     * 不会有"在一条慢链路上反复重试"的隐藏成本。
     *
     * 结果单独缓存 10 分钟（成功）/ 90 秒（失败）。缓存极其重要：
     * 用户从列表点歌时，[AudioProxyServer] 会在**同一毫秒**发起解析，
     * 播放器随后请求代理时读的就是这份缓存，不再二次阻塞。
     */
    private val fastCache = java.util.concurrent.ConcurrentHashMap<String, Pair<ResolvedAudio?, Long>>()

    /** R27：整条链路都失败的短缓存（60 秒），避免同一首死歌每点一次都等十几秒 */
    private val hardFailUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 用户点「重试」：把这首歌的失败缓存清掉，否则短时间内重试等于没点 */
    fun clearHardFail(id: String) {
        if (id.isBlank()) return
        hardFailUntil.keys.removeAll { it.startsWith("$id|") }
    }

    /**
     * 6.7.3：把这首歌所有"上次失败"的记忆一次清干净。
     *
     * 播放侧原地重试时调它。只清整条链路的那张表是不够的 ——
     * 脚本层还有一张"没搜到"的表，那张不清，重试会秒回 null，
     * 用户看到的就是"点了重试还是没版权"。
     */
    fun clearNegative(id: String) {
        if (id.isBlank()) return
        clearHardFail(id)
        runCatching {
            com.jun.musiccloud.data.sourceplugin.SourcePluginResolver.clearMiss(id)
        }
    }

    /**
     * 竞速线程池：2 条，专给「两个 meting 源同时发」用。
     *
     * 用有界线程池而不是每次 new Thread：列表快速连点时会短时间内并发好几首，
     * 每首两条线程，无节制创建会把 GC 打起来，反而拖慢起播。
     * 满了两条就排队——排队的那首本来也还得等前面解析完。
     */
    private val racePool: java.util.concurrent.ExecutorService =
        java.util.concurrent.ThreadPoolExecutor(
            2, 4, 20L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue(),
            java.util.concurrent.ThreadFactory { r ->
                Thread(r, "fast-race").apply { isDaemon = true }
            }
        ).apply { allowCoreThreadTimeOut(true) }

    // ==================== 6.7.0：API / 脚本双通道 ====================

    /**
     * 双通道线程池：API 快链和脚本链各占一条，互不排队。
     *
     * 3 条而不是更多：预取一次只有「当前曲 + 下一首」真的需要并发，
     * 再多只会让音源后端把我们当成爬虫（那才是"全都没版权"的源头）。
     */
    private val hybridPool: java.util.concurrent.ExecutorService =
        java.util.concurrent.ThreadPoolExecutor(
            // 6.7.4：4 → 6。多了一条"内置 QQ 直连"通道要占线程；4 个线程时
            // 三通道并发会让任务排队，反而把最快的那条堵住。
            // 6.8.3：6 → 8。备用换链（helloworld/vkeys）再占一条；现在每首歌
            // 最多 4 路并发，当前曲 + 下一首预取 = 8，正好不排队。
            8, 8, 30L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue(),
            java.util.concurrent.ThreadFactory { r ->
                Thread(r, "resolve-hybrid").apply { isDaemon = true }
            }
        ).apply { allowCoreThreadTimeOut(true) }

    /** API 通道最多等这么久（meting 302 约 0.3s，加一次字节探测也就 1~2s） */
    // 6.7.2：跟 fastTimeoutMs 对齐（1.5s）。API 那边 1.5 秒给不出地址，
    // 就没有必要再在这里多等 700ms——脚本链是并行的，早就在路上了。
    private const val HYBRID_API_BUDGET_MS = 1_600L

    /** 脚本通道的整体预算（秒）；内部 resolveBlocking 另有 10s 硬超时 */
    // 6.7.4：8 秒 → 5 秒。
    //
    // 8 秒 + 三级重试 = 最坏 24 秒，已经超过播放器的 25 秒响应超时，
    // 于是"每一首都不出声"—— 这是 6.7.3 全线崩的直接原因，方向错了。
    // 现在两条约束同时成立：
    //   · 起播最多等 5 秒（有试听则 2.5 秒出声）；
    //   · 超时**不取消**脚本任务，让它后台跑完并落缓存，下次点这首秒开。
    private const val HYBRID_SCRIPT_BUDGET_S = 5L

    /**
     * 「优先 API、脚本补缺」的核心实现。
     *
     * 用户原话：「实实在不行的话，你就是优先启用 API……像周杰伦那些歌的话，
     * 就用那种我给你那些音源」。这段就是照这句话写的：
     *  - 热门/普通歌 → 网易云公开链路最快，谁先回用谁；
     *  - API 拿不到（VIP / 无版权 / 只给 30 秒试听）→ 脚本链的结果顶上；
     *  - 无损 → 以脚本为准，不让 API 的 128k 抢跑。
     */
    private fun resolveHybrid(
        id: String,
        title: String,
        artist: String,
        quality: AudioQuality,
        preferFormat: String?,
        skipFast: Boolean,
        durationMs: Long,
        force: Boolean = false
    ): ResolvedAudio? {
        val startedAt = System.currentTimeMillis()
        fun leftMs(): Long =
            (HYBRID_SCRIPT_BUDGET_S * 1000L) - (System.currentTimeMillis() - startedAt)

        // 6.8.0：远程开关。后台把 script 关掉时，这里直接不提交任务 ——
        // 脚本链是最不稳的一条（依赖脚本作者的后端），出问题时要能一键停掉，
        // 让起播走 QQ 直连，而不是等它超时把整首歌拖死。
        val scriptFuture = if (!SourceSwitches.script) null else runCatching {
            hybridPool.submit(java.util.concurrent.Callable {
                runCatching {
                    com.jun.musiccloud.data.sourceplugin.SourcePluginResolver.resolveBlocking(
                        id, title, artist, quality,
                        // 6.7.3：4 → 8 秒。上一版为了"快"把预算砍到 4 秒，
                        // 代价是**慢一点的源全被误判成没版权**（用户："之前能听，现在听不了"）。
                        // 方向反了：宁可多等几秒拿到完整版，也不能把能听的歌判死。
                        // 真正的"快"由另一条路保证——API 有试听就先把声音放出来，见下方 2.5s 分支。
                        timeoutSec = 8L,
                        preferFormat = preferFormat,
                        durationMs = durationMs,
                        force = force
                    )
                }.getOrNull()
            })
        }.getOrNull()

        // 无损只能脚本给（脚本的 flac24bit / flac 档位）；API 给不了真无损
        val wantScriptFirst = quality == AudioQuality.LOSSLESS ||
            quality == AudioQuality.HI_RES ||
            preferFormat.equals("flac", ignoreCase = true)

        val numericId = id.isNotBlank() && id.all { it.isDigit() }
        val canUseApi = !wantScriptFirst && !skipFast && numericId

        if (!canUseApi) {
            return scriptFuture?.let {
                runCatching { it.get(leftMs().coerceAtLeast(1_000L), java.util.concurrent.TimeUnit.MILLISECONDS) }
                    .getOrNull()
            }
        }

        val apiFuture = if (!SourceSwitches.meting) null else runCatching {
            hybridPool.submit(java.util.concurrent.Callable {
                runCatching { fastDirectResolve(id) }.getOrNull()
            })
        }.getOrNull()

        // 6.7.4：第三条通道 —— 内置 QQ 直连（实测 0.4 秒，且不依赖脚本与 meting）。
        // 无损/下载 FLAC 时不开这条：它只给 m4a/320k，会把无损请求"抢"成有损。
        val qqFuture = if (SourceSwitches.qq && !wantScriptFirst && title.isNotBlank()) runCatching {
            hybridPool.submit(java.util.concurrent.Callable {
                runCatching { qqBuiltinResolve(title, artist, durationMs) }.getOrNull()
            })
        }.getOrNull() else null

        // 6.8.3：第四条通道 —— helloworld + vkeys 备用换链，与 QQ 直连共用搜索结果。
        val extraFuture = if (SourceSwitches.qq && !wantScriptFirst && title.isNotBlank()) runCatching {
            hybridPool.submit(java.util.concurrent.Callable {
                runCatching { qqExtraResolve(title, artist, durationMs) }.getOrNull()
            })
        }.getOrNull() else null
        // ────────────────────────────────────────────────────────────
        // 6.7.1：**真·竞速**，不是"先死等 API，再等脚本"。
        //
        // 上一版是 `apiFuture.get(2.2s)` 后再 `scriptFuture.get(...)`：
        // 就算脚本 0.8 秒就拿到了地址，用户还是要陪 API 等满 2.2 秒。
        // 周杰伦这类 API 根本没结果的歌，等于每首白白多等 2.2 秒
        //（用户原话："切歌也慢，一点歌就慢"）。
        //
        // 现在每 20ms 看一眼两边谁先完成，谁先给出可播地址就用谁：
        //   · API 先回且是完整文件 → 立刻出声（热门歌 0.3~0.8 秒）；
        //   · 脚本先回（VIP 歌）→ 也立刻出声，不再陪 API 空等。
        // ────────────────────────────────────────────────────────────
        var api: ResolvedAudio? = null
        var apiDone = false
        var script: ResolvedAudio? = null
        var scriptDone = false
        var qq: ResolvedAudio? = null
        var qqDone = qqFuture == null
        var extra: ResolvedAudio? = null
        var extraDone = extraFuture == null
        val deadline = startedAt + HYBRID_API_BUDGET_MS
        while (true) {
            // 先看 QQ 直连：它是目前唯一"实测稳定"的通道，通常 0.4 秒就回来
            if (!qqDone && qqFuture?.isDone == true) {
                qq = runCatching { qqFuture.get() }.getOrNull()
                qqDone = true
                if (qq != null && qq.url.isNotBlank()) {
                    lastProviderName = "QQ音乐"
                    return qq
                }
            }
            // 6.8.3：备用换链（helloworld/vkeys），cyapi 慢或挂时自动顶上
            if (!extraDone && extraFuture?.isDone == true) {
                extra = runCatching { extraFuture.get() }.getOrNull()
                extraDone = true
                if (extra != null && extra.url.isNotBlank()) {
                    lastProviderName = "备用音源"
                    return extra
                }
            }
            if (!apiDone && apiFuture?.isDone == true) {
                api = runCatching { apiFuture.get() }.getOrNull()
                apiDone = true
                if (api != null && api.url.isNotBlank() && !api.trial) {
                    lastProviderName = "网易直连"
                    return api
                }
            }
            if (!scriptDone && scriptFuture?.isDone == true) {
                script = runCatching { scriptFuture.get() }.getOrNull()
                scriptDone = true
                if (script != null && script.url.isNotBlank()) {
                    lastProviderName = "音源脚本"
                    return script
                }
            }
            // 6.7.3：API 已经给了能响的试听、脚本还在路上 —— **先把声音放出来**。
            // 用户不用对着 0:00 干等；完整版由播放侧的 detectTrialByDuration 在后台补，
            // 拿到就无缝换源（那段逻辑本来就有，只是以前拿不到完整版时反而整首判死）。
            if (api != null && api.url.isNotBlank() && api.trial &&
                System.currentTimeMillis() - startedAt >= 2_500L
            ) return api
            if (System.currentTimeMillis() >= deadline) break
            if (apiDone && scriptDone && extraDone) break
            // 20ms 一次：比一次性阻塞等待多消耗一点 CPU，但换来的是"谁先好谁先响"
            runCatching { Thread.sleep(20L) }
        }

        // 超时后：把脚本剩下的预算用完（脚本链通常比 API 慢，但它是 VIP 歌的唯一出路）
        if (!scriptDone) {
            script = scriptFuture?.let {
                runCatching {
                    it.get(leftMs().coerceAtLeast(1_500L), java.util.concurrent.TimeUnit.MILLISECONDS)
                }.getOrNull()
            }
        }
        if (script != null && script.url.isNotBlank()) return script
        if (qq != null && qq.url.isNotBlank()) return qq
        if (extra != null && extra.url.isNotBlank()) return extra
        // 脚本也没结果：宁可先给试听片段（至少能响），也不让用户面对"无版权"
        return api?.takeIf { it.url.isNotBlank() }
    }

    /**
     * 6.7.0：把"失败记忆"全部清掉。
     *
     * App 从后台回来时调一次。这些缓存本意是防止反复撞同一个坏接口，
     * 但它们把**一次网络抖动**记成了"这首歌没版权"（2~60 分钟），
     * 用户看到的正是"回来点什么都无版权、整个歌单连跳"。
     */
    fun clearAllFailures() {
        runCatching { hardFailUntil.clear() }
        runCatching { fastCache.clear() }
        runCatching { com.jun.musiccloud.data.sourceplugin.SourcePluginResolver.clearCache() }
        runCatching { com.jun.musiccloud.data.sourceplugin.LxScriptHost.clearSearchCache() }
    }


    /**
     * 6.7.2：后台"这到底是整首还是试听片段"的探测。
     *
     * 放在独立线程，与起播完全解耦：先让用户听到声音，结论回来再写回缓存。
     * 下一次 seek / 重播就会走准确的分支（残片会去要完整版）。
     * 2 路足够——它只是每个新地址一次 2 字节请求。
     */
    /** 6.7.4：QQ 直连的「歌名|歌手 → songmid」与「→ 播放地址」缓存 */
    private val qqMidCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val qqUrlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<ResolvedAudio, Long>>()

    private val probePool: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
            Thread(r, "audio-probe").apply { isDaemon = true }
        }

    private fun scheduleSizeProbe(id: String, url: String, base: ResolvedAudio) {
        runCatching {
            probePool.execute {
                runCatching {
                    val b = probeFileBytes(url)
                    if (b > 0) {
                        fastCache[id] = base.copy(sizeBytes = b, trial = looksLikeTrial(b)) to
                            System.currentTimeMillis()
                    }
                }
            }
        }
    }

    private fun qqNorm(x: String): String =
        x.lowercase().replace(Regex("[\\s·、,，.。!！?？'\"“”‘’()（）\\[\\]【】-]"), "")

    /** 6.7.4：按「歌名 + 歌手 + 时长」在 QQ 音乐里找 songmid（时长是防串歌的关键） */
    /**
     * 6.9：搜索页「QQ音乐」源的一条结果。
     * mid 是 QQ 的 songmid；Song.songId 会存成 "qq:<mid>"，
     * 取源时 [resolveAudio] 的 qq: 分支直接换直链（0.2~0.5 秒，不串歌）。
     */
    data class QqSearchSong(
        val mid: String,
        val title: String,
        val singer: String,
        val album: String,
        val durationSec: Int,
        val cover: String
    )

    /**
     * QQ 音乐公开搜索（c.y.qq.com，无需 key，与 [qqSearchMid] 同一个接口）。
     * 给搜索页「QQ音乐」源用：返回完整列表，不做"同名同人"打分 —— 那套打分是
     * 给"已经知道要找哪首"的换源场景的；搜索场景用户就是想看到所有结果。
     * 解析失败 / 空结果返回空列表，调用方自行兜底。
     */
    fun qqSearchSongs(query: String, limit: Int = 30, page: Int = 1): List<QqSearchSong> {
        if (query.isBlank()) return emptyList()
        // 6.9.2：**QQ 公开搜索单页上限是 60**。实测 n=60 尚能回满 60 条，
        // n=80 只回 60，n=100（搜索页 SEARCH_PAGE_SIZE 原样传下来）直接回
        // **0 条** —— 用户看到的就是「QQ 源啥都搜不出来」。这里把单页钳到 60，
        // 超出的部分由调用方传 [page]（接口 p 参数，从 1 起）翻页拿。
        val n = limit.coerceIn(1, 60)
        val p = page.coerceIn(1, 50)
        val body = rawGet(
            "$QQ_SEARCH?w=${enc(query)}&format=json&n=$n&p=$p",
            referer = "https://y.qq.com/",
            connectMs = 3_000,
            readMs = 6_000
        ) ?: return emptyList()
        val arr = runCatching {
            JSONObject(body).getJSONObject("data").getJSONObject("song").getJSONArray("list")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<QqSearchSong>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mid = o.optString("songmid").orEmpty()
            if (mid.isBlank()) continue
            val singers = o.optJSONArray("singer")?.let { a ->
                (0 until a.length()).mapNotNull { j -> a.optJSONObject(j)?.optString("name") }
            } ?: emptyList()
            val albumMid = o.optString("albummid").orEmpty()
            val item = QqSearchSong(
                mid = mid,
                title = o.optString("songname").orEmpty().ifBlank { "未知歌曲" },
                singer = singers.joinToString(" / ").ifBlank { "未知艺术家" },
                album = o.optString("albumname").orEmpty(),
                durationSec = o.optInt("interval", 0),
                cover = if (albumMid.isNotBlank())
                    "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"
                else ""
            )
            // 6.9.x：搜索结果一出来就登记元信息，供歌词兜底按 qq:<mid> 反查（早于点播）
            // 6.6：把专辑一起登记，凑齐「歌名+歌手+时长+专辑+音源id」版本指纹。
            rememberQqLyricMeta(
                "qq:$mid", item.title, item.singer,
                item.durationSec.coerceAtLeast(0) * 1000L, item.album
            )
            out.add(item)
        }
        return out
    }

    /** QQ 搜索结果：歌手（client_search_cp t=9，data.singer.list） */
    data class QqSearchSinger(val mid: String, val name: String, val pic: String, val songNum: Int)

    /** QQ 搜索结果：专辑（client_search_cp t=8，data.album.list） */
    data class QqSearchAlbum(val mid: String, val name: String, val artist: String, val pic: String)

    /**
     * QQ 音乐搜索：歌手（t=9）。2026-11 实测 c.y.qq.com 公开接口支持
     * t=0 单曲 / t=7 歌词 / t=8 专辑 / t=9 歌手；歌单没有公开搜索。
     * id 只留 mid（客户端按名字回网易云打开，mid 不参与后续请求）。
     *
     * 6.9.4：t=9 的单页上限极小 —— 实测 n=40 只回 11 条（该词的真实匹配数），
     * **n=50 起直接回 0 条**（和单曲 n=100 回 0 是同一类风控行为）。
     * 6.9.3 把 60 原样传下来，正好踩中 0 条分支 —— 用户看到的就是
     * 「QQ 源歌手 tab 永远空」。这里钳到 30（实测 n=30 尚正常返回）。
     */
    fun qqSearchSingers(query: String, limit: Int = 30): List<QqSearchSinger> {
        if (query.isBlank()) return emptyList()
        val body = rawGet(
            "$QQ_SEARCH?w=${enc(query)}&format=json&t=9&n=${limit.coerceIn(1, 30)}&p=1",
            referer = "https://y.qq.com/",
            connectMs = 3_000,
            readMs = 6_000
        ) ?: return emptyList()
        val arr = runCatching {
            JSONObject(body).getJSONObject("data").getJSONObject("singer").getJSONArray("list")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<QqSearchSinger>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mid = o.optString("singerMID").orEmpty()
            val name = o.optString("singerName").orEmpty()
            if (mid.isBlank() || name.isBlank()) continue
            out.add(
                QqSearchSinger(
                    mid = mid,
                    name = name,
                    // 接口给的是 150x150 小图，换成 300x300 大图
                    pic = o.optString("singerPic").orEmpty().replace("R150x150", "R300x300"),
                    songNum = o.optInt("songNum", 0)
                )
            )
        }
        return out
    }

    /** QQ 音乐搜索：专辑（t=8，data.album.list） */
    fun qqSearchAlbums(query: String, limit: Int = 30): List<QqSearchAlbum> {
        if (query.isBlank()) return emptyList()
        val body = rawGet(
            "$QQ_SEARCH?w=${enc(query)}&format=json&t=8&n=${limit.coerceIn(1, 60)}&p=1",
            referer = "https://y.qq.com/",
            connectMs = 3_000,
            readMs = 6_000
        ) ?: return emptyList()
        val arr = runCatching {
            JSONObject(body).getJSONObject("data").getJSONObject("album").getJSONArray("list")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<QqSearchAlbum>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mid = o.optString("albumMID").orEmpty()
            val name = o.optString("albumName").orEmpty()
            if (mid.isBlank() || name.isBlank()) continue
            val singers = o.optJSONArray("singer")?.let { a ->
                (0 until a.length()).mapNotNull { j -> a.optJSONObject(j)?.optString("name") }
            }.orEmpty()
            out.add(
                QqSearchAlbum(
                    mid = mid,
                    name = name,
                    artist = singers.joinToString(" / ").ifBlank { "未知艺术家" },
                    pic = o.optString("albumPic").orEmpty().replace("R180x180", "R300x300")
                )
            )
        }
        return out
    }

    private fun qqSearchMid(title: String, artist: String, durationMs: Long): String? {
        val key = "${qqNorm(title)}|${qqNorm(artist)}|${durationMs / 1000}"
        qqMidCache[key]?.let { (mid, at) ->
            if (System.currentTimeMillis() - at < 6 * 60 * 60_000L) return mid
        }
        val kw = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        val body = rawGet(
            "$QQ_SEARCH?w=${enc(kw)}&format=json&n=6&p=1",
            referer = "https://y.qq.com/",
            connectMs = 3_000,
            readMs = 4_000
        ) ?: return null
        val arr = runCatching {
            JSONObject(body).getJSONObject("data").getJSONObject("song").getJSONArray("list")
        }.getOrNull() ?: return null

        val nTitle = qqNorm(title)
        val nArtist = qqNorm(artist)
        var best: String? = null
        var bestScore = -99
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mid = o.optString("songmid").orEmpty()
            if (mid.isBlank()) continue
            val name = o.optString("songname").orEmpty()
            val sec = o.optInt("interval", 0)
            val singers = o.optJSONArray("singer")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }
            } ?: emptyList()

            var score = 0
            if (qqNorm(name) == nTitle) score += 4
            else if (qqNorm(name).contains(nTitle) || nTitle.contains(qqNorm(name))) score += 1
            if (nArtist.isNotBlank() && singers.any {
                    val x = qqNorm(it)
                    x.contains(nArtist) || nArtist.contains(x)
                }
            ) score += 4
            if (durationMs > 0 && sec > 0) {
                val diff = kotlin.math.abs(durationMs / 1000 - sec)
                score += when {
                    diff <= 2 -> 4
                    diff <= 5 -> 2
                    diff <= 10 -> 0
                    else -> -6
                }
            }
            val low = name.lowercase()
            if (low.contains("伴奏") || low.contains("live") || low.contains("cover") ||
                low.contains("翻唱") || low.contains("dj")
            ) score -= 4
            if (score > bestScore) {
                bestScore = score
                best = mid
            }
        }
        // 分数门槛：歌名对上 + 歌手或时长至少再对一项，才敢用（防串歌）
        if (best == null || bestScore < 5) return null
        qqMidCache[key] = best to System.currentTimeMillis()
        return best
    }

    /**
     * 6.7.4：内置 QQ 直连解析。不碰脚本、不碰 meting，纯两次 HTTP。
     *
     * @return 拿不到返回 null（QQ 没有这首 / 是 VIP 需要登录 / 接口抖动），
     *         调用方继续走脚本，二者是并行竞速。
     */
    fun qqBuiltinResolve(title: String, artist: String, durationMs: Long): ResolvedAudio? {
        if (title.isBlank()) return null
        val key = "${qqNorm(title)}|${qqNorm(artist)}"
        qqUrlCache[key]?.let { (hit, at) ->
            // vkey 有效期通常 2~6 小时，这里保守缓存 8 分钟，过期后重新换一次（0.2 秒的事）
            if (System.currentTimeMillis() - at < 8 * 60_000L) return hit
        }
        val mid = qqSearchMid(title, artist, durationMs) ?: return null
        val body = rawGet(
            "$QQ_CYAPI?apikey=$QQ_CYAPI_KEY&type=json&mid=$mid",
            connectMs = 3_500,
            readMs = 6_000
        ) ?: return null
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val url = obj.optString("url").orEmpty()
        if (!url.startsWith("http")) return null
        val fmt = when {
            url.contains(".flac") -> "flac"
            url.contains(".m4a") || url.contains(".mp4") -> "m4a"
            url.contains(".ogg") -> "ogg"
            else -> "mp3"
        }
        val hit = ResolvedAudio(
            url = url,
            level = if (fmt == "flac") "lossless" else "320k",
            format = fmt,
            sizeBytes = 0L,
            trial = false,
            provider = "qq"
        )
        qqUrlCache[key] = hit to System.currentTimeMillis()
        return hit
    }

    /**
     * 6.9.6：用 QQ songmid 直接换直链（导入的歌单专用）。
     *
     * 与 [qqBuiltinResolve] 的唯一区别：**跳过搜索**。
     * mid 是导入歌单时 QQ 自己给的，不需要再按「歌名 + 歌手 + 时长」猜哪首才是
     * 同一首歌 —— 以前那套打分逻辑（同名 +4、时长差 ≤2 秒 +4）在
     * 「原曲 vs 翻唱 vs Live」这种场景下本来就有误判，现在直接没有这个问题。
     */
    fun qqResolveByMid(mid: String): ResolvedAudio? {
        if (mid.isBlank()) return null
        val key = "mid:$mid"
        qqUrlCache[key]?.let { (hit, at) ->
            // vkey 有效期 2~6 小时，保守缓存 8 分钟，过期重新换一次（0.2 秒的事）
            if (System.currentTimeMillis() - at < 8 * 60_000L) return hit
        }
        val body = rawGet(
            "$QQ_CYAPI?apikey=$QQ_CYAPI_KEY&type=json&mid=$mid",
            connectMs = 3_500,
            readMs = 6_000
        ) ?: return null
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val url = obj.optString("url").orEmpty()
        if (!url.startsWith("http")) return null
        val fmt = when {
            url.contains(".flac") -> "flac"
            url.contains(".m4a") || url.contains(".mp4") -> "m4a"
            url.contains(".ogg") -> "ogg"
            else -> "mp3"
        }
        val hit = ResolvedAudio(
            url = url,
            level = if (fmt == "flac") "lossless" else "320k",
            format = fmt,
            sizeBytes = 0L,
            trial = false,
            provider = "qq"
        )
        qqUrlCache[key] = hit to System.currentTimeMillis()
        return hit
    }

    /**
     * 6.9.6：没有可用平台 id 时，只按「歌名 + 歌手」找 —— QQ 直连、QQ 备用换链、
     * 音源脚本三路竞速，**全程不碰网易云**。
     *
     * 为什么不复用 [resolveHybrid]：它拿 id 去请求 meting 的前提是 id 为纯数字
     * （`canUseApi = … && numericId`），对 `qq:` / `kg:` 这种 id 会先白跑一轮再
     * 判失败，几百毫秒就这么没了。更糟的是它会往 60 秒失败缓存里写一条，
     * 导致同一首歌一分钟后还是"放不了"。这里干脆把 API 那条路整个摘掉。
     */
    private fun resolveByNameOnly(
        platformId: String,
        title: String,
        artist: String,
        durationMs: Long,
        quality: AudioQuality,
        preferFormat: String?,
        force: Boolean
    ): ResolvedAudio? {
        if (title.isBlank()) return null
        val startedAt = System.currentTimeMillis()
        val wantLossless = quality == AudioQuality.LOSSLESS ||
            quality == AudioQuality.HI_RES ||
            preferFormat.equals("flac", ignoreCase = true)

        val scriptF = if (!SourceSwitches.script) null else runCatching {
            hybridPool.submit(java.util.concurrent.Callable {
                runCatching {
                    com.jun.musiccloud.data.sourceplugin.SourcePluginResolver.resolveBlocking(
                        // 传平台原生 id（QQ songmid / 酷狗 hash）：脚本侧的酷狗源正好认 hash，
                        // 比只给歌名精准得多；空串会让某些脚本直接返回 null
                        platformId, title, artist, quality,
                        timeoutSec = 8L,
                        preferFormat = preferFormat,
                        durationMs = durationMs,
                        force = force
                    )
                }.getOrNull()
            })
        }.getOrNull()

        // 无损只能脚本给（脚本有 flac / flac24bit 档位），QQ 直连最高 320k，不许抢
        val qqF = if (SourceSwitches.qq && !wantLossless) runCatching {
            hybridPool.submit(java.util.concurrent.Callable {
                runCatching { qqBuiltinResolve(title, artist, durationMs) }.getOrNull()
            })
        }.getOrNull() else null
        val extraF = if (SourceSwitches.qq && !wantLossless) runCatching {
            hybridPool.submit(java.util.concurrent.Callable {
                runCatching { qqExtraResolve(title, artist, durationMs) }.getOrNull()
            })
        }.getOrNull() else null

        val futures = listOfNotNull(scriptF, qqF, extraF)
        if (futures.isEmpty()) return null
        val deadline = startedAt + 9_000L
        while (System.currentTimeMillis() < deadline) {
            for (f in futures) {
                if (f.isDone && !f.isCancelled) {
                    val r = runCatching { f.get() }.getOrNull()
                    if (r != null) {
                        futures.forEach { runCatching { it.cancel(true) } }
                        return r
                    }
                }
            }
            if (futures.all { it.isDone }) return null
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                return null
            }
        }
        futures.forEach { runCatching { it.cancel(true) } }
        return null
    }

    /** 6.8.3：helloworld / vkeys 两条备用换链的结果缓存（mid → 直链，10 分钟） */
    private val extraUrlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<ResolvedAudio, Long>>()

    /**
     * 6.8.3：QQ 换链的第二、第三路 —— helloworld + vkeys，谁先成功用谁。
     *
     * 与 [qqBuiltinResolve] 共用 [qqSearchMid]（songmid 有缓存，不会重复搜），
     * 只是把"cyapi 换直链"这一步扩成三条路：cyapi 主走，这里两条备走。
     * 串行 try：helloworld 实测更快（mp3 直链），vkeys 兜底；各自 3.5 秒超时，
     * 都失败返回 null，由竞速循环继续等别的通道。
     */
    private fun qqExtraResolve(title: String, artist: String, durationMs: Long): ResolvedAudio? {
        if (title.isBlank()) return null
        val mid = qqSearchMid(title, artist, durationMs) ?: return null
        extraUrlCache[mid]?.let { (hit, at) ->
            if (System.currentTimeMillis() - at < 10 * 60_000L) return hit
        }
        // 路 1：helloworld（http 明文，network_security_config 已允许）
        val hello = runCatching {
            val body = rawGet(
                "$HELLO_TX?source=tx&songId=$mid&quality=320k&key=$HELLO_KEY",
                connectMs = 3_500, readMs = 5_000
            ) ?: return@runCatching null
            val obj = runCatching { JSONObject(body) }.getOrNull() ?: return@runCatching null
            if (obj.optInt("code") != 200) return@runCatching null
            obj.optString("url").orEmpty().takeIf { it.startsWith("http") }
        }.getOrNull()
        // 路 2：vkeys（https）
        val vkeys = runCatching {
            val body = rawGet(
                "$VKEYS_TX?mid=$mid&quality=6",
                connectMs = 3_500, readMs = 5_000
            ) ?: return@runCatching null
            val obj = runCatching { JSONObject(body) }.getOrNull() ?: return@runCatching null
            if (obj.optInt("code") != 200) return@runCatching null
            obj.optJSONObject("data")?.optString("url").orEmpty().takeIf { it.startsWith("http") }
        }.getOrNull()
        val url = hello ?: vkeys ?: return null
        val provider = if (hello != null) "hello" else "vkeys"
        val fmt = when {
            url.contains(".flac") -> "flac"
            url.contains(".m4a") || url.contains(".mp4") -> "m4a"
            else -> "mp3"
        }
        val hit = ResolvedAudio(
            url = url,
            level = if (fmt == "flac") "lossless" else "320k",
            format = fmt,
            sizeBytes = 0L,
            trial = false,
            provider = provider
        )
        extraUrlCache[mid] = hit to System.currentTimeMillis()
        return hit
    }

    private fun fastDirectResolve(neteaseId: String): ResolvedAudio? {
        if (neteaseId.isBlank() || !neteaseId.all { it.isDigit() }) return null
        val now = System.currentTimeMillis()
        fastCache[neteaseId]?.let { (hit, at) ->
            // 6.3.4：残片结果只留 60 秒（它只是兜底，缓存久了每次播放都要多试一轮）；
            // 完整结果 10 分钟、失败 90 秒，和原来一致。
            val ttl = when {
                hit == null -> 90_000L
                hit.trial -> 60_000L
                else -> 600_000L
            }
            if (now - at < ttl) return hit
        }
        val timeout = AudioQualityStore.fastTimeoutMs()
        // ────────────────────────────────────────────────────────────────
        // 6.3.4 修正：**不再并行竞速**。
        //
        // 上一版是「两个 meting 源同时发，谁先回用谁」。这个策略有个致命前提：
        // 两个源给的东西**一样**。实测下来完全不成立（2026-11-06 逐首对比）：
        //
        //   歌曲            时长    qijieya        injahow
        //   光年之外        236s    9202 KB 完整   469 KB  30秒
        //   多远都要在一起   217s    8474 KB 完整   704 KB  45秒
        //   孤勇者          256s   10002 KB 完整   703 KB  45秒
        //   晴天            271s   10577 KB 完整  10577 KB 完整
        //
        // injahow 平均比 qijieya 快 50ms 左右，于是**每一首 VIP 歌都是它先回，
        // 用户听到的永远是 30/45 秒残片**。这就是「接入 API 之后每首都不完整」的根因，
        // 跟播放器、缓存、探测阈值全都无关——是源选错了。
        //
        // 新策略：qijieya 作为唯一主源（实测 9/10 命中且给完整文件，0.26 秒），
        // injahow 只在主源**完全没返回可用地址**时才作为保命兜底。
        // 串行最多多花一次超时，但只在主源彻底挂掉时才会发生，正常路径零影响。
        val url = runCatching {
            resolveMetingRedirect(
                "$METING/?server=netease&type=url&id=$neteaseId",
                connectMs = timeout, readMs = timeout
            )
        }.getOrNull()?.takeIf { it.isNotBlank() && !it.contains("outer/url") && !it.endsWith("/404") }
        // ────────────────────────────────────────────────────────────
        // 6.7.2：**主源没给地址就直接返回 null，不再串行等备用源。**
        //
        // 原来这里是 `主源(2.5s) ?: 备用源(2.5s)`：VIP 歌两条都超时，
        // 光"等 API 放弃"就要 5 秒，然后才轮到脚本——用户体感就是"点歌要等半天"。
        // 而脚本链是和 API **并行**跑的（见 resolveHybrid 的竞速），
        // 此刻它往往已经把地址拿回来了，完全没有必要再等第二个 meting 源。
        // 备用源只保留在 skipFast（残片补救）那条路上，那里本来就需要换一个结果。
        // ────────────────────────────────────────────────────────────
        val hit = if (!url.isNullOrEmpty() && !url.contains("outer/url") && !url.endsWith("/404")) {
            val direct = url.replaceFirst("^http://".toRegex(), "https://")
            val guess = ResolvedAudio(
                url = direct,
                level = "standard",
                format = sniffFormat(url) ?: "mp3",
                sizeBytes = 0L,
                // 先按完整版放行：探测只取 2 字节，但它挡在"发出第一个音频字节"前面，
                // 700ms 的超时就是实打实的 700ms 空白。改成后台探测、回来再修正缓存，
                // 起播这一段直接归零（用户要的是"点了就响"）。
                trial = false,
                provider = "meting"
            )
            scheduleSizeProbe(neteaseId, direct, guess)
            guess
        } else null
        // 残片结果只缓存 60 秒：它只是「暂时能听」的兜底，
        // 缓存太久会让每一次播放都先拿残片再换源，白白多一次请求。
        fastCache[neteaseId] = hit to now
        return hit
    }

    /**
     * 试听片段的字节上限（6.3.2）。
     *
     * 实测：45 秒 128k ≈ 721KB；30 秒 320k ≈ 1.2MB。
     * 而一首完整歌最短也在 2MB 以上（3 分钟 128k ≈ 2.8MB）。
     * 取 1.3MB 作分界线，两头都留了余量。
     *
     * 会误伤的只有「本身不到 90 秒的短曲」——那种情况多走一次完整源，
     * 拿不到就退回原结果，最多多花一秒，不会放不出歌。
     */
    private const val TRIAL_MAX_BYTES = 1_300_000L

    private fun looksLikeTrial(bytes: Long): Boolean =
        bytes > 0L && bytes < TRIAL_MAX_BYTES

    /**
     * 只取 2 个字节，从 `Content-Range: bytes 0-1/721023` 里读出真实文件长度。
     *
     * 为什么不用 HEAD：网易云 CDN 对部分节点不返回 HEAD 的 Content-Length，
     * 会给出 0 或直接 405。带 Range 的 GET 在所有节点上都能拿到总长，
     * 而且只传 2 字节就要断开，不会真的把歌下下来。
     */
    private fun probeFileBytes(url: String): Long {
        var conn: java.net.HttpURLConnection? = null
        return runCatching {
            conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn!!.requestMethod = "GET"
            // 6.7.1：1.6 秒 × 两次（建连 + 读数）就是 3 秒，这段是**串行挡在起播前面**的
            //（预取没命中时用户直接等这段）。压到 0.7 秒：探测失败一律当"未知"，
            // 按完整版放行，由播放侧的实际时长兜底 —— 宁可偶尔漏一个残片，
            // 也不能让每一首新歌都先愣 1.6 秒。
            conn!!.connectTimeout = 700
            conn!!.readTimeout = 700
            conn!!.instanceFollowRedirects = true
            conn!!.setRequestProperty("Range", "bytes=0-1")
            conn!!.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            conn!!.setRequestProperty("Referer", "https://music.163.com/")
            val code = conn!!.responseCode
            if (code !in 200..299) return@runCatching 0L
            val total = conn!!.getHeaderField("Content-Range")
                ?.substringAfterLast('/', "")
                ?.toLongOrNull() ?: 0L
            if (total > 0) total else conn!!.contentLengthLong
        }.getOrDefault(0L).also {
            // 只读了响应头，body 一个字节都不取，立刻断开省流量
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 并发请求 [urls]，返回**第一个非空结果**；全都失败 / 超时返回 null。
     *
     * 用的是 `CompletionService`：它内部的完成队列天然按「谁先完成」出队，
     * 不需要自己维护锁。拿到第一个有效结果就 `return`，其余 future 不 cancel——
     * 它们已经发出去了，cancel 只省本地线程、省不了流量，而且可能打断
     * 底层连接池复用。剩下的结果丢弃即可（请求本身很小，就是个 302 头）。
     */
    private fun raceFirstNonNull(urls: List<String>, timeoutMs: Int): String? {
        if (urls.isEmpty()) return null
        if (urls.size == 1) {
            return runCatching {
                resolveMetingRedirect(urls[0], connectMs = timeoutMs, readMs = timeoutMs)
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        val done = java.util.concurrent.ExecutorCompletionService<String?>(racePool)
        val futures = urls.map { u ->
            done.submit(java.util.concurrent.Callable {
                runCatching {
                    resolveMetingRedirect(u, connectMs = timeoutMs, readMs = timeoutMs)
                }.getOrNull()?.takeIf { it.isNotBlank() }
            })
        }
        // 总超时取单个 timeout：两个是并行的，总耗时不会超过最慢的那个，
        // 再加 200ms 余量防止两端都卡在边界上把有效结果漏掉。
        val deadline = System.currentTimeMillis() + timeoutMs + 200L
        repeat(urls.size) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return@repeat
            val f = runCatching {
                done.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS)
            }.getOrNull() ?: return@repeat
            val v = runCatching { f.get() }.getOrNull()
            if (!v.isNullOrBlank()) {
                // 有一个成了就不用再等另一个
                futures.forEach { if (!it.isDone) it.cancel(true) }
                return v
            }
        }
        return null
    }

    /**
     * 网络切换时清掉快源缓存（6.5）。
     *
     * 网易云的直链是**带过期时间的签名 URL**，而且不同网络出口拿到的 CDN 节点不一样。
     * 从 Wi-Fi 切到 4G 之后，缓存的旧直链在部分节点上会 403，
     * 用户的感受是「点了转圈然后报无版权」，手动再点一次又好了。
     *
     * 只清快源缓存，不动 GD / eapi 那些——那些返回的是另一套鉴权，
     * 且数量少、重建成本高。
     */
    fun invalidateFastCache() {
        fastCache.clear()
    }

    /** GD 聚合的音质档位取值（br=999 表示无损；320/192/128 为有损档） */
    private const val GD_BR_LOSSLESS = 999

    private const val GD_BR_320 = 320

    /**
     * 播放音质 → GD 的 br。
     *
     * [AudioQuality.AUTO] 返回 0 表示「不走 GD」，保持原来的链路（AUTO 是"能放就行"，
     * 那时候用户在乎的是快，而不是音质）。
     */
    private fun gdBrForQuality(q: AudioQuality): Int = when (q) {
        AudioQuality.LOSSLESS, AudioQuality.HI_RES -> GD_BR_LOSSLESS
        AudioQuality.EXHIGH -> GD_BR_320
        AudioQuality.STANDARD -> 128
        else -> 0
    }

    /**
     * 6.3 新增：不依赖登录的按档位解析（GD Studio 聚合，`source=netease`）。
     *
     * 为什么要专门加这一条：
     *  - 官方 eapi 高档位要 MUSIC_U（登录网易云），**未登录用户永远拿不到无损**；
     *  - 原来的外层兜底 `outer/url` 只有一档，实测下来不管怎么选都是同一个 8MB 左右的 mp3，
     *    用户看到的就是「换音质没变化」；
     *  - GD 的 `types=url&br=` 是公开端点、无需 key、无需登录，实测 999 → flac（32.5MB）、
     *    320 → 320k mp3（13MB），档位是真的分开的。
     *
     * 失败一律返回 null 交给后面的链路，绝不影响「能放出来」这件事。
     * 结果做 10 分钟缓存 + 失败 3 分钟退避：批量下载时同一首歌不会被反复请求。
     */
    private val gdCache = java.util.concurrent.ConcurrentHashMap<String, Pair<ResolvedAudio?, Long>>()

    private fun gdResolve(neteaseId: String, br: Int): ResolvedAudio? {
        if (neteaseId.isBlank() || neteaseId.startsWith(JOOX_PREFIX)) return null
        if (!neteaseId.all { it.isDigit() }) return null
        val key = "$neteaseId@$br"
        val now = System.currentTimeMillis()
        gdCache[key]?.let { (hit, at) ->
            // 成功缓存 10 分钟；失败只退避 3 分钟，之后允许重试
            if (now - at < if (hit != null) 600_000L else 180_000L) return hit
        }
        val body = runCatching {
            rawGet("$GD?types=url&source=netease&id=${enc(neteaseId)}&br=$br", connectMs = 6000, readMs = 15000)
        }.getOrNull()
        val obj = runCatching { body?.let { JSONObject(it) } }.getOrNull()
        val url = obj?.optString("url").orEmpty()
        if (!url.startsWith("http")) {
            gdCache[key] = null to now
            return null
        }
        val bitrate = obj?.optInt("br", 0) ?: 0
        val size = obj?.optLong("size", 0L) ?: 0L
        val fmt = sniffFormat(url) ?: when {
            bitrate >= GD_BR_LOSSLESS -> "flac"
            url.contains(".flac") -> "flac"
            else -> "mp3"
        }
        val hit = ResolvedAudio(
            url = url.replaceFirst("^http://".toRegex(), "https://"),
            level = if (bitrate >= GD_BR_LOSSLESS) "lossless" else "exhigh",
            format = fmt,
            bitrate = bitrate,
            sizeBytes = size,
            provider = "gd"
        )
        gdCache[key] = hit to now
        return hit
    }


    /**
     * 6.3：直接按指定 level 取一次音频（下载格式专用）。
     *
     * 为什么单独写一个而不是复用上面的 candidates 循环：
     * candidates 会从高档逐级往下**全部试一遍**，那是试听要的"一定要放出声音"；
     * 下载要的是"要么给 FLAC、要么我就退回原链路"，逐级降级到 128k 反而更糟。
     *
     * 失败结果做 5 分钟退避：批量下载 200 首时，如果这个账号就是没有无损权限，
     * 每首歌都去撞一次接口会白白多出 200 个请求（还可能触发风控）。
     * 退避只记"这个 level 刚才失败了"，到时间自动重试，不会永久禁用。
     */
    private val levelFailAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 6.3.2：某档位刚解析成功过 —— 撤掉它的失败退避。
     *
     * 退避本意是"批量下载时别反复撞同一个接口"，但它绑在档位上、不绑歌：
     * 只要有一首歌在某档位失败（VIP / 无版权 / 网络抖），接下来 3 分钟**所有歌**
     * 在这个档位都会被直接跳过，表现为"切了音质就放不出声、还特别慢"。
     * 所以一旦有任何一首在该档位成功，就立刻解除退避。
     */
    fun clearLevelFail(level: String?) {
        if (level.isNullOrBlank()) return
        levelFailAt.remove(level)
    }

    fun highQualityFallback(id: String, level: String): ResolvedAudio? {
        if (id.isBlank() || id.startsWith(JOOX_PREFIX) || level.isBlank()) return null
        val now = System.currentTimeMillis()
        val failedAt = levelFailAt[level] ?: 0L
        if (now - failedAt < 300_000L) return null
        val cookie = NeteaseStore.cookie
        if (cookie.isBlank()) return null
        val hit = runCatching { NeteaseEapi.songUrl(id, level, cookie) }.getOrNull()
        if (hit == null) {
            levelFailAt[level] = now
            return null
        }
        return ResolvedAudio(
            url = hit.url,
            level = hit.level ?: level,
            format = hit.format,
            bitrate = hit.bitrate,
            sizeBytes = hit.sizeBytes
        )
    }

    /** 从直链猜容器，给播放页徽章用；识别不出返回 null（徽章继续显示「在线」） */
    private fun sniffFormat(url: String): String? {
        val path = url.substringBefore('#').substringBefore('?')
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "dsf" -> ext
            else -> null
        }
    }

    /**
     * 原四级链路（5.0 逻辑，未改动）：
     *  1. jOOX 曲目：直接走 jOOX 解析
     *  2. 网易云：Meting 302 → xfabe 详情 → outer/url
     *  3. 网易云整条链路都拿不到（VIP / 无版权）时，按「歌名 + 歌手」去 jOOX
     *     找同名曲兜底——这是「周杰伦能搜到但点开不能听」的主要解法。
     */
    private fun resolveAudioUrlLegacy(id: String, title: String, artist: String): String? {
        if (id.startsWith(JOOX_PREFIX)) {
            val jid = jooxDecode(id) ?: return null
            return jooxResolve(jid)
        }
        // 1) Meting
        resolveMetingRedirect("$METING/?server=netease&type=url&id=$id")?.let { if (it.isNotEmpty()) return it }
        // 2) xfabe 详情
        runCatching {
            val detail = httpGet("$XFABE/music?type=json&id=$id")?.optJSONObject("data")
            val u = detail?.optString("url").orEmpty()
            if (u.isNotEmpty() && !u.contains("outer/url")) return u
            if (u.isNotEmpty()) {
                resolveRedirect(u)?.let { if (!it.contains("outer/url")) return it }
            }
        }
        // 3) outer/url 兜底
        resolveRedirect("https://music.163.com/song/media/outer/url?id=$id.mp3")?.let { return it }
        // 4) 第二曲库兜底
        if (title.isNotBlank()) {
            jooxFallback(title, artist)?.let { return it }
        }
        return null
    }

    // ==================== 搜索联想（5.1 新增） ====================

    /**
     * 输入联想词。官方 `search/suggest/keyword` 需要 POST，GET 会 400（已实测）。
     *
     * 失败/为空返回空列表 —— 搜索页原本的「历史 + 热词」补全照常显示，
     * 这条链路坏了不会让搜索页变空白。
     */
    fun suggestKeywords(keyword: String, limit: Int = 8): List<String> {
        if (keyword.isBlank()) return emptyList()
        val root = runCatching {
            formPost(
                "$NETEASE/search/suggest/keyword",
                "s=${enc(keyword)}&limit=${limit.coerceIn(1, 20)}&type=1&cursor=__empty__"
            )
        }.getOrNull() ?: return emptyList()
        val arr = root.optJSONObject("result")?.optJSONArray("allMatch") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val kw = arr.optJSONObject(i)?.optString("keyword").orEmpty().trim()
            if (kw.isNotBlank() && seen.add(kw)) out.add(kw)
        }
        return out
    }

    /** 表单 POST 取 JSON（只给联想这类小请求用，音频链路仍走 GET） */
    private fun formPost(url: String, body: String): JSONObject? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", "https://music.163.com/")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Accept", "application/json")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader().use { it?.readText() }.orEmpty()
        if (text.isBlank()) null else JSONObject(text)
    }.getOrNull()

    // ==================== jOOX 第二曲库 ====================

    /** id 编码成 `joox:<url-safe-base64>`，保证能直接塞进代理 URL 的查询串 */
    private fun jooxEncode(raw: String): String = JOOX_PREFIX + android.util.Base64.encodeToString(
        raw.toByteArray(Charsets.UTF_8),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
    )

    private fun jooxDecode(tag: String): String? = runCatching {
        String(
            android.util.Base64.decode(
                tag.removePrefix(JOOX_PREFIX),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
            ),
            Charsets.UTF_8
        )
    }.getOrNull()

    /** jOOX 搜索结果缓存（关键词 → 结果, 时间） */
    private val jooxSearchCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<List<RawSong>, Long>>()

    /**
     * 带**硬超时**的 jOOX 搜索：GD 聚合经常 5 秒以上不回，直接放弃。
     * 用 Future.get(timeout) 而不是协程 withTimeout——底层是阻塞 HttpURLConnection，
     * 协程取消打断不了正在读的 socket。
     */
    private fun jooxSearchWithTimeout(keyword: String, limit: Int): List<RawSong> {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "joox-search").apply { isDaemon = true }
        }
        return try {
            // 6.3.1：5 秒 → 2.5 秒。jOOX 现在只剩"补条数"的作用（url 解析已废），
            // 让它拖住搜索结果不值得；超时就当没补上，界面上少几条也比转圈 5 秒强。
            pool.submit(java.util.concurrent.Callable { jooxSearch(keyword, limit) })
                .get(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            emptyList()
        } finally {
            pool.shutdownNow()
        }
    }

    /** jOOX 搜索（GD Studio 聚合）。失败返回空表，不阻断主搜索。 */
    fun jooxSearch(keyword: String, limit: Int = 20): List<RawSong> {
        if (keyword.isBlank()) return emptyList()
        val body = rawGet(
            "$GD?types=search&source=joox&name=${enc(keyword)}&count=${limit.coerceIn(1, 50)}&pages=1",
            connectMs = 6000,
            readMs = 12000
        ) ?: return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = ArrayList<RawSong>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rawId = o.optString("id")
            if (rawId.isBlank()) continue
            val picId = o.optString("pic_id")
            val artist = o.optJSONArray("artist")?.let { a ->
                (0 until a.length()).joinToString("/") { a.optString(it) }
            }.orEmpty()
            out.add(
                RawSong(
                    id = jooxEncode(rawId),
                    title = o.optString("name"),
                    artist = artist,
                    album = o.optString("album"),
                    cover = if (picId.isBlank()) "" else "https://image.joox.com/JOOXcover/0/$picId/300",
                    durationMs = 0L
                )
            )
        }
        return out
    }

    /** jOOX 曲目 → 真实音频地址（先要 320k，拿不到再退 128k） */
    private fun jooxResolve(rawId: String): String? {
        for (br in listOf("320", "128")) {
            val body = rawGet(
                "$GD?types=url&source=joox&id=${enc(rawId)}&br=$br",
                connectMs = 6000,
                readMs = 15000
            ) ?: continue
            val url = runCatching { JSONObject(body).optString("url") }.getOrNull().orEmpty()
            if (url.startsWith("http")) return url
        }
        return null
    }

    /** 网易云无版权时的 jOOX 兜底缓存：id → 地址（空串表示确认拿不到，短时间内不再重试） */
    private val jooxFallbackCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * 按「歌名 + 歌手」到 jOOX 找同名曲并解析。
     *
     * 网易云的《彩虹》是周杰伦 VIP，jOOX 上同名曲可直接播放；这里按歌名精确匹配，
     * 再按歌手用字重合度挑最像的一条，避免拿到翻唱 / DJ 版。
     */
    private fun jooxFallback(title: String, artist: String): String? {
        val key = normTitle(title) + "|" + artist
        jooxFallbackCache[key]?.let { (url, at) ->
            if (System.currentTimeMillis() - at < 10 * 60 * 1000) return url.ifEmpty { null }
        }
        val want = normTitle(title)
        if (want.isEmpty()) return null
        // jOOX 的歌词/歌名多为繁体，不能要求歌名完全一致：
        // 用「字重合度 + 歌手重合度」挑最像的一条（小幸運/小幸运、演員/演员 都能对上）。
        var best: RawSong? = null
        var bestScore = 0.0
        for (c in jooxSearch("$title $artist".trim(), 10)) {
            val titleScore = titleSimilarity(title, c.title)
            if (titleScore < 0.35) continue
            val overlap = artistOverlap(c.artist, artist)
            // R27：歌手必须真的对得上，否则宁可不放。
            // 旧条件只要求「有 1 个字重合」，于是「周杰伦」能匹配到「周深」「伦桑」，
            // 周杰伦的歌放出来变成别人的翻唱——这是用户反馈最不能接受的一类 bug。
            if (artist.isNotBlank()) {
                val a = artist.lowercase().trim()
                val ca = c.artist.lowercase().trim()
                val strict = overlap >= 2 ||
                    (a.isNotEmpty() && ca.isNotEmpty() && (ca.contains(a) || a.contains(ca))) ||
                    isArtistAlias(c.artist, artist)
                if (!strict) continue
            }
            val score = titleScore * 10 + overlap
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        val url = best?.let { jooxDecode(it.id) }?.let { jooxResolve(it) }
        jooxFallbackCache[key] = (url ?: "") to System.currentTimeMillis()
        return url
    }

    /** 歌名相似度：完全相同 1.0；包含 0.9；否则按重合字占比 */
    private fun titleSimilarity(a: String, b: String): Double {
        val x = normTitle(a)
        val y = normTitle(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.9
        val set = x.toHashSet()
        val common = y.count { it in set }
        return common.toDouble() / maxOf(x.length, y.length)
    }

    // ==================== 6.3.1：同名替代版本兜底 ====================

    /** 替代版本搜索结果缓存：`歌名|歌手` → (候选列表, 时间) */
    private val altSearchCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<List<RawSong>, Long>>()

    /**
     * 周杰伦这类「正版曲目无版权」的真正解法：**换一个同名可播放版本**。
     *
     * 背景（实测）：
     *  - 原曲 186016《晴天》在网易云已下架，meting 返回 200 + 空 body，GD 返回 `url:""`；
     *  - jOOX `types=url` 目前持续返回 `{"url":"","br":-1}`，即 jOOX 这条路已经废了；
     *  - 但网易云曲库里**仍有可播放的同名曲**：GD 搜「晴天 周杰伦」能拿到 3440441479
     *    （《晴天》，artist 写的是 "Jay"），它 br=320 能出真实 CDN。
     *
     * 所以当原 id 解析不到时，按「歌名 + 歌手」搜一次，挑标题最像、时长最接近的一条，
     * 再按同一档位解析。挑的时候**过滤翻唱/DJ/伴奏**，避免点《晴天》给一段纯音乐。
     *
     * @param wantBr 与调用方一致的音质档位；0 表示「能放就行」，用 320 试（128 音质太差）
     * @return 命中的可播放地址与命中曲目信息；全失败返回 null
     */
    @Suppress("unused")
    private fun altVersionResolve(
        title: String,
        artist: String,
        wantBr: Int
    ): Triple<ResolvedAudio, String, String>? {
        val want = normTitle(title)
        if (want.isEmpty()) return null
        val key = "$want|${normTitle(artist)}"
        val now = System.currentTimeMillis()
        val cands = altSearchCache[key]?.let { (list, at) ->
            if (now - at < 30 * 60 * 1000L) list else null
        } ?: searchNeteaseByGd("$title $artist".trim(), 20).also {
            altSearchCache[key] = it to now
        }
        if (cands.isEmpty()) return null

        // 明显不是原唱的版本：伴奏 / DJ / 钢琴版 / 翻唱 / 铃声 —— 一律降权。
        // 实测网易云里「周杰伦」的曲目常被拆成第三方上传版：原曲下架后剩下的多是
        // 「稻香(治愈版)」「七里香 (MV版)」「夜曲 (钢琴版) [原唱: 周杰伦]」这类，
        // 但也有真正能播的正版条目（如《晴天》3440441479、《稻香》3441643322）。
        val badWords = listOf(
            "伴奏", "纯音乐", "钢琴", "吉他", "尤克里里", "八音盒", "铃声", "翻唱",
            "cover", "remix", "dj", "mix", "beat", "karaoke", "instrumental",
            "治愈版", "正式版", "原版", "慢速", "加速", "降调", "升调"
        )
        val scored = cands.mapNotNull { c ->
            val tScore = titleSimilarity(title, c.title)
            if (tScore < 0.5) return@mapNotNull null
            val text = (c.title + " " + c.artist).lowercase()
            var factor = 1.0
            if (badWords.any { text.contains(it) }) factor *= 0.45
            // 「[原唱: 周杰伦]」这种标注说明它自己就是翻唱/改编，不是原唱
            if (text.contains("原唱") || text.contains("翻自")) factor *= 0.6
            // 歌手要沾边：GD 常把版权方登记的艺名写成 "Jay"、"周杰伦."，
            // 所以除了字重合，还认这几个等价写法
            val aScore = when {
                artist.isBlank() -> 0.5
                else -> {
                    val ov = artistOverlap(c.artist, artist)
                    val alias = isArtistAlias(c.artist, artist)
                    when {
                        ov >= 2 -> 1.0
                        alias -> 0.95
                        ov == 1 -> 0.75
                        else -> 0.25
                    }
                }
            }
            val score = tScore * factor * aScore
            if (score < 0.4) null else c to score
        }.sortedByDescending { it.second }.take(6)

        val br = if (wantBr > 0) wantBr else GD_BR_320
        for ((cand, _) in scored) {
            val hit = gdResolve(cand.id, br) ?: gdResolve(cand.id, 128) ?: continue
            return Triple(hit, cand.title, cand.artist)
        }
        return null
    }

    /** 歌手等价写法：GD/网易云的版权方条目里，周杰伦常写作 Jay / 周杰倫 / Jay Chou */
    private fun isArtistAlias(candArtist: String, want: String): Boolean {
        val a = candArtist.lowercase()
        val w = want.lowercase()
        val pairs = listOf(
            "周杰伦" to listOf("jay", "jay chou", "周杰倫"),
            "周杰倫" to listOf("jay", "jay chou", "周杰伦")
        )
        for ((k, aliases) in pairs) {
            if (w.contains(k)) {
                if (aliases.any { a.contains(it) }) return true
                // 繁体/简体互换
                if (k == "周杰伦" && a.contains("周杰倫")) return true
                if (k == "周杰倫" && a.contains("周杰伦")) return true
            }
        }
        return false
    }

    /** GD 聚合按歌名搜网易云曲库（只取曲目 id，用于替代版本） */
    private fun searchNeteaseByGd(keyword: String, limit: Int): List<RawSong> = runCatching {
        val body = rawGet(
            "$GD?types=search&source=netease&name=${enc(keyword)}&count=${limit.coerceIn(1, 30)}&pages=1",
            connectMs = 5000,
            readMs = 9000
        ) ?: return@runCatching emptyList()
        val arr = JSONArray(body)
        val out = ArrayList<RawSong>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rid = o.optString("id")
            if (rid.isBlank() || !rid.all { it.isDigit() }) continue
            val ar = o.optJSONArray("artist")?.let { a ->
                (0 until a.length()).joinToString("/") { a.optString(it) }
            }.orEmpty()
            out.add(
                RawSong(
                    id = rid,
                    title = o.optString("name"),
                    artist = ar,
                    album = o.optString("album"),
                    cover = "",
                    durationMs = 0L
                )
            )
        }
        out
    }.getOrDefault(emptyList())

    /** 归一化歌名：去空白/标点/括号内容，只留文字与数字 */
    private fun normTitle(s: String): String {
        val noBracket = s.replace(Regex("[（(\\[【].*?[）)\\]】]"), "")
        return noBracket.lowercase().replace(Regex("[\\s\\p{Punct}·、，。！？：；“”‘’《》…—－]"), "")
    }

    /** 歌手名重合字数（周杰伦 / 周杰倫 至少能对上「周」） */
    private fun artistOverlap(a: String, b: String): Int {
        if (b.isBlank()) return 0
        val set = b.lowercase().toHashSet()
        return a.lowercase().count { it in set }
    }

    private fun resolveMetingRedirect(
        url: String,
        connectMs: Int = 6000,
        readMs: Int = 9000
    ): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "*/*")
        when (conn.responseCode) {
            in 300..399 -> conn.getHeaderField("Location")?.takeIf { !it.contains("api.qijieya.cn") }
            in 200..299 -> conn.inputStream?.bufferedReader()?.use { it.readText() }?.trim()
                ?.takeIf { it.startsWith("http") }
            else -> null
        }
    }.getOrNull()

    private fun resolveRedirect(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 6000
        conn.readTimeout = 9000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", "https://music.163.com/")
        if (conn.responseCode in 200..399) {
            conn.url.toString().takeIf { !it.contains("outer/url") }
        } else null
    }.getOrNull()

    // ==================== 歌词 ====================

    /** 在线歌词包：主歌词 + 翻译 + 罗马音/音译（任一可为空） */
    data class LyricBundle(
        val lyric: String? = null,
        val translation: String? = null,
        val romanization: String? = null,
        /**
         * 逐字轨（网易云 v1 接口的 `yrc` 字段）。5.2featR5 起单独带上：
         * 之前以为逐字内容在 `lrc.lyric` 里，实测发现那里是
         * 「制作信息 JSON 行 + 普通 LRC 正文」的混合文档，真正的逐字时间在 `yrc`。
         * 两条都解析后取行数更多的那条，谁都不落下。
         */
        val verbatim: String? = null,
        /**
         * 贴着**逐字轨**时间轴的译文（v1 的 `ytlrc`）。
         * 它和 `tlyric` 是两套时间轴：同一句能差 2~3.7 秒（实测），
         * 所以必须分开带，由解析侧按主轨来源挑，不能混着就近吸附。
         */
        val wordTranslation: String? = null
    )

    /** 返回原始 LRC 文本（含时间轴），无歌词返回 null */
    fun fetchLyric(id: String): String? = fetchLyricBundle(id).lyric

    /**
     * 拉取歌词 + 翻译 + 音译。
     *
     * 主歌词优先 xfabe（快），翻译/音译只有网易云官方接口给得全：
     * 即使主歌词已命中，也再请求一次官方接口把 tlyric / romalrc 补齐。
     * 官方接口失败不影响主歌词，各字段独立降级。
     */
    /**
     * 拉取歌词 + 翻译 + 音译。
     *
     * 5.2featR5 实测（直接抓官方返回）：`/api/song/lyric/v1` 的
     * `lrc.lyric` = 开头几行 `{"t":…,"c":[{"tx":"作词: "}]}` 制作信息 +
     * 后面整篇 `[mm:ss.xx]` 正文；逐字时间戳在**另一个字段** `yrc.lyric`
     * （`[开始ms,时长ms](开始ms,时长,0)词…`）。5.1 的注释以为逐字内容就在
     * `lrc.lyric` 里，所以这里两条都取回来，由 [LyricParser] 各解析一遍，
     * 取行数更多的作为主歌词——正文和逐字点亮同时保住。
     *
     * 降级保证：v1 拿不到（翻唱/新曲/风控/断网）时，完全退回 5.0 的原链路
     * （xfabe → /api/song/lyric → Meting），一行行为都不变。
     */
    fun fetchLyricBundle(id: String, song: Song? = null): LyricBundle {
        // 6.6：每轮取词先清掉上一首的「未匹配」标记；只有 qq 分支指纹匹配失败才会重新 mark。
        LyricMatchDiagnostics.clear()
        // jOOX 曲目：歌词自带 LRC，不存在网易云的翻译/音译字段
        if (id.startsWith(JOOX_PREFIX)) {
            val jid = jooxDecode(id) ?: return LyricBundle()
            val body = rawGet(
                "$GD?types=lyric&source=joox&id=${enc(jid)}",
                connectMs = 6000,
                readMs = 12000
            )
            val lyric = body?.let {
                runCatching { JSONObject(it).optString("lyric") }.getOrNull()
            }?.takeIf { it.isNotBlank() }
            return LyricBundle(lyric)
        }

        // ---------- QQ 源 → 网易云 id 兜底（6.9.9 新增） ----------
        // QQ 音乐源的 Song.songId 是 "qq:<mid>"，网易云歌词接口只认数字 id。
        // 过去这个分支直接拿 qq:xxx 去打 /lyrics，必然空 → 播放页「纯音乐 · 请欣赏」。
        // 这里按「歌名 + 歌手」解析出最匹配的网易云数字 id，再递归走一遍本函数，
        // 完整复用下面的 eapi / v1 / proxy / xfabe / Meting 全部歌词链路。
        if (id.startsWith("qq:")) {
            val meta = lyricFallbackMeta(id, song)
            // 6.6：按版本指纹匹配（歌名 + 歌手 + 时长 ±2s + 专辑 + 音源 id）。
            // 「歌名 + 歌手」搜不到时再用纯歌名搜一次：QQ 歌手常是 "A / B / C" 多人拼接，
            // 整串塞进网易云关键词反而降低命中率；时长与专辑仍由指纹逻辑守住。
            val neteaseId = if (meta == null) null else runCatching {
                resolveNeteaseIdBlocking(id, meta.title, meta.artist, meta.durationMs, meta.album)
                    ?: resolveNeteaseIdBlocking(id, meta.title, "", meta.durationMs, meta.album)
            }.getOrNull()
            // 只有纯数字 id 才敢递归（防止把带前缀的字符串再送进网易云接口）
            if (neteaseId != null && neteaseId.all { it.isDigit() }) {
                return fetchLyricBundle(neteaseId)
            }
            // 拿不到元信息 / 指纹匹配不上 → 明确标记「未找到匹配歌词」，
            // **绝不拿近似结果硬套**（硬套就是「跳一大段 / 越唱越偏」的来源）。
            // 空结果不会被 LyricParser 缓存，元信息补齐 / 网络恢复后重开歌词会自动重试。
            if (meta == null) LyricMatchDiagnostics.mark(id, "", "", 0L, "")
            return LyricBundle()
        }

        // ---------- 0. eapi 加密通道（6.9.5 新增，逐字歌词唯一可靠来源） ----------
        // 网易只对加密通道下发逐字轨（yrc）：手机直连 web 版 v1、桌面 curl、
        // 服务器代理全拿不到 —— 旧链路里绝大多数歌的逐字点亮退化成
        // "上一句到下一句的平均值"（用户实测反馈点亮节奏对不上）。
        // eapi 与取播放地址同一条加密路（真机实测可用），匿名即可，失败自然降级。
        if (!id.startsWith("qq:") && id.all { it.isDigit() }) {
            val v1e = runCatching {
                NeteaseEapi.lyricV1(id, NeteaseStore.cookie)
            }.getOrNull()
            if (v1e != null) {
                return LyricBundle(
                    lyric = v1e.lrc,
                    translation = v1e.tlyric,
                    romanization = v1e.romalrc,
                    verbatim = v1e.yrc,
                    wordTranslation = v1e.ytlrc
                )
            }
        }

        // ---------- 1. 逐字歌词优先 ----------
        val verbatim = runCatching {
            val v1 = httpGet(
                "$NETEASE/song/lyric/v1?id=$id&lv=1&tv=-1&rv=1&yv=1&cs=false",
                referer = "https://music.163.com/"
            )
            val main = v1?.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
            val wordTrack = v1?.optJSONObject("yrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
            // 只有 lrc 非空才走"v1 直接用"这条快路；lrc 空着（新曲/风控）时继续往下
            // 降级到 xfabe / 老接口，宁可没有逐字时间轴，也不能没有正文。
            if (main == null) null else LyricBundle(
                lyric = main,
                translation = v1.optJSONObject("tlyric")?.optString("lyric")
                    ?.takeIf { it.isNotBlank() },
                romanization = v1.optJSONObject("romalrc")?.optString("lyric")
                    ?.takeIf { it.isNotBlank() },
                verbatim = wordTrack,
                wordTranslation = v1.optJSONObject("ytlrc")?.optString("lyric")
                    ?.takeIf { it.isNotBlank() }
            )
        }.getOrNull()
        if (verbatim != null) return verbatim

        // ---------- 1.5 服务器代理歌词通道（6.9.8 新增兜底） ----------
        // 直连被风控（v1 拿不到正文）时，服务器代理的 `/lyric/new` 稳定给出
        // 正文 + 译文 + 音译（逐字轨代理侧没有，此通道退化为按句估算扫光）。
        if (!id.startsWith("qq:") && id.all { it.isDigit() }) {
            val viaProxy = runCatching {
                val root = NeteaseApi.proxyJson("/lyric/new", mapOf("id" to id))
                    ?: return@runCatching null
                val lrc = root.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
                    ?: return@runCatching null
                LyricBundle(
                    lyric = lrc,
                    translation = root.optJSONObject("tlyric")?.optString("lyric")?.takeIf { it.isNotBlank() },
                    romanization = root.optJSONObject("romalrc")?.optString("lyric")?.takeIf { it.isNotBlank() },
                    verbatim = root.optJSONObject("yrc")?.optString("lyric")?.takeIf { it.isNotBlank() },
                    wordTranslation = root.optJSONObject("ytlrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
                )
            }.getOrNull()
            if (viaProxy != null) return viaProxy
        }

        // ---------- 2. 原链路（与 5.0 完全一致） ----------
        var lyric = httpGet("$XFABE/lyrics?id=$id")
            ?.optJSONObject("data")?.optString("lyric")
            ?.takeIf { it.isNotBlank() }
        var translation: String? = null
        var romanization: String? = null

        runCatching {
            val root = httpGet(
                "$NETEASE/song/lyric?id=$id&lv=1&kv=1&tv=-1&rv=1",
                referer = "https://music.163.com/"
            )
            if (lyric.isNullOrBlank()) {
                lyric = root?.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
            }
            translation = root?.optJSONObject("tlyric")
                ?.optString("lyric")?.takeIf { it.isNotBlank() }
            romanization = root?.optJSONObject("romalrc")
                ?.optString("lyric")?.takeIf { it.isNotBlank() }
        }

        if (lyric.isNullOrBlank()) {
            lyric = rawGet("$METING/?server=netease&type=lrc&id=$id")
                ?.takeIf { it.isNotBlank() }
        }
        return LyricBundle(lyric, translation, romanization)
    }

    // ---------- QQ 源歌词兜底的「mid → 歌名/歌手/时长」反查表 ----------
    //
    // 背景：歌词链路 LyricParser.loadOnline 只把 `Song.lyricKey` 去掉 `ol:` 后的
    // 字符串传给本函数 —— QQ 源拿到的就是 `qq:<mid>`，并没有 Song 对象本身。
    // 而要把 `qq:<mid>` 映射成网易云数字 id，必须知道「歌名 + 歌手」。
    // 过去只从 PlayHistory.knownSongs() 反查，但那张表只在「加喜欢 / 加歌单 /
    // 云同步 / 分享导入」时写入，QQ 搜索直接播放、歌单/榜单里的 QQ 歌在起播时
    // 并不在里面 → 反查恒为 null → 空歌词 → 播放页「纯音乐 · 请欣赏」。
    //
    // 这里在两条所有 QQ 歌都必经的链路上顺手登记（零新增网络请求）：
    //   · qqSearchSongs：搜索结果一解析出来就记（搜索场景，远早于点播）；
    //   · resolveAudio 的 qq: 分支：起播换直链的必经之路（歌单/榜单/导入场景）。
    // 只做进程内内存缓存；key 用完整 `Song.songId`（`qq:<mid>`），与歌词/评论兜底一致。
    /**
     * 6.6：QQ 源歌曲的「版本指纹」元信息。
     * 除了歌名/歌手，还带 **专辑 + 时长 + 音源 id**——歌词匹配必须证明
     * 「时间轴描述的就是耳朵里这条音轨」，不能只靠歌名像就硬套（那会串到
     * Live / 翻唱 / DJ / 试听版，表现就是歌词突然跳一大段）。
     */
    private data class QqLyricMeta(
        val title: String,
        val artist: String,
        val durationMs: Long,
        val album: String = "",
        val sourceId: String = ""
    )
    private val qqLyricMetaCache = java.util.concurrent.ConcurrentHashMap<String, QqLyricMeta>()

    /** 登记一首 QQ 源歌曲的元信息（标题非空才记，避免用空信息污染兜底）。 */
    private fun rememberQqLyricMeta(
        id: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String = ""
    ) {
        if (!id.startsWith("qq:") || title.isBlank()) return
        qqLyricMetaCache[id] = QqLyricMeta(title, artist, durationMs, album, id)
    }

    /**
     * QQ 源歌词兜底时取 title/artist/duration 用，按可靠性依次回退：
     * 1. 调用方传入的 [song]；
     * 2. 进程内 [qqLyricMetaCache]（由 qqSearchSongs / resolveAudio 登记，覆盖搜索与点播）；
     * 3. 在线歌曲元数据缓存 [PlayHistory.knownSongs]（覆盖歌单快照还原的 QQ 歌）。
     * knownSongs() 为纯读缓存，全程 runCatching，异常一律返回 null。
     */
    private fun lyricFallbackMeta(id: String, song: Song?): QqLyricMeta? = runCatching {
        if (song != null) {
            QqLyricMeta(song.title, song.artist, song.durationMs, song.album, song.songId.ifBlank { id })
        } else {
            qqLyricMetaCache[id]
                ?: PlayHistory.knownSongs().firstOrNull { it.songId == id }
                    ?.let { QqLyricMeta(it.title, it.artist, it.durationMs, it.album, id) }
        }
    }.getOrNull()

    // ==================== 封面 ====================

    /** 补封面：xfabe 搜索/榜单偶尔缺失 picurl，用歌曲详情兜底 */
    fun resolveCover(id: String): String {
        httpGet("$NETEASE/song/detail/?id=$id&ids=%5B$id%5D", referer = "https://music.163.com/")
            ?.optJSONArray("songs")?.optJSONObject(0)
            ?.optJSONObject("album")?.optString("picUrl")?.takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    /**
     * 直接下载图片字节（ArtworkLoader 用）。
     *
     * 带 UA；失败时若没指定 Referer，会补一次网易云来源页 Referer 再试——部分图床对
     * 无来源头的请求返回 403 / 空体，补 Referer 后即可拿到，这是"封面老是加载不出来"
     * 最常见的一类可修复原因。
     */
    fun fetchBytes(url: String, referer: String? = null): ByteArray? {
        downloadBytes(url, referer)?.let { return it }
        if (referer == null) return downloadBytes(url, "https://music.163.com/")
        return null
    }

    private fun downloadBytes(url: String, referer: String?): ByteArray? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "image/*,*/*;q=0.8")
        if (referer != null) conn.setRequestProperty("Referer", referer)
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream ?: return null
        val bytes = stream.use { readAll(it) }
        if (bytes.isEmpty()) return null
        bytes
    }.getOrNull()

    // ==================== 工具 ====================

    private fun httpGet(url: String, referer: String? = null): JSONObject? {
        val body = rawGet(url, referer) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun rawGet(
        url: String,
        referer: String? = null,
        connectMs: Int = 12000,
        readMs: Int = 20000
    ): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        if (referer != null) conn.setRequestProperty("Referer", referer)
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream ?: return null
        stream.bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var n: Int
        while (input.read(buf).also { n = it } != -1) out.write(buf, 0, n)
        return out.toByteArray()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

// ==================== QQ 源 → 网易云 id 兜底解析（歌词 / 评论共用） ====================
//
// 背景：6.9 起「QQ音乐」源把 songmid 存进 Song.songId（形如 `qq:<mid>`），
// 但歌词 / 评论接口只认网易云的数字 id。这里按「歌名 + 歌手」去网易云搜一首
// 最匹配的，用它的数字 id 兜底。
//
// 6.9.x 修「QQ 源一律纯音乐」：歌词链路 LyricParser.loadOnline 只把 `ol:` 后的
// 字符串（QQ 源即 `qq:<mid>`）传给 fetchLyricBundle，拿不到 Song；旧兜底只从
// PlayHistory.knownSongs() 反查歌名/歌手，而那张表不含「搜索直接播放 / 歌单点播」
// 的 QQ 歌 → 反查恒空 → 空歌词。现由 qqSearchSongs 与 resolveAudio 在点播前把
// 「mid → 歌名/歌手/时长」登记进内存表（见 object 内 qqLyricMetaCache），
// fetchLyricBundle 仅凭 qq:<mid> 即可完成映射，所有入口统一覆盖。
//
// 本文件内没有 QQ 自己的歌词接口（QQ_CYAPI 只负责把 mid 换成播放直链），
// 因此按需求第 3 条直接走网易云兜底。OnlineMusicApi 是纯联网 object、拿不到
// Context，SharedPreferences 落盘不方便，故只做进程内内存缓存；且只缓存命中，
// miss 不落缓存以便网络恢复 / 元信息补齐后重试。

/**
 * 缓存 key = **完整版本指纹**（音源 id + 歌名 + 歌手 + 专辑 + 时长秒），
 * value = 解析出的网易云数字 id。
 * 以前只用 onlineId 当 key：元信息后来补齐了也一直吃旧缓存，会永远串到错版。
 * 只缓存**命中**的结果：匹配不上不写缓存，切歌 / 重开歌词时还能再试一次。
 */
private val neteaseIdFallbackCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/**
 * 6.6：歌词「没匹配上」的诊断标记，供播放页区分「纯音乐」和「版本对不上」。
 * 匹配失败时明确告诉用户，不再伪装成「纯音乐 · 请欣赏」，也不拿近似结果硬套。
 */
object LyricMatchDiagnostics {
    @Volatile
    var lastMiss: Boolean = false

    @Volatile
    var lastMissDetail: String = ""

    fun mark(sourceId: String, title: String, artist: String, durationMs: Long, album: String) {
        lastMiss = true
        lastMissDetail = buildString {
            append(title)
            if (artist.isNotBlank()) append(" · ").append(artist)
            if (album.isNotBlank()) append(" · ").append(album)
            if (durationMs > 0) append(" · ").append(durationMs / 1000).append("s")
            if (sourceId.isNotBlank()) append(" · ").append(sourceId)
        }
    }

    fun clear() {
        lastMiss = false
        lastMissDetail = ""
    }
}

/** 与 object 内 qqNorm 一致的归一化：去空白/标点，方便同名同人比对。 */
private fun normForNeteaseMatch(x: String): String =
    x.lowercase().replace(Regex("[\\s·、,，.。!！?？'\"“”‘’()（）\\[\\]【】-]"), "")

/**
 * 6.6：QQ 源歌曲 → 网易云数字 id，改用**版本指纹**匹配。
 *
 * 旧逻辑只按「歌名 + 歌手」打分，score>=5 就认，于是 Live / 翻唱 / DJ /
 * 试听剪辑版只要名字像就被当成原曲 —— 歌词时间轴描述的是另一条音轨，
 * 用户感觉到的「越唱越偏、副歌突然跳一大段」基本都出在这里。
 *
 * 现在的硬门槛（宁可显示「未找到匹配歌词」，绝不拿近似结果硬套）：
 *  1. 归一化后歌名必须**完全相等**，模糊包含一律不认；
 *  2. 双方都有时长时差 > 2 秒直接淘汰（版本不同）；
 *  3. 候选是改编 / 剪辑 / 现场版而原曲名没写 → 淘汰；
 *  4. 至少满足「时长吻合」或「歌手 + 专辑同时吻合」之一。
 * 全程 runCatching，任何异常返回 null，绝不外抛。
 */
private fun resolveNeteaseIdBlocking(
    onlineId: String,
    title: String,
    artist: String,
    durationMs: Long = 0L,
    album: String = ""
): String? = runCatching {
    // 本来就是网易云 id（纯数字），直接返回
    if (!onlineId.startsWith("qq:") && onlineId.all { it.isDigit() }) return@runCatching onlineId
    // 既不是 qq: 也不是纯数字（joox 等已在上层处理）→ 不解析
    if (!onlineId.startsWith("qq:")) return@runCatching null
    if (title.isBlank()) return@runCatching null

    val nTitle = normForNeteaseMatch(title)
    val nArtist = normForNeteaseMatch(artist)
    val nAlbum = normForNeteaseMatch(album)
    val fingerprint = listOf(onlineId, nTitle, nArtist, nAlbum, (durationMs / 1000).toString())
        .joinToString("|")
    neteaseIdFallbackCache[fingerprint]?.takeIf { it.isNotBlank() }?.let { return@runCatching it }

    val kw = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
    val songs = NeteaseApi.search(kw, 20)
    val lowSource = title.lowercase()
    // 括号 / 空格 / 中文词，避免 "believe" 里含 "live"、"discover" 里含 "cover" 这类误伤
    val variantWords = listOf(
        "(live)", "（live", " live ", "live版", "(dj)", "（dj", " dj ", "dj版",
        "(remix)", " remix", "remix版", "(inst)", "inst版", "(cover)", " cover",
        "翻唱", "伴奏", "现场", "试听", "片段", "混音", "纯音乐", "不插电"
    )
    // 原曲名自己就带这些标记时（用户找的就是 Live 版），不能再拿它淘汰候选
    val sourceHasVariant = variantWords.any { lowSource.contains(it) }

    var best: String? = null
    var bestScore = -99
    for (s in songs) {
        val sid = s.id
        if (sid.isBlank() || !sid.all { it.isDigit() }) continue
        // ① 歌名必须完全一致
        if (normForNeteaseMatch(s.title) != nTitle) continue

        // ② 时长是版本指纹里最硬的一项：差超过 2 秒就是另一条音轨
        val bothHaveDuration = durationMs > 0 && s.durationMs > 0
        val diffSec = if (bothHaveDuration) kotlin.math.abs(durationMs - s.durationMs) / 1000 else -1L
        if (bothHaveDuration && diffSec > 2) continue

        // ③ 非原版候选直接淘汰
        val lowCandidate = s.title.lowercase()
        if (!sourceHasVariant && variantWords.any { lowCandidate.contains(it) }) continue

        val sArtist = normForNeteaseMatch(s.artist)
        val sAlbum = normForNeteaseMatch(s.album)
        val artistHit = nArtist.isNotBlank() &&
            (sArtist == nArtist || sArtist.contains(nArtist) || nArtist.contains(sArtist))
        val albumHit = nAlbum.isNotBlank() && sAlbum.isNotBlank() &&
            (sAlbum == nAlbum || sAlbum.contains(nAlbum) || nAlbum.contains(sAlbum))
        val durationHit = bothHaveDuration && diffSec <= 2

        // ④ 必须有「同源证据」：时长吻合，或歌手 + 专辑同时吻合
        if (!durationHit && !(artistHit && albumHit)) continue

        var score = 0
        if (durationHit) score += 6
        if (artistHit) score += 4
        if (albumHit) score += 4
        if (score > bestScore) { bestScore = score; best = sid }
    }
    val hit = best
    if (hit != null) {
        neteaseIdFallbackCache[fingerprint] = hit
        LyricMatchDiagnostics.clear()
    } else {
        LyricMatchDiagnostics.mark(onlineId, title, artist, durationMs, album)
    }
    hit
}.getOrNull()

/**
 * 公开：QQ 源歌曲 → 网易云歌曲 id（PlayerViewModel 评论兜底用；歌词兜底见
 * fetchLyricBundle 的 `qq:` 分支）。
 *
 * - `Song.songId` 为纯数字：本来就是网易云，原样返回。
 * - `Song.songId` 以 `qq:` 开头：按「歌名 + 歌手」搜索，返回歌名和歌手都最匹配
 *   的那一首的 id；结果（含匹配不到）做内存缓存。
 * - 任何异常返回 null，绝不抛。
 */
suspend fun resolveNeteaseIdForOnlineSong(song: com.jun.musiccloud.data.Song): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            resolveNeteaseIdBlocking(
                onlineId = song.songId.ifBlank { song.id.toString() },
                title = song.title,
                artist = song.artist,
                durationMs = song.durationMs,
                album = song.album
            )
        }.getOrNull()
    }
