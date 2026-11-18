package com.jun.musiccloud.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 网易云音乐接口适配层（走「自有服务器」上的 Node API）。
 *
 * 服务器部署：`https://api.example.invalid/netease/` → systemd 服务 `netease-api`
 * （binaryify/NeteaseCloudMusicApi，监听 127.0.0.1:3002，nginx 反代 /jm/netease/）。
 * 换服务器时只改 [ServerApi.BASE_URL]，本文件无需改动。
 * 好处：加密、风控与 Cookie 会话全部在服务器侧处理，App 只发普通 HTTP，
 * 既稳定又不受手机侧网络环境影响；同时保持「不依赖第三方公开实例」。
 *
 * 会话：登录成功后把服务端下发的 Cookie 串存进 [NeteaseStore] 并缓存在内存，
 * 之后所有需要登录的请求都带上 `cookie=` 参数。
 */
object NeteaseApi {

    // 走 effectiveBaseUrl：随 ServerApi 的 https/http 开关一起变
    private val BASE get() = ServerApi.effectiveBaseUrl + ServerApi.NETEASE_PATH
    private const val UA = "JunMusic"
    /**
     * 公开搜索/歌手/专辑/歌单走网易云官方接口直连。
     *
     * 之前这些只读接口全部走服务器上的 binaryify 代理，但线上 `/jm/netease`
     * 已经 404，导致搜索页的歌手/专辑/歌单永远是空的，歌手页也只能显示曲库里
     * 原有的那两首。官方 web 接口带上普通浏览器 UA + Referer 就能正常返回，
     * 不依赖登录 Cookie，所以这里单独留一条直连通道；登录、评论等仍然走代理。
     */
    private const val DIRECT_BASE = "https://music.163.com/api"
    private const val DIRECT_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val CONNECT_MS = 12000
    private const val READ_MS = 20000

    /** 登录后的 Cookie（由 [NeteaseStore] 注入；含 MUSIC_U / __csrf） */
    @Volatile var cookie: String = ""
        private set

    /** 供 [NeteaseStore] 恢复会话时写回 */
    fun restoreCookie(value: String) {
        cookie = value
    }

    // ==================== 数据模型 ====================

    /** 网易云搜索/歌单里的歌曲 */
    data class NsSong(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val cover: String,
        val durationMs: Long,
        val likedCount: Int = 0
    )

    /** 一条评论 */
    data class NsComment(
        val id: String,
        val content: String,
        val likedCount: Int,
        val liked: Boolean,
        val timeMs: Long,
        val nickname: String,
        val avatar: String,
        val userId: String,
        val replyTo: String
    )

    /** 评论分页结果 */
    data class NsComments(
        val total: Int,
        val hot: List<NsComment>,
        val items: List<NsComment>,
        val hasMore: Boolean
    )

    /** 登录后的用户资料 */
    data class NsProfile(
        val userId: String,
        val nickname: String,
        val avatar: String
    )

    // ==================== 凭证登录（手机号 / 邮箱） ====================

    /** 凭证登录结果：成功带回资料，失败带回可读的错误文案 */
    sealed class LoginResult {
        data class Success(val profile: NsProfile) : LoginResult()
        data class Failure(val message: String) : LoginResult()
    }

    /**
     * 手机号 + 密码登录。走 binaryify `/login/cellphone`，密码由服务端 MD5。
     * 成功时把响应体里的 `cookie` 字段落库，并从 `profile` 取用户信息。
     */
    fun loginCellphone(phone: String, password: String): LoginResult {
        val cleanPhone = phone.trim().filter { it.isDigit() }
        if (cleanPhone.length < 6) return LoginResult.Failure("请输入正确的手机号")
        if (password.isBlank()) return LoginResult.Failure("请输入密码")
        val root = call(
            "/login/cellphone",
            mapOf("phone" to cleanPhone, "password" to password, "timestamp" to now()),
            needCookie = false
        ) ?: return LoginResult.Failure("网络错误，请稍后重试")
        return parseLoginResponse(root)
    }

    /**
     * 手机号 + 验证码登录。先 [sendCaptcha] 下发短信，再用此接口登录。
     * 未注册的手机号会自动注册并登录。
     */
    fun loginCellphoneCaptcha(phone: String, captcha: String): LoginResult {
        val cleanPhone = phone.trim().filter { it.isDigit() }
        if (cleanPhone.length < 6) return LoginResult.Failure("请输入正确的手机号")
        if (captcha.isBlank()) return LoginResult.Failure("请输入验证码")
        val root = call(
            "/login/cellphone",
            mapOf("phone" to cleanPhone, "captcha" to captcha, "timestamp" to now()),
            needCookie = false
        ) ?: return LoginResult.Failure("网络错误，请稍后重试")
        return parseLoginResponse(root)
    }

    // ==================== 验证码登录（weapi 直连，修复 binaryify 502） ====================

    /** 下发短信验证码（weapi 直连）。返回空串表示成功，非空为可读失败原因。 */
    fun sendCellphoneCaptchaWeapi(phone: String): String {
        val cleanPhone = phone.trim().filter { it.isDigit() }
        if (cleanPhone.length < 6) return "请输入正确的手机号"
        val (ok, msg) = NeteaseWeApi.sendCellphoneCaptcha(cleanPhone)
        return if (ok) "" else msg
    }

    /**
     * 手机号 + 验证码登录（weapi 直连）。
     * 修复 binaryify 转发验证码登录时丢失会话 cookie、误报「账号或密码错误(502)」的问题。
     */
    fun loginCellphoneCaptchaWeapi(phone: String, captcha: String): LoginResult {
        val cleanPhone = phone.trim().filter { it.isDigit() }
        if (cleanPhone.length < 6) return LoginResult.Failure("请输入正确的手机号")
        if (captcha.isBlank()) return LoginResult.Failure("请输入验证码")
        val (code, cookie, profileJson) = NeteaseWeApi.loginCellphoneByCaptcha(cleanPhone, captcha)
        if (code == 200 && cookie != null && cookie.isNotBlank()) {
            this.cookie = cookie
            NeteaseStore.saveCookie(cookie)
            val profile = if (profileJson != null && profileJson.optString("userId").isNotBlank()) {
                NsProfile(
                    userId = profileJson.optString("userId"),
                    nickname = profileJson.optString("nickname"),
                    avatar = profileJson.optString("avatarUrl")
                )
            } else {
                runCatching { loginStatus() }.getOrNull()
            }
            if (profile != null) return LoginResult.Success(profile)
            return LoginResult.Failure("登录成功但未获取到账号信息，请重试")
        }
        val msg = profileJson?.optString("__msg").orEmpty()
        val friendly = when (code) {
            501, 502 -> "账号或密码错误"
            503, -461 -> "验证码错误，请重新获取"
            2001 -> "手机号格式错误"
            509 -> "验证码错误次数过多，请稍后再试"
            else -> null
        }
        return LoginResult.Failure(friendly ?: msg.ifBlank { "登录失败（code=$code），请稍后重试" })
    }

    /** 邮箱 + 密码登录。走 binaryify `/login`（路径就是 `/login`，不带 `/cellphone`）。 */
    fun loginEmail(email: String, password: String): LoginResult {
        val cleanEmail = email.trim()
        if (!cleanEmail.contains("@")) return LoginResult.Failure("请输入正确的邮箱")
        if (password.isBlank()) return LoginResult.Failure("请输入密码")
        val root = call(
            "/login",
            mapOf("email" to cleanEmail, "password" to password, "timestamp" to now()),
            needCookie = false
        ) ?: return LoginResult.Failure("网络错误，请稍后重试")
        return parseLoginResponse(root)
    }

    /**
     * 下发短信验证码。
     *
     * 返回「可直接展示给用户」的结果：成功返回空字符串，失败返回具体原因
     * （例如"操作频繁，请稍候再试""手机号码不符合规范"），而不是笼统的"发送失败"。
     * binaryify 对不同失败场景会返回不同 code（400 号码不规范 / 406 频率限制等），
     * 且多数情况下 HTTP 状态码也非 200，因此这里统一解析响应体里的文案。
     */
    fun sendCaptcha(phone: String): String {
        val cleanPhone = phone.trim().filter { it.isDigit() }
        if (cleanPhone.length < 6) return "请输入正确的手机号"
        val root = call(
            "/captcha/sent",
            mapOf("phone" to cleanPhone, "timestamp" to now()),
            needCookie = false
        ) ?: return "网络错误，请稍后重试"
        val code = root.optInt("code", -1)
        // 成功：{"code":200,"data":true}
        if (code == 200 && root.optBoolean("data", true)) return ""
        val msg = root.optString("message").ifBlank { root.optString("msg") }
        if (msg.isNotBlank()) return msg
        return when (code) {
            400 -> "手机号码不符合规范"
            406 -> "操作频繁，请稍候再试"
            503 -> "验证码错误"
            else -> "发送失败，请稍后重试"
        }
    }

