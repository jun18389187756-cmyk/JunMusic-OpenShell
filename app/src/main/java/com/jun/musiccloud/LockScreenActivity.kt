package com.jun.musiccloud

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.jun.musiccloud.data.LockScreenPrefs
import com.jun.musiccloud.data.LockScreenStyle
import com.jun.musiccloud.data.LyricLine
import com.jun.musiccloud.data.LyricParser
import com.jun.musiccloud.data.LyricRuntime
import com.jun.musiccloud.data.LyricTimeFit
import com.jun.musiccloud.playback.MusicService
import com.jun.musiccloud.playback.NowPlayingBus
import com.jun.musiccloud.playback.NowPlayingSnapshot
import com.jun.musiccloud.ui.ArtworkLoader
import com.jun.musiccloud.ui.JunIcons
import kotlin.jvm.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 锁屏音乐界面。
 *
 * 6.9.7 起是一个 `setShowWhenLocked` 的全屏 Activity，亮屏且未解锁时盖在锁屏之上，
 * 解锁后自动关闭。6.9.10 按用户给的酷狗截图加了五种样式（见 [LockScreenStyle]）。
 *
 * 为什么不用悬浮窗：那条路只能画一条文字，做不出封面 + 歌词 + 控制；
 * 也不用系统媒体卡片：那是 SystemUI 的样式，第三方改不了排布。
 */
class LockScreenActivity : ComponentActivity() {

    companion object {
        /** 从设置页"立即查看"进来时带这个标记：不锁屏也要看得到，方便预览样式。 */
        const val EXTRA_PREVIEW = "lock_preview"

        // 首帧缓存：只存简单字符串，绝不持有 Context / Activity / Bitmap，避免泄漏。
        // 亮屏瞬间先用上次的文字把内容画出来，等真实 StateFlow / 歌词加载到达再无缝替换。
        @Volatile var lastSongTitle: String = ""
        @Volatile var lastSongArtist: String = ""
        @Volatile var lastCoverKey: String = ""
        @Volatile var lastLyricLine: String = ""
    }

    private val preview by lazy { intent?.getBooleanExtra(EXTRA_PREVIEW, false) == true }

    private val keyguard by lazy { getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager }

    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 6.9.12：**预览也要盖在锁屏上**。
        // 旧版 preview 分支跳过了 setShowWhenLocked，用户在设置里点「立即查看」时
        // 如果正处于锁屏，Activity 会被系统锁屏挡在下面 —— 用户结论就是
        // "这个锁屏样式功能一点都没效果"。preview 与正常模式的差别只剩"不自动 finish"。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 6.9.12：状态栏/导航栏**整个隐藏**，而不是只把颜色设透明。
        //
        // 之前只设了 statusBarColor = TRANSPARENT（API 35 上已失效），状态栏本身还在：
        // 图标压在封面/封套上，深色封面时是一排看不见的图标，浅色封面时是一条黑带，
        // 用户的原话是"最上面那个黑标太恶心了""上面那些状态栏全是黑色的"。
        // 锁屏本来就不该有系统栏 —— 直接 hide + 允许从边缘临时滑出。
        runCatching {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.isAppearanceLightStatusBars = false
            c.isAppearanceLightNavigationBars = false
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // 刘海/挖孔机型：SHORT_EDGES 让内容延伸进刘海区，不再被压成黑条
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // 预览窗口/起始窗口设为透明，避免启动瞬间白屏/黑屏被当成「慢」；
        // 五个样式都会自己画满全屏背景，透明不会露出空洞。
        window.setBackgroundDrawableResource(android.R.color.transparent)

        // 首帧优先：尽早 setContent，让 Compose 第一帧就能画出来。
        setContent {
            // 6.9.12：整页下滑即可解锁进入桌面。
            // 手势在 Final 阶段**只观察不消费**，进度条横拖、歌词点击照常工作。
            val unlock: () -> Unit = {
                runCatching { keyguard?.requestDismissKeyguard(this, null) }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var total = 0f
                            while (true) {
                                val e = awaitPointerEvent(
                                    androidx.compose.ui.input.pointer.PointerEventPass.Final
                                )
                                val c = e.changes.firstOrNull() ?: break
                                total += c.positionChange().y
                                if (!c.pressed) break
                            }
                            if (total > 120.dp.toPx()) unlock()
                        }
                    }
            ) {
                LockScreenMusic(
                    onDismiss = { finish() },
                    onUnlock = unlock
                )
            }
        }

        // 非首帧必需的工作延后到首帧之后：广播注册、返回键回调都不参与绘制，
        // 留在 onCreate 同步块里只会拖慢第一帧。
        window.decorView.post {
            // 亮屏时若其实已解锁，onResume 会直接 finish；此时别再注册，避免 receiver 泄漏。
            if (!isFinishing && !isDestroyed) {
                runCatching { registerReceiver(userPresent, IntentFilter(Intent.ACTION_USER_PRESENT)) }
                onBackPressedDispatcher.addCallback(this@LockScreenActivity, object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() { finish() }
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!preview && keyguard?.isKeyguardLocked == false) finish()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(userPresent) }
        // 6.7 修：用户按返回 / 点「收起」/ 下滑解锁后，撤掉 MusicService 那条
        // fullScreenIntent 通知并复位「本次亮屏已拉起」标记，否则下次亮屏不会再弹。
        // 同进程直接取 instance，不走广播，没有额外延迟。
        runCatching { MusicService.instance?.cancelLockScreenKick() }
        super.onDestroy()
    }
}

