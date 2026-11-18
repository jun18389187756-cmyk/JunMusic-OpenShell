package com.jun.musiccloud.data

import android.content.Context

/**
 * 搜索历史本地存储（与云端合并）。
 *
 * 以前搜索历史只在打开搜索页时才去云端拉一次；用户登录后不点搜索页，
 * 「那边有、这边没有」就一直存在。现在登录/启动时统一走 [mergeFromCloud]，
 * 搜索页只负责展示与新增。
 */
object SearchHistoryStore {

    private const val PREFS = "jun_search"
    private const val KEY_HISTORY = "history"
    private const val SEP = "\u0001"

    /** 最多保留 12 条，与搜索页展示一致 */
    const val MAX = 12

    fun load(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, "")
            ?.split(SEP)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun save(ctx: Context, list: List<String>) {
        val merged = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, merged.joinToString(SEP)).apply()
    }

    /** 新增一条（置顶去重），返回最新列表 */
    fun add(ctx: Context, keyword: String): List<String> {
        val t = keyword.trim()
        if (t.isEmpty()) return load(ctx)
        val merged = (listOf(t) + load(ctx).filter { it != t }).take(MAX)
        save(ctx, merged)
        return merged
    }

    /**
     * 与云端历史合并：云端最新的在前，本地补充其后。
     * 登录 / 冷启动时调用，保证换设备后搜索页立刻能看到历史，而不是等用户点进搜索。
     */
    fun mergeFromCloud(ctx: Context, cloud: List<String>): List<String> {
        if (cloud.isEmpty()) return load(ctx)
        val merged = (cloud.map { it.trim() }.filter { it.isNotEmpty() } + load(ctx))
            .distinct()
            .take(MAX)
        save(ctx, merged)
        return merged
    }

    fun clear(ctx: Context) = save(ctx, emptyList())
}
