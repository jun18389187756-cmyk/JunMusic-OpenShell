package com.jun.musiccloud.data

import android.icu.text.BreakIterator
import java.util.Locale

/** 句子时长档：决定进场/退场与逐字显示的力度，对应 Folia 的 renderHints。 */
enum class LineTimingClass { MICRO, SHORT, NORMAL }

/** 句子过渡档：micro 不做过渡，short 用快速过渡，normal 才走完整时长。 */
enum class LineTransitionMode { NORMAL, FAST, NONE }

/** 逐字显示档：micro 瞬时铺满，short 快速扫光，normal 常规扫光。 */
enum class WordRevealMode { NORMAL, FAST, INSTANT }

/**
 * 一句歌词的渲染提示。
 *
 * 和 Folia 的 `src/utils/lyrics/renderHints.ts` 同构，但不依赖 Web 的秒制：
 * 这里全部用毫秒。`renderEndMs` 不是简单的行尾，而是"这一句最晚可以留在屏幕上的时间"，
 * 给退场/余辉留出窗口；下一句提前开始时由 [LyricRuntime.activeIndex] 截断。
 */
data class LineRenderHints(
    val rawDurationMs: Long,
    val timingClass: LineTimingClass,
    val transition: LineTransitionMode,
    val reveal: WordRevealMode,
    val enterMs: Long,
    val exitMs: Long,
    val holdMs: Long,
    val renderEndMs: Long
)

