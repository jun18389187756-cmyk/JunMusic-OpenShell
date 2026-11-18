package com.jun.musiccloud.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jun.musiccloud.ui.theme.GlassContentColumn
import com.jun.musiccloud.ui.theme.GlassLevel
import com.jun.musiccloud.ui.theme.JunColors
import com.jun.musiccloud.ui.theme.JunShape
import com.jun.musiccloud.ui.theme.isLiquidGlass
import com.jun.musiccloud.ui.theme.junSurface
import com.jun.musiccloud.ui.theme.readableOn
import kotlinx.coroutines.delay

/** 设置页「搜索并跳到某个分组」的目标；seq 自增用于重复点同一项也能触发。 */
internal data class SettingsJumpTarget(
    val section: String? = null,
    val group: String? = null,
    val seq: Int = 0
)

internal val LocalSettingsJump = staticCompositionLocalOf { SettingsJumpTarget() }

/** 普通设置 / 其他设置。advanced=true 的组只在「其他设置」里渲染。 */
internal val LocalSettingsAdvanced = staticCompositionLocalOf { false }

/**
 * 设置搜索目录：只做导航，不复制控件，点结果直接展开对应分组。
 *
 * [advanced] 用来决定要不要先切到「其他设置」——搜到普通项时不该把用户
 * 从普通设置里踢出去，只有目标确实藏在其他设置里才切。
 * group 为 null 表示这个分区没有二级分组，跳到分区顶即可。
 */
private data class SettingsSearchEntry(
    val section: String,
    val group: String?,
    val label: String,
    val keywords: String,
    val advanced: Boolean = false
)

