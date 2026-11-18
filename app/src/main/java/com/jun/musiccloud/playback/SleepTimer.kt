package com.jun.musiccloud.playback

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 睡眠定时器——权威计时在 Service 所在进程，UI 销毁不影响到期停播。
 *
 * 旧实现把 delay(totalMs) 放在 PlayerViewModel.viewModelScope：
 * ViewModel 随界面销毁取消协程，用户"设了定时、划掉界面"后定时器静默消失，
 * 音乐播一整夜。这里把计时挪到进程级单例（Service 与 ViewModel 同进程）。
 *
 * - endMs 落盘 SharedPreferences：进程被回收后服务被重新拉起时继续剩余计时。
 * - 到期广播给所有观察者：Service 暂停播放器，ViewModel 清 UI 状态。
 * - UI 倒计时展示走 endMs 墙钟，天然与这里同步。
 */
object SleepTimer {

    private const val PREFS = "sleep_timer"
    private const val KEY_END = "end_ms"
    private const val KEY_TOTAL = "total_ms"
    private const val KEY_STOP_AFTER_SONG = "stop_after_song"

    private val handler = Handler(Looper.getMainLooper())
    private var prefs: SharedPreferences? = null
    private var runnable: Runnable? = null

    /** 到期观察者：Service 注册"暂停播放器"，ViewModel 注册"清 UI 状态"。返回列表以便反注册。 */
    private val expireObservers = CopyOnWriteArrayList<() -> Unit>()

    fun addExpireObserver(obs: () -> Unit): () -> Unit {
        expireObservers.add(obs)
        return { expireObservers.remove(obs) }
    }

    /** "播完这首停"状态变化观察者（开启/触发清除都会通知），用于同步 UI。 */
    private val stopAfterSongObservers = CopyOnWriteArrayList<() -> Unit>()

    fun addStopAfterSongObserver(obs: () -> Unit): () -> Unit {
        stopAfterSongObservers.add(obs)
        return { stopAfterSongObservers.remove(obs) }
    }

    private fun notifyStopAfterSongChanged() {
        stopAfterSongObservers.forEach { runCatching { it() } }
    }

    /** 当前定时的结束墙钟毫秒；0 = 未设置。 */
    @Volatile
    var endMs: Long = 0L
        private set

    /** 本次定时的总时长（进度环用）。 */
    @Volatile
    var totalMs: Long = 0L
        private set

    /**
     * 是否在"当前曲目播完"后停止（与分钟定时互斥）。
     * 适合"这首听完后就睡"的场景：不依赖墙钟，自动切到下一首的瞬间暂停。
     */
    @Volatile
    var stopAfterSong: Boolean = false
        private set

    /** Service onCreate：绑 prefs 并恢复未到期的定时 / 续播开关。 */
    fun attach(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
        if (endMs == 0L) {
            val saved = prefs!!.getLong(KEY_END, 0L)
            if (saved > System.currentTimeMillis()) {
                totalMs = prefs!!.getLong(KEY_TOTAL, 0L)
                arm(saved)
            } else if (saved != 0L) clearPersisted()
        }
        stopAfterSong = prefs!!.getBoolean(KEY_STOP_AFTER_SONG, false)
    }

    /** 设置定时（minutes<=0 视为取消），返回新的 endMs 墙钟。设分钟会清掉"播完这首停"。 */
    fun set(context: Context, minutes: Int): Long {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
        cancelAlarmOnly()
        stopAfterSong = false
        prefs?.edit()?.putBoolean(KEY_STOP_AFTER_SONG, false)?.apply()
        if (minutes <= 0) {
            endMs = 0L
            totalMs = 0L
            clearPersisted()
            return 0L
        }
        totalMs = minutes * 60_000L
        val end = System.currentTimeMillis() + totalMs
        arm(end)
        prefs?.edit()?.putLong(KEY_END, end)?.putLong(KEY_TOTAL, totalMs)?.apply()
        return end
    }

    /** 设置"当前曲目播完后停止"。与分钟定时互斥：开启会清掉分钟定时。 */
    fun setStopAfterSong(context: Context, enable: Boolean) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
        if (enable) {
            cancelAlarmOnly()
            endMs = 0L
            totalMs = 0L
            clearPersisted()
        }
        stopAfterSong = enable
        prefs?.edit()?.putBoolean(KEY_STOP_AFTER_SONG, enable)?.apply()
        notifyStopAfterSongChanged()
    }

    /** 仅取消"播完这首停"（保留分钟定时不受影响）。 */
    fun cancelStopAfterSong() {
        if (!stopAfterSong) return
        stopAfterSong = false
        prefs?.edit()?.remove(KEY_STOP_AFTER_SONG)?.apply()
        notifyStopAfterSongChanged()
    }

    fun cancel() {
        cancelAlarmOnly()
        endMs = 0L
        totalMs = 0L
        stopAfterSong = false
        clearPersisted()
        prefs?.edit()?.remove(KEY_STOP_AFTER_SONG)?.apply()
    }

    private fun arm(end: Long) {
        endMs = end
        schedule(end, (end - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    /**
     * 挂一次到期回调。
     *
     * 早到保护：Handler.postDelayed 走 uptimeMillis，deep sleep 期间 uptime 冻结而墙钟在走，
     * 加上 EMUI 会把唤醒闹钟往前对齐，于是可能出现"还没到点就跑进来"。
     * 用户在定时里听到的是"设 30 分钟、20 分钟就自己停了"——提前 pause 比晚几秒停严重得多，
     * 所以进来先对墙钟复核一次，没到点就把剩下的时间再挂一遍。
     */
    private fun schedule(end: Long, delayMs: Long) {
        val r = Runnable {
            val remain = end - System.currentTimeMillis()
            if (remain > 1_000L) {
                schedule(end, remain)
                return@Runnable
            }
            onExpired()
        }
        runnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun onExpired() {
        runnable = null
        endMs = 0L
        totalMs = 0L
        clearPersisted()
        expireObservers.forEach { runCatching { it() } }
    }

    private fun cancelAlarmOnly() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
    }

    /**
     * 进程收尾（含未捕获异常兜底）时调用：只撤闹钟回调，不清用户设的定时。
     *
     * 为什么单独一个方法而不是直接 cancel()：cancel() 会把 endMs 与 prefs 里的
     * 记录一起抹掉，服务被 EMUI 杀掉再拉起来时用户那条"还剩 12 分钟"就没了。
     * 这里只保证"当前这个进程不再因为一个到点的回调去动播放器"。
     */
    fun releaseAlarms() {
        cancelAlarmOnly()
    }

    private fun clearPersisted() {
        prefs?.edit()?.remove(KEY_END)?.remove(KEY_TOTAL)?.apply()
    }
}