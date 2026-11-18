package com.jun.musiccloud.ui.theme

import android.view.Choreographer
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.max

/**
 * 玻璃渲染的运行时调度器——「120Hz 丝滑」的总闸。
 *
 * 背景：真折射液态玻璃的每一块元件，每帧都要做三件重活：
 *   1. 把整块背板（可能是整屏歌单）record 进自己的 GraphicsLayer；
 *   2. 跑「调色 → 高斯模糊 → AGSL 位移取样」RenderEffect 链；
 *   3. 再把 SDF 内缘描一圈。
 * 一屏十几个玻璃元件就是十几条全屏流水线。旗舰机扛得住，8 Gen1 这一档（以及绝大多数
 * 中端机）在**滚动时**必然掉帧——静止时其实很富余。
 *
 * 因此这里的策略不是"砍效果"，而是**按负载分时供给**：
 *   - 静止：完整真折射，质感一点不减；
 *   - 滚动中（[scrollActive]）：跳过背板取样，只留本体染色 + 内缘——
 *     观感几乎一致（背景本来就已经被高斯糊过），但省掉最贵的那一段；
 *   - 滚动期实测仍然掉帧（[startFrameWatch]）：永久关掉真折射（仍保留玻璃观感），
 *     再不行才由 [DevicePerformance.markRuntimeDegrade] 切实色。
 *
 * 关键点：以上全部判断发生在**绘制阶段**（读 @Volatile 字段），不参与组合。
 * 滚动开始时不需要任何重组，滚动停止后靠 [redrawTick] 触发一次重绘把真折射补回来。
 */
object GlassRuntime {

    /** 列表正在滚动。由界面用 `LazyListState.isScrollInProgress` 写入。 */
    @Volatile
    var scrollActive: Boolean = false
        set(value) {
            field = value
            if (value) {
                lastScrollAtMs = android.os.SystemClock.elapsedRealtime()
                ensureFrameWatch()
            }
        }

    /** 最后一次"正在滚动"的时刻（uptime 毫秒）。用于识别"手指还在滑"的误触。 */
    @Volatile
    private var lastScrollAtMs: Long = 0L

    /**
     * 刚刚还在滚动吗（6.3.1 防误触）。
     *
     * 用户反馈：翻列表、翻唱片墙时经常"莫名点到播放/点到歌手"。
     * 安卓标准的点击取消依赖 touch slop——手指位移超过阈值才会被判定为滑动，
     * 高刷屏上快速轻扫很容易在几十毫秒内完成、位移却没过阈值，于是"滑动"被当成"点击"。
     * 这里给一个 140ms 的时间窗：只要最近还在滚，这次点击就当误触丢弃。
     * 时间窗必须短——正常"停手马上点"不能被吃掉。
     */
    fun tappedWhileScrolling(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        return (scrollActive || pageScrolling) || (now - lastScrollAtMs) < 140L
    }

    /** 卡片页 / 唱片墙正在翻页或拖动（横向列表、堆叠、唱片墙都走这里）。 */
    @Volatile
    var pageScrolling: Boolean = false
        set(value) {
            field = value
            if (value) ensureFrameWatch()
        }

    /** 实测扛不住真折射：进程内永久关闭（保住玻璃观感，去掉最贵的取样）。 */
    @Volatile
    private var refractionOff: Boolean = false

    /** 绘制期判断：此刻是否跳过背板取样。读它是零成本的 volatile 读，不触发重组。 */
    val skipRefraction: Boolean
        get() = scrollActive || pageScrolling || refractionOff || DevicePerformance.autoDegraded ||
            displayPaused

    /**
     * 滚动结束后的重绘信号。
     *
     * 玻璃的简化分支与真折射分支都写在 `drawWithCache` 的 onDrawBehind 里，
     * 不重绘就不会切换外观。滚动停下后自增它，让所有玻璃元件重组一帧，
     * 真折射随即回到屏幕上——用户看到的是"停下来玻璃就变回通透"。
     */
    private val tickState = mutableIntStateOf(0)
    val redrawTick: Int get() = tickState.intValue

    /** 滚动停止后延迟一帧再补画，避开惯性滑动的尾巴（还在动就又降级了一次） */
    fun requestRedraw() {
        tickState.intValue++
    }

    /** 由帧监控调用：确认本机跑不动真折射 */
    fun disableRefraction() {
        refractionOff = true
    }

    /** 当前是否已关闭真折射（供「关于」页 / 调试展示） */
    val refractionDisabled: Boolean get() = refractionOff

    /**
     * 画面不可见（熄屏 / 锁屏 / 退到后台）标记。
     *
     * 老机适配必需的一条：玻璃管线的代价全花在"用户看不见的帧"上时，
     * 换来的只有 GPU 占用与更高的被 EMUI 功耗守护盯上的概率。
     * nova 6 上播放时锁屏，界面并没有真的死掉（服务还在放歌、Compose 树还活着），
     * 于是背板取样与帧监控可能在后台继续被唤醒——这里给它一道明确的闸。
     */
    @Volatile
    private var displayPaused: Boolean = false

