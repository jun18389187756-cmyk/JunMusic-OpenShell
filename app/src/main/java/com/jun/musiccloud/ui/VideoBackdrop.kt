package com.jun.musiccloud.ui

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.jun.musiccloud.ui.theme.DevicePerformance
import kotlinx.coroutines.delay

/**
 * 本地视频背景：垫在歌词层最下面的一层静音循环 MP4。
 *
 * 用 TextureView + Media3 ExoPlayer，而不是 VideoView：VideoView 是 SurfaceView，
 * 独立于窗口合成，alpha/变换不生效、还会在歌词上挖一个洞；TextureView 是
 * 普通 View，能被 Compose 正常绘制和调透明度，也能压在毛玻璃下。
 * ExoPlayer 比 MediaPlayer 更稳：切后台、seek、循环、解码器回收都由它统一处理。
 *
 * 跟随播放：播放/暂停切换；seek 跳变或漂移超过 1.2 秒才纠正，避免频繁
 * seekTo 把解码器拖卡。视频比歌曲短时按视频时长循环。
 */
@Composable
internal fun VideoBackdrop(
    uri: String,
    isPlaying: Boolean,
    positionMs: Long,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    if (uri.isBlank()) return
    // 老机安全档：整层不建。
    //
    // 为什么这条对 nova 6 这一类机器是"风险"而不只是"卡"：视频背景会再起一个
    // ExoPlayer，也就是第二路 MediaCodec 硬解 + 第二个 SurfaceTexture，
    // 而正在放歌的那一路自己就占着一路解码器。华为/麒麟平台的硬件解码器
    // 实例数配额本来就紧（Kirin 的 OMX 组件同时只给很少的 slot），
    // 并发不足时 mediacodec/codec2 会走到"等待可用组件"的阻塞路径上——
    // 表现不是 App 崩溃，而是音频与画面一起僵住、系统显示层被拖到异常，
    // 正好符合"闪一下跳到密码屏、只能强制重启"。
    // 判定与开关都在 DevicePerformance（legacy_fx_video 可单独强制打开）。
    if (!DevicePerformance.allowVideoBackdrop) return
    val context = LocalContext.current
    var player by remember(uri) { mutableStateOf<ExoPlayer?>(null) }
    var ready by remember(uri) { mutableStateOf(false) }
    var videoDuration by remember(uri) { mutableLongStateOf(0L) }
    val playingNow by rememberUpdatedState(isPlaying)
    val positionNow by rememberUpdatedState(positionMs)

    DisposableEffect(uri) {
        // 建播放器与配置都要包起来：老 ROM 上 ExoPlayer.Builder().build() 会因为在
        // 等硬件解码器而抛 IllegalStateException / 或直接返回一个建不起 codec 的实例。
        // 这里失败的正确结果是"没有视频背景"，不是"整个歌词层炸掉"。
        val built = runCatching { ExoPlayer.Builder(context).build() }.getOrNull()
        val exo = built?.let { p ->
            runCatching {
                p.apply {
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = 0f
                    // 视频背景不要抢音频焦点，否则会打断正在播的歌。
                    setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, false)
                    setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
                    playWhenReady = playingNow
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            ready = state == Player.STATE_READY
                            if (state == Player.STATE_READY) {
                                videoDuration = duration.coerceAtLeast(0L)
                                if (positionNow > 0L && duration > 0) {
                                    seekTo(positionNow % duration)
                                }
                            }
                        }
                    })
                    prepare()
                }
            }.getOrElse {
                // 配置一半失败：把已建出来的实例放掉，否则它自己占着解码器不放
                runCatching { p.release() }
                null
            }
        }
        player = exo
        onDispose {
            exo?.let { runCatching { it.release() } }
            player = null
            ready = false
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.coerceIn(0f, 0.9f) },
        factory = { ctx ->
            TextureView(ctx).also { view ->
                player?.setVideoTextureView(view)
            }
        },
        update = { view ->
            val p = player ?: return@AndroidView
            // 视图复用到新位置时，SurfaceTexture 可能已经可用，重新绑定一次。
            p.setVideoTextureView(view)
            p.playWhenReady = isPlaying
        }
    )

    // 漂移纠正：只在大跳或累计偏差超过 1.2 秒时 seek，播放中不每帧摸解码器。
    LaunchedEffect(uri, isPlaying, positionMs / 1000L) {
        val p = player ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val duration = videoDuration
        if (duration > 0) {
            val target = (positionMs % duration).coerceAtLeast(0L)
            val drift = kotlin.math.abs(p.currentPosition - target)
            // 循环点附近允许一个大偏差：视频刚绕回开头时不做无谓 seek。
            val wrapped = duration - drift
            if (drift > 1200 && wrapped > 1200) runCatching { p.seekTo(target) }
        }
        p.playWhenReady = isPlaying
        delay(1500)
        if (duration > 0 && isPlaying) {
            val target = (positionMs % duration).coerceAtLeast(0L)
            if (kotlin.math.abs(p.currentPosition - target) > 1800) {
                runCatching { p.seekTo(target) }
            }
        }
    }

    // 可读性暗角：视频再亮，歌词区也不会被吃掉。
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f)),
                    radius = 1600f
                )
            )
    )
}
