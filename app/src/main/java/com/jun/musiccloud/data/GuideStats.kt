package com.jun.musiccloud.data

import android.content.Context

/**
 * 引导效果埋点（本地留存，不上传）。
 *
 * 加了引导却不知道「有多少人看完、多少人在第几步跳了」，就只能靠猜去改。
 * 这里只记三个数字：走了几步、是看完还是跳过、花了多久。
 * 纯本地、不联网、不关联账号——够用来判断引导该删、该改还是该加。
 */
object GuideStats {

    private const val PREFS = "jun_guide_stats"
    private const val KEY_RUNS = "runs"
    private const val KEY_COMPLETED = "completed"
    private const val KEY_LAST_STEPS = "last_steps"
    private const val KEY_LAST_COMPLETED = "last_completed"
    private const val KEY_LAST_MS = "last_ms"
    private const val KEY_TOTAL_STEPS = "total_steps"

    /** 引导开始 */
    fun recordStart(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt(KEY_RUNS, p.getInt(KEY_RUNS, 0) + 1).apply()
    }

    /**
     * 引导结束。
     *
     * @param completed 是否走完全部步骤
     * @param reachedStep 跳过的步数（completed 为 true 时传任意值即可，会被记成"全部"）
     */
    fun record(context: Context, completed: Boolean, reachedStep: Int, elapsedMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_STEPS, reachedStep)
            .putBoolean(KEY_LAST_COMPLETED, completed)
            .putLong(KEY_LAST_MS, elapsedMs.coerceAtLeast(0L))
            .putInt(KEY_TOTAL_STEPS, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_TOTAL_STEPS, 0) + (if (completed) 1 else 0))
            .putInt(KEY_COMPLETED, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_COMPLETED, 0) + (if (completed) 1 else 0))
            .apply()
    }

    /** 给设置页用的一句话摘要 */
    fun summary(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val runs = p.getInt(KEY_RUNS, 0)
        if (runs == 0) return "还没有走过引导"
        val completed = p.getInt(KEY_COMPLETED, 0)
        val sec = p.getLong(KEY_LAST_MS, 0L) / 1000L
        return if (p.getBoolean(KEY_LAST_COMPLETED, false)) {
            "共 $runs 次 · 看完 $completed 次 · 上次用时 ${sec}s"
        } else {
            val step = p.getInt(KEY_LAST_STEPS, 0)
            "共 $runs 次 · 看完 $completed 次 · 上次在第 ${step + 1} 步跳过（${sec}s）"
        }
    }
}
