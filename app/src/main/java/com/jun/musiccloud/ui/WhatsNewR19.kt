package com.jun.musiccloud.ui

/**
 * 6.6 最新版更新公告的**唯一数据源**（文件名沿用历史，不再新建第 N 份，避免改错那份）。
 *
 * 这一版用户的要求：把「6.5 → 6.6 之间之前那版重做的 13 项修复」+「这一轮又修的」
 * +「新版本加的功能」**全部**列进弹窗，重点引导【自定义界面】和【气氛模式】两个功能，
 * 并且如实写出还没做到的边界（锁屏系统调度 / 汽水源站缺歌 / 逐字点亮延迟未动）。
 *
 * 三个渲染位共用这一份数据，口径绝不打架：
 *  - 刚进软件的引导弹层 [FeatureGuideSheet]（3 步：两个功能引导 + 完整清单）；
 *  - 设置 → 查看本次更新公告 [WhatsNewDialog]；
 *  - 复制出去的纯文本 [PLAIN_TEXT]（官网 / 公告栏）。
 *
 * 规矩仍然只有一条：**公告正文只写在这里**。
 *  - 「弹不弹」由 GuidePrefs.kt 的 `WHATS_NEW_CODE` 决定，且必须等于 [CODE]（本轮 129）。
 *  - versionCode 抬到 129，全新安装与覆盖安装都会重新弹一次这份 6.6 最新版引导。
 */
object WhatsNewR19 {

    /** 版本码，与 app/build.gradle.kts 的 versionCode 保持一致 */
    const val CODE = 129

    /** 对外版本号 */
    const val VERSION_NAME = "6.6"

    const val DIALOG_TITLE = "6.6 更新公告"

    /** 历史总结折叠条的标题（弹层与纯文本共用，别在 UI 里再手写一遍） */
    const val HISTORY_LABEL = "6.5 → 6.6 都做了啥"

    /** 一条重点引导的功能（前两步引导卡用） */
    data class Highlight(
        val tag: String,
        val title: String,
        val desc: String,
        val where: String,
        val action: String
    )

    /** 本次重点引导的两个新功能 */
    val HIGHLIGHTS: List<Highlight> = listOf(
        Highlight(
            tag = "新功能 ①",
            title = "自定义界面",
            desc = "一个居中的玻璃弹窗：首页模块、背景、外观、排版、小播放器、入口、玻璃质感，" +
                "想留什么、想去掉什么都在这一页。默认就是整个 App 那套液态玻璃，" +
                "在设置里换成毛玻璃或实色，它也跟着变。",
            where = "设置 → 顶部「自定义中心」",
            action = "立即打开自定义"
        ),
        Highlight(
            tag = "新功能 ②",
            title = "气氛模式",
            desc = "按当下的心情挑一张歌单：夜跑、通勤、下雨、睡前……" +
                "每个模式都是真实歌单封面，点开就接着听，不用自己翻。",
            where = "首页向右滑 → 模式探索 → 气氛模式",
            action = "去气氛模式"
        )
    )

    /** 修好的：之前那版重做的 13 项 + 这一轮又修的，一条一句人话 */
    val FIXES: List<WhatsNewItem> = listOf(
        WhatsNewItem("返回不再重建播放队列", "切歌、返回都稳了，不会把正在放的队列冲掉。", "全局"),
        WhatsNewItem("锁屏界面秒出", "防抖从 600ms 压到 120ms，去掉系统转场动画和首帧等数据。", "锁屏"),
        WhatsNewItem("汽水歌单断点续导", "分片失败自动重试 3 次；同一链接复用歌单、去重补齐。", "歌单导入"),
        WhatsNewItem("QQ 歌词有兜底", "主歌词取不到时用评论等兜底，不再空着。", "歌词"),
        WhatsNewItem("横屏退出直接交还系统方向", "不再先竖屏、再横屏闪一下。", "横屏"),
        WhatsNewItem("焦点丢失后自动恢复播放", "切出去再回来接着放，不用手动点。", "播放"),
        WhatsNewItem("底部 400 首可一键隐藏", "列表太长时一键收起，清爽。", "音乐库"),
        WhatsNewItem("歌单页返回按钮", "液态玻璃质感，带按压回弹和入场动效。", "歌单页"),
        WhatsNewItem("播放列表已下载标志", "哪首下没下过，一眼看得出。", "播放列表"),
        WhatsNewItem("自定义中心回到液态玻璃", "不再一遮全是黑，跟整个 App 同一套玻璃；改毛玻璃/实色它跟着变。", "自定义中心"),
        WhatsNewItem("外观页按设置界面统一", "去掉没用的「即套用」，主题配色、按钮配色、返回都能用，每个都有颜色。", "自定义中心 / 设置"),
        WhatsNewItem("小播放器去掉中间竖杠", "学大播放器的干净排布，去掉上一曲/下一曲之间那道竖杠。", "小播放器"),
        WhatsNewItem("排版密度全 App 生效", "以前只改自定义中心局部，现在和设置界面一样，整 App 一起变。", "全局"),
        WhatsNewItem("材质默认液态玻璃、随动效切换", "默认就是这套 App 的液态玻璃，你选毛玻璃才变毛玻璃。", "自定义中心"),
        WhatsNewItem("默认值理顺", "桌面悬浮歌词默认关、不再自己弹授权；锁屏显示播放界面正常可用，不再写「研发中」。", "设置"),
        WhatsNewItem("整体更流畅", "减少无谓重组、缓存密度实例，滚动和动画更跟手。", "全局"),
        WhatsNewItem("歌词不再乱跳 / 越唱越偏", "取词改用版本指纹（歌名+时长+专辑+音源 id），删掉会把时间轴拉歪的猜测式缩放；" +
            "匹配不上就老实说「未找到匹配歌词」，不再硬套近似结果。", "歌词")
    )

