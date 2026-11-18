package com.jun.musiccloud.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 封面磁盘缓存（6.3 新增）。
 *
 * 为什么这一条直接对应「App 每次进去加载特别慢」
 * ------------------------------------------
 * 6.2 里封面只有内存缓存（[com.jun.musiccloud.ui.ArtworkLoader] 的 LruCache）。
 * 内存缓存的生命周期就是进程生命周期：进一次 App 全部重下，首页 20 张封面 =
 * 20 次 HTTP 请求 + 20 次 JPEG 解码，在网络差的移动数据下就是白屏三秒。
 * 用户说的"每次进去加载特别慢"里，有一大半是这件事，不是数据库。
 *
 * 现在加一层磁盘缓存：URL → sha256 → `cache/cover/<hash>.jpg`。
 *   - 命中直接读文件，零网络；
 *   - 未命中才下，下完顺手压成 360px/JPEG78 落盘（原图 500×500 PNG 有 300KB，
 *     压完 20~35KB，一屏封面从 6MB 降到 600KB）；
 *   - 老设备上的原始大图也一并降级，解码时间跟着减少。
 *
 * 容量策略
 * --------
 * 上限 80MB / 2000 个文件，超了按"最后修改时间"删最旧的 20%。
 * 为什么是 80MB：一个重度用户常听的歌 3000 首左右，按每张 30KB 算是 90MB，
 * 砍到 2000 张正好覆盖"最近在听"的那部分，再往前的封面重下也就一次。
 *
 * 线程安全
 * --------
 * 所有公开方法都能从任意线程调（ArtworkLoader 在 IO 线程池里跑）。写文件用
 * 「临时文件 + rename」，避免读到写了一半的图——那会显示成灰色块。
 */
object CoverCache {

    private const val DIR = "cache/cover"
    private const val MAX_BYTES = 80L * 1024 * 1024
    private const val MAX_FILES = 2000
    private const val TARGET_EDGE = 360

    @Volatile
    private var lastPrune = 0L

    private fun dir(ctx: Context): File =
        File(ctx.applicationContext.cacheDir, DIR).apply { if (!exists()) mkdirs() }

    private fun keyOf(url: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(40)
    }

    private fun fileOf(ctx: Context, url: String): File = File(dir(ctx), keyOf(url) + ".jpg")

    /** 磁盘上有没有这张 */
    fun has(ctx: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val f = runCatching { fileOf(ctx, url) }.getOrNull() ?: return false
        return f.exists() && f.length() > 0
    }

