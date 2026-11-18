package com.jun.musiccloud.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.audio.SpectrumAnalyzer
import com.jun.musiccloud.data.LyricLine
import com.jun.musiccloud.data.LyricRenderEngine
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

// =====================================================================================
// 歌词动效总控：节拍引擎、运动风格、显示模式、渲染品质、星河粒子与低频光晕。
//
// 设计原则和 Mineradio 的舞台歌词一致：
//  - 节拍/能量只从音频线程的 SpectrumAnalyzer 取，绝不在 UI 线程做 FFT；
//  - 逐帧状态写进普通 @Stable 对象，绘制阶段（graphicsLayer / Canvas draw）才读取，
//    避免每秒 60 次全量重组；
//  - 逐词弹跳不靠字体大小动画（会触发每帧重新排版），而是两层同尺寸文字叠放，
//    下层未唱、上层已唱，用 graphicsLayer 的 alpha/scale 做动画，排版只算一次。
// =====================================================================================

/** 运动风格：对应 Mineradio 的 glass / smooth / float / quick / shine / glitch，另保留经典扫光。 */
internal enum class LyricMotionStyle(val label: String, val hint: String) {
    SWEEP("经典扫光", "整行一道柔光带匀速扫过，最稳，性能最好"),
    BOUNCE("弹跳", "每个词点亮时向上弹一下再落回，跟咬字走"),
    GLASS("玻璃", "词块轻微放大，已唱部分带冷白高光"),
    FLOAT("漂浮", "词块上下缓浮，像悬在水面"),
    QUICK("轻快", "更短更干脆的弹入，适合快歌"),
    SHINE("流光", "点亮瞬间高光更强，余辉更长"),
    GLITCH("故障", "强拍时整行轻微错位与抖动");

    companion object {
        fun of(index: Int): LyricMotionStyle = entries.getOrElse(index) { BOUNCE }
    }
}

/** 显示模式：控制舞台能拿到几句上下文。对应 Mineradio 的 single/dual/triple/cinema/custom。 */
internal enum class LyricDisplayMode(val label: String, val context: Int) {
    SINGLE("单行", 0),
    DUAL("双行", 1),
    TRIPLE("三行", 2),
    CINEMA("影院", 4),
    CUSTOM("自定义", 2);

    companion object {
        fun of(index: Int): LyricDisplayMode = entries.getOrElse(index) { DUAL }

        /** 实际上下文行数：自定义时取用户滑条，其余取预设。 */
        fun limit(index: Int, custom: Int): Int =
            if (of(index) == CUSTOM) custom.coerceIn(0, 6) else of(index).context
    }
}

/** 渲染品质：同时约束粒子数、预热深度、逐词/扫光的选择。 */
internal enum class LyricQuality(val label: String, val particles: Int, val preheat: Int) {
    AUTO("自动", 64, 3),
    FLUID("流畅", 28, 2),
    BALANCED("均衡", 64, 3),
    FINE("精致", 104, 4);

    companion object {
        fun of(index: Int): LyricQuality = entries.getOrElse(index) { AUTO }
    }
}

/**
 * 歌词层画质判定：只在进入歌词层时读一次，不参与重组。
 *
 * 只看核数会误伤老机型——华为 nova 6 是 8 核 Kirin 990，Android 10，
 * 按 cores>=8 会被分到「精致」（104 粒子 x4 层），满屏 Canvas 逐帧重绘直接拖到掉帧。
 * 这里统一以 DevicePerformance 的分档为准，核数只作为额外的兜底下限。
 */