// ==================== 数据与分发 ====================

private class LockUi(
    val snap: NowPlayingSnapshot,
    val position: Long,
    val now: Long,
    val timed: List<LyricLine>,
    val active: Int,
    /** 歌词是否已完成本轮加载：区分「还在加载」与「已加载但为纯音乐」。 */
    val lyricsLoaded: Boolean = true
) {
    val prev: String get() = timed.getOrNull(active - 1)?.text.orEmpty()
    val current: String get() = timed.getOrNull(active)?.text.orEmpty()
    val next: String get() = timed.getOrNull(active + 1)?.text.orEmpty()
}

/**
 * 首帧数据：优先用 StateFlow 当前值（同步、不阻塞）。
 * 若服务这次进程还没 publish 过（updatedAt==0 的默认空快照），就用静态缓存把
 * 歌名/歌手/封面 key 顶上，保证亮屏瞬间就有文字，而不是等下一次 publish。
 */
private fun firstFrameSnapshot(): NowPlayingSnapshot {
    val live = NowPlayingBus.state.value
    if (live.updatedAt > 0L) {
        // 6.8 修：锁屏复查 —— 即便已拿到实时快照，标题/封面为空也用上次缓存兜底，
        // 避免「亮屏首帧是默认 Jun Music / 空白封面」造成的既视延迟感。
        return live.copy(
            title = live.title.ifBlank { LockScreenActivity.lastSongTitle },
            artist = live.artist.ifBlank { LockScreenActivity.lastSongArtist },
            artworkKey = live.artworkKey.ifBlank { LockScreenActivity.lastCoverKey }
        )
    }
    return live.copy(
        title = LockScreenActivity.lastSongTitle.ifBlank { live.title },
        artist = LockScreenActivity.lastSongArtist.ifBlank { live.artist },
        artworkKey = LockScreenActivity.lastCoverKey.ifBlank { live.artworkKey }
    )
}

