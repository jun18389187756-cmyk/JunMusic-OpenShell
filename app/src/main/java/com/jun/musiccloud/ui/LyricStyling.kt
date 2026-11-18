package com.jun.musiccloud.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.jun.musiccloud.data.LyricLine

/**
 * 舞台字号令牌：译文/音译/下一句预览的唯一来源。
 *
 * 对应 Folia 的 `subtitleFontSizes.ts`：所有模式共用同一组 clamp，
 * 换舞台时字幕不会突然变大变小；下限保证窄屏读得出，上限避免宽屏顶到边。
 */
internal object LyricType {
    fun translation(base: Float): Float = (base * 0.62f).coerceIn(10.5f, 19f)
    fun romanization(base: Float): Float = (base * 0.50f).coerceIn(9.5f, 16f)
    fun upcoming(base: Float): Float = (base * 0.46f).coerceIn(9f, 15f)
}

internal data class KeywordRule(
    val text: String,
    val color: Color,
    val priority: Int
)

/**
 * 关键词着色：不写死词表，从整首歌里找反复出现的短语。
 *
 * 对应 Folia 的 `wordColoring.ts` 的 range-based 思路：
 * - CJK 取 2–4 字重复片段，英文取长度 ≥4 的重复单词；
 * - 出现次数越多、片段越长，优先级越高；
 * - 重叠区间只保留优先级最高的一个，避免两种颜色叠在同一段字上。
 */
internal object LyricKeywordStyler {

    fun buildPalette(lines: List<LyricLine>, accent: Color, secondary: Color): List<KeywordRule> {
        if (lines.size < 2) return emptyList()
        val cjk = HashMap<String, Int>()
        val latin = HashMap<String, Int>()

        for (line in lines.take(120)) {
            val text = line.text
            if (text.isBlank()) continue
            // CJK 连续段：2–4 字 n-gram
            var i = 0
            while (i < text.length) {
                if (!isCjk(text[i])) { i++; continue }
                var j = i
                while (j < text.length && isCjk(text[j])) j++
                val run = text.substring(i, j)
                for (n in 2..4) {
                    if (run.length < n) break
                    for (s in 0..run.length - n) {
                        val key = run.substring(s, s + n)
                        cjk[key] = (cjk[key] ?: 0) + 1
                    }
                }
                i = j
            }
            // 英文单词
            for (word in text.split(Regex("[^A-Za-z0-9']+"))) {
                val w = word.trim().lowercase()
                if (w.length < 4) continue
                latin[w] = (latin[w] ?: 0) + 1
            }
        }

        val picked = ArrayList<Pair<String, Int>>()
        for ((word, count) in cjk) {
            if (count >= 2) picked.add(word to count * word.length)
        }
        for ((word, count) in latin) {
            if (count >= 2) picked.add(word to count * minOf(word.length, 6))
        }
        if (picked.isEmpty()) return emptyList()
        picked.sortByDescending { it.second }

        val rules = ArrayList<KeywordRule>(8)
        val colors = listOf(accent, secondary)
        for ((word, score) in picked) {
            if (rules.size >= 8) break
            // 已经被更长的高优先级词完全包含，就不再单独上色
            if (rules.any { it.text.contains(word, ignoreCase = true) }) continue
            rules.add(
                KeywordRule(
                    text = word,
                    color = colors[rules.size % colors.size],
                    priority = score
                )
            )
        }
        return rules
    }

    fun annotate(text: String, rules: List<KeywordRule>): AnnotatedString {
        if (text.isBlank() || rules.isEmpty()) return AnnotatedString(text)
        val marks = ArrayList<IntArray>() // [start, end, ruleIndex]
        for ((index, rule) in rules.withIndex()) {
            if (rule.text.isBlank()) continue
            var from = 0
            while (from <= text.length - rule.text.length) {
                val at = text.indexOf(rule.text, from, ignoreCase = true)
                if (at < 0) break
                marks.add(intArrayOf(at, at + rule.text.length, index))
                from = at + rule.text.length
            }
        }
        if (marks.isEmpty()) return AnnotatedString(text)
        // 优先级高的先占位，后面的重叠区间直接丢弃
        marks.sortByDescending { rules[it[2]].priority }
        val taken = ArrayList<IntRange>()
        val chosen = ArrayList<IntArray>()
        for (m in marks) {
            val range = m[0] until m[1]
            if (taken.any { it.first < range.last + 1 && range.first < it.last + 1 }) continue
            taken.add(range)
            chosen.add(m)
        }
        if (chosen.isEmpty()) return AnnotatedString(text)
        chosen.sortBy { it[0] }
        return buildAnnotatedString {
            append(text)
            for (m in chosen) {
                addStyle(
                    SpanStyle(color = rules[m[2]].color),
                    start = m[0],
                    end = m[1]
                )
            }
        }
    }

    private fun isCjk(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || c.code in 0x3040..0x30FF || c.code in 0xAC00..0xD7AF
}
