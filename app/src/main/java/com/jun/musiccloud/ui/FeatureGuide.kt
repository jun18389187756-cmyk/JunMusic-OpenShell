package com.jun.musiccloud.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.readableOn
import com.jun.musiccloud.ui.theme.readableTextOn

/**
 * 6.6 最新版 · 刚进软件的引导（首次安装 / 覆盖安装后都弹一次）。
 *
 * 用户这轮的要求：App 里原本就有一套引导动画（聚光灯那种呼吸光环 / 入场 / 按压反馈），
 * 要「刚进软件有个引导，主要引导【自定义界面】和【气氛模式】这两个功能」，并且
 * 「把新版本修复的、加的功能全部加到弹窗里」。
 *
 * 所以这里做成 **3 步、带动画的引导**，视觉语言直接沿用项目里的聚光灯引导：
 *  - 卡片缩放淡入、功能卡上一个持续扩散的呼吸光环（跟 SpotlightGuide 同源）；
 *  - 步骤之间 AnimatedContent 左右滑入淡入，底部进度点；
 *  - 所有按钮走 junPressable（按压缩放），「上一步 / 下一步 / 跳过」齐备；
 *  - 前两步各讲一个重点功能，卡片里带「一键直达」——点了立刻打开对应界面；
 *  - 第三步把 [WhatsNewR19] 的完整清单（修好的 / 新加的 / 说明）铺开、可滚动。
 *
 * 刻意**不新增独立弹层、不改 MainScreen 的启动时序**：历史上「多个启动弹层抢时间窗、
 * 公告闪一下就没」踩过好几次坑，这里只替换原来那一个 FeatureGuideSheet，
 * 门控仍由 WHATS_NEW_CODE / showWhatsNew 管。
 */
@Composable
internal fun FeatureGuideSheet(
    colors: JunColors,
    onOpenCustom: () -> Unit = {},
    onOpenMood: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val cardShape = remember { RoundedCornerShape(24.dp) }
    val accent = colors.primary.readableOn(colors.card, 3.5f)

    // 卡片入场：缩放 + 淡入（跟手但不吵）
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enterScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.92f,
        animationSpec = tween(340), label = "guideEnterScale"
    )
    val enterAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(300), label = "guideEnterAlpha"
    )

    var step by remember { mutableIntStateOf(0) }
    val lastStep = 2

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.12f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .graphicsLayer {
                    scaleX = enterScale; scaleY = enterScale; alpha = enterAlpha
                }
                .junSurface(GlassLevel.SHEET, colors, cardShape)
                // 吞掉卡片内部点击，别穿透到遮罩把弹窗关了
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .padding(20.dp)
        ) {
            // ── 顶部：版本 + 标题 + 跳过 ─────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("6.6", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (step < lastStep) "带你认两个新功能" else "这版都更新了啥",
                        color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Black
                    )
                    Text(
                        "从 6.5 到 6.6 最新版",
                        color = colors.subText, fontSize = 11.sp
                    )
                }
                Text(
                    "跳过",
                    color = colors.subText, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .junPressable { onDismiss() }
                        .clip(JunShape.item)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            // ── 内容：3 步切换，左右滑入淡入 ───────────────────────
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 5 })
                        .togetherWith(fadeOut(tween(150)) + slideOutHorizontally(tween(180)) { -it / 5 })
                },
                label = "guideStep"
            ) { s ->
                when (s) {
                    0 -> FeatureIntroCard(WhatsNewR19.HIGHLIGHTS[0], accent, colors, onOpenCustom)
                    1 -> FeatureIntroCard(WhatsNewR19.HIGHLIGHTS[1], accent, colors, onOpenMood)
                    else -> FullChangelogCard(accent, colors)
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 底部：进度点 + 上一步 / 下一步 ─────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    repeat(lastStep + 1) { i ->
                        Box(
                            Modifier
                                .padding(end = 5.dp)
                                .size(width = if (i == step) 18.dp else 6.dp, height = 6.dp)
                                .clip(CircleShape)
                                .background(if (i == step) accent else colors.subText.copy(alpha = 0.32f))
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (step > 0) {
                    Text(
                        "上一步",
                        color = colors.subText, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .junPressable { step-- }
                            .clip(JunShape.item)
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Box(
                    Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.primary)
                        .junPressable {
                            if (step < lastStep) step++ else onDismiss()
                        }
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (step < lastStep) "下一步" else "知道了，开始听歌",
                        color = colors.primary.readableTextOn(),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * 一步功能引导卡：呼吸光环 + 标题 + 介绍 + 位置 + 一键直达。
 * 呼吸光环跟 SpotlightGuide 的「扩散描边」同源，纯 Compose、全机型可用。
 */
@Composable
private fun FeatureIntroCard(
    h: WhatsNewR19.Highlight,
    accent: Color,
    colors: JunColors,
    onGo: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        // 呼吸光环
        val infinite = rememberInfiniteTransition(label = "guideGlow")
        val glow by infinite.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing)),
            label = "glow"
        )
        Box(
            Modifier.fillMaxWidth().height(66.dp),
            contentAlignment = Alignment.Center
        ) {
            // 三层错开扩散的环
            for (k in 0 until 2) {
                val p = (glow + k / 2f) % 1f
                Box(
                    Modifier
                        .size((40 + p * 26).dp)
                        .graphicsLayer { alpha = (1f - p) * 0.55f }
                        .border(2.dp, accent, CircleShape)
                )
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f))
                    .border(1.5.dp, accent.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (h.title.contains("自定义")) "自" else "气",
                    color = accent, fontSize = 20.sp, fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(accent.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(h.tag, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(8.dp))
            Text(h.title, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(10.dp))
        Text(h.desc, color = colors.subText, fontSize = 12.5.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(10.dp))
        Text("位置：${h.where}", color = accent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.14f))
                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .junPressable { onGo() },
            contentAlignment = Alignment.Center
        ) {
            Text(h.action, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 第 3 步：把全部修复 / 新增 / 说明铺开，可滚动，分组小标题 */
@Composable
private fun FullChangelogCard(accent: Color, colors: JunColors) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
    ) {
        ChangelogGroup("修好的", WhatsNewR19.FIXES, accent, colors)
        Spacer(Modifier.height(12.dp))
        ChangelogGroup("新加的", WhatsNewR19.NEWS, accent, colors)
        Spacer(Modifier.height(12.dp))
        ChangelogGroup("说明与边界（如实说）", WhatsNewR19.NOTES, accent, colors)
        Spacer(Modifier.height(4.dp))
        Text(
            WhatsNewR19.FOOTER,
            color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp
        )
    }
}

@Composable
private fun ChangelogGroup(
    label: String,
    items: List<WhatsNewItem>,
    accent: Color,
    colors: JunColors
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(7.dp))
        Text(label, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(6.dp))
        Text("${items.size} 项", color = colors.subText, fontSize = 10.5.sp)
    }
    Spacer(Modifier.height(8.dp))
    items.forEach { item ->
        Row(Modifier.fillMaxWidth().padding(bottom = 7.dp)) {
            Text("· ", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    lineHeight = 16.sp
                )
                Text(
                    item.body,
                    color = colors.subText, fontSize = 11.sp, lineHeight = 16.sp,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}
