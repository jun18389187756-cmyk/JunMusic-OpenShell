package com.jun.musiccloud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.data.RemoteConfigStore
import com.jun.musiccloud.data.ServerApi
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.LocalJunUiFamily
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.readableTextOn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 客服邮箱：优先用总后台「App 配置 → 客服信息」里填的邮箱，没填才用默认值 */
internal val SUPPORT_EMAIL: String
    get() = RemoteConfigStore.customerService.email
        .ifBlank { "3611452139@qq.com" }
private val ERROR_RED = Color(0xFFFF5B6E)
/** 4.0.3 补偿会员金色：只在补偿卡片与会员标识上使用 */
private val VIP_GOLD = Color(0xFFF5C451)
private val VIP_INK = Color(0xFF2A1D00)

/** 会员到期时间：2036-10-31 */
private fun formatVipDate(ms: Long): String {
    if (ms <= 0L) return "—"
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ms))
    }.getOrDefault("—")
}

/**
 * 账号弹层：登录 / 注册 / 同步 / 退出 / 注销。
 *
 * 与设置 / 播放列表等弹层同构（[JunSheet] + 拖把手），风格完全一致。
 *
 * 交互上的硬要求（之前最容易翻车的几处）：
 *  - 输入先在**本地**校验（用户名字符与长度、密码长度），不合格直接说清楚，
 *    不再让用户等一次网络往返才看到「密码至少 6 位」；
 *  - 请求进行中（[PlayerUiState.accountBusy]）按钮禁用 + 转圈，网络慢时不会连点重试；
 *  - 服务器未接入（[ServerApi.isConfigured] 为假）直接说明，不让用户白等超时。
 */
