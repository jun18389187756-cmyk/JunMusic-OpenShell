package com.jun.musiccloud.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自有服务器（Node + SQLite）接口。
 *
 * 【换服务器只改这一处】把新域名 / 地址填到 [BASE_URL]（末尾不要带斜杠）即可，
 * App 的账号、我喜欢同步、远程配置、检查更新、网易云曲库（[NETEASE_PATH]）全部跟着走。
 *
 * 换服务器时的完整清单（服务端要提供的东西见仓库根目录 `server/` 与 `docs/SERVER.md`）：
 *   1. App 接口服务  —— `server/`（端口 3001，只监听 127.0.0.1），nginx 反代到 `/jm/`
 *   2. 网易云接口    —— NeteaseCloudMusicApi（端口 3002），nginx 反代到 `/jm/netease/`
 *   3. 下载站        —— 已存在的站点（端口 3000），App 的版本更新从这里读最新安装包
 *   4. HTTPS 证书    —— `certbot --nginx -d 你的域名`
 *
 * 留空表示尚未接入：所有调用安全降级（不联网、不检查更新、用本地默认热搜词）。
 */
object ServerApi {

    /**
     * 服务器地址（末尾不要带斜杠）。
     *
     * 走 HTTPS（Let's Encrypt 证书）：账号凭证与听歌数据都经这里传输，
     * 明文 HTTP 在同网段可被直接嗅探。
     * 若你的服务器还没上 HTTPS，改成 `http://...` 并把 [ALLOW_INSECURE_HTTP] 打开即可，
     * network_security_config 已放行明文，不会连不上——但这只是过渡，别当常态。
     */
    const val BASE_URL = "https://api.example.invalid"

    /** 网易云接口在服务器上的挂载路径（App 不直接连网易云，统一走自有服务器） */
    const val NETEASE_PATH = "/netease"

    /** 允许明文 HTTP（仅用于服务器还没上 HTTPS 的过渡期） */
    const val ALLOW_INSECURE_HTTP = false

    /** 实际使用的地址：按上面的开关在 https / http 之间选一个 */
    val effectiveBaseUrl: String
        get() = if (ALLOW_INSECURE_HTTP && BASE_URL.startsWith("https://")) {
            "http" + BASE_URL.removePrefix("https")
        } else BASE_URL

    val isConfigured: Boolean get() = BASE_URL.isNotBlank()

    private const val TIMEOUT_MS = 8000

    /** 凭证过期 / 被服务端拒绝：这两码一律当成"要重新登录"，不再和"网络失败"混在一起 */
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403

    /** 服务器上的最新版本 */
    data class RemoteVersion(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val size: Long,
        val force: Boolean,
        val minVersionCode: Int,
        val changelog: String
    )

    /** 拉取应用远程配置；未接入或失败返回 null */
    suspend fun fetchConfig(): AppRemoteConfig? = withContext(Dispatchers.IO) {
        val root = getJson("/api/app/config") ?: return@withContext null
        val c = root.optJSONObject("config") ?: return@withContext null
        AppRemoteConfig(
            hotKeywords = c.optJSONArray("hotKeywords")?.let { arr ->
                (0 until arr.length())
                    .map { arr.optString(it) }
                    .filter { it.isNotBlank() }
            } ?: emptyList(),
            maintenance = c.optBoolean("maintenance", false),
            maintenanceText = c.optString("maintenanceText", ""),
            notice = c.optString("notice", ""),
            // 6.8.0：应用远程音源开关（flags.sources）。拉到配置立刻生效，不用重启 ——
            // 某个源挂掉之后，后台点一下关闭，用户下次刷新配置就自动绕开它。
            flags = AppFlags.from(c.optJSONObject("flags")).also {
                runCatching {
                    SourceSwitches.apply(c.optJSONObject("flags")?.optJSONObject("sources"))
                }
            },
            banners = AppBanner.from(c.optJSONArray("banners")),
            sensitiveEnabled = c.optBoolean("sensitiveEnabled", true),
            sensitiveWords = c.optJSONArray("sensitiveWords")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList(),
            customerService = AppCustomerService.from(c.optJSONObject("customerService"))
        )
    }

