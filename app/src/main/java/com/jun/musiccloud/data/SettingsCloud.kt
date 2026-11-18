package com.jun.musiccloud.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 设置自动云同步的编排层（6.2 新增）。
 *
 * 用户要的效果
 * ------------
 * "我把背景、透明度、均衡器、歌词层全调好，下次登录还是这套，别让我重调。"
 * 6.1.1 之前只有设置页里两个手动按钮，而且同步面窄到连均衡器都不带 —— 现在改成：
 *   1. 登录后自动拉云端：本机没动过 → 静默恢复；本机动过 → 让人选，绝不静默覆盖；
 *   2. 改动后自动上传：靠快照指纹判脏（不靠给每个 setter 埋点，漏埋一个就是"改了没传"），
 *      前台心跳 + 最短 45 秒节流，用户完全无感；
 *   3. 背景图带本体：只同步 URI 的话，换一台手机那个 content:// 根本读不到，
 *      所以选图时先在 App 私有目录存一份归一化副本（长边 ≤1600、JPEG q85），
 *      再用副本的 sha256 当资产 id 上传/下载。顺带把"SAF 授权掉了背景就没了"这个
 *      老毛病一起修掉 —— 渲染永远读私有目录那份，不再依赖外部授权还活着。
 *
 * 冲突策略（这是原白名单实现里最缺的一段）
 * --------------------------------------
 * 服务端只有一个 (user_id → payload) 槽位，天然会互相覆盖，所以关键信息是**两个时间戳**：
 *   - `settings_cloud_updated_at`：本机最后已知的那份云端写入时间；
 *   - `settings_pushed_hash`：本机最后一次成功上传的内容指纹。
 * 判定：
 *   - 云端时间戳比已知的新，且本机指纹也变了 → 两台设备各改各的 → 保留双方，弹一次确认；
 *   - 云端时间戳比已知的新，但本机指纹没变 → 别的设备改了、本机没动 → 静默恢复；
 *   - 本机指纹变了、云端没动 → 静默上传。
 * 老实现是 GET 回来直接 apply，等于"谁后打开设置页谁赢"，另一台的调校会被无声冲掉。
 *
 * 状态放哪
 * --------
 * 全部用 Compose `mutableStateOf` 挂在这个 object 上，**不进 PlayerUiState**。
 * PlayerUiState 的 copy 已经占掉约 260 个寄存器（上限 255 是 dex 方法参数的硬线，
 * 历史上就在这里崩过一次 VerifyError），往那里加字段属于禁止事项。
 */
object SettingsCloud {

    /** 显示给用户的状态行（设置页「云同步」区直接读） */
    val status = mutableStateOf("")
    /** 后台/界面显示用：最近一次成功同步时间（毫秒，0=从没同步过） */
    val lastSyncAt = mutableStateOf(0L)
    /**
     * 有冲突时暂存的云端快照；null = 没有待处理的冲突。
     * UI 读到非空就画那二选一（[resolve] 里落地），进程被杀也不会丢：
     * 快照本体写在 filesDir/settings_cloud_pending.json 里。
     */
    val conflict = mutableStateOf<String?>(null)
    /** 正在跑网络动作，UI 用来禁用按钮 */
    val busy = mutableStateOf(false)

    private const val PREFS = "jun_prefs"
    private const val KEY_BG_LOCAL = "custom_bg_local"
    private const val KEY_BG_ASSET = "custom_bg_asset"
    private const val KEY_BG_URI = "custom_bg_uri"
    private const val PENDING_FILE = "settings_cloud_pending.json"

    /**
     * 与 src/index.js 里 SETTINGS_MAX 对齐：服务端 2026-11-03 15:43 CST 已从 24KB 提到 512KB，
     * 这里取 480KB 留一点余量（JSON 转义、UTF-8 多字节都会让字节数比字符数大）。
     */
    private const val MAX_SNAPSHOT_BYTES = 480 * 1024
    /** 背景图副本上限：超过就不上传本体，只在同机保留 */
    private const val MAX_ASSET_BYTES = 3 * 1024 * 1024
    /** 自动上传节流：同一台设备 45 秒内不重复推 */
    private const val PUSH_INTERVAL_MS = 45_000L
    /** 图片归一化：长边超过这个值就缩，壁纸用不着原图分辨率 */
    private const val BG_MAX_EDGE = 1600

