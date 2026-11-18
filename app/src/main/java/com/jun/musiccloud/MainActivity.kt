package com.jun.musiccloud

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jun.musiccloud.player.PlayerViewModel
import com.jun.musiccloud.playback.FloatingLyric
import com.jun.musiccloud.ui.CustomConfig
import com.jun.musiccloud.ui.MainScreen
import com.jun.musiccloud.ui.SafeModeLayer
import com.jun.musiccloud.ui.hasSeenOnboarding
import com.jun.musiccloud.ui.theme.DevicePerformance
import com.jun.musiccloud.ui.theme.GlassRuntime
import com.jun.musiccloud.ui.theme.JunMusicTheme
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    /** 当前我们主动钉住的显示模式 id；0 = 交还系统（默认） */
    private var appliedDisplayModeId = 0

    /** 本机不适合抢高刷（应用失败或切换没落地）：置真后本次进程不再尝试，避免模式来回抖 */
    private var highRefreshRejected = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // 纯在线播放：通知权限被拒也不影响播放，这里无需任何回调动作。
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 低端设备探测：决定是否自动关闭液态玻璃真折射（自适应降级）
        DevicePerformance.init(this)
        // 首启不再一上来就弹系统权限框：通知与悬浮窗都放进首启设置的「② 权限」，
        // 用户看清用途后自己点「去开启」。老用户（已完成首启）保持静默申请，不打断。
        //
        // 6.4：**只问一次**。以前每次冷启动都调 requestNeededPermissions()，
        // 用户拒绝过之后每次开 App 还弹一遍，属于把"我不想要"当成"他还没决定"。
        // 拒绝后不再自动问，设置里仍然可以手动开。
        if (hasSeenOnboarding(this) && !com.jun.musiccloud.ui.hasAskedNotification(this)) {
            requestNeededPermissions()
            com.jun.musiccloud.ui.markNotificationAsked(this)
        }
        enableImmersiveMode()
        enableHighRefreshRate()

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val baseDensity = androidx.compose.ui.platform.LocalDensity.current
            // 6.6 流畅性：缓存 Density 实例。以前每次重组（进度、封面、滚动）都 new 一个
            // Density 塞进 CompositionLocal，整棵树所有读 LocalDensity 的节点跟着全量失效，
            // 是掉帧的主因之一。现在只有字号真变了才换对象。
            val rootDensity = androidx.compose.runtime.remember(baseDensity, state.fontScale) {
                androidx.compose.ui.unit.Density(
                    density = baseDensity.density,
                    fontScale = baseDensity.fontScale * state.fontScale
                )
            }
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides rootDensity
            ) {
            JunMusicTheme(
                theme = state.theme,
                mode = state.mode,
                cornerBase = state.cornerBase,
                surfaceStyle = state.surfaceStyle,
                glassBlur = state.glassBlur,
                uiFont = state.uiFont
            ) {
                // 6.6.2：自定义界面召唤手势（与「长按音量+」二选一）。
                // 双击屏幕后继续按住约半秒即召唤自定义界面；只观测事件、不消费，不影响正常点按。
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { summonCustomByDoubleTapHold() }
                ) {
                MainScreen(
                    state = state,
                    viewModel = viewModel,
                    onToggle = viewModel::togglePlay,
                    onPrev = viewModel::previous,
                    onNext = viewModel::next,
                    onSeek = viewModel::seekTo,
                    onSelect = viewModel::playAt,
                    onSearch = viewModel::updateSearch,
                    onToggleLike = viewModel::toggleLike,
                    onCyclePlayMode = viewModel::cyclePlayMode,
                    onToggleSettings = viewModel::toggleSettings
                )
                // 6.9.8：安全模式覆盖层。safemode_pending 为真（上次真实崩溃）才渲染，
                // 放在 MainScreen 之后，绘制在最上层；正常用户永远不进这里。
                SafeModeLayer(state = state, viewModel = viewModel)
                }
            }
            }
        }
    }

    /** 6.6.2：长按音量+ 键召唤自定义界面（隐藏搜索/设置入口后的备用入口） */
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            runCatching { CustomConfig.showDialog.value = true }
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    /** 双击屏幕并继续按住约 500ms → 召唤自定义界面。事件只观测、不消费。 */
    private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.summonCustomByDoubleTapHold() {
        awaitPointerEventScope {
            while (true) {
                awaitFirstDown(requireUnconsumed = false)
                // 280ms 内出现第二次按下 = 双击
                val second = withTimeoutOrNull(280) { awaitFirstDown(requireUnconsumed = false) }
                    ?: continue
                // 双击后继续按住 500ms 视为「双击+长按」
                val held = withTimeoutOrNull(500) {
                    do {
                        val e = awaitPointerEvent()
                    } while (e.changes.none { it.changedToUpIgnoreConsumed() })
                    true
                }
                if (held == true) runCatching { CustomConfig.showDialog.value = true }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 6.8 修：锁屏复查 —— 若开了锁屏歌词开关，但 Android 12+ 未授予「全屏通知」权限，
        // 6.7 的 fullScreenIntent 通道会被系统静默掐掉，亮屏永远弹不出。回到前台时弹一次
        // 引导把用户带到设置页开启（这是平台唯一 sanctioned 的解决路径）。
        maybePromptFullScreenIntent()
        // 亮屏/解锁回来先恢复玻璃管线：熄屏时 onPause 已经把帧监控和背板取样一起收了，
        // 不恢复的话再进来就是"整棵树永久实色"——用户看到的是画质莫名变差且重启才好。
        runCatching { GlassRuntime.resumeFromDisplayOff() }
        // 悬浮歌词的屏幕状态广播是它自己那条唯一的"重新挂窗"通路。
        // 之前有一次 unregister 失败（熄屏收窗时走过），这里补挂一次；
        // 函数内部幂等，重复调用只会立刻返回。补挂之后再手动同步一次显隐：
        // onResume 只在解锁后才发生，此刻 syncVisibility 内部的 keyguard 判定是干净的，
        // 否则 screenOn 这个字段可能停在过期的 false 上，窗口就一直不回来。
        runCatching { FloatingLyric.registerScreenReceiver() }
        runCatching { FloatingLyric.refresh() }
        // 回到前台重新申请高刷：停后台时我们主动把 preferredDisplayModeId 归零（见 onPause），
        // 一是省电（别的 App 在前台时不该被我们钉在 120Hz），二是万一那次模式切换
        // 在系统侧没落地，回来时按当前 display 的真实状态重算一次，避免拿着脏 modeId 不放。
        enableHighRefreshRate()
        // 6.2：切回前台时，如果歌单广场还开着，重拉一次「我的歌单」并接上自动换新。
        // 用户在网易云 App 里建完歌单切回来，靠这一发当场就能看到，不必退出重登。
        viewModel.onUiResumed()
        // 6.9.3：回前台补拉一次后台推送（内部 5 分钟节流，不会每次都打接口）。
        // 放独立线程：这是网络请求，绝不能碰主线程。
        runCatching {
            Thread({
                runCatching { com.jun.musiccloud.data.PushInbox.poll(this) }
            }, "push-poll-resume").start()
        }
        // 6.4.1：回前台刷一次「意见反馈」红点。站长回复后用户切回来就能看到，
        // 不用等下次冷启动。内部有并发保护，冷启动与回前台同时触发只会跑一次。
        runCatching { viewModel.refreshFeedbackUnread() }
    }

    /**
     * 离开前台（含锁屏/熄屏）时必须收干净我们碰过的窗口状态。
     *
     * 为什么这条对老机型是安全问题而不只是洁癖：
     * 1) FLAG_KEEP_SCREEN_ON 是加在 Activity 的 window 上的。Keyguard 出现时走的是
     *    onPause，此时如果这个标志还挂着，WindowManager 会一边被要求熄屏、
     *    一边看到前台窗口声明"我要亮屏"，Android 10 的 EMUI 在这对矛盾下
     *    会重开一次 Keyguard——用户看到的就是"屏幕闪一下，变成输入密码那一屏"。
     * 2) preferredDisplayModeId 留在 window 属性里会让显示模式在我们并不在前台时
     *    仍然被请求，模式切换失败时系统侧的兜底是黑屏重启显示管线。
     * 两处都在这里清掉；横屏沉浸自己的 DisposableEffect 也会清，双份无害（clearFlags 幂等）。
     */
    override fun onPause() {
        runCatching { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        resetDisplayMode()
        // 玻璃管线随窗口一起收：熄屏后不该再为看不见的帧挂 vsync、录背板。
        runCatching { GlassRuntime.pauseForDisplayOff() }
        // 离开前台停掉「我的歌单」的自动换新：弹层开关还在 ViewModel 里仍是 true，
        // 不停的话就是后台每 30 秒一次没人看的请求（回来时 onUiResumed 会接上）。
        viewModel.onUiPaused()
        super.onPause()
    }

    /** 把显示模式交还系统（preferredDisplayModeId = 0 表示"由系统决定"）。 */
    private fun resetDisplayMode() {
        if (appliedDisplayModeId == 0) return
        appliedDisplayModeId = 0
        runCatching {
            val params = window.attributes
            if (params.preferredDisplayModeId != 0) {
                params.preferredDisplayModeId = 0
                window.attributes = params
            }
        }
    }

    /**
     * 配置变更自己处理（配合 AndroidManifest 里扩后的 configChanges）。
     *
     * 声明了"我们自己接"之后，系统就不会再重建 Activity——这正是老机上想要的结果，
     * 但也意味着任何"重建时顺手会重做一遍"的事，得在这里补做，否则就是纯回归。
     * 本项目需要重做的只有三件，都是窗口/渲染层面的，没有一件需要销毁 Compose 树：
     *  1) 系统栏：EMUI 在插上蓝牙键盘 / 切深色模式后会把自己的沉浸式设置覆盖回去；
     *  2) 显示模式：外接屏或刷新率策略变了，之前钉住的 modeId 可能已失效；
     *  3) 玻璃层：drawWithCache 按尺寸缓存，density 变了必须重画一次（尺寸缓存不重算就花屏）。
     * 主题不依赖系统深色模式（无 values-night，深浅由 App 内 state.mode 决定），
     * 所以 uiMode 变更这里无需额外动作。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching { enableImmersiveMode() }
        runCatching { enableHighRefreshRate() }
        runCatching { GlassRuntime.requestRedraw() }
        // 冷启动之后 RAM/核数不会变，但用户在别处改过安全档的可能性要接住
        runCatching { DevicePerformance.refreshSafeMode(this) }
    }

    /**
     * 6.8 修：锁屏复查 —— 锁屏歌词「亮屏不弹」的引导。
     *
     * Android 12+ 把 fullScreenIntent 限制在了来电/闹钟这类系统通道，音乐 App 默认拿不到，
     * 必须用户在设置里手动开启「全屏通知 / 出现在其他应用上」。没开时 MusicService 里的
     * 双通道（fullScreenIntent + 后台 startActivity）都会静默失败，表现就是用户说的
     * 「亮屏还是没弹出锁屏歌词」。这里在回前台时检测一次并引导，转静默失败为用户可修。
     */
    private fun maybePromptFullScreenIntent() {
        // 6.6.1 紧急修复：NotificationManager.canUseFullScreenIntent() 是 Android 14
        // (API 34) 才加入的方法。6.6 误按 S(31) 做门槛，导致 Android 12/13 在
        // onResume 直接抛 NoSuchMethodError、一进主界面就闪退。34 以下跳过该查询，
        // 锁屏双通道逻辑不受影响；整段再包一层兜底，任何异常都不带崩主界面。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            val prefs = getSharedPreferences("jun_prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("lock_screen_player", true)) return@runCatching
            if (prefs.getBoolean("lock_screen_fullintent_prompted", false)) return@runCatching
            val nm = getSystemService(NotificationManager::class.java) ?: return@runCatching
            if (nm.canUseFullScreenIntent()) return@runCatching
            prefs.edit().putBoolean("lock_screen_fullintent_prompted", true).apply()
            AlertDialog.Builder(this)
                .setTitle("开启锁屏歌词秒显")
                .setMessage("系统已限制后台 App 自动弹出界面。请开启「全屏通知 / 出现在其他应用上」权限，亮屏时锁屏歌词即可第一秒弹出。")
                .setPositiveButton("去设置") { _, _ -> openFullScreenIntentSettings() }
                .setNegativeButton("暂不", null)
                .show()
        }.onFailure {
            android.util.Log.w("MainActivity", "fullScreenIntent prompt skipped: ${it.message}")
        }
    }

    /** 6.8 修：跳到本 App 的全屏通知授权页（14+ 有专用入口；12/13 进应用通知设置）。 */
    private fun openFullScreenIntentSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:$packageName")
            }
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        }
        runCatching { startActivity(intent) }
    }

    /** 沉浸式系统栏：内容延伸至状态栏/导航栏区域，系统栏透明、图标跟随主题，常驻显示 */
    private fun enableImmersiveMode() {
        // 内容延伸到状态栏/导航栏区域，背景铺满全屏（消除黑块）
        // 整段包 runCatching：老 ROM 上 setDecorFitsSystemWindows 会与厂商自己的
        // 沉浸式实现（华为的 notch 适配层）打架，抛异常就是"点开 App 白屏"。
        runCatching { WindowCompat.setDecorFitsSystemWindows(window, false) }
        // 保持系统栏常驻（不隐藏），只做透明化处理，避免滑动唤出导致退应用要划两次
    }

    private fun requestNeededPermissions() {
        // 纯在线播放：不再需要读取本地媒体 / 「所有文件访问」权限，避免首启就弹高危授权。
        // 仅申请通知权限用于后台播放通知（Android 13+）。
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = perms.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    /**
     * 高刷新率支持：选取设备支持的最高刷新率显示模式（如 120Hz / 144Hz），
     * 让 Compose 以原生高帧率渲染，滑动与动画更顺滑。
     * Android 6.0+（M）通过 preferredDisplayModeId 生效；低版本无操作。
     *
     * R13 老机适配收口的三件事：
     * 1) 判定改成"新机型才做"：不仅看 isLowEnd/isOldOs，还走 DevicePerformance
     *    的安全档总闸（用户可以在设置里逐项放开，见 KEY_FX_HIGH_REFRESH）；
     * 2) 失败可回退：应用后延时校验实际 modeId，没落地就把 preferredDisplayModeId
     *    归零并记一次永久拒绝，本次进程不再重试（模式来回切换比一直 60Hz 更伤）；
     * 3) 不留悬挂状态：离开前台一律归零（见 onPause），避免我们不在前台时
     *    还钉着显示模式，撞上别的 App 的切换请求。
     */
    private fun enableHighRefreshRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        // 已经判定"这台机器不该抢高刷"或上次切换失败被拒绝：直接停在系统默认档。
        if (highRefreshRejected) return
        // 低端机：保持系统默认刷新率（通常 60Hz），避免强制高刷导致满帧渲染卡顿。
        // 中高端机才启用设备支持的最高刷新率（如 120Hz / 144Hz）。
        // 低端机 / 老系统机 / EMUI 老机一律不强开高刷：这类机器的最高刷新率
        // 常常是 90/120Hz，但 GPU 撑不住整帧合成，结果是满帧率掉帧 + 发热，
        // 比老实停在 60Hz 更卡。老机型（含 Android 11 及以下的次旗舰）同样按这条走。
        if (DevicePerformance.isLowEnd || DevicePerformance.isOldOs ||
            !DevicePerformance.allowHighRefreshRate
        ) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val display = wm.defaultDisplay ?: return
        val modes = display.supportedModes ?: return
        if (modes.isEmpty()) return
        val best = modes.maxByOrNull { it.refreshRate } ?: return
        // 仅当存在比当前更高刷新率时才切换，避免无谓的模式变更
        if (best.refreshRate <= display.refreshRate + 0.5f) return
        val targetId = best.modeId
        val applied = runCatching {
            val params = window.attributes
            params.preferredDisplayModeId = targetId
            window.attributes = params
            true
        }.getOrDefault(false)
        if (!applied) {
            // 属性都写不进去（部分 ROM 对非系统 App 收窄了该权限）：别再来第二次
            highRefreshRejected = true
            return
        }
        appliedDisplayModeId = targetId
        // 模式切换是异步的：给 600ms，然后看真实生效的 mode 是不是我们要的那一个。
        // 校验写在 decorView.postDelayed 上——沿用主线程 Handler，不新建线程/Looper。
        window.decorView.postDelayed({
            val now = runCatching { display.mode?.modeId ?: -1 }.getOrDefault(-1)
            // -1 取不到（个别 ROM 隐藏该字段）时不动：拿不到证据就维持现状，
            // 只有"明确没切过去"才回退，避免把本来正常的高刷误杀
            if (now in 1 until Int.MAX_VALUE && now != targetId) {
                highRefreshRejected = true
                resetDisplayMode()
            }
        }, 600L)
    }
}