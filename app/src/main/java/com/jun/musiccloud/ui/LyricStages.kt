package com.jun.musiccloud.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.data.LyricDisplayFilter
import kotlin.math.sin
import com.jun.musiccloud.data.LyricLine
import com.jun.musiccloud.data.LyricParser
import com.jun.musiccloud.data.LyricRenderEngine
import com.jun.musiccloud.data.LyricRuntime
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.fontFamilyForIndex
import com.jun.musiccloud.ui.theme.readableOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Folia 的十四种歌词视觉模式，按同一份时间轴拆成「空间 + 动势 + 排版」。
 *
 * 序号与设置页、播放页舞台按钮共用：
 *  0 流光（保留原有滚动歌词层）
 *  1 心象  2 云阶  3 浮名  4 莫奈  5 群唱  6 倾诉
 *  7 回环  8 镜台  9 时计 10 商籁 11 凝彩 12 绘光 13 静止
 *
 * 所有模式共用 LyricFrame：当前句、上一句、下一句、逐字进度、翻译/音译、
 * 时间轴微调都来自同一处，切舞台不会打断播放，也不会丢失歌词位置。
 */
internal object LyricStageCatalog {
    val names: List<String> = listOf(
        "流光", "心象", "云阶", "浮名", "莫奈", "群唱", "倾诉",
        "回环", "镜台", "时计", "商籁", "凝彩", "绘光", "静止"
    )
    val count: Int get() = names.size

    fun name(stage: Int): String = names.getOrElse(stage) { names.first() }

    fun next(stage: Int): Int = ((stage + 1) % count + count) % count
}

