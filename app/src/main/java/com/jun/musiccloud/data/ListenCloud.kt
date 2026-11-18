package com.jun.musiccloud.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 听歌报告云同步。
 *
 * 本地 [ListenStats] 只有「这台手机」的明细：换手机、重装、清数据后报告就会归零。
 * 这里把本地「天 + 歌曲 + 毫秒」聚合结果上传到账号，并在打开报告时拉回云端数据
 * 与本机合并，于是同一账号在任何设备上看到的都是累计报告。
 *
 * 防重复的算法：
 *   · 服务端按 (账号, 天, 歌曲, 设备) 存，同一设备重复上报只取 MAX —— 上传重试、
 *     离线补报、反复打开报告都不会把时长翻倍；
 *   · 拉取时服务端返回 ms（全账号合计）与 own（本机贡献），客户端用
 *     `(ms - own) + MAX(本地, own)` 合并：本机已上传的部分不会被算两次，
 *     本机刚听还没上传的部分也不会丢；
 *   · 多台手机之间是 SUM，所以「手机 + 平板」的时长会正确相加。
 *
 * stable_id 是 63 位 Long，超过 JSON 数字的安全范围，传输时一律用字符串。
 */
object ListenCloud {

    private const val PREFS = "jun_listen_cloud"
    private const val CHUNK = 800
    private const val UPLOAD_TIMEOUT_MS = 20_000
    private const val FETCH_TIMEOUT_MS = 15_000

    /** 串行化：启动同步、打开报告、定时同步可能同时触发，避免重复上传同一批 */
    private val mutex = Mutex()

    fun deviceKey(context: Context): String = Telemetry.hwId(context.applicationContext)

    // ==================== 合并 ====================

    /**
     * 纯函数：把云端快照与本机明细合并成报告用的 entries。
     * 抽出来是为了能在 JVM 单元测试里直接验证，不依赖 Android。
     */
    fun mergeEntries(local: List<ListenEntry>, cloud: List<CloudListenEntry>): List<ListenEntry> {
        if (cloud.isEmpty()) return local
        val merged = LinkedHashMap<Pair<Long, Long>, Long>()
        for (e in local) {
            val key = e.day to e.songId
            merged[key] = (merged[key] ?: 0L) + e.ms
        }
        for (c in cloud) {
            if (c.ms <= 0L) continue
            val key = c.day to c.stableId
            val localMs = merged[key] ?: 0L
            val others = (c.ms - c.ownMs).coerceAtLeast(0L)
            // 本机这一份取较大值：已上传的不会重复加，刚听未上传的不会丢
            merged[key] = others + maxOf(localMs, c.ownMs)
        }
        return merged.map { (key, ms) -> ListenEntry(key.first, key.second, ms) }
    }

    fun metaOf(cloud: List<CloudListenEntry>): Map<Long, ListenSongMeta> {
        val out = HashMap<Long, ListenSongMeta>()
        for (c in cloud) {
            if (c.title.isBlank() && c.artist.isBlank() && c.cover.isBlank()) continue
            val old = out[c.stableId]
            // 优先保留信息更完整的一条（不同设备可能只有其中一台有曲库元数据）
            if (old == null || old.title.isBlank() || old.cover.isBlank()) {
                out[c.stableId] = ListenSongMeta(
                    title = c.title,
                    artist = c.artist,
                    cover = c.cover,
                    durationMs = c.durationSec.toLong() * 1000L
                )
            }
        }
        return out
    }

    // ==================== 同步 ====================

    /**
     * 上传本机增量，并拉回云端快照。
     *
     * @param songs 本地 ID → 歌曲，用于给云端补歌名/歌手/封面；曲库没有也可以传，
     *              只是排行榜会显示「已移除的歌曲」。
     * @return 云端快照；未登录或网络失败返回 null（调用方继续用本地数据）。
     */
    suspend fun sync(
        context: Context,
        uid: Long,
        songs: Map<Long, Song>
    ): CloudListenSnapshot? = mutex.withLock {
        if (uid <= 0L || !AccountStore.isLoggedIn) return@withLock null
        val app = context.applicationContext
        val token = AccountStore.token
        if (token.isBlank()) return@withLock null
        withContext(Dispatchers.IO) {
            val deviceKey = deviceKey(app)
            val local = ListenStats.load()
            val uploaded = loadUploaded(app, uid)

            // 只传新增/变大的行。ListenStats 里的值只会随时间增长，所以「比上次大」
            // 就说明有新时长；max 语义保证即使重传也不会翻倍。
            val pending = ArrayList<Pair<String, Long>>()
            val entries = JSONArray()
            for (e in local) {
                if (e.ms <= 0L) continue
                val key = "${e.day}:${e.songId}"
                val prev = uploaded[key] ?: 0L
                if (e.ms <= prev) continue
                val song = songs[e.songId]
                val obj = JSONObject()
                    .put("day", e.day)
                    .put("stableId", e.songId.toString())
                    .put("ms", e.ms)
                if (song != null) {
                    obj.put("songId", song.songId.ifBlank { "L${song.id}" })
                    obj.put("title", song.title)
                    obj.put("artist", song.artist)
                    obj.put("album", song.album)
                    obj.put("cover", song.albumArtUri?.toString() ?: song.filePath.takeIf { it.startsWith("http") }.orEmpty())
                    obj.put("duration", (song.durationMs / 1000L).toInt())
                }
                entries.put(obj)
                pending.add(key to e.ms)
            }

            var cursor = 0
            while (cursor < entries.length()) {
                val end = minOf(cursor + CHUNK, entries.length())
                val chunk = JSONArray()
                for (i in cursor until end) chunk.put(entries.getJSONObject(i))
                val body = JSONObject()
                    .put("deviceKey", deviceKey)
                    .put("entries", chunk)
                val root = ServerApi.request(
                    "/api/listen/stats", body, token, timeoutMs = UPLOAD_TIMEOUT_MS
                )
                if (root == null || root.optInt("code", -1) != 0) break
                // 上传成功才记进度：失败下次会重传，max 语义不会翻倍
                for (i in cursor until end) {
                    val (key, ms) = pending[i]
                    if (ms > (uploaded[key] ?: 0L)) uploaded[key] = ms
                }
                saveUploaded(app, uid, uploaded)
                cursor = end
            }

            // 拉取云端（即使上传失败也拉，页面至少能看到别的设备的数据）
            val query = "/api/listen/stats?deviceKey=" + URLEncoder.encode(deviceKey, "UTF-8")
            val root = ServerApi.request(query, null, token, timeoutMs = FETCH_TIMEOUT_MS)
            if (root == null || root.optInt("code", -1) != 0) return@withContext null
            val data = root.optJSONObject("data") ?: return@withContext null
            saveSnapshot(app, uid, data.toString())
            parseSnapshot(data)
        }
    }