    /** 画面当前是否处于不可见状态（供诊断日志与「关于」页展示） */
    val displayPausedNow: Boolean get() = displayPaused

    /**
     * 熄屏 / 锁屏 / onPause 时调用：收掉帧监控挂号，并宣布"没有在滚"。
     *
     * 之所以顺手把 scrollActive/pageScrolling 也翻成 false：惯性滚动的事件在锁屏后
     * 还会送达，留着 true 会让帧监控在下一次亮屏时拿着过期状态立刻重新挂号。
     */
    fun pauseForDisplayOff() {
        displayPaused = true
        scrollActive = false
        pageScrolling = false
        stopFrameWatch()
    }

    /** 亮屏 / 解锁 / onResume 时调用：恢复玻璃管线并补一帧把真折射画回来。 */
    fun resumeFromDisplayOff() {
        if (!displayPaused) return
        displayPaused = false
        requestRedraw()
    }

    /**
     * 彻底停止帧监控（幂等）。熄屏与崩溃收尾都要走这里。
     *
     * removeFrameCallback 只是撤销还没执行的挂号，不会打断正在执行的那一次 doFrame；
     * 那一次执行末尾会看到 scrollActive/pageScrolling 已经是 false，自行收手。
     */
    fun stopFrameWatch() {
        watchRunning = false
        val c = watchChoreographer ?: return
        val cb = watchCallback ?: return
        runCatching { c.removeFrameCallback(cb) }
    }

    /**
     * 常驻帧监控（只在滚动期间统计）。
     *
     * 只统计滚动窗口是有意的：静止时掉帧毫无意义（Compose 静止根本不出帧），
     * 拿静止帧去算掉帧率会把 60Hz 待机误判成"掉了一半帧"。
     * 每 90 个滚动帧做一次裁决：
     *   - 掉帧率 > 25% → 关真折射（仍保留玻璃观感）
     *   - 掉帧率 > 40% → 切实色 + 关装饰动画
     *
     * 回调本身只有一次 nanoTime 读取与几个自增，开销可以忽略。
     */
    // 帧监控的"睡眠/唤醒"状态：静止时彻底不占 vsync，滚动开始时由 setter 重新挂号。
    private var watchChoreographer: Choreographer? = null
    private var watchCallback: Choreographer.FrameCallback? = null
    @Volatile private var watchRunning = false
    private var watchLastNs = 0L

    /** 重新挂上帧回调（幂等）。 */
    private fun ensureFrameWatch() {
        if (watchRunning) return
        // 画面不可见时一律不挂 vsync：熄屏后没有任何东西要画，挂号只是白唤醒 CPU
        if (displayPaused) return
        val c = watchChoreographer ?: return
        val cb = watchCallback ?: return
        watchRunning = true
        watchLastNs = System.nanoTime()
        runCatching { c.postFrameCallback(cb) }
    }

    fun startFrameWatch() {
        val choreographer = runCatching { Choreographer.getInstance() }.getOrNull() ?: return
        var frames = 0
        var slow = 0
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val now = System.nanoTime()
                val dtMs = (now - watchLastNs) / 1_000_000.0
                watchLastNs = now
                if (scrollActive) {
                    frames++
                    // 6.3.1：阈值从 24ms 收到 20ms、采样窗从 90 帧收到 60 帧。
                    // 用户是一台 144Hz 机器（每帧 6.9ms），按 24ms 判等于允许掉到 40fps
                    // 才开始降级——那时候肉眼看已经是"卡一下"了。20ms（50fps）就开始动手，
                    // 降的只是折射层（背板取样），视觉几乎无感，换回的是稳定帧率。
                    // 60Hz 设备同样适用：16.7ms 的正常帧不会被误判，只有真掉帧才触发。
                    if (dtMs > 20.0) slow++
                    if (frames >= 60) {
                        val ratio = slow.toFloat() / max(frames, 1)
                        when {
                            ratio > 0.40f -> DevicePerformance.markRuntimeDegrade()
                            ratio > 0.25f -> disableRefraction()
                        }
                        frames = 0
                        slow = 0
                    }
                }
                // 只在真的还在滚/还在拖时继续挂号；停下来就松掉 vsync，
                // 待机时不再每秒被唤醒 60~120 次（老机型与 EMUI 的后台查杀都更友好）。
                if ((scrollActive || pageScrolling) && !displayPaused) {
                    choreographer.postFrameCallback(this)
                } else {
                    watchRunning = false
                }
            }
        }
        watchChoreographer = choreographer
        watchCallback = callback
        watchLastNs = System.nanoTime()
        // 启动时若并不在滚动，就先不挂号：等第一次滚动由 ensureFrameWatch() 拉起。
        if (scrollActive || pageScrolling) ensureFrameWatch()
    }
}
