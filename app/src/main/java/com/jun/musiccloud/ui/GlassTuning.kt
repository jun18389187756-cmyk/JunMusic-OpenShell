package com.jun.musiccloud.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.player.PlayerViewModel
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunSurfaceStyle
import com.jun.musiccloud.ui.theme.junSurface

/**
 * 6.6：玻璃质感调参。
 *
 * 参考源码（Kyant backdrop 2.0.1）里能抄、且**不挑机型**的三样：
 *   · InteractiveHighlight —— 手指位置的发光光斑
 *   · DampedDragAnimation  —— 按下收缩 / 松手弹簧回弹
 *   · Highlight / InnerShadow —— 边缘高光 + 内阴影（「像真玻璃」的关键）
 *
 * 这里全部用纯 Compose Canvas（drawWithContent + Outline → Path）重写，
 * **不引整库、不用 AGSL / RuntimeShader**：那份库是 Kotlin 2.4 / AGP 9.3 / Compose 1.12
 * 且 lens() 要 API 33，本项目 minSdk 24，整库引会编译不过还会丢掉低端机降级。
 * 抄的是视觉规则，落地仍走项目自己的 junSurface / GlassRuntime。
 *
 * 参数落盘独立 SharedPreferences，Compose State 即时驱动重组：拖动滑杆，
 * 预览卡、自定义中心弹窗、以及全 App 所有走 junSurface 的玻璃面同一帧跟着变。
 */
object GlassTuning {

    private const val PREFS = "jun_glass_tuning"

    /** 边缘高光强度 0..1 */
    val edgeGlow = mutableStateOf(0.55f)

    /** 内阴影强度 0..1 */
    val innerShade = mutableStateOf(0.45f)

    /** 按压回弹幅度 0..1（越大越 Q 弹） */
    val pressSpring = mutableStateOf(0.60f)

    /** 跟手光斑开关 */
    val touchGlow = mutableStateOf(true)

    @Volatile
    private var inited = false
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            appCtx = ctx.applicationContext
            val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            edgeGlow.value = p.getFloat("edge_glow", 0.55f)
            innerShade.value = p.getFloat("inner_shade", 0.45f)
            pressSpring.value = p.getFloat("press_spring", 0.60f)
            touchGlow.value = p.getBoolean("touch_glow", true)
            inited = true
        }
    }

    private fun putFloat(key: String, v: Float) {
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putFloat(key, v)?.apply()
    }

    fun setEdgeGlow(v: Float) {
        edgeGlow.value = v
        putFloat("edge_glow", v)
    }

    fun setInnerShade(v: Float) {
        innerShade.value = v
        putFloat("inner_shade", v)
    }

    fun setPressSpring(v: Float) {
        pressSpring.value = v
        putFloat("press_spring", v)
    }

    fun setTouchGlow(v: Boolean) {
        touchGlow.value = v
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("touch_glow", v)?.apply()
    }

    fun reset() {
        setEdgeGlow(0.55f)
        setInnerShade(0.45f)
        setPressSpring(0.60f)
        setTouchGlow(true)
    }
}

/** Outline → Path，把绘制限制在任意形状内（与 LiquidGlass.kt 同一套写法） */
private fun Outline.toGlassPath(): Path = Path().apply {
    when (val o = this@toGlassPath) {
        is Outline.Rectangle -> addRect(o.rect)
        is Outline.Rounded -> addRoundRect(o.roundRect)
        is Outline.Generic -> addPath(o.path)
    }
}

/**
 * 边缘高光 + 内阴影叠加层。纯 Canvas，全机型可用。
 *
 * 挂在 junSurface 的液态玻璃分支上 —— 调参一改，整个 App 的玻璃面都跟着变；
 * 实色 / 毛玻璃材质不叠（毛玻璃有自己的 Haze 模糊，叠高光会脏）。
 */
fun Modifier.glassTunedEdges(shape: Shape, glow: Float, shade: Float): Modifier =
    this.drawBehind {
        // 画在玻璃背景之上、文字之下：高光不会盖字，也不会被内容冲淡
        if (size.minDimension <= 1f) return@drawBehind
        val path = shape.createOutline(size, layoutDirection, this).toGlassPath()
        val w = size.width
        val h = size.height

        // 内阴影：向右下压一圈暗边，玻璃才有厚度；不再是一层扁平描边
        if (shade > 0.001f) {
            clipPath(path) {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.24f * shade)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(w, h)
                    )
                )
            }
        }
        // 边缘高光：左上迎光 → 中部收 → 右下回光，模拟镜面内缘
        if (glow > 0.001f) {
            drawPath(
                path = path,
                brush = Brush.linearGradient(
                    0.00f to Color.White.copy(alpha = 0.78f * glow),
                    0.45f to Color.White.copy(alpha = 0.10f * glow),
                    1.00f to Color.White.copy(alpha = 0.40f * glow),
                    start = Offset(0f, 0f),
                    end = Offset(w, h)
                ),
                style = Stroke(width = 2.2f)
            )
        }
    }

/**
 * 跟手光斑 + 阻尼按压回弹。只挂在需要「按一下有反馈」的地方（调参预览、自定义中心卡片）。
 *
 * 用 PointerEventPass.Initial 只观察手势、不消费事件，不会抢 clickable / 滑动。
 */
