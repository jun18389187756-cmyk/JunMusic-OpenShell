package com.jun.musiccloud.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.jun.musiccloud.data.GuideStats
import com.jun.musiccloud.ui.theme.JunColors

/**
 * 「我的」抽屉里的聚光灯从第几步开始需要打开抽屉。
 * 抽屉里只剩「登录 / 注册」这一步（6.0 起「听歌待办」已从抽屉移除），指到它之前要先把抽屉拉开。
 */
internal const val FIRST_DRAWER_STEP = 5

/**
 * 设置里的「重看新手引导」。
 *
 * 首启已经不再播放绘本和聚光灯（见 [FirstRunSetup]），这两段只保留在这里：
 * 用户想再看一遍时即时打开，看完即走，不写「已看过」标记、也不影响下次启动。
 * 仍然是「欢迎绘本 → 界面指路」的顺序：先讲能干什么，再指按钮在哪。
 */
@Composable
internal fun ReplayGuideFlow(
    colors: JunColors,
    onGuideStep: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    var spotlight by remember { mutableStateOf(false) }
    val startedAt = remember { System.currentTimeMillis() }
    var lastStep by remember { mutableIntStateOf(0) }

    // 看完或跳过都记一次埋点；设置里重看不算「首次引导」，所以不写 onboarding 标记。
    val stop: (Boolean) -> Unit = { completed ->
        GuideStats.record(
            ctx,
            completed = completed,
            reachedStep = if (completed) Int.MAX_VALUE else lastStep,
            elapsedMs = System.currentTimeMillis() - startedAt
        )
        onGuideStep(-1)
        onDismiss()
    }

    if (!spotlight) {
        StoryWelcome(
            colors = colors,
            onSkip = { Haptics.tap(); stop(false) },
            onFinish = {
                Haptics.click()
                GuideStats.recordStart(ctx)
                spotlight = true
            }
        )
    } else {
        SpotlightGuide(
            steps = remember { defaultSpotlightSteps() },
            colors = colors,
            onSkip = { stop(false) },
            onFinish = { stop(true) },
            onStepChange = { i -> if (i >= 0) lastStep = i; onGuideStep(i) }
        )
    }
}

/**
 * 聚光灯步骤：把上栏和「我的」里的入口逐个讲完。
 *
 * 顺序 = 界面从左到右的实际排列，用户指完一个眼就往右挪一个；
 * 最后一步钻进「我的」抽屉，把**登录 / 注册**这个藏得最深、
 * 又最影响留存的功能也照一遍。
 */
private fun defaultSpotlightSteps(): List<SpotlightStep> = listOf(
    SpotlightStep(
        anchorKey = SpotlightKeys.SEARCH,
        title = "① 搜索常驻在顶部",
        body = "点一下就能搜歌名、歌手、专辑；结果里直接「下一首播放」或收藏，不用切来切去。",
        shape = SpotlightShape.ROUND_RECT
    ),
    SpotlightStep(
        anchorKey = SpotlightKeys.PLAYLIST,
        title = "② 歌单都收在这里",
        body = "我喜欢的音乐、自建歌单、在线榜单，都从这枚按钮进。",
        shape = SpotlightShape.CIRCLE
    ),
    SpotlightStep(
        anchorKey = SpotlightKeys.LAYOUT,
        title = "③ 换一种看法",
        body = "按一下在「纵向列表 / 卡片 / 唱片墙」之间切换；卡片模式下整屏就是播放器。",
        shape = SpotlightShape.CIRCLE
    ),
    SpotlightStep(
        anchorKey = SpotlightKeys.AVATAR,
        title = "④ 「我的」在这里",
        body = "头像点开是「我的」：账号、歌单、收听报告、设置都在里面，分组默认收起。最后一步我们会进去看。",
        shape = SpotlightShape.CIRCLE
    ),
    SpotlightStep(
        anchorKey = SpotlightKeys.SETTINGS,
        title = "⑤ 设置",
        body = "主题配色、界面排布、全局字体、音效开关、检查更新，全都藏在这一枚里；" +
            "6.1.0 在这里新加两款字体（志勇大同体、乐米波波体），" +
            "同一层还有「查看本次更新公告」，这一轮改了啥随时能回看。",
        shape = SpotlightShape.CIRCLE
    ),
    SpotlightStep(
        anchorKey = SpotlightKeys.ACCOUNT,
        title = "⑥ 登录 / 注册（可选）",
        body = "点这里注册或登录：「我喜欢的音乐」与歌单会同步到服务器，换手机也不丢。\n不登录也照常能听，只是不同步。",
        shape = SpotlightShape.ROUND_RECT,
        bubbleBelow = false
    )
    // 6.0：原来的第 ⑦ 步「听歌待办」删掉了 —— 抽屉里那一行已经按用户要求撤除。
)

/** 供设置页复用的「引导数据」文案：上一次引导是看完还是跳过、停在第几步 */
internal fun guideStatsSummary(context: Context): String = GuideStats.summary(context)
