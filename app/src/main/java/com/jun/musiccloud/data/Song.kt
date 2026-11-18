package com.jun.musiccloud.data

import android.net.Uri
import androidx.compose.runtime.Immutable

/**
 * @Immutable：告诉 Compose「这个类的实例内容不会变」。
 *
 * 没有它时 Song 被当成不稳定类型（里面的 Uri 是框架类，Compose 不敢假设），
 * 于是每个携带 Song 的 lambda 都无法被记忆化——播放进度每 500ms 刷一次 state，
 * **整个可见列表的每一行都要跟着重组两遍**，这就是"整体特别卡"的最大单一来源。
 * 标了之后，参数没变的行会被组合器直接跳过，重组只发生在真正变化的行上。
 * Song 全是 val 且 Uri 实际不可变，标注是安全的。
 */
@Immutable
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** 播放地址：在线曲目为本地代理 URL（http://127.0.0.1:8089/audio?id=…） */
    val uri: Uri,
    val albumArtUri: Uri?,
    /**
     * 在线化后该字段复用为「封面 URL」，同时充当 ArtworkLoader 的缓存键。
     * 在线歌曲没有本地文件路径，封面统一走网络加载（ArtworkLoader 识别 http 前缀）。
     */
    val filePath: String = "",
    /** 来源分类：榜单/歌单名（如「热歌榜」），充当原「文件夹」语义 */
    val folderPath: String,
    /** 来源显示名（同 [folderPath]） */
    val folderName: String,
    /** 网易云歌曲 ID（在线播放/歌词解析用） */
    val songId: String = "",
    /** 音质标签（SQ/HQ/标准），空则回退按扩展名推断 */
    val format: String = "",
    /** 网易云点赞数（0 表示未知/本地歌曲），用于列表行显示热度 */
    val likeCount: Long = 0L,
    /** 本地 SAF 曲库的歌词键（"local:<id>"），空则按 songId / filePath 推导 */
    val lyricKeyOverride: String = ""
)

/**
 * 歌曲的「同一个身份」键：跨列表判定"是不是同一首歌"的唯一依据。
 *
 * 不能拿列表下标当身份：播放队列（queue）与浏览列表（homeSongs）是两份不同的列表，
 * 从榜单/歌单点歌后 queue 换成榜单，首页列表仍是全库——此时 `index == currentIndex`
 * 会把高亮画到完全无关的某一行（曾表现为「播的是这首、竖杠却标在上面那首」）。
 *
 * 在线歌曲以网易云 songId 为准，本地歌曲以本地 id 为准，两者不会互相撞号。
 */
val Song.identityKey: String
    get() = if (songId.isNotBlank()) "N$songId" else "L$id"

data class MusicFolder(
    val path: String,
    val name: String,
    val songCount: Int
)

/** 下载任务状态 */
enum class DownloadStatus { PENDING, DOWNLOADING, COMPLETED, FAILED }

/**
 * 下载任务记录。
 * - 在线歌曲：由系统 DownloadManager 下载，[localPath] 下载完成后填充。
 * - 本地歌曲：直接复制到下载目录，立即完成。
 */
data class DownloadTask(
    /** 任务唯一 ID（时间戳 + 歌曲 ID） */
    val id: Long,
    /** 歌曲 ID */
    val songId: Long,
    /** 歌曲标题（用于显示） */
    val title: String,
    /** 歌手 */
    val artist: String,
    /** 网易云歌曲 ID（在线歌曲解析真实 URL 用） */
    val neteaseId: String = "",
    /** 当前状态 */
    val status: DownloadStatus = DownloadStatus.PENDING,
    /** 下载进度 0-100，-1 表示未知 */
    val progress: Int = 0,
    /** 下载完成后的本地文件路径 */
    val localPath: String = "",
    /** 文件大小（字节），下载完成后填充 */
    val fileSize: Long = 0L,
    /** 创建时间戳 */
    val createdAt: Long = System.currentTimeMillis()
)

enum class RepeatMode { OFF, ALL, ONE }

enum class SortOrder(val label: String) {
    TITLE("标题"),
    ARTIST("艺术家"),
    DURATION("时长"),
    RECENT("修改/添加时间")
}

enum class SortDirection(val label: String) {
    ASCENDING("正序"),
    DESCENDING("倒序")
}

/**
 * 播放页音质徽章用的档位说法：无损容器就写「无损」，其余写「本地」。
 * 码率没有落库，猜不出具体档位，所以不拿容器名（MP3/FLAC）糊在徽章上。
 */
val Song.qualityLabel: String
    get() {
        val ext = (format.ifBlank { filePath.substringAfterLast('.', "") }).lowercase()
        return if (ext in setOf("flac", "ape", "wav", "alac", "aiff", "aif", "dsf", "dff", "tak")) "无损" else "本地"
    }

/**
 * 音频格式标签：由文件扩展名推导，例如 MP3 / FLAC / OGG。
 * 无扩展名或异常长度时回退为 AUDIO。
 */
val Song.formatLabel: String
    get() {
        if (format.isNotEmpty()) return format
        val ext = filePath.substringAfterLast('.', "").trim()
        if (ext.isEmpty() || ext.length > 5 || ext.contains('/')) return "AUDIO"
        return when (val u = ext.uppercase()) {
            "M4A", "MP4" -> "AAC"
            "OGA" -> "OGG"
            else -> u
        }
    }

/**
 * 歌词加载键。在线歌曲用 "ol:<songId>"（LyricParser 识别该前缀走网络拉取 LRC），
 * 本地歌曲回退为文件路径（原有同名 .lrc / 内嵌歌词逻辑）。
 */
val Song.lyricKey: String
    get() {
        if (lyricKeyOverride.isNotEmpty()) return lyricKeyOverride
        // SAF 本地曲目：没有可直接读取的文件路径，歌词已由 LocalLibrary 复制到私有目录
        if (filePath.startsWith("content://")) return ""
        return if (songId.isNotEmpty()) "ol:$songId" else filePath
    }