/** 一个"用户感知字符"的时间片：CJK 单字、拉丁字母、emoji 组合都算一个。 */
data class TimedGrapheme(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

/** 一句歌词的逐字时间线，供扫光、余辉与预加热共用。 */
class LyricTimeline internal constructor(
    val graphemes: List<TimedGrapheme>
) {
    val count: Int get() = graphemes.size

    /** 当前已显示到第几个 grapheme（可为小数，词内连续推进）。 */
    fun revealUnits(positionMs: Long): Float {
        if (graphemes.isEmpty()) return 0f
        var done = 0
        for (g in graphemes) {
            if (positionMs < g.startMs) return done.toFloat()
            if (positionMs < g.endMs) {
                val span = (g.endMs - g.startMs).coerceAtLeast(1L)
                val frac = ((positionMs - g.startMs).toFloat() / span).coerceIn(0f, 1f)
                return done + frac
            }
            done++
        }
        return done.toFloat()
    }

    /** 0..1 的显示比例。按 grapheme 计数而不是 UTF-16 长度，emoji 不会被拆成两半。 */
    fun revealFraction(positionMs: Long): Float {
        if (graphemes.isEmpty()) return 0f
        return (revealUnits(positionMs) / graphemes.size).coerceIn(0f, 1f)
    }

    /**
     * 6.9.13：定位当前进度落在**第几个字**、以及这个字内已唱的比例。
     *
     * 这是"逐字对齐"的关键接口。以前只有 [revealFraction]（已唱字数 / 总字数），
     * 拿它去推扫光位置，等于假设"每个字一样宽"——中文勉强，英文、数字、
     * 标点一混排就整段错位，视觉上就是"光带跑到字缝里去了"。
     * 有了「第几个字 + 字内比例」，UI 就能查这个字的**真实像素左右边界**再插值，
     * 光带前沿永远压在笔画上，跟 QQ 音乐/网易云是同一套做法。
     *
     * @return index 为 grapheme 下标（== count 表示整行唱完）；fraction 为字内 0..1。
     */
    fun revealAt(positionMs: Long): Pair<Int, Float> {
        if (graphemes.isEmpty()) return 0 to 0f
        for ((i, g) in graphemes.withIndex()) {
            if (positionMs < g.startMs) return i to 0f
            if (positionMs < g.endMs) {
                val span = (g.endMs - g.startMs).coerceAtLeast(1L)
                return i to ((positionMs - g.startMs).toFloat() / span).coerceIn(0f, 1f)
            }
        }
        return graphemes.size to 1f
    }

    /**
     * 逐字余辉：返回 0..1 的亮度包络。
     * 和扫光进度分开计算——字已经亮完，光还会多留一小段时间再散掉。
     */
    fun glowAt(positionMs: Long, glowMs: Long = GLOW_MS): Float {
        if (graphemes.isEmpty()) return 0f
        for (g in graphemes) {
            if (positionMs < g.startMs) return 0f
            if (positionMs <= g.startMs + glowMs) {
                val age = (positionMs - g.startMs).coerceAtLeast(0L)
                return (1f - age.toFloat() / glowMs).coerceIn(0f, 1f)
            }
        }
        return 0f
    }

    companion object {
        const val GLOW_MS = 210L
    }
}

/**
 * 布局单位：把"时间词"整理成"显示词"。
 *
 * 对应 Folia 的 `cjkSemanticLayout.ts`：
 * - 标点、缩写后缀粘回前一个词，避免行首出现孤零零的「，」「’s」；
 * - CJK 连续单字按 ICU 词边界合成一个语义单位，排版/关键词匹配都以它为准；
 * - 原始词和原始时间一个都不丢，逐字扫光仍按词内时间走。
 */
data class LyricLayoutUnit(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val semantic: Boolean = false,
    val sticky: Boolean = false
)

/**
 * 歌词渲染引擎：渲染提示、逐字时间线、布局单位、运行时窗口与显示过滤。
 *
 * 设计原则和 Folia 一致：这一层只回答"这句该怎么演、下一个字是什么、下一句何时预热"，
 * 不持有任何 Compose 状态，也不做动画曲线；各舞台自己决定怎么画。
 */
object LyricRenderEngine {

    const val MICRO_MS = 100L
    const val SHORT_MS = 180L
    const val MICRO_FLOOR_MS = 67L

    private const val CACHE_MAX = 48
    private val timelineCache = object : LinkedHashMap<String, LyricTimeline>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricTimeline>?): Boolean =
            size > CACHE_MAX
    }
    private val layoutCache = object : LinkedHashMap<String, List<LyricLayoutUnit>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LyricLayoutUnit>>?): Boolean =
            size > CACHE_MAX
    }

    // ==================== 渲染提示 ====================

    fun hintsFor(line: LyricLine, lineEndMs: Long): LineRenderHints {
        val safeEnd = if (lineEndMs > line.timeMs) lineEndMs else line.timeMs + 1200L
        // 有词级时间时，最后一個词最多再占 600ms；否则把"到下一句的空档"也算成句长，
        // 会把一堆短句误判成 normal。
        val rawEnd = if (line.words.isNotEmpty()) {
            minOf(safeEnd, line.words.last().startMs + 600L)
        } else {
            safeEnd
        }
        val raw = (rawEnd - line.timeMs).coerceAtLeast(0L)
        val timingClass = when {
            raw < MICRO_MS -> LineTimingClass.MICRO
            raw < SHORT_MS -> LineTimingClass.SHORT
            else -> LineTimingClass.NORMAL
        }
        val transition = when (timingClass) {
            LineTimingClass.MICRO -> LineTransitionMode.NONE
            LineTimingClass.SHORT -> LineTransitionMode.FAST
            LineTimingClass.NORMAL -> LineTransitionMode.NORMAL
        }
        val reveal = when (timingClass) {
            LineTimingClass.MICRO -> WordRevealMode.INSTANT
            LineTimingClass.SHORT -> WordRevealMode.FAST
            LineTimingClass.NORMAL -> WordRevealMode.NORMAL
        }
        val enter: Long
        val exit: Long
        val hold: Long
        when (transition) {
            LineTransitionMode.NONE -> {
                enter = 0L
                exit = 0L
                hold = 0L
            }
            LineTransitionMode.FAST -> {
                enter = 55L
                exit = 35L
                hold = 30L
            }
            LineTransitionMode.NORMAL -> {
                val d = raw.coerceAtLeast(120L)
                enter = (d * 0.34f).toLong().coerceIn(220L, 420L)
                exit = (d * 0.18f).toLong().coerceIn(180L, 320L)
                hold = 60L
            }
        }
        val lastWordStart = line.words.lastOrNull()?.startMs ?: line.timeMs
        val passStart = maxOf(lastWordStart, line.timeMs) + hold
        val exitStart = maxOf(passStart, rawEnd - exit)
        val renderEnd = maxOf(lineEndMs, exitStart + exit, line.timeMs + MICRO_FLOOR_MS)
        return LineRenderHints(
            rawDurationMs = raw,
            timingClass = timingClass,
            transition = transition,
            reveal = reveal,
            enterMs = enter,
            exitMs = exit,
            holdMs = hold,
            renderEndMs = renderEnd
        )
    }

    // ==================== 逐字时间线 ====================

    fun timelineFor(line: LyricLine, lineEndMs: Long): LyricTimeline {
        val key = "${line.timeMs}|${lineEndMs}|${line.text.hashCode()}|${line.words.size}"
        synchronized(timelineCache) { timelineCache[key] }?.let { return it }
        val built = buildTimeline(line, lineEndMs)
        synchronized(timelineCache) { timelineCache[key] = built }
        return built
    }

    /** 预热：把当前句之后 [count] 句的时间线先算好，切句那一帧不再分配、不再分词。 */
    fun preheat(lines: List<LyricLine>, index: Int, count: Int, durationMs: Long) {
        if (index < 0 || index >= lines.size) return
        for (i in index until minOf(lines.size, index + count + 1)) {
            val line = lines[i]
            val end = lines.getOrNull(i + 1)?.timeMs
                ?: (if (durationMs > line.timeMs) durationMs else line.timeMs + 5000L)
            timelineFor(line, end)
            layoutUnits(line, end)
        }
    }

    /**
     * 常驻预构建：对应 Mineradio 的 resident build。
     *
     * 打开歌词层时在后台把整首（最多 [limit] 行）的时间线与布局单位先算完，
     * 之后任何一句切进来都只做查表。必须在 Default/IO 线程调用，不要在主线程跑。
     */
    fun residentBuild(lines: List<LyricLine>, durationMs: Long, limit: Int = 240) {
        val end = minOf(lines.size, limit)
        for (i in 0 until end) {
            val line = lines[i]
            val lineEnd = lines.getOrNull(i + 1)?.timeMs
                ?: (if (durationMs > line.timeMs) durationMs else line.timeMs + 5000L)
            timelineFor(line, lineEnd)
            layoutUnits(line, lineEnd)
        }
    }

    private fun buildTimeline(line: LyricLine, lineEndMs: Long): LyricTimeline {
        val text = line.text
        if (text.isBlank()) return LyricTimeline(emptyList())
        val out = ArrayList<TimedGrapheme>(text.length)
        val safeEnd = if (lineEndMs > line.timeMs) lineEndMs else line.timeMs + 1200L

        if (line.words.isEmpty()) {
            val parts = graphemes(text)
            if (parts.isEmpty()) return LyricTimeline(emptyList())
            val gap = (safeEnd - line.timeMs).coerceAtLeast(0L)
            // 6.8 修：没有逐字时间时，扫光只能按"这一行实际占的窗口"铺。
            // 旧逻辑拍死 parts × 260ms：慢歌里这一行被拉得行尾拖很久（"行尾慢"），
            // 快歌里又被压得头几个字飞快（"行头快"）。改成跟着本行到下一行的真实间隔走：
            //   · 有 [起,时长] 真实行时长 → 直接用它（最高优先，YRC/QRC/KRC 行级标签）；
            //   · 间隔宽松 → 按 92% 铺满、留一口气，字均摊时长夹在 [110,360]ms 防极端拉伸；
            //   · 间隔吃紧 → 铺满整段，字恰好在下一句亮起前唱完。
            val natural = if (line.durationMs > 0L) {
                line.durationMs
            } else if (gap > 0L) {
                val per = (gap.toFloat() / parts.size).coerceIn(110f, 360f)
                (per * parts.size).toLong()
            } else {
                (parts.size * 260L).coerceAtLeast(700L)
            }
            val sweepEnd = minOf(safeEnd, line.timeMs + natural).coerceAtLeast(line.timeMs + 1L)
            spread(parts, line.timeMs, sweepEnd, out)
            return LyricTimeline(out)
        }

        var cursor = 0
        for (i in line.words.indices) {
            val word = line.words[i]
            if (word.text.isEmpty()) continue
            var at = text.indexOf(word.text, cursor)
            if (at < 0) at = text.indexOf(word.text)
            if (at < 0) continue
            if (at > cursor) {
                // 词与词之间的空白/标点：时间归到下一个词开始，不单独占时长。
                spread(graphemes(text.substring(cursor, at)), word.startMs, word.startMs, out)
            }
            val nextStart = line.words.getOrNull(i + 1)?.startMs ?: safeEnd
            // 6.9.13：优先用逐字轨给的**真实时长**。句尾拖腔、和声收尾这类
            // "最后一个字唱很久"的地方，只有真实 duration 才画得对；
            // 用下一个字的开始去推，会把这个字压缩成 300ms，然后整行提前唱完。
            val wordEnd = when {
                word.endMs > word.startMs -> word.endMs
                nextStart > word.startMs -> nextStart
                else -> word.startMs + 300L
            }.coerceAtMost(safeEnd).coerceAtLeast(word.startMs + 1L)
            spread(graphemes(word.text), word.startMs, wordEnd, out)
            cursor = at + word.text.length
        }
        if (cursor < text.length) {
            spread(graphemes(text.substring(cursor)), safeEnd, safeEnd, out)
        }
        if (out.isEmpty()) spread(graphemes(text), line.timeMs, safeEnd, out)
        return LyricTimeline(out)
    }

    private fun spread(
        parts: List<String>,
        startMs: Long,
        endMs: Long,
        out: MutableList<TimedGrapheme>
    ) {
        if (parts.isEmpty()) return
        val span = (endMs - startMs).coerceAtLeast(0L)
        if (span <= 0L) {
            // 零时长段（词间空隙等）：全部贴在 startMs 上，不占时长
            for (part in parts) out.add(TimedGrapheme(part, startMs, startMs))
            return
        }
        // 6.9.5：点亮节奏按**字面宽度加权**，不再每字均分 —— 汉字唱满一拍、
        // 拉丁字母约半拍、标点几乎不占时长。用户反馈"点亮速度是上一句结束到
        // 下一句开始的平均值"：均匀切片会让"Are you OK"里每个轻音字母和汉字
        // 占一样长，整句点亮的视觉节奏跟演唱错位。加权后逐字扫光贴着演唱密度走。
        val weights = parts.map { it.weightSum() }
        val total = weights.sum().coerceAtLeast(0.001)
        var acc = 0.0
        for ((i, part) in parts.withIndex()) {
            val s = startMs + (span * acc / total).toLong()
            acc += weights[i]
            val e = if (i == parts.lastIndex) maxOf(endMs, s) else startMs + (span * acc / total).toLong()
            out.add(TimedGrapheme(part, s, maxOf(e, s)))
        }
    }

    /** 一个字素在"演唱时长"里该占的权重：汉字 1，拉丁/数字 0.55，标点/空白 0.25 */
    private fun String.weightSum(): Double {
        var w = 0.0
        for (c in this) w += when {
            c.code >= 0x2E80 || c.isHighSurrogate() -> 1.0      // CJK 全角字 / emoji
            c.isLetterOrDigit() -> 0.55
            else -> 0.25
        }
        return w.coerceAtLeast(0.05)
    }

    // ==================== 布局单位 ====================

    fun layoutUnits(line: LyricLine, lineEndMs: Long = line.timeMs + 3000L): List<LyricLayoutUnit> {
        if (line.text.isBlank()) return emptyList()
        val key = "${line.timeMs}|$lineEndMs|${line.text.hashCode()}|${line.words.size}"
        synchronized(layoutCache) { layoutCache[key] }?.let { return it }
        val built = buildLayoutUnits(line, lineEndMs)
        synchronized(layoutCache) { layoutCache[key] = built }
        return built
    }

    private fun buildLayoutUnits(line: LyricLine, lineEndMs: Long): List<LyricLayoutUnit> {
        val text = line.text
        if (line.words.isEmpty()) {
            val parts = graphemes(text)
            val span = ((lineEndMs - line.timeMs).coerceAtLeast(1L)) / parts.size.coerceAtLeast(1)
            return stickyMerge(
                parts.mapIndexed { i, g ->
                    LyricLayoutUnit(g, line.timeMs + span * i, line.timeMs + span * (i + 1))
                }
            )
        }

        val base = ArrayList<LyricLayoutUnit>(line.words.size)
        var cursor = 0
        for (i in line.words.indices) {
            val word = line.words[i]
            var at = text.indexOf(word.text, cursor)
            if (at < 0) at = text.indexOf(word.text)
            if (at < 0) continue
            val nextStart = line.words.getOrNull(i + 1)?.startMs ?: lineEndMs
            base.add(
                LyricLayoutUnit(
                    text = word.text,
                    startMs = word.startMs,
                    endMs = when {
                        word.endMs > word.startMs -> word.endMs
                        else -> maxOf(nextStart, word.startMs + 1L)
                    }
                )
            )
            cursor = at + word.text.length
        }
        return stickyMerge(semanticMerge(text, base))
    }

    private fun semanticMerge(text: String, base: List<LyricLayoutUnit>): List<LyricLayoutUnit> {
        if (base.size < 2) return base
        // 只在整行都是 CJK / 标点时做语义合并；中英混排保持词级单位，避免把英文单词拆开。
        val cjkOnly = base.all { unit ->
            unit.text.all { it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x30FF || it.code in 0xAC00..0xD7AF || !it.isLetterOrDigit() }
        }
        if (!cjkOnly) return base
        val segments = runCatching {
            val it = BreakIterator.getWordInstance(Locale.CHINESE)
            it.setText(text)
            val ranges = ArrayList<IntRange>()
            var s = it.first()
            var e = it.next()
            while (e != BreakIterator.DONE) {
                if (e > s) ranges.add(s until e)
                s = e
                e = it.next()
            }
            ranges
        }.getOrNull() ?: return base
        if (segments.size <= 1) return base

        val merged = ArrayList<LyricLayoutUnit>(segments.size)
        for (range in segments) {
            val slice = text.substring(range.first, range.last + 1)
            if (slice.isBlank()) continue
            // 用切片在整行里的位置换算覆盖到的词单位，时间取首尾。
            var start = Long.MAX_VALUE
            var end = Long.MIN_VALUE
            var cursor = 0
            for (u in base) {
                val at = text.indexOf(u.text, cursor)
                if (at < 0) continue
                val uEnd = at + u.text.length
                if (uEnd > range.first && at <= range.last) {
                    start = minOf(start, u.startMs)
                    end = maxOf(end, u.endMs)
                }
                cursor = uEnd
            }
            if (start == Long.MAX_VALUE || end == Long.MIN_VALUE) continue
            merged.add(
                LyricLayoutUnit(
                    text = slice,
                    startMs = start,
                    endMs = end,
                    semantic = slice.any { isCjk(it) }
                )
            )
        }
        return if (merged.isEmpty()) base else merged
    }

    private fun stickyMerge(units: List<LyricLayoutUnit>): List<LyricLayoutUnit> {
        if (units.size < 2) return units
        val out = ArrayList<LyricLayoutUnit>(units.size)
        for (u in units) {
            val prev = out.lastOrNull()
            val sticky = prev != null && isSticky(u.text)
            if (sticky) {
                out[out.lastIndex] = prev!!.copy(
                    text = prev.text + u.text,
                    endMs = maxOf(prev.endMs, u.endMs),
                    sticky = true
                )
            } else {
                out.add(u)
            }
        }
        return out
    }

    private fun isSticky(text: String): Boolean {
        if (text.isBlank()) return true
        if (text.all { !it.isLetterOrDigit() }) return true
        val t = text.trim().lowercase(Locale.ROOT)
        return t in setOf("s", "t", "m", "d", "ll", "re", "ve", "em")
    }

    private fun isCjk(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || c.code in 0x3040..0x30FF || c.code in 0xAC00..0xD7AF

    // ==================== 基础工具 ====================

    private fun graphemes(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>(text.length)
        runCatching {
            val it = BreakIterator.getCharacterInstance(Locale.ROOT)
            it.setText(text)
            var s = it.first()
            var e = it.next()
            while (e != BreakIterator.DONE) {
                out.add(text.substring(s, e))
                s = e
                e = it.next()
            }
        }
        if (out.isEmpty()) {
            for (c in text) out.add(c.toString())
        }
        return out
    }
}

