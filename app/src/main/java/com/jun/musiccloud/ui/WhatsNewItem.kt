package com.jun.musiccloud.ui

/**
 * 公告里的一条。**类型是跨版本稳定的**，每轮换掉的只是正文对象（6.3 是 [WhatsNewR19]）。
 *
 * 单独放一个文件就是这个原因：条目结构不能跟某一份公告同生共死，
 * 否则每换一轮都要把弹层和折叠组一起改一遍。
 *
 * title 写「用户能感知到的那件事」，body 说清「以前怎么样、现在怎么样」，
 * where 是可选的落地位置（写法要和界面上真的字一致，例如「设置 → 全局字体」）。
 *
 * 三段分开存，是因为同一份公告要同时喂三个地方：版本公告弹层、新功能聚光灯、
 * 复制出去的纯文本（官网 / 公告栏）。原先这种文案各写一遍，改了一处漏两处，
 * 用户就会看到「弹层说加了字体、导览里却没提」这种自相矛盾。
 */
data class WhatsNewItem(
    val title: String,
    val body: String,
    /** 落地位置提示；没有就留空，纯文本里也不会多出空括号 */
    val where: String = ""
) {
    /** 纯文本版里的一条：`· 标题：正文（就在 位置）` */
    fun plainLine(): String = buildString {
        // 纯文本版去掉 ** 标记：加粗只属于弹层渲染，纯文本里保留星号是噪音
        append("· ").append(title).append("：").append(body.replace("**", ""))
        if (where.isNotBlank()) append("（就在 ").append(where).append("）")
    }
}
