package com.jun.musiccloud.ui

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.data.LyricLine
import com.jun.musiccloud.data.LyricParser
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.readableOn
import com.jun.musiccloud.ui.theme.readableTextOn
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ==================== 1 心象 ====================

/** 心象：整句拆成单字散落画面，像脑海里浮起的碎片。 */
@Composable
internal fun CadenzaStage(frame: LyricFrame) {
    val dark = frame.colors.isDark
    val accent = if (dark) frame.colors.primary else frame.colors.primary.readableOn(Color(0xFFF4F0FF), 3.2f)
    val onStage = if (dark) Color.White else Color.Black
    val text = frame.line?.text.orEmpty()
    val chars = remember(text) { text.toList().take(48) }
    val drift = rememberInfiniteTransition(label = "cadenzaDrift")
    val phase by drift.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(15000, easing = LinearEasing)),
        label = "cadenzaPhase"
    )
    StageScaffold(
        frame = frame,
        dark = dark,
        accent = accent,
        background = { CadenzaBackground(frame, phase, dark) }
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .stageContentPadding(frame, top = 84.dp, bottom = 92.dp, start = 24.dp, end = 24.dp)
        ) {
            val count = chars.size.coerceAtLeast(1)
            val cols = when {
                count <= 5 -> count
                count <= 12 -> 4
                count <= 20 -> 5
                else -> 6
            }
            val rows = ((count + cols - 1) / cols).coerceAtLeast(1)
            val cellW = maxWidth / cols
            // 给底部译文留 26dp，长句（最多 48 字、8 行）在横屏也不会压到译文或底栏
            val rowH = minOf((maxHeight - 26.dp) / rows, (frame.fontSize * 2.1f).dp)
            val totalH = rowH * rows
            val originY = (maxHeight - totalH) / 2
            val charSize = frame.fontSize * if (count > 22) 0.88f else if (count > 14) 1.0f else 1.2f
            val look = frame.stageProfile.lookIndex
            val shapedSize = when (look) {
                2 -> charSize * 0.88f   // 窄幅
                3 -> charSize * 1.10f   // 宽幅
                else -> charSize
            }
            val sungCount = remember(frame.line, frame.adjustedMs, frame.lineEndMs) {
                val l = frame.line ?: return@remember 0f
                if (l.hasWords) {
                    LyricParser.wordProgressCharsF(l, frame.adjustedMs, frame.lineEndMs)
                } else {
                    val span = (frame.lineEndMs - l.timeMs).coerceAtLeast(1L)
                    val progress = ((frame.adjustedMs - l.timeMs).toFloat() / span).coerceIn(0f, 1f)
                    progress * l.text.length
                }
            }

            // 背景里的邻句碎片：只留极低透明度，像没浮现完的记忆
            frame.lineAt(-2)?.text?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    color = onStage.copy(alpha = 0.05f),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .widthIn(max = maxWidth * 0.7f)
                        .graphicsLayer { rotationZ = -7f }
                )
            }
            frame.lineAt(2)?.text?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    color = onStage.copy(alpha = 0.045f),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .widthIn(max = maxWidth * 0.66f)
                        .graphicsLayer { rotationZ = 5f }
                )
            }

            chars.forEachIndexed { i, ch ->
                val col = i % cols
                val row = i / cols
                val seed = ((i + 1) * 92821 + frame.index * 68917) and 0x7fffffff
                val jx = 4.dp * (((seed % 13) - 6) / 6f)
                val jy = 5.dp * ((((seed / 11) % 13) - 6) / 6f)
                val rot = ((seed % 17) - 8).toFloat()
                val sung = i < sungCount
                val size = shapedSize * (0.88f + ((seed / 7) % 5) * 0.06f)
                Box(
                    Modifier
                        .offset(x = cellW * col + jx, y = originY + rowH * row + jy)
                        .width(cellW)
                        .height(rowH)
                        .graphicsLayer {
                            translationY = sin(phase + i * 0.72f) * 3.5.dp.toPx()
                            rotationZ = rot
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        ch.toString(),
                        color = if (sung) accent else onStage.copy(alpha = 0.34f),
                        fontSize = size.sp,
                        fontFamily = frame.family,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
            ) {
                StageSubLines(
                    frame = frame,
                    color = onStage,
                    accent = accent,
                    textAlign = frame.align
                )
            }

            // 一道很细的轴，防止散字完全失去重心；形态 1「无光柱」时整条收起。
            if (look != 1) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .alpha(0.16f)
                ) {
                    drawLine(
                        brush = Brush.verticalGradient(
                            listOf(Color.Transparent, accent.copy(alpha = 0.42f), Color.Transparent)
                        ),
                        start = Offset(size.width * 0.5f, size.height * 0.12f),
                        end = Offset(size.width * 0.5f, size.height * 0.88f),
                        strokeWidth = 1f
                    )
                }
            }
        }
    }
}

