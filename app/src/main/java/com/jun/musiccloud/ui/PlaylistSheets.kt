@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jun.musiccloud.ui

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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

import com.jun.musiccloud.audio.BassHaptics
import com.jun.musiccloud.audio.EqualizerController
import com.jun.musiccloud.data.MusicFolder
import com.jun.musiccloud.data.LyricLine
import com.jun.musiccloud.data.LyricParser
import com.jun.musiccloud.data.RepeatMode
import com.jun.musiccloud.data.Playlist
import com.jun.musiccloud.data.PlaylistShare
import com.jun.musiccloud.data.Playlists
import com.jun.musiccloud.data.Song
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
// ==================== 歌单管理弹层 ====================

/**
 * 歌单管理弹层：查看/新建/删除歌单，切换「文件夹视图 / 歌单视图」。
 *
 * 与设置/均衡器/睡眠定时器同构的自绘覆盖层——遮罩 + 底部玻璃卡片，
 * 复用 [junSurface] 保证弹层观感统一。
 */
@Composable
internal fun PlaylistSheet(
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit,
    onSelect: (Long?) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    /** 导入网易云歌单：传入链接/ID，返回结果提示 */
    // 6.9.8：第二个参数是分片进度回调 (已处理, 总数)。
    // 以前只有一个 String 入参，500 首的汽水歌单只能显示一句「正在导入…」干等 40 秒。
    // 6.4：第三个参数回结构化结果（可播 / 需换源 / 缺哪几首）。
    onImport: suspend (String, (Int, Int) -> Unit, (com.jun.musiccloud.data.ImportStats) -> Unit) -> String =
        { _, _, _ -> "" },
    /** 网易云 · 每日推荐 */
    onNeteaseDaily: () -> Unit = {},
    /** 网易云 · 我的歌单（打开歌单选单弹层） */
    onNeteasePlaylists: () -> Unit = {},
    /** 网易云 · 我喜欢的音乐（账号同步） */
    onNeteaseLiked: () -> Unit = {},
    /** 本地「我喜欢的音乐」：不登录也能用，就是历来的 ♡ 列表 */
    onOpenLikedLocal: () -> Unit = {},
    /** 底部避让高度（迷你播放器） */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    /** 由新功能导览从外部展开导入面板（聚光灯要照输入框） */
    openImport: Boolean = false,
    /** 新功能导览指到导入入口时，把弹层滚到底部让入口露出来 */
    revealImportEntry: Boolean = false,
    // ===== R13：分享 / 导入 Jun 口令。四个都有默认值，旧调用点一行不用改 =====
    // 为什么不在这层直接调 viewModel：这个文件只管"列出歌单、让人选"，
    // 编码和落库都在数据层 + ViewModel。入口只把"用户要分享哪一份"交出去，
    // 弹层（PlaylistShareSheet）由 MainScreen 那一级持有，才能盖在本弹层之上。
    /** 分享某个本地歌单（生成 Jun 口令） */
    onSharePlaylist: (Playlist) -> Unit = {},
    /** 分享「我喜欢」 */
    onShareLiked: () -> Unit = {},
    /** 分享「最近播放」 */
    onShareRecent: () -> Unit = {},
    /** 打开「导入好友口令」粘贴框；带文本 = 已在统一入口识别成口令，直接预填 */
    onImportJunCode: (String) -> Unit = { _ -> },
    /** 6.4：打开「整理播放库」——把四层结构讲清楚，并一键合并碎片/重复歌单 */
    onOpenTidy: () -> Unit = {},
    /**
     * 6.5：歌单乱到值得提一句了吗。[needsTidyHint] 为 true 时「整理」旁边点一颗小圆点。
     *
     * 刻意**不做**弹窗、不做红点、不做横幅：用户打开这一页是想挑歌听，
     * 不是来被管家训话的。一颗小圆点的信息量刚好——注意到了就会点，
     * 没注意到也不打扰。整理完 [needsTidyHint] 变 false，圆点自己消失。
     */
    needsTidyHint: Boolean = false
) {
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val onSheet = if (colors.isDark) Color.White else Color.Black

    // 新建歌单输入框
    var newName by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }

    // 歌单排序：0 默认(创建顺序) / 1 名称 / 2 歌曲数多→少。点「本地歌单 ⌄」循环切换
    var sortMode by remember { mutableStateOf(0) }
    val sortNames = listOf("默认", "按名称", "按歌曲数")
    val sortedPlaylists = remember(state.playlists, sortMode) {
        when (sortMode) {
            1 -> state.playlists.sortedBy { it.name }
            2 -> state.playlists.sortedByDescending { it.songCount }
            else -> state.playlists
        }
    }

    // 待删除的歌单（二次确认用）
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }

    // 待重命名的歌单（弹输入框用）
    var pendingRename by remember { mutableStateOf<Playlist?>(null) }
    var renameText by remember { mutableStateOf("") }

    // 从网易云导入歌单
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importBusy by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf("") }
    // 6.9.8：分片进度。importTotal=0 表示还没拿到分母（首包未回），此时不画进度条，
    // 避免"一条永远 0% 的条"比没有更让人焦虑。
    var importDone by remember { mutableStateOf(0) }
    var importTotal by remember { mutableStateOf(0) }
    // 6.4：导入结果。非 null 时弹窗切成"结果视图"——
    // 以前一成功就自动关窗，可播/需换源/缺几首这些信息用户根本来不及看。
    var importStats by remember { mutableStateOf<com.jun.musiccloud.data.ImportStats?>(null) }
    // 未匹配清单是否展开
    var showMissing by remember { mutableStateOf(false) }
    val importScope = rememberCoroutineScope()
    val sheetScroll = rememberScrollState()
    val importBringIntoView = remember { BringIntoViewRequester() }
    val importFocus = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current
    // 6.4：未匹配清单要「复制全部歌名」给用户去搜，需要 Toast 和剪贴板
    val importCtx = androidx.compose.ui.platform.LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    // 打开导入面板时自动聚焦并滚到可见位置：键盘弹起后输入框不会被挡在下面
    // 6.7：新功能导览走到「粘贴输入框」那一步也会把 showImport 置真——但导览只是在
    // 讲这里有个框，不是要用户马上打字。以前导览一走到这步键盘"啪"就弹出来，
    // 挡掉半屏聚光灯气泡（用户：「有搜索界面的东西就直接弹出键盘了，太难受了」）。
    // 所以导览带开的导入面板只展开、不抢焦点；用户自己点开的（openImport=false）照旧弹键盘。
    LaunchedEffect(showImport) {
        if (showImport) {
            delay(280)
            if (!openImport) runCatching { importFocus.requestFocus() }
            delay(320)
            runCatching { importBringIntoView.bringIntoView() }
        }
    }

    // 新功能导览走到「粘贴输入框」这一步时，从外部把导入面板展开
    LaunchedEffect(openImport) {
        if (openImport) showImport = true
    }

    // 导览指到导入入口时，弹层里的本地歌单可能很长：滚到底部，入口才在屏幕里
    LaunchedEffect(revealImportEntry) {
        if (revealImportEntry) {
            delay(520)
            if (sheetScroll.maxValue > 0) {
                sheetScroll.animateScrollTo(sheetScroll.maxValue)
            }
        }
    }

    // 拖把手下滑关闭
    val drag = rememberSheetDragState()

    // 外层 Box：让「删除二次确认」能覆盖在弹层之上；imePadding 让整个弹层随键盘上移
    Box(Modifier.fillMaxSize().imePadding()) {
        JunSheet(
            colors = colors,
            onDismiss = onDismiss,
            drag = drag,
            center = true,
            panelModifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 520.dp)
                .fillMaxHeight(0.82f),
            bottomInset = bottomInset,
            contentModifier = Modifier
                .verticalScroll(sheetScroll)
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 20.dp)
        ) {
            SheetDragHandle(drag, onDismiss, colors.card)
            Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("歌单", color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Text(
                            if (state.activePlaylistId == null) "文件夹视图" else "歌单视图",
                            color = colors.subText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    // 入口改名（6.9.12）：用户说"不要是全部音乐，改成推荐音乐"。
                    // 点开仍是整库列表，但入口语义从"仓库"变成"给你听的"，
                    // 副标题也改了，不再强调"我把所有歌堆在这"。
                    val showAll = state.activePlaylistId == null
                    FolderRow(
                        "推荐音乐",
                        "按口味排好序 · ${state.allSongs.size} 首",
                        showAll,
                        colors
                    ) {
                        onSelect(null)
                    }

                    // 「我喜欢的音乐」紧跟其后 —— 用户要求"不能只是有歌单"，
                    // 而且这条不该依赖网易云登录：本地红心一直都在。
                    Spacer(Modifier.height(6.dp))
                    FolderRow(
                        "我喜欢的音乐",
                        if (state.likedSongs.isEmpty()) "还没有 ♡ 过的歌"
                        else "${state.likedSongs.size} 首 · 你标过红心的",
                        false,
                        colors
                    ) {
                        Haptics.tap()
                        onOpenLikedLocal()
                    }

                    Spacer(Modifier.height(14.dp))

                    // ===== 本地歌单（软件本身的歌单，与网易云完全分开）=====
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SheetSectionLabel("本地歌单", colors)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 6.4：整理入口就放在"本地歌单"标题旁边——
                            // 用户看着一堆歌单觉得乱的那一刻，手正停在这一行
                            Text(
                                "整理",
                                color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.junPressable {
                                    Haptics.tap(); onOpenTidy()
                                }
                            )
                            // 6.5：乱到值得提一句时点一颗小圆点（不弹窗、不说话）
                            if (needsTidyHint) {
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(accent)
                                )
                            }
                            if (state.playlists.size > 1) {
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "排序：${sortNames[sortMode]}",
                                    color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.junPressable { sortMode = (sortMode + 1) % 3 }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (state.playlists.isEmpty()) {
                        EmptyState(
                            colors = colors,
                            title = "还没有歌单",
                            body = "点下方「新建歌单」，把想一起听的收在一处。",
                            glyph = EmptyGlyphKind.STACK,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        val allSongsById = state.allSongs.associateBy { it.id }
                        val currentSongId = state.currentSong?.id
                        // 自适应列数（本地歌单优先做小）：默认 3 列让卡片更紧凑，
                        // 歌单很多（≥12）时变 4 列，每张卡再小一圈。
                        val cols = when {
                            sortedPlaylists.size >= 12 -> 4
                            else -> 3
                        }
                        sortedPlaylists.chunked(cols).forEach { pair ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pair.forEach { p ->
                                    val firstSongId = Playlists.songs(p.id).firstOrNull()
                                    val coverPath = firstSongId?.let { allSongsById[it]?.filePath }
                                    val containsCurrent = currentSongId != null &&
                                        Playlists.songs(p.id).contains(currentSongId)
                                    PlaylistGridCard(
                                        playlist = p,
                                        active = state.activePlaylistId == p.id,
                                        colors = colors,
                                        coverPath = coverPath,
                                        containsCurrent = containsCurrent,
                                        onClick = { onSelect(p.id) },
                                        onShare = { onSharePlaylist(p) },
                                        onRename = {
                                            renameText = p.name
                                            pendingRename = p
                                        },
                                        onDelete = { pendingDelete = p },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                // 不足一行时补占位保持对齐
                                repeat(cols - pair.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    // 6.9.12：去掉「每日推荐」这一行（用户明确要求），
                    // 「我喜欢的音乐」已上移为常显条目，这里只留账号同步的歌单。
                    if (state.neteaseLoggedIn) {
                        Spacer(Modifier.height(14.dp))
                        SheetSectionLabel("网易云", colors)
                        Spacer(Modifier.height(6.dp))
                        FolderRow("我的歌单", "账号同步的网易云歌单", false, colors) {
                            Haptics.tap(); onNeteasePlaylists()
                        }
                        Spacer(Modifier.height(4.dp))
                        FolderRow("云端红心", "与网易云账号同步的喜欢", false, colors) {
                            Haptics.tap(); onNeteaseLiked()
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 新建歌单
                    if (showCreate) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 玻璃风格输入框
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(JunShape.item)
                                    .background(if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC))
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                BasicTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    singleLine = true,
                                    textStyle = TextStyle(fontFamily = LocalJunUiFamily.current, color = colors.text, fontSize = 14.sp),
                                    cursorBrush = SolidColor(colors.primary),
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { inner ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (newName.isEmpty()) {
                                                Text("歌单名称", color = colors.subText, fontSize = 14.sp)
                                            }
                                            inner()
                                        }
                                    }
                                )
                            }
                            Box(
                                Modifier
                                    .height(44.dp)
                                    // 和全 App 同一套按压弹簧：缩放层放在 clip/background/junSurface 之前，底色才跟着缩
                                    .junPressable(feedback = PressFeedback.CLICK) {
                                        val n = newName.trim()
                                        if (n.isNotEmpty()) {
                                            onCreate(n)
                                            newName = ""
                                            showCreate = false
                                        }
                                    }
                                    .clip(JunShape.item)
                                    .background(accent)
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("创建", color = accent.readableTextOn(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .junPressable { showCreate = true }
                                .clip(JunShape.item)
                                .background(colors.card),
                            contentAlignment = Alignment.Center
                         ) {
                             Text("新建歌单", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                         }
                     }

                    Spacer(Modifier.height(10.dp))
                    // 从网易云 / QQ / 汽水导入歌单：粘贴分享链接即可，不需要登录
                    if (showImport) {
                        Column(
                            Modifier
                                .spotlightAnchor(SpotlightKeys.IMPORT_INPUT)
                                .bringIntoViewRequester(importBringIntoView)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(JunShape.item)
                                    .background(if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC))
                                    .padding(start = 14.dp, end = 6.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BasicTextField(
                                        value = importText,
                                        onValueChange = { importText = it; importStatus = "" },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            fontFamily = LocalJunUiFamily.current,
                                            color = colors.text,
                                            fontSize = 13.sp
                                        ),
                                        cursorBrush = SolidColor(colors.primary),
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(importFocus)
                                            .onFocusChanged { focus ->
                                                if (focus.isFocused) {
                                                    importScope.launch {
                                                        delay(260)
                                                        runCatching { importBringIntoView.bringIntoView() }
                                                    }
                                                }
                                            },
                                        decorationBox = { inner ->
                                            Box(contentAlignment = Alignment.CenterStart) {
                                                if (importText.isEmpty()) {
                                                    Text(
                                                        "粘贴链接、整段分享文案或歌单 ID",
                                                        color = colors.subText, fontSize = 13.sp
                                                    )
                                                }
                                                inner()
                                            }
                                        }
                                    )
                                    // 键盘挡住输入框时也能一键把剪贴板内容放进来
                                    Box(
                                        Modifier
                                            .junPressable {
                                                val clip = runCatching {
                                                    clipboard.getText()?.text?.trim().orEmpty()
                                                }.getOrDefault("")
                                                if (clip.isNotBlank()) {
                                                    importText = clip
                                                    importStatus = ""
                                                }
                                            }
                                            .clip(JunShape.chip)
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("粘贴", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "链接（网易云 / QQ / 汽水）或好友发来的 JM 口令都粘这里，自动识别",
                                color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .junPressable(enabled = !importBusy) {
                                            val text = importText.trim()
                                            if (text.isEmpty()) {
                                                importStatus = "先粘贴歌单链接、分享文案或 ID"
                                            } else {
                                                keyboard?.hide()
                                                // 统一入口自动识别：JM 口令走本地解码，链接 / ID 走在线导入
                                                if (PlaylistShare.looksLikeShareCode(text)) {
                                                    keyboard?.hide()
                                                    showImport = false
                                                    importText = ""
                                                    onImportJunCode(text)
                                                } else {
                                                    importScope.launch {
                                                        importBusy = true
                                                        importDone = 0
                                                        importTotal = 0
                                                        importStats = null
                                                        showMissing = false
                                                        importStatus = "正在导入…"
                                                        importStatus = runCatching {
                                                            onImport(text, { d, t ->
                                                                importDone = d
                                                                importTotal = t
                                                                // 分片时给一句能量化的状态，比「正在导入…」有信息量
                                                                if (t > 0) importStatus = "正在导入 $d / $t 首…"
                                                            }, { st ->
                                                                // 6.4：拿到统计就切成结果视图。
                                                                // 故意不自动关窗 —— 用户需要看到
                                                                // "多少能播、多少要换源、缺了哪几首"。
                                                                importStats = st
                                                            })
                                                        }.getOrElse { "导入失败：${it.message ?: "网络异常"}" }
                                                        importBusy = false
                                                        importTotal = 0
                                                        if (importStatus.startsWith("已导入")) {
                                                            importText = ""
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        .clip(JunShape.item)
                                        .background(if (importBusy) colors.card else accent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (importBusy) "导入中…" else "开始导入",
                                        color = if (importBusy) colors.subText else accent.readableTextOn(),
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(
                                    Modifier
                                        .height(40.dp)
                                        .junPressable(enabled = !importBusy) {
                                            keyboard?.hide()
                                            showImport = false
                                            importStatus = ""
                                        }
                                        .clip(JunShape.item)
                                        .background(colors.card)
                                        .padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("取消", color = colors.subText, fontSize = 13.sp)
                                }
                            }
                            // 6.9.8：分片进度条。自己用 Box 画而不是 LinearProgressIndicator，
                            // 是为了不赌 material3 版本里 progress 参数是 Float 还是 lambda。
                            if (importBusy && importTotal > 0) {
                                Spacer(Modifier.height(8.dp))
                                val frac = (importDone.toFloat() / importTotal).coerceIn(0f, 1f)
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(colors.card)
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(frac)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(accent)
                                    )
                                }
                            }
                            // 6.4：导入结果卡片。
                            // 以前导入成功只留一句「已导入 264 首到「xxx」」，
                            // 用户根本不知道剩下的 123 首去哪了 —— 是搜不到、是 VIP、
                            // 还是平台只给了前 10 首？三种情况该做的事完全不同。
                            val st = importStats
                            if (st != null) {
                                Spacer(Modifier.height(10.dp))
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(JunShape.item)
                                        .background(colors.card)
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        st.headline(),
                                        color = colors.text,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    // 第一行：网易云直连（点开立刻出声）
                                    if (st.playable != null && st.playable > 0) {
                                        ImportStatRow(
                                            color = Color(0xFF3FCF8E),
                                            text = "网易云直连 ${st.playable} 首"
                                        )
                                    }
                                    // 第二行：要换源。
                                    // 措辞很关键：这些歌**照样能听**，只是不從网易云官方拿地址，
                                    // 会切到别的音源，可能多等半秒。写成"需换源"会让用户
                                    // 以为这 110 首都废了 —— 实测许嵩歌单就是 112 首里 110 首这种情况，
                                    // 文案一吓人，整个歌单看着就像坏的。
                                    if (st.needSource != null && st.needSource > 0) {
                                        ImportStatRow(
                                            color = Color(0xFFE8A33D),
                                            text = "自动换源 ${st.needSource} 首（点开就播，可能多等半秒）"
                                        )
                                    }
                                    // 预判不了时如实说，不糊一个假数字。
                                    // 分两种：QQ/酷狗是「自带平台 id」（direct），
                                    // 网易云/其它是「这条链路没带收费标记」。
                                    if (st.predictUnknown && st.playable == null) {
                                        ImportStatRow(
                                            color = colors.subText,
                                            text = if (st.direct)
                                                "这批歌自带平台 id，播放时自动匹配音源"
                                            else
                                                "这个平台没做预判，播放时自动选源"
                                        )
                                    }
                                    // 第三行：具体缺哪几首
                                    if (st.missing.isNotEmpty()) {
                                        // 6.4：先把「没导完」说清楚 —— 用户常以为要重头再来，
                                        // 其实再点一次导入只会补齐缺的，已导入的不会重复添加。
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(JunShape.chip)
                                                .background(Color(0xFFE8A33D).copy(alpha = 0.14f))
                                                .padding(horizontal = 10.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(7.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color(0xFFE8A33D))
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "本次有 ${st.missing.size} 首未导入成功，可再点一次导入继续补齐（已导入的不会重复添加）",
                                                color = colors.text,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                lineHeight = 17.sp
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                Modifier
                                                    .padding(end = 6.dp)
                                                    .size(7.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(colors.subText)
                                            )
                                            Text(
                                                "未找到 ${st.missing.size} 首",
                                                color = colors.subText,
                                                fontSize = 12.sp
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Box(
                                                Modifier
                                                    .junPressable { showMissing = !showMissing }
                                                    .clip(JunShape.chip)
                                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    if (showMissing) "收起" else "查看",
                                                    color = accent,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        if (showMissing) {
                                            Spacer(Modifier.height(6.dp))
                                            // 最多列 50 条：再多用户也不会一条条补，
                                            // 而且一次渲染上千行 Text 会明显卡顿。
                                            Column(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 150.dp)
                                                    .verticalScroll(rememberScrollState())
                                            ) {
                                                st.missing.take(50).forEach { (t, a) ->
                                                    Text(
                                                        if (a.isBlank()) "· $t" else "· $t — $a",
                                                        color = colors.subText,
                                                        fontSize = 11.sp,
                                                        lineHeight = 17.sp
                                                    )
                                                }
                                                if (st.missing.size > 50) {
                                                    Text(
                                                        "…等 ${st.missing.size} 首",
                                                        color = colors.subText,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            // 6.4：光列出来还不够 —— 用户想补只能一首首打。
                                            // 给一个「复制全部」，粘到搜索框里能连着搜。
                                            Box(
                                                Modifier
                                                    .junPressable {
                                                        val lines = st.missing.joinToString("\n") {
                                                            if (it.second.isBlank()) it.first
                                                            else "${'$'}{it.first} - ${'$'}{it.second}"
                                                        }
                                                        clipboard.setText(AnnotatedString(lines))
                                                        android.widget.Toast.makeText(
                                                            importCtx,
                                                            "已复制 ${'$'}{st.missing.size} 首歌名，去搜索框粘贴",
                                                            android.widget.Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                    .clip(JunShape.chip)
                                                    .background(accent.copy(alpha = 0.14f))
                                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                            ) {
                                                Text(
                                                    "复制全部未导入歌名",
                                                    color = accent,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    // 平台限制 / 没来得及匹配，各说一句
                                    if (st.limited && st.limitedNote.isNotBlank()) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            st.limitedNote.removeSurrounding("（", "）"),
                                            color = Color(0xFFE8A33D),
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                    if (st.skipped > 0) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "另有 ${st.skipped} 首没来得及匹配，再点一次导入即可续上",
                                            color = colors.subText,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                            .junPressable {
                                                importStats = null
                                                showMissing = false
                                                importStatus = ""
                                                showImport = false
                                            }
                                            .clip(JunShape.item)
                                            .background(accent),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "完成",
                                            color = accent.readableTextOn(),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            } else if (importStatus.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(importStatus, color = colors.subText, fontSize = 11.sp)
                            }
                        }
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .spotlightAnchor(SpotlightKeys.IMPORT_ENTRY)
                                .junPressable { showImport = true }
                                .clip(JunShape.item)
                                .background(colors.card),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("导入歌单（链接 / 好友口令，自动识别）", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // ===== R13：本地分享 / 导入 Jun 口令 =====
                    //
                    // 为什么不和上面那个「导入歌单」框共用一个输入框：
                    // 那一条吃的是**网易云/QQ 链接**（要联网把歌单抓回来），这一条吃的是
                    // 好友发来的 **JM 口令**（先本地解码，再逐首对到本机曲库）。
                    // 校验、报错、进度三样都不一样；混成一个框就只能靠猜，猜错的代价是把
                    // 口令当链接去联网抓，用户看到的是一句莫名其妙的"链接无效"。
                    //
                    // 这一节只有入口、没有弹层：分享层由 MainScreen 那一级持有（zIndex 才压得住
                    // 本弹层），这里点一下只是把"要分享哪一份"报上去。
                    Spacer(Modifier.height(10.dp))
                    SheetSectionLabel("分享 / 好友口令", colors)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "口令是一段文字，微信 / QQ 直接发给好友即可；歌单卡片右下角也能分享。",
                        color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(34.dp)
                                .junPressable { onShareLiked() }
                                .clip(JunShape.item)
                                .background(colors.card),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("分享我喜欢", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(34.dp)
                                .junPressable { onShareRecent() }
                                .clip(JunShape.item)
                                .background(colors.card),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("分享最近播放", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    // 6.3 大修版：口令入口已并入上面的统一「导入歌单」，粘贴 JM 口令会自动分流
        }

        // 删除二次确认弹层：覆盖在歌单面板之上
        if (pendingDelete != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { pendingDelete = null }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp)
                        .junSurface(GlassLevel.SHEET, colors, JunShape.item)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .padding(20.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "删除歌单「${pendingDelete?.name}」？",
                            color = if (colors.isDark) Color.White else Color.Black,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "歌单内的 ${pendingDelete?.songCount ?: 0} 首歌不会被删除",
                            color = colors.subText,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 取消
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .junPressable { pendingDelete = null }
                                    .clip(JunShape.item)
                                    .background(colors.card),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("取消", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            // 确认删除
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .junPressable(feedback = PressFeedback.CLICK) {
                                        pendingDelete?.let { onDelete(it.id) }
                                        pendingDelete = null
                                    }
                                    .clip(JunShape.item)
                                    .background(accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("删除", color = accent.readableTextOn(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 重命名输入弹层：覆盖在歌单面板之上
        if (pendingRename != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { pendingRename = null }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp)
                        .junSurface(GlassLevel.SHEET, colors, JunShape.item)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .padding(20.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "重命名歌单",
                            color = if (colors.isDark) Color.White else Color.Black,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(JunShape.item)
                                .background(if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC))
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = renameText,
                                onValueChange = { renameText = it },
                                singleLine = true,
                                textStyle = TextStyle(fontFamily = LocalJunUiFamily.current, color = colors.text, fontSize = 14.sp),
                                cursorBrush = SolidColor(colors.primary),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (renameText.isEmpty()) {
                                            Text("歌单名称", color = colors.subText, fontSize = 14.sp)
                                        }
                                        inner()
                                    }
                                }
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .junPressable { pendingRename = null }
                                    .clip(JunShape.item)
                                    .background(colors.card),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("取消", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .junPressable(feedback = PressFeedback.CLICK) {
                                        val n = renameText.trim()
                                        if (n.isNotEmpty()) {
                                            pendingRename?.let { onRename(it.id, n) }
                                        }
                                        pendingRename = null
                                    }
                                    .clip(JunShape.item)
                                    .background(accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("确定", color = accent.readableTextOn(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 弹层内的小节标题（本地歌单 / 网易云 等分组用） */
@Composable
private fun SheetSectionLabel(text: String, colors: JunColors) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    Text(
        text,
        color = onSheet,
        fontSize = 13.sp,
        fontWeight = FontWeight.Black
    )
}

/** 歌单行：封面 + 名称 + 歌曲数 + 选中态 + 重命名/删除按钮 */
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    active: Boolean,
    colors: JunColors,
    /** 封面图路径（歌单第一首歌的封面，没有则 null 显示渐变色块） */
    coverPath: String? = null,
    /** 当前播放的歌是否在这个歌单里（高亮用） */
    containsCurrent: Boolean = false,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val t by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(240),
        label = "playlistActive"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .junPressableRow { onClick() }
            .junSurface(
                level = GlassLevel.ROW,
                colors = colors,
                shape = JunShape.item,
                solid = lerp(colors.row, colors.primary.copy(alpha = 0.12f), t),
                accent = if (t > 0.5f) colors.primary else null
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面：优先用第一首歌的封面，没有则用渐变色块
        Box(
            Modifier
                .size(42.dp)
                .clip(JunShape.chip)
                .background(
                    if (coverPath.isNullOrEmpty()) {
                        Brush.linearGradient(
                            listOf(colors.primary.copy(alpha = 0.5f), colors.secondary.copy(alpha = 0.3f))
                        )
                    } else SolidColor(colors.card)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (!coverPath.isNullOrEmpty()) {
                SongArtwork(
                    filePath = coverPath,
                    seedId = playlist.id,
                    artworkSize = 42.dp,
                    isDark = colors.isDark
                )
            } else {
                Icon(JunIcons.QueueMusic, null, Modifier.size(20.dp), Color.White.copy(alpha = 0.8f))
            }
            // 当前播放的歌在此歌单里 → 左下角小圆点提示
            if (containsCurrent) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.primary)
                        .border(1.5.dp, colors.row, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                color = lerp(colors.text, colors.primary.readableOn(colors.row, 3.2f), t),
                fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text("${playlist.songCount} 首", color = colors.subText, fontSize = 11.sp)
        }
        // 重命名按钮
        Box(
            Modifier
                .size(30.dp)
                .junPressable(scaleDown = 0.80f) { onRename() }
                .clip(JunShape.chip)
                .background(colors.card),
            contentAlignment = Alignment.Center
        ) {
            Icon(JunIcons.Edit, null, Modifier.size(15.dp), colors.subText)
        }
        Spacer(Modifier.width(8.dp))
        // 删除按钮
        Box(
            Modifier
                .size(30.dp)
                .junPressable(scaleDown = 0.80f, feedback = PressFeedback.CLICK) { onDelete() }
                .clip(JunShape.chip)
                .background(colors.dangerBg),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(13.dp)) {
                val c = colors.dangerText
                val w = size.minDimension * 0.16f
                drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = w, cap = StrokeCap.Round)
            }
        }
    }
}

/**
 * 歌单网格卡片：2 列网格用，方封面 + 名称 + 歌曲数。
 * 封面与液态玻璃融合（圆角卡片 + 底部渐变蒙层），右下角悬浮歌曲数。
 */
@Composable
private fun PlaylistGridCard(
    playlist: Playlist,
    active: Boolean,
    colors: JunColors,
    coverPath: String? = null,
    containsCurrent: Boolean = false,
    onClick: () -> Unit,
    /** R13：分享这份歌单（生成 Jun 口令）。放在重命名之前，卡片右下角留给删除 */
    onShare: () -> Unit = {},
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(240),
        label = "playlistGridActive"
    )
    Column(
        modifier
            .junPressableRow { onClick() }
            .junSurface(
                level = GlassLevel.CARD,
                colors = colors,
                shape = JunShape.item,
                solid = lerp(colors.row, colors.primary.copy(alpha = 0.12f), t),
                accent = if (t > 0.5f) colors.primary else null
            )
    ) {
        // 封面区
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(JunShape.item)
                .background(
                    if (coverPath.isNullOrEmpty()) {
                        Brush.linearGradient(
                            listOf(colors.primary.copy(alpha = 0.5f), colors.secondary.copy(alpha = 0.3f))
                        )
                    } else SolidColor(colors.card)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (!coverPath.isNullOrEmpty()) {
                SongArtwork(
                    filePath = coverPath,
                    seedId = playlist.id,
                    modifier = Modifier.fillMaxSize(),
                    isDark = colors.isDark
                )
            } else {
                Icon(JunIcons.QueueMusic, null, Modifier.size(36.dp), Color.White.copy(alpha = 0.8f))
            }
            // 底部玻璃渐变蒙层，让封面融进卡片
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, colors.card.copy(alpha = 0.4f))
                        )
                    )
            )
            // 右下角悬浮歌曲数
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(JunShape.chip)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("${playlist.songCount}", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            // 分享（R13）：挂在封面右上角，和右下角的曲目数同一套悬浮胶囊。
            // 为什么不放进下面那一排按钮：卡片只有 176dp 宽，名称那格是 weight(1f)，
            // 再加一颗 26dp 图标就把歌单名挤到只剩五六个字——歌单名才是这张卡片的主信息。
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .junPressable(scaleDown = 0.80f) { onShare() }
                    .clip(JunShape.chip)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(JunIcons.Share, null, Modifier.size(11.dp), Color.White.copy(alpha = 0.9f))
            }
            // 当前播放的歌在此歌单 → 左下角小圆点
            if (containsCurrent) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.primary)
                        .border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                )
            }
        }
        // 名称 + 操作
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                playlist.name,
                color = colors.text,
                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 重命名
            Box(
                Modifier
                    .size(22.dp)
                    .junPressable(scaleDown = 0.80f) { onRename() }
                    .clip(JunShape.chip)
                    .background(colors.card.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(JunIcons.Edit, null, Modifier.size(11.dp), colors.subText)
            }
            Spacer(Modifier.width(3.dp))
            // 删除
            Box(
                Modifier
                    .size(22.dp)
                    .junPressable(scaleDown = 0.80f, feedback = PressFeedback.CLICK) { onDelete() }
                    .clip(JunShape.chip)
                    .background(colors.dangerBg),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(9.dp)) {
                    val c = colors.dangerText
                    val w = size.minDimension * 0.16f
                    drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = w, cap = StrokeCap.Round)
                    drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = w, cap = StrokeCap.Round)
                }
            }
        }
    }
}

// ==================== 添加到歌单弹层 ====================

/**
 * 6.6.9：**整单收藏弹层**（歌单 / 榜单一次性存进本地歌单）。
 *
 * 与单曲版 [AddToPlaylistSheet] 的区别只在"加什么"：这里一次带整份列表，
 * 所以副标题写"共 N 首"，并且不显示"已加入"（整单基本不可能已经全在里头，
 * 逐首判重反而要遍历 N 次数据库）。
 */
@Composable
internal fun AddManyToPlaylistSheet(
    title: String,
    count: Int,
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onAdd: (Long) -> Unit
) {
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val onSheet = if (colors.isDark) Color.White else Color.Black
    var newName by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    val drag = rememberSheetDragState()

    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        panelModifier = Modifier.fillMaxHeight(0.8f),
        contentModifier = Modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 10.dp, bottom = 24.dp)
    ) {
        SheetDragHandle(drag, onDismiss, colors.card)
        Spacer(Modifier.height(14.dp))
        Text("整单收藏", color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(4.dp))
        Text(
            "$title · 共 $count 首",
            color = colors.subText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(16.dp))

        if (state.playlists.isEmpty()) {
            Text("还没有歌单，点下方「新建歌单」创建一个", color = colors.subText, fontSize = 13.sp)
        } else {
            state.playlists.forEach { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .junPressableRow(scaleDown = 0.975f) { onAdd(p.id) }
                        .junSurface(
                            level = GlassLevel.ROW,
                            colors = colors,
                            shape = JunShape.item,
                            accent = colors.primary
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        p.name,
                        Modifier.weight(1f),
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("${p.songCount} 首", color = colors.subText, fontSize = 11.sp)
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(12.dp))

        if (showCreate) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(JunShape.item)
                        .background(if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = newName,
                        onValueChange = { newName = it.take(24) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = colors.text, fontSize = 14.sp
                        ),
                        decorationBox = { inner ->
                            if (newName.isEmpty()) Text("歌单名", color = colors.subText, fontSize = 14.sp)
                            inner()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(JunShape.item)
                        .background(accent)
                        .junPressable { if (newName.isNotBlank()) { onCreateAndAdd(newName.trim()); newName = "" } }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("创建并加入", color = colors.background, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(JunShape.item)
                    .background(colors.card)
                    .junPressable { showCreate = true },
                contentAlignment = Alignment.Center
            ) {
                Text("+ 新建歌单并加入", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}


/**
 * 长按歌曲后弹出的「添加到歌单」弹层。
 * 列出所有歌单（可勾选加入/已加入态），支持一键新建歌单并把当前歌加入。
 */
@Composable
internal fun AddToPlaylistSheet(
    song: Song,
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onAdd: (Long) -> Unit
) {
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val onSheet = if (colors.isDark) Color.White else Color.Black
    var newName by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    // 拖把手下滑关闭
    val drag = rememberSheetDragState()

    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        panelModifier = Modifier.fillMaxHeight(0.8f),
        contentModifier = Modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 10.dp, bottom = 24.dp)
    ) {
        SheetDragHandle(drag, onDismiss, colors.card)
        Spacer(Modifier.height(14.dp))
        Text("加入歌单", color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        song.title,
                        color = colors.subText,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(16.dp))

                    if (state.playlists.isEmpty()) {
                        Text(
                            "还没有歌单，点下方「新建歌单」创建一个",
                            color = colors.subText,
                            fontSize = 13.sp
                        )
                    } else {
                        // 已在该歌单的提示
                        state.playlists.forEach { p ->
                            val alreadyIn = Playlists.contains(p.id, song.id)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .junPressableRow(scaleDown = 0.975f) {
                                        if (!alreadyIn) onAdd(p.id)
                                    }
                                    .junSurface(
                                        level = GlassLevel.ROW,
                                        colors = colors,
                                        shape = JunShape.item,
                                        accent = if (alreadyIn) colors.primary else null
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    p.name,
                                    Modifier.weight(1f),
                                    color = colors.text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (alreadyIn) "已加入" else "${p.songCount} 首",
                                    color = if (alreadyIn) accent else colors.subText,
                                    fontSize = 11.sp,
                                    fontWeight = if (alreadyIn) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 新建歌单并加入
                    if (showCreate) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(JunShape.item)
                                    .background(if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC))
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                BasicTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    singleLine = true,
                                    textStyle = TextStyle(fontFamily = LocalJunUiFamily.current, color = colors.text, fontSize = 14.sp),
                                    cursorBrush = SolidColor(colors.primary),
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { inner ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (newName.isEmpty()) {
                                                Text("歌单名称", color = colors.subText, fontSize = 14.sp)
                                            }
                                            inner()
                                        }
                                    }
                                )
                            }
                            Box(
                                Modifier
                                    .height(44.dp)
                                    .junPressable(feedback = PressFeedback.CLICK) {
                                        val n = newName.trim()
                                        if (n.isNotEmpty()) onCreateAndAdd(n)
                                    }
                                    .clip(JunShape.item)
                                    .background(accent)
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("创建并加入", color = accent.readableTextOn(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .junPressable { showCreate = true }
                                .clip(JunShape.item)
                                .background(colors.card),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("＋ 新建歌单并加入", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
    }
}

/**
 * 6.4：导入结果的单行统计（圆点 + 文案）。
 * 单独抽出来是因为三行的间距、圆点大小、字号必须一致，
 * 内联写三遍迟早会有一行字号不一样。
 */
@Composable
private fun ImportStatRow(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(
            Modifier
                .padding(end = 6.dp)
                .size(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Text(text, color = color, fontSize = 12.sp)
    }
}
