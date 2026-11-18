package com.jun.musiccloud.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.junSurface

/**
 * 上栏 / 卡片顶栏共用的图标按钮组。
 *
 * 原先这些按钮散在 MainScreen（3600 行）里，而它们被 SongDeck、PosterWall、
 * 搜索页、设置页多处复用——放在 MainScreen 里等于让每个复用方都依赖一个巨型文件。
 * 搬到自己的文件后，MainScreen 只管编排，按钮的造型在这里单独维护。
 */

@Composable
internal fun SettingsButton(onClick: () -> Unit, colors: JunColors, plain: Boolean = false) {
    Box(
        Modifier
            .size(34.dp)
            .junPressable(onClick = onClick)
            .then(if (plain) Modifier else Modifier.junSurface(GlassLevel.CHIP, colors, JunShape.chip)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            JunIcons.Settings,
            contentDescription = "设置",
            tint = colors.primary,
            modifier = Modifier.size(19.dp)
        )
    }
}

/**
 * 首页入口：房子图标，点击回到完整曲库（清空「我喜欢」/歌单筛选）。
 * active 为真时用主题色，表示当前就在完整曲库视图。
 */
@Composable
internal fun HomeButton(onClick: () -> Unit, colors: JunColors, active: Boolean = false, plain: Boolean = false) {
    Box(
        Modifier
            .size(34.dp)
            .junPressable(onClick = onClick)
            .then(if (plain) Modifier else Modifier.junSurface(GlassLevel.CHIP, colors, JunShape.chip)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            JunIcons.Home,
            contentDescription = "首页",
            tint = if (active) colors.primary else colors.subText,
            modifier = Modifier.size(19.dp)
        )
    }
}

/**
 * 排列方式入口：四宫格图标，点击循环切换列表/卡片/堆叠/唱片墙布局。
 * 与 [HomeButton] 同为矢量线性图标，保证上栏图标语言统一。
 */
@Composable
internal fun LayoutButton(onClick: () -> Unit, colors: JunColors, plain: Boolean = false) {
    Box(
        Modifier
            .size(34.dp)
            .junPressable(onClick = onClick)
            .then(if (plain) Modifier else Modifier.junSurface(GlassLevel.CHIP, colors, JunShape.chip)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            JunIcons.Layout,
            contentDescription = "排列方式",
            tint = colors.primary,
            modifier = Modifier.size(19.dp)
        )
    }
}

/**
 * 歌单入口：三横线列表图标，点击打开歌单管理弹层。
 * 点亮的判断交给外层（有自定义歌单/选中歌单时给个视觉暗示）。
 */
