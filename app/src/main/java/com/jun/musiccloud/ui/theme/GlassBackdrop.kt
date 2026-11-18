package com.jun.musiccloud.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader as AndroidShader
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * 液态玻璃的背板系统与光学内核。
 *
 * 关键认知：液态玻璃的"通透"来自**位移取样**——边缘的像素去采样更靠内的背景，
 * 背景于是在玻璃边缘被压缩、拉伸，看起来真像透过一块有厚度的玻璃。
 * 这件事画渐变永远做不到，只能得到"雾"。所以必须拿到背板像素：
 *
 * 1. [junBackdropSource] 把一段内容录进 [GraphicsLayer]；
 * 2. 玻璃元件把这些层重画进自己的图层，挂上 RenderEffect 链：
 *    色彩（对比度/饱和度）→ 模糊 → AGSL 折射；
 * 3. 再用第二个 AGSL（SDF 法向 · 光源点积）画镜面内缘。
 *
 * SDF 折射与高光算法参考 Kyant0/AndroidLiquidGlass（Apache-2.0）。
 * AGSL 需要 API 33，31~32 退化为"真模糊 + 调色 + 渐变内缘"，30 及以下退化为绘制近似。
 */

// ==================== 背板 ====================

/**
 * 一块可被玻璃取样的背板。
 *
 * [coordinates] 用 neverEqualPolicy：位置每次上报都算变化，玻璃元件在绘制里读它就能跟随滚动。
 *
 * 这里刻意**没有**"内容变了"的通知状态。在绘制阶段写 state 会引发每帧互相失效的死循环：
 * 任何图层失效都会 `ownerView.invalidate()` → 根显示列表重录 → 背板的 drawWithContent 重跑
 * → 又写一次 state → 玻璃失效 → 循环。
 *
 * 内容更新靠 RenderNode 引用本身：玻璃把背板层画进自己的图层时，记录的是一条指向同一个
 * RenderNode 的引用，背板重录后 HWUI 沿引用链传递脏区，玻璃那层同帧重新渲染。
 *
 * 铁律：**玻璃元件不能取样包住它自己的背板**，否则递归引用。
 * 取样必须单向：背板只能是玻璃元件的兄弟，或祖先的兄弟。
 */
@Stable
class JunBackdrop internal constructor(internal val layer: GraphicsLayer?) {

    internal var coordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())
}

@Composable
fun rememberJunBackdrop(): JunBackdrop {
    val layer = if (isRenderEffectSupported) rememberGraphicsLayer() else null
    return remember(layer) { JunBackdrop(layer) }
}

/**
 * 把这段内容照常画到屏幕上，同时录进背板层。
 *
 * 玻璃元件本身不能放进被录制的内容里，否则会自己取样自己（见 [JunBackdrop] 的铁律）。
 */
fun Modifier.junBackdropSource(backdrop: JunBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.coordinates = it }
    .drawWithContent {
        val layer = backdrop.layer
        if (layer == null) {
            drawContent()
            return@drawWithContent
        }
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }

/**
 * 当前可用的背板栈，按绘制顺序从后到前。
 *
 * 用栈而不是单块：上栏浮在歌单之上，要同时折射「模糊封面 + 歌单」两层。
 */
val LocalJunBackdrops = staticCompositionLocalOf<List<JunBackdrop>> { emptyList() }

/** 把背板栈对齐到当前元件的坐标系后画出来 */
internal fun DrawScope.drawBackdropStack(
    backdrops: List<JunBackdrop>,
    target: LayoutCoordinates?
) {
    if (target == null || !target.isAttached) return
    backdrops.forEach { backdrop ->
        val layer = backdrop.layer ?: return@forEach
        val source = backdrop.coordinates ?: return@forEach
        if (!source.isAttached) return@forEach
        // 元件原点在背板层坐标系中的位置：反向平移即可让层内容与屏幕对齐
        val offset = try {
            source.localPositionOf(target, Offset.Zero)
        } catch (_: Throwable) {
            target.positionInWindow() - source.positionInWindow()
        }
        translate(-offset.x, -offset.y) {
            drawLayer(layer)
        }
    }
}

// ==================== 能力探测 ====================

/**
 * 低端设备探测（启动时一次）。
 *
 * 液态玻璃每帧要为每个玻璃元件新建 GraphicsLayer、把整块背板 record 进来，
 * 再跑「调色 → 模糊 → AGSL 位移取样」链。旗舰机上几毫秒，低端机上会直接掉帧。
 * 判定为真时，下面的能力开关整体关闭真折射：玻璃退化为「半透明本体 + 内缘高光」
 * 的实色近似——观感仍是玻璃，只是省掉最贵的逐元件背板取样流水线。
 * 这是自适应降级，用户在设置里选了「液态玻璃」也无需手动改回。
 */
/**
 * 这些 SoC 的 GPU 跑不动「逐元件整背板录制 + RenderEffect 模糊 + AGSL 折射」。
 * 8 Gen 1（SM8450 / taro）是重点：它常配 12GB 内存，旧的分档会把它当旗舰。
 * 8+ Gen 1、888、870 一并按轻量玻璃处理；天玑 9000/9200 与 Exynos 2200 同样保守。
 */
private val LIGHT_GLASS_SOC_PATTERNS = listOf(
    "sm8450", "taro",          // 骁龙 8 Gen 1
    "sm8475", "cape",          // 骁龙 8+ Gen 1
    "sm8350", "lahaina",       // 骁龙 888
    "sm8250", "kona",          // 骁龙 870 / 865
    "sm7450", "parrot",        // 骁龙 7 Gen 1
    "mt6983", "mt6985",        // 天玑 9000 / 9200
    "exynos2200", "universal2200"
)

