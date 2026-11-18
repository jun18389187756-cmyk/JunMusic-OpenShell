package com.jun.musiccloud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import com.jun.musiccloud.BuildConfig
import com.jun.musiccloud.data.FeedbackApi
import com.jun.musiccloud.data.Telemetry
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.LocalJunUiFamily
import com.jun.musiccloud.ui.theme.readableOn
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 意见反馈弹层。
 *
 * 为什么单独做一个弹层而不是"跳邮箱"：
 * 让用户切到邮箱 App、再写收件人、再描述机型版本 —— 每一步都会流失一半人。
 * 这里只有一个输入框，机型和版本自动带上，写完一个按钮就发出去了。
 *
 * 提交后落到官网站点的 `feedback` 表，总后台「反馈管理」能看见、能回复、能导 CSV。
 * 登录用户会带上 userId / nickname，后台知道是谁提的；未登录也能提，只是标成游客。
 *
 * 布局分三段：标题固定、中间可滚、发送按钮固定。
 *
 * 6.4.1 修的三件事（用户原话「输入框有的时候根本弹不出来，点一下没反应」）：
 *   1. **点下半截没反应**：原来 BasicTextField 只占一行高，外面那个 132dp 的框
 *      下面一大片是"死区"。现在输入框撑满整块区域（heightIn），并且整块都可以点。
 *   2. **键盘不弹**：原来 imePadding 挂在内容内边距上，面板本身不动，
 *      键盘一起来就把输入框盖住了；现在按歌单/导入弹层的做法，最外层
 *      `Box(fillMaxSize().imePadding())` 让整块面板随键盘上移，
 *      聚焦后再显式 show() 一次兜底（部分 ROM 不认 Compose 的自动请求）。
 *   3. **写完看不见发送键**：内容区带 weight(1f) + 可滚，按钮钉在底部，
 *      聚焦时把输入框滚进可视区。
 *
 * 失败**不清空正文**：用户最在意的是"我写的这段会不会白写"。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun FeedbackSheet(
    state: PlayerUiState,
    colors: JunColors,
    /** 用户真的翻到「我的反馈」了：外层据此把抽屉上的红点清掉 */
    onSeen: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val drag = rememberSheetDragState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    /** 0 = 写反馈，1 = 我的反馈 */
    var tab by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf(FeedbackApi.Category.PLAYBACK) }
    var content by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }

    // 历史反馈：null = 还没拉过，空列表 = 确实没有
    var mine by remember { mutableStateOf<List<FeedbackApi.Item>?>(null) }
    var mineLoading by remember { mutableStateOf(false) }

    val inputFocus = remember { FocusRequester() }
    val inputBring = remember { BringIntoViewRequester() }
    val deviceId = remember { Telemetry.deviceId(context) }

    val loggedIn = state.accountLoggedIn && state.accountUserId > 0L
    val name = state.accountName.ifBlank { "游客" }
    val count = content.length
    val unread = state.feedbackUnread

    suspend fun loadMine() {
        mineLoading = true
        mine = FeedbackApi.fetchMine(state.accountUserId, deviceId)
        mineLoading = false
    }

    // 打开面板就顺手拉一次：用户进来第一眼就能看到"上次反馈有没有人回"。
    LaunchedEffect(Unit) { loadMine() }

    // 切到「我的反馈」才算看过：红点清掉、本地记一个时间戳。
    // 注意不能"打开面板就清"——用户可能只是来写新反馈，还没看回复。
    LaunchedEffect(tab) {
        if (tab == 1) {
            FeedbackApi.markSeen(context, System.currentTimeMillis())
            onSeen()
        }
    }

    // imePadding 放最外层：让整个面板（含底部按钮）随键盘一起上移。
    // 挂在 contentModifier 上只会给内容加内边距，面板还在原地，输入框会被键盘压住。
    Box(Modifier.fillMaxSize().imePadding()) {
        JunSheet(
            colors = colors,
            onDismiss = onDismiss,
            drag = drag,
            panelModifier = Modifier.fillMaxHeight(0.92f),
            contentModifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
        ) {
            SheetDragHandle(drag, onDismiss, colors.card)
            Spacer(Modifier.height(8.dp))

            if (done) {
                FeedbackDonePane(colors = colors, onDone = onDismiss)
                return@JunSheet
            }

            // ---- 标题 + 分段切换（固定） ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("意见反馈", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                FeedbackTab(
                    label = "写反馈",
                    active = tab == 0,
                    badge = 0,
                    colors = colors
                ) { tab = 0 }
                Spacer(Modifier.width(6.dp))
                FeedbackTab(
                    label = "我的反馈",
                    active = tab == 1,
                    badge = unread,
                    colors = colors
                ) {
                    tab = 1
                    Haptics.click()
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                if (tab == 0) {
                    if (loggedIn) "以「$name」的身份提交 · 机型版本自动带上"
                    else "未登录也能提 · 留个联系方式方便回你"
                } else {
                    "你在这台手机上提交过的反馈，官方回复会显示在下面"
                },
                color = colors.subText, fontSize = 11.sp
            )
            Spacer(Modifier.height(12.dp))

            if (tab == 0) {
                WriteFeedbackPane(
                    modifier = Modifier.weight(1f),
                    colors = colors,
                    content = content,
                    onContentChange = {
                        if (it.length <= FeedbackApi.MAX_LENGTH) {
                            content = it
                            if (error.isNotBlank()) error = ""
                        }
                    },
                    contact = contact,
                    onContactChange = { if (it.length <= 60) contact = it },
                    category = category,
                    onCategoryChange = { category = it },
                    inputFocus = inputFocus,
                    inputBring = inputBring,
                    keyboard = keyboard,
                    count = count
                )

                if (error.isNotBlank()) {
                    Text(error, color = FEEDBACK_ERROR, fontSize = 11.sp, lineHeight = 16.sp)
                    Spacer(Modifier.height(6.dp))
                }
                FeedbackPrimaryButton(
                    if (sending) "发送中…" else "发送反馈",
                    sending = sending,
                    enabled = !sending,
                    colors = colors
                ) {
                    if (sending) return@FeedbackPrimaryButton
                    if (content.trim().length < FeedbackApi.MIN_LENGTH) {
                        error = "再多写几个字吧，至少 ${FeedbackApi.MIN_LENGTH} 个字"
                        return@FeedbackPrimaryButton
                    }
                    Haptics.click()
                    keyboard?.hide()
                    sending = true
                    error = ""
                    scope.launch {
                        val r = FeedbackApi.submit(
                            content = content,
                            contact = contact,
                            category = category,
                            userId = if (loggedIn) state.accountUserId else 0L,
                            nickname = if (loggedIn) state.accountName else "",
                            appVersion = BuildConfig.VERSION_NAME,
                            deviceId = deviceId
                        )
                        sending = false
                        when (r) {
                            is FeedbackApi.Result.Ok -> {
                                done = true
                                // 刚提交的这条要立刻出现在「我的反馈」里，不用等下次打开
                                content = ""
                                contact = ""
                                loadMine()
                            }
                            // 失败只报错不动正文：用户写的字比什么都重要
                            is FeedbackApi.Result.Fail -> error = r.message
                        }
                    }
                }
            } else {
                HistoryPane(
                    modifier = Modifier.weight(1f),
                    colors = colors,
                    items = mine,
                    loading = mineLoading,
                    onRetry = { scope.launch { loadMine() } },
                    onWrite = { tab = 0 }
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/* ============================================================
   写反馈
   ============================================================ */

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WriteFeedbackPane(
    modifier: Modifier,
    colors: JunColors,
    content: String,
    onContentChange: (String) -> Unit,
    contact: String,
    onContactChange: (String) -> Unit,
    category: FeedbackApi.Category,
    onCategoryChange: (FeedbackApi.Category) -> Unit,
    inputFocus: FocusRequester,
    inputBring: BringIntoViewRequester,
    keyboard: androidx.compose.ui.platform.SoftwareKeyboardController?,
    count: Int
) {
    // 这个面板自己也要一个作用域：onFocusChanged 里要把输入框滚进可视区，
    // 而 bringIntoView() 是挂起函数（见下方注释）
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        // 分类
        Text("哪方面的问题", color = colors.subText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FeedbackApi.Category.entries.forEach { c ->
                val active = c == category
                Box(
                    Modifier
                        .junPressableRow(scaleDown = 0.94f) {
                            Haptics.click()
                            onCategoryChange(c)
                        }
                        .clip(JunShape.chip)
                        .background(if (active) colors.primary else colors.card.copy(alpha = 0.6f))
                        .padding(horizontal = 13.dp, vertical = 7.dp)
                ) {
                    Text(
                        c.label,
                        color = if (active) Color.White.readableOn(colors.primary) else colors.subText,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // 正文
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("说具体一点", color = colors.subText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "$count/${FeedbackApi.MAX_LENGTH}",
                color = if (count > FeedbackApi.MAX_LENGTH - 100) FEEDBACK_ERROR
                else colors.subText.copy(alpha = 0.55f),
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(7.dp))

        // 输入区：整块可点 → 聚焦 → 显式要键盘 → 滚进可视区。
        // 「点一下没反应」的根因就是这里：以前只有文字那一行能被点到。
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 148.dp)
                .clip(JunShape.item)
                .background(colors.card.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    inputFocus.requestFocus()
                    keyboard?.show()
                }
                .bringIntoViewRequester(inputBring)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (content.isEmpty()) {
                Text(
                    "比如：某个歌单导进来少了几首 / 播到一半停了 / 哪里点着别扭。\n" +
                        "写清「听了什么、点了什么、出现什么」，最好定位。",
                    color = colors.subText.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
            BasicTextField(
                value = content,
                onValueChange = onContentChange,
                textStyle = TextStyle(
                    fontFamily = LocalJunUiFamily.current,
                    color = colors.text,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                ),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                modifier = Modifier
                    .fillMaxWidth()
                    // 关键：撑满 148-24=124dp 的可写区域。
                    // 只写 fillMaxWidth 的话文本域只有一行高，下面全是点不到的死区。
                    .heightIn(min = 124.dp)
                    .focusRequester(inputFocus)
                    .onFocusChanged {
                        if (it.isFocused) {
                            keyboard?.show()
                            // bringIntoView 是挂起函数：onFocusChanged 是普通回调，
                            // 必须丢回协程里执行，否则编译期就会报
                            // "Suspend function should be called only from a coroutine"
                            scope.launch { inputBring.bringIntoView() }
                        }
                    }
            )
        }
        Spacer(Modifier.height(14.dp))

        // 联系方式
        Text("怎么联系你（选填）", color = colors.subText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(JunShape.item)
                .background(colors.card.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    inputFocus.requestFocus()
                    keyboard?.show()
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (contact.isEmpty()) {
                    Text("邮箱 / QQ / 微信", color = colors.subText.copy(alpha = 0.7f), fontSize = 13.sp)
                }
                BasicTextField(
                    value = contact,
                    onValueChange = onContactChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = LocalJunUiFamily.current,
                        color = colors.text,
                        fontSize = 14.sp
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "提交后直接进总后台，不带任何歌单或听歌记录。机型「${FeedbackApi.deviceLabel()}」和版本 v${BuildConfig.VERSION_NAME} 会一起带上，方便定位。",
            color = colors.subText.copy(alpha = 0.6f),
            fontSize = 10.sp,
            lineHeight = 15.sp
        )
        Spacer(Modifier.height(6.dp))
    }
}

/* ============================================================
   我的反馈
   ============================================================ */

@Composable
private fun HistoryPane(
    modifier: Modifier,
    colors: JunColors,
    items: List<FeedbackApi.Item>?,
    loading: Boolean,
    onRetry: () -> Unit,
    onWrite: () -> Unit
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        when {
            loading && items.isNullOrEmpty() -> {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.primary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
            items.isNullOrEmpty() -> {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有提交过反馈", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "遇到问题随手写两句，处理进度会显示在这里",
                            color = colors.subText, fontSize = 11.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        Row {
                            Box(
                                Modifier
                                    .junPressableRow(scaleDown = 0.95f) { onWrite() }
                                    .clip(JunShape.chip)
                                    .background(colors.primary)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text("去写一条", color = Color.White.readableOn(colors.primary), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .junPressableRow(scaleDown = 0.95f) { onRetry() }
                                    .clip(JunShape.chip)
                                    .background(colors.card.copy(alpha = 0.6f))
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text("刷新", color = colors.subText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            else -> {
                items.forEach { item ->
                    FeedbackHistoryCard(item, colors)
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "只显示这台设备提交过的记录。换手机后历史不会跟过来。",
                    color = colors.subText.copy(alpha = 0.55f), fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun FeedbackHistoryCard(item: FeedbackApi.Item, colors: JunColors) {
    val accent = when {
        item.hasReply -> colors.primary
        item.status == "doing" -> FEEDBACK_WARN
        else -> colors.subText
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(JunShape.item)
            .background(colors.card.copy(alpha = 0.5f))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(JunShape.chip)
                    .background(accent.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(item.statusLabel, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(7.dp))
            Text(
                FeedbackApi.Category.entries.firstOrNull { it.key == item.category }?.label ?: "其他",
                color = colors.subText, fontSize = 10.sp
            )
            Spacer(Modifier.weight(1f))
            Text(fmtFeedbackTime(item.createdAt), color = colors.subText.copy(alpha = 0.6f), fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(item.content, color = colors.text, fontSize = 13.sp, lineHeight = 19.sp)

        if (item.hasReply) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(JunShape.item)
                    .background(colors.primary.copy(alpha = 0.10f))
                    .padding(11.dp)
            ) {
                Text("官方回复", color = colors.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(item.reply, color = colors.text, fontSize = 12.sp, lineHeight = 18.sp)
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                if (item.status == "doing") "已经有人在看了，处理完会在这里回你" else "已收到，处理完会在这里回你",
                color = colors.subText.copy(alpha = 0.7f), fontSize = 10.sp
            )
        }
    }
}

/* ============================================================
   公共小件
   ============================================================ */

@Composable
private fun FeedbackTab(
    label: String,
    active: Boolean,
    badge: Int,
    colors: JunColors,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .junPressableRow(scaleDown = 0.94f, onClick = onClick)
            .clip(JunShape.chip)
            .background(if (active) colors.primary else colors.card.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = if (active) Color.White.readableOn(colors.primary) else colors.subText,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
            )
            if (badge > 0) {
                Spacer(Modifier.width(5.dp))
                Box(
                    Modifier
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(FEEDBACK_ERROR),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (badge > 9) "9+" else badge.toString(),
                        color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.FeedbackDonePane(colors: JunColors, onDone: () -> Unit) {
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text("✓", color = colors.primary, fontSize = 17.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("收到啦", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(2.dp))
            Text(
                "已经进后台了，会认真看。回复会出现在「我的反馈」里。",
                color = colors.subText, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
    }
    Spacer(Modifier.weight(1f))
    FeedbackPrimaryButton("完成", sending = false, colors = colors) { onDone() }
    Spacer(Modifier.height(10.dp))
}

private val FEEDBACK_ERROR = Color(0xFFFF5B6E)
private val FEEDBACK_WARN = Color(0xFFFFA726)

/** 反馈列表里的时间：只到分钟，年份省略（真跨年了再加） */
private fun fmtFeedbackTime(ms: Long): String {
    if (ms <= 0L) return ""
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val mm = c.get(Calendar.MONTH) + 1
    val dd = c.get(Calendar.DAY_OF_MONTH)
    val hh = c.get(Calendar.HOUR_OF_DAY)
    val mi = c.get(Calendar.MINUTE)
    return "${mm}月${dd}日 ${hh}:${"%02d".format(mi)}"
}

/** 反馈弹层的主按钮：实色主色，和「我的」抽屉里那颗入口同一个视觉重量 */
@Composable
private fun FeedbackPrimaryButton(
    label: String,
    sending: Boolean,
    enabled: Boolean = true,
    colors: JunColors,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .junPressable(feedback = PressFeedback.NONE) { if (enabled) onClick() }
            .clip(JunShape.item)
            .background(if (enabled) colors.primary else colors.primary.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sending) {
                CircularProgressIndicator(
                    color = Color.White.readableOn(colors.primary),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(
                label,
                color = Color.White.readableOn(colors.primary),
                fontSize = 15.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}
