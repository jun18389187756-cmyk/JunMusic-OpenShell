package com.jun.musiccloud.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全局动效中心：果冻开关（设置里）驱动整个 App 的弹性手感。
 *
 * 打开：按钮按压、开关拨钮、卡片滑动等交互动画换成低阻尼弹簧（果冻回弹）；
 * 关闭：保持原有的平滑 tween / 高阻尼弹簧。
 *
 * 静态字段由 MainScreen 在状态变化时写入，各组件直接读取，不引入重组依赖。
 */
object JunMotion {

    // ===== 6.6 动效风格（用户：「除了果冻Q弹也会弄一个别的效果吧……设置里面能切换」）=====
    //
    // 三种风格，同一套运动词汇（按压/出现/弹层/滑行），只换弹簧的"性格"：
    //   果冻Q弹 JELLY  —— 现有的软弹簧，冲过头再荡回来（原本的 jelly=true）；
    //   顺滑     SMOOTH —— 临界阻尼为主、只有轻微余势：几乎不过冲，快而稳；
    //   超弹     BOUNCY —— 比果冻更夸张：阻尼更低、刚度更软，过冲更大、荡的次数更多。
    //
    // 实现上沿用 timeScale 的思路：每种风格就是一组 (阻尼比, 刚度基数)，全部
    // 集中在下面的 styleDamping()/styleStiffness() 两个函数里，别处不许写数字。
    // [jelly] 保留为派生值（= style == JELLY），全 App 十几处直接读它的老代码不用动。
    enum class MotionStyle(val label: String) {
        JELLY("果冻Q弹"),
        SMOOTH("顺滑"),
        BOUNCY("超弹");

        companion object {
            fun of(name: String?): MotionStyle =
                entries.firstOrNull { it.name == name } ?: SMOOTH
        }
    }

    // 6.7：这四个参数全部换成 Compose State。之前是普通 @Volatile 静态字段，
    // 各组件在组合时把值读进 animateXxxAsState 后就再也不订阅——设置里切了风格/速度，
    // 界面纹丝不动（要退出重进才生效），用户读到的是"改了也没效果"。
    // 换成 State 后：改动那一帧所有读过它的动效调用点会重组一次（一次性成本，可接受），
    // 新弹簧当场接管，立刻能看到手感的差别。
    var style: MotionStyle by mutableStateOf(MotionStyle.SMOOTH)
        private set

    /** 切换风格；调用方负责持久化（见 PlayerViewModel.setMotionStyle）。
     *  6.7：改名 applyStyle——style 换成 Compose State 后，private set 与本函数
     *  在 JVM 层撞签名（都是 setStyle），把写入入口让给这个函数。 */
    fun applyStyle(s: MotionStyle) {
        style = s
        // jelly 保持派生：老的 if (jelly) 分支在 SMOOTH / BOUNCY 下走"非果冻"档，
        // 新档位的差异全部由本文件里显式读 style 的弹簧给出。
        jelly = s == MotionStyle.JELLY
    }

    /** 各风格的基础阻尼比。值越小越"弹" */
    private fun styleDamping(jellyV: Float, smoothV: Float, bouncyV: Float): Float =
        when (style) {
            MotionStyle.JELLY -> jellyV
            MotionStyle.SMOOTH -> smoothV
            MotionStyle.BOUNCY -> bouncyV
        }

    /** 各风格的基础刚度。越小越慢越软 */
    private fun styleStiffness(jellyK: Float, smoothK: Float, bouncyK: Float): Float =
        when (style) {
            MotionStyle.JELLY -> jellyK
            MotionStyle.SMOOTH -> smoothK
            MotionStyle.BOUNCY -> bouncyK
        }
    /**
     * 6.3 新增：整体动画速度倍率。1.0 = 设计基准，>1 更慢，<1 更快。
     *
     * 用户的原话是「想要一个整体过渡动画快慢的开关，小播放器↔大播放器、果冻 Q 弹的
     * 速度都能调；默认稍微快一点，追求极致动画的人可以自己调慢」。
     *
     * 怎么实现才"只改速度、不改形状"
     * --------------------------
     * 弹簧的运动方程里，阻尼比 ζ = c / (2√(km)) 决定"弹几下、冲多远"，固有频率
     * ω = √(k/m) 决定"多久收敛"。所以：
     *   - 只动 k：ζ 会跟着变，越快越"硬"、越慢越"黏"，果冻的手感被改掉了；
     *   - 同时按 **k' = k / s²** 缩放 stiffness、阻尼比原样保留：
     *     时间轴整体被拉长 s 倍，过冲幅度、回弹次数、曲线形状完全一致。
     * 这就是下面所有 [k] 包装存在的原因——stiffness 一律过一遍它，别处不许直接写数字。
     *
     * 定长 tween 则按 s 线性乘（时间就是时间）。
     *
     * 为什么用 @Volatile 静态字段而不是 Compose State：这些参数在 animateFloatAsState
     * 构造时被读一次，放进 State 只会让整个界面为一次设置改动重组 400 个组件。
     * 改完让人重新进入界面即可生效——比"滑动时全屏重组"体验好得多。
     */
    /** 默认档：比设计基准快约 15%（收敛时间约 0.85 倍），用户要的「默认稍微快一点」 */
    const val DEFAULT_SCALE = 0.85f

    var timeScale: Float by mutableStateOf(DEFAULT_SCALE)
        private set

