package com.jun.musiccloud.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.jun.musiccloud.data.PlaylistShare
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.junSurface

/**
 * 剪贴板里发现歌单链接时的确认卡片。
 *
 * 只在真正检测到音乐平台链接时出现，问一句「是否将链接导入到歌单」；
 * 导入中禁用按钮，导入成功后可以直接去看新歌单。
 */
@Composable
internal fun ClipboardImportPrompt(
    link: String,
    busy: Boolean,
    status: String,
    success: Boolean,
    colors: JunColors,
    onImport: () -> Unit,
    onOpenPlaylist: () -> Unit,
    onDismiss: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val fieldBg = if (colors.isDark) Color(0x14FFFFFF) else Color(0x0F000000)
    val errorColor = Color(0xFFFF6B6B)

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
                .padding(horizontal = 30.dp)
                .junSurface(GlassLevel.SHEET, colors, JunShape.item)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .padding(22.dp)
        ) {
            // R13：剪贴板里可能是本 App 自己的歌单口令（JUNPLAY…），不是平台链接。
            // 这里只改说法，流程共用（PlayerViewModel.importNeteasePlaylist 里已经按口令分流）。
            val isJunCode = PlaylistShare.looksLikeShareCode(link)
            Text(
                if (success) "导入完成" else if (isJunCode) "检测到 Jun 歌单口令" else "检测到歌单链接",
                color = onSheet,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    success -> "歌单已经保存到「我的歌单」，可以直接播放。"
                    busy -> if (isJunCode) "正在逐首对口令里的歌，对不上的会给你列出来…"
                            else "正在解析歌单并匹配网易云曲库，请稍候…"
                    else -> if (isJunCode) "是否把这段口令导入成一个新歌单？" else "是否将链接导入到歌单？"
                },
                color = colors.subText,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )

            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(JunShape.item)
                    .background(fieldBg)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    link,
                    color = colors.text.copy(alpha = 0.82f),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (busy) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        color = colors.primary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(9.dp))
                    Text("正在导入…", color = colors.primary, fontSize = 11.sp)
                }
            } else if (status.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    status,
                    color = if (success) colors.primary else errorColor,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (success) {
                    Box(
                        Modifier
                            .junPressable(feedback = PressFeedback.NONE) { onDismiss() }
                            .clip(JunShape.item)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("完成", color = colors.subText, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .junPressable(feedback = PressFeedback.NONE) { onOpenPlaylist() }
                            .clip(JunShape.item)
                            .background(colors.primary.copy(alpha = 0.16f))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("查看歌单", color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Box(
                        Modifier
                            .junPressable(feedback = PressFeedback.NONE, enabled = !busy) { onDismiss() }
                            .clip(JunShape.item)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("不用了", color = colors.subText, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .junPressable(feedback = PressFeedback.NONE, enabled = !busy) { onImport() }
                            .clip(JunShape.item)
                            .background(colors.primary.copy(alpha = if (busy) 0.08f else 0.16f))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            if (status.isNotBlank()) "重试" else "导入到歌单",
                            color = if (busy) colors.primary.copy(alpha = 0.5f) else colors.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
