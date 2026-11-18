package com.jun.musiccloud.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 设置快照与云同步的序列化层（6.2 起改为「全量 dump + 排除名单」）。
 *
 * 为什么不用白名单
 * ----------------
 * 6.1.1 之前这里是一张手写白名单（72 个键），后果有三层，全是这一轮在源码里核出来的：
 *   1. 漏项没人报警：均衡器整套（eq_*）、歌词层三十来个 lyric_*、自定义背景、悬浮歌词、
 *      倍速、省电动画、排序方式…… 全都不在名单里，用户以为"设置会跟着账号走"，其实不会；
 *   2. 键名写错了也不会响：白名单里的 floating_lyric_enabled / size / width / alpha
 *      与 FloatingLyric.kt 真实用的 float_lyric_* 对不上，silence_skip 与真实键
 *      silence_skip_enabled 也对不上 —— 这 5 项从来没同步成功过，快照里直接没这几个键，
 *      服务端与客户端都不报错，属于静默失效；
 *   3. 每加一个设置都要记得来这里补一行，漏了就是下一个静默失效。
 *
 * 现在反过来：jun_prefs 里**除了排除名单全都同步**（本机 SharedPreferences 整份 dump）。
 * 以后新增设置项自动被带走，不需要记得维护这张表 —— 和 DataTransfer 的备份文件用同一套
 * 语义，避免"本地备份很全、云端同步很窄"这种口径分裂。
 *
 * 类型标记必须带
 * --------------
 * JSON 不区分 Int/Long，一个 Long 键若数值不大，读回来会退化成 Integer；而
 * custom_primary / custom_secondary / fade_ms 这些在 App 里是用 getLong 读的
 * （PlayerViewModel:973、FadeController:94），写回时若用 putInt，下次 getLong 会抛
 * ClassCastException 直接崩。所以每个值都带 {"t":类型,"v":值}，恢复时按类型精确写回。
 *
 * 与 DataTransfer（本地导出文件）的兼容
 * ------------------------------------
 * 两边读对方格式：这里发出的是短标记 b/i/l/f/s/a，DataTransfer 用的是 bool/int/long/…；
 * [typeOf] 两种都认，所以「云端快照」可以直接当「配置档」用，配置档文件也能上传到云端。
 *
 * 排除名单为什么这么定
 * --------------------
 * 原则一句话：**同步"偏好"，不同步"凭证 / 本机路径与授权 / 设备标识 / 行为统计 /
 * 一次性引导标记 / 服务端下发的缓存"**。
 * 前者的典型是主题、材质、模糊浓度、圆角、EQ、歌词层、DSP；后者的典型是 token、
 * 下载目录、SAF 授权 URI、桌面歌词窗口坐标（换台分辨率就飘出屏幕）、
 * remote_control_token（把局域网遥控服务在新设备上悄悄打开是安全问题）、
 * whats_new_code / onboarding_done_v1（新设备应该正常看一次引导，不该被"继承"掉）。
 */
object SettingsSync {

    private const val PREFS = "jun_prefs"

    // ===== 云同步记账键（都写在 jun_prefs 里，因此自己也在排除名单中）=====
    const val KEY_PUSHED_HASH = "settings_pushed_hash"
    const val KEY_CLOUD_AT = "settings_cloud_updated_at"
    const val KEY_DEFAULT_HASH = "settings_default_hash"
    const val KEY_LAST_SYNC_AT = "settings_last_sync_at"
    const val KEY_LOCAL_TOUCH = "settings_local_touched_at"