    /**
     * 统一解析登录响应：
     *  - code == 200 且有 profile → 存 cookie + 取资料 → Success
     *  - 否则从 message / msg 字段取错误文案 → Failure
     */
    private fun parseLoginResponse(root: JSONObject): LoginResult {
        val code = root.optInt("code", -1)
        if (code == 200) {
            // binaryify 把登录后的完整 cookie 放在响应体的 cookie 字段里
            val respCookie = root.optString("cookie")
            if (respCookie.isNotBlank()) {
                cookie = respCookie
                NeteaseStore.saveCookie(respCookie)
            }
            val profile = root.optJSONObject("profile")
            if (profile != null) {
                val uid = profile.optString("userId")
                if (uid.isNotEmpty() && uid != "0") {
                    return LoginResult.Success(
                        NsProfile(
                            userId = uid,
                            nickname = profile.optString("nickname"),
                            avatar = profile.optString("avatarUrl")
                        )
                    )
                }
            }
            // code 200 但没有 profile（极少见）：用 loginStatus 再拉一次兜底
            val p = runCatching { loginStatus() }.getOrNull()
            if (p != null) return LoginResult.Success(p)
            return LoginResult.Failure("登录成功但未获取到账号信息，请重试")
        }
        // 常见错误码：502 账号密码错误 / 501 账号或密码错误 / 503 验证码错误 /
        // 2001 手机号格式错误 / 509 密码错误次数过多 / 460/-460 风控 / -461 验证码错误
        val msg = root.optString("message").ifBlank { root.optString("msg") }
        if (msg.isNotBlank()) return LoginResult.Failure(msg)
        val friendly = when (code) {
            501, 502 -> "账号或密码错误"
            503, -461 -> "验证码错误"
            2001 -> "手机号格式错误"
            509 -> "密码错误次数过多，请稍后再试"
            460, -460 -> "登录过于频繁，请稍后再试"
            else -> null
        }
        return LoginResult.Failure(friendly ?: "登录失败（code=$code），请稍后重试")
    }

    // ==================== 二维码登录（走 weapi 直连，不依赖 binaryify） ====================

    /**
     * 1) 获取二维码 key（unikey）。
     * 直接走网易云 weapi 接口，App 完全掌控 NMTID 会话 cookie，
     * 避免 binaryify 自动生成不一致的设备标识导致扫码后立即过期。
     */
    fun qrKey(): String? = NeteaseWeApi.qrKey()

    /** 2) 二维码内容：网易云 App 扫码后打开的登录链接（本地生成，无需再请求） */
    fun qrContent(key: String): String = "https://music.163.com/login?codekey=$key"

    /**
     * 3) 轮询扫码状态。
     * 返回 (code, message)：800 过期 / 801 等待扫码 / 802 待确认 / 803 登录成功。
     * 803 时 weapi 响应头 Set-Cookie 含 MUSIC_U，直接落库并缓存。
     */
    fun qrCheck(key: String): Pair<Int, String?> {
        val (code, payload) = NeteaseWeApi.qrCheck(key)
        if (code == 803 && payload != null && payload.isNotBlank()) {
            // 803 时 payload 是含 MUSIC_U 的 cookie
            cookie = payload
            NeteaseStore.saveCookie(payload)
        }
        // 非 803 时 payload 是 message
        return code to payload
    }

    /** 登录后拉取账号资料（校验会话是否有效） */
    fun loginStatus(): NsProfile? {
        if (cookie.isBlank()) return null
        // 走 weapi 直连，cookie 格式与登录时一致，避免 binaryify 解析失败
        return NeteaseWeApi.loginStatus(cookie)
    }

    /** 退出登录（清空服务端会话，本地由 [NeteaseStore] 清理） */
    fun logout() {
        if (cookie.isBlank()) return
        runCatching { call("/logout") }
    }

    // ==================== 每日推荐 / 我的歌单 / 我喜欢 ====================

    /** 每日推荐（需要登录） */
    fun recommendSongs(): List<NsSong> {
        if (cookie.isBlank()) return emptyList()
        val root = call("/recommend/songs") ?: return emptyList()
        val arr = root.optJSONObject("data")?.optJSONArray("dailySongs") ?: return emptyList()
        return parseSongs(arr)
    }

    /** 当前账号的歌单（需要登录）→ (id, 名称, 封面) */
    fun userPlaylists(uid: String): List<Triple<String, String, String>> =
        when (val r = userPlaylistsResult(uid)) {
            is MinePlaylistsResult.Ok -> r.items
            else -> emptyList()
        }

    /**
     * 「我的歌单」拉取结果：把三种"看起来一样"的情况分开。
     *
     * 旧写法是 `call(...) ?: return emptyList()` + `optJSONArray("playlist") ?: return emptyList()`，
     * 于是 **cookie 过期**、**接口 500/断网**、**账号确实一个歌单都没有** 全塌成同一个空列表。
     * 上层 `if (mine.isNotEmpty()) 才覆盖界面` 一判，过期后就永久停在旧列表上，
     * 用户新建的歌单"在 App 里不存在"。必须能让 UI 说出"没拉到 / 登录过期，点此重试"。
     */
    sealed class MinePlaylistsResult {
        /** 服务器明确回了列表（items 为空 = 这个账号真的没有歌单，可以放心清空界面） */
        data class Ok(val items: List<Triple<String, String, String>>) : MinePlaylistsResult()
        /** 凭证过期 / 被拒（401/403）：要提示重新登录，不是"没有歌单" */
        object Unauthorized : MinePlaylistsResult()
        /** 请求失败或返回体不认识（断网、500、结构变了）：可重试 */
        object Failed : MinePlaylistsResult()
    }

