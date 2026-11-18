package com.jun.musiccloud.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape

/**
 * 空状态图形：一叠"应该有内容"的卡片 + 一枚主题色点缀。
 *
 * 只画轮廓不填满，配合低透明度，落到任何底色上都不会变成一块色斑；
 * 省电动画开着时不做浮动（[JunMotion.reduced]）。
 */
@Composable
internal fun EmptyGlyph(
    colors: JunColors,
    modifier: Modifier = Modifier,
    /** 造型：STACK=一叠卡片（列表类）、DISC=唱片（音频类）、CHECK=勾（任务类） */
    kind: EmptyGlyphKind = EmptyGlyphKind.STACK
) {
    val accent = colors.primary
    val second = colors.secondary
    val outline = (if (colors.isDark) Color.White else Color.Black).copy(alpha = 0.22f)
    Canvas(modifier.size(96.dp)) {
        val w = size.width
        val h = size.height
        when (kind) {
            EmptyGlyphKind.STACK -> {
                for (i in 0 until 3) {
                    drawRoundRect(
                        color = if (i == 2) accent.copy(alpha = 0.28f) else outline,
                        topLeft = Offset(w * (0.16f + i * 0.06f), h * (0.24f + i * 0.08f)),
                        size = Size(w * 0.62f, h * 0.2f),
                        cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
                        style = if (i == 2) androidx.compose.ui.graphics.drawscope.Fill else Stroke(width = w * 0.018f)
                    )
                }
            }
            EmptyGlyphKind.DISC -> {
                drawCircle(color = outline, radius = w * 0.34f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(width = w * 0.03f))
                drawCircle(color = accent.copy(alpha = 0.35f), radius = w * 0.22f, center = Offset(w * 0.5f, h * 0.5f))
                drawCircle(color = second, radius = w * 0.08f, center = Offset(w * 0.5f, h * 0.5f))
            }
            EmptyGlyphKind.CHECK -> {
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(w * 0.2f, h * 0.28f),
                    size = Size(w * 0.6f, h * 0.44f),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                    style = Stroke(width = w * 0.02f)
                )
                drawLine(
                    color = accent,
                    start = Offset(w * 0.36f, h * 0.5f),
                    end = Offset(w * 0.46f, h * 0.6f),
                    strokeWidth = w * 0.05f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                drawLine(
                    color = accent,
                    start = Offset(w * 0.46f, h * 0.6f),
                    end = Offset(w * 0.68f, h * 0.38f),
                    strokeWidth = w * 0.05f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
    }
}

internal enum class EmptyGlyphKind { STACK, DISC, CHECK }

/**
 * 通用空状态：图形 + 标题 + 说明 + 可选的主行动。
 *
 * 之前每个空列表各自写一句灰字（"暂无下载记录" / "还没有歌单…"），
 * 排版、字号、上下留白全都不一样，看着像四个不同的 App。
 * 统一走这里：图形是按语义挑的（列表/音频/任务），行动键只在真有下一步时出现。
 */
@Composable
internal fun EmptyState(
    colors: JunColors,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    glyph: EmptyGlyphKind = EmptyGlyphKind.STACK,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EmptyGlyph(colors = colors, kind = glyph)
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
            color = colors.text,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, lineHeight = 19.sp),
            color = colors.subText,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .junPressable { onAction() }
                    .clip(JunShape.item)
                    .background(colors.primary)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    actionLabel,
                    color = colors.text,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
