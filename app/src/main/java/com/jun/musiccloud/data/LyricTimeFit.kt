package com.jun.musiccloud.data

/**
 * 歌词时间轴校准。
 *
 * 背景（用户反复反馈"有的歌歌词比人声快一大截，有的又正常"）：
 * 在线歌词是按「歌名 + 歌手」匹配回来的，而播放的音源经常是另一个版本——
 * 试听剪辑版、MV 版、重制版、现场版，长度相差十几秒到一分钟都很常见。
 * 歌词本身没有错，错的是它假设的那条音轨长度；一旦版本不同，越往后偏得越多，
 * 听感就是"整句提前一大截"。
 *
 * 这里做三件克制的事：
 *  1. 先应用歌词文件自带的 `[offset:±ms]`（官方就给了，以前被丢掉）；
 *  2. 只在**偏差明显**时，把整条时间轴按比例映射到「本曲真实时长」；
 *  3. 尾奏异常长（人声早结束、纯音乐拖很久）的歌一律豁免，避免把它们拉慢。
 */
object LyricTimeFit {

    /**
     * 应用 `[offset:±毫秒]`：正值表示歌词整体**推后**（更晚出现）。
     * LRC 规范里 offset 是"提前量"，正数让歌词延后，负号提前。
     */
    fun applyOffset(lines: List<LyricLine>, offsetMs: Long): List<LyricLine> {
        if (offsetMs == 0L || lines.isEmpty()) return lines
        return lines.map { line ->
            val t = (line.timeMs + offsetMs).coerceAtLeast(0L)
            if (line.words.isEmpty()) {
                line.copy(timeMs = t)
            } else {
                line.copy(
                    timeMs = t,
                    words = line.words.map { w ->
                        w.copy(startMs = (w.startMs + offsetMs).coerceAtLeast(0L))
                    }
                )
            }
        }
    }

    fun fitToDuration(lines: List<LyricLine>, durationMs: Long): List<LyricLine> {
        // 6.9.12：**彻底停用整条时间轴缩放**。
        //
        // 这个功能从一开始就是在猜："歌词末行落在歌曲 65% 处，那大概是短版音源，
        // 把它拉长一点"。但猜错的方向是双向的，而且代价极高：
        //   · durationMs 在在线歌曲上经常先返回 0/试听时长/上一首残留值，
        //     拿它当分母 → 整条时间轴被压缩（scale 最低 0.80），
        //     听感就是"开头还行，越唱歌词越往前跑"——正是用户反馈的现象；
        //   · 一旦压缩，逐句误差会随播放线性放大，看起来像"歌词自己在加速"，
        //     用户再怎么用校准滑条（只能整体平移）也永远调不准。
        //
        // 主流播放器（网易云 / 酷狗 / QQ 音乐）的做法是不改歌词时间轴，
        // 它们相信平台返回的歌词和自己播放的音源是同一版本；
        // 版本真的不同时，用户自己换源，而不是靠客户端偷偷拉伸。
        // 所以这里改成"原样返回"，只保留 `[offset:]` 的真正平移（见 applyOffset）。
        // 需要手动对齐时走设置里的「歌词时间校准」（平移，不会改变速率）。
        return lines
    }
}