/** 舞台渲染所需的全部共享状态；各风格只消费，不各自扫描时间轴。 */
internal data class LyricFrame(
    val stage: Int,
    val lines: List<LyricLine>,
    val loading: Boolean,
    val index: Int,
    val adjustedMs: Long,
    /** 实时播放位置提供者：逐字扫光的帧钟每帧读一次，不经 500ms 轮询中转 */
    val live: () -> Long,
    val lineEndMs: Long,
    val durationMs: Long,
    val title: String,
    val artist: String,
    val colors: JunColors,
    val family: FontFamily,
    val fontSize: Float,
    val align: TextAlign,
    val horizontal: Alignment.Horizontal,
    val karaoke: Boolean,
    val showTranslation: Boolean,
    val showRomanization: Boolean,
    val artworkKey: String,
    /** 当前是否横屏：横屏下 chrome 已让位，各风格收紧上下留白 */
    val landscape: Boolean,
    /** 运动风格：逐词弹跳 / 扫光 / 玻璃 / 漂浮 / 轻快 / 流光 / 故障 */
    val motion: LyricMotionStyle,
    /** 显示模式：控制各舞台能拿到的上下文行数 */
    val displayMode: LyricDisplayMode,
    val contextLimit: Int,
    /** 实际生效的渲染品质（AUTO 已解析成具体档位） */
    val quality: LyricQuality,
    /** 节拍状态；null = 未开启节拍律动 */
    val beat: LyricBeatState?,
    val particles: Boolean,
    val bassGlow: Boolean,
    /** 排版细调：行距、字距、字重、描边、纹理 */
    val typography: LyricTypography = LyricTypography(),
    /** 逐词发光样式：0=无 1=拖尾 2=呼吸 3=强拍闪白 */
    val glowStyle: Int = 1,
    val glowStrength: Float = 0.6f,
    /** 当前句是否属于副歌 */
    val chorus: Boolean = false,
    val chorusEffect: Int = 0,
    val chorusStrength: Float = 0.7f,
    /** 封面纵深背景 */
    val coverDepth: Boolean = false,
    /** 当前舞台的调参：密度 / 速度 / 发光 / 镜头，0..100 */
    val tuning: List<Int> = listOf(50, 50, 50, 50),
    /** 当前舞台专属调校：字号 / 动势 / 辉光 / 形态 */
    val stageProfile: StageProfile = StageProfile.Default,
    /** 长音频（播客/DJ）：降低运动幅度、拉长停留 */
    val longForm: Boolean = false,
    /** 中文语义断行 */
    val semanticWrap: Boolean = true,
    /** 本地视频背景（SAF URIs）；空 = 关闭 */
    val videoUri: String = "",
    val videoAlpha: Float = 0.42f,
    /** 当前是否正在播放：视频背景跟随暂停 */
    val playing: Boolean = false,
    /** 节拍电影镜头开关与幅度（0..1，0.5 为默认） */
    val beatCamera: Boolean = true,
    val beatCameraStrength: Float = 0.5f,
    val onSeek: (Float) -> Unit,
    val onCycleStage: () -> Unit,
    val onDismiss: () -> Unit
) {
    val line: LyricLine? get() = lines.getOrNull(index)
    val prev: LyricLine? get() = lineAt(-1)
    val next: LyricLine? get() = lineAt(1)
    val instrumental: Boolean get() = lines.isEmpty() || LyricParser.isInstrumental(lines)
    val progress: Float
        get() = if (durationMs > 0L) (adjustedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /**
     * 取上下文句。超出显示模式允许的行数时返回 null，
     * 各舞台的装饰性邻句会自动消失，不用逐个舞台改布局。
     */
    fun lineAt(offset: Int): LyricLine? {
        if (offset != 0 && kotlin.math.abs(offset) > contextLimit) return null
        return lines.getOrNull(index + offset)
    }

    fun seekToLine() {
        val l = line ?: return
        if (durationMs > 0L) onSeek((l.timeMs.toFloat() / durationMs).coerceIn(0f, 1f))
    }
}

/**
 * 舞台内容的上下留白。
 * 竖屏：顶部 chrome 浮在舞台上，需要较大的留白避开；底部不再有「本句/关闭」，
 *       留白收到与顶部呼吸位相近的一档，把高度还给歌词。
 * 横屏：StageScaffold 已统一给顶部 chrome 让位，内容再收一点呼吸位即可，
 *       否则在 360dp 高的屏幕上上下留白会把歌词压扁。
 */
internal fun Modifier.stageContentPadding(
    frame: LyricFrame,
    top: Dp,
    bottom: Dp,
    start: Dp = 24.dp,
    end: Dp = 24.dp
): Modifier = padding(
    start = start,
    end = end,
    top = if (frame.landscape) 10.dp else top,
    bottom = if (frame.landscape) 4.dp else minOf(bottom, 26.dp)
)

@Composable
internal fun AlternateLyricStage(
    stage: Int,
    lines: List<LyricLine>,
    loading: Boolean,
    positionMs: Long,
    /**
     * 实时播放位置（已含歌词时间偏移）。逐字扫光的帧钟读它，
     * 不再在 500ms 采样值上叠墙上时间。默认退回 [positionMs]。
     */
    livePosition: () -> Long = { positionMs },
    durationMs: Long,
    title: String,
    artist: String,
    colors: JunColors,
    onSeek: (Float) -> Unit,
    lyricAlign: Int,
    lyricFont: Int,
    lyricFontSize: Float,
    karaoke: Boolean,
    showTranslation: Boolean,
    showRomanization: Boolean,
    artworkKey: String = "",
    motion: LyricMotionStyle = LyricMotionStyle.BOUNCE,
    displayMode: LyricDisplayMode = LyricDisplayMode.DUAL,
    contextLimit: Int = 1,
    quality: LyricQuality = LyricQuality.BALANCED,
    beat: LyricBeatState? = null,
    particles: Boolean = true,
    bassGlow: Boolean = true,
    typography: LyricTypography = LyricTypography(),
    glowStyle: Int = 1,
    glowStrength: Float = 0.6f,
    chorusFx: Boolean = true,
    chorusStrength: Float = 0.7f,
    coverDepth: Boolean = false,
    tuning: List<Int> = listOf(50, 50, 50, 50),
    stageProfile: StageProfile = StageProfile.Default,
    longForm: Boolean = false,
    semanticWrap: Boolean = true,
    videoUri: String = "",
    videoAlpha: Float = 0.42f,
    playing: Boolean = false,
    beatCamera: Boolean = true,
    beatCameraStrength: Float = 0.5f,
    onCycleStage: () -> Unit,
    onDismiss: () -> Unit
) {
    // 6.6.9：当前行用**平滑时钟**判定，而不是 500ms 采样值。
    // 以前这里读的是 positionMs（每秒只更新一两次的快照），每句歌词最多要晚
    // 半秒才亮起来，用户的感觉就是"歌词总是慢半拍，快慢还不均匀"。
    val adjusted = runCatching { livePosition() }.getOrDefault(positionMs).coerceAtLeast(0L)
    val stageLandscape = rememberIsLandscape()
    val stageCtx = LocalContext.current
    val displayLines = lines
    val chorusFlags = remember(displayLines) { LyricChorus.detect(displayLines) }
    // 横屏里关闭歌词 = 回到竖屏，否则会停在横屏的主界面。
    val dismissStage: () -> Unit = {
        if (stageLandscape) stageCtx.findActivity()?.exitLandscapeMode()
        onDismiss()
    }
    val current = LyricRuntime.activeIndex(displayLines, adjusted)
    val lineEnd = LyricParser.lineEnd(displayLines, current, durationMs)
    // 预热：当前句和之后几句的 grapheme 时间线、CJK 布局单位先算好，
    // 切句那一帧不再分词、不再分配。
    LaunchedEffect(current, displayLines, durationMs, quality) {
        LyricRuntime.preheat(displayLines, current, quality.preheat, durationMs)
    }
    // 常驻预构建：Mineradio 的 resident build。打开歌词层时后台把整首铺完，
    // 之后无论跳到哪一句都只查缓存，不在播放线程上做 ICU 分词。
    LaunchedEffect(displayLines, durationMs) {
        withContext(Dispatchers.Default) {
            LyricRenderEngine.residentBuild(displayLines, durationMs)
        }
    }
    val frame = LyricFrame(
        stage = stage,
        lines = displayLines,
        loading = loading,
        index = current,
        adjustedMs = adjusted,
        live = livePosition,
        lineEndMs = lineEnd,
        durationMs = durationMs,
        title = title,
        artist = artist,
        colors = colors,
        family = fontFamilyForIndex(lyricFont),
        // 舞台专属字号在入口处一次性乘进去：舞台内部所有 fontSize/布局都自动跟随，
        // 不需要在 14 个舞台里分别改。
        fontSize = lyricFontSize * stageProfile.fontScale,
        align = when (lyricAlign) {
            0 -> TextAlign.Start
            2 -> TextAlign.End
            else -> TextAlign.Center
        },
        horizontal = when (lyricAlign) {
            0 -> Alignment.Start
            2 -> Alignment.End
            else -> Alignment.CenterHorizontally
        },
        karaoke = karaoke,
        showTranslation = showTranslation,
        showRomanization = showRomanization,
        artworkKey = artworkKey,
        landscape = stageLandscape,
        motion = motion,
        displayMode = displayMode,
        contextLimit = contextLimit,
        quality = quality,
        beat = beat,
        particles = particles,
        bassGlow = bassGlow,
        typography = typography,
        glowStyle = glowStyle,
        glowStrength = glowStrength,
        chorus = chorusFx && chorusFlags.getOrElse(current) { false },
        chorusEffect = chorusEffectFor(current),
        chorusStrength = chorusStrength,
        coverDepth = coverDepth,
        tuning = tuning,
        stageProfile = stageProfile,
        longForm = longForm,
        semanticWrap = semanticWrap,
        videoUri = videoUri,
        videoAlpha = videoAlpha,
        playing = playing,
        beatCamera = beatCamera,
        beatCameraStrength = beatCameraStrength,
        onSeek = onSeek,
        onCycleStage = onCycleStage,
        onDismiss = dismissStage
    )
    when (stage.coerceIn(1, LyricStageCatalog.count - 1)) {
        1 -> CadenzaStage(frame)
        2 -> PartitaStage(frame)
        3 -> FumeStage(frame)
        4 -> MonetStage(frame)
        5 -> CappellaStage(frame)
        6 -> TiltStage(frame)
        7 -> CladdaghStage(frame)
        8 -> DioramaStage(frame)
        9 -> PendoloStage(frame)
        10 -> SonnetStage(frame)
        11 -> TemperaStage(frame)
        12 -> LumiereStage(frame)
        else -> StillStage(frame)
    }
}

/**
 * 所有舞台共用的外壳：点击空白关闭、顶部曲目/样式、底部时间轴微调。
 * 背景与歌词内容由各风格自己提供，翻译轨、音译轨、纯音乐状态在这里统一处理。
 */
@Composable
internal fun StageScaffold(
    frame: LyricFrame,
    dark: Boolean,
    accent: Color,
    background: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val onStage = if (dark) Color.White else Color.Black
    val landscape = rememberIsLandscape()
    // 封面纵深交互：落指生成涟漪，拖动让封面随手指轻微位移。
    val ripple = rememberLyricRippleState()
    var coverShift by remember { mutableStateOf(Offset.Zero) }
    val depthOn by rememberUpdatedState(frame.coverDepth)
    // 横屏：歌词内容只让开顶部标题/模式胶囊；底部不再有「本句/关闭」，
    // 只留一点呼吸位，按钮永远压不到歌词上。
    val contentTop = if (landscape) 52.dp else 0.dp
    val contentBottom = if (landscape) 10.dp else 0.dp
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (!depthOn) continue
                        // 控件区的点按不抢：只处理落在舞台空白处的按压与拖动。
                        when (event.type) {
                            PointerEventType.Press -> ripple.add(change.position)
                            PointerEventType.Move -> {
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                coverShift = ((change.position - center) / w * 26f)
                                    .let { Offset(it.x.coerceIn(-26f, 26f), it.y.coerceIn(-26f, 26f)) }
                            }
                        }
                    }
                }
            }
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                frame.onDismiss()
            }
    ) {
        // 背景铺到状态栏/导航栏下面，内容与 chrome 再让出安全区
        background()
        // 封面纵深：重模糊封面做底，手指拖动产生视差，点击落点扩散涟漪。
        LyricCoverDepth(frame.artworkKey, frame.coverDepth, dark, shift = coverShift)
        // 本地视频背景：压在各舞台底色/封面纵深之上，歌词与装饰之下。
        if (frame.videoUri.isNotBlank()) {
            VideoBackdrop(
                uri = frame.videoUri,
                isPlaying = frame.playing,
                positionMs = frame.adjustedMs,
                alpha = frame.videoAlpha
            )
        }
        // 排版纹理：纸纹 / 颗粒 / 扫描线，压在背景上、文字下。
        LyricTextureOverlay(frame.typography.texture, dark)
        // 副歌氛围：只在副歌句出现，三种形态按行号固定分配。
        frame.beat?.let { b ->
            if (frame.chorus) {
                LyricChorusFx(
                    beat = b,
                    accent = accent,
                    dark = dark,
                    effect = frame.chorusEffect,
                    strength = frame.chorusStrength,
                    speed = 0.4f + frame.tuning.getOrElse(1) { 50 } / 100f * 1.2f
                )
            }
        }
        // 节拍层：低频光晕垫在粒子下面，粒子再垫在文字下面。
        // 两者都在绘制阶段读频谱，不产生重组；关掉开关时连 Canvas 都不组合。
        frame.beat?.let { b ->
            val boost = if (frame.chorus) 1f + frame.chorusStrength * 0.8f else 1f
            val glowBoost = (0.55f + frame.tuning.getOrElse(2) { 50 } / 100f * 0.9f) *
                frame.stageProfile.glowScale
            val lookDensity = when {
                frame.stage == 8 && frame.stageProfile.lookIndex == 1 -> 1.65f
                frame.stage == 8 && frame.stageProfile.lookIndex == 2 -> 0f
                else -> 1f
            }
            val density = (frame.quality.particles *
                (0.45f + frame.tuning.getOrElse(0) { 50 } / 100f * 1.1f) *
                lookDensity).toInt()
            if (frame.bassGlow) LyricBassHalo(b, accent, dark, boost = boost * glowBoost)
            if (frame.particles && density > 0) {
                LyricStarRiver(
                    b, accent, dark, density,
                    boost = boost,
                    speed = (0.4f + frame.tuning.getOrElse(1) { 50 } / 100f * 1.2f) *
                        frame.stageProfile.motionScale * (if (frame.longForm) 0.55f else 1f)
                )
            }
        }
        // 舞台专属「形态」的兜底装饰：每个舞台的 0..3 档在不同位置含义不同，
        // 这里负责那些纯装饰性的线、环、胶片颗粒；语义性更强的档位由舞台自己处理。
        StageLookOverlay(frame, accent, dark)
        StageEdgeScrim(dark)
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .displayCutoutPadding()
                .padding(top = contentTop, bottom = contentBottom)
                .graphicsLayer {
                    // 节拍电影镜头：默认很轻，副歌、镜头调参和舞台动势再放大。
                    // 设置里关闭后完全不动；幅度滑杆以 50% 为旧版默认手感。
                    if (!frame.beatCamera) {
                        scaleX = 1f; scaleY = 1f
                        translationX = 0f; translationY = 0f
                        return@graphicsLayer
                    }
                    val cam = (0.55f + frame.tuning.getOrElse(3) { 50 } / 100f * 0.9f) *
                        frame.stageProfile.motionScale *
                        (frame.beatCameraStrength.coerceIn(0f, 1f) * 2f)
                    val chorus = if (frame.chorus) 1.5f else 1f
                    val long = if (frame.longForm) 0.45f else 1f
                    val p = frame.beat?.pulse ?: 0f
                    val s = 1f + 0.014f * p * cam * chorus * long
                    scaleX = s
                    scaleY = s
                    translationX = sin((frame.adjustedMs / 90f)) * 1.1f * p * cam * long
                    translationY = -p * 1.6f * cam * long
                }
        ) {
            when {
                frame.loading -> StageLoading(onStage)
                frame.instrumental -> InstrumentalStage(frame.stage, onStage, accent, frame.title, frame.artist, frame.family)
                else -> content()
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .displayCutoutPadding()
        ) {
            StageChrome(frame, accent, onStage, dark, landscape)
        }
        // 涟漪画在最上层：不吃触摸，只负责把落点的圈画完。
        if (frame.coverDepth) LyricRippleCanvas(ripple, accent)
    }
}

