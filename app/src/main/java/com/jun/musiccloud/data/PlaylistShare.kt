package com.jun.musiccloud.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 口令里的一首歌。
 *
 * 刻意只带「能跨账号认歌」的最小字段：
 *  - [id] 是**网易云歌曲 id**（不是本地 stableId）。有 id 就能直接构造在线 Song，
 *    不必联网搜索，也绕开了"歌手名写法不一样"这一大类匹配失败；
 *  - 本地 SAF 歌曲没有网易云 id，写空串，导入端退化成按 title+artist 搜索匹配；
 *  - album / durationMs 只用来**加分与扣分**（同名翻唱、Live/Remix 靠时长区分），
 *    不参与硬条件，缺了也能匹配。
 */
data class ShareTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
)

/** 一条口令解开后的内容（分卷时 [part]/[parts] 用来告诉导入端"这只是第几卷"） */
data class SharePayload(
    val name: String,
    val createdAt: Long,
    val tracks: List<ShareTrack>,
    val part: Int = 1,
    val parts: Int = 1
) {
    /** 界面上显示的名字：分卷口令自带「(2/3)」尾巴，免得用户以为只导了一半 */
    val displayName: String
        get() = if (parts > 1) "$name（$part/$parts）" else name
}

/**
 * 歌单分享口令（Jun 码）：编解码 + 导入匹配。
 *
 * 【为什么要自己造编码，而不是复用网易云/QQ 的歌单链接】
 * 用户要的是"我听了很久的歌，分享给别人"。这些歌只存在于**本机**：本地 SAF 曲库、
 * 自己建的本地歌单、本地"我喜欢"。它们没有对应的云端歌单，也就没有链接可分享——
 * 挂服务器建歌单又违背"这一轮不上传"的要求。所以只能把曲目本身编码进剪贴板文本，
 * 靠聊天软件当载体传出去。
 *
 * 【格式】`JUNPLAY<版本>.<6 位校验>.<base64url(deflate(JSON))>`
 *  - 纯 ASCII、单行、无空格：能直接粘进微信 / QQ / 短信，不被自动加链接预览打断；
 *  - 前缀 `JUNPLAY` 一眼区别于 QQ 音乐 / 网易云那种 `https://…` 链接，
 *    而且 [PlaylistLinkDetector] 的识别完全建立在 `https?://` 正则上，
 *    没有 scheme 的口令**天然不会被它误判成歌单链接**（导入端要单独认，见 findCode）；
 *  - 版本号写在数字尾巴里：将来要加字段就发 JUNPLAY2，旧 App 解到 v2 会明确说
 *    "请升级"，而不是硬解出一个错歌单；
 *  - 校验位 + JSON 里的曲目数 `c`：聊天软件截断长文本是最常见的坏码原因，
 *    这两种情况都能检出并给出人话提示，绝不会静默导入半首歌单。
 *
 * 【为什么不加密/不签名】口令里没有账号信息、没有 token，只有歌名和公开歌曲 id，
 * 加密只会让它在聊天软件里更长更难粘。校验位是为截断服务的，不是为防伪服务——
 * 被人改口令最多是导进来一个错歌单，损失可控。
 */
object PlaylistShare {

    /** 口令前缀：6.3 大修版起从 JUNPLAY 换成 **JM**——用户反馈"口令太长了"，先从头上省。
     *  旧前缀 JUNPLAY 的口令仍然认（见 [codeRegex]），只是不再生成。 */
    const val PREFIX = "JM"

    /** 兼容的历史前缀（识别用） */
    val LEGACY_PREFIXES = listOf("JUNPLAY", "JM")

    /** 当前写出口令用的格式版本 */
    const val FORMAT_VERSION = 2

    /** 单条口令的曲目上限。超过它，压缩后也一定超过字符上限，直接引导用户分卷 */
    const val MAX_TRACKS = 1000

    /** 单条口令的字符硬上限：再长就会被聊天软件截断，宁可拒绝生成也不给一个坏码 */
    const val MAX_CODE_CHARS = 20_000

    /** 软阈值：超过它还能用，但要提醒"微信/QQ 可能折叠或截断" */
    const val WARN_CODE_CHARS = 4_000

    /** 分卷时每卷目标字符数：卡在软阈值之下，保证每卷都能整段粘进聊天框 */
    private const val PART_TARGET_CHARS = 3_400

    /** 解压体积上限：口令体积本身只有几十 KB，异常膨胀说明是坏码，不能被 zip bomb 撑爆 */
    private const val MAX_INFLATED_BYTES = 4_000_000

    /** 单条曲目长度保护：分享文案里夹带的超长字段没有意义 */
    private const val MAX_TITLE_CHARS = 80
    private const val MAX_ARTIST_CHARS = 60
    private const val MAX_ALBUM_CHARS = 60

    /** 歌单名上限：和 [Playlists] 那边导入歌单名的 take(30) 对齐 */
    const val MAX_NAME_CHARS = 30

    /**
     * 曲目数超上限时默认建议的每卷首数。
     *
     * 6.3 大修版：v2 去掉专辑 + 前缀变短后，单首实测约 17 字符，200 首 ≈ 3400 字符仍在软阈值内。
     * 之前 150 是拍脑袋：实测一条中英混排歌单约 21 字符/首，150 首 ≈ 3100 字符，
     * 正好压在 [PART_TARGET_CHARS] 之下（比 [WARN_CODE_CHARS] 也留了余量）。
     * 定 300 的话每卷约 6300 字符——不会超硬上限，但已经落进"微信可能折叠"的区间，
     * 用户看不见结尾就很难确认自己复制全了。宁可多分两卷。
     */
    const val PART_DEFAULT = 200

    // ==================== 导出 ====================

    /** 编码结果：成功给口令原文，太长给分卷方案，空歌单/异常给人话错误 */
    sealed class EncodeResult {
        /** [warning] 非空表示口令能用但偏长（超过 [WARN_CODE_CHARS]），界面要提示一句 */
        data class Ok(val code: String, val trackCount: Int, val charCount: Int, val warning: String) : EncodeResult()