    /**
     * 不同步的键。分两类：
     *   - 凭证与设备标识：换设备也不该跟着走，跟着走等于把凭据搬到另一台机器；
     *   - 本机路径 / 授权 / 硬件相关：换了机器这些值就是错的，写回去比不写更糟。
     */
    private val DENY = setOf(
        // —— 账号凭证与账号态（服务端另有权威，绝不在设置里搬）——
        "token", "uid", "name", "nickname", "avatar", "avatar_path", "cookie",
        "action_token", "remote_control_token", "remote_control_enabled", "resume_on_reconnect",
        "vip_level", "vip_name", "vip_until", "vip_claimed",
        // —— 隐私同意与一次性引导：新设备必须重新走一遍，不能继承 ——
        "agreed", "agreed_at", "version",
        "onboarding_done_v1", "onboarding_version",
        "feature_tour_auto_shown_v1", "feature_tour_version",
        "whats_new_code", "notif_perm_asked_v1", "vip_comp_seen_v403",
        "defaults_seeded_v1", "dismissed", "lyric_guide_seen_v1",
        "completed", "last_completed", "last_ms", "last_steps", "runs", "total_ms", "total_steps",
        // —— 设备标识与埋点队列 ——
        "device_id", "install_id", "hw_id", "event_queue",
        // —— 本机路径 / SAF 授权 / 与存储绑定项：跨设备无效 ——
        "download_path", "download_path_confirmed", "download_records_json",
        "local_folder", "selected_folders",
        "stream_cache_enabled", "stream_cache_limit_mb",
        // 自定义背景：URI 是本机授权、path 是本机文件，都只在设备内有效；
        // 跨设备靠 custom_bg_asset（内容 sha256）重新取回图片本体。
        "custom_bg_uri", "custom_bg_local", "custom_bg_path",
        "lyric_video_uri",
        // —— 播放进度与"上次停在哪"：属于设备现场，不属于偏好 ——
        "last_song_id", "last_position_ms", "last_queue_ids", "last_queue_index",
        "last_was_playing", "active_playlist_id", "only_liked_view",
        // —— 行为统计与本地库数据（服务端有专门的接口，不混进设置快照）——
        "play_counts", "skip_counts", "last_played", "likes", "total_played_ms",
        "incomplete_counts", "pending_unlikes", "known_online_songs", "recent_playbacks",
        "history", "library_blocked_ids", "library_verified_ids",
        "loudnorm_lufs_store_v2",
        // —— 服务端下发缓存：拉取时自然重建，搬过去只会过期 ——
        "banners_json", "customer_json", "flags_json", "hot_keywords",
        "maintenance", "maintenance_text", "notice", "notice_seen",
        "sensitive_on", "sensitive_words", "max_seen_id", "daily_counts",
        "traffic_base_at", "traffic_base_rx", "traffic_base_tx", "traffic_month",
        // —— 云同步自身的记账键（否则会把"上次同步指纹"也同步过去，脏检查永远算不对）——
        KEY_PUSHED_HASH, KEY_CLOUD_AT, KEY_DEFAULT_HASH, KEY_LAST_SYNC_AT, KEY_LOCAL_TOUCH,
        "feature_flags_at", "sync_announcement",
        // —— 桌面歌词窗口坐标：换分辨率/换横竖屏会飘到屏幕外，用户得能重新拖 ——
        "float_lyric_x", "float_lyric_y"
    )

    /** 前缀排除：动态键名的那类（云歌单映射、本地歌单曲目表）。
     *  6.3 修复版：记账键改成按账号后缀（settings_pushed_hash_u12 这类），
     *  所以这里用前缀把整族都挡住，否则它们会被当成普通设置同步出去，
     *  指纹就永远算不对。 */
    private val DENY_PREFIX = listOf(
        "cloud_pl_", "playlist_songs_", "playlist_names", "ls_", "jm_guide_",
        "settings_pushed_hash_", "settings_cloud_updated_at_", "settings_last_sync_at_"
    )

    /** 单个值的长度上限：壁纸 base64、超长的逐首偏移串这类不该从设置通道走 */
    private const val MAX_VALUE_LEN = 8192
    /**
     * 快照条数上限：兜住"某个键集合爆炸"把 512KB 配额吃光的情况。
     *
     * 6.3 把 400 提到 1200，并且**改掉截断方式**。
     * 原来是在遍历 prefs.all 时 `if (n >= MAX_ITEMS) break` ——SharedPreferences 的
     * all 是 HashMap，遍历顺序不确定，所以"被截掉的 400 项之后是谁"每次都不一样。
     * 表现是：同一套设置在两台手机上算出两个不同的指纹，云同步永远判成"冲突"；
     * 而重装后恢复出来的那份设置，均衡器在、歌词层没了，用户看到的就是
     * 「配置完全不同步」。
     *
     * 现在改成：先按键名排序，再按顺序取前 MAX_ITEMS 项。截断仍然可能发生
     * （极端情况），但**同样的设置永远截掉同一批**，指纹稳定、同步可预测。
     */
    private const val MAX_ITEMS = 1200

    private fun denied(key: String): Boolean =
        key in DENY || DENY_PREFIX.any { key.startsWith(it) }

    /** 短标记 <-> DataTransfer 长标记 双向认，保证两份数据可以互用 */
    private fun typeOf(t: String): String = when (t) {
        "b", "bool" -> "b"
        "i", "int" -> "i"
        "l", "long" -> "l"
        "f", "float", "double" -> "f"
        "s", "string" -> "s"
        "a", "set" -> "a"
        else -> ""
    }

    /**
     * 6.3：把"进快照前就已经注定被丢掉"的键先筛掉。
     *
     * 为什么要在遍历前先筛：SharedPreferences.all 会把整个 XML 反序列化成 HashMap，
     * 一个重度用户的 jun_prefs 有 400~800 个键（设置 + 引导标记 + 各种缓存）。
     * 每次同步先 all() 一次、再逐个判断类型、再算指纹——冷启动时这笔开销
     * 和"首页要的曲库 / 封面"抢同一个主线程，用户体感就是"刚进来卡一下"。
     *
     * 这里只做一件很便宜的事：把明确不属于设置的键（行为统计、本机路径、凭证、
     * 服务端下发缓存）用前缀先挡掉，剩下的才进 all() 之后的类型判断。
     * 注意：**不能只靠这个前缀表来决定同步内容**，DENY 才是权威；
     * 这里只是一次提前剪枝，剪错了的后果是"某一项不同步"，所以只剪那些
     * 前面 DENY 里已经写死的前缀，不引入新规则。
     */
    private val CHEAP_SKIP_PREFIX = listOf(
        "lyric_cache_", "cover_", "cache_", "tmp_", "download_records", "event_", "traffic_"
    )