@Composable
private fun CadenzaBackground(frame: LyricFrame, phase: Float, dark: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    if (dark) listOf(Color(0xFF151827), Color(0xFF221A33), Color(0xFF12141F))
                    else listOf(Color(0xFFF6F2FF), Color(0xFFE9E2F7), Color(0xFFF8F4FF))
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width * (0.38f + 0.12f * sin(phase * 0.7f))
            val cy = size.height * (0.42f + 0.10f * sin(phase * 0.9f + 1f))
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(frame.colors.primary.copy(alpha = if (dark) 0.24f else 0.18f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = size.minDimension * 0.72f
                ),
                radius = size.minDimension * 0.72f,
                center = Offset(cx, cy)
            )
            val cx2 = size.width * (0.68f + 0.08f * sin(phase * 0.6f + 2f))
            val cy2 = size.height * (0.68f + 0.08f * cos(phase * 0.8f))
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(frame.colors.secondary.copy(alpha = if (dark) 0.16f else 0.12f), Color.Transparent),
                    center = Offset(cx2, cy2),
                    radius = size.minDimension * 0.56f
                ),
                radius = size.minDimension * 0.56f,
                center = Offset(cx2, cy2)
            )
            for (i in 0 until 46) {
                val x = ((i * 73 + 19) % 100) / 100f * size.width
                val y = ((i * 47 + 31) % 100) / 100f * size.height
                val twinkle = 0.08f + 0.10f * (sin(phase * 1.4f + i) * 0.5f + 0.5f)
                drawCircle((if (dark) Color.White else Color.Black).copy(alpha = twinkle), radius = 1.2f, center = Offset(x, y))
            }
        }
    }
}

// ==================== 2 云阶 ====================

/** 云阶：歌词沿引导线逐块错落下行，像一级级云中阶梯。 */
@Composable
internal fun PartitaStage(frame: LyricFrame) {
    val dark = frame.colors.isDark
    val accent = if (dark) frame.colors.primary else frame.colors.primary.readableOn(Color(0xFFF3EFE5), 3.2f)
    val onStage = if (dark) Color.White else Color.Black
    StageScaffold(
        frame = frame,
        dark = dark,
        accent = accent,
        background = { PartitaBackground(frame, dark, accent) }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .stageContentPadding(frame, top = 86.dp, bottom = 94.dp, start = 24.dp, end = 24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            for (offset in -2..2) {
                val line = frame.lineAt(offset) ?: continue
                val current = offset == 0
                val rowHeight = (frame.fontSize * if (current) 2.35f else 1.85f).dp
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .offset(x = (offset * 15).dp)
                        .graphicsLayer {
                            alpha = if (current) 1f else (0.42f - abs(offset) * 0.08f).coerceAtLeast(0.12f)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(if (current) 36.dp else 16.dp)
                            .background(
                                if (current) accent else onStage.copy(alpha = 0.24f),
                                RoundedCornerShape(2.dp)
                            )
                    )
                    Spacer(Modifier.width(if (current) 16.dp else 12.dp))
                    Box(Modifier.weight(1f)) {
                        if (current) {
                            StageCurrentLine(
                                frame = frame,
                                sungColor = accent,
                                unsungColor = onStage.copy(alpha = 0.38f),
                                fontSize = frame.fontSize * 1.12f,
                                textAlign = frame.align,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                line.text,
                                color = onStage.copy(alpha = 0.72f),
                                fontSize = (frame.fontSize * 0.82f).sp,
                                fontFamily = frame.family,
                                fontWeight = FontWeight.Medium,
                                textAlign = frame.align,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                // 阶梯之间的细引导线，让错位看起来是同一级轨道
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .offset(x = (offset * 15 + 14).dp)
                        .background(onStage.copy(alpha = if (current) 0.14f else 0.06f))
                )
            }
            if (frame.line != null) {
                Spacer(Modifier.height(14.dp))
                StageSubLines(
                    frame = frame,
                    color = onStage,
                    accent = accent,
                    textAlign = frame.align,
                    modifier = Modifier.padding(start = 18.dp)
                )
            }
        }
    }
}

@Composable
private fun PartitaBackground(frame: LyricFrame, dark: Boolean, accent: Color) {
    val bg = if (dark) Color(0xFF0A0E16) else Color(0xFFF3EFE5)
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    if (dark) listOf(Color(0xFF080C13), Color(0xFF111925), Color(0xFF080B11))
                    else listOf(Color(0xFFF7F4EC), Color(0xFFE9E3D6), Color(0xFFF4F0E7))
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val guide = if (dark) Color.White.copy(alpha = 0.055f) else Color.Black.copy(alpha = 0.06f)
            for (i in -1..3) {
                drawLine(
                    color = guide,
                    start = Offset(size.width * (0.02f + i * 0.18f), 0f),
                    end = Offset(size.width * (0.42f + i * 0.18f), size.height),
                    strokeWidth = 1f
                )
            }
            // 云层：几团极淡的圆，压在引导线后面
            val cloud = if (dark) Color.White.copy(alpha = 0.035f) else Color.White.copy(alpha = 0.35f)
            for (i in 0 until 7) {
                val x = size.width * (0.08f + (i % 4) * 0.26f)
                val y = size.height * (0.14f + (i / 4) * 0.58f)
                drawCircle(cloud, radius = size.minDimension * (0.16f + (i % 3) * 0.04f), center = Offset(x, y))
            }
            // 阶梯刻度：沿引导线排一串小方块
            for (i in 0 until 9) {
                val y = size.height * (0.14f + i * 0.09f)
                val x = size.width * (0.08f + i * 0.035f)
                drawRect(
                    color = accent.copy(alpha = 0.16f),
                    topLeft = Offset(x, y),
                    size = Size(5f, 5f)
                )
            }
        }
    }
}

