package com.jun.musiccloud.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.BuildConfig
import com.jun.musiccloud.R
import kotlinx.coroutines.launch
import com.jun.musiccloud.data.AccountStore
import com.jun.musiccloud.data.AuthApi
import com.jun.musiccloud.data.Playlists
import com.jun.musiccloud.data.RemoteConfigStore
import com.jun.musiccloud.data.SearchHistoryStore
import com.jun.musiccloud.data.Song
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.GlassContentColumn
import com.jun.musiccloud.ui.theme.readableOn
import com.jun.musiccloud.ui.theme.LocalJunUiFamily
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunLayout
import com.jun.musiccloud.data.DownloadStatus
import com.jun.musiccloud.data.NeteaseApi
import com.jun.musiccloud.ui.theme.isLiquidGlass
import com.jun.musiccloud.ui.theme.junSurface
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// 说明：本文件是纯在线版在 JUN 主 UI 上叠加的两个二级界面——
//  1) 全屏搜索页：历史 + 热搜 + 在线结果（不改动原有列表页玻璃结构）
//  2) 「我的」侧滑抽屉：我喜欢 / 我的歌单 / 收听报告 / 刷新 / 设置 / 关于
// 两者都以浮层形式挂载在 MainScreen 根层级，复用同一套主题色与玻璃材质。

// ==================== 顶栏头像按钮 ====================

@Composable
internal fun AvatarButton(onClick: () -> Unit, colors: JunColors) {
    Box(
        Modifier
            .size(34.dp)
            .junPressable(onClick = onClick)
            .junSurface(GlassLevel.CHIP, colors, JunShape.chip),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp)) {
            val c = colors.primary
            val strokeW = size.minDimension * 0.12f
            // 头 + 肩：一个圆 + 一段下半圆，最简洁的人形
            drawCircle(
                color = c,
                radius = size.minDimension * 0.2f,
                center = Offset(size.width * 0.5f, size.height * 0.32f),
                style = Stroke(width = strokeW)
            )
            drawArc(
                color = c,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(size.width * 0.16f, size.height * 0.52f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.68f, size.height * 0.5f),
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
        }
    }
}

// ==================== 「我的」侧滑抽屉 ====================

