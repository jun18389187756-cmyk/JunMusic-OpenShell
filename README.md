# JunMusic · 开源版

> 一个基于 Jetpack Compose 的原生安卓音乐播放器：**真·折射液态玻璃 UI · 一库四排 · 本地推荐算法 · LX 音源插件系统 · 完整播放链路**。

本项目是 JunMusic 的**脱敏开源版**：保留全部客户端源码与技术实现，已移除所有私有服务器地址、密钥、账号凭据及内置音源脚本。项目以 GPL-3.0 开源。

---

## ✨ 功能

- 🎨 **液态玻璃材质系统**：折射模糊、分档材质（卡片/弹层/表面）、按设备性能自动降级、取色器自定义主/次色
- 🧭 **一库四排**：纵向 / 横向 / 堆叠 / 唱片墙，排布、配色、明暗、材质各自独立
- 🧠 **本地推荐引擎**：基于点赞、播放次数、跳过、时长打分，支持「探索度」调节
- 🎵 **完整播放链路**：Media3/ExoPlayer + 磁盘缓存 + 断点续播 + 后台断流自愈 + 音频焦点管理
- 🎧 **音源插件系统**：基于 QuickJS 的 LX 协议脚本宿主，支持多音源并存、动态切换、脚本即插即用
- 📱 **全局体验**：悬浮/桌面歌词、蓝牙歌词、歌词壁纸、桌面小组件、音律震动、听歌报告、Sleep Timer、自定义 EQ、歌单分享口令
- 🔒 **隐私**：无广告、无遥测；所有播放列表、收藏、统计默认只留在本机

## 🏗 技术栈

| 层 | 技术 |
| --- | --- |
| 客户端 | Kotlin 1.9.24 + Jetpack Compose (Material 3) + Media3/ExoPlayer 1.5 |
| 脚本引擎 | QuickJS (LX 音源协议) |
| 模糊材质 | Haze 0.7.3 + 自研 Glass 材质分层 |
| 本地服务 | NanoHTTPD（本地音频代理） |
| 二维码 | ZXing |
| 构建 | Gradle 8.10.2 + AGP 8.7.3 |
| 许可 | GPL-3.0 |

## 📦 构建

```bash
# 需要 Android SDK（minSdk 24 / targetSdk 35）与 JDK 17+
git clone <your-repo-url>
cd JunMusic-OpenShell
./gradlew :app:assembleDebug
```

> Release 签名需要你自己的 keystore（仓库不含任何签名材料）。

## 🙏 致谢 / Attribution

本项目的界面布局、四排布交互与本地推荐算法设计参考了以下开源项目（按 GPL-3.0 要求保留署名）：

- **IRIS Music** — Copyright (C) 2026 WWRJ，GPL-3.0，[github.com/WWRJ3321/IRISMusic](https://github.com/WWRJ3321/IRISMusic)
- **Folia** — 唱片墙交互灵感来源，[github.com/chthollyphile/folia-major](https://github.com/chthollyphile/folia-major)
- **落雪音乐 LX Music** — LX 音源协议，[github.com/lyswhut](https://github.com/lyswhut)
- **NeteaseCloudMusicApi (Binaryify)** — 早期架构参考
- **Meting** — 聚合接口设计参考
- **Haze** — 模糊渲染，[github.com/chrisbanes/haze](https://github.com/chrisbanes/haze)
- **AndroidLiquidGlass** — 液态玻璃实现思路参考
- **QuickJS / nanohttpd / ZXing** — 底层依赖

详见 [`NOTICE`](./NOTICE) 与 [`REMIND.md`](./REMIND.md)。

## ⚠️ 免责声明

1. **本项目不含、不内置、不分发任何音乐内容或音源脚本**。仓库中仅保留 LX 协议桥接层（`lx-preload.js`），不含任何可用音源。用户若自行导入第三方音源脚本，与本项目作者无关。
2. 项目中的在线接口地址均为**占位符**（`example.invalid`），开箱即用状态为**纯本地播放器**。
3. 本项目按「AS IS」提供，无担保。音乐版权归各权利人所有，请遵守你所在地区的法律。
4. 基于 GPL-3.0：你可以自由使用/修改/分发，但任何衍生作品对外分发时必须同样以 GPL-3.0 开源。