/** 把 SoC 指纹翻成用户能看懂的名字；认不出的原样返回。 */
private fun friendlySocName(fingerprint: String): String = when {
    fingerprint.contains("sm8750") -> "骁龙 8 Elite"
    fingerprint.contains("sm8650") -> "骁龙 8 Gen 3"
    fingerprint.contains("sm8550") -> "骁龙 8 Gen 2"
    fingerprint.contains("sm8475") || fingerprint.contains("cape") -> "骁龙 8+ Gen 1"
    fingerprint.contains("sm8450") || fingerprint.contains("taro") -> "骁龙 8 Gen 1"
    fingerprint.contains("sm8350") || fingerprint.contains("lahaina") -> "骁龙 888"
    fingerprint.contains("sm8250") || fingerprint.contains("kona") -> "骁龙 870 / 865"
    fingerprint.contains("mt6989") -> "天玑 9300"
    fingerprint.contains("mt6985") -> "天玑 9200"
    fingerprint.contains("mt6983") -> "天玑 9000"
    fingerprint.contains("exynos2200") || fingerprint.contains("universal2200") -> "Exynos 2200"
    else -> fingerprint.trim().split(' ').firstOrNull().orEmpty()
}

/** SoC 指纹：优先 SOC_MODEL（API 31+），再拼 hardware / board / device 兜底。 */
private fun socFingerprint(): String {
    val model = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
    return listOf(model, Build.HARDWARE, Build.BOARD, Build.DEVICE)
        .joinToString(" ")
        .lowercase()
}

/**
 * 是否华为/荣耀的 EMUI（含 HarmonyOS）。
 *
 * 为什么要单独判 ROM：同一颗 Kirin，AOSP 与 EMUI 的行为差别极大——
 * EMUI 会（1）在锁屏/熄屏时主动回收 TYPE_APPLICATION_OVERLAY 悬浮窗；
 * （2）把持续振动 + 亮屏请求当成"唤醒事件"弹到 Keyguard；
 * （3）对未处理 configChanges 的 Activity 用更激进的重建策略。
 * 这三条正是 nova 6 上"闪一下跳到输入密码屏"的候选路径，判定必须把它们隔开。
 *
 * 只用 Build 里的静态字段，不反射任何华为私有 API（反射会随 ROM 版本失效，
 * 而且华为对部分私有类做了调用方校验）。
 */
private fun isHuaweiEmui(): Boolean {
    val hay = listOf(
        Build.MANUFACTURER, Build.BRAND, Build.PRODUCT, Build.DEVICE,
        Build.DISPLAY, Build.ID
    ).filterNotNull().joinToString(" ").lowercase()
    return hay.contains("huawei") || hay.contains("honor") || hay.contains("emui") ||
        hay.contains("harmony") || hay.contains("kirin") || hay.contains("h silicon")
}

/**
 * 首次启动展示给用户的设备画像。
 *
 * 只读、不含任何可识别个人的信息：机型、SoC、内存、核数，以及据此给出的
 * 材质建议。真折射是否开放由 [fullRefraction] 决定，用户可以在首启里覆盖材质选择。
 */
data class DeviceProfile(
    val model: String,
    val soc: String,
    val ramMb: Int,
    val cores: Int,
    /** 低端 / 中端 / 旗舰 */
    val tier: String,
    /** 推荐材质：低端实色，中端轻量液态，旗舰完整液态 */
    val recommendation: JunSurfaceStyle,
    /** 是否开放完整 AGSL 折射（仅 8 Gen 2 及以后 + 足够内存） */
    val fullRefraction: Boolean,
    /** 低端机：系统层面强制实色，首启里不应让用户选到实际不生效的材质 */
    val solidOnly: Boolean = false
)

object DevicePerformance {
    @Volatile var isLowEnd: Boolean = false
        private set

    /**
     * 中端档：跑得动系统但吃不下「每帧整背板录制 + RenderEffect + AGSL 折射」的
     * 完整液态玻璃管线。中端机同样降级：关真折射、主题强制实色、默认关装饰动画，
     * 但保留设备最高刷新率（120Hz 丝滑靠的是降渲染负载，不是砍帧率）。
     */
    @Volatile var isMidEnd: Boolean = false
        private set

    /** 首启“设备与材质”卡片读取；init 之后才有意义。 */
    @Volatile var profile: DeviceProfile = DeviceProfile(
        model = Build.MODEL.orEmpty(),
        soc = "",
        ramMb = 0,
        cores = Runtime.getRuntime().availableProcessors(),
        tier = "中端",
        recommendation = JunSurfaceStyle.LIQUID,
        fullRefraction = false
    )
        private set

    /**
     * 运行时降级标记：冷启动前若干帧实测掉帧率过高时置真。
     *
     * 内存/核数只能粗略分档，同 RAM 的机型 GPU 差距很大。这里用真实帧数据兜底：
     * 冷启动窗口内掉帧率超阈值→当场切实色并关装饰动画，避免"看着配置不低、实际还是很卡"。
     *
     * 用 Compose [androidx.compose.runtime.MutableState] 承载：置真会让读取它的
     * JunTheme 重组，把 LocalSurfaceStyle / LocalGlassBlur 换成实色并下发到整棵树，
     * 无需重启即生效。
     */
    var autoDegraded: Boolean by mutableStateOf(false)
        private set

    /** 由冷启动掉帧监控调用：确认本机扛不住当前画质，永久降级到实色。 */
    fun markRuntimeDegrade() {
        // 切实色之前先把真折射关掉：能保住玻璃观感就别一步退到底
        GlassRuntime.disableRefraction()
        if (autoDegraded) return
        autoDegraded = true
        runCatching { com.jun.musiccloud.ui.JunMotion.reduced = true }
    }

