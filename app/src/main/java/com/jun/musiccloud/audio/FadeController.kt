package com.jun.musiccloud.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * 音量渐变：曲目开头渐入、结尾渐出、手动暂停先渐出再真正暂停。
 *
 * 音量按「当前播放位置」实时算出来，而不是自己维护一条动画时间轴——
 * seek、切歌、单曲循环、跳到中段播放都会自然落在正确的音量上，
 * 不需要额外的状态同步，也不会出现动画和播放位置打架的情况。
 *
 * 关闭渐变时仍保留曲首 150ms 的快速渐入，用于消除解码首帧的爆音
 * （原先写在 MusicService 里的那段 anti-pop 逻辑，统一收到这里）。
 */
object FadeController {

    const val PREFS = "jun_prefs"
    const val KEY_ENABLED = "fade_enabled"
    const val KEY_FADE_MS = "fade_ms"

    const val DEFAULT_FADE_MS = 2000L
    const val MIN_FADE_MS = 500L
    const val MAX_FADE_MS = 5000L

    /** 渐入渐出总开关（设置项驱动） */
    @Volatile
    var enabled: Boolean = false

    /** 单次渐变时长（毫秒） */
    @Volatile
    var fadeMs: Long = DEFAULT_FADE_MS

    /** 40ms ≈ 25Hz，足够平滑，开销可忽略 */
    private const val TICK_MS = 40L

    /**
     * Automix 的实时淡出系数：由 MusicService 按「距曲尾剩余时间」写入，
     * 叠加在正常音量曲线上。两处都在写 player.volume 会互相覆盖，所以统一在这里相乘。
     */
    @Volatile private var automixGain: Float = 1f

    fun setAutomixGain(gain: Float) {
        automixGain = gain.coerceIn(0f, 1f)
    }

    fun resetAutomixGain() {
        automixGain = 1f
    }

    /**
     * 用户音量（局域网遥控滑杆）。
     *
     * 音量最终的写入口只有这里的 ticker，遥控端直接改 player.volume 会在 40ms 内被覆盖；
     * 所以把「用户意图」也做成一个系数，与渐入渐出、Automix 相乘。
     */
    @Volatile private var userGain: Float = 1f

    fun setUserGain(gain: Float) {
        userGain = gain.coerceIn(0f, 1f)
    }

    fun userGain(): Float = userGain

    /** 空闲（没在播放、也没有渐变在进行）时降频轮询 */
    private const val IDLE_TICK_MS = 250L

    /** 关闭渐变时保留的防爆音渐入时长 */
    private const val ANTI_POP_MS = 150f

    /**
     * 手动暂停/恢复的渐变时长。故意比曲目渐变短很多：按下暂停要等 2 秒才真的停会
     * 显得按键没反应，350ms 被反馈成"反应慢"，压到 150ms——几乎无感，仍保留柔和 + 防爆音。
     */
    private const val GATE_MS = 150f

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    /** 手动暂停/恢复的软开关：0=静音，1=全量。与播放位置无关，可与位置渐变叠加 */
    private var gate = 1f
    private var gateRising = true

    /** 渐出到 0 之后才真正调用 pause() */
    private var pausePending = false