private val searchCatalog: List<SettingsSearchEntry> = listOf(
    // ---- 外观 ----
    SettingsSearchEntry("外观", "主题与自定义配色", "明暗模式 / 主题 / 主色", "深色 浅色 自动 配色 主色 次色 主题"),
    SettingsSearchEntry("外观", "界面材质与模糊", "毛玻璃 / 液态玻璃 / 模糊", "材质 模糊 玻璃 实色 背景模糊"),
    SettingsSearchEntry("外观", "背景、封面与圆角", "自定义背景 / 当前歌曲封面 / 圆角", "背景图 封面 裁剪 圆角 已替换封面 管理"),
    SettingsSearchEntry("外观", "字体与字号", "全局字体 / 字号", "字体 衬线 字号 大小"),
    SettingsSearchEntry("外观", "界面排布与列表", "列表 / 卡片 / 堆叠 / 唱片墙", "布局 行样式 列表 卡片 唱片墙"),

    // ---- 播放 ----
    SettingsSearchEntry("播放", "播放与过渡", "淡入淡出 / 无声略过 / 上栏自动隐藏", "渐变 淡入 淡出 静音 上栏"),
    SettingsSearchEntry("播放", "音效预设", "EQ / 低音 / 声场 / 限幅", "音效 均衡器 低音 环绕 声场 限幅", advanced = true),
    SettingsSearchEntry("播放", "智能 Automix", "曲尾淡出接下一首", "auto mix 过渡 淡切 切歌", advanced = true),

    // ---- 歌词 ----
    SettingsSearchEntry("歌词", "舞台与动效", "歌词舞台 / 逐词动效 / 显示模式", "舞台 预览 收藏 动效 显示模式 上下文"),
    SettingsSearchEntry("歌词", "氛围与性能", "节拍律动 / 粒子 / 光晕 / 品质", "节拍 粒子 光晕 渲染 品质 性能", advanced = true),
    SettingsSearchEntry("歌词", "多语言与导出", "翻译 / 音译 / 单曲与整库导出", "翻译 音译 罗马音 导出 lrc zip 批量", advanced = true),
    SettingsSearchEntry("歌词", "副歌与封面氛围", "副歌特效 / 封面取色 / 纵深", "副歌 取色 封面 纵深 视差", advanced = true),
    SettingsSearchEntry("歌词", "歌词时间校准", "蓝牙延迟 / 现场版 / 视频转音频", "偏移 延迟 校准 蓝牙 现场", advanced = true),
    SettingsSearchEntry("歌词", "排版细调", "行距 / 字距 / 字重 / 描边 / 纹理", "行距 字距 字重 描边 纹理", advanced = true),
    SettingsSearchEntry("歌词", "逐词发光", "拖尾 / 呼吸 / 强拍闪白", "发光 拖尾 呼吸 闪白 逐词", advanced = true),
    SettingsSearchEntry("歌词", "视觉预设", "8 个槽位 / JSON 迁移", "预设 槽位 json 导入 导出", advanced = true),
    SettingsSearchEntry("歌词", "舞台调参与专属调校", "四轴调参 / 专属字号动势辉光形态", "调参 密度 速度 镜头 形态 字号 动势 辉光", advanced = true),
    SettingsSearchEntry("歌词", "沉浸与镜头", "焦点线 / 节拍镜头 / 长音频", "焦点 镜头 长音频 播客 dj 电台", advanced = true),
    SettingsSearchEntry("歌词", "歌词视频背景", "本地 MP4 / 静音循环", "视频 mp4 背景 不透明度", advanced = true),
    SettingsSearchEntry("歌词", "中文语义断行", "标点 / 词组边界优先", "断行 换行 中文 语义", advanced = true),

    // ---- 本地曲库 ----
    SettingsSearchEntry("本地曲库", "文件夹与扫描", "授权文件夹 / 重新扫描", "本地 文件夹 扫描 saf"),
    SettingsSearchEntry("本地曲库", "扫描选项", "文件夹封面 / .junignore / 多歌手", "扫描 封面 junignore 歌手 拆分", advanced = true),
    SettingsSearchEntry("本地曲库", "歌手 / 专辑别名", "原名=新名，重扫后生效", "别名 歌手 专辑 合并", advanced = true),

    // ---- 智能与连接（整段只存在于其他设置）----
    SettingsSearchEntry("智能与连接", "AI 主题与歌词壁纸", "按歌曲生成配色 / 桌面歌词", "ai 主题 壁纸 桌面", advanced = true),
    SettingsSearchEntry("智能与连接", "局域网遥控", "同一 Wi-Fi 浏览器控制", "遥控 网页 局域网 令牌", advanced = true),
    SettingsSearchEntry("智能与连接", "设置云同步", "只同步界面与听感偏好", "云同步 备份 上传 恢复", advanced = true),

    // ---- 动效与反馈 ----
    SettingsSearchEntry("动效与反馈", "触感与界面动效", "触感 / 物理动效 / 省电", "触感 震动 动效 省电"),
    SettingsSearchEntry("动效与反馈", "低音马达震动", "跟随鼓点轻震", "低音 马达 震动 beta", advanced = true),
    SettingsSearchEntry("动效与反馈", "无障碍", "减少动效 / 晕动症友好", "无障碍 减少动效 晕动症 省电"),
    SettingsSearchEntry("动效与反馈", "诊断与帧率", "歌词层实时 FPS", "诊断 帧率 fps 监测 调试", advanced = true),

    // ---- 直接设置的分区 ----
    SettingsSearchEntry("推荐", null, "推荐探索度 / 歌单推荐区", "推荐 探索 随机 歌单"),
    SettingsSearchEntry("数据", null, "上栏排序 / 歌单来源", "排序 上栏 歌单来源 收藏"),
    SettingsSearchEntry("下载", null, "下载目录 / 下载管理", "下载 目录 管理 任务"),
    SettingsSearchEntry("关于", null, "检查更新 / 版本号", "检查更新 版本 升级 最新"),
    SettingsSearchEntry("关于", null, "通知权限", "通知 权限 推送"),
    SettingsSearchEntry("关于", null, "重看新手引导", "新手 引导 绘本 聚光灯 教程"),
    SettingsSearchEntry("关于", null, "隐私政策与撤回同意", "隐私 政策 同意 授权"),
    SettingsSearchEntry("关于", null, "联系我们", "联系 客服 邮箱 反馈"),
    SettingsSearchEntry("我的数据", null, "数据迁移 / 清空", "备份 恢复 迁移 清空 数据")
)

/**
 * 全屏设置搜索页。
 *
 * 交互与布局要点：
 *  - 打开即聚焦输入框并拉起键盘；
 *  - 整个页面套 safeDrawing padding（键盘 / 导航栏取最大值），键盘出现时结果区自动缩短，
 *    而不是被键盘盖住（这里的结果列表一定带 weight(1f) 自己滚）；
 *  - 表面、圆角、配色与设置弹层同源，玻璃模式下同样是液态玻璃，不另做一套白卡片。
 */
