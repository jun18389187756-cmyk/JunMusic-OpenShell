package com.jun.musiccloud.data

import android.os.Build
import com.jun.musiccloud.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 意见反馈：把用户在 App 里写的内容送到官网站点，落进 `feedback` 表，
 * 总后台「反馈管理」页立刻能看到（带状态、可回复、可导出 CSV）。
 *
 * 【为什么走 music 域名而不是 api 域名】
 * 反馈的接收端是**官网站点**那套服务（`routes/public.js` 的 `POST /api/feedback`），
 * 表也在官网库 `site.db` 里；`api.example.invalid` 是另一套服务、另一个库。
 * 所以这里写死官网域名，和 [ServerApi.BASE_URL] 是两条独立链路——
 * 换 App 后端服务器不影响反馈，反过来也一样。
 *
 * 【权限】
 * 这个接口是**匿名可提交**的（官网留言板同一个口子），不需要登录。
 * 但登录用户会额外带上 userId / nickname，后台就能看出"是谁提的"，
 * 而不是只有一串 IP。没登录照样能提，只是后台显示"游客"。
 *
 * 【服务端防护】
 * 官网那边有三道：蜜罐字段 `website`（机器人会填，人看不见）、
 * 同 IP 每小时 5 条限流、正文 5~2000 字。这里的 [submit] 做同样的前置校验，
 * 让用户在点按钮之前就拿到明确的错误，而不是等服务器回 400。
 */
object FeedbackApi {

    /** 官网站点的反馈接口（末尾不带斜杠） */
    private const val ENDPOINT = "https://api.example.invalid/api/feedback"

    /**
     * 超时给到 15s：这接口后面挂着"给站长发通知"的 setImmediate，
     * 偶尔会被通知通道拖一下。8s 会让用户在弱网下看到假失败（其实已经入库了）。
     */
    private const val TIMEOUT_MS = 15000

    /** 正文长度上下限，与服务端 `public.js` 保持一致 */
    const val MIN_LENGTH = 5
    const val MAX_LENGTH = 2000

    /** 分类：key 直接进库，label 只用于界面 */
    enum class Category(val key: String, val label: String) {
        PLAYBACK("playback", "播放问题"),
        IMPORT("import", "导入歌单"),
        UI("ui", "界面建议"),
        OTHER("other", "其他")
    }

    /** 提交结果。失败也带一句能给用户看的话，不返回裸异常 */
    sealed class Result {
        /** message 用服务端原话（"收到啦，感谢反馈"），偶尔会改文案，直接透传更诚实 */
        data class Ok(val message: String) : Result()
        data class Fail(val message: String) : Result()
    }

    /**
     * 「我的反馈」里的一条记录。
     *
     * 只带用户自己看得懂的四样东西：写了什么、归到哪一类、现在什么状态、官方回了什么。
     * 不返回 contact / ip / device —— 这些是后台排查用的，回给用户没有意义，
     * 反而让这个接口变成一个"能读到设备信息"的口子。
     */
    data class Item(
        val id: Long,
        val content: String,
        val category: String,
        /** open / doing / done，服务端原值 */
        val status: String,
        val reply: String,
        val createdAt: Long,
        val updatedAt: Long
    ) {
        val hasReply: Boolean get() = reply.isNotBlank()

        /** 界面上那一枚状态徽章 */
        val statusLabel: String
            get() = when {
                hasReply -> "已回复"
                status == "doing" -> "处理中"
                status == "done" -> "已处理"
                else -> "待处理"
            }
    }

    /** 机型串：后台排查"只有某台机器播不了"时全靠它 */
    fun deviceLabel(): String {
        val brand = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        val os = "Android ${Build.VERSION.RELEASE}"
        return listOf(brand, model, os).filter { it.isNotBlank() }.joinToString(" ").take(120)
    }