@Composable
private fun LockScreenMusic(onDismiss: () -> Unit, onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf(firstFrameSnapshot()) }
    var position by remember { mutableLongStateOf(0L) }
    var lines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    // 歌词走 IO 异步加载，首帧必然还没好；用它区分「加载中」和「已加载但纯音乐」。
    var lyricsLoaded by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    val style = remember { LockScreenPrefs.style(ctx) }
    val customBg = remember { LockScreenPrefs.customBackground(ctx) }

    LaunchedEffect(Unit) {
        NowPlayingBus.state.collect { s ->
            // 忽略「服务还没 publish 过」的默认空快照，别把首帧缓存文字冲掉；
            // 真实快照（updatedAt>0）一到就无缝替换。
            if (s.updatedAt > 0L) snap = s
        }
    }
    // 播放位置：250ms 一跳。锁屏上没有逐帧动画，纯读字段，代价可以忽略。
    LaunchedEffect(Unit) {
        while (true) {
            position = MusicService.instance?.lockScreenPlayer()?.currentPosition ?: snap.positionMs
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    LaunchedEffect(snap.lyricKey, snap.durationMs, snap.title) {
        lyricsLoaded = false
        lines = withContext(Dispatchers.IO) {
            runCatching { LyricParser.loadLyrics(snap.lyricKey.ifBlank { null }, snap.durationMs) }
                .getOrDefault(emptyList())
        }
        lyricsLoaded = true
    }

    // 解析层已经做过时长拟合，这里不再重复缩放（重复调用虽幂等，但白白 map 一遍）。
    val timed = lines
    // 6.9.11：锁屏此前也读裸位置，全局校准偏移没生效。语义与主歌词页一致：正 = 延后。
    val offset = remember { runCatching {
        ctx.getSharedPreferences("jun_prefs", Context.MODE_PRIVATE).getInt("lyric_offset", 0)
    }.getOrDefault(0) }
    val calibrated = (position - offset).coerceAtLeast(0L)
    val ui = LockUi(snap, position, now, timed, LyricRuntime.activeIndex(timed, calibrated), lyricsLoaded)

    // 每次真实数据到达，刷新静态首帧缓存（只存简单字符串，绝不持有 Context/Bitmap）。
    LaunchedEffect(snap, lines, ui.active) {
        if (snap.updatedAt > 0L) {
            LockScreenActivity.lastSongTitle = snap.title
            LockScreenActivity.lastSongArtist = snap.artist
            LockScreenActivity.lastCoverKey = snap.artworkKey
        }
        val cur = ui.current
        if (cur.isNotBlank()) LockScreenActivity.lastLyricLine = cur
    }

    when (style) {
        LockScreenStyle.ARTIST -> ArtistLock(ui, null, onDismiss, onUnlock)
        LockScreenStyle.CUSTOM -> ArtistLock(ui, customBg, onDismiss, onUnlock)
        LockScreenStyle.ALBUM -> AlbumLock(ui, onDismiss, onUnlock)
        LockScreenStyle.BLESSING -> BlessingLock(ui, onDismiss, onUnlock)
        LockScreenStyle.APPLE -> AppleLock(ui, onDismiss, onUnlock)
    }
}

// ==================== 背景 ====================

/** 封面放大 + 模糊 + 压暗。模糊在 Android 12 以下自动失效，仍有余下两层渐变兜底。 */
@Composable
private fun CoverBackdrop(artworkKey: String?, seed: Int, blurRadius: Dp = 44.dp, dim: Float = 0.55f) {
    var bmp by remember(artworkKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(artworkKey) {
        bmp = ArtworkLoader.load(artworkKey)?.asImageBitmap()
    }
    Box(Modifier.fillMaxSize()) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    // 模糊会让边缘透明，放大 1.25 倍把毛边推出屏幕
                    .graphicsLayer { scaleX = 1.25f; scaleY = 1.25f }
                    .blur(blurRadius)
            )
        } else {
            val hue = seed.mod(360L).toFloat()
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.hsv(hue, 0.45f, 0.32f),
                                Color.hsv((hue + 40f) % 360f, 0.40f, 0.16f),
                                Color(0xFF07070B)
                            )
                        )
                    )
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.30f + dim * 0.25f),
                        0.45f to Color.Black.copy(alpha = dim * 0.55f),
                        1f to Color.Black.copy(alpha = 0.80f)
                    )
                )
        )
    }
}

/** 用户相册图。读不出来就退回封面背景，不做空屏。 */
@Composable
private fun UriBackdrop(uri: String?, fallbackKey: String?, seed: Int, dim: Float = 0.45f) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        if (uri.isNullOrBlank()) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { s ->
                    BitmapFactory.decodeStream(s)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize()) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.28f),
                            0.45f to Color.Black.copy(alpha = dim),
                            1f to Color.Black.copy(alpha = 0.82f)
                        )
                    )
            )
        } else {
            CoverBackdrop(fallbackKey, seed, dim = dim + 0.1f)
        }
    }
}

// ==================== 样式 1/3：歌手写真 / 自定义背景 ====================

