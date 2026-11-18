package com.jun.musiccloud.data

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 6.8.0：**播放用本地索引** —— 让"手机上已有的文件"优先于任何在线音源。
 *
 * ## 与 [LocalLibrary] 的分工
 *
 * · [LocalLibrary]：用户手动选一个文件夹（SAF）→ 扫成"本地歌曲列表"给界面用；
 * · **本类**：回答一个播放链路里的问题 ——「这首歌我手机上是不是已经有文件了？」
 *
 * 两者数据来源不同（一个是用户导入的曲库，一个是 App 下载目录），
 * 但最终都指向同一个目标：**能在本地读到的，就绝不走网络**。
 *
 * ## 为什么这是最重要的那条后路
 *
 * 外部音源（meting / 第三方脚本 / 内置直连）全部依赖别人的服务器，
 * 而它们这几个月在陆续关停 —— 用户已经亲历"一个歌都听不了"。
 * 本地文件是唯一一条**零依赖**的路：没网、源头全挂、CDN 全 403，它照样能响。
 *
 * ## 匹配策略
 *
 * 1. 平台 id 精确命中（下载时登记过）；
 * 2. 指纹命中：规范化（小写、去标点、去括号内容）后的「歌名 + 歌手」。
 *    给老版本下载的文件和 SAF 导入的文件用 —— 它们没有平台 id，只能靠名字认。
 *
 * 指纹不带时长：`DownloadTask` 里没有可靠的原始时长，
 * 而"同歌名 + 同歌手"在实际曲库里几乎不歧义。宁可多命中一次，
 * 也好过让用户明明有文件却被告知"没版权"。
 */
object LocalAudioIndex {

    private const val TAG = "LocalAudioIndex"
    private const val PREFS = "jun_local_audio_index"
    private const val KEY_INDEX = "index_v1"

    /** 索引上限：一个人手机里存 5000 首都算极多，超了按最旧淘汰 */
    private const val MAX_ENTRIES = 5000

