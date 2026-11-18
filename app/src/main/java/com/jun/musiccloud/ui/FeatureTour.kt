package com.jun.musiccloud.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.jun.musiccloud.ui.theme.JunColors

/**
 * 新功能导览的步骤**编号**（不是下标）。
 *
 * 为什么不用下标：这一版导览讲的东西跨越 5.0 → R9 好几轮，有些步骤是有条件的
 * （没开网易云模块就不讲首页歌单，列表里没有歌就不讲 ⋮，没在放歌就不讲音质按钮）。
 * 步骤一旦会被过滤掉，下标就不再稳定，MainScreen 那边"第几步该打开哪个界面"会全部错位。
 * 所以每个步骤带一个固定 id，过滤后仍然按 id 分派。
 */
internal object FeatureTourSteps {
    const val HOME_FEED = 0
    const val MORE_MENU = 1
    const val PLAYLIST = 2
    const val IMPORT_ENTRY = 3
    const val IMPORT_INPUT = 4
    const val AVATAR = 5
    const val LIBRARY_MODE = 6
    // 6.0 撤掉 TODO = 7（「听歌待办」那行已从「我的」抽屉移除），编号保留空档不改，
    // 免得历史版本里存过的步骤序号对不上。
    const val SEARCH = 8
    const val SEARCH_TABS = 9
    const val SETTINGS = 10
    const val QUALITY = 11
    /** 6.1.0：歌单层里的「分享 / 导入口令」 */
    const val PLAYLIST_SHARE = 12
    /** 6.1.0：设置 → 全局字体（两款新字体 + 回看公告的入口） */
    const val SETTINGS_FONT = 13
    /** 6.7：6.2 → 6.3 更新一览（照着「设置」图标讲，全文在设置 → 查看本次更新公告） */
    const val HISTORY = 14
    /** 6.3：上栏第一枚「听歌排行榜」（金色奖杯） */
    const val RANK = 15
    const val NONE = -1

    /** 这些步骤要看首页：上栏 + 首页信息流 + 歌曲行 */
    fun onHome(step: Int) = step == HOME_FEED || step == MORE_MENU ||
        step == PLAYLIST || step == SEARCH || step == SETTINGS || step == SETTINGS_FONT ||
        step == HISTORY || step == RANK

    /** 歌单弹层里（导入入口 + 粘贴框 + 6.1.0 的分享口令行） */
    fun onPlaylistSheet(step: Int) = step == IMPORT_ENTRY || step == IMPORT_INPUT ||
        step == PLAYLIST_SHARE

    /** 「我的」抽屉里（头像 → 图书馆模式） */
    fun onDrawer(step: Int) = step == AVATAR || step == LIBRARY_MODE

    /** 搜索页整页（结果分类那一排） */
    fun onSearchPage(step: Int) = step == SEARCH_TABS

    /** 播放页（音质徽章） */
    fun onPlayerPage(step: Int) = step == QUALITY
}

/** 一步 = 一个固定 id + 一张聚光灯卡片 */
private class TourStop(val id: Int, val step: SpotlightStep)

