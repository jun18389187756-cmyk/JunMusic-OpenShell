package com.jun.musiccloud.ui

import android.content.Context

/**
 * 与「首启引导」相关的全部本地标记，集中在一处。
 *
 * 原先这些常量和读写散在引导组件内部，而引导组件换过三代（四步卡片 → 布局选择器 →
 * 现在的绘本 + 聚光灯），每换一代就要把 prefs 逻辑再抄一遍。
 * 标记本身与 UI 无关，单独放这里：换 UI 不动数据，老用户不会因为升级被重新引导一遍。
 */
private const val GUIDE_PREFS = "jun_guide"
private const val KEY_GUIDE_DONE = "onboarding_done_v1"
private const val KEY_GUIDE_VERSION = "onboarding_version"
private const val KEY_NOTIF_ASKED = "notif_perm_asked_v1"
private const val KEY_FEATURE_TOUR_VERSION = "feature_tour_version"

/**
 * 当前引导版本。引导的步骤 / 文案有实质更新时 +1，
 * 老用户升级后会因「看过的版本 < 当前版本」而再放一遍新引导，
 * 不会因为升级被永久错过新功能介绍。
 */
const val GUIDE_VERSION = 2

/** 是否已经看过「当前版本」的首启引导 */
fun hasSeenOnboarding(context: Context): Boolean {
    val p = context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
    return p.getBoolean(KEY_GUIDE_DONE, false) && p.getInt(KEY_GUIDE_VERSION, 0) >= GUIDE_VERSION
}

/** 标记为已看过（跳过 / 看完都调用），并记录当前版本 */
fun markOnboardingSeen(context: Context) {
    context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_GUIDE_DONE, true).putInt(KEY_GUIDE_VERSION, GUIDE_VERSION).apply()
}

/** 重置为「未看过」：设置里「重看新手引导」用，下次冷启动重放整套引导（含版本号） */
fun resetOnboarding(context: Context) {
    context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_GUIDE_DONE, false).putInt(KEY_GUIDE_VERSION, 0).apply()
}

/**
 * 新功能导览的版本号。每加一组需要「指位置」的新功能就 +1，
 * 老用户升级后自动看一次对应新功能的聚光灯导览；新装用户则从下一次启动开始。
 *
 * v3：三平台歌单导入 + 复制链接自动弹导入 + 图书馆模式。
 * v4：重做成「5.0 起攒下来的新功能走一遍」——首页免登录推荐歌单（三列 / 播放量 / 换一批 /
 * 下滑加载）、行尾 ⋮ 菜单、歌单导入（含匹配不到会列出歌名）、图书馆模式、
 * 搜索页（不自动弹键盘 / 结果分类左右滑 / 歌单一页 30 条）、设置里的在线缓存与
 * 「点歌直接展开播放器」默认关、播放页音质按钮与切档提示。
 *
 * 6.0：这条导览**不再自动弹**。它和版本更新说明抢同一段时间窗，更新说明会被顶掉。
 * 入口留在「设置 → 重看新功能导览」，标记函数保留，老用户本机存过的序号不受影响。
 *
 * v5（6.1.0 / R13）：补两步——歌单层的「分享 / 导入口令」、设置里的「全局字体」两款新字体。
 * 依旧**不自动弹**（6.0 那条结论没变）；抬版本只是让本机存的序号跟得上步骤表，
 * 读写函数和老用户已经存过的值都不动。
 *
 * 6.1.1（R14）：只对**第一次安装**的人放开一次自动弹，而且严格排在公告关掉之后
 * （见 [hasAutoFeatureTourShown] 与 MainScreen 里那条链路）。
 * 6.3 第四轮（R16b）：用户要求「每次更新都会出现引导」。做法改为：
 * 每轮把 [FEATURE_TOUR_VERSION] 抬 1（本轮 5 → 6），所有用户升级后自动重走一遍新导览。
 * 同时自动弹出不再只限新装机用户（见 MainScreen 的触发条件）。
 * 6.3 第五轮（R19b）：抬 6 → 7，第一步换成「听歌排行榜」（[FeatureTourSteps.RANK]）。
 * 用户原话「先给 App 加个引导，就是排行榜的引导」——这轮强制下发要保证升级的人
 * 打开就被指到上栏那枚金色奖杯上。
 */
const val FEATURE_TOUR_VERSION = 7

/** 是否已经看过当前版本的新功能导览 */
fun hasSeenFeatureTour(context: Context): Boolean =
    context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_FEATURE_TOUR_VERSION, 0) >= FEATURE_TOUR_VERSION

/** 跳过 / 看完都调用：记下当前导览版本，不再自动弹出 */
fun markFeatureTourSeen(context: Context) {
    context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .edit().putInt(KEY_FEATURE_TOUR_VERSION, FEATURE_TOUR_VERSION).apply()
}

/** 设置里「重看新功能导览」用：只清导览标记，不影响首启引导 */
fun resetFeatureTour(context: Context) {
    context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .edit().putInt(KEY_FEATURE_TOUR_VERSION, 0).apply()
}

