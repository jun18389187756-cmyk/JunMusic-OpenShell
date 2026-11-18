package com.jun.musiccloud.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashMap
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * 封面加载器：直接从音频文件读取内嵌封面。
 *
 * 三层加速，解决「进应用 / 切墙那一面封面加载慢」：
 *  1. 内存 LRU：同会话内命中，避免反复解析。
 *  2. 磁盘缓存：把解码后的缩小封面持久化到 cacheDir，进程重启后直接读图，
 *     不再重新打开音频文件解析内嵌封面（MediaMetadataRetriever 极慢）。
 *  3. 缩小到磁贴实际需要的尺寸（≤256px），大幅降低解码开销。
 *
 * 双路径解析：
 *  1. MediaMetadataRetriever.embeddedPicture —— MP3/FLAC/m4a 大多数情况 OK。
 *  2. MediaExtractor + METADATA_KEY_ALBUM_ART —— 回退路径，对 ogg 更可靠。
 */
object ArtworkLoader {

    private const val DISK_TARGET_PX = 256       // 磁贴显示尺寸，够用且解码快
    // 海报墙 6 格外扩余量下，同屏唯一封面可达 150~250 张；旧值 192 会在
    // 滚动中把刚解析完的封面挤出 LRU，再滚动时重新走 MediaMetadataRetriever
    // ——"卡片瞬间加载"的直接来源。256 张 × 256px RGB_565 ≈ 33MB，兼顾流畅与内存。
    // 进一步提升到 384：大曲库快速滚动时减少回源，384×256²×2 ≈ 50MB，仍在安全范围。
    private const val MEM_CACHE_MAX = 384

    /**
     * 内存里最多留几张封面：旗舰 512 / 通用 384 / 老机型 160（低端 128）。
     *
     * 384 张 256px RGB_565 约 50MB——在 nova 6 这类 Android 10 的老机器上，
     * 这块要和 ExoPlayer 缓冲、歌词渲染、玻璃层一起抢同一个 Heap，
     * 顶到头就是 OOM 或长时间 GC（用户体感"划两下就闪退/重启"）。
     * 160 张仍覆盖两整屏海报墙，滚动回源概率很低，内存却省掉一半多。
     * 写成 @Volatile 变量而非常量：[init] 时机型分档已就绪，按档写入。
     *
     * 6.3.1：张数上限保留为"防呆"，真正的闸门换成 [memCacheBytesMax] 字节预算。
     * 原因：2026-11-05 一台 16G 内存旗舰在播放线程 OOM（堆 256MB 打满）。
     * 光靠张数管不住峰值——封面尺寸随封面源变化（有的内嵌图 1200px，
     * 解码到 256 后仍是 256KB），512 张的上限在某些曲库下能顶到 80MB+，
     * 叠加播放缓冲、歌词层、玻璃层就顶穿了。按字节算才是硬约束。
     */
    @Volatile private var memCacheMax: Int = MEM_CACHE_MAX

    /** 封面内存预算（字节）。按机型分档写入，见 [init]。 */
    @Volatile private var memCacheBytesMax: Long = 24L * 1024 * 1024

    /**
     * 磁盘缓存容量上限。缓存键带文件指纹（路径+长度+mtime），文件一旦改动
     * 就会产生新键的副本，旧副本若无回收会永久残留、缓存只增不减。这里给出
     * 硬上限：超出后按 lastModified 淘汰最旧的封面，缩到目标的 [DISK_TRIM_RATIO]。
     * 6MB 约可容纳上千张 256px webp，覆盖常规曲库首屏与常听歌；系统也可随时整体
     * 清空 cacheDir，因此这只是主动控容，不影响正确性。压缩用 WEBP q65 进一步省空间。
     */
    private const val DISK_MAX_BYTES = 6L * 1024 * 1024
    private const val DISK_TRIM_RATIO = 0.8      // 超限后删到 80%，留出余量避免频繁触发

    /**
     * 6.9.6：磁盘缓存实际生效上限，init 按机型分档覆写。
     *
     * 用户报「每次加载封面都特别慢」：6MB 硬上限是主因之一 —— 6MB 只装得下
     * 几百张 256px webp，听一阵就被 lastModified 淘汰殆尽，同一批封面反复
     * 重新下载+解码，网络稍慢就"每次都慢"。分档后旗舰/中端机 32~64MB，
     * 千元/老机器保持小预算，系统清 cacheDir 时也只是多下几行图。
     */
    @Volatile private var diskMaxBytes: Long = DISK_MAX_BYTES

