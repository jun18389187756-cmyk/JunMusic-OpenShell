package com.jun.musiccloud.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jun.musiccloud.R
import com.jun.musiccloud.data.RemoteConfigStore
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.ui.theme.GlassContent
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.junSurface

/**
 * 登录引导弹窗（首启引导走完之后弹出一次，不强制）。
 *
 * 材质与全 App 同源：外层是 [GlassLevel.SHEET] 液态玻璃，内部两张选项卡片走
 * [GlassContent] 的「玻璃里的玻璃」叠加染色——和设置 / 歌单 / 播放列表弹层
 * 用的是同一套表面，不再是一块孤立的系统白卡片。
 *
 * 两类登录分开呈现，避免「点一下登录要用两个」的混淆：
 *  - 卡片一：Jun Music 账号（App 内注册 / 登录，云端同步「我喜欢」）
 *  - 卡片二：网易云账号（扫码 / 手机号登录，听云村曲库）
 * 底部「暂不登录」（本次跳过）与「不再提示」（永久关闭引导）。
 */
@Composable
fun StartupLoginPrompt(
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit,
    onLocalLogin: () -> Unit,
    onNeteaseLogin: () -> Unit,
    onDontAskAgain: () -> Unit
) {
    val dontAsk = remember { mutableStateOf(false) }
    val appFlags = RemoteConfigStore.flagsState.value
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val scale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.92f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 320f),
        label = "loginPromptScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 400f),
        label = "loginPromptAlpha"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(junSheetScrim(colors))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .fillMaxWidth(0.9f)
                    .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                    .junSurface(GlassLevel.SHEET, colors, JunShape.card)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                    .padding(horizontal = 22.dp, vertical = 22.dp)
            ) {
                GlassContent {
                    // 顶部品牌区：logo + 标题 + 副标题
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.app_logo),
                            contentDescription = "Jun Music",
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                        )
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "欢迎来到 Jun Music",
                                color = colors.text,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "登录后，我喜欢与歌单跟着你走",
                                color = colors.subText,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // 选项一：Jun Music 账号（已登录则直接显示账号名，不用再猜自己登没登）
                    val localName = state.accountName
                    LoginOptionCard(
                        icon = {
                            Image(
                                painter = painterResource(R.drawable.app_logo),
                                contentDescription = "Jun Music 账号",
                                modifier = Modifier.fillMaxSize()
                            )
                        },
                        title = if (localName.isNotBlank()) "Jun Music · $localName" else "Jun Music 账号",
                        desc = if (localName.isNotBlank()) "已登录，可查看同步状态或切换账号"
                        else "手机号不用填，起个名字就能注册 · 云端同步「我喜欢」",
                        colors = colors,
                        onClick = {
                            if (dontAsk.value) onDontAskAgain() else onLocalLogin()
                        }
                    )
                    Spacer(Modifier.height(10.dp))

                    // 选项二：网易云账号（后台关闭网易云功能时整卡不显示）
                    if (appFlags.netease) {
                        LoginOptionCard(
                            icon = {
                                Image(
                                    painter = painterResource(R.drawable.ic_netease_logo),
                                    contentDescription = "网易云账号",
                                    modifier = Modifier.fillMaxSize()
                                )
                            },
                            title = "网易云账号",
                            desc = if (state.neteaseLoggedIn && state.neteaseName.isNotBlank())
                                "已登录：${state.neteaseName} · 可切换账号"
                            else "扫码 / 手机号登录 · 畅听云村曲库与评论区",
                            colors = colors,
                            onClick = {
                                if (dontAsk.value) onDontAskAgain() else onNeteaseLogin()
                            }
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    // 底部：不再提示（左） + 暂不登录（右）
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                // 按压缩放放在 clip 之前：整颗勾选区（含圆角裁剪）一起软
                                .junPressableRow(feedback = PressFeedback.NONE) {
                                    dontAsk.value = !dontAsk.value
                                }
                                .clip(JunShape.chip)
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(17.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        if (dontAsk.value) colors.primary
                                        else colors.subText.copy(alpha = 0.25f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (dontAsk.value) {
                                    Text(
                                        "✓",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(Modifier.width(7.dp))
                            Text("不再提示", color = colors.subText, fontSize = 12.sp)
                        }
                        // 后台关闭「游客模式」后不再提供「暂不登录」入口
                        if (appFlags.guest) {
                            Box(
                                Modifier
                                    .junPressable {
                                        if (dontAsk.value) onDontAskAgain() else onDismiss()
                                    }
                                    .clip(JunShape.chip)
                                    .background(colors.primary.copy(alpha = 0.14f))
                                    .padding(horizontal = 18.dp, vertical = 9.dp)
                            ) {
                                Text(
                                    "暂不登录",
                                    color = colors.primary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一张登录方式卡片。
 *
 * 玻璃里的玻璃：外壳已经是整块折射玻璃，这里用 [GlassLevel.CARD] 的叠加染色
 * （[GlassContent] 内自动降级），只加一层厚度与内缘，不再各自取样背板——
 * 二十个元件各建一条全背板流水线是低端机掉帧的头号来源。
 */
@Composable
private fun LoginOptionCard(
    icon: @Composable () -> Unit,
    title: String,
    desc: String,
    colors: JunColors,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            // 整卡按压：缩放层要在 junSurface 之前，玻璃底才跟内容一起软下去
            .junPressableRow(feedback = PressFeedback.CLICK, onClick = onClick)
            .junSurface(GlassLevel.CARD, colors, JunShape.card)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.text, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(desc, color = colors.subText, fontSize = 11.5.sp, lineHeight = 16.sp)
            }
            Text("›", color = colors.primary, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
    }
}
