package com.jun.musiccloud.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * 最近播放会话：一次「播放整张歌单/榜单/搜索结果」会生成一条记录。
 * 用于「最近播放」横向滑动区，左右滑可切回之前听过的歌单。
 * 持久化到 SharedPreferences，重启不丢失。
 */
data class RecentPlayback(
    val title: String,
    val songs: List<Song>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 播放历史追踪器：记录每首歌的播放次数、跳过次数、最后播放时间。
 * 持久化到 SharedPreferences，格式简单紧凑。
 *
 * 存储格式：
 * - play_counts: "songId:count;songId:count;..."
 * - skip_counts: 同上
 * - last_played: "songId:timestamp;..."
 */
object PlayHistory {

    /**
     * 6.3：存储位置从全局 `jun_prefs` 换成**按账号分区**（见 [AccountScope]）。
     *
     * 为什么这是「换号后我喜欢全体串号」的根因：以前不管谁登录，都读写同一个
     * `jun_prefs`。「我喜欢」在用 A 账号点的赞、B 登录进来看到的还是同一份。
     * 现在每个人的 liked_songs / likes 落在 `jm_account_<uid>` 里，互不可见。
     *
     * 常量 [PREFS] 保留，只是给 [AccountScope] 的迁移逻辑引用，业务代码不再直接用它。
     */
    private const val PREFS = AccountScope.LEGACY_PREFS
    private const val KEY_PLAY_COUNTS = "play_counts"
    private const val KEY_SKIP_COUNTS = "skip_counts"
    private const val KEY_LAST_PLAYED = "last_played"
    private const val KEY_LIKES = "likes"
    private const val KEY_LIKED_SONGS = "liked_songs"
    private const val KEY_PENDING_UNLIKES = "pending_unlikes"
    private const val KEY_KNOWN_SONGS = "known_online_songs"
    private const val KEY_TOTAL_PLAYED_MS = "total_played_ms"
    private const val KEY_INCOMPLETE = "incomplete_counts"
    private const val KEY_RECENT_PLAYBACKS = "recent_playbacks"

    private lateinit var prefs: SharedPreferences
    /** 6.9.5：镜像文件要写 filesDir，存一份 applicationContext */
    @Volatile private var appContext: Context? = null

    /** 完播判定：播放进度不足 25% 视为未完播 */
    const val COMPLETE_RATIO = 0.25f

    // ===== 内存缓存：避免每次切歌读写 5 次 SharedPreferences =====
    @Volatile private var cachedPlayCounts: Map<Long, Int>? = null
    @Volatile private var cachedSkipCounts: Map<Long, Int>? = null
    @Volatile private var cachedLastPlayed: Map<Long, Long>? = null
    @Volatile private var cachedTotalPlayedMs: Map<Long, Long>? = null
    @Volatile private var cachedIncomplete: Map<Long, Int>? = null
    @Volatile private var cachedLikes: Set<Long>? = null
    /** 离线取消的赞：服务器 song_id 集合，网络恢复后逐个补删，避免“取消的喜欢”下次同步又复活 */
    @Volatile private var cachedPendingUnlikes: Set<String>? = null
    /** 已点赞歌曲的完整元数据（含在线搜索结果，曲库列表里没有的也能在「我喜欢」里看到） */
    @Volatile private var cachedLikedSongs: List<Song>? = null
    /** 在线歌曲元数据缓存：从搜索结果加进歌单的歌不在曲库 allSongs 里，靠它按 ID 还原 */
    @Volatile private var cachedKnownSongs: List<Song>? = null
    /** 最近播放会话缓存 */
    @Volatile private var cachedRecentPlaybacks: List<RecentPlayback>? = null

    private val writeLock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!::prefs.isInitialized) {
            prefs = AccountScope.prefs(context)
        }
    }

    /**
     * 切账号时把 prefs 指向新分区，并清空所有内存缓存。
     *
     * 缓存不清的话，B 登录后第一次点「我喜欢」读到的还是 A 那 30 首（cachedLikedSongs），
     * 等下一次落盘还会把 A 的列表写进 B 的分区——串号就从"看得见"升级成"写脏了"。
     */
    fun rebind(context: Context) {
        prefs = AccountScope.prefs(context)
        cachedPlayCounts = null
        cachedSkipCounts = null
        cachedLastPlayed = null
        cachedTotalPlayedMs = null
        cachedIncomplete = null
        cachedLikes = null
        cachedPendingUnlikes = null
        cachedLikedSongs = null
        cachedKnownSongs = null
        cachedRecentPlaybacks = null
        Recommender.invalidate()
    }

    // ==================== 公共 API ====================

    /**
     * 记录一次播放结束（切歌/停止时调用）。
     * @param songId 歌曲 ID
     * @param playedMs 实际播放时长（毫秒）
     * @param durationMs 歌曲总时长（毫秒），用于完播判定
     */
    fun recordPlay(songId: Long, playedMs: Long, durationMs: Long = 0L) {
        synchronized(writeLock) {
            val counts = (cachedPlayCounts ?: readCounts(KEY_PLAY_COUNTS)).toMutableMap()
            val skips = (cachedSkipCounts ?: readCounts(KEY_SKIP_COUNTS)).toMutableMap()
            val lastPlayed = (cachedLastPlayed ?: readLastPlayed()).toMutableMap()
            val totals = (cachedTotalPlayedMs
                ?: readCounts(KEY_TOTAL_PLAYED_MS).mapValues { it.value.toLong() }).toMutableMap()
            val incomplete = (cachedIncomplete ?: readCounts(KEY_INCOMPLETE)).toMutableMap()

            // 累计总播放时长
            totals[songId] = (totals[songId] ?: 0L) + playedMs.coerceAtLeast(0L)

            if (durationMs > 0 && playedMs < durationMs * COMPLETE_RATIO) {
                incomplete[songId] = (incomplete[songId] ?: 0) + 1
                skips[songId] = (skips[songId] ?: 0) + 1
            } else if (playedMs >= 30_000L) {
                counts[songId] = (counts[songId] ?: 0) + 1
            }

            lastPlayed[songId] = System.currentTimeMillis()

            // 批量落盘：一次 apply 替代五次
            val edit = prefs.edit()
            writeCountsTo(edit, KEY_PLAY_COUNTS, counts)
            writeCountsTo(edit, KEY_SKIP_COUNTS, skips)
            writeCountsTo(edit, KEY_TOTAL_PLAYED_MS, totals.mapValues { it.value.toInt() })
            writeCountsTo(edit, KEY_INCOMPLETE, incomplete)
            writeLastPlayedTo(edit, lastPlayed)
            edit.apply()

            // 更新缓存
            cachedPlayCounts = counts
            cachedSkipCounts = skips
            cachedLastPlayed = lastPlayed
            cachedTotalPlayedMs = totals
            cachedIncomplete = incomplete
        }
        // 行为数据变了，推荐分必须重算（否则命中旧缓存，排行/未完播权重形同虚设）
        Recommender.invalidate()
    }

    /** 获取所有播放计数 */
    fun getPlayCounts(): Map<Long, Int> = cachedPlayCounts ?: readCounts(KEY_PLAY_COUNTS).also { cachedPlayCounts = it }

    /** 获取所有跳过计数 */
    fun getSkipCounts(): Map<Long, Int> = cachedSkipCounts ?: readCounts(KEY_SKIP_COUNTS).also { cachedSkipCounts = it }

    /** 获取累计播放总时长（毫秒） */
    fun getTotalPlayedMs(): Map<Long, Long> = cachedTotalPlayedMs
        ?: readCounts(KEY_TOTAL_PLAYED_MS).mapValues { it.value.toLong() }.also { cachedTotalPlayedMs = it }

    /** 获取未完播计数（进度不足 25% 的播放次数） */
    fun getIncompleteCounts(): Map<Long, Int> = cachedIncomplete ?: readCounts(KEY_INCOMPLETE).also { cachedIncomplete = it }

    /** 获取所有最后播放时间 */
    fun getLastPlayed(): Map<Long, Long> = cachedLastPlayed ?: readLastPlayed().also { cachedLastPlayed = it }

    // ==================== 点赞 ====================

    /** 切换歌曲点赞状态，返回切换后的状态 */
    fun toggleLike(songId: Long): Boolean {
        val result: Boolean
        synchronized(writeLock) {
            val likes = (cachedLikes ?: readLikes()).toMutableSet()
            val next = if (songId in likes) likes - songId else likes + songId
            prefs.edit().putString(KEY_LIKES, next.joinToString(";")).apply()
            cachedLikes = next
            result = songId in next
        }
        // 点赞是单项最强信号（×1.5），不失效缓存的话推荐结果不会变
        Recommender.invalidate()
        return result
    }

    /** 获取所有点赞歌曲 ID */
    fun getLikes(): Set<Long> = cachedLikes ?: readLikes().also { cachedLikes = it }

    /** 当前歌曲是否已点赞 */
    fun isLiked(songId: Long): Boolean = songId in getLikes()

    // ==================== 点赞歌曲元数据（「我喜欢」列表） ====================

    /**
     * 记录一首被点赞歌曲的完整元数据。
     *
     * 只存 ID 是不够的：在线搜索出来的歌不在曲库 allSongs 里，一旦离开搜索页就无从还原，
     * 结果「我喜欢」里永远看不到它。这里连同标题/歌手/封面一起落盘，重启也在。
     */
    fun saveLikedSong(song: Song) {
        synchronized(writeLock) {
            val cur = (cachedLikedSongs ?: readSongs(KEY_LIKED_SONGS)).toMutableList()
            cur.removeAll { it.id == song.id }
            cur.add(0, song) // 最近喜欢的排前面
            writeSongs(KEY_LIKED_SONGS, cur)
            cachedLikedSongs = cur
        }
    }

    /** 取消点赞：从元数据列表移除该歌 */
    fun removeLikedSong(songId: Long) {
        synchronized(writeLock) {
            val cur = (cachedLikedSongs ?: readSongs(KEY_LIKED_SONGS)).toMutableList()
            if (cur.removeAll { it.id == songId }) {
                writeSongs(KEY_LIKED_SONGS, cur)
                cachedLikedSongs = cur
            }
        }
    }

    /** 已点赞歌曲列表（按最近点赞在前） */
    fun likedSongs(): List<Song> = cachedLikedSongs ?: readSongs(KEY_LIKED_SONGS).also { cachedLikedSongs = it }

    /**
     * 按本地 id 取一首「我喜欢」里的歌（不存在返回 null）。
     *
     * 6.3 的对账要用它：曲库还没加载、或者这首歌是从搜索结果直接点进来的，
     * 这时 [PlayHistory.getLikes] 里有这个 id，但曲库索引里查不到对象。
     * 没有这个兜底，这类歌会在同步时被当成"本地连元数据都没有"直接删掉——
     * 用户看到的是"我点过的赞同步一次少几首"。
     */
    fun likedSong(songId: Long): Song? =
        likedSongs().firstOrNull { it.id == songId }

    // ==================== 在线歌曲元数据缓存（歌单按 ID 还原用） ====================

    /**
     * 记录一首在线歌曲的完整元数据。
     *
     * 搜索出来加进歌单的歌不在曲库 allSongs 里，只存 ID 的话重进歌单就还原不出来。
     * 这里把标题/歌手/封面一起落盘，歌单按 ID 就能把这首在线歌也显示出来。
     */
    fun saveKnownSong(song: Song) {
        synchronized(writeLock) {
            val cur = (cachedKnownSongs ?: readSongs(KEY_KNOWN_SONGS)).toMutableList()
            cur.removeAll { it.id == song.id }
            cur.add(0, song)
            val trimmed = cur.take(1000)
            writeSongs(KEY_KNOWN_SONGS, trimmed)
            cachedKnownSongs = trimmed
        }
    }

    /** 批量记录（导入歌单用）：一次落盘，避免几百首歌逐条重写整份列表 */
    fun saveKnownSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        synchronized(writeLock) {
            val cur = (cachedKnownSongs ?: readSongs(KEY_KNOWN_SONGS)).toMutableList()
            val incoming = songs.map { it.id }.toHashSet()
            cur.removeAll { it.id in incoming }
            cur.addAll(0, songs)
            val trimmed = cur.take(1000)
            writeSongs(KEY_KNOWN_SONGS, trimmed)
            cachedKnownSongs = trimmed
        }
    }

    /** 已缓存的在线歌曲元数据（按最近记录在前） */
    fun knownSongs(): List<Song> = cachedKnownSongs ?: readSongs(KEY_KNOWN_SONGS).also { cachedKnownSongs = it }

    /** 按歌曲 ID 取回已缓存的在线歌曲元数据（不存在返回 null） */
    fun knownSong(songId: Long): Song? = knownSongs().firstOrNull { it.id == songId }

    // ==================== 最近播放会话持久化 ====================
    //
    // 6.9.5 加固：用户反馈"最近播放里的歌，一退出 App 再进就变回之前那一份"。
    //
    // 旧实现只有一个落盘口：`prefs.edit().putString(...).apply()`。两个已知坑：
    //   ① apply() 是**异步**的。用户点完歌立刻划掉后台，队列里的写盘请求可能还没
    //      落到 flash 上，进程就没了 —— 下次启动读到的是上一次成功写入的旧列表，
    //      表现正是"刚听的没了，只剩之前的"。
    //   ② 序列化用 \u0001-\u0005 当分隔符，但 sanitizeField 只转义了 \u0001/\u0002。
    //      歌名/歌单名里只要混进 \u0004 或 \u0005，整条会话就会被解析成两截，
    //      解析失败的那截被静默丢掉 —— 也是"少了几条"。
    //
    // 现在做三件事：
    //   A. 双写：除了 prefs，再原子写一份小文件（tmp + rename）。prefs 那份坏了、
    //      被 ROM 清了、或异步没来得及落盘，文件那份还在。
    //   B. 读的时候两份都解析，取"首条时间更新"的那份（时间相同取更长的），
    //      顺手把另一份补齐 —— 自愈，不用用户手动清缓存。
    //   C. 回前台/退后台时额外 flush 一次（commit 同步落盘）。
    // 分隔符转义也补全到 \u0001..\u0005。

    private const val MIRROR_FILE = "recent_playbacks.dat"

    /** 落盘用的单线程：小文件顺序写，不跟主线程抢 IO */
    private val mirrorExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "recent-playbacks-io").apply { isDaemon = true }
    }

    private fun mirrorFile(): java.io.File? = runCatching {
        val dir = appContext?.filesDir ?: return null
        java.io.File(dir, MIRROR_FILE)
    }.getOrNull()

    private fun encodeRecent(list: List<RecentPlayback>): String =
        list.take(12).joinToString("\u0004") { rp ->
            val songsRaw = rp.songs.joinToString("\u0001") { s ->
                listOf(
                    s.id.toString(), s.songId, sanitizeField(s.title), sanitizeField(s.artist),
                    sanitizeField(s.album), s.durationMs.toString(), sanitizeField(s.filePath),
                    sanitizeField(s.folderPath), sanitizeField(s.folderName), s.likeCount.toString()
                ).joinToString("\u0002")
            }
            "${sanitizeField(rp.title)}\u0005${rp.timestamp}\u0005$songsRaw"
        }

    private fun parseRecent(raw: String?): List<RecentPlayback> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split("\u0004").mapNotNull { section ->
            // 单条坏掉只丢这一条，不再连累整份列表
            runCatching {
                val parts = section.split("\u0005")
                if (parts.size < 3) return@runCatching null
                val title = parts[0]
                val ts = parts[1].toLongOrNull() ?: System.currentTimeMillis()
                val songsRaw = parts[2]
                val songs = if (songsRaw.isBlank()) emptyList() else songsRaw.split("\u0001").mapNotNull { rec ->
                    runCatching {
                        val f = rec.split("\u0002")
                        if (f.size < 9) return@runCatching null
                        val id = f[0].toLongOrNull() ?: return@runCatching null
                        val songId = f[1]
                        val cover = f[6]
                        val likeCount = f.getOrNull(9)?.toLongOrNull() ?: 0L
                        Song(
                            id = id, title = f[2], artist = f[3], album = f[4],
                            durationMs = f[5].toLongOrNull() ?: 0L,
                            uri = Uri.parse(
                                com.jun.musiccloud.playback.AudioProxyServer.audioUrl(
                                    songId, f[2], f[3], f[5].toLongOrNull() ?: 0L
                                )
                            ),
                            albumArtUri = cover.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
                            filePath = cover, folderPath = f[7], folderName = f[8],
                            songId = songId, format = "在线", likeCount = likeCount
                        )
                    }.getOrNull()
                }
                RecentPlayback(title = title, songs = songs, timestamp = ts)
            }.getOrNull()
        }
    }

    private fun readMirror(): String? = runCatching {
        mirrorFile()?.takeIf { it.exists() }?.readText()
    }.getOrNull()

    /** 原子写镜像文件：先写 .tmp 再 rename，写一半断电也不会留下半截文件 */
    private fun writeMirror(raw: String, sync: Boolean) {
        val file = mirrorFile() ?: return
        val job = Runnable {
            runCatching {
                val tmp = java.io.File(file.parentFile, "$MIRROR_FILE.tmp")
                tmp.writeText(raw)
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    // rename 失败（极少数机型跨挂载点）就退化成直接覆盖
                    file.writeText(raw)
                    tmp.delete()
                }
            }
        }
        if (sync) job.run() else runCatching { mirrorExecutor.execute(job) }
    }

    /** 保存最近播放会话列表（覆盖式），重启后可恢复 */
    fun saveRecentPlaybacks(list: List<RecentPlayback>, sync: Boolean = false) {
        val trimmed = list.take(12)
        val raw = encodeRecent(trimmed)
        synchronized(writeLock) {
            // prefs 这份保留：老版本的读取路径、以及"文件被系统清掉"时的兜底
            val e = prefs.edit().putString(KEY_RECENT_PLAYBACKS, raw)
            if (sync) e.commit() else e.apply()
            cachedRecentPlaybacks = trimmed
        }
        // 文件这份同步/异步都在锁外：prefs 的 commit 已经够慢，别把文件 IO 也塞进锁里
        writeMirror(raw, sync)
    }

    /**
     * 强制把当前列表同步落盘（prefs commit + 文件即时写）。
     * 调用点：切后台 / 退出前。异步 apply 在"点完歌立刻划掉后台"的场景里会丢。
     */
    fun flushRecentPlaybacks() {
        val list = cachedRecentPlaybacks ?: return
        saveRecentPlaybacks(list, sync = true)
    }

    /** 读取最近播放会话列表（重启恢复用） */
    fun recentPlaybacks(): List<RecentPlayback> {
        cachedRecentPlaybacks?.let { return it }
        val fromPrefs = parseRecent(prefs.getString(KEY_RECENT_PLAYBACKS, null))
        val fromFile = parseRecent(readMirror())
        val list = when {
            fromFile.isEmpty() -> fromPrefs
            fromPrefs.isEmpty() -> fromFile
            else -> {
                val tf = fromFile.firstOrNull()?.timestamp ?: 0L
                val tp = fromPrefs.firstOrNull()?.timestamp ?: 0L
                when {
                    tf > tp -> fromFile
                    tp > tf -> fromPrefs
                    fromFile.size >= fromPrefs.size -> fromFile
                    else -> fromPrefs
                }
            }
        }
        cachedRecentPlaybacks = list
        // 自愈：两份取较新的那份之后，把另一份补齐，下次启动不用再比
        if (list.isNotEmpty()) {
            val raw = encodeRecent(list)
            runCatching { prefs.edit().putString(KEY_RECENT_PLAYBACKS, raw).apply() }
            writeMirror(raw, sync = false)
        }
        return list
    }

    // ---- Song 列表序列化（点赞元数据与在线缓存共用） ----

    private fun readSongs(key: String): List<Song> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split('\u0001').mapNotNull { rec ->
            val f = rec.split('\u0002')
            if (f.size < 9) return@mapNotNull null
            val id = f[0].toLongOrNull() ?: return@mapNotNull null
            val songId = f[1]
            val cover = f[6]
            // 第 10 个字段为 likeCount（旧数据没有则默认 0）
            val likeCount = f.getOrNull(9)?.toLongOrNull() ?: 0L
            Song(
                id = id,
                title = f[2],
                artist = f[3],
                album = f[4],
                durationMs = f[5].toLongOrNull() ?: 0L,
                uri = Uri.parse(
                    com.jun.musiccloud.playback.AudioProxyServer.audioUrl(songId, f[2], f[3], f[5].toLongOrNull() ?: 0L)
                ),
                albumArtUri = cover.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
                filePath = cover,
                folderPath = f[7],
                folderName = f[8],
                songId = songId,
                format = "在线",
                likeCount = likeCount
            )
        }
    }

    private fun writeSongs(key: String, list: List<Song>) {
        val raw = list.take(1000).joinToString("\u0001") { s ->
            listOf(
                s.id.toString(), s.songId, sanitizeField(s.title), sanitizeField(s.artist),
                sanitizeField(s.album), s.durationMs.toString(), sanitizeField(s.filePath),
                sanitizeField(s.folderPath), sanitizeField(s.folderName), s.likeCount.toString()
            ).joinToString("\u0002")
        }
        prefs.edit().putString(key, raw).apply()
    }

    private fun sanitizeField(s: String): String =
        s.replace('\u0001', ' ').replace('\u0002', ' ')
            .replace('\u0004', ' ').replace('\u0005', ' ')

    /**
     * 批量加入点赞（服务器同步用）。取并集，只增不减——
     * 云端「我喜欢」合并到本地时不能覆盖掉本地已有的，否则会丢数据。
     */
    fun addLikes(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        var changed = false
        synchronized(writeLock) {
            val likes = (cachedLikes ?: readLikes()).toMutableSet()
            val before = likes.size
            likes.addAll(ids)
            if (likes.size != before) {
                prefs.edit().putString(KEY_LIKES, likes.joinToString(";")).apply()
                cachedLikes = likes
                changed = true
            }
        }
        if (changed) Recommender.invalidate()
    }

    /**
     * 批量移除点赞（服务器同步用）。
     *
     * 用于「别的设备取消了我喜欢」：云端已经没有这首歌时，本地也要删掉，
     * 否则旧点赞会在本机永远留着，计数和云端对不上。
     */
    fun removeLikes(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        var changed = false
        synchronized(writeLock) {
            val likes = (cachedLikes ?: readLikes()).toMutableSet()
            if (!likes.removeAll(ids.toSet())) return@synchronized
            prefs.edit().putString(KEY_LIKES, likes.joinToString(";")).apply()
            cachedLikes = likes
            val songs = (cachedLikedSongs ?: readSongs(KEY_LIKED_SONGS)).filter { it.id !in ids }
            writeSongs(KEY_LIKED_SONGS, songs)
            cachedLikedSongs = songs
            changed = true
        }
        if (changed) Recommender.invalidate()
    }

    // ==================== 离线取消的赞（待补删） ====================

    /** 待补删的服务器 song_id：取消点赞时网络失败就记下来，下次同步接着删 */
    fun pendingUnlikes(): Set<String> = cachedPendingUnlikes
        ?: prefs.getStringSet(KEY_PENDING_UNLIKES, emptySet())?.toSet()
            .also { cachedPendingUnlikes = it }
        ?: emptySet()

    fun addPendingUnlike(serverId: String) {
        if (serverId.isBlank()) return
        synchronized(writeLock) {
            val cur = pendingUnlikes().toMutableSet()
            if (cur.add(serverId)) {
                prefs.edit().putStringSet(KEY_PENDING_UNLIKES, cur).apply()
                cachedPendingUnlikes = cur
            }
        }
    }

    fun removePendingUnlike(serverId: String) {
        synchronized(writeLock) {
            val cur = pendingUnlikes().toMutableSet()
            if (cur.remove(serverId)) {
                prefs.edit().putStringSet(KEY_PENDING_UNLIKES, cur).apply()
                cachedPendingUnlikes = cur
            }
        }
    }

    // ==================== 导入合并 ====================

    /**
     * 合并外部导入的行为数据（见 [DataTransfer]）。
     *
     * 合并而非覆盖：点赞取并集，计数与时长逐项取较大值，最后播放时间取较晚。
     * 取较大值而不是相加，是为了让同一份文件重复导入保持幂等——相加会把次数翻倍，
     * 直接污染推荐排行。
     */
    fun mergeImported(
        likes: Set<Long>,
        playCounts: Map<Long, Int>,
        incompleteCounts: Map<Long, Int>,
        totalPlayedMs: Map<Long, Long>,
        lastPlayed: Map<Long, Long>
    ) {
        synchronized(writeLock) {
            val mergedLikes = (cachedLikes ?: readLikes()).toMutableSet().apply { addAll(likes) }
            val counts = (cachedPlayCounts ?: readCounts(KEY_PLAY_COUNTS)).toMutableMap()
            val incomplete = (cachedIncomplete ?: readCounts(KEY_INCOMPLETE)).toMutableMap()
            val totals = (cachedTotalPlayedMs
                ?: readCounts(KEY_TOTAL_PLAYED_MS).mapValues { it.value.toLong() }).toMutableMap()
            val lasts = (cachedLastPlayed ?: readLastPlayed()).toMutableMap()

            playCounts.forEach { (id, v) -> counts[id] = maxOf(counts[id] ?: 0, v) }
            incompleteCounts.forEach { (id, v) -> incomplete[id] = maxOf(incomplete[id] ?: 0, v) }
            totalPlayedMs.forEach { (id, v) -> totals[id] = maxOf(totals[id] ?: 0L, v) }
            lastPlayed.forEach { (id, v) -> lasts[id] = maxOf(lasts[id] ?: 0L, v) }

            val edit = prefs.edit()
            edit.putString(KEY_LIKES, mergedLikes.joinToString(";"))
            writeCountsTo(edit, KEY_PLAY_COUNTS, counts)
            writeCountsTo(edit, KEY_TOTAL_PLAYED_MS, totals.mapValues { it.value.toInt() })
            writeCountsTo(edit, KEY_INCOMPLETE, incomplete)
            writeLastPlayedTo(edit, lasts)
            edit.apply()

            cachedLikes = mergedLikes
            cachedPlayCounts = counts
            cachedIncomplete = incomplete
            cachedTotalPlayedMs = totals
            cachedLastPlayed = lasts
        }
        Recommender.invalidate()
    }

    // ==================== ID 迁移（文件被移动） ====================

    /**
     * 把记账里的旧歌曲 ID 整体换成新 ID（文件移动后路径哈希变化）。
     * 同值键合并规则与 [mergeImported] 一致：计数取较大、时间取较晚、
     * 点赞取并集，保证重复执行幂等、不会把新位置已攒的数据冲掉。
     */
    fun remapIds(idMap: Map<Long, Long>) {
        if (idMap.isEmpty()) return
        synchronized(writeLock) {
            fun remap(map: Map<Long, Int>): MutableMap<Long, Int> {
                val out = LinkedHashMap<Long, Int>()
                map.forEach { (id, v) ->
                    val nid = idMap[id] ?: id
                    out[nid] = maxOf(out[nid] ?: 0, v)
                }
                return out
            }
            fun remapL(map: Map<Long, Long>): MutableMap<Long, Long> {
                val out = LinkedHashMap<Long, Long>()
                map.forEach { (id, v) ->
                    val nid = idMap[id] ?: id
                    out[nid] = maxOf(out[nid] ?: 0L, v)
                }
                return out
            }
            val counts = remap(cachedPlayCounts ?: readCounts(KEY_PLAY_COUNTS))
            val skips = remap(cachedSkipCounts ?: readCounts(KEY_SKIP_COUNTS))
            val incomplete = remap(cachedIncomplete ?: readCounts(KEY_INCOMPLETE))
            val totals = remapL(cachedTotalPlayedMs
                ?: readCounts(KEY_TOTAL_PLAYED_MS).mapValues { it.value.toLong() })
            val lasts = remapL(cachedLastPlayed ?: readLastPlayed())
            val likes = (cachedLikes ?: readLikes()).map { idMap[it] ?: it }.toSet()
            val edit = prefs.edit()
            edit.putString(KEY_LIKES, likes.joinToString(";"))
            writeCountsTo(edit, KEY_PLAY_COUNTS, counts)
            writeCountsTo(edit, KEY_SKIP_COUNTS, skips)
            writeCountsTo(edit, KEY_TOTAL_PLAYED_MS, totals.mapValues { it.value.toInt() })
            writeCountsTo(edit, KEY_INCOMPLETE, incomplete)
            writeLastPlayedTo(edit, lasts)
            edit.apply()
            cachedLikes = likes
            cachedPlayCounts = counts
            cachedSkipCounts = skips
            cachedIncomplete = incomplete
            cachedTotalPlayedMs = totals
            cachedLastPlayed = lasts
        }
        Recommender.invalidate()
    }

    private fun readLikes(): MutableSet<Long> {
        val raw = prefs.getString(KEY_LIKES, null) ?: return mutableSetOf()
        if (raw.isBlank()) return mutableSetOf()
        return raw.split(';').mapNotNull { it.toLongOrNull() }.toMutableSet()
    }

    // ==================== 内部实现 ====================

    private fun readCounts(key: String): MutableMap<Long, Int> {
        val raw = prefs.getString(key, null) ?: return mutableMapOf()
        return raw.split(';').mapNotNull { part ->
            val kv = part.split(':')
            val id = kv.getOrNull(0)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            val count = kv.getOrNull(1)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            id to count
        }.toMap().toMutableMap()
    }

    private fun writeCounts(key: String, counts: Map<Long, Int>) {
        writeCountsTo(prefs.edit(), key, counts).apply()
    }

    private fun writeCountsTo(edit: SharedPreferences.Editor, key: String, counts: Map<Long, Int>): SharedPreferences.Editor {
        val raw = counts.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(500)
            .joinToString(";") { "${it.key}:${it.value}" }
        return edit.putString(key, raw)
    }

    private fun readLastPlayed(): MutableMap<Long, Long> {
        val raw = prefs.getString(KEY_LAST_PLAYED, null) ?: return mutableMapOf()
        return raw.split(';').mapNotNull { part ->
            val kv = part.split(':')
            val id = kv.getOrNull(0)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            val ts = kv.getOrNull(1)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            id to ts
        }.toMap().toMutableMap()
    }

    private fun writeLastPlayed(map: Map<Long, Long>) {
        writeLastPlayedTo(prefs.edit(), map).apply()
    }

    private fun writeLastPlayedTo(edit: SharedPreferences.Editor, map: Map<Long, Long>): SharedPreferences.Editor {
        val raw = map.entries
            .sortedByDescending { it.value }
            .take(500)
            .joinToString(";") { "${it.key}:${it.value}" }
        return edit.putString(KEY_LAST_PLAYED, raw)
    }
}