@Composable
internal fun PlaylistButton(onClick: () -> Unit, colors: JunColors, active: Boolean = false) {
    Box(
        Modifier
            .size(34.dp)
            .junPressable(onClick = onClick)
            .junSurface(GlassLevel.CHIP, colors, JunShape.chip)
            .semantics { contentDescription = "歌单" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(17.dp)) {
            val c = if (active) colors.primary else colors.subText
            val lw = size.minDimension * 0.14f
            // 左圆点 + 三条横线（列表 = 带项目标记的清单）
            drawCircle(c, radius = lw * 0.6f, center = Offset(size.width * 0.14f, size.height * 0.2f))
            drawCircle(c, radius = lw * 0.6f, center = Offset(size.width * 0.14f, size.height * 0.5f))
            drawCircle(c, radius = lw * 0.6f, center = Offset(size.width * 0.14f, size.height * 0.8f))
            (0..2).forEach { i ->
                val y = size.height * (0.2f + i * 0.3f)
                drawLine(
                    color = c,
                    start = Offset(size.width * 0.34f, y),
                    end = Offset(size.width * 0.94f, y),
                    strokeWidth = lw,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
    }
}

/**
 * 听歌报告入口：柱状图图标（三根高低不同的柱子）。
 *
 * 和刷新/设置一样自绘：项目为了体积没引 material-icons-extended，
 * 三根圆头竖线已经足够表达"统计"，不必为一个图标加 5MB 依赖。
 */
@Composable
internal fun ReportButton(onClick: () -> Unit, colors: JunColors, plain: Boolean = false) {
    Box(
        Modifier
            .size(34.dp)
            .junPressable(onClick = onClick)
            .then(if (plain) Modifier else Modifier.junSurface(GlassLevel.CHIP, colors, JunShape.chip))
            .semantics { contentDescription = "收听报告" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(17.dp)) {
            val c = colors.primary
            val w = size.width * 0.17f
            // 三根柱子的高度比例：矮-高-中，一眼能看出是图表而不是三条等长杠
            val heights = floatArrayOf(0.45f, 1f, 0.7f)
            heights.forEachIndexed { i, h ->
                val x = size.width * (0.2f + i * 0.3f)
                val barH = size.height * 0.78f * h
                drawLine(
                    color = c,
                    start = Offset(x, size.height * 0.88f),
                    end = Offset(x, size.height * 0.88f - barH),
                    strokeWidth = w,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
    }
}

/**
 * 搜索入口：放大镜图标，点击打开全屏搜索页。
 * 卡片模式（横向/堆叠/唱片墙）没有搜索框，用这枚按钮进入搜索。
 */
@Composable
internal fun SearchButton(onClick: () -> Unit, colors: JunColors, plain: Boolean = false) {
    Box(
        Modifier
            .size(34.dp)
            .junPressable(onClick = onClick)
            .then(if (plain) Modifier else Modifier.junSurface(GlassLevel.CHIP, colors, JunShape.chip)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            JunIcons.Search,
            contentDescription = "搜索",
            tint = colors.primary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
internal fun RefreshButton(onClick: () -> Unit, colors: JunColors, isRefreshing: Boolean = false) {
    // 刷新中：无限正转（用 Animatable 累加，不会像 360→0 那样结束瞬间倒转一圈）。
    // 静止时不转。整体绘制思路改为：3/4 圆弧 + 圆环末端一枚小箭头（收在圆周上，不再外突）。
    //
    // 这里不用 rememberInfiniteTransition：它一经创建就永久驻留帧回调，
    // isRefreshing=false 时仍在每帧算角度再被丢弃，白烧一路重组。
    // 改成 Animatable + LaunchedEffect(isRefreshing)，不刷新时协程挂起、零帧开销；
    // 停下时从当前角度平滑归零，而不是硬跳回 0°。
    val spin = remember { Animatable(0f) }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            spin.animateTo(
                targetValue = spin.value + 3600f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing))
            )
        } else if (spin.value != 0f) {
            // 收尾：转到下一个整圈再停，避免箭头停在半途
            val nextWhole = kotlin.math.ceil(spin.value / 360f) * 360f
            spin.animateTo(nextWhole, tween(320, easing = FastOutSlowInEasing))
            spin.snapTo(0f)
        }
    }
    val rotation = spin.value
    Box(
        Modifier
            .size(34.dp)
            .junPressable(enabled = !isRefreshing, onClick = onClick)
            .junSurface(GlassLevel.CHIP, colors, JunShape.chip),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp).graphicsLayer { this.rotationZ = rotation }) {
            val c = colors.primary
            val strokeW = size.minDimension * 0.11f
            // 圆弧：从 -30° 起扫 300°，缺口留在右上——箭头贴着缺口下端画
            val r = size.minDimension / 2f - strokeW / 2f - size.minDimension * 0.04f
            val inset = size.minDimension / 2f - r - strokeW / 2f
            drawArc(
                color = c,
                startAngle = -30f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = strokeW,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )
            // 箭头：等腰三角形，顶点在圆弧终点、指向切线方向（顺时针），
            // 两条腿向切线后方 ±30° 张开——这才是标准 Material 刷新箭头
            val cx = size.width / 2f
            val cy = size.height / 2f
            // 圆弧终点角度 = -30 + 300 = 270°（正下方）
            val endRad = Math.toRadians(270.0)
            val ex = cx + (r * Math.cos(endRad)).toFloat()
            val ey = cy + (r * Math.sin(endRad)).toFloat()
            val s = size.minDimension * 0.14f
            // 顺时针切线方向 = 角度方向 + 90°
            val tangent = endRad + Math.PI / 2
            val half = Math.toRadians(30.0) // 顶角 60°，腿长 s
            val p1 = Offset(
                (ex + s * Math.cos(tangent - half)).toFloat(),
                (ey + s * Math.sin(tangent - half)).toFloat()
            )
            val p2 = Offset(
                (ex + s * Math.cos(tangent + half)).toFloat(),
                (ey + s * Math.sin(tangent + half)).toFloat()
            )
            drawLine(c, Offset(ex, ey), p1, strokeWidth = strokeW * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(c, Offset(ex, ey), p2, strokeWidth = strokeW * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/**
 * 排行榜入口（6.3）：自绘奖杯图标 + 金色描边。
 *
 * 用户要求「显眼的位置」——上栏五个入口里只有它是彩色的：其余永远是主题色描边，
 * 这一枚固定用段位金，扫一眼就知道点进去是排行榜，不必读图标细节。
 */
@Composable
internal fun RankButton(onClick: () -> Unit, colors: JunColors) {
    val gold = androidx.compose.ui.graphics.Color(0xFFE8B84B)
    Box(
        Modifier
            .size(34.dp)
            .junPressable(onClick = onClick)
            .junSurface(GlassLevel.CHIP, colors, JunShape.chip)
            .border(1.dp, gold.copy(alpha = 0.55f), JunShape.chip)
            .semantics { contentDescription = "听歌排行榜" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(17.dp)) {
            val c = if (colors.isDark) gold else androidx.compose.ui.graphics.Color(0xFFB8862B)
            val lw = size.minDimension * 0.12f
            val cap = androidx.compose.ui.graphics.StrokeCap.Round
            // 杯身：上宽下窄的梯形（四根线）
            val lt = Offset(size.width * 0.28f, size.height * 0.12f)
            val rt = Offset(size.width * 0.72f, size.height * 0.12f)
            val lb = Offset(size.width * 0.42f, size.height * 0.56f)
            val rb = Offset(size.width * 0.58f, size.height * 0.56f)
            drawLine(c, lt, lb, lw, cap); drawLine(c, rt, rb, lw, cap)
            drawLine(c, lt, rt, lw, cap); drawLine(c, lb, rb, lw, cap)
            // 两侧杯把
            drawArc(
                c, 90f, 180f, false,
                topLeft = Offset(size.width * 0.10f, size.height * 0.16f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.26f, size.height * 0.26f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(lw)
            )
            drawArc(
                c, 270f, 180f, false,
                topLeft = Offset(size.width * 0.64f, size.height * 0.16f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.26f, size.height * 0.26f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(lw)
            )
            // 杯柄 + 底座
            drawLine(c, Offset(size.width * 0.5f, size.height * 0.56f), Offset(size.width * 0.5f, size.height * 0.78f), lw, cap)
            drawLine(c, Offset(size.width * 0.34f, size.height * 0.88f), Offset(size.width * 0.66f, size.height * 0.88f), lw, cap)
        }
    }
}