        /** 口令超上限：[partCount] / [perPart] 就是建议的分卷方案，界面上给一个"生成分卷"按钮 */
        data class TooLong(val reason: String, val charCount: Int, val partCount: Int, val perPart: Int) : EncodeResult()

        data class Fail(val message: String) : EncodeResult()
    }

    /**
     * 把整个歌单编成一条口令。
     *
     * 这里**不会**偷偷截断曲目：宁可返回 [EncodeResult.TooLong] 让用户改走分卷，
     * 也不能让剪贴板里躺一条"看着完整、导入只有半截"的码——那是最难查的 bug。
     */
    fun encode(payload: SharePayload): EncodeResult {
        val tracks = payload.tracks.filter { it.title.isNotBlank() || it.artist.isNotBlank() }
        if (tracks.isEmpty()) return EncodeResult.Fail("这个歌单还是空的，先放几首歌再分享")
        if (tracks.size > MAX_TRACKS) {
            return EncodeResult.TooLong(
                reason = "歌单有 ${tracks.size} 首，超过一条口令的 ${MAX_TRACKS} 首上限",
                charCount = 0,
                partCount = estimateParts(tracks.size, PART_DEFAULT),
                perPart = PART_DEFAULT
            )
        }
        val code = assemble(payload.name, payload.createdAt, tracks, tracks.size, 1, 1)
            ?: return EncodeResult.Fail("口令生成失败：这首歌单里有异常字符，换个歌单试试")
        if (code.length > MAX_CODE_CHARS) {
            // 按实测单首体积算每卷装几首，比按首数硬切准得多（欧美歌名长、专辑名长都会翻倍）
            val perPart = perPartFor(code.length, tracks.size)
            return EncodeResult.TooLong(
                reason = "这条口令有 ${code.length} 个字符，超过 $MAX_CODE_CHARS 会被聊天软件截断",
                charCount = code.length,
                partCount = if (perPart > 0) (tracks.size + perPart - 1) / perPart else 0,
                perPart = perPart
            )
        }
        val warning = if (code.length > WARN_CODE_CHARS) {
            "口令 ${code.length} 个字符，偏长：微信可能折叠、部分聊天软件会截断。" +
                "复制后请先粘到备忘录确认结尾是完整的，或者直接改用分卷。"
        } else ""
        return EncodeResult.Ok(code, tracks.size, code.length, warning)
    }

    /**
     * 生成分卷口令：每卷都是一个**自带动歌单名**的独立口令，导入端各建一个歌单。
     *
     * 不做"三卷拼成一首歌单"那种跨卷会话，因为剪贴板里没有可靠的方式知道
     * 用户到底粘过哪几卷、什么时候粘的；跨卷状态一旦出现错位，用户看到的
     * 就是"我明明粘了第 2 卷怎么又让我从第 1 卷开始"。
     * 一卷一歌单，导入端每次都是完整闭环，最多是用户手动把三个歌单合并。
     */
    fun encodeParts(payload: SharePayload, perPart: Int): List<String> {
        val tracks = payload.tracks.filter { it.title.isNotBlank() || it.artist.isNotBlank() }
        if (tracks.isEmpty() || perPart <= 0) return emptyList()
        val total = (tracks.size + perPart - 1) / perPart
        val out = ArrayList<String>(total)
        for (i in 0 until total) {
            val from = i * perPart
            val slice = tracks.subList(from, minOf(from + perPart, tracks.size))
            val code = assemble(payload.name, payload.createdAt, slice, slice.size, i + 1, total)
            if (code != null) out.add(code)
        }
        return out
    }

    /** 由一条口令反推每卷能装几首：用实测总长度均摊，再留两成余量给分卷头 */
    private fun perPartFor(codeChars: Int, trackCount: Int): Int {
        if (trackCount <= 0 || codeChars <= 0) return 0
        val perTrack = codeChars.toDouble() / trackCount
        if (perTrack <= 0.01) return MAX_TRACKS
        // 0.8 的余量：分卷要额外写 p/q 两个字段，而且每卷开头还要重复歌单名
        val fit = (PART_TARGET_CHARS * 0.8 / perTrack).toInt()
        return fit.coerceIn(20, MAX_TRACKS)
    }

    /** 分卷数量：只用于把"建议分几卷"这句话写给用户看 */
    private fun estimateParts(trackCount: Int, perPart: Int): Int {
        val p = if (perPart > 0) perPart else 1
        return (trackCount + p - 1) / p
    }

