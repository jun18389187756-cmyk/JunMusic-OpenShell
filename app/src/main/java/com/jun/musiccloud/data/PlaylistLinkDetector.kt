package com.jun.musiccloud.data

import android.content.Context

/**
 * 剪贴板里的歌单链接识别。
 *
 * 只认音乐平台的链接：
 *  - 网易云：music.163.com/playlist、163cn.tv 短链
 *  - QQ 音乐：y.qq.com/.../playlist、taoge 分享页、fcgi-bin 短链
 *  - 酷狗：kugou.com/yy/special/single、m.kugou.com/plist/list、t1.kugou.com 短链（6.9.6 新增）
 *  - 汽水音乐：qishui.douyin.com/s/ 短链
 *
 * 单曲 / 专辑 / 歌手页直接排除；短链分不清是歌单还是单曲时看分享文案，
 * 文案里只写「分享单曲」的就不弹导入提示。
 */
object PlaylistLinkDetector {

    private val urlRegex = Regex("(?i)https?://[^\\s\"'<>()（）\\[\\]，。；！？、）】》]+")

    private val trailingChars = charArrayOf(
        '.', ',', ';', ':', '!', '?', ')', '(', ']', '[', '>', '<',
        '，', '。', '；', '：', '！', '？', '）', '（', '】', '》'
    )

    private val saysSong = Regex("(分享|复制|正在播放)?\\s*(单曲|歌曲)|song", RegexOption.IGNORE_CASE)
    private val saysPlaylist = Regex("歌单|playlist|taoge|收藏|创建的歌单", RegexOption.IGNORE_CASE)

    /** 从任意文本（分享文案 / 剪贴板）里挑出第一个像歌单的链接 */
    fun extract(input: String): String? {
        if (input.isBlank()) return null
        val text = input.replace("\u200B", "").replace("\uFEFF", "")
        val songOnly = saysSong.containsMatchIn(text) && !saysPlaylist.containsMatchIn(text)
        for (match in urlRegex.findAll(text)) {
            val url = match.value.trimEnd(*trailingChars)
            if (url.length < 12) continue
            val lower = url.lowercase()
            if (!isMusicHost(lower)) continue
            if (isSingleSong(lower)) continue
            if (isPlaylistPage(lower)) return url
            // 短链：文案明确是单曲就跳过，其余情况交给服务器解析
            if (isShortLink(lower) && !songOnly) return url
        }
        return null
    }

    /**
     * R13：链接之外，还要认本 App 自己的**歌单口令**（`JUNPLAY1.<校验>.<正文>`，见 PlaylistShare）。
     *
     * 为什么必须单独一条路：上面整套判据全部建立在 `https?://` 这条正则上，
     * 口令是纯文本、没有 scheme，`extract()` 永远认不到它；
     * 反过来，口令里如果夹了一段网易云链接（分享文案常见），也绝不会把口令当成链接——
     * 两条判据互不重叠，所以判定顺序是「先链接、再口令」，谁先命中算谁。
     *
     * 命中后仍然走 ClipboardLinkStore 那套去重：bucket 就用口令本身（已含 CRC 校验位，
     * 同一条口令第二次复制时间戳会变，仍然会再问一次）。
     */
    fun extractAny(input: String): String? =
        extract(input) ?: PlaylistShare.findCode(input) ?: PlaylistShare.findShortCode(input)

    private fun isMusicHost(url: String): Boolean =
        url.contains("music.163.com") ||
            url.contains("163cn.tv") ||
            url.contains("y.qq.com") ||
            url.contains("qq.com/base/fcgi-bin") ||
            url.contains("kugou.com") ||
            url.contains("qishui.douyin.com") ||
            url.contains("music.douyin.com")

    private fun isSingleSong(url: String): Boolean =
        url.contains("/song/") ||
            url.contains("songdetail") ||
            url.contains("/album/") ||
            url.contains("albumdetail") ||
            url.contains("/singer/") ||
            url.contains("artistdetail") ||
            url.contains("/mv/") ||
            url.contains("mvdetail") ||
            // 酷狗的「单曲」页形如 /yy/html/single/xxx.html，别和歌单页 /special/single/ 混淆
            (url.contains("kugou.com") && url.contains("/html/single/")) ||
            (url.contains("music.163.com") && Regex("[?&#]id=\\d+").containsMatchIn(url) &&
                !url.contains("playlist") && !url.contains("discover/toplist"))

    private fun isPlaylistPage(url: String): Boolean =
        (url.contains("music.163.com") && url.contains("playlist")) ||
            (url.contains("y.qq.com") && (url.contains("/playlist/") || url.contains("playlistid"))) ||
            (url.contains("y.qq.com") && url.contains("taoge")) ||
            (url.contains("i.y.qq.com") && url.contains("taoge")) ||
            (url.contains("kugou.com") && url.contains("/special/single/")) ||
            (url.contains("kugou.com") && url.contains("/plist/list/")) ||
            (url.contains("qishui.douyin.com") && Regex("/share/playlist|/playlist/").containsMatchIn(url))

    private fun isShortLink(url: String): Boolean =
        url.contains("163cn.tv") ||
            url.contains("fcgi-bin/u") ||
            // 酷狗分享面板给的是 t1.kugou.com/xxxxx 这种裸短链
            (url.contains("t1.kugou.com")) ||
            (url.contains("qishui.douyin.com") && url.contains("/s/"))
}

/**
 * 防止同一条剪贴板内容反复弹窗。
 *
 * 记录「上次提示的链接 + 剪贴板时间戳」：
 *  - 文案变了 / 重新复制了同一条（时间戳变化）→ 立即提示；
 *  - 同一条内容放着没动 → 冷却 10 分钟内不再打扰。
 */
object ClipboardLinkStore {
    private const val PREFS = "jun_clipboard_import"
    private const val KEY_LINK = "last_link"
    private const val KEY_TS = "last_clip_ts"
    private const val KEY_AT = "last_prompt_at"
    private const val COOLDOWN_MS = 10 * 60 * 1000L

    fun shouldPrompt(context: Context, link: String, clipTimestamp: Long): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY_LINK, "") != link) return true
        if (clipTimestamp > 0L && p.getLong(KEY_TS, 0L) != clipTimestamp) return true
        return System.currentTimeMillis() - p.getLong(KEY_AT, 0L) > COOLDOWN_MS
    }

    fun markHandled(context: Context, link: String, clipTimestamp: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LINK, link)
            .putLong(KEY_TS, clipTimestamp)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }
}
