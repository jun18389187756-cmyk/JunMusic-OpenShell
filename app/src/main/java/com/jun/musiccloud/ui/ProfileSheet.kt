package com.jun.musiccloud.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.data.AccountStore
import com.jun.musiccloud.data.ListenCloud
import com.jun.musiccloud.data.ListenReportBuilder
import com.jun.musiccloud.data.ListenStats
import com.jun.musiccloud.data.PlayHistory
import com.jun.musiccloud.data.ServerApi
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.LocalJunUiFamily
import com.jun.musiccloud.ui.theme.isLiquidGlass
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.readableTextOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 本地听歌概览（从 [ListenStats] 明细现算，避免依赖报告页的缓存） */
private data class LocalListenStats(
    val totalMs: Long = 0L,
    val todayMs: Long = 0L,
    val days: Int = 0,
    val songs: Int = 0,
    /** 服务端旧版心跳累计（仅总时长，没有歌曲明细） */
    val legacyMs: Long = 0L,
    /** 最近 14 天每日时长（毫秒，按时间正序）：给报告卡的迷你柱状图用 */
    val recent: List<Long> = emptyList()
)

/** 4.0.3 补偿会员金色标识 */
private val PROFILE_VIP_GOLD = Color(0xFFF5C451)
private val PROFILE_VIP_INK = Color(0xFF2A1D00)

/**
 * 整页留白节奏。
 *
 * 原来左右 20dp 是散在各处的字面量，分区之间一会儿 18 一会儿 20，看着就是"乱"。
 * 这里收成一组常量：横向留白、分区之间、标题到卡片、卡片内边距各一档，
 * 改观感只要动这四个值，整页比例同步跟着走。
 */
private const val PROFILE_SIDE_PAD = 20
private const val PROFILE_SECTION_GAP = 18
private const val PROFILE_TITLE_GAP = 8
private const val PROFILE_CARD_PAD = 14

/**
 * 数据块在**实色材质**下的底色。
 *
 * [GlassLevel.CARD] 与 [GlassLevel.CHIP] 折回实色时都是同一个 `colors.card`，
 * 也就是说实色模式下"卡片"和"卡片里的格子"会糊成同一块颜色，2×2 的整齐感直接消失。
 * 玻璃模式没这个问题（层级决定染色浓度），所以只给实色分支单独定一档：
 * 深色往上抬一档白、浅色往下压一档黑，任何材质里块与卡都分得开。
 */
private fun profileTileSolid(colors: JunColors): Color =
    if (colors.isDark) Color(0x1FFFFFFF) else Color(0x0D000000)

/** 服务端 /api/profile 拉回来的资料 */
private data class CloudProfile(
    val nickname: String = "",
    val createdAt: Long = 0L,
    val deviceCount: Int = 0,
    val favorites: Int = 0,
    val playlists: Int = 0,
    val history: Int = 0,
    val listenSeconds: Long = 0L,
    val searches: Int = 0
)

/**
 * 「我的主页」：网易云风格的个人主页，但配色 / 圆角 / 玻璃材质全部走 App 现有主题。
 *
 * 三个用途：
 *  - 已登录：头像、昵称、注册时间、设备数、云端收藏与歌单一眼看全；
 *  - 未登录：显示本机昵称 / 本机头像和本地听歌数据，云端区改成登录引导；
 *  - 头像与昵称可直接在这里改，不用再绕去「账号与安全」。
 *
 * 排版（R14 重做）：
 *  - 面板 [fillMaxHeight] 0.92f 基本占满，正文自己滚；小屏（nova 6）不再被
 *    `heightIn(max=760.dp)` 压成一小截，大屏也不会顶到状态栏。
 *  - 信息按「个人资料 / 听歌数据 / 云端数据 / 账号操作」四段归类，每段一个小标题
 *    + 一张 CARD 底，段内数字块用 `Row + weight(1f)` 等分，不留硬编码偏移。
 *  - 数字层级拉开：数据块是大号数值 + 小号单位 + 更小号标签；明细行走「左标签右值」。
 */
