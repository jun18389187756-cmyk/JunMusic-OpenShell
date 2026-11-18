package com.jun.musiccloud.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.LockScreenActivity
import com.jun.musiccloud.data.LockScreenPrefs
import com.jun.musiccloud.data.LockScreenStyle
import com.jun.musiccloud.playback.FloatingLyric
import com.jun.musiccloud.playback.MusicService
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.readableOn

/**
 * 锁屏歌词样式页（6.9.10 新增）。
 *
 * 照着用户给的酷狗截图做：顶部开关 → 五个样式卡 → 底部"正在使用 + 立即查看"。
 * 卡片里是**真的迷你锁屏**（时间、封面块、歌词行都是按样式画的），
 * 不是一张静态截图，换主题色/换样式时预览会跟着变。
 */
@Composable
fun LockScreenLyricSheet(
    colors: JunColors,
    onDismiss: () -> Unit,
    /** 底部迷你播放器高度：面板整体抬上去，否则"正在使用 / 立即查看"被压住点不到 */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("jun_prefs", Context.MODE_PRIVATE) }
    val drag = rememberSheetDragState()

    var enabled by remember {
        mutableStateOf(prefs.getBoolean(MusicService.KEY_LOCK_SCREEN_PLAYER, true))
    }
    var style by remember { mutableStateOf(LockScreenPrefs.style(ctx)) }
    var customBg by remember { mutableStateOf(LockScreenPrefs.customBackground(ctx)) }
    var granted by remember { mutableStateOf(FloatingLyric.hasPermission(ctx)) }

    // 返回键兜底：主界面那层 BackHandler 已经处理了，这里再挂一层，
    // 走到任何奇怪的层级组合（设置页刚关、动画还没结束）都不会"一路退回主页"。
    androidx.activity.compose.BackHandler { onDismiss() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val s = uri.toString()
            LockScreenPrefs.setCustomBackground(ctx, s)
            customBg = s
            style = LockScreenStyle.CUSTOM
            LockScreenPrefs.setStyle(ctx, LockScreenStyle.CUSTOM)
            Haptics.click()
        }
    }

    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        panelModifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.90f),
        bottomInset = bottomInset,
        contentModifier = Modifier
            .padding(horizontal = 20.dp)
            // 小屏（640dp 高）放不下整页，整体可滚；面板高度仍锁在 94%
            .verticalScroll(rememberScrollState())
    ) {
        SheetDragHandle(drag, onDismiss, colors.card)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("锁屏歌词", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            Text(
                "全部样式",
                color = colors.primary.readableOn(colors.surface, 3.5f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(JunShape.item)
                    .background(colors.primary.copy(alpha = 0.10f))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "亮屏未解锁时盖在锁屏上，解锁后自动收起",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(14.dp))

        // ---- 总开关 ----
        Row(
            Modifier
                .fillMaxWidth()
                .clip(JunShape.card)
                .background(colors.surface.copy(alpha = if (colors.isDark) 0.55f else 0.72f))
                .border(1.dp, colors.primary.copy(alpha = 0.12f), JunShape.card)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("锁屏歌词", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        !enabled -> "已关闭 · 锁屏仍显示系统通知"
                        // 锁屏用的是 Activity 的 showWhenLocked，本身不需要悬浮窗权限；
                        // 只有"桌面歌词"才需要。旧版把它写成硬性前置条件，
                        // 用户没授权就以为整个功能坏了（"一点效果都没有"）。
                        !granted -> "已开启 · 亮屏时盖在锁屏上（桌面歌词另需悬浮窗权限）"
                        else -> "已开启 · 锁屏时显示歌词与播放控制"
                    },
                    color = colors.subText, fontSize = 10.sp
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { next ->
                    enabled = next
                    prefs.edit().putBoolean(MusicService.KEY_LOCK_SCREEN_PLAYER, next).apply()
                    Haptics.tap()
                    // 打开锁屏歌词后顺手把桌面歌词的悬浮窗权限也引导一次，
                    // 但**不阻塞**：锁屏页是 Activity，不走悬浮窗。
                    if (next && !granted) {
                        runCatching { FloatingLyric.requestPermission(ctx) }
                        granted = FloatingLyric.hasPermission(ctx)
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.primary,
                    uncheckedThumbColor = Color.White.copy(alpha = 0.85f),
                    uncheckedTrackColor = colors.subText.copy(alpha = 0.28f),
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }

        if (enabled && !granted) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(JunShape.item)
                    .background(colors.primary.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "桌面歌词需要悬浮窗权限（锁屏歌词不需要）",
                    fontSize = 10.5.sp, color = colors.subText,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "去授权 ›",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.primary,
                    modifier = Modifier.junPressable {
                        runCatching { FloatingLyric.requestPermission(ctx) }
                        granted = FloatingLyric.hasPermission(ctx)
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- 样式网格：三列（与酷狗截图一致）----
        // 两列时单卡约 154dp 宽、aspect 0.62 → 248dp 高，三行要 744dp，
        // 在 94% 高度的面板里直接溢出。三列后 98dp 宽、158dp 高，两行刚好放得下。
        LockScreenStyle.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { s ->
                    StyleCard(
                        style = s,
                        selected = style == s,
                        colors = colors,
                        customBg = customBg,
                        onPick = {
                            Haptics.click()
                            if (s == LockScreenStyle.CUSTOM && customBg == null) {
                                runCatching { picker.launch(arrayOf("image/*")) }
                            } else {
                                style = s
                                LockScreenPrefs.setStyle(ctx, s)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(14.dp))

        // ---- 底部：正在使用 + 立即查看 ----
        Row(
            Modifier
                .fillMaxWidth()
                .clip(JunShape.card)
                .background(colors.surface.copy(alpha = if (colors.isDark) 0.60f else 0.78f))
                .border(1.dp, colors.primary.copy(alpha = 0.14f), JunShape.card)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(width = 40.dp, height = 56.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) { StylePreview(style, colors, customBg) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("正在使用", color = colors.subText, fontSize = 10.sp)
                Text(style.label, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Black)
                if (style == LockScreenStyle.CUSTOM && customBg != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "清除这张背景图 →",
                        color = colors.subText, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable {
                                Haptics.click()
                                LockScreenPrefs.setCustomBackground(ctx, "")
                                customBg = null
                                style = LockScreenStyle.ARTIST
                                LockScreenPrefs.setStyle(ctx, LockScreenStyle.ARTIST)
                            }
                            .padding(vertical = 2.dp)
                    )
                }
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.primary)
                    .clickable {
                        Haptics.click()
                        runCatching {
                            ctx.startActivity(
                                Intent(ctx, LockScreenActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    .putExtra(LockScreenActivity.EXTRA_PREVIEW, true)
                            )
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            ) {
                Text("立即查看", color = Color(0xFF101014), fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun StyleCard(
    style: LockScreenStyle,
    selected: Boolean,
    colors: JunColors,
    customBg: String?,
    onPick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface.copy(alpha = if (colors.isDark) 0.45f else 0.65f))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.primary else colors.primary.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable { onPick() }
        ) {
            StylePreview(style, colors, customBg)
            if (style == LockScreenStyle.CUSTOM && customBg == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("+", color = colors.primary, fontSize = 34.sp, fontWeight = FontWeight.Light)
                }
            }
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", color = Color(0xFF101014), fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            style.label,
            color = if (selected) colors.primary.readableOn(colors.surface, 4.0f) else colors.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp)
        )
        Text(
            style.desc,
            color = colors.subText,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, top = 1.dp)
        )
    }
}

/** 迷你锁屏：按样式给出不同的底色、时间位置和歌词排版。 */
@Composable
private fun StylePreview(style: LockScreenStyle, colors: JunColors, customBg: String?) {
    val accent = colors.primary
    when (style) {
        LockScreenStyle.ARTIST -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF2A2333), Color(0xFF6E4B7A), Color(0xFF14101A))
                        )
                    )
            ) {
                MiniContent(Color.White, Color.White.copy(alpha = 0.3f))
            }
        }
        LockScreenStyle.ALBUM -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF14232A), Color(0xFF0B0E14)))
                    )
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(top = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MiniClock(Color.White, 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.52f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.75f), Color(0xFF1B2B33))))
                    )
                    Spacer(Modifier.height(8.dp))
                    MiniLyric(Color.White, Color.White.copy(alpha = 0.32f))
                }
            }
        }
        LockScreenStyle.CUSTOM -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (customBg == null) Brush.linearGradient(listOf(Color(0xFF2C2C34), Color(0xFF17171C)))
                        else Brush.linearGradient(listOf(Color(0xFF3B4A6B), Color(0xFF1A1F2E)))
                    )
            ) {
                MiniContent(Color.White, Color.White.copy(alpha = 0.3f))
            }
        }
        LockScreenStyle.BLESSING -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF8E1418), Color(0xFF5A0D12)))
                    )
            ) {
                MiniContent(Color(0xFFFFD98A), Color(0xFFFFD98A).copy(alpha = 0.32f))
            }
        }
        LockScreenStyle.APPLE -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF3A4550), Color(0xFF12161B)))
                    )
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 7.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MiniClock(Color.White, 20.sp)
                    Spacer(Modifier.weight(1f))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                            .padding(6.dp)
                    ) {
                        MiniLyric(Color.White, Color.White.copy(alpha = 0.36f))
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier
                                .fillMaxWidth(0.5f)
                                .height(2.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.7f))
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun MiniContent(primary: Color, secondary: Color) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 7.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MiniClock(primary, 15.sp)
        Spacer(Modifier.weight(1f))
        MiniLyric(primary, secondary)
        Spacer(Modifier.weight(0.7f))
    }
}

@Composable
private fun MiniClock(color: Color, size: androidx.compose.ui.unit.TextUnit) {
    Text(
        "20:34",
        color = color,
        fontSize = size,
        fontWeight = FontWeight.Light,
        letterSpacing = (-0.5).sp
    )
}

@Composable
private fun MiniLyric(primary: Color, secondary: Color) {
    Text("是你给的诠释", color = secondary, fontSize = 4.sp, maxLines = 1, textAlign = TextAlign.Center)
    Spacer(Modifier.height(3.dp))
    Text(
        "我看过",
        color = primary,
        fontSize = 7.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(3.dp))
    Text("城市的夜", color = secondary, fontSize = 4.sp, maxLines = 1, textAlign = TextAlign.Center)
}
