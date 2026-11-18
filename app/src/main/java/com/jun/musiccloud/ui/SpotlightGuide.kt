package com.jun.musiccloud.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.readableTextOn
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 聚光灯锚点的 key：上栏各入口 + 搜索框 */
internal object SpotlightKeys {
    const val SEARCH = "sp_search"
    const val PLAYLIST = "sp_playlist"
    const val LAYOUT = "sp_layout"
    const val AVATAR = "sp_avatar"
    const val SETTINGS = "sp_settings"
    const val MINI_PLAYER = "sp_mini_player"
    /** 「我的」抽屉里的账号资料区（登录 / 注册入口） */
    const val ACCOUNT = "sp_account"
    /** 「我的」抽屉里的「听歌待办」行 */
    const val TODO = "sp_todo"
    /** 歌单弹层里的「导入歌单」入口 */
    const val IMPORT_ENTRY = "sp_import_entry"
    /** 歌单弹层展开后的粘贴输入框 */
    const val IMPORT_INPUT = "sp_import_input"
    /** 「我的」抽屉里的「图书馆模式」开关 */
    const val LIBRARY_MODE = "sp_library_mode"
    /** 首页「推荐歌单」抬头（标题 + 换一批 + 更多） */
    const val HOME_FEED = "sp_home_feed"
    /** 歌曲行末尾的 ⋮ 按钮 */
    const val MORE_MENU = "sp_more_menu"
    /** 搜索页结果分类那一排 chip */
    const val SEARCH_TABS = "sp_search_tabs"
    /** 播放页的音质徽章 */
    const val QUALITY = "sp_quality"
    /** 6.3：上栏第一枚「听歌排行榜」（金色奖杯） */
    const val RANK = "sp_rank"
}

/**
 * 锚点登记中心：各界面元素把自己在根坐标系里的矩形报上来，聚光灯照着这个矩形挖洞。
 *
 * 用 mutableStateMapOf 而不是普通 HashMap：写入发生在布局之后，遮罩那一帧必须立刻重组
 * 才能拿到矩形；普通 Map 的写入不会通知 Compose，洞就永远挖不出来。
 */
internal class SpotlightRegistry {
    private val anchors = mutableStateMapOf<String, Rect>()

    operator fun set(key: String, rect: Rect) {
        // R14（流畅性）：同一个锚点每次布局都会 boundsInRoot() 出一个**内容相同、实例不同**的矩形，
        // 而 mutableStateMapOf 是「只要 put 就作废读它的那一处」，不看值相不相等——
        // 于是列表每滚一下、外面每布局一次，遮罩就被拉去重组一遍，导览期间白掉帧。
        // 先按值比一次：矩形真的变了才写。跟手是靠这里触发的重组，所以不能改成不写，
        // 只能把"没变的写"省掉。
        if (anchors[key] == rect) return
        anchors[key] = rect
    }

    fun rectOf(key: String): Rect? = anchors[key]
}

internal val LocalSpotlightRegistry = staticCompositionLocalOf { SpotlightRegistry() }

/**
 * 把目标元素登记成聚光灯锚点。
 *
 * 用 boundsInRoot 取根坐标：遮罩是铺满根 Box 的，两者必须同一坐标系才能对上。
 * 零宽/零高的矩形不上报——元素还没布局完时上报会把洞挖成一个点。
 */
@Composable
internal fun Modifier.spotlightAnchor(key: String): Modifier {
    val registry = LocalSpotlightRegistry.current
    return this.onGloballyPositioned { coords ->
        val r = coords.boundsInRoot()
        if (r.width > 0f && r.height > 0f) registry[key] = r
    }
}

/**
 * 只登记「此刻真的在屏幕里」的元素。
 *
 * 歌曲行、播放页卡片这类控件会在好几个地方同时存在（HorizontalPager 的邻页也参与布局、
 * deck 与列表两种排布各有一份）。用普通 [spotlightAnchor] 的话，谁最后布局谁说了算，
 * 洞可能飘到屏幕外面；这里按中心点过滤，只让屏幕上那一份登记。
 */