@Composable
internal fun rememberLyricQuality(index: Int): LyricQuality {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val auto = remember {
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager
        val lowRam = am?.isLowRamDevice == true
        val cores = Runtime.getRuntime().availableProcessors()
        val perf = com.jun.musiccloud.ui.theme.DevicePerformance
        when {
            lowRam || cores <= 4 || perf.isLowEnd -> LyricQuality.FLUID
            // 老系统 / 已经被实测掉帧降级过 / 命中老机安全档：按最省的档位跑
            perf.legacy || perf.autoDegraded || perf.safeMode -> LyricQuality.FLUID
            perf.isFlagship -> LyricQuality.FINE
            else -> LyricQuality.BALANCED
        }
    }
    return if (LyricQuality.of(index) == LyricQuality.AUTO) auto else LyricQuality.of(index)
}

// =====================================================================================
// 节拍引擎
// =====================================================================================

/**
 * 歌词层共享的节拍状态。
 *
 * 只暴露四个标量，全部是 Compose State：
 *  - [pulse]  0..1 的打击包络，attack 立刻跟、release 约 280ms 落下；
 *  - [bass]   低频能量 EMA，驱动光晕大小；
 *  - [energy] 全频 RMS，驱动整体亮度；
 *  - [hit]    节拍序号，每检测到一次强拍自增（给一次性特效做 key）。
 */
@Stable
internal class LyricBeatState {
    var pulse by mutableFloatStateOf(0f)
        internal set
    var bass by mutableFloatStateOf(0f)
        internal set
    var energy by mutableFloatStateOf(0f)
        internal set
    var hit by mutableIntStateOf(0)
        internal set
    var quiet by mutableStateOf(true)
        internal set

    internal fun reset() {
        pulse = 0f
        bass = 0f
        energy = 0f
        quiet = true
    }
}

/**
 * 逐帧采样 [SpectrumAnalyzer]，把鼓点变成视觉包络。
 *
 * 双通道检测：
 *  - 慢/快 EMA 差（rise）：稳定跟住底鼓的绝对能量抬升；
 *  - 三频段频谱通量（flux）：抓住军鼓/踩镲的瞬态，安静段的小抖动不会误触。
 * 两者取大，再做 190ms 不应期，避免一个鼓点连跳三次。
 */
@Composable
internal fun rememberLyricBeat(enabled: Boolean, sensitivity: Float = 1f): LyricBeatState {
    val state = remember { LyricBeatState() }
    val sens = rememberUpdatedState(sensitivity)
    DisposableEffect(enabled) {
        // 歌词动效开着时也需要分析管线；关掉时只撤销自己这一票，不影响频谱/低音震动。
        SpectrumAnalyzer.lyricFxEnabled = enabled
        onDispose { if (enabled) SpectrumAnalyzer.lyricFxEnabled = false }
    }
    LaunchedEffect(enabled) {
        if (!enabled) {
            state.reset()
            return@LaunchedEffect
        }
        var lastNanos = 0L
        var env = 0f
        var fast = 0f
        var slow = 0f
        var bassS = 0f
        var energyS = 0f
        var fluxAvg = 0.004f
        var lastHitMs = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 1f
                else ((now - lastNanos) / 16_666_667f).coerceIn(0.2f, 3f)
                lastNanos = now

                val fresh = SpectrumAnalyzer.isFresh()
                val beat = if (fresh) SpectrumAnalyzer.beatLevel else 0f
                val bass = if (fresh) SpectrumAnalyzer.bassLevel else 0f
                val rms = if (fresh) SpectrumAnalyzer.rms else 0f
                val flux = if (fresh) {
                    maxOf(
                        SpectrumAnalyzer.lowFlux,
                        SpectrumAnalyzer.midFlux * 0.70f,
                        SpectrumAnalyzer.highFlux * 0.45f
                    )
                } else 0f

                fast += (beat - fast) * (0.55f * dt).coerceAtMost(1f)
                slow += (beat - slow) * (0.055f * dt).coerceAtMost(1f)
                fluxAvg += (flux - fluxAvg) * (0.08f * dt).coerceAtMost(1f)

                val rise = (fast - slow).coerceAtLeast(0f) * 7f
                val fluxRise = (flux - fluxAvg * 1.25f).coerceAtLeast(0f) * 28f
                val target = (maxOf(rise, fluxRise) * sens.value).coerceIn(0f, 1f)

                // attack 立刻跟，release 线性落：鼓点有"打下去"的力度，不是匀速呼吸。
                env = if (target >= env) target else (env - 0.062f * dt).coerceAtLeast(target)
                bassS += (bass - bassS) * (0.22f * dt).coerceAtMost(1f)
                energyS += (rms - energyS) * (0.18f * dt).coerceAtMost(1f)

                val hit = target > 0.42f && (now / 1_000_000L - lastHitMs) > 190L
                if (hit) lastHitMs = now / 1_000_000L

                state.pulse = env
                state.bass = bassS
                state.energy = energyS
                state.quiet = !fresh
                if (hit) state.hit++
            }
        }
    }
    return state
}

