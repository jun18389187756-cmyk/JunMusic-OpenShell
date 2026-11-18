# Jun Music 全量意见总览（v2.9）

把源码整个翻了一遍之后的所有意见，按「先修 bug → 安全 → 合规 → 加功能 → 改功能 → 删功能 → 性能 → 工程 → 商业化」排列。
每条都写了**依据（在哪个文件看到的）**，方便你核。

---

## 〇、先说三个"已经坏掉的"（不是建议，是 bug）

| # | 问题 | 依据 | 后果 | 建议 |
|---|---|---|---|---|
| **B1** | **悬浮歌词权限没声明**：代码用 `TYPE_APPLICATION_OVERLAY`，但 Manifest 里 6 个权限中没有 `SYSTEM_ALERT_WINDOW` | `playback/FloatingLyric.kt:155` vs `AndroidManifest.xml:4-10` | 设置里开了悬浮歌词也没用：系统授权页里根本没有这个 App 的开关，`addView` 还会抛异常 | 补 `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />`（原来的 10 项权限列表里是有的，被清理时误删了） |
| **B2** | **CI 权限审计必然失败**：`.github/workflows/build.yml` 里 allowed 列表是 10 项，Manifest 实际只有 6 项 | yml 的 `/tmp/allowed.txt` vs Manifest | `diff` 不相等 → `exit 1`，打 tag 发版时 CI 直接红 | 把 allowed 列表改成实际的 6 项（这次清理权限是好事，但忘了同步审计名单） |
| **B3** | **修改密码接口拿不到 token 也能调用**：`/api/change-password` 传 `token`，但没有校验 token 非空/有效 | `data/Account.kt:108-110` | 未登录态下的异常路径可能改到别人账号，取决于服务端实现 | 客户端先判空拦截；服务端必须校验 token 归属（这条主要靠服务端） |

---

## 一、安全（最高优先级）

### P0

| # | 问题 | 依据 | 建议 |
|---|---|---|---|
| S1 | **登录密码明文 POST，而且走 HTTP**：`JSONObject().put("password", password)` 直接塞进请求体 | `data/Account.kt:92,98` + `ServerApi.effectiveBaseUrl`（当前回落 http） | 服务器必须上 HTTPS（客户端已改 https 优先，把 `ALLOW_INSECURE_HTTP` 关掉）；服务端存 bcrypt/argon2，响应体不回传密码 |
| S2 | **登录 token 明文存 SharedPreferences** | `data/Account.kt:36,47` | 用 `EncryptedSharedPreferences`（AndroidX Security），root 机和备份文件里就不会直接读到 |
| S3 | **本地音频代理无鉴权**：NanoHTTPD 监听 `127.0.0.1:8089`，任何本地 App 都能 `GET /audio?id=…` | `playback/AudioProxyServer.kt:21` | 绑定地址是对的（只听 localhost），但建议加一次性 token：ExoPlayer 请求时带上，代理校验后再给流 |
| S4 | **网易云 weapi 逆向加密**（AES + RSA，含第三方硬编码常量） | `data/NeteaseWeApi.kt:51-132` | 见「合规 C4」：这是法律风险，不只是技术问题 |

### P1

| # | 问题 | 依据 | 建议 |
|---|---|---|---|
| S5 | 无证书绑定（certificate pinning） | `ServerApi` 用裸 `HttpURLConnection` | 上 HTTPS 后加 pinning（或至少 `network-security-config` 里限制 trust-anchors 只信你的 CA） |
| S6 | `network_security_config` 全局放行明文 | `res/xml/network_security_config.xml` | 改成 domain-config：只对你的域名放行，`<base-config cleartextTrafficPermitted="false">` |
| S7 | **依赖偏旧**：media3 `1.4.0`、nanohttpd `2.3.1`（2019）、haze `0.7.3`、Compose BOM `2024.09.03` | `app/build.gradle.kts:107-125` | 一次升一个：先 nanohttpd（历史 CVE 最多）→ media3 → Compose BOM |
| S8 | 组件导出面偏大：MainActivity / MusicService / 两个 Receiver 都是 `exported=true` | `AndroidManifest.xml:26,39,60,75` | 逐个确认必要性：AppWidget 的 Receiver 必须导出；MediaService 建议加 `permission` 或改用 `MediaSession` 回调；Activity 只为 launcher intent 导出 |
| S9 | 无防调试/防篡改（可被二次打包注入广告） | 构建配置 | 加 `android:debuggable=false`（release 默认已是）+ Play App Signing / 签名校验（启动时比对签名指纹） |
| S10 | 崩溃日志写在 `getExternalFilesDir/crash/crash.log`，含完整堆栈（可能含路径/账号信息） | `JunApp.kt` | 本地留存可以，但导出分享时要做脱敏；别随崩溃上报一起发出去 |

