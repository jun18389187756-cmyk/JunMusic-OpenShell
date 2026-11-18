package com.jun.musiccloud.data

/**
 * 图书馆模式：只放轻音乐 / 纯音乐 / 白噪音 / lofi，把带歌词的歌挡在门外。
 *
 * 两道闸门：
 *  1. 元数据初筛（本文件）：标题/专辑里写着「纯音乐」「白噪音」「lofi」的优先，
 *     写着「现场」「翻唱」「feat.」的排除；
 *  2. 歌词预筛（[hasVocalLyrics]）：歌曲轮到播放之前先拉一次歌词，
 *     有实质歌词的在后台直接移出队列，轮到播放的只剩能听的歌。
 *     纯音乐通常没有歌词，或只有「纯音乐，请欣赏」这类占位文案。
 *
 * 只靠关键词会漏掉大量没有标签的器乐，只靠歌词又无法提前排序，所以两者配合使用。
 */
object LibraryMode {

    /** 强器乐标签：即使标题里同时有 Live / 现场，也以器乐为准（如「现场钢琴」） */
    private val friendlyStrongCn = listOf(
        "纯音乐", "轻音乐", "白噪音", "白噪声", "白躁音", "无人声", "无歌词",
        "纯享", "伴奏", "八音盒", "音乐盒", "钢琴", "吉他", "古筝", "二胡",
        "琵琶", "笛子", "箫", "葫芦丝", "小提琴", "大提琴", "萨克斯", "口琴",
        "手风琴", "自然音", "环境音", "器乐"
    )

    /** 弱场景标签：只参与排序，不覆盖「现场 / 翻唱」这类明确人声标记 */
    private val friendlyWeakCn = listOf(
        "助眠", "催眠", "冥想", "放松", "解压", "学习", "专注", "看书", "阅读",
        "背景音乐", "配乐", "原声", "雨声", "海浪", "风声", "鸟鸣", "溪流",
        "篝火", "森林", "咖啡馆", "咖啡厅", "爵士", "古典", "巴洛克", "奏鸣曲",
        "协奏曲", "小夜曲", "圆舞曲", "夜曲", "练习曲", "即兴", "轻快", "治愈",
        "舒缓", "安静", "空灵", "氛围", "电子氛围"
    )

    private val friendlyCn = friendlyStrongCn + friendlyWeakCn

    /** 强英文器乐标签 */
    private val friendlyStrongEn = Regex(
        "(?i)(?<![a-z])(instrumental|inst\\.|lofi|lo-fi|ambient|piano|guitar|" +
            "violin|cello|saxophone|music box|white noise|ost|soundtrack|" +
            "jazz|classical)(?![a-z])"
    )

    /** 弱场景标签单独用词边界匹配，避免 "rain" 命中 "train" */
    private val friendlyEn = Regex(
        "(?i)(?<![a-z])(instrumental|inst\\.|lofi|lo-fi|chillhop|chill|ambient|" +
            "piano|guitar|violin|cello|saxophone|music box|white noise|" +
            "sleep|study|focus|meditation|relax|spa|asmr|ost|soundtrack|" +
            "jazz|classical|nature|rain|ocean|waves)(?![a-z])"
    )

    /** 明显带人声 / 不适合图书馆的标签。命中后若同时命中 friendlyCn/En，以 friendly 为准 */
    private val vocalCn = listOf(
        "现场", "演唱会", "翻唱", "合唱", "对唱", "人声", "演唱版", "歌词版",
        "清唱", "独唱", "原唱", "点歌", "男声", "女声", "说唱", "摇滚",
        "金属", "朋克", "热歌", "情歌", "抖音", "口水歌", "喊麦"
    )

    private val vocalEn = Regex(
        "(?i)(?<![a-z])(live|cover|feat|ft\\.|remix|dj|rap|ktv|acapella|" +
            "a cappella|vocal|singing)(?![a-z])"
    )

    /** 歌词里的制作人员名单，不是歌词正文 */
    private val creditCn = Regex(
        "^\\s*(作词|作曲|编曲|制作人|监制|出品|发行|录音|混音|母带|吉他|贝斯|鼓|" +
            "和声|键盘|弦乐|人声|演唱|原唱|词|曲|词曲|制作|统筹|企划|营销|封面|" +
            "设计|混音工程师|母带工程师|录音工程师|编曲人)(\\s*/\\s*[^:：]+)?\\s*[:：]"
    )

    private val creditEn = Regex(
        "(?i)^\\s*(lyrics?|composed|composer|arranged|arranger|producer|produced|" +
            "mixed|mixer|mastered|mastering|recorded|recording|vocals?|performed|" +
            "written)\\s*(by)?\\s*[:：]"
    )

    /** 纯音乐占位歌词（网易云大量器乐会填这句），不能算“有歌词” */
    private val instrumentalNote = Regex(
        "纯音乐|请欣赏|没有填词|无歌词|暂无歌词|instrumental|no lyrics|" +
            "this song has no lyrics|music only",
        RegexOption.IGNORE_CASE
    )

    /** 标题 / 专辑 / 来源里带明确器乐标签 */
    fun isLikelyFriendly(song: Song): Boolean {
        val hay = "${song.title} ${song.album} ${song.folderName}".lowercase()
        return friendlyCn.any { hay.contains(it) } || friendlyEn.containsMatchIn(hay)
    }

    /** 标题 / 专辑里带明显人声标签；强器乐标签优先级更高（如「现场钢琴」） */
    fun isLikelyVocal(song: Song): Boolean {
        val hay = "${song.title} ${song.album}".lowercase()
        if (friendlyStrongCn.any { hay.contains(it) } || friendlyStrongEn.containsMatchIn(hay)) return false
        return vocalCn.any { hay.contains(it) } || vocalEn.containsMatchIn(hay)
    }

    /**
     * 播放队列初筛：只去掉元数据上就写着现场 / 翻唱 / feat. 的歌。
     * 没有标签的歌保留，之后由后台歌词预筛决定去留。
     */
    fun filterForPlayback(songs: List<Song>): List<Song> = songs.filterNot { isLikelyVocal(it) }

    /** 器乐候选排前面，没标签的排后面，让开启模式后更容易从纯音乐开始放 */
    fun prioritize(songs: List<Song>): List<Song> {
        val (friendly, rest) = filterForPlayback(songs).partition { isLikelyFriendly(it) }
        return friendly + rest
    }

    /**
     * 判断一份歌词是不是真的“有人唱”。
     *
     * 全部是作词/作曲/编曲等制作名单、或「纯音乐，请欣赏」占位文案时视为无歌词；
     * 至少要有 2 行、合计 6 个以上有效字符才算人声歌词，避免一句语气词误伤。
     */
    fun hasVocalLyrics(lines: List<LyricLine>): Boolean {
        if (lines.isEmpty()) return false
        var meaningfulLines = 0
        var meaningfulChars = 0
        for (line in lines) {
            val text = line.text.trim()
            if (text.isEmpty()) continue
            if (creditCn.containsMatchIn(text) || creditEn.containsMatchIn(text)) continue
            if (instrumentalNote.containsMatchIn(text)) continue
            val chars = text.count { it.isLetterOrDigit() }
            if (chars == 0) continue
            meaningfulLines++
            meaningfulChars += chars
            if (meaningfulLines >= 2 && meaningfulChars >= 6) return true
        }
        return false
    }
}