    /** 新加的：能直接摸到的功能，重点两条同时也是引导卡 */
    val NEWS: List<WhatsNewItem> = listOf(
        WhatsNewItem("自定义界面", "居中玻璃弹窗，首页模块/背景/外观/排版/小播放器/入口/玻璃质感都能自己留或去掉。", "设置 → 顶部「自定义中心」"),
        WhatsNewItem("气氛模式", "按心情挑歌单：夜跑、通勤、下雨、睡前……真实封面，点开就接着听。", "首页向右滑 → 模式探索"),
        WhatsNewItem("玻璃质感调参页", "自定义中心新一页：跟手高光、边缘高光/内阴影、按压回弹实时可调，拖一下全 App 玻璃同帧跟着变。", "自定义中心 → 玻璃质感"),
        WhatsNewItem("小播放器歌名排布", "默认「歌名在上/歌手在下」，也能切成「赵雷 - 家乡」单行。", "自定义中心 → 小播放器"),
        WhatsNewItem("小播放器药丸岛", "收窄成药丸：只留暂停键，左滑下一首/右滑上一首，去掉白进度条、上移 12dp。", "自定义中心 → 小播放器"),
        WhatsNewItem("自定义 EQ", "拖完停 400ms 自动落盘，能命名保存多套「我的 EQ」，冷启动不覆盖已存的。", "设置 → 均衡器"),
        WhatsNewItem("通知栏歌词开关", "想在通知栏看歌词就开，不想就关。", "设置"),
        WhatsNewItem("设置里歌词实时预览卡", "调字号、颜色、排版时当场看效果，不用退出去试。", "设置"),
        WhatsNewItem("音源粘贴链接添加", "复制好的链接直接粘进来加歌，不用手敲。", "音源 / 导入"),
        WhatsNewItem("开机新功能引导", "刚进软件这份引导，重点带你认【自定义界面】和【气氛模式】，两个都能一键直达。", "启动")
    )

    /** 如实说明的边界：这轮没做到 / 不由 App 控制 / 需要你给信息才能治的 */
    val NOTES: List<WhatsNewItem> = listOf(
        WhatsNewItem("锁屏那几十毫秒", "能去掉的等待都去掉了，但系统调度锁屏界面那几十毫秒不由 App 控制，偶尔仍有极短延迟。"),
        WhatsNewItem("汽水续导仍会缺歌", "重试和去重补齐做了，但源站本身不给的歌仍会缺，会列进「未导入清单」，不悄悄丢。"),
        WhatsNewItem("逐字点亮局部延迟（这轮没动）", "怕把已对齐的改坏，这轮没碰歌词时间轴，这项还留着。要治它，把对不准的歌名发我，按首对齐。")
    )

    val SUBTITLE: String = "6.5 → 6.6 最新版 · ${FIXES.size + NEWS.size + NOTES.size} 条，一次说清"

    val HISTORY: List<Pair<String, String>> = listOf(
        "6.5 → 6.6 之间那版" to "返回切歌、通知栏歌词、自定义 EQ、锁屏秒出、汽水断点续导、QQ 歌词兜底、横屏、药丸岛、已下载标志等 13 项集中修复",
        "6.6 最新版" to "界面统一成液态玻璃、排版密度全 App 生效、歌词版本指纹；自定义中心 / 气氛模式 / 玻璃质感 / 歌名排布 / 开机引导；并把全部更新列进一份清单"
    )

    val HISTORY_TEXT: String =
        "6.5 → 6.6 都做了啥（人话版）\n" + HISTORY.joinToString("\n") { "· ${it.first}：${it.second}" }

    const val FOOTER = "同签名覆盖安装，登录态、歌单、设置、下载记录都保留，不用先卸载。"

    val PLAIN_TEXT: String = buildPlain()

    private fun buildPlain(): String = buildString {
        append("Jun Music ").append(VERSION_NAME).append(" 最新版 更新公告\n\n")
        append("从 6.5 到 6.6，一次说清：\n\n")
        append("【两个重点功能】\n")
        HIGHLIGHTS.forEach { append("· ").append(it.title).append("：").append(it.desc.replace("**", ""))
            .append("（").append(it.where).append("）\n") }
        append("\n【修好的】\n")
        FIXES.forEach { append(it.plainLine()).append("\n") }
        append("\n【新加的】\n")
        NEWS.forEach { append(it.plainLine()).append("\n") }
        append("\n【说明与边界】\n")
        NOTES.forEach { append(it.plainLine()).append("\n") }
        append("\n").append(FOOTER).append("\n\n")
        append(HISTORY_TEXT)
    }
}
