package com.jun.musiccloud.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自定义歌单：纯「歌曲 ID 集合」记录，非文件夹分类。
 *
 * 用户可以创建歌单、往歌单里加歌/移出歌、删除歌单。持久化到 SharedPreferences，
 * 只记歌单名 + 歌曲 ID 列表（有序，保持加入顺序）。
 *
 * 存储格式：
 * - playlist_names:   "自增ID:名称;自增ID:名称;..."
 * - playlist_songs_<id>:  "songId,songId,songId,..."
 * - playlist_next_id: 下一个自增 ID
 */
object Playlists {

    /**
     * 6.3：歌单归到**当前账号**的分区里（见 [AccountScope]）。
     * 6.2 用的是独立的 `jun_playlists` 文件，仍然不分账号——A 建的歌单 B 登录后照样看得见。
     * 现在跟着 PlayHistory 一起走 `jm_account_<uid>`，`playlist_songs_` 前缀也在隔离名单里。
     */
    private const val PREFS = AccountScope.LEGACY_PREFS
    private const val KEY_NAMES = "playlist_names"
    private const val KEY_NEXT_ID = "playlist_next_id"
    private const val KEY_SONGS_PREFIX = "playlist_songs_"

    private lateinit var prefs: SharedPreferences

    @Volatile private var cachedPlaylists: List<Playlist>? = null
    private val writeLock = Any()

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = AccountScope.prefs(context)
        }
        if (appCtx == null) appCtx = context.applicationContext
    }

    /** 切账号：换分区并丢掉内存缓存（理由同 [PlayHistory.rebind]） */
    fun rebind(context: Context) {
        prefs = AccountScope.prefs(context)
        cachedPlaylists = null; songIndex = null
        appCtx = context.applicationContext
        metaCache = null
        playableCache = null
    }

    // ==================== 歌单曲目元数据（6.9.6） ====================
    //
    // 为什么必须单独存一份：
    //   歌单本身只记 songId（Long），曲目元数据要靠 PlayHistory.knownSongs() 反查，
    //   而那份缓存**上限 1000 首、且所有歌单共用一条 LRU**。导入一个 1000 首的歌单
    //   会把它整条塞满，之后任何时候写进一首新歌，最老的那批就被挤出去 ——
    //   用户看到的是"歌单里的歌莫名其妙少了"（songsOfPlaylist 里 mapNotNull 直接丢）。
    //   这里给歌单的曲目单独落一份文件，与 knownSongs 完全解耦：导入多少首就存多少首。
    //
    // 落文件而不是 prefs：1000 首约 175KB，prefs 是整份 XML 一次加载，
    // 塞进去会把冷启动拖慢；文件按账号分，切号不串。
    @Volatile private var metaCache: Map<Long, Song>? = null
    @Volatile private var appCtx: Context? = null

    private const val META_MAX = 5000

    private fun metaFile(): File? {
        val ctx = appCtx ?: return null
        val uid = AccountScope.activeUid()
        return File(File(ctx.filesDir, "playlist_meta"), if (uid > 0L) "$uid.dat" else "guest.dat")
    }

    /** 记住一批歌曲元数据（导入歌单时调用）。同 id 覆盖，总量超 [META_MAX] 丢最早的。 */
    fun rememberSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        synchronized(writeLock) {
            val cur = LinkedHashMap(metaCache ?: readMeta())
            for (s in songs) cur[s.id] = s
            val finalMap: Map<Long, Song> = if (cur.size > META_MAX) {
                val keep = cur.entries.toList().takeLast(META_MAX)
                LinkedHashMap<Long, Song>(META_MAX).apply { for ((k, v) in keep) put(k, v) }
            } else cur
            metaCache = finalMap
            writeMeta(finalMap)
        }
    }

    /**
     * 按本地 id 取歌单曲目元数据（[rememberSongs] 存进来的那批）。
     *
     * 必须走缓存：这个方法会在 `songsOfPlaylist` 里对**每个 id 调一次**，
     * 一个千首歌单就是 1000 次调用 —— 每次都 readMeta() 等于读 1000 次磁盘，
     * 界面直接卡死。所以第一次读进 metaCache 之后就一直用它。
     */
    fun songById(id: Long): Song? {
        val m = metaCache ?: readMeta().also { metaCache = it }
        return m[id]
    }

    // ==================== 可播标记（6.4） ====================
    //
    // 服务端在导入时按网易云的 fee 字段预判了「哪些歌要换源」，把网易云 id 列表
    // 一起回来了。这里按 songId 存一份映射，供歌单详情的「只看可播」过滤。
    //
    // 为什么单独一个文件而不是塞进 Song：
    //   Song 是全 App 通用的数据类，加一个字段要改序列化、改所有构造点；
    //   而这个标记只对"歌单过滤"有用，生命周期也跟歌单走，独立存最省事。
    //
    // 只存 false（要换源）的，不存 true —— 绝大多数歌是可播的，
    // 只记例外既省空间，也天然对新导入的歌默认可播。
    @Volatile private var playableCache: Map<String, Boolean>? = null

    private fun playableFile(): File? {
        val ctx = appCtx ?: return null
        val uid = AccountScope.activeUid()
        return File(File(ctx.filesDir, "playlist_playable"), if (uid > 0L) "$uid.dat" else "guest.dat")
    }

    private fun readPlayable(): Map<String, Boolean> {
        val f = playableFile() ?: return emptyMap()
        return runCatching {
            if (!f.exists()) return emptyMap()
            val obj = JSONObject(f.readText())
            HashMap<String, Boolean>(obj.length()).apply {
                for (k in obj.keys()) put(k, obj.optBoolean(k, true))
            }
        }.getOrDefault(emptyMap())
    }

    private fun writePlayable(map: Map<String, Boolean>) {
        val f = playableFile() ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val obj = JSONObject()
            for ((k, v) in map) obj.put(k, v)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(obj.toString())
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
    }

    /**
     * 标记一批歌的可播性。[needSourceIds] 是服务端判定的「需要换源」的歌。
     * 导入时调用一次；播放失败时也可以单独纠正某一首。
     */
    fun markPlayable(playableIds: Collection<String>, needSourceIds: Collection<String>) {
        if (playableIds.isEmpty() && needSourceIds.isEmpty()) return
        synchronized(writeLock) {
            val cur = HashMap(playableCache ?: readPlayable())
            for (id in playableIds) if (id.isNotBlank()) cur[id] = true
            for (id in needSourceIds) if (id.isNotBlank()) cur[id] = false
            // 上限 8000：再多说明用户导了几十个歌单，老标记按插入序丢掉
            val finalMap: Map<String, Boolean> = if (cur.size > 8000) {
                LinkedHashMap<String, Boolean>().apply {
                    for ((k, v) in cur.entries.toList().takeLast(8000)) put(k, v)
                }
            } else cur
            playableCache = finalMap
            writePlayable(finalMap)
        }
    }

    /** 单首纠正（播放失败时调用）。null = 没标记过，按可播处理。 */
    fun setSongPlayable(songId: String, playable: Boolean) {
        if (songId.isBlank()) return
        synchronized(writeLock) {
            val cur = HashMap(playableCache ?: readPlayable())
            cur[songId] = playable
            playableCache = cur
            writePlayable(cur)
        }
    }

    /** 查询：null 表示没有标记（按可播处理，不误导用户）。 */
    fun isSongPlayable(songId: String): Boolean? {
        if (songId.isBlank()) return null
        val m = playableCache ?: readPlayable().also { playableCache = it }
        return m[songId]
    }

    /** 当前有没有任何可播标记（没有的话歌单详情不显示「只看可播」，免得点了没反应）。 */
    fun hasPlayableData(): Boolean {
        val m = playableCache ?: readPlayable().also { playableCache = it }
        return m.isNotEmpty()
    }

    private fun writeMeta(map: Map<Long, Song>) {
        val file = metaFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            for (s in map.values) {
                arr.put(
                    JSONObject().apply {
                        put("i", s.id)
                        put("sid", s.songId)
                        put("t", s.title)
                        put("a", s.artist)
                        put("al", s.album)
                        put("d", s.durationMs)
                        put("cov", s.filePath)
                        put("fp", s.folderPath)
                        put("fn", s.folderName)
                        put("lc", s.likeCount)
                    }
                )
            }
            // 先写 .tmp 再 rename：写到一半被杀进程不会留下半截 JSON，
            // 读到一个残缺数组会让整个歌单变成空的
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    private fun readMeta(): Map<Long, Song> {
        val file = metaFile() ?: return emptyMap()
        if (!file.exists()) return emptyMap()
        return runCatching {
            val arr = JSONArray(file.readText())
            val out = LinkedHashMap<Long, Song>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("i", 0L)
                if (id == 0L) continue
                val songId = o.optString("sid")
                val cover = o.optString("cov")
                val title = o.optString("t")
                val artist = o.optString("a")
                val dur = o.optLong("d")
                out[id] = Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = o.optString("al"),
                    durationMs = dur,
                    uri = Uri.parse(
                        com.jun.musiccloud.playback.AudioProxyServer.audioUrl(songId, title, artist, dur)
                    ),
                    albumArtUri = cover.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
                    filePath = cover,
                    folderPath = o.optString("fp"),
                    folderName = o.optString("fn"),
                    songId = songId,
                    format = "在线",
                    likeCount = o.optLong("lc")
                )
            }
            out
        }.getOrDefault(emptyMap())
    }

    // ==================== 查询 ====================

    /** 所有歌单（按创建顺序，不含歌曲明细） */
    fun all(): List<Playlist> = cachedPlaylists ?: readAll().also { cachedPlaylists = it }

    /** 单个歌单的歌曲 ID 列表（有序） */
    fun songs(playlistId: Long): List<Long> {
        val raw = prefs.getString(KEY_SONGS_PREFIX + playlistId, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }
    }

    // ==================== 增删改 ====================

    /** 创建歌单，返回新歌单 ID */
    fun create(name: String): Long = synchronized(writeLock) {
        val id = prefs.getLong(KEY_NEXT_ID, 1L)
        val cur = readNamesMap()
        cur[id] = name.trim().ifBlank { "歌单 $id" }
        writeNames(cur)
        prefs.edit().putLong(KEY_NEXT_ID, id + 1).apply()
        cachedPlaylists = null; songIndex = null
        id
    }

    /** 重命名歌单 */
    fun rename(playlistId: Long, newName: String): Boolean = synchronized(writeLock) {
        val cur = readNamesMap()
        if (playlistId !in cur) return@synchronized false
        cur[playlistId] = newName.trim().ifBlank { "歌单 $playlistId" }
        writeNames(cur)
        cachedPlaylists = null; songIndex = null
        true
    }

    /** 删除歌单 */
    fun delete(playlistId: Long): Boolean = synchronized(writeLock) {
        val cur = readNamesMap()
        if (playlistId !in cur) return@synchronized false
        cur.remove(playlistId)
        writeNames(cur)
        prefs.edit().remove(KEY_SONGS_PREFIX + playlistId).apply()
        cachedPlaylists = null; songIndex = null
        true
    }

    /** 往歌单追加一首歌，已存在则忽略 */
    fun addSong(playlistId: Long, songId: Long): Boolean = synchronized(writeLock) {
        if (playlistId !in readNamesMap()) return@synchronized false
        val list = songs(playlistId).toMutableList()
        if (songId in list) return@synchronized false
        list.add(songId)
        prefs.edit().putString(KEY_SONGS_PREFIX + playlistId, list.joinToString(",")).apply()
        cachedPlaylists = null; songIndex = null
        true
    }

    /** 批量追加（导入歌单用）：一次写入，避免几百首歌逐条改写 SharedPreferences */
    fun addSongs(playlistId: Long, songIds: List<Long>): Int = synchronized(writeLock) {
        if (playlistId !in readNamesMap()) return@synchronized 0
        val list = songs(playlistId).toMutableList()
        val before = list.size
        val seen = list.toHashSet()
        for (id in songIds) {
            if (seen.add(id)) list.add(id)
        }
        val added = list.size - before
        if (added > 0) {
            prefs.edit().putString(KEY_SONGS_PREFIX + playlistId, list.joinToString(",")).apply()
            cachedPlaylists = null; songIndex = null
        }
        added
    }

    /** 从歌单移除一首歌 */
    fun removeSong(playlistId: Long, songId: Long): Boolean = synchronized(writeLock) {
        if (playlistId !in readNamesMap()) return@synchronized false
        val list = songs(playlistId).toMutableList()
        if (songId !in list) return@synchronized false
        list.remove(songId)
        prefs.edit().putString(KEY_SONGS_PREFIX + playlistId, list.joinToString(",")).apply()
        cachedPlaylists = null; songIndex = null
        true
    }

    /** 歌曲是否在歌单中 */
    fun contains(playlistId: Long, songId: Long): Boolean = songId in songs(playlistId)

    /** 歌曲所属的所有歌单名（按创建顺序） */
    fun playlistsOfSong(songId: Long): List<String> =
        songToPlaylists()[songId] ?: emptyList()

    /**
     * 6.3.2：songId → 歌单名 的反向索引（懒构建、写入时失效）。
     *
     * 原来 [playlistsOfSong] 每行都要「读一遍所有歌单的 prefs 字符串 + split 成 Long」，
     * 首页全库列表有 3000 行时，只要开着「显示所属歌单」，滚动一屏就要做几十次
     * prefs 读取 + 上百次字符串解析，全在主线程——这是"划一下顿一下"的隐蔽来源。
     * 现在整表只建一次，任何一个歌单被改动就置空重建。
     */
    @Volatile
    private var songIndex: Map<Long, List<String>>? = null

    private fun songToPlaylists(): Map<Long, List<String>> {
        songIndex?.let { return it }
        val map = HashMap<Long, MutableList<String>>(256)
        for (pl in all()) {
            for (sid in songs(pl.id)) {
                map.getOrPut(sid) { ArrayList(2) }.add(pl.name)
            }
        }
        val frozen: Map<Long, List<String>> = map.mapValues { it.value.toList() }
        songIndex = frozen
        return frozen
    }

    /** 任何会改变「哪首歌在哪个歌单」的操作都要调它（见下面各写方法） */
    private fun invalidateSongIndex() {
        songIndex = null
    }

    /**
     * 歌曲移动后把各歌单里的旧 ID 换成新 ID（原位替换，保持加入顺序）。
     * 新 ID 已在歌单里则丢弃旧条目去重。
     */
    fun remapIds(idMap: Map<Long, Long>): Unit = synchronized(writeLock) {
        if (idMap.isEmpty()) return@synchronized
        val edit = prefs.edit()
        var changed = false
        for (pid in readNamesMap().keys) {
            val key = KEY_SONGS_PREFIX + pid
            val list = songs(pid)
            val mapped = ArrayList<Long>(list.size)
            for (id in list) {
                val nid = idMap[id] ?: id
                if (nid !in mapped) mapped.add(nid)
            }
            if (mapped != list) {
                edit.putString(key, mapped.joinToString(","))
                changed = true
            }
        }
        cachedPlaylists = null; songIndex = null
        if (changed) edit.apply()
    }

    // ==================== 内部 ====================

    // ==================== 整理（6.4） ====================
    //
    // 设计依据：大众音乐平台的通行分层是「临时队列 / 最近播放 / 我喜欢 / 自己的歌单」四层。
    // 混乱的来源只有两个，都可以机械地识别和清理：
    //   a) 碎片歌单——只有一两首歌的"临时中转站"，建的时候没想清楚，之后就一直是乱的；
    //   b) 重复歌单——两个歌单里 80% 以上是同一批歌，用户自己也分不清该点哪个。
    // 这里只**报告**不自动改，并在报告里给出一个可一键执行的方案，让用户确认。

    /** 一个歌单的体检结果 */
    data class PlaylistHealth(
        val id: Long,
        val name: String,
        val songCount: Int,
        /** 与其它歌单的重合度（0f..1f），取最大那个 */
        val overlap: Float = 0f,
        /** 重合度最高的那个歌单 id */
        val overlapWith: Long = -1L,
        /** 碎片：1~2 首，且不是刚建的（歌单 id 大不代表新，用首数判断足够） */
        val fragment: Boolean = false
    )

    /** 整库体检报告 */
    data class LibraryHealth(
        val playlists: List<PlaylistHealth>,
        /** 碎片歌单（建议并入主题歌单） */
        val fragments: List<PlaylistHealth>,
        /** 重复歌单对（建议合并） */
        val duplicates: List<Pair<PlaylistHealth, PlaylistHealth>>,
        /** 主题超过 5 个：说明"分得太细"，也是乱的一种 */
        val tooManyThemes: Boolean
    )

    /**
     * 检查整个歌单库。
     *
     * 复杂度 O(n²·m)，n 是歌单数、m 是平均首歌数。实际场景 n ≤ 30、m ≤ 500，
     * 用集合求交，几十毫秒级；调用方仍应放在 IO 线程（见 [PlayerViewModel.auditLibrary]）。
     */
    fun audit(minOverlap: Float = 0.8f): LibraryHealth {
        val all = all()
        val ids = all.associate { it.id to songs(it.id).toHashSet() }
        val reports = ArrayList<PlaylistHealth>(all.size)

        for (p in all) {
            val mine = ids[p.id].orEmpty()
            var bestOverlap = 0f
            var bestId = -1L
            if (mine.isNotEmpty()) {
                for (q in all) {
                    if (q.id == p.id) continue
                    val other = ids[q.id].orEmpty()
                    if (other.isEmpty()) continue
                    val inter = mine.count { it in other }
                    val ratio = inter.toFloat() / minOf(mine.size, other.size)
                    if (ratio > bestOverlap) {
                        bestOverlap = ratio
                        bestId = q.id
                    }
                }
            }
            reports.add(
                PlaylistHealth(
                    id = p.id,
                    name = p.name,
                    songCount = p.songCount,
                    overlap = bestOverlap,
                    overlapWith = bestId,
                    fragment = p.songCount in 1..2
                )
            )
        }

        val dupPairs = ArrayList<Pair<PlaylistHealth, PlaylistHealth>>()
        val seenPair = HashSet<String>()
        for (r in reports) {
            if (r.overlap < minOverlap) continue
            val other = reports.firstOrNull { it.id == r.overlapWith } ?: continue
            val key = if (r.id < other.id) "${r.id}-${other.id}" else "${other.id}-${r.id}"
            if (!seenPair.add(key)) continue
            // 大的留、小的并进去：这是唯一说得通的合并方向
            val pair = if (r.songCount >= other.songCount) r to other else other to r
            dupPairs.add(pair)
        }

        val themes = reports.filterNot { it.fragment }
        return LibraryHealth(
            playlists = reports,
            fragments = reports.filter { it.fragment },
            duplicates = dupPairs,
            tooManyThemes = themes.size > 5
        )
    }

    /**
     * 一键整理：
     *   1. 碎片歌单里的歌**并进**指定的目标歌单（[into]），然后删掉碎片歌单；
     *   2. 重复歌单里小的并进大的，然后删掉小的。
     *
     * 返回 (合并的歌数, 删掉的歌单数)。全部操作在一个同步块里做完，
     * 中途失败不会留下"歌被复制了两遍"的中间态（[addSongs] 自带去重）。
     *
     * [into] = -1 时第一步只删碎片歌单不动歌（用户明确说"这些歌我不要了"时用）。
     */
    fun tidyUp(into: Long = -1L, minOverlap: Float = 0.8f): Pair<Int, Int> = synchronized(writeLock) {
        val health = audit(minOverlap)
        var movedSongs = 0
        var removedPlaylists = 0

        // 先处理重复：大的留、小的并进去
        for ((keep, drop) in health.duplicates) {
            if (keep.id !in readNamesMap() || drop.id !in readNamesMap()) continue
            val incoming = songs(drop.id)
            if (incoming.isNotEmpty()) movedSongs += addSongs(keep.id, incoming)
            if (delete(drop.id)) removedPlaylists++
        }

        // 再处理碎片：并进目标歌单（目标已在上一步存活下来的话）
        for (f in health.fragments) {
            if (f.id !in readNamesMap()) continue          // 已经被上一步合并掉了
            if (into >= 0L && into in readNamesMap()) {
                val incoming = songs(f.id)
                if (incoming.isNotEmpty()) movedSongs += addSongs(into, incoming)
            }
            if (delete(f.id)) removedPlaylists++
        }
        movedSongs to removedPlaylists
    }

    private fun readAll(): List<Playlist> {
        val names = readNamesMap()
        // 按创建顺序（ID 升序）
        return names.entries.sortedBy { it.key }.map { (id, name) ->
            Playlist(id = id, name = name, songCount = songs(id).size)
        }
    }

    private fun readNamesMap(): MutableMap<Long, String> {
        val raw = prefs.getString(KEY_NAMES, "") ?: ""
        val map = linkedMapOf<Long, String>()
        if (raw.isBlank()) return map
        for (part in raw.split(';')) {
            val kv = part.split(':')
            val id = kv.getOrNull(0)?.trim()?.toLongOrNull() ?: continue
            val name = kv.getOrNull(1) ?: continue
            map[id] = name
        }
        return map
    }

    private fun writeNames(map: Map<Long, String>) {
        val raw = map.entries.joinToString(";") { "${it.key}:${it.value}" }
        prefs.edit().putString(KEY_NAMES, raw).apply()
    }
}

/** 歌单元数据（不含歌曲明细，明细由 [Playlists.songs] 按需读取） */
@androidx.compose.runtime.Immutable
data class Playlist(
    val id: Long,
    val name: String,
    val songCount: Int
)