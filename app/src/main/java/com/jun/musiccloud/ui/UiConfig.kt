package com.jun.musiccloud.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity

/**
 * 6.8：全局字号 & 排版密度。
 *
 * 用户这轮要「界面的标准 / 紧凑 / 宽松」以及「字号」可调。之前这些散在各处写死，
 * 现在统一收口到 [JunUiConfig]：一改，整 App 的字号和纵向间距一起变。
 *
 * - 字号：通过 [LocalDensity] 提供给整棵组合树（在 MainScreen 根部 `Density(density, scale)`），
 *   是系统级、最稳的做法，不会漏掉任何文字。
 * - 间距：通过 [LocalCompact] 提供倍率（0.85 / 1.0 / 1.18），首页列表、弹层、卡片内部读它来收缩/放宽。
 *
 * 落盘在独立 SharedPreferences，重启不丢。
 */
object JunUiConfig {

    /** 三档密度：紧凑 / 标准 / 宽松 */
    enum class DensityLevel(val label: String, val fontScale: Float, val spacing: Float) {
        COMPACT("紧凑", 0.92f, 0.82f),
        STANDARD("标准", 1.0f, 1.0f),
        LOOSE("宽松", 1.08f, 1.18f)
    }

    private const val PREFS = "jun_ui_config"
    private const val KEY_LEVEL = "density_level"

    private val _level = mutableStateOf(DensityLevel.STANDARD)
    /** 当前密度档；自定义中心与系统启动处读取 */
    val level: MutableState<DensityLevel> get() = _level

    private var inited = false
    @Volatile private var appCtx: Context? = null
    fun init(ctx: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            appCtx = ctx.applicationContext
            val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val name = p.getString(KEY_LEVEL, DensityLevel.STANDARD.name)
            _level.value = runCatching { DensityLevel.valueOf(name ?: "STANDARD") }
                .getOrDefault(DensityLevel.STANDARD)
            inited = true
        }
    }

    fun setLevel(l: DensityLevel) {
        _level.value = l
        // 用 init 时缓存的 Application Context（JunApp.onCreate 里传入），不依赖静态全局字段
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_LEVEL, l.name)?.apply()
    }

    /** 字号倍率（喂给 LocalDensity） */
    val fontScale: Float get() = _level.value.fontScale
    /** 间距倍率（喂给 LocalCompact） */
    val spacing: Float get() = _level.value.spacing
}

/** 当前的间距倍率，列表/弹层用它收缩或放宽纵向间距 */
val LocalCompact = compositionLocalOf { 1.0f }
