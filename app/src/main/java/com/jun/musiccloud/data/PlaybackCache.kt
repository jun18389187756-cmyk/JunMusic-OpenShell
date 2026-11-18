package com.jun.musiccloud.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.asStateFlow

/**
 * 在线播放磁盘缓存（5.1 新增）。
 *
 * 5.0 之前没有任何流式缓存：同一首歌重听、seek 回去、断网重连，全部重新走一遍
 * CDN 下载。加缓存带来三件事——重播秒开、省流量、弱网下不再一卡一卡。
 *
 * 三个必须处理干净的细节：
 *
 * 1. **缓存键不能用 URI**。播放地址是
 *    `http://127.0.0.1:PORT/audio?id=<歌曲ID>&t=<进程级令牌>`，令牌每次进程重启都会
 *    变（见 [com.jun.musiccloud.playback.AudioProxyServer.ACCESS_TOKEN]）。若按 URI 建键，
 *    重启后缓存全部错位、等于白存。这里用查询串里的 `id` 当键，跨进程、跨重启都命中。
 * 2. **不碰本地曲目**。SAF/本地文件走 `file://`、`content://`，由外层
 *    [DefaultDataSource.Factory] 自己处理，只有需要联网的 URI 才进缓存层。
 * 3. **缓存出错不能影响播放**。开 `FLAG_IGNORE_CACHE_ON_ERROR`，缓存读写任何异常
 *    都退化成"直接联网播"，最多丢缓存，不会丢声音。
 *
 * 关闭开关后不再写入，但已存数据保留（用户可能只是临时关）；
 * 真要腾空间用设置页的「清理缓存」。
 */
object PlaybackCache {

    private const val TAG = "PlaybackCache"

    /**
     * 6.3.2：可热替换的数据源工厂。
     *
     * 为什么需要这么一个薄包装：media3 1.5 的 [androidx.media3.exoplayer.ExoPlayer]
     * **没有** `setMediaSourceFactory`，工厂是 `ExoPlayer.Builder` 构造期绑死的，
     * 之后再也换不了。而「改缓存档位」必须换掉底层 SimpleCache（evictor 容量是
     * 构造期决定），旧的释放后，绑死在播放器里的那个工厂就指向一个死对象——
     * 用户改完档位，每首歌都报「网络断了」，只能杀 App 重开。
     *
     * 这层包装让播放器始终持有同一个引用，真正干活的对象放在 [delegate] 里，
     * 改档位时只换 delegate。播放器完全无感，也不用重建。
     */
    @UnstableApi
    class Swappable(initial: DataSource.Factory) : DataSource.Factory {
        @Volatile
        private var delegate: DataSource.Factory = initial

        /** 换底层工厂；下一次 createDataSource 就会走新的缓存实例 */
        fun swapTo(factory: DataSource.Factory) {
            delegate = factory
        }

        override fun createDataSource(): DataSource = delegate.createDataSource()
    }

    /** 与现有设置同库，不新建 prefs 文件 */
    const val PREFS = "jun_prefs"

    /** 缓存语义版本。改动缓存键/内容判定规则时必须 +1，触发一次性清空。 */
    private const val CACHE_SCHEMA = 634
    private const val KEY_CACHE_SCHEMA = "stream_cache_schema"
    const val KEY_ENABLED = "stream_cache_enabled"
    const val KEY_LIMIT_MB = "stream_cache_limit_mb"

    /**
     * 可选容量档位（MB）。6.3.1 起补上 2048 / 4096 与"自动"（[AUTO]）。
     *
     * 之前最大 1024，但设置页的选择器被撤掉了，实际所有用户都被钉在 256MB。
     * 用户在一台 512G 的机器上听无损，256MB 连 20 首都装不下——写满就再也存不进，
     * 表现成"缓存已用满"提示反复出现、点重试还是同一首重新联网。
     * 现在恢复可选，并按剩余空间给"自动"档。
     */
    val LIMIT_OPTIONS = listOf(128, 256, 512, 1024, 2048, 4096)

    /** 档位标记：0 = 自动（按剩余空间算，见 [autoLimitMb]） */
    const val AUTO = 0

    private const val DEFAULT_LIMIT_MB = 256

