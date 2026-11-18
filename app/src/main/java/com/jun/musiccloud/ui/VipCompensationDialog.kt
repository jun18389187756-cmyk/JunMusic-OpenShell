package com.jun.musiccloud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.junSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val COMP_GOLD = Color(0xFFF5C451)
private val COMP_INK = Color(0xFF2A1D00)

private fun compDate(ms: Long): String {
    if (ms <= 0L) return "—"
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ms))
    }.getOrDefault("—")
}

/**
 * 4.0.3 特大事故补偿弹窗：更新说明关掉后自动出现一次。
 *
 * 补偿按账号只发一次，服务端幂等；领完显示到期时间，之后在「账号」弹层里
 * 仍能看到会员卡片。点「稍后再说」不会丢，账号弹层里还能领。
 */
@Composable
internal fun VipCompensationDialog(
    state: PlayerUiState,
    colors: JunColors,
    onClaim: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = { if (!state.vipBusy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !state.vipBusy, dismissOnClickOutside = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .junSurface(GlassLevel.SHEET, colors, RoundedCornerShape(26.dp))
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(COMP_GOLD.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("VIP", color = COMP_GOLD, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "特大事故补偿",
                        color = COMP_GOLD, fontSize = 11.sp, fontWeight = FontWeight.Black
                    )
                    Text(
                        if (state.vipClaimed) "补偿已到账" else "10 年 VIP · 内部尊享会员",
                        color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Black
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.text.copy(alpha = 0.035f))
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    if (state.vipClaimed) {
                        "感谢你在这次同步故障期间的耐心等待。\n\n" +
                            "你的补偿已到账：${state.vipName.ifBlank { "内部尊享会员" }}，" +
                            "有效期至 ${compDate(state.vipUntil)}。\n\n" +
                            "账号、收藏、歌单和搜索记录已经全部自动恢复；换设备、重装后登录一次即可。"
                    } else {
                        "这次账号与收藏同步故障，导致部分用户更新后掉登录、收藏和歌单显示为空。\n\n" +
                            "数据本身没有丢：服务器上的收藏、歌单、播放记录一条都没少，更新后登录一次就会全部回来。\n\n" +
                            "为表达歉意，所有用户可一键领取：\n" +
                            "• 10 年 VIP 会员\n" +
                            "• 内部尊享会员身份\n" +
                            "• 立即到账，换设备不丢\n\n" +
                            "点下面按钮领取，不需要付费、不需要兑换码。"
                    },
                    color = colors.text,
                    fontSize = 12.5.sp,
                    lineHeight = 20.sp
                )
            }
            Spacer(Modifier.height(14.dp))
            if (state.vipClaimed) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .junPressable { onDismiss() }
                        .clip(JunShape.item)
                        .background(COMP_GOLD),
                    contentAlignment = Alignment.Center
                ) {
                    Text("完成", color = COMP_INK, fontSize = 14.sp, fontWeight = FontWeight.Black)
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .junPressable(feedback = PressFeedback.CLICK, enabled = !state.vipBusy) { onClaim() }
                        .clip(JunShape.item)
                        .background(if (state.vipBusy) COMP_GOLD.copy(alpha = 0.55f) else COMP_GOLD),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.vipBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = COMP_INK,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            "一键领取 10 年 VIP + 内部尊享",
                            color = COMP_INK, fontSize = 14.sp, fontWeight = FontWeight.Black
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "稍后再说",
                    color = colors.subText,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .junPressable { onDismiss() }
                        .padding(vertical = 6.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