    /**
     * 拉取「通知中心」里 id > since 的公告。
     *
     * 和配置里的 notice 字段不同：这里是真正的推送事件——后台每发一次就多一条
     * 自增 id，即使内容一模一样，App 也会再弹一次。失败返回 null（保持原样，
     * 不要把网络抖动当成「没有新通知」）。
     */
    suspend fun fetchNotices(
        since: Long,
        deviceKey: String = "",
        versionCode: Int = 0,
        channel: String = "stable",
        uid: Long = 0L,
        username: String = "",
        model: String = ""
    ): List<AppNotice>? = withContext(Dispatchers.IO) {
        val q = StringBuilder("/api/app/notices?since=$since&channel=$channel&versionCode=$versionCode")
        if (deviceKey.isNotBlank()) q.append("&deviceKey=").append(java.net.URLEncoder.encode(deviceKey, "UTF-8"))
        if (uid > 0) q.append("&uid=").append(uid)
        if (username.isNotBlank()) q.append("&username=").append(java.net.URLEncoder.encode(username, "UTF-8"))
        if (model.isNotBlank()) q.append("&model=").append(java.net.URLEncoder.encode(model, "UTF-8"))
        val root = getJson(q.toString()) ?: return@withContext null
        val data = root.optJSONObject("data") ?: return@withContext null
        val arr = data.optJSONArray("notices") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val content = o.optString("content", "")
            if (content.isBlank()) return@mapNotNull null
            AppNotice(
                id = o.optLong("id", 0L),
                title = o.optString("title", "").ifBlank { "公告" },
                content = content,
                level = o.optString("level", "info"),
                createdAt = o.optLong("created_at", 0L),
                dailyLimit = o.optInt("daily_limit", 0),
                imageUrl = o.optString("image_url", ""),
                linkUrl = o.optString("link_url", "")
            )
        }
    }

    /** 拉取当前线上版本；无版本记录或未接入返回 null */
    suspend fun fetchVersion(
        channel: String = "stable",
        bucket: Int = -1,
        /** 设备稳定标识（ANDROID_ID 哈希）：后台定向测试更新用，匿名时为空 */
        deviceKey: String = "",
        /** 登录用户 id：后台按账号定向测试更新用，未登录为 0 */
        userId: Long = 0L,
        /** 当前安装的 versionCode：后台统计设备版本 / 定向规则用 */
        versionCode: Int = 0,
        /** 机型：后台设备列表展示用 */
        model: String = ""
    ): RemoteVersion? = withContext(Dispatchers.IO) {
        val q = buildString {
            append("/api/app/version?channel=")
            append(java.net.URLEncoder.encode(channel, "UTF-8"))
            if (bucket in 0..99) append("&bucket=").append(bucket)
            if (deviceKey.isNotBlank()) {
                append("&deviceKey=").append(java.net.URLEncoder.encode(deviceKey, "UTF-8"))
            }
            if (userId > 0L) append("&uid=").append(userId)
            if (versionCode > 0) append("&versionCode=").append(versionCode)
            if (model.isNotBlank()) {
                append("&model=").append(java.net.URLEncoder.encode(model, "UTF-8"))
            }
        }
        val root = getJson(q) ?: return@withContext null
        val v = root.optJSONObject("version") ?: return@withContext null
        val code = v.optInt("versionCode", 0)
        val name = v.optString("versionName", "")
        val url = v.optString("apkUrl", "")
        if (code <= 0 || name.isBlank() || url.isBlank()) return@withContext null
        RemoteVersion(
            versionCode = code,
            versionName = name,
            apkUrl = url,
            sha256 = v.optString("sha256", ""),
            size = v.optLong("size", 0L),
            force = v.optBoolean("force", false),
            minVersionCode = v.optInt("minVersionCode", 0),
            changelog = v.optString("changelog", "")
        )
    }

    /** 听歌时长榜单条目（6.3）：带段位、累计时长、游客标记 */
    data class BoardEntry(
        val rank: Int,
        val key: String,
        val name: String,
        val avatar: String,
        val seconds: Long,
        val total: Long,
        val guest: Boolean,
        val tier: Int,
        val tierName: String,
        val badge: String
    )

    /** 榜单结果：前 N 名 + 总人数 + 我的名次（可能不在前 N） */
    data class BoardResult(
        val enabled: Boolean,
        val total: Int,
        val items: List<BoardEntry>,
        val me: BoardEntry?
    )

    private fun boardEntry(o: JSONObject?, fallbackRank: Int): BoardEntry? {
        val o = o ?: return null
        return BoardEntry(
            rank = o.optInt("rank", fallbackRank),
            key = o.optString("key", ""),
            name = o.optString("name", "").ifBlank { "神秘听众" },
            avatar = o.optString("avatar", ""),
            seconds = o.optLong("seconds", 0L),
            total = o.optLong("total", 0L),
            guest = o.optInt("guest", 0) == 1,
            tier = o.optInt("tier", 1),
            tierName = o.optString("tierName", "初听者"),
            badge = o.optString("badge", "初听")
        )
    }

    /**
     * 拉取听歌时长榜单（公开接口，免登录）。
     * [range]：all = 总榜（默认）/ 7 = 周榜 / today = 日榜；[limit] 最多 100，默认 20。
     * [meKey]：用来定位「我的名次」，注册用户传 `u<uid>`，游客传 `g<deviceKey>`。
     * 失败返回 null——调用方保留上一次数据，别闪成空白。
     */
    suspend fun fetchListenBoard(
        range: String = "all",
        limit: Int = 20,
        meKey: String = ""
    ): BoardResult? = withContext(Dispatchers.IO) {
        val q = StringBuilder("/api/app/board/listen?range=")
            .append(range).append("&limit=").append(limit.coerceIn(1, 100))
        if (meKey.isNotBlank()) {
            q.append("&me=1")
            if (meKey.startsWith("u")) q.append("&uid=").append(meKey.removePrefix("u"))
            else q.append("&deviceKey=").append(java.net.URLEncoder.encode(meKey.removePrefix("g"), "UTF-8"))
        }
        val root = getJson(q.toString()) ?: return@withContext null
        val data = root.optJSONObject("data") ?: return@withContext null
        val enabled = data.optBoolean("enabled", true)
        val arr = data.optJSONArray("items")
        val list = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
            boardEntry(arr.optJSONObject(i), i + 1)
        }
        BoardResult(
            enabled = enabled,
            total = list.size,
            items = list,
            me = boardEntry(data.optJSONObject("me"), 0)
        )
    }

    /** 我的听歌档案（段位 + 下一档还差多少 + 行为彩蛋头衔） */
    data class ListenTierInfo(
        val enabled: Boolean,
        val loggedIn: Boolean,
        val total: Long,
        val week: Long,
        val today: Long,
        val tier: Int,
        val tierName: String,
        val badge: String,
        val nextTier: Int,
        val nextName: String,
        val nextNeed: Long,
        val titles: List<Pair<String, String>>
    )

    suspend fun fetchListenTier(uid: Long, deviceKey: String): ListenTierInfo? = withContext(Dispatchers.IO) {
        val q = StringBuilder("/api/profile/listen-tier?")
        if (uid > 0) q.append("uid=").append(uid)
        else if (deviceKey.isNotBlank()) {
            q.append("deviceKey=").append(java.net.URLEncoder.encode(deviceKey, "UTF-8"))
        } else return@withContext null
        val root = getJson(q.toString()) ?: return@withContext null
        val d = root.optJSONObject("data") ?: return@withContext null
        if (!d.optBoolean("enabled", false)) return@withContext null
        val tr = d.optJSONArray("titles")
        val titles = if (tr == null) emptyList() else (0 until tr.length()).mapNotNull { i ->
            val o = tr.optJSONObject(i) ?: return@mapNotNull null
            o.optString("name", "") to o.optString("desc", "")
        }.filter { it.first.isNotBlank() }
        ListenTierInfo(
            enabled = true,
            loggedIn = d.optBoolean("loggedIn", false),
            total = d.optLong("total", 0L),
            week = d.optLong("week", 0L),
            today = d.optLong("today", 0L),
            tier = d.optInt("tier", 1),
            tierName = d.optString("tierName", "初听者"),
            badge = d.optString("badge", "初听"),
            nextTier = d.optInt("nextTier", 0),
            nextName = d.optString("nextName", ""),
            nextNeed = d.optLong("nextNeed", 0L),
            titles = titles
        )
    }

    /** 崩溃上报：崩溃线程里调用，任何异常都吞掉，绝不能再把进程带崩一次 */
    fun uploadCrash(
        message: String,
        stack: String,
        thread: String,
        versionCode: Int,
        versionName: String,
        deviceKey: String = "",
        userId: Long = 0L,
        username: String = "",
        model: String = "",
        brand: String = "",
        os: String = "",
        sdk: Int = 0
    ): Boolean = runCatching {
        if (!isConfigured) return false
        if (message.isBlank() && stack.isBlank()) return false
        val body = JSONObject()
            .put("message", message.take(800))
            .put("stack", stack.take(20000))
            .put("thread", thread.take(120))
            .put("versionCode", versionCode)
            .put("versionName", versionName.take(32))
            .put("deviceKey", deviceKey.take(120))
            .put("userId", userId)
            .put("username", username.take(64))
            .put("model", model.take(120))
            .put("brand", brand.take(64))
            .put("os", os.take(64))
            .put("sdk", sdk)
            .put("ts", System.currentTimeMillis())
        request("/api/app/crash", body) != null
    }.getOrDefault(false)

    /** path -> (ETag, 上次响应体)：304 时直接用缓存，不再下载正文 */
    private val etagCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    /**
     * 6.9.8：进入安全模式的设备上报。
     *
     * 让后台知道「哪些设备因崩溃落到了安全模式」，从而定向推送修复 / 强制更新。
     * 这是 fire-and-forget：整个调用包在 runCatching 里，且 [request] 自身已吞掉所有网络异常，
     * 任何失败（没联网 / 接口未就绪 / 非 2xx）都不会抛、不会拖慢、不会闪退。
     * 后台该接口是并行在做的，未就绪也无所谓——只要别让客户端崩。
     */
    fun reportSafeMode(
        deviceId: String,
        versionCode: Int,
        crashSig: String,
        message: String = ""
    ) {
        if (!isConfigured) return
        runCatching {
            val body = JSONObject()
                .put("deviceId", deviceId.take(120))
                .put("versionCode", versionCode)
                .put("crashSig", crashSig.take(200))
                .put("message", message.take(800))
            request("/api/app/safemode/report", body)
        }
    }

    private fun getJson(path: String): JSONObject? {
        if (!isConfigured) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(effectiveBaseUrl + path).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "JunMusic")
            conn.setRequestProperty("Accept", "application/json")
            val cached = etagCache[path]
            if (cached != null) conn.setRequestProperty("If-None-Match", cached.first)
            when (conn.responseCode) {
                304 -> cached?.let { runCatching { JSONObject(it.second) }.getOrNull() }
                200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val tag = conn.getHeaderField("ETag")
                    if (!tag.isNullOrBlank()) etagCache[path] = tag to body
                    JSONObject(body)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 通用请求（账号 / 我喜欢 / 播放上报复用）。
     *
     * body 非空走 POST，否则 GET；token 非空带 Authorization: Bearer。
     * 服务器统一返回 `{code, msg, ...}` JSON，HTTP 非 2xx 时 body 在 errorStream 里，
     * 所以正文照旧从 errorStream 读（很多业务错误码写在里面）。
     *
     * 6.1.2 修掉"完全不看状态码"：以前 401 的响应体和 200 的响应体在上层长得一模一样
     * （都是 `code != 0 → 返回 null`），token 过期与"这台设备没这条数据""网络断了"被压成
     * 同一件事，云同步从此永久静默失败、也不提示重登。现在把 **HTTP 401/403** 与
     * **body code 401/403** 两条未授权路径都收在这一个出口点亮 [SessionSignals]，
     * 调用方不必各自判 401、也不必改返回值语义（解不出 JSON 仍返回 null）。
     */
    fun request(
        path: String,
        body: JSONObject? = null,
        token: String? = null,
        method: String? = null,
        timeoutMs: Int = TIMEOUT_MS
    ): JSONObject? {
        if (!isConfigured) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(effectiveBaseUrl + path).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "JunMusic")
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            val m = method ?: if (body != null) "POST" else "GET"
            conn.requestMethod = m
            if (body != null && (m == "POST" || m == "PUT" || m == "PATCH")) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            // 必须先触发请求（responseCode），响应头与 errorStream 才可用
            val status = conn.responseCode
            // 未授权要先在"读不读得到响应体"之前记下来：不少服务端拒绝时直接给空 body，
            // 老代码这里是 `errorStream ?: return null`，状态码都没看就出去了，
            // 过期信号也就永远点不亮（这正是"云同步永久静默失败"的另一半）。
            val deniedByStatus = token != null &&
                (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN)
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val root = stream?.let { s ->
                runCatching { JSONObject(s.bufferedReader().use { it.readText() }) }.getOrNull()
            }
            // 只有带着凭证去打的请求才谈得上"过期"；匿名请求 401 是别的问题，不该喊重登
            if (token != null && (deniedByStatus || root.isUnauthorizedCode())) {
                SessionSignals.markExpired(
                    SessionSignals.SOURCE_ACCOUNT,
                    root?.optString("msg", "").orEmpty()
                )
            }
            root
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 带自定义头的文本请求（事件批量上报签名用）。
     * bodyText 必须与签名时使用的字符串完全一致，服务端按收到的原始字节验签。
     */
    fun requestText(
        path: String,
        bodyText: String,
        headers: Map<String, String>,
        token: String? = null
    ): JSONObject? {
        if (!isConfigured) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(effectiveBaseUrl + path).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "JunMusic")
            conn.setRequestProperty("Accept", "application/json")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(bodyText.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: return null
            runCatching { JSONObject(text) }.getOrNull()
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}

/** 业务 code 是不是"未授权/凭证过期"（服务器习惯把 401 放进 200 的响应体里） */
private fun JSONObject?.isUnauthorizedCode(): Boolean {
    if (this == null) return false
    val c = optInt("code", -1)
    return c == 401 || c == 403
}

/**
 * 「登录已过期」一次性信号。
 *
 * 为什么不塞进 PlayerUiState：那个 data class 有 125 个构造参数，已经贴着 dex
 * 寄存器上限，再加字段有 VerifyError 风险；而"凭证过期"既不是播放状态、也不该
 * 在每次 copy 时被搬一遍。用 Compose State 承载（和 [RemoteConfigStore.flagsState]
 * 同一套路），界面读 `.value` 自动订阅；tick 自增保证同一句文案也能再弹一次。
 *
 * 谁点亮它：[ServerApi.request]（自建账号 token 过期）与 PlayerViewModel 里
 * 网易云 `/user/playlist` 返回 401/403 的那一条路。
 */
object SessionSignals {

    /** 该去哪一边重新登录：自建账号（云同步）还是网易云（我的歌单/我喜欢） */
    const val SOURCE_ACCOUNT = "account"
    const val SOURCE_NETEASE = "netease"

    /** 只增不减的触发计数：UI 用它驱动一次性提示 */
    val expiredTick = mutableStateOf(0)
    val expiredMessage = mutableStateOf("")
    val expiredTarget = mutableStateOf(SOURCE_ACCOUNT)

    /** 同一次过期不重复弹：60 秒内同一个来源只点一次，界面状态仍然保持 */
    @Volatile
    private var lastMarkAt = 0L
    private const val COOLDOWN_MS = 60_000L

    fun markExpired(target: String, message: String? = null) {
        val now = System.currentTimeMillis()
        val sameTarget = expiredTarget.value == target
        if (sameTarget && now - lastMarkAt < COOLDOWN_MS) return
        lastMarkAt = now
        expiredTarget.value = target
        expiredMessage.value = message?.takeIf { it.isNotBlank() }
            ?: if (target == SOURCE_NETEASE) "网易云登录已过期，重新登录后才能看到最新歌单"
            else "登录已过期，请重新登录"
        expiredTick.value = expiredTick.value + 1
    }
}

/** 服务器下发的应用配置 */
data class AppRemoteConfig(
    val hotKeywords: List<String>,
    val maintenance: Boolean,
    val maintenanceText: String,
    val notice: String,
    val flags: AppFlags = AppFlags(),
    val banners: List<AppBanner> = emptyList(),
    val sensitiveEnabled: Boolean = true,
    val sensitiveWords: List<String> = emptyList(),
    val customerService: AppCustomerService = AppCustomerService()
)

/**
 * 总后台「功能开关」。
 *
 * 缺省一律 true：老服务端 / 老配置不认识这些字段时，App 保持原有全部能力，
 * 不会因为升级客户端而突然少功能。后台显式关掉某一项才会隐藏对应入口。
 */
data class AppFlags(
    val download: Boolean = true,
    val comments: Boolean = true,
    val netease: Boolean = true,
    val register: Boolean = true,
    val guest: Boolean = true,
    val playlist: Boolean = true
) {
    companion object {
        fun from(o: JSONObject?): AppFlags = if (o == null) AppFlags() else AppFlags(
            download = o.optBoolean("download", true),
            comments = o.optBoolean("comments", true),
            netease = o.optBoolean("netease", true),
            register = o.optBoolean("register", true),
            guest = o.optBoolean("guest", true),
            playlist = o.optBoolean("playlist", true)
        )
    }
}

/** 首页运营横幅（后台「横幅管理」下发，图片可空时退化为文字卡） */
data class AppBanner(
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val image: String = "",
    val link: String = ""
) {
    companion object {
        /** 官网 / 上传资源的公网地址（图片、APK 都挂在这个域名下） */
        const val PUBLIC_SITE = "https://api.example.invalid"

        private fun absolute(u: String): String {
            val t = u.trim()
            if (t.isBlank()) return ""
            if (t.startsWith("http://") || t.startsWith("https://")) return t
            return PUBLIC_SITE + if (t.startsWith("/")) t else "/$t"
        }

        fun from(arr: JSONArray?): List<AppBanner> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title", "").trim()
                val image = absolute(o.optString("image", ""))
                if (title.isBlank() && image.isBlank()) return@mapNotNull null
                val rawLink = o.optString("link", "").trim()
                AppBanner(
                    id = o.optString("id", "") ?: "",
                    title = title,
                    subtitle = o.optString("subtitle", "").trim(),
                    image = image,
                    // 站内相对跳转也补成绝对地址；http(s) 原样保留
                    link = if (rawLink.startsWith("/")) absolute(rawLink) else rawLink
                )
            }
        }
    }
}

