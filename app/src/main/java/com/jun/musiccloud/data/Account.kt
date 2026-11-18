package com.jun.musiccloud.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 账号与云同步（自建账号体系）。
 *
 * 账号、我喜欢、播放记录都存在自己的服务器（/workspace/server）。
 * 未配置 [ServerApi.BASE_URL] 时全部安全降级：登录界面会明确提示「服务器未接入」，
 * 本地点赞/播放照常工作，只是不上云。
 */
object AccountStore {

    private const val PREFS = "jun_account"
    private const val KEY_TOKEN = "token"
    private const val KEY_UID = "uid"
    private const val KEY_NAME = "name"
    private const val KEY_AVATAR = "avatar"

    /**
     * 登录态镜像：普通 SharedPreferences，只在加密存储读不出来时兜底。
     *
     * 为什么需要：EncryptedSharedPreferences 的密钥放在 Android Keystore 里。
     * 部分机型在覆盖安装、系统升级或 Keystore 被重置后，加密文件还在但密钥没了，
     * 读取会抛异常/返回空——以前这里直接当成“未登录”，用户每次更新都要重登。
     * 现在先把加密存储当主路径，读不到再回退到镜像，并立刻回写加密存储。
     *
     * 安全边界：镜像位于应用私有目录，未 root 的其它 App 读不到；root/备份仍可能拿到。
     * 这是“升级不丢登录”和“token 绝不明文”之间必须做的取舍，主存储仍是 AES256-GCM。
     */
    private const val MIRROR_PREFS = "jun_account_mirror"

    /**
     * 第二份登录态镜像：应用私有目录下的 JSON 文件。
     *
     * SharedPreferences 在极少数机型上会因为文件损坏 / 迁移失败被系统整份清掉，
     * 但 filesDir 下的小文件通常还在。登录、改名、换头像时同步写一份，读取时最后兜底。
     * 同样是应用私有目录，未 root 的其它 App 读不到。
     */
    private const val SESSION_FILE = "account_session.json"

    var token: String = ""
        private set
    var userId: Long = 0L
        private set
    var username: String = ""
        private set
    var avatar: String = ""
        private set

    val isLoggedIn: Boolean get() = token.isNotBlank()