private const val KEY_FEATURE_TOUR_AUTO = "feature_tour_auto_shown_ver"

/**
 * 「本轮版本的自动导览」是否已经送达过。
 *
 * 6.3 第四轮起按版本记（存的是版本号）：每轮抬 [FEATURE_TOUR_VERSION]，
 * 老用户升级后也会自动弹一次新导览；同一版本内只弹一次，中途杀掉也不会重复。
 * 设置里「重看导览」走 [resetFeatureTour]，与这里互不影响。
 */
fun hasAutoFeatureTourShown(context: Context): Boolean =
    context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_FEATURE_TOUR_AUTO, 0) >= FEATURE_TOUR_VERSION

/**
 * 记下「自动导览已经出现过」。
 *
 * 在**发起**的时候就写，不是等看完再写：中途被用户杀掉或者进程没了，下次启动不该再来一遍，
 * 否则就成了「每次进去都拦着我走导览」。看完 / 跳过仍然会另外写 [markFeatureTourSeen]。
 */
fun markAutoFeatureTourShown(context: Context) {
    context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .edit().putInt(KEY_FEATURE_TOUR_AUTO, FEATURE_TOUR_VERSION).apply()
}

/**
 * 版本更新说明（当前是 6.3 那份公告）：每个门槛只自动弹一次，并且排在所有启动流程最后。
 *
 * 门槛直接取 [WhatsNewR19.CODE]：公告内容和「弹不弹」必须同一个口径，
 * 改了公告忘了改门槛 = 老用户永远看不到这一轮。
 *
 * **这里有个反复踩的坑，写给下一轮**：判据是阈值比较 `存过的 >= WHATS_NEW_CODE`，不是「等于」。
 *  - 这一轮出了新公告、门槛却还停在 62：看过 6.1.0 公告的人本机存的正是 62，
 *    `62 >= 62` 直接成立，**新公告对他一辈子都不会出现**——哪怕 build.gradle.kts 里的
 *    versionCode 已经抬到 63 也没用，那个数只管「能不能收到更新包」，不管公告。
 *  - 反过来，只把门槛抬到 63、正文还写着 6.1.0：用户会被一个旧公告再打扰一次。
 * 所以规则是：**出新公告 = 同一次提交里把 WHATS_NEW_CODE 抬到本轮版本码**，
 * 并且正文与版本行只改 [WhatsNewR19] 那一处（新一轮就新建 `WhatsNewR20` 并把这里换过去，
 * 上一轮的正文文件删掉——留两份只会让人改错那份，历史看仓库根的更新说明就行）。
 * 另注意别把门槛抬过头：`markWhatsNewSeen` 写的就是当前门槛值，
 * 要是哪一轮图省事直接写成 999，看过那一轮公告的人本机存的就是 999，
 * 之后任何低于 999 的门槛都会被 `999 >= 门槛` 判成「已经看过」，中间几轮的公告全废。
 */
/* 6.9.3：门槛直接跟本轮 versionCode 对齐（108）。
   6.3 那版写的是 74，之后一直没抬 —— 老用户本机存着 74，`74 >= 74` 成立，
   所以 6.9.0~6.9.2 的新功能说明他们一次都没看到。用户这一轮点名"要引导"，
   必须把门槛抬到本轮版本，否则弹层永远不出现。 */
/**
 * 6.4（R47）：114 → 115。
 * 这一版的「新功能说明」把 6.4~6.9.9 的所有条目合并成一份 6.4 清单，
 * 内容整块换过，所以抬到 115，已装的机器升级后会重看一次。
 */
// 6.6 最新版：门槛抬到本轮版本码 129（versionCode 也是 129）。
// 全新安装 prefs 里没值 → 一定弹；老机器覆盖安装本机存的是旧门槛（128 或更早），
// `旧值 >= 129` 不成立 → 也会重看一次 6.6 最新版引导，不会「升级了反而啥也没提示」。
const val WHATS_NEW_CODE = 129
private const val KEY_WHATS_NEW_CODE = "whats_new_code"
private const val KEY_VIP_COMP_SEEN = "vip_comp_seen_v403"

fun hasSeenWhatsNew(context: Context): Boolean =
    context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_WHATS_NEW_CODE, 0) >= WHATS_NEW_CODE

fun markWhatsNewSeen(context: Context) {
    context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .edit().putInt(KEY_WHATS_NEW_CODE, WHATS_NEW_CODE).apply()
}

/** 4.0.3 补偿弹窗是否已展示过（关闭 / 领取都算，之后仍在账号弹层里可领） */
fun hasSeenVipCompensation(context: Context): Boolean =
    context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_VIP_COMP_SEEN, false)

fun markVipCompensationSeen(context: Context) {
    context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_VIP_COMP_SEEN, true).apply()
}

/** 通知权限是否已经问过（问过一次就不再用预说明卡片打扰） */
fun hasAskedNotification(context: Context): Boolean =
    context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_NOTIF_ASKED, false)

fun markNotificationAsked(context: Context) {
    context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
}
