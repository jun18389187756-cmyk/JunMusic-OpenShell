package com.jun.musiccloud.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.R

private fun junTypographyOf(family: FontFamily) = Typography(
    displayLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Black,
        fontSize = 34.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    ),
    labelSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.sp
    )
)

/** 默认（系统无衬线）排版 */
val JunTypography = junTypographyOf(FontFamily.SansSerif)

/** 衬线（Noto Serif CJK）排版 */
val JunSerifTypography = junTypographyOf(FontFamily.Serif)

// ==================== 内置自定义字体 ====================
// 四款精选中文字体，均已按「App 源码实际用字 + GB2312 一级字(3755) + 标点符号」裁剪，
// 并做了 glyf 规约（坐标量化 + 冗余轮廓点删除），避免把 APK 撑爆。
// 未裁剪原件与再压缩/回退办法见 tools/subset_fonts.py（改字库后必须重跑那个脚本）。
// 索引与界面、歌词字体设置共用同一套编号，保证"设置里选什么，全局就用什么"。
private val FontChildFun = FontFamily(Font(R.font.font_childfun))
private val FontChillRound = FontFamily(Font(R.font.font_chillround))
private val FontZhiYongDaTong = FontFamily(Font(R.font.font_zhiyongdatong))
private val FontLeMiBoBo = FontFamily(Font(R.font.font_lemibobo))

/**
 * 字体条目：显示名 + 对应 FontFamily。
 * 下标即设置项 uiFont / lyricFont 的取值：
 *  0 系统无衬线（默认，首启即此）
 *  1 衬线
 *  2 ChildFun 童趣体
 *  3 ChillRound 圆体
 *  4 志勇大同体
 *  5 乐米波波体
 */
data class JunFontEntry(val index: Int, val label: String, val family: FontFamily)

val JunFonts: List<JunFontEntry> = listOf(
    JunFontEntry(0, "系统无衬线", FontFamily.SansSerif),
    JunFontEntry(1, "衬线", FontFamily.Serif),
    JunFontEntry(2, "ChildFun 童趣体", FontChildFun),
    JunFontEntry(3, "ChillRound 圆体", FontChillRound),
    JunFontEntry(4, "志勇大同体", FontZhiYongDaTong),
    JunFontEntry(5, "乐米波波体", FontLeMiBoBo)
)

/** 字体索引的合法上限（含），用于 ViewModel 入参 clamp。加字体时只改这里，别在别处写死数字 */
const val FONT_INDEX_MAX = 5

/**
 * 按索引取 FontFamily，越界回退到系统无衬线。
 * 供 Theme、歌词页等需要"按索引切换字体"的地方统一调用。
 */
fun fontFamilyForIndex(index: Int): FontFamily =
    JunFonts.getOrNull(index.coerceIn(0, FONT_INDEX_MAX))?.family ?: FontFamily.SansSerif

/** 按索引取排版（Typography），越界回退系统无衬线排版。 */
fun typographyForIndex(index: Int): Typography =
    junTypographyOf(fontFamilyForIndex(index))