    /**
     * 存 token 的 prefs：走 EncryptedSharedPreferences（AES256-GCM，密钥存 Keystore）。
     *
     * 之前 token 明文躺在 /data/data/.../shared_prefs/jun_account.xml 里，
     * 只要拿到备份文件或 root 权限就能直接拿来冒充这个账号。
     * 加密后即使文件被读走，没有 Keystore 里的密钥也解不开。
     *
     * 万一设备上的 Keystore 不可用（极少数机型 / 被 root 改过），降级到普通 prefs
     * 而不是崩溃——宁可安全性回退，也不能让用户登出后进不去。
     */
    private fun prefs(ctx: Context): SharedPreferences {
        if (encrypted != null) return encrypted!!
        val tryEncrypted = runCatching {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()
        encrypted = tryEncrypted ?: ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return encrypted!!
    }

    @Volatile
    private var encrypted: SharedPreferences? = null

    fun load(ctx: Context) {
        // 主路径：加密存储。任何一步失败都不让 App 起不来，统一走镜像兜底。
        val p = runCatching { prefs(ctx) }.getOrNull()
        val encryptedToken = runCatching { p?.getString(KEY_TOKEN, "") }.getOrNull().orEmpty()
        if (encryptedToken.isNotBlank()) {
            token = encryptedToken
            userId = runCatching { p?.getLong(KEY_UID, 0L) }.getOrNull() ?: 0L
            username = runCatching { p?.getString(KEY_NAME, "") }.getOrNull().orEmpty()
            avatar = runCatching { p?.getString(KEY_AVATAR, "") }.getOrNull().orEmpty()
            // 老版本用户没有镜像，顺手补一份，下一次升级也能兜底。
            writeMirror(ctx, token, userId, username, avatar)
            writeSessionFile(ctx, token, userId, username, avatar)
            return
        }

        val m = runCatching { mirror(ctx) }.getOrNull()
        val mirrorToken = runCatching { m?.getString(KEY_TOKEN, "") }.getOrNull().orEmpty()
        if (mirrorToken.isNotBlank()) {
            token = mirrorToken
            userId = runCatching { m?.getLong(KEY_UID, 0L) }.getOrNull() ?: 0L
            username = runCatching { m?.getString(KEY_NAME, "") }.getOrNull().orEmpty()
            avatar = runCatching { m?.getString(KEY_AVATAR, "") }.getOrNull().orEmpty()
            // 回写主存储；写失败也不影响本次登录态。
            runCatching {
                prefs(ctx).edit()
                    .putString(KEY_TOKEN, token)
                    .putLong(KEY_UID, userId)
                    .putString(KEY_NAME, username)
                    .putString(KEY_AVATAR, avatar)
                    .apply()
            }
            writeSessionFile(ctx, token, userId, username, avatar)
            return
        }

        // 最后兜底：应用私有目录里的 JSON 会话文件。
        val f = readSessionFile(ctx)
        val fileToken = f?.optString(KEY_TOKEN, "").orEmpty()
        if (fileToken.isNotBlank()) {
            token = fileToken
            userId = f?.optLong(KEY_UID, 0L) ?: 0L
            username = f?.optString(KEY_NAME, "").orEmpty()
            avatar = f?.optString(KEY_AVATAR, "").orEmpty()
            writeMirror(ctx, token, userId, username, avatar)
            runCatching {
                prefs(ctx).edit()
                    .putString(KEY_TOKEN, token)
                    .putLong(KEY_UID, userId)
                    .putString(KEY_NAME, username)
                    .putString(KEY_AVATAR, avatar)
                    .apply()
            }
            return
        }

        token = ""
        userId = 0L
        username = ""
        avatar = ""
    }

    fun save(ctx: Context, token: String, userId: Long, username: String) {
        this.token = token
        this.userId = userId
        this.username = username
        runCatching {
            prefs(ctx).edit()
                .putString(KEY_TOKEN, token)
                .putLong(KEY_UID, userId)
                .putString(KEY_NAME, username)
                .apply()
        }
        writeMirror(ctx, token, userId, username, avatar)
        writeSessionFile(ctx, token, userId, username, avatar)
        // 6.3：登录成功即刻切到「这个人」的本地分区，并把上一个账号未落盘的写入刷掉。
        // 顺序很重要——先 bind，再 rebind 各数据层，最后迁移老数据（迁移写的是新分区）。
        AccountScope.bind(ctx, userId)
        PlayHistory.rebind(ctx)
        Playlists.rebind(ctx)
        ListenStats.rebind(ctx, userId)
        AccountScope.migrateLegacyIfNeeded(ctx)
    }

    fun setName(ctx: Context, name: String) {
        username = name
        runCatching { prefs(ctx).edit().putString(KEY_NAME, name).apply() }
        writeMirror(ctx, token, userId, name, avatar)
        writeSessionFile(ctx, token, userId, name, avatar)
    }

    fun setAvatar(ctx: Context, avatarUrl: String) {
        avatar = avatarUrl
        runCatching { prefs(ctx).edit().putString(KEY_AVATAR, avatarUrl).apply() }
        writeMirror(ctx, token, userId, username, avatarUrl)
        writeSessionFile(ctx, token, userId, username, avatarUrl)
    }

    /**
     * 退出登录。
     *
     * 6.3 起这里**不再碰** `jun_prefs`：以前那句 `prefs(ctx).edit().clear()` 清的是
     * 全局 prefs，退出登录连字体、主题、各种偏好一起洗掉，用户再登录回来发现"设置全变了"。
     * 现在只清凭证，本地数据留在 `jm_account_<uid>` 里，这个人下次登录还在。
     * 访客分区照旧使用，所以"退出后别人拿到手机"看到的是一份干净的空数据。
     */
    fun clear(ctx: Context) {
        val leaving = userId
        token = ""
        userId = 0L
        username = ""
        avatar = ""
        runCatching { prefs(ctx).edit().clear().apply() }
        runCatching { mirror(ctx).edit().clear().apply() }
        runCatching { sessionFile(ctx).delete() }
        // 切回访客分区，并让各数据层指向它。leaving 只用于日志/后续清理，不在这里删数据。
        AccountScope.bind(ctx, 0L)
        PlayHistory.rebind(ctx)
        Playlists.rebind(ctx)
        ListenStats.rebind(ctx, 0L)
        if (leaving > 0L) {
            // 断点续播属于"设备现场"，换人就不该续上一个人的位置
            runCatching { AccountScope.prefs(ctx).edit().remove("last_song_id").apply() }
        }
    }

    private fun mirror(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)

    private fun writeMirror(ctx: Context, token: String, userId: Long, username: String, avatar: String) {
        if (token.isBlank()) return
        runCatching {
            mirror(ctx).edit()
                .putString(KEY_TOKEN, token)
                .putLong(KEY_UID, userId)
                .putString(KEY_NAME, username)
                .putString(KEY_AVATAR, avatar)
                .apply()
        }
    }

    private fun sessionFile(ctx: Context): File = File(ctx.filesDir, SESSION_FILE)

    private fun readSessionFile(ctx: Context): JSONObject? = runCatching {
        val f = sessionFile(ctx)
        if (!f.exists()) null else JSONObject(f.readText())
    }.getOrNull()

    private fun writeSessionFile(ctx: Context, token: String, userId: Long, username: String, avatar: String) {
        if (token.isBlank()) return
        runCatching {
            val obj = JSONObject()
                .put(KEY_TOKEN, token)
                .put(KEY_UID, userId)
                .put(KEY_NAME, username)
                .put(KEY_AVATAR, avatar)
            sessionFile(ctx).writeText(obj.toString())
        }
    }
}

/** 登录 / 注册结果 */
sealed class AuthResult {
    /**
     * 登录/注册成功的用户信息。
     *
     * 6.6.6 补了 [nickname]：以前只带 username，界面就拿用户名当昵称显示。
     * 于是用户改了昵称、退出重登，看到的名字又变回用户名 —— 他以为"改昵称没用"。
     * 服务端 `userPublic()` 一直有返回 nickname，只是客户端没接。
     */
    data class Ok(
        val token: String,
        val userId: Long,
        val username: String,
        val avatar: String = "",
        val nickname: String = "",
        /** 6.6.6：服务端的"上次活跃"时间戳（毫秒）。账号页用它对齐后台的在线口径。 */
        val lastActiveAt: Long = 0L
    ) : AuthResult()

