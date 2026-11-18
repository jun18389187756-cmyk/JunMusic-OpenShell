@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jun.musiccloud.ui

import com.jun.musiccloud.BuildConfig
import com.jun.musiccloud.R
// 6.7.0：定时提醒（系统通知 + 后台服务）
import com.jun.musiccloud.data.ReminderPrefs
import com.jun.musiccloud.playback.ReminderNotification
import com.jun.musiccloud.playback.ReminderScheduler
import com.jun.musiccloud.playback.ReminderService

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

import com.jun.musiccloud.audio.BassHaptics
import com.jun.musiccloud.audio.EqualizerController
import com.jun.musiccloud.audio.FxPreset
import com.jun.musiccloud.playback.DownloadManager
import com.jun.musiccloud.playback.FloatingLyric
import com.jun.musiccloud.playback.LyricWallpaperService
import com.jun.musiccloud.playback.RemoteControlServer
import android.app.WallpaperManager
import android.content.ComponentName
import android.net.Uri
import java.io.File
import android.content.Context
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.content.Intent
import androidx.core.content.ContextCompat
import com.jun.musiccloud.data.SettingsCloud
import com.jun.musiccloud.data.SettingsPresets
import com.jun.musiccloud.data.PrivacyStore
import com.jun.musiccloud.data.RemoteConfigStore
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.jun.musiccloud.data.DataTransfer
import com.jun.musiccloud.data.MusicFolder
import com.jun.musiccloud.data.LyricLine
import com.jun.musiccloud.data.LyricExportManager
import com.jun.musiccloud.data.LyricParser
import com.jun.musiccloud.data.RepeatMode
import com.jun.musiccloud.data.Playlist
import com.jun.musiccloud.data.Playlists
import com.jun.musiccloud.data.Song
import com.jun.musiccloud.data.UpdateChecker
import com.jun.musiccloud.data.lyricKey
import com.jun.musiccloud.data.DownloadStatus
import com.jun.musiccloud.data.DownloadTask
import com.jun.musiccloud.data.formatLabel
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.player.PlayerViewModel
import com.jun.musiccloud.ui.theme.*
import com.jun.musiccloud.ui.theme.LocalJunUiFamily
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
// ==================== 设置面板 ====================