    /**
     * 上一次 push 失败的具体原因，给调用方组织文案用。
     * 为什么要把原因留住而不是一律说「网络不通」：被服务端体积配额拒了和断网是两件
     * 完全不同的事，报成「网络不通」会把排查方向整个带偏。
     *
     * 2026-11-03 15:43 CST 服务端 `SETTINGS_MAX` 已上线到 512KB（见《6.2-上线记录》§14），
     * 而本 App 的快照上限是 480KB，所以「过大」那条分支现在正常情况下不会再被走到 ——
     * 只有拿旧版 6.2 客户端连一台被人为调小配额的服务端时才会出现。
     * 对应的文案字符串是 6.2/64 正式包里已经编译进去的常量（改了就要重新出包重签名），
     * 这一轮只在注释里说明，字面量留到 6.3 顺带改写。
     */
    private var pushFailReason: String = "网络不通"

    private fun prefs(ctx: Context) = ctx.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pendingFile(ctx: Context) =
        File(ctx.applicationContext.filesDir, PENDING_FILE)

    // ==================== 6.3 修复版：记账键按账号隔离 ====================
    //
    // 为什么必须隔离：pushed_hash / cloud_at 原来是全局的一套，
    // A 登录时写的「上次推送指纹」，在 B 登录时会被当成「B 在本机改过」——
    // 于是 B 登录后不是「瞬间换上自己的配置」，而是被挂进冲突等人选。
    // 这正是「登录了但配置不自动变」的最后一块拼图。
    // 现在 pushed_hash / cloud_at / last_sync_at 都跟着账号走，
    // 每个账号第一次用时把老的全局值搬进自己的格子（一次性迁移，老用户不丢状态）。
    private fun scope(ctx: Context): String {
        val uid = AccountStore.userId
        return if (uid > 0L) "u$uid" else "guest"
    }
    private fun kPushed(ctx: Context) = "settings_pushed_hash_" + scope(ctx)
    private fun kCloudAt(ctx: Context) = "settings_cloud_updated_at_" + scope(ctx)
    private fun kLastSync(ctx: Context) = "settings_last_sync_at_" + scope(ctx)

    /** 一次性迁移：老的全局记账值搬进当前账号的格子（不删全局键，captureDefaults 还要看它） */
    private fun adoptLegacy(p: android.content.SharedPreferences, legacy: String, mine: String, isLong: Boolean) {
        if (p.contains(mine)) return
        if (isLong) {
            if (p.contains(legacy)) p.edit().putLong(mine, p.getLong(legacy, 0L)).apply()
        } else {
            if (p.contains(legacy)) p.edit().putString(mine, p.getString(legacy, "") ?: "").apply()
        }
    }

    // ==================== 入口：登录后 ====================