### P2

| # | 问题 | 建议 |
|---|---|---|
| S11 | 没有 ProGuard 字典/资源混淆，反编译后类名可读性尚可 | 加 `-obfuscationdictionary`（低成本） |
| S12 | 固定端口 8089 可能与其它 App 冲突 | 端口占用时自动顺延 |

---

## 二、合规（国内上架/应用商店硬门槛）

| # | 问题 | 依据 | 建议 |
|---|---|---|---|
| C1 | **没有注销账号 / 删除个人信息入口** | 全库 grep 无 `注销`/`deleteAccount` | 合规明确要求"提供删除个人信息的途径"。至少加「设置 → 账号 → 注销账号」，调用服务端删除接口并清空本地 |
| C2 | 隐私政策缺少必备条目 | `ui/PrivacyConsentSheet.kt` | 补：如何联系我们（邮箱）、如何注销/导出数据、第三方 SDK 清单（目前无第三方 SDK，要写明"无"）、未成年人条款 |
| C3 | 隐私同意只在本地记标记，无法举证 | `data/PrivacyStore.kt` | 服务端记一条同意记录（时间 + 政策版本 + 设备），出事时能拿出来 |
| C4 | **在线曲库依赖第三方非公开接口** | `NeteaseWeApi.kt`、`NeteaseApi.kt` | 这是最大的长期风险：接口随时失效，且有法律争议。建议做「可配置音源」，默认走有授权的聚合 API 或让用户自己填；至少要能在服务端一键切换而不发版 |
| C5 | 权限清单里有 `WAKE_LOCK`/`FOREGROUND_SERVICE`，但隐私文案没提 | Manifest | 隐私政策里逐个说明每个权限的用途 |
| C6 | 没有「儿童/未成年人」相关说明 | — | 若目标用户不含未成年人，隐私政策里写明；含的话需要额外保护 |
| C7 | 应用商店要求的目标 API 等级 | `targetSdk=34` | 升到 35/36 |

---

## 三、可以加的功能（按"用户价值 / 实现成本"排序）

### 高价值、低成本

| # | 功能 | 理由 |
|---|---|---|
| F1 | **注销账号 + 导出我的数据** | 合规刚需（见 C1） |
| F2 | **待办条目 → 「搜一下」** | 待办现在只是备忘，点一下带着文本跳搜索页，"记下来"才闭环成"听到" |
| F3 | **播放页第二段引导** | 均衡器/睡眠定时/评论/歌词全都没介绍；首次进播放页延迟 1 秒触发聚光灯 |
| F4 | **引导标记带版本号** | `onboarding_done_v1` 是一次性标记，大版本新功能老用户永远看不到 |
| F5 | **崩溃日志导出（分享/复制）** | 现在只能看，用户没法把日志发给你 |
| F6 | **下载断点续传** | `DownloadManager` 里没有 Range/续传逻辑，大文件失败要重头下 |
| F7 | **歌曲详情**（码率/大小/格式/来源） | `Song` 已有 `format` 字段但基本没展示，用户想知道"我听的是多大码率" |
| F8 | **手势自定义**（左滑进播放页可关） | 老引导反复教"要左滑"，说明这个手势本身有争议 |
| F9 | **定时关闭的"播完这首再停"** | 睡眠定时器常见诉求，成本极低 |
| F10 | **首页快捷入口：继续上次播放** | 冷启动恢复的是队列但没有一键续播 |

### 中成本