    /**
     * 提交一条反馈。
     *
     * @param content 正文，调用方已 trim
     * @param contact 联系方式，可空
     * @param userId  登录用户的 id，0 表示游客
     * @param nickname 登录用户的显示名，可空
     * @param appVersion 形如 "6.4"，后台版本分布统计要用
     */
    suspend fun submit(
        content: String,
        contact: String = "",
        category: Category = Category.OTHER,
        userId: Long = 0L,
        nickname: String = "",
        appVersion: String,
        device: String = deviceLabel(),
        /** 设备标识（[Telemetry.deviceId]）。「我的反馈」靠它归属，见服务端注释 */
        deviceId: String = ""
    ): Result = withContext(Dispatchers.IO) {
        val text = content.trim()
        // 前置校验：省一次必然失败的往返，也让错误提示即时
        when {
            text.length < MIN_LENGTH -> return@withContext Result.Fail("再多写几个字吧，至少 $MIN_LENGTH 个字")
            text.length > MAX_LENGTH -> return@withContext Result.Fail("太长了，精简到 $MAX_LENGTH 字以内")
        }

        val body = JSONObject().apply {
            put("content", text)
            put("contact", contact.trim().take(128))
            put("category", category.key)
            put("appVersion", appVersion.take(32))
            put("device", device.take(120))
            // 账号身份：服务端 6.4 起才认这两个字段，老服务端会直接忽略，不影响提交
            if (userId > 0L) put("userId", userId)
            if (nickname.isNotBlank()) put("nickname", nickname.trim().take(40))
            if (deviceId.isNotBlank()) put("deviceId", deviceId.take(64))
            // 蜜罐：人类看不见这个框，永远是空；机器人会填，服务端见到就静默丢弃
            put("website", "")
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "JunMusic/$appVersion")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val root = stream?.let { s ->
                runCatching { JSONObject(s.bufferedReader().use { it.readText() }) }.getOrNull()
            }
            val msg = root?.optString("msg").orEmpty()
            when {
                status in 200..299 && (root?.optInt("code", 0) == 0) ->
                    Result.Ok(msg.ifBlank { "收到啦，感谢反馈" })
                status == 429 ->
                    Result.Fail(msg.ifBlank { "提交太频繁了，过一会儿再试" })
                status in 400..499 ->
                    Result.Fail(msg.ifBlank { "内容没通过校验，改一下再试" })
                else ->
                    Result.Fail("服务器开小差了（$status），稍后再试")
            }
        } catch (e: Exception) {
            // 网络异常一律给"可以重试"的话术：用户最关心的是"我写的字会不会白写"，
            // 所以界面上提交失败不清空正文（见 FeedbackSheet）
            Result.Fail("网络不通，检查一下再试（内容还在）")
        } finally {
            conn?.disconnect()
        }
    }

    // ==================== 我的反馈 ====================

    /**
     * 拉取本机（+ 本账号）提交过的反馈，带官方回复与处理状态。
     *
     * 失败一律返回空列表而不是抛异常：这是"打开面板顺带看一眼"的辅助信息，
     * 网络不好时不该弹错误把用户正在写的正文打断。
     */
    suspend fun fetchMine(
        userId: Long = 0L,
        deviceId: String = ""
    ): List<Item> = withContext(Dispatchers.IO) {
        if (userId <= 0L && deviceId.isBlank()) return@withContext emptyList()
        var conn: HttpURLConnection? = null
        try {
            val body = JSONObject().apply {
                if (userId > 0L) put("userId", userId)
                if (deviceId.isNotBlank()) put("deviceId", deviceId)
            }
            conn = (URL(MINE_ENDPOINT).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "JunMusic/${BuildConfig.VERSION_NAME}")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return@withContext emptyList()

            val root = conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val arr = root.optJSONObject("data")?.optJSONArray("rows") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        Item(
                            id = o.optLong("id"),
                            content = o.optString("content"),
                            category = o.optString("category"),
                            status = o.optString("status"),
                            reply = o.optString("reply"),
                            createdAt = o.optLong("createdAt"),
                            updatedAt = o.optLong("updatedAt")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 上次查看回复的时间点（毫秒）。
     *
     * 为什么不做服务端「已读」字段：反馈表在官网库，App 每次进来写一次已读会多一次
     * 写库；而"有没有新回复"本质是**本机 UI 上的一个小红点**，本地记一个时间戳
     * 就够了，换手机丢掉也无所谓（历史还在）。
     */
    fun lastSeenAt(context: android.content.Context): Long =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getLong(KEY_SEEN, 0L)

    fun markSeen(context: android.content.Context, ts: Long) {
        val p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (ts > p.getLong(KEY_SEEN, 0L)) {
            p.edit().putLong(KEY_SEEN, ts).apply()
        }
    }

    /** 未读回复数：只算"官方写了回复、且回复时间晚于上次查看"的条目 */
    fun unreadCount(items: List<Item>, lastSeen: Long): Int =
        items.count { it.hasReply && it.updatedAt > lastSeen }

    private const val PREFS = "jun_feedback"
    private const val KEY_SEEN = "reply_seen_at"
    private const val MINE_ENDPOINT = "https://api.example.invalid/api/feedback/mine"
}
