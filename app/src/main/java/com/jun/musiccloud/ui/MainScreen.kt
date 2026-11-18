package com.jun.musiccloud.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.derivedStateOf
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.jun.musiccloud.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
// runtime.* 不包含 saveable 包：rememberSaveable 在 androidx.compose.runtime.saveable 下
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

import com.jun.musiccloud.audio.BassHaptics
import com.jun.musiccloud.audio.EqualizerController
import com.jun.musiccloud.data.HomeFeed
import com.jun.musiccloud.data.MusicFolder
import com.jun.musiccloud.data.SessionSignals
import com.jun.musiccloud.data.LyricDisplayFilter
import com.jun.musiccloud.data.LineRenderHints
import com.jun.musiccloud.data.LyricLine
import com.jun.musiccloud.data.LyricParser
import com.jun.musiccloud.data.LyricRenderEngine
import com.jun.musiccloud.data.LyricRuntime
import com.jun.musiccloud.data.WordRevealMode
import com.jun.musiccloud.data.RepeatMode
import com.jun.musiccloud.data.Playlist
import com.jun.musiccloud.data.Playlists
import com.jun.musiccloud.data.PlaylistShare
import com.jun.musiccloud.data.PlaylistLinkDetector
import com.jun.musiccloud.data.ClipboardLinkStore
import com.jun.musiccloud.data.AppNotice
import com.jun.musiccloud.data.NoticeStore
import com.jun.musiccloud.data.RemoteConfigStore
import com.jun.musiccloud.data.ServerApi
import com.jun.musiccloud.BuildConfig
import com.jun.musiccloud.data.Telemetry
import com.jun.musiccloud.data.UpdateChecker
import com.jun.musiccloud.data.Song
import com.jun.musiccloud.data.identityKey
import com.jun.musiccloud.data.SortDirection
import com.jun.musiccloud.data.SortOrder
import com.jun.musiccloud.data.StartupLoginStore
import com.jun.musiccloud.data.formatLabel
import com.jun.musiccloud.data.lyricKey
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.player.qualityBadge
import com.jun.musiccloud.player.PlayerViewModel
import com.jun.musiccloud.ui.theme.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * R13 预留开关：歌单分享（ShareSheet 由另一路实现）。
 *
 * 这一轮先把**接线**在本文件里做完：长按菜单里的「分享歌单」入口、待分享目标、
 * 返回键分级、以及"分享层压住底部小播放器"的显隐关系。
 * R13：实现已落地（ui/PlaylistShareSheet.kt + data/PlaylistShare.kt），所以这里是 true。 若哪一天分享弹层要重新下线，只把这个常量改回 false 即可——入口整行不画， 不会出现「点了没反应」的假按钮。
 */
private const val PLAYLIST_SHARE_READY = true

/** 主界面：列表 ↔ 播放器（左右滑动），右上角设置 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onSelect: (Int) -> Unit,
    onSearch: (String) -> Unit,
    onToggleLike: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleSettings: (Boolean) -> Unit
) {
    val mainCtx = LocalContext.current
    // 6.7：自定义中心的开关落盘要尽早读出来，否则首帧会先用默认值画一遍再跳变。
    LaunchedEffect(Unit) { CustomConfig.init(mainCtx) }
    // 总后台下发的功能开关 / 首页横幅：Compose State，后台改完下一轮轮询即时生效。
    val appFlags = RemoteConfigStore.flagsState.value
    val appBanners = RemoteConfigStore.bannersState.value
    // 横屏「天台夜航」场景已下线，实现文件也已删除（死代码不留着占位）。
    // 自定义背景图 SAF 选择器
    val bgPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.setCustomBackground(uri) }
    // 自定义封面：先选图，再进裁剪对话框
    var pendingCoverUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingCoverUri = uri }
    // 歌词视频背景：持久化读权限，重启后仍能播放
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                mainCtx.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.setLyricVideoUri(uri.toString())
        }
    }
    // 下载目录选择器（选文件夹）
    // 首次下载路径提示：未确认过下载路径时，下载前弹窗让用户选择
    var pendingDownloadSong by remember { mutableStateOf<Song?>(null) }
    var showDownloadPathDialog by remember { mutableStateOf(false) }
    val downloadDirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { mainCtx.contentResolver.takePersistableUriPermission(uri, flags) }
            viewModel.setDownloadPath(uri.toString())
            viewModel.setDownloadPathConfirmed(true)
            // 如果是首次下载弹窗触发的选择，选完路径后立即开始下载
            pendingDownloadSong?.let { viewModel.downloadSong(it) }
            pendingDownloadSong = null
        }
    }
    // 果冻开关同步到全局动效中心：所有组件读 JunMotion.jelly
    // 6.6：动效风格（果冻/顺滑/超弹）从这里同步，jelly 由风格派生
    androidx.compose.runtime.LaunchedEffect(state.motionStyle) {
        JunMotion.applyStyle(com.jun.musiccloud.ui.JunMotion.MotionStyle.of(state.motionStyle))
    }
    LaunchedEffect(state.powerSavingAnim) {
        JunMotion.reduced = state.powerSavingAnim
    }
    // 主题色过渡：切主题/明暗时整套配色 450ms 渐变，不整屏硬闪。
    // 快速连续切主题：新动画总是从「当前显示的混色」出发（LaunchedEffect 重启时
    // holder 里正是上一段动画停下的帧），所以接续而不是跳变。
    val targetColors = state.theme.colors(
        state.mode, state.systemDark,
        customPrimary = Color(state.customPrimaryArgb),
        customSecondary = Color(state.customSecondaryArgb)
    )
    val colorAnim = remember { androidx.compose.animation.core.Animatable(1f) }
    var mixedColors by remember { mutableStateOf(targetColors) }
    LaunchedEffect(state.theme, state.mode, state.systemDark, state.customPrimaryArgb, state.customSecondaryArgb) {
        val from = mixedColors
        val to = targetColors
        if (from == to) { colorAnim.snapTo(1f); return@LaunchedEffect }
        colorAnim.snapTo(0f)
        colorAnim.animateTo(
            1f,
            androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.EaseInOutCubic)
        ) { mixedColors = lerpJunColors(from, to, value) }
    }
    // 6.3 修复版：液态玻璃材质下，把 surface/card/row 三个实色令牌统一成
    // 与玻璃同源的半透明（见 harmonizeColorsForSurface 的注释）。
    // 全站所有裸 .background(colors.card) 由此自动"变成玻璃同料"，消除分裂感。
    val colors = harmonizeColorsForSurface(if (colorAnim.value >= 1f) targetColors else mixedColors)
    // 6.9.7：三页 —— 0 模式探索 / 1 音乐库 / 2 播放页。初始停在音乐库。
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    // 6.6：更新弹窗里「去气氛模式」按钮 —— 关掉弹窗后直接滑到模式探索页，
    // 不用用户自己猜「往哪儿滑」。
    var requestMoodPage by remember { mutableStateOf(false) }
    LaunchedEffect(requestMoodPage) {
        if (requestMoodPage) {
            pagerState.animateScrollToPage(0)
            requestMoodPage = false
        }
    }
    // 主页 ↔ 播放页的横向翻页：翻页中跳过玻璃真折射（最贵的一段），翻完补画回来。
    // 与列表滚动共用同一个开关，见 GlassRuntime。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }
            .collect { moving ->
                GlassRuntime.pageScrolling = moving
                if (!moving) GlassRuntime.requestRedraw()
            }
    }

    val glass = isLiquidGlass

    // 液态玻璃的取样背板：背景一层、页面内容一层。
    // 弹层（设置 / 均衡器）浮在页面之上，要同时折射这两层，
    // 才会出现"透过玻璃看见下面的卡片被压缩变形"这种真玻璃观感。
    val bgBackdrop = rememberJunBackdrop()
    val pageBackdrop = rememberJunBackdrop()
    val pageBackdrops = remember(glass, bgBackdrop) {
        if (glass) listOf(bgBackdrop) else emptyList()
    }
    val sheetBackdrops = remember(glass, bgBackdrop, pageBackdrop) {
        if (glass) listOf(bgBackdrop, pageBackdrop) else emptyList()
    }

    // 均衡器弹层
    var showEqualizer by remember { mutableStateOf(false) }
    val eqState by EqualizerController.state.collectAsState()

    // 睡眠定时器弹层：提到根层级，才能和设置/均衡器一样折射「背景 + 页面」两层
    var showSleepTimer by remember { mutableStateOf(false) }

    // 歌单管理弹层
    var showPlaylists by remember { mutableStateOf(false) }

    // 6.4：播放库整理弹层（从「本地歌单」标题旁边的「整理」进入）
    var showLibraryTidy by remember { mutableStateOf(false) }

    /**
     * R13：统一的「打开完整歌单列表」入口，首页两块的「更多」、「本地歌单」弹层、「我的」抽屉共用。
     *
     * 这里只做转发，不再自己维护第二份开关状态：根治在 PlayerViewModel.openNeteasePlaylists()。
     * 那个函数以前第一句就是 `if (!isLoggedIn) 提示登录; return`，等于把「公开歌单广场」整块
     * 关在扫码登录页后面；现在未登录照样打开广场（推荐池由免登录的 loadNeteaseHomeData() 填），
     * 需要账号的「每日推荐 / 我喜欢 / 同步我的歌单」仍由各自方法逐个提示登录——点了才提示。
     * returnTo = "localPlaylists" 让弹层关掉后回到本地歌单层，而不是把用户一步踢回首页。
     */
    val openPlaylistSquare: (Boolean) -> Unit = { backToLocal ->
        viewModel.openNeteasePlaylists(if (backToLocal) "localPlaylists" else "")
    }

    // R13：歌单分享 / 导入。showPlaylistShare = 分享层是否展开；
    // shareTarget = 要分享的那份列表（名字 + 曲目），由长按菜单「分享歌单」或歌单层那几颗按钮写入。
    // 实现见 ui/PlaylistShareSheet.kt + data/PlaylistShare.kt，弹层调用在 PlaylistSheet 之后。
    var showPlaylistShare by remember { mutableStateOf(false) }
    var shareTarget by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    /** true = 分享层只做导入（从「导入 Jun 口令」进来）：导出那一整块不画，口令也不会被生成 */
    var junImportOnly by remember { mutableStateOf(false) }
    // 统一导入入口自动识别出的口令预填文本（随弹层关闭清空）
    var shareInitialPaste by remember { mutableStateOf("") }

    // 分享/导入的回执走轻提示栈。为什么不塞进 VM：ToastItem 要「文案 + tick」两个字段，
    // 而 PlayerUiState 的构造参数已经超过 dex 的 255 寄存器上限（见文件里那套 PlayerExtras 拆分），
    // 再加一个 state 字段就是拿全站闪退风险换一句提示——这一句只有弹层用得着，留在界面本地。
    var shareNotice by remember { mutableStateOf("") }
    var shareNoticeTick by remember { mutableIntStateOf(0) }

    // 全屏搜索页 / 「我的」侧滑抽屉（在线版新增的二级界面）
    var showSearchScreen by remember { mutableStateOf(false) }
    var showMyDrawer by remember { mutableStateOf(false) }
    // 播放列表弹层 / 账号弹层
    var showQueue by remember { mutableStateOf(false) }
    var showRank by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    /** 「我的主页」弹层 */
    var showProfile by remember { mutableStateOf(false) }
    /** 6.4：意见反馈弹层（入口在「我的」抽屉最上面） */
    var showFeedback by remember { mutableStateOf(false) }
    // 修改密码弹层
    var showChangePassword by remember { mutableStateOf(false) }
    // 下载管理弹层
    var showDownloadManager by remember { mutableStateOf(false) }
    // 6.9.10：锁屏歌词样式页（酷狗那套五样式）。放在根层而不是设置面板内部，
    // 才能拿到 sheetBackdrops 的折射背板，液态玻璃效果才成立。
    var showLockLyricStyle by remember { mutableStateOf(false) }
    // 首启登录：只在「第一次安装」出现一次，只有登录（Jun Music / 网易云）和「进入」。
    // 绘本、聚光灯、隐私同意、设备与材质选择都不再随首启出现；
    // 欢迎绘本与指路保留在「设置 → 重看新手引导」，隐私政策保留在「设置 → 关于」。
    val guideContext = LocalContext.current
    // 「先看见首页，再弹登录」：首启不再一上来就盖住界面。
    // firstInstall 只在本次组合里判定一次，记下是不是第一次装；
    // showFirstRun 一开始是 false，让首页先完整画出来，过一会儿再浮上来。
    val firstInstall = remember { !hasSeenOnboarding(guideContext) }
    var showFirstRun by remember { mutableStateOf(false) }
    // 首页信息流先出来，再盖登录层：数据一到就 0.35s 后弹；网络慢也最多等 1.8s，
    // 不会为了等封面把首启拖成白屏。（R9 起判据从榜单换成推荐歌单——榜单已经不在首页了。）
    val homeFeedReady = state.neteaseRecPlaylists.isNotEmpty()
    LaunchedEffect(firstInstall, homeFeedReady) {
        if (!firstInstall || showFirstRun) return@LaunchedEffect
        delay(if (homeFeedReady) 350 else 1800)
        showFirstRun = true
    }
    // 设置里即时重看欢迎绘本 + 聚光灯
    var showReplayGuide by remember { mutableStateOf(false) }

    // 6.9.9：首页分区自定义（顺序 + 显示隐藏，落盘 jun_prefs）
    var homeOrder by remember { mutableStateOf(HomeSectionConfig.order(mainCtx)) }
    var homeHidden by remember { mutableStateOf(HomeSectionConfig.hidden(mainCtx)) }
    var showHomeCustomize by remember { mutableStateOf(false) }

    // 6.6：桌面悬浮歌词默认关闭，且不再每次进 App 就自动弹「开启桌面歌词」。
    // 以前 KEY_ENABLED 的缺省值写成 true，和 default_settings.json 里的 false 相反，
    // 于是刚装完 / 每次进去都被一个授权弹窗糊脸，可开关自己明明是关的。
    // 现在只有用户自己在设置里打开悬浮歌词、又缺悬浮窗权限时才引导一次。
    val floatPromptShown = remember {
        val sp = mainCtx.getSharedPreferences("jun_prefs", Context.MODE_PRIVATE)
        val code = runCatching {
            mainCtx.packageManager.getPackageInfo(mainCtx.packageName, 0).longVersionCode
        }.getOrDefault(0L)
        val flag = "float_lyric_prompted_$code"
        // 首启引导没走完时不弹：授权引导排在首启绘本之后，别抢首启的戏。
        val seenGuide = hasSeenOnboarding(mainCtx)
        val need = seenGuide &&
            sp.getBoolean(com.jun.musiccloud.playback.FloatingLyric.KEY_ENABLED, false) &&
            !com.jun.musiccloud.playback.FloatingLyric.hasPermission(mainCtx) &&
            !sp.getBoolean(flag, false)
        if (need) sp.edit().putBoolean(flag, true).apply()
        need
    }
    var showFloatLyricPrompt by remember { mutableStateOf(floatPromptShown) }
    // 新功能导览（只从「设置 → 重看」和「我的」以外的入口手动触发；6.0 起不再自动弹）
    var showFeatureTour by remember { mutableStateOf(false) }
    var featureTourStep by remember { mutableIntStateOf(FeatureTourSteps.NONE) }
    // 版本更新说明：升级后首次进入弹一次，看完写标记。
    // 6.0 把它排到所有启动流程的最后（见下面 whatsNewReady / 渲染门）：以前它 1.6s 就冒头，
    // 1.8s 弹出的导览一出现就把它的渲染门条件顶掉，用户看到的就是"更新说明闪一下就没了"。
    var showWhatsNew by remember { mutableStateOf(!hasSeenWhatsNew(guideContext)) }
    var whatsNewReady by remember { mutableStateOf(false) }
    // R13：公告不再只靠固定延时冒头。用户这一轮的要求是"每次进去都引导一下，
    // 不能修了啥新功能都不知道"，可如果公告压在还没出数据的空首页上，用户只会顺手关掉——
    // 等于没通知到。改成等信息流真的出来再等 600ms（让它画完），网络慢最多等 2600ms 兜底，
    // 不会因为等封面把公告拖成"这次没弹"。
    LaunchedEffect(homeFeedReady) {
        if (whatsNewReady) return@LaunchedEffect
        delay(if (homeFeedReady) 600 else 2600)
        whatsNewReady = true
    }
    // R14：用户这轮的原话是「用户刚下载完进去引导一下」——光给公告不够，还得带着走一遍。
    // 做法是把功能导览接在**公告关掉之后**，只放一次，而且只给第一次安装的人：
    //  - firstInstall：老用户升级照旧不自动弹（6.0 那次删掉自动弹的结论没被推翻，
    //    它和公告抢同一段时间窗，公告会被顶掉成「闪一下就没了」）；
    //  - whatsNewReady && !showWhatsNew：公告不仅过了渲染门、而且是用户自己关掉的，
    //    首启绘本 / 启动登录还没走完时 showWhatsNew 仍是 true，这里就继续等；
    //  - 标记在**发起时**就写：中途杀掉也不会下次进来再拦一遍。
    // 再延 500ms 是让公告的收起动画走完，两层同帧切换看起来就像闪屏（这轮正在治闪）。
    // 6.9.3：**取消自动聚光灯导览**。用户明确要求「之前的引导界面都给去掉」——
    // 升级后只看一份可滚动的新功能清单（FeatureGuideSheet），不再强制一步步遮挡界面。
    // 想重看的人从「设置 → 新功能与更新记录」再打开。
    // 旧的 showFeatureTour 状态保留（设置里仍可手动触发），但这里不再自动置 true。
    // 4.0.3 特大事故补偿：更新说明关掉后弹一次；未登录等登录后再弹
    var showVipComp by remember { mutableStateOf(false) }
    // 「听歌待办」页（空状态引导的落地场景）
    var showTodoBoard by remember { mutableStateOf(false) }

    // ===== 剪贴板歌单链接检测 =====
    // 用户复制网易云 / QQ / 汽水歌单链接后回到 App，自动问一句要不要导入
    var clipboardLink by remember { mutableStateOf<String?>(null) }
    var clipboardBusy by remember { mutableStateOf(false) }
    var clipboardStatus by remember { mutableStateOf("") }
    var clipboardSuccess by remember { mutableStateOf(false) }
    val clipboardScope = rememberCoroutineScope()
    // 启动登录引导：只服务「已经看过首启」的回归用户。
    //  - 首启用户：登录已经放进首启设置的第一节，结束后不再补弹；
    //  - 回归用户：进 App 静置 ~1.2s 后弹（满足「进去之后再弹」，不挡首屏）。
    var showStartupLogin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (firstInstall) return@LaunchedEffect   // 首启用户由 FirstRunLogin 负责登录
        delay(1200)
        if (StartupLoginStore.shouldShow(mainCtx, state.accountLoggedIn)) {
            showStartupLogin = true
        }
    }

    // 4.0.3 补偿弹窗：等更新说明、首启、登录引导、功能导览都结束，再弹一次
    LaunchedEffect(whatsNewReady, showWhatsNew, state.accountLoggedIn, state.vipClaimed,
        showFirstRun, showFeatureTour, showReplayGuide, showStartupLogin) {
        if (!whatsNewReady || showWhatsNew || state.vipClaimed) return@LaunchedEffect
        if (!state.accountLoggedIn) return@LaunchedEffect
        if (showFirstRun || showFeatureTour || showReplayGuide || showStartupLogin) return@LaunchedEffect
        if (!hasSeenVipCompensation(guideContext)) showVipComp = true
    }

    // 6.0：删掉「升级后自动弹一次新功能导览」这条链路。
    // 它和版本更新说明抢同一段时间窗（导览 1.8s、更新说明 1.6s），而更新说明的渲染门里
    // 写着 !showFeatureTour —— 导览一冒头就把更新说明顶掉，用户看到的是"更新说明闪一下就
    // 消失"。导览本身还留在「设置 → 重看新手引导 / 功能导览」里，随时可以手动走一遍。

    // 首启设置里用户点「去开启」时才真正唤起系统通知弹窗
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* 授权结果不影响播放，无需回调动作 */ }

    /**
     * 远程配置 + 版本更新（服务器控制）。
     *
     * 冷启动一次：先读本地配置缓存（热搜词），再异步拉服务器配置覆盖，
     * 最后检查版本——服务器标记 force 就弹不可取消的更新框，无需发版即可推送内容。
     */
    var pendingUpdate by remember { mutableStateOf<UpdateChecker.Update?>(null) }
    val updateScope = rememberCoroutineScope()
    val appContext = LocalContext.current

    // 服务器下发的「维护模式 / 公告」：本次启动拉到配置后立即生效，
    // 之后每 5 分钟再对一次，后台改了开关正在使用的人也能收到，不用重装/重启。
    var maintenanceOn by remember { mutableStateOf(false) }
    var maintenanceTip by remember { mutableStateOf("") }

    // 通知中心：后台每发一条就是一条新 id，App 30 秒轮询一次，
    // 有多条时按 id 从小到大依次弹，点「我知道了」才推进已读位。
    var noticeQueue by remember { mutableStateOf<List<AppNotice>>(emptyList()) }

    val syncRemoteConfig: suspend () -> Unit = {
        val cfg = withContext(Dispatchers.IO) { ServerApi.fetchConfig() }
        if (cfg != null) {
            RemoteConfigStore.save(appContext, cfg)
            viewModel.applyHotKeywords(cfg.hotKeywords)
            maintenanceOn = cfg.maintenance
            if (cfg.maintenance) maintenanceTip = cfg.maintenanceText
        }
    }

    val syncNotices: suspend () -> Unit = {
        val list = withContext(Dispatchers.IO) {
            ServerApi.fetchNotices(
                since = NoticeStore.maxSeenId,
                deviceKey = Telemetry.deviceId(appContext),
                versionCode = BuildConfig.VERSION_CODE,
                channel = "stable",
                uid = state.accountUserId,
                username = state.accountName,
                model = android.os.Build.MODEL
            )
        }
        if (!list.isNullOrEmpty()) {
            // 一次性通知：id 大于已读位才弹；每日通知：今天弹满 N 次就不再弹
            val incoming = list.filter { NoticeStore.canShow(it) }
            val merged = (noticeQueue + incoming)
                .distinctBy { it.id }
                .sortedBy { it.id }
            if (merged.size != noticeQueue.size) noticeQueue = merged
        }
    }

    LaunchedEffect(Unit) {
        // 冷启动分级：首屏要的是"歌单快出来"，不是"什么都在同一秒开始"。
        // 曲库由 ViewModel 自己的 loadLibrary 负责；远程配置与版本检查都是
        // 用户此刻看不见收益的东西，等首屏渲染完（800ms）再做，避免和曲库
        // 抢那一点点弱网带宽——原来三个请求一起打出去，弱网下首屏会明显慢。
        RemoteConfigStore.load(appContext)
        NoticeStore.load(appContext)
        delay(800)
        syncRemoteConfig()
        syncNotices()
        val r = UpdateChecker.check(appContext)
        if (r is UpdateChecker.Result.NewVersion) pendingUpdate = r.update
        // 常驻对时：有变化时 20 秒一轮；连续多轮没新通知就退避到 60 秒，
        // 少打请求也少耗电，后台一发布仍会在下一轮（≤60 秒）弹出。
        var quiet = 0
        while (true) {
            val before = noticeQueue.size
            syncNotices()
            syncRemoteConfig()
            quiet = if (noticeQueue.size > before) 0 else quiet + 1
            delay(if (quiet >= 3) 60 * 1000L else 20 * 1000L)
        }
    }

    // R9 撤掉「每次打开搜索页补拉一次配置」：它卡在打开那一帧上打一个阻塞 HTTP，
    // 而拉回来的热搜词全 App 没有任何地方在读（state.hotKeywords 无消费者）。
    // 后台开关本身已有 20s（安静时 60s）轮询覆盖，搜索页不需要再单独催一次。
    // 设置页「检查更新」：把结果原样交回去，设置里才能显示「已是最新 / 发现新版本 / 失败」。
    val checkUpdateNow: suspend () -> UpdateChecker.Result = {
        val r = UpdateChecker.check(appContext)
        if (r is UpdateChecker.Result.NewVersion) pendingUpdate = r.update
        r
    }

    /**
     * 启动 / 停留统计：
     *   · 每次点开 App（冷启动或从后台回来）上报一次 launch，点开一次就是一次；
     *   · 进入前台记下时间，退到后台时上报 session（停留秒数 + 进入时间），
     *     后台因此能看到「几点进来、几点出去、用了多久」；
     *   · 回到前台立即刷新通知和热搜词，不用等轮询。
     */
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var appSessionId by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var appSessionStart by remember { mutableStateOf(System.currentTimeMillis()) }
    DisposableEffect(lifecycleOwner) {
        var inSession = false
        var sessionJob: Job? = null
        fun beginSession() {
            if (inSession) return
            inSession = true
            val now = System.currentTimeMillis()
            appSessionId = java.util.UUID.randomUUID().toString()
            appSessionStart = now
            // 启动只在进程内第一次进前台时上报：热恢复 / 转屏 / 从后台回来都不再 +1
            Telemetry.reportLaunchOnce(appContext)
            // 断网期间攒下的事件，回到前台立刻补发
            Telemetry.flush(appContext)
            // 每 60 秒打一次停留检查点：进程被杀也不会把这段使用时长整段丢掉。
            // 服务端按 sessionId 覆盖同一条记录，不会重复累加。
            sessionJob?.cancel()
            sessionJob = updateScope.launch {
                while (true) {
                    delay(60_000L)
                    val secs = ((System.currentTimeMillis() - appSessionStart) / 1000L).toInt()
                    if (secs > 0) Telemetry.reportSession(appContext, secs, appSessionStart, appSessionId)
                }
            }
            updateScope.launch {
                delay(600)
                syncRemoteConfig()
                syncNotices()
                val r = UpdateChecker.check(appContext)
                if (r is UpdateChecker.Result.NewVersion) pendingUpdate = r.update
            }
        }
        fun endSession() {
            if (!inSession) return
            inSession = false
            sessionJob?.cancel()
            sessionJob = null
            val secs = ((System.currentTimeMillis() - appSessionStart) / 1000L).toInt()
            Telemetry.reportSession(appContext, secs, appSessionStart, appSessionId)
            Telemetry.flush(appContext)
        }
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> beginSession()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> endSession()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            beginSession()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            endSession()
        }
    }

    /**
     * 剪贴板歌单链接：每次回到前台检查一次。
     *
     * 只读第一条文本，交给 [PlaylistLinkDetector] 判断是不是音乐平台歌单；
     * 同一条内容 10 分钟内不重复打扰（见 [ClipboardLinkStore]）。
     * 这里只记录待处理链接，真正弹卡片由下方渲染条件决定，
     * 避免首启 / 新功能导览 / 登录弹层叠在一层。
     */
    DisposableEffect(lifecycleOwner) {
        val cm = mainCtx.getSystemService(android.content.ClipboardManager::class.java)
        fun scanClipboard() {
            val clip = cm?.primaryClip ?: return
            if (clip.itemCount <= 0) return
            val text = runCatching {
                clip.getItemAt(0)?.coerceToText(mainCtx)?.toString().orEmpty()
            }.getOrDefault("")
            val link = PlaylistLinkDetector.extractAny(text) ?: return
            if (!ClipboardLinkStore.shouldPrompt(mainCtx, link, 0L)) return
            clipboardLink = link
            clipboardStatus = ""
            clipboardSuccess = false
        }
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) scanClipboard()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            scanClipboard()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 长按歌曲 → 弹出操作菜单（添加到歌单 / 下一首播放 / 下载）
    var pendingActionSong by remember { mutableStateOf<Song?>(null) }
    /**
     * 长按菜单打开时，用户正在看的那份列表（来源名 + 列表本体）。
     *
     * 批量「全选」必须以它为准：之前选中集写死成 `state.queue`（播放队列），
     * 在热歌榜 / 歌单 / 搜索结果里点「全选」，实际选中的是外面曲库里的歌，
     * 批量喜欢 / 加歌单 / 下载全打在错误的列表上。
     */
    var pendingActionList by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    // 操作菜单里选「添加到歌单」后 → 待加入歌单的歌曲
    var pendingAddSong by remember { mutableStateOf<Song?>(null) }

    // 批量添加到歌单：待加入的多首歌曲
    var pendingAddSongs by remember { mutableStateOf<List<Song>>(emptyList()) }

    // 6.6.9：整单收藏（歌单/榜单 → 我的歌单），值是「来源名 + 整份列表」
    var saveAllTarget by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }

    // ===== 批量选择（5.1 重构：状态提升到 MainScreen） =====
    // 选中集、来源、显隐都在这里，各列表页只拿一个 ListSelect 上下文画勾选框，
    // 底部操作栏也只在这一个地方画——所以任何页面点全选，范围都是当前这份列表。
    val selectedIds = remember { mutableStateListOf<Long>() }
    var selectMode by remember { mutableStateOf(false) }
    var selectSource by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    /** 「最近批量操作」面板 */
    var showBatchLog by remember { mutableStateOf(false) }
    fun enterSelectMode(name: String, songs: List<Song>) {
        if (songs.isEmpty()) return
        selectSource = name to songs
        selectedIds.clear()
        selectedIds.addAll(songs.map { it.id })
        selectMode = true
    }
    fun exitSelectMode() {
        selectMode = false
        selectedIds.clear()
        selectSource = null
    }
    val listSelect = ListSelect(
        active = selectMode,
        selectedIds = selectedIds.toList(),
        sourceName = selectSource?.first.orEmpty(),
        onToggle = { id -> if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id) },
        onExit = { exitSelectMode() },
        onStart = { name, songs -> enterSelectMode(name, songs) }
    )

    /**
     * 播放页（Pager 第 2 页）按返回回到库页，而不是退出应用。
     * 只在没有任何浮层时生效——有浮层时上面那个 BackHandler 会先接管。
     */
    val onPlayerPage by remember { derivedStateOf { pagerState.currentPage == 2 } }

    /**
     * 返回键分级处理。
     *
     * 这些二级界面都是状态驱动的浮层而不是独立 Activity/路由，
     * 系统返回键默认直接落到 Activity 上导致退出应用——用户预期是「退回上一级」。
     *
     * 判定顺序 = 视觉堆叠顺序，永远只关最上面那一层：
     * 加歌单 > 歌单管理 > 均衡器 > 睡眠定时 > 收听报告 > 设置 > 播放页。
     * 全都关着时不拦截，交还系统（此时返回 = 退出，符合预期）。
     */
    val hasOverlay = showHomeCustomize || CustomConfig.showDialog.value || selectMode || pendingActionSong != null || pendingAddSong != null || pendingAddSongs.isNotEmpty() ||
        showDownloadPathDialog || showPlaylists || showEqualizer || clipboardLink != null ||
        showSleepTimer || showSearchScreen || showMyDrawer || showQueue || showAccount || showProfile || showFeedback || showDownloadManager ||
        // 6.9.11：锁屏歌词样式页此前漏在浮层清单里 —— 打开后返回键既不关它、
        // 也不挡住下面的翻页 BackHandler，用户"退都退不出去"
        showLockLyricStyle ||
        showFirstRun || showReplayGuide || showTodoBoard || state.showReport || state.showSettings || showRank ||
        showPlaylistShare ||
        state.showNeteaseLogin || state.commentsTitle.isNotBlank() ||
        state.neteaseListTitle.isNotBlank() || state.showNeteasePlaylists ||
        state.showNeteaseToplist || state.artistDetail != null
    BackHandler(enabled = hasOverlay) {
        when {
            // 批量选择模式浮在所有列表之上，返回键先退出它
            selectMode -> exitSelectMode()
            // 6.9.9 / 6.7：自定义中心压在其它层之上，返回先关它
            showHomeCustomize || CustomConfig.showDialog.value -> {
                showHomeCustomize = false
                CustomConfig.showDialog.value = false
            }
            // 长按歌曲弹出的操作菜单永远在最上层，先关它
            pendingActionSong != null -> pendingActionSong = null
            clipboardLink != null -> {
                val link = clipboardLink
                if (link != null && !clipboardBusy) {
                    ClipboardLinkStore.markHandled(mainCtx, link, 0L)
                    clipboardLink = null
                }
            }
            // 6.9.8：曲目列表弹层可能叠在歌手页上（歌手页点专辑）。
            // 必须先判曲目列表再判歌手页，否则从专辑按返回会把歌手页整个关掉
            // （用户反馈："返回的时候全部返回了"）——现在一层一层退：专辑 → 歌手 → 首页。
            state.neteaseListTitle.isNotBlank() -> {
                val returnTo = state.neteaseListReturnTo
                viewModel.closeNeteaseList()
                if (returnTo == "localPlaylists") showPlaylists = true
            }
            state.artistDetail != null -> viewModel.closeArtist()
            state.showNeteaseToplist -> viewModel.closeNeteaseToplist()
            // R13：歌单分享层压在歌单弹层之上，返回键先关最上面这一层。
            showPlaylistShare -> { showPlaylistShare = false; shareTarget = null }
            // R13：未登录时首页「更多」开的是这层本地歌单广场，它和 VM 那层 showNeteasePlaylists
            // 是同一个视觉层级、不会同时开着，所以紧跟在它前面按同样方式关掉。
            state.showNeteasePlaylists -> {
                val returnTo = state.neteasePlaylistsReturnTo
                viewModel.closeNeteasePlaylists()
                if (returnTo == "localPlaylists") showPlaylists = true
            }
            state.showNeteaseLogin -> viewModel.closeNeteaseLogin()
            state.commentsTitle.isNotBlank() -> viewModel.closeComments()
            // 首启设置之上还压着登录弹层时，返回先关登录，不能把首启一起关掉
            showAccount -> { showAccount = false; viewModel.clearAccountStatus() }
            showFirstRun -> Unit   // 首启设置不可被返回键跳过：必须点「进入」或「跳过」
            showReplayGuide -> { showMyDrawer = false; showReplayGuide = false }
            showTodoBoard -> showTodoBoard = false
            pendingAddSong != null -> pendingAddSong = null
            pendingAddSongs.isNotEmpty() -> pendingAddSongs = emptyList()
            showDownloadPathDialog -> { showDownloadPathDialog = false; pendingDownloadSong = null }
            showPlaylists -> showPlaylists = false
            showSearchScreen -> { onSearch(""); showSearchScreen = false }
            showMyDrawer -> showMyDrawer = false
            showFeedback -> showFeedback = false
            showProfile -> showProfile = false
            showRank -> showRank = false
            showQueue -> showQueue = false
            showDownloadManager -> showDownloadManager = false
            showLockLyricStyle -> showLockLyricStyle = false
            showEqualizer -> showEqualizer = false
            showSleepTimer -> showSleepTimer = false
            state.showReport -> viewModel.toggleReport(false)
            state.showSettings -> onToggleSettings(false)
        }
    }

    // 库页 ← 播放页。卡片模式（isDeck）没有 Pager 结构，不适用。
    val backScope = rememberCoroutineScope()
    /**
     * 翻到某一页（0=音乐库 1=播放页），统一带上 6.0 拉长后的吸附曲线。
     *
     * 点歌自动展开、⋮ 里的「播放」、导览跳页、点小播放器 —— 这些以前都直接调
     * `animateScrollToPage(page)`，吃的是框架默认的 `spring(ζ=1, k=1500)`，
     * 页面"啪"地就换完了。收成一个 helper，曲线只有一处真源。
     */
    val goToPagerPage: (Int) -> Unit = { page ->
        backScope.launch {
            pagerState.animateScrollToPage(page, animationSpec = JunMotion.pagerSnap())
        }
    }
    BackHandler(enabled = !hasOverlay && !state.layout.isDeck && onPlayerPage) {
        goToPagerPage(1)
    }
    // 6.9.7：模式探索页按返回回到音乐库，而不是直接退出应用。
    BackHandler(
        enabled = !hasOverlay && !state.layout.isDeck && pagerState.currentPage == 0
    ) {
        goToPagerPage(1)
    }

    /**
     * 「点歌后自动展开播放器」（设置 → 播放 → 点歌直接展开播放器）。
     *
     * 走的是分页器本身的动画，因此和手指上滑完全同一条路径：morphProgress() 连续插值，
     * 小播放器塌下去、播放页升起来那套形变一次走完，不是"另开一个页面"。
     * 卡片/堆叠布局没有 Pager（播放器就在当前页），因此直接跳过。
     */
    val openPlayerIfAuto: () -> Unit = {
        if (state.autoOpenPlayer && !state.layout.isDeck) {
            goToPagerPage(2)
        }
    }

    // 库页且处于「我喜欢」/自定义歌单视图时，按返回先回到「全部音乐」，
    // 避免"进了我喜欢就退不出来、一直卡在那个列表"的体验。
    BackHandler(
        enabled = !hasOverlay && !onPlayerPage &&
            (state.onlyLiked || state.activePlaylistId != null)
    ) {
        viewModel.goHome()
    }

    // 下载前检查：首次下载（未确认路径）时弹窗让用户选择默认路径或自定义路径
    // 后台关闭「下载」时直接不执行（入口本身也会隐藏，这里兜底拦截旧入口）。
    val tryDownload: (Song) -> Unit = { song ->
        if (appFlags.download) {
            if (!state.downloadPathConfirmed) {
                pendingDownloadSong = song
                showDownloadPathDialog = true
            } else {
                viewModel.downloadSong(song)
            }
        }
    }

    // 库页列表状态：提升到根层级，滚动条挂在根 Box 上（保证钉在屏幕右缘）
    val libraryListState = rememberLazyListState()
    // 快速滚动条是否正在拖动（拖动期间列表由滑条驱动，上栏需实时跟随）
    val scrollerDragging = remember { mutableStateOf(false) }

    // 上栏计数（几首 / 已选文件夹）：启动时显示 4 秒后自动隐藏，之后不再常驻。
    // 状态放在根层级而不是 LibraryPage：切到播放页再切回来不会重新计时显示。
    var showCountLabel by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(4000)
        showCountLabel = false
    }

    // 全局「用户最近一次操作」计数器：搜索页底部小播放器空闲 4 秒后会淡去，
    // 任意触摸（滑列表、点按钮…）都算一次操作，让它重新浮现。
    // 刻意用 State 对象而不是解构成值：值的读取下沉到 MiniPlayerBar 内部，
    // 这样每次触摸只重组那一枚播放条，不会带着整个 MainScreen 一起重组。
    val touchTickState = remember { mutableIntStateOf(0) }

    // 搜索页是否处于最顶层（没有被播放列表 / 账号 / 加歌单等更靠上的弹层盖住）。
    // 只有它最靠上时，底部小播放器才浮在搜索页之上；否则会被弹层压住或反过来盖住弹层。
    val searchIsTop = showSearchScreen && !showQueue && !showAccount && !showFirstRun &&
        !showReplayGuide && pendingAddSong == null && pendingUpdate == null

    // 底部小播放器：仅「纵向列表」布局和搜索页显示；横向/堆叠/唱片墙本身就是播放器，不重复显示。
    // 打开播放列表等弹层时隐藏，避免盖住弹层内容。
    // 小播放器显示条件：
    // 1. 有正在播放的歌
    // 2. 除搜索外的任何弹层/抽屉都没开（设置、我的、播放列表、账号、下载等一律隐藏）
    // 3. 布局是纵向列表，或者当前在搜索页
    // 目的：小播放器只在「纵向列表」和「搜索」两个场景出现，其他任何界面都不挡视线。
    val otherOverlayOpen = pendingActionSong != null || pendingAddSong != null ||
        pendingAddSongs.isNotEmpty() || showDownloadPathDialog || showPlaylists ||
        showEqualizer || showSleepTimer || showMyDrawer || showQueue || showAccount || showProfile || showFeedback ||
        showDownloadManager || showFirstRun || showReplayGuide || showFeatureTour ||
        showWhatsNew ||
        clipboardLink != null ||
        showTodoBoard || state.showReport || state.showSettings ||
        // R13：本地歌单广场与歌单分享都是全屏压层的弹层，开着就不该再有底部小播放器
        showPlaylistShare ||
        state.showNeteaseLogin || state.commentsTitle.isNotBlank() ||
        state.neteaseListTitle.isNotBlank() || state.showNeteasePlaylists ||
        state.showNeteaseToplist || state.artistDetail != null
    val showMiniPlayer = state.currentSong != null &&
        !otherOverlayOpen &&
        (state.layout == JunLayout.LIST || showSearchScreen)

    // 聚光灯锚点登记表：库页各入口把自己的根坐标报上来，首启引导照着挖洞。
    // 放在根层级，保证遮罩铺满的坐标系与锚点上报的坐标系是同一个。
    val spotlightRegistry = remember { SpotlightRegistry() }

    // 播放页的档位/倍速动作：一次性 provide，深处的徽章和倍速行直接取。
    val playerTuning = remember(viewModel) {
        PlayerTuning(
            onCycleQuality = { viewModel.cycleAudioQuality() },
            onSetSpeedBp = { viewModel.setPlaybackSpeedBp(it) },
            onDismissFlash = { viewModel.dismissQualityFlash() },
            onClearCache = { viewModel.clearStreamCache() },
            onDismissCacheNotice = { viewModel.dismissCacheNotice() }
        )
    }

    // 6.6：全局字号 / 排版密度，两处根因一起修。
    // ① 以前写的是 Density(density, JunUiConfig.fontScale)，把 MainActivity 里
    //    「设置 → 界面字号」算好的 fontScale 直接覆盖掉了 —— 设置页拖字号没反应，
    //    只有自定义中心自己那三档在变，用户说的「改的是局部」就是它。
    //    现在在 baseDensity.fontScale（已含设置页字号）之上再乘排版档倍率，两层叠加、全 App 生效。
    // ② 以前每次重组都 new 一个 Density 塞进 CompositionLocal，整棵树所有读
    //    LocalDensity 的节点每帧全部失效重组，是滚动/动画卡顿的主因之一。
    //    用 remember 缓存实例，依赖没变就不换对象。
    val baseDensity = LocalDensity.current
    val uiFontScale = JunUiConfig.fontScale
    val appDensity = remember(baseDensity, uiFontScale) {
        Density(baseDensity.density, baseDensity.fontScale * uiFontScale)
    }
    CompositionLocalProvider(
        LocalSpotlightRegistry provides spotlightRegistry,
        LocalPlayerTuning provides playerTuning,
        LocalCompact provides JunUiConfig.spacing,
        LocalDensity provides appDensity
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            // 只观察、不消费：任意按下事件都刷新计数，用于小播放器空闲淡出。
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    touchTickState.intValue++
                }
            }
    ) {
        // 共享模糊背景（玻璃的最底层背板）
        AuroraBackground(
            state.currentSong?.filePath,
            isDark = colors.isDark,
            blurRadius = state.bgBlur,
            customUri = state.customBackgroundUri,
            isXiaomei = colors.isXiaomei,
            modifier = if (glass) Modifier.junBackdropSource(bgBackdrop) else Modifier
        )

        CompositionLocalProvider(LocalJunBackdrops provides pageBackdrops) {
            // 卡片模式：整个 App 只剩一叠播放卡片，没有歌单页也没有上栏。
            // 不是"在 Pager 里加一页"，而是彻底替掉 Pager——两种结构的手势
            // （横滑翻页 / 横滑翻卡）会互相抢，同时存在必然打架。
            //
            // 用 Crossfade 包住切换：if/else 直接换树，两种结构的首帧
            // （玻璃背板重录、Pager 重建）在同一帧里裸奔，看着就是闪。
            Crossfade(
                targetState = state.layout,
                // 慢一点 + EaseInOut：180ms 直切快得像闪屏，420ms 缓入缓出
                // 才是"柔和过渡"的节奏。再长会拖泥带水——切布局是低频操作，
                // 用户愿意多等半秒看清楚，但不愿意等一秒。
                animationSpec = if (JunMotion.jelly) JunMotion.appear() else tween(420, easing = androidx.compose.animation.core.EaseInOut),
                label = "layoutSwitch"
            ) { layout ->
            if (layout.isDeck) {
                SongDeck(
                    state = state,
                    colors = colors,
                    livePosition = viewModel::livePositionMs,
                    modifier = if (glass) Modifier.junBackdropSource(pageBackdrop) else Modifier,
                    onToggle = onToggle,
                    onPrev = onPrev,
                    onNext = onNext,
                    onSeek = onSeek,
                    onSelect = onSelect,
                    onToggleLike = onToggleLike,
                    onCyclePlayMode = onCyclePlayMode,
                    onToggleVisualizer = { viewModel.setVisualizerEnabled(!state.visualizerEnabled) },
                    onOpenEqualizer = { showEqualizer = true },
                    onOpenSleepTimer = { showSleepTimer = true },
                    onOpenReport = { viewModel.toggleReport(true) },
                    onOpenSettings = { onToggleSettings(true) },
                    onCycleLayout = { viewModel.setLayout(state.layout.next) },
                    onOpenPlaylists = { showPlaylists = true; viewModel.auditLibraryQuietly() },
                    onOpenQueue = { showQueue = true },
                    onReload = { viewModel.refreshHome() },
                    onGoHome = { viewModel.goHome() },
                    onOpenSearch = { showSearchScreen = true },
                    onOpenMy = { showMyDrawer = true },
                    onDownload = { state.currentSong?.let { tryDownload(it) } },
                    showDownload = appFlags.download,
                    onSimiSongs = { viewModel.openSimiSongs() },
                    onRetryLoad = { viewModel.retryLoading() },
                    onOpenComments = if (appFlags.comments) {
                        { state.currentSong?.let { viewModel.openComments(it) } }
                    } else null,
                    commentCount = state.currentCommentCount,
                    eqActive = eqState.active,
                    lyricStage = state.lyricStage,
                    showLyricTranslation = state.lyricTranslation,
                    showLyricRomanization = state.lyricRomanization,
                    onCycleLyricStage = { viewModel.setLyricStage(LyricStageCatalog.next(state.lyricStage)) }
                )
            } else {
            HorizontalPager(
                state = pagerState,
                // 6.7：自定义中心里关掉「模式探索 / 气氛模式」后，停在音乐库页时
                // 整页水平手势禁用 —— 右滑什么也划不动（用户明确要的「划不动」）。
                // 已经滑到别的页时仍放行，否则会把自己锁死在那一页回不来。
                userScrollEnabled = CustomConfig.swipeExplore.value || pagerState.currentPage != 1,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (glass) Modifier.junBackdropSource(pageBackdrop) else Modifier)
            ) { page ->
                when (page) {
                    // 6.9.7：分页顺序改为 [模式探索, 音乐库, 播放页]。
                    // 初始落在第 2 页（音乐库），往右滑就露出左边的模式探索页——
                    // 正是用户说的"首页往右边滑会出现模式探索"。
                    0 -> ModeExplorePage(
                        state = state,
                        colors = colors,
                        onToggleLibraryMode = { viewModel.setLibraryMode(!state.libraryMode) },
                        onEnsureModes = { viewModel.ensureModeCards(it) },
                        onOpenMode = { kw, label -> viewModel.openMode(kw, label) },
                        onRefreshModes = {
                            viewModel.refreshModeCards(ModeCatalog.map { it.keyword })
                        },
                        // 右滑过渡：reveal 0→1，与分页手势同帧；从播放页滑回来时也复用同一条曲线
                        reveal = (1f - (pagerState.currentPage + pagerState.currentPageOffsetFraction))
                            .coerceIn(0f, 1f)
                    )

                    1 -> LibraryPage(
                        state = state,
                        colors = colors,
                        listState = libraryListState,
                        onSelect = { index ->
                            // 首页纵向列表永远显示全部音乐（homeSongs），
                            // 点歌即以这份列表为队列从该首开始播（playFromHome）。
                            // 「我喜欢」/歌单的播放走播放列表弹层（QueueSheet）的对应标签页。
                            viewModel.playFromHome(index)
                            openPlayerIfAuto()
                        },
                        onPlaySong = { song ->
                            viewModel.playSong(song)
                            openPlayerIfAuto()
                        },
                        onSearch = onSearch,
                        onSettings = { onToggleSettings(true) },
                        onReport = { viewModel.toggleReport(true) },
                        onReload = { viewModel.refreshHome() },
                        onCycleLayout = { viewModel.setLayout(state.layout.next) },
onOpenPlaylists = { showPlaylists = true; viewModel.auditLibraryQuietly() },
                        onOpenQueue = { showQueue = true },
                        onOpenMy = { showMyDrawer = true },
                        onOpenRank = { showRank = true },
                        onOpenSearchPage = { showSearchScreen = true },
                        onOpenHomeCustomize = { showHomeCustomize = true },
                        homeOrder = homeOrder,
                        homeHidden = homeHidden,
                         onSortOrder = viewModel::setSortOrder,
                         onSortDirection = viewModel::setSortDirection,
                         // 「全选」的范围 = 首页可见列表（homeSongs），不再是播放队列
                         onAddToPlaylist = { song -> pendingActionList = "全部音乐" to state.homeSongs; pendingActionSong = song },
                        onToggleLike = { song -> viewModel.toggleLike(song.id) },
                        onOpenArtist = { name -> viewModel.openArtistByName(name) },
                        onOpenArtistById = { id, name, pic -> viewModel.openArtist(id, name, pic) },
                        onGoHome = { viewModel.goHome() },
                        onPlayAll = {
                            // 首页播放全部：以 homeSongs 为队列播放
                            viewModel.playList(state.homeSongs, 0, "全部音乐")
                        },
                        onNeteaseToplist = { viewModel.openNeteaseToplist() },
                        onLoadNeteaseCharts = { viewModel.loadNeteaseHomeData() },
                        onLoadMoreNeteaseRecs = { viewModel.loadMoreRecommendPlaylists() },
                        // R13 放行：首页「更多」在未登录时也要能直接打开完整歌单列表，
                        // 不再一进列表就被拦去登录（登录判断只剩「我的歌单/每日推荐/我喜欢」这些账号动作）。
                        // 注意：这块 UI（NeteaseHomeFeed）的「推荐歌单」和「音乐榜单」两个表头目前共用同一个
                        // onOpen，等 MusicComponents 新增 onOpenToplist 参数落地后，榜单那颗「更多」
                        // 应改走 openNeteaseToplist()（排行榜弹层本来就免登录）。
                        onNeteasePlaylists = { openPlaylistSquare(false) },
                        // 首页「我的音乐」点卡片：切到对应视图并直接打开播放列表面板，
                        // QueueSheet 会读 activePlaylistId / onlyLiked 自动落在目标列表。
                        onOpenLocalLiked = { viewModel.setOnlyLiked(true); showQueue = true },
                        onOpenLocalPlaylist = { id -> viewModel.selectPlaylist(id); showQueue = true },
                        onNeteaseDaily = { viewModel.openNeteaseDaily() },
                        onNeteaseLiked = { viewModel.openNeteaseLiked() },
                        onNeteaseSelectPlaylist = { id, name -> viewModel.selectNeteasePlaylist(id, name) },
                        onOpenNeteaseToplist = { id, name -> viewModel.selectNeteaseToplist(id, name) },
                        onNeteaseSelectToplist = { id, name -> viewModel.selectNeteaseToplist(id, name) },
                        onPlayRecent = { rp -> viewModel.playRecentPlayback(rp) },
                        onPlayHomeGuess = { viewModel.playHomeGuess() },
                        onRefreshHomeGuess = { viewModel.refreshHomeGuess() },
                        onPlayHomeNewSong = { song -> viewModel.playHomeNewSong(song) },
                        onPlayChannel = { _, songs -> viewModel.playList(songs, 0, "风格电台") },
                        onOpenNeteaseAlbum = { id, name -> viewModel.openAlbumSongs(id, name, "") },
                        onLoadHomeFeed = { viewModel.loadHomeFeed() },
                        scrollerDragging = scrollerDragging,
                        showCount = showCountLabel,
                        select = listSelect,
                        flags = appFlags,
                        banners = appBanners,
                        pagerState = pagerState
                    )

                    2 -> {
                        // 6.9.11 下拉丝滑收起：从播放页顶部往下拖，整页跟手位移 + 缩放 + 淡出，
                        // 松手超过 22% 弹回音乐库，否则丝滑回弹。手势只接管播放页顶部 34% 区域，
                        // 歌词滚动、点击控制都在下方，互不打架；横向拖动仍让给 Pager 翻页。
                        val pull = remember { Animatable(0f) }
                        val pullScope = rememberCoroutineScope()
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val pv = pull.value
                                    translationY = pv * size.height * 0.62f
                                    val sc = 1f - 0.07f * pv
                                    scaleX = sc; scaleY = sc
                                    alpha = 1f - 0.5f * pv
                                }
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        if (down.position.y > size.height * 0.34f) return@awaitEachGesture
                                        var dragging = false
                                        var acc = 0f
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val c = event.changes.firstOrNull() ?: break
                                            val dy = c.positionChange().y
                                            if (!dragging && c.pressed) {
                                                // 首次显著位移判定方向：向下才接管；横向/向上交还 Pager
                                                if (dy > 10f) dragging = true
                                                else if (kotlin.math.abs(dy) > 10f) return@awaitEachGesture
                                            }
                                            if (dragging && c.pressed) {
                                                acc += dy
                                                c.consume()
                                                pullScope.launch {
                                                    pull.snapTo((acc / size.height).coerceIn(0f, 1f))
                                                }
                                            }
                                            if (!c.pressed) break
                                        }
                                        pullScope.launch {
                                            if (dragging && acc > size.height * 0.22f) goToPagerPage(1)
                                            pull.animateTo(
                                                0f,
                                                spring(dampingRatio = 0.88f, stiffness = 420f)
                                            )
                                        }
                                    }
                                }
                        ) {
                            PlayerPage(
                                state = state,
                                colors = colors,
                                viewModel = viewModel,
                                onToggle = onToggle,
                                onPrev = onPrev,
                                onNext = onNext,
                                onSeek = onSeek,
                                onToggleLike = onToggleLike,
                                onCyclePlayMode = onCyclePlayMode,
                                onOpenEqualizer = { showEqualizer = true },
                                onOpenSleepTimer = { showSleepTimer = true },
                                onOpenQueue = { showQueue = true },
                                onDownload = { tryDownload(it) },
                                eqActive = eqState.active,
                                flags = appFlags,
                                pagerState = pagerState
                            )
                        }
                    }
                }
            }
            }
            } // Crossfade
        }

        // ===== 快速滚动条：挂在根层级、Pager 之上，钉死屏幕右缘且不被页面遮挡 =====
        // 仅库页显示：翻到播放页或滑动中时隐藏。卡片模式下没有歌单，整条不存在。