    /**
     * 设置页的五档。数值就是时间倍率。
     *
     * 为什么最慢只到 1.60：再慢弹层打开要 2 秒以上，用户会以为卡住了；
     * 最快 0.55 是"我在调设置，让我立刻看到结果"的速度，不影响可用性。
     */
    val SPEED_STEPS: List<Pair<Float, String>> = listOf(
        0.55f to "极快",
        0.72f to "快",
        0.85f to "标准",
        1.12f to "慢",
        1.60f to "很慢"
    )

    /** 写入新倍率；调用方负责持久化到 prefs（见 PlayerViewModel.setMotionSpeed） */
    fun setSpeed(scale: Float) {
        timeScale = scale.coerceIn(0.4f, 2.5f)
    }

    /** 当前倍率对应的中文档名，设置页显示用 */
    fun speedLabel(scale: Float = timeScale): String {
        val hit = SPEED_STEPS.minByOrNull { kotlin.math.abs(it.first - scale) }
        return hit?.second ?: "自定义"
    }

    /**
     * 弹簧刚度换算：k' = k / s²。
     *
     * 推导：ω = √(k/m)，周期 T = 2π/ω ∝ 1/√k。要让 T' = s·T，需要
     * √(k/k') = s ⇒ k' = k / s²。阻尼比 ζ = c/(2√(km)) 里 c 不动、k 变小，
     * ζ 会变……所以这里同时把 dampingRatio 保持原值——Compose 的 spring() 是
     * 直接吃 ζ 的，ζ 不变 ⇒ 形状不变，只有速度变。这正是我们要的。
     */
    private fun k(base: Float): Float = base / (timeScale * timeScale)

    /** 定长动画时长换算：直接乘 s */
    private fun ms(base: Int): Int = (base * timeScale).toInt().coerceAtLeast(16)

    /** [ms] 的 Dp/无类型重载，给 tween<Dp> 用 */
    private fun dur(base: Int): Int = ms(base)

    /** 果冻动效总开关（PlayerViewModel.jellyAnim 同步而来） */
    var jelly: Boolean by mutableStateOf(false)

    /**
     * 省电动画（PlayerViewModel.powerSavingAnim 同步而来）。
     *
     * 只对"一直跑、纯装饰"的无限动画生效：呼吸描边、插画浮动、加号脉动这类。
     * 交互反馈（按压、翻页、飞入）一律保留——那是用户操作后的回应，砍掉会变成"点了没反应"。
     */
    var reduced: Boolean by mutableStateOf(false)

    /**
     * 逐帧动画在老机型上的最小帧间隔（ns）。0 = 不节流（每帧都画）。
     *
     * 歌词舞台那些全屏 Canvas 装饰（星尘、涟漪、流体纹）原本每帧都 tick 一次：
     * 120Hz 的屏幕就是每秒 120 次「重组 + 整屏重绘」。nova 6 这一类老机器扛不住，
     * 表现就是歌词页掉帧、发热、被系统当成异常进程杀掉。
     * 开了省电动画 / 判定为老机型时降到约 30fps：装饰还在动，负载只有三分之一。
     */
    val frameGapNanos: Long
        get() = if (reduced || com.jun.musiccloud.ui.theme.DevicePerformance.legacy) 33_333_333L else 0L

    /**
     * 帧循环里问一句「这一帧该不该干活」。
     * @param last 调用方持有的单元素时间戳数组（remember 一份即可，不是 State，不会引起重组）
     */
    fun frameDue(nowNanos: Long, last: LongArray): Boolean {
        val gap = frameGapNanos
        if (gap <= 0L) { last[0] = nowNanos; return true }
        if (nowNanos - last[0] < gap) return false
        last[0] = nowNanos
        return true
    }

    // ===== R12 全站放慢（用户：「整体的果冻动效都要缩慢一大截，必须是非常明显的回弹，
    // 不管点什么都是要有果冻特效」）=====
    //
    // 这一轮把**所有交互弹簧**整体调软调慢：刚度平均下调约 45~55%（收敛时间约
    // √(旧/新) ≈ 1.4~2.4 倍），阻尼再往下压 0.1~0.15（过冲更明显，松手后能看见
    // "弹过头再晃回来"那两下）。定长 tween 的基准 300ms → 420ms。
    // 只有「省电 / 减弱动画」这一档不参与：它仍然是临界阻尼 + 高刚度，
    // 免得老机器上"慢"变成"拖"。要再快一点，改这一个 object 里的数字就够了。

    /** 按钮按压回弹（按压缩小 → 松手弹回） */
    fun pressScale(): FiniteAnimationSpec<Float> = when {
        reduced -> spring(dampingRatio = 0.9f, stiffness = k(700f))
        // 6.6：三种风格。顺滑＝几乎不过冲、落点干脆；超弹＝比果冻更荡
        else -> spring(
            dampingRatio = styleDamping(0.30f, 0.55f, 0.20f),
            stiffness = k(styleStiffness(160f, 340f, 110f))
        )
    }

