package com.jun.musiccloud.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.junSurface
import kotlinx.coroutines.delay

/**
 * 一条轻提示。
 *
 * [tick] 每次触发自增：同一文案再次触发时也重播动画（否则 Compose 认为状态没变，弹不出来）。
 * [actionLabel] + [onAction] 成对给出时右侧多一枚可点的动作键（「查看」「撤销」「重试」）。
 */
internal data class ToastItem(
    val message: String,
    val tick: Int,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    /** 停留时长：带动作的提示要给足点击时间（撤销 4 秒，普通提示 2.6 秒） */
    val durationMs: Long = 2600L,
    /**
     * 左侧小图标（可空）。
     *
     * 6.1 加：音质切换这类提示用户要的是"那种高端的提示"——光换文案还是灰扑扑一条字。
     * 给一枚 15dp 的主色图标，胶囊立刻读成一条**状态提示**而不是一行调试文本。
     */
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /** 图标与文案用主色（默认跟正文同色） */
    val accent: Boolean = false
)

/**
 * 底部的轻提示栈。
 *
 * 之前每个提示各自 `align(BottomCenter).padding(bottom = 96/140/172dp)`——
 * 三个数字是为了让它们不互相压住而手算出来的，多一种提示就要把所有数字重排一遍，
 * 底部播放条一显隐又要另算一套。
 *
 * 这里改成栈：调用方只管把要弹的提示丢进 [items]（空文案自动忽略），
 * 位置由栈自己从下往上排，底部留出的播放条高度由 [bottomInset] 一次给清。
 */
@Composable
internal fun ToastHost(
    items: List<ToastItem>,
    colors: JunColors,
    /** 底部被占用高度（常驻播放条等），提示整体抬到它上面 */
    bottomInset: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val visible = items.filter { it.message.isNotBlank() }
    if (visible.isEmpty()) return

    Column(
        modifier = modifier.padding(bottom = bottomInset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
            8.dp,
            Alignment.Bottom
        )
    ) {
        visible.forEach { item -> ToastPill(item, colors) }
    }
}

@Composable
private fun ToastPill(item: ToastItem, colors: JunColors) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(item.tick) {
        if (item.tick > 0 && item.message.isNotBlank()) {
            shown = true
            delay(item.durationMs)
            shown = false
        }
    }
    AnimatedVisibility(
        visible = shown,
        // 入场用欠阻尼弹簧：提示条推上来时冲过一点再收回，和 App 里其它会回弹的东西同一套物理。
        // 出场保持定长（关闭要有"确定性"，弹出去会显得拖泥带水）。
        enter = fadeIn(tween(160)) + slideInVertically(animationSpec = JunMotion.toastEnterOffset()) { it / 2 },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 2 }
    ) {
        val tone = if (item.accent) colors.primary else colors.text
        Row(
            Modifier
                .junSurface(GlassLevel.CHIP, colors, JunShape.item)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            if (item.icon != null) {
                Icon(
                    item.icon!!,
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(item.message, color = tone, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (item.actionLabel != null && item.onAction != null) {
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .junPressable(scaleDown = 0.86f) { item.onAction.invoke() }
                        .clip(JunShape.chip)
                        .background(colors.primary.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(item.actionLabel, color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
