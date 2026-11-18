package com.jun.musiccloud.data

/**
 * 首页「每日轮换」的数据源（6.3.8 新增）。
 *
 * 为什么单独写一个文件而不是塞进 [NeteaseApi]：
 * 这里做的是**编排**——把几个互不相干的接口按"今天几号"组装成固定栏目，
 * 而 [NeteaseApi] 只负责单个接口的收发。混在一起以后接口一改就要翻 700 行。
 *
 * ## 为什么需要"每日轮换"
 *
 * 网易云有「每日推荐」，但那个接口要登录（`/recommend/songs` 需要 `MUSIC_U`）。
 * 用户明确要求「需要登录的就不要加上去了」，同时又要求「每天能更新」。
 * 两者都要满足，只剩一条路：**用免登录的公共数据，按日期做确定性轮换**。
 *
 * 做法：
 *   - 取一个基准池（例如全部 65 个榜单 / 12 位热门歌手 / 12 张新专辑）；
 *   - 用 `LocalDate.toEpochDay()` 作为种子，做一次稳定的偏移；
 *   - 同一天内任何人打开看到的是同一组，**过了零点自动换一批**。
 *
 * 这比"随机"好：随机每次进首页都变，用户会以为 App 在乱跳；
 * 按日期轮换是"每天换一次"，符合"每日推荐"的心理预期。
 *
 * ## 语言/风格栏目（粤语、说唱、电音、古典）
 *
 * 网易云有「语种」和「风格」两个维度，但 `/personalized` 不带筛选能力。
 * 能按风格拿歌的免登录接口只有榜单——网易云官方榜单本身就按风格分好了
 * （中文说唱榜、电音榜、古典榜、全球说唱榜…），所以直接用榜单 id 当"风格栏目"，
 * 既准又不用自己猜标签。
 */
object HomeFeed {

    /**
     * 风格/语种栏目定义。
     *
     * [id] 是榜单 id（与 `/toplist/detail` 返回的一致）；
     * [name] 是首页标题；[tone] 用来给卡片一个区分色（暗色遮罩上的小标签）。
     *
     * 这里只放**真的能拿到歌**的榜。之前试过 `/simi/song`、`/style/recommend`
     * 这类接口，要么 200 但空数组、要么 404，所以全部弃用。
     */
    data class Channel(
        val id: String,
        val name: String,
        val label: String
    )

    val CHANNELS: List<Channel> = listOf(
        Channel("991319590", "中文说唱", "说唱"),
        Channel("14028249541", "全球说唱", "Rap"),
        Channel("1978921795", "电子音乐", "电音"),
        Channel("71384707", "古典音乐", "古典")
    )

    /**
     * 粤语栏目用的不是榜单，而是「搜索 + 固定关键词」。
     *
     * 原因：网易云没有独立的"粤语榜"。榜单里最接近的是"华语榜"，
     * 但它混了国语；而 `search` 接口支持按关键词搜，搜"粤语"能稳定拿回
     * 一批标注为粤语的歌单/歌曲。这里用 `searchPlaylists` 取歌单，
     * 再用歌单里的歌，准确度比硬凑榜单高。
     */
    const val CANTONESE_QUERY = "粤语经典"

    /**
     * 用日期算一个稳定的随机种子。
     *
     * 用 `toEpochDay` 而不是 `System.currentTimeMillis()`：后者每次调用都不同，
     * 会导致"同一天刷新两次看到两批不同的歌"。
     *
     * 6.9.3：**不再用 java.time**。用户线上报的崩溃
     * `NoClassDefFoundError: Failed resolution of: Ljava/time/LocalDate;`
     * 出现在 Android 7.1（API 25）上——`java.time` 要 API 26，项目又没开脱糖。
     * 这里改成 Calendar 算"本地时区的第几天"，语义完全一致，24 起都能跑。
     */
    private fun todayEpochDay(): Long {
        val c = java.util.Calendar.getInstance()
        val local = c.timeInMillis + c.timeZone.getOffset(c.timeInMillis)
        return if (local >= 0) local / 86_400_000L else (local - 86_399_999L) / 86_400_000L
    }

    private fun daySeed(salt: Int): Long {
        val day = todayEpochDay()
        return day * 2654435761L + salt * 40503L
    }

    /** 用日期算一个稳定的偏移量。[size] 是要轮换的池子大小，返回值恒在 `0 until size`。 */
    fun dailyOffset(size: Int, salt: Int = 0): Int {
        if (size <= 0) return 0
        return ((daySeed(salt) and 0x7FFFFFFFL) % size).toInt()
    }

    /**
     * 从池子里按日期取 [count] 个，**每天重新洗牌**。
     *
     * 早期版本只是"整体平移 offset"（`pool[(start+i) % size]`），实测下来相邻两天
     * 的 8 首里有 7 首重合——因为平移一格而已，用户根本感觉不到"今天更新了"。
     * 现在改成：以日期为种子做一次 Fisher-Yates 洗牌，每天的组合完全不同。
     *
     * 洗牌用的是固定种子的 `java.util.Random`，所以**同一天内多次调用结果一致**
     * （不会每进一次首页就换一批），过了零点自然变新。
     */
    fun <T> dailyPick(pool: List<T>, count: Int, salt: Int = 0): List<T> {
        if (pool.isEmpty()) return emptyList()
        val n = minOf(count, pool.size)
        val idx = pool.indices.toMutableList()
        val rnd = java.util.Random(daySeed(salt))
        // Fisher-Yates：从后往前换，只需要打乱前 n 个位置即可停下
        for (i in idx.lastIndex downTo 1) {
            if (i < idx.size - n) break
            val j = rnd.nextInt(i + 1)
            val t = idx[i]; idx[i] = idx[j]; idx[j] = t
        }
        return (idx.size - n until idx.size).map { pool[idx[it]] }
    }

    /** 今天的日期字符串，用于"X 月 X 日更新"这类文案。同样不碰 java.time。 */
    fun todayLabel(): String {
        val c = java.util.Calendar.getInstance()
        return "${c.get(java.util.Calendar.MONTH) + 1} 月 ${c.get(java.util.Calendar.DAY_OF_MONTH)} 日"
    }
}
