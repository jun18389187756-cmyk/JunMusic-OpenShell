package com.jun.musiccloud.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.jun.musiccloud.data.LyricLine
import com.jun.musiccloud.data.LyricParser
import com.jun.musiccloud.data.RepeatMode
import com.jun.musiccloud.ui.theme.GlassContent
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.isLiquidGlass
import com.jun.musiccloud.ui.theme.LocalJunUiFamily
import com.jun.musiccloud.ui.theme.readableOn
import com.jun.musiccloud.ui.theme.readableTextOn
import com.jun.musiccloud.player.PlayerViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 播放页的「档位 / 倍速」动作集合。
 *
 * 这两个动词要落到 PlayerCard → PlayerControls 以及 SongDeck 的
 * DeckStack/DeckCard/LandscapeDeckPlayer 好几层嵌套里，而每层都已经把 `state`
 * 一路传到底。为四个 lambda 逐个加参数会把签名撑成一堵墙，所以走项目里既有的
 * CompositionLocal 路子（同 LocalSpotlightRegistry / LocalSettingsJump）：
 * MainScreen 一次性 provide，深处的徽章直接取用。
 */
internal class PlayerTuning(
    /** 单击音质徽章：自动 → 标准 → 极高 → 无损 → Hi-Res → 自动 */
    val onCycleQuality: () -> Unit = {},
    /** 倍速档位（万分比） */
    val onSetSpeedBp: (Int) -> Unit = {},
    /** 音质解析结果提示条被关闭 */
    val onDismissFlash: () -> Unit = {},
    /** 在线缓存写满通知：清空 */
    val onClearCache: () -> Unit = {},
    /** 在线缓存写满通知：知道了 */
    val onDismissCacheNotice: () -> Unit = {}
)

internal val LocalPlayerTuning = staticCompositionLocalOf { PlayerTuning() }

/** 倍速可选档位（万分比）。与 PlayerViewModel 的 75..200 上限一致。 */
internal val SPEED_STEPS = listOf(75, 100, 125, 150, 175, 200)

/** 75 → "0.75×"，100 → "1×"，150 → "1.5×"，200 → "2×" */
internal fun speedChipLabel(bp: Int): String {
    val s = if (bp % 10 == 0) "%.1f".format(bp / 100f) else "%.2f".format(bp / 100f)
    return s.trimEnd('0').trimEnd('.') + "×"
}

