package com.jun.musiccloud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.data.Playlists
import com.jun.musiccloud.player.ArtistDetailState
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.data.Song
import com.jun.musiccloud.ui.theme.*

/**
 * 歌手详情页：封面/头像、名字、歌曲数、播放全部 + 歌曲列表。
 * 全屏浮层，返回键或左上角返回箭头关闭。视觉与搜索页/曲库保持一致：
 * 同款玻璃底、同款返回箭头、同款 SongRow。
 */
@Composable
internal fun ArtistDetailScreen(
    detail: ArtistDetailState,
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit,
    onPlayAll: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onToggleLike: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    /** 批量选择上下文（全选范围 = 这个歌手的歌曲列表） */
    select: ListSelect = ListSelect(),
    /** 6.9.5：点开一张专辑 → 复用专辑歌曲弹层 */
    onOpenAlbum: (String, String, String) -> Unit = { _, _, _ -> }
) {
    val glass = isLiquidGlass
    Column(
        Modifier
            .fillMaxSize()
            .then(
                if (glass) Modifier.junSurface(GlassLevel.SHEET, colors, androidx.compose.ui.graphics.RectangleShape)
                else Modifier.background(colors.background)
            )
            .statusBarsPadding()
    ) {
        // ---- 顶栏：返回 + 标题 ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    // 按压层在 clip 之前，圆形底跟箭头一起缩
                    .junPressable { onDismiss() }
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
                    val c = colors.text
                    drawLine(
                        c, Offset(size.width * 0.8f, size.height * 0.08f),
                        Offset(size.width * 0.22f, size.height * 0.5f),
                        strokeWidth = size.minDimension * 0.14f, cap = StrokeCap.Round
                    )
                    drawLine(
                        c, Offset(size.width * 0.22f, size.height * 0.5f),
                        Offset(size.width * 0.8f, size.height * 0.92f),
                        strokeWidth = size.minDimension * 0.14f, cap = StrokeCap.Round
                    )
                }
            }
            Text(
                "歌手",
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp)
        ) {
            item(key = "artist-header") {
                ArtistHeader(detail = detail, colors = colors)
            }

            item(key = "artist-actions") {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        // 触感留在 lambda 里：它原本就被「有歌才响」的条件包着，
                        // 交给 feedback 会变成空列表也震一下，所以这里显式关掉封装的触感。
                        .junPressable(feedback = PressFeedback.NONE) {
                            if (detail.songs.isNotEmpty()) { Haptics.click(); onPlayAll() }
                        }
                        .clip(JunShape.item)
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            JunIcons.Play,
                            contentDescription = null,
                            tint = colors.primary.readableTextOn(),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (detail.songs.isEmpty()) "暂无歌曲" else "播放全部 · ${detail.songs.size} 首",
                            color = colors.primary.readableTextOn(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (detail.songs.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    val allChecked = select.active && detail.songs.all { it.id in select.selectedIds }
                    SelectAllEntry(
                        colors,
                        checked = allChecked
                    ) {
                        if (allChecked) select.onExit()
                        else select.onStart("歌手 · ${detail.name}", detail.songs)
                    }
                }
                }
                Spacer(Modifier.height(10.dp))
                if (detail.notice.isNotBlank()) {
                    Text(
                        detail.notice,
                        color = colors.subText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }
            }

            // 6.9.5：专辑一栏 —— 用户反馈「点开歌手只能看到热度歌曲」，
            // 现在专辑横排放在歌曲列表上方，点开任意一张走专辑歌曲弹层。
            if (detail.albums.isNotEmpty()) {
                item(key = "artist-albums") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "专辑",
                        color = colors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                    )
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(detail.albums, key = { i, album -> "alb-$i-${album.id}" }) { _, album ->
                            Column(
                                Modifier
                                    .width(104.dp)
                                    .junPressable {
                                        Haptics.tap()
                                        onOpenAlbum(album.id, album.name, album.artist)
                                    }
                            ) {
                                AlbumArt(album.pic, album.id, colors)
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    album.name,
                                    color = colors.text,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (detail.loading && detail.songs.isEmpty()) {
                items(6) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .clip(JunShape.item)
                            .background(colors.surface.copy(alpha = 0.55f))
                    )
                }
            } else {
                itemsIndexed(detail.songs, key = { i, song -> "ars-$i-${song.id}" }) { _, song ->
                    SongRow(
                        song = song,
                        active = song.id == state.currentSong?.id,
                        colors = colors,
                        rowSize = state.rowSize,
                        showCover = state.showRowCover,
                        showArtist = false,
                        showPlaylist = state.showRowPlaylist,
                        showLikedBadge = state.showLikedBadge,
                        liked = song.id in state.likedSongIds,
                        playlistNames = if (state.showRowPlaylist) Playlists.playlistsOfSong(song.id) else emptyList(),
                        selectMode = select.active,
                        isSelected = song.id in select.selectedIds,
                        onClick = {
                            if (select.active) select.onToggle(song.id)
                            else { Haptics.tap(); onPlaySong(song) }
                        },
                        onLongPress = {
                            if (select.active) select.onExit() else onAddToPlaylist(song)
                        },
                        onToggleLike = { onToggleLike(song) }
                    )
                }
            }

            item(key = "artist-bottom") {
                Spacer(Modifier.height(30.dp))
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun ArtistHeader(detail: ArtistDetailState, colors: JunColors) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        // 主题色光晕：让头像从背景里“浮”出来，和 App 其它页的玻璃光感一致
        Box(
            Modifier
                .size(220.dp)
                .background(
                    Brush.radialGradient(
                        listOf(colors.primary.copy(alpha = 0.20f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(136.dp)
                    .clip(JunShape.card)
                    .background(colors.card)
                    .border(1.dp, colors.accentBorder.copy(alpha = 0.5f), JunShape.card),
                contentAlignment = Alignment.Center
            ) {
                SongArtwork(
                    filePath = detail.avatar.ifBlank { null },
                    seedId = detail.name.hashCode().toLong(),
                    artworkSize = 128.dp,
                    isDark = colors.isDark,
                    noteIcon = true
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                detail.name,
                color = colors.text,
                fontSize = 25.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    detail.songs.isEmpty() -> if (detail.loading) "歌手 · 正在补全歌曲…" else "歌手"
                    detail.loading -> "歌手 · 已找到 ${detail.songs.size} 首 · 还在补全…"
                    else -> "歌手 · ${detail.songs.size} 首歌曲"
                },
                color = colors.subText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 专辑封面（6.9.5）：复用 ArtworkLoader 的头像缓存通道，加载中给渐变占位 */
@Composable
private fun AlbumArt(url: String, seedId: String, colors: JunColors) {
    var bmp by remember(url) { mutableStateOf(ArtworkLoader.peekAvatar(url)) }
    LaunchedEffect(url) {
        if (bmp != null) return@LaunchedEffect
        ArtworkLoader.loadAvatar(url)?.let { bmp = it }
    }
    val image = bmp
    Box(
        Modifier
            .size(104.dp)
            .clip(JunShape.item)
            .background(if (image == null) colors.surface.copy(alpha = 0.6f) else androidx.compose.ui.graphics.Color.Transparent)
    ) {
        if (image != null) {
            androidx.compose.foundation.Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(104.dp)
            )
        } else {
            Text(
                seedId.takeLast(2).ifBlank { "?" },
                color = colors.subText,
                fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
