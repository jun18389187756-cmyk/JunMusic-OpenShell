package com.jun.musiccloud.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.DialogProperties
import com.jun.musiccloud.playback.BluetoothLyricBridge
import com.jun.musiccloud.player.PlayerUiState
import com.jun.musiccloud.player.PlayerViewModel
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunMode
import com.jun.musiccloud.ui.theme.JunSurfaceStyle
import com.jun.musiccloud.ui.theme.JunTheme
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.readableOn

/**
 * 6.7：自定义中心。
 *
 * 用户这轮的原话是「直接是中间这个弹窗，在中间出现弹窗不遮挡最底下那个小播放器」，
 * 所以这里**不用**从底部升起的 [JunSheet]，改成窗口级 [Dialog] + 居中卡片，
 * 外层 Box 底部固定留 96dp，迷你播放器那一条始终露在外面、还能顺手点。
 *
 * 设置里原有的「主题与自定义配色 / 界面材质与模糊 / 背景、封面与圆角」一项没删，
 * 这里是把它们**捞一份到台面上**：不看设置的人也能改。两边读写的是同一份
 * [PlayerUiState]，不会出现「这边改了那边没变」。
 *
 * 新增的、设置里本来没有的项（小播放器按钮/位置、侧滑页、上栏入口）落在
 * [CustomConfig]，用独立的 SharedPreferences，Compose State 即时驱动重组。
 */
object CustomConfig {

    private const val PREFS = "jun_custom_center"

    // ── 小播放器 ────────────────────────────────────────────────
    /** true = 经典四按钮（默认，用户说的「最之前那样」）；false = 6.6 的药丸岛 */
    val miniClassic = mutableStateOf(true)
    val miniShowPrev = mutableStateOf(true)
    val miniShowNext = mutableStateOf(true)
    val miniShowQueue = mutableStateOf(true)
    val miniShowCover = mutableStateOf(true)
    val miniShowArtist = mutableStateOf(true)
    /** 歌名+歌手是否居中（默认靠左，像大播放器信息区） */
    val miniTitleCenter = mutableStateOf(false)
    /** 长歌名/长歌手自动滚动（跑马灯特效），避免被省略号截断 */
    val miniTitleMarquee = mutableStateOf(true)
    /**
     * 歌名 / 歌手排布（6.6 新增）：
     * 0 = 两行，歌名在上、歌手在下（默认，和以前一样）；
     * 1 = 单行「歌手 - 歌名」，例如「赵雷 - 家乡」。
     */
    val miniTitleLayout = mutableStateOf(0)
    /** true = 贴边全宽；false = 左右留白悬浮 */
    val miniWide = mutableStateOf(true)
    /** 向上偏移量（dp）。0 = 原来的位置，正数上移 */
    val miniOffsetDp = mutableStateOf(0f)
    /** 去掉上一曲/下一曲后，左右滑动切歌自动兜底；这里也可独立开关 */
    val miniSwipeTrack = mutableStateOf(true)
    /** true = 常驻；false = 轻隐（一段时间没操作后淡成半透明，轻触即回） */
    val miniPersist = mutableStateOf(false)

    // ── 侧滑与入口 ──────────────────────────────────────────────
    /** 右滑露出「模式探索 / 气氛模式」那一页 */
    val swipeExplore = mutableStateOf(true)
    val showRankButton = mutableStateOf(true)
    val showPlaylistButton = mutableStateOf(true)
    val showLayoutButton = mutableStateOf(true)
    val showAvatarButton = mutableStateOf(true)

    // ── 通知栏歌词（与 BluetoothLyricBridge 共用真实开关） ────────
    val notificationLyric = mutableStateOf(true)

    /** 设置页也能一键拉起本弹窗；Dialog 是独立窗口，会盖在设置面板之上 */
    val showDialog = mutableStateOf(false)