@Composable
internal fun Modifier.spotlightAnchorIfVisible(key: String): Modifier {
    val registry = LocalSpotlightRegistry.current
    val density = LocalDensity.current
    val cfg = LocalConfiguration.current
    val screenW = with(density) { cfg.screenWidthDp.dp.toPx() }
    val screenH = with(density) { cfg.screenHeightDp.dp.toPx() }
    return this.onGloballyPositioned { coords ->
        val r = coords.boundsInRoot()
        if (r.width <= 0f || r.height <= 0f) return@onGloballyPositioned
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        if (cx >= 0f && cx <= screenW && cy >= 0f && cy <= screenH) registry[key] = r
    }
}

/** 镂空形状：图标按钮用圆，搜索框这类长条用圆角矩形 */
internal enum class SpotlightShape { CIRCLE, ROUND_RECT }

internal data class SpotlightStep(
    val anchorKey: String,
    val title: String,
    val body: String,
    val shape: SpotlightShape = SpotlightShape.CIRCLE,
    /** 气泡优先放在洞的下侧；洞靠近屏幕下缘时自动翻到上侧。false = 始终放上侧 */
    val bubbleBelow: Boolean = true
)

/** Rect 的四维向量转换器：让洞的位置/大小能用一个动画一起插值 */
private val RectConverter = TwoWayConverter<Rect, AnimationVector4D>(
    convertToVector = { AnimationVector4D(it.left, it.top, it.right, it.bottom) },
    convertFromVector = { Rect(it.v1, it.v2, it.v3, it.v4) }
)

/**
 * 聚光灯引导。
 *
 * 全局半黑遮罩 + 目标镂空 + 呼吸描边 + 气泡讲解；多步之间洞的位置与大小平滑过渡，
 * 支持随时跳过，最后一步结束遮罩整体淡出。
 *
 * 挖洞用 BlendMode.Clear：整屏先涂黑，再把洞的形状以 Clear 混合模式擦掉。
 * 必须开 CompositingStrategy.Offscreen——默认 Auto 策略下 Clear 会穿透到下层内容，
 * 把底下的界面也一起擦成透明。
 */
