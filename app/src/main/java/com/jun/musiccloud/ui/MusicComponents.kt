package com.jun.musiccloud.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import com.jun.musiccloud.data.Recommender
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

import com.jun.musiccloud.audio.BassHaptics
import com.jun.musiccloud.audio.EqualizerController
import com.jun.musiccloud.data.MusicFolder
import com.jun.musiccloud.data.LyricLine
import com.jun.musiccloud.data.LyricParser
import com.jun.musiccloud.data.RepeatMode
import com.jun.musiccloud.data.Playlist
import com.jun.musiccloud.data.Playlists
import com.jun.musiccloud.data.Song
import com.jun.musiccloud.data.formatLabel
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.player.PlayerViewModel
import com.jun.musiccloud.ui.theme.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
// ==================== 推荐与工具 ====================

internal fun formatDuration(ms: Long): String {
    val t = ms / 1000
    return "%d:%02d".format(t / 60, t % 60)
}

/**
 * 点赞数格式化，与网易云一致：
 * - < 10000 直接显示数字
 * - >= 10000 显示「x.x万」（保留一位小数，末尾 .0 去掉）
 */
internal fun formatLikeCount(count: Long): String {
    if (count < 10000L) return count.toString()
    val wan = count / 10000.0
    val rounded = (wan * 10).roundToInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) {
        "${rounded.toInt()}万"
    } else {
        "${rounded}万"
    }
}