/** 给整块歌词内容加一点跟随节拍的推镜：幅度很小，只在视觉上"呼吸"。 */
internal fun Modifier.lyricBeatPulse(beat: LyricBeatState?, amplitude: Float): Modifier =
    if (beat == null || amplitude <= 0f) this else graphicsLayer {
        val p = beat.pulse
        val s = 1f + amplitude * p
        scaleX = s
        scaleY = s
    }

/** 不同运动风格的整行推镜幅度：快歌更明显，玻璃/漂浮更克制。 */
internal fun LyricMotionStyle.pulseAmplitude(): Float = when (this) {
    LyricMotionStyle.SWEEP -> 0.008f
    LyricMotionStyle.BOUNCE -> 0.016f
    LyricMotionStyle.GLASS -> 0.007f
    LyricMotionStyle.FLOAT -> 0.006f
    LyricMotionStyle.QUICK -> 0.018f
    LyricMotionStyle.SHINE -> 0.010f
    LyricMotionStyle.GLITCH -> 0.012f
}

// =====================================================================================
// 星河粒子 / 低频光晕
// =====================================================================================

/**
 * 星河粒子：Mineradio 420 粒的三档精简版（28 / 64 / 104）。
 *
 * 粒子位置存在普通 FloatArray 里，逐帧更新；只有一个 tick 计数器是 State，
 * 用来触发 Canvas 重绘，不产生重组。低频越强，整体流动越快、粒子越亮。
 */
