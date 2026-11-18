package com.jun.musiccloud.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.data.AccountStore
import androidx.compose.ui.graphics.asImageBitmap
import com.jun.musiccloud.data.ServerApi
import com.jun.musiccloud.ui.theme.GlassContentColumn
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.isLiquidGlass
import com.jun.musiccloud.ui.theme.junSurface
import kotlinx.coroutines.delay

/**
 * 排行榜页（6.3）：听歌时长榜 + 段位体系。
 *
 * 用户原话：「在用户界面加一个排行榜，要一个显眼的位置，点开就会出现这些人，
 * 段位也会展现出来，要实时更新。」（后按用户要求收紧：只显示前 10、不显示总人数）
 *
 * 为什么是独立整页而不是首页分区：首页那块放哪都挡，而且榜单需要"看得久"——
 * 段位、名次、时长都是要比较的信息，一整页才放得下，也才像"排行榜"。
 *
 * 实时更新：进入页面先拉一次，之后每 20 秒静默刷新（服务端 45 秒缓存 + 20 秒响应缓存）；
 * 刷新失败保留旧数据、只在标题行给个「暂未更新」，绝不清空闪白。
 */
/** 只展示前 10 名（用户指定）：不再展开、不再显示总人数 */
private const val TOP_N = 10

// ==================== 6.6.9：榜单本地快照（解决"打开要等好几秒"）====================
//
// 用户反复反馈"APP 端的用户时长排行榜加载太慢"。服务端冷路径要全表聚合
//（实测 1.6s），网络再叠一层，打开就是一段白屏等。
// 现在把上一次成功的数据落盘：点开排行榜先用本地快照**立刻出画面**，
// 网络请求在后台刷新、到位后无缝替换 —— 感知延迟从"1~2 秒空白"变成 0。
private const val BOARD_CACHE_PREFS = "jun_prefs"
private fun boardCacheKey(range: String) = "listen_board_snapshot_$range"

private fun loadBoardSnapshot(ctx: android.content.Context, range: String): ServerApi.BoardResult? =
    runCatching {
        val raw = ctx.getSharedPreferences(BOARD_CACHE_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(boardCacheKey(range), null) ?: return null
        val arr = org.json.JSONArray(raw)
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ServerApi.BoardEntry(
                rank = o.optInt("rank", i + 1),
                key = o.optString("key"),
                name = o.optString("name").ifBlank { "神秘听众" },
                avatar = o.optString("avatar"),
                seconds = o.optLong("seconds"),
                total = o.optLong("total"),
                guest = o.optBoolean("guest", false),
                tier = o.optInt("tier", 1),
                tierName = o.optString("tierName", "初听者"),
                badge = o.optString("badge", "初听")
            )
        }
        if (items.isEmpty()) null else ServerApi.BoardResult(true, items.size, items, null)
    }.getOrNull()