val scrollerVisible by remember {
             derivedStateOf { pagerState.currentPage == 1 && !pagerState.isScrollInProgress }
         }
         val locateScope = rememberCoroutineScope()
         // 行程
         // 行程：原先居中占屏高 55%（22.5%~77.5%），拇指要伸到很靠上的位置才够。
        // 顶部再收 20%、底部收 10%（按原行程比例），落到 33.5%~72%，
        // 行程缩到 38.5% 屏高且整体下移，单手拖动更省力。
        if (!state.layout.isDeck) {
         Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
        ) {
            Spacer(Modifier.weight(0.335f))
            Box(Modifier.weight(0.385f)) {
                // 首页顶部那几个整块 item（运营横幅 + 网易云信息流）占据 LazyColumn 的前几格，
                // 因此队列索引需要减去该偏移。条件必须与 LibraryPage 里各 header item 的
                // 渲染条件逐字一致——关掉推荐后列表少一个 item 而这里仍按有算，
                // 侧边条整体错位一格（气泡显示上一首、定位跳到下一首）。
                // R9 把「排行榜 + 推荐歌单 + 我的歌单」并成一格 NeteaseHomeFeed，
                // 再按以前两区算就多扣一格。
                val showHomeFeedSection = appFlags.netease && state.searchQuery.isBlank()
                val showBannerSection = appBanners.isNotEmpty() && state.searchQuery.isBlank()
                val headerOffset = (if (showHomeFeedSection) 1 else 0) +
                    (if (showBannerSection) 1 else 0)
                FastScroller(
                    listState = libraryListState,
                    colors = colors,
                    topPadding = 0.dp,
                    bottomPadding = 0.dp,
visible = scrollerVisible,
                     onClick = {
                         Haptics.tap()
                         // 同样不能拿 currentIndex 当列表下标：按身份键在 homeSongs 里找，
                         // 否则从榜单切歌后会定位到不相干的一行。
                         val curKey = state.currentSong?.identityKey
                         val idx = if (curKey == null) -1
                         else state.homeSongs.indexOfFirst { it.identityKey == curKey }
                         if (idx >= 0) {
                             locateScope.launch { libraryListState.animateScrollToItem(idx + headerOffset) }
                         }
                     },
                     labelForIndex = { i ->
                        state.homeSongs.getOrNull(i - headerOffset)?.title
                    },
                    onDraggingChange = { scrollerDragging.value = it }
                )
            }
            Spacer(Modifier.weight(0.28f))
        }
        }

        // 设置面板 / 均衡器：作为最上层玻璃，折射「背景 + 页面」两层背板
        CompositionLocalProvider(LocalJunBackdrops provides sheetBackdrops) {
            AnimatedVisibility(
                visible = state.showSettings,
                // 这一轮起弹层入场**永远带回弹**：以前果冻关掉就是纯淡入、全程没有一次过冲
                enter = JunMotion.overlayEnter(),
                exit = fadeOut(tween(180)),
                modifier = Modifier.fillMaxSize()
            ) {
                // 拖把手下滑关闭：面板根随手指位移，把手命中在 SettingsPanel 内部
                val settingsDrag = rememberSheetDragState()
                JunSheet(
                    colors = colors,
                    onDismiss = { onToggleSettings(false) },
                    drag = settingsDrag,
                    panelModifier = Modifier.heightIn(max = 720.dp)
                ) {
                    SettingsPanel(
                        state = state,
                        colors = colors,
                        viewModel = viewModel,
                        onPickBackground = { bgPickerLauncher.launch(arrayOf("image/*")) },
                        onPickCover = { coverPickerLauncher.launch(arrayOf("image/*")) },
                        onPickVideo = { videoPickerLauncher.launch(arrayOf("video/*")) },
                        sheetDrag = settingsDrag,
                        onCheckUpdate = checkUpdateNow,
                        onDismiss = { onToggleSettings(false) },
                        onPickDownloadPath = { downloadDirPickerLauncher.launch(null) },
                        onOpenDownloadManager = { showDownloadManager = true },
                        onReplayGuide = {
                            // 即时重看：收起设置、回到库页首页再开绘本；
                            // 聚光灯要照的上栏和「我的」入口都在库页，不在播放页。
                            onToggleSettings(false)
                            showSearchScreen = false
                            showQueue = false
                            showMyDrawer = false
                            showTodoBoard = false
                            viewModel.goHome()
                            if (!state.layout.isDeck) {
                                backScope.launch { pagerState.scrollToPage(1) }
                            }
                            showReplayGuide = true
                        },
                        onFeatureTour = {
                            // 重看本次新功能导览：同样先回首页，再让聚光灯从歌单按钮开始指
                            onToggleSettings(false)
                            showSearchScreen = false
                            showQueue = false
                            showMyDrawer = false
                            showTodoBoard = false
                            viewModel.goHome()
                            if (!state.layout.isDeck) {
                                backScope.launch { pagerState.scrollToPage(1) }
                            }
                            showFeatureTour = true
                        },
                        // R13：设置里第三枚「查看本次更新公告」——复用上面那个唯一的公告渲染位
                        // （WhatsNewDialog，正文只在本轮的公告对象里，当前是 ui/WhatsNewR19.kt），
                        // 不新写第二个宿主，也不重置已读标记：
                        // 重看时再写一次 markWhatsNewSeen 是无害的幂等。
                        onWhatsNew = { showWhatsNew = true },
                        onOpenLockScreenStyle = {
                            // 先收设置再开样式页：两层玻璃叠在一起会互相吃折射，
                            // 视觉上像糊了一层灰。
                            onToggleSettings(false)
                            showLockLyricStyle = true
                        }
                    )
                }
            }

            // 锁屏歌词样式页
            AnimatedVisibility(
                visible = showLockLyricStyle,
                enter = JunMotion.overlayEnter(),
                exit = fadeOut(tween(180)),
                // zIndex 必须高于其它浮层：这层原来没设，和歌单/设置同级时
                // 会按声明顺序被后声明的层盖住，用户看到的是"点了锁屏样式却弹出别的东西"。
                modifier = Modifier.fillMaxSize().zIndex(16f)
            ) {
                LockScreenLyricSheet(
                    colors = colors,
                    onDismiss = { showLockLyricStyle = false },
                    bottomInset = if (showMiniPlayer) 96.dp else 24.dp
                )
            }

            // 歌单管理弹层
            AnimatedVisibility(
                visible = showPlaylists,
                // 这一轮起弹层入场**永远带回弹**：以前果冻关掉就是纯淡入、全程没有一次过冲
                enter = JunMotion.overlayEnter(),
                exit = fadeOut(tween(180)),
                modifier = Modifier.fillMaxSize()
            ) {
                PlaylistSheet(
                    state = state,
                    colors = colors,
                    onDismiss = { showPlaylists = false },
                    onSelect = {
                        viewModel.selectPlaylist(it)
                        showPlaylists = false
                        showQueue = true
                    },
                    onCreate = { name -> viewModel.createPlaylist(name) },
                    onRename = { id, name -> viewModel.renamePlaylist(id, name) },
                    onDelete = { viewModel.deletePlaylist(it) },
                    // 6.4：进度 + 结果统计一起透传，导入面板据此画进度条和三行结果
                    onImport = { link, onProgress, onResult ->
                        viewModel.importNeteasePlaylist(link, onProgress, onResult)
                    },
                    onNeteaseDaily = { showPlaylists = false; viewModel.openNeteaseDaily("localPlaylists") },
                    onNeteasePlaylists = { showPlaylists = false; openPlaylistSquare(true) },
                    onNeteaseLiked = { showPlaylists = false; viewModel.openNeteaseLiked("localPlaylists") },
                    // 本地「我喜欢的音乐」：不登录也能听，直接开播红心列表
                    onOpenLikedLocal = {
                        showPlaylists = false
                        val liked = state.likedSongs
                        if (liked.isEmpty()) {
                            shareNotice = "还没有 ♡ 过的歌，在播放页点一下爱心就会收进这里"
                            shareNoticeTick++
                        } else {
                            viewModel.playList(liked, 0, "我喜欢的音乐")
                        }
                    },
                    bottomInset = if (showMiniPlayer) 96.dp else 24.dp,
                    openImport = featureTourStep == FeatureTourSteps.IMPORT_INPUT,
                    revealImportEntry = FeatureTourSteps.onPlaylistSheet(featureTourStep),
                    // 6.5：歌单碎到"该整理了"的程度时，在「整理」旁边点一颗小圆点。
                    // 判据故意比体检报告严一点（碎片 ≥5 个），否则三个歌单里有一个是空的
                    // 也会点灯，点灯太廉价就没人当回事了。
                    needsTidyHint = (state.libraryHealth?.fragments?.size ?: 0) >= 5 ||
                        (state.libraryHealth?.duplicates?.isNotEmpty() == true),
                    // ===== R13：本地分享 / 导入（Jun 口令），全部离线，不经过我们的服务器 =====
                    // 卡片右上角那颗分享 = 分享这个自定义歌单；下面三颗 = 我喜欢 / 最近播放 / 粘贴导入。
                    onSharePlaylist = { pl ->
                        val songs = viewModel.songsOfPlaylist(pl.id)
                        if (songs.isEmpty()) {
                            shareNotice = "「${pl.name}」还是空的，先加几首歌再分享"
                            shareNoticeTick++
                        } else {
                            junImportOnly = false
                            shareTarget = pl.name to songs
                            showPlaylistShare = true
                        }
                    },
                    onShareLiked = {
                        val songs = state.likedSongs
                        if (songs.isEmpty()) {
                            shareNotice = "还没有喜欢的歌，点♡之后再分享"
                            shareNoticeTick++
                        } else {
                            junImportOnly = false
                            shareTarget = "我喜欢" to songs
                            showPlaylistShare = true
                        }
                    },
                    onShareRecent = {
                        // 用户原话是"我听了很久的歌想分享给别人"——最近播放会话正是那一份
                        val last = state.recentPlaybacks.firstOrNull()
                        if (last == null || last.songs.isEmpty()) {
                            shareNotice = "最近没有播放记录"
                            shareNoticeTick++
                        } else {
                            junImportOnly = false
                            shareTarget = last.title to last.songs
                            showPlaylistShare = true
                        }
                    },
                    onImportJunCode = { preset ->
                        junImportOnly = true
                        shareTarget = null
                        shareInitialPaste = preset
                        showPlaylistShare = true
                    },
                    onOpenTidy = {
                        Haptics.tap()
                        showLibraryTidy = true
                    }
                )
            }

            // ===== 6.3：听歌排行榜（独立整页）=====
            // 全屏压在页面之上、底部小播放器之下（zIndex 14f），返回键已接入 hasOverlay。
            // 实时刷新在 RankScreen 内部（20 秒），这里只负责开合与传 deviceKey。
            AnimatedVisibility(
                visible = showRank,
                enter = JunMotion.overlayEnter(),
                exit = fadeOut(tween(180)),
                modifier = Modifier.zIndex(14f)
            ) {
                RankScreen(
                    colors = colors,
                    deviceKey = Telemetry.deviceId(appContext),
                    onDismiss = { showRank = false },
                    onOpenMyTier = {
                        showRank = false
                        showMyDrawer = true
                    }
                )
            }

            // ===== 6.4：播放库整理 =====
            // 从「本地歌单」旁边的「整理」进入。它盖在歌单层之上（zIndex 13.5f），
            // 关掉之后回到歌单层——用户的动作是"整理完接着挑一个歌单听"。
            AnimatedVisibility(
                visible = showLibraryTidy,
                enter = JunMotion.overlayEnter(),
                exit = fadeOut(tween(180)),
                modifier = Modifier.zIndex(13.5f)
            ) {
                LibraryTidySheet(
                    playlists = state.playlists,
                    health = state.libraryHealth,
                    auditing = state.libraryAuditing,
                    colors = colors,
                    onDismiss = { showLibraryTidy = false },
                    onAudit = { viewModel.auditLibrary() },
                    onTidy = { into ->
                        viewModel.applyLibraryTidy(into)
                        showLibraryTidy = false
                    }
                )
            }

            // ===== R13：歌单分享 / 导入弹层（Jun 口令）=====
            // zIndex 14f：它可能从长按菜单（13f）或歌单层里点进来，必须压过它们；
            // 返回键与小播放器隐藏已在前面接好（showPlaylistShare 进了 hasOverlay / otherOverlayOpen）。
            AnimatedVisibility(
                visible = showPlaylistShare,
                enter = JunMotion.overlayEnter(),
                exit = fadeOut(tween(180)),
                modifier = Modifier.zIndex(14f)
            ) {
                // payload 为 null 就是"这屏只做导入"——从「导入 Jun 口令」进来时用，
                // 顶部只留标题，导出那一整块不画（也不会白算一次压缩编码）。
                //
                // R14：**必须 remember 住**。payloadOf() 里带 System.currentTimeMillis()，
                // 每次组合都返回一个"内容相同但实例不同"的新对象；弹层里
                // `LaunchedEffect(payload)` 与 `remember(payload)` 都按实例比对，于是每帧重来一次：
                // encoded 被清成 null → 那块先退回「正在生成口令…」再变回口令，导入进度每 tick
                // 触发一次组合就再闪一遍。这就是用户说的"分享那个界面非常非常的闪"。
                val target = shareTarget
                val presetPaste = shareInitialPaste
                val payload = remember(target, junImportOnly) {
                    if (junImportOnly || target == null) null
                    else PlaylistShare.payloadOf(target.first, target.second)
                }
                PlaylistShareSheet(
                    payload = payload,
                    initialPaste = presetPaste,
                    colors = colors,
                    onDismiss = {
                        showPlaylistShare = false
                        shareTarget = null
                        shareInitialPaste = ""
                        junImportOnly = false
                    },
                    // 回执走全站那一套轻提示栈（见下方 toastItems）
                    onNotice = { msg ->
                        if (msg.isNotBlank()) {
                            shareNotice = msg
                            shareNoticeTick++
                        }
                    },
                    onSystemShare = { code, text ->
                        // 纯文本，不需要 FileProvider：微信 / QQ / 短信都能接。
                        // 起分享面板失败（比如某些 EMUI 上没有可接 text/plain 的组件）就退回复制，
                        // 绝不因为一次 startActivity 抛异常把弹层整个弄崩。
                        val ok = runCatching {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_SUBJECT, "Jun Music 歌单口令")
                                .putExtra(android.content.Intent.EXTRA_TEXT, text)
                            mainCtx.startActivity(android.content.Intent.createChooser(send, "分享歌单口令"))
                        }.isSuccess
                        if (!ok) {
                            val cm = mainCtx.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(
                                android.content.ClipData.newPlainText("Jun Music 歌单口令", code)
                            )
                            shareNotice = "没找到分享目标，口令已经帮你复制"
                            shareNoticeTick++
                        }
                    },
                    onImport = { code, progress ->
                        viewModel.importJunShareCode(code, progress)
                    }
                )
            }

            // 添加到歌单弹层（长按歌曲触发）
            // 歌曲操作菜单：长按弹出（添加到歌单 / 下一首播放 / 下载）
            AnimatedVisibility(
                visible = pendingActionSong != null,
                // 这一轮起弹层入场**永远带回弹**：以前果冻关掉就是纯淡入、全程没有一次过冲
                enter = JunMotion.overlayEnter(),
                exit = fadeOut(tween(180)),
                modifier = Modifier.zIndex(13f)
            ) {
                val song = pendingActionSong
                if (song != null) {
                    SongActionMenu(
                        song = song,
                        liked = song.id in state.likedSongIds,
                        colors = colors,
                        onDismiss = { pendingActionSong = null },
                        onAddToPlaylist = {
                            pendingActionSong = null
                            pendingAddSong = song
                        },
                        onToggleLike = {
                            viewModel.toggleLike(song.id)
                            pendingActionSong = null
                        },
                        onDownload = {
                            tryDownload(song)
                            pendingActionSong = null
                        },
                        onComments = {
                            pendingActionSong = null
                            viewModel.openComments(song)
                        },
                        onPlayNext = {
                            viewModel.enqueueNext(song)
                            pendingActionSong = null
                        },
                        // 顶排「播放」：直接播这一首，并且展开播放页。这里不看 autoOpenPlayer ——
                        // 那个开关管的是"在列表里连点试歌"，而菜单里的「播放」是一次明确意图，
                        // 不跳过去反而像没响应。卡片/堆叠布局没有 Pager，沿用同一套 isDeck 判断。
                        onPlayNow = {
                            pendingActionSong = null
                            viewModel.playSong(song)
                            if (!state.layout.isDeck) {
                                goToPagerPage(2)
                            }
                        },
                        // R13 预留：分享正在看的这一份列表（首页/搜索/榜单/歌单都可能长按到这里）。
                        // 开关关着时一律传 null → 菜单里整行不出现，不会有"点了没反应"的假按钮；
                        // ShareSheet 落地后只在这次长按背后确实有一份列表（pendingActionList 非空）时才出现。
                        onSharePlaylist = if (PLAYLIST_SHARE_READY) {
                            val src = pendingActionList
                            if (src != null) {
                                val action: () -> Unit = {
                                    shareTarget = src
                                    showPlaylistShare = true
                                }
                                action
                            } else null
                        } else null,
                        // ⋮ 按下时写进来的落点：菜单就从那颗按钮下面鼓出来
                        popAnchor = SongMoreAnchor.offset,
                        onStartSelectMode = {
                            pendingActionSong = null
                            // 全选范围 = 长按时正在看的那份列表（榜单/歌单/搜索/首页）
                            val src = pendingActionList
                            if (src != null) enterSelectMode(src.first, src.second)
                        }
                    )
                }
            }

            // zIndex 抬高：它既可能从曲库页长按触发，也可能从全屏搜索页长按触发，
            // 而搜索页在组合顺序里更靠后（默认会盖住它），必须显式压过搜索页。
            AnimatedVisibility(
                visible = pendingAddSong != null,
                // 这一轮起弹层入场**永远带回弹**：以前果冻关掉就是纯淡入、全程没有一次过冲
                enter = JunMotion.overlayEnter(),
                exit = fadeOut(tween(180)),
                modifier = Modifier.zIndex(12f)
            ) {
                val song = pendingAddSong
                if (song != null) {
                    AddToPlaylistSheet(
                        song = song,
                        state = state,
                        colors = colors,
                        onDismiss = { pendingAddSong = null },
                        onCreateAndAdd = { name ->
                            val id = viewModel.createPlaylist(name)
                            viewModel.addToPlaylist(id, song.id)
                            pendingAddSong = null
                        },
                        onAdd = { pid -> viewModel.addToPlaylist(pid, song.id); pendingAddSong = null }
                    )
                }
            }

            // 批量添加到歌单
            AnimatedVisibility(
                visible = pendingAddSongs.isNotEmpty(),
                // 这一轮起弹层入场**永远带回弹**：以前果冻关掉就是纯淡入、全程没有一次过冲
                enter = JunMotion.overlayEnter(),
                exit = fadeOut(tween(180)),
                modifier = Modifier.zIndex(13f)
            ) {
                val songs = pendingAddSongs
                if (songs.isNotEmpty()) {
                    AddToPlaylistSheet(
                        song = songs.first(),
                        state = state,
                        colors = colors,
                        onDismiss = { pendingAddSongs = emptyList() },
                        onCreateAndAdd = { name ->
                            val id = viewModel.createPlaylist(name)
                            viewModel.addSongsToMyPlaylist(id, songs)
                            pendingAddSongs = emptyList()
                        },
                        onAdd = { pid ->
                            viewModel.addSongsToMyPlaylist(pid, songs)
                            pendingAddSongs = emptyList()
                        }
                    )
                }
            }

            // 6.6.9：整单收藏（歌单 / 榜单 / 搜索歌单 → 我的歌单）
            AnimatedVisibility(
                visible = saveAllTarget != null,
                enter = JunMotion.overlayEnter(),
                exit = fadeOut(tween(180)),
                modifier = Modifier.zIndex(13.5f)
            ) {
                val target = saveAllTarget
                if (target != null) {
                    AddManyToPlaylistSheet(
                        title = target.first,
                        count = target.second.size,
                        state = state,
                        colors = colors,
                        onDismiss = { saveAllTarget = null },
                        onCreateAndAdd = { name ->
                            viewModel.createPlaylistWithSongs(name, target.second)
                            saveAllTarget = null
                        },
                        onAdd = { pid ->
                            viewModel.addSongsToMyPlaylist(pid, target.second)
                            saveAllTarget = null
                        }
                    )
                }
            }

            // 首次下载路径选择弹窗
            AnimatedVisibility(
                visible = showDownloadPathDialog,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(180)),
                modifier = Modifier.zIndex(14f)
            ) {
                DownloadPathDialog(
                    colors = colors,
                    onUseDefault = {
                        viewModel.setDownloadPathConfirmed(true)
                        showDownloadPathDialog = false
                        pendingDownloadSong?.let { viewModel.downloadSong(it) }
                        pendingDownloadSong = null
                    },
                    onPickPath = {
                        showDownloadPathDialog = false
                        downloadDirPickerLauncher.launch(null)
                    },
                    onDismiss = {
                        showDownloadPathDialog = false
                        pendingDownloadSong = null
                    }
                )
            }

            // 均衡器弹层
            AnimatedVisibility(
                visible = showEqualizer,
                // 这一轮起弹层入场**永远带回弹**：以前果冻关掉就是纯淡入、全程没有一次过冲
                enter = JunMotion.overlayEnter(),
                exit = fadeOut(tween(180))
            ) {
                EqualizerSheet(
                    state = eqState,
                    fxPresetIndex = state.fxPreset,
                    onSelectFxPreset = { viewModel.setFxPreset(it) },
                    onDismiss = { showEqualizer = false },
                    onSelectBoost = { EqualizerController.selectBoost(it) },
                    onToggleLoudness = { EqualizerController.setLoudness(it) },
                     onLoudnessStrengthChange = { EqualizerController.setLoudnessStrength(it) },
                    virtualBass = state.virtualBass,
                    virtualBassStrength = state.virtualBassStrength,
                    onVirtualBassChange = viewModel::setVirtualBass,
                    onVirtualBassStrengthChange = viewModel::setVirtualBassStrength,
                    rangeEnhancer = state.rangeEnhancer,
                    rangeEnhancerStrength = state.rangeEnhancerStrength,
                    onRangeEnhancerChange = viewModel::setRangeEnhancer,
                    onRangeEnhancerStrengthChange = viewModel::setRangeEnhancerStrength,
                    virtualSurround = state.virtualSurround,
                    virtualSurroundStrength = state.virtualSurroundStrength,
                    onVirtualSurroundChange = viewModel::setVirtualSurround,
                    onVirtualSurroundStrengthChange = viewModel::setVirtualSurroundStrength,
                    spatialWide = state.spatialWide,
                    spatialWideStrength = state.spatialWideStrength,
                    onSpatialWideChange = viewModel::setSpatialWide,
                    onSpatialWideStrengthChange = viewModel::setSpatialWideStrength,
                    safeLimiter = state.safeLimiter,
                    safeLimiterStrength = state.safeLimiterStrength,
                    onSafeLimiterChange = viewModel::setSafeLimiter,
                    onSafeLimiterStrengthChange = viewModel::setSafeLimiterStrength,
                    loudnorm = state.loudnorm,
                    loudnormStrength = state.loudnormStrength,
                    onLoudnormChange = viewModel::setLoudnorm,
                    onLoudnormStrengthChange = viewModel::setLoudnormStrength,
                    onSetEditMode = { EqualizerController.setEditMode(it) },
                    onBandLevel = { band, level -> EqualizerController.setBandLevel(band, level) },
                    onMoveAnchor = { index, freq, gain -> EqualizerController.moveAnchor(index, freq, gain) },
                    onCommitAnchors = { EqualizerController.commitAnchors() },
                    onAddAnchor = { freq, gain -> EqualizerController.addAnchor(freq, gain) },
                    onRemoveAnchor = { EqualizerController.removeAnchor(it) },
                    onReset = { EqualizerController.reset() },
                    colors = colors
                )
            }

            // 睡眠定时器弹层
            AnimatedVisibility(
                visible = showSleepTimer,
                // 这一轮起弹层入场**永远带回弹**：以前果冻关掉就是纯淡入、全程没有一次过冲
                enter = JunMotion.overlayEnter(),
                exit = fadeOut(tween(180))
            ) {
                SleepTimerSheet(
                    currentMinutes = if (state.sleepTimerMs > 0) (state.sleepTimerMs / 60000).toInt() else 0,
                    stopAfterSong = state.sleepStopAfterSong,
                    onSetStopAfterSong = { enable -> viewModel.setSleepStopAfterSong(enable) },
                    onDismiss = { showSleepTimer = false },
                    onSetMinutes = { minutes ->
                        if (minutes > 0) viewModel.setSleepTimer(minutes)
                        else viewModel.cancelSleepTimer()
                    },
                    colors = colors
                )
            }

            // 听歌报告弹层：和设置/均衡器同层，折射「背景 + 页面」两层背板
            AnimatedVisibility(
                visible = state.showReport,
                // 这一轮起弹层入场**永远带回弹**：以前果冻关掉就是纯淡入、全程没有一次过冲
                enter = JunMotion.overlayEnter(),
                exit = fadeOut(tween(180))
            ) {
                ListenReportSheet(
                    report = state.report,
                    onDismiss = { viewModel.toggleReport(false) },
                    onRangeChange = viewModel::setReportRange,
                    onPlaySong = { id ->
                        viewModel.playSongById(id)
                        viewModel.toggleReport(false)
                    },
                    colors = colors
                )
            }

            // 6.7：自定义中心。改成屏幕居中的液态玻璃弹窗，外层底部固定留 96dp，
            // 最底下那条小播放器始终露在外面、还能边调边看（用户原话「不遮挡」）。
            if (showHomeCustomize || CustomConfig.showDialog.value) {
                CustomCenterDialog(
                    state = state,
                    viewModel = viewModel,
                    colors = colors,
                    order = homeOrder,
                    hidden = homeHidden,
                    onHomeChange = { o, h ->
                        homeOrder = o
                        homeHidden = h
                        HomeSectionConfig.save(mainCtx, o, h)
                    },
                    onPickBackground = { bgPickerLauncher.launch(arrayOf("image/*")) },
                    onDismiss = {
                        showHomeCustomize = false
                        CustomConfig.showDialog.value = false
                    }
                )
            }

            // 全屏搜索页的过渡。
            //
            // 以前这里写的是 fadeIn(tween(220)) + slideInVertically(tween(280)) { it / 4 }：
            // ① 两条定长曲线一先一后结束，淡入先停、位移还在走，读起来就是"一顿一顿"；
            // ② 位移量是屏高四分之一，欠阻尼一点就会在底部让出一条缝，闪一下更明显；
            // ③ 真正的卡顿来自内容——SearchScreen 首帧同时起「猜你喜欢」「榜单预览」
            //    和云端历史三次状态写入，全落在动画那 280ms 里（见 DiscoverOverlays 里的延时）。
            // 现在：淡入与缩放共用同一条弹簧（appear()），整页像被推上来一点点、
            // 冲过一点再落回；不再有纵向位移，所以不会出现底部让缝。
            AnimatedVisibility(
                visible = showSearchScreen,
                enter = JunMotion.overlayEnter(initialScale = 0.955f),
                // 出场保持定长：关闭要有"确定性"，往回弹会显得拖泥带水
                exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.97f, animationSpec = tween(180))
            ) {
                SearchScreen(
                    state = state,
                    colors = colors,
                    // 底部小播放器浮在搜索页之上，把它的高度让给列表，避免最后几首被压住。
                    // 搜索页打开时横排/堆叠/唱片墙也显示小播放器，所以同样要让位。
                    bottomInset = if (state.currentSong != null) 88.dp else 16.dp,
                    onDismiss = {
                        // 关闭搜索页时清空查询词，避免搜索结果污染曲库列表
                        onSearch("")
                        showSearchScreen = false
                    },
                    onQuery = onSearch,
                    onPlaySong = { song ->
                        // 点搜索结果：把这首歌加进「当前所在列表」并直接在底部小播放器开播，
                        // 不跳到播放页、也不排入"下一首"。
                        // 不自动关闭搜索页：用户可以连续点歌试听。
                        viewModel.playFromSearch(song)
                    },
                    onPlayAll = {
                        if (state.searchResults.isNotEmpty()) {
                            viewModel.playList(state.searchResults, title = "搜索结果")
                        }
                        onSearch("")
                        showSearchScreen = false
                    },
                    onToggleLike = { song -> viewModel.toggleLike(song.id) },
                    onAddToPlaylist = { song -> pendingActionList = "搜索结果" to state.searchResults; pendingActionSong = song },
                    select = listSelect,
                    onSetTab = { viewModel.setSearchTab(it) },
                    onSourceSelect = { viewModel.setSearchSource(it) },
                    // 6.3.2：邻页静默预加载（不切 tab），用户横扫时不会再看到"还没搜过"
                    onPreloadTab = { viewModel.preloadSearchTab(it) },
                    // 6.9.3：QQ 源的歌手/专辑结果没有网易云 id —— 点了按名字回网易云
                    // 定位再打开；网易云源保持原直开逻辑。
                    onOpenArtist =
                        if (state.searchSource == "qq") {
                            { _, name, _ -> viewModel.openArtistByName(name) }
                        } else {
                            { id, name, pic -> viewModel.openArtist(id, name, pic) }
                        },
                    onOpenAlbum =
                        if (state.searchSource == "qq") {
                            { _, name, artist -> viewModel.openAlbumByName(name, artist) }
                        } else {
                            { id, name, artist -> viewModel.openAlbumSongs(id, name, artist) }
                        },
                    onOpenPlaylist = { id, name -> viewModel.openSearchedPlaylist(id, name) },
                    onOpenToplist = { id, name -> viewModel.selectNeteaseToplist(id, name) },
                    onPlayToplist = { id, title ->
                        // 6.0：整榜取回来再播（以前只把卡片预览那几首塞进队列）
                        viewModel.playToplistFull(id, title)
                        showSearchScreen = false
                    },
                    onRefreshGuess = { viewModel.shuffleSearchGuess() },
                    onEnsureGuess = { viewModel.ensureSearchGuess() },
                    onLoadToplistPreviews = { force -> viewModel.loadToplistPreviews(force) },
                    onLoadMore = { viewModel.loadMoreSearch() }
                )
            }

            // 「我的」侧滑抽屉（在线版新增）
            if (showMyDrawer) {
                MyDrawer(
                    state = state,
                    colors = colors,
                    onDismiss = { showMyDrawer = false },
                    onLiked = {
                        viewModel.selectPlaylist(null)
                        viewModel.clearFolders()
                        viewModel.setOnlyLiked(true)
                        showMyDrawer = false
                        showQueue = true
                    },
                    onPlaylists = {
                        showMyDrawer = false
                        showPlaylists = true
                        // 6.5：静默算一次体检，供「整理」旁边那颗小圆点判断要不要亮
                        viewModel.auditLibraryQuietly()
                    },
                    onSelectLayout = { layout -> viewModel.setLayout(layout) },
                    onDownload = { showMyDrawer = false; showDownloadManager = true },
                    onAccount = { showMyDrawer = false; showAccount = true },
                    onFeedback = { showMyDrawer = false; showFeedback = true },
                    onRank = { showMyDrawer = false; showRank = true },
                    onProfile = {
                        showMyDrawer = false
                        showProfile = true
                        viewModel.syncListenForProfile()
                    },
                    onNeteaseLogin = { showMyDrawer = false; viewModel.openNeteaseLogin() },
                    onReport = { showMyDrawer = false; viewModel.toggleReport(true) },
                    onSettings = { showMyDrawer = false; onToggleSettings(true) },
                    onNeteaseDaily = { showMyDrawer = false; viewModel.openNeteaseDaily() },
                    // R13：「我的」抽屉里这一行点的是完整歌单列表，未登录也直接放行（关掉回到抽屉）
                    onNeteasePlaylists = { showMyDrawer = false; openPlaylistSquare(false) },
                    onNeteaseToplist = { showMyDrawer = false; viewModel.openNeteaseToplist() },
                    onNeteaseLiked = { showMyDrawer = false; viewModel.openNeteaseLiked() },
                    // 未登录时这个名字显示的是本机昵称，写回也必须写本机昵称（setDisplayName 负责分流），
                    // 否则"改完没反应"：账号表被写了、界面读的还是 localNickname。
                    onEditName = { name -> viewModel.setDisplayName(name) },
                    onEditAvatar = { uri -> viewModel.setAvatarFromUri(uri) },
                    onChangePassword = { showMyDrawer = false; showChangePassword = true },
                    // 6.0：抽屉里的「听歌待办」「新功能导览」两行按用户要求去掉，
                    // 这两个回调也就不再传给 MyDrawer（导览入口留在「设置 → 重看」）。
                    // 抽屉现在默认全部收起，只有导览真要照到抽屉里那一行时才临时展开，
                    // 不然聚光灯找不到锚点，会对着屏幕中间开一个洞。
                    expandAllSections = showReplayGuide ||
                        FeatureTourSteps.onDrawer(featureTourStep),
                    onToggleLibraryMode = { viewModel.setLibraryMode(!state.libraryMode) }
                )
            }

            // 播放列表弹层（在线版新增）
            if (showQueue) {
                QueueSheet(
                    state = state,
                    viewModel = viewModel,
                    colors = colors,
                    onDismiss = { showQueue = false }
                )
            }

            // ===== 首次安装：只留登录（Jun Music / 网易云）→ 进入 → 申请通知权限 =====
            // 放在账号弹层之前：点登录后 AccountSheet / 网易云登录叠在首启之上，关掉即回到这里。
            if (showFirstRun) {
                FirstRunLogin(
                    state = state,
                    colors = colors,
                    onLocalLogin = { showAccount = true },
                    onNeteaseLogin = { viewModel.openNeteaseLogin() },
                    onEnter = {
                        markOnboardingSeen(guideContext)
                        // 首启已经问过登录，之后不再补弹启动登录
                        StartupLoginStore.markDismissed(mainCtx)
                        showStartupLogin = false
                        showFirstRun = false
                        // 收起首启后再弹系统权限框，主界面已经就位，授权完直接就能用
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                mainCtx, android.Manifest.permission.POST_NOTIFICATIONS
                            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }

            // 启动登录引导弹窗（打开软件默认弹出，不强制）
            if (showStartupLogin) {
                StartupLoginPrompt(
                    state = state,
                    colors = colors,
                    onDismiss = { showStartupLogin = false },
                    onLocalLogin = {
                        showStartupLogin = false
                        showMyDrawer = false
                        showAccount = true
                    },
                    onNeteaseLogin = {
                        showStartupLogin = false
                        viewModel.openNeteaseLogin()
                    },
                    onDontAskAgain = {
                        StartupLoginStore.markDismissed(mainCtx)
                        showStartupLogin = false
                    }
                )
            }

            // 账号弹层（在线版新增）
            if (showAccount) {
                AccountSheet(
                    state = state,
                    colors = colors,
                    onDismiss = { showAccount = false; viewModel.clearAccountStatus() },
                    onLogin = { u, p -> viewModel.login(u, p) },
                    onRegister = { u, p, n -> viewModel.register(u, p, n) },
                    onLogout = { viewModel.logout() },
                    onDeleteAccount = { viewModel.deleteAccount() },
                    onNeteaseLogin = { viewModel.openNeteaseLogin() },
                    onNeteaseLogout = { viewModel.logoutNetease() },
                    onPickAvatar = { uri -> viewModel.setAccountAvatarFromUri(uri) },
                    onClaimVip = { viewModel.claimVip() },
                    onOpenProfile = {
                        showAccount = false
                        viewModel.clearAccountStatus()
                        showProfile = true
                        viewModel.syncListenForProfile()
                    }
                )
            }

            // 「我的主页」：网易云风格的个人资料页（头像 / 昵称 / 听歌数据）
            if (showProfile) {
                ProfileSheet(
                    state = state,
                    colors = colors,
                    onDismiss = { showProfile = false },
                    onPickAvatar = { uri -> viewModel.setAvatarFromUri(uri) },
                    // 未登录时这个名字显示的是本机昵称，写回也必须写本机昵称（setDisplayName 负责分流），
                        // 否则"改完没反应"：账号表被写了、界面读的还是 localNickname。
                        onEditName = { name -> viewModel.setDisplayName(name) },
                    onAccount = { showProfile = false; showAccount = true },
                    onOpenReport = { viewModel.toggleReport(true) }
                )
            }

            // 意见反馈：抽屉顶部那颗实色卡片打开的就是它
            if (showFeedback) {
                FeedbackSheet(
                    state = state,
                    colors = colors,
                    // 翻到「我的反馈」才清红点，单纯来写新反馈不算看过回复
                    onSeen = { viewModel.markFeedbackRead() },
                    onDismiss = { showFeedback = false }
                )
            }

            // 修改密码弹层
            if (showChangePassword) {
                ChangePasswordDialog(
                    colors = colors,
                    onDismiss = { showChangePassword = false },
                    onChange = { oldPwd, newPwd, onMsg ->
                        viewModel.changePassword(oldPwd, newPwd) { msg -> onMsg(msg) }
                    }
                )
            }

            // 下载管理弹层
            if (showDownloadManager) {
                // R13：进页先按磁盘真相重扫一次。用户报的是「明明下载了 58 首，显示下载为零」——
                // 下载任务表以前只活在内存里，进程一死就整份丢掉，计数于是恒为 0。
                // VM 冷启动已经扫过一次，这里再扫一次是覆盖「用户在文件管理器里删过歌 / 换了下载目录」
                // 这类会话中途的变化（DownloadManager 内部有 1.2s 去抖，反复进出不会连环扫）。
                LaunchedEffect(Unit) { viewModel.rescanDownloads() }
                DownloadManagementSheet(
                    state = state,
                    colors = colors,
                    onDismiss = { showDownloadManager = false },
                    onDelete = { id -> viewModel.deleteDownload(id) },
                    onDeleteBatch = { ids -> viewModel.deleteDownloads(ids) },
                    onRetry = { id -> viewModel.retryDownload(id) },
                    // 6.9.1：专听已下载——file:// 直读本机文件，飞行模式也能完整听
                    onPlayAll = { tasks, start, shuffle ->
                        showDownloadManager = false
                        viewModel.playDownloaded(tasks, start, shuffle)
                    }
                )
            }

            // 歌曲评论弹层（网易云）：只读无需登录，发表评论需登录
            if (state.commentsTitle.isNotBlank()) {
                CommentsSheet(
                    state = state,
                    colors = colors,
                    onDismiss = { viewModel.closeComments() },
                    onLoadMore = { viewModel.loadMoreComments() },
                    onPost = { text -> viewModel.postComment(text) },
                    onRequireLogin = { viewModel.openNeteaseLogin() },
                    onInputChange = { viewModel.clearCommentNotice() }
                )
            }

            // 网易云账号扫码登录弹层（第二个登录，可选）
            if (state.showNeteaseLogin) {
                NeteaseLoginSheet(
                    state = state,
                    colors = colors,
                    onDismiss = { viewModel.closeNeteaseLogin() },
                    onRefresh = { viewModel.openNeteaseLogin() },
                    onLogout = { viewModel.logoutNetease() },
                    onLoginPhone = { phone, password -> viewModel.loginNeteasePhone(phone, password) },
                    onLoginPhoneCaptcha = { phone, captcha -> viewModel.loginNeteasePhoneCaptcha(phone, captcha) },
                    onLoginEmail = { email, password -> viewModel.loginNeteaseEmail(email, password) },
                    onSendCaptcha = { phone -> viewModel.sendNeteaseCaptcha(phone) },
                    onClearError = { viewModel.clearNeteaseCredentialError() }
                )
            }

            // 歌单选择弹层：我的歌单 + 推荐歌单。
            // 歌单选择弹层（歌单广场）：推荐歌单 + 我的歌单。
            // R13：登录判断从 VM 的「进门拦截」改成「每个动作各自提示」，未登录也能把公开歌单看完，
            // 所以这里重新只认 VM 一个开关——上一版为了绕开拦截加的本地开关已删掉，避免两份状态互相顶。
            if (state.showNeteasePlaylists) {
                NeteasePlaylistSheet(
                    state = state,
                    colors = colors,
                    onDismiss = { viewModel.closeNeteasePlaylists() },
                    onOpenDaily = { viewModel.closeNeteasePlaylists(); viewModel.openNeteaseDaily() },
                    onOpenLiked = { viewModel.closeNeteasePlaylists(); viewModel.openNeteaseLiked() },
                    onSelect = { id, name -> viewModel.selectNeteasePlaylist(id, name) },
                    // R13：登录是广场里的一行动作，不是进门拦截；点了才开扫码弹层。
                    // 登录层是独立开关，压在广场之上；广场留在身后，登录完回来接着逛。
                    onLogin = { viewModel.openNeteaseLogin() },
                    // 6.1.2：「我的歌单」这一节自己说话 + 给一颗明面上的刷新钮。
                    // 只把接口缓存 TTL 调短不够：用户刚在网易云建完歌单切回来，窗口期内还是旧列表，
                    // 看到的仍是「我建的那单不存在」。这颗钮先清缓存再打网络。
                    onRefreshMine = { viewModel.refreshNeteaseMyPlaylists(force = true) },
                    mineFailed = viewModel.neteaseMineFailed.value,
                    mineNotice = viewModel.neteaseMineNotice.value
                )
            }

            // 网易云排行榜弹层（无需登录）：官方榜单列表
            if (state.showNeteaseToplist) {
                NeteaseToplistSheet(
                    state = state,
                    colors = colors,
                    onDismiss = { viewModel.closeNeteaseToplist() },
                    onSelect = { id, name -> viewModel.selectNeteaseToplist(id, name) }
                )
            }

            // 歌手详情页：全屏浮层，返回键/左上角关闭
            state.artistDetail?.let { detail ->
                ArtistDetailScreen(
                    detail = detail,
                    state = state,
                    colors = colors,
                    onDismiss = { viewModel.closeArtist() },
                    onPlayAll = { viewModel.playAllArtist() },
                    onPlaySong = { song -> viewModel.playFromArtist(song) },
                    onToggleLike = { song -> viewModel.toggleLike(song.id) },
                    onAddToPlaylist = { song -> pendingActionList = detail.name.let { "歌手 · $it" } to detail.songs; pendingActionSong = song },
                    // 6.9.5：点歌手页的专辑 → 复用专辑歌曲弹层
                    onOpenAlbum = { albumId, albumName, artist ->
                        viewModel.openAlbumSongs(albumId, albumName, artist)
                    },
                    select = listSelect
                )
            }

            // 网易云曲目弹层（每日推荐 / 某个歌单 / 排行榜 / 相似推荐）
            if (state.neteaseListTitle.isNotBlank()) {
                NeteaseSongListSheet(
                    state = state,
                    colors = colors,
                    onDismiss = { viewModel.closeNeteaseList() },
                    onPlayAll = {
                        if (state.neteaseListSongs.isNotEmpty()) {
                            // 队列表题兜底：R13 起来源统一写「聚合多平台」，不再点名单个平台。
                            // （这里只是标题意外为空时的兜底，正常都用弹层顶部那个榜名/歌单名。）
                            viewModel.playList(state.neteaseListSongs, title = state.neteaseListTitle.ifBlank { "聚合多平台歌单" })
                        }
                        viewModel.closeNeteaseList()
                    },
                    onPlaySong = { song ->
                        // 单击某首歌：队列切到该榜单/歌单（从这首开始）便于上下切歌，
                        // 但不写入「最近播放」。「播放全部」才入栈。
                        // 不关闭弹层：用户可继续浏览/切歌
                        viewModel.playFromNeteaseList(song)
                    },
                    onToggleLike = { song -> viewModel.toggleLike(song.id) },
                    onAddToPlaylist = { song -> pendingActionList = state.neteaseListTitle to state.neteaseListSongs; pendingActionSong = song },
                    // 6.6.9：整单收藏
                    onSaveAllToPlaylist = {
                        if (state.neteaseListSongs.isNotEmpty()) {
                            saveAllTarget = state.neteaseListTitle.ifBlank { "这份榜单" } to state.neteaseListSongs
                        }
                    },
                    select = listSelect
                )
            }

            // 6.9.8：桌面歌词授权引导（每版本一次）
            if (showFloatLyricPrompt) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .junPressable { showFloatLyricPrompt = false },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        Modifier
                            .padding(horizontal = 36.dp)
                            .clip(JunShape.card)
                            .background(colors.surface)
                            .padding(24.dp)
                            .junPressable(enabled = false) {},
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "开启桌面歌词",
                            color = colors.text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "播放时在桌面和其它应用上方悬浮显示当前歌词。\n需要授予「显示在其他应用上层」权限。",
                            color = colors.subText,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                Modifier
                                    .junPressable { showFloatLyricPrompt = false }
                                    .clip(JunShape.item)
                                    .background(colors.surface)
                                    .padding(horizontal = 22.dp, vertical = 10.dp)
                            ) {
                                Text("暂不", color = colors.subText, fontSize = 13.sp)
                            }
                            Box(
                                Modifier
                                    .junPressable {
                                        showFloatLyricPrompt = false
                                        com.jun.musiccloud.playback.FloatingLyric.requestPermission(mainCtx)
                                    }
                                    .clip(JunShape.item)
                                    .background(colors.primary)
                                    .padding(horizontal = 22.dp, vertical = 10.dp)
                            ) {
                                Text("去授权", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // ===== 设置里重看新手引导：欢迎绘本 → 聚光灯指路 =====
            if (showReplayGuide) {
                ReplayGuideFlow(
                    colors = colors,
                    onGuideStep = { step -> showMyDrawer = step >= FIRST_DRAWER_STEP },
                    onDismiss = {
                        showMyDrawer = false
                        showReplayGuide = false
                    }
                )
            }

            // ===== 新功能导览：5.0 起攒下来的新功能，照着真实控件走一遍 =====
            // 每一步该看哪个界面由 FeatureTourSteps 上的分派规则决定；换步之前先把
            // "不该开着"的界面全部收掉，否则抽屉还开着，聚光灯照的是挡在前面的那层。
            val tourCleanSlate: () -> Unit = {
                onToggleSettings(false)
                showPlaylists = false
                showSearchScreen = false
                showMyDrawer = false
                showQueue = false
                showTodoBoard = false
                showAccount = false
                showProfile = false
                showDownloadManager = false
                showEqualizer = false
                showSleepTimer = false
            }
            // 6.7：导览期间收键盘用（LocalSoftwareKeyboardController 只能在组合期取）
            val tourIme = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            if (showFeatureTour) {
                FeatureTourFlow(
                    colors = colors,
                    netease = appFlags.netease,
                    hasSongRows = state.homeSongs.isNotEmpty(),
                    hasCurrentSong = state.currentSong != null,
                    onStep = { step ->
                        featureTourStep = step
                        if (step != FeatureTourSteps.NONE) {
                            // 6.7：导览换步时无条件收键盘。有些步骤会打开带输入框的界面
                            //（导入粘贴框等），键盘一弹就挡掉半屏聚光灯（用户："直接弹出键盘了，太难受了"）
                            tourIme?.hide()
                            tourCleanSlate()
                            when {
                                FeatureTourSteps.onHome(step) -> {
                                    // 回首页：上栏、推荐歌单抬头、歌曲行都要在界面上
                                    viewModel.goHome()
                                    if (!state.layout.isDeck) {
                                        backScope.launch { pagerState.scrollToPage(1) }
                                    }
                                }
                                FeatureTourSteps.onPlaylistSheet(step) -> {
                                    // 拉开歌单弹层；IMPORT_INPUT 那一步再由 openImport 展开输入面板
                                    showPlaylists = true
                                }
                                FeatureTourSteps.onDrawer(step) -> showMyDrawer = true
                                FeatureTourSteps.onSearchPage(step) -> showSearchScreen = true
                                FeatureTourSteps.onPlayerPage(step) -> {
                                    // 音质徽章在播放页；deck 排布本来就是全宽播放页，不用翻
                                    if (!state.layout.isDeck) {
                                        goToPagerPage(2)
                                    }
                                }
                            }
                        }
                    },
                    onDismiss = {
                        featureTourStep = FeatureTourSteps.NONE
                        showFeatureTour = false
                        showPlaylists = false
                        showMyDrawer = false
                    }
                )
            }

            // ===== 6.2 版本公告：升级后首次进入弹一次，并且排在所有启动流程最后 =====
            // 渲染门里这几项都是"比它先出现的流程"：首启绘本、启动登录、重看引导、功能导览。
            // 6.0 起功能导览对老用户不再自动弹，所以更新说明不会再被顶掉（用户："它出来一会儿又消失了"）；
            // R14 只给**第一次安装**的人放开一次，并且严格排在公告关掉以后（见上面那段链路）。
            // R13：正文不再写死在这一层——它整份搬到本轮的公告对象（当前 ui/WhatsNewR19.kt，唯一数据源），
            // 界面交给 ui/AppUpdateDialog.kt 的 WhatsNewDialog（修复/改动两段折叠 + 可复制纯文本）。
            // 之所以不能继续用 NoticeDialog：那一款是**服务器下发**的紧急公告，必须读满 3 秒、
            // 点遮罩和返回键都关不掉；本机文案是"看完自己走"，混用会把用户关不掉地按在公告上。
            // 抬门槛（GuidePrefs.WHATS_NEW_CODE = WhatsNewR19.CODE）只改这里不够：
            // 上一版文案若还挂着，门槛一抬就把旧公告再弹一次，这就是这处必须跟着换的原因。
            if (showWhatsNew && whatsNewReady && !showFirstRun &&
                !showReplayGuide && !showStartupLogin
            ) {
                // 6.9.3：旧版「欢迎绘本 + 聚光灯」不再自动出现，升级后弹这一份
                // 「从 6.3 到现在新增了什么 + 去哪儿找」的清单（用户点名要的）。
                FeatureGuideSheet(
                    colors = colors,
                    // 6.6：公告不再是「看完就走」，两个新功能都能一键直达
                    onOpenCustom = {
                        markWhatsNewSeen(guideContext)
                        showWhatsNew = false
                        CustomConfig.showDialog.value = true
                    },
                    onOpenMood = {
                        markWhatsNewSeen(guideContext)
                        showWhatsNew = false
                        requestMoodPage = true
                    },
                    onDismiss = {
                        markWhatsNewSeen(guideContext)
                        showWhatsNew = false
                    }
                )
            }

            // ===== 4.0.3 特大事故补偿：更新说明之后弹一次，一键领取 10 年 VIP =====
            if (showVipComp && !showWhatsNew && state.accountLoggedIn) {
                VipCompensationDialog(
                    state = state,
                    colors = colors,
                    onClaim = { viewModel.claimVip() },
                    onDismiss = {
                        markVipCompensationSeen(guideContext)
                        showVipComp = false
                    }
                )
            }

            // ===== 听歌待办（空状态引导）=====
            if (showTodoBoard) {
                TodoBoard(
                    colors = colors,
                    todos = state.todos,
                    onAdd = { viewModel.addTodo(it) },
                    onToggle = { viewModel.toggleTodo(it) },
                    onRemove = { viewModel.removeTodo(it) },
                    onClearDone = { viewModel.clearDoneTodos() },
                    onDismiss = { showTodoBoard = false },
                    onSearch = { q ->
                        // 备忘闭环成行动：用待办文案直接搜，并收起待办板露出下方的搜索结果
                        viewModel.updateSearch(q)
                        showTodoBoard = false
                    }
                )
            }

            // 版本更新提示（服务器控制，force 时不可取消）
            pendingUpdate?.let { u ->
                AppUpdateDialog(update = u, colors = colors, onDismiss = { pendingUpdate = null })
            }

            // 维护模式：后台打开开关后锁定当前界面，只能等后台关闭（或手动重试）
            if (maintenanceOn) {
                MaintenanceDialog(
                    text = maintenanceTip,
                    colors = colors,
                    onRetry = { updateScope.launch { syncRemoteConfig() } }
                )
            }

            // 通知中心：按 id 依次弹；必须等 3 秒点「已读」才能关闭
            noticeQueue.firstOrNull()?.let { notice ->
                NoticeDialog(
                    title = notice.title,
                    content = notice.content,
                    level = notice.level,
                    colors = colors,
                    noticeId = notice.id,
                    imageUrl = notice.imageUrl,
                    linkUrl = notice.linkUrl,
                    onDismiss = {
                        NoticeStore.markShown(appContext, notice)
                        Telemetry.reportNotice(appContext, notice.id, "read")
                        noticeQueue = noticeQueue.drop(1)
                    }
                )
            }
        }

        // ===== 底部常驻播放条（固定贴底、导航栏之上）=====
        // 「纵向列表」布局下常驻：
        //  - 库页（首页）：无浮层、停留在库页、有正在播放的歌时显示；
        //  - 搜索页：同样常驻（与首页同一枚播放条），空闲 4 秒后淡去、任意触摸即恢复。
        // 卡片/堆叠/唱片墙本身就是播放器，播放页已展示完整播放器，都不重复显示。
        if (showMiniPlayer) {
            MiniPlayerBar(
                state = state,
                colors = colors,
                pagerState = pagerState,
                modifier = Modifier.align(Alignment.BottomCenter),
                onToggle = onToggle,
                onPrev = onPrev,
                onNext = onNext,
                // 搜索页里小播放器不做「上滑翻页」手势（底层分页器被搜索页盖住，
                // 跟手拖动会改到看不见的页面）。改为点击直接打开播放页。
                draggable = !searchIsTop,
                // 空闲淡出：默认开启（首页和搜索页都会淡出），设置里「小播放器常驻」开启后关闭淡出。
                idleFadeEnabled = !state.miniPlayerPersist,
                idleFadeTick = touchTickState,
                onOpenPlayer = {
                    if (showSearchScreen) {
                        onSearch("")
                        showSearchScreen = false
                    }
                    goToPagerPage(2)
                },
                onOpenQueue = { showQueue = true }
            )
        }

        // 6.9.1：全局加载反馈条。播放页自带的反馈条只在打开播放页时看得见；
        // 用户在列表/搜索/榜单里点歌的那几秒（弱网最坏 5 秒+），此前屏幕上
        // 什么动静都没有——用户以为点了没反应就狂点。这条不管在哪个页面
        // 都贴底显示，取源→换源→缓冲全程有话可看；进了播放页就交给卡片自己的那条。
        // 6.9.2 放宽：唱片墙（COMPACT）没有播放页，反馈条也要贴底常显；
        // 卡片/堆叠（isDeck）整屏就是播放卡，卡片上的反馈条 6.9.2 已接上，
        // 这里保持关闭避免同屏两条。
        val showGlobalLoad = state.loadText.isNotBlank() &&
            ((state.layout == JunLayout.LIST && !onPlayerPage) || state.layout == JunLayout.COMPACT)
        if (showGlobalLoad) {
            GlobalLoadBar(
                text = state.loadText,
                buffered = state.loadBuffered,
                canRetry = state.loadCanRetry,
                colors = colors,
                onRetry = { viewModel.retryLoading() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(30f)
                    .padding(bottom = if (showMiniPlayer) 88.dp else 28.dp)
            )
        }

        // ===== 批量选择操作栏（5.1：从列表页提升到根层级） =====
        // 放在根 Box 的最后一段，才能盖在榜单/歌单/搜索这些浮层之上——
        // 之前它画在曲库列表内部，用户在热歌榜里点全选，操作栏却被压在榜单底下，
        // 选中集还是外面的播放队列。
        if (selectMode && selectSource != null) {
            val src = selectSource!!
            val pool = src.second
            val selectedSongs = pool.filter { it.id in selectedIds }
            BatchSelectBar(
                // align/zIndex 必须在 BoxScope 里取，所以从调用方传进来
                modifier = Modifier.align(Alignment.BottomCenter).zIndex(20f),
                colors = colors,
                sourceName = src.first,
                total = pool.size,
                selected = selectedIds.size,
                flags = appFlags,
                miniPlayerVisible = showMiniPlayer,
                onSelectAllToggle = {
                    if (selectedIds.size == pool.size) selectedIds.clear()
                    else {
                        selectedIds.clear()
                        selectedIds.addAll(pool.map { it.id })
                    }
                },
                onLike = {
                    viewModel.batchLike(selectedIds.toList(), src.first)
                    exitSelectMode()
                },
                onAddToPlaylist = {
                    if (selectedSongs.isNotEmpty()) pendingAddSongs = selectedSongs
                    exitSelectMode()
                },
                onDownload = {
                    if (selectedSongs.isNotEmpty()) viewModel.batchDownload(selectedSongs, src.first)
                    // 一首都没勾就点「下载」：不能只是什么都不做。这一条和批量下载
                    // 那颗钮同一个口径（ui/batchdl/BatchDownload.kt 里写得很清楚，
                    // clickable(enabled=false) 会把点击吞掉，用户只知道"按了没反应"）。
                    else viewModel.notice("先勾几首歌，再点下载")
                    exitSelectMode()
                },
                onOpenLog = { showBatchLog = true },
                onExit = { exitSelectMode() }
            )
        }

        // ===== 最近批量操作：可回看、最后一次可撤销 =====
        if (showBatchLog) {
            RecentBatchSheet(
                log = state.batchLog,
                colors = colors,
                onDismiss = { showBatchLog = false },
                onUndo = { viewModel.performUndo(); showBatchLog = false },
                onClear = { viewModel.clearBatchLog() }
            )
        }

        // 待弹的提示：空文案由 ToastHost 自己忽略，调用方不需要判断
        val toastItems = listOf(
            // R13：分享 / 导入口令的回执（复制成功、口令太长、导进多少首、几首没找到）
            ToastItem(shareNotice, shareNoticeTick, durationMs = 3200L),
            ToastItem(state.likeNotice, state.likeNoticeTick),
            ToastItem(state.libraryNotice, state.libraryNoticeTick, durationMs = 3400L),
            ToastItem(
                state.downloadNotice, state.downloadNoticeTick,
                actionLabel = if (state.downloadNotice.isNotBlank()) "查看" else null,
                onAction = { showDownloadManager = true }
            ),
            ToastItem(state.loginPrompt, state.loginPromptTick),
            // R25：整张歌单都没版权时的出口。连跳满 8 首才出现，给一次「搜其他版本」：
            // 原版拿不到，但同名翻唱 / 现场版经常能放，用户不用自己退出去敲关键字。
            ToastItem(
                state.copyrightTip,
                state.copyrightTipTick,
                actionLabel = if (state.copyrightTip.isNotBlank()) "搜其他版本" else null,
                onAction = {
                    val q = state.copyrightQuery
                    viewModel.clearCopyrightTip()
                    showSearchScreen = true
                    onSearch(q)
                },
                durationMs = 8000L
            ),
            // 6.1.2：凭证过期终于会说话了。
            // 以前 ServerApi.request 压根不看 HTTP 状态码，Account 里二十来处 `code != 0` 一律
            // 当成「没有这条数据」吞掉 —— token 过期之后云同步永久静默失败，界面一声不吭，
            // 用户只知道「我新建的歌单不见了」，只能靠退出重登碰运气。现在过期由
            // ServerApi / Account / PlayerViewModel 统一点亮 SessionSignals（同一来源 60 秒内
            // 只响一次），这里给一条留在屏上够久的胶囊，点「去登录」按来源分流。
            ToastItem(
                SessionSignals.expiredMessage.value, SessionSignals.expiredTick.value,
                actionLabel = if (SessionSignals.expiredMessage.value.isNotBlank()) "去登录" else null,
                onAction = {
                    if (SessionSignals.expiredTarget.value == SessionSignals.SOURCE_NETEASE) {
                        viewModel.openNeteaseLogin()
                    } else {
                        showAccount = true
                    }
                },
                durationMs = 6000L,
                accent = true
            ),
            // 音质切换：6.0 起不再贴在徽章上（会被按钮和相邻控件压住、字根本看不到），
            // 改成底部小胶囊，停留 3 秒自己收走。解析回来后文案会被替换并重新弹一次。
            ToastItem(
                state.qualityFlash, state.qualityFlashTick,
                durationMs = 3_000L,
                icon = JunIcons.Tune, accent = true
            ),
            // 音效切换（R12）：上面写着哪一档，切过去就提示哪一档的名字，
            // 本机有没有真的改变听感都不影响这句话——用户明确要的就是"报名字"。
            ToastItem(
                state.fxFlash, state.fxFlashTick,
                durationMs = 2_600L,
                icon = JunIcons.Tune, accent = true
            ),
            ToastItem(
                state.errorNotice, state.errorNoticeTick,
                actionLabel = "重试",
                onAction = { viewModel.retryLastFailedAction() },
                durationMs = 4200L
            ),
            // 撤销：清空队列 / 删除待办这类不可逆操作给 4 秒反悔窗口
            ToastItem(
                state.undoNotice, state.undoNoticeTick,
                actionLabel = "撤销",
                onAction = { viewModel.performUndo() },
                durationMs = 4200L
            ),
            // 在线缓存写满：默认 256MB，满了自动 LRU 覆盖；给一条提示 + 一个清空入口
            ToastItem(
                state.cacheNotice, state.cacheNoticeTick,
                actionLabel = if (state.cacheNotice.isNotBlank()) "清空" else null,
                onAction = { viewModel.clearStreamCache() },
                durationMs = 5000L
            )
        )

        // ===== 自定义封面裁剪 =====
        pendingCoverUri?.let { uri ->
            CoverCropDialog(
                uri = uri,
                colors = colors,
                onDismiss = { pendingCoverUri = null },
                onSave = { zoom, ox, oy ->
                    val key = state.currentSong?.filePath.orEmpty()
                    if (key.isBlank()) {
                        false
                    } else {
                        val path = CustomCoverStore.saveCropped(mainCtx, key, uri, zoom, ox, oy)
                        path != null
                    }
                }
            )
        }

        // ===== 剪贴板歌单链接：确认后直接走现有导入流程 =====
        val pendingClipLink = clipboardLink
        if (pendingClipLink != null &&
            !showFirstRun && !showFeatureTour && !showReplayGuide && !showStartupLogin &&
            !showWhatsNew
        ) {
            ClipboardImportPrompt(
                link = pendingClipLink,
                busy = clipboardBusy,
                status = clipboardStatus,
                success = clipboardSuccess,
                colors = colors,
                onImport = {
                    if (!clipboardBusy) {
                        clipboardBusy = true
                        clipboardStatus = ""
                        clipboardSuccess = false
                        clipboardScope.launch {
                            val msg = runCatching { viewModel.importNeteasePlaylist(pendingClipLink) }
                                .getOrElse { "导入失败：${it.message ?: "网络异常"}" }
                            clipboardBusy = false
                            clipboardSuccess = msg.startsWith("已导入")
                            clipboardStatus = msg
                            ClipboardLinkStore.markHandled(mainCtx, pendingClipLink, 0L)
                        }
                    }
                },
                onOpenPlaylist = {
                    ClipboardLinkStore.markHandled(mainCtx, pendingClipLink, 0L)
                    clipboardLink = null
                    showQueue = true
                },
                onDismiss = {
                    ClipboardLinkStore.markHandled(mainCtx, pendingClipLink, 0L)
                    clipboardLink = null
                }
            )
        }

        // ===== 轻提示栈：点赞 / 下载 / 登录提醒 / 错误 / 撤销，统一排队显示 =====
        // 位置由栈自己算，不再为每个提示手写一个 bottom padding（原来四个数字互相对齐，
        // 加一种提示就要重排一遍，播放条一显隐又要另算一套）。
        ToastHost(
            items = toastItems,
            colors = colors,
            bottomInset = if (showMiniPlayer) 96.dp else 40.dp,
            // zIndex 必须高于所有弹层（曲库弹层 / 歌曲菜单 12~14 / 批量栏 20）：
            // 「已清空播放列表 · 撤销」这类反悔提示正是从弹层里点出来的，
            // 画在弹层遮罩底下就等于没弹（撤销点不到，4 秒后窗口就过了）。
            modifier = Modifier.align(Alignment.BottomCenter).zIndex(30f)
        )
    }
    }
}



