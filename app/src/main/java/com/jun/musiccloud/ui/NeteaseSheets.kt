package com.jun.musiccloud.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.data.NeteaseApi
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.LocalJunUiFamily
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.readableOn
import com.jun.musiccloud.ui.theme.readableTextOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 网易云账号登录弹层（第二个登录，可选）。
 *
 * 与账号弹层 / 歌单弹层同构（[JunSheet] + 拖把手），风格完全一致。
 * 提供三种登录方式：扫码登录 / 手机号登录（密码或验证码）/ 邮箱登录。
 * 不登录也能正常使用 App，仅评论等需要账号的功能在触发时会提示登录。
 */
@Composable
internal fun NeteaseLoginSheet(
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onLoginPhone: (phone: String, password: String) -> Unit,
    onLoginPhoneCaptcha: (phone: String, captcha: String) -> Unit,
    onLoginEmail: (email: String, password: String) -> Unit,
    onSendCaptcha: suspend (phone: String) -> String,
    onClearError: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val drag = rememberSheetDragState()
    // 登录方式：0 扫码 / 1 手机号 / 2 邮箱
    var tab by remember { mutableStateOf(0) }

    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        panelModifier = Modifier.fillMaxWidth(),
        contentModifier = Modifier
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 22.dp)
    ) {
        SheetDragHandle(drag, onDismiss, colors.card)
        Spacer(Modifier.height(12.dp))

        if (state.neteaseLoggedIn) {
            Text("网易云账号", color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SongArtwork(
                    filePath = state.neteaseAvatar,
                    seedId = state.neteaseUserId.hashCode().toLong(),
                    artworkSize = 52.dp,
                    isDark = colors.isDark
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        state.neteaseName.ifBlank { "已登录" },
                        color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("已登录网易云账号", color = colors.subText, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "网易云账号用于查看 / 发表评论等；与 App 账号相互独立，不登录也能正常使用。",
                color = colors.subText, fontSize = 12.sp, lineHeight = 19.sp
            )
            Spacer(Modifier.height(16.dp))
            NeteaseButton("退出网易云账号", colors.subText, colors, Modifier.fillMaxWidth()) {
                Haptics.tap(); onLogout()
            }
        } else {
            Text("网易云账号", color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))

            // ---- 登录方式切换标签 ----
            val tabs = listOf("扫码登录", "手机号", "邮箱")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(JunShape.item)
                    .background(if (colors.isDark) Color(0x12FFFFFF) else Color(0x0D000000)),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                tabs.forEachIndexed { i, label ->
                    val active = tab == i
                    Box(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            // 和全 App 同一套按压弹簧：缩放层放在 clip/background 之前，底色才跟着缩
                            .junPressable {
                                tab = i
                                onClearError()
                            }
                            .clip(JunShape.item)
                            .background(if (active) accent else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (active) accent.readableTextOn() else colors.subText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            when (tab) {
                // ---------- 扫码登录 ----------
                0 -> QrLoginPanel(state, colors, accent, onRefresh)
                // ---------- 手机号登录 ----------
                1 -> PhoneLoginPanel(
                    state = state,
                    colors = colors,
                    accent = accent,
                    onLoginPassword = onLoginPhone,
                    onLoginCaptcha = onLoginPhoneCaptcha,
                    onSendCaptcha = onSendCaptcha,
                    onClearError = onClearError
                )
                // ---------- 邮箱登录 ----------
                2 -> EmailLoginPanel(
                    state = state,
                    colors = colors,
                    accent = accent,
                    onLogin = onLoginEmail,
                    onClearError = onClearError
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "登录是可选的：不登录也能正常收听；仅在评论等需要账号的功能会提示登录。",
                color = colors.subText, fontSize = 11.sp, lineHeight = 17.sp
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

// ==================== 登录子面板 ====================

/** 扫码登录面板：二维码 + 状态文案 + 刷新按钮 */
@Composable
private fun QrLoginPanel(
    state: PlayerUiState,
    colors: JunColors,
    accent: Color,
    onRefresh: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            "打开「网易云音乐」App 扫描二维码登录",
            color = colors.subText, fontSize = 13.sp
        )
        Spacer(Modifier.height(16.dp))
        val qr = remember(state.neteaseQrContent) {
            state.neteaseQrContent.takeIf { it.isNotBlank() }
                ?.let { QrCodes.render(it, QR_PX) }
        }
        Box(
            Modifier
                .size(QR_BOX)
                .junPressable(enabled = qr == null) { onRefresh() }
                .clip(JunShape.item)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (qr != null) {
                Image(qr, contentDescription = "网易云登录二维码", modifier = Modifier.size(QR_PX.dp))
            } else {
                CircularProgressIndicator(
                    color = Color(0xFF444444),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        val status = state.neteaseQrStatus
        Text(
            status.ifBlank { "正在获取二维码…" },
            color = if (qr == null) accent else colors.text,
            fontSize = 13.sp, fontWeight = FontWeight.Medium
        )
        if (qr == null || status.contains("过期") || status.contains("失败")) {
            Spacer(Modifier.height(10.dp))
            NeteaseButton("点击刷新二维码", accent, colors, Modifier.width(160.dp)) {
                Haptics.click(); onRefresh()
            }
        }
    }
}

/** 手机号登录面板：支持「密码登录」和「验证码登录」两种子模式 */
@Composable
private fun PhoneLoginPanel(
    state: PlayerUiState,
    colors: JunColors,
    accent: Color,
    onLoginPassword: (phone: String, password: String) -> Unit,
    onLoginCaptcha: (phone: String, captcha: String) -> Unit,
    onSendCaptcha: suspend (phone: String) -> String,
    onClearError: () -> Unit
) {
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var useCaptcha by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var captchaNotice by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val fieldBg = if (colors.isDark) Color(0x14FFFFFF) else Color(0x0F000000)
    val loading = state.neteaseCredentialLoading
    val scope = rememberCoroutineScope()

    // 验证码 60 秒倒计时
    LaunchedEffect(countdown) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // 密码 / 验证码 子切换
        Row(
            Modifier
                .fillMaxWidth()
                .clip(JunShape.item)
                .background(fieldBg),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("密码登录" to false, "验证码登录" to true).forEach { (label, valCaptcha) ->
                val active = useCaptcha == valCaptcha
                Box(
                    Modifier
                        .weight(1f)
                        .height(34.dp)
                        .junPressable { useCaptcha = valCaptcha; onClearError() }
                        .clip(JunShape.item)
                        .background(if (active) accent else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (active) accent.readableTextOn() else colors.subText,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // 手机号
        LoginField(
            value = phone,
            onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11); onClearError() },
            placeholder = "手机号",
            colors = colors,
            fieldBg = fieldBg,
            keyboardType = KeyboardType.Phone
        )
        Spacer(Modifier.height(10.dp))

        if (useCaptcha) {
            // 验证码 + 发送按钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    LoginField(
                        value = captcha,
                        onValueChange = { captcha = it.filter { c -> c.isDigit() }.take(6); onClearError() },
                        placeholder = "验证码",
                        colors = colors,
                        fieldBg = fieldBg,
                        keyboardType = KeyboardType.Number
                    )
                }
                Spacer(Modifier.width(10.dp))
                val canSend = countdown == 0 && phone.trim().length >= 6 && !loading && !sending
                Box(
                    Modifier
                        .height(46.dp)
                        .junPressable(enabled = canSend) {
                            sending = true
                            scope.launch {
                                // onSendCaptcha 返回空串表示成功，非空为具体失败原因
                                val err = try { onSendCaptcha(phone) } catch (_: Throwable) { "网络错误，请稍后重试" }
                                if (err.isBlank()) {
                                    captchaNotice = "验证码已发送，请注意查收"
                                    countdown = 60
                                } else {
                                    // 短信被网易云限流（服务器出口 IP 频率限制）时，
                                    // 提示改用扫码登录，并启动冷却避免反复点加重限流
                                    val throttled = err.contains("频繁")
                                    captchaNotice = if (throttled) "$err（短信发送被网易云限流，建议改用「扫码登录」）" else err
                                    if (throttled) countdown = 60
                                }
                                sending = false
                            }
                        }
                        .clip(JunShape.item)
                        .background(if (canSend) accent.copy(alpha = 0.18f) else colors.subText.copy(alpha = 0.15f))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when {
                            sending -> "发送中…"
                            countdown > 0 -> "${countdown}s 后重发"
                            else -> "发送验证码"
                        },
                        color = if (canSend) accent else colors.subText,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            if (captchaNotice.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(captchaNotice, color = colors.subText, fontSize = 11.sp)
            }
        } else {
            // 密码
            LoginField(
                value = password,
                onValueChange = { password = it; onClearError() },
                placeholder = "密码",
                colors = colors,
                fieldBg = fieldBg,
                keyboardType = KeyboardType.Password,
                isPassword = true
            )
        }

        Spacer(Modifier.height(14.dp))

        // 错误提示
        if (state.neteaseCredentialError.isNotBlank()) {
            Text(
                state.neteaseCredentialError,
                color = Color(0xFFFF5252), fontSize = 12.sp, lineHeight = 18.sp
            )
            Spacer(Modifier.height(10.dp))
        }

        // 登录按钮
        val canLogin = !loading && phone.trim().length >= 6 &&
            (if (useCaptcha) captcha.length >= 4 else password.isNotBlank())
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .junPressable(enabled = canLogin, feedback = PressFeedback.CLICK) {
                    if (useCaptcha) onLoginCaptcha(phone, captcha)
                    else onLoginPassword(phone, password)
                }
                .clip(JunShape.item)
                .background(if (canLogin) accent else colors.subText.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = accent.readableTextOn(), strokeWidth = 2.dp, modifier = Modifier.size(20.dp)
                )
            } else {
                Text("登录", color = accent.readableTextOn(), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 邮箱登录面板：邮箱 + 密码 */
@Composable
private fun EmailLoginPanel(
    state: PlayerUiState,
    colors: JunColors,
    accent: Color,
    onLogin: (email: String, password: String) -> Unit,
    onClearError: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val fieldBg = if (colors.isDark) Color(0x14FFFFFF) else Color(0x0F000000)
    val loading = state.neteaseCredentialLoading

    Column(Modifier.fillMaxWidth()) {
        LoginField(
            value = email,
            onValueChange = { email = it; onClearError() },
            placeholder = "邮箱地址",
            colors = colors,
            fieldBg = fieldBg,
            keyboardType = KeyboardType.Email
        )
        Spacer(Modifier.height(10.dp))
        LoginField(
            value = password,
            onValueChange = { password = it; onClearError() },
            placeholder = "密码",
            colors = colors,
            fieldBg = fieldBg,
            keyboardType = KeyboardType.Password,
            isPassword = true
        )

        Spacer(Modifier.height(14.dp))

        if (state.neteaseCredentialError.isNotBlank()) {
            Text(
                state.neteaseCredentialError,
                color = Color(0xFFFF5252), fontSize = 12.sp, lineHeight = 18.sp
            )
            Spacer(Modifier.height(10.dp))
        }

        val canLogin = !loading && email.contains("@") && password.isNotBlank()
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .junPressable(enabled = canLogin, feedback = PressFeedback.CLICK) { onLogin(email, password) }
                .clip(JunShape.item)
                .background(if (canLogin) accent else colors.subText.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = accent.readableTextOn(), strokeWidth = 2.dp, modifier = Modifier.size(20.dp)
                )
            } else {
                Text("登录", color = accent.readableTextOn(), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 统一的登录输入框：圆角底板 + 占位符 + 可切换密码掩码 */
@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    colors: JunColors,
    fieldBg: Color,
    keyboardType: KeyboardType,
    isPassword: Boolean = false
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(JunShape.item)
            .background(fieldBg)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = LocalJunUiFamily.current, color = colors.text, fontSize = 14.sp),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = colors.subText, fontSize = 14.sp)
                    }
                    inner()
                }
            }
        )
    }
}


/**
 * 歌曲评论弹层：只读列表无需登录，发表评论需要登录（未登录时给出登录入口）。
 * 与播放列表弹层同高（0.78 屏），风格与其它弹层保持一致。
 */
@Composable
internal fun CommentsSheet(
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onPost: (String) -> Unit,
    onRequireLogin: () -> Unit,
    onInputChange: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val drag = rememberSheetDragState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val loggedIn = state.neteaseLoggedIn
    val fieldBg = if (colors.isDark) Color(0x14FFFFFF) else Color(0x0F000000)

    // 触底自动加载下一页（距底部 3 条内触发）
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearBottom, state.commentsHasMore) {
        if (nearBottom && state.commentsHasMore) onLoadMore()
    }

    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        panelModifier = Modifier.fillMaxHeight(0.78f),
        contentModifier = Modifier
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp)
    ) {
        SheetDragHandle(drag, onDismiss, colors.card)
        Spacer(Modifier.height(6.dp))

        // 标题：评论 + 总数
        Row(verticalAlignment = Alignment.Bottom) {
            Text("评论", color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            if (state.commentsTotal > 0) {
                Text(
                    "${state.commentsTotal}",
                    color = colors.subText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            state.commentsTitle,
            color = colors.subText, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            if (state.commentsHot.isNotEmpty()) {
                item { CommentSectionLabel("精彩评论", colors) }
                // key 带下标：接口偶尔返回缺失 / 重复 commentId，纯 id 作 key 会撞车
                //（LazyColumn 里 key 重复会直接抛异常闪退），下标兜底保证唯一。
                itemsIndexed(state.commentsHot, key = { i, c -> "hot-$i-${c.id}" }) { _, c ->
                    CommentRow(c, colors, onSheet)
                }
            }
            item { CommentSectionLabel("最新评论", colors) }
            if (state.commentsItems.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        if (state.commentsLoading) {
                            CircularProgressIndicator(
                                color = accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Text("还没有评论，来抢沙发～", color = colors.subText, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                itemsIndexed(state.commentsItems, key = { i, c -> "c-$i-${c.id}" }) { _, c ->
                    CommentRow(c, colors, onSheet)
                }
                item {
                    Box(
                        Modifier.fillMaxWidth().height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            state.commentsLoading -> CircularProgressIndicator(
                                color = accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp)
                            )
                            state.commentsHasMore -> Text(
                                "加载更多",
                                color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .junPressable { onLoadMore() }
                                    .clip(JunShape.item)
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            else -> Text("— 没有更多评论了 —", color = colors.subText, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 操作提示（发表成功/失败/需登录）
        if (state.commentsNotice.isNotBlank()) {
            Text(state.commentsNotice, color = accent, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
        }

        // 输入区：登录后可发表；未登录给出登录入口
        if (loggedIn) {
            val focusRequester = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(JunShape.item)
                        .background(fieldBg)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            focusRequester.requestFocus()
                            keyboard?.show()
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it; if (it.isNotEmpty()) onInputChange() },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = LocalJunUiFamily.current, color = colors.text, fontSize = 14.sp),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (input.isNotBlank()) {
                                onPost(input)
                                input = ""
                                keyboard?.hide()
                            }
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { if (it.isFocused) keyboard?.show() },
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (input.isEmpty()) {
                                    Text("说点什么…", color = colors.subText, fontSize = 14.sp)
                                }
                                inner()
                            }
                        }
                    )
                }
                Spacer(Modifier.width(10.dp))
                val canSend = input.isNotBlank()
                Box(
                    Modifier
                        .height(44.dp)
                        .junPressable(enabled = canSend, feedback = PressFeedback.CLICK) {
                            onPost(input)
                            input = ""
                        }
                        .clip(JunShape.item)
                        .background(if (canSend) accent else colors.subText.copy(alpha = 0.3f))
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "发送",
                        color = accent.readableTextOn(),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .junPressable(feedback = PressFeedback.CLICK) { onRequireLogin() }
                    .clip(JunShape.item)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "登录网易云账号后可发表评论",
                    color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun CommentSectionLabel(text: String, colors: JunColors) {
    Text(
        text,
        color = colors.subText,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
    )
}

@Composable
private fun CommentRow(comment: NeteaseApi.NsComment, colors: JunColors, onSheet: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        SongArtwork(
            filePath = comment.avatar,
            seedId = comment.userId.hashCode().toLong(),
            artworkSize = 36.dp,
            isDark = colors.isDark
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                comment.nickname.ifBlank { "网易云用户" },
                color = colors.subText, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            if (comment.replyTo.isNotBlank()) {
                Text(
                    "回复 @${comment.replyTo}：",
                    color = colors.primary, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(comment.content, color = onSheet, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatCommentTime(comment.timeMs), color = colors.subText, fontSize = 11.sp)
                if (comment.likedCount > 0) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "♥ ${comment.likedCount}",
                        color = colors.subText, fontSize = 11.sp
                    )
                }
            }
        }
    }
}

/** 评论时间：毫秒时间戳统一格式化为「yyyy-MM-dd」。 */
private fun formatCommentTime(timeMs: Long): String {
    if (timeMs <= 0L) return ""
    // 部分接口返回秒级时间戳，统一归一到毫秒
    val ms = if (timeMs < 100_000_000_000L) timeMs * 1000 else timeMs
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
    }.getOrDefault("")
}

@Composable
private fun NeteaseButton(
    label: String,
    tint: Color,
    colors: JunColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .height(44.dp)
            .junPressable(feedback = PressFeedback.NONE) { onClick() }
            .clip(JunShape.item)
            .background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** 二维码尺寸常量：白色底板 + 内部码图（保留一圈静默区）。 */
private const val QR_PX = 200
private val QR_BOX = 236.dp

/**
 * 二维码生成：用 ZXing 把登录链接编码成位图（纯本地，无第三方服务）。
 * 失败返回 null，UI 侧显示占位并以点击刷新兜底。
 */
private object QrCodes {
    fun render(content: String, sizePx: Int): androidx.compose.ui.graphics.ImageBitmap? =
        runCatching {
            val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    pixels[row + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            val bmp = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888
            )
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            bmp.asImageBitmap()
        }.getOrNull()
}
