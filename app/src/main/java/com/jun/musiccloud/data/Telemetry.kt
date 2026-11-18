package com.jun.musiccloud.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * App 行为上报（总后台「APP 用户 / 访客 / 增长分析」用）。
 *
 * 可靠性设计：
 *   · 事件先写本地队列（SharedPreferences），再批量上报；断网不丢，恢复后补报；
 *   · 每条事件带 eventId，服务端唯一索引去重，重试不会把启动数刷高；
 *   · occurredAt 记录真实发生时间，离线补报也能归到正确的一天；
 *   · 批量接口用 HMAC-SHA256 签名，脚本无法伪造上报。
 *
 * 设备标识：
 *   · hwId      ANDROID_ID 哈希，换账号、卸载重装都不变（一台手机只算一台）；
 *   · installId 每次安装生成的 UUID，hwId 取不到时兜底，避免同型号+同 IP 误合并。
 *
 * 只上报：事件类型、设备标识、机型、系统、App 版本、时长。
 * 不采集 IMEI / MAC / 手机号 / 位置。
 */
object Telemetry {

    private const val PREFS = "jun_telemetry"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_INSTALL = "install_id"
    private const val KEY_QUEUE = "event_queue"
    private const val EVENT_SECRET = "junmusic-ev-2026-3f9c1a7e"
    private const val MAX_QUEUE = 500
    private const val BATCH = 50

    @Volatile private var cachedDeviceId: String? = null
    @Volatile private var cachedInstallId: String? = null
    @Volatile private var lastPlayAt: Long = 0L
    private val flushing = AtomicBoolean(false)
    private val launchSent = AtomicBoolean(false)
    private val lock = Any()

