package com.jun.musiccloud.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.jun.musiccloud.data.Telemetry
import com.jun.musiccloud.data.UpdateChecker
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.readableTextOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL

/** 官网下载页（「前往官网更新」固定打开这里，走手机当前默认浏览器） */
internal const val OFFICIAL_SITE_URL = "https://api.example.invalid/"

/** 应用内更新流程的四个阶段（失败可重试，下载中可以取消） */
private sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data class Downloading(val progress: Float) : UpdatePhase
    data class Ready(val file: File) : UpdatePhase
    /** 系统「允许安装未知应用」开关还没打开，等用户回来继续 */
    data class NeedPermission(val file: File) : UpdatePhase
    data class Failed(val message: String) : UpdatePhase
}

/**
 * 版本更新提示框（应用内下载 + 直接拉起安装器）。
 *
 * 由服务器控制是否强制：force=true 时不可取消（返回键 / 点遮罩都关不掉）。
 * 点「立即更新」不再跳浏览器：APK 直接下到缓存目录，校验 zip 头后交给
 * FileProvider + 系统安装器，下载、安装一步到位；失败再提供浏览器兜底。
 */
@Composable
internal fun AppUpdateDialog(
    update: UpdateChecker.Update,
    colors: JunColors,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<UpdatePhase>(UpdatePhase.Idle) }

    val busy = phase is UpdatePhase.Downloading

    val install: (File) -> Unit = { file ->
        if (ApkInstaller.canInstall(ctx)) {
            if (!ApkInstaller.install(ctx, file)) {
                phase = UpdatePhase.Failed("没有找到系统安装器，请用浏览器下载安装")
            }
        } else {
            // Android 8.0+ 首次需要用户给「安装未知应用」授权；授权后回来点「继续安装」
            ApkInstaller.openUnknownSourcesSettings(ctx)
            phase = UpdatePhase.NeedPermission(file)
        }
    }

    val startDownload: () -> Unit = {
        phase = UpdatePhase.Downloading(0f)
        scope.launch {
            try {
                val file = downloadApk(ctx, update.apkUrl, update.size) { p ->
                    phase = UpdatePhase.Downloading(p)
                }
                // 必须校验 SHA-256：服务端没给校验值也不装，避免坏包 / 被替换的包。
                if (update.sha256.isBlank()) {
                    file.delete()
                    throw IllegalStateException("服务器没有提供安装包校验信息，请点「前往官网更新」")
                }
                val got = sha256Of(file)
                if (!got.equals(update.sha256.trim(), ignoreCase = true)) {
                    file.delete()
                    throw IllegalStateException("安装包校验失败（SHA-256 不一致），请重新下载")
                }
                // 再确认包名：防止下到别的应用（或网页伪装成 APK）
                val info = ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                if (info == null || info.packageName != ctx.packageName) {
                    file.delete()
                    throw IllegalStateException("安装包不属于本应用，已阻止安装")
                }
                phase = UpdatePhase.Ready(file)
                install(file)
            } catch (e: Exception) {
                phase = UpdatePhase.Failed(e.message ?: "下载失败，请稍后重试")
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!update.force && !busy) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !update.force && !busy,
            dismissOnClickOutside = !update.force && !busy
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .junSurface(GlassLevel.SHEET, colors, RoundedCornerShape(22.dp))
                .padding(22.dp)
        ) {
            Text(
                "发现新版本 ${update.versionName}",
                color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (update.force) "需要更新后才能继续使用" else "建议更新以获得更好体验",
                color = colors.subText, fontSize = 12.sp
            )
            if (update.changelog.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    update.changelog,
                    color = colors.text, fontSize = 13.sp, lineHeight = 20.sp,
                    modifier = Modifier
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                )
            }

            val p = phase
            if (p is UpdatePhase.Downloading) {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth().height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.surface)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((p.progress.takeIf { it > 0f } ?: 0.02f).coerceIn(0f, 1f))
                            .height(6.dp)
                            .background(colors.primary)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (update.size > 0) "正在下载 ${(p.progress * 100).toInt()}% · 约 ${update.size / 1024 / 1024} MB"
                    else "正在下载 ${(p.progress * 100).toInt()}%",
                    color = colors.subText, fontSize = 12.sp
                )
            } else if (p is UpdatePhase.NeedPermission) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "安装包已下载完成。请在系统设置里允许「本应用安装应用」，然后回来点「继续安装」。",
                    color = colors.subText, fontSize = 12.sp, lineHeight = 18.sp
                )
            } else if (p is UpdatePhase.Failed) {
                Spacer(Modifier.height(14.dp))
                Text(
                    p.message,
                    color = colors.primary, fontSize = 12.sp, lineHeight = 18.sp
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!update.force && !busy) {
                    Box(
                        Modifier
                            .weight(1f).height(44.dp)
                            // 按压层在 clip/background 之前：整颗按钮（含底色）一起软下去
                            .junPressable { onDismiss() }
                            .clip(JunShape.item)
                            .background(colors.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("稍后", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(
                    Modifier
                        .weight(1f).height(44.dp)
                        .junPressable {
                            when (val cur = phase) {
                                is UpdatePhase.Downloading -> Unit
                                is UpdatePhase.Ready -> install(cur.file)
                                is UpdatePhase.NeedPermission -> install(cur.file)
                                else -> startDownload()
                            }
                        }
                        .clip(JunShape.item)
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when (phase) {
                            is UpdatePhase.Downloading -> "下载中 ${(((phase as UpdatePhase.Downloading).progress) * 100).toInt()}%"
                            is UpdatePhase.Ready -> "安装"
                            is UpdatePhase.NeedPermission -> "继续安装"
                            is UpdatePhase.Failed -> "重试"
                            else -> "立即更新"
                        },
                        color = colors.primary.readableTextOn(),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                }
            }

            // 一直可见的兜底入口：不管下载是否成功，都能用手机当前浏览器打开官网下载页。
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth().height(42.dp)
                    .junPressable {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_SITE_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        if (!update.force && !busy) onDismiss()
                    }
                    .clip(JunShape.item)
                    .background(colors.surface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "前往官网更新  ↗",
                    color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }

            // 强制更新且失败时给一个「退出应用」的出口：
            // 不让人卡在既装不上又关不掉的弹窗里。
            if (update.force && phase is UpdatePhase.Failed) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth().height(42.dp)
                        .junPressable { finishApp(ctx) }
                        .clip(JunShape.item)
                        .background(colors.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "退出应用",
                        color = colors.subText, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
            }

            if (phase is UpdatePhase.Failed) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "改用浏览器下载",
                    color = colors.subText, fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .junPressableRow {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.apkUrl)))
                            }
                            if (!update.force) onDismiss()
                        }
                )
            }
        }
    }
}

