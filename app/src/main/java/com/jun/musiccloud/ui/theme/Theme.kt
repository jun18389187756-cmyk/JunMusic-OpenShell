package com.jun.musiccloud.ui.theme
import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** 全局 UI 字体族：BasicTextField 等不吃 LocalTextStyle 的控件从这里取，保证整个 App 统一换字体。 */
val LocalJunUiFamily = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }

@Composable
fun JunMusicTheme(
    theme: JunTheme = JunTheme.MONO,
    mode: JunMode = JunMode.DARK,
    /** 全局圆角基准（dp），由设置项驱动 */
    cornerBase: Float = 38f,
    /** 表面材质：实色 / 毛玻璃 / 液态玻璃 */
    surfaceStyle: JunSurfaceStyle = JunSurfaceStyle.SOLID,
    /** 模糊浓度倍率，1.0 为基准 */
    glassBlur: Float = GLASS_BLUR_DEFAULT,
    /** 界面字体索引：0=系统无衬线 1=衬线 2=ChildFun 3=ChillRound 4=志勇大同体 5=乐米波波体，见 [JunFonts] */
    uiFont: Int = 0,
    content: @Composable () -> Unit
) {
    // 目标配色：切主题/明暗模式时不直接硬切，而是对每个颜色字段做 320ms 缓动过渡，
    // 切换观感从"啪一下变色"变成"丝滑渐变色"，整体流畅度提升最明显的一处。
    val target = theme.colors(mode)
    val spec = tween<androidx.compose.ui.graphics.Color>(320, easing = EaseInOutCubic)
    val primary by animateColorAsState(target.primary, spec, label = "cPrimary")
    val secondary by animateColorAsState(target.secondary, spec, label = "cSecondary")
    val background by animateColorAsState(target.background, spec, label = "cBg")
    val surface by animateColorAsState(target.surface, spec, label = "cSurface")
    val card by animateColorAsState(target.card, spec, label = "cCard")
    val row by animateColorAsState(target.row, spec, label = "cRow")
    val text by animateColorAsState(target.text, spec, label = "cText")
    val subText by animateColorAsState(target.subText, spec, label = "cSubText")
    val accentBorder by animateColorAsState(target.accentBorder, spec, label = "cBorder")
    val colors = target.copy(
        primary = primary, secondary = secondary, background = background,
        surface = surface, card = card, row = row, text = text,
        subText = subText, accentBorder = accentBorder
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // 这里的强转必须包起来：JunMusicTheme 不只被 Activity 用，
            // Dialog / ComposeView / 壁纸预览里 LocalView.context 可能是 ContextWrapper，
            // 直接 `as Activity` 会 ClassCastException —— 而且它发生在 SideEffect 里，
            // 也就是每一帧组合稳定后都可能炸，表现就是"开着开着闪退"。
            val window = (view.context as? Activity)?.window
            if (window != null) {
                // 系统栏透明：背景由 App 内容铺满（无黑白底），图标深浅跟随明暗模式
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                // 关闭系统栏对比度 scrim：Android 会强制在深色背景上的系统栏加灰色面，
                // 顶部状态栏和底部小白条位置的那些"灰块"就是它。关掉后真正透明。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                    window.isStatusBarContrastEnforced = false
                }
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !colors.isDark
                controller.isAppearanceLightNavigationBars = !colors.isDark
            }
        }
    }

    val colorScheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.primary,
            secondary = colors.secondary,
            tertiary = JunPalette.NeonPurple,
            background = colors.background,
            surface = colors.surface,
            onPrimary = JunPalette.OutlineBlack,
            onBackground = colors.text,
            onSurface = colors.text
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            secondary = colors.secondary,
            tertiary = JunPalette.NeonPurple,
            background = colors.background,
            surface = colors.surface,
            onPrimary = JunPalette.CardWhite,
            onBackground = colors.text,
            onSurface = colors.text
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typographyForIndex(uiFont)
    ) {
        // 三档自适应（6.4.1 重写）：
        //
        // 旧逻辑把「低端机」和「安全档」直接判成实色，于是新用户开箱看到的是实色/毛玻璃，
        // 和宣传图里的液态玻璃完全不是一回事（用户原话："感觉宣传图是假的"）。
        //
        // 新逻辑只区分「要不要跑真折射」和「要不要实色兜底」两件事：
        //   · 实色兜底：仅限系统层可能不稳的机器（EMUI 10 及更早的华为）或用户手动开的
        //     legacy_safe_mode。这类机器上玻璃管线本身就是闪退/花屏的来源，必须退。
        //   · 轻量液态玻璃：其余全部机型（含低端机、中端机）。保留半透明本体 +
        //     内缘高光 + 顶部反光，**不建 GraphicsLayer、不录背板、不跑 RenderEffect/AGSL**。
        //     每个元件的额外成本就一次圆角矩形填充和一条描边，实色也要画这两笔。
        //   · 完整液态玻璃：只有真旗舰（≥10GB 且 ≥8 核且非 8Gen1 一代）才开真折射。
        //
        // 换句话说：低端机第一眼也是液态玻璃，只是"薄"了一层光学；旗舰才有真折射。
        val solidDevice = DevicePerformance.forceSolidSurface
        val lightGlass = !solidDevice && !DevicePerformance.isFlagship
        val effectiveSurface = if (solidDevice) JunSurfaceStyle.SOLID else surfaceStyle
        val effectiveBlur = when {
            // 实色档没有模糊可言，给最小值避免下游除零/异常
            solidDevice -> GLASS_BLUR_MIN
            // 轻量档不做逐元件真模糊：模糊只在「背景整屏模糊」那一层生效，
            // 这里压到最低，防止任何漏网的按元件模糊被放大
            lightGlass -> GLASS_BLUR_MIN
            else -> glassBlur.coerceIn(GLASS_BLUR_MIN, GLASS_BLUR_MAX)
        }
        // 圆角尺度下发给整棵树，所有 JunShape.* 读取的都是这个值
        CompositionLocalProvider(
            LocalCorners provides JunCorners(cornerBase.dp),
            LocalSurfaceStyle provides effectiveSurface,
            LocalGlassBlur provides effectiveBlur,
            // 全局界面字体：Text 未显式传 style 时继承 LocalTextStyle，
            // 输入框等需要显式 TextStyle 的控件从 LocalJunUiFamily 取，二者同源。
            LocalJunUiFamily provides fontFamilyForIndex(uiFont),
            androidx.compose.material3.LocalTextStyle provides
                androidx.compose.ui.text.TextStyle(
                    fontFamily = fontFamilyForIndex(uiFont)
                ),
            content = content
        )
    }
}