    /**
     * 自动档：按缓存目录所在分区的剩余空间定，保证永远不会把存储吃穿。
     *
     * 为什么按分区剩余而不是按总容量：一台 512G 的手机只剩 800MB 时，
     * 再往上加缓存只会让系统开始杀进程。留 4 倍于缓存上限的空闲是最低安全边际。
     */
    fun autoLimitMb(context: Context): Int {
        val freeMb = runCatching { context.cacheDir.usableSpace / (1024L * 1024L) }.getOrDefault(0L)
        return when {
            freeMb >= 20_000 -> 4096
            freeMb >= 10_000 -> 2048
            freeMb >= 6_000 -> 1024
            freeMb >= 3_000 -> 512
            freeMb >= 1_200 -> 256
            else -> 128
        }
    }

    @Volatile private var cacheDir: File? = null
    @Volatile private var simpleCache: SimpleCache? = null
    @Volatile private var factory: DataSource.Factory? = null
    @Volatile private var boundLimitMb = 0

    /**
     * 播放器手里那个 [Swappable] 壳（6.5.3）。
     *
     * 为什么必须留这个引用：清空缓存时 [SimpleCache] 会被 release 掉，
     * 但播放器的 MediaSourceFactory 里握着的是**清空前那个** CacheDataSource.Factory，
     * 它指向的 SimpleCache 已经死了。之后每一次 createDataSource 都从死对象上读，
     * 结果是**所有在线歌都放不出来，只有杀进程重开才恢复**。
     *
     * 以前只有「改缓存档位」那条路径会 swapTo，清缓存这条漏了 —— 这就是用户说的
     * "清完缓存 / 报过格式错误之后什么歌都听不了，必须重进"。
     */
    @Volatile private var swappableHost: Swappable? = null

    /** 播放器创建时把自己那个壳登记进来，供清空/重建缓存后重新接线 */
    fun attachSwappable(host: Swappable) {
        swappableHost = host
    }

