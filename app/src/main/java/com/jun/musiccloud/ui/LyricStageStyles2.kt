package com.jun.musiccloud.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.ui.theme.readableOn
import com.jun.musiccloud.ui.theme.readableTextOn
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ==================== 8 镜台 ====================

/** 镜台：点阵化的文字在 3D 空间里穿行，镜头跟着歌词运镜。 */
@Composable
internal fun DioramaStage(frame: LyricFrame) {
    val dark = frame.colors.isDark
    val accent = if (dark) frame.colors.primary else frame.colors.primary.readableOn(Color(0xFFF8F3EC), 3.2f)
    val onStage = if (dark) Color.White else Color.Black
    val artwork = rememberStageArtwork(frame.artworkKey)
    val look = frame.stageProfile.lookIndex
    StageScaffold(
        frame = frame,
        dark = dark,
        accent = accent,
        background = { DioramaBackground(frame, artwork, accent, dark) }
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .stageContentPadding(frame, top = 90.dp, bottom = 94.dp, start = 26.dp, end = 26.dp)
        ) {
            // 形态 3「灵魂残影」：当前句在后面拖一层低透明度重影。
            if (look == 3) {
                Text(
                    frame.line?.text.orEmpty(),
                    color = accent.copy(alpha = 0.10f),
                    fontSize = (frame.fontSize * 1.02f).sp,
                    fontFamily = frame.family,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationX = 5.dp.toPx()
                            translationY = 7.dp.toPx()
                            scaleX = 1.04f
                            scaleY = 1.04f
                        }
                )
            }
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    frame.prev?.text.orEmpty(),
                    color = onStage.copy(alpha = 0.24f),
                    fontSize = (frame.fontSize * 0.78f).sp,
                    fontFamily = frame.family,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                DioramaCard(frame, accent, onStage, dark, reflection = false)
                Spacer(Modifier.height(4.dp))
                DioramaCard(frame, accent, onStage, dark, reflection = true)
                Spacer(Modifier.height(18.dp))
                Text(
                    frame.next?.text.orEmpty(),
                    color = onStage.copy(alpha = 0.20f),
                    fontSize = (frame.fontSize * 0.78f).sp,
                    fontFamily = frame.family,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                "DIORAMA / ${frame.artist.uppercase().take(16)}",
                color = onStage.copy(alpha = 0.22f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(top = 4.dp)
            )
            Text(
                "3D TEXT FIELD",
                color = accent.copy(alpha = 0.45f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.4.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun DioramaCard(frame: LyricFrame, accent: Color, onStage: Color, dark: Boolean, reflection: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationX = if (reflection) -7f else 7f
                cameraDistance = 20f * density
                if (reflection) {
                    scaleY = -1f
                    alpha = 0.16f
                }
            }
            .clip(RoundedCornerShape(18.dp))
            .background((if (dark) Color.Black else onStage).copy(alpha = if (reflection) 0.12f else if (dark) 0.44f else 0.74f))
            .border(1.dp, onStage.copy(alpha = if (reflection) 0.08f else 0.20f), RoundedCornerShape(18.dp))
            .padding(horizontal = 20.dp, vertical = 17.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(4.dp).background(accent, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                if (reflection) "REFLECTION" else "NOW PLAYING",
                color = onStage.copy(alpha = 0.48f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.2.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                formatStageTime(frame.line?.timeMs ?: 0L),
                color = accent.copy(alpha = 0.7f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(9.dp))
        if (reflection) {
            Text(
                frame.line?.text.orEmpty(),
                color = onStage.copy(alpha = 0.72f),
                fontSize = (frame.fontSize * 0.98f).sp,
                fontFamily = frame.family,
                fontWeight = FontWeight.Bold,
                lineHeight = (frame.fontSize * 1.2f).sp
            )
        } else {
            StageCurrentLine(
                frame = frame,
                sungColor = accent,
                unsungColor = onStage.copy(alpha = 0.34f),
                fontSize = frame.fontSize * 0.98f,
                textAlign = TextAlign.Start
            )
            StageSubLines(
                frame = frame,
                color = onStage,
                accent = accent,
                textAlign = TextAlign.Start,
                translationSize = LyricType.translation(frame.fontSize),
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

@Composable
private fun DioramaBackground(frame: LyricFrame, artwork: ImageBitmap?, accent: Color, dark: Boolean) {
    Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF141220) else Color(0xFFF8F3EC))) {
        artwork?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(if (dark) 0.34f else 0.18f),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        if (dark) listOf(
                            Color(0xFF16131F).copy(alpha = 0.92f),
                            Color(0xFF201A2B).copy(alpha = 0.70f),
                            Color(0xFF131019).copy(alpha = 0.94f)
                        ) else listOf(
                            Color(0xFFF8F3EC).copy(alpha = 0.86f),
                            Color(0xFFEDE4D6).copy(alpha = 0.62f),
                            Color(0xFFF6EFE4).copy(alpha = 0.88f)
                        )
                    )
                )
        )
        Canvas(Modifier.fillMaxSize()) {
            // 点阵：近大远小的文字场
            val spacing = size.width / 18f
            for (ix in 0..18) {
                for (iy in 0..30) {
                    val x = ix * spacing
                    val y = iy * spacing
                    val centerBias = 1f - (abs(x - size.width / 2f) / (size.width / 2f)).coerceIn(0f, 1f)
                    val dot = 0.02f + centerBias * 0.07f
                    drawCircle(
                        color = (if (dark) Color.White else Color.Black).copy(alpha = dot * if (dark) 1f else 0.7f),
                        radius = 0.8f + centerBias * 1.3f,
                        center = Offset(x, y)
                    )
                }
            }
            // 透视走廊
            val horizon = Offset(size.width * 0.5f, size.height * 0.46f)
            for (i in -4..4) {
                drawLine(
                    color = accent.copy(alpha = 0.055f),
                    start = horizon,
                    end = Offset(size.width * (0.5f + i * 0.16f), size.height),
                    strokeWidth = 1f
                )
                drawLine(
                    color = (if (dark) Color.White else Color.Black).copy(alpha = 0.03f),
                    start = Offset(size.width * (0.5f + i * 0.16f), 0f),
                    end = Offset(size.width * (0.5f + i * 0.26f), size.height),
                    strokeWidth = 1f
                )
            }
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = if (dark) 0.2f else 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.46f),
                    radius = size.minDimension * 0.5f
                ),
                radius = size.minDimension * 0.5f,
                center = Offset(size.width * 0.5f, size.height * 0.46f)
            )
        }
        // 镜面地平线
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.8f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, accent.copy(alpha = 0.4f), Color.Transparent)
                    )
                )
        )
    }
}