    /** 磁盘缓存目录（延迟初始化，避免在类加载时触碰 Context） */
    @Volatile private var diskDir: File? = null

    /** SAF content:// 曲目的封面解析入口 */
    @Volatile private var appContext: Context? = null

    /** 目录当前占用字节数。init 扫描一次、每次写盘累加，避免滚动时反复全目录扫描。 */
    private val diskBytes = AtomicLong(0L)
    @Volatile private var trimmedSinceOverLimit = false

    fun init(context: Context) {
        appContext = context.applicationContext
        // 按机型定封面内存上限。DevicePerformance 在 Application.onCreate 第一行 init，
        // 这里读到的分档一定是准的；万一异常就保持默认值。
        runCatching {
            val dp = com.jun.musiccloud.ui.theme.DevicePerformance
            memCacheMax = when {
                dp.isLowEnd -> 128
                dp.isOldOs -> 160
                dp.isFlagship -> 512
                else -> MEM_CACHE_MAX
            }
            // 6.3.1：同时给一个字节预算。旗舰也给上限——大内存机不等于无限，堆上限
            // 由系统按机型给（这台三星只有 256MB），预算必须跟着堆走而不是跟着内存走。
            memCacheBytesMax = when {
                dp.isLowEnd -> 10L * 1024 * 1024
                dp.isOldOs -> 14L * 1024 * 1024
                dp.isFlagship -> 28L * 1024 * 1024
                else -> 22L * 1024 * 1024
            }
            // 6.9.6：磁盘缓存同步分档（见 diskMaxBytes 注释）
            diskMaxBytes = when {
                dp.isLowEnd -> 12L * 1024 * 1024
                dp.isOldOs -> 16L * 1024 * 1024
                dp.isFlagship -> 64L * 1024 * 1024
                else -> 32L * 1024 * 1024
            }
        }
        CustomCoverStore.init(context)
        if (diskDir == null) {
            val dir = File(context.applicationContext.cacheDir, "artwork").apply { mkdirs() }
            diskDir = dir
            ioScope.launch {
                var total = 0L
                dir.listFiles()?.forEach { total += it.length() }
                diskBytes.set(total)
                if (total > diskMaxBytes) trimDisk()
            }
        }
    }

    /**
     * 磁盘缓存键：带文件指纹（长度 + mtime），文件内容变了键自然不同，
     * 不会读到过期封面。代价是三次文件 IO，所以只能在 IO 线程调用。
     */
    private fun keyOf(path: String): String {
        // 在线封面（http/https）：无本地文件指纹，直接用 URL 作键
        if (path.startsWith("http")) return path
        // SAF 曲目/封面：content:// 不能 new File，直接用 URI 作键
        if (path.startsWith("content://")) return path
        val f = java.io.File(path)
        val exists = f.exists()
        return if (exists) "$path|${f.length()}|${f.lastModified()}" else path
    }

    /**
     * 内存缓存键：纯路径，零 IO，可在主线程调用（见 [peek]）。
     *
     * 不带文件指纹是有意的：同一进程生命周期内用户几乎不会改动正在听的
     * 音频文件，而为了这个边缘情况在每次组合都做三次 stat 不划算。
     * 真发生了文件替换，[load] 走磁盘键时会发现指纹不符并重新解析。
     */
    private fun memKeyOf(path: String): String = path

