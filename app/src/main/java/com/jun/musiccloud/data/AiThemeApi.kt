package com.jun.musiccloud.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

/**
 * AI 情绪主题。
 *
 * 优先请求自有服务器 /api/ai-theme（按歌名/歌手/专辑做语义配色，服务端可接入模型）；
 * 网络不可用时用本地情绪调色板兜底，保证按钮永远有结果，不会转圈后什么都不发生。
 */
object AiThemeApi {

    data class Theme(
        val name: String,
        val primaryArgb: Long,
        val secondaryArgb: Long,
        val accentArgb: Long,
        val reason: String = ""
    )

    suspend fun generate(song: Song?, extraMood: String = ""): Theme? = withContext(Dispatchers.IO) {
        val title = song?.title.orEmpty()
        val artist = song?.artist.orEmpty()
        val album = song?.album.orEmpty()
        val remote = runCatching {
            val body = JSONObject()
                .put("title", title)
                .put("artist", artist)
                .put("album", album)
                .put("mood", extraMood)
            ServerApi.request("/api/ai-theme", body)
        }.getOrNull()
        if (remote != null && remote.optInt("code", -1) == 0) {
            val d = remote.optJSONObject("data")
            val p = parseColor(d?.optString("primary").orEmpty())
            val s = parseColor(d?.optString("secondary").orEmpty())
            val a = parseColor(d?.optString("accent").orEmpty())
            if (p != null && s != null) {
                return@withContext Theme(
                    name = d?.optString("name").orEmpty().ifBlank { "AI 情绪主题" },
                    primaryArgb = p,
                    secondaryArgb = s,
                    accentArgb = a ?: p,
                    reason = d?.optString("reason").orEmpty()
                )
            }
        }
        localFallback(title, artist, album)
    }

    private fun localFallback(title: String, artist: String, album: String): Theme {
        val seed = (title + "|" + artist + "|" + album).ifBlank { "jun" }
        val palettes = listOf(
            Theme("霓虹夜行", 0xFF22D3EE, 0xFFFF4FA3, 0xFFA78BFA, "city-pop 的霓虹反光"),
            Theme("海盐日落", 0xFFFF8A5B, 0xFF4FD1C5, 0xFFF6C177, "海风与落日之间的暖橙"),
            Theme("晨雾青", 0xFF7DD3C7, 0xFF8FB9A8, 0xFFE8F3EF, "清晨薄雾里的第一口气"),
            Theme("琥珀电台", 0xFFE0A458, 0xFF8C5E3C, 0xFFF2D7A0, "老收音机里的琥珀色"),
            Theme("银河漫游", 0xFF6D8BFF, 0xFFB18CFF, 0xFFE5E9FF, "低频铺开的星云"),
            Theme("雨后薄荷", 0xFF5ED6A0, 0xFF3AAFA9, 0xFFD7F9E9, "雨后街角的薄荷"),
            Theme("玫瑰噪点", 0xFFE85D75, 0xFF9B5DE5, 0xFFFFD6E0, "失真吉他的粉色噪点"),
            Theme("金色余温", 0xFFD4AF37, 0xFFB07D3C, 0xFFFFF0C2, "尾奏留下的金色余温")
        )
        var h = 0
        for (c in seed) h = 31 * h + c.code
        return palettes[abs(h) % palettes.size]
    }

    /** "#RRGGBB" / "#AARRGGBB" / "RRGGBB" → 0xFFRRGGBB */
    private fun parseColor(raw: String): Long? {
        val s = raw.trim().removePrefix("#")
        if (s.length != 6 && s.length != 8) return null
        val v = s.toLongOrNull(16) ?: return null
        return if (s.length == 6) 0xFF000000L or v else v
    }
}
