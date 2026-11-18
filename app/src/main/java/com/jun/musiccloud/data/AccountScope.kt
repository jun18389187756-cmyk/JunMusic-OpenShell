package com.jun.musiccloud.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 账号级本地存储分区（6.3 新增）。
 *
 * 为什么必须有这一层
 * ----------------
 * 6.2 及以前，**所有**本地数据（我喜欢、歌单、播放计数、最近播放……）都写在同一个
 * SharedPreferences 文件 `jun_prefs` 里，而那个文件跟账号毫无关系：
 *
 *   1. A 登录 → 点赞 30 首 → 退出 → B 登录：B 一进「我喜欢」看到的是 A 的 30 首；
 *   2. 更要命的是 [AccountStore.clear] 以前对 `jun_prefs` 直接 `clear()`：
 *      A 退出登录时把全局设置（字体、主题、各种偏好）和 B 的数据一起清掉；
 *   3. 于是「换号」这件事在 6.2 里的表现是：串号 + 设置被洗白 + 再登录一次设置又不一样。
 *
 * 现在把「属于账号的东西」按 uid 切到独立文件：
 *
 *   `jm_account_<uid>`  —— A 的一份
 *   `jm_account_<uid>`  —— B 的一份，互不可见
 *   `jm_account_guest`  —— 没登录时的一份
 *
 * 而 `jun_prefs` 退回它本来的职责：**设备级偏好**（字体、动画速度、下载格式、主题……）。
 * 设备级偏好换账号不该变，账号级数据换账号必须变——这两类混在一个文件里，怎么修都会有一头错。
 *
 * 迁移
 * ----
 * 老版本的数据全在 `jun_prefs` 里，升级到 6.3 时第一次绑定账号做一次性搬迁：
 * 把 [SCOPED_KEYS] / [SCOPED_PREFIXES] 命中的键搬到对应分区，**并从 `jun_prefs` 里删掉**。
 * 为什么必须删：留着的话，下一次换账号时新分区是空的，读不到就会去读全局那份，
 * 串号原样复发。写完 `scope_migrated_v1` 标记，只搬一次。
 *
 * 谁该进来
 * --------
 * 只放「一个人的东西」：我喜欢、歌单曲目、播放统计、最近播放、听歌报告缓存。
 * 不放：设置、token、引导标记、设备标识、下载记录（下载目录在本机，跟账号无关）。
 */
object AccountScope {

    /** 没登录时的分区名 */
    const val GUEST = "jm_account_guest"

    /** 老版本的全局文件：现在只放设备级偏好 */
    const val LEGACY_PREFS = "jun_prefs"

    private const val MIGRATED_FLAG = "scope_migrated_v1"

    /**
     * 按账号隔离的键（精确匹配）。
     * 新增行为类数据时**必须**在这里登记，否则又会回到「所有账号共用一份」。
     */
    val SCOPED_KEYS: Set<String> = setOf(
        // 我喜欢
        "likes",
        "liked_songs",
        "pending_unlikes",
        // 播放行为
        "play_counts",
        "skip_counts",
        "last_played",
        "total_played_ms",
        "incomplete_counts",
        "recent_playbacks",
        "known_online_songs",
        // 断点续播
        "last_song_id",
        "last_position_ms",
        "last_queue_ids",
        "last_queue_index",
        "last_was_playing",
        "active_playlist_id",
        "only_liked_view",
        // 本地库核对结果（跟这个人的歌单强相关）
        "library_blocked_ids",
        "library_verified_ids",
        // 听歌报告 / 统计缓存
        "listen_report_cache",
        "daily_counts",
        "max_seen_id",
        // 云同步对账用的「我喜欢快照」（旧名，5.x 遗留）
        "cloud_like_ids",
        // 歌单视图
        "playlist_names"
    )

    /** 按前缀隔离的键（动态键名） */
    val SCOPED_PREFIXES: List<String> = listOf(
        "playlist_songs_",   // 本地歌单曲目表
        "cloud_pl_",         // 本地歌单 ↔ 云端歌单映射
        "cloud_like_ids_",   // 按账号的「我喜欢」快照（6.3 起改用 cloud_like_keys_<uid>）
        "cloud_like_keys_",  // 6.3：按账号的「我喜欢」对账键集合
        "cloud_history_snapshot_",
        "ls_"                // 听歌统计的零碎键
    )

    private const val KEY_ACTIVE_UID = "scope_active_uid"

    @Volatile
    private var cached: SharedPreferences? = null

    @Volatile
    private var cachedUid: Long = Long.MIN_VALUE

    private fun fileOf(uid: Long): String = if (uid > 0L) "jm_account_$uid" else GUEST