/** 中央播放器卡片：白色圆角卡片 + 封面 + 标题/作者 + 进度条 + 时间 + 三枚圆角矢量按钮 + 副控制 */
@Composable
fun PlayerCard(
    title: String,
    artist: String,
    onArtistClick: (() -> Unit)? = null,
    positionMs: Long,
    durationMs: Long,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    artworkPath: String?,
    /** 歌词加载键（在线歌曲为 "ol:<songId>"），默认沿用封面路径以兼容本地场景 */
    lyricKey: String? = artworkPath,
    progress: Float,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleLike: () -> Unit = {},
    liked: Boolean = false,
    onClickArtwork: () -> Unit = {},
    colors: JunColors,
    modifier: Modifier = Modifier,
    // 睡眠定时器（弹层在根层级，这里只负责按钮与倒计时环）
    sleepTimerMs: Long = 0L,
    sleepTimerEndMs: Long = 0L,
    onOpenSleepTimer: () -> Unit = {},
    // 均衡器
    /** 均衡器当前是否有生效的增益（曲线非全平）。均衡器本身常开，没有开关概念 */
    eqActive: Boolean = false,
    onOpenEqualizer: () -> Unit = {},
    /** 打开播放列表面板（卡片右下角的列表按钮） */
    onOpenQueue: () -> Unit = {},
    /** 下载当前歌曲 */
    onDownload: () -> Unit = {},
    /** 总后台「下载」开关：关闭时隐藏下载按钮 */
    showDownload: Boolean = true,
    /** 相似歌曲：基于当前歌曲推荐并加入播放队列 */
    onSimiSongs: () -> Unit = {},
    /** 打开当前歌曲的评论弹层（音效按钮旁，右上角显示评论数角标）。null 时不显示该按钮 */
    onOpenComments: (() -> Unit)? = null,
    /** 当前歌曲的评论总数（0 时不显示角标） */
    commentCount: Int = 0,
    /** 音质按钮文案：选中的档位名（标准音质 / 无损音质 / Hi-Res 音质…），R4 起不再显示容器名 */
    audioFormat: String = "",
    /** 可视化频谱开关状态；长按徽章时用它 */
    visualizerEnabled: Boolean = false,
    onToggleVisualizer: () -> Unit = {},
    /**
     * 5.1：切档那一刻的一次性提示（"想要 无损 · 实际 320k MP3"）。
     * 空串时不显示，动画只在非空→空的边界上播一次。
     */
    qualityFlash: String = "",
    /** 5.1：倍速，万分比（100=1.0x）。传 -1 表示这张卡不显示倍速行（非活动卡） */
    playbackSpeedBp: Int = -1,
    /** 实验性：可视化随手机倾斜变化 */
    tiltSpectrum: Boolean = false,
    /** 实验性：摇动手机封面跟着一晃一晃（duangduang） */
    coverShakeEnabled: Boolean = false,
    /** 封面左下角单行歌词开关（锁在封面上，换词时模糊渐隐渐出） */
    coverLyricEnabled: Boolean = false,
    /**
     * 是否是"活的"卡片。
     *
     * 卡片模式（[JunLayout.isDeck]）下屏幕上同时有好几张卡，只有正在播的那张该响应
     * 拖动类手势——进度条的水平拖动会把翻页 / 抽卡手势整段吃掉，旁边的卡一拖就变成
     * 给当前歌曲 seek。非活动卡只保留点击（点一下跳到那首）。
     */
    interactive: Boolean = true,
    // 6.9.0：加载反馈 —— 非空时卡片顶部显示"正在取源 / 切换备用 / 缓冲中"细条与文字
    loadingText: String = "",
    /** 真实缓冲进度（0~1），-1 = 还没拿到地址，走不定进度动画 */
    loadingBuffered: Float = -1f,
    loadCanRetry: Boolean = false,
    onLoadHelp: () -> Unit = {},
    onRetryLoad: () -> Unit = {}
) {
    // 深色模式：深色卡片 + 白字 + 白图标；浅色模式：白色卡片 + 黑字 + 黑图标
    // 粉主题下卡片底与进度条轨道整体染粉（见 JunColors.playerCard / playerTrack）
    val cardColor = colors.playerCard
    val glass = isLiquidGlass
    val btnColor = colors.primary.readableOn(cardColor, 3.2f)
    val trackColor = colors.playerTrack
    // 卡片上的图标/主文字颜色：深色=白，浅色=黑
    val onCard = if (colors.isDark) Color.White else Color.Black
    // 主题色在卡片背景上的可读版本（霓虹黄/青落在白卡上会看不清）
    val readableAccent = btnColor
    // 次要文字：0.55 太淡，提到 0.72 保证小字也看得清
    val subOnCard = onCard.copy(alpha = 0.72f)

    // 实验性：封面摇动。仅在活动卡 + 开关开启时注册加速度计。
    val shake = rememberShakeState(coverShakeEnabled && interactive)
    // 封面左下角单行歌词：仅在开启 + 活动卡解析。解析放 IO，切歌时重载。
    var coverLyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    LaunchedEffect(lyricKey, coverLyricEnabled) {
        if (!coverLyricEnabled || !interactive) { coverLyrics = emptyList(); return@LaunchedEffect }
        coverLyrics = if (lyricKey == null) emptyList()
        else withContext(Dispatchers.IO) { runCatching { LyricParser.loadLyrics(lyricKey, durationMs) }.getOrDefault(emptyList()) }
    }

    // 可视化模式的过渡进度。
    // 关键：新卡片实例直接从当前开关状态起步（Animatable 构造值）——
    // 堆叠/横向排布下切歌后顶上来的是全新的 composable 实例，
    // animateFloatAsState 的初始值恒为 0，会把 420ms 的展开动画整个重播，
    // 看着就像"切歌把频谱关了又开"。列表模式是同一张卡原地换内容，没这问题。
    // Animatable + animateTo：只有 visualizerEnabled 真正变化时才播动画。
    val vizT = remember { androidx.compose.animation.core.Animatable(if (visualizerEnabled) 1f else 0f) }
    LaunchedEffect(visualizerEnabled) {
        vizT.animateTo(
            targetValue = if (visualizerEnabled) 1f else 0f,
            animationSpec = tween(420, easing = androidx.compose.animation.core.EaseInOut)
        )
    }

    val isLandscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = modifier
            // 横屏窄化：内容不再拉满整个横屏宽度，统一封顶后由外层 Column 居中。
            // 竖屏保持 fillMaxWidth 原样（竖屏宽度本就小于封顶值，但 tablet 竖屏
            // 可能超过，所以只在横屏生效）。
            .then(
                if (isLandscape) Modifier.widthIn(max = LANDSCAPE_PLAYER_MAX_WIDTH)
                else Modifier.fillMaxWidth()
            )
            .junSurface(GlassLevel.PLAYER, colors, JunShape.card, solid = cardColor)
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        // 6.9.0 的加载反馈条原来就画在这里 —— 但它是 Box 的**第一个**子节点。
        // 封面是 fillMaxWidth 的正方形，从同一条基线往下铺，绘制顺序在后，
        // 于是整条反馈被封面压在后面：取源/缓冲那几秒屏幕上什么都没有。
        // 6.9.4 把它挪到 Box 的末尾（见本函数结尾），并换成不透明底的提示条。
        if (isLandscape) {
            // 横屏：左封面 + 右控制，垂直居中，控制区高度克制不铺满
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                PlayerCoverArt(
                    artworkPath = artworkPath,
                    positionMs = positionMs,
                    isDark = colors.isDark,
                    vizAlpha = vizT.value,
                    // 横屏禁用摇动封面物理：横屏时加速度计轴向变了，
                    // 摇晃检测会误触发。整个物理效果横屏一律关掉。
                    shake = rememberShakeState(false),
                    coverLyricEnabled = coverLyricEnabled,
                    interactive = interactive,
                    coverLyrics = coverLyrics,
                    onClickArtwork = onClickArtwork,
                    modifier = Modifier
                        // 横屏按屏高定尺寸，正方形自然收窄宽度，不撑爆
                        .fillMaxHeight(0.72f)
                        .aspectRatio(1f)
                )
                PlayerControls(
                    title = title,
                    artist = artist,
                    onArtistClick = onArtistClick,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    shuffle = shuffle,
                    repeatMode = repeatMode,
                    progress = progress,
                    isPlaying = isPlaying,
                    onToggle = onToggle,
                    onPrev = onPrev,
                    onNext = onNext,
                    onSeek = onSeek,
                    onCyclePlayMode = onCyclePlayMode,
                    onToggleLike = onToggleLike,
                    liked = liked,
                    sleepTimerMs = sleepTimerMs,
                    sleepTimerEndMs = sleepTimerEndMs,
                    onOpenSleepTimer = onOpenSleepTimer,
                    eqActive = eqActive,
                    onOpenEqualizer = onOpenEqualizer,
                    onOpenQueue = onOpenQueue,
                    onDownload = onDownload,
                    showDownload = showDownload,
                    onSimiSongs = onSimiSongs,
                    onOpenComments = onOpenComments,
                    commentCount = commentCount,
                    audioFormat = audioFormat,
                    visualizerEnabled = visualizerEnabled,
                    onToggleVisualizer = onToggleVisualizer,
                    qualityFlash = qualityFlash,
                    playbackSpeedBp = playbackSpeedBp,

                    tiltSpectrum = tiltSpectrum,
                    vizAlpha = vizT.value,
                    onCard = onCard,
                    subOnCard = subOnCard,
                    readableAccent = readableAccent,
                    btnColor = btnColor,
                    trackColor = trackColor,
                    glass = glass,
                    isDark = colors.isDark,
                    interactive = interactive,
                    compact = true,
                    // 6.9.5：加载提示条挪进控制区（音质按钮上方那片空白），见 PlayerControls
                    loadText = if (interactive) loadingText else "",
                    loadBuffered = loadingBuffered,
                    loadCanRetry = loadCanRetry,
                    onLoadHelp = onLoadHelp,
                    onRetryLoad = onRetryLoad,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PlayerCoverArt(
                    artworkPath = artworkPath,
                    positionMs = positionMs,
                    isDark = colors.isDark,
                    vizAlpha = vizT.value,
                    shake = shake,
                    coverLyricEnabled = coverLyricEnabled,
                    interactive = interactive,
                    coverLyrics = coverLyrics,
                    onClickArtwork = onClickArtwork,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 竖屏/矮屏封面高度上限：屏高 46%，竖屏时该值远大于卡宽、不生效
                        .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.46f)
                        .aspectRatio(1f)
                )

                Spacer(Modifier.height(20.dp))

                PlayerControls(
                    title = title,
                    artist = artist,
                    onArtistClick = onArtistClick,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    shuffle = shuffle,
                    repeatMode = repeatMode,
                    progress = progress,
                    isPlaying = isPlaying,
                    onToggle = onToggle,
                    onPrev = onPrev,
                    onNext = onNext,
                    onSeek = onSeek,
                    onCyclePlayMode = onCyclePlayMode,
                    onToggleLike = onToggleLike,
                    liked = liked,
                    sleepTimerMs = sleepTimerMs,
                    sleepTimerEndMs = sleepTimerEndMs,
                    onOpenSleepTimer = onOpenSleepTimer,
                    eqActive = eqActive,
                    onOpenEqualizer = onOpenEqualizer,
                    onOpenQueue = onOpenQueue,
                    onDownload = onDownload,
                    showDownload = showDownload,
                    onSimiSongs = onSimiSongs,
                    onOpenComments = onOpenComments,
                    commentCount = commentCount,
                    audioFormat = audioFormat,
                    visualizerEnabled = visualizerEnabled,
                    onToggleVisualizer = onToggleVisualizer,
                    tiltSpectrum = tiltSpectrum,
                    qualityFlash = qualityFlash,
                    playbackSpeedBp = playbackSpeedBp,

                    vizAlpha = vizT.value,
                    onCard = onCard,
                    subOnCard = subOnCard,
                    readableAccent = readableAccent,
                    btnColor = btnColor,
                    trackColor = trackColor,
                    glass = glass,
                    isDark = colors.isDark,
                    interactive = interactive,
                    compact = false,
                    // 6.9.5：加载提示条挪进控制区（音质按钮上方那片空白），见 PlayerControls
                    loadText = if (interactive) loadingText else "",
                    loadBuffered = loadingBuffered,
                    loadCanRetry = loadCanRetry,
                    onLoadHelp = onLoadHelp,
                    onRetryLoad = onRetryLoad,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 6.9.4 新增 / 6.9.5 放大重排：加载提示条本体（播放页 / 横向排布 / 堆叠排布共用）。
 *
 * 为什么单独抽出来：三种排布走的是两个不同的播放器组件
 * （竖屏与列表走 [PlayerCard]，横屏卡片走 [LandscapeDeckPlayer]），
 * 以前只有前者接了加载反馈，横屏卡片模式连参数都没有 —— 横屏用户取源时
 * 屏幕上一片死寂。现在两边引用同一个组件，外形和文案保证一致。
 *
 * 6.9.5 按用户要求调整：
 *   · 位置从卡片顶沿挪到「音质按钮上方那片空白」居中（调用方在 PlayerControls 里放）；
 *   · 条体放大：进度线 3dp → 5dp，内边距和字号都加一点，缩略图也看得见；
 *   · 实底胶囊保留（细线压在封面上等于隐形，这个教训来自 6.9.4）。
 */
@Composable
internal fun PlayerLoadBanner(
    text: String,
    buffered: Float,
    canRetry: Boolean,
    onHelp: () -> Unit,
    onRetry: () -> Unit,
    isDark: Boolean,
    onCard: Color,
    accent: Color,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 6.9.3：加载指示高端化（用户：加载动画要好看、要明确告诉他"正在加载"，
    // 整体感觉舒服）。图案借鉴 Android 官方 Material 进度条的不定态实现
    // （androidx.compose.material.ProgressIndicator，Apache-2.0，维护最活跃的
    // 成熟方案）：两段短线按 Material 规范的缓动曲线先后扫过整条轨道，
    // 一收一放，比旧版单条流光带更有"系统级加载"的质感。
    // 线是纯 accent 色，不参与玻璃/毛玻璃/实色任何材质，三态观感一致。
    //  - 还没拿到地址：双段扫动（Material 常量：总周期 1800ms，四段缓动）；
    //  - 拿到地址进入缓冲：显示真实缓冲百分比（渐变填充）；
    //  - 状态文字**常驻**在线下（"正在取播放地址…"这类），轻微呼吸，
    //    卡住 5 秒（canRetry）才多出「重试」和「?」两个操作。
    val barH = 3.dp
    val barShape = RoundedCornerShape(barH / 2)

    // —— Material 不定态进度条的规范常量（官方实现照搬）——
    val DURATION = 1800
    val it_ = rememberInfiniteTransition(label = "loadBarTop")
    val head1 by it_.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = DURATION
                0f at 0 with CubicBezierEasing(0.375f, 0f, 0.935f, 1f)
                1f at 667
            }
        ),
        label = "loadHead1"
    )
    val tail1 by it_.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = DURATION
                0f at 333 with CubicBezierEasing(0.100f, 0.295f, 0.520f, 1f)
                1f at 1666
            }
        ),
        label = "loadTail1"
    )
    val head2 by it_.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = DURATION
                0f at 1000 with CubicBezierEasing(0.500f, 0f, 0.750f, 0.435f)
                1f at 1567
            }
        ),
        label = "loadHead2"
    )
    val tail2 by it_.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = DURATION
                0f at 1267 with CubicBezierEasing(0.050f, 0.520f, 0.935f, 1f)
                1f at 1800
            }
        ),
        label = "loadTail2"
    )
    // 状态文字呼吸（1.6s 往复）：正常加载时文字轻轻明灭，"活着"的感觉
    val pulse by it_.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "loadPulse"
    )
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(barH)
                .clip(barShape)
                .background(onCard.copy(alpha = 0.14f))
        ) {
            if (buffered >= 0f) {
                // 确定态：真实缓冲百分比，accent → 提亮 accent 的横向渐变
                val accentLit = androidx.compose.ui.graphics.lerp(accent, Color.White, 0.28f)
                Box(
                    Modifier
                        .fillMaxWidth(buffered.coerceIn(0.04f, 1f))
                        .height(barH)
                        .clip(barShape)
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                0f to accent, 1f to accentLit
                            )
                        )
                )
            } else {
                // 不定态：Material 双段扫动 + 端头亮点
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val corner = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f)
                    fun seg(tail: Float, head: Float) {
                        val len = (head - tail).coerceAtLeast(0f) * w
                        if (len <= 0f) return
                        drawRoundRect(
                            color = accent,
                            topLeft = Offset(tail * w, 0f),
                            size = Size(len, h),
                            cornerRadius = corner
                        )
                    }
                    seg(tail1, head1)
                    seg(tail2, head2)
                    // 第一段端头一颗白色亮点：视线跟着它走，明确"正在推进"
                    if (head1 > tail1) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.85f),
                            radius = h / 2f * 0.9f,
                            // 6.6：容器宽度小于圆头直径时 w - h/2f 会小于 h/2f，
                            // coerceIn 拿到空区间直接抛 IllegalArgumentException
                            // （后台 5 条 "maximum -4.0 is less than minimum 4.0" 就是它）。
                            center = Offset(
                                (head1 * w).coerceIn(
                                    h / 2f,
                                    (w - h / 2f).coerceAtLeast(h / 2f)
                                ),
                                h / 2f
                            )
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text,
                // 常驻状态：正常加载轻轻明灭（0.5~0.8），卡住后变实（0.9）提醒处理
                color = onCard.copy(alpha = if (canRetry) 0.9f else 0.5f + 0.3f * pulse),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (canRetry) {
                Text(
                    "重试",
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .clickable(onClick = onRetry)
                        .padding(end = 2.dp)
                )
                Text(
                    "?",
                    color = onCard.copy(alpha = 0.70f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(CircleShape)
                        .background(onCard.copy(alpha = 0.12f))
                        .clickable(onClick = onHelp)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
    }
}

/**
 * 6.9.4：「为什么有的歌要等？」说明弹窗。
 *
 * 这段文案原来在 MainScreen（竖屏播放页）和 SongDeck（卡片/堆叠）各抄了一份，
 * 横屏卡片这次也要接"?"，再抄第三份就该失控了 —— 收成一处。
 */
@Composable
internal fun LoadHelpDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("知道了")
            }
        },
        title = { androidx.compose.material3.Text("为什么有的歌要等？") },
        text = {
            androidx.compose.material3.Text(
                "点一首歌时，App 按四层找音频，前一层有就直接用：\n\n" +
                    "① 手机本地 —— 下载过的文件，0.1 秒出声\n" +
                    "② 云端曲库 —— 听过并已备份的歌，秒开\n" +
                    "③ QQ 音乐直连 —— 约 0.4~1 秒\n" +
                    "④ 音源脚本 —— 冷门歌/VIP 歌的路，最慢\n\n" +
                    "四条路同时出发，谁先到用谁，2 秒没回音自动换下一条，5 秒还没好可以点重试。\n\n" +
                    "所以：听过的歌越多，秒开的歌就越多——App 会在 Wi-Fi 下自动把听完整首的歌备份进曲库。" +
                    "等得久通常是在走第④层借别人的接口，和手机性能无关。"
            )
        }
    )
}