@Composable
private fun ArtistLock(
    ui: LockUi,
    customBg: String?,
    onDismiss: () -> Unit,
    onUnlock: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        if (customBg != null) {
            UriBackdrop(customBg, ui.snap.artworkKey, ui.snap.title.hashCode())
        } else {
            CoverBackdrop(ui.snap.artworkKey, ui.snap.title.hashCode())
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp)
                .padding(top = 58.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BigClock(ui.now, Color.White, Color.White.copy(alpha = 0.62f), 62.sp)
            Spacer(Modifier.weight(0.9f))
            LyricBlock(
                ui, Color.White, Color.White.copy(alpha = 0.34f),
                currentSize = 21.sp, sideSize = 13.sp, maxWidth = 0.92f
            )
            Spacer(Modifier.weight(0.75f))
            TrackLine(ui, Color.White, Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.height(14.dp))
            ProgressBar(ui, Color.White, Color.White.copy(alpha = 0.20f))
            Spacer(Modifier.height(16.dp))
            Controls(ui, Color.White, Color(0xFF111117))
            Spacer(Modifier.weight(0.35f))
            UnlockBar(onUnlock, onDismiss)
        }
    }
}

// ==================== 样式 2：专辑封面 ====================

@Composable
private fun AlbumLock(ui: LockUi, onDismiss: () -> Unit, onUnlock: () -> Unit) {
    val hue = ui.snap.title.hashCode().mod(360L).toFloat()
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.hsv(hue, 0.30f, 0.22f),
                        Color(0xFF0B0B12),
                        Color(0xFF05050A)
                    )
                )
            )
    ) {
        // 顶部一层和封面同色系的柔光，避免整屏死黑
        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color.hsv(hue, 0.55f, 0.60f).copy(alpha = 0.30f), Color.Transparent),
                        radius = 900f
                    )
                )
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp)
                .padding(top = 54.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BigClock(ui.now, Color.White, Color.White.copy(alpha = 0.55f), 52.sp)
            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .size(228.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
            ) {
                CoverImage(ui.snap.artworkKey, ui.snap.title.hashCode())
            }
            Spacer(Modifier.height(18.dp))
            TrackLine(ui, Color.White, Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.height(14.dp))
            LyricBlock(
                ui, Color.White, Color.White.copy(alpha = 0.30f),
                currentSize = 17.sp, sideSize = 12.sp, maxWidth = 0.94f
            )
            Spacer(Modifier.weight(1f))
            ProgressBar(ui, Color(0xFF8FE3D0), Color.White.copy(alpha = 0.18f))
            Spacer(Modifier.height(16.dp))
            Controls(ui, Color.White, Color(0xFF111117))
            Spacer(Modifier.height(14.dp))
            UnlockBar(onUnlock, onDismiss)
        }
    }
}

// ==================== 样式 4：祈福祝愿 ====================

@Composable
private fun BlessingLock(ui: LockUi, onDismiss: () -> Unit, onUnlock: () -> Unit) {
    val gold = Color(0xFFFFD98A)
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF5A0D12), Color(0xFF8E1418), Color(0xFF3A0609))
                )
            )
    ) {
        // 四角金线：用两条极淡的径向光斑模拟年画的暖光
        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFFFF6A5A).copy(alpha = 0.28f), Color.Transparent),
                        radius = 800f,
                        center = androidx.compose.ui.geometry.Offset(200f, 120f)
                    )
                )
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp)
                .padding(top = 58.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "恭喜发财 · 万事顺遂",
                color = gold.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(8.dp))
            BigClock(ui.now, gold, gold.copy(alpha = 0.55f), 60.sp)
            Spacer(Modifier.weight(0.8f))
            LyricBlock(
                ui, gold, gold.copy(alpha = 0.34f),
                currentSize = 22.sp, sideSize = 13.sp, maxWidth = 0.92f
            )
            Spacer(Modifier.weight(0.8f))
            TrackLine(ui, Color(0xFFFFF1D6), Color(0xFFFFF1D6).copy(alpha = 0.55f))
            Spacer(Modifier.height(14.dp))
            ProgressBar(ui, gold, Color.White.copy(alpha = 0.16f))
            Spacer(Modifier.height(16.dp))
            Controls(ui, gold, Color(0xFF5A0D12))
            Spacer(Modifier.weight(0.35f))
            UnlockBar(onUnlock, onDismiss)
        }
    }
}