@Composable
internal fun ProfileSheet(
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit,
    onPickAvatar: (android.net.Uri) -> Unit,
    onEditName: (String) -> Unit,
    onAccount: () -> Unit,
    onOpenReport: () -> Unit = {}
) {
    val loggedIn = state.accountLoggedIn
    val displayName = if (loggedIn) state.accountName else state.localNickname.ifBlank { "未登录" }
    val displayAvatar = if (loggedIn) state.accountAvatar else state.localAvatar

    val avatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? -> uri?.let { onPickAvatar(it) } }

    var local by remember { mutableStateOf(LocalListenStats()) }
    var cloud by remember { mutableStateOf<CloudProfile?>(null) }
    var editingName by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf(displayName) }
    val appContext = LocalContext.current.applicationContext

    // 同时随账号变化刷新：登录 / 换号后要显示「账号累计」而不是上一台手机的本机数据
    LaunchedEffect(
        state.likedSongs.size,
        state.playlists.size,
        state.accountUserId,
        state.listenSyncVersion,
    ) {
        local = withContext(Dispatchers.IO) {
            runCatching {
                // 已登录：本机 + 云端合并（账号累计）；未登录：只读本机
                val uid = state.accountUserId
                val logged = loggedIn && uid > 0L
                val entries = if (logged) {
                    ListenCloud.mergedEntries(appContext, uid)
                } else {
                    ListenStats.load()
                }
                val legacyDaily = if (logged) {
                    ListenCloud.cachedSnapshot(appContext, uid).legacyDaily.associate { it.day to it.ms }
                } else {
                    emptyMap()
                }
                val daily = ListenReportBuilder.mergedDaily(entries, legacyDaily)
                val today = ListenStats.todayEpochDay()
                LocalListenStats(
                    totalMs = daily.values.sum(),
                    todayMs = daily[today] ?: 0L,
                    days = daily.count { it.value > 0L },
                    songs = entries.map { it.songId }.distinct().size,
                    legacyMs = legacyDaily.values.sum(),
                    // 报告卡上的 14 根小柱子：只取近两周，缺的天补 0
                    recent = (13 downTo 0).map { daily[today - it] ?: 0L }
                )
            }.getOrDefault(LocalListenStats())
        }
    }

    LaunchedEffect(state.accountUserId, state.accountName) {
        if (!loggedIn || AccountStore.token.isBlank()) {
            cloud = null
            return@LaunchedEffect
        }
        cloud = withContext(Dispatchers.IO) {
            runCatching {
                val root = ServerApi.request("/api/profile", null, AccountStore.token) ?: return@runCatching null
                if (root.optInt("code", -1) != 0) return@runCatching null
                val d = root.optJSONObject("data") ?: return@runCatching null
                val u = d.optJSONObject("user")
                val c = d.optJSONObject("counts")
                CloudProfile(
                    nickname = u?.optString("nickname", "").orEmpty(),
                    createdAt = u?.optLong("created_at", 0L) ?: 0L,
                    deviceCount = d.optInt("device_count", 0),
                    favorites = c?.optInt("favorites", 0) ?: 0,
                    playlists = c?.optInt("playlists", 0) ?: 0,
                    history = c?.optInt("history", 0) ?: 0,
                    searches = c?.optInt("searches", 0) ?: 0,
                    listenSeconds = d.optLong("listenSeconds", d.optLong("listen_seconds", 0L))
                )
            }.getOrNull()
        }
    }

    // 6.3 彻底修复版：云端四个数字（收藏/歌单/历史/搜索）可点开看明细。
    // detailKind 非空时在面板内叠一层明细列表，数据全部来自本机已有的状态
    // （我喜欢 / 歌单 / 最近播放 / 搜索历史），不额外发请求。
    var detailKind by remember { mutableStateOf<String?>(null) }

    // 6.4：「听歌数据」与「云端数据」合并后的口径切换（0=本机，1=账号累计）。
    // 原来两段并排、今日/累计/歌数各出现一遍，用户分不清哪个是自己手机、哪个是账号累计。
    var dataScope by remember { mutableStateOf(0) }

    // 6.3：段位 / 头衔（与排行榜同一口径）。拉失败就整块不显示——主页不因为一个接口挂了变空。
    var tierInfo by remember { mutableStateOf<ServerApi.ListenTierInfo?>(null) }
    LaunchedEffect(state.accountUserId) {
        val dk = com.jun.musiccloud.data.Telemetry.deviceId(appContext)
        tierInfo = runCatching {
            ServerApi.fetchListenTier(state.accountUserId, dk)
        }.getOrNull()
    }

    // 段位：接口挂了也不能让这一块消失 —— 用本地累计时长兜底成一条无进度的档位
    // （标注「本地估算」），下一次拉取成功再补齐进度与头衔。
    val ti = tierInfo
    val tierFallback = if (ti == null && local.totalMs > 0) {
        val sec = local.totalMs / 1000
        val lv = profileTierOf(sec)
        ServerApi.ListenTierInfo(
            enabled = true, loggedIn = loggedIn, total = sec, week = 0, today = 0,
            tier = lv, tierName = profileTierName(lv), badge = profileTierBadge(lv),
            nextTier = 0, nextName = "", nextNeed = 0, titles = emptyList()
        )
    } else null
    val tierShown = ti ?: tierFallback
    val shortTotal = profileShortDuration(local.totalMs)

    val drag = rememberSheetDragState()
    val glass = isLiquidGlass
    val playedSongs = remember { PlayHistory.getPlayCounts().size }

    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        // 占满：按屏高比例给，不按 dp 给。760dp 上限在 nova 6（约 640dp 可视高）上等于
        // 整块被裁掉一截；fillMaxHeight(0.92f) 让面板吃满可用高度、底部对齐，
        // 顶部剩 8% 呼吸（约 52~64dp，够避开状态栏），大屏小屏都是自然比例。
        panelModifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f),
        // 导航条内边距留在外壳上（不随内容滚动），把手也固定，随时能下滑关闭
        contentModifier = Modifier.navigationBarsPadding()
    ) {
        SheetDragHandle(drag, onDismiss, colors.card, Modifier.padding(top = 6.dp))

        // 正文：weight(1f) 吃掉把手以外的全部高度，自己滚动 —— 小屏 overflowing 时
        // 是往下滚，而不是把每一块压扁到看不清。
        // 平板 / 折叠屏展开时整块不再横着铺满（两列格子会被拉到三四百 dp 宽），
        // 520dp 封顶后居中；手机上 fillMaxWidth 本来就不到这个值，看不出差别。
        Column(
            Modifier
                .weight(1f)
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PROFILE_SIDE_PAD.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ==================== ① 头部：头像 / 昵称 / 身份 / 数据带 ====================
            // R21 重排：把「标题 + 资料卡 + 两个数字块 + 修改昵称整行」压成一张头卡，
            // 数字从 2 个扩到 4 个（补段位、累计），一屏读完"我是谁、我听多少"。
            ProfileHero(
                displayName = displayName,
                displayAvatar = displayAvatar,
                seedId = state.accountUserId.takeIf { it > 0 } ?: displayName.hashCode().toLong(),
                loggedIn = loggedIn,
                vipClaimed = state.vipClaimed,
                vipName = state.vipName,
                sinceText = if (loggedIn) {
                    val since = formatJoinDate(cloud?.createdAt ?: 0L)
                    if (since.isNotBlank()) "Jun Music · $since 加入" else "Jun Music · 数据已同步"
                } else {
                    "本机模式 · 登录后收藏与记录可跨设备同步"
                },
                stats = listOf(
                    ProfileStat("我喜欢", "${state.likedSongs.size}", "首"),
                    ProfileStat("歌单", "${state.playlists.size}", "个"),
                    ProfileStat(
                        "段位",
                        tierShown?.let { "${it.tier}" } ?: "—",
                        if (tierShown != null) "段" else ""
                    ),
                    ProfileStat("累计", shortTotal.first, shortTotal.second)
                ),
                colors = colors,
                glass = glass,
                editing = editingName,
                nameInput = nameInput,
                onNameInput = { nameInput = it },
                onStartEdit = {
                    nameInput = displayName
                    editingName = true
                },
                onCancelEdit = { editingName = false },
                onSaveEdit = {
                    if (nameInput.isNotBlank()) onEditName(nameInput.trim())
                    editingName = false
                },
                onPickAvatar = { avatarPicker.launch("image/*") },
                onAccount = onAccount
            )

            // ==================== ② 听歌与段位：段位行 + 口径切换 + 数据格 ====================
            // 段位原来单独占一张卡（一个圆章 + 一条进度），和下面"听歌数据"信息量太少、
            // 卡片又一样大。合成一张之后，上半段回答"我什么水平"，下半段才是数字。
            ProfileSectionSpacer()
            ProfileSection("听歌与段位", colors, glass) {
                if (tierShown != null) {
                    ProfileTierRow(tierShown, colors)
                    Spacer(Modifier.height(13.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.text.copy(alpha = 0.07f))
                    )
                    Spacer(Modifier.height(13.dp))
                }

                // 6.4 合并口径：本机 / 账号累计二选一，同一时间只看到一套数字
                ProfileSegmented(
                    leftLabel = "本机",
                    rightLabel = "账号累计",
                    rightSelected = dataScope == 1 && loggedIn,
                    rightEnabled = loggedIn,
                    colors = colors
                ) { right ->
                    dataScope = if (right && loggedIn) 1 else 0
                }
                Spacer(Modifier.height(12.dp))

                if (dataScope == 1 && loggedIn) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 四个数字都能点开看明细（收藏看了什么 / 歌单里有什么…）
                        CloudMetricTile("收藏", cloud?.favorites ?: 0, "favorites", colors, Modifier.weight(1f)) { detailKind = it }
                        CloudMetricTile("歌单", cloud?.playlists ?: 0, "playlists", colors, Modifier.weight(1f)) { detailKind = it }
                        CloudMetricTile("历史", cloud?.history ?: 0, "history", colors, Modifier.weight(1f)) { detailKind = it }
                        CloudMetricTile("搜索", cloud?.searches ?: 0, "searches", colors, Modifier.weight(1f)) { detailKind = it }
                    }
                    Spacer(Modifier.height(10.dp))
                    ProfileInfoRows(colors, buildList {
                        add("注册时间" to formatJoinDate(cloud?.createdAt ?: 0L).ifBlank { "—" })
                        add("登录设备" to "${cloud?.deviceCount ?: 0} 台")
                        add("云端时长" to profileDuration((cloud?.listenSeconds ?: 0L) * 1000L))
                        if (state.vipClaimed) {
                            add(
                                "会员" to "${state.vipName.ifBlank { "内部尊享会员" }} · " +
                                    formatJoinDate(state.vipUntil).ifBlank { "—" }
                            )
                        }
                    })
                } else {
                    // 本机口径：四格主数据 + 两行次要明细
                    ProfileMetricRow {
                        ProfileDurationTile("今日听歌", local.todayMs, colors, Modifier.weight(1f))
                        ProfileDurationTile("累计听歌", local.totalMs, colors, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    ProfileMetricRow {
                        ProfileMetricTile("活跃天数", "${local.days}", "天", colors, Modifier.weight(1f))
                        ProfileMetricTile("听过的歌", "${local.songs}", "首", colors, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    ProfileInfoRows(colors, buildList {
                        add("播放记录" to "$playedSongs 首")
                        // 旧版心跳只有总时长、没有歌曲明细，并进累计之后单独交代一句，免得以为数字对不上
                        if (local.legacyMs > 0L) {
                            add("含旧版累计" to "${profileDuration(local.legacyMs)}（仅总时长）")
                        }
                    })
                    if (!loggedIn) {
                        // 未登录时这一块顺便承担「登录后能得到什么」的引导，
                        // 而不是留一排「—」占位（占位既读不出信息，又显得页面空）
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "登录后，收藏、歌单、播放历史与搜索记录会跟着账号走，换设备也不用重来。",
                            color = colors.subText,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(10.dp))
                        ProfileAction("登录 / 注册账号", true, colors, Modifier.fillMaxWidth()) { onAccount() }
                    }
                }
            }

            // 云端数据点开的明细（就地展开，不叠新弹层）
            if (detailKind != null) {
                Spacer(Modifier.height(8.dp))
                ProfileDetailList(
                    kind = detailKind!!,
                    state = state,
                    colors = colors,
                    onDismiss = { detailKind = null }
                )
            }

            // ==================== ③ 听歌报告：14 天柱状预览 + 入口 ====================
            // 原来是一张只有按钮和两行说明的卡，页面上三个"标题 + 卡"连排、节奏一样。
            // 这里把真实数据画成 14 根小柱子，卡片自己就有内容，也顺手说明报告里有什么。
            ProfileSectionSpacer()
            ProfileReportCard(
                recent = local.recent,
                colors = colors,
                glass = glass
            ) {
                onDismiss()
                onOpenReport()
            }

            Spacer(Modifier.height(26.dp))
        }
    }
}


