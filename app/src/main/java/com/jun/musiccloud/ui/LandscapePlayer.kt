package com.jun.musiccloud.ui

import android.app.Activity
import android.view.WindowManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.jun.musiccloud.ui.theme.DevicePerformance

/**
 * 横屏模式共用的小工具。
 *
 * 横屏「沉浸」不再是独立黑胶页，也不再在播放页/唱片墙浮入口按钮：
 * 唯一入口是歌词层右上角的小胶囊，进入后复用 Folia 十四种舞台。
 * 本文件只保留方向控制、沉浸系统栏与手机小图标。
 */

/** 当前是否横屏。全 App 统一从这里判断，避免每处各写一遍 Configuration 比较。 */
@Composable
internal fun rememberIsLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/** 从任意 Context 里找到宿主 Activity（Compose 的 LocalContext 可能是 ContextWrapper）。 */
internal fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** 点横屏标志：锁定横屏（左右两个方向都允许，躺着拿手机也不会被卡住）。 */
internal fun Activity.enterLandscapeMode() {
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}

/**
 * 退出横屏（6.6）：**直接**把方向交还系统，不再强制先竖屏。
 *
 * 旧实现先锁 PORTRAIT、900ms 后才 FULL_USER，本意是「让回竖屏这一步真的落地」。
 * 但用户开着自动旋转、手机本来就横着拿时，这段会变成肉眼可见的两次翻转：
 * 退出全屏歌词 → 先被掰成竖屏 → 传感器又转回横屏。
 * 退出全屏歌词只是退出沉浸层，不该替用户决定屏幕方向；
 * FULL_USER 会立刻按当前传感器姿态给出正确方向，一步到位。
 */
internal fun Activity.exitLandscapeMode() {
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
}

/** 横屏沉浸：藏起系统栏，退出时恢复。 */
@Composable
internal fun HideSystemBarsForImmersive() {
    val view = LocalView.current
    val context = LocalContext.current
    // 必须在组合期取：LocalLifecycleOwner.current 是 @Composable，
    // 在 DisposableEffect 的回调体（DisposableEffectScope）里读不到。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        if (activity == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // 看歌词时屏幕不能被系统超时压暗：横屏沉浸里长时间不操作也保持常亮。
            //
            // R13 老机收口，两条：
            // 1) 只在"当前真的在前台"时挂标志。activity.window.addFlags 是加在窗口上的
            //    长期状态，锁屏后还挂着，等于一边系统要熄屏、一边窗口要求亮屏，
            //    Android 10 的 EMUI 在这种矛盾下会重弹一次 Keyguard ——
            //    用户描述的就是"屏幕闪一下变成输入密码那一屏"。
            //    所以改成跟随生命周期：ON_RESUME 加、ON_PAUSE 立刻撤。
            // 2) 老机安全档（或被用户关掉该项）时完全不挂：这类机器上"常亮"省下的
            //    那点体验，抵不上电源状态机被打乱的风险。
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                        if (DevicePerformance.allowKeepScreenOn)
                            runCatching {
                                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                    androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                    androidx.lifecycle.Lifecycle.Event.ON_STOP,
                    androidx.lifecycle.Lifecycle.Event.ON_DESTROY ->
                        runCatching {
                            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            // 组合进入时通常已经是 RESUMED，观察者不会再补一次，这里手动补上。
            if (lifecycleOwner.lifecycle.currentState
                    .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                DevicePerformance.allowKeepScreenOn
            ) {
                runCatching {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
                lifecycleOwner.lifecycle.removeObserver(observer)
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

/** 手机图标：portrait=true 竖着，false 横躺（旋转 -90°）。 */
@Composable
internal fun DeviceGlyph(tint: Color, portrait: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        rotate(degrees = if (portrait) 0f else -90f) {
            val w = size.width * 0.54f
            val h = size.height * 0.84f
            val left = (size.width - w) / 2f
            val top = (size.height - h) / 2f
            drawRoundRect(
                color = tint,
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = CornerRadius(w * 0.24f, w * 0.24f),
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawCircle(
                color = tint,
                radius = 1.1.dp.toPx(),
                center = Offset(size.width / 2f, top + h - 2.6.dp.toPx())
            )
        }
    }
}