    /** 拼一条口令：压不出结果（超长曲目、异常字符）时返回 null，交给调用方给人话 */
    private fun assemble(
        name: String,
        createdAt: Long,
        tracks: List<ShareTrack>,
        declaredCount: Int,
        part: Int,
        parts: Int
    ): String? = runCatching {
        val json = jsonOf(name, createdAt, tracks, declaredCount, part, parts)
        val deflated = deflate(json.toByteArray(Charsets.UTF_8))
        val body = Base64.encodeToString(
            deflated, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        "$PREFIX$FORMAT_VERSION.${crcOf(body)}.$body"
    }.getOrNull()

    /**
     * 口令里那份 JSON。
     *
     * 用**定长数组**而不是 `{"title":…,"artist":…}`：曲名重复出现一千次，
     * 键名就白占一千倍体积（deflate 能压掉大半，但 base64 后仍差出一截）。
     * 时长写秒不写毫秒，也够用（匹配只拿来比长短）。
     */
    private fun jsonOf(
        name: String,
        createdAt: Long,
        tracks: List<ShareTrack>,
        declaredCount: Int,
        part: Int,
        parts: Int
    ): String {
        val root = JSONObject()
        root.put("v", FORMAT_VERSION)
        root.put("n", clip(name, MAX_NAME_CHARS))
        root.put("k", createdAt / 1000L)
        // c = 这份歌单**声明**的曲目数：解码端拿它和实际读到的条数比，
        // 就能分辨"用户只复制了一半"和"这首歌本来就没写进来"。
        root.put("c", declaredCount)
        if (parts > 1) {
            root.put("p", part)
            root.put("q", parts)
        }
        // v2 起**不写专辑**：专辑名只给匹配加分用，却常常是整条里最长的字段
        //（几十字的电影原声名），去掉后一条 100 首的口令能短一截。
        // v1 旧口令解码按 optString(3) 读，缺失自然为空，完全兼容。
        val arr = JSONArray()
        for (t in tracks) {
            arr.put(
                JSONArray().apply {
                    put(t.id.filter { it.isDigit() }.take(16))
                    put(clip(t.title, MAX_TITLE_CHARS))
                    put(clip(t.artist, MAX_ARTIST_CHARS))
                    put((t.durationMs / 1000L).coerceIn(0L, 100_000L))
                }
            )
        }
        root.put("t", arr)
        return root.toString()
    }

    /** 写出口令前洗一遍字段：去控制字符、折行、限长（超长字段是坏数据的来源） */
    private fun clip(raw: String, max: Int): String {
        val sb = StringBuilder(raw.length.coerceAtMost(max + 8))
        var space = false
        for (ch in raw) {
            when {
                // 换行/制表/控制符一律压成一个空格：口令里绝不能出现换行
                ch.isWhitespace() || ch.code < 0x20 || ch.code == 0x7F || ch.code in 0x80..0xA0 -> space = true
                // 各种"看起来像横线但不是 -"的连字符统一成 ASCII，
                // 否则 -/{_/-} 之外的写法会让导入端的歌手切分认不出合唱
                ch == '\u2010' || ch == '\u2011' || ch == '\u2012' || ch == '\u2013' ||
                    ch == '\u2014' || ch == '\u2015' || ch == '\u2212' -> {
                    if (space) { sb.append(' '); space = false }
                    sb.append('-')
                }
                else -> {
                    if (space) { sb.append(' '); space = false }
                    sb.append(ch)
                }
            }
            if (sb.length >= max) break
        }
        return sb.toString().trim()
    }

    // ==================== 导入：识别与解码 ====================

    /** 解码结果 */
    sealed class DecodeResult {
        data class Ok(val payload: SharePayload) : DecodeResult()

        /** [reason] 一定是一句人话：告诉用户哪儿不对、下一步该做什么，不抛异常 */
        data class Fail(val reason: String) : DecodeResult()
    }

    /**
     * 口令字面匹配：`JUNPLAY<版本>.<6 位校验>.<base64url>`。
     *
     * 校验位固定 6 位小写字母数字，base64 主体至少 16 字符（一首歌压缩后的下限），
     * 这样"用户随手打了 JUNPLAY1"不会进解码流程。
     */
    private val codeRegex = Regex("(?:JUNPLAY|JM)([0-9]{1,2})\\.([0-9a-z]{6})\\.([A-Za-z0-9_-]{16,})", RegexOption.IGNORE_CASE)

    /**
     * 从任意文本里挑出最长的一条口令。
     *
     * 【为什么要先删空白】聊天软件、邮件客户端会把长行按 76/998 字符折行，
     * 折进来的是换行符；一条口令一旦被折行，逐行匹配就永远匹配不上。
     * base64url 字母表里没有空白字符，所以"先抹掉所有空白再找"只有好处。
     *
     * 【为什么取最长的一条】万一文本里同时存在被截断的半条和完整的整条
     * （用户从聊天记录里选中过两次），长的才是能用那条。
     */
    fun findCode(text: String): String? {
        if (text.isBlank()) return null
        val flat = StringBuilder(text.length)
        for (ch in text) {
            if (ch.isWhitespace() || ch == '\u200B' || ch == '\uFEFF' || ch == '\u00A0') continue
            flat.append(ch)
        }
        return codeRegex.findAll(flat.toString())
            .maxByOrNull { it.value.length }?.value
            ?.uppercasePrefix()
    }

    /** 只做前缀大小写归一：主体里大小写是有意义的，绝不能整体 upper */
    private fun String.uppercasePrefix(): String {
        val dot = indexOf('.')
        return if (dot <= 0) this else substring(0, dot).uppercase() + substring(dot)
    }

    /** 这条输入像不像 Jun 口令（给剪贴板检测与导入分流用，不做完整解码） */
    fun looksLikeShareCode(text: String): Boolean = findCode(text) != null || looksLikeShortCode(text)

    /** 解开一条口令：任何一步不对都返回 [DecodeResult.Fail]，绝不抛给调用方 */
    fun decode(raw: String): DecodeResult {
        val code = findCode(raw)
            ?: return DecodeResult.Fail(
                "没认出这条 Jun 歌单口令。口令应该以 $PREFIX 开头（例如 ${PREFIX}1.ab12cd…），" +
                    "网易云 / QQ 音乐的链接请直接用上面的「导入歌单」"
            )
        val pieces = code.split('.')
        if (pieces.size != 3) return DecodeResult.Fail("口令格式不完整：应该是「$PREFIX + 版本 . 校验 . 内容」三段")
        val version = Regex("(?:JUNPLAY|JM)([0-9]{1,2})", RegexOption.IGNORE_CASE)
            .matchEntire(pieces[0])?.groupValues?.get(1)?.toIntOrNull()
            ?: return DecodeResult.Fail("口令前缀不对，可能不是 $PREFIX 这一族的内容")
        if (version > FORMAT_VERSION) {
            return DecodeResult.Fail(
                "这条口令来自更新版的 Jun Music（格式 v$version，本机支持到 v$FORMAT_VERSION）。" +
                    "把 App 升级到最新版再导入就行"
            )
        }
        if (version <= 0) return DecodeResult.Fail("口令版本号异常，请重新复制整段内容")

        val body = pieces[2]
        // 校验位比对前先 lowercase：正则开了 IGNORE_CASE，聊天软件把
        // "abc123" 自动改成 "Abc123" 时不应该被判成坏码。
        if (crcOf(body) != pieces[1].lowercase()) {
            return DecodeResult.Fail(
                "口令校验没过：多半是复制时被截断或中间漏了字符。" +
                    "请回到那条分享消息长按 → 复制全文，再粘一次（不要手动选中片段）"
            )
        }
        val bytes = runCatching {
            // encodeToString 用了 NO_PADDING，聊天软件里也不会出现 '='；
            // 但 android.util.Base64.decode 对补齐更稳，所以按 4 字节一组补回 '='
            val pad = (4 - body.length % 4) % 4
            Base64.decode(body + "=".repeat(pad), Base64.URL_SAFE or Base64.NO_WRAP)
        }.getOrNull()
            ?: return DecodeResult.Fail("口令里含有非法字符，可能被聊天软件改写过了。建议改用分卷口令重发一次")

        val json = runCatching { String(inflate(bytes), Charsets.UTF_8) }
            .getOrElse {
                return DecodeResult.Fail(
                    if (bytes.size < 32) "口令内容太短，应该是复制漏了一段。请复制完整整行再试"
                    else "口令内容已损坏（解压失败），麻烦让对方重新分享一次"
                )
            }
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return DecodeResult.Fail("口令内容解析失败，麻烦让对方重新分享一次")

        val arr = root.optJSONArray("t") ?: JSONArray()
        val tracks = ArrayList<ShareTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            val title = row.optString(1)
            val artist = row.optString(2)
            if (title.isBlank() && artist.isBlank()) continue
            // v1：[id,title,artist,album,dur]；v2：[id,title,artist,dur]（专辑省略）
            val isV1 = version < 2
            tracks.add(
                ShareTrack(
                    id = row.optString(0).filter { it.isDigit() },
                    title = title,
                    artist = artist,
                    album = if (isV1) row.optString(3) else "",
                    durationMs = row.optLong(if (isV1) 4 else 3, 0L) * 1000L
                )
            )
        }
        // 声明数 > 实读数 = 截断。校验位是算在整段主体上的，一般到不了这里就已被拦下；
        // 这一条兜的是"主体完整但 JSON 数组被改写"这类少见情况。
        val declared = root.optInt("c", -1)
        if (declared > tracks.size) {
            return DecodeResult.Fail(
                "口令被截断了：这条歌单应该有 $declared 首，只读到 ${tracks.size} 首。" +
                    "请复制完整整段（末尾不应该有省略号），或让对方改用分卷"
            )
        }
        if (tracks.isEmpty()) return DecodeResult.Fail("口令里没有可导入的曲目，让对方重新分享一次吧")

        val parts = root.optInt("q", 1).coerceAtLeast(1)
        val part = root.optInt("p", 1).coerceIn(1, parts)
        return DecodeResult.Ok(
            SharePayload(
                name = root.optString("n").ifBlank { "分享的歌单" },
                createdAt = root.optLong("k", 0L) * 1000L,
                tracks = tracks,
                part = part,
                parts = parts
            )
        )
    }

