package com.jun.musiccloud.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.data.Song
import com.jun.musiccloud.player.NeteasePlaylistItem
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape

/**
 * 首页纵向排布的组件族（6.3.3 重写）。
 *
 * 上一版为什么被否：卡片用 112dp 小方图 + 卡下两行小字，一排挤 3.4 张，
 * 文字像贴纸浮在封面下面；标题行还堆了「副标题 + 全部按钮」两个元素，
 * 和参考的「大图 + 通透留白 + 单一标题」完全不是一个气质。
 *
 * 这一版比例按参考截图量过（1272×2800，约 3.1x，换算 dp）：
 *
 *   屏宽 411dp  →  左右边距 18dp，可用 375dp
 *   歌单卡 108dp、间距 8dp  →  三张完整 + 第四张露约 30%（"还能滑"的暗示）
 *   封面 1:1、圆角用 App 的 card 基准（大圆角，不是小方块）
 *   卡下文字：标题 11.5sp 两行 + 副标题 10sp 一行
 *
 * 三个刻意的"减法"：
 *   1. 卡片**不加边框、不加玻璃描边**——参考图里卡片就是纯图 + 圆角，加描边立刻变廉价；
 *   2. 卡下文字**直接压在留白里**，不套容器底色，避免"一坨一坨小方块"；
 *   3. 分区标题 **15sp Bold + 右侧细箭头**，只有一个元素，不堆副标题和按钮。
 */

/** 分区之间的纵向留白。参考图两区之间约 26dp，比上一版更透气。 */
internal val HomeSectionGap = 20.dp

/**
 * 分区自身的额外边距（6.6 起 = 0）。
 *
 * 三轮演进：6.4 是 16（叠在外层 22 上 = 38，和网格的 16 不一致 → 左重右轻）；
 * 6.5 外层改 0、这里 16（全部 16 → 太靠边）；6.6 外层恢复 22、这里归零——
 * **横向边距只由首页 LazyColumn 的 contentPadding 提供**（22dp），
 * 标题、横向行、网格、歌曲行全部同一条线，与上栏搜索框对齐。
 * 留着这个常量是因为 HomeRow / HomeSectionTitle 等十几处引用它，
 * 归零比逐处删 padding 改动小、且以后想整体微调仍只动一处。
 */
internal val HomeSectionSide = 0.dp

/**
 * 分区标题：15sp Bold + 可选右侧箭头。
 *
 * 参考图的标题是「推荐歌单 ›」这种一段式：标题本身就是入口。
 */