/**
 * 运行时窗口：当前句、刚唱完的上一句、接下来两句。
 * 对应 Folia 的 `runtime.ts`，预加热按"提前量"而不是绝对时间戳判断。
 */
object LyricRuntime {

    fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** 句间空隙里仍能拿到的上一句（给译文/余辉兜底）。 */
    fun recentCompleted(
        lines: List<LyricLine>,
        positionMs: Long,
        durationMs: Long = 0L
    ): LyricLine? {
        if (lines.isEmpty()) return null
        val idx = activeIndex(lines, positionMs)
        if (idx >= 0) {
            val line = lines[idx]
            val end = lines.getOrNull(idx + 1)?.timeMs
                ?: (if (durationMs > line.timeMs) durationMs else line.timeMs + 5000L)
            val hints = LyricRenderEngine.hintsFor(line, end)
            if (positionMs <= hints.renderEndMs) return line
        }
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            val end = lines.getOrNull(i + 1)?.timeMs
                ?: (if (durationMs > line.timeMs) durationMs else line.timeMs + 5000L)
            if (positionMs <= LyricRenderEngine.hintsFor(line, end).renderEndMs) return line
        }
        return null
    }

    fun upcoming(lines: List<LyricLine>, index: Int, count: Int = 2): List<LyricLine> {
        if (lines.isEmpty() || index < 0) return emptyList()
        val from = (index + 1).coerceAtMost(lines.size)
        val to = (from + count).coerceAtMost(lines.size)
        return if (from >= to) emptyList() else lines.subList(from, to)
    }

    fun shouldPreheat(line: LyricLine?, positionMs: Long, minLeadMs: Long = 600L, maxLeadMs: Long = 4000L): Boolean {
        if (line == null) return false
        val lead = line.timeMs - positionMs
        return lead in minLeadMs..maxLeadMs
    }

    fun preheat(lines: List<LyricLine>, index: Int, count: Int = 2, durationMs: Long = 0L) {
        LyricRenderEngine.preheat(lines, index, count, durationMs)
    }
}