| # | 功能 | 理由 |
|---|---|---|
| F11 | **Android Auto / 车载蓝牙** | 已有 media3-session，加 `MediaLibraryService` 成本可控，车主是高频场景 |
| F12 | **歌单分享链接** | 账号体系和云同步歌单已有，只差分享链接就能从"我的歌单"变"我们的歌单" |
| F13 | **歌单封面自定义 / 自动生成拼图** | 视觉吸引力，且已有封面加载能力 |
| F14 | **跨平台：桌面小组件 4×4 的专辑墙** | 已有 widget 框架 |
| F15 | **听歌识曲** | 需第三方服务，成本高，谨慎 |
| F16 | **本地文件扫描（真正的本地播放）** | README 还写着"本地音乐播放器"，但现在是在线为主——要么补回来，要么改文案 |
| F17 | **歌词翻译 / 罗马音** | 二次元曲库常见诉求 |
| F18 | **播客/有声书** | 偏离定位，除非你想扩品类 |

---

## 四、可以改进的功能

| # | 处 | 现状 | 建议 |
|---|---|---|---|
| I1 | 搜索 | 只有关键词匹配（依赖服务端） | 加本地搜索历史、热搜（RemoteConfig 已有热搜词）、拼音首字母匹配 |
| I2 | 下载 | 无断点续传、无速度显示、失败只有一句"下载失败，请重试" | 区分错误原因（403 防盗链 / 存储满 / 无网络），给对应的处理建议 |
| I3 | 歌词 | 有逐字 karaoke，但无歌词校正（时间轴偏移调整） | 加 ±0.5s 微调，这是歌词类 App 最常被要求的功能 |
| I4 | 均衡器 | 有 3 个内置增强预设 | 加常见预设（流行/摇滚/爵士/电子/人声）+ 自定义预设保存 |
| I5 | 通知栏 | 三键 + 进度条（预览里已画） | 加"喜欢"键、支持媒体样式大封面 |
| I6 | 睡眠定时器 | 只有总时长 | 加"播完 N 首后停止" |
| I7 | 账号 | 可改密码、改昵称头像 | 加邮箱/手机绑定、找回密码 |
| I8 | 推荐 | `exploration` 探索度可调 | 加"不感兴趣"按钮，负反馈比调参有效 |
| I9 | 主题 | 8 套 + 自定义取色 | 加"跟随专辑封面取色"（已有 ArtworkLoader，成本不高） |
| I10 | 排序 | 标题/歌手/时长等 | 加"最近添加" |
| I11 | 空状态 | 已统一 | 剩下的老弹层（`colors.card` 硬编码）全量改 `junSurface` |
| I12 | 多语言 | 只有中文，`strings.xml` 只有 3 条 | 要么全量抽 strings 做英文，要么明确只做国内别留半吊子 |

---

## 五、可以删 / 可以精简的

| # | 对象 | 依据 | 建议 |
|---|---|---|---|
| D1 | `MANAGE_EXTERNAL_STORAGE` / `READ_MEDIA_AUDIO` / `SYSTEM_ALERT_WINDOW` 的 CI 审计条目 | yml 里还在，App 里已没有 | 同步成实际清单（见 B2）；`SYSTEM_ALERT_WINDOW` 要看你是不是要救悬浮歌词 |
| D2 | README 里"本地音乐播放器""默认不联网""无账号"的定位描述 | `README.md` | 已经是在线为主 + 有账号，文案和产品对不上，最容易被误解 |
| D3 | 项目里残留的旧名 "IRIS" | 签名证书 `CN=IRIS Music`、`docs/ARCHITECTURE.md` | 改名要换证书（会影响覆盖安装），不换就统一叫回 IRIS，别两头叫 |
| D4 | `REMIND.md` | 仓库根目录 | 如果是给自己的备忘，别进仓库；如果是 TODO，整理成 issues |
| D5 | 重复的空状态文案（4 处"还没有…"） | `QueueSheet.kt`、`PlaylistSheets.kt` | 已抽 `EmptyState`，剩下的全量替换 |
| D6 | 完全一致的两份下载路径逻辑（默认路径 / 自定义路径分叉） | `DownloadManager.kt` | 合成一条，减少分支 |
| D7 | `docs/ARCHITECTURE.md` 里如果还在描述"本地扫描" | 文档 | 与实际架构对齐，否则比没有文档更误导 |