    /**
     * 校验位：CRC32 掩到 31 位后写 6 个 base36 字符。
     *
     * 只校验**口令主体是否被改动/截断**，不校验语义：base64 少一位，后面全部错位，
     * inflate 也会失败，所以这里的作用是把最常见的"复制了一半"提前拦下来，
     * 给用户一句明确的人话，而不是一句含糊的"解析失败"。
     */
    private fun crcOf(body: String): String {
        val crc = CRC32()
        crc.update(body.toByteArray(Charsets.UTF_8))
        return (crc.value and 0x7FFF_FFFFL).toString(36).padStart(6, '0')
    }

    // ==================== 压缩 ====================

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream(bytes.size / 2 + 64)
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    /** 解压并带上限保护：坏码不能把内存撑爆（inflate 返回 0 而不 finished 视为截断） */
    private fun inflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater(false)
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream(bytes.size * 4 + 256)
        val buf = ByteArray(16384)
        var broken = false
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n <= 0) {
                if (inflater.finished()) break
                broken = true
                break
            }
            if (out.size() + n > MAX_INFLATED_BYTES) {
                inflater.end()
                throw IllegalStateException("口令体积异常")
            }
            out.write(buf, 0, n)
        }
        inflater.end()
        if (broken) throw IllegalStateException("口令内容不完整")
        return out.toByteArray()
    }

    // ==================== 便捷构造 ====================

    /** 一批 Song → 分享载荷（导出端一行搞定） */
    fun payloadOf(name: String, songs: List<Song>): SharePayload = SharePayload(
        name = name.trim().ifBlank { "我的歌单" }.take(MAX_NAME_CHARS),
        createdAt = System.currentTimeMillis(),
        tracks = songs.map {
            ShareTrack(
                id = it.songId.filter { c -> c.isDigit() },
                title = it.title,
                artist = it.artist,
                album = it.album,
                durationMs = it.durationMs
            )
        }
    )

    /**
     * 歌单重名时自动加后缀：`名字` → `名字 2` → `名字 3`。
     *
     * 导入同一个口令两次是常态（用户想"再导一遍看看"），直接建两个同名歌单会让
     * 歌单列表出现两个分不清的行；报错又不像"导入"该有的脾气。所以改名新建。
     */
    fun uniqueName(desired: String, existing: Collection<String>): String {
        val base = desired.trim().ifBlank { "导入的歌单" }.take(MAX_NAME_CHARS)
        if (existing.none { it == base }) return base
        for (i in 2..99) {
            val suffix = " $i"
            val cand = base.take(MAX_NAME_CHARS - suffix.length) + suffix
            if (existing.none { it == cand }) return cand
        }
        return base + " " + System.currentTimeMillis()
    }

    /**
     * 分享文案：口令 + 一句"怎么导入"。
     *
     * 口令本身对没装 Jun Music 的人毫无意义，所以说明必须写在同一条消息里；
     * 顺便把曲目数写出来，接收方一眼能判断"对方是不是只分享了半截"。
     */
    fun shareText(payload: SharePayload, code: String): String = buildString {
        append("《").append(payload.displayName).append("》· ").append(payload.tracks.size).append(" 首 — Jun Music 歌单\n")
        append(code).append('\n')
        if (looksLikeShortCode(code)) {
            append("打开 Jun Music，把上面这行粘进去就能导入（30 天内有效）。")
        } else {
            append("复制这一整段（JUNPLAY 开头那行），打开 Jun Music 会自动提示导入；" +
                "没提示就进「歌单 → 导入 Jun 口令」粘上去。")
        }
    }

    // ==================== 短码分享（6.6.6）====================
    //
    // 用户原话：「分享歌单那一串特别长，我歌越多它越长，别人根本复制不完」。
    //
    // 口令是纯本地编码，每首歌必须带曲名 + 歌手，100 首就是几千字符，
    // 微信里直接折叠。短码把正文存到服务端，聊天里只发 8 位码。
    //
    // 这里**不做降级判断**：上传失败就返回 null，界面继续用完整口令。
    // 断网时分享歌单仍然可用，只是长一点 —— 不能因为服务器不可用就不让分享。

    /** 短码前缀。导入时靠它判断"这是一条短码，要去服务器取正文"。
     *  6.9.3：JMS: → Jun: ——用户要求短码必须以 Jun 开头，聊天里一眼知道是谁家的码。 */
    const val SHORT_PREFIX = "Jun:"

    /** 历史前缀（6.9.2 及以前发出去的短码仍然能导入，不能让老分享变死码） */
    private val LEGACY_SHORT_PREFIXES = listOf("JMS:", "JMS-", "JM:", "Jun-", "Jun：", "JMS：")

    /** 短码正文：8 位，去掉容易混淆的 0/1，和服务器 genShareCode 的字符集一致 */
    private val SHORT_BODY = Regex("[2-9a-zA-Z]{8}")

    private fun stripShortPrefix(raw: String): String {
        var t = raw.trim()
        for (p in listOf(SHORT_PREFIX) + LEGACY_SHORT_PREFIXES) {
            if (t.startsWith(p, ignoreCase = true)) {
                t = t.substring(p.length).trim()
                break
            }
        }
        return t
    }

    /** 从一段聊天文本里挑出短码（可能夹着《歌单名》· 12 首之类的前后缀） */
    fun findShortCode(text: String): String? {
        if (text.isBlank()) return null
        val re = Regex("(?i)(?:JMS\\s*[:：-]|Jun\\s*[:：-])\\s*([2-9a-zA-Z]{8})")
        val m = re.find(text) ?: return null
        return SHORT_PREFIX + m.groupValues[1].lowercase()
    }


    /**
     * 把一条完整口令存到服务器，换回一个 8 位短码。
     *
     * @return 短码（不含 [SHORT_PREFIX]）；服务器不可用 / 超限 / 未配置时返回 null
     */
    suspend fun uploadShort(code: String, name: String, trackCount: Int): String? =
        withContext(Dispatchers.IO) {
            if (code.isBlank()) return@withContext null
            if (!ServerApi.isConfigured) return@withContext null
            val body = JSONObject().apply {
                put("payload", code)
                put("name", name.take(MAX_NAME_CHARS))
                put("tracks", trackCount)
            }
            val root = runCatching { ServerApi.request("/api/share", body, null, null, 15_000) }.getOrNull()
                ?: return@withContext null
            if (root.optInt("code", -1) != 0) return@withContext null
            root.optJSONObject("data")?.optString("code", "")?.takeIf { it.isNotBlank() }
        }

    /**
     * 用短码换回完整口令正文。
     *
     * @return 完整口令（可直接交给 [decode]）；失败返回 null
     */
    suspend fun fetchShort(shortCode: String): String? = withContext(Dispatchers.IO) {
        val c = stripShortPrefix(shortCode).lowercase()
        if (c.isBlank()) return@withContext null
        if (!ServerApi.isConfigured) return@withContext null
        val root = runCatching { ServerApi.request("/api/share/$c", null, null, null, 12_000) }.getOrNull()
            ?: return@withContext null
        if (root.optInt("code", -1) != 0) return@withContext null
        root.optJSONObject("data")?.optString("payload", "")?.takeIf { it.isNotBlank() }
    }

    /** 这段文本是短码吗（Jun: + 8 位，兼容历史上的 JMS:） */
    fun looksLikeShortCode(text: String): Boolean {
        val body = stripShortPrefix(text)
        if (body == text.trim()) return false          // 没有前缀 → 不是短码
        return body.matches(SHORT_BODY)
    }
}

