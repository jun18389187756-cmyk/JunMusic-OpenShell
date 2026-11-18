package com.jun.musiccloud.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.BuildConfig
import com.jun.musiccloud.data.ServerApi
import com.jun.musiccloud.data.Telemetry
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.player.PlayerViewModel
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.colors
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.harmonizeColorsForSurface
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.readableTextOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 安全模式覆盖层（6.9.8）。
 *
 * 只在 jun_prefs 里 `safemode_pending` 为真时渲染——该标记只由 JunApp 的真实崩溃处理器写入，
 * 普通退出 / 轻微异常都不会触发，所以正常用户永远看不到这一层。
 *
 * 行为：
 *  · 居中卡片，说明上次崩溃并进入安全模式；
 *  · 「下载修复版」：拉 /api/app/version 取 apkUrl，用 ACTION_VIEW 拉起（下载 / 安装）；
 *  · 「正常启动」：清掉 safemode_pending，覆盖层随即消失，App 照常使用；
 *  · 进场时把"进入安全模式"上报后台一次（进程内只发一次，失败不抛）。
 *
 * 配色与全站同源：复用 state.theme.colors + harmonizeColorsForSurface，junSurface/junPressable
 * 与 AppUpdateDialog 同一套视觉语言。
 */
@Composable
fun SafeModeLayer(state: PlayerUiState, viewModel: PlayerViewModel) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("jun_prefs", Context.MODE_PRIVATE) }
    var pending by remember { mutableStateOf(prefs.getBoolean("safemode_pending", false)) }
    if (!pending) return

    val colors = harmonizeColorsForSurface(
        state.theme.colors(
            state.mode,
            state.systemDark,
            customPrimary = Color(state.customPrimaryArgb),
            customSecondary = Color(state.customSecondaryArgb)
        )
    )
    val scope = rememberCoroutineScope()

    // 进场即上报一次（LaunchedEffect(Unit) 仅在首次进入组合时执行一次）
    LaunchedEffect(Unit) {
        reportSafeModeOnce(ctx, prefs)
    }

    Box(
        Modifier
            .fillMaxSize()
            // 点遮罩不关闭：必须二选一，避免误触把"进安全模式"这件事消解掉
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable { },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.86f)
                .junSurface(GlassLevel.SHEET, colors, RoundedCornerShape(22.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "安全模式",
                color = colors.text,
                fontSize = 19.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "上次启动发生崩溃，已进入安全模式。你可以在这里下载修复版本，或先正常启动。",
                color = colors.subText,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f).height(44.dp)
                        .junPressable {
                            // 正常启动：清标记并收起覆盖层，App 照常运行
                            prefs.edit().putBoolean("safemode_pending", false).apply()
                            pending = false
                        }
                        .clip(JunShape.item)
                        .background(colors.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "正常启动",
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    Modifier
                        .weight(1f).height(44.dp)
                        .junPressable {
                            scope.launch { fetchFix(ctx) }
                        }
                        .clip(JunShape.item)
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "下载修复版",
                        color = colors.primary.readableTextOn(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * 拉取服务器当前版本并打开 apkUrl。
 *
 * 复用 AppUpdateDialog 同款的 [ServerApi.fetchVersion] 取 apkUrl，
 * 用 ACTION_VIEW + FLAG_ACTIVITY_NEW_TASK (+ FLAG_GRANT_READ_URI_PERMISSION) 拉起——
 * 既可能是浏览器下载页，也可能是系统安装器，随服务端下发的地址而定。
 * 全程 try/catch + 后台线程，绝对不阻塞、不崩溃。
 */
private suspend fun fetchFix(ctx: Context) {
    val url = withContext(Dispatchers.IO) {
        runCatching {
            val v = ServerApi.fetchVersion(
                channel = "stable",
                deviceKey = Telemetry.deviceId(ctx),
                versionCode = BuildConfig.VERSION_CODE,
                model = android.os.Build.MODEL.orEmpty()
            )
            v?.apkUrl?.takeIf { it.isNotBlank() }
        }.getOrNull()
    } ?: return
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
}

/**
 * 进入安全模式后上报后台一次。
 *
 * 用 prefs 里的 safemode_reported 做幂等：哪怕组合多次重建也只发一次；
 * 上报内容取自崩溃时落下的 safemode_crash_sig / safemode_version，设备标识复用 Telemetry。
 */
private suspend fun reportSafeModeOnce(ctx: Context, prefs: android.content.SharedPreferences) {
    if (prefs.getBoolean("safemode_reported", false)) return
    withContext(Dispatchers.IO) {
        runCatching {
            val deviceId = Telemetry.deviceId(ctx)
            val versionCode = prefs.getInt("safemode_version", BuildConfig.VERSION_CODE)
            val crashSig = prefs.getString("safemode_crash_sig", "").orEmpty()
            ServerApi.reportSafeMode(deviceId, versionCode, crashSig)
            prefs.edit().putBoolean("safemode_reported", true).apply()
        }
    }
}
