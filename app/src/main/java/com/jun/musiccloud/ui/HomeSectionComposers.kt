package com.jun.musiccloud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.data.RecentPlayback
import com.jun.musiccloud.data.Song
import com.jun.musiccloud.player.NeteasePlaylistItem
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape

/**
 * 首页纵向排布的分区组装（6.3.3 按网易云结构重写）。
 *
 * 结构（上栏与底部小播放器不动）：
 *
 *   ① 推荐歌单   ›   横向 108dp 卡片（纯图 + 卡下两行字）
 *   ② 根据你喜爱的歌曲推荐   竖排歌曲行 × 3（方封面 + 歌名 + 标签 + 歌手 + 播放三角）
 *   ③ 猜你喜欢   ›   横向歌曲卡 96dp
 *   ④ 排行榜     ›   横向卡片，标题压图上
 *   ⑤ 最近播放   ›   横向歌曲卡
 *   ── 全部音乐 ──  长列表
 *
 * ★ 本轮按你的要求删掉的东西：
 *   · 顶部「我喜欢 / 本地」那排快捷胶囊**整块去掉**——它和 App 的玻璃材质语言不合，
 *     而且这些入口在「我的」抽屉和上栏都有，首页再摆一遍就是重复。
 *   · 卡片不带边框/描边，卡下文字不套容器底色，避免"一坨一坨小方块"。
 *   · 分区标题不堆副标题和"全部"按钮，只留「标题 ›」一段式。
 */

/** ① 推荐歌单：横向 118dp 卡，标题两行 + 播放数。 */
@Composable
internal fun HomeRecPlaylistSection(
    playlists: List<NeteasePlaylistItem>,
    colors: JunColors,
    onOpen: (String, String) -> Unit,
    onOpenAll: () -> Unit
) {
    if (playlists.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        HomeSectionTitle("推荐歌单", colors, onClick = onOpenAll)
        Spacer(Modifier.height(12.dp))
        HomeRow {
            itemsIndexed(playlists.take(12), key = { _, pl -> "rec-${pl.id}" }) { idx, pl ->
                HomeCoverCard(
                    title = pl.name,
                    cover = pl.coverPath(),
                    seedId = pl.id.hashCode().toLong(),
                    colors = colors,
                    // 参考图里第一张卡有「根据常听推荐」角标——只有第一张有，别每张都贴
                    badge = if (idx == 0) "根据常听推荐" else null,
                    onClick = { onOpen(pl.id, pl.name) }
                )
            }
        }
    }
}

/**
 * ② 竖排歌曲行（参考图第二张的核心版式）。
 *
 * 每行：48dp 方封面 + 歌名 / 标签徽章 + 歌手 + 右侧播放三角。
 * 不做整块卡片底——参考图里行就是白底上的一条，只有封面是圆的。
 */
@Composable
internal fun HomeSongRowList(
    title: String,
    songs: List<Song>,
    colors: JunColors,
    playingId: Long?,
    onPlay: (Song) -> Unit,
    onOpenAll: (() -> Unit)? = null,
    limit: Int = 3
) {
    if (songs.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        HomeSectionTitle(title, colors, onClick = onOpenAll)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = HomeSectionSide)) {
            songs.take(limit).forEach { song ->
                HomeSongRow(
                    song = song,
                    colors = colors,
                    playing = song.id == playingId,
                    onClick = { onPlay(song) }
                )
            }
        }
    }
}