    /**
     * 过渡时长基准（ms）。
     *
     * 省电模式（低端机）下压到 [DURATION_REDUCED]：不是砍掉动画，而是缩短。
     * 低端机上 300ms 的展开动画整段都在掉帧，观感反而"卡"；缩到 170ms 后
     * 需要渲染的帧数少掉四成，既更利落也更容易跑满帧。交互反馈依旧存在，
     * 不会出现"点了没反应"。
     */
    // 6.3.2：420 → 280。420ms 的补间在连续操作（切歌、开关设置项、划页）时会被读成
    // "慢半拍"；280ms 仍在"看得见过程"的区间内，但手感立刻跟手。
    private const val DURATION_FULL = 280
    private const val DURATION_REDUCED = 170
    // 6.3：基准时长也跟着「动画速度」倍率走，否则速度只影响弹簧、不影响淡入淡出，
    // 快档下会出现「元素已经弹到位、底色还在慢慢淡」的分裂感。
    private val baseDuration get() = ms(if (reduced) DURATION_REDUCED else DURATION_FULL)

    /** 开关拨钮滑动：始终用弹簧（定长 tween 的拨钮"到点停住"，弹簧才有余势） */
    fun knob(): FiniteAnimationSpec<Float> = when {
        reduced -> spring(dampingRatio = 0.95f, stiffness = k(1200f))
        else -> spring(
            dampingRatio = styleDamping(0.40f, 0.85f, 0.28f),
            stiffness = k(styleStiffness(200f, 700f, 150f))
        )
    }

    /** 卡片/元素位移动 */
    fun move(): FiniteAnimationSpec<Float> = when {
        reduced -> tween<Float>(baseDuration)
        style == MotionStyle.SMOOTH -> tween<Float>(ms(200))
        style == MotionStyle.BOUNCY -> spring(dampingRatio = 0.45f, stiffness = k(150f))
        jelly -> spring(dampingRatio = 0.72f, stiffness = k(320f))
        else -> tween<Float>(baseDuration)
    }
    fun moveDp(): FiniteAnimationSpec<Dp> =
        // 返回类型是 FiniteAnimationSpec<Dp>，注解里的 Dp 已经能推断，无需显式泛型
        when {
            reduced -> tween(baseDuration)
            style == MotionStyle.SMOOTH -> tween(ms(200))
            style == MotionStyle.BOUNCY -> spring(dampingRatio = 0.45f, stiffness = k(150f))
            jelly -> spring(dampingRatio = 0.72f, stiffness = k(320f))
            else -> tween(baseDuration)
        }
    /** 出现/展开（面板、区块）
     *
     * 这一轮起这条**永远带过冲**：用户反馈「整个果冻特效都应该有一个慢慢的回弹效果，
     * 不管在哪儿、包括打开最近播放的时候」。以前果冻关掉时这里是 `tween(300)`——
     * 匀速淡入到位就完事，界面上没有一个地方会"弹一下"，全站读起来是硬的。
     * 现在两档都有回弹，只是果冻档更软更慢；省电/低端机才换成临界阻尼（少算帧）。
     */
    fun appear(): FiniteAnimationSpec<Float> = when {
        reduced -> spring(dampingRatio = 1f, stiffness = k(900f))
        // 6.6：果冻＝软而荡；顺滑＝接近临界、只留一点点余势；超弹＝最软最荡
        else -> spring(
            dampingRatio = styleDamping(0.40f, 0.80f, 0.28f),
            stiffness = k(styleStiffness(95f, 260f, 70f))
        )
    }

    /**
     * 弹层 / 全屏页 / 抽屉的入场：位移用的弹簧（比 [appear] 更慢、过冲更看得清）。
     *
     * 这些都是"打开一整块东西"的运动：距离大，所以弹簧要软。ζ<1 会冲过目标位置
     * 再收回来，观感就是慢慢回弹的果冻；刚度压到 200 出头，全程约 0.6~0.9s，
     * 短于这个就会又变成"啪地贴上去"。
     */
    fun sheetEnter(): FiniteAnimationSpec<Float> = when {
        reduced -> spring(dampingRatio = 1f, stiffness = k(1000f))
        else -> spring(
            dampingRatio = styleDamping(0.42f, 0.82f, 0.30f),
            stiffness = k(styleStiffness(95f, 280f, 72f))
        )
    }

    /**
     * [appear] / [sheetEnter] 那两条弹簧大致**收敛完**的时间（ms）。
     *
     * R12 把弹簧整体放慢之后，搜索页那条"等动画结束再写数据"的延迟不能还是 520ms——
     * 数据会掉在动画中间，把上一轮刚修好的"一卡一卡"又请回来。这里给一个跟着
     * 当前档位走的数：果冻最慢、省电最快，调用方只写 delay(JunMotion.appearSettleMs)。
     */
    val appearSettleMs: Int
        get() = ms(when {
            reduced -> 260
            style == MotionStyle.BOUNCY -> 1_500
            jelly -> 1_100
            else -> 620
        })

    /** 轻提示入场（Float 版，给 scaleY/alpha 这类标量用） */
    fun toastEnter(): FiniteAnimationSpec<Float> = when {
        reduced -> spring(dampingRatio = 1f, stiffness = k(1000f))
        else -> spring(
            dampingRatio = styleDamping(0.38f, 0.80f, 0.26f),
            stiffness = k(styleStiffness(150f, 420f, 110f))
        )
    }

    /**
     * 轻提示入场（IntOffset 版，给 slideInVertically 用）。
     *
     * Compose 的 spring() 是按**期望类型**重载的：Float 和 IntOffset 是两个不同的 @JvmName
     * 版本，参数完全一样，但返回类型不通用，所以这里成对给出，别让调用方自己写数字。
     */
    fun toastEnterOffset(): FiniteAnimationSpec<IntOffset> = when {
        reduced -> spring(dampingRatio = 1f, stiffness = k(1000f))
        else -> spring(
            dampingRatio = styleDamping(0.38f, 0.80f, 0.26f),
            stiffness = k(styleStiffness(150f, 420f, 110f))
        )
    }