    fun init(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalRamMb = info.totalMem / (1024L * 1024L)
        val cores = Runtime.getRuntime().availableProcessors()
        // 低端：厂商声明的低内存机，或总内存 ≤ 3.5GB，或「4 核及以下且内存 ≤ 4GB」的弱机。
        // 低端机自动切实色 + 刷新率封顶 60Hz。
        isLowEnd = am.isLowRamDevice || totalRamMb <= 3584 || (cores <= 4 && totalRamMb <= 4096)
        ramMb = totalRamMb
        // 老系统机（Android 11 及以下）单独标记：内存够但 ROM/GPU 老，策略按省着跑
        isOldOs = Build.VERSION.SDK_INT <= 30

        // ==== 5.2feat R13 老机安全档：新增维度 ====
        // 只看 RAM/核数会漏掉真正的风险面：**系统层是谁的**。nova 6 是 8GB + 8 核，
        // 上面两条都判不出它，但它是 EMUI 10——窗口层（悬浮窗/锁屏/壁纸）、
        // 后台查杀、媒体硬解并发、振动与亮屏的策略全都和 AOSP 不一样。
        // 用户反馈的"播放时闪到输入密码屏、只能强制重启"就是这一类系统层异常，
        // 不是 App 崩溃（崩溃只会退回桌面），所以必须单独一个判定维度。
        coresCount = cores
        isHuaweiRom = isHuaweiEmui()
        // Android 10（API 29）是 EMUI 出问题最集中的一代：后台起服务限制、
        // 悬浮窗回收、锁屏与壁纸引擎的 SurfaceFlinger 交互都改过。
        isApi29OrLower = Build.VERSION.SDK_INT <= 29
        isEmuiOldOs = isHuaweiRom && isApi29OrLower
        // 主线程心跳类特效（频谱/振动/粒子）在小内存 + 老系统上最容易演变成
        // wakelock 风暴：CPU 永远进不了深度空闲，EMUI 的功耗守护会直接杀进程。
        tightMemory = totalRamMb <= 6144
        safeOverride = runCatching {
            context.getSharedPreferences(SAFE_PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_SAFE_MODE, SAFE_AUTO)
        }.getOrDefault(SAFE_AUTO)
        // 单项覆盖一次性读进内存：这些判定在每帧/每次振动的热路径上，
        // 绝不能在原地读 SharedPreferences。
        // 用"整体换一个不可变 Map"而不是往里 put：读取端无锁、无竞态。
        runCatching {
            val sp = context.getSharedPreferences(SAFE_PREFS, Context.MODE_PRIVATE)
            val snap = HashMap<String, Int>(FX_KEYS.size)
            FX_KEYS.forEach { k -> snap[k] = sp.getInt(k, SAFE_AUTO) }
            safeOverrides = snap
        }
        // 安全档 = 老机（legacy）或 EMUI 老系统，或被用户/远程强制打开。
        // 注意这里刻意比 legacy 更宽：isEmuiOldOs 单独就能触发。
        safeMode = when (safeOverride) {
            SAFE_FORCE_ON -> true
            SAFE_FORCE_OFF -> false
            else -> isLowEnd || isOldOs || isEmuiOldOs || tightMemory
        }

        // 关键修正：内存和核数不能代表 GPU。
        // 12GB 的骁龙 8 Gen 1 以前会被判成「旗舰」，于是放开完整 AGSL 折射 + 色散，
        // 结果就是这块 Adreno 730 被逐元件整背板录制拖垮。这里按 SoC 指纹先排除
        // 8 Gen 1 / 888 / 天玑 9000 / Exynos 2200 这一代，再谈内存。
        val soc = socFingerprint()
        val lightSoc = LIGHT_GLASS_SOC_PATTERNS.any { soc.contains(it) }

        // 旗舰：已知 8 Gen 2 及以后，且 ≥10GB、≥8 核——唯一放开完整管线的档位。
        isFlagship = !isLowEnd && !lightSoc && totalRamMb >= 10240 && cores >= 8
        // 中端：其余全部（含 8 Gen 1 全系）。走「轻量玻璃」：
        // 只保留半透明染色 + 内缘高光，不再逐元件录整块背板、不再跑模糊/AGSL。
        isMidEnd = !isLowEnd && !isFlagship

        profile = DeviceProfile(
            model = Build.MODEL.orEmpty().ifBlank { "Android 设备" },
            soc = friendlySocName(soc),
            ramMb = totalRamMb.toInt(),
            cores = cores,
            tier = when {
                isLowEnd -> "低端"
                isFlagship -> "旗舰"
                else -> "中端"
            },
            recommendation = when {
                isLowEnd -> JunSurfaceStyle.SOLID
                else -> JunSurfaceStyle.LIQUID
            },
            fullRefraction = isFlagship,
            solidOnly = isLowEnd
        )
    }

    /** 旗舰档：唯一启用 AGSL 折射/色散完整管线的机型档位 */
    @Volatile var isFlagship: Boolean = false
        private set

    /**
     * 封面解码并发路数（按机型分档）。
     *
     * 解码是 CPU/IO 重活，路数太少则唱片墙一屏几十张磁贴排长队（"切换墙要加载老半天"），
     * 太多又会把 Dispatchers.IO 打满、反过来拖慢主线程。按核数与内存分档最稳。
     */
    val decodeParallelism: Int
        get() = when {
            isLowEnd -> 4
            isFlagship -> 8
            else -> 6
        }

