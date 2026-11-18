package com.jun.musiccloud.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 自定义封面：把用户选的图裁成正方形后存进应用私有目录，并按「歌曲封面键」
 * （本地文件路径 / 在线封面 URL）建立映射。
 *
 * ArtworkLoader 在解析前会查这张表，命中就直接读裁剪后的文件，所以列表、播放卡、
 * 14 个歌词舞台、唱片墙都会同步换封面，不需要每个调用点单独适配。
 */
object CustomCoverStore {
    private const val PREFS = "jun_custom_covers"
    private const val DIR = "custom_covers"
    private var prefs: SharedPreferences? = null
    private val overrides = HashMap<String, String>()

    /** 版本号：Compose 侧把它读进 remember key，改封面后即时重组。 */
    var version by mutableIntStateOf(0)
        private set

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val loaded = HashMap<String, String>()
        for ((k, v) in p.all) {
            if (k is String && v is String && v.isNotBlank()) loaded[k] = v
        }
        overrides.clear()
        overrides.putAll(loaded)
        prefs = p
    }

    @Synchronized
    fun overrideFor(key: String?): String? {
        if (key.isNullOrEmpty()) return null
        val path = overrides[key] ?: return null
        // 文件被清理或用户手动删除：自动退回原封面，避免一直显示占位。
        if (path.startsWith("/") && !File(path).isFile) {
            overrides.remove(key)
            prefs?.edit()?.remove(key)?.apply()
            return null
        }
        return path
    }

    @Synchronized
    fun hasOverride(key: String?): Boolean = overrideFor(key) != null

    @Synchronized
    fun set(key: String, path: String) {
        if (key.isBlank() || path.isBlank()) return
        overrides[key] = path
        prefs?.edit()?.putString(key, path)?.apply()
        version++
        ArtworkLoader.invalidate(key)
    }

    @Synchronized
    fun clear(key: String) {
        val old = overrides.remove(key)
        prefs?.edit()?.remove(key)?.apply()
        if (old != null && old.startsWith("/")) runCatching { File(old).delete() }
        version++
        ArtworkLoader.invalidate(key)
    }

    @Synchronized
    fun clearAll() {
        overrides.values.forEach { path ->
            if (path.startsWith("/")) runCatching { File(path).delete() }
        }
        overrides.clear()
        prefs?.edit()?.clear()?.apply()
        version++
        ArtworkLoader.invalidateAll()
    }

    /** 已替换封面的条目（歌曲键 → 裁剪图路径），按路径稳定排序，管理页用。 */
    @Synchronized
    fun entries(): List<Pair<String, String>> =
        overrides.entries
            .filter { it.value.isNotBlank() }
            .map { it.key to it.value }
            .sortedBy { it.first }

    /** 自定义封面占用的磁盘空间（字节）。 */
    @Synchronized
    fun totalBytes(): Long =
        overrides.values.sumOf { path ->
            if (path.startsWith("/")) runCatching { File(path).length() }.getOrDefault(0L) else 0L
        }

    /** 清理目录里没有被任何映射引用的孤儿文件。 */
    @Synchronized
    fun pruneOrphans(context: Context): Int {
        val dir = File(context.filesDir, DIR)
        if (!dir.isDirectory) return 0
        val used = overrides.values.filter { it.startsWith("/") }.toHashSet()
        var removed = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.absolutePath !in used) {
                if (runCatching { f.delete() }.getOrDefault(false)) removed++
            }
        }
        return removed
    }

    /**
     * 把用户选的图按「缩放 + 水平/垂直偏移」裁成正方形并落盘。
     *
     * @param zoom 1..3，越大裁得越紧
     * @param offsetX -1..1，0 居中，正数取右侧
     * @param offsetY -1..1，0 居中，正数取下方
     * @return 裁剪后的文件路径；失败返回 null
     */
    suspend fun saveCropped(
        context: Context,
        key: String,
        source: Uri,
        zoom: Float,
        offsetX: Float,
        offsetY: Float
    ): String? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(source)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null
        val src = runCatching { decodeMax(bytes, 2000) }.getOrNull() ?: return@withContext null
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return@withContext null
        val z = zoom.coerceIn(1f, 3f)
        val size = (min(w, h) / z).toInt().coerceAtLeast(16)
        val maxX = (w - size).coerceAtLeast(0)
        val maxY = (h - size).coerceAtLeast(0)
        val x = ((maxX / 2f) + offsetX.coerceIn(-1f, 1f) * (maxX / 2f))
            .toInt().coerceIn(0, maxX)
        val y = ((maxY / 2f) + offsetY.coerceIn(-1f, 1f) * (maxY / 2f))
            .toInt().coerceIn(0, maxY)
        val square = runCatching { Bitmap.createBitmap(src, x, y, size, size) }.getOrNull()
            ?: return@withContext null
        val out = Bitmap.createScaledBitmap(square, 768, 768, true)
        if (out !== square) square.recycle()
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        // 每次写新文件，旧文件由 clear/替换时删除，避免缓存键不变读到旧图。
        val target = File(dir, "cover_${System.currentTimeMillis()}.jpg")
        val ok = runCatching {
            FileOutputStream(target).use { fos ->
                out.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                fos.flush()
            }
        }.isSuccess
        out.recycle()
        src.recycle()
        if (!ok) return@withContext null
        val previous = overrideFor(key)
        set(key, target.absolutePath)
        // set 里已经 remove 掉旧映射；这里补删旧图，防目录无限增长。
        if (previous != null && previous != target.absolutePath && previous.startsWith("/")) {
            runCatching { File(previous).delete() }
        }
        target.absolutePath
    }

    private fun decodeMax(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }
}
