package com.jun.musiccloud.playback

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.media3.common.Player
import com.jun.musiccloud.data.LyricLine
import com.jun.musiccloud.data.LyricParser
import com.jun.musiccloud.ui.theme.DevicePerformance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 桌面悬浮歌词：常驻全局覆盖窗，单行显示当前句，跟随播放进度滚动。
 *
 * 挂在 [MusicService] 上而非 Activity：服务是前台常驻的，App 退到桌面、切到别的
 * 应用，歌词窗依然跟随。所有可调项（开关/字号/长度/透明度）读 `jun_prefs`，
 * 注册 [SharedPreferences.OnSharedPreferenceChangeListener] 实时响应设置页改动。
 *
 * 用原生 TextView 而非 ComposeView：不引新依赖（离线仓库无 ui-view），
 * 且脱离 Activity 的悬浮窗里原生 view 无需自持 Lifecycle owner，最稳。
 */
object FloatingLyric {

    const val PREFS = "jun_prefs"
    const val KEY_ENABLED = "float_lyric_enabled"
    const val KEY_FONT = "float_lyric_font"          // sp 字号，12~40
    const val KEY_WIDTH = "float_lyric_width"         // 文本框宽度占屏比，0.3~1.0
    const val KEY_BG = "float_lyric_bg"               // 黑底透明度 0~1（0 最浓，1 完全消失）
    const val KEY_POS_X = "float_lyric_x"             // 悬浮窗 gravity-x 偏移（px）
    const val KEY_POS_Y = "float_lyric_y"             // 悬浮窗 gravity-y 偏移（px）
    const val KEY_LOCK = "float_lyric_lock"           // 锁定：不可拖动、点击穿透
    /** 6.9.5：锁屏显示歌词（用户需求）。默认关 —— 打开后亮屏未解锁时也保持悬浮窗。
     *  依赖"显示悬浮窗"权限；MIUI/澎湃 还要允许「锁屏显示」，ColorOS/Funtouch 同理。 */
    const val KEY_SHOW_ON_LOCKSCREEN = "float_lyric_show_lockscreen"
    const val KEY_TRANS = "float_lyric_trans"         // 第二行显示译文
    const val KEY_TEXT_ALPHA = "float_lyric_alpha"    // 文字不透明度 0.2~1
    /** 6.9.11：与设置页「歌词时间校准」同源的偏移键（PlayerViewModel.KEY_LYRIC_OFFSET 同值） */
    const val KEY_OFFSET = "lyric_offset"

    const val FONT_DEFAULT = 18f
    const val WIDTH_DEFAULT = 0.86f
    const val BG_DEFAULT = 0.3f
    const val ALPHA_DEFAULT = 1f

    /** 进度轮询周期：500ms 足够跟手，又不至于高频读播放器 */
    private const val POLL_MS = 500L

    /**
     * 熄屏时的轮询周期（老机适配）。
     *
     * 熄屏后歌词窗已经收掉了，这条只是"防止 Runnable 自己断掉"的保底心跳，
     * 不该继续 500ms 敲主线程。给到 5 秒：CPU 能睡，重新亮屏由广播立刻叫醒。
     */
    private const val POLL_MS_OFF = 5_000L

    private var appContext: Context? = null
    private var player: Player? = null
    private var windowManager: WindowManager? = null
    private var textView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var bgDrawable: GradientDrawable? = null

    /** 黑底透明档（0~1），喂给 applyStyle 计算黑底浓度。 */
    private var bgT = BG_DEFAULT

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var prefs: SharedPreferences? = null
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // 歌词缓存
    private var lyricPath: String? = null
    private var lyricLines: List<LyricLine> = emptyList()
    private var loadingPath: String? = null

    private var shown = false

    /**
     * 本次挂窗的时刻（uptimeMillis）。
     *
     * addView 到 dispatchAttachedToWindow 之间隔着一次 traversal，那几帧里
     * isAttachedToWindow 必然是 false。所有"按附着状态判定窗已被系统收走"的地方
     * 都要先看这个宽限期，否则刚挂好的窗会被自己拆掉（表现为桌面歌词一闪就没）。
     */
    private var shownSince = 0L