    /** 埋点落盘专用线程：串行，保证事件顺序；低优先级，不和播放/网络抢 CPU */
    private val io: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "jun-telemetry").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

    /** 稳定的硬件标识：ANDROID_ID 哈希，卸载重装也不会变 */
    fun hwId(context: Context): String {
        cachedDeviceId?.let { return it }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        val id = if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
            val raw = "$androidId|${Build.MANUFACTURER}|${Build.MODEL}"
            val hex = MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray())
                .joinToString("") { "%02x".format(it) }
            "d" + hex.take(30)
        } else {
            p.getString(KEY_DEVICE, null)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also { p.edit().putString(KEY_DEVICE, it).apply() }
        }
        if (p.getString(KEY_DEVICE, null) != id) {
            p.edit().putString(KEY_DEVICE, id).apply()
        }
        cachedDeviceId = id
        return id
    }

    fun deviceId(context: Context): String = hwId(context)

    /** 每次安装一个稳定 UUID：同型号两台手机不会被并成一台 */
    fun installId(context: Context): String {
        cachedInstallId?.let { return it }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getString(KEY_INSTALL, null)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { p.edit().putString(KEY_INSTALL, it).apply() }
        cachedInstallId = id
        return id
    }

    /** 由安装 ID 算出的稳定灰度分桶（0-99），保证同一台设备每次检查更新结果一致 */
    fun installBucket(context: Context): Int {
        val h = MessageDigest.getInstance("SHA-256").digest(installId(context).toByteArray())
        return (h[0].toInt() and 0xFF) % 100
    }

    /** 每次冷启动只上报一次：进程内 AtomicBoolean 保证热恢复不会重复计数 */
    fun reportLaunchOnce(context: Context) {
        if (!launchSent.compareAndSet(false, true)) return
        enqueue(context, "launch", "", 0, "")
    }

    /** 兼容旧调用 */
    fun reportLaunch(context: Context) = reportLaunchOnce(context)

    /** 一次前台停留：seconds 为累计秒数，start 为进入时间（毫秒） */
    fun reportSession(context: Context, seconds: Int, startAt: Long, sessionId: String) {
        if (seconds <= 0) return
        val extra = runCatching { JSONObject().put("start", startAt).toString() }.getOrDefault("")
        enqueue(context, "session", extra, seconds.coerceAtMost(86400), sessionId)
    }

    /** 登录 / 退出，后台用来判断这台手机当前属于哪个账号 */
    fun reportAuth(context: Context, type: String) {
        if (type != "login" && type != "logout") return
        enqueue(context, type, "", 0, "")
    }

    fun report(context: Context, type: String, extra: String = "") {
        val now = System.currentTimeMillis()
        if (type == "play") {
            if (now - lastPlayAt < 5 * 60 * 1000L) return
            lastPlayAt = now
        }
        enqueue(context, type, extra, 0, "")
    }

    /**
     * 听歌时长心跳：累计播放满 5 秒上报一次（秒数向下取整，至少 1 秒）。
     * 暂停 / 切歌时把不足 5 秒的尾巴也补报，所以「听 1 秒就算 1 秒」。
     */
    fun reportListen(context: Context, seconds: Int, title: String = "") {
        if (seconds <= 0) return
        enqueue(context, "listen", title, seconds.coerceAtMost(600), "")
    }

    /** 当前正在播放的歌曲（后台用户详情里直接显示） */
    fun reportNowPlaying(context: Context, title: String, artist: String) {
        if (title.isBlank()) return
        val extra = runCatching {
            JSONObject().put("title", title).put("artist", artist).toString()
        }.getOrDefault(title)
        enqueue(context, "nowplaying", extra, 0, "")
    }

    /** 通知已展示 / 已读回执：服务端按设备去重，清 App 数据也不会重复弹 */
    fun reportNotice(context: Context, id: Long, action: String) {
        if (id <= 0) return
        val app = context.applicationContext
        Thread {
            runCatching {
                val body = JSONObject()
                    .put("id", id)
                    .put("deviceKey", hwId(app))
                    .put("action", if (action == "read") "read" else "shown")
                ServerApi.request("/api/app/notice/read", body, AccountStore.token.ifBlank { null })
            }
        }.start()
    }

    /** 把本地队列整批发出去；失败保留，下一次上报或回到前台继续重试 */
    fun flush(context: Context) {
        val app = context.applicationContext
        if (!flushing.compareAndSet(false, true)) return
        Thread {
            try {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                // 一次 flush 连续发多批，最多 10 批（500 条），避免大积压只能等下次
                var round = 0
                while (round < 10) {
                    round += 1
                    val arr = synchronized(lock) {
                        runCatching { JSONArray(prefs.getString(KEY_QUEUE, "[]") ?: "[]") }.getOrElse { JSONArray() }
                    }
                    if (arr.length() == 0) break
                    val take = minOf(arr.length(), BATCH)
                    val batch = JSONArray()
                    val sentIds = HashSet<String>()
                    for (i in 0 until take) {
                        val item = arr.getJSONObject(i)
                        batch.put(item)
                        sentIds.add(item.optString("eventId", ""))
                    }
                    val bodyText = JSONObject().put("events", batch).toString()
                    val ts = System.currentTimeMillis().toString()
                    val nonce = UUID.randomUUID().toString().replace("-", "")
                    val bodyHash = sha256Hex(bodyText)
                    val msg = "POST\n/api/app/events\n$ts\n$nonce\n$bodyHash"
                    val sign = hmacHex(msg)
                    val headers = mapOf(
                        "X-JM-Ts" to ts,
                        "X-JM-Nonce" to nonce,
                        "X-JM-Sign" to sign
                    )
                    val r = ServerApi.requestText(
                        "/api/app/events",
                        bodyText,
                        headers,
                        AccountStore.token.ifBlank { null }
                    )
                    if (r != null && r.optInt("code", -1) == 0) {
                        // 按 eventId 精确移除已发送项：发送期间新入队的事件不会被旧快照覆盖丢失
                        synchronized(lock) {
                            val cur = runCatching { JSONArray(prefs.getString(KEY_QUEUE, "[]") ?: "[]") }
                                .getOrElse { JSONArray() }
                            val rest = JSONArray()
                            for (i in 0 until cur.length()) {
                                val item = cur.getJSONObject(i)
                                if (item.optString("eventId", "") !in sentIds) rest.put(item)
                            }
                            prefs.edit().putString(KEY_QUEUE, rest.toString()).apply()
                        }
                    } else break
                    if (take < BATCH) break
                }
            } catch (_: Exception) {
                // 网络失败：保留队列，等下一次
            } finally {
                flushing.set(false)
            }
        }.start()
    }

    private fun enqueue(context: Context, type: String, extra: String, seconds: Int, sessionId: String) {
        val app = context.applicationContext
        // 队列最多 500 条、上百 KB 的 JSON：解析 + 重序列化放在调用线程（通常是主线程）
        // 上就是肉眼可见的卡顿，nova 6 这一类老机上足够触发 ANR 被系统回收。
        // 现在交给单线程后台执行器，事件顺序仍然保持入队顺序。
        io.execute {
            runCatching { writeEvent(app, type, extra, seconds, sessionId) }
        }
    }

    private fun writeEvent(app: Context, type: String, extra: String, seconds: Int, sessionId: String) {
        val obj = JSONObject()
            .put("eventId", UUID.randomUUID().toString())
            .put("type", type)
            .put("occurredAt", System.currentTimeMillis())
            .put("deviceId", deviceId(app))
            .put("installId", installId(app))
            .put("hwId", hwId(app))
            .put("model", Build.MODEL.orEmpty())
            .put("os", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("versionCode", com.jun.musiccloud.BuildConfig.VERSION_CODE)
            .put("versionName", com.jun.musiccloud.BuildConfig.VERSION_NAME)
            .put("extra", extra)
        if (seconds > 0) obj.put("seconds", seconds)
        if (sessionId.isNotBlank()) obj.put("sessionId", sessionId)

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(lock) {
            val arr = runCatching { JSONArray(prefs.getString(KEY_QUEUE, "[]") ?: "[]") }
                .getOrElse { JSONArray() }
            arr.put(obj)
            val trimmed = if (arr.length() > MAX_QUEUE) {
                JSONArray().also { out ->
                    for (i in arr.length() - MAX_QUEUE until arr.length()) out.put(arr.getJSONObject(i))
                }
            } else arr
            prefs.edit().putString(KEY_QUEUE, trimmed.toString()).apply()
        }
        flush(app)
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun hmacHex(text: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(EVENT_SECRET.toByteArray(), "HmacSHA256"))
        return mac.doFinal(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