    @Volatile private var inited = false
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            miniClassic.value = p.getBoolean("mini_classic", true)
            miniShowPrev.value = p.getBoolean("mini_prev", true)
            miniShowNext.value = p.getBoolean("mini_next", true)
            miniShowQueue.value = p.getBoolean("mini_queue", true)
            miniShowCover.value = p.getBoolean("mini_cover", true)
            miniShowArtist.value = p.getBoolean("mini_artist", true)
            miniTitleCenter.value = p.getBoolean("mini_title_center", false)
            miniTitleMarquee.value = p.getBoolean("mini_title_marquee", true)
            miniTitleLayout.value = p.getInt("mini_title_layout", 0)
            miniWide.value = p.getBoolean("mini_wide", true)
            miniOffsetDp.value = p.getFloat("mini_offset", 0f)
            miniSwipeTrack.value = p.getBoolean("mini_swipe", true)
            miniPersist.value = p.getBoolean("mini_persist", false)
            swipeExplore.value = p.getBoolean("swipe_explore", true)
            showRankButton.value = p.getBoolean("btn_rank", true)
            showPlaylistButton.value = p.getBoolean("btn_playlist", true)
            showLayoutButton.value = p.getBoolean("btn_layout", true)
            showAvatarButton.value = p.getBoolean("btn_avatar", true)
            notificationLyric.value = BluetoothLyricBridge.isNotificationLyricEnabled(ctx)
            inited = true
        }
    }

    private fun put(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    fun setMiniClassic(v: Boolean) { miniClassic.value = v; put("mini_classic", v) }
    fun setMiniShowPrev(v: Boolean) { miniShowPrev.value = v; put("mini_prev", v) }
    fun setMiniShowNext(v: Boolean) { miniShowNext.value = v; put("mini_next", v) }
    fun setMiniShowQueue(v: Boolean) { miniShowQueue.value = v; put("mini_queue", v) }
    fun setMiniShowCover(v: Boolean) { miniShowCover.value = v; put("mini_cover", v) }
    fun setMiniShowArtist(v: Boolean) { miniShowArtist.value = v; put("mini_artist", v) }
    fun setMiniTitleCenter(v: Boolean) { miniTitleCenter.value = v; put("mini_title_center", v) }
    fun setMiniTitleMarquee(v: Boolean) { miniTitleMarquee.value = v; put("mini_title_marquee", v) }
    fun setMiniTitleLayout(v: Int) {
        miniTitleLayout.value = v
        prefs?.edit()?.putInt("mini_title_layout", v)?.apply()
    }
    fun setMiniWide(v: Boolean) { miniWide.value = v; put("mini_wide", v) }
    fun setMiniOffset(v: Float) {
        miniOffsetDp.value = v
        prefs?.edit()?.putFloat("mini_offset", v)?.apply()
    }
    fun setMiniSwipeTrack(v: Boolean) { miniSwipeTrack.value = v; put("mini_swipe", v) }
    fun setSwipeExplore(v: Boolean) { swipeExplore.value = v; put("swipe_explore", v) }
    fun setShowRankButton(v: Boolean) { showRankButton.value = v; put("btn_rank", v) }
    fun setShowPlaylistButton(v: Boolean) { showPlaylistButton.value = v; put("btn_playlist", v) }
    fun setShowLayoutButton(v: Boolean) { showLayoutButton.value = v; put("btn_layout", v) }
    fun setShowAvatarButton(v: Boolean) { showAvatarButton.value = v; put("btn_avatar", v) }

    fun setNotificationLyric(ctx: Context, v: Boolean) {
        BluetoothLyricBridge.setNotificationLyricEnabled(ctx, v)
        notificationLyric.value = v
    }

    /** 常驻行为真源在 ViewModel（设置页那颗开关写的是同一处），这里只做镜像 */
    fun syncPersistFromVm(persist: Boolean) {
        miniPersist.value = persist
        put("mini_persist", persist)
    }

    fun resetAll(ctx: Context) {
        prefs?.edit()?.clear()?.apply()
        miniClassic.value = true
        miniShowPrev.value = true
        miniShowNext.value = true
        miniShowQueue.value = true
        miniShowCover.value = true
        miniShowArtist.value = true
        miniTitleCenter.value = false
        miniTitleMarquee.value = true
        miniTitleLayout.value = 0
        miniWide.value = true
        miniOffsetDp.value = 0f
        miniSwipeTrack.value = true
        miniPersist.value = false
        swipeExplore.value = true
        showRankButton.value = true
        showPlaylistButton.value = true
        showLayoutButton.value = true
        showAvatarButton.value = true
        BluetoothLyricBridge.setNotificationLyricEnabled(ctx, true)
        notificationLyric.value = true
    }
}