    data class Fail(val message: String) : AuthResult()
}

/** 服务端用的歌曲标识：优先网易云 id，没有则用本地稳定 id */
val Song.serverId: String get() = songId.ifEmpty { id.toString() }

/**
 * 服务端「我喜欢 / 歌单」里返回的完整歌曲信息。
 *
 * 以前客户端只取 song_id，把 title/cover/duration 全丢了；一换设备或清过数据，
 * 这些歌不在首页曲库里就拼不回 Song，表现就是「云端有 5 首、我喜欢里 0 首」。
 * 现在整条元数据都带回来，缺哪首都能直接还原成可播放的 Song。
 */
data class CloudSong(
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val cover: String,
    val url: String,
    val durationSec: Int,
    val updatedAt: Long = 0L,
    /** 歌单内记录 id（取消歌曲时服务端两种 id 都认，这里保留备用） */
    val rowId: Long = 0L
) {
    fun toSong(folder: String): Song {
        val c = normalizeCloudCover(cover)
        val t = title.ifBlank { "未知歌曲" }
        val a = artist.ifBlank { "未知艺术家" }
        return Song(
            id = MusicRepository.stableId(songId),
            title = t,
            artist = a,
            album = album,
            durationMs = durationSec.coerceAtLeast(0) * 1000L,
            // 6.6.6：时长一并带进播放地址，脚本链路靠它认「换艺名的同一首录音」
            uri = Uri.parse(
                com.jun.musiccloud.playback.AudioProxyServer.audioUrl(
                    songId, t, a, durationSec.coerceAtLeast(0) * 1000L
                )
            ),
            albumArtUri = c.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
            filePath = c,
            folderPath = folder,
            folderName = folder,
            songId = songId,
            format = "在线"
        )
    }
}

private fun normalizeCloudCover(url: String): String {
    val u = url.trim()
    return when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://") && (u.contains("126.net") || u.contains("music.163.com")) ->
            "https://" + u.removePrefix("http://")
        else -> u
    }
}

/** 服务端歌单（含完整歌曲列表） */
data class CloudPlaylist(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val songs: List<CloudSong>
)

/**
 * 4.0.3 特大事故补偿会员状态。
 *
 * 服务端按账号只发一次：level=2 即「内部尊享会员」，until 为到期毫秒时间戳。
 * 领取接口幂等，重复点不会叠加年限，也不会报错。
 */
