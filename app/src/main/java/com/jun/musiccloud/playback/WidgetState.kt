package com.jun.musiccloud.playback

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.media3.common.Player
import com.jun.musiccloud.ui.theme.DevicePerformance
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 桌面小组件的「最后状态」快照。
 *
 * 小组件不能再从 BroadcastReceiver 里 bind MediaSession（Android 16 会直接抛
 * ReceiverCallNotAllowedException），所以改由播放服务在状态变化时把标题、歌手、
 * 播放状态写进 SharedPreferences；小组件 onUpdate 只读快照渲染。
 *
 * 封面同样不能在 RemoteViews 里塞 http(s) URI（Android 16 会抛
 * SecurityException: Disallowed URI ... in RemoteViews）。服务把封面解码后
 * 压成 300px 的 JPEG 放到 filesDir，小组件读本地文件转 Bitmap 再 setImageViewBitmap。
 */
/**
 * 小组件控制广播的来路闸门（6.2）。
 *
 * 为什么要它：两颗小组件（4×2 / 4×4）的 receiver 是 `exported="true"`——桌面要往这里发
 * APPWIDGET_UPDATE，这是小组件能工作的**前提**，不能关。但动作串
 * `com.jun.musiccloud.widget(.4x4).PREV/PLAY/NEXT` 是明文的，以前 onReceive 不认来路，
 * 任何装了个不起眼 App 的人都能发这条广播：远程控制你的播放，服务没起来时那条分支还会
 * 把 MainActivity 拉到前台（能把 App 顶到你正在用的东西上面）。
 *
 * 判据用「每次安装生成一次的私有令牌」，不用 signature 权限，两个原因：
 * 1) `PendingIntent.getBroadcast()` 根本没有「要求接收方持有某权限」的重载（只有
 *    flags + Bundle options 那一个版本），想把权限挂在自己发出的按钮上挂不进去；
 * 2) `checkCallingOrSelfPermission()` 在 onReceive 里没有调用方身份时会回落成「查自己」，
 *    而本 App 自己 `<uses-permission>` 了那条 signature 权限，于是永远返回 GRANTED——
 *    检查等于不存在；反过来真按发广播的那个身份查，桌面替我们代发的那一次又会被误杀，
 *    按钮整个失灵。
 * 令牌只写进本 App 私有 SharedPreferences，第三方读不到也猜不到（SecureRandom 128bit），
 * 伪造不出带正确令牌的 Intent；再配一道「发送包名要么为空（PendingIntent 代发，
 * getSendingPackageName 对 PendingIntent 返回 null）要么就是本包」的兜底。
 * 两条都只在**控制类动作**上查，桌面自己那几条标准动作照旧放过，小组件刷新不受影响。
 */
internal const val EXTRA_WIDGET_TOKEN = "com.jun.musiccloud.extra.WIDGET_ACTION_TOKEN"

/**
 * Android 13（API 33）起才允许 BroadcastReceiver 问「这条广播是谁发的」，
 * 低版本只能拿到 null（=未知），判据退化成「只看令牌」。用 runCatching 兜一层：
 * 某些厂商 ROM 在异常上下文里调它会抛，宁可少一道确认也不要让小组件收不到点击。
 */
internal fun android.content.BroadcastReceiver.widgetSentFromPackage(): String? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        runCatching { sentFromPackage }.getOrNull()
    } else null

internal object WidgetGuard {
    private const val PREFS = "jun_widget_state"
    private const val KEY_TOKEN = "action_token"

    @Volatile private var cached: String? = null

    /** 首次调用时生成并落盘，之后每次直接吃内存里那份 */
    fun token(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_TOKEN, null)
            val value = if (!saved.isNullOrBlank()) saved else run {
                val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                val fresh = bytes.joinToString("") {
                    ((it.toInt() and 0xff) + 0x100).toString(16).substring(1)
                }
                prefs.edit().putString(KEY_TOKEN, fresh).apply()
                fresh
            }
            cached = value
            return value
        }
    }

    /**
     * 控制类动作能不能执行。两道判据：
     * 1) 令牌：随 PendingIntent 一起带过来的那份、每次安装随机生成的值。第三方 App 读不到
     *    本 App 的私有 SharedPreferences，也就伪造不出正确的 `EXTRA_WIDGET_TOKEN`。
     * 2) 来源包名：由调用方在 Android 13+ 上取 `BroadcastReceiver.getSentFromPackage()` 传进来
     *    （对我们自己创建的 PendingIntent 它返回本包名，第三方直接 sendBroadcast 时返回它自己的
     *    包名）。minSdk 24 上没有这个 API，传 null 表示「未知」，此时只靠令牌——令牌本身已经
     *    是不可猜测的，这一道只是把「连我们的私有目录都读到了」这种极端情况也挡住。
     */
    fun allowed(context: Context, intent: Intent, sentFrom: String?): Boolean {
        val given = intent.getStringExtra(EXTRA_WIDGET_TOKEN) ?: return false
        if (given != token(context)) return false
        return sentFrom == null || sentFrom == context.packageName
    }
}

internal data class WidgetSnapshot(
    val title: String,
    val artist: String,
    val nextTitle: String,
    val isPlaying: Boolean,
    val hasMedia: Boolean
)

internal object WidgetState {
    private const val PREFS = "jun_widget_state"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_NEXT = "next_title"
    private const val KEY_PLAYING = "is_playing"
    private const val KEY_HAS_MEDIA = "has_media"
    private const val KEY_UPDATED = "updated_at"

