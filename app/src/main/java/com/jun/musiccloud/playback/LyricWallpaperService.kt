package com.jun.musiccloud.playback

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.jun.musiccloud.data.LyricLine
import com.jun.musiccloud.data.LyricParser
import com.jun.musiccloud.ui.theme.DevicePerformance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 歌词动态壁纸。
 *
 * 不申请任何特殊权限：桌面壁纸服务由系统绘制，播放状态通过进程内 NowPlayingBus 读取。
 * 15fps 的低频重绘 + 只画文字与两层渐变，耗电接近静态壁纸；不可见时完全停掉。
 */
class LyricWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = LyricEngine()

    companion object {
        /**
         * 进程级"别再画了"闸门。
         *
         * 壁纸引擎的 surface 是被系统（桌面/锁屏）持有的，我们这边一旦崩溃或被强杀，
         * Engine 不一定收到 onDestroy，SurfaceFlinger 侧就留着一个还在被提交的图层。
         * 崩溃兜底里把这个标记置真，所有引擎立刻停帧、释放 surface，
         * 不给系统留"贴着锁屏绘制的第三方层"。
         */
        @Volatile
        var shutDownRequested: Boolean = false

        /** 由 JunApp 的未捕获异常处理器调用 */
        fun requestShutdown() { shutDownRequested = true }
    }

    private inner class LyricEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var visible = false

        /**
         * 连续绘制失败次数。
         *
         * 锁屏/切桌面/息屏的瞬间 lockCanvas 会失败，这是正常的；
         * 但如果连着失败 20 次还继续 15fps 地试，就是在给一个已经死掉的
         * surface 每 66ms 敲一次系统调用——日志刷屏 + 主线程常驻唤醒，
         * 老 ROM 上这正是被省电守护盯上然后连进程一起收掉的行为。
         * 到阈值就停，等 onVisibilityChanged / onSurfaceChanged 再恢复。
         */
        private var drawFailures = 0

        private var lines: List<LyricLine> = emptyList()
        private var lyricKey: String = ""
        private var lyricDuration: Long = 0L
        private var loadingKey: String = ""

        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8B909C.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        // 背景渐变对象：原来每帧 new 三个 Paint + 三个 Gradient，15fps 下等于每秒
        // 45 个带 native 分配的对象，老机器的 GC 会被这条壁纸链拖着走。
        // Paint 复用，Shader 只在尺寸/种子变了时重建。
        private val bgPaint = Paint()
        private val glowPaint = Paint()
        private var bgKey = ""
        private var bgShader: Shader? = null
        private var glowShaderA: Shader? = null
        private var glowShaderB: Shader? = null
        /** 逐句扫光的强调色：随配色种子一起算，原来每句一次 HSVToColor，现在每首歌一次 */
        private var accentColor: Int = Color.WHITE

        /**
         * 重绘节奏：常规 15fps，老机安全档降到约 6fps。
         *
         * 逐句扫光是"一句几秒"的慢变化，6fps 肉眼看不出差别，
         * 但系统调用与分配量直接砍到 40%。
         */
        private fun frameDelay(): Long = if (DevicePerformance.safeMode) 160L else 66L

        private val draw = object : Runnable {
            override fun run() {
                if (!visible || shutDownRequested) return
                drawFrame()
                handler.postDelayed(this, frameDelay())
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(draw)
            if (visible && !shutDownRequested && DevicePerformance.allowLyricWallpaper) {
                drawFailures = 0
                drawFrame()
                handler.postDelayed(draw, frameDelay())
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            // 尺寸变了，缓存的渐变全部作废
            bgKey = ""
            bgShader = null
            glowShaderA = null
            glowShaderB = null
            drawFailures = 0
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(draw)
            // 主动释放渐变，surface 都没了还留着三个大 Shader 对象没有意义
            bgShader = null
            glowShaderA = null
            glowShaderB = null
            bgKey = ""
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacks(draw)
            scope.cancel()
            super.onDestroy()
        }

        private fun drawFrame() {
            if (shutDownRequested || !DevicePerformance.allowLyricWallpaper) {
                handler.removeCallbacks(draw)
                return
            }
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) { drawScene(canvas); drawFailures = 0 }
            } catch (_: Throwable) {
                // 桌面正在切换/锁屏时 lockCanvas 可能失败，下一帧再试
                drawFailures++
                if (drawFailures >= 20) {
                    // 连续失败：这个 surface 已经不能用了，停手等系统重新给可见性
                    handler.removeCallbacks(draw)
                    visible = false
                    drawFailures = 0
                }
            } finally {
                if (canvas != null) runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }

        private fun drawScene(canvas: Canvas) {
            val w = canvas.width.coerceAtLeast(1)
            val h = canvas.height.coerceAtLeast(1)
            val snap = NowPlayingBus.state.value

            // 背景：以歌名为种子的深色渐变 + 两团缓慢呼吸的光
            val seed = (snap.title + snap.artist).hashCode()
            // 渐变只在"尺寸或配色种子变了"时重建：h 与 seed 就是它的全部输入。
            // 注意 HSVToColor 也要放在重建分支里——它每次都新建一个 int[] 语义的颜色，
            // 原来每帧算 4 次，是纯粹的无谓分配。
            val key = "${w}x${h}:$seed"
            if (key != bgKey) {
                bgKey = key
                val hue = (abs(seed) % 360).toFloat()
                val top = Color.HSVToColor(floatArrayOf(hue, 0.34f, 0.16f))
                val bottom = Color.HSVToColor(floatArrayOf((hue + 42f) % 360f, 0.42f, 0.055f))
                bgShader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
                val glow = Color.HSVToColor(floatArrayOf(hue, 0.65f, 1f))
                glowShaderA = RadialGradient(
                    w * 0.26f, h * 0.28f, w * 0.60f,
                    intArrayOf((glow and 0x00FFFFFF) or 0x33000000, 0x00000000),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
                )
                glowShaderB = RadialGradient(
                    w * 0.78f, h * 0.72f, w * 0.55f,
                    intArrayOf(0x22FFFFFF, 0x00000000),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
                )
                accentColor = Color.HSVToColor(floatArrayOf(hue, 0.55f, 1f))
            }
            bgPaint.shader = bgShader
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
            val t = SystemClock.elapsedRealtime() / 1000f
            // 呼吸位移仍然每帧算，但用的 Paint 与渐变是复用的
            glowPaint.shader = glowShaderA
            canvas.drawCircle(
                w * (0.26f + 0.05f * kotlin.math.sin(t * 0.20f)),
                h * 0.28f,
                w * 0.60f,
                glowPaint
            )
            glowPaint.shader = glowShaderB
            canvas.drawCircle(
                w * (0.78f + 0.04f * kotlin.math.cos(t * 0.16f)),
                h * 0.72f,
                w * 0.55f,
                glowPaint
            )

            // 没在播放时给一句温柔的引导
            if (snap.currentIndex < 0 || snap.durationMs <= 0L) {
                linePaint.color = Color.WHITE
                linePaint.textSize = h * 0.045f
                canvas.drawText("Jun Music", w / 2f, h * 0.48f, linePaint)
                metaPaint.textSize = h * 0.018f
                canvas.drawText("在手机上播放一首歌，歌词会落在这里", w / 2f, h * 0.53f, metaPaint)
                return
            }

            updateLyrics(snap.lyricKey, snap.durationMs)
            val pos = if (snap.playing) {
                snap.positionMs + (System.currentTimeMillis() - snap.updatedAt)
            } else snap.positionMs
            val index = LyricParser.currentIndex(lines, pos)
            val current = lines.getOrNull(index)
            val prev = lines.getOrNull(index - 1)?.text
            val next = lines.getOrNull(index + 1)?.text
            val fontSize = (h * 0.040f).coerceIn(dp(24f), dp(64f))

            linePaint.textSize = fontSize
            linePaint.color = 0xFF7B8494.toInt()
            prev?.let { drawWrapped(canvas, it, w, h * 0.36f, w * 0.78f, linePaint, alpha = 0.55f) }

            if (current != null) {
                val baseY = h * 0.50f
                val maxW = w * 0.82f
                linePaint.color = Color.WHITE
                if (snap.playing && current.timeMs >= 0) {
                    // 逐句扫光：按本句到下一句之间的进度裁切
                    val nextStart = lines.getOrNull(index + 1)?.timeMs ?: (current.timeMs + 4000L)
                    val span = (nextStart - current.timeMs).coerceAtLeast(1L)
                    val p = ((pos - current.timeMs).toFloat() / span).coerceIn(0f, 1f)
                    drawWrapped(
                        canvas, current.text, w, baseY, maxW, linePaint,
                        clipFraction = p, accent = accentColor
                    )
                } else {
                    drawWrapped(canvas, current.text, w, baseY, maxW, linePaint)
                }
            }

            linePaint.color = 0xFF7B8494.toInt()
            linePaint.textSize = fontSize * 0.72f
            next?.let { drawWrapped(canvas, it, w, h * 0.65f, w * 0.76f, linePaint, alpha = 0.5f) }

            titlePaint.textSize = h * 0.020f
            titlePaint.color = 0xE6FFFFFF.toInt()
            canvas.drawText(snap.title, w / 2f, h * 0.855f, titlePaint)
            metaPaint.textSize = h * 0.015f
            canvas.drawText(snap.artist.ifBlank { "Jun Music" }, w / 2f, h * 0.885f, metaPaint)
        }

        private fun updateLyrics(key: String, duration: Long) {
            if (key == lyricKey && duration == lyricDuration) return
            if (key == loadingKey) return
            lyricKey = key
            lyricDuration = duration
            loadingKey = key
            lines = emptyList()
            if (key.isBlank()) {
                loadingKey = ""
                return
            }
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching { LyricParser.loadLyrics(key, duration) }.getOrDefault(emptyList())
                }
                if (loadingKey == key) {
                    lines = loaded
                    loadingKey = ""
                }
            }
        }

        /** 按宽度逐字换行；[clipFraction] 用于逐句扫光，只把已唱部分染成强调色 */
        private fun drawWrapped(
            canvas: Canvas,
            text: String,
            width: Int,
            centerY: Float,
            maxWidth: Float,
            paint: Paint,
            alpha: Float = 1f,
            clipFraction: Float = 1f,
            accent: Int = Color.WHITE
        ) {
            if (text.isBlank()) return
            val rows = wrap(text, paint, maxWidth)
            val lineHeight = paint.textSize * 1.34f
            val startY = centerY - (rows.size - 1) * lineHeight / 2f
            val oldAlpha = paint.alpha
            paint.alpha = (oldAlpha * alpha).toInt().coerceIn(0, 255)
            rows.forEachIndexed { i, row ->
                val y = startY + i * lineHeight
                canvas.drawText(row, width / 2f, y, paint)
                if (clipFraction < 1f) {
                    val rowWidth = paint.measureText(row)
                    val left = width / 2f - rowWidth / 2f
                    val right = left + rowWidth * clipFraction
                    canvas.save()
                    canvas.clipRect(left, y - paint.textSize, right, y + paint.textSize * 0.35f)
                    val old = paint.color
                    paint.color = accent
                    canvas.drawText(row, width / 2f, y, paint)
                    paint.color = old
                    canvas.restore()
                }
            }
            paint.alpha = oldAlpha
        }

        private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            for (ch in text) {
                sb.append(ch)
                if (paint.measureText(sb.toString()) > maxWidth) {
                    if (sb.length > 1) {
                        sb.setLength(sb.length - 1)
                        out.add(sb.toString())
                        sb.setLength(0)
                        sb.append(ch)
                    } else {
                        out.add(sb.toString())
                        sb.setLength(0)
                    }
                }
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
            return if (out.size > 4) out.take(4) else out
        }

        private fun dp(v: Float): Float = v * resources.displayMetrics.scaledDensity
    }
}
