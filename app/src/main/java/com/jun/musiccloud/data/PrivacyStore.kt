package com.jun.musiccloud.data

import android.content.Context

/**
 * 隐私政策同意状态。
 *
 * App 会把账号信息与歌单上传到自有服务器，属于「收集个人信息」，
 * 按国内合规要求必须在使用前明示并征得同意，且要能证明用户点过同意。
 * 这里记的是「同意过 + 同意时的政策版本 + 时间」，政策更新后版本号变化，会重新征询。
 */
object PrivacyStore {

    private const val PREFS = "jun_privacy"
    private const val KEY_AGREED = "agreed"
    private const val KEY_VERSION = "version"
    private const val KEY_TIME = "agreed_at"

    /** 当前隐私政策版本：政策内容有实质变更时 +1，会重新弹一次征求同意 */
    const val POLICY_VERSION = 2

    /** 是否已同意当前版本的隐私政策 */
    fun hasAgreed(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_AGREED, false) && p.getInt(KEY_VERSION, 0) == POLICY_VERSION
    }

    fun markAgreed(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AGREED, true)
            .putInt(KEY_VERSION, POLICY_VERSION)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
    }

    /** 同意时间（毫秒），未同意返回 0 */
    fun agreedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_TIME, 0L)

    /** 撤回同意（设置里「撤回隐私同意」用）：下次启动重新征求 */
    fun revoke(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AGREED, false).apply()
    }
}
