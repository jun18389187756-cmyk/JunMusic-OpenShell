package com.jun.musiccloud.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jun.musiccloud.ui.theme.BG_BLUR_DEFAULT
import com.jun.musiccloud.ui.theme.DevicePerformance
import com.jun.musiccloud.ui.theme.JunPalette
import com.jun.musiccloud.ui.theme.isLiquidGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 背景：默认把当前专辑封面压成模糊图铺满；若设置里选了自定义背景图则优先用它。
 * 切换（切歌 / 换背景）用 Crossfade 交叉淡化，过渡平缓。
 * 无任何图源时回退霓虹渐变光晕。
 *
 * [blurRadius] 由设置项驱动（单位 dp）。给 0 就是原图直出。
 * [customUri] 为用户自选背景图（SAF content URI）；非空时忽略专辑封面。
 */
@Composable
fun AuroraBackground(
    artworkPath: String?,
    isDark: Boolean = true,
    blurRadius: Float = BG_BLUR_DEFAULT,
    customUri: String? = null,
    /** 「筱莓专属主题」：底色与霓虹光晕整体染粉 */
    isXiaomei: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 不能用 remember(artworkPath)：那会在切歌瞬间把状态重置成 null，
    // 新封面解码完成前的几百毫秒背景退到霓虹光晕、解码完再淡入，看着就是"闪一下"。
    // 改成普通 remember + LaunchedEffect 里就绪才替换：旧图保持显示，
    // 新图加载完成后由 Crossfade 交叉淡化过去。加载失败（null）也等确认了再切换。
    var blurBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // 已加载完成对应的"源标识"：自定义背景用 uri，否则用封面路径。
    var loadedSource by remember { mutableStateOf<String?>(null) }
    val glass = isLiquidGlass
    val context = LocalContext.current
    // 老机安全档：背景不要用 Modifier.blur 逐帧糊。
    //
    // 关键认知（很多老机反馈都栽在这里）：Modifier.blur 在 API 31 以下**不是空实现**，
    // Compose 会退到"把这一层强制 drawAsImage 成位图 + 软件 StackBlur"的路径。
    // 于是全屏 1080×2400 的一张大图，每帧一次 CPU 模糊 + 一次离屏位图分配，
    // 老机器的 Mali-G76/老 HWUI 上就是稳定的掉帧源与 GC 压力源；
    // 更糟的是 drawAsImage 会把该层的硬件绘制层拆掉重建，滚动时反复发生。
    //
    // 替代做法：把封面一次性缩到 ~72px，再交给 Image 放大铺满。
    // 双线性放大的结果和重度高斯糊几乎同形，成本却是"每首歌一次"而不是"每帧一次"，
    // 而且省下的是解码内存（72px 位图 20KB vs 全屏位图 10MB 量级）。
    val cheapBlur = !DevicePerformance.allowBackgroundBlur

    // 本次要显示的图源标识：自定义背景优先。
    val sourceKey = if (!customUri.isNullOrBlank()) "uri:$customUri"
                    else artworkPath?.let { "art:$it" }

    LaunchedEffect(sourceKey, customUri, artworkPath) {
        if (sourceKey == loadedSource) return@LaunchedEffect
        val newBitmap = if (sourceKey == null) null else withContext(Dispatchers.IO) {
            val raw = if (!customUri.isNullOrBlank()) loadBackgroundBitmap(context, customUri)
                      else ArtworkLoader.load(artworkPath)
            // 只在老机/关掉实时模糊时做一次性缩放；新机原样返回，画面一字不变
            if (raw != null && cheapBlur) cheapBlurCopy(raw) else raw
        }
        // 加载协程可能被快速连切取消：只有 sourceKey 仍是最新值时才提交
        if (sourceKey == null) {
            blurBitmap = null
            loadedSource = null
        } else if (newBitmap != null) {
            blurBitmap = newBitmap
            loadedSource = sourceKey
        } else if (!customUri.isNullOrBlank()) {
            // 自定义背景读取失败（如重启后 URI 权限失效）：清掉 loadedSource，
            // 让下一帧能回退到封面/光晕，而不是永远停在旧图。
            loadedSource = null
        }
    }

    // API 31 以下 Modifier.blur 不生效，硬件模糊上限也低，按老逻辑压到 24dp 以内。
    // 拖动滑块时半径走一段短过渡，避免一格一格地跳。
    // 老机安全档（cheapBlur）时半径直接给 0：糊已经由"一次性缩小"替代，
    // 再挂 blur 就是把最贵的那条逐帧路径原样留着。
    val targetBlur = when {
        cheapBlur -> 0f
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> blurRadius
        else -> blurRadius.coerceAtMost(24f)
    }
    val blurDp by animateFloatAsState(targetBlur, tween(220), label = "bgBlur")

    // 底层：纯色（避免透明闪烁）
    Box(
        modifier
            .fillMaxSize()
            .background(
                when {
                    isXiaomei && isDark -> JunPalette.XiaomeiDarkBg
                    isXiaomei -> JunPalette.XiaomeiLightBg
                    isDark -> Color(0xFF050505)
                    else -> Color(0xFFF0F0F2)
                }
            )
    ) {
        // 交叉淡化：新封面淡入覆盖旧封面。
        // 切歌背景要"平缓"：1050ms + EaseInOutSine 正弦缓入缓出，
        // 比原先 700ms 线性慢且中段不突兀，两张封面交叠更柔。
        //
        // 6.4.1 低端机降级：交叉淡化期间两张全屏位图同时绘制，内存带宽翻倍，
        // 低端机的 GPU 在切歌那 1 秒里最先掉帧。这类机器改成 180ms 快切，
        // 用户几乎察觉不到"没有渐变"，但省掉了一整秒的双图合成。
        val bgFadeMs = if (DevicePerformance.legacy) 180 else 1050
        Crossfade(
            targetState = blurBitmap,
            animationSpec = tween(bgFadeMs, easing = androidx.compose.animation.core.EaseInOutSine),
            label = "bg"
        ) { bmp ->
            if (bmp != null) {
                Box(Modifier.fillMaxSize()) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            // blur(0.dp) 会抛异常，0 时直接不挂这个 Modifier
                            .then(if (blurDp >= 0.5f) Modifier.blur(blurDp.dp) else Modifier)
                    )
                    // 暗化/亮化叠层
                    // 液态玻璃下大幅减轻压制：卡片本体自己带了烟熏/磨砂层，
                    // 全局再压一遍等于压两次，封面颜色被冲干净后玻璃里就没东西可折射，
                    // 看起来会像一块死板的灰塑料。留住背景的颜色才有通透感。
                    val scrim = if (glass) {
                        // 深色模式文字是白的，背景可以留得很暗、颜色很足。
                        // 浅色模式文字是黑的，背景必须先提亮到"深色封面也压不住黑字"的程度，
                        // 否则遇到一张黑色专辑封面整页就废了，所以这里比深色高不少。
                        if (isDark) Color.Black.copy(alpha = 0.32f)
                        else Color.White.copy(alpha = 0.42f)
                    } else {
                        if (isDark) Color.Black.copy(alpha = 0.62f)
                        else Color.White.copy(alpha = 0.55f)
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(scrim)
                    )
                }
            } else {
                // 无封面：霓虹光晕（粉主题下换成粉调光晕）
                NeonFallback(isDark = isDark, isXiaomei = isXiaomei)
            }
        }
    }
}