@Composable
internal fun HomeSectionTitle(
    title: String,
    colors: JunColors,
    onClick: (() -> Unit)? = null
) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = HomeSectionSide)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = src, indication = null) {
                        Haptics.tap()
                        onClick()
                    }
                } else Modifier
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = colors.text,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (onClick != null) {
            Spacer(Modifier.width(3.dp))
            Text(
                "›",
                color = colors.text.copy(alpha = 0.85f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}

/**
 * 横向列表：两端贴边、间距 8dp、**不做边缘渐隐**。
 *
 * 上一版加了左右渐隐，实际效果是卡片边缘糊掉一块，像渲染不全。
 * 参考图没有渐隐——靠"第 4 张只露 1/3"就足够表达"还能滑"。
 */
@Composable
internal fun HomeRow(content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        // 左右内边距必须与 [HomeSectionTitle] 的 horizontal padding 完全一致，
        // 否则首张卡的左边会和标题的左边缘错开几个 dp —— 这是"看着别扭"的常见来源。
        contentPadding = PaddingValues(horizontal = HomeSectionSide),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
        content = content
    )
}

/**
 * 通用卡片容器：按下时缩到 0.97（绘制期变换，不触发布局）。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun HomeCard(
    modifier: Modifier = Modifier,
    width: Int,
    shape: Shape = JunShape.chip,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, label = "cardPress")
    Column(
        modifier
            .width(width.dp)
            .graphicsLayer {
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = src,
                        indication = null,
                        onLongClick = {
                            Haptics.click()
                            onLongClick()
                        },
                        onClick = {
                            Haptics.tap()
                            onClick()
                        }
                    )
                } else {
                    Modifier.clickable(interactionSource = src, indication = null) {
                        Haptics.tap()
                        onClick()
                    }
                }
            ),
        horizontalAlignment = Alignment.Start,
        content = content
    )
}

/**
 * 歌单 / 排行榜卡（参考图「推荐歌单」那一排）。
 *
 * 结构：1:1 方图（大圆角）+ 卡下标题两行 + 副标题一行。宽 108dp。
 * [overlayTitle] = true 时标题压在图上（雷达/榜单那种图上有字的版式）。
 *
 * 6.7：封面上面盖一层「液态玻璃光泽」（[HomeGlassSheen] + 顶部一道 1px 反光）——
 * 用户原话「推荐歌单、每日新歌、热门歌手、排行榜这些整体要有液态玻璃的质感，
 * 不能就是单纯的图片」。排行榜那张卡 6.3.9 已经是玻璃的，这次把同一种材质铺到
 * 首页所有封面卡上，整屏读起来是同一种东西。
 */
@Composable
internal fun HomeCoverCard(
    title: String,
    cover: String?,
    seedId: Long,
    colors: JunColors,
    subtitle: String = "",
    badge: String? = null,
    badgeColor: Color = Color(0xFFE5484D),
    overlayTitle: Boolean = false,
    onClick: () -> Unit
) {
    HomeCard(width = 100, shape = JunShape.chip, onClick = onClick) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(JunShape.chip)
        ) {
            SongArtwork(
                filePath = cover,
                seedId = seedId,
                isDark = colors.isDark,
                noteIcon = true,
                modifier = Modifier.fillMaxSize()
            )
            // 6.7：液态玻璃光泽（左上柔光 + 顶部 1px 反光边）
            HomeGlassSheen()
            // 角标：参考图实测是**白底红字 + 红色细描边 + 4dp 圆角**，贴在封面左下角
            // （原来做成红底白字是反的，放大图一看就露馅）
            if (badge != null) {
                Text(
                    badge,
                    color = badgeColor,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.94f))
                        .border(0.8.dp, badgeColor.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (overlayTitle) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                            )
                        )
                )
                Text(
                    title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }
        }
        if (!overlayTitle) {
            Spacer(Modifier.height(6.dp))
            // 卡下只给两行：108dp 宽的卡里，两行 12.5sp 刚好放下 18 个字。
            // 再窄就会被省略号吃掉半句，读不出是什么歌单——不如一行结束，干净。
            Text(
                title,
                color = colors.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 15.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = colors.subText,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 歌曲卡：1:1 方图 + 歌名 + 歌手。宽 108dp——与歌单卡同宽，整屏只有一种节奏。
 */
@Composable
internal fun HomeSongCard(
    title: String,
    artist: String,
    cover: String?,
    seedId: Long,
    colors: JunColors,
    playing: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    HomeCard(width = 100, shape = JunShape.chip, onClick = onClick, onLongClick = onLongClick) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(JunShape.chip)
        ) {
            SongArtwork(
                filePath = cover,
                seedId = seedId,
                isDark = colors.isDark,
                noteIcon = true,
                modifier = Modifier.fillMaxSize()
            )
            // 6.7：液态玻璃光泽，与歌单卡/排行榜同一套材质
            HomeGlassSheen()
            if (playing) {
                // 正在播：右下角一颗小圆点，不抢封面
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.primary)
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            title,
            color = if (playing) colors.primary else colors.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 15.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            artist,
            color = colors.subText,
            fontSize = 10.5.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 歌单封面：空串统一成 null（SongArtwork 会画音符占位）。 */
internal fun NeteasePlaylistItem.coverPath(): String? = cover.takeIf { it.isNotBlank() }

/**
 * 6.7：首页卡片的液态玻璃光泽层。
 *
 * 与排行榜玻璃卡（HomeChartGlassCard）同一种材质语言，参数对齐它的实测值：
 *   · 左上角一小片白色柔光（0.36 起手、中段衰减）——高光只在物理上该有的地方；
 *   · 顶部一道 1.2dp 的白反光边（两端淡、中间亮）——玻璃边缘那一笔。
 * 用法：放在已经 clip 过的封面容器**内部**、内容（角标/标题）**之前**。
 */
@Composable
internal fun HomeGlassSheen() {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.36f),
                        0.55f to Color.White.copy(alpha = 0.09f),
                        1f to Color.Transparent,
                        center = Offset(0f, 0f),
                        radius = 250f
                    )
                )
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.2.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.14f),
                            Color.White.copy(alpha = 0.62f),
                            Color.White.copy(alpha = 0.14f)
                        )
                    )
                )
        )
    }
}

/** Song 的封面：在线歌复用 filePath（见 Song.filePath 注释），本地歌是文件路径。 */
internal fun Song.coverPath(): String? = filePath.takeIf { it.isNotBlank() }

/** 播放数格式化：12800 → 1.3万。 */
internal fun fmtPlayCount(n: Long): String = when {
    n >= 100_000_000L -> String.format("%.1f亿", n / 100_000_000.0)
    n >= 10_000L -> String.format("%.1f万", n / 10_000.0)
    n > 0 -> n.toString()
    else -> ""
}
