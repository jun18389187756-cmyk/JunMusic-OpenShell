package com.jun.musiccloud.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.R
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.readableOn
import com.jun.musiccloud.ui.theme.readableTextOn
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/**
 * 绘本式欢迎页：5 页左右滑动。
 *
 * UI 与 App 本体同源：背景是主题底色 + 主/辅色光斑（首页 Aurora 背景的简化版），
 * 文字装在 [junSurface] 玻璃卡片里（材质跟随设置里的实色 / 毛玻璃 / 液态玻璃），
 * 字体走 MaterialTheme.typography（跟随全局字体设置），圆角走 JunShape，
 * 主按钮与首页主色按钮同一套——翻完点「开始探索」接上首页时不会有割裂感。
 *
 * 视差分层：插画慢（0.28）、文字快（0.42）、背景光斑最慢（0.12）；
 * 圆点指示器胶囊形变；末页弹出开始按钮；前几页可跳过。
 *
 * 内容按"新用户真正会卡住的事"排：能听 → 怎么找 → 声音怎么调 → 登录有什么用 → 想听的先记哪。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StoryWelcome(
    colors: JunColors,
    onSkip: () -> Unit,
    onFinish: () -> Unit
) {
    val pages = remember { storyPages() }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            // >0 = 该页已滑到左侧（即将离场），<0 = 还在右侧（即将入场）
            val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            StoryPage(
                page = pages[page],
                index = page,
                offset = offset,
                screenW = screenW,
                colors = colors
            )
        }

        // ===== 跳过：与首页次级按钮同一套观感（淡底药丸）=====
        AnimatedVisibility(
            visible = pagerState.currentPage < pages.lastIndex,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(160)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 10.dp, end = 18.dp)
        ) {
            Row(
                Modifier
                    .junPressable { onSkip() }
                    .clip(JunShape.chip)
                    .background(colors.subText.copy(alpha = 0.14f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .semantics { contentDescription = "跳过新手引导" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "跳过",
                    color = colors.text,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ===== 底部：胶囊指示器 + 翻页 / 开始 =====
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pages.indices.forEach { i ->
                    val w by animateDpAsState(
                        targetValue = if (i == pagerState.currentPage) 22.dp else 7.dp,
                        animationSpec = if (JunMotion.jelly) {
                            spring(dampingRatio = 0.55f, stiffness = 400f)
                        } else tween(260),
                        label = "dotWidth"
                    )
                    Box(
                        Modifier
                            .size(width = w, height = 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == pagerState.currentPage) colors.primary
                                else colors.subText.copy(alpha = 0.3f)
                            )
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            val atLast = pagerState.currentPage == pages.lastIndex
            Box(contentAlignment = Alignment.Center) {
                // 末页：开始按钮弹出 + 轻微呼吸缩放
                androidx.compose.animation.AnimatedVisibility(
                    visible = atLast,
                    enter = scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) +
                        fadeIn(tween(160)),
                    exit = scaleOut(tween(140)) + fadeOut(tween(120))
                ) {
                    val pulse by rememberInfiniteTransition(label = "startPulse").animateFloat(
                        initialValue = 1f,
                        targetValue = 1.045f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1100, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse"
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .graphicsLayer { scaleX = pulse; scaleY = pulse }
                            .junPressable(feedback = PressFeedback.CLICK) { onFinish() }
                            .clip(JunShape.item)
                            .background(colors.primary)
                            .semantics { contentDescription = "开始使用" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "开始探索",
                            color = colors.primary.readableTextOn(),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                // 其余页：上一步 / 继续
                androidx.compose.animation.AnimatedVisibility(
                    visible = !atLast,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(140))
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "上一步",
                            color = if (pagerState.currentPage == 0) colors.subText.copy(alpha = 0.35f)
                            else colors.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .junPressable(enabled = pagerState.currentPage > 0) {
                                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                }
                                .clip(JunShape.item)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                        Row(
                            Modifier
                                .height(52.dp)
                                .width(150.dp)
                                .junPressable {
                                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                }
                                .clip(JunShape.item)
                                .background(colors.primary)
                                .semantics { contentDescription = "下一页" },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "继续",
                                color = colors.primary.readableTextOn(),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(6.dp))
                            Canvas(Modifier.size(14.dp)) {
                                val s = size.minDimension
                                val c = colors.primary.readableTextOn()
                                drawLine(c, Offset(s * 0.25f, s * 0.5f), Offset(s * 0.78f, s * 0.5f), strokeWidth = s * 0.14f, cap = StrokeCap.Round)
                                drawLine(c, Offset(s * 0.62f, s * 0.26f), Offset(s * 0.82f, s * 0.5f), strokeWidth = s * 0.14f, cap = StrokeCap.Round)
                                drawLine(c, Offset(s * 0.62f, s * 0.74f), Offset(s * 0.82f, s * 0.5f), strokeWidth = s * 0.14f, cap = StrokeCap.Round)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryPage(
    page: StoryPageData,
    index: Int,
    offset: Float,
    screenW: Float,
    colors: JunColors
) {
    val illuAlpha = (1f - abs(offset) * 0.55f).coerceIn(0f, 1f)
    val textAlpha = (1f - abs(offset) * 1.15f).coerceIn(0f, 1f)
    val illuTx = offset * screenW * 0.28f
    val textTx = -offset * screenW * 0.42f

    // 插画浮动：4.2 秒一轮，幅度 8dp
    val floatT by rememberInfiniteTransition(label = "storyFloat$index").animateFloat(
        initialValue = 0f,
        targetValue = if (JunMotion.reduced) 0f else 6.283f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "float"
    )
    val floatPx = with(LocalDensity.current) { (sin(floatT) * 8f).dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        // 背景光斑：主/辅色径向渐变，与首页 Aurora 同源
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offset * screenW * 0.12f }
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors.primary.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * page.glowX, size.height * 0.32f),
                    radius = size.minDimension * 0.85f
                ),
                radius = size.minDimension * 0.85f,
                center = Offset(size.width * page.glowX, size.height * 0.32f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors.secondary.copy(alpha = 0.13f), Color.Transparent),
                    center = Offset(size.width * (1f - page.glowX), size.height * 0.78f),
                    radius = size.minDimension * 0.6f
                ),
                radius = size.minDimension * 0.6f,
                center = Offset(size.width * (1f - page.glowX), size.height * 0.78f)
            )
        }

        Column(Modifier.fillMaxSize()) {
            // ===== 品牌行：与首页上栏一致的「Jun / Music」双色标题 =====
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .graphicsLayer { translationX = textTx * 0.5f; alpha = textAlpha }
                    .padding(start = 22.dp, top = 12.dp, end = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.app_logo),
                    contentDescription = "Jun Music",
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Jun",
                    color = colors.primary.readableOn(colors.background),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Music",
                    color = colors.secondary.readableOn(colors.background),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black
                )
            }

            // ===== 插画层 =====
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .graphicsLayer {
                        translationX = illuTx
                        translationY = floatPx
                        alpha = illuAlpha
                        scaleX = 1f - abs(offset) * 0.16f
                        scaleY = 1f - abs(offset) * 0.16f
                    }
                    .padding(horizontal = 30.dp),
                contentAlignment = Alignment.Center
            ) {
                when (index) {
                    0 -> StoryArtTurntable(colors)
                    1 -> StoryArtSearch(colors)
                    2 -> StoryArtSound(colors)
                    3 -> StoryArtAccount(colors)
                    else -> StoryArtTodo(colors)
                }
            }

            // ===== 文字卡片：材质与首页卡片同源 =====
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationX = textTx; alpha = textAlpha }
                    .padding(horizontal = 22.dp)
                    .junSurface(level = GlassLevel.CARD, colors = colors, shape = JunShape.card)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    page.kicker,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    page.title,
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 24.sp, lineHeight = 31.sp),
                    color = colors.text
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    page.body,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp),
                    color = colors.subText,
                    textAlign = TextAlign.Start
                )
            }
            Spacer(Modifier.height(160.dp))
        }
    }
}

private data class StoryPageData(
    val kicker: String,
    val title: String,
    val body: String,
    val glowX: Float
)

/**
 * 5 页内容：把"各个按钮分别是什么"讲完——
 * 能听（曲库）→ 怎么找（搜索）→ 声音怎么调（均衡器）→ 登录有什么用（云同步）→ 想听的先记哪（待办）。
 */
