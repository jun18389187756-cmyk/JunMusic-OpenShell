package com.jun.musiccloud.data

import android.content.Context

/**
 * 锁屏歌词样式（6.9.10 新增）。
 *
 * 用户拿着酷狗音乐的截图说："你学着他得弄啊，反正也加到设置里面"。
 * 酷狗那页是五个样式卡：自定义背景 / 专辑封面 / 歌手写真 / 祈福祝愿 / 苹果风格。
 * 这里把「样式」抽象成一个枚举，锁屏 Activity 按 id 走不同渲染分支，
 * 设置页只负责选，不再各写一套。
 */
enum class LockScreenStyle(
    val id: String,
    val label: String,
    val desc: String
) {
    ARTIST("artist", "歌手写真", "全屏大图打底，歌词大字贴在中段"),
    ALBUM("album", "专辑封面", "封面立起来，像一张唱片封套"),
    CUSTOM("custom", "自定义背景", "从相册挑一张图当锁屏底"),
    BLESSING("blessing", "祈福祝愿", "红底金字，逢年过节应个景"),
    APPLE("apple", "苹果风格", "时间在上、毛玻璃卡片在下，最克制");

    companion object {
        fun of(id: String?): LockScreenStyle =
            entries.firstOrNull { it.id == id } ?: ARTIST
    }
}

/**
 * 锁屏样式偏好。
 *
 * 故意和播放开关（[com.jun.musiccloud.playback.MusicService.KEY_LOCK_SCREEN_PLAYER]）
 * 放同一个 `jun_prefs`：一个是"要不要出现"，一个是"长什么样"，
 * 设置页上一次点击就能同时改到，不用跨文件同步。
 */
object LockScreenPrefs {

    private const val FILE = "jun_prefs"
    private const val KEY_STYLE = "lock_screen_style"
    private const val KEY_BG = "lock_screen_custom_bg"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun style(ctx: Context): LockScreenStyle =
        LockScreenStyle.of(prefs(ctx).getString(KEY_STYLE, LockScreenStyle.ARTIST.id))

    fun setStyle(ctx: Context, style: LockScreenStyle) {
        prefs(ctx).edit().putString(KEY_STYLE, style.id).apply()
    }

    fun customBackground(ctx: Context): String? =
        prefs(ctx).getString(KEY_BG, null)?.takeIf { it.isNotBlank() }

    fun setCustomBackground(ctx: Context, uri: String?) {
        prefs(ctx).edit().apply {
            if (uri.isNullOrBlank()) remove(KEY_BG) else putString(KEY_BG, uri)
        }.apply()
    }
}
