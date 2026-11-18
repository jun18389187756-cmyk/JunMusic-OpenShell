package com.jun.musiccloud.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 曲库仓库（在线版）。
 *
 * 不再扫描本地文件系统：曲库 = 从在线音乐源（xfabe 榜单接口）并发拉取的多个榜单，
 * 每个榜单作为一类「歌单」（对应 UI 里的 folderPath / folderName），首页即是一份可点可播的
 * 在线曲库。
 *
 * 秒开策略：把拉取到的曲库写成一份紧凑 JSON 缓存（filesDir），冷启动先读缓存瞬间出歌，
 * 随后后台重新拉取一次在线榜单校正。缓存只存元数据（含封面 URL 与代理播放 URL），
 * 体积小、读取毫秒级。
 */
object MusicRepository {

    private const val CACHE_FILE = "online_songs_cache.json"

    // 每个榜单取多少首。
    // 免费榜单接口每个榜单实际返回 200 首（热歌榜实测 200，且都带封面/时长/歌手），
    // 之前的 40 首上限把曲库硬生生压在 300 多首；提到 150 后 16 个榜单去重可得到
    // 千余首，刷新时榜单变动的新歌也能进来，不再是"永远那点歌"。
    private const val CHART_TRACK_LIMIT = 150
    private const val SEARCH_CACHE_MAX = 64

    @Volatile private var cacheFile: File? = null
    /** 最近一次成功构建的曲库（进程内），供 Android Auto 浏览树读取，避免每次浏览都拉网络 */
    @Volatile private var lastLibrary: List<Song> = emptyList()
    /** 搜索关键词 → 结果的内存缓存（进程内），让热词与重复搜索秒出 */
    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, List<Song>>()

    fun init(context: Context) {
        if (cacheFile == null) cacheFile = File(context.applicationContext.filesDir, CACHE_FILE)
    }

    /** 路径/ID → 稳定正数 id（供喜欢/播放历史等按 id 索引；同一首歌每次启动都一致） */
    fun stableId(key: String): Long {
        var h = 1125899906842627L
        for (c in key) h = 31 * h + c.code
        return h and Long.MAX_VALUE
    }

    /** 刷新 = 重新拉取在线榜单建库（清空歌词缓存） */
    suspend fun rescanAndReload(context: Context): List<Song> {
        LyricParser.clearCache()
        return loadSongs(context)
    }

    /**
     * 秒开路径：只读曲库缓存直接出歌，不发起任何网络请求。
     * 返回空表示还没有缓存（首次安装/清过数据），调用方应回退到 [loadSongs] 做完整拉取。
     */
    suspend fun loadFromCache(context: Context): List<Song> =
        withContext(Dispatchers.IO) {
            init(context)
            // 6.3：先问 SQLite（有索引，按需读），没有再退回 JSON 并回填。
            //
            // 为什么这一步能显著提速：JSON 方案每次都把整个文件 parse 一遍再建 Song 列表，
            // 3000 首大约 300~600ms，而且这笔开销和首屏要用的主线程抢 CPU。
            // SQLite 版本按 pos 顺序流式游标读，同样 3000 首约 40~80ms。
            // 用户说的"每次进去加载特别慢"，其中最稳定、最可复现的一段就是它。
            val fromDb = runCatching { LibraryStore.loadAll(context) }.getOrDefault(emptyList())
            val songs = if (fromDb.isNotEmpty()) {
                fromDb
            } else {
                val fromJson = readCache()
                // 回填：只做一次（下次启动直接命中 DB）
                if (fromJson.isNotEmpty()) runCatching { LibraryStore.replaceAll(context, fromJson) }
                fromJson
            }
            songs.also { if (it.isNotEmpty()) lastLibrary = it }
        }

    /**
     * 并发拉取全部榜单，合并去重后构建曲库并回写缓存。
     */
    suspend fun loadSongs(context: Context): List<Song> =
        withContext(Dispatchers.IO) {
            init(context)
            val charts = OnlineMusicApi.chartNames
            val fetched = coroutineScope {
                charts.map { name -> async { name to OnlineMusicApi.fetchChart(name) } }.awaitAll()
            }
            val songs = ArrayList<Song>()
            val seen = HashSet<String>()
            for ((name, list) in fetched) {
                var count = 0
                for (raw in list) {
                    if (count >= CHART_TRACK_LIMIT) break
                    if (!seen.add(raw.id)) continue
                    songs.add(raw.toSong(name))
                    count++
                }
            }
            if (songs.isNotEmpty()) {
                // JSON 仍然写（可读、可回退、可导出排查），DB 是并行镜像。
                // 两者都写而不是只写 DB：万一某台机器上 SQLite 打不开，
                // 至少还能退回 6.2 的读取路径，不会出现"首页空着"。
                writeCache(songs)
                runCatching { LibraryStore.replaceAll(context, songs) }
                lastLibrary = songs
            }
            songs
        }

