package com.jun.musiccloud.playback

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.jun.musiccloud.data.ReminderPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 6.7.0：定时提醒的后台服务。
 *
 * 为什么用前台服务而不是 WorkManager：
 * 用户明确要「每隔 N 分钟」——WorkManager 的最小周期是 15 分钟，而且系统会按
 * Doze/电池策略随意推迟（实测能推迟到几小时）。AlarmManager 的精确闹钟在
 * Android 12+ 需要 SCHEDULE_EXACT_ALARM 授权，普通用户看到那个开关只会更迷糊。
 *
 * 前台服务是唯一"用户设几分钟就几分钟"的方案，代价是状态栏有一条常驻通知 ——
 * 所以这条通知做成 IMPORTANCE_MIN（折叠、不响不震），并且关掉提醒就立刻消失。
 * 这也是高德、网易云那类"后台常驻"功能的通行做法。
 */
class ReminderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        ReminderNotification.ensureChannels(this)
        refreshForeground()
    }

    /** 建立/更新常驻通知（改间隔后要让用户看到"每 N 分钟"的新值） */
    private fun refreshForeground() {
        runCatching {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                ReminderNotification.ID_SERVICE,
                ReminderNotification.buildService(this),
                type
            )
        }.onFailure { Log.w(TAG, "startForeground failed: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ReminderPrefs.setOn(this, false)
            stopReminder()
            return START_NOT_STICKY
        }
        if (!ReminderPrefs.isOn(this)) {
            stopReminder()
            return START_NOT_STICKY
        }
        refreshForeground()
        // ────────────────────────────────────────────────────────────
        // 6.7.1：**开启后立刻弹一条**，不要让用户等一个完整间隔。
        //
        // 用户反馈"开了根本没有任何通知，前台也通知不了"——原因就是：
        // 第一条提醒要等 N 分钟（默认 30 分钟），这段时间里只有一条
        // IMPORTANCE_MIN 的常驻通知（折叠在"其他通知"里，几乎看不见）。
        // 于是用户以为功能没生效。现在拨开开关当场弹一条，肉眼立刻能验证。
        // 系统看门狗/被系统重启时 intent 为 null，不会重复弹。
        // ────────────────────────────────────────────────────────────
        if (intent?.action == ACTION_START) {
            runCatching { ReminderNotification.showReminderNow(this) }
        }
        // 间隔可能在服务运行期间被改：每次 start 都重启循环，保证新的分钟数立刻生效
        startLoop()
        // START_STICKY：被系统清掉后尽量自己回来（配合 BootReceiver 与看门狗闹钟）
        return START_STICKY
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                val minutes = ReminderPrefs.intervalMin(this@ReminderService)
                delay(minutes.coerceAtLeast(1) * 60_000L)
                if (!ReminderPrefs.isOn(this@ReminderService)) {
                    stopReminder()
                    break
                }
                // 通知权限被用户在系统里关掉时，静默跳过（不报错、不反复弹）
                ReminderNotification.showReminderNow(this@ReminderService)
            }
        }
    }

    private fun stopReminder() {
        loop?.cancel()
        loop = null
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onDestroy() {
        loop?.cancel()
        loop = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ReminderService"
        const val ACTION_START = "com.jun.musiccloud.action.REMINDER_START"
        const val ACTION_STOP = "com.jun.musiccloud.action.REMINDER_STOP"

        /** 开启 / 更新定时（幂等：重复调用只是重起循环） */
        fun start(ctx: Context) {
            // 6.6：Android 12+ 在后台调 startForegroundService 会抛
            // ForegroundServiceStartNotAllowedException。定时提醒属于「可以等回到前台再补」的
            // 工作，绝不能在后台硬拉服务 —— 后台崩溃日志里 12 条同类记录主要来自这条路径。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isForeground(ctx)) return
            val i = Intent(ctx, ReminderService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }.onFailure {
                // 即便判定为前台，厂商 ROM 仍可能拒绝；降级成普通 startService，
                // 服务 onCreate 里会自己 startForeground，失败也不影响主进程。
                android.util.Log.w(TAG, "startForegroundService rejected, fallback", it)
                runCatching { ctx.startService(i) }
            }
        }

        private fun isForeground(ctx: Context): Boolean = runCatching {
            val info = android.app.ActivityManager.RunningAppProcessInfo()
            android.app.ActivityManager.getMyMemoryState(info)
            info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }.getOrDefault(true)

        fun stop(ctx: Context) {
            runCatching {
                val i = Intent(ctx, ReminderService::class.java).setAction(ACTION_STOP)
                ctx.startService(i)
            }
        }
    }
}