/**
 * 封面块（点击开歌词、频谱压暗层、封面歌词），从 PlayerCard 抽出以便横屏时
 * 单独承担翻页/堆叠而控制区固定。尺寸由外部 modifier 决定（竖屏按宽、横屏按高）。
 */
@Composable
internal fun PlayerCoverArt(
    artworkPath: String?,
    positionMs: Long,
    isDark: Boolean,
    vizAlpha: Float,
    shake: CoverShake,
    coverLyricEnabled: Boolean,
    interactive: Boolean,
    coverLyrics: List<LyricLine>,
    onClickArtwork: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .coverShake(shake)
            // 整张封面走行级按压（幅度小，避免大封面一下缩得太狠）；
            // 缩放层在 clip/background 之前，封面底色跟内容一起软下去。
            .junPressableRow { onClickArtwork() }
            .clip(JunShape.item)
            .background(Color.hsv(
                hue = (artworkPath?.hashCode()?.mod(360) ?: 0).toFloat(),
                saturation = 0.35f,
                value = if (isDark) 0.18f else 0.92f
            ))
    ) {
        AlbumArt(filePath = artworkPath)
        // 压暗层：固定盖满整卡，让频谱清晰可辨
        if (vizAlpha > 0.01f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.3f * vizAlpha))
            )
        }
        // 封面左下角单行歌词：锁在封面上（随 shake 一起动），换词时模糊渐隐渐出。
        if (coverLyricEnabled && interactive) {
            val curIdx = LyricParser.currentIndex(coverLyrics, positionMs)
            val curText = if (curIdx in coverLyrics.indices) coverLyrics[curIdx].text else ""
            CoverLyricLine(
                text = curText,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 14.dp)
            )
        }
    }
}

/**
 * 控制块（信息/频谱、进度、时间、主控制、副控制），从 PlayerCard 抽出。
 * compact=true 为横屏紧凑排布：间距收窄、按钮略小、居中不铺满。
 */