/** 客服联系方式（后台「App 配置」下发） */
data class AppCustomerService(
    val qq: String = "",
    val wechat: String = "",
    val email: String = ""
) {
    companion object {
        fun from(o: JSONObject?): AppCustomerService = if (o == null) AppCustomerService() else AppCustomerService(
            qq = o.optString("qq", "").trim(),
            wechat = o.optString("wechat", "").trim(),
            email = o.optString("email", "").trim()
        )
    }

    val isEmpty: Boolean get() = qq.isBlank() && wechat.isBlank() && email.isBlank()
}

/**
 * 远程配置本地缓存（prefs）：冷启动先读缓存给搜索页用，随后异步刷新。
 * 这样「服务器改热搜词 → 用户下次打开即生效」不需要发版。
 */
object RemoteConfigStore {

    private const val PREFS = "jun_remote"
    private const val KEY_HOT = "hot_keywords"
    private const val KEY_MAINT = "maintenance"
    private const val KEY_MAINT_TEXT = "maintenance_text"
    private const val KEY_NOTICE = "notice"
    private const val KEY_NOTICE_SEEN = "notice_seen"
    private const val KEY_FLAGS = "flags_json"
    private const val KEY_BANNERS = "banners_json"
    private const val KEY_SENSITIVE_ON = "sensitive_on"
    private const val KEY_SENSITIVE_WORDS = "sensitive_words"
    private const val KEY_CUSTOMER = "customer_json"

