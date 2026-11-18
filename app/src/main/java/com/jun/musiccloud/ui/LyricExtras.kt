package com.jun.musiccloud.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.jun.musiccloud.data.LyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// =====================================================================================
// 2.8 歌词增强：副歌识别、制作信息、封面取色、排版、预设、海报、封面纵深。
// 这里只放纯函数与独立绘图层，舞台代码通过 LyricFrame 取用，避免互相耦合。
// =====================================================================================

// -------------------------------------------------------------------------------------
// 副歌识别
// -------------------------------------------------------------------------------------

/**
 * 副歌检测：不依赖接口给的 chorus 字段，纯看歌词文本。
 *
 * 规则刻意保守：
 *  - 归一化后长度 >= 6 个字符，避免「啊」「哦」被当成副歌；
 *  - 同一句至少出现 2 次，且两次相隔 >= 6 行，排除连续重复的排版错误；
 *  - 副歌段内允许 2 行的漏标（间奏后接一句主歌再回副歌）。
 */
internal object LyricChorus {

    fun detect(lines: List<LyricLine>): BooleanArray {
        val flags = BooleanArray(lines.size)
        if (lines.size < 8) return flags
        val normalized = lines.map { normalize(it.text) }
        val positions = HashMap<String, MutableList<Int>>()
        normalized.forEachIndexed { index, text ->
            if (text.length >= 6) positions.getOrPut(text) { ArrayList() }.add(index)
        }
        for ((_, pos) in positions) {
            if (pos.size < 2) continue
            if (pos.last() - pos.first() < 6) continue
            for (p in pos) flags[p] = true
        }
        // 副歌块之间通常只隔一句，向两侧扩一格，让整段副歌都有氛围增强。
        val expanded = flags.copyOf()
        for (i in flags.indices) {
            if (!flags[i]) continue
            var j = i - 1
            var steps = 0
            while (j >= 0 && steps < 2 && !flags[j] && looksLikeHook(normalized[j])) {
                expanded[j] = true; j--; steps++
            }
        }
        return expanded
    }

    fun fraction(flags: BooleanArray): Float =
        if (flags.isEmpty()) 0f else flags.count { it }.toFloat() / flags.size

    private fun looksLikeHook(text: String): Boolean = text.length in 3..24

    private fun normalize(text: String): String {
        if (text.isBlank()) return ""
        val out = StringBuilder(text.length)
        for (c in text) {
            when {
                c.isLetterOrDigit() -> out.append(c.lowercaseChar())
                c.code in 0x4E00..0x9FFF -> out.append(c)
            }
        }
        return out.toString()
    }
}

/**
 * 副歌氛围层：三种形态（光柱 / 光环 / 光束），按行号固定分配，切歌不会闪烁。
 * 只在副歌行开启，强度跟随节拍包络。
 */