@Composable
private fun PlayerControls(
    title: String,
    artist: String,
    onArtistClick: (() -> Unit)? = null,
    positionMs: Long,
    durationMs: Long,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    progress: Float,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleLike: () -> Unit,
    liked: Boolean,
    sleepTimerMs: Long,
    sleepTimerEndMs: Long,
    sleepStopAfterSong: Boolean = false,
    onOpenSleepTimer: () -> Unit,
    eqActive: Boolean,
    onOpenEqualizer: () -> Unit,
    onOpenQueue: () -> Unit,
    onDownload: () -> Unit,
    showDownload: Boolean = true,
    onSimiSongs: () -> Unit = {},
    onOpenComments: (() -> Unit)? = null,
    commentCount: Int = 0,
    audioFormat: String,
    visualizerEnabled: Boolean,
    onToggleVisualizer: () -> Unit,
    /** 5.1：切档瞬间的一次性提示，空则不显示 */
    qualityFlash: String = "",
    /** 5.1：倍速万分比；-1 表示不显示倍速行 */
    playbackSpeedBp: Int = -1,
    tiltSpectrum: Boolean,
    vizAlpha: Float,
    onCard: Color,
    subOnCard: Color,
    readableAccent: Color,
    btnColor: Color,
    trackColor: Color,
    glass: Boolean,
    isDark: Boolean,
    interactive: Boolean,
    compact: Boolean,
    // 6.9.5：加载提示条。位置按用户要求从"封面顶部"挪到「音质按钮上方那片空白」居中，
    // 条体也加粗加大，并且播放开始后还会停留一段时间（文案见 PlayerViewModel 的
    // "已开始播放"），不再是闪一下就没。
    loadText: String = "",
    loadBuffered: Float = -1f,
    loadCanRetry: Boolean = false,
    onLoadHelp: () -> Unit = {},
    onRetryLoad: () -> Unit = {},
    infoFade: Float = 1f,
    // 大播放器控件可见性：隐藏后仍生效，其余控件居中重排（来自 PlayerUiState 的 hideXxx）
    hideSpeed: Boolean = false,    // 倍速
    hideComment: Boolean = false,  // 评论
    hidePitch: Boolean = false,    // 音调
    hideQuality: Boolean = false,  // 音质
    hideDownload: Boolean = false, // 下载
    hideLike: Boolean = false,     // 喜欢
    hideSimilar: Boolean = false,  // 相似推荐
    hideRecent: Boolean = false,   // 最近播放
    hideRepeat: Boolean = false,   // 循环播放
    hideSleep: Boolean = false,    // 睡眠定时器
    modifier: Modifier = Modifier
) {
    // 切档 / 倍速的动作由 MainScreen 一次性 provide（见 PlayerTuning 注释）
    val tuning = LocalPlayerTuning.current
    // 倍速面板开合（按钮点了才弹，不常驻占位）
    var speedOpen by remember { mutableStateOf(false) }
    // 横屏紧凑：信息区更矮、间距更小、主控制按钮略缩。
    // 信息区要能完整放下标题(17sp≈23dp) + 间距4 + 艺术家(13sp≈18dp)，
    // 给到 48dp 才不会被下面的进度条压住第二行。
    val infoHeight = if (compact) 52.dp else 60.dp
    val titleSize = if (compact) 17.sp else 22.sp
    val prevNextBtn = if (compact) 48.dp else 62.dp
    val prevNextIcon = if (compact) 26.dp else 34.dp
    val playBtn = if (compact) 58.dp else 76.dp
    val gapAfterCover = if (compact) 14.dp else 18.dp
    val gapAfterInfo = if (compact) 2.dp else 6.dp
    val gapBeforeMain = if (compact) 6.dp else 16.dp
    val gapBeforeMini = if (compact) 8.dp else 14.dp

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 信息区 ↔ 频谱区：叠在同一个固定高度容器里做交叉淡变。
        Box(
            Modifier
                .fillMaxWidth()
                .height(infoHeight),
            contentAlignment = Alignment.Center
        ) {
            if (vizAlpha < 0.995f) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        // blur 要 API 31，minSdk 24 上低版本机型完全看不到效果
                        // （就是"切换非常直接、没有模糊"）。所以过渡不依赖 blur：
                        // 用透明度 + 轻微缩放 + 纵向位移复合，任何版本都柔和。
                        // 有 blur 的机型再叠一层真模糊，锦上添花。
                        .then(
                            if (infoFade < 0.999f && android.os.Build.VERSION.SDK_INT >= 31)
                                Modifier.blur(
                                    ((1f - infoFade) * 10).dp,
                                    edgeTreatment = androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded
                                )
                            else Modifier
                        )
                        .graphicsLayer {
                            alpha = (1f - vizAlpha) * infoFade
                            // 只做频谱切换的纵向位移；切歌过渡不再纵向移动——
                            // 之前的 +8dp 下移会把艺术家那行推出固定高度的信息区被裁掉。
                            translationY = -10.dp.toPx() * vizAlpha
                            val s = 0.94f + 0.06f * infoFade
                            scaleX = s
                            scaleY = s
                        }
                ) {
                    Text(
                        title,
                        color = onCard,
                        fontSize = titleSize,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        artist,
                        color = readableAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (onArtistClick != null) Modifier
                            .junPressable { onArtistClick() }
                            .clip(RoundedCornerShape(6.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                        else Modifier
                    )
                }
            }

            if (vizAlpha > 0.005f) {
                SpectrumBars(
                    accent = readableAccent,
                    playing = isPlaying,
                    // 横屏(compact)禁用倾斜频谱：横屏时设备本就横放，
                    // 加速度计分量与竖屏不同轴，倾斜增益会乱跳影响可视化。
                    tiltEnabled = tiltSpectrum && !compact,
                    height = if (compact) 40.dp else 52.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = vizAlpha
                            translationY = 10.dp.toPx() * (1f - vizAlpha)
                        }
                )
            }
        }

        Spacer(Modifier.height(gapAfterCover))

        // 进度条
        NeonProgressBar(
            progress = progress,
            onSeek = onSeek,
            accent = readableAccent,
            track = trackColor,
            glass = glass,
            isDark = isDark,
            interactive = interactive,
            playing = isPlaying,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        )

        Spacer(Modifier.height(gapAfterInfo))

        // 当前时间 / 总时长
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                formatDuration(positionMs),
                color = subOnCard,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                formatDuration(durationMs),
                color = subOnCard,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(gapBeforeMain))

        // ────────────────────────────────────────────────────────
        // 6.9.5：加载提示条 —— 位置按用户要求定在「音质按钮上方那片空白」居中。
        //
        // 演进：6.9.0 画在卡片顶部（被封面压住，看不见）→ 6.9.4 抬到卡片上沿
        // 浮在最上层（能看见了，但要压在封面上，用户觉得别扭）→ 现在直接放进
        // 控制区：时间行与音质徽章之间，左右居中，谁都不挡。
        //
        // 它在音质徽章行的正上方，所以纵向排布、横向排布、堆叠排布、
        // 横屏卡片全都落在同一个视觉位置，不用各写一套。
        // ────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = loadText.isNotBlank(),
            enter = fadeIn(tween(140)) + expandVertically(tween(200)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(180))
        ) {
            PlayerLoadBanner(
                text = loadText,
                buffered = loadBuffered,
                canRetry = loadCanRetry,
                onHelp = onLoadHelp,
                onRetry = onRetryLoad,
                isDark = isDark,
                onCard = onCard,
                accent = btnColor,
                compact = compact,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (compact) 4.dp else 8.dp)
            )
        }

        // 音质徽章（5.1 重做交互）：
        //   · 单击 = 切档位（自动 → 标准 → 极高 → 无损 → Hi-Res → 自动），立即重新解析并原地续播
        //   · 长按 = 频谱可视化开关（原来单击就是这个，用户找不到切音质的地方）
        // 按钮文字永远是当前选中的档位名（见 PlayerUiState.qualityBadge）；真实解析结果在闪条里说。
        // 音质徽章 + 音效（均衡器）+ 评论：同一行居中。评论按钮紧挨音效按钮右侧，
        // 右上角用红点角标显示这首歌的评论数（0 时隐藏）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (audioFormat.isNotEmpty() && !hideQuality) {
                Box(Modifier.spotlightAnchorIfVisible(SpotlightKeys.QUALITY)) {
                    AudioFormatBadge(
                        format = audioFormat,
                        active = visualizerEnabled,
                        accent = readableAccent,
                        isDark = isDark,
                        compact = true,
                        // 只在切换那一刻脉冲一次，平时静止
                        pulseKey = qualityFlash,
                        onClick = { if (interactive) tuning.onCycleQuality() },
                        onLongClick = { if (interactive) onToggleVisualizer() }
                    )
                    // 6.0：切档提示不再画在这里。之前它是挂在徽章上的一个浮层，
                    // 徽章两侧紧挨着倍速/歌词按钮，浮层又比徽章宽 —— 结果就是用户说的
                    // "那行提示根本被按钮遮挡住了，字都没显示"。现在整条提示交给根层的
                    // ToastHost 小胶囊（见 MainScreen 的 toastItems），这里只留一次脉冲。
                }
                Spacer(Modifier.width(10.dp))
            }
            // 倍速：一个按钮，点了才弹档位面板。之前直接摊开一排六个 chip
            // 把播放页撑得太乱（用户原话），现在和音质徽章并排放，只占一颗的位置。
            if (playbackSpeedBp > 0) {
                Box {
                    SpeedBadgeButton(
                        speedBp = playbackSpeedBp,
                        open = speedOpen,
                        accent = readableAccent,
                        sub = subOnCard,
                        onCard = onCard,
                        isDark = isDark,
                        compact = compact,
                        onClick = { if (interactive) speedOpen = !speedOpen }
                    )
                    if (speedOpen) {
                        // 面板浮在按钮上方：BottomCenter 对齐后再抬一个面板高度
                        val lift = with(LocalDensity.current) { 118.dp.roundToPx() }
                        Popup(
                            alignment = Alignment.BottomCenter,
                            offset = IntOffset(0, -lift),
                            onDismissRequest = { speedOpen = false }
                        ) {
                            SpeedPickerPanel(
                                speedBp = playbackSpeedBp,
                                accent = readableAccent,
                                sub = subOnCard,
                                isDark = isDark,
                                onPick = { bp -> speedOpen = false; tuning.onSetSpeedBp(bp) }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            // 音效（均衡器）
            MiniControlButton(eqActive, onOpenEqualizer, btnColor, onCard) {
                Icon(Icons.Tune, null, Modifier.size(20.dp), it)
            }
            // 评论：右上角红点角标显示评论数（仅在调用方提供了入口时显示）
            if (onOpenComments != null) {
                Spacer(Modifier.width(10.dp))
                CommentButton(count = commentCount, onClick = onOpenComments, btnColor = btnColor, onCard = onCard)
            }
        }
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))

        // 主控制：上一曲 / 播放 / 下一曲，三者垂直居中对齐
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PressableIconButton(size = prevNextBtn, onClick = onPrev) {
                Icon(Icons.Play, contentDescription = null, tint = onCard, modifier = Modifier.size(prevNextIcon).graphicsLayer { scaleX = -1f })
            }
            PressableIconButton(size = playBtn, onClick = onToggle) {
                PlayPauseIcon(isPlaying, btnColor, playBtn * 0.62f)
            }
            PressableIconButton(size = prevNextBtn, onClick = onNext) {
                Icon(Icons.Play, contentDescription = null, tint = onCard, modifier = Modifier.size(prevNextIcon))
            }
        }

        Spacer(Modifier.height(gapBeforeMini))

        // 副控制行：播放模式 / 点赞 / 睡眠 / 下载 / 相似歌曲，右下角为播放列表入口
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = ((maxWidth - MINI_BTN_SIZE * 6) / 6).coerceIn(3.dp, 12.dp)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    MiniControlButton(shuffle || repeatMode != RepeatMode.OFF, onCyclePlayMode, btnColor, onCard) {
                        PlayModeIcon(shuffle, repeatMode, it)
                    }
                    // 点赞：本地乐观状态，快速连点也不会闪回
                    var localLiked by remember { mutableStateOf(liked) }
                    LaunchedEffect(liked) { localLiked = liked }
                    MiniControlButton(localLiked, { localLiked = !localLiked; onToggleLike() }, btnColor, onCard) {
                        Icon(if (localLiked) Icons.Favorite else Icons.FavoriteBorder, null, Modifier.size(20.dp), it)
                    }
                    SleepTimerButton(
                        active = sleepTimerMs > 0 || sleepStopAfterSong,
                        totalMs = sleepTimerMs,
                        endMs = sleepTimerEndMs,
                        onClick = onOpenSleepTimer,
                        accent = btnColor,
                        onCard = onCard
                    )
                    // 下载按钮（后台关闭下载时隐藏）
                    if (showDownload) {
                        MiniControlButton(false, onDownload, btnColor, onCard) {
                            Icon(JunIcons.Download, null, Modifier.size(20.dp), it)
                        }
                    }
                    // 相似歌曲：基于当前歌曲推荐并加入播放队列
                    MiniControlButton(false, onSimiSongs, btnColor, onCard) {
                        Icon(JunIcons.Similar, null, Modifier.size(20.dp), it)
                    }
                }
                // 卡片右下角：播放列表入口
                MiniControlButton(false, onOpenQueue, btnColor, onCard) {
                    Icon(Icons.QueueMusic, contentDescription = "播放列表", modifier = Modifier.size(20.dp), tint = it)
                }
            }
        }
    }
}

/**
 * 音质按钮：显示当前选中的**档位名**（标准音质 / 极高音质 / 无损音质 / Hi-Res 音质 / 自动）。
 *
 * 5.1 交互重做：单击 = 切播放档位（[onClick]），长按 = 频谱可视化开关（[onLongClick]）。
 * 之前单击直接吃掉频谱，用户根本找不到切音质的入口。
 * 未开启可视化时为低调的描边样式，开启后用主题色实心高亮。
 *
 * 手势用 [detectTapGestures] 而不是 combinedClickable：后者在部分版本仍是
 * ExperimentalFoundationApi，且这里不需要 ripple，徽章本来就很小。
 */