private fun storyPages(): List<StoryPageData> = listOf(
    StoryPageData(
        kicker = "01 / 05 · 曲库",
        title = "整个曲库，点开就响",
        body = "在线榜单、每日推荐、全网搜索都在里面，点一首直接播，\n不用下载、不占一点存储空间。",
        glowX = 0.32f
    ),
    StoryPageData(
        kicker = "02 / 05 · 搜索",
        title = "想听什么，一句就找到",
        body = "搜索框常驻在顶部，歌名、歌手、专辑都能搜；\n结果里直接「下一首播放」或收藏。",
        glowX = 0.68f
    ),
    StoryPageData(
        kicker = "03 / 05 · 声音",
        title = "把声音调成你的",
        body = "播放页里有均衡器、虚拟低音、环绕声场和低音马达，\n随手把耳机调成你耳朵喜欢的样子。",
        glowX = 0.5f
    ),
    StoryPageData(
        kicker = "04 / 05 · 账号",
        title = "登录一下，换手机也不丢",
        body = "注册登录后，「我喜欢的音乐」与歌单会同步到服务器；\n还能再绑一个网易云账号，直接听你的红心歌单。不登录也照常能听。",
        glowX = 0.36f
    ),
    StoryPageData(
        kicker = "05 / 05 · 待办",
        title = "想听的，先记下来",
        body = "「我的 → 听歌待办」：刷到的、被安利的、突然想起的，\n先记一句，回头有空一首首清掉。",
        glowX = 0.6f
    )
)