@Composable
internal fun LyricStarRiver(
    beat: LyricBeatState,
    accent: Color,
    dark: Boolean,
    count: Int,
    modifier: Modifier = Modifier,
    boost: Float = 1f,
    speed: Float = 1f
) {
    if (count <= 0) return
    val field = remember(count) { StarField(count) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(count) {
        var lastNanos = 0L
        // 老机型约 30fps：dt 按真实帧间隔算，跳帧只是步长变大，星星速度不变
        val frameGate = LongArray(1)
        while (true) {
            withFrameNanos { now ->
                if (!JunMotion.frameDue(now, frameGate)) return@withFrameNanos
                val dt = if (lastNanos == 0L) 1f
                else ((now - lastNanos) / 16_666_667f).coerceIn(0.2f, 3f)
                lastNanos = now
                field.step(dt, beat.pulse, beat.bass, beat.quiet, speed)
                tick++
            }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION") tick
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val base = if (dark) Color.White else accent
        val boost = (0.72f + beat.pulse * 0.55f) * boost
        for (i in 0 until field.count) {
            val z = field.z[i]
            val a = ((0.07f + z * 0.30f) * boost).coerceIn(0f, 0.62f)
            drawCircle(
                color = base.copy(alpha = a),
                radius = field.size[i] * z,
                center = Offset(field.x[i] * w, field.y[i] * h)
            )
        }
    }
}

private class StarField(val count: Int) {
    val x = FloatArray(count)
    val y = FloatArray(count)
    val z = FloatArray(count)
    val size = FloatArray(count)
    val speed = FloatArray(count)
    val sway = FloatArray(count)
    private val rnd = Random(0x4A554E)

    init {
        for (i in 0 until count) {
            x[i] = rnd.nextFloat()
            y[i] = rnd.nextFloat()
            z[i] = 0.25f + rnd.nextFloat() * 0.75f
            size[i] = 0.6f + rnd.nextFloat() * 1.9f
            speed[i] = 0.0010f + rnd.nextFloat() * 0.0028f
            sway[i] = rnd.nextFloat() * (2f * PI.toFloat())
        }
    }

    fun step(dt: Float, pulse: Float, bass: Float, quiet: Boolean, speedScale: Float = 1f) {
        // 暂停/无音频时几乎停住，只留极慢的漂移：既不死板，也不空转烧电。
        val flow = (if (quiet) 0.10f else 0.55f + bass * 2.4f + pulse * 1.2f) * speedScale
        for (i in 0 until count) {
            y[i] -= speed[i] * flow * dt
            val phase = sway[i] + y[i] * 6.28f
            x[i] += sin(phase) * 0.00022f * dt
            if (y[i] < -0.06f) {
                y[i] = 1.06f
                x[i] = rnd.nextFloat()
                z[i] = 0.25f + rnd.nextFloat() * 0.75f
            }
            if (x[i] < -0.05f) x[i] = 1.05f
            if (x[i] > 1.05f) x[i] = -0.05f
        }
    }
}

/** 低频光晕：一团跟着底鼓呼吸的径向光，垫在歌词后面，不抢字。 */
@Composable
internal fun LyricBassHalo(
    beat: LyricBeatState,
    accent: Color,
    dark: Boolean,
    modifier: Modifier = Modifier,
    boost: Float = 1f
) {
    Canvas(modifier.fillMaxSize()) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val b = beat.bass
        val p = beat.pulse
        val strength = (0.045f + b * 0.15f + p * 0.05f) *
            (if (dark) 1f else 0.72f) * boost.coerceIn(0.2f, 3f)
        if (strength <= 0.001f) return@Canvas
        val center = Offset(size.width * 0.5f, size.height * 0.54f)
        val radius = size.minDimension * (0.46f + b * 0.16f + p * 0.05f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = strength.coerceIn(0f, 0.30f)),
                    accent.copy(alpha = strength * 0.35f),
                    Color.Transparent
                ),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }
}

// =====================================================================================
// 逐词弹跳
// =====================================================================================

/** 一个显示词块：CJK 单字或语义词、英文单词，标点已粘到前一个块。 */
private data class LyricWordUnit(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

/**
 * 把一句歌词切成"显示词块"。
 *
 * 有词级时间时直接用引擎的语义布局单位（保留真实咬字时间）；
 * 没有词级时间时按 CJK 单字 / 拉丁词块切分，再按字数权重把自然演唱时长铺开。
 */
private object LyricWordUnits {

    fun of(line: LyricLine, lineEndMs: Long): List<LyricWordUnit> {
        if (line.words.isNotEmpty()) {
            val units = LyricRenderEngine.layoutUnits(line, lineEndMs)
            if (units.isNotEmpty()) {
                return units.map { LyricWordUnit(it.text, it.startMs, it.endMs) }
            }
        }
        val chunks = chunk(line.text)
        if (chunks.isEmpty()) return emptyList()
        val safeEnd = if (lineEndMs > line.timeMs) lineEndMs else line.timeMs + 2600L
        val weight = chunks.sumOf { it.length.coerceAtLeast(1) }
        val natural = (weight * 240L).coerceAtLeast(700L)
        val end = minOf(safeEnd, line.timeMs + natural).coerceAtLeast(line.timeMs + chunks.size * 70L)
        var cursor = line.timeMs
        val span = (end - line.timeMs).coerceAtLeast(1L)
        val out = ArrayList<LyricWordUnit>(chunks.size)
        for ((i, text) in chunks.withIndex()) {
            val next = line.timeMs + span * (i + 1) / chunks.size
            out.add(LyricWordUnit(text, cursor, maxOf(next, cursor + 1L)))
            cursor = next
        }
        return out
    }

    /** CJK 逐字、拉丁按词、标点并入前块；空格丢弃，由排版间距补回。 */
    private fun chunk(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                isCjk(c) -> {
                    out.add(c.toString())
                    i++
                }
                c.isLetterOrDigit() || c == '\'' -> {
                    var j = i + 1
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '\'')) j++
                    out.add(text.substring(i, j))
                    i = j
                }
                else -> {
                    if (out.isEmpty()) out.add(c.toString())
                    else out[out.lastIndex] = out.last() + c
                    i++
                }
            }
        }
        return out
    }

    private fun isCjk(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || c.code in 0x3040..0x30FF || c.code in 0xAC00..0xD7AF ||
            c.code in 0x3400..0x4DBF
}