/**
 * 舞台「形态」兜底装饰。
 *
 * 每个舞台的形态档位含义不同：云阶是引导线、回环是轴线、商籁是胶片后期。
 * 这里用同一套 Canvas 画出对应的线/环/颗粒/暗角，保证切档时一定有可见变化；
 * 各舞台还可以在自己的代码里对形态做更细的解释。
 */
@Composable
private fun StageLookOverlay(frame: LyricFrame, accent: Color, dark: Boolean) {
    val look = frame.stageProfile.lookIndex
    if (look == 0) return
    val ink = if (dark) Color.White else Color.Black
    val stage = frame.stage
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (frame.longForm) 0.55f else 0.9f }
    ) {
        val w = size.width
        val h = size.height
        val thin = 1.dp.toPx()
        when {
            // 云阶：引导线 / 中轴 / 网格
            stage == 2 && look == 1 -> {
                for (i in 1..2) {
                    val y = h * (0.25f + 0.25f * i)
                    drawLine(ink.copy(alpha = 0.10f), Offset(w * 0.10f, y), Offset(w * 0.90f, y), thin)
                }
            }
            stage == 2 && look == 2 -> {
                drawLine(ink.copy(alpha = 0.12f), Offset(w * 0.5f, h * 0.16f), Offset(w * 0.5f, h * 0.84f), thin)
            }
            stage == 2 && look == 3 -> {
                for (i in 1..4) {
                    val y = h * i / 5f
                    drawLine(ink.copy(alpha = 0.07f), Offset(0f, y), Offset(w, y), thin)
                }
                for (i in 1..3) {
                    val x = w * i / 4f
                    drawLine(ink.copy(alpha = 0.05f), Offset(x, 0f), Offset(x, h), thin)
                }
            }
            // 回环：中轴 / 椭圆环 / 双环
            stage == 7 && look == 1 -> {
                drawLine(ink.copy(alpha = 0.12f), Offset(w * 0.5f, h * 0.12f), Offset(w * 0.5f, h * 0.88f), thin)
            }
            stage == 7 && look == 2 -> {
                drawOval(
                    color = accent.copy(alpha = 0.16f),
                    topLeft = Offset(w * 0.12f, h * 0.20f),
                    size = Size(w * 0.76f, h * 0.60f),
                    style = Stroke(width = thin)
                )
            }
            stage == 7 && look == 3 -> {
                drawOval(
                    color = accent.copy(alpha = 0.18f),
                    topLeft = Offset(w * 0.08f, h * 0.14f),
                    size = Size(w * 0.84f, h * 0.72f),
                    style = Stroke(width = thin)
                )
                drawOval(
                    color = accent.copy(alpha = 0.10f),
                    topLeft = Offset(w * 0.20f, h * 0.26f),
                    size = Size(w * 0.60f, h * 0.48f),
                    style = Stroke(width = thin)
                )
            }
            // 商籁/浮名/静止：颗粒 / 暗角 / 颗粒+暗角
            (stage == 10 || stage == 3 || stage == 13) && look == 1 -> drawStageGrain(ink, 150)
            (stage == 10 || stage == 3 || stage == 13) && look == 2 -> drawStageVignette()
            (stage == 10 || stage == 3 || stage == 13) && look == 3 -> {
                drawStageGrain(ink, 230)
                drawStageVignette()
            }
            // 时计：中轴 / 表盘环 / 极简角标
            stage == 9 && look == 1 -> {
                drawLine(ink.copy(alpha = 0.10f), Offset(w * 0.5f, h * 0.10f), Offset(w * 0.5f, h * 0.90f), thin)
            }
            stage == 9 && look == 2 -> {
                drawCircle(
                    color = accent.copy(alpha = 0.18f),
                    radius = minOf(w, h) * 0.30f,
                    center = Offset(w * 0.5f, h * 0.5f),
                    style = Stroke(width = thin)
                )
            }
            stage == 9 && look == 3 -> {
                val pad = minOf(w, h) * 0.06f
                drawLine(ink.copy(alpha = 0.10f), Offset(pad, pad), Offset(pad * 1.8f, pad), thin)
                drawLine(ink.copy(alpha = 0.10f), Offset(w - pad, h - pad), Offset(w - pad * 1.8f, h - pad), thin)
            }
            // 其余舞台：形态 1 细线、2 光环、3 胶片颗粒
            look == 1 -> {
                drawLine(ink.copy(alpha = 0.08f), Offset(w * 0.10f, h * 0.5f), Offset(w * 0.90f, h * 0.5f), thin)
            }
            look == 2 -> {
                drawCircle(
                    color = accent.copy(alpha = 0.14f),
                    radius = minOf(w, h) * 0.34f,
                    center = Offset(w * 0.5f, h * 0.5f),
                    style = Stroke(width = thin)
                )
            }
            else -> {
                drawStageGrain(ink, 160)
                drawStageVignette()
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStageGrain(
    ink: Color,
    count: Int
) {
    val stepX = size.width / 1.7f
    for (i in 0 until count) {
        val x = ((i * 7919) % 997) / 997f * size.width
        val y = ((i * 104729 + (size.height * 13).toInt()) % 991) / 991f * size.height
        val r = if (i % 7 == 0) 1.1f else 0.6f
        drawCircle(ink.copy(alpha = 0.045f), radius = r, center = Offset(x, y))
        if (i % 11 == 0) {
            drawCircle(ink.copy(alpha = 0.025f), radius = r * 2.2f, center = Offset(x + stepX, y))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStageVignette() {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)),
            center = Offset(size.width * 0.5f, size.height * 0.5f),
            radius = maxOf(size.width, size.height) * 0.72f
        )
    )
}

@Composable
private fun StageLoading(onStage: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("正在加载歌词…", color = onStage.copy(alpha = 0.6f), fontSize = 14.sp)
    }
}

@Composable
private fun BoxScope.StageEdgeScrim(dark: Boolean) {
    val top = if (dark) Color.Black else Color.White
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(112.dp)
            .background(
                Brush.verticalGradient(
                    listOf(top.copy(alpha = if (dark) 0.62f else 0.52f), Color.Transparent)
                )
            )
    )
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(116.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, top.copy(alpha = if (dark) 0.62f else 0.52f))
                )
            )
    )
}