// ────────────────────────────────────────────────────────────────
// 弹窗
// ────────────────────────────────────────────────────────────────

private val TABS = listOf("首页", "背景", "外观", "液态玻璃材质", "小播放器", "入口")

@Composable
internal fun CustomCenterDialog(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    colors: JunColors,
    order: List<String>,
    hidden: Set<String>,
    onHomeChange: (List<String>, Set<String>) -> Unit,
    onPickBackground: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        CustomConfig.init(ctx)
        JunUiConfig.init(ctx)
        GlassTuning.init(ctx)
    }

    fun close() {
        CustomConfig.showDialog.value = false
        onDismiss()
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // 6.6：彻底不再自己铺全屏黑底（原来 0.55 那层就是「一遮全是黑色」的元凶）。
        // Dialog 窗口自带系统级 dim，跟设置面板等其它弹窗完全一致 —— 不再双重压暗，
        // 背后的封面 / 极光 / 玻璃都透得出来。点空白处直接返回。
        Box(
            Modifier
                .fillMaxSize()
                .junPressable { close() }
                .padding(bottom = 96.dp),
            contentAlignment = Alignment.Center
        ) {
            val compact = LocalCompact.current
            val cardShape = remember { RoundedCornerShape(28.dp) }
            // 6.6：卡片走项目统一的 junSurface。默认材质 LIQUID 时它就是液态玻璃
            // （与整个 App UI 同一套折射/内缘高光），在设置或本页切成毛玻璃 / 实色时
            // 同一帧跟着变，不再写死一张实色黑板。
            Column(
                Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.74f)
                    .heightIn(max = 580.dp)
                    .junSurface(GlassLevel.SHEET, colors, cardShape, elevation = 28.dp)
                    // 吞掉卡片内部的点击，避免穿透到遮罩把弹窗关掉（与设置面板同一写法）
                    .junPressable(enabled = false) {}
                    .padding((16 * compact).dp)
            ) {
                // 标题：左上角常驻返回（学设置面板），系统返回键 / 点空白处同样能退，
                // 不再出现「根本返回不了」。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallChip("‹ 返回", colors) { close() }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "自定义", color = colors.text,
                            fontSize = 20.sp, fontWeight = FontWeight.Black
                        )
                        Text(
                            "想留什么、想去掉什么，都在这里",
                            color = colors.subText, fontSize = 11.sp
                        )
                    }
                    SmallChip("完成", colors, filled = true) { close() }
                }
                Spacer(Modifier.height((12 * compact).dp))

                val tab = androidx.compose.runtime.remember { mutableStateOf(0) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    TABS.forEachIndexed { i, name ->
                        SmallChip(name, colors, filled = tab.value == i) { tab.value = i }
                    }
                }
                Spacer(Modifier.height((10 * compact).dp))

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    when (tab.value) {
                        0 -> HomeTab(order, hidden, onHomeChange, colors)
                        1 -> BackgroundTab(state, viewModel, colors, onPickBackground)
                        2 -> AppearanceTab(state, viewModel, colors)
                        3 -> GlassTuningTab(state, viewModel, colors)
                        4 -> MiniPlayerTab(state, viewModel, colors)
                        else -> EntryTab(ctx, viewModel, colors)
                    }
                }

                Spacer(Modifier.height((10 * compact).dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SmallChip("恢复全部默认", colors) {
                        CustomConfig.resetAll(ctx)
                        viewModel.setMiniPlayerPersist(false)
                        onHomeChange(
                            HomeSectionConfig.ALL.map { it.id },
                            emptySet()
                        )
                        HomeSectionConfig.save(ctx, HomeSectionConfig.ALL.map { it.id }, emptySet())
                    }
                    SmallChip("完成", colors, filled = true, modifier = Modifier.weight(1f)) { close() }
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────
// 分类 1：首页
// ────────────────────────────────────────────────────────────────

@Composable
private fun HomeTab(
    order: List<String>,
    hidden: Set<String>,
    onHomeChange: (List<String>, Set<String>) -> Unit,
    colors: JunColors
) {
    TabHint("关掉哪一块，首页就完全不画它（连列表项都不放），不是藏起来。", colors)
    val ctx = LocalContext.current
    val sorted = HomeSectionConfig.ALL.sortedBy { e ->
        order.indexOf(e.id).let { if (it < 0) 999 else it }
    }
    sorted.forEach { e ->
        val visible = e.id !in hidden
        val idx = order.indexOf(e.id)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CustomToggle(e.label, visible, colors, hint = e.hint) {
                    val h = hidden.toMutableSet()
                    if (visible) h.add(e.id) else h.remove(e.id)
                    onHomeChange(order, h)
                }
            }
            // 上移 / 下移：改的是顺序串，MainScreen 立刻按新顺序渲染
            SmallChip("↑", colors) {
                if (idx > 0) {
                    val n = order.toMutableList()
                    java.util.Collections.swap(n, idx, idx - 1)
                    onHomeChange(n, hidden)
                    HomeSectionConfig.save(ctx, n, hidden)
                }
            }
            Spacer(Modifier.width(4.dp))
            SmallChip("↓", colors) {
                if (idx >= 0 && idx < order.size - 1) {
                    val n = order.toMutableList()
                    java.util.Collections.swap(n, idx, idx + 1)
                    onHomeChange(n, hidden)
                    HomeSectionConfig.save(ctx, n, hidden)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

// ────────────────────────────────────────────────────────────────
// 分类 2：背景（与设置页同源）
// ────────────────────────────────────────────────────────────────

@Composable
private fun BackgroundTab(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    colors: JunColors,
    onPickBackground: () -> Unit
) {
    TabHint("和「设置 → 外观与界面」是同一份配置，两边改哪边都生效。", colors)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallChip("选一张背景图", colors, filled = true, modifier = Modifier.weight(1f)) {
            onPickBackground()
        }
        SmallChip("清除", colors) { viewModel.setCustomBackground(null) }
    }
    Text(
        if (state.customBackgroundUri != null) "当前：自选图片" else "当前：跟随歌曲封面",
        color = colors.subText, fontSize = 11.sp
    )
    Spacer(Modifier.height(12.dp))

    SectionCard(colors, "背景调节") {
        LabelSlider("背景深度（模糊）", "${state.bgBlur.toInt()} dp", state.bgBlur, 0f..60f, colors) {
            viewModel.setBgBlur(it)
        }
        Text("越大越沉：小了能看清封面构图，大了只剩一层氛围色。", color = colors.subText, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        LabelSlider("玻璃浓度", "%.1f×".format(state.glassBlur), state.glassBlur, 0.4f..2f, colors) {
            viewModel.setGlassBlur(it)
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "界面材质（实色 / 毛玻璃 / 液态玻璃）挪到「外观」页，跟设置里是同一项；" +
            "默认液态玻璃，改完整个 App 和这个弹窗一起变。",
        color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp
    )
}

// ────────────────────────────────────────────────────────────────
// 分类 3：外观 / 配色
// ────────────────────────────────────────────────────────────────

@Composable
private fun AppearanceTab(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    colors: JunColors
) {
    // 6.6：整页照「设置 → 外观与界面」重做。原来那块「自定义强调色（点一下即套用）」
    // 点了没用、也没人看得懂，按用户要求直接删掉；自定义配色回到设置里那套取色器，
    // 两边读写同一份 state，不搞第二套。
    TabHint("和「设置 → 外观与界面」是同一份配置，两边改哪边都生效。默认材质就是液态玻璃。", colors)

    SectionCard(colors, "明暗模式") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in JunMode.values()) {
                SmallChip(m.label, colors, filled = state.mode == m, modifier = Modifier.weight(1f)) {
                    viewModel.setMode(m)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    SectionCard(colors, "主题配色") {
        // 学设置界面的 ThemeSwatch：每一枚都带自己的主题主色，未选中也带主色淡底，
        // 不再出现「有的按钮有颜色、有的按钮是灰的」。两列排，8 套主题一眼看完。
        for (row in JunTheme.values().toList().chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in row) {
                    ThemeColorChip(t, state.theme == t, colors, Modifier.weight(1f)) {
                        viewModel.setTheme(t)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
        }
        if (state.theme == JunTheme.CUSTOM) {
            Text(
                "自定义主色 / 次色在「设置 → 外观与界面 → 主题与自定义配色」里调，拖动滑块即时生效。",
                color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp
            )
            Spacer(Modifier.height(8.dp))
        }
    }
    Spacer(Modifier.height(12.dp))

    SectionCard(colors, "界面材质") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in JunSurfaceStyle.values()) {
                SmallChip(s.label, colors, filled = state.surfaceStyle == s, modifier = Modifier.weight(1f)) {
                    viewModel.setSurfaceStyle(s)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "默认「液态玻璃」＝整套 App UI 的那套折射质感；切成毛玻璃就只糊浮层背后，切实色就完全不透明。" +
                "这个弹窗自己也是同一套材质，跟着一起变，不再是一块写死的黑板。",
            color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp
        )
    }
}

/** 主题色板：每枚都显示自己的主色圆点（学设置界面 ThemeSwatch），选中态主色高亮 */
@Composable
private fun ThemeColorChip(
    theme: JunTheme,
    selected: Boolean,
    colors: JunColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = JunShape.chip
    val dot = theme.primary.readableOn(colors.card, 3f)
    Row(
        modifier
            .height(38.dp)
            .junPressable(scaleDown = 0.94f, onClick = onClick)
            .junSurface(
                level = GlassLevel.CARD,
                colors = colors,
                shape = shape,
                solid = if (selected) dot.copy(alpha = 0.28f) else dot.copy(alpha = 0.10f),
                accent = if (selected) dot else dot.copy(alpha = 0.35f)
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) dot else dot.copy(alpha = 0.35f),
                shape = shape
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(dot)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            theme.label, color = colors.text, fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.Black else FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

// ────────────────────────────────────────────────────────────────
// 分类 4：小播放器（含实时预览）
// ────────────────────────────────────────────────────────────────

@Composable
private fun MiniPlayerTab(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    colors: JunColors
) {
    TabHint("默认就是最早那套：上一曲 / 播放暂停 / 下一曲 / 播放列表。", colors)
    MiniPlayerPreview(colors)
    Spacer(Modifier.height(12.dp))

    SectionCard(colors, "样式") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallChip("经典（默认）", colors, filled = CustomConfig.miniClassic.value,
                modifier = Modifier.weight(1f)) { CustomConfig.setMiniClassic(true) }
            SmallChip("药丸岛", colors, filled = !CustomConfig.miniClassic.value,
                modifier = Modifier.weight(1f)) { CustomConfig.setMiniClassic(false) }
        }
    }
    Spacer(Modifier.height(12.dp))

    // 6.6 新增：歌名 / 歌手排布。第一种是默认两行，第二种是「歌手 - 歌名」单行。
    SectionCard(colors, "歌名 / 歌手排布") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallChip("歌名在上 · 歌手在下", colors,
                filled = CustomConfig.miniTitleLayout.value == 0,
                modifier = Modifier.weight(1f)) { CustomConfig.setMiniTitleLayout(0) }
            SmallChip("歌手 - 歌名", colors,
                filled = CustomConfig.miniTitleLayout.value == 1,
                modifier = Modifier.weight(1f)) { CustomConfig.setMiniTitleLayout(1) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "单行就是「赵雷 - 家乡」这种排法，歌手在前、歌名在后，中间用横杠隔开；" +
                "上面那块预览会立刻跟着变。关掉「歌手名」时两种排布都只显示歌名。",
            color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp
        )
    }
    Spacer(Modifier.height(12.dp))

    SectionCard(colors, "显示哪些") {
        CustomToggle("上一曲按钮", CustomConfig.miniShowPrev.value, colors) { CustomConfig.setMiniShowPrev(it) }
        CustomToggle("下一曲按钮", CustomConfig.miniShowNext.value, colors) { CustomConfig.setMiniShowNext(it) }
        CustomToggle("播放列表按钮", CustomConfig.miniShowQueue.value, colors) { CustomConfig.setMiniShowQueue(it) }
        CustomToggle("封面", CustomConfig.miniShowCover.value, colors) { CustomConfig.setMiniShowCover(it) }
        CustomToggle("歌手名", CustomConfig.miniShowArtist.value, colors) { CustomConfig.setMiniShowArtist(it) }
        CustomToggle("歌名居中", CustomConfig.miniTitleCenter.value, colors,
            hint = "关掉靠左（像大播放器信息区）；打开则歌名/歌手在播放条里居中") { CustomConfig.setMiniTitleCenter(it) }
        CustomToggle("长歌名滚动", CustomConfig.miniTitleMarquee.value, colors,
            hint = "歌名/歌手超出宽度时自动滚动，不再被省略号截断") { CustomConfig.setMiniTitleMarquee(it) }
        CustomToggle("贴边全宽", CustomConfig.miniWide.value, colors,
            hint = "关掉就左右留白，变成悬浮小岛") { CustomConfig.setMiniWide(it) }
        CustomToggle("左右滑动切歌", CustomConfig.miniSwipeTrack.value, colors,
            hint = "去掉上一曲/下一曲后会自动开启，用滑的换歌") { CustomConfig.setMiniSwipeTrack(it) }
        Spacer(Modifier.height(10.dp))

        LabelSlider(
            "上下位置",
            if (CustomConfig.miniOffsetDp.value == 0f) "默认" else "上移 ${CustomConfig.miniOffsetDp.value.toInt()} dp",
            CustomConfig.miniOffsetDp.value, -8f..48f, colors
        ) { CustomConfig.setMiniOffset(it) }
        Text("拖动时上面那块预览会跟着上下走，不用来回退出看效果。", color = colors.subText, fontSize = 11.sp)
    }
    Spacer(Modifier.height(12.dp))

    SectionCard(colors, "常驻方式") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallChip("常驻", colors, filled = state.miniPlayerPersist, modifier = Modifier.weight(1f)) {
                viewModel.setMiniPlayerPersist(true)
                CustomConfig.syncPersistFromVm(true)
            }
            SmallChip("轻隐", colors, filled = !state.miniPlayerPersist, modifier = Modifier.weight(1f)) {
                viewModel.setMiniPlayerPersist(false)
                CustomConfig.syncPersistFromVm(false)
            }
        }
        Text(
            "「常驻」= 一直亮着；「轻隐」= 一段时间没碰它就淡成半透明，轻触立刻回来。\n" +
                "给「变淡化」起了个更像人话的名字，两个词一眼能分出区别。",
            color = colors.subText, fontSize = 11.sp
        )
    }
}

/** 屏幕底部示意框：预览卡会随位置滑杆上下移动，按钮开关即时反映 */
@Composable
private fun MiniPlayerPreview(colors: JunColors) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .junSurface(GlassLevel.CARD, colors, JunShape.item)
    ) {
        // 底部导航条示意
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(10.dp)
                .background(colors.subText.copy(alpha = 0.10f))
        )
        val hPad = if (CustomConfig.miniWide.value) 10.dp else 28.dp
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = hPad)
        .padding(bottom = (14f + CustomConfig.miniOffsetDp.value.coerceIn(-8f, 48f)).dp)
        .fillMaxWidth()
        .junSurface(GlassLevel.CARD, colors, shape)
        .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (CustomConfig.miniShowCover.value) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.primary.copy(alpha = 0.65f))
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = if (CustomConfig.miniTitleCenter.value) Alignment.CenterHorizontally else Alignment.Start
            ) {
                val marquee = CustomConfig.miniTitleMarquee.value
                val titleMod = if (marquee) Modifier.basicMarquee() else Modifier
                if (CustomConfig.miniTitleLayout.value == 1) {
                    // 单行：歌手 - 歌名（如「赵雷 - 家乡」）
                    val single = if (CustomConfig.miniShowArtist.value) "赵雷 - 家乡" else "家乡"
                    Text(
                        single,
                        color = colors.text, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = titleMod
                    )
                } else {
                    // 两行（默认）：歌名在上、歌手在下
                    Text(
                        "示例歌曲示例歌曲很长很长很长很长很长",
                        color = colors.text, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = titleMod
                    )
                    if (CustomConfig.miniShowArtist.value) {
                        Text(
                            "示例歌手示例歌手很长很长",
                            color = colors.subText, fontSize = 9.sp, maxLines = 1,
                            modifier = if (marquee) Modifier.basicMarquee() else Modifier
                        )
                    }
                }
            }
            val classic = CustomConfig.miniClassic.value
            if (classic && CustomConfig.miniShowPrev.value) PreviewIcon(JunIcons.SkipPrevious, colors)
            PreviewIcon(JunIcons.Play, colors, size = 26.dp)
            if (classic && CustomConfig.miniShowNext.value) PreviewIcon(JunIcons.SkipNext, colors)
            if (classic && CustomConfig.miniShowQueue.value) PreviewIcon(JunIcons.QueueMusic, colors)
        }
        Text(
            "预览 · 位置 ${CustomConfig.miniOffsetDp.value.toInt()} dp",
            color = colors.subText, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        )
    }
}

