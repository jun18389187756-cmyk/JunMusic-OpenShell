package com.jun.musiccloud.data

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** 单字（词）歌词：逐字点亮用。startMs 为该字开始时间。 */
data class LyricWord(
    val startMs: Long,
    val text: String,
    /**
     * 6.9.13：该字**真实的**结束时间（毫秒）。
     *
     * QRC / YRC / KRC / 方括号内联轴都同时给「开始」和「时长」，这是大厂逐字对齐的
     * 全部秘密——播放器不需要猜这个字播多久，查表就行。以前只存了开始时间，
     * 结束时间靠"下一个字的开始"推，遇到句尾拖腔、和声、气口就会整段错位。
     * 0 = 该格式没给时长（增强型 LRC 的 `<mm:ss.xx>`），退回用下一个字推断。
     */
    val endMs: Long = 0L
) {
    /** 有真实时长才敢用它当"这个字唱到什么时候" */
    val hasRealSpan: Boolean get() = endMs > startMs
}

/** 单行歌词 */
data class LyricLine(
    /** 该行开始时间（毫秒） */
    val timeMs: Long,
    val text: String,
    /**
     * 词级时间戳（增强型 LRC 的 <mm:ss.xx> 行内标签）。
     * 为空表示只有行级时间——展示层退回整行高亮，不做逐字。
     */
    val words: List<LyricWord> = emptyList(),
    /** 翻译行（.t.lrc / 在线 tlyric）：与 [text] 同步显示的第二行 */
    val translation: String? = null,
    /** 音译/罗马音行（.r.lrc / 在线 romalrc） */
    val romanization: String? = null,
    /**
     * 行级真实时长（毫秒），来自增强标签 `[开始,时长]` 的第二个数。
     * 0 = 未知。有它就不能拿"下一行开始"当本行结束——网易云 YRC 一句唱完到下一句
     * 之间常有 0.4~1.5s 空拍，用下一行起点会把最后一个字拖到空拍里才亮完。
     */
    val durationMs: Long = 0L
) {
    /** 是否带词级时间，可做逐字点亮 */
    val hasWords: Boolean get() = words.isNotEmpty()

    /** 是否有译文可显示 */
    val hasTranslation: Boolean get() = !translation.isNullOrBlank()
}

object LyricParser {

    /** 在线歌词键前缀，见 [Song.lyricKey] */
    private const val ONLINE_PREFIX = "ol:"

    /**
     * 综合获取歌词，优先级：
     * 1. 音频文件内嵌歌词（MP3 ID3v2 USLT 帧）
     * 2. 同目录同名 .lrc 文件
     * 都没有返回空列表
     *
     * @param durationMs 歌曲时长，纯文本歌词会按时间均匀分配，实现滚动高亮
     */
    fun loadLyrics(audioPath: String?, durationMs: Long): List<LyricLine> {
        if (audioPath.isNullOrBlank()) return emptyList()

        // 在线歌词键："ol:<songId>" —— 走网络拉取 LRC 文本，再按时间轴解析
        if (audioPath.startsWith(ONLINE_PREFIX)) return loadOnline(audioPath, durationMs)

        val key = cacheKey(audioPath, durationMs)
        // 命中即返回：解析结果是不可变 List，多处共享安全
        synchronized(cacheLock) { cache[key] }?.let { return it }

        // 未命中：同一首歌的并发请求（封面歌词 / 全屏歌词 / 悬浮窗 / 唱片墙
        // 常常同时发起）在这里收敛到同一把锁上，只让第一个真正去解析文件，
        // 其余的等它做完直接读缓存，避免 N 次 RandomAccessFile 扫标签。
        val gate = inFlight.computeIfAbsent(key) { Any() }
        try {
            synchronized(gate) {
                synchronized(cacheLock) { cache[key] }?.let { return it }
                val result = loadUncached(audioPath, durationMs)
                synchronized(cacheLock) { cache[key] = result }
                return result
            }
        } finally {
            inFlight.remove(key, gate)
        }
    }

    /**
     * 在线歌词：从 "ol:<songId>" 提取歌曲 ID，走 [OnlineMusicApi] 拉 LRC 文本再解析。
     * 结果进同一份 LRU 缓存，避免全屏歌词 / 封面歌词 / 悬浮窗重复请求。
     */
    private fun loadOnline(key: String, durationMs: Long): List<LyricLine> {
        val ck = "$key|$durationMs"
        synchronized(cacheLock) { cache[ck] }?.let { return it }
        val gate = inFlight.computeIfAbsent(ck) { Any() }
        try {
            synchronized(gate) {
                synchronized(cacheLock) { cache[ck] }?.let { return it }
                val id = key.removePrefix(ONLINE_PREFIX)
                val bundle = runCatching { OnlineMusicApi.fetchLyricBundle(id) }
                    .getOrNull() ?: OnlineMusicApi.LyricBundle()
                // 两条轨都解析，但不再"二选一"：lrc 管断句，yrc 管时间（见 alignWordTimings）。
                // 旧实现按行数比例 60% 选一条整轨，而 yrc 常把两三句并成一行（86→44），
                // 于是整首歌被判定"逐字轨不完整"，退回比 yrc 早 2 秒的 lrc 轨
                // ——「歌词比唱得快」的主因。现在两边都要，取各自擅长的部分。
                val plain = bundle.lyric?.takeIf { it.isNotBlank() }
                    ?.let { parseText(it, durationMs) }.orEmpty()
                val word = bundle.verbatim?.takeIf { it.isNotBlank() }
                    ?.let { parseText(it, durationMs) }.orEmpty()
                val (aligned, matched) = if (plain.isEmpty() || word.isEmpty()) {
                    emptyList<LyricLine>() to 0
                } else {
                    alignWordTimings(plain, word, durationMs)
                }
                // 命中率门槛：绝大多数句子都能拿到真实起唱时间才用对齐结果。
                // 对不上（不同版本歌词、翻译歌词、伴奏带）就退回原来的整轨选择。
                val useAligned = aligned.isNotEmpty() && plain.isNotEmpty() &&
                    matched * 100 >= plain.size * 60
                val usableWord = word.isNotEmpty() && word.size * 5 >= plain.size * 3
                val lines = when {
                    useAligned -> aligned
                    usableWord -> word
                    else -> plain
                }
                // 译文轨要和主轨同源：ytlrc 是贴着 yrc 时间轴给的，tlyric 贴着 lrc。
                // 混用会让译文贴错行甚至整条丢（旧实现按"就近 1.2 秒"硬吸附，
                // 主轨两套时间轴差 2~3.7 秒时必然吸到邻句上）。
                val wordBased = useAligned || usableWord
                val translationTrack =
                    if (wordBased) (bundle.wordTranslation ?: bundle.translation) else bundle.translation
                val merged = mergeExtraLines(
                    lines,
                    translation = translationTrack?.let { parseText(it, durationMs) }.orEmpty(),
                    romanization = bundle.romanization?.let { parseText(it, durationMs) }.orEmpty()
                )
                // 6.6：**不再对时间轴做任何自动缩放 / 重映射**。
                // 以前这里先后调过 LyricTimeFit.fitToDuration（按 65% 猜系数）和
                // LyricVersionFit.fit（首尾锚定按比例拉伸），两者都会改变演唱速率、
                // 让逐句误差随播放线性累积 —— 正是「越唱越偏、副歌突然错一大截」的元凶。
                // 现在版本一致性交给数据层的版本指纹（OnlineMusicApi），解析层原样透传；
                // 用户要对齐只用整体平移（[offset:] / 歌词时间校准），平移不累积漂移。
                val fitted = merged
                // 只缓存"有内容"的结果。空结果进缓存等于把这首歌在当前进程里判了死刑：
                // 网络抖一次、风控一次，之后每次点开歌词都不再重试，用户只看到"没有歌词"。
                if (fitted.isNotEmpty()) synchronized(cacheLock) { cache[ck] = fitted }
                return fitted
            }
        } finally {
            inFlight.remove(ck, gate)
        }
    }

    /** 真正的解析路径：内嵌标签优先，其次同名歌词文件（LRC/VTT/TTML/QRC/YRC/KRC） */
    private fun loadUncached(audioPath: String?, durationMs: Long): List<LyricLine> {
        // 1. 内嵌歌词
        extractEmbeddedLyrics(audioPath)?.let { raw ->
            val l = parseText(raw, durationMs)
            if (l.isNotEmpty()) return l
        }
        // 2. 同名歌词文件（支持多格式；.t.lrc / .r.lrc 作为译文/音译合并）
        val main = findLyricFile(audioPath) ?: return emptyList()
        val lines = parse(main)
        if (lines.isEmpty()) return emptyList()
        val translation = findSidecar(audioPath, listOf("t", "trans", "zh", "translation"))
            ?.let { parse(it) }.orEmpty()
        val romanization = findSidecar(audioPath, listOf("r", "roma", "romaji", "pinyin"))
            ?.let { parse(it) }.orEmpty()
        return mergeExtraLines(lines, translation, romanization)
    }

    // ---------- 解析结果缓存 ----------
    /**
     * 缓存上限。一条歌词几 KB 到几十 KB，8 首足够覆盖"当前曲 + 前后翻动"的
     * 访问局部性；再多收益递减，白占内存。
     */
    private const val CACHE_MAX = 8

    /**
     * 缓存键带文件指纹（长度 + mtime）与 durationMs：
     * - 指纹保证用户替换/重写歌词文件后不会读到旧结果；
     * - durationMs 参与是因为纯文本歌词按时长均分时间戳，
     *   时长不同解析结果就不同（首次拿到的 duration 可能是 0）。
     * 注意 0 时长的结果同样会被缓存，但它的键与真实时长的键不同，
     * 播放器拿到真实时长后自然会重新解析一次，不会永久卡在退化结果上。
     */
    private fun cacheKey(audioPath: String, durationMs: Long): String {
        val f = File(audioPath)
        val lyric = findLyricFile(audioPath)
        val trans = findSidecar(audioPath, listOf("t", "trans", "zh", "translation"))
        // 外挂歌词的指纹也要进键：改了歌词文件但没动音频时同样要失效
        return buildString {
            append(audioPath).append('|')
            append(f.length()).append('|').append(f.lastModified()).append('|')
            append(lyric?.length() ?: 0L).append('|').append(lyric?.lastModified() ?: 0L).append('|')
            append(trans?.length() ?: 0L).append('|').append(trans?.lastModified() ?: 0L).append('|')
            append(durationMs)
        }
    }