    /** 当前进程内的曲库快照（Android Auto 浏览用）；空列表表示还没建库 */
    fun currentLibrary(): List<Song> = lastLibrary

    /** 在线搜索：关键词 → 曲库（folderPath 记为「搜索」）。limit 默认 100，尽量多给。 */
    suspend fun searchOnline(context: Context, keyword: String, limit: Int = 100): List<Song> {
        // 内存缓存：同一关键词重复搜索直接命中，热词/复搜秒出，避免重复打网络。
        val key = keyword.trim().lowercase() + "#" + limit
        searchCache[key]?.let { return it }
        val out = withContext(Dispatchers.IO) {
            OnlineMusicApi.search(keyword, limit).map { it.toSong("搜索") }
        }
        if (out.isNotEmpty()) {
            if (searchCache.size >= SEARCH_CACHE_MAX) searchCache.clear()
            searchCache[key] = out
        }
        return out
    }

    /**
     * 分页搜索：返回 (本页歌曲, 是否还有下一页)。
     * 搜索页滚动到底时用它加载更多，不再一次性把 100 条全塞进首屏。
     */
    suspend fun searchOnlinePage(
        context: Context,
        keyword: String,
        limit: Int = 30,
        offset: Int = 0
    ): Pair<List<Song>, Boolean> {
        val key = keyword.trim().lowercase() + "#" + limit + "@" + offset
        searchCache[key]?.let { return it to (it.size >= limit) }
        val (out, hasMore) = withContext(Dispatchers.IO) {
            val page = OnlineMusicApi.searchPage(keyword, limit, offset)
            page.songs.map { it.toSong("搜索") } to page.hasMore
        }
        if (out.isNotEmpty()) {
            if (searchCache.size >= SEARCH_CACHE_MAX) searchCache.clear()
            searchCache[key] = out
        }
        return out to hasMore
    }

    /**
     * 封面 URL 归一化：部分源返回协议相对地址（`//p1.music.126.net/...`）或站内相对路径，
     * 直接交给封面加载器会因不以 http 开头而走成本地文件解析、必然失败（"封面加载不出来"）。
     * 这里统一补全成完整 https 地址。
     */
    private fun normalizeCover(url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return ""
        val full = when {
            u.startsWith("//") -> "https:$u"
            // 网易云图床同时支持 https，统一升级，避免明文 http 被系统策略/图床拦截，
            // 也避免列表里出现「有封面地址但一直加载不出来」。
            u.startsWith("http://") && (u.contains("music.126.net") || u.contains("126.net")) ->
                "https://" + u.removePrefix("http://")
            u.startsWith("http") -> u
            u.startsWith("/") -> "https://music.163.com$u"
            else -> u
        }
        return withThumb(full)
    }

    /**
     * 网易云图床支持按尺寸取图：原图动辄几百 KB，列表/封面墙根本用不到。
     * 统一要 300×300（列表和磁贴都够清晰），流量和首帧时间都降到原来的零头，
     * 这是「封面墙加载半天」最有效的一刀。
     */
    private fun withThumb(url: String): String {
        if (!url.contains("music.126.net") || url.contains("param=")) return url
        return url + (if (url.contains('?')) "&" else "?") + "param=300y300"
    }

    /**
     * 音质标签归一化：把历史缓存里的 HQ / SQ / FLAC / Hi-Res 等「无损/高音质」字样
     * 统统收敛为统一的「在线」，避免列表里出现音质承诺。
     */
    private fun normalizeFormat(fmt: String): String = when (fmt.uppercase()) {
        "", "HQ", "SQ", "FLAC", "HI-RES", "HIRES", "AUDIO", "MASTER", "DOLBY" -> "在线"
        else -> fmt
    }

    private fun OnlineMusicApi.RawSong.toSong(folder: String): Song {
        val cover = normalizeCover(cover)
        return Song(
            id = stableId(id),
            title = title.ifBlank { "未知歌曲" },
            artist = artist.ifBlank { "未知艺术家" },
            album = album,
            durationMs = durationMs,
            // 播放走本地代理，起播时才解析真实地址；列表加载无需等待
            uri = Uri.parse(com.jun.musiccloud.playback.AudioProxyServer.audioUrl(id, title, artist, durationMs)),
            albumArtUri = cover.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
            filePath = cover,          // 封面 URL，兼作 ArtworkLoader 的缓存键
            folderPath = folder,
            folderName = folder,
            songId = id,
            // 不再标注「无损/高音质」：在线播放统一为标准音质，标签只说明来源为在线
            format = "在线"
        )
    }