    /**
     * 系统年代：Android 11（API 30）及以下算「老系统机」。
     *
     * 用户反馈的崩溃/卡顿主力就是这一批：华为 nova 6（Android 10 + Mali-G76）、
     * 各种 2019–2020 年的 6~8GB 机器。它们内存够、核数够，所以 [isLowEnd] 判不出来，
     * 但 ROM 老、GPU 老、后台杀得狠：同样的画面与缓存策略在上面就是更贵。
     * 老系统机一律按「省内存 + 少并发 + 短动画」跑，与低端机同一档处理。
     */
    @Volatile var isOldOs: Boolean = false
        private set

    /** 综合"该省着跑"的判断：低端机 或 老系统机。内存预算、并发度、动画都看这个。 */
    val legacy: Boolean get() = isLowEnd || isOldOs

    /** 总内存 MB，供各处按机型算预算（init 之前是 0 → 调用方自己兜底） */
    @Volatile var ramMb: Long = 0L
        private set

    /**
     * 在线流式缓存上限（MB）。
     *
     * 原来固定 256MB：老机器 / 64GB 存储的机型上，光这一份缓存就把剩余空间吃穿，
     * 系统盘一紧就卡 IO、media3 写索引更容易出错；被系统因为存储/内存压力杀掉后
     * 重启，用户看到的就是"又闪退了"。按机型给三档：老机型 64、中端 128、旗舰 256。
     */
    val cacheLimitMb: Int
        get() = when {
            isLowEnd || isOldOs -> 64
            isFlagship -> 256
            else -> 128
        }

    /** 封面内存缓存预算（MB）：老机型留 Heap 给播放器，别和 ExoPlayer 抢 */
    val artworkMemoryMb: Int
        get() = when {
            isLowEnd -> 16
            isOldOs -> 24
            isFlagship -> 48
            else -> 32
        }

    /** 封面磁盘缓存预算（MB） */
    val artworkDiskMb: Int
        get() = if (legacy) 64 else 128

    /**
     * 封面批量预取一次最多几首。
     *
     * 老机器上"预取下一整屏封面"会和当前可见区的解码抢那 3 路解码闸，
     * 结果首屏反而更慢；预取量按机型收一下。
     */
    val prefetchBatch: Int
        get() = if (legacy) 4 else 8

    /**
     * 频谱采样间隔（ms）：老机型少测一半。
     *
     * FFT 每帧一次，AudioEffect 采样在低端 SoC 上是持续的后台耗电；
     * 60ms→120ms 肉眼几乎看不出条子变钝，但采样和重绘都直接减半。
     */
    val spectrumIntervalMs: Int
        get() = if (legacy) 120 else 60

    // ==================== 老机安全档（R13 新增） ====================

    /** 安全档总开关的 prefs 键与取值：0=自动判定（默认）1=强制开 2=强制关 */
    const val SAFE_PREFS = "jun_prefs"
    const val KEY_SAFE_MODE = "legacy_safe_mode"
    const val SAFE_AUTO = 0
    const val SAFE_FORCE_ON = 1
    const val SAFE_FORCE_OFF = 2

    /**
     * 单项特效的手动覆盖键（都是三态：0=跟随安全档、1=强制开、2=强制关）。
     *
     * 为什么每个降级点要单独的键：只给一个总开关的话，用户在老机上想留一个
     * 「桌面歌词」就得放开全部特效，等于把风险一起放回来。三态分键既能
     * 默认对用户不可见地安全，也让设置页能逐项接线。
     */
    const val KEY_FX_VIDEO = "legacy_fx_video"
    const val KEY_FX_BG_BLUR = "legacy_fx_bg_blur"
    const val KEY_FX_HAPTICS = "legacy_fx_haptics"
    const val KEY_FX_HAPTICS_SCREEN_OFF = "legacy_fx_haptics_screen_off"
    const val KEY_FX_KEEP_SCREEN_ON = "legacy_fx_keep_screen_on"
    const val KEY_FX_HIGH_REFRESH = "legacy_fx_high_refresh"
    const val KEY_FX_OVERLAY_LYRIC = "legacy_fx_overlay_lyric"
    const val KEY_FX_WALLPAPER_LYRIC = "legacy_fx_wallpaper_lyric"
    const val KEY_FX_REFRACTION = "legacy_fx_refraction"
    const val KEY_FX_HEAVY_STAGE = "legacy_fx_heavy_stage"

    /** 全部单项覆盖键：init 与设置页写入后刷新时按这份清单读 */
    val FX_KEYS = listOf(
        KEY_FX_VIDEO, KEY_FX_BG_BLUR, KEY_FX_HAPTICS, KEY_FX_HAPTICS_SCREEN_OFF,
        KEY_FX_KEEP_SCREEN_ON, KEY_FX_HIGH_REFRESH, KEY_FX_OVERLAY_LYRIC,
        KEY_FX_WALLPAPER_LYRIC, KEY_FX_REFRACTION, KEY_FX_HEAVY_STAGE
    )

    /** 核数（init 之前 0） */
    @Volatile var coresCount: Int = 0
        private set

    /** 华为/荣耀 EMUI（含 HarmonyOS）ROM */
    @Volatile var isHuaweiRom: Boolean = false
        private set

    /** Android 10（API 29）及以下 */
    @Volatile var isApi29OrLower: Boolean = false
        private set

    /** EMUI + Android 10 及以下：本次 nova 6 反馈命中的那一档 */
    @Volatile var isEmuiOldOs: Boolean = false
        private set

    /** ≤6GB 内存：主线程心跳类特效最容易在这里演变成 wakelock 风暴 */
    @Volatile var tightMemory: Boolean = false
        private set

    @Volatile
    private var safeOverride: Int = SAFE_AUTO

    /**
     * 老机安全档：高风险特效默认整体关闭。
     *
     * 判定比 [legacy] 更宽（EMUI 老系统单独就能触发），且带 prefs 三态覆盖，
     * 用户可以在设置里逐项或整体放开。init 之前为 false —— 所有调用点都在
     * Application/Activity 的 init 之后，不存在"读到默认值导致没降级"的窗口。
     */
    @Volatile var safeMode: Boolean = false
        private set