    /** 本地默认热搜词（服务器未接入或还没拉到配置时兜底） */
    var hotKeywords: List<String> = OnlineMusicApi.hotKeywords
        private set

    /** 维护模式（服务器下发；界面在本次启动拉到配置后立即生效） */
    var maintenance: Boolean = false
        private set

    /** 维护提示文案 */
    var maintenanceText: String = ""
        private set

    /** 服务器公告正文 */
    var notice: String = ""
        private set

    /**
     * 功能开关 / 横幅 / 敏感词 / 客服：用 Compose State 承载。
     *
     * 界面直接读 `.value` 会自动订阅——后台改开关后，下一次轮询
     * （App 每 20–60 秒拉一次配置）就能让入口即时出现或消失，不用重启。
     */
    val flagsState = mutableStateOf(AppFlags())
    val bannersState = mutableStateOf<List<AppBanner>>(emptyList())
    val sensitiveEnabledState = mutableStateOf(true)
    val sensitiveWordsState = mutableStateOf<List<String>>(emptyList())
    val customerServiceState = mutableStateOf(AppCustomerService())

    /** 只读快捷访问（非 Compose 场景下使用） */
    val flags: AppFlags get() = flagsState.value
    val banners: List<AppBanner> get() = bannersState.value
    val sensitiveEnabled: Boolean get() = sensitiveEnabledState.value
    val sensitiveWords: List<String> get() = sensitiveWordsState.value
    val customerService: AppCustomerService get() = customerServiceState.value

