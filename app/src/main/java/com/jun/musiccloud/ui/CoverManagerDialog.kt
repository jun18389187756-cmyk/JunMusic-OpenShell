package com.jun.musiccloud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import androidx.compose.ui.window.DialogProperties
import com.jun.musiccloud.data.Song

/**
 * 已替换封面管理：列出每一张被替换的封面、占用空间，支持单张恢复、全部恢复、
 * 清理没有映射的孤儿文件。封面文件在应用私有目录，卸载才会彻底消失；
 * 这里让用户不必为了删一张图去翻文件管理器。
 */
@Composable
internal fun CoverManagerDialog(
    songs: List<Song>,
    colors: JunColors,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // 读 version：换/删封面后列表即时刷新。
    val version = CustomCoverStore.version
    val entries = remember(version) { CustomCoverStore.entries() }
    val totalBytes = remember(version, entries) { CustomCoverStore.totalBytes() }
    val titleByKey = remember(songs) { songs.associate { it.filePath to it.title } }
    var notice by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .padding(horizontal = 16.dp, vertical = 26.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.surface)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("已替换封面", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text(
                            "${entries.size} 张 · 占用 ${formatBytes(totalBytes)} · 只存在本机",
                            color = colors.subText, fontSize = 11.sp
                        )
                    }
                    Text(
                        "关闭",
                        color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            // 按压缩放层在 clip 之前，圆角区跟文字一起软
                            .junPressable(feedback = PressFeedback.NONE) { onDismiss() }
                            .clip(JunShape.item)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("还没有替换过封面", color = colors.subText, fontSize = 12.sp)
                    }
                } else {
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        entries.forEach { (key, path) ->
                            val name = titleByKey[key] ?: key.substringAfterLast('/')
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        name,
                                        color = colors.text,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${runCatching { java.io.File(path).length() }.getOrDefault(0L) / 1024} KB",
                                        color = colors.subText, fontSize = 10.sp
                                    )
                                }
                                Text(
                                    "恢复",
                                    color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .junPressable(feedback = PressFeedback.NONE) {
                                            CustomCoverStore.clear(key)
                                            notice = "已恢复原封面"
                                        }
                                        .clip(JunShape.item)
                                        .background(colors.primary.copy(alpha = 0.12f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
                if (notice.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(notice, color = colors.primary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .junPressable(feedback = PressFeedback.NONE) {
                                val removed = CustomCoverStore.pruneOrphans(context)
                                notice = if (removed > 0) "已清理 $removed 个无主文件" else "没有可清理的文件"
                            }
                            .clip(JunShape.item)
                            .background(colors.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("清理无主文件", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .junPressable(feedback = PressFeedback.NONE) {
                                if (entries.isNotEmpty()) {
                                    CustomCoverStore.clearAll()
                                    notice = "已全部恢复原封面"
                                }
                            }
                            .clip(JunShape.item)
                            .background(colors.surface.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("全部恢复", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
