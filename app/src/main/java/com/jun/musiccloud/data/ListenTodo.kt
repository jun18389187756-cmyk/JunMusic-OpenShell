package com.jun.musiccloud.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条「听歌待办」。
 *
 * 场景：刷到一首想听的、朋友推荐的一张专辑、直播里听到的一段 BGM——当场就去搜会打断现在
 * 在听的歌。这里给它一个极轻的落脚点：先记一句，回头有空再一首首清掉。
 *
 * 刻意不做「关联歌曲 id」：待办是给人看的备忘，不是播放队列。绑定 id 会让离线/在线
 * 曲库切换时大批条目变成死链，反而要写一堆失效处理。
 */
data class ListenTodo(
    val id: Long,
    val text: String,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 待办本地存储：SharedPreferences + JSON。
 *
 * 用 org.json 而不是引入序列化库：条目只有三个字段、数量级是几十条，
 * 为一个纯 UI 备忘功能背一个 Gson/Moshi 依赖不划算（包体积 + 混淆规则都要跟着加）。
 */
object ListenTodoStore {

    private const val PREFS = "jun_listen_todo"
    private const val KEY_ITEMS = "items"

    fun load(context: Context): List<ListenTodo> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<ListenTodo>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += ListenTodo(
                    id = o.optLong("id"),
                    text = o.optString("text"),
                    done = o.optBoolean("done", false),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            }
            out.sortedByDescending { it.createdAt }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, items: List<ListenTodo>) {
        val arr = JSONArray()
        items.forEach { t ->
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("text", t.text)
                    put("done", t.done)
                    put("createdAt", t.createdAt)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, arr.toString()).apply()
    }
}