    // ==================== 老机（EMUI/Android 10）窗口安全闸 ====================
    //
    // 悬浮歌词是这份清单里唯一"越过 App 边界去占系统窗口层"的功能：
    // TYPE_APPLICATION_OVERLAY 画在别人的窗口之上，锁屏、桌面、别的 App 都会受影响。
    // EMUI 10 上有两种真实故障：
    //  A) 系统在熄屏/锁屏时把 overlay 窗口回收掉，但我们这边 shown 还是 true，
    //     于是再也不会重新 addView——用户看到的是"歌词窗不见了"，
    //     而 WindowManager 侧留下的半截层就是"看着像跳到锁屏"的那种残影；
    //  B) 窗口还在但内容透明（黑底档位调到"完全消失"），却仍然可点——
    //     一条横穿屏幕的隐形触摸拦截带压在 Keyguard 之上，
    //     结果是"屏幕亮着密码框但划不动"，只能长按强制重启。
    // 这两条都必须用代码挡掉，不能指望用户不去调那个滑条。
    @Volatile private var screenOn = true
    @Volatile private var receiverRegistered = false
    @Volatile private var addFailedRecently = false
    private val screenReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // 熄屏直接收窗：既不给系统留"锁屏上的第三方层"，也让主线程轮询停下来
                    screenOn = false
                    hide()
                }
                Intent.ACTION_USER_PRESENT -> {
                    // 解锁完成，这是唯一允许重新挂窗的时机
                    screenOn = true
                    addFailedRecently = false
                    syncVisibility()
                }
                Intent.ACTION_SCREEN_ON -> {
                    // 只亮屏还没解锁：仍然不挂 overlay。密码输入那一屏就是最容易
                    // 被第三方层压住的一屏，等 USER_PRESENT 再挂。
                    screenOn = true
                    hide()
                }
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            // 窗已经不在了就别再自轮询：熄屏/锁屏收窗后这条心跳必须一起停，
            // 否则老机器上等于留了一个 500ms 的常驻唤醒源（EMUI 省电策略最爱抓这个）
            if (!shown || textView == null) return
            // 被系统偷偷收走窗的情况：标记还在、view 已脱离窗口树 → 立刻按没挂处理
            // （刚挂上的 400ms 内不判：首次遍历可能还没跑完，误判会把好窗拆掉）
            if (!textView!!.isAttachedToWindow &&
                android.os.SystemClock.uptimeMillis() - shownSince >= 400L
            ) { hide(); return }
            updateLine()
            handler.postDelayed(this, if (screenOn) POLL_MS else POLL_MS_OFF)
        }
    }

    /** 由 [MusicService.onCreate] 调用一次：保存上下文与播放器引用，恢复悬浮窗状态。 */
    fun init(context: Context, p: Player) {
        appContext = context.applicationContext
        player = p
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = sp
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KEY_FONT -> applyFont(sp.getFloat(KEY_FONT, FONT_DEFAULT))
                KEY_WIDTH -> applyWidth(sp.getFloat(KEY_WIDTH, WIDTH_DEFAULT))
                KEY_BG -> { bgT = sp.getFloat(KEY_BG, BG_DEFAULT); applyStyle() }
                KEY_TRANS, KEY_TEXT_ALPHA -> applyStyle()
                KEY_LOCK -> {
                    val c = appContext
                    if (c != null) {
                        if (shown) { hide(); show(c) } else syncVisibility()
                    }
                }
                // 6.9.5：锁屏显示开关实时生效
                KEY_SHOW_ON_LOCKSCREEN -> syncVisibility()
                KEY_ENABLED -> syncVisibility()
            }
        }
        prefListener = listener
        sp.registerOnSharedPreferenceChangeListener(listener)
        // 屏幕状态：init 时先取真值。服务可能是在熄屏状态下被媒体键拉起的，
        // 默认"亮着"会让我们在锁屏上直接挂出 overlay 窗——正是要避免的那一幕。
        screenOn = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm?.isInteractive ?: true
            else pm?.isScreenOn ?: true
        }.getOrDefault(true)
        registerScreenReceiver()
        syncVisibility()
    }

    /**
     * 挂上屏幕状态广播（幂等，可安全重复调用）。
     *
     * 公开给 Activity 用：熄屏收窗时若那次 unregister 没成功、或进程在熄屏状态下
     * 被媒体键拉起（init 里注册失败），就没有任何事件会再叫醒这个窗；
     * 亮屏时补挂一次是最省事的自愈路径。
     */
    fun registerScreenReceiver() {
        if (receiverRegistered) return
        val ctx = appContext ?: return
        runCatching {
            ctx.registerReceiver(
                screenReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
            )
            receiverRegistered = true
        }
    }

    fun release() {
        handler.removeCallbacks(poll)
        prefListener?.let { prefs?.unregisterOnSharedPreferenceChangeListener(it) }
        prefListener = null
        if (receiverRegistered) {
            runCatching { appContext?.unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        hide()
        appContext = null
        player = null
        prefs = null
    }

    /**
     * 开关 + 老机安全档一起决定要不要放这个窗。
     *
     * 安全档默认关掉悬浮歌词（legacy_fx_overlay_lyric 可单独强制打开）：
     * 判定只有这一处，别在调用点再各写一遍 Build.VERSION 分支。
     */
    // 6.6：缺省值从 true 改成 false —— 桌面悬浮歌词默认关闭。
    // 以前这里写 true，和 default_settings.json 的 false 相反，导致刚进 App 就自动
    // 拉起悬浮窗 / 弹授权引导，用户看到的却是「开关是关的」。以「关」为唯一默认值。
    private fun enabled(): Boolean =
        DevicePerformance.allowOverlayLyric && (prefs?.getBoolean(KEY_ENABLED, false) ?: false)

    /** 外部（设置页授权返回后 / Activity onResume）主动触发一次显隐同步。 */
    fun refresh() = syncVisibility()

    /**
     * 崩溃日志用的窗口现场快照：纯读、不产生任何副作用。
     *
     * 为什么需要它：nova 6 那类"屏幕闪到输入密码页、划不动、只能强制重启"的反馈，
     * 关键线索不是崩在哪一行，而是**崩的那一刻这块 TYPE_APPLICATION_OVERLAY 挂着没有**。
     * 窗口是系统侧资源，进程一死现场就断了，所以必须在写 crash.log 的同一瞬间
     * 把 shown / 是否真的附着 / 触摸闸状态抄进日志，否则下次只能靠用户复述。
     */
    fun diagnosticState(): String =
        "shown=$shown attached=${textView?.isAttachedToWindow} screenOn=$screenOn " +
            "addFailed=$addFailedRecently receiver=$receiverRegistered shield=$shieldApplied"

    /** 根据开关 + 悬浮窗权限决定显示/隐藏。 */
    private fun syncVisibility() {
        val ctx = appContext ?: return
        // 6.9.5：锁屏显示歌词是**可选**开关（默认关，沿用"Keyguard 之上不留第三方层"
        // 的历史默认）。打开后亮屏未解锁时也保持悬浮窗；熄屏仍然收窗（省电 + ROM 限制）。
        val wantLockscreen = prefs?.getBoolean(KEY_SHOW_ON_LOCKSCREEN, true) ?: true
        val keyguardOk = keyguardUp(ctx) && wantLockscreen && screenOn
        val canShow = enabled() && Settings.canDrawOverlays(ctx) &&
            (screenOn && (!keyguardUp(ctx) || keyguardOk))
        if (canShow) show(ctx) else hide()
    }

    /** KeyguardManager 判锁屏；拿不到就按"没锁"处理，最坏是多挂一次，不会漏收窗。 */
    private fun keyguardUp(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)
            ?.isKeyguardLocked ?: false
    }.getOrDefault(false)

    private fun screenW(): Int =
        appContext?.resources?.displayMetrics?.widthPixels ?: 1080

    @SuppressLint("ClickableViewAccessibility")
    private fun show(ctx: Context) {
        if (shown) {
            // 已经"认为"自己挂着窗：再核一次它是不是真的还在窗口树上。
            // EMUI 会在熄屏/切桌面/省电策略里直接回收 overlay，而我们这边的
            // shown 标记是进程内的，回收不会把它翻回 false——于是永远不再 addView。
            // isAttachedToWindow 是唯一可靠的证据：系统 removeView 时一定会
            // dispatchDetachedFromWindow。（这里不能用 windowToken：悬浮窗的
            // token 本来就由系统侧持有，客户端 params.token 常常是 null，会误判。）
            val alive = textView?.isAttachedToWindow == true
            // 刚挂上的这一帧内不能拿附着状态说事：首次 traversal 还没跑，附着一定是 false，
            // 直接重建就会在同一帧里 addView/removeView 来回抖动。
            val justAdded = android.os.SystemClock.uptimeMillis() - shownSince < 400L
            if (alive || justAdded) return
            hide()
        }
        // addView 连续失败过就先不再试（等 USER_PRESENT / 权限回调 / refresh 再来）。
        // 老 ROM 上权限被回收后每次同步都去 addView，会留下一串"已加入但没附上"的
        // 窗口记录，SurfaceFlinger 侧就是残影的来源。
        if (addFailedRecently) return
        val wm = windowManager ?: return
        val sp = prefs ?: return

        val locked = sp.getBoolean(KEY_LOCK, false)
        currentLocked = locked
        // 6.9.5：锁屏显示歌词 —— FLAG_SHOW_WHEN_LOCKED 对 overlay 窗仍被绝大多数 ROM
        // 尊重（AOSP/MIUI/ColorOS/Funtouch 实测可用面广）；配合 syncVisibility 的
        // "亮屏未解锁 + 开关打开"规则，只影响锁屏这一种状态。
        val showOnLockscreen = sp.getBoolean(KEY_SHOW_ON_LOCKSCREEN, true)
        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            maxLines = if (sp.getBoolean(KEY_TRANS, false)) 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            alpha = sp.getFloat(KEY_TEXT_ALPHA, ALPHA_DEFAULT).coerceIn(0.2f, 1f)
            val padH = (16 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            // 阴影提升暗背景下的可读性
            setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.getFloat(KEY_FONT, FONT_DEFAULT))
        }

        val lp = WindowManager.LayoutParams(
            (screenW() * sp.getFloat(KEY_WIDTH, WIDTH_DEFAULT)).roundToInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // 不抢焦点、不挡其它区域触摸（除自身可拖），延伸到状态栏。
            // 6.9.5：锁屏显示开关打开时额外带 FLAG_SHOW_WHEN_LOCKED，
            // 让系统在亮屏未解锁时保留本窗；熄屏路径仍由 syncVisibility 收窗。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (locked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0) or
                (if (showOnLockscreen) @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sp.getInt(KEY_POS_X, 0)
            y = sp.getInt(KEY_POS_Y, (120 * ctx.resources.displayMetrics.density).toInt())
        }

        if (!locked) attachDrag(tv, lp)

        val added = runCatching { wm.addView(tv, lp); true }
            .onFailure {
                // BadTokenException（权限被收回）/ 其他 ROM 私有异常都在这里吃掉
                addFailedRecently = true
                android.util.Log.w("FloatingLyric", "addView failed: ${it.message}")
            }.getOrDefault(false)
        if (!added) { hide(); return }
        textView = tv
        params = lp
        shown = true
        shownSince = android.os.SystemClock.uptimeMillis()
        // 初始化外观（黑底浓度）
        bgT = sp.getFloat(KEY_BG, BG_DEFAULT)
        applyStyle()
        applyTouchShield()
        // “窗真的进了渲染树没有”必须异步校验：addView 返回的那一刻，ViewRootImpl 只是
        // 排了首次 traversal，dispatchAttachedToWindow 还没发生，此时同步读
        // isAttachedToWindow 恒为 false——那样写等于每次都把好窗拆掉，桌面歌词永远出不来。
        // 而 handler.post 恰好排在 traversal 之后：scheduleTraversals() 会先在主线程队列上
        // 立一道同步屏障，普通消息要等屏障撤销（也就是首次遍历跑完）才会执行。
        handler.post {
            // 期间可能已经收窗或被重建：只对"还是这一块 view"的结果负责
            if (!shown || textView !== tv) return@post
            if (tv.isAttachedToWindow) return@post
            // 加了窗却始终没附着 = 半挂着的窗口，正是"透明却拦触摸"的残影来源，当场拆掉
            addFailedRecently = true
            android.util.Log.w("FloatingLyric", "overlay added but never attached")
            hide()
        }
        handler.post(poll)
    }

    /** 创建时用户选的锁定态，供触摸闸判断（锁定本来就不可点） */
    private var currentLocked: Boolean = false

    /** 当前窗口是否已经带上 FLAG_NOT_TOUCHABLE，避免每次改文字都 updateViewLayout 一次 */
    private var shieldApplied: Boolean = false

    /**
     * 透明层的触摸闸。
     *
     * 规则：黑底滑到"完全消失"（bgT≈1）或当前没有文字内容时，这一层就是纯透明的；
     * 此时若还可点，屏幕上就横着一条隐形触摸拦截带。压在桌面/锁屏上时这是
     * "亮着密码框却划不动"的直接原因，所以宁可牺牲一次拖动，也不留这种层。
     *
     * 只在"想要"和"已有"不一致时才 updateViewLayout：拖动、切句都会走到这里，
     * 每次都刷新布局反而更卡。
     */
    private fun applyTouchShield() {
        val lp = params ?: return
        val invisible = bgT >= 0.999f
        val noText = textView?.text.isNullOrBlank() == true
        val need = currentLocked || invisible || noText
        val has = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
        if (need == has) { shieldApplied = need; return }
        lp.flags = if (need)
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        val ok = runCatching { windowManager?.updateViewLayout(textView, lp); true }
            .getOrDefault(false)
        // 改不动（窗已被系统回收）就别留个假状态：直接收窗，下一次同步会重建
        if (!ok) { addFailedRecently = true; hide() } else shieldApplied = need
    }

    /** 文本区拖动：整体移动悬浮窗，落盘记忆位置。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(tv: TextView, lp: WindowManager.LayoutParams) {
        var lastX = 0f
        var lastY = 0f
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX; lastY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x += (e.rawX - lastX).roundToInt()
                    lp.y += (e.rawY - lastY).roundToInt()
                    lastX = e.rawX; lastY = e.rawY
                    runCatching { windowManager?.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs?.edit()
                        ?.putInt(KEY_POS_X, lp.x)
                        ?.putInt(KEY_POS_Y, lp.y)
                        ?.apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun hide() {
        handler.removeCallbacks(poll)
        // 先把标记翻掉再 removeView：万一 removeView 抛（窗已被系统收走），
        // 也不能让 shown 停在 true，否则下一次 syncVisibility 永远不再重建
        shown = false
        shieldApplied = false
        textView?.let { v ->
            // removeViewImmediate：同步把窗口从渲染树上摘掉。
            // 用 removeView 是排到下一帧才摘，中途那几毫秒就是"透明却拦触摸"的窗口。
            runCatching { windowManager?.removeViewImmediate(v) }
                .onFailure { runCatching { windowManager?.removeView(v) } }
        }
        textView = null
        params = null
        bgDrawable = null
    }

    // —— 参数实时应用（仅在已显示时改视图，未显示则 show() 时会读取最新值）——
    private fun applyFont(sp: Float) {
        textView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    /**
     * 黑底颜色：滑条档 t∈[0,1]（0 最浓 → 1 完全消失），全量程线性。
     * 文字始终不受此档影响。
     */
    private fun bgColorFor(t: Float): Int {
        val maxA = 190 // 最浓时黑底 alpha（约 75%）
        val a = ((1f - t).coerceIn(0f, 1f) * maxA).roundToInt()
        return (a shl 24)
    }

    /**
     * 统一应用外观：单层圆角黑底。黑底浓度由 [bgT] 全量程决定，文字不受影响。
     */
    private fun applyStyle() {
        val tv = textView ?: return
        val sp = prefs ?: return
        tv.alpha = sp.getFloat(KEY_TEXT_ALPHA, ALPHA_DEFAULT).coerceIn(0.2f, 1f)
        tv.maxLines = if (sp.getBoolean(KEY_TRANS, false)) 2 else 1
        val radius = 18 * tv.resources.displayMetrics.density
        val fill = GradientDrawable().apply {
            cornerRadius = radius
            setColor(bgColorFor(bgT))
        }
        bgDrawable = fill
        tv.background = fill
        // 黑底滑到"完全消失"时，这一层已经看不见了 —— 立刻过一遍触摸闸，
        // 不给"看不见的可点条带"留任何存活时间
        applyTouchShield()
    }

    private fun applyWidth(frac: Float) {
        val lp = params ?: return
        lp.width = (screenW() * frac).roundToInt()
        runCatching { windowManager?.updateViewLayout(textView, lp) }
    }

    /** 取当前播放句：歌曲变化时异步解析歌词，之后按 position 取行。 */
    private fun updateLine() {
        val p = player ?: return
        val item = p.currentMediaItem ?: run { setText(""); return }
        // 真实文件路径由 toMediaItem 透传在 MediaMetadata.extras 里；
        // content URI 的 .path 不是文件路径，直接读会取不到歌词，故优先取 extras。
        val path = item.mediaMetadata.extras?.getString("lyric_key")
            ?.takeIf { it.isNotBlank() }
            ?: item.mediaMetadata.extras?.getString("file_path")
            ?: item.localConfiguration?.uri?.let { if (it.scheme == "file") it.path else null }
        if (path.isNullOrBlank()) { setText(""); return }

        if (path != lyricPath && path != loadingPath) {
            loadingPath = path
            lyricPath = path
            lyricLines = emptyList()
            val duration = if (p.duration > 0) p.duration else 0L
            scope.launch {
                val lines = withContext(Dispatchers.IO) {
                    runCatching { LyricParser.loadLyrics(path, duration) }.getOrDefault(emptyList())
                }
                if (loadingPath == path) {
                    lyricLines = lines
                    loadingPath = null
                }
            }
        }

        val lines = lyricLines
        if (lines.isEmpty()) {
            // 还没解析出来 / 没歌词：显示歌名占位，避免空白
            setText(item.mediaMetadata.title?.toString() ?: "")
            return
        }
        // 6.9.11：悬浮歌词此前读的是 player 裸位置，全局/单曲校准偏移从没生效过。
        // 语义与主歌词页一致：正 = 歌词延后（引擎时钟往回拨）。
        val offset = run {
            var v = prefs?.getInt(KEY_OFFSET, 0) ?: 0
            val songId = runCatching { p.currentMediaItem?.mediaId?.toLongOrNull() }.getOrNull()
            if (songId != null && songId > 0L) {
                // 单曲记忆：lyric_song_offsets 存的是 "id=ms;id=ms" 串（与设置页同源）
                val map = prefs?.getString("lyric_song_offsets", null)
                map?.split(';')?.forEach { kv ->
                    val pair = kv.split('=')
                    if (pair.size == 2 && pair[0].toLongOrNull() == songId) {
                        pair[1].toIntOrNull()?.let { v = it }
                    }
                }
            }
            v.coerceIn(-2000, 2000)
        }
        val idx = LyricParser.currentIndex(lines, (p.currentPosition - offset).coerceAtLeast(0L))
        val line = lines.getOrNull(idx)
        val main = line?.text.orEmpty()
        val trans = if (prefs?.getBoolean(KEY_TRANS, false) == true) {
            line?.translation?.takeIf { it.isNotBlank() }
        } else null
        setText(if (trans == null) main else "$main\n$trans")
    }

    private fun setText(s: String) {
        val tv = textView ?: return
        if (tv.text != s) {
            tv.text = s
            // 空句 = 这一层完全透明，必须马上过一遍触摸闸
            applyTouchShield()
        }
    }

    /**
     * 崩溃/退出前强制收窗。
     *
     * 进程被系统杀掉时窗口会自动消失，但"我们自己把异常吞掉继续跑"的那条路径
     * 不会——那时留着的 overlay 就是用户口中"桌面/锁屏上一块划不动的区域"。
     */
    fun forceHide() {
        addFailedRecently = false
        hide()
    }

    /** 跳转系统悬浮窗授权页。 */
    fun requestPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun hasPermission(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)
}