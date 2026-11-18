package com.jun.musiccloud.data

import android.content.SharedPreferences

/**
 * 音质档位的进程级快照。
 *
 * 为什么要有这个对象而不是让代理去读 SharedPreferences：
 *  - 播放地址在 [com.jun.musiccloud.playback.AudioProxyServer] 的 socket 线程里解析，
 *    那一边拿 Context 很别扭；
 *  - 解析发生在起播瞬间，读一次 prefs 会把 IO 压在首帧路径上；
 *  - 设置页改档位后要**立刻**对下一首歌生效，volatile 写字段比重建队列便宜得多。
 *
 * 默认 [AudioQuality.STANDARD]（5.2feat R4）：播放页那枚音质按钮要一进来就读得出
 * 「标准音质」，而不是「在线」「MP3」这种和档位无关的字。默认走标准档在链路上是安全的——
 * 拿不到就按 [AudioQuality.candidates] 逐级降回原链路，不会播不出来。
 */
object AudioQualityStore {

    @Volatile
    var chosen: AudioQuality = AudioQuality.STANDARD
        private set

    /** 服务/代理侧读取（无 Context 场景），由 UI 侧 [load] 填充 */
    fun current(): AudioQuality = chosen

    fun load(prefs: SharedPreferences) {
        chosen = AudioQuality.of(prefs.getString(AudioQuality.PREF_KEY, null), AudioQuality.STANDARD)
    }

    /** 写盘 + 更新进程快照；档位变化不影响已在播的曲目，从下一首起生效 */
    fun save(prefs: SharedPreferences, quality: AudioQuality) {
        prefs.edit().putString(AudioQuality.PREF_KEY, quality.name).apply()
        chosen = quality
    }

    /**
     * 快路径（meting 302）的连接/读取超时，毫秒。
     *
     * 6.4：这个源实测 0.25~0.31 秒返回，所以 2.5 秒已经是很宽的余量了。
     * 之所以要"短"：它排在整条解析链路的最前面，一旦它卡住（源站挂了、被墙），
     * 用户等的是这个超时，之后才轮到后面那些源——超时设成 9 秒，
     * 用户感受到的就是"点了歌要等 9 秒"。宁可 2.5 秒放弃、换下一条源。
     */
    fun fastTimeoutMs(): Int =
        // 6.7.2：2500 → 1500。meting 的 302 正常 250~350ms 就回来，
        // 2.5 秒只会在"这个源根本没这首"时白白罚站。宁可早一点判它没结果、
        // 让并行的脚本链接手，也不要让用户对着转圈等两秒半。
        1500
}

/**
 * 播放音质档位（5.1 新增）。
 *
 * 设计约束（重要）：
 *  - **默认 [STANDARD]**（5.2feat R4）：播放页那枚按钮直接读得出档位名。
 *  - 只有用户主动选了 [EXHIGH]/[LOSSLESS]/[HI_RES]，且**本机已登录网易云账号**
 *    （[NeteaseStore.isLoggedIn]），才会先尝试官方 eapi 高档位接口。
 *  - 任何一级拿不到地址都自动**逐级降级**，最终一定落回原链路
 *    （「xfabe → Meting → outer/url → jOOX」），不会出现「选了无损就放不出来」。
 *
 * 档位与网易云官方 level 字符串一一对应，便于直接塞进接口参数。
 */
enum class AudioQuality(
    /** 网易云 `level` 参数值；空串表示不参与 eapi 档位（走原链路） */
    val level: String,
    /** 界面短标签 */
    val label: String,
    /** 音质说明：只说档位，不写 MP3 / FLAC 这类容器名 */
    val hint: String,
    /** 是否需要网易云账号权限（VIP/付费包的歌即使选了也拿不到，会自动降级） */
    val requiresLogin: Boolean
) {
    AUTO("", "自动", "每首自动挑当前能放出来的最高音质", false),
    STANDARD("standard", "标准", "常规音质", false),
    EXHIGH("exhigh", "极高", "接近 CD 的音质", true),
    LOSSLESS("lossless", "无损", "无损音质", true),
    HI_RES("hires", "Hi-Res", "高解析度音质", true);

    /**
     * 播放页音质按钮上的文案：**永远是档位名**（标准音质 / 无损音质 / Hi-Res 音质…）。
     *
     * 用户明确不要「MP3」「在线」「解析中」这类和音质无关的字。真实解析结果仍然要说，
     * 但那是切档那一刻闪条的事（qualityFlash），不是把这枚按钮当成状态显示屏。
     */
    val badgeLabel: String
        get() = if (this == AUTO) "自动" else "${label}音质"

    companion object {
        const val PREF_KEY = "audio_quality_level"

        /** 读盘：认不出来（含键不存在）就落回 [fallback]，新装默认 [STANDARD] */
        fun of(name: String?, fallback: AudioQuality = AUTO): AudioQuality =
            entries.firstOrNull { it.name == name } ?: fallback

        /**
         * 降级候选顺序：从用户选的档位开始，逐级往下，最后一定补一个 [AUTO]
         * 兜底（即原链路）。相同 level 去重，[AUTO] 只出现在末尾一次。
         */
        fun candidates(chosen: AudioQuality): List<AudioQuality> {
            if (chosen == AUTO) return listOf(AUTO)
            val ordered = listOf(chosen) + entries.filter {
                it != AUTO && it != chosen && it.ordinal < chosen.ordinal
            }.sortedByDescending { it.ordinal }
            return (ordered.distinct() + AUTO).distinct()
        }
    }
}

