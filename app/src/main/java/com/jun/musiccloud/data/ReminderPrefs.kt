package com.jun.musiccloud.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 6.7.0 新增：定时提醒的本地设置。
 *
 * 用户诉求原话：
 * 「安卓原生系统通知，样式和高德状态栏通知一样，带图标、标题、内容；
 *   自动创建 NotificationChannel；首次打开申请通知权限；
 *   后台可以自定义间隔时间，比如每隔 N 分钟自动弹通知；
 *   开启后台服务，App 退到后台也能跑定时；后台可以开关；点通知自动打开 App。」
 *
 * 这里只存"用户想怎么提醒"，真正的调度在 [com.jun.musiccloud.playback.ReminderService]。
 * 单独一个 prefs 文件而不是塞进 jun_prefs：设置迁移/清数据时这一块是独立的，
 * 混在一起容易出现"改了提醒间隔把播放设置也带走了"。
 */
object ReminderPrefs {

    private const val FILE = "jun_reminder"
    private const val KEY_ON = "enabled"
    private const val KEY_INTERVAL = "interval_min"
    private const val KEY_TITLE = "title"
    private const val KEY_TEXT = "text"

    /** 默认关闭：定时通知属于"用户主动要的"功能，不能装完就自己弹 */
    const val DEFAULT_ON = false
    const val DEFAULT_INTERVAL_MIN = 30
    const val DEFAULT_TITLE = "该听首歌了"
    const val DEFAULT_TEXT = "你有一阵子没打开 JunMusic 了，点一下接着听刚才那首。"

    /** 可选间隔（分钟）。5 分钟已经足够密，再小只会被系统判成骚扰通知 */
    val INTERVAL_CHOICES = listOf(5, 15, 30, 60, 120, 360)

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, DEFAULT_ON)

    fun intervalMin(ctx: Context): Int =
        prefs(ctx).getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MIN).coerceIn(1, 24 * 60)

    fun title(ctx: Context): String =
        prefs(ctx).getString(KEY_TITLE, DEFAULT_TITLE).orEmpty().ifBlank { DEFAULT_TITLE }

    fun text(ctx: Context): String =
        prefs(ctx).getString(KEY_TEXT, DEFAULT_TEXT).orEmpty().ifBlank { DEFAULT_TEXT }

    fun setOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
    }

    fun setInterval(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(KEY_INTERVAL, minutes.coerceIn(1, 24 * 60)).apply()
    }

    fun setTitle(ctx: Context, title: String) {
        prefs(ctx).edit().putString(KEY_TITLE, title.trim().take(40)).apply()
    }

    fun setText(ctx: Context, text: String) {
        prefs(ctx).edit().putString(KEY_TEXT, text.trim().take(160)).apply()
    }
}