/**
 * 新功能导览（5.0 起攒下来的东西走一遍）。
 *
 * 复用首启的聚光灯（[SpotlightGuide]）：全屏遮罩 + 目标镂空 + 气泡讲解，
 * 每一步都直接照在真实控件上，而不是只给一张说明图。
 *
 * 触发方式有两种：
 *  - 升级后自动弹一次：见 [FEATURE_TOUR_VERSION] / [hasSeenFeatureTour]；
 *  - 设置 / 「我的」抽屉里「重看新功能导览」随时重放，不写标记、不影响下次启动。
 *
 * 6.1.0（R13）加的两步：[FeatureTourSteps.PLAYLIST_SHARE]（歌单层讲「分享 / 导入 Jun 口令」）和
 * [FeatureTourSteps.SETTINGS_FONT]（设置 → 全局字体两款新字体 + 回看公告的位置）。
 * 两步都故意复用**已经在界面上登记好的**锚点（[SpotlightKeys.IMPORT_ENTRY] / [SpotlightKeys.SETTINGS]）：
 * 导览走到这两步时该开的界面由下面的分派规则开，洞照的是真东西，不会飘在屏幕中间。
 *
 * 界面动作由调用方（MainScreen）按 [TourStop.id] 驱动，分派规则写在 [FeatureTourSteps] 上：
 * 照首页的步骤先把弹层收干净，照歌单弹层的把弹层拉开，照抽屉的打开抽屉，
 * 照搜索页 / 播放页的翻到对应页面。
 *
 * @param netease 网易云模块开着才讲首页推荐歌单
 * @param hasSongRows 首页列表里现在真的有歌，才指得出 ⋮
 * @param hasCurrentSong 没在放歌就不讲音质按钮 —— 那颗按钮此刻不在界面上
 */
@Composable
internal fun FeatureTourFlow(
    colors: JunColors,
    netease: Boolean,
    hasSongRows: Boolean,
    hasCurrentSong: Boolean,
    onStep: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val stops = remember(netease, hasSongRows, hasCurrentSong) {
        featureTourStops(netease, hasSongRows, hasCurrentSong)
    }
    // 跳过和看完都算「这次导览已送达」：下次升级有新功能时再弹新的
    val finish: () -> Unit = {
        markFeatureTourSeen(ctx)
        onStep(FeatureTourSteps.NONE)
        onDismiss()
    }
    val goStep: (Int) -> Unit = { index ->
        onStep(stops.getOrNull(index)?.id ?: FeatureTourSteps.NONE)
    }
    SpotlightGuide(
        steps = remember(stops) { stops.map { it.step } },
        colors = colors,
        onSkip = finish,
        onFinish = finish,
        onStepChange = goStep
    )
}

/**
 * 步骤表。文案刻意短：聚光灯气泡只有 320dp 宽，讲清「在哪、点什么、能得到什么」就够。
 * 顺序按**界面上会走到哪儿**排，不按功能上线的先后排：
 * 首页 → 歌曲行 → 上栏 → 歌单弹层 → 头像抽屉 → 搜索 → 设置 → 播放页，
 * 每换一次界面最多换一次，不用来回跳。
 */