    /**
     * 绑定到某个账号的分区。[uid] <= 0 表示访客。
     *
     * 调用点只有两处：登录成功（[AccountStore.save]）和启动时确定登录态之后。
     * 切之前会把当前分区的未落盘改动 commit 掉——apply 是异步的，切文件时还在队列里的
     * 写入会写到新文件里去，那是典型的"退出登录后少了几首歌"。
     */
    fun bind(context: Context, uid: Long) {
        val app = context.applicationContext
        val target = if (uid > 0L) uid else 0L
        if (cachedUid == target && cached != null) return
        // 先把旧分区刷盘
        runCatching { legacy(app).edit().putLong(KEY_ACTIVE_UID, target).commit() }
        cached = app.getSharedPreferences(fileOf(target), Context.MODE_PRIVATE)
        cachedUid = target
    }

    /** 当前分区。没绑定过就按「上次登录的是谁」恢复，再不行当访客。 */
    fun prefs(context: Context): SharedPreferences {
        cached?.let { if (cachedUid != Long.MIN_VALUE) return it }
        val app = context.applicationContext
        val remembered = runCatching { legacy(app).getLong(KEY_ACTIVE_UID, 0L) }.getOrDefault(0L)
        return app.getSharedPreferences(fileOf(remembered), Context.MODE_PRIVATE).also {
            cached = it
            cachedUid = remembered
        }
    }

    /** 当前分区对应的 uid（0 = 访客） */
    fun activeUid(): Long = if (cachedUid == Long.MIN_VALUE) 0L else cachedUid

    private fun legacy(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    private fun scoped(key: String): Boolean =
        key in SCOPED_KEYS || SCOPED_PREFIXES.any { key.startsWith(it) }

    /**
     * 一次性搬迁：把 `jun_prefs` 里属于账号的那部分搬到当前分区。
     *
     * 只在当前分区还是空的时候搬。为什么：分区里已经有数据，说明这个账号在 6.3 上
     * 已经用过一轮了，此时 `jun_prefs` 里残留的只可能是上一个账号的脏数据，
     * 搬过来就是把串号又请回来了。
     *
     * 搬完删旧键是硬要求，理由见类注释。
     */
    fun migrateLegacyIfNeeded(context: Context) {
        val app = context.applicationContext
        val dst = prefs(app)
        if (dst.getBoolean(MIGRATED_FLAG, false)) return
        val src = legacy(app)
        val moved = mutableMapOf<String, Any?>()
        for ((k, v) in src.all) {
            if (!scoped(k)) continue
            if (v == null) continue
            moved[k] = v
        }
        if (moved.isEmpty()) {
            dst.edit().putBoolean(MIGRATED_FLAG, true).apply()
            return
        }
        // 目标分区已经有同一批键 → 说明它自己有数据，不覆盖
        val occupied = moved.keys.count { dst.contains(it) }
        if (occupied > 0 && occupied == moved.size) {
            dst.edit().putBoolean(MIGRATED_FLAG, true).apply()
            runCatching { src.edit().apply { moved.keys.forEach { remove(it) } }.apply() }
            return
        }
        val e = dst.edit()
        for ((k, v) in moved) {
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is String -> e.putString(k, v)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    e.putStringSet(k, (v as Set<String>).toSet())
                }
            }
        }
        e.putBoolean(MIGRATED_FLAG, true).commit()
        // 从全局文件删掉（commit，必须同步完成，否则下一次换号读到的还是这份）
        runCatching {
            val del = src.edit()
            moved.keys.forEach { del.remove(it) }
            del.commit()
        }
    }

    /**
     * 注销 / 删除账号时清空该账号的分区。
     *
     * 只清这个 uid 自己的文件，**绝不动 `jun_prefs`** ——
     * 6.2 的「退出登录把设置也洗了」就是从这里来的。
     */
    fun wipe(context: Context, uid: Long) {
        val app = context.applicationContext
        val target = if (uid > 0L) uid else 0L
        if (target == 0L) {
            // 访客分区不清：那是"还没登录时的本机数据"，清了等于把没登录的用户数据删了
            return
        }
        runCatching { app.getSharedPreferences(fileOf(target), Context.MODE_PRIVATE).edit().clear().commit() }
        if (cachedUid == target) cached = null
    }

    /** 调试/诊断：当前分区里有多少个键、分别是什么（设置页「存储诊断」用） */
    fun dumpScoped(context: Context): Map<String, Int> {
        val p = prefs(context)
        val counts = HashMap<String, Int>()
        for (k in p.all.keys) {
            val v = p.all[k]
            counts[k] = when (v) {
                is String -> v.length
                is Set<*> -> v.size
                else -> 1
            }
        }
        return counts
    }
}
