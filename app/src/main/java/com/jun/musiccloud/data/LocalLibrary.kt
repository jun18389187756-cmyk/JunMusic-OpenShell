package com.jun.musiccloud.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 本地 SAF 曲库。
 *
 * 不依赖 androidx.documentfile：直接用 DocumentsContract 走树形目录查询，
 * 兼容 Android 5.0–15。扫描结果（含元数据缓存）落在 filesDir/local_library.json，
 * 冷启动毫秒级恢复；再次扫描时只对新增/修改过的文件重新读标签，实现增量刷新。
 *
 * 支持：
 * - 音频 + 内嵌封面 / 同目录 folder.jpg / cover.png
 * - 同名外挂歌词（LRC/VTT/TTML/QRC/YRC/KRC）与 .t/.r 翻译轨
 * - .junignore / .foliaignore 排除规则
 * - 歌手/专辑别名（每行「原名称=新名称」）
 */
object LocalLibrary {

    private const val CACHE_FILE = "local_library.json"
    private const val LYRIC_DIR = "local_lyrics"
    private const val MAX_FILES = 12_000
    private const val MAX_DEPTH = 16

    private val AUDIO_EXTS = setOf(
        "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "ape", "wma",
        "aiff", "aif", "alac", "mka", "mp2", "dsf", "dff"
    )
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp")
    private val LYRIC_EXTS = listOf("lrc", "vtt", "ttml", "qrc", "yrc", "krc")
    private val COVER_NAMES = setOf("cover", "folder", "front", "album", "albumart", "artwork")

    data class ScanResult(val songs: List<Song>, val notice: String, val ignored: Int)

    @Volatile private var cachedSongs: List<Song>? = null
    @Volatile private var cachedContext: Context? = null

    /** 已扫描的本地歌曲（进程内缓存，首次读磁盘） */
    fun cached(context: Context): List<Song> {
        cachedContext?.let { if (it === context.applicationContext) cachedSongs?.let { s -> return s } }
        val app = context.applicationContext
        val songs = readCache(app)
        cachedContext = app
        cachedSongs = songs
        return songs
    }

    fun putCached(context: Context, songs: List<Song>) {
        cachedContext = context.applicationContext
        cachedSongs = songs
        writeCache(context.applicationContext, songs)
    }

    /** 扫描指定 SAF 目录树。 [treeUri] 必须已取得持久化读权限。 */
    suspend fun scan(
        context: Context,
        treeUri: Uri,
        readFolderCover: Boolean,
        obeyIgnore: Boolean,
        aliasRules: String,
        splitArtists: Boolean = false
    ): ScanResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val resolver = app.contentResolver
        val previous = readMeta(app).associateBy { it.uri }

        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext ScanResult(cached(app), "无法读取这个文件夹", 0)
        val rootName = queryDocumentName(app, treeUri, rootDocId) ?: "本地曲库"

        val ignore = if (obeyIgnore) readIgnoreRules(app, treeUri, rootDocId) else emptyList()
        val lyricsDir = File(app.filesDir, LYRIC_DIR).apply { mkdirs() }

        val out = ArrayList<Meta>(256)
        val alias = parseAlias(aliasRules)
        var ignoredCount = 0
        var files = 0

        suspend fun walk(dirDocId: String, relDir: String, depth: Int) {
            if (depth > MAX_DEPTH || files >= MAX_FILES) return
            val children = queryChildren(app, treeUri, dirDocId)
            if (children.isEmpty()) return

            val images = children.filter { it.isImage() }
            val folderCover = if (readFolderCover) pickCover(images) else null
            val coverUri = folderCover?.let {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, it.documentId).toString()
            }.orEmpty()
            // 按「不含扩展名的文件名」建索引，避免每首歌都遍历一遍同目录歌词
            val lyricIndex = children.filter { it.isLyric() }
                .groupBy { it.name.substringBeforeLast('.', it.name).lowercase() }
            val audioDocs = children.filter { it.isAudio() }