@Composable
internal fun SpotlightGuide(
    steps: List<SpotlightStep>,
    colors: JunColors,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    /**
     * 每一步开始时的回调：某一步要照亮的元素不在当前界面上（例如「我的」抽屉里的
     * 登录入口），由调用方在这一步把对应界面打开，聚光灯才有东西可照。
     */
    onStepChange: (Int) -> Unit = {}
) {
    val registry = LocalSpotlightRegistry.current
    val density = LocalDensity.current
    val screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val screenH = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val scope = rememberCoroutineScope()

    var index by remember { mutableIntStateOf(0) }
    // 步骤表会在导览走到一半时变短：FeatureTour 的 steps 是 remember(netease, hasSongRows,
    // hasCurrentSong)，歌播完、首页被筛空、网易云开关翻转都会少一步。
    // 原来这里直接 steps[index]，走到最后一步时列表缩 1 → IndexOutOfBoundsException 当场崩，
    // 用户看到的就是「跟着导览讲着讲着 App 没了」。先钳住越界，再取不到就走正常收尾。
    LaunchedEffect(steps.size) {
        if (index > steps.lastIndex) index = steps.lastIndex.coerceAtLeast(0)
    }
    val step = steps.getOrNull(index)
    if (step == null) {
        // 列表空了（极端情况：所有开关同时关掉）：直接结束，不拿旧索引硬画
        LaunchedEffect(Unit) { onFinish() }
        return
    }
    // 切步即通知调用方（打开/关闭对应界面），首帧也会跑一次，保证第 0 步状态正确
    LaunchedEffect(index) { onStepChange(index) }

    // 整层淡入淡出：收尾时先淡掉遮罩再回调，避免"啪"一下消失
    val veil = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { veil.animateTo(1f, tween(260)) }

    val pad = with(density) { 9.dp.toPx() }
    val rawRect = registry.rectOf(step.anchorKey)
    val targetRect = if (rawRect == null || rawRect == Rect.Zero) {
        // 锚点还没测到（首次布局未完成）：先落在屏幕中心的一个点上，
        // 等真实矩形上报后由动画平滑"飘"过去，不会闪。
        Rect(Offset(screenW / 2f, screenH / 2f), Size(1f, 1f))
    } else {
        Rect(rawRect.left - pad, rawRect.top - pad, rawRect.right + pad, rawRect.bottom + pad)
    }
    val holeRect by animateValueAsState(
        targetValue = targetRect,
        typeConverter = RectConverter,
        animationSpec = tween(420, easing = EaseInOutCubic),
        label = "spotlightHole"
    )

    // 呼吸描边：三层错开相位，外扩同时淡出，形成持续扩散的呼吸感。
    // 省电动画开着时不再扩散（infinite 动画是持续耗电项），只留一圈静态描边指位置。
    val breath by rememberInfiniteTransition(label = "spotlightBreath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing)),
        label = "breath"
    )

    // 气泡尺寸：测出来后用于「洞靠下时把气泡翻到上方」的精确定位
    var bubbleH by remember { mutableFloatStateOf(0f) }
    var bubbleW by remember { mutableFloatStateOf(0f) }

    val finish: () -> Unit = {
        scope.launch {
            veil.animateTo(0f, tween(220))
            onFinish()
        }
    }
    val next: () -> Unit = {
        Haptics.tap()
        if (index < steps.lastIndex) index++ else finish()
    }

    val accent = colors.primary
    val onVeil = Color.White // 遮罩恒为黑底，文字固定近白，不跟主题底色走

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = veil.value }
    ) {
        // ===== 遮罩 + 镂空 + 呼吸描边 =====
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            drawRect(color = Color.Black.copy(alpha = 0.74f))

            val topLeft = Offset(holeRect.left, holeRect.top)
            val size = Size(holeRect.width.coerceAtLeast(1f), holeRect.height.coerceAtLeast(1f))
            val strokeW = 2.5.dp.toPx()

            if (step.shape == SpotlightShape.CIRCLE) {
                drawCircle(
                    color = Color.Black,
                    radius = size.minDimension / 2f,
                    center = holeRect.center,
                    blendMode = BlendMode.Clear
                )
                val rings = if (JunMotion.reduced) 1 else 3
                for (k in 0 until rings) {
                    val p = if (JunMotion.reduced) 0f else (breath + k / 3f) % 1f
                    val grow = p * 26.dp.toPx()
                    drawCircle(
                        color = accent.copy(alpha = (1f - p) * 0.85f),
                        radius = size.minDimension / 2f + 3.dp.toPx() + grow,
                        center = holeRect.center,
                        style = Stroke(width = strokeW)
                    )
                }
            } else {
                val corner = CornerRadius(size.minDimension * 0.5f, size.minDimension * 0.5f)
                drawRoundRect(
                    color = Color.Black,
                    topLeft = topLeft,
                    size = size,
                    cornerRadius = corner,
                    blendMode = BlendMode.Clear
                )
                val rings2 = if (JunMotion.reduced) 1 else 3
                for (k in 0 until rings2) {
                    val p = if (JunMotion.reduced) 0f else (breath + k / 3f) % 1f
                    val grow = p * 26.dp.toPx()
                    drawRoundRect(
                        color = accent.copy(alpha = (1f - p) * 0.85f),
                        topLeft = Offset(topLeft.x - 4.dp.toPx() - grow, topLeft.y - 4.dp.toPx() - grow),
                        size = Size(size.width + (8.dp.toPx() + grow) * 2f, size.height + (8.dp.toPx() + grow) * 2f),
                        cornerRadius = corner,
                        style = Stroke(width = strokeW)
                    )
                }
            }
        }

        // ===== 点遮罩任意处进入下一步（气泡与底部按钮在它之上，各自消费自己的点击）=====
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(index) { detectTapGestures { next() } }
        )

        // ===== 讲解气泡 =====
        val gap = with(density) { 18.dp.toPx() }
        val edge = with(density) { 16.dp.toPx() }
        val minTop = with(density) { 76.dp.toPx() }
        // 6.7：定位不再只看"洞在上半屏还是下半屏"，而是拿气泡实测高度算上下两边
        // 哪边真放得下。之前的问题：洞在屏幕上缘时（上栏那批锚点），气泡被 minTop
        // 钳到 76px 处，正好压在洞上——用户看到的就是"引导位置不对劲，气泡把
        // 要指的东西盖住了"。现在放不下就自动翻到另一侧，两边都放不下才贴边钳。
        val bottomReserve = with(density) { 96.dp.toPx() } // 底部操作条的高度预留
        val fitsBelow = holeRect.bottom + gap + bubbleH <= screenH - bottomReserve
        val fitsAbove = holeRect.top - gap - bubbleH >= minTop
        val placeBelow = if (step.bubbleBelow) (fitsBelow || !fitsAbove) else (fitsAbove || !fitsBelow)
        val bubbleX = (holeRect.center.x - bubbleW / 2f)
            .coerceIn(edge, (screenW - bubbleW - edge).coerceAtLeast(edge))
        val bubbleY = if (placeBelow) {
            (holeRect.bottom + gap).coerceAtMost(screenH - bottomReserve - bubbleH).coerceAtLeast(minTop)
        } else {
            (holeRect.top - bubbleH - gap).coerceAtLeast(minTop)
        }

        Box(
            Modifier
                .offset { IntOffset(bubbleX.roundToInt(), bubbleY.roundToInt()) }
                .onSizeChanged { bubbleW = it.width.toFloat(); bubbleH = it.height.toFloat() }
                .widthIn(max = 320.dp)
                .heightIn(min = 74.dp)
                // 材质与 App 内卡片同源：实色模式下是卡片色，玻璃模式下是真折射玻璃
                .junSurface(level = GlassLevel.SHEET, colors = colors, shape = JunShape.card)
                // 气泡自己吃掉点击，避免点气泡也跳下一步
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    (fadeIn(tween(200)) + slideInVertically(tween(240)) { it / 4 })
                        .togetherWith(fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it / 4 })
                },
                label = "spotlightBubble"
            ) { i ->
                Column {
                    Text(
                        steps[i].title,
                        color = if (colors.isDark) Color.White else Color.Black,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.height(6.dp))
                    // **重点** 加粗渲染，和更新公告同一套写法
                    Text(
                        buildAnnotatedString {
                            steps[i].body.split("**").forEachIndexed { pi, part ->
                                if (part.isEmpty()) return@forEachIndexed
                                if (pi % 2 == 1) {
                                    withStyle(SpanStyle(fontWeight = FontWeight.Black, color = colors.text)) { append(part) }
                                } else {
                                    append(part)
                                }
                            }
                        },
                        color = colors.subText,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Start
                    )
                }
            }
        }

        // ===== 底部操作条 =====
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "跳过",
                color = onVeil.copy(alpha = 0.72f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .junPressable { onSkip() }
                    .clip(JunShape.item)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .semantics { contentDescription = "跳过引导" }
            )
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                steps.indices.forEach { i ->
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (i == index) 16.dp else 6.dp, height = 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (i == index) accent else onVeil.copy(alpha = 0.32f))
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .junPressable(feedback = PressFeedback.NONE) { next() }
                    .clip(JunShape.item)
                    .background(accent)
                    .padding(horizontal = 18.dp, vertical = 11.dp)
                    .semantics { contentDescription = if (index < steps.lastIndex) "下一步" else "完成引导" }
            ) {
                Text(
                    if (index < steps.lastIndex) "下一步" else "开始使用",
                    color = accent.readableTextOn(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
