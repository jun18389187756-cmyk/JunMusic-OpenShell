package com.jun.musiccloud.playback

import com.jun.musiccloud.R
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.MediaSessionService
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioManager
import android.content.Intent
import com.jun.musiccloud.MainActivity
import com.jun.musiccloud.audio.BassHaptics
import com.jun.musiccloud.player.ACTION_PLAY_WITH_OTHERS
import com.jun.musiccloud.audio.EqualizerController
import com.jun.musiccloud.audio.EqProcessor
import com.jun.musiccloud.audio.FadeController
import com.jun.musiccloud.audio.RangeEnhancer
import com.jun.musiccloud.audio.SafeLimiter
import com.jun.musiccloud.audio.SilenceSkipper
import com.jun.musiccloud.audio.SpatialWide
import com.jun.musiccloud.audio.SpectrumAnalyzer
import com.jun.musiccloud.audio.TrackGain
import com.jun.musiccloud.audio.VirtualBass
import com.jun.musiccloud.audio.VirtualSurround
import com.jun.musiccloud.data.AudioQualityStore
import com.jun.musiccloud.data.ListenStats
import com.jun.musiccloud.data.NeteaseStore
import com.jun.musiccloud.data.PlaybackCache
import com.jun.musiccloud.data.lyricKey
import com.jun.musiccloud.ui.ArtworkLoader
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
// 6.7 修：锁屏亮屏即见（fullScreenIntent 通知）+ 通知栏歌词（自定义媒体通知 Provider）
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 前台播放服务：持有 ExoPlayer 与 MediaSession。
 * UI 侧通过 MediaController 连接本服务进行控制，保证后台/通知栏播放。
 */
class MusicService : MediaLibraryService() {

