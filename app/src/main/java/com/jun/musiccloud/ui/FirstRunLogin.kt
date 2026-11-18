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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.R
import com.jun.musiccloud.data.RemoteConfigStore
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.ui.theme.GlassContent
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.readableTextOn

/**
 * 首次安装的唯一界面：登录（可选）→ 进入。
 *
 * 只保留两种登录入口：Jun Music 账号、网易云账号。没有绘本、没有聚光灯、
 * 没有隐私同意、没有设备与材质选择——那些都从首启里拿掉了；隐私政策仍在
 * 「设置 → 关于 → 隐私政策与撤回同意」里，想看的用户自己会去。
 *
 * 刻意不用 Dialog：账号 / 网易云登录是主窗口里的普通浮层，首启若另开 Dialog
 * 窗口会把登录弹层压在下面。用整屏 Box 叠在主界面之上，后面的登录浮层就能正常盖上来。
 *
 * 点「进入」由调用方收起首启并申请通知权限（Android 13+）。
 */
@Composable
internal fun FirstRunLogin(
    state: PlayerUiState,
    colors: JunColors,
    onLocalLogin: () -> Unit,
    onNeteaseLogin: () -> Unit,
    onEnter: () -> Unit
) {
    val flags = RemoteConfigStore.flagsState.value
    val localLogged = state.accountLoggedIn
    val neteaseLogged = state.neteaseLoggedIn
    val loggedIn = localLogged || neteaseLogged

    Box(
        Modifier
            .fillMaxSize()
            // 半透明遮罩：首页已经先画出来了，登录层浮在上面，而不是另开一块空白页。
            .background(junSheetScrim(colors))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(JunShape.card)
                .junSurface(GlassLevel.SHEET, colors, JunShape.card)
                .padding(horizontal = 20.dp, vertical = 22.dp)
        ) {
            GlassContent {
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
                            "欢迎使用 Jun Music",
                            color = colors.text,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "登录（可选），不登录也能正常听",
                            color = colors.subText,
                            fontSize = 11.5.sp
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                FirstRunLoginOption(
                    iconRes = R.drawable.app_logo,
                    title = if (localLogged) {
                        "Jun Music · ${state.accountName.ifBlank { "已登录" }}"
                    } else {
                        "Jun Music 登录 / 注册"
                    },
                    detail = if (localLogged) {
                        "已登录，可查看同步状态或切换账号"
                    } else {
                        "云端同步「我喜欢」、歌单和听歌记录"
                    },
                    colors = colors,
                    onClick = onLocalLogin
                )

                if (flags.netease) {
                    Spacer(Modifier.height(10.dp))
                    FirstRunLoginOption(
                        iconRes = R.drawable.ic_netease_logo,
                        title = if (neteaseLogged) {
                            "网易云 · ${state.neteaseName.ifBlank { "已登录" }}"
                        } else {
                            "网易云音乐登录"
                        },
                        detail = if (neteaseLogged) {
                            "已登录，可切换账号"
                        } else {
                            "扫码 / 手机号登录，畅听云村曲库"
                        },
                        colors = colors,
                        onClick = onNeteaseLogin
                    )
                }

                Spacer(Modifier.height(20.dp))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .junPressable(feedback = PressFeedback.CLICK) { onEnter() }
                        .clip(RoundedCornerShape(15.dp))
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (loggedIn) "进入 Jun Music" else "暂不登录，直接进入",
                        color = colors.primary.readableTextOn(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(Modifier.height(9.dp))
                Text(
                    "进入后会申请通知权限，用于播放控制与歌词通知；拒绝不影响播放。",
                    color = colors.subText,
                    fontSize = 9.5.sp,
                    lineHeight = 14.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "账号凭证保存在本机，覆盖安装不会退出登录。",
                    color = colors.subText.copy(alpha = 0.85f),
                    fontSize = 9.5.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun FirstRunLoginOption(
    iconRes: Int,
    title: String,
    detail: String,
    colors: JunColors,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .junPressableRow { onClick() }
            .clip(JunShape.item)
            .background(colors.surface.copy(alpha = 0.55f))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = title,
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(detail, color = colors.subText, fontSize = 10.5.sp, lineHeight = 14.sp)
        }
        Text("›", color = colors.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
