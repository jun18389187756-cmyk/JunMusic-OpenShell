package com.jun.musiccloud.playback

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.jun.musiccloud.data.DownloadStatus
import com.jun.musiccloud.data.DownloadTask
import com.jun.musiccloud.data.OnlineMusicApi
import com.jun.musiccloud.data.Song
import com.jun.musiccloud.player.PlayerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 下载管理：把「下载」这件自成一块的事从 PlayerViewModel 里搬出来。
 *
 * ViewModel 原本 3700 行，下载独占 300 行——任务表、系统 DownloadManager、
 * 手动 HTTP 兜底、进度轮询、目录选择，全是内聚的下载逻辑，和播放控制没关系。
 * 搬出来后 ViewModel 只留一行转发，下载相关的改动都在这个文件里闭环。
 *
 * 状态仍然是同一个 [PlayerUiState]（这里拿到的是同一个 StateFlow 引用），
 * 所以 UI 层不需要任何改动——这是能安全搬走的前提。
 *
 * 自定义目录的坑：系统文件选择器给的是 SAF `content://…/tree/…` 授权，不是文件路径。
 * 以前代码把它当 File 用（`File(custom)` + `setDestinationUri(Uri.fromFile(…))`），
 * 在现代 Android 上系统下载器根本不接受 file:// 目标，所以「自定义路径 100% 失败」。
 * 现在分三条路：
 *  - 默认路径：继续交给系统 DownloadManager（免存储权限、通知栏可见）
 *  - content:// 目录：自己下到缓存再通过 ContentResolver 写进用户选的文件夹
 *  - 真实文件路径（历史遗留）：手动 HTTP 直写
 *
 * R13 补的第三块：下载记录落盘 + 冷启动按磁盘真相同步。
 * 之前 [PlayerUiState.downloadTasks] 只活在内存里，进程一死就整份没了（prefs 里只存了
 * 目录和"已确认"两个开关）。用户下载 58 首、杀掉 App 再进来，"下载管理"显示 0 首——
 * 不是文件没了，是**记录**没了。现在：
 *  - 任务进入 COMPLETED / FAILED 就写进 prefs 的 JSON 记录表；
 *  - 冷启动（以及用户进下载页）先扫一次下载目录 + MediaStore，与记录合并；
 *  - 只有确认目录读到了、而记录里的文件不在，才删这条记录（目录都扫不到时一律保留，
 *    避免"listFiles() 返回 null 就清零"把用户的 58 首又抹成 0）；
 *  - 同一首歌（按 songId / 网易云 id / 文件名）只算一条，重复下载不重复计数。
 */
