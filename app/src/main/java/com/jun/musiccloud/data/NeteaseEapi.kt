package com.jun.musiccloud.data

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 eapi 直连客户端（5.1 新增，只为「高音质档位」服务）。
 *
 * 与现有 [NeteaseApi]（走自有服务器 binaryify）、[NeteaseWeApi]（weapi 扫码登录）
 * 完全独立：**不替换、不改动任何现有调用链路**，只新增一条可选路径。
 * 任何失败都返回 null，由调用方降级，绝不抛异常打断播放。
 *
 * 协议（已在真机外网实测：请求签名正确，服务端返回结构化 JSON）：
 * ```
 * message = "nobody" + uri + "use" + json + "md5forencrypt"
 * digest  = md5(message)
 * payload = uri + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest
 * params  = upperHex( AES-ECB( key = e82ckenh8dichen8, payload ) )
 * POST https://interface.music.163.com + uri.replace("/api/", "/eapi/")   form: params=...
 * ```
 * 请求体带 `e_r = false`，因此响应是**明文 JSON**，不需要再解密。
 *
 * @see com.jun.musiccloud.playback.AudioProxyServer 真正的调用方是它（起播时才解析地址）
 */
object NeteaseEapi {

    private const val TAG = "NeteaseEapi"
    private const val BASE = "https://interface.music.163.com"
    private const val SALT = "36cd479b6b5"
    private const val AES_KEY = "e82ckenh8dichen8"
    private const val UA = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)"
    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    /** 一次解析出来的真实音源信息 */
    data class SongUrl(
        val url: String,
        val level: String?,
        val format: String?,
        val bitrate: Int,
        val sizeBytes: Long
    )

    /** /api/song/lyric/v1 的一束歌词（6.9.5：逐字轨 yrc 只有 eapi 通道下得全） */
    data class LyricV1(
        val lrc: String,
        val tlyric: String?,
        val romalrc: String?,
        val yrc: String?,
        val ytlrc: String?
    )

    /**
     * `/api/song/lyric/v1`（eapi 加密通道）：拉正文 + 翻译 + 音译 + **逐字轨**。
     *
     * 6.9.5：逐字歌词（yrc）网易只从加密通道下发 —— 手机直连 web 版 v1、桌面 curl、
     * 甚至服务器上的 NeteaseCloudMusicApi 代理（旧版 1.0.0）全部拿不到 yrc，
     * 结果逐字点亮长期退化成"上一句到下一句的平均值"（用户实测反馈）。
     * 本客户端的 eapi 签名已在真机外网实测可用（songUrl 同一条路），
     * 2026-11-09 沙箱复现：1901371647 → yrc:True（老歌无逐字轨的正常 songs 返回 False）。
     */
    fun lyricV1(songId: String, cookie: String): LyricV1? {
        if (songId.isBlank()) return null
        val payload = JSONObject()
            .put("id", songId)
            .put("cp", false)
            .put("tv", 0)
            .put("lv", 0)
            .put("rv", 0)
            .put("kv", 0)
            .put("yv", 1)
            .put("ytv", 0)
            .put("yrv", 0)
        val root = post("/api/song/lyric/v1", payload, cookie) ?: return null
        val lrc = root.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
            ?: return null
        return LyricV1(
            lrc = lrc,
            tlyric = root.optJSONObject("tlyric")?.optString("lyric")?.takeIf { it.isNotBlank() },
            romalrc = root.optJSONObject("romalrc")?.optString("lyric")?.takeIf { it.isNotBlank() },
            yrc = root.optJSONObject("yrc")?.optString("lyric")?.takeIf { it.isNotBlank() },
            ytlrc = root.optJSONObject("ytlrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * `/api/song/enhance/player/url/v1`：按档位取播放地址。
     *
     * @param cookie 已登录网易云的 Cookie（含 MUSIC_U）；空串表示匿名请求
     * @return 解析成功返回地址，任何失败（未登录 / 无版权 / 网络 / 字段缺失）返回 null
     */
    fun songUrl(songId: String, level: String, cookie: String): SongUrl? {
        if (songId.isBlank() || level.isBlank()) return null
        val data = JSONObject()
            .put("ids", "[$songId]")
            .put("level", level)
            .put("encodeType", "flac")
        if (level == "sky") data.put("immerseType", "c51")
        val root = post("/api/song/enhance/player/url/v1", data, cookie) ?: return null
        val arr = root.optJSONArray("data") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id").let { it == songId || it == "$songId" } || o.optLong("id").toString() == songId) {
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                // https 化：源站给的是 http 直链，Android 9+ 明文请求会被拦
                val secure = url.replaceFirst("^http://".toRegex(), "https://")
                if (!secure.startsWith("https://")) continue
                return SongUrl(
                    url = secure,
                    level = o.optString("level").takeIf { it.isNotBlank() },
                    format = o.optString("type", o.optString("encodeType")).takeIf { it.isNotBlank() },
                    bitrate = o.optInt("br", o.optInt("bitrate", 0)),
                    sizeBytes = o.optLong("size", 0L)
                )
            }
        }
        return null
    }

    /** 发一个 eapi 请求，返回明文 JSON；任何异常吞掉返回 null */
    private fun post(uri: String, payload: JSONObject, cookie: String): JSONObject? {
        return try {
            val body = payload
                .put("header", header(cookie))
                .put("e_r", false)
                .toString()
            val digest = md5("nobody${uri}use${body}md5forencrypt")
            val plain = "$uri-$SALT-$body-$SALT-$digest"
            val params = encryptHex(plain)
            val path = uri.replace("/api/", "/eapi/")
            val conn = URL(BASE + path).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.doOutput = true
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Accept", "application/json")
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            conn.outputStream.use {
                it.write(("params=" + URLEncoder.encode(params, "UTF-8")).toByteArray())
            }
            val stream =
                if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) {
                Log.w(TAG, "eapi $uri empty (${conn.responseCode})")
                return null
            }
            val text = stream.bufferedReader().use { it.readText() }
            if (text.isBlank()) return null
            JSONObject(text).takeIf { it.optInt("code", 0) == 200 }
        } catch (e: Exception) {
            Log.w(TAG, "eapi $uri failed: ${e.message}")
            null
        }
    }

    /** 客户端头：匿名与登录两套字段，登录态下补齐 cookie 里的设备信息 */
    private fun header(cookie: String): JSONObject {
        val now = System.currentTimeMillis()
        val h = JSONObject()
            .put("os", "ios")
            .put("appver", "9.0.90")
            .put("osver", "18.0")
            .put("buildver", (System.currentTimeMillis() / 1000).toString())
            .put("channel", "distribution")
            .put("requestId", "${now}_0000")
            .put("__csrf", csrfFromCookie(cookie))
            .put("deviceID", deviceId)
            .put("clientVersion", "9.0.90")
            .put("appType", "iphone")
            .put("osMode", 0)
        val musicU = valueFromCookie(cookie, "MUSIC_U")
        if (!musicU.isNullOrBlank()) {
            h.put("MUSIC_U", musicU)
            h.put("os", valueFromCookie(cookie, "os") ?: "ios")
            valueFromCookie(cookie, "appver")?.let { h.put("appver", it) }
        }
        return h
    }

    /**
     * AES-ECB/PKCS5 加密后转大写 hex。
     *
     * JDK 的 `ECB/PKCS5Padding` 与网易云要求的 16 字节补位（每字节填补位长度）
     * 在块不满时完全等价；恰好整块时 JDK 会多补一整块 PKCS#5——服务端按 PKCS#7
     * 语义去填充，因此两边一致，不必手写补位。
     */
    private fun encryptHex(text: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(), "AES"))
        val out = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(out.size * 2)
        for (b in out) sb.append(String.format("%02X", b))
        return sb.toString()
    }

    private fun md5(text: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
        // 与 BigInteger 无关，逐字节补零才是服务端要的 32 位小写 hex
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun csrfFromCookie(cookie: String): String =
        valueFromCookie(cookie, "__csrf") ?: valueFromCookie(cookie, "csrf_token") ?: ""

    private fun valueFromCookie(cookie: String, name: String): String? {
        if (cookie.isBlank()) return null
        for (part in cookie.split(";")) {
            val kv = part.trim()
            if (kv.startsWith("$name=")) return kv.removePrefix("$name=").trim()
        }
        return null
    }

    /**
     * 设备标识：进程内一次性生成。
     *
     * 同一进程里所有请求共用一个 deviceID（与网易云官方客户端"一台设备一个 id"的
     * 语义一致），进程重启换新 id 等同于重装，服务端不会当成异常高频。
     * 不落盘是刻意的：这条路径只服务"选高档位"这一个场景，没必要新增持久化键。
     */
    private val processDeviceId: String by lazy {
        buildString(52) {
            val rnd = java.security.SecureRandom()
            repeat(52) { append(HEX_CHARS[rnd.nextInt(HEX_CHARS.size)]) }
        }
    }

    private val deviceId: String get() = processDeviceId
}