@Composable
internal fun AccountSheet(
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onLogout: () -> Unit,
    onDeleteAccount: () -> Unit = {},
    onNeteaseLogin: () -> Unit,
    onNeteaseLogout: () -> Unit,
    onPickAvatar: (android.net.Uri) -> Unit = {},
    /** 4.0.3 补偿：一键领取 10 年 VIP + 内部尊享会员 */
    onClaimVip: () -> Unit = {},
    /** 打开「我的主页」 */
    onOpenProfile: () -> Unit = {}
) {
    val onCard = colors.text
    val accent = colors.primary
    val loggedIn = state.accountLoggedIn
    // 总后台功能开关：关闭注册后只保留登录；关闭网易云后隐藏第二账号入口。
    val appFlags = RemoteConfigStore.flagsState.value

    var mode by remember { mutableStateOf(0) } // 0=登录 1=注册
    // 后台中途关掉注册：已经停留在注册页的用户立刻退回登录页
    LaunchedEffect(appFlags.register) {
        if (!appFlags.register && mode == 1) mode = 0
    }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var avatarPath by remember { mutableStateOf(state.accountAvatar) }
    // 本地校验提示：写完就变，不用等服务器
    var localHint by remember { mutableStateOf("") }

    // 注册时从相册选头像（普通人不懂 URL，直接拉相册）
    val avatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let { onPickAvatar(it) }
    }
    LaunchedEffect(state.accountAvatar) { avatarPath = state.accountAvatar }

    val drag = rememberSheetDragState()
    val fieldBg = if (colors.isDark) Color(0x14FFFFFF) else Color(0x0F000000)

    /** 提交前的本地校验：返回 null 表示通过 */
    fun validate(): String? {
        val u = username.trim()
        val p = password
        if (u.length < 3 || u.length > 20) return "用户名需 3~20 位"
        if (!u.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it in '一'..'龥' })
            return "用户名仅支持中文 / 字母 / 数字 / 下划线"
        if (p.length < 6) return "密码至少 6 位"
        if (p.length > 64) return "密码最长 64 位"
        // 注册时先在本地过一遍后台敏感词，输入即可得到提示；
        // 真正拦截仍以服务端为准（本地词表可能滞后）。
        if (mode == 1) {
            val words = RemoteConfigStore.sensitiveWordsState.value
            val text = (u + " " + nickname.trim()).lowercase()
            val hit = words.firstOrNull { it.isNotBlank() && text.contains(it.lowercase()) }
            if (hit != null) return "包含敏感词「$hit」，请修改后重试"
        }
        return null
    }

    fun submit() {
        val err = validate()
        if (err != null) {
            localHint = err
            return
        }
        localHint = ""
        Haptics.click()
        if (mode == 0) onLogin(username.trim(), password)
        else onRegister(username.trim(), password, nickname.trim())
    }

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

        if (loggedIn) {
            // 头像直接可点：点一下拉相册换头像，不用再翻设置
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(60.dp)
                        // 和全 App 同一套按压弹簧：缩放层放在 clip/background/junSurface 之前，底色才跟着缩
                        .junPressable(feedback = PressFeedback.NONE) { avatarPicker.launch("image/*") }
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(colors.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarPath.isNotBlank()) {
                        SongArtwork(
                            filePath = avatarPath,
                            seedId = state.accountUserId.takeIf { it > 0 }
                                ?: state.accountName.hashCode().toLong(),
                            artworkSize = 60.dp,
                            isDark = colors.isDark
                        )
                    } else {
                        Text(
                            state.accountName.take(1).ifBlank { "J" },
                            color = colors.primary, fontSize = 24.sp, fontWeight = FontWeight.Black
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("账号", color = onCard, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "已登录：${state.accountName}",
                        color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "点击头像即可更换 APP 头像",
                        color = colors.primary, fontSize = 11.sp
                    )
                    // 6.6.6：这里原来只有「已登录」，用户会把它读成「在线」，
                    // 而服务器后台按心跳算的是「3 分钟前在线」—— 两边对不上，像是 bug。
                    // 现在直接把服务端记录的活跃时间摆出来，口径完全一致。
                    if (state.accountLoggedIn && state.accountLastActiveAt > 0L) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "上次活跃：${lastActiveText(state.accountLastActiveAt)}",
                            color = colors.subText, fontSize = 11.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "「我喜欢」会自动实时同步到服务器，换设备也不会丢，无需手动操作。",
                color = colors.subText, fontSize = 12.sp, lineHeight = 19.sp
            )
            AccountStatusLine(state.accountStatus, colors)
            Spacer(Modifier.height(14.dp))

            // ---------- 4.0.3 特大事故补偿：10 年 VIP / 内部尊享会员 ----------
            if (state.vipClaimed) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(JunShape.item)
                        .background(VIP_GOLD.copy(alpha = 0.14f))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.vipName.ifBlank { "内部尊享会员" },
                            color = VIP_GOLD, fontSize = 15.sp, fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("10 年 VIP", color = colors.subText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "有效期至 ${formatVipDate(state.vipUntil)} · 感谢你在故障期间的耐心等待",
                        color = colors.subText, fontSize = 12.sp, lineHeight = 18.sp
                    )
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(JunShape.item)
                        .background(VIP_GOLD.copy(alpha = 0.12f))
                        .padding(14.dp)
                ) {
                    Text(
                        "4.0.3 特大事故补偿",
                        color = VIP_GOLD, fontSize = 15.sp, fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "因账号与收藏同步故障给你带来的损失，向所有用户补偿：10 年 VIP + 内部尊享会员。点下面按钮一键领取，立即到账。",
                        color = colors.text, fontSize = 12.sp, lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .junPressable(feedback = PressFeedback.CLICK, enabled = !state.vipBusy) { onClaimVip() }
                            .clip(JunShape.item)
                            .background(if (state.vipBusy) VIP_GOLD.copy(alpha = 0.55f) else VIP_GOLD),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.vipBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = VIP_INK,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "一键领取 10 年 VIP + 内部尊享",
                                color = VIP_INK, fontSize = 13.sp, fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            AccountButton("退出登录", colors.subText, colors, Modifier.fillMaxWidth()) {
                Haptics.tap(); onLogout()
            }
            Spacer(Modifier.height(10.dp))
            AccountButton("我的主页", accent, colors, Modifier.fillMaxWidth()) {
                Haptics.tap(); onOpenProfile()
            }
            Spacer(Modifier.height(10.dp))
            // 注销账号：删除服务端个人数据 + 清本机，合规硬门槛。二次确认避免误触。
            var showDeleteConfirm by remember { mutableStateOf(false) }
            AccountButton(
                "注销账号并删除我的数据",
                ERROR_RED, colors, Modifier.fillMaxWidth()
            ) { Haptics.tap(); showDeleteConfirm = true }
            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    confirmButton = {
                        Text(
                            "确认注销",
                            color = ERROR_RED,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .junPressable(feedback = PressFeedback.NONE) { showDeleteConfirm = false; onDeleteAccount() }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    },
                    dismissButton = {
                        Text(
                            "取消",
                            color = colors.subText,
                            modifier = Modifier
                                .junPressable(feedback = PressFeedback.NONE) { showDeleteConfirm = false }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    },
                    title = { Text("注销账号？", color = onCard, fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            "这会删除服务器上你的账号、我喜欢、歌单与播放记录，且不可恢复。本机账号凭证也会一并清除。",
                            color = colors.subText, fontSize = 13.sp, lineHeight = 19.sp
                        )
                    },
                    containerColor = colors.card
                )
            }

        } else {
            if (!ServerApi.isConfigured) {
                Text(
                    "当前未配置服务器，登录 / 注册不可用；本地功能不受影响。",
                    color = ERROR_RED, fontSize = 12.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("账号", color = onCard, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(12.dp))
                ModeChip("登录", mode == 0, colors) { mode = 0; localHint = "" }
                if (appFlags.register) {
                    Spacer(Modifier.width(8.dp))
                    ModeChip("注册", mode == 1, colors) { mode = 1; localHint = "" }
                }
            }
            Spacer(Modifier.height(10.dp))
            // 未登录也能看到补偿入口：登录后一键领取，避免用户以为活动不存在
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(JunShape.item)
                    .background(VIP_GOLD.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    "4.0.3 特大事故补偿",
                    color = VIP_GOLD, fontSize = 12.sp, fontWeight = FontWeight.Black
                )
                Text(
                    "登录后一键领取 10 年 VIP + 内部尊享会员",
                    color = colors.text, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(14.dp))

            // 注册模式：顶部展示头像选择（从相册选，不用输 URL）
            if (mode == 1) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .junPressable(feedback = PressFeedback.NONE) { avatarPicker.launch("image/*") }
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(colors.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarPath.isNotBlank()) {
                            SongArtwork(
                                filePath = avatarPath,
                                seedId = username.hashCode().toLong(),
                                artworkSize = 64.dp,
                                isDark = colors.isDark
                            )
                        } else {
                            Text("头像", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            "点击选择头像",
                            color = onCard, fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "从本地相册选择，无需输入网址",
                            color = colors.subText, fontSize = 11.sp
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            AccountField(
                placeholder = "用户名（3~20 位）",
                value = username,
                bg = fieldBg,
                colors = colors,
                isPassword = false,
                onChange = { username = it; localHint = "" }
            )
            Spacer(Modifier.height(10.dp))
            AccountField(
                placeholder = if (mode == 0) "密码" else "密码（至少 6 位）",
                value = password,
                bg = fieldBg,
                colors = colors,
                isPassword = !passwordVisible,
                onChange = { password = it; localHint = "" },
                trailing = {
                    Text(
                        if (passwordVisible) "隐藏" else "显示",
                        color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.junPressable(feedback = PressFeedback.NONE) { passwordVisible = !passwordVisible }
                    )
                },
                onDone = { submit() }
            )
            if (mode == 1) {
                Spacer(Modifier.height(10.dp))
                AccountField(
                    "昵称（可留空）", nickname, fieldBg, colors, false,
                    onChange = { nickname = it }
                )
            }

            if (localHint.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(localHint, color = ERROR_RED, fontSize = 12.sp, lineHeight = 18.sp)
            }
            AccountStatusLine(state.accountStatus, colors)

            Spacer(Modifier.height(16.dp))
            val canSubmit = username.isNotBlank() && password.isNotBlank() && !state.accountBusy
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .junPressable(feedback = PressFeedback.NONE) {
                        if (!canSubmit) return@junPressable
                        submit()
                    }
                    .clip(JunShape.item)
                    .background(if (canSubmit) accent else colors.subText.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                if (state.accountBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = accent.readableTextOn(),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        if (mode == 0) "登录" else "注册并登录",
                        color = accent.readableTextOn(),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "登录后「我喜欢」会实时自动同步；不登录也能正常使用，只是不同步。\n忘记密码请联系 $SUPPORT_EMAIL",
                color = colors.subText, fontSize = 11.sp, lineHeight = 17.sp
            )
        }

        // ---------- 网易云账号（第二个登录，与 App 账号相互独立、可选） ----------
        if (appFlags.netease) {
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.subText.copy(alpha = 0.15f))
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("网易云账号", color = onCard, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (state.neteaseLoggedIn) "已登录：${state.neteaseName}"
                        else "扫码登录后可查看 / 发表评论",
                        color = colors.subText, fontSize = 12.sp, lineHeight = 17.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (state.neteaseLoggedIn) {
                    AccountButton("退出", colors.subText, colors, Modifier.width(68.dp)) {
                        Haptics.tap(); onNeteaseLogout()
                    }
                } else {
                    AccountButton("扫码登录", accent, colors, Modifier.width(96.dp)) {
                        Haptics.click(); onNeteaseLogin()
                    }
                }
            }
        }

        // ---------- 客服（后台「App 配置」里填了才显示） ----------
        val customer = RemoteConfigStore.customerServiceState.value
        if (!customer.isEmpty) {
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.subText.copy(alpha = 0.15f))
            )
            Spacer(Modifier.height(12.dp))
            Text("联系客服", color = onCard, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (customer.qq.isNotBlank()) {
                Text("QQ：${customer.qq}", color = colors.subText, fontSize = 12.sp, lineHeight = 18.sp)
            }
            if (customer.wechat.isNotBlank()) {
                Text("微信：${customer.wechat}", color = colors.subText, fontSize = 12.sp, lineHeight = 18.sp)
            }
            if (customer.email.isNotBlank()) {
                Text("邮箱：${customer.email}", color = colors.subText, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
    }
}

/** 账号状态行：进行中/成功用强调色，失败用红色（省得用户自己去读文案判断成败） */
@Composable
private fun AccountStatusLine(status: String, colors: JunColors) {
    if (status.isBlank()) return
    val isError = status.contains("失败") || status.contains("错误") || status.contains("请") ||
        status.contains("不") || status.contains("无法") || status.contains("未")
    Spacer(Modifier.height(10.dp))
    Text(status, color = if (isError) ERROR_RED else colors.primary, fontSize = 12.sp, lineHeight = 18.sp)
}

@Composable
private fun ModeChip(label: String, active: Boolean, colors: JunColors, onClick: () -> Unit) {
    Box(
        Modifier
            .junPressable { onClick() }
            .clip(JunShape.item)
            .background(if (active) colors.primary.copy(alpha = 0.2f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (active) colors.primary else colors.subText,
            fontSize = 12.sp, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AccountField(
    placeholder: String,
    value: String,
    bg: Color,
    colors: JunColors,
    isPassword: Boolean,
    onChange: (String) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    onDone: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(JunShape.item)
            .background(bg)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = LocalJunUiFamily.current, color = colors.text, fontSize = 14.sp),
                cursorBrush = SolidColor(colors.primary),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
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
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

@Composable
private fun AccountButton(
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

/**
 * 修改密码弹层：旧密码 + 新密码，提交后回调结果文案。
 */
@Composable
internal fun ChangePasswordDialog(
    colors: JunColors,
    onDismiss: () -> Unit,
    onChange: (String, String, (String) -> Unit) -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val accent = colors.primary
    val fieldBg = if (colors.isDark) Color(0x14FFFFFF) else Color(0x0F000000)
    var oldPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var confirmPwd by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { if (!busy) onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .junSurface(GlassLevel.SHEET, colors, JunShape.item)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .padding(20.dp)
        ) {
            Text("修改密码", color = onSheet, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            AccountField("旧密码", oldPwd, fieldBg, colors, true, onChange = { oldPwd = it })
            Spacer(Modifier.height(10.dp))
            AccountField("新密码（至少 6 位）", newPwd, fieldBg, colors, true, onChange = { newPwd = it })
            Spacer(Modifier.height(10.dp))
            AccountField("确认新密码", confirmPwd, fieldBg, colors, true, onChange = { confirmPwd = it })
            if (notice.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    notice,
                    color = if (notice.contains("成功")) accent else ERROR_RED,
                    fontSize = 12.sp, lineHeight = 18.sp
                )
            }
            Spacer(Modifier.height(16.dp))
            val canSubmit = oldPwd.isNotBlank() && newPwd.isNotBlank() && confirmPwd.isNotBlank() && !busy
            Row {
                AccountButton("取消", colors.subText, colors, Modifier.weight(1f)) { onDismiss() }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .junPressable(feedback = PressFeedback.NONE) {
                            if (!canSubmit) return@junPressable
                            if (newPwd.length < 6) {
                                notice = "新密码至少 6 位"
                                return@junPressable
                            }
                            if (newPwd != confirmPwd) {
                                notice = "两次输入的新密码不一致"
                                return@junPressable
                            }
                            busy = true
                            notice = "提交中…"
                            onChange(oldPwd, newPwd) { msg ->
                                notice = msg
                                busy = false
                            }
                        }
                        .clip(JunShape.item)
                        .background(if (canSubmit) accent.copy(alpha = 0.18f) else colors.subText.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (busy) "提交中…" else "确认修改",
                        color = if (canSubmit) accent else colors.subText,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * 6.6.6：把「上次活跃」时间戳说成人话。
 *
 * 精确到分钟就够了 —— 用户要的是"我到底还在不在线"，不是秒级时间。
 * 和后台管理页用的是同一个 last_seen，所以两处显示的结论不会再打架。
 */
private fun lastActiveText(ts: Long): String {
    if (ts <= 0L) return "未知"
    val diff = System.currentTimeMillis() - ts
    if (diff < 0L) return "刚刚"
    val min = diff / 60_000L
    return when {
        min < 1L -> "刚刚"
        min < 60L -> "${min} 分钟前"
        min < 24L * 60 -> "${min / 60L} 小时前"
        min < 30L * 24 * 60 -> "${min / (24L * 60)} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(ts))
    }
}