/** 头部数据带的一格：数值 / 单位 / 标签 */
private data class ProfileStat(val label: String, val value: String, val unit: String)

/**
 * 头部卡（R21）：头像 / 昵称 / 身份徽标 / 加入时间 / 账号入口 + 一条四格数据带。
 *
 * 材质仍是 [GlassLevel.CARD] 玻璃，只在表面叠一层很低透明度的主色渐变给点头部色温；
 * 透明度压到 7%~18%，不遮玻璃本身的折射纹理，也不影响深色下的文字对比。
 */
@Composable
private fun ProfileHero(
    displayName: String,
    displayAvatar: String,
    seedId: Long,
    loggedIn: Boolean,
    vipClaimed: Boolean,
    vipName: String,
    sinceText: String,
    stats: List<ProfileStat>,
    colors: JunColors,
    glass: Boolean,
    editing: Boolean,
    nameInput: String,
    onNameInput: (String) -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onPickAvatar: () -> Unit,
    onAccount: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .junSurface(GlassLevel.CARD, colors, JunShape.card)
            .then(if (glass) Modifier.junFrostSheen(colors.isDark) else Modifier)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            colors.primary.copy(alpha = if (colors.isDark) 0.18f else 0.12f),
                            Color.Transparent,
                            colors.secondary.copy(alpha = if (colors.isDark) 0.10f else 0.07f)
                        ),
                        start = Offset.Zero,
                        end = Offset(460f, 300f)
                    )
                )
                .padding(PROFILE_CARD_PAD.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 头像整块 66dp 都是命中区；右下＋只是"这里能换图"的提示，共用同一个点击区
                    Box(
                        Modifier
                            .size(66.dp)
                            .junPressable { onPickAvatar() }
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        colors.primary.copy(alpha = 0.85f),
                                        colors.secondary.copy(alpha = 0.75f)
                                    ),
                                    start = Offset.Zero,
                                    end = Offset(180f, 180f)
                                )
                            ),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        if (displayAvatar.isNotBlank()) {
                            Box(Modifier.size(66.dp).clip(CircleShape)) {
                                SongArtwork(
                                    filePath = displayAvatar,
                                    seedId = seedId,
                                    artworkSize = 66.dp,
                                    isDark = colors.isDark
                                )
                            }
                        } else {
                            Box(Modifier.size(66.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    displayName.take(1).ifBlank { "J" },
                                    color = Color.White,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(colors.card)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(colors.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "＋",
                                color = colors.primary.readableTextOn(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Spacer(Modifier.width(13.dp))

                    Column(Modifier.weight(1f)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                displayName,
                                color = colors.text,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(8.dp))
                            // 编辑入口收成小胶囊（原先是整行 44dp 的「修改昵称」），
                            // 外圈仍撑到 44dp 命中区，小拇指也按得中
                            Box(
                                Modifier
                                    .heightIn(min = 44.dp)
                                    .junPressable { onStartEdit() },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    Modifier
                                        .clip(JunShape.chip)
                                        .background(colors.primary.copy(alpha = 0.13f))
                                        .padding(horizontal = 9.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        "编辑",
                                        color = colors.primary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ProfileBadge(
                                if (loggedIn) "已登录" else "本机",
                                colors.primary.copy(alpha = 0.14f),
                                colors.primary
                            )
                            if (loggedIn && vipClaimed) {
                                ProfileBadge(
                                    vipName.ifBlank { "内部尊享会员" },
                                    PROFILE_VIP_GOLD,
                                    PROFILE_VIP_INK,
                                    maxWidth = 92.dp
                                )
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            sinceText,
                            color = colors.subText,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .heightIn(min = 44.dp)
                            .junPressable { onAccount() },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .clip(JunShape.chip)
                                .background(colors.text.copy(alpha = 0.08f))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                if (loggedIn) "账号" else "登录",
                                color = colors.text.copy(alpha = 0.8f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (editing) {
                    Spacer(Modifier.height(12.dp))
                    NicknameEditor(
                        value = nameInput,
                        colors = colors,
                        onValueChange = onNameInput,
                        onCancel = onCancelEdit,
                        onSave = onSaveEdit,
                        flat = true,
                        showTitle = false
                    )
                }

                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.text.copy(alpha = 0.08f))
                )
                Spacer(Modifier.height(11.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    stats.forEachIndexed { index, stat ->
                        if (index > 0) {
                            Box(
                                Modifier
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(colors.text.copy(alpha = 0.08f))
                            )
                        }
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Text(
                                    stat.value,
                                    color = colors.text,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (stat.unit.isNotBlank()) {
                                    Spacer(Modifier.width(2.dp))
                                    Text(
                                        stat.unit,
                                        color = colors.subText,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                stat.label,
                                color = colors.subText,
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 口径分段控件（替代 6.4 的两个独立 chip）。
 *
 * 整条是一块凹槽（[junReadoutField] 或 8% 文本色），选中项只换底色 + 文字色，
 * 用颜色动画过渡而不是位移指示器：不用测量、不额外起图层，长列表滚动时没有额外开销。
 */
@Composable
private fun ProfileSegmented(
    leftLabel: String,
    rightLabel: String,
    rightSelected: Boolean,
    rightEnabled: Boolean,
    colors: JunColors,
    onSelect: (right: Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(JunShape.chip)
            .background(
                if (isLiquidGlass) junReadoutField(colors)
                else if (colors.isDark) Color(0x2CFFFFFF) else Color(0x16000000)
            )
            .padding(3.dp)
    ) {
        ProfileSegmentedItem(leftLabel, !rightSelected, true, colors, Modifier.weight(1f)) {
            onSelect(false)
        }
        ProfileSegmentedItem(rightLabel, rightSelected, rightEnabled, colors, Modifier.weight(1f)) {
            onSelect(true)
        }
    }
}

@Composable
private fun ProfileSegmentedItem(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    colors: JunColors,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (selected) colors.primary.copy(alpha = 0.16f) else Color.Transparent,
        label = "profileSegBg"
    )
    val fg by animateColorAsState(
        when {
            !enabled -> colors.subText.copy(alpha = 0.45f)
            selected -> colors.primary
            else -> colors.subText
        },
        label = "profileSegFg"
    )
    Box(
        modifier
            .heightIn(min = 38.dp)
            .junPressable(enabled = enabled) { onClick() }
            .clip(JunShape.chip)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 段位行（R21 紧凑版）：圆章 + 档位名 + badge + 细进度条 + 升段提示。
 *
 * 和排行榜档案卡同一套数据，但这里不再重复"进入排行榜"的箭头——
 * 人在「我的主页」里，这块只回答"我什么段位、还差多少"。
 */
@Composable
private fun ProfileTierRow(info: ServerApi.ListenTierInfo, colors: JunColors) {
    val c = tierColor(info.tier)
    val progress = if (info.nextTier <= 0) 1f else {
        val curMin = profileTierMinSeconds(info.tier)
        val nextMin = (info.total + info.nextNeed).coerceAtLeast(curMin + 1)
        ((info.total - curMin).toFloat() / (nextMin - curMin).coerceAtLeast(1)).coerceIn(0f, 1f)
    }
    val anim by animateFloatAsState(progress, label = "profileTierProgress")

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(c.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${info.tier}", color = c, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text(
                    "段",
                    color = c.copy(alpha = 0.75f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 3.dp, start = 1.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    info.tierName,
                    color = colors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(c.copy(alpha = 0.16f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(info.badge, color = c, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                if (info.loggedIn) "账号累计 ${formatBoardSeconds(info.total)} · 本周 ${formatBoardSeconds(info.week)}"
                else "本机累计 ${formatBoardSeconds(info.total)} · 注册后永久保存",
                color = colors.subText,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(colors.text.copy(alpha = 0.10f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(anim)
                .height(4.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.55f), c)))
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        if (info.nextTier <= 0) "已是最高段位 · ${info.tierName}"
        else "再听 ${formatBoardSeconds(info.nextNeed)} 升到「${info.nextName}」",
        color = colors.subText,
        fontSize = 10.5.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    if (info.titles.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            info.titles.take(3).forEach { (name, _) ->
                Box(
                    Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(c.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(name, color = c, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 听歌报告入口卡（R21）：14 天迷你柱状图 + 一行说明。
 *
 * 原来的卡只有一个按钮和两行字，和上下两张卡长得一样、内容却是空的；
 * 现在用真实的近两周时长画一排柱子，卡片本身有信息，也顺手交代了报告里有什么。
 */
@Composable
private fun ProfileReportCard(
    recent: List<Long>,
    colors: JunColors,
    glass: Boolean,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .junPressable { onClick() }
            .junSurface(GlassLevel.CARD, colors, JunShape.card)
            .then(if (glass) Modifier.junFrostSheen(colors.isDark) else Modifier)
            .padding(PROFILE_CARD_PAD.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("听歌报告", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "日 / 周 / 月 / 年 · 热力图 · 单曲排行",
                    color = colors.subText,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("›", color = colors.subText, fontSize = 16.sp)
        }
        Spacer(Modifier.height(12.dp))
        ProfileSparkline(recent, colors)
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("近 14 天", color = colors.subText, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("查看完整报告", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 14 天时长柱状图：无数据时退成浅色矮柱，不画空白 */
@Composable
private fun ProfileSparkline(values: List<Long>, colors: JunColors) {
    val data = if (values.isEmpty()) List(14) { 0L } else values
    val maxValue = (data.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val hasData = data.any { it > 0L }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        val n = data.size
        val gap = 3.dp.toPx()
        // 柱宽设上限：14 根铺满 320dp 会变成一排胖方块，压到 14dp 以内更像柱状图，
        // 整体再水平居中，左右留白对称
        val barWidth = ((size.width - gap * (n - 1)) / n)
            .coerceAtLeast(2f)
            .coerceAtMost(14.dp.toPx())
        val totalWidth = barWidth * n + gap * (n - 1)
        val startX = ((size.width - totalWidth) / 2f).coerceAtLeast(0f)
        val radius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
        val topColor = if (hasData) colors.primary.copy(alpha = 0.88f) else colors.subText.copy(alpha = 0.26f)
        val bottomColor = if (hasData) colors.primary.copy(alpha = 0.28f) else colors.subText.copy(alpha = 0.10f)
        data.forEachIndexed { index, value ->
            val ratio = if (value <= 0L) 0f else (value.toFloat() / maxValue).coerceIn(0.10f, 1f)
            val barHeight = (size.height * ratio).coerceAtLeast(3.dp.toPx())
            val left = startX + index * (barWidth + gap)
            val top = size.height - barHeight
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(topColor, bottomColor),
                    startY = top,
                    endY = size.height
                ),
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = radius
            )
        }
    }
}

/** 毫秒 → (数值, 单位)：数据带一格只放得下最粗的一档（23 小时 → 23 时，不显示分钟） */
private fun profileShortDuration(ms: Long): Pair<String, String> {
    if (ms <= 0L) return "0" to "分"
    val minutes = ms / 60_000L
    return when {
        minutes < 60L -> "$minutes" to "分"
        minutes < 24L * 60L -> "${minutes / 60L}" to "时"
        else -> "${minutes / (24L * 60L)}" to "天"
    }
}

/** 分区之间的固定间距：只有一处定义，四段节奏才不会走形 */
@Composable
private fun ProfileSectionSpacer() = Spacer(Modifier.height(PROFILE_SECTION_GAP.dp))

/**
 * 分区外壳：小标题 + 一张 CARD 底。
 *
 * 标题压到 subText、正文用 text，层级靠"颜色 + 字号"分而不是靠框线分；
 * 卡片走 [junSurface]，实色 / 毛玻璃 / 液态玻璃三种材质下都和全站同一块料。
 * 玻璃材质再叠一层 [junFrostSheen]，让大块卡片有磨砂明暗而不是一块死色。
 */
@Composable
private fun ProfileSection(
    title: String,
    colors: JunColors,
    glass: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            color = colors.subText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, bottom = PROFILE_TITLE_GAP.dp)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .junSurface(GlassLevel.CARD, colors, JunShape.card)
                .then(if (glass) Modifier.junFrostSheen(colors.isDark) else Modifier)
                .padding(PROFILE_CARD_PAD.dp),
            content = content
        )
    }
}

/** 数据块的一行：等分由调用点的 weight(1f) 负责，这里只管横向间距 */
@Composable
private fun ProfileMetricRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

/**
 * 大号数据块：数值最大、单位退到右下角、标签最小。
 *
 * 原来四项数据只有 17sp，和旁边的 10sp 标签拉不开，扫一眼抓不到重点；
 * 现在数值默认 22sp，单位 10sp，标签 11sp，三级分明。
 * `weight(1f, fill = false)` 让数值只吃单位以外的剩余宽度，长数值省略而不是顶出卡片。
 */
@Composable
private fun ProfileMetricTile(
    label: String,
    value: String,
    unit: String,
    colors: JunColors,
    modifier: Modifier = Modifier,
    valueSize: TextUnit = 22.sp
) {
    Column(
        modifier
            .junSurface(GlassLevel.CHIP, colors, JunShape.item, solid = profileTileSolid(colors))
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                value,
                color = colors.text,
                fontSize = valueSize,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    unit,
                    color = colors.subText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = colors.subText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 时长数据块：数值部分大字号、余下部分小字号，"1 天 3 小时"读成 1（天 3 小时） */
@Composable
private fun ProfileDurationTile(
    label: String,
    ms: Long,
    colors: JunColors,
    modifier: Modifier = Modifier
) {
    val (head, tail) = profileDurationParts(ms)
    ProfileMetricTile(label, head, tail, colors, modifier)
}

/** 昵称就地编辑：输入框 + 取消 / 保存，全都 ≥44dp，不叠新弹层 */
@Composable
private fun NicknameEditor(
    value: String,
    colors: JunColors,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    flat: Boolean = false,
    showTitle: Boolean = true
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (flat) Modifier
                else Modifier.junSurface(GlassLevel.ROW, colors, JunShape.item, solid = profileTileSolid(colors))
            )
            .padding(if (flat) 0.dp else 12.dp)
    ) {
        if (showTitle) {
            Text(
                "修改昵称",
                color = colors.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(JunShape.item)
                .background(
                    if (isLiquidGlass) junReadoutField(colors)
                    else if (colors.isDark) Color(0x2CFFFFFF) else Color(0x16000000)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = LocalJunUiFamily.current, color = colors.text, fontSize = 14.sp),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
                modifier = Modifier.fillMaxWidth()
            )
            if (value.isBlank()) {
                Text(
                    "输入新昵称",
                    color = colors.subText.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProfileAction("取消", false, colors, Modifier.weight(1f)) { onCancel() }
            ProfileAction("保存", true, colors, Modifier.weight(1f)) { onSave() }
        }
    }
}

/** 昵称旁的小徽标（登录态 / 会员），纯展示、不接收点击 */
@Composable
private fun ProfileBadge(
    label: String,
    bg: Color,
    fg: Color,
    maxWidth: Dp = 96.dp
) {
    Box(
        Modifier
            .heightIn(min = 20.dp)
            .widthIn(max = maxWidth)
            .clip(JunShape.chip)
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            label,
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 「左标签右值」明细行。
 *
 * 值给 1.5f 权重并右对齐：标签永远留在左边，长值（"3 天 6 小时 · 内部尊享会员"）
 * 自己省略，不会把整行顶出卡片。
 */
@Composable
private fun ProfileInfoRow(label: String, value: String, colors: JunColors) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = colors.subText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            value,
            color = colors.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.5f)
        )
    }
}

/** 明细行组：行间用 [JunColors.row] 分一下，四五行连排才不会糊成一段文字 */
@Composable
private fun ProfileInfoRows(colors: JunColors, rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.row.copy(alpha = if (colors.isDark) 0.5f else 0.7f))
                )
            }
            ProfileInfoRow(row.first, row.second, colors)
        }
    }
}

/** 分区里的动作按钮：48dp 高（>44dp 下限），主 / 次两档配色沿用全站 */
@Composable
private fun ProfileAction(
    label: String,
    primary: Boolean,
    colors: JunColors,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier
            .heightIn(min = if (compact) 40.dp else 48.dp)
            .junPressable { onClick() }
            .clip(JunShape.item)
            .background(
                if (primary) colors.primary else colors.subText.copy(alpha = 0.12f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (primary) colors.primary.readableTextOn() else colors.text,
            fontSize = if (compact) 12.sp else 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 毫秒 → 人话（0 显示「0 分钟」） */
private fun profileDuration(ms: Long): String {
    if (ms <= 0L) return "0 分钟"
    val totalMinutes = ms / 60_000L
    if (totalMinutes < 60L) return "$totalMinutes 分钟"
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    if (hours < 24L) return if (minutes > 0) "$hours 小时 $minutes 分" else "$hours 小时"
    val days = hours / 24L
    val restHours = hours % 24L
    return if (restHours > 0) "$days 天 $restHours 小时" else "$days 天"
}

/**
 * 把 [profileDuration] 拆成「大字号数值段 + 小字号单位段」。
 *
 * 不重算一遍口径：直接切第一个空格，"3 小时 20 分" → "3" + "小时 20 分"，
 * 数据块里的数字于是能放到 22sp，后面那截精度信息退回 10sp，一位都不丢。
 */
private fun profileDurationParts(ms: Long): Pair<String, String> {
    val text = profileDuration(ms)
    val split = text.indexOf(' ')
    return if (split < 0) text to "" else text.substring(0, split) to text.substring(split + 1)
}

/** 服务端 created_at（毫秒时间戳）→ 「2026-10-24」 */
private fun formatJoinDate(timeMs: Long): String {
    if (timeMs <= 0L) return ""
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timeMs))
    }.getOrDefault("")
}


/** 云端数据格：数字 + 标签，可点开看明细（6.3 彻底修复版） */
@Composable
private fun CloudMetricTile(
    label: String,
    value: Int,
    kind: String,
    colors: JunColors,
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit
) {
    Column(
        modifier
            .junPressable { onOpen(kind) }
            .junSurface(GlassLevel.CHIP, colors, JunShape.item, solid = profileTileSolid(colors))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "$value",
            color = colors.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1
        )
        Spacer(Modifier.height(3.dp))
        Text(label, color = colors.subText, fontSize = 11.sp, maxLines = 1)
    }
}

/**
 * 云端数据明细：在面板内就地展开一列（不叠新弹层、不发新请求）。
 * 数据口径：
 *  - 收藏 → 本机「我喜欢」（state.likedSongs，登录时是从账号分区重读的）；
 *  - 歌单 → state.playlists（名字 + 歌数）；
 *  - 历史 → state.recentPlaybacks（歌名 + 什么时候听的）；
 *  - 搜索 → SearchHistoryStore（本机最近搜过的词）。
 */
@Composable
private fun ProfileDetailList(
    kind: String,
    state: PlayerUiState,
    colors: JunColors,
    onDismiss: () -> Unit
) {
    val appContext = LocalContext.current
    val title = when (kind) {
        "favorites" -> "收藏明细（我喜欢）"
        "playlists" -> "我的歌单"
        "history" -> "最近播放"
        else -> "搜索记录"
    }
    // 搜索历史要在 IO 读文件，其余直接用 state
    var searches by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(kind) {
        if (kind == "searches") {
            searches = withContext(Dispatchers.IO) {
                runCatching { com.jun.musiccloud.data.SearchHistoryStore.load(appContext) }.getOrDefault(emptyList())
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .junSurface(GlassLevel.ROW, colors, JunShape.card, solid = profileTileSolid(colors))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = colors.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                Modifier
                    .heightIn(min = 32.dp)
                    .junPressable(onClick = onDismiss)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("收起", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            val rows: List<Pair<String, String>> = when (kind) {
                "favorites" -> state.likedSongs.map { (it.title to it.artist) }
                "playlists" -> state.playlists.map { (it.name to "${it.songCount} 首") }
                "history" -> state.recentPlaybacks.map {
                    (it.title to "${it.songs.size} 首")
                }
                else -> searches.map { it to "" }
            }
            if (rows.isEmpty()) {
                Text(
                    "还没有内容",
                    color = colors.subText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            }
            rows.take(60).forEachIndexed { i, (l, r) ->
                if (i > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.row.copy(alpha = if (colors.isDark) 0.5f else 0.7f))
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        l,
                        color = colors.text,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (r.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            r,
                            color = colors.subText,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (rows.size > 60) {
                Text(
                    "只显示前 60 条，完整列表在对应页面里",
                    color = colors.subText,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/** 本地兜底：秒数 → 档位（与服务端 TIERS 对齐） */
private fun profileTierOf(sec: Long): Int = when {
    sec >= 2000 * 3600L -> 11
    sec >= 1000 * 3600L -> 10
    sec >= 600 * 3600L -> 9
    sec >= 300 * 3600L -> 8
    sec >= 150 * 3600L -> 7
    sec >= 60 * 3600L -> 6
    sec >= 20 * 3600L -> 5
    sec >= 5 * 3600L -> 4
    sec >= 3600L -> 3
    sec >= 600L -> 2
    else -> 1
}

private val TIER_NAMES = listOf(
    "初听者", "歌单收集者", "通勤听歌人", "常听听众", "深夜循环者",
    "耳机常驻", "资深乐迷", "音乐探路人", "听觉鉴赏家", "殿堂听众", "耳机钉子户"
)
private val TIER_BADGES = listOf(
    "初听", "收集", "通勤", "常听", "夜循", "常驻", "乐迷", "探路", "鉴赏", "殿堂", "钉子户"
)
private fun profileTierName(lv: Int) = TIER_NAMES.getOrElse(lv - 1) { "初听者" }
private fun profileTierBadge(lv: Int) = TIER_BADGES.getOrElse(lv - 1) { "初听" }

/** 各档门槛秒数（与服务端 TIERS 对齐，仅用于画进度） */
private fun profileTierMinSeconds(tier: Int): Long = when (tier) {
    1 -> 0L; 2 -> 600L; 3 -> 3600L; 4 -> 5 * 3600L; 5 -> 20 * 3600L
    6 -> 60 * 3600L; 7 -> 150 * 3600L; 8 -> 300 * 3600L; 9 -> 600 * 3600L
    10 -> 1000 * 3600L; else -> 2000 * 3600L
}