/**
 * 逐词点亮的当前行。
 *
 * 性能关键：字号、字距、阴影这些会影响排版的属性全部只算一次，
 * 每帧变化的只有 graphicsLayer 里的 alpha / scale / translation（绘制阶段），
 * 所以 20 个词也不会每帧重新排版。
 *
 * @param clock 外部传入的帧钟（与 KaraokeLine 共用同一套外推逻辑）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LyricFlowLine(
    line: LyricLine,
    clock: LyricFrameClock,
    lineEndMs: Long,
    sungColor: Color,
    unsungColor: Color,
    textAlign: TextAlign,
    fontFamily: FontFamily,
    fontSize: Float,
    fontWeight: FontWeight,
    letterSpacing: TextUnit,
    shadow: Shadow?,
    motion: LyricMotionStyle,
    beat: LyricBeatState?,
    modifier: Modifier = Modifier,
    glowStyle: Int = 1,
    glowStrength: Float = 0.6f
) {
    val units = remember(line, lineEndMs) { LyricWordUnits.of(line, lineEndMs) }
    if (units.isEmpty()) return

    val litColor = lerp(sungColor, Color.White, if (motion == LyricMotionStyle.SHINE) 0.34f else 0.18f)
    val gap: Dp = remember(line.text, fontSize) {
        if (line.text.any { it.isWhitespace() }) (fontSize * 0.24f).dp else 0.dp
    }
    val baseStyle = TextStyle(
        fontFamily = fontFamily,
        fontSize = fontSize.sp,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        shadow = shadow
    )
    val hAlign = when (textAlign) {
        TextAlign.Start -> Alignment.Start
        TextAlign.End -> Alignment.End
        else -> Alignment.CenterHorizontally
    }
    val hArrangement: Arrangement.Horizontal = if (gap > 0.dp) {
        Arrangement.spacedBy(gap, hAlign)
    } else {
        when (textAlign) {
            TextAlign.Start -> Arrangement.Start
            TextAlign.End -> Arrangement.End
            else -> Arrangement.Center
        }
    }
    val amp = when (motion) {
        LyricMotionStyle.BOUNCE -> 0.15f
        LyricMotionStyle.QUICK -> 0.11f
        LyricMotionStyle.GLASS -> 0.055f
        LyricMotionStyle.FLOAT -> 0.035f
        LyricMotionStyle.SHINE -> 0.06f
        LyricMotionStyle.GLITCH -> 0.03f
        LyricMotionStyle.SWEEP -> 0f
    }
    val popMs = when (motion) {
        LyricMotionStyle.BOUNCE -> 380L
        LyricMotionStyle.QUICK -> 220L
        LyricMotionStyle.GLASS -> 300L
        LyricMotionStyle.FLOAT -> 700L
        LyricMotionStyle.SHINE -> 420L
        LyricMotionStyle.GLITCH -> 240L
        LyricMotionStyle.SWEEP -> 1L
    }
    val fadeMs = if (motion == LyricMotionStyle.QUICK) 140L else 210L
    val glowRadius = fontSize * (0.30f + glowStrength.coerceIn(0f, 1f) * 1.15f)
    val glowOn = glowStyle != 0 && glowStrength > 0.01f

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = hArrangement,
        verticalArrangement = Arrangement.spacedBy((fontSize * 0.24f).dp, Alignment.CenterVertically)
    ) {
        units.forEachIndexed { index, unit ->
            val unitSpan = (unit.endMs - unit.startMs).coerceAtLeast(1L)
            Box(
                modifier = Modifier.graphicsLayer {
                    val t = clock.estMs
                    val age = t - unit.startMs
                    val pop = if (age >= 0L && age < popMs) {
                        val u = age.toFloat() / popMs
                        val rise = (u / 0.18f).coerceIn(0f, 1f)
                        val decay = exp(-3.0f * (u - 0.18f).coerceAtLeast(0f))
                        rise * decay * amp
                    } else 0f
                    // 未唱层常显，已唱层按进度淡入。弹跳作用在整个词块上，
                    // 但两层文字尺寸完全一致，所以不会出现重影错位。
                    val s = 1f + pop
                    scaleX = s
                    scaleY = s
                    translationY = -pop * fontSize * 0.42f
                    val floatPhase = (t / 1100f) + index * 0.62f
                    if (motion == LyricMotionStyle.FLOAT) {
                        translationY += sin(floatPhase) * fontSize * 0.055f
                    }
                    if (motion == LyricMotionStyle.GLITCH && beat != null) {
                        val p = beat.pulse
                        if (p > 0.18f) {
                            translationX = sin(t * 0.045f + index * 1.7f) * 2.4f * p
                            scaleX = s + 0.012f * p
                            scaleY = s + 0.012f * p
                        } else {
                            translationX = 0f
                        }
                    }
                }
            ) {
                Text(text = unit.text, style = baseStyle, color = unsungColor, maxLines = 1)
                if (glowOn) {
                    Text(
                        text = unit.text,
                        style = baseStyle.copy(
                            shadow = Shadow(
                                color = litColor.copy(alpha = 0.85f),
                                offset = Offset.Zero,
                                blurRadius = glowRadius
                            )
                        ),
                        color = litColor,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .graphicsLayer {
                                val age = clock.estMs - unit.startMs
                                val lit = (age.toFloat() / fadeMs.coerceAtMost(unitSpan))
                                    .coerceIn(0f, 1f)
                                val smooth = lit * lit * (3f - 2f * lit)
                                alpha = when (glowStyle) {
                                    1 -> smooth * 0.55f
                                    2 -> smooth * (0.32f + 0.32f * sin(clock.estMs / 260f))
                                    3 -> smooth * (0.18f + 0.82f * (beat?.pulse ?: 0f))
                                    else -> 0f
                                } * glowStrength.coerceIn(0f, 1f)
                            }
                    )
                }
                Text(
                    text = unit.text,
                    style = baseStyle,
                    color = litColor,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .graphicsLayer {
                            // 已唱层的透明度只在绘制阶段读帧钟，不触发重新排版。
                            val age = clock.estMs - unit.startMs
                            val lit = (age.toFloat() / fadeMs.coerceAtMost(unitSpan))
                                .coerceIn(0f, 1f)
                            alpha = lit * lit * (3f - 2f * lit)
                        }
                )
            }
        }
    }
}

// =====================================================================================
// 帧钟
// =====================================================================================

/**
 * 歌词帧钟：给逐词动画提供一个每帧可读的时间轴。
 *
 * [estMs] 是 State，但只在 graphicsLayer / Canvas 的绘制 lambda 里读取，
 * 因此每帧只让绘制失效，不触发重组——这是逐词动画能跑满 60fps 的关键。
 *
 * 5.2featR6 起改为**每帧直接问播放器**（[live]），不再自己按墙上时间外推：
 * media3 的 currentPosition 内部已经外推过一次，这里再加一遍就是把同一段时间
 * 算两次，扫光会稳定跑在咬字前面（最多约 1 秒，因为外推还叠在行索引之上）。
 * 读一次 currentPosition 是纯算术（缓存的状态 + 时间差），成本与读字段相当。
 */