@Composable
fun Modifier.glassTouchGlow(shape: Shape, enabled: Boolean, springAmount: Float): Modifier {
    var pointer by remember { mutableStateOf<Offset?>(null) }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 1f - 0.055f * springAmount else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 380f),
        label = "glassPressScale"
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "glassTouchAlpha"
    )
    LaunchedEffect(enabled) {
        if (!enabled) {
            pressed = false
            pointer = null
        }
    }
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    pressed = e.changes.any { it.pressed }
                    pointer = e.changes.firstOrNull()?.position
                }
            }
        }
        .drawWithContent {
            drawContent()
            val p = pointer
            if (p != null && glowAlpha > 0.01f && size.minDimension > 1f) {
                val path = shape.createOutline(size, layoutDirection, this).toGlassPath()
                val radius = size.minDimension * 0.95f
                clipPath(path) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.32f * glowAlpha),
                                Color.White.copy(alpha = 0.09f * glowAlpha),
                                Color.Transparent
                            ),
                            center = p,
                            radius = radius
                        ),
                        radius = radius,
                        center = p
                    )
                }
            }
        }
}

// ────────────────────────────────────────────────────────────────
// 自定义中心里的「玻璃质感」页：调参与实时预览
// ────────────────────────────────────────────────────────────────

@Composable
fun GlassTuningTab(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    colors: JunColors
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { GlassTuning.init(ctx) }
    val compact = LocalCompact.current

    Text(
        "从参考源码里挑了不挑机型的三样：跟手高光、阻尼回弹、边缘高光 / 内阴影。" +
            "纯 Canvas 实现，全机型可用；不引整库、不上旗舰专属的真折射。",
        color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp
    )
    Spacer(Modifier.height((10 * compact).dp))

    GlassTuningPreview(colors)
    Spacer(Modifier.height((12 * compact).dp))

    Text("界面材质", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        JunSurfaceStyle.values().forEach { s ->
            TuningChip(s.label, state.surfaceStyle == s, colors, Modifier.weight(1f)) {
                viewModel.setSurfaceStyle(s)
            }
        }
    }
    Text(
        "默认液态玻璃＝整套 App UI 的折射质感；切换时下面这块预览和整个 App 同一帧跟着变。",
        color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp
    )
    Spacer(Modifier.height((12 * compact).dp))

    Text("玻璃质感", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    TuningSlider("边缘高光", GlassTuning.edgeGlow.value, colors) { GlassTuning.setEdgeGlow(it) }
    TuningSlider("内阴影厚度", GlassTuning.innerShade.value, colors) { GlassTuning.setInnerShade(it) }
    TuningSlider("按压回弹", GlassTuning.pressSpring.value, colors) { GlassTuning.setPressSpring(it) }
    Spacer(Modifier.height(4.dp))
    TuningSwitch("跟手光斑", GlassTuning.touchGlow.value, colors) { GlassTuning.setTouchGlow(it) }
    Spacer(Modifier.height((10 * compact).dp))

    Text("模糊", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    TuningSlider("玻璃浓度", state.glassBlur / 2f, colors) { viewModel.setGlassBlur(it * 2f) }
    TuningSlider("背景深度", state.bgBlur / 60f, colors) { viewModel.setBgBlur(it * 60f) }
    Spacer(Modifier.height((10 * compact).dp))

    TuningChip("恢复玻璃默认", false, colors, Modifier.fillMaxWidth()) { GlassTuning.reset() }
    Text(
        "拖动滑杆时上面那块预览、这个弹窗和整个 App 的玻璃面会同时变化，不用退出去看效果。",
        color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp
    )
}

@Composable
private fun GlassTuningPreview(colors: JunColors) {
    val outer = remember { RoundedCornerShape(16.dp) }
    val card = remember { RoundedCornerShape(24.dp) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(124.dp)
            .clip(outer)
            .background(colors.surface.copy(alpha = 0.5f))
            .border(1.dp, colors.subText.copy(alpha = 0.22f), outer),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.82f)
                .height(78.dp)
                .junSurface(GlassLevel.CARD, colors, card)
                .glassTunedEdges(card, GlassTuning.edgeGlow.value, GlassTuning.innerShade.value)
                .glassTouchGlow(card, GlassTuning.touchGlow.value, GlassTuning.pressSpring.value)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "按我一下试试", color = colors.text,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "高光跟着手指走 · 松手弹簧回弹",
                    color = colors.subText, fontSize = 10.sp, textAlign = TextAlign.Center
                )
            }
        }
        Text(
            "实时预览",
            color = colors.subText, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        )
    }
}

@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    colors: JunColors,
    onChange: (Float) -> Unit
) {
    Text(
        "$label · ${(value.coerceIn(0f, 1f) * 100).toInt()}%",
        color = colors.text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold
    )
    CompactSlider(
        value = value.coerceIn(0f, 1f),
        onValueChange = onChange,
        valueRange = 0f..1f,
        activeColor = colors.primary,
        inactiveColor = colors.surface
    )
}

@Composable
private fun TuningSwitch(
    label: String,
    checked: Boolean,
    colors: JunColors,
    onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = colors.text, fontSize = 12.5.sp)
        Box(
            Modifier
                .size(width = 40.dp, height = 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked) colors.primary else colors.primary.copy(alpha = 0.22f))
                .junPressable(scaleDown = 0.92f) { onChange(!checked) }
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun TuningChip(
    label: String,
    selected: Boolean,
    colors: JunColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = remember { RoundedCornerShape(12.dp) }
    Box(
        modifier
            .height(36.dp)
            .junPressable(scaleDown = 0.94f, onClick = onClick)
            .junSurface(
                level = GlassLevel.CARD,
                colors = colors,
                shape = shape,
                solid = if (selected) colors.primary.copy(alpha = 0.30f)
                else colors.card.copy(alpha = 0.85f),
                accent = if (selected) colors.primary else null
            )
            .border(
                1.dp,
                if (selected) colors.primary else colors.subText.copy(alpha = 0.35f),
                shape
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) colors.primary else colors.text,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.Black else FontWeight.Bold,
            maxLines = 1
        )
    }
}