// ==================== 9 时计 ====================

/** 时计：封面嵌进钟表盘，歌词沿弧线随擒纵一格格推过。 */
@Composable
internal fun PendoloStage(frame: LyricFrame) {
    val dark = frame.colors.isDark
    val accent = if (dark) frame.colors.primary else frame.colors.primary.readableOn(Color(0xFFF6F1E7), 3.2f)
    val onStage = if (dark) Color.White else Color.Black
    val artwork = rememberStageArtwork(frame.artworkKey)
    StageScaffold(
        frame = frame,
        dark = dark,
        accent = accent,
        background = { PendoloBackground(frame, accent, dark) }
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .stageContentPadding(frame, top = 78.dp, bottom = 88.dp, start = 18.dp, end = 18.dp)
        ) {
            if (frame.landscape) {
                // 横屏：表盘与歌词左右并排，表盘按高度封顶。
                // 竖屏那种「表盘在上、歌词在下」在 250dp 高的横屏里必然溢出，
                // 会顶到顶部标题和底部按钮，所以横屏换一套构图。
                val dial = minOf(maxWidth * 0.44f, maxHeight * 0.94f)
                Row(
                    Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(30.dp)
                ) {
                    PendoloDial(frame, artwork, accent, onStage, dial, dark)
                    PendoloLyrics(frame, accent, onStage, Modifier.weight(1f))
                }
            } else {
                val dial = minOf(maxWidth * 0.92f, maxHeight * 0.58f)
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    PendoloDial(frame, artwork, accent, onStage, dial, dark)
                    PendoloLyrics(frame, accent, onStage, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** 时计的表盘：刻度、三根指针、中心封面与铭牌。 */
@Composable
private fun PendoloDial(
    frame: LyricFrame,
    artwork: ImageBitmap?,
    accent: Color,
    onStage: Color,
    dial: androidx.compose.ui.unit.Dp,
    dark: Boolean
) {
    Box(
        Modifier
            .size(dial)
            .padding(top = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        val look = frame.stageProfile.lookIndex
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f
            drawCircle((if (dark) Color.Black else onStage).copy(alpha = if (dark) 0.30f else 0.55f), radius = r)
            drawCircle(onStage.copy(alpha = 0.14f), radius = r, style = Stroke(width = 1.5f))
            drawCircle(accent.copy(alpha = 0.34f), radius = r * 0.86f, style = Stroke(width = 1f))
            // 形态 1「无齿轮」/ 3「极简」收起刻度与小圆点，只留外圈。
            if (look != 1 && look != 3) {
                drawTicks(
                    color = onStage.copy(alpha = 0.4f),
                    center = c,
                    radius = r * 0.94f,
                    count = 60,
                    longEvery = 5,
                    longLength = r * 0.055f,
                    shortLength = r * 0.026f,
                    strokeWidth = 1.6f
                )
                // 十二个主刻度做成小圆点
                for (i in 0 until 12) {
                    val a = Math.toRadians((i * 30 - 90).toDouble())
                    val rr = r * 0.81f
                    drawCircle(
                        color = accent.copy(alpha = 0.55f),
                        radius = if (i % 3 == 0) 3.4f else 2f,
                        center = Offset(
                            c.x + cos(a).toFloat() * rr,
                            c.y + sin(a).toFloat() * rr
                        )
                    )
                }
            }
            val second = (frame.adjustedMs / 1000f) % 60f
            val minute = ((frame.adjustedMs / 60000f) % 60f) +
                (frame.adjustedMs % 60000L) / 60000f
            val hour = frame.progress * 360f
            rotate(hour, c) {
                drawLine(
                    color = onStage.copy(alpha = 0.3f),
                    start = c,
                    end = Offset(c.x, c.y - r * 0.38f),
                    strokeWidth = 5f
                )
            }
            rotate(minute * 6f, c) {
                drawLine(
                    color = onStage.copy(alpha = 0.55f),
                    start = c,
                    end = Offset(c.x, c.y - r * 0.56f),
                    strokeWidth = 3f
                )
            }
            rotate(second * 6f, c) {
                drawLine(
                    color = accent,
                    start = c,
                    end = Offset(c.x, c.y - r * 0.72f),
                    strokeWidth = 1.6f
                )
            }
            drawCircle(accent, radius = 4.5f, center = c)
            drawCircle(if (dark) Color(0xFF151119) else Color(0xFFF6F1E7), radius = 1.8f, center = c)
        }
        StageArtwork(
            image = artwork,
            title = frame.title,
            colors = frame.colors,
            modifier = Modifier
                .size(dial * if (look == 2) 0.56f else 0.40f)
                .align(Alignment.Center),
            shape = CircleShape,
            overlayAlpha = 0.12f
        )
        if (look != 3) {
            Text(
                "PENDOLO",
                color = onStage.copy(alpha = 0.42f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.5.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
            )
        }
        Text(
            "\u25b3  ${formatStageTime(frame.line?.timeMs ?: frame.adjustedMs)}  \u25bd",
            color = accent.copy(alpha = 0.6f),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp)
        )
    }
}

/** 时计的歌词卡片：上一句 / 当前句（逐字扫光）/ 译文 / 下一句 + 总时长。 */
@Composable
private fun PendoloLyrics(
    frame: LyricFrame,
    accent: Color,
    onStage: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(18.dp))
                .background((if (frame.colors.isDark) Color.Black else onStage).copy(alpha = if (frame.colors.isDark) 0.52f else 0.74f))
                .border(1.dp, onStage.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                frame.prev?.text.orEmpty(),
                color = onStage.copy(alpha = 0.26f),
                fontSize = (frame.fontSize * 0.7f).sp,
                fontFamily = frame.family,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            StageCurrentLine(
                frame = frame,
                sungColor = accent,
                unsungColor = onStage.copy(alpha = 0.34f),
                fontSize = frame.fontSize * 0.94f,
                textAlign = TextAlign.Center
            )
            StageSubLines(
                frame = frame,
                color = onStage,
                accent = accent,
                textAlign = TextAlign.Center,
                translationSize = LyricType.translation(frame.fontSize),
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                frame.next?.text.orEmpty(),
                color = onStage.copy(alpha = 0.26f),
                fontSize = (frame.fontSize * 0.7f).sp,
                fontFamily = frame.family,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            "${formatStageTime(frame.adjustedMs)} / ${formatStageTime(frame.durationMs)}",
            color = onStage.copy(alpha = 0.36f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.8.sp
        )
    }
}

@Composable
private fun PendoloBackground(frame: LyricFrame, accent: Color, dark: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    if (dark) listOf(Color(0xFF241C2C), Color(0xFF15111C), Color(0xFF100D16))
                    else listOf(Color(0xFFFBF6EB), Color(0xFFF0E8D9), Color(0xFFF8F2E6))
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width * 0.5f, size.height * 0.4f)
            // 背后的齿轮轮廓
            for (i in 0 until 3) {
                val r = size.minDimension * (0.34f + i * 0.1f)
                drawCircle(
                    color = (if (dark) Color.White else Color.Black).copy(alpha = 0.04f),
                    radius = r,
                    center = c,
                    style = Stroke(width = 1f)
                )
                for (t in 0 until 24) {
                    val a = (t * 15f) * (Math.PI / 180f).toFloat()
                    val inner = r * 0.96f
                    val outer = r * 1.015f
                    drawLine(
                        color = accent.copy(alpha = 0.05f),
                        start = Offset(c.x + cos(a) * inner, c.y + sin(a) * inner),
                        end = Offset(c.x + cos(a) * outer, c.y + sin(a) * outer),
                        strokeWidth = 2f
                    )
                }
            }
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = if (dark) 0.14f else 0.11f), Color.Transparent),
                    center = c,
                    radius = size.minDimension * 0.5f
                ),
                radius = size.minDimension * 0.5f,
                center = c
            )
            // 摆锤轨迹
            drawArc(
                color = (if (dark) Color.White else Color.Black).copy(alpha = 0.055f),
                startAngle = 205f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(size.width * 0.18f, size.height * 0.66f),
                size = Size(size.width * 0.64f, size.height * 0.26f),
                style = Stroke(width = 1f)
            )
            drawCircle(accent.copy(alpha = 0.35f), radius = 3f, center = Offset(size.width * 0.5f, size.height * 0.79f))
        }
    }
}