/** 显示过滤：把时间标记、元数据行、空行挡在界面之外；译文/音译各自独立清理。 */
object LyricDisplayFilter {

    private val META = Regex(
        "^\\[(ar|ti|al|by|offset|re|ve|length|kana|language|tool):.*]$",
        RegexOption.IGNORE_CASE
    )
    private val TIME_ONLY = Regex("^(\\[\\d{1,3}:\\d{1,2}([.:]\\d{1,3})?])+$")
    private val STRAY_TAG = Regex(
        "\\[(ar|ti|al|by|offset|re|ve|length|kana|language|tool):[^]]*]",
        RegexOption.IGNORE_CASE
    )
    private val INLINE_TIME = Regex("\\[\\d{1,3}:\\d{1,2}([.:]\\d{1,3})?]")

    fun clean(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return lines
        var changed = false
        val out = ArrayList<LyricLine>(lines.size)
        for (line in lines) {
            val text = strip(line.text)
            if (text.isBlank() || TIME_ONLY.matches(text) || META.matches(text) || isCredit(text)) {
                changed = true
                continue
            }
            val tr = track(line.translation)
            val ro = track(line.romanization)
            if (text != line.text || tr != line.translation || ro != line.romanization) changed = true
            out.add(
                if (text != line.text || tr != line.translation || ro != line.romanization) {
                    line.copy(text = text, translation = tr, romanization = ro)
                } else {
                    line
                }
            )
        }
        return if (changed) out else lines
    }

