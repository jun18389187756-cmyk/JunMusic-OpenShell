package com.jun.musiccloud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.data.Playlist
import com.jun.musiccloud.data.Playlists
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.readableOn
import com.jun.musiccloud.ui.theme.readableTextOn

/**
 * 播放库整理（6.4）。
 *
 * 这一页回答的是用户那句「我这个播放列表平时太乱了，我之前乱加，不知道为何如此之乱」。
 *
 * 不讲道理，直接给结论：**哪里乱、乱成什么样、一键怎么收**。
 * 判断依据只有两条（见 [Playlists.audit]）：
 *   · 碎片歌单：只有 1~2 首，建的时候没想清楚，之后一直是乱的；
 *   · 重复歌单：两个歌单 80% 以上是同一批歌，用户自己也分不清该点哪个。
 *
 * 页面结构（从上到下，就是用户要做的三件事）：
 *   ① 四层结构是什么（一句话一条，让用户知道"该往哪层放歌"）
 *   ② 我现在的问题（体检报告：几个碎片、几组重复）
 *   ③ 一键整理（选一个"根据地"歌单，碎片里的歌并进去）
 */
@Composable
internal fun LibraryTidySheet(
    playlists: List<Playlist>,
    health: Playlists.LibraryHealth?,
    auditing: Boolean,
    colors: JunColors,
    onDismiss: () -> Unit,
    onAudit: () -> Unit,
    onTidy: (into: Long) -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val drag = rememberSheetDragState()

    // 用户选中的"根据地"歌单：碎片里的歌都并到这里来
    var target by remember { mutableStateOf<Long>(-1L) }

    LaunchedEffect(Unit) { onAudit() }

    JunSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        panelModifier = Modifier.fillMaxHeight(0.88f),
        contentModifier = Modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 10.dp, bottom = 24.dp)
    ) {
        SheetDragHandle(drag, onDismiss, colors.card)
        Spacer(Modifier.height(14.dp))

        Text("整理播放库", color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(4.dp))
        Text(
            "歌单乱了不用一条条理，看完这一页，点一次就好。",
            color = colors.subText,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(18.dp))

        // ① 四层结构
        TidyLabel("一个好用的播放库，只有四层", colors)
        Spacer(Modifier.height(8.dp))
        TidyLayerRow("1", "正在播放", "你点歌时临时排的那一串。会随时被下一张歌单整份替换，别往里手动加歌。", colors, accent)
        TidyLayerRow("2", "最近播放", "自动记录，只读。当回收站用，一周清一次。", colors, accent)
        TidyLayerRow("3", "我喜欢", "听到好听的先丢这儿。先收下，不急着分类。", colors, accent)
        TidyLayerRow("4", "我的歌单", "有主题才建：能一句话说清「为什么这些歌在一起」，说不清就别建。", colors, accent)

        Spacer(Modifier.height(20.dp))

        // ② 体检报告
        TidyLabel("你现在的歌单", colors)
        Spacer(Modifier.height(8.dp))
        if (health == null || auditing) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(JunShape.item)
                    .background(colors.card)
                    .padding(vertical = 26.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("正在数一遍…", color = colors.subText, fontSize = 12.sp)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TidyStat("${health.playlists.size}", "个歌单", colors, Modifier.weight(1f))
                TidyStat("${health.fragments.size}", "个碎片", colors, Modifier.weight(1f))
                TidyStat("${health.duplicates.size}", "组重复", colors, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            if (health.fragments.isEmpty() && health.duplicates.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(JunShape.item)
                        .background(colors.card)
                        .padding(14.dp)
                ) {
                    Text(
                        "没有碎片、也没有重复的歌单，这一块你已经很干净了。",
                        color = colors.subText,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            } else {
                // 碎片清单
                if (health.fragments.isNotEmpty()) {
                    TidyCard(colors) {
                        Text(
                            "碎片歌单 ${health.fragments.size} 个",
                            color = colors.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            health.fragments.joinToString("、") { "${it.name}（${it.songCount} 首）" },
                            color = colors.subText,
                            fontSize = 11.5.sp,
                            lineHeight = 17.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "只有一两首歌放一个歌单，等于没整理——歌找不到，歌单还占地方。",
                            color = colors.subText.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // 重复清单
                if (health.duplicates.isNotEmpty()) {
                    TidyCard(colors) {
                        Text(
                            "重复歌单 ${health.duplicates.size} 组",
                            color = colors.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        health.duplicates.take(4).forEach { (keep, drop) ->
                            Text(
                                "「${keep.name}」 与 「${drop.name}」 内容基本一样",
                                color = colors.subText,
                                fontSize = 11.5.sp,
                                lineHeight = 17.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "整理时会保留歌多的那个，歌少的并进去后删掉。",
                            color = colors.subText.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // ③ 选根据地 + 一键整理
                if (health.fragments.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    TidyLabel("碎片歌单里的歌，并到哪儿？", colors)
                    Spacer(Modifier.height(8.dp))
                    val candidates = playlists.filter { p -> health.fragments.none { it.id == p.id } }
                    if (candidates.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(JunShape.item)
                                .background(colors.card)
                                .padding(14.dp)
                        ) {
                            Text(
                                "你暂时没有「正经」的主题歌单可并。整理会直接删掉碎片歌单，" +
                                    "里面的歌仍在「全部音乐」里，不会丢。",
                                color = colors.subText,
                                fontSize = 11.5.sp,
                                lineHeight = 17.sp
                            )
                        }
                    } else {
                        candidates.take(8).forEach { p ->
                            val selected = target == p.id
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(JunShape.item)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { Haptics.tap(); target = p.id }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(if (selected) accent else colors.subText.copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Box(
                                            Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    p.name,
                                    color = colors.text,
                                    fontSize = 13.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${p.songCount} 首",
                                    color = colors.subText,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                val canTidy = health.fragments.isNotEmpty() || health.duplicates.isNotEmpty()
                val noTarget = candidates(health, playlists)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(JunShape.item)
                        .background(if (canTidy) accent else colors.subText.copy(alpha = 0.18f))
                        .clickable(enabled = canTidy, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            Haptics.tap()
                            onTidy(target)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when {
                            !canTidy -> "已经很干净了"
                            health.fragments.isEmpty() -> "合并重复歌单"
                            noTarget || target < 0 -> "删除碎片歌单（歌仍在全部音乐里）"
                            else -> "开始整理"
                        },
                        color = if (canTidy) accent.readableTextOn() else colors.subText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "整理只动歌单结构：歌不会丢，哪怕删掉歌单，那些歌也还在「全部音乐」里。",
                    color = colors.subText.copy(alpha = 0.75f),
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 没有可并入的主题歌单时，按钮文案要说明"只删不搬" */
private fun candidates(health: Playlists.LibraryHealth, playlists: List<Playlist>): Boolean =
    playlists.none { p -> health.fragments.none { it.id == p.id } }

@Composable
private fun TidyLabel(text: String, colors: JunColors) {
    Text(
        text,
        color = colors.subText,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun TidyCard(colors: JunColors, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(JunShape.item)
            .background(colors.card)
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun TidyStat(value: String, label: String, colors: JunColors, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.card)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(2.dp))
        Text(label, color = colors.subText, fontSize = 10.5.sp)
    }
}

/** 四层结构的一行：序号圆点 + 层名 + 一句说明 */
@Composable
private fun TidyLayerRow(
    index: String,
    title: String,
    body: String,
    colors: JunColors,
    accent: Color
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (colors.isDark) 0.22f else 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Text(index, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(body, color = colors.subText, fontSize = 11.5.sp, lineHeight = 17.sp)
        }
    }
}