    /** 服务可能在 UI 之前创建（通知栏/媒体键唤起），所以配置从 prefs 直接读一份 */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        fadeMs = prefs.getLong(KEY_FADE_MS, DEFAULT_FADE_MS).coerceIn(MIN_FADE_MS, MAX_FADE_MS)
    }

    /** 上一帧是否真正在播放：用于捕捉「出声瞬间」——起播请求与首帧出声之间存在异步延迟，
     *  爆音正是发生在首帧，anti-pop 渐入点必须以出声为准，而不是以 play() 调用为准。 */
    @Volatile
    private var wasPlaying = false

    /** attach/播放恢复的时刻（elapsedRealtime）。此后 ANTI_POP_MS 内强制从 0 渐入，
     *  消除进程重建后恢复播放时新 AudioTrack 首帧非零起点导致的爆音 */
    private var playStartStamp = 0L

    fun attach(p: ExoPlayer) {
        player = p
        gate = 1f
        gateRising = true
        pausePending = false
        playStartStamp = android.os.SystemClock.elapsedRealtime()
        // 不主动压音量到 0：避免 ticker 未及时执行导致恢复播放时全程无声。
        // 防爆音交由 enabled=true 时的渐入曲线处理；关闭渐变时直接全量出声。
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun release() {
        handler.removeCallbacks(ticker)
        player = null
    }

    /** 带渐入的播放；从暂停恢复时从静音起升 */
    fun requestPlay() {
        val p = player ?: return
        pausePending = false
        gateRising = true
        // 起播时刻打点：无论从哪个位置起播（含进程重建后 resume），都做防爆音快速渐入
        playStartStamp = android.os.SystemClock.elapsedRealtime()
        if (enabled) {
            // 已经在播放时不要把 gate 打回 0，否则会听到一次音量凹陷
            if (!p.isPlaying) {
                gate = 0f
                p.volume = 0f
            }
        } else {
            // 渐变关闭：不压音量到 0，直接全量播放。
            // 之前为了防爆音设 volume=0 依赖 ticker 在 150ms 内升起，
            // 一旦 ticker 未及时执行就会全程无声——比起偶发爆音，无声更致命。
            gate = 1f
        }
        p.play()
        kick()
    }

    /**
     * 暂停：直接调用 player.pause()，保证暂停一定生效。
     * 之前的"渐出后再暂停"依赖 ticker 逐帧把音量降到 0 才调用 pause，
     * 一旦 ticker 未及时执行（主线程卡顿 / 服务重建竞态），音乐就停不下来。
     * 现在改为立即暂停，音量渐出的柔化效果由下一次起播时的渐入自然覆盖。
     */
    fun requestPause() {
        val p = player ?: return
        pausePending = false
        gateRising = true
        gate = 1f
        p.pause()
    }

    fun resetVolume(soft: Boolean = false) {
        gate = 1f
        gateRising = true
        pausePending = false
        // 不压音量到 0：避免 ticker 未及时执行导致切歌后全程无声。
        // soft 模式仅复位闸门状态，音量交由 targetVolume 曲线自然过渡。
        if (soft) {
            playStartStamp = android.os.SystemClock.elapsedRealtime()
        } else {
            player?.volume = 1f
        }
    }

    private fun kick() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            var busy = false

            // 捕捉「真正出声」这一帧：play()/resume 是异步的，调用到首帧出采样之间有解码延迟。
            // 起播时 volume 已被压到 0，但若出声发生在下一拍 tick 之前，anti-pop 计时（从
            // play() 时刻算）可能已跑到 1，导致 tick 再把音量从静音直接跳满——这个非零起点的
            // 突跳就是概率性爆音。以「isPlaying 变 true」为准重置渐入点，音量便从首帧起 0→满
            // 平滑渐入，突跳消失。
            val nowPlaying = p.isPlaying
            if (nowPlaying && !wasPlaying) {
                playStartStamp = android.os.SystemClock.elapsedRealtime()
            }
            wasPlaying = nowPlaying

            if (!enabled) {
                // 关闭状态下 gate 不参与计算，但要复位，避免开关切换时残留静音
                gate = 1f
                gateRising = true
            } else {
                val step = TICK_MS / GATE_MS
                if (gateRising && gate < 1f) {
                    gate = (gate + step).coerceAtMost(1f)
                    busy = true
                } else if (!gateRising && gate > 0f) {
                    gate = (gate - step).coerceAtLeast(0f)
                    busy = true
                }
            }

            val target = targetVolume(p)
            if (abs(p.volume - target) > 0.004f) p.volume = target
            if (p.isPlaying) busy = true

            handler.postDelayed(this, if (busy) TICK_MS else IDLE_TICK_MS)
        }
    }

    private fun targetVolume(p: ExoPlayer): Float {
        // 渐变关闭时直接全量，不做任何音量压制——确保一定能听到声音。
        // Automix 与遥控音量是独立于「淡入淡出」开关的，仍要乘进去。
        if (!enabled) return automixGain * userGain

        // 起播准备期锁 0（防爆音）：play() 到首帧出声之间是异步准备期，isPlaying 仍为 false。
        if (p.playWhenReady && !p.isPlaying) return 0f
        val pos = p.currentPosition.coerceAtLeast(0L).toFloat()
        // 防爆音渐入：从起播时刻算（而非播放位置）
        val antiPop = ((android.os.SystemClock.elapsedRealtime() - playStartStamp) / ANTI_POP_MS)
            .coerceIn(0f, 1f)

        val dur = p.duration
        // 短曲目上 2s 渐入 + 2s 渐出会让音量永远到不了满值，按时长上限收窄
        val f = if (dur > 0) fadeMs.coerceAtMost(dur / 3).coerceAtLeast(200L).toFloat()
                else fadeMs.toFloat().coerceAtLeast(1f)

        val fadeIn = (pos / f).coerceIn(0f, 1f)
        val fadeOut = if (dur > 0) ((dur - pos) / f).coerceIn(0f, 1f) else 1f
        return minOf(fadeIn, fadeOut, antiPop) * gate * automixGain * userGain
    }
}
