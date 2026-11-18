package com.jun.musiccloud.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.jun.musiccloud.data.LyricLine
import com.jun.musiccloud.data.LyricParser
import java.util.concurrent.atomic.AtomicBoolean
// 6.7 修：给设置页 UI 用的开关状态流
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 车机 / 蓝牙耳机显示歌词（6.3.2 新增）。
 *
 * 为什么"车机只显示歌名不显示歌词"
 * --------------------------------
 * AVRCP（蓝牙音频遥控协议）本身**没有歌词通道**，车机屏幕上的文字全部来自
 * `MediaMetadata`。我们之前只往里塞了 title / artist / albumTitle，车机自然只有歌名。
 *
 * 车机想显示滚动歌词，只有两条真实可行的路：
 *  1. **AVRCP 1.6 的「Now Playing List + 当前曲目元数据」**：部分车机（尤其带
 *     网易云/QQ音乐专版适配的）会读 `MediaMetadata.extras` 里的歌词字段；
 *  2. **把当前这一句歌词当作第三方字段发出**——也就是下面做的事：
 *     随播放进度把「当前行」写进 metadata 的 subtitle/extras，并主动 notify。
 *     车机只要显示副标题或"艺术家/专辑"位置，就能看到这句歌词在滚动。
 *
 * 为了不打扰原有界面：
 *  - `title` 永远保持歌名，绝不改成歌词（否则通知栏/锁屏也会跟着变）；
 *  - 6.6 起由「通知栏歌词」开关控制。6.7 修：语义收为**总开关**——开关开才推，
 *    关掉当场把已写入的歌词清回去；只有换句才改 metadata，不会每 0.9s 抖一次；
 *  - 歌词来自 [LyricParser] 的同一份缓存，不额外发网络请求。
 *
 * 6.7 修：字幕写在 `MediaMetadata.subtitle`，由 MusicService 挂的
 * LyricNotificationProvider 读成通知栏副标题（media3 默认只读 artist，不读 subtitle）。
 *
 * 兼容性：不申请任何新权限，只用 AudioManager 查询 + AudioDeviceCallback。
 */
object BluetoothLyricBridge {

    private const val TAG = "BtnLyric"

    /** 车机/耳机都在读的第三方歌词字段名（各家写法不同，一起塞，谁认哪个用哪个） */
    private const val KEY_LYRIC = "lyric"
    private const val KEY_LYRIC_LINE = "lyric_line"
    private const val KEY_LRC = "lrc"

    private const val TICK_MS = 900L

    /** 6.6：通知栏歌词开关（总设置里可关）。默认开：亮屏下拉就能看到当前这一句。
     *  6.7 修：语义明确为「通知栏 + 车机歌词」的总开关 —— 关掉就完全不写歌词，
     *  且当场把已写入的歌词清回去，不再出现「插着蓝牙时关不掉 / 关了还停在最后一句」。 */
    const val PREF_KEY = "notification_lyric"
    private const val PREFS = "jun_prefs"

    @Volatile private var notificationLyric = true

    /** 6.7 修：给设置页 UI 观察开关状态用（Compose 可 collectAsState）。 */
    private val enabledState = MutableStateFlow(true)
    val notificationLyricEnabled: StateFlow<Boolean> get() = enabledState