@Composable
internal fun LyricChorusFx(
    beat: LyricBeatState,
    accent: Color,
    dark: Boolean,
    effect: Int,
    strength: Float,
    modifier: Modifier = Modifier,
    speed: Float = 1f
) {
    if (strength <= 0.01f) return
    var tick by remember { mutableIntStateOf(0) }
    // R4：老机型 / 省电动画下这些全屏装饰降到约 30fps（见 JunMotion.frameDue），
    // 每帧一次「重组 + 整屏 Canvas 重绘」在 2019 年的机器上是持续的卡顿源。
    val frameGate = remember { LongArray(1) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { now ->
            if (!JunMotion.frameDue(now, frameGate)) return@withFrameNanos
            tick++
        }
    }
    Canvas(modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION") tick
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val p = beat.pulse
        val bass = beat.bass
        val baseAlpha = (0.05f + (p * 0.14f + bass * 0.10f)) * strength
        val tone = if (dark) accent.copy(alpha = baseAlpha) else accent.copy(alpha = baseAlpha * 0.8f)
        when (effect % 3) {
            0 -> {
                // 光柱：从底部升起的竖条，高度随节拍起伏。
                val bars = 21
                val bw = w / bars * 0.42f
                for (i in 0 until bars) {
                    val x = (i + 0.5f) * w / bars
                    val phase = sin(tick * 0.045f * speed + i * 0.9f) * 0.5f + 0.5f
                    val bh = h * (0.05f + 0.20f * phase * (0.35f + p * 0.9f))
                    drawRect(
                        color = tone.copy(alpha = tone.alpha * (0.55f + 0.45f * phase)),
                        topLeft = Offset(x - bw / 2f, h - bh),
                        size = Size(bw, bh)
                    )
                }
            }
            1 -> {
                // 光环：从歌词中心向外扩散的同心圆。
                val center = Offset(w * 0.5f, h * 0.52f)
                for (i in 0 until 3) {
                    val phase = ((tick * 0.006f * speed + i / 3f) % 1f)
                    val radius = size.minDimension * (0.18f + phase * 0.42f) * (1f + p * 0.12f)
                    drawCircle(
                        color = tone.copy(alpha = tone.alpha * (1f - phase) * 1.6f),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 1.6f + p * 3f)
                    )
                }
            }
            else -> {
                // 光束：斜向扫过的柔光，用渐变线模拟。
                val beams = 5
                for (i in 0 until beams) {
                    val x = w * (i / (beams - 1f))
                    val sway = sin(tick * 0.012f * speed + i * 1.3f) * w * 0.04f
                    drawLine(
                        brush = Brush.linearGradient(
                            listOf(Color.Transparent, tone, Color.Transparent),
                            start = Offset(x + sway, 0f),
                            end = Offset(x - sway, h)
                        ),
                        start = Offset(x + sway, 0f),
                        end = Offset(x - sway, h),
                        strokeWidth = 2f + p * 6f
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------
// 制作人员识别
// -------------------------------------------------------------------------------------

internal data class LyricStaffInfo(
    val displayLines: List<LyricLine>,
    val credits: List<Pair<String, String>>
)

/**
 * 歌词开头的制作人员块：词表 + 冒号 + 只扫前 24 行。
 * 命中后从滚动歌词里移除，折叠成一张「制作信息」卡。
 */
internal object LyricStaff {

    private val roles = listOf(
        "作词", "作曲", "词", "曲", "编曲", "制作人", "演唱", "歌手", "原唱", "吉他", "贝斯",
        "混音", "母带", "录音", "监制", "出品", "策划", "统筹", "设计", "宣传", "运营", "总监",
        "鸣谢", "工作室", "版权", "发行", "和声", "弦乐", "键盘", "鼓", "制作", "企划", "美工",
        "题字", "视频", "封面", "歌词字幕", "人声后期", "录音棚",
        "Producer", "Composer", "Lyricist", "Arranger", "Vocal", "Guitar", "Bass",
        "Mixing", "Mastering", "Recording", "Lyrics", "Music"
    )

    private val roleGroup = roles.joinToString("|") { Regex.escape(it) }
    private val staffRegex = Regex(
        "^\\s*[\\[（(【]?\\s*(?:$roleGroup)(?:\\s*[/、&+,，·・和]\\s*(?:$roleGroup))*" +
            "\\s*[\\]）)】]?\\s*[:：]\\s*(\\S.*)$",
        RegexOption.IGNORE_CASE
    )
    private val spacedRegex = Regex(
        "^\\s*(?:$roleGroup)\\s*[/、&+,，·・和]?\\s*(?:$roleGroup)?\\s{1,}(\\S.*)$",
        RegexOption.IGNORE_CASE
    )

    fun scan(lines: List<LyricLine>): LyricStaffInfo {
        if (lines.size < 2) return LyricStaffInfo(lines, emptyList())
        val credits = ArrayList<Pair<String, String>>()
        val drop = HashSet<Int>()
        var started = false
        var misses = 0
        val limit = minOf(24, lines.size)
        for (i in 0 until limit) {
            val text = lines[i].text.trim()
            if (text.isBlank()) continue
            val match = staffRegex.find(text) ?: spacedRegex.find(text)
            if (match != null) {
                val colon = text.indexOfAny(charArrayOf(':', '：'))
                val role = (if (colon > 0) text.substring(0, colon) else text)
                    .trim().trim('[', ']', '(', ')', '（', '）', '【', '】')
                credits += role.ifBlank { "制作" } to match.groupValues[1].trim()
                drop += i
                started = true
                misses = 0
            } else if (started) {
                misses++
                if (misses >= 3) break
            }
        }
        // 别把整首歌词都当制作信息：至少给界面留 2 行。
        if (lines.size - drop.size < 2) return LyricStaffInfo(lines, emptyList())
        val display = if (drop.isEmpty()) lines else lines.filterIndexed { index, _ -> index !in drop }
        return LyricStaffInfo(display, credits.take(10))
    }
}

// -------------------------------------------------------------------------------------
// 封面取色
// -------------------------------------------------------------------------------------

/** 从封面缩略图里取一个「能当强调色」的颜色；失败返回 null，完全不影响原主题。 */
internal object CoverTint {

    fun extract(bitmap: Bitmap): Color? {
        val sample = runCatching {
            Bitmap.createScaledBitmap(bitmap, 28, 28, true)
        }.getOrNull() ?: return null
        val buckets = HashMap<Int, FloatArray>() // key -> [weight, r, g, b]
        for (y in 0 until sample.height) {
            for (x in 0 until sample.width) {
                val px = sample.getPixel(x, y)
                val a = (px ushr 24) and 0xFF
                if (a < 160) continue
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val maxC = max(r, max(g, b)) / 255f
                val minC = min(r, min(g, b)) / 255f
                val sat = if (maxC <= 0f) 0f else (maxC - minC) / maxC
                val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                if (lum < 0.06f || lum > 0.96f) continue
                if (sat < 0.12f) continue // 灰阶不当强调色
                val score = sat * (1f - abs(lum - 0.55f) * 1.2f).coerceAtLeast(0.15f)
                val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                val slot = buckets.getOrPut(key) { FloatArray(4) }
                slot[0] += score
                slot[1] += r * score
                slot[2] += g * score
                slot[3] += b * score
            }
        }
        var best: FloatArray? = null
        var bestWeight = 0f
        for (slot in buckets.values) {
            if (slot[0] > bestWeight) {
                bestWeight = slot[0]
                best = slot
            }
        }
        val b = best ?: return null
        if (bestWeight <= 0.01f) return null
        var color = Color(
            (b[1] / b[0] / 255f).coerceIn(0f, 1f),
            (b[2] / b[0] / 255f).coerceIn(0f, 1f),
            (b[3] / b[0] / 255f).coerceIn(0f, 1f)
        )
        // 太暗的颜色往白里提一点，保证在深色背景上能看见。
        val lum = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
        if (lum < 0.48f) color = lerp(color, Color.White, (0.48f - lum) * 0.85f)
        // 太亮的颜色往主题深色压一点，避免白字背景刺眼。
        if (lum > 0.86f) color = lerp(color, Color.Black, (lum - 0.86f) * 0.5f)
        if (sample !== bitmap) sample.recycle()
        return color
    }
}

@Composable
internal fun rememberCoverTint(
    artworkKey: String,
    enabled: Boolean,
    strength: Float
): Color? {
    val coverVersion = CustomCoverStore.version
    var raw by remember(artworkKey, enabled, coverVersion) { mutableStateOf<Color?>(null) }
    LaunchedEffect(artworkKey, enabled, coverVersion) {
        if (!enabled || artworkKey.isBlank()) {
            raw = null
            return@LaunchedEffect
        }
        raw = withContext(Dispatchers.IO) {
            runCatching {
                ArtworkLoader.load(artworkKey)?.let { CoverTint.extract(it) }
            }.getOrNull()
        }
    }
    val tint = raw ?: return null
    val amount = strength.coerceIn(0f, 1f) * 0.8f
    return if (amount <= 0.001f) null else tint.copy(alpha = amount)
}

// -------------------------------------------------------------------------------------
// 排版
// -------------------------------------------------------------------------------------

internal data class LyricTypography(
    val lineSpacing: Float = 1.18f,
    val letterSpacingEm: Float = 0f,
    val weightIndex: Int = 1,
    val outline: Float = 0f,
    val texture: Int = 0
) {
    fun weight(fallback: FontWeight): FontWeight = when (weightIndex) {
        0 -> FontWeight.Normal
        1 -> fallback
        else -> FontWeight.Black
    }
}

internal fun letterSpacingFor(fontSize: Float, em: Float) = (fontSize * em).sp

/**
 * 纸张 / 颗粒 / 扫描线纹理：固定随机点，不每帧分配；透明度极低，只在近看时有一层质感。
 */
@Composable
internal fun LyricTextureOverlay(
    kind: Int,
    dark: Boolean,
    modifier: Modifier = Modifier
) {
    if (kind <= 0) return
    val points = remember(kind) {
        val rnd = Random(kind * 977 + 31)
        FloatArray(260) { if (it % 2 == 0) rnd.nextFloat() else rnd.nextFloat() }
    }
    var tick by remember { mutableIntStateOf(0) }
    val frameGate = remember { LongArray(1) }
    LaunchedEffect(kind) {
        while (true) withFrameNanos { now ->
            if (!JunMotion.frameDue(now, frameGate)) return@withFrameNanos
            tick++
        }
    }
    Canvas(modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION") tick
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        when (kind) {
            1 -> {
                // 纸纹：暖白细点 + 极淡的斜向纤维。
                val tone = (if (dark) Color(0xFFFFF4E0) else Color(0xFF3A3020)).copy(alpha = 0.045f)
                for (i in 0 until points.size step 2) {
                    drawCircle(
                        tone,
                        radius = 0.6f + (points[i] * 1.2f),
                        center = Offset(points[i] * w, points[i + 1] * h)
                    )
                }
                drawLine(
                    Color.White.copy(alpha = 0.018f),
                    Offset(0f, h * 0.2f),
                    Offset(w, h * 0.8f),
                    strokeWidth = h * 0.18f
                )
            }
            2 -> {
                // 颗粒：黑白交替的细砂，滚动方向缓慢下移。
                val drift = (tick % 1200) / 1200f * h
                for (i in 0 until points.size step 2) {
                    val x = points[i] * w
                    val y = (points[i + 1] * h + drift) % h
                    val white = i % 4 == 0
                    drawCircle(
                        (if (white) Color.White else Color.Black).copy(alpha = 0.028f),
                        radius = 0.5f + points[i] * 1.1f,
                        center = Offset(x, y)
                    )
                }
            }
            else -> {
                // 扫描线：每 4px 一道，像老监视器。
                val gap = 4.dp.toPx()
                val tone = Color.Black.copy(alpha = 0.045f)
                var y = 0f
                while (y < h) {
                    drawLine(tone, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                    y += gap
                }
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.02f), Color.Transparent)
                    )
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------
// 视觉预设
// -------------------------------------------------------------------------------------

internal data class LyricPreset(
    val name: String,
    val stage: Int,
    val motion: Int,
    val displayMode: Int,
    val context: Int,
    val quality: Int,
    val chorus: Boolean,
    val glowStyle: Int,
    val coverTint: Boolean,
    val particles: Boolean,
    val bassGlow: Boolean,
    val beatFx: Boolean,
    /** 四轴调参：密度 / 速度 / 发光 / 镜头 */
    val tuning: List<Int> = listOf(50, 50, 50, 50),
    /** 舞台专属调校：字号 / 动势 / 辉光 / 形态 */
    val stageProfile: List<Int> = listOf(100, 100, 100, 0)
)

internal object LyricPresetCatalog {

    val builtIn: List<LyricPreset> = listOf(
        LyricPreset("影院·流光", 0, 5, 3, 4, 3, true, 1, true, true, true, true),
        LyricPreset("极简·扫光", 0, 0, 0, 0, 1, false, 0, false, false, false, false),
        LyricPreset("心象·呼吸", 1, 2, 1, 1, 2, true, 2, true, true, true, true),
        LyricPreset("故障·副歌", 6, 6, 2, 2, 2, true, 3, false, true, true, true),
        LyricPreset("漂浮·星河", 3, 3, 2, 2, 3, true, 1, true, true, true, true),
        LyricPreset("静止·纸纹", 13, 0, 1, 1, 1, false, 0, false, false, false, false)
    )

    fun encode(p: LyricPreset): String = JSONObject().apply {
        put("name", p.name)
        put("stage", p.stage)
        put("motion", p.motion)
        put("displayMode", p.displayMode)
        put("context", p.context)
        put("quality", p.quality)
        put("chorus", p.chorus)
        put("glowStyle", p.glowStyle)
        put("coverTint", p.coverTint)
        put("particles", p.particles)
        put("bassGlow", p.bassGlow)
        put("beatFx", p.beatFx)
        put("tuning", JSONArray(p.tuning))
        put("stageProfile", JSONArray(p.stageProfile))
    }.toString()

    fun decode(raw: String?): LyricPreset? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            LyricPreset(
                name = o.optString("name", "自定义"),
                stage = o.optInt("stage", 0).coerceIn(0, 13),
                motion = o.optInt("motion", 1).coerceIn(0, 6),
                displayMode = o.optInt("displayMode", 2).coerceIn(0, 4),
                context = o.optInt("context", 2).coerceIn(0, 6),
                quality = o.optInt("quality", 0).coerceIn(0, 3),
                chorus = o.optBoolean("chorus", true),
                glowStyle = o.optInt("glowStyle", 1).coerceIn(0, 3),
                coverTint = o.optBoolean("coverTint", false),
                particles = o.optBoolean("particles", true),
                bassGlow = o.optBoolean("bassGlow", true),
                beatFx = o.optBoolean("beatFx", true),
                tuning = runCatching {
                    val a = o.optJSONArray("tuning") ?: return@runCatching listOf(50, 50, 50, 50)
                    List(4) { i -> a.optInt(i, 50).coerceIn(0, 100) }
                }.getOrDefault(listOf(50, 50, 50, 50)),
                stageProfile = runCatching {
                    val a = o.optJSONArray("stageProfile")
                        ?: return@runCatching listOf(100, 100, 100, 0)
                    listOf(
                        a.optInt(0, 100).coerceIn(85, 125),
                        a.optInt(1, 100).coerceIn(0, 150),
                        a.optInt(2, 100).coerceIn(0, 150),
                        a.optInt(3, 0).coerceIn(0, 3)
                    )
                }.getOrDefault(listOf(100, 100, 100, 0))
            )
        }.getOrNull()
    }
}

// -------------------------------------------------------------------------------------
// 封面纵深 / 点击涟漪
// -------------------------------------------------------------------------------------

/**
 * 封面纵深：整张封面做底、重模糊、低透明度，手指拖动时产生 1~2% 的视差；
 * 点击时在落点扩散一圈涟漪。只观察事件、不消费，不会抢走「点空白关闭」。
 */
@Composable
internal fun LyricCoverDepth(
    artworkKey: String,
    enabled: Boolean,
    dark: Boolean,
    modifier: Modifier = Modifier,
    shift: Offset = Offset.Zero
) {
    if (!enabled || artworkKey.isBlank()) return
    val image = rememberStageArtwork(artworkKey) ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = shift.x
                translationY = shift.y
                scaleX = 1.16f
                scaleY = 1.16f
            }
            .blur(46.dp)
            .graphicsLayer { alpha = if (dark) 0.20f else 0.12f }
    )
}

/** 点击涟漪状态：只存落点与时间，由 Canvas 逐帧扩散。 */
@Stable
internal class LyricRippleState {
    var items by mutableStateOf<List<Pair<Offset, Long>>>(emptyList())
        private set

    fun add(position: Offset) {
        val now = System.currentTimeMillis()
        items = (items.filter { now - it.second < 900L } + (position to now)).takeLast(6)
    }

    fun prune() {
        val now = System.currentTimeMillis()
        if (items.any { now - it.second >= 900L }) {
            items = items.filter { now - it.second < 900L }
        }
    }
}

@Composable
internal fun rememberLyricRippleState(): LyricRippleState = remember { LyricRippleState() }

@Composable
internal fun LyricRippleCanvas(
    state: LyricRippleState,
    accent: Color,
    modifier: Modifier = Modifier
) {
    var tick by remember { mutableIntStateOf(0) }
    val frameGate = remember { LongArray(1) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { now ->
            // 涟漪按时间戳自己算进度，节流只影响重绘频率，不影响动画长度
            if (!JunMotion.frameDue(now, frameGate)) return@withFrameNanos
            tick++
            state.prune()
        }
    }
    Canvas(modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION") tick
        val now = System.currentTimeMillis()
        for ((pos, stamp) in state.items) {
            val t = ((now - stamp) / 900f).coerceIn(0f, 1f)
            if (t >= 1f) continue
            val radius = 8.dp.toPx() + t * size.minDimension * 0.30f
            drawCircle(
                color = accent.copy(alpha = (1f - t) * 0.28f),
                radius = radius,
                center = pos,
                style = Stroke(width = 1.4f + (1f - t) * 2f)
            )
        }
    }
}

// -------------------------------------------------------------------------------------
// 歌词海报
// -------------------------------------------------------------------------------------

/** 把当前句渲染成一张 1080×1620 的分享海报；不依赖截图，字体和排版完全可控。 */
internal object LyricPoster {

    suspend fun share(
        context: Context,
        title: String,
        artist: String,
        line: String,
        translation: String?,
        artworkKey: String,
        accent: Color
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val cover = if (artworkKey.isBlank()) null
            else ArtworkLoader.load(artworkKey)
            val bitmap = render(title, artist, line, translation, cover, accent)
            val dir = File(context.cacheDir, "posters").apply { mkdirs() }
            val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(32).ifBlank { "lyric" }
            val file = File(dir, "$safe-${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$title · 歌词海报")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享歌词海报").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    private fun render(
        title: String,
        artist: String,
        line: String,
        translation: String?,
        cover: Bitmap?,
        accent: Color
    ): Bitmap {
        val w = 1080
        val h = 1620
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bmp)
        val accentInt = AColor.rgb(
            (accent.red * 255).toInt().coerceIn(0, 255),
            (accent.green * 255).toInt().coerceIn(0, 255),
            (accent.blue * 255).toInt().coerceIn(0, 255)
        )
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(AColor.rgb(14, 14, 20), blend(accentInt, AColor.BLACK, 0.62f), AColor.rgb(8, 8, 12)),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        // 封面：720 方图居中，圆角 + 细边框。
        val coverSize = 720f
        val left = (w - coverSize) / 2f
        val top = 110f
        if (cover != null) {
            val rect = RectF(left, top, left + coverSize, top + coverSize)
            val path = Path().apply { addRoundRect(rect, 30f, 30f, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(path)
            val scale = max(coverSize / cover.width, coverSize / cover.height)
            val dw = cover.width * scale
            val dh = cover.height * scale
            val src = RectF(
                left + (coverSize - dw) / 2f,
                top + (coverSize - dh) / 2f,
                left + (coverSize + dw) / 2f,
                top + (coverSize + dh) / 2f
            )
            canvas.drawBitmap(cover, null, src, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            canvas.drawRoundRect(rect, 30f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = AColor.argb(60, 255, 255, 255)
            })
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.argb(30, 255, 255, 255) }
            canvas.drawRoundRect(RectF(left, top, left + coverSize, top + coverSize), 30f, 30f, p)
        }

        var y = top + coverSize + 76f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AColor.WHITE
            textSize = 54f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titleLayout = layout(title, titlePaint, (w - 200).toInt(), Layout.Alignment.ALIGN_CENTER)
        canvas.save()
        canvas.translate((w - titleLayout.width) / 2f, y)
        titleLayout.draw(canvas)
        canvas.restore()
        y += titleLayout.height + 18f

        if (artist.isNotBlank()) {
            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AColor.argb(210, 255, 255, 255)
                textSize = 30f
            }
            val layout = layout(artist, artistPaint, (w - 240).toInt(), Layout.Alignment.ALIGN_CENTER)
            canvas.save()
            canvas.translate((w - layout.width) / 2f, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 54f
        } else {
            y += 40f
        }

        // 强调色细线
        canvas.drawRect(w / 2f - 34f, y, w / 2f + 34f, y + 3f, Paint().apply { color = accentInt })
        y += 58f

        val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AColor.WHITE
            textSize = 56f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(18f, 0f, 4f, AColor.argb(120, 0, 0, 0))
        }
        val lyricLayout = layout(line.ifBlank { " " }, lyricPaint, (w - 180).toInt(), Layout.Alignment.ALIGN_CENTER)
        canvas.save()
        canvas.translate((w - lyricLayout.width) / 2f, y)
        lyricLayout.draw(canvas)
        canvas.restore()
        y += lyricLayout.height + 26f

        if (!translation.isNullOrBlank()) {
            val transPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AColor.argb(190, 255, 255, 255)
                textSize = 30f
            }
            val layout = layout(translation, transPaint, (w - 200).toInt(), Layout.Alignment.ALIGN_CENTER)
            canvas.save()
            canvas.translate((w - layout.width) / 2f, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 20f
        }

        // 底部：品牌 + 日期，淡一点，不抢歌词。
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AColor.argb(120, 255, 255, 255)
            textSize = 24f
            letterSpacing = 0.08f
        }
        val date = SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(Date())
        val footer = layout("JUN MUSIC  ·  $date", footerPaint, (w - 200).toInt(), Layout.Alignment.ALIGN_CENTER)
        canvas.save()
        canvas.translate((w - footer.width) / 2f, h - 112f)
        footer.draw(canvas)
        canvas.restore()
        return bmp
    }

    private fun layout(text: String, paint: TextPaint, width: Int, align: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setIncludePad(false)
            .setLineSpacing(10f, 1.12f)
            .setMaxLines(5)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()

    private fun blend(a: Int, b: Int, t: Float): Int {
        val ar = AColor.red(a); val ag = AColor.green(a); val ab = AColor.blue(a)
        val br = AColor.red(b); val bg = AColor.green(b); val bb = AColor.blue(b)
        return AColor.rgb(
            (ar + (br - ar) * t).toInt(),
            (ag + (bg - ag) * t).toInt(),
            (ab + (bb - ab) * t).toInt()
        )
    }
}

/** 歌词海报按钮：点击后把当前句渲染成 PNG 并调系统分享。 */
@Composable
internal fun LyricPosterPill(
    title: String,
    artist: String,
    line: String,
    translation: String?,
    artworkKey: String,
    accent: Color,
    onStage: Color,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Text(
        if (busy) "生成中…" else "歌词海报",
        color = accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .junPressable(feedback = PressFeedback.NONE, enabled = !busy) {
                if (busy) return@junPressable
                busy = true
                scope.launch {
                    val ok = LyricPoster.share(
                        ctx, title, artist, line, translation, artworkKey, accent
                    )
                    busy = false
                    if (!ok) android.widget.Toast.makeText(
                        ctx, "海报生成失败", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .clip(RoundedCornerShape(50))
            .background(onStage.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** 顶部「制作信息」小胶囊：收起时只占一行，点开列出署名。 */
@Composable
internal fun LyricStaffPill(
    credits: List<Pair<String, String>>,
    accent: Color,
    onStage: Color,
    modifier: Modifier = Modifier
) {
    if (credits.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        androidx.compose.foundation.layout.Column(
            Modifier
                // 按压挂在整颗药丸上（在 clip/background 之前），不然按下时只有字缩、那层淡底不动
                // 展开时点药丸任意处收起，和「制作信息 · 收起」这句文案的语义一致
                .junPressable(feedback = PressFeedback.NONE) { open = !open }
                .clip(RoundedCornerShape(14.dp))
                .background(if (open) onStage.copy(alpha = 0.08f) else onStage.copy(alpha = 0.05f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                if (open) "制作信息 · 收起" else "制作信息 · ${credits.size} 项",
                color = accent.copy(alpha = 0.9f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .padding(vertical = 1.dp)
            )
            if (open) {
                androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                credits.forEach { (role, value) ->
                    Text(
                        "$role  $value",
                        color = onStage.copy(alpha = 0.72f),
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------
// 小工具
// -------------------------------------------------------------------------------------

internal fun chorusEffectFor(index: Int): Int = ((index * 31) % 3 + 3) % 3