    /**
     * 覆盖层入场（淡入 + 轻微放大）的单一真源。
     *
     * 原来九处弹层各写一遍 `if (JunMotion.jelly) fadeIn + scaleIn else fadeIn(tween(200))`，
     * 果冻关掉就完全没回弹。调用方只写 `enter = JunMotion.overlayEnter()`，
     * 参数（软/慢/过冲）留在这里调，一处改全站生效。
     */
    @Composable
    fun overlayEnter(initialScale: Float = 0.94f): EnterTransition =
        fadeIn(appear()) + scaleIn(initialScale = initialScale, animationSpec = appear())

    // ===== 动效令牌：全 App 共用的一套弹簧词汇 =====
    //
    // 原先各处动画时长/曲线各写一份（tween(300)、tween(260)、spring(0.5,380)…），
    // 观感上"每个地方都快一点点，但快到不一样"。这里统一成有物理含义的五个令牌，
    // 组件只表达"我属于哪一类运动"，不自己挑参数。
    //
    // 词汇参考主流手势实现的双弹簧结构：跟手的「值」用极高刚度、零过冲（指到哪就到哪，
    // 不晃）；松手后的「速度交接」用中等刚度的弹簧（有轻微跟随余量，但不停在原地抖）。

    /** 跟手位移：刚度极高、零过冲。手指停，画面就停。 */
    fun track(): FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = k(1000f))

    /** 松手后的速度/惯性衰减：中等刚度、略微欠阻尼，让手势有"余势"。 */
    fun glide(): FiniteAnimationSpec<Float> = when {
        reduced -> spring(dampingRatio = 1f, stiffness = k(1000f))
        else -> spring(
            dampingRatio = styleDamping(0.42f, 0.85f, 0.30f),
            stiffness = k(styleStiffness(160f, 420f, 120f))
        )
    }

    /** 按压时横向收缩的响应（比纵向略快，形成"被压扁"的错位感）。 */
    /**
     * 按压回弹（横向）。
     *
     * 6.3.2 调参：dampingRatio 0.26 / 0.40、stiffness 150 / 200 这一组是"果冻感"最强的一档，
     * 但它收敛要 0.6~0.8 秒、还会来回荡三四下——手指已经抬起来了，按钮还在抖，
     * 主观上就是"不够跟手、有点拖"。现在把 stiffness 提到 420/520（收敛 ~0.25s），
     * 阻尼比降到 0.62/0.72：只保留一次轻微的过冲，形状仍是 Q 弹，但绝不拖尾。
     */
    fun squishX(): FiniteAnimationSpec<Float> =
        if (reduced) spring(dampingRatio = 1f, stiffness = k(1000f))
        else if (jelly) spring(dampingRatio = 0.55f, stiffness = k(420f))
        else spring(dampingRatio = 0.68f, stiffness = k(520f))

    /** 按压时纵向收缩的响应（略阻尼，回弹时比横向慢半拍）。 */
    fun squishY(): FiniteAnimationSpec<Float> =
        if (reduced) spring(dampingRatio = 1f, stiffness = k(800f))
        else if (jelly) spring(dampingRatio = 0.62f, stiffness = k(380f))
        else spring(dampingRatio = 0.74f, stiffness = k(470f))

    /**
     * 弹层"从手指落点弹出来"的主弹簧：明显欠阻尼 → 冲出一点点再**慢慢**收回。
     *
     * 这就是"Q 弹"的全部秘密：入场不是淡入到位就完事，而是**过冲再回落**，
     * 观感上像被按出来的果冻。
     * 这一轮把刚度从 520 降到 300~360（用户："整个果冻特效都应该有一个慢慢的回弹效果"）——
     * 过冲幅度差不多，但收回的过程慢了一倍，看得清"弹回来"这件事本身。
     * 省电 / 老机型仍换成临界阻尼，只保留极轻的浮现。
     */
    fun pop(): FiniteAnimationSpec<Float> =
        if (reduced) spring(dampingRatio = 1f, stiffness = k(1100f))
        else if (jelly) spring(dampingRatio = 0.30f, stiffness = k(140f))
        else spring(dampingRatio = 0.42f, stiffness = k(175f))

    /** 弹层内条目的错峰浮现（比整体 pop 略快一点点，否则整屏要等最后一行）。 */
    fun popItem(): FiniteAnimationSpec<Float> =
        if (reduced) spring(dampingRatio = 1f, stiffness = k(1200f))
        else if (jelly) spring(dampingRatio = 0.42f, stiffness = k(170f))
        else spring(dampingRatio = 0.55f, stiffness = k(210f))

    /** 高亮/选中态的浮现（不着急，慢弹簧更像材质在亮）。 */
    fun highlight(): FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.5f, stiffness = k(if (reduced) 600f else 180f))

    /**
     * 歌词跟踪：临界阻尼弹簧（ζ≈1.04，理论上刚好不过冲）。
     *
     * 用 tween 的问题是"每句都走满 620ms"——连续快句时上一句还没到位就开始下一句，
     * 视觉上整列在漂。临界阻尼弹簧按剩余距离收敛：近的小步快收、远的一口气滑过去，
     * 而且不会越过头再拉回来。省电模式下把刚度提高，收敛更快、要算的帧更少。
     */
    fun lyricFollow(): FiniteAnimationSpec<Float> =
        if (reduced) spring(dampingRatio = 1f, stiffness = k(900f))
        else spring(dampingRatio = 1.04f, stiffness = k(180f))

    /** 歌词行的缩放/淡入：和滚动同一条物理响应，整列读起来像一个物体。
     *  6.9.5：用户反馈歌词页"太抖"——ζ=0.78 的过冲（每行到位时弹一下）在
     *  连续换行时叠成肉眼可见的抖动，收敛到 ζ=0.95（几乎无过冲，只留一点软感）。 */
    fun lyricLineAppear(): FiniteAnimationSpec<Float> =
        if (reduced) spring(dampingRatio = 1f, stiffness = k(1000f))
        else spring(dampingRatio = 0.95f, stiffness = k(240f))

    /**
     * 歌词行「按距离错峰亮起」：离焦点越远、弹簧越软（刚度越低），到位就越晚。
     *
     * 原本是用 tween 的 delayMillis 做错落——那是"假装"有先后，每行还是同一曲线同长度。
     * 换成随距离变化的刚度后，先后是物理结果：当前行几乎立刻到位，外圈慢半拍追上来，
     * 整列像被推了一下而不是齐步走；而且轻微过冲让焦点行有一个很软的"弹一下"。
     */
    fun lyricRipple(dist: Int): FiniteAnimationSpec<Float> =
        if (reduced) spring(dampingRatio = 1f, stiffness = k(900f))
        else spring(
            // 6.9.5：ζ 0.85→0.95 —— 错峰过冲与行入场过冲叠加是"歌词页抖动"的
            // 第二来源，统一收敛掉过冲，错峰的先后感保留（由刚度差决定）。
            dampingRatio = 0.95f,
            stiffness = k((260f - dist.coerceIn(0, 6) * 18f).coerceAtLeast(120f))
        )

    /**
     * 分页吸附（小播放器 ↔ 播放页）。PagerState 不接受初速度，
     * 因此动量体现在"选哪一档吸附阈值"上（见 MiniPlayerBar 的松手判定），而不是曲线本身。
     *
     * 6.0 把这条明显拉长（用户："过渡动画拉长一点，要有非常明显的过渡，看着舒服"）：
     * 框架默认用的是 `spring(ζ=1, k=1500)`，整页不到 200ms 就到位，封面/歌词是"啪"地
     * 换过去的，看不出过程。这里 stiffness 降到 260（约慢 √(1500/260)≈2.4 倍），
     * 果冻模式下再给一点欠阻尼（ζ=0.86），播放页归位时会轻轻冲过一点再落回。
     * 省电/减弱动画下不拉长，只保留临界阻尼，避免"卡了一下"的错觉。
     */
    fun pagerSnap(): FiniteAnimationSpec<Float> = spring(
        // 6.3.2：stiffness 150/190 → 420/520。
        // 原来那一档是 6.0 特意放慢的（"过渡动画拉长一点"），但用户这次的反馈是
        // 「一个手指根本划不过去，要很大幅度」——曲线太软时，手指松开后页面还要慢慢
        // 挪半秒才到位，主观上就是"没跟手 + 划不动"。现在保留轻微过冲（ζ=0.78），
        // 但收敛时间压到约 0.35s，手感是"跟手一推就翻过去，落定有一下 Q 弹"。
        // 6.7：420/520 → 300/380。用户反馈"点开小播放器到大播放器没有效果"——
        // 420 的刚度在点按（非拖动）场景下 0.2 秒内就贴到位，形变根本来不及看见。
        // 点按与拖动共用这一条，但拖动有跟手进度垫底，慢一点不损害跟手感。
        dampingRatio = if (jelly && !reduced) 0.78f else 0.92f,
        stiffness = k(if (reduced) 1100f else if (jelly) 360f else 470f)
    )

    /**
     * 弹层松手回位：吸收向下甩的动量再收回来，不"急刹"。
     * 速度请作为 `initialVelocity` 传给 `Animatable.animateTo(target, JunMotion.sheetSettle(), iv)`。
     */
    fun sheetSettle(): FiniteAnimationSpec<Float> = spring(
        dampingRatio = if (reduced) 1f else if (jelly) 0.48f else 0.68f,
        stiffness = k(if (reduced) 1400f else if (jelly) 180f else 260f)
    )

    /** 弹层关闭：临界阻尼，速度越快出去越快，但不会过冲到底部以外再弹回。 */
    fun sheetDismiss(): FiniteAnimationSpec<Float> = spring(
        dampingRatio = 1f,
        stiffness = k(if (reduced) 1800f else 1000f)
    )

    /**
     * 把手势末端速度换算成 `animateTo` 的 initialVelocity。
     *
     * Compose 的弹簧初速度是**归一化**的：1 表示"一秒走完 from→target 的全程"，
     * 所以不能直接把 px/s 塞进去（那是几千，弹簧会先飞出去再拽回来）。
     * [delta] = target - from（带符号，px），[velocity] = 末端速度（px/s，同号为正）。
     */
    fun springVelocity(delta: Float, velocity: Float): Float {
        if (kotlin.math.abs(delta) < 1f) return 0f
        // 直接带符号相除：速度方向与 from→target 一致时为正（加快到位），相反时为负
        // （先带出一点顺势余量再收回来）——这正是"松手不急停"的手感来源。
        return (velocity / delta).coerceIn(-6f, 6f)
    }
}