    /** 读取单项三态覆盖：返回 null 表示"跟随安全档自动判定" */
    private fun triState(key: String): Int? {
        val v = safeOverrides[key] ?: return null
        return if (v == SAFE_AUTO) null else v
    }

    /**
     * 降级点统一写法：有明确覆盖就用覆盖，否则老机安全档关掉、新机放开。
     * 之所以不写成一堆散落的 `if (Buildxx)`：那样每处判定口径都可能不一致，
     * 出问题时也无法一眼看清"这台机器到底关了什么"。
     */
    private fun gate(key: String): Boolean {
        val o = triState(key)
        return when (o) {
            SAFE_FORCE_ON -> true
            SAFE_FORCE_OFF -> false
            else -> !safeMode
        }
    }

    /** 单项覆盖快照：init 里一次性读进内存，热路径（每帧/每次振动）不再碰 SharedPreferences */
    @Volatile private var safeOverrides: Map<String, Int> = emptyMap()

    /** 视频背景（第二路 MediaCodec 硬解 + TextureView） */
    val allowVideoBackdrop: Boolean get() = gate(KEY_FX_VIDEO)

    /** 背景封面实时模糊（API<31 走软件模糊，逐帧强制 drawAsImage，老机上最贵） */
    val allowBackgroundBlur: Boolean get() = gate(KEY_FX_BG_BLUR)

    /** 鼓点振动 */
    val allowBassHaptics: Boolean get() = gate(KEY_FX_HAPTICS)

    /** 熄屏/锁屏时是否允许鼓点振动（默认关：这条最像"把手机弹到密码屏"） */
    val allowHapticsWhenScreenOff: Boolean
        get() = (triState(KEY_FX_HAPTICS_SCREEN_OFF) ?: SAFE_FORCE_OFF) == SAFE_FORCE_ON

    /** 播放/看歌词时保持屏幕常亮 */
    val allowKeepScreenOn: Boolean get() = gate(KEY_FX_KEEP_SCREEN_ON)

    /** 主动切换 preferredDisplayModeId 抢高刷 */
    val allowHighRefreshRate: Boolean get() = gate(KEY_FX_HIGH_REFRESH)

    /** 桌面悬浮歌词（TYPE_APPLICATION_OVERLAY） */
    val allowOverlayLyric: Boolean get() = gate(KEY_FX_OVERLAY_LYRIC)

    /** 歌词动态壁纸（WallpaperService 贴着桌面/锁屏绘制） */
    val allowLyricWallpaper: Boolean get() = gate(KEY_FX_WALLPAPER_LYRIC)

    /** AGSL 真折射 */
    val allowTrueRefraction: Boolean get() = gate(KEY_FX_REFRACTION)

    /** 14 个歌词舞台里最重的几个形态（强粒子/灵魂残影/双环/颗粒+暗角/步进镜头） */
    val allowHeavyStages: Boolean get() = gate(KEY_FX_HEAVY_STAGE)

    /**
     * 表面材质是否强制实色（6.4.1 收窄口径）。
     *
     * 以前这里是 `safeMode`，而 safeMode = 低端 || 老系统 || EMUI 老系统 || 小内存，
     * 于是「内存小」直接等于「画不了玻璃」：新用户开箱就是实色/毛玻璃，
     * 和宣传图完全是两套观感。
     *
     * 现在只兜真正有风险的场景：
     *   · EMUI 10 及更早的华为机（isEmuiOldOs）—— 系统层老，玻璃管线曾引发
     *     悬浮窗/锁屏/SurfaceFlinger 异常；
     *   · 用户自己把 legacy_safe_mode 打开（SAFE_FORCE_ON）。
     *
     * 低端机不再强制实色：它们走「轻量液态玻璃」——只画半透明圆角矩形 + 内缘描边，
     * 不建图层、不录背板、不跑 RenderEffect/AGSL。实色本身也要画圆角矩形，
     * 增量成本只有一次 alpha 混合和一条 stroke。
     *
     * 其余特效开关（[allowHeavyStages] 等）仍然完整保留 safeMode 的判断，不受此处影响。
     */
    val forceSolidSurface: Boolean
        get() = safeMode && (isEmuiOldOs || safeOverride == SAFE_FORCE_ON)

    /**
     * 把某个舞台的专属调校压到安全区间。
     *
     * 返回 [font, motion, glow, look]。两条独立的闸，分别对应一个可单独覆盖的开关：
     * - 「形态」重映射看 [allowHeavyStages]（legacy_fx_heavy_stage）：用户单独放开这一项时，
     *   重粒子/双环/暗角这些最重的形态就该回来；
     * - 「动势/辉光/字号」上限看 [safeMode]（总闸）：这是整体负载，不给单列开关，
     *   避免老机上把某个舞台的形态放开之后负载直接翻倍。
     * 新机（safeMode=false 且 allowHeavyStages=true）原样返回，画面一字不变。
     *
     * 这里只返回数字、不 import ui 包的 StageProfile：避免 ui.theme ↔ ui 的双向依赖，
     * 调用方一行 `StageProfile(*DevicePerformance.clampStageProfile(...))` 即可接线。
     */
    fun clampStageProfile(stage: Int, font: Int, motion: Int, glow: Int, look: Int): IntArray {
        val remapLook = !allowHeavyStages
        val capLoad = safeMode
        if (!remapLook && !capLoad) return intArrayOf(font, motion, glow, look)
        val s = stage.coerceIn(0, 13)
        // 每个重舞台的"最省形态"索引，与 LyricStageProfiles 里的 looks 文案一一对应：
        // 2=辅助线「无」、5=头像「无头像」、8=粒子「无粒子」、9=表盘「无齿轮」、
        // 10=胶片「颗粒」（去掉暗角双层）、12=光线「无尘」（去掉全部光流）。
        val safeLook = if (!remapLook) look else when (s) {
            2 -> if (look == 3) 0 else look           // 网格 → 无
            5 -> if (look == 3) 2 else look           // 紧凑（大头像）→ 无头像
            7 -> if (look >= 2) 0 else look           // 椭圆环/双环 → 无
            8 -> if (look == 1 || look == 3) 2 else look // 强粒子/灵魂残影 → 无粒子
            9 -> if (look == 2) 1 else look           // 表盘封面 → 无齿轮
            10 -> if (look == 3) 1 else look          // 颗粒+暗角 → 颗粒
            12 -> if (look == 1) 3 else look          // 强光 → 无尘
            else -> look
        }
        val mCap = if (tightMemory || isEmuiOldOs) 70 else 100
        val gCap = if (tightMemory || isEmuiOldOs) 70 else 110
        return intArrayOf(
            if (capLoad) font.coerceIn(85, 125) else font,
            if (capLoad) motion.coerceAtMost(mCap) else motion,
            if (capLoad) glow.coerceAtMost(gCap) else glow,
            safeLook.coerceIn(0, 3)
        )
    }