    fun track(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val cleaned = strip(text)
        return cleaned.takeIf {
            it.isNotBlank() && !TIME_ONLY.matches(it) && !META.matches(it) && !isCredit(it)
        }
    }

    private fun strip(text: String): String =
        INLINE_TIME.replace(STRAY_TAG.replace(text, ""), "").trim()

    /**
     * 制作信息行判定。
     *
     * 网易云把「作词: XX / 作曲: XX / 编曲: XX / 混音师: XX」这类 credits 混在歌词
     * 正文里返回（v1 接口里它们还是 YRC-JSON 行，时间戳 0ms、1000ms、2000ms 依次排开），
     * 于是歌词页开头先滚一整屏"作曲"，用户体感就是"点开只有作曲、没有主歌词"。
     * 判定收得很紧：必须"已知标签 + 冒号"开头才算，正文里的"爱：是你"这种不会被误杀。
     */
    private fun isCredit(text: String): Boolean {
        val t = text.trim()
        if (t.length < 3 || t.length > 240) return false
        val colon = t.indexOfFirst { it == ':' || it == '：' }
        if (colon <= 0) return false
        val label = t.substring(0, colon).trim().lowercase().replace(Regex("\\s+"), " ")
        if (label.isEmpty() || label.length > 28) return false
        if (CREDIT_LABELS.contains(label)) return true
        // 「作词 (Lyrics)」「制作 / 编曲」「词、曲」这类带尾巴的写法：取第一段再比一次
        val head = label.split('(', '（', '/', '、', ' ').firstOrNull().orEmpty()
        if (head.isNotEmpty() && CREDIT_LABELS.contains(head)) return true
        // 平台自定义标签枚举不完（混音工程师 / 弦乐编写 / 第一小提琴 / 营销推广 /
        // 母带后期制作人…），但它们都以固定职务词出现、且整行很短。兜底收在这里：
        // 标签 ≤10 字符、整行 ≤60 字符、不含句读——正文里的长句不会被误杀。
        val compact = label.replace(" ", "")
        if (compact.length !in 2..10 || t.length > 60) return false
        if (compact.any { it in "，。,.!?！？;；" }) return false
        return CREDIT_TOKENS.any { compact.contains(it) } ||
                CREDIT_SUFFIXES.any { compact.endsWith(it) }
    }