@Composable
internal fun RecommendationSection(
    recommendations: List<Song>,
    colors: JunColors,
    onSelect: (Song) -> Unit,
    /** 卡片是否显示歌手（跟随设置「歌单行显示歌手」） */
    showArtist: Boolean = true
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val scrollState = rememberScrollState()

    Column(Modifier.padding(bottom = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "最近播放",
                color = onSheet,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black
            )
        }
        // 横滑卡片：内容两端 alpha 渐变淡出（对内容本身混合，适配任何背景）
        Box {
            val fadeBrush = Brush.horizontalGradient(
                0.00f to Color.Transparent,
                0.04f to Color.Black,
                0.95f to Color.Black,
                1.00f to Color.Transparent
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithCache {
                        onDrawWithContent {
                            drawContent()
                            drawRect(brush = fadeBrush, blendMode = BlendMode.DstIn)
                        }
                    }
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                recommendations.forEach { song ->
                    RecommendCard(
                        song = song,
                        colors = colors,
                        showArtist = showArtist,
                        onClick = { onSelect(song) }
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecommendCard(
    song: Song,
    colors: JunColors,
    showArtist: Boolean = true,
    onClick: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black

    Column(
        Modifier
            // 和全 App 同一套按压弹簧：缩放层放在 clip/background/junSurface 之前，底色才跟着缩
            .width(120.dp)
            .junPressable { onClick() }
            .junSurface(GlassLevel.CARD, colors, JunShape.item)
            .padding(10.dp)
    ) {
        // 封面：SongArtwork（异步加载 + HSV 占位 + 音符图标）
        SongArtwork(
            filePath = song.filePath,
            seedId = song.id,
            modifier = Modifier.fillMaxWidth().height(100.dp),
            isDark = colors.isDark,
            noteIcon = true
        )
        Spacer(Modifier.height(8.dp))
        Text(
            song.title,
            color = onSheet,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            // 固定占两行：无论歌名长短，卡片高度一致
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp
        )
        if (showArtist) {
            Text(
                song.artist,
                color = colors.subText,
                fontSize = 11.sp,
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ==================== 歌曲封面 ====================

/**
 * 通用歌曲封面：异步加载内嵌封面，无封面时显示按歌曲 ID 生成的 HSV 占位色块。
 * 首帧先同步窥缓存（ArtworkLoader.peek），滚动复用/翻卡时命中缓存不闪占位。
 */
@Composable
internal fun SongArtwork(
    filePath: String?,
    seedId: Long,
    modifier: Modifier = Modifier,
    artworkSize: androidx.compose.ui.unit.Dp? = null,
    isDark: Boolean,
    /** 占位是否画音符图标（推荐卡要，行内小图不要） */
    noteIcon: Boolean = false
) {
    val coverVersion = CustomCoverStore.version
    // 6.6（用户原话：「每次我往下滑的时候，那个封面加载不出来，我手指一松才能加载出来」）：
    // 6.3.2 引入的"滚动期间不发加载"在这里退化了——LaunchedEffect 把 scrolling 当 key，
    // 滚动开始时 effect 重启并直接 return，**滚动结束才重启去加载**。
    // 结果是滚动期间新出现的行全部停在占位色块，一松手整屏十几行同时涌入解码闸，
    // 封面要再等几百毫秒才逐个蹦出来，观感就是"封面坏了，松手才好"。
    //
    // 现在滚动中照常加载：并发由 ArtworkLoader 的 decodeGate（4~8 路）控制，
    // 解码在 IO 线程、上屏走绘制层，实测不挤占滚动帧；封面随滚随出。
    var bitmap by remember(filePath, coverVersion) { mutableStateOf(ArtworkLoader.peek(filePath)) }
    LaunchedEffect(filePath, coverVersion) {
        if (bitmap != null) return@LaunchedEffect
        var bmp = ArtworkLoader.load(filePath)
        val isNet = filePath?.startsWith("http") == true
        var attempt = 0
        while (bmp == null && isNet && attempt < 2) {
            attempt++
            kotlinx.coroutines.delay(500L * attempt)
            bmp = ArtworkLoader.load(filePath)
        }
        bmp?.let { bitmap = it }
    }
    // seedId 可能为负（如 hashCode() 结果），Kotlin 的 % 会保留负号，
    // 直接喂给 Color.hsv 会抛 "HSV (...) must be in range (0..360...)"。
    // 用 mod 取非负余数，保证 hue 落在 0..359。
    val hue = seedId.mod(360L).toFloat()
    // R13：占位块原来亮色 V=0.92（白卡上＝一块空白）、暗色 V=0.18（比行底还黑＝一个黑洞），
    // 两种都会被读成"封面坏了"（用户："搜索出来的歌连封面都没有"）。
    // 现在收到中间明度，色相仍按 seedId 稳定取值（同一行每次同一色，不会闪），
    // 并给一块极细描边——缺封面时是"有边界的图位"，不是一坨同色糊面。
    val placeholderColor = Color.hsv(hue, if (isDark) 0.28f else 0.24f, if (isDark) 0.34f else 0.80f)
    val placeholderInk = if (isDark) Color.White.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.22f)
    Box(
        modifier
            .then(artworkSize?.let { Modifier.size(it) } ?: Modifier)
            .clip(JunShape.chip)
            .background(placeholderColor)
            .border(1.dp, if (isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.08f), JunShape.chip),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        // 关键：加载完成的瞬间不要"原地炸出"一张封面。列表滚动时新行不断出现，
        // 每张图都在不同时刻就位，若每张都各自做一个 scale/alpha 动画，屏幕上就是
        // 一片此起彼伏的闪动——主观感受就是"卡、不丝滑"。这里直接换上，
        // 需要过渡感的地方（大图/播放页）自己再做。
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else if (noteIcon) {
            Canvas(Modifier.size(28.dp)) {
                drawCircle(
                    color = placeholderInk,
                    radius = size.minDimension * 0.28f,
                    center = Offset(size.width * 0.42f, size.height * 0.42f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.1f)
                )
                drawLine(
                    color = placeholderInk,
                    start = Offset(size.width * 0.62f, size.height * 0.62f),
                    end = Offset(size.width * 0.88f, size.height * 0.88f),
                    strokeWidth = size.minDimension * 0.1f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        } else {
            Canvas(Modifier.size(artworkSize?.div(2) ?: 28.dp)) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.6f),
                    radius = size.minDimension * 0.28f,
                    center = Offset(size.width * 0.42f, size.height * 0.42f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.1f)
                )
            }
        }
    }
}

// ==================== 快速滚动条 ====================

@Composable
internal fun FastScroller(
    listState: androidx.compose.foundation.lazy.LazyListState,
    colors: JunColors,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
visible: Boolean = true,
    onClick: () -> Unit = {},
     /** 索引 → 该行歌曲标题（用于拖动气泡），返回 null 表示无标签 */
    labelForIndex: (Int) -> String? = { null },
    onDraggingChange: (Boolean) -> Unit = {}
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val density = LocalDensity.current

    val minThumbPx = with(density) { 48.dp.toPx() }
    val edgeGapPx = with(density) { 4.dp.toPx() }
    val idleWpx = with(density) { 4.dp.toPx() }
    val activeWpx = with(density) { 9.dp.toPx() }
    val dragWpx = with(density) { 14.dp.toPx() }

    var isDragging by remember { mutableStateOf(false) }
    // 拖动时 thumb 跟手指的即时分数，不回读列表 scrollProgress（那有量化+延迟，会抽动）
    var dragFraction by remember { mutableStateOf(0f) }
    var trackH by remember { mutableStateOf(0f) }
    var dragIndex by remember { mutableStateOf(0) }
    // 一次拖动内固定映射范围，避免 scrollToItem 改变可见项数量后目标来回漂移。
    var dragMaxFirstIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(isDragging) { onDraggingChange(isDragging) }

    // 滑块长度固定；歌曲数量只影响拖动映射跨度。
    val canScroll by remember {
        derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
    }

    /** 列表当前首项可达的近似最大索引；可滚动但所有项均可见时至少保留一个索引单位。 */
    fun currentMaxFirstIndex(): Int {
        val info = listState.layoutInfo
        val maxByVisible = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0)
        return if (canScroll) maxByVisible.coerceAtLeast(1) else 0
    }

    // 滚动进度：按 LazyColumn 实际可达首项范围归一化，而非 totalItems - 1。
    val scrollProgress by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull() ?: return@derivedStateOf 0f
            val maxFirst = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            if (!canScroll) return@derivedStateOf 0f
            val exactIndex = first.index +
                (if (first.size > 0) (-first.offset.toFloat() / first.size) else 0f)
            (exactIndex / maxFirst).coerceIn(0f, 1f)
        }
    }

    // 活跃态：滚动或拖动时展开，静止 1.1s 后自动收细
    val moving by remember { derivedStateOf { isDragging || listState.isScrollInProgress } }
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(moving) {
        if (moving) active = true else { kotlinx.coroutines.delay(1100); active = false }
    }

    // 三条动画：展开（弹簧）/ 拖动强调（弹簧）/ 翻页淡出（补间）
    val expand by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "scrollerExpand"
    )
    val dragT by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "scrollerDrag"
    )
    val visAlpha by animateFloatAsState(
        targetValue = if (visible && canScroll) 1f else 0f,
        animationSpec = tween(220),
        label = "scrollerAlpha"
    )

    // 滑块几何：轨道和滑块长度固定，拖动跨度由列表可滚动范围决定。
    // thumbYpx/thumbWpx 依赖每帧变化的 dragFraction / expand / dragT——
    // 若在组合层求值，拖动时每帧都触发整块重组（Canvas + 气泡）。改为在
    // 绘制/布局阶段（Canvas draw lambda、气泡 offset lambda）内读取这些状态，
    // 让拖动只走重绘、不走重组，是这条滑条跟手流畅度的关键。
    val thumbHpx = minThumbPx.coerceAtMost(trackH.coerceAtLeast(0f))
    // 供 draw / offset 内复用的即时几何（读取快照状态，仅在对应阶段求值）
    fun currentThumbY(): Float =
        (if (isDragging) dragFraction else scrollProgress) * (trackH - thumbHpx).coerceAtLeast(0f)
    fun currentThumbW(): Float =
        idleWpx + (activeWpx - idleWpx) * expand + (dragWpx - activeWpx) * dragT

    // 拖动：只把手指 y 坐标换算成目标分数写入 dragFraction，不在这里启动滚动。
    // 真正的滚动由下方单一消费者 LaunchedEffect 驱动——每次拖动事件都 launch 一个
    // scrollToItem 协程会导致几十个协程并发竞争、乱序打断，这才是抽动的根因。
    fun updateDragFraction(y: Float) {
        if (trackH <= 0f) return
        val maxFirst = if (isDragging && dragMaxFirstIndex > 0) {
            dragMaxFirstIndex
        } else {
            currentMaxFirstIndex()
        }
        if (maxFirst <= 0) return
        val span = trackH - thumbHpx
        val f = if (span > 1f) ((y - thumbHpx / 2f) / span).coerceIn(0f, 1f)
                else (y / trackH).coerceIn(0f, 1f)
        val exact = f * maxFirst
        dragFraction = f
        // 跨行刻度震动（滑过同一行不重复）
        val idx = exact.toInt().coerceIn(0, maxFirst)
        if (idx != dragIndex) Haptics.tick()
        dragIndex = idx
    }

    // 单一消费者：拖动中订阅 dragFraction，用 collectLatest 让过时的滚动请求
    // 被自动取消，任何时刻只执行最新目标。scrollBy 走 UI 帧同步、无跳变。
    LaunchedEffect(isDragging) {
        if (!isDragging) return@LaunchedEffect
        snapshotFlow { dragFraction }
            .collectLatest { f ->
                val maxFirst = dragMaxFirstIndex
                if (maxFirst <= 0) return@collectLatest
                val targetIndex = (f * maxFirst).toInt().coerceIn(0, maxFirst)
                // 不再用当前可见行高度制造 offset：推荐头/歌曲行高度不同会造成回弹。
                listState.scrollToItem(targetIndex)
            }
    }

    // 触摸区：40dp 宽贴右缘，滑块绘制在最右侧
    Box(
        Modifier
            .fillMaxHeight()
            .width(40.dp)
            .padding(top = topPadding, bottom = bottomPadding)
.graphicsLayer { this.alpha = visAlpha }
             .clickable(
                 interactionSource = remember { MutableInteractionSource() },
                 indication = null,
                 enabled = visible && canScroll,
                 onClick = onClick
             )
             .pointerInput(visible, canScroll) {
                // 不可见或列表不溢出时不注册手势，避免右缘热区拦截页面交互。
                if (!visible || !canScroll) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        dragMaxFirstIndex = currentMaxFirstIndex()
                        if (dragMaxFirstIndex <= 0) return@detectVerticalDragGestures
                        isDragging = true
                        Haptics.click()
                        updateDragFraction(offset.y)
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        updateDragFraction(change.position.y)
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false }
                )
            }
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .onSizeChanged { trackH = it.height.toFloat() }
        ) {
            val h = size.height
            if (h <= 0f) return@Canvas
            val rightEdge = size.width - edgeGapPx

            // 轨道：静止时几乎隐形，活跃时淡入
            val trackW = idleWpx * 0.5f + idleWpx * 0.5f * expand
            drawRoundRect(
                color = onSheet.copy(alpha = 0.05f + 0.10f * expand),
                topLeft = Offset(rightEdge - trackW, 0f),
                size = Size(trackW, h),
                cornerRadius = CornerRadius(trackW / 2f)
            )

            // 滑块：药丸，拖动时变主题色并膨胀。几何在 draw 阶段实时求值，
            // 读取 dragFraction/expand/dragT 快照——状态变化只让本 Canvas 重绘，不重组。
            val thumbWpx = currentThumbW()
            val thumbYpx = currentThumbY()
            val thumbColor = androidx.compose.ui.graphics.lerp(
                onSheet.copy(alpha = 0.28f + 0.42f * expand),
                colors.primary,
                dragT
            )
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(rightEdge - thumbWpx, thumbYpx),
                size = Size(thumbWpx, thumbHpx),
                cornerRadius = CornerRadius(thumbWpx / 2f)
            )
        }

        // 拖动气泡：显示当前歌曲首字 + 序号，跟随滑块中心。
        // dragT 只用于决定气泡是否存在（低频），位置在 offset lambda 内实时算——
        // 拖动时只重跑布局偏移，不重组整个气泡。
        if (dragT > 0.01f) {
            val label = labelForIndex(dragIndex)
            // 只显示 A-Z 首字母，中文/日文/数字/符号统一归为 #
            val fc = label?.trim()?.firstOrNull()?.uppercaseChar()
            val head = if (fc != null && fc in 'A'..'Z') fc.toString() else "#"
            val bubbleSize = 58.dp
            val bubbleHalfPx = with(density) { bubbleSize.toPx() } / 2f
            val bubbleXpx = with(density) { 24.dp.roundToPx() }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            x = -bubbleXpx,
                            y = (currentThumbY() + thumbHpx / 2f - bubbleHalfPx).roundToInt()
                        )
                    }
                    .graphicsLayer {
                        alpha = dragT
                        scaleX = 0.82f + 0.18f * dragT
                        scaleY = 0.82f + 0.18f * dragT
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                    }
                    .size(bubbleSize)
                    .clip(JunShape.item)
                    // 不透明底色 + 主题色描边：任何背景上都看得清
                    .background(colors.surface)
                    .border(1.5.dp, colors.primary.copy(alpha = 0.55f), JunShape.item),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        head,
                        color = colors.text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1
                    )
                    Text(
                        "${dragIndex + 1}",
                        color = colors.primary.readableOn(colors.surface, 3.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ==================== 最近播放（横向滑动歌单页）====================

/**
 * 首页「最近播放」横向滑动区：每页是一张最近听过的歌单/榜单/搜索结果，
 * 左右滑可切回之前听过的歌单，点击「播放整张」即可重播。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecentPlaybackSection(
    playbacks: List<com.jun.musiccloud.data.RecentPlayback>,
    colors: JunColors,
    onPlay: (com.jun.musiccloud.data.RecentPlayback) -> Unit
) {
    if (playbacks.isEmpty()) return
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val pagerState = rememberPagerState(pageCount = { playbacks.size })

    Column(Modifier.padding(bottom = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("最近播放", color = onSheet, fontSize = 15.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            Text(
                "${playbacks.size} 张歌单 · 左右滑动切换",
                color = colors.subText,
                fontSize = 11.sp
            )
        }
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 4.dp),
            pageSpacing = 12.dp
        ) { page ->
            val rp = playbacks[page]
            RecentPlaybackCard(rp = rp, colors = colors, onPlay = { onPlay(rp) })
        }
    }
}

@Composable
private fun RecentPlaybackCard(
    rp: com.jun.musiccloud.data.RecentPlayback,
    colors: JunColors,
    onPlay: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    Column(
        Modifier
            .fillMaxWidth()
            .junSurface(GlassLevel.CARD, colors, JunShape.item)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 取歌单第一首的封面作为歌单封面
            rp.songs.firstOrNull()?.let { song ->
                SongArtwork(
                    filePath = song.filePath,
                    seedId = song.id,
                    modifier = Modifier.size(56.dp),
                    isDark = colors.isDark
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    rp.title,
                    color = onSheet,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${rp.songs.size} 首",
                    color = colors.subText,
                    fontSize = 11.sp
                )
            }
            Box(
                Modifier
                    .junPressable(feedback = PressFeedback.CLICK) { onPlay() }
                    .clip(JunShape.chip)
                    .background(colors.primary.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("播放", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        // 预览前 4 首歌名
        rp.songs.take(4).forEachIndexed { i, song ->
            Text(
                "${i + 1}. ${song.title} - ${song.artist}",
                color = colors.subText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
        if (rp.songs.size > 4) {
            Text(
                "…还有 ${rp.songs.size - 4} 首",
                color = colors.subText.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
        }
    }
}

// ==================== 首页网易云信息流（推荐歌单网格 + 我的歌单）====================

/**
 * 名字看起来是"榜单"而不是"歌单"的推荐项。
 *
 * 6.0 加：网易云 /personalized 会把飙升榜、热歌榜、新歌榜、原创榜这类官方榜混进推荐歌单，
 * 首页网格就成了"前面两排推荐歌单、后面两排榜单"（用户反馈）。榜单在搜索页底部和
 * 「我的 → 排行榜」都有完整的一份，首页不再重复展示，直接把这类名字筛掉。
 * 判据只认名字里的"榜 / 排行 / Top / Chart"，普通歌单里带"最热"这类词不会被误伤。
 */
private fun looksLikeChart(name: String): Boolean {
    val n = name.trim()
    if (n.isEmpty()) return false
    if (n.contains("榜") || n.contains("排行")) return true
    val lower = n.lowercase()
    return lower.startsWith("top") || lower.contains("top") ||
        lower.contains("chart") || lower.contains("heatmap")
}

/**
 * 首页网易云信息流。R9 重排的三条决定：
 *
 * 1. **推荐歌单不再要求登录**。它走 /personalized，是公开只读接口（needCookie=false），
 *    ViewModel 在初始化时无条件拉取；之前整块被 `neteaseLoggedIn` 挡住，才让人以为
 *    「不登录就没有歌单」。现在只有「每日推荐 / 我喜欢的音乐 / 我的歌单」这三块才真的
 *    需要账号，所以登录判断从整块下移到各小节。
 * 2. **首页榜单区撤掉**：搜索页已经有「榜单」入口，同一份 toplist 数据不在首页再占一排。
 * 3. **推荐歌单改成 3 列网格 + 换一批**，横滑一排只能看到 12 张、还得来回拖，
 *    网格一眼能扫完，卡片下方留两行标题位，播放数摆在封面右上角，和网易云首页一致。
 */
@Composable
internal fun NeteaseHomeFeed(
    recPlaylists: List<com.jun.musiccloud.player.NeteasePlaylistItem>,
    myPlaylists: List<com.jun.musiccloud.player.NeteasePlaylistItem>,
    loggedIn: Boolean,
    loading: Boolean,
    loadingMore: Boolean,
    allLoaded: Boolean,
    colors: JunColors,
    onOpen: () -> Unit,
    onSelectPlaylist: (String, String) -> Unit,
    onDaily: () -> Unit,
    onLiked: () -> Unit,
    onReload: () -> Unit,
    onLoadMore: () -> Unit,
    /**
     * 已知榜单 id（官方兜底榜 + 已拉回来的榜单列表）。
     *
     * 6.1 加（放在末尾做默认参数，不破坏原有调用）：名字里带"榜"的已经能挑掉，
     * 但 /personalized 里偶尔有改了名、加了前缀的榜中榜
     * （"网易云XX榜""TOP榜"这类不一定命中名字判据）。
     * 榜单 id 是固定的，所以再按 id 兜一层，保证首页这四排**全是推荐歌单**。
     */
    chartIds: Set<String> = emptySet(),
    /**
     * 首页**后两排**要摆的官方榜单（网易云排行榜：热歌榜、新歌榜、古典榜、飙升榜…）。
     *
     * 6.0 R12：用户把需求说清楚了 —— 「前两排是推荐的歌单，后两排是音乐的排行榜」。
     * 上一轮我把榜单从首页全挑出去（四排都留推荐），方向反了。现在的排法是：
     * 推荐歌单只占 3 列 × 2 行（6 张），紧接着榜单 3 列 × 2 行（6 张），
     * 两个池子互不重叠（推荐池仍按名字 + id 把榜剔掉，榜自己单独一份数据源）。
     */
    charts: List<com.jun.musiccloud.player.NeteasePlaylistItem> = emptyList(),
    /** 点榜单卡：打开那个榜的完整列表（与搜索页那排榜单卡同一个动作） */
    onOpenChart: (String, String) -> Unit = { _, _ -> },
    /**
     * R13：「音乐榜单」表头那颗「更多」的去处——排行榜弹层（VM.openNeteaseToplist 本来就免登录）。
     *
     * 之前两个表头共用 onOpen，而 onOpen 指向的 VM 方法第一句就是登录拦截，
     * 于是首页点「更多」= 跳扫码登录页。榜单完整列表是公开只读数据，跟账号无关，
     * 拆成独立回调（默认空实现），不破坏任何现有调用。
     */
    onOpenToplistMore: () -> Unit = {}
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black

    // 6.0：先把"榜"从推荐池里挑出去。网易云 /personalized 经常把飙升榜、热歌榜、
    // 新歌榜、原创榜这些官方榜混进推荐歌单，首页就变成"上两排是歌单、下两排是榜单"
    // ——正是用户反馈的那个问题。榜单在搜索页底部和「我的 → 排行榜」都有完整一份，
    // 首页这一屏只留歌单，不再重复。
    val pool = remember(recPlaylists, chartIds) {
        recPlaylists.filterNot { looksLikeChart(it.name) || it.id in chartIds }
    }
    // 一批 6 张 = 3 列 × **2 行**（R12：首页只要两排推荐，下面两排留给榜单）。
    // 换一批只在已缓存的池子里挪窗口，顺便要下一批新数据
    // （VM 内部有 recsLoadingMore / recommendAllLoaded 双重去重）。
    val batchSize = 6
    var batch by remember { mutableIntStateOf(0) }
    val poolSize = pool.size
    LaunchedEffect(poolSize) { if (poolSize <= batch * batchSize) batch = 0 }
    // 挑掉榜单以后池子可能不够铺满两排（6 张），这时候**主动再要一批**，
    // 而不是让用户看到"只有一排"。loadingMore / allLoaded 双保险，不会连环发请求。
    LaunchedEffect(poolSize, loadingMore, allLoaded) {
        if (poolSize in 1 until batchSize && !loadingMore && !allLoaded) onLoadMore()
    }
    val visible = remember(pool, batch, poolSize) {
        if (poolSize == 0) emptyList()
        else {
            val start = (batch * batchSize) % poolSize
            (0 until minOf(batchSize, poolSize)).map { pool[(start + it) % poolSize] }
        }
    }

    // 推荐池子还空着就不干等：1.2s 后补拉一次，之后每 20s 再试，数据一到 key 变化自动停。
    // （首次进入 VM 自己会重试 6 次，这里只是给用户停在首页时的兜底。）
    LaunchedEffect(poolSize) {
        if (poolSize > 0) return@LaunchedEffect
        kotlinx.coroutines.delay(1_200)
        onReload()
        while (true) {
            kotlinx.coroutines.delay(20_000)
            onReload()
        }
    }

    Column(Modifier.padding(vertical = 8.dp)) {

        // ===== 我的歌单（登录账号自建/收藏的歌单）=====
        if (loggedIn && myPlaylists.isNotEmpty()) {
            NetSectionTitle("我的歌单", colors, topPadding = 14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                myPlaylists.take(12).forEach { pl ->
                    NeteaseCoverCard(pl = pl, colors = colors, onClick = { Haptics.tap(); onSelectPlaylist(pl.id, pl.name) })
                }
                ViewAllChip(colors = colors, onClick = { Haptics.tap(); onOpen() })
            }
        }
        // ===== 我喜欢的音乐（6.9.6 按用户要求重排：我的歌单下面紧跟我喜欢）=====
        if (loggedIn) {
            NetSectionTitle("我喜欢的音乐", colors, topPadding = 14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NeteaseLikedCard(colors = colors, onClick = { Haptics.click(); onLiked() })
            }
        }


        // ===== 推荐歌单：免登录，3 列网格 =====
        Row(
            // 抬头登记成聚光灯锚点：新功能导览第一步要照着"推荐歌单 + 换一批 + 更多"讲
            Modifier.fillMaxWidth().padding(bottom = 10.dp)
                .spotlightAnchor(SpotlightKeys.HOME_FEED),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "推荐歌单", color = onSheet, fontSize = 15.sp, fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            // 来源不再点名单一平台（用户："不能后面提示是网易云的"）。
            // 再给这一行一个宽度上限 + 单行省略：小屏配系统超大字体时，表头是
            // "标题+副标题+换一批+更多"四个固定宽度并排，副标题不设上限就会把后面两颗按钮
            // 挤到 0 宽——「更多」直接看不见。宁可副标题省略，也不能让按钮消失。
            Text(
                "聚合多平台 · 免登录", color = colors.subText, fontSize = 10.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp)
            )
            Spacer(Modifier.weight(1f))
            // 换一批：池子够大就挪窗口，不够大就是直接再要一批
            if (poolSize > 0) {
                Text(
                    if (loadingMore) "载入中…" else "换一批",
                    color = if (loadingMore) colors.subText else colors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .junPressable(enabled = !loadingMore, scaleDown = 0.90f) {
                            if (loadingMore) return@junPressable
                            batch += 1
                            onLoadMore()
                        }
                        .clip(JunShape.chip)
                        .background(colors.card.copy(alpha = 0.7f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                "更多",
                color = colors.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .junPressable(scaleDown = 0.90f) { Haptics.tap(); onOpen() }
                    .clip(JunShape.chip)
                    .background(colors.card.copy(alpha = 0.7f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        if (poolSize == 0) {
            // 占位：正在拉就转圈，失败才给重试入口，不再写「点击加载」。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .junPressable(enabled = !loading) { onReload() }
                    .junSurface(GlassLevel.CARD, colors, JunShape.item),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = colors.primary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.height(7.dp))
                    }
                    Text(
                        if (loading) "正在为你准备歌单…" else "歌单还没出来，点此重试",
                        color = colors.subText, fontSize = 12.sp
                    )
                }
            }
        } else {
            visible.chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { pl ->
                        RecGridCard(
                            pl = pl,
                            colors = colors,
                            modifier = Modifier.weight(1f),
                            onClick = { Haptics.tap(); onSelectPlaylist(pl.id, pl.name) }
                        )
                    }
                    // 不足 3 张时补空位，最后一张不会被拉宽（列宽恒定）
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            // 划到底自动续拉的可见反馈：还有更多就留一行提示
            if (!allLoaded) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (loadingMore) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = colors.primary,
                            strokeWidth = 1.6.dp
                        )
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        if (loadingMore) "正在加载更多歌单" else "往下滑还能再要一批",
                        color = colors.subText, fontSize = 11.sp
                    )
                }
            }
        }

        // ===== 每日新歌 · 每日推荐（6.9.6 重排：推荐歌单下面）=====
        if (loggedIn) {
            NetSectionTitle("每日新歌", colors, topPadding = 14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DailyRecCard(colors = colors, onClick = { Haptics.click(); onDaily() })
            }
        }

        // ===== 音乐榜单：3 列 × 2 行，紧跟在推荐歌单那两排下面（R12 用户明确要的排法）=====
        if (charts.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "音乐榜单", color = onSheet, fontSize = 15.sp, fontWeight = FontWeight.Black,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                // 同上：来源不点名单一平台，并给副标题宽度上限，别把「更多」挤没
                Text(
                    "聚合多平台 · 官方榜", color = colors.subText, fontSize = 10.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 100.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "更多",
                    color = colors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .junPressable(scaleDown = 0.90f) { Haptics.tap(); onOpenToplistMore() }
                        .clip(JunShape.chip)
                        .background(colors.card.copy(alpha = 0.7f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            charts.take(6).chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { ch ->
                        // 复用推荐那张网格卡的形状（正方封面 + 两行标题），
                        // 差别只在点下去开的是「这个榜的完整列表」。
                        RecGridCard(
                            pl = ch,
                            colors = colors,
                            modifier = Modifier.weight(1f),
                            onClick = { Haptics.tap(); onOpenChart(ch.id, ch.name) }
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }


        // 登录了但我的歌单还没下来：给一个进入歌单选单弹层的入口（未登录不需要这行）
        if (loggedIn && myPlaylists.isEmpty() && poolSize == 0) {
            NetSectionTitle("我的歌单", colors, topPadding = 14.dp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Box(
                    Modifier
                        .height(110.dp)
                        .junPressable { onOpen() }
                        .junSurface(GlassLevel.CARD, colors, JunShape.item)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("点击加载歌单", color = colors.subText, fontSize = 12.sp)
                }
            }
        }
    }
}

/** 首页推荐歌单网格卡：正方封面 + 右上角播放数 + 下方两行标题（网易云首页样式） */
@Composable
private fun RecGridCard(
    pl: com.jun.musiccloud.player.NeteasePlaylistItem,
    colors: JunColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    // 按压的 graphicsLayer 必须挂在带背景的那层**之外**（这里在 Column 上），
    // 封面 Box 的 junSurface 作为子节点被一起缩放，才不会按下去只剩边框在动。
    Column(
        modifier
            .junPressable { onClick() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .junSurface(GlassLevel.CARD, colors, JunShape.item)
        ) {
            SongArtwork(
                filePath = pl.cover,
                seedId = pl.id.hashCode().toLong(),
                modifier = Modifier.fillMaxSize(),
                isDark = colors.isDark
            )
            // 上沿高光：玻璃反光
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.16f), Color.Transparent)
                        )
                    )
            )
            if (pl.playCount > 0) {
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.38f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "▶",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 7.sp
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(formatPlayCount(pl.playCount), color = Color.White.copy(alpha = 0.9f), fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            pl.name,
            color = onSheet,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp
        )
    }
}

/** 横向卡片组末尾的「查看全部」胶囊 */
@Composable
private fun ViewAllChip(colors: JunColors, onClick: () -> Unit) {
    Box(
        Modifier
            .width(80.dp)
            .height(110.dp)
            .junPressable(feedback = PressFeedback.NONE) { onClick() }
            .clip(JunShape.chip)
            .background(colors.card.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("查看全部", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** 分区小节标题：小字号加粗，左侧对齐，作为横向卡片组的前导 */
@Composable
private fun NetSectionTitle(
    text: String,
    colors: JunColors,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    Text(
        text,
        color = onSheet,
        fontSize = 15.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier.padding(top = topPadding, bottom = 10.dp)
    )
}

/** 「每日推荐」入口卡：渐变背景 + 日历图标 */
@Composable
private fun DailyRecCard(
    colors: JunColors,
    onClick: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    Column(
        Modifier
            .width(110.dp)
            .junPressable(feedback = PressFeedback.NONE) { onClick() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(JunShape.chip)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(colors.primary, colors.secondary)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(36.dp)) {
                    val c = Color.White
                    val strokeW = size.minDimension * 0.12f
                    // 日历外框
                    drawRoundRect(
                        color = c,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.18f)
                    )
                    // 日历顶部装订条
                    drawLine(
                        color = c,
                        start = Offset(size.width * 0.18f, size.height * 0.34f),
                        end = Offset(size.width * 0.82f, size.height * 0.34f),
                        strokeWidth = strokeW
                    )
                    // 两个挂耳
                    drawCircle(color = c, radius = size.minDimension * 0.06f, center = Offset(size.width * 0.3f, size.height * 0.2f))
                    drawCircle(color = c, radius = size.minDimension * 0.06f, center = Offset(size.width * 0.7f, size.height * 0.2f))
                }
                Spacer(Modifier.height(2.dp))
                Text("每日推荐", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "每日推荐",
            color = onSheet,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 「我喜欢的音乐」入口卡：红粉渐变背景 + 爱心图标（对应网易云账号红心） */
@Composable
private fun NeteaseLikedCard(
    colors: JunColors,
    onClick: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    Column(
        Modifier
            .width(110.dp)
            .junPressable(feedback = PressFeedback.NONE) { onClick() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(JunShape.chip)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0xFFFF4E6A), Color(0xFFB3123A))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(JunIcons.Favorite, null, Modifier.size(36.dp), Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "我喜欢的音乐",
            color = onSheet,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 网易云封面卡片：方封面 + 标题，用于榜单/歌单横向滚动 */
@Composable
private fun NeteaseCoverCard(
    pl: com.jun.musiccloud.player.NeteasePlaylistItem,
    colors: JunColors,
    onClick: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    Column(
        Modifier
            .width(110.dp)
            .junPressable { onClick() }
    ) {
        // 封面卡：液态玻璃融合 —— 封面铺满卡片，底部叠渐变蒙层让标题可读，
        // 外层 junSurface 提供玻璃折射与圆角边框，整体与 App 玻璃风格统一。
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .junSurface(GlassLevel.CARD, colors, JunShape.item)
        ) {
            SongArtwork(
                filePath = pl.cover,
                seedId = pl.id.hashCode().toLong(),
                modifier = Modifier.fillMaxSize(),
                isDark = colors.isDark
            )
            // 顶部高光：模拟玻璃上沿的反光，强化"玻璃"质感
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.18f),
                                Color.Transparent
                            )
                        )
                    )
            )
            // 底部玻璃渐变蒙层 + 歌单名：让标题始终可读，同时让封面自然融入卡片
            Column(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Transparent,
                                colors.card.copy(alpha = 0.75f)
                            )
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    pl.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 13.sp
                )
            }
            // 播放数角标（如果有）
            if (pl.playCount > 0) {
                Text(
                    formatPlayCount(pl.playCount),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private fun formatPlayCount(count: Long): String {
    return when {
        count >= 100000000 -> "${count / 100000000}亿"
        count >= 10000 -> "${count / 10000}万"
        else -> count.toString()
    }
}