@Composable
internal fun MyDrawer(
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit,
    onLiked: () -> Unit,
    onPlaylists: () -> Unit,
    onSelectLayout: (JunLayout) -> Unit,
    onDownload: () -> Unit,
    onAccount: () -> Unit,
    /** 打开「我的主页」 */
    onProfile: () -> Unit,
    onNeteaseLogin: () -> Unit,
    onReport: () -> Unit,
    /** 6.3：打开听歌排行榜（我的抽屉里也给一个入口） */
    onRank: () -> Unit = {},
    onSettings: () -> Unit,
    onNeteaseDaily: () -> Unit,
    onNeteasePlaylists: () -> Unit,
    onNeteaseToplist: () -> Unit,
    onNeteaseLiked: () -> Unit,
    onEditName: (String) -> Unit,
    onEditAvatar: (android.net.Uri) -> Unit,
    onChangePassword: () -> Unit,
    /** 6.4：打开意见反馈弹层（抽屉最上面那颗实色卡片） */
    onFeedback: () -> Unit = {},
    /**
     * 强制展开所有分组。聚光灯导览（重看新手引导 / 功能导览）要用 —— 6.0 起抽屉默认全部
     * 收起，而导览要照的「登录 / 注册」「图书馆模式」都在收起的分组里，不展开就没有锚点。
     */
    expandAllSections: Boolean = false,
    /** 一键开关图书馆模式（只放轻音乐 / 纯音乐 / 白噪音 / lofi） */
    onToggleLibraryMode: () -> Unit = {}
) {
    val panelWidth = 300.dp
    // 入场自左侧滑入（无退出动画，由调用方的 if 收起，成本低）。
    // 原来写死 -360f 当 px 用：nova 6 上抽屉宽约 300dp ≈ 825px，所以第一帧抽屉
    // **已经露出一半**再滑 360px —— 看起来就是"闪一下、再挪一段"，不是丝滑推入。
    // 现在按真实宽度走完整行程，并用软弹簧收住（轻微过冲 = 一点点回弹，
    // 过冲压在 1% 行程内，不会在右边缘让出一条缝）。
    val drawerTravel = with(LocalDensity.current) { panelWidth.toPx() }
    val x = remember { Animatable(-drawerTravel) }
    var showLayoutPicker by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf(state.accountName) }
    LaunchedEffect(Unit) {
        x.animateTo(
            0f,
            // R12 再放慢：刚度 240/300 → 110/140（到位时间约慢 1.5 倍）。
            // 阻尼**不能跟着一起放松**——抽屉的终点是贴住屏幕边缘，过冲就是右边露缝；
            // ζ0.82 的过冲约 1.1% 行程（825px 里约 9px），刚好读成"软了一下"又不会露底。
            if (JunMotion.reduced) spring(dampingRatio = 1f, stiffness = 900f)
            else spring(dampingRatio = if (JunMotion.jelly) 0.82f else 0.9f,
                        stiffness = if (JunMotion.jelly) 110f else 140f)
        )
    }
    LaunchedEffect(state.accountName) { nameInput = state.accountName }

    val loggedIn = state.accountLoggedIn
    // 总后台功能开关：关掉网易云后，抽屉里所有网易云入口（含登录）一并隐藏。
    val appFlags = RemoteConfigStore.flagsState.value

    // 各分组折叠态：6.3 彻底修复版按用户要求**全部默认展开**——
    // 「账号与安全，我的音乐发现通用，这里面的内容直接默认全部给展开」。
    // 下载管理同时提到「我的音乐」第一行（着重的东西放明显的位置）。
    val sections = remember { mutableStateMapOf<String, Boolean>().apply {
        put("账号与安全", true)
        put("我的音乐", true)
        put("发现", true)
        put("通用", true)
    } }

    // 相册选图：直接拉本地相册，普通人不懂 URL
    val avatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let { onEditAvatar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        // 遮罩：点击关闭
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
        )

        Column(
            Modifier
                .align(Alignment.CenterStart)
                .width(panelWidth)
                .fillMaxHeight()
                .offset { IntOffset(x.value.roundToInt(), 0) }
                .junSurface(
                    GlassLevel.SHEET, colors,
                    RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp)
                )
                .statusBarsPadding()
                .padding(vertical = 8.dp)
        ) {
            // ===== 顶部：个人资料（头像 + 昵称，可编辑）=====
            // 首启引导第 6 步照的就是这块：登录 / 注册从这里进
            Column(
                Modifier
                    .fillMaxWidth()
                    .spotlightAnchor(SpotlightKeys.ACCOUNT)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 头像：点击从本地相册选择（未登录也能直接换本地头像，不强制登录）
                    val displayAvatar = if (state.accountLoggedIn) state.accountAvatar else state.localAvatar
                    Box(
                        Modifier
                            .size(56.dp)
                            .junPressable(feedback = PressFeedback.NONE) { avatarPicker.launch("image/*") }
                            .clip(CircleShape)
                    ) {
                        if (displayAvatar.isNotBlank()) {
                            SongArtwork(
                                filePath = displayAvatar,
                                seedId = state.accountUserId.takeIf { it > 0 } ?: state.accountName.hashCode().toLong(),
                                artworkSize = 56.dp,
                                isDark = colors.isDark
                            )
                        } else {
                            // 未设置头像：显示默认人形图标
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(colors.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(Modifier.size(30.dp)) {
                                    val c = colors.primary
                                    val strokeW = size.minDimension * 0.12f
                                    drawCircle(
                                        color = c,
                                        radius = size.minDimension * 0.2f,
                                        center = Offset(size.width * 0.5f, size.height * 0.32f),
                                        style = Stroke(width = strokeW)
                                    )
                                    drawArc(
                                        color = c,
                                        startAngle = 180f,
                                        sweepAngle = 180f,
                                        useCenter = false,
                                        topLeft = Offset(size.width * 0.16f, size.height * 0.52f),
                                        size = androidx.compose.ui.geometry.Size(size.width * 0.68f, size.height * 0.5f),
                                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    // 昵称：整块可点编辑资料（头像改头像、昵称区改昵称）
                    Column(
                        Modifier
                            .weight(1f)
                            .junPressable(feedback = PressFeedback.NONE) {
                                if (loggedIn) onProfile()
                                else onAccount()
                            }
                    ) {
                        Text(
                            if (loggedIn) state.accountName else state.localNickname.ifBlank { "未登录" },
                            color = colors.text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (loggedIn) "点击进入我的主页" else "点击登录 / 注册账号",
                            color = colors.subText,
                            fontSize = 11.sp
                        )
                    }
                    if (loggedIn) {
                        Box(
                            Modifier
                                .junPressable(feedback = PressFeedback.NONE) { editingName = true }
                                .clip(JunShape.chip)
                                .background(colors.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("编辑", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ===== 菜单区：可滚动 + 分组可折叠 =====
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // —— 意见反馈 ——
                // 放在全部分组之上、且用实色主色渐变，是整个抽屉里唯一"不像普通条目"的一行。
                // 理由：反馈入口的价值全在"用户想说话时能不能一眼看见"，
                // 埋在「关于」二级页里等于没有。
                DrawerFeedbackCard(colors, state.feedbackUnread) { onFeedback() }
                Spacer(Modifier.height(10.dp))

                // —— 账号与安全 ——
                CollapsibleSection("账号与安全", sections, colors, expandAllSections) {
                    if (loggedIn) {
                        DrawerRow("我的主页", "资料 · 听歌数据", colors) { onProfile() }
                    }
                    DrawerRow(
                        if (loggedIn) "账号：${state.accountName}" else "登录 / 注册",
                        if (loggedIn) "已登录 · 实时同步" else "同步我喜欢",
                        colors
                    ) { onAccount() }
                    if (loggedIn) {
                        DrawerRow("修改密码", "更改登录密码", colors) { onChangePassword() }
                    }
                    if (appFlags.netease) {
                        DrawerRow(
                            "网易云账号",
                            if (state.neteaseLoggedIn) "已登录：${state.neteaseName.ifBlank { "网易云" }}" else "扫码登录",
                            colors
                        ) { onNeteaseLogin() }
                    }
                }

                // —— 我的音乐 ——
                CollapsibleSection("我的音乐", sections, colors, expandAllSections) {
                    // 6.3 彻底修复版：下载管理是着重功能，放第一行；
                    // 「排行榜」按要求去掉（榜单入口在「发现」类的网易云界面里，不再占这屏）。
                    if (appFlags.download) {
                        DrawerRow("下载管理", "${state.downloadTasks.count { it.status == DownloadStatus.COMPLETED }} 首已下载", colors) { onDownload() }
                    }
                    Box(Modifier.spotlightAnchor(SpotlightKeys.LIBRARY_MODE)) {
                        DrawerRow(
                            "图书馆模式",
                            when {
                                state.libraryFiltering -> "正在筛选纯音乐…"
                                state.libraryMode -> "已开启 · 纯音乐 / 白噪音"
                                else -> "轻音乐 · 纯音乐 · lofi"
                            },
                            colors
                        ) { onToggleLibraryMode() }
                    }
                    DrawerRow("我喜欢的音乐", "${state.likedSongs.size} 首 · 本机", colors) { onLiked() }
                    if (appFlags.netease) {
                        DrawerRow(
                            "网易云我喜欢",
                            if (state.neteaseLoggedIn) "网易云 · 红心" else "需登录",
                            colors
                        ) { onNeteaseLiked() }
                    }
                    // 6.0：按用户要求从「我的」里去掉「听歌待办」和「新功能导览」两行。
                    // 导览还留在「设置 → 重看」；待办的数据和本机记录都不动，只是不再占这一屏。
                    if (appFlags.playlist) {
                        DrawerRow("我的歌单", "${state.playlists.size} 个 · 本机", colors) { onPlaylists() }
                    }
                    if (appFlags.netease) {
                        DrawerRow("网易云歌单", if (state.neteaseLoggedIn) "我的 / 推荐" else "需登录", colors) { onNeteasePlaylists() }
                    }
                }

                // —— 发现 ——
                CollapsibleSection("发现", sections, colors, expandAllSections) {
                    // 6.3：听歌排行榜（段位 / 总榜 / 周榜 / 日榜）——从「我的」也够得着
                    DrawerRow("听歌排行榜", "段位 · 总榜 / 周榜 / 日榜", colors) { onRank() }
                    if (appFlags.netease) {
                        DrawerRow("每日推荐", if (state.neteaseLoggedIn) "网易云" else "需登录", colors) { onNeteaseDaily() }
                        // 6.3：排行榜已移到上面「我的音乐」第一行，这里不再重复
                    }
                }

                // —— 通用 ——
                CollapsibleSection("通用", sections, colors, expandAllSections) {
                    DrawerRow("界面布局", state.layout.label, colors) { showLayoutPicker = true }
                    // 收听报告已挪进「我的主页」（6.3 彻底修复版），这里不再重复
                    DrawerRow("设置", null, colors) { onSettings() }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ===== 底部：软件名字 + 图标 =====
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.app_logo),
                    contentDescription = "Jun Music",
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Jun", color = colors.primary, fontSize = 16.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(4.dp))
                        Text("Music", color = colors.secondary, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        color = colors.subText.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                }
            }
        }

        // 编辑昵称弹层
        if (editingName) {
            InputDialog(
                title = "修改昵称",
                value = nameInput,
                placeholder = "输入新昵称",
                colors = colors,
                onConfirm = {
                    if (it.isNotBlank()) onEditName(it.trim())
                    editingName = false
                },
                onDismiss = { editingName = false }
            )
        }

        // 布局选择器：4 种排布同时展示，点击直接切换
        if (showLayoutPicker) {
            val onSheet = if (colors.isDark) Color.White else Color.Black
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showLayoutPicker = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .junSurface(GlassLevel.SHEET, colors, JunShape.item)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {}
                        .padding(20.dp)
                ) {
                    Text("选择界面排布", color = onSheet, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    JunLayout.entries.forEachIndexed { idx, layout ->
                        val selected = state.layout == layout
                        Row(
                            Modifier
                                .fillMaxWidth()
                                // 静态弹层里的条目：错峰浮现 + 和全 App 同一套按压弹簧
                                .junPopItem(idx)
                                .junPressableRow {
                                    onSelectLayout(layout)
                                    showLayoutPicker = false
                                }
                                .clip(JunShape.item)
                                .background(if (selected) colors.primary.copy(alpha = 0.15f) else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                layout.label,
                                color = if (selected) colors.primary else colors.text,
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                Canvas(Modifier.size(16.dp)) {
                                    drawCircle(colors.primary, radius = size.minDimension * 0.5f)
                                    drawCircle(Color.White, radius = size.minDimension * 0.22f)
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

/** 简单的输入弹层：用于编辑昵称 / 头像 URL */
@Composable
private fun InputDialog(
    title: String,
    value: String,
    placeholder: String,
    colors: JunColors,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(value) { mutableStateOf(value) }
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val fieldBg = if (colors.isDark) Color(0x14FFFFFF) else Color(0x0F000000)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .junSurface(GlassLevel.SHEET, colors, JunShape.item)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .padding(20.dp)
        ) {
            Text(title, color = onSheet, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(JunShape.item)
                    .background(fieldBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = LocalJunUiFamily.current, color = colors.text, fontSize = 14.sp),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                    modifier = Modifier.fillMaxWidth()
                )
                if (text.isBlank()) {
                    Text(placeholder, color = colors.subText.copy(alpha = 0.5f), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    Modifier
                        .junPressable(feedback = PressFeedback.NONE) { onDismiss() }
                        .clip(JunShape.item)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("取消", color = colors.subText, fontSize = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .junPressable(feedback = PressFeedback.NONE) { onConfirm(text) }
                        .clip(JunShape.item)
                        .background(colors.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("确定", color = colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DrawerSection(title: String, colors: JunColors) {
    Text(
        title,
        color = colors.primary.copy(alpha = 0.85f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)
    )
}

/**
 * 可折叠分组：标题行带展开/折叠箭头，点击切换；内容仅在展开时渲染。
 * 用于「我的」抽屉，把过长的菜单收起来。6.0 起四个分组默认**全部收缩**，
 * 打开抽屉先看到四个标题，点哪个展开哪个。
 */
@Composable
private fun CollapsibleSection(
    title: String,
    sections: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    colors: JunColors,
    forceExpand: Boolean = false,
    content: @Composable () -> Unit
) {
    val expanded = forceExpand || (sections[title] ?: false)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .junPressableRow { sections[title] = !expanded }
                .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = colors.primary.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            // 展开/折叠箭头
            androidx.compose.animation.AnimatedVisibility(visible = !expanded) {
                Canvas(Modifier.size(10.dp)) {
                    val c = colors.subText.copy(alpha = 0.6f)
                    drawLine(c, Offset(size.width * 0.2f, size.height * 0.5f),
                        Offset(size.width * 0.8f, size.height * 0.5f),
                        strokeWidth = size.minDimension * 0.16f, cap = StrokeCap.Round)
                }
            }
            if (expanded) {
                Canvas(Modifier.size(10.dp)) {
                    val c = colors.subText.copy(alpha = 0.6f)
                    drawLine(c, Offset(size.width * 0.2f, size.height * 0.5f),
                        Offset(size.width * 0.8f, size.height * 0.5f),
                        strokeWidth = size.minDimension * 0.16f, cap = StrokeCap.Round)
                    drawLine(c, Offset(size.width * 0.5f, size.height * 0.2f),
                        Offset(size.width * 0.5f, size.height * 0.8f),
                        strokeWidth = size.minDimension * 0.16f, cap = StrokeCap.Round)
                }
            }
        }
        if (expanded) {
            content()
        }
    }
}

/**
 * 「我的」抽屉最上面的意见反馈入口。
 *
 * 视觉上刻意和其它条目不同：实色主色→次色横向渐变、白色图标、两行字、右侧箭头。
 * 抽屉里其余条目都是"透明行 + 灰字"，这一行是整个面板唯一的高饱和色块，
 * 一眼就能落到它上面——反馈入口的价值全在"想说话时看得见"。
 */
@Composable
private fun DrawerFeedbackCard(colors: JunColors, unread: Int = 0, onClick: () -> Unit) {
    // 主色底上的字色：浅色主题下主色偏亮，纯白会糊；让 readableOn 按对比度自己挑黑或白
    val ink = Color.White.readableOn(colors.primary)
    Row(
        Modifier
            .fillMaxWidth()
            .junPressableRow(scaleDown = 0.97f) { onClick() }
            .clip(JunShape.item)
            .background(Brush.horizontalGradient(listOf(colors.primary, colors.secondary)))
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(ink.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(JunIcons.Comment, contentDescription = null, modifier = Modifier.size(16.dp), tint = ink)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("意见反馈", color = ink, fontSize = 15.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(2.dp))
            Text(
                if (unread > 0) "有 $unread 条新回复，点这里看" else "有问题直接说，会认真看",
                color = ink.copy(alpha = if (unread > 0) 1f else 0.82f),
                fontSize = 11.sp,
                fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Normal
            )
        }
        // 新回复红点：用醒目的纯红而不是主色，主色底上的主色点等于没画
        if (unread > 0) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF3B30)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (unread > 9) "9+" else unread.toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Spacer(Modifier.width(5.dp))
        }
        Text("›", color = ink.copy(alpha = 0.9f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DrawerRow(
    label: String,
    value: String?,
    colors: JunColors,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .junPressableRow { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = colors.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (value != null) {
            Text(value, color = colors.subText, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        // 右侧细箭头
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

// ==================== 全屏搜索页 ====================

/**
 * 搜索类页面的**柔焦面板基色**（R13 定的实色值，R14 只改"怎么用"这个色，不改它本身）。
 *
 * 根因：搜索页整块走 `GlassLevel.SHEET`，而 SHEET 的染色只有 0x8F（≈56% alpha）。
 * 在能跑真折射的机器上它会去取样首页背板，在 nova 6（Android 10 / 中端档）上
 * `glassSurface` 走的是 perfLight 分支——只在这 56% 染色下面垫一层首页列表的原样像素，
 * 于是"直接能透到外面那层"。文字对比度是数学问题：#FDFDFD 落在"56% 烟熏 + 白封面"上，
 * 实测能从 18:1 掉到 3:1 以下，再漂亮的折射也读不清。
 *
 * 所以这一页的地基必须**接近实色**：四套值按"比页面本色略抬一档"来定，深色不是纯黑
 * （纯黑下 8% 的行染色几乎看不出来，行与行会糊成一片），筱莓两套跟随粉底/粉黑底，
 * 只是把饱和度和明度往"能承字"的方向压。
 *
 * R14 用户接着说"这样又太没美观，像一块白板"——观感不在这里解决，在
 * [junSoftFocusPanel]：这一版把它按 **0.945（暗）/ 0.955（亮）** 的透明度画上去，
 * 只留 5% 上下的透光，再靠画出来的软焦渐变做"背后被糊过"的错觉。
 * 档位绝不回到 0.56 那种能认出封面原图的程度，可读性优先。
 */
internal fun junReadoutPanel(colors: JunColors): Color = when {
    colors.isXiaomei && colors.isDark -> Color(0xFF23101A)
    colors.isXiaomei -> Color(0xFFFFF3F8)
    colors.isDark -> Color(0xFF0F0F14)
    else -> Color(0xFFFBFBFC)
}

/**
 * 面板上"再凹一块"的输入框 / 胶囊底。这里给的仍然是**基色**（终值确定，不再叠两层半透明），
 * 真正上屏由 [junSoftFocusField] 按比整页更实的一档 alpha 画：元件面积小，
 * 同样比例的透光在大面板上只是"有点呼吸感"，压到一颗输入框上就直接糊到字底下了。
 */
internal fun junReadoutField(colors: JunColors): Color = when {
    colors.isXiaomei && colors.isDark -> Color(0xFF331B27)
    colors.isXiaomei -> Color(0xFFFFE8F1)
    colors.isDark -> Color(0xFF1C1C24)
    else -> Color(0xFFEEEFF2)
}

// ==================== 柔焦地基（画出来的"略微模糊"）====================
//
// 用户这一轮的原话是"现在太没美观，但不是要厚磨玻璃"。nova 6（Android 10）没有
// RenderEffect / Modifier.blur，系统模糊这条路本来就走不通；R13 为了把可读性捞回来
// 把整页刷成 100% 实色，代价就是"一块白板"。R14 的答案是**接近实色 + 自己画软焦**：
// 基色只留 5% 上下的透光（背后那层首页像素被压到几乎认不出内容，只余一点明暗起伏），
// 再在上面走 2~3 档渐变 + 一条内缘高光，眼睛把它读成"背后被糊过"。
// 每一档数值都同时喂给 [junReadoutInk] 做对比度校验，改这里必须同步改那里的系数。

/** 整页柔焦基色的不透明度：暗 0.945 / 亮 0.955（用户要的那一档：0.93~0.96 / 0.94~0.97） */
internal const val JUN_SOFT_PANEL_ALPHA_DARK = 0.945f
internal const val JUN_SOFT_PANEL_ALPHA_LIGHT = 0.955f

/** 元件（输入框 / 胶囊）比整页再实一档，小面积上透光会直接压字 */
internal const val JUN_SOFT_FIELD_ALPHA_DARK = 0.955f
internal const val JUN_SOFT_FIELD_ALPHA_LIGHT = 0.965f

/** 软焦渐变三档：顶亮 / 底暗。[junReadoutInk] 按它们折算最亮、最暗两种真实底 */
internal const val JUN_SOFT_TOP_LIFT_DARK = 0.055f
internal const val JUN_SOFT_TOP_LIFT_LIGHT = 0.22f
internal const val JUN_SOFT_BOTTOM_SHADE_DARK = 0.12f
internal const val JUN_SOFT_BOTTOM_SHADE_LIGHT = 0.035f

/**
 * 这一页专用的文字配色：把 text / subText / primary 拿到**本页真正叠出来的几种底**上过一遍 WCAG。
 *
 * 不直接改 [JunColors]：主题 token 是按旧的实色卡片底调的，而这一页的底是上面那套面板基色 +
 * 行/卡各一层薄染色 + 主色药丸再叠 15~18%，四种叠法出来的终值都不一样。改主题会牵动全站，
 * 在这里按本页实际绘制顺序逐层校验，只把不达标的往背景反方向推，达标的一律原样返回
 * （[readableOn] 自带记忆化，每帧几次查表而已）。
 *
 * R14 起这一层不能只校验 `panel` 那个"名义底色"了：地基改成柔焦之后，真正画到屏幕上的
 * 是 **基色(94.5%) + 背后透上来的 5.5% + 顶部提亮 + 底部压暗 + 对角色偏**。背后那层是
 * 首页的封面和列表，最亮能是一整张白封面、最暗能是纯黑，所以每一种底都要按
 * "往上偏一档、往下偏一档"再各验一遍——这才对得上实际像素，不是名义上校验。
 *
 * 阈值分工：正文 7:1（远超 4.5，扫歌时一眼要能抓到）、次级信息与强调色 4.5:1。
 * 顺带把 [JunColors.card] / [JunColors.row] 也列入校验：实色材质下这一页用的就是它们，
 * 同一套 ink 两种材质都够用，不必分叉。实色材质下这套系数只会让字更"用力"一点，不会读反。
 */
internal fun junReadoutInk(colors: JunColors, panel: Color): JunColors {
    // 透光 + 渐变能造成的最大偏移：向上 = 1 - 面板 alpha + 顶亮；向下 = 同理 + 底暗。
    // 取面板那一档（比元件更透），元件用同一组上界只会更保守，不会漏验。
    val bleed = if (colors.isDark) 1f - JUN_SOFT_PANEL_ALPHA_DARK else 1f - JUN_SOFT_PANEL_ALPHA_LIGHT
    val lighten = bleed + (if (colors.isDark) JUN_SOFT_TOP_LIFT_DARK else JUN_SOFT_TOP_LIFT_LIGHT)
    val darken = bleed + (if (colors.isDark) JUN_SOFT_BOTTOM_SHADE_DARK else JUN_SOFT_BOTTOM_SHADE_LIGHT)
    fun fit(fg: Color, min: Float): Color {
        // 系数取的是**真实画出来的那层**：暗色 nested CARD 本体是 0x1F 白（12.2%），
        // 这里取 0.13 略高一档兜住取整；亮色 nested CARD 是 0x12 黑（7.1%），0.08 已经盖过它。
        val rowAndCard = if (colors.isDark) lerp(panel, Color.White, 0.13f) else lerp(panel, Color.Black, 0.08f)
        val pill = lerp(panel, colors.primary, 0.18f)
        val grounds = listOf(panel, rowAndCard, pill, colors.card, colors.row)
        var c = fg
        // 两遍：第一遍把不达标的推到刚好达标，第二遍回头补"被上一遍推过头"的那些底。
        // （单遍链式校验是先验的后验的会互相拽，两遍以后基本收敛。）
        repeat(2) {
            grounds.forEach { bg ->
                c = c.readableOn(bg, min)
                    .readableOn(lerp(bg, Color.White, lighten), min)
                    .readableOn(lerp(bg, Color.Black, darken), min)
            }
        }
        return c
    }
    return colors.copy(
        text = fit(colors.text, 7f),
        subText = fit(colors.subText, 4.5f),
        primary = fit(colors.primary, 4.5f)
    )
}

/**
 * 面板上的一层极淡磨砂渐变（只在液态玻璃材质下画）。
 *
 * 系统 backdrop blur（`RenderEffect` / `Modifier.blur`）要 API 31+，华为 nova 6 是 Android 10，
 * 根本没有这个能力——用户说"实在不行弄模糊一点"，但**不能把可读性押在模糊上**。
 * 这里画的是不依赖任何系统能力的静态渐变：顶部提亮、底部压一档，再各补一条极淡的
 * 内缘高光/内缘阴影，读起来是"一块有厚度的柔焦板"而不是"一张白纸"。
 * 两档偏移量都取自 JUN_SOFT_* 常量，和 [junReadoutInk] 折算最亮/最暗底用的是同一组数字，
 * 改这里必须同步改那里，否则对比度校验就成了名义上的。
 */
internal fun Modifier.junFrostSheen(dark: Boolean): Modifier = drawWithCache {
    val lift = if (dark) JUN_SOFT_TOP_LIFT_DARK else JUN_SOFT_TOP_LIFT_LIGHT
    val shade = if (dark) JUN_SOFT_BOTTOM_SHADE_DARK else JUN_SOFT_BOTTOM_SHADE_LIGHT
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = lift),
        0.42f to Color.Transparent,
        1f to Color.Black.copy(alpha = shade)
    )
    // 内缘：顶边 1.6dp 的极淡白线（板子受光的那一侧）、底边同宽的极淡黑线（吃进去的那一侧）。
    // 亮色模式白线给到 0.5 是因为面板本来就接近白，不加大就完全看不出来。
    val edgeHi = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.10f else 0.5f), 1f to Color.Transparent
    )
    val edgeLo = Brush.verticalGradient(
        0f to Color.Transparent, 1f to Color.Black.copy(alpha = if (dark) 0.20f else 0.06f)
    )
    val edgePx = 1.6.dp.toPx()
    onDrawBehind {
        drawRect(sheen)
        drawRect(edgeHi, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, edgePx))
        drawRect(
            edgeLo, Offset(0f, size.height - edgePx),
            androidx.compose.ui.geometry.Size(size.width, edgePx)
        )
    }
}

/**
 * 整页地基：把 [junReadoutPanel] 的基色按 94.5% / 95.5% 画上去，只留 5% 上下的透光，
 * 再叠一层对角的柔和色偏（主色从左上、背景色从右下）和 [junFrostSheen] 的顶亮/底暗。
 *
 * 之所以是"画出来的软焦"而不是系统模糊：nova 6 上没有 RenderEffect；而 R13 的纯实色板
 * 虽然读得清，但用户原话"像一块白板，太没美观"。透光压到 5% 这个量级时，背后那层首页
 * 像素已经糊到认不出内容，只剩下明暗起伏——正好是"略微模糊"的观感，又不会把字冲淡。
 */
internal fun Modifier.junSoftFocusPanel(colors: JunColors, dark: Boolean): Modifier =
    junSoftFocusSurface(
        junReadoutPanel(colors),
        if (dark) JUN_SOFT_PANEL_ALPHA_DARK else JUN_SOFT_PANEL_ALPHA_LIGHT,
        colors,
        dark
    )

/**
 * 元件版的柔焦底（搜索输入框 / 胶囊），用 [junReadoutField] 的基色，比整页再实一档：
 * 小面积上同样比例的透光会直接压在字底下，所以 alpha 抬高 0.01。
 * 调用点需要先 `.clip(shape)` 再挂这个 modifier，圆角由 clip 的那层 graphicsLayer 裁掉。
 */
internal fun Modifier.junSoftFocusField(colors: JunColors, dark: Boolean): Modifier =
    junSoftFocusSurface(
        junReadoutField(colors),
        if (dark) JUN_SOFT_FIELD_ALPHA_DARK else JUN_SOFT_FIELD_ALPHA_LIGHT,
        colors,
        dark
    )

/** 两个柔焦底共用的绘制栈：基色 → 对角色偏 → 顶亮/底暗 + 内缘。Brush 全部走 drawWithCache 缓存 */
private fun Modifier.junSoftFocusSurface(
    base: Color,
    alpha: Float,
    colors: JunColors,
    dark: Boolean
): Modifier = this
    .drawWithCache {
        val fill = base.copy(alpha = alpha)
        val tint = Brush.linearGradient(
            0f to colors.primary.copy(alpha = if (dark) 0.05f else 0.035f),
            0.5f to Color.Transparent,
            1f to colors.background.copy(alpha = if (dark) 0.10f else 0.05f)
        )
        onDrawBehind {
            drawRect(fill)
            drawRect(tint)
        }
    }
    .junFrostSheen(dark)

private const val HISTORY_MAX = SearchHistoryStore.MAX

/**
 * 四个搜索分类的固定顺序（0 单曲 / 1 歌手 / 2 专辑 / 3 歌单），与 `PlayerUiState.searchTab` 的编号一致。
 *
 * 以前这串字面量写在 SearchScreen 的 else 分支里：每次组合都新建一个 List，而它是
 * HorizontalPager 页 lambda 的捕获值之一 —— 键永远"不相等"，横扫时三页跟着白白重组。
 * 提到文件顶层之后它就是一个常量引用，memo 比较天然过关。
 */
private val SEARCH_TAB_LABELS = listOf("单曲", "歌手", "专辑", "歌单")

/**
 * 6.9：搜索源切换 chip —— 与分类药丸同一套实色小胶囊视觉：
 * 28dp 高、JunShape.item、选中描边 ink.primary，字号 11.5sp。
 * 纯实色，不参与玻璃/毛玻璃/实色任何材质分支，三态下观感一致。
 */
@Composable
private fun SearchSourceChip(
    label: String,
    selected: Boolean,
    panel: Color,
    ink: JunColors,
    onClick: () -> Unit
) {
    val idleBg = if (ink.isDark) lerp(panel, Color.White, 0.05f) else lerp(panel, Color.Black, 0.05f)
    val activeBg = if (ink.isDark) lerp(panel, Color.White, 0.12f) else lerp(panel, Color.Black, 0.12f)
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .junPressable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .clip(JunShape.item)
                .background(if (selected) activeBg else idleBg)
                .then(
                    if (selected) Modifier.border(1.1.dp, ink.primary, JunShape.item)
                    else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(
                label,
                color = if (selected) ink.primary else ink.subText,
                fontSize = 11.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

/**
 * 结果列表的固定行距与左右/顶部内边距：同上一样属于"每帧新建毫无意义"的东西。
 * （bottom 要跟着 bottomInset 走，那颗在页内 remember。）
 */
private val SEARCH_LIST_GAP = Arrangement.spacedBy(6.dp)
private val SEARCH_LIST_SIDE = 22.dp
private val SEARCH_LIST_TOP = 4.dp

private fun loadHistory(ctx: Context): List<String> = SearchHistoryStore.load(ctx)

private fun saveHistory(ctx: Context, list: List<String>) = SearchHistoryStore.save(ctx, list)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchScreen(
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit,
    onQuery: (String) -> Unit,
    onPlaySong: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onToggleLike: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    /** 切换搜索分类 tab（0单曲 1歌手 2专辑 3歌单） */
    onSetTab: (Int) -> Unit,
    /**
     * 6.9：切换搜索源（"netease" / "qq"）。QQ 源只搜单曲，
     * songId 带 qq: 前缀，点播直达 QQ 换链。默认空实现，老调用点不受影响。
     */
    onSourceSelect: (String) -> Unit = {},
    /**
     * 6.3.2：只预加载某一类，不切 tab。
     *
     * 邻页（HorizontalPager 提前组合的那一页）发现自己是空的时调它，
     * 数据回来就自然显示结果，用户不会再看到「歌手还没搜过，点这里切过去」。
     * 默认空实现，不影响任何老调用点。
     */
    onPreloadTab: (Int) -> Unit = {},
    onOpenArtist: (id: String, name: String, pic: String) -> Unit,
    onOpenAlbum: (id: String, name: String, artist: String) -> Unit,
    onOpenPlaylist: (id: String, name: String) -> Unit,
    /** 单曲搜索结果滚动到底时加载下一页 */
    onLoadMore: () -> Unit = {},
    /** 列表底部留白：给浮在页面之上的底部小播放器让位，避免最后几首被压住。 */
    bottomInset: Dp = 16.dp,
    /** 批量选择上下文：全选范围 = 当前这页搜索结果里的单曲 */
    select: ListSelect = ListSelect(),
    /** 搜索页底部「榜单」卡片：点卡片标题区 → 打开那个榜的完整列表 */
    onOpenToplist: (String, String) -> Unit = { _, _ -> },
    /**
     * 榜单卡片里的「播放」：按榜 ID 取回整榜再播（6.0）。
     * 以前这里传的是卡片预览那几首（[List] 直接进队列），所以"点播放只有九首"。
     */
    onPlayToplist: (String, String) -> Unit = { _, _ -> },
    /** 「猜你喜欢」右上角换一批 */
    onRefreshGuess: () -> Unit = {},
    /** 进搜索页时拉榜单卡片的预览数据（force = 重新拉） */
    onLoadToplistPreviews: (Boolean) -> Unit = {},
    /** 进搜索页时确保「猜你喜欢」有内容（只在为空时抽一批，不打扰已有的一批） */
    onEnsureGuess: () -> Unit = {}
) {
    val ctx = LocalContext.current
    var history by remember { mutableStateOf(loadHistory(ctx)) }
    val scope = rememberCoroutineScope()
    val ime = LocalSoftwareKeyboardController.current
    /** 收键盘：滑列表、横扫切 tab 时都调用。键盘撑着会挡掉半屏，还会吃掉结果区的横扫手势。 */
    // R14：这颗 lambda 会一路传到每一个结果页当参数。写成 `{ ime?.hide() }` 的话每次组合都是
    // 一个新实例，页参数"永远不相等"，后面窄参数化换来的整页 skip 会当场失效。remember 住它，
    // 引用就恒定，IME 那层本来也只会在切材质时变。
    val hideIme: () -> Unit = remember(ime) { { ime?.hide() } }
    val query = state.searchQuery

    // commit 同理：它只碰 history 这个状态对象、ctx、scope（三样跨帧都稳定），
    // 所以可以 remember 成一颗恒定引用的 lambda —— 下面「搜索后播放全部」和 onCommit
    // 都要把它塞进每一页的参数里。
    val commit: (String) -> Unit = remember(ctx, scope) {
        { q: String ->
            val t = q.trim()
            if (t.isNotEmpty()) {
                history = (listOf(t) + history.filter { it != t }).take(HISTORY_MAX)
                saveHistory(ctx, history)
                if (AccountStore.isLoggedIn) { scope.launch { AuthApi.pushSearchKeyword(t) } }
            }
        }
    }

    LaunchedEffect(Unit) {
        // R9：进搜索页**不再自动弹键盘**。以前延时 120ms 强行抢焦点，
        // 结果是"一打开就跳键盘"，还要为键盘的弹出重排一整屏 —— 
        // 用户报的"打开搜索界面非常卡"里有它一份。想打字点一下输入框即可。
        //
        // 这一轮再把下面三处**推到入场动画之后**（用户："那个过渡一卡一卡的"）。
        // 这三件事都会写 PlayerUiState，而这个 state 是整个 MainScreen 共用的一个对象：
        // 动画途中任何一次写入都会把整棵搜索页重组成一遍，帧就掉在过渡的正中间。
        // 收敛时间不再写死：这一轮把弹簧整体放慢了一大截，这里的等待跟着 JunMotion
        // 的 appearSettleMs 走，保证"画面静止之后"才动数据（改一处两处都会跟着变）。
        delay(JunMotion.appearSettleMs.toLong())
        onEnsureGuess()
        // 榜单那一排卡片的预览数据也在进页面时铺：先读内存/磁盘里的上一次结果，
        // 没有才发请求（loadToplistPreviews 内部判数量与新度）。
        onLoadToplistPreviews(false)
        // 登录态下合并云端搜索历史
        if (AccountStore.isLoggedIn) {
            val cloud = AuthApi.fetchSearchHistory()
            if (cloud != null) {
                val merged = (cloud + history).distinct().take(HISTORY_MAX)
                history = merged
                saveHistory(ctx, merged)
            }
        }
    }

    val glass = isLiquidGlass
    // R13 把这一页从"透光的 SHEET"捞回实色（SHEET 只有 56% 染色，nova 6 上连取样都没有，
    // 首页列表直接透上来 → 用户原话"根本看不清楚"）；R14 再往回收一档：
    // 100% 实色读得清，但代价是"像一块白板"。现在的地基是 [junSoftFocusPanel]——
    // 基色只画到 0.945/0.955，留 5% 上下的透光，软焦的"糊感"完全靠画出来的渐变撑，
    // 不依赖任何 API 31+ 的系统模糊。文字仍按真实叠底走 [junReadoutInk] 校验。
    val panel = if (glass) junReadoutPanel(colors) else colors.background
    // 本页文字按真实叠出来的几层底重新校验一遍（不达标才动，达标原样返回）
    val ink = remember(colors, panel) { junReadoutInk(colors, panel) }
    // 输入框 / 历史胶囊的底：玻璃下也改成实色（原来那对 0x3D/0x52 白叠在透光面板上终值不定）
    val chipBg = if (glass) {
        junReadoutField(colors)
    } else {
        if (colors.isXiaomei) colors.row
        else if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC)
    }
    // 元件级的那一层柔焦：和整页同一套画法，只是透光档位收得更紧（见 [junSoftFocusField]）
    val chipSurface: Modifier = if (glass) {
        Modifier.junSoftFocusField(colors, colors.isDark)
    } else {
        Modifier.background(chipBg)
    }

    Column(
        Modifier
            .fillMaxSize()
            .then(if (glass) Modifier.junSoftFocusPanel(colors, colors.isDark) else Modifier.background(panel))
            .statusBarsPadding()
    ) {
        // 玻璃里的玻璃：这一页内部的卡片 / 行 / 胶囊都不要再回去取样首页背板，
        // 只在实色面板上叠一层薄染色 + 内缘。既让文字有确定底色，也省掉每行一条
        // 全背板流水线（nova 6 这种中端机上是实打实的帧数）。
        GlassContentColumn {
        // ---- 顶部搜索栏（与首页上栏同款玻璃卡片：同一圆角、同一材质、同一内边距）----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .junSurface(GlassLevel.CARD, colors, JunShape.card)
                .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .junPressable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(16.dp)) {
                    val c = ink.text
                    drawLine(
                        c, Offset(size.width * 0.8f, size.height * 0.08f),
                        Offset(size.width * 0.22f, size.height * 0.5f),
                        strokeWidth = size.minDimension * 0.14f, cap = StrokeCap.Round
                    )
                    drawLine(
                        c, Offset(size.width * 0.22f, size.height * 0.5f),
                        Offset(size.width * 0.8f, size.height * 0.92f),
                        strokeWidth = size.minDimension * 0.14f, cap = StrokeCap.Round
                    )
                }
            }
            // 搜索输入框：与上栏搜索框同款（灰底圆角 + 放大镜 + 自定义文字），
            // 不用 material3 TextField，避免与整体自绘 UI 风格割裂。
            // R14：底不再是一颗 100% 的实色 —— 玻璃材质下走元件级柔焦（比整页再实一档，
            // 小面积上透光会直接压在字底下），圆角照旧由前面那层 clip 裁掉。
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(JunShape.item)
                    .then(chipSurface),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(Modifier.size(15.dp)) {
                        val r = size.minDimension * 0.32f
                        val cx = size.width * 0.38f
                        val cy = size.height * 0.38f
                        drawCircle(
                            color = ink.subText, radius = r,
                            center = Offset(cx, cy), style = Stroke(width = size.minDimension * 0.12f)
                        )
                        drawLine(
                            color = ink.subText,
                            start = Offset(cx + r * 0.7f, cy + r * 0.7f),
                            end = Offset(size.width * 0.88f, size.height * 0.88f),
                            strokeWidth = size.minDimension * 0.12f, cap = StrokeCap.Round
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isBlank()) {
                            Text("搜索歌曲、歌手、专辑…", color = ink.subText, fontSize = 12.sp)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQuery,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontFamily = LocalJunUiFamily.current, color = ink.text, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            cursorBrush = SolidColor(ink.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { commit(query) })
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "取消",
                color = ink.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.junPressable { onDismiss() }
            )
        }

        if (query.isBlank()) {
            // ---- 空查询页：历史 + 猜你喜欢 + 榜单 ----
            //
            // R9 从 `Column + verticalScroll` 换成 LazyColumn。旧写法会把历史胶囊、
            // 8 张猜你喜欢（每张都要解码封面）、以及全部榜单卡（每卡 9 行歌名）
            // 在**打开搜索页的那一帧**一次性组合完 —— 这就是"点开搜索界面非常卡"的主因。
            // 懒加载之后首帧只组合屏幕上看得见的那几块。
            val homeList = rememberLazyListState()
            LaunchedEffect(homeList) {
                snapshotFlow { homeList.isScrollInProgress }
                    .distinctUntilChanged()
                    .collect { moving -> if (moving) hideIme() }
            }
            val rec = state.searchGuess
            LazyColumn(
                state = homeList,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomInset)
            ) {
                if (history.isNotEmpty()) {
                    // 分区标题统一走 SearchSectionHeader：字号、字重、左右留白和首页各区块一致，
                    // 搜索页不再像"另一个 App 的页面"。
                    item(key = "hist-head") {
                        SearchSectionHeader(
                            title = "搜索历史", note = "共 ${history.size} 条", colors = ink,
                            trailing = {
                                Text(
                                    "清空",
                                    color = ink.subText, fontSize = 12.sp,
                                    modifier = Modifier.junPressable(feedback = PressFeedback.NONE) {
                                        history = emptyList(); saveHistory(ctx, history)
                                    }
                                )
                            }
                        )
                    }
                    item(key = "hist-chips") {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            history.forEach { kw ->
                                KeywordChip(kw, chipBg, ink, glass) { onQuery(kw) }
                            }
                        }
                    }
                }

                // ② 猜你喜欢：两列卡片（形状照用户给的截图），但每格有封面 + 歌名 + 歌手，
                //    材质走 App 自己的玻璃表面组件，跟首页/曲库同款观感。
                //    点一格直接播这首歌，不用先搜——这里要的是"我能听什么"。
                //
                //    R9 换数据源：原来这里读 state.recommendations，而 recommendations 是
                //    "每播放一首就追加一条"的列表（见 addToRecommendationsIfAppropriate），
                //    所以在榜单里点一首，猜你喜欢就长一首 —— 用户报的那个"凭空多一首"
                //    就是这条链路。现在改成进搜索页时从候选池抽固定的一批（searchGuess），
                //    只有右上角「换一批」才重摇，播放行为不再影响它。
                item(key = "guess-head") {
                    SearchSectionHeader(
                        title = "猜你喜欢", note = "点一下就播", colors = ink,
                        trailing = {
                            Box(
                                Modifier
                                    .junPressable { onRefreshGuess() }
                                    .clip(JunShape.chip)
                                    .padding(6.dp)
                            ) {
                                Icon(
                                    JunIcons.Refresh, contentDescription = "换一批",
                                    tint = ink.subText, modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
                if (rec.isEmpty()) {
                    item(key = "guess-empty") {
                        Text(
                            "多听几首，这里就会长出你自己的推荐",
                            color = ink.subText, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                        )
                    }
                } else {
                    rec.chunked(2).forEachIndexed { rowIdx, pair ->
                        item(key = "guess-$rowIdx-${pair.map { it.id }.joinToString("-")}") {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pair.forEach { song ->
                                    GuessTile(
                                        song = song, colors = ink,
                                        modifier = Modifier.weight(1f),
                                        onClick = { Haptics.tap(); onPlaySong(song) }
                                    )
                                }
                                // 落单的一格补空位，右边不会被拉宽
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                // ③ 榜单：横滑的整卡，一张卡就是一个榜（标题 + 播放 + 带序号的预览曲目）。
                //    点卡片空白 → 打开那个榜的完整列表；点某一首 → 直接播；
                //    点「播放」→ 6.0 起先取整榜再播，不再只播预览这一屏。
                item(key = "top-head") { SearchSectionHeader("榜单", "整榜能播，点卡片看全榜", ink) }
                item(key = "top-rail") {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val previews = state.toplistPreviews
                        if (previews.isEmpty()) {
                            // 还没拉到就先摆两张骨架卡，别让这一屏空着像坏了
                            repeat(2) { ToplistCardPlaceholder(ink) }
                        } else {
                            previews.forEach { p ->
                                ToplistCard(
                                    name = p.name,
                                    songs = p.songs,
                                    colors = ink,
                                    onPlaySong = onPlaySong,
                                    onPlayAll = { onPlayToplist(p.id, p.name) },
                                    onOpen = { onOpenToplist(p.id, p.name) }
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // ---- 6.9：搜索源切换（网易云 / QQ音乐）----
            // chips 与下面分类药丸同一套实色小胶囊视觉（28dp 体系）。
            // 6.9.3：QQ 源不再只有单曲 —— 歌手(t=9)/专辑(t=8) 走 QQ 公开搜索；
            // QQ 没有公开歌单搜索，「歌单」tab 两个源都用网易云歌单（点开照样四层取源）。
            val isQq = state.searchSource == "qq"
            val tabLabels = SEARCH_TAB_LABELS
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SearchSourceChip("网易云", !isQq, panel, ink) { onSourceSelect("netease") }
                SearchSourceChip("QQ音乐", isQq, panel, ink) { onSourceSelect("qq") }
            }
            // ---- 搜索分类 tab（单曲 / 歌手 / 专辑 / 歌单）----
            // 分类名固定在文件顶层（SEARCH_TAB_LABELS）：写在分支里的那串字面量每次组合都新建一个
            // List，而它是 HorizontalPager 页 lambda 的捕获值之一，键永远不相等 → 三页跟着白重组。
            // 6.9：QQ 源时换成单元素 tabLabels（本地 val，只在这一分支里用）。
            // tab 能左右滑：四类结果共用一个 HorizontalPager，切页即切 tab。
            val pagerState = rememberPagerState(
                initialPage = state.searchTab.coerceIn(0, tabLabels.lastIndex)
            ) { tabLabels.size }
            val tabFromVm = state.searchTab

            // 顶栏高亮的**唯一依据**改成本地派生值：手势进行中直接看 pager 自己的 targetPage，
            // 落定之后才回到 VM 的 tab。以前一律等 VM —— 要等 settle 动画跑完、settle 上报、
            // 请求发出、state 回写整条回路走完，于是"我已经滑到歌手了，上面那排药丸就是特别慢"。
            // targetPage 在手指一动时就更新，比拿 currentPage 四舍五入稳（半页时不会来回跳）。
            val activeIdx by remember(pagerState, tabFromVm) {
                derivedStateOf {
                    if (pagerState.isScrollInProgress) pagerState.targetPage else tabFromVm
                }
            }

            // 点药丸 → 让 pager 滚过去。这一条是 bug(b)"横扫被弹回 / 卡在歌手页"的现场：
            // pager 落定后 onSetTab 上报，VM 把同一个值再写回来，本 effect 把这次回读成
            // "用户点了药丸"，于是又补一次 animateScrollToPage —— 两个来源抢同一段滚动。
            // 现在套上多重护栏，任何一条成立就直接 return：
            //  - lastFromPager == target：这次回写是本页刚上报的**回声**，消费掉标记、什么都不做
            //  - 正在滚动 / targetPage / settledPage / currentPage 已在位：没有要补的动画
            // 这样点药丸照样会滚过去（那几条护栏全不成立），横扫绝不会被打回。
            var lastFromPager by remember { mutableStateOf(-1) }
            LaunchedEffect(tabFromVm, pagerState) {
                val target = tabFromVm.coerceIn(0, tabLabels.lastIndex)
                if (lastFromPager == target) {
                    lastFromPager = -1
                    return@LaunchedEffect
                }
                if (pagerState.isScrollInProgress) return@LaunchedEffect
                if (pagerState.targetPage == target) return@LaunchedEffect
                if (pagerState.settledPage == target) return@LaunchedEffect
                if (pagerState.currentPage == target) return@LaunchedEffect
                pagerState.animateScrollToPage(target, animationSpec = JunMotion.pagerSnap())
            }

            // 滑到位 → 通知 VM 换 tab（VM 里按 tab 惰性发请求，滑过去才开始搜那一类）。
            // 上报继续用 settledPage：多页横扫（3→0 一路路过 2、1）只有最终落点那一次会发请求。
            // 但比较的对象换成 rememberUpdatedState：这个协程只挂一次，以前读的是**首次组合那一刻
            // 的 state**，`page != state.searchTab` 永远在跟过期值比 —— 从歌单滑回单曲时被当成
            // "没变化"，VM 不动、pager 又被上一条 effect 的护栏按住，就是"卡在歌手那个界面不动了"。
            val latestTab = rememberUpdatedState(tabFromVm)
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }
                    .distinctUntilChanged()
                    .collect { page ->
                        // 先记下"这一页是我自己滑到的"再上报：上一条 effect 看到回声就跳过，
                        // 不会再补一次动画，手势不会被抢回去。
                        lastFromPager = page
                        if (page != latestTab.value) onSetTab(page)
                    }
            }
            // 横扫切页时同样先把键盘收起来，别让 IME 盖住半屏
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.isScrollInProgress }
                    .distinctUntilChanged()
                    .collect { moving -> if (moving) hideIme() }
            }

            Row(
                // 新功能导览有一步就照着这排分类讲"可以左右滑"
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp)
                    .spotlightAnchor(SpotlightKeys.SEARCH_TABS),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // R13：四个分类块是实色药丸（选中/未选中都有确定终值，不再"发虚"），这里只动热区。
                //
                // 6.3.2：用户反馈「单曲/歌手/专辑/歌单这几个控件要弄小一点，符合整体 UI 感」。
                // 上一版为了防误触把整块撑到 44dp（heightIn + padding 叠加实际约 44~48dp），
                // 四个并排在搜索框下面显得又厚又重，跟 App 其它地方的 28~30dp 小胶囊不是一个体系。
                //
                // 现在的做法：
                //   · 视觉高度 **28dp**（与播放页工具条、歌单页那排小胶囊一致）；
                //   · 字号 12sp → **11.5sp**，字重 Selected 用 Bold、未选中 Medium（不再全 Bold，视觉更轻）；
                //   · 圆角沿用 JunShape.item，左右 padding 收到 8dp；
                //   · **可点区域不减**：视觉 28dp，但外面套一层 `.heightIn(min = 40.dp)`
                //     的透明热区（放在 background 之前、clip 之后不影响视觉），
                //     手指的容错范围仍然接近 40dp，"变小"只发生在视觉上，不会变成误触源。
                val idleBg = if (colors.isDark) lerp(panel, Color.White, 0.05f) else lerp(panel, Color.Black, 0.05f)
                val activeBg = if (colors.isDark) lerp(panel, Color.White, 0.12f) else lerp(panel, Color.Black, 0.12f)
                tabLabels.forEachIndexed { idx, label ->
                    val selected = activeIdx == idx
                    Box(
                        Modifier
                            .weight(1f)
                            // 热区：整格撑到 40dp 高，内含那颗 28dp 的视觉胶囊
                            .heightIn(min = 40.dp)
                            .junPressable { onSetTab(idx) },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .clip(JunShape.item)
                                .background(if (selected) activeBg else idleBg)
                                .then(
                                    if (selected) Modifier.border(1.1.dp, ink.primary, JunShape.item)
                                    else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (selected) ink.primary else ink.subText,
                                fontSize = 11.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 每一页真正要读的字段在这里一次取完（都是 VM 里引用不变的列表 / 标量）。
            // 以前把整只 state 传进 SearchTabBody，而 PlayerUiState 在播放中每个进度/歌词 tick
            // 都换一个新的 data class 实例：三页的参数"永远不相等"→ 每帧整页重组合，
            // beyondViewportPageCount = 1 又让邻页一起挨这份重组合。这就是 nova 6 上横扫掉帧的主因，
            // 也是"滑动一下就是要非常的快"的正解：窄参数 + 引用相等，Compose 才 skip 得掉整页。
            val results = state.searchResults
            val artists = state.searchArtists
            val albums = state.searchAlbums
            val playlistsFound = state.searchPlaylistsFound
            val searching = state.searching
            val loadingMore = state.searchLoadingMore
            val hasMore = state.searchHasMore
            val selectActive = select.active
            val selectedIds = select.selectedIds
            val playingId = state.currentSong?.id
            val rowSize = state.rowSize
            val showRowCover = state.showRowCover
            val showRowArtist = state.showRowArtist
            val showRowPlaylist = state.showRowPlaylist
            val showLikedBadge = state.showLikedBadge
            val likedIds = state.likedSongIds

            // select 是一颗每帧都新建的普通类（ListSelect 没有 equals，引用一变整页就没法 skip），
            // 所以拆成"布尔 + 稳定列表 + 三颗恒定 lambda"往下传。
            // rememberUpdatedState 保证"调用时永远拿到最新那一份"，remember 保证"引用不变"。
            // 同理 onPlayAll / onAddToPlaylist 来自 MainScreen 的内联 lambda，捕获了 state，
            // 每个 tick 都是新实例 —— 不转发的话窄参数化等于白做。
            val latestSelect = rememberUpdatedState(select)
            val latestOnPlayAll = rememberUpdatedState(onPlayAll)
            val latestOnAddToPlaylist = rememberUpdatedState(onAddToPlaylist)
            val onToggleSelected: (Long) -> Unit =
                remember(latestSelect) { { id -> latestSelect.value.onToggle(id) } }
            val onExitSelect: () -> Unit =
                remember(latestSelect) { { latestSelect.value.onExit() } }
            val onStartSelect: (String, List<Song>) -> Unit =
                remember(latestSelect) { { name, songs -> latestSelect.value.onStart(name, songs) } }
            val addToPlaylistHere: (Song) -> Unit =
                remember(latestOnAddToPlaylist) { { song -> latestOnAddToPlaylist.value(song) } }
            // 「播放全部」要先收一次搜索历史再播（这个先后顺序是原本就在的）；query 变了才新建
            val commitAndPlayAll: () -> Unit =
                remember(query, commit, latestOnPlayAll) { { commit(query); latestOnPlayAll.value() } }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // 相邻页提前组合：横扫时下一页已经在，跟手而不是"甩完才出现"。
                // 这一轮把页参数收窄之后，邻页在播放 tick 里能整页 skip，所以 1 保留 ——
                // 改成 0 虽然再省一点组合量，但用户会看到"甩完才出现的空白页"，不值。
                beyondViewportPageCount = 1,
                // 多选模式里禁用滑动：整屏都是勾选框，横扫会被读成"想选这一片"。
                // 横扫路过时也不会误点行项目：pager 的 pointerInput 一旦接走这次手势，
                // 行上的 clickable 会收到 Cancel 而不会吃到 touch-up，不需要再加别的拦截。
                userScrollEnabled = !selectActive
            ) { page ->
                SearchTabBody(
                    tab = page,
                    results = results,
                    artists = artists,
                    albums = albums,
                    playlistsFound = playlistsFound,
                    searching = searching,
                    isCurrentTab = page == tabFromVm,
                    loadingMore = loadingMore,
                    hasMore = hasMore,
                    colors = ink,
                    query = query,
                    bottomInset = bottomInset,
                    selectActive = selectActive,
                    selectedIds = selectedIds,
                    onToggleSelected = onToggleSelected,
                    onExitSelect = onExitSelect,
                    onStartSelect = onStartSelect,
                    playingId = playingId,
                    rowSize = rowSize,
                    showRowCover = showRowCover,
                    showRowArtist = showRowArtist,
                    showRowPlaylist = showRowPlaylist,
                    showLikedBadge = showLikedBadge,
                    likedIds = likedIds,
                    hideIme = hideIme,
                    onPlaySong = onPlaySong,
                    onPlayAll = commitAndPlayAll,
                    onToggleLike = onToggleLike,
                    onAddToPlaylist = addToPlaylistHere,
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = onOpenAlbum,
                    onOpenPlaylist = onOpenPlaylist,
                    onLoadMore = onLoadMore,
                    onCommit = commit,
                    // 空态里那句"切到这一类"和点 tab 走同一条路：让"还没搜过"有出口，
                    // 用户不会以为这一类坏了
                    onSelectTab = onSetTab,
                    // 6.3.2：邻页空着时直接预加载，不再亮"还没搜过"占位
                    onPreloadTab = onPreloadTab
                )
            }
        }
        // 收起 [GlassContentColumn]：这一页内部的玻璃元件到此为止不再嵌套降级
        }
    }
}

/**
 * 搜索结果的一页（tab 内容）。从 [SearchScreen] 里抽出来是为了配 HorizontalPager：
 * 四页共用一个 Composable，页与页之间靠 `tab` 分支，滑动/点 tab 都走同一份渲染。
 *
 * 每页有自己的列表状态，所以滚动收键盘要各页单独挂 —— 共用一个 LazyListState
 * 会让四页的滚动位置互相拽着走。
 *
 * R14 把参数表收窄成本页真正要读的那几样（原先直接收整只 [PlayerUiState]）：
 * state 在播放中每个进度/歌词 tick 都要换一个新的 data class 实例，于是三页（邻页靠
 * beyondViewportPageCount = 1 也在场）的参数永远不相等、每帧整体重组合 —— 这就是 nova 6
 * 上横扫掉帧的主因。改成收 `List<Song>` / 布尔 / 常量之后，列表实例由 VM 保持不变，
 * Compose 的参数相等判定就能在 tick 时直接把整页 skip 掉。
 * select（ListSelect 是每帧新建的普通类、没有 equals）也一并拆成布尔 + 稳定列表 + 恒定 lambda。
 */
@Composable
private fun SearchTabBody(
    tab: Int,
    results: List<Song>,
    artists: List<NeteaseApi.NsArtist>,
    albums: List<NeteaseApi.NsAlbum>,
    playlistsFound: List<NeteaseApi.NsPlaylistSearch>,
    /** VM 的全局搜索中标志：只有本页就是当前那一类时才认它（见下面 searching 那段） */
    searching: Boolean,
    /** 这一页是不是 VM 认为的当前分类（邻页不能显示"搜索中"，也不能写"没找到"） */
    isCurrentTab: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    colors: JunColors,
    query: String,
    bottomInset: Dp,
    selectActive: Boolean,
    selectedIds: List<Long>,
    onToggleSelected: (Long) -> Unit,
    onExitSelect: () -> Unit,
    onStartSelect: (String, List<Song>) -> Unit,
    /** 行项目的显示方式（原来也是从 state 上直接读，一样收成窄值才能 skip） */
    playingId: Long?,
    rowSize: Int,
    showRowCover: Boolean,
    showRowArtist: Boolean,
    showRowPlaylist: Boolean,
    showLikedBadge: Boolean,
    likedIds: Set<Long>,
    hideIme: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onToggleLike: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onOpenArtist: (String, String, String) -> Unit,
    onOpenAlbum: (String, String, String) -> Unit,
    onOpenPlaylist: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    onCommit: (String) -> Unit,
    /** 空态上的"切到这一类"：与点 tab、横扫切页同一条通路 */
    onSelectTab: (Int) -> Unit = {},
    /** 6.3.2：邻页空着时静默预加载这一类（不切 tab） */
    onPreloadTab: (Int) -> Unit = {}
) {
    // ---- 搜索结果（独立于首页曲库队列，不污染首页）----
    val listState = rememberLazyListState()
    // 列表的固定内边距/行距：spacedBy 和边距常量都在文件顶层，这颗 PaddingValues 只跟着
    // bottomInset（底部小播放器的高度）重建一次，不用每帧新建。
    val listPadding = remember(bottomInset) {
        PaddingValues(start = SEARCH_LIST_SIDE, end = SEARCH_LIST_SIDE, top = SEARCH_LIST_TOP, bottom = bottomInset)
    }
    // R13 的四态判定，两条前提：
    //  ① 上面的 searching 是**全局**的，而 HorizontalPager 会提前组合邻页
    //     （beyondViewportPageCount = 1）。不隔开的话，在"歌手"页搜索时邻着的"单曲"页
    //     也会一起变成"搜索中"，用户看到的是"结果被吞了"。所以只有当前这一类才认它。
    //  ② 邻页列表为空 ≠ 没搜到：VM 按 tab 惰性发请求，没轮上的那一类本来就是空的。
    //     这一类要单独说"还没搜过 + 怎么切过去"，不能一句"没找到"把三种状态糊成一团。
    val current = isCurrentTab
    val searchingThisTab = searching && current

    // ===== 6.3.2：邻页不再显示「还没搜过」，直接预加载 =====
    //
    // 用户原话：「左右滑动的时候，不能出现那种『还没被搜索过』的界面，直接加载就行了」。
    // 原来 HorizontalPager 的 beyondViewportPageCount = 1 会把邻页提前组合，而 VM 是
    // 按 tab 惰性发请求的（切过去才搜），于是邻页拿到的必然是空列表 → 亮出
    // 「歌手还没搜过 / 点这里切过去」这种占位。用户横扫路过时看着像坏了。
    //
    // 现在改成：这一页确实没有数据、也不在搜索中时，**主动让 VM 搜一次这一类**
    // （走 onSelectTab，它会更新 searchTab 并发请求）。请求一发出去 searching 就变真，
    // 下面的空态自然被「搜索中…」顶掉，视觉上就是"直接加载"。
    //
    // 加 120ms 延迟：横扫时邻页会在几十毫秒内连续组合，不加延迟的话一次划动可能
    // 触发两三个分类的请求；延迟后只在用户真的停在这一页（或滑得慢）时才发。
    val hasDataForTab = when (tab) {
        1 -> artists.isNotEmpty()
        2 -> albums.isNotEmpty()
        3 -> playlistsFound.isNotEmpty()
        else -> results.isNotEmpty()
    }
    LaunchedEffect(tab, query, hasDataForTab, searching) {
        if (searching || hasDataForTab) return@LaunchedEffect
        // 只在非当前页时补拉：当前页的空态交给 VM 自己的流程（它可能真的搜过且结果为空，
        // 这时候显示"没找到"才是对的，反复重发只会打满请求）。
        if (current) return@LaunchedEffect
        if (query.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        onPreloadTab(tab)
    }

    // 往下滑就收键盘：结果区只有一屏多点，键盘一直撑着会挡掉三分之一的可看内容，
    // 而且它会吃掉横扫手势（IME 上方那片区域归键盘）。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { moving -> if (moving) hideIme() }
    }
    // R13 补的这一层布局根不是多余的：HorizontalPager 的一页里，
    // 页 lambda 先后组合出来的多个兄弟（这里是"找到 N 位"计数行 + 结果列表）
    // 如果没有共同父布局，会各自拿到整页尺寸并**叠在同一处**——计数那行灰字直接压在
    // 第一条结果的标题上，两行字糊成一片，正是"文字发虚"的一个来源。
    // 有了 Column，纵向按行分配（列表吃 weight(1f)），兄弟之间只会上下排开。
    Column(Modifier.fillMaxSize()) {
        when (tab) {
            1 -> { // 歌手：只用 artists，绝不读 results（否则串位）
                NetTabCaption(count = artists.size, unit = "位歌手", searching = searchingThisTab, colors = colors)
                if (artists.isEmpty() && !searchingThisTab) {
                    NetTabEmpty(kind = "歌手", query = query, current = current, colors = colors) { onSelectTab(1) }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = SEARCH_LIST_GAP,
                        contentPadding = listPadding
                    ) {
                        itemsIndexed(artists, key = { i, a -> "a-$i-${a.id}" }) { _, a ->
                            NetRow(
                                a.name, "歌手", colors,
                                cover = a.pic,
                                // 占位色种子：网易云的 id 偶尔不是纯数字（海外站/百科类条目），
                                // toLongOrNull() 一失败整列就都落到 seed 0 —— 满屏同一个颜色的
                                // 方块，看着像"封面坏了"。用 hashCode 兜底，每格仍是稳定且不同的色。
                                coverId = a.id.toLongOrNull() ?: a.id.hashCode().toLong()
                            ) { Haptics.tap(); onOpenArtist(a.id, a.name, a.pic) }
                        }
                    }
                }
            }
            2 -> { // 专辑：只用 albums
                NetTabCaption(count = albums.size, unit = "张专辑", searching = searchingThisTab, colors = colors)
                if (albums.isEmpty() && !searchingThisTab) {
                    NetTabEmpty(kind = "专辑", query = query, current = current, colors = colors) { onSelectTab(2) }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = SEARCH_LIST_GAP,
                        contentPadding = listPadding
                    ) {
                        itemsIndexed(albums, key = { i, al -> "al-$i-${al.id}" }) { _, al ->
                            NetRow(
                                al.name, al.artist.ifEmpty { "专辑" }, colors,
                                cover = al.pic,
                                coverId = al.id.toLongOrNull() ?: al.id.hashCode().toLong()
                            ) {
                                Haptics.tap(); onOpenAlbum(al.id, al.name, al.artist)
                            }
                        }
                    }
                }
            }
            3 -> { // 歌单：只用 playlistsFound
                NetTabCaption(count = playlistsFound.size, unit = "个歌单", searching = searchingThisTab, colors = colors)
                if (playlistsFound.isEmpty() && !searchingThisTab) {
                    NetTabEmpty(kind = "歌单", query = query, current = current, colors = colors) { onSelectTab(3) }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = SEARCH_LIST_GAP,
                        contentPadding = listPadding
                    ) {
                        itemsIndexed(playlistsFound, key = { i, p -> "p-$i-${p.id}" }) { _, p ->
                            NetRow(
                                p.name, "歌单", colors,
                                cover = p.cover,
                                coverId = p.id.toLongOrNull() ?: p.id.hashCode().toLong()
                            ) { Haptics.tap(); onOpenPlaylist(p.id, p.name) }
                        }
                        // 歌单也接上"滚到底再要一页"：以前 offset 写死 0，一次拿完就到底，
                        // 用户滑到第 50 个就没了 —— "往左滑应该有非常多呀"说的是这个。
                        if (loadingMore || hasMore) {
                            item(key = "playlist-more") {
                                if (!loadingMore) {
                                    LaunchedEffect(Unit) { onLoadMore() }
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (loadingMore) {
                                        SearchLoadingSpinner(colors.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Text("加载更多…", color = colors.subText, fontSize = 12.sp)
                                    } else {
                                        Text(
                                            "加载更多",
                                            color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.junPressable { onLoadMore() }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> { // 单曲：只有这一类有「全选 / 播放全部」，作用对象是整页 results
                // R13 改的是这一排的**存在性**：
                //  ① 搜索中 → 只留"搜索中…"一行，两颗按钮这时一颗都不画：结果没就绪就不给点。
                //     旧写法不管有没有结果都把整排画出来，
                //     中间那句 Text 还带 weight(1f)，于是刚进页面时屏幕上是一排半截按钮
                //     加一句"找到 0 首"，就是用户说的"刚进去就处于居中的状态，根本音乐都没有"。
                //  ② 搜完是空 → 两颗按钮一律不画，整段交给下面的空态说话：0 首时"播放全部/全选"
                //     本来就没有作用对象，不给点才是对的（VM 里 playList 也已经是非空才播）。
                //  ③ 有结果 → 固定「左：全选｜中：找到 N 首｜右：播放全部」，两颗按钮各自贴边，
                //     计数用 weight(1f) + 居中填满中间，横向不会随内容长短漂移。
                if (searchingThisTab) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SearchLoadingSpinner(colors.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("搜索中…", color = colors.subText, fontSize = 12.sp)
                    }
                } else if (results.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 已经整屏选中 → 画带勾的框、文案变「取消全选」，点它退出多选
                        val allChecked = selectActive && results.all { it.id in selectedIds }
                        SelectAllEntry(colors, small = true, checked = allChecked) {
                            if (allChecked) onExitSelect()
                            else onStartSelect("搜索结果 · $query", results)
                        }
                        Text(
                            "找到 ${results.size} 首",
                            color = colors.subText, fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Box(
                            Modifier
                                .junPressable(feedback = PressFeedback.CLICK) { onCommit(query); onPlayAll() }
                                .clip(JunShape.item)
                                .background(colors.primary.copy(alpha = 0.15f))
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "播放全部",
                                color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                if (results.isEmpty() && !searchingThisTab) {
                    // 搜不到东西时给一个 App 统一风格的空状态（和曲库/歌词同款），
                    // 而不是整页空白——用户会以为界面坏了。
                    // R13 再分一刀：这一类**没轮上过**（邻页）时不能也写"没找到"，
                    // 那会让用户以为搜过了、结果是坏的；这种要给出口（点一下切过去）。
                    NetTabEmpty(kind = "单曲", query = query, current = current, colors = colors) { onSelectTab(0) }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = SEARCH_LIST_GAP,
                        contentPadding = listPadding
                    ) {
                        itemsIndexed(results, key = { i, song -> "s-$i-${song.id}" }) { _, song ->
                            SongRow(
                                song = song,
                                active = song.id == playingId,
                                colors = colors,
                                rowSize = rowSize,
                                showCover = showRowCover,
                                showArtist = showRowArtist,
                                showPlaylist = showRowPlaylist,
                                showLikedBadge = showLikedBadge,
                                liked = song.id in likedIds,
                                playlistNames = if (showRowPlaylist) Playlists.playlistsOfSong(song.id) else emptyList(),
                                selectMode = selectActive,
                                isSelected = song.id in selectedIds,
                                onClick = {
                                    if (selectActive) { onToggleSelected(song.id) }
                                    else { onCommit(query); onPlaySong(song) }
                                },
                                onLongPress = { if (selectActive) onExitSelect() else onAddToPlaylist(song) },
                                onToggleLike = { onToggleLike(song) }
                            )
                        }
                        if (loadingMore || hasMore) {
                            item(key = "search-more") {
                                // 列表滚到底、这一项被组合出来时自动加载下一页；
                                // 失败/没有更多时 searchHasMore 变 false，这一项自动消失。
                                if (!loadingMore) {
                                    LaunchedEffect(Unit) { onLoadMore() }
                                }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 18.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (loadingMore) {
                                        SearchLoadingSpinner(colors.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Text("加载更多…", color = colors.subText, fontSize = 12.sp)
                                    } else {
                                        Text(
                                            "加载更多",
                                            color = colors.primary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.junPressable { onLoadMore() }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } // 收起上面那层 Column：一页只有一个布局根
}

/**
 * 分类页的计数行（歌手 / 专辑 / 歌单共用）。
 *
 * 只在"有结果"或"正在搜"时出现：结果为空时这一行不留，让下面的空态独占发言，
 * 避免出现"找到 0 位歌手"配一整屏空白那种既不说明原因、也不给出口的观感。
 */
@Composable
private fun NetTabCaption(count: Int, unit: String, searching: Boolean, colors: JunColors) {
    if (count <= 0 && !searching) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (searching) {
            SearchLoadingSpinner(colors.primary)
            Spacer(Modifier.width(8.dp))
            Text("搜索中…", color = colors.subText, fontSize = 12.sp)
        } else {
            Text("找到 $count $unit", color = colors.subText, fontSize = 12.sp)
        }
    }
}

/**
 * 分类页的空态，分"搜了没有"和"还没搜过"两种说法。
 *
 * 为什么要拆：[SearchScreen] 用的是 HorizontalPager + beyondViewportPageCount = 1，
 * 邻页也会被提前组合。以前邻页一律显示"没找到「xxx」"，用户横扫路过时以为这一类坏了；
 * 而 VM 是按 tab 惰性发请求的（切过去才搜），这一类其实**还没搜过**。
 * 所以没搜过时文案要说清"点这里或左右滑切到这一类"，并给一颗真能点的按钮。
 */
@Composable
private fun NetTabEmpty(
    kind: String,
    query: String,
    current: Boolean,
    colors: JunColors,
    onPick: () -> Unit
) {
    // 空态独占这一页（计数行此时被 [NetTabCaption] 自己吞掉了），所以直接铺满并居中：
    // 以前只有 fillMaxWidth，整块内容贴在页面顶部、下面一大片真空，看起来像界面坏了。
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (current) {
            EmptyState(
                colors = colors,
                title = "没找到「$query」的$kind",
                body = "换个更短的关键词，或者左右滑切到别的分类再搜。",
                glyph = EmptyGlyphKind.DISC
            )
        } else {
            EmptyState(
                colors = colors,
                title = "${kind}还没搜过",
                body = "点这里、或者左右滑切到「$kind」，切过去就会自动搜。",
                glyph = EmptyGlyphKind.STACK
            )
            // 出口按钮：和「播放全部」同一套药丸语言（主色 15% 底 + 主色字），
            // 颜色取自 [junReadoutInk]，在这块实色面板上一定是读得清的。
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .junPressable(feedback = PressFeedback.CLICK) { onPick() }
                        .clip(JunShape.item)
                        .background(colors.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "切到$kind",
                        color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** 搜索加载动画：主题色旋转圆环，与整体玻璃/胶囊风格一致 */
@Composable
private fun SearchLoadingSpinner(color: Color) {
    val transition = rememberInfiniteTransition(label = "searchSpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "searchSpinnerAngle"
    )
    Canvas(Modifier.size(16.dp)) {
        val stroke = size.minDimension * 0.16f
        drawArc(
            color = color.copy(alpha = 0.25f),
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            style = Stroke(width = stroke)
        )
        drawArc(
            color = color,
            startAngle = angle, sweepAngle = 110f, useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordChip(
    text: String,
    bg: Color,
    colors: JunColors,
    glass: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .junPressable { onClick() }
            .clip(JunShape.item)
            // 液态玻璃下用与首页同款的玻璃小胶囊（CHIP），实色下用同色底，
            // 让搜索页的标签与整体材质语言保持一致。
            .then(
                if (glass) Modifier.junSurface(GlassLevel.CHIP, colors, JunShape.item)
                else Modifier.background(bg)
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 搜索页分区标题：全站同一套（15sp 特粗 + 一行小注 + 右侧可选控件），
 * 左边距和首页各区块一致，避免搜索页看起来像另一个 App。
 */
@Composable
private fun SearchSectionHeader(
    title: String,
    note: String,
    colors: JunColors,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Black)
        if (note.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                note,
                color = colors.primary.copy(alpha = 0.85f),
                fontSize = 11.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * 「猜你喜欢」的一格：封面 + 歌名 + 歌手。
 *
 * 两列的形状照用户给的截图，但材质用 App 自己的 junSurface（玻璃主题下是玻璃，
 * 实色主题下是卡片色），不再是孤零零一行纯文字——这样它才像这个 App 里的东西。
 */
@Composable
private fun GuessTile(
    song: Song,
    colors: JunColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // R9 收小一档（用户："猜你喜欢这几首歌曲，你弄小一点"）：
    // 封面 38→30dp、内边距 8→6dp、标题 13sp Bold→12sp、副标 11→10sp。
    // 保持两列：改三列每格只剩约 110dp，歌名和歌手放不下去。
    Row(
        modifier
            .junPressableRow(feedback = PressFeedback.NONE) { onClick() }
            .junSurface(GlassLevel.CARD, colors, JunShape.item)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SongArtwork(
            filePath = song.filePath.takeIf { it.isNotBlank() },
            seedId = song.id,
            modifier = Modifier.size(30.dp).clip(JunShape.chip),
            isDark = colors.isDark,
            noteIcon = true
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            Text(
                song.artist.ifBlank { "未知歌手" },
                color = colors.subText, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 搜索页榜单卡片：一张卡 = 一个榜。榜封面 + 标题 + 「播放」按钮，下面列前 9 首（1-3 名用强调色）。
 *
 * 形状照用户给的截图（横滑整卡 + 卡头播放 + 带序号的歌单），配色、圆角、材质全部
 * 用 App 自己的主题 token：卡片本体走 [junSurface]（玻璃主题下就是玻璃卡），
 * 不再自己写半透明实色 + 描边。
 */
@Composable
private fun ToplistCard(
    name: String,
    songs: List<Song>,
    colors: JunColors,
    onPlaySong: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onOpen: () -> Unit
) {
    // 榜单卡现在是嵌在实色面板里的薄玻璃（nestedBody 只有 8~12%），卡的终值 ≈ 面板色，
    // 所以按 card 折算出来的强调色仍旧偏低，11sp 的「播放」几乎浮不起来。阈值抬到 4.5。
    val accent = colors.primary.readableOn(colors.card, 4.5f)
    Column(
        Modifier
            .width(272.dp)
            .junPressable(scaleDown = 0.96f) { onOpen() }
            .junSurface(GlassLevel.CARD, colors, JunShape.card)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val first = songs.firstOrNull()
            SongArtwork(
                filePath = first?.filePath?.takeIf { it.isNotBlank() },
                seedId = first?.id ?: 0L,
                modifier = Modifier.size(38.dp).clip(JunShape.item),
                isDark = colors.isDark,
                noteIcon = true
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Black,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                // 6.9：字号 10 → 11sp、文案写明白 —— 之前太多用户没注意到卡片只是预览，
                // 以为整个榜/歌单就这几首。这里把「播放=整个榜」说清楚。
                Text(
                    "预览 ${songs.size} 首 · 点「播放」载入整个榜",
                    color = colors.subText, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier
                    .junPressable(feedback = PressFeedback.CLICK) { onPlayAll() }
                    .clip(JunShape.chip)
                    .background(accent.copy(alpha = 0.16f))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(JunIcons.Play, null, tint = accent, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(3.dp))
                Text("播放", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.text.copy(alpha = 0.08f)))
        Spacer(Modifier.height(2.dp))
        songs.forEachIndexed { i, song ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .junPressableRow { onPlaySong(song) }
                    .clip(JunShape.chip)
                    .padding(horizontal = 2.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${i + 1}",
                    color = if (i < 3) accent else colors.subText,
                    fontSize = 13.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.width(22.dp)
                )
                Text(
                    song.title,
                    color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    song.artist,
                    color = colors.subText, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(72.dp)
                )
            }
        }
    }
}

@Composable
private fun ToplistCardPlaceholder(colors: JunColors) {
    val block = colors.text.copy(alpha = 0.08f)
    // 骨架卡刻意**不做流光动画**：这是一张常驻的等待卡，老机型上每帧重绘一条渐变
    // 是纯耗电；卡片本体也和其它地方一样走 junSurface，材质统一。
    Column(
        Modifier
            .width(272.dp)
            .junSurface(GlassLevel.CARD, colors, JunShape.card)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(38.dp).height(38.dp).clip(JunShape.item).background(block))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(96.dp).height(16.dp).clip(JunShape.chip).background(block))
        }
        Spacer(Modifier.height(14.dp))
        repeat(6) {
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(14.dp).height(12.dp).clip(JunShape.chip).background(block))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.fillMaxWidth().height(12.dp).clip(JunShape.chip).background(block))
            }
        }
        Text("正在拉榜单…", color = colors.subText, fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp))
    }
}