/**
 * 一次导入的完整回执。
 *
 * 为什么不只返回一句 String：剪贴板卡片只要一句话，但分享弹层要把
 * "哪几首没找到"逐条列出来让用户自己补。两处共用同一个返回值，
 * 省得导入逻辑写两遍。[message] 永远以"已导入 N 首…"开头，
 * 因为 [ClipboardImportPrompt] 那条链路是靠这个前缀判断成功与否的。
 */
data class ShareImportOutcome(
    val message: String,
    val imported: Int,
    val total: Int,
    val missing: List<ShareTrack>,
    /** 至少导进来一首才算成功：剪贴板卡片据此决定是"完成"还是"重试" */
    val ok: Boolean
)

/**
 * 把口令里的曲目清单变成真正可播的 [Song]。
 *
 * 【匹配顺序，按"便宜且准"排】
 *  1. 网易云 id 命中本机已知曲库（榜单缓存 / 搜索历史 / 我喜欢缓存）——零网络；
 *  2. 歌名+歌手命中本机已知曲库——零网络，专治"对方是本地 SAF 歌、我这儿也有一模一样的一首"；
 *  3. 剩下的按 id 批量查 `/song/detail`（50 首一片），一次拿齐标题/歌手/封面/时长；
 *  4. 还剩下的（无 id、或 id 已下架）才逐首搜索网易云，并设搜索次数上限。
 *
 * 第 1、2 步很关键：重复导入同一条口令时整场匹配是纯内存的，秒回且不吃流量，
 * 这也正是"幂等"的第二道闸（第一道是 [Playlists.addSongs] 本身按 id 去重）。
 *
 * 【为什么不直接用网易云搜索当唯一路径】
 * 一千首歌逐首搜要一千次请求，被限流后大面积失败，用户看到的就是一长串"没找到"。
 * 批量 detail 接口一次能查 50 首，把网络请求从 O(曲目数) 压到 O(曲目数/50)。
 */
object PlaylistImport {

    /** 批量查详情一片多少首，和 NeteaseApi.songDetail 内部分片保持一致 */
    private const val DETAIL_CHUNK = 50