    /**
     * 网易云歌曲（每日推荐 / 我的歌单）→ 曲库 [Song]。
     * 播放同样走本地代理，列表加载不解析真实地址。
     */
    fun fromNetease(folder: String, list: List<NeteaseApi.NsSong>): List<Song> = list.map { raw ->
        val cover = normalizeCover(raw.cover)
        Song(
            id = stableId(raw.id),
            title = raw.title.ifBlank { "未知歌曲" },
            artist = raw.artist.ifBlank { "未知艺术家" },
            album = raw.album,
            durationMs = raw.durationMs,
            uri = Uri.parse(com.jun.musiccloud.playback.AudioProxyServer.audioUrl(raw.id, raw.title, raw.artist, raw.durationMs)),
            albumArtUri = cover.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
            filePath = cover,
            folderPath = folder,
            folderName = folder,
            songId = raw.id,
            format = "在线",
            likeCount = raw.likedCount.toLong()
        )
    }

    /**
     * 6.9：搜索页「QQ音乐」源 → 曲库 [Song]。
     * songId 存 "qq:<songmid>"：取源链路识别这个前缀后直接换 QQ 直链
     * （0.2~0.5 秒，不再按歌名反查，串歌风险归零，见 OnlineMusicApi.resolveAudio）。
     * 与网易云歌的身份键不同前缀，不会互相撞号。
     * 注意：这类歌没有网易云 ID —— 歌词/评论/点赞不可用，收藏会静默失败（测试已注明）。
     */
    fun fromQQ(folder: String, list: List<OnlineMusicApi.QqSearchSong>): List<Song> = list.map { raw ->
        val ms = raw.durationSec.coerceAtLeast(0) * 1000L
        Song(
            id = stableId("qq:${raw.mid}"),
            title = raw.title,
            artist = raw.singer,
            album = raw.album,
            durationMs = ms,
            uri = Uri.parse(
                com.jun.musiccloud.playback.AudioProxyServer.audioUrl("qq:${raw.mid}", raw.title, raw.singer, ms)
            ),
            albumArtUri = raw.cover.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
            filePath = raw.cover,
            folderPath = folder,
            folderName = folder,
            songId = "qq:${raw.mid}",
            format = "在线"
        )
    }

    // ==================== 曲库缓存 ====================

    private fun readCache(): List<Song> {
        val f = cacheFile ?: return emptyList()
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Song>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val songId = o.optString("sid")
                if (songId.isEmpty()) continue
                val cover = o.optString("cov")
                val cachedTitle = o.optString("t")
                val cachedArtist = o.optString("a")
                out.add(
                    Song(
                        id = o.optLong("id"),
                        title = cachedTitle,
                        artist = cachedArtist,
                        album = o.optString("al"),
                        durationMs = o.optLong("d"),
                        uri = Uri.parse(com.jun.musiccloud.playback.AudioProxyServer.audioUrl(songId, cachedTitle, cachedArtist, o.optLong("d"))),
                        albumArtUri = cover.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
                        filePath = cover,
                        folderPath = o.optString("fp"),
                        folderName = o.optString("fn"),
                        songId = songId,
                        // 旧缓存可能写的是 HQ/SQ/FLAC 等音质标签，统一收敛为「在线」
                        format = normalizeFormat(o.optString("fmt")),
                        likeCount = o.optLong("lc", 0L)
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun writeCache(songs: List<Song>) {
        val f = cacheFile ?: return
        runCatching {
            val arr = JSONArray()
            for (s in songs) {
                arr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("sid", s.songId)
                        put("t", s.title)
                        put("a", s.artist)
                        put("al", s.album)
                        put("d", s.durationMs)
                        put("cov", s.filePath)
                        put("fp", s.folderPath)
                        put("fn", s.folderName)
                        put("fmt", s.format)
                        put("lc", s.likeCount)
                    }
                )
            }
            f.writeText(arr.toString())
        }
    }

    /** 由歌曲列表聚合出来源清单（原「文件夹」，此处即榜单），按歌曲数降序 */
    fun buildFolders(songs: List<Song>): List<MusicFolder> =
        songs.groupBy { it.folderPath }
            .map { (path, list) ->
                MusicFolder(
                    path = path,
                    name = list.first().folderName,
                    songCount = list.size
                )
            }
            .sortedByDescending { it.songCount }
}