/** 结束整个应用（强制更新失败时的出口）。 */
private fun finishApp(ctx: Context) {
    runCatching {
        var c: Context? = ctx
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) break
            c = c.baseContext
        }
        if (c is android.app.Activity) {
            c.finishAffinity()
        } else {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}

private fun sha256Of(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * 下载 APK 到 cache/updates。
 *
 * 做三件事，避免把网页 / 错误页当安装包：
 *   1. 手动跟随最多 5 次重定向（蓝奏云 / CDN 常见）；
 *   2. 校验开头是 zip 魔数（APK 本质是 zip）；
 *   3. 文件太小（< 100KB）直接判失败。
 */
private suspend fun downloadApk(
    ctx: Context,
    url: String,
    expectedSize: Long,
    onProgress: (Float) -> Unit
): File = withContext(Dispatchers.IO) {
    val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
    val tmp = File(dir, "JunMusic-update.apk.tmp")
    val out = File(dir, "JunMusic-update.apk")
    if (tmp.exists()) tmp.delete()

    // 空间预检：安装包 + 校验/安装过程的余量，空间不够直接给出人话提示
    if (expectedSize > 0) {
        val free = runCatching { android.os.StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < expectedSize + 20L * 1024 * 1024) {
            throw IOException("手机存储空间不足，请清理出至少 ${(expectedSize / 1024 / 1024) + 20} MB 后重试")
        }
    }

    var conn: HttpURLConnection? = null
    var stream: InputStream? = null
    try {
        var current = url
        var redirects = 0
        while (redirects <= 5) {
            val c = URL(current).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "JunMusic")
            c.setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("下载地址跳转异常")
                current = URL(URL(current), loc).toString()
                c.disconnect()
                redirects++
                continue
            }
            if (code !in 200..299) throw IOException("下载失败（HTTP $code）")
            stream = c.inputStream
            break
        }
        val input = stream ?: throw IOException("下载失败，请检查网络")

        val total = conn?.contentLengthLong ?: -1L
        val expected = if (expectedSize > 0) expectedSize else total
        val buf = ByteArray(64 * 1024)
        var written = 0L
        var checked = false
        FileOutputStream(tmp).use { fos ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                if (!checked && n >= 2) {
                    checked = true
                    // "PK"：zip / apk 的固定开头
                    if (buf[0] != 0x50.toByte() || buf[1] != 0x4B.toByte()) {
                        throw IOException("服务器返回的不是安装包，请稍后用浏览器重试")
                    }
                }
                fos.write(buf, 0, n)
                written += n
                if (expected > 0) onProgress((written.toFloat() / expected).coerceIn(0f, 1f))
            }
        }
        if (written < 100 * 1024) throw IOException("安装包不完整，请重试")
        if (expectedSize > 0 && written != expectedSize) {
            throw IOException("安装包大小不一致（应为 ${expectedSize / 1024 / 1024} MB，实际 ${written / 1024 / 1024} MB），请重试")
        }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
        out
    } finally {
        runCatching { stream?.close() }
        runCatching { conn?.disconnect() }
        if (tmp.exists()) runCatching { tmp.delete() }
    }
}

