package com.jun.musiccloud.playback

import android.app.Notification
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
import com.jun.musiccloud.data.ReminderPrefs

/**
 * 6.7.0：定时提醒的系统通知。
 *
 * 用户要求的样式：「和高德状态栏通知一样，带图标、标题、内容」——
 * 也就是标准 Android 通知：小图标（状态栏）+ 标题 + 正文，可展开看全文，
 * 点击直接回到 App。这里全部用系统原生 NotificationCompat 实现，不画自定义布局
 * （自定义布局在深色模式/各家 ROM 上几乎都会被裁切，这是踩过的老坑）。
 */
object ReminderNotification {

    /** 提醒通知的通道（可调重要性/声音，用户能在系统设置里单独关掉） */
    const val CHANNEL_REMINDER = "jun_reminder"

    /** 前台服务常驻通知的通道：最低重要性，不响不震、折叠在"其他通知"里 */
    const val CHANNEL_SERVICE = "jun_reminder_service"

    const val ID_REMINDER = 7301
    const val ID_SERVICE = 7300

    /** 自动创建通道（Android 8.0+ 必须；重复调用是幂等的） */
    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        runCatching {
            val reminder = NotificationChannel(
                CHANNEL_REMINDER,
                "定时提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "按设定的间隔提醒你回来听歌"
                enableVibration(true)
                setShowBadge(true)
            }
            val service = NotificationChannel(
                CHANNEL_SERVICE,
                "提醒服务运行中",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "定时提醒的后台服务状态（关闭提醒后自动消失）"
                setShowBadge(false)
            }
            nm.createNotificationChannel(reminder)
            nm.createNotificationChannel(service)
        }
    }

    /** 点通知回到 App：清栈顶、复用已有实例，不要每次重开一个 MainActivity */
    private fun openAppIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_FROM_REMINDER, true)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(ctx, ID_REMINDER, intent, flags)
    }

    /** 一条提醒通知：图标 / 标题 / 内容 + 点击打开 App */
    fun buildReminder(ctx: Context): Notification {
        ensureChannels(ctx)
        val title = ReminderPrefs.title(ctx)
        val text = ReminderPrefs.text(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_jun)
            .setContentTitle(title)
            .setContentText(text)
            // 内容长了要能展开看全，不然状态栏只显示半句
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openAppIntent(ctx))
            .addAction(
                0,
                "打开 JunMusic",
                openAppIntent(ctx)
            )
            .build()
    }

    /** 后台服务常驻通知（Android 8+ 前台服务必须有；这里保持安静不打扰） */
    fun buildService(ctx: Context): Notification {
        ensureChannels(ctx)
        val minutes = ReminderPrefs.intervalMin(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_jun)
            .setContentTitle("定时提醒已开启")
            .setContentText("每 $minutes 分钟提醒一次 · 可在设置里关闭")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent(ctx))
            .build()
    }

    /** 立刻弹一条（"试一下"按钮用），权限没给时返回 false 让 UI 能提示 */
    fun showReminderNow(ctx: Context): Boolean {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false
        return runCatching {
            NotificationManagerCompat.from(ctx).notify(ID_REMINDER, buildReminder(ctx))
            true
        }.getOrDefault(false)
    }

    const val EXTRA_FROM_REMINDER = "jun_from_reminder"
}
