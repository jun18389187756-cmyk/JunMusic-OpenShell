package com.jun.musiccloud.data

import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 weapi 加密直连：App 直接调用 music.163.com 的 weapi 接口，
 * 不依赖第三方 Node 服务，完全掌控 cookie 会话，避免 binaryify 每次请求
 * 自动生成不一致的 _ntes_nuid / _ntes_nnid 导致二维码登录扫码后立即过期。
 *
 * 仅用于二维码登录（key + check），其它接口仍走自有服务器 binaryify。
 *
 * 关键修复：完全对齐 binaryify 4.32.0 的请求行为——
 * 1. 携带完整的 PC 客户端 cookie（_ntes_nuid / _ntes_nnid / os / appver 等），
 *    只带 NMTID 会被风控拦在 802，确认后不返回 803。
 * 2. 每次响应的 Set-Cookie 都合并回会话，保持会话连续。
 * 3. weapi body 带 csrf_token（空串即可），与 binaryify 一致。
 */
object NeteaseWeApi {

    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725" +
        "152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312" +
        "ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424" +
        "d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val EXPONENT = "010001"
    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val HEX = "0123456789abcdef"
    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"

    /** 二维码登录过程中的会话 cookie（NMTID 等），整个流程复用 */
    @Volatile var sessionCookie: String = ""
        private set

    /** 持久化的设备标识 cookie（_ntes_nuid / _ntes_nnid / WNMCID），每次启动生成一次 */
    @Volatile private var deviceCookie: String = ""

    private fun randomSecretKey(): String {
        val rnd = java.security.SecureRandom()
        return (1..16).map { BASE62[rnd.nextInt(BASE62.length)] }.joinToString("")
    }

    private fun randomHex(len: Int): String {
        val rnd = java.security.SecureRandom()
        return (1..len).map { HEX[rnd.nextInt(HEX.length)] }.joinToString("")
    }

    /** 生成与 binaryify processCookieObject 一致的设备 cookie 串 */
    private fun ensureDeviceCookie(): String {
        if (deviceCookie.isNotBlank()) return deviceCookie
        val nuid = randomHex(32)
        val nnid = "$nuid,${System.currentTimeMillis()}"
        val chars = "abcdefghijklmnopqrstuvwxyz"
        val rnd = java.security.SecureRandom()
        val prefix = (1..6).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        val wnmcid = "$prefix.${System.currentTimeMillis()}.01.0"
        deviceCookie = listOf(
            "__remember_me=true",
            "ntes_kaola_ad=1",
            "_ntes_nuid=$nuid",
            "_ntes_nnid=$nnid",
            "WNMCID=$wnmcid",
            "WEVNSM=1.0.0",
            "osver=Microsoft-Windows-10-Professional-build-19045-64bit",
            "os=pc",
            "channel=netease",
            "appver=3.1.17.204416",
        ).joinToString("; ")
        return deviceCookie
    }

