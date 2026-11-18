# Jun Music 改进意见（v2.9 · 第二轮）

上一轮给了「删除 / 改进 / 添加」三张表。这一轮把它们**全部落地**，并在最后给出第三轮建议。
每条都标了**落地位置**，方便你直接跳到代码里看。

---

## 一、已完成的改动（对照上一轮清单）

### 删除类

| 项 | 处理 | 位置 |
| --- | --- | --- |
| 签名私钥 `jun-release.jks` + `keystore.properties` | **从交付源码中移除**（APK 仍用正式签名构建） | 源码包已不含；`.gitignore` 明确排除 `*.jks` / `keystore.properties` |
| `.gitignore` 是空的 | 补齐：私钥、凭据、local.properties、构建产物、IDE 文件 | 仓库根 `.gitignore` |
| 死代码 `NightFlightScene.kt` | 删除 | 已删；`MainScreen` 里对应注释同步改写 |
| 旧 4 步引导组件 `OnboardingGuide.kt` | 删除组件本体，prefs 函数抽出 | 新 `ui/GuidePrefs.kt`（`hasSeenOnboarding` / `markOnboardingSeen` / `resetOnboarding` / 通知权限标记） |
| `docs/IRISMusic.md` 与产品名不符 | 删除 | 已删 |
| `README.md` / `README.en.md` 双份 | 合并为一份（英文作为末尾 English 段），顺手把旧产品名 IRIS Music 改成 Jun Music | `README.md` |
| 四个硬编码 bottom padding 的 Toast | 换成统一提示栈 | 新 `ui/ToastHost.kt` |

### 改进类

| 项 | 处理 | 位置 |
| --- | --- | --- |
| **A2** ViewModel 3715 行 | 下载整段（约 300 行）搬出 | 新 `playback/DownloadManager.kt`，ViewModel 只留 8 行转发 |
| **A3** MainScreen 3623 行 | 顶栏按钮组搬出 | 新 `ui/LibraryButtons.kt`（227 行）→ MainScreen 3365 行 |
| **C3** 明文 HTTP + IP 硬编码 | `BASE_URL` 改 HTTPS 优先，留 `ALLOW_INSECURE_HTTP` 过渡开关，所有调用走 `effectiveBaseUrl` | `data/ServerApi.kt`、`data/NeteaseApi.kt` |
| **C4** 冷启动 5 个任务并发 | 曲库优先，远程配置/版本检查延后 800ms | `MainScreen.kt` 的 `LaunchedEffect(Unit)` |
| **C5** CI 无静态检查 | 加单测步骤 + `lintVitalRelease` + 测试报告上传 | `.github/workflows/build.yml` |
| **E1** 无障碍为零 | Canvas 图标按钮补 `semantics`（歌单 / 收听报告 / 刷新 / 加号 / 待办 / 引导按钮） | `LibraryButtons.kt`、`TodoBoard.kt`、`SpotlightGuide.kt` |
| **E2** 不可逆操作无保护 | 撤销条：清空队列、删除待办都是「生效 + 4 秒可反悔」 | VM `requestUndo/performUndo/restoreQueue`，提示栈带「撤销」键 |
| **E3** 无统一错误态 | 曲库加载失败给带「重试」的提示，重试重跑对应动作 | VM `reportError/retryLastFailedAction` + ToastHost |
| **E4** 空状态不统一 | 抽出通用空状态（三种图形：列表/音频/任务） | 新 `ui/EmptyState.kt`，已接入下载管理、歌单页 |
| **E5** 动画不降级 | 「省电动画」开关，低端机默认开；只砍装饰性无限动画，交互反馈保留 | `JunMotion.reduced` + 设置项 + 聚光灯/待办/绘本接线 |
| **N1** 没有单测 | 新增 `app/src/test`，覆盖歌词解析、待办排序、服务器地址 | `LyricParserTest.kt`、`PureLogicTest.kt`，依赖 `junit:junit:4.13.2` |
| **N2** 无隐私政策 | 首启强制隐私同意卡（不同意退出），设置里可回看 / 撤回，带政策版本号 | 新 `data/PrivacyStore.kt`、`ui/PrivacyConsentSheet.kt` |
| **N4** 崩溃无入口 | 崩溃日志可在设置里查看 / 清空（`JunApp` 原本只写文件） | 设置 → 关于 → 崩溃日志 |
| **N8** 引导无法观测 | 本地埋点：走了几次、看完几次、上次停在第几步、用时 | 新 `data/GuideStats.kt`，设置里可见 |

### UI 一致性（你这次特别提的）