@Composable
private fun HomeSongRow(
    song: Song,
    colors: JunColors,
    playing: Boolean,
    onClick: () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = src, indication = null) {
                Haptics.tap()
                onClick()
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(7.dp))
        ) {
            SongArtwork(
                filePath = song.coverPath(),
                seedId = song.id,
                isDark = colors.isDark,
                noteIcon = true,
                modifier = Modifier.fillMaxSize()
            )
            // 6.7：液态玻璃光泽（每日新歌这几行与首页其它栏目同一材质）
            HomeGlassSheen()
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(
                song.title,
                color = if (playing) colors.primary else colors.text,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 19.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 参考图第二行的结构是：❤ 红心 + 粉底红字标签 + 灰色歌手名。
                // 标签规则沿用 App 里已有的能力：无损 → SQ，热度高 → 热门。
                // 红心只在"这首歌确实被喜欢过"时出现，不无条件画，否则等于骗人。
                val liked = song.likeCount > 0
                val tag = when {
                    song.format.contains("FLAC", true) || song.format.contains("SQ", true) -> "SQ"
                    song.likeCount > 50_000 -> "热门"
                    else -> ""
                }
                if (liked) {
                    Text(
                        "❤",
                        color = Color(0xFFF5385F),
                        fontSize = 9.sp,
                        modifier = Modifier.offset(y = (-0.5).dp)
                    )
                    Spacer(Modifier.width(3.dp))
                }
                if (tag.isNotEmpty()) {
                    Text(
                        tag,
                        color = Color(0xFFE5484D),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFDE8EA))
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    song.artist.ifBlank { "未知歌手" },
                    color = colors.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // 右侧播放指示：参考图是一颗细三角，正在播时换成两条竖线
        Text(
            if (playing) "❚❚" else "▶",
            // 参考图这颗三角是深灰（#565867）、高约 15.5dp，不是浅灰小字
            color = if (playing) colors.primary else colors.text.copy(alpha = 0.55f),
            fontSize = if (playing) 9.sp else 13.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

/**
 * ③ 猜你喜欢 / 最近播放：横向歌曲卡 96dp。
 *
 * 两个分区共用同一个组件，区别只是标题和空判。
 */
@Composable
internal fun HomeSongCardSection(
    title: String,
    songs: List<Song>,
    colors: JunColors,
    playingId: Long?,
    onPlay: (Song) -> Unit,
    onOpenAll: (() -> Unit)? = null,
    limit: Int = 12
) {
    if (songs.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        HomeSectionTitle(title, colors, onClick = onOpenAll)
        Spacer(Modifier.height(12.dp))
        HomeRow {
            itemsIndexed(songs.take(limit), key = { i, s -> "$title-${s.id}-$i" }) { _, song ->
                HomeSongCard(
                    title = song.title,
                    artist = song.artist,
                    cover = song.coverPath(),
                    seedId = song.id,
                    colors = colors,
                    playing = song.id == playingId,
                    onClick = { onPlay(song) }
                )
            }
        }
    }
}

/**
 * ④ 排行榜：**玻璃卡**（6.3.9 重做）。
 *
 * 用户的反馈有两条，都指向同一件事：
 *   1. 「排行榜上面不是有榜单名字吗？不要我标格外的字」——之前卡上有副标题/角标/热度，
 *      一层压一层，名字反而看不清；
 *   2. 「优化一下，符合整体的液态玻璃感」——之前的卡就是一张圆角图 + 黑渐变，
 *      和 App 里其它玻璃面的材质语言脱节。
 *
 * 现在这张卡：封面 → 一层柔和的暗化（保证白字可读）→ 一层玻璃高光（左上斜向白色渐变
 * + 顶部一道 1px 白描边）→ 只写榜名。榜名之下只留一条极细的"更新频率"，字号 8.5sp、
 * 白 55% 透明，属于"看起来有质感"的那一层，不是"额外的字"。
 */
@Composable
internal fun HomeChartSection(
    charts: List<NeteasePlaylistItem>,
    colors: JunColors,
    onOpen: (String, String) -> Unit,
    onOpenAll: () -> Unit
) {
    if (charts.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        HomeSectionTitle("排行榜", colors, onClick = onOpenAll)
        Spacer(Modifier.height(12.dp))
        HomeRow {
            items(charts.take(12), key = { "chart-${it.id}" }) { pl ->
                HomeChartGlassCard(
                    item = pl,
                    colors = colors,
                    onClick = { onOpen(pl.id, pl.name) }
                )
            }
        }
    }
}

/**
 * 排行榜玻璃卡：132×132 的方卡，封面打底，上面盖一层玻璃。
 *
 * 比普通歌单卡（100dp）宽一档：榜单位于首页中段，是"横向视觉锚点"，
 * 宽度不一样才读得出"这一排和上下两排不是一类东西"。
 */
@Composable
private fun HomeChartGlassCard(
    item: NeteasePlaylistItem,
    colors: JunColors,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, label = "chartCardScale")

    Box(
        Modifier
            .width(132.dp)
            .height(132.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .clickable(interactionSource = src, indication = null) {
                Haptics.tap()
                onClick()
            }
    ) {
        SongArtwork(
            filePath = item.coverPath(),
            seedId = item.id.hashCode().toLong(),
            isDark = colors.isDark,
            noteIcon = false,
            modifier = Modifier.fillMaxSize()
        )
        // ① 暗化：只在底部 65% 收暗，上面留亮，封面才不被闷成一块灰
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = 0.18f),
                        1f to Color.Black.copy(alpha = 0.72f)
                    )
                )
        )
        // ② 玻璃：左上斜向的一层薄白 + 顶部 1px 白描边（液态玻璃最关键的两笔）
        //    调过两版：
        //      · 0.16 起手 —— 白底浅封面上完全看不出，等于没做；
        //      · 0.30 线性铺满 —— 整张卡被蒙了一层灰，封面的颜色全被洗掉。
        //    现在改成「左上角一小片柔光 + 中段迅速衰减到 0」：高光只在物理上该有的地方，
        //    其余保持封面原色，这才是玻璃压在图上的样子（而不是给图加一层白纱）。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.42f),
                        0.55f to Color.White.copy(alpha = 0.10f),
                        1f to Color.Transparent,
                        center = Offset(0f, 0f),
                        radius = 260f
                    )
                )
        )
        // 顶部描边：外圈整体 1dp + 中间一段更亮，读起来像玻璃边缘的那道反光
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.4.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.16f),
                            Color.White.copy(alpha = 0.72f),
                            Color.White.copy(alpha = 0.16f)
                        )
                    )
                )
        )
        // ③ 内容：只写榜名 + 一条极细的更新频率
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 11.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                item.name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start
            )
            if (item.subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    item.subtitle,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 8.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

/**
 * ⑤ 最近播放：最近播放的会话。
 *
 * 用会话第一首的封面当卡图、卡下写会话名和首数——和「猜你喜欢」同一套 96dp 卡。
 * 首页不要出现四种卡片样式，否则比现在更乱。
 */
@Composable
internal fun HomeRecentSection(
    sessions: List<RecentPlayback>,
    colors: JunColors,
    onPlay: (RecentPlayback) -> Unit,
    onOpenAll: (() -> Unit)? = null
) {
    if (sessions.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        HomeSectionTitle("最近播放", colors, onClick = onOpenAll)
        Spacer(Modifier.height(12.dp))
        HomeRow {
            itemsIndexed(sessions, key = { i, s -> "recent-$i-${s.title}" }) { _, s ->
                HomeSongCard(
                    title = s.title,
                    artist = "${s.songs.size} 首",
                    cover = s.songs.firstOrNull()?.coverPath(),
                    seedId = s.title.hashCode().toLong(),
                    colors = colors,
                    onClick = { onPlay(s) }
                )
            }
        }
    }
}


// ==================== 6.3.8 新增版式 ====================
//
// 目的：首页不能只有"横滑一排卡"一种形态。网易云的首页之所以耐看，
// 是因为它在一屏之内交替使用**四五种不同的排布**，眼睛不会一直看同一个节奏：
//
//   横滑方卡 → 竖排行 → 圆形头像行 → 两列网格 → 大图横卡 → 三列小格
//
// 这里就是把这几种补齐。每种都只用免登录数据，且数据为空时整块不出现。

/**
 * ⑥ 圆形歌手行：圆形头像 + 名字，一行 4.5 个。
 *
 * 圆形是首页唯一的圆形元素——所有封面卡都是圆角方块，圆形只给"人"，
 * 这样一眼就能分出"这是歌手不是歌单"。
 */
@Composable
internal fun HomeArtistRow(
    artists: List<com.jun.musiccloud.data.NeteaseApi.NsArtist>,
    colors: JunColors,
    onOpen: (com.jun.musiccloud.data.NeteaseApi.NsArtist) -> Unit,
    title: String = "热门歌手"
) {
    if (artists.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        HomeSectionTitle(title, colors)
        Spacer(Modifier.height(12.dp))
        HomeRow {
            itemsIndexed(artists, key = { i, a -> "artist-$i-${a.id}" }) { _, a ->
                // 6.3.9：整块（头像 + 名字）都是可点区域。原来只有 Column 里的
                // Box 挂了点击，手指落在名字上没反应——"点不开"有一半是这个原因。
                Column(
                    Modifier
                        .width(78.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            Haptics.tap()
                            onOpen(a)
                        }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                    ) {
                        SongArtwork(
                            filePath = a.pic,
                            seedId = a.id.hashCode().toLong(),
                            isDark = colors.isDark,
                            noteIcon = true,
                            modifier = Modifier.fillMaxSize()
                        )
                        // 6.7：液态玻璃光泽，与推荐歌单/每日新歌/排行榜同一套材质
                        HomeGlassSheen()
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        a.name,
                        color = colors.text,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * ⑦ 新碟上架：**两列网格**（这是用户点名要的"两排两列"）。
 *
 * 为什么网格用 `Row` 手算而不是 LazyVerticalGrid：首页本身在一个 LazyColumn 里，
 * 嵌套可滚动容器会因为高度无界直接崩。这里只有 4~6 项，手拼两行更快也更稳。
 */
@Composable
internal fun HomeAlbumGrid(
    albums: List<com.jun.musiccloud.data.NeteaseApi.NsAlbum>,
    colors: JunColors,
    onOpen: (com.jun.musiccloud.data.NeteaseApi.NsAlbum) -> Unit,
    title: String = "新碟上架",
    // 6.5：2 列 → 3 列。
    //
    // 2 列时每一项只有「48dp 封面 + 一行字」，横向还剩很多空白，
    // 整块看上去右边虚、左边实，是「整体不居中」里最明显的一处。
    // 3 列刚好把 48dp 封面 + 两行字铺满整行宽度，两边留边也一致。
    columns: Int = 3
) {
    if (albums.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        HomeSectionTitle(title, colors)
        Spacer(Modifier.height(12.dp))
        // 封面在左、文字在右（横向排），比"上图下字"省一半高度；3 列铺满整行
        albums.take(6).chunked(columns).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HomeSectionSide, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { al ->
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(JunShape.item)
                            .clickable(interactionSource = remember { MutableInteractionSource() },
                                indication = null) {
                                Haptics.tap()
                                onOpen(al)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(7.dp))
                        ) {
                            SongArtwork(
                                filePath = al.pic,
                                seedId = al.id.hashCode().toLong(),
                                isDark = colors.isDark,
                                noteIcon = true,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                al.name,
                                color = colors.text,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                al.artist.ifBlank { "未知歌手" },
                                color = colors.subText,
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                // 最后一行不足 columns 时补空位，避免单个条目撑满整行
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * ⑧ 每日新歌：**默认 3 行**，点「更多」原地展开到 10 行。
 *
 * 带序号是为了和「根据你喜爱的歌曲推荐」区分开——那里是推荐，这里是"今天的新东西"，
 * 序号让人有"在翻今日榜单"的感觉。
 *
 * 6.4（用户原话：「只显示三个，然后点开更多，然后再会显示其他的新歌，要不然影响美观」）：
 * 上一版直接铺 10 行，把「热门歌手」「猜你喜欢」「排行榜」全挤出首屏，
 * 首页整体节奏被一个栏目吃掉。现在固定 3 行起手——三行刚好是"看得出这是什么、
 * 又没占满一屏"的量——想看更多的点右边那颗「更多」原地展开，不跳页。
 */
@Composable
internal fun HomeDailySongs(
    songs: List<Song>,
    colors: JunColors,
    playingId: Long?,
    onPlay: (Song) -> Unit,
    dayLabel: String = ""
) {
    if (songs.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val collapsedCount = 3
    val shown = if (expanded) songs.take(10) else songs.take(collapsedCount)
    Column(Modifier.fillMaxWidth()) {
        // 标题行：左边「每日新歌 + 日期」，右边「更多 / 收起」
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = HomeSectionSide)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "每日新歌",
                color = colors.text,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold
            )
            if (dayLabel.isNotEmpty()) {
                Spacer(Modifier.width(7.dp))
                Text(
                    dayLabel,
                    color = colors.subText,
                    fontSize = 10.5.sp
                )
            }
            Spacer(Modifier.weight(1f))
            if (songs.size > collapsedCount) {
                // 只要歌比 3 首多，就一定能切换（展开 / 收起），没有第三种状态
                Row(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            Haptics.tap()
                            expanded = !expanded
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (expanded) "收起" else "更多",
                        color = colors.subText,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        // 不用 ⌄ / ⌃：这两个是技术符号，部分 ROM 的字体没覆盖，会显示成方框。
                        // 「▾ / ▴」在中文字体里一定有，且视觉重量和 12sp 的字配得上。
                        if (expanded) "▴" else "▾",
                        color = colors.subText.copy(alpha = 0.8f),
                        fontSize = 9.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = HomeSectionSide)) {
            shown.forEachIndexed { i, song ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() },
                            indication = null) {
                            Haptics.tap()
                            onPlay(song)
                        }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        (i + 1).toString().padStart(2, '0'),
                        color = if (song.id == playingId) colors.primary else colors.subText.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(24.dp)
                    )
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(6.dp))
                    ) {
                        SongArtwork(
                            filePath = song.coverPath(),
                            seedId = song.id,
                            isDark = colors.isDark,
                            noteIcon = true,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(
                            song.title,
                            color = if (song.id == playingId) colors.primary else colors.text,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            song.artist.ifBlank { "未知歌手" },
                            color = colors.subText,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "▶",
                        color = colors.text.copy(alpha = 0.45f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * ⑨ 风格栏目卡（说唱 / 电子 / 古典 / 粤语）：**大图 + 压在图上的栏目名**。
 *
 * 比普通歌单卡宽一点（132dp），因为它是"入口"而不是"某一首歌"，
 * 宽一点更容易点中，视觉上也更像一个"频道"。
 */
@Composable
internal fun HomeChannelRow(
    channels: List<Pair<String, List<Song>>>,   // (标题, 歌曲)
    colors: JunColors,
    onPlay: (title: String, songs: List<Song>) -> Unit,
    title: String = "风格电台"
) {
    val valid = channels.filter { it.second.isNotEmpty() }
    if (valid.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        HomeSectionTitle(title, colors)
        Spacer(Modifier.height(12.dp))
        HomeRow {
            itemsIndexed(valid, key = { _, c -> "ch-${c.first}" }) { _, c ->
                val (name, songs) = c
                Column(Modifier.width(132.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .clip(JunShape.chip)
                            .clickable(interactionSource = remember { MutableInteractionSource() },
                                indication = null) {
                                Haptics.tap()
                                onPlay(name, songs)
                            }
                    ) {
                        SongArtwork(
                            filePath = songs.firstOrNull()?.coverPath(),
                            seedId = name.hashCode().toLong(),
                            isDark = true,
                            noteIcon = true,
                            modifier = Modifier.fillMaxSize()
                        )
                        // 底部压一层渐变，保证白字在任何封面上都读得清
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))
                                    )
                                )
                        )
                        Text(
                            name,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(horizontal = 9.dp, vertical = 8.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        songs.firstOrNull()?.title.orEmpty(),
                        color = colors.subText,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** 分区之间的统一留白。 */
@Composable
internal fun SectionGap() {
    Spacer(Modifier.height(HomeSectionGap))
}

/**
 * ⓪ 我的歌单（6.9.6）：用户明确要求首页第一排就是自己的歌单。
 * 未登录或还没有歌单时整块不出现。
 */
@Composable
internal fun HomeMyPlaylistSection(
    myPlaylists: List<NeteasePlaylistItem>,
    colors: JunColors,
    onOpen: (String, String) -> Unit,
    onOpenAll: () -> Unit
) {
    if (myPlaylists.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        HomeSectionTitle("我的歌单", colors, onClick = onOpenAll)
        Spacer(Modifier.height(12.dp))
        HomeRow {
            itemsIndexed(myPlaylists.take(12), key = { i, pl -> "mypl-$i-${pl.id}" }) { _, pl ->
                HomeCoverCard(
                    title = pl.name,
                    cover = pl.coverPath(),
                    seedId = pl.id.hashCode().toLong(),
                    colors = colors,
                    onClick = { onOpen(pl.id, pl.name) }
                )
            }
        }
    }
}

/**
 * ⓪½ 我喜欢的音乐（6.9.6）：首页第二排。单张入口卡，点开走网易云"我喜欢"。
 */
@Composable
internal fun HomeLikedSection(
    colors: JunColors,
    onOpen: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        HomeSectionTitle("我喜欢的音乐", colors, onClick = onOpen)
        Spacer(Modifier.height(12.dp))
        HomeRow {
            item(key = "liked-entry") {
                HomeCoverCard(
                    title = "我喜欢的音乐",
                    cover = "",
                    seedId = 0x9E3779B9,
                    colors = colors,
                    badge = "红心",
                    onClick = onOpen
                )
            }
        }
    }
}

/**
 * ⓪ 我的音乐（6.9.7）：用户明确要求「首页第一排就是我喜欢的 + 我的歌单」，
 * 而且强调「是本地的歌单，就是 JunMusic 本地的歌单」。
 *
 * 所以这里用的是 [Playlist]（本机自建歌单）和本地喜欢数量，不是网易云账号歌单：
 *  · 第一张固定是「我喜欢的音乐」；
 *  · 后面依次是自建歌单，卡上直接标歌数；
 *  · 两者都为空（新装 / 未建歌单）时整块不出现，首页自然从"推荐歌单"开始，
 *    正好对应用户说的"没登录/没歌单就直接还是推荐歌单"。
 */
@Composable
internal fun HomeLocalLibrarySection(
    playlists: List<com.jun.musiccloud.data.Playlist>,
    covers: Map<Long, String> = emptyMap(),
    likedCover: String = "",
    likedCount: Int,
    colors: JunColors,
    onOpenLiked: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenAll: () -> Unit
) {
    if (playlists.isEmpty() && likedCount <= 0) return
    Column(Modifier.fillMaxWidth()) {
        HomeSectionTitle("我的音乐", colors, onClick = onOpenAll)
        Spacer(Modifier.height(12.dp))
        HomeRow {
            item(key = "local-liked") {
                HomeCoverCard(
                    title = "我喜欢的音乐",
                    // 6.9.8：红心卡用最近一首喜欢歌的真实封面（VM.refreshHomeCovers 计算）
                    cover = likedCover,
                    seedId = 0x51ED270BL,
                    colors = colors,
                    subtitle = if (likedCount > 0) "$likedCount 首" else "还没有喜欢的歌",
                    badge = "红心",
                    onClick = onOpenLiked
                )
            }
            itemsIndexed(playlists.take(11), key = { i, p -> "local-pl-$i-${p.id}" }) { _, p ->
                HomeCoverCard(
                    title = p.name,
                    // 6.9.8：歌单卡封面 = 正在听/最近听/歌单内歌曲的封面（不再是空占位）
                    cover = covers[p.id].orEmpty(),
                    seedId = p.id,
                    colors = colors,
                    subtitle = "${p.songCount} 首",
                    onClick = { onOpenPlaylist(p.id) }
                )
            }
        }
    }
}