    /**
     * 设置页写单项覆盖：先落 prefs，再刷新内存快照，最后回读实际生效值。
     *
     * 之所以把写入收在这里而不是让设置页直接 edit()：口径必须只有一处，
     * 否则改了 prefs 而热路径还在读旧快照，用户看到的就是"开关没反应"。
     */
    fun setFeatureOverride(context: Context, key: String, value: Int): Boolean {
        val v = when (value) { SAFE_AUTO, SAFE_FORCE_ON, SAFE_FORCE_OFF -> value; else -> SAFE_AUTO }
        runCatching {
            context.getSharedPreferences(SAFE_PREFS, Context.MODE_PRIVATE)
                .edit().putInt(key, v).apply()
        }
        refreshSafeMode(context)
        return when (triState(key)) {
            SAFE_FORCE_ON -> true
            SAFE_FORCE_OFF -> false
            else -> !safeMode
        }
    }

    /** 写安全档总开关（三态）。改完立刻重算，无需重启进程。 */
    fun setSafeModeOverride(context: Context, value: Int) {
        val v = when (value) { SAFE_AUTO, SAFE_FORCE_ON, SAFE_FORCE_OFF -> value; else -> SAFE_AUTO }
        runCatching {
            context.getSharedPreferences(SAFE_PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_SAFE_MODE, v).apply()
        }
        refreshSafeMode(context)
    }

    /** 重新读一遍 prefs（总开关 + 全部单项），供热更新后立刻生效 */
    fun refreshSafeMode(context: Context) {
        safeOverride = runCatching {
            context.getSharedPreferences(SAFE_PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_SAFE_MODE, SAFE_AUTO)
        }.getOrDefault(SAFE_AUTO)
        runCatching {
            val sp = context.getSharedPreferences(SAFE_PREFS, Context.MODE_PRIVATE)
            val snap = HashMap<String, Int>(FX_KEYS.size)
            FX_KEYS.forEach { k -> snap[k] = sp.getInt(k, SAFE_AUTO) }
            safeOverrides = snap
        }
        safeMode = when (safeOverride) {
            SAFE_FORCE_ON -> true
            SAFE_FORCE_OFF -> false
            else -> isLowEnd || isOldOs || isEmuiOldOs || tightMemory
        }
    }

    /**
     * 设置页/诊断页展示用：当前这台机器实际关掉了哪些。
     *
     * 只在 init 之后有意义；全部走一次 prefs 快照，读它是零成本，
     * 可以安全地放进「关于」页里当"我的设备为什么没有某个效果"的答案。
     */
    fun safeModeReport(): List<String> {
        val off = ArrayList<String>(10)
        if (!allowVideoBackdrop) off += "视频背景"
        if (!allowBackgroundBlur) off += "背景实时模糊"
        if (!allowBassHaptics) off += "鼓点振动"
        if (!allowKeepScreenOn) off += "播放常亮屏"
        if (!allowHighRefreshRate) off += "高刷切换"
        if (!allowOverlayLyric) off += "悬浮歌词"
        if (!allowLyricWallpaper) off += "歌词壁纸"
        if (!allowTrueRefraction) off += "真折射玻璃"
        if (!allowHeavyStages) off += "重舞台特效"
        return off
    }
}

/** AGSL RuntimeShader（折射/色散位移采样）：最贵的一段，仅旗舰启用 */
internal val isRuntimeShaderSupported: Boolean
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        DevicePerformance.isFlagship &&
        // 老机安全档（或被用户强制关掉真折射）时整体退出：即便 SoC 够，
        // 逐元件背板取样 + AGSL 在系统层老化的机器上就是最容易引发
        // RenderNode/SurfaceFlinger 异常的负载。
        DevicePerformance.allowTrueRefraction

/**
 * RenderEffect（模糊 / 色彩矩阵）与 GraphicsLayer 逐元件取样。
 *
 * 只在旗舰档启用。中端机以前也走这条路：每个 CARD/CHIP/SHEET 都建一个
 * GraphicsLayer、把整块背板录进来、再跑一遍模糊——背板本身已经全屏糊过一次，
 * 这里等于对同一张背景重复做 N 次全屏模糊。8 Gen 1 的 GPU 撑不住这种叠加，
 * 所以中端直接关掉取样，只画半透明染色 + 内缘高光；背景仍是糊的，观感接近，
 * 但每帧不再有 N 条全背板流水线。
 */