@Stable
internal class LyricFrameClock {
    var estMs by mutableLongStateOf(0L)
        internal set
}

/**
 * @param live 实时播放位置提供者（已含歌词时间偏移）
 * @param key 换行 / 换曲时重建，避免上一句的时间残留
 */
@Composable
internal fun rememberLyricFrameClock(live: () -> Long, key: Any?): LyricFrameClock {
    val liveState = rememberUpdatedState(live)
    val clock = remember(key) { LyricFrameClock().also { it.estMs = liveState.value() } }
    LaunchedEffect(key) {
        while (true) {
            withFrameNanos {
                val v = liveState.value()
                // 前进无条件跟随；倒退超过 60ms 也跟随。
                // 6.9.12：死区 120→60ms。120ms 的宽限会让"位置领先 100ms"永久留在
                // 逐字扫光的时钟上，每句都快一点点，叠起来就是"歌词一直跑在前面"。
                if (v > clock.estMs || clock.estMs - v > 60L) clock.estMs = v
            }
        }
    }
    return clock
}

/**
 * 歌词播放头：修「唱到下一句了，字还没跳」，同时修「字比唱得快」。
 *
 * 两件事以前是靠同一个错误做法解决的——自己拿墙上时间往外推。
 * media3 的 `currentPosition` 已经把外推做掉了（`MediaUtils.getUpdatedCurrentPositionMs`），
 * 5.2featR5 的 `rememberPreciseLyricMs` 又在 500ms 轮询值上叠了一层外推，
 * 逐字那层再叠第三层：结果高亮稳定超前约 0.25~0.5 秒、扫光最多 1 秒，
 * 而且它只前进不回退（1.2 秒以内不修正），切音质/队列重建时白吃的时间会永久留在头上。
 *
 * 现在改成 Sonify 那套做法，并保留低频发布：
 *  ① 值来自**直接问播放器**，不自己算时间；
 *  ② 醒来时机由"下一句起点"精确安排，所以切句仍是几十毫秒级；
 *  ③ 额外每 [resyncMs] 强制重同步一次，任何偏差都会在不到 0.7 秒内被纠回来
 *     ——前进后退都能纠，不存在"越听越靠前"。
 * 发布频率与原来的 500ms 轮询同一量级，老机型不会因为这层多醒。
 */
