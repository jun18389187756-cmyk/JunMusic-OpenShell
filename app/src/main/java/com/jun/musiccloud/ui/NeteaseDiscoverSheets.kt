package com.jun.musiccloud.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.data.Playlists
import com.jun.musiccloud.data.Song
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.readableOn

/**
 * 网易云「歌单」选择弹层（第二个登录后可用）。
 *
 * 与其它弹层同构（[JunSheet] + 拖把手），风格完全一致：
 * 顶部「每日推荐」入口 + 「我的歌单」 + 「推荐歌单」两组列表，点任意歌单进入曲目弹层。
 * 不登录也能正常使用 App；此处未登录时由调用方先行提示登录。
 */
@Composable
internal fun NeteasePlaylistSheet(
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit,
    onOpenDaily: () -> Unit,
    onOpenLiked: () -> Unit = {},
    onSelect: (id: String, name: String) -> Unit,
    /**
     * R13：登录入口从"进门拦截"下移到"列表里的一行"。
     *
     * 之前未登录点首页「更多」直接被 VM 弹到扫码页，公开歌单一条都看不到（用户明确不满）。
     * 现在广场对所有内容敞开，登录只是这里的一个动作行——点了才提示，不影响读公开数据。
     */
    onLogin: () -> Unit = {},
    /**
     * 6.1.2：「我的歌单」的显式刷新（下拉之外给一颗明面上的钮）。
     *
     * 只靠 TTL 不够：用户刚在网易云建完歌单就切回来，30 秒窗口内仍然拿到旧列表，
     * 看到的就是"我建的那单不存在"。这颗钮会先把那条内存缓存清掉再打网络。
     */
    onRefreshMine: () -> Unit = {},
    /** 上一次拉取失败（未授权 / 网络）：这一排要说话，不能假装"账号就是这样" */
    mineFailed: Boolean = false,
    mineNotice: String = ""
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val drag = rememberSheetDragState()

    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        panelModifier = Modifier.fillMaxHeight(0.78f),
        contentModifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        SheetDragHandle(drag, onDismiss, colors.card)
        Spacer(Modifier.height(6.dp))

        // 6.7 修：推荐/搜索歌单详情返回按钮——广场列表也补左上角返回入口，
        // 与曲目详情同一枚 GlassBackChip；点击 = 关闭弹层。
        // 副标题（账号信息）给 weight(1f, fill=false) + ellipsis，加了按钮也不溢出。
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassBackChip(colors, onClick = onDismiss)
            Spacer(Modifier.width(10.dp))
            // R13：大标题不再写单一平台名（用户："不能后面提示是网易云的"）。
            // 这一屏是"公开歌单广场 + 我的歌单"，来源是聚合的，标题按功能命名更准确；
            // 账号信息挪到副标题，登录了才显示"网易云账号 · 昵称"——那才是事实描述。
            Text("歌单广场", color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            Text(
                if (state.neteaseName.isBlank()) "聚合多平台 · 免登录" else "网易云账号 · ${state.neteaseName}",
                color = colors.subText, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        Spacer(Modifier.height(10.dp))

        // 不显示转圈：快捷入口立即渲染，歌单区拉到后自然补上（接口有内存缓存，通常瞬间返回）

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // 未登录也把这个账号动作摆在"能点的动作"上，而不是摆在整个列表门口
            if (!state.neteaseLoggedIn) {
                item {
                    NetRow("登录网易云账号", "同步我的歌单", colors) { Haptics.tap(); onLogin() }
                }
            }
            // 快捷入口：每日推荐 / 我喜欢的音乐
            item {
                NetRow("每日推荐", "猜你喜欢 · 每日更新", colors) { Haptics.tap(); onOpenDaily() }
            }
            item {
                NetRow("我喜欢的音乐", "账号红心", colors) { Haptics.tap(); onOpenLiked() }
            }
            // 6.1.2：「我的歌单」这一节自己说话。
            // 之前只有"有没有数据"两种画法：拉到空列表就整节不画，用户分不清
            // 「账号真没有歌单」「cookie 过期」「网断了」，只能靠退出重登碰运气。
            if (state.neteaseLoggedIn) {
                item { MinePlaylistsHeader(colors, mineFailed, mineNotice, onRefreshMine) }
            }
            if (state.neteaseMyPlaylists.isNotEmpty()) {
                items(state.neteaseMyPlaylists, key = { "my-${it.id}" }) { p ->
                    NetRow(p.name, null, colors) { Haptics.tap(); onSelect(p.id, p.name) }
                }
            } else if (state.neteaseLoggedIn && !mineFailed) {
                // 真的一个歌单都没有：说清"去网易云建完点刷新"，而不是整节消失
                item {
                    Text(
                        "这个账号还没有歌单。在网易云建好之后点上面的「刷新」就能收到。",
                        color = colors.subText, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
            if (state.neteaseRecPlaylists.isNotEmpty()) {
                item { NetSectionLabel("推荐歌单", colors) }
                items(state.neteaseRecPlaylists, key = { "rec-${it.id}" }) { p ->
                    NetRow(p.name, null, colors) { Haptics.tap(); onSelect(p.id, p.name) }
                }
            }
            if (state.neteaseMyPlaylists.isEmpty() && state.neteaseRecPlaylists.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        Text("暂时没有歌单，稍后再试", color = colors.subText, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * 网易云曲目弹层：展示「每日推荐 / 某个歌单」的歌曲。
 * 行组件与首页曲库、搜索页完全同款（[SongRow]），不改变任何既有布局。
 */
@Composable
internal fun NeteaseSongListSheet(
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit,
    onPlayAll: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onToggleLike: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    /** 6.6.9：把整份歌单/榜单存进自己的歌单（用户点名要的功能） */
    onSaveAllToPlaylist: () -> Unit = {},
    /** 批量选择上下文：全选范围 = 当前这份榜单/歌单的歌 */
    select: ListSelect = ListSelect()
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val drag = rememberSheetDragState()
    val songs = state.neteaseListSongs

    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        panelModifier = Modifier.fillMaxHeight(0.82f),
        contentModifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        SheetDragHandle(drag, onDismiss, colors.card)
        Spacer(Modifier.height(6.dp))

        // 6.7 修：推荐/搜索歌单详情返回按钮——左上角补一枚与「我的歌单」同款的
        // CHIP 玻璃返回钮（纯图标圆钮，省出空间给标题）；点击 = 关闭弹层，
        // 顶部 SheetDragHandle 的下滑关闭照旧保留。标题用 weight(1f, fill=false)
        // + ellipsis，加了按钮也不会把标题挤没，也不会让「N 首」被顶出屏幕。
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassBackChip(colors, onClick = onDismiss)
            Spacer(Modifier.width(10.dp))
            Text(
                state.neteaseListTitle, color = onSheet, fontSize = 20.sp,
                fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (songs.isNotEmpty()) "${songs.size} 首" else "",
                color = colors.subText, fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            state.neteaseListSubtitle, color = colors.subText, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))

        // 列表加载中不再整屏转圈：有缓存内容就先显示，首次加载用静态骨架占位，
        // 「打开」这个动作看起来是瞬间完成的，不会出现明显的等待。
        if (state.neteaseListLoading && songs.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                repeat(7) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(JunShape.item)
                            .background(colors.surface.copy(alpha = 0.55f))
                    )
                }
            }
            return@JunSheet
        }

        if (state.neteaseListNotice.isNotBlank() || songs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 明面上的「全选」入口（长按才能进多选这件事没人知道），范围 = 这份榜单/歌单
                if (songs.isNotEmpty()) {
                    val allChecked = select.active && songs.all { it.id in select.selectedIds }
                    SelectAllEntry(colors, small = true, checked = allChecked) {
                        if (allChecked) select.onExit()
                        else select.onStart(state.neteaseListTitle.ifBlank { "聚合多平台歌单" }, songs)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    state.neteaseListNotice,
                    color = colors.subText, fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                if (songs.isNotEmpty()) {
                    // 6.6.9：整单收藏（用户："点开之后都能添加到自己的歌单里"）
                    Box(
                        Modifier
                            .junPressable(feedback = PressFeedback.CLICK) { onSaveAllToPlaylist() }
                            .clip(JunShape.item)
                            .background(colors.card)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("存到我的歌单", color = onSheet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            // 和全 App 同一套按压弹簧（缩放层必须在 clip/background 之前）
                            .junPressable(feedback = PressFeedback.CLICK) { onPlayAll() }
                            .clip(JunShape.item)
                            .background(colors.primary.copy(alpha = 0.18f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("播放全部", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            itemsIndexed(songs, key = { i, song -> "nps-$i-${song.id}" }) { _, song ->
                SongRow(
                    song = song,
                    active = song.id == state.currentSong?.id,
                    colors = colors,
                    rowSize = state.rowSize,
                    showCover = state.showRowCover,
                    showArtist = state.showRowArtist,
                    showPlaylist = state.showRowPlaylist,
                    showLikedBadge = state.showLikedBadge,
                    liked = song.id in state.likedSongIds,
                    playlistNames = if (state.showRowPlaylist) Playlists.playlistsOfSong(song.id) else emptyList(),
                    selectMode = select.active,
                    isSelected = song.id in select.selectedIds,
                    onClick = { if (select.active) select.onToggle(song.id) else onPlaySong(song) },
                    onLongPress = { if (select.active) select.onExit() else onAddToPlaylist(song) },
                    onToggleLike = { onToggleLike(song) }
                )
            }
        }
    }
}

/**
 * 6.7 修：推荐/搜索歌单详情返回按钮。
 *
 * 全 App「歌单详情」弹层共用的液态玻璃返回钮：GlassLevel.CHIP 玻璃底 + 返回箭头。
 * [label] 非空时是带文字的胶囊（我的歌单详情），为空时是纯图标圆钮（要和标题挤一行
 * 的曲目详情/广场），两种形态共用同一套材质：按压缩到 0.92 并变暗、松手 spring 回弹，
 * 进入弹层时淡入 + 从左侧 12dp 归位（220ms）。推荐歌单 / 搜索歌单 / 排行榜曲目详情、
 * 歌单广场、我的歌单详情三处都调它，避免 6.6 那种「只在一个界面内联、其它界面仍然没有」。
 * 点击回调 = 各自弹层的关闭动作；顶部 SheetDragHandle 的下滑关闭照旧保留。
 *
 * @param label 为 null 时只画箭头（圆形），用于标题行空间紧张的详情页
 * @param enterKey 变化时重新播放入场动效（例如切到另一份歌单）
 */
@Composable
internal fun GlassBackChip(
    colors: JunColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enterKey: Any? = Unit
) {
    var pressed by remember(enterKey) { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "backBtnScale"
    )
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.72f else 1f,
        animationSpec = tween(120),
        label = "backBtnAlpha"
    )
    val enter = remember(enterKey) { Animatable(0f) }
    LaunchedEffect(enterKey) {
        enter.snapTo(0f)
        enter.animateTo(1f, tween(220))
    }
    // 回调可能在重组时换新实例，用 rememberUpdatedState 保证手势里拿到的是最新那份
    val currentOnClick by rememberUpdatedState(onClick)
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = pressAlpha * enter.value
                translationX = (enter.value - 1f) * 12.dp.toPx()
            }
            // 自绘按压手势：按下即缩、松手回弹，手指滑走不当成点击（与弹层拖动共存）
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    val start = down.position
                    val slop = viewConfiguration.touchSlop
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if ((change.position - start).getDistance() > slop) moved = true
                            change.consume()
                        }
                        if (event.changes.all { !it.pressed }) break
                    }
                    pressed = false
                    if (!moved) {
                        Haptics.tap()
                        currentOnClick()
                    }
                }
            }
            .junSurface(
                level = GlassLevel.CHIP,
                colors = colors,
                shape = RoundedCornerShape(50.dp),
                solid = colors.card.copy(alpha = 0.55f)
            )
            .padding(
                horizontal = if (label != null) 12.dp else 9.dp,
                vertical = if (label != null) 7.dp else 9.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = label ?: "返回",
            tint = colors.primary,
            modifier = Modifier.size(if (label != null) 16.dp else 18.dp)
        )
        if (label != null) {
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                color = colors.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

/** 网易云弹层里的小节标签 */
@Composable
private fun NetSectionLabel(text: String, colors: JunColors) {
    Text(
        text,
        color = colors.subText, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)
    )
}

/**
 * 「我的歌单」这一节的表头：标题 + 一颗明面上的刷新钮，失败时把原因写在下面。
 *
 * 为什么要有这颗钮（6.2）：网易云那边新建完歌单切回 App，接口缓存还有 30 秒窗口，
 * 只看 TTL 用户仍会以为「我建的那单不存在」，只能退出重登（换 cookie 才强制走网络）。
 * 刷新钮会先清掉那条内存缓存再打网络，是「当场看到结果」的唯一可靠路径。
 *
 * 为什么要写失败原因：以前拉失败与「这个账号真没有歌单」画的是同一种东西，
 * cookie 过期时界面看着像加载完成了，用户没有任何线索去重新登录。
 */
@Composable
private fun MinePlaylistsHeader(
    colors: JunColors,
    failed: Boolean,
    notice: String,
    onRefresh: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "我的歌单",
                color = onSheet, fontSize = 14.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f)
            )
            // 刷新：文字按钮够用，这里不需要一颗占位置的图标钮
            Row(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable { Haptics.tap(); onRefresh() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    JunIcons.Refresh,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("刷新", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (failed) {
            Text(
                notice.ifBlank { "我的歌单没拉到，点右上角「刷新」重试" },
                color = colors.dangerText, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 网易云弹层里的通用行：与「我的」抽屉的 DrawerRow 同一种视觉语言
 * （左标题、右侧细箭头），保证风格一致。
 */
@Composable
internal fun NetRow(
    label: String,
    value: String?,
    colors: JunColors,
    /** 歌手头像 / 专辑封面 / 歌单封面；为空时绘制占位图 */
    cover: String = "",
    /** 占位图种子（保证同一行每次颜色一致） */
    coverId: Long = 0L,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .junPressableRow { onClick() }
            .clip(RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SongArtwork(
            filePath = cover.takeIf { it.isNotBlank() },
            seedId = coverId,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp)),
            isDark = colors.isDark,
            noteIcon = true
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (value != null) {
            Text(value, color = colors.subText, fontSize = 12.sp, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.size(10.dp)) {
            val c = colors.subText.copy(alpha = 0.6f)
            drawLine(
                c, Offset(size.width * 0.2f, size.height * 0.1f),
                Offset(size.width * 0.72f, size.height * 0.5f),
                strokeWidth = size.minDimension * 0.16f, cap = StrokeCap.Round
            )
            drawLine(
                c, Offset(size.width * 0.72f, size.height * 0.5f),
                Offset(size.width * 0.2f, size.height * 0.9f),
                strokeWidth = size.minDimension * 0.16f, cap = StrokeCap.Round
            )
        }
    }
}

/**
 * 网易云「排行榜」弹层：列出所有官方榜单（飙升榜 / 热歌榜 / 新歌榜…）。
 * 与歌单选单弹层同构，点任意榜单进曲目弹层（复用 [NeteaseSongListSheet]）。
 * 排行榜无需登录即可查看。
 */
@Composable
internal fun NeteaseToplistSheet(
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit,
    onSelect: (id: String, name: String) -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val drag = rememberSheetDragState()

    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        panelModifier = Modifier.fillMaxHeight(0.82f),
        contentModifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        SheetDragHandle(drag, onDismiss, colors.card)
        Spacer(Modifier.height(6.dp))

        // 6.7 修：推荐/搜索歌单详情返回按钮——排行榜列表同构弹层也补左上角返回入口，
        // 与曲目详情同一枚 GlassBackChip；点击 = 关闭弹层。
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassBackChip(colors, onClick = onDismiss)
            Spacer(Modifier.width(10.dp))
            Text("排行榜", color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            Text(
                "聚合多平台 · 官方榜单",
                color = colors.subText, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(state.neteaseToplists, key = { "top-${it.id}" }) { p ->
                NetRow(p.name, null, colors) { Haptics.tap(); onSelect(p.id, p.name) }
            }
            if (state.neteaseToplists.isEmpty() && state.neteaseToplistLoading) {
                item {
                    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = colors.primary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("正在加载榜单…", color = colors.subText, fontSize = 12.sp)
                        }
                    }
                }
            } else if (state.neteaseToplists.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        Text("榜单加载失败，稍后重试", color = colors.subText, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
