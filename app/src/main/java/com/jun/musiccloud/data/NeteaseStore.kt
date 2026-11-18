package com.jun.musiccloud.data

import android.content.Context

/**
 * 网易云账号会话持久化。
 *
 * 与自建账号 [AccountStore] 相互独立：两者都是可选的，互不影响。
 * 只保存登录 Cookie（MUSIC_U / __csrf）与展示用的资料，不保存密码。
 */
object NeteaseStore {

    private const val PREFS = "jun_netease"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_UID = "uid"
    private const val KEY_NAME = "nickname"
    private const val KEY_AVATAR = "avatar"

    @Volatile private var appCtx: Context? = null

    var cookie: String = ""
        private set
    var userId: String = ""
        private set
    var nickname: String = ""
        private set
    var avatar: String = ""
        private set

    val isLoggedIn: Boolean get() = cookie.isNotBlank() && userId.isNotBlank()

    fun load(ctx: Context) {
        appCtx = ctx.applicationContext
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cookie = p.getString(KEY_COOKIE, "").orEmpty()
        userId = p.getString(KEY_UID, "").orEmpty()
        nickname = p.getString(KEY_NAME, "").orEmpty()
        avatar = p.getString(KEY_AVATAR, "").orEmpty()
        NeteaseApi.restoreCookie(cookie)
    }

    /** 保存登录后的资料（扫码成功 / 拉取到 profile 后调用） */
    fun save(ctx: Context, profile: NeteaseApi.NsProfile) {
        appCtx = ctx.applicationContext
        userId = profile.userId
        nickname = profile.nickname
        avatar = profile.avatar
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UID, userId)
            .putString(KEY_NAME, nickname)
            .putString(KEY_AVATAR, avatar)
            .apply()
    }

    /** 只更新 Cookie（由 [NeteaseApi] 在收到 Set-Cookie 时回调） */
    fun saveCookie(cookie: String) {
        this.cookie = cookie
        val ctx = appCtx ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COOKIE, cookie).apply()
    }

    fun clear(ctx: Context) {
        appCtx = ctx.applicationContext
        cookie = ""
        userId = ""
        nickname = ""
        avatar = ""
        NeteaseApi.restoreCookie("")
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
