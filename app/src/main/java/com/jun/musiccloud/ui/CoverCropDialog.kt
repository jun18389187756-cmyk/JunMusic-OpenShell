package com.jun.musiccloud.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.readableTextOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自定义封面裁剪：正方形预览 + 缩放 / 水平 / 垂直三个滑块。
 *
 * 不引入第三方裁剪库：只需正方形头像式裁切，滑块比手势更可控，也更适合单手操作。
 * 真正落盘在 [CustomCoverStore.saveCropped] 里按同一组参数计算裁剪窗口。
 */
@Composable
internal fun CoverCropDialog(
    uri: Uri,
    colors: JunColors,
    onDismiss: () -> Unit,
    /** 保存回调：参数为 zoom(1..3)、offsetX(-1..1)、offsetY(-1..1)，返回是否成功 */
    onSave: suspend (Float, Float, Float) -> Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(uri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
        }
        if (bitmap == null) error = "这张图片读不出来，换一张试试"
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .clip(JunShape.card)
                .background(colors.card)
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("裁剪封面", color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text(
                "拖动滑块调整构图，保存后当前歌曲的列表、播放卡和全部歌词舞台一起换图",
                color = colors.subText, fontSize = 11.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .size(260.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bmp,
                        contentDescription = "封面预览",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                // 偏移与最终裁剪方向一致：正 offset = 取右侧 / 下侧
                                translationX = -offsetX * (size.width * (zoom - 1f) / 2f)
                                translationY = -offsetY * (size.height * (zoom - 1f) / 2f)
                            }
                    )
                } else {
                    Text("读取中…", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
                // 三分线，帮助构图
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val line = Color.White.copy(alpha = 0.22f)
                    for (i in 1..2) {
                        val x = size.width * i / 3f
                        val y = size.height * i / 3f
                        drawLine(line, androidx.compose.ui.geometry.Offset(x, 0f),
                            androidx.compose.ui.geometry.Offset(x, size.height), 1f)
                        drawLine(line, androidx.compose.ui.geometry.Offset(0f, y),
                            androidx.compose.ui.geometry.Offset(size.width, y), 1f)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            CropSlider(
                label = "缩放 · ${"%.2f".format(zoom)}×",
                value = zoom,
                range = 1f..3f,
                colors = colors
            ) { zoom = it }
            CropSlider(
                label = "水平 · ${(offsetX * 100).toInt()}",
                value = offsetX,
                range = -1f..1f,
                colors = colors
            ) { offsetX = it }
            CropSlider(
                label = "垂直 · ${(offsetY * 100).toInt()}",
                value = offsetY,
                range = -1f..1f,
                colors = colors
            ) { offsetY = it }

            if (error.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(error, color = colors.primary, fontSize = 11.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(42.dp)
                        // 按压层在 clip/background 之前，底色跟着按钮一起缩
                        .junPressable(enabled = !busy, feedback = PressFeedback.NONE) { onDismiss() }
                        .clip(JunShape.item)
                        .background(colors.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Text("取消", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(42.dp)
                        .junPressable(enabled = !busy && bitmap != null) {
                            busy = true
                            error = ""
                            scope.launch {
                                val ok = runCatching { onSave(zoom, offsetX, offsetY) }.getOrDefault(false)
                                busy = false
                                if (ok) onDismiss() else error = "保存失败，换一张图再试"
                            }
                        }
                        .clip(JunShape.item)
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (busy) "保存中…" else "保存",
                        color = colors.primary.readableTextOn(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

@Composable
private fun CropSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    colors: JunColors,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = colors.subText, fontSize = 11.sp)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.surface
            )
        )
    }
}
