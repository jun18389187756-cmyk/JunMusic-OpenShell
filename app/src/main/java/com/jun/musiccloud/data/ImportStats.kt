package com.jun.musiccloud.data

/**
 * 6.4：一次歌单导入的结果快照。
 *
 * 为什么不再用一句 "已导入 N 首" 的字符串：
 * 用户导 387 首、实际只进来 264 首，光看这一句根本不知道发生了什么 ——
 * 是搜不到？是 VIP？是平台只给了前 10 首？三种情况该做的补救完全不同。
 *
 * 这里把「总量 / 进来多少 / 预计能直接播多少 / 要换源多少 / 具体缺哪几首」
 * 拆开，界面才能说出人话。
 *
 * 注意 [playable] 和 [needSource] 是**预判**，不是保证：
 *  - 网易云侧按 fee 字段算（0/8 可播，1/4 需换源）
 *  - NULL 表示服务端没法判断（酷狗这类要走反搜的平台），界面显示"播放时才知道"
 * 播放失败时客户端会纠正标记，所以不会一直错下去。
 */
data class ImportStats(
    /** 原歌单总首数（平台上写的那个数） */
    val total: Int = 0,
    /** 实际进到本地歌单的曲目数 */
    val imported: Int = 0,
    /** 预计能直接播的曲目数；null = 无法预判 */
    val playable: Int? = null,
    /** 预计需要换源的曲目数；null = 无法预判 */
    val needSource: Int? = null,
    /** 具体没匹配上的歌（最多 200 条） */
    val missing: List<Pair<String, String>> = emptyList(),
    /** 服务端只开放了前 N 首（酷狗 gcid 这类），true 时不要说"导入成功" */
    val limited: Boolean = false,
    /** limited 的原因说明，直接可显示 */
    val limitedNote: String = "",
    /** 时间预算先到、还没来得及匹配的首数（再导一次能续上） */
    val skipped: Int = 0,
    /** 是否这批歌自带平台 id（QQ / 酷狗），不需要走网易云匹配 */
    val direct: Boolean = false,
    /** 服务端明确说「这个平台的可播性预判不了」，界面显示"播放时自动匹配" */
    val predictUnknown: Boolean = false,
    /** 具体哪几首需要换源（网易云 id），写进歌单的可播标记 */
    val needSourceIds: List<String> = emptyList()
) {
    /** 一句话概括，给 Toast / 状态行用 */
    fun headline(): String = when {
        limited -> "已导入 $imported 首（受平台限制，未取全）"
        total > imported -> "已导入 $imported 首（歌单共 $total 首）"
        else -> "已导入 $imported 首"
    }

    /** 有没有可说的可播信息（有才画那一行，否则界面会空一格） */
    val hasPlayableInfo: Boolean
        get() = playable != null || predictUnknown
}