// ==================== 10 商籁 ====================

/** 商籁：短线、边框与镂空巨字轮番铺开，歌词像被排进一页页翻动的诗稿。 */
@Composable
internal fun SonnetStage(frame: LyricFrame) {
    val paper = Color(0xFFF4EDDD)
    val ink = Color(0xFF241C15)
    val accent = frame.colors.primary.readableOn(paper, 3.4f)
    StageScaffold(
        frame = frame,
        dark = false,
        accent = accent,
        background = { SonnetBackground(frame, accent) }
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .stageContentPadding(frame, top = 98.dp, bottom = 96.dp, start = 34.dp, end = 34.dp)
        ) {
            Text(
                "%02d".format(frame.index + 1),
                color = ink.copy(alpha = 0.055f),
                fontSize = 168.sp,
                fontFamily = frame.family,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.CenterEnd).offset(x = 20.dp, y = (-10).dp)
            )
            Canvas(Modifier.fillMaxSize()) {
                drawLine(
                    color = ink.copy(alpha = 0.18f),
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 1f
                )
            }
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "SONNET",
                        color = ink.copy(alpha = 0.82f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 5.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "NO. ${"%02d".format(frame.index + 1)}",
                        color = ink.copy(alpha = 0.52f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(ink.copy(alpha = 0.28f))
                )
                Spacer(Modifier.height(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    for (offset in -2..1) {
                        val line = frame.lineAt(offset) ?: continue
                        val current = offset == 0
                        val number = (frame.index + offset + 1).coerceAtLeast(1)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = if (current) 6.dp else 3.dp)
                        ) {
                            Text(
                                "%02d".format(number),
                                color = ink.copy(alpha = if (current) 0.58f else 0.28f),
                                fontSize = 8.sp,
                                fontFamily = frame.family,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                modifier = Modifier.width(26.dp).padding(top = if (current) 6.dp else 2.dp)
                            )
                            if (current) {
                                Box(
                                    Modifier
                                        .width(2.dp)
                                        .height(if (frame.showTranslation || frame.showRomanization) 54.dp else 30.dp)
                                        .background(accent.copy(alpha = 0.75f))
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    StageCurrentLine(
                                        frame = frame,
                                        sungColor = ink,
                                        unsungColor = ink.copy(alpha = 0.32f),
                                        fontSize = frame.fontSize * 1.02f,
                                        textAlign = TextAlign.Start,
                                        fontFamily = frame.family,
                                        fontWeight = FontWeight.Medium,
                                        lineHeight = (frame.fontSize * 1.4f).sp
                                    )
                                    StageSubLines(
                                        frame = frame,
                                        color = ink.copy(alpha = 0.74f),
                                        accent = accent,
                                        textAlign = TextAlign.Start,
                                        translationSize = LyricType.translation(frame.fontSize),
                                        modifier = Modifier.padding(top = 5.dp)
                                    )
                                }
                            } else {
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    line.text,
                                    color = ink.copy(alpha = if (abs(offset) == 1) 0.46f else 0.26f),
                                    fontSize = (frame.fontSize * 0.72f).sp,
                                    fontFamily = frame.family,
                                    fontWeight = FontWeight.Normal,
                                    fontStyle = if (abs(offset) == 1) FontStyle.Italic else FontStyle.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = (frame.fontSize * 1.15f).sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(ink.copy(alpha = 0.22f))
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        frame.title.uppercase(),
                        color = ink.copy(alpha = 0.62f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "JUN · 商籁",
                        color = accent.copy(alpha = 0.7f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SonnetBackground(frame: LyricFrame, accent: Color) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFF8F2E5), Color(0xFFF1E7D4), Color(0xFFEADDC7))
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 纸纤维
            for (i in 0 until 260) {
                val x = ((i * 83 + 11) % 100) / 100f * size.width
                val y = ((i * 59 + 29) % 100) / 100f * size.height
                drawCircle(
                    color = Color(0xFF6B4C2A).copy(alpha = 0.022f + (i % 3) * 0.008f),
                    radius = 0.7f + (i % 4) * 0.3f,
                    center = Offset(x, y)
                )
            }
            // 一处很淡的茶渍
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF9A6B3D).copy(alpha = 0.055f), Color.Transparent),
                    center = Offset(size.width * 0.82f, size.height * 0.18f),
                    radius = size.minDimension * 0.3f
                ),
                radius = size.minDimension * 0.3f,
                center = Offset(size.width * 0.82f, size.height * 0.18f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.035f), Color.Transparent),
                    center = Offset(size.width * 0.12f, size.height * 0.9f),
                    radius = size.minDimension * 0.36f
                ),
                radius = size.minDimension * 0.36f,
                center = Offset(size.width * 0.12f, size.height * 0.9f)
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .border(1.dp, Color(0xFF2A2119).copy(alpha = 0.24f))
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(21.dp)
                .border(0.5.dp, Color(0xFF2A2119).copy(alpha = 0.12f))
        )
    }
}

