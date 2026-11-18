package com.jun.musiccloud.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 6.8.0：**自建曲库**（第二层后路）—— 你自己服务器上的文件。
 *
 * ## 为什么要有这一层
 *
 * 第一层（[LocalAudioIndex]）只管"这台手机上已经有的文件"。
 * 换手机、清缓存、重装之后它就空了。而第三方音源随时会关。
 *
 * 这一层把文件放在**你自己的服务器**上：只要那台机器还在跑，
 * 换什么手机、卸载多少次，歌都还在。这是唯一一个"别人关不掉"的在线来源。
 *
 * ## 设计取舍
 *
 * · **清单在本地缓存**，点歌时纯内存匹配（微秒级），命中后才发一个 HTTP 请求。
 *   不做"每次点歌先问服务器"——那等于给起播加一次网络往返，
 *   而这一层的意义恰恰是"在最坏的情况下还能响"，不能反过来成为新的延迟源。
 * · **清单拉取失败保持旧缓存**。断网 / 服务器重启时，旧清单照样能匹配出直链，
 *   直链打不通时代理会自动回退到第三方（见 `resolveWithLadder`）。
 * · **不做鉴权体系**，只用一个共享密钥 `k` 挡住随手扫描。
 *   曲库是私人备份，不是公开服务；真正的边界是"别把链接发出去"。
 */
object RemoteLibrary {

    private const val TAG = "RemoteLibrary"
    private const val PREFS = "jun_remote_library"
    private const val KEY_CACHE = "cache_v2"

    /** 清单本地缓存有效期：6 小时。比这更旧就先拿旧的用，同时后台刷新 */
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

    /** 曲库访问密钥。要和服务器 `LIB_KEY` 一致，改动需两边同时改 */
    internal const val LIB_KEY = "jun-lib-2026-7d41c9"

    /** 由 [init] 填充；[addLocal] 落盘时要用 */
    @Volatile
    private var appCtx: Context? = null

    private val lock = Any()

    private var items: List<Item> = emptyList()
    private var loaded = false
    private var loadedAt = 0L
    private var refreshing = false

    data class Item(
        val id: String,
        val platformId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val format: String,
        val bytes: Long
    )

    // ────────────────────────────────────────────────────────────
    // 规范化（与 LocalAudioIndex 保持完全一致的规则）
    // ────────────────────────────────────────────────────────────

    private fun norm(s: String): String {
        if (s.isEmpty()) return ""
        val sb = StringBuilder(s.length)
        var depth = 0
        for (c in s) {
            when {
                c == '(' || c == '（' || c == '[' || c == '【' -> depth++
                c == ')' || c == '）' || c == ']' || c == '】' -> if (depth > 0) depth--
                depth > 0 -> {}
                c.isLetterOrDigit() -> sb.append(c.lowercaseChar())
                else -> {}
            }
        }
        return sb.toString()
    }

    private fun fp(title: String, artist: String): String {
        val t = norm(title)
        if (t.isEmpty()) return ""
        val a = norm(artist)
        return if (a.isEmpty()) t else "$t|$a"
    }