    /**
     * 登录/注册成功后调用。三条路：静默恢复、静默上传、留冲突给用户选。
     * 返回给界面用的一句话说明（调用方负责写进状态）。
     */
    suspend fun onAccountReady(ctx: Context): Int = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val token = AccountStore.token
        if (token.isBlank() || !ServerApi.isConfigured) return@withContext 0
        busy.value = true
        try {
            val cloud = fetch(token) ?: return@withContext -1
            val raw = cloud.optString("settings", "")
            val cloudAt = cloud.optLong("updated_at", 0L)
            adoptLegacy(prefs(app), SettingsSync.KEY_CLOUD_AT, kCloudAt(app), true)
            adoptLegacy(prefs(app), SettingsSync.KEY_PUSHED_HASH, kPushed(app), false)
            val knownAt = prefs(app).getLong(kCloudAt(app), 0L)
            prefs(app).edit().putLong(kCloudAt(app), cloudAt).apply()

            if (raw.isBlank()) {
                // 云端还没有备份：本机这一套就是"权威版本"，直接推上去
                val pushed = push(app, token)
                status.value = if (pushed) "本机设置已备份到云端" else "本机这套设置还没备份上去：$pushFailReason"
                return@withContext if (pushed) 1 else 0
            }
            if (SettingsSync.countOf(raw) == 0) {
                status.value = "云端备份是空的，已用本机设置覆盖"
                push(app, token)
                return@withContext 0
            }
            val localHash = SettingsSync.fingerprint(app)
            val pushedHash = prefs(app).getString(kPushed(app), "") ?: ""
            val defaultHash = prefs(app).getString(SettingsSync.KEY_DEFAULT_HASH, "") ?: ""

            /**
             * 「这台设备还没被人调过」的判据（6.3 重写）。
             *
             * 6.2 的写法是：
             *   `localHash == pushedHash || (pushedHash.isEmpty() && defaultHash.isNotEmpty()
             *    && localHash == defaultHash)`
             * 在**全新安装**（用户删了 App 重装）这一步上是错的：
             *   - `pushedHash` 为空（新装没推过）；
             *   - `defaultHash` 在 init 里刚被 captureDefaultsIfMissing 写成"当前出厂默认"；
             *   - 而 `localHash` 是"出厂默认 + 用户上次同步下来的一小撮"——
             *   三者两两不等，于是 untouched = false，流程落到下面的 `cloudAt > knownAt`
             *   分支，把云端快照挂进「冲突」，等用户在设置里点二选一。
             *   用户根本不知道有这个东西，于是他的结论是：「重装之后设置全不对了」。
             *
             * 新判据只问一件事：**本机有没有"我曾经成功推上去过"的记录？**
             *   - 没有 → 这台设备（或这次安装）从来没有代表用户表过态，云端那份就是权威，
             *   静默恢复。这条覆盖了"新装 / 重装 / 换机"，也正是用户点名要修的场景；
             *   - 有，且等于当前指纹 → 没动过，静默恢复（老的快路径，保留）；
             *   - 有，但不等于当前指纹 → 用户确实改过，走冲突判定，绝不静默覆盖。
             *
             * 为什么原来那个 defaultHash 条件可以不要了：它的目的是"区分出厂默认和用户改动"。
             * 但 pushedHash 为空已经足够表达"没推过"，再加一个哈希比较只会引入
             * "两边都不是"的第三态（就是这次的 bug）。defaultHash 保留在 prefs 里不动，
             * 只为兼容旧数据与诊断，不再参与判定。
             */
            val pristine = pushedHash.isEmpty()
            val untouched = pristine || localHash == pushedHash

            when {
                untouched -> {
                    // 本机没动过（新装/重装/从没改过设置）→ 静默恢复，正是用户要的效果
                    val n = SettingsSync.apply(app, raw)
                    if (n > 0) afterApplied(app, token)
                    status.value = if (n > 0) "已自动恢复云端设置（$n 项）" else "云端备份与本机一致"
                    // 6.3：恢复完必须把界面状态也刷一遍，否则设置页显示的还是恢复前的值，
                    // 用户以为"没生效"，再手动改一次，就把刚恢复的又覆盖回去了。
                    // 调用方（PlayerViewModel.afterAccountReady）看返回值决定要不要 reload。
                    n
                }
                cloudAt > knownAt -> {
                    // 别的设备在本机离线期间改过，而本机也改过 → 不许静默覆盖
                    holdConflict(app, raw, cloudAt)
                    status.value = "云端设置与本机不同，请在设置里选择保留哪一份"
                    0
                }
                else -> {
                    val pushed = push(app, token)
                    status.value = if (pushed) "设置已同步到云端" else "设置备份没成功（$pushFailReason），下次打开会自动重试"
                    if (pushed) 1 else 0
                }
            }
        } finally {
            busy.value = false
        }
    }

    // ==================== 入口：前台心跳 ====================

    /**
     * 由 ViewModel 在启动与每次回到前台时调用。做三件事：
     * 先把上次没处理完的冲突从磁盘捞回来；本机脏了就（在没有新冲突的前提下）推上去；
     * 顺带记录同步时间给"很久没备份"的提醒用。
     */
    suspend fun tick(ctx: Context) = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        adoptLegacy(prefs(app), SettingsSync.KEY_CLOUD_AT, kCloudAt(app), true)
        adoptLegacy(prefs(app), SettingsSync.KEY_PUSHED_HASH, kPushed(app), false)
        adoptLegacy(prefs(app), SettingsSync.KEY_LAST_SYNC_AT, kLastSync(app), true)
        lastSyncAt.value = prefs(app).getLong(kLastSync(app), 0L)
        restorePending(app)
        val token = AccountStore.token
        if (token.isBlank() || !ServerApi.isConfigured) return@withContext
        if (conflict.value != null) return@withContext        // 等用户先做选择，别抢
        val localHash = SettingsSync.fingerprint(app)
        val pushedHash = prefs(app).getString(kPushed(app), "") ?: ""
        if (localHash == pushedHash) return@withContext
        val now = System.currentTimeMillis()
        if (prefs(app).getLong(kLastSync(app), 0L) > now - PUSH_INTERVAL_MS) return@withContext
        val cloud = fetch(token) ?: return@withContext
        val cloudAt = cloud.optLong("updated_at", 0L)
        val knownAt = prefs(app).getLong(kCloudAt(app), 0L)
        prefs(app).edit().putLong(kCloudAt(app), cloudAt).apply()
        if (cloudAt > knownAt && cloud.optString("settings", "").isNotBlank()) {
            // 云端在本机不知道的时候被改过，两边都有改动 → 交还给人
            holdConflict(app, cloud.optString("settings"), cloudAt)
            status.value = "云端与本机各有一套设置，请选择保留哪一份"
            return@withContext
        }
        val pushed = push(app, token)
        status.value = if (pushed) "设置已自动同步" else "设置自动备份没成功：$pushFailReason"
    }

    // ==================== 手动动作（设置页按钮） ====================

    suspend fun uploadNow(ctx: Context): Int = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val token = AccountStore.token
        if (token.isBlank()) { status.value = "请先登录账号"; return@withContext -1 }
        busy.value = true
        try {
            if (push(app, token)) {
                val n = SettingsSync.countOf(SettingsSync.snapshot(app))
                status.value = "已上传 $n 项设置"
                clearPending(app)
                n
            } else { status.value = "备份没成功：$pushFailReason"; -1 }
        } finally { busy.value = false }
    }

    suspend fun downloadNow(ctx: Context): Int = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val token = AccountStore.token
        if (token.isBlank()) { status.value = "请先登录账号"; return@withContext -1 }
        busy.value = true
        try {
            val cloud = fetch(token) ?: run { status.value = "读取云端失败"; return@withContext -1 }
            val raw = cloud.optString("settings", "")
            if (raw.isBlank()) { status.value = "云端还没有设置备份"; return@withContext 0 }
            val n = SettingsSync.apply(app, raw)
            if (n > 0) afterApplied(app, token, pushBack = false)
            status.value = if (n > 0) "已恢复 $n 项设置" else "云端备份是空的"
            clearPending(app)
            n
        } finally { busy.value = false }
    }

    /**
     * 冲突解决：`useCloud=true` 用云端覆盖本机，`false` 把本机推上去覆盖云端。
     * 两条路都要求"立刻写盘 + 更新记账"，否则下一次心跳会又算成脏。
     */
    suspend fun resolve(ctx: Context, useCloud: Boolean): Int = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val raw = conflict.value ?: ""
        if (raw.isBlank()) return@withContext 0
        val token = AccountStore.token
        if (useCloud) {
            val n = SettingsSync.apply(app, raw)
            if (n > 0) afterApplied(app, token, pushBack = false)
            status.value = "已用云端设置覆盖本机（$n 项）"
            clearPending(app)
            n
        } else {
            val ok = push(app, token)
            status.value = if (ok) "已用本机设置覆盖云端" else "覆盖没成功：$pushFailReason"
            if (ok) clearPending(app)
            if (ok) 1 else 0
        }
    }

    fun dismissConflict(ctx: Context) {
        // "这次不用"：不是没冲突了，而是本机选择不处理。记账保持原样，
        // 下次心跳仍会算出脏/新差异，但只在用户主动进设置页时才看得见。
        runCatching { pendingFile(ctx).delete() }
        conflict.value = null
    }

    /** 距上次成功同步多少天，设置页用它提示"30 天没备份了" */
    fun daysSinceSync(ctx: Context): Long {
        val t = prefs(ctx).getLong(kLastSync(ctx), 0L)
        if (t <= 0L) return -1
        return (System.currentTimeMillis() - t) / 86_400_000L
    }

    /** 首次拿到出厂默认快照后记下指纹，用来识别"这台机器还没被人动过" */
    fun captureDefaultsIfMissing(ctx: Context) {
        val p = prefs(ctx)
        if (p.contains(SettingsSync.KEY_DEFAULT_HASH)) return
        // 只在真·新装（还没推过任何一份设置）时记，否则老用户升级会把他的改动当默认值
        if (p.contains(SettingsSync.KEY_PUSHED_HASH)) return
        runCatching { p.edit().putString(SettingsSync.KEY_DEFAULT_HASH, SettingsSync.fingerprint(ctx)).apply() }
    }

    // ==================== 背景图本体 ====================

    /**
     * 把用户选的 SAF 图片落成 App 私有目录里的归一化副本，返回副本的 sha256。
     *
     * 为什么非但不可省，还顺手修了一个老 bug：
     *   - `custom_bg_uri` 是 content:// URI，换设备/换账号/源文件被删/授权被回收都会读不到，
     *     界面显示"已使用自选图片做背景"但实际渲染回落到封面 —— 用户看到的"设置丢了"多半是这个；
     *   - 副本在 filesDir/theme/background.jpg，包名固定 ⇒ 路径也固定，同机永远可读；
     *   - sha256 当资产 id，云端只存这个短字符串，恢复时按 id 取回图片本体。
     */
    fun installBackground(ctx: Context, uri: Uri): String? {
        val app = ctx.applicationContext
        val dir = File(app.filesDir, "theme").apply { if (!exists()) mkdirs() }
        val out = File(dir, "background.jpg")
        val bytes = runCatching {
            app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val encoded = runCatching { normalize(bytes) }.getOrNull() ?: bytes
        if (runCatching { out.writeBytes(encoded) }.isFailure) return null
        val sha = sha256(encoded)
        prefs(app).edit()
            .putString(KEY_BG_URI, Uri.fromFile(out).toString())
            .putString(KEY_BG_LOCAL, out.absolutePath)
            .putString(KEY_BG_ASSET, sha)
            .apply()
        return sha
    }

    fun clearBackground(ctx: Context) {
        val app = ctx.applicationContext
        prefs(app).edit().remove(KEY_BG_URI).remove(KEY_BG_LOCAL).remove(KEY_BG_ASSET).apply()
        runCatching { File(File(app.filesDir, "theme"), "background.jpg").delete() }
    }

    /** 恢复设置后（或首次登录拉到云端快照后）把背景图本体取回本地 */
    private fun ensureBackground(ctx: Context, token: String) {
        val app = ctx.applicationContext
        val p = prefs(app)
        val want = p.getString(KEY_BG_ASSET, "") ?: ""
        if (want.isBlank()) return
        val f = File(File(app.filesDir, "theme"), "background.jpg")
        if (f.exists() && sha256(runCatching { f.readBytes() }.getOrNull() ?: ByteArray(0)) == want) {
            if (p.getString(KEY_BG_URI, "") != Uri.fromFile(f).toString()) {
                p.edit().putString(KEY_BG_URI, Uri.fromFile(f).toString())
                    .putString(KEY_BG_LOCAL, f.absolutePath).apply()
            }
            return
        }
        val got = downloadAsset(token, want) ?: return
        if (got.size > MAX_ASSET_BYTES) return
        if (runCatching { f.parentFile?.mkdirs(); f.writeBytes(got) }.isFailure) return
        p.edit().putString(KEY_BG_URI, Uri.fromFile(f).toString())
            .putString(KEY_BG_LOCAL, f.absolutePath).apply()
    }

    /**
     * 上传背景图本体。走「原始字节 + 查询串里的 sha256」而不是 JSON base64：
     * 3 MB 的图 base64 之后是 4 MB，会直接顶穿服务端 express.json 的 4mb 上限，
     * 而那个上限是所有接口共用的，不该为一张背景图放宽。
     * 服务端会重算 sha256 并与 [KEY_BG_ASSET] 比对，所以这里必须先确认本地文件
     * 的哈希确实等于快照里那个 id —— 不等就说明本地图和云端引用不是同一张，
     * 这种情况不上传（宁可不同步，也不要传一张对不上号的图）。
     */
    private fun pushAsset(ctx: Context, token: String) {
        val app = ctx.applicationContext
        val p = prefs(app)
        val sha = p.getString(KEY_BG_ASSET, "") ?: ""
        if (sha.isBlank() || !sha.matches(Regex("^[0-9a-f]{64}$"))) return
        val f = File(p.getString(KEY_BG_LOCAL, "") ?: "")
        if (!f.exists() || f.length() > MAX_ASSET_BYTES) return
        val bytes = runCatching { readAll(f) }.getOrNull() ?: return
        if (bytes.isEmpty() || sha256(bytes) != sha) return
        var conn: HttpURLConnection? = null
        try {
            conn = URL("${ServerApi.effectiveBaseUrl}/api/settings/asset?id=$sha")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "JunMusic")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "image/jpeg")
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            if (status !in 200..299) {
                val msg = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()
                android.util.Log.w("SettingsCloud", "背景图本体上传失败 http=$status ${msg?.take(120)}")
            }
        } catch (e: Exception) {
            android.util.Log.w("SettingsCloud", "背景图本体上传异常：${e.message}")
        } finally { conn?.disconnect() }
    }

    private fun downloadAsset(token: String, sha: String): ByteArray? {
        if (!ServerApi.isConfigured || sha.isBlank() || !sha.matches(Regex("^[0-9a-f]{64}$"))) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("${ServerApi.effectiveBaseUrl}/api/settings/asset?id=$sha")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "JunMusic")
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > MAX_ASSET_BYTES) null else bytes
        } catch (e: Exception) {
            null
        } finally { conn?.disconnect() }
    }

    /** 长边缩到 [BG_MAX_EDGE] 以内、重编码 JPEG，控制快照与资产体积 */
    private fun normalize(bytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w0 = bounds.outWidth; val h0 = bounds.outHeight
        if (w0 <= 0 || h0 <= 0) return bytes
        // 先按 2 的幂做采样（内存友好），再用矩阵精修到目标长边
        var sample = 1
        while (maxOf(w0, h0) / (sample * 2) >= BG_MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes
        val extra = BG_MAX_EDGE.toFloat() / maxOf(src.width, src.height)
        // Matrix.setScale 返回 Boolean（不是自己），链式写法在这里编不过
        val matrix = Matrix().apply { setScale(extra, extra) }
        val bmp = if (extra < 0.98f) {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
                .also { if (it !== src) src.recycle() }
        } else src
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        if (bmp !== src) runCatching { bmp.recycle() } else runCatching { src.recycle() }
        return bos.toByteArray()
    }

    // ==================== 内部 ====================

    private fun afterApplied(app: Context, token: String, pushBack: Boolean = true) {
        ensureBackground(app, token)
        val hash = SettingsSync.fingerprint(app)
        val now = System.currentTimeMillis()
        prefs(app).edit()
            .putString(kPushed(app), hash)
            .putLong(kLastSync(app), now)
            .apply()
        lastSyncAt.value = now
        if (pushBack) push(app, token)   // 恢复后本机指纹变了，推一次让指纹对齐
    }

    private fun push(app: Context, token: String): Boolean {
        val raw = SettingsSync.snapshot(app)
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_SNAPSHOT_BYTES) {
            pushFailReason = "这套设置的体积超过 App 自己的上限"
            status.value = "设置快照超出上限，已跳过自动上传"
            return false
        }
        val res = runCatching {
            ServerApi.request("/api/settings", JSONObject().put("settings", raw), token, "PUT")
        }.getOrNull()
        if (res?.optInt("code", -1) != 0) {
            val msg = res?.optString("msg", "").orEmpty().take(40)
            pushFailReason = when {
                res == null -> "网络不通"
                msg.contains("过大") -> "云端旧版体积配额装不下整套设置（服务器那侧的上限还没上线）"
                msg.isNotBlank() -> "云端拒收：$msg"
                else -> "云端返回了异常回执"
            }
            return false
        }
        val now = System.currentTimeMillis()
        prefs(app).edit()
            .putString(kPushed(app), SettingsSync.fingerprint(app))
            .putLong(kCloudAt(app), res.optJSONObject("data")?.optLong("updated_at", now) ?: now)
            .putLong(kLastSync(app), now)
            .apply()
        lastSyncAt.value = now
        pushAsset(app, token)
        return true
    }

    private fun fetch(token: String): JSONObject? {
        val res = runCatching { ServerApi.request("/api/settings", null, token) }.getOrNull() ?: return null
        if (res.optInt("code", -1) != 0) return null
        return res.optJSONObject("data")
    }

    private fun holdConflict(app: Context, raw: String, cloudAt: Long) {
        runCatching {
            pendingFile(app).writeText(JSONObject().put("at", cloudAt).put("settings", raw).toString())
        }
        conflict.value = raw
    }

    private fun restorePending(app: Context) {
        if (conflict.value != null) return
        val f = pendingFile(app)
        if (!f.exists()) return
        val raw = runCatching { JSONObject(f.readText()).optString("settings") }.getOrNull()
        if (raw.isNullOrBlank() || SettingsSync.countOf(raw) == 0) { clearPending(app); return }
        conflict.value = raw
    }

    private fun clearPending(app: Context) {
        runCatching { pendingFile(app).delete() }
        conflict.value = null
    }

    private fun readAll(f: File): ByteArray = runCatching { f.readBytes() }.getOrDefault(ByteArray(0))

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