    /**
     * 逐首搜索的次数上限。
     *
     * 正常口令里靠搜索兜底的只有个位数（下架、翻唱、本地歌）。
     * 需要搜几百首的口令一定来自别的 App 的纯文字歌单，那种情况继续硬打网络
     * 只会把限流请出来，剩下的干脆全报"没找到"，并在文案里说明为什么停了。
     */
    const val MAX_SEARCH_LOOKUPS = 150

    /** 综合分门槛：低于它就宁可报"没找到"，也不要塞一首别人的歌进用户歌单 */
    private const val ACCEPT_SCORE = 0.74

    /** 歌名分门槛：歌手重合度可以骗人（"Various Artists"），歌名不行 */
    private const val ACCEPT_TITLE_SCORE = 0.72

    /** 进度：给弹层显示"第 N/M 首匹配中 · 当前歌名" */
    data class Progress(val done: Int, val total: Int, val current: String)

    /**
     * 匹配结果。
     *
     * [duplicates] 是"口令里本来就重复的那几首"（同名同 id 出现两次）：
     * 它们既不该进 [missing]（用户会误以为丢了歌），也不该重复入库。
     */
    data class Outcome(
        val songs: List<Song>,
        val missing: List<ShareTrack>,
        val knownHits: Int,
        val detailHits: Int,
        val searchHits: Int,
        val duplicates: Int,
        val stoppedSearching: Boolean
    )