internal val isRenderEffectSupported: Boolean
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DevicePerformance.isFlagship &&
        DevicePerformance.allowTrueRefraction

// ==================== AGSL ====================

/** 圆角矩形 SDF：距离场 + 边缘法向，折射与内缘高光共用 */
private const val ROUNDED_RECT_SDF = """
float junRadiusAt(float2 c, float4 radii) {
    if (c.x >= 0.0) {
        return c.y <= 0.0 ? radii.y : radii.z;
    } else {
        return c.y <= 0.0 ? radii.x : radii.w;
    }
}

float junSd(float2 c, float2 hs, float r) {
    float2 q = abs(c) - (hs - float2(r));
    return length(max(q, 0.0)) - r + min(max(q.x, q.y), 0.0);
}

float2 junGrad(float2 c, float2 hs, float r) {
    float2 q = abs(c) - (hs - float2(r));
    if (q.x >= 0.0 || q.y >= 0.0) {
        return sign(c) * normalize(max(q, float2(0.0001)));
    } else {
        float gx = step(q.y, q.x);
        return sign(c) * float2(gx, 1.0 - gx);
    }
}

float2 junSafeNorm(float2 v) {
    float l = length(v);
    return l > 0.0001 ? v / l : float2(0.0, 0.0);
}

float junCircleMap(float x) {
    return 1.0 - sqrt(max(1.0 - x * x, 0.0));
}
"""

/**
 * 折射：距边缘 height 以内的像素，沿边缘法向向内偏移取样。
 *
 * 偏移量走 circleMap（圆弧曲线）：贴边处变化最急、往里迅速趋零，
 * 这正是玻璃厚度切面的光学分布。depth > 0 时法向再往中心掰一点，
 * 边缘就从"平板玻璃"变成"有弧度的透镜"。
 */
private const val REFRACTION_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float height;
uniform float amount;
uniform float depth;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 cc = (coord + offset) - hs;
    float r = junRadiusAt(cc, cornerRadii);

    float sd = junSd(cc, hs, r);
    if (-sd >= height) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = junCircleMap(1.0 - (-sd / height)) * -amount;
    float gr = min(max(r, 1.0) * 1.5, min(hs.x, hs.y));
    float2 g = normalize(junGrad(cc, hs, gr) + depth * junSafeNorm(cc));

    return content.eval(coord + d * g);
}
"""

/** 带色散的折射：RGB 三通道错开取样，四角最强，边缘泛出极淡的虹彩 */
private const val REFRACTION_DISPERSION_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float height;
uniform float amount;
uniform float depth;
uniform float dispersion;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 cc = (coord + offset) - hs;
    float r = junRadiusAt(cc, cornerRadii);

    float sd = junSd(cc, hs, r);
    if (-sd >= height) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = junCircleMap(1.0 - (-sd / height)) * -amount;
    float gr = min(max(r, 1.0) * 1.5, min(hs.x, hs.y));
    float2 g = normalize(junGrad(cc, hs, gr) + depth * junSafeNorm(cc));

    float2 base = coord + d * g;
    float k = dispersion * ((cc.x * cc.y) / (hs.x * hs.y));
    float2 shift = d * g * k;

    half4 red = content.eval(base + shift);
    half4 green = content.eval(base);
    half4 blue = content.eval(base - shift);

    return half4(red.r, green.g, blue.b, (red.a + green.a + blue.a) / 3.0);
}
"""

/**
 * 镜面内缘：SDF 法向与光源方向的点积取幂作强度。
 *
 * 迎光侧亮、背光侧暗，沿轮廓描一圈就是玻璃的边缘切面。
 * 两侧要分两次画（迎光用 Plus 叠加才够亮，背光用 SrcOver 才压得出暗边），
 * 所以用 alpha 开关让某一侧不出图。输出预乘。
 */
private const val EDGE_SHADER = """
uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 lightColor;
layout(color) uniform half4 darkColor;
uniform float lightAlpha;
uniform float darkAlpha;
uniform float angle;
uniform float falloff;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 cc = coord - hs;
    float r = junRadiusAt(cc, cornerRadii);

    float gr = min(max(r, 1.0) * 1.5, min(hs.x, hs.y));
    float2 g = junGrad(cc, hs, gr);
    float d = dot(g, float2(cos(angle), sin(angle)));
    float intensity = pow(abs(d), falloff);

    float3 rgb = d >= 0.0 ? float3(lightColor.rgb) : float3(darkColor.rgb);
    float a = (d >= 0.0 ? lightAlpha : darkAlpha) * intensity;
    return half4(half3(rgb * a), half(a));
}
"""

/** 单个玻璃元件的 shader 缓存：RuntimeShader 必须复用实例，uniform 每次重设 */
internal class GlassShaders {

    private val cache = HashMap<String, RuntimeShader>(4)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun obtain(key: String, source: String): RuntimeShader =
        cache.getOrPut(key) { RuntimeShader(source) }
}

/**
 * RenderEffect 缓存：调色/模糊两类效果只取决于数值参数，与元件尺寸无关。
 * key 粒度＝参数本身，参数集合很小（模糊档位 + 固定调色曲线），缓存几乎恒命中。
 */
@RequiresApi(Build.VERSION_CODES.S)
private object EffectCache {
    private val colorFilters = java.util.concurrent.ConcurrentHashMap<String, AndroidRenderEffect>()
    private val blurs = java.util.concurrent.ConcurrentHashMap<String, AndroidRenderEffect>()

    inline fun colorFilter(
        brightness: Float, contrast: Float, saturation: Float,
        create: () -> AndroidRenderEffect
    ): AndroidRenderEffect =
        colorFilters.getOrPut("b:$brightness|c:$contrast|s:$saturation", create)

