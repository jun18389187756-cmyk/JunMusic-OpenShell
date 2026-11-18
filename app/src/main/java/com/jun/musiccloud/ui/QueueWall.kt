package com.jun.musiccloud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jun.musiccloud.data.Song
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import kotlin.math.abs

/**
 * 3D 队列墙：把当前播放队列铺成一条可旋转的封面长廊。
 *
 * 每张卡片按它相对视口中心的位置计算 rotationY / scale / alpha，
 * 在 graphicsLayer 里读 LazyListState，滑动时只重绘、不重组。
 * 点封面直接 playAt(index)，和队列列表的播放行为一致。
 */
@Composable
internal fun QueueWallOverlay(
    queue: List<Song>,
    currentIndex: Int,
    colors: JunColors,
    onPlayAt: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0)
    )
    var selected by remember { mutableStateOf(currentIndex.coerceAtLeast(0)) }
    LaunchedEffect(currentIndex) {
        if (currentIndex in queue.indices) {
            selected = currentIndex
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = if (rememberIsLandscape()) 8.dp else 28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("3D 队列墙", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text(
                        "左右滑动 · 点封面播放",
                        color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp
                    )
                }
                Box(
                    Modifier
                        .size(34.dp)
                        // 按压缩放要包住底色，所以放在 clip/background 之前
                        .junPressable(feedback = PressFeedback.NONE) { onDismiss() }
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Light)
                }
            }

            if (queue.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("队列是空的", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                }
                return@Column
            }

            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 72.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(queue, key = { i, s -> "$i-${s.id}" }) { index, song ->
                    val playing = index == currentIndex
                    Column(
                        Modifier
                            .width(196.dp)
                            .graphicsLayer {
                                val info = listState.layoutInfo
                                val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                                val viewportWidth = (info.viewportEndOffset - info.viewportStartOffset)
                                    .toFloat().coerceAtLeast(1f)
                                val center = if (item != null) {
                                    item.offset + item.size / 2f
                                } else {
                                    // 还没测得布局时按索引估算，避免首帧全部扁平
                                    viewportWidth / 2f + (index - currentIndex) * size.width
                                }
                                val viewportCenter = info.viewportStartOffset + viewportWidth / 2f
                                val norm = ((center - viewportCenter) / viewportWidth).coerceIn(-1.6f, 1.6f)
                                rotationY = -norm * 34f
                                scaleX = (1f - abs(norm) * 0.14f).coerceIn(0.62f, 1f)
                                scaleY = scaleX
                                alpha = (1f - abs(norm) * 0.32f).coerceIn(0.25f, 1f)
                                cameraDistance = 14f * density
                            }
                            // 整张卡用行级按压（幅度很小，避免大卡在 3D 墙里缩成一大跳），
                            // 缩放层在 clip/background 之前，底色才跟着一起软
                            .junPressableRow {
                                selected = index
                                onPlayAt(index)
                            }
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(8.dp)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .clip(RoundedCornerShape(14.dp))
                        ) {
                            SongArtwork(
                                filePath = song.filePath,
                                seedId = song.id,
                                isDark = true,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                                    )
                                )
                            )
                            if (playing) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp)
                                        .clip(JunShape.item)
                                        .background(colors.primary)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        "正在播放",
                                        color = colors.primary.readableTextOnForWall(),
                                        fontSize = 9.sp, fontWeight = FontWeight.Black
                                    )
                                }
                            }
                            Column(
                                Modifier.align(Alignment.BottomStart).padding(10.dp)
                            ) {
                                Text(
                                    song.title,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    song.artist,
                                    color = Color.White.copy(alpha = 0.62f),
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${index + 1}",
                                color = Color.White.copy(alpha = 0.42f),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                if (selected == index) "点击播放" else song.album.ifBlank { " " },
                                color = Color.White.copy(alpha = 0.42f),
                                fontSize = 10.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Text(
                "共 ${queue.size} 首 · 当前第 ${(currentIndex + 1).coerceAtLeast(1)} 首",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 18.dp)
            )
        }
    }
    }
}

/** 亮色主题下主色上要用深色字；这里队列墙固定深底，直接按主色亮度判断。 */
private fun Color.readableTextOnForWall(): Color {
    val lum = 0.299f * red + 0.587f * green + 0.114f * blue
    return if (lum > 0.62f) Color.Black else Color.White
}
