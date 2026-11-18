package com.jun.musiccloud.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.jun.musiccloud.data.ReminderPrefs

/**
 * 6.7.0：定时提醒的"看守"。
 *
 * 职责只有两件，都跟"别让定时悄悄失效"有关：
 *  1. 开机 / App 升级后把前台服务拉起来（否则用户重启一次手机，提醒就永远不响了）；
 *  2. 每 15 分钟做一次看门狗：国产 ROM 的省电策略经常直接清掉前台服务，
 *     这里发现服务没在跑就重新拉起来。
 *
 * 用 AlarmManager 的**非精确**闹钟（setAndAllowWhileIdle）：不需要
 * SCHEDULE_EXACT_ALARM 那个让用户一头雾水的系统授权，误差几分钟对"提醒"完全够用。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!ReminderPrefs.isOn(context)) return
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_WATCHDOG -> {
                Log.i(TAG, "restart reminder service: ${intent.action}")
                ReminderService.start(context)
                ReminderScheduler.scheduleWatchdog(context)
            }
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        const val ACTION_WATCHDOG = "com.jun.musiccloud.action.REMINDER_WATCHDOG"
    }
}

/** 看门狗闹钟的登记 / 取消；设置页开关时调用 */
object ReminderScheduler {

    private const val WATCHDOG_INTERVAL_MS = 15L * 60 * 1000

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_WATCHDOG)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(ctx, 7401, i, flags)
    }

    fun scheduleWatchdog(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(ctx))
        }.onFailure { Log.w("ReminderScheduler", "schedule failed: ${it.message}") }
    }

    fun cancel(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(ctx))
        }
    }

    /** 用户拨开开关时统一走这里：开 → 起服务 + 布看门狗；关 → 全停 */
    fun setEnabled(ctx: Context, on: Boolean) {
        ReminderPrefs.setOn(ctx, on)
        if (on) {
            ReminderService.start(ctx)
            scheduleWatchdog(ctx)
        } else {
            ReminderService.stop(ctx)
            cancel(ctx)
            runCatching {
                androidx.core.app.NotificationManagerCompat.from(ctx)
                    .cancel(ReminderNotification.ID_REMINDER)
            }
        }
    }
}