    fun isNotificationLyricEnabled(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, true)
    }.getOrDefault(true)

    fun setNotificationLyricEnabled(context: Context, enabled: Boolean) {
        notificationLyric = enabled
        enabledState.value = enabled
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEY, enabled).apply()
        }
        // 6.7 修：点开关要**当场**看到变化，不等下一个 900ms tick。
        // 打开：复位行号并立刻推一句；关闭：立刻把已写进 metadata 的歌词清回歌手名。
        if (enabled) {
            lastLineIndex = -2
            runCatching { push() }
        } else {
            runCatching { restoreMetadata() }
            lastLineIndex = -2
            lastMediaId = ""
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var player: Player? = null
    private var audioManager: AudioManager? = null

    /** 外接设备是否在位（蓝牙 / 有线 / USB）。
     *  6.7 修：不再是推送门槛，只用于插拔时强制重推一次；总闸是 [notificationLyric]。 */
    @Volatile private var externalOutput = false

    /** 已经推送过的行号，避免同一句反复 notify（每次 notify 都是一次 AVRCP 往返） */
    private var lastLineIndex = -2
    private var lastMediaId: String = ""

    private val running = AtomicBoolean(false)

    fun attach(context: Context, session: MediaSession, player: Player) {
        this.session = session
        this.player = player
        notificationLyric = isNotificationLyricEnabled(context)
        enabledState.value = notificationLyric
        lastLineIndex = -2
        lastMediaId = ""
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am
        externalOutput = probeExternal(am)
        // 插拔蓝牙/车机时立刻重算并重推一次。
        // 6.7 修：externalOutput 不再作为推送门槛，只用来在设备变化时强制刷新；开关才是总闸。
        runCatching {
            am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    externalOutput = probeExternal(am)
                    lastLineIndex = -2
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    externalOutput = probeExternal(am)
                }
            }, handler)
        }
        // 6.7 修：重复 attach 时先撤掉旧 tick 再挂，避免两条轮询链并存。
        running.set(true)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    private val tick = object : Runnable {
        override fun run() {
            runCatching { push() }
            if (running.get()) handler.postDelayed(this, TICK_MS)
        }
    }

    /** 外部音频通路是否在位：蓝牙 A2DP/SCO、有线耳机、USB/车机，任一即可 */
    private fun probeExternal(am: AudioManager): Boolean = runCatching {
        @Suppress("DEPRECATION")
        if (am.isBluetoothA2dpOn || am.isBluetoothScoOn || am.isWiredHeadsetOn) return true
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        devices.any {
            when (it.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> it.isSink
                // 车载音响：API 23+ 才有这个类型，老系统拿不到就走上面的蓝牙判断
                AudioDeviceInfo.TYPE_UNKNOWN -> true
                else -> false
            }
        }
    }.getOrDefault(false)

    private fun push() {
        if (session == null) return
        val p = player ?: return
        // 6.7 修：开关是本功能的总闸。关掉就彻底不写歌词（车机也一样），
        // 并且当场把已写进 metadata 的歌词清回原始状态。旧版 `!externalOutput && !开关`
        // 的写法在插着蓝牙/耳机时 externalOutput=true，关了开关仍继续推，且最后一句
        // 歌词永远停在通知栏/车机上，看起来就是「关了没用」。
        // 只有换句才 notify（下面 idx==lastLineIndex 的判断），不会每 0.9s 刷一次通知。
        if (!notificationLyric) {
            if (lastLineIndex != -2) {
                lastLineIndex = -2
                restoreMetadata()
            }
            return
        }
        val item = p.currentMediaItem ?: return
        val md = item.mediaMetadata
        // 歌词键：优先 lyricKey（"ol:<id>" 在线 / 本地文件路径），没有就跳过
        val lyricKey = md.extras?.getString("lyric_key").orEmpty()
            .ifBlank { md.extras?.getString("file_path").orEmpty() }
        if (lyricKey.isBlank()) return
        val mediaId = item.mediaId

        val lines: List<LyricLine> = runCatching {
            LyricParser.loadLyrics(lyricKey, md.extras?.getLong("duration_ms") ?: 0L)
        }.getOrDefault(emptyList())
        if (lines.isEmpty()) return

        val pos = p.currentPosition.coerceAtLeast(0L)
        val idx = indexOf(lines, pos)
        if (idx == lastLineIndex && mediaId == lastMediaId) return
        lastLineIndex = idx
        lastMediaId = mediaId

        val text = lines.getOrNull(idx)?.text.orEmpty().ifBlank { lines.firstOrNull()?.text.orEmpty() }
        if (text.isBlank()) return
        // 下一句也带上：部分车机习惯显示"当前/下一句"两行
        val next = lines.getOrNull(idx + 1)?.text.orEmpty()

        val newMd = md.buildUpon()
            // subtitle 是 AVRCP 里车机**一定读**的字段之一（很多车机放在歌名下面那行）
            .setSubtitle(text)
            .setExtras(
                android.os.Bundle().apply {
                    // 原 extras 要原样带回去，否则 lyric_key/file_path 会丢
                    md.extras?.let { putAll(it) }
                    putString(KEY_LYRIC, text)
                    putString(KEY_LYRIC_LINE, text)
                    // 标准 LRC 行也行：认这个的车机会按时间轴自己滚
                    putString(KEY_LRC, "[${stamp(pos)}]$text")
                    if (next.isNotBlank()) putString("lyric_next", next)
                    // 有的车机读「艺术家」位置显示滚动文本，用第二个字段名兜一层
                    putString("android.media.metadata.ARTIST", md.artist?.toString().orEmpty())
                }
            )
            .build()

        val fresh = item.buildUpon().setMediaMetadata(newMd).build()
        val index = p.currentMediaItemIndex
        if (index < 0) return
        // 只替换「当前这一条」的元数据，不动时间线、不动播放位置——
        // replaceMediaItem 在 Media3 里不会重建 MediaSource，因此不会打断播放。
        // 6.7 修：只 replace 一次。原来对 p（ExoPlayer）和 s.player（FadingPlayer
        // 包装的同一个 player）各 replace 一遍，第二遍纯属重复，还会让 timeline 抖一下。
        // 底层 player 的 onMediaMetadataChanged 会经 ForwardingPlayer 转发给 MediaSession，
        // 通知栏（LyricNotificationProvider）与小组件照常刷新。
        runCatching { p.replaceMediaItem(index, fresh) }
    }

    /**
     * 6.7 修：开关关闭时把当前曲目 MediaItem 里我们塞进去的歌词字段清掉。
     * subtitle 清空后，MusicService 的 LyricNotificationProvider 会回落到
     * MediaMetadata.artist，通知栏重新显示歌手名；车机读的 extras 歌词也一并移除。
     */
    private fun restoreMetadata() {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val md = item.mediaMetadata
        val extras = md.extras
        if (md.subtitle.isNullOrBlank() && extras?.getString(KEY_LYRIC).isNullOrBlank()) return
        val cleaned = md.buildUpon()
            .setSubtitle(null)
            .setExtras(
                android.os.Bundle().apply {
                    extras?.let { putAll(it) }
                    remove(KEY_LYRIC)
                    remove(KEY_LYRIC_LINE)
                    remove(KEY_LRC)
                    remove("lyric_next")
                    remove("android.media.metadata.ARTIST")
                }
            )
            .build()
        val index = p.currentMediaItemIndex
        if (index < 0) return
        runCatching { p.replaceMediaItem(index, item.buildUpon().setMediaMetadata(cleaned).build()) }
    }

    /** 找当前时间对应的行（二分；lines 已按时间升序） */
    private fun indexOf(lines: List<LyricLine>, posMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var hit = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= posMs) {
                hit = mid; lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return hit
    }

    private fun stamp(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        val cs = (ms % 1000) / 10
        return "%02d:%02d.%02d".format(m, s, cs)
    }

    /** 6.7 修：MusicService.onDestroy 现在会调用本方法，收掉轮询与引用，避免 Handler 空转。 */
    fun detach() {
        running.set(false)
        handler.removeCallbacks(tick)
        session = null
        player = null
        lastLineIndex = -2
        lastMediaId = ""
    }
}