| 处 | 原来 | 现在 |
| --- | --- | --- |
| 绘本欢迎页 | 自建渐变、系统默认字体、独立排版 | 主题底色 + 主/辅色光斑（与首页 Aurora 同源）、文字装在 `junSurface` 玻璃卡片里、字体走 `MaterialTheme.typography`（跟随设置里的全局字体）、圆角走 JunShape、顶部是与首页上栏一致的「Jun / Music」双色品牌行 |
| 聚光灯气泡 | 实色 `colors.card` | `junSurface(SHEET)`——实色/毛玻璃/液态玻璃跟随全局材质 |
| 权限预说明卡 | 实色卡片 | 同上，与所有底部弹层同一套材质 |
| 待办页 | 实色卡片 | 条目走 `junSurface(ROW)`、输入条走 `SHEET`，与歌单行/弹层同一套 |
| 空状态 | 各写一句灰字 | 统一组件，图形按语义选（列表/音频/任务） |

### 引导补全（你这次特别提的：登录注册没介绍、按钮没讲完）

- **绘本欢迎页 3 页 → 5 页**：曲库 → 搜索 → 声音（均衡器/虚拟低音）→ **账号（登录注册 + 云同步，明确说"不登录也照常能听"）** → 待办。
- **聚光灯 4 步 → 7 步**，而且是"钻进界面里指"：
  1 搜索框 → 2 歌单 → 3 布局 → 4 我的 → 5 设置 → **6 自动打开「我的」抽屉照登录/注册入口** → **7 照听歌待办**，结束自动收起抽屉。
- 实现方式：聚光灯新增 `onStepChange`，第 5 步之后由 MainScreen 打开抽屉，让"藏得最深的两个功能"也能被照到。
- 全程可跳过，跳过 / 走完都记埋点。

---

## 二、这一轮没做完的（说清楚为什么）

| 项 | 状态 | 原因与建议做法 |
| --- | --- | --- |
| **A1** 拆分 `PlayerUiState`（143 字段） | **未做** | 这个改动会牵动 UI 侧上百处 `state.xxx` 读取，必须逐块迁移并逐屏验证，一次做完无法保证质量。建议按「播放 / 界面偏好 / 网易云 / 下载」拆成 4 个 StateFlow，**一次拆一块**（先拆下载：字段最少、只有设置页和下载管理两处读取），每拆一块发一个版本。下载的实现层这次已经先搬走了，状态拆分时阻力会小很多。 |
| **A2** ViewModel 剩余部分 | 部分 | 网易云（约 500 行）、账号（约 200 行）两段还没搬。下载这次验证了「把同一个 StateFlow 引用传给新类」这条路可行，照抄即可。 |
| **A4** prefs KEY 散落 | 部分 | 下载相关的 KEY 已随 `DownloadManager` 收拢；其余仍是 VM 内的 `KEY_XXX` 常量（集中在同一个 companion object 里，至少不再散落到各个文件）。 |
| **N3** 音源降级 | 部分 | 加了错误提示 + 重试，但"服务端不可用时降级为本地已下载可播"没做——需要先定义清楚本地缓存的保留策略。 |
| **N6 / N7** 小组件样式同步、歌单分享 | 未做 | 前者需要 RemoteViews 动态主题色（改动面大、收益小）；后者需要服务端接口配合。 |

---

## 三、第三轮建议（做完上一轮之后新暴露出来的问题）

### P0（上线前）

1. **升级 targetSdk**：现在是 34，Google Play 已要求跟进新版（35/36）。不升会影响上架。
2. **隐私政策文案要过一遍法务口径**：现在卡里的文案是我按代码实际行为写的（收集什么 / 不收集什么），但它同时是一份法律文本，需要你确认措辞，并补上「如何联系我们、如何注销账号」这类必备条目。
3. **`ALLOW_INSECURE_HTTP` 目前是 true**（因为服务器 IP 还没上 HTTPS）。上线前必须给服务器配证书然后关掉它——否则改 HTTPS 只是"看起来改了"。

### P1（下个版本）

4. **引导标记带版本号**：现在「看过一次」是永久标记，大版本新增功能时老用户永远看不到新引导。改成 `onboarding_done_v{版本}`，跨大版本自动重放一次。
5. **播放页缺一段引导**：聚光灯目前只覆盖上栏和抽屉，播放页的均衡器、睡眠定时、评论、歌词都没介绍。建议首次进入播放页时延迟 1 秒触发第二段聚光灯（照 2–3 个按钮就够）。
6. **待办要能变成行动**：现在待办只是一句备忘。建议条目上加「搜一下」，点了带着文本跳到搜索页，把"记下来"闭环成"听到"。
7. **崩溃日志要能导出**：现在只能看，用户反馈问题时没法发给你。加「复制到剪贴板 / 分享文件」。
8. **引导埋点应该上传**：本地记了但你看不到，等于没有。在用户已同意隐私政策的前提下，把「引导完成率」随其它统计数据上报，才知道引导该删还是该改。
9. **隐私政策改服务器下发**：现在文案写死在代码里，改一个字要发版。走 `ServerApi` 拉文本 + 版本号，版本变了自动重新征求同意。