    /** 用户已经读过的公告内容（按正文比对，同一条不重复弹） */
    private var seenNotice: String = ""

    /** 是否有一条用户还没读过的公告 */
    val hasUnseenNotice: Boolean get() = notice.isNotBlank() && notice != seenNotice

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_HOT, null)?.takeIf { it.isNotBlank() }?.let { raw ->
            hotKeywords = raw.split('\u0001').filter { it.isNotBlank() }
        }
        maintenance = p.getBoolean(KEY_MAINT, false)
        maintenanceText = p.getString(KEY_MAINT_TEXT, "") ?: ""
        notice = p.getString(KEY_NOTICE, "") ?: ""
        seenNotice = p.getString(KEY_NOTICE_SEEN, "") ?: ""
        // 上次拉到的开关 / 横幅 / 敏感词 / 客服：冷启动立刻按缓存渲染，
        // 避免"先全显示，800ms 后才消失"的闪烁。
        flagsState.value = runCatching {
            AppFlags.from(JSONObject(p.getString(KEY_FLAGS, "") ?: ""))
        }.getOrDefault(AppFlags())
        bannersState.value = runCatching {
            AppBanner.from(JSONArray(p.getString(KEY_BANNERS, "") ?: ""))
        }.getOrDefault(emptyList())
        sensitiveEnabledState.value = p.getBoolean(KEY_SENSITIVE_ON, true)
        sensitiveWordsState.value = (p.getString(KEY_SENSITIVE_WORDS, "") ?: "")
            .split('\u0001').filter { it.isNotBlank() }
        customerServiceState.value = runCatching {
            AppCustomerService.from(JSONObject(p.getString(KEY_CUSTOMER, "") ?: ""))
        }.getOrDefault(AppCustomerService())
    }

    /**
     * 写入服务器配置。
     * 返回 true 表示这次带回了一条用户还没读过的新公告（调用方负责弹窗）。
     * 热搜词以服务器为准（后台清空也要同步清空），只有请求失败时才保留本地缓存。
     */
    fun save(context: Context, config: AppRemoteConfig): Boolean {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        hotKeywords = config.hotKeywords
        editor.putString(KEY_HOT, config.hotKeywords.joinToString("\u0001"))
        maintenance = config.maintenance
        maintenanceText = config.maintenanceText
        notice = config.notice
        editor.putBoolean(KEY_MAINT, maintenance)
            .putString(KEY_MAINT_TEXT, maintenanceText)
            .putString(KEY_NOTICE, notice)

        // 功能开关 / 横幅 / 敏感词 / 客服
        flagsState.value = config.flags
        bannersState.value = config.banners
        sensitiveEnabledState.value = config.sensitiveEnabled
        sensitiveWordsState.value = config.sensitiveWords
        customerServiceState.value = config.customerService
        editor.putString(KEY_FLAGS, flagsJson(config.flags))
            .putString(KEY_BANNERS, bannersJson(config.banners))
            .putBoolean(KEY_SENSITIVE_ON, config.sensitiveEnabled)
            .putString(KEY_SENSITIVE_WORDS, config.sensitiveWords.joinToString("\u0001"))
            .putString(
                KEY_CUSTOMER,
                JSONObject()
                    .put("qq", config.customerService.qq)
                    .put("wechat", config.customerService.wechat)
                    .put("email", config.customerService.email)
                    .toString()
            )
            .apply()
        return hasUnseenNotice
    }

    private fun flagsJson(f: AppFlags): String = JSONObject()
        .put("download", f.download)
        .put("comments", f.comments)
        .put("netease", f.netease)
        .put("register", f.register)
        .put("guest", f.guest)
        .put("playlist", f.playlist)
        .toString()

    private fun bannersJson(list: List<AppBanner>): String {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(
                JSONObject()
                    .put("id", b.id)
                    .put("title", b.title)
                    .put("subtitle", b.subtitle)
                    .put("image", b.image)
                    .put("link", b.link)
            )
        }
        return arr.toString()
    }

    /** 用户点了「我知道了」：记下当前公告，之后内容不变就不再弹 */
    fun markNoticeSeen(context: Context) {
        seenNotice = notice
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NOTICE_SEEN, seenNotice)
            .apply()
    }
}