data class VipStatus(
    val level: Int = 0,
    val name: String = "普通用户",
    val until: Long = 0L,
    val claimed: Boolean = false,
    val claimedAt: Long = 0L,
    val daysLeft: Int = 0
)

object AuthApi {

    /** 服务器已接入 */
    val available: Boolean get() = true

    /**
     * 统一的「这一次成功没有」判据。
     *
     * 旧写法是 20 处 `root.optInt("code", -1) != 0 → return null`：把「服务器明确说没有」
     * 「token 过期」「网络断了」三种情况压成同一个 null，云同步从此永久静默失败，
     * 用户看到的永远是"就是这个列表"，只能靠重新登录打破。
     * 现在 code 401/403 单独走 [SessionSignals]（HTTP 层的 401 已由 ServerApi.request 认领），
     * 返回值语义一字不改——调用方照样拿到 null/false，只是不再静默。
     * 顺带接受 200：部分网关把成功码写成 HTTP 风格而不是 0。
     */
    private fun okCode(root: JSONObject?): Boolean {
        if (root == null) return false
        val c = root.optInt("code", -1)
        if (c == 401 || c == 403) {
            SessionSignals.markExpired(SessionSignals.SOURCE_ACCOUNT, root.optString("msg", ""))
            return false
        }
        return c == 0 || c == 200
    }