@Composable
private fun BoxScope.StageChrome(
    frame: LyricFrame,
    accent: Color,
    onStage: Color,
    dark: Boolean,
    landscape: Boolean
) {
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(
                horizontal = if (landscape) 30.dp else 22.dp,
                vertical = if (landscape) 9.dp else 16.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                frame.title,
                color = onStage.copy(alpha = 0.92f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (frame.artist.isNotBlank()) {
                Text(
                    frame.artist,
                    color = accent.copy(alpha = 0.86f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(onStage.copy(alpha = 0.10f))
                .clickable { frame.onCycleStage() }
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(
                "${LyricStageCatalog.name(frame.stage)} · ${frame.stage + 1}/${LyricStageCatalog.count}",
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(8.dp))
        StageLandscapePill(accent = accent, onStage = onStage)
    }
}

/**
 * 歌词舞台的横屏开关：竖屏显示「横屏沉浸」，横屏显示「竖屏」。
 * 和左上标题、右上模式胶囊同一行，位于歌词内容之外，不遮任何一句词。
 */
@Composable
internal fun StageLandscapePill(accent: Color, onStage: Color) {
    val landscape = rememberIsLandscape()
    val ctx = LocalContext.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(onStage.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.34f), RoundedCornerShape(50))
            .junPressable(
                onClick = {
                    Haptics.tap()
                    if (landscape) ctx.findActivity()?.exitLandscapeMode()
                    else ctx.findActivity()?.enterLandscapeMode()
                }
            )
            .semantics {
                contentDescription = if (landscape) "退出横屏沉浸" else "进入横屏沉浸"
            }
            .padding(start = 9.dp, end = 11.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DeviceGlyph(
            tint = accent,
            portrait = landscape,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            if (landscape) "竖屏" else "横屏沉浸",
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.2.sp
        )
    }
}

/** 当前句：有逐字时间时走扫光，没有时按行时长做伪逐字；可被各风格覆写字号与阴影。 */
@Composable
internal fun StageCurrentLine(
    frame: LyricFrame,
    sungColor: Color,
    unsungColor: Color,
    fontSize: Float,
    textAlign: TextAlign = frame.align,
    fontFamily: FontFamily = frame.family,
    fontWeight: FontWeight = FontWeight.Bold,
    lineHeight: TextUnit = TextUnit.Unspecified,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    shadow: Shadow? = null,
    text: String? = null,
    modifier: Modifier = Modifier
) {
    val line = frame.line ?: return
    val value = text ?: line.text
    if (value.isBlank()) return
    val weight = frame.typography.weight(fontWeight)
    val spacing = if (letterSpacing == TextUnit.Unspecified) {
        letterSpacingFor(fontSize, frame.typography.letterSpacingEm)
    } else letterSpacing
    val lineHeightValue = if (lineHeight == TextUnit.Unspecified) {
        (fontSize * frame.typography.lineSpacing.coerceIn(0.9f, 2.2f)).sp
    } else lineHeight
    val outline = frame.typography.outline.coerceIn(0f, 1f)
    val lineShadow = if (outline > 0.01f) {
        androidx.compose.ui.graphics.Shadow(
            color = sungColor.copy(alpha = 0.55f),
            offset = Offset.Zero,
            blurRadius = fontSize * (0.12f + outline * 0.55f)
        )
    } else shadow
    // 渲染提示：短句不做长过渡、逐字瞬时铺满；常规句才走完整进场。
    val hints = remember(line.timeMs, frame.lineEndMs) {
        LyricRenderEngine.hintsFor(line, frame.lineEndMs)
    }
    var entered by remember(line.timeMs) { mutableStateOf(hints.enterMs == 0L) }
    LaunchedEffect(line.timeMs) {
        if (!entered) entered = true
    }
    val enterAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = hints.enterMs.toInt().coerceAtLeast(0)),
        label = "lineEnter"
    )
    val motion = Modifier.graphicsLayer {
        alpha = enterAlpha
        translationY = (1f - enterAlpha) * 6.dp.toPx()
        val s = 0.985f + 0.015f * enterAlpha
        scaleX = s
        scaleY = s
    }
    if (frame.karaoke) {
        // 词块太多（超长句）或选经典扫光时走渐变扫光；其余风格走逐词弹跳。
        val unitCount = remember(line, frame.lineEndMs) {
            LyricRenderEngine.layoutUnits(line, frame.lineEndMs).size
        }
        val maxUnits = when (frame.quality) {
            LyricQuality.FINE -> 56
            LyricQuality.BALANCED -> 42
            else -> 28
        }
        val useFlow = frame.motion != LyricMotionStyle.SWEEP && unitCount in 1..maxUnits
        if (useFlow) {
            val clock = rememberLyricFrameClock(frame.live, line)
            LyricFlowLine(
                line = line.copy(text = value),
                clock = clock,
                lineEndMs = frame.lineEndMs,
                sungColor = sungColor,
                unsungColor = unsungColor,
                textAlign = textAlign,
                fontFamily = fontFamily,
                fontSize = fontSize,
                fontWeight = weight,
                letterSpacing = spacing,
                shadow = lineShadow,
                motion = frame.motion,
                beat = frame.beat,
                modifier = modifier.then(motion).lyricBeatPulse(frame.beat, frame.motion.pulseAmplitude()),
                glowStyle = frame.glowStyle,
                glowStrength = frame.glowStrength
            )
        } else {
            KaraokeLine(
                line = line.copy(text = value),
                positionMs = frame.adjustedMs,
                live = frame.live,
                lineEndMs = frame.lineEndMs,
                sungColor = sungColor,
                unsungColor = unsungColor,
                textAlign = textAlign,
                fontFamily = fontFamily,
                fontSize = fontSize,
                fontWeight = weight,
                lineHeight = lineHeightValue,
                letterSpacing = spacing,
                shadow = lineShadow,
                hints = hints,
                glowColor = sungColor,
                lineBreak = if (frame.semanticWrap) {
                    androidx.compose.ui.text.style.LineBreak.Paragraph
                } else {
                    androidx.compose.ui.text.style.LineBreak.Unspecified
                },
                modifier = modifier.then(motion).lyricBeatPulse(frame.beat, frame.motion.pulseAmplitude())
            )
        }
    } else {
        // 关键词着色：从整首歌的重复短语里取，按区间上色，不写死词表。
        val palette = remember(frame.lines, frame.colors.primary, frame.colors.secondary) {
            LyricKeywordStyler.buildPalette(frame.lines, frame.colors.primary, frame.colors.secondary)
        }
        val styled = remember(value, palette) {
            LyricKeywordStyler.annotate(value, palette)
        }
        Text(
            styled,
            color = sungColor,
            fontSize = fontSize.sp,
            fontFamily = fontFamily,
            fontWeight = weight,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Normal,
            textAlign = textAlign,
            lineHeight = lineHeightValue,
            letterSpacing = spacing,
            style = androidx.compose.ui.text.TextStyle(
                shadow = lineShadow,
                lineBreak = if (frame.semanticWrap) {
                    androidx.compose.ui.text.style.LineBreak.Paragraph
                } else {
                    androidx.compose.ui.text.style.LineBreak.Unspecified
                }
            ),
            modifier = modifier.then(motion).lyricBeatPulse(frame.beat, frame.motion.pulseAmplitude())
        )
    }
}

/** 翻译 / 音译：位置、字号、颜色由风格决定，内容展示开关由设置统一控制。 */
@Composable
internal fun StageSubLines(
    frame: LyricFrame,
    color: Color,
    accent: Color,
    textAlign: TextAlign = frame.align,
    translationSize: Float = LyricType.translation(frame.fontSize),
    romanizationSize: Float = LyricType.romanization(frame.fontSize),
    modifier: Modifier = Modifier
) {
    val line = frame.line ?: return
    // 译文/音译是两条独立轨道，各自过滤时间标记和空行，互不挤占。
    val translation = LyricDisplayFilter.track(line.translation)?.takeIf { frame.showTranslation }
    val romanization = LyricDisplayFilter.track(line.romanization)?.takeIf { frame.showRomanization }
    if (translation == null && romanization == null) return
    Column(modifier.fillMaxWidth()) {
        if (translation != null) {
            Text(
                translation,
                color = color.copy(alpha = 0.78f),
                fontSize = translationSize.sp,
                fontFamily = frame.family,
                fontWeight = FontWeight.Medium,
                textAlign = textAlign,
                lineHeight = (translationSize * 1.35f).sp,
                style = androidx.compose.ui.text.TextStyle(
                    lineBreak = if (frame.semanticWrap) {
                        androidx.compose.ui.text.style.LineBreak.Paragraph
                    } else {
                        androidx.compose.ui.text.style.LineBreak.Unspecified
                    }
                )
            )
        }
        if (romanization != null) {
            if (translation != null) Spacer(Modifier.height(4.dp))
            Text(
                romanization,
                color = accent.copy(alpha = 0.62f),
                fontSize = romanizationSize.sp,
                fontFamily = frame.family,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                textAlign = textAlign,
                lineHeight = (romanizationSize * 1.35f).sp,
                style = androidx.compose.ui.text.TextStyle(
                    lineBreak = if (frame.semanticWrap) {
                        androidx.compose.ui.text.style.LineBreak.Paragraph
                    } else {
                        androidx.compose.ui.text.style.LineBreak.Unspecified
                    }
                )
            )
        }
    }
}

@Composable
internal fun rememberStageArtwork(key: String): ImageBitmap? {
    if (key.isBlank()) return null
    val coverVersion = CustomCoverStore.version
    var image by remember(key, coverVersion) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key, coverVersion) {
        image = ArtworkLoader.load(key)?.asImageBitmap()
    }
    return image
}