    private val cache = object : LinkedHashMap<String, Bitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > memCacheMax
    }

    /**
     * 写入内存缓存并按字节预算淘汰（6.3.1）。
     *
     * 张数上限（[memCacheMax]）只管"数量"，管不住"体积"：不同封面解码后的
     * 字节数能差几十倍，一旦曲库里全是大封面，512 张能顶到 80MB+。
     * 这里每次写入后按 access-order 从最旧的开始扣，直到总量落回预算内。
     * 只从 Map 里移除、绝不 recycle——界面可能正持有这张 Bitmap，回收会直接崩。
     */
    private fun cachePut(key: String, bmp: Bitmap) {
        synchronized(lock) {
            cache[key] = bmp
            var total = 0L
            for (b in cache.values) total += b.allocationByteCount.toLong()
            if (total <= memCacheBytesMax) return
            val it = cache.entries.iterator()
            while (total > memCacheBytesMax && it.hasNext()) {
                val e = it.next()
                if (e.key == key) continue      // 刚放进去的这张先留着，否则等于白解码
                total -= e.value.allocationByteCount.toLong()
                it.remove()
            }
        }
    }
    private val lock = Any()

    // ===== 头像层：排行榜一屏 10 个圆形小图（40dp 左右），走普通封面链路会把
    // 256px 的图塞进几十 dp 的圆里白占内存，而且和封面抢同一个 LRU 名额。
    // 这里单独一个 96px 的 LRU（最多 64 张 ≈ 1.5MB），解码也走同一个并发闸，
    // 进榜出榜反复切档切页都不再重复解码。
    private const val AVATAR_TARGET_PX = 96
    private const val AVATAR_CACHE_MAX = 64
    private val avatarCache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > AVATAR_CACHE_MAX
    }
    private val avatarLock = Any()
    private val avatarInFlight =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Bitmap?>>()

    /** 头像：只查内存（组合期调用，绝不碰 IO） */
    fun peekAvatar(url: String?): Bitmap? {
        if (url.isNullOrEmpty()) return null
        synchronized(avatarLock) { return avatarCache[url] }
    }

    /**
     * 头像加载：命中内存直接返回；未命中走同一批 [decodeGate] 并发闸，
     * 解码到 96px。请求合并与封面一致（同 URL 并发只解析一次）。
     */
    suspend fun loadAvatar(url: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isNullOrEmpty()) return@withContext null
        synchronized(avatarLock) { avatarCache[url]?.let { return@withContext it } }
        val mine = kotlinx.coroutines.CompletableDeferred<Bitmap?>(null)
        val existing = avatarInFlight.putIfAbsent(url, mine)
        if (existing != null) return@withContext existing.await()
        try {
            val bytes = gated { readBytesForAvatar(url) }
            val bmp = bytes?.let { decodeSampled(it, AVATAR_TARGET_PX) }
            if (bmp != null) synchronized(avatarLock) { avatarCache[url] = bmp }
            mine.complete(bmp)
            bmp
        } catch (e: Throwable) {
            mine.complete(null)
            null
        } finally {
            avatarInFlight.remove(url, mine)
        }
    }

    private fun readBytesForAvatar(url: String): ByteArray? = runCatching {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    // ===== 高清层：仅供播放大卡 / 全屏等大图场景，先显缩略再无缝换高清 =====
    // 大图占内存（1080² ARGB_8888 ≈ 4.6MB），只留很小的 LRU；且绝不落磁盘——
    // 高清是"当前在看的这一张"的即时需求，不是要长期堆积的缓存，落磁盘会把
    // 刚压下去的存储又顶回来。切歌换图时旧的自然被挤出。
    private const val HIRES_TARGET_PX = 1080
    private const val HIRES_CACHE_MAX = 3
    private val hiResCache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > HIRES_CACHE_MAX
    }
    private val hiResLock = Any()
    private val hiResInFlight =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Bitmap?>>()

    /**
     * 解析失败的文件路径集合：同一会话内不再重试。
     * 有界 LRU（access-order）：长会话 / 大曲库下自动淘汰最旧项，避免无上限增长。
     * 值不重要，只用键做存在性判断；所有读写都在 [lock] 下串行化。
     */
    private const val FAILED_MAX = 512
    private val failedPaths = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > FAILED_MAX
    }

    /** 磁盘写入互斥：同一文件并发写保护，同时串行化容量计数与淘汰 */
    private val diskMutex = Mutex()

    /**
     * 进行中的加载：同一封面的并发请求（磁贴自加载 + 墙层预热 + 滚动中
     * 多个 prefetch 批次）合并到同一个 Deferred，只解析一次。
     * 没有它时，后到的请求会重复打开音频文件做 MediaMetadataRetriever 解析——
     * 那正是"新露出的卡片来不及加载"的排队源头。
     */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Bitmap?>>()

    /** 缓存落盘/淘汰共用的后台作用域 */
    private val ioScope = CoroutineScope(Dispatchers.IO)

    /**
     * 封面解析并发闸（4 路）。
     *
     * MediaMetadataRetriever/解码都是几十上百毫秒的重活，唱片墙一屏几十张磁贴
     * 同时 load 时，此前无限制地把 Dispatchers.IO（默认 64 线程）打满——表现为
     * 「每个都加载不出来」：全部在抢 CPU/IO，谁都出不来，还把主线程一起拖卡。
     * 限到 4 路后首屏先出、滚动逐个补齐，排队在 CompletableDeferred 上，几乎零开销。
     */
    private val decodeGate by lazy {
        kotlinx.coroutines.sync.Semaphore(
            com.jun.musiccloud.ui.theme.DevicePerformance.decodeParallelism
        )
    }

    // 6.6：scrollPause / isScrolling() 已删除。
    // 它们是 6.3.2「滚动中让解码让路」的机制：滚动时拒收新加载、松手再补。
    // 实际效果是"滚动中一行封面都不出，一松手整屏一起蹦"（用户报的 bug），
    // 且滚动开始/结束时每行 LaunchedEffect 各重启两次。封面加载已改回随滚随出，
    // CPU/IO 压力由 decodeGate 的并发上限兜住，不再需要滚动门控。

    private suspend fun <T> gated(block: suspend () -> T): T {
        // 6.6：去掉滚动退避自旋，只留并发闸。
        //
        // 自旋是 6.3.2 的设计：滚动中把解码让给列表。但下游 SongArtwork 的
        // LaunchedEffect 也把 scrolling 当 key——滚动一开始所有行的加载协程直接取消，
        // 松手时**整屏的行同时重新发起**，闸前排起长队，封面"一松手才蹦出来"，
        // 用户直接报 bug。实测解码走 IO 线程 + 上屏走绘制层，滚动中照常加载
        // 并不吃滚动帧；真要省电省 CPU，靠 decodeGate 的 4~8 路并发上限就够了。
        return decodeGate.withPermit { block() }
    }

    private fun sha1(path: String): String =
        MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun diskFile(path: String): File? = diskDir?.let { File(it, sha1(keyOf(path)) + ".webp") }

    /**
     * 同步窥视：只查内存缓存，绝不碰磁盘。
     *
     * 这个函数在 Composable 组合期被调用（SongArtwork / AlbumArt 的初始值），
     * 也就是主线程。早期版本在这里做了两件危险的事：
     *  1. keyOf() 里的 File.exists()/length()/lastModified() —— 三次同步文件 IO；
     *  2. 未命中内存时 BitmapFactory.decodeFile() —— 整张图的同步磁盘解码。
     * 列表快速滚动时每个新出现的行都要付这笔钱，直接表现为掉帧。
     *
     * 现在磁盘读取全部交给 [load] 的 IO 协程；这里只做纯内存查表（O(1)、无 IO）。
     * 代价是磁盘命中的那一帧会先显示占位、下一帧换上真图，但不再阻塞主线程。
     */
    fun peek(filePath: String?): Bitmap? {
        if (filePath.isNullOrEmpty()) return null
        val real = effectivePath(filePath)
        synchronized(lock) { return cache[memKeyOf(real)] }
    }

    /** 自定义封面优先：命中映射后，一切走裁剪文件，列表/舞台/播放卡同步生效。 */
    private fun effectivePath(path: String): String = CustomCoverStore.overrideFor(path) ?: path

    /** 换/删封面后清掉内存缓存，下一帧重新读图。 */
    fun invalidate(path: String) {
        val real = effectivePath(path)
        synchronized(lock) {
            cache.remove(path)
            cache.remove(real)
            failedPaths.remove(path)
            failedPaths.remove(real)
        }
        synchronized(hiResLock) {
            hiResCache.remove(path)
            hiResCache.remove(real)
        }
    }

    fun invalidateAll() {
        synchronized(lock) { cache.clear(); failedPaths.clear() }
        synchronized(hiResLock) { hiResCache.clear() }
    }

    /**
     * 内存告急时主动缩容（系统 trim 回调调用）。
     *
     * 封面是全 App 最大的一块**可重建**内存：丢掉只是下次再解码一次，
     * 磁盘里那份缩小图还在，回来很快。不 recycle Bitmap——界面可能正拿着它画，
     * 只从缓存里摘引用，让 GC 自己收。
     * @param full true = 整层清空（进程已到后台/系统极度紧张）
     */
    fun trimMemory(full: Boolean) {
        runCatching {
            synchronized(lock) {
                if (full) {
                    cache.clear()
                } else {
                    val keep = cache.size / 3
                    val it = cache.entries.iterator()
                    while (it.hasNext() && cache.size > keep) {
                        it.next()
                        it.remove()
                    }
                }
            }
            synchronized(hiResLock) { hiResCache.clear() }
        }
    }

    /**
     * 批量预取封面：把传入的文件路径对应的封面加载进内存缓存，不返回结果。
     * 用于切歌前预取下一首 / 上一首封面，或列表滚动停止后预取可见区前后的封面，
     * 消除"切歌瞬间封面转圈"的加载闪烁。所有解析走 IO 线程并复用 inFlight 合并。
     */
    fun prefetch(filePaths: List<String?>) {
        if (filePaths.isEmpty()) return
        // 6.3.2：**并发**预取。原来是一个 forEach 串行 await，一屏 20 张 = 20 次
        // 磁盘读 + 解码排队，最后一排封面要等前面全部做完才出来——这就是
        // 「有些封面加载太慢」。改成每张一个协程，真正的并发由 decodeGate 控制。
        val clean = filePaths.filterNotNull().filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return
        clean.forEach { path ->
            ioScope.launch {
                synchronized(lock) { if (cache[memKeyOf(effectivePath(path))] != null) return@launch }
                runCatching { load(path) }
            }
        }
    }

    suspend fun load(filePath: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (filePath.isNullOrEmpty()) return@withContext null
        // 自定义封面优先；内存用纯路径键（与 peek 一致），磁盘用带指纹的键（防过期）
        val real = effectivePath(filePath)
        val key = memKeyOf(real)
        synchronized(lock) {
            cache[key]?.let { return@withContext it }
            if (real in failedPaths) return@withContext null
        }

        // 请求合并：已有同路径解析在途时挂到它上面等结果，绝不重复解析。
        // putIfAbsent 返回非 null 即"别人已在做"。
        val mine = kotlinx.coroutines.CompletableDeferred<Bitmap?>(null)
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) return@withContext existing.await()

        try {
            val bmp = gated { loadUncached(real, key) }
            mine.complete(bmp)
            bmp
        } catch (e: Throwable) {
            // 封面解析失败不该拖垮界面：调用方（LaunchedEffect）没有 try/catch，
            // 一旦这里抛出会把整个界面带崩，退化成占位色块即可。
            mine.complete(null)
            null
        } finally {
            inFlight.remove(key, mine)
        }
    }

    /**
     * 高清封面：按需读取原图内嵌封面、解码到 [HIRES_TARGET_PX]（相对磁贴缓存的
     * 256px 大得多），只给播放大卡 / 全屏等大图场景。
     *
     * 用法配合：UI 先用 [peek]/[load] 拿到缩略图立即显示（不割裂），再异步调本函数
     * 拿高清并在就绪后无缝替换（清晰）。缩略图来自磁盘缓存，高清只在内存、绝不落盘。
     *
     * 找不到高清（无封面/解码失败）时返回 null，UI 应继续沿用缩略图。
     */
    suspend fun loadHiRes(filePath: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (filePath.isNullOrEmpty()) return@withContext null
        val real = effectivePath(filePath)
        synchronized(hiResLock) {
            hiResCache[real]?.let { return@withContext it }
        }
        if (real in failedPaths) return@withContext null

        val mine = kotlinx.coroutines.CompletableDeferred<Bitmap?>(null)
        val existing = hiResInFlight.putIfAbsent(real, mine)
        if (existing != null) return@withContext existing.await()

        try {
            // 6.3.1：堆已经用到 70% 以上时，高清封面这一张（1080² ARGB ≈ 4.6MB）不让解了。
            // 收益只有"更清楚"，代价可能是整个进程 OOM——播放线程拿不到内存时，
            // 用户看到的是听着歌突然闪退。这里直接放弃，界面继续用缩略图，观感几乎无损。
            if (heapHeadroomLow()) { mine.complete(null); return@withContext null }
            val bmp = gated {
                val bytes = readPictureBytes(real)
                if (bytes == null) null else decodeSampled(bytes, HIRES_TARGET_PX)
            }
            if (bmp != null) synchronized(hiResLock) { hiResCache[real] = bmp }
            mine.complete(bmp)
            bmp
        } catch (e: Throwable) {
            mine.complete(null)
            null
        } finally {
            hiResInFlight.remove(real, mine)
        }
    }

    /**
     * 堆余量是否已经很低（6.3.1 大图解码闸门）。
     *
     * 用 used/max 而不是"可用 MB"：安卓给每个进程的堆上限不同（同一台机的
     * 系统堆可能只有 256MB），按比例判才适用于所有机型。
     */
    private fun heapHeadroomLow(): Boolean = runCatching {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        used > (rt.maxMemory() * 0.70).toLong()
    }.getOrDefault(false)

    /** 同步窥高清缓存：切歌前若这张已看过高清则首帧直接命中，避免再走一次缩略→高清过渡。 */
    fun peekHiRes(filePath: String?): Bitmap? {
        if (filePath.isNullOrEmpty()) return null
        val real = effectivePath(filePath)
        synchronized(hiResLock) { return hiResCache[real] }
    }

    private fun loadUncached(filePath: String, key: String): Bitmap? {
        // 磁盘缓存命中：直接读图，跳过音频解析
        val df = diskFile(filePath)
        if (df != null && df.exists()) {
            val bmp = runCatching { decodeFileSampled(df.absolutePath) }.getOrNull()
            if (bmp != null) {
                cachePut(key, bmp)
                return bmp
            }
        }

        val bytes = readPictureBytes(filePath)
        if (bytes == null) {
            // 网络封面（http/https）失败不永久拉黑：可能只是瞬时网络抖动 / 首次被图床
            // 拒绝，下次仍有取到的机会；若一并拉黑，就再也不会重试，表现为"封面永远
            // 加载不出来"。本地文件路径失败才置入失败集合（同会话内不再重试）。
            if (!filePath.startsWith("http")) {
                synchronized(lock) { failedPaths[filePath] = Unit }
            }
            return null
        }

        val bmp = decodeSampled(bytes)
        if (bmp != null) {
            cachePut(key, bmp)
            // 异步落盘（不阻塞返回，封面先显示，缓存后补）
            if (df != null) {
                ioScope.launch {
                    diskMutex.withLock {
                        runCatching {
                            val existed = df.length()
                            val out = java.io.FileOutputStream(df)
                            bmp.compress(Bitmap.CompressFormat.WEBP, 65, out)
                            out.flush(); out.close()
                            diskBytes.addAndGet(df.length() - existed)
                        }
                        // 写超了才淘汰；trim 后留余量，缩回阈值以下前不再重复扫描
                        if (diskBytes.get() > diskMaxBytes) trimDiskLocked() else trimmedSinceOverLimit = false
                    }
                }
            }
        }
        return bmp
    }

    /**
     * 容量淘汰：按 lastModified 从旧到新删除，直到占用缩到上限的 [DISK_TRIM_RATIO]。
     * 一次淘汰后立标志位，避免在重新涨回上限前每张图都全目录扫描排序。需持有 diskMutex。
     */
    private suspend fun trimDisk() = diskMutex.withLock { trimDiskLocked() }

    private fun trimDiskLocked() {
        val dir = diskDir ?: return
        if (diskBytes.get() <= diskMaxBytes) { trimmedSinceOverLimit = false; return }
        if (trimmedSinceOverLimit) return
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        val target = (diskMaxBytes * DISK_TRIM_RATIO).toLong()
        var used = diskBytes.get()
        for (f in files) {
            if (used <= target) break
            val len = f.length()
            if (f.delete()) used -= len
        }
        diskBytes.set(if (used < 0L) 0L else used)
        trimmedSinceOverLimit = diskBytes.get() > diskMaxBytes
    }

    /**
     * 解码封面到目标边长。
     * @param targetPx 目标边长；小图（磁贴）走 RGB_565 省内存，大图（高清）走 ARGB_8888
     *   保色深避免渐变带状。
     */
    /**
     * 从磁盘文件解码（6.3.1）：同样走降采样 + RGB_565。
     *
     * 原来这里是 [BitmapFactory.decodeFile] 一把梭——文件多大就解多大。
     * 磁盘缓存是用 256px 的图压的，正常情况下没问题；但自定义封面（[CustomCoverStore]）
     * 和旧版本遗留的大图会原尺寸解出来，一张 3000px 的图就是 36MB，
     * 正好撞在"播放线程 OOM"的现场里。统一走采样，峰值封在目标尺寸。
     */
    private fun decodeFileSampled(path: String, targetPx: Int = DISK_TARGET_PX): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx &&
            bounds.outHeight / (sample * 2) >= targetPx
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun decodeSampled(bytes: ByteArray, targetPx: Int = DISK_TARGET_PX): Bitmap? {        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx &&
            bounds.outHeight / (sample * 2) >= targetPx
        ) {
            sample *= 2
        }
        // 小图（≤256px 磁贴）用 RGB_565：内存减半，满屏几十张卡片占用腰斩，肉眼在缩略
        // 尺寸下几乎看不出带状；高清大图用 ARGB_8888 保色深，全屏观感更干净。
        val hiRes = targetPx > DISK_TARGET_PX
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (hiRes) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        }
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    /**
     * 读取内嵌封面原始字节，三条路径依次兜底：
     *  1. MediaMetadataRetriever —— 大多数规范文件最快、最省事。
     *  2. MediaExtractor —— ogg/opus 等场景回退。
     *  3. 自解析 ID3v2 APIC —— MMR 对部分来源（如 QQ 音乐带内嵌歌词 USLT +
     *     巨型 APIC 的 MP3）会解析中断、embeddedPicture 返回 null，MediaExtractor
     *     的 ALBUM_ART key 在 MP3 上也常拿不到。此时直接扫 ID3 标签取 APIC，
     *     覆盖这些前两路都失败的文件。
     */
    private fun readPictureBytes(filePath: String): ByteArray? {
        // 在线封面：先从磁盘缓存取（6.3 新增），没有再下并落盘。
        //
        // 6.2 这里是直接 fetchBytes：每次冷启动、每次列表滚动到新行都会重新下，
        // 首页 20 张封面 = 20 次网络请求。这就是用户说的"每次进去加载特别慢"的一大半。
        // 现在 cacheDir 里有一份按 URL 哈希的 360px JPEG，命中即零网络。
        // 注意：磁盘缓存只缓存"压缩后的副本"，返回给解码器的仍是原字节，首屏清晰度不变。
        if (filePath.startsWith("http")) {
            val ctx = appContext
            if (ctx != null) {
                com.jun.musiccloud.data.CoverCache.get(ctx, filePath)?.let { if (it.isNotEmpty()) return it }
                // 6.3.2：原来这里是 fetchToDisk ?: fetchBytes —— 失败时**再串行**打一次网络，
                // 一张封面失败就是两次超时叠加（最长 20s+），在 4 路解码闸里把整排封面堵死。
                // fetchToDisk 内部已经是一次完整下载，失败就交给上层重试逻辑（见 SongArtwork），
                // 这里不再叠第二次。
                return com.jun.musiccloud.data.CoverCache.fetchToDisk(ctx, filePath)
            }
            return com.jun.musiccloud.data.OnlineMusicApi.fetchBytes(filePath)
        }

        // 纯图片文件（头像等）：直接读文件字节，跳过音频内嵌封面解析
        val lower = filePath.lowercase()
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")) {
            return runCatching {
                val f = java.io.File(filePath)
                if (!f.exists() || f.length() <= 0L) return@runCatching null
                f.readBytes()
            }.getOrNull()
        }

        // SAF content://：图片直接读流；音频交给支持 Context + Uri 的解析器
        if (filePath.startsWith("content://")) {
            val ctx = appContext ?: return null
            val uri = android.net.Uri.parse(filePath)
            val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull().orEmpty()
            if (mime.startsWith("image/")) {
                return runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(ctx, uri)
                    retriever.embeddedPicture?.let { if (it.isNotEmpty()) return it }
                } finally {
                    runCatching { retriever.release() }
                }
            }
            runCatching {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(ctx, uri, null)
                    (0 until extractor.trackCount).forEach { i ->
                        val format = extractor.getTrackFormat(i)
                        format.keys?.forEach { key ->
                            val value = format.getByteBuffer(key)
                            if (value != null && value.remaining() > 4) {
                                val arr = ByteArray(value.remaining())
                                value.get(arr)
                                if ((arr[0] == 0xFF.toByte() && arr[1] == 0xD8.toByte()) ||
                                    (arr[0] == 0x89.toByte() && arr[1] == 0x50.toByte())
                                ) {
                                    return arr
                                }
                            }
                        }
                    }
                } finally {
                    runCatching { extractor.release() }
                }
            }
            return null
        }

        // 路径 1：MediaMetadataRetriever
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(filePath)
                retriever.embeddedPicture?.let { if (it.isNotEmpty()) return it }
            } finally {
                runCatching { retriever.release() }
            }
        }

        // 路径 2：MediaExtractor（ogg/opus 等场景回退）
        runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(filePath)
                (0 until extractor.trackCount).forEach { i ->
                    val format = extractor.getTrackFormat(i)
                    format.keys?.forEach { key ->
                        val value = format.getByteBuffer(key)
                        if (value != null && value.remaining() > 4) {
                            val arr = ByteArray(value.remaining())
                            value.get(arr)
                            // 封面特征：JPEG/PNG 魔数
                            if ((arr[0] == 0xFF.toByte() && arr[1] == 0xD8.toByte()) ||
                                (arr[0] == 0x89.toByte() && arr[1] == 0x50.toByte())
                            ) {
                                return arr
                            }
                        }
                    }
                }
            } finally {
                runCatching { extractor.release() }
            }
        }

        // 路径 3：手动解析 ID3v2 的 APIC 帧
        return runCatching { parseId3v2Apic(filePath) }.getOrNull()
    }

    /**
     * 直接从 MP3 文件头部的 ID3v2 标签里抽取 APIC（内嵌封面）帧的图片字节。
     *
     * 只做够用的解析：ID3v2.2/2.3/2.4 帧遍历、tag 级 unsynchronisation 反转、
     * 扩展头跳过、APIC/PIC 帧的编码/MIME/描述字段跳过。找到第一张封面即返回。
     * 不依赖系统解析器，专治 MMR 读不到封面的文件。
     */
    private fun parseId3v2Apic(filePath: String): ByteArray? {
        val file = File(filePath)
        if (!file.exists() || file.length() < 10) return null

        java.io.RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            if (raf.read(header) != 10) return null
            if (header[0] != 'I'.code.toByte() ||
                header[1] != 'D'.code.toByte() ||
                header[2] != '3'.code.toByte()
            ) return null

            val major = header[3].toInt() and 0xFF
            val flags = header[5].toInt() and 0xFF
            val tagSize = synchsafe(header, 6)
            if (tagSize <= 0 || tagSize > file.length()) return null

            var tag = ByteArray(tagSize)
            if (raf.read(tag) != tagSize) return null

            // tag 级 unsynchronisation（v2.2/2.3 用 header flag 0x80）：还原 0xFF 0x00 -> 0xFF
            if (flags and 0x80 != 0) tag = deunsync(tag)

            var pos = 0
            // 扩展头
            if (flags and 0x40 != 0) {
                pos += if (major >= 4) {
                    synchsafe(tag, 0)
                } else {
                    beInt(tag, 0) + 4
                }
            }

            val idLen = if (major == 2) 3 else 4
            val sizeLen = if (major == 2) 3 else 4
            val frameFlagsLen = if (major == 2) 0 else 2

            while (pos + idLen + sizeLen + frameFlagsLen <= tag.size) {
                if (tag[pos].toInt() == 0) break // 到达填充区
                val id = String(tag, pos, idLen, Charsets.ISO_8859_1)
                val frameSize = when {
                    major == 2 -> ((tag[pos + 3].toInt() and 0xFF) shl 16) or
                        ((tag[pos + 4].toInt() and 0xFF) shl 8) or
                        (tag[pos + 5].toInt() and 0xFF)
                    major >= 4 -> synchsafe(tag, pos + 4)
                    else -> beInt(tag, pos + 4)
                }
                if (frameSize <= 0) break
                val bodyPos = pos + idLen + sizeLen + frameFlagsLen
                if (bodyPos + frameSize > tag.size) break

                if (id == "APIC" || id == "PIC") {
                    val pic = extractApicImage(tag, bodyPos, frameSize, id == "PIC")
                    if (pic != null && pic.size > 4) return pic
                }
                pos = bodyPos + frameSize
            }
        }
        return null
    }

    /** 从 APIC/PIC 帧体里跳过编码/MIME/图片类型/描述，返回图片原始字节。 */
    private fun extractApicImage(tag: ByteArray, start: Int, size: Int, isV22: Boolean): ByteArray? {
        var i = start
        val end = start + size
        if (i >= end) return null
        val enc = tag[i].toInt() and 0xFF; i++
        if (isV22) {
            i += 3 // v2.2：3 字节图片格式（如 "JPG"）
        } else {
            while (i < end && tag[i].toInt() != 0) i++ // MIME 以 0x00 结尾
            i++
        }
        if (i >= end) return null
        i++ // 图片类型 1 字节
        // 描述：编码 1/2（UTF-16）以双 0x00 结尾，0/3（Latin1/UTF-8）以单 0x00 结尾
        if (enc == 1 || enc == 2) {
            while (i + 1 < end && !(tag[i].toInt() == 0 && tag[i + 1].toInt() == 0)) i += 2
            i += 2
        } else {
            while (i < end && tag[i].toInt() != 0) i++
            i++
        }
        if (i >= end) return null
        return tag.copyOfRange(i, end)
    }

    /** ID3 synchsafe 整数（每字节仅低 7 位有效）。 */
    private fun synchsafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)

    /** 普通大端 32 位整数。 */
    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    /** 反 unsynchronisation：把 0xFF 0x00 序列还原为 0xFF。 */
    private fun deunsync(src: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(src.size)
        var i = 0
        while (i < src.size) {
            out.write(src[i].toInt())
            if (src[i] == 0xFF.toByte() && i + 1 < src.size && src[i + 1] == 0x00.toByte()) i++
            i++
        }
        return out.toByteArray()
    }
}
