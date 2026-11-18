package com.jun.musiccloud.ui.batchdl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jun.musiccloud.data.RemoteConfigStore
import com.jun.musiccloud.data.Song
import com.jun.musiccloud.player.PlayerViewModel
import com.jun.musiccloud.ui.PressFeedback
import com.jun.musiccloud.ui.junPressable
import com.jun.musiccloud.ui.theme.JunShape

/**
 * 多选操作条上的「批量下载」入口。
 *
 * 为什么单独一个包：工程里带全选/多选的列表有好几处（首页曲库、搜索结果、歌手详情、
 * 网易云榜单/歌单详情，以及播放列表面板里的 队列 / 我喜欢 / 歌单 / 最近播放），
 * 每一处的多选条都必须有同一颗「下载」，而且点下去的行为要一模一样。
 * 行为写在各处就是五份各自演化的下载器；收在这里，界面只负责把它摆进自己的操作条。
 *
 * 真源仍然只有一份：这里不建任务表、不判断"这首歌下载过没有"、不自己开协程下载。
 * 整批交给 [PlayerViewModel.batchDownload]，它转发到 DownloadManager 的批量收口
 * （`downloadSongsBatch`），同歌去重 / 在途去重 / 跳过已下载 / 并发上限 / 空间预检
 * 都在那边一次做完。
 * 回执写进 `state.downloadNotice`，由 MainScreen 根层级的 ToastHost 弹在弹层之上，
 * 所以这些面板里点下载也看得到那句「新增 N 首 / 已在队列 M 首 / 跳过已下载 K 首」；
 * 同一批还会记进「最近批量操作」，撤销 = 取消这批里还没下完的任务。
 */
@Composable
internal fun BatchDownloadButton(
    accent: Color,
    modifier: Modifier = Modifier,
    /** 一首都没选中时按下去没有意义：置灰但仍然占位，避免操作条在勾选过程中左右跳动 */
    enabled: Boolean = true,
    /** 没选中就点了该说什么。传 null = 什么都不做（保留给确实不需要提示的地方） */
    onEmptyClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Box(
        modifier
            // 6.2：这里刻意不把 enabled 交给 junPressable —— clickable(enabled=false)
            // 会把点击整个吞掉，用户看到一颗灰钮按下去毫无反应（PlaylistShareSheet 同款问题）。
            // 按钮永远可点，没选中时改说一句人话。
            .junPressable(scaleDown = 0.94f, feedback = PressFeedback.CLICK) {
                if (enabled) onClick() else onEmptyClick?.invoke()
            }
            .clip(JunShape.item)
            .background(accent.copy(alpha = if (enabled) 0.15f else 0.08f))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "下载",
            color = accent.copy(alpha = if (enabled) 1f else 0.45f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 后台有没有放开「下载」功能。
 *
 * 与主界面那条批量栏同一个判据（RemoteConfigStore 下发的 AppFlags.download）：
 * 后台关掉下载时，所有多选条里都不该冒出这颗按钮，不能只有首页那条听话。
 */
@Composable
internal fun downloadFeatureOn(): Boolean = RemoteConfigStore.flagsState.value.download

/**
 * 整批提交下载。
 *
 * 只做「空列表不提交」这一件事，其余全部转发给 ViewModel 既有收口
 * `PlayerViewModel.batchDownload(songs, source)`：判重、跳过已下载、并发上限、空间预检、
 * 一句汇总回执和「撤销」都在那里。
 * 单独收一层是为了别让各处多选条各写一份 —— 之前有一处直接调了 `downloadSongs`，
 * 绕开了汇总回执，用户点完全选按下载后什么话都没说一句。
 */
internal fun submitBatchDownload(
    viewModel: com.jun.musiccloud.player.PlayerViewModel,
    songs: List<com.jun.musiccloud.data.Song>,
    source: String
) {
    if (songs.isEmpty()) return
    viewModel.batchDownload(songs, source)
}