// ==================== 3 浮名 ====================

/** 浮名：满屏歌词层层堆叠，镜头追着当前句推移，虚实之间浮沉。 */
@Composable
internal fun FumeStage(frame: LyricFrame) {
    val dark = frame.colors.isDark
    val accent = if (dark) frame.colors.primary else frame.colors.primary.readableOn(Color(0xFFF7F2EA), 3.2f)
    val onStage = if (dark) Color.White else Color.Black
    val look = frame.stageProfile.lookIndex
    val camera by animateFloatAsState(
        targetValue = frame.index.toFloat(),
        // 形态 1 是「步进镜头」：不做缓动，像老式幻灯一格一格跳。
        animationSpec = if (look == 1) tween(1, easing = LinearEasing) else tween(760, easing = EaseInOutCubic),
        label = "fumeCamera"
    )
    StageScaffold(
        frame = frame,
        dark = dark,
        accent = accent,
        background = { FumeBackground(frame, accent, dark) }
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .stageContentPadding(frame, top = 88.dp, bottom = 96.dp, start = 26.dp, end = 26.dp)
        ) {
            // 横屏高度只有约 250dp：9 行隧道要收紧行距才能完整落在内容区
            val lineHeight = (frame.fontSize * if (frame.landscape) 1.2f else 1.55f).dp
            val centerY = maxHeight * 0.48f
            val cam = camera - frame.index
            for (offset in -4..4) {
                val line = frame.lineAt(offset) ?: continue
                val pos = offset - cam
                val distance = abs(pos)
                val alpha = if (distance < 0.5f) 1f else (0.34f - distance * 0.065f).coerceIn(0.06f, 0.34f)
                val scale = (1f - distance * 0.055f).coerceIn(0.72f, 1f) * (if (offset == 0) 1.1f else 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(lineHeight)
                        .offset(y = centerY + lineHeight * pos - lineHeight * 0.5f)
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (offset == 0) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .background(accent, RoundedCornerShape(1.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                            Box(Modifier.weight(1f)) {
                                StageCurrentLine(
                                    frame = frame,
                                    sungColor = accent,
                                    unsungColor = onStage.copy(alpha = 0.34f),
                                    fontSize = frame.fontSize * 1.05f,
                                    textAlign = frame.align,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    } else {
                        Text(
                            line.text,
                            color = onStage.copy(alpha = if (offset < 0) 0.9f else 0.78f),
                            fontSize = (frame.fontSize * 0.86f).sp,
                            fontFamily = frame.family,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = frame.align,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = (frame.fontSize * 1.15f).sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            if (frame.line != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 38.dp)
                ) {
                    StageSubLines(
                        frame = frame,
                        color = onStage,
                        accent = accent,
                        textAlign = frame.align
                    )
                }
            }
            // 大标题：竖排在右侧，像海报上的刊头；形态 2/3 去掉这层「印刷符号」。
            if (look <= 1) {
                Text(
                    frame.title.uppercase(),
                    color = onStage.copy(alpha = 0.055f),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 7.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .rotate(90f)
                )
            }
        }
    }
}

@Composable
private fun FumeBackground(frame: LyricFrame, accent: Color, dark: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    if (dark) listOf(Color(0xFF17161C), Color(0xFF241E28), Color(0xFF141318))
                    else listOf(Color(0xFFFAF6EE), Color(0xFFEDE4D8), Color(0xFFF9F4EC))
                )
            )
    ) {
        val drift = rememberInfiniteTransition(label = "fumeFog")
        val phase by drift.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(21000, easing = LinearEasing)),
            label = "fumePhase"
        )
        Canvas(Modifier.fillMaxSize()) {
            val c1 = Offset(
                size.width * (0.28f + 0.12f * sin(phase)),
                size.height * (0.34f + 0.08f * sin(phase * 0.8f))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(frame.colors.primary.copy(alpha = if (dark) 0.17f else 0.13f), Color.Transparent),
                    center = c1,
                    radius = size.minDimension * 0.7f
                ),
                radius = size.minDimension * 0.7f,
                center = c1
            )
            val c2 = Offset(
                size.width * (0.7f + 0.08f * sin(phase * 0.7f + 2f)),
                size.height * (0.64f + 0.1f * cos(phase * 0.6f))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(frame.colors.secondary.copy(alpha = if (dark) 0.12f else 0.10f), Color.Transparent),
                    center = c2,
                    radius = size.minDimension * 0.62f
                ),
                radius = size.minDimension * 0.62f,
                center = c2
            )
            // 层叠的水平细线：像印刷时的套准线
            for (i in 0 until 14) {
                val y = size.height * (0.08f + i * 0.065f)
                drawLine(
                    color = (if (dark) Color.White else Color.Black).copy(alpha = if (dark) 0.026f else 0.035f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
            }
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, accent.copy(alpha = 0.35f), Color.Transparent)
                ),
                start = Offset(0f, size.height * 0.86f),
                end = Offset(size.width, size.height * 0.86f),
                strokeWidth = 1f
            )
        }
    }
}

