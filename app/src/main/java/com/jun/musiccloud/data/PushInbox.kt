package com.jun.musiccloud.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jun.musiccloud.MainActivity
import com.jun.musiccloud.R
import org.json.JSONObject

/**
 * 6.9.3：后台推送 → 手机通知栏。
 *
 * ## 为什么是轮询而不是 FCM
 *
 * 国内机型上 FCM 基本收不到（没有 GMS），厂商通道（小米/华为/OPPO…）要逐家申请、
 * 每家一套 SDK + 后台备案，对这个体量完全不划算。而我们的场景是「运营发一条通知」，
 * 不是秒级聊天消息——App 冷启动、回到前台、以及前台每 5 分钟各拉一次，
 * 已经足够。消息本身留在服务端，**关机/重装/换设备都不会漏**。
 *
 * ## 为什么把「已读」放在本地
 *
 * 服务端只负责存消息，不记录每个设备看到哪条（几万台设备一人一行，纯属浪费）。
 * 本地只存一个 lastId，简单、无并发、换设备重新收一遍也无所谓。
 */
object PushInbox {

    /** 推送通道：默认重要度，有声音/振动，用户可在系统设置里单独关掉 */
    const val CHANNEL = "jun_push"

    private const val PREFS = "jun_push"
    private const val KEY_LAST = "last_id"
    private const val KEY_SHOWN = "shown_ids"
    private const val KEY_LAST_POLL = "last_poll_at"

    /** 前台轮询间隔：5 分钟。太频繁会让服务端日志全是这个接口 */
    private const val POLL_GAP_MS = 5 * 60 * 1000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            val ch = NotificationChannel(CHANNEL, "App 通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "来自 Jun Music 官方的消息与更新提醒"
                enableVibration(true)
                setShowBadge(true)
            }
            nm.createNotificationChannel(ch)
        }
    }

    /**
     * 拉一次推送。**阻塞函数**，只能在 IO 线程调。
     *
     * @param force true = 忽略 5 分钟节流（冷启动 / 手动刷新）
     * @return 这次新弹出的条数
     */
    fun poll(ctx: Context, force: Boolean = false): Int {
        if (!ServerApi.isConfigured) return 0
        // 冷启动时 Application 可能比 ViewModel 先跑，账号还没载入 →
        // uid=0 会让「仅登录用户」的推送被漏掉。这里补一次（幂等）。
        if (AccountStore.userId <= 0L) runCatching { AccountStore.load(ctx) }
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        if (!force && now - p.getLong(KEY_LAST_POLL, 0L) < POLL_GAP_MS) return 0
        p.edit().putLong(KEY_LAST_POLL, now).apply()

        val since = p.getLong(KEY_LAST, 0L)
        val uid = AccountStore.userId
        val root = runCatching {
            ServerApi.request("/api/app/push?since=$since&uid=$uid", null, null, null, 8_000)
        }.getOrNull() ?: return 0
        if (root.optInt("code", -1) != 0) return 0
        val data = root.optJSONObject("data") ?: return 0

        // 撤回：后台把某条停用后，已弹出的通知也要收掉
        val activeIds = runCatching {
            val arr = data.optJSONArray("activeIds") ?: return@runCatching emptySet<Long>()
            (0 until arr.length()).map { arr.optLong(it) }.toSet()
        }.getOrDefault(emptySet())
        val shown = p.getStringSet(KEY_SHOWN, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (activeIds.isNotEmpty()) {
            val gone = shown.filter { (it.toLongOrNull() ?: -1L) !in activeIds }
            if (gone.isNotEmpty()) {
                val nm = NotificationManagerCompat.from(ctx)
                gone.forEach { runCatching { nm.cancel(it.hashCode()) } }
                shown.removeAll(gone.toSet())
            }
        }

        val arr = data.optJSONArray("items") ?: return 0
        var shownCount = 0
        var maxId = since
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id")
            if (id <= since) continue
            maxId = maxOf(maxId, id)
            val title = o.optString("title").ifBlank { "Jun Music" }
            val body = o.optString("body")
            if (body.isBlank()) continue
            val link = o.optString("link")
            if (show(ctx, id, title, body, link) && shownCount < 3) shownCount++
            shown += id.toString()
        }
        if (maxId > since) p.edit().putLong(KEY_LAST, maxId).apply()
        // 只留最近 200 个 id，别让 prefs 无限长
        if (shown.size > 200) {
            val keep = shown.mapNotNull { it.toLongOrNull() }.sorted().takeLast(200).map { it.toString() }.toSet()
            p.edit().putStringSet(KEY_SHOWN, keep).apply()
        } else {
            p.edit().putStringSet(KEY_SHOWN, shown).apply()
        }
        return shownCount
    }

    /** 弹一条系统通知；没给通知权限时返回 false（不抛异常，不打断调用方） */
    fun show(ctx: Context, id: Long, title: String, body: String, link: String = ""): Boolean {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false
        ensureChannel(ctx)
        return runCatching {
            val intent = Intent(ctx, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_PUSH_ID, id)
                if (link.isNotBlank()) putExtra(EXTRA_PUSH_LINK, link)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getActivity(ctx, id.toInt(), intent, flags)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_jun)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            NotificationManagerCompat.from(ctx).notify(id.toInt(), n)
            true
        }.getOrDefault(false)
    }

    /** 设置页“测试通知”用：立刻弹一条本地通知，验证权限与通道都通 */
    fun showTest(ctx: Context): Boolean =
        show(ctx, 900000L + (System.currentTimeMillis() % 1000), "Jun Music 通知已开启", "以后后台发的消息会出现在这里。")

    const val EXTRA_PUSH_ID = "jun_push_id"
    const val EXTRA_PUSH_LINK = "jun_push_link"
}