// ==================== 11 凝彩 ====================

/** 凝彩：成片的色块与细密网点缓缓重组，歌词压过颜色时翻出反色。 */
@Composable
internal fun TemperaStage(frame: LyricFrame) {
    val dark = frame.colors.isDark
    val base = if (dark) Color(0xFF121013) else Color(0xFFEDE7DA)
    val accent = if (dark) frame.colors.primary else frame.colors.primary.readableOn(base, 3.2f)
    val onStage = if (dark) Color.White else Color.Black
    val look = frame.stageProfile.lookIndex
    StageScaffold(
        frame = frame,
        dark = dark,
        accent = accent,
        background = { TemperaBackground(frame, dark, accent) }
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .stageContentPadding(frame, top = 94.dp, bottom = 100.dp, start = 22.dp, end = 22.dp)
        ) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center
            ) {
                for (offset in -2..2) {
                    val line = frame.lineAt(offset) ?: continue
                    val current = offset == 0
                    val block = when {
                        current -> if (dark) accent else Color(0xFF17130F)
                        offset == -1 || offset == 1 -> if (dark) Color.White.copy(alpha = 0.09f)
                        else Color.White.copy(alpha = 0.62f)
                        else -> if (dark) Color.White.copy(alpha = 0.055f)
                        else Color.White.copy(alpha = 0.45f)
                    }
                    val textColor = when {
                        current -> block.readableTextOn()
                        else -> onStage.copy(alpha = if (abs(offset) == 1) 0.58f else 0.34f)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth(if (current) 1f else if (abs(offset) == 1) 0.88f else 0.72f)
                            .offset(x = (if (offset < 0) 0.dp else 8.dp))
                            .graphicsLayer { rotationZ = when (offset) { -2 -> -1.6f; -1 -> 0.9f; 0 -> -0.4f; 1 -> 1.2f; else -> -0.8f } }
                            .background(block, RoundedCornerShape(1.dp))
                            .padding(
                                horizontal = if (current) 16.dp else 12.dp,
                                vertical = if (current) 12.dp else 7.dp
                            )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 形态 1「整行」：拿掉行号，把整行宽度还给歌词。
                            if (look != 1) {
                                Text(
                                    "%02d".format((frame.index + offset + 1).coerceAtLeast(1)),
                                    color = textColor.copy(alpha = 0.62f),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.width(28.dp)
                                )
                            }
                            if (current) {
                                StageCurrentLine(
                                    frame = frame,
                                    sungColor = textColor,
                                    unsungColor = textColor.copy(alpha = 0.4f),
                                    fontSize = frame.fontSize * 1.14f,
                                    textAlign = TextAlign.Start,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.4.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Text(
                                    line.text,
                                    color = textColor,
                                    fontSize = (frame.fontSize * 0.8f).sp,
                                    fontFamily = frame.family,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (current) {
                            StageSubLines(
                                frame = frame,
                                color = textColor,
                                accent = textColor,
                                textAlign = TextAlign.Start,
                                translationSize = LyricType.translation(frame.fontSize),
                                modifier = Modifier.padding(start = 28.dp, top = 3.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(if (current) 10.dp else 5.dp))
                }
            }
            Text(
                "TEMPERA",
                color = onStage.copy(alpha = 0.28f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(top = 4.dp)
            )
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(frame.colors.primary, frame.colors.secondary, accent).forEach {
                    Box(Modifier.size(7.dp).background(it.copy(alpha = 0.75f), CircleShape))
                }
            }
        }
    }
}

@Composable
private fun TemperaBackground(frame: LyricFrame, dark: Boolean, accent: Color) {
    val base = if (dark) Color(0xFF121013) else Color(0xFFEDE7DA)
    Box(Modifier.fillMaxSize().background(base)) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            // 大色块：避开中间文字区，压在四角
            rotate(-9f, center) {
                drawRoundRect(
                    color = frame.colors.primary.copy(alpha = if (dark) 0.24f else 0.30f),
                    topLeft = Offset(-size.width * 0.18f, size.height * 0.02f),
                    size = Size(size.width * 0.62f, size.height * 0.24f),
                    cornerRadius = CornerRadius(28f, 28f)
                )
                drawRoundRect(
                    color = frame.colors.secondary.copy(alpha = if (dark) 0.20f else 0.24f),
                    topLeft = Offset(size.width * 0.56f, size.height * 0.13f),
                    size = Size(size.width * 0.6f, size.height * 0.2f),
                    cornerRadius = CornerRadius(28f, 28f)
                )
                drawRoundRect(
                    color = accent.copy(alpha = if (dark) 0.16f else 0.20f),
                    topLeft = Offset(-size.width * 0.1f, size.height * 0.7f),
                    size = Size(size.width * 0.66f, size.height * 0.26f),
                    cornerRadius = CornerRadius(28f, 28f)
                )
                drawRoundRect(
                    color = if (dark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.42f),
                    topLeft = Offset(size.width * 0.58f, size.height * 0.64f),
                    size = Size(size.width * 0.54f, size.height * 0.3f),
                    cornerRadius = CornerRadius(28f, 28f)
                )
            }
            // 网点：模拟印刷的半调
            val dotColor = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f)
            for (ix in 0..22) {
                for (iy in 0..40) {
                    val x = ix * size.width / 22f
                    val y = iy * size.height / 40f
                    val distance = kotlin.math.hypot(
                        (x - size.width * 0.5f).toDouble(),
                        (y - size.height * 0.5f).toDouble()
                    ).toFloat()
                    if (distance > size.minDimension * 0.34f) {
                        drawCircle(dotColor, radius = 1.1f, center = Offset(x, y))
                    }
                }
            }
            // 几道粗笔触
            for (i in 0 until 3) {
                drawLine(
                    color = accent.copy(alpha = 0.08f),
                    start = Offset(size.width * (0.05f + i * 0.1f), size.height * 0.2f),
                    end = Offset(size.width * (0.45f + i * 0.15f), size.height * 0.85f),
                    strokeWidth = 26f
                )
            }
        }
    }
}