@Composable
private fun PreviewIcon(vec: androidx.compose.ui.graphics.vector.ImageVector, colors: JunColors, size: androidx.compose.ui.unit.Dp = 22.dp) {
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        Icon(vec, contentDescription = null, tint = colors.text, modifier = Modifier.size(size))
    }
}

// ────────────────────────────────────────────────────────────────
// 分类 5：侧滑与入口
// ────────────────────────────────────────────────────────────────

@Composable
private fun EntryTab(ctx: Context, viewModel: PlayerViewModel, colors: JunColors) {
    TabHint("不用的入口直接摘掉，界面会自己收窄；想找回来再打开就行。", colors)
    SectionCard(colors) {
        CustomToggle("右滑进入模式探索 / 气氛模式", CustomConfig.swipeExplore.value, colors,
            hint = "关掉后，在首页往右划就什么也不会划出来") { CustomConfig.setSwipeExplore(it) }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard(colors, "上栏按钮") {
        CustomToggle("排行榜", CustomConfig.showRankButton.value, colors) { CustomConfig.setShowRankButton(it) }
        CustomToggle("歌单", CustomConfig.showPlaylistButton.value, colors) { CustomConfig.setShowPlaylistButton(it) }
        CustomToggle("排列方式", CustomConfig.showLayoutButton.value, colors) { CustomConfig.setShowLayoutButton(it) }
        CustomToggle("我的", CustomConfig.showAvatarButton.value, colors) { CustomConfig.setShowAvatarButton(it) }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard(colors, "播放") {
        CustomToggle("通知栏显示歌词", CustomConfig.notificationLyric.value, colors,
            hint = "当前这一句写进通知卡片；车机 / 蓝牙同样能读") {
            CustomConfig.setNotificationLyric(ctx, it)
        }
        Text(
            "系统原生通知模板由手机 ROM 决定，个别机型只显示歌名/歌手、不显示副标题；" +
                "这时车机与蓝牙仍可正常读到歌词。",
            color = colors.subText, fontSize = 11.sp
        )
    }
}

// ────────────────────────────────────────────────────────────────
// 通用小组件
// ────────────────────────────────────────────────────────────────

@Composable
private fun TabHint(text: String, colors: JunColors, compact: Float = LocalCompact.current) {
    Text(text, color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp)
    Spacer(Modifier.height((10 * compact).dp))
}

/**
 * 分组卡片：把同一类控件收进一块轻量玻璃卡里，让弹窗从「一串零散行」变成
 * 「几块清晰分区」。表面同样走 [junSurface]（CARD 层级），所以和整个 App、
 * 和这个弹窗的外壳是同一套液态玻璃，不会出现第二种材质。
 */
@Composable
private fun SectionCard(
    colors: JunColors,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = JunShape.item
    Column(
        Modifier
            .fillMaxWidth()
            .junSurface(GlassLevel.CARD, colors, shape)
            .padding(14.dp)
    ) {
        if (title != null) {
            Text(title, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

@Composable
private fun SmallChip(
    label: String,
    colors: JunColors,
    filled: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // 6.6：照设置界面的 ModeSwatch 做 —— 每一枚胶囊都带主题主色（未选中是淡底 +
    // 主色描边，选中是主色玻璃 + 实描边），不再有「有的按钮有颜色、有的没有」。
    // 表面同样走 junSurface，所以选中态在液态玻璃 / 毛玻璃 / 实色下都跟着变。
    val shape = JunShape.chip
    val accent = colors.primary.readableOn(colors.card, 3.2f)
    Box(
        modifier
            .height(36.dp)
            .junPressable(scaleDown = 0.94f, onClick = onClick) // 跟手 Q 弹（横纵分弹簧回弹）
            .junSurface(
                level = GlassLevel.CARD,
                colors = colors,
                shape = shape,
                solid = if (filled) accent.copy(alpha = 0.30f) else accent.copy(alpha = 0.10f),
                accent = if (filled) colors.primary else accent.copy(alpha = 0.35f)
            )
            .border(
                1.dp,
                if (filled) accent else accent.copy(alpha = 0.40f),
                shape
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (filled) accent else colors.text,
            fontSize = 11.5.sp,
            fontWeight = if (filled) FontWeight.Black else FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CustomToggle(
    label: String,
    checked: Boolean,
    colors: JunColors,
    hint: String = "",
    compact: Float = LocalCompact.current,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = (3 * compact).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            if (hint.isNotBlank()) {
                Text(hint, color = colors.subText, fontSize = 10.5.sp, lineHeight = 13.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        // 自绘开关：不依赖 material3 Switch（项目里没引），视觉与玻璃面板更贴
        Box(
            Modifier
                .size(width = 40.dp, height = 24.dp)
                .clip(RoundedCornerShape(12.dp))
                // 6.6：关着的开关也带一层主题主色，不再是灰扑扑的一块
                .background(if (checked) colors.primary else colors.primary.copy(alpha = 0.22f))
                .junPressable(scaleDown = 0.92f) { onChange(!checked) }
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    // 6.8：按开关底色算对比，旋钮永远清晰可见
                    .background(if (checked) colors.primary.readableOn(colors.primary, 4.5f) else Color.White)
            )
        }
    }
}

@Composable
private fun LabelSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    colors: JunColors,
    onChange: (Float) -> Unit
) {
    Text("$label · $valueText", color = colors.text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
    CompactSlider(
        value = value.coerceIn(range),
        onValueChange = onChange,
        valueRange = range,
        activeColor = colors.primary,
        inactiveColor = colors.surface
    )
}