    /** 读缓存；没有返回 null */
    fun get(ctx: Context, url: String): ByteArray? {
        if (url.isBlank()) return null
        val f = runCatching { fileOf(ctx, url) }.getOrNull() ?: return null
        if (!f.exists()) return null
        return runCatching { f.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 下载并落盘，返回原图字节（供调用方立刻解码显示）。
     *
     * 落盘的那份是压缩过的，但**返回给调用方的仍是原字节**——首屏要的是清晰度，
     * 下次进来读压缩版也够用。这样既不拖慢首次显示，又让第二次变快。
     */
    fun fetchToDisk(ctx: Context, url: String, timeoutMs: Int = 12_000): ByteArray? {
        if (url.isBlank()) return null
        // 合并同 URL 的并发请求：命中在途就等它，不重复下载
        val mine = java.util.concurrent.CompletableFuture<ByteArray?>()
        val existing = dlInFlight.putIfAbsent(url, mine)
        if (existing != null) {
            return runCatching { existing.get(timeoutMs + 3000L, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .getOrNull()
        }
        try {
            // 并发闸：最多 4 路同时下
            dlGate.acquire()
            val raw = try {
                runCatching {
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 4_000
                    c.readTimeout = timeoutMs
                    c.instanceFollowRedirects = true
                    // 6.3.2：高优先级缓存头。封面落盘之后几乎不会再走网络，
                    // 加上它能让图床/CDN 直接把图缓到边缘节点，冷启动更快
                    c.setRequestProperty("Cache-Control", "max-age=2592000")
                    c.setRequestProperty("User-Agent", "JunMusic")
                    c.setRequestProperty("Referer", "https://music.163.com/")
                    c.inputStream.use { it.readBytes() }
                }.getOrNull()
            } finally {
                dlGate.release()
            }
            if (raw == null || raw.isEmpty()) {
                mine.complete(null); return null
            }
            runCatching { write(ctx, url, raw) }
            mine.complete(raw)
            return raw
        } finally {
            dlInFlight.remove(url, mine)
        }
    }

    /** 把字节写成缓存（会压到 360px）。失败静默——缓存不是功能，不能因为它崩溃 */
    fun write(ctx: Context, url: String, raw: ByteArray) {
        if (url.isBlank() || raw.isEmpty()) return
        val target = runCatching { fileOf(ctx, url) }.getOrNull() ?: return
        // 6.3.2：先落盘再压缩。原来压缩（解码 + 缩放 + 重编码，一张 30~80ms）
        // 是同步跑在 fetchToDisk 里的，一屏 20 张就是 1.5 秒的纯等待——
        // 封面显示慢有一半是它。现在原图立刻落盘、立刻返回，压缩挪到后台补齐。
        val tmp = File(target.parentFile, target.name + ".tmp")
        val ok = runCatching {
            tmp.writeBytes(raw)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) { tmp.delete(); false } else true
            } else true
        }.getOrDefault(false)
        if (!ok) return
        backfillPool.execute {
            runCatching {
                if (!target.exists()) return@runCatching
                val encoded = shrink(raw)
                if (encoded.size >= raw.size) return@runCatching
                val t2 = File(target.parentFile, target.name + ".c.tmp")
                t2.writeBytes(encoded)
                if (!t2.renameTo(target)) { t2.delete() }
            }
            runCatching { pruneIfNeeded(ctx) }
        }
    }

    /**
     * 后台压缩线程池：2 路就够。
     *
     * 不用 Dispatchers.IO 的原因：这个压缩是纯 CPU + 短磁盘写，且必须在
     * 用户滚动时也不至于把 IO 池占满（解码封面本来就在抢同一个池）。
     */
    private val backfillPool: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
            Thread(r, "cover-shrink").apply { isDaemon = true }
        }

    /** 同时在飞的封面下载上限：4 路。再高会在弱网下互相拖慢、还会触发图床限流。 */
    private val dlGate = java.util.concurrent.Semaphore(4)

    /** 同一 URL 的并发下载合并成一个，避免磁贴 + 预热 + 重试同时打三次 */
    private val dlInFlight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<ByteArray?>>()

    private fun shrink(raw: ByteArray): ByteArray {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return raw
        // 已经是小图就不折腾，避免二次压缩掉画质
        if (maxOf(w, h) <= TARGET_EDGE && raw.size < 120 * 1024) return raw
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= TARGET_EDGE) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val src = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return raw
        val scale = TARGET_EDGE.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 0.95f) {
            android.graphics.Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { if (it !== src) src.recycle() }
        } else src
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, bos)
        runCatching { bmp.recycle() }
        return bos.toByteArray()
    }

    /**
     * 超限就删。用 10 分钟节流：每次写都扫一遍目录，2000 个文件时足够慢到
     * 影响滚动帧率（stat 两千次大约 30~60ms）。
     */
    private fun pruneIfNeeded(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastPrune < 600_000L) return
        lastPrune = now
        runCatching {
            val files = dir(ctx).listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_BYTES && files.size <= MAX_FILES) return
            val sorted = files.sortedBy { it.lastModified() }
            val dropCount = if (files.size > MAX_FILES) {
                files.size - (MAX_FILES * 4 / 5)
            } else {
                val target = MAX_BYTES * 4 / 5
                var acc = 0L
                var n = 0
                for (f in sorted) {
                    if (total - acc <= target) break
                    acc += f.length()
                    n++
                }
                n
            }
            sorted.take(dropCount).forEach { it.delete() }
            total = 0
        }
    }

    /** 当前占用字节数（设置页「存储占用」显示用） */
    fun sizeBytes(ctx: Context): Long =
        runCatching { dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    /** 清空（设置页「清除缓存」用） */
    fun clear(ctx: Context) {
        runCatching { dir(ctx).listFiles()?.forEach { it.delete() } }
    }
}
