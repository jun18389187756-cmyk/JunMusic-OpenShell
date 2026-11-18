package com.jun.musiccloud.ui

// 6.4 返回按钮：入场/按压动效、手势、玻璃图标所需
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.data.Playlist
import com.jun.musiccloud.data.RecentPlayback
import com.jun.musiccloud.data.Song
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.player.PlayerViewModel
import com.jun.musiccloud.ui.batchdl.BatchDownloadButton
import com.jun.musiccloud.ui.batchdl.downloadFeatureOn
import com.jun.musiccloud.ui.batchdl.submitBatchDownload
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.readableTextOn
import com.jun.musiccloud.ui.theme.LocalJunUiFamily
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.readableOn
import kotlinx.coroutines.launch

/**
 * 播放列表面板：三个可左右滑动的标签页。
 *
 * 1. 最近播放：整张播放过的歌单/榜单/搜索会留下一条会话（点一下在该会话内播放整张，可左右滑动切换会话、重命名）；
 * 2. 我喜欢：已点赞歌曲（点一下在「我喜欢」列表内播放，不跳播放页）；
 * 3. 歌单：自建歌单列表（点歌单进入看歌、可返回；点歌在该歌单内播放）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QueueSheet(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    colors: JunColors,
    onDismiss: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val accent = colors.primary
    val drag = rememberSheetDragState()
    // 打开时落在「当前正在听的列表」：在「我喜欢」就停在我喜欢页，
    // 在某个歌单就停在歌单页并直接打开那个歌单，否则才回到「最近播放」。
    val pagerState = rememberPagerState(
        initialPage = when {
            state.activePlaylistId != null -> 2
            state.onlyLiked -> 1
            else -> 0
        },
        pageCount = { 3 }
    )
    val scope = rememberCoroutineScope()
    val tabs = listOf("最近播放", "我喜欢", "歌单")
    // 6.6：播放列表里给「已下载到本地」的歌打标志。
    // 只在这里算一次 Set，三处行组件直接 O(1) 查，不在每一行里扫整个下载任务表。
    val downloadedIds = remember(state.downloadTasks) {
        state.downloadTasks.asSequence()
            .filter {
                it.status == com.jun.musiccloud.data.DownloadStatus.COMPLETED &&
                    it.localPath.isNotBlank()
            }
            .mapTo(HashSet()) { it.songId }
    }
    // 新建歌单弹窗 / 重命名弹窗
    var showCreate by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    // 最近播放：当前选中的会话
    var selectedRecent by remember { mutableStateOf(0) }
    var renameRecent by remember { mutableStateOf<Int?>(null) }
    // 多选模式
    var selectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    // 批量加入歌单弹窗
    var showAddToPlaylist by remember { mutableStateOf(false) }
    // 「我喜欢」「歌单」来源：0=本地 1=网易云（仅登录后可选）
    // 来源选择持久化到 ViewModel：关闭弹层再打开不重置，避免网易云/本地串台
    val likedSource = state.likedSource
    val playlistSource = state.playlistSource
    // 歌单页内的下钻：null = 显示歌单列表，非 null = 显示该歌单的歌曲
    var openedPlaylistId by remember { mutableStateOf<Long?>(null) }
    // 3D 队列墙：整屏封面长廊，点封面直接播放
    var showQueueWall by remember { mutableStateOf(false) }

    // 切到歌单页且当前正在听某个歌单时，自动展开那个歌单
    LaunchedEffect(pagerState.currentPage, state.activePlaylistId) {
        if (pagerState.currentPage == 2 && state.activePlaylistId != null) {
            openedPlaylistId = state.activePlaylistId
        }
    }

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
        Spacer(Modifier.height(10.dp))

        // 标题 + 计数 + 操作
        // 固定区（标题行、标签栏）吃弹层的错峰入场动画，滚动列表不吃：
        // 列表条目一滚动就重新组合，若也挂错峰动画会变成"滚一下抖一遍"。
        Row(
            Modifier
                .fillMaxWidth()
                .junPopItem(0),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when (pagerState.currentPage) {
                    1 -> "我喜欢"
                    2 -> "歌单"
                    else -> "最近播放"
                },
                color = onSheet, fontSize = 19.sp, fontWeight = FontWeight.Black
            )
            Spacer(Modifier.width(10.dp))
            val count = when (pagerState.currentPage) {
                0 -> state.recentPlaybacks.size
                1 -> state.likedSongs.size
                else -> if (openedPlaylistId != null)
                    viewModel.songsOfPlaylist(openedPlaylistId!!).size
                else state.playlists.size
            }
            Text(
                "$count 项",
                color = colors.subText, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            // 多选按钮（有歌曲的标签页才显示）
            val canSelect = when (pagerState.currentPage) {
                0 -> state.queue.isNotEmpty() || state.recentPlaybacks.isNotEmpty()
                1 -> (if (likedSource == 1 && state.neteaseLoggedIn) state.neteaseLikedSongs else state.likedSongs).isNotEmpty()
                else -> openedPlaylistId != null && viewModel.songsOfPlaylist(openedPlaylistId!!).isNotEmpty()
            }
            if (canSelect) {
                Text(
                    if (selectMode) "取消" else "选择",
                    color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        // 按压缩放要包住背景，所以放在 clip/background 之前
                        .junPressable {
                            selectMode = !selectMode
                            selectedIds.clear()
                        }
                        .clip(JunShape.item)
                        .background(accent.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            // 最近播放：明面上的「清空历史」（只清历史会话，当前队列不动）
            if (pagerState.currentPage == 0 && state.recentPlaybacks.isNotEmpty()) {
                Text(
                    "清空历史",
                    color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .junPressable { viewModel.clearRecentPlaybacks() }
                        .clip(JunShape.item)
                        .background(accent.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            // 3D 队列墙（当前播放队列）
            if (state.queue.isNotEmpty()) {
                Text(
                    "3D 墙",
                    color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .junPressable { showQueueWall = true }
                        .clip(JunShape.item)
                        .background(accent.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            // 歌单列表页显示「新建」
            if (pagerState.currentPage == 2 && openedPlaylistId == null) {
                Text(
                    "新建",
                    color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .junPressable {
                            createName = ""
                            showCreate = true
                        }
                        .clip(JunShape.item)
                        .background(accent.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // 标签栏
        Row(
            Modifier
                .fillMaxWidth()
                .junPopItem(1),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.forEachIndexed { i, label ->
                val active = pagerState.currentPage == i
                Box(
                    Modifier
                        .weight(1f)
                        .height(34.dp)
                        .junPressable(scaleDown = 0.94f) {
                            if (i != 2) openedPlaylistId = null
                            scope.launch { pagerState.animateScrollToPage(i, animationSpec = JunMotion.pagerSnap()) }
                        }
                        .clip(JunShape.item)
                        .background(if (active) accent.copy(alpha = 0.18f) else colors.card.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (active) accent else colors.subText,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = true
        ) { page ->
            when (page) {
                0 -> {
                    // 播放队列 + 最近播放会话：
                    // 第 0 个会话永远是「当前播放队列」（state.queue），这样从搜索/单曲点歌
                    // 进来的歌立刻就能在播放列表里看到；后面才是历史会话。
                    val queueSession = RecentPlayback(title = "播放队列", songs = state.queue)
                    val sessions = listOf(queueSession) + state.recentPlaybacks
                    if (sessions.isEmpty() || sessions.all { it.songs.isEmpty() }) {
                        Text(
                            "还没有播放队列。点一首歌或搜索结果后，这里会显示当前播放列表。",
                            color = colors.subText, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 18.dp)
                        )
                    } else {
                        val sessionPager = rememberPagerState(
                            initialPage = selectedRecent.coerceIn(0, sessions.size - 1),
                            pageCount = { sessions.size }
                        )
                        // 同步：pager 翻页时更新 selectedRecent，点胶囊时也翻 pager
                        LaunchedEffect(sessionPager.currentPage) {
                            selectedRecent = sessionPager.currentPage
                        }
                        // 6.9.6：删除历史会话后 sessions 变短，而 Pager 的 currentPage
                        // 还停在旧页（翻页动画/Compose 状态更新有先后），下一帧组合时
                        // sessions[sel] 直接越界闪退（后台崩溃日志 Index 1 Size:1 实录）。
                        // 把选中页钳回有效范围。
                        val sel = sessionPager.currentPage.coerceIn(0, sessions.size - 1)
                        val session = sessions[sel]
                        val isQueueSession = sel == 0
                        Column(Modifier.fillMaxSize()) {
                            // 会话胶囊指示器：横向滚动，点击直接跳到该会话；左右滑动列表也能切
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                sessions.forEachIndexed { i, s ->
                                    if (s.songs.isEmpty() && i != 0) return@forEachIndexed
                                    val active = i == sel
                                    Box(
                                        Modifier
                                            // 长按历史会话胶囊 = 直接把它从「最近播放」里删掉（撤销键 4 秒内可回）
                                            .junPressableRow(
                                                scaleDown = 0.90f,
                                                onLongClick = if (i > 0) {
                                                    { viewModel.deleteRecentPlayback(i - 1) }
                                                } else null
                                            ) {
                                                scope.launch { sessionPager.animateScrollToPage(i, animationSpec = JunMotion.pagerSnap()) }
                                            }
                                            .clip(JunShape.item)
                                            .background(
                                                if (active) accent.copy(alpha = 0.18f)
                                                else colors.card.copy(alpha = 0.5f)
                                            )
                                            .padding(horizontal = 12.dp, vertical = 7.dp)
                                    ) {
                                        Text(
                                            s.title,
                                            color = if (active) accent else colors.subText,
                                            fontSize = 12.sp,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            // 用 HorizontalPager 承载每个会话的歌曲列表：
                            // 左右滑切换歌单队列，组内上下滚动切歌。
                            //
                            // 6.3.2 修「一根手指划不动 / 划了完全没反应」。
                            //
                            // 前两版都失败了，原因是嵌套滚动方向的取舍没想透：
                            //  · onPostScroll 交还 → 内层 pager 先把手势吃完，available.x 恒为 0，
                            //    父级永远收不到，等于没交；
                            //  · onPreScroll 里 dispatchRawDelta 再返回 available →
                            //    内层 pager 自己也会把这段位移拿去翻页，两套滚动同时动，
                            //    结果是「抖一下然后卡住」。
                            //
                            // 真正的解法是**别让两个 pager 同时响应同一段手势**：
                            // 内层会话只有 1 个时（绝大多数用户：只有"播放队列"一条），
                            // 它本来就没有可翻的页，直接把它自己的横滑关掉，手势全部交给外层。
                            // 会话 ≥ 2 时才启用内层横滑，此时横滑语义优先给内层（翻会话），
                            // 要切"最近播放 ↔ 我喜欢"用顶部三个标签（0.1 秒点一下就过去，
                            // 比在嵌套两层里赌手势方向可靠得多）。
                            //
                            // 保留 nestedScroll 只为一件小事：内层在末页继续左滑时，
                            // 把这段位移**转发**给外层，实现"会话滑到头能接着切标签页"。
                            val innerScrollable = sessions.size > 1
                            val sessionNested = remember(sessions.size, pagerState.pageCount) {
                                object : NestedScrollConnection {
                                    override fun onPreScroll(
                                        available: Offset,
                                        source: NestedScrollSource
                                    ): Offset {
                                        val dx = available.x
                                        // 只有内层滑到末页还继续左滑时才接管（此时内层
                                        // 已经翻不动了，位移交出去不会产生两套滚动打架）。
                                        if (dx >= 0f) return Offset.Zero
                                        if (sessionPager.currentPage != sessions.lastIndex) return Offset.Zero
                                        pagerState.dispatchRawDelta(dx)
                                        return available
                                    }

                                    override suspend fun onPostFling(
                                        consumed: Velocity,
                                        available: Velocity
                                    ): Velocity {
                                        // dispatchRawDelta 不会自己结算，松手补一次吸附，
                                        // 否则外层停半路，两页各露一半。
                                        if (available.x == 0f) return Velocity.Zero
                                        val target = pagerState.currentPage
                                        if (pagerState.currentPageOffsetFraction != 0f) {
                                            pagerState.animateScrollToPage(
                                                target,
                                                animationSpec = JunMotion.pagerSnap()
                                            )
                                        }
                                        return Velocity.Zero
                                    }
                                }
                            }
                            HorizontalPager(
                                state = sessionPager,
                                modifier = Modifier
                                    .weight(1f)
                                    .nestedScroll(sessionNested),
                                // 会话只有 1 条时它就是唯一一页，横滑没有任何意义，
                                // 关掉后手势整体给外层标签 pager —— 这是"一根手指从
                                // 最近播放划到我喜欢"能生效的关键。
                                userScrollEnabled = innerScrollable
                            ) { pageIdx ->
                                val s = sessions[pageIdx]
                                val isQueue = pageIdx == 0
                                Column(Modifier.fillMaxSize()) {
                                    // 当前会话：名字 + 歌曲数 + 重命名（仅历史会话可重命名）
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            s.title, color = onSheet, fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "${s.songs.size} 首", color = colors.subText,
                                            fontSize = 11.sp, modifier = Modifier.weight(1f)
                                        )
                                        if (!isQueue) {
                                            Box(
                                                Modifier
                                                    .junPressable(scaleDown = 0.92f) {
                                                        renameRecent = pageIdx - 1
                                                    }
                                                    .clip(JunShape.item)
                                                    .background(colors.card)
                                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                            ) {
                                                Text("重命名", color = colors.subText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            // 删除这条历史会话：点错有 4 秒撤销
                                            Box(
                                                Modifier
                                                    .junPressable(scaleDown = 0.92f) {
                                                        viewModel.deleteRecentPlayback(pageIdx - 1)
                                                    }
                                                    .clip(JunShape.item)
                                                    .background(colors.card)
                                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                            ) {
                                                Text("删除", color = colors.subText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        } else if (s.songs.isNotEmpty()) {
                                            // 当前播放队列：清空（不可逆，但 clearQueue 自带撤销窗口）
                                            Box(
                                                Modifier
                                                    .junPressable(scaleDown = 0.92f) {
                                                        viewModel.clearQueue()
                                                    }
                                                    .clip(JunShape.item)
                                                    .background(colors.card)
                                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                            ) {
                                                Text("清空", color = colors.subText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    val listState = rememberLazyListState()
                                    // 打开播放列表时自动滚动到正在播放的那首
                                    LaunchedEffect(state.currentSong?.id, pageIdx) {
                                        val curId = state.currentSong?.id
                                        if (curId != null) {
                                            val idx = s.songs.indexOfFirst { it.id == curId }
                                            if (idx >= 0) listState.scrollToItem(idx)
                                        }
                                    }
                                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                                        itemsIndexed(s.songs, key = { i, song -> "rp-$pageIdx-$i-${song.id}" }) { index, song ->
                                            QueueSongRow(
                                                song = song,
                                                colors = colors,
                                                accent = accent,
                                                playing = song.id == state.currentSong?.id,
                                                selectMode = selectMode,
                                                selected = song.id in selectedIds,
                                                onToggleSelect = {
                                                    if (song.id in selectedIds) selectedIds.remove(song.id)
                                                    else selectedIds.add(song.id)
                                                },
                                                onClick = {
                                                    if (selectMode) {
                                                        if (song.id in selectedIds) selectedIds.remove(song.id)
                                                        else selectedIds.add(song.id)
                                                    } else {
                                                        if (isQueue) {
                                                            // 播放队列里点歌 → 直接播这首。
                                                            // R27：位置可能因为刚删/加过歌和队列错开半拍，
                                                            // 对不上就按歌曲身份播，不能让点击静默落空。
                                                            if (state.queue.getOrNull(index)?.id == song.id) {
                                                                viewModel.playAt(index)
                                                            } else {
                                                                viewModel.playSong(song)
                                                            }
                                                        } else {
                                                            // 历史会话里点歌 → 播放整个会话（从这首开始）
                                                            viewModel.playRecentPlayback(s, index)
                                                        }
                                                        // 不关闭弹层：用户可能还想继续浏览/切歌
                                                    }
                                                },
                                                onLongPress = {
                                                    if (!selectMode) {
                                                        selectMode = true
                                                        selectedIds.clear()
                                                        selectedIds.add(song.id)
                                                    }
                                                },
                                                // 6.6：已下载集合在 QueueSheet 顶部算好，这里只查表
                                                downloaded = song.id in downloadedIds
                                            )
                                        }
                                        item { Spacer(Modifier.height(if (selectMode) 70.dp else 10.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // 我喜欢
                    Column(Modifier.fillMaxSize()) {
                        // 来源切换（登录后可选：本地 / 网易云）
                        if (state.neteaseLoggedIn) {
                            SourceToggle(
                                source = likedSource,
                                colors = colors,
                                accent = accent,
                                onSelect = {
                                    viewModel.setLikedSource(it)
                                    if (it == 1) viewModel.loadNeteaseLikedSongs()
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        val useNetease = state.neteaseLoggedIn && likedSource == 1
                        val likedSongs = if (useNetease) state.neteaseLikedSongs else state.likedSongs
                        val loading = useNetease && state.neteaseLikedLoading
                        if (loading) {
                            Text(
                                "加载网易云我喜欢…",
                                color = colors.subText, fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 18.dp)
                            )
                        } else if (likedSongs.isEmpty()) {
                            Text(
                                if (useNetease) "网易云我喜欢暂时是空的"
                                // 歌曲行末尾的爱心已经换成 ⋮（5.3），文案要指对入口
                                else "还没有喜欢的音乐。点歌曲行末尾的 ⋮ 选「添加到我喜欢」，或在播放页点爱心。",
                                color = colors.subText, fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 18.dp)
                            )
                        } else {
                            val likedListState = rememberLazyListState()
                            LaunchedEffect(state.currentSong?.id, likedSource) {
                                val curId = state.currentSong?.id
                                if (curId != null) {
                                    val idx = likedSongs.indexOfFirst { it.id == curId }
                                    if (idx >= 0) likedListState.scrollToItem(idx)
                                }
                            }
                            LazyColumn(Modifier.fillMaxSize(), state = likedListState) {
                                itemsIndexed(likedSongs, key = { index, s -> "liked-$likedSource-$index-${s.id}" }) { index, song ->
                                    QueueSongRow(
                                        song = song,
                                        colors = colors,
                                        accent = accent,
                                        playing = song.id == state.currentSong?.id,
                                        selectMode = selectMode,
                                        selected = song.id in selectedIds,
                                        onToggleSelect = {
                                            if (song.id in selectedIds) selectedIds.remove(song.id)
                                            else selectedIds.add(song.id)
                                        },
                                        onClick = {
                                            if (selectMode) {
                                                if (song.id in selectedIds) selectedIds.remove(song.id)
                                                else selectedIds.add(song.id)
                                            } else {
                                                if (useNetease) {
                                                    // 网易云我喜欢：整张播放（图书馆模式下会先校验）
                                                    viewModel.playFromNeteaseLiked(index)
                                                } else {
                                                    viewModel.playFromLiked(index)
                                                }
                                                // 不关闭弹层：用户可继续浏览
                                            }
                                        },
                                        onLongPress = {
                                            if (!selectMode) {
                                                selectMode = true
                                                selectedIds.clear()
                                                selectedIds.add(song.id)
                                            }
                                        },
                                        // 6.6：已下载集合在 QueueSheet 顶部算好，这里只查表
                                        downloaded = song.id in downloadedIds
                                    )
                                }
                                item { Spacer(Modifier.height(if (selectMode) 70.dp else 10.dp)) }
                            }
                        }
                    }
                }
                else -> {
                    // 歌单
                    val opened = openedPlaylistId
                    if (opened != null) {
                        // 某个歌单的歌曲列表
                        val allSongs = viewModel.songsOfPlaylist(opened)
                        // 6.4：「只看可播」。导入时服务端按 fee 预判了哪些歌要换源，
                        // 标记存在 Playlists 里。没有标记的歌一律算可播 ——
                        // 宁可让用户点进去发现要换源，也不要把能播的歌藏起来。
                        var onlyPlayable by remember(opened) { mutableStateOf(false) }
                        val songs = if (onlyPlayable) {
                            allSongs.filter {
                                com.jun.musiccloud.data.Playlists.isSongPlayable(it.songId) != false
                            }
                        } else allSongs
                        Column(Modifier.fillMaxSize()) {
                            // 返回条：左侧液态玻璃胶囊返回按钮，右侧保留全选入口
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 6.7 修：推荐/搜索歌单详情返回按钮——与曲目详情、歌单广场
                                // 共用同一枚 GlassBackChip（同一套 CHIP 玻璃材质 + 按压回弹 +
                                // 入场淡入）。点击行为不变：回到歌单列表。
                                GlassBackChip(
                                    colors = colors,
                                    onClick = { openedPlaylistId = null },
                                    label = "返回歌单列表",
                                    enterKey = opened
                                )
                                Spacer(Modifier.weight(1f))
                                // 明面上的全选入口：以这个歌单当前显示的歌为范围
                                if (songs.isNotEmpty()) {
                                    // 整屏已选中 → 带勾框 +「取消全选」，点它退出多选
                                    val allChecked = selectMode &&
                                        songs.all { it.id in selectedIds }
                                    SelectAllEntry(colors, small = true, checked = allChecked) {
                                        Haptics.tap()
                                        if (allChecked) {
                                            selectMode = false
                                            selectedIds.clear()
                                        } else {
                                            selectMode = true
                                            selectedIds.clear()
                                            selectedIds.addAll(songs.map { it.id })
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            // 6.4：有可播数据才显示过滤条。
                            // 没数据还显示一个「只看可播 0 首」，等于给用户挖坑。
                            if (allSongs.isNotEmpty() && com.jun.musiccloud.data.Playlists.hasPlayableData()) {
                                val playableCount = allSongs.count {
                                    com.jun.musiccloud.data.Playlists.isSongPlayable(it.songId) != false
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PlayableFilterChip(
                                        text = "全部 ${allSongs.size}",
                                        active = !onlyPlayable,
                                        activeColor = colors.primary,
                                        activeText = colors.primary.readableTextOn(),
                                        idleBg = colors.card,
                                        idleText = colors.subText
                                    ) { onlyPlayable = false }
                                    // 文案是「仅网易云直连」不是「只看可播」：
                                    // 被过滤掉的歌**照样能听**（走换源），只是要多等半秒。
                                    // 写成"只看可播"会让用户以为剩下的都不能听，
                                    // 而实测许嵩歌单 112 首里 110 首都是这种情况 ——
                                    // 一开过滤就剩 2 首，看着像歌单坏了。
                                    PlayableFilterChip(
                                        text = "仅网易云直连 $playableCount",
                                        active = onlyPlayable,
                                        activeColor = colors.primary,
                                        activeText = colors.primary.readableTextOn(),
                                        idleBg = colors.card,
                                        idleText = colors.subText
                                    ) { onlyPlayable = true }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            if (songs.isEmpty()) {
                                Text(
                                    // 两种空要分开说：真的空，和被过滤空了
                                    if (onlyPlayable && allSongs.isNotEmpty())
                                        "这个歌单里的歌都要走换源播放。切到「全部」正常听就行，不影响。"
                                    else "这个歌单还是空的。去首页或搜索里把歌加进来吧。",
                                    color = colors.subText, fontSize = 13.sp,
                                    modifier = Modifier.padding(vertical = 18.dp)
                                )
                            } else {
                                val plListState = rememberLazyListState()
                                LaunchedEffect(state.currentSong?.id, opened) {
                                    val curId = state.currentSong?.id
                                    if (curId != null) {
                                        val idx = songs.indexOfFirst { it.id == curId }
                                        if (idx >= 0) plListState.scrollToItem(idx)
                                    }
                                }
                                LazyColumn(Modifier.fillMaxSize(), state = plListState) {
                                    itemsIndexed(songs, key = { index, s -> "pls-${opened}-$index-${s.id}" }) { index, song ->
                                        QueueSongRow(
                                            song = song,
                                            colors = colors,
                                            accent = accent,
                                            playing = song.id == state.currentSong?.id,
                                            selectMode = selectMode,
                                            selected = song.id in selectedIds,
                                            onToggleSelect = {
                                                if (song.id in selectedIds) selectedIds.remove(song.id)
                                                else selectedIds.add(song.id)
                                            },
                                            onClick = {
                                                if (selectMode) {
                                                    if (song.id in selectedIds) selectedIds.remove(song.id)
                                                    else selectedIds.add(song.id)
                                                } else {
                                                    // 在这个歌单内播放这首，不跳播放页
                                                    viewModel.playFromPlaylist(opened, index)
                                                    // 不关闭弹层：用户可继续浏览
                                                }
                                            },
                                            onLongPress = {
                                                if (!selectMode) {
                                                    selectMode = true
                                                    selectedIds.clear()
                                                    selectedIds.add(song.id)
                                                }
                                            },
                                            // 6.6：已下载集合在 QueueSheet 顶部算好，这里只查表
                                            downloaded = song.id in downloadedIds
                                        )
                                    }
                                    item { Spacer(Modifier.height(if (selectMode) 70.dp else 10.dp)) }
                                }
                            }
                        }
                    } else {
                        // 歌单列表
                        Column(Modifier.fillMaxSize()) {
                            // 来源切换（登录后可选：本地 / 网易云）
                            if (state.neteaseLoggedIn) {
                                SourceToggle(
                                    source = playlistSource,
                                    colors = colors,
                                    accent = accent,
                                    onSelect = { viewModel.setPlaylistSource(it) }
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            val useNetease = state.neteaseLoggedIn && playlistSource == 1
                            if (useNetease) {
                                // 网易云我的歌单
                                val nps = state.neteaseMyPlaylists
                                if (nps.isEmpty()) {
                                    Text(
                                        "网易云歌单为空或加载中…",
                                        color = colors.subText, fontSize = 13.sp,
                                        modifier = Modifier.padding(vertical = 18.dp)
                                    )
                                } else {
                                    LazyColumn(Modifier.fillMaxSize()) {
                                        itemsIndexed(nps, key = { _, p -> "npl-${p.id}" }) { _, pl ->
                                            NeteasePlaylistRow(
                                                pl = pl,
                                                colors = colors,
                                                accent = accent,
                                                onClick = {
                                                    // 打开网易云歌单曲目弹层
                                                    viewModel.selectNeteasePlaylist(pl.id, pl.name)
                                                    onDismiss()
                                                }
                                            )
                                        }
                                        item { Spacer(Modifier.height(10.dp)) }
                                    }
                                }
                            } else if (state.playlists.isEmpty()) {
                                Text(
                                    "还没有歌单。点右上角「新建」创建第一个歌单。",
                                    color = colors.subText, fontSize = 13.sp,
                                    modifier = Modifier.padding(vertical = 18.dp)
                                )
                            } else {
                                LazyColumn(Modifier.fillMaxSize()) {
                                    itemsIndexed(state.playlists, key = { _, p -> "pl-${p.id}" }) { _, playlist ->
                                        PlaylistRow(
                                            playlist = playlist,
                                            colors = colors,
                                            accent = accent,
                                            active = playlist.id == state.activePlaylistId,
                                            onClick = { openedPlaylistId = playlist.id },
                                            onRename = { renameTarget = playlist },
                                            onDelete = { viewModel.deletePlaylist(playlist.id) }
                                        )
                                    }
                                    item { Spacer(Modifier.height(10.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 多选模式底部操作栏：欠阻尼弹簧推上来（直接 if 会出现"凭空闪出"，
        // 和同一个面板里所有会回弹的按钮不是一个材质）
        AnimatedVisibility(
            visible = selectMode,
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f)
            ) { it } + fadeIn(),
            exit = slideOutVertically(
                animationSpec = spring(dampingRatio = 1f, stiffness = 900f)
            ) { it } + fadeOut()
        ) {
            val currentList: List<Song> = when (pagerState.currentPage) {
                0 -> {
                    val sel = selectedRecent.coerceIn(0, (listOf(RecentPlayback("", state.queue)) + state.recentPlaybacks).size - 1)
                    if (sel == 0) state.queue else state.recentPlaybacks.getOrNull(sel - 1)?.songs ?: emptyList()
                }
                1 -> if (likedSource == 1 && state.neteaseLoggedIn) state.neteaseLikedSongs else state.likedSongs
                else -> openedPlaylistId?.let { viewModel.songsOfPlaylist(it) } ?: emptyList()
            }
            val allSelected = currentList.isNotEmpty() && currentList.all { it.id in selectedIds }
            // 6.1.2：这批歌的出处 —— 下载记录与撤销提示都靠它说人话
            val currentSource: String = when (pagerState.currentPage) {
                0 -> {
                    val sel = selectedRecent.coerceIn(
                        0,
                        (listOf(RecentPlayback("", state.queue)) + state.recentPlaybacks).size - 1
                    )
                    if (sel == 0) "播放队列"
                    else state.recentPlaybacks.getOrNull(sel - 1)?.title?.takeIf { it.isNotBlank() }
                        ?: "最近播放"
                }
                1 -> if (likedSource == 1 && state.neteaseLoggedIn) "我喜欢 · 网易云" else "我喜欢"
                else -> state.playlists.firstOrNull { it.id == openedPlaylistId }?.name ?: "歌单"
            }
            // 后台关掉「下载」功能时这颗钮不出现，和主界面那条批量栏同一个判据
            val canDownload = downloadFeatureOn()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 全选
                Box(
                    Modifier
                        .weight(1f)
                        .junPressable(scaleDown = 0.94f) {
                            if (allSelected) selectedIds.clear()
                            else selectedIds.apply { clear(); addAll(currentList.map { it.id }) }
                        }
                        .clip(JunShape.item)
                        .background(accent.copy(alpha = 0.15f))
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (allSelected) "取消全选" else "全选", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                // 下载（6.1.2 补）：这一排以前只有 全选/删除/加歌单/加喜欢，
                // 于是播放队列、最近播放、我喜欢、本地歌单这四处多选根本下不了歌，
                // 只能退出去首页曲库或搜索页全选——同一件事被迫走两条路。
                // 钮和判据都用 ui.batchdl 那一份共享实现，跟首页曲库、搜索结果、歌手详情、
                // 网易云歌单/榜单弹层完全同一行为：同一套判重/跳过已下载/并发上限，同一句回执。
                if (canDownload) {
                    BatchDownloadButton(
                        accent = accent,
                        modifier = Modifier.weight(1f),
                        enabled = selectedIds.isNotEmpty(),
                        // 一首都没勾就按「下载」：按钮置灰但仍然可点，点下去给一句人话，
                        // 不能再出现「按了没反应」（6.2 一起修的那类问题）
                        onEmptyClick = { viewModel.notice("先勾几首歌，再点下载") }
                    ) {
                        submitBatchDownload(
                            viewModel,
                            currentList.filter { it.id in selectedIds },
                            currentSource
                        )
                        selectedIds.clear()
                    }
                }
                // 删除
                Box(
                    Modifier
                        .weight(1f)
                        .junPressable(
                            scaleDown = 0.94f,
                            feedback = PressFeedback.CLICK
                        ) {
                            val ids = selectedIds.toList()
                            if (ids.isEmpty()) return@junPressable
                            when (pagerState.currentPage) {
                                0 -> {
                                    // 从播放队列删除
                                    val indices = state.queue.mapIndexedNotNull { i, s -> if (s.id in ids) i else null }
                                    viewModel.removeSongsFromQueue(indices)
                                }
                                1 -> {
                                    // 从我喜欢删除
                                    viewModel.removeSongsFromLiked(ids)
                                }
                                else -> {
                                    // 从歌单删除
                                    openedPlaylistId?.let { pid ->
                                        ids.forEach { viewModel.removeFromPlaylist(pid, it) }
                                    }
                                }
                            }
                            selectedIds.clear()
                        }
                        .clip(JunShape.item)
                        .background(colors.dangerBg)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("删除", color = colors.dangerText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                // 添加到歌单
                Box(
                    Modifier
                        .weight(1f)
                        .junPressable(scaleDown = 0.94f) {
                            if (selectedIds.isNotEmpty()) showAddToPlaylist = true
                        }
                        .clip(JunShape.item)
                        .background(accent.copy(alpha = 0.15f))
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("加歌单", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                // 添加到我喜欢
                Box(
                    Modifier
                        .weight(1f)
                        .junPressable(scaleDown = 0.94f) {
                            val ids = selectedIds.toList()
                            if (ids.isNotEmpty()) {
                                viewModel.addSongsToLiked(ids)
                                selectedIds.clear()
                            }
                        }
                        .clip(JunShape.item)
                        .background(accent.copy(alpha = 0.15f))
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("加喜欢", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 新建歌单弹窗
        if (showCreate) {
            CreatePlaylistDialog(
                colors = colors,
                initial = createName,
                title = "新建歌单",
                confirmText = "创建",
                onNameChange = { createName = it },
                onConfirm = {
                    val name = createName.trim()
                    if (name.isNotBlank()) {
                        val id = viewModel.createPlaylist(name)
                        openedPlaylistId = id
                    }
                    showCreate = false
                },
                onDismiss = { showCreate = false }
            )
        }
        // 重命名弹窗
        renameTarget?.let { pl ->
            var name by remember(pl.id) { mutableStateOf(pl.name) }
            CreatePlaylistDialog(
                colors = colors,
                initial = name,
                title = "重命名歌单",
                confirmText = "保存",
                onNameChange = { name = it },
                onConfirm = {
                    val n = name.trim()
                    if (n.isNotBlank()) viewModel.renamePlaylist(pl.id, n)
                    renameTarget = null
                },
                onDismiss = { renameTarget = null }
            )
        }
        // 最近播放会话重命名弹窗
        renameRecent?.let { idx ->
            val session = state.recentPlaybacks.getOrNull(idx)
            if (session != null) {
                var name by remember(idx, session.title) { mutableStateOf(session.title) }
                CreatePlaylistDialog(
                    colors = colors,
                    initial = name,
                    title = "重命名最近播放",
                    confirmText = "保存",
                    onNameChange = { name = it },
                    onConfirm = {
                        val n = name.trim()
                        if (n.isNotBlank()) viewModel.renameRecentPlayback(idx, n)
                        renameRecent = null
                    },
                    onDismiss = { renameRecent = null }
                )
            }
        }
    }

    // 批量加入歌单：换成和其它弹层同一具外壳（玻璃面板 + 拖把手 + 错峰条目）
    if (showAddToPlaylist) {
        BatchAddPlaylistSheet(
            state = state,
            colors = colors,
            selectedCount = selectedIds.size,
            onDismiss = { showAddToPlaylist = false },
            onAdd = { pid ->
                val ids = selectedIds.toList()
                if (ids.isNotEmpty()) viewModel.addSongsToPlaylist(pid, ids)
                selectedIds.clear()
                showAddToPlaylist = false
            }
        )
    }

    if (showQueueWall) {
        QueueWallOverlay(
            queue = state.queue,
            currentIndex = state.currentIndex,
            colors = colors,
            onPlayAt = { index ->
                viewModel.playAt(index)
                showQueueWall = false
            },
            onDismiss = { showQueueWall = false }
        )
    }
}

/** 推荐 / 我喜欢 / 歌单内共用的歌曲行：封面 + 标题/歌手 + 时长 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueSongRow(
    song: Song,
    colors: JunColors,
    accent: Color,
    playing: Boolean = false,
    selectMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    downloaded: Boolean = false
) {
    // 正在播放：给整行加一个强调底色 + 左侧播放中动效条，一眼就能看到听到哪首
    val playingBg = if (playing) accent.copy(alpha = 0.12f) else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            // 与弹层里其它按钮共用一套按压弹簧：行很宽，幅度取 PRESS_SCALE_ROW（很小），
            // 否则整行一起缩会读成"整个列表跳了一下"。缩放层在 clip/background 之前，
            // 底色才会跟着行一起软下去。
            .junPressableRow(onLongClick = { onLongPress() }) {
                onClick()
            }
            .clip(JunShape.item)
            .background(playingBg)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectMode) {
            // 多选复选框：6.0 起和歌曲行 [SongRow] 用同一套视觉（圆角方框 + 一枚勾），
            // 之前这里是圆形 + 手画两条线，同一个 App 里两种勾选框。
            Box(
                Modifier
                    .size(20.dp)
                    .junPressable(scaleDown = 0.72f) { onToggleSelect() }
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (selected) accent else Color.Transparent)
                    .border(
                        if (selected) 1.dp else 1.4.dp,
                        if (selected) accent else colors.subText.copy(alpha = 0.72f),
                        RoundedCornerShape(5.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(JunIcons.Check, null, Modifier.size(14.dp), Color.White)
                }
            }
            Spacer(Modifier.width(10.dp))
        } else if (playing) {
            // 正在播放：左侧跳动的音柱动画，比单纯变色更直观
            PlayingBars(accent = accent)
            Spacer(Modifier.width(10.dp))
        }
        SongArtwork(
            filePath = song.filePath,
            seedId = song.id,
            artworkSize = 42.dp,
            isDark = colors.isDark
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                color = if (playing) accent else colors.text,
                fontSize = 13.sp, fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                color = if (playing) accent.copy(alpha = 0.7f) else colors.subText, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        // 6.4：已下载到本地的歌，在时长前给一枚主色小标志（不占掉「播放中」/时长）
        if (downloaded) {
            Icon(
                JunIcons.Download,
                contentDescription = "已下载到本地",
                tint = colors.primary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        if (playing) {
            Text("播放中", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
        }
        Text(formatDuration(song.durationMs), color = if (playing) accent else colors.subText, fontSize = 11.sp)
    }
}

/** 正在播放的音柱动画：三根竖条上下跳动 */
@Composable
private fun PlayingBars(accent: Color) {
    val infinite = rememberInfiniteTransition(label = "playingBars")
    val h1 by infinite.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "b1")
    val h2 by infinite.animateFloat(1f, 0.3f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "b2")
    val h3 by infinite.animateFloat(0.5f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "b3")
    Row(
        Modifier.size(20.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(h1, h2, h3).forEach { h ->
            Box(
                Modifier
                    .width(3.dp)
                    .height((10.dp.value * h).dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
    }
}

/** 歌单行：歌单图标 + 歌单名 + 歌曲数 + 重命名/删除（与歌单弹层 PlaylistRow 视觉统一） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    colors: JunColors,
    accent: Color,
    active: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val t by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(240),
        label = "playlistActive"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .junPressableRow(onLongClick = onRename) { onClick() }
            .junSurface(
                level = GlassLevel.ROW,
                colors = colors,
                shape = JunShape.item,
                solid = androidx.compose.ui.graphics.lerp(colors.row, colors.primary.copy(alpha = 0.12f), t),
                accent = if (t > 0.5f) colors.primary else null
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面：渐变色块 + QueueMusic 图标（与歌单弹层一致）
        Box(
            Modifier
                .size(42.dp)
                .clip(JunShape.chip)
                .background(
                    Brush.linearGradient(
                        listOf(colors.primary.copy(alpha = 0.5f), colors.secondary.copy(alpha = 0.3f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(JunIcons.QueueMusic, null, Modifier.size(20.dp), Color.White.copy(alpha = 0.8f))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                color = androidx.compose.ui.graphics.lerp(colors.text, colors.primary.readableOn(colors.row, 3.2f), t),
                fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text("${playlist.songCount} 首", color = colors.subText, fontSize = 11.sp)
        }
        // 重命名按钮（与歌单弹层一致）
        Box(
            Modifier
                .size(30.dp)
                .junPressable(scaleDown = 0.80f) { onRename() }
                .clip(JunShape.chip)
                .background(colors.card),
            contentAlignment = Alignment.Center
        ) {
            Icon(JunIcons.Edit, null, Modifier.size(15.dp), colors.subText)
        }
        Spacer(Modifier.width(8.dp))
        // 删除按钮（配色跟随主题：暗色下不再是一块刺眼的淡粉）
        Box(
            Modifier
                .size(30.dp)
                .junPressable(scaleDown = 0.80f, feedback = PressFeedback.CLICK) { onDelete() }
                .clip(JunShape.chip)
                .background(colors.dangerBg),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(13.dp)) {
                val c = colors.dangerText
                val w = size.minDimension * 0.16f
                drawLine(c, Offset(0f, size.height * 0.18f), Offset(size.width, size.height * 0.18f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(c, Offset(size.width * 0.25f, size.height * 0.18f), Offset(size.width * 0.25f, size.height * 0.82f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(c, Offset(size.width * 0.5f, size.height * 0.18f), Offset(size.width * 0.5f, size.height * 0.82f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(c, Offset(size.width * 0.75f, size.height * 0.18f), Offset(size.width * 0.75f, size.height * 0.82f), strokeWidth = w, cap = StrokeCap.Round)
            }
        }
    }
}

/** 新建 / 重命名歌单通用对话框 */
@Composable
private fun CreatePlaylistDialog(
    colors: JunColors,
    initial: String,
    title: String,
    confirmText: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val focus = androidx.compose.ui.focus.FocusRequester()
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { focus.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.82f)
                .clip(JunShape.item)
                .background(colors.surface)
                .padding(18.dp)
        ) {
            Text(title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = initial,
                onValueChange = onNameChange,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .clip(JunShape.item)
                    .background(colors.card.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = LocalJunUiFamily.current, color = colors.text, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary)
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "取消",
                    color = colors.subText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .junPressable(scaleDown = 0.92f) { onDismiss() }
                        .clip(JunShape.item)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    confirmText,
                    color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .junPressable(scaleDown = 0.92f) { onConfirm() }
                        .clip(JunShape.item)
                        .background(colors.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** 顶栏「播放列表」按钮，与设置/歌单按钮同尺寸同材质 */
@Composable
internal fun QueueButton(onClick: () -> Unit, colors: JunColors, count: Int = 0) {
    Box(
        Modifier
            .size(34.dp)
            .junPressable(scaleDown = 0.84f) { onClick() }
            .clip(JunShape.chip)
            .background(colors.card.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(17.dp)) {
            val c = colors.primary
            val w = size.minDimension * 0.12f
            val y0 = size.height * 0.26f
            val y1 = size.height * 0.52f
            val y2 = size.height * 0.78f
            drawLine(c, Offset(size.width * 0.14f, y0), Offset(size.width * 0.78f, y0), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(c, Offset(size.width * 0.14f, y1), Offset(size.width * 0.78f, y1), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(c, Offset(size.width * 0.14f, y2), Offset(size.width * 0.52f, y2), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

/**
 * 来源切换胶囊：本地 / 网易云。
 * 用于「我喜欢」「歌单」标签页，登录后可在本地收藏与网易云来源之间切换。
 */
@Composable
private fun SourceToggle(
    source: Int,
    colors: JunColors,
    accent: Color,
    onSelect: (Int) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(JunShape.item)
            .background(colors.card.copy(alpha = 0.5f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("本地", "网易云").forEachIndexed { i, label ->
            val active = source == i
            Box(
                Modifier
                    .weight(1f)
                    .junPressable(scaleDown = 0.94f) { onSelect(i) }
                    .clip(JunShape.item)
                    .background(if (active) accent.copy(alpha = 0.18f) else Color.Transparent)
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (active) accent else colors.subText,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

/** 网易云歌单行：封面 + 名称 + 歌曲数（只读，点击打开曲目弹层） */
@Composable
private fun NeteasePlaylistRow(
    pl: com.jun.musiccloud.player.NeteasePlaylistItem,
    colors: JunColors,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .junPressableRow { onClick() }
            .junSurface(level = GlassLevel.ROW, colors = colors, shape = JunShape.item)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SongArtwork(
            filePath = pl.cover,
            seedId = pl.id.hashCode().toLong(),
            artworkSize = 44.dp,
            isDark = colors.isDark
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                pl.name,
                color = colors.text,
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text("网易云歌单", color = colors.subText, fontSize = 11.sp)
        }
        Canvas(Modifier.size(16.dp)) {
            val c = colors.subText
            drawLine(c, Offset(size.width * 0.22f, size.height * 0.2f),
                Offset(size.width * 0.7f, size.height * 0.5f),
                strokeWidth = size.minDimension * 0.12f, cap = StrokeCap.Round)
            drawLine(c, Offset(size.width * 0.7f, size.height * 0.5f),
                Offset(size.width * 0.22f, size.height * 0.8f),
                strokeWidth = size.minDimension * 0.12f, cap = StrokeCap.Round)
        }
    }
}

/**
 * 批量「加入歌单」弹层。
 *
 * 原来这里是手搓的一层半透明黑底 + 实色卡片：和同一屏里其余弹层（玻璃面板、拖把手、
 * 错峰条目）明显是两套材质，用户在播放列表里点「加歌单」会突然看到一块不折射的实心方块。
 * 这里换成共享的 [JunSheet] 外壳，条目走 [junPopItem] 错峰，点击走 [junPressable]。
 */
@Composable
private fun BatchAddPlaylistSheet(
    state: PlayerUiState,
    colors: JunColors,
    selectedCount: Int,
    onDismiss: () -> Unit,
    onAdd: (Long) -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val drag = rememberSheetDragState()
    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        panelModifier = Modifier.fillMaxHeight(0.72f),
        contentModifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 10.dp, bottom = 20.dp)
    ) {
        SheetDragHandle(drag, onDismiss, colors.card)
        Spacer(Modifier.height(14.dp))
        Column(Modifier.junPopItem(0)) {
            Text("加入歌单", color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text("已选 $selectedCount 首", color = colors.subText, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        if (state.playlists.isEmpty()) {
            Text(
                "还没有歌单。先在「歌单」标签页点「新建」建一个。",
                color = colors.subText, fontSize = 13.sp,
                modifier = Modifier.junPopItem(1)
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(state.playlists, key = { _, p -> "batch-${p.id}" }) { i, p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // 歌单行不错峰：列表一滚动就重新组合，错峰动画会变成"滚一下抖一遍"；
                            // 只给最前面几行错峰，翻到底就是静止的，读起来仍是"掀开"而不是齐步。
                            .then(if (i < 6) Modifier.junPopItem(1 + i) else Modifier)
                            .junPressableRow(scaleDown = 0.975f) { onAdd(p.id) }
                            .junSurface(
                                level = GlassLevel.ROW,
                                colors = colors,
                                shape = JunShape.item
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(JunShape.chip)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            colors.primary.copy(alpha = 0.55f),
                                            colors.secondary.copy(alpha = 0.35f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(JunIcons.QueueMusic, null, Modifier.size(17.dp), Color.White.copy(alpha = 0.85f))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.name, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text("${p.songCount} 首", color = colors.subText, fontSize = 11.sp)
                        }
                        Text("加入", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
}


/** 6.4：歌单详情的可播过滤 chip。两个按钮样式必须完全一致，抽出来免得写歪。 */
@Composable
private fun PlayableFilterChip(
    text: String,
    active: Boolean,
    activeColor: Color,
    activeText: Color,
    idleBg: Color,
    idleText: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .junPressable { onClick() }
            .clip(JunShape.chip)
            .background(if (active) activeColor else idleBg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (active) activeText else idleText,
            fontSize = 11.5.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
        )
    }
}