### P2（有余力）

10. **单测扩到需要 Context 的部分**：`ListenTodoStore`、`PlayHistory`、`Playlists` 都是纯 prefs/JSON 逻辑，加 `org.robolectric:robolectric` 就能测，是性价比最高的下一步。
11. **老弹层材质统一**：还有若干弹层直接写 `colors.card`，没走 `junSurface`，切到玻璃材质时会和周围不一致。建议全量替换。
12. **依赖升级**：Compose BOM `2024.09.03`、Kotlin `1.9.24`、AGP `8.5.2` 都已落后，建议跟到当前稳定版（一次升一个，别同时升）。
13. **baselineProfile 入库**：release 构建已在生成 baseline profile，建议纳入版本库并在 CI 里复用，冷启动还能再快一截。
14. **`MainScreen` 继续拆**：现在 3365 行，`LibraryPage` 单个 Composable 仍约 800 行。下一步按 `LibraryTopBar / SongList / BatchActionBar / PullToRefresh` 拆，和 A1 的状态拆分一起做，一次动一块。

---

## 四、第三轮落地（对照 `OPINIONS.md` v2.9 全量清单）

这一轮把 `OPINIONS.md` 里**客户端能独立完成的**项全部落地，剩下的大类是「服务端配合 / 高风险的整块重构 / 需要真机走查的 UI」。逐条状态如下。

### ✅ 已落地（本轮 + 前几轮累计）

| 编号 | 项 | 落地位置 |
| --- | --- | --- |
| **B1** | 悬浮歌词 `SYSTEM_ALERT_WINDOW` 权限缺失 | `AndroidManifest.xml` 已补声明 |
| **B2** | CI 权限审计名单与实际不符 | `.github/workflows/build.yml` 审计名单已同步为实际 7 项（含 SYSTEM_ALERT_WINDOW） |
| **B3** | 改密码接口未校验 token | `data/Account.kt` `changePassword` 先判 `token.isBlank()` 拦截 |
| **S1** | 登录密码明文 + HTTP | `ServerApi.effectiveBaseUrl` 改为 HTTPS 优先；`ALLOW_INSECURE_HTTP` 留作过渡开关（**关掉需服务端上证书**，见下） |
| **S2** | token 明文存 SharedPreferences | 改用 `EncryptedSharedPreferences`（AES256-GCM + MasterKey，Keystore 不可用时降级明文），`data/Account.kt` |
| **S3** | 本地音频代理无鉴权 | 代理加一次性 `ACCESS_TOKEN`，ExoPlayer 请求带参、校验失败返回 403；端口占用自动顺延 20 个，`playback/AudioProxyServer.kt` |
| **S7** | media3 过旧 | `1.4.0 → 1.5.0`，配套 AGP `8.5.2 → 8.7.3`、compileSdk/targetSdk `→ 35`、Gradle `→ 8.10.2` |
| **S9** | 无防篡改 | `security/AppIntegrity.kt`：安装来源白名单 + debuggable 检测 + 可选签名指纹比对，启动自检 |
| **S10** | 崩溃日志导出脱敏 | 设置页崩溃日志弹层加「导出/分享」，`ui/SettingsScreen.kt` |
| **S11** | 无混淆字典 | `app/proguard-rules.pro` 加 `-obfuscationdictionary` 等，`proguard/obfuscation_dictionary.txt`（4000 随机标识符） |
| **S12** | 固定端口冲突 | 代理端口占用时顺延，见 S3 |
| **C1** | 无注销账号入口 | 设置 → 账号 → 「注销账号并删除我的数据」（带二次确认），`ui/AccountSheet.kt` + `PlayerViewModel.deleteAccount()` + `Account.deleteAccount()` |
| **C2** | 隐私政策缺条目 | 补「第三方 SDK / 联系与注销 / 未成年人」三段，`ui/PrivacyConsentSheet.kt` |
| **C6** | 无未成年人说明 | 同上，未成年人条款已写入隐私卡 |
| **C7** | targetSdk 34 | 升到 35（已在 S7 一并完成） |
| **F1** | 注销账号 + 导出数据 | 见 C1；导出见 S10 |
| **F2** | 待办「搜一下」 | 待办条目加放大镜按钮，带文本跳搜索页，`ui/TodoBoard.kt` + `MainScreen` 接线 |
| **F4** | 引导标记带版本号 | `ui/GuidePrefs.kt` 加 `GUIDE_VERSION`，跨版本自动重放 |
| **F5** | 崩溃日志导出 | 见 S10 |
| **F6** | 下载断点续传 | `DownloadManager.downloadFileHttp` 支持 `Range` 续传（206 追加 / 200 重头），`playback/DownloadManager.kt` |
| **F9** | 睡眠定时器「播完这首再停」 | `playback/SleepTimer.kt` 加 `stopAfterSong` 模式（落盘、与分钟定时互斥），`PlayerViewModel.setSleepStopAfterSong`，`MusicService` 切歌/播完触发暂停，播放页弹层加开关，`ui/PlayerCard.kt` |
| **F11** | Android Auto / 车载 | `MusicService` 由 `MediaSessionService` 升级为 `MediaLibraryService`，实现 `MediaLibrarySession.Callback` 浏览树（根 → 曲库前 800 首），`AndroidManifest` 声明 `MediaBrowserService` + automotive 描述；media3 1.5.0 的 `ListenableFuture<LibraryResult<…>>` 签名已对齐 |
| **I4** | 均衡器预设 | 加 流行 / 摇滚 / 古典 三档，`audio/EqCurve.kt`（UI 用 `EqBoost.entries` 自动出现） |
| **P2** | 背景无限动画 | 「省电动画」开关 + 切后台停装饰动画（前几轮已做） |
| **P5** | 无掉帧监控 | `JunApp.initFrameMonitor()`：Choreographer 量帧，debug 包记掉帧 warning，release 零开销 |
| **D3** | 文档残留旧名 IRIS | `docs/BUILD.md`、`docs/ARCHITECTURE.md` 的旧名/旧路径已全部改为 Jun Music（代码包名本就是 `com.jun.musiccloud`） |