/**
 * 分页器的连续展开进度：0 = 曲库页 + 底部播放条就位，1 = 播放页完全展开。
 *
 * 播放条和播放页读同一个值，两侧的运动才是同一个物体的两端（一个塌下去、一个长上来），
 * 而不是"淡出 + 滑入"两段各自独立的动画。
 */
private fun PagerState.morphProgress(): Float =
    // 6.9.7：三页布局下，形变区间是「音乐库(1) → 播放页(2)」。
    // 模式探索页(0) 也得 0，与音乐库一样保持小播放器完全展开。
    ((currentPage + currentPageOffsetFraction) - 1f).coerceIn(0f, 1f)

/**
 * 底部常驻播放条：悬浮圆角卡片，与列表行/卡片同款的圆角与材质语言。
 *
 * 封面 + 歌名/歌手 + 上一曲 · 播放/暂停 · 下一曲 + 播放列表，顶部细进度线。
 * 点封面/文字区域进入全屏播放页；上滑是「跟手」的：竖向拖动直接驱动分页器，
 * 播放页随手指从右侧滑入，播放条按进度淡出（不再是先消失、再弹出播放页）。
 */
@Composable
private fun MiniPlayerBar(
    state: PlayerUiState,
    colors: JunColors,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenQueue: () -> Unit,
    /** 是否启用「上滑翻到播放页」的跟手手势；搜索页里底层分页器被盖住，必须关掉。 */
    draggable: Boolean = true,
    /** 是否启用空闲淡出（仅搜索页）。 */
    idleFadeEnabled: Boolean = false,
    /** 每次用户操作自增；变化即视为"有操作"，把淡出的播放条重新点亮。 */
    idleFadeTick: State<Int>? = null
) {
    val song = state.currentSong ?: return
    // 6.6：迷你条不再画进度条（用户一直把它当成"没填充好的 UI bug"），
    // 进度改为点开播放页看；这里只留一个干净的药丸岛。
    val glass = isLiquidGlass
    val scope = rememberCoroutineScope()

    // 跟手：用「绝对滚动位置 = 当前页 + 页内偏移比例」判定打开程度。
    // Compose Pager 的 currentPageOffsetFraction 符号约定：向「下一页（播放器）」拖动时
    // 该值为「正」（本文件 SongDeck 的相邻卡片景深就是按 `(page - currentPage) - offsetFraction`
    // 算距离，等价于连续位置 = currentPage + offsetFraction）。
    // 因此正确视口浮点位置是 currentPage + offset；误写成 - 会让拖动过半时算成负值、
    // 松手判定不成立并弹回首页——表现为"上滑只切到一半"。
    val absoluteOffset = if (draggable) pagerState.morphProgress() else 0f
    val barAlpha = (1f - absoluteOffset * 1.35f).coerceIn(0f, 1f)
    // 连续形变：播放条不是"到点消失"，而是随同一个 0→1 进度一路塌扁、下沉、变淡，
    // 播放页同时反向升起、放大、亮起（见 PlayerPage 根层）。两端读同一个进度，
    // 观感就是一块玻璃被顶大成整屏播放器，而不是"切页"。
    // 纵向塌得比横向快（0.12 vs 0.045）：先被压扁、再沉出底边，比等比缩小更像被顶下去。
    val morph = absoluteOffset
    val morphScaleX = 1f - morph * 0.045f
    val morphScaleY = 1f - morph * 0.12f
    // 内容比外壳先退：外壳还在塌的时候字已经走了，剩一整块玻璃在沉——
    // 否则压扁的文字是那种一眼假的形变。
    val morphContentAlpha = (1f - morph * 2.4f).coerceIn(0f, 1f)

    // 空闲淡出：搜索页里一段时间无操作就淡到很浅（隐隐约约），任意触摸重新浮现。
    var idle by remember { mutableStateOf(false) }
    val tick = idleFadeTick?.value ?: 0
    LaunchedEffect(idleFadeEnabled, tick) {
        if (!idleFadeEnabled) {
            idle = false
            return@LaunchedEffect
        }
        idle = false
        delay(4000)
        idle = true
    }
    val idleAlpha by animateFloatAsState(
        targetValue = if (idle) 0.3f else 1f,
        animationSpec = tween(520),
        label = "miniIdleFade"
    )

    Box(
        modifier
            .fillMaxWidth()
            // 先避开系统导航栏，玻璃底只在卡片本体上——之前把 navigationBarsPadding 放在
            // 玻璃之后，玻璃会一直铺到导航栏区域，底部就留出一条巨大的空白玻璃条。
            .navigationBarsPadding()
            // 6.7：宽度与上下位置都交给「自定义中心 → 小播放器」，调节时弹窗里有实时预览。
            // 默认值恢复成用户要的「最之前那样」：贴边全宽（左右 12dp）。
            .padding(horizontal = if (CustomConfig.miniWide.value) 12.dp else 24.dp)
            .padding(bottom = (18f + CustomConfig.miniOffsetDp.value.coerceIn(-8f, 48f)).dp)
            .graphicsLayer {
                alpha = barAlpha * idleAlpha
                // 随分页进度塌扁 + 下沉：进度 1 时整条已经滑出底部，
                // 观感上是"被播放页顶下去"，不是"到某一刻突然没了"。
                scaleX = morphScaleX
                scaleY = morphScaleY
                translationY = morph * 34.dp.toPx()
                // 缩放以底边为轴，卡片才贴着底部沉下去而不是往中间缩。
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }
            .then(
                if (glass) {
                    Modifier.junSurface(GlassLevel.SHEET, colors, JunShape.card)
                } else {
                    Modifier
                        .clip(JunShape.card)
                        .background(colors.surface)
                }
            )
            .then(
                if (!draggable) {
                    // 搜索页：底层分页器被盖住，不能上滑跟手。
                    // 点封面/文字区域直接打开播放页（暂停有专门的播放/暂停按钮）。
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { Haptics.tap(); onOpenPlayer() }
                } else Modifier.pointerInput(Unit) {
                    // 单一手势处理器：同时负责「点一下进入播放页」和「上滑跟手展开播放页」。
                    // 之前把点击放在 Row 的 clickable、上滑放在外层 detectVerticalDragGestures，
                    // 两套手势在同一棵子树里抢 down 事件，导致上滑经常只跟到一半就被吞掉、
                    // 松手弹回——表现为"抬一下卡在一半"。合并后 down 只被这一处消费，
                    // 拖动全程连续驱动分页器，松手再按进度吸附到 0 / 1 页。
                    val openSwipePx = 180.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 落在按钮上的 down 已被按钮消费，交给按钮处理，这里不接管
                        if (down.isConsumed) return@awaitEachGesture
                        var dragging = false
                        var horizontal = false
                        var totalDy = 0f
                        var totalDx = 0f
                        var lastT = down.uptimeMillis
                        // 末端速度（px/s，向下为正）：只做轻度平滑，避免单帧抖动决定吸附
                        var velocity = 0f
                        val slop = viewConfiguration.touchSlop
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUp()) break
                            val dy = change.positionChange().y
                            val dx = change.positionChange().x
                            val t = change.uptimeMillis
                            val dt = t - lastT
                            totalDy += dy
                            totalDx += dx
                            // 6.6：先判方向，竖向主导 = 上滑展开播放页，
                            // 横向主导 = 滑动切歌。两种手势互斥，一旦选定就不改，
                            // 避免斜着滑时既展开又切歌。
                            if (!dragging && !horizontal) {
                                when {
                                    kotlin.math.abs(totalDy) > slop &&
                                        kotlin.math.abs(dy) > kotlin.math.abs(dx) -> dragging = true
                                    kotlin.math.abs(totalDx) > slop &&
                                        kotlin.math.abs(dx) > kotlin.math.abs(dy) -> horizontal = true
                                }
                            }
                            if (horizontal) {
                                change.consume()
                                lastT = t
                                continue
                            }
                            if (dragging) {
                                change.consume()
                                val pagePx = pagerState.layoutInfo.pageSize.toFloat()
                                val boost = if (pagePx > 0f) pagePx / openSwipePx else 2.5f
                                pagerState.dispatchRawDelta(-dy * boost)
                                if (dt > 0L) {
                                    val inst = dy * 1000f / dt
                                    velocity = velocity * 0.5f + inst * 0.5f
                                }
                                lastT = t
                            }
                        }
                        if (horizontal) {
                            // 6.6：向左滑 = 下一首，向右滑 = 上一首。
                            // 阈值 48dp，太短（点一下带点手抖）不切歌。
                            // 6.7：把上一曲/下一曲两枚按钮都去掉后，滑动自动兜底切歌；
                            // 也可以单独在自定义中心关掉横滑，避免误触。
                            val swipeEnabled = CustomConfig.miniSwipeTrack.value ||
                                (!CustomConfig.miniShowPrev.value && !CustomConfig.miniShowNext.value)
                            if (swipeEnabled && kotlin.math.abs(totalDx) > 48.dp.toPx()) {
                                Haptics.tap()
                                if (totalDx < 0f) onNext() else onPrev()
                            }
                        } else if (dragging) {
                            // 6.9.7：三页布局后不能再写死 0/1，改成"相对当前页 +1 / 回当前页"。
                            // 当前页是音乐库(1) 时上滑仍展开到播放页(2)；
                            // 万一在模式探索页(0) 上滑，则落到音乐库，不会越界。
                            val page = pagerState.currentPage
                            val fraction = pagerState.currentPageOffsetFraction
                            val pagePx = pagerState.layoutInfo.pageSize.toFloat().coerceAtLeast(1f)
                            // 手势是 -dy 驱动的，所以分页器坐标系里的速度是向上为正。
                            val pageVelocity = -velocity / pagePx
                            // 过半或向上快甩都算"要展开"：慢拖靠位置，快甩靠动量。
                            val target = when {
                                pageVelocity > 1.6f -> page + 1
                                pageVelocity < -1.6f -> page
                                fraction > 0.4f -> page + 1
                                else -> page
                            }.coerceIn(0, (pagerState.pageCount - 1).coerceAtLeast(0))
                            scope.launch {
                                // 6.0：显式传 [JunMotion.pagerSnap]。框架默认那档 spring 是
                                // ζ=1 / k=1500，整页两百毫秒内就到位，看不出"从小条长成播放页"
                                // 的过程；这里换成更软的曲线（k=260），上滑展开/下滑收起都有
                                // 一段明显的过渡。animateScrollToPage 不吃初速度，
                                // 动量仍然体现在上面的吸附阈值判定里。
                                pagerState.animateScrollToPage(
                                    target,
                                    animationSpec = JunMotion.pagerSnap()
                                )
                            }
                        } else if (kotlin.math.abs(totalDy) <= slop &&
                            kotlin.math.abs(totalDx) <= slop
                        ) {
                            // 没有明显拖动 → 视为点击，进入播放页
                            Haptics.tap()
                            onOpenPlayer()
                        }
                    }
                }
            )
    ) {
        Column(Modifier.graphicsLayer { alpha = morphContentAlpha }) {
            // 6.6：去掉那条 3dp 细进度条。用户反馈"白色进度条有点影响美观，
            // 一开始还以为是 UI bug 没填充好，如果是进度条应该能拖动"——
            // 既然它本来就不能拖、又容易被误读，直接取消，进度去播放页看。
            Row(
                Modifier
                    .fillMaxWidth()
                    // 点击进入播放页的逻辑已上移到外层 pointerInput（与上滑展开共用同一手势），
                    // 这里不再挂 clickable，避免两套手势抢 down 事件导致上滑卡顿。
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (CustomConfig.miniShowCover.value) {
                    SongArtwork(
                        filePath = song.filePath,
                        seedId = song.id,
                        artworkSize = 44.dp,
                        isDark = colors.isDark
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = if (CustomConfig.miniTitleCenter.value)
                        Alignment.CenterHorizontally else Alignment.Start
                ) {
                    val marquee = CustomConfig.miniTitleMarquee.value
                    val titleMod = if (marquee) Modifier.basicMarquee() else Modifier
                    if (CustomConfig.miniTitleLayout.value == 1) {
                        // 6.6 新增排布：单行「歌手 - 歌名」（如 赵雷 - 家乡）
                        val single = if (CustomConfig.miniShowArtist.value) {
                            "${song.artist.ifBlank { "未知艺术家" }} - ${song.title}"
                        } else song.title
                        Text(
                            single,
                            color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = titleMod
                        )
                    } else {
                        // 默认：两行，歌名在上、歌手在下
                        Text(
                            song.title,
                            color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = titleMod
                        )
                        if (CustomConfig.miniShowArtist.value) {
                            Spacer(Modifier.height(1.dp))
                            Text(
                                song.artist.ifBlank { "未知艺术家" },
                                color = colors.subText, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = if (marquee) Modifier.basicMarquee() else Modifier
                            )
                        }
                    }
                }
                Spacer(Modifier.width(2.dp))
                // 6.7：默认恢复成用户要的「最之前那样」—— 上一曲 / 播放暂停 /
                // 下一曲 / 播放列表四枚键；每一枚都能在自定义中心单独摘掉。
                // 选「药丸岛」则回到 6.6 那版只留一枚播放键的极简形态。
                if (CustomConfig.miniClassic.value) {
                    // 6.6：按用户要求去掉上一曲 / 下一曲之间那道细竖杠，改成大播放器
                    // 控制区那套「几枚裸图标按钮 + 均匀留白」，不再加任何分隔线。
                    if (CustomConfig.miniShowPrev.value) {
                        PressableIconButton(size = 40.dp, onClick = { Haptics.click(); onPrev() }) {
                            Icon(
                                JunIcons.SkipPrevious, contentDescription = "上一曲",
                                tint = colors.text, modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(Modifier.width(2.dp))
                    }
                    PressableIconButton(size = 42.dp, onClick = { Haptics.click(); onToggle() }) {
                        PlayPauseIcon(state.isPlaying, colors.primary, 26.dp)
                    }
                    if (CustomConfig.miniShowNext.value) {
                        Spacer(Modifier.width(2.dp))
                        PressableIconButton(size = 40.dp, onClick = { Haptics.click(); onNext() }) {
                            Icon(
                                JunIcons.SkipNext, contentDescription = "下一曲",
                                tint = colors.text, modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    if (CustomConfig.miniShowQueue.value) {
                        Spacer(Modifier.width(2.dp))
                        PressableIconButton(size = 40.dp, onClick = { Haptics.click(); onOpenQueue() }) {
                            Icon(
                                JunIcons.QueueMusic, contentDescription = "播放列表",
                                tint = colors.text, modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                } else {
                    PressableIconButton(size = 48.dp, onClick = { Haptics.click(); onToggle() }) {
                        PlayPauseIcon(state.isPlaying, colors.primary, 28.dp)
                    }
                }
            }
        }
    }
}

// 6.6：细竖杠（MiniBarDivider）已整体移除 —— 用户明确不要按钮中间的竖线，
// 小播放器的上一曲 / 下一曲改学大播放器：裸图标按钮 + 均匀留白。

/**
 * 曲库排序栏：上栏卡片的扩展行（与搜索框同级）。
 *
 * 交互是"点一下切换"，没有弹出选单——Popup 悬浮层无法折射上栏背板、
 * 嵌套圆角和主题适配都容易出缝，循环切换从根上没有这些问题：
 * 左胶囊点击循环 标题→艺术家→时长→修改/添加时间，右胶囊切换 正序/倒序。
 * 胶囊与搜索框同形：同圆角（JunShape.item）、同底色、同字号。
 */
@Composable
private fun LibrarySortBar(
    order: SortOrder,
    direction: SortDirection,
    colors: JunColors,
    glass: Boolean,
    onOrderChange: (SortOrder) -> Unit,
    onDirectionChange: (SortDirection) -> Unit
) {
    val chipHeight = 30.dp
    val chipBg = if (glass) {
        if (colors.isXiaomei) {
            if (colors.isDark) Color(0x3DFF9CC0) else Color(0x52FFD9E8)
        } else if (colors.isDark) Color(0x3DFFFFFF) else Color(0x52FFFFFF)
    } else {
        if (colors.isXiaomei) colors.row
        else if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .height(chipHeight)
                .junPressable(scaleDown = 0.92f) {
                    val next = SortOrder.entries[(order.ordinal + 1) % SortOrder.entries.size]
                    onOrderChange(next)
                }
                .clip(JunShape.item)
                .background(chipBg)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                order.label,
                color = colors.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Box(
            modifier = Modifier
                .height(chipHeight)
                .junPressable(scaleDown = 0.92f) {
                    onDirectionChange(
                        if (direction == SortDirection.ASCENDING) SortDirection.DESCENDING
                        else SortDirection.ASCENDING
                    )
                }
                .clip(JunShape.item)
                .background(chipBg)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (direction == SortDirection.ASCENDING) "正序 ↑" else "倒序 ↓",
                color = colors.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ==================== 列表页 ====================
/**
 * 首页运营横幅：横向滑动卡片，图片来自后台「横幅管理」。
 *
 * 网络图片解码放在 IO 线程；没图/加载失败时退化成主题色渐变 + 标题，
 * 不会出现白块或空占位。点击有链接的横幅用系统浏览器打开（只放行 http/https）。
 */
@Composable
private fun BannerStrip(
    banners: List<com.jun.musiccloud.data.AppBanner>,
    colors: JunColors
) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        banners.forEach { b ->
            var bmp by remember(b.image) { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(b.image) {
                if (b.image.isBlank()) return@LaunchedEffect
                bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        val conn = java.net.URL(b.image).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 6000
                        conn.readTimeout = 8000
                        conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                }
            }
            Box(
                Modifier
                    .width(268.dp)
                    .height(122.dp)
                    .clip(JunShape.card)
                    .background(colors.card)
                    .clickable {
                        if (b.link.isNotBlank()) {
                            Haptics.tap()
                            runCatching {
                                val uri = android.net.Uri.parse(b.link)
                                // 只放行网页链接：后台误填 file:// / intent:// 也不会被点开
                                if (uri.scheme == "http" || uri.scheme == "https") {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                    ctx.startActivity(intent)
                                }
                            }
                        }
                    }
            ) {
                val bitmap = bmp
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = b.title.ifBlank { "运营横幅" },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        colors.primary.copy(alpha = 0.32f),
                                        colors.primary.copy(alpha = 0.08f)
                                    )
                                )
                            )
                    )
                }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                            )
                        )
                        .padding(12.dp)
                ) {
                    if (b.title.isNotBlank()) {
                        Text(
                            b.title,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (b.subtitle.isNotBlank()) {
                        Text(
                            b.subtitle,
                            color = Color.White.copy(alpha = 0.82f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryPage(
    state: PlayerUiState,
    colors: JunColors,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scrollerDragging: MutableState<Boolean>,
    onSelect: (Int) -> Unit,
    onPlaySong: (Song) -> Unit,
    onSearch: (String) -> Unit,
    onSettings: () -> Unit,
    onReport: () -> Unit,
    onReload: () -> Unit,
    onCycleLayout: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenMy: () -> Unit,
    /** 6.3：打开听歌排行榜（上栏金色奖杯入口） */
    onOpenRank: () -> Unit = {},
    onOpenSearchPage: () -> Unit,
    /** 6.9.9：搜索栏右侧「自定义」按钮 → 打开首页分区自定义弹层 */
    onOpenHomeCustomize: () -> Unit = {},
    /** 6.9.9：首页分区顺序与隐藏（MainScreen 持有，自定义弹层改完立刻传回） */
    homeOrder: List<String> = emptyList(),
    homeHidden: Set<String> = emptySet(),
    onSortOrder: (SortOrder) -> Unit,
    onSortDirection: (SortDirection) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onToggleLike: (Song) -> Unit,
    /** 点歌曲行里的歌手名 → 打开歌手详情页 */
    onOpenArtist: (String) -> Unit = {},
    /** 6.3.9：首页「热门歌手」→ 直接用歌手 id + 头像打开，不再按名字反查 */
    onOpenArtistById: (String, String, String) -> Unit = { _, _, _ -> },
    onGoHome: () -> Unit,
    onPlayAll: () -> Unit = {},
    onNeteaseToplist: () -> Unit = {},
    /** 首页信息流发现空数据时自动补拉（不用用户先点一下） */
    onLoadNeteaseCharts: () -> Unit = {},
    /** 首页推荐歌单划到底：再要一批（VM 内部按 recsLoadingMore / recommendAllLoaded 去重封顶） */
    onLoadMoreNeteaseRecs: () -> Unit = {},
    onNeteasePlaylists: () -> Unit = {},
    /** 6.9.7：首页第一排「我的音乐」——本地喜欢 + 本机自建歌单 */
    onOpenLocalLiked: () -> Unit = {},
    onOpenLocalPlaylist: (Long) -> Unit = {},
    onNeteaseDaily: () -> Unit = {},
    onNeteaseLiked: () -> Unit = {},
    onNeteaseSelectPlaylist: (String, String) -> Unit = { _, _ -> },
    /** 首页后两排的榜卡：打开这个榜的完整列表（R12 新增，与搜索页那排榜单卡同一个动作） */
    onOpenNeteaseToplist: (String, String) -> Unit = { _, _ -> },
    onNeteaseSelectToplist: (String, String) -> Unit = { _, _ -> },
    onPlayRecent: (com.jun.musiccloud.data.RecentPlayback) -> Unit = {},
    /** 6.3.2：首页「猜你喜欢」入口被点 → 直接开播 */
    onPlayHomeGuess: () -> Unit = {},
    /** 6.3.2：重算「猜你喜欢」（本地算法，不发网络） */
    onRefreshHomeGuess: () -> Unit = {},
    /** 6.3.8：加载首页每日栏目（新歌/歌手/风格/新碟/粤语），内部按天去重 */
    onLoadHomeFeed: () -> Unit = {},
    /** 6.3.8：首页「每日新歌」点歌 → 从那一首起播整组 */
    onPlayHomeNewSong: (Song) -> Unit = {},
    /** 6.3.8：首页风格电台卡片被点 → 以该栏目歌曲开播 */
    onPlayChannel: (String, List<Song>) -> Unit = { _, _ -> },
    /** 6.3.8：首页「新碟上架」点专辑 → 打开专辑曲目 */
    onOpenNeteaseAlbum: (String, String) -> Unit = { _, _ -> },
    showCount: Boolean = true,
    /** 批量选择上下文：选中集与范围都由 MainScreen 持有（见 ListSelect） */
    select: ListSelect = ListSelect(),
    /** 总后台功能开关：控制网易云专区 / 下载 / 批量加歌单入口 */
    flags: com.jun.musiccloud.data.AppFlags = com.jun.musiccloud.data.AppFlags(),
    /** 首页运营横幅（后台「横幅管理」下发；为空时不占位） */
    banners: List<com.jun.musiccloud.data.AppBanner> = emptyList(),
    /** 曲库页 ↔ 播放页的分页状态：只用来读连续展开进度，做退后一层的视差。 */
    pagerState: PagerState? = null
) {
    // 上栏高度（px）与当前偏移：上滑跟手滑出屏幕，下滑从任意位置随时拉回
    val density = LocalDensity.current
    val statusBarHeightDp = WindowInsets.statusBars.getTop(density)
    val barTopGap = 20.dp // 上栏距离状态栏底部的间距：贴近顶部，不压到屏幕中间
    val barSlotHeight = 84.dp // 上栏卡片估算高度（实际由 onSizeChanged 校准），用于给歌单顶部预留空间
    // 初始估算上栏高度，实际值由 onSizeChanged 更新
    val barHeight = remember { mutableStateOf(with(density) { barSlotHeight.toPx().toInt() }) }
    val barOffset = remember { mutableStateOf(0f) }

    // 歌单列表状态（由外部传入：滚动条挂在根层级共享）
    // 不再自动滚动到当前播放行：用户在浏览列表时切歌（推荐点歌/自动切歌）不打断浏览位置
    // 毛玻璃模糊源状态
    val hazeState = remember { HazeState() }
    // ===== 搜索输入焦点管理 =====
    // 用户诉求：输入时点任何地方都中断输入（收起键盘、失焦）。
    // 追踪搜索框是否聚焦 + 它在根坐标下的矩形；页面根层在聚焦期间监听按下，
    // 落点在搜索框外就 clearFocus()。监听只读不消费，不干扰列表点击/滚动。
    val focusManager = LocalFocusManager.current
    val searchInteraction = remember { MutableInteractionSource() }
    val searchFocused by searchInteraction.collectIsFocusedAsState()
    val searchBounds = remember { mutableStateOf(Rect.Zero) }
    // ===== 搜索框常驻开关 =====
    // 关闭常驻后：搜索框收起，点标题「JUN MUSIC」展开并自动聚焦；
    // 再点标题收起（有查询词时一并清空回到全库）；聚焦后又闲置且清空了词，几秒后自动收起。
    var searchExpanded by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    // 纵向排布始终显示搜索框（顶栏搜索按钮已移除，搜索框是唯一搜索入口）
    val showSearch = true
    // 批量选择的选中集已提升到 MainScreen（这里只消费 [select]）：
    // 长按菜单可能从榜单详情 / 歌单 / 搜索结果里打开，全选范围必须是"用户正在看的那份列表"，
    // 让每个列表页各存一份选中集，就会出现"在热歌榜点全选、选中的却是外面曲库"的错位。
    // 进入歌单/我喜欢视图，或在这些视图内切歌时，自动滚动到当前播放的那首，
    // 让用户一眼看到正在听哪首。首页（全部音乐）不自动滚，避免打断浏览。
    // 首页纵向列表永远展示全部音乐，不再因「我喜欢」/歌单切换而改变内容，
    // 因此无需在切歌时自动滚动（避免打断浏览）。播放列表弹层内有独立的自动滚动。
    // 展开后自动聚焦（等 TextField 进入组合再请求焦点）
    LaunchedEffect(searchExpanded, state.searchPersistent) {
        if (!state.searchPersistent) {
            if (searchExpanded) {
                delay(150)
                runCatching { searchFocusRequester.requestFocus() }
            }
        }
    }
    // 闲置自动收起：非展开开关周期内、未聚焦、无查询词 → 4 秒后收起
    LaunchedEffect(searchExpanded, searchFocused, state.searchQuery, state.searchPersistent) {
        if (!state.searchPersistent && searchExpanded && !searchFocused && state.searchQuery.isBlank()) {
            delay(4000)
            searchExpanded = false
        }
    }
    val frosted = isFrostedGlass
    val frostedBlur = frostedBlurRadius
    val glass = isLiquidGlass

    // 上栏浮在歌单之上，除了背景还要折射歌单本身——"玻璃下面的歌名被压弯"是最能
    // 说明这是真玻璃的一处细节。歌单单独录一层背板，上栏取样【外层背板 + 歌单】。
    // 上栏是歌单的兄弟节点，不在歌单的录制内容里，所以不会互相失效。
    val listBackdrop = rememberJunBackdrop()
    val outerBackdrops = LocalJunBackdrops.current
    val barBackdrops = remember(glass, outerBackdrops, listBackdrop) {
        if (glass) outerBackdrops + listBackdrop else emptyList()
    }

    // 顶栏改为「固定常驻」：隐藏行程恒为 0，barOffset 永远停在 0。
    // 顶栏不再随列表滚动收起，也就没有"移出屏幕后拉不回来"这一类问题；
    // 下拉刷新（refreshOffset）逻辑不受影响，下拉时顶栏仍会轻微跟手。
    val hideDistanceState = rememberUpdatedState(0f)
    val barScope = rememberCoroutineScope()
    // 排序行是上栏的扩展：上栏几乎完整露出（≈90%）才展开，滚走时随上栏收起。
    // 与搜索框同款"闲置自动收起"：选完排序/方向后短暂停留自动收起，
    // 上栏完全收起再重新拉出时恢复展开。不占列表条目，滚动条索引不受影响。
    // 顶栏固定常驻后不再有"收起"状态：排序行只要在设置里打开就常驻显示，
    // 选完排序/方向后短暂停留仍会自动收起（sortBarDismissed）。
    val sortBarExposed = true
    var sortBarDismissed by remember { mutableStateOf(false) }
    var sortInteractionTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(sortBarExposed) { if (!sortBarExposed) sortBarDismissed = false }
    LaunchedEffect(sortInteractionTick) {
        if (sortInteractionTick > 0) {
            delay(3500)
            sortBarDismissed = true
        }
    }
    val sortBarVisible = state.showSortBar && sortBarExposed && !sortBarDismissed
    var barSettleJob by remember { mutableStateOf<Job?>(null) }
    val settleBarTo: (Float) -> Unit = { target ->
        barSettleJob?.cancel()
        barSettleJob = barScope.launch {
            Animatable(barOffset.value).animateTo(
                targetValue = target,
                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
            ) { barOffset.value = value }
        }
    }

    // 滑条拖动（scrollToItem 跳转）不经过嵌套滚动，上栏不会自动跟随。
    // 拖动中：完全按当前索引实时决定上栏；松手后交由 scrollConnection 的滚动增量驱动
    LaunchedEffect(listState, scrollerDragging.value) {
        if (!scrollerDragging.value) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                barSettleJob?.cancel()
                barOffset.value = if (index == 0) 0f else -hideDistanceState.value
            }
    }

    // 滚动期间通知玻璃跳过真折射取样（省掉最贵的整背板流水线），停下后补一次重绘
    // 把真折射画回来——这是「滚动 120Hz 不掉帧」的主开关（见 GlassRuntime）。
    //
    // 6.6：不再写 ArtworkLoader.scrollPause。那个开关本意是"滚动中让封面解码让路"，
    // 实际效果是"滚动中一行封面都不出，一松手整屏一起蹦"（用户报的 bug），
    // 而且滚动开始/结束时每行的 LaunchedEffect 都要重启一遍，反而添乱。
    // 封面加载改回随滚随出，并发由 ArtworkLoader 的 decodeGate 控制。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                GlassRuntime.scrollActive = scrolling
                if (!scrolling) GlassRuntime.requestRedraw()
            }
    }

    // 首页推荐歌单「划到底再要一批」：只在一次滚动手势停下时判定，
    // 停在末尾附近才发请求。发不出去也没关系 —— VM 里 recsLoadingMore 防并发、
    // recommendAllLoaded 防在服务端钳死的 limit 上反复空跑。
    LaunchedEffect(listState, state.recommendAllLoaded) {
        if (state.recommendAllLoaded || !flags.netease) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (inProgress) return@collect
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                if (last >= info.totalItemsCount - 4) onLoadMoreNeteaseRecs()
            }
    }


    // 列表真正回到顶部时上栏必须完整露出——这是整套位移逻辑的兜底。
    // 少了它，任何一次"上栏位移没跟上列表位移"都会把上栏永久留在屏幕外：
    // 到了顶部，下滑手势归下拉刷新，上栏再没有机会被拉回来。
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
            .distinctUntilChanged()
            .collect { atTop -> if (atTop) settleBarTo(0f) }
    }
    // 内容不足一屏（搜索只剩几首）时列表根本不能滚，上栏一旦被推走就推不回来
    val listScrollable = listState.canScrollForward || listState.canScrollBackward
    LaunchedEffect(listScrollable) {
        if (!listScrollable && barOffset.value != 0f) settleBarTo(0f)
    }

    // ===== 下拉刷新：橡皮筋位移 + 弹性回弹 =====
    // 原先位移只喂给指示文字，界面本体不动，所以看着"不跟手"；现在位移直接作用在
    // 歌单与上栏上（见下方 graphicsLayer），整页跟着手指走。
    val refreshTriggerPx = with(density) { 96.dp.toPx() }
    // 拉到底的上限：再往下也不动，避免把整页拖出屏幕
    val maxPullPx = refreshTriggerPx * 1.7f
    // 刷新中内容停在这个位置，露出指示器
    val holdPullPx = refreshTriggerPx * 0.68f
    var refreshOffset by remember { mutableFloatStateOf(0f) }
    // 刷新序列是否进行中（触发的瞬间置位，回弹归零后复位）。
    // 圆圈旋转原本只看 state.refreshing：触发后要先弹簧停到指示位（~300ms），
    // 扫描又快时圆圈"露脸"时 refreshing 已经false——用户看到圆圈整个刷新期间
    // 一动不动（v4.6.15 修复）。旋转与最短驻留改由这个"held"状态驱动。
    var refreshHeld by remember { mutableStateOf(false) }
    val isRefreshing = state.refreshing
    val pullScope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    // 回弹用低阻尼 spring：过冲一点再回摆，这才是"Q弹"，直接置 0 是硬切
    suspend fun springOffsetTo(target: Float) {
        Animatable(refreshOffset).animateTo(
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)
        ) { refreshOffset = value }
    }
    val settleTo: (Float) -> Unit = { target ->
        settleJob?.cancel()
        settleJob = pullScope.launch { springOffsetTo(target) }
    }
    // remember 块里的连接体不能直接读 state.refreshing / onReload：会永远拿到首帧的值
    val refreshingState = rememberUpdatedState(state.refreshing)
    val reloadState = rememberUpdatedState(onReload)
    val heldState = rememberUpdatedState(refreshHeld)
    // 统一触发：置 held（圆圈立即开始转）、弹簧停在指示位、驻留到扫描结束
    // 且至少转满一圈，再回弹。慢速松手与甩动两条触发路径都走这里；
    // reloadLibrary 自带 refreshing 去重，双路径先后调用也只刷一次。
    val runRefresh: () -> Unit = {
        settleJob?.cancel()
        refreshHeld = true
        settleJob = pullScope.launch {
            reloadState.value.invoke()
            springOffsetTo(holdPullPx)
            delay(700)
            snapshotFlow { refreshingState.value }.first { !it }
            springOffsetTo(0f)
            refreshHeld = false
        }
    }
    val runRefreshState = rememberUpdatedState(runRefresh)
    // 慢速松手兜底：拖过阈值后缓慢释放不产生 fling，onPreFling 根本不会被调——
    // 位移晾在半空、不触发也不回弹（"圆圈不转"的主根因）。列表停止滚动时统一收尾。
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (inProgress) return@collect
                val off = refreshOffset
                if (off > 0f && !refreshingState.value && !heldState.value) {
                    if (off >= refreshTriggerPx) runRefreshState.value.invoke()
                    else settleTo(0f)
                }
            }
    }

    // 下拉位移与上栏位移合并进同一个连接。
    // 拆成两个 nestedScroll 时，同一段手势会被两边各消化一次：下拉时上栏收到同样的
    // 增量，于是"下拉过程中往上滑，上栏跟着一起飞上去"。合并后每段手势只有一个归属。
    //
    // 上栏位移的驱动源是【列表真正消费掉的位移】(onPostScroll)，不是手势原始增量。
    // 用原始增量会失衡：列表到底/到顶还硬拖时列表不动、上栏却继续走，一段手势下来
    // 上栏比内容多走一截；等滑回顶部时上栏还差这一截没露出来，而顶部的下滑手势已经
    // 归下拉刷新，上栏就永久卡在屏幕外了。按消费量走，隐藏与拉回天然对称。
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta == 0f) return Offset.Zero
                val byFinger = source == NestedScrollSource.UserInput

                // 1) 已经拉出下拉位移：这段手势先把位移收回，不传给列表也不动上栏。
                //    held（刷新序列进行中）时不放行：cancel 会杀掉驱动协程，
                //    位移与 held 状态都会卡在半途。
                if (byFinger && refreshOffset > 0f && delta < 0f && !heldState.value) {
                    settleJob?.cancel()
                    val consumed = delta.coerceAtLeast(-refreshOffset)
                    refreshOffset += consumed
                    return Offset(0f, consumed)
                }

                // 2) 下滑且列表已在顶部：列表没法再吃这段位移，由上栏和下拉接手
                if (delta > 0f && !listState.canScrollBackward) {
                    // 2a) 上栏还没露全：先把它拉回来。这段自己消费掉，
                    //     否则会同时触发列表的 overscroll 拉伸与下拉刷新
                    if (barOffset.value < 0f) {
                        barSettleJob?.cancel()
                        val used = delta.coerceAtMost(-barOffset.value)
                        barOffset.value += used
                        return Offset(0f, used)
                    }
                    // 2b) 上栏已归位：橡皮筋下拉，越拉越沉（惯性阶段不触发刷新）
                    if (byFinger && !heldState.value) {
                        settleJob?.cancel()
                        val resistance = (1f - refreshOffset / maxPullPx).coerceIn(0.15f, 1f)
                        refreshOffset = (refreshOffset + delta * resistance).coerceAtMost(maxPullPx)
                        return Offset(0f, delta)
                    }
                }

                return Offset.Zero // 不消费，列表正常滚动，上栏交给 onPostScroll
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // 上栏跟随列表实际滚动量：上滑滑出、下滑拉回。
                // consumed 只含列表自己吃掉的量（不含 onPreScroll 的消费，也不含 overscroll
                // 拉伸），所以到底/到顶硬拖时上栏不会白走一截。惯性阶段同样计入，
                // 否则 fling 时列表飞很远而上栏停着，松手后两者位置就对不上了。
                val delta = consumed.y
                if (delta == 0f) return Offset.Zero
                if (delta > 0f) {
                    // 下滑露出上栏。区分手指与惯性：
                    // - 手指主动下拉（UserInput）：任何位置都一次性动画完整滑出。
                    //   之前按列表消费量逐帧累加，列表在中间能一直回滚吃掉位移，
                    //   上栏每帧只涨一点，单次手势拉不满——得连拉几次才全露。改成
                    //   检测到手指下拉就 settle 到 0，一拉到底。
                    // - fling 惯性：仅在接近顶部（前两条内）才跟手，否则一次长距离甩动会把
                    //   上栏在列表还远没到顶时就整块拉出来提前遮挡内容。
                    val byFinger = source == NestedScrollSource.UserInput
                    if (byFinger) {
                        if (barOffset.value < 0f && barSettleJob?.isActive != true) settleBarTo(0f)
                    } else if (listState.firstVisibleItemIndex <= 1) {
                        barSettleJob?.cancel()
                        barOffset.value = (barOffset.value + delta).coerceIn(-hideDistanceState.value, 0f)
                    }
                } else {
                    // 上滑隐藏：任何位置都 1:1 收起，保证滚动时不挡内容
                    barSettleJob?.cancel()
                    barOffset.value = (barOffset.value + delta).coerceIn(-hideDistanceState.value, 0f)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (refreshOffset <= 0f) return Velocity.Zero
                if (refreshOffset >= refreshTriggerPx && !heldState.value) {
                    runRefreshState.value.invoke()
                } else if (!refreshingState.value && !heldState.value) {
                    settleTo(0f)
                }
                // 吃掉这段惯性：位移已经消化了手势，再让列表 fling 会突然一跳
                return Velocity(0f, available.y)
            }
        }
    }
    // 回弹全部由 onPreFling 里那个协程负责（先停在指示位、等刷新结束再弹回）。
    // 这里不再放 LaunchedEffect(refreshing)：两个 Animatable 同时驱动同一个位移会互相打架。


    Box(
        Modifier
            .fillMaxSize()
            // 曲库页在展开过程中退到「后面一层」：轻微缩小、变暗、上移，把前景让给播放页。
            // 有了明确的前后关系，横滑就不像翻书页，而像同一块玻璃被推远（进度 1 之前都是
            // 绘制层变换，不参与布局；回到 0 时是恒等变换）。
            .then(
                if (pagerState != null) Modifier.graphicsLayer {
                    val m = pagerState.morphProgress()
                    alpha = (1f - m * 0.72f).coerceIn(0f, 1f)
                    val s = 1f - m * 0.05f
                    scaleX = s
                    scaleY = s
                    translationY = -m * 20.dp.toPx()
                } else Modifier
            )
            // 聚焦监听：仅搜索框聚焦时重启生效。按下落在搜索框外就 clearFocus() 收键盘。
            // 不消费事件——点列表项照常选中/播放，同时收起键盘（输入完点歌的直觉行为）；
            // 点空白处则单纯收键盘。落点在框内不动作，保持编辑。
            .pointerInput(searchFocused) {
                if (!searchFocused) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!searchBounds.value.contains(down.position)) focusManager.clearFocus()
                }
            }
    ) {
        // 推荐候选池：跟随选中文件夹。解释卡用的池必须与打分的池同一个，
        // 否则"排名靠前"的口径会和实际排序对不上。
        // 算在 LazyColumn 之外——LazyListScope 的 content 不是 Composable 上下文，
        // 里面不能用 remember。
        // 6.3.2：首页改成横向分区后，原来为「3 列网格」服务的两个变量（homeChartIds 用来
        // 把榜单从推荐池里挑掉、openHomeChart 用来响应榜卡点击）都失去了调用者——
        // 网格已经不存在，榜单自己独立成一个分区。留着只会是一段永远不执行的代码，删掉。
        // 6.3.2：首页「猜你喜欢」（免登录，纯本地算法）与「每日推荐」入口卡的数据。
        // 这两个值来自 state，但只在这里取一次，避免下面每个分区各自读 state 触发重组。
        val guessSongs = state.homeGuessSongs
        val onPlayGuess: () -> Unit = onPlayHomeGuess

        // 6.3.2：首页分区首次进入时补齐数据。
        //   · 猜你喜欢：全库变了才重算（Recommender 要遍历全库，不能每帧算）；
        //   · 榜单：onNeteaseToplist 走的是弹层（会改 state.showNeteaseToplist），
        //     首页只要数据，所以让 VM 的首页数据加载负责，这里只在"榜单还是空"时补一次。
        LaunchedEffect(Unit) {
            onRefreshHomeGuess()
            // 榜单与推荐歌单：VM 的 loadNeteaseHomeData 内部有磁盘缓存 + 自动重试，
            // 冷启动先铺缓存、网络回来再覆盖，不会让首页停在占位。重复调用是幂等的。
            onLoadNeteaseCharts()
            // 6.3.8：免登录的每日栏目（新歌 / 歌手 / 风格 / 新碟 / 粤语）。
            // 内部按"今天是否已加载"去重，所以这里不用再判空。
            onLoadHomeFeed()
        }

        val recPool = remember(state.allSongs, state.selectedFolders) {
            val sel = state.selectedFolders
            if (sel.isEmpty()) state.allSongs
            else state.allSongs.filter { it.folderPath in sel }
        }
        // ===== 歌单：毛玻璃模糊源，同时作为上栏的折射背板 =====
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (glass) Modifier.junBackdropSource(listBackdrop)
                    else if (frosted) Modifier.haze(state = hazeState, style = HazeStyle(blurRadius = frostedBlur))
                    else Modifier
                )
                // 下拉位移作用在歌单本体上，整列跟着手指走。
                // 放在背板/haze 之后（内层）：录进背板层的是已位移的内容，
                // 玻璃取样时才不会和屏幕上看到的错位。
                .graphicsLayer { translationY = refreshOffset }
                .nestedScroll(scrollConnection),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(
                // 6.6：左右**恢复 22dp**，并且分区内不再叠加边距（HomeSectionSide 已归零）。
                //
                // 三轮演进，写清楚免得再绕回去：
                //   6.4  外层 22 + 分区 16 = 内容在 38dp，网格却 16 → 左重右轻；
                //   6.5  外层 0 + 分区 16 = 全部 16 → 用户："上面太靠边、下面空白太大"；
                //   6.6  外层 22 + 分区 0 = **所有东西同一条线（22dp）**，与上栏搜索框对齐；
                //        底部歌曲行的卡片边缘也在 22dp（行内 14dp 是文字留白）——
                //        和 6.4 完全一致（用户："最底下那个跟原来一样"）。
                start = 22.dp, end = 22.dp,
                // 预留上栏空间（状态栏 + barTopGap + 上栏实际高度），第一条歌单不被遮挡
                // barHeight 由 onSizeChanged 实时校准，预留空间自动跟随
                top = with(density) { statusBarHeightDp.toDp() + barTopGap + barHeight.value.toDp() + 8.dp },
                // 底部常驻播放条会盖住列表末尾：有正在播放的歌时预留它的高度，
                // 保证最后几首也能完整滚动出来、不被播放条遮住。
                bottom = if (state.currentSong != null) 84.dp else 50.dp
            )
        ) {
            // 首页运营横幅：后台「横幅管理」里启用且填了标题/图片的才会下发。
            // 放在最前面，不参与搜索（搜索时让位给结果）。
            if (banners.isNotEmpty() && state.searchQuery.isBlank()) {
                item(key = "banner-strip") { BannerStrip(banners, colors) }
            }

            // ===== 6.3.3 首页分区（按网易云版式重排）=====
            //
            // 排法（上栏与底部小播放器不动）：
            //   ① 推荐歌单 ›   横向 118dp 卡（纯图 + 卡下两行字）
            //   ② 根据你喜爱的歌曲推荐   竖排歌曲行 × 3
            //   ③ 猜你喜欢 ›   横向歌曲卡 96dp
            //   ④ 排行榜 ›     横向卡片，标题压图上
            //   ⑤ 最近播放 ›   横向歌曲卡
            //   ── 全部音乐 ── 长列表，直接接在后面
            //
            // 已删除：顶部「我喜欢 / 本地」那一排快捷胶囊。
            // 原因（你的原话）：与 UI 材质语言不合，且这些入口在「我的」抽屉与上栏都有，
            // 首页再摆一遍属重复。现在逛首页 = 直接看歌单和歌，不再先过一排按钮。
            if (flags.netease && state.searchQuery.isBlank()) {
                item(key = "home-sections") {
                    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp)) {
                        val charts = state.neteaseToplists
                        val recs = state.neteaseRecPlaylists

                        // 6.9.9：首页分区改为**按用户自定义顺序渲染**（搜索栏右侧「自定义」可调）。
                        // 每个分区先算"有没有内容"，空的跳过；分隔线只画在两个实际渲染的分区之间。
                        val favSongs = if (state.neteaseDailyPreview.isNotEmpty()) {
                            state.neteaseDailyPreview
                        } else {
                            guessSongs
                        }
                        val channelList = buildList {
                            HomeFeed.CHANNELS.forEach { ch ->
                                val songs = state.homeChannels[ch.id].orEmpty()
                                if (songs.isNotEmpty()) add(ch.name to songs)
                            }
                            if (state.homeCantonese.isNotEmpty()) add("粤语经典" to state.homeCantonese)
                        }
                        val hasLocalLibrary =
                            state.playlists.isNotEmpty() || state.likedSongs.isNotEmpty()

                        fun sectionVisible(id: String): Boolean = when (id) {
                            "local" -> hasLocalLibrary || (state.neteaseLoggedIn && state.neteaseMyPlaylists.isNotEmpty())
                            "recs" -> true
                            "newsongs" -> state.homeNewSongs.isNotEmpty()
                            "artists" -> state.homeHotArtists.isNotEmpty()
                            "fav" -> favSongs.isNotEmpty()
                            "guess" -> guessSongs.isNotEmpty()
                            "charts" -> charts.isNotEmpty()
                            "channels" -> channelList.isNotEmpty()
                            "albums" -> state.homeNewAlbums.isNotEmpty()
                            "recent" -> state.recentPlaybacks.isNotEmpty()
                            else -> false
                        }

                        // homesongs 是底部独立长列表，不参与上面的分区流，
                        // 否则会多出一个空白 SectionGap；隐藏与否在下面 items 里直接判断。
                        val orderedIds = homeOrder.filter {
                            it != "homesongs" && it !in homeHidden && sectionVisible(it)
                        }
                        orderedIds.forEachIndexed { secIdx, sid ->
                            if (secIdx > 0) SectionGap()
                            when (sid) {
                                "local" -> when {
                                    hasLocalLibrary -> HomeLocalLibrarySection(
                                        playlists = state.playlists,
                                        covers = state.extras.localPlaylistCovers,
                                        likedCover = state.extras.likedPlaylistCover,
                                        likedCount = state.likedSongs.size,
                                        colors = colors,
                                        onOpenLiked = onOpenLocalLiked,
                                        onOpenPlaylist = onOpenLocalPlaylist,
                                        onOpenAll = onOpenPlaylists
                                    )
                                    state.neteaseLoggedIn && state.neteaseMyPlaylists.isNotEmpty() ->
                                        HomeMyPlaylistSection(
                                            myPlaylists = state.neteaseMyPlaylists,
                                            colors = colors,
                                            onOpen = { id, name -> onNeteaseSelectPlaylist(id, name) },
                                            onOpenAll = onNeteasePlaylists
                                        )
                                }

                                "recs" -> HomeRecPlaylistSection(
                                    playlists = recs,
                                    colors = colors,
                                    onOpen = { id, name -> onNeteaseSelectPlaylist(id, name) },
                                    onOpenAll = onNeteasePlaylists
                                )

                                "newsongs" -> HomeDailySongs(
                                    songs = state.homeNewSongs,
                                    colors = colors,
                                    playingId = state.currentSong?.id,
                                    onPlay = { onPlayHomeNewSong(it) },
                                    dayLabel = state.homeFeedDay
                                )

                                "artists" -> HomeArtistRow(
                                    artists = state.homeHotArtists,
                                    colors = colors,
                                    onOpen = { a -> onOpenArtistById(a.id, a.name, a.pic) }
                                )

                                "fav" -> HomeSongRowList(
                                    title = "根据你喜爱的歌曲推荐",
                                    songs = favSongs,
                                    colors = colors,
                                    playingId = state.currentSong?.id,
                                    onPlay = { song -> onPlaySong(song) },
                                    onOpenAll = onNeteaseDaily
                                )

                                "guess" -> HomeSongCardSection(
                                    title = "猜你喜欢",
                                    songs = guessSongs,
                                    colors = colors,
                                    playingId = state.currentSong?.id,
                                    onPlay = { song -> onPlaySong(song) },
                                    onOpenAll = { onPlayGuess() }
                                )

                                "charts" -> HomeChartSection(
                                    charts = charts,
                                    colors = colors,
                                    onOpen = { id, name -> onOpenNeteaseToplist(id, name) },
                                    onOpenAll = onNeteaseToplist
                                )

                                "channels" -> HomeChannelRow(
                                    channels = channelList,
                                    colors = colors,
                                    onPlay = { name, songs -> onPlayChannel(name, songs) }
                                )

                                "albums" -> HomeAlbumGrid(
                                    albums = state.homeNewAlbums,
                                    colors = colors,
                                    onOpen = { al -> onOpenNeteaseAlbum(al.id, al.name) }
                                )

                                "recent" -> HomeRecentSection(
                                    sessions = state.recentPlaybacks,
                                    colors = colors,
                                    onPlay = { rp -> onPlayRecent(rp) },
                                    onOpenAll = onOpenQueue
                                )
                            }
                        }

                        // 兜底：两块网络数据都没回来时给一行轻提示，不留大片空白。
                        if (charts.isEmpty() && recs.isEmpty()) {
                            SectionGap()
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = HomeSectionSide),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = colors.primary,
                                    strokeWidth = 1.8.dp
                                )
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    "正在加载…",
                                    color = colors.subText, fontSize = 12.sp,
                                    modifier = Modifier
                                        .clickable { onLoadNeteaseCharts() }
                                )
                            }
                        }
                    }
                }
            }

            // 当前播放曲目的身份键。绝不能拿 index 和 currentIndex 比：
            // currentIndex 是**播放队列**的下标，而这里是 homeSongs 的下标，
            // 从榜单/歌单/搜索点过歌之后两份列表就不是一个东西，
            // 比下标会把高亮竖杠标到无关的行上（播 A 标 B）。
            val currentKey = state.currentSong?.identityKey
            // 首页歌曲列表：永远显示全部音乐（homeSongs），不被「我喜欢」/歌单替换。
            // 6.3.2：给顶部两块一个稳定 key。
            // 没有 key 时，条件性插入/移除（搜索框有字/无字、横幅下不下发）会让 LazyColumn
            // 把后面所有歌曲行当成"位置变了"，整屏条目一起重新组合——这是"往下滑突然顿一下"。
            // 有 key 之后，只增删这两块，歌曲行原地不动。
            itemsIndexed(
                // 6.6：首页自定义里关掉「底部全部歌曲」时，这一整段长列表彻底不渲染
                // （不是只隐藏行，LazyColumn 里连 item 都不放），滚动到底也不再无限延伸。
                items = if ("homesongs" in homeHidden) state.homeSongs.take(0) else state.homeSongs,
                key = { i, song -> "song-$i-${song.id}" },
                // contentType：告诉 LazyColumn「这些行是同一种」。
                // 没有它时，列表里出现任何新类型（横幅/信息流/最后一条占位）都会让
                // 复用池分裂，滚动过程中频繁"拆掉重搭"行——一行一行的组合成本直接翻倍。
                contentType = { _, _ -> "song-row" }
            ) { index, song ->
                // 6.5.1：底部歌曲行的滚动渐入——慢慢往下滑时，每一行从下方 18dp 处
                // 浮上来 + 从透明到实。只在每行第一次进入屏幕时播一次
                // （rememberSaveable 记在 item key 上，滑回去再滑下来不会重播）。
                HomeRowReveal(songKey = song.id) {
                    SongRow(
                    song = song,
                    active = !select.active && currentKey != null && song.identityKey == currentKey,
                    colors = colors,
                    rowSize = state.rowSize,
                    showCover = state.showRowCover,
                    showArtist = state.showRowArtist,
                    showPlaylist = state.showRowPlaylist,
                    showLikedBadge = state.showLikedBadge,
                    liked = song.id in state.likedSongIds,
                    // 关掉「显示所属歌单」时干脆别算：playlistsOfSong 会在主线程读一次 prefs，
                    // 再把每个歌单的 id 串 split(',') 展开一遍。首页是全库列表，
                    // 这笔开销每帧都在付，而开关关掉以后结果根本没人看。
                    // 6.6：remember 住结果。以前每次重组都重读 prefs + 重新 split——
                    // 进度条每 500ms 刷一次 state，一屏十几行就是每秒几十次 prefs 读。
                    playlistNames = if (state.showRowPlaylist)
                        remember(song.id) { Playlists.playlistsOfSong(song.id) }
                    else emptyList(),
                    selectMode = select.active,
                    isSelected = song.id in select.selectedIds,
                    onClick = {
                        if (select.active) select.onToggle(song.id) else onSelect(index)
                    },
                    onLongPress = {
                        if (select.active) select.onExit() else onAddToPlaylist(song)
                    },
                    onToggleLike = { onToggleLike(song) },
                    onArtistClick = { onOpenArtist(song.artist) }
                    // 6.6：sidePad 恢复默认 14dp。外层 contentPadding 已恢复 22，
                    // 行卡片边缘在 22dp、文字在 36dp——与 6.4 完全一致
                    // （用户："最底下那个还是跟原来一样"）。
                )
                }
            }
        }


        // ===== 快速滚动条：已移到 MainScreen 根层级（见下方），此处移除 =====

        // ===== 下拉刷新指示器 =====
        // 落在「状态栏底部 ↔ 上栏顶边」这段随下拉张开的空隙正中，
        // 所以它不会被跟着下移的上栏压住。
        val refreshProgress = (refreshOffset / refreshTriggerPx).coerceIn(0f, 1f)
        if (refreshOffset > 0.5f) {
            val spin by rememberInfiniteTransition(label = "pullSpin").animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                label = "pullSpinValue"
            )
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        val gap = statusBarHeightDp + with(density) { barTopGap.toPx() } + refreshOffset
                        IntOffset(0, (gap * 0.5f - with(density) { 13.dp.toPx() }).toInt())
                    }
                    .graphicsLayer {
                        alpha = refreshProgress
                        // 到阈值前随进度长大，过阈值轻微过冲，给一点"胀开"的手感
                        val s = 0.6f + 0.5f * refreshProgress
                        scaleX = s; scaleY = s
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(26.dp)) {
                    // 不能写 2.6f * density：外层 LibraryPage 里有个同名的 Density 局部变量，
                    // 会盖住 DrawScope 自己的 density（Float），乘法解析不出来。
                    val stroke = 2.6.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    // 底环
                    drawArc(
                        color = colors.primary.copy(alpha = 0.18f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    )
                    // 进度弧：刷新序列中（held，含扫描已完成但仍在最短驻留期）改成定长弧持续旋转。
                    // 只看 state.refreshing 会因"扫描快于动画"整场不转（v4.6.15 修复）。
                    val spinning = isRefreshing || refreshHeld
                    drawArc(
                        color = colors.primary,
                        startAngle = if (spinning) spin else -90f,
                        sweepAngle = if (spinning) 100f else 360f * refreshProgress,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    )
                }
            }
        }

        // ===== 上栏：覆盖层 =====
        // 整体下移：状态栏下方 barTopGap 处开始，不贴顶部、不盖摄像头
        // tint 压暗/提亮：深色主题压暗、浅色主题提亮，保证与歌单的边界可读性
        // 玻璃模式下上栏走真折射（取样背景 + 歌单），不再叠 haze 与额外 tint
        val barTint = if (colors.isXiaomei) {
            if (colors.isDark) JunPalette.XiaomeiDarkBg.copy(alpha = 0.38f)
            else JunPalette.XiaomeiLightBg.copy(alpha = 0.5f)
        } else if (colors.isDark) Color.Black.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.5f)
        // 折射背板要在 Modifier 链外先算好：glassSurface 是 @Composable，不能写在 .then{} 里
        val barGlass = if (glass) {
            Modifier.glassSurface(
                spec = GlassLevel.CARD.currentSpec(colors),
                shape = JunShape.item,
                backdrops = barBackdrops
            )
        } else Modifier
        Column(
            Modifier
                .fillMaxWidth()
                // 横屏窄化：上栏玻璃与横屏播放卡片同宽（LANDSCAPE_BAR_MAX_WIDTH
                // 已含玻璃外 22dp padding 的补偿）；竖屏 fillMaxWidth 原样。
                .then(
                    if (LocalConfiguration.current.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    ) Modifier.widthIn(max = LANDSCAPE_BAR_MAX_WIDTH) else Modifier
                )
                .align(Alignment.TopCenter)
                .offset(y = with(density) { statusBarHeightDp.toDp() + barTopGap })
                .onSizeChanged { barHeight.value = it.height }
                // 上栏跟着下拉一起走，界面整体是一块在动；
                // 系数 0.55 让它比歌单慢一点，两层之间张开一点视差空隙给指示器
                .graphicsLayer { translationY = barOffset.value + refreshOffset * 0.55f }
        ) {
            // 卡片：毛玻璃背景贴合内容（22dp 水平 padding 内），不超出内容区域
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    // 搜索框需要接收完整点击/拖动，不能由上栏外层统一消费触摸。
                     .then(
                        if (glass) {
                            barGlass
                        } else if (frosted) {
                            Modifier.hazeChild(
                                state = hazeState,
                                style = HazeStyle(blurRadius = frostedBlur, tint = barTint),
                                shape = JunShape.item
                            )
                        } else {
                            Modifier.clip(JunShape.item).background(colors.surface)
                        }
                    )
            ) {
                GlassContent {
                Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                // 标题行
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        // 点软件名「Jun Music」：返回首页（全库视图，不重新加载）
                        modifier = Modifier
                            .junPressable(scaleDown = 0.94f) { onGoHome() }
                            .clip(JunShape.item)
                    ) {
                        // 没人保证它在上栏底上可读。黑白主题的辅色两边都翻车——浅色模式是
                        // 纯白落在白上栏、深色模式是纯黑落在近黑上栏，对比度都在 1.1 以下。
                        // 基准取 colors.surface：实色模式就是上栏底色；玻璃/毛玻璃下底是半透明的，
                        // 实际会更偏背景色，按不透明底算属于偏保守，宁可修多一点。
                        // 用默认 4.5（正文级）而不是大字号的 3.0：黑白主题修到刚过 3.0 只能得到
                        // 一个发灰的 #8D8D8D，标题该有的力度撑不住。
                        val wordPrimary = colors.primary.readableOn(colors.surface)
                        val wordSecondary = colors.secondary.readableOn(colors.surface)
                        Text("Jun", color = wordPrimary, fontSize = 17.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(4.dp))
                        Text("Music", color = wordSecondary, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    }
                    // 顶栏入口：歌单 / 排列方式 / 我的 / 设置（纵向有独立搜索框；首页由标题点击承担）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 6.3：排行榜入口——放在上栏第一枚（最显眼），金色奖杯一眼可辨
                        // 6.7：四枚上栏按钮都能在「自定义中心 → 入口」里单独摘掉，
                        // 连同它们之间的间距一起不画，Row 会自己收窄，不留空位。
                        if (CustomConfig.showRankButton.value) {
                            Box(Modifier.spotlightAnchor(SpotlightKeys.RANK)) {
                                RankButton(onClick = { Haptics.tap(); onOpenRank() }, colors = colors)
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        // 聚光灯锚点：首启引导照亮的四个入口（矩形上报给 SpotlightGuide）
                        if (CustomConfig.showPlaylistButton.value) {
                            Box(Modifier.spotlightAnchor(SpotlightKeys.PLAYLIST)) {
                                PlaylistButton(
                                    onClick = onOpenPlaylists,
                                    colors = colors,
                                    active = state.activePlaylistId != null
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        if (CustomConfig.showLayoutButton.value) {
                            Box(Modifier.spotlightAnchor(SpotlightKeys.LAYOUT)) {
                                LayoutButton(onClick = onCycleLayout, colors = colors)
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        if (CustomConfig.showAvatarButton.value) {
                            Box(Modifier.spotlightAnchor(SpotlightKeys.AVATAR)) {
                                AvatarButton(onClick = onOpenMy, colors = colors)
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.spotlightAnchor(SpotlightKeys.SETTINGS)) {
                            SettingsButton(onClick = onSettings, colors = colors)
                        }
                    }
                }

                // 排序行：上栏的扩展，上栏基本露出后才展开，随上栏收起
                AnimatedVisibility(
                    visible = sortBarVisible,
                    enter = fadeIn(tween(220)) + expandVertically(tween(260, easing = EaseOutCubic)),
                    exit = fadeOut(tween(700)) + shrinkVertically(tween(600, easing = EaseOutCubic))
                ) {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        LibrarySortBar(
                            order = state.sortOrder,
                            direction = state.sortDirection,
                            colors = colors,
                            glass = glass,
                            onOrderChange = {
                                onSortOrder(it)
                                sortInteractionTick++
                            },
                            onDirectionChange = {
                                onSortDirection(it)
                                sortInteractionTick++
                            }
                        )
                    }
                }

                // 搜索框：灰底 + 放大镜图标，简约统一
                // 液态玻璃下改半透明底，否则实色方块会把整条玻璃切成两半
                val searchBg = if (glass) {
                    if (colors.isDark) Color(0x3DFFFFFF) else Color(0x52FFFFFF)
                } else {
                    if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC)
                }
                val iconTint = colors.subText
                AnimatedVisibility(
                    visible = showSearch,
                    enter = fadeIn(tween(220)) + expandVertically(tween(260, easing = EaseOutCubic)),
                    exit = fadeOut(tween(180)) + shrinkVertically(tween(220, easing = EaseOutCubic))
                ) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                // 搜索入口：点击展开「全屏搜索页」（历史 / 热搜 / 在线结果）。
                // 原「输入即过滤」改由搜索页统一驱动，上栏只保留与原先一致的入口外观。
                // 6.9.9：搜索条右侧加「自定义」小按钮（首页分区排序/删除入口）
                Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .spotlightAnchor(SpotlightKeys.SEARCH)
                        .junPressable(scaleDown = 0.985f) { onOpenSearchPage() }
                        .clip(JunShape.item)
                        .background(searchBg)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(15.dp)) {
                            val r = size.minDimension * 0.32f
                            // 圆心略微偏左上，让手柄指向右下，视觉居中
                            val cx = size.width * 0.38f
                            val cy = size.height * 0.38f
                            drawCircle(
                                color = iconTint,
                                radius = r,
                                center = Offset(cx, cy),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.12f)
                            )
                            drawLine(
                                color = iconTint,
                                start = Offset(cx + r * 0.7f, cy + r * 0.7f),
                                end = Offset(size.width * 0.88f, size.height * 0.88f),
                                strokeWidth = size.minDimension * 0.12f,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            state.searchQuery.ifBlank { "搜索歌曲、艺术家…" },
                            color = if (state.searchQuery.isBlank()) colors.subText else colors.text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .height(40.dp)
                        .junPressable(scaleDown = 0.92f) { onOpenHomeCustomize() }
                        .clip(JunShape.item)
                        .background(searchBg)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "自定义",
                        color = colors.subText,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                }
                    }
                }

                // 计数：启动后显示 4 秒自动隐藏（showCount 由根层级控制），淡出后收回高度
                AnimatedVisibility(
                    visible = showCount,
                    enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                    exit = fadeOut(tween(400)) + shrinkVertically(tween(400))
                ) {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        val countLabel = when {
                            state.searchQuery.isNotBlank() -> "搜索结果 · ${state.homeSongs.size} 首"
                            state.selectedFolders.isNotEmpty() -> "${state.homeSongs.size} 首 · 已选 ${state.selectedFolders.size} 个文件夹"
                            else -> "${state.homeSongs.size} 首"
                        }
                        Text(
                            countLabel,
                            color = colors.subText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentWidth(Alignment.CenterHorizontally)
                        )
                        // 首页「播放全部」：播放当前显示的全部歌曲（homeSongs）
                        if (state.homeSongs.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier
                                    .wrapContentWidth()
                                    .align(Alignment.CenterHorizontally)
                                    .junPressable(scaleDown = 0.92f) { onPlayAll() }
                                    .clip(JunShape.item)
                                    .background(colors.primary.copy(alpha = 0.15f))
                                    .padding(horizontal = 18.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    "播放全部",
                                    color = colors.primary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
            }
            }
        }
    }
}

// ==================== 播放器页 ====================

@Composable
private fun PlayerPage(
    state: PlayerUiState,
    colors: JunColors,
    viewModel: PlayerViewModel,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleLike: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenQueue: () -> Unit,
    onDownload: (Song) -> Unit = {},
    /** 均衡器曲线是否非全平，决定副控制行里那枚推子图标点不点亮 */
    eqActive: Boolean,
    /** 总后台功能开关：下载 / 评论入口按开关显隐 */
    flags: com.jun.musiccloud.data.AppFlags = com.jun.musiccloud.data.AppFlags(),
    /** 曲库页 ↔ 播放页的分页状态：只为读「连续展开进度」，驱动与底部播放条的形变交接 */
    pagerState: PagerState
) {
    // 歌词弹层状态
    var showLyrics by remember { mutableStateOf(false) }
    var lyricLines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricLoading by remember { mutableStateOf(false) }

    // 歌词层比根层级的那些弹层更靠内：它开着时先关它，不要退出应用。
    // 这个 BackHandler 注册得比 MainScreen 根部那个晚，因此优先级更高，
    // 正好匹配它在视觉上更靠上的层级。
    BackHandler(enabled = showLyrics) { showLyrics = false }
    val scope = rememberCoroutineScope()
    // 切歌时刷新评论数角标（内部带缓存，失败静默为 0）
    LaunchedEffect(state.currentSong?.songId) {
        viewModel.loadCommentCount(state.currentSong?.songId ?: "")
    }
    // 歌词层当前显示的歌词属于哪个文件：用于"开着歌词层切歌"时判断需要重新加载。
    var lyricPath by remember { mutableStateOf<String?>(null) }
    // 歌词层开着时自动切歌：旧词必须跟着走，否则整屏还停在上一首。
    // 路径一变先清空再按新歌重解析（LyricParser 自带缓存，重复加载只走内存）。
    LaunchedEffect(showLyrics, state.currentSong?.lyricKey, state.durationMs) {
        val path = state.currentSong?.lyricKey
        if (showLyrics && !path.isNullOrBlank() && path != lyricPath) {
            lyricLines = emptyList()
            lyricLoading = true
            try {
                lyricLines = withContext(Dispatchers.IO) {
                    LyricParser.loadLyrics(path, state.durationMs)
                }
                lyricPath = path
            } catch (_: Exception) {
            } finally {
                lyricLoading = false
            }
        }
    }
    // 封面顶部歌词：显示在卡片上方的空白区，与封面内歌词互不影响，可单独开启。
    var topLyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    LaunchedEffect(state.coverTopLyric, state.currentSong?.lyricKey, state.durationMs) {
        if (!state.coverTopLyric) { topLyrics = emptyList(); return@LaunchedEffect }
        val path = state.currentSong?.lyricKey
        topLyrics = if (path.isNullOrBlank()) emptyList()
        else withContext(Dispatchers.IO) {
            runCatching { LyricParser.loadLyrics(path, state.durationMs) }.getOrDefault(emptyList())
        }
    }
    // 歌词弹层毛玻璃：PlayerPage 作为模糊源，弹层作为模糊子层
    val lyricsHazeState = remember { HazeState() }
    val glass = isLiquidGlass
    val frosted = isFrostedGlass
    val frostedBlur = frostedBlurRadius

    // 歌词层浮在播放卡片之上，要折射「背景 + 卡片」两层。卡片单独录一层背板，
    // 歌词层是它的兄弟节点，取样方向单向，不会互相触发重绘。
    val cardBackdrop = rememberJunBackdrop()
    val outerBackdrops = LocalJunBackdrops.current
    val lyricsBackdrops = remember(glass, outerBackdrops, cardBackdrop) {
        if (glass) outerBackdrops + cardBackdrop else emptyList()
    }

    // 点击封面：读取歌词（内嵌优先，其次同名 .lrc）并弹出
    // try-catch 防止文件IO异常崩溃；filePath 非空检查避免无效路径
    val onClickArtwork: () -> Unit = {
        val song = state.currentSong
        if (song != null && song.lyricKey.isNotBlank()) {
            scope.launch {
                lyricLoading = true
                try {
                    val lines = withContext(Dispatchers.IO) {
                        LyricParser.loadLyrics(song.lyricKey, state.durationMs)
                    }
                    lyricLines = lines
                    lyricPath = song.lyricKey
                    showLyrics = true
                } catch (_: Exception) {
                    // 文件读取异常（路径不存在、权限问题等）静默忽略
                } finally {
                    lyricLoading = false
                }
            }
        }
    }

    // ===== 与底部播放条的连续形变交接 =====
    // 分页器是横向的（架构所限：纵向分页套纵向列表会让列表滚不动），但如果让它原样
    // "从右边滑进来"，观感就是翻了一页。这里把同一个展开进度读进绘制层：播放页以底边为轴
    // 从 0.93 长回 1、顺势升起并点亮，播放条在同一进度上反向塌下去——横移仍在，但主导
    // 运动变成"长大"，于是读起来是同一个播放器被顶开，而不是切到下一页。
    // 到位时（进度 1）缩放 =1、位移 =0、alpha=1，是恒等变换，静态布局零影响。
    Box(Modifier
        .fillMaxSize()
        .graphicsLayer {
            val m = pagerState.morphProgress()
            alpha = (0.25f + 0.75f * m).coerceIn(0f, 1f)
            val s = 0.93f + 0.07f * m
            scaleX = s
            scaleY = s
            translationY = (1f - m) * 36.dp.toPx()
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
        }
    ) {
        // 播放卡片：毛玻璃模糊源 / 歌词层的折射背板
        // 横屏窄化只在 PlayerCard 内部单层封顶（LANDSCAPE_PLAYER_MAX_WIDTH），
        // 外层不再封顶——双层套娃会把卡片压得比封顶值还窄一圈，
        // 和 CAROUSEL/STACK 横屏的宽度对不齐。列本身水平居中即可。
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 26.dp)
                .then(
                    if (glass) Modifier.junBackdropSource(cardBackdrop)
                    else if (frosted) Modifier.haze(state = lyricsHazeState, style = HazeStyle(blurRadius = frostedBlur))
                    else Modifier
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.fillMaxWidth()) {
            // 6.9.0：加载中"问号"——解释四层取源逻辑，为什么有的歌快有的慢
            var showLoadHelp by remember { mutableStateOf(false) }
            PlayerCard(
                title = state.currentSong?.title ?: "未选择",
                artist = state.currentSong?.artist ?: "",
                onArtistClick = state.currentSong?.artist?.takeIf { it.isNotBlank() }?.let {
                    { viewModel.openArtistByName(it) }
                },
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                shuffle = state.shuffle,
                repeatMode = state.repeatMode,
                artworkPath = state.currentSong?.filePath,
                lyricKey = state.currentSong?.lyricKey,
                progress = state.progress,
                isPlaying = state.isPlaying,
                onToggle = onToggle,
                onPrev = onPrev,
                onNext = onNext,
                onSeek = onSeek,
                onCyclePlayMode = onCyclePlayMode,
                onToggleLike = {
                    state.currentSong?.let { onToggleLike(it.id) }
                },
                liked = state.currentSong?.id in state.likedSongIds,
                onClickArtwork = onClickArtwork,
                colors = colors,
                // 宽度完全交给 PlayerCard 内部决定（横屏封顶居中 / 竖屏铺满）。
                // 这里绝不能传 fillMaxWidth——它在 widthIn(max) 之前设置
                // minWidth=屏宽，封顶会被抵消，横屏卡片又变回整屏宽。
                modifier = Modifier,
                sleepTimerMs = state.sleepTimerMs,
                sleepTimerEndMs = state.sleepTimerEndMs,
                onOpenSleepTimer = onOpenSleepTimer,
                onOpenEqualizer = onOpenEqualizer,
                onOpenQueue = onOpenQueue,
                onDownload = { state.currentSong?.let { onDownload(it) } },
                showDownload = flags.download,
                onSimiSongs = { viewModel.openSimiSongs() },
                onOpenComments = if (flags.comments) {
                    { state.currentSong?.let { viewModel.openComments(it) } }
                } else null,
                commentCount = state.currentCommentCount,
                eqActive = eqActive,
                audioFormat = state.qualityBadge,
visualizerEnabled = state.visualizerEnabled,
                   qualityFlash = state.qualityFlash,
                   playbackSpeedBp = state.playbackSpeedBp,
                   tiltSpectrum = state.tiltSpectrum,
                   coverShakeEnabled = state.coverShake,
                   coverLyricEnabled = state.coverLyric,
                   loadingText = state.loadText,
                   loadingBuffered = state.loadBuffered,
                   loadCanRetry = state.loadCanRetry,
                   onLoadHelp = { showLoadHelp = true },
                   onRetryLoad = { viewModel.retryLoading() },
                   onToggleVisualizer = { viewModel.setVisualizerEnabled(!state.visualizerEnabled) }
              )
            // 6.9.0：为什么有的歌要等？——四层取源逻辑说明
            // 6.9.4：文案与卡片/横屏共用一份，避免三处各写一版
            if (showLoadHelp) LoadHelpDialog { showLoadHelp = false }
            // 封面顶部歌词：浮在卡片正上方的空白区，不参与布局，不会把卡片挤动。
            if (state.coverTopLyric) {
                val topIdx = LyricParser.currentIndex(topLyrics, state.positionMs)
                val topText = if (topIdx in topLyrics.indices) topLyrics[topIdx].text else ""
                TopLyricLine(
                    text = topText,
                    colors = colors,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-58).dp)
                        .padding(horizontal = 4.dp)
                )
            }
            }
        }

        // 横屏沉浸入口只保留在歌词层右上角；播放页不再浮按钮，避免遮挡封面/歌词。

        // 歌词弹层：毛玻璃背景 + 滚动歌词
        if (showLyrics) {
            LyricsOverlay(
                lines = lyricLines,
                loading = lyricLoading,
                positionMs = state.positionMs,
                livePosition = viewModel::livePositionMs,
                durationMs = state.durationMs,
                title = state.currentSong?.title ?: "",
                artist = state.currentSong?.artist ?: "",
                colors = colors,
                hazeState = lyricsHazeState,
                backdrops = lyricsBackdrops,
                onSeek = onSeek,
                lyricAlign = state.lyricAlign,
                lyricFont = state.lyricFont,
                lyricFontSize = state.lyricFontSize,
                karaoke = state.karaokeLyric,
                stage = state.lyricStage,
                lyricMotion = state.lyricMotion,
                lyricDisplayMode = state.lyricDisplayMode,
                lyricContext = state.lyricContext,
                lyricQuality = state.lyricQuality,
                lyricBeatFx = state.lyricBeatFx,
                lyricParticles = state.lyricParticles,
                lyricBassGlow = state.lyricBassGlow,
                lyricChorusFx = state.lyricChorusFx,
                lyricChorusBoost = state.lyricChorusBoost,
                lyricCoverTint = state.lyricCoverTint,
                lyricTintStrength = state.lyricTintStrength,
                lyricOffsetMs = state.lyricOffsetMs +
                    (state.lyricSongOffsets[state.currentSong?.id ?: -1L] ?: 0),
                lyricLineSpacing = state.lyricLineSpacing,
                lyricLetterSpacing = state.lyricLetterSpacing,
                lyricWeight = state.lyricWeight,
                lyricOutline = state.lyricOutline,
                lyricTexture = state.lyricTexture,
                lyricGlowStyle = state.lyricGlowStyle,
                lyricGlowStrength = state.lyricGlowStrength,
                lyricCoverDepth = state.lyricCoverDepth,
                lyricTuning = state.lyricTuning,
                lyricStageProfile = state.lyricStageProfile,
                lyricFocus = state.lyricFocus,
                lyricBeatCamera = state.lyricBeatCamera,
                lyricBeatCameraStrength = state.lyricBeatCameraStrength,
                lyricLongFormMode = state.lyricLongFormMode,
                lyricVideoUri = state.lyricVideoUri,
                lyricVideoAlpha = state.lyricVideoAlpha,
                lyricSemanticWrap = state.lyricSemanticWrap,
                reduceMotion = state.reduceMotion,
                showFrameStats = state.lyricFrameStats,
                isPlaying = state.isPlaying,
                artworkKey = state.currentSong?.filePath.orEmpty(),
                showTranslation = state.lyricTranslation,
                showRomanization = state.lyricRomanization,
                onCycleStage = { viewModel.setLyricStage(LyricStageCatalog.next(state.lyricStage)) },
                onDismiss = { showLyrics = false }
            )
        }
    }
}

