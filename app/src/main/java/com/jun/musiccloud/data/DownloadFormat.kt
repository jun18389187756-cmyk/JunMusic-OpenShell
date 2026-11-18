package com.jun.musiccloud.data

import android.content.Context

/**
 * 下载格式（6.3）。
 *
 * 用户在设置里选「跟音质走 / 只要 MP3 / 只要无损 FLAC」，下载时按这个决定向网关请求哪一档。
 * 为什么要做成「跟音质走」而不是强制二选一：
 *   - 大多数人对两种格式没概念，他关心的是「别占我 30MB」；
 *   - 真正在意的人会明确点 MP3 或 FLAC，这两种情况下不再看会员音质设置。
 *   - 默认必须是 [FOLLOW]，否则升级上来的老用户会突然发现歌全变成 FLAC，
 *     一次批量下载把手机塞满——这种"优化"是负分。
 */
enum class DownloadFormat(val label: String, val desc: String) {
    /** 跟随试听/播放时选的音质（老行为，默认） */
    FOLLOW("跟音质走", "播放时选什么音质，就下什么格式"),
    /** 强制标准 MP3（320k） */
    MP3("只要 MP3", "体积小，车机和老播放器都认"),
    /** 强制无损 FLAC */
    FLAC("只要无损 FLAC", "音质最好，单曲约 20~40MB");

    /**
     * 这个格式对应的「请求哪一档音质」。
     * 网关的 quality 只有几档取值：LOSSLESS 就是 FLAC 源，EXHIGH 是 320k MP3。
     * FOLLOW 返回空串表示「不改，用调用方原来的」。
     */
    val quality: String
        get() = when (this) {
            FOLLOW -> ""
            MP3 -> "EXHIGH"
            FLAC -> "LOSSLESS"
        }

    /** 拿不到真实格式时的兜底扩展名 */
    val fallbackExt: String
        get() = when (this) {
            FLAC -> "flac"
            else -> "mp3"
        }
}

object DownloadFormatStore {

    private const val KEY = "download_format"

    @Volatile
    private var cached: DownloadFormat? = null

    fun load(ctx: Context): DownloadFormat {
        cached?.let { return it }
        val raw = prefs(ctx).getString(KEY, DownloadFormat.FOLLOW.name)
        val v = runCatching { DownloadFormat.valueOf(raw ?: "") }.getOrDefault(DownloadFormat.FOLLOW)
        cached = v
        return v
    }

    fun save(ctx: Context, f: DownloadFormat) {
        cached = f
        prefs(ctx).edit().putString(KEY, f.name).apply()
    }

    /** 下载格式是设备级偏好（跟账号无关），直接放全局 jun_prefs，不进账号分区 */
    fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(AccountScope.LEGACY_PREFS, Context.MODE_PRIVATE)
}