private fun featureTourStops(
    netease: Boolean,
    hasSongRows: Boolean,
    hasCurrentSong: Boolean
): List<TourStop> {
    val stops = mutableListOf<TourStop>()
    // 6.3：排行榜排在最前面 —— 用户要求「先给 App 加个引导，就是排行榜的引导」。
    // 它挂在首页上栏第一枚（金色奖杯），照首页的步骤本来就会先把弹层收干净，位置正好。
    stops += TourStop(
        FeatureTourSteps.RANK,
        SpotlightStep(
            anchorKey = SpotlightKeys.RANK,
            title = "新功能：听歌排行榜",
            body = "上栏这枚金色奖杯就是排行榜：**总榜 / 周榜 / 日榜**，只放前 10 名。" +
                "榜单里有段位——初听者、通勤听歌人、耳机钉子户，共 11 档；" +
                "听得越多段位越高，点开自己的主页也能看到段位和还差多少升下一档。",
            shape = SpotlightShape.CIRCLE
        )
    )
    if (netease) {
        stops += TourStop(
            FeatureTourSteps.HOME_FEED,
            SpotlightStep(
                anchorKey = SpotlightKeys.HOME_FEED,
                title = "首页：推荐歌单不用登录",
                body = "推荐歌单搬回首页了，三列卡片，封面右上角写着 ▶ 播放量。" +
                    "不想看这批就点「换一批」，一直往下滑还会自动再要一批。",
                shape = SpotlightShape.ROUND_RECT,
                bubbleBelow = true
            )
        )
    }
    if (hasSongRows) {
        stops += TourStop(
            FeatureTourSteps.MORE_MENU,
            SpotlightStep(
                anchorKey = SpotlightKeys.MORE_MENU,
                title = "每行末尾的三个点",
                body = "爱心和时长从行尾撤了，换成 ⋮。点开是我喜欢、下一首播放、播放，" +
                    "再往下是添加到歌单、下载、评论和全选；菜单从手指落点鼓起来。",
                shape = SpotlightShape.CIRCLE
            )
        )
    }
    stops += TourStop(
        FeatureTourSteps.PLAYLIST,
        SpotlightStep(
            anchorKey = SpotlightKeys.PLAYLIST,
            title = "歌单按钮",
            body = "点这里打开歌单层：本机歌单、网易云歌单和**导入入口**都在这一层；新建、导入、分享的按钮都在页面下方，**导入不再有首数限制**。",
            shape = SpotlightShape.CIRCLE
        )
    )
    stops += TourStop(
        FeatureTourSteps.IMPORT_ENTRY,
        SpotlightStep(
            anchorKey = SpotlightKeys.IMPORT_ENTRY,
            title = "三平台导入：最多 200 首",
            body = "网易云链接 / ID、QQ 和汽水的分享链接都能导入，**一次最多 200 首**。复制好链接回到 App，" +
                "它会自动问你要不要导进来。",
            shape = SpotlightShape.ROUND_RECT
        )
    )
    stops += TourStop(
        FeatureTourSteps.IMPORT_INPUT,
        SpotlightStep(
            anchorKey = SpotlightKeys.IMPORT_INPUT,
            title = "整段分享文案直接粘",
            body = "不用手动删「分享这个歌单」那些字。QQ / 汽水歌单要靠服务器逐首匹配网易云，" +
                "匹配不到的会把歌名列出来告诉你，不再悄悄丢歌。",
            shape = SpotlightShape.ROUND_RECT
        )
    )
    // 6.1.0 第 ① 步：紧挨着上面两步，洞还在歌单层里挪一小段，不用换界面。
    // 分享和导入是两个动作、两行入口，所以单独一步讲，不塞进「三平台导入」那段文案里。
    stops += TourStop(
        FeatureTourSteps.PLAYLIST_SHARE,
        SpotlightStep(
            // 洞照的是**已经登记好的**那行（PlaylistSheets.kt 的「⇩ 导入歌单」，key IMPORT_ENTRY）。
            // 不新造 key：分享按钮挂在每张歌单卡片的封面右上角（PlaylistSheets.kt 的 PlaylistGridCard），
            // 照一个没登记的 key 只会让洞飘在屏幕中间；等分享行真的落进歌单层，再换独立 key 不迟。
            anchorKey = SpotlightKeys.IMPORT_ENTRY,
            title = "歌单现在能互相传了",
            body = "每张歌单卡片右上角那颗分享图标生成一段 JM 开头的短口令，复制或发给朋友；" +
                "他粘贴到这一行就导入成自己的新歌单，对不上的歌会一条条列出来。" +
                "网易云 / QQ 那些链接照旧走这里。",
            shape = SpotlightShape.ROUND_RECT
        )
    )
    stops += TourStop(
        FeatureTourSteps.AVATAR,
        SpotlightStep(
            anchorKey = SpotlightKeys.AVATAR,
            title = "右上角头像",
            body = "「我的」抽屉在这儿：账号、图书馆模式、歌单、下载管理都从这一层进，" +
                "几个分组默认是收起的，点标题才展开。",
            shape = SpotlightShape.CIRCLE
        )
    )
    stops += TourStop(
        FeatureTourSteps.LIBRARY_MODE,
        SpotlightStep(
            anchorKey = SpotlightKeys.LIBRARY_MODE,
            title = "图书馆模式",
            body = "开启后只放轻音乐、纯音乐、白噪音、lofi；带歌词的歌排队时就被筛掉，播放中不会跳歌。",
            shape = SpotlightShape.ROUND_RECT
        )
    )
    // 6.0：「听歌待办」这一步删掉 —— 抽屉里那一行已经按用户要求撤了，
    // 聚光灯再指过去就只剩一个对着空气的洞。
    stops += TourStop(
        FeatureTourSteps.SEARCH,
        SpotlightStep(
            anchorKey = SpotlightKeys.SEARCH,
            title = "搜索：进来不再怼键盘",
            body = "打开搜索页先给你看历史和猜你喜欢，键盘不自动弹；列表一往下滑，键盘就自己收起来。",
            shape = SpotlightShape.ROUND_RECT
        )
    )
    stops += TourStop(
        FeatureTourSteps.SEARCH_TABS,
        SpotlightStep(
            anchorKey = SpotlightKeys.SEARCH_TABS,
            title = "结果分类可以左右滑",
            body = "单曲 / 歌手 / 专辑 / 歌单四个分类，手指左右滑就切得动，不用来回点。" +
                "歌单一页 30 条，往下滑还有；猜你喜欢也缩小了。",
            shape = SpotlightShape.ROUND_RECT
        )
    )
    stops += TourStop(
        FeatureTourSteps.SETTINGS,
        SpotlightStep(
            anchorKey = SpotlightKeys.SETTINGS,
            title = "设置里两个新开关",
            body = "在线播放缓存默认开着（256MB 上限，能看占了多少、能一键清空）；" +
                "「点歌直接展开播放器」现在默认关 —— 首页点歌不再跳播放页。",
            shape = SpotlightShape.CIRCLE
        )
    )
    // 6.1.0 第 ② 步：仍然照上栏那枚「设置」，洞不动、只换讲解，
    // 免得为了一步文案把用户带去设置里再翻回来（导览期间设置层是被收掉的）。
    stops += TourStop(
        FeatureTourSteps.SETTINGS_FONT,
        SpotlightStep(
            anchorKey = SpotlightKeys.SETTINGS,
            title = "设置里多了两款字体",
            body = "「设置 → 全局字体」新加志勇大同体、乐米波波体，界面和歌词一起换；" +
                "四款字体都重新裁剪压缩过，安装包比上一版更小。" +
                "同一层还有「查看本次更新公告」，这一轮改了啥随时能回去看。",
            shape = SpotlightShape.CIRCLE
        )
    )
    // 6.7：用户点名「从 6.2 版本到现在一共更新了啥，都要引导一下」。
    // 照的还是上栏那枚「设置」（全文入口在设置 → 查看本次更新公告），不新造锚点。
    stops += TourStop(
        FeatureTourSteps.HISTORY,
        SpotlightStep(
            anchorKey = SpotlightKeys.SETTINGS,
            title = "6.2 → 6.3 都更新了啥",
            // 6.3 定稿：对外统一叫 6.3，中间那些内测版本号不再往外报（用户："别让我看着像发过 8 个版本"）
            body = "**6.2** 批量下载、修闪退；**6.3** 首页重排、点歌秒响、真无损、一键搬家；" +
                "**6.3 内测** 每日新歌三行、播放库整理、首页对齐、源挂了不变慢、动效风格可选、液态玻璃；" +
                "**6.3 正式版** 听歌排行榜与段位。" +
                "完整清单在设置 → 查看本次更新公告。",
            shape = SpotlightShape.CIRCLE
        )
    )
    if (hasCurrentSong) {
        stops += TourStop(
            FeatureTourSteps.QUALITY,
            SpotlightStep(
                anchorKey = SpotlightKeys.QUALITY,
                title = "音质按钮：单击切档，长按频谱",
                body = "徽章上只写档位名。切档那一刻浮出一条提示说实际拿到哪一档，看完自己消失；" +
                    "它是固定宽度，不会再横着拉长。",
                shape = SpotlightShape.ROUND_RECT,
                bubbleBelow = false
            )
        )
    }
    return stops
}