/** [junPressable] 的触感强度档位，对应 [Haptics] 的三档。 */
enum class PressFeedback { CLICK, TAP, NONE }

/**
 * 可按压元素的统一交互封装：按压缩放 + 触感 + 无水波点击。
 *
 * 项目里这套样板原本在 9 处逐字重写（PlayerCard 三处、MainScreen 五处、
 * SongDeck/PosterWall 若干）：每处都要自己 remember 一个 InteractionSource、
 * collectIsPressedAsState、animateFloatAsState、再手写 graphicsLayer 和
 * `indication = null`。散开写的代价不只是行数——按压幅度在各处从 0.82 到 0.94
 * 飘了五个值，手感并不统一，改动效果时要逐个文件找。
 *
 * 收拢成 Modifier 后：单一真源、缩放系数集中可调、触感档位显式声明。
 *
 * @param scaleDown 按下时缩到的比例，默认 [PRESS_SCALE_DEFAULT]
 * @param feedback 触感档位，主控制用 CLICK、次级用 TAP
 * @param enabled 为 false 时不响应点击也不缩放
 */
@Composable
fun Modifier.junPressable(
    scaleDown: Float = PRESS_SCALE_DEFAULT,
    feedback: PressFeedback = PressFeedback.TAP,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 横纵分开收：两条不同响应的弹簧让"按下"不再是一个等比缩放，
    // 而是先被压扁、再慢慢鼓回来。松手时横向先回位、纵向慢半拍，
    // 这半拍之差就是"软"的来源；幅度和方向都没变，布局不受影响。
    val target = if (pressed && enabled) scaleDown else 1f
    val sx by animateFloatAsState(target, JunMotion.squishX(), label = "junPressScaleX")
    val sy by animateFloatAsState(target, JunMotion.squishY(), label = "junPressScaleY")
    return this
        .graphicsLayer { scaleX = sx; scaleY = sy }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled
        ) {
            when (feedback) {
                PressFeedback.CLICK -> Haptics.click()
                PressFeedback.TAP -> Haptics.tap()
                PressFeedback.NONE -> Unit
            }
            onClick()
        }
}