/**
 * 一次音频解析的结果：地址 + 真实拿到的档位/容器/码率。
 *
 * 5.0 之前 [OnlineMusicApi.resolveAudioUrl] 只返回 String，界面上的「HQ/SQ」徽章
 * 其实是写死的标签。带上这三个字段后，徽章可以显示**真实**容器与码率。
 */
data class ResolvedAudio(
    val url: String,
    /** 命中的档位；原链路解析不出来时为 null（沿用「在线」文案） */
    val level: String? = null,
    /** 容器/编码，如 mp3 / flac */
    val format: String? = null,
    /** 码率 bps */
    val bitrate: Int = 0,
    /** 文件大小（字节），0=未知 */
    val sizeBytes: Long = 0L,
    /**
     * 6.3.1：这条地址实际对应的「替代版本」曲目名（原曲无版权、换了同名的可播放版本时才有值）。
     * null 表示放的就是原曲。UI 可据此提示「正放的是同名版本」。
     */
    val altTitle: String? = null,
    /**
     * 6.3.2：这条地址是**试听片段**（30/45 秒），不是完整曲目。
     *
     * 来源：用户现场「有些歌只有 30 秒、45 秒，切一下音质才正常」。
     * 根因是默认走的 meting 直连是网易云官方接口，未登录/无版权时只返回试听片段
     * （实测 721KB ≈ 45 秒，完整版同曲 7~32MB）。播放器不知道，就当成整首放了。
     *
     * 解析层探测到残片会继续往下找完整版（见 [OnlineMusicApi.resolveAudio]）；
     * 只有所有源都只剩残片时才把 trial=true 交出去，UI 据此提示「只有试听版」，
     * 而不是让用户听 45 秒突然没了还以为是播放器坏了。
     */
    val trial: Boolean = false,
    /**
     * 6.8.0：**这条地址是哪个通道给的**（`qq` / `meting` / `script` / `local` / `library`）。
     *
     * 加这个字段只为一件事：失败时能上报"是哪个源出了问题"。
     * 在此之前，链路里所有通道的返回值长得一模一样，后台只能看到
     * "这首歌没解析出来"，永远不知道是 QQ 挂了、meting 空了、还是某个脚本限流。
     * 没有这个区分，"哪个源用不了了"这个问题就只能靠猜。
     *
     * 空串 = 未知（老代码路径），上报时归到 `unknown`。
     */
    val provider: String = ""
) {
    /**
     * 真实解析结果的档位说法，只用在切档那一刻的闪条上（音质按钮本身永远显示所选档位）。
     *
     * **只显示档位中文名**（标准 / 极高 / 无损 / Hi-Res），不写 MP3 / FLAC 这类容器名，
     * 也不再写「在线」——用户认的是档位，不是封装格式，也不是"这首歌从哪来的"。
     * 原链路（没有 level 字段）就按码率反推到最贴近的一档；反推不出来返回空串，
     * 由调用方换成不给用户看内部链路的说法。
     */
    /**
     * 拿不准时的兜底文案（6.1 起返回空串）。
     *
     * 以前这里返回「内置链路」，闪条就写成"想要无损 · 实际走内置链路"——
     * 用户明确说这种句子不能出现：那是实现细节，不是给人的提示。
     * 现在由调用方（PlayerViewModel.qualityFlashText）在拿到空串时改说人话。
     */
    fun badge(): String {
        level?.takeIf { it.isNotBlank() }?.let { l ->
            AudioQuality.entries.firstOrNull { it.level == l.lowercase() }?.let { return it.label }
        }
        val f = format?.lowercase().orEmpty()
        val kbps = bitrate / 1000
        return when {
            f.contains("flac") || f.contains("ape") || f.contains("wav") || f.contains("alac") ->
                if (kbps >= 1400) AudioQuality.HI_RES.label else AudioQuality.LOSSLESS.label
            kbps >= 280 -> AudioQuality.EXHIGH.label
            kbps >= 96 -> AudioQuality.STANDARD.label
            else -> ""
        }
    }
}
