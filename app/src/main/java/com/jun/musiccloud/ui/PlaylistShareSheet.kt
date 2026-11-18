package com.jun.musiccloud.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.data.PlaylistImport
import com.jun.musiccloud.data.PlaylistShare
import com.jun.musiccloud.data.ShareImportOutcome
import com.jun.musiccloud.data.SharePayload
import com.jun.musiccloud.data.ShareTrack
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.readableOn
import com.jun.musiccloud.ui.theme.readableTextOn
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.LocalJunUiFamily
import com.jun.musiccloud.ui.theme.junSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「分享歌单 / 导入歌单口令」弹层。
 *
 * 为什么单独一个文件、而不是塞进 PlaylistSheets：那个文件已经一千行，
 * 而且这一屏的状态机（编码中 / 太长要分卷 / 导入中第 N 首 / 失败清单）
 * 和歌单管理是两件事，混在一起以后改动效要翻两个上下文。
 *
 * 外壳一律走 [JunSheet]：遮罩厚度、折射玻璃、拖把手下滑、入场回弹
 * 都由 [JunSheet] / [junSheetSlideIn] / [JunMotion] 统一给，
 * 这里不再写第二套弹簧参数（R12 已经把全站动效放慢，再造一条快的就是漂移）。
 *
 * @param payload 要分享的对象（自定义歌单 / 我喜欢 / 最近播放会话）。
 *   传 null = 这屏只做导入：从「导入 Jun 口令」入口进来时用，
 *   这时候顶部只留标题，导出那一整块不画。
 * @param onImport 真正干活的是 PlayerViewModel（解码 → 匹配 → 建歌单 → 落盘）。
 *   进度回调可能从 IO 线程发来，所以本函数内部一律 `scope.launch` 回到主线程再写 state。
 */