// ==================== 五幅插画（全矢量，配色跟随主题） ====================

/** 第 1 页：黑胶唱片 + 唱臂 + 漂浮音符 */
@Composable
private fun StoryArtTurntable(colors: JunColors) {
    val accent = colors.primary
    val second = colors.secondary
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val r = size.minDimension * 0.34f

        for (i in 0 until 5) {
            drawCircle(
                color = accent.copy(alpha = 0.10f + i * 0.05f),
                radius = r * (1f - i * 0.13f),
                center = Offset(cx, cy),
                style = Stroke(width = size.minDimension * 0.012f)
            )
        }
        drawCircle(color = accent.copy(alpha = 0.16f), radius = r, center = Offset(cx, cy))
        drawCircle(color = accent.copy(alpha = 0.28f), radius = r, center = Offset(cx, cy), style = Stroke(width = size.minDimension * 0.022f))
        drawCircle(color = second, radius = r * 0.32f, center = Offset(cx, cy))
        drawCircle(color = accent.readableTextOn(), radius = r * 0.07f, center = Offset(cx, cy))

        drawLine(
            color = accent,
            start = Offset(size.width * 0.86f, size.height * 0.16f),
            end = Offset(cx + r * 0.55f, cy - r * 0.62f),
            strokeWidth = size.minDimension * 0.028f,
            cap = StrokeCap.Round
        )
        drawCircle(color = accent, radius = size.minDimension * 0.032f, center = Offset(size.width * 0.86f, size.height * 0.16f))

        fun note(x: Float, y: Float, s: Float) {
            drawCircle(color = second, radius = s * 0.32f, center = Offset(x, y))
            drawLine(
                color = second,
                start = Offset(x + s * 0.28f, y - s * 0.06f),
                end = Offset(x + s * 0.28f, y - s * 1.15f),
                strokeWidth = s * 0.16f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = second,
                start = Offset(x + s * 0.28f, y - s * 1.15f),
                end = Offset(x + s * 0.95f, y - s * 0.86f),
                strokeWidth = s * 0.16f,
                cap = StrokeCap.Round
            )
        }
        note(size.width * 0.16f, size.height * 0.72f, size.minDimension * 0.12f)
        note(size.width * 0.30f, size.height * 0.86f, size.minDimension * 0.08f)
    }
}

