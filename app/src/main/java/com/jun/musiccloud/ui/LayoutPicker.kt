package com.jun.musiccloud.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.readableTextOn

/**
 * 首次启动的界面排布选择器（替代旧的新手引导）。
 *
 * 用户必须选一种排布才能进入主界面；默认选中「纵向排布」。
 * 每个选项带一个极简预览，降低理解成本。
 */
@Composable
internal fun LayoutPicker(
    colors: JunColors,
    current: com.jun.musiccloud.ui.theme.JunLayout,
    onPick: (com.jun.musiccloud.ui.theme.JunLayout) -> Unit,
    onEnter: () -> Unit
) {
    var selected by remember { mutableStateOf(current) }
    val options = com.jun.musiccloud.ui.theme.JunLayout.values()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (colors.isDark) 0.82f else 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .junSurface(GlassLevel.SHEET, colors, RoundedCornerShape(24.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("选择界面排布", color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text("选一个你喜欢的样子，之后随时可在设置里切换", color = colors.subText, fontSize = 12.sp)
            Spacer(Modifier.height(18.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEach { layout ->
                    val active = layout == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .junPressableRow {
                                selected = layout
                                onPick(layout)
                            }
                            .clip(JunShape.item)
                            .background(if (active) colors.primary.copy(alpha = 0.18f) else colors.surface)
                            .border(
                                1.5.dp,
                                if (active) colors.primary else Color.Transparent,
                                JunShape.item
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LayoutPreview(layout, colors, Modifier.size(56.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(layout.label, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(layoutHint(layout), color = colors.subText, fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .junPressable { onEnter() }
                    .clip(JunShape.item)
                    .background(colors.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("进入 Jun Music", color = colors.primary.readableTextOn(), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun layoutHint(layout: com.jun.musiccloud.ui.theme.JunLayout): String = when (layout) {
    com.jun.musiccloud.ui.theme.JunLayout.LIST -> "竖向歌单，左右滑切播放页"
    com.jun.musiccloud.ui.theme.JunLayout.CAROUSEL -> "卡片横滑，左右翻歌"
    com.jun.musiccloud.ui.theme.JunLayout.STACK -> "一沓卡片，拖走切歌"
    com.jun.musiccloud.ui.theme.JunLayout.COMPACT -> "整屏唱片墙"
}

/** 排布的极简示意图 */
@Composable
private fun LayoutPreview(layout: com.jun.musiccloud.ui.theme.JunLayout, colors: JunColors, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val line = colors.primary.copy(alpha = 0.55f)
    Box(modifier.clip(shape).background(colors.surface)) {
        when (layout) {
            com.jun.musiccloud.ui.theme.JunLayout.LIST -> Column(
                Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                repeat(4) {
                    Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(line))
                }
            }
            com.jun.musiccloud.ui.theme.JunLayout.CAROUSEL -> Row(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(Modifier.width(28.dp).fillMaxSize().clip(shape).background(line))
                Box(Modifier.width(28.dp).fillMaxSize().clip(shape).background(colors.primary))
                Box(Modifier.width(28.dp).fillMaxSize().clip(shape).background(line))
            }
            com.jun.musiccloud.ui.theme.JunLayout.STACK -> Box(Modifier.fillMaxSize().padding(8.dp)) {
                Box(Modifier.fillMaxSize().offset(x = 6.dp, y = 6.dp).clip(shape).background(line.copy(alpha = 0.4f)))
                Box(Modifier.fillMaxSize().offset(x = 3.dp, y = 3.dp).clip(shape).background(line.copy(alpha = 0.7f)))
                Box(Modifier.fillMaxSize().clip(shape).background(colors.primary))
            }
            com.jun.musiccloud.ui.theme.JunLayout.COMPACT -> Column(
                Modifier.fillMaxSize().padding(7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(13.dp).clip(CircleShape).background(colors.primary))
                    Box(Modifier.size(13.dp).clip(CircleShape).background(line))
                    Box(Modifier.size(13.dp).clip(CircleShape).background(line))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(13.dp).clip(CircleShape).background(line))
                    Box(Modifier.size(13.dp).clip(CircleShape).background(colors.primary))
                    Box(Modifier.size(13.dp).clip(CircleShape).background(line))
                }
            }
        }
    }
}