    fun userPlaylistsResult(uid: String): MinePlaylistsResult {
        if (uid.isBlank()) return MinePlaylistsResult.Failed
        val root = call(
            "/user/playlist",
            mapOf("uid" to uid, "limit" to "100", "offset" to "0")
        ) ?: return MinePlaylistsResult.Failed
        // binaryify 未登录回 401，网关也可能直接给 403
        val code = root.optInt("code", -1)
        if (code == 401 || code == 403) return MinePlaylistsResult.Unauthorized
        // 空账号回的是 `playlist: []`（字段在、是空数组）→ Ok(empty)；
        // 字段整个不在（502 页面、结构变更）→ Failed，不能当成"用户没有歌单"。
        val list = root.optJSONArray("playlist") ?: return MinePlaylistsResult.Failed
        val out = ArrayList<Triple<String, String, String>>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            out.add(Triple(id, o.optString("name"), o.optString("coverImgUrl")))
        }
        return MinePlaylistsResult.Ok(out)
    }

    /**
     * 歌单详情 → 歌曲列表。
     * 公开歌单无需登录；私有歌单（如「我喜欢的音乐」）需要登录 Cookie。
     */
    fun playlistTracks(playlistId: String): List<NsSong> = playlistInfo(playlistId).second

    /**
     * 榜单/歌单的**前 [limit] 首**预览——一次请求出结果，专门给搜索页那排榜单卡用。
     *
     * 为什么单开一个：[playlistInfo] 为了把整个歌单列全，会拿 trackIds（最多 1000 个）
     * 再按 50 首一批去打 `/song/detail`，一个榜就是 20 个请求；搜索页一次要 5 个榜，
     * 旧写法等于 100 多个串行请求，用户看到的就是"榜单卡半天出不来"（R4 修的就是这里）。
     * 预览只要前几首，detail 响应里本来就近乎白送 `tracks` 字段，够用了。
     */
    fun playlistPreview(playlistId: String, limit: Int = 10): List<NsSong> {
        if (playlistId.isBlank()) return emptyList()
        // 6.9.4：先走自家服务器代理（一次请求、nginx 10 分钟缓存）。手机直连
        // music.163.com 已被网易大面积风控（code 20001 / -462），预览卡经常
        // 空着或转圈好几秒 —— 榜单卡一排 5 个全是直连，就是首页加载慢的主因。
        runCatching {
            val root = call(
                "/playlist/detail",
                mapOf("id" to playlistId, "n" to limit.coerceIn(1, 50).toString()),
                needCookie = false
            ) ?: return@runCatching
            val pl = root.optJSONObject("playlist") ?: return@runCatching
            val songs = parseSongs(pl.optJSONArray("tracks"))
            if (songs.isNotEmpty()) return songs.take(limit)
        }
        val root = directGet(
            "/v6/playlist/detail",
            // v6 的 n 参数就是"tracks 里给多少首"，别按 1000 拉
            mapOf("id" to playlistId, "n" to limit.coerceIn(1, 50).toString())
        ) ?: return emptyList()
        val playlist = root.optJSONObject("playlist") ?: return emptyList()
        val songs = parseSongs(playlist.optJSONArray("tracks"))
        if (songs.isNotEmpty()) return songs.take(limit)
        // 个别榜单的 detail 只回 trackIds：补一次请求，也只要前 limit 首
        val ids = playlist.optJSONArray("trackIds")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
            }
        }.orEmpty().take(limit)
        return songDetail(ids).take(limit)
    }

    /**
     * 歌单目录 5 分钟内存缓存：同一个歌单反复点开不再重打网络（6.9.3 提速）。
     * 只缓存「目录」本身；换源标记、可播性由调用方现场决定，不受缓存影响。
     */
    private val plInfoCacheLock = Any()
    private val plInfoCache = HashMap<String, Pair<Long, Pair<String, List<NsSong>>>>()
    private const val PL_INFO_CACHE_MS = 5 * 60_000L

    /**
     * 歌单详情 → (歌单名, 歌曲列表)。导入歌单时用来给本地歌单起名。
     * 公开歌单无需登录；私有歌单会返回空列表。
     *
     * 6.9.3 提速：6.9.2 改成"先服务器后直连"后，直连本来能拿到全量的用户也要
     * 白等服务器一个来回（2~5s），用户报「点开歌单太慢」。现在三通道**并行**：
     *  - 旧接口直连在当前线程先跑，**能拿全量就立即返回**（≈0.5s，最快路径）；
     *  - 同时后台线程打自家服务器 /api/playlist/parse（抗风控的保底）；
     *  - 当前线程接着跑 v6+trackIds 直连。
     *  直连不够全量时 join 服务器结果，最后所有候选谁给得多用谁。
     *  另加 5 分钟内存缓存：第二次点开同一个歌单是瞬时的。
     */
    fun playlistInfo(playlistId: String): Pair<String, List<NsSong>> {
        if (playlistId.isBlank()) return "" to emptyList()
        synchronized(plInfoCacheLock) {
            plInfoCache[playlistId]?.let { (at, v) ->
                if (System.currentTimeMillis() - at < PL_INFO_CACHE_MS) return v
            }
        }
        // ── 通道 0：自家服务器上的 NeteaseCloudMusicApi 代理（6.9.4 首选）──
        //
        // 用户报「歌单还是 10 首、打开特别卡」。上服务器实查发现：nginx 反代
        // /netease/ → 127.0.0.1:3002 一直活着且返回全量（热歌榜 trackCount=200、
        // tracks=200 一次带全），还叠了两层缓存（nginx 10 分钟 + 客户端 resCache）。
        // 而 6.9.3 的三通道全是"手机直连 music.163.com"，网易对手机 IP 风控后
        // 直连只能拿到一小截，还得白等服务器 /api/playlist/parse 逐首匹配好几秒
        // —— 又慢又缺。这条通道是项目本来的正路（"不直连网易云，统一走自有
        // 服务器"），一条请求、零逐首匹配、带缓存，歌单打开从"数秒"回到"亚秒"。
        //
        // 响应结构与 v6 直连一致（playlist.tracks / trackIds / trackCount），
        // 解析逻辑完全复用。私有/受限歌单（trackCount=0）自然落后面的通道。
        var proxyCand: Pair<String, List<NsSong>>? = null
        runCatching {
            val root = call(
                "/playlist/detail",
                mapOf("id" to playlistId, "n" to "1000"),
                needCookie = false
            ) ?: return@runCatching
            val pl = root.optJSONObject("playlist") ?: return@runCatching
            val name = pl.optString("name")
            val tracks = parseSongs(pl.optJSONArray("tracks"))
            if (name.isBlank() || tracks.isEmpty()) return@runCatching
            val total = pl.optInt("trackCount", 0)
            val result = name to tracks
            if (total <= 0 || tracks.size >= total) {
                putPlInfoCache(playlistId, result); return result
            }
            // 代理也只给了一部分（个别受限歌单）：先存为候选，继续走下面的通道补全
            proxyCand = result
        }
        // ── 通道 A：旧接口直连（最快；被风控时返回 code 20001 的残缺响应，跳过）──
        var legacy: Pair<String, List<NsSong>>? = null
        runCatching {
            val root = directGet(
                "/playlist/detail",
                mapOf("id" to playlistId, "n" to "1000")
            ) ?: return@runCatching
            val pl = root.optJSONObject("result") ?: root.optJSONObject("playlist") ?: return@runCatching
            val name = pl.optString("name")
            val tracks = parseSongs(pl.optJSONArray("tracks"))
            if (name.isBlank() || tracks.isEmpty()) return@runCatching
            val total = pl.optInt("trackCount", 0)
            val result = name to tracks
            if (tracks.size >= total) { putPlInfoCache(playlistId, result); return result }
            // total<=0（风控响应常常连 trackCount 都没有）且拿到的不止 10 首：
            // 比风控兜底那一小截多，可信，直接用。
            if (total <= 0 && tracks.size > 10) { putPlInfoCache(playlistId, result); return result }
            legacy = result
        }
        // ── 通道 B：自家服务器，后台线程并行跑（抗网易风控的保底，稍慢）──
        val srvHolder = arrayOfNulls<PlaylistServerResult>(1)
        val srvThread = Thread({
            srvHolder[0] = runCatching { playlistInfoFromServer(playlistId) }.getOrNull()
        }, "pl-info-server-v694")
        srvThread.isDaemon = true
        srvThread.start()
        // ── 通道 C：v6 直连 + trackIds 批量详情（当前线程，与服务器并行）──
        val v6Result = runCatching { playlistInfoV6Direct(playlistId) }.getOrNull()
        srvThread.join(9000)
        // ── 汇总：谁给得多用谁 ──
        val candidates = ArrayList<Pair<String, List<NsSong>>>(4)
        proxyCand?.let { candidates.add(it) }
        (srvHolder[0] as? PlaylistServerResult.Ok)?.takeIf { it.tracks.isNotEmpty() }?.let {
            candidates.add(it.name.ifBlank { "网易云歌单" } to it.tracks)
        }
        v6Result?.let { candidates.add(it) }
        legacy?.let { candidates.add(it) }
        val best = candidates.maxByOrNull { it.second.size } ?: ("" to emptyList())
        if (best.second.isNotEmpty()) putPlInfoCache(playlistId, best)
        return best
    }

    private fun putPlInfoCache(id: String, value: Pair<String, List<NsSong>>) {
        synchronized(plInfoCacheLock) {
            if (plInfoCache.size > 64) plInfoCache.clear()
            plInfoCache[id] = System.currentTimeMillis() to value
        }
    }

    /** v6 直连取歌单：trackIds 批量补详情；trackIds 拿不到时重试一次旧接口兜底 */
    private fun playlistInfoV6Direct(playlistId: String): Pair<String, List<NsSong>>? {
        val root = directGet(
            "/v6/playlist/detail",
            mapOf("id" to playlistId, "n" to "1000")
        ) ?: return null
        val playlist = root.optJSONObject("playlist") ?: return null
        val name = playlist.optString("name")
        // v6 的 tracks 通常只给前 10 首，完整顺序在 trackIds 里；
        // 按 trackIds 再批量取详情，才能把整个歌单都列出来。
        val ids = playlist.optJSONArray("trackIds")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
            }
        }.orEmpty().take(1000)
        val songs = if (ids.isNotEmpty()) {
            songDetail(ids)
        } else {
            // v6 的 tracks 通常只给前 10 首；trackIds 又拿不到时（旧接口瞬时风控、
            // v6 响应不全），这里以前直接拿 10 首收工 —— 详情页偶发"整个歌单只剩
            // 十首"就是撞的这条路。现在先按原条件重试一次旧接口（风控多半是瞬时的），
            // 重试拿到的比 v6 多就用重试结果；两次都不行才认命用 v6 给的那几首。
            val v6Tracks = parseSongs(playlist.optJSONArray("tracks"))
            val retry = runCatching {
                directGet("/playlist/detail", mapOf("id" to playlistId, "n" to "1000"))
                    ?.let { root -> root.optJSONObject("result") ?: root.optJSONObject("playlist") }
                    ?.optJSONArray("tracks")
                    ?.let { parseSongs(it) }
            }.getOrNull().orEmpty()
            if (retry.size > v6Tracks.size) retry else v6Tracks
        }
        if (songs.isEmpty()) return null
        return name to songs
    }

    /** 服务端解析歌单的结果：成功 / 明确失败 / 服务器不可达（可退回旧逻辑） */
    sealed class PlaylistServerResult {
        /**
         * platform：netease / qq / qishui；total 是原歌单曲目数，
         * matched 是成功匹配到网易云的数量，unmatched = total - matched。
         * 网易云歌单三者相等；QQ / 汽水要经过名字匹配，可能有对不上的。
         */
        data class Ok(
            val name: String,
            val tracks: List<NsSong>,
            val platform: String = "netease",
            val total: Int = tracks.size,
            val matched: Int = tracks.size,
            val unmatched: Int = (total - matched).coerceAtLeast(0),
            /**
             * 服务端明确回报"这几首没匹配上"时的清单（歌名 to 歌手）。
             *
             * 老服务器不发 `data.missing` 时它是空表，界面只会显示" N 首未匹配"，
             * 不会崩也不会显示错东西 —— 这样 App 可以先于服务端升级把展示位备好。
             */
            val missing: List<Pair<String, String>> = emptyList(),
            /**
             * 6.9.6：这批歌自带平台 id（`qq:<songmid>` / `kg:<hash>`），
             * 导入时**没有**再拿去网易云逐首搜。
             *
             * 决定两件事：一是文案该说"没匹配上"还是"超出上限截断"，
             * 二是不能拿 matched/total 当"能播比例"——真正的可播性由
             * 四层取源现场决定。老服务器不发这个字段时为 false，走原文案。
             */
            val direct: Boolean = false,
            /**
             * 6.9.7：源站自己只肯给一部分。目前只有酷狗的 gcid 歌单会命中 ——
             * 酷狗网页端（m.kugou.com/songlist/gcid_xxx/）固定只渲染前 10 首，
             * 全量要走 App 签名接口，密钥不在客户端。此时 [tracks] 只有 10 首，
             * [total] 才是真实总数（可能几百），界面必须如实说明，不能装作导全了。
             */
            val limited: Boolean = false,
            /**
             * 6.9.7：服务端把候选歌单按时间预算「做到哪算哪」之后，还剩多少首
             * 压根没送去匹配。它和 unmatched（搜了但没搜到）是两回事：
             * 前者再导一次大概率能补上（已匹配的命中 6 小时缓存，很快），
             * 所以文案不能混着说。
             */
            val skipped: Int = 0,
            /**
             * 6.9.8：limited 的具体原因，决定提示文案怎么说。
             *  `gcid`     —— 这个酷狗链接本身只开放前 10 首
             *  `searchMiss` —— 想靠歌单名反查完整歌单，但没搜到
             * 其它值一律当 `gcid` 处理。
             */
            val limitedReason: String = "",
            /** 反查命中/原歌单的作者昵称，用于"帮你找到了「xxx」的完整歌单" */
            val ownerName: String = "",
            /* ===== 6.9.8 分片导入字段 =====
             * 汽水这类必须逐首搜的歌单，500 首一次跑不完也等不起。
             * 服务端按 offset/limit 切片，done 是本片已处理到的下标，
             * hasMore 为 true 时客户端用 nextOffset 继续要下一片。 */
            val offset: Int = 0,
            val done: Int = tracks.size,
            val hasMore: Boolean = false,
            val nextOffset: Int = tracks.size,
            /* ===== 6.4 可播性预判 =====
             * playable    ：预计能直接播（网易云免费曲 / 非会员低音质）
             * needSource  ：预计要换源（VIP 曲 / 需购专辑）
             * 都是「本片」口径，分片导入时由调用方按片累加。
             * null 表示服务端没法预判（酷狗这类要走反搜的平台），
             * 界面显示「播放时才知道」，不要瞎报一个数字糊人。 */
            val playable: Int? = null,
            val needSource: Int? = null,
            /** 服务端明确标记「这一类平台的可播性无法预判」 */
            val predictUnknown: Boolean = false,
            /** 被去重丢掉的重复项，不计入 unmatched，也不吓用户 */
            val dupCount: Int = 0,
            /** 具体哪几首需要换源（网易云 id）。供歌单「只看可播」过滤用。 */
            val needSourceIds: List<String> = emptyList()
        ) : PlaylistServerResult()
        data class Fail(val message: String) : PlaylistServerResult()
        object Unavailable : PlaylistServerResult()
    }

    /**
     * 把用户粘贴的原文交给自有服务器解析。
     *
     * 服务端负责短链跳转、分享文案清洗（中文 / 全角数字 / 百分号编码）和
     * 网易云请求，比手机直连稳定；返回的歌单名、封面、歌曲信息一次带齐。
     * 服务器不可达时返回 [PlaylistServerResult.Unavailable]，调用方仍可
     * 走原来的手机端直连逻辑，不会因为服务器故障完全无法导入。
     */
    fun playlistInfoFromServer(
        input: String,
        offset: Int = 0,
        limit: Int = 300
    ): PlaylistServerResult {
        if (input.isBlank()) return PlaylistServerResult.Fail("先粘贴歌单链接、分享文案或 ID")
        val body = JSONObject().put("input", input.take(4096))
        // 6.9.8：分片参数。offset=0 时不传，跟老服务器完全兼容 ——
        // 老服务器不认识这两个字段，行为等于"一次给完"。
        if (offset > 0) body.put("offset", offset)
        if (limit != 300) body.put("limit", limit)
        // QQ / 汽水要逐首去网易云搜索匹配，几十秒是正常耗时。
        // R9 从 60s 提到 150s：大歌单在服务端本来就跑得过 60s，客户端先超时会被判成
        // "服务器不可达"，然后掉进直连兜底 —— 用户看到的是"导入失败"或者一个错的歌单。
        val root = ServerApi.request(
            "/api/playlist/parse", body, timeoutMs = 150000
        ) ?: return PlaylistServerResult.Unavailable
        if (root.optInt("code", -1) != 0) {
            return PlaylistServerResult.Fail(root.optString("msg", "歌单解析失败"))
        }
        val data = root.optJSONObject("data") ?: return PlaylistServerResult.Fail("歌单解析失败")
        val name = data.optString("name")
        val platform = data.optString("platform").ifBlank { "netease" }
        val total = data.optInt("total", -1)
        val matched = data.optInt("matched", -1)
        val arr = data.optJSONArray("tracks") ?: JSONArray()
        val tracks = ArrayList<NsSong>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            tracks.add(
                NsSong(
                    id = id,
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    cover = o.optString("cover"),
                    durationMs = o.optLong("duration")
                )
            )
        }
        val realTotal = if (total >= 0) total else tracks.size
        val realMatched = if (matched >= 0) matched else tracks.size
        // 未匹配清单：服务端 data.missing = [{title, artist}, …]。
        // 现在不发这个字段的服务器 → 空表，App 侧只显示数量，不影响导入。
        val missArr = data.optJSONArray("missing") ?: JSONArray()
        val missing = ArrayList<Pair<String, String>>(missArr.length())
        for (i in 0 until missArr.length()) {
            val o = missArr.optJSONObject(i) ?: continue
            val t = o.optString("title")
            if (t.isBlank()) continue
            missing.add(t to o.optString("artist"))
        }
        return PlaylistServerResult.Ok(
            name = name,
            tracks = tracks,
            platform = platform,
            total = realTotal,
            matched = realMatched,
            unmatched = data.optInt("unmatched", (realTotal - realMatched).coerceAtLeast(0)),
            missing = missing,
            direct = data.optBoolean("direct", false),
            limited = data.optBoolean("limited", false),
            skipped = data.optInt("skipped", 0),
            limitedReason = data.optString("limitedReason", ""),
            ownerName = data.optString("ownerName", ""),
            // 服务端回 null 时不写这个 key，或显式写 null，两种都当「未知」
            playable = if (data.has("playable") && !data.isNull("playable")) {
                data.optInt("playable", -1).takeIf { it >= 0 }
            } else null,
            needSource = if (data.has("needSource") && !data.isNull("needSource")) {
                data.optInt("needSource", -1).takeIf { it >= 0 }
            } else null,
            predictUnknown = data.optBoolean("predictUnknown", false),
            dupCount = data.optInt("dupCount", 0),
            needSourceIds = run {
                val arr = data.optJSONArray("needSourceIds") ?: JSONArray()
                ArrayList<String>(arr.length()).apply {
                    for (i in 0 until arr.length()) {
                        val v = arr.optString(i)
                        if (v.isNotBlank()) add(v)
                    }
                }
            },
            offset = data.optInt("offset", 0),
            done = data.optInt("done", tracks.size),
            hasMore = data.optBoolean("hasMore", false),
            nextOffset = data.optInt("nextOffset", tracks.size)
        )
    }
    fun likedSongIds(uid: String): List<String> {
        if (cookie.isBlank()) return emptyList()
        val root = call(
            "/likelist",
            mapOf("uid" to uid, "timestamp" to now())
        ) ?: return emptyList()
        val arr = root.optJSONArray("ids") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optLong(it).takeIf { v -> v > 0 }?.toString() }
    }

    /** 批量取歌曲详情（用于把红心 / 歌单 id 列表变成歌曲） */
    fun songDetail(ids: List<String>): List<NsSong> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<NsSong>(ids.size)
        // 官方接口一次别塞太多，50 首一片，和封面补全保持一致。
        val chunks = ids.distinct().chunked(50)
        // 以前 20 片是纯串行：1000 首的歌单要 20 个来回，用户看到的就是"点开歌单半天不出"。
        // 改成 4 路并发、按下标回填，顺序不乱，总耗时≈ceil(片数/4) 个来回。
        val filled = arrayOfNulls<List<NsSong>>(chunks.size)
        val queue = chunks.indices.toMutableList()
        val workers = List(minOf(4, queue.size)) {
            Thread {
                while (true) {
                    val i = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                    val root = runCatching {
                        directGet(
                            "/song/detail/",
                            mapOf("ids" to "[" + chunks[i].joinToString(",") + "]")
                        )
                    }.getOrNull() ?: continue
                    filled[i] = parseSongs(root.optJSONArray("songs"))
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join(20000) }
        for (part in filled) out.addAll(part.orEmpty())
        return out
    }

    /**
     * 推荐歌单（公开个性化推荐，无需登录）。
     *
     * 返回带上 `playCount`：首页卡片要照参考实现那样在封面下方写「▷ 12.3万」，
     * 以前用 Triple 只取三个字段，播放数在解析这一步就被丢掉了。
     * `limit` 可以要更多——首页「划到底续拉」就是拿更大的 limit 再要一批，
     * 服务端不认的话返回的还是同一批，按 id 去重后自然就没有新增，前端会停下。
     */
    fun recommendPlaylists(limit: Int = 30): List<NeteaseRecPlaylist> {
        val root = call("/personalized", mapOf("limit" to limit.toString(), "timestamp" to now()), needCookie = false)
            ?: return emptyList()
        val arr = root.optJSONArray("result") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            NeteaseRecPlaylist(
                id = id,
                name = o.optString("name"),
                cover = picUrl(o.optString("picUrl")),
                playCount = o.optLong("playCount")
            )
        }
    }

    /** 红心 / 取消红心（需要登录） */
    fun likeSong(songId: String, like: Boolean): Boolean {
        if (cookie.isBlank()) return false
        val root = call("/like", mapOf("id" to songId, "like" to like.toString(), "timestamp" to now()))
            ?: return false
        return root.optInt("code", -1) == 200
    }

    // ==================== 排行榜（无需登录） ====================

    /** 网易云所有官方榜单 → (id, 名称, 封面)。榜单本质是歌单，曲目复用 [playlistTracks]。 */
    /**
     * 上面那批兜底榜单的 id 集合（6.1 加）。
     *
     * 首页「推荐歌单」要把榜单挑出去：光看名字不够稳 —— /personalized 返回的榜单项
     * 偶尔会带前缀或改名，而**榜单 id 是固定的**，所以名字判漏了还能按 id 再兜一层。
     */
    fun defaultToplistIds(): Set<String> = defaultToplists().map { it.first }.toSet()

    /**
     * 首页那两排榜单的**优先顺序**（R12 加）。
     *
     * 用户点名要「热歌榜 / 古典榜 / 新歌榜」这类官方榜，而 /toplist 线上返回的顺序
     * 会把合作榜（荣耀、小天才）和细分风格榜混在前面，所以首页不按接口顺序取前 6，
     * 而是按这个表把大家真正会听的榜顶到前面；表里没列到的榜接着排在后面，
     * 一个都不会丢，只是排位靠后（「更多」→ 排行榜页仍然是完整列表）。
     */
    fun defaultToplistOrder(): List<String> = listOf(
        "3778678",     // 热歌榜
        "3779629",     // 新歌榜
        "19723756",    // 飙升榜
        "71384707",    // 网易云古典榜
        "2884035",     // 原创榜
        "991319590",   // 网易云中文说唱榜
        "1978921795"   // 网易云电音榜
    )

    /**
     * 冷启动兜底榜单：首帧就把这 10 个榜单铺出来，网络回来后再用线上数据覆盖。
     *
     * 不依赖本地缓存，所以新装/清数据后的第一次打开也不会停在「正在加载榜单…」。
     * id 与网易云官方榜单一致，点进去仍走正常榜单详情接口。
     */
    fun defaultToplists(): List<Triple<String, String, String>> = listOf(
        Triple("19723756", "飙升榜", "https://p3.music.126.net/rIi7Qzy2i2Y_1QD7cd0MYA==/109951170048506929.jpg"),
        Triple("3779629", "新歌榜", "https://p4.music.126.net/5guhqPBTcIrrhLBotgaT6w==/109951170048511751.jpg"),
        Triple("2884035", "原创榜", "https://p3.music.126.net/BaP9nrocNTL3gGThysv4eQ==/109951170091896587.jpg"),
        Triple("3778678", "热歌榜", "https://p4.music.126.net/0SUEG8yDACfx0Bw2MYFv4Q==/109951170048519512.jpg"),
        // 6.9.5：参考汽水音乐的分类粒度扩充（全部 2026-11-09 实测可拉全量）：
        Triple("6723173524", "网络热歌榜", "https://p4.music.126.net/_kSxOPqQ5J5etC5DKTFwNA==/109951170048519530.jpg"),
        Triple("18013635672", "红果热门BGM", "https://p1.music.126.net/j2a6HL6hMKwamDQmIVgCwg==/109951162933404679.jpg"),
        Triple("13372522766", "潮流风向榜", "https://p4.music.126.net/dIKA5e7jCncz2Br1Toxgaw==/109951170621574552.jpg"),
        Triple("5059642708", "国风榜", "https://p4.music.126.net/kTJC5OBhg8I477X_ZmXyDQ==/109951168539740982.jpg"),
        Triple("5059661515", "民谣榜", "https://p4.music.126.net/Xe9qLTAqtBAWX_hPgFHMyw==/109951170048510929.jpg"),
        Triple("5059633707", "摇滚榜", "https://p3.music.126.net/LjkX2hktgFD1NXc3W6w0sA==/109951170048522513.jpg"),
        Triple("71384707", "网易云古典榜", "https://p3.music.126.net/urByD_AmfBDBrs7fA9-O8A==/109951167976973225.jpg"),
        Triple("1978921795", "网易云电音榜", "https://p3.music.126.net/hXGObvXfsGtFjFvRhOYAkA==/109951170091888741.jpg"),
        Triple("991319590", "网易云中文说唱榜", "https://p4.music.126.net/GgHbgDfGXHpE2YTchU7IvA==/109951171510498108.jpg"),
        Triple("18512097939", "荣耀音乐热歌榜", "https://p4.music.126.net/KrEjItbh8PkIlaRMKP5lAw==/109951174149506005.jpg"),
        Triple("18478568150", "小天才热歌榜", "https://p3.music.126.net/GmmRZwOmh-DFwtF3TVJJrw==/109951174096842449.jpg"),
        Triple("18176153161", "实时分享榜", "https://p4.music.126.net/b2-_Xw-6DwDXWIn7RNuCYQ==/109951173652479140.jpg")
    )

    fun toplists(): List<Triple<String, String, String>> {
        val root = call("/toplist/detail", mapOf("timestamp" to now()), needCookie = false)
            ?: return emptyList()
        val arr = root.optJSONArray("list") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            Triple(id, o.optString("name"), o.optString("coverImgUrl"))
        }
    }

    /**
     * 榜单详情卡（6.3.9）：比 [toplists] 多带「上榜歌曲数 / 更新频率 / 前三首」。
     *
     * 首页那张榜单卡上只写榜名（用户明确说"不要标格外的字"），这三项是给
     * 卡片的**视觉层次**用的：前三首写成极小的试听行，让卡看起来"有内容"，
     * 而不是一张纯图。接口一次请求全拿到，不额外打网络。
     */
    fun toplistCards(): List<NsToplist> {
        val root = call("/toplist/detail", mapOf("timestamp" to now()), needCookie = false)
            ?: return emptyList()
        val arr = root.optJSONArray("list") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val name = o.optString("name")
            if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
            val previews = mutableListOf<String>()
            o.optJSONArray("tracks")?.let { ts ->
                for (k in 0 until minOf(3, ts.length())) {
                    val t = ts.optJSONObject(k) ?: continue
                    val first = t.optString("first")
                    val second = t.optString("second")
                    if (first.isNotBlank()) {
                        previews.add(if (second.isBlank()) first else "$first - $second")
                    }
                }
            }
            NsToplist(
                id = id,
                name = name,
                cover = o.optString("coverImgUrl"),
                updateFrequency = o.optString("updateFrequency"),
                trackCount = o.optInt("trackCount", 0),
                playCount = o.optLong("playCount", 0L),
                previews = previews
            )
        }
    }

    /** 榜单卡数据（见 [toplistCards]） */
    data class NsToplist(
        val id: String,
        val name: String,
        val cover: String,
        /** 网易云给的更新说明，如「每天更新」「每周五更新」 */
        val updateFrequency: String = "",
        val trackCount: Int = 0,
        val playCount: Long = 0L,
        /** 前三首「歌名 - 歌手」，给卡片做背景纹理用 */
        val previews: List<String> = emptyList()
    )

    // ==================== 首页栏目（免登录，6.3.8） ====================
    //
    // 首页要"每天不重样"又"不能要登录"，所以全部挑公开接口：
    //   /top/artists           热门歌手
    //   /album/new             新碟上架
    //   /personalized/newsong  推荐新歌（新闻歌，任何人可见）
    //   /toplist/detail + /v6/playlist/detail  各类榜单（风格栏目的数据源）
    // 这几个都在线上实测过 200 且有内容；`/simi/song`、`/style/recommend` 已废弃不用。

    /** 热门歌手（免登录）。[offset] 用于翻页，首页只要第一页。 */
    fun topArtists(offset: Int = 0, limit: Int = 12): List<NsArtist> {
        val root = call(
            "/top/artists",
            mapOf("offset" to offset.toString(), "limit" to limit.toString(), "timestamp" to now()),
            needCookie = false
        ) ?: return emptyList()
        val arr = root.optJSONArray("artists") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val name = o.optString("name")
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            // 6.3.9：接口在不同镜像上有 fansCount / musicSize / albumSize 几种热度字段，
            // 全取第一个非零的——首页要用它做"人多优先"的排序依据。
            val score = listOf(
                "fansCount", "fansCnt", "followedUsers", "musicSize", "songSize", "albumSize"
            ).asSequence().map { o.optLong(it, 0L) }.firstOrNull { it > 0L } ?: 0L
            NsArtist(id, name, o.optString("picUrl"), score)
        }
    }

    /**
     * 新碟上架（免登录）。
     * [area]：ALL / ZH / EA / KR / JP
     */
    fun newAlbums(area: String = "ALL", limit: Int = 12): List<NsAlbum> {
        val root = call(
            "/album/new",
            mapOf("area" to area, "limit" to limit.toString(), "timestamp" to now()),
            needCookie = false
        ) ?: return emptyList()
        val arr = root.optJSONArray("albums") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val name = o.optString("name")
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            val artist = o.optJSONObject("artist")?.optString("name").orEmpty()
            NsAlbum(id, name, artist, o.optString("picUrl"))
        }
    }

    /**
     * 推荐新歌（免登录）。
     *
     * 这个接口不吃 Cookie，任何人打开都有一批当天的新歌——首页拿它做
     * 「每日新歌」栏目，再按日期轮换取 8 首，就得到"每天不一样"的效果。
     */
    fun newSongRecommend(limit: Int = 20): List<NsSong> {
        val root = call(
            "/personalized/newsong",
            mapOf("limit" to limit.toString(), "timestamp" to now()),
            needCookie = false
        ) ?: return emptyList()
        val arr = root.optJSONArray("result") ?: return emptyList()
        // 这个接口的外层是「推荐条目」，真正的歌在 `song` 字段里；
        // 少数版本直接就是歌对象。先摊平成标准数组，再交给 [parseSongs] 复用字段解析，
        // 免得这里再写一份 ar/al/dt 的取值逻辑，以后字段变了要改两处。
        val flat = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val song = o.optJSONObject("song") ?: o
            if (song.optString("id").isNotBlank()) flat.put(song)
        }
        return parseSongs(flat)
    }

    // ==================== 相似推荐（无需登录） ====================

    /** 基于某首歌的相似歌曲推荐 */
    fun simiSongs(songId: String): List<NsSong> {
        if (songId.isBlank()) return emptyList()
        val root = call("/simi/song", mapOf("id" to songId), needCookie = false) ?: return emptyList()
        return parseSongs(root.optJSONArray("songs"))
    }

    // ==================== 搜索 ====================

    /** 一页搜索结果：songs + 是否还有下一页 */
    data class NsSongPage(val songs: List<NsSong>, val hasMore: Boolean, val total: Int)

    /**
     * 官方云搜索 `/api/cloudsearch/pc`。
     *
     * 之前搜索走 `/api/search/get/web`，那个接口返回的 album 只有 `picId` 没有 `picUrl`，
     * 歌手 / 专辑 / 歌单的封面字段也经常缺失，于是列表大面积没封面；而且它对
     * 分页支持不稳定，翻到后面会直接返回空，表现为「搜完一开始有，继续翻就 0」。
     * 云搜索返回完整的 `al.picUrl` / `ar` / `dt` / `coverImgUrl`，并带 songCount，
     * 分页、封面、时长一次到位。失败时再回退旧接口。
     */
    private fun cloudSearch(keyword: String, type: Int, limit: Int, offset: Int): JSONObject? {
        if (keyword.isBlank()) return null
        val per = limit.coerceIn(1, 100)
        val off = offset.coerceAtLeast(0)
        // 每条分类在 result 里用的数组字段名不一样，判定「这次响应算不算有货」必须按分类判：
        // 只判 result 是否存在，会出现「歌手搜索返回了 songs 数组、artists 是空」也被当成成功，
        // 于是那一格永远显示 0 条却不再走第二条通道。
        val key = when (type) {
            100 -> "artists"
            10 -> "albums"
            1000 -> "playlists"
            else -> "songs"
        }
        // ---- 通道一：直连网易云官方 web 接口 ----
        // 带浏览器 UA + Referer 就能拿到数据，不依赖登录态，成功率高、延迟低，优先用它。
        val direct = directGet(
            "/cloudsearch/pc",
            mapOf(
                "s" to keyword, "type" to type.toString(),
                "limit" to per.toString(), "offset" to off.toString()
            )
        )
        if (hasRows(direct, key)) return direct
        // ---- 通道二：自有服务器上的 NeteaseCloudMusicApi ----
        //
        // 这一条是本轮（R13）补上的，修的是用户报的头号 bug：
        // 「搜『毛不易』，单曲那一格什么都没有，连音乐都没有」。
        // 根因：搜索四个分类全部只有直连这一条路（见上面 DIRECT_BASE 的注释，
        // 当初是因为线上 /jm/netease 反代 404 才改成直连的）。手机侧一旦直连不通
        // ——运营商 DNS 污染、EMUI 的网络管控、网易云对非常规网络的风控（返回 460/462
        // 或一个没有 result 的空壳）—— 四个分类就一起空掉，界面表现就是"搜索完全坏了"。
        // 而项目本来的设计是「不直连网易云，统一走自有服务器」（见 netease-app.js 头注释、
        // ServerApi 的说明），服务器侧已经验证可用：/netease/cloudsearch?keywords=&type=
        // 对 1/10/100/1000 四类都返回 200 且字段结构与官方一致（ar / al.picUrl / dt /
        // coverImgUrl / songCount 全在），所以两条通道能直接互换，不需要改解析代码。
        //
        // 参数名不一样：官方用 s=，binaryify 用 keywords=；这条差异必须在这里抹平，
        // 否则第二条通道永远拿不到结果（实测传 s= 给服务器会返回 {"code":400,"msg":"参数错误"}）。
        //
        // 顺序仍然是「直连优先」：手机到网易云通常是通的，直连少一跳更快；
        // 只有直连失败或返回空壳才落到服务器，等于给单点故障加了第二条命，不改变原有行为。
        val viaServer = call(
            "/cloudsearch",
            mapOf(
                "keywords" to keyword, "type" to type.toString(),
                "limit" to per.toString(), "offset" to off.toString()
            ),
            needCookie = false
        )
        if (hasRows(viaServer, key)) return viaServer
        // 两条通道都没有行：谁带回了结构就返回谁（好让上层按「真的没有结果」处理，
        // 而不是把 null 当成「接口挂了」再重试一遍旧接口）。
        return if (viaServer != null) viaServer else direct
    }

    /** 判断一份搜索响应里，该分类的数组是不是真的有数据。 */
    private fun hasRows(root: JSONObject?, resultKey: String): Boolean {
        val arr = root?.optJSONObject("result")?.optJSONArray(resultKey)
        return arr != null && arr.length() > 0
    }

    /** 网易云搜索（歌曲，第 1 页；保留旧调用方） */
    fun search(keyword: String, limit: Int = 30): List<NsSong> = searchPage(keyword, limit, 0).songs

    /** 网易云搜索（歌曲，可翻页） */
    fun searchPage(keyword: String, limit: Int = 30, offset: Int = 0): NsSongPage {
        if (keyword.isBlank()) return NsSongPage(emptyList(), false, 0)
        val per = limit.coerceIn(1, 100)
        val cloud = cloudSearch(keyword, 1, per, offset)
        val result = cloud?.optJSONObject("result")
        val arr = result?.optJSONArray("songs")
        // 注意这里判的是「解析出来有没有歌」，不是「有没有 songs 字段」：
        // 空数组也走兜底。以前只判 arr != null，于是云搜索返回 `songs: []`（网易云对
        // 某些关键词就是这么回的，配合上风控码）时，代码当成"成功但没结果"直接返回空页，
        // 旧接口连试都不试 —— 用户看到的「搜什么都是 0 首」有它一份。
        if (arr != null) {
            val total = result.optInt("songCount", 0)
            val songs = parseSongs(arr)
            if (songs.isNotEmpty()) {
                val hasMore = if (total > 0) offset + songs.size < total else songs.size >= per
                return NsSongPage(songs, hasMore, total)
            }
        }
        // 旧接口兜底（只支持第一页，且可能没有封面）
        if (offset > 0) return NsSongPage(emptyList(), false, 0)
        val root = legacySearchRoot(keyword, 1, per) ?: return NsSongPage(emptyList(), false, 0)
        val songs = parseSongs(root.optJSONObject("result")?.optJSONArray("songs"))
        return NsSongPage(songs, false, songs.size)
    }

    /**
     * 旧搜索接口 `/search/get/web`，同样两条通道：先直连官方，空壳再走服务器。
     *
     * 单独抽出来是因为四个分类（单曲/歌手/专辑/歌单）的兜底都要用它，
     * 以前每条路径各自写一次 directGet，加服务器通道就得改四处、漏一处就留下
     * "只有某个分类会空"这种最难查的 bug（本轮的搜索问题正是这一类）。
     */
    private fun legacySearchRoot(keyword: String, type: Int, limit: Int): JSONObject? {
        val params = mapOf(
            "s" to keyword, "type" to type.toString(),
            "limit" to limit.coerceIn(1, 100).toString(), "offset" to "0"
        )
        val key = when (type) {
            100 -> "artists"
            10 -> "albums"
            1000 -> "playlists"
            else -> "songs"
        }
        val direct = directGet("/search/get/web", params)
        if (hasRows(direct, key)) return direct
        val viaServer = call(
            "/search",
            mapOf(
                "keywords" to keyword, "type" to type.toString(),
                "limit" to params.getValue("limit"), "offset" to "0"
            ),
            needCookie = false
        )
        if (hasRows(viaServer, key)) return viaServer
        return if (viaServer != null) viaServer else direct
    }

    /**
     * 搜索歌曲（自动翻页）。歌手详情页用它把某位歌手的歌尽量找全：
     * 云搜索每页最多 100 条，传 offset 可以继续翻。
     */
    fun searchSongsPaged(keyword: String, pages: Int = 4, perPage: Int = 100): List<NsSong> {
        if (keyword.isBlank()) return emptyList()
        val out = LinkedHashMap<String, NsSong>()
        val per = perPage.coerceIn(1, 100)
        val pageCount = pages.coerceIn(1, 8)
        // 6.9.6：**翻页并行化**。旧实现 4 页串行，每页如果直连被风控挂住就要等
        // 一轮超时，歌手页"补全歌曲"阶段能干等几十秒。第 0 页先发（要靠它判断
        // 有没有下一页），第 1..N 页同时并发，谁先回来谁先合入。
        val first = searchPage(keyword, per, 0)
        for (song in first.songs) out.put(song.id, song)
        if (first.songs.isEmpty() || !first.hasMore || first.songs.size < per) {
            return out.values.toList()
        }
        if (pageCount > 1) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(pageCount - 1, 4))
            try {
                val futures = (1 until pageCount).map { page ->
                    pool.submit(java.util.concurrent.Callable { searchPage(keyword, per, page * per) })
                }
                for (f in futures) {
                    val pageData = runCatching { f.get(20, java.util.concurrent.TimeUnit.SECONDS) }
                        .getOrNull() ?: continue
                    for (song in pageData.songs) out.put(song.id, song)
                }
            } finally {
                pool.shutdownNow()
            }
        }
        return out.values.toList()
    }

    /** 搜索结果：歌手 */
    data class NsArtist(
        val id: String,
        val name: String,
        val pic: String,
        /** 网易云热度（粉丝数 / 歌曲数，接口有就给，没有就 0）——首页按这个排序取前几名 */
        val score: Long = 0L
    )

    /** 搜索结果：专辑 */
    data class NsAlbum(val id: String, val name: String, val artist: String, val pic: String)

    /** 搜索结果：歌单 */
    data class NsPlaylistSearch(val id: String, val name: String, val cover: String)

    /** 网易云搜索（歌手） */
    fun searchArtists(keyword: String, limit: Int = 30): List<NsArtist> {
        if (keyword.isBlank()) return emptyList()
        val cloud = cloudSearch(keyword, 100, limit, 0)
        val cloudArr = cloud?.optJSONObject("result")?.optJSONArray("artists")
        if (cloudArr != null) {
            return (0 until cloudArr.length()).mapNotNull { i ->
                val o = cloudArr.optJSONObject(i) ?: return@mapNotNull null
                NsArtist(
                    o.optString("id"),
                    o.optString("name"),
                    picUrl(o.optString("picUrl").ifBlank { o.optString("img1v1Url") })
                )
            }
        }
        // 兜底同样两条通道（直连 → 自有服务器），见 legacySearchRoot 的注释
        val root = legacySearchRoot(keyword, 100, limit) ?: return emptyList()
        val arr = root.optJSONObject("result")?.optJSONArray("artists") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            NsArtist(
                o.optString("id"),
                o.optString("name"),
                picUrl(o.optString("picUrl").ifBlank { o.optString("img1v1Url") })
            )
        }
    }

    /** 网易云搜索（专辑） */
    fun searchAlbums(keyword: String, limit: Int = 30): List<NsAlbum> {
        if (keyword.isBlank()) return emptyList()
        val cloud = cloudSearch(keyword, 10, limit, 0)
        val cloudArr = cloud?.optJSONObject("result")?.optJSONArray("albums")
        if (cloudArr != null) {
            return (0 until cloudArr.length()).mapNotNull { i ->
                val o = cloudArr.optJSONObject(i) ?: return@mapNotNull null
                val artist = o.optJSONArray("artists")?.let { a ->
                    (0 until a.length())
                        .mapNotNull { a.optJSONObject(it)?.optString("name") }
                        .filter { it.isNotBlank() }
                        .joinToString(",")
                }.orEmpty().ifBlank { o.optJSONObject("artist")?.optString("name").orEmpty() }
                NsAlbum(
                    o.optString("id"), o.optString("name"), artist,
                    picUrl(o.optString("picUrl").ifBlank { o.optString("blurPicUrl") })
                )
            }
        }
        val root = legacySearchRoot(keyword, 10, limit) ?: return emptyList()
        val arr = root.optJSONObject("result")?.optJSONArray("albums") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val artist = o.optJSONArray("artists")?.let { a ->
                (0 until a.length())
                    .mapNotNull { a.optJSONObject(it)?.optString("name") }
                    .filter { it.isNotBlank() }
                    .joinToString(",")
            }.orEmpty()
            NsAlbum(o.optString("id"), o.optString("name"), artist, picUrl(o.optString("picUrl")))
        }
    }

    /**
     * 网易云搜索（歌单）。
     *
     * [offset] 是 R9 新加的：之前两条通道都把 offset 写死成 0，所以不管 limit 给多大，
     * 结果永远只有第一页 —— 用户说的"歌单往左滑应该有非常多呀，怎么这么少"就截在这。
     * 接口本身支持翻页（单曲搜索一直在用 searchPage(keyword, limit, offset)），只是这里没接。
     */
    fun searchPlaylists(keyword: String, limit: Int = 30, offset: Int = 0): List<NsPlaylistSearch> {
        if (keyword.isBlank()) return emptyList()
        val cloud = cloudSearch(keyword, 1000, limit, offset)
        val cloudArr = cloud?.optJSONObject("result")?.optJSONArray("playlists")
        if (cloudArr != null) {
            return (0 until cloudArr.length()).mapNotNull { i ->
                val o = cloudArr.optJSONObject(i) ?: return@mapNotNull null
                NsPlaylistSearch(o.optString("id"), o.optString("name"), picUrl(o.optString("coverImgUrl")))
            }
        }
        // 注意：旧接口这条兜底在服务器通道上只取第一页（binaryify 的 /search 支持 offset，
        // 这里保持与 legacySearchRoot 一致传 0）；翻页主力仍是云搜索那条通道，不受影响。
        val root = legacySearchRoot(keyword, 1000, limit) ?: return emptyList()
        val arr = root.optJSONObject("result")?.optJSONArray("playlists") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            NsPlaylistSearch(o.optString("id"), o.optString("name"), picUrl(o.optString("coverImgUrl")))
        }
    }

    // ==================== 歌手 / 专辑详情 ====================

    /** 歌手热门歌曲（官方接口一次最多 50 首） */
    fun artistTopSongs(artistId: String): List<NsSong> {
        if (artistId.isBlank()) return emptyList()
        // 6.9.6：歌手页打开"老慢老慢"的根治 —— 热门 50 首改走**服务器代理优先**。
        // 手机直连 /artist/top/song 时好时坏，坏的时候不是失败而是**挂着**（直连
        // 超时 12s/20s，用户就干等十几秒页面才出内容）。代理走服务器 IP + nginx
        // 缓存，实测亚秒级；直连降级为兜底。
        val root = call(
            "/artist/top/song",
            mapOf("id" to artistId),
            needCookie = false
        ) ?: directGet("/artist/top/song", mapOf("id" to artistId))
            ?: return emptyList()
        return parseSongs(root.optJSONArray("songs"))
    }

    /** 专辑歌曲 */
    fun albumSongs(albumId: String): List<NsSong> {
        if (albumId.isBlank()) return emptyList()
        // /api/album/{id} 会被风控成 -462，/api/v1/album/{id} 正常。
        val root = directGet("/v1/album/$albumId") ?: return emptyList()
        return parseSongs(root.optJSONArray("songs"))
    }

    /**
     * 某歌手的专辑列表（6.9.5 新增）：歌手页"专辑"一栏用。
     *
     * 服务器代理 `/artist/album` 优先（返回 hotAlbums，字段 id/name/picUrl），
     * 直连 `/api/artist/album` 作兜底（同字段结构）。
     * 失败返回空表 —— 歌手页那一栏直接不渲染，不打扰用户。
     */
    fun artistAlbums(artistId: String, limit: Int = 30): List<NsAlbum> {
        if (artistId.isBlank()) return emptyList()
        val params = mapOf("id" to artistId, "limit" to limit.coerceIn(1, 100).toString(), "offset" to "0")
        val root = call("/artist/album", params, needCookie = false)
            ?: directGet("/artist/album", params)
            ?: return emptyList()
        val arr = root.optJSONArray("hotAlbums") ?: return emptyList()
        val out = ArrayList<NsAlbum>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val name = o.optString("name")
            if (id.isBlank() || name.isBlank()) continue
            out.add(NsAlbum(id = id, name = name, artist = "", pic = picUrl(o.optString("picUrl"))))
        }
        return out
    }

    // ==================== 评论 ====================

    /**
     * 读取某首歌的评论（无需登录）。page 从 1 开始，每页 [pageSize] 条。
     * 第 1 页会额外带「精彩评论」。
     */
    fun comments(songId: String, page: Int = 1, pageSize: Int = 20): NsComments {
        val offset = ((page - 1) * pageSize).coerceAtLeast(0)
        val root = call(
            "/comment/music",
            mapOf(
                "id" to songId,
                "limit" to pageSize.toString(),
                "offset" to offset.toString(),
                "timestamp" to now()
            )
        ) ?: return NsComments(0, emptyList(), emptyList(), false)
        val total = root.optInt("total", 0)
        return NsComments(
            total = total,
            hot = if (page == 1) parseComments(root.optJSONArray("hotComments")) else emptyList(),
            items = parseComments(root.optJSONArray("comments")),
            hasMore = root.optBoolean("more", false) || offset + pageSize < total
        )
    }

    private fun parseComments(arr: JSONArray?): List<NsComment> {
        if (arr == null) return emptyList()
        val out = ArrayList<NsComment>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val user = c.optJSONObject("user")
            val reply = c.optJSONArray("beReplied")?.optJSONObject(0)
            out.add(
                NsComment(
                    id = c.optString("commentId"),
                    content = c.optString("content"),
                    likedCount = c.optInt("likedCount", 0),
                    liked = c.optBoolean("liked", false),
                    timeMs = c.optLong("time", 0L),
                    nickname = user?.optString("nickname").orEmpty(),
                    avatar = user?.optString("avatarUrl").orEmpty(),
                    userId = user?.optString("userId", "").orEmpty(),
                    replyTo = reply?.optJSONObject("user")?.optString("nickname").orEmpty()
                )
            )
        }
        return out
    }

    /** 发表评论（需要登录）。成功返回 true，未登录返回 false。 */
    fun postComment(songId: String, content: String): Boolean {
        if (cookie.isBlank()) return false
        val root = call(
            "/comment",
            mapOf(
                "t" to "1",
                "type" to "0",
                "id" to songId,
                "content" to content,
                "timestamp" to now()
            )
        ) ?: return false
        return root.optInt("code", -1) == 200
    }

    /** 点赞 / 取消点赞评论（需要登录） */
    fun likeComment(songId: String, commentId: String, like: Boolean): Boolean {
        if (cookie.isBlank()) return false
        val root = call(
            "/comment/like",
            mapOf(
                "id" to songId,
                "cid" to commentId,
                "t" to if (like) "1" else "0",
                "type" to "0",
                "timestamp" to now()
            )
        ) ?: return false
        return root.optInt("code", -1) == 200
    }

    // ==================== 底层请求 ====================

    private fun now(): String = System.currentTimeMillis().toString()

    /**
     * 统一 GET 请求：把参数拼到 query 上；需要登录的接口自动带上当前 Cookie。
     * 返回可解析的 JSON，失败一律返回 null（由各调用方安全降级）。
     *
     * @param setCookieOut 若不为 null，会把响应头 Set-Cookie 合并后回调出去
     *   （用于二维码登录：`/login/qr/key` 下发的 NMTID 必须在 `/login/qr/check` 时传回）。
     */
    /**
     * 列表类响应的内存缓存：同一份榜单 / 歌单 10 分钟内再次打开直接命中，
     * 不再等网络（这是「点完榜单要转圈很久」的直接修法）。
     * 登录、点赞、评论等有副作用的接口一律不缓存。
     */
    private val CACHEABLE = listOf(
        "/toplist/detail", "/playlist/detail", "/personalized", "/user/playlist",
        "/album", "/artist/top/song", "/simi/song", "/song/detail", "/recommend/songs",
        // "/cloudsearch" 必须单独列：命中判断用的是 startsWith，它并不以 "/search" 开头，
        // 少了这一条，走服务器那条搜索通道就每次都打网络（同一次搜索来回切分类会连打三次）。
        "/search", "/cloudsearch", "/top/song", "/playlist/catlist", "/personalized/newsong"
    )
    private val resCache = object : LinkedHashMap<String, Pair<Long, JSONObject>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, JSONObject>>?) =
            size > 160
    }
    private val cacheLock = Any()

    /**
     * 「账号名下的列表」这条 TTL 单独压到 30 秒（其余公开列表仍 10 分钟）。
     *
     * 这就是"网易云新建歌单在 App 里不出现、必须重新登录才出现"的第一现场：
     * 缓存 key 把 `cookie` 一起拼进去了（见 [call]），所以同一份 cookie 下
     * `/user/playlist` 会整整 10 分钟命中旧响应；而重新登录会换一个新 cookie
     * → key 变了 → 强制走网络 → 新歌单"突然出现"。用户看到的正是这条因果。
     * 账号数据变得快、又是要用户当场看到结果的，10 分钟太长。
     */
    private fun ttlFor(path: String): Long = when {
        path.startsWith("/user/playlist") || path.startsWith("/recommend/songs") -> 30_000L
        path.startsWith("/search") || path.startsWith("/cloudsearch") ||
            path.startsWith("/song/detail") -> 2 * 60_000L
        else -> 10 * 60_000L
    }

    /**
     * 丢掉某一类响应的内存缓存（前缀匹配）。
     *
     * 「显式刷新」必须走这里而不是只把 TTL 调短：只调 TTL 仍会在 30 秒窗口里
     * 拿到旧列表，用户刚在网易云建完歌单就点刷新，看到的还是"没有这首"。
     * key 里带 cookie，所以按前缀清，不能按整串 key 清。
     */
    fun clearResponseCache(pathPrefix: String) {
        synchronized(cacheLock) {
            val it = resCache.keys.iterator()
            while (it.hasNext()) {
                if (it.next().startsWith(pathPrefix)) it.remove()
            }
        }
    }

    /**
     * 直连网易云官方 web 接口（只用于公开只读数据：搜索 / 歌手 / 专辑 / 歌单）。
     * 不依赖服务器代理，也不依赖登录 Cookie。
     */
    private fun directGet(
        path: String,
        params: Map<String, String> = emptyMap()
    ): JSONObject? = runCatching {
        val query = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val url = if (query.isEmpty()) "$DIRECT_BASE$path" else "$DIRECT_BASE$path?$query"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_MS
        conn.readTimeout = READ_MS
        conn.setRequestProperty("User-Agent", DIRECT_UA)
        conn.setRequestProperty("Referer", "https://music.163.com/")
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        val code = conn.responseCode
        val stream = try {
            conn.inputStream
        } catch (_: Exception) {
            runCatching { conn.errorStream }.getOrNull()
        } ?: return@runCatching null
        val text = stream.bufferedReader().use { it.readText() }
        if (text.isBlank()) return@runCatching null
        if (code !in 200..299) return@runCatching null
        JSONObject(text)
    }.getOrNull()

    /**
     * 6.9.8：服务器代理原始 JSON（公开包装）。
     * 歌词链路用它拿 `/lyric/new` 的逐字轨（yrc）——手机直连网易经常拿不到逐字，
     * 服务器 IP + nginx 缓存这条路稳定得多。
     */
    fun proxyJson(path: String, params: Map<String, String> = emptyMap()): JSONObject? =
        call(path, params, needCookie = false)

    private fun call(
        path: String,
        params: Map<String, String> = emptyMap(),
        needCookie: Boolean = true,
        setCookieOut: ((String) -> Unit)? = null
    ): JSONObject? = runCatching {
        val all = LinkedHashMap<String, String>(params)
        if (needCookie && cookie.isNotBlank()) all["cookie"] = cookie
        val query = all.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val url = if (query.isEmpty()) "$BASE$path" else "$BASE$path?$query"

        // 命中缓存直接返回：列表页二次打开 = 0 等待。
        // 缓存 key 必须剔掉 `timestamp`：多数公开接口都带 now() 做防缓存参数，
        // 原样拼进 key 会让每次请求的 key 都不一样，10 分钟缓存形同虚设
        // （推荐歌单 /personalized 就是这个情况，每次进首页都白打一次网络）。
        val cacheKey = if (setCookieOut == null && CACHEABLE.any { path.startsWith(it) && it.isNotEmpty() }) {
            path + "?" + all.entries.filter { it.key != "timestamp" }
                .sortedBy { it.key }
                .joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        } else null
        if (cacheKey != null) {
            // 搜索类结果变得快（改词、榜单波动、版权状态变化），给 2 分钟；公开列表 10 分钟；
            // 账号名下的列表（我的歌单 / 每日推荐）另按 [ttlFor] 的 30 秒 —— 见那里的成因说明。
            val ttl = ttlFor(path)
            synchronized(cacheLock) {
                resCache[cacheKey]?.let { (t, obj) ->
                    if (System.currentTimeMillis() - t < ttl) return obj
                }
            }
        }

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_MS
        conn.readTimeout = READ_MS
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json")

        // 必须先触发请求（responseCode），响应头才可用；在此之前 headerFields 为空。
        val respCode = conn.responseCode
        // 读取响应体：2xx 用 inputStream，非 2xx 用 errorStream。
        // Android 的 HttpURLConnection 对 4xx/5xx 的 inputStream 会抛 FileNotFoundException，
        // 而 errorStream 在部分机型/特定响应下可能为 null，所以按顺序尝试。
        val stream = try {
            conn.inputStream
        } catch (_: Exception) {
            runCatching { conn.errorStream }.getOrNull()
        }
        val text = runCatching { stream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()

        // 捕获 Set-Cookie 响应头（二维码登录会话绑定必需）。
        // headerFields 的 key 大小写不固定，遍历查找 "set-cookie" 更稳妥。
        if (setCookieOut != null) {
            val setCookies = conn.headerFields?.entries
                ?.firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
                ?.value
            if (setCookies != null && setCookies.isNotEmpty()) {
                // 多个 Set-Cookie 合并为 "k1=v1; k2=v2"，去掉过期时间等属性
                val merged = setCookies.joinToString("; ") { raw ->
                    raw.split(';').firstOrNull()?.trim().orEmpty()
                }.trimEnd(';', ' ')
                if (merged.isNotBlank()) setCookieOut(merged)
            }
        }

        // 优先解析真实响应体；解析不出时，若非 2xx 就合成一个带 HTTP 状态码的 JSON，
        // 让上层能给出"验证码错误/账号密码错误"这类具体提示，而不是笼统的"网络错误"。
        if (text.isNotBlank()) {
            val root = runCatching { JSONObject(text) }.getOrNull()
            if (root != null) {
                if (cacheKey != null) {
                    synchronized(cacheLock) { resCache[cacheKey] = System.currentTimeMillis() to root }
                }
                return@runCatching root
            }
        }
        if (respCode !in 200..299) {
            runCatching { JSONObject().put("code", respCode) }.getOrNull()
        } else null
    }.getOrNull()

    /**
     * 网易云歌曲 JSON → [NsSong]，兼容两套字段命名：
     *  - 旧版（搜索）：`artists` / `album` / `duration`
     *  - 新版（歌单/推荐）：`ar` / `al` / `dt`
     */
    private fun parseSongs(arr: JSONArray?): List<NsSong> {
        if (arr == null) return emptyList()
        val out = ArrayList<NsSong>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optString("id")
            if (id.isEmpty() || id == "0") continue
            val ar = s.optJSONArray("ar") ?: s.optJSONArray("artists")
            val artist = ar?.let { a ->
                (0 until a.length())
                    .mapNotNull { a.optJSONObject(it)?.optString("name") }
                    .filter { it.isNotBlank() }
                    .joinToString(",")
            }.orEmpty()
            val albumObj = s.optJSONObject("al") ?: s.optJSONObject("album")
            val cover = picUrl(
                albumObj?.optString("picUrl").orEmpty()
                    .ifBlank { albumObj?.optString("blurPicUrl").orEmpty() }
            )
            val dur = s.optLong("dt").let { if (it > 0) it else s.optLong("duration") }
            out.add(
                NsSong(
                    id = id,
                    title = s.optString("name"),
                    artist = artist,
                    album = albumObj?.optString("name").orEmpty(),
                    cover = cover,
                    durationMs = dur,
                    likedCount = s.optInt("likedCount", 0)
                )
            )
        }
        return out
    }

    /** 封面地址归一化：协议相对 / http 图床统一升级到 https，避免列表里出现「有 URL 但加载失败」。 */
    private fun picUrl(raw: String?): String {
        val u = raw?.trim().orEmpty()
        return when {
            u.isEmpty() -> ""
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://") -> "https://" + u.removePrefix("http://")
            u.startsWith("/") -> "https://music.163.com$u"
            else -> u
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/**
 * 公开推荐歌单的一条（`/personalized`）。
 *
 * 单独建类型而不是复用 `player` 包里的 `NeteasePlaylistItem`：data 层不该反过来依赖
 * 业务层的数据类，而且榜单 / 我的歌单那些条目没有 playCount 概念。
 */
data class NeteaseRecPlaylist(
    val id: String,
    val name: String,
    val cover: String,
    val playCount: Long
)
