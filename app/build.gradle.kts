import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 正式签名凭据：从项目根的 keystore.properties 读取（该文件与 .jks 都不入库）。
// 缺失时回落 debug 签名，保证别人 clone 下来仍能编译出可安装的包。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { stream -> load(stream) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")
val hasReleaseKeystore = releaseStoreFile != null && rootProject.file(releaseStoreFile).exists()

android {
    namespace = "com.jun.musiccloud"
    compileSdk = 35

    // 6.9.5：arm64 分包 + universal 全量包并存。用户红米 Turbo 装包报「解析包异常」，
    // 最常见是传输截包/安装器挑包；universal 包多一条兜底安装路径。
    // 项目原生库只有 arm64（quickjs / androidx.graphics），所以 universal 实际也是
    // arm64 能跑的完整包 —— 作用是给安装失败的场景一个不同文件名的重试包。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.jun.musiccloud"
        minSdk = 24
        targetSdk = 35
        // R13：6.1.0 / versionCode 62。
        // 为什么必须 62：线上 6.0.0 已经是 61，同签名覆盖安装要求新包版本码严格大于已装值，
        // 否则手机判定「已是最新」，这一轮修的搜索单曲空白、顺序播放跳歌、下载计数为 0
        // 和新加的分享/导入口令、两款新字体，一个都到不了用户手上。
        // 为什么 versionName 是 6.1.0 而不是继续 6.0.0：这一轮不只是修 bug，还进了两个
        // 用户能看见的新功能（歌单口令分享/导入、两款新字体），按「大版本 6.0、功能小版本 .1」
        // 走，公告里也能把「新增」和「修复」分干净。若你坚持对外仍叫 6.0，只改 versionName
        // 字符串即可，62 这个数字不能动。
        // R14：6.1.1 / versionCode 63。这一轮全是「修 + 重排」（分享页闪、搜索横扫卡住、
        // 我的主页、默认顺序播放、柔焦底色），没有再进新功能，所以只动最后一位：6.1.0 → 6.1.1。
        // 63 同时是本轮公告的门槛（GuidePrefs.WHATS_NEW_CODE = WhatsNewR14.CODE），
        // 两处必须一起动：只抬版本不抬门槛，看过 6.1.0 公告的人收不到这一轮。
        // R15：6.2 / versionCode 64。这一轮既有用户点名的两件功能（网易云新歌单当场可见、
        // 多选之后能批量下载），也有一批安全性修复，所以按「功能小版本」走：6.1.1 → 6.2。
        // 64 同时是本轮公告的门槛（GuidePrefs.WHATS_NEW_CODE = WhatsNewR15.CODE），两处一起动。
        // 63 是线上已发布的那一版；64 必须严格大于它，同签名才能覆盖安装、数据保留。
        // 本轮未经你允许不发版：线上版本号仍是 63，64 的包只作为交付物给你，
        // 官网 versions 表、强制更新开关、/api/dl 发布位一律没动。
        // R16：6.3 / versionCode 65。这一轮用户点名的是「修 + 加」各占一半：
        //   - 修：重装后设置不同步、换号后我喜欢串号、App 冷启动慢、后台崩溃日志处理不了；
        //   - 加：下载可选 MP3 / 无损 FLAC、整体过渡动画快慢、后台数据中心一键迁移。
        // 按「功能小版本」走：6.2 → 6.3；64 是线上已发布的那一版，65 必须严格大于它。
        // 65 同时是本轮公告的门槛（GuidePrefs.WHATS_NEW_CODE = WhatsNewR16.CODE），两处一起动。
        // 本轮仍未获授权发版：线上版本号仍是 64，65 的包只作为交付物给出。
        // R17：6.3.9 / versionCode 66。这一轮用户点名的全是 bug：
        //   - 点歌/切歌要等好几秒才出声（队列整条 cross-process 灌进去，缓不过来）；
        //   - 热门歌手点不开主页（按名字反查不到）、还要排除周杰伦、按热度取人；
        //   - 每日新歌只有四五首；排行榜卡上多一层字、要玻璃感；
        //   - 单曲专辑点开是个"只有一首歌"的列表页；下拉刷新当天只生效一次。
        // 66 同时是本轮公告的门槛（GuidePrefs.WHATS_NEW_CODE = WhatsNewR17.CODE）。
        // R18：6.4 / versionCode 67。这一轮用户点名的两件事 + 一件我上轮提的建议：
        //   - 每日新歌只显示 3 首，点「更多」原地展开（10 行铺满会挤掉下面几块）；
        //   - 很多歌点开要等很久才出声 —— 解析链路的源顺序错了（详见 OnlineMusicApi.resolveAudio）；
        //   - 把「播放库四层结构」做成可执行的整理向导（体检 + 一键合并）。
        // 67 同时是本轮公告的门槛（GuidePrefs.WHATS_NEW_CODE = WhatsNewR16.CODE）。
        // R19：6.5 / versionCode 68。这一轮用户点名两件 + 上轮建议 4 条全落地：
        //   - 首页整体不居中（分区 16dp 叠在外层 22dp 上 = 38dp，网格却是 16dp + 2 列）；
        //   - 建议①双快源并行竞速（meting 单点，挂掉就落回 1.9 秒的 GD）；
        //   - 建议②冷启动首屏预解析（首屏 6 首，进程内一次，有网才做）；
        //   - 建议③网络切换让直链缓存失效（Wi-Fi 切 4G 报"无版权"的抽风）；
        //   - 建议⑤歌单页整理提醒（碎到 5 个才点一颗小圆点，不弹窗）。
        // 建议④（首页分区可排序可隐藏）是产品级改动，本轮不塞进来，留作下一轮。
        // 68 同时是本轮公告的门槛（GuidePrefs.WHATS_NEW_CODE = WhatsNewR19.CODE）。
        // R20：6.5.1 / versionCode 69。小轮：只动用户点名的一处——
        //   - 底部「全部音乐」歌曲行恢复改前的居中宽度（36dp 对称，上轮误改成 16dp）；
        //   - 底部列表加滚动渐入特效（每行首次滚进屏幕时浮上来 + 淡入，只播一次）。
        // 上面的分区保持 6.5 的 16dp 对齐不动。公告门槛不抬（69 不是内容版本，
        // 弹公告反而吵），6.5 的 WhatsNewR19.CODE = 68 继续生效。
        // R21：6.6 / versionCode 70。用户点名三件：
        //   - 对齐再改：外层恢复 22dp、分区边距归零 → 全部内容同一条线（与搜索框对齐），
        //     底部歌曲行卡片边缘也回到 22dp（= 6.4 原样）；
        //   - 封面滚动中不加载、松手才蹦（6.3.2 的滚动门控退化）→ 改回随滚随出；
        //   - 整体卡顿：给 Song/Playlist 标 @Immutable（进度每 500ms 刷 state 时
        //     整个可见列表每行都在重组，这是最大单一来源）+ 歌单名 remember。
        // 公告门槛不抬（修补+性能轮）。
        // R22：6.7 / versionCode 71。用户点名四件：整体流畅性 + 抽卡误触阈值再抬、
        // 动效风格可切换（果冻/顺滑/超弹）、后台强制更新豁免（设备/账号）、
        // 公告里加 6.2→6.7 人话历史总结且展开折叠提速；官网更新日志折叠精简。
        // R23：6.3.1 / versionCode 75。只修不加，全部来自真实故障：
        //   ① 播放线程 OOM（16G 旗舰 256MB 堆打满）→ 封面缓存改按字节预算 + 大图解码闸门；
        //   ② 缓存写满后连环故障（满→重试→"网络错误"→全打不开）→ 识别缓存异常自动重建 + 真重试；
        //   ③ 缓存上限恢复可调（128MB~4GB + 自动）；④ 滚动中误触；⑤ 掉帧降级更早触发。
        // R24：6.5.0 / versionCode 81。用户点名重排「我的主页」：
        //   ① 顶部收成一张头卡（头像/昵称/身份/加入时间 + 四格数据带）；
        //   ② 段位与听歌数据合成一张，口径切换由两个 chip 改成分段控件；
        //   ③ 听歌报告入口加近 14 天迷你柱状图（真实数据，不是装饰）；
        //   ④ 账号入口收到头卡右上角，底部不再单独占一张卡。
        // R25：6.5.1 / versionCode 82。用户点名「周杰伦这类歌全是 0:00、只提示无版权、
        //   必须手动切」→ 收到 204 直接自动跳下一首；连跳上限 8 首；提示合并成一句；
        //   整张歌单都没版权时给一次「搜其他版本」出口。
        // R26：6.6.1 / versionCode 87。用户原话「为什么启用都启用不了？根本启用不了，
        // 而且那音乐还跟之前的一样」—— 上一版音源插件的两个真 bug：
        //  ① 只存一个 active 槽位，点第二个「启用」把第一个踢掉，看着像没反应；
        //  ② 解析时把网易云 id 当 QQ songmid 用，取到的是别人的歌（串歌根因）。
        // 这版改成多脚本并存 + 先按歌名搜本源 ID + 同名同人校验 + 实测音频字节。
        // R27：6.6.2 / versionCode 88。用户原话「还是听不了，还显示没版权，还显示没有可用平台」。
        // 复查发现 6.6.1 的测试是在 Node 里跑的，而 App 用的是 QuickJS —— 两个引擎。
        // Node 能跑通不代表 QuickJS 能跑通，失败还被吞成"没有可用平台"。
        // 本版：内置 5 个实测可用脚本默认全开、错误不再吞掉（带类型和行号）、
        // 加「自检全部脚本」让用户直接在真引擎里看结论、并发打所有源、老 API 链路整体关闭。
        // R28：6.6.3 / versionCode 89。用户原话「一自检它就闪退」「一停用就闪退」「所有歌全部听不了」。
        // 三个都是我自己引入的严重缺陷：
        //  ① 自检在主宿主上跑，和播放线程抢同一个 QuickJS 实例 → native abort；
        //  ② shutdown() 后 worker 已关，后续 execute 抛 RejectedExecutionException；
        //  ③ 停用后宿主零脚本、老链路又被我关掉 → 全部歌听不了。
        // 本版：影子沙箱自检、安全提交、原子换血、内存/栈上限、至少保留一个音源。
        // R29：6.6.4 / versionCode 90。用户原话「人家那个插件导进去秒能听，你这个一个都听不了」。
        //
        // **这一版才是真正的根因**：我把 LX 请求喂错了形状。
        // 官方 preload 的入口是 handleRequest = ({ requestKey, data }) => {...}，
        // 它解构出 data 这一层；而我发的是 { requestKey, source, action, info }（平铺），
        // 于是脚本侧拿到 data === undefined，第一行就抛
        //   TypeError: cannot read property 'source' of undefined
        // 结果：请求从头到尾没送达过脚本，每次取地址都白等满 15 秒超时。
        // 连带第二个 bug：脚本成功后回的是 { status, result: { data: { url } } }，
        // 我只认 { url }，所以就算脚本算出了地址也读不到。
        //
        // 为什么 6.6.1~6.6.3 一直没发现：之前的验证全在 Node 里用自己写的假 lx 对象跑，
        // 假对象的 on('request') 直接把参数原样给我，形状不对也照样能跑通。
        // 本版改用**真实 QuickJS + 官方 lx-preload.js** 在桌面跑通验证（见验证记录）。
        //
        // 顺带修：音质逐级降级（flac 拿不到会退 320k/128k，不再整首放不出）、
        // 失败原因逐条写到设置页（"酷我:搜不到这首歌" 而不是笼统一句），
        // musicInfo 补上官方嵌套 meta 层。
        // R30：6.6.5 / versionCode 91。用户点名四件事：
        //  ① 起播慢、切歌慢；② 知我/樱花草 只响 6 秒就报无版权；③ 下载没有 FLAC；④ 整体观感慢。
        //
        // 逐个查到的根因：
        //  ① 探测是 GET 96KB，紧接着代理又 GET 一次同一地址 → 每首歌多一趟往返。
        //     改成 HEAD 优先（实测 43ms → 6ms），再配合搜索缓存（600~1400ms 省掉）。
        //  ② 磁盘缓存键只有 `song:<id>#<档位>`，不含解析代次。脚本直链会过期（vkey/Tim），
        //     代理已重新解析，但播放器 URL 没变 → CacheDataSource 命中上次写了一半的残片，
        //     进度条走满、声音几秒就停。键里加 `@<代次>`，残片再也顶不掉新地址。
        //  ③ preferFormat 在脚本链路里从来没生效，下载 FLAC 实际只问了 flac 档位；
        //     而 QQ 的 flac 档位常常只回 mp3。现在下载无损从 flac24bit 起、拒收假无损。
        //  ④ 代理转发固定用 music.163.com 做 Referer，QQ/咪咕 CDN 校验 Referer 直接 403。
        //
        // 另外：非 QQ 源不再拿 QQ 的 mid 去问（那就是最早的串歌），没有自己的 ID 就不放。
        // R31：6.6.6 / versionCode 92。用户这轮报的六件事，逐条对根因：
        //  ① 「知我」等歌显示无版权被跳过 —— QQ 上这首歌署名是「谭渊」、标题《知我 (寻我)》，
        //     歌名+歌手双匹配永远过不了。新增「时长认歌」：歌名去括号后完全相等 + 时长差 ≤6s。
        //  ② 点歌要等好几秒 —— prefetch 一直只传 ID，脚本按名搜索时拿不到歌名，**预取从没成功过**，
        //     还占着 in-flight 让播放器白等。现在列表装配时登记歌名歌手，预取真正生效；
        //     搜索缓存也从 30 分钟延长到 7 天并落盘，听过的歌第二次起等于老链路的"一次请求"。
        //  ③ 分不清真音质假音质 —— 按「文件大小 ÷ 时长」算真实码率定档，拿不到就如实写 MP3，
        //     不再把"请求了 flac 却回 mp3"标成 320k。
        //  ④ 后台说离线、账号页说在线 —— 账号页改为显示服务端 last_seen（同一口径）。
        //  ⑤ 改昵称只在本机生效 —— 新增 PATCH /api/profile，登录态改名同步服务端；
        //     登录/重登改用服务端 nickname，不再拿 username 顶替。
        //  ⑥ 分享码太长 —— 新增歌单短码（服务端存正文，聊天只发 JMS:8 位）。
        // R32：6.6.7 / versionCode 93。用户这轮又报四件事 + 一份运维建议，逐条对根因：
        //  ① 能听的歌，进度条一拖到中间/末尾就暂停、报"无版权已跳过"——
        //     **残片识别的致命误伤**：ExoPlayer 拖动进度条发 Range 请求，上游回 206 时
        //     Content-Length 只是剩余段的长度，拖到末尾剩余 <400KB 恰好落进残片阈值
        //     → 被当成试听片段判死 → 记入 unplayable → 跳歌。现在只按「整首歌总大小」
        //     判残片（206 从 Content-Range 取总长；带 Range 的 200 不判）。
        //     顺带：解析地址过期（10 分钟缓存但 CDN 更早失效）导致的 seek 403，
        //     现在会作废旧地址重解析一次再重试，不再直接判失败。
        //  ② 无缝衔接 —— 用 Media3 官方 PreloadConfiguration（1.5.0）：
        //     当前曲播放时就预加载下一首开头 8 秒（加载+解码器初始化全做完），
        //     配合已有的地址预解析+连接预热，切歌间隙压到 0。
        //  ③ 分享短码失败静默回退长口令 —— 失败现在明确写原因（服务器没部署/断网）
        //     并给「重新生成短码」重试按钮。
        //  ④ 改昵称服务器没同步成功时静默丢弃 —— 失败记入 pending_nickname，
        //     下次启动/登录自动补传（与头像补传同一模式），服务器恢复后名字必同步。
        // R33：6.6.8 / versionCode 94。服务器已由开发者本人直接部署（6.6.7 补丁已上线），
        // 这一轮的四件事：
        //  ① 《反季节》（博仔）"无版权已跳过" —— 根因：内置搜索只支持 QQ 源，而 QQ 上
        //     这首歌只有"网络AI"传的 22 秒 AI 垃圾片段；网易云明明有正版（博仔LIT·186s）
        //     但 wy 源连搜都不搜。现在 builtinSearch 补上 wy（网易云公开搜索，实测免登录），
        //     QQ 搜索结果也带上时长供"时长认歌"拒掉垃圾片段。
        //  ② 下载：选 FLAC 但所有源都没有无损时，**自动回退 MP3** 落盘并在通知里说明，
        //     不再报"下载失败"让用户去找设置；默认档位仍是"跟音质走"。
        //  ③ 下载管理页"全选"按钮原来只进选择模式、什么都没选（点着没反应）——
        //     现在改名"批量管理"并**进入即全选**，选择模式里提供 全选/取消全选 切换。
        //  ④ 榜单/歌单详情提速：playlistInfo 先走旧接口 /playlist/detail
        //     （实测一次请求返回全量 tracks），够数直接返回；不再永远 v6+4 批 /song/detail。
        // R32：6.6.9 / versionCode 95。用户这轮报的八个问题（含"不能再出现没效果的功能"）：
        //  ① 拖进度条 → 永久卡死 → 报无版权 → 整单连跳。根因链（四段）：
        //     a. 解析地址只缓存 10 分钟，过期后重新解析；音源恰好限流就整首判死。
        //        现在过期地址保留 2 小时作"陈旧兜底"（CDN 直链真实有效期本就几小时）。
        //     b. putAudio 在"地址变化"时清这首歌的磁盘分片 —— 而这一步往往发生在
        //        播放器的 CacheDataSource 正握着该缓存键等数据时，等于抽掉脚下的地板，
        //        报 span not found → 被判缓存故障 → 清空全部缓存 → 重试 → 更糟。
        //        已删除该清除（缓存键早已含解析代次，旧分片顶不掉新内容）。
        //     c. 解析失败回 204：属 2xx，播放器当成功吞掉 → **无声卡死**（用户看到
        //        "暂停不动、进度一直是 0"）。统一改回 502，错误立刻冒泡、立刻可跳。
        //     d. 播放中出错不再直接判无版权：先原地重试一次（清失败记账 + prepare），
        //        仍失败才跳。跳歌不可逆，重试只花半秒。
        //  ② 改昵称仍不同步：服务端我已部署并端到端验证通过（改→重登→名字保留）。
        //     客户端补一个启动竞态修复：有 pending 昵称时，profile 拉取不得用旧名覆盖。
        //  ③ 热门歌手一直不动：新增"本机搜索热度"统计（搜歌手 tab / 搜歌名时按
        //     结果中反复出现的歌手计数），热门歌手 = 搜索热度前 6 → 官方热榜补满
        //     → 仍不足则随机补。落盘、有上限、纯本地。
        //  ④ 用户时长排行榜加载慢：服务端缓存键不再混入 me 标志，改为按 range 缓存
        //     完整行（冷路径 1.6s 只走一次）；客户端落盘上次榜单快照，打开即出画面。
        //  ⑤ 拖进度条报"播放出错"：与 ① 同源，504/上游错误不再直接判死。
        //  ⑥ 歌词快慢不一：MediaController.currentPosition 是跨进程 500ms 级快照，
        //     拿它当逐帧时钟必然一顿一顿。改为"采样锚点 + SystemClock 外推"的平滑时钟，
        //     当前行判定也改用平滑值，不再晚半拍。
        //  ⑦ 播放列表不记得来源：playFromLiked / playLikedAll 先设 onlyLiked，
        //     紧接着 playList 默认把 onlyLiked 清掉 —— 自己抹掉自己。改为保留上下文。
        //  ⑧ 歌单/榜单不能整单收藏：新增"存到我的歌单"（含新建并加入），
        //     按 Song 对象落库，不再因"不在本地曲库"静默丢歌。
        // R39：6.8.0 / versionCode 101。这一版做的是**后路体系**，不是继续调参：
        //   1. 本地优先：下载完成即登记索引（含升级前的老文件扫描回填），
        //      代理在发任何网络请求之前先查本地 —— 源全挂/断网也能播；
        //   2. 自建曲库：服务端 library_tracks 表 + 上传/清单/Range 直链，
        //      客户端清单本地缓存、点歌纯内存匹配；
        //   3. 源健康上报：解析失败与"降级救回"上报到服务器，后台看板可看；
        //   4. 远程音源开关：flags.sources 下发，某个源挂了后台一键关，不用发版。
        //
        // R43：6.9.1 / versionCode 106。用户点名三件事：
        //   1. 总后台 admin.均.top 集成「音乐后台」（服务端侧，见 R43 服务端）；
        //   2. 全局加载反馈条 GlobalLoadBar：6.9.0 的反馈只在播放页可见，
        //      列表/搜索/榜单里点歌后的几秒是屏幕死寂期——这条只要在取源
        //      就贴底显示，重试按钮同款；进播放页则交给卡片自带的那条。
        //   3. 「我的下载」专听：下载管理弹层加「播放全部 / 随机播放」，
        //      点已完成行从该首开播；playDownloaded 用 file:// 直读本机文件、
        //      songId 留空不走解析——飞行模式也能完整听。
        //
        // R42：6.9.0 / versionCode 105。用户要"加载动画 + 讲清楚运行逻辑"：
        //   1. 播放卡顶部加载反馈条：取源不定进度 → 缓冲真实百分比（Media3
        //      bufferedPercentage），文字三段变化（正在取源→切换备用→从XX缓冲），
        //      >5 秒出现"重试"按钮，不再无限干等。
        //   2. 加载条尾部的"?"：点开弹窗讲四层取源逻辑（本地→曲库→QQ直连→脚本），
        //      解释为什么听过越多的歌秒开越快——放慢的地方不显眼但找得到。
        //   3. OnlineMusicApi.lastProviderName：解析成功时记录通道名，
        //      缓冲文案能说清"正在从 QQ音乐/备用音源/网易直连 缓冲"。
        //
        // R41：6.8.3 / versionCode 104。用户实测反馈"现在这个音源加载慢"。
        // 根因：网关要多走一跳海外服务器；而 App 内置 QQ 直连是直连腾讯的，本来就快。
        // 本版把 2026-11-07 全量实测后仅剩的活源全部加进竞速池：
        //   · qqExtraResolve：helloworld(103.79.184.97) + vkeys.cn 两条备用换链，
        //     与 qqBuiltinResolve 共用 songmid 搜索缓存，串行兜底各 3.5s 超时；
        //   · 竞速循环加第四路 extraFuture，谁先给地址用谁，慢的自动淘汰；
        //   · hybridPool 6→8 线程：每歌最多 4 路并发 × 当前曲+预取 = 8 不排队。
        // cyapi 哪天变慢/变挂，备用路自动顶上，用户无感、不用发版。
        //
        // R40：6.8.2 / versionCode 103。用户点名要"跟他们同款的 JS 音源"，本版补上：
        //   1. 服务器新增 /lx 网关（LX 协议同款契约）：
        //      GET /lx/url/:source/:songId/:quality，X-Request-Key 校验；
        //      顺序 = 曲库（platform_id 命中直接回自己文件）→ QQ 直连（搜索换 mid → cyapi）
        //      → 按平台转发上游（tx→cyapi / wy→cenguigui / kw→kw-api）。
        //      网关的价值：上游加/换/死只改服务器，App 与落雪里的 JS 永不用动。
        //   2. 新增内置 jun-source.js（BUILTINS 第 0 位 + 单独播种标记）：
        //      结构与 huibq 完全同款，API_URL 指向自己域名；也能导入落雪使用。
        //   3. 播种特殊处理：老用户 KEY_SEEDED 已置位不会重跑，jun-source 用独立
        //      标记强制铺进目录并启用——"主音源必须装完就有"。
        //
        // R39：6.8.1 / versionCode 102。用户一句「你不是说给我搞一个音源吗作为主音源吗」
        //   把问题点破了：6.8.0 的曲库建好了，但是**空的**，只能手动传，等于没有主音源。
        //   而"能解析任何歌的自有音源"只有两条路——买版权（不做）或破解协议（不做），
        //   于是这一版让曲库自己长大：
        //   1. 曲库自动回填：一首歌在代理里真的拿到音频（整首 200，不是 seek 的 206）之后，
        //      后台用同一个直链自己下一份，POST 到自己的服务器落盘，并立刻进内存清单——
        //      下次点这首歌直接命中第二层，一个第三方都不碰；
        //   2. 硬约束：只在 Wi-Fi、单线程串行、起播 15 秒后才开始、每天 300MB 上限、
        //      超过 30MB 的无损不存、小于 400KB 的残片不存、连续失败 3 次冷却 30 分钟，
        //      任何失败都静默（这是增值功能，不能污染播放链路）；
        //   3. 设置页「我的云端曲库」：显示已备份 N 首 / X MB，可一键关闭；
        //   4. 顺带修掉服务端一个我自己引入的回归：改昵称接口被压成只剩 PATCH，
        //      Android 不支持 PATCH 会让"改昵称不生效"复发，已恢复三路挂同一处理器。
        //
        // R38：6.7.4 / versionCode 100。**紧急修复 6.7.3 "一个歌都听不了"**。
        //   实测三条公共 API 全挂（meting 空响应 / GD 503 / xfabe 404），
        //   6.7.3 又把脚本预算加到 8 秒还叠三级重试 → 最坏 24 秒，超过播放器 25 秒超时 → 全线失败。
        //   本版：
        //   1. 新增"内置 QQ 直连"通道（QQ 搜索 songmid → cyapi 换直链，实测 0.4 秒、支持 Range），
        //      与脚本/API 三路并行竞速，不再依赖任何单一源；
        //   2. 阶梯砍回两档 + 4 秒时限，脚本预算 8→5 秒，超时不取消任务（后台跑完落缓存）；
        //   3. 脚本并发闸 2 + 连续 4 次失败熔断 20 秒，防止"越点越堵"的雪崩；
        //   4. 预取从 4 首/3 首砍到 2 首，没出声的重试 2→1 次。
        //
        // R37：6.7.3 / versionCode 99。用户这轮的核心诉求：**"有些歌以前能听，现在没版权"**
        //   + 要"万全的办法"。这轮不再调参，改的是"失败"的定义：
        //   1. 失败阶梯：当前音质 → 降档强制重试 → 直链兜底，三档都空才算真没有；
        //   2. 负缓存全面缩短：脚本"没搜到" 2min→12s、整链失败 60s→15s、跳过黑名单 30min→3min；
        //   3. 脚本预算 4s→8s（4 秒会把慢源误判成没版权，这是"以前能听"变"没版权"的直接原因）；
        //   4. 没出声的歌也先重试 2 次（换源）再跳，不再一次失败就弹"无版权已跳过"；
        //   5. 重试时必须把脚本层负缓存一起清掉，否则重试秒回 null（"点了重试还是没用"）。
        //
        // R36：6.7.2 / versionCode 98。用户这轮只报一件事：**起播/切歌/搜索点歌太慢**。
        //   根因是三层串行排队，全部拆掉：
        //   1. 点歌走预取队列（6 个线程被前一批预取占满）→ 改独立线程 prefetchNow，点下去就解析；
        //   2. VIP 歌 API 主源 2.5s 超时后还串行等备用源 2.5s → 去掉备用源，1.5s 快速失败交给并行脚本；
        //   3. 起播前还要同步探测文件大小（0.7s）→ 改后台探测，起播零等待；
        //   另外：起播缓冲 800→350ms、脚本预算 6→4s、resolveOnce 等待 8→4s、搜索结果前 3 首预解析。
        //
        // R35：6.7.1 / versionCode 97。用户这轮四件事（全部是"6.7.0 根本没修好"）：
        //   1. 拖进度条必停必失败 → 代理区分 seek 与整首请求，seek 不判残片、
        //      上游忽略 Range 时自己补 206；播放中出错不再判无版权，只原地续播；
        //   2. 点歌/切歌慢 → API 与脚本真竞速（谁先好谁先响）、探测 1.6s→0.7s、脚本预算 12s→6s；
        //   3. 下载全失败 → Referer 按域名给（原来一律给网易云，QQ/咪咕 403）；
        //   4. 通知设置找不到 → 从「关于」折叠区提到设置页顶部，开启当场弹一条。
        //
        // R34：6.7.0 / versionCode 96。用户这轮七件事：
        //  ① 点任何歌都要等很久才播 —— 6.6.x「有脚本就只走脚本」，而脚本要挨个平台
        //     搜索，600~3000ms；改成 **API 快链与脚本链并行竞速**（用户明确要求"优先 API"），
        //     热门歌走 meting 0.3s 出声，API 拿不到的（周杰伦那类）脚本结果顶上。
        //  ② 后台回来点什么都"没版权"、整张歌单连跳 —— 三重原因：
        //     · 进程被回收后脚本还没装载完，hasAnySource() 为 false → 直接判没音源。
        //       新增 LxScriptHost.awaitReady(2.5s) 等脚本就绪；
        //     · 上一轮的"不可播"判定（30 分钟 / 5 分钟 / 2~60 分钟）全部还在，
        //       回来后被继承成无版权 → onUiResumed 统一清一次（节流 20s）；
        //     · 连跳满 3 首就停在那里，缓存仍全是失败 → 停下前先清失败记忆。
        //  ③ 拖进度条后停顿、时长时短 —— LoadControl 的 minBuffer 只有 800ms，
        //     拖一下缓冲瞬间见底。改为 15s 常规 / 60s 上限，起播门槛仍 0.8s。
        //  ④ 改昵称 100% 不生效 —— **Android 的 HttpURLConnection 不支持 PATCH**，
        //     setRequestMethod("PATCH") 直接抛 ProtocolException，被 catch 吞成 null，
        //     请求根本没发出去（所以服务端 users.nickname 一条都没变过）。
        //     客户端改走 POST /api/profile/update，服务端新增等价路由；失败原因原文提示。
        //  ⑤ 定时提醒（新功能）：原生通知（图标/标题/内容）、自动建通道、
        //     首次开启申请通知权限、间隔 5 分钟~6 小时可调、前台服务后台常驻、
        //     可开关、点通知回到 App；开机恢复 + 15 分钟看门狗防被 ROM 清掉。
        // R44：6.9.2 / versionCode 107。用户实测反馈四个 bug，本轮全修：
        //   1. 播放界面没有加载反馈条：6.9.0 只接到了纵向排布的播放页，
        //      卡片横滑/堆叠布局的 DeckCard 没接——补上（含"?"说明与重试）；
        //      唱片墙布局没有播放页，全局反馈条也放开到它。
        //   2. 后台管理不了前台音源：新增"下发音源"——后台粘贴 LX 脚本存
        //      app_config.dsources，客户端冷启动拉 /api/library/sources/remote
        //      自动装/启/删（SourcePluginStore.syncRemoteSources）；
        //      monitor 页与总后台 admin.均.top 均可管理。
        //   3. 听歌排行榜加载慢：服务端补 (type,user_id,created_at) 索引 +
        //      30 秒后台预热三档榜单，用户请求永远命中热缓存（<50ms）。
        //   4. 300MB 每日回填额度烧太快：改为「听完整首才回填」——
        //      代理只记候选（LibraryBackfill.offer），播放器自然播完
        //      （STATE_ENDED → markListenedThrough）才真正下载上传；
        //      切走的歌不花额度，300MB ≈ 每天 30 首。
        // R45：6.9.3 / versionCode 108。用户八点反馈：
        //   1. 播放页加载条（横向/堆叠/唱片墙全接上，并常驻监听二次缓冲）
        //   2. 后台推送通知到手机通知栏（服务端 push_messages + 客户端轮询）
        //   3. 切后台回来进度不重置（落盘 + 首帧先铺旧进度 + 恢复点锚定）
        //   4. 改昵称真正同步（清榜单缓存 + 放宽限流 + 状态提示）
        //   5. 去掉旧引导，改成「新功能与更新记录」清单
        //   6. 分享短码改 Jun 前缀，剪贴板自动识别短码
        //   7. 修 7 条线上崩溃（java.time / lx-net 线程池 / 数组越界 / OOM）
        // R46：6.9.4 / versionCode 109。用户反馈"播放界面的进度条（加载条）
        //   一直被封面挡住，播放不出来时屏幕上什么都没有"：
        //   根因不是没画，是**画在封面底下**——加载反馈条是 PlayerCard 里 Box 的
        //   第一个子节点，封面 fillMaxWidth 从同一基线往下铺，绘制顺序在后，
        //   整条反馈被压住了。修法三件：
        //   1. 节点挪到最后 + zIndex(50)：绘制顺序上永远在最前；
        //   2. 换成不透明实底胶囊条（描边 + 阴影）：压在杂色封面上也读得清，
        //      不再是"透明细线"；位置也上移到卡片上边距带里，基本不压封面；
        //   3. 横屏卡片模式（LandscapeDeckPlayer）此前压根没接加载反馈，补上；
        //      "?"说明弹窗收到 LoadHelpDialog 一处，三端共用。
        // R47：对外版本 6.4 / versionCode 115。
        //   官网（api.example.invalid）上一条公开版本是 6.3 / 74，这里对外只放 6.4 一个号，
        //   内部的 6.4 → 6.9.9 全部改动合并进这一版，更新公告与「新功能说明」也按 6.4 归组。
        //   versionCode 取 115 而不是 75：75 在 R23（6.3.1）用过；115 既能覆盖 74 的老用户
        //   （74 → 115 正常升级），也能覆盖装过 6.9.x 内测包的机器（114 → 115），不用卸载。
        // 6.9.7：116 → 117（覆盖安装必须递增；versionName 仍对外保持 6.4 基线）
        // 6.9.13：122 → 123。逐字对齐（真实字级时长 + 像素级扫光）、蓝牙延迟补偿。
        // 6.5：123 → 124。对外正式版号提到 6.5，全量强制下发。
        // 6.6：125。15 项反馈集中修复（本轮只本地出包，不下发服务器）。
        // 6.8：127。自定义中心重做（高对比/大圆角/排版密度页/跟手按压）、背景可覆盖上传、
        //       小播放器经典竖杠 + 歌名居中/滚动特效、全局字号与排版密度、锁屏与歌词复查。
        // 6.6 修复版：127 → 128。对外版本号按本轮要求从 6.8 归回 6.6（公告写 6.5 → 6.6）。
        //  versionCode 仍必须递增才能覆盖安装；签名与历史一致，登录态、歌单、设置、下载全保留。
        //  GuidePrefs.WHATS_NEW_CODE / WhatsNewR19.CODE 同步抬到 128，覆盖安装也会重看一次公告。
        // 6.6 最新版：128 → 129。开机引导重做成 3 步带动画的两功能引导 + 完整更新清单
        //  （13 项修复 + 本轮修复 + 新增功能 + 如实说明的边界），公告数据源 WhatsNewR19 全量扩充。
        //  门槛 WHATS_NEW_CODE / CODE 同步 129，全新安装与覆盖安装都会弹一次这份最新版引导。
        // 6.6.2：131。后台播放断流修复 + 闪退安全模式 + 大播放器控件可隐藏 +
        //  与其他应用同时播放 + 自定义界面入口可隐藏/手势召唤 + 自定义界面重做 + 歌单居中弹窗等。
        versionCode = 131
        versionName = "6.6.2"

        // 签名钉选哈希：把正式证书 SHA-256 填进项目根 keystore.properties 的 expectedSignature，
        // 打包时注入 BuildConfig；运行时 AppIntegrity 会比对本机签名，仿冒重签名包会被拦下。
        // 留空 = 不校验（源码不包含私钥，无法自动算哈希，需你手动提供）。
        val expectedSig = (rootProject.findProperty("expectedSignature") as? String).orEmpty()
        buildConfigField("String", "EXPECTED_SIGNATURE", "\"$expectedSig\"")

        // 只保留中英文资源，去掉其它语言的 Compose/AndroidX 字符串，减小体积
        resourceConfigurations += listOf("en", "zh")
    }

    buildTypes {
        release {
            // 诊断包开关：gradle :app:assembleRelease -PjunNoMinify
            // 出一个「release 但不混淆、不压缩资源」的包，用来判断崩溃到底是 R8 优化造成的，
            // 还是编译器 / dex 编码本身的问题。5.2featR3 就是靠它排除了 R8 ——
            // 两个包同样闪退，才把矛头指向 D8 的 invoke/range 8 位寄存器上限。
            isMinifyEnabled = !project.hasProperty("junNoMinify")
            isShrinkResources = !project.hasProperty("junNoMinify")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true   // 提供 BuildConfig.VERSION_NAME，供「关于」页读取，避免版本号硬编码脱节
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // ===== 滑动丝滑的关键开关：Compose 强跳过（Strong Skipping） =====
    // 开启后，编译器会把 List/回调这类「不可判定稳定性」的参数也纳入比较，并让
    // 可组合 lambda 具备记忆语义——列表滚动时每个可见 item 不再无谓重组，
    // 帧抖动显著下降。本项目 state 是「不可变 data class + 复制替换 List」的
    // 单向数据流，数据变化时引用必变，因此不存在「改了不刷新」的语义陷阱。
    kotlinOptions {
        freeCompilerArgs += listOf(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:experimentalStrongSkipping=true"
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // material-icons-extended removed: 5MB+ of icon classes we don't use

    // 背景模糊（毛玻璃/backdrop blur），~100KB
    implementation("dev.chrisbanes.haze:haze:0.7.3")

    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")
    implementation("androidx.media3:media3-common:1.5.0")
    // 流式磁盘缓存（5.1）需要直接用 datasource 里的 CacheDataSource/SimpleCache。
    // 它本来就在 media3-exoplayer 的运行时依赖图里，显式声明只为编译期可见，不增加 APK 体积。
    implementation("androidx.media3:media3-datasource:1.5.0")

    // 本地音频代理（在线音频解析真实 CDN 地址 + 带 Referer 转发，绕防盗链），~50KB
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // 6.6 音源插件：QuickJS 引擎（约 2MB，含 arm64/armv7/x86/x64 四套 .so）
    // 用来跑 LX 协议的音源脚本（.js），换音源 = 换一个脚本文件，不用重新打包。
    implementation("wang.harlon.quickjs:wrapper-android:2.4.0")

    // 二维码生成（网易云扫码登录用）：纯 Java，R8 仅保留 QR 编码所需类，体积极小
    implementation("com.google.zxing:core:3.5.3")

    // 登录 token 加密存储（EncryptedSharedPreferences，AndroidX Security）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // 单元测试：纯 JVM，无 Android 框架依赖
    testImplementation("junit:junit:4.13.2")

    // Coil + okhttp removed: ~8MB installed size saved.
    // Album art loaded via ContentResolver + ThumbnailUtils instead.
    // palette-ktx removed: not used anywhere.
}
/**
 * 6.3.1：把混淆对照表（mapping.txt）归档到安装包目录。
 *
 * 为什么必须有这一步：2026-11-05 那条 OOM 崩溃，后台拿到的堆栈全是
 * `B_Gt1Fi7rY.B4tMssiZ.BTnYM3` 这种混淆名，谁也看不出崩在哪个文件哪一行，
 * 等于崩溃中心只报"出事了"不报"在哪出事"。mapping.txt 是 R8 生成的
 * 「混淆名 → 真名」对照表，有了它才能把线上堆栈还原成人能读的代码位置。
 *
 * 打包后执行：gradle :app:archiveMapping
 */
tasks.register("archiveMapping") {
    group = "jun"
    description = "把 release 的 mapping.txt 归档到 安装包/ 目录，便于还原线上崩溃堆栈"
    doLast {
        val vn = android.defaultConfig.versionName ?: "unknown"
        val src = file("build/outputs/mapping/release/mapping.txt")
        if (!src.exists()) {
            println("⚠️ 未找到 mapping.txt（未开混淆或还没打过 release 包）：${src.absolutePath}")
            return@doLast
        }
        val dir = rootProject.file("安装包").apply { mkdirs() }
        val dst = dir.toPath().resolve("mapping-$vn.txt").toFile()
        src.copyTo(dst, overwrite = true)
        println("✅ mapping 已归档：${dst.absolutePath}（${dst.length() / 1024} KB）")
    }
}