@Composable
internal fun SettingsSearchDialog(
    colors: JunColors,
    onDismiss: () -> Unit,
    onJump: (section: String, group: String?, advanced: Boolean) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val results = remember(query) {
        val q = query.trim()
        if (q.isEmpty()) emptyList()
        else {
            val tokens = q.split(' ', '　', '/').filter { it.isNotBlank() }
            searchCatalog.filter { e ->
                val haystack = "${e.section} ${e.group.orEmpty()} ${e.label} ${e.keywords}"
                tokens.all { haystack.contains(it, ignoreCase = true) }
            }.sortedBy { e ->
                when {
                    e.label.contains(q, ignoreCase = true) -> 0
                    e.group?.contains(q, ignoreCase = true) == true -> 1
                    e.section.contains(q, ignoreCase = true) -> 2
                    else -> 3
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        // Dialog 窗口拿到焦点需要一点时间，立刻 request 容易落空。
        delay(180)
        runCatching { focus.requestFocus() }
        delay(120)
        keyboard?.show()
    }

    val glass = isLiquidGlass
    // R13：设置内搜索和主页搜索页是同一个毛病——整页压在 SHEET 玻璃上，
    // 而 SHEET 只有 56% 染色；Dialog 又拿不到主页背板，于是"透"变成"糊到底下的设置列表"。
    // R14：地基和主页那一页共用同一套画法（[junSoftFocusPanel]）：基色画到 94.5% / 95.5%，
    // 只留 5% 上下的透光，"糊感"由画出来的顶亮/底暗 + 对角色偏提供。
    // 两块屏幕现在是同一块板子，不会再一个像纸、一个像毛玻璃；文字一律走 [junReadoutInk] 校验过的色。
    val panel = if (glass) junReadoutPanel(colors) else colors.background
    val ink = junReadoutInk(colors, panel)
    // 输入框底：玻璃下也改实色（终值确定，叠色不再叠两层半透明）；实色下沿用原来的行底色
    val chipBg = if (glass) junReadoutField(colors) else when {
        colors.isXiaomei -> colors.row
        colors.isDark -> Color(0xFF2A2A32)
        else -> Color(0xFFE8E8EC)
    }
    // 元件级柔焦：和整页同一套栈，只是透光档位更紧（小面积上透出来的东西会直接压在字底下）
    val chipSurface: Modifier = if (glass) {
        Modifier.junSoftFocusField(colors, colors.isDark)
    } else {
        Modifier.background(chipBg)
    }
    // Dialog 是独立窗口，拿不到主页那套背板取样；所以这一层的"软焦"本来就只能是画出来的那一份：
    // 基色 + 顶亮/底暗 + 内缘，一颗像素都不依赖 API 31+ 的系统模糊，nova 6 上照样成立。
    val sheetBase: Modifier = if (glass) {
        Modifier.junSoftFocusPanel(colors, colors.isDark)
    } else {
        Modifier.background(panel)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(sheetBase)
                // safeDrawing 的 bottom 是「导航栏 / 键盘」取最大值，不会把两者相加；
                // 键盘弹起时结果区自动缩短，绝不盖住搜索结果。
                .safeDrawingPadding()
        ) {
            Column(Modifier.fillMaxSize()) {
                GlassContentColumn {
                    // ---- 顶部搜索栏：结构、圆角、材质与主页 SearchScreen 完全一致 ----
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .junSurface(GlassLevel.CARD, colors, JunShape.card)
                            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .junPressable { onDismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(Modifier.size(16.dp)) {
                                val c = ink.text
                                drawLine(
                                    c, Offset(size.width * 0.8f, size.height * 0.08f),
                                    Offset(size.width * 0.22f, size.height * 0.5f),
                                    strokeWidth = size.minDimension * 0.14f, cap = StrokeCap.Round
                                )
                                drawLine(
                                    c, Offset(size.width * 0.22f, size.height * 0.5f),
                                    Offset(size.width * 0.8f, size.height * 0.92f),
                                    strokeWidth = size.minDimension * 0.14f, cap = StrokeCap.Round
                                )
                            }
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(JunShape.item)
                                .then(chipSurface),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Canvas(Modifier.size(15.dp)) {
                                    val r = size.minDimension * 0.32f
                                    val cx = size.width * 0.38f
                                    val cy = size.height * 0.38f
                                    drawCircle(
                                        color = ink.subText, radius = r,
                                        center = Offset(cx, cy), style = Stroke(width = size.minDimension * 0.12f)
                                    )
                                    drawLine(
                                        color = ink.subText,
                                        start = Offset(cx + r * 0.7f, cy + r * 0.7f),
                                        end = Offset(size.width * 0.88f, size.height * 0.88f),
                                        strokeWidth = size.minDimension * 0.12f, cap = StrokeCap.Round
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.weight(1f)) {
                                    if (query.isBlank()) {
                                        Text(
                                            "搜索设置项、功能或关键词",
                                            color = ink.subText,
                                            fontSize = 12.sp
                                        )
                                    }
                                    BasicTextField(
                                        value = query,
                                        onValueChange = { query = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(focus),
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = ink.text,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        cursorBrush = SolidColor(ink.primary),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
                                    )
                                }
                                if (query.isNotEmpty()) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        Modifier
                                            .size(18.dp)
                                            .junPressable {
                                                query = ""
                                                runCatching { focus.requestFocus() }
                                            }
                                            .clip(JunShape.chip)
                                            .background(ink.subText.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Canvas(Modifier.size(7.dp)) {
                                            drawLine(
                                                ink.subText,
                                                Offset(size.width * 0.15f, size.height * 0.15f),
                                                Offset(size.width * 0.85f, size.height * 0.85f),
                                                strokeWidth = size.minDimension * 0.18f,
                                                cap = StrokeCap.Round
                                            )
                                            drawLine(
                                                ink.subText,
                                                Offset(size.width * 0.85f, size.height * 0.15f),
                                                Offset(size.width * 0.15f, size.height * 0.85f),
                                                strokeWidth = size.minDimension * 0.18f,
                                                cap = StrokeCap.Round
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "取消",
                            color = ink.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.junPressable { onDismiss() }
                        )
                    }

                    if (query.isBlank()) {
                        SearchSuggestions(ink) { word ->
                            query = word
                            runCatching { focus.requestFocus() }
                        }
                    } else if (results.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "没有找到「$query」",
                                    color = ink.text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    "换个更短的关键词试试，比如「断行」「封面」「遥控」",
                                    color = ink.subText,
                                    fontSize = 10.5.sp
                                )
                            }
                        }
                    } else {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "找到 ${results.size} 项",
                                color = ink.subText,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "点按直达",
                                // 原来在这里又乘了一次 0.75：次级色本身已经按 4.5:1 校过，
                                // 再压一次就掉回 3:1 上下，正是"这行字灰得看不清"的那种。
                                color = ink.subText,
                                fontSize = 10.sp
                            )
                        }
                        LazyColumn(
                            Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                results,
                                key = { "${it.section}|${it.group}|${it.label}" }
                            ) { entry ->
                                SearchResultRow(
                                    entry = entry,
                                    query = query,
                                    colors = ink,
                                    onClick = {
                                        Haptics.tap()
                                        onJump(entry.section, entry.group, entry.advanced)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 空查询时的常用入口。点一下填词并保持焦点，键盘不会收掉。 */
@Composable
private fun ColumnScope.SearchSuggestions(
    colors: JunColors,
    onPick: (String) -> Unit
) {
    val hot = listOf("主题", "歌词", "音效", "封面", "下载", "云同步")
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Text(
            "常用搜索",
            color = colors.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.height(9.dp))
        hot.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { word ->
                    Box(
                        Modifier
                            .weight(1f)
                            .junPressable(feedback = PressFeedback.NONE) { onPick(word) }
                            .junSurface(GlassLevel.CHIP, colors, JunShape.item)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            word,
                            color = colors.text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "舞台调校、渲染性能与实验项也能直接搜到；点结果会展开对应分组并停在那一项。",
            color = colors.subText,
            fontSize = 10.5.sp,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun SearchResultRow(
    entry: SettingsSearchEntry,
    query: String,
    colors: JunColors,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .junPressableRow(feedback = PressFeedback.NONE) { onClick() }
            // 与主页搜索结果行同款：每行一块薄玻璃，叠在整页 SHEET 玻璃上。
            .junSurface(GlassLevel.ROW, colors, JunShape.item)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                // 命中词标色按**行底**折算，而不是按 surface：这一行的真实底是
                // junSurface(ROW)（玻璃下≈面板+8% 染色，实色下就是 colors.row），
                // 之前按 surface 折算出来的浅色高亮压在这两种底上都不够 4.5:1。
                highlightMatch(entry.label, query, colors.primary.readableOn(colors.row, 4.5f)),
                color = colors.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${entry.section} · ${entry.group ?: "直接设置"}",
                    color = colors.subText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (entry.advanced) {
                    Spacer(Modifier.width(6.dp))
                    AdvancedBadge(colors)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.size(14.dp)) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.12f
            drawLine(
                colors.subText,
                Offset(w * 0.36f, h * 0.2f),
                Offset(w * 0.66f, h * 0.5f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                colors.subText,
                Offset(w * 0.66f, h * 0.5f),
                Offset(w * 0.36f, h * 0.8f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/** 「进阶」小徽章：总设置下不再分 tab，这个徽章只用来提示"这组偏调校"。 */
@Composable
private fun AdvancedBadge(colors: JunColors) {
    Text(
        "进阶",
        color = colors.primary.readableOn(colors.row, 4.5f),
        fontSize = 8.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .clip(JunShape.chip)
            .background(colors.primary.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** 把命中关键词标成主题色，长目录里一眼能看出为什么匹配。 */
private fun highlightMatch(text: String, query: String, accent: Color): AnnotatedString {
    val q = query.trim()
    val index = if (q.isEmpty()) -1 else text.indexOf(q, ignoreCase = true)
    if (index < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(
            SpanStyle(color = accent, fontWeight = FontWeight.Black),
            index,
            index + q.length
        )
    }
}