/** 第 2 页：放大镜 + 歌曲列表 */
@Composable
private fun StoryArtSearch(colors: JunColors) {
    val accent = colors.primary
    val second = colors.secondary
    Canvas(Modifier.fillMaxSize()) {
        val rowW = size.width * 0.66f
        val rowH = size.height * 0.13f
        val left = size.width * 0.08f
        val rowColor = if (colors.isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)

        for (i in 0 until 3) {
            val top = size.height * (0.38f + i * 0.17f)
            drawRoundRect(
                color = if (i == 1) accent.copy(alpha = 0.18f) else rowColor,
                topLeft = Offset(left, top),
                size = Size(rowW, rowH),
                cornerRadius = CornerRadius(rowH * 0.42f, rowH * 0.42f)
            )
            drawCircle(
                color = if (i == 1) accent else second.copy(alpha = 0.5f),
                radius = rowH * 0.3f,
                center = Offset(left + rowH * 0.42f, top + rowH * 0.5f)
            )
            drawLine(
                color = (if (colors.isDark) Color.White else Color.Black).copy(alpha = 0.22f),
                start = Offset(left + rowH * 0.9f, top + rowH * 0.38f),
                end = Offset(left + rowW * (0.62f + i * 0.06f), top + rowH * 0.38f),
                strokeWidth = size.height * 0.012f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = (if (colors.isDark) Color.White else Color.Black).copy(alpha = 0.12f),
                start = Offset(left + rowH * 0.9f, top + rowH * 0.64f),
                end = Offset(left + rowW * (0.44f + i * 0.05f), top + rowH * 0.64f),
                strokeWidth = size.height * 0.010f,
                cap = StrokeCap.Round
            )
        }

        val gx = size.width * 0.72f
        val gy = size.height * 0.24f
        val gr = size.minDimension * 0.15f
        drawCircle(
            color = accent,
            radius = gr,
            center = Offset(gx, gy),
            style = Stroke(width = size.minDimension * 0.035f)
        )
        drawLine(
            color = accent,
            start = Offset(gx + gr * 0.72f, gy + gr * 0.72f),
            end = Offset(gx + gr * 1.75f, gy + gr * 1.75f),
            strokeWidth = size.minDimension * 0.042f,
            cap = StrokeCap.Round
        )
    }
}

/** 第 3 页：耳机 + 声波 + 心 */
@Composable
private fun StoryArtSound(colors: JunColors) {
    val accent = colors.primary
    val second = colors.secondary
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.46f
        val r = size.minDimension * 0.30f
        val stroke = size.minDimension * 0.05f

        drawArc(
            color = accent,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        val cupW = size.minDimension * 0.14f
        val cupH = size.minDimension * 0.22f
        drawRoundRect(
            color = second,
            topLeft = Offset(cx - r - cupW * 0.5f, cy - cupH * 0.1f),
            size = Size(cupW, cupH),
            cornerRadius = CornerRadius(cupW * 0.45f, cupW * 0.45f)
        )
        drawRoundRect(
            color = second,
            topLeft = Offset(cx + r - cupW * 0.5f, cy - cupH * 0.1f),
            size = Size(cupW, cupH),
            cornerRadius = CornerRadius(cupW * 0.45f, cupW * 0.45f)
        )

        val bars = 7
        val barW = size.minDimension * 0.035f
        val gap = size.minDimension * 0.028f
        val total = bars * barW + (bars - 1) * gap
        var x = cx - total / 2f
        val base = size.height * 0.86f
        val heights = listOf(0.28f, 0.5f, 0.78f, 1f, 0.72f, 0.46f, 0.24f)
        heights.forEach { h ->
            val bh = size.minDimension * 0.22f * h
            drawRoundRect(
                color = accent.copy(alpha = 0.55f + h * 0.4f),
                topLeft = Offset(x, base - bh),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW * 0.5f, barW * 0.5f)
            )
            x += barW + gap
        }

        val hx = size.width * 0.78f
        val hy = size.height * 0.2f
        val hr = size.minDimension * 0.075f
        drawPath(
            path = Path().apply {
                moveTo(hx, hy + hr * 0.85f)
                cubicTo(hx - hr * 1.6f, hy - hr * 0.25f, hx - hr * 0.7f, hy - hr * 1.15f, hx, hy - hr * 0.4f)
                cubicTo(hx + hr * 0.7f, hy - hr * 1.15f, hx + hr * 1.6f, hy - hr * 0.25f, hx, hy + hr * 0.85f)
                close()
            },
            color = second.copy(alpha = 0.9f)
        )
    }
}