@Composable
private fun AudioFormatBadge(
    format: String,
    active: Boolean,
    accent: Color,
    isDark: Boolean,
    compact: Boolean,
    pulseKey: String = "",
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    val bg = if (active) accent else accent.copy(alpha = if (isDark) 0.18f else 0.12f)
    val fg = if (active) accent.readableTextOn() else accent
    // 系统"减少动态效果"或省电动画开着时，只留淡入淡出，不做滚动/扫光。
    val calm = JunMotion.reduced
    // pop：整颗徽章的弹簧回弹（0.82 → 过冲一点 → 落回 1），比原来"整体放大 24%"自然。
    // flash：0→1 走一遍，驱动描边收干 + 一道高光扫过徽章；平时停在 0，完全静止。
    val pop = remember { Animatable(1f) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(pulseKey) {
        if (pulseKey.isEmpty()) return@LaunchedEffect
        if (calm) {
            flash.snapTo(0f)
            return@LaunchedEffect
        }
        pop.snapTo(0.82f)
        flash.snapTo(0f)
        launch {
            flash.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
            flash.snapTo(0f)
        }
        pop.animateTo(1f, spring(dampingRatio = 0.40f, stiffness = 620f))
    }
    val k = flash.value
    Box(
        Modifier
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                shadowElevation = 14f * k
            }
            .clip(JunShape.chip)
            .background(bg)
            // 切档瞬间描边亮一下再收干，替代原来"整颗涨大"的粗糙感
            .border(
                width = (1.6f * (1f - k)).dp,
                color = accent.copy(alpha = 0.95f * (1f - k)),
                shape = JunShape.chip
            )
            .drawWithCache {
                val h = size.height
                onDrawWithContent {
                    drawContent()
                    if (k <= 0f) return@onDrawWithContent
                    val cx = size.width * (k * 1.6f - 0.3f)
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.5f),
                                accent.copy(alpha = 0.32f),
                                Color.Transparent
                            ),
                            start = Offset(cx - h * 0.7f, 0f),
                            end = Offset(cx + h * 0.7f, h)
                        ),
                        topLeft = Offset(cx - h, 0f),
                        size = Size(h * 2f, h)
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { Haptics.tap(); onClick() },
                    onLongPress = { Haptics.tap(); onLongClick() }
                )
            }
            .padding(horizontal = if (compact) 7.dp else 9.dp, vertical = if (compact) 2.dp else 4.dp)
    ) {
        // 档位名换成"滚上去"的过渡：旧名向上淡出、新名从下方滚入并轻微放大，
        // 像拨轮转过一格——正好对应"按一下换一档"的操作。
        AnimatedContent(
            targetState = format,
            transitionSpec = {
                if (calm) {
                    fadeIn(tween(140)) togetherWith fadeOut(tween(120))
                } else {
                    (fadeIn(tween(90)) +
                        slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it } +
                        scaleIn(tween(280), initialScale = 0.84f))
                        .togetherWith(
                            fadeOut(tween(130)) +
                                slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { -it } +
                                scaleOut(tween(280), targetScale = 1.1f)
                        )
                }
            },
            label = "qualityBadgeSwap"
        ) { label ->
            Text(
                label,
                color = fg,
                fontSize = if (compact) 10.sp else 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.6.sp,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * 封面左下角单行歌词：纯文字无底框，固定屏幕坐标（不随封面摇动），盖在封面左下角。
 *
 * 换词时「模糊渐隐 → 换字 → 模糊渐出」：先淡出到 0（伴随轻微模糊），切到新词后
 * 再淡入回到清晰。整段用一个 [Animatable] 驱动 visibility，alpha 与 blur 都从它
 * 派生——淡出时 blur 0→峰值、淡入时峰值→0，中段最糊，正好是字切换的瞬间。
 */
@Composable
private fun CoverLyricLine(text: String, modifier: Modifier = Modifier) {
    var displayText by remember { mutableStateOf(text) }
    val visibility = remember { Animatable(1f) }
    // 短词切换不突兀、无词/首词直接落位不重复播过渡
    LaunchedEffect(text) {
        if (displayText == text) return@LaunchedEffect
        if (displayText.isEmpty() || text.isEmpty()) { displayText = text; return@LaunchedEffect }
        visibility.animateTo(0f, tween(240))
        displayText = text
        visibility.animateTo(1f, tween(320))
    }
    // blur 峰值 6dp：visibility 0 处最糊，1 处清晰
    val blur = (1f - visibility.value) * 6f
    Text(
        displayText,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            fontFamily = LocalJunUiFamily.current,
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.55f),
                offset = Offset(0f, 1.2f),
                blurRadius = 3f
            )
        ),
        modifier = modifier
            .graphicsLayer { alpha = visibility.value }
            .blur(blur.dp)
    )
}

/** 主控制按钮：无底图标，按压缩放动画（0.85x），图标本身圆角。internal 供底部播放条复用同一套按钮语言。 */
@Composable
internal fun PressableIconButton(
    size: Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .junPressable(
                scaleDown = PRESS_SCALE_STRONG,
                feedback = PressFeedback.CLICK,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * 副控制按钮的固定边长。副控制行要按可用宽度反算间距（见 [PlayerCard]），
 * 所以这个尺寸必须是一处定义、多处引用，不能各写一遍 38.dp。
 */
private val MINI_BTN_SIZE = 38.dp

/**
 * 横屏时播放界面（含上栏）的统一宽度封顶。
 *
 * 三个布局共用这一个值：LIST 播放页的卡片、CAROUSEL/STACK 横屏的
 * LandscapeDeckPlayer、卡片模式顶栏 DeckTopBar——封顶相同、都水平居中，
 * 上栏与播放卡片左右边缘严格对齐。宽度只在这一层封顶，外层容器不再
 * 二次封顶（曾双层套娃导致 LIST 卡片比另外两个布局窄一圈）。
 * 唱片墙（COMPACT）明确排除——它的设计就是铺满整屏。
 */
internal val LANDSCAPE_PLAYER_MAX_WIDTH = 680.dp

/**
 * 上栏横屏封顶的补偿值：DeckTopBar / LIST 上栏的玻璃都在 padding(horizontal=22)
 * 之后绘制，玻璃可见宽 = 封顶 - 44。给上栏封顶 680+44，玻璃才和 680 宽的
 * 播放卡片左右缘严格对齐。
 */
internal val LANDSCAPE_BAR_MAX_WIDTH = 724.dp

/**
 * 副控制按钮未激活时的图标不透明度。
 *
 * 原值 0.72 是这个 bug 的根源：黑白主题下激活色就是纯黑/纯白，
 * 白卡上 72% 的黑落在 #494949，和激活的 #000000 都是"很深的颜色"，分不出开关。
 * 压到 0.32 后未激活是 #ADADAD 这种明确的关闭灰，和实色隔着一大截明度，
 * 20dp 的线条图标在这个灰度下依然看得清轮廓。
 */
private const val MINI_INACTIVE_ALPHA = 0.32f

/**
 * 副控制按钮：无底纯图标 + 按压缩放动画。
 *
 * 状态只由图标颜色表达，不加底、不加点 —— 这一行的调子就是裸图标。
 * 所以未激活和激活之间的明度差必须拉得足够开，见 [MINI_INACTIVE_ALPHA]。
 */
@Composable
private fun MiniControlButton(
    active: Boolean,
    onClick: () -> Unit,
    accent: Color,
    onCard: Color,
    icon: @Composable (Color) -> Unit
) {
    // 颜色走短过渡，点一下不硬跳
    val activeT by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(180),
        label = "miniBtnActive"
    )
    val iconColor = lerp(onCard.copy(alpha = MINI_INACTIVE_ALPHA), accent, activeT)
    Box(
        Modifier
            .size(MINI_BTN_SIZE)
            .junPressable(scaleDown = 0.82f, feedback = PressFeedback.TAP, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { icon(iconColor) }
}

/**
 * 评论按钮：与 [MiniControlButton] 同尺寸，右上角叠加红色数字角标显示评论数。
 * 未登录网易云 / 评论数为 0 时不显示角标。
 */
@Composable
private fun CommentButton(
    count: Int,
    onClick: () -> Unit,
    btnColor: Color,
    onCard: Color
) {
    Box(contentAlignment = Alignment.Center) {
        MiniControlButton(false, onClick, btnColor, onCard) {
            Icon(JunIcons.Comment, null, Modifier.size(20.dp), it)
        }
        if (count > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-4).dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE53935))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    if (count > 9999) "9999+" else "$count",
                    color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** 播放↔暂停图标：旋转 90° 翻面 + 缩放淡变的组合过渡。internal 供底部播放条复用。 */
@Composable
internal fun PlayPauseIcon(isPlaying: Boolean, tint: Color, size: Dp = 39.dp) {
    // 播放中显示暂停图标、暂停时显示播放图标（按钮表达"点击后的动作"）
    val t by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "playPauseFlip"
    )
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Play, contentDescription = null, tint = tint,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    rotationZ = -90f * t
                    alpha = 1f - t
                    scaleX = 1f - 0.15f * t
                    scaleY = 1f - 0.15f * t
                }
        )
        Icon(
            Icons.Pause, contentDescription = null, tint = tint,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    rotationZ = 90f * (1f - t)
                    alpha = t
                    scaleX = 0.85f + 0.15f * (1f - t)
                    scaleY = 0.85f + 0.15f * (1f - t)
                }
        )
    }
}