            for (doc in audioDocs) {
                if (files >= MAX_FILES) break
                val rel = if (relDir.isEmpty()) doc.name else "$relDir/${doc.name}"
                if (isIgnored(rel, doc.name, ignore)) { ignoredCount++; continue }
                files++

                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.documentId)
                val old = previous[uri.toString()]
                val meta = if (old != null && old.mtime == doc.lastModified && old.size == doc.size) {
                    old.copy(folderCover = coverUri, alias = alias)
                } else {
                    readMetadata(app, uri, doc, relDir, coverUri, alias)
                }

                // 歌词文件很小：每次扫描核对一次，歌词单独更新不需要重读音频标签
                meta.lyricKey = copyLyricSet(
                    app, treeUri, doc, lyricIndex, lyricsDir, meta.id
                ) ?: ""
                out.add(meta)
            }

            for (child in children) {
                if (!child.isDir) continue
                if (child.name.startsWith(".")) continue
                val rel = if (relDir.isEmpty()) child.name else "$relDir/${child.name}"
                if (isIgnored(rel, child.name, ignore)) { ignoredCount++; continue }
                walk(child.documentId, rel, depth + 1)
            }
        }

        runCatching { walk(rootDocId, "", 0) }
            .onFailure { android.util.Log.w("LocalLibrary", "scan failed: ${it.message}") }

        val songs = out
            .map { it.toSong(rootName, splitArtists) }
            .sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.title.lowercase() }))
        putCached(app, songs)
        writeMeta(app, out)
        val notice = buildString {
            append("已扫描 ").append(songs.size).append(" 首")
            if (ignoredCount > 0) append("，忽略 ").append(ignoredCount).append(" 项")
            if (files >= MAX_FILES) append("（已达 ").append(MAX_FILES).append(" 首上限）")
        }
        ScanResult(songs, notice, ignoredCount)
    }

    // ==================== 元数据 ====================

    private data class Meta(
        val uri: String,
        val id: Long,
        val mtime: Long,
        val size: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val format: String,
        val folderCover: String,
        val alias: Map<String, String>,
        var lyricKey: String = ""
    ) {
        fun toSong(rootName: String, splitArtists: Boolean): Song {
            val art = folderCover.ifBlank { uri }
            val baseArtist = if (splitArtists) primaryArtist(artist) else artist
            val finalArtist = alias[baseArtist] ?: baseArtist
            return Song(
                id = id,
                title = title,
                artist = finalArtist,
                album = alias[album] ?: album,
                durationMs = durationMs,
                uri = Uri.parse(uri),
                albumArtUri = art.takeIf { it.startsWith("content://") || it.startsWith("file://") }
                    ?.let { Uri.parse(it) },
                filePath = art,
                folderPath = "local:$rootName",
                folderName = "$rootName · 本地",
                songId = "",
                format = format,
                lyricKeyOverride = lyricKey
            )
        }
    }

    private fun readMetadata(
        context: Context,
        uri: Uri,
        doc: Doc,
        relDir: String,
        cover: String?,
        alias: Map<String, String>
    ): Meta {
        val name = doc.name
        var title = name.substringBeforeLast('.', name)
        var artist = "未知艺术家"
        var album = if (relDir.isBlank()) "本地曲库" else relDir.substringAfterLast('/')
        var duration = 0L
        runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, uri)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() }?.let { title = it.trim() }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() }?.let { artist = it.trim() }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.takeIf { it.isNotBlank() }?.let { album = it.trim() }
                duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            } finally {
                runCatching { mmr.release() }
            }
        }
        return Meta(
            uri = uri.toString(),
            id = MusicRepository.stableId(uri.toString()),
            mtime = doc.lastModified,
            size = doc.size,
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            format = name.substringAfterLast('.', "audio").uppercase(),
            folderCover = cover.orEmpty(),
            alias = alias
        )
    }

    /** 把「A / B」「A feat. B」这类多歌手标签收成主歌手；AC/DC 这类名字建议保持关闭 */
    private fun primaryArtist(raw: String): String {
        val parts = raw.split(ARTIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        return parts.firstOrNull()?.takeIf { it != raw.trim() } ?: raw.trim()
    }

    private val ARTIST_SPLIT = Regex(
        """\s*(?:/|、|;|；|\s+&\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+with\s+)\s*""",
        RegexOption.IGNORE_CASE
    )

    // ==================== 歌词 ====================

    /**
     * 把同名歌词与翻译/音译轨复制到应用私有目录。
     * LyricParser 以文件为键，content:// 无法直接当缓存键，复制一份最稳。
     */
    private fun copyLyricSet(
        app: Context,
        treeUri: Uri,
        audio: Doc,
        lyricIndex: Map<String, List<Doc>>,
        dir: File,
        songId: Long
    ): String? {
        val base = audio.name.substringBeforeLast('.', audio.name).lowercase()
        val main = lyricIndex[base]?.firstOrNull { it.ext in LYRIC_EXTS } ?: return null
        val mainFile = File(dir, "$songId.${main.ext}")
        if (!copyTo(app, treeUri, main, mainFile)) return null

        val suffixes = listOf(
            "t" to listOf("t", "trans", "zh", "translation"),
            "r" to listOf("r", "roma", "romaji", "pinyin")
        )
        for ((target, names) in suffixes) {
            val hit = names.asSequence()
                .mapNotNull { n -> lyricIndex["$base.$n"]?.firstOrNull { it.ext in LYRIC_EXTS } }
                .firstOrNull() ?: continue
            copyTo(app, treeUri, hit, File(dir, "$songId.$target.${hit.ext}"))
        }
        return mainFile.absolutePath
    }

    private fun copyTo(app: Context, treeUri: Uri, doc: Doc, dest: File): Boolean = runCatching {
        // 增量扫描：源文件大小与修改时间都没变时直接复用，避免每次重扫都复制一遍歌词
        if (dest.exists() && dest.length() == doc.size &&
            doc.lastModified > 0L && dest.lastModified() >= doc.lastModified
        ) {
            return true
        }
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.documentId)
        app.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out) }
        } ?: return false
        dest.length() > 0L
    }.getOrDefault(false)

    // ==================== 忽略规则 ====================

    private fun readIgnoreRules(app: Context, treeUri: Uri, rootDocId: String): List<Regex> {
        val docs = queryChildren(app, treeUri, rootDocId)
        val file = docs.firstOrNull { it.name.equals(".junignore", true) || it.name.equals(".foliaignore", true) }
            ?: return emptyList()
        val text = runCatching {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, file.documentId)
            app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return emptyList()
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { globToRegex(it) }
    }

    private fun isIgnored(relPath: String, name: String, rules: List<Regex>): Boolean {
        if (name.startsWith(".")) return true
        val candidates = listOf(relPath, name, "/$relPath")
        return rules.any { r -> candidates.any { r.matches(it) } }
    }

    private fun globToRegex(pattern: String): Regex? = runCatching {
        val p = pattern.removePrefix("/").removeSuffix("/")
        val sb = StringBuilder()
        for (c in p) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        Regex("(^|.*/)$sb(/.*)?$", RegexOption.IGNORE_CASE)
    }.getOrNull()

    private fun parseAlias(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val idx = t.indexOf('=')
            if (idx <= 0 || idx == t.lastIndex) continue
            val from = t.substring(0, idx).trim()
            val to = t.substring(idx + 1).trim()
            if (from.isNotEmpty() && to.isNotEmpty()) map[from] = to
        }
        return map
    }

    // ==================== SAF 查询 ====================

    private data class Doc(
        val documentId: String,
        val name: String,
        val mime: String,
        val lastModified: Long,
        val size: Long
    ) {
        val ext: String get() = name.substringAfterLast('.', "").lowercase()
        val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
        fun isAudio() = !isDir && ext in AUDIO_EXTS
        fun isImage() = !isDir && ext in IMAGE_EXTS
        fun isLyric() = !isDir && ext in LYRIC_EXTS
    }

    private fun queryChildren(app: Context, treeUri: Uri, docId: String): List<Doc> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val out = ArrayList<Doc>()
        runCatching {
            app.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    out.add(
                        Doc(
                            documentId = c.getString(0) ?: continue,
                            name = name,
                            mime = c.getString(2) ?: "",
                            lastModified = c.getLong(3),
                            size = c.getLong(4)
                        )
                    )
                }
            }
        }
        return out
    }

    private fun queryDocumentName(app: Context, treeUri: Uri, docId: String): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return runCatching {
            app.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    private fun pickCover(images: List<Doc>): Doc? =
        images.firstOrNull { it.name.substringBeforeLast('.', it.name).lowercase() in COVER_NAMES }
            ?: images.firstOrNull {
                it.name.lowercase().let { n -> COVER_NAMES.any { key -> n.startsWith(key) } }
            }

    // ==================== 持久化 ====================

    private fun cacheFile(app: Context) = File(app.filesDir, CACHE_FILE)

    private fun readCache(app: Context): List<Song> {
        val f = cacheFile(app)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Song>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val uri = o.optString("uri")
                if (uri.isEmpty()) continue
                val folderName = o.optString("folderName", "本地曲库 · 本地")
                out.add(
                    Song(
                        id = o.optLong("id"),
                        title = o.optString("title", "未知歌曲"),
                        artist = o.optString("artist", "未知艺术家"),
                        album = o.optString("album", ""),
                        durationMs = o.optLong("duration", 0L),
                        uri = Uri.parse(uri),
                        albumArtUri = o.optString("art").takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
                        filePath = o.optString("art", uri),
                        folderPath = o.optString("folderPath", "local:本地曲库"),
                        folderName = folderName,
                        songId = "",
                        format = o.optString("format", "AUDIO"),
                        lyricKeyOverride = o.optString("lyric", "")
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun writeCache(app: Context, songs: List<Song>) {
        runCatching {
            val arr = JSONArray()
            for (s in songs) {
                arr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("uri", s.uri.toString())
                        put("title", s.title)
                        put("artist", s.artist)
                        put("album", s.album)
                        put("duration", s.durationMs)
                        put("art", s.filePath)
                        put("folderPath", s.folderPath)
                        put("folderName", s.folderName)
                        put("format", s.format)
                        put("lyric", s.lyricKeyOverride)
                    }
                )
            }
            cacheFile(app).writeText(arr.toString())
        }
    }

    /** 元数据缓存只给扫描增量用，字段与缓存展示文件基本一致 */
    private fun metaFile(app: Context) = File(app.filesDir, "local_library_meta.json")

    private fun readMeta(app: Context): List<Meta> {
        val f = metaFile(app)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Meta>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val uri = o.optString("uri")
                if (uri.isEmpty()) continue
                out.add(
                    Meta(
                        uri = uri,
                        id = o.optLong("id"),
                        mtime = o.optLong("mtime"),
                        size = o.optLong("size"),
                        title = o.optString("title", "未知歌曲"),
                        artist = o.optString("artist", "未知艺术家"),
                        album = o.optString("album", ""),
                        durationMs = o.optLong("duration"),
                        format = o.optString("format", "AUDIO"),
                        folderCover = o.optString("cover"),
                        alias = emptyMap(),
                        lyricKey = o.optString("lyric")
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun writeMeta(app: Context, metas: List<Meta>) {
        runCatching {
            val arr = JSONArray()
            for (m in metas) {
                arr.put(
                    JSONObject().apply {
                        put("uri", m.uri)
                        put("id", m.id)
                        put("mtime", m.mtime)
                        put("size", m.size)
                        put("title", m.title)
                        put("artist", m.artist)
                        put("album", m.album)
                        put("duration", m.durationMs)
                        put("format", m.format)
                        put("cover", m.folderCover)
                        put("lyric", m.lyricKey)
                    }
                )
            }
            metaFile(app).writeText(arr.toString())
        }
    }

    /** 只给 UI 展示的轻量校验：持久化权限是否还在 */
    fun hasPermission(context: Context, treeUri: Uri): Boolean = runCatching {
        val resolver = context.contentResolver
        DocumentsContract.getTreeDocumentId(treeUri)
        resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            ),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null
        )?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    fun persistPermission(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }
}
