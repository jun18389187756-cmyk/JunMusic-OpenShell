package com.jun.musiccloud.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.readableOn

/**
 * 6.9.9 首页分区自定义。
 *
 * 用户要求：搜索栏右边加一颗「自定义」小按钮，点开可以把首页的区块
 * （我的音乐 / 推荐歌单 / 每日新歌 / 热门歌手…）调位置、删除。
 *
 * 持久化在 jun_prefs：顺序是一串 id，隐藏是另一串。读取时校验，
 * 缺失的 id 按默认顺序补在后面（新版本加了新分区自动跟上）。
 */
object HomeSectionConfig {

    const val KEY_ORDER = "home_sections_order"
    const val KEY_HIDDEN = "home_sections_hidden"

    data class Entry(val id: String, val label: String, val hint: String)

    /** 默认顺序 = 首页现在的自然顺序 */
    val ALL: List<Entry> = listOf(
        Entry("local", "我的音乐", "我喜欢的歌 · 本机歌单"),
        Entry("recs", "推荐歌单", "按口味猜的整张歌单"),
        Entry("newsongs", "每日新歌", "今天刚上的新歌"),
        Entry("artists", "热门歌手", "常听的歌手快捷入口"),
        Entry("fav", "根据你喜爱的歌曲推荐", "红心歌的相似推荐"),
        Entry("guess", "猜你喜欢", "单曲推荐流"),
        Entry("charts", "排行榜", "各平台热榜"),
        Entry("channels", "风格电台", "按气氛切歌单"),
        Entry("albums", "新碟上架", "最近发行的专辑"),
        Entry("recent", "最近播放", "接着上次往下听"),
        // 6.6：首页最底下那一长串（用户口中的「400 多首」「无穷无尽的音乐」）。
        // 它不在上面的分区流里，是 LazyColumn 里独立的 itemsIndexed(state.homeSongs)，
        // 所以这里登记成一项，让「首页自定义」能一键整体隐藏。
        Entry("homesongs", "底部全部歌曲", "首页最下面那一长串，可整体隐藏")
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jun_prefs", Context.MODE_PRIVATE)

    /** 读出当前顺序（缺失的补默认、多余的丢弃） */
    fun order(ctx: Context): List<String> {
        val saved = runCatching { prefs(ctx).getString(KEY_ORDER, "").orEmpty() }.getOrDefault("")
        val ids = saved.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val valid = ALL.map { it.id }
        val out = ids.filter { it in valid }.toMutableList()
        ALL.forEach { e -> if (e.id !in out) out.add(e.id) }
        return out
    }

    fun hidden(ctx: Context): Set<String> {
        val saved = runCatching { prefs(ctx).getString(KEY_HIDDEN, "").orEmpty() }.getOrDefault("")
        return saved.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun save(ctx: Context, order: List<String>, hidden: Set<String>) {
        runCatching {
            prefs(ctx).edit()
                .putString(KEY_ORDER, order.joinToString(","))
                .putString(KEY_HIDDEN, hidden.joinToString(","))
                .apply()
        }
    }
}

/**
 * 首页自定义弹层（6.9.10 重做）。
 *
 * 用户反馈三件事：
 *  ① "弹窗位置太靠上面了" —— 旧实现最外层 Box 只有 fillMaxWidth，高度等于内容，
 *     `align(BottomCenter)` 是在**内容高度**里底对齐，面板实际从屏幕顶部开始排；
 *  ② "点完成有的时候一直点不了" —— 同一个原因：面板超出 Box 边界被裁，
 *     「完成」按钮落在可点区域外面；
 *  ③ "尽量符合整个 APP 的 UI" —— 旧版是自绘实色面板，没有玻璃、没有拖拽。
 *
 * 现在直接复用 [JunSheet]：遮罩吃满全屏、面板从底部弹出、液态玻璃表面、
 * 把手可下滑关闭。「完成」钉在标题行右侧，任何滚动位置都点得到。
 */
@Composable
internal fun HomeCustomizeSheet(
    order: List<String>,
    hidden: Set<String>,
    colors: JunColors,
    onChange: (List<String>, Set<String>) -> Unit,
    onDismiss: () -> Unit,
    /** 底部迷你播放器高度；非 0 时整块玻璃抬上去，否则「恢复默认」会被压住点不到 */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp
) {
    var curOrder by remember { mutableStateOf(order) }
    var curHidden by remember { mutableStateOf(hidden) }
    val drag = rememberSheetDragState()

    fun commit() = onChange(curOrder, curHidden)

    fun move(id: String, delta: Int) {
        val idx = curOrder.indexOf(id)
        val to = idx + delta
        if (idx < 0 || to < 0 || to >= curOrder.size) return
        val list = curOrder.toMutableList()
        list.removeAt(idx)
        list.add(to, id)
        curOrder = list
        Haptics.tap()
        commit()
    }

    fun toggle(id: String) {
        curHidden = if (id in curHidden) curHidden - id else curHidden + id
        Haptics.tap()
        commit()
    }

    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        panelModifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp),
        // 不再自己加 navigationBarsPadding：bottomInset 已经把整块玻璃抬过导航栏，
        // 两个一起加会把面板顶得过高、上半屏空一大片（用户要"居中、看着舒服"）。
        bottomInset = bottomInset,
        contentModifier = Modifier
            .padding(horizontal = 18.dp)
    ) {
        SheetDragHandle(drag, onDismiss, colors.card)
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "自定义首页",
                    color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.Black
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "上下箭头调顺序，眼睛控制显示；改动即时生效",
                    color = colors.subText, fontSize = 10.5.sp
                )
            }
            // 「完成」固定在这里：面板本身可滚，但标题行不滚，按钮永远在可见区
            Box(
                Modifier
                    .junPressable { Haptics.click(); onDismiss() }
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.primary)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(
                    "完成",
                    // 主色底上的字：readableOn 会在对比度不足时给黑/白，
                    // 浅色主题（霓虹青底）和深色主题（深紫底）都能看清。
                    color = colors.primary.readableOn(colors.primary, 4.5f),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            curOrder.forEachIndexed { index, id ->
                val entry = HomeSectionConfig.ALL.firstOrNull { it.id == id } ?: return@forEachIndexed
                val visible = entry.id !in curHidden
                val first = index == 0
                val last = index == curOrder.lastIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(JunShape.chip)
                        .background(
                            colors.surface.copy(alpha = if (visible) 0.55f else 0.22f)
                        )
                        .border(
                            1.dp,
                            colors.primary.copy(alpha = if (visible) 0.10f else 0.04f),
                            JunShape.chip
                        )
                        .padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 序号：一眼看出这是第几块
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (visible) colors.primary.copy(alpha = 0.16f)
                                else colors.subText.copy(alpha = 0.10f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${index + 1}",
                            color = if (visible) colors.primary else colors.subText,
                            fontSize = 10.sp, fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.label,
                            color = if (visible) colors.text else colors.subText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!visible) {
                            Text(
                                "已隐藏",
                                color = colors.subText.copy(alpha = 0.8f),
                                fontSize = 9.sp
                            )
                        }
                    }
                    ArrowBtn("↑", colors, enabled = !first) { move(entry.id, -1) }
                    Spacer(Modifier.width(4.dp))
                    ArrowBtn("↓", colors, enabled = !last) { move(entry.id, 1) }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .junPressable { toggle(entry.id) }
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (visible) colors.primary.copy(alpha = 0.16f)
                                else colors.subText.copy(alpha = 0.12f)
                            )
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text(
                            if (visible) "隐藏" else "显示",
                            color = if (visible) colors.primary else colors.subText,
                            fontSize = 10.5.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .junPressable {
                        curOrder = HomeSectionConfig.ALL.map { it.id }
                        curHidden = emptySet()
                        Haptics.tap()
                        commit()
                    }
                    .clip(JunShape.chip)
                    .background(colors.surface.copy(alpha = 0.5f))
                    .border(1.dp, colors.primary.copy(alpha = 0.12f), JunShape.chip)
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "恢复默认",
                    color = colors.subText, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .junPressable {
                        // 全部隐藏没有意义：至少保留「我的音乐」
                        curHidden = HomeSectionConfig.ALL
                            .filter { it.id != "local" }
                            .map { it.id }
                            .toSet()
                        Haptics.tap()
                        commit()
                    }
                    .clip(JunShape.chip)
                    .background(colors.surface.copy(alpha = 0.5f))
                    .border(1.dp, colors.primary.copy(alpha = 0.12f), JunShape.chip)
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "只留我的音乐",
                    color = colors.subText, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ArrowBtn(text: String, colors: JunColors, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .junPressable(enabled = enabled, onClick = onClick)
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (enabled) colors.primary.copy(alpha = 0.14f)
                else colors.subText.copy(alpha = 0.06f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) colors.primary else colors.subText.copy(alpha = 0.35f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Black
        )
    }
}