/** 睡眠定时器按钮：未激活显示月亮图标；激活后外圈进度环 + 中央倒计时数字（每分钟跳字） */
@Composable
private fun SleepTimerButton(
    active: Boolean,
    totalMs: Long,
    endMs: Long,
    onClick: () -> Unit,
    accent: Color,
    onCard: Color
) {
    // 按压手感交给 junPressable（与 MiniControlButton 同一颗 38dp、同一档 0.82），
    // 不再自己记 InteractionSource / 缩放。
    // 每秒刷新剩余毫秒
    var remainingMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active, totalMs, endMs) {
        if (!active || totalMs <= 0) { remainingMs = 0L; return@LaunchedEffect }
        while (true) {
            remainingMs = (endMs - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remainingMs <= 0L) break
            delay(1000)
        }
    }
    val rawFraction = if (totalMs > 0) (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    // 圆环平滑过渡，不跳变
    val fraction by animateFloatAsState(
        targetValue = rawFraction,
        animationSpec = tween(durationMillis = 900, easing = LinearEasing),
        label = "sleepRing"
    )
    // 剩余分钟数（向上取整，每秒跳动）
    val remainMinutes = ((remainingMs + 59999) / 60000).toInt().coerceAtLeast(0)

    val iconColor = if (active) accent else onCard.copy(alpha = MINI_INACTIVE_ALPHA)

    Box(
        Modifier
            .size(MINI_BTN_SIZE)
            .junPressable(scaleDown = 0.82f, feedback = PressFeedback.TAP, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (active) {
            // 进度环（平滑动画）
            Canvas(Modifier.size(38.dp)) {
                val stroke = 2.6f * density
                val inset = stroke / 2f + 1.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(
                    color = onCard.copy(alpha = 0.15f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke)
                )
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            // 中央倒计时数字
            Text(
                text = "$remainMinutes",
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold
            )
        } else {
            Icon(Icons.Bedtime, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * 睡眠定时器弹层：与均衡器面板同构的自绘覆盖层 —— 遮罩 + 底部玻璃卡片。
 *
 * 不用 ModalBottomSheet：它自带的 Surface 是实色容器，玻璃材质塞不进去
 * （containerColor 只能给一个颜色，拿不到折射背板），液态玻璃下会露出一块实心方块。
 * 自绘之后这层和设置 / 均衡器走同一套 [junSurface]，三个弹层观感统一。
 *
 * 放在根层级调用，才能折射到「背景 + 页面」两层背板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    currentMinutes: Int,
    stopAfterSong: Boolean = false,
    onSetStopAfterSong: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
    onSetMinutes: (Int) -> Unit,
    colors: JunColors
) {
    val minutesOptions = listOf(5, 10, 15, 20, 30, 45, 60)
    // 默认选中当前定时值；无定时时落在 5 分钟档
    var selectedIndex by remember { mutableIntStateOf(minutesOptions.indexOf(currentMinutes).coerceAtLeast(0)) }
    val selectedMinutes = minutesOptions[selectedIndex]
    // 弹层底是 colors.surface（浅色下接近纯白），主题色直接当文字会糊掉，
    // 统一走可读修正；深色下修正函数原样返回。
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val glass = isLiquidGlass
    // 拖把手下滑关闭
    val drag = rememberSheetDragState()

    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        contentModifier = Modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 10.dp, bottom = 24.dp)
    ) {
            SheetDragHandle(drag, onDismiss, colors.card)
            Spacer(Modifier.height(14.dp))
                    Text("睡眠定时器", color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(18.dp))

                    // 圆形进度环 + 分钟数（80dp 紧凑尺寸）
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(80.dp)) {
                            val stroke = 6f * density
                            val inset = stroke / 2f + 2.dp.toPx()
                            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                            drawArc(
                                // 玻璃上轨道要用半透明白/黑，实色轨道会和折射背景糊成一体
                                color = if (glass) onSheet.copy(alpha = 0.18f) else colors.card,
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = arcSize,
                                style = Stroke(width = stroke)
                            )
                            val fraction = (selectedIndex + 1).toFloat() / minutesOptions.size
                            drawArc(
                                color = accent,
                                startAngle = -90f,
                                sweepAngle = 360f * fraction,
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = arcSize,
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "$selectedMinutes",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = accent
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // 与设置页同款 CompactSlider：白点嵌条内、同高同圆角，不再用 Material 默认样式
                    CompactSlider(
                        value = selectedIndex.toFloat(),
                        onValueChange = { selectedIndex = it.roundToInt().coerceIn(0, minutesOptions.size - 1) },
                        valueRange = 0f..(minutesOptions.size - 1).toFloat(),
                        steps = minutesOptions.size - 2,
                        modifier = Modifier.fillMaxWidth(),
                        activeColor = accent,
                        inactiveColor = if (glass) onSheet.copy(alpha = 0.18f) else colors.card
                    )

                    // 刻度标签
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        minutesOptions.forEach { min ->
                            Text(
                                "$min",
                                fontSize = 10.sp,
                                color = if (min == selectedMinutes) accent else onSheet.copy(alpha = 0.62f),
                                fontWeight = if (min == selectedMinutes) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // 播完这首再停：与分钟定时互斥，适合"这首听完后就睡"
                    val toggleTrackW = 46.dp
                    val toggleTrackH = 26.dp
                    val toggleThumb = 20.dp
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // 整行开关用行级按压：缩放层在 clip 之前，整条一起软
                            .junPressableRow { onSetStopAfterSong(!stopAfterSong) }
                            .clip(RoundedCornerShape(toggleTrackH / 2))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "播完这首再停",
                                color = onSheet,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "当前曲目自然结束后暂停播放",
                                color = onSheet.copy(alpha = 0.55f),
                                fontSize = 11.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(
                            Modifier
                                .size(toggleTrackW, toggleTrackH)
                                .background(
                                    if (stopAfterSong) accent else onSheet.copy(alpha = 0.2f),
                                    RoundedCornerShape(toggleTrackH / 2)
                                )
                                .padding(3.dp),
                            contentAlignment = if (stopAfterSong) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Box(
                                Modifier
                                    .size(toggleThumb)
                                    .background(Color.White, RoundedCornerShape(50))
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // 取消 / 关闭定时器 / 设置
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SheetActionButton(
                            label = "取消",
                            labelColor = onSheet.copy(alpha = 0.75f),
                            borderColor = onSheet.copy(alpha = 0.25f),
                            colors = colors,
                            modifier = Modifier.weight(1f),
                            onClick = { Haptics.tap(); onDismiss() }
                        )
                        SheetActionButton(
                            label = "关闭",
                            labelColor = Color(0xFFFF4D6D),
                            borderColor = Color(0xFFFF4D6D).copy(alpha = 0.65f),
                            colors = colors,
                            modifier = Modifier.weight(1f),
                            onClick = { Haptics.click(); onSetMinutes(0); onDismiss() }
                        )
                        // 设置：主题色实心，玻璃上也要保持醒目，所以不走玻璃材质
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .junPressable(feedback = PressFeedback.CLICK) {
                                    onSetMinutes(selectedMinutes)
                                    onDismiss()
                                }
                                .clip(JunShape.item)
                                .background(accent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "设置",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = accent.readableTextOn()
                            )
                        }
                    }
    }
}

/** 弹层里的描边按钮：底走当前材质（玻璃下是嵌套叠加层），描边给语义色 */
@Composable
private fun SheetActionButton(
    label: String,
    labelColor: Color,
    borderColor: Color,
    colors: JunColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .height(44.dp)
            // 触感仍由调用方的 onClick 决定（取消=tap、关闭=click 两档不同），
            // 这里只统一按压弹簧；缩放层必须在 junSurface 之前，玻璃底才跟着一起缩。
            .junPressable(feedback = PressFeedback.NONE, onClick = onClick)
            .junSurface(GlassLevel.CARD, colors, JunShape.item)
            .border(1.dp, borderColor, JunShape.item),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = labelColor)
    }
}

/** 标准 Material 图标（手写 ImageVector，避免引 5MB 的 icons-extended 库） */
private object Icons {
    // 图标定义已收拢到全局 JunIcons（唯一来源）。此处保留 typealias 式转发，
    // 避免大面积改调用点，同时消除三份重复的 path 字符串与构建器。
    val Play get() = JunIcons.Play
    val Pause get() = JunIcons.Pause
    val Shuffle get() = JunIcons.Shuffle
    val Repeat get() = JunIcons.Repeat
    val Bedtime get() = JunIcons.Bedtime
    val Tune get() = JunIcons.Tune
    val Favorite get() = JunIcons.Favorite
    val FavoriteBorder get() = JunIcons.FavoriteBorder
    val QueueMusic get() = JunIcons.QueueMusic
}

/**
 * 四合一播放模式指示（原 随机+循环 两枚按钮合并后的图标）：
 * 关 = 灰色随机图标；随机 = 点亮随机；列表循环 = 点亮循环；单曲循环 = 点亮循环 + 中心圆点。
 *
 * 图标形状表示"当前是哪一档"，颜色深浅表示开/关；随机 ↔ 循环切换时做
 * 同点旋转淡变（和播放/暂停同款手法），两图标交叉切换不硬切。
 */
@Composable
private fun PlayModeIcon(shuffle: Boolean, repeatMode: RepeatMode, color: Color) {
    // 单曲圆点：淡入 + 弹性放大，不硬出现
    val oneT by animateFloatAsState(
        targetValue = if (repeatMode == RepeatMode.ONE) 1f else 0f,
        animationSpec = JunMotion.pressScale(),
        label = "playModeOneDot"
    )
    // 随机(0) ↔ 循环(1) 图标翻面过渡
    val iconT by animateFloatAsState(
        targetValue = if (shuffle) 0f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "playModeIconFlip"
    )
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Shuffle, contentDescription = null, tint = color,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    rotationZ = -90f * iconT
                    alpha = 1f - iconT
                    scaleX = 0.85f + 0.15f * (1f - iconT)
                    scaleY = 0.85f + 0.15f * (1f - iconT)
                }
        )
        Icon(
            Icons.Repeat, contentDescription = null, tint = color,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    rotationZ = 90f * (1f - iconT)
                    alpha = iconT
                    scaleX = 0.85f + 0.15f * iconT
                    scaleY = 0.85f + 0.15f * iconT
                }
        )
        if (oneT > 0.01f) {
            Canvas(
                Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        alpha = oneT
                        scaleX = oneT; scaleY = oneT
                    }
            ) {
                // 单曲循环必须在图形上有额外标记，否则和列表循环完全一样
                drawCircle(
                    color = color,
                    radius = 1.9.dp.toPx(),
                    center = Offset(size.width / 2f, size.height / 2f)
                )
            }
        }
    }
}