@Composable
private fun NeonFallback(isDark: Boolean, isXiaomei: Boolean) {
    val alphaA = if (isDark) 0.40f else 0.22f
    val alphaB = if (isDark) 0.34f else 0.18f
    val glowA by animateFloatAsState(alphaA, tween(700), label = "a")
    val glowB by animateFloatAsState(alphaB, tween(700), label = "b")
    // 粉主题：光晕整体换成玫粉 + 浅樱粉
    val colorA = if (isXiaomei) JunPalette.XiaomeiPrimary else JunPalette.NeonCyan
    val colorB = if (isXiaomei) JunPalette.XiaomeiSecondary else JunPalette.NeonMagenta

    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colorA.copy(alpha = glowA), Color.Transparent),
                center = Offset(size.width * 0.18f, size.height * 0.20f),
                radius = size.width * 0.85f
            ),
            radius = size.width * 0.85f,
            center = Offset(size.width * 0.18f, size.height * 0.20f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colorB.copy(alpha = glowB), Color.Transparent),
                center = Offset(size.width * 0.85f, size.height * 0.62f),
                radius = size.width * 0.80f
            ),
            radius = size.width * 0.80f,
            center = Offset(size.width * 0.85f, size.height * 0.62f)
        )
    }
}

/**
 * 一次性"缩图当模糊"：把大图裁成 ~72px 的小位图，交给 Image 双线性放大铺满。
 *
 * 只在老机安全档走这里，且整首歌只做一次（在 IO 线程）。
 * 绝不 recycle 传进来的 src——那是 ArtworkLoader 的缓存对象，回收它等于把
 * 别的界面正在用的封面一起弄坏；这里返回的是一张新的小图，老的交给 GC。
 */
private fun cheapBlurCopy(src: Bitmap): Bitmap {
    val longEdge = max(src.width, src.height).coerceAtLeast(1)
    if (longEdge <= CHEAP_BLUR_PX) return src
    val w = (src.width * CHEAP_BLUR_PX.toFloat() / longEdge).toInt().coerceAtLeast(1)
    val h = (src.height * CHEAP_BLUR_PX.toFloat() / longEdge).toInt().coerceAtLeast(1)
    return runCatching { Bitmap.createScaledBitmap(src, w, h, true) }.getOrDefault(src)
}

/** 缩小目标长边：20~30dp 级别的糊感，实测与 24dp 高斯几乎同形 */
private const val CHEAP_BLUR_PX = 72

/**
 * 从 SAF content URI 解码背景图，采样到约 BG_TARGET_PX 长边。
 * 背景铺满全屏，比封面（256px）大得多，但仍远小于原图，避免整张高清图解码占内存。
 * 失败返回 null（权限失效/文件被删/解码异常），交由调用方回退。
 */
private fun loadBackgroundBitmap(context: Context, uriStr: String): Bitmap? {
    val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
    val fromUri = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= BG_TARGET_PX) sample *= 2
            context.contentResolver.openInputStream(uri)?.use { input2 ->
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeStream(input2, null, opts)
            }
        }
    }.getOrNull()
    if (fromUri != null) return fromUri
    // 读不出来不代表用户"没设背景"：老版本只存 SAF URI，授权一掉就永久失效，
    // 界面上却还写着"已使用自选图片"，看起来就像"设置每次都要重设"。
    // 6.2 起选图时会同时在私有目录留一份副本，这里拿它兜底（新装的也走同一条路径）。
    val copy = java.io.File(java.io.File(context.filesDir, "theme"), "background.jpg")
    if (!copy.isFile) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        copy.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= BG_TARGET_PX) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        copy.inputStream().use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()?.also {
        // 把兜底路径写回 prefs，让 URI 与实际渲染图源重新一致（SettingsCloud 会按这条推云端）
        runCatching {
            context.getSharedPreferences("jun_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("custom_bg_uri", android.net.Uri.fromFile(copy).toString()).apply()
        }
    }
}

private const val BG_TARGET_PX = 1080