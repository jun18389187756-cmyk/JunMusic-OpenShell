package com.jun.musiccloud.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 启动登录引导的本地记忆：用户选过「暂不登录 / 不再提示」后，
 * 后续打开不再弹窗打扰；一旦登录成功则永远不弹。
 */
object StartupLoginStore {

    private const val PREFS = "jun_startup_login"
    private const val KEY_DISMISSED = "dismissed"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 是否应当弹出启动登录引导：未登录 且 用户没选过「不再提示」。 */
    fun shouldShow(ctx: Context, loggedIn: Boolean): Boolean {
        if (loggedIn) return false
        return !p(ctx).getBoolean(KEY_DISMISSED, false)
    }

    /** 记住用户「这次不弹了」（不再提示）。 */
    fun markDismissed(ctx: Context) {
        p(ctx).edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    /** 重置（例如退出登录后，下回打开重新引导）。 */
    fun reset(ctx: Context) {
        p(ctx).edit().remove(KEY_DISMISSED).apply()
    }
}