    val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "playback-cache-io").apply { isDaemon = true }
    }

    fun isEnabled(prefs: SharedPreferences): Boolean =
        // 6.7.1：默认关闭。
        //
        // 在线音频全部经 127.0.0.1 代理转发，外面再套一层 CacheDataSource 会带来
        // 一整类"拖进度条就失败"的问题：seek 到未缓存的区间时，CacheDataSource 要在
        // 同一个 span 里写洞、和代理返回的 206 边界对不齐，轻则重缓冲，重则
        // "span not found" → 播放器清缓存重试 → 越清越慢。
        // 用户要的是"点了就响、拖哪都不停"，不是省那点流量，所以默认走最短链路。
        // 想省流量仍可在设置里手动打开（功能保留，不是假开关）。
        prefs.getBoolean(KEY_ENABLED, false)

    /**
     * 流式缓存上限：用户显式设过就用用户的，否则按机型分档。
     *
     * 原来是常量 256MB。老机器（nova 6 这一类 Android 10、64~128GB 存储）上，
     * 光这一份缓存就能把可用空间吃穿：存储一紧 IO 就变慢、media3 写索引更容易出错，
     * 系统也更容易因为存储/内存压力把进程杀掉——用户看到的就是"又闪退/重启"。
     * R4 起：低端/老系统 64MB、中端 128MB、旗舰 256MB（见 DevicePerformance.cacheLimitMb）。
     */
    fun limitMb(prefs: SharedPreferences): Int =
        if (prefs.contains(KEY_LIMIT_MB)) prefs.getInt(KEY_LIMIT_MB, DEFAULT_LIMIT_MB)
        else runCatching { com.jun.musiccloud.ui.theme.DevicePerformance.cacheLimitMb }
            .getOrDefault(DEFAULT_LIMIT_MB)

    /**
     * 带 Context 的版本（6.3.1）：能解析"自动"档。
     *
     * 存储里的值可能是 [AUTO]（0），此时按分区剩余空间算；老版本存的是具体 MB，
     * 原样返回。所有需要真实上限的地方都应走这个重载。
     */
    fun limitMb(context: Context, prefs: SharedPreferences): Int {
        val raw = limitMb(prefs)
        if (raw == AUTO) return autoLimitMb(context)
        return raw.coerceIn(LIMIT_OPTIONS.first(), LIMIT_OPTIONS.last())
    }

    /** 用户选的原始档位（0 表示自动），设置页回显用 */
    fun selectedLimitMb(prefs: SharedPreferences): Int =
        if (prefs.contains(KEY_LIMIT_MB)) prefs.getInt(KEY_LIMIT_MB, DEFAULT_LIMIT_MB)
        else DEFAULT_LIMIT_MB

    /**
     * 供 ExoPlayer 用的数据源工厂。
     *
     * 未开启缓存时返回**纯 [DefaultDataSource.Factory]**——与 5.0 完全一致的一条链路，
     * 不给播放加任何中间层。
     */
    @OptIn(UnstableApi::class)
    /**
     * 6.7.1：不带缓存的纯 HTTP 工厂。
     *
     * 超时必须是 25s/30s：代理在"解析真实地址"阶段可能花 5~6 秒才回响应头，
     * Media3 默认的 8 秒连接超时会让播放器在代理还没解析完时就报网络错误，
     * 用户看到的就是"点了等一会儿，然后播放失败"。
     */
    private fun plainFactory(context: Context): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(OnlineMusicApi.userAgentForExo())
            .setConnectTimeoutMs(25_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(false)
        return DefaultDataSource.Factory(context, http)
    }

    fun dataSourceFactory(context: Context, prefs: SharedPreferences): DataSource.Factory {
        val enabled = isEnabled(prefs)
        val limit = limitMb(context, prefs)
        if (!enabled) {
            synchronized(this) { factory = null; simpleCache = null; boundLimitMb = 0 }
            return plainFactory(context)
        }
        factory?.let { if (boundLimitMb == limit) return it }
        synchronized(this) {
            val dir = File(context.cacheDir, "audio").apply { if (!exists()) mkdirs() }
            cacheDir = dir
            // 容量档位变了要重建 SimpleCache：evictor 是构造期决定的
            if (simpleCache == null || boundLimitMb != limit) {
                runCatching { simpleCache?.release() }
                simpleCache = openCache(dir, limit)
            }
            val cacheIndex = simpleCache
            if (cacheIndex == null) {
                // 缓存打不开就退回"和 5.0 完全一样"的纯联网链路。
                // 少一层缓存最多是重播要重新拉流；在这里抛出去等于让一次磁盘
                // 异常直接掀掉播放器初始化，代价完全不成比例。
                Log.w(TAG, "cache unavailable, fallback to plain datasource")
                factory = null
                boundLimitMb = 0
                return plainFactory(context)
            }
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(OnlineMusicApi.userAgentForExo())
                // 6.7.1：代理在"解析地址"阶段可能花 5~10 秒才回响应头，
                // 连接超时压在 8 秒会让播放器在代理还没解析完时就报网络错误
                //（用户看到的就是"点了等一会儿，然后播放失败"）。
                .setConnectTimeoutMs(25_000)
                .setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(false)
            val cache = CacheDataSource.Factory()
                .setCache(cacheIndex)
                .setUpstreamDataSourceFactory(http)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                // 缓存键 = 歌曲 ID + 当前档位 + **解析代次**。
                //
                // 只用 ID 会串味：同一首从「标准」切到「无损」后，老的低码率分片还在，
                // CacheDataSource 直接拿旧字节喂新解析出来的地址，用户以为升档成功了、
                // 实际听的还是 128k。档位名进键以后，换档立刻是一块新空间。
                //
                // 6.6.5 再补一个代次（`e=`，来自 [AudioProxyServer.bumpEpoch]）。
                //
                // 这是用户报「知我 / 樱花草 只响 6 秒」的根因：脚本音源给的直链**会过期**
                // （vkey / Tim 参数几小时后作废），代理那边已经重新解析出了新地址，
                // 但播放器的 URL 只要 id+档位没变，缓存键就还是同一个 ——
                // CacheDataSource 于是把上一次写到一半的残片当成本首歌的全部内容返回。
                // 表现就是：进度条按完整时长走，实际上几秒就没声了。
                //
                // 代次进键后：每次重新解析 = 一块全新空间，残片再也顶不掉新地址。
                .setCacheKeyFactory { dataSpec ->
                    val id = dataSpec.uri.getQueryParameter("id")
                    if (id.isNullOrBlank()) dataSpec.uri.toString()
                    else {
                        val epoch = dataSpec.uri.getQueryParameter("e").orEmpty()
                        "song:$id#${AudioQualityStore.current().name}@$epoch"
                    }
                }
            return DefaultDataSource.Factory(context, cache).also {
                factory = it
                boundLimitMb = limit
            }
        }
    }

    /**
     * 建立 [SimpleCache]；失败就清掉损坏目录重试一次，仍失败返回 null。
     *
     * SimpleCache 构造期要读 cache.db 索引并抢文件锁。上次进程被强杀把索引写坏、
     * 或另一个实例还持着锁时，media3 会直接抛 CacheIntegrityException /
     * IllegalStateException。这属于磁盘状态问题，不是可以靠重试解决的代码 bug，
     * 所以宁可丢缓存，也不能让它掀掉播放器和启动流程。
     */
    private fun openCache(dir: File, limitMb: Int): SimpleCache? {
        fun build(): SimpleCache? = runCatching {
            SimpleCache(dir, LeastRecentlyUsedCacheEvictor(limitMb * 1024L * 1024L))
        }.getOrNull()
        build()?.let { return it }
        Log.w(TAG, "cache open failed, rebuilding cache dir")
        runCatching {
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
        }
        return build()
    }

    /** 当前占用字节数（后台线程调用；主线程请用 [usageAsync]） */    fun usageBytes(context: Context): Long {
        val dir = cacheDir ?: File(context.cacheDir, "audio")
        return runCatching { dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
            .getOrDefault(0L)
    }

    fun usageAsync(context: Context, onResult: (Long) -> Unit) {
        io.submit {
            val bytes = usageBytes(context)
            runCatching { onResult(bytes) }
        }
    }

    /**
     * 6.3.4：缓存语义修复的**一次性迁移**。
     *
     * 为什么必须整体清、而不是只清受影响的那几首：
     * 6.3.3 及之前，缓存键是「歌曲ID + 档位」，不含音频地址，于是所有曾经
     * 拿过 30/45 秒试听片段的歌，磁盘上都躺着半首歌。升级后内存里的地址表是空的，
     * 没有「旧地址」可以用来比对，[evictSong] 根本不会触发 —— 播放器会直接
     * 命中这些历史残片，用户看到的现象就是「装了新包还是 30 秒」。
     *
     * 这是版本级的数据不可信，只能整体作废重来。代价是升级后第一遍听歌要重新缓存，
     * 换来的是从此每首都对得上真实时长。
     *
     * 用 prefs 里的 schema 号做幂等：跑过一次就不再跑，不会每次启动都清。
     */
    fun migrateIfNeeded(context: Context, prefs: SharedPreferences) {
        if (prefs.getInt(KEY_CACHE_SCHEMA, 0) >= CACHE_SCHEMA) return
        io.submit {
            runCatching {
                synchronized(this) {
                    runCatching { simpleCache?.release() }
                    simpleCache = null
                    factory = null
                    boundLimitMb = 0
                }
                val dir = File(context.cacheDir, "audio")
                if (dir.exists()) dir.deleteRecursively()
                dir.mkdirs()
                prefs.edit().putInt(KEY_CACHE_SCHEMA, CACHE_SCHEMA).apply()
                Log.i(TAG, "缓存 schema 升级到 $CACHE_SCHEMA，已清空历史残片缓存")
            }.onFailure { Log.w(TAG, "cache migrate failed: ${it.message}") }
        }
    }

    /** 清空缓存：正在播的曲目不受影响，下一首起重新拉流 */
    fun clearAsync(context: Context, onDone: () -> Unit = {}) {
        io.submit {
            runCatching {
                // SimpleCache 持有自己的索引，必须 release 再删文件，
                // 否则索引与磁盘内容不一致，下次读会报 "cache span not found"
                synchronized(this) {
                    runCatching { simpleCache?.release() }
                    simpleCache = null
                    factory = null
                    boundLimitMb = 0
                }
                val dir = File(context.cacheDir, "audio")
                if (dir.exists()) dir.deleteRecursively()
                dir.mkdirs()
            }.onFailure { Log.w(TAG, "clear cache failed: ${it.message}") }
            // 关键一步（6.5.3）：把新工厂重新接到播放器手里的壳上。
            // 少了这一步，播放器会继续用刚被 release 的 SimpleCache，
            // 表现就是"清完缓存以后所有歌都放不了，必须杀进程重开"。
            runCatching {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                synchronized(this) {
                    factory = null
                    boundLimitMb = 0
                }
                val fresh = dataSourceFactory(context, prefs)
                swappableHost?.swapTo(fresh)
                Log.i(TAG, "缓存已清空，数据源工厂已重建")
            }.onFailure { Log.w(TAG, "rebuild datasource after clear failed: ${it.message}") }
            // 腾出空间后允许再次提醒，否则「清空了但还是满」的提示会永远不再出现
            notifiedLimitMb = 0
            runCatching { onDone() }
        }
    }

    /**
     * 6.3.4：清掉某首歌**所有音质档位**的磁盘缓存条目。
     *
     * 为什么非要有这个方法：
     * 缓存键是「歌曲ID + 档位名」，**不含实际音频地址**。只要某首歌曾经以
     * 30/45 秒试听片段落过盘，之后哪怕解析到了 25MB 的完整版地址，
     * CacheDataSource 依然按同一个键命中那份旧残片，把残片字节当成新地址的内容
     * 喂给播放器——用户听到的永远是 30 秒，怎么修解析都没用，只有手动切一次
     * 音质（键里的档位名变了）才会「突然好了」。
     *
     * 这正好对应用户现场：「怎么弄都是残的，切换音质就正常了」。
     *
     * 所以凡是「换源 / 判定为残片 / 同一首歌解析结果发生变化」时，
     * 必须先调用本方法把旧字节清掉，否则新地址会被旧字节顶掉。
     *
     * 只删指定歌曲，不动其它歌；正在播放的曲目由调用方负责重载。
     * 返回实际删掉的条目数（0 表示本来就没缓存，可以直接重播）。
     */
    fun evictSong(songId: String): Int {
        val cache = simpleCache ?: return 0
        if (songId.isBlank()) return 0
        var removed = 0
        AudioQuality.values().forEach { q ->
            // 6.6.5：键里现在带代次（`@e`），老版本写下的 `song:id#档位` 也要一并清掉，
            // 否则升级上来的用户第一次播那首仍然会撞到旧的残片。
            val keys = listOf(
                "song:$songId#${q.name}",
                "song:$songId#${q.name}@0"
            ) + (0..8).map { "song:$songId#${q.name}@$it" }
            keys.distinct().forEach { key ->
                val had = runCatching { cache.isCached(key, 0L, 1L) }.getOrDefault(false)
                if (had) {
                    runCatching { cache.removeResource(key) }
                    removed++
                }
            }
        }
        if (removed > 0) Log.i(TAG, "已清除 $songId 的旧缓存 $removed 条（换源/残片修正）")
        return removed
    }

    // ==================== 写满提示（5.2） ====================

    /**
     * 一次性「缓存写满」提示。
     *
     * 缓存没有开关、也没有容量选择器（设置页那套已按用户要求撤掉），满了就靠 LRU
     * 自动覆盖旧歌——功能上没问题，但用户完全看不见它在发生。所以逼近上限时提醒一次，
     * 让他知道「不是卡了，是在循环用这块空间」，并提供一个清空入口。
     *
     * 同一个容量档位只提示一次（[notifiedLimitMb]），避免每次起播都弹；
     * 清空后重新计数。
     */
    private val _notice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()

    @Volatile private var notifiedLimitMb = 0

    /** 检查占用并在写满时置位提示。会遍历缓存目录，必须在后台线程调用。 */
    fun checkFull(context: Context, prefs: SharedPreferences) {
        if (!isEnabled(prefs)) return
        val limit = limitMb(context, prefs)
        if (notifiedLimitMb == limit) return
        val bytes = usageBytes(context)
        val threshold = limit * 1024L * 1024L * 95 / 100
        if (bytes < threshold) return
        notifiedLimitMb = limit
        _notice.value = "在线缓存已用满 $limit MB，会自动覆盖先听过的歌"
    }

    /** 界面显示完（用户点了清空或关掉）后收回提示 */
    fun consumeNotice() {
        _notice.value = null
    }

    /** 服务销毁时释放缓存句柄，避免下次启动时文件锁冲突 */
    fun release() {
        synchronized(this) {
            runCatching { simpleCache?.release() }
            simpleCache = null
            factory = null
            boundLimitMb = 0
        }
    }
}