    /** 本机当前要同步的那部分设置（已排除、已裁掉超大值） */
    private fun collect(prefs: android.content.SharedPreferences): JSONObject {
        val root = JSONObject()
        var n = 0
        // 按键名排序后再遍历：见 MAX_ITEMS 的注释，HashMap 的遍历顺序不保证稳定，
        // 而"截断发生在上限处"意味着顺序一变截掉的东西就变，指纹跟着变。
        val ordered = prefs.all.entries.sortedBy { it.key }
        for ((key, v) in ordered) {
            if (n >= MAX_ITEMS) break
            if (denied(key)) continue
            if (CHEAP_SKIP_PREFIX.any { key.startsWith(it) }) continue
            val item = JSONObject()
            when (v) {
                null -> continue
                is Boolean -> { item.put("t", "b"); item.put("v", v) }
                is Int -> { item.put("t", "i"); item.put("v", v) }
                is Long -> { item.put("t", "l"); item.put("v", v) }
                is Float -> { item.put("t", "f"); item.put("v", v.toDouble()) }
                is String -> {
                    if (v.length > MAX_VALUE_LEN) continue
                    item.put("t", "s"); item.put("v", v)
                }
                is Set<*> -> {
                    val arr = JSONArray()
                    v.forEach { s -> s?.toString()?.let { arr.put(it) } }
                    if (arr.toString().length > MAX_VALUE_LEN) continue
                    item.put("t", "a"); item.put("v", arr)
                }
                else -> continue
            }
            root.put(key, item)
            n++
        }
        return root
    }

    /** 导出当前设置为 JSON 字符串（云端快照 / 配置档共用） */
    fun snapshot(context: Context): String =
        collect(prefs(context)).toString()

    /**
     * 当前设置的指纹。用来判断"本机有没有改动过"，不依赖任何 setter 埋点 ——
     * 设置写入分散在几十上百个地方，漏埋一个就会出现"改了但没上传"，
     * 所以这里改成"每次要同步前把快照算一遍再比指纹"。
     */
    fun fingerprint(context: Context): String = sha256(canonical(collect(prefs(context))))

    /** 键排序后的稳定文本，保证同一套设置在任何设备上得到同一个指纹 */
    private fun canonical(root: JSONObject): String {
        val keys = root.keys().asSequence().sorted().toList()
        val sb = StringBuilder()
        for (k in keys) {
            val it = root.optJSONObject(k) ?: continue
            sb.append(k).append('=').append(it.optString("t")).append(':')
            val v = it.opt("v")
            sb.append(if (v is JSONArray) (0 until v.length()).joinToString(",") { v.optString(it) } else v)
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 6.3：给一段**已有的快照**算指纹。
     *
     * 为什么要这个：云同步的判据是"本机指纹 vs 上次推上去的指纹"。重装之后
     * 本机指纹是本机算的，而"上次推的"那份在服务器上——两者的键集合只要差一项，
     * 算出来的哈希就完全不同，于是被判成"用户改过很多"，进入冲突分支（用户看不见的
     * 那个分支），表现就是"重装后配置完全不同步"。
     *
     * 用同一个函数对"云端那份 raw"算一次指纹，两边就能用同一把尺子比。
     */
    fun fingerprintOfSnapshot(raw: String): String {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return ""
        if (obj.length() == 0) return ""
        return sha256(canonical(obj))
    }

    /** 快照里有多少项 */
    fun countOf(raw: String): Int = runCatching { JSONObject(raw).length() }.getOrDefault(0)

    /**
     * 应用一份快照，返回写入的项数；解析失败返回 -1。
     *
     * 只覆盖快照里出现的键，本机多出来的键不动（这样"从云端恢复"不会把新设备上
     * 刚做的个性化抹掉），未知键直接忽略（旧版本的快照回到新版本也不会写坏东西）。
     */
    fun apply(context: Context, raw: String): Int {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return -1
        val edit = prefs(context).edit()
        var count = 0
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (denied(key)) continue
            val item = root.optJSONObject(key) ?: continue
            when (typeOf(item.optString("t"))) {
                "b" -> { edit.putBoolean(key, item.optBoolean("v")); count++ }
                "i" -> { edit.putInt(key, item.optInt("v")); count++ }
                "l" -> { edit.putLong(key, item.optLong("v")); count++ }
                "f" -> { edit.putFloat(key, item.optDouble("v").toFloat()); count++ }
                "s" -> {
                    val v = item.optString("v")
                    if (v.length <= MAX_VALUE_LEN) { edit.putString(key, v); count++ }
                }
                "a" -> {
                    val arr = item.optJSONArray("v") ?: continue
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) arr.optString(i, "").takeIf { it.isNotEmpty() }?.let { set.add(it) }
                    edit.putStringSet(key, set); count++
                }
            }
        }
        edit.apply()
        return count
    }

    fun prefs(context: Context): android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

}