    /** 出现即判定为制作信息的职务词（标签内任意位置） */
    private val CREDIT_TOKENS = setOf(
        "作词", "作詞", "填词", "填詞", "作曲", "编曲", "編曲", "编配", "配器", "制作", "製作", "监制",
        "監製", "出品", "发行", "發行", "宣发", "宣传", "推广", "统筹", "母带", "母帶",
        "录音", "錄音", "混音", "人声", "人聲", "和声", "和聲", "和音", "配唱", "演唱", "声音",
        "音效", "吉他", "贝斯", "貝斯", "钢琴", "鋼琴", "键盘", "弦乐", "弦樂", "管乐", "提琴",
        "萨克斯", "长笛", "合成器", "编程", "編程", "采样", "后期",
        "lyric", "word", "music", "compos", "arrang", "produc", "mix", "record", "master",
        "engineer", "vocal", "guitar", "bass", "drum", "piano", "string", "keyboard", "synth"
    )

    /** 以这些职务后缀收尾的短标签也算制作信息 */
    private val CREDIT_SUFFIXES = listOf(
        "师", "師", "室", "组", "团", "團", "编写", "编辑", "編寫", "剪辑", "设计", "設計", "助理",
        "统筹", "推广", "发行", "制作", "製作", "混音", "录音", "母带", "和声", "人声", "编曲",
        "监制", "出品", "企划", "鋼琴", "吉他", "贝斯", "提琴", "弦乐", "管乐", "合声", "和音",
        "配唱", "演唱", "program"
    )