    inline fun blur(blurPx: Float, create: () -> AndroidRenderEffect): AndroidRenderEffect =
        blurs.getOrPut("r:${(blurPx * 100).toInt()}", create)
}

private const val DISPERSION_STRENGTH = 0.6f

/**
 * 背板效果链：调色 → 模糊 → 折射。
 *
 * 顺序有讲究。先调色（提对比度/饱和度）让背景颜色更足——玻璃里有东西可折射才不像塑料；
 * 折射必须放在模糊之后，否则位移取样会把未模糊的锐利像素拽到边缘，出现重影。
 */
@RequiresApi(Build.VERSION_CODES.S)
internal fun buildGlassEffect(
    shaders: GlassShaders,
    spec: GlassSpec,
    size: Size,
    cornerRadii: FloatArray,
    padding: Float,
    blurPx: Float,
    refractionPx: Float
): AndroidRenderEffect? {
    var effect: AndroidRenderEffect? = null

    // 调色/模糊两类 RenderEffect 与元件尺寸无关（只取决于参数），按参数缓存复用。
    // 之前每帧新建整条链，effect 对象本身虽轻，但在 120Hz 下每秒 120 次的分配与
    // native 侧 RenderNode 状态切换累计可观；缓存后命中路径零分配。
    if (spec.contrast != 1f || spec.saturation != 1f || spec.brightness != 0f) {
        effect = EffectCache.colorFilter(
            spec.brightness, spec.contrast, spec.saturation
        ) { AndroidRenderEffect.createColorFilterEffect(colorControlsFilter(spec.brightness, spec.contrast, spec.saturation)) }
    }

    if (blurPx > 0f) {
        val blur = EffectCache.blur(blurPx) {
            AndroidRenderEffect.createBlurEffect(blurPx, blurPx, AndroidShader.TileMode.CLAMP)
        }
        effect = if (effect == null) blur else AndroidRenderEffect.createChainEffect(blur, effect)
    }

    if (refractionPx > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = if (spec.dispersion) {
            shaders.obtain("dispersion", REFRACTION_DISPERSION_SHADER).apply {
                setFloatUniform("dispersion", DISPERSION_STRENGTH)
            }
        } else {
            shaders.obtain("refraction", REFRACTION_SHADER)
        }
        shader.apply {
            setFloatUniform("size", size.width, size.height)
            setFloatUniform("offset", -padding, -padding)
            setFloatUniform("cornerRadii", cornerRadii)
            setFloatUniform("height", refractionPx)
            setFloatUniform("amount", refractionPx * spec.refractionScale)
            setFloatUniform("depth", spec.depth)
        }
        val lens = AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
        effect = if (effect == null) lens else AndroidRenderEffect.createChainEffect(lens, effect)
    }

    return effect
}

/** 内缘 shader。[light] = true 取迎光侧（配 Plus 叠加），false 取背光侧（配 SrcOver）。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun buildEdgeShader(
    shaders: GlassShaders,
    spec: GlassSpec,
    size: Size,
    cornerRadii: FloatArray,
    light: Boolean
): AndroidShader =
    shaders.obtain(if (light) "edgeLight" else "edgeDark", EDGE_SHADER).apply {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("cornerRadii", cornerRadii)
        setColorUniform("lightColor", spec.lightEdge.copy(alpha = 1f).toArgb())
        setColorUniform("darkColor", spec.darkEdge.copy(alpha = 1f).toArgb())
        setFloatUniform("lightAlpha", if (light) spec.lightEdge.alpha else 0f)
        setFloatUniform("darkAlpha", if (light) 0f else spec.darkEdge.alpha)
        setFloatUniform("angle", Math.toRadians(spec.edgeAngle.toDouble()).toFloat())
        setFloatUniform("falloff", spec.edgeFalloff)
    }

/**
 * 亮度 / 对比度 / 饱和度矩阵。
 *
 * 这是"不泛白"的核心手段：想让玻璃里的背景更清楚，就提对比度和饱和度，
 * 而不是往上叠白——叠白会把背后封面的颜色一起冲淡，冲淡了就没有可折射的东西。
 */
private fun colorControlsFilter(
    brightness: Float,
    contrast: Float,
    saturation: Float
): ColorMatrixColorFilter {
    val invSat = 1f - saturation
    val r = 0.213f * invSat
    val g = 0.715f * invSat
    val b = 0.072f * invSat
    val t = (0.5f - contrast * 0.5f + brightness) * 255f
    val cr = contrast * r
    val cg = contrast * g
    val cb = contrast * b
    val cs = contrast * saturation
    return ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                cr + cs, cg, cb, 0f, t,
                cr, cg + cs, cb, 0f, t,
                cr, cg, cb + cs, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
}

/** 形状四角半径 → shader 用的 (左上, 右上, 右下, 左下) */
internal fun Density.glassCornerRadii(
    shape: Shape,
    size: Size,
    layoutDirection: LayoutDirection
): FloatArray {
    val max = size.minDimension / 2f
    val corners = shape as? CornerBasedShape ?: return FloatArray(4) { 0f }
    val ltr = layoutDirection == LayoutDirection.Ltr
    val topLeft = (if (ltr) corners.topStart else corners.topEnd).toPx(size, this)
    val topRight = (if (ltr) corners.topEnd else corners.topStart).toPx(size, this)
    val bottomRight = (if (ltr) corners.bottomEnd else corners.bottomStart).toPx(size, this)
    val bottomLeft = (if (ltr) corners.bottomStart else corners.bottomEnd).toPx(size, this)
    return floatArrayOf(
        topLeft.coerceAtMost(max),
        topRight.coerceAtMost(max),
        bottomRight.coerceAtMost(max),
        bottomLeft.coerceAtMost(max)
    )
}