@Composable
internal fun SettingsPanel(
    state: PlayerUiState,
    colors: JunColors,
    viewModel: com.jun.musiccloud.player.PlayerViewModel,
    onPickBackground: () -> Unit,
    sheetDrag: SheetDragState,
    onCheckUpdate: suspend () -> UpdateChecker.Result,
    onDismiss: () -> Unit,
    onPickDownloadPath: () -> Unit = {},
    onOpenDownloadManager: () -> Unit = {},
    /** 选择当前歌曲的自定义封面 */
    onPickCover: () -> Unit = {},
    /** 选择歌词层本地视频背景 */
    onPickVideo: () -> Unit = {},
    /** 重看首启引导（绘本欢迎页 + 聚光灯） */
    onReplayGuide: () -> Unit = {},
    /** 重看本次新功能导览（歌单导入三平台） */
    onFeatureTour: () -> Unit = {},
    /** 查看本次更新公告（6.1.0 起，正文只在本轮的公告对象里，当前是 ui/WhatsNewR19.kt 一处） */
    onWhatsNew: () -> Unit = {},
    /** 打开「锁屏歌词」样式页（酷狗那套：专辑封面 / 歌手写真 / 自定义背景…） */
    onOpenLockScreenStyle: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    var searchOpen by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf(SettingsJumpTarget()) }
    // 主题色直接当文字用，在浅色模式下会翻车：霓虹青/黄落在白色面板上对比度只有 1.5 左右。
    // 分组标题统一走可读修正，深色模式下修正函数会原样返回，不影响原有观感。
    val headerColor = colors.primary.readableOn(colors.surface, 3.5f)
    // 6.6：设置页与自定义中心共用同一套排版密度（JunUiConfig → LocalCompact）。
    // 以前 LocalCompact 只有自定义中心自己在读，用户在「排版」页切档时设置页纹丝不动，
    // 看着就像「只改了局部」。现在设置页的边距也跟着同一倍率走，一改全 App 一起变。
    val compact = LocalCompact.current
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        CompositionLocalProvider(
            LocalSettingsJump provides jump,
            // 6.9.10：设置合并成一个「总设置」页，不再分普通/其他。
            // 用户原话："不用弄其他的了，直接弄成总设置"。这里恒为 true，
            // 所有分组（含原来的高阶项）都在同一页里，靠折叠标题收纳，不再切换 tab。
            LocalSettingsAdvanced provides true
        ) {
        // ===== 可滚动的设置内容 =====
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
                .padding(horizontal = (20 * compact).dp, vertical = (4 * compact).dp)
        ) {
        SheetDragHandle(sheetDrag, onDismiss, colors.card)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("设置", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            Text(
                "搜索",
                color = colors.primary.readableOn(colors.surface, 3.5f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .junPressable { searchOpen = true }
                    .clip(JunShape.item)
                    .background(colors.primary.copy(alpha = 0.12f))
                    .semantics { contentDescription = "搜索设置项" }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        // 单一「总设置」：不再有普通/其他两个切换片，一眼看到页内总项数。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "总设置",
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${SettingsGroupCounters.advanced()} 项",
                color = colors.primary.readableOn(colors.surface, 3.5f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(JunShape.item)
                    .background(colors.primary.copy(alpha = 0.10f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "全部功能都在这一页：点分组标题展开，右侧搜索可以直接跳到任意一项",
            color = colors.subText, fontSize = 10.sp, lineHeight = 14.sp
        )
        Spacer(Modifier.height(14.dp))

        // ==================== 6.7：一级快捷区 ====================
        // 用户反馈「通知栏歌词开关我一点没看到」——它原来埋在
        // 「播放与歌词 → 歌词 → 字号/预览」那一串折叠组的最下面，等于没有。
        // 这里提到设置首页第一条，同时给自定义中心一个一眼可见的入口。
        val panelCtx = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(Unit) { CustomConfig.init(panelCtx) }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(JunShape.card)
                .background(colors.primary.copy(alpha = 0.10f))
                .junPressable { CustomConfig.showDialog.value = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("自定义中心", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Black)
                Text(
                    "首页模块 · 背景与深度 · 配色材质 · 小播放器 · 侧滑与入口，都在一处改",
                    color = colors.subText, fontSize = 10.5.sp, lineHeight = 14.sp
                )
            }
            Text(
                "打开 ›",
                color = colors.primary.readableOn(colors.surface, 3.5f),
                fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(10.dp))
        SettingToggleRow(
            "通知栏显示歌词",
            CustomConfig.notificationLyric.value,
            colors,
            subtitle = "下拉通知栏的播放卡片里显示当前这一句；车机 / 蓝牙同样生效（部分 ROM 只显示歌名，属系统模板限制）"
        ) {
            CustomConfig.setNotificationLyric(panelCtx, !CustomConfig.notificationLyric.value)
        }
        Spacer(Modifier.height(14.dp))

        // ==================== 外观 ====================
        SettingsGroup("外观与界面", colors, listOf("外观")) {
        CollapsibleSection("外观", colors) {

        FoldGroup("主题与自定义配色", colors, "明暗模式 · 主题 · 自定义主色/次色") {
        // ---- 明暗模式 ----
        Text("明暗模式", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeSwatch("深色", state.mode == JunMode.DARK, colors,
                modifier = Modifier.weight(1f)) { viewModel.setMode(JunMode.DARK) }
            ModeSwatch("浅色", state.mode == JunMode.LIGHT, colors,
                modifier = Modifier.weight(1f)) { viewModel.setMode(JunMode.LIGHT) }
            ModeSwatch("自动", state.mode == JunMode.AUTO, colors,
                modifier = Modifier.weight(1f)) { viewModel.setMode(JunMode.AUTO) }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 主题 ----
        Text("主题", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(JunTheme.MONO, JunTheme.OCEAN, JunTheme.GRASS, JunTheme.XIAOMEI, JunTheme.CUSTOM).chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { theme ->
                        ThemeSwatch(
                            theme = theme,
                            selected = state.theme == theme,
                            colors = colors,
                            onClick = { viewModel.setTheme(theme) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        AnimatedVisibility(
            visible = state.theme == JunTheme.CUSTOM,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            // 只有选了「自定义」主题才展开调色区：进入时 CustomColorSection 重新组合，
            // HSV 从外部 argb 同步；离开时整段移出组合树，避免常显死控件。
            Column {
                Spacer(Modifier.height(14.dp))
                // ---- 自定义配色（紧跟主题）----
                Text("自定义配色", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "拖动滑块调配主色与次色，立即生效",
                    color = colors.subText, fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))
                CustomColorSection(
                    primaryArgb = state.customPrimaryArgb,
                    secondaryArgb = state.customSecondaryArgb,
                    colors = colors,
                    onColorsChange = viewModel::setCustomColors
                )
            }
        }
        }

        FoldGroup("界面材质与模糊", colors, "实色 / 毛玻璃 / 液态玻璃 · 模糊浓度 · 背景模糊") {
        // ---- 表面材质 ----
        // 毛玻璃从独立开关并入这里：三者本质是同一维度（浮层背后怎么处理），
        // 拆成"材质选择 + 毛玻璃开关"会出现"实色 + 毛玻璃""液态玻璃 + 毛玻璃"这类
        // 语义打架的组合，后者还会把折射素材糊掉。
        Text("界面材质", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            when (state.surfaceStyle) {
                JunSurfaceStyle.SOLID -> "实色：不透明卡片，对比强、最省电"
                JunSurfaceStyle.FROSTED -> "毛玻璃：上栏与歌词层背景模糊，卡片仍是实色"
                JunSurfaceStyle.LIQUID -> "液态玻璃：真折射取样 + 色散 + 镜面内缘，最费性能"
            },
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JunSurfaceStyle.entries.forEach { style ->
                ModeSwatch(
                    style.label,
                    state.surfaceStyle == style,
                    colors,
                    modifier = Modifier.weight(1f)
                ) { viewModel.setSurfaceStyle(style) }
            }
        }

        // ---- 模糊浓度 ----
        // 只在毛玻璃/液态玻璃下出现：实色没有可调的模糊，摆着是死控件。
        AnimatedVisibility(
            visible = state.surfaceStyle != JunSurfaceStyle.SOLID,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(top = 12.dp)) {
                Text(
                    "模糊浓度 · ${"%.1f".format(state.glassBlur)}×",
                    color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        state.glassBlur < 0.55f -> "清透：几乎能看清背后内容，最省电"
                        state.glassBlur < 0.9f -> "轻薄：淡淡一层雾"
                        state.glassBlur < 1.25f -> "标准（推荐）"
                        state.glassBlur < 1.6f -> "厚重：背景只剩色块"
                        else -> "极厚：糊成纯色，最费性能"
                    },
                    color = colors.subText, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                CompactSlider(
                    value = state.glassBlur,
                    onValueChange = { viewModel.setGlassBlur(it) },
                    valueRange = GLASS_BLUR_MIN..GLASS_BLUR_MAX,
                    activeColor = colors.primary,
                    inactiveColor = colors.surface
                )
            }
        }

        // ---- 背景模糊 ----
        // 独立于「模糊浓度」：那个管浮层背后的玻璃，这个管最底层封面本身糊多少，
        // 实色材质下也生效，所以不放在上面那个 AnimatedVisibility 里。
        Column(Modifier.padding(top = 14.dp)) {
            Text("背景模糊 · ${state.bgBlur.toInt()}dp", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    state.bgBlur < 4f -> "关闭：封面原图直接当背景，最清晰也最抢眼"
                    state.bgBlur < 18f -> "轻微：还看得出封面构图"
                    state.bgBlur < 34f -> "适中：只剩色块与轮廓"
                    state.bgBlur < 50f -> "标准（推荐）：纯色氛围底"
                    else -> "极致：几乎是一片纯色"
                },
                color = colors.subText, fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            CompactSlider(
                value = state.bgBlur,
                onValueChange = { viewModel.setBgBlur(it) },
                valueRange = BG_BLUR_MIN..BG_BLUR_MAX,
                activeColor = colors.primary,
                inactiveColor = colors.surface
            )
        }

        Spacer(Modifier.height(14.dp))

        }

        FoldGroup("背景、封面与圆角", colors, "自定义背景 · 当前歌曲封面 · 界面圆角") {
        // ---- 自定义背景 ----
        Text("自定义背景", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            if (state.customBackgroundUri != null) "已使用自选图片做背景（仍受上方「背景模糊」影响）"
            else "不设则背景跟随当前专辑封面",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .junPressable { onPickBackground() }
                    .clip(JunShape.item)
                    .background(if (state.theme == JunTheme.CUSTOM) colors.primary else colors.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text("选择图片",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (state.theme == JunTheme.CUSTOM) colors.primary.readableTextOn() else colors.primary)
            }
            if (state.customBackgroundUri != null) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .junPressable { viewModel.setCustomBackground(null) }
                        .clip(JunShape.item)
                        .background(colors.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Text("恢复封面", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.text)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 当前歌曲自定义封面 ----
        val coverKey = state.currentSong?.filePath.orEmpty()
        // version 变化 = 刚换/删过封面，重新查映射并刷新按钮状态。
        CustomCoverStore.version
        val hasCustomCover = coverKey.isNotBlank() && CustomCoverStore.hasOverride(coverKey)
        var showCoverManager by remember { mutableStateOf(false) }
        FoldGroup(
            "当前歌曲封面",
            colors,
            if (hasCustomCover) "已替换 · 点开可重新裁剪或恢复原封面" else "选图后裁剪成正方形，全部界面同步"
        ) {
            Text(
                state.currentSong?.let { "当前：${it.title} - ${it.artist}" } ?: "先播放一首歌再设置封面",
                color = colors.subText, fontSize = 11.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .junPressable(enabled = coverKey.isNotBlank()) {
                            onPickCover()
                        }
                        .clip(JunShape.item)
                        .background(colors.primary.copy(alpha = if (coverKey.isBlank()) 0.08f else 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (hasCustomCover) "重新裁剪" else "选择图片",
                        color = if (coverKey.isBlank()) colors.subText else colors.primary,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
                if (hasCustomCover) {
                    Box(
                        Modifier
                            .weight(0.8f)
                            .height(40.dp)
                            .junPressable {
                                CustomCoverStore.clear(coverKey)
                            }
                            .clip(JunShape.item)
                            .background(colors.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("恢复原封面", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .junPressable { showCoverManager = true }
                    .clip(JunShape.item)
                    .background(colors.surface.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "管理已替换封面（${CustomCoverStore.entries().size} 张）",
                    color = colors.text, fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
            }
            if (showCoverManager) {
                CoverManagerDialog(
                    songs = state.allSongs,
                    colors = colors,
                    onDismiss = { showCoverManager = false }
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 界面圆角 ----
        Text("界面圆角", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                state.cornerBase < 4f -> "直角：锐利硬朗"
                state.cornerBase < 16f -> "微圆：克制内敛"
                state.cornerBase < 30f -> "标准圆角"
                state.cornerBase < 40f -> "大圆角：柔和饱满（推荐）"
                else -> "极圆：接近胶囊"
            } + " · ${state.cornerBase.toInt()}dp",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        CompactSlider(
            value = state.cornerBase,
            onValueChange = { viewModel.setCornerBase(it) },
            valueRange = 0f..44f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )
        Spacer(Modifier.height(14.dp))

        }

        FoldGroup("字体与字号", colors, "全局字号 · 全局字体（界面/歌词/14 舞台统一）") {
        // ---- 字号大小 ----
        // 拖动时只改本地值，松手才提交全局——否则每次 onValueChange 都切 Density，
        // 触发整树重测量打断手势，表现为"拉不动"。
        var pendingFontScale by remember(state.fontScale) { mutableStateOf(state.fontScale) }
        Text("字号大小", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                pendingFontScale < 0.92f -> "偏小：紧凑省地"
                pendingFontScale < 1.05f -> "标准（推荐）"
                pendingFontScale < 1.18f -> "偏大：清晰易读"
                else -> "特大：醒目护眼"
            } + " · ${(pendingFontScale * 100).toInt()}%",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        CompactSlider(
            value = pendingFontScale,
            onValueChange = { pendingFontScale = it },
            onValueChangeFinished = { viewModel.setFontScale(pendingFontScale) },
            valueRange = 0.85f..1.30f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )
        Spacer(Modifier.height(14.dp))

        // ---- 全局字体 ----
        Text("全局字体", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "整个 App 统一字体：界面、列表、歌词与全部 14 个舞台一起切换，点击立即生效",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        FontSelector(state.uiFont, colors) { viewModel.setUiFont(it) }
        Spacer(Modifier.height(14.dp))

        }

        FoldGroup("界面排布与列表", colors, "列表 / 卡片 / 堆叠 / 唱片墙 · 行样式") {
        // ---- 界面排布 ----
        // 和材质、配色一样是独立一维：这里决定"整个 App 长什么结构"。
        // 卡片模式下歌单和上栏都不存在，所以下面那些歌单相关的设置项要跟着藏起来。
        Text("界面排布", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            when (state.layout) {
                JunLayout.LIST -> "纵向排布：歌单列表 + 右滑播放页，原有结构"
                JunLayout.CAROUSEL -> "横向排布：只剩播放卡片，左右滑动翻歌"
                JunLayout.STACK -> "堆叠排布：卡片叠成一沓，把最上面那张拖走切歌"
                JunLayout.COMPACT -> "唱片墙：正方形/长方形磁贴组成画布，可自由拖动探索"
            },
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JunLayout.entries.forEach { layout ->
                LayoutSwatch(
                    layout = layout,
                    selected = state.layout == layout,
                    colors = colors,
                    modifier = Modifier.weight(1f)
                ) { viewModel.setLayout(layout) }
            }
        }

        // 唱片墙方向：只在「唱片墙」排布下出现，纵向 / 横向一键切换。
        AnimatedVisibility(
            visible = state.layout == JunLayout.COMPACT,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                Text("唱片墙方向", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (state.wallHorizontal) "横向：宽画布，左右拖动探索，长条卡也横过来"
                    else "纵向：竖向画布，上下拖动探索（默认）",
                    color = colors.subText, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeSwatch("纵向", !state.wallHorizontal, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setWallHorizontal(false) }
                    ModeSwatch("横向", state.wallHorizontal, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setWallHorizontal(true) }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 歌单行大小 / 行封面 ----
        // 卡片模式下没有歌单，这两项摆着是死控件
        AnimatedVisibility(
            visible = !state.layout.isDeck,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Text("列表行样式", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeSwatch("紧凑", state.rowSize == 0, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setRowSize(0) }
                    ModeSwatch("标准", state.rowSize == 1, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setRowSize(1) }
                    ModeSwatch("宽松", state.rowSize == 2, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setRowSize(2) }
                }

                Spacer(Modifier.height(14.dp))

                SettingToggleRow("歌单行显示封面", state.showRowCover, colors) { viewModel.setShowRowCover(!state.showRowCover) }

                Spacer(Modifier.height(10.dp))
                SettingToggleRow("歌单行显示歌手", state.showRowArtist, colors,
                    subtitle = "关闭后歌单行只显示歌名") { viewModel.setShowRowArtist(!state.showRowArtist) }

                Spacer(Modifier.height(10.dp))
                SettingToggleRow("歌单行显示所属歌单", state.showRowPlaylist, colors,
                    subtitle = "关闭后不再显示歌曲所在的收藏歌单") { viewModel.setShowRowPlaylist(!state.showRowPlaylist) }

                Spacer(Modifier.height(10.dp))
                SettingToggleRow("搜索框常驻", state.searchPersistent, colors,
                    subtitle = "关闭后上栏收起搜索框，点标题展开，闲置几秒自动收起") { viewModel.setSearchPersistent(!state.searchPersistent) }

                Spacer(Modifier.height(10.dp))
                SettingToggleRow("小播放器常驻", state.miniPlayerPersist, colors,
                    subtitle = "开启后底部小播放器始终显示，关闭则长时间不用时自动变淡") { viewModel.setMiniPlayerPersist(!state.miniPlayerPersist) }

                Spacer(Modifier.height(10.dp))
                SettingToggleRow("歌单行显示点赞角标", state.showLikedBadge, colors,
                    subtitle = "已点赞的歌曲在右下角显示爱心") { viewModel.setShowLikedBadge(!state.showLikedBadge) }
            }
        }

        }

        Spacer(Modifier.height(18.dp))

        } // 结束外观

        } // 结束「外观与界面」

        SettingsGroup("播放与歌词", colors, listOf("播放", "歌词")) {

        // 6.9.7：锁屏全屏音乐界面（用户点名要的"酷我那种整个锁屏都是音乐"）。
        // 放在「播放与歌词」分组最顶部、不折进任何二级区 —— 用户反馈过三次"根本没这个开关"，
        // 这次进入分组第一眼就能看到。
        LockScreenPlayerSection(colors, headerColor, onOpenLockScreenStyle)
        Spacer(Modifier.height(14.dp))

        // 6.9.8：桌面歌词提到分组顶层 —— 用户第三次反馈"桌面歌词根本都没有"，
        // 因为开关一直折在「歌词」子区里翻不到。与锁屏音乐并排放在最顶上。
        FloatingLyricSection(colors, headerColor)
        Spacer(Modifier.height(14.dp))

        // ==================== 播放 ====================
        CollapsibleSection("播放", colors) {

        FoldGroup("播放与过渡", colors, "淡入淡出 · 无声略过 · 上栏自动隐藏 · 点歌展开") {
        SettingToggleRow(
            "淡入淡出",
            state.fadeEnabled,
            colors,
            subtitle = "开头音量渐起、结尾渐落，暂停也不再突兀"
        ) { viewModel.setFadeEnabled(!state.fadeEnabled) }

        // 时长滑块只在开启时出现：关闭状态下它没有意义，摆着反而干扰
        AnimatedVisibility(
            visible = state.fadeEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                Text(
                    "渐变时长 · ${"%.1f".format(state.fadeMs / 1000f)} 秒",
                    color = colors.subText, fontSize = 11.sp
                )
                Spacer(Modifier.height(4.dp))
                CompactSlider(
                    value = state.fadeMs.toFloat(),
                    onValueChange = { viewModel.setFadeMs(it.toLong()) },
                    valueRange = 500f..5000f,
                    activeColor = colors.primary,
                    inactiveColor = colors.surface
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- 无声略过（beta） ----
        SettingToggleRow(
            "无声略过 · beta",
            state.silenceSkip,
            colors,
            subtitle = "自动跳过歌曲开头和结尾没有声音的部分"
        ) { viewModel.setSilenceSkip(!state.silenceSkip) }

        Spacer(Modifier.height(10.dp))

        SettingToggleRow(
            "上栏静止时隐藏",
            state.topBarAutoHide,
            colors,
            subtitle = "静止后上栏缓缓淡到 10% 残影（不是彻底消失），碰一下即回"
        ) { viewModel.setTopBarAutoHide(!state.topBarAutoHide) }

        // 6.9.12：隐藏时长的档位选择。旧版写死 5 秒，切到横向/堆叠/唱片墙
        // "上面那排一会儿就没了"，根本来不及点。现在默认 10 秒、可调 5~30 秒。
        if (state.topBarAutoHide) {
            Spacer(Modifier.height(8.dp))
            Text(
                "静止 ${state.topBarHideSeconds} 秒后开始淡出，淡出过程约 1.8 秒",
                color = colors.subText, fontSize = 10.5.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(5, 8, 10, 15, 20, 30).forEach { sec ->
                    val on = state.topBarHideSeconds == sec
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(JunShape.chip)
                            .background(
                                if (on) colors.primary.copy(alpha = 0.18f)
                                else colors.surface.copy(alpha = 0.35f)
                            )
                            .border(
                                1.dp,
                                if (on) colors.primary.copy(alpha = 0.50f) else Color.Transparent,
                                JunShape.chip
                            )
                            .junPressable { Haptics.tap(); viewModel.setTopBarHideSeconds(sec) }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${sec}s",
                            color = if (on) colors.primary else colors.subText,
                            fontSize = 11.sp,
                            fontWeight = if (on) FontWeight.Black else FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 点歌后是否直接把播放页推上来。开关接的是分页器同一条动画，
        // 所以"自动展开"和手指上滑看到的形变是同一个过程。
        SettingToggleRow(
            "点歌直接展开播放器",
            state.autoOpenPlayer,
            colors,
            subtitle = "关闭后点歌只起播，底部小播放器保持收起，列表不跳走"
        ) { viewModel.setAutoOpenPlayer(!state.autoOpenPlayer) }

        Spacer(Modifier.height(10.dp))

        }

        FoldGroup("图书馆模式", colors, "轻音乐 · 纯音乐 · 白噪音 · lofi") {
            SettingToggleRow(
                "图书馆模式",
                state.libraryMode,
                colors,
                subtitle = if (state.libraryFiltering) {
                    "正在筛选纯音乐…找到的会按顺序加入队列"
                } else {
                    "开启后只播放轻音乐 / 纯音乐 / 白噪音 / lofi，带歌词的歌在入队前就被筛掉"
                }
            ) { viewModel.setLibraryMode(!state.libraryMode) }
            Spacer(Modifier.height(8.dp))
            Text(
                "判断以歌词为准：纯音乐、白噪音、lofi 通常没有歌词，会正常播放；" +
                    "带人声歌词的歌会在后台提前移出队列，播放过程中不会跳歌，" +
                    "点下一首就是自然地播下一首。也可以在「我的」抽屉里一键开关。",
                color = colors.subText,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }

        FoldGroup("音效预设", colors, "EQ + 低音 + 声场 + 限幅整套切换", advanced = true) {
        // ---- 音效预设链 ----
        FxPresetSection(state, colors, headerColor, viewModel)
        }

        FoldGroup("智能 Automix", colors, "曲尾提前淡出，按风格接下一首", advanced = true) {
        // ---- Automix ----
        SettingToggleRow(
            "智能 Automix",
            state.automixEnabled,
            colors,
            subtitle = "曲尾提前淡出并接下一首，减少硬切"
        ) { viewModel.setAutomixEnabled(!state.automixEnabled) }
        AnimatedVisibility(
            visible = state.automixEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("智能淡切", "柔和长淡", "直接切换").forEachIndexed { i, label ->
                        ModeSwatch(label, state.automixStyle == i, colors,
                            modifier = Modifier.weight(1f)) { viewModel.setAutomixStyle(i) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (state.automixStyle) {
                        1 -> "提前 12 秒开始收音量，适合慢歌与氛围电子"
                        2 -> "只做 0.9 秒快速淡出，适合节奏硬的曲目"
                        else -> "提前 6 秒按剩余时间平滑淡出，兼顾清晰与连贯"
                    },
                    color = colors.subText, fontSize = 10.sp, lineHeight = 14.sp
                )
            }
        }

        }

        // ==================== 6.6.2 新增 ====================
        FoldGroup("与其他应用同时播放", colors, "开启后不抢占音频焦点，可与其他 App 混音") {
            SettingToggleRow(
                "与其他应用同时播放",
                state.playWithOthers,
                colors,
                subtitle = "开启后音乐不会因其他 App 发声而暂停/压低，适合边听边看视频"
            ) { viewModel.setPlayWithOthers(!state.playWithOthers) }
        }

        FoldGroup("大播放器控件", colors, "隐藏后功能仍生效，其余控件自动居中重排") {
            SettingToggleRow("隐藏 · 倍速", state.hideSpeed, colors) { viewModel.setHideSpeed(!state.hideSpeed) }
            Spacer(Modifier.height(8.dp))
            SettingToggleRow("隐藏 · 评论", state.hideComment, colors) { viewModel.setHideComment(!state.hideComment) }
            Spacer(Modifier.height(8.dp))
            SettingToggleRow("隐藏 · 音调", state.hidePitch, colors) { viewModel.setHidePitch(!state.hidePitch) }
            Spacer(Modifier.height(8.dp))
            SettingToggleRow("隐藏 · 音质", state.hideQuality, colors) { viewModel.setHideQuality(!state.hideQuality) }
            Spacer(Modifier.height(8.dp))
            SettingToggleRow("隐藏 · 下载", state.hideDownload, colors) { viewModel.setHideDownload(!state.hideDownload) }
            Spacer(Modifier.height(8.dp))
            SettingToggleRow("隐藏 · 喜欢", state.hideLike, colors) { viewModel.setHideLike(!state.hideLike) }
            Spacer(Modifier.height(8.dp))
            SettingToggleRow("隐藏 · 相似推荐", state.hideSimilar, colors) { viewModel.setHideSimilar(!state.hideSimilar) }
            Spacer(Modifier.height(8.dp))
            SettingToggleRow("隐藏 · 最近播放", state.hideRecent, colors) { viewModel.setHideRecent(!state.hideRecent) }
            Spacer(Modifier.height(8.dp))
            SettingToggleRow("隐藏 · 循环播放", state.hideRepeat, colors) { viewModel.setHideRepeat(!state.hideRepeat) }
            Spacer(Modifier.height(8.dp))
            SettingToggleRow("隐藏 · 睡眠定时器", state.hideSleep, colors) { viewModel.setHideSleep(!state.hideSleep) }
        }

        FoldGroup("自定义界面入口", colors, "隐藏后可用长按音量+ / 长按双击屏幕召唤自定义界面") {
            SettingToggleRow(
                "隐藏搜索入口",
                state.hideSearchEntry,
                colors,
                subtitle = "关闭搜索图标，界面更干净"
            ) { viewModel.setHideSearchEntry(!state.hideSearchEntry) }
            Spacer(Modifier.height(8.dp))
            SettingToggleRow(
                "隐藏设置入口",
                state.hideSettingsEntry,
                colors,
                subtitle = "关闭设置图标，需要时用手势召唤"
            ) { viewModel.setHideSettingsEntry(!state.hideSettingsEntry) }
        }

        Spacer(Modifier.height(18.dp))

        } // 结束播放

        // ==================== 歌词 ====================
        CollapsibleSection("歌词", colors) {

        SettingToggleRow(
            "封面歌词",
            state.coverLyric,
            colors,
            subtitle = "封面左下角单行歌词，换句时模糊渐隐渐出"
        ) { viewModel.setCoverLyric(!state.coverLyric) }
        Spacer(Modifier.height(14.dp))

        SettingToggleRow(
            "封面顶部歌词",
            state.coverTopLyric,
            colors,
            subtitle = "封面正上方空白区大字歌词，与封面歌词可同时开启"
        ) { viewModel.setCoverTopLyric(!state.coverTopLyric) }
        Spacer(Modifier.height(14.dp))

        SettingToggleRow(
            "逐字流光",
            state.karaokeLyric,
            colors,
            subtitle = "全屏歌词当前行按进度扫光点亮（无词级时间时按句长估算）"
        ) { viewModel.setKaraokeLyric(!state.karaokeLyric) }
        Spacer(Modifier.height(14.dp))
        Column {
            Text("歌词对齐", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeSwatch("靠左", state.lyricAlign == 0, colors,
                    modifier = Modifier.weight(1f)) { viewModel.setLyricAlign(0) }
                ModeSwatch("居中", state.lyricAlign == 1, colors,
                    modifier = Modifier.weight(1f)) { viewModel.setLyricAlign(1) }
                ModeSwatch("靠右", state.lyricAlign == 2, colors,
                    modifier = Modifier.weight(1f)) { viewModel.setLyricAlign(2) }
            }
        }
        Spacer(Modifier.height(14.dp))

        // ---- 歌词字号 ----
        // 字体已统一到上面的「全局字体」，这里只保留字号。
        Text("歌词字号 · ${state.lyricFontSize.toInt()}sp", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        CompactSlider(
            value = state.lyricFontSize,
            onValueChange = { viewModel.setLyricFontSize(it) },
            valueRange = 14f..32f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )
        Spacer(Modifier.height(14.dp))

        // ---- 6.6：调完立刻能看的实时预览 ----
        LyricSettingsPreview(state, colors)
        Spacer(Modifier.height(14.dp))

        // ---- 6.6：通知栏歌词开关（6.7 起与设置首页那颗共用同一份状态）----
        val notifCtx = LocalContext.current
        SettingToggleRow(
            "通知栏显示歌词",
            CustomConfig.notificationLyric.value,
            colors,
            subtitle = "与设置首页顶部那颗是同一个开关；打开后当前句写进通知卡片"
        ) {
            CustomConfig.setNotificationLyric(notifCtx, !CustomConfig.notificationLyric.value)
        }
        Spacer(Modifier.height(14.dp))

        // ---- 歌词舞台 / 多格式 / 翻译轨 ----
        LyricExtrasSection(state, colors, headerColor, viewModel, onPickVideo)
        Spacer(Modifier.height(14.dp))

        } // 结束歌词

        } // 结束「播放与歌词」

        SettingsGroup("曲库与连接", colors, listOf("本地曲库", "音源插件", "智能与连接")) {

        // ==================== 本地曲库 ====================
        CollapsibleSection("本地曲库", colors) {
            LocalLibrarySection(state, colors, headerColor, viewModel)
        }

        // ==================== 音源插件（6.6） ====================
        // 「不换界面只换音源」的入口：导入 LX 协议的 .js 脚本即可接管播放地址解析。
        // 放在「曲库与连接」里，跟本地曲库平级——用户理解成本最低。
        CollapsibleSection("音源插件", colors) {
            SourcePluginSection(state, colors, headerColor)
        }

        // ==================== 智能与连接 ====================
        // 总设置下不再隐藏：进阶工具也在这页，只是默认收起。
        CollapsibleSection("智能与连接", colors) {
            SmartConnectSection(state, colors, headerColor, viewModel)
        }

        } // 结束「曲库与连接」

        SettingsGroup("体验与动效", colors, listOf("动效与反馈", "推荐")) {

        // ==================== 动效与反馈 ====================
        // 6.3 彻底修复版：用户拍板「设置里面默认的话都不要是展开的，都是收缩状态」。
        // （上一版为了治「找不到过渡动画设置」把它默认展开；这一轮统一收回——
        // 找不到的问题由设置搜索兜底，全部分区默认收起、页面保持清爽。）
        CollapsibleSection("动效与反馈", colors) {

        // ---- 6.3：整体动画快慢（最先看到，不藏在二级折叠里） ----
        // 用户的原话：「想要一个整体过渡动画快慢的开关，小播放器↔大播放器、果冻 Q 弹的
        // 速度都能调，默认稍微快一点，追求极致动画的人可以自己调慢。」
        MotionSpeedPicker(
            colors = colors,
            current = state.motionSpeed,
            onPick = { viewModel.setMotionSpeed(it) }
        )

        Spacer(Modifier.height(10.dp))

        FoldGroup("触感与界面动效", colors, "触感 · 物理动效 · 灵动动效 · 省电") {
        // ---- 触感反馈 ----
        SettingToggleRow(
            "触感反馈",
            state.hapticsEnabled,
            colors,
            subtitle = "按键、切歌、拖动滚动条时轻微震动"
        ) { viewModel.setHapticsEnabled(!state.hapticsEnabled) }

        Spacer(Modifier.height(10.dp))

        // ---- 物理动效 ----
        SettingToggleRow(
            "物理动效 · beta",
            state.tiltSpectrum || state.coverShake,
            colors,
            subtitle = "倾斜频谱 + 摇动封面，传感器驱动"
        ) { viewModel.setPhysicsFx(!(state.tiltSpectrum || state.coverShake)) }

        Spacer(Modifier.height(10.dp))

        // ---- 动效风格（6.6：用户"除了果冻Q弹也弄一个别的效果吧，设置里面能切换"）----
        // 三档：果冻Q弹（原版软弹簧）/ 顺滑（几乎不过冲、快而稳，老机型推荐）/ 超弹（比果冻更荡）。
        // 替代了原来那颗「灵动动效」开关——开关只能表达两态，三档风格把它吃掉了，
        // 原开关的 JELLY 语义由果冻档继承，老用户的设置不丢。
        MotionStylePicker(
            colors = colors,
            current = state.motionStyle,
            onPick = { viewModel.setMotionStyle(it) }
        )

        Spacer(Modifier.height(10.dp))

        // ---- 省电动画 ----
        SettingToggleRow(
            "省电动画",
            state.powerSavingAnim,
            colors,
            subtitle = "减少持续运行的装饰动画（呼吸描边、浮动插画、脉动提示），低端机默认开启"
        ) { viewModel.setPowerSavingAnim(!state.powerSavingAnim) }

        }

        // R13 老机适配（华为 nova 6 / EMUI Android 10 专项）。
        // 判定与降级全在 DevicePerformance（ui/theme/GlassBackdrop.kt），默认对用户不可见：
        // 老机自动关掉这些特效，新机一行都不关。这里给的是**出口**——
        // 用户如果觉得"我的机器明明带得动"，或者某个效果他就是要，可以在这里单项放开。
        FoldGroup(
            "老机安全档 · 自动降级",
            colors,
            "老机器自动关掉最容易出问题的特效（闪屏、锁屏异常、后台被杀）；可强制开关或逐项放开",
            advanced = true
        ) {
            val dp = com.jun.musiccloud.ui.theme.DevicePerformance
            val deviceCtx = LocalContext.current
            // DevicePerformance 是进程内单例、不是 Compose state：写完 prefs 必须自己制造一次重组，
            // 否则拨完开关这一屏还在读旧值，看起来就是"这个开关没反应"。
            // revision 在组合期被读一次（下面每个 allowed() 都带它），拨一下就整组重画。
            val revision = remember { mutableIntStateOf(0) }
            fun allowed(v: Boolean): Boolean = if (revision.intValue >= 0) v else false

            val safeOn = allowed(dp.safeMode)
            val offList = dp.safeModeReport()
            SettingToggleRow(
                "安全档 · 总闸",
                safeOn,
                colors,
                subtitle = if (safeOn) "已关闭：" + offList.joinToString("、")
                           else "本机判定带得动，没有降级；也可以强制打开"
            ) {
                dp.setSafeModeOverride(deviceCtx, if (safeOn) dp.SAFE_FORCE_OFF else dp.SAFE_FORCE_ON)
                revision.intValue = revision.intValue + 1
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "下面每一行都是这一项在这台机器上的实际状态：开着 = 允许运行；关掉 = 跟随总闸。",
                color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp
            )
            Spacer(Modifier.height(6.dp))

            val fxRows = listOf(
                Triple(dp.KEY_FX_VIDEO, "视频背景 · 歌词层", allowed(dp.allowVideoBackdrop)),
                Triple(dp.KEY_FX_BG_BLUR, "背景实时模糊", allowed(dp.allowBackgroundBlur)),
                Triple(dp.KEY_FX_HAPTICS, "鼓点振动", allowed(dp.allowBassHaptics)),
                Triple(dp.KEY_FX_HAPTICS_SCREEN_OFF, "熄屏时也振动（默认关）", allowed(dp.allowHapticsWhenScreenOff)),
                Triple(dp.KEY_FX_KEEP_SCREEN_ON, "播放时屏幕常亮", allowed(dp.allowKeepScreenOn)),
                Triple(dp.KEY_FX_HIGH_REFRESH, "主动切高刷新率", allowed(dp.allowHighRefreshRate)),
                Triple(dp.KEY_FX_OVERLAY_LYRIC, "桌面悬浮歌词", allowed(dp.allowOverlayLyric)),
                Triple(dp.KEY_FX_WALLPAPER_LYRIC, "歌词壁纸", allowed(dp.allowLyricWallpaper)),
                Triple(dp.KEY_FX_REFRACTION, "玻璃真折射", allowed(dp.allowTrueRefraction)),
                Triple(dp.KEY_FX_HEAVY_STAGE, "重舞台歌词特效", allowed(dp.allowHeavyStages))
            )
            fxRows.forEach { row ->
                SettingToggleRow(row.second, row.third, colors) {
                    dp.setFeatureOverride(
                        deviceCtx, row.first,
                        if (row.third) dp.SAFE_FORCE_OFF else dp.SAFE_FORCE_ON
                    )
                    revision.intValue = revision.intValue + 1
                }
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(6.dp))
            Text(
                if (safeOn) "当前档位：已降级（第 1 项关掉的话，锁屏/熄屏相关的那几条最稳）"
                else "当前档位：不降级",
                color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .junPressable {
                        // 一键回到出厂行为：总闸 + 全部单项都改回"自动"
                        dp.setSafeModeOverride(deviceCtx, dp.SAFE_AUTO)
                        dp.FX_KEYS.forEach { k -> dp.setFeatureOverride(deviceCtx, k, dp.SAFE_AUTO) }
                        revision.intValue = revision.intValue + 1
                    }
                    .clip(JunShape.item)
                    .background(colors.subText.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text("恢复自动判定", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        FoldGroup("低音马达震动 · beta", colors, "跟随鼓点轻震 · 强度/时长/灵敏度/截止频率", advanced = true) {
        // ---- 低音马达震动（beta） ----
        SettingToggleRow(
            "低音马达震动 · beta",
            state.bassHaptics,
            colors,
            subtitle = "跟随低频节奏轻微震动，幅度随鼓点强弱变化"
        ) { viewModel.setBassHaptics(!state.bassHaptics) }

        // 强度档位只在开启时出现（同渐变时长的做法）
        AnimatedVisibility(
            visible = state.bassHaptics,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                Text("震动强度", color = colors.subText, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeSwatch("轻点", state.bassHapticsIntensity == 0, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setBassHapticsIntensity(0) }
                    ModeSwatch("标准", state.bassHapticsIntensity == 1, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setBassHapticsIntensity(1) }
                    ModeSwatch("重击", state.bassHapticsIntensity == 2, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setBassHapticsIntensity(2) }
                }

                Spacer(Modifier.height(10.dp))

                // 自适应：震动强度跟随鼓点力度、时长跟随鼓点时长，开启后隐藏手动时长档
                Spacer(Modifier.height(4.dp))
                SettingToggleRow(
                    "自适应旋律",
                    state.bassHapticsAdaptive,
                    colors,
                    subtitle = "强度随鼓点力度、时长随鼓点长短自动变化"
                ) { viewModel.setBassHapticsAdaptive(!state.bassHapticsAdaptive) }

                // 单次震动时长仅在「自适应」关闭时显示（自适应时由鼓点时长接管）
                AnimatedVisibility(
                    visible = !state.bassHapticsAdaptive,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        Spacer(Modifier.height(10.dp))

                        // 单次震动时长：三档（短/中/长 = 8/20/30ms），按钮替代滑条——
                        // 底部滑条手势竞争太强调不动，改成点选一档最稳。
                        Text("单次震动时长", color = colors.subText, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val dur = state.bassHapticsPulseMs
                            ModeSwatch("短", dur <= 6, colors,
                                modifier = Modifier.weight(1f)) {
                                viewModel.setBassHapticsPulseMs(4); BassHaptics.preview(state.bassHapticsIntensity)
                            }
                            ModeSwatch("中", dur > 6 && dur < 15, colors,
                                modifier = Modifier.weight(1f)) {
                                viewModel.setBassHapticsPulseMs(10); BassHaptics.preview(state.bassHapticsIntensity)
                            }
                            ModeSwatch("长", dur >= 15, colors,
                                modifier = Modifier.weight(1f)) {
                                viewModel.setBassHapticsPulseMs(18); BassHaptics.preview(state.bassHapticsIntensity)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 触发灵敏度：三档（低/中/高）。始终可单独调节，不受自适应影响。
                Text("触发灵敏度", color = colors.subText, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val sens = state.bassHapticsSensitivity
                    ModeSwatch("低", sens <= 3, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setBassHapticsSensitivity(3) }
                    ModeSwatch("中", sens > 3 && sens < 7, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setBassHapticsSensitivity(5) }
                    ModeSwatch("高", sens >= 7, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setBassHapticsSensitivity(8) }
                }

                Spacer(Modifier.height(12.dp))

                // 低音截止频率：多少 Hz 以下算作"低音"（走底鼓闷震），120–320Hz 可调。
                // 调高 → 更多中低频鼓（如厚底鼓/低 tom）归入低音走沉震；调低 → 只有最低的底鼓算低音。
                Text("低音范围（截止频率）", color = colors.subText, fontSize = 11.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${state.bassHapticsLowCutoff}Hz 以下算作低音（走沉震），以上走脆震",
                    color = colors.subText, fontSize = 10.sp
                )
                Spacer(Modifier.height(6.dp))
                CompactSlider(
                    value = (state.bassHapticsLowCutoff - 120) / 200f,
                    onValueChange = {
                        // 120–320Hz，步进 10Hz
                        val hz = (120 + (it * 200f)).toInt().let { v -> (v / 10) * 10 }.coerceIn(120, 320)
                        viewModel.setBassHapticsLowCutoff(hz)
                    },
                    valueRange = 0f..1f,
                    activeColor = colors.primary,
                    inactiveColor = colors.surface
                )
            }
        }

        }

        FoldGroup("无障碍", colors, "减少动效 · 晕动症友好") {
        SettingToggleRow(
            "减少动效",
            state.reduceMotion,
            colors,
            subtitle = "关掉粒子、节拍镜头、低频光晕与副歌氛围；逐词点亮保留，晕动症或低端机建议开"
        ) { viewModel.setReduceMotion(!state.reduceMotion) }
        }

        FoldGroup("诊断与帧率", colors, "歌词层实时 FPS · 仅调试用", advanced = true) {
        SettingToggleRow(
            "歌词帧率监测",
            state.lyricFrameStats,
            colors,
            subtitle = "歌词层左下角显示实时 FPS 与最慢一帧，调试用"
        ) { viewModel.setLyricFrameStats(!state.lyricFrameStats) }
        }

        Spacer(Modifier.height(18.dp))

        } // 结束动效与反馈

        // ==================== 推荐 ====================
        CollapsibleSection("推荐", colors) {

        // ---- 推荐探索度 ----
        Text("推荐探索度", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            if (state.exploration < 0.2f) "贴合口味：按相似度和历史喜好推荐"
            else if (state.exploration < 0.5f) "均衡：偏好为主，偶尔新鲜"
            else if (state.exploration < 0.8f) "探索为主：多推没听过的风格"
            else "全随机：完全探索未知",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        CompactSlider(
            value = state.exploration,
            onValueChange = { viewModel.setExploration(it) },
            valueRange = 0f..1f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )

        Spacer(Modifier.height(10.dp))
        SettingToggleRow(
            "歌单页显示推荐区",
            state.showRecommendations,
            colors,
            subtitle = "关闭后歌单页只显示歌曲列表"
        ) { viewModel.setShowRecommendations(!state.showRecommendations) }

        Spacer(Modifier.height(18.dp))

        } // 结束推荐

        } // 结束「体验与动效」

        SettingsGroup("数据与关于", colors, listOf("数据", "下载", "关于", "我的数据")) {

        // ==================== 数据 ====================
        CollapsibleSection("数据", colors) {

        // ---- 上栏排序行 ----
        SettingToggleRow(
            "上栏显示排序",
            state.showSortBar,
            colors,
            subtitle = "关闭后上栏不再展开排序选项，界面更简洁"
        ) { viewModel.setShowSortBar(!state.showSortBar) }

        // ---- 存储权限 ----
        // 纯在线播放版本不再扫描本地文件，此入口已移除（预留位，不渲染）。

        Spacer(Modifier.height(14.dp))

        // ---- 文件夹（= 在线榜单/歌单来源） ----
        Text("歌单来源", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.heightIn(max = 200.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                FolderRow("全部音乐", "${state.folders.sumOf { it.songCount }} 首",
                    state.selectedFolders.isEmpty() && !state.onlyLiked, colors) { viewModel.clearFolders(); viewModel.setOnlyLiked(false) }
            }
            item {
                FolderRow("我的收藏", "${state.likedSongIds.size} 首",
                    state.onlyLiked, colors) { viewModel.setOnlyLiked(true) }
            }
            itemsIndexed(state.folders, key = { _, f -> "folder-${f.path}" }) { _, f ->
                FolderRow(f.name, "${f.songCount} 首",
                    f.path in state.selectedFolders, colors) { viewModel.toggleFolder(f.path) }
            }
        }

        Spacer(Modifier.height(18.dp))

        } // 结束数据

        // ==================== 下载 ====================
        // 6.3 彻底修复版：同样默认收起（下载格式可用设置搜索直达）。
        CollapsibleSection("下载", colors) {

        // 下载目录设置
        val downloadPath = DownloadManager.prettyPath(state.downloadPath)
        Row(
            Modifier
                .fillMaxWidth()
                .junSurface(GlassLevel.CARD, colors, JunShape.item)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("下载目录", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(downloadPath, color = colors.subText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .junPressable(scaleDown = 0.90f) { onPickDownloadPath() }
                    .clip(JunShape.item)
                    .background(colors.primary.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("选择", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- 6.3：下载格式 ----
        // 用户的原话："下载的时候要有选择，是要 MP3 还是无损 FLAC。"
        // 做成三档而不是两档：多出来的"跟音质走"是老行为，必须是默认，
        // 否则升级上来的用户会突然发现每一首都是几十兆的 FLAC，一次批量下载塞满手机。
        DownloadFormatPicker(
            colors = colors,
            current = state.downloadFormat,
            onPick = { viewModel.setDownloadFormat(it) }
        )

        Spacer(Modifier.height(8.dp))

        // ---- 6.7.1：通知提醒（用户反馈"后台根本找不到这个设置"：原来它被塞在
        //      「关于」折叠区最底部，要滑到底再展开才看得见，等于没有）----
        SectionHeader("通知提醒", colors)
        Spacer(Modifier.height(6.dp))
        ReminderSection(colors)

        Spacer(Modifier.height(14.dp))

        // 下载管理入口
        Row(
            Modifier
                .fillMaxWidth()
                .junPressableRow { onOpenDownloadManager() }
                .junSurface(GlassLevel.CARD, colors, JunShape.item)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(JunIcons.Download, null, Modifier.size(20.dp), colors.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("下载管理", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                val completed = state.downloadTasks.count { it.status == DownloadStatus.COMPLETED }
                val inProgress = state.downloadTasks.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
                Text("已完成 $completed · 进行中 $inProgress", color = colors.subText, fontSize = 10.sp)
            }
            Canvas(Modifier.size(16.dp)) {
                drawLine(
                    colors.subText,
                    Offset(size.width * 0.3f, size.height * 0.25f),
                    Offset(size.width * 0.7f, size.height * 0.5f),
                    strokeWidth = size.minDimension * 0.14f, cap = StrokeCap.Round
                )
                drawLine(
                    colors.subText,
                    Offset(size.width * 0.7f, size.height * 0.5f),
                    Offset(size.width * 0.3f, size.height * 0.75f),
                    strokeWidth = size.minDimension * 0.14f, cap = StrokeCap.Round
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- 在线播放缓存（之前是个"看不见的行为"：默认开、256MB、写满自动覆盖，
        //      用户既看不到占用也没有入口。这里补上占用数字和清空按钮，让它可见可控）----
        StreamCacheSection(state, colors, viewModel)

        Spacer(Modifier.height(18.dp))

        } // 结束下载

        // ==================== 关于 ====================
        CollapsibleSection("关于", colors) {

        // 品牌区：应用图标 + 软件名 + 版权
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = "Jun Music",
                modifier = Modifier.size(52.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Jun Music", color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(2.dp))
                Text("v${BuildConfig.VERSION_NAME}", color = colors.subText, fontSize = 12.sp)
                Spacer(Modifier.height(2.dp))
                Text("© 2026 Jun Music 版权所有", color = colors.subText, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "在线音乐库 · 榜单 / 搜索 / 播放\nKotlin + Jetpack Compose 构建",
            color = colors.subText,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(14.dp))

        // ---- 检查更新 ----
        UpdateCheckSection(colors, onCheckUpdate)

        Spacer(Modifier.height(14.dp))

        // ---- 联系我们 ----
        ContactSection(colors)

        Spacer(Modifier.height(14.dp))

        // ---- 通知权限 ----
        NotificationPermissionSection(colors)

        Spacer(Modifier.height(14.dp))

        // ---- 重看新手引导 / 新功能导览 ----
        ReplayGuideSection(colors, onReplayGuide, onFeatureTour, onWhatsNew)

        Spacer(Modifier.height(14.dp))

        // ---- 隐私与诊断 ----
        PrivacySection(colors)

        Spacer(Modifier.height(18.dp))

        } // 结束关于

        // ==================== 我的数据 ====================
        CollapsibleSection("我的数据", colors) {

        DataTransferSection(colors, state.allSongs)

        } // 结束我的数据
        } // 结束「数据与关于」

        Spacer(Modifier.height(24.dp))
        } // ===== 结束可滚动内容 =====
        } // ===== 结束设置 CompositionLocalProvider =====

        if (searchOpen) {
            SettingsSearchDialog(
                colors = colors,
                onDismiss = { searchOpen = false },
                onJump = { section, group, advanced ->
                    // 只有目标确实藏在「其他设置」里才切模式；普通项跳转保持当前模式。
                    if (advanced && !state.settingsAdvanced) viewModel.setSettingsAdvanced(true)
                    jump = SettingsJumpTarget(section, group, jump.seq + 1)
                }
            )
        }

        // ===== 底部固定：完成按钮 =====
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .junPressable(feedback = PressFeedback.CLICK) { onDismiss() }
                .clip(JunShape.item)
                .background(colors.primary)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("完成", color = colors.primary.readableTextOn(),
                fontSize = 15.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(18.dp))
    }
}

/**
 * 字体选择器：横向滚动的胶囊标签，每个标签用自身字体渲染，
 * 用户一眼就能预览效果。选哪款整个 App 立刻切换（StateFlow 驱动重组）。
 *
 * @param selected 当前选中的字体索引（uiFont / lyricFont）
 * @param onSelect 选中回调，直接调用 viewModel.setFontXxx
 */
// ==================== Folia 增强：歌词 / 音效 / 本地 / 智能 ====================

@Composable
private fun LyricExtrasSection(
    state: PlayerUiState,
    colors: JunColors,
    headerColor: Color,
    viewModel: PlayerViewModel,
    onPickVideo: () -> Unit
) {
    var showStageGallery by remember { mutableStateOf(false) }
    Column {
        FoldGroup("舞台与动效", colors, "14 种舞台 · 7 种逐词动效 · 显示模式") {
        Text("歌词舞台", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Folia 全套十四种歌词视觉；播放页歌词层右上角也能一键切换",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        LyricStageCatalog.names.chunked(4).forEachIndexed { rowIndex, row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { label ->
                    val index = LyricStageCatalog.names.indexOf(label)
                    ModeSwatch(label, state.lyricStage == index, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setLyricStage(index) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            if (rowIndex != LyricStageCatalog.names.chunked(4).lastIndex) {
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .junPressable { showStageGallery = true }
                    .clip(JunShape.item)
                    .background(colors.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text("舞台预览墙", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .junPressable {
                        viewModel.setLyricStagePerSong(!state.lyricStagePerSong)
                    }
                    .clip(JunShape.item)
                    .background(colors.surface.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (state.lyricStagePerSong) "按歌曲记舞台 · 开" else "按歌曲记舞台 · 关",
                    color = colors.text, fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
            }
        }
        if (state.favoriteStages.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("收藏的舞台", color = colors.subText, fontSize = 10.sp)
            Spacer(Modifier.height(5.dp))
            state.favoriteStages.chunked(4).forEachIndexed { rowIndex, row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    row.forEach { index ->
                        ModeSwatch(
                            LyricStageCatalog.name(index),
                            state.lyricStage == index,
                            colors,
                            Modifier.weight(1f)
                        ) { viewModel.setLyricStage(index) }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                if (rowIndex != state.favoriteStages.chunked(4).lastIndex) {
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        if (showStageGallery) {
            StageGalleryDialog(
                stage = state.lyricStage,
                favorites = state.favoriteStages,
                colors = colors,
                onSelect = { viewModel.setLyricStage(it) },
                onToggleFavorite = { viewModel.toggleFavoriteStage(it) },
                onDismiss = { showStageGallery = false }
            )
        }
        Spacer(Modifier.height(14.dp))

        // ---- 歌词动效（Mineradio 六种运动风格 + 经典扫光）----
        Text("歌词动效", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "逐词点亮时按词级时间弹跳；节拍律动再叠加低频光晕与故障抖动",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        LyricMotionStyle.entries.chunked(4).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { style ->
                    ModeSwatch(style.label, state.lyricMotion == style.ordinal, colors,
                        modifier = Modifier.weight(1f)) { viewModel.setLyricMotion(style.ordinal) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            if (rowIndex != LyricMotionStyle.entries.chunked(4).lastIndex) {
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(14.dp))

        // ---- 显示模式 ----
        Text("显示模式", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "控制舞台带几句上下文；流光用明暗层次表达，不删行",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LyricDisplayMode.entries.forEach { mode ->
                ModeSwatch(mode.label, state.lyricDisplayMode == mode.ordinal, colors,
                    modifier = Modifier.weight(1f)) { viewModel.setLyricDisplayMode(mode.ordinal) }
            }
        }
        if (state.lyricDisplayMode == LyricDisplayMode.CUSTOM.ordinal) {
            Spacer(Modifier.height(10.dp))
            Text(
                "上下文行数 · ${state.lyricContext}",
                color = headerColor, fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
            CompactSlider(
                value = state.lyricContext.toFloat(),
                onValueChange = { viewModel.setLyricContext(it.toInt()) },
                valueRange = 0f..6f,
                steps = 5,
                activeColor = colors.primary,
                inactiveColor = colors.surface
            )
        }
        Spacer(Modifier.height(14.dp))

        }

        FoldGroup("氛围与性能", colors, "节拍律动 · 星河粒子 · 低频光晕 · 渲染品质", advanced = true) {
        // ---- 节拍与氛围 ----
        SettingToggleRow(
            "节拍律动",
            state.lyricBeatFx,
            colors,
            subtitle = "低频与鼓点驱动推镜、光晕、故障抖动；关闭后仍保留逐词弹跳"
        ) { viewModel.setLyricBeatFx(!state.lyricBeatFx) }
        Spacer(Modifier.height(10.dp))
        SettingToggleRow(
            "星河粒子",
            state.lyricParticles,
            colors,
            subtitle = "歌词后面缓慢上浮的微粒，跟随低频加速；低配机自动减量"
        ) { viewModel.setLyricParticles(!state.lyricParticles) }
        Spacer(Modifier.height(10.dp))
        SettingToggleRow(
            "低频光晕",
            state.lyricBassGlow,
            colors,
            subtitle = "底鼓每一下，歌词后面的一团光跟着呼吸"
        ) { viewModel.setLyricBassGlow(!state.lyricBassGlow) }
        Spacer(Modifier.height(14.dp))

        // ---- 渲染品质 ----
        Text("渲染品质", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "自动按内存与核心数选择；流畅档会限制粒子数与逐词词块上限",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LyricQuality.entries.forEach { q ->
                ModeSwatch(q.label, state.lyricQuality == q.ordinal, colors,
                    modifier = Modifier.weight(1f)) { viewModel.setLyricQuality(q.ordinal) }
            }
        }
        Spacer(Modifier.height(14.dp))

        }

        FoldGroup("多语言与导出", colors, "翻译/音译轨 · 单曲与整库导出", advanced = true) {
        SettingToggleRow(
            "翻译轨",
            state.lyricTranslation,
            colors,
            subtitle = "显示在线 tlyric / 本地 .t / .zh 歌词"
        ) { viewModel.setLyricTranslation(!state.lyricTranslation) }
        Spacer(Modifier.height(10.dp))
        SettingToggleRow(
            "音译 / 罗马音",
            state.lyricRomanization,
            colors,
            subtitle = "日语罗马音、中文拼音等读音轨"
        ) { viewModel.setLyricRomanization(!state.lyricRomanization) }
        Spacer(Modifier.height(10.dp))
        EnhancedLrcExportButton(state, colors)
        Spacer(Modifier.height(8.dp))
        BatchLrcExportButton(state, colors)
        }

        Spacer(Modifier.height(16.dp))
        LyricProSection(state, colors, headerColor, viewModel, onPickVideo)
    }
}

/** 2.8 歌词专业面板：副歌、取色、校准、排版、发光、预设、舞台调参。 */
@Composable
private fun LyricProSection(
    state: PlayerUiState,
    colors: JunColors,
    headerColor: Color,
    viewModel: PlayerViewModel,
    onPickVideo: () -> Unit
) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("") }
    val stageName = LyricStageCatalog.name(state.lyricStage)

    FoldGroup("副歌与封面氛围", colors, "副歌特效 · 封面取色 · 纵深视差", advanced = true) {
    Text("副歌与氛围", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "自动从重复句里找副歌；副歌句叠加光柱/光环/光束，光晕和粒子同步增强",
        color = colors.subText, fontSize = 11.sp
    )
    Spacer(Modifier.height(8.dp))
    SettingToggleRow(
        "副歌特效",
        state.lyricChorusFx,
        colors,
        subtitle = "不依赖接口字段，按歌词文本判断，纯音乐不会误触发"
    ) { viewModel.setLyricChorusFx(!state.lyricChorusFx) }
    if (state.lyricChorusFx) {
        Spacer(Modifier.height(8.dp))
        Text("副歌强度 · ${(state.lyricChorusBoost * 100).toInt()}%", color = colors.subText, fontSize = 11.sp)
        CompactSlider(
            value = state.lyricChorusBoost,
            onValueChange = { viewModel.setLyricChorusBoost(it) },
            valueRange = 0f..1f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )
    }
    Spacer(Modifier.height(10.dp))
    SettingToggleRow(
        "封面取色",
        state.lyricCoverTint,
        colors,
        subtitle = "取封面主色，染到光晕、粒子和高亮；取不到时保持原主题"
    ) { viewModel.setLyricCoverTint(!state.lyricCoverTint) }
    if (state.lyricCoverTint) {
        Spacer(Modifier.height(8.dp))
        Text("取色强度 · ${(state.lyricTintStrength * 100).toInt()}%", color = colors.subText, fontSize = 11.sp)
        CompactSlider(
            value = state.lyricTintStrength,
            onValueChange = { viewModel.setLyricTintStrength(it) },
            valueRange = 0f..1f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )
    }
    Spacer(Modifier.height(10.dp))
    SettingToggleRow(
        "封面纵深",
        state.lyricCoverDepth,
        colors,
        subtitle = "重模糊封面做底，拖动有视差，点击落点扩散涟漪"
    ) { viewModel.setLyricCoverDepth(!state.lyricCoverDepth) }
    Spacer(Modifier.height(16.dp))

    }

    FoldGroup("歌词时间校准", colors, "蓝牙延迟 / 现场版 / 视频转音频，或歌词抢拍、慢半拍时手动微调", defaultExpanded = true) {
    Text(
        "正数=歌词延后，负数=歌词提前；常用于蓝牙耳机延迟、现场版、视频转音频",
        color = colors.subText, fontSize = 11.sp
    )
    Spacer(Modifier.height(8.dp))
    Text("全局偏移 · ${signedMs(state.lyricOffsetMs)}", color = colors.subText, fontSize = 11.sp)
    CompactSlider(
        value = state.lyricOffsetMs.toFloat(),
        onValueChange = { viewModel.setLyricOffsetMs(it.toInt()) },
        valueRange = -2000f..2000f,
        steps = 79,
        detents = listOf(-2000f, -500f, 0f, 500f, 2000f),
        activeColor = colors.primary,
        inactiveColor = colors.surface
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NudgeChip("-500", colors) { viewModel.nudgeLyricOffset(-500) }
        NudgeChip("-50", colors) { viewModel.nudgeLyricOffset(-50) }
        NudgeChip("归零", colors) { viewModel.setLyricOffsetMs(0) }
        NudgeChip("+50", colors) { viewModel.nudgeLyricOffset(50) }
        NudgeChip("+500", colors) { viewModel.nudgeLyricOffset(500) }
    }
    Spacer(Modifier.height(10.dp))
    val song = state.currentSong
    if (song != null) {
        val songOffset = state.lyricSongOffsets[song.id] ?: 0
        Text(
            "本曲偏移 · ${signedMs(songOffset)}  ·  ${song.title}",
            color = colors.subText, fontSize = 11.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NudgeChip("-500", colors) { viewModel.nudgeLyricSongOffset(-500) }
            NudgeChip("-50", colors) { viewModel.nudgeLyricSongOffset(-50) }
            NudgeChip("仅本曲归零", colors) { viewModel.setLyricSongOffsetMs(song.id, 0) }
            NudgeChip("+50", colors) { viewModel.nudgeLyricSongOffset(50) }
            NudgeChip("+500", colors) { viewModel.nudgeLyricSongOffset(500) }
        }
    }
    Spacer(Modifier.height(16.dp))

    }

    FoldGroup("排版细调", colors, "行距 · 字距 · 字重 · 描边 · 纹理 · 语义断行", advanced = true) {
    Text("行距 · ${"%.2f".format(state.lyricLineSpacing)}×", color = colors.subText, fontSize = 11.sp)
    CompactSlider(
        value = state.lyricLineSpacing,
        onValueChange = { viewModel.setLyricLineSpacing(it) },
        valueRange = 1f..1.8f,
        activeColor = colors.primary,
        inactiveColor = colors.surface
    )
    Spacer(Modifier.height(8.dp))
    Text("字距 · ${(state.lyricLetterSpacing * 100).toInt()}%", color = colors.subText, fontSize = 11.sp)
    CompactSlider(
        value = state.lyricLetterSpacing,
        onValueChange = { viewModel.setLyricLetterSpacing(it) },
        valueRange = -0.04f..0.12f,
        detents = listOf(0f),
        activeColor = colors.primary,
        inactiveColor = colors.surface
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeSwatch("常规", state.lyricWeight == 0, colors, Modifier.weight(1f)) {
            viewModel.setLyricWeight(0)
        }
        ModeSwatch("加粗", state.lyricWeight == 1, colors, Modifier.weight(1f)) {
            viewModel.setLyricWeight(1)
        }
        ModeSwatch("超粗", state.lyricWeight == 2, colors, Modifier.weight(1f)) {
            viewModel.setLyricWeight(2)
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("描边/阴影 · ${(state.lyricOutline * 100).toInt()}%", color = colors.subText, fontSize = 11.sp)
    CompactSlider(
        value = state.lyricOutline,
        onValueChange = { viewModel.setLyricOutline(it) },
        valueRange = 0f..1f,
        activeColor = colors.primary,
        inactiveColor = colors.surface
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("无纹理", "纸纹", "颗粒", "扫描线").forEachIndexed { index, label ->
            ModeSwatch(label, state.lyricTexture == index, colors, Modifier.weight(1f)) {
                viewModel.setLyricTexture(index)
            }
        }
    }
    Spacer(Modifier.height(16.dp))

    }

    FoldGroup("逐词发光", colors, "拖尾 / 呼吸 / 强拍闪白", advanced = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("无", "拖尾", "呼吸", "闪白").forEachIndexed { index, label ->
            ModeSwatch(label, state.lyricGlowStyle == index, colors, Modifier.weight(1f)) {
                viewModel.setLyricGlowStyle(index)
            }
        }
    }
    if (state.lyricGlowStyle != 0) {
        Spacer(Modifier.height(8.dp))
        Text("发光强度 · ${(state.lyricGlowStrength * 100).toInt()}%", color = colors.subText, fontSize = 11.sp)
        CompactSlider(
            value = state.lyricGlowStrength,
            onValueChange = { viewModel.setLyricGlowStrength(it) },
            valueRange = 0f..1f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )
    }
    Spacer(Modifier.height(16.dp))

    }

    FoldGroup("视觉预设", colors, "一键套用 · 8 个自定义槽位 · JSON 迁移", advanced = true) {
    Text(
        "一键套用舞台/动效/显示模式/品质/副歌/取色；可存 8 个自己的槽位（含舞台专属调校）",
        color = colors.subText, fontSize = 11.sp
    )
    Spacer(Modifier.height(8.dp))
    LyricPresetCatalog.builtIn.chunked(3).forEachIndexed { rowIndex, row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { preset ->
                ModeSwatch(preset.name, false, colors, Modifier.weight(1f)) {
                    viewModel.applyLyricPreset(preset)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
        if (rowIndex != LyricPresetCatalog.builtIn.chunked(3).lastIndex) {
            Spacer(Modifier.height(6.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    for (slot in 0..7) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val stored = viewModel.loadLyricPresetSlot(slot)
            ModeSwatch(
                stored?.name?.take(8) ?: "槽位 ${slot + 1} · 空",
                false,
                colors,
                Modifier.weight(1f)
            ) {
                stored?.let { viewModel.applyLyricPreset(it) }
            }
            Box(
                Modifier
                    .junPressable { viewModel.saveLyricPresetSlot(slot); status = "已存入槽位 ${slot + 1}" }
                    .clip(JunShape.item)
                    .background(colors.primary.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text("存当前", color = colors.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NudgeChip("复制预设 JSON", colors) {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            cm?.setPrimaryClip(
                android.content.ClipData.newPlainText(
                    "Jun Music 歌词预设",
                    viewModel.exportLyricPreset()
                )
            )
            status = "已复制，可粘贴到另一台设备导入"
        }
        NudgeChip("从剪贴板导入", colors) {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
            status = if (raw != null && viewModel.importLyricPreset(raw)) "预设已导入并生效" else "剪贴板里没有有效预设"
        }
    }
    if (status.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(status, color = colors.primary, fontSize = 11.sp)
    }
    Spacer(Modifier.height(16.dp))

    }

    val profile = state.lyricStageProfile
    val profileSpec = LyricStageProfiles.of(state.lyricStage)
    FoldGroup(
        "舞台调参与专属调校 · $stageName",
        colors,
        "四轴调参 + 当前舞台专属的字号 / 动势 / 辉光 / 形态",
        advanced = true
    ) {
    Text("四轴调参", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "密度控制粒子数，发光控制光晕，镜头控制节拍推镜；速度参与粒子/副歌流速",
        color = colors.subText, fontSize = 11.sp
    )
    Spacer(Modifier.height(8.dp))
    val tuningLabels = listOf("密度", "速度", "发光", "镜头")
    tuningLabels.forEachIndexed { axis, label ->
        Text(
            "$label · ${state.lyricTuning.getOrElse(axis) { 50 }}%",
            color = colors.subText, fontSize = 11.sp
        )
        CompactSlider(
            value = state.lyricTuning.getOrElse(axis) { 50 }.toFloat(),
            onValueChange = { viewModel.setLyricTuning(axis, it.toInt()) },
            valueRange = 0f..100f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )
        Spacer(Modifier.height(4.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NudgeChip("当前舞台归零", colors) { viewModel.resetLyricTuning() }
        NudgeChip("应用到全部舞台", colors) { viewModel.applyLyricTuningToAll(); status = "已复制到 14 个舞台" }
    }

    Spacer(Modifier.height(16.dp))
    Text("专属调校 · $stageName", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "每个舞台各存一套：切舞台时自动换回它自己的数值",
        color = colors.subText, fontSize = 11.sp
    )
    Spacer(Modifier.height(8.dp))
    Text("字号 · ${profile.font}%", color = colors.subText, fontSize = 11.sp)
    CompactSlider(
        value = profile.font.toFloat(),
        onValueChange = { viewModel.setLyricStageProfile(0, it.toInt()) },
        valueRange = 85f..125f,
        activeColor = colors.primary,
        inactiveColor = colors.surface
    )
    Text("${profileSpec.motionLabel} · ${profile.motion}%", color = colors.subText, fontSize = 11.sp)
    CompactSlider(
        value = profile.motion.toFloat(),
        onValueChange = { viewModel.setLyricStageProfile(1, it.toInt()) },
        valueRange = 0f..150f,
        activeColor = colors.primary,
        inactiveColor = colors.surface
    )
    Text("${profileSpec.glowLabel} · ${profile.glow}%", color = colors.subText, fontSize = 11.sp)
    CompactSlider(
        value = profile.glow.toFloat(),
        onValueChange = { viewModel.setLyricStageProfile(2, it.toInt()) },
        valueRange = 0f..150f,
        activeColor = colors.primary,
        inactiveColor = colors.surface
    )
    Spacer(Modifier.height(6.dp))
    Text("${profileSpec.lookLabel} · ${profileSpec.looks.getOrElse(profile.lookIndex) { "标准" }}",
        color = colors.subText, fontSize = 11.sp)
    Spacer(Modifier.height(6.dp))
    profileSpec.looks.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { label ->
                val lookIndex = profileSpec.looks.indexOf(label).coerceAtLeast(0)
                ModeSwatch(label, profile.lookIndex == lookIndex, colors,
                    modifier = Modifier.weight(1f)) {
                    viewModel.setLyricStageProfile(3, lookIndex)
                }
            }
            repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(6.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NudgeChip("当前舞台重置", colors) { viewModel.resetLyricStageProfile() }
        NudgeChip("应用到全部舞台", colors) {
            viewModel.applyLyricStageProfileToAll(); status = "专属调校已复制到 14 个舞台"
        }
    }
    }

    FoldGroup("沉浸与镜头", colors, "焦点线位置 · 节拍电影镜头 · 长音频模式", advanced = true) {
    Text("焦点线位置 · ${(state.lyricFocus * 100).toInt()}%", color = colors.subText, fontSize = 11.sp)
    Text("当前句停在屏幕的这个高度，越靠上预读的下一句越多",
        color = colors.subText, fontSize = 10.sp)
    CompactSlider(
        value = state.lyricFocus,
        onValueChange = { viewModel.setLyricFocus(it) },
        valueRange = 0.24f..0.62f,
        activeColor = colors.primary,
        inactiveColor = colors.surface
    )
    Spacer(Modifier.height(8.dp))
    SettingToggleRow(
        "节拍电影镜头",
        state.lyricBeatCamera,
        colors,
        subtitle = "低频每一下轻轻推近，副歌加强；关闭后画面完全静止"
    ) { viewModel.setLyricBeatCamera(!state.lyricBeatCamera) }
    if (state.lyricBeatCamera) {
        Spacer(Modifier.height(6.dp))
        Text("镜头幅度 · ${(state.lyricBeatCameraStrength * 100).toInt()}%",
            color = colors.subText, fontSize = 11.sp)
        CompactSlider(
            value = state.lyricBeatCameraStrength,
            onValueChange = { viewModel.setLyricBeatCameraStrength(it) },
            valueRange = 0f..1f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )
    }
    Spacer(Modifier.height(10.dp))
    Text("长音频模式", color = colors.subText, fontSize = 11.sp)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("关闭", "播客", "DJ").forEachIndexed { i, label ->
            ModeSwatch(label, state.lyricLongFormMode == i, colors,
                modifier = Modifier.weight(1f)) { viewModel.setLyricLongFormMode(i) }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        when (state.lyricLongFormMode) {
            1 -> "播客：降低镜头与粒子速度，句子停留更久，长段落不晃眼"
            2 -> "DJ / 电台：进一步收住动势，只保留轻微的呼吸与光晕"
            else -> "关闭：按普通歌曲的节奏律动"
        },
        color = colors.subText, fontSize = 10.sp, lineHeight = 14.sp
    )
    }

    FoldGroup("歌词视频背景", colors, "本地 MP4 · 静音循环 · 跟随播放暂停", advanced = true) {
    Text(
        if (state.lyricVideoUri.isBlank()) "未启用：歌词层使用主题底色与封面纵深"
        else "已启用本地视频；换歌不会重置，横竖屏都生效",
        color = colors.subText, fontSize = 11.sp
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "耗电提示：视频背景会持续硬解并占用 GPU，长时间亮屏播放比静态背景更耗电；" +
            "低端机或电量紧张时建议关掉，舞台本身的光影不受影响。",
        color = colors.subText.copy(alpha = 0.85f), fontSize = 10.sp, lineHeight = 14.sp
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .weight(1f)
                .height(40.dp)
                .junPressable { onPickVideo() }
                .clip(JunShape.item)
                .background(colors.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(if (state.lyricVideoUri.isBlank()) "选择视频" else "更换视频",
                color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        if (state.lyricVideoUri.isNotBlank()) {
            Box(
                Modifier
                    .weight(0.7f)
                    .height(40.dp)
                    .junPressable { viewModel.setLyricVideoUri("") }
                    .clip(JunShape.item)
                    .background(colors.surface),
                contentAlignment = Alignment.Center
            ) {
                Text("关闭视频", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (state.lyricVideoUri.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text("视频不透明度 · ${(state.lyricVideoAlpha * 100).toInt()}%",
            color = colors.subText, fontSize = 11.sp)
        CompactSlider(
            value = state.lyricVideoAlpha,
            onValueChange = { viewModel.setLyricVideoAlpha(it) },
            valueRange = 0f..0.9f,
            activeColor = colors.primary,
            inactiveColor = colors.surface
        )
    }
    }

    FoldGroup("中文语义断行", colors, "标点 / 词组边界优先，长句不把词拆开", advanced = true) {
    SettingToggleRow(
        "语义断行",
        state.lyricSemanticWrap,
        colors,
        subtitle = "按段落级断行策略处理中文长句；英文与日文同样受益"
    ) { viewModel.setLyricSemanticWrap(!state.lyricSemanticWrap) }
    }
}

private fun signedMs(value: Int): String = when {
    value > 0 -> "+${value}ms"
    else -> "${value}ms"
}

@Composable
private fun NudgeChip(label: String, colors: JunColors, onClick: () -> Unit) {
    Box(
        Modifier
            .junPressable { onClick() }
            .clip(JunShape.item)
            .background(colors.surface)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(label, color = colors.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EnhancedLrcExportButton(state: PlayerUiState, colors: JunColors) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .junPressable(enabled = !busy) {
                    val song = state.currentSong
                    if (song == null) {
                        status = "先播放一首歌再导出"
                        return@junPressable
                    }
                    busy = true
                    status = ""
                    scope.launch {
                        val lines = withContext(Dispatchers.IO) {
                            runCatching { LyricParser.loadLyrics(song.lyricKey, state.durationMs) }
                                .getOrDefault(emptyList())
                        }
                        if (lines.isEmpty()) {
                            status = "这首歌暂时没有可导出的歌词"
                            busy = false
                            return@launch
                        }
                        runCatching {
                            val text = LyricParser.exportEnhancedLrc(lines)
                            val dir = File(context.cacheDir, "export_lyrics").apply { mkdirs() }
                            val safe = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                .take(48).ifBlank { "lyrics" }
                            val file = File(dir, "$safe.lrc")
                            file.writeText(text)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "${song.title} · 增强 LRC")
                                putExtra(Intent.EXTRA_TEXT, text)
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "导出增强 LRC"))
                            status = "已生成逐字 LRC（${lines.size} 行）"
                        }.onFailure {
                            status = "导出失败：${it.message ?: "无法写入文件"}"
                        }
                        busy = false
                    }
                }
                .clip(JunShape.item)
                .background(colors.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (busy) "正在整理歌词…" else "导出增强 LRC（逐字时间轴）",
                color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
        }
        if (status.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(status, color = colors.subText, fontSize = 11.sp)
        }
    }
}

@Composable
private fun BatchLrcExportButton(state: PlayerUiState, colors: JunColors) {
    val context = LocalContext.current
    val export by LyricExportManager.state.collectAsState()
    // 导出在进程级 scope 里跑：离开设置页不会中断；回到设置页仍能看到结果。
    // 完成时如果本页还在，顺手拉起分享面板。
    LaunchedEffect(export.zipFile) {
        val zip = export.zipFile ?: return@LaunchedEffect
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", zip
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_SUBJECT, "Jun Music 歌词批量导出")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "导出歌词 ZIP"))
        }
        LyricExportManager.markShared()
    }

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .junPressable(enabled = !export.running) {
                    LyricExportManager.start(context, state.allSongs)
                }
                .clip(JunShape.item)
                .background(colors.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (export.running) "正在打包 ${export.done}/${export.total}…"
                else "批量导出歌词 ZIP（逐字时间轴）",
                color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
        }
        if (export.running) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.surface)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(export.fraction)
                        .height(4.dp)
                        .background(colors.primary)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "后台整理中，可以离开设置页；点右侧可取消",
                    color = colors.subText, fontSize = 10.sp, modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .junPressable {
                            LyricExportManager.cancel()
                        }
                        .clip(JunShape.item)
                        .background(colors.surface)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text("取消", color = colors.text, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (export.status.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(export.status, color = colors.subText, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FxPresetSection(
    state: PlayerUiState,
    colors: JunColors,
    headerColor: Color,
    viewModel: PlayerViewModel
) {
    Column {
        Text("音效预设", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "整套 EQ + 低音 + 声场 + 限幅一次到位，不必逐个开关调",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FxPreset.entries.forEachIndexed { i, preset ->
                val selected = state.fxPreset == i
                Box(
                    Modifier
                        .junPressable { viewModel.setFxPreset(i) }
                        .clip(JunShape.item)
                        .background(
                            if (selected) colors.primary
                            else colors.primary.copy(alpha = 0.12f)
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        preset.label,
                        color = if (selected) colors.primary.readableTextOn() else colors.text,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val current = FxPreset.of(state.fxPreset)
        Text(
            current?.subtitle ?: "当前为自定义曲线；点上方任一预设可整套切换",
            color = colors.subText, fontSize = 10.sp, lineHeight = 14.sp
        )
    }
}

@Composable
private fun LocalLibrarySection(
    state: PlayerUiState,
    colors: JunColors,
    headerColor: Color,
    viewModel: PlayerViewModel
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setLocalFolder(uri)
    }
    Column {
        FoldGroup("文件夹与扫描", colors, "授权文件夹 · 重新扫描 · 曲目统计") {
        Text("本地音乐", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "授权一个文件夹，App 只读取其中的音频、封面与同名歌词；文件不会被移动或上传",
            color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .junPressable { picker.launch(null) }
                    .clip(JunShape.item)
                    .background(colors.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (state.localFolder.isBlank()) "选择音乐文件夹" else "更换文件夹",
                    color = colors.primary.readableTextOn(), fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }
            if (state.localFolder.isNotBlank()) {
                Box(
                    Modifier
                        .weight(0.72f)
                        .height(40.dp)
                        .junPressable(enabled = !state.localScanning) {
                            viewModel.rescanLocal()
                        }
                        .clip(JunShape.item)
                        .background(colors.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (state.localScanning) "扫描中…" else "重新扫描",
                        color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    Modifier
                        .weight(0.55f)
                        .height(40.dp)
                        .junPressable { viewModel.setLocalFolder(null) }
                        .clip(JunShape.item)
                        .background(colors.surface.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("关闭", color = colors.subText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                state.localFolder.isBlank() -> "尚未启用本地曲库"
                state.localScanning -> "正在读取文件夹…大曲库首次扫描需要一点时间"
                state.localScanNotice.isNotBlank() -> "${state.localScanNotice} · 共 ${state.localSongCount} 首"
                else -> "已启用 · ${state.localSongCount} 首"
            },
            color = colors.subText, fontSize = 11.sp
        )
        }

        // ────────────────────────────────────────────────────────────
        // 6.8.1：我的云端曲库（第二层后路的内容从哪来）
        //
        // 用户原话「主音源呢」——库是空的就等于没有主音源。这里的开关让它自己长：
        // 播稳一首就备份一首，下次这首歌直接走自己的服务器，第三方死不死都无所谓。
        // 默认开启，但**只在 Wi-Fi 下、每天 300MB 上限**，不会偷偷吃掉流量。
        // ────────────────────────────────────────────────────────────
        var bfOn by remember { mutableStateOf(com.jun.musiccloud.data.LibraryBackfill.isEnabled()) }
        val bfStats = com.jun.musiccloud.data.RemoteLibrary.stats()
        FoldGroup(
            "我的云端曲库",
            colors,
            if (bfStats.first > 0) "已备份 ${bfStats.first} 首 · ${"%.0f".format(bfStats.second / 1048576.0)} MB"
            else "还没有备份 · 播过的歌会自动存进来"
        ) {
            // 6.7：以前后台一直 27 首，用户完全看不出是「没传」还是「传了被拒」。
            // 修完链路后把状态直接摆出来，以后再断一眼就能定位。
            Text(
                "运行状态：${com.jun.musiccloud.data.LibraryBackfill.lastNote()}" +
                    "（本次启动成功 ${com.jun.musiccloud.data.LibraryBackfill.successCount()} 首）",
                color = headerColor, fontSize = 11.sp, lineHeight = 15.sp
            )
            Spacer(Modifier.height(8.dp))
            SettingToggleRow(
                "自动备份听过的歌",
                bfOn,
                colors,
                subtitle = "听到全曲 80% 即备份（不再强求自然播完，切歌前听完的也算）；每天最多 300MB；备份后这首歌以后直接走你自己的服务器"
            ) {
                bfOn = !bfOn
                com.jun.musiccloud.data.LibraryBackfill.setEnabled(bfOn)
            }
            Spacer(Modifier.height(8.dp))
            var bfMetered by remember { mutableStateOf(com.jun.musiccloud.data.LibraryBackfill.isAllowMetered()) }
            SettingToggleRow(
                "用移动网络也备份",
                bfMetered,
                colors,
                subtitle = "6.7 起默认开启：之前只在 Wi-Fi 下备份，用流量听歌时曲库一首都不涨。仍受每天 300MB 上限约束，不想花流量可关"
            ) {
                bfMetered = !bfMetered
                com.jun.musiccloud.data.LibraryBackfill.setAllowMetered(bfMetered)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "这一层是唯一「别人关不掉」的在线来源：文件在你的服务器上，" +
                    "换手机、重装、第三方音源全挂，都还能听。",
                color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp
            )
        }

        FoldGroup("扫描选项", colors, "文件夹封面 · .junignore · 多歌手拆分", advanced = true) {
        SettingToggleRow(
            "读取文件夹封面",
            state.localFolderCover,
            colors,
            subtitle = "优先使用同目录 cover / folder / front 图片"
        ) { viewModel.setLocalFolderCover(!state.localFolderCover) }
        Spacer(Modifier.height(10.dp))
        SettingToggleRow(
            "遵循 .junignore",
            state.localIgnore,
            colors,
            subtitle = "按 .junignore / .foliaignore 排除文件与目录"
        ) { viewModel.setLocalIgnore(!state.localIgnore) }
        Spacer(Modifier.height(10.dp))
        SettingToggleRow(
            "拆分多歌手",
            state.localSplitArtists,
            colors,
            subtitle = "把「A / B」「A feat. B」归到主歌手 A；AC/DC 这类名字建议关闭"
        ) { viewModel.setLocalSplitArtists(!state.localSplitArtists) }
        }

        FoldGroup("歌手 / 专辑别名", colors, "每行一条「原名=新名」，重扫后生效", advanced = true) {
        Text(
            "每行一条「原名=新名」，保存后点「重新扫描」生效，用于合并同一歌手或专辑的不同写法",
            color = colors.subText, fontSize = 10.sp, lineHeight = 14.sp
        )
        Spacer(Modifier.height(6.dp))
        SettingsTextArea(
            value = state.localAliasRules,
            placeholder = "周杰伦=Jay Chou\n范特西=Fantasy",
            colors = colors,
            onValueChange = { viewModel.setLocalAliasRules(it) }
        )
        }
    }
}

@Composable
private fun SettingsTextArea(
    value: String,
    placeholder: String,
    colors: JunColors,
    onValueChange: (String) -> Unit
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(fontFamily = LocalJunUiFamily.current, color = colors.text, fontSize = 12.sp, lineHeight = 18.sp),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .junSurface(GlassLevel.CARD, colors, JunShape.item)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box {
                if (value.isBlank()) {
                    Text(placeholder, color = colors.subText.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 18.sp)
                }
                inner()
            }
        }
    )
}

@Composable
private fun SmartConnectSection(
    state: PlayerUiState,
    colors: JunColors,
    headerColor: Color,
    viewModel: PlayerViewModel
) {
    val context = LocalContext.current
    Column {
        FoldGroup("AI 主题与歌词壁纸", colors, "按当前歌曲生成配色 · 把歌词放到桌面", advanced = true) {
        Text("AI 情绪主题", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "根据当前歌曲的歌名、歌手与专辑生成一套配色，直接应用到整个界面",
            color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .junPressable(enabled = !state.aiThemeBusy) {
                    viewModel.generateAiTheme()
                }
                .clip(JunShape.item)
                .background(colors.primary.copy(alpha = if (state.aiThemeBusy) 0.12f else 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (state.aiThemeBusy) "正在感受这首歌…" else "用当前歌曲生成主题",
                color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
        }
        if (state.aiThemeName.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("当前主题：${state.aiThemeName}", color = colors.subText, fontSize = 11.sp)
        }
        Spacer(Modifier.height(16.dp))

        Text("歌词动态壁纸", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "把当前歌词与呼吸光晕放到桌面，切歌自动跟随，不可见时停止重绘",
            color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .junPressable {
                    val component = ComponentName(context, LyricWallpaperService::class.java)
                    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
                    runCatching { context.startActivity(intent) }.onFailure {
                        runCatching {
                            context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                        }
                    }
                }
                .clip(JunShape.item)
                .background(colors.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text("设为歌词壁纸", color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))

        }

        FoldGroup("局域网遥控", colors, "同一 Wi-Fi 浏览器控制 · 地址带随机令牌", advanced = true) {
        Text(
            "同一 Wi-Fi 下用浏览器打开地址即可控制播放、看当前歌词；地址带随机令牌",
            color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp
        )
        Spacer(Modifier.height(8.dp))
        SettingToggleRow(
            "启用遥控服务",
            state.remoteEnabled,
            colors,
            subtitle = "播放服务运行时监听 8099 端口"
        ) { viewModel.setRemoteEnabled(!state.remoteEnabled) }
        if (state.remoteEnabled) {
            val url = remember(state.remoteUrl, state.remoteEnabled) {
                state.remoteUrl.ifBlank { RemoteControlServer.url(context) }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .junPressable(feedback = PressFeedback.NONE) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Jun Music 遥控地址", url))
                    }
                    .clip(JunShape.item)
                    .background(colors.surface.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(url, color = colors.primary, fontSize = 11.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text("点地址复制 · 播放一首歌后生效", color = colors.subText, fontSize = 10.sp)
        }
        Spacer(Modifier.height(16.dp))

        }
        FoldGroup("设置云同步与配置档", colors, "改完自动备份 · 重装换机自动恢复") {
            SettingsCloudSection(state, colors, viewModel, context)
        }
    }
}

/**
 * 「设置云同步与配置档」这一块的正文（6.2 R15）。
 *
 * 之所以单独拆出来：这块既要显示自动同步的状态、又要画冲突二选一、还要管配置档的
 * 增删改，全塞进 FoldGroup 的 lambda 里会把这个函数顶到没法读。
 *
 * 状态来源分两半是刻意的：
 *   - 状态文案走 [PlayerUiState.cloudSyncStatus]（ViewModel 在每次动作后镜像一次），
 *     这样文案和其它设置项一样受同一套状态管理；
 *   - 冲突快照 / 忙碌标志直接读 SettingsCloud 的 Compose 状态，因为它们本来就是
 *     `mutableStateOf`，再往 PlayerUiState 里复制一份只是多一套会不同步的真相。
 */
@Composable
private fun SettingsCloudSection(
    state: PlayerUiState,
    colors: JunColors,
    viewModel: PlayerViewModel,
    context: Context
) {
    val scope = rememberCoroutineScope()
    val busy = SettingsCloud.busy.value
    val conflictRaw = SettingsCloud.conflict.value
    var presets by remember { mutableStateOf<List<SettingsPresets.Item>>(emptyList()) }
    var presetRefresh by remember { mutableIntStateOf(0) }
    var presetName by remember { mutableStateOf("") }

    LaunchedEffect(presetRefresh) {
        presets = withContext(Dispatchers.IO) { SettingsPresets.list(context) }
    }

    Text(
        "背景图、透明度、均衡器、歌词层这些偏好改完就自动备份到云端（含背景图本体），" +
            "重装或换机登录后自动恢复；账号凭证、下载目录、本地授权不参与同步。",
        color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp
    )
    Spacer(Modifier.height(8.dp))

    // ---- 未登录时只留一句话：后面所有按钮都要凭证 ----
    if (!state.accountLoggedIn) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(JunShape.item)
                .background(colors.surface.copy(alpha = 0.4f))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text("登录账号后才会自动同步；本机设置照常保存。",
                color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp)
        }
    } else {
        // ---- 冲突：两套设置都存在，绝不替用户静默选一边 ----
        if (conflictRaw != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(JunShape.item)
                    .background(com.jun.musiccloud.ui.theme.JunPalette.NeonOrange.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text("云端和本机各有一套设置", color = colors.text, fontSize = 12.sp,
                    fontWeight = FontWeight.Black)
                Spacer(Modifier.height(4.dp))
                Text(
                    "多半是另一台设备在你不在线的时候改过。选哪边，另一边就会被覆盖。",
                    color = colors.subText, fontSize = 10.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .junPressable(feedback = PressFeedback.CLICK, enabled = !busy) {
                                viewModel.resolveSettingsConflict(true)
                            }
                            .clip(JunShape.item)
                            .background(colors.primary.copy(alpha = 0.24f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("用云端", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .junPressable(feedback = PressFeedback.CLICK, enabled = !busy) {
                                viewModel.resolveSettingsConflict(false)
                            }
                            .clip(JunShape.item)
                            .background(colors.surface.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("用本机", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        Modifier
                            .height(36.dp)
                            .junPressable(feedback = PressFeedback.NONE) { viewModel.dismissSettingsConflict() }
                            .clip(JunShape.item)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("先不动", color = colors.subText, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .junPressable(feedback = PressFeedback.CLICK, enabled = !busy && conflictRaw == null) {
                        viewModel.uploadSettings()
                    }
                    .clip(JunShape.item)
                    .background(colors.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(if (busy) "处理中…" else "立即备份", color = colors.primary,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .junPressable(feedback = PressFeedback.CLICK, enabled = !busy && conflictRaw == null) {
                        viewModel.downloadSettings()
                    }
                    .clip(JunShape.item)
                    .background(colors.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text("从云端恢复", color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        // 距离上次成功同步太久的提醒：自动同步最容易坏在「用户早就不用了」这台设备上，
        // 一句「已经 N 天没备份」比讲原理管用。-1 = 从没成功过。
        val idleDays = remember(state.cloudSyncStatus, busy) {
            SettingsCloud.daysSinceSync(context)
        }
        val line = when {
            state.cloudSyncStatus.isBlank() && idleDays >= 30 ->
                "已经 $idleDays 天没成功备份过设置了，点上面「立即备份」试一次"
            state.cloudSyncStatus.isBlank() && idleDays < 0 ->
                if (busy) "正在准备第一次备份…" else "还没备份过，改完设置会自动上传"
            state.cloudSyncStatus.isBlank() -> "自动同步运行中"
            else -> state.cloudSyncStatus
        }
        Text(line, color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp)
    }

    // ---- 配置档：本机多套，云端一份 ----
    Spacer(Modifier.height(14.dp))
    Text("配置档（只存在本机）", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(4.dp))
    Text(
        "云同步只保留「最后那一套」。想同时留着「深夜安静版」和「通勤外放版」，" +
            "就在这里各存一档；切档时会自动把当前这套也存一份留后路。",
        color = colors.subText, fontSize = 10.sp, lineHeight = 14.sp
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = presetName,
            onValueChange = { presetName = it.take(24) },
            singleLine = true,
            textStyle = TextStyle(fontFamily = LocalJunUiFamily.current, color = colors.text, fontSize = 12.sp),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .weight(1f)
                .junSurface(GlassLevel.CARD, colors, JunShape.item)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { inner ->
                Box {
                    if (presetName.isBlank()) {
                        Text("给这套设置起个名字", color = colors.subText.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    inner()
                }
            }
        )
        Box(
            Modifier
                .height(40.dp)
                .junPressable(feedback = PressFeedback.CLICK, enabled = presetName.isNotBlank()) {
                    viewModel.saveSettingsPreset(presetName.trim())
                    presetName = ""
                    presetRefresh++
                }
                .clip(JunShape.item)
                .background(
                    if (presetName.isBlank()) colors.surface.copy(alpha = 0.3f)
                    else colors.primary.copy(alpha = 0.22f)
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("保存", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }

    presets.forEach { p ->
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(JunShape.item)
                .background(colors.surface.copy(alpha = 0.28f))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${p.items} 项 · " + java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
                        ).format(java.util.Date(p.createdAt)),
                        color = colors.subText, fontSize = 10.sp
                    )
                }
                Box(
                    Modifier
                        .height(32.dp)
                        .junPressable(feedback = PressFeedback.CLICK, enabled = !busy) {
                            viewModel.applySettingsPreset(p.id)
                            presetRefresh++
                        }
                        .clip(JunShape.item)
                        .background(colors.primary.copy(alpha = 0.2f))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("切到这套", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "分享", color = colors.subText, fontSize = 11.sp,
                    modifier = Modifier.junPressable(feedback = PressFeedback.NONE) {
                        scope.launch {
                            val text = withContext(Dispatchers.IO) {
                                SettingsPresets.snapshotOf(context, p.id)
                            }
                            if (text.isBlank()) return@launch
                            runCatching {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                    putExtra(Intent.EXTRA_SUBJECT, "JunMusic 配置档 · ${p.name}")
                                }
                                context.startActivity(Intent.createChooser(send, "分享配置档"))
                            }
                        }
                    }.padding(vertical = 2.dp)
                )
                Text(
                    "导出到剪贴板", color = colors.subText, fontSize = 11.sp,
                    modifier = Modifier.junPressable(feedback = PressFeedback.NONE) {
                        scope.launch {
                            val text = withContext(Dispatchers.IO) {
                                SettingsPresets.snapshotOf(context, p.id)
                            }
                            if (text.isBlank()) return@launch
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("JunMusic 配置档", text)
                            )
                        }
                    }.padding(vertical = 2.dp)
                )
                Text(
                    "删除", color = colors.subText, fontSize = 11.sp,
                    modifier = Modifier.junPressable(feedback = PressFeedback.NONE) {
                        viewModel.deleteSettingsPreset(p.id)
                        presetRefresh++
                    }.padding(vertical = 2.dp)
                )
            }
        }
    }

    if (presets.isEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text("还没有配置档", color = colors.subText.copy(alpha = 0.7f), fontSize = 10.sp)
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "别人分享的配置档：把那段文字复制后回到这里，点下面的按钮即可存成一档（不会直接改当前设置）。",
        color = colors.subText, fontSize = 10.sp, lineHeight = 14.sp
    )
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .junPressable(feedback = PressFeedback.CLICK) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                viewModel.importSettingsPreset(text)
                presetRefresh++
            }
            .clip(JunShape.item)
            .background(colors.surface.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        Text("从剪贴板导入配置档", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FontSelector(
    selected: Int,
    colors: JunColors,
    onSelect: (Int) -> Unit
) {
    val scroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        JunFonts.forEach { entry ->
            val isSel = selected == entry.index
            Box(
                Modifier
                    .junPressable { onSelect(entry.index) }
                    .clip(JunShape.item)
                    .background(
                        if (isSel) colors.primary
                        else colors.primary.copy(alpha = 0.12f)
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    entry.label,
                    color = if (isSel) colors.primary.readableTextOn() else colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = entry.family
                )
            }
        }
    }
}

/** 一级分组标题：醒目、中性（用主文字色），与彩色子项标题区分开层级。 */
@Composable
private fun SectionHeader(text: String, colors: JunColors) {
    Text(
        text,
        color = colors.text,
        fontSize = 15.sp,
        fontWeight = FontWeight.Black
    )
}

/**
 * 可折叠分区：默认收起，只显示一行标题 + 右侧箭头；点标题展开整段详细设置。
 *
 * 设置项太多，一屏塞不下、第一眼也显得杂乱。R4 起**普通设置也一律默认收起**
 * （之前普通模式整页铺开，进来就是一屏看不到头的开关）：收起时就是一列干净的分区名，
 * 想看哪块点哪块。箭头指向随展开状态旋转（› 收起 / ⌄ 展开）。
 * 唯一会自动展开的是搜索跳转（[LocalSettingsJump]），以及 [defaultExpanded] 显式点名的分区。
 */
@Composable
private fun CollapsibleSection(
    title: String,
    colors: JunColors,
    defaultExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.runtime.SideEffect { SettingsGroupCounters.register(title, advancedOnly = false) }
    // 两种模式默认都收起：进设置页先看到一列分区名，点哪块展开哪块。
    var expanded by remember { mutableStateOf(defaultExpanded) }
    LaunchedEffect(defaultExpanded) { expanded = defaultExpanded }
    val jump = LocalSettingsJump.current
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(jump.seq) {
        if (jump.section == title) {
            expanded = true
            delay(80)
            runCatching { requester.bringIntoView() }
        }
    }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(requester).padding(bottom = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .junPressableRow(scaleDown = 0.99f, feedback = PressFeedback.NONE) {
                    Haptics.tap()
                    expanded = !expanded
                }
                .clip(JunShape.item)
                .padding(vertical = 8.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Black)
            // 右侧箭头：收起指向右、展开指向下，220ms 旋转过渡
            val rot by animateFloatAsState(
                targetValue = if (expanded) 90f else 0f,
                animationSpec = tween(220, easing = EaseInOutCubic),
                label = "sectionChevron"
            )
            Canvas(Modifier.size(16.dp).graphicsLayer { rotationZ = rot }) {
                val c = colors.subText
                val w = size.width
                val h = size.height
                val mid = h / 2f
                val stroke = w * 0.12f
                drawLine(
                    color = c,
                    start = Offset(w * 0.38f, h * 0.22f),
                    end = Offset(w * 0.66f, mid),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                drawLine(
                    color = c,
                    start = Offset(w * 0.66f, mid),
                    end = Offset(w * 0.38f, h * 0.78f),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(top = 4.dp), content = content)
        }
    }
}

/**
 * 一级分组（6.4）：把原来 11 个顶层分区收成 5 组，首屏只看到 5 个入口。
 *
 * 设计参考 nowinandroid（Google 官方示例，Apache-2.0）的设置分组范式：
 * 分组标题独立成行、组内才是具体设置项。这里全部自己实现，没有引入任何
 * 第三方库，也没有抄任何 GPL 代码（GPL 项目只作为交互参考，不取代码）。
 *
 * [members] 是这个组包含的分区名，只用于一件事：设置搜索点了组内某项时，
 * 本组自动展开。不写的话用户点了搜索结果进来，看到的是收起的空组，搜索等于失效。
 */
@Composable
private fun SettingsGroup(
    title: String,
    colors: JunColors,
    members: List<String>,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val jump = LocalSettingsJump.current
    LaunchedEffect(jump.seq) {
        val target = jump.section
        if (target != null && target.isNotBlank() && target in members) expanded = true
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .junPressableRow(scaleDown = 0.985f, feedback = PressFeedback.NONE) {
                    Haptics.tap()
                    expanded = !expanded
                }
                .clip(JunShape.card)
                .background(colors.card)
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = colors.text, fontSize = 15.5.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${members.size} 项",
                    color = colors.subText.copy(alpha = 0.72f),
                    fontSize = 10.5.sp
                )
            }
            val rot by animateFloatAsState(
                targetValue = if (expanded) 90f else 0f,
                animationSpec = tween(220, easing = EaseInOutCubic),
                label = "groupChevron"
            )
            Canvas(Modifier.size(16.dp).graphicsLayer { rotationZ = rot }) {
                val c = colors.subText
                val w = size.width
                val h = size.height
                val mid = h / 2f
                val stroke = w * 0.12f
                drawLine(
                    color = c,
                    start = Offset(w * 0.38f, h * 0.22f),
                    end = Offset(w * 0.66f, mid),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                drawLine(
                    color = c,
                    start = Offset(w * 0.66f, mid),
                    end = Offset(w * 0.38f, h * 0.78f),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(top = 6.dp), content = content)
        }
    }
}

/**
 * 二级折叠卡：一级分区展开后，内部仍然按主题分组折叠。
 *
 * R4 起一级、二级都**默认收起**（普通设置也不例外），标题下一行小字说明这组管什么；
 * 展开箭头与一级分区同款，只是字号更小、层级更轻，避免设置页变成一条看不到头的长列表。
 */
@Composable
private fun FoldGroup(
    title: String,
    colors: JunColors,
    subtitle: String = "",
    defaultExpanded: Boolean = false,
    /** true=只在进阶模式显示 */
    advanced: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val advancedMode = LocalSettingsAdvanced.current
    // 注册要在早退之前：普通设置下"只在其他设置显示"的组虽不可见，但也要计数，
    // 否则「其他设置 (N)」的 N 永远数不全。
    androidx.compose.runtime.SideEffect { SettingsGroupCounters.register(title, advancedOnly = advanced) }
    if (advanced && !advancedMode) return
    // 默认一律收起；搜索跳转会临时展开目标组。
    var expanded by remember { mutableStateOf(defaultExpanded) }
    LaunchedEffect(defaultExpanded) { expanded = defaultExpanded }
    val jump = LocalSettingsJump.current
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(jump.seq) {
        val target = jump.group
        if (target != null && (title == target || title.startsWith(target))) {
            expanded = true
            delay(120)
            runCatching { requester.bringIntoView() }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .padding(vertical = 3.dp)
            .clip(JunShape.item)
            .background(colors.surface.copy(alpha = if (expanded) 0.35f else 0.20f))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { Haptics.tap(); expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Black)
                if (subtitle.isNotBlank() && !expanded) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        color = colors.subText, fontSize = 10.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            val rot by animateFloatAsState(
                targetValue = if (expanded) 90f else 0f,
                animationSpec = tween(200, easing = EaseInOutCubic),
                label = "foldChevron"
            )
            Canvas(Modifier.size(14.dp).graphicsLayer { rotationZ = rot }) {
                val c = colors.subText
                val w = size.width
                val h = size.height
                val mid = h / 2f
                val stroke = w * 0.12f
                drawLine(
                    color = c,
                    start = Offset(w * 0.38f, h * 0.22f),
                    end = Offset(w * 0.66f, mid),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                drawLine(
                    color = c,
                    start = Offset(w * 0.66f, mid),
                    end = Offset(w * 0.38f, h * 0.78f),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 2.dp),
                content = content
            )
        }
    }
}

/**
 * 我的数据：导出 / 导入听歌数据（JSON，全程离线）。
 *
 * 存在意义是让推荐算法依赖的长期行为数据可以跟着人走——换机、刷机、重装不再清零。
 * 走 SAF（ACTION_CREATE_DOCUMENT / OpenDocument），因此不需要任何存储写权限。
 */
@Composable
private fun DataTransferSection(colors: JunColors, songs: List<Song>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "正在导出…"
        scope.launch {
            val ok = DataTransfer.export(ctx, uri, songs)
            status = if (ok) "已导出到所选位置" else "导出失败"
            busy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "正在导入…"
        scope.launch {
            status = when (val r = DataTransfer.import(ctx, uri, songs)) {
                is DataTransfer.ImportResult.Success -> {
                    val s = r.summary
                    "导入完成：匹配 ${s.playedSongs} 首、点赞 ${s.likes}、歌单 ${s.playlists}、听歌记录 ${s.listenDays} 天"
                }
                DataTransfer.ImportResult.BadFormat -> "导入失败：文件格式不是 JUN Music 导出的数据"
                DataTransfer.ImportResult.Failed -> "导入失败：文件无法读取"
            }
            busy = false
        }
    }

    Column {
        Text(
            "把点赞、播放次数、听歌时长和歌单导出成 JSON 文件，换机或重装后导入即可恢复。" +
                "导入按曲目匹配并取较大值合并，重复导入不会翻倍。全程本地读写，不联网。",
            color = colors.subText,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .junPressable(enabled = !busy) {
                        status = ""
                        exportLauncher.launch(DataTransfer.SUGGESTED_FILE_NAME)
                    }
                    .clip(JunShape.item)
                    .background(if (busy) colors.primary.copy(alpha = 0.18f) else colors.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "导出数据",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (busy) colors.primary else colors.primary.readableTextOn()
                )
            }

            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .junPressable(enabled = !busy) {
                        status = ""
                        // 部分机型对 application/json 过滤过严，放宽到任意类型再由解析兜底
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                    .clip(JunShape.item)
                    .background(colors.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "导入数据",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
            }
        }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(status, color = colors.subText, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

/**
 * 检查更新：点击后请求自有服务器 /api/app/version，有新版则弹更新框
 * （是否强更由服务器控制，见 UpdateChecker）。
 */
@Composable
private fun UpdateCheckSection(
    colors: JunColors,
    onCheckUpdate: suspend () -> UpdateChecker.Result
) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(true) }
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .junPressable(feedback = PressFeedback.NONE) {
                    if (checking) return@junPressable
                    Haptics.tap()
                    checking = true
                    message = null
                    scope.launch {
                        val result = runCatching { onCheckUpdate() }
                            .getOrElse { UpdateChecker.Result.Failed }
                        when (result) {
                            UpdateChecker.Result.UpToDate -> {
                                ok = true
                                message = "已是最新版本 v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）"
                            }
                            is UpdateChecker.Result.NewVersion -> {
                                ok = true
                                message = "发现新版本 v${result.update.versionName}，已弹出更新说明"
                            }
                            UpdateChecker.Result.Failed -> {
                                ok = false
                                message = "检查失败：网络不可用或服务器无响应，请稍后再试"
                            }
                            UpdateChecker.Result.Unconfigured -> {
                                ok = false
                                message = "当前安装包未配置更新服务器"
                            }
                        }
                        checking = false
                    }
                }
                .clip(JunShape.item)
                .background(colors.primary.copy(alpha = if (checking) 0.10f else 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (checking) "正在检查…" else "检查更新",
                color = if (checking) colors.primary.copy(alpha = 0.65f) else colors.primary,
                fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "点击会立刻向服务器查询最新版本；启动时也会自动检查，是否必须更新由服务器决定",
            color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp
        )
        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                color = if (ok) colors.primary.readableOn(colors.surface, 3.5f)
                else Color(0xFFD9534F).let { c -> if (colors.isDark) Color(0xFFFF8A80) else c },
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 联系我们：邮箱直写 + 一键复制。
 *
 * 「出问题了找谁」必须一眼可见：反馈、数据删除、账号问题都走这一个邮箱，
 * 用户不用去翻 README 或应用商店评论区。
 */
@Composable
private fun ContactSection(colors: JunColors) {
    val ctx = LocalContext.current
    // 后台「App 配置 → 客服信息」保存后立刻按配置显示；邮箱可直接点按复制。
    val cs = RemoteConfigStore.customerServiceState.value
    val email = cs.email.ifBlank { "" }
    val qq = cs.qq
    val wechat = cs.wechat
    val empty = email.isBlank() && qq.isBlank() && wechat.isBlank()

    Column {
        if (empty) {
            Text(
                "客服信息暂未配置，可在总后台「App 配置 → 客服信息」里填写。",
                color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp
            )
            return@Column
        }
        if (email.isNotBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .junPressable {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("email", email))
                        android.widget.Toast.makeText(ctx, "邮箱已复制", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .clip(JunShape.item)
                    .background(colors.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text("邮箱 · $email", color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
        }
        if (qq.isNotBlank()) {
            ContactCopyRow("QQ", qq, colors) {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("qq", qq))
                android.widget.Toast.makeText(ctx, "QQ 已复制", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        if (wechat.isNotBlank()) {
            ContactCopyRow("微信", wechat, colors) {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("wechat", wechat))
                android.widget.Toast.makeText(ctx, "微信号已复制", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "问题反馈、账号与数据删除、合作洽谈都可以通过以上方式联系（点击可复制）",
            color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp
        )
    }
}

/** 一条可点击复制的客服信息（QQ / 微信） */
@Composable
private fun ContactCopyRow(
    label: String,
    value: String,
    colors: JunColors,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .junPressable { onClick() }
            .clip(JunShape.item)
            .background(colors.surface),
        contentAlignment = Alignment.Center
    ) {
        Text("$label · $value", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * 隐私与诊断：隐私政策可随时回看 / 撤回同意，引导效果数据可见，崩溃日志可查可清。
 *
 * 这三件事都属于"平时不需要、出问题时必须找得到"：把它们放进「关于」分组，
 * 而不是藏进二级菜单或让用户在文件管理器里翻 crash.log。
 */
@Composable
private fun PrivacySection(colors: JunColors) {
    val ctx = LocalContext.current
    var showPolicy by remember { mutableStateOf(false) }
    var showCrash by remember { mutableStateOf(false) }

    Column {
        // 隐私政策
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .junPressable { showPolicy = true }
                .clip(JunShape.item)
                .background(colors.subText.copy(alpha = 0.14f))
                .semantics { contentDescription = "查看隐私政策" },
            contentAlignment = Alignment.Center
        ) {
            Text("隐私政策与撤回同意", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(10.dp))

        // 引导数据
        Column(
            Modifier
                .fillMaxWidth()
                .clip(JunShape.card)
                .background(colors.subText.copy(alpha = 0.08f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text("新手引导", color = colors.text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                guideStatsSummary(ctx),
                color = colors.subText,
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )
        }

        Spacer(Modifier.height(10.dp))

        // 崩溃日志
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .junPressable { showCrash = true }
                .clip(JunShape.item)
                .background(colors.subText.copy(alpha = 0.14f))
                .semantics { contentDescription = "查看崩溃日志" },
            contentAlignment = Alignment.Center
        ) {
            Text("崩溃日志", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "崩溃会写进本地 crash.log（不上传）；这里可查看或清空",
            color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp
        )
    }

    if (showPolicy) {
        PrivacyConsentSheet(
            colors = colors,
            // 首启不再弹隐私同意，这里成为唯一的同意入口：
            // 点「同意」写标记（含版本与时间），点「不同意」撤回同意。
            onAgree = {
                PrivacyStore.markAgreed(ctx)
                showPolicy = false
            },
            onDecline = { PrivacyStore.revoke(ctx); showPolicy = false }
        )
    }

    if (showCrash) {
        val log = remember { com.jun.musiccloud.JunApp.readLastCrash(ctx.applicationContext as android.app.Application) }
        CrashLogDialog(
            log = log,
            colors = colors,
            onClear = {
                com.jun.musiccloud.JunApp.clearCrashLog(ctx.applicationContext as android.app.Application)
                showCrash = false
            },
            onDismiss = { showCrash = false }
        )
    }
}

/** 崩溃日志查看框：只读 + 清空，不做花活 */
@Composable
private fun CrashLogDialog(
    log: String?,
    colors: JunColors,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
                .clip(JunShape.card)
                .background(colors.card)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .padding(18.dp)
        ) {
            Text("崩溃日志", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            Text(
                if (log.isNullOrBlank()) "暂无崩溃记录" else log.take(1500),
                color = colors.subText,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .junPressable(feedback = PressFeedback.NONE) { onDismiss() }
                        .clip(JunShape.item)
                        .background(colors.subText.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("关闭", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                if (!log.isNullOrBlank()) {
                    Spacer(Modifier.width(10.dp))
                    // 导出：以系统分享把日志文本发出去，便于发给开发者定位
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .junPressable {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TITLE, "Jun Music 崩溃日志")
                                        putExtra(Intent.EXTRA_TEXT, log)
                                    }
                                    ctx.startActivity(Intent.createChooser(intent, "导出崩溃日志"))
                                }
                            }
                            .clip(JunShape.item)
                            .background(colors.subText.copy(alpha = 0.14f))
                            .semantics { contentDescription = "导出崩溃日志" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("导出", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .junPressable(feedback = PressFeedback.NONE) { onClear() }
                            .clip(JunShape.item)
                            .background(colors.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "清空",
                            color = colors.primary.readableTextOn(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}


/**
 * 通知权限：首启问过一次，拒绝后不能只在系统设置里找。
 * 已开启时点进去看系统通知设置，未开启时先走运行时授权，仍拒绝再跳系统页。
 */
/**
 * 6.6：歌词设置的**实时预览**。
 *
 * 用户原话："建议在设置那里给一些调节的地方给个预览效果，尤其是歌词那部分，
 * 这样就不用每次调完再看一遍，不合适再回来调"。
 *
 * 这里直接读 state 里的字号 / 对齐 / 逐字流光开关，用一条循环扫光的示例歌词
 * 把三者同时演示出来。两层 Text 叠在一起：底层是暗色全句，上层是主色，
 * 用 drawWithContent + clipRect 按进度裁出「已唱」部分，和播放页的扫光观感一致。
 * 不用 Brush stops，避免进度变化时 colorStop 顺序非法导致崩溃。
 */
@Composable
private fun LyricSettingsPreview(state: PlayerUiState, colors: JunColors) {
    val transition = rememberInfiniteTransition(label = "lyricPreview")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing)
        ),
        label = "lyricPreviewSweep"
    )
    val align = when (state.lyricAlign) {
        0 -> TextAlign.Start
        2 -> TextAlign.End
        else -> TextAlign.Center
    }
    val sample = "星光落在窗前 慢慢亮起来"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text("实时预览 · 调完立刻能看到效果", color = colors.subText, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth()) {
            Text(
                sample,
                color = colors.subText.copy(alpha = 0.5f),
                fontSize = state.lyricFontSize.sp,
                fontWeight = FontWeight.Bold,
                textAlign = align,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
            val lit = if (state.karaokeLyric) sweep else 1f
            Text(
                sample,
                color = colors.primary,
                fontSize = state.lyricFontSize.sp,
                fontWeight = FontWeight.Bold,
                textAlign = align,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        clipRect(right = size.width * lit.coerceIn(0f, 1f)) {
                            this@drawWithContent.drawContent()
                        }
                    }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "字号 ${state.lyricFontSize.toInt()}sp · " +
                when (state.lyricAlign) { 0 -> "靠左"; 2 -> "靠右"; else -> "居中" } +
                if (state.karaokeLyric) " · 逐字流光" else " · 整句点亮",
            color = colors.subText,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun NotificationPermissionSection(colors: JunColors) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(notificationsGranted(ctx)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = notificationsGranted(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        granted = notificationsGranted(ctx)
        if (!granted) openAppNotificationSettings(ctx)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .junPressableRow {
                when {
                    granted -> openAppNotificationSettings(ctx)
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU ->
                        launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else -> openAppNotificationSettings(ctx)
                }
            }
            .clip(JunShape.item)
            .background(colors.subText.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { contentDescription = "设置通知权限" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("通知权限", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(
                "播放控制、歌词通知与后台播放状态",
                color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (granted) "已开启" else "去开启",
            color = if (granted) colors.primary else colors.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black
        )
    }
}

/**
 * 6.7.0：定时提醒设置区。
 *
 * 用户原话：
 * 「安卓原生系统通知，样式和高德状态栏通知一样，带图标、标题、内容；
 *   自动创建 NotificationChannel，首次打开申请通知权限；
 *   后台可以自定义间隔时间，比如每隔 N 分钟自动弹出通知；
 *   开启后台服务，App 退到后台也能跑定时；后台可以开启/关闭；点通知自动打开 APP」
 *
 * 这里的每一项都对应一句：
 *  · 开关 → ReminderScheduler.setEnabled（起/停前台服务 + 布/撤看门狗）
 *  · 间隔 → 5 分钟 ~ 6 小时，改完立刻重起循环，无需重启 App
 *  · 标题/内容 → 存 prefs，下次弹的就是用户自己写的那句
 *  · 预览 → 照着通知的真实排布画一遍，避免"开了但不知道长什么样"
 */
@Composable
private fun ReminderSection(colors: JunColors) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var on by remember { mutableStateOf(ReminderPrefs.isOn(ctx)) }
    var interval by remember { mutableIntStateOf(ReminderPrefs.intervalMin(ctx)) }
    var customTitle by remember { mutableStateOf(ReminderPrefs.title(ctx)) }
    var customText by remember { mutableStateOf(ReminderPrefs.text(ctx)) }
    var hint by remember { mutableStateOf("") }
    var granted by remember { mutableStateOf(notificationsGranted(ctx)) }

    val launcher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = notificationsGranted(ctx)
        if (ok) {
            ReminderScheduler.setEnabled(ctx, true)
            on = true
            hint = "已开启：每 ${ReminderPrefs.intervalMin(ctx)} 分钟提醒一次"
        } else {
            hint = "没拿到通知权限，提醒发不出来"
            openAppNotificationSettings(ctx)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SettingToggleRow(
            label = "定时提醒（系统通知）",
            checked = on,
            colors = colors,
            subtitle = if (on) {
                "每 $interval 分钟弹一条通知 · 退到后台也会响 · 点通知回到 App"
            } else {
                "按设定的间隔在状态栏提醒你回来听歌，随时可关"
            }
        ) {
            if (on) {
                ReminderScheduler.setEnabled(ctx, false)
                on = false
                hint = "已关闭，后台服务已停止"
            } else if (android.os.Build.VERSION.SDK_INT >= 33 && !granted) {
                // 首次开启先要系统通知权限，否则开了也弹不出来
                launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                ReminderScheduler.setEnabled(ctx, true)
                on = true
                hint = "已开启：每 $interval 分钟提醒一次，后台持续运行"
            }
        }

        if (on) {
            Spacer(Modifier.height(10.dp))
            Text("提醒间隔", color = colors.subText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            ReminderPrefs.INTERVAL_CHOICES.chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    row.forEach { min ->
                        val active = min == interval
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(JunShape.item)
                                .background(if (active) colors.primary.copy(alpha = 0.18f) else colors.row)
                                .then(
                                    if (active) Modifier.border(
                                        1.dp, colors.primary.copy(alpha = 0.55f), JunShape.item
                                    ) else Modifier
                                )
                                .junPressable(scaleDown = 0.94f) {
                                    interval = min
                                    ReminderPrefs.setInterval(ctx, min)
                                    // 立刻按新间隔重起循环（不用等这一轮睡完）
                                    if (ReminderPrefs.isOn(ctx)) ReminderService.start(ctx)
                                    hint = "间隔已改为 ${reminderIntervalLabel(min)}"
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                reminderIntervalLabel(min),
                                color = if (active) colors.primary else colors.subText,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                    // 最后一行不足 3 个时补空位，保证宽度一致
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(4.dp))
            Text("通知标题", color = colors.subText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            ReminderInput(
                value = customTitle,
                placeholder = "该听首歌了",
                colors = colors,
                minHeight = 42.dp
            ) {
                customTitle = it.take(40)
                ReminderPrefs.setTitle(ctx, customTitle)
            }
            Spacer(Modifier.height(8.dp))
            Text("通知内容", color = colors.subText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            ReminderInput(
                value = customText,
                placeholder = "你有一阵子没打开 JunMusic 了，点一下接着听刚才那首。",
                colors = colors,
                minHeight = 64.dp
            ) {
                customText = it.take(160)
                ReminderPrefs.setText(ctx, customText)
            }

            Spacer(Modifier.height(10.dp))
            // 预览：照着系统通知的排布画一遍
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(JunShape.item)
                    .background(colors.row)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_stat_jun),
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        customTitle.ifBlank { "该听首歌了" },
                        color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        customText.ifBlank { "点一下回到 JunMusic 继续听" },
                        color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(JunShape.item)
                        .background(colors.primary.copy(alpha = 0.18f))
                        .junPressable(scaleDown = 0.95f) {
                            val ok = ReminderNotification.showReminderNow(ctx)
                            hint = if (ok) "已发一条，去状态栏看看" else "通知被系统拦了，去设置里打开通知权限"
                        }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("立即试一条", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(JunShape.item)
                        .background(colors.row)
                        .junPressable(scaleDown = 0.95f) {
                            openAppNotificationSettings(ctx)
                        }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("系统通知设置", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (hint.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(hint, color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

/** 间隔文案：分钟数大了就换算成小时，避免"360 分钟"这种读起来费劲的写法 */
private fun reminderIntervalLabel(min: Int): String = when {
    min < 60 -> "$min 分钟"
    min % 60 == 0 -> "${min / 60} 小时"
    else -> "${min / 60} 小时 ${min % 60} 分"
}

/** 提醒文案输入框（单行标题 / 多行内容共用；比 SettingsTextArea 矮，适合放在密集设置里） */
@Composable
private fun ReminderInput(
    value: String,
    placeholder: String,
    colors: JunColors,
    minHeight: androidx.compose.ui.unit.Dp,
    onValueChange: (String) -> Unit
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontFamily = LocalJunUiFamily.current,
            color = colors.text,
            fontSize = 12.sp,
            lineHeight = 17.sp
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(JunShape.item)
            .background(colors.card)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box {
                if (value.isBlank()) {
                    Text(placeholder, color = colors.subText.copy(alpha = 0.65f), fontSize = 12.sp)
                }
                inner()
            }
        }
    )
}

private fun notificationsGranted(ctx: Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        return ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    return androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
}

private fun openAppNotificationSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * 重看新手引导 / 新功能导览：引导只出现一次，但"忘了那个按钮在哪"这件事会在两周后发生。
 * 给常驻入口，用户想看就能立刻再看一遍（不重置任何标记，不影响下次启动）。
 */
@Composable
private fun ReplayGuideSection(
    colors: JunColors,
    onReplayGuide: () -> Unit,
    onFeatureTour: () -> Unit,
    onWhatsNew: () -> Unit = {}
) {
    // 6.9.3：用户要求去掉旧的「欢迎绘本 / 聚光灯导览」，统一成一份
    // 「从 6.3 到现在新增了什么 + 每条去哪儿找」的清单。
    // onReplayGuide / onFeatureTour 两个参数保留，仅为兼容调用点，不再渲染入口。
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .junPressable { onWhatsNew() }
                .clip(JunShape.item)
                .background(colors.subText.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Text("新功能与更新记录", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "从 6.3 到现在新增的功能一目了然，每条都标了去哪儿找；升级后也会自动弹一次",
            color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp
        )
    }
}

/**
 * 悬浮歌词设置区：开关 + 字号/长度/透明度三个滑条，全部读写 jun_prefs，
 * 由服务侧 FloatingLyric 的 OnSharedPreferenceChangeListener 实时生效。
 * 无悬浮窗权限时给出授权入口，从系统授权页返回后自动重新同步显隐。
 */
@Composable
private fun FloatingLyricSection(colors: JunColors, headerColor: Color) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("jun_prefs", Context.MODE_PRIVATE)

    var enabled by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_ENABLED, true)) }
    var granted by remember { mutableStateOf(FloatingLyric.hasPermission(ctx)) }
    var font by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_FONT, FloatingLyric.FONT_DEFAULT)) }
    var widthFrac by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_WIDTH, FloatingLyric.WIDTH_DEFAULT)) }
    var bg by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_BG, FloatingLyric.BG_DEFAULT)) }
    var lock by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_LOCK, false)) }
    var trans by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_TRANS, true)) }
    var textAlpha by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_TEXT_ALPHA, FloatingLyric.ALPHA_DEFAULT)) }

    // 从系统授权页返回时刷新权限并让服务重新同步显隐
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = FloatingLyric.hasPermission(ctx)
                FloatingLyric.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun writeBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    fun writeFloat(key: String, v: Float) = prefs.edit().putFloat(key, v).apply()

    Text("悬浮歌词", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "在桌面/其它应用上方常驻显示当前歌词，单行可拖动移动位置",
        color = colors.subText, fontSize = 11.sp
    )
    Spacer(Modifier.height(10.dp))
    SettingToggleRow(
        "桌面悬浮歌词",
        enabled,
        colors,
        subtitle = if (!granted) "尚未授予悬浮窗权限" else "常驻前台服务，退到后台也跟随"
    ) {
        val next = !enabled
        enabled = next
        writeBool(FloatingLyric.KEY_ENABLED, next)
        // 首次开启且无权限：跳系统授权页（授权返回后 ON_RESUME 自动显示）
        if (next && !FloatingLyric.hasPermission(ctx)) FloatingLyric.requestPermission(ctx)
    }

    AnimatedVisibility(
        visible = enabled,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column(Modifier.padding(top = 6.dp)) {
            if (!granted) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .junPressable { FloatingLyric.requestPermission(ctx) }
                        .clip(JunShape.item)
                        .background(colors.surface)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("去授予悬浮窗权限", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.primary)
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("字号 · ${font.toInt()} sp", color = colors.subText, fontSize = 11.sp)
            CompactSlider(
                value = font,
                onValueChange = { font = it; writeFloat(FloatingLyric.KEY_FONT, it) },
                valueRange = 12f..40f,
                activeColor = colors.primary,
                inactiveColor = colors.surface
            )

            Spacer(Modifier.height(10.dp))
            Text("长度 · ${(widthFrac * 100).toInt()}% 屏宽", color = colors.subText, fontSize = 11.sp)
            CompactSlider(
                value = widthFrac,
                onValueChange = { widthFrac = it; writeFloat(FloatingLyric.KEY_WIDTH, it) },
                valueRange = 0.3f..1f,
                activeColor = colors.primary,
                inactiveColor = colors.surface
            )

            Spacer(Modifier.height(10.dp))
            Text("黑底透明度 · ${(bg * 100).toInt()}%", color = colors.subText, fontSize = 11.sp)
            CompactSlider(
                value = bg,
                onValueChange = { bg = it; writeFloat(FloatingLyric.KEY_BG, it) },
                valueRange = 0f..1f,
                activeColor = colors.primary,
                inactiveColor = colors.surface
            )

            Spacer(Modifier.height(10.dp))
            Text("文字不透明度 · ${(textAlpha * 100).toInt()}%", color = colors.subText, fontSize = 11.sp)
            CompactSlider(
                value = textAlpha,
                onValueChange = { textAlpha = it; writeFloat(FloatingLyric.KEY_TEXT_ALPHA, it) },
                valueRange = 0.2f..1f,
                activeColor = colors.primary,
                inactiveColor = colors.surface
            )

            Spacer(Modifier.height(10.dp))
            SettingToggleRow(
                "锁定位置",
                lock,
                colors,
                subtitle = "锁定后不可拖动、点击穿透，不会挡住其它应用"
            ) {
                lock = !lock
                writeBool(FloatingLyric.KEY_LOCK, lock)
            }
            Spacer(Modifier.height(10.dp))
            // 6.9.5：锁屏显示歌词（用户需求）。vivo/OPPO/小米等 ROM 需要同时允许
            // 「显示悬浮窗」，MIUI/澎湃额外要有「锁屏显示」权限 —— 给出引导文案。
            var lockscreen by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_SHOW_ON_LOCKSCREEN, true)) }
            SettingToggleRow(
                "锁屏显示歌词",
                lockscreen,
                colors,
                subtitle = "亮屏未解锁时也显示歌词；若不出现，请到系统的「锁屏显示 / 悬浮窗」权限里允许本应用"
            ) {
                lockscreen = !lockscreen
                writeBool(FloatingLyric.KEY_SHOW_ON_LOCKSCREEN, lockscreen)
            }
            Spacer(Modifier.height(10.dp))
            SettingToggleRow(
                "译文第二行",
                trans,
                colors,
                subtitle = "原文一行、译文一行；没有译文时只显示原文"
            ) {
                trans = !trans
                writeBool(FloatingLyric.KEY_TRANS, trans)
            }
        }
    }
}

/**
 * 设置项开关行。原来每个开关都是一整段 Row + Box 手写胶囊，
 * 加一个开关就复制 20 行；抽出来后新增开关只需一行调用。
 */
/**
 * 在线播放缓存：占用数字 + 一键清空。
 *
 * 这套缓存没有开关也没有容量选择器（那套按用户要求撤掉了），默认开、256MB、
 * 写满 LRU 自动覆盖——代价是它变成一个"看不见的行为"。这里至少让它可见可控：
 * 展开本区块才统计占用（磁盘遍历，不该在进设置页时就白跑一次）。
 */
@Composable
private fun StreamCacheSection(
    state: PlayerUiState,
    colors: JunColors,
    viewModel: PlayerViewModel
) {
    LaunchedEffect(Unit) { viewModel.refreshStreamCacheUsage() }
    val bytes = state.streamCacheBytes
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(com.jun.musiccloud.data.PlaybackCache.PREFS, android.content.Context.MODE_PRIVATE) }
    // 6.3.1：容量选择器回来了。之前按"简化界面"的要求撤掉，结果所有用户都被钉在
    // 256MB——大存储机器上根本不够用，写满之后反复弹提示、重试也没用。
    var selected by remember { mutableStateOf(com.jun.musiccloud.data.PlaybackCache.selectedLimitMb(prefs)) }
    val effective = if (selected == com.jun.musiccloud.data.PlaybackCache.AUTO)
        com.jun.musiccloud.data.PlaybackCache.autoLimitMb(ctx) else selected

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .junSurface(GlassLevel.CARD, colors, JunShape.item)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("在线播放缓存", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (bytes < 0L) "正在统计占用…"
                    else "已占用 ${formatFileSize(bytes)} · 上限 ${formatCacheLimit(effective, selected)}，写满后自动覆盖最早听过的歌",
                    color = colors.subText, fontSize = 10.sp, lineHeight = 13.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .junPressable(scaleDown = 0.92f) { viewModel.clearStreamCache() }
                    .clip(JunShape.item)
                    .background(colors.dangerBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("清空", color = colors.dangerText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(8.dp))

        // 档位：128MB ~ 4GB + 自动。自动按分区剩余空间算，永远不会把存储吃穿。
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            cacheLimitChips(colors, selected) { mb ->
                selected = mb
                viewModel.setStreamCacheLimit(mb)
            }
        }
    }
}

/** 一行放不下的档位折两行：这里用两个 Row 手动排，避免引入 FlowRow 的实验性 API */
@Composable
private fun cacheLimitChips(
    colors: JunColors,
    selected: Int,
    onPick: (Int) -> Unit
) {
    val options = listOf(
        128 to "128M", 256 to "256M", 512 to "512M",
        1024 to "1G", 2048 to "2G", 4096 to "4G",
        com.jun.musiccloud.data.PlaybackCache.AUTO to "自动"
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (mb, label) ->
                    val on = mb == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(JunShape.item)
                            .background(if (on) colors.primary.copy(alpha = 0.18f) else colors.row)
                            .then(
                                if (on) Modifier.border(1.dp, colors.primary.copy(alpha = 0.55f), JunShape.item)
                                else Modifier
                            )
                            .junPressable(scaleDown = 0.94f) { onPick(mb) }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (on) colors.primary else colors.subText,
                            fontSize = 11.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/** 档位文案：自动要带上算出来的实际值，否则用户不知道"自动"到底是多大 */
private fun formatCacheLimit(effectiveMb: Int, selected: Int): String =
    if (selected == com.jun.musiccloud.data.PlaybackCache.AUTO) "自动 $effectiveMb MB"
    else if (effectiveMb >= 1024) "${effectiveMb / 1024} GB"
    else "$effectiveMb MB"

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    colors: JunColors,
    subtitle: String? = null,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            // 设置里的每一行也跟着回弹：整页原本只有开关拨钮会动、行本身按下去是硬的，
            // 读起来像"一张卡片上贴了个活的小机关"。幅度取行级（很小），只软不跳。
            .junPressableRow(scaleDown = 0.985f) { onToggle() }
            .junSurface(GlassLevel.CARD, colors, JunShape.item)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = colors.subText, fontSize = 10.sp, lineHeight = 13.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        // 拨钮位置走动画，不是瞬移（果冻开关下为弹性滑动）
        val knobAlign by animateFloatAsState(
            targetValue = if (checked) 1f else 0f,
            animationSpec = JunMotion.knob(),
            label = "toggleKnob"
        )
        Box(
            Modifier
                .size(40.dp, 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked) colors.primary else colors.subText.copy(alpha = 0.55f))
                .padding(2.dp),
            contentAlignment = androidx.compose.ui.BiasAlignment(
                horizontalBias = -1f + 2f * knobAlign,
                verticalBias = 0f
            )
        ) {
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(colors.surface))
        }
    }
}

/**
 * 6.3：下载格式选择器。
 *
 * 三档：跟音质走（默认）/ 只要 MP3 / 只要无损 FLAC。
 *
 * 为什么不用两档：用户说"能选 MP3 或 FLAC"，但 6.2 的行为是"跟着播放音质走"，
 * 对已经调好音质的人这反而是最省心的。直接砍成两档，等于强行让所有人做一次选择。
 *
 * 下面那行小字是**诚实说明**：无损不是每一首都有（版权 / 会员 / 曲库缺失），
 * 拿不到时会退回 MP3。不写这句，用户下完发现是 mp3 文件就会以为这个开关坏了。
 */
@Composable
private fun DownloadFormatPicker(
    colors: JunColors,
    current: String,
    onPick: (com.jun.musiccloud.data.DownloadFormat) -> Unit
) {
    val list = com.jun.musiccloud.data.DownloadFormat.entries
    val picked = list.firstOrNull { it.name == current } ?: com.jun.musiccloud.data.DownloadFormat.FOLLOW
    Column(
        Modifier
            .fillMaxWidth()
            .junSurface(GlassLevel.CARD, colors, JunShape.item)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("下载格式", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(picked.desc, color = colors.subText, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            list.forEach { f ->
                val on = f == picked
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                        .junPressable(scaleDown = 0.94f) { onPick(f) }
                        .junSurface(
                            level = GlassLevel.CARD,
                            colors = colors,
                            shape = JunShape.chip,
                            solid = if (on) colors.card.copy(alpha = 0.6f) else colors.card,
                            accent = if (on) colors.primary else null
                        )
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        f.label,
                        color = if (on) colors.primary.readableOn(colors.card, 3.2f) else colors.subText,
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (picked == com.jun.musiccloud.data.DownloadFormat.FOLLOW)
                "播放页选了什么音质，下载就是什么格式"
            else "个别歌曲没有你选的格式（版权或会员限制），会退回 MP3 并如实命名，不会假装是无损",
            color = colors.subText, fontSize = 9.sp, lineHeight = 12.sp
        )
    }
}

/**
 * 6.3：整体动画快慢选择器。
 *
 * 设计上的三个取舍
 * --------------
 * 1. **五档而不是滑杆**。滑杆看着高级，但"0.83 和 0.87 有什么区别"没人答得上来；
 *    五档各有名字（极快/快/标准/慢/很慢），用户学到的是"我适合'快'"这件事，
 *    换手机时也能一次选回来。
 * 2. **每颗胶囊自己弹一下**。选中瞬间用 [JunMotion.pop] 做一次按压缩放——
 *    这就是当前速度最直观的预览，不用额外做"试听动画"面板。
 *    缺点是新速度要下一颗胶囊才生效（动画参数在构造时读取），这里用文案讲清楚。
 * 3. **默认值写"标准"并标注**。用户的诉求是"默认稍微快一点、追求动画的人自己调慢"，
 *    所以默认档在界面上叫"标准"但实际上是比设计基准快 15% 的那一档；
 *    文案里直接说"默认就是这一档"，避免用户以为要手动调才生效。
 */
@Composable
private fun MotionSpeedPicker(
    colors: JunColors,
    current: Float,
    onPick: (Float) -> Unit
) {
    val selectedIndex = JunMotion.SPEED_STEPS.indexOfLast { it.first <= current + 0.001f }
        .let { if (it < 0) 0 else it }
    Column(
        Modifier
            .fillMaxWidth()
            .junSurface(GlassLevel.CARD, colors, JunShape.item)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("过渡动画快慢", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(
                JunMotion.speedLabel(current),
                color = colors.primary.readableOn(colors.card, 3.2f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text("默认就是这一档", color = colors.subText, fontSize = 9.sp)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "小播放器↔播放页的翻页、按钮按压回弹、面板弹出，全部按这个速度走",
            color = colors.subText, fontSize = 10.sp, lineHeight = 13.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            JunMotion.SPEED_STEPS.forEachIndexed { i, (value, label) ->
                val on = i == selectedIndex
                // 选中态用一次弹簧把胶囊"按"出来：这就是当前速度的即时预览
                val bump = remember { Animatable(1f) }
                LaunchedEffect(on) {
                    if (on) {
                        bump.snapTo(0.9f)
                        bump.animateTo(1f, JunMotion.pop())
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                        .graphicsLayer { scaleX = bump.value; scaleY = bump.value }
                        .junPressable(scaleDown = 0.94f) { onPick(value) }
                        .junSurface(
                            level = GlassLevel.CARD,
                            colors = colors,
                            shape = JunShape.chip,
                            solid = if (on) colors.card.copy(alpha = 0.6f) else colors.card,
                            accent = if (on) colors.primary else null
                        )
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (on) colors.primary.readableOn(colors.card, 3.2f) else colors.subText,
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "调完不用退出：下一次点按、下一个弹出的面板立刻是新速度",
            color = colors.subText, fontSize = 9.sp
        )
    }
}

/**
 * 6.6 动效风格三档选择（用户："除了果冻Q弹也会弄一个别的效果吧……设置里面能切换"）。
 * 版式与 [MotionSpeedPicker] 同款（胶囊三选一，选中即预览）。
 */
@Composable
private fun MotionStylePicker(
    colors: JunColors,
    current: String,
    onPick: (String) -> Unit
) {
    val styles = com.jun.musiccloud.ui.JunMotion.MotionStyle.entries
    val currentStyle = com.jun.musiccloud.ui.JunMotion.MotionStyle.of(current)
    Column(
        Modifier
            .fillMaxWidth()
            .junSurface(GlassLevel.CARD, colors, JunShape.item)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("动效风格", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(
                currentStyle.label,
                color = colors.primary.readableOn(colors.card, 3.2f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "果冻Q弹＝软弹簧冲过头再荡回来；顺滑＝不过冲、快而稳（老机型推荐）；超弹＝比果冻更荡。点一下全 App 立刻换",
            color = colors.subText, fontSize = 10.sp, lineHeight = 13.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            styles.forEach { st ->
                val on = st == currentStyle
                val bump = remember { Animatable(1f) }
                LaunchedEffect(on) {
                    if (on) {
                        bump.snapTo(0.9f)
                        bump.animateTo(1f, JunMotion.pop())
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                        .graphicsLayer { scaleX = bump.value; scaleY = bump.value }
                        .junPressable(scaleDown = 0.94f) { onPick(st.name) }
                        .junSurface(
                            level = GlassLevel.CARD,
                            colors = colors,
                            shape = JunShape.chip,
                            solid = if (on) colors.card.copy(alpha = 0.6f) else colors.card,
                            accent = if (on) colors.primary else null
                        )
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        st.label,
                        color = if (on) colors.primary.readableOn(colors.card, 3.2f) else colors.subText,
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSwatch(
    label: String,
    selected: Boolean,
    colors: JunColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .junPressable { onClick() }
            .junSurface(
                level = GlassLevel.CARD,
                colors = colors,
                shape = JunShape.item,
                solid = if (selected) colors.card.copy(alpha = 0.6f) else colors.card,
                accent = if (selected) colors.primary else null
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) colors.primary.readableOn(colors.card, 3.2f) else colors.subText,
            fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 界面排布选择卡：上方一枚手机缩略预览，下方标签。
 *
 * 只用色块表达结构——纵向是两列格子，横向是三张并排的竖卡，堆叠是错开的一沓。
 * 和参考图同构，但完全 Canvas 自绘（项目不引图标库，也不该为三张示意图加位图资源）。
 */
@Composable
private fun LayoutSwatch(
    layout: JunLayout,
    selected: Boolean,
    colors: JunColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = colors.primary.readableOn(colors.card, 3.2f)
    Column(
        modifier
            .junPressable { onClick() }
            .junSurface(
                level = GlassLevel.CARD,
                colors = colors,
                shape = JunShape.item,
                solid = if (selected) colors.card.copy(alpha = 0.6f) else colors.card,
                accent = if (selected) colors.primary else null
            )
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.size(width = 26.dp, height = 34.dp)) {
            val stroke = 1.4.dp.toPx()
            val frame = accent.copy(alpha = if (selected) 0.9f else 0.42f)
            val fill = accent.copy(alpha = if (selected) 0.55f else 0.28f)
            val r = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            val inner = CornerRadius(2.dp.toPx(), 2.dp.toPx())

            // 手机外框
            drawRoundRect(
                color = frame,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = r,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )

            val pad = 4.dp.toPx()
            val w = size.width - pad * 2
            val h = size.height - pad * 2

            when (layout) {
                JunLayout.LIST -> {
// 纵向歌单：标题栏 + 一条条歌曲行
                     val gap = 2.5.dp.toPx()
                     val rowH = (h - gap * 4f) / 5f
                     drawRoundRect(fill, Offset(pad, pad), Size(w, rowH * 0.72f), inner)
                     for (row in 0 until 4) {
                         drawRoundRect(fill.copy(alpha = fill.alpha * 0.82f),
                             Offset(pad, pad + rowH * (row + 1.05f)), Size(w, rowH * 0.72f), inner)
                     }
                }

                JunLayout.CAROUSEL -> {
                    // 三张并排竖卡，中间那张满高、两侧被裁掉一半
                    val cw = w * 0.46f
                    val gap = w * 0.10f
                    val cy = pad + h * 0.16f
                    val ch = h * 0.68f
                    for (i in -1..1) {
                        val x = pad + w / 2f - cw / 2f + i * (cw + gap)
                        drawRoundRect(
                            color = if (i == 0) fill else fill.copy(alpha = fill.alpha * 0.5f),
                            topLeft = Offset(x, cy),
                            size = Size(cw, ch),
                            cornerRadius = inner
                        )
                    }
                }

                JunLayout.STACK -> {
                    // 一沓错开的卡：从最里层往外画，最后那张最实
                    val cw = w * 0.62f
                    val ch = h * 0.60f
                    val stepX = w * 0.09f
                    val stepY = h * 0.05f
                    for (d in 2 downTo 0) {
                        drawRoundRect(
                            color = fill.copy(alpha = fill.alpha * (1f - 0.28f * d)),
                            topLeft = Offset(
                                pad + w - cw - stepX * d - w * 0.04f,
                                pad + h / 2f - ch / 2f - stepY * d
                            ),
                            size = Size(cw, ch),
                            cornerRadius = inner
                        )
                    }
                }

JunLayout.COMPACT -> {
                     // 唱片墙：手机框内铺满一格格方形封面卡（3 列 × 4 行网格）
                     val cols = 3
                     val rows = 4
                     val cellGap = 1.6.dp.toPx()
                     val cellW = (w - cellGap * (cols - 1)) / cols
                     val cellH = (h - cellGap * (rows - 1)) / rows
                     for (row in 0 until rows) {
                         for (col in 0 until cols) {
                             // 交替深浅让"一格格封面"更清楚，不是一块实心
                             val a = if ((row + col) % 2 == 0) fill.alpha else fill.alpha * 0.6f
                             drawRoundRect(
                                 color = fill.copy(alpha = a),
                                 topLeft = Offset(pad + col * (cellW + cellGap), pad + row * (cellH + cellGap)),
                                 size = Size(cellW, cellH),
                                 cornerRadius = inner
                             )
                         }
                     }
                 }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            layout.label,
            color = if (selected) accent else colors.subText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun ThemeSwatch(theme: JunTheme, selected: Boolean, colors: JunColors, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // 色板必须用「当前明暗模式下实际生效」的色对：黑白主题的枚举值是白+黑，
    // 浅色模式下白色圆点和白色标题会直接消失在白卡上。
    val (swatchPrimary, swatchSecondary) = theme.resolvedPair(colors.isDark)
    val cardBg = if (selected) swatchPrimary.copy(alpha = 0.15f) else colors.card
    // 选中态标题落在半透明主题底上，仍以卡片色为背景基准反算可读色
    val labelColor = if (selected) swatchPrimary.readableOn(colors.card) else colors.text.copy(alpha = 0.8f)
    Column(
        modifier
            .junPressable { onClick() }
            .junSurface(
                level = GlassLevel.CARD,
                colors = colors,
                shape = JunShape.item,
                solid = cardBg,
                accent = if (selected) swatchPrimary else null
            )
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row {
            // 圆点描边：纯白/纯黑圆点在同色卡片上没有边界，加一圈细描边兜底
            val dotBorder = colors.subText.copy(alpha = 0.45f)
            Box(
                Modifier.size(12.dp).clip(RoundedCornerShape(6.dp))
                    .background(swatchPrimary)
                    .border(1.dp, dotBorder, RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(3.dp))
            Box(
                Modifier.size(12.dp).clip(RoundedCornerShape(6.dp))
                    .background(swatchSecondary)
                    .border(1.dp, dotBorder, RoundedCornerShape(6.dp))
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            theme.label,
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}


// ==================== 自定义配色（HSV 取色） ====================

private fun argbOf(hsv: FloatArray): Long =
    (android.graphics.Color.HSVToColor(hsv).toLong() and 0xFFFFFFFFL)

private fun hsvOf(argb: Long): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV(argb.toInt(), out)
    return out
}

@Composable
private fun CustomColorSection(
    primaryArgb: Long,
    secondaryArgb: Long,
    colors: JunColors,
    onColorsChange: (Long, Long) -> Unit
) {
    // HSV 草稿：改成「持有 FloatArray 的 mutableStateOf」，每次改动写入一个**新数组**。
    // 原来用无 key 的 remember { FloatArray } 原地改 + 一个从未被读取的 bump，
    // 拖动时本节根本不重组，预览/滑块只能靠上层回传的间接重组刷新，
    // 表现为「拖动不跟手、要重进设置才看到颜色变」。
    // 用新数组引用触发重组：滑块 value 读当前数组，引用一变就重绘，且不重建滑块子树，
    // 拖动手势状态得以保留（比 key(bump) 更安全）。
    var primaryHsv by remember { mutableStateOf(hsvOf(primaryArgb)) }
    var secondaryHsv by remember { mutableStateOf(hsvOf(secondaryArgb)) }
    var editSecondary by remember { mutableStateOf(false) }
    val activeHsv = if (editSecondary) secondaryHsv else primaryHsv
    val onSlider: (Int, Float) -> Unit = { index, v ->
        val updated = activeHsv.copyOf()
        updated[index] = v.coerceIn(0f, if (index == 0) 360f else 1f)
        if (editSecondary) secondaryHsv = updated else primaryHsv = updated
        onColorsChange(argbOf(primaryHsv), argbOf(secondaryHsv))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ColorPreviewBox(argbOf(primaryHsv), "主色", !editSecondary, colors,
            Modifier.weight(1f)) { editSecondary = false }
        ColorPreviewBox(argbOf(secondaryHsv), "次色", editSecondary, colors,
            Modifier.weight(1f)) { editSecondary = true }
    }
    Spacer(Modifier.height(12.dp))

    Text("色相 · ${activeHsv[0].toInt()}", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    CompactSlider(value = activeHsv[0], onValueChange = { onSlider(0, it) },
        valueRange = 0f..360f, activeColor = colors.primary, inactiveColor = colors.surface)
    Spacer(Modifier.height(8.dp))
    Text("饱和度 · ${(activeHsv[1] * 100).toInt()}%", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    CompactSlider(value = activeHsv[1], onValueChange = { onSlider(1, it) },
        valueRange = 0f..1f, activeColor = colors.primary, inactiveColor = colors.surface)
    Spacer(Modifier.height(8.dp))
    Text("明度 · ${(activeHsv[2] * 100).toInt()}%", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    CompactSlider(value = activeHsv[2], onValueChange = { onSlider(2, it) },
        valueRange = 0f..1f, activeColor = colors.primary, inactiveColor = colors.surface)
}

@Composable
private fun ColorPreviewBox(
    argb: Long,
    label: String,
    selected: Boolean,
    colors: JunColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = Color(argb)
    Column(
        modifier
            .height(52.dp)
            .junPressable { onClick() }
            .clip(JunShape.item)
            .background(c)
            .then(if (selected) Modifier.border(2.dp, colors.text, JunShape.item) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.readableTextOn())
    }
}

// ==================== 下载管理弹层 ====================

/**
 * 下载管理：查看下载进度 / 已完成 / 失败任务，支持全选批量删除、
 * 分享已下载文件、用其他应用打开、重试失败任务。
 */
@Composable
internal fun DownloadManagementSheet(
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit,
    onDelete: (Long) -> Unit,
    onDeleteBatch: (List<Long>) -> Unit,
    onRetry: (Long) -> Unit,
    /** 6.9.1 专听已下载：onPlayAll(tasks, 起点, 是否随机) */
    onPlayAll: (List<DownloadTask>, Int, Boolean) -> Unit = { _, _, _ -> }
) {
    val ctx = LocalContext.current
    val tasks = state.downloadTasks.sortedByDescending { it.createdAt }
    val inProgress = tasks.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
    val completed = tasks.filter { it.status == DownloadStatus.COMPLETED }
    val failed = tasks.filter { it.status == DownloadStatus.FAILED }

    // 选择模式
    var selectMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }

    fun toggleSelect(id: Long) {
        if (id in selected) selected.remove(id) else selected.add(id)
    }
    fun selectAll(ids: List<Long>) {
        selected.clear()
        selected.addAll(ids)
    }

    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val drag = rememberSheetDragState()

    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        contentModifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 10.dp, bottom = 24.dp)
    ) {
        SheetDragHandle(drag, onDismiss, colors.card)
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("下载管理", color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black)
            // 6.6.7：右上角这颗原来叫「全选」——但按下只是**进入选择模式**，
            // 一首都没选上。用户点完没反应，观感就是"全选功能根本没有"。
            // 现在叫「批量管理」（进选择模式），真正的「全选 / 取消全选」
            // 放进选择模式后的操作栏，一点就真选中。
            if (selectMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已选 ${selected.size}",
                        color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier
                            .junPressable { selectMode = false; selected.clear() }
                            .clip(JunShape.item)
                            .background(colors.card)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("取消", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (tasks.isNotEmpty()) {
                Box(
                    Modifier
                        .junPressable {
                            // 6.6.7：进入批量管理就顺手把可删的全部选上——
                            // 用户要的"全选"一步到位，想删几个再手动去掉勾。
                            selectMode = true
                            selectAll((completed + failed).map { it.id })
                        }
                        .clip(JunShape.item)
                        .background(colors.card)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("批量管理", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 6.9.1：「我的下载」专听按钮排。有人就是只想听自己下载的歌——
        // 这两个按钮把已完成列表一键建成队列，file:// 直读本机文件，
        // 不走网络解析：飞行模式照样完整听，不存在"解析失败/无版权"。
        if (!selectMode && completed.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .junPressable(scaleDown = 0.96f, feedback = PressFeedback.CLICK) {
                            onPlayAll(completed, 0, false)
                        }
                        .clip(JunShape.item)
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("播放全部 ${completed.size} 首", color = colors.primary.readableOn(colors.primary, 3.2f),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .junPressable(scaleDown = 0.96f, feedback = PressFeedback.CLICK) {
                            onPlayAll(completed, 0, true)
                        }
                        .clip(JunShape.item)
                        .background(colors.card),
                    contentAlignment = Alignment.Center
                ) {
                    Text("随机播放", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("只播本机已下载的文件，飞行模式也能听；点单首歌从它开始播。",
                color = colors.subText, fontSize = 10.sp, lineHeight = 14.sp)
            Spacer(Modifier.height(4.dp))
        }


        if (selectMode && selected.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .junPressable {
                            // 6.6.7：真·全选（已完成 + 失败都算；进行中的不许批量删）。
                            // 已经全选中就清空，变成「取消全选」。
                            val allIds = (completed + failed).map { it.id }
                            if (allIds.isNotEmpty() && allIds.all { it in selected }) {
                                selected.clear()
                            } else {
                                selectAll(allIds)
                            }
                        }
                        .clip(JunShape.item)
                        .background(colors.card)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val allIds = (completed + failed).map { it.id }
                    val allPicked = allIds.isNotEmpty() && allIds.all { it in selected }
                    Text(
                        if (allPicked) "取消全选" else "全选",
                        color = colors.text, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .junPressable(
                            scaleDown = 0.94f,
                            feedback = PressFeedback.CLICK
                        ) {
                            onDeleteBatch(selected.toList())
                            selected.clear()
                        }
                        .clip(JunShape.item)
                        .background(colors.dangerBg)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("删除选中", color = colors.dangerText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (tasks.isEmpty()) {
            EmptyState(
                colors = colors,
                title = "还没有下载任何歌",
                body = "在歌曲行长按 →「下载」，离线也能听。",
                glyph = EmptyGlyphKind.DISC
            )
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 进行中
                if (inProgress.isNotEmpty()) {
                    DownloadGroupHeader("进行中 (${inProgress.size})", colors)
                    inProgress.forEach { task ->
                        DownloadTaskRow(
                            task = task,
                            colors = colors,
                            selected = task.id in selected,
                            selectMode = selectMode,
                            onToggleSelect = { toggleSelect(task.id) },
                            onDelete = { onDelete(task.id) }
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
                // 已完成
                if (completed.isNotEmpty()) {
                    DownloadGroupHeader("已完成 (${completed.size})", colors)
                    completed.forEachIndexed { cIdx, task ->
                        DownloadTaskRow(
                            task = task,
                            colors = colors,
                            selected = task.id in selected,
                            selectMode = selectMode,
                            onToggleSelect = { toggleSelect(task.id) },
                            onDelete = { onDelete(task.id) },
                            onShare = { shareFile(ctx, task.localPath, task.title) },
                            onOpen = { openFile(ctx, task.localPath) },
                            // 6.9.1：非选择模式下点整行 = 从这首开始专听已下载
                            onPlay = { onPlayAll(completed, cIdx, false) }
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
                // 失败（标题行带「全部重试」，一键重试所有失败任务）
                if (failed.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DownloadGroupHeader("失败 (${failed.size})", colors)
                        Box(
                            Modifier
                                .junPressable { failed.forEach { onRetry(it.id) } }
                                .clip(JunShape.chip)
                                .background(accent.copy(alpha = 0.14f))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "全部重试",
                                color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    failed.forEach { task ->
                        DownloadTaskRow(
                            task = task,
                            colors = colors,
                            selected = task.id in selected,
                            selectMode = selectMode,
                            onToggleSelect = { toggleSelect(task.id) },
                            onDelete = { onDelete(task.id) },
                            onRetry = { onRetry(task.id) }
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadGroupHeader(text: String, colors: JunColors) {
    Spacer(Modifier.height(8.dp))
    Text(text, color = colors.primary.readableOn(colors.surface, 3.5f),
        fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun DownloadTaskRow(
    task: DownloadTask,
    colors: JunColors,
    selected: Boolean,
    selectMode: Boolean,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
    onShare: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    /** 6.9.1：非选择模式下点整行播放（仅已完成任务传入） */
    onPlay: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .junSurface(GlassLevel.ROW, colors, JunShape.item)
            .then(
                when {
                    selectMode -> Modifier.clickable { onToggleSelect() }
                    onPlay != null -> Modifier.clickable { onPlay() }
                    else -> Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 选择框：和歌曲行同一套（未选中=清楚的空框，选中=主色实心 + 单独一枚勾）
        if (selectMode) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (selected) colors.primary else Color.Transparent)
                    .border(
                        if (selected) 1.dp else 1.4.dp,
                        if (selected) colors.primary else colors.subText.copy(alpha = 0.72f),
                        RoundedCornerShape(5.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(JunIcons.Check, null, Modifier.size(14.dp), Color.White)
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(task.title, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(task.artist, color = colors.subText, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 进度条 / 状态
            when (task.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.PENDING -> {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.surface)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(task.progress / 100f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(colors.primary)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("${task.progress}%", color = colors.primary, fontSize = 9.sp)
                }
                DownloadStatus.COMPLETED -> {
                    Spacer(Modifier.height(2.dp))
                    Text(formatFileSize(task.fileSize), color = colors.subText, fontSize = 9.sp)
                }
                DownloadStatus.FAILED -> {
                    Spacer(Modifier.height(2.dp))
                    Text("下载失败", color = colors.dangerText, fontSize = 9.sp)
                }
            }
        }
        // 操作按钮
        if (!selectMode) {
            when (task.status) {
                DownloadStatus.COMPLETED -> {
                    if (onShare != null) {
                        IconButtonSmall(JunIcons.Share, colors) { onShare() }
                        Spacer(Modifier.width(4.dp))
                    }
                    if (onOpen != null) {
                        IconButtonSmall(JunIcons.Folder, colors) { onOpen() }
                        Spacer(Modifier.width(4.dp))
                    }
                }
                DownloadStatus.FAILED -> {
                    if (onRetry != null) {
                        IconButtonSmall(JunIcons.Download, colors) { onRetry() }
                        Spacer(Modifier.width(4.dp))
                    }
                }
                else -> {}
            }
            // 删除按钮
            Box(
                Modifier
                    .size(28.dp)
                    .junPressable(scaleDown = 0.80f, feedback = PressFeedback.CLICK) { onDelete() }
                    .clip(JunShape.chip)
                    .background(colors.dangerBg),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(12.dp)) {
                    val c = colors.dangerText
                    val w = size.minDimension * 0.16f
                    drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = w, cap = StrokeCap.Round)
                    drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = w, cap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun IconButtonSmall(icon: ImageVector, colors: JunColors, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .junPressable { onClick() }
            .clip(JunShape.chip)
            .background(colors.card),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, Modifier.size(15.dp), colors.primary)
    }
}

/** 格式化文件大小 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

/** 按扩展名给出 MIME，让播放器 / 文件管理器能正确接管下载好的音频 */
private fun audioMimeOf(path: String): String {
    val ext = path.substringBefore('?').substringAfterLast('.', "").lowercase()
    return when (ext) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "wma" -> "audio/x-ms-wma"
        else -> "audio/*"
    }
}

/** 自定义目录下载后 localPath 是 SAF 的 content:// 文档 URI，不能再用 File 判断/打开 */
private fun contentFileExists(ctx: Context, uri: Uri): Boolean = runCatching {
    ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
}.getOrDefault(false)

/** 分享已下载的文件：兼容默认目录（File）与自定义目录（SAF content://） */
private fun shareFile(ctx: Context, path: String, title: String) {
    runCatching {
        val uri: Uri = if (path.startsWith("content://")) {
            val u = Uri.parse(path)
            if (!contentFileExists(ctx, u)) {
                android.widget.Toast.makeText(ctx, "文件不存在或已被移动", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            u
        } else {
            val file = java.io.File(path)
            if (path.isBlank() || !file.exists()) {
                android.widget.Toast.makeText(ctx, "文件不存在或已被移动", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file
            )
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = audioMimeOf(path)
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, title)
            // ClipData 一并给读权限，部分文件管理器 / 聊天软件只认 ClipData
            clipData = android.content.ClipData.newRawUri(title, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(android.content.Intent.createChooser(intent, "分享 $title"))
    }.onFailure {
        android.widget.Toast.makeText(ctx, "分享失败：${it.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 用其他应用打开文件（MT管理器等文件管理器也能识别） */
private fun openFile(ctx: Context, path: String) {
    runCatching {
        val uri: Uri = if (path.startsWith("content://")) {
            val u = Uri.parse(path)
            if (!contentFileExists(ctx, u)) {
                android.widget.Toast.makeText(ctx, "文件不存在或已被移动", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            u
        } else {
            val file = java.io.File(path)
            if (path.isBlank() || !file.exists()) {
                android.widget.Toast.makeText(ctx, "文件不存在或已被移动", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file
            )
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, audioMimeOf(path))
            clipData = android.content.ClipData.newRawUri(path, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(android.content.Intent.createChooser(intent, "打开方式"))
    }.onFailure {
        android.widget.Toast.makeText(ctx, "打开失败：${it.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * 锁屏音乐界面开关（6.9.7）。
 *
 * 用户反馈「根本没有锁屏显示这个开关」——6.9.6 的锁屏开关藏在「桌面悬浮歌词」展开项里，
 * 而且它只控制一条悬浮文字，不是他想要的整屏音乐。这里独立成块：
 *  · 默认开启；
 *  · 明确写出触发条件（亮屏 + 未解锁 + 正在播放）；
 *  · 没有悬浮窗权限时给出授权入口——Android 10+ 后台拉起锁屏 Activity 依赖这项权限。
 */
@Composable
private fun LockScreenPlayerSection(
    colors: JunColors,
    headerColor: Color,
    onOpenStyle: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("jun_prefs", Context.MODE_PRIVATE)
    var on by remember {
        mutableStateOf(
            prefs.getBoolean(com.jun.musiccloud.playback.MusicService.KEY_LOCK_SCREEN_PLAYER, true)
        )
    }
    var granted by remember { mutableStateOf(com.jun.musiccloud.playback.FloatingLyric.hasPermission(ctx)) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = com.jun.musiccloud.playback.FloatingLyric.hasPermission(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Text("锁屏音乐", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "锁屏后点亮屏幕，直接看到封面、歌词和播放控制（像酷我/QQ 音乐的锁屏页）",
        color = colors.subText, fontSize = 11.sp
    )
    Spacer(Modifier.height(10.dp))
    // 6.6：这一项是可用的正式开关（默认开），不再有任何「停用 / 研发中」占位态。
    // 用户反馈里看到的「正在研发中」是旧包里的残留文案，本版源码里已无此字样。
    SettingToggleRow(
        "锁屏显示播放界面",
        on,
        colors,
        subtitle = if (granted) "已开放 · 亮屏未解锁时自动出现，解锁后自动收起"
        else "已开放 · 需授予「显示在其他应用上层」权限才会盖在锁屏上"
    ) {
        val next = !on
        on = next
        prefs.edit()
            .putBoolean(com.jun.musiccloud.playback.MusicService.KEY_LOCK_SCREEN_PLAYER, next)
            .apply()
        if (next && !com.jun.musiccloud.playback.FloatingLyric.hasPermission(ctx)) {
            com.jun.musiccloud.playback.FloatingLyric.requestPermission(ctx)
        }
    }
    if (on && !granted) {
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .junPressable { com.jun.musiccloud.playback.FloatingLyric.requestPermission(ctx) }
                .clip(JunShape.item)
                .background(colors.surface)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "去授予锁屏/悬浮窗权限",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.primary
            )
        }
    }

    // 6.9.10：锁屏样式入口。用户给了酷狗那张"五个样式卡"的截图，
    // 样式选择页做成独立的全屏弹层，这里只留一颗明确的入口，不再往下堆开关。
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .junPressable { onOpenStyle() }
            .clip(JunShape.item)
            .background(colors.primary.copy(alpha = if (colors.isDark) 0.14f else 0.10f))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "锁屏样式",
                color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "专辑封面 · 歌手写真 · 自定义背景 · 祈福 · 苹果风格",
                color = colors.subText, fontSize = 10.sp
            )
        }
        Text(
            "去选择 ›",
            color = colors.primary.readableOn(colors.surface, 3.5f),
            fontSize = 12.sp, fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 6.9.9：设置分组计数。
 *
 * 用户要求「普通设置有几项就是几项，其他设置有几项就是几项，要真实的说明」。
 * 折叠分组在组合时把标题注册进来（注册发生在 FoldGroup 的早退之前，
 * 所以只在其他设置显示的组也会被统计到）；tab 上的数字就是当前模式下
 * 实际显示的折叠分组数，不是拍脑袋写的。
 */
internal object SettingsGroupCounters {
    /**
     * 6.9.11：总数改为**静态基线**。旧实现靠每个分区渲染时 register 计数，
     * 而设置列表滚动未到的分区不渲染 —— 数字首帧偏小、滚动后才"长齐"，
     * 用户看到的就是"打开多少项它才会显示多少项"。现在直接写死真实总数：
     * 12 个一级 CollapsibleSection + 30 个二级 FoldGroup（其中 17 个为进阶）。
     * 增删分区时同步这两个数即可；register 仍保留，仅作开发期校验。
     */
    const val BASE_TOTAL = 42
    const val BASE_NORMAL = 25

    private val map = HashMap<String, Boolean>()
    private val rev = androidx.compose.runtime.mutableIntStateOf(0)

    fun register(title: String, advancedOnly: Boolean) {
        val changed = map.put(title, advancedOnly) != advancedOnly
        if (changed) rev.intValue++
    }

    /** 非"仅进阶"的分区数（静态基线优先，注册只兜底） */
    fun normal(): Int {
        rev.intValue
        return maxOf(BASE_NORMAL, map.count { !it.value })
    }

    /** 全部分区数（含进阶） */
    fun advanced(): Int {
        rev.intValue
        return maxOf(BASE_TOTAL, map.size)
    }
}