// ==================== 样式 5：苹果风格 ====================

@Composable
private fun AppleLock(ui: LockUi, onDismiss: () -> Unit, onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        CoverBackdrop(ui.snap.artworkKey, ui.snap.title.hashCode(), blurRadius = 70.dp, dim = 0.70f)
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp)
                .padding(top = 64.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ui.now)),
                color = Color.White,
                fontSize = 78.sp,
                fontWeight = FontWeight.Thin,
                letterSpacing = (-1).sp
            )
            Text(
                SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date(ui.now)),
                color = Color.White.copy(alpha = 0.60f),
                fontSize = 13.sp
            )
            Spacer(Modifier.weight(1f))
            // 底部毛玻璃卡片
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(28.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.10f))
                    ) { CoverImage(ui.snap.artworkKey, ui.snap.title.hashCode()) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            ui.snap.title.ifBlank { "Jun Music" },
                            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            ui.snap.artist.ifBlank { " " },
                            color = Color.White.copy(alpha = 0.60f), fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                LyricBlock(
                    ui, Color.White, Color.White.copy(alpha = 0.34f),
                    currentSize = 15.sp, sideSize = 11.sp, maxWidth = 1f
                )
                Spacer(Modifier.height(10.dp))
                ProgressBar(ui, Color.White, Color.White.copy(alpha = 0.20f))
                Spacer(Modifier.height(10.dp))
                Controls(ui, Color.White, Color(0xFF16161C), compact = true)
            }
            Spacer(Modifier.height(14.dp))
            UnlockBar(onUnlock, onDismiss)
        }
    }
}

// ==================== 公共部件 ====================

@Composable
private fun BigClock(now: Long, color: Color, dateColor: Color, size: androidx.compose.ui.unit.TextUnit) {
    Text(
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)),
        color = color,
        fontSize = size,
        fontWeight = FontWeight.Light
    )
    Text(
        SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date(now)),
        color = dateColor,
        fontSize = 13.sp
    )
}