/** 封面顶部歌词：大字单行/双行，换句时淡出淡入（低版本无 blur 时只做透明度过渡）。 */
@Composable
private fun TopLyricLine(text: String, colors: JunColors, modifier: Modifier = Modifier) {
    var displayText by remember { mutableStateOf(text) }
    val visibility = remember { Animatable(1f) }
    LaunchedEffect(text) {
        if (displayText == text) return@LaunchedEffect
        if (displayText.isEmpty() || text.isEmpty()) { displayText = text; return@LaunchedEffect }
        visibility.animateTo(0f, tween(200))
        displayText = text
        visibility.animateTo(1f, tween(320))
    }
    if (displayText.isBlank()) return
    val onPage = if (colors.isDark) Color.White else Color.Black
    Text(
        displayText,
        color = onPage.copy(alpha = 0.94f),
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        lineHeight = 24.sp,
        modifier = modifier
            .graphicsLayer { alpha = visibility.value }
            .blur(((1f - visibility.value) * 5f).dp)
    )
}

/** 全屏歌词覆盖层：毛玻璃背景，当前行高亮，随播放进度滚动 */
// 歌词专用缓动：起步利落、尾部长长地缓缓收住（easeOutQuint 风格贝塞尔）。
// 比对称的 EaseInOutCubic 更"丝滑"——换行不是机械地加速又减速，
// 而是干脆起步后优雅滑停，这是苹果那类歌词页顺滑感的关键。
private val LyricEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private const val LYRIC_ANIM_MS = 620