### ⏳ 需要服务端 / 配置配合（客户端已就绪或无法纯客户端完成）

| 编号 | 项 | 说明 |
| --- | --- | --- |
| **S1（收尾）** | 关闭 `ALLOW_INSECURE_HTTP` | 等服务器上 HTTPS 证书后把这个开关置 false，否则只是"看起来改了" |
| **S5** | 证书绑定 pinning | 上 HTTPS 后加；建议至少 `network-security-config` 限制 trust-anchors |
| **C3** | 隐私同意服务端留痕 | 客户端已记本地版本号；需服务端存「时间+版本+设备」以便举证 |
| **C4** | 在线曲库依赖第三方非公开接口 | 法律 + 稳定性风险，建议做「可配置音源」；客户端无法单独消除 |
| **F12** | 歌单分享链接 | 账号/云同步已有，差分享链接接口 |

### 🚧 已规划但本轮未做（高风险整块重构 / 需真机走查）

| 编号 | 项 | 原因 |
| --- | --- | --- |
| **A1 / P1** | 拆 `PlayerUiState`（143 字段） | 牵动 UI 上百处读取，需逐屏迁移验证；建议先拆下载那块 |
| **F3** | 播放页第二段引导 | 需真机走查聚光灯落点，风险可控但未排期 |
| **F7（增强）** | 歌曲详情完整展示 | `formatLabel` 已在播放页/详情展示码率格式；更完整的大小/来源面板未做 |
| **I3** | 歌词时间轴偏移微调 | 常见诉求，需新增 ±0.5s 校正 UI |
| **E3** | Robolectric 单测 | 需引入 `org.robolectric:robolectric` 覆盖 prefs/JSON 逻辑 |
| **S7（剩余）** | nanohttpd / Compose BOM 升级 | 一次升一个，media3 已先行 |
| **P6** | LeakCanary | `debugImplementation` 即可，待加 |

### 关于产品名

- **软件名永久为「Jun Music」**，应用内所有可见文案、包名（`com.jun.musiccloud`）、文档标题均已统一；旧名 IRIS 仅历史提交记录里残留，不影响产物与安装。

### 构建与交付说明（沙箱）

- 本沙箱环境用于出包：JDK 17（`/opt/jdk/jdk17`）、Gradle `8.10.2`、AGP `8.7.3`。
- 调试/发布签名：仓库不含 `*.jks` 与 `keystore.properties`（`.gitignore` 排除），缺省时 Gradle 回落调试签名——**覆盖安装务必使用同一份密钥**。
- 受限内存沙箱：`gradle.properties` 已设 `org.gradle.jvmargs=-Xmx1794m`、`kotlin.compiler.execution.strategy=in-process` 防 OOM。