    /** LRU：accessOrder=true，超出 [CACHE_MAX] 淘汰最久未访问的一首 */
    private val cache = object : LinkedHashMap<String, List<LyricLine>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<LyricLine>>?
        ): Boolean = size > CACHE_MAX
    }
    private val cacheLock = Any()

    /** 进行中的解析：同键并发只解析一次 */
    private val inFlight = ConcurrentHashMap<String, Any>()

    /** 清空歌词缓存（媒体库重扫后调用，避免读到已删除文件的残留结果） */
    fun clearCache() {
        synchronized(cacheLock) { cache.clear() }
    }

    /**
     * 从音频文件内嵌标签读取歌词原文。
     * 按格式分路径：
     * - MP3: ID3v2（USLT 优先，其次 TXXX:LYRICS，最后 SYLT 转 LRC），再兜底文件尾 APEv2
     * - FLAC: VORBIS_COMMENT 的 LYRICS 字段；文件头带 ID3v2 的 FLAC 先跳过它
     * - M4A/MP4: moov/udta/meta/ilst 的 ©lyr 原子或 freeform LYRICS
     * - OGG/Opus: Vorbis comment 的 LYRICS 字段
     * - WAV/AIFF: RIFF/FORM 里的 "id3 " 块；DSF: 头部指针指向的 ID3v2
     * - WMA: ASF 扩展内容描述里的 WM/Lyrics
     * - APE/WV/MPC 等：ID3v2 头或 APEv2 尾
     */
    fun extractEmbeddedLyrics(audioPath: String?): String? {
        if (audioPath.isNullOrBlank()) return null
        return runCatching {
            val f = File(audioPath)
            if (!f.exists() || f.length() < 16) return@runCatching null
            val lower = audioPath.lowercase()
            when {
                lower.endsWith(".mp3") || lower.endsWith(".mp2") ->
                    readId3(f, 0) ?: readApeTag(f)
                lower.endsWith(".flac") -> readFlacLyrics(f) ?: readId3(f, 0) ?: readApeTag(f)
                lower.endsWith(".m4a") || lower.endsWith(".mp4") || lower.endsWith(".m4b") ||
                        lower.endsWith(".alac") -> readMp4Lyrics(f)
                lower.endsWith(".ogg") || lower.endsWith(".oga") || lower.endsWith(".opus") ->
                    readOggLyrics(f) ?: readApeTag(f)
                lower.endsWith(".wav") || lower.endsWith(".wave") -> readChunkedId3(f, bigEndian = false)
                lower.endsWith(".aif") || lower.endsWith(".aiff") || lower.endsWith(".aifc") -> readChunkedId3(f, bigEndian = true)
                lower.endsWith(".dsf") -> readDsfLyrics(f)
                lower.endsWith(".wma") || lower.endsWith(".asf") -> readAsfLyrics(f)
                // ape / wv / mpc / tta / aac 等：ID3v2 头或 APEv2 尾
                else -> readId3(f, 0) ?: readApeTag(f)
            }
        }.getOrNull()
    }

    // ===== ID3v2 解析 =====

    /** 读取位于 [offset] 处的 ID3v2 标签中的歌词 */
    private fun readId3(file: File, offset: Long): String? {
        if (file.length() < offset + 10) return null
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val head = ByteArray(10)
            raf.readFully(head)
            if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return null
            val ver = head[3].toInt() and 0xFF
            if (ver < 2 || ver > 4) return null
            val flags = head[5].toInt() and 0xFF
            val tagSize = syncsafe(head, 6)
            if (tagSize <= 0 || offset + 10 + tagSize > file.length()) return null
            var body = ByteArray(tagSize)
            raf.readFully(body)
            var end = tagSize
            // 标签级 unsynchronisation：v2.2/v2.3 是整块处理；
            // v2.4 的帧长按"反同步后"计，必须逐帧还原，不能整块还原（否则帧长全错位）
            if ((flags and 0x80) != 0 && ver < 4) {
                body = deunsync(body, end)
                end = body.size
            }
            var start = 0
            if ((flags and 0x40) != 0 && ver >= 3) {
                start = if (ver >= 4) syncsafe(body, 0) else beInt(body, 0) + 4
                if (start < 0 || start >= end) start = 0
            }
            return if (ver == 2) readV22Frames(body, start, end)
            else readV23Frames(body, start, end, ver, tagUnsync = (flags and 0x80) != 0)
        }
    }

    private fun readV22Frames(body: ByteArray, start: Int, end: Int): String? {
        var uslt: String? = null
        var sylt: String? = null
        var txxx: String? = null
        var pos = start
        while (pos + 6 <= end) {
            if (body[pos].toInt() == 0) break
            val id = String(body, pos, 3, Charsets.ISO_8859_1)
            val size = ((body[pos + 3].toInt() and 0xFF) shl 16) or
                    ((body[pos + 4].toInt() and 0xFF) shl 8) or
                    (body[pos + 5].toInt() and 0xFF)
            pos += 6
            if (size <= 0 || pos + size > end) break
            when (id) {
                "ULT", "LYR" -> if (uslt == null) uslt = decodeUslt(body, pos, size)?.takeIf { it.isNotBlank() }
                "SLT" -> if (sylt == null) sylt = decodeSylt(body.copyOfRange(pos, pos + size))
                "TXX" -> if (txxx == null) txxx = decodeTxxx(body, pos, size)?.takeIf { it.isNotBlank() }
            }
            if (uslt != null) return uslt
            pos += size
        }
        return uslt ?: txxx ?: sylt
    }

    /**
     * v2.3/v2.4 帧遍历。歌词优先级：USLT > TXXX(LYRICS) > SYLT。
     * SYLT 是二进制同步歌词（文本+时间戳交替），按结构转成 LRC，不能当 USLT 文本读，
     * 否则会读出一串夹着乱码的单行"歌词"，还会挡住后面真正的 USLT。
     */
    private fun readV23Frames(body: ByteArray, start: Int, end: Int, ver: Int, tagUnsync: Boolean): String? {
        var uslt: String? = null
        var sylt: String? = null
        var txxx: String? = null
        var pos = start
        // 部分写入端（旧 iTunes 等）在 v2.4 里仍写普通 big-endian 帧长，
        // 遇到 syncsafe 解释下一帧落点不合法、普通解释合法时切换。
        var syncsafeSizes = ver >= 4
        while (pos + 10 <= end) {
            if (body[pos].toInt() == 0) break // 填充区
            if (!isFrameId(body, pos)) break
            val id = String(body, pos, 4, Charsets.ISO_8859_1)
            var size = if (syncsafeSizes) syncsafe(body, pos + 4) else beInt(body, pos + 4)
            if (syncsafeSizes && !nextFrameOk(body, pos + 10 + size, end)) {
                val alt = beInt(body, pos + 4)
                if (alt != size && nextFrameOk(body, pos + 10 + alt, end)) {
                    size = alt; syncsafeSizes = false
                }
            }
            val fmt = body[pos + 9].toInt() and 0xFF
            pos += 10
            if (size <= 0 || pos + size > end) break
            if (id == "USLT" || id == "SYLT" || id == "TXXX") {
                val fb = frameBody(body, pos, size, fmt, ver, tagUnsync)
                if (fb != null && fb.isNotEmpty()) {
                    when (id) {
                        "USLT" -> if (uslt == null) uslt = decodeUslt(fb, 0, fb.size)?.takeIf { it.isNotBlank() }
                        "SYLT" -> if (sylt == null) sylt = decodeSylt(fb)
                        "TXXX" -> if (txxx == null) txxx = decodeTxxx(fb, 0, fb.size)?.takeIf { it.isNotBlank() }
                    }
                }
                if (uslt != null) return uslt
            }
            pos += size
        }
        return uslt ?: txxx ?: sylt
    }

    /** 按帧格式标志还原帧体：分组字节 / 数据长度指示 / 反同步 / zlib 压缩；加密帧返回 null */
    private fun frameBody(body: ByteArray, pos: Int, size: Int, fmt: Int, ver: Int, tagUnsync: Boolean): ByteArray? {
        var b = body.copyOfRange(pos, pos + size)
        if (ver >= 4) {
            if ((fmt and 0x04) != 0) return null              // 加密
            var skip = 0
            if ((fmt and 0x40) != 0) skip += 1                // 分组标识
            if ((fmt and 0x01) != 0) skip += 4                // 数据长度指示（v2.4 常见，Mp3tag/foobar 压缩帧必带）
            if (skip > 0) b = if (b.size > skip) b.copyOfRange(skip, b.size) else return null
            if ((fmt and 0x02) != 0 || tagUnsync) b = deunsync(b, b.size)
            if ((fmt and 0x08) != 0) b = inflate(b) ?: return null
        } else {
            if ((fmt and 0x40) != 0) return null              // v2.3 加密
            if ((fmt and 0x80) != 0) {                        // v2.3 压缩：4 字节解压后长度 + zlib
                b = if (b.size > 4) inflate(b.copyOfRange(4, b.size)) ?: return null else return null
            }
            if ((fmt and 0x20) != 0 && b.isNotEmpty()) b = b.copyOfRange(1, b.size) // 分组标识
        }
        return b
    }

    private fun inflate(b: ByteArray): ByteArray? = runCatching {
        java.util.zip.InflaterInputStream(b.inputStream()).use { it.readBytes() }
    }.getOrNull()

    private fun isFrameId(b: ByteArray, p: Int): Boolean {
        if (p + 4 > b.size) return false
        for (i in 0 until 4) {
            val c = b[p + i].toInt().toChar()
            if (!(c in 'A'..'Z' || c in '0'..'9')) return false
        }
        return true
    }

    private fun nextFrameOk(b: ByteArray, p: Int, end: Int): Boolean =
        p == end || (p in 0 until end && (b[p].toInt() == 0 || isFrameId(b, p)))

    /** TXXX：编码 + 描述(终止) + 文本；仅当描述是歌词字段时返回文本 */
    private fun decodeTxxx(body: ByteArray, start: Int, size: Int): String? {
        if (size < 3) return null
        val enc = body[start].toInt() and 0xFF
        val dataEnd = start + size
        val descEnd = findTerminator(body, start + 1, dataEnd, enc)
        val desc = decodeText(body.copyOfRange(start + 1, descEnd), enc)
            .replace("\uFEFF", "").trim('\u0000', ' ')
        if (!isLyricFieldName(desc)) return null
        val p = (descEnd + termLen(enc)).coerceAtMost(dataEnd)
        if (p >= dataEnd) return null
        return decodeText(body.copyOfRange(p, dataEnd), enc)
    }

    /** USLT：编码 + 语言(3) + 描述(终止) + 歌词 */
    private fun decodeUslt(body: ByteArray, start: Int, size: Int): String? {
        if (size < 5) return null
        val enc = body[start].toInt() and 0xFF
        val dataEnd = start + size
        val descEnd = findTerminator(body, start + 4, dataEnd, enc)
        val p = (descEnd + termLen(enc)).coerceAtMost(dataEnd)
        if (p >= dataEnd) return null
        return runCatching { decodeText(body.copyOfRange(p, dataEnd), enc) }.getOrNull()
    }

    /**
     * SYLT：编码 + 语言(3) + 时间戳格式(1) + 内容类型(1) + 描述(终止)
     *       + { 文本(终止) + 时间戳(4, BE) }*
     * 时间戳格式 2 = 毫秒，转成 [mm:ss.xx] LRC；格式 1（MPEG 帧号）无法换算，只保留文本。
     */
    private fun decodeSylt(b: ByteArray): String? {
        if (b.size < 7) return null
        val enc = b[0].toInt() and 0xFF
        val tsFormat = b[4].toInt() and 0xFF
        var p = findTerminator(b, 6, b.size, enc) + termLen(enc)
        val sb = StringBuilder()
        while (p < b.size) {
            val tEnd = findTerminator(b, p, b.size, enc)
            val text = decodeText(b.copyOfRange(p, tEnd), enc).replace("\uFEFF", "").trimEnd('\u0000')
            p = tEnd + termLen(enc)
            if (p + 4 > b.size) break
            val ts = beInt(b, p).toLong() and 0xFFFFFFFFL
            p += 4
            val line = text.trim('\n', '\r')
            if (tsFormat == 2) {
                val mm = ts / 60000; val ss = (ts % 60000) / 1000; val cs = (ts % 1000) / 10
                sb.append(String.format("[%02d:%02d.%02d]", mm, ss, cs)).append(line).append('\n')
            } else if (line.isNotBlank()) {
                sb.append(line).append('\n')
            }
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun termLen(enc: Int) = if (enc == 1 || enc == 2) 2 else 1

    /** 找字符串终止符位置（UTF-16 按 2 字节对齐找 00 00），找不到返回 end */
    private fun findTerminator(b: ByteArray, from: Int, end: Int, enc: Int): Int {
        var p = from
        if (enc == 1 || enc == 2) {
            while (p + 1 < end) {
                if (b[p].toInt() == 0 && b[p + 1].toInt() == 0) return p
                p += 2
            }
            return end
        }
        while (p < end) {
            if (b[p].toInt() == 0) return p
            p++
        }
        return end
    }

    /** 按 ID3 编码字节解码文本 */
    private fun decodeText(b: ByteArray, enc: Int): String = when (enc) {
        0 -> decodeLegacy(b)
        1 -> decodeUtf16(b, false)
        2 -> decodeUtf16(b, true)
        else -> {
            val s = if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte())
                String(b, 3, b.size - 3, Charsets.UTF_8) else String(b, Charsets.UTF_8)
            s
        }
    }

    /** 反转 ID3v2 unsynchronisation：把 0xFF 0x00 还原成 0xFF，返回新数组 */
    private fun deunsync(src: ByteArray, len: Int): ByteArray {
        val out = ByteArray(len)
        var w = 0
        var i = 0
        while (i < len) {
            val b = src[i]
            out[w++] = b
            i++
            if (b == 0xFF.toByte() && i < len && src[i].toInt() == 0) i++
        }
        return out.copyOf(w)
    }

    /** 读取 4 字节普通 big-endian 整数 */
    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
                ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or
                (b[off + 3].toInt() and 0xFF)

    /** UTF-16：有 BOM 按 BOM 字节序（FE FF = BE，FF FE = LE），没有 BOM 用 [bigEndian] 默认 */
    private fun decodeUtf16(b: ByteArray, bigEndian: Boolean): String {
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        return String(b, if (bigEndian) Charsets.UTF_16BE else Charsets.UTF_16LE)
    }

    /**
     * "无编码声明"字节的解码：ID3 编码 0 名义上是 Latin-1，但大量国产写入端
     * 直接塞 GBK 字节，也有工具在编码 0 下写 UTF-8。
     * 顺序：纯 ASCII → 严格 UTF-8 → 严格 GB18030 且以汉字为主 → 严格 Shift_JIS → Latin-1。
     */
    private fun decodeLegacy(b: ByteArray): String {
        if (b.none { it < 0 }) return String(b, Charsets.ISO_8859_1)
        strictDecode(b, Charsets.UTF_8)?.let { return it }
        strictDecode(b, GB18030)?.let { if (cjkRatio(it) >= 0.5) return it }
        SHIFT_JIS?.let { cs -> strictDecode(b, cs)?.let { if (cjkRatio(it) >= 0.5) return it } }
        return String(b, Charsets.ISO_8859_1)
    }

    private val GB18030: java.nio.charset.Charset = java.nio.charset.Charset.forName("GB18030")
    private val SHIFT_JIS: java.nio.charset.Charset? = runCatching { java.nio.charset.Charset.forName("Shift_JIS") }.getOrNull()

    private fun strictDecode(b: ByteArray, cs: java.nio.charset.Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(b)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        null
    }

    /** 非 ASCII 字符里中日文字（汉字/假名/全角标点）的占比 */
    private fun cjkRatio(s: String): Double {
        var non = 0; var cjk = 0
        for (c in s) {
            if (c.code < 0x80) continue
            non++
            if (c in '\u3000'..'\u30FF' || c in '\u4E00'..'\u9FFF' || c in '\uFF00'..'\uFFEF' || c in '\u3400'..'\u4DBF') cjk++
        }
        return if (non == 0) 0.0 else cjk.toDouble() / non
    }

    // ===== 文件尾 APEv2（foobar2000 / Monkey's Audio / WavPack 常用） =====

    private fun readApeTag(file: File): String? {
        val len = file.length()
        if (len < 64) return null
        RandomAccessFile(file, "r").use { raf ->
            // APEv2 尾可能紧贴文件末尾，也可能在 ID3v1（128 字节）之前
            for (tail in longArrayOf(0L, 128L)) {
                val footerPos = len - tail - 32
                if (footerPos < 0) continue
                raf.seek(footerPos)
                val ft = ByteArray(32)
                raf.readFully(ft)
                if (String(ft, 0, 8, Charsets.ISO_8859_1) != "APETAGEX") continue
                val tagSize = readInt32LE(ft, 12).toLong() and 0xFFFFFFFFL   // 含 footer，不含 header
                val count = readInt32LE(ft, 16)
                if (tagSize < 32 || tagSize > footerPos + 32 || count !in 0..10_000) return null
                val itemsStart = footerPos + 32 - tagSize
                val items = ByteArray((tagSize - 32).toInt())
                raf.seek(itemsStart)
                raf.readFully(items)
                var p = 0
                repeat(count) {
                    if (p + 9 > items.size) return null
                    val vLen = readInt32LE(items, p)
                    val flags = readInt32LE(items, p + 4)
                    p += 8
                    var k = p
                    while (k < items.size && items[k].toInt() != 0) k++
                    if (k >= items.size) return null
                    val key = String(items, p, k - p, Charsets.ISO_8859_1)
                    p = k + 1
                    if (vLen < 0 || p + vLen > items.size) return null
                    // flags bit1-2 = 0 表示 UTF-8 文本
                    if ((flags and 0x06) == 0 && isLyricFieldName(key)) {
                        val v = String(items, p, vLen, Charsets.UTF_8).trim()
                        if (v.isNotEmpty()) return v
                    }
                    p += vLen
                }
                return null
            }
        }
        return null
    }

    // ===== WAV / AIFF 里的 ID3 块 =====

    private fun readChunkedId3(file: File, bigEndian: Boolean): String? {
        val len = file.length()
        var id3At = -1L
        RandomAccessFile(file, "r").use { raf ->
            val hdr = ByteArray(12)
            raf.readFully(hdr)
            val magic = String(hdr, 0, 4, Charsets.ISO_8859_1)
            if (magic != "RIFF" && magic != "RF64" && magic != "FORM") return null
            var pos = 12L
            val ch = ByteArray(8)
            while (pos + 8 <= len) {
                raf.seek(pos)
                raf.readFully(ch)
                val id = String(ch, 0, 4, Charsets.ISO_8859_1)
                val size = (if (bigEndian) beInt(ch, 4) else readInt32LE(ch, 4)).toLong() and 0xFFFFFFFFL
                if (id.equals("id3 ", true) || id.equals("ID32", true)) { id3At = pos + 8; break }
                pos += 8 + size + (size and 1L)
            }
        }
        return if (id3At > 0) readId3(file, id3At) else null
    }

    // ===== DSF：头部 20..27 字节是 ID3v2 的绝对偏移 =====

    private fun readDsfLyrics(file: File): String? {
        val ptr = RandomAccessFile(file, "r").use { raf ->
            val h = ByteArray(28)
            raf.readFully(h)
            if (String(h, 0, 4, Charsets.ISO_8859_1) != "DSD ") return null
            (readInt32LE(h, 20).toLong() and 0xFFFFFFFFL) or ((readInt32LE(h, 24).toLong() and 0xFFFFFFFFL) shl 32)
        }
        return if (ptr > 0 && ptr < file.length()) readId3(file, ptr) else null
    }

    // ===== WMA/ASF：Extended Content Description 里的 WM/Lyrics =====

    private val ASF_HEADER = byteArrayOf(0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
        0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62, 0xCE.toByte(), 0x6C)
    private val ASF_EXT_CONTENT = byteArrayOf(0x40, 0xA4.toByte(), 0xD0.toByte(), 0xD2.toByte(), 0x07, 0xE3.toByte(), 0xD2.toByte(), 0x11,
        0x97.toByte(), 0xF0.toByte(), 0x00, 0xA0.toByte(), 0xC9.toByte(), 0x5E, 0xA8.toByte(), 0x50)

    private fun readAsfLyrics(file: File): String? {
        RandomAccessFile(file, "r").use { raf ->
            val h = ByteArray(30)
            raf.readFully(h)
            if (!h.copyOfRange(0, 16).contentEquals(ASF_HEADER)) return null
            val headerSize = le64(h, 16)
            val n = readInt32LE(h, 24)
            var pos = 30L
            val oh = ByteArray(24)
            repeat(n.coerceIn(0, 1000)) {
                if (pos + 24 > headerSize) return null
                raf.seek(pos)
                raf.readFully(oh)
                val size = le64(oh, 16)
                if (size < 24 || pos + size > headerSize) return null
                if (oh.copyOfRange(0, 16).contentEquals(ASF_EXT_CONTENT) && size < 64L * 1024 * 1024) {
                    val obj = ByteArray((size - 24).toInt())
                    raf.readFully(obj)
                    var p = 0
                    val cnt = (obj[0].toInt() and 0xFF) or ((obj[1].toInt() and 0xFF) shl 8)
                    p += 2
                    repeat(cnt) {
                        if (p + 2 > obj.size) return null
                        val nl = le16(obj, p); p += 2
                        if (p + nl + 4 > obj.size) return null
                        val name = String(obj, p, nl, Charsets.UTF_16LE).trimEnd('\u0000'); p += nl
                        val type = le16(obj, p); p += 2
                        val vl = le16(obj, p); p += 2
                        if (p + vl > obj.size) return null
                        if (type == 0 && (name.equals("WM/Lyrics", true) || isLyricFieldName(name))) {
                            val v = String(obj, p, vl, Charsets.UTF_16LE).trimEnd('\u0000').trim()
                            if (v.isNotEmpty()) return v
                        }
                        p += vl
                    }
                    return null
                }
                pos += size
            }
        }
        return null
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    // ===== FLAC VORBIS_COMMENT 歌词 =====

    /**
     * FLAC：按 metadata block 结构跳到 VORBIS_COMMENT 块再取 LYRICS 字段。
     * 不再盲扫前 1MB——带大封面（PICTURE 块）的 FLAC，VORBIS_COMMENT 可能被推到
     * 1MB 之外，盲扫会漏读。VORBIS_COMMENT block type = 4。
     */
    private fun readFlacLyrics(file: File): String? {
        if (!file.exists() || file.length() < 8) return null
        RandomAccessFile(file, "r").use { raf ->
            // FLAC 魔数是 "fLaC"（大小写混合）。之前按全小写 "flac" 比较，
            // 所有 FLAC 都在这里直接返回 null——这是 FLAC 内嵌歌词一律读不出的根因。
            // 另外有些转换/打标工具会在 FLAC 前面加一段 ID3v2，先跳过它再找魔数。
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (magic[0] == 'I'.code.toByte() && magic[1] == 'D'.code.toByte() && magic[2] == '3'.code.toByte()) {
                val h = ByteArray(10)
                raf.seek(0); raf.readFully(h)
                val skip = 10L + syncsafe(h, 6) + (if ((h[5].toInt() and 0x10) != 0) 10 else 0)
                if (skip + 4 > file.length()) return null
                raf.seek(skip); raf.readFully(magic)
            }
            if (magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
                magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()) return null
            val fileLen = file.length()
            // 遍历 metadata block header：1 字节(最高位=last-block 标志 + 7 位 type) + 3 字节长度
            while (true) {
                val h = raf.read()
                if (h < 0) return null
                val isLast = (h and 0x80) != 0
                val type = h and 0x7F
                val b0 = raf.read(); val b1 = raf.read(); val b2 = raf.read()
                if (b0 < 0 || b1 < 0 || b2 < 0) return null
                val blockLen = (b0 shl 16) or (b1 shl 8) or b2
                if (blockLen < 0 || raf.filePointer + blockLen > fileLen) return null
                if (type == 4) {
                    // VORBIS_COMMENT：整块读进来按结构解析
                    val block = ByteArray(blockLen)
                    raf.readFully(block)
                    return parseVorbisComment(block)
                }
                raf.seek(raf.filePointer + blockLen)
                if (isLast) return null
            }
            @Suppress("UNREACHABLE_CODE")
            return null
        }
    }

    /** 解析 VORBIS_COMMENT 块（LE 长度前缀），返回 LYRICS/UNSYNCEDLYRICS 字段 */
    private fun parseVorbisComment(block: ByteArray): String? {
        var p = 0
        fun le32(): Int {
            if (p + 4 > block.size) return -1
            val v = (block[p].toInt() and 0xFF) or ((block[p + 1].toInt() and 0xFF) shl 8) or
                    ((block[p + 2].toInt() and 0xFF) shl 16) or ((block[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        val vendorLen = le32()
        if (vendorLen < 0 || p + vendorLen > block.size) return null
        p += vendorLen
        val count = le32()
        if (count < 0 || count > 100_000) return null
        repeat(count) {
            val cLen = le32()
            if (cLen < 0 || p + cLen > block.size) return null
            val entry = String(block, p, cLen, Charsets.UTF_8)
            p += cLen
            val eq = entry.indexOf('=')
            if (eq > 0 && isLyricFieldName(entry.substring(0, eq))) {
                val v = entry.substring(eq + 1).trim()
                if (v.isNotEmpty()) return v
            }
        }
        return null
    }

    /**
     * 歌词字段名归一判定：不同工具写的键五花八门——
     *   LYRICS / UNSYNCEDLYRICS / UNSYNCED LYRICS / UNSYNCED_LYRICS / LYRICS-XXX
     * 去空格与下划线后统一比较，避免换个写入端就读不出。
     */
    private fun isLyricFieldName(key: String): Boolean {
        val k = key.trim().uppercase().replace(" ", "").replace("_", "")
        return k == "LYRICS" || k == "UNSYNCEDLYRICS" ||
                k == "SYLT" || k == "SYNCEDLYRICS" || k.startsWith("LYRICS-")
    }

    // ===== MP4/M4A ©lyr 原子解析 =====
    //（v4.6.14 自研的 readApev2Lyrics 已由外部补丁的 readApeTag 取代并删除）

    /** 读取 MP4/M4A 的 ©lyr 原子（moov/udta/meta/ilst/©lyr），文本通常 UTF-8 */
    private fun readMp4Lyrics(file: File): String? {
        if (!file.exists() || file.length() < 16) return null
        RandomAccessFile(file, "r").use { raf ->
            // 递归搜原子路径，限制总扫描量
            val result = findMp4Atom(raf, 0, file.length(), 32, "moov", "udta", "meta", "ilst")
                ?: return null
            var (start, end) = result
            // ilst 内逐个 entry：size(4) + type(4)；©lyr = A9 6C 79 72
            var pos = start
            while (pos + 8 <= end) {
                raf.seek(pos)
                val size = readInt32BE(raf)
                if (size < 8) break
                val type = ByteArray(4)
                raf.readFully(type)
                if (type[0] == 0xA9.toByte() && type[1] == 'l'.code.toByte() &&
                    type[2] == 'y'.code.toByte() && type[3] == 'r'.code.toByte()
                ) {
                    // data 原子：size(4)+'data'(4)+versionFlags(4)+reserved(4)+内容
                    val dataStart = pos + 8
                    raf.seek(dataStart)
                    val dSize = readInt32BE(raf)
                    val dType = ByteArray(4)
                    raf.readFully(dType)
                    if (dType.contentEquals("data".toByteArray(Charsets.ISO_8859_1)) && dSize >= 16) {
                        raf.seek(dataStart + 8)
                        val skip = 8 // version(1)+flags(3)+reserved(4)
                        val textLen = dSize - 16
                        if (textLen > 0 && textLen < 1_000_000) {
                            raf.skipBytes(skip)
                            val txt = ByteArray(textLen)
                            raf.readFully(txt)
                            // 前 4 字节语言/locale 码（UTF-8 data 原子通常没有，直接尝试解码）
                            val s = decodeTextBytes(txt)
                            if (!s.isNullOrBlank()) return s.trim()
                        }
                    }
                } else if (type[0] == '-'.code.toByte()) {
                    // freeform 原子：---- + mean + name + data。
                    // 部分工具（MusicBee / foobar / 部分转换器）不写 ©lyr，
                    // 而把歌词写进 ----:com.apple.iTunes:LYRICS。
                    readMp4FreeformLyrics(raf, pos + 8, pos + size.toLong())?.let { s ->
                        if (s.isNotBlank()) return s.trim()
                    }
                }
                pos += size
            }
            return null
        }
    }

    /** 解析 freeform(----) 原子：逐个读子原子，遇到 data 且 name 是歌词字段则返回其文本 */
    private fun readMp4FreeformLyrics(raf: RandomAccessFile, start: Long, end: Long): String? {
        var p = start
        var name = ""
        while (p + 8 <= end) {
            raf.seek(p)
            val size = readInt32BE(raf)
            val type = ByteArray(4)
            raf.readFully(type)
            if (size < 8 || p + size > end) break
            val t = String(type, Charsets.ISO_8859_1)
            val body = p + 8
            val bodyLen = size - 8
            when (t) {
                "name" -> {
                    raf.seek(body + 4) // version(1)+flags(3)
                    name = String(readN(raf, (bodyLen - 4).coerceAtLeast(0)), Charsets.UTF_8).trim()
                }
                "data" -> {
                    if (isLyricFieldName(name) || name.contains("LYRICS", true)) {
                        raf.seek(body + 8)
                        val txt = readN(raf, (bodyLen - 8).coerceAtLeast(0))
                        decodeTextBytes(txt)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
            p += size
        }
        return null
    }

    private fun readN(raf: RandomAccessFile, n: Int): ByteArray {
        val b = ByteArray(n)
        raf.readFully(b)
        return b
    }

    /** 在 MP4 文件中按原子路径查找，返回目标原子的 body 范围（start, end） */
    private fun findMp4Atom(
        raf: RandomAccessFile,
        start: Long, end: Long, maxDepth: Int,
        vararg path: String
    ): Pair<Long, Long>? {
        if (path.isEmpty() || maxDepth < 0) return null
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            var size = readInt32BE(raf).toLong() and 0xFFFFFFFFL
            val type = ByteArray(4)
            raf.readFully(type)
            val typeStr = String(type, Charsets.ISO_8859_1)
            if (size == 1L) {
                // 64 位 size
                size = raf.readLong()
            }
            if (size < 8) break
            val bodyStart = pos + 8 + (if (size == 1L) 8 else 0)
            if (size > end - pos) break
            if (typeStr == path[0]) {
                if (path.size == 1) return bodyStart to (pos + size)
                // meta 原子有 4 字节 version/flags 头
                val childStart = if (typeStr == "meta") bodyStart + 4 else bodyStart
                return findMp4Atom(raf, childStart, pos + size, maxDepth - 1, *path.copyOfRange(1, path.size))
            }
            pos += size
        }
        return null
    }

    private fun readInt32BE(raf: RandomAccessFile): Int =
        ((raf.read() and 0xFF) shl 24) or ((raf.read() and 0xFF) shl 16) or
                ((raf.read() and 0xFF) shl 8) or (raf.read() and 0xFF)

    /** 智能解码文本字节：UTF-8 优先，含 BOM 时按 BOM 走 UTF-16 */
    private fun decodeTextBytes(b: ByteArray): String? {
        return runCatching {
            when {
                b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
                    String(b, 3, b.size - 3, Charsets.UTF_8)
                b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() ->
                    String(b, 2, b.size - 2, Charsets.UTF_16LE)
                b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() ->
                    String(b, 2, b.size - 2, Charsets.UTF_16BE)
                else -> String(b, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    // ===== OGG Vorbis Comment 解析 =====

    /** 读取 OGG/Opus 的 Vorbis comment 块中 LYRICS 字段 */
    private fun readOggLyrics(file: File): String? {
        if (!file.exists() || file.length() < 4) return null
        // 旧实现把前 256KB 当连续内存解析，遇到"comment packet 跨多个 Ogg 页"就崩：
        // 页与页之间插有 OggS 页头，(续段长度) 会读到页头字节，跳字段时算错位置、
        // 落进 base64 封面中间读出垃圾长度，随后的 LYRICS 字段永远轮不到被检查。
        // QQ 音乐的 ogg 常把 METADATA_BLOCK_PICTURE（内嵌封面，上百 KB）排在
        // LYRICS 之前，正是触发这一缺陷的典型。现在按 Ogg 分页规范重组 packet。
        val window = minOf(file.length(), 4L * 1024 * 1024).toInt()
        val buf = ByteArray(window)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(0)
            raf.readFully(buf)
        }
        val packet = extractVorbisCommentPacket(buf) ?: return null

        // Vorbis comment packet: [\\x03"vorbis" | "OpusTags"] + vendorLen + vendor + count + (len+entry)*
        var p = if (packet.size >= 8 && packet[1] == 'p'.code.toByte()) 8 else 7
        if (p + 4 > packet.size) return null
        val vendorLen = readInt32LE(packet, p)
        if (vendorLen < 0 || vendorLen > packet.size) return null
        p += 4 + vendorLen
        if (p + 4 > packet.size) return null
        val count = readInt32LE(packet, p)
        p += 4
        if (count < 0 || count > 10_000) return null
        repeat(count) {
            if (p + 4 > packet.size) return null
            val cLen = readInt32LE(packet, p)
            p += 4
            if (cLen <= 0 || cLen > packet.size || p + cLen > packet.size) return null
            val entry = String(packet, p, cLen, Charsets.UTF_8)
            p += cLen
            val eq = entry.indexOf('=')
            if (eq > 0 && isLyricFieldName(entry.substring(0, eq))) {
                val v = entry.substring(eq + 1).trim()
                if (v.isNotEmpty()) return v
            }
        }
        return null
    }

    /**
     * 按 Ogg 分页规范把 comment 页重组为完整 packet。
     *
     * Ogg 页结构：OggS(4) version(1) headerType(1) granule(8) serial(4) seq(4)
     *            crc(4) segCount(1) segTable(segCount) payload。
     * packet 由若干 segment 拼成，segment 值 255 表示"未完待续"、<255 表示 packet 结束；
     * 跨页时页头不参与 packet 内容。这里只扫到 comment packet 为止（通常是第 2 个 packet）。
     */
    private fun extractVorbisCommentPacket(buf: ByteArray): ByteArray? {
        var pos = 0
        var cur: java.io.ByteArrayOutputStream? = null
        while (pos + 27 <= buf.size) {
            // 页头校验：OggS
            if (buf[pos] != 'O'.code.toByte() || buf[pos + 1] != 'g'.code.toByte() ||
                buf[pos + 2] != 'g'.code.toByte() || buf[pos + 3] != 'S'.code.toByte()
            ) break
            val segCount = buf[pos + 26].toInt() and 0xFF
            if (pos + 27 + segCount > buf.size) break
            var p = pos + 27 + segCount   // payload 起点（跳过段表）
            var i = 0
            while (i < segCount) {
                val segLen = buf[pos + 27 + i].toInt() and 0xFF
                if (p + segLen > buf.size) return null
                if (cur == null) {
                    // packet 起始：判断是不是 comment 头（\x03vorbis / OpusTags）
                    if (segLen >= 7 && buf[p] == 0x03.toByte() &&
                        matches(buf, p + 1, "vorbis")
                    ) {
                        cur = java.io.ByteArrayOutputStream()
                    } else if (segLen >= 8 && matches(buf, p, "OpusTags")) {
                        cur = java.io.ByteArrayOutputStream()
                    } else {
                        // 非目标 packet：跳过本页剩余段（不跨页跟踪，audio 页另处理）
                        p += segLen
                        i++
                        continue
                    }
                }
                cur.write(buf, p, segLen)
                p += segLen
                i++
                if (segLen < 255) {
                    // packet 结束
                    val done = cur?.toByteArray()
                    if (done != null) return done
                    cur = null
                }
            }
            pos = p
        }
        // 读到窗口末尾仍未见完整 packet（极少见），返回已拼内容兜底
        return cur?.toByteArray()?.takeIf { it.isNotEmpty() }
    }

    private fun matches(b: ByteArray, off: Int, s: String): Boolean {
        if (off + s.length > b.size) return false
        for (i in s.indices) if (b[off + i] != s[i].code.toByte()) return false
        return true
    }

    private fun readInt32LE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF)) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    // ===== 通用工具 =====

    /** 读取 4 字节 syncsafe 整数 */
    private fun syncsafe(b: ByteArray, off: Int): Int {
        return ((b[off].toInt() and 0x7F) shl 21) or
                ((b[off + 1].toInt() and 0x7F) shl 14) or
                ((b[off + 2].toInt() and 0x7F) shl 7) or
                (b[off + 3].toInt() and 0x7F)
    }

    /**
     * 解析歌词文本：优先按 LRC 时间戳解析；若不含时间戳（纯文本歌词），
     * 按行均匀分配到整首歌时长，使滚动高亮仍然生效
     */
    fun parseText(rawIn: String, durationMs: Long = 0L): List<LyricLine> {
        // 去掉 BOM 与 UTF-16 残留的 NUL，否则首行 "\uFEFF[00:01.00]" 会被当成非时间戳行丢掉
        val raw = rawIn.replace("\uFEFF", "").replace("\u0000", "")
        // 6.9.10：`[offset:±ms]` 以前被当元信息直接丢掉。官方歌词里它是作者
        // 亲口标注的整体提前/延后量，丢掉的后果是"整首差半秒"——不致命但一直存在。
        val offset = parseOffsetTag(raw)
        val timed = parseDocument(raw, durationMs)
        if (timed.isNotEmpty()) {
            val cleaned = LyricDisplayFilter.clean(timed)
            return if (offset != 0L) LyricTimeFit.applyOffset(cleaned, offset) else cleaned
        }
        // 纯文本：均匀分配时间
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        // 时长还不知道时**不伪造时间轴**。旧代码这里退成"每秒一句"：
        // 一首 40 句的无时间戳歌词会在 40 秒内跑完一整首 5 分钟的歌，
        // 这就是「歌词飞得特别快、根本唱不到那儿」其中一种表现。
        // 返回空 = "还没准备好"；调用方的缓存键带 durationMs，
        // 时长到位后会自动重解析，不会把坏结果钉在缓存里。
        if (durationMs <= 0L) return emptyList()
        val step = durationMs / lines.size
        return LyricDisplayFilter.clean(lines.mapIndexed { i, t -> LyricLine(i * step, t) })
    }

    /**
     * 解析 `[offset:±毫秒]`。
     *
     * LRC 规范里正值表示歌词**提前**（时间戳整体减），所以这里取负号返回，
     * 交给 [LyricTimeFit.applyOffset] 做"加偏移"的统一处理。
     * 超过 ±30 秒的视为脏数据（有制作方把 offset 写成 Unix 时间戳的）。
     */
    private fun parseOffsetTag(raw: String): Long {
        val m = OFFSET_TAG.find(raw) ?: return 0L
        val v = m.groupValues.getOrNull(1)?.toLongOrNull() ?: return 0L
        if (v == 0L) return 0L
        return (-v).coerceIn(-30_000L, 30_000L)
    }

    private val OFFSET_TAG = Regex("(?im)^\\s*\\[\\s*offset\\s*:\\s*([+-]?\\d{1,7})\\s*]")

    /**
     * 按内容嗅探歌词格式，返回带时间轴的行；识别不出返回空。
     *
     * 支持：LRC / 增强 LRC、WebVTT、TTML、QRC、YRC、KRC（明文）。
     * 这些格式大多来自 QQ 音乐 / 酷狗 / 本地整理工具，统一进同一条时间轴。
     */
    private fun parseDocument(raw: String, durationMs: Long): List<LyricLine> {
        val head = raw.trimStart().take(512).lowercase()
        return when {
            head.startsWith("webvtt") || Regex("(?m)^\\s*\\d{1,2}:\\d{2}:\\d{2}[.,]\\d{3}\\s*-->").containsMatchIn(raw) ->
                parseVtt(raw)
            head.startsWith("<?xml") || head.contains("<tt") || head.contains("<ttml") ->
                parseTtml(raw)
            QRC_LINE.containsMatchIn(raw) || YRC_LINE.containsMatchIn(raw) || KRC_LINE.containsMatchIn(raw) ->
                parseMsTagged(raw)
            else -> parseRaw(raw)
        }
    }

    private val QRC_LINE = Regex("(?m)^\\s*\\[\\d{1,7},\\d{1,7}]")
    private val YRC_LINE = Regex("(?m)^\\s*\\{\"t\":\\d+")
    private val KRC_LINE = Regex("(?m)^\\s*\\[\\d{1,7},\\d{1,7}]<\\d+,\\d+,\\d+>")

    /**
     * 根据音频文件路径查找同目录同名歌词文件，按优先级依次尝试：
     * .lrc / .vtt / .ttml / .qrc / .yrc / .krc
     * /path/song.mp3 -> /path/song.lrc
     * 找不到返回 null
     */
    fun findLyricFile(audioPath: String?): File? = findSidecar(audioPath, listOf(""), skipBase = true)

    /**
     * 找同名旁挂文件：
     * - [suffixes] 为空字符串时找主歌词（所有支持格式）；
     * - 传 "t"/"zh" 时找 `歌名.t.lrc`、`歌名.zh.lrc` 这类翻译轨。
     */
    private fun findSidecar(
        audioPath: String?,
        suffixes: List<String>,
        skipBase: Boolean = false
    ): File? {
        if (audioPath.isNullOrBlank()) return null
        val f = File(audioPath)
        if (f.isDirectory) return null
        val dir = f.parentFile ?: return null
        val base = f.nameWithoutExtension
        val exts = if (skipBase) LYRIC_EXTS else LYRIC_EXTS
        if (suffixes == listOf("")) {
            for (ext in exts) {
                val exact = File(dir, "$base$ext")
                if (exact.isFile) return exact
            }
        } else {
            for (suffix in suffixes) {
                for (ext in exts) {
                    val exact = File(dir, "$base.$suffix$ext")
                    if (exact.isFile) return exact
                }
            }
        }
        // 大小写不同（.LRC / .Lrc）或 "歌名.mp3.lrc" 这种写法：列一次目录兜底
        val names = dir.list() ?: return null
        val wanted = if (suffixes == listOf("")) {
            exts.flatMap { listOf("$base$it", f.name + it) }
        } else {
            suffixes.flatMap { s -> exts.flatMap { listOf("$base.$s$it", "${f.name}.$s$it") } }
        }
        val hit = names.firstOrNull { name -> wanted.any { it.equals(name, true) } }
        if (hit != null) return File(dir, hit).takeIf { it.isFile }

        // 多源歌词模糊匹配：文件名常带「01 - 」「[320K]」「(Live)」「feat. XX」等前后缀，
        // 严格同名会漏。归一化掉编号/括号/符号后，只接受唯一且足够长的前缀匹配，
        // 避免把《Love》错配到《Lovely》。
        val baseNorm = normalizeLyricStem(base)
        if (baseNorm.length < 2) return null
        val suffixTag = if (suffixes == listOf("")) "" else suffixes.firstOrNull().orEmpty()
        val fuzzy = names.mapNotNull { name ->
            val dot = name.lastIndexOf('.')
            if (dot <= 0) return@mapNotNull null
            val ext = name.substring(dot).lowercase()
            if (ext !in LYRIC_EXTS) return@mapNotNull null
            val stem = name.substring(0, dot)
            if (suffixTag.isNotEmpty() && !stem.endsWith(".$suffixTag", ignoreCase = true)) {
                return@mapNotNull null
            }
            val cleaned = if (suffixTag.isNotEmpty()) {
                stem.dropLast(suffixTag.length + 1)
            } else stem
            val norm = normalizeLyricStem(cleaned)
            if (norm.length < 2) return@mapNotNull null
            val related = norm == baseNorm ||
                (norm.length >= 4 && baseNorm.startsWith(norm)) ||
                (baseNorm.length >= 4 && norm.startsWith(baseNorm))
            if (related) name to norm.length else null
        }.sortedByDescending { it.second }
        // 只有唯一候选（或最长候选明显更长）时才采用，宁可没词也不要错词。
        val best = fuzzy.firstOrNull() ?: return null
        val second = fuzzy.getOrNull(1)
        if (second != null && second.second == best.second) return null
        return File(dir, best.first).takeIf { it.isFile }
    }

    /** 歌词文件名归一化：去编号、括号补充说明、常见音质/歌词后缀与所有标点。 */
    private fun normalizeLyricStem(raw: String): String {
        var s = raw.lowercase()
        // 开头的音轨号：01 - / 01. / 01_ / [01]
        s = s.replace(Regex("^\\s*(\\[\\d{1,3}\\]|\\d{1,3}\\s*[-._)])\\s*"), "")
        // 括号里的版本/音质信息
        s = s.replace(Regex("[（(\\[【].*?[)）\\]】]"), " ")
        // feat / ft / remix / live 之类的尾巴，保留主标题
        s = s.replace(Regex("\\b(feat|ft|featuring|remix|live|cover|inst|instrumental)\\b.*$"), " ")
        // 常见歌词/音质标记
        s = s.replace(Regex("\\b(official|lyrics?|hq|hires|320k|flac)\\b"), " ")
        return s.filter { it.isLetterOrDigit() }
    }

    /** 支持的外挂歌词扩展名（小写，含点） */
    private val LYRIC_EXTS = listOf(".lrc", ".vtt", ".ttml", ".qrc", ".yrc", ".krc")

    /** 读取并解析歌词文件（自动识别 LRC / VTT / TTML / QRC / YRC / KRC） */
    fun parse(file: File): List<LyricLine> {
        val text = runCatching { decodeLyricFile(file.readBytes()) }.getOrNull() ?: return emptyList()
        return parseText(text, 0L)
    }

    // ==================== 其他歌词格式 ====================

    /** WebVTT：cue 的 start 时间作为行时间，正文去掉样式标签与实体 */
    private fun parseVtt(raw: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        val blocks = raw.replace("\r\n", "\n").split(Regex("\\n\\s*\\n"))
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            val cueIdx = lines.indexOfFirst { it.contains("-->") }
            if (cueIdx < 0) continue
            val times = lines[cueIdx].split("-->")
            val start = parseClock(times.getOrNull(0)?.trim() ?: continue) ?: continue
            val text = lines.drop(cueIdx + 1).joinToString("\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"")
                .trim()
            if (text.isNotEmpty()) out.add(LyricLine(start, text))
        }
        return out.sortedBy { it.timeMs }
    }

    /** TTML / Apple Music 逐句歌词：<p begin="12.5s">…</p> */
    private fun parseTtml(raw: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        val regex = Regex("<p\\b([^>]*)>(.*?)</p>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (m in regex.findAll(raw)) {
            val attrs = m.groupValues[1]
            val begin = Regex("\\bbegin\\s*=\\s*\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                ?: Regex("\\bbegin\\s*=\\s*'([^']+)'").find(attrs)?.groupValues?.get(1)
                ?: continue
            val start = parseClock(begin) ?: continue
            val text = m.groupValues[2]
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .trim()
            if (text.isNotEmpty()) out.add(LyricLine(start, text))
        }
        return out.sortedBy { it.timeMs }
    }

    /**
     * QRC / YRC / KRC（明文）逐字歌词。
     *
     * 行头统一是 `[开始ms,时长ms]`；词级时间有三种常见写法：
     *  - KRC: `<偏移ms,时长ms,0>词`（相对行首）
     *  - YRC: `(绝对开始ms,时长ms,0)词`
     *  - QRC: `[绝对开始ms,时长ms]词`（部分导出器）
     * 没有词级标签时退化为普通行歌词。
     */
    private fun parseMsTagged(raw: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        val lineHead = Regex("^\\s*\\[(\\d{1,7}),(\\d{1,7})]")
        val wordTag = Regex("[<(\\[]\\s*(\\d{1,7})\\s*,\\s*(\\d{1,7})(?:\\s*,\\s*\\d+)?\\s*[>)\\]]")
        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("{")) {
                // YRC 的逐行 JSON：{"t":123,"c":[{"tx":"词"},...]}
                if (line.startsWith("{")) parseYrcJson(line)?.let { out.add(it) }
                continue
            }
            val head = lineHead.find(line)
            if (head == null) {
                // 混合文档必须逐行降级处理：网易云 /api/song/lyric/v1 的 lrc.lyric
                // 就是「开头几行 YRC-JSON 制作信息 + 后面整篇普通 [mm:ss.xx] 正文」
                // （实测 102 行里只有 16 行是 JSON，其余 86 行全是普通 LRC）。
                // 旧实现在这里 `?: continue`，把整篇正文全丢了——用户看到的
                // 「点开只剩作词/作曲，主歌词没了」就是这一行造成的。
                parseLine(line)?.let { out.add(it) }
                continue
            }
            val start = head.groupValues[1].toLongOrNull() ?: continue
            // 行头第二个数是本行时长（YRC/QRC/KRC 都是这个语义）。存下来，
            // 展示层据此决定"最后一个字什么时候算唱完"，而不是拖到下一行起点。
            val spanMs = head.groupValues[2].toLongOrNull()?.coerceIn(0L, 600_000L) ?: 0L
            val rest = line.substring(head.range.last + 1)
            val tags = wordTag.findAll(rest).toList()
            if (tags.isEmpty()) {
                val text = rest.trim()
                if (text.isNotEmpty()) out.add(LyricLine(start, text, durationMs = spanMs))
                continue
            }
            val words = mutableListOf<LyricWord>()
            val text = StringBuilder()
            // 6.9.10 治根：逐字标签是**写在词前面**的：
            //   [16210,2860](16210,340,0)我(16550,340,0)曾(16890,340,0)经
            // 旧实现取的是「标签**之前**的文本」并把时间记成**这个标签**的时间，
            // 于是每个字都拿到了下一个字的时间（"我"被记成 16550ms），
            // 整行的逐字点亮稳定错后一个字 —— 用户听到的"有的字抢拍、有的字拖半拍"
            // 就是这一处。现在按「标签之后的文本属于该标签」切分，字与时间一一对应。
            for ((i, tag) in tags.withIndex()) {
                val segStart = tag.range.last + 1
                val segEnd = tags.getOrNull(i + 1)?.range?.first ?: rest.length
                if (segStart >= segEnd) continue
                val seg = rest.substring(segStart, segEnd)
                if (seg.isBlank()) continue
                val rawStart = tag.groupValues[1].toLongOrNull() ?: start
                // KRC 相对行首，YRC/QRC 是绝对毫秒；小于行首的一律按相对值处理
                val wordStart = if (rawStart < start) start + rawStart else rawStart
                // 6.9.13：第二个数就是**这个字的演唱时长**，直接落地。
                // 大厂逐字之所以准，就是因为每个字都自带 duration，不用相邻字去推。
                val wordDur = tag.groupValues[2].toLongOrNull()?.coerceIn(0L, 30_000L) ?: 0L
                words.add(
                    LyricWord(
                        wordStart,
                        seg,
                        if (wordDur > 0L) wordStart + wordDur else 0L
                    )
                )
                text.append(seg)
            }
            if (text.isNotBlank()) {
                out.add(
                    LyricLine(
                        start,
                        text.toString().trim(),
                        if (words.size > 1) words else emptyList(),
                        durationMs = spanMs
                    )
                )
            }
        }
        return dedupeSameTime(out.sortedBy { it.timeMs })
    }

    /**
     * 同一时间戳重复出现同一句时只留一条。混合文档（YRC + LRC 拼在一起）
     * 或导出工具重复写行头时会撞车，不去重的话整句会显示两遍。
     */
    private fun dedupeSameTime(lines: List<LyricLine>): List<LyricLine> {
        if (lines.size < 2) return lines
        var prevTime = Long.MIN_VALUE
        var prevText: String? = null
        val keep = ArrayList<LyricLine>(lines.size)
        for (l in lines) {
            if (l.timeMs == prevTime && l.text == prevText) continue
            prevTime = l.timeMs; prevText = l.text
            keep.add(l)
        }
        return keep
    }

    // ---------- 双轨对齐（lrc 分行 + yrc 时间轴） ----------

    /** 去掉标点/空白后的比对键。标点在不同轨道间经常不一致（全角半角、有无逗号）。 */
    private fun alignKey(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text.lowercase()) {
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }

    /** 词级流里的一个原子：原文 + 比对键 + 开始时间。 */
    private class WordAtom(val orig: String, val key: String, val startMs: Long, val endMs: Long = 0L)

    /**
     * 把 lrc 轨的分行和 yrc 轨的时间轴合成一条：
     * 结果保留 lrc 的**句子切分**（用户看到的断句不变），
     * 但每句的开始时间换成 yrc 里同一句第一个字的真实起唱时间，
     * 并把落在这句范围内的词级时间戳搬过来（原来只有行时间的句子从此也能逐字点亮）。
     *
     * 为什么必须合：网易云 `/api/song/lyric/v1` 的两条轨是**两套时间轴**。
     * 实测 id=287398：同一句 lrc 标 `[00:31.420]`、yrc 标 `(33480,...)`，差 2.06 秒；
     * id=1230006 反向差 3.76 秒。旧实现按"行数够不够 60%"二选一，
     * 而 yrc 习惯把两三个短句并成一行（86 行 → 44 行），于是这种歌被判"不完整"、
     * 丢掉逐字轨改用早 2 秒的 lrc —— 用户听到的"歌词特别快、跟唱不上"就是这里。
     *
     * 匹配是**顺序单遍**扫描（cursor 只前进）：副歌会整段重复，
     * 全局 indexOf 会把第二段副歌对到第一段上，时间轴当场倒退几十秒。
     */
    private fun alignWordTimings(
        plain: List<LyricLine>,
        word: List<LyricLine>,
        durationMs: Long = 0L
    ): Pair<List<LyricLine>, Int> {
        if (plain.isEmpty() || word.isEmpty()) return plain to 0

        // 1) 展平词级流：没有词级标签的行整体当一个原子，用行时间当起点。
        val atoms = ArrayList<WordAtom>(word.sumOf { it.words.size.coerceAtLeast(1) })
        val sb = StringBuilder()
        for (w in word) {
            if (w.words.size > 1) {
                for (x in w.words) {
                    val k = alignKey(x.text)
                    if (k.isEmpty()) continue
                    atoms.add(WordAtom(x.text, k, x.startMs, x.endMs))
                    sb.append(k)
                }
            } else {
                val k = alignKey(w.text)
                if (k.isEmpty()) continue
                atoms.add(WordAtom(w.text, k, w.timeMs))
                sb.append(k)
            }
        }
        val haystack = sb.toString()
        if (haystack.isEmpty()) return plain to 0
        // 每个字符属于哪个原子
        val owner = IntArray(haystack.length)
        run { var p = 0; for (i in atoms.indices) { val n = atoms[i].key.length; repeat(n) { owner[p++] = i } } }

        // 2) 第一遍只做匹配，记录 [lrc 行号, 首原子, 末原子]。
        //    顺序单遍扫描（cursor 只前进）：副歌整段重复，全局 indexOf 会把
        //    第二段副歌对到第一段上，时间轴当场倒退几十秒。
        val hits = ArrayList<IntArray>(plain.size)
        var cursor = 0
        for (pi in plain.indices) {
            val needle = alignKey(plain[pi].text)
            if (needle.isEmpty()) continue
            val at = if (cursor in 0..haystack.length) haystack.indexOf(needle, cursor) else -1
            if (at < 0) continue
            val first = owner[at]
            val last = owner[(at + needle.length - 1).coerceAtMost(haystack.length - 1)]
            hits.add(intArrayOf(pi, first, last))
            cursor = at + needle.length
        }
        if (hits.isEmpty()) return plain to 0

        // 3) 整体偏移估计。6.9.10 治根点：
        //    旧实现把每句时间无条件换成 yrc 的绝对时刻。但 lrc 与 yrc 是两套时间轴
        //    （实测同一句相差 2.06s / 3.76s，见类注释），音源版本一变，整首就被平移，
        //    听感就是"有的歌快一大截、有的慢一大截、同一首越唱越偏"。
        //    这里先看两条轨的**差值中位数**（中位数不会被漏标行带偏），
        //    偏移小 = 同源，保留 yrc 绝对时间（最精确）；
        //    偏移大 = 版本不同，改用 lrc 句首当绝对锚点，只借 yrc 的句内节奏。
        val work = plain
        val deltas = LongArray(hits.size) {
            atoms[hits[it][1]].startMs - work[hits[it][0]].timeMs
        }
        deltas.sort()
        val median = deltas[deltas.size / 2]
        // lrc 自己是不是更靠谱的那条轨：收尾位置更接近"人声结束"(约 93%)。
        // lrc 自身严重失真时不拿它当锚，免得把对的 yrc 改坏。
        val lrcTailOk = if (durationMs > 0) {
            val lrcTail = plain.last().timeMs.toFloat() / durationMs
            val yrcTail = word.last().timeMs.toFloat() / durationMs
            kotlin.math.abs(lrcTail - 0.93f) <= kotlin.math.abs(yrcTail - 0.93f) + 0.02f
        } else true
        val rebase = kotlin.math.abs(median) > 600L && lrcTailOk

        val out = ArrayList<LyricLine>(plain.size)
        var hitPos = 0
        var prevStart = Long.MIN_VALUE
        var matched = 0
        for (pi in plain.indices) {
            val p = plain[pi]
            val hit = if (hitPos < hits.size && hits[hitPos][0] == pi) hits[hitPos++] else null
            if (hit == null) {
                // 这一句在逐字轨里找不到（和声、旁白、yrc 漏标的行）：保留 lrc 原时间，
                // 不移动 cursor —— 后面的句子还要往下对。
                val t = maxOf(p.timeMs, prevStart)
                out.add(if (t == p.timeMs) p else p.copy(timeMs = t))
                prevStart = t
                continue
            }
            val firstAtomStart = atoms[hit[1]].startMs
            val nextTime = plain.getOrNull(pi + 1)?.timeMs ?: Long.MAX_VALUE
            val lineStart = if (rebase) maxOf(p.timeMs, prevStart) else maxOf(firstAtomStart, prevStart)
            val shift = if (rebase) lineStart - firstAtomStart else 0L
            // 末字的结束时间 = 流里下一个原子的起点（通常是下一句第一个字），
            // 这比"下一行开始时间"更贴近真实收声，空拍不会被算进咬字。
            val words = if (hit[2] > hit[1]) {
                val cap = if (nextTime == Long.MAX_VALUE) Long.MAX_VALUE else nextTime - 1L
                (hit[1]..hit[2]).map { i ->
                    val t = (atoms[i].startMs + shift).coerceAtLeast(lineStart)
                    // 6.9.13：逐字轨给的**真实时长**跟着一起平移，不要退回"下一字起点"。
                    // 平移量 shift 对整条轨是常量，所以字内时长保持不变——这正是
                    // 「整首换版本、但句内节奏是准的」场景下最该保住的东西。
                    val realEnd = if (atoms[i].endMs > atoms[i].startMs) {
                        atoms[i].endMs + shift
                    } else 0L
                    val e = if (cap == Long.MAX_VALUE) realEnd else realEnd.coerceAtMost(cap)
                    LyricWord(
                        if (cap == Long.MAX_VALUE) t else t.coerceAtMost(cap),
                        atoms[i].orig,
                        if (e > t) e else 0L
                    )
                }
            } else {
                // 单字短句：至少让它有一个属于自己的起点，逐字层退回整行高亮即可
                p.words
            }
            val tailEnd = atoms.getOrNull(hit[2] + 1)?.startMs?.plus(shift) ?: 0L
            val span = if (tailEnd > lineStart) tailEnd - lineStart else 0L
            // 顺序单调保护：混进一条比前一句更早的时间会让 activeIndex 的二分直接失效
            // （读到乱序列表会跳行/卡行），宁可让这一句跟上一句同刻。
            out.add(p.copy(timeMs = lineStart, words = words, durationMs = span))
            prevStart = lineStart
            matched++
        }
        return out to matched
    }

    /**
     * 本行结束时间。优先级：
     *  ① 增强标签/对齐算出来的真实时长 → 最准，句间空拍不会被算进咬字；
     *  ② 下一行开始时间；
     *  ③ 歌曲时长；
     *  ④ 行起点 +5s。
     * 展示层（扫光进度、余辉、末字补齐）全部走这里，避免各处自己 +1 取下一行。
     */
    fun lineEnd(lines: List<LyricLine>, index: Int, durationMs: Long = 0L): Long {
        val line = lines.getOrNull(index) ?: return 0L
        val nextStart = lines.getOrNull(index + 1)?.timeMs
        val declared = if (line.durationMs > 0L) line.timeMs + line.durationMs else 0L
        return when {
            declared > 0L && (nextStart == null || declared <= nextStart + 250L) -> declared
            nextStart != null && nextStart > line.timeMs -> nextStart
            durationMs > line.timeMs -> durationMs
            else -> line.timeMs + 5_000L
        }
    }

    /** YRC 的 JSON 行：t = 行开始毫秒，c[].tx = 词 */
    private fun parseYrcJson(line: String): LyricLine? = runCatching {
        val o = org.json.JSONObject(line)
        val t = o.optLong("t", -1L)
        val arr = o.optJSONArray("c") ?: return null
        val sb = StringBuilder()
        val words = ArrayList<LyricWord>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val tx = c.optString("tx", "")
            if (tx.isEmpty()) continue
            val ms = c.optLong("t", t).let { if (it < t) t + it else it }
            words.add(LyricWord(ms, tx))
            sb.append(tx)
        }
        if (t < 0 || sb.isEmpty()) null else
            LyricLine(t, sb.toString(), if (words.size > 1) words else emptyList())
    }.getOrNull()

    /** VTT/TTML 时钟：hh:mm:ss.mmm / mm:ss.mmm / 12.5s / 1:02.500 */
    private fun parseClock(raw: String): Long? {
        val t = raw.trim().lowercase()
        if (t.isEmpty()) return null
        Regex("^([\\d.]+)\\s*(ms|s|m)$").find(t)?.let { m ->
            val v = m.groupValues[1].toDoubleOrNull() ?: return null
            return when (m.groupValues[2]) {
                "ms" -> v.toLong()
                "m" -> (v * 60_000).toLong()
                else -> (v * 1000).toLong()
            }
        }
        val clean = t.replace(',', '.')
        val parts = clean.split(':')
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val s = parts[2].toDoubleOrNull() ?: return null
                h * 3_600_000 + m * 60_000 + (s * 1000).toLong()
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val s = parts[1].toDoubleOrNull() ?: return null
                m * 60_000 + (s * 1000).toLong()
            }
            else -> (clean.toDoubleOrNull() ?: return null).let { (it * 1000).toLong() }
        }
    }

    /**
     * 把翻译/音译轨按时间对齐合并进主歌词。
     * 容差 1.2 秒：不同平台导出时会有几十到几百毫秒偏差，不能要求时间戳完全相等。
     */
    private fun mergeExtraLines(
        main: List<LyricLine>,
        translation: List<LyricLine>,
        romanization: List<LyricLine>
    ): List<LyricLine> {
        if (main.isEmpty() || (translation.isEmpty() && romanization.isEmpty())) return main
        fun nearest(extra: List<LyricLine>, t: Long): String? {
            if (extra.isEmpty()) return null
            var best: LyricLine? = null
            var bestDiff = Long.MAX_VALUE
            for (e in extra) {
                val d = kotlin.math.abs(e.timeMs - t)
                if (d < bestDiff) { bestDiff = d; best = e }
            }
            return if (best != null && bestDiff <= 1200L) best.text.takeIf { it.isNotBlank() } else null
        }
        return main.map { line ->
            line.copy(
                translation = nearest(translation, line.timeMs) ?: line.translation,
                romanization = nearest(romanization, line.timeMs) ?: line.romanization
            )
        }
    }

    /** 纯音乐判定：只有一句“纯音乐/请欣赏/Instrumental”占位，或完全没有歌词 */
    fun isInstrumental(lines: List<LyricLine>): Boolean {
        if (lines.isEmpty()) return true
        if (lines.size > 2) return false
        val joined = lines.joinToString(" ") { it.text }.lowercase()
        return Regex("纯音乐|请欣赏|instrumental|无歌词|暂无歌词|solo|music only").containsMatchIn(joined)
    }

    /**
     * 导出增强型 LRC（词级时间 `<mm:ss.xx>`），便于把应用内校正后的歌词带到别的播放器。
     * 没有词级时间的行导出普通 LRC。
     */
    fun exportEnhancedLrc(lines: List<LyricLine>): String {
        fun ts(ms: Long): String {
            val m = ms / 60_000
            val s = (ms % 60_000) / 1000
            val cs = (ms % 1000) / 10
            return String.format("%02d:%02d.%02d", m, s, cs)
        }
        val sb = StringBuilder()
        for (line in lines) {
            sb.append('[').append(ts(line.timeMs)).append(']')
            if (line.words.isNotEmpty()) {
                for (w in line.words) sb.append('<').append(ts(w.startMs)).append('>').append(w.text)
            } else {
                sb.append(line.text)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 外挂 lrc 编码识别：BOM（UTF-8/UTF-16LE/BE）→ 严格 UTF-8 → GB18030/Shift_JIS。
     * 网上下载和老播放器导出的 lrc 大量是 GBK/ANSI，只按 UTF-8 读会整篇乱码。
     */
    private fun decodeLyricFile(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte())
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        strictDecode(b, Charsets.UTF_8)?.let { return it }
        strictDecode(b, GB18030)?.let { if (cjkRatio(it) >= 0.5) return it }
        SHIFT_JIS?.let { cs -> strictDecode(b, cs)?.let { return it } }
        return String(b, GB18030)
    }

    /** 从字符串解析 LRC（多个时间戳支持） */
    private fun parseRaw(raw: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        raw.lines().forEach { l ->
            parseLine(l)?.let { lines.add(it) }
        }
        return lines.sortedBy { it.timeMs }
    }

    /**
     * 解析单行 LRC：支持多个时间戳（如 [00:12.34][01:02.34]歌词）
     * 返回解析出的歌词行；非时间戳行（元信息/纯文本）返回 null
     */
    private fun parseLine(raw: String): LyricLine? {
        val line = raw.trim().trimStart('\uFEFF')
        if (line.isEmpty()) return null
        // 跳过元信息标签 [ti:][ar:][al:][by:][offset:] 等
        if (line.startsWith("[") && !line.contains("]")) return null

        val times = mutableListOf<Long>()
        var rest = line
        while (true) {
            val open = rest.indexOf('[')
            val close = rest.indexOf(']')
            if (open == 0 && close > open) {
                val tag = rest.substring(open + 1, close)
                parseTime(tag)?.let { times.add(it) }
                rest = rest.substring(close + 1)
            } else {
                break
            }
        }
        // 行内紧跟词尾的方括号时间戳：网易云/QQ 逐字歌词导出格式。
        // 旧实现只认 <mm:ss.xx> 尖括号词级标签，这种行会带着原始 [时间] 当正文
        // 显示（用户截图里"监[00:03.141]制…"就是），或整行解析失败退成纯文本。
        splitInlineStamps(rest)?.let { segs ->
            val text = segs.joinToString("") { it.first }
            if (text.isBlank()) return null
            // 行首有时间标签时它是首字的开始；没有就用第一个时间戳兜底。
            val lineStart = times.firstOrNull() ?: segs.first().second
            val words = ArrayList<LyricWord>(segs.size)
            var next = lineStart
            for ((txt, endMs) in segs) {
                if (txt.isEmpty()) continue
                // 每个词后面紧跟的方括号时间就是它的**结束**边界，原样存下来。
                words.add(LyricWord(next, txt, if (endMs > next) endMs else 0L))
                next = endMs
            }
            return LyricLine(lineStart, text, if (words.size > 1) words else emptyList())
        }

        if (times.isEmpty()) return null
        // rest 里可能含增强型 LRC 的词级标签 <mm:ss.xx>，先拆词
        val words = parseWords(rest, times.first())
        val text = stripWordTags(rest).trim()
        if (text.isEmpty()) return null
        return LyricLine(times.first(), text, words)
    }

    /**
     * 拆"字[结束时间]字[结束时间]…"格式：每个紧跟词尾的方括号时间是该词的结束边界
     * （行首标签是首字的开始）。兼容三种写法：
     *   [mm:ss.xxx] / [mm:ss.xxx,mm:ss.xxx]（带时长，取开始段的互补端）/ [mm:ss:xxx]
     * 一个可解析的时间戳都没有时返回 null（不是逐字行，走普通解析）。
     * 无法解析的方括号（如正文里的备注）原样保留进文本。
     */
    private fun splitInlineStamps(rest: String): List<Pair<String, Long>>? {
        if (!rest.contains('[') || !rest.contains(']')) return null
        val segs = ArrayList<Pair<String, Long>>()
        val sb = StringBuilder()
        var i = 0
        var stamps = 0
        while (i < rest.length) {
            if (rest[i] == '[') {
                val close = rest.indexOf(']', i)
                if (close > i) {
                    // KRC 的 [开始,时长] 写法：只取逗号前的时间部分
                    val t = parseTime(rest.substring(i + 1, close).substringBefore(','))
                    if (t != null) {
                        segs.add(sb.toString() to t)
                        sb.setLength(0)
                        stamps++
                        i = close + 1
                        continue
                    }
                }
            }
            sb.append(rest[i])
            i++
        }
        if (stamps == 0) return null
        // 最后一个时间戳后还有残留文字：给 300ms 估计时长兜底，保证扫光能走完
        if (sb.isNotEmpty()) segs.add(sb.toString() to (segs.last().second + 300L))
        return segs
    }

    /**
     * 解析增强型 LRC 的词级时间标签：
     *   <00:12.34>今<00:12.80>天<00:13.10>...
     * 每个 <> 标签是紧随其后那段文字的开始时间。若行首没有标签，
     * 用行时间 [lineStartMs] 作为第一段的起点。
     * 行内没有任何 <> 标签时返回空列表（表示无词级信息）。
     */
    private fun parseWords(raw: String, lineStartMs: Long): List<LyricWord> {
        if (raw.indexOf('<') < 0) return emptyList()
        val words = mutableListOf<LyricWord>()
        var i = 0
        var curStart = lineStartMs
        val sb = StringBuilder()
        fun flush(start: Long, end: Long) {
            val t = sb.toString()
            if (t.isNotEmpty()) words.add(LyricWord(start, t, if (end > start) end else 0L))
            sb.setLength(0)
        }
        while (i < raw.length) {
            val c = raw[i]
            if (c == '<') {
                val close = raw.indexOf('>', i)
                if (close > i) {
                    val tag = raw.substring(i + 1, close)
                    val t = parseTime(tag)
                    if (t != null) {
                        // 结束上一段（用它的开始时间与这个标签时间），开启新段
                        flush(curStart, t)
                        curStart = t
                        i = close + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        flush(curStart, 0L)
        // 只有一段且和整行等价时没意义，直接判定为无词级
        return if (words.size <= 1) emptyList() else words
    }

    /** 去掉行内的 <mm:ss.xx> 词级标签，得到纯文本 */
    private fun stripWordTags(raw: String): String {
        if (raw.indexOf('<') < 0) return raw
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '<') {
                val close = raw.indexOf('>', i)
                if (close > i && parseTime(raw.substring(i + 1, close)) != null) {
                    i = close + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** 解析 [mm:ss.fff] / [mm:ss.ff] / [mm:ss] / [mm:ss:ff] 时间戳 */
    private fun parseTime(tag: String): Long? {
        val t = tag.trim()
        if (t.isEmpty()) return null
        // 先分出小数部分（'.' 之后），按位数决定单位——不能按数值大小判断：
        // QQ 音乐用 3 位毫秒 [00:04.050]，".050" 是 50ms 而非 500ms。
        val dot = t.indexOf('.')
        val frac = if (dot >= 0) t.substring(dot + 1) else ""
        val head = if (dot >= 0) t.substring(0, dot) else t
        val parts = head.split(':')
        if (parts.size < 2) return null
        val m = parts[0].toLongOrNull() ?: return null
        val s = parts[1].toLongOrNull() ?: return null
        var ms = m * 60_000 + s * 1000
        when {
            // [mm:ss:xx] 百分秒写法：小数用冒号分隔
            parts.size >= 3 -> {
                val p3 = parts[2].toLongOrNull() ?: return null
                ms += if (p3 < 100) p3 * 10 else p3
            }
            frac.isNotEmpty() -> {
                val fv = frac.toLongOrNull() ?: return null
                // 位数即精度：1 位=十分之一秒，2 位=百分秒，3 位及以上=毫秒
                ms += when (frac.length) {
                    1 -> fv * 100
                    2 -> fv * 10
                    else -> fv
                }
            }
        }
        return ms
    }

    /** 取指定时间点应高亮的歌词行下标（二分） */
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /**
     * 逐字点亮进度：给定某行和当前播放时间，返回该行已唱到的字符数（0..text.length）。
     * 用词级时间在相邻两词之间做线性插值，让高亮平滑扫过而不是一词一跳。
     * 无词级信息时返回 -1，调用方退回整行高亮。
     */
    fun wordProgressChars(line: LyricLine, positionMs: Long): Int {
        val f = wordProgressCharsF(line, positionMs)
        return if (f < 0f) -1 else f.toInt()
    }
    /**
     * 逐字点亮进度（浮点版）：返回已唱到的字符数（0f..text.length，连续小数）。
     * 相比取整版，小数部分让扫光在词内继续平滑推进，配合 glyph 级像素定位可做到
     * 光带精确停在正在咬的那个字上，而不是按字符数比例粗略铺。
     * 无词级信息时返回 -1f。
     *
     * [lineEndMs] 为下一行开始时间（或曲末），用于给最后一个词一个合理的结束时间。
     * 不传（<=0）时按最后一词平均字时长兜底——否则最后一词的 end 是 Long.MAX_VALUE，
     * 插值比例恒为 0，最后半个字永远扫不完，卡在半亮再直接跳行。
     */
    fun wordProgressCharsF(line: LyricLine, positionMs: Long, lineEndMs: Long = -1L): Float {
        val words = line.words
        if (words.isEmpty()) return -1f
        // 累计每个词结束时的字符数
        var chars = 0
        for (i in words.indices) {
            val w = words[i]
            val start = w.startMs
            val end = if (i + 1 < words.size) {
                words[i + 1].startMs
            } else {
                // 最后一个词：优先用行尾时间；否则按其它词的平均时长估算，最少 300ms
                if (lineEndMs > start) lineEndMs
                else {
                    val avg = if (i > 0) (start - words.first().startMs) / i else 300L
                    start + avg.coerceAtLeast(300L)
                }
            }
            if (positionMs < start) return chars.toFloat()
            if (positionMs < end) {
                // 词内插值（保留小数，扫光在词内连续推进）
                val span = (end - start).coerceAtLeast(1)
                val frac = ((positionMs - start).toFloat() / span).coerceIn(0f, 1f)
                return chars + w.text.length * frac
            }
            chars += w.text.length
        }
        return chars.toFloat()
    }
}