    /** 扫描回填只认这些扩展名，避免把封面图、歌词当成音频 */
    private val AUDIO_EXT = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "ape", "dsf", "dff", "mka"
    )

    @Volatile private var appCtx: Context? = null

    private val lock = Any()
    private val byId = HashMap<String, Entry>()
    private val byFp = HashMap<String, Entry>()
    private var loaded = false

    /**
     * @param id    平台 id（网易云 songId），未知为空
     * @param path  绝对路径，或 content:// URI
     * @param bytes 文件大小，0=未知（只影响占用显示）
     */
    data class Entry(
        val id: String,
        val path: String,
        val title: String,
        val artist: String,
        val bytes: Long,
        val addedAt: Long
    )

    /** 打开结果：流 + 真实总长度（Range 必须知道总长才能算 Content-Range） */
    class Opened(val stream: InputStream, val length: Long)

    // ────────────────────────────────────────────────────────────
    // 规范化
    // ────────────────────────────────────────────────────────────

    /**
     * 指纹规范化：小写、只留字母数字、丢掉括号里的内容。
     *
     * 去括号的原因：同一首歌可能叫「晴天」也可能叫「晴天 (Live)」，
     * 而下载文件名里的括号常被系统改名工具替换，两边对不上。
     * 去括号后两者命中同一指纹 —— 在"找自己下载过的歌"这个场景里，
     * 匹配到自己的 Live 版远好过显示"无版权"。
     */
    private fun norm(s: String): String {
        if (s.isEmpty()) return ""
        val sb = StringBuilder(s.length)
        var depth = 0
        for (c in s) {
            when {
                c == '(' || c == '（' || c == '[' || c == '【' -> depth++
                c == ')' || c == '）' || c == ']' || c == '】' -> if (depth > 0) depth--
                depth > 0 -> {}
                c.isLetterOrDigit() -> sb.append(c.lowercaseChar())
                else -> {}
            }
        }
        return sb.toString()
    }

    private fun fp(title: String, artist: String): String {
        val t = norm(title)
        if (t.isEmpty()) return ""
        val a = norm(artist)
        return if (a.isEmpty()) t else "$t|$a"
    }

    // ────────────────────────────────────────────────────────────
    // 初始化 / 持久化
    // ────────────────────────────────────────────────────────────

    fun init(context: Context) {
        appCtx = context.applicationContext
        ensureLoaded()
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            loaded = true
            runCatching { load() }.onFailure { Log.w(TAG, "索引读取失败，按空索引继续：${it.message}") }
        }
    }

    private fun prefs() = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load() {
        val raw = prefs()?.getString(KEY_INDEX, null) ?: return
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val e = Entry(
                id = o.optString("id").orEmpty(),
                path = o.optString("path").orEmpty(),
                title = o.optString("title").orEmpty(),
                artist = o.optString("artist").orEmpty(),
                bytes = o.optLong("bytes", 0L),
                addedAt = o.optLong("at", 0L)
            )
            if (e.path.isNotEmpty()) index(e)
        }
        Log.i(TAG, "本地播放索引载入：${allLocked().size} 条")
    }

    private fun allLocked(): Map<String, Entry> {
        val all = LinkedHashMap<String, Entry>()
        byId.values.forEach { all[it.path] = it }
        byFp.values.forEach { all[it.path] = it }
        return all
    }

    private fun saveLocked() {
        val p = prefs() ?: return
        val list = allLocked().values.sortedByDescending { it.addedAt }.take(MAX_ENTRIES)
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("path", e.path)
                    .put("title", e.title)
                    .put("artist", e.artist)
                    .put("bytes", e.bytes)
                    .put("at", e.addedAt)
            )
        }
        runCatching { p.edit().putString(KEY_INDEX, arr.toString()).apply() }
    }

    // ────────────────────────────────────────────────────────────
    // 写
    // ────────────────────────────────────────────────────────────

    private fun index(e: Entry) {
        if (e.id.isNotBlank()) byId[e.id] = e
        val k = fp(e.title, e.artist)
        if (k.isNotEmpty()) byFp[k] = e
    }

    /**
     * 登记一个本地文件。
     *
     * 调用点：`DownloadManager` 下载完成、启动扫描回填、SAF 曲库导入后播种。
     *
     * @param id 平台 id，未知传空串（此时只按指纹匹配）
     */
    fun register(id: String?, title: String, artist: String, path: String, bytes: Long = 0L) {
        if (path.isBlank() || title.isBlank()) return
        ensureLoaded()
        synchronized(lock) {
            index(
                Entry(
                    id = id.orEmpty().trim(),
                    path = path,
                    title = title.trim(),
                    artist = artist.trim(),
                    bytes = bytes,
                    addedAt = System.currentTimeMillis()
                )
            )
            saveLocked()
        }
        Log.i(TAG, "登记本地文件：$title / $artist → ${path.takeLast(48)}")
    }

    /** 文件被删除时同步移出索引，避免播放时反复打开一个不存在的路径 */
    fun forget(path: String) {
        if (path.isBlank()) return
        ensureLoaded()
        synchronized(lock) {
            byId.entries.removeAll { it.value.path == path }
            byFp.entries.removeAll { it.value.path == path }
            saveLocked()
        }
    }

    // ────────────────────────────────────────────────────────────
    // 查
    // ────────────────────────────────────────────────────────────

    /**
     * 找这首歌的本地文件。**这是播放链路的入口。**
     *
     * 顺序：平台 id → 指纹。命中后验证文件确实能打开，
     * 避免索引里躺着一条已被系统清理的路径，让播放器每次点这首歌都白等一次失败。
     */
    fun lookup(songId: String?, title: String?, artist: String?): Entry? {
        ensureLoaded()
        val id = songId.orEmpty().trim()
        if (id.isNotEmpty()) {
            val hit = synchronized(lock) { byId[id] }
            if (hit != null) {
                if (exists(hit)) return hit
                synchronized(lock) { byId.remove(id) }
            }
        }
        val k = fp(title.orEmpty(), artist.orEmpty())
        if (k.isNotEmpty()) {
            val hit = synchronized(lock) { byFp[k] }
            if (hit != null) {
                if (exists(hit)) return hit
                synchronized(lock) { byFp.remove(k) }
            }
        }
        return null
    }

    private fun exists(e: Entry): Boolean = runCatching {
        if (e.path.startsWith("content://")) {
            // content:// 无法用 File 判断：用 AssetFileDescriptor 探一次。
            // 拿不到就当失效 —— 宁可回退在线，也不要让播放器面对一个打不开的流。
            val uri = Uri.parse(e.path)
            appCtx?.contentResolver?.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } else {
            File(e.path).let { it.isFile && it.length() > 0L }
        }
    }.getOrDefault(false)

    /**
     * 打开本地文件。返回 null = 这条索引已失效（被删/被清理），
     * 调用方应回退在线解析。
     */
    fun openStream(e: Entry): Opened? = runCatching {
        if (e.path.startsWith("content://")) {
            val ctx = appCtx ?: return null
            val uri = Uri.parse(e.path)
            val len = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            Opened(input, len)
        } else {
            val f = File(e.path)
            if (!f.isFile || f.length() <= 0L) return null
            Opened(FileInputStream(f), f.length())
        }
    }.getOrNull()

    /** 条目数 + 占用字节（设置页显示"本地保底 N 首 / X MB"） */
    fun stats(): Pair<Int, Long> {
        ensureLoaded()
        synchronized(lock) {
            val all = allLocked()
            return all.size to all.values.sumOf { it.bytes }
        }
    }

    // ────────────────────────────────────────────────────────────
    // 回填
    // ────────────────────────────────────────────────────────────

    /**
     * 把 SAF 曲库里已经导入的歌播种进索引。
     *
     * 用户可能已经用「本地音乐 → 选择文件夹」导过一批歌。那些歌在界面上是
     * 独立的本地歌曲，但**在线歌单里的同一首并不会自动用它** ——
     * 索引打通之后，在线歌单点下去会直接命中本地文件，秒开且不耗流量。
     */
    fun seedFromLocalSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        ensureLoaded()
        var n = 0
        synchronized(lock) {
            for (s in songs) {
                val scheme = s.uri.scheme.orEmpty()
                if (scheme != "file" && scheme != "content") continue
                val path = s.uri.toString()
                if (path.isBlank()) continue
                if (byFp.values.any { it.path == path }) continue
                index(
                    Entry(
                        id = s.songId.orEmpty(),
                        path = path,
                        title = s.title,
                        artist = s.artist,
                        bytes = 0L,
                        addedAt = System.currentTimeMillis()
                    )
                )
                n++
            }
            if (n > 0) saveLocked()
        }
        if (n > 0) Log.i(TAG, "从 SAF 曲库播种 $n 条")
    }

    /**
     * **启动扫描**：把升级前就下载好的文件补进索引。
     *
     * 老用户的下载目录里躺着一堆 `歌名 - 歌手.mp3`，而索引是空的。
     * 不做这一步，本功能对他们等于没上线 —— 而这恰恰是最需要它的人。
     *
     * 文件名格式来自 `DownloadManager`：`"$safeName - $safeArtist.$ext"`。
     * 用**最后一个** " - " 分割（歌名本身常带 " - "，歌手名基本不会）。
     * safeName 替换过非法字符，指纹规范化同样剔除标点，所以两边能对上。
     *
     * 只扫两层（根 + 一层子目录）：全盘遍历在几千首歌的手机上要跑几十秒，
     * 那是拿用户的开机时间去补一个边缘场景。
     */
    fun scanDownloads(extraDirs: List<String> = emptyList()) {
        val ctx = appCtx ?: return
        ensureLoaded()

        val dirs = LinkedHashSet<File>()
        runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)?.let { dirs.add(it) }
        }
        runCatching { ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { dirs.add(it) } }
        extraDirs.filter { it.isNotBlank() && !it.startsWith("content://") }
            .forEach { runCatching { dirs.add(File(it)) } }

        var added = 0
        for (d in dirs) {
            if (!d.isDirectory) continue
            val files = runCatching { d.listFiles() }.getOrNull() ?: continue
            for (f in files) {
                if (f.isDirectory) {
                    runCatching { f.listFiles() }.getOrNull()?.forEach { sub ->
                        if (sub.isFile && registerFromFile(sub)) added++
                    }
                    continue
                }
                if (registerFromFile(f)) added++
            }
        }
        if (added > 0) Log.i(TAG, "扫描回填完成，新增 $added 条")
    }

    private fun registerFromFile(f: File): Boolean {
        if (!f.isFile || f.length() <= 0L) return false
        if (f.extension.lowercase() !in AUDIO_EXT) return false
        val name = f.nameWithoutExtension
        val cut = name.lastIndexOf(" - ")
        if (cut <= 0) return false
        val title = name.substring(0, cut).trim()
        val artist = name.substring(cut + 3).trim()
        if (title.isEmpty()) return false
        val known = synchronized(lock) { byFp.values.any { it.path == f.absolutePath } }
        if (known) return false
        register("", title, artist, f.absolutePath, f.length())
        return true
    }

    /**
     * 下载完成后让系统媒体库也知道这个文件。
     *
     * 不做这一步的后果：文件在 `Music/` 里，但系统音乐播放器、车机、
     * 以及某些文件管理器都看不见它 —— 用户会以为"下载失败了"。
     */
    fun notifyMediaStore(ctx: Context, path: String) {
        if (path.isBlank() || path.startsWith("content://")) return
        runCatching {
            MediaScannerConnection.scanFile(ctx, arrayOf(path), null) { _, _ -> }
        }
        // 部分机型（尤其国产 ROM）不响应 scanFile，补一条广播兜底
        runCatching {
            ctx.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(File(path)))
            )
        }
    }
}
