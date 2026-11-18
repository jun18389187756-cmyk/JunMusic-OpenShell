package com.jun.musiccloud.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地资料：未登录时也能设置的头像（相册选取）与昵称，仅存本机、不触达服务器。
 *
 * 与 [AccountStore]（云端账号）相互独立：
 *  - 没登录也想换个头像、起个昵称的人，不需要被强制进登录/注册流程；
 *  - 一旦真正登录云端账号，显示以云端账号资料为准，本地资料仅作为「未登录时的本机身份」。
 */
object LocalProfileStore {

    private const val PREFS = "jun_local_profile"
    private const val KEY_AVATAR = "avatar_path"
    private const val KEY_NICK = "nickname"

    var avatar: String = ""
        private set
    var nickname: String = ""
        private set

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        avatar = p.getString(KEY_AVATAR, "").orEmpty()
        nickname = p.getString(KEY_NICK, "").orEmpty()
    }

    fun setAvatar(ctx: Context, path: String) {
        avatar = path
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_AVATAR, path).apply()
    }

    fun setNickname(ctx: Context, name: String) {
        nickname = name
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NICK, name).apply()
    }

    fun clear(ctx: Context) {
        avatar = ""
        nickname = ""
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