/** 主控制按钮按压幅度：大按钮缩得多一点才有回弹感 */
const val PRESS_SCALE_STRONG = 0.80f

/** 通用按压幅度：图标按钮、开关、条目 */
const val PRESS_SCALE_DEFAULT = 0.88f

/** 长条目的按压幅度：整行缩得多会显得"抖"，0.96 只够读出一次柔软的沉降 */
const val PRESS_SCALE_ROW = 0.965f

/**
 * 整行的「按压 + 长按」封装：[junPressable] 的长按版。
 *
 * 歌曲行、歌单行这类既要点击播放、又要长按进多选的元素，原本各自手写
 * `combinedClickable(indication = null)`，因此全 App 唯一一批没有按压反馈的元素就是列表行：
 * 手指按下去只有底色、没有形变，和同一个弹层里会回弹的按钮明显是两套材质。
 * 这里把行也纳入同一套弹簧（幅度用 [PRESS_SCALE_ROW]，行很宽，等比缩放会被读成"整屏跳"，
 * 因此横纵分开、幅度压到很小）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.junPressableRow(
    scaleDown: Float = PRESS_SCALE_ROW,
    feedback: PressFeedback = PressFeedback.TAP,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val target = if (pressed) scaleDown else 1f
    val sx by animateFloatAsState(target, JunMotion.squishX(), label = "junRowScaleX")
    val sy by animateFloatAsState(target, JunMotion.squishY(), label = "junRowScaleY")
    return this
        .graphicsLayer { scaleX = sx; scaleY = sy }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = {
                when (feedback) {
                    PressFeedback.CLICK -> Haptics.click()
                    PressFeedback.TAP -> Haptics.tap()
                    PressFeedback.NONE -> Unit
                }
                onClick()
            },
            // 长按为空时不传：那样 long press 什么都不做，也不会白震一下
            onLongClick = onLongClick?.let {
                {
                    Haptics.click()
                    it()
                }
            }
        )
}

/**
 * 只做按压缩放、不接管点击的变体：给手势自己已经写了 pointerInput / combinedClickable
 * 的元素用（把 scale 交出来，click 留在原处），避免出现两层点击。
 */
@Composable
fun Modifier.junPressSpring(pressed: Boolean, scaleDown: Float = PRESS_SCALE_DEFAULT): Modifier {
    val sx by animateFloatAsState(if (pressed) scaleDown else 1f, JunMotion.squishX(), label = "junSpringScaleX")
    val sy by animateFloatAsState(if (pressed) scaleDown else 1f, JunMotion.squishY(), label = "junSpringScaleY")
    return this.graphicsLayer { scaleX = sx; scaleY = sy }
}

/**
 * 底部弹层统一入场：自底部上滑归位。
 * 替代各 Sheet 里各自手写一遍的 `Animatable(1f) + animateTo(0f, tween(300, FastOutSlowIn))
 * + graphicsLayer{ translationY = slide.value * 160.dp.toPx() }`——单一真源，观感统一。
 * 用法：把面板容器的 graphicsLayer 位移那一行换成 `.junSheetSlideIn()`。
 */