@Composable
internal fun LyricsOverlay(
    lines: List<LyricLine>,
    loading: Boolean,
    positionMs: Long,
    /**
     * 实时播放位置（毫秒，**不含**歌词时间偏移）。歌词高亮与逐字扫光都直接读它，
     * 不再依赖 [positionMs] 那份 500ms 才更新一次的采样。默认退回 [positionMs]，
     * 便于悬浮窗等拿不到控制器的调用点照常工作。
     */
    livePosition: () -> Long = { positionMs },
    durationMs: Long,
    title: String,
    artist: String,
    colors: JunColors,
    hazeState: HazeState,
    backdrops: List<JunBackdrop>,
    onSeek: (Float) -> Unit,
    lyricAlign: Int = 1,
    lyricFont: Int = 0,
    lyricFontSize: Float = 20f,
    karaoke: Boolean = true,
    /** 全屏歌词舞台：0=流光 … 13=静止，共 14 种 Folia 视觉模式 */
    stage: Int = 0,
    /** 歌词运动风格：0=经典扫光 1=弹跳 2=玻璃 3=漂浮 4=轻快 5=流光 6=故障 */
    lyricMotion: Int = 1,
    /** 显示模式：0=单行 1=双行 2=三行 3=影院 4=自定义 */
    lyricDisplayMode: Int = 2,
    /** 自定义模式下的上下文行数 0..6 */
    lyricContext: Int = 2,
    /** 渲染品质：0=自动 1=流畅 2=均衡 3=精致 */
    lyricQuality: Int = 0,
    /** 节拍律动：低频/鼓点驱动字号呼吸、光晕与故障抖动 */
    lyricBeatFx: Boolean = true,
    /** 星河粒子 */
    lyricParticles: Boolean = true,
    /** 低频光晕 */
    lyricBassGlow: Boolean = true,
    /** 副歌识别与副歌氛围 */
    lyricChorusFx: Boolean = true,
    lyricChorusBoost: Float = 0.7f,
    /** 封面取色联动 */
    lyricCoverTint: Boolean = false,
    lyricTintStrength: Float = 0.65f,
    /** 歌词时间校准（正数=歌词延后） */
    lyricOffsetMs: Int = 0,
    /** 制作人员折叠卡 */
    /** 排版 */
    lyricLineSpacing: Float = 1.18f,
    lyricLetterSpacing: Float = 0f,
    lyricWeight: Int = 1,
    lyricOutline: Float = 0f,
    lyricTexture: Int = 0,
    /** 逐词发光 */
    lyricGlowStyle: Int = 1,
    lyricGlowStrength: Float = 0.6f,
    /** 封面纵深 + 涟漪 */
    lyricCoverDepth: Boolean = false,
    /** 当前舞台调参 */
    lyricTuning: List<Int> = listOf(50, 50, 50, 50),
    /** 当前舞台专属调校：字号/动势/辉光/形态 */
    lyricStageProfile: StageProfile = StageProfile.Default,
    /** 焦点线位置（0.24..0.62，越大越靠下） */
    lyricFocus: Float = 0.38f,
    /** 节拍电影镜头 */
    lyricBeatCamera: Boolean = true,
    lyricBeatCameraStrength: Float = 0.5f,
    /** 长音频模式：0=关 1=播客 2=DJ */
    lyricLongFormMode: Int = 0,
    /** 本地视频背景 */
    lyricVideoUri: String = "",
    lyricVideoAlpha: Float = 0.42f,
    /** 中文语义断行 */
    lyricSemanticWrap: Boolean = true,
    /** 减少动效：关闭粒子、低频光晕、副歌氛围与节拍镜头 */
    reduceMotion: Boolean = false,
    /** 歌词层帧率诊断 */
    showFrameStats: Boolean = false,
    isPlaying: Boolean = false,
    /** 专辑封面加载键（在线为封面 URL，本地为 SAF 封面 URI），供莫奈/镜台/时计取图 */
    artworkKey: String = "",
    /** 显示翻译轨 */
    showTranslation: Boolean = true,
    /** 显示罗马音/音译轨 */
    showRomanization: Boolean = false,
    /** 切换舞台样式 */
    onCycleStage: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val overlayLandscape = rememberIsLandscape()
    val overlayCtx = LocalContext.current
    // 动效总控：品质先解析成具体档位，节拍引擎整层只建一个（舞台 0–13 共用）。
    val quality = rememberLyricQuality(lyricQuality)
    val motionStyle = LyricMotionStyle.of(lyricMotion)
    val contextLimit = LyricDisplayMode.limit(lyricDisplayMode, lyricContext)
    // 减少动效：保留歌词本体的排版与逐词点亮，只收掉自发的运动层，
    // 包括粒子、低频光晕、副歌氛围、节拍镜头与弹跳/漂浮等运动风格。
    val effMotion = if (reduceMotion) LyricMotionStyle.SWEEP else motionStyle
    val effParticles = lyricParticles && !reduceMotion
    val effBassGlow = lyricBassGlow && !reduceMotion
    val effBeatFx = lyricBeatFx && !reduceMotion
    val effChorusFx = lyricChorusFx && !reduceMotion
    val effChorusBoost = if (reduceMotion) 0f else lyricChorusBoost
    val effBeatCamera = lyricBeatCamera && !reduceMotion
    val beat = rememberLyricBeat(effBeatFx)
    // 舞台专属字号：和 14 个舞台共用同一套 profile，行为一致。
    val stageFontSize = lyricFontSize * lyricStageProfile.fontScale
    val focusFraction = lyricFocus.coerceIn(0.24f, 0.62f)
    val longForm = lyricLongFormMode != 0
    // 时间校准：全局/单曲偏移统一在这里加，舞台与列表读同一个时间轴。
    // 时间轴来源是「直接问播放器」+ 句起点精确唤醒，不再自己按墙上时间外推。
    // 6.6：**删掉按歌曲时长「校准」整条时间轴这一步**（fitToDuration 已停用）。
    // 按比例拉伸会改变演唱速率、误差随播放线性累积，是「越唱越偏」的主因。
    // 版本一致性由数据层的版本指纹保证；这里只保留下面 liveMs 的整体平移 offset。
    val timedLines = lines
    val shownLines = timedLines
    val lyricBounds = remember(timedLines) { timedLines.map { it.timeMs } }
    val liveMs = remember(livePosition, lyricOffsetMs) {
        // 6.9.11 方向修正：设置页文案「正数=歌词延后」，
        // 旧实现却是 livePosition()+offset —— 正值反而让歌词提前亮，
        // 用户照文案调，方向永远反着来，怎么调都觉得不齐。统一为 -offset。
        { (livePosition() - lyricOffsetMs).coerceAtLeast(0L) }
    }
    val playbackMs = rememberLyricPlayheadMs(liveMs, isPlaying, lyricBounds)
    val typography = LyricTypography(
        lineSpacing = lyricLineSpacing,
        letterSpacingEm = lyricLetterSpacing,
        weightIndex = lyricWeight,
        outline = lyricOutline,
        texture = lyricTexture
    )
    // 封面取色：只在有封面且开关打开时生效，取不到就完全沿用原主题。
    val coverTintColor = rememberCoverTint(artworkKey, lyricCoverTint, lyricTintStrength)
    val stageColors = if (coverTintColor != null) {
        colors.copy(
            primary = androidx.compose.ui.graphics.lerp(
                colors.primary, coverTintColor, coverTintColor.alpha
            )
        )
    } else colors
    // 横屏沉浸：歌词层自己藏系统栏；关闭时顺手回竖屏。
    if (overlayLandscape) HideSystemBarsForImmersive()
    val dismissOverlay: () -> Unit = {
        if (overlayLandscape) overlayCtx.findActivity()?.exitLandscapeMode()
        onDismiss()
    }
    BackHandler { dismissOverlay() }
    // 歌词层打开期间屏幕常亮：横屏/竖屏长时间不操作也不会被系统压暗成一层淡玻璃。
    val overlayView = LocalView.current
    // R13 老机适配：常亮只在「真的在前台」时挂，离开前台就撤。
    // 以前是无条件挂、只有组合退出才撤：歌词层开着时锁屏，窗口还在要求亮屏——
    // "系统要熄屏 / 窗口要亮屏"这对矛盾在 EMUI 10（nova 6 4.2.0.121）上会重弹一次 Keyguard，
    // 用户描述的正是"播放时闪一下到输入开屏密码那一屏"。老机安全档下默认根本不挂常亮。
    // LocalLifecycleOwner 必须在组合期取（@Composable accessor），DisposableEffect 块里拿不到。
    val keepLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        overlayView.keepScreenOn =
            com.jun.musiccloud.ui.theme.DevicePerformance.allowKeepScreenOn &&
                keepLifecycle.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        val keepObs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> overlayView.keepScreenOn =
                    com.jun.musiccloud.ui.theme.DevicePerformance.allowKeepScreenOn
                else -> overlayView.keepScreenOn = false
            }
        }
        keepLifecycle.lifecycle.addObserver(keepObs)
        onDispose {
            keepLifecycle.lifecycle.removeObserver(keepObs)
            overlayView.keepScreenOn = false
        }
    }
    if (stage != 0) {
        Box(Modifier.fillMaxSize()) {
        // 舞台切换不再硬切：220ms 交叉淡化，旧舞台的余像会自然化开。
        Crossfade(
            targetState = stage,
            animationSpec = tween(220),
            label = "lyricStageFade"
        ) { currentStage ->
            AlternateLyricStage(
                stage = currentStage,
                lines = lines,
                loading = loading,
                positionMs = playbackMs,
                livePosition = liveMs,
                durationMs = durationMs,
                title = title,
                artist = artist,
                colors = stageColors,
                onSeek = onSeek,
                lyricAlign = lyricAlign,
                lyricFont = lyricFont,
                lyricFontSize = lyricFontSize,
                karaoke = karaoke,
                motion = effMotion,
                displayMode = LyricDisplayMode.of(lyricDisplayMode),
                contextLimit = contextLimit,
                quality = quality,
                beat = beat,
                particles = effParticles,
                bassGlow = effBassGlow,
                typography = typography,
                glowStyle = lyricGlowStyle,
                glowStrength = lyricGlowStrength,
                chorusFx = effChorusFx,
                chorusStrength = effChorusBoost,
                coverDepth = lyricCoverDepth,
                tuning = lyricTuning,
                stageProfile = lyricStageProfile,
                longForm = longForm,
                semanticWrap = lyricSemanticWrap,
                videoUri = lyricVideoUri,
                videoAlpha = lyricVideoAlpha,
                playing = isPlaying,
                beatCamera = effBeatCamera,
                beatCameraStrength = lyricBeatCameraStrength,
                showTranslation = showTranslation,
                showRomanization = showRomanization,
                artworkKey = artworkKey,
                onCycleStage = onCycleStage,
                onDismiss = dismissOverlay
            )
        }
        if (showFrameStats) {
            LyricFrameStatsOverlay(
                colors,
                Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 16.dp)
            )
        }
        }
        return
    }
    val chorusFlags = remember(shownLines) { LyricChorus.detect(shownLines) }
    val accentColor = stageColors.primary
    val listState = rememberLazyListState()
    val current = LyricRuntime.activeIndex(shownLines, playbackMs)
    // 当前句及后几句的 grapheme 时间线/语义布局单位先算好，切句不卡帧。
    LaunchedEffect(current, shownLines, quality) {
        LyricRuntime.preheat(shownLines, current, quality.preheat, durationMs)
    }
    // 常驻预构建：整首歌词在后台一次性铺完，滚动到任意位置都不再分词。
    LaunchedEffect(shownLines, durationMs) {
        withContext(Dispatchers.Default) {
            LyricRenderEngine.residentBuild(shownLines, durationMs)
        }
    }
    // 关键词着色：整首歌重复出现的短语，不写死词表。
    val keywordRules = remember(shownLines, accentColor, stageColors.secondary) {
        LyricKeywordStyler.buildPalette(shownLines, accentColor, stageColors.secondary)
    }
    val colAlign = when (lyricAlign) { 0 -> Alignment.Start; 2 -> Alignment.End; else -> Alignment.CenterHorizontally }
    val boxAlign = when (lyricAlign) { 0 -> Alignment.CenterStart; 2 -> Alignment.CenterEnd; else -> Alignment.Center }
    val textAlign = when (lyricAlign) { 0 -> TextAlign.Start; 2 -> TextAlign.End; else -> TextAlign.Center }
    val lyricFamily = fontFamilyForIndex(lyricFont)
    // 横屏：高度矮，且刘海/挖孔多在左侧——靠左时给更大的左边距避开，
    // 同时压缩列表上下留白、隐藏底部提示，把纵向空间尽量还给歌词。
    val isLand = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val onCard = if (colors.isDark) Color.White else Color.Black
    val glass = isLiquidGlass
    val frosted = isFrostedGlass
    val frostedBlur = frostedBlurRadius
    val scrim = if (glass) {
        if (colors.isDark) Color(0x8C121218) else Color(0xA6F4F4F8)
    } else {
        if (colors.isDark) Color(0xB3121218) else Color(0xCCF4F4F8)
    }
    val lyricsGlass = if (glass) {
        Modifier.glassSurface(
            spec = GlassLevel.SHEET.currentSpec(colors),
            shape = RectangleShape,
            backdrops = backdrops
        )
    } else Modifier
    // ---- 侧边时间标记：垂直滚动歌词时，在焦点线一侧冒出一个迷你时间戳，
    // 标记"滚到的这一句"对应的时间点；停手后淡出。跟随对齐方向贴在左/右侧。
    val scrubScope = rememberCoroutineScope()
    var scrubHideJob by remember { mutableStateOf<Job?>(null) }
    var showTimeTag by remember { mutableStateOf(false) }
    // 焦点线附近的行：取视口内最靠近焦点线的可见行，它就是"滚到的这一句"
    val focusIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val vis = info.visibleItemsInfo
            if (vis.isEmpty()) return@derivedStateOf -1
            val focusFrac = focusFraction
            val focusPx = info.viewportStartOffset +
                (info.viewportEndOffset - info.viewportStartOffset) * focusFrac
            vis.minByOrNull { kotlin.math.abs((it.offset + it.size / 2f) - focusPx) }?.index ?: -1
        }
    }
    val scrolling = listState.interactionSource.collectIsDraggedAsState().value
    // 用户手动拖动期间【暂停自动跟踪】，停手 900ms 后再交还给播放。
    // 之前没有这一层：手指正在拖着列表，换行时的 animateScrollBy 同时在跑，
    // 两股力打在同一个滚动位置上——表现为"拖不动 / 拖到一半被拽回去"。
    var holdFollow by remember { mutableStateOf(false) }
    var followResumeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(scrolling) {
        if (scrolling) {
            holdFollow = true
            scrubHideJob?.cancel()
            showTimeTag = true
        } else {
            scrubHideJob?.cancel()
            scrubHideJob = scrubScope.launch {
                delay(900)
                showTimeTag = false
                holdFollow = false
                followResumeTick++   // 触发一次"归位"，把手借走的列表软软地送回焦点线
            }
        }
    }
    // 跟踪：当前行始终滑向焦点线。
    //
    // 6.9.7 重写（用户三次反馈"一抖一抖 / 一跳一跳"）：之前用的是
    // spring(ζ=1.04, k=180)——收敛时间约 1s，比大多数句子还长。于是每一句都在
    // "上一段还没走完"时被新目标打断，animateScrollBy 从当前位置重新起算，
    // 肉眼看到的是一列被反复拽动的字，而不是匀速滚动。
    // 现在改成定长 260ms 的 tween（几乎无过冲、到点即停），并且：
    //   ① 目标不在屏内时先 scrollToItem 定位，再用同一条 260ms 曲线收尾；
    //   ② 计算目标前等一帧布局（withFrameNanos），确保读到的是换句后的稳定行高，
    //      否则会拿新行的旧 offset 去滚，滚完再被下一帧纠正——这就是"跳一下"。
    //   ③ 位移小于 1px 直接跳过，避免 60Hz 下反复启动一个 0 距离动画。
    LaunchedEffect(current, holdFollow, followResumeTick) {
        if (current < 0) return@LaunchedEffect
        if (holdFollow) return@LaunchedEffect
        val spec = tween<Float>(
            durationMillis = if (JunMotion.reduced) 150 else 260,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        )
        withFrameNanos { } // 等换句后的布局落定
        // 焦点线：视口内 38% 高度处（横屏空间矮，放到 42% 稍低一点避免贴顶）
        fun focusLine(): Float {
            val info = listState.layoutInfo
            return info.viewportStartOffset +
                (info.viewportEndOffset - info.viewportStartOffset) * focusFraction
        }
        var item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
        if (item == null) {
            listState.scrollToItem(current)
            withFrameNanos { }
            item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
                ?: return@LaunchedEffect
        }
        val delta = item.offset + item.size / 2f - focusLine()
        if (kotlin.math.abs(delta) > 1f) listState.animateScrollBy(delta, spec)
    }
    // 封面纵深交互：落指涟漪 + 拖动视差；关闭纵深时整体不响应。
    val coverRipple = rememberLyricRippleState()
    var coverShift by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val depthOn by rememberUpdatedState(lyricCoverDepth)
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (glass) {
                    lyricsGlass
                } else if (frosted) {
                    Modifier.hazeChild(
                        state = hazeState,
                        style = HazeStyle(blurRadius = frostedBlur, tint = scrim),
                        shape = JunShape.item
                    )
                } else {
                    Modifier.background(scrim)
                }
            )
            // 点空白处关闭（歌词行自己会消费点击去 seek，不会冒泡到这里）
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { dismissOverlay() }
            .statusBarsPadding()
            .navigationBarsPadding()
            .displayCutoutPadding()
            // 放在安全区修饰符之后：触点坐标与涟漪 Canvas 的绘制原点一致。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (!depthOn) continue
                        when (event.type) {
                            PointerEventType.Press -> coverRipple.add(change.position)
                            PointerEventType.Move -> {
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                                coverShift = ((change.position - center) / w * 26f).let {
                                    androidx.compose.ui.geometry.Offset(it.x.coerceIn(-26f, 26f), it.y.coerceIn(-26f, 26f))
                                }
                            }
                        }
                    }
                }
            }
    ) {
        // 封面纵深（可选）+ 排版纹理，铺在最底层。
        LyricCoverDepth(artworkKey, lyricCoverDepth, colors.isDark, shift = coverShift)
        // 本地视频背景：铺在封面纵深之上、歌词之下，静音循环并跟随播放状态。
        if (lyricVideoUri.isNotBlank()) {
            VideoBackdrop(
                uri = lyricVideoUri,
                isPlaying = isPlaying,
                positionMs = playbackMs,
                alpha = lyricVideoAlpha
            )
        }
        LyricTextureOverlay(lyricTexture, colors.isDark)
        // 副歌氛围：只在副歌句出现。
        val inChorus = effChorusFx && chorusFlags.getOrElse(current) { false }
        if (inChorus) {
            LyricChorusFx(
                beat = beat,
                accent = accentColor,
                dark = colors.isDark,
                effect = chorusEffectFor(current),
                strength = effChorusBoost,
                speed = 0.4f + lyricTuning.getOrElse(1) { 50 } / 100f * 1.2f
            )
        }
        // 节拍氛围层：低频光晕 + 星河粒子，铺在歌词列表下面。
        if (effBeatFx) {
            val boost = if (inChorus) 1f + effChorusBoost * 0.8f else 1f
            val glowBoost = 0.55f + lyricTuning.getOrElse(2) { 50 } / 100f * 0.9f
            val density = (quality.particles *
                (0.45f + lyricTuning.getOrElse(0) { 50 } / 100f * 1.1f)).toInt()
            if (effBassGlow) LyricBassHalo(beat, accentColor, colors.isDark, boost = boost * glowBoost)
            if (effParticles) {
                LyricStarRiver(
                    beat, accentColor, colors.isDark, density,
                    boost = boost,
                    speed = 0.4f + lyricTuning.getOrElse(1) { 50 } / 100f * 1.2f
                )
            }
        }
        // 只有一个超小时间戳 + 一道指向性短横线指向焦点行，无胶囊背景。
        val tagAlpha by animateFloatAsState(
            if (showTimeTag && focusIndex in shownLines.indices) 1f else 0f,
            animationSpec = tween(180), label = "timeTagAlpha"
        )
        if (focusIndex in shownLines.indices) {
            val focusFrac = focusFraction
            // 居中/靠左对齐 → 标记贴右；靠右对齐 → 标记贴左
            val onRight = lyricAlign != 2
            // 时间数值平滑过渡：跨行时不是瞬间跳变，而是从旧时间连续滑到新时间，
            // 视觉上像滚动条一样渐变滚过去。
            val animMs by animateFloatAsState(
                targetValue = shownLines[focusIndex].timeMs.toFloat(),
                animationSpec = tween(durationMillis = 260, easing = LyricEase),
                label = "timeTagMs"
            )
            val tagText = formatDuration(animMs.toLong())
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val tagY = maxHeight * focusFrac
                Row(
                    Modifier
                        .align(if (onRight) Alignment.TopEnd else Alignment.TopStart)
                        .padding(top = tagY - 7.dp, start = if (onRight) 0.dp else 12.dp, end = if (onRight) 12.dp else 0.dp)
                        .alpha(tagAlpha),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onRight) {
                        Box(Modifier.width(10.dp).height(2.dp).background(accentColor))
                        Spacer(Modifier.width(5.dp))
                        Text(tagText,
                            color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text(tagText,
                            color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(5.dp))
                        Box(Modifier.width(10.dp).height(2.dp).background(accentColor))
                    }
                }
            }
        }
        GlassContent {
        Column(
            Modifier
                .fillMaxSize()
                // 信息区（标题 + 底部提示）压缩：顶部从 48dp 收到 16dp，
                // 把腾出来的纵向空间还给歌词。
                .padding(horizontal = 28.dp)
                .padding(top = 16.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左：曲名 + 歌手（单行省略，绝不与右侧按钮抢位）
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        title,
                        color = onCard,
                        fontSize = if (isLand) 15.sp else 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (artist.isNotBlank()) {
                        Text(
                            artist,
                            color = accentColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 右：当前舞台胶囊（点击切换）+ 横屏沉浸开关，和其余 13 个舞台同一套 chrome
                Box(
                    Modifier
                        // 与全 App 同一套按压弹簧：缩放层放在 clip/background/border 之前，胶囊底色一起软
                        .junPressable(scaleDown = 0.90f) { onCycleStage() }
                        .clip(RoundedCornerShape(20.dp))
                        .background(accentColor.copy(alpha = 0.12f))
                        .border(1.dp, accentColor.copy(alpha = 0.30f), RoundedCornerShape(20.dp))
                        .semantics {
                            contentDescription =
                                "切换歌词舞台，当前${LyricStageCatalog.name(stage)}，" +
                                    "第 ${stage + 1} 种，共 ${LyricStageCatalog.count} 种"
                        }
                        .padding(horizontal = 11.dp, vertical = 7.dp)
                ) {
                    Text(
                        "${LyricStageCatalog.name(stage)} · ${stage + 1}/${LyricStageCatalog.count}",
                        color = accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(8.dp))
                StageLandscapePill(accent = accentColor, onStage = onCard)
            }
            Spacer(Modifier.height(10.dp))
            when {
                loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("\u52a0\u8f7d\u6b4c\u8bcd\u2026", color = colors.subText, fontSize = 14.sp)
                }
                shownLines.isEmpty() -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("\u672a\u627e\u5230\u6b4c\u8bcd\n\uff08\u6b4c\u66f2\u5185\u5d4c\u6b4c\u8bcd\u6216\u540c\u76ee\u5f55\u540c\u540d .lrc\uff09", color = colors.subText,
                        fontSize = 14.sp, textAlign = TextAlign.Center)
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // 节拍电影镜头：低频每一下轻轻推近，长音频模式自动收住。
                        .graphicsLayer {
                            if (!effBeatCamera || longForm) {
                                scaleX = 1f; scaleY = 1f
                                return@graphicsLayer
                            }
                            val p = beat?.pulse ?: 0f
                            val k = lyricBeatCameraStrength.coerceIn(0f, 1f)
                            val s = 1f + 0.012f * p * k
                            scaleX = s
                            scaleY = s
                        }
                        // 靠左/靠右时额外内缩，别贴着屏幕边缘；横屏靠左要躲开左侧刘海/挖孔
                        .padding(
                            start = if (lyricAlign == 0) (if (isLand) 52.dp else 10.dp) else 0.dp,
                            end = if (lyricAlign == 2) (if (isLand) 52.dp else 10.dp) else 0.dp
                        )
                        // 上下边缘渐隐：整层离屏合成后用渐变蒙版擦除首尾，滚动进出更柔
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val fade = 56.dp.toPx()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent, 1f to Color.Black,
                                    startY = 0f, endY = fade
                                ),
                                blendMode = BlendMode.DstIn
                            )
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Black, 1f to Color.Transparent,
                                    startY = size.height - fade, endY = size.height
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        },
                    verticalArrangement = Arrangement.spacedBy(
                        if (isLand) 14.dp else if (longForm) 26.dp else 18.dp
                    ),
                    // 横屏高度矮：上下留白从 120dp 收到 56dp，
                    // 让居中的当前行仍能上下滚入，同时显示更多歌词。
                    contentPadding = PaddingValues(vertical = if (isLand) 56.dp else 120.dp)
                ) {
                    itemsIndexed(shownLines, key = { index, _ -> "lyric-$index" }) { index, line ->
                        val isCurrent = index == current
                        // 距当前行的行距：用它做连续衰减，离焦点越远越小越淡，
                        // 不再是"当前行/其它行"的二值突变，整屏有纵深层次感。
                        val dist = if (current < 0) 0 else kotlin.math.abs(index - current)
                        // 缩放：当前行最大，随距离平滑收拢到 0.86。用统一字号 + scale
                        // 承载大小差异，行高保持一致，跟踪滚动不会因字号跳变而抖。
                        // 错落：离焦点越远的行弹簧越软、到位越晚，整屏像波纹一样依次亮起，
                        // 而不是所有行同一帧齐刷刷变化——这是消除"僵硬感"的关键。
                        // 用刚度差而不是 delayMillis 做先后：延时是"假装"的先后，
                        // 曲线仍然一模一样；刚度差是真物理，追上来的过程带轻微过冲。
                        // 6.9.7：只有焦点附近的几行才真的跑弹簧动画。
                        // 以前整屏每一行都挂着一组 spring，滚动时几十组动画同时逐帧求值，
                        // 中低端机直接掉帧——掉帧在视觉上也是"抖"。远处的行用 snap，
                        // 反正它们本来就压到 0.10 透明度，看不出有没有过渡。
                        val rippleSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
                            if (dist <= 3) JunMotion.lyricRipple(dist) else androidx.compose.animation.core.snap()
                        // 显示模式：超出上下文行数的句子压到很淡、略小，
                        // 滚动列表仍然完整可滚，只是视觉焦点收在当前句附近。
                        val inContext = dist <= contextLimit
                        val scale by animateFloatAsState(
                            targetValue = if (isCurrent) 1f
                            else if (!inContext) 0.84f
                            else (0.9f - (dist - 1).coerceAtLeast(0) * 0.015f).coerceAtLeast(0.86f),
                            animationSpec = rippleSpec,
                            label = "lyricScale"
                        )
                        // 透明度：当前行全亮，上下文按行距阶梯淡出；超出显示模式的极淡。
                        val rowAlpha by animateFloatAsState(
                            targetValue = if (isCurrent) 1f
                            else if (!inContext) 0.10f
                            else (0.66f - (dist - 1).coerceAtLeast(0) * 0.12f).coerceAtLeast(0.26f),
                            animationSpec = rippleSpec,
                            label = "lyricAlpha"
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = rowAlpha
                                    // 缩放锚点跟随对齐方向，靠左/右时不从中心缩放
                                    transformOrigin = when (lyricAlign) {
                                        0 -> androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                                        2 -> androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                                        else -> androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                                    }
                                }
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { dismissOverlay() }
                                // 6.9.7：行高必须恒定。以前当前行多 6dp 上下内边距，
                                // 换句瞬间该行高度 +12dp，LazyColumn 会把整列重排一次，
                                // 跟踪滚动同时在算目标位置——两层叠起来就是"一跳一跳"。
                                // 现在所有行同高，当前行的强调只走 graphicsLayer 的 scale/alpha。
                                .padding(vertical = 3.dp),
                            contentAlignment = boxAlign
                        ) {
                            val seekModifier = Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                if (durationMs > 0L) {
                                    onSeek((line.timeMs.toFloat() / durationMs).coerceIn(0f, 1f))
                                }
                            }
                            // 颜色随高亮平滑过渡，换行时字色渐变而非硬切。
                            // 时长收到 360ms：行的缩放/淡入已经改成弹簧（约 0.3s 收敛），
                            // 颜色再走 620ms 就会出现"形状到位了、字还是灰的"两段感。
                            val lineColor by animateColorAsState(
                                targetValue = if (isCurrent) accentColor else onCard,
                                animationSpec = tween(durationMillis = if (JunMotion.reduced) 200 else 360, easing = LyricEase),
                                label = "lyricColor"
                            )
                            Column(
                                horizontalAlignment = when (lyricAlign) {
                                    0 -> Alignment.Start
                                    2 -> Alignment.End
                                    else -> Alignment.CenterHorizontally
                                },
                                modifier = seekModifier
                            ) {
                                if (isCurrent && karaoke) {
                                    // 当前行走逐词弹跳 / 扫光：
                                    //  - 有词级时间（增强型 LRC）→ 真逐字，按每个字的时间戳点亮
                                    //  - 无词级时间（普通 LRC）→ 伪逐字，用「本行→下一行」时长按字符
                                    //    数均匀铺开模拟扫光。虽非真实咬字，但比整行硬高亮生动。
                                    val lineEnd = LyricParser.lineEnd(shownLines, index, durationMs)
                                    val unitCount = remember(line, lineEnd) {
                                        LyricRenderEngine.layoutUnits(line, lineEnd).size
                                    }
                                    val maxUnits = when (quality) {
                                        LyricQuality.FINE -> 56
                                        LyricQuality.BALANCED -> 42
                                        else -> 28
                                    }
                                    if (effMotion != LyricMotionStyle.SWEEP && unitCount in 1..maxUnits) {
                                        val clock = rememberLyricFrameClock(liveMs, line)
                                        LyricFlowLine(
                                            line = line,
                                            clock = clock,
                                            lineEndMs = lineEnd,
                                            sungColor = accentColor,
                                            unsungColor = onCard.copy(alpha = 0.42f),
                                            textAlign = textAlign,
                                            fontFamily = lyricFamily,
                                            fontSize = stageFontSize,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
                                            shadow = null,
                                            motion = effMotion,
                                            beat = beat,
                                            modifier = Modifier.lyricBeatPulse(beat, effMotion.pulseAmplitude())
                                        )
                                    } else {
                                        KaraokeLine(
                                            line = line,
                                            positionMs = playbackMs,
                                            live = liveMs,
                                            lineEndMs = lineEnd,
                                            sungColor = accentColor,
                                            unsungColor = onCard.copy(alpha = 0.42f),
                                            textAlign = textAlign,
                                            fontFamily = lyricFamily,
                                            fontSize = stageFontSize,
                                            hints = LyricRenderEngine.hintsFor(line, lineEnd),
                                            glowColor = accentColor,
                                            lineBreak = if (lyricSemanticWrap) {
                                                androidx.compose.ui.text.style.LineBreak.Paragraph
                                            } else {
                                                androidx.compose.ui.text.style.LineBreak.Unspecified
                                            },
                                            modifier = Modifier.lyricBeatPulse(beat, effMotion.pulseAmplitude())
                                        )
                                    }
                                } else {
                                    val styledText = remember(line.text, isCurrent, keywordRules) {
                                        if (isCurrent) {
                                            LyricKeywordStyler.annotate(line.text, keywordRules)
                                        } else {
                                            AnnotatedString(line.text)
                                        }
                                    }
                                    Text(
                                        styledText,
                                        // 统一字号，大小差异交给 scale，行高恒定 → 跟踪更稳
                                        color = lineColor,
                                        fontSize = stageFontSize.sp,
                                        fontFamily = lyricFamily,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                        textAlign = textAlign,
                                        // 中文语义断行：优先在标点/词组边界换行，不把词劈开
                                        style = androidx.compose.ui.text.TextStyle(
                                            lineBreak = if (lyricSemanticWrap) {
                                                androidx.compose.ui.text.style.LineBreak.Paragraph
                                            } else {
                                                androidx.compose.ui.text.style.LineBreak.Unspecified
                                            }
                                        )
                                    )
                                }
                                // 翻译 / 音译：两条独立轨道，各自过滤时间标记再显示。
                                val translationText = LyricDisplayFilter.track(line.translation)
                                val romanizationText = LyricDisplayFilter.track(line.romanization)
                                val transSize = LyricType.translation(stageFontSize)
                                val romaSize = LyricType.romanization(stageFontSize)
                                if (showTranslation && translationText != null &&
                                    (isCurrent || translationText.length <= 40)
                                ) {
                                    Text(
                                        translationText,
                                        color = if (isCurrent) onCard.copy(alpha = 0.82f)
                                        else onCard.copy(alpha = 0.34f),
                                        fontSize = transSize.sp,
                                        fontFamily = lyricFamily,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = textAlign,
                                        lineHeight = (transSize * 1.35f).sp
                                    )
                                }
                                if (showRomanization && isCurrent && romanizationText != null) {
                                    Text(
                                        romanizationText,
                                        color = accentColor.copy(alpha = 0.68f),
                                        fontSize = romaSize.sp,
                                        fontFamily = lyricFamily,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = textAlign
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!isLand) Text("\u70b9\u6b4c\u8bcd\u8df3\u8f6c\uff0c\u70b9\u7a7a\u767d\u5904\u5173\u95ed", color = colors.subText, fontSize = 11.sp)
        }
        }
        // 涟漪画在所有内容之上：点击空白处的白圈不会被歌词盖住。
        if (lyricCoverDepth) LyricRippleCanvas(coverRipple, accentColor)
        if (showFrameStats) {
            LyricFrameStatsOverlay(
                colors,
                Modifier.align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 16.dp)
            )
        }
    }
}