/** 时长格式化统一用 MainScreen.kt 里的 internal fun formatDuration */

/** 无 Coil 的封面加载：直接从音频文件读内嵌封面（比 MediaStore 缓存准确） */
@Composable
private fun AlbumArt(filePath: String?) {
    // 不用 remember(filePath)：切歌时 key 变化会把 bitmap 重置 null，
    // 新封面解码的几百毫秒里露出占位色块、解码完又突然出现——就是切歌"闪一下"。
    // 改成保留旧图、新图就绪才替换 + Crossfade 淡入。
    // 初始值先同步窥缓存：翻卡前这张封面就在旁边的卡上显示过、必在缓存里，
    // 首帧直接命中，连那一次 280ms 淡入都省了（淡入本身也是"闪"的一种）。
    // 高清优先：若这张之前已在大卡看过、命中高清内存缓存，首帧直接上高清。
    var bitmap by remember { mutableStateOf(ArtworkLoader.peekHiRes(filePath) ?: ArtworkLoader.peek(filePath)) }
    var loadedPath by remember { mutableStateOf<String?>(if (bitmap != null) filePath else null) }
    // 当前显示的是否已是高清：避免高清就绪后又被后到的缩略图覆盖回去。
    var hiResPath by remember { mutableStateOf<String?>(if (ArtworkLoader.peekHiRes(filePath) != null) filePath else null) }
    val coverVersion = CustomCoverStore.version
    var seenCoverVersion by remember { mutableIntStateOf(coverVersion) }

    LaunchedEffect(filePath, coverVersion) {
        // 用户在设置里换了封面：丢掉旧图与“已加载”标记，重新走一遍加载。
        if (coverVersion != seenCoverVersion) {
            seenCoverVersion = coverVersion
            bitmap = null
            loadedPath = null
            hiResPath = null
        }
        // 阶段一：缩略图立即到位（磁盘缓存命中≈瞬时），先保证"不割裂"。
        if (filePath != loadedPath) {
            val bmp = if (filePath == null) null else ArtworkLoader.load(filePath)
            // 若高清已在阶段二先就位，别用低清盖回去。
            if ((bmp != null || filePath == null) && hiResPath != filePath) {
                bitmap = bmp
                loadedPath = filePath
            }
        }
        // 阶段二：后台解码高清原图，就绪后无缝换上，保证"又清"。
        // 大图只在播放大卡这一处用，内存开销可控（见 ArtworkLoader.HIRES_CACHE_MAX）。
        if (filePath != null && hiResPath != filePath) {
            val hi = ArtworkLoader.loadHiRes(filePath)
            if (hi != null && filePath == loadedPath) {
                bitmap = hi
                hiResPath = filePath
            }
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        androidx.compose.animation.Crossfade(targetState = bmp, animationSpec = tween(280), label = "albumArt") { b ->
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** 进度条（6.9.3：按用户要求恢复 6.4 原版粗胶囊样式；升级的是加载指示，见 PlayerLoadBanner） */
@Composable
private fun NeonProgressBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    accent: Color,
    track: Color,
    modifier: Modifier = Modifier,
    /** 液态玻璃模式：轨道背后是任意亮度的模糊封面，实色轨道会融进背景 */
    glass: Boolean = false,
    isDark: Boolean = true,
    /** false 时只画不响应手势（卡片模式里非当前那几张卡） */
    interactive: Boolean = true,
    /** 6.9.3：保留参数位（呼吸光点方案已按用户要求撤回），调用处不用改 */
    playing: Boolean = true
) {
    // 拖动状态 + 本地手指位置：见下方手势注释。bulge 用统一弹簧令牌，
    // 按下时轨道"鼓"一点、松手弹回，拖动焦点线随之加粗。
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val bulge by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = JunMotion.glide(),
        label = "seekBulge"
    )
    val shownProgress = if (dragging) dragFrac else progress
    Box(
        modifier
            .height(14.dp)
            .then(
                if (interactive) Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            onSeek((offset.x / size.width).coerceIn(0f, 1f))
                        }
                    }
                    .pointerInput(Unit) {
                        // 拖动期间画的是【手指位置】而不是【播放器回报的进度】。
                        // 后者要等 media3 消化 seek 指令，慢几十到几百毫秒，于是出现
                        // "手指已经到 2:10、进度条还停在 1:40"的脱手感。
                        // onSeek 照常发（真正 seek 仍由播放器决定），但显示走本地这条。
                        detectHorizontalDragGestures(
                            onDragStart = { start ->
                                dragging = true
                                dragFrac = (start.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false }
                        ) { change, _ ->
                            dragFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                            onSeek(dragFrac)
                        }
                    }
                else Modifier
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 轨道厚度随拖动状态用弹簧长起来（11 → 13.5dp，仍在 14dp 命中区内），
            // 松手弹回。只有绘制变粗，Box 高度不变，布局不会抖。
            val barH = (11f + 2.5f * bulge) * density
            val y = (size.height - barH) / 2f
            val radius = barH / 2f
            val corner = androidx.compose.ui.geometry.CornerRadius(radius, radius)

            if (glass) {
                // 玻璃上不能用实色轨道：卡片是半透明的，轨道会和模糊封面糊成一体。
                // 改成"凹槽"——压暗一层 + 一圈亮描边，无论背后封面是亮是暗都看得见沟槽轮廓。
                drawRoundRect(
                    color = Color.Black.copy(alpha = if (isDark) 0.45f else 0.22f),
                    topLeft = Offset(0f, y),
                    size = Size(size.width, barH),
                    cornerRadius = corner
                )
                val rimW = 1f * density
                drawRoundRect(
                    color = Color.White.copy(alpha = if (isDark) 0.30f else 0.55f),
                    topLeft = Offset(rimW / 2f, y + rimW / 2f),
                    size = Size(size.width - rimW, barH - rimW),
                    cornerRadius = corner,
                    style = Stroke(width = rimW)
                )
            } else {
                drawRoundRect(
                    color = track,
                    topLeft = Offset(0f, y),
                    size = Size(size.width, barH),
                    cornerRadius = corner
                )
            }

            val filled = (size.width * shownProgress).coerceAtLeast(barH)
            drawRoundRect(
                color = accent,
                topLeft = Offset(0f, y),
                size = Size(filled, barH),
                cornerRadius = corner
            )
            // 玻璃下给已播部分补一圈同色高光：主题色若与封面撞色，靠这圈亮边仍能分辨进度位置
            if (glass) {
                drawRoundRect(
                    color = Color.White.copy(alpha = if (isDark) 0.22f else 0.30f),
                    topLeft = Offset(0f, y),
                    size = Size(filled, barH),
                    cornerRadius = corner,
                    style = Stroke(width = 1f * density)
                )
            }
            // 拖动时端点长出一颗焦点点：手指有了明确的"我抓在这里"的落点，
            // 不靠猜。淡入淡出跟着 bulge 走，松手自己缩回去。
            val head = (size.width * shownProgress).coerceIn(0f, size.width)
            if (bulge > 0.01f) {
                val thumbR = barH / 2f + 1.2f * density * bulge
                drawCircle(
                    color = accent.copy(alpha = 0.35f * bulge),
                    radius = thumbR * 1.25f,
                    center = Offset(head, size.height / 2f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = (0.55f + 0.45f * bulge)),
                    radius = thumbR,
                    center = Offset(head, size.height / 2f)
                )
            }
        }
    }
}

/**
 * 横屏卡片模式播放器：左侧封面舞台由外部 [coverSlot] 提供（承担翻页/堆叠动画），
 * 右侧是固定的控制区。切歌时右侧标题/艺术家做模糊淡变过渡（title/artist 变化触发）。
 *
 * 复用 PlayerControls（与竖屏/LIST 横屏同一套控件与动画），颜色/频谱过渡在此自算。
 */
@Composable
internal fun LandscapeDeckPlayer(
    title: String,
    artist: String,
    onArtistClick: (() -> Unit)? = null,
    positionMs: Long,
    durationMs: Long,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    progress: Float,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleLike: () -> Unit,
    liked: Boolean,
    colors: JunColors,
    sleepTimerMs: Long,
    sleepTimerEndMs: Long,
    onOpenSleepTimer: () -> Unit,
    eqActive: Boolean,
    onOpenEqualizer: () -> Unit,
    onOpenQueue: () -> Unit = {},
    onDownload: () -> Unit = {},
    showDownload: Boolean = true,
    onSimiSongs: () -> Unit = {},
    onOpenComments: (() -> Unit)? = null,
    commentCount: Int = 0,
    audioFormat: String,
    visualizerEnabled: Boolean,
    onToggleVisualizer: () -> Unit,
    /** 5.1：切档瞬间的一次性提示，空则不显示 */
    qualityFlash: String = "",
    /** 5.1：倍速万分比；-1 表示不显示倍速行 */
    playbackSpeedBp: Int = -1,
    tiltSpectrum: Boolean,
    // 6.9.4：横屏卡片模式（横向排布 / 堆叠排布横屏）此前完全没有加载反馈，
    // 取源那几秒屏幕上没有任何动静。这里接上与竖屏同一个提示条。
    loadingText: String = "",
    loadingBuffered: Float = -1f,
    loadCanRetry: Boolean = false,
    onLoadHelp: () -> Unit = {},
    onRetryLoad: () -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * 封面舞台的宽高比。
     *
     * 舞台必须按高度算出宽度，不能用 weight 吃满剩余空间：
     * 封面是正方形（宽=高），舞台却被拉到几百 dp 宽时，
     * 页与页之间就空出一大条，邻曲被推到舞台边缘外。
     * 1.0 = 刚好一张封面；大于 1 的部分就是留给邻曲/堆叠露头的余量。
     */
    coverAspect: Float = 1f,
    coverSlot: @Composable () -> Unit
) {
    val cardColor = colors.playerCard
    val glass = isLiquidGlass
    val btnColor = colors.primary.readableOn(cardColor, 3.2f)
    val trackColor = colors.playerTrack
    val onCard = if (colors.isDark) Color.White else Color.Black
    val readableAccent = btnColor
    val subOnCard = onCard.copy(alpha = 0.72f)

    // 可视化过渡（同 PlayerCard）
    val vizT = remember { Animatable(if (visualizerEnabled) 1f else 0f) }
    LaunchedEffect(visualizerEnabled) {
        vizT.animateTo(
            targetValue = if (visualizerEnabled) 1f else 0f,
            animationSpec = tween(420, easing = androidx.compose.animation.core.EaseInOut)
        )
    }

    // 切歌时资料模糊淡变：title+artist 变化 → 淡出模糊 → 换字 → 淡入清晰。
    // 用一个 Animatable 驱动 alpha 与 blur（同封面歌词的换词动画节奏）。
    val infoFade = remember { Animatable(1f) }
    val shownTitle = remember { mutableStateOf(title) }
    val shownArtist = remember { mutableStateOf(artist) }
    LaunchedEffect(title, artist) {
        if (shownTitle.value == title && shownArtist.value == artist) return@LaunchedEffect
        infoFade.animateTo(0f, tween(210, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        shownTitle.value = title
        shownArtist.value = artist
        infoFade.animateTo(1f, tween(360, easing = androidx.compose.animation.core.EaseOutCubic))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        // 横屏窄化：整行封顶 LANDSCAPE_PLAYER_MAX_WIDTH 并居中（本组件只在横屏
        // 卡片模式使用）。唱片墙不经过这里，不受影响。
        // 背板与 LIST 播放页的 PlayerCard 完全同款（GlassLevel.PLAYER 玻璃 +
        // 同圆角同底色）——此前这里只有一排裸控件悬在背景上，没有卡片包裹，
        // 三个布局里只有它有玻璃背板，观感断裂。
        Row(
            Modifier
                .fillMaxHeight()
                .widthIn(max = LANDSCAPE_PLAYER_MAX_WIDTH)
                .junSurface(GlassLevel.PLAYER, colors, JunShape.card, solid = cardColor)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 左：封面舞台（翻页/堆叠由外部提供）。
            // 宽度由 coverAspect 按高度算出——封面是正方形，舞台宽度就该等于
            // 高度（加上邻曲露头的余量），不能吃满剩余宽度。
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(coverAspect),
                contentAlignment = Alignment.Center
            ) { coverSlot() }

            // 右：控制区，拿走剩下的横向空间并居中。
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
            PlayerControls(
                title = shownTitle.value,
                artist = shownArtist.value,
                onArtistClick = onArtistClick,
                positionMs = positionMs,
                durationMs = durationMs,
                shuffle = shuffle,
                repeatMode = repeatMode,
                progress = progress,
                isPlaying = isPlaying,
                onToggle = onToggle,
                onPrev = onPrev,
                onNext = onNext,
                onSeek = onSeek,
                onCyclePlayMode = onCyclePlayMode,
                onToggleLike = onToggleLike,
                liked = liked,
                sleepTimerMs = sleepTimerMs,
                sleepTimerEndMs = sleepTimerEndMs,
                onOpenSleepTimer = onOpenSleepTimer,
                eqActive = eqActive,
                onOpenEqualizer = onOpenEqualizer,
                onOpenQueue = onOpenQueue,
                onDownload = onDownload,
                showDownload = showDownload,
                onSimiSongs = onSimiSongs,
                onOpenComments = onOpenComments,
                commentCount = commentCount,
                audioFormat = audioFormat,
                visualizerEnabled = visualizerEnabled,
                onToggleVisualizer = onToggleVisualizer,
                qualityFlash = qualityFlash,
                playbackSpeedBp = playbackSpeedBp,

                tiltSpectrum = tiltSpectrum,
                vizAlpha = vizT.value,
                onCard = onCard,
                subOnCard = subOnCard,
                readableAccent = readableAccent,
                btnColor = btnColor,
                trackColor = trackColor,
                glass = glass,
                isDark = colors.isDark,
                interactive = true,
                compact = true,
                // 6.9.5：横屏也把提示条放进控制区（音质按钮上方），见 PlayerControls
                loadText = loadingText,
                loadBuffered = loadingBuffered,
                loadCanRetry = loadCanRetry,
                onLoadHelp = onLoadHelp,
                onRetryLoad = onRetryLoad,
                infoFade = infoFade.value,
                modifier = Modifier.fillMaxWidth()
)
            }
        }
    }
}
/**
 * 播放页那颗倍速按钮：平时只显示当前档位（1× / 1.25×…），点了才弹面板。
 * 1× 时尽量低调（不描边），只要不是原速就点亮——用户扫一眼就知道自己现在不是 1 倍。
 */
@Composable
private fun SpeedBadgeButton(
    speedBp: Int,
    open: Boolean,
    accent: Color,
    sub: Color,
    onCard: Color,
    isDark: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    val on = speedBp != 100 || open
    val bg = if (on) accent.copy(alpha = if (isDark) 0.22f else 0.14f) else onCard.copy(alpha = 0.10f)
    val fg = if (on) accent.readableTextOn() else sub
    Box(
        Modifier
            .clip(JunShape.chip)
            .background(bg)
            .then(if (open) Modifier.border(1.dp, accent.copy(alpha = 0.9f), JunShape.chip) else Modifier)
            .pointerInput(Unit) { detectTapGestures(onTap = { Haptics.tap(); onClick() }) }
            .padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 2.dp else 4.dp
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                speedChipLabel(speedBp),
                color = fg,
                fontSize = if (compact) 10.sp else 11.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.width(2.dp))
            // 小三角：提示这颗是"能点开选"的，不是单击循环
            Canvas(Modifier.size(7.dp)) {
                val w = size.width
                val h = size.height
                drawPath(
                    Path().apply {
                        moveTo(0f, h * 0.28f)
                        lineTo(w, h * 0.28f)
                        lineTo(w / 2f, h * 0.92f)
                        close()
                    },
                    fg
                )
            }
        }
    }
}

