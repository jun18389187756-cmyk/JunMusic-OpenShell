package com.jun.musiccloud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.readableTextOn
import androidx.compose.ui.window.DialogProperties

/**
 * 舞台预览墙：14 个舞台各一张带自身配色的预览卡，点一下切换，
 * 右上角星标收藏。收藏的舞台会排在最前面，方便在两三个常用舞台之间跳。
 *
 * 不做 14 个真实渲染的缩略图——那等于同时开 14 套粒子/模糊，低端机会直接卡死。
 * 这里用舞台自己的主色渐变 + 样字 + 形态名做「可辨认的预览」，开销可以忽略。
 */
@Composable
internal fun StageGalleryDialog(
    stage: Int,
    favorites: List<Int>,
    colors: JunColors,
    onSelect: (Int) -> Unit,
    onToggleFavorite: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val names = LyricStageCatalog.names
    val ordered = remember(favorites) {
        favorites.filter { it in names.indices } + names.indices.filter { it !in favorites }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .padding(horizontal = 16.dp, vertical = 26.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.surface)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("舞台预览", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text(
                            "点卡片切换；点右上角星标收藏。当前 ${LyricStageCatalog.name(stage)}",
                            color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp
                        )
                    }
                    Text(
                        "完成",
                        color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            // 按压层放在 clip 之前，圆角底才跟文字一起缩
                            .junPressable(feedback = PressFeedback.NONE) { onDismiss() }
                            .clip(JunShape.item)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (favorites.isNotEmpty()) {
                    Text("收藏", color = colors.subText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        favorites.filter { it in names.indices }.forEach { index ->
                            StagePill(
                                label = names[index],
                                selected = index == stage,
                                colors = colors,
                                onClick = { onSelect(index) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                ordered.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { index ->
                            StagePreviewCard(
                                index = index,
                                selected = index == stage,
                                favorite = index in favorites,
                                colors = colors,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelect(index) },
                                onToggleFavorite = { onToggleFavorite(index) }
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun StagePill(
    label: String,
    selected: Boolean,
    colors: JunColors,
    onClick: () -> Unit
) {
    Text(
        label,
        color = if (selected) colors.primary.readableTextOn() else colors.text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .junPressable(feedback = PressFeedback.NONE) { onClick() }
            .clip(JunShape.item)
            .background(if (selected) colors.primary else colors.surface.copy(alpha = 0.6f))
            .padding(horizontal = 11.dp, vertical = 7.dp)
    )
}

@Composable
private fun StagePreviewCard(
    index: Int,
    selected: Boolean,
    favorite: Boolean,
    colors: JunColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val palette = remember(index, colors.isDark) { stagePreviewPalette(index, colors.isDark) }
    val name = LyricStageCatalog.name(index)
    val spec = LyricStageProfiles.of(index)
    Box(
        modifier
            .height(112.dp)
            // 整卡按压：缩放层在 clip/background/border 之前，渐变底与描边跟着一起缩
            .junPressableRow(feedback = PressFeedback.NONE) { onClick() }
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(palette))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.primary else Color.White.copy(alpha = 0.18f),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (favorite) "★" else "☆",
                    color = if (favorite) Color(0xFFFFD76A) else Color.White.copy(alpha = 0.55f),
                    fontSize = 18.sp,
                    modifier = Modifier
                        .semantics { contentDescription = if (favorite) "取消收藏 $name" else "收藏 $name" }
                        .junPressable(feedback = PressFeedback.NONE) { onToggleFavorite() }
                        .padding(horizontal = 2.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "夜色落在肩上",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                name,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                "形态 · ${spec.looks.firstOrNull() ?: "标准"}",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 每个舞台一组可辨认的主色，只用于预览卡，不参与真实渲染。 */
private fun stagePreviewPalette(index: Int, dark: Boolean): List<Color> {
    val a = 0.85f
    return when (index) {
        0 -> listOf(Color(0xFF1C6FA8).copy(alpha = a), Color(0xFF6B2FA0).copy(alpha = a))
        1 -> listOf(Color(0xFF3B2D6E).copy(alpha = a), Color(0xFF151126).copy(alpha = a))
        2 -> listOf(Color(0xFF2D6CDF).copy(alpha = a), Color(0xFF7FB6FF).copy(alpha = a))
        3 -> listOf(Color(0xFF6E4B2A).copy(alpha = a), Color(0xFF2A1A10).copy(alpha = a))
        4 -> listOf(Color(0xFF8A4B6B).copy(alpha = a), Color(0xFF2E5C5A).copy(alpha = a))
        5 -> listOf(Color(0xFFB84A1E).copy(alpha = a), Color(0xFF5E1230).copy(alpha = a))
        6 -> listOf(Color(0xFF8C3B4A).copy(alpha = a), Color(0xFF262B3A).copy(alpha = a))
        7 -> listOf(Color(0xFF5A3B14).copy(alpha = a), Color(0xFF14100A).copy(alpha = a))
        8 -> listOf(Color(0xFF1B2B5E).copy(alpha = a), Color(0xFF0C1626).copy(alpha = a))
        9 -> listOf(Color(0xFF6B4E2E).copy(alpha = a), Color(0xFF241708).copy(alpha = a))
        10 -> listOf(Color(0xFFD8C7A8).copy(alpha = a), Color(0xFF6B5335).copy(alpha = a))
        11 -> listOf(Color(0xFF1F5FA8).copy(alpha = a), Color(0xFF8A2F6E).copy(alpha = a))
        12 -> listOf(Color(0xFF1D4E89).copy(alpha = a), Color(0xFF3A1E6E).copy(alpha = a))
        else -> if (dark) listOf(Color(0xFF2A2A2E), Color(0xFF111113))
        else listOf(Color(0xFFE8E6E1), Color(0xFFB9B5AD))
    }
}