/**
 * 逐字点亮的当前行：已唱→未唱之间是一道带柔边的渐变扫光，连续平滑推过整行，
 * 不是一个字一个字地硬切。
 *
 * 两种进度来源自动切换：
 *  - 有词级时间戳（增强型 LRC）→ 按每个字的真实时间算已唱比例。
 *  - 无词级时间戳（普通 LRC，如 QQ 音乐内嵌歌词）→ 伪逐字：用「本行开始 [line.timeMs]
 *    → 下一行开始 [lineEndMs]」这段时长线性推进比例。
 *
 * 着色用 horizontalGradient：已唱色 → 一段柔边 → 未唱色，柔边随进度平移，
 * 视觉上就是一道模糊的光带匀速扫过文字。时间轴每帧直接问播放器（[live]），
 * 光带因此每帧连续推进，且不额外叠加墙上时间——media3 的位置本身已经是外推好的。
 */
@Composable
internal fun KaraokeLine(
    line: LyricLine,
    positionMs: Long,
    live: () -> Long = { positionMs },
    lineEndMs: Long,
    sungColor: Color,
    unsungColor: Color,
    textAlign: TextAlign = TextAlign.Center,
    fontFamily: FontFamily = FontFamily.SansSerif,
    fontSize: Float = 20f,
    fontWeight: FontWeight = FontWeight.Bold,
    lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    letterSpacing: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    shadow: androidx.compose.ui.graphics.Shadow? = null,
    /** 句子渲染提示：不传时按行时长现算；micro 句瞬时铺满，short 句快速扫光。 */
    hints: LineRenderHints? = null,
    /** 扫光前沿的余辉颜色，和已唱色分开。 */
    glowColor: Color = sungColor,
    /** 中文语义断行：优先在标点/词组边界换行 */
    lineBreak: androidx.compose.ui.text.style.LineBreak =
        androidx.compose.ui.text.style.LineBreak.Unspecified,
    modifier: Modifier = Modifier
) {
    val text = line.text
    val liveState = rememberUpdatedState(live)
    var estMs by remember { mutableStateOf(positionMs) }
    // 6.9.13：布局结果只用来做「第几个字 → 第几像素」的映射，拿到一次就不再变。
    // 它不参与每帧循环，所以不会拖慢扫光。
    var layoutResult by remember(line) { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    LaunchedEffect(line) {
        while (true) {
            withFrameMillis {
                val v = liveState.value()
                // 直接跟随。live() 本身已经是单调、平滑的播放器位置；
                // 这里以前留了 120ms 的「倒退死区」——等于允许歌词领先 120ms
                // 且永远不会被收回，逐句累积起来就是"越唱越快"。
                if (v != estMs) estMs = v
            }
        }
    }
    // 渲染提示 + grapheme 时间线：逐字进度按"用户感知字符"算，emoji 不会被拆开；
    // micro 句直接铺满，short 句用更窄的柔边快速扫过。
    val hint = hints ?: LyricRenderEngine.hintsFor(line, lineEndMs)
    val timeline = remember(line, lineEndMs) {
        LyricRenderEngine.timelineFor(line, lineEndMs)
    }
    // 6.9.13 逐字对齐的核心：把每个字素映射到它在文本里的**真实像素区间**。
    // 以前 frac 用的是"已唱字数 / 总字数"，等于假设每个字一样宽——中文还行，
    // 中英混排、数字、标点一进来，光带前沿就落到字缝里，看着就是"不齐"。
    // 单行才做像素映射；多行（超长句自动换行）退回整行比例，避免跨行串色。
    val boxes = remember(timeline, layoutResult, text) {
        val lr = layoutResult
        if (lr == null || lr.lineCount != 1 || lr.size.width <= 0) null
        else graphemeBoxes(text, timeline.graphemes, lr)
    }
    val frac = when (hint.reveal) {
        WordRevealMode.INSTANT -> if (estMs >= line.timeMs) 1f else 0f
        else -> {
            val lr = layoutResult
            val b = boxes
            if (b == null || lr == null) {
                timeline.revealFraction(estMs)
            } else {
                val (idx, inChar) = timeline.revealAt(estMs)
                if (idx >= b.size / 2) 1f else {
                    val l = b[idx * 2]
                    val r = b[idx * 2 + 1]
                    if (l < 0f) timeline.revealFraction(estMs)
                    else ((l + (r - l) * inChar) / lr.size.width.toFloat()).coerceIn(0f, 1f)
                }
            }
        }
    }
    // 余辉和扫光进度分开：字亮完以后，光还会再留一小段时间。
    val glow = if (hint.reveal == WordRevealMode.INSTANT) 0f else timeline.glowAt(estMs)
    // 柔边宽度（占整行比例）：normal 宽、short 窄、micro 无渐变。
    val soft = when (hint.reveal) {
        WordRevealMode.NORMAL -> 0.10f
        WordRevealMode.FAST -> 0.055f
        WordRevealMode.INSTANT -> 0f
    }
    val half = soft / 2f
    // 进度 0→1 映射到过渡带中心 -half→1+half：两端各多滑出半个柔边，
    // frac=1 时整行已唱、frac=0 时整行未唱。之前把 center 夹在 [half,1-half]，
    // 行尾永远差最后一段柔边扫不完 —— 最后半个字卡在半透明再直接跳行。
    val center = frac * (1f + soft) - half
    val start = (center - half).coerceIn(0f, 1f)
    val end = (center + half).coerceIn(0f, 1f)
    val sweep = when {
        soft <= 0.001f ->
            androidx.compose.ui.graphics.SolidColor(if (frac >= 1f) sungColor else unsungColor)
        end <= start ->
            // 柔边已完全滑出边界：右端=整行已唱，左端=整行未唱
            androidx.compose.ui.graphics.SolidColor(if (frac >= 0.5f) sungColor else unsungColor)
        else -> {
            val edge = androidx.compose.ui.graphics.lerp(
                glowColor,
                Color.White,
                (0.55f * glow).coerceIn(0f, 0.55f)
            )
            val raw = ArrayList<Pair<Float, Color>>(8)
            raw.add(0f to sungColor)
            if (start > 0f) raw.add(start to sungColor)
            if (glow > 0.01f) {
                val gw = (soft * 0.85f).coerceAtLeast(0.010f)
                val gs = (center - gw).coerceIn(start, end)
                val ge = (center + gw).coerceIn(start, end)
                if (gs > start) raw.add(gs to sungColor)
                if (ge > gs) raw.add(center.coerceIn(gs, ge) to edge)
                if (ge < end) raw.add(ge to unsungColor)
            }
            raw.add(end to unsungColor)
            if (end < 1f) raw.add(1f to unsungColor)
            // colorStops 必须严格递增：排序后去掉重复/过近的端点
            val stops = ArrayList<Pair<Float, Color>>(raw.size)
            for (p in raw.sortedBy { it.first }) {
                if (stops.isEmpty() || p.first > stops.last().first + 0.0005f) stops.add(p)
            }
            if (stops.size < 2) {
                androidx.compose.ui.graphics.SolidColor(if (frac >= 0.5f) sungColor else unsungColor)
            } else {
                androidx.compose.ui.graphics.Brush.horizontalGradient(colorStops = stops.toTypedArray())
            }
        }
    }
    Text(
        text,
        textAlign = textAlign,
        modifier = modifier,
        // 只在真正换布局时更新；引用不变就不触发重组，避免每帧自我刷新。
        onTextLayout = { if (layoutResult !== it) layoutResult = it },
        style = androidx.compose.ui.text.TextStyle(
            brush = sweep,
            fontFamily = fontFamily,
            fontSize = fontSize.sp,
            fontWeight = fontWeight,
            lineHeight = lineHeight,
            letterSpacing = letterSpacing,
            shadow = shadow,
            textAlign = textAlign,
            lineBreak = lineBreak
        )
    )
}

/**
 * 6.9.13：把「第几个字素」映射成「这一行文本里的像素区间」。
 *
 * 大厂逐字之所以看着服帖，不是因为它算得准时，而是因为它**知道每个字画在哪里**：
 * 拿到当前字的时间进度后，直接查这个字的左右边界，再把扫光前沿插值进去。
 * 我们以前只有"已唱字数 ÷ 总字数"，等于假设每个字等宽，中英混排必然错位。
 *
 * 这里一次性把 [graphemes] 全部映射成 [left, right] 扁平数组（长度 = n*2），
 * 之后每帧只做一次下标查表，零分配、零测量。定位不到的字素填 -1，调用方退回比例模式。
 */
private fun graphemeBoxes(
    text: String,
    graphemes: List<com.jun.musiccloud.data.TimedGrapheme>,
    lr: androidx.compose.ui.text.TextLayoutResult
): FloatArray? {
    if (graphemes.isEmpty() || text.isEmpty()) return null
    val out = FloatArray(graphemes.size * 2)
    val last = text.length - 1
    var cursor = 0
    for (i in graphemes.indices) {
        val g = graphemes[i].text
        var at = if (cursor <= last && g.isNotEmpty()) text.indexOf(g, cursor) else -1
        if (at < 0 && g.isNotEmpty()) at = text.indexOf(g)
        if (at < 0) {
            out[i * 2] = -1f
            out[i * 2 + 1] = -1f
            continue
        }
        val s = at.coerceIn(0, last)
        val e = (at + g.length - 1).coerceIn(0, last)
        val b0 = runCatching { lr.getBoundingBox(s) }.getOrNull()
        val b1 = runCatching { lr.getBoundingBox(e) }.getOrNull()
        if (b0 == null || b1 == null) {
            out[i * 2] = -1f
            out[i * 2 + 1] = -1f
        } else {
            out[i * 2] = b0.left
            out[i * 2 + 1] = if (b1.right >= b0.left) b1.right else b0.right
        }
        cursor = at + g.length
    }
    return out
}

/**
 * 歌曲操作菜单：点歌曲行末尾的 ⋮（或长按整行）弹出。
 *
 * R9 按参考实现的度量重排（用户要求"UI 界面就跟那个源码一样、条目变小一点"），
 * R10 在 R9 的档位上再收一档（用户："把那个界面弄小一点，不要弄的太大"）：
 *  · **顶排三个动作钮** = 我喜欢 / 下一首 / 播放，改成**横排胶囊**：钮高 40dp、
 *    图标 17dp（播放三角 16dp）、图标文字间距 5dp、文字 11.5sp、钮间 8dp、圆角 13dp。
 *    「下一首」的图标换成 [JunIcons.PlayNext]：原先用的 Material queue_music 箭头
 *    画到 x=25，超出 24 的 viewport，右半边被裁掉——这就是"这个按钮不太好看"的根因。
 *    「我喜欢」在已喜爱时走主色淡染底，状态一眼可读。
 *  · **条目**：行 38dp、图标 18dp、图标文字间距 8dp、文字 14sp、水平 12dp、圆角 13dp，
 *    组与组之间用 0.5dp / alpha 0.12 的细分割线（左右缩进 14dp、上下各 4dp）。
 *    「相似歌曲，接着一路听」按用户要求从菜单里删除（播放页副控制行那个入口保留）。
 *  · q 弹保留：顶排与条目仍走 [junPopItem] 错峰 + [junPressable] 欠阻尼回弹。
 *    参考实现本身不做条目错峰（整层一条 alpha 曲线），这里不跟 —— 错峰已经是
 *    全 App 的统一语言，撤掉会让这个菜单显得比别处"死"。
 *
 * 抬头自带封面与「歌手 · 来源 · 时长」一行元信息：歌曲行末尾改成只有 ⋮ 之后，
 * 时长不再常驻每一行，改放在这个按需展开的菜单里。
 *
 * 入场由 [popAnchor] 分两条路：从 ⋮ 点开 → 以按钮为支点鼓出来（面板缩放支点 +
 * 欠阻尼过冲），条目再按序号错峰跟上；长按唤起 → 没有具体按钮，仍从底部推上来。
 */
@Composable
internal fun SongActionMenu(
    song: Song,
    liked: Boolean,
    colors: JunColors,
    onDismiss: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleLike: () -> Unit,
    onDownload: () -> Unit,
    onComments: () -> Unit = {},
    onStartSelectMode: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    /** 顶排「播放」：立刻播这首（不同于「下一首播放」的插队） */
    onPlayNow: () -> Unit = {},
    /**
     * R13 预留：分享**这一整份列表**（首页/搜索/榜单/歌单里长按时正在看的那一份）。
     *
     * 传 null 就不画这一行：长按菜单在四个界面复用，只有调用方确实持有一份列表
     *（MainScreen 的 pendingActionList 非空）时才有东西可分享，否则点了是空壳。
     * 面板实现见 MainScreen 里的 PLAYLIST_SHARE_READY 与 PlaylistSheet 之后的 TODO。
     */
    onSharePlaylist: (() -> Unit)? = null,
    /** 来自歌曲行 ⋮ 的落点（根坐标）；null 时面板按常规从底部上滑 */
    popAnchor: Offset? = null
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val flags = RemoteConfigStore.flagsState.value
    val drag = rememberSheetDragState()
    // 条目错峰序号：被后台开关隐藏的项不占号，避免出现 0、2、4 的空档。
    // 0 号给抬头（封面 + 元信息），条目从 1 开始，读起来是"先看清是哪首歌，再掀开动作"。
    var slot = 1
    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        popAnchor = popAnchor,
        contentModifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 14.dp)
    ) {
        SheetDragHandle(drag, onDismiss, colors.card)
        Spacer(Modifier.height(8.dp))
        // 抬头：封面 + 曲名 + 元信息行（R10 再收一档：封面 40 → 34dp，字号跟着缩）
        Row(
            Modifier
                .fillMaxWidth()
                .junPopItem(0),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SongArtwork(
                filePath = song.filePath,
                seedId = song.id,
                artworkSize = 34.dp,
                isDark = colors.isDark
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    color = onSheet, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    listOf(
                        song.artist,
                        song.folderName,
                        formatDuration(song.durationMs)
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    color = colors.subText, fontSize = 10.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(9.dp))

        // ===== 顶排三钮：我喜欢 / 下一首播放 / 播放 =====
        Row(
            Modifier.fillMaxWidth().junPopItem(slot++),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MenuQuickButton(
                label = if (liked) "取消喜爱" else "我喜欢",
                colors = colors,
                modifier = Modifier.weight(1f),
                // 已喜爱时这枚钮用主色底，一眼看得出当前状态；未喜爱保持中性
                highlighted = liked,
                icon = { tint ->
                    Icon(
                        if (liked) JunIcons.Favorite else JunIcons.FavoriteBorder,
                        null, Modifier.size(17.dp), tint
                    )
                },
                onClick = onToggleLike
            )
            // 图标换成 JunIcons.PlayNext：原来那枚 queue_music 的箭头画到 x=25，
            // 超出 24 的 viewport，右半边在真机上是被裁掉的——"不好看"就在这里。
            MenuQuickButton(
                label = "下一首",
                colors = colors,
                modifier = Modifier.weight(1f),
                icon = { tint -> Icon(JunIcons.PlayNext, null, Modifier.size(17.dp), tint) },
                onClick = onPlayNext
            )
            MenuQuickButton(
                label = "播放",
                colors = colors,
                modifier = Modifier.weight(1f),
                icon = { tint -> Icon(JunIcons.Play, null, Modifier.size(16.dp), tint) },
                onClick = onPlayNow
            )
        }

        MenuDivider(colors)

        // ===== 主条目 =====
        if (flags.playlist) {
            SongActionItem(
                icon = { Icon(JunIcons.PlaylistAdd, null, Modifier.size(18.dp), it) },
                label = "添加到歌单",
                colors = colors,
                index = slot++,
                onClick = { onAddToPlaylist() }
            )
        }
        if (flags.download || flags.comments) MenuDivider(colors)
        // 下载（后台关闭下载时不显示）
        if (flags.download) {
            SongActionItem(
                icon = { Icon(JunIcons.Download, null, Modifier.size(18.dp), it) },
                label = "下载",
                colors = colors,
                index = slot++,
                onClick = { onDownload() }
            )
        }
        // 查看评论（网易云歌曲；后台关闭评论时不显示）
        if (flags.comments) {
            SongActionItem(
                icon = { Icon(JunIcons.Comment, null, Modifier.size(18.dp), it) },
                label = "查看评论",
                colors = colors,
                index = slot++,
                onClick = { onComments() }
            )
        }
        // 分享这份歌单 / 榜单（R13 预留入口）：调用方没给列表时整行不出现，
        // 不占错峰序号，条目顺序就不会出现空档。
        if (onSharePlaylist != null) {
            SongActionItem(
                icon = { Icon(JunIcons.Share, null, Modifier.size(18.dp), it) },
                label = "分享歌单",
                colors = colors,
                index = slot++,
                onClick = { onDismiss(); onSharePlaylist() }
            )
        }
        // 全选（进入批量操作模式）：这是一颗"入口"，画空框而不是带勾的框
        SongActionItem(
            icon = { Icon(JunIcons.CheckBoxBlank, null, Modifier.size(18.dp), it) },
            label = "全选",
            colors = colors,
            index = slot++,
            onClick = { onDismiss(); onStartSelectMode() }
        )
    }
}

/**
 * 顶排动作钮：图标在左、文字在右，40dp 高、13dp 圆角、按下整块缩 + 回弹。
 *
 * R10 从"图标在上、文字在下"改成横排：竖排时每格只有 56dp 高，22dp 图标加 11sp 文字
 * 挤在一起，三格看起来像三个小色块；横排 40dp 高的胶囊更像一个正常的按钮组，
 * 字号也能回到 11.5sp 不用省略号。
 */
@Composable
private fun MenuQuickButton(
    label: String,
    colors: JunColors,
    modifier: Modifier = Modifier,
    /** 已生效状态（比如已经喜爱）：底色换成主色淡染，图标文字换成主色 */
    highlighted: Boolean = false,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val tint = if (highlighted) accent else onSheet.copy(alpha = 0.85f)
    Row(
        modifier
            .height(40.dp)
            // graphicsLayer 系的外挂在 clip/background 之前，底色才跟着一起缩
            .junPressable(scaleDown = 0.92f, feedback = PressFeedback.TAP, onClick = onClick)
            .clip(RoundedCornerShape(13.dp))
            .background(if (highlighted) accent.copy(alpha = 0.16f) else colors.card)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon(tint)
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            color = if (highlighted) accent else onSheet.copy(alpha = 0.9f),
            fontSize = 11.5.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 组间细分割线：0.5dp / alpha 0.12，左右缩进 14dp、上下各 4dp（参考实现同款，R10 再收一档） */
@Composable
private fun MenuDivider(colors: JunColors) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .height(0.5.dp)
            .background((if (colors.isDark) Color.White else Color.Black).copy(alpha = 0.12f))
    )
}

/** 首次下载路径选择弹窗：让用户选择默认路径或自定义路径 */
@Composable
private fun DownloadPathDialog(
    colors: JunColors,
    onUseDefault: () -> Unit,
    onPickPath: () -> Unit,
    onDismiss: () -> Unit
) {
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val onSheet = if (colors.isDark) Color.White else Color.Black
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
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
                    "选择下载位置",
                    color = onSheet,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "歌曲将下载到「Music/Jun Music」文件夹，也可以选择其他位置。",
                    color = colors.subText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(42.dp)
                            .junPressable(scaleDown = 0.93f) { onUseDefault() }
                            .clip(JunShape.item)
                            .background(colors.card),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("使用默认", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(42.dp)
                            .junPressable(scaleDown = 0.93f, feedback = PressFeedback.CLICK) { onPickPath() }
                            .clip(JunShape.item)
                            .background(accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("选择位置", color = accent.readableTextOn(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * 单条操作项：左图标 + 文字，整行可点。
 *
 * [index] 决定入场错峰（第几条、晚 34ms×index 起步）；行本身按 0.94 轻压——整宽的条
 * 缩得太狠会看出"面条"，0.94 配上横向先回、纵向慢半拍的弹簧，只留一点软的余振。
 *
 * R10 再收一档（用户："那个界面弄小一点，不要弄的太大"）：行 38dp、图标 18dp、
 * 图标文字间距 8dp、文字 14sp、水平内边距 12dp、圆角 13dp。
 */
@Composable
private fun SongActionItem(
    icon: @Composable (Color) -> Unit,
    label: String,
    colors: JunColors,
    index: Int = 0,
    onClick: () -> Unit
) {
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(38.dp)
            .junPopItem(index)
            // 必须放在 clip/background 之前：graphicsLayer 只包住它内侧的绘制，
            // 放在底色后面就会出现"文字缩了、卡片底色不动"（同 sheetDragTranslate 的坑）。
            .junPressable(scaleDown = 0.95f, feedback = PressFeedback.TAP, onClick = onClick)
            .clip(RoundedCornerShape(13.dp))
            .background(colors.card)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon(accent.copy(alpha = 0.85f))
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 批量选择底部操作栏（5.1）。
 *
 * 与旧版的两点区别：
 *  · 范围标签：明确写出"已选 N 首 · 来自 热歌榜"，用户一眼看出全选到底选了谁；
 *  · 画在根层级，所以从榜单/歌单/搜索结果进入批量模式时它就在那些浮层之上。
 */
@Composable
private fun BatchSelectBar(
    colors: JunColors,
    sourceName: String,
    total: Int,
    selected: Int,
    flags: com.jun.musiccloud.data.AppFlags,
    miniPlayerVisible: Boolean,
    onSelectAllToggle: () -> Unit,
    onLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onOpenLog: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allSelected = selected == total && total > 0
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .padding(bottom = if (miniPlayerVisible) 72.dp else 0.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .junSurface(GlassLevel.SHEET, colors, JunShape.card)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            // 范围说明：选中的到底是谁，写在最显眼的一行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已选 $selected 首",
                    color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Black
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (sourceName.isBlank()) "· 当前列表" else "· 来自《$sourceName》",
                    color = colors.subText, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "共 $total 首",
                    color = colors.subText, fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 全选 / 取消
                Box(
                    Modifier
                        .junPressable(scaleDown = 0.92f) { onSelectAllToggle() }
                        .clip(JunShape.item)
                        .background(colors.card)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        if (allSelected) "取消全选" else "全选",
                        color = colors.text, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
                // 批量喜欢
                BatchActionBtn(JunIcons.FavoriteBorder, colors, "$selected") {
                    Haptics.click(); onLike()
                }
                // 批量加歌单（后台关闭歌单功能时不显示）
                if (flags.playlist) {
                    BatchActionBtn(JunIcons.PlaylistAdd, colors) {
                        Haptics.tap(); onAddToPlaylist()
                    }
                }
                // 批量下载（后台关闭下载时不显示）
                if (flags.download) {
                    BatchActionBtn(JunIcons.Download, colors) {
                        Haptics.click(); onDownload()
                    }
                }
                // 最近批量操作：可回看、最后一次可撤销
                BatchActionBtn(JunIcons.History, colors) {
                    Haptics.tap(); onOpenLog()
                }
                // 退出
                Box(
                    Modifier
                        .junPressable(scaleDown = 0.92f) { onExit() }
                        .clip(JunShape.item)
                        .background(colors.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("完成", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * 最近批量操作：本次会话里的批量喜欢 / 加歌单 / 下载流水，最后一条可撤销。
 *
 * 批量一次点几十上百首，点错的代价不小；给一个能回看又能反悔的地方，
 * 比在每个操作前弹一次"确定吗"更少打断。
 */
@Composable
private fun RecentBatchSheet(
    log: List<com.jun.musiccloud.player.BatchOpEntry>,
    colors: JunColors,
    onDismiss: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val drag = rememberSheetDragState()
    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        panelModifier = Modifier.fillMaxHeight(0.6f),
        contentModifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        SheetDragHandle(drag, onDismiss, colors.card)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("最近批量操作", color = onSheet, fontSize = 19.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f))
            if (log.isNotEmpty()) {
                Text(
                    "清空",
                    color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .junPressable(scaleDown = 0.90f) { onClear() }
                        .clip(JunShape.chip)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "只记本次打开应用的操作；撤销只对最近一次有效",
            color = colors.subText, fontSize = 11.sp
        )
        Spacer(Modifier.height(12.dp))
        if (log.isEmpty()) {
            Text("还没有批量操作", color = colors.subText, fontSize = 13.sp)
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                log.forEachIndexed { i, e ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // 静态 Column（不回收），错峰入场是安全的：整叠记录像被掀开而不是齐步出现
                            .junPopItem(if (i < 8) i else 0)
                            .clip(JunShape.item)
                            .background(colors.card)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.text, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                    .format(java.util.Date(e.timeMs)),
                                color = colors.subText, fontSize = 10.sp
                            )
                        }
                        if (i == 0 && e.undoable) {
                            Box(
                                Modifier
                                    .junPressable(scaleDown = 0.90f) { onUndo() }
                                    .clip(JunShape.chip)
                                    .background(colors.primary.copy(alpha = 0.18f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("撤销", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 批量操作栏的小图标按钮：图标 + 可选角标数字 */
@Composable
private fun BatchActionBtn(
    icon: ImageVector,
    colors: JunColors,
    badge: String? = null,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(40.dp)
            .junSurface(GlassLevel.CHIP, colors, JunShape.chip)
            .semantics { contentDescription = "刷新曲库" }
        .junPressable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, Modifier.size(20.dp), colors.primary)
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(badge, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 列表页的批量选择上下文（5.1）。
 *
 * 由 MainScreen 构造一次，传给曲库列表 / 搜索结果 / 歌手详情 / 榜单歌单弹层。
 * 这些页面共用 [SongRow]，所以每页只要把 `select.active` / `isSelected` 透传给行，
 * 就能在同一种视觉语言里勾选；范围（"全选的是哪一批歌"）由 MainScreen 的
 * selectSource 决定，页面自己不再维护选中集。
 */
internal class ListSelect(
    val active: Boolean = false,
    val selectedIds: List<Long> = emptyList(),
    val sourceName: String = "",
    val onToggle: (Long) -> Unit = {},
    val onExit: () -> Unit = {},
    /** 直接以"这份列表"为范围进入多选（列表页上那颗明面上的全选按钮走这里） */
    val onStart: (String, List<Song>) -> Unit = { _, _ -> }
)

/**
 * 列表页上的「全选」入口按钮，放在每份列表的「播放全部」旁边。
 *
 * 之前进多选只能靠长按某一首歌，没人知道（用户原话）。给一个明面上的入口，
 * 点它 = 以当前这份列表为范围进入多选，全选范围仍然是"正在看的这一屏"。
 *
 * 6.0 修掉它的对勾语义（用户："正常的话都是没有那个对勾的，点开全选之后才会有"）：
 * 旧版无论什么状态都画 Material `check_box` —— 那个字形里**自带一个勾**，所以还没选
 * 任何东西时按钮上就挂着一个对勾，看着像"已经全选了"。现在未选中画空框 [JunIcons.CheckBoxBlank]，
 * 这一屏真的全被选中时才画带勾的框并把文案改成「取消全选」，点它就是退出多选。
 */
@Composable
internal fun SelectAllEntry(
    colors: JunColors,
    small: Boolean = false,
    modifier: Modifier = Modifier,
    /** 当前这份列表是不是已经整屏选中了（决定画空框还是带勾框） */
    checked: Boolean = false,
    onClick: () -> Unit
) {
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    Row(
        modifier
            .junPressable(scaleDown = 0.92f) { onClick() }
            .clip(JunShape.item)
            .background(accent.copy(alpha = if (checked) 0.22f else 0.12f))
            .padding(
                horizontal = if (small) 9.dp else 12.dp,
                vertical = if (small) 4.dp else 6.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (checked) JunIcons.CheckBox else JunIcons.CheckBoxBlank,
            contentDescription = null,
            // 未选中时框子淡一档，选中后满色 —— 颜色深浅 + 有没有勾，两层信号
            tint = if (checked) accent else accent.copy(alpha = 0.72f),
            modifier = Modifier.size(if (small) 13.dp else 15.dp)
        )
        Spacer(Modifier.width(if (small) 3.dp else 5.dp))
        Text(
            if (checked) "取消全选" else "全选",
            color = accent,
            fontSize = if (small) 11.sp else 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 歌曲行「更多」（⋮）的按压落点，根坐标 px；null = 这次菜单不是从按钮点开的（长按）。
 *
 * 为什么是全局临时值而不是参数：同一份 SongActionMenu 只在 MainScreen 渲染一次，而
 * SongRow 被四个界面复用（首页列表 / 全屏搜索 / 歌手详情 / 网易云榜单）。要做成参数，
 * 这四层的 onAddToPlaylist 签名全得改成 (Song, Offset?)——为一个坐标改八个函数签名，
 * 代价和收益不对等。这里只活过"按下 → 菜单展开动画"这一小段：SongRow 的两条路径
 * （点 ⋮ / 长按行）都在回调里先写它，因此不会读到上一次的残留；而 SongActionMenu
 * 只由 SongRow 唤起（队列行的长按进的是多选模式，不走这里），写入方是唯一且对称的。
 */
internal object SongMoreAnchor {
    var offset by mutableStateOf<Offset?>(null)
}

/**
 * 首页底部歌曲行的滚动渐入（6.5.1）。
 *
 * 用户原话：「底下滑动的话，也要有特效，慢慢往下滑的话，也要有特效」。
 * 做的是最克制的一种：每行第一次滚进屏幕时，从下方 18dp 处浮上来 + 透明到实，
 * 320ms，FastOutSlowIn。不缩放、不模糊、不逐行错拍——首页是长列表，
 * 特效太重会让"找歌"变成"看动画"。
 *
 * 三个实现细节，都是踩过坑的：
 *  1. **rememberSaveable 记在 item key 上**：LazyColumn 滑出屏幕的行会被丢弃，
 *     滑回去再组合。如果用 remember（不 saveable），每行滚回去都会再播一遍，
 *     来回滑动时整个列表都在闪。saveable 的状态跟着 key 走，一行只播一次。
 *  2. **动画放在 graphicsLayer**：alpha/translationY 是绘制期属性，
 *     不触发布局；要是写在 offset/alpha modifier 上，每一帧都会重新 measure。
 *  3. **只包首页歌曲行**：歌单页/搜索页的列表不包——那些页面用户是来找结果的，
 *     动画反而拖慢"看到目标行"的时间。
 *
 * [songKey] 参与rememberSaveable 的 key 计算：行内容换了（刷新后同位置变成另一首歌）
 * 就重新播一次，不会出现"换了歌但动画状态还是旧的"。
 */
@Composable
internal fun HomeRowReveal(songKey: Long, content: @Composable () -> Unit) {
    var played by rememberSaveable(songKey) { mutableStateOf(false) }
    val t = remember(songKey) { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(songKey) {
        if (!played) {
            t.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
            played = true
        }
    }
    Box(
        Modifier.graphicsLayer {
            val v = t.value
            alpha = v
            translationY = (1f - v) * 18.dp.toPx()
        }
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SongRow(
    song: Song,
    active: Boolean,
    colors: JunColors,
    rowSize: Int,
    showCover: Boolean = false,
    showArtist: Boolean = true,
    showPlaylist: Boolean = true,
    showLikedBadge: Boolean = false,
    liked: Boolean = false,
    playlistNames: List<String> = emptyList(),
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onToggleLike: () -> Unit = {},
    /** 点歌手名打开歌手详情页；null 时歌手名不可点 */
    onArtistClick: (() -> Unit)? = null,
    selectMode: Boolean = false,
    isSelected: Boolean = false,
    /**
     * 行左右留白。
     *
     * 首页的 LazyColumn 不再给左右 contentPadding（见那里的注释），所以首页调用点
     * 传 [HomeSectionSide]；歌单页 / 搜索页的外层本来就留过边，保持默认 14dp。
     * 不给成固定值是因为 SongRow 是共用的，改常量会连带改到别的页面。
     */
    sidePad: Dp = 14.dp
) {
    // 指示条高度动画：切歌时旧条收缩、新条生长，平滑过渡不瞬移
    val barH by animateDpAsState(if (active) 22.dp else 0.dp, JunMotion.moveDp(), label = "cursorBar")
    // 激活态整体渐变：底色、文字色都走 260ms 补间，切歌时行内所有颜色一起过渡
    val activeT by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = JunMotion.move(),
        label = "rowActive"
    )
    val activeBg = lerp(colors.row, colors.secondary.copy(alpha = 0.12f), activeT)
    // 行大小：0=紧凑 1=标准 2=宽松
    val vPad = if (rowSize == 0) 5.dp else if (rowSize == 1) 10.dp else 15.dp
    val titleSize = if (rowSize == 0) 12.sp else if (rowSize == 1) 13.sp else 14.sp
    val subSize = if (rowSize == 0) 10.sp else if (rowSize == 1) 11.sp else 12.sp
    // 封面尺寸
    val coverSize = if (rowSize == 0) 34.dp else if (rowSize == 1) 44.dp else 50.dp
    Row(
        Modifier
            .fillMaxWidth()
            .junSurface(
                level = GlassLevel.ROW,
                colors = colors,
                shape = JunShape.item,
                solid = activeBg,
                accent = if (activeT > 0.5f) colors.secondary else null
            )
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                // 6.3.1 防误触：滚动中/刚停下的 140ms 内，行点击一律丢弃。
                // 用户现场是"往下滑，莫名点到播放/点进歌手页"——快速轻扫的位移常常
                // 还没超过 touch slop，被系统判成一次点击。长按菜单不受影响。
                onClick = {
                    if (com.jun.musiccloud.ui.theme.GlassRuntime.tappedWhileScrolling()) return@combinedClickable
                    Haptics.tap(); onClick()
                },
                // 长按走的是同一份菜单，但落点清空：没有按钮可当支点，就回退成从底部推上来
                onLongClick = { Haptics.click(); SongMoreAnchor.offset = null; onLongPress() }
            )
            .padding(start = sidePad, end = sidePad, top = vPad, bottom = vPad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 批量选择模式：左侧显示勾选框。
        // 6.0：未选中 = 空框（边框描清楚，不再用 0.4 的淡色几乎看不见）；选中 = 主色实心 +
        // 单独一枚勾 [JunIcons.Check]。旧版把自带方框的 check_box 画进已经填色的框里，
        // 等于框套框，勾看不清 —— 这正是「全选那个对勾不好看」的另一半原因。
        if (selectMode) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (isSelected) colors.primary else Color.Transparent)
                    .border(
                        if (isSelected) 1.dp else 1.4.dp,
                        if (isSelected) colors.primary else colors.subText.copy(alpha = 0.72f),
                        RoundedCornerShape(5.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(JunIcons.Check, null, Modifier.size(14.dp), Color.White)
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        // 行内指示条：当前播放行显示主色胶囊，天然钉在歌单上，滚动跟随、开APP必显示
        Box(
            Modifier
                .width(4.dp)
                .height(barH)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.primary)
        )
        Spacer(Modifier.width(10.dp))
        if (showCover) {
            SongArtwork(
                filePath = song.filePath,
                seedId = song.id,
                artworkSize = coverSize,
                isDark = colors.isDark
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(song.title, color = lerp(colors.text, colors.primary.readableOn(colors.row, 3.2f), activeT),
                fontSize = titleSize, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showArtist) {
                Text(
                    song.artist, color = colors.subText, fontSize = subSize,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (onArtistClick != null) Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { Haptics.tap(); onArtistClick() }
                    else Modifier
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            // 行尾只留一颗「更多」（⋮）：爱心和时长都撤掉了。
            // 点赞收进菜单（不丢功能），时长对扫歌没有决策价值、却占了最贵的右边界。
            // 点它 = 长按同一份菜单，但会带上落点，让菜单从这颗按钮下面鼓出来（见 SongMoreAnchor）。
            //
            // 尺寸按参考实现的档位来：图标 19/21dp（原来 15/17dp 太小，三点会在低 DPR
            // 上糊成两点）、热区 30/34dp（源码 40dp 热区是为了裸图标也够点，
            // 我们还有极淡圆底当视觉锚，30dp 已经不小）、圆底更淡（0.10 → 0.08），
            // 让它更像"目标"而不是"按钮"。
            val moreSize = if (rowSize == 0) 30.dp else 34.dp
            var moreCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
            Box(
                Modifier
                    .size(moreSize)
                    // 导览第「⋮」步要照这颗按钮；同一时刻好几个页面都有歌曲行，
                    // 用带可见性过滤的登记，别让邻页那一行把洞抢走
                    .spotlightAnchorIfVisible(SpotlightKeys.MORE_MENU)
                    .onGloballyPositioned { moreCoords = it }
                    // junPressable 要在 clip/background 前：缩放图层只包住内侧绘制，圆底才跟着一起缩
                    // 0.70 比常规按压（0.88）深得多：小按钮要缩得明显才看得见"被按下去"，
                    // 松手再靠欠阻尼的 squishX/squishY 冲回 1 以上——那下过冲就是回弹。
                    .junPressable(
                        scaleDown = 0.70f,
                        feedback = PressFeedback.TAP
                    ) {
                        SongMoreAnchor.offset = moreCoords?.boundsInRoot()?.center
                        onLongPress()
                    }
                    .clip(CircleShape)
                    .background(colors.subText.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = JunIcons.More,
                    contentDescription = "更多操作",
                    tint = lerp(colors.subText, colors.primary.readableOn(colors.row, 3.0f), activeT),
                    modifier = Modifier.size(if (rowSize == 0) 19.dp else 21.dp)
                )
            }
            // 所属歌单名：右下角淡显示，多个歌单用「/」分隔
            if (showPlaylist && playlistNames.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    playlistNames.joinToString(" / "),
                    color = colors.primary.readableOn(colors.row, 3.0f),
                    fontSize = if (rowSize == 0) 9.sp else 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun FolderRow(name: String, count: String, active: Boolean, colors: JunColors, onClick: () -> Unit) {
    // 选中渐变：底色/文字/色块一起补间，切换文件夹不再硬跳
    val t by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(240),
        label = "folderActive"
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
        Box(Modifier.size(18.dp).clip(JunShape.chip)
            .background(lerp(colors.card, colors.primary, t)))
        Spacer(Modifier.width(12.dp))
        Text(name, Modifier.weight(1f), color = lerp(colors.text, colors.primary.readableOn(colors.row, 3.2f), t),
            fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count, color = colors.subText, fontSize = 11.sp)
    }
}



/**
 * 6.9.1 全局加载反馈条：取源全程贴底的一枚胶囊小条。
 *
 * 为什么单独做全局的：6.9.0 的反馈条加在播放卡顶部，但卡片只在打开播放页时
 * 可见——列表/搜索/榜单里点歌后的那几秒是真正的"屏幕死寂期"，弱网最坏 5 秒+
 * 什么都不显示，用户只会反复点同一行。这条与播放器状态无关，只要 ViewModel
 * 在取源（loadText 非空）就显示，拿到地址开始出声即消失。
 *
 * 进度语义与播放卡一致：buffered < 0 时走流动的不定进度（约 1.2s 一个来回），
 * 拿到地址进入缓冲后显示真实百分比；卡住超 5 秒出现"重试"。
 */
@Composable
fun GlobalLoadBar(
    text: String,
    buffered: Float,
    canRetry: Boolean,
    colors: JunColors,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val inf = rememberInfiniteTransition(label = "globalLoad")
    val shift by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "globalShift"
    )
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            Modifier
                .junSurface(
                    level = GlassLevel.SHEET,
                    colors = colors,
                    shape = JunShape.chip,
                    solid = colors.card
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(46.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.primary.copy(alpha = 0.15f))
            ) {
                if (buffered >= 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(buffered.coerceIn(0.03f, 1f))
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.primary)
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth(0.4f)
                            .height(4.dp)
                            .graphicsLayer { translationX = shift * (46.dp.toPx() * 0.6f) }
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.primary)
                    )
                }
            }
            Spacer(Modifier.width(9.dp))
            Text(
                text,
                color = colors.text,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (canRetry) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "重试",
                    color = colors.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onRetry)
                )
            }
        }
    }
}
