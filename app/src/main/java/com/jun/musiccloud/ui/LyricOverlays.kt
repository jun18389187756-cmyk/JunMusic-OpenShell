package com.jun.musiccloud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.ui.theme.JunColors
import kotlin.math.roundToInt

/**
 * 歌词层帧率监测。
 *
 * 只在调试开关打开时组合。数据源是渲染本身：用 withFrameNanos 逐帧采样，
 * 每秒结算一次平均 FPS 与这一秒里最差的一帧。比任何"估算"都诚实——
 * 掉帧就是掉帧，和当前舞台的实际开销完全对应。
 */
@Composable
internal fun LyricFrameStatsOverlay(
    colors: JunColors,
    modifier: Modifier = Modifier
) {
    var fps by remember { mutableStateOf(0) }
    var worstMs by remember { mutableStateOf(0) }
    var frames by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        var windowStart = 0L
        var last = 0L
        var count = 0
        var worst = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val delta = now - last
                    // 切后台/锁屏回来会有巨大的时间跳变，不计入统计。
                    if (delta in 1L..1_000_000_000L) {
                        count++
                        if (delta > worst) worst = delta
                    }
                }
                last = now
                if (windowStart == 0L) windowStart = now
                val elapsed = now - windowStart
                if (elapsed >= 1_000_000_000L) {
                    fps = (count * 1_000_000_000.0 / elapsed).roundToInt()
                    worstMs = (worst / 1_000_000L).toInt()
                    frames = count
                    count = 0
                    worst = 0L
                    windowStart = now
                }
            }
        }
    }

    val warning = worstMs >= 40
    val accent = if (warning) Color(0xFFFF8A65) else colors.primary
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.46f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Text(
            "FPS $fps · 最差 ${worstMs}ms",
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black
        )
        Text(
            "$frames 帧/秒 · 仅诊断",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 8.sp
        )
    }
}