/** 专辑封面面板：有图铺图，无图用主题渐变 + 首字，保证莫奈/镜台/时计不出现空洞。 */
@Composable
internal fun StageArtwork(
    image: ImageBitmap?,
    title: String,
    colors: JunColors,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    overlayAlpha: Float = 0f
) {
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        colors.primary.copy(alpha = 0.82f),
                        colors.secondary.copy(alpha = 0.64f),
                        Color(0xFF1A1620)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                title.trim().take(1).ifBlank { "♪" },
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 42.sp,
                fontWeight = FontWeight.Black
            )
        }
        if (overlayAlpha > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = overlayAlpha)))
        }
    }
}

/** 共用的时钟/刻度绘制。 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTicks(
    color: Color,
    center: Offset,
    radius: Float,
    count: Int,
    longEvery: Int,
    longLength: Float,
    shortLength: Float,
    strokeWidth: Float
) {
    for (i in 0 until count) {
        val angle = (i * 360f / count) * (Math.PI / 180f).toFloat()
        val sin = kotlin.math.sin(angle)
        val cos = kotlin.math.cos(angle)
        val isLong = longEvery > 0 && i % longEvery == 0
        val len = if (isLong) longLength else shortLength
        val start = Offset(center.x + cos * (radius - len), center.y + sin * (radius - len))
        val end = Offset(center.x + cos * radius, center.y + sin * radius)
        drawLine(
            color = if (isLong) color else color.copy(alpha = color.alpha * 0.55f),
            start = start,
            end = end,
            strokeWidth = if (isLong) strokeWidth else strokeWidth * 0.6f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun InstrumentalStage(
    stage: Int,
    onStage: Color,
    accent: Color,
    title: String,
    artist: String,
    family: FontFamily
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val trans = rememberInfiniteTransition(label = "inst")
            val pulse by trans.animateFloat(
                initialValue = 0.82f,
                targetValue = 1.08f,
                animationSpec = infiniteRepeatable(
                    tween(2600, easing = LinearEasing),
                    RepeatMode.Reverse
                ),
                label = "instPulse"
            )
            Canvas(Modifier.width(96.dp).height(96.dp)) {
                drawCircle(accent.copy(alpha = 0.10f), radius = size.minDimension * 0.5f * pulse)
                drawCircle(
                    accent.copy(alpha = 0.35f),
                    radius = size.minDimension * 0.34f,
                    style = Stroke(width = 2f)
                )
                drawCircle(accent, radius = 4f, center = center)
            }
            Spacer(Modifier.height(22.dp))
            // 6.6：区分「纯音乐」和「音源与歌词版本对不上」。
            // 版本指纹匹配失败时明确说明，不再伪装成纯音乐、也不拿错版时间轴硬套。
            val versionMiss = com.jun.musiccloud.data.LyricMatchDiagnostics.lastMiss
            Text(
                if (versionMiss) "未找到匹配歌词"
                else if (stage == 13) "纯音乐" else "纯音乐 · 请欣赏",
                color = onStage.copy(alpha = 0.9f),
                fontSize = if (versionMiss) 20.sp else 22.sp,
                fontFamily = family,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (versionMiss) "音源与歌词版本不一致，已停止自动套用。换个音源或稍后重试"
                else "$title${if (artist.isBlank()) "" else " · $artist"}",
                color = onStage.copy(alpha = 0.45f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