    // ────────────────────────────────────────────────────────────
    // 缓存
    // ────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        val ctx = context.applicationContext
        appCtx = ctx
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            runCatching { loadFromDisk(ctx) }.onFailure {
                Log.w(TAG, "曲库清单读取失败：${it.message}")
            }
            loaded = true
        }
    }

    private fun loadFromDisk(ctx: Context) {
        val p = prefs(ctx)
        loadedAt = p.getLong("at", 0L)
        val raw = p.getString(KEY_CACHE, null) ?: return
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
        val list = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                Item(
                    id = o.optString("id").orEmpty(),
                    platformId = o.optString("pid").orEmpty(),
                    title = o.optString("title").orEmpty(),
                    artist = o.optString("artist").orEmpty(),
                    durationMs = o.optLong("du", 0L),
                    format = o.optString("fmt").orEmpty(),
                    bytes = o.optLong("bytes", 0L)
                )
            )
        }
        items = list
        Log.i(TAG, "曲库清单载入：${list.size} 首（${if (loadedAt > 0) (System.currentTimeMillis() - loadedAt) / 60000 else -1} 分钟前）")
    }

    private fun saveToDisk(ctx: Context, list: List<Item>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("pid", e.platformId)
                    .put("title", e.title)
                    .put("artist", e.artist)
                    .put("du", e.durationMs)
                    .put("fmt", e.format)
                    .put("bytes", e.bytes)
            )
        }
        prefs(ctx).edit()
            .putString(KEY_CACHE, arr.toString())
            .putLong("at", System.currentTimeMillis())
            .apply()
    }

    // ────────────────────────────────────────────────────────────
    // 刷新
    // ────────────────────────────────────────────────────────────

    /**
     * 拉取曲库清单。
     *
     * @param force false 时只在缓存过期后拉（启动时调用，避免每次冷启动都发请求）
     */
    fun refresh(context: Context, force: Boolean = false) {
        val ctx = context.applicationContext
        if (refreshing) return
        if (!force && items.isNotEmpty() &&
            System.currentTimeMillis() - loadedAt < CACHE_TTL_MS
        ) return

        refreshing = true
        Thread({
            try {
                val body = ServerApi.request("/api/library/list?k=$LIB_KEY", timeoutMs = 8_000)
                    ?: return@Thread
                val data = body.optJSONObject("data") ?: return@Thread
                val arr = data.optJSONArray("items") ?: return@Thread
                val list = ArrayList<Item>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").orEmpty()
                    if (id.isEmpty()) continue
                    list.add(
                        Item(
                            id = id,
                            platformId = o.optString("platformId").orEmpty(),
                            title = o.optString("title").orEmpty(),
                            artist = o.optString("artist").orEmpty(),
                            durationMs = o.optLong("durationMs", 0L),
                            format = o.optString("format").orEmpty(),
                            bytes = o.optLong("bytes", 0L)
                        )
                    )
                }
                synchronized(lock) {
                    items = list
                    loadedAt = System.currentTimeMillis()
                }
                saveToDisk(ctx, list)
                Log.i(TAG, "曲库清单已刷新：${list.size} 首")
            } catch (e: Exception) {
                // 拉不到就用旧清单。这一层不能因为一次网络失败就整层消失。
                Log.w(TAG, "曲库清单刷新失败（继续用旧缓存）：${e.message}")
            } finally {
                refreshing = false
            }
        }, "remote-library-refresh").apply { isDaemon = true }.start()
    }

    // ────────────────────────────────────────────────────────────
    // 查
    // ────────────────────────────────────────────────────────────

    /**
     * 在自建曲库里找这首歌，命中返回**可直接播放的完整 URL**。
     *
     * 纯内存操作，不联网 —— 它挡在起播路径上，绝不能有网络往返。
     */
    fun lookup(songId: String?, title: String?, artist: String?): Item? {
        val list = synchronized(lock) { items }
        if (list.isEmpty()) return null

        val pid = songId.orEmpty().trim()
        if (pid.isNotEmpty()) {
            list.firstOrNull { it.platformId.isNotBlank() && it.platformId == pid }?.let { return it }
        }
        val k = fp(title.orEmpty(), artist.orEmpty())
        if (k.isEmpty()) return null
        return list.firstOrNull { fp(it.title, it.artist) == k }
    }

    /** 拼出播放地址。带共享密钥，服务器据此校验。 */
    fun fileUrl(item: Item): String {
        return "${ServerApi.BASE_URL}/api/library/file/${item.id}?k=$LIB_KEY"
    }

    /** 给设置页显示"云端曲库 N 首 / X MB" */
    fun stats(): Pair<Int, Long> {
        val list = synchronized(lock) { items }
        return list.size to list.sumOf { it.bytes }
    }

    /**
     * 6.8.1：**回填成功后立刻进内存清单**。
     *
     * 不重新拉清单的理由：拉一次要一个往返，而且要等 6 小时缓存；
     * 而此刻我们手里已经有全部字段（id / 歌名 / 歌手 / 时长 / 格式 / 字节数），
     * 直接插到本地就够了 —— 下一首歌点自己时就能命中。
     */
    fun addLocal(item: Item) {
        if (item.id.isBlank()) return
        synchronized(lock) {
            if (items.any { it.id == item.id }) return
            items = listOf(item) + items
            appCtx?.let { ctx -> runCatching { saveToDisk(ctx, items) } }
        }
    }
}