@Composable
fun Modifier.junSheetSlideIn(offsetDp: Dp = 160.dp): Modifier {
    val slide = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        // 这一轮起用 sheetEnter()（欠阻尼、软）：面板推上来时会**冲过一点再慢慢落回**，
        // 打开最近播放 / 设置 / 队列 / 加入歌单这一整类弹层都是这一下。
        // 以前这里是临界阻尼 ζ=1 + 刚度 700：匀速贴上去，全程没有一次回弹。
        // 省电 / 低端机换回临界阻尼、刚度更高（要算的帧更少），这一处仍然比定长 tween 自然。
        slide.animateTo(0f, JunMotion.sheetEnter())
    }
    return this.graphicsLayer { translationY = slide.value * offsetDp.toPx() }
}

/**
 * 弹层「从手指落点鼓出来」：按 ⋮ 的位置缩放浮现，而不是从屏幕底部匀速推上来。
 *
 * 这就是"点哪里、菜单就从哪里长出来"的物理来源：[anchorProvider] 给出按压点在
 * **面板自身坐标系**里的位置（由 [JunSheet] 换算），把它当作 graphicsLayer 的缩放支点。
 * 弹簧 ζ=0.42 明显欠阻尼，所以面板会冲过实际尺寸一点点再收回——观感上是果冻被按了一下
 * 弹回来。
 *
 * 落点在面板外时钳到最近边缘：歌曲行的 ⋮ 在面板上方，纵向因此钳到 0，
 * 面板读起来就像从那颗按钮的正下方鼓出来。
 *
 * 为什么传 lambda 而不是 Offset：面板在根坐标系的位置要到布局阶段才测得，而第一帧就要
 * 开始缩放。直接传值会让首帧拿到未布局的 (0,0)，第二帧支点突然挪位——观感是"跳一下"。
 * graphicsLayer 的 lambda 在绘制阶段执行（同一帧、布局之后），读到的就是真实落点。
 */
@Composable
fun Modifier.junPopFrom(
    anchorProvider: () -> Offset,
    scaleFrom: Float = 0.82f,
    riseDp: Dp = 26.dp
): Modifier {
    val slide = remember { Animatable(0f) }
    LaunchedEffect(Unit) { slide.animateTo(1f, JunMotion.pop()) }
    return this.graphicsLayer {
        val v = slide.value
        // 弹簧会过冲到 1 以上：alpha 钳住，缩放/位移不钳（过冲就是那点"弹一下"）
        alpha = v.coerceIn(0f, 1f)
        val s = scaleFrom + (1f - scaleFrom) * v
        scaleX = s
        scaleY = s
        translationY = (1f - v) * riseDp.toPx()
        val w = size.width
        val h = size.height
        val a = anchorProvider()
        transformOrigin = if (w <= 0 || h <= 0) TransformOrigin(0.5f, 0f)
        else TransformOrigin(
            (a.x / w).coerceIn(0f, 1f),
            (a.y / h).coerceIn(0f, 1f)
        )
    }
}

/**
 * 居中弹层统一入场：从中心轻微缩放 + 淡入浮现，替代底部上滑。
 * 用于改为居中对话框的弹层（如歌单管理）：不再从屏幕底部推上来，而是停在画面中央。
 */
@Composable
fun Modifier.junCenterIn(scaleFrom: Float = 0.92f): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, JunMotion.pop()) }
    return this.graphicsLayer {
        val v = a.value
        alpha = v.coerceIn(0f, 1f)
        val s = scaleFrom + (1f - scaleFrom) * v
        scaleX = s
        scaleY = s
        transformOrigin = TransformOrigin(0.5f, 0.5f)
    }
}

/** 弹层条目错峰的间隔（ms）：不是硬凑的延时动画，见 [junPopItem] */
private const val POP_ITEM_STAGGER_MS_BASE = 34L

/** 错峰间隔也要跟速度走：慢档下每行隔 54ms 才有「一行一行掀开」的感觉 */
private val popItemStaggerMs: Long get() = (POP_ITEM_STAGGER_MS_BASE * JunMotion.timeScale).toLong().coerceAtLeast(0L)

/**
 * 弹层里的条目按序号错峰浮现：越靠后的行起步越晚，整块菜单像被"掀开"而不是齐步出现。
 *
 * 和歌词的 [JunMotion.lyricRipple] 同一个思路——先后来自延迟 + 同一条弹簧，
 * 但这里的延迟是入场序列（条目在面板内位置固定），所以直接按 index 算。
 * 省电模式跳过错峰：一次性到位，省掉一串还在跑的动画协程。
 */
@Composable
fun Modifier.junPopItem(index: Int, slipDp: Dp = 12.dp): Modifier {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val stagger = if (JunMotion.reduced) 0L else index * popItemStaggerMs
        if (stagger > 0L) delay(stagger)
        t.animateTo(1f, JunMotion.popItem())
    }
    return this.graphicsLayer {
        val v = t.value
        alpha = v.coerceIn(0f, 1f)
        val s = 0.9f + 0.1f * v
        scaleX = s
        scaleY = s
        translationY = (1f - v) * slipDp.toPx()
        transformOrigin = TransformOrigin(0.5f, 0f)
    }
}