    private const val COVER_DIR = "widget"
    private const val COVER_NAME = "cover.jpg"
    private const val COVER_MAX_PX = 300

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 播放服务每次状态变化时调用；只做轻量写入，不碰磁盘图片。 */
    fun update(context: Context, player: Player) {
        val meta = player.mediaMetadata
        val hasMedia = player.currentMediaItem != null
        val nextIdx = player.nextMediaItemIndex
        val nextTitle = if (nextIdx >= 0 && nextIdx < player.mediaItemCount) {
            player.getMediaItemAt(nextIdx).mediaMetadata.title?.toString()?.ifBlank { null }
        } else {
            null
        }
        prefs(context).edit()
            .putString(KEY_TITLE, meta.title?.toString()?.ifBlank { null } ?: "未播放")
            .putString(KEY_ARTIST, meta.artist?.toString()?.ifBlank { null } ?: "—")
            .putString(KEY_NEXT, nextTitle ?: "—")
            .putBoolean(KEY_PLAYING, player.isPlaying)
            .putBoolean(KEY_HAS_MEDIA, hasMedia)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    /** 服务销毁后没有播放器可读，明确标记为暂停，避免桌面一直显示「正在播放」。 */
    fun markStopped(context: Context) {
        prefs(context).edit().putBoolean(KEY_PLAYING, false).apply()
    }

    fun snapshot(context: Context): WidgetSnapshot {
        val p = prefs(context)
        return WidgetSnapshot(
            title = p.getString(KEY_TITLE, null) ?: "未播放",
            artist = p.getString(KEY_ARTIST, null) ?: "—",
            nextTitle = p.getString(KEY_NEXT, null) ?: "—",
            isPlaying = p.getBoolean(KEY_PLAYING, false),
            hasMedia = p.getBoolean(KEY_HAS_MEDIA, false)
        )
    }

    fun coverFile(context: Context): File =
        File(File(context.applicationContext.filesDir, COVER_DIR), COVER_NAME)

    /**
     * 保存小组件封面。必须在 IO 线程调用（会做缩放与压缩）。
     * 只保留最长边 [COVER_MAX_PX] 的 RGB_565 图，RemoteViews 的 Binder 事务
     * 有 1MB 上限，300px JPEG 解成 Bitmap 约 180KB，留足余量。
     */
    fun saveCover(context: Context, bitmap: Bitmap): Boolean {
        val target = coverFile(context)
        val dir = target.parentFile ?: return false
        if (!dir.exists() && !dir.mkdirs()) return false
        val scale = min(1f, COVER_MAX_PX.toFloat() / max(bitmap.width, bitmap.height).toFloat())
        val out = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).toInt()),
                max(1, (bitmap.height * scale).toInt()),
                true
            )
        } else {
            bitmap
        }
        val tmp = File(dir, "$COVER_NAME.tmp")
        return try {
            FileOutputStream(tmp).use { out.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (t: Throwable) {
            tmp.delete()
            false
        } finally {
            if (out !== bitmap) out.recycle()
        }
    }

    fun clearCover(context: Context) {
        runCatching { coverFile(context).delete() }
    }

    fun loadCover(context: Context): Bitmap? {
        val f = coverFile(context)
        if (!f.exists() || f.length() <= 0L) return null
        return runCatching {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = 1
            }
            BitmapFactory.decodeFile(f.absolutePath, opts)
        }.getOrNull()
    }

    // ==================== 重绘节流（R13 老机适配） ====================
    //
    // 桌面小组件的整条渲染链里唯一的重活是 loadCover：一次 BitmapFactory.decodeFile
    // 发生在 **BroadcastReceiver 的主线程**上（onUpdate 必须在 onReceive 返回前
    // 把 RemoteViews 交给系统）。MusicService 在 onTimelineChanged /
    // onPlaybackStateChanged / onMediaMetadataChanged / onIsPlayingChanged 上都会
    // 调 updateWidgets，一次播放里的进度 tick 又能连出几十次。
    // 老机器上这条链的后果是"广播超时 → ANR → 系统把整个进程连同前台服务一起收掉"。
    //
    // 规则很简单：内容没变，或者距上次真重绘不足一个最小间隔，就跳过这次重绘。
    // 快照本身照写（很便宜），所以不会丢状态；跳过的只是"解码 + 提交 RemoteViews"。
    // 新机同样受这条节流保护，只是间隔更短，观感上没有任何区别。

    /**
     * 上次真重绘的内容签名与时刻。
     *
     * 必须按小组件种类分开记：4×2 与 4×4 是两个独立的宿主，共用一条记录的话，
     * 前一个刚画过后一个就会被误判成"内容没变"而永远不更新（桌面 4×4 卡在上一首）。
     */
    private class RenderRecord { var sig: String = ""; var at: Long = 0L }
    private val renderRecords =
        java.util.concurrent.ConcurrentHashMap<String, RenderRecord>()

    /** 内容签名：标题|歌手|播放态|有无曲目|封面文件版本（mtime） */
    fun renderSignature(snap: WidgetSnapshot, context: Context): String {
        val ver = runCatching { coverFile(context).lastModified() }.getOrDefault(0L)
        return "${snap.title}|${snap.artist}|${snap.isPlaying}|${snap.hasMedia}|$ver"
    }

    /**
     * 这次要不要真的重绘。
     *
     * 返回 false 表示"内容和 400ms 前一样"，调用方直接跳过 updateAppWidget。
     * 老机安全档把窗口拉长到 900ms：桌面小组件本来就没有比这更快的可见变化，
     * 而省下的是主线程上的图片解码。
     */
    fun shouldRender(tag: String, sig: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val window = if (DevicePerformance.safeMode) 900L else 400L
        val rec = renderRecords.getOrPut(tag) { RenderRecord() }
        if (sig == rec.sig && now - rec.at < window) return false
        rec.sig = sig
        rec.at = now
        return true
    }
}