    /** 合并 cookie：新 cookie 覆盖旧值，返回合并后的 cookie 串 */
    private fun mergeCookies(old: String, new: String): String {
        if (new.isBlank()) return old
        if (old.isBlank()) return new
        val map = LinkedHashMap<String, String>()
        for (part in old.split(';')) {
            val kv = part.trim()
            if (kv.isEmpty()) continue
            val eq = kv.indexOf('=')
            if (eq > 0) map[kv.substring(0, eq).trim()] = kv.substring(eq + 1).trim()
        }
        for (part in new.split(';')) {
            val kv = part.trim()
            if (kv.isEmpty()) continue
            val eq = kv.indexOf('=')
            if (eq > 0) map[kv.substring(0, eq).trim()] = kv.substring(eq + 1).trim()
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun aesEncrypt(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(IV.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    private fun rsaEncrypt(text: String): String {
        val reversed = text.reversed()
        val modulus = BigInteger(MODULUS, 16)
        val exponent = BigInteger(EXPONENT, 16)
        val keySpec = RSAPublicKeySpec(modulus, exponent)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(reversed.toByteArray(Charsets.UTF_8))
        return BigInteger(1, encrypted).toString(16).padStart(256, '0')
    }

    /** weapi 加密：返回 (params, encSecKey) */
    private fun encrypt(params: Map<String, Any>): Pair<String, String> {
        val json = JSONObject(params).toString()
        val secretKey = randomSecretKey()
        val encText = aesEncrypt(json, PRESET_KEY)
        val encParams = aesEncrypt(encText, secretKey)
        val encSecKey = rsaEncrypt(secretKey)
        return encParams to encSecKey
    }

    /** 从响应头提取所有 Set-Cookie 的 key=value 部分，合并为 cookie 字符串。
     *  遍历全部 Set-Cookie 头（不止第一个），避免漏掉 JSESSIONID / MUSIC_U 等。 */
    private fun extractAllCookies(conn: HttpURLConnection): String {
        val fields = conn.headerFields?.entries
            ?.filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            ?: return ""
        val parts = fields.flatMap { it.value.orEmpty() }.mapNotNull { raw ->
            // 只取第一个 name=value，去掉 Domain/Path/Expires 等属性
            raw.split(';').firstOrNull()?.trim()?.takeIf { it.isNotBlank() && it.contains('=') }
        }
        return parts.joinToString("; ")
    }

    /** 统一的 weapi POST：加密 + 请求头 + body，返回 (响应 JSON 文本, 响应 Set-Cookie 合并串) */
    private fun weapiPost(endpoint: String, params: Map<String, Any>, withSession: Boolean): Pair<String, String> {
        val (p, encSecKey) = encrypt(params)
        val body = "params=${URLEncoder.encode(p, "UTF-8")}&encSecKey=$encSecKey"
        val conn = URL("https://music.163.com$endpoint").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Referer", "https://music.163.com/")
        conn.setRequestProperty("Origin", "https://music.163.com")
        if (withSession) conn.setRequestProperty("Cookie", fullCookie())
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        val text = runCatching { conn.inputStream.bufferedReader().use { r -> r.readText() } }
            .getOrElse { runCatching { conn.errorStream?.bufferedReader()?.use { r -> r.readText() }.orEmpty() }.getOrDefault("") }
        val respCookies = extractAllCookies(conn)
        if (respCookies.isNotBlank()) sessionCookie = mergeCookies(sessionCookie, respCookies)
        return text to respCookies
    }

    /** 组装请求头：设备 cookie + 会话 cookie 合并 */
    private fun fullCookie(): String {
        return mergeCookies(ensureDeviceCookie(), sessionCookie)
    }

    /**
     * 取 csrf_token：优先复用会话里已有的 __csrf cookie；
     * 没有则先 GET 首页拿一个（与 binaryify 行为一致：无 cookie 时 csrf_token 为空串）。
     * 网易云的状态变更类 weapi（验证码下发 / 登录）要求 csrf_token 与会话 __csrf 匹配。
     */
    private fun fetchCsrf(): String {
        val fromSession = sessionCookie.split(";").firstOrNull {
            it.trim().startsWith("__csrf=")
        }?.substringAfter("=")?.trim()
        if (!fromSession.isNullOrBlank()) return fromSession
        runCatching {
            val conn = URL("https://music.163.com/").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.connect()
            val c = extractAllCookies(conn)
            if (c.isNotBlank()) sessionCookie = mergeCookies(sessionCookie, c)
        }
        return sessionCookie.split(";").firstOrNull {
            it.trim().startsWith("__csrf=")
        }?.substringAfter("=")?.trim().orEmpty()
    }

    /** 1) 获取二维码 unikey，同时捕获 NMTID 会话 cookie */
    fun qrKey(): String? = runCatching {
        // 每次新流程重置会话 cookie（但保留设备 cookie）
        sessionCookie = ""
        val (params, encSecKey) = encrypt(mapOf("type" to 3, "csrf_token" to ""))
        val body = "params=${URLEncoder.encode(params, "UTF-8")}&encSecKey=$encSecKey"

        val conn = URL("https://music.163.com/weapi/login/qrcode/unikey").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Referer", "https://music.163.com/")
        conn.setRequestProperty("Origin", "https://music.163.com")
        conn.setRequestProperty("Cookie", fullCookie())
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }

        // 捕获所有 Set-Cookie（NMTID 等），合并到会话
        val respCookies = extractAllCookies(conn)
        if (respCookies.isNotBlank()) {
            sessionCookie = mergeCookies(sessionCookie, respCookies)
        }

        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        json.optString("unikey").takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 2) 轮询扫码状态，返回 (code, cookie)。803 时 cookie 含 MUSIC_U */
    fun qrCheck(key: String): Pair<Int, String?> = runCatching {
        val (params, encSecKey) = encrypt(mapOf("key" to key, "type" to 3, "csrf_token" to ""))
        val body = "params=${URLEncoder.encode(params, "UTF-8")}&encSecKey=$encSecKey"

        val conn = URL("https://music.163.com/weapi/login/qrcode/client/login").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Referer", "https://music.163.com/")
        conn.setRequestProperty("Origin", "https://music.163.com")
        conn.setRequestProperty("Cookie", fullCookie())
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }

        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        val code = json.optInt("code", 801)

        // 每次都合并响应头 Set-Cookie，保持会话连续（802 阶段也可能下发新 cookie）
        val respCookies = extractAllCookies(conn)
        if (respCookies.isNotBlank()) {
            sessionCookie = mergeCookies(sessionCookie, respCookies)
        }

        if (code == 803) {
            // 803 时优先从响应体 cookie 字段取（含 MUSIC_U），响应头 Set-Cookie 作为补充
            val bodyCookie = json.optString("cookie")
            val merged = listOf(bodyCookie, respCookies)
                .filter { it.isNotBlank() }
                .joinToString("; ")
            code to merged.ifBlank { null }
        } else {
            code to json.optString("message").takeIf { it.isNotBlank() }
        }
    }.getOrDefault(801 to null)

    /**
     * 获取当前登录用户信息（weapi 直连，不走 binaryify，避免 cookie 格式不匹配）。
     * 需要传入登录后的完整 cookie（含 MUSIC_U）。
     */
    fun loginStatus(cookie: String): NeteaseApi.NsProfile? = runCatching {
        val (params, encSecKey) = encrypt(mapOf("csrf_token" to ""))
        val body = "params=${URLEncoder.encode(params, "UTF-8")}&encSecKey=$encSecKey"

        val conn = URL("https://music.163.com/weapi/w/nuser/account/get").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Referer", "https://music.163.com/")
        conn.setRequestProperty("Origin", "https://music.163.com")
        // 登录后请求：设备 cookie + 登录 cookie（MUSIC_U）合并
        conn.setRequestProperty("Cookie", mergeCookies(ensureDeviceCookie(), cookie))
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }

        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        // 兼容多种返回结构
        val profile = json.optJSONObject("profile")
            ?: json.optJSONObject("data")?.optJSONObject("profile")
            ?: return@runCatching null
        val uid = profile.optString("userId")
        if (uid.isEmpty() || uid == "0") return@runCatching null
        NeteaseApi.NsProfile(
            userId = uid,
            nickname = profile.optString("nickname"),
            avatar = profile.optString("avatarUrl")
        )
    }.getOrNull()

    /**
     * 手机验证码登录（weapi 直连，绕过 binaryify 的验证码会话 cookie 问题）。
     *
     * binaryify 的 /login/cellphone 验证码分支依赖 send 阶段下发的 JSESSIONID 会话，
     * 但它在转发给网易云的两次请求之间没能稳定复用该 cookie，导致网易云拿不到验证码会话，
     * 直接返回 502「账号或密码错误」。这里走与二维码登录相同的 weapi 直连，
     * App 自己持有 sessionCookie 并在登录时回传，验证码即可被正确校验。
     *
     * @return Triple(code, cookie, profile)：
     *   - code == 200 时 cookie 为含 MUSIC_U 的完整登录 cookie，profile 为用户资料；
     *   - 否则 cookie 为 null，profile 为 null，调用方用 code 映射友好文案。
     */
    fun loginCellphoneByCaptcha(phone: String, captcha: String): Triple<Int, String?, org.json.JSONObject?> = runCatching {
        val csrf = fetchCsrf()
        val (text, _) = weapiPost(
            "/weapi/w/login/cellphone",
            mapOf(
                "type" to "1",
                "https" to "true",
                "phone" to phone,
                "countrycode" to "86",
                "captcha" to captcha,
                "remember" to "true",
                "csrf_token" to csrf
            ),
            withSession = true
        )
        val json = org.json.JSONObject(text)
        val code = json.optInt("code", -1)
        if (code == 200) {
            val cookie = json.optString("cookie").takeIf { it.isNotBlank() }
            val profile = json.optJSONObject("profile")
                ?: json.optJSONObject("data")?.optJSONObject("profile")
            Triple(code, cookie, profile)
        } else {
            val msg = json.optString("message").ifBlank { json.optString("msg") }
            Triple(code, null, org.json.JSONObject().put("__msg", msg))
        }
    }.getOrDefault(Triple(-1, null, null))

    /**
     * 下发手机短信验证码（weapi 直连）。
     * @return Pair(是否成功, 失败时的可读原因)；成功时 reason 为空串。
     */
    fun sendCellphoneCaptcha(phone: String): Pair<Boolean, String> = runCatching {
        // 每次新流程重置会话（保留设备 cookie），并捕获 send 阶段下发的 JSESSIONID
        sessionCookie = ""
        val csrf = fetchCsrf()
        val (text, _) = weapiPost(
            "/weapi/sms/captcha/sent",
            mapOf(
                "cellphone" to phone,
                "ctcode" to "86",
                "secrete" to "music_middleuser_pclogin",
                "csrf_token" to csrf
            ),
            withSession = true
        )
        val json = org.json.JSONObject(text)
        val code = json.optInt("code", -1)
        if (code == 200) {
            true to ""
        } else {
            val msg = json.optString("message").ifBlank { json.optString("msg") }
            false to if (msg.isNotBlank()) msg else "发送失败，请稍后重试"
        }
    }.getOrDefault(false to "网络错误，请稍后重试")
}