    suspend fun register(username: String, password: String, nickname: String): AuthResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("username", username)
                .put("password", password)
            parse(ServerApi.request("/api/register", body), username)
        }

    suspend fun login(username: String, password: String): AuthResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("username", username).put("password", password)
            parse(ServerApi.request("/api/login", body), username)
        }

    /** 注销账号并删除服务端个人数据（需要登录态 token）。失败也不影响本地清除。 */
    suspend fun deleteAccount(): Boolean = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank()) return@withContext false
        ServerApi.request("/api/account/delete", JSONObject(), token, "DELETE")?.let { okCode(it) } == true
    }

    /** 修改密码（需要登录态 token） */
    suspend fun changePassword(oldPassword: String, newPassword: String): Boolean =
        withContext(Dispatchers.IO) {
            val token = AccountStore.token
            if (token.isBlank()) return@withContext false
            val body = JSONObject()
                .put("old_password", oldPassword)
                .put("new_password", newPassword)
            ServerApi.request("/api/change-password", body, token)?.let { okCode(it) } == true
        }

    /**
     * 上传头像到服务器（JPEG 字节，已由调用方压缩到 512px 内）。
     * 成功返回服务器上的头像 URL；未登录或失败返回 null。
     * 失败时本地头像仍保留，下次启动/下次换头像会重试上传。
     */
    suspend fun uploadAvatar(jpeg: ByteArray): String? = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank() || jpeg.isEmpty()) return@withContext null
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val body = JSONObject().put("image", "data:image/jpeg;base64,$b64")
        val root = ServerApi.request("/api/avatar", body, token) ?: return@withContext null
        if (!okCode(root)) return@withContext null
        root.optJSONObject("data")?.optString("avatar", "")?.takeIf { it.isNotBlank() }
    }

    /**
     * 6.6.6：改昵称（服务端）。
     *
     * 用户报的 bug：「改的昵称只有本地有效果，排行榜上还是之前那样，下次登录还是旧昵称」。
     * 根因是改名只写了本机 SharedPreferences（见 LocalProfileStore 的注释），
     * 服务端 users.nickname 从来没被改过。
     *
     * 返回服务端确认后的昵称；失败返回 null（调用方据此提示"没同步上去"，
     * 而不是假装成功 —— 用户最烦的就是改完看着好了、重登又变回去）。
     */
    suspend fun changeNickname(name: String): String? = changeNicknameDetailed(name).nickname

    /** 改名结果：成功带回服务端确认的昵称，失败带回**能直接显示给用户**的原因 */
    data class NicknameOutcome(val nickname: String? = null, val error: String? = null) {
        val ok: Boolean get() = !nickname.isNullOrBlank()
    }

    /**
     * 6.7.0：改名改走 **POST** `/api/profile/update`。
     *
     * 这是「改昵称 100% 不生效」的真正原因，藏得非常深：
     * Android 的 `HttpURLConnection` 只接受 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE，
     * 传 `"PATCH"` 会在 `setRequestMethod()` 直接抛 `ProtocolException: Invalid HTTP method`。
     * 而 [ServerApi.request] 的 catch 把任何异常都吞成 `null` ——
     * 也就是说：**请求根本没有发出去**，客户端却只是以为"服务器没同步"。
     * 所以 6.6.6 起服务端明明有 PATCH 接口、curl 也验证通过，App 里却一次都没成功过
     *（服务器 users 表里所有 nickname 都还等于 username，正是这个的铁证）。
     *
     * 服务端同时保留了 PATCH（供后台/脚本用），并新增等价的 POST 路由。
     */
    suspend fun changeNicknameDetailed(name: String): NicknameOutcome = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank()) return@withContext NicknameOutcome(error = "登录已失效，请重新登录")
        val clean = name.trim()
        if (clean.isBlank()) return@withContext NicknameOutcome(error = "昵称不能为空")
        val body = JSONObject().put("nickname", clean)
        val root = ServerApi.request("/api/profile/update", body, token)
            ?: return@withContext NicknameOutcome(error = "连不上服务器，检查网络后重试")
        val code = root.optInt("code", -1)
        if (code == 0 || code == 200) {
            val got = root.optJSONObject("data")?.optString("nickname")?.takeIf { it.isNotBlank() }
            return@withContext if (got != null) NicknameOutcome(nickname = got)
            else NicknameOutcome(error = "服务器没有返回新昵称（服务端版本过旧，需更新）")
        }
        if (code == 401 || code == 403) {
            SessionSignals.markExpired(SessionSignals.SOURCE_ACCOUNT, root.optString("msg", ""))
            return@withContext NicknameOutcome(error = "登录已过期，请退出后重新登录再改")
        }
        NicknameOutcome(
            error = root.optString("msg", "").ifBlank { "服务器拒绝了这次修改（code=$code）" }
        )
    }

    /** 拉取当前账号资料（头像可能已在别的设备改过）；失败返回 null */
    suspend fun fetchProfile(): AuthResult.Ok? = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank()) return@withContext null
        val root = ServerApi.request("/api/profile", null, token) ?: return@withContext null
        if (!okCode(root)) return@withContext null
        val user = root.optJSONObject("data")?.optJSONObject("user") ?: return@withContext null
        AuthResult.Ok(
            token = token,
            userId = user.optLong("id", AccountStore.userId),
            username = user.optString("username", AccountStore.username).ifBlank { AccountStore.username },
            avatar = user.optString("avatar", "").orEmpty(),
            nickname = user.optString("nickname", "").orEmpty(),
            lastActiveAt = root.optJSONObject("data")?.optLong("lastActiveAt", 0L) ?: 0L
        )
    }

    // ---------- 4.0.3 特大事故补偿：10 年 VIP / 内部尊享会员 ----------
    private fun parseVip(o: JSONObject?): VipStatus? {
        if (o == null) return null
        return VipStatus(
            level = o.optInt("level", 0),
            name = o.optString("name", "普通用户").ifBlank { "普通用户" },
            until = o.optLong("until", 0L),
            claimed = o.optBoolean("claimed", false),
            claimedAt = o.optLong("claimed_at", 0L),
            daysLeft = o.optInt("days_left", 0)
        )
    }

    /** 查询补偿会员状态；未登录/失败返回 null（本地缓存继续显示） */
    suspend fun fetchVip(): VipStatus? = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank()) return@withContext null
        val root = ServerApi.request("/api/vip", null, token) ?: return@withContext null
        if (!okCode(root)) return@withContext null
        parseVip(root.optJSONObject("data"))
    }

    /** 一键领取 10 年 VIP + 内部尊享会员（幂等，重复点返回已领取状态） */
    suspend fun claimVip(): VipStatus? = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank()) return@withContext null
        val root = ServerApi.request("/api/vip/claim", JSONObject(), token) ?: return@withContext null
        if (!okCode(root)) return@withContext null
        parseVip(root.optJSONObject("data"))
    }

    private fun parse(root: JSONObject?, fallbackName: String): AuthResult {
        if (root == null) return AuthResult.Fail("连接服务器失败，请检查网络")
        // 这里**故意**不走上面的 okCode()：注册 / 登录这两条链路回 401 的意思是
        // 「账号或密码不对」，不是「本机凭证过期」。走 okCode 会在用户正输密码的时候
        // 点亮 SessionSignals 那条「登录已过期，请重新登录」的横幅，等于自己吓自己。
        // 真正凭证过期的路径（带 token 的同步接口）由 okCode 和 ServerApi 的 HTTP 层认领。
        if (root.optInt("code", -1) != 0) {
            return AuthResult.Fail(root.optString("msg", "操作失败"))
        }
        val data = root.optJSONObject("data") ?: return AuthResult.Fail("服务器返回异常")
        val token = data.optString("token", "")
        if (token.isBlank()) return AuthResult.Fail("服务器未返回登录凭证")
        val user = data.optJSONObject("user")
        return AuthResult.Ok(
            token = token,
            userId = user?.optLong("id", 0L) ?: 0L,
            username = user?.optString("username", fallbackName).orEmpty().ifBlank { fallbackName },
            avatar = user?.optString("avatar", "").orEmpty(),
            nickname = user?.optString("nickname", "").orEmpty().orEmpty()
        )
    }

    private fun songBody(song: Song) = JSONObject()
        .put("song_id", song.serverId)
        .put("source", "")
        .put("title", song.title)
        .put("artist", song.artist)
        .put("album", song.album)
        .put("cover", song.albumArtUri?.toString() ?: "")
        .put("url", song.filePath)
        .put("duration", (song.durationMs / 1000).toInt())

    /** 上报点赞/取消点赞（失败静默，本地状态不回滚） */
    suspend fun pushLike(song: Song, liked: Boolean): Boolean = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank()) return@withContext false
        if (liked) {
            ServerApi.request("/api/favorites", songBody(song), token)
        } else {
            ServerApi.request("/api/favorites/" + song.serverId, null, token, "DELETE")
        }?.let { okCode(it) } == true
    }

    /** 只按服务器 song_id 取消点赞（离线补删队列用，不依赖本地歌曲对象） */
    suspend fun deleteLikeByServerId(serverId: String): Boolean = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank() || serverId.isBlank()) return@withContext false
        val encoded = java.net.URLEncoder.encode(serverId, "UTF-8")
        ServerApi.request("/api/favorites/$encoded", null, token, "DELETE")?.let { okCode(it) } == true
    }

    /** 解析云端歌曲行（收藏 / 歌单通用），song_id 为空视为脏数据丢弃 */
    private fun parseCloudSong(o: JSONObject?): CloudSong? {
        if (o == null) return null
        val sid = o.optString("song_id", "").trim()
        if (sid.isEmpty()) return null
        return CloudSong(
            songId = sid,
            title = o.optString("title", "").orEmpty(),
            artist = o.optString("artist", "").orEmpty(),
            album = o.optString("album", "").orEmpty(),
            cover = o.optString("cover", "").orEmpty(),
            url = o.optString("url", "").orEmpty(),
            durationSec = o.optInt("duration", 0),
            updatedAt = o.optLong("updated_at", 0L),
            rowId = o.optLong("id", 0L)
        )
    }

    /** 拉取服务器上的「我喜欢」（带完整歌曲元数据）；未登录/失败返回 null */
    suspend fun fetchLikes(): List<CloudSong>? = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank()) return@withContext null
        val root = ServerApi.request("/api/favorites", null, token) ?: return@withContext null
        if (!okCode(root)) return@withContext null
        val arr = root.optJSONArray("data") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i -> parseCloudSong(arr.optJSONObject(i)) }
    }

    /** 播放上报（歌曲 + 实际收听毫秒） */
    suspend fun pushPlay(song: Song, playedMs: Long): Boolean = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank()) return@withContext false
        val body = songBody(song).put("progress", (playedMs / 1000).toInt())
        ServerApi.request("/api/history", body, token)?.let { okCode(it) } == true
    }

    /** 搜索历史上报 */
    suspend fun pushSearchKeyword(keyword: String): Boolean = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank() || keyword.isBlank()) return@withContext false
        val body = JSONObject().put("keyword", keyword)
        ServerApi.request("/api/search-history", body, token)?.let { okCode(it) } == true
    }

    // ---------- 歌单云同步 ----------
    /** 创建歌单到云端，返回云端歌单 ID；失败返回 null */
    suspend fun createPlaylistCloud(name: String): Long? = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank()) return@withContext null
        val body = JSONObject().put("name", name)
        val root = ServerApi.request("/api/playlists", body, token) ?: return@withContext null
        if (!okCode(root)) return@withContext null
        root.optJSONObject("data")?.optLong("id")
    }

    /** 添加歌曲到云端歌单 */
    suspend fun addSongToPlaylistCloud(cloudPlaylistId: Long, song: Song): Boolean = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank() || cloudPlaylistId <= 0) return@withContext false
        ServerApi.request("/api/playlists/$cloudPlaylistId/songs", songBody(song), token)?.let { okCode(it) } == true
    }

    /** 从云端歌单移除歌曲（songCloudId 为服务器 song_id 字符串，不是本地数据库行号） */
    suspend fun removeSongFromPlaylistCloud(cloudPlaylistId: Long, songCloudId: String): Boolean = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank() || cloudPlaylistId <= 0 || songCloudId.isBlank()) return@withContext false
        val encoded = java.net.URLEncoder.encode(songCloudId, "UTF-8")
        ServerApi.request("/api/playlists/$cloudPlaylistId/songs/$encoded", null, token, "DELETE")?.let { okCode(it) } == true
    }

    /** 拉取服务器上的全部歌单（含每首歌的完整元数据）；未登录/失败返回 null */
    suspend fun fetchPlaylists(): List<CloudPlaylist>? = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank()) return@withContext null
        val root = ServerApi.request("/api/playlists", null, token) ?: return@withContext null
        if (!okCode(root)) return@withContext null
        val arr = root.optJSONArray("data") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val pid = o.optLong("id", 0L)
            if (pid <= 0) return@mapNotNull null
            val songsArr = o.optJSONArray("songs")
            val songs = (0 until (songsArr?.length() ?: 0)).mapNotNull { j ->
                parseCloudSong(songsArr?.optJSONObject(j))
            }
            CloudPlaylist(
                id = pid,
                name = o.optString("name", "").orEmpty().ifBlank { "未命名歌单" },
                createdAt = o.optLong("created_at", 0L),
                updatedAt = o.optLong("updated_at", 0L),
                songs = songs
            )
        }
    }

    /** 删除云端歌单 */
    suspend fun deletePlaylistCloud(cloudPlaylistId: Long): Boolean = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank() || cloudPlaylistId <= 0) return@withContext false
        ServerApi.request("/api/playlists/$cloudPlaylistId", null, token, "DELETE")?.let { okCode(it) } == true
    }

    /** 重命名云端歌单 */
    suspend fun renamePlaylistCloud(cloudPlaylistId: Long, name: String): Boolean = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank() || cloudPlaylistId <= 0 || name.isBlank()) return@withContext false
        val body = JSONObject().put("name", name)
        ServerApi.request("/api/playlists/$cloudPlaylistId", body, token, "PATCH")?.let { okCode(it) } == true
    }

    /** 拉取搜索历史 */
    suspend fun fetchSearchHistory(): List<String>? = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank()) return@withContext null
        val root = ServerApi.request("/api/search-history", null, token) ?: return@withContext null
        if (!okCode(root)) return@withContext null
        val arr = root.optJSONArray("data") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { arr.optString(it)?.takeIf { k -> k.isNotBlank() } }
    }

    /** 增量同步：拉取 since 时间戳之后的所有变更 */
    data class SyncData(
        val serverTime: Long,
        val favorites: List<String>,
        val history: List<String>,
        val searchHistory: List<String>
    )
    suspend fun syncAll(sinceMs: Long): SyncData? = withContext(Dispatchers.IO) {
        val token = AccountStore.token
        if (token.isBlank()) return@withContext null
        val root = ServerApi.request("/api/sync?since=$sinceMs", null, token) ?: return@withContext null
        if (!okCode(root)) return@withContext null
        val d = root.optJSONObject("data") ?: return@withContext null
        val fav = d.optJSONArray("favorites")
        val his = d.optJSONArray("history")
        val sh = d.optJSONArray("search_history")
        SyncData(
            serverTime = d.optLong("server_time", System.currentTimeMillis()),
            favorites = (0 until (fav?.length() ?: 0)).mapNotNull { fav?.optJSONObject(it)?.optString("song_id") }.filter { it.isNotBlank() },
            history = (0 until (his?.length() ?: 0)).mapNotNull { his?.optJSONObject(it)?.optString("song_id") }.filter { it.isNotBlank() },
            searchHistory = (0 until (sh?.length() ?: 0)).mapNotNull { sh?.optString(it)?.takeIf { k -> k.isNotBlank() } }
        )
    }
}