// ==================== 4 莫奈 ====================

/** 莫奈：海报式版面，左侧歌词轨、右侧肖像、底部频谱。 */
@Composable
internal fun MonetStage(frame: LyricFrame) {
    val dark = frame.colors.isDark
    val paper = if (dark) Color(0xFF121014) else Color(0xFFF3EFE6)
    val accent = if (dark) frame.colors.primary else frame.colors.primary.readableOn(paper, 3.2f)
    val onStage = if (dark) Color.White else Color.Black
    val artwork = rememberStageArtwork(frame.artworkKey)
    val look = frame.stageProfile.lookIndex
    // 画框档：0 画框、1 无框、2 大肖像、3 小肖像。
    val lyricWeight = when (look) { 2 -> 0.94f; 3 -> 1.42f; else -> 1.18f }
    val portraitWeight = when (look) { 2 -> 1.06f; 3 -> 0.58f; else -> 0.82f }
    val portraitShape = if (look == 1) RoundedCornerShape(2.dp) else RoundedCornerShape(16.dp)
    StageScaffold(
        frame = frame,
        dark = dark,
        accent = accent,
        background = { MonetBackground(frame, artwork, paper, accent, dark) }
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .stageContentPadding(frame, top = 92.dp, bottom = 104.dp, start = 22.dp, end = 22.dp)
        ) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(lyricWeight)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "LYRICS / MONET",
                        color = accent.copy(alpha = 0.9f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    for (offset in -3..2) {
                        val line = frame.lineAt(offset) ?: continue
                        val current = offset == 0
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = if (current) 5.dp else 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (current) {
                                Box(
                                    Modifier
                                        .width(5.dp)
                                        .height(5.dp)
                                        .background(accent, CircleShape)
                                )
                                Spacer(Modifier.width(9.dp))
                            } else {
                                Spacer(Modifier.width(14.dp))
                            }
                            if (current) {
                                Box(Modifier.weight(1f)) {
                                    StageCurrentLine(
                                        frame = frame,
                                        sungColor = onStage,
                                        unsungColor = onStage.copy(alpha = 0.28f),
                                        fontSize = frame.fontSize * 1.08f,
                                        textAlign = TextAlign.Start
                                    )
                                }
                            } else {
                                Text(
                                    line.text,
                                    color = onStage.copy(
                                        alpha = if (abs(offset) == 1) 0.42f else 0.22f
                                    ),
                                    fontSize = (frame.fontSize * 0.76f).sp,
                                    fontFamily = frame.family,
                                    fontWeight = if (abs(offset) == 1) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (current) {
                            if (offset == 0) {
                                StageSubLines(
                                    frame = frame,
                                    color = onStage,
                                    accent = accent,
                                    textAlign = TextAlign.Start,
                                    translationSize = LyricType.translation(frame.fontSize),
                                    modifier = Modifier.padding(start = 14.dp, top = 4.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    MonetSpectrum(frame, accent, onStage)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "SIDE A · ${formatStageTime(frame.adjustedMs)}",
                        color = onStage.copy(alpha = 0.34f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(
                    Modifier
                        .weight(portraitWeight)
                        .fillMaxHeight()
                ) {
                    StageArtwork(
                        image = artwork,
                        title = frame.title,
                        colors = frame.colors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .border(
                                width = if (look == 1) 0.dp else 1.dp,
                                color = onStage.copy(alpha = 0.18f),
                                shape = portraitShape
                            ),
                        shape = portraitShape
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "PORTRAIT",
                        color = onStage.copy(alpha = 0.38f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        listOf(frame.colors.primary, frame.colors.secondary, accent).forEach {
                            Box(
                                Modifier
                                    .padding(start = 4.dp)
                                    .size(7.dp)
                                    .background(it.copy(alpha = 0.8f), CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonetSpectrum(frame: LyricFrame, accent: Color, onStage: Color) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
    ) {
        val bars = 30
        val gap = size.width / bars
        val barW = gap * 0.52f
        for (i in 0 until bars) {
            val wave = sin(i * 0.62f + frame.index * 0.7f) * 0.5f + 0.5f
            val h = size.height * (0.12f + wave * (0.34f + frame.progress * 0.5f))
            drawRoundRect(
                color = if (i % 3 == 0) accent.copy(alpha = 0.72f) else onStage.copy(alpha = 0.28f),
                topLeft = Offset(i * gap, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW * 0.5f, barW * 0.5f)
            )
        }
    }
}

@Composable
private fun MonetBackground(
    frame: LyricFrame,
    artwork: ImageBitmap?,
    paper: Color,
    accent: Color,
    dark: Boolean
) {
    Box(Modifier.fillMaxSize().background(paper)) {
        artwork?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(if (dark) 0.32f else 0.24f),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            paper.copy(alpha = 0.98f),
                            paper.copy(alpha = 0.92f),
                            paper.copy(alpha = 0.48f)
                        )
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(paper.copy(alpha = 0.34f), Color.Transparent, paper.copy(alpha = 0.72f))
                    )
                )
        )
        Canvas(Modifier.fillMaxSize()) {
            // 极淡的纸纹与油彩颗粒
            for (i in 0 until 160) {
                val x = ((i * 89 + 7) % 100) / 100f * size.width
                val y = ((i * 53 + 23) % 100) / 100f * size.height
                drawCircle(
                    color = if (dark) Color.White.copy(alpha = 0.035f) else Color.Black.copy(alpha = 0.025f),
                    radius = 0.8f + (i % 3) * 0.4f,
                    center = Offset(x, y)
                )
            }
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.82f, size.height * 0.16f),
                    radius = size.minDimension * 0.38f
                ),
                radius = size.minDimension * 0.38f,
                center = Offset(size.width * 0.82f, size.height * 0.16f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(frame.colors.secondary.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.08f, size.height * 0.86f),
                    radius = size.minDimension * 0.4f
                ),
                radius = size.minDimension * 0.4f,
                center = Offset(size.width * 0.08f, size.height * 0.86f)
            )
        }
    }
}

// ==================== 5 群唱 ====================

/** 群唱：歌词化成聊天气泡左右往返，配头像与表情，像一场对唱。 */
@Composable
internal fun CappellaStage(frame: LyricFrame) {
    val dark = frame.colors.isDark
    val accent = if (dark) frame.colors.primary else frame.colors.primary.readableOn(Color(0xFFF4EFE6), 3.2f)
    val onStage = if (dark) Color.White else Color.Black
    val artwork = rememberStageArtwork(frame.artworkKey)
    val bubbleText = accent.readableTextOn()
    val look = frame.stageProfile.lookIndex
    // 头像档：0 封面、1 纯色、2 无头像、3 紧凑（无头像 + 窄气泡）。
    val showAvatar = look != 2 && look != 3
    val avatarImage = if (look == 1) null else artwork
    val bubbleMax = if (look == 3) 0.68f else 0.76f
    StageScaffold(
        frame = frame,
        dark = dark,
        accent = accent,
        background = { CappellaBackground(frame, dark, accent) }
    ) {
        val dots = rememberInfiniteTransition(label = "cappellaDots")
        val dotPhase by dots.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
            label = "dotPhase"
        )
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .stageContentPadding(frame, top = 92.dp, bottom = 98.dp, start = 14.dp, end = 14.dp)
        ) {
            val maxW = maxWidth
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center
            ) {
                for (offset in -2..1) {
                    val line = frame.lineAt(offset) ?: continue
                    val current = offset == 0
                    val alignEnd = offset % 2 != 0
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        if (!alignEnd && showAvatar) {
                            CappellaAvatar(
                                image = avatarImage,
                                label = if (current) frame.artist else "♪",
                                colors = frame.colors,
                                size = if (current) 30 else 24
                            )
                            Spacer(Modifier.width(7.dp))
                        }
                        Column(
                            Modifier
                                .widthIn(max = maxW * bubbleMax)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomEnd = if (alignEnd) 4.dp else 16.dp,
                                        bottomStart = if (alignEnd) 16.dp else 4.dp
                                    )
                                )
                                .background(
                                    if (current) accent
                                    else if (dark) Color.White.copy(alpha = 0.07f)
                                    else Color.White.copy(alpha = 0.78f)
                                )
                                .border(
                                    width = if (current) 0.dp else 1.dp,
                                    color = if (dark) Color.White.copy(alpha = 0.08f)
                                    else Color.Black.copy(alpha = 0.05f),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .padding(horizontal = 13.dp, vertical = 9.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (current) frame.artist.ifBlank { "主唱" }
                                    else if (alignEnd) "和声" else "听众",
                                    color = if (current) bubbleText.copy(alpha = 0.7f)
                                    else onStage.copy(alpha = 0.4f),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    formatStageTime(line.timeMs),
                                    color = if (current) bubbleText.copy(alpha = 0.5f)
                                    else onStage.copy(alpha = 0.28f),
                                    fontSize = 8.sp
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            if (current) {
                                StageCurrentLine(
                                    frame = frame,
                                    sungColor = bubbleText,
                                    unsungColor = bubbleText.copy(alpha = 0.42f),
                                    fontSize = frame.fontSize * 0.9f,
                                    textAlign = TextAlign.Start
                                )
                                StageSubLines(
                                    frame = frame,
                                    color = bubbleText,
                                    accent = bubbleText,
                                    textAlign = TextAlign.Start,
                                    translationSize = LyricType.translation(frame.fontSize),
                                    romanizationSize = frame.fontSize * 0.48f,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            } else {
                                Text(
                                    line.text,
                                    color = onStage.copy(alpha = 0.72f),
                                    fontSize = (frame.fontSize * 0.78f).sp,
                                    fontFamily = frame.family,
                                    fontWeight = FontWeight.Medium,
                                    lineHeight = (frame.fontSize * 1.02f).sp
                                )
                            }
                        }
                        if (alignEnd && showAvatar) {
                            Spacer(Modifier.width(7.dp))
                            CappellaAvatar(
                                image = null,
                                label = "和",
                                colors = frame.colors,
                                size = 24
                            )
                        }
                    }
                }
                if (frame.lineAt(1) != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Spacer(Modifier.width(37.dp))
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.7f))
                                .padding(horizontal = 13.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (i in 0 until 3) {
                                val active = ((dotPhase * 3f).toInt() % 3) == i
                                Box(
                                    Modifier
                                        .size(5.dp)
                                        .background(
                                            accent.copy(alpha = if (active) 0.9f else 0.3f),
                                            CircleShape
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CappellaAvatar(
    image: ImageBitmap?,
    label: String,
    colors: JunColors,
    size: Int
) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(colors.primary.copy(alpha = 0.9f), colors.secondary.copy(alpha = 0.8f))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                label.take(1).ifBlank { "♪" },
                color = Color.White,
                fontSize = (size * 0.4f).sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun CappellaBackground(frame: LyricFrame, dark: Boolean, accent: Color) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    if (dark) listOf(Color(0xFF0D0B10), Color(0xFF15111A), Color(0xFF0A090D))
                    else listOf(Color(0xFFF7F2E8), Color(0xFFEFE6D8), Color(0xFFF6F0E6))
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 聊天壁纸：稀疏的圆点与斜线
            val dot = if (dark) Color.White.copy(alpha = 0.035f) else Color.Black.copy(alpha = 0.045f)
            for (x in 0..16) {
                for (y in 0..28) {
                    drawCircle(
                        color = dot,
                        radius = 1.1f,
                        center = Offset(
                            size.width * x / 16f + (if (y % 2 == 0) 0f else size.width / 32f),
                            size.height * y / 28f
                        )
                    )
                }
            }
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(size.width * 0.18f, size.height * 0.2f),
                    radius = size.minDimension * 0.5f
                ),
                radius = size.minDimension * 0.5f,
                center = Offset(size.width * 0.18f, size.height * 0.2f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(frame.colors.secondary.copy(alpha = 0.1f), Color.Transparent),
                    center = Offset(size.width * 0.86f, size.height * 0.78f),
                    radius = size.minDimension * 0.45f
                ),
                radius = size.minDimension * 0.45f,
                center = Offset(size.width * 0.86f, size.height * 0.78f)
            )
        }
    }
}

// ==================== 6 倾诉 ====================

/** 倾诉：长句自动断行、随机斜体强调，像有人凑近低声说话。 */
@Composable
internal fun TiltStage(frame: LyricFrame) {
    val dark = frame.colors.isDark
    val paper = if (dark) Color(0xFF171116) else Color(0xFFF6EDE2)
    val look = frame.stageProfile.lookIndex
    val baseAccent = if (dark) frame.colors.primary else frame.colors.primary.readableOn(paper, 3.2f)
    val accent = when (look) {
        1 -> frame.colors.secondary.readableOn(paper, 2.6f)
        2 -> frame.colors.secondary.readableOn(paper, 2.6f)
        else -> baseAccent
    }
    val onStage = if (dark) Color.White else Color.Black
    // 配色档：0 默认、1 主次互换、2 全强调色、3 全主色。
    val bodyColor = when (look) {
        1 -> accent
        2 -> accent
        3 -> baseAccent
        else -> onStage
    }
    StageScaffold(
        frame = frame,
        dark = dark,
        accent = accent,
        background = { TiltBackground(frame, dark, accent) }
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .stageContentPadding(frame, top = 96.dp, bottom = 100.dp, start = 30.dp, end = 22.dp)
        ) {
            Text(
                "“",
                color = accent.copy(alpha = 0.12f),
                fontSize = 150.sp,
                fontWeight = FontWeight.Black,
                fontFamily = frame.family,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-10).dp, y = (-40).dp)
            )
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center
            ) {
                for (offset in -2..1) {
                    val line = frame.lineAt(offset) ?: continue
                    val current = offset == 0
                    val rotation = when (offset) {
                        -2 -> -2.6f
                        -1 -> 1.8f
                        0 -> -0.7f
                        else -> 2.4f
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = if (current) 8.dp else 3.dp)
                            .offset(x = (if (offset < 0) 8.dp else 0.dp))
                            .graphicsLayer {
                                rotationZ = rotation
                                alpha = if (current) 1f else 0.34f
                            }
                    ) {
                        if (current) {
                            val parts = tiltParts(line.text)
                            Text(
                                parts.first(),
                                color = bodyColor,
                                fontSize = (frame.fontSize * 1.22f).sp,
                                fontFamily = frame.family,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = (frame.fontSize * 1.42f).sp,
                                letterSpacing = 0.3.sp
                            )
                            if (parts.size > 1) {
                                Text(
                                    parts[1],
                                    color = accent,
                                    fontSize = (frame.fontSize * 1.08f).sp,
                                    fontFamily = frame.family,
                                    fontWeight = FontWeight.Medium,
                                    fontStyle = FontStyle.Italic,
                                    lineHeight = (frame.fontSize * 1.3f).sp,
                                    modifier = Modifier.padding(start = 26.dp, top = 2.dp)
                                )
                            }
                            StageSubLines(
                                frame = frame,
                                color = onStage,
                                accent = accent,
                                textAlign = TextAlign.Start,
                                translationSize = LyricType.translation(frame.fontSize),
                                modifier = Modifier.padding(start = 26.dp, top = 8.dp)
                            )
                        } else {
                            Text(
                                line.text,
                                color = onStage.copy(alpha = 0.8f),
                                fontSize = (frame.fontSize * 0.8f).sp,
                                fontFamily = frame.family,
                                fontWeight = FontWeight.Medium,
                                fontStyle = if ((frame.index + offset) % 3 == 0) FontStyle.Italic else FontStyle.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = (frame.fontSize * 1.05f).sp
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun tiltParts(text: String): List<String> {
    if (text.length <= 14) return listOf(text)
    val mid = text.length / 2
    val candidates = text.indices.filter {
        val c = text[it]
        c == ' ' || c == '，' || c == '、' || c == '；' || c == '。'
    }
    val cut = candidates.minByOrNull { abs(it - mid) }?.takeIf { it in 3 until text.length - 2 } ?: mid
    val first = text.substring(0, cut).trim().trimEnd('，', '、', '；', '。')
    val second = text.substring(cut).trim().trimStart('，', '、', '；', '。')
    return if (first.isBlank() || second.isBlank()) listOf(text) else listOf(first, second)
}

@Composable
private fun TiltBackground(frame: LyricFrame, dark: Boolean, accent: Color) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    if (dark) listOf(Color(0xFF140F13), Color(0xFF241920), Color(0xFF100C10))
                    else listOf(Color(0xFFF8EFE4), Color(0xFFEADCCB), Color(0xFFF5EBDD))
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = if (dark) 0.18f else 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.24f, size.height * 0.3f),
                    radius = size.minDimension * 0.6f
                ),
                radius = size.minDimension * 0.6f,
                center = Offset(size.width * 0.24f, size.height * 0.3f)
            )
            // 一道从左上斜下来的窗光
            drawLine(
                brush = Brush.linearGradient(
                    listOf(Color.White.copy(alpha = if (dark) 0.06f else 0.5f), Color.Transparent)
                ),
                start = Offset(size.width * 0.1f, 0f),
                end = Offset(size.width * 0.62f, size.height),
                strokeWidth = size.width * 0.16f
            )
            // 底部细尘
            for (i in 0 until 40) {
                val x = ((i * 61 + 13) % 100) / 100f * size.width
                val y = ((i * 37 + 7) % 100) / 100f * size.height
                drawCircle(
                    color = Color.White.copy(alpha = if (dark) 0.05f else 0.18f),
                    radius = 0.9f,
                    center = Offset(x, y)
                )
            }
        }
    }
}

// ==================== 7 回环 ====================

/** 回环：歌词沿倾斜圆环轨道循环推进，大小对比拉开纵深。 */
@Composable
internal fun CladdaghStage(frame: LyricFrame) {
    val dark = frame.colors.isDark
    val accent = if (dark) frame.colors.primary else frame.colors.primary.readableOn(Color(0xFFF7F2E4), 3.2f)
    val onStage = if (dark) Color.White else Color.Black
    val spin = rememberInfiniteTransition(label = "claddaghSpin")
    val spinPhase by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(38000, easing = LinearEasing)),
        label = "claddaghPhase"
    )
    StageScaffold(
        frame = frame,
        dark = dark,
        accent = accent,
        background = { CladdaghBackground(frame, accent, spinPhase, dark) }
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .stageContentPadding(frame, top = 82.dp, bottom = 92.dp, start = 16.dp, end = 16.dp)
        ) {
            val cx = maxWidth / 2
            val cy = maxHeight * 0.47f
            // 横屏时宽度很大，若只按宽度算椭圆短轴，环的上下两端会顶出内容区
            // 压到顶部标题/底部按钮。横向半径照旧铺开，纵向半径用高度封顶，
            // 横屏自动收成一个宽而完整的椭圆环。
            val rx = maxWidth * 0.40f
            val ry = minOf(rx * 0.56f, maxHeight * 0.40f)
            val step = 360f / 7f

            Canvas(
                Modifier
                    .fillMaxSize()
                    .alpha(0.9f)
            ) {
                val center = Offset(cx.toPx(), cy.toPx())
                val radiusX = rx.toPx()
                val radiusY = ry.toPx()
                // 外圈与内圈
                drawOval(
                    color = accent.copy(alpha = 0.16f),
                    topLeft = Offset(center.x - radiusX, center.y - radiusY),
                    size = Size(radiusX * 2, radiusY * 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                )
                drawOval(
                    color = onStage.copy(alpha = 0.06f),
                    topLeft = Offset(center.x - radiusX * 1.14f, center.y - radiusY * 1.14f),
                    size = Size(radiusX * 2.28f, radiusY * 2.28f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
                )
                // 缓慢转动的弧光
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, accent.copy(alpha = 0.55f), Color.Transparent)
                    ),
                    startAngle = spinPhase,
                    sweepAngle = 288f,
                    useCenter = false,
                    topLeft = Offset(center.x - radiusX, center.y - radiusY),
                    size = Size(radiusX * 2, radiusY * 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
                )
                // 轴上刻度
                for (i in 0 until 7) {
                    val angle = Math.toRadians((90f + i * step).toDouble())
                    val x = center.x + radiusX * cos(angle).toFloat()
                    val y = center.y + radiusY * sin(angle).toFloat()
                    drawCircle(accent.copy(alpha = 0.5f), radius = 2.4f, center = Offset(x, y))
                }
                drawLine(
                    color = onStage.copy(alpha = 0.08f),
                    start = Offset(center.x, center.y - radiusY * 1.2f),
                    end = Offset(center.x, center.y + radiusY * 1.2f),
                    strokeWidth = 1f
                )
            }

            for (offset in -3..3) {
                if (offset == 0) continue
                val line = frame.lineAt(offset) ?: continue
                val angle = 90f + offset * step
                val rad = Math.toRadians(angle.toDouble())
                val x = cx + rx * cos(rad).toFloat()
                val y = cy + ry * sin(rad).toFloat()
                val size = (frame.fontSize * 0.7f).coerceIn(11f, 20f)
                Box(
                    Modifier
                        .offset(x = x - 74.dp, y = y - (size * 1.6f).dp)
                        .width(148.dp)
                        .graphicsLayer {
                            val tangent = (angle + 90f) % 360f
                            rotationZ = if (tangent > 90f && tangent < 270f) tangent - 180f else tangent
                            alpha = (0.46f - abs(offset) * 0.06f).coerceAtLeast(0.14f)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        line.text,
                        color = if (abs(offset) == 1) onStage.copy(alpha = 0.9f)
                        else onStage.copy(alpha = 0.7f),
                        fontSize = size.sp,
                        fontFamily = frame.family,
                        fontWeight = if (abs(offset) == 1) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.82f)
                    .padding(top = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "CLADDAGH",
                    color = accent.copy(alpha = 0.55f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp
                )
                Spacer(Modifier.height(10.dp))
                StageCurrentLine(
                    frame = frame,
                    sungColor = accent,
                    unsungColor = onStage.copy(alpha = 0.3f),
                    fontSize = frame.fontSize * 1.12f,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                StageSubLines(
                    frame = frame,
                    color = onStage,
                    accent = accent,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun CladdaghBackground(frame: LyricFrame, accent: Color, phase: Float, dark: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    if (dark) listOf(Color(0xFF171420), Color(0xFF241C2E), Color(0xFF141119))
                    else listOf(Color(0xFFFBF6E9), Color(0xFFF0E7D6), Color(0xFFF9F3E4))
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = if (dark) 0.18f else 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.44f),
                    radius = size.minDimension * 0.64f
                ),
                radius = size.minDimension * 0.64f,
                center = Offset(size.width * 0.5f, size.height * 0.44f)
            )
            // 环绕的细金线
            for (i in 0 until 4) {
                val r = size.minDimension * (0.28f + i * 0.07f)
                drawCircle(
                    color = frame.colors.secondary.copy(alpha = 0.05f),
                    radius = r,
                    center = Offset(size.width * 0.5f, size.height * 0.44f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
                )
            }
            for (i in 0 until 30) {
                val a = (phase + i * 12f) * (Math.PI / 180f).toFloat()
                val r = size.minDimension * (0.3f + (i % 4) * 0.06f)
                drawCircle(
                    color = (if (dark) Color.White else Color.Black).copy(alpha = 0.10f),
                    radius = 1f,
                    center = Offset(
                        size.width * 0.5f + cos(a) * r,
                        size.height * 0.44f + sin(a) * r * 0.6f
                    )
                )
            }
        }
    }
}

internal fun formatStageTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