private fun saveBoardSnapshot(ctx: android.content.Context, range: String, r: ServerApi.BoardResult) {
    runCatching {
        val arr = org.json.JSONArray()
        r.items.forEach { e ->
            arr.put(org.json.JSONObject().apply {
                put("rank", e.rank); put("key", e.key); put("name", e.name)
                put("avatar", e.avatar); put("seconds", e.seconds); put("total", e.total)
                put("guest", e.guest); put("tier", e.tier)
                put("tierName", e.tierName); put("badge", e.badge)
            })
        }
        ctx.getSharedPreferences(BOARD_CACHE_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(boardCacheKey(range), arr.toString()).apply()
    }
}

@Composable
internal fun RankScreen(
    colors: JunColors,
    deviceKey: String,
    onDismiss: () -> Unit,
    /** 点自己那行 / 顶部档案卡：打开我的听歌档案 */
    onOpenMyTier: () -> Unit
) {
    val glass = isLiquidGlass
    val panel = if (glass) junReadoutPanel(colors) else colors.background
    val ink = remember(colors, panel) { junReadoutInk(colors, panel) }

    var range by remember { mutableStateOf("all") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 6.6.9：首帧直接用上一次的快照（有就秒开，没有才转圈）
    var board by remember { mutableStateOf(loadBoardSnapshot(ctx, "all")) }
    var tier by remember { mutableStateOf<ServerApi.ListenTierInfo?>(null) }
    var loading by remember { mutableStateOf(board == null) }
    var stale by remember { mutableStateOf(false) }

    // 「我的名次」的定位键：注册用户用 uid，游客用设备键。
    // 用 remember 固定成稳定字符串 —— 排行榜每 20 秒刷新一次 tier，
    // 若直接依赖 tier 变化，会连带重启榜单轮询（请求翻倍、列表闪一下）。
    val meKey = remember(deviceKey, AccountStore.userId) {
        if (AccountStore.isLoggedIn && AccountStore.userId > 0) "u${AccountStore.userId}"
        else if (deviceKey.isNotBlank()) "g$deviceKey" else ""
    }

    // 前台可见性：退到后台时暂停轮询，回来立刻补一次——省流量、也不会在后台一直打接口
    var foreground by remember { mutableStateOf(true) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> foreground = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> foreground = false
                else -> {}
            }
        }
        val lc = owner.lifecycle
        lc.addObserver(observer)
        onDispose { lc.removeObserver(observer) }
    }

    // 档案（段位 / 进度 / 彩蛋头衔）：进页面拉一次，之后 30 秒一次（段位变化本来就慢）
    LaunchedEffect(foreground) {
        if (!foreground) return@LaunchedEffect
        while (true) {
            val info = runCatching {
                ServerApi.fetchListenTier(AccountStore.userId, deviceKey)
            }.getOrNull()
            if (info != null) tier = info
            delay(30_000L)
        }
    }

    // 榜单：切档 / 展开立刻拉，之后每 20 秒静默刷新。
    // range/limit/foreground 任一变化都会重启这个循环 —— 上一轮 delay 被自动取消，不会叠加请求。
    LaunchedEffect(range, foreground) {
        if (!foreground) return@LaunchedEffect
        // 6.6.9：切档时先贴该档位的本地快照（上一次看过的数据），再让网络去刷新
        loadBoardSnapshot(ctx, range)?.let { snap ->
            board = snap
            stale = false
        }
        loading = board == null || board?.items.isNullOrEmpty()
        while (true) {
            val r = runCatching { ServerApi.fetchListenBoard(range, TOP_N, meKey) }.getOrNull()
            if (r != null) {
                board = r
                stale = false
                if (r.enabled) {
                    saveBoardSnapshot(ctx, range, r)
                } else {
                    // 后台关了排行榜：把本档快照一起删掉，下次打开不会再闪出旧榜单
                    runCatching {
                        ctx.getSharedPreferences(BOARD_CACHE_PREFS, android.content.Context.MODE_PRIVATE)
                            .edit().remove(boardCacheKey(range)).apply()
                    }
                }
            } else {
                stale = true
            }
            loading = false
            delay(20_000L)
        }
    }

    // 后台可以关掉排行榜：接口返回 enabled=false 时整页换成关闭态。
    // 旧版完全没读这个字段 —— 关了之后前端还在拿本地快照 + 每 20 秒轮询，
    // 用户看到的就是"后台关掉了，前台还显示一堆东西"。
    val closed = board != null && !board!!.enabled
    val items = board?.items.orEmpty()

    // 入场：整页从下方 30dp 推入 + 淡入（用户："打开的时候太僵硬了"）
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val enter by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            340, easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "rankEnter"
    )

    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = enter
                translationY = (1f - enter) * 32.dp.toPx()
            }
            .then(
                if (glass) Modifier.junSoftFocusPanel(colors, colors.isDark)
                else Modifier.background(panel)
            )
            .statusBarsPadding()
    ) {
        GlassContentColumn {
            // ---------- 标题栏 ----------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .junSurface(GlassLevel.CHIP, colors, JunShape.card)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(40.dp).junPressable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas15(ink.text)
                }
                Column(Modifier.weight(1f).padding(start = 2.dp)) {
                    Text(
                        "听歌排行榜",
                        color = ink.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        when {
                            closed -> "管理员已暂停这个功能"
                            loading && board == null -> "正在加载…"
                            stale -> "网络不稳，显示上一次的数据"
                            else -> "总榜 / 周榜 / 日榜 · 实时更新"
                        },
                        color = ink.subText,
                        fontSize = 10.5.sp
                    )
                }
            }

            if (closed) {
                RankClosed(ink = ink, onDismiss = onDismiss)
            } else {
                // ---------- 我的档案卡（段位 + 进度 + 彩蛋头衔） ----------
                MyTierCard(
                    tier = tier,
                    colors = colors,
                    ink = ink,
                    onClick = onOpenMyTier
                )

                // ---------- 档位切换 ----------
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("all" to "总榜", "7" to "周榜", "today" to "日榜").forEach { (k, label) ->
                        val active = range == k
                        // 背景/边框都做过渡，切档时不再"啪"地跳一下
                        val fill by androidx.compose.animation.animateColorAsState(
                            if (active) ink.primary.copy(alpha = 0.16f) else ink.text.copy(alpha = 0.05f),
                            androidx.compose.animation.core.tween(180),
                            label = "tabFill"
                        )
                        val edge by androidx.compose.animation.animateColorAsState(
                            if (active) ink.primary.copy(alpha = 0.45f) else Color.Transparent,
                            androidx.compose.animation.core.tween(180),
                            label = "tabEdge"
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(JunShape.chip)
                                .background(fill)
                                .border(1.dp, edge, JunShape.chip)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (range != k) {
                                        Haptics.tap()
                                        range = k
                                    }
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (active) ink.primary else ink.subText,
                                fontSize = 12.5.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (loading && items.isEmpty()) {
                        items(6) { RankSkeleton(colors, ink) }
                    } else if (items.isEmpty()) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "还没有人上榜，听几首歌就有啦",
                                    color = ink.subText, fontSize = 12.sp
                                )
                            }
                        }
                    } else {
                        // 前三名单独做领奖台：金银铜、中间最高，一眼看出差距
                        item(key = "podium") {
                            Podium(items.take(3), colors, ink, meKey)
                        }
                        items(items.drop(3), key = { it.key.ifBlank { "r${it.rank}" } }) { e ->
                            RankRow(entry = e, colors = colors, ink = ink, isMe = e.key == meKey)
                        }
                    }
                    item { Spacer(Modifier.navigationBarsPadding().height(12.dp)) }
                }
            }
        }
    }
}