@Composable
private fun LyricBlock(
    ui: LockUi,
    primary: Color,
    secondary: Color,
    currentSize: androidx.compose.ui.unit.TextUnit,
    sideSize: androidx.compose.ui.unit.TextUnit,
    maxWidth: Float
) {
    Column(
        Modifier
            .fillMaxWidth(maxWidth)
            .height(92.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 6.9.11：歌词能点了 —— 点上一句/下一句直接跳到那句起唱，点当前句重播本句。
        fun seekToLine(index: Int) {
            val line = ui.timed.getOrNull(index) ?: return
            val target = line.timeMs.coerceAtLeast(0L)
            MusicService.instance?.lockScreenPlayer()?.seekTo(target)
        }
        Text(
            ui.prev,
            color = secondary, fontSize = sideSize,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.noRippleClickable(enabled = ui.active > 0) { seekToLine(ui.active - 1) }
        )
        Spacer(Modifier.height(7.dp))
        // 首帧歌词还在 IO 加载时，先用静态缓存的上一句把文字画出来；
        // 加载完成（lyricsLoaded=true）后无缝切回真实当前句。
        val currentText = when {
            ui.current.isNotBlank() -> ui.current
            !ui.lyricsLoaded && LockScreenActivity.lastLyricLine.isNotBlank() ->
                LockScreenActivity.lastLyricLine
            ui.timed.isEmpty() -> "纯音乐 · 请享受"
            else -> ""
        }
        Text(
            currentText,
            color = primary,
            fontSize = currentSize,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = (currentSize.value * 1.35f).sp,
            modifier = Modifier.noRippleClickable { seekToLine(ui.active) }
        )
        Spacer(Modifier.height(7.dp))
        Text(
            ui.next,
            color = secondary, fontSize = sideSize,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.noRippleClickable { seekToLine(ui.active + 1) }
        )
    }
}

/** 锁屏页专用：无涟漪点击。enabled=false 时不吃事件也不留手势痕迹。 */
private fun Modifier.noRippleClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    if (!enabled) this else this.then(
        Modifier.pointerInput(Unit) {
            detectTapGestures { onClick() }
        }
    )

@Composable
private fun TrackLine(ui: LockUi, titleColor: Color, subColor: Color) {
    Text(
        ui.snap.title.ifBlank { "Jun Music" },
        color = titleColor, fontSize = 19.sp, fontWeight = FontWeight.Black,
        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(3.dp))
    Text(
        ui.snap.artist.ifBlank { " " },
        color = subColor, fontSize = 12.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
    )
}

@Composable
private fun ProgressBar(ui: LockUi, active: Color, track: Color) {
    val dur = ui.snap.durationMs.coerceAtLeast(1L)
    val frac = (ui.position.toFloat() / dur).coerceIn(0f, 1f)
    // 6.9.11：锁屏进度条可以拖了。拖动时本地 state 顶住显示，
    // 松手才 seek —— 跟着 250ms 轮询跳的旧版在拖动中会来回打架。
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }
    val shown = if (dragging) dragFrac else frac
    var widthPx by remember { mutableStateOf(1f) }
    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { off ->
                        dragging = true
                        dragFrac = (off.x / widthPx).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        val target = (dragFrac * dur).toLong().coerceIn(0L, dur)
                        MusicService.instance?.lockScreenPlayer()?.seekTo(target)
                        dragging = false
                    },
                    onDragCancel = { dragging = false }
                ) { change, _ ->
                    dragFrac = (change.position.x / widthPx).coerceIn(0f, 1f)
                    change.consume()
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .onSizeChanged { if (it.width > 0) widthPx = it.width.toFloat() },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(track)
            )
            Box(
                Modifier
                    .fillMaxWidth(shown)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(active)
            )
            if (dragging) {
                Box(
                    Modifier
                        .graphicsLayer { translationX = dragFrac * widthPx - 14f }
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(active)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                fmt(if (dragging) (dragFrac * dur).toLong() else ui.position),
                color = active.copy(alpha = 0.75f), fontSize = 11.sp
            )
            Text(fmt(ui.snap.durationMs), color = active.copy(alpha = 0.75f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun Controls(ui: LockUi, tint: Color, playInk: Color, compact: Boolean = false) {
    val big = if (compact) 52.dp else 64.dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ControlButton(JunIcons.SkipPrevious, if (compact) 24.dp else 27.dp, tint) {
            MusicService.instance?.lockScreenPlayer()?.seekToPreviousMediaItem()
        }
        Box(
            Modifier
                .size(big)
                .clip(CircleShape)
                .background(if (compact) tint.copy(alpha = 0.92f) else tint)
                .clickable {
                    MusicService.instance?.lockScreenPlayer()?.let { p ->
                        if (p.isPlaying) p.pause() else p.play()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (ui.snap.playing) JunIcons.Pause else JunIcons.Play,
                contentDescription = if (ui.snap.playing) "暂停" else "播放",
                tint = playInk,
                modifier = Modifier.size(if (compact) 24.dp else 28.dp)
            )
        }
        ControlButton(JunIcons.SkipNext, if (compact) 24.dp else 27.dp, tint) {
            MusicService.instance?.lockScreenPlayer()?.seekToNextMediaItem()
        }
    }
}

@Composable
private fun UnlockBar(onUnlock: () -> Unit, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "解锁",
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .clickable { onUnlock() }
                .padding(horizontal = 22.dp, vertical = 9.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "收起",
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .clickable { onDismiss() }
                .padding(horizontal = 22.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun CoverImage(artworkKey: String?, seed: Int) {
    var bmp by remember(artworkKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(artworkKey) {
        bmp = ArtworkLoader.load(artworkKey)?.asImageBitmap()
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        val hue = seed.mod(360L).toFloat()
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(Color.hsv(hue, 0.34f, 0.36f), Color.hsv((hue + 30f) % 360f, 0.30f, 0.22f))
                    )
                )
        )
    }
}

@Composable
private fun ControlButton(icon: ImageVector, size: Dp, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size + 26.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}

private fun fmt(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}