    /** 读本机 + 云端缓存合并后的报告明细（磁盘 IO，调用方放后台线程） */
    fun mergedEntries(context: Context, uid: Long): List<ListenEntry> {
        val local = ListenStats.load()
        if (uid <= 0L) return local
        val cloud = loadSnapshot(context, uid).entries
        return mergeEntries(local, cloud)
    }

    /** 云端歌曲元数据：曲库已经下架的歌也能在排行榜显示正确的歌名/歌手 */
    fun cachedMeta(context: Context, uid: Long): Map<Long, ListenSongMeta> =
        if (uid <= 0L) emptyMap() else metaOf(loadSnapshot(context, uid).entries)

    fun cachedSnapshot(context: Context, uid: Long): CloudListenSnapshot =
        if (uid <= 0L) CloudListenSnapshot() else loadSnapshot(context, uid)

    // ==================== 本地缓存 / 进度 ====================

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun uploadedKey(uid: Long) = "uploaded_$uid"
    private fun snapshotKey(uid: Long) = "snapshot_$uid"

    private fun loadUploaded(context: Context, uid: Long): MutableMap<String, Long> {
        val raw = prefs(context).getString(uploadedKey(uid), null).orEmpty()
        if (raw.isBlank()) return HashMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return HashMap()
        val out = HashMap<String, Long>(obj.length().coerceAtLeast(8))
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optLong(k, 0L)
            if (v > 0L) out[k] = v
        }
        return out
    }

    private fun saveUploaded(context: Context, uid: Long, map: Map<String, Long>) {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        prefs(context).edit().putString(uploadedKey(uid), obj.toString()).apply()
    }

    private fun saveSnapshot(context: Context, uid: Long, raw: String) {
        prefs(context).edit().putString(snapshotKey(uid), raw).apply()
    }

    private fun loadSnapshot(context: Context, uid: Long): CloudListenSnapshot {
        val raw = prefs(context).getString(snapshotKey(uid), null).orEmpty()
        if (raw.isBlank()) return CloudListenSnapshot()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return CloudListenSnapshot()
        return parseSnapshot(obj)
    }

    private fun parseSnapshot(data: JSONObject): CloudListenSnapshot {
        val arr = data.optJSONArray("entries") ?: JSONArray()
        val list = ArrayList<CloudListenEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sid = o.optString("stableId", "").toLongOrNull() ?: continue
            val ms = o.optLong("ms", 0L)
            if (ms <= 0L) continue
            list.add(
                CloudListenEntry(
                    day = o.optLong("day", 0L),
                    stableId = sid,
                    ms = ms,
                    ownMs = o.optLong("own", 0L).coerceIn(0L, ms),
                    songId = o.optString("songId", ""),
                    title = o.optString("title", ""),
                    artist = o.optString("artist", ""),
                    album = o.optString("album", ""),
                    cover = o.optString("cover", ""),
                    durationSec = o.optInt("duration", 0)
                )
            )
        }
        return CloudListenSnapshot(
            totalMs = data.optLong("totalMs", 0L),
            updatedAt = data.optLong("updatedAt", 0L),
            entries = list,
            legacyDaily = parseLegacyDaily(data),
            legacyMs = data.optLong("legacyMs", 0L),
            eventListenSeconds = data.optLong("eventListenSeconds", 0L)
        )
    }

    private fun parseLegacyDaily(data: JSONObject): List<CloudListenDay> {
        val arr = data.optJSONArray("legacyDaily") ?: return emptyList()
        val out = ArrayList<CloudListenDay>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val day = o.optLong("day", 0L)
            val ms = o.optLong("ms", 0L)
            if (day > 0L && ms > 0L) out.add(CloudListenDay(day, ms))
        }
        return out
    }
}

/** 服务端按天汇总的旧版心跳时长（没有歌曲明细） */
data class CloudListenDay(
    val day: Long,
    val ms: Long
)

/** 云端返回的一条聚合记录 */
data class CloudListenEntry(
    val day: Long,
    val stableId: Long,
    /** 该账号所有设备在这首歌这一天的合计毫秒 */
    val ms: Long,
    /** 本机 deviceKey 贡献的毫秒（用于合并时去重） */
    val ownMs: Long,
    val songId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val cover: String = "",
    val durationSec: Int = 0
)

data class CloudListenSnapshot(
    val totalMs: Long = 0L,
    val updatedAt: Long = 0L,
    val entries: List<CloudListenEntry> = emptyList(),
    /** 服务端心跳汇总的按天时长，旧版没有歌曲明细 */
    val legacyDaily: List<CloudListenDay> = emptyList(),
    val legacyMs: Long = 0L,
    val eventListenSeconds: Long = 0L
)