// ==================== 12 绘光 ====================

/** 绘光：体积光穿过烟雾与浮尘，歌词像被光一点点照亮的刻字。 */
@Composable
internal fun LumiereStage(frame: LyricFrame) {
    val dark = frame.colors.isDark
    val accent = if (dark) frame.colors.primary else frame.colors.primary.readableOn(Color(0xFFF7F2E8), 3.2f)
    val onStage = if (dark) Color.White else Color.Black
    val shimmer = rememberInfiniteTransition(label = "lumiere")
    val phase by shimmer.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing)),
        label = "lumierePhase"
    )
    StageScaffold(
        frame = frame,
        dark = dark,
        accent = accent,
        background = { LumiereBackground(frame, accent, phase, dark) }
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .stageContentPadding(frame, top = 98.dp, bottom = 102.dp, start = 30.dp, end = 30.dp)
        ) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    frame.prev?.text.orEmpty(),
                    color = onStage.copy(alpha = 0.15f),
                    fontSize = (frame.fontSize * 0.78f).sp,
                    fontFamily = frame.family,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(26.dp))
                StageCurrentLine(
                    frame = frame,
                    sungColor = onStage,
                    unsungColor = onStage.copy(alpha = 0.26f),
                    fontSize = frame.fontSize * 1.22f,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    shadow = Shadow(
                        color = accent.copy(alpha = 0.85f),
                        blurRadius = 26f
                    )
                )
                StageSubLines(
                    frame = frame,
                    color = onStage,
                    accent = accent,
                    textAlign = TextAlign.Center,
                    translationSize = LyricType.translation(frame.fontSize),
                    modifier = Modifier.padding(top = 8.dp)
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.46f)
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, accent.copy(alpha = 0.8f), Color.Transparent)
                            )
                        )
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    frame.next?.text.orEmpty(),
                    color = onStage.copy(alpha = 0.18f),
                    fontSize = (frame.fontSize * 0.8f).sp,
                    fontFamily = frame.family,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // 取景器角标
            Canvas(Modifier.fillMaxSize()) {
                val len = size.minDimension * 0.055f
                val inset = 2f
                val stroke = 1.4f
                val c = onStage.copy(alpha = 0.26f)
                drawLine(c, Offset(inset, inset), Offset(inset + len, inset), stroke)
                drawLine(c, Offset(inset, inset), Offset(inset, inset + len), stroke)
                drawLine(c, Offset(size.width - inset, inset), Offset(size.width - inset - len, inset), stroke)
                drawLine(c, Offset(size.width - inset, inset), Offset(size.width - inset, inset + len), stroke)
                drawLine(c, Offset(inset, size.height - inset), Offset(inset + len, size.height - inset), stroke)
                drawLine(c, Offset(inset, size.height - inset), Offset(inset, size.height - inset - len), stroke)
                drawLine(c, Offset(size.width - inset, size.height - inset), Offset(size.width - inset - len, size.height - inset), stroke)
                drawLine(c, Offset(size.width - inset, size.height - inset), Offset(size.width - inset, size.height - inset - len), stroke)
            }
            Text(
                "LUMIÈRE",
                color = onStage.copy(alpha = 0.3f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 4.dp)
            )
            Text(
                "${(frame.progress * 100).toInt()}%",
                color = accent.copy(alpha = 0.55f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun LumiereBackground(frame: LyricFrame, accent: Color, phase: Float, dark: Boolean) {
    val ink = if (dark) Color.White else Color.Black
    Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF151218) else Color(0xFFF9F4EA))) {
        Canvas(Modifier.fillMaxSize()) {
            // 体积光束：从顶部斜切下来
            for (i in 0 until 4) {
                val baseX = size.width * (0.12f + i * 0.24f)
                val sway = sin(phase * 2 * PI + i * 1.4f).toFloat() * 8f
                val path = Path().apply {
                    moveTo(baseX - 34f + sway, -20f)
                    lineTo(baseX + 44f + sway, -20f)
                    lineTo(baseX + 190f + sway, size.height + 20f)
                    lineTo(baseX - 10f + sway, size.height + 20f)
                    close()
                }
                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = if (i == 1) if (dark) 0.20f else 0.16f else if (dark) 0.12f else 0.09f),
                            accent.copy(alpha = if (dark) 0.045f else 0.035f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = size.height
                    )
                )
                drawLine(
                    color = accent.copy(alpha = if (dark) 0.12f else 0.10f),
                    start = Offset(baseX + 6f + sway, -20f),
                    end = Offset(baseX + 92f + sway, size.height + 20f),
                    strokeWidth = 1.2f
                )
            }
            // 烟雾
            val turn = phase * (2f * PI.toFloat())
            val s1 = Offset(
                size.width * (0.38f + 0.1f * sin(turn)),
                size.height * (0.46f + 0.06f * cos(turn))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(ink.copy(alpha = 0.06f), Color.Transparent),
                    center = s1,
                    radius = size.minDimension * 0.54f
                ),
                radius = size.minDimension * 0.54f,
                center = s1
            )
            val s2 = Offset(
                size.width * (0.62f + 0.08f * cos(turn + 1f)),
                size.height * (0.62f + 0.07f * sin(turn + 2f))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(frame.colors.secondary.copy(alpha = if (dark) 0.07f else 0.06f), Color.Transparent),
                    center = s2,
                    radius = size.minDimension * 0.48f
                ),
                radius = size.minDimension * 0.48f,
                center = s2
            )
            // 浮尘：缓慢上浮
            for (i in 0 until 80) {
                val baseX = ((i * 71 + 23) % 100) / 100f * size.width
                val speed = 0.35f + (i % 5) * 0.12f
                val rawY = ((i * 37 + 11) / 100f) - phase * speed
                val y = ((rawY % 1f) + 1f) % 1f * size.height
                val alpha = 0.12f + 0.28f * (sin(phase * 8f + i) * 0.5f + 0.5f)
                drawCircle(
                    color = ink.copy(alpha = alpha * 0.6f),
                    radius = 0.8f + (i % 3) * 0.6f,
                    center = Offset(baseX, y)
                )
            }
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.5f),
                    radius = size.minDimension * 0.46f
                ),
                radius = size.minDimension * 0.46f,
                center = Offset(size.width * 0.5f, size.height * 0.5f)
            )
            // 暗场四角（浅色主题下只做很轻的压边）
            drawRect(
                brush = Brush.radialGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.62f else 0.10f)),
                    center = Offset(size.width * 0.5f, size.height * 0.5f),
                    radius = size.maxDimension * 0.7f
                )
            )
        }
    }
}