/**
 * 底部弹层「拖把手下滑关闭」的共享状态。
 *
 * 面板根挂 [sheetDragTranslate]（背景随手指一起位移），把手挂 [sheetDragHandle]
 * （按住拖动 + 松手回弹/关闭）。下滑过阈值或向下快甩即关闭，否则弹簧归位。
 */
class SheetDragState internal constructor() {
    /** 当前下滑位移（px）。0 = 归位，越大越往下。普通状态，便于在手势里直接写。 */
    var offset by mutableFloatStateOf(0f)
    /** 关闭需要滑动的距离 = 面板高度，由 [sheetDragTranslate] 测得后写入。 */
    var dismissPx: Float = Float.MAX_VALUE / 4f
}

@Composable
fun rememberSheetDragState(): SheetDragState = remember { SheetDragState() }

/**
 * 面板根：在已有入场动画之外，再叠一层「拖把手」带来的位移。
 *
 * 必须挂在 [com.jun.musiccloud.ui.theme.junSurface] **之前**：graphicsLayer 的 translationY
 * 只平移它内侧的绘制，junSurface 画的卡片背景在外侧就不会跟着走——位移放它后面就会出现
 * "文字先下滑、卡片背景不动"。放在前面，平移图层才包住背景 + 内容整体。
 */
@Composable
fun Modifier.sheetDragTranslate(state: SheetDragState): Modifier = this
    .onSizeChanged { if (it.height > 0) state.dismissPx = it.height.toFloat() }
    .graphicsLayer { translationY = state.offset }

/** 下滑比例超过这个值就关闭 */
private const val SHEET_DISMISS_FRACTION = 0.30f
/** 向下瞬时速度超过这个值（px/s）判定为快甩关闭 */
private const val SHEET_FLING_VELOCITY = 1500f

/**
 * 把手：按住上下拖可拉着面板走，松手过阈值/快甩即关闭，否则回弹。
 *
 * 手势里直接写 [SheetDragState.offset]（普通状态，非受限挂起，可在受限作用域里直接赋值），
 * 收尾动画在 launch 出来的非受限协程里用 Animatable 跑。
 */
@Composable
fun Modifier.sheetDragHandle(
    state: SheetDragState,
    onDismiss: () -> Unit
): Modifier {
    val dismiss = rememberUpdatedState(onDismiss)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return this
        .pointerInput(state) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                // 关闭残留保护：极快"关闭又立即重开"时 AnimatedVisibility 的 fadeOut 还没走完、
                // composition 未销毁，offset 会停在上次的 dismissPx。这里检测到就归零，
                // 否则按下瞬间卡片还停在屏幕外。正常打开时 offset=0，不触发。
                if (state.offset >= state.dismissPx) state.offset = 0f
                var lastY = 0f
                var lastT = 0L
                var velocity = 0f
                var lastDown = true
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    val down = change.pressed
                    val y = change.position.y
                    val t = change.uptimeMillis
                    if (down) {
                        if (lastDown) {
                            lastY = y; lastT = t; lastDown = false
                        } else {
                            val dy = y - lastY
                            if (t > lastT) velocity = dy * 1000f / (t - lastT)
                            // 只允许往下拉，往上钳到 0
                            state.offset = (state.offset + dy).coerceAtLeast(0f)
                            lastY = y; lastT = t
                        }
                        change.consume()
                    } else {
                        break
                    }
                }
                val start = state.offset
                val threshold = state.dismissPx * SHEET_DISMISS_FRACTION
                // 松手瞬间把速度交给弹簧：initialVelocity 用「剩余距离/秒」归一化，
                // 因此动画从手势速度接着走，而不是先急停再从零加速——那正是"拖的时候跟手、
                // 松手突然一顿"的来源。往回拖（velocity<0）时会带出一个很短的顺势余量。
                if (start >= threshold || velocity >= SHEET_FLING_VELOCITY) {
                    scope.launch {
                        Animatable(start).animateTo(
                            state.dismissPx,
                            JunMotion.sheetDismiss(),
                            JunMotion.springVelocity(state.dismissPx - start, velocity)
                        ) { state.offset = value }
                        // 触发关闭后不要重置 offset：AnimatedVisibility 的 fadeOut 退出动画
                        // 还要渲染 180ms，节点此时仍存活。若这里立刻归零，卡片会从屏幕底
                        // 弹回原位再淡出 —— 就是"弹下去还闪一下"。让它停在屏幕外随 fadeOut
                        // 淡掉即可；下次打开是全新 composition，remember 的 offset 自然回到 0。
                        dismiss.value()
                    }
                } else {
                    scope.launch {
                        Animatable(start).animateTo(
                            0f,
                            JunMotion.sheetSettle(),
                            JunMotion.springVelocity(0f - start, velocity)
                        ) { state.offset = value }
                    }
                }
            }
        }
}

/**
 * 底部弹层的可拖拽把手：一条居中圆角药丸 + 上下撑高的命中区，按住下滑关闭面板。
 *
 * 直接把它放在面板内容顶部即可——它自带足够的纵向命中区（[hitHeight]），
 * 不再需要外面再套一层 Box 去撑高度。[pillColor] 决定药丸颜色（跟随卡片底色）。
 */
@Composable
fun SheetDragHandle(
    state: SheetDragState,
    onDismiss: () -> Unit,
    pillColor: Color,
    modifier: Modifier = Modifier,
    hitHeight: Dp = 24.dp
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(hitHeight)
            .sheetDragHandle(state, onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(pillColor)
        )
    }
}