    /** 把一首歌转成可播放的 MediaItem（在线走本地代理，SAF 本地曲目直接播放 content URI） */
    private fun toMediaItem(song: com.jun.musiccloud.data.Song): MediaItem = MediaItem.Builder()
        .setMediaId(song.id.toString())
        .setUri(song.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album.ifBlank { "Jun Music" })
                .setArtworkUri(song.albumArtUri)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(
                    android.os.Bundle().apply {
                        putString("file_path", song.filePath)
                        putString("lyric_key", song.lyricKey)
                        // 6.3.2 车机歌词：见 BluetoothLyricBridge
                        putLong("duration_ms", song.durationMs)
                    }
                )
                .build()
        ).build()


    private var mediaSession: MediaLibrarySession? = null
    private var sleepUnsub: (() -> Unit)? = null

    /** 小组件封面缓存：下载/解码放在 IO 线程，避免卡播放主线程。 */
    private val widgetIo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var widgetArtJob: Job? = null
    private val appPrefs by lazy { getSharedPreferences("jun_prefs", Context.MODE_PRIVATE) }

    /** 播放状态总线 / Automix 的轻量轮询（500ms，只做几个标量读取） */
    private val mixTick = object : Runnable {
        override fun run() {
            publishNowPlaying()
            automixStep()
            tickHandler.postDelayed(this, 500L)
        }
    }

    private fun publishNowPlaying() {
        val p = mediaSession?.player
        val item = p?.currentMediaItem
        val md = item?.mediaMetadata
        val duration = p?.duration?.takeIf { it > 0L } ?: 0L
        NowPlayingBus.publish(
            NowPlayingSnapshot(
                title = md?.title?.toString().orEmpty().ifBlank { "Jun Music" },
                artist = md?.artist?.toString().orEmpty(),
                album = md?.albumTitle?.toString().orEmpty(),
                playing = p?.isPlaying == true,
                positionMs = p?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                durationMs = duration,
                lyricKey = md?.extras?.getString("lyric_key").orEmpty(),
                artworkKey = md?.extras?.getString("file_path").orEmpty(),
                currentIndex = p?.currentMediaItemIndex ?: -1,
                queueSize = p?.mediaItemCount ?: 0,
                volume = FadeController.userGain(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 智能 Automix：曲尾按风格提前淡出，并在合适的时间点切到下一首。
     *
     * 单 ExoPlayer 无法真正双流交叉，所以做的是「安全淡切」：淡出当前曲 →
     * 立刻起下一首（FadeController 会给新曲 150ms 防爆音渐入）。听感上不会硬切，
     * 也不会像传统交叉淡化那样需要两条解码链路。风格只改变淡出窗口与切点。
     */
    private fun automixStep() {
        val p = mediaSession?.player ?: return
        if (!appPrefs.getBoolean("automix_enabled", false)) {
            FadeController.resetAutomixGain()
            return
        }
        if (!p.isPlaying || p.playbackState != Player.STATE_READY ||
            p.repeatMode == Player.REPEAT_MODE_ONE || p.mediaItemCount <= 1
        ) {
            FadeController.resetAutomixGain()
            return
        }
        if (!p.hasNextMediaItem()) {
            FadeController.resetAutomixGain()
            return
        }
        val duration = p.duration
        if (duration <= 0L) return
        val remaining = duration - p.currentPosition
        val style = appPrefs.getInt("automix_style", 0).coerceIn(0, 2)
        val leadMs = when (style) { 1 -> 12_000L; 2 -> 900L; else -> 6_000L }
        val cutMs = when (style) { 1 -> 1_500L; 2 -> 300L; else -> 700L }
        val gain = if (remaining in 1..leadMs) {
            ((remaining - cutMs).toFloat() / (leadMs - cutMs).toFloat()).coerceIn(0f, 1f)
        } else 1f
        FadeController.setAutomixGain(gain)
        if (remaining <= cutMs) {
            FadeController.resetAutomixGain()
            runCatching { p.seekToNextMediaItem() }
        }
    }

    /**
     * 下发倍速（5.1 新增）。
     *
     * 只设 speed、pitch 固定 1f：ExoPlayer 走时间拉伸，变速不变调。
     * 读取处再夹一次范围，prefs 被别的版本写坏也不会把音频搞没。
     */
    private fun applyPlaybackSpeed(p: Player) {
        val bp = appPrefs.getInt(KEY_PLAYBACK_SPEED_BP, 100)
        val speed = (bp / 100f).coerceIn(0.75f, 2f)
        val params = androidx.media3.common.PlaybackParameters(speed, 1f)
        runCatching { p.setPlaybackParameters(params) }
            .onFailure { android.util.Log.w("MusicService", "speed apply failed: ${it.message}") }
    }

    /** 队列里后面 4 首提前解析地址；不碰播放器状态，正在播的不受影响 */
    private fun prefetchUpcoming(p: Player) {
        val timeline = runCatching { p.currentTimeline }.getOrNull() ?: return
        if (timeline.windowCount == 0) return
        val start = p.currentMediaItemIndex
        val ids = ArrayList<String>(4)
        // 6.3.2：预取窗口 2 → 4。GD 一次解析 1~2 秒，只提前一首时用户连点两次
        // 「下一首」就会连吃两次等待；4 首 + 3 路并发预取，正常听歌基本不会撞上冷解析。
        for (i in (start + 1) until minOf(start + 5, timeline.windowCount)) {
            val w = timeline.getWindow(i, androidx.media3.common.Timeline.Window())
            val id = w.mediaItem?.mediaId.orEmpty()
            if (id.isNotBlank()) ids.add(id)
        }
        // 6.7.2：**下一首即时解析**（切歌要秒响），其余几首走普通预取队列。
        // 原来 4 首全塞队列，6 个预取线程被占满时，"下一首"反而要排在别人后面，
        // 用户按下一首就得等——这是"切歌慢"的隐性原因。
        if (ids.isNotEmpty()) {
            AudioProxyServer.prefetchNow(listOf(ids.first()))
            // 6.7.4：只预取紧跟着的 2 首。
            // 原来是 4 首全部提交，每次切歌都往队列里塞 3 个新任务；
            // 连点几下就是十几个解析任务叠在一起，再加上 QuickJS 单线程，
            // 反而把用户真正点的那一首堵死。
            if (ids.size > 1) AudioProxyServer.prefetch(ids.drop(1).take(2))
        }
    }

    /**
     * 换档位后立刻让当前这首歌按新档位重新解析并续播（5.2）。
     *
     * 做法：把当前这一段的 MediaItem 换成「同一条 URI、只多了一个 r= 随机参数」的新对象。
     * URI 变了 → ExoPlayer 重建这一段的 MediaSource → 本地代理重新收到 /audio 请求；
     * 而解析缓存已经被 [AudioProxyServer.clearResolved] 清空，这一次就是按新档位解析。
     * `r=` 参数代理不认，纯粹当 nonce 用，也不影响流式缓存的键（键只取 id + 档位）。
     * 换完 seek 回原进度，用户听到的还是同一句歌词。
     */
    @OptIn(UnstableApi::class)
    fun reloadCurrentForQuality() {
        val p = mediaSession?.player ?: return
        val item = p.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString().orEmpty()
        if (uri.isBlank()) return
        val index = p.currentMediaItemIndex
        val pos = p.currentPosition
        val wasPlaying = p.isPlaying
        runCatching {
            val fresh = item.buildUpon()
                .setUri("$uri&r=${System.currentTimeMillis()}")
                .build()
            p.replaceMediaItem(index, fresh)
            p.seekTo(index, pos)
            if (wasPlaying) p.play()
        }.onFailure { android.util.Log.w("MusicService", "reload for quality failed: ${it.message}") }
    }

    /**
     * 6.3.2：改缓存档位后安全重建缓存管道。
     *
     * 用户现场：「设置页把播放缓存改成 4G，紧接着就提示网络断了，这首放不出来。」
     *
     * 旧行为：设置页直接调 `PlaybackCache.release()` 把 SimpleCache 关掉，
     * 但播放器的数据源工厂是**构造期绑死的**（ExoPlayer.Builder 建好之后不再更新），
     * 它手里还握着那个已经被释放的缓存对象。下一次读缓存直接抛
     * IllegalStateException，被 ExoPlayer 归类成 IO 错误，界面上就显示成「网络断了」，
     * 而且这个坏状态在整个进程生命周期里都不会自愈——后面每首歌都报同样的错，
     * 必须杀掉 App 重开。
     *
     * 正确做法：暂停 → 释放旧缓存 → 按新容量建新缓存 → 给播放器换上新的
     * MediaSourceFactory → 把当前这首重新挂上并 seek 回原进度 → 恢复播放。
     * 全程 0.3~1 秒，用户只感到音乐停了半拍，不会看到任何报错。
     */
    @OptIn(UnstableApi::class)
    fun rebuildCachePipe() {
        val exo = exoPlayer ?: return
        val item = exo.currentMediaItem
        val index = exo.currentMediaItemIndex
        val pos = exo.currentPosition
        val wasPlaying = exo.isPlaying
        runCatching {
            if (wasPlaying) exo.pause()
            // 必须先释放：SimpleCache 对同一目录是独占文件锁，旧的不放新的就建不起来
            PlaybackCache.release()
            // 换底层工厂（新容量 + 新 SimpleCache）。播放器持有的 Swappable 壳不变，
            // 所以不会出现「工厂指向已释放对象」的死亡状态。
            val factory = PlaybackCache.dataSourceFactory(this, appPrefs)
            cacheSwappable?.swapTo(factory)
            if (item != null) {
                val uri = item.localConfiguration?.uri?.toString().orEmpty()
                if (uri.isNotBlank()) {
                    // 换个 nonce 强制 ExoPlayer 重建这一段的 MediaSource，
                    // 否则它会继续复用旧的（已失效的）数据源
                    val fresh = item.buildUpon()
                        .setUri("$uri&rc=${System.currentTimeMillis()}")
                        .build()
                    exo.replaceMediaItem(index, fresh)
                    exo.seekTo(index, pos)
                }
            }
            if (wasPlaying) exo.play()
        }.onFailure {
            android.util.Log.w("MusicService", "rebuild cache pipe failed: ${it.message}", it)
        }
    }

    /** 起播后节流查一次在线缓存占用，写满就提醒用户（后台线程，不碰播放路径） */
    private fun maybeCheckStreamCacheFull() {
        val now = System.currentTimeMillis()
        if (now - lastCacheCheckMs < CACHE_CHECK_MS) return
        lastCacheCheckMs = now
        runCatching {
            PlaybackCache.io.submit {
                runCatching { PlaybackCache.checkFull(this, appPrefs) }
            }
        }
    }

    /**
     * 拔出/回连监听（5.1 新增）。
     *
     * 只用系统级无权限广播 + AudioManager 查询，**不新增蓝牙权限**：
     * 申请 BLUETOOTH_CONNECT 要多一个运行时授权弹窗，收益不值。
     * 回连检测是 5 秒轮询，只在「确实因拔出而暂停」期间跑；续播成功或用户手动
     * 播起来就自停，不留常驻后台活动。
     */
    private val noisyHandler = Handler(Looper.getMainLooper())
    private var pausedByDeviceUnplug = false
    /** 6.6：短暂音频焦点丢失（微信语音/导航播报/通知提示音）之前确实在播。 */
    @Volatile private var pausedByFocusLoss = false
    private var resumeWatchStarted = false
    /** 在线缓存占用的上次统计时刻（毫秒），见 [maybeCheckStreamCacheFull] */
    private var lastCacheCheckMs = 0L

    // ==================== 后台断流自愈（Change 1） ====================
    /**
     * 后台播放断流/报错自动重连。
     *
     * 原来「断点续播」逻辑写在 UI 进程的 PlayerViewModel.onPlayerError 里，经跨进程
     * MediaController 重试。App 退到后台后 UI 进程被系统节流/回收，这条重试链就断了——
     * 表现为「后台放一会儿就停，切回前台才弹 '刚才那下没连上，正在断点续播' 然后才续上」。
     * 现在把自愈搬进常驻的播放服务进程，不依赖 UI 也能秒级重连。
     */
    private val reconnectHandler = Handler(Looper.getMainLooper())
    @Volatile private var reconnectAttempts = 0
    private var lastReconnectAt = 0L
    /** 卡顿看门狗：缓冲超过此毫秒数仍卡着，视为死连接，触发重连 */
    private val BUFFER_STALL_MS = 15_000L
    private val RECONNECT_MAX = 6

    private val bufferingWatchdog = object : Runnable {
        override fun run() {
            val p = exoPlayer ?: return
            // 仍在缓冲且用户本意是播放（playWhenReady）→ 网络已掉，强制重连后继续盯
            if (p.playbackState == Player.STATE_BUFFERING && p.playWhenReady) {
                scheduleReconnect()
                reconnectHandler.postDelayed(this, BUFFER_STALL_MS)
            }
        }
    }

    /** 断流重连：回到当前进度重新 prepare 一次，指数退避，上限数次后停手。 */
    private fun scheduleReconnect() {
        val p = exoPlayer ?: return
        val now = SystemClock.elapsedRealtime()
        // 节流：同一秒内多次报错只算一次，避免刷屏式重连
        if (reconnectAttempts > 0 && now - lastReconnectAt < 1_000L) return
        if (reconnectAttempts >= RECONNECT_MAX) return
        val delay = (1_000L shl reconnectAttempts).coerceAtMost(30_000L) // 1s,2s,4s,8s,16s,32s→封顶30s
        lastReconnectAt = now
        reconnectAttempts++
        reconnectHandler.removeCallbacks(bufferingWatchdog)
        reconnectHandler.postDelayed({
            val pl = exoPlayer ?: return@postDelayed
            if (pl.playbackState == Player.STATE_ENDED) return@postDelayed
            val pos = pl.currentPosition.coerceAtLeast(0L)
            runCatching {
                pl.seekTo(pos)
                pl.prepare()
                pl.play()
            }.onFailure { android.util.Log.w("MusicService", "reconnect failed: ${it.message}") }
        }, delay)
        android.util.Log.w("MusicService", "scheduleReconnect attempt=$reconnectAttempts delay=${delay}ms")
    }

    /** 成功播放/切歌后复位重连计数，避免把上一段的失败记到下一首头上。 */
    private fun resetReconnect() {
        reconnectAttempts = 0
        lastReconnectAt = 0L
        reconnectHandler.removeCallbacks(bufferingWatchdog)
    }

    // ==================== 与其他应用同时播放（Change 2） ====================
    /**
     * 与其他应用同时播放：true 时播放器不抢/弃音频焦点，允许和其他 App 混音；
     * false（默认）保持原行为（Media3 自动管理焦点，被导航/微信语音等打断）。
     */
    @Volatile private var playWithOthers = false

    private val playWithOthersReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PLAY_WITH_OTHERS) return
            applyPlayWithOthers(intent.getBooleanExtra("on", false))
        }
    }

    /** 应用「与其他应用同时播放」开关：通过 Media3 的 handleAudioFocus 开关实现。 */
    private fun applyPlayWithOthers(on: Boolean) {
        playWithOthers = on
        val p = exoPlayer ?: return
        val attrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        // on=true：handleAudioFocus=false → 播放器不再请求/监听音频焦点，
        // 既不会被别家 App 打断压低，也能和别家 App 同时出声（即「同时播放」）。
        // on=false：恢复默认抢焦点行为。setAudioAttributes 内部会在启用/停用焦点时
        // 自动重新申请或释放焦点，等于「重新应用焦点」。
        runCatching { p.setAudioAttributes(attrs, !on) }
            .onFailure { android.util.Log.w("MusicService", "applyPlayWithOthers failed: ${it.message}") }
    }

    /**
     * 6.3.2：真正的 ExoPlayer 实例。
     *
     * 为什么必须单独存一份：`mediaSession.player` 拿到的是 [FadingPlayer]（用于接管
     * play/pause 走淡入淡出），它是 ForwardingPlayer，取不到被包装的底层实例。
     * 而「改缓存档位」需要调 `setMediaSourceFactory`——那是 ExoPlayer 的方法，
     * Player 接口上没有。
     */
    private var exoPlayer: ExoPlayer? = null

    /**
     * 6.3.2：可热替换的数据源工厂（见 [PlaybackCache.Swappable]）。
     *
     * 播放器构造时就把它交出去了，之后永远持有同一个对象。改缓存档位时
     * 只需要 [PlaybackCache.Swappable.swapTo]，播放器无感、不用重建、不会断音。
     */
    private var cacheSwappable: PlaybackCache.Swappable? = null

    private val noisierReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            val p = mediaSession?.player ?: return
            if (p.isPlaying) pausedByDeviceUnplug = true
            startResumeWatch()
        }
    }

    private val resumeTick = object : Runnable {
        override fun run() {
            val p = mediaSession?.player
            if (p == null || !pausedByDeviceUnplug) { resumeWatchStarted = false; return }
            if (p.isPlaying) { pausedByDeviceUnplug = false; resumeWatchStarted = false; return }
            if (externalAudioPresent() && p.currentPosition > 0) {
                pausedByDeviceUnplug = false
                resumeWatchStarted = false
                runCatching { FadeController.requestPlay() }
                return
            }
            noisyHandler.postDelayed(this, RESUME_WATCH_MS)
        }
    }

    private fun registerNoisyReceiver() {
        registerReceiver(noisierReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    // ==================== 6.9.7：锁屏音乐界面 ====================

    /**
     * 6.7 修：本次亮屏那一发 fullScreenIntent 通知是否还挂着。
     *
     * 6.6 用的是「上次拉起时刻 + 120ms 时间窗」防抖。时间窗虽只挡连发、不挡首帧，
     * 但仍是 App 自己能省掉的一层状态；更要紧的是它掩盖了真正的病根（见下）。
     * 改成布尔标记：SCREEN_ON 连发只发一次，解锁 / 熄屏 / 收起后复位，下次亮屏立刻能再发。
     */
    @Volatile private var lockScreenKickActive = false

    private val lockScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                // 6.7 修：触发点一直就是亮屏（不是解锁广播），保留。
                Intent.ACTION_SCREEN_ON -> maybeShowLockScreen()
                // 解锁 / 熄屏：立刻撤掉那条全屏拉起通知并复位标记。
                // 不撤的话通知栏会常驻一条，且下次亮屏会被 lockScreenKickActive 一直挡住。
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_OFF -> cancelLockScreenKick()
            }
        }
    }

    /**
     * 亮屏 + 还锁着 + 正在播放 + 开关打开 → 把锁屏音乐界面盖到 Keyguard 之上。
     *
     * 6.7 修：为什么以前「要等解锁才闪一下」
     * --------------------------------------------------
     * 触发点从来就挂在 ACTION_SCREEN_ON，并没有错挂到解锁广播上；但 startActivity
     * 是从**后台 Service 的 BroadcastReceiver** 里发出的。Android 10（API 29）起的
     * 「后台启动 Activity」限制会把这个请求挂起排队，直到用户解锁（产生交互、
     * Keyguard 解除、系统认为可以放行）才真正 launch；那一刻 isKeyguardLocked
     * 已经是 false，LockScreenActivity.onResume 随即 finish() —— 用户看到的正是
     * 「已经解锁了，你才出现锁屏的一秒」。6.6 注释里寄望的 SYSTEM_ALERT_WINDOW
     * 豁免在 Android 12+/14+ 已被系统收紧，靠不住。
     *
     * 现在走双通道，都指向同一个 singleTop 的 LockScreenActivity：
     *  1) fullScreenIntent 通知：系统专为「锁屏/熄屏时拉起全屏界面」（来电、闹钟）
     *     留的官方口子。设备锁定时由系统**立即**启动目标 Activity，不受后台启动
     *     Activity 限制；设备已解锁且亮屏时系统只显示通知、不会自动拉起，天然
     *     不会造成「解锁后锁屏页盖住桌面」。
     *  2) 直接 startActivity 作兼容兜底（有悬浮窗权限 / 放行 ROM / 旧系统）。
     *
     * 系统侧仍不可控的部分：SCREEN_ON 广播本身的投递时机、ROM 的锁屏显示白名单、
     * Android 14+ 对 USE_FULL_SCREEN_INTENT 权限的默认收窄。
     */
    private fun maybeShowLockScreen() {
        if (!appPrefs.getBoolean(KEY_LOCK_SCREEN_PLAYER, true)) return
        if (lockScreenKickActive) return
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager ?: return
        if (km.isKeyguardLocked != true) return
        val p = mediaSession?.player ?: return
        if (!p.isPlaying) return
        lockScreenKickActive = true
        // 6.6.1 修：canUseFullScreenIntent() 仅 Android 14(API 34)+ 存在，
        // 低版本查询会抛 NoSuchMethodError。34 以下不查询，双通道照常尝试。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.util.Log.d("MusicService", "lockscreen fullScreenIntent permitted=${canUseFullScreenIntent()}")
        }
        // 1) 系统级放行通道
        runCatching { showLockScreenViaFullScreenIntent() }
            .onFailure { android.util.Log.w("MusicService", "lockscreen fullScreenIntent failed: ${it.message}") }
        // 2) 兼容兜底：原来的直接启动，仍跳过转场动画
        runCatching {
            startActivity(
                Intent(this, com.jun.musiccloud.LockScreenActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            )
        }.onFailure {
            android.util.Log.w("MusicService", "lockscreen activity blocked: ${it.message}")
        }
    }

    /**
     * 6.7 修：发一条独立的 fullScreenIntent 通知，让系统在锁屏状态立即拉起锁屏页。
     * 用独立 id / 独立 channel，避开 media3 的媒体通知（默认 id 1001），互不影响；
     * channel 静音无振动，通知本身几乎不可见（锁屏时直接被 Activity 顶掉、解锁即撤）。
     */
    private fun showLockScreenViaFullScreenIntent() {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                LOCK_KICK_CHANNEL_ID, "锁屏歌词", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "亮屏时立即显示锁屏歌词"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(channel)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.jun.musiccloud.LockScreenActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, LOCK_KICK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jun)
            .setContentTitle("锁屏歌词")
            .setContentText(" ")
            .setFullScreenIntent(pi, true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSound(null)
            .setVibrate(null)
            .build()
        nm.notify(LOCK_KICK_NOTIFICATION_ID, notification)
    }

    /**
     * 6.8 修：锁屏复查 —— Android 12+ 是否允许本 App 用 fullScreenIntent 在锁屏拉起 Activity。
     * 默认 false 即「亮屏不弹」的根因：系统把 fullScreenIntent 限制在了来电/闹钟这类
     * 系统级通道，音乐 App 需要用户在设置里手动开启，否则 6.7 的双通道都会静默失效。
     */
    private fun canUseFullScreenIntent(): Boolean {
        // 6.6.1 修：API 34 以下系统里没有这个方法，直接调用会 NoSuchMethodError。
        // 34 以下沿用旧行为（视为放行），锁屏双通道逻辑不变。
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.canUseFullScreenIntent() ?: false
            }.getOrDefault(false)
        } else true
    }

    /**
     * 6.7 修：撤掉全屏拉起通知 + 复位标记。暴露给 LockScreenActivity.onDestroy，
     * 覆盖「用户按返回 / 点收起 / 下滑解锁」这些不走 SCREEN_OFF、USER_PRESENT 之外的路径。
     */
    fun cancelLockScreenKick() {
        lockScreenKickActive = false
        runCatching { NotificationManagerCompat.from(this).cancel(LOCK_KICK_NOTIFICATION_ID) }
    }

    private fun registerLockScreenReceiver() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(lockScreenReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(lockScreenReceiver, f)
        }
    }

    private fun startResumeWatch() {
        if (resumeWatchStarted) return
        if (!appPrefs.getBoolean(KEY_RESUME_ON_RECONNECT, true)) return
        resumeWatchStarted = true
        noisyHandler.postDelayed(resumeTick, RESUME_WATCH_MS)
    }

    /** 外部音频通路是否在线：蓝牙 A2DP / SCO 或有线耳机，任一即可 */
    private fun externalAudioPresent(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            am.isBluetoothA2dpOn || am.isBluetoothScoOn || am.isWiredHeadsetOn
        }.getOrDefault(false)
    }

    /** UI 里切换遥控开关后调用；服务没在播放也可以先把 HTTP 服务起来 */
    fun syncRemoteControl() {
        if (appPrefs.getBoolean("remote_control_enabled", false)) {
            RemoteControlServer.start(this) { mediaSession?.player }
        } else {
            RemoteControlServer.stop()
        }
    }

    /**
     * 听歌时长记账。
     *
     * 放在服务而不是 ViewModel 里：UI 划到后台、Activity 被销毁之后音乐还在放，
     * 那段时间的时长同样要算进报告。每 [TICK_MS] 结算一次而非按曲目结束一次性记账——
     * 跨零点的长曲那样会全记到前一天，进程被杀也会整段丢失。
     */
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickingSongId = -1L
    private var tickAnchorMs = 0L
    private val statsTick = object : Runnable {
        override fun run() {
            settleListenTime()
            tickHandler.postDelayed(this, TICK_MS)
        }
    }

    /**
     * 把"上次结算点到现在"这段计入当前曲目。
     *
     * 用 [SystemClock.elapsedRealtime] 而不是播放器位置差：拖动进度条会让位置跳变，
     * 用位置差算时长会把一次 seek 记成听了十分钟。
     */
    private fun settleListenTime() {
        val player = mediaSession?.player ?: return
        val playing = player.isPlaying
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
        val now = SystemClock.elapsedRealtime()

        if (tickingSongId >= 0 && tickAnchorMs > 0L) {
            val delta = now - tickAnchorMs
            // 上限一个结算周期的两倍：设备睡眠期间 Handler 不跑，醒来后第一次结算
            // 会拿到一段巨大的 delta，那段时间其实没有在放。
            if (delta in 1..(TICK_MS * 2)) ListenStats.add(tickingSongId, delta)
        }

        // 曲目变了或停了：先结清旧的（上面已做），再决定新的锚点
        tickingSongId = if (playing) id else -1L
        tickAnchorMs = if (playing) now else 0L
        // 停下来是个自然的落盘点（用户可能马上就去看报告）。异步写，本方法在主线程
        if (!playing) ListenStats.flushAsync()
    }

    /**
     * 在音频链末端插一个 TeeAudioProcessor，把解码后的 PCM 旁路给频谱分析器。
     * 走 AudioProcessor 而非 audiofx.Visualizer，因此不需要 RECORD_AUDIO 权限。
     * 分析器默认关闭，handleBuffer 会直接返回，对播放无额外开销。
     */
    @OptIn(UnstableApi::class)
    private inner class SpectrumRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            // DSP 链顺序：曲间响度均衡 → 动态范围增强 → 虚拟环绕 → 宽场环绕 → 虚拟低音 → 十段均衡 → 防失真限幅 → 频谱旁路。
            // TrackGain 放最前：测原始信号，校正后电平让后级（含频谱/震动）看到真实输出。
            // RangeEnhancer 先抬响度会让 VirtualBass 的低频检测跟着抬高，
            // 两者对响度的影响会互相叠加；让 VirtualBass 看到原始低频
            // 更接近"补低音"而非"补已被增强的低音"。VirtualSurround 基于
            // 原始 L/R 差分做声场展宽，放在 VirtualBass 前（低音谐波是
            // 单声道注入，若先注入再展宽会把谐波也摊到两侧、破坏居中）。
            // SpatialWide 紧跟 VirtualSurround：多频段展宽 + 早期反射叠在展宽后的
            // 声场上，同样必须在 VirtualBass 之前——低音谐波居中注入才不被摊向两侧。
            // SafeLimiter 放最后一道处理：前级所有增强叠加后可能顶过 0dBFS，
            // 由它统一把峰值压回安全线，杜绝硬削波（撕裂/破音）。
            // 频谱放限幅之后，UI 显示与低音震动看到的都是处理后的实际输出。
            .setAudioProcessors(arrayOf<AudioProcessor>(
                TrackGain,
                RangeEnhancer,
                VirtualSurround,
                SpatialWide,
                VirtualBass,
                // EQ 必须在限幅之前：v4.6.8 及之前用的是系统 audiofx.Equalizer，
                // 挂在 AudioTrack 输出段、排在限幅之后，用户抬频段等于绕过限幅
                // 直接在系统混音层削波。现在 EQ 是链内 biquad，增益溢出先被
                // 自动预增益抵消、残余峰值再由限幅兜底。
                EqProcessor,
                SafeLimiter,
                TeeAudioProcessor(SpectrumAnalyzer)
            ))
            .build()
    }

    /**
     * 拦截播放/暂停，交给 [FadeController] 做渐入渐出。
     * 包一层 ForwardingPlayer 而不是只改 UI 调用点，是为了让通知栏、媒体键、
     * 蓝牙耳机等所有入口都走同一条渐变路径。
     */
    @OptIn(UnstableApi::class)
    private class FadingPlayer(player: Player) : ForwardingPlayer(player) {
        override fun play() = FadeController.requestPlay()
        override fun pause() = FadeController.requestPause()
        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) FadeController.requestPlay() else FadeController.requestPause()
        }
    }

    /**
     * 小组件刷新统一入口：写快照 -> 重绘 -> 异步补封面。
     * 只在主线程调用（Player.Listener 都回到主线程）。
     */
    private fun updateWidgets(player: Player) {
        MusicWidget.updateAll(this, player)
        MusicWidget4x4.updateAll(this, player)
        refreshWidgetArtwork(player)
    }

    /**
     * 封面不能以 http(s) URI 进 RemoteViews，先由服务下载/解码并压到 filesDir，
     * 再让小组件用 setImageViewBitmap 读取。失败就清掉旧封面，避免串图。
     */
    private fun refreshWidgetArtwork(player: Player) {
        val art = player.mediaMetadata.artworkUri?.toString().orEmpty()
        if (art.isBlank()) {
            WidgetState.clearCover(this)
            return
        }
        widgetArtJob?.cancel()
        widgetArtJob = widgetIo.launch {
            val bitmap = runCatching { ArtworkLoader.load(art) }.getOrNull()
            if (bitmap != null) WidgetState.saveCover(this@MusicService, bitmap)
            else WidgetState.clearCover(this@MusicService)
            withContext(Dispatchers.Main) {
                MusicWidget.updateAll(this@MusicService, player)
                MusicWidget4x4.updateAll(this@MusicService, player)
            }
        }
    }

    /** 桌面小组件按钮：服务存活时直接操作播放器，避免 Receiver 里 bindService 崩溃。 */
    fun widgetToggle() {
        val p = mediaSession?.player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun widgetNext() {
        mediaSession?.player?.seekToNextMediaItem()
    }

    fun widgetPrevious() {
        mediaSession?.player?.seekToPreviousMediaItem()
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        // 6.7 修（问题 B）：挂上自定义媒体通知 Provider，让通知栏副标题能显示歌词。
        // media3 1.5.0 的 DefaultMediaNotificationProvider.getNotificationContentText()
        // 只读 MediaMetadata.artist，完全不读 subtitle —— 6.6 把歌词写进 subtitle，
        // 通知栏自然「一点没看到」。setMediaNotificationProvider 必须在 onCreate 返回前
        // 调用（media3 官方要求），放在任何 session 加入之前。
        runCatching { setMediaNotificationProvider(LyricNotificationProvider(this)) }
            .onFailure { android.util.Log.w("MusicService", "lyric notification provider failed: ${it.message}") }
        // 播放服务启动即拉起本地音频代理，保证在线曲目点击即播。
        // 老机型上端口被占、ROM 限制本地 socket 都可能抛：起不来就记日志，
        // 解析结果里的 proxyUrl 为空时链路本来就会退回直连，不该带走整个进程。
        runCatching { AudioProxyServer.start(this) }
            .onFailure { android.util.Log.e("MusicService", "audio proxy start failed: ${it.message}") }

        // 5.1：媒体键/通知栏冷启动时 UI 的 ViewModel 可能还没建过，
        // 这两行保证「音质档位」与「网易云登录态」在服务侧一定就绪（都是幂等读 prefs）
        runCatching { AudioQualityStore.load(appPrefs) }
        runCatching { NeteaseStore.load(this) }

        // 播放器是服务的心脏：老设备（缺解码器、AudioFlinger 拿不到焦点、
        // ROM 拦截音频会话）上 build() 会抛异常，抛出去就是用户说的"崩溃重启"。
        // 现在改成：建不起来就安静地把服务停掉，UI 表现为"暂时放不出声音"，App 还活着。
        val player = runCatching {
            ExoPlayer.Builder(this)
            .setRenderersFactory(SpectrumRenderersFactory(this))
            // 在线流式磁盘缓存。开关关闭时返回的就是 5.0 用的默认数据源，链路一字不差；
            // 构造万一失败也必须退回默认工厂——绝不能因为缓存层把声音搞没
            // 注：ExoPlayer.Builder 没有 setDataSourceFactory，只能通过 MediaSource.Factory 注入数据源
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    runCatching { PlaybackCache.dataSourceFactory(this, appPrefs) }
                        .getOrElse { DefaultDataSource.Factory(this) }
                        // 6.3.2：外面包一层可热替换的壳。播放器永远持有这一个对象，
                        // 改缓存档位时只换里面的 delegate，不用重建播放器（也重建不了）。
                        .let { base ->
                            PlaybackCache.Swappable(base).also {
                                cacheSwappable = it
                                // 6.5.3：登记给 PlaybackCache，清空缓存后由它把新工厂接回来
                                PlaybackCache.attachSwappable(it)
                            }
                        }
                )
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // 拔耳机/断开蓝牙时自动暂停
            .setHandleAudioBecomingNoisy(true)
            // 6.3.2「点歌要立刻出声」：把缓冲门槛从框架默认（2.5s 才起播）压到
            // 0.8s / 1.2s。本地代理已经把音频流接在 127.0.0.1 上，带宽和抖动都可控，
            // 低于这个值反而容易被网络波动打断；再高就是"点了等两秒才响"。
            //
            // 6.7.0：**调高总缓冲，只保留低起播门槛**（用户报"拖到中间会停一下，
            // 有时长有时短"）。原来 minBufferMs=800、max=30s，播放器存不到 1 秒数据就
            // 停止预读 —— 一旦拖动进度条或 CDN 抖一下，缓冲瞬间见底，只能停下来等，
            // 停顿长度取决于那一刻的网速，所以"有时长有时短"。
            // 现在：常规缓冲 15 秒、上限 60 秒（内存代价约 2~4MB，可接受），
            // 但**起播仍然只要 0.8 秒**、卡顿恢复只需 1.5 秒 —— 既快又不会中途停。
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // 6.9.3：内存吃紧的机器（低端 / 老系统）把缓冲腰斩。
                    // 线上有一条 `OutOfMemoryError: Failed to allocate a 56 byte allocation`
                    // 就崩在 ExoPlayer:Playback 线程上——56 字节都分不出来，说明堆已经被
                    // 别的东西占满，60 秒缓冲再压一根稻草就会崩。
                    .setBufferDurationsMs(
                        /* minBufferMs = */ if (com.jun.musiccloud.ui.theme.DevicePerformance.tightMemory) 8_000 else 15_000,
                        /* maxBufferMs = */ if (com.jun.musiccloud.ui.theme.DevicePerformance.tightMemory) 30_000 else 60_000,
                        // 6.7.2：起播门槛 800ms → 350ms；拖动/断流恢复 1.5s → 900ms。
                        // 音频来自 127.0.0.1 本地代理（自己控速），不存在公网抖动，
                        // 350ms 足以避免爆音。用户的诉求是"点了就响"，
                        // 这 450ms 是他能明确感知到的差别。
                        /* bufferForPlaybackMs = */ 350,
                        /* bufferForPlaybackAfterRebufferMs = */ 900
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    // 切歌时立刻丢掉上一首残留的缓冲，别让新曲在前面几帧读到旧数据
                    .build()
            )
            .build()
        }.getOrElse {
            android.util.Log.e("MusicService", "ExoPlayer build failed", it)
            stopSelf()
            return
        }
        // 锁屏播放期间持有唤醒锁（WAKE_LOCK 权限的消费方，否则 Doze 下 CPU 休眠会断音）
        player.setWakeMode(C.WAKE_MODE_LOCAL)

        // ────────────────────────────────────────────────────────────
        // 6.6.7：**无缝衔接**（用户点名："我就是想要无缝衔接，你必须得给我处理"）。
        //
        // 以前的切歌流程：当前曲放完 → ExoPlayer 才开始准备下一首 →
        // 起播要 0.5s 缓冲 +（如果预取没跑成）现场解析地址 → 用户耳朵里
        // 就是"每首歌之间愣一下，甚至断一两秒"。
        //
        // 修法用的是 Media3 官方的**下一曲预加载**（PreloadConfiguration，
        // media3 1.4+ 才有，本项目 1.5.0）：当前曲还在放的时候，播放器就
        // 提前把队列里下一首的开头（这里给 8 秒）准备好——加载、解码器初始化
        // 全部做完，切歌那一刻只是"把音频路由过去"，人耳听不到间隙。
        //
        // 这正是 ExoPlayer 官方 playlist 无缝播放的成熟方案，不需要双播放器
        // 交叉淡化那类自造轮子。预加载走的是同一条 CacheDataSource：
        // 下一首开头会先落磁盘缓存，正式播放时直接命中，流量也不多花。
        //
        // 配合已有的「上一首还在放时就预解析下一首地址」（prefetch + prewarm），
        // 三层一起把切歌间隙压到 0。
        // ────────────────────────────────────────────────────────────
        runCatching {
            player.setPreloadConfiguration(
                ExoPlayer.PreloadConfiguration(/* targetPreloadDurationUs = */ 8_000_000L)
            )
        }.onFailure { android.util.Log.w("MusicService", "preload config failed: ${it.message}") }
        // 6.3.2：留住真身。改缓存档位要重设 MediaSourceFactory，而 FadingPlayer 是
        // ForwardingPlayer，拿不到底层 ExoPlayer；会话里存的又是包装后的那个。
        exoPlayer = player

        // 音量渐变：曲首渐入、曲尾渐出、暂停先渐出。
        // 关闭渐变时仍保留 150ms 防爆音渐入（原来写在这里的 fadeHandler 逻辑已收进控制器）。
        //
        // 整段 DSP 装配包进 runCatching：淡入淡出/无声略过/均衡器这些都靠往播放器上挂
        // 音频处理器实现，个别 ROM 会在 addAudioProcessor 时抛 IllegalStateException。
        // 挂了失败只是"没有渐变、没有虚拟低音"，抛出去就是进程崩溃——不值。
        runCatching {
            FadeController.init(this)
            FadeController.attach(player)

            // 无声略过 / 低音震动：旁路电平读 SpectrumAnalyzer 的 rms/bassLevel，
            // 两者的轮询都在服务进程内自转，与 UI 生命周期无关
            SilenceSkipper.init(this)
            SilenceSkipper.attach(player)
            BassHaptics.init(this)
            // V2.2 DSP 处理器：恢复上次的开关/强度（object 挂在音频链上，随时可改）
            VirtualBass.init(getSharedPreferences("jun_prefs", Context.MODE_PRIVATE))
            RangeEnhancer.init(getSharedPreferences("jun_prefs", Context.MODE_PRIVATE))
            VirtualSurround.init(getSharedPreferences("jun_prefs", Context.MODE_PRIVATE))
            SpatialWide.init(getSharedPreferences("jun_prefs", Context.MODE_PRIVATE))
            SafeLimiter.init(getSharedPreferences("jun_prefs", Context.MODE_PRIVATE))
            TrackGain.init(getSharedPreferences("jun_prefs", Context.MODE_PRIVATE))
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // 自动切歌不经过 play()，需要在这里重置音量闸门，否则新曲目会继承上一首的渐出值。
                    // soft：不直接跳满音量——首帧从 0 快速渐入，消除切歌爆音
                    FadeController.resetVolume(soft = true)
                    // 响度均衡：切歌后把当前曲路径交给链首处理器（查库/开始测量）。
                    // path 必须与 Song.filePath 同一口径（extras 里的 file_path），
                    // mediaId 是 stableId hash、content uri 的 path 都对不上 store key。
                    val path = mediaItem?.mediaMetadata?.extras?.getString("file_path")
                        ?: mediaItem?.localConfiguration?.uri?.takeIf { it.scheme == "file" }?.path
                    TrackGain.setCurrentSong(path)
                }
            })
        }.onFailure { android.util.Log.e("MusicService", "DSP chain init failed: ${it.message}", it) }

        // 均衡器挂到播放器的音频会话上，并恢复上次的配置
        // 兜底：均衡器初始化失败不应导致整个播放服务崩溃
        runCatching {
            EqualizerController.init(this)
            EqualizerController.attach(player.audioSessionId)
        }.onFailure { android.util.Log.w("MusicService", "Equalizer init failed: ${it.message}") }

        // 听歌时长统计：文件在服务进程里初始化（UI 进程那边也 init 一次，两边同路径）
        runCatching { ListenStats.init(this) }
            .onFailure { android.util.Log.w("MusicService", "ListenStats init failed: ${it.message}") }

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                // 复用已有实例回到前台，而不是新起一个 Activity
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Android Auto / 车载：MediaLibrarySession 是 MediaSession 的超集，
        // 既保留手机端的媒体控制，又额外提供"浏览树"，车机才能列出曲库选歌。
        val libraryCallback = object : MediaLibrarySession.Callback {
            private fun rootItem() = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Jun Music")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                ).build()

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                controller: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                return Futures.immediateFuture(
                    LibraryResult.ofItem(rootItem(), params)
                )
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                controller: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                if (parentId != ROOT_ID) {
                    return Futures.immediateFuture(
                        LibraryResult.ofItemList(ImmutableList.of(), params)
                    )
                }
                val songs = com.jun.musiccloud.data.MusicRepository.currentLibrary().take(800)
                val items = songs.map { toMediaItem(it) }
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                )
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                controller: MediaSession.ControllerInfo,
                mediaId: String
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val song = com.jun.musiccloud.data.MusicRepository.currentLibrary()
                    .firstOrNull { it.id.toString() == mediaId }
                    ?: return Futures.immediateFuture(
                        LibraryResult.ofItem(rootItem(), null)
                    )
                return Futures.immediateFuture(
                    LibraryResult.ofItem(toMediaItem(song), null)
                )
            }
        }

        // 会话建立同样要兜住：部分定制 ROM 上通知渠道/媒体会话注册会抛，
        // 直接 build() 就是"打开 App 就重启"。失败时播放器仍然可用，
        // 只是通知栏与车机控制缺失——比整机崩掉好得多。
        mediaSession = runCatching {
            MediaLibrarySession.Builder(this, FadingPlayer(player), libraryCallback)
                .setSessionActivity(sessionActivityPendingIntent)
                // 通知栏/锁屏/车机封面：内嵌封面无法按 URI 被系统解出，需主动提供 Bitmap
                .setBitmapLoader(ArtworkBitmapLoader.get(this))
                .build()
        }.getOrElse {
            android.util.Log.e("MusicService", "session build failed: ${it.message}", it)
            runCatching {
                // 退回最小会话：去掉封面加载器（多数字源问题出在这里）
                MediaLibrarySession.Builder(this, FadingPlayer(player), libraryCallback)
                    .setSessionActivity(sessionActivityPendingIntent)
                    .build()
            }.getOrNull()
        }

        // 6.3.2：车机 / 蓝牙耳机显示歌词；6.6 起兼作「通知栏歌词」。
        // AVRCP 本身没有歌词通道，车机屏幕上的字全来自 MediaMetadata，
        // 所以把「当前这一句」随播放进度写进 metadata（title 永远是歌名，不抢）。
        // 6.7 修：歌词写进 subtitle，由 onCreate 挂的 LyricNotificationProvider 读成
        // 通知栏副标题；开关关闭时 BluetoothLyricBridge 自行把 subtitle 清回歌手名。
        runCatching {
            mediaSession?.let { BluetoothLyricBridge.attach(this, it, player) }
        }.onFailure { android.util.Log.w("MusicService", "bluetooth lyric bridge failed: ${it.message}") }

        // 睡眠定时器：计时权威在本进程，UI 销毁不影响；到期只暂停播放器
        runCatching {
            SleepTimer.attach(this)
            // 5.1：到期不硬切，走 FadeController 渐弱（关闭渐变时它自己会直接暂停）
            sleepUnsub = SleepTimer.addExpireObserver { runCatching { FadeController.requestPause() } }
        }.onFailure { android.util.Log.w("MusicService", "SleepTimer init failed: ${it.message}") }
        // 桌面悬浮歌词：常驻服务里初始化，UI 退到后台也能跟随
        runCatching { FloatingLyric.init(this, player) }
            .onFailure { android.util.Log.w("MusicService", "FloatingLyric init failed: ${it.message}") }
        // 封面加载器的磁盘缓存在服务侧也要就绪：媒体键起播时 UI 可能从未打开过
        runCatching { com.jun.musiccloud.ui.ArtworkLoader.init(this) }
            .onFailure { android.util.Log.w("MusicService", "ArtworkLoader init failed: ${it.message}") }

        // 播放/暂停与切歌都要立刻结算，否则一段最多 TICK_MS 的零头会记到下一首头上
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                settleListenTime()
                updateWidgets(player)
                if (isPlaying) {
                    applyPlaybackSpeed(player)
                    // 用户/系统已经把声音放出来了，就不必再插手回连
                    pausedByDeviceUnplug = false
                    pausedByFocusLoss = false
                    // 真正播起来了：上一段断流的重连计数清零（自愈成功）
                    resetReconnect()
                } else if (player.playbackSuppressionReason ==
                    Player.PLAYBACK_SUPPRESSION_REASON_NONE
                ) {
                    // 没有任何抑制原因却停了 = 用户自己按的暂停，别在焦点恢复时自作主张续播
                    pausedByFocusLoss = false
                }
            }

            /**
             * 6.6「播放会被打断」：短暂焦点丢失后，部分 ROM 的 media3 只暂停、不自动恢复
             * （微信语音、导航播报、通知提示音最常见）。记下"丢焦点前确实在播"，
             * 抑制解除后自己补一次 play。用户主动暂停会走上面的分支把标记清掉，不会误续播。
             */
            override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                when (reason) {
                    Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS ->
                        pausedByFocusLoss = true
                    Player.PLAYBACK_SUPPRESSION_REASON_NONE -> {
                        if (!pausedByFocusLoss) return
                        pausedByFocusLoss = false
                        if (!player.isPlaying &&
                            player.playbackState != Player.STATE_ENDED &&
                            player.currentMediaItem != null
                        ) {
                            runCatching { FadeController.requestPlay() }
                        }
                    }
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                settleListenTime()
                updateWidgets(player)
                // 切歌意味着上一段的断流重试已无关，复位计数
                resetReconnect()
                // 倍速跟随切歌重新下发一次，避免「设了 1.5x 但下一首回到 1x」
                applyPlaybackSpeed(player)
                // 下一首预解析：把起播那一下的解析 RTT 提前到上一首还在放时做掉
                prefetchUpcoming(player)
                // 顺带（10 分钟一次）看看在线缓存是否已经写满
                maybeCheckStreamCacheFull()
                // 睡眠定时器"播完这首停"：当前曲自然结束、自动切到下一首的瞬间暂停
                if (SleepTimer.stopAfterSong && mediaItem != null) {
                    SleepTimer.cancelStopAfterSong()
                    FadeController.requestPause()
                }
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updateWidgets(player)
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                updateWidgets(player)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateWidgets(player)
                // 最后一首自然播完（不会触发切歌）也清掉续播标记，避免状态卡住
                if (playbackState == Player.STATE_ENDED && SleepTimer.stopAfterSong) {
                    SleepTimer.cancelStopAfterSong()
                }
                // 后台断流自愈：进入缓冲且本意是播放 → 启动卡顿看门狗；
                // 缓冲解除（就绪/暂停/结束）→ 停掉看门狗，就绪时复位重连计数（自愈成功）。
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (player.playWhenReady) {
                            reconnectHandler.removeCallbacks(bufferingWatchdog)
                            reconnectHandler.postDelayed(bufferingWatchdog, BUFFER_STALL_MS)
                        }
                    }
                    Player.STATE_READY -> resetReconnect()
                    else -> reconnectHandler.removeCallbacks(bufferingWatchdog)
                }
            }

            /**
             * 后台断流自愈：播放器报错（网络抖断/连接被掐/上游 5xx）时，
             * 在常驻服务进程里直接安排重连，不依赖已被系统节流的 UI 进程。
             */
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.w("MusicService", "onPlayerError, scheduling reconnect: ${error.errorCode}")
                scheduleReconnect()
            }
        })
        tickHandler.postDelayed(statsTick, TICK_MS)
        tickHandler.postDelayed(mixTick, 500L)
        // 拔出监听：ExoPlayer 的 setHandleAudioBecomingNoisy 负责暂停，
        // 这里只记「是不是因为它停的」，回连时才知道该不该自己接上
        runCatching { registerNoisyReceiver() }
            .onFailure { android.util.Log.w("MusicService", "noisy receiver failed: ${it.message}") }
        // 6.9.7：亮屏时按开关拉起全屏锁屏音乐界面
        runCatching { registerLockScreenReceiver() }
            .onFailure { android.util.Log.w("MusicService", "lockscreen receiver failed: ${it.message}") }
        // 遥控开关上次是开着的：服务重建后自动恢复监听
        runCatching { syncRemoteControl() }
        // 6.6.2：与其他应用同时播放 —— 冷启动时读持久化开关并应用，
        // 否则服务重建后会回到默认（抢焦点）行为，开关形同虚设。
        runCatching {
            applyPlayWithOthers(appPrefs.getBoolean(KEY_PLAY_WITH_OTHERS, false))
            val pwoFilter = IntentFilter(ACTION_PLAY_WITH_OTHERS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(playWithOthersReceiver, pwoFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(playWithOthersReceiver, pwoFilter)
            }
        }.onFailure { android.util.Log.w("MusicService", "playWithOthers init failed: ${it.message}") }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从「最近任务」划掉本应用：立即停止播放并结束服务，
        // 修复「退出 App 后音乐还在后台播放」的严重问题。
        // 切后台 / 锁屏不会触发此回调，因此正常背景播放不受影响。
        runCatching {
            mediaSession?.player?.stop()
            mediaSession?.player?.clearMediaItems()
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 反注册到期观察者，避免旧 player 引用留在进程级单例里
        sleepUnsub?.let { it() }; sleepUnsub = null
        // 服务结束前收起悬浮歌词窗
        FloatingLyric.release()
        // 服务结束前把最后一段听歌时长结清并落盘
        tickHandler.removeCallbacks(statsTick)
        tickHandler.removeCallbacks(mixTick)
        noisyHandler.removeCallbacks(resumeTick)
        runCatching { unregisterReceiver(noisierReceiver) }
        runCatching { unregisterReceiver(lockScreenReceiver) }
        runCatching { unregisterReceiver(playWithOthersReceiver) }
        reconnectHandler.removeCallbacksAndMessages(null)
        // 6.7 修：服务销毁时撤掉锁屏全屏拉起通知、停掉通知栏歌词轮询，
        // 避免通知残留与 Handler 持有已释放 player 空转。
        cancelLockScreenKick()
        runCatching { BluetoothLyricBridge.detach() }
        PlaybackCache.release()
        FadeController.resetAutomixGain()
        RemoteControlServer.stop()
        widgetArtJob?.cancel()
        widgetIo.cancel()
        WidgetState.markStopped(this)
        MusicWidget.updateAll(this)
        MusicWidget4x4.updateAll(this)
        instance = null
        settleListenTime()
        ListenStats.flush()
        EqualizerController.release()
        FadeController.release()
        SilenceSkipper.setEnabled(false)
        // R13 老机适配：shutdown 比 setEnabled(false) 多做两步——cancel 掉排队中的脉冲、
        // 拆掉屏幕广播。EMUI 上"服务已经没了、振动心跳还在敲"正是熄屏后仍被唤醒的那一类残留。
        BassHaptics.shutdown()
        // 睡眠定时器的待触发闹钟随服务一起收：进程没了还留着闹钟，EMUI 会把它算成后台唤醒源
        com.jun.musiccloud.playback.SleepTimer.releaseAlarms()
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /**
     * 6.9.7：锁屏界面的播放器入口。
     *
     * MediaSessionService 的 `player` 不对外暴露（protected），同进程的 Activity
     * 只能通过这个方法拿到当前会话播放器，用来读进度、播放/暂停、切歌。
     */
    fun lockScreenPlayer(): androidx.media3.common.Player? = mediaSession?.player

    companion object {
        /** 当前存活的播放服务实例：UI 切换遥控开关时直接通知它，避免跨进程/广播开销 */
        @Volatile
        var instance: MusicService? = null
            private set

        /** 浏览树根节点 id（Android Auto / 系统媒体浏览器） */
        private const val ROOT_ID = "__root__"
        /** 听歌时长结算周期。10 秒：足够细（丢失上限 10 秒）又不至于频繁唤醒 */
        private const val TICK_MS = 10_000L
        /** 倍速存储：整型基点（100 = 1.0x），prefs 里不出现浮点比较 */
        const val KEY_PLAYBACK_SPEED_BP = "playback_speed_bp"
        /** 拔出后回连自动续播开关（默认开） */
        const val KEY_RESUME_ON_RECONNECT = "resume_on_reconnect"
        /** 6.9.7：锁屏全屏音乐界面开关（默认开） */
        const val KEY_LOCK_SCREEN_PLAYER = "lock_screen_player"
        /** 6.6.2：与其他应用同时播放开关（持久化 key，与 ViewModel 共用） */
        const val KEY_PLAY_WITH_OTHERS = "play_with_others"
        /** 6.7 修：锁屏 fullScreenIntent 通知的独立 id / channel，避开 media3 默认的 1001 */
        private const val LOCK_KICK_NOTIFICATION_ID = 2027
        private const val LOCK_KICK_CHANNEL_ID = "lockscreen_kick"
        /** 回连轮询间隔 */
        private const val RESUME_WATCH_MS = 5_000L
        /** 在线缓存占用统计的最小间隔：10 分钟 */
        private const val CACHE_CHECK_MS = 10 * 60 * 1000L
    }
}

/**
 * 6.7 修（问题 B）：通知栏歌词的关键一环。
 *
 * media3 1.5.0 的 `DefaultMediaNotificationProvider.getNotificationContentText(MediaMetadata)`
 * 源码里是 `return metadata.artist;` —— 媒体通知的副标题**只读 artist，从不读 subtitle**。
 * 6.6 把当前歌词写进了 `MediaMetadata.subtitle`（MusicService 里也确实没有任何
 * subtitle 写入/刷新逻辑），于是「通知栏显示歌词」开关默认开着却一点效果都没有。
 *
 * 这里覆盖 contentText：有歌词（subtitle 非空）就显示歌词，否则回落歌手名。
 * 只改通知读法、**不动 artist 字段本身**，所以 NowPlayingBus / 主界面 / 锁屏页 /
 * 小组件读到的仍是真歌手，不会被歌词污染；开关关闭时 BluetoothLyricBridge 会把
 * subtitle 清掉，通知栏自动回到歌手名。
 */
@OptIn(UnstableApi::class)
private class LyricNotificationProvider(context: Context) :
    DefaultMediaNotificationProvider(context) {
    override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? {
        val lyric = metadata.subtitle
        return if (lyric.isNullOrBlank()) metadata.artist else lyric
    }
}