/** 服务器下发的一条通知（id 单调递增，客户端按 id 去重） */
data class AppNotice(
    val id: Long,
    val title: String,
    val content: String,
    val level: String,
    val createdAt: Long,
    /** 0 = 只提醒一次；>0 = 每天最多提醒几次 */
    val dailyLimit: Int = 0,
    val imageUrl: String = "",
    val linkUrl: String = ""
)

/**
 * 通知中心已读位置：只记「最大已读 id」。
 *
 * 不用正文比对判重——运营哪怕把同一句话再发一次，也必须是一轮新的弹窗，
 * 这就是「后台一按，App 就弹」的核心。
 */
object NoticeStore {

    private const val PREFS = "jun_notices"
    private const val KEY_MAX_ID = "max_seen_id"
    private const val KEY_DAILY = "daily_counts"

    /** 本地已读到的最大通知 id（只对「只提醒一次」的通知生效） */
    var maxSeenId: Long = 0L
        private set

    private var dailyRaw: String = "{}"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        maxSeenId = p.getLong(KEY_MAX_ID, 0L)
        dailyRaw = p.getString(KEY_DAILY, "{}") ?: "{}"
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    private fun countsToday(): JSONObject {
        val root = runCatching { JSONObject(dailyRaw) }.getOrElse { JSONObject() }
        val day = today()
        val out = JSONObject()
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val o = root.optJSONObject(k) ?: continue
            if (o.optString("day") == day) out.put(k, o.optInt("n", 0))
        }
        return out
    }

    /** 这条通知现在能不能弹：一次性通知看已读位，每日通知看今天已经弹过几次 */
    fun canShow(n: AppNotice): Boolean {
        if (n.dailyLimit <= 0) return n.id > maxSeenId
        return countsToday().optInt(n.id.toString(), 0) < n.dailyLimit
    }

    /** 用户点「已读」：一次性通知推进已读位；每日通知累计今天的次数 */
    fun markShown(context: Context, n: AppNotice) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (n.dailyLimit <= 0) {
            markSeen(context, n.id)
            return
        }
        val root = runCatching { JSONObject(dailyRaw) }.getOrElse { JSONObject() }
        val key = n.id.toString()
        val day = today()
        val prev = root.optJSONObject(key)
        val n0 = if (prev != null && prev.optString("day") == day) prev.optInt("n", 0) else 0
        root.put(key, JSONObject().put("day", day).put("n", n0 + 1))
        dailyRaw = root.toString()
        p.edit().putString(KEY_DAILY, dailyRaw).apply()
    }

    fun markSeen(context: Context, id: Long) {
        if (id <= maxSeenId) return
        maxSeenId = id
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_MAX_ID, id)
            .apply()
    }
}