/** 第 4 页：账号卡 + 云同步 */
@Composable
private fun StoryArtAccount(colors: JunColors) {
    val accent = colors.primary
    val second = colors.secondary
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // 账号卡
        drawRoundRect(
            color = accent.copy(alpha = 0.16f),
            topLeft = Offset(w * 0.14f, h * 0.3f),
            size = Size(w * 0.66f, h * 0.26f),
            cornerRadius = CornerRadius(w * 0.06f, w * 0.06f)
        )
        drawCircle(color = accent, radius = h * 0.075f, center = Offset(w * 0.26f, h * 0.43f))
        drawLine(
            color = colors.subText.copy(alpha = 0.5f),
            start = Offset(w * 0.36f, h * 0.395f),
            end = Offset(w * 0.68f, h * 0.395f),
            strokeWidth = h * 0.022f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = colors.subText.copy(alpha = 0.3f),
            start = Offset(w * 0.36f, h * 0.475f),
            end = Offset(w * 0.58f, h * 0.475f),
            strokeWidth = h * 0.018f,
            cap = StrokeCap.Round
        )

        // 云：三个圆
        val cx = w * 0.5f
        val cy = h * 0.74f
        val cr = w * 0.09f
        drawCircle(color = second, radius = cr, center = Offset(cx - cr * 0.75f, cy))
        drawCircle(color = second, radius = cr * 0.8f, center = Offset(cx + cr * 0.7f, cy + cr * 0.1f))
        drawCircle(color = second, radius = cr * 1.15f, center = Offset(cx, cy - cr * 0.15f))

        // 同步弧线箭头
        drawArc(
            color = accent,
            startAngle = 30f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(cx - cr * 1.9f, cy - cr * 2.35f),
            size = Size(cr * 3.8f, cr * 2.2f),
            style = Stroke(width = w * 0.016f, cap = StrokeCap.Round)
        )
        drawLine(
            color = accent,
            start = Offset(cx + cr * 1.55f, cy - cr * 1.35f),
            end = Offset(cx + cr * 1.0f, cy - cr * 2.0f),
            strokeWidth = w * 0.016f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = accent,
            start = Offset(cx + cr * 1.55f, cy - cr * 1.35f),
            end = Offset(cx + cr * 1.85f, cy - cr * 1.95f),
            strokeWidth = w * 0.016f,
            cap = StrokeCap.Round
        )
        // 勾：同步完成
        drawLine(
            color = accent,
            start = Offset(w * 0.8f, h * 0.24f),
            end = Offset(w * 0.855f, h * 0.3f),
            strokeWidth = w * 0.022f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = accent,
            start = Offset(w * 0.855f, h * 0.3f),
            end = Offset(w * 0.94f, h * 0.19f),
            strokeWidth = w * 0.022f,
            cap = StrokeCap.Round
        )
    }
}

/** 第 5 页：待办清单 + 加号 */
@Composable
private fun StoryArtTodo(colors: JunColors) {
    val accent = colors.primary
    val second = colors.secondary
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val left = w * 0.14f
        val rowW = w * 0.62f
        val rowH = h * 0.11f

        for (i in 0 until 3) {
            val top = h * (0.26f + i * 0.15f)
            drawRoundRect(
                color = if (colors.isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f),
                topLeft = Offset(left, top),
                size = Size(rowW, rowH),
                cornerRadius = CornerRadius(rowH * 0.4f, rowH * 0.4f)
            )
            val ccx = left + rowH * 0.45f
            val ccy = top + rowH * 0.5f
            val cr = rowH * 0.26f
            if (i < 2) {
                drawCircle(color = accent, radius = cr, center = Offset(ccx, ccy))
                drawLine(
                    color = accent.readableTextOn(),
                    start = Offset(ccx - cr * 0.45f, ccy),
                    end = Offset(ccx - cr * 0.1f, ccy + cr * 0.4f),
                    strokeWidth = cr * 0.32f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = accent.readableTextOn(),
                    start = Offset(ccx - cr * 0.1f, ccy + cr * 0.4f),
                    end = Offset(ccx + cr * 0.55f, ccy - cr * 0.35f),
                    strokeWidth = cr * 0.32f,
                    cap = StrokeCap.Round
                )
            } else {
                drawCircle(color = accent, radius = cr, center = Offset(ccx, ccy), style = Stroke(width = cr * 0.24f))
            }
            drawLine(
                color = colors.subText.copy(alpha = 0.45f),
                start = Offset(left + rowH * 0.85f, ccy),
                end = Offset(left + rowW * (0.62f - i * 0.06f), ccy),
                strokeWidth = h * 0.014f,
                cap = StrokeCap.Round
            )
        }

        // 加号（与真实页面的加号同一造型）
        val fx = w * 0.78f
        val fy = h * 0.76f
        val fr = w * 0.085f
        drawCircle(color = second.copy(alpha = 0.35f), radius = fr * 1.35f, center = Offset(fx, fy))
        drawCircle(color = accent, radius = fr, center = Offset(fx, fy))
        drawLine(
            color = accent.readableTextOn(),
            start = Offset(fx, fy - fr * 0.45f),
            end = Offset(fx, fy + fr * 0.45f),
            strokeWidth = fr * 0.22f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = accent.readableTextOn(),
            start = Offset(fx - fr * 0.45f, fy),
            end = Offset(fx + fr * 0.45f, fy),
            strokeWidth = fr * 0.22f,
            cap = StrokeCap.Round
        )
    }
}