@Composable
internal fun rememberLyricPlayheadMs(
    live: () -> Long,
    playing: Boolean,
    boundaries: List<Long>
): Long {
    val liveState = rememberUpdatedState(live)
    var shown by remember { mutableLongStateOf(liveState.value()) }
    val resyncMs = if (com.jun.musiccloud.ui.theme.DevicePerformance.legacy) 700L else 420L
    LaunchedEffect(playing, boundaries) {
        while (true) {
            val v = liveState.value()
            // 前进无条件跟随；倒退超过 60ms 也跟随。
            // 6.9.12：死区从 120ms 收到 60ms —— 旧值会让"位置领先 100ms"这种
            // 小偏差被永久保留，逐句看是"每句都提前一点点"，累积到听感就是"整首跑快"。
            // 60ms 已经大于播放器毫秒级抖动，不会再出现"往回弹"。
            if (v > shown || shown - v > 60L) shown = v
            if (!playing) {
                kotlinx.coroutines.delay(700)
                continue
            }
            val now = liveState.value()
            val next = boundaries.firstOrNull { it > now }
            val waitResync = resyncMs
            val waitLine = if (next != null) (next - now).coerceAtLeast(16L) else Long.MAX_VALUE
            kotlinx.coroutines.delay(minOf(waitResync, waitLine))
        }
    }
    return shown
}