internal class DownloadManager(
    private val app: Application,
    private val prefs: SharedPreferences,
    private val state: MutableStateFlow<PlayerUiState>,
    private val scope: CoroutineScope
) {

    /** 默认下载目录：公共 Music/Jun Music */
    private fun defaultDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
        "Jun Music"
    ).apply { mkdirs() }

    /**
     * 同时在跑的下载数。
     *
     * 6.1.2：批量下载把这里收成 3 路。全选 200 首直接开 200 个协程时，最重的不是写盘而是
     * 解析直链那一轮（每首都要走一遍网易云接口 + jOOX 兜底），nova 6 这种 4G 内存的机器
     * 会一起卡在解析上，进度条全体不动，看起来像"点了没反应"。
     * 排在后面的任务保持 PENDING，在下载管理页里就是"排队中"，读得通。
     */
    private val downloadPermits = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    /**
     * 任务 id。
     *
     * 原来是裸的 `System.currentTimeMillis()`：单首下载没问题，批量循环在同一毫秒里入队
     * 十几首会拿到同一个 id，而 [updateTask] 是 `map { if (it.id == taskId) … }`——
     * 一条进度会把十几行一起改掉，[mergeBy] 那句 `it.id != task.id` 也会误删别人的记录。
     * 这里保证单调递增，同一毫秒内继续 +1。
     */
    private var lastTaskId = 0L

    private fun newTaskId(): Long = synchronized(this) {
        val now = System.currentTimeMillis()
        lastTaskId = if (now > lastTaskId) now else lastTaskId + 1
        lastTaskId
    }

    /**
     * 下载歌曲。
     * - 在线歌曲：解析真实音频 URL（网易云 → jOOX 兜底）后下载
     * - 本地歌曲：直接复制到下载目录，立即完成
     * 下载任务会记录到 [PlayerUiState.downloadTasks]，可在设置-下载管理中查看。
     */
    fun downloadSong(song: Song) = enqueue(song, announce = true)

    /**
     * 入队一首。
     *
     * @param announce 是否给这一首单独发一条提示。单曲入口要（用户点了就得回一句），
     *   批量不要——几十个提示会在同一个毫秒里互相顶掉，最后只剩随机一条，
     *   批量改由调用方汇总成一句「新增 N · 已在队列 M · 跳过已下载 K」。
     */
    private fun enqueue(song: Song, announce: Boolean) {
        val taskId = newTaskId()
        scope.launch {
            // 先创建任务记录
            val task = DownloadTask(
                id = taskId,
                songId = song.id,
                title = song.title,
                artist = song.artist,
                neteaseId = song.songId,
                status = DownloadStatus.PENDING
            )
            // R13：同一首歌重复下载不再叠加第二条记录。
            // 以前这里是 `downloadTasks + task`，同一首歌点两次就在列表里出现两行、
            // "已下载"计数也跟着翻倍；重扫磁盘时又会按文件名再补一条，越用越乱。
            // 现在按身份键（songId → 网易云 id → 歌名+歌手）先摘掉旧的那条再放新的。
            val key = identityOf(task)
            val merged = mergeBy(key, state.value.downloadTasks, task)
            state.value = state.value.withExtras { copy(downloadTasks = merged) }
            // 单曲入口立刻落一次盘：重下一首"已完成"的歌会先摘掉那条老记录，
            // 中途进程被杀就不该留下一个指向半截文件的「已下载」。
            // 批量入口跳过这一步——downloadSongsBatch 已经不会碰 COMPLETED 的身份，
            // 而 persistTasks 只写 COMPLETED，所以对批量这一下是纯空转；
            // 代价却是主线程把整张记录表（上限 800 条）序列化 N 次，全选 200 首就是 200 次。
            if (announce) persistTasks(merged)

            val notice = withContext(Dispatchers.IO) {
                runCatching {
                    // 许可在 IO 里等：排队中的几十首只是挂起，不占线程
                    downloadPermits.withPermit { performDownload(song, taskId) }
                }.getOrElse {
                    updateTask(taskId) { t -> t.copy(status = DownloadStatus.FAILED) }
                    "下载失败：${it.message ?: "请重试"}"
                }
            }
            if (announce) {
                state.value = state.value.withExtras {
                    copy(
                        downloadNotice = notice,
                        downloadNoticeTick = state.value.downloadNoticeTick + 1
                    )
                }
            }
        }
    }

    /**
     * 一条下载记录的身份键：判定"是不是同一首歌的同一份下载"。
     * 优先 songId（本地稳定 ID），其次网易云 ID（在线曲目），都没有就退回"歌名 + 歌手"
     * ——重扫磁盘恢复出来的记录只有文件名，必须也能跟内存里的记录对上，否则同一首歌
     * 会在列表里出现两份（一份带 songId、一份没有）。
     */
    private fun identityOf(t: DownloadTask): String {
        val title = t.title.trim().lowercase()
        val artist = t.artist.trim().lowercase()
        return when {
            t.songId > 0L -> "S${t.songId}"
            t.neteaseId.isNotBlank() -> "N${t.neteaseId}"
            else -> "T$title|$artist"
        }
    }

    /** 用新任务替换列表里同一身份的老任务（老任务不在列表里就等于追加） */
    /**
     * 6.7.1：按来源域名给 Referer。
     *
     * 和播放代理里的 [com.jun.musiccloud.playback.AudioProxyServer] 保持同一套规则：
     * 播放能过、下载就必须能过，否则就是"能听不能下"。
     */
    private fun refererFor(url: String): String = when {
        url.contains("qqmusic") || url.contains("qq.com") -> "https://y.qq.com/"
        url.contains("kuwo") -> "http://www.kuwo.cn/"
        url.contains("kugou") -> "https://www.kugou.com/"
        url.contains("migu") -> "https://music.migu.cn/"
        url.contains("joox") -> "https://www.joox.com/"
        url.contains("126.net") || url.contains("music.163") -> "https://music.163.com/"
        else -> "https://music.163.com/"
    }

    /** 认识的音频容器。判断标准只有一个：写进文件名后播放器能认出来 */
    private val AUDIO_EXTS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "wma", "opus", "ape")

    private fun mergeBy(key: String, list: List<DownloadTask>, task: DownloadTask): List<DownloadTask> {
        val kept = list.filterNot { identityOf(it) == key && it.id != task.id }
        return kept + task
    }

    /** 实际下载流程（IO 线程），返回给用户看的提示 */
    private fun performDownload(song: Song, taskId: Long): String {
        val custom = state.value.downloadPath
        val safeName = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "未命名" }
        val safeArtist = song.artist.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "未知艺术家" }

        if (song.songId.isNotEmpty()) {
            // 在线歌曲：解析真实 URL 后再下载（带歌名/歌手，没版权时自动走 jOOX）
            //
            // 6.3：下载格式不再只跟播放音质走。用户在设置里选「只要 MP3 / 只要无损 FLAC」
            // 时，这里显式要求对应容器。
            //
            // 6.3 修（用户反馈「换任何音质下来都是 8 点多 MB，一模一样」）：
            // FOLLOW 原来传 preferFormat=null，结果落到旧链路，而那一条**只有一档**，
            // 所以三个选项拿到的是同一个文件。现在 FOLLOW 也把当前播放音质显式带上，
            // 由 OnlineMusicApi 映射成真实档位去取。
            val wantFormat = com.jun.musiccloud.data.DownloadFormatStore.load(app)
            // 6.6.7：**FLAC 拿不到就自动回退 MP3**（用户原话：下不了就默认到 MP3，
            // 别让用户对着"下载失败"去找设置在哪）。
            //
            // 以前选了「只要无损 FLAC」而所有音源都只有 mp3 时，这里直接判失败，
            // 提示里还让用户"改选跟随音质"——批量下载几十首的人根本不会去看提示，
            // 结果就是"什么都没下下来"。
            // 现在：先按用户要的格式问一遍（尊重明确意愿），拿不到无损就**自动**
            // 用 MP3 再问一遍，落盘照常进行，只在通知里说一句"已自动存为 MP3"。
            var resolved = OnlineMusicApi.resolveAudio(
                id = song.songId,
                title = song.title,
                artist = song.artist,
                quality = com.jun.musiccloud.data.AudioQualityStore.current(),
                preferFormat = when (wantFormat) {
                    com.jun.musiccloud.data.DownloadFormat.FLAC -> "flac"
                    com.jun.musiccloud.data.DownloadFormat.MP3 -> "mp3"
                    // 跟音质走：不强制容器，但由 quality 决定档位（不能再传 null 让它掉回单档旧链路）
                    com.jun.musiccloud.data.DownloadFormat.FOLLOW -> null
                }
            )
            var fellBackToMp3 = false
            if (wantFormat == com.jun.musiccloud.data.DownloadFormat.FLAC &&
                (resolved == null || resolved.format?.lowercase() != "flac")
            ) {
                fellBackToMp3 = true
                resolved = OnlineMusicApi.resolveAudio(
                    id = song.songId,
                    title = song.title,
                    artist = song.artist,
                    quality = com.jun.musiccloud.data.AudioQualityStore.current(),
                    preferFormat = "mp3"
                ) ?: resolved
            }
            val url = resolved?.url
            if (url.isNullOrEmpty()) {
                updateTask(taskId) { it.copy(status = DownloadStatus.FAILED) }
                return "下载失败：无法获取音频地址（可能是 VIP / 版权限制）"
            }
            val gotFormat = resolved?.format?.lowercase()
            if (fellBackToMp3 && gotFormat != "flac") {
                // 无损没找到已自动降级：不在这里打断流程，落盘后把提示补在通知里
            }
            // 扩展名优先用解析结果里的真实格式（FLAC 直链常常没有 .flac 后缀，
            // 以前一律从 URL 猜，结果无损文件被存成 .mp3，车机当垃圾文件跳过）
            val urlExt = url.substringBefore('?').substringAfterLast('.', "")
                .lowercase()
                .let { if (it.length > 5 || !it.matches(Regex("[a-z0-9]+"))) "" else it }
            val guessExt = gotFormat?.takeIf { it in AUDIO_EXTS }
                ?: urlExt.takeIf { it in AUDIO_EXTS }
                ?: wantFormat.fallbackExt
            // 文件名永远按「真实拿到的格式」写，绝不按用户愿望写。
            // 想让 FLAC 却只拿到 mp3 时，存成 .flac 是更坏的结果：播放器按 FLAC 解会失败，
            // 用户还以为自己拿到了无损。给 mp3 就老实叫 mp3。
            val fileName = "$safeName - $safeArtist.$guessExt"

            updateTask(taskId) { it.copy(status = DownloadStatus.DOWNLOADING, progress = 0) }
            val saveResult = when {
                custom.isBlank() -> saveOnlineToDefault(url, fileName, taskId, song.title, guessExt)
                custom.startsWith("content://") -> saveOnlineToTree(custom, url, fileName, taskId)
                else -> saveOnlineToDir(File(custom), url, fileName, taskId)
            }
            // 6.6.7：自动降级要在通知里说清楚，但绝不算失败
            return if (fellBackToMp3 && !saveResult.startsWith("下载失败")) {
                "$saveResult（无损没找到，已自动存为 MP3）"
            } else saveResult
        }

        // 本地 SAF 歌曲：filePath 存的是封面，真正的音频在 song.uri（content://）
        val ext = song.format.lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "mp3"
        val fileName = "$safeName - $safeArtist.$ext"
        val src: InputStream? = if (song.uri.scheme == "content") {
            runCatching { app.contentResolver.openInputStream(song.uri) }.getOrNull()
        } else {
            runCatching { File(song.uri.path.orEmpty()).takeIf { it.exists() }?.inputStream() }.getOrNull()
        }
        if (src == null) {
            updateTask(taskId) { it.copy(status = DownloadStatus.FAILED) }
            return "下载失败：源文件不存在"
        }
        updateTask(taskId) { it.copy(status = DownloadStatus.DOWNLOADING, progress = 40) }
        return src.use { input ->
            when {
                custom.startsWith("content://") -> {
                    val doc = createDocument(Uri.parse(custom), fileName)
                    if (doc == null) {
                        updateTask(taskId) { it.copy(status = DownloadStatus.FAILED) }
                        return@use "下载失败：自定义目录不可写，请重新选择下载目录"
                    }
                    val ok = runCatching {
                        app.contentResolver.openOutputStream(doc)?.use { out ->
                            input.copyTo(out, 128 * 1024)
                            out.flush()
                        } != null
                    }.getOrDefault(false)
                    if (!ok) {
                        runCatching { DocumentsContract.deleteDocument(app.contentResolver, doc) }
                        updateTask(taskId) { it.copy(status = DownloadStatus.FAILED) }
                        return@use "下载失败：写入自定义目录失败"
                    }
                    updateTask(taskId) {
                        it.copy(
                            status = DownloadStatus.COMPLETED,
                            progress = 100,
                            localPath = doc.toString(),
                            fileSize = runCatching {
                                app.contentResolver.openAssetFileDescriptor(doc, "r")?.use { fd -> fd.length }
                            }.getOrNull() ?: 0L
                        )
                    }
                    "已保存到自定义文件夹：$fileName"
                }
                else -> {
                    val dir = if (custom.isBlank()) defaultDir() else File(custom).apply { mkdirs() }
                    val target = File(dir, fileName)
                    val ok = runCatching {
                        target.outputStream().use { out -> input.copyTo(out, 128 * 1024) }
                        true
                    }.getOrDefault(false)
                    if (!ok) {
                        updateTask(taskId) { it.copy(status = DownloadStatus.FAILED) }
                        return@use "下载失败：目录不可写"
                    }
                    updateTask(taskId) {
                        it.copy(
                            status = DownloadStatus.COMPLETED,
                            progress = 100,
                            localPath = target.absolutePath,
                            fileSize = target.length()
                        )
                    }
                    "已保存到 ${target.parentFile?.name ?: "下载目录"}"
                }
            }
        }
    }

    /** 默认公共目录：交给系统 DownloadManager（不需要存储权限，通知栏有进度） */
    private fun saveOnlineToDefault(
        url: String,
        fileName: String,
        taskId: Long,
        title: String,
        ext: String = "mp3"
    ): String {
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val targetFile = File(defaultDir(), fileName)
        // 6.3 修：以前不管什么格式都写死 audio/mpeg，下载完系统相册/文件管理器会把
        // 32MB 的 flac 也标成 mp3，车机按 mp3 解就失败。按真实扩展名给 MIME。
        val mime = when (ext.lowercase()) {
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            else -> "audio/mpeg"
        }
        val req = android.app.DownloadManager.Request(Uri.parse(url))
            .setTitle("$title - Jun Music")
            .setDescription("Jun Music 下载")
            .setMimeType(mime)
            .addRequestHeader("User-Agent", DL_UA)
            // 6.7.1：**这里原来是下载全失败的头号原因**。
            // Referer 只区分了 joox 和"其它一律 music.163.com"，而脚本音源给的
            // 地址绝大多数是 QQ / 咪咕 / 酷我 / 酷狗 CDN —— 它们校验 Referer，
            // 给网易云的来源就是防盗链 403。系统下载管理器只会报"下载失败"，
            // 而同一首在 App 里能正常播放（播放走代理，代理是按域名给 Referer 的）。
            // 这就是"能听不能下"的全部真相。
            .addRequestHeader("Referer", refererFor(url))
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_MUSIC,
                "Jun Music/$fileName"
            )
        val dmId = dm.enqueue(req)
        pollDownloadProgress(dm, dmId, taskId, targetFile.absolutePath)
        return "开始下载：$title"
    }

    /** 真实文件路径（非 SAF）：手动 HTTP 直写，可控文件名与断点续传 */
    private fun saveOnlineToDir(dir: File, url: String, fileName: String, taskId: Long): String {
        if (!dir.exists() && !dir.mkdirs()) {
            updateTask(taskId) { it.copy(status = DownloadStatus.FAILED) }
            return "下载失败：目录不可写"
        }
        val target = File(dir, fileName)
        val ok = downloadFileHttp(url, target.absolutePath) { done, total ->
            val progress = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 99) else 0
            if (progress > 0) updateTask(taskId) { it.copy(progress = progress) }
        }
        if (!ok || !target.exists() || target.length() <= 0L) {
            updateTask(taskId) { it.copy(status = DownloadStatus.FAILED) }
            return "下载失败：网络异常或版权受限"
        }
        updateTask(taskId) {
            it.copy(
                status = DownloadStatus.COMPLETED,
                progress = 100,
                localPath = target.absolutePath,
                fileSize = target.length()
            )
        }
        return "已保存到 ${dir.name}"
    }

    /**
     * SAF 自定义目录：先下到缓存，再写进用户授权的那棵树。
     *
     * 系统 DownloadManager 只接受外部存储的 file 目标，`content://` 树它写不了，
     * 所以自定义目录必须自己落盘。缓存文件确保「下载失败不会留下半个文件」。
     */
    private fun saveOnlineToTree(tree: String, url: String, fileName: String, taskId: Long): String {
        val temp = File(app.cacheDir, "jun_dl_${taskId}.tmp")
        return try {
            val ok = downloadFileHttp(url, temp.absolutePath) { done, total ->
                val progress = if (total > 0) ((done * 88) / total).toInt().coerceIn(0, 88) else 0
                updateTask(taskId) { it.copy(progress = progress) }
            }
            if (!ok || temp.length() <= 0L) {
                updateTask(taskId) { it.copy(status = DownloadStatus.FAILED) }
                return "下载失败：网络异常或版权受限"
            }
            updateTask(taskId) { it.copy(progress = 92) }
            val doc = createDocument(Uri.parse(tree), fileName)
            if (doc == null) {
                updateTask(taskId) { it.copy(status = DownloadStatus.FAILED) }
                return "下载失败：自定义目录不可写，请在设置里重新选择下载目录"
            }
            val copied = runCatching {
                app.contentResolver.openOutputStream(doc)?.use { out ->
                    FileInputStream(temp).use { input -> input.copyTo(out, 128 * 1024) }
                    out.flush()
                } != null
            }.getOrDefault(false)
            if (!copied) {
                runCatching { DocumentsContract.deleteDocument(app.contentResolver, doc) }
                updateTask(taskId) { it.copy(status = DownloadStatus.FAILED) }
                return "下载失败：写入自定义目录失败"
            }
            updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    localPath = doc.toString(),
                    fileSize = temp.length()
                )
            }
            "已保存到自定义文件夹：$fileName"
        } finally {
            temp.delete()
        }
    }

    /**
     * 在用户选择的文件夹里建文件；同名旧文件先删掉，避免「重下变成 (1)」。
     * 用 application/octet-stream 是为了让提供方原样保留带扩展名的文件名。
     */
    private fun createDocument(tree: Uri, fileName: String): Uri? = runCatching {
        val resolver = app.contentResolver
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        findChild(tree, parentId, fileName)?.let { old ->
            runCatching { DocumentsContract.deleteDocument(resolver, old) }
        }
        DocumentsContract.createDocument(resolver, parent, "application/octet-stream", fileName)
    }.getOrNull()

    private fun findChild(tree: Uri, parentDocId: String, name: String): Uri? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        app.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val display = c.getString(1) ?: continue
                if (display.equals(name, ignoreCase = true)) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                }
            }
            null
        }
    }.getOrNull()

    /**
     * 一条歌的下载身份键（和 [identityOf] 对 [DownloadTask] 的算法完全一致）。
     * 批量入队前用它判重：勾进来的 [Song] 还没有对应任务记录，必须先算出它会落成哪个键，
     * 才能和 [PlayerUiState.downloadTasks] 里的老记录对上。
     */
    private fun identityOf(song: Song): String {
        val title = song.title.trim().lowercase()
        val artist = song.artist.trim().lowercase()
        return when {
            song.id > 0L -> "S${song.id}"
            song.songId.isNotBlank() -> "N${song.songId}"
            else -> "T$title|$artist"
        }
    }

    /**
     * 一批下载的统计（界面靠它出一句话，不再一首一条提示互相顶掉）。
     *
     * @param added 新入队几首
     * @param queued 本来就在排队 / 正在下，或者这次勾选里重复出现，没有重复入队
     * @param downloaded 已经下载好了，直接跳过（绝不重跑一遍）
     * @param noSpace 目标目录剩余空间明显放不下这一批，整批没有入队
     * @param noPerm 自定义下载目录那棵 SAF 树的写权限已经没了，整批没有入队
     */
    data class BatchCount(
        val added: Int,
        val queued: Int,
        val downloaded: Int,
        val noSpace: Boolean = false,
        val noPerm: Boolean = false
    )

    /**
     * 批量下载：所有「全选 → 下载」的入口都走这一个口。
     *
     * 原来的 [downloadSongs] 就是 `songs.forEach { downloadSong(it) }`：
     * 同一首歌勾两次入队两次、已经下载好的也照样重新下一遍、几十首同时开 HTTP、
     * 每首还各自发一条提示（同一个毫秒里互相顶掉，最后只剩随机一条）。
     * 这里只做"该不该下"的判断，怎么下仍然复用 [enqueue] → [performDownload]
     * 那套现成流程（解析直链、系统下载器、SAF、进度、记录落盘），不另起第二套下载系统。
     *
     * 注意：本函数**不**发提示（返回统计给调用方汇总），所以批量之后界面上不会出现
     * 几十条下载回执；单首入口 [downloadSong] 保持原样，仍然当场回一句下到哪了。
     */
    fun downloadSongsBatch(songs: List<Song>): BatchCount {
        if (songs.isEmpty()) return BatchCount(0, 0, 0)
        // 现场快照：状态由各自的协程在改，这里只在调用线程读一次，之后靠 seen 兜住本次内部重复
        val statusByKey = HashMap<String, DownloadStatus>()
        state.value.downloadTasks.forEach { statusByKey[identityOf(it)] = it.status }
        // 授权预检放在空间预检前面：SAF 树被收回时 hasRoomFor 一律放行（content:// 拿不到
        // StatFs），于是整批照样入队、逐首失败，回执却写着「已开始下载 N 首」——
        // 用户看见的是「说了下载却没下来，也不知道为什么」。这一条必须在入队前判掉。
        if (!treeStillWritable()) {
            return BatchCount(0, 0, 0, noPerm = true)
        }
        if (!hasRoomFor(songs.count { statusByKey[identityOf(it)] != DownloadStatus.COMPLETED })) {
            return BatchCount(0, 0, 0, noSpace = true)
        }
        val seen = HashSet<String>()
        var added = 0
        var queued = 0
        var downloaded = 0
        songs.forEach { s ->
            val key = identityOf(s)
            when {
                // 这一次勾选里出现过：不再开第二条任务
                !seen.add(key) -> queued++
                // 已经下载好：直接跳过。用户要的从来不是"再下一遍"，
                // 重复覆盖还会把下载管理页的"已下载"计数抖一下（老记录被摘掉再补回来）
                statusByKey[key] == DownloadStatus.COMPLETED -> downloaded++
                // 正在下 / 排队中：复用现有那条，不再新开
                statusByKey[key] == DownloadStatus.PENDING ||
                    statusByKey[key] == DownloadStatus.DOWNLOADING -> queued++
                else -> {
                    // FAILED 落到这里：上次没下成，这次点了就该重试
                    statusByKey[key] = DownloadStatus.PENDING
                    enqueue(s, announce = false)
                    added++
                }
            }
        }
        return BatchCount(added, queued, downloaded)
    }

    /**
     * 空间预检：只在"明显放不下"时拦下整批，读不到就一律放行。
     *
     * 这一层存在的意义是别把用户带进"点了没反应"：SAF 目录没授权、路径不可写、
     * 磁盘已满这几类失败发生在几十首里，逐条报只会糊成一片。
     * content:// 目标拿不到 StatFs，无法估——照旧放行，交给各自的写入去报失败。
     */
    /**
     * 自定义下载目录（SAF `content://…/tree/…`）现在还能不能写。
     *
     * 用户可以在系统设置里撤销授权，也可能隔了几个月回来那份持久授权已经没了。
     * 不是 content:// 目标（默认目录、历史遗留的真实路径）一律放行，
     * 读权限列表这一步本身失败时也放行——宁可让下面逐首去撞真实的写失败，
     * 也不要因为一次查询异常就整批不给下。
     */
    private fun treeStillWritable(): Boolean {
        val custom = state.value.downloadPath
        if (!custom.startsWith("content://")) return true
        val tree = runCatching { Uri.parse(custom) }.getOrNull() ?: return false
        return runCatching {
            app.contentResolver.persistedUriPermissions.any {
                it.uri == tree && it.isWritePermission && it.persistedTime > 0L
            }
        }.getOrDefault(true)
    }

    private fun hasRoomFor(count: Int): Boolean {
        if (count <= 0) return true
        val custom = state.value.downloadPath
        if (custom.startsWith("content://")) return true
        val dir = runCatching { if (custom.isNotBlank()) File(custom) else defaultDir() }.getOrNull()
            ?: return true
        return runCatching {
            val fs = StatFs(dir.absolutePath)
            fs.availableBytes > count * EST_BYTES_PER_SONG
        }.getOrDefault(true)
    }

    /** 批量下载多首歌（不汇总统计的老接口：交给 [downloadSongsBatch]，仍不返回结果） */
    fun downloadSongs(songs: List<Song>) {
        downloadSongsBatch(songs)
    }

    /** 更新某个下载任务的状态 */
    private fun updateTask(taskId: Long, transform: (DownloadTask) -> DownloadTask) {
        val updated = state.value.downloadTasks.map {
            if (it.id == taskId) transform(it) else it
        }
        state.value = state.value.withExtras { copy(downloadTasks = updated) }
        // R13：只有走到终态（完成 / 失败）才落盘。
        // 进度回调是 800ms 一次，每次都序列化整张记录表写 prefs 是白扔 IO 和闪存寿命；
        // 终态写一次，重启后就靠这条记录 + 磁盘扫描恢复。
        val finished = updated.firstOrNull { it.id == taskId }
        if (finished != null &&
            (finished.status == DownloadStatus.COMPLETED || finished.status == DownloadStatus.FAILED)
        ) {
            persistTasks(updated)

            // ────────────────────────────────────────────────────────
            // 6.8.0：下载完成 → **登记到本地播放索引**。
            //
            // 这里是所有下载路径（默认目录 / SAF 自定义目录 / 指定文件夹）
            // 唯一的终态收口点，改一处就能覆盖全部。
            //
            // 不登记的话，下载下来的文件和 App 毫无关系：源头一挂，
            // 明明手机上躺着一百多首也一首放不出来（用户已亲历）。
            // 登记之后，代理在发出任何网络请求之前先查这里，命中就直接发文件。
            // ────────────────────────────────────────────────────────
            if (finished.status == DownloadStatus.COMPLETED && finished.localPath.isNotBlank()) {
                runCatching {
                    com.jun.musiccloud.data.LocalAudioIndex.init(app)
                    com.jun.musiccloud.data.LocalAudioIndex.register(
                        id = finished.neteaseId,
                        title = finished.title,
                        artist = finished.artist,
                        path = finished.localPath,
                        bytes = finished.fileSize
                    )
                    com.jun.musiccloud.data.LocalAudioIndex.notifyMediaStore(app, finished.localPath)
                }.onFailure { android.util.Log.w("DownloadManager", "本地索引登记失败：${it.message}") }
            }
        }
    }

    /**
     * 手动 HTTP 下载文件。
     * 相比系统 DownloadManager，完整控制文件名与扩展名，避免重定向导致的命名错误。
     * @return true 下载成功，false 失败
     */
    private fun downloadFileHttp(
        urlStr: String,
        destPath: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): Boolean {
        return runCatching {
            val dest = File(destPath)
            dest.parentFile?.mkdirs()
            val tmpFile = File(destPath + ".part")
            var conn: java.net.HttpURLConnection? = null
            try {
                // 断点续传：上次残留的 .part 不为空，就从上次断点接着下，
                // 弱网 / 切后台被杀之后重下不用从头再来一遍。
                val resumeFrom = if (tmpFile.exists() && tmpFile.length() > 0) tmpFile.length() else 0L
                var url = java.net.URL(urlStr)
                // 最多跟随 5 次重定向
                for (i in 0..5) {
                    conn = url.openConnection() as java.net.HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.setRequestProperty("User-Agent", DL_UA)
                    conn.setRequestProperty("Accept", "*/*")
                    conn.setRequestProperty("Referer", refererFor(url.toString()))
                    // 请求续传：服务器支持则返回 206，从断点继续；不支持就 200 重头下
                    if (resumeFrom > 0L) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location") ?: break
                        conn.disconnect()
                        // 6.7.1：Location 可能是相对路径（`/path/x.mp3`），
                        // 直接 new URL(location) 会抛 MalformedURLException，
                        // 表现为"下载失败"但日志里什么都没有。
                        url = try {
                            java.net.URL(java.net.URL(url.toString()), location)
                        } catch (e: Exception) {
                            break
                        }
                        continue
                    }
                    if (code == 206 || (code in 200..299 && resumeFrom == 0L)) {
                        // ok，继续
                    } else if (code in 200..299) {
                        // 服务器不支持 Range，忽略断点从头下：先把残留 .part 清掉
                        resumeFrom.let { if (it > 0L) tmpFile.delete() }
                    } else {
                        conn.disconnect()
                        return false
                    }
                    break
                }
                if (conn == null) return false
                val isResume = resumeFrom > 0L && conn.responseCode == 206
                // 206 下 contentLengthLong 是「剩余」字节，要加上已下部分才是总量
                val remaining = conn.contentLengthLong
                val total = if (remaining <= 0) -1L else (if (isResume) resumeFrom + remaining else remaining)
                val initial = if (isResume) resumeFrom else 0L
                conn.inputStream.use { input ->
                    // 续传时追加写（FileOutputStream 第二参 true）；不支持 Range 时已清过 .part，覆盖写
                    java.io.FileOutputStream(tmpFile, isResume).use { output ->
                        val buf = ByteArray(8192)
                        var downloaded = initial
                        var lastReport = initial
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            // 每 100KB 回调一次进度，避免频繁更新
                            if (downloaded - lastReport > 100 * 1024) {
                                lastReport = downloaded
                                onProgress(downloaded, total)
                            }
                        }
                        output.flush()
                        onProgress(downloaded, total)
                    }
                }
                conn.disconnect()
                // 下载完成，重命名临时文件
                if (dest.exists()) dest.delete()
                tmpFile.renameTo(dest)
                true
            } catch (e: Exception) {
                conn?.disconnect()
                // 出错保留 .part，下次可续传（除非文件已损坏过大——简单起见保留）
                false
            }
        }.getOrDefault(false)
    }

    /** 轮询 DownloadManager 下载进度 */
    private fun pollDownloadProgress(
        dm: android.app.DownloadManager,
        dmId: Long,
        taskId: Long,
        targetPath: String
    ) {
        scope.launch(Dispatchers.IO) {
            val query = android.app.DownloadManager.Query().setFilterById(dmId)
            while (true) {
                val cursor = dm.query(query)
                if (cursor == null) break
                if (!cursor.moveToFirst()) {
                    cursor.close()
                    break
                }
                val statusIdx = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)
                val bytesIdx = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val status = cursor.getInt(statusIdx)
                val downloaded = cursor.getLong(bytesIdx)
                val total = cursor.getLong(totalIdx)
                cursor.close()

                when (status) {
                    android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                        val file = File(targetPath)
                        updateTask(taskId) {
                            it.copy(
                                status = DownloadStatus.COMPLETED,
                                progress = 100,
                                localPath = targetPath,
                                fileSize = if (file.exists()) file.length() else total
                            )
                        }
                        break
                    }
                    android.app.DownloadManager.STATUS_FAILED -> {
                        updateTask(taskId) { it.copy(status = DownloadStatus.FAILED) }
                        break
                    }
                    else -> {
                        val progress = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 99) else 0
                        updateTask(taskId) {
                            it.copy(status = DownloadStatus.DOWNLOADING, progress = progress)
                        }
                    }
                }
                kotlinx.coroutines.delay(800)
            }
        }
    }

    /** 删除下载记录（不删除已下载的文件，除非 deleteFile=true） */
    fun deleteDownload(taskId: Long, deleteFile: Boolean = true) {
        val task = state.value.downloadTasks.find { it.id == taskId }
        if (deleteFile && task != null && task.localPath.isNotEmpty()) {
            if (task.localPath.startsWith("content://")) {
                runCatching {
                    DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(task.localPath))
                }
            } else {
                runCatching { File(task.localPath).delete() }
            }
        }
        val rest = state.value.downloadTasks.filter { it.id != taskId }
        state.value = state.value.withExtras { copy(downloadTasks = rest) }
        // R13：删记录必须同步落盘，否则下次冷启动"记录 + 扫描"又把它捞回来，
        // 用户看到的是"删了又出现"，而文件其实已经删掉了——计数也就再也减不下来了。
        persistTasks(rest)
        // 文件删了但本次会话的内存列表里没有别的改动时，重扫会让界面与磁盘一致
        if (deleteFile) rescanDownloads(force = true)
    }

    /** 批量删除下载记录 */
    fun deleteDownloads(taskIds: List<Long>, deleteFile: Boolean = true) {
        taskIds.forEach { deleteDownload(it, deleteFile) }
    }

    /** 设置下载目录 */
    fun setDownloadPath(path: String) {
        prefs.edit().putString(KEY_DOWNLOAD_PATH, path).apply()
        state.value = state.value.withExtras { copy(downloadPath = path) }
        // R13：换目录后立刻按新目录重扫一次。老目录里的歌靠记录里的 localPath
        // 继续存在（File.exists 仍然为真），不会被"换目录"当成没下载。
        rescanDownloads(force = true)
    }

    /** 确认下载目录（首次下载后标记） */
    fun setDownloadPathConfirmed(confirmed: Boolean) {
        prefs.edit().putBoolean(KEY_DOWNLOAD_PATH_CONFIRMED, confirmed).apply()
        state.value = state.value.withExtras { copy(downloadPathConfirmed = confirmed) }
    }

    /** 设置底部小播放器是否常驻 */
    fun setMiniPlayerPersist(persist: Boolean) {
        prefs.edit().putBoolean(KEY_MINIPLAYER_PERSIST, persist).apply()
        state.value = state.value.withExtras { copy(miniPlayerPersist = persist) }
    }

    /** 重试失败的下载 */
    fun retryDownload(taskId: Long) {
        val task = state.value.downloadTasks.find { it.id == taskId } ?: return
        // R13：重扫磁盘恢复出来的记录没有 songId（文件名叫不出数据库里的身份），
        // 光按 `it.id == task.songId` 找不到歌，用户点"重试"只会看到又一次失败。
        // 补两层找回：先按网易云 id，再按"歌名 + 歌手"，都对不上才认失败。
        val song = state.value.allSongs.find { it.id == task.songId }
            ?: task.neteaseId.takeIf { it.isNotBlank() }
                ?.let { key -> state.value.allSongs.find { it.songId == key } }
            ?: state.value.allSongs.find {
                it.title.equals(task.title, ignoreCase = true) &&
                    it.artist.equals(task.artist, ignoreCase = true)
            }
        if (song != null) {
            deleteDownload(taskId, deleteFile = false)
            downloadSong(song)
        } else {
            updateTask(taskId) { it.copy(status = DownloadStatus.FAILED) }
        }
    }

    // ==================== R13：记录落盘 + 按磁盘真相恢复 ====================

    /** 同一进程内是否已经自动扫过（冷启动那一次） */
    @Volatile
    private var rescanDone = false

    /**
     * 最近一次重扫是否真的读到了下载目录（listFiles / MediaStore / SAF 三条腿任意一条通）。
     * false 时"已下载 0 首"是**扫不到**而不是**没有**，UI 该提示用户重新授权目录，
     * 而不是让用户以为自己下的歌没了。
     */
    @Volatile
    private var scanReadableFlag = false

    /** 给 UI 判断"要不要提示重新选择下载目录" */
    fun lastScanReadable(): Boolean = scanReadableFlag

    /** 合并短时间内的多次重扫请求（批量删除时会连着调用） */
    private var rescanJob: kotlinx.coroutines.Job? = null

    /**
     * 扫描本机下载目录，把"磁盘上真实存在的歌"与 prefs 里的记录合并回
     * [PlayerUiState.downloadTasks]，并把合并结果落盘。
     *
     * 为什么必须由"记录 + 扫描"两条腿组成：
     *  - 只靠记录：老用户（这一轮之前的版本）prefs 里什么都没有，恢复出 0 首，白改；
     *  - 只靠扫描：targetSdk 35 在 Android 10（nova 这一批机型）上是强制分区存储，
     *    `File.listFiles()` 读公共 Music 目录经常直接返回 null，扫描结果为空时
     *    如果照单全收，就会把记录清零——正是"下载了 58 首、界面显示 0 首"的另一半成因。
     *    同一份文件走 MediaStore 查得到：系统 DownloadManager 写进公共目录的文件，
     *    本 App 是 owner，owner 不需要存储权限就能查、能读。
     *
     * 调用点：PlayerViewModel.init 冷启动一次（默认不重复）；用户进下载页 / 删除文件 /
     * 换下载目录时带 force=true。force 会在 1.2 秒内合并请求，批量删 20 首只扫一次。
     */
    fun rescanDownloads(force: Boolean = false) {
        if (rescanDone && !force) return
        rescanJob?.cancel()
        rescanJob = scope.launch(Dispatchers.IO) {
            if (force) delay(RESCAN_DEBOUNCE_MS)
            val merged = runCatching { buildRestoredTasks() }
                .getOrDefault(state.value.downloadTasks)
            rescanDone = true
            state.value = state.value.withExtras { copy(downloadTasks = merged) }
            persistTasks(merged)
        }
    }

    /** 恢复后的完整列表：本次会话未完成的在前（不动它们），已完成部分按磁盘真相同步 */
    private fun buildRestoredTasks(): List<DownloadTask> {
        // 正在下 / 排队中 / 本次失败的任务属于当前会话，它们的状态由各自的协程在写，
        // 重扫不能把它们改成"已完成"或删掉
        val live = state.value.downloadTasks.filter { it.status != DownloadStatus.COMPLETED }
        val records = loadRecords().filter { it.status == DownloadStatus.COMPLETED }
        val scanned = scanDownloadDirs()
        val files = scanned.first
        val scanOk = scanned.second
        scanReadableFlag = scanOk
        val byPath = HashMap<String, ScannedFile>(files.size * 2)
        files.forEach { byPath[pathKey(it.path)] = it }

        val out = ArrayList<DownloadTask>(records.size + files.size + live.size)
        val usedIdentity = HashSet<String>()
        val usedPath = HashSet<String>()

        // 1) 记录优先：带着 songId / 网易云 id，显示与"重试"都靠它
        records.forEach { r ->
            if (r.localPath.isBlank()) return@forEach
            val key = pathKey(r.localPath)
            val alive = byPath.containsKey(key) || fileStillThere(r.localPath)
            // 只有"目录确实读到了"才敢判死一条记录：listFiles 被分区存储挡掉时
            // byPath 是空的，照删就把用户 58 首的记录又抹成 0 了
            if (alive || !scanOk) {
                if (usedIdentity.add(identityOf(r)) && usedPath.add(key)) out.add(r)
            }
        }

        // 2) 记录里没有、但目录里确实在的歌：补成"已下载"（老版本没落盘的历史下载就靠这一步回来）
        files.forEach { f ->
            val key = pathKey(f.path)
            if (key in usedPath) return@forEach
            val (title, artist) = splitFileName(f.name)
            val t = DownloadTask(
                id = stableTaskId("${f.path}|${f.size}"),
                songId = 0L,
                title = title,
                artist = artist,
                neteaseId = "",
                status = DownloadStatus.COMPLETED,
                progress = 100,
                localPath = f.path,
                fileSize = f.size,
                // 文件时间当"下载时间"：下载管理页按 createdAt 倒序，恢复出来的歌
                // 要落在它当时所在的位置，而不是全部堆在最上面
                createdAt = if (f.modifiedMs > 0L) f.modifiedMs else (f.path.hashCode().toLong() and 0x7FFFFFFFL)
            )
            if (usedIdentity.add(identityOf(t)) && usedPath.add(key)) out.add(t)
        }

        // 3) 本次会话的进行中任务排前面，保证"正在下"的那几条不会被恢复结果顶掉。
        // 截断只截已完成的那部分：把 live 挤掉的话，正在下载的那几条会从界面上消失。
        val liveIds = live.mapTo(HashSet<Long>()) { it.id }
        val kept = out.filterNot { it.id in liveIds }.takeLast(RECORDS_MAX)
        return (live + kept).distinctBy { it.id }
    }

    /**
     * 路径归一化：记录里存的是 `File.absolutePath`（下载时写的），
     * 目录扫描拿到的可能是 canonicalPath（软链接已展开，如 /sdcard → /storage/emulated/0）。
     * 两边都过这里再比，否则同一个文件会被算成两条记录，"已下载"计数就虚高了。
     * SAF 的 content:// 不是文件系统路径，原样返回。
     */
    private fun pathKey(p: String): String {
        if (p.isBlank() || p.startsWith("content://")) return p
        return runCatching { File(p).canonicalPath }.getOrDefault(p)
    }

    /** 一条下载记录的落盘结果 */
    private fun persistTasks(list: List<DownloadTask>) {
        // 只持久化"已完成"：进行中/失败是本次会话的临时状态，重启后没有对应文件，
        // 存下来只会让用户看到一堆永远不动的假任务。
        // 整段包起来：org.json 在极端数据（歌名里有代理对字符等）上会抛 JSONException，
        // 而 updateTask 是从下载进度轮询里调进来的，那里没有别的地方兜异常——
        // 让落盘失败把正在跑的下载协程炸掉，比丢一条记录严重得多。
        runCatching {
            val arr = JSONArray()
            list.filter { it.status == DownloadStatus.COMPLETED && it.localPath.isNotBlank() }
                .takeLast(RECORDS_MAX)
                .forEach { t ->
                    arr.put(
                        JSONObject().apply {
                            put("id", t.id)
                            put("songId", t.songId)
                            put("title", t.title)
                            put("artist", t.artist)
                            put("neteaseId", t.neteaseId)
                            put("status", t.status.name)
                            put("progress", t.progress)
                            put("localPath", t.localPath)
                            put("fileSize", t.fileSize)
                            put("createdAt", t.createdAt)
                        }
                    )
                }
            prefs.edit().putString(KEY_DOWNLOAD_RECORDS, arr.toString()).apply()
        }
    }

    /** 从 prefs 读回下载记录；解析失败就当没有（不能让一条坏 JSON 把整个下载页拖崩） */
    private fun loadRecords(): List<DownloadTask> {
        val raw = prefs.getString(KEY_DOWNLOAD_RECORDS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<DownloadTask>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val path = o.optString("localPath")
                if (path.isBlank()) continue
                list.add(
                    DownloadTask(
                        id = o.optLong("id"),
                        songId = o.optLong("songId"),
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        neteaseId = o.optString("neteaseId"),
                        status = runCatching { DownloadStatus.valueOf(o.optString("status")) }
                            .getOrDefault(DownloadStatus.COMPLETED),
                        progress = o.optInt("progress", 100),
                        localPath = path,
                        fileSize = o.optLong("fileSize"),
                        createdAt = o.optLong("createdAt")
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    /** 下载文件扩展名白名单：扫描时靠它排除封面图、.part 残留、txt 之类 */
    private val audioExts = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "wma", "opus", "mka")

    private fun isAudioName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in audioExts

    /**
     * 扫描所有可能的下载位置。返回 (文件列表, 是否真的读到了东西)。
     * 第二个分量很关键：false 时调用方**不许**删除任何记录。
     */
    private fun scanDownloadDirs(): Pair<List<ScannedFile>, Boolean> {
        val out = LinkedHashMap<String, ScannedFile>()
        var ok = false
        // 默认公共目录：先 File.listFiles()（老设备/自己建的目录通常能读），
        // 再补一次 MediaStore（分区存储下 owner 视角一定看得见）
        val custom = state.value.downloadPath
        val dirs = LinkedHashSet<String>()
        dirs.add(defaultDir().absolutePath)
        if (custom.isNotBlank() && !custom.startsWith("content://")) {
            dirs.add(runCatching { File(custom).canonicalPath }.getOrDefault(custom))
        }
        dirs.forEach { dir ->
            if (listDirInto(dir, out)) ok = true
            if (mediaStoreInto(dir, out)) ok = true
        }
        // SAF 自定义目录：树里的子文件按显示名 + 大小读，读得到就算 ok
        if (custom.startsWith("content://") && treeInto(Uri.parse(custom), out)) ok = true
        // 上面全都失败时的最后一条腿：历史上授权过的树 URI（persistable 权限跨进程重启还在）。
        // 只挑"正好指到下载目录"的那一棵：本地曲库授权的是整个 Music 甚至更大的目录，
        // 一并扫的话用户的所有本地歌都会被算成"已下载"，数量直接爆掉。
        if (!ok) {
            persistedDownloadTree()?.let { t -> if (treeInto(t, out)) ok = true }
        }
        return out.values.toList() to ok
    }

    /**
     * 在已持久化的 SAF 树授权里找那一棵"就是下载目录"的树。
     * 匹配条件：树的 documentId 末段与下载目录末段同名（例如 primary:Music/Jun Music 对 Jun Music）。
     */
    private fun persistedDownloadTree(): Uri? {
        val custom = state.value.downloadPath
        // 候选目录名：默认目录那一层（Jun Music），以及用户自定义路径的最后一段
        val tails = ArrayList<String>()
        defaultDir().name.takeIf { it.isNotBlank() }?.let { tails.add(it) }
        if (custom.isNotBlank() && !custom.startsWith("content://")) {
            runCatching { File(custom).name }.getOrNull()?.takeIf { it.isNotBlank() }?.let { tails.add(it) }
        }
        if (tails.isEmpty()) return null
        val perms = runCatching { app.contentResolver.persistedUriPermissions.toList() }.getOrDefault(emptyList())
        return perms.firstOrNull { p ->
            // 不用 DocumentsContract.isTreeUri：那是 API29 才加的，minSdk24 上要背一条
            // NewApi 提示、老机器上还会 NoSuchMethodError。树 URI 的结构是固定的
            // content://<provider>/tree/<documentId>，自己看路径分段就行。
            val segs = p.uri.pathSegments
            p.isReadPermission && p.uri.scheme == "content" &&
                segs.size >= 2 && segs[0] == "tree" &&
                runCatching {
                    val id = Uri.decode(segs[1])
                    val tail = id.substringAfter(':', id)
                    tails.any { t -> tail.trimEnd('/').endsWith(t, ignoreCase = true) }
                }.getOrDefault(false)
        }?.uri
    }

    /** 直接列目录：listFiles 返回 null（无权限/目录不存在）时算"没读到" */
    private fun listDirInto(dirPath: String, out: MutableMap<String, ScannedFile>): Boolean {
        val files = runCatching { File(dirPath).listFiles()?.toList() }.getOrNull() ?: return false
        files.forEach { f ->
            if (!f.isFile || f.length() <= 0L || !isAudioName(f.name)) return@forEach
            val p = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
            out[p] = ScannedFile(p, f.name, f.length(), f.lastModified())
        }
        return true
    }

    /**
     * MediaStore 兜底。
     * `_data` 在 API29+ 仍然有值（官方标 deprecated 但没摘），owner 视角查得到自己下载的歌；
     * 万一某些 ROM 把 `_data` 抹掉了，就退回 `RELATIVE_PATH`（API29+ 的等价列）再试一次。
     */
    private fun mediaStoreInto(dirPath: String, out: MutableMap<String, ScannedFile>): Boolean {
        val dir = dirPath.trimEnd('/')
        if (dir.isBlank()) return false
        val columns = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val attempts = listOf(
            MediaStore.Audio.Media.DATA + " LIKE ?" to arrayOf("$dir/%"),
            // 直接写字面量而不是 MediaStore.MediaColumns.RELATIVE_PATH：那一列 API29 才有，
            // 常量本身在 minSdk24 上会吃一条 InlinedApi 提示；写字符串既没这个麻烦，
            // 老系统上查不到也只是这一次尝试失败，退回上面的 _data 路径。
            "relative_path LIKE ?" to
                arrayOf("%" + dir.substringAfterLast('/') + "%")
        )
        var touched = false
        attempts.forEach { (where, args) ->
            if (touched) return@forEach
            val cursor = runCatching {
                app.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    columns, where, args, null
                )
            }.getOrNull() ?: return@forEach
            cursor.use { c ->
                touched = true
                val dataIdx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val timeIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val path = if (dataIdx >= 0) c.getString(dataIdx) else null
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                    if (path.isNullOrBlank() || !path.startsWith(dir)) continue
                    if (!isAudioName(name ?: path)) continue
                    val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                    if (size <= 0L) continue
                    val modified = if (timeIdx >= 0) c.getLong(timeIdx) * 1000L else 0L
                    out[path] = ScannedFile(path, (name ?: path.substringAfterLast('/')), size, modified)
                }
            }
        }
        return touched
    }

    /** SAF 目录：列树的第一层子文档（不递归，避免把整棵外部存储走一遍） */
    private fun treeInto(tree: Uri, out: MutableMap<String, ScannedFile>): Boolean {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return false
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE
        )
        var touched = false
        val cursor = runCatching { app.contentResolver.query(children, columns, null, null, null) }
            .getOrNull() ?: return false
        cursor.use { c ->
            touched = true
            var n = 0
            while (c.moveToNext() && n < RECORDS_MAX) {
                n++
                val name = c.getString(1)
                val id = c.getString(0)
                if (name.isNullOrBlank() || id.isNullOrBlank() || !isAudioName(name)) continue
                val size = runCatching { c.getLong(2) }.getOrDefault(0L)
                if (size <= 0L) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                out[uri] = ScannedFile(uri, name, size, 0L)
            }
        }
        return touched
    }

    /** 记录里的文件是否还在；content:// 走 AssetFileDescriptor（SAF 授权还在就能开） */
    private fun fileStillThere(path: String): Boolean {
        if (path.isBlank()) return false
        return if (path.startsWith("content://")) {
            runCatching {
                app.contentResolver.openAssetFileDescriptor(Uri.parse(path), "r")?.use {
                    it.length != 0L
                } ?: false
            }.getOrDefault(false)
        } else {
            runCatching { File(path).isFile && File(path).length() > 0L }.getOrDefault(false)
        }
    }

    /** "歌名 - 歌手.mp3" 拆回两段；拆不出来就整名当歌名、歌手留空 */
    private fun splitFileName(name: String): Pair<String, String> {
        val base = name.substringBeforeLast('.').trim()
        val sep = base.indexOf(" - ")
        return if (sep > 0) {
            base.substring(0, sep).trim().ifBlank { name } to base.substring(sep + 3).trim()
        } else base to ""
    }

    /**
     * 给扫描出来的文件生成稳定任务 id。
     * 必须稳定：id 变了的话"删除/重试"点的是另一条记录；又要跟
     * `System.currentTimeMillis()`（正常下载任务的 id，量级 1.7e12）不撞号，
     * 所以取 32 位无符号哈希（上限 4.3e9），永不与时间戳重叠。
     */
    private fun stableTaskId(key: String): Long {
        var h = 0x811C9DC5L
        key.forEach { ch ->
            h = (h xor ch.code.toLong()) * 0x01000193L
            h = h and 0xFFFFFFFFL
        }
        return (h and 0xFFFFFFFFL).coerceAtLeast(1L)
    }

    /** 扫描到的一个文件（path 可能是真实路径，也可能是 SAF document URI） */
    private class ScannedFile(
        val path: String,
        val name: String,
        val size: Long,
        val modifiedMs: Long
    )

    companion object {
        /** 6.7.1：和播放链路一致的 UA，避免"播放能过、下载被拦" */
        private const val DL_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        const val KEY_DOWNLOAD_PATH = "download_path"
        const val KEY_DOWNLOAD_PATH_CONFIRMED = "download_path_confirmed"
        const val KEY_MINIPLAYER_PERSIST = "miniplayer_persist"

        /** R13：已下载记录表（JSON 数组）。以前下载任务只活在内存里，进程一死就归零 */
        const val KEY_DOWNLOAD_RECORDS = "download_records_json"

        /** 记录表上限：再多 prefs 就要变成几 MB 的字符串了，读取和写盘都会拖慢主线程 */
        private const val RECORDS_MAX = 800

        /** 重扫去抖窗口：批量删除会连着调用多次，合并成一次目录扫描 */
        private const val RESCAN_DEBOUNCE_MS = 1_200L

        /** 6.1.2：批量下载的同时并发上限（其余排队保持 PENDING） */
        private const val MAX_CONCURRENT_DOWNLOADS = 3

        /**
         * 空间预检按每首多大估。
         * 一首 4 分钟 320kbps mp3 约 9.6MB，flac 更多；这里取偏小的一档，只在"明显放不下"
         * 时才拦——宁可让用户点了看到一条失败提示，也不要在还剩 2GB 时误判"空间不足"不给下。
         */
        private const val EST_BYTES_PER_SONG = 8L * 1024 * 1024

        /**
         * 把 SAF 目录 URI 转成给人看的路径。
         * content://com.android.externalstorage.documents/tree/primary%3AMusic%2FJun%20Music
         * → 自定义 · Music/Jun Music
         */
        fun prettyPath(path: String): String {
            if (path.isBlank()) return "Music/Jun Music（默认）"
            if (!path.startsWith("content://")) return path
            val decoded = runCatching { Uri.decode(path) }.getOrDefault(path)
            val afterTree = decoded.substringAfter("/tree/", "")
            val id = if (afterTree.contains(':')) afterTree.substringAfter(':') else afterTree
            return "自定义 · " + id.ifBlank { "已选文件夹" }
        }
    }
}