    private val CREDIT_LABELS = setOf(
        // 中文
        "作词", "作詞", "填词", "填詞", "词", "詞", "歌词", "歌詞", "词曲", "作曲", "编曲", "編曲",
        "制作人", "製作人", "制作", "製作", "监制", "監製", "出品", "出品人", "发行", "發行", "宣发",
        "企划", "母带", "母带工程师", "录音", "录音室", "录音师", "混音", "混音师", "混音室",
        "人声", "人声编辑", "和声", "合声", "和音", "配唱制作", "音乐制作", "声音设计", "音效设计",
        "吉他", "贝斯", "貝斯", "钢琴", "鋼琴", "弦乐", "弦樂", "管乐", "合成器", "编程", "編程", "鼓",
        "封面", "封面设计", "封面摄影", "平面设计", "摄影", "艺人", "艺人经纪", "经纪", "项目统筹",
        "统筹", "宣传", "版权", "后期", "母带处理", "录音助理", "助理", "制作助理", "编配", "配器",
        // 英文
        "op", "sp", "lyrics", "lyrics by", "lyric", "words", "words by", "word",
        "music", "music by", "composed", "composed by", "composer", "arranged", "arranged by",
        "arrangement", "arranger", "producer", "produced", "produced by", "production",
        "mixed", "mix", "mixing", "mixed by", "recording", "recorded", "recorded by",
        "recording engineer", "engineer", "engineering", "mastered", "mastered by", "mastering",
        "vocal", "vocals", "backing vocal", "backing vocals", "guitar", "bass", "drums", "piano",
        "strings", "synthesizer", "keyboard", "choir", "studio", "publishing", "publisher",
        "distribution", "distributed by", "marketing", "photography", "cover art", "illustration"
    )
}