/**
 * 倍速档位面板：六档，3×2 排布，点一档立即生效并收起。
 * 浮在按钮上方（Popup 由调用方定位），点击面板外或按返回都会收起。
 */
@Composable
private fun SpeedPickerPanel(
    speedBp: Int,
    accent: Color,
    sub: Color,
    isDark: Boolean,
    onPick: (Int) -> Unit
) {
    val bg = if (isDark) Color.Black.copy(alpha = 0.94f) else Color.White.copy(alpha = 0.97f)
    val fg = if (isDark) Color.White else Color.Black
    Column(
        Modifier
            .width(224.dp)
            .shadow(16.dp, JunShape.item, clip = false)
            .clip(JunShape.item)
            .background(bg)
            .border(1.dp, accent.copy(alpha = 0.30f), JunShape.item)
            .padding(10.dp)
    ) {
        Text("倍速", color = sub, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        SPEED_STEPS.chunked(3).forEach { rowSteps ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowSteps.forEach { bp ->
                    val sel = bp == speedBp
                    Box(
                        Modifier
                            .weight(1f)
                            .junPressable { onPick(bp) }
                            .clip(JunShape.chip)
                            .background(
                                if (sel) accent.copy(alpha = if (isDark) 0.30f else 0.16f)
                                else fg.copy(alpha = 0.06f)
                            )
                            .then(
                                if (sel) Modifier.border(1.dp, accent.copy(alpha = 0.9f), JunShape.chip)
                                else Modifier
                            )
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            speedChipLabel(bp),
                            color = if (sel) accent.readableTextOn() else fg.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                            fontWeight = if (sel) FontWeight.Black else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(
            "变速不变调",
            color = sub, fontSize = 9.sp,
            modifier = Modifier.padding(start = 2.dp, top = 2.dp)
        )
    }
}