@Composable
internal fun PlaylistShareSheet(
    payload: SharePayload?,
    /** 预填进导入粘贴框的文本：从歌单层统一入口自动识别成口令时带上 */
    initialPaste: String = "",
    colors: JunColors,
    onDismiss: () -> Unit,
    onNotice: (String) -> Unit,
    onSystemShare: (code: String, shareText: String) -> Unit,
    onImport: suspend (code: String, onProgress: (PlaylistImport.Progress) -> Unit) -> ShareImportOutcome
) {
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val fieldBg = if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC)
    val errorColor = colors.dangerText
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    val drag = rememberSheetDragState()
    val scroll = rememberScrollState()

    // ===== 导出：口令是算出来的，不是 state 里的常驻数据 =====
    //
    // 压缩 + base64 一千首要几毫秒到几十毫秒，放组合里同步算会让弹层"卡一下才出现"，
    // 所以丢进 LaunchedEffect（默认跟着组合的生命周期走，弹层一关就取消）。
    //
    // R14（用户："分享那个界面非常非常的闪"）两条：
    //  ① key 换成**值**判据（名字 + 曲数 + 分卷），不再用 payload 实例。
    //     payloadOf() 里有 System.currentTimeMillis()，只要调用方某一次组合没把 payload
    //     remember 住，payload 就是个"内容相同、实例不同"的新对象；而
    //     `remember(payload)` / `LaunchedEffect(payload)` 全按实例比对 —— 于是每一次重组
    //     都把整条编码链路重跑一遍。
    //  ② 重算期间**保留上一次的口令**，不再退回 null。原来 null → 「正在生成口令…」→ 口令
    //     这么一来一回就是肉眼可见的闪；导入时每报一次进度都会重组一次，于是"非常非常闪"。
    val encodeKey = payloadKeyOf(payload)
    // 口令和「它是哪一份歌单算出来的」成对存（6.2）。
    // 原来 `var encoded by remember { mutableStateOf(null) }` 不带 key：换歌单时旧的口令还挂在
    // 界面上、`ready` 仍然为真，用户立刻点复制就把 A 歌单的口令当成 B 发出去了。
    // 同一条 key 重算时仍然保留上一份结果（R14 ②：这就是「不闪」的来源），只有 key 变了才算没就绪。
    var encodedPair by remember {
        mutableStateOf<Pair<String?, PlaylistShare.EncodeResult?>>(null to null)
    }
    // 下游一律读这个：key 对不上（换成别的歌单、正在重算）就当没有结果
    val encoded = encodedPair.second.takeIf { encodedPair.first == encodeKey }
    val currentPayload = rememberUpdatedState(payload)
    LaunchedEffect(encodeKey) {
        val p = currentPayload.value
        if (p == null) { encodedPair = encodeKey to null; return@LaunchedEffect }
        // 压缩是纯 CPU 活：一千首也要几毫秒，留在 Main 上就是弹层"卡一下才出现"
        val next = withContext(Dispatchers.Default) { PlaylistShare.encode(p) }
        // 算完再核对目标还是不是原来那份：中途换了歌单就把这次结果丢掉。
        // 6.2 修的是这条核对本身 —— 原来比的是本协程自己捕获的 payload，恒等，等于没防；
        // 现在比 rememberUpdatedState 的实时值。
        if (encodeKey == payloadKeyOf(currentPayload.value)) {
            encodedPair = encodeKey to next
        }
    }
    // 清单展开态与分卷结果都属于"这一个目标"，换歌单必须重来，但不能被每帧重组重置
    var showTracks by remember(encodeKey) { mutableStateOf(false) }
    var partCodes by remember(encodeKey) { mutableStateOf<List<String>>(emptyList()) }

    // ===== 短码（6.6.6 / 6.6.7）=====
    //
    // 用户原话：「分享歌单那一串特别长，歌越多越长，微信里别人根本复制不完」。
    // 本地口令天生就长（每首歌都要带曲名+歌手），唯一的解法是只发一个索引，
    // 正文放服务器。这里在算出完整口令后顺手传一次，拿到短码就优先展示。
    //
    // 上传失败 = 保持完整口令，**不影响分享**。断网时仍然能分享，只是长一点。
    //
    // 6.6.7：失败不再静默 —— 用户拿着一条几千字符的长口令来兴师问罪，
    // 界面上一字不提是不行的。现在失败时明确写原因（服务器没更新 / 断网），
    // 并给一颗「重新生成短码」的重试按钮：比如用户先把服务端部署好了，
    // 回来点一下就能换短码，不用关掉弹层重来。
    var shortPair by remember { mutableStateOf<Pair<String?, String?>?>(null) }
    var shortRetry by remember { mutableStateOf(0) }
    var shortTried by remember { mutableStateOf(false) }
    val okCode = (encoded as? PlaylistShare.EncodeResult.Ok)?.code
    LaunchedEffect(encodeKey, okCode, shortRetry) {
        if (okCode.isNullOrBlank()) return@LaunchedEffect
        val p = currentPayload.value ?: return@LaunchedEffect
        shortTried = true
        val got = PlaylistShare.uploadShort(okCode, p.displayName, p.tracks.size)
        if (encodeKey == payloadKeyOf(currentPayload.value)) {
            shortPair = encodeKey to got
        }
    }
    val shortCode = shortPair?.takeIf { it.first == encodeKey }?.second
    val displayCode = shortCode?.let { PlaylistShare.SHORT_PREFIX + it } ?: okCode

    // ===== 导入 =====
    var pasteText by remember { mutableStateOf(initialPaste) }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(PlaylistImport.Progress(0, 0, "")) }
    var resultText by remember { mutableStateOf("") }
    var resultOk by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf<List<ShareTrack>>(emptyList()) }

    Box(Modifier.fillMaxWidth().fillMaxHeight().imePadding()) {
        JunSheet(
            colors = colors,
            onDismiss = onDismiss,
            drag = drag,
            panelModifier = Modifier.fillMaxHeight(if (payload == null) 0.62f else 0.86f),
            contentModifier = Modifier
                .verticalScroll(scroll)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 10.dp, bottom = 24.dp)
        ) {
            SheetDragHandle(drag, onDismiss, colors.card)
            Spacer(Modifier.height(14.dp))
            Text(
                if (payload == null) "导入歌单口令" else "分享歌单",
                color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(4.dp))
            if (payload != null) {
                Text(
                    "${payload.displayName} · ${payload.tracks.size} 首",
                    color = colors.subText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    "粘贴对方发来的 JM 口令，会自动建一个新歌单",
                    color = colors.subText, fontSize = 12.sp, lineHeight = 17.sp
                )
            }
            Spacer(Modifier.height(16.dp))

            // ================= 导出区 =================
            //
            // R14：**版式固定 + 按钮常驻**。原来只有算出口令那一刻才把口令框和
            // 「复制 / 系统分享」画出来，之前那一帧这里只有一行"正在生成口令…"：
            // 整块内容高度突然涨一截，用户的手指正落在那片区域就会点错（"误触"）。
            // 现在口令区给固定最小高度，两颗按钮一直在、未就绪时置灰不可点，
            // 观感上从"内容蹦出来"变成"按钮由灰变亮"。
            if (payload != null) {
                val ok = encoded as? PlaylistShare.EncodeResult.Ok
                val fail = encoded as? PlaylistShare.EncodeResult.Fail
                val tooLong = encoded as? PlaylistShare.EncodeResult.TooLong
                val ready = ok != null

                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp)
                        .clip(JunShape.item)
                        .background(fieldBg)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    when {
                        encoded == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp), color = colors.primary, strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(9.dp))
                            Text("正在生成口令…", color = colors.subText, fontSize = 11.sp)
                        }

                        fail != null -> Text(
                            fail.message, color = errorColor, fontSize = 12.sp, lineHeight = 17.sp
                        )

                        tooLong != null -> Text(
                            // 只报"为什么没给口令"，具体怎么分卷交给下面 TooLongBlock 说，
                            // 两处都讲就成两遍同样的话
                            tooLong.reason,
                            color = errorColor, fontSize = 12.sp, lineHeight = 17.sp
                        )

                        ok != null -> Text(
                            displayCode ?: ok.code,
                            color = colors.text.copy(alpha = 0.8f),
                            fontSize = if (shortCode != null) 15.sp else 10.sp,
                            lineHeight = if (shortCode != null) 20.sp else 14.sp,
                            fontWeight = if (shortCode != null) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 计数行也常驻：没有结果时给一句占位说明，避免下面整体上下跳
                Box(Modifier.fillMaxWidth().heightIn(min = 18.dp)) {
                    if (ok != null) {
                        if (shortCode != null) {
                            // 短码成功：不再报原始字符数，免得用户被那个几千吓到
                            Text(
                                "短码 · ${ok.trackCount} 首 · 30 天内有效，对方联网即可导入",
                                color = colors.subText,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        } else {
                            // 6.6.7：短码失败必须说人话，不能默默甩一条长口令
                            Text(
                                "完整口令 · ${ok.trackCount} 首。短码没生成出来：" +
                                    if (shortTried) "连不上分享服务器（需已部署 6.6.6+ 服务端），检查网络后点下方按钮重试"
                                    else "正在上传…",
                                color = colors.dangerText,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
                // 重试短码：只在"完整口令已就绪但短码没拿到"时出现
                if (ok != null && shortCode == null && shortTried) {
                    Spacer(Modifier.height(6.dp))
                    ShareButton(
                        text = "重新生成短码",
                        colors = colors,
                        accent = accent,
                        full = true
                    ) { shortRetry += 1 }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 复制口令：Android 10 起后台读剪贴板会被系统拦掉，
                    // 但**写**剪贴板没有这个限制，所以这一颗在任何版本上都是最可靠的路径。
                    ShareButton(
                        text = "复制口令",
                        colors = colors,
                        accent = accent,
                        filled = true,
                        enabled = ready,
                        clickWhenDisabled = true,
                        modifier = Modifier.weight(1f)
                    ) {
                        val code = displayCode
                        if (code.isNullOrBlank()) {
                            onNotice("口令还在生成，稍等一下再复制")
                        } else {
                            val copied = runCatching {
                                clipboard.setText(AnnotatedString(code)); true
                            }.getOrDefault(false)
                            onNotice(
                                when {
                                    !copied -> "复制失败，请长按上面这段文字手动复制"
                                    shortCode != null -> "短码已复制，直接发微信就行"
                                    else -> "口令已复制，粘贴给好友就能导入"
                                }
                            )
                        }
                    }
                    ShareButton(
                        text = "系统分享",
                        colors = colors,
                        accent = accent,
                        enabled = ready,
                        clickWhenDisabled = true,
                        modifier = Modifier.weight(1f)
                    ) {
                        val code = displayCode
                        val p = payload
                        if (code.isNullOrBlank() || p == null) {
                            onNotice("口令还在生成，稍等一下再分享")
                        } else {
                            onSystemShare(code, PlaylistShare.shareText(p, code))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                ShareButton(
                    text = if (showTracks) "收起曲目清单" else "预览曲目",
                    colors = colors,
                    accent = accent,
                    enabled = ready,
                    full = true
                ) { showTracks = !showTracks }

                // 口令太长：分卷那颗按钮只在 TooLong 时出现，出现位置就在上面这块下面，
                // 不影响已经点不到的两颗主按钮。
                if (tooLong != null) {
                    Spacer(Modifier.height(10.dp))
                    TooLongBlock(
                        res = tooLong, colors = colors, accent = accent,
                        payload = payload,
                        partCodes = partCodes,
                        onMakeParts = {
                            // 分卷是纯计算，但一千首要压 M 次，仍走协程避免这一帧卡住
                            scope.launch {
                                partCodes = withContext(Dispatchers.Default) {
                                    PlaylistShare.encodeParts(payload, tooLong.perPart)
                                }
                            }
                        },
                        onCopyPart = { code ->
                            runCatching { clipboard.setText(AnnotatedString(code)) }
                            onNotice("这一卷已复制，发出去后再回来复制下一卷")
                        }
                    )
                }

                if (showTracks && ok != null) {
                    Spacer(Modifier.height(10.dp))
                    payload.tracks.take(30).forEachIndexed { i, t ->
                        Text(
                            "${i + 1}. ${t.title} — ${t.artist.ifBlank { "未知歌手" }}",
                            color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (payload.tracks.size > 30) {
                        Text(
                            "…共 ${payload.tracks.size} 首",
                            color = colors.subText, fontSize = 11.sp
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            // ================= 导入区 =================
            Text("我要导入", color = onSheet, fontSize = 14.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .clip(JunShape.item)
                    .background(fieldBg)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                BasicTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it; resultText = "" },
                    enabled = !importing,
                    textStyle = TextStyle(
                        fontFamily = LocalJunUiFamily.current,
                        color = colors.text,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.TopStart) {
                            if (pasteText.isEmpty()) {
                                Text(
                                    "把 JM 开头的整段口令贴在这里（带上周围的分享文案也没关系）",
                                    color = colors.subText, fontSize = 12.sp, lineHeight = 16.sp
                                )
                            }
                            inner()
                        }
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShareButton(
                    text = "粘贴",
                    colors = colors,
                    accent = accent,
                    enabled = !importing,
                    modifier = Modifier.weight(0.32f)
                ) {
                    val clip = runCatching { clipboard.getText()?.text?.trim().orEmpty() }
                        .getOrDefault("")
                    if (clip.isNotBlank()) {
                        pasteText = clip
                        resultText = ""
                    } else {
                        onNotice("剪贴板里是空的，先复制口令再回来")
                    }
                }
                ShareButton(
                    text = if (importing) "匹配中…" else "开始导入",
                    colors = colors,
                    accent = accent,
                    filled = true,
                    enabled = !importing,
                    modifier = Modifier.weight(0.68f)
                ) {
                    val text = pasteText.trim()
                    if (text.isEmpty()) {
                        resultOk = false
                        resultText = "先把口令贴上来（Jun 开头那一整段，或 Jun:xxxxxxxx 短码）"
                    } else {
                        keyboard?.hide()
                        importing = true
                        resultText = ""
                        missing = emptyList()
                        progress = PlaylistImport.Progress(0, 0, "")
                        scope.launch {
                            // 6.6.6：短码要先换回完整口令再走原来的解码流程。
                            // 换不回来（过期 / 断网 / 服务器没这个接口）就说清楚，
                            // 不要让用户对着"没认出这条口令"发呆。
                            val source = if (PlaylistShare.looksLikeShortCode(text)) {
                                val full = PlaylistShare.fetchShort(text)
                                if (full.isNullOrBlank()) {
                                    importing = false
                                    resultOk = false
                                    resultText = "短码取不回来：可能已过期（30 天），或者当前网络连不上服务器。让对方重新分享一次，或用完整口令导入。"
                                    onNotice("短码已失效或网络不通")
                                    return@launch
                                }
                                full
                            } else {
                                text
                            }
                            val out = runCatching {
                                onImport(source) { p ->
                                    // 回调可能在 IO 线程发出：写 Compose state 必须回主线程
                                    scope.launch { progress = p }
                                }
                            }.getOrElse { e ->
                                ShareImportOutcome(
                                    message = "导入失败：${e.message ?: "出错了"}",
                                    imported = 0,
                                    total = 0,
                                    missing = emptyList(),
                                    ok = false
                                )
                            }
                            importing = false
                            resultOk = out.ok
                            resultText = out.message
                            missing = out.missing
                            if (out.ok) pasteText = ""
                            onNotice(out.message)
                        }
                    }
                }
            }

            if (importing) {
                Spacer(Modifier.height(12.dp))
                val total = progress.total
                val frac = if (total > 0) progress.done.toFloat() / total else 0f
                val animated by animateFloatAsState(frac, JunMotion.move(), label = "shareImportProgress")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp), color = colors.primary, strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        if (total > 0) "第 ${progress.done}/$total 首匹配中${progress.current.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
                        else "正在解析口令…",
                        color = colors.primary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(JunShape.chip)
                        .background(colors.progressTrack)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(animatedFraction(animated))
                            .height(4.dp)
                            .clip(JunShape.chip)
                            .background(colors.primary)
                    )
                }
            } else if (resultText.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    resultText,
                    color = if (resultOk) colors.primary else errorColor,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }

            if (missing.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "这几首没能对上，可能是已下架或只在别的平台：",
                    color = colors.subText, fontSize = 11.sp
                )
                Spacer(Modifier.height(4.dp))
                missing.take(12).forEach { t ->
                    Text(
                        "· ${t.title} — ${t.artist.ifBlank { "未知歌手" }}",
                        color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (missing.size > 12) {
                    Text("…共 ${missing.size} 首", color = colors.subText, fontSize = 11.sp)
                }
            }
        }
    }
}

/** 口令太长时的分卷方案：先讲清为什么不给口令，再给一颗真的能用的按钮 */
@Composable
private fun TooLongBlock(
    res: PlaylistShare.EncodeResult.TooLong,
    colors: JunColors,
    accent: Color,
    payload: SharePayload,
    partCodes: List<String>,
    onMakeParts: () -> Unit,
    onCopyPart: (String) -> Unit
) {
    val fieldBg = if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC)
    Text(
        res.reason + "。建议分 ${maxOf(res.partCount, 1)} 卷发，每卷 ${res.perPart} 首左右，" +
            "对方每卷各导入成一个歌单，最后手动合并。",
        color = colors.dangerText, fontSize = 12.sp, lineHeight = 17.sp
    )
    Spacer(Modifier.height(10.dp))
    ShareButton(
        text = if (partCodes.isEmpty()) "生成分卷口令" else "重新生成分卷口令",
        colors = colors,
        accent = accent,
        filled = true,
        full = true
    ) { onMakeParts() }
    if (partCodes.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        partCodes.forEachIndexed { i, code ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(JunShape.item)
                        .background(fieldBg)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text(
                        code,
                        color = colors.text.copy(alpha = 0.78f),
                        fontSize = 9.sp,
                        lineHeight = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .junPressable(feedback = PressFeedback.CLICK) { onCopyPart(code) }
                        .clip(JunShape.chip)
                        .background(colors.card)
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        "第 ${i + 1}/${partCodes.size} 卷",
                        color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "共 ${payload.tracks.size} 首，分成 ${partCodes.size} 条：一条一条复制发送，别拼在一条消息里。",
            color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp
        )
    }
}

/**
 * 弹层里的一颗按钮：造型照 [PlaylistSheet] 里那批（junPressable + clip + background），
 * 不引 material3 Button：它是实色容器，液态玻璃下会和其他元件不成一套材质。
 */
@Composable
private fun ShareButton(
    text: String,
    colors: JunColors,
    accent: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    full: Boolean = false,
    enabled: Boolean = true,
    /**
     * 6.2：置灰但仍然可点。`clickable(enabled = false)` 会把点击整个吞掉，
     * 于是 onClick 里那句「口令还在生成，稍等一下再复制」永远走不到 ——
     * 用户看到的是「按钮灰着、按下去没反应、也不告诉为什么」，
     * 和本轮在批量下载那颗钮上一起修掉的是同一个毛病。
     * 外观仍按 enabled 置灰，只是把手势放出来让提示说话。
     */
    clickWhenDisabled: Boolean = false,
    onClick: () -> Unit
) {
    val widthMod = if (full) Modifier.fillMaxWidth() else Modifier
    Box(
        widthMod
            .then(modifier)
            .height(42.dp)
            .junPressable(
                feedback = if (filled) PressFeedback.CLICK else PressFeedback.TAP,
                enabled = enabled || clickWhenDisabled
            ) { onClick() }
            .clip(JunShape.item)
            .junSurface(
                level = GlassLevel.ROW,
                colors = colors,
                shape = JunShape.item,
                solid = when {
                    !enabled -> colors.card.copy(alpha = 0.6f)
                    filled -> accent
                    else -> colors.card
                }
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = when {
                !enabled -> colors.subText
                filled -> accent.readableTextOn()
                else -> accent
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 进度条宽度：0f~1f，外面已经用弹簧插值过，这里只做一次钳制 */
private fun animatedFraction(v: Float): Float = v.coerceIn(0f, 1f)

/**
 * 弹层"目标是哪一份歌单"的**值**判据（R14）。
 *
 * 不能拿 SharePayload 实例当 key：payloadOf() 里写了 System.currentTimeMillis()，
 * 每次调用 createdAt 都不一样，两个内容完全相同的 payload 永不相等。
 * 于是调用方只要漏一次 remember，编码就会每帧重跑 —— 界面表现为反复闪。
 */
private fun payloadKeyOf(p: SharePayload?): String? =
    p?.let {
        // 只算「名字 + 曲数 + 分卷」是不够的（6.2）：同名同数量地换掉一首歌、调一次顺序，
        // key 一模一样 → LaunchedEffect 不重算 → 复制到的是**上一版歌单**的口令，
        // 而且用户完全看不出来。首尾 id 做指纹，内容一动 key 就动。
        // 6.2 再补一刀：只看首尾还不够 —— 换掉中间那首歌、把中间两段调个顺序，
        // 首尾不变就 key 不变，照样复制到上一版。这里按顺序把每一首都混进去：
        // 一次 O(n) 的整数滚动哈希（String.hashCode 由规范定义、跨进程稳定），
        // 不做 joinToString，几百首也就几百次整数运算，老机型上不构成负担。
        val head = it.tracks.firstOrNull()?.id.orEmpty()
        val tail = it.tracks.lastOrNull()?.id.orEmpty()
        var mixed = 0
        for (t in it.tracks) mixed = mixed * 31 + t.id.hashCode()
        it.name + "|" + it.tracks.size + "|" + head + "/" + tail + "|#" + mixed + "|" + it.part + "/" + it.parts
    }