---

## 六、性能与稳定性

| # | 问题 | 依据 | 建议 |
|---|---|---|---|
| P1 | **`PlayerUiState` 143 个字段，一次 copy 全树重组** | `PlayerViewModel.kt` | 拆 4 个 StateFlow，先拆下载（字段最少、读取处最少） |
| P2 | 液态玻璃 + 频谱 + 光晕 + 悬浮动画同时跑 | `LiquidGlass.kt`、`SpectrumBars.kt` | 已有 `省电动画` 开关，再补"播放页才开频谱""切后台立即停所有 infinite 动画" |
| P3 | 冷启动：曲库 + 配置 + 更新 + 榜单 + 听歌统计 | `MainScreen.kt` | 已延后 800ms；再进一步：听歌统计迁移和榜单放到 idle（`IdleHandler`） |
| P4 | 大列表 `SongRow` 每行都算 `readableOn` | `MainScreen.kt` | 已有缓存，但可在 `Song` 上预计算 |
| P5 | 无 ANR / 卡顿监控 | — | 加 `FrameMetricsAggregator` 或 Jetpack Benchmark，至少记首帧时间 |
| P6 | 无内存泄漏检测（Activity 重建、controller 未释放） | `PlayerViewModel` 的 `connectController` | 加 LeakCanary（debugImplementation） |
| P7 | 弱网/无网时没有缓存兜底（曲库缓存有，但搜索/榜单没有） | `MusicRepository` | 榜单和搜索结果做 5 分钟内存缓存 + 24h 磁盘缓存 |

---

## 七、工程与架构

| # | 建议 | 说明 |
|---|---|---|
| E1 | ViewModel 继续拆 | 网易云（约 500 行）、账号（约 200 行）按 `DownloadManager` 的方式搬（传同一个 StateFlow） |
| E2 | MainScreen 继续拆 | 现 3365 行，`LibraryPage` 仍约 800 行；按 TopBar / SongList / BatchActionBar / PullToRefresh 拆 |
| E3 | 单测扩到需要 Context 的部分 | 加 `org.robolectric:robolectric`，覆盖 `ListenTodoStore` / `PlayHistory` / `Playlists` |
| E4 | 加 UI 测试 | Compose 的 `createAndroidComposeRule`，至少覆盖"首启引导能走完"这条主链路 |
| E5 | 加 ktlint/detekt | 现在 3000+ 行文件靠人眼兜不住 |
| E6 | 统一命名与包名残留 | `IRIS` / `iris` / `Jun` 混用 |
| E7 | 版本号/更新策略 | 服务端 force 更新能一键把用户卡住，建议加"灰度比例 + 可回滚" |
| E8 | baseline profile 入库 | release 已生成，纳入版本库后冷启动更快 |

---

## 八、如果你打算商业化

| # | 想法 | 代价/风险 |
|---|---|---|
| M1 | 会员去广告 / 更高码率 | 目前没有广告，先要有广告才有去广告 |
| M2 | 主题/图标包付费 | 成本最低、风险最小，适合先试 |
| M3 | 音效预设包付费 | 和均衡器强相关，用户感知强 |
| M4 | 捐助/买断 | 无合规负担 |
| ⚠️ | **不要靠"下载付费歌曲"变现** | 版权红线 |

---

## 九、如果只做十件事（我的排序）

1. 补 `SYSTEM_ALERT_WINDOW`（B1）或明确砍掉悬浮歌词功能
2. 修 CI 权限审计列表（B2）
3. 服务器上 HTTPS + 关掉 `ALLOW_INSECURE_HTTP`（S1）
4. token 改 `EncryptedSharedPreferences`（S2）
5. 加注销账号 + 数据导出（C1）
6. 隐私政策补必备条目 + 服务端记同意记录（C2/C3）
7. 本地代理加一次性 token（S3）
8. 升 targetSdk 到 35/36（C7）
9. 评估网易云 weapi 的替代方案（C4）——这是唯一可能让 App 某天整体失效的风险
10. 拆分 `PlayerUiState`（P1），先拆下载那一块

---

*以上每条都对应源码里的具体位置；带 ⚠️ / P0 的属于"上线前必须处理"，其余按节奏来。*
