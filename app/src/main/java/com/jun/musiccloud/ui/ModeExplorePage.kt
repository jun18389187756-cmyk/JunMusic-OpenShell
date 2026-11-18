package com.jun.musiccloud.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.readableOn

/**
 * 模式探索（6.9.10 第三次调整）。
 *
 * 用户这一轮的反馈：
 *  · "整体那个效果不太好，过渡效果、返回的时候那个动画、那些图片的动画太反应太慢了"
 *    → 位移从 60dp 收到 26dp，卡片再做**错落入场**（越靠下越晚到），
 *      观感是"刷地铺上来"而不是"整块慢慢挪"；
 *  · "图书馆模式弄小点就行了，不用弄那么大" → 176dp 压到 112dp，右侧补一个书架小图；
 *  · "这个气氛模式…都要有液态玻璃的效果" → 卡片加内缘高光 + 外描边，
 *    与首页封面卡同一套材质语言；
 *  · "这个也出来个刷新功能吧" → 标题右侧一颗刷新，强制重挑歌单。
 */
@Composable
internal fun ModeExplorePage(
    state: PlayerUiState,
    colors: JunColors,
    onToggleLibraryMode: () -> Unit,
    onEnsureModes: (List<String>) -> Unit = {},
    onOpenMode: (String, String) -> Unit = { _, _ -> },
    onRefreshModes: () -> Unit = {},
    reveal: Float = 1f
) {
    // 进页就补齐模式卡（幂等：VM 侧有去重，重复触发不会重发请求）
    LaunchedEffect(Unit) { onEnsureModes(ModeCatalog.map { it.keyword }) }

    val modeCards = state.extras.modeCards
    // 预取全部模式卡封面：一次 16 张，走 ArtworkLoader 的并发队列 + decodeGate 限流，
    // 滑到这页时封面已经解码好，不会"一格一格往外蹦"。
    LaunchedEffect(modeCards.size) {
        if (modeCards.isNotEmpty()) {
            ArtworkLoader.prefetch(modeCards.values.map { it.cover })
        }
    }

    val r = reveal.coerceIn(0f, 1f)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            // 过渡动画：视差位移 + 缩放 + 淡入，全部挂在 graphicsLayer 上，
            // 只在绘制层求值，滑动时不触发重组。
            .graphicsLayer {
                translationX = (1f - r) * 26.dp.toPx()
                translationY = (1f - r) * 10.dp.toPx()
                scaleX = 0.965f + 0.035f * r
                scaleY = 0.965f + 0.035f * r
                alpha = 0.45f + 0.55f * r
            },
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp, top = 88.dp, bottom = 120.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "mode-title") {
            Column(Modifier.padding(bottom = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "模式探索",
                        color = colors.text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.weight(1f))
                    // 刷新：歌单是别人在维护的，封面/名字/内容都会变
                    Box(
                        Modifier
                            .junPressable { Haptics.click(); onRefreshModes() }
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.primary.copy(alpha = 0.12f))
                            .border(
                                1.dp, colors.primary.copy(alpha = 0.22f), RoundedCornerShape(999.dp)
                            )
                            .padding(horizontal = 11.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "刷新",
                            color = colors.primary.readableOn(colors.surface, 3.5f),
                            fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${ModeCatalog.size} 个气氛模式 · 每个都对应一张真实歌单",
                    color = colors.subText,
                    fontSize = 12.sp
                )
            }
        }

        // ---- 图书馆模式：紧凑横卡（112dp）----
        item(key = "mode-library") {
            LibraryModeCard(
                on = state.libraryMode,
                onToggle = onToggleLibraryMode,
                reveal = r
            )
        }

        // ---- 气氛模式：两列真封面卡 ----
        item(key = "mode-grid-title") {
            Text(
                "气氛模式",
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp, start = 2.dp)
            )
        }
        val cards = modeCards
        items(ModeCatalog.size / 2 + ModeCatalog.size % 2, key = { "mode-row-$it" }) { rowIdx ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (col in 0..1) {
                    val idx = rowIdx * 2 + col
                    val entry = ModeCatalog.getOrNull(idx)
                    if (entry == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val card = cards[entry.keyword]
                        Box(
                            Modifier
                                .weight(1f)
                                // 错落入场：同一行内左右差 6dp，行与行差 10dp，
                                // 越往下越晚到，扫过去是一片"刷"地铺开
                                .graphicsLayer {
                                    val delay = (idx * 0.055f).coerceAtMost(0.55f)
                                    val local = ((r - delay) / (1f - delay).coerceAtLeast(0.01f))
                                        .coerceIn(0f, 1f)
                                    translationY = (1f - local) * 26.dp.toPx()
                                    alpha = 0.30f + 0.70f * local
                                }
                        ) {
                            ModeCoverCard(
                                label = entry.label,
                                subtitle = card?.name.orEmpty(),
                                cover = card?.cover.orEmpty(),
                                seedId = entry.keyword.hashCode().toLong(),
                                fallback = modePalette(idx),
                                onClick = { onOpenMode(entry.keyword, entry.label) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================================
// 模式目录
// =====================================================================================

internal data class ModeEntry(val label: String, val keyword: String)

/**
 * 模式清单：label 是卡上显示的名字，keyword 是拿去搜网易云歌单的关键词。
 * 点开的内容 = 歌单内容，与模式名天然一致。
 */
internal val ModeCatalog = listOf(
    ModeEntry("抖音爆火情歌", "抖音爆火情歌"),
    ModeEntry("流行热歌", "华语流行"),
    ModeEntry("轻音乐", "轻音乐"),
    ModeEntry("情歌对唱", "情歌对唱"),
    ModeEntry("爆火翻唱", "热门翻唱"),
    ModeEntry("纯音乐", "纯音乐"),
    ModeEntry("中文励志", "励志歌曲"),
    ModeEntry("说唱", "中文说唱"),
    ModeEntry("搞笑神曲", "搞笑神曲"),
    ModeEntry("DJ 热歌", "DJ舞曲"),
    ModeEntry("古风", "古风"),
    ModeEntry("魔性神曲", "魔性神曲"),
    ModeEntry("夜晚安眠", "晚安助眠"),
    ModeEntry("90年代", "90年代经典老歌"),
    ModeEntry("日语歌", "日语歌"),
    ModeEntry("民谣", "华语民谣")
)

/**
 * 图书馆模式主卡（6.9.10 紧凑版）。
 *
 * 用户："弄小点就行了，不用弄那么大，加个小图片啥的，让人看顺眼一点"。
 * 176dp 的沉浸大卡压到 112dp，右侧补一张自绘的书架小图，
 * 保留深青渐变 + 月绿光斑 + 玻璃高光，但不再抢整屏视线。
 */
@Composable
private fun LibraryModeCard(
    on: Boolean,
    onToggle: () -> Unit,
    reveal: Float = 1f
) {
    val bgStart by animateColorAsState(
        if (on) Color(0xFF16424A) else Color(0xFF2E313A), tween(220), label = "libBg1"
    )
    val bgEnd by animateColorAsState(
        if (on) Color(0xFF0C5348) else Color(0xFF1D1F26), tween(220), label = "libBg2"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val local = (reveal / 0.35f).coerceIn(0f, 1f)
                alpha = 0.4f + 0.6f * local
                translationY = (1f - local) * 14.dp.toPx()
            }
            .junPressable(scaleDown = 0.975f, onClick = onToggle)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(listOf(bgStart, bgEnd)))
            .border(
                1.dp,
                if (on) Color(0xFF7ED6A5).copy(alpha = 0.30f) else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(22.dp)
            )
            .height(112.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.fillMaxSize()) {
            // 月绿光斑
            Box(
                Modifier
                    .size(96.dp)
                    .align(Alignment.CenterEnd)
                    .graphicsLayer { alpha = if (on) 0.18f else 0.08f }
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (on) Color(0xFF7ED6A5) else Color(0xFF8FA0B8))
            )
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = 14.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 书架小图（自绘）：三本竖书 + 一层隔板
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    BookshelfGlyph(
                        Color.White.copy(alpha = if (on) 0.92f else 0.55f),
                        Color(0xFFB8F0CF).copy(alpha = if (on) 0.95f else 0f)
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "图书馆模式",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (on) "已开启 · 只放纯音乐 / 白噪音 / Lofi"
                        else "安静环境专用 · 自动过滤带人声的歌",
                        color = Color.White.copy(alpha = 0.70f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (on) Color(0xFF7ED6A5).copy(alpha = 0.92f)
                            else Color.White.copy(alpha = 0.16f)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        if (on) "已开启" else "开启",
                        color = if (on) Color(0xFF10321F) else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            HomeGlassSheen()
        }
    }
}

/** 三本竖书 + 隔板的极简书架图标。 */
@Composable
private fun BookshelfGlyph(ink: Color, accent: Color) {
    Canvas(Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        val shelfY = h * 0.78f
        val stroke = w * 0.085f
        // 隔板
        drawLine(ink.copy(alpha = 0.8f), Offset(w * 0.06f, shelfY), Offset(w * 0.94f, shelfY), stroke, StrokeCap.Round)
        // 三本书，高度不一
        val books = listOf(0.12f to 0.62f, 0.40f to 0.50f, 0.66f to 0.68f)
        books.forEachIndexed { i, (x, top) ->
            drawLine(
                if (i == 1 && accent.alpha > 0f) accent else ink,
                Offset(w * x, h * top),
                Offset(w * x, shelfY - stroke * 0.4f),
                w * 0.16f,
                StrokeCap.Round
            )
        }
    }
}

/**
 * 气氛模式卡：歌单真实封面打底，暗化渐变保证白字可读，
 * 玻璃高光 + 内缘描边呼应首页的液态玻璃。
 */
@Composable
private fun ModeCoverCard(
    label: String,
    subtitle: String,
    cover: String,
    seedId: Long,
    fallback: Pair<Color, Color>,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.45f)
            .junPressable(scaleDown = 0.96f, onClick = onClick)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(fallback.first, fallback.second)))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
    ) {
        if (cover.isNotBlank()) {
            SongArtwork(
                filePath = cover,
                seedId = seedId,
                isDark = true,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                label.take(1),
                color = Color.White.copy(alpha = 0.20f),
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.04f),
                            Color.Black.copy(alpha = 0.20f),
                            Color.Black.copy(alpha = 0.66f)
                        )
                    )
                )
        )
        HomeGlassSheen()
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(
                label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 真实歌单名：让人一眼看出这是「真·歌单」而不是我们现编的分类
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 气氛模式卡兜底配色：按位置轮转，冷/暖相间。 */
private fun modePalette(index: Int): Pair<Color, Color> {
    val table = listOf(
        Color(0xFF7B2FF7) to Color(0xFF3B0B6B),
        Color(0xFF2B7FFF) to Color(0xFF0E2A63),
        Color(0xFFE05A47) to Color(0xFF6E1E12),
        Color(0xFF12A594) to Color(0xFF073B35),
        Color(0xFFB5478C) to Color(0xFF4A1038),
        Color(0xFFE8A33D) to Color(0xFF6B3E08),
        Color(0xFF4B6584) to Color(0xFF1A2231),
        Color(0xFF6A8F3C) to Color(0xFF25330F)
    )
    return table[index % table.size]
}
