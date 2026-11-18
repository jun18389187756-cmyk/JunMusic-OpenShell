package com.jun.musiccloud.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.jun.musiccloud.R
import com.jun.musiccloud.data.PrivacyStore
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.readableTextOn

/**
 * 隐私政策同意卡（首次启动必须先过这一关）。
 *
 * 与 App 内其它卡片同一套材质与圆角，内容用要点列表而不是一大段法律文本——
 * 用户真的会读的只有"你拿我什么数据、拿去干嘛"。
 *
 * 不同意时的处理交给调用方（通常是退出应用）：合规上不能"不同意也照样用"。
 */
@Composable
internal fun PrivacyConsentSheet(
    colors: JunColors,
    onAgree: () -> Unit,
    onDecline: () -> Unit
) {
    val slide = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        slide.animateTo(0f, spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow))
    }
    val veil = remember { Animatable(0f) }
    LaunchedEffect(Unit) { veil.animateTo(1f, tween(240)) }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = veil.value }
            .background(colors.background)
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 20.dp)
                .graphicsLayer { translationY = slide.value * 240f }
                .junSurface(GlassLevel.SHEET, colors, JunShape.card)
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            // 品牌 + 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.app_logo),
                    contentDescription = "Jun Music",
                    modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("隐私政策", color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "在使用前，请花 10 秒看一下我们会用到哪些数据。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.subText
            )

            Spacer(Modifier.height(14.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PolicyItem(
                    colors = colors,
                    dot = Color(0xFF2EE6A8),
                    title = "不会收集的",
                    body = "通讯录、短信、通话记录、精确位置、相册里的照片——一个都不碰，也没有对应权限。"
                )
                PolicyItem(
                    colors = colors,
                    dot = colors.primary,
                    title = "注册登录时",
                    body = "账号（手机号 / 邮箱）与密码，用于识别你的身份；「我喜欢的音乐」与歌单会存到服务器，方便换手机后恢复。"
                )
                PolicyItem(
                    colors = colors,
                    dot = colors.secondary,
                    title = "听歌时",
                    body = "记录播放时长用于生成「收听报告」，只存在你自己的手机上与你的账号下，不对外提供。"
                )
                PolicyItem(
                    colors = colors,
                    dot = Color(0xFFFF8A3C),
                    title = "在线曲库",
                    body = "搜索与播放会向曲库服务发起请求（含关键词）。不登录也能听，此时服务端不持有任何你的身份信息。"
                )
                PolicyItem(
                    colors = colors,
                    dot = colors.subText,
                    title = "你的权利",
                    body = "设置里可随时撤回同意、导出或清除自己的数据；撤回后不再上传，本地已下载的内容照常可播。"
                )
                PolicyItem(
                    colors = colors,
                    dot = Color(0xFF6C8CFF),
                    title = "第三方 SDK",
                    body = "不集成任何第三方统计、广告、推送 SDK，也不会把你的数据发给「你的自建服务器」以外的任何一方。仅使用 Android 官方播放与加密库。"
                )
                PolicyItem(
                    colors = colors,
                    dot = Color(0xFFFFC93C),
                    title = "联系与注销",
                    body = "可在「账号」页一键注销并删除云端全部个人数据；如需联系或申请删除数据，请发邮件至 3611452139@qq.com。"
                )
                PolicyItem(
                    colors = colors,
                    dot = Color(0xFFB388FF),
                    title = "未成年人",
                    body = "本应用主要面向成年用户。若你是未成年人，请在监护人指导下使用，并注意控制使用时长。"
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        // 按压层放在 clip/background 之前，底色才跟着按钮一起缩
                        .junPressable { onDecline() }
                        .clip(JunShape.item)
                        .background(colors.subText.copy(alpha = 0.14f))
                        .semantics { contentDescription = "不同意隐私政策并退出" },
                    contentAlignment = Alignment.Center
                ) {
                    Text("不同意", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .weight(1.35f)
                        .height(48.dp)
                        .junPressable(feedback = PressFeedback.CLICK) { onAgree() }
                        .clip(JunShape.item)
                        .background(colors.primary)
                        .semantics { contentDescription = "同意隐私政策并开始使用" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "同意并继续",
                        color = colors.primary.readableTextOn(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "政策版本 v${PrivacyStore.POLICY_VERSION}",
                color = colors.subText.copy(alpha = 0.7f),
                fontSize = 10.5.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

/** 一条政策要点：色点 + 小标题 + 说明 */
@Composable
private fun PolicyItem(
    colors: JunColors,
    dot: Color,
    title: String,
    body: String
) {
    Row {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(
                body,
                color = colors.subText,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Start
            )
        }
    }
}
