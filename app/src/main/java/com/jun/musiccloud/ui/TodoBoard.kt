package com.jun.musiccloud.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.data.ListenTodo
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.LocalJunUiFamily
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.readableTextOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 「听歌待办」：一个极轻的备忘清单，同时是空状态引导的落地场景。
 *
 * 空列表时不给一句干巴巴的「暂无数据」，而是：
 *  - 一叠会缓慢起伏的唱片插画（告诉用户这里"应该"有什么）；
 *  - 一条流动虚线从插画指向右下角加号（告诉用户下一步点哪）；
 *  - 加号持续脉动（在视觉上主动伸手）。
 * 添加时的动作连贯到底：内容从加号飞进列表 → 插画淡出 → 弹出成功提示，
 * 让"我刚做了一件有效的事"这件事被明确地反馈一次。
 */
@Composable
internal fun TodoBoard(
    colors: JunColors,
    todos: List<ListenTodo>,
    onAdd: (String) -> Unit,
    onToggle: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onClearDone: () -> Unit,
    onDismiss: () -> Unit,
    /** 「搜一下」：把这条待办当成搜索词直接搜，让备忘闭环成行动 */
    onSearch: (String) -> Unit = {}
) {
    // 本容器在根坐标系里的位置：飞行动画要在「根坐标 → 本容器坐标」之间换算
    var hostRect by remember { mutableStateOf(Rect.Zero) }
    // 加号与列表顶部的落点（根坐标）
    var fabRect by remember { mutableStateOf(Rect.Zero) }
    var listTopCenter by remember { mutableStateOf(Offset.Zero) }

    var inputOpen by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }

    // 飞行中的条目：从加号飞向列表顶部
    var flight by remember { mutableStateOf<FlightState?>(null) }
    val flightProgress = remember { Animatable(0f) }
    // 成功提示
    var successText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(flight) {
        val f = flight ?: return@LaunchedEffect
        flightProgress.snapTo(0f)
        flightProgress.animateTo(1f, tween(520, easing = EaseInOutCubic))
        onAdd(f.text)
        flight = null
        successText = f.text
    }
    LaunchedEffect(successText) {
        if (successText == null) return@LaunchedEffect
        delay(1600)
        successText = null
    }

    val isEmpty = todos.isEmpty()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .onGloballyPositioned { hostRect = it.boundsInRoot() }
    ) {
        Column(Modifier.fillMaxSize()) {
            // ===== 顶栏 =====
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        // 和全 App 同一套按压弹簧：缩放层放在 clip/background 之前，底色才跟着缩
                        .junPressable { onDismiss() }
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(16.dp)) {
                        val s = size.minDimension
                        drawLine(
                            colors.text, Offset(s * 0.72f, s * 0.18f), Offset(s * 0.28f, s * 0.5f),
                            strokeWidth = s * 0.14f, cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                        drawLine(
                            colors.text, Offset(s * 0.28f, s * 0.5f), Offset(s * 0.72f, s * 0.82f),
                            strokeWidth = s * 0.14f, cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "听歌待办",
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        if (isEmpty) "还没记下任何想听的" else "${todos.count { !it.done }} 条待听 · 共 ${todos.size} 条",
                        color = colors.subText,
                        fontSize = 11.5.sp
                    )
                }
                if (todos.any { it.done }) {
                    Text(
                        "清除已完成",
                        color = colors.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .junPressable { onClearDone() }
                            .clip(JunShape.item)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }

            // ===== 内容区 =====
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // 空状态：插画 + 虚线箭头（列表有内容时整体淡出）
                androidx.compose.animation.AnimatedVisibility(
                    visible = isEmpty,
                    enter = fadeIn(tween(300)),
                    exit = fadeOut(tween(340))
                ) {
                    EmptyGuide(
                        colors = colors,
                        // 传根坐标：虚线终点要落在加号上，而 EmptyGuide 自己并不在根原点
                        fabCenterRoot = fabRect.center,
                        onQuickAdd = { preset ->
                            inputText = preset
                            inputOpen = true
                        }
                    )
                }

                if (!isEmpty) {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                val r = it.boundsInRoot()
                                listTopCenter = Offset(r.center.x, r.top + 60f) - hostRect.topLeft
                            }
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item { Spacer(Modifier.height(4.dp)) }
                        items(todos, key = { it.id }) { todo ->
                            // 首次组合也播入场动画：MutableTransitionState 起始为 false 再置 true
                            val visible = remember { MutableTransitionState(false) }.apply { targetState = true }
                            androidx.compose.animation.AnimatedVisibility(
                                visibleState = visible,
                                enter = fadeIn(tween(220)) + slideInVertically(
                                    animationSpec = tween(280, easing = FastOutSlowInEasing),
                                    initialOffsetY = { -it / 3 }
                                )
                            ) {
                                TodoRow(
                                    todo = todo,
                                    colors = colors,
                                    onToggle = { Haptics.tap(); onToggle(todo.id) },
                                    onRemove = { Haptics.tap(); onRemove(todo.id) },
                                    onSearch = { Haptics.tap(); onSearch(todo.text) }
                                )
                            }
                        }
                        item { Spacer(Modifier.height(96.dp)) }
                    }
                }
            }
        }

        // ===== 输入条（点加号后从底部升起）=====
        androidx.compose.animation.AnimatedVisibility(
            visible = inputOpen,
            enter = slideInVertically(
                animationSpec = tween(280, easing = EaseOutCubic),
                initialOffsetY = { it }
            ) + fadeIn(tween(200)),
            exit = fadeOut(tween(160)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 88.dp)
        ) {
            AddInputBar(
                colors = colors,
                text = inputText,
                onTextChange = { inputText = it },
                onConfirm = {
                    val t = inputText.trim()
                    if (t.isNotEmpty() && flight == null) {
                        // 起点=加号中心，终点=列表首行位置（列表为空时用列表区顶部）
                        val from = if (fabRect == Rect.Zero) Offset.Zero else fabRect.center - hostRect.topLeft
                        val to = if (listTopCenter == Offset.Zero) {
                            Offset(hostRect.width * 0.5f, hostRect.height * 0.34f)
                        } else listTopCenter
                        flight = FlightState(text = t, from = from, to = to)
                        inputText = ""
                        inputOpen = false
                        Haptics.click()
                    }
                },
                onCancel = { inputOpen = false; inputText = "" }
            )
        }

        // ===== 右下角加号（持续脉动）=====
        val pulseTarget = if (isEmpty) 1f else 0f // 有内容后停止脉动，不再抢视线
        PulsingAddButton(
            colors = colors,
            pulsing = pulseTarget > 0f,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 20.dp)
                .onGloballyPositioned { fabRect = it.boundsInRoot() },
            onClick = {
                Haptics.click()
                inputOpen = !inputOpen
            }
        )

        // ===== 飞行中的条目：从加号飞入列表 =====
        flight?.let { f ->
            val p = flightProgress.value
            val x = f.from.x + (f.to.x - f.from.x) * p
            val y = f.from.y + (f.to.y - f.from.y) * p
            Box(
                Modifier
                    .offset { IntOffset((x - 90.dp.toPx() / 2f).roundToInt(), (y - 22.dp.toPx()).roundToInt()) }
                    .widthIn(max = 190.dp)
                    .graphicsLayer {
                        scaleX = 1f - 0.25f * p
                        scaleY = 1f - 0.25f * p
                        alpha = 1f - 0.15f * p
                    }
                    .clip(JunShape.item)
                    .background(colors.primary)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text(
                    f.text,
                    color = colors.primary.readableTextOn(),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        // ===== 成功提示 =====
        androidx.compose.animation.AnimatedVisibility(
            visible = successText != null,
            enter = fadeIn(tween(180)) + slideInVertically(tween(260, easing = EaseOutCubic)) { it },
            exit = fadeOut(tween(220)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp)
        ) {
            Row(
                Modifier
                    .clip(JunShape.chip)
                    .background(colors.card)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Canvas(Modifier.size(14.dp)) {
                    val s = size.minDimension
                    drawLine(
                        colors.primary, Offset(s * 0.12f, s * 0.52f), Offset(s * 0.4f, s * 0.8f),
                        strokeWidth = s * 0.16f, cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    drawLine(
                        colors.primary, Offset(s * 0.4f, s * 0.8f), Offset(s * 0.92f, s * 0.2f),
                        strokeWidth = s * 0.16f, cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "已加入待办",
                    color = colors.text,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private data class FlightState(val text: String, val from: Offset, val to: Offset)

/** 空状态：浮动插画 + 流动虚线箭头指向右下角加号 */
@Composable
private fun EmptyGuide(
    colors: JunColors,
    /** 加号中心（根坐标系）：本组件自己不在根原点，换算后才知道箭头该指到哪 */
    fabCenterRoot: Offset,
    onQuickAdd: (String) -> Unit
) {
    var selfRect by remember { mutableStateOf(Rect.Zero) }
    // 插画缓慢起伏：3.6 秒一轮，幅度 10dp
    val t by rememberInfiniteTransition(label = "todoFloat").animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing)),
        label = "float"
    )
    // 虚线流动：相位持续推进，视觉上像"往加号那边流过去"
    val phase by rememberInfiniteTransition(label = "dashFlow").animateFloat(
        initialValue = 0f,
        targetValue = 40f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "dash"
    )

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { selfRect = it.boundsInRoot() }
    ) {
        val floatPx = with(androidx.compose.ui.platform.LocalDensity.current) {
            (kotlin.math.sin(t) * 10f).dp.toPx()
        }
        val fabCenter = if (selfRect == Rect.Zero || fabCenterRoot == Offset.Zero) {
            Offset.Zero
        } else {
            fabCenterRoot - selfRect.topLeft
        }

        // 虚线 + 箭头（在插画之下，先画）
        Canvas(Modifier.fillMaxSize()) {
            val start = Offset(size.width * 0.5f, size.height * 0.46f)
            val end = if (fabCenter == Offset.Zero) {
                Offset(size.width * 0.82f, size.height * 0.86f)
            } else {
                Offset(fabCenter.x - 34.dp.toPx(), fabCenter.y - 34.dp.toPx())
            }
            val path = Path().apply {
                moveTo(start.x, start.y)
                cubicTo(
                    start.x + size.width * 0.28f, start.y + size.height * 0.06f,
                    end.x - size.width * 0.1f, end.y - size.height * 0.22f,
                    end.x, end.y
                )
            }
            drawPath(
                path = path,
                color = colors.primary.copy(alpha = 0.75f),
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 9.dp.toPx()), phase)
                )
            )
            // 箭头：两条短线，指向曲线末端的切线方向（近似：右上 → 左下）
            val arrowLen = 11.dp.toPx()
            drawLine(
                colors.primary, end, Offset(end.x - arrowLen * 0.95f, end.y + arrowLen * 0.15f),
                strokeWidth = 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawLine(
                colors.primary, end, Offset(end.x - arrowLen * 0.15f, end.y - arrowLen * 0.95f),
                strokeWidth = 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }

        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(28.dp))
            // ===== 插画：一叠唱片卡 + 音符 =====
            Canvas(
                Modifier
                    .size(190.dp)
                    .graphicsLayer { translationY = floatPx }
            ) {
                val w = size.width
                val h = size.height
                // 三张叠放的卡片（从后往前）
                for (i in 0 until 3) {
                    val dy = i * h * 0.045f
                    val alpha = 0.25f + i * 0.22f
                    drawRoundRect(
                        color = (if (i == 2) colors.primary else colors.secondary).copy(alpha = alpha),
                        topLeft = Offset(w * 0.12f + i * w * 0.03f, h * 0.2f + dy),
                        size = Size(w * 0.7f, h * 0.42f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f, w * 0.08f)
                    )
                }
                // 最上面一张唱片：同心圆
                val cx = w * 0.45f
                val cy = h * 0.44f
                val r = w * 0.2f
                drawCircle(color = colors.card, radius = r, center = Offset(cx, cy))
                for (i in 0 until 3) {
                    drawCircle(
                        color = colors.primary.copy(alpha = 0.22f + i * 0.12f),
                        radius = r * (0.85f - i * 0.2f),
                        center = Offset(cx, cy),
                        style = Stroke(width = w * 0.012f)
                    )
                }
                drawCircle(color = colors.primary, radius = r * 0.2f, center = Offset(cx, cy))
                // 音符
                val nx = w * 0.82f
                val ny = h * 0.26f
                val ns = w * 0.09f
                drawCircle(color = colors.secondary, radius = ns * 0.9f, center = Offset(nx, ny))
                drawLine(
                    colors.secondary, Offset(nx + ns * 0.8f, ny - ns * 0.15f), Offset(nx + ns * 0.8f, ny - ns * 3.1f),
                    strokeWidth = ns * 0.45f, cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                drawLine(
                    colors.secondary, Offset(nx + ns * 0.8f, ny - ns * 3.1f), Offset(nx + ns * 2.6f, ny - ns * 2.3f),
                    strokeWidth = ns * 0.45f, cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "先记下来，回头慢慢听",
                color = colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "刷到的、被安利的、突然想起的——\n现在不用切歌去找，点右下角加号记一句就行。",
                color = colors.subText,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            // 两个一键预填：降低"空白输入框"的起步成本
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("朋友推荐的一张专辑", "直播里听到的 BGM").forEach { preset ->
                    Text(
                        preset,
                        color = colors.primary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .junPressable { onQuickAdd(preset) }
                            .clip(JunShape.chip)
                            .background(colors.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** 右下角加号：两圈扩散的脉动环 + 加号本体 */
@Composable
private fun PulsingAddButton(
    colors: JunColors,
    pulsing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val t by rememberInfiniteTransition(label = "fabPulse").animateFloat(
        initialValue = 0f,
        targetValue = if (JunMotion.reduced) 0f else 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "pulse"
    )
    Box(modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (pulsing) {
            for (k in 0 until 2) {
                val p = (t + k * 0.5f) % 1f
                Box(
                    Modifier
                        .size(56.dp)
                        .graphicsLayer {
                            scaleX = 1f + p * 0.55f
                            scaleY = 1f + p * 0.55f
                            alpha = (1f - p) * 0.4f
                        }
                        .clip(CircleShape)
                        .background(colors.primary)
                )
            }
        }
        Box(
            Modifier
                .size(56.dp)
                .junPressable(feedback = PressFeedback.NONE) { onClick() }
                .clip(CircleShape)
                .background(colors.primary),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(22.dp)) {
                val s = size.minDimension
                val c = colors.primary.readableTextOn()
                drawLine(c, Offset(s * 0.5f, s * 0.16f), Offset(s * 0.5f, s * 0.84f), strokeWidth = s * 0.14f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(c, Offset(s * 0.16f, s * 0.5f), Offset(s * 0.84f, s * 0.5f), strokeWidth = s * 0.14f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

/** 底部输入条：加号展开后的输入区 */
@Composable
private fun AddInputBar(
    colors: JunColors,
    text: String,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    Row(
        Modifier
            .fillMaxWidth()
            .junSurface(GlassLevel.SHEET, colors, JunShape.card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 40.dp)
                .clip(JunShape.item)
                .background(if (colors.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (text.isEmpty()) {
                Text("想听什么？记一句", color = colors.subText, fontSize = 13.sp)
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = LocalJunUiFamily.current, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.fillMaxWidth().focusRequester(requester)
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .junPressable(enabled = text.isNotBlank(), feedback = PressFeedback.NONE) { onConfirm() }
                .clip(JunShape.item)
                .background(if (text.isBlank()) colors.subText.copy(alpha = 0.18f) else colors.primary)
                .padding(horizontal = 16.dp, vertical = 11.dp)
        ) {
            Text(
                "记下",
                color = if (text.isBlank()) colors.subText else colors.primary.readableTextOn(),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "取消",
            color = colors.subText,
            fontSize = 12.sp,
            modifier = Modifier
                .junPressable(feedback = PressFeedback.NONE) { onCancel() }
                .clip(JunShape.item)
                .padding(horizontal = 6.dp, vertical = 8.dp)
        )
    }
}

/** 一条待办：圆圈勾选 + 文本 + 删除 */
@Composable
private fun TodoRow(
    todo: ListenTodo,
    colors: JunColors,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onSearch: () -> Unit = {}
) {
    val alpha by animateFloatAsState(
        targetValue = if (todo.done) 0.5f else 1f,
        animationSpec = tween(220),
        label = "todoAlpha"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .junPressableRow(feedback = PressFeedback.NONE) { onToggle() }
            .junSurface(GlassLevel.ROW, colors, JunShape.card)
            .padding(horizontal = 14.dp, vertical = 13.dp)
            .semantics { contentDescription = "待办：${todo.text}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (todo.done) colors.primary else Color.Transparent)
                .then(
                    if (!todo.done) {
                        Modifier.border(width = 1.6.dp, color = colors.primary, shape = CircleShape)
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (todo.done) {
                Canvas(Modifier.size(12.dp)) {
                    val s = size.minDimension
                    val c = colors.primary.readableTextOn()
                    drawLine(c, Offset(s * 0.1f, s * 0.52f), Offset(s * 0.4f, s * 0.84f), strokeWidth = s * 0.17f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(c, Offset(s * 0.4f, s * 0.84f), Offset(s * 0.92f, s * 0.16f), strokeWidth = s * 0.17f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            todo.text,
            color = colors.text,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            textDecoration = if (todo.done) TextDecoration.LineThrough else null
        )
        // 「搜一下」：备忘闭环成行动——把这句待听直接当成搜索词
        Box(
            Modifier
                .size(28.dp)
                .junPressable(feedback = PressFeedback.NONE) { onSearch() }
                .clip(CircleShape)
                .semantics { contentDescription = "搜索：${todo.text}" },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(14.dp)) {
                val s = size.minDimension
                val c = colors.subText
                // 放大镜：圆 + 手柄
                drawCircle(c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = s * 0.14f), radius = s * 0.32f, center = Offset(s * 0.4f, s * 0.4f))
                drawLine(c, Offset(s * 0.62f, s * 0.62f), Offset(s * 0.9f, s * 0.9f), strokeWidth = s * 0.14f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(28.dp)
                .junPressable(feedback = PressFeedback.NONE) { onRemove() }
                .clip(CircleShape)
                .semantics { contentDescription = "删除这条待办" },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(12.dp)) {
                val s = size.minDimension
                drawLine(colors.subText, Offset(s * 0.12f, s * 0.12f), Offset(s * 0.88f, s * 0.88f), strokeWidth = s * 0.15f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(colors.subText, Offset(s * 0.88f, s * 0.12f), Offset(s * 0.12f, s * 0.88f), strokeWidth = s * 0.15f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