/**
 * 关闭态：后台把排行榜关了之后整页显示的东西。
 *
 * 旧版这里什么都没做，于是"关了"之后页面还是原来那张榜，
 * 用户看到的是"后台关了但前台还有东西"，比空白更让人困惑。
 * 现在给一块干净的说明卡：暂停图标 + 一句话解释 + 返回按钮。
 */
@Composable
private fun RankClosed(ink: JunColors, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(ink.text.copy(alpha = 0.06f))
                .border(1.dp, ink.text.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(26.dp)) {
                val w = size.width * 0.24f
                val h = size.height * 0.82f
                val top = size.height * 0.09f
                val r = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f)
                drawRoundRect(
                    color = ink.subText,
                    topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.28f - w / 2f, top),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = r
                )
                drawRoundRect(
                    color = ink.subText,
                    topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.72f - w / 2f, top),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = r
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "听歌排行榜已关闭",
            color = ink.text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "管理员在后台暂停了这个功能，过段时间再来看看。\n你的听歌时长一直在记录，重新开启后会自动上榜。",
            color = ink.subText,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(26.dp))
        Box(
            Modifier
                .clip(JunShape.chip)
                .background(ink.primary.copy(alpha = 0.14f))
                .border(1.dp, ink.primary.copy(alpha = 0.30f), JunShape.chip)
                .junPressable { onDismiss() }
                .padding(horizontal = 26.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "返回",
                color = ink.primary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 前三名领奖台：2 - 1 - 3 排布，冠军最高。
 * 原版把前三名和 4-10 名一样铺成一列，看不出"名次高低"这件事；
 * 领奖台一眼就有比赛感。
 */
@Composable
private fun Podium(
    top: List<ServerApi.BoardEntry>,
    colors: JunColors,
    ink: JunColors,
    meKey: String
) {
    if (top.isEmpty()) return
    val one = top.firstOrNull { it.rank == 1 }
    val two = top.firstOrNull { it.rank == 2 }
    val three = top.firstOrNull { it.rank == 3 }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        PodiumCard(two, 2, 104.dp, colors, ink, meKey, Modifier.weight(1f))
        PodiumCard(one, 1, 132.dp, colors, ink, meKey, Modifier.weight(1f))
        PodiumCard(three, 3, 92.dp, colors, ink, meKey, Modifier.weight(1f))
    }
}

@Composable
private fun PodiumCard(
    entry: ServerApi.BoardEntry?,
    rank: Int,
    height: androidx.compose.ui.unit.Dp,
    colors: JunColors,
    ink: JunColors,
    meKey: String,
    modifier: Modifier = Modifier
) {
    val medal = when (rank) {
        1 -> Color(0xFFE8B84B)
        2 -> Color(0xFFC0C6CF)
        else -> Color(0xFFCE9268)
    }
    val isMe = entry != null && entry.key == meKey
    Box(
        modifier
            .height(height)
            .clip(JunShape.card)
            .background(
                Brush.verticalGradient(
                    listOf(
                        medal.copy(alpha = 0.22f),
                        medal.copy(alpha = 0.05f)
                    )
                )
            )
            .border(
                1.dp,
                if (isMe) ink.primary.copy(alpha = 0.55f) else medal.copy(alpha = 0.32f),
                JunShape.card
            )
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (entry == null) {
            // 人数不足 3 人：留个虚线位，不要整行塌掉
            Text("虚位以待", color = ink.subText.copy(alpha = 0.5f), fontSize = 10.sp)
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "${rank}",
                    color = medal,
                    fontSize = if (rank == 1) 22.sp else 17.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .size(if (rank == 1) 46.dp else 38.dp)
                        .clip(CircleShape)
                        .background(medal.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (entry.avatar.isNotBlank()) {
                        AvatarImage(
                            url = entry.avatar,
                            seedId = rank.toLong() * 11 + 7,
                            isDark = colors.isDark,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Text(
                            entry.name.take(1),
                            color = medal,
                            fontSize = if (rank == 1) 18.sp else 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    entry.name,
                    color = ink.text,
                    fontSize = if (rank == 1) 11.5.sp else 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    formatBoardSeconds(entry.seconds),
                    color = ink.subText,
                    fontSize = 9.5.sp,
                    maxLines = 1
                )
            }
        }
    }
}

/** 关窗叉号（自绘，和项目里其它图标一个路子，不引 material-icons） */
@Composable
private fun Canvas15(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(15.dp)) {
        val lw = size.minDimension * 0.13f
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.15f),
            androidx.compose.ui.geometry.Offset(size.width * 0.85f, size.height * 0.85f),
            strokeWidth = lw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.85f, size.height * 0.15f),
            androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.85f),
            strokeWidth = lw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** 段位配色：1-3 铜灰、4-6 银、7-8 金、9 紫、10+ 殿堂金红 */
internal fun tierColor(tier: Int): Color = when {
    tier >= 10 -> Color(0xFFE0453F)
    tier >= 9 -> Color(0xFF9B5BD6)
    tier >= 7 -> Color(0xFFE8B84B)
    tier >= 4 -> Color(0xFF9AA6B8)
    else -> Color(0xFFB07C5A)
}

/** 段位徽章：一个小圆 + 档位数 + 头衔首字，用作头像角标 */
@Composable
private fun TierBadge(tier: Int, badge: String, colors: JunColors, modifier: Modifier = Modifier) {
    val c = tierColor(tier)
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Brush.linearGradient(listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0.70f))))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$tier · $badge",
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** 我的档案卡：段位名 + 累计时长 + 升段进度条 + 彩蛋头衔 */
@Composable
private fun MyTierCard(
    tier: ServerApi.ListenTierInfo?,
    colors: JunColors,
    ink: JunColors,
    onClick: () -> Unit
) {
    val info = tier
    val c = tierColor(info?.tier ?: 1)
    // 进度：以「当前档到下一档」为区间。服务端只给了差多少，进度用累计时长反推
    val progress = run {
        if (info == null || info.nextTier <= 0) 1f
        else {
            // 本档门槛 = 本档起点；下一档门槛 = 当前累计 + 还差多少，两者之差就是完整区间
            val curMin = tierMinSeconds(info.tier)
            val nextMin = (info.total + info.nextNeed).coerceAtLeast(curMin + 1)
            val span = (nextMin - curMin).coerceAtLeast(1)
            ((info.total - curMin).toFloat() / span).coerceIn(0f, 1f)
        }
    }
    val anim by animateFloatAsState(progress, label = "tierProgress")

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(JunShape.card)
            .background(
                Brush.linearGradient(
                    listOf(c.copy(alpha = 0.20f), c.copy(alpha = 0.06f))
                )
            )
            .border(1.dp, c.copy(alpha = 0.35f), JunShape.card)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                Haptics.tap()
                onClick()
            }
            .padding(14.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(c.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${info?.tier ?: 1}",
                        color = c, fontSize = 17.sp, fontWeight = FontWeight.Black
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        info?.tierName ?: "初听者",
                        color = ink.text, fontSize = 15.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "累计 ${formatBoardSeconds(info?.total ?: 0L)} · 本周 ${formatBoardSeconds(info?.week ?: 0L)}",
                        color = ink.subText, fontSize = 11.sp
                    )
                }
                Text(
                    info?.let { if (it.loggedIn) "我的 ›" else "注册后永久保存 ›" } ?: "›",
                    color = c,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(10.dp))
            // 进度条
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(ink.text.copy(alpha = 0.10f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(anim)
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.55f), c)))
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    info == null -> "正在统计你的听歌时长…"
                    info.nextTier <= 0 -> "已是最高段位 · ${info.tierName}"
                    else -> "再听 ${formatBoardSeconds(info.nextNeed)} 升到「${info.nextName}」"
                },
                color = ink.subText,
                fontSize = 11.sp
            )
            val titles = info?.titles.orEmpty()
            if (titles.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    titles.take(3).forEach { (name, _) ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(c.copy(alpha = 0.18f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(name, color = c, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 圆形头像小图（6.3）：走 ArtworkLoader 的头像缓存层（96px LRU），
 * 不再和封面共用 256px 磁贴缓存 —— 排行榜一屏 10 个圆头像，
 * 用大图既浪费内存又和封面抢并发名额。
 */
@Composable
private fun AvatarImage(url: String, seedId: Long, isDark: Boolean, modifier: Modifier = Modifier) {
    var bmp by remember(url) { mutableStateOf(ArtworkLoader.peekAvatar(url)) }
    LaunchedEffect(url) {
        if (bmp != null) return@LaunchedEffect
        ArtworkLoader.loadAvatar(url)?.let { bmp = it }
    }
    val image = bmp
    if (image != null) {
        androidx.compose.foundation.Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = modifier
        )
    } else {
        // 加载中 / 失败：渐变小圆，跟榜单的段位色同一套，不闪白块
        Box(
            modifier.background(
                Brush.linearGradient(
                    listOf(
                        if (isDark) Color(0xFF3A3A44) else Color(0xFFE6E6EC),
                        if (isDark) Color(0xFF2A2A32) else Color(0xFFF2F2F6)
                    )
                )
            )
        )
    }
}

/** 一档的门槛秒数（与服务端 TIERS 对齐；只用于画进度） */
private fun tierMinSeconds(tier: Int): Long = when (tier) {
    1 -> 0L; 2 -> 600L; 3 -> 3600L; 4 -> 5 * 3600L; 5 -> 20 * 3600L
    6 -> 60 * 3600L; 7 -> 150 * 3600L; 8 -> 300 * 3600L; 9 -> 600 * 3600L
    10 -> 1000 * 3600L; else -> 2000 * 3600L
}

/** 榜单一行的骨架占位 */
@Composable
private fun RankSkeleton(colors: JunColors, ink: JunColors) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(JunShape.card)
            .background(ink.text.copy(alpha = 0.05f))
    )
}

/** 榜单一行：名次 + 头像 + 昵称/段位 + 时长 */
@Composable
private fun RankRow(
    entry: ServerApi.BoardEntry,
    colors: JunColors,
    ink: JunColors,
    isMe: Boolean
) {
    val rankColor = when (entry.rank) {
        1 -> Color(0xFFE8B84B)
        2 -> Color(0xFFC0C6CF)
        3 -> Color(0xFFCE9268)
        else -> ink.subText.copy(alpha = 0.75f)
    }
    val medal = entry.rank <= 3
    Box(
        Modifier
            .fillMaxWidth()
            .clip(JunShape.card)
            .background(
                if (isMe) ink.primary.copy(alpha = 0.10f)
                else ink.text.copy(alpha = 0.045f)
            )
            .then(
                if (isMe) Modifier.border(1.dp, ink.primary.copy(alpha = 0.35f), JunShape.card)
                else Modifier
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (medal) {
                    // 前三：一枚小奖牌
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(rankColor.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${entry.rank}", color = rankColor, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                } else {
                    Text(
                        "${entry.rank}",
                        color = rankColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (entry.avatar.isNotBlank()) {
                AvatarImage(
                    url = entry.avatar,
                    seedId = entry.rank.toLong() * 7 + 13,
                    isDark = colors.isDark,
                    modifier = Modifier.size(38.dp).clip(CircleShape)
                )
            } else {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    tierColor(entry.tier).copy(alpha = 0.32f),
                                    tierColor(entry.tier).copy(alpha = 0.10f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(entry.name.take(1), color = tierColor(entry.tier), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.name,
                        color = ink.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (entry.guest) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "游客",
                            color = ink.subText,
                            fontSize = 9.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(ink.text.copy(alpha = 0.08f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TierBadge(entry.tier, entry.badge, colors)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        entry.tierName,
                        color = ink.subText,
                        fontSize = 9.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatBoardSeconds(entry.seconds),
                    color = ink.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "累计 ${formatBoardSeconds(entry.total)}",
                    color = ink.subText,
                    fontSize = 9.sp
                )
            }
        }
    }
}

/** 秒数 → 展示文案（排行榜 / 档案共用）：1 小时以上按小时，1 天以上按天 */
internal fun formatBoardSeconds(sec: Long): String {
    val s = if (sec < 0) 0 else sec
    return when {
        s >= 86400 -> String.format("%.1f 天", s / 86400.0)
        s >= 3600 -> String.format("%.1f 小时", s / 3600.0)
        s >= 60 -> "${s / 60} 分钟"
        else -> if (s > 0) "$s 秒" else "0 分钟"
    }
}