    /** 匹配进度节流器：一千首逐首批进度会把主线程刷屏，120ms 一次足够界面读 */
    private class Ticker(val total: Int, val onProgress: (Progress) -> Unit) {
        var done = 0
        private var last = 0L
        fun report(index: Int, title: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && index != total - 1 && now - last < 120L) return
            last = now
            onProgress(Progress(done.coerceIn(0, total).coerceAtLeast(index + 1), total, title))
        }
    }

    /**
     * 执行匹配。所有网络都在 [Dispatchers.IO]，任何一首查失败都只是"这一首没找到"，
     * 不会让整个导入崩掉。
     *
     * @param localLibrary 界面当前那份曲库（PlayerUiState.allSongs）；
     *        与进程内榜单缓存、[PlayHistory] 已知歌曲一起作为离线候选池。
     */
    suspend fun match(
        payload: SharePayload,
        localLibrary: List<Song>,
        onProgress: (Progress) -> Unit
    ): Outcome = withContext(Dispatchers.IO) {
        val tracks = payload.tracks
        val total = tracks.size
        if (total == 0) return@withContext Outcome(emptyList(), emptyList(), 0, 0, 0, 0, false)
        val folder = payload.displayName.ifBlank { "导入的歌单" }.take(PlaylistShare.MAX_NAME_CHARS)

        // ---- 离线候选池：三份来源合起来，同一首歌留第一个就行（内容是一样的） ----
        val pool = ArrayList<Song>(total * 2 + 512)
        pool.addAll(MusicRepository.currentLibrary())
        pool.addAll(PlayHistory.knownSongs())
        pool.addAll(localLibrary)
        val byNeteaseId = HashMap<String, Song>(pool.size * 2)
        val byTitle = HashMap<String, ArrayList<Song>>(pool.size * 2)
        for (s in pool) {
            val title = normalizeTitle(s.title)
            if (title.isEmpty()) continue
            if (s.songId.isNotBlank() && !byNeteaseId.containsKey(s.songId)) byNeteaseId[s.songId] = s
            byTitle.getOrPut(title) { ArrayList(2) }.add(s)
        }

        val picked = arrayOfNulls<Song>(total)
        val taken = HashSet<Long>(total * 2)
        val dup = HashSet<Int>()
        val needDetail = ArrayList<Int>()
        val needSearch = ArrayList<Int>()
        var knownHits = 0
        val tick = Ticker(total, onProgress)

        // ---- 第 1、2 步：完全离线 ----
        for (i in tracks.indices) {
            val t = tracks[i]
            val byId = if (t.id.isNotBlank()) byNeteaseId[t.id] else null
            val hit = byId ?: bestFromPool(byTitle, t)
            when {
                hit == null -> if (t.id.isNotBlank()) needDetail.add(i) else needSearch.add(i)
                taken.add(hit.id) -> { picked[i] = hit; knownHits++ }
                else -> dup.add(i)          // 口令里前面已经出现过这首，静默合掉
            }
            tick.done = i + 1
            tick.report(i, t.title)
        }

        // ---- 第 3 步：按 id 批量查详情 ----
        var detailHits = 0
        if (needDetail.isNotEmpty()) {
            val slotsByNeteaseId = HashMap<String, ArrayList<Int>>()
            for (i in needDetail) slotsByNeteaseId.getOrPut(tracks[i].id) { ArrayList(1) }.add(i)
            val ids = slotsByNeteaseId.keys.toList()
            for (chunk in ids.chunked(DETAIL_CHUNK)) {
                coroutineContext.ensureActive()
                val found = runCatching { NeteaseApi.songDetail(chunk) }.getOrDefault(emptyList())
                val made = if (found.isEmpty()) emptyList() else MusicRepository.fromNetease(folder, found)
                val bySongId = made.associateBy { it.songId }
                for (id in chunk) {
                    // 查不到 = 下架 / 区域限制 / 对方写的是本地歌，退化成第 4 步按名字搜
                    val song = bySongId[id] ?: continue
                    for (slot in slotsByNeteaseId[id].orEmpty()) {
                        if (picked[slot] != null) continue
                        if (taken.add(song.id)) { picked[slot] = song; detailHits++ } else dup.add(slot)
                    }
                }
                tick.done = (tick.done + chunk.size).coerceAtMost(total)
                tick.report(tick.done - 1, "", force = true)
            }
            for (i in needDetail) if (picked[i] == null && i !in dup) needSearch.add(i)
        }

        // ---- 第 4 步：逐首搜索（有上限） ----
        var searchHits = 0
        var stopped = false
        val pending = needSearch.filter { picked[it] == null && it !in dup }.distinct().sorted()
        for ((n, i) in pending.withIndex()) {
            if (n >= MAX_SEARCH_LOOKUPS) {
                stopped = true
                break
            }
            coroutineContext.ensureActive()
            val t = tracks[i]
            val song = searchOne(t, folder)
            if (song != null) {
                if (taken.add(song.id)) { picked[i] = song; searchHits++ } else dup.add(i)
            }
            tick.done = (i + 1).coerceAtLeast(tick.done)
            tick.report(i, t.title)
        }

        val songs = ArrayList<Song>(total)
        val missing = ArrayList<ShareTrack>()
        for (i in tracks.indices) {
            val s = picked[i]
            if (s != null) songs.add(s) else if (i !in dup) missing.add(tracks[i])
        }
        onProgress(Progress(total, total, ""))
        Outcome(
            songs = songs,
            missing = missing,
            knownHits = knownHits,
            detailHits = detailHits,
            searchHits = searchHits,
            duplicates = dup.size,
            stoppedSearching = stopped
        )
    }

    /** 一首一首搜：先"歌名 歌手"，搜不到再退化成只用歌名（很多歌的歌手写法带副标题） */
    private suspend fun searchOne(t: ShareTrack, folder: String): Song? {
        val key = "${t.title} ${t.artist}".trim()
        if (key.isBlank()) return null
        bestFromNetease(t, folder, key)?.let { return it }
        if (t.artist.isNotBlank() && t.title.isNotBlank()) bestFromNetease(t, folder, t.title)?.let { return it }
        return null
    }

    /**
     * 一次搜索里挑最像的那首。
     *
     * 候选直接来自 [NeteaseApi.search]（NsSong），先打分再一次性交给
     * [MusicRepository.fromNetease] 构造 —— 中间不能自己 new Song：
     * 那条路会漏掉 stableId、代理播放地址和封面 https 归一，
     * 导入的歌就会和曲库里同一首歌变成两个身份。
     */
    private suspend fun bestFromNetease(t: ShareTrack, folder: String, keyword: String): Song? {
        val candidates = runCatching { NeteaseApi.search(keyword, 12) }.getOrDefault(emptyList())
        if (candidates.isEmpty()) return null
        var best: NeteaseApi.NsSong? = null
        var bestScore = 0.0
        for (c in candidates) {
            val s = score(t, c.title, c.artist, c.durationMs)
            if (s > bestScore) {
                bestScore = s
                best = c
            }
        }
        val win = best ?: return null
        if (bestScore < ACCEPT_SCORE) return null
        val made = MusicRepository.fromNetease(folder, listOf(win))
        return made.firstOrNull()
    }

    /** 在离线候选池里按分数找同一首（同名可能有多位歌手版本，所以要打分不能直接取第一个） */
    private fun bestFromPool(byTitle: Map<String, ArrayList<Song>>, t: ShareTrack): Song? {
        val title = normalizeTitle(t.title)
        if (title.isEmpty()) return null
        val cands = byTitle[title] ?: return null
        var best: Song? = null
        var bestScore = 0.0
        for (c in cands) {
            val s = score(t, c.title, c.artist, c.durationMs)
            if (s > bestScore) {
                bestScore = s
                best = c
            }
        }
        return if (bestScore >= ACCEPT_SCORE) best else null
    }

    /**
     * 相似度打分：`0.62*歌名 + 0.30*歌手 + 0.08*时长`。
     *
     * 为什么这么配：歌名是唯一性最强的字段，但"Live 版 / Remix 版 / 伴奏"这类
     * 尾巴靠括号区分，所以先剥括号再比；歌手名写法最乱（简繁体、中英并列、
     * "A, B" 合唱），因此只用它当"能不能凑够分"的第二票，绝不单独当条件；
     * 时长只用来扣分——同名翻唱时长往往差十几秒，正好是最有效的一票否决。
     *
     * 两条硬门槛（[ACCEPT_SCORE] + [ACCEPT_TITLE_SCORE]）：宁可报"没找到"，
     * 也不能把一首不相干的歌塞进用户歌单——那比导不进去糟糕得多。
     */
    private fun score(want: ShareTrack, candTitle: String, candArtist: String, candDurationMs: Long): Double {
        val ts = titleScore(want.title, candTitle)
        if (ts < ACCEPT_TITLE_SCORE) return 0.0
        val as_ = artistScore(want.artist, candArtist)
        var total = 0.62 * ts + 0.30 * as_
        total += 0.08 * durationScore(want.durationMs, candDurationMs)
        return total
    }

    private fun titleScore(a: String, b: String): Double {
        val x = normalizeTitle(a)
        val y = normalizeTitle(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.9
        val set = x.toHashSet()
        val common = y.count { it in set }
        return common.toDouble() / maxOf(x.length, y.length)
    }

    /**
     * 歌手分：先按分隔符切成一个个名字，有一个完全对上就给满分
     * （合唱顺序不同、"A/B" 与 "A, B" 都算同一首）；对不上再退化成字符重合。
     * 两边都空时给 0.6 —— 不给分也不否决，剩下交给歌名。
     */
    private fun artistScore(a: String, b: String): Double {
        val x = a.trim()
        val y = b.trim()
        if (x.isEmpty() && y.isEmpty()) return 0.6
        if (x.isEmpty() || y.isEmpty()) return 0.3
        val xs = splitArtists(x)
        val ys = splitArtists(y)
        if (xs.any { it in ys } && ys.any { it in xs }) return 1.0
        val set = y.lowercase().toHashSet()
        val hit = x.lowercase().count { it in set }
        return (hit.toDouble() / maxOf(x.length, y.length).toDouble()).let {
            when {
                it >= 0.6 -> 0.85
                it >= 0.34 -> 0.6
                it >= 0.2 -> 0.35
                else -> 0.1
            }
        }
    }

    /**
     * 歌手串切分。分隔符**必须统一成 String 变长参数**：
     * `split` 的 Char 版和 String 版不能混着传，混了直接编译不过。
     */
    private fun splitArtists(raw: String): Set<String> =
        raw.split("/", ",", "，", "、", ";", "；", "&", "＆", " feat. ", " ft. ", " ")
            .map { normalizeTitle(it) }
            .filter { it.isNotEmpty() }
            .toSet()

    /** 时长：双方都有才参与；5 秒内算同一首，差得远就大幅扣分 */
    private fun durationScore(a: Long, b: Long): Double {
        if (a <= 0L || b <= 0L) return 0.5
        val diff = Math.abs(a - b) / 1000.0
        return when {
            diff <= 5.0 -> 1.0
            diff <= 15.0 -> 0.6
            diff <= 40.0 -> 0.25
            else -> 0.0
        }
    }

    /**
     * 歌名归一化：剥掉括号里的"Live / Remix / 伴奏 / 高清", 转小写，去掉空白和标点。
     *
     * 分享文案里的歌名十有八九带尾巴（《晴天 (Live)》），不剥掉的话同一条口令里
     * 同一首歌都可能被判成两首不同的歌。
     */
    private fun normalizeTitle(s: String): String {
        val noBracket = s.replace(Regex("[（(\\[【].*?[）)\\]】]"), "")
        return noBracket.lowercase().replace(Regex("[\\s\\p{Punct}·、，。！？：；“”‘’《》…—－-]"), "")
    }

    /** 结果文案：把"哪几首没找到""为什么停了""重复合掉几首"讲清楚，
     *  开头保持"已导入 N 首…"——剪贴板卡片靠这个前缀判断成功与否 */
    fun describe(name: String, out: Outcome, total: Int): String = buildString {
        append("已导入 ${out.songs.size} 首到「$name」")
        val miss = out.missing.size
        if (miss > 0) {
            append("（${total} 首里有 $miss 首没找到")
            append(out.missing.take(10).joinToString("、") {
                if (it.artist.isBlank()) it.title else "${it.title}（${it.artist}）"
            })
            if (miss > 10) append("…等 $miss 首")
            if (out.stoppedSearching) {
                append("；剩下的是怕打太多搜索请求被限流才停下的，可以稍后再导一次补齐")
            }
            append("）")
        }
        if (out.duplicates > 0) append("（口令里 ${out.duplicates} 首是重复的，已经合成一首）")
        if (out.songs.isEmpty()) {
            append(" 但一首都没匹配上——这些歌多半已下架或只在别的平台有；")
            append("可以先在曲库里搜到其中几首、或让对方重新分享一份带网易云 id 的口令")
        }
    }

    /**
     * 全链路导入：解码 → 匹配 → 新建歌单 → 落盘 → 回执。
     *
     * 【为什么把整条链放在数据层，而不是让 ViewModel 写】
     * 调用方有两处：分享弹层和剪贴板确认卡片。两边都要"先 [PlayHistory.saveKnownSongs]
     * 再 [Playlists.addSongs]"——顺序反了不算错，但下次导入时离线池少一批歌，
     * 同一份口令第二次导入就要重新联网。这种"顺序有讲究但错了不报错"的规矩
     * 写两遍一定会有人漏写一遍，所以在这里做成一次函数调用。
     *
     * 【一首都没匹配上时故意不建空歌单】
     * 建了就是一个躺在「我的歌单」里的空壳，用户还得手动删；报"没导进来"更符合直觉。
     * 回执文案这时以"没能导入"开头，[ok] 置 false，界面（剪贴板卡片）就会走失败样式，
     * 而不是伪装成功。
     *
     * 【重名】走 [PlaylistShare.uniqueName] 自动加 " 2" 后缀：重复导入同一个口令很常见，
     * 直接报错会让人以为口令坏了，静默合并进旧歌单又会让用户看不出"到底导没导进来"。
     */
    suspend fun importCode(
        raw: String,
        localLibrary: List<Song>,
        onProgress: (Progress) -> Unit = {}
    ): ShareImportOutcome {
        // 6.9.3：短码（Jun:xxxxxxxx）先在服务器换回完整口令再解。
        // 以前只有分享弹层里那一处做了这一步，剪贴板自动识别走的是本方法，
        // 于是"复制短码回 App"永远提示格式不对 —— 用户要的正是这条链路。
        val expanded = if (PlaylistShare.looksLikeShortCode(raw)) {
            PlaylistShare.fetchShort(raw) ?: return ShareImportOutcome(
                message = "短码取不回来：可能已过期（30 天），或者当前网络连不上服务器。" +
                    "让对方重新分享一次，或用完整口令导入。",
                imported = 0, total = 0, missing = emptyList(), ok = false
            )
        } else raw
        val payload = when (val d = PlaylistShare.decode(expanded)) {
            is PlaylistShare.DecodeResult.Ok -> d.payload
            is PlaylistShare.DecodeResult.Fail ->
                return ShareImportOutcome(d.reason, 0, 0, emptyList(), ok = false)
        }
        val out = match(payload, localLibrary, onProgress)
        val total = payload.tracks.size
        if (out.songs.isEmpty()) {
            return ShareImportOutcome(
                message = "没能导入：口令是好的（${payload.displayName} · $total 首），" +
                    "但 ${total} 首都对不上——多半是已下架的歌或只在别的平台的本地歌",
                imported = 0,
                total = total,
                missing = out.missing,
                ok = false
            )
        }
        val name = PlaylistShare.uniqueName(
            payload.displayName,
            Playlists.all().map { it.name }
        )
        val pid = withContext(Dispatchers.IO) {
            val id = Playlists.create(name)
            // 先记元数据再写歌单：歌单只存 ID，界面还原全靠这份已知曲库
            PlayHistory.saveKnownSongs(out.songs)
            Playlists.addSongs(id, out.songs.map { it.id })
            id
        }
        createdPlaylistId = pid
        return ShareImportOutcome(
            message = describe(name, out, total),
            imported = out.songs.size,
            total = total,
            missing = out.missing,
            ok = true
        )
    }

    /**
     * 最近一次 [importCode] 新建的歌单 ID（没有则 -1）。
     *
     * 为什么不塞进 [ShareImportOutcome] 当字段：那样每个调用方（含界面里构造的"失败占位"）
     * 都得跟着多写一个参数，而界面只想知道"导入完要不要直接跳过去"。
     * ViewModel 拿到 ok=true 的回执后读这一个值即可，取用请立刻读取，别缓存——
     * 下一次导入会覆盖它。
     */
    var createdPlaylistId: Long = -1L
        private set
}