// ==================== 13 静止 ====================

/** 静止：歌词静止在画面中央，不做动画，也不加任何背景特效。 */
@Composable
internal fun StillStage(frame: LyricFrame) {
    val dark = frame.colors.isDark
    val bg = if (dark) Color(0xFF0B0B0D) else Color(0xFFF5F2EA)
    val accent = if (dark) frame.colors.primary else frame.colors.primary.readableOn(bg, 3.2f)
    val onStage = if (dark) Color.White else Color.Black
    val look = frame.stageProfile.lookIndex
    StageScaffold(
        frame = frame,
        dark = dark,
        accent = accent,
        background = { Box(Modifier.fillMaxSize().background(bg)) }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .stageContentPadding(frame, top = 30.dp, bottom = 30.dp, start = 34.dp, end = 34.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = frame.horizontal
        ) {
            Text(
                frame.line?.text.orEmpty(),
                color = onStage,
                fontSize = (frame.fontSize * if (look == 1) 1.30f else 1.12f).sp,
                fontFamily = frame.family,
                fontWeight = FontWeight.Medium,
                textAlign = frame.align,
                lineHeight = (frame.fontSize * if (look == 2) 1.78f else 1.4f).sp,
                modifier = Modifier.fillMaxWidth()
            )
            if (look != 3) {
                StageSubLines(
                    frame = frame,
                    color = onStage,
                    accent = accent,
                    textAlign = frame.align,
                    translationSize = LyricType.translation(frame.fontSize),
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }
}