/** 安装器：FileProvider 把缓存里的 APK 换成 content:// 再交给系统 */
private object ApkInstaller {

    /** Android 8.0+ 需要「安装未知应用」权限 */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun install(ctx: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
        true
    }.getOrDefault(false)
}

/**
 * 维护模式提示：服务器把「维护」打开时盖住整个界面。
 *
 * 不可取消（返回键 / 点遮罩都关不掉），只提供「重试」——点一下立刻重新拉配置，
 * 后台一关掉开关就能马上恢复，不用等轮询、也不用杀进程重进。
 */
@Composable
internal fun MaintenanceDialog(
    text: String,
    colors: JunColors,
    onRetry: () -> Unit
) {
    Dialog(
        onDismissRequest = { /* 维护中不允许关闭 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .junSurface(GlassLevel.SHEET, colors, RoundedCornerShape(22.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "服务维护中",
                color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text.ifBlank { "服务器正在升级维护，请稍后再试。" },
                color = colors.subText, fontSize = 13.sp, lineHeight = 20.sp,
                modifier = Modifier
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            )
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth().height(44.dp)
                    .junPressable { onRetry() }
                    .clip(JunShape.item)
                    .background(colors.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "重试",
                    color = colors.primary.readableTextOn(),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 本机随包发布的**版本公告**弹层（6.1.0 起，正文只来自本轮的 WhatsNewR1x 对象，当前是 [WhatsNewR19]）。
 *
 * 和 [NoticeDialog] 的分工：那一款是**服务器下发**的公告，必须读满 3 秒、
 * 点遮罩与返回键都关不掉（紧急通知不能让人手滑关掉）；这里这款是本机文案，
 * 用户读完自己会走，所以做成可取消的普通弹层。
 *
 * 6.3 定稿（第六轮改稿）：用户要求「再简短一点」，并点名「你这说的都根本不全」。
 * 于是弹层不再分「本次修复 / 本次改动」两段折叠——那种结构会把 5 条压成 4+1，
 * 看着像两份清单；现在改成**一条一行、单一列表**，修复和新增按用户关心的顺序排。
 * 历史总结仍然默认收起，想看的人点开。
 * 折叠动画沿用设置里 FoldGroup 同一套（AnimatedVisibility 的默认淡入 + 纵向展开），
 * 不写自定义时长——R12 刚把全站动效整体放慢，这里再自己来一套就是回归。
 *
 * 「只弹一次」不在这个文件里管：门控在 [hasSeenWhatsNew] / [markWhatsNewSeen]，
 * 弹出时机与渲染门在 MainScreen（启动流程最后、首页信息流出来之后）。
 * 设置里「查看本次更新公告」复用同一个渲染位（把 showWhatsNew 置回 true），
 * 所以这里不需要自己记状态；折叠状态也不写 prefs，公告是看一眼的事。
 */
@Composable
internal fun WhatsNewDialog(
    colors: JunColors,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    // 6.7：6.2 → 6.7 的历史总结，默认收起——想看的人点开，不想看的完全不占地方
    var historyOpen by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .junSurface(GlassLevel.SHEET, colors, RoundedCornerShape(22.dp))
                .padding(20.dp)
        ) {
            Text(
                WhatsNewR19.DIALOG_TITLE,
                color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(4.dp))
            Text(WhatsNewR19.SUBTITLE, color = colors.subText, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))

            // 内容放在可滚动框里：nova 6 这种 16:9 的小屏上不会把按钮顶出屏幕。
            // 单一列表（修复在前、新增在后），不再做两段折叠——用户要的是"几句话看完"。
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.text.copy(alpha = 0.035f))
                    .padding(12.dp)
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // 直接平铺，不复用可折叠的 WhatsNewGroup：
                    // 那一款标题行带「展开/收起」和按压缩放，5 条全展开时那颗"收起"是多余的开关。
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(JunShape.item)
                            .background(colors.surface.copy(alpha = 0.28f))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        // 6.6 最新版：把「说明与边界」也一起铺上，跟开机引导同一份完整清单
                        (WhatsNewR19.FIXES + WhatsNewR19.NEWS + WhatsNewR19.NOTES).forEachIndexed { i, item ->
                            if (i > 0) Spacer(Modifier.height(9.dp))
                            Text(
                                item.title,
                                color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Black,
                                lineHeight = 19.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                buildAnnotatedString {
                                    val parts = item.body.split("**")
                                    parts.forEachIndexed { idx, part ->
                                        if (part.isEmpty()) return@forEachIndexed
                                        if (idx % 2 == 1) {
                                            withStyle(SpanStyle(fontWeight = FontWeight.Black, color = colors.text)) {
                                                append(part)
                                            }
                                        } else {
                                            append(part)
                                        }
                                    }
                                },
                                color = colors.subText, fontSize = 12.sp, lineHeight = 18.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    WhatsNewHistoryGroup(
                        label = WhatsNewR19.HISTORY_LABEL,
                        colors = colors,
                        expanded = historyOpen,
                        onToggle = { historyOpen = !historyOpen }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            // 底部那句指路和纯文本结尾是同一句（WhatsNewR19.FOOTER），两处不会说岔
            Text(
                if (copied) "公告已经复制，直接粘给朋友就行" else WhatsNewR19.FOOTER,
                color = colors.subText, fontSize = 11.sp, lineHeight = 17.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f).height(44.dp)
                        .junPressable {
                            // 用系统剪贴板，与设置里几处「复制」同一写法（不碰 Compose 的
                            // LocalClipboardManager：它的 setText/getText 签名在 BOM 版本间变过）
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            cm?.setPrimaryClip(
                                android.content.ClipData.newPlainText(
                                    "Jun Music 更新公告",
                                    WhatsNewR19.PLAIN_TEXT
                                )
                            )
                            copied = true
                        }
                        .clip(JunShape.item)
                        .background(colors.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Text("复制文本", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier
                        .weight(1f).height(44.dp)
                        .junPressable(feedback = PressFeedback.CLICK) { onDismiss() }
                        .clip(JunShape.item)
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "知道了",
                        color = colors.primary.readableTextOn(),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * 公告里的一段（修复 / 新增）：一行标题 + 展开后的正文。
 * 折叠时只留标题，两段都收起就是「一屏看完这一轮改了啥」的那个体积。
 */
@Composable
private fun WhatsNewGroup(
    label: String,
    items: List<WhatsNewItem>,
    colors: JunColors,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                // 按压层放在 clip / background 之前：整条标题（含底色）一起软下去，与设置里折叠组一致
                .junPressable { onToggle() }
                .clip(JunShape.item)
                .background(colors.surface.copy(alpha = if (expanded) 0.35f else 0.20f))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f)
            )
            Text(if (expanded) "收起" else "展开", color = colors.subText, fontSize = 11.sp)
        }
        AnimatedVisibility(
            visible = expanded,
            // 6.7：改用固定 180ms 的标准曲线。之前走默认（果冻弹簧要 1 秒上下才收敛），
            // 折叠/展开时整块内容长时间在动，小屏上看起来就是"特别卡"。
            enter = fadeIn(tween(120)) + expandVertically(tween(180, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(180, easing = FastOutSlowInEasing))
        ) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
                items.forEach { item ->
                    Text(
                        item.title,
                        color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(3.dp))
                    // 正文里的 **重点** 会加粗：公告要短，重点靠字重标出来
                    Text(
                        buildAnnotatedString {
                            val parts = item.body.split("**")
                            parts.forEachIndexed { i, part ->
                                if (part.isEmpty()) return@forEachIndexed
                                if (i % 2 == 1) {
                                    withStyle(SpanStyle(fontWeight = FontWeight.Black, color = colors.text)) { append(part) }
                                } else {
                                    append(part)
                                }
                            }
                        },
                        color = colors.subText, fontSize = 12.sp, lineHeight = 18.sp
                    )
                    if (item.where.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        // 落地位置单独一行：导览讲不完的东西，靠这一行也能自己找到
                        Text(
                            "就在 " + item.where,
                            color = colors.primary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

/**
 * 6.7 新增：公告里的历史总结段（6.2 → 6.7，人话版）。
 * 与 [WhatsNewGroup] 同一套外观和 180ms 折叠动画，只是内容是「版本 → 一句话」的清单。
 */
@Composable
private fun WhatsNewHistoryGroup(
    label: String,
    colors: JunColors,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .junPressable { onToggle() }
                .clip(JunShape.item)
                .background(colors.surface.copy(alpha = if (expanded) 0.35f else 0.20f))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f)
            )
            Text(if (expanded) "收起" else "展开", color = colors.subText, fontSize = 11.sp)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(120)) + expandVertically(tween(180, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(180, easing = FastOutSlowInEasing))
        ) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
                WhatsNewR19.HISTORY.forEach { (ver, text) ->
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Black, color = colors.text)) {
                                append(ver + "  ")
                            }
                            append(text)
                        },
                        color = colors.subText, fontSize = 12.sp, lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** 通知中心弹窗：符合整体 UI 的卡片 + 必须等 3 秒的「已读」按钮，点遮罩 / 返回都关不掉 */
@Composable
internal fun NoticeDialog(
    title: String,
    content: String,
    level: String,
    colors: JunColors,
    noticeId: Long = 0L,
    imageUrl: String = "",
    linkUrl: String = "",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var remain by remember { mutableIntStateOf(3) }
    var image by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(noticeId) { if (noticeId > 0) Telemetry.reportNotice(context, noticeId, "shown") }
    LaunchedEffect(imageUrl) {
        if (imageUrl.isBlank()) return@LaunchedEffect
        image = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(imageUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    LaunchedEffect(title, content) {
        remain = 3
        while (remain > 0) {
            kotlinx.coroutines.delay(1000L)
            remain -= 1
        }
    }

    val accent = when (level) {
        "warn" -> Color(0xFFFF5D5D)
        "update" -> Color(0xFF4ADE80)
        else -> colors.primary
    }
    val badge = when (level) {
        "warn" -> "紧急提醒"
        "update" -> "版本更新"
        else -> "公告"
    }

    Dialog(
        onDismissRequest = { /* 只能点「已读」关闭，点空白 / 返回键都不行 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .junSurface(GlassLevel.SHEET, colors, RoundedCornerShape(26.dp))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when (level) { "warn" -> "!" ; "update" -> "↑" ; else -> "i" },
                        color = accent, fontSize = 19.sp, fontWeight = FontWeight.Black
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        badge, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        title.ifBlank { "公告" },
                        color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            image?.let { bmp ->
                Spacer(Modifier.height(12.dp))
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.text.copy(alpha = 0.035f))
                    .padding(14.dp)
            ) {
                Text(
                    content,
                    color = colors.text, fontSize = 13.sp, lineHeight = 21.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
            if (linkUrl.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "查看详情 →",
                    color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .junPressableRow(feedback = PressFeedback.NONE) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl))) }
                        }
                        .padding(vertical = 4.dp),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (remain > 0) "请阅读 ${remain} 秒后点「已读」" else "已阅读完毕，可以关闭了",
                color = colors.subText, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth().height(46.dp)
                    .junPressable(enabled = remain == 0) { onDismiss() }
                    .clip(JunShape.item)
                    .background(if (remain > 0) colors.text.copy(alpha = 0.08f) else accent),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (remain > 0) "已读（${remain}s）" else "已读",
                    color = if (remain > 0) colors.subText else accent.readableTextOn(),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
