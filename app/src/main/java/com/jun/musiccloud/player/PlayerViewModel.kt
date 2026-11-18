package com.jun.musiccloud.player

import android.app.Application
import android.content.ComponentCallbacks
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.jun.musiccloud.audio.BassHaptics
import com.jun.musiccloud.audio.EqualizerController
import com.jun.musiccloud.audio.FxPreset
import com.jun.musiccloud.audio.RangeEnhancer
import com.jun.musiccloud.audio.SafeLimiter
import com.jun.musiccloud.audio.VirtualBass
import com.jun.musiccloud.audio.TrackGain
import com.jun.musiccloud.audio.VirtualSurround
import com.jun.musiccloud.audio.SpatialWide
import com.jun.musiccloud.audio.FadeController
import com.jun.musiccloud.audio.SilenceSkipper
import com.jun.musiccloud.audio.SpectrumAnalyzer
import com.jun.musiccloud.data.ListenEntry
import com.jun.musiccloud.data.ListenCloud
import com.jun.musiccloud.data.ListenReport
import com.jun.musiccloud.data.ListenReportBuilder
import com.jun.musiccloud.data.ListenSongMeta
import com.jun.musiccloud.data.ListenStats
import com.jun.musiccloud.data.ListenTodo
import com.jun.musiccloud.data.ListenTodoStore
import com.jun.musiccloud.data.LibraryMode
import com.jun.musiccloud.data.LyricParser
import com.jun.musiccloud.data.LocalLibrary
import com.jun.musiccloud.data.SettingsSync
import com.jun.musiccloud.data.SettingsCloud
import com.jun.musiccloud.data.SettingsPresets
import com.jun.musiccloud.data.AiThemeApi
import com.jun.musiccloud.data.StartupLoginStore
import com.jun.musiccloud.data.Telemetry
import com.jun.musiccloud.data.AccountStore
import com.jun.musiccloud.data.AccountScope
import com.jun.musiccloud.data.DownloadFormat
import com.jun.musiccloud.data.DownloadFormatStore
import com.jun.musiccloud.ui.JunMotionSpeed
import com.jun.musiccloud.data.AudioQuality
import com.jun.musiccloud.data.AudioQualityStore
import com.jun.musiccloud.data.PlaybackCache
import com.jun.musiccloud.data.ResolvedAudio
import com.jun.musiccloud.data.LocalProfileStore
import com.jun.musiccloud.data.AuthApi
import com.jun.musiccloud.data.AuthResult
import com.jun.musiccloud.data.CloudPlaylist
import com.jun.musiccloud.data.CloudSong
import com.jun.musiccloud.data.VipStatus
import com.jun.musiccloud.data.DataTransfer
import com.jun.musiccloud.data.MusicFolder
import com.jun.musiccloud.data.HomeFeed
import com.jun.musiccloud.data.MusicRepository
import com.jun.musiccloud.data.NeteaseApi
import com.jun.musiccloud.data.NeteaseStore
import com.jun.musiccloud.data.OnlineMusicApi
import com.jun.musiccloud.data.resolveNeteaseIdForOnlineSong
import com.jun.musiccloud.data.PlayHistory
import com.jun.musiccloud.data.PlaylistImport
import com.jun.musiccloud.data.PlaylistShare
import com.jun.musiccloud.data.ShareImportOutcome
import com.jun.musiccloud.data.Playlist
import com.jun.musiccloud.data.Playlists
import com.jun.musiccloud.data.RecentPlayback
import com.jun.musiccloud.data.Recommender
import com.jun.musiccloud.data.SearchHistoryStore
import com.jun.musiccloud.data.ServerApi
import com.jun.musiccloud.data.SessionSignals
import com.jun.musiccloud.data.RepeatMode


import com.jun.musiccloud.data.Song
import com.jun.musiccloud.data.lyricKey
import com.jun.musiccloud.data.identityKey
import com.jun.musiccloud.data.formatLabel
import com.jun.musiccloud.data.qualityLabel
import com.jun.musiccloud.data.DownloadTask
import com.jun.musiccloud.data.DownloadStatus
import com.jun.musiccloud.data.serverId
import com.jun.musiccloud.data.SortDirection
import com.jun.musiccloud.data.SortOrder
import com.jun.musiccloud.data.StatRange
import com.jun.musiccloud.playback.DownloadManager
import com.jun.musiccloud.playback.MusicService
import com.jun.musiccloud.playback.AudioProxyServer
import com.jun.musiccloud.playback.SleepTimer
import com.jun.musiccloud.ui.ArtworkLoader
import com.jun.musiccloud.ui.LyricPreset
import com.jun.musiccloud.ui.LyricPresetCatalog
import com.jun.musiccloud.ui.StageProfile
import com.jun.musiccloud.ui.Haptics
import com.jun.musiccloud.ui.theme.BG_BLUR_DEFAULT
import com.jun.musiccloud.ui.theme.DevicePerformance
import com.jun.musiccloud.ui.theme.BG_BLUR_MAX
import com.jun.musiccloud.ui.theme.BG_BLUR_MIN
import com.jun.musiccloud.ui.theme.GLASS_BLUR_DEFAULT
import com.jun.musiccloud.ui.theme.GLASS_BLUR_MAX
import com.jun.musiccloud.ui.theme.GLASS_BLUR_MIN
import com.jun.musiccloud.ui.theme.JunMode
import com.jun.musiccloud.ui.theme.JunLayout
import com.jun.musiccloud.ui.theme.JunSurfaceStyle
import com.jun.musiccloud.ui.theme.JunTheme
import com.jun.musiccloud.ui.theme.FONT_INDEX_MAX
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 6.6.2：广播动作 —— 通知播放服务重新申请/释放音频焦点（与其他应用同时播放开关） */
const val ACTION_PLAY_WITH_OTHERS = "com.jun.musiccloud.action.PLAY_WITH_OTHERS"

/** 一条批量操作的记录（5.1「最近批量操作」面板用，仅存内存） */
data class BatchOpEntry(
    val timeMs: Long,
    val text: String,
    val undoable: Boolean
)

/** 歌手详情页状态（null = 页面关闭） */
data class ArtistDetailState(
    val artistId: String = "",
    val name: String = "",
    val avatar: String = "",
    val songs: List<Song> = emptyList(),
    val loading: Boolean = false,
    val notice: String = "",
    /** 6.9.5：歌手的专辑列表（歌手页"专辑"一栏），点开复用专辑歌曲弹层 */
    val albums: List<NeteaseApi.NsAlbum> = emptyList()
)

/** 播放器全局 UI 状态 */
/**
 * PlayerUiState 拆出来的第二层（5.2featR3）。
 *
 * 为什么非拆不可：PlayerUiState 一度有 238 个属性，Kotlin 为 `state.copy(某字段 = x)`
 * 生成的 `copy$default` 需要 **260 个 argument registers**，而 dex 的 `invoke/range`
 * 指令只用 8 位存寄存器个数（上限 255）——D8 把 260 写成 260 & 0xFF = 4，产出一个
 * 参数个数对不上的坏调用，ART 校验器于是拒收整个 PlayerViewModel：
 * `VerifyError: Rejecting invocation, expected 4 argument registers, method signature has 5 or more`，
 * 表现就是点开 App 就闪退（混淆与否都会，R8 不是元凶）。
 *
 * 所以：新增状态字段请放进这里，或在需要时再拆第三层，**别把 PlayerUiState 的
 * 一级属性加回 200 个以上**。写完打包请用 tools/check_dex_arg_registers.py 验一遍。
 *
 * 读取仍走 PlayerUiState 上的平铺 getter（`state.lyricFocus`），只有写要经过
 * `withExtras { copy(...) }`。
 */
data class PlayerExtras(
    /** 播放页可视化频谱开关（点击音频格式徽章切换） */
    val visualizerEnabled: Boolean = false,
    /** 实验性：可视化随手机倾斜变化（向哪侧倾斜哪侧升幅加大） */
    val tiltSpectrum: Boolean = false,
    /** 实验性：摇动手机封面跟着一晃（duangduang） */
    val coverShake: Boolean = false,
    /** 封面左下角单行歌词（锁在封面上，换词时模糊渐隐渐出） */
    val coverLyric: Boolean = false,
    /** 封面正上方空白区单行歌词（大字号，换句时渐隐渐出） */
    val coverTopLyric: Boolean = false,
    /** 全屏歌词当前行的伪逐字扫光（无词级时间时按行时长匀速扫过） */
    val karaokeLyric: Boolean = true,
    /** 全屏歌词对齐：0=靠左 1=居中 2=靠右 */
    val lyricAlign: Int = 1,
    /** 歌词字体：0=系统 1=衬线 */
    val lyricFont: Int = 0,
    /** 歌词字号 (sp) */
    val lyricFontSize: Float = 20f,
    /** 全屏歌词舞台：0=流光 … 13=静止（Folia 十四种视觉模式） */
    val lyricStage: Int = 0,
    /** 歌词运动风格：0=经典扫光 1=弹跳 2=玻璃 3=漂浮 4=轻快 5=流光 6=故障 */
    val lyricMotion: Int = 1,
    /** 歌词显示模式：0=单行 1=双行 2=三行 3=影院 4=自定义 */
    val lyricDisplayMode: Int = 2,
    /** 自定义显示模式的上下文行数（0..6） */
    val lyricContext: Int = 2,
    /** 歌词渲染品质：0=自动 1=流畅 2=均衡 3=精致 */
    val lyricQuality: Int = 0,
    /** 节拍律动：低频/鼓点驱动呼吸、光晕与故障抖动 */
    val lyricBeatFx: Boolean = true,
    /** 歌词层星河粒子 */
    val lyricParticles: Boolean = true,
    /** 歌词层低频光晕 */
    val lyricBassGlow: Boolean = true,
    /** 副歌识别与副歌专属氛围 */
    val lyricChorusFx: Boolean = true,
    val lyricChorusBoost: Float = 0.7f,
    /** 封面取色联动：光晕/粒子/强调色跟随专辑封面 */
    val lyricCoverTint: Boolean = false,
    val lyricTintStrength: Float = 0.65f,
    /** 歌词时间校准：全局偏移 + 单曲偏移（正数=歌词延后） */
    val lyricOffsetMs: Int = 0,
    val lyricSongOffsets: Map<Long, Int> = emptyMap(),
    /** 开头制作人员折叠卡 */
    val lyricStaffCard: Boolean = true,
    /** 排版：行距倍率、字距(em)、字重 0/1/2、描边强度、纹理 0/1/2/3 */
    val lyricLineSpacing: Float = 1.18f,
    val lyricLetterSpacing: Float = 0f,
    val lyricWeight: Int = 1,
    val lyricOutline: Float = 0f,
    val lyricTexture: Int = 0,
    /** 逐词发光：0=无 1=拖尾 2=呼吸 3=强拍闪白 */
    val lyricGlowStyle: Int = 1,
    val lyricGlowStrength: Float = 0.6f,
    /** 封面纵深 + 点击涟漪 */
    val lyricCoverDepth: Boolean = false,
    /** 当前舞台调参：密度 / 速度 / 发光 / 镜头 */
    val lyricTuning: List<Int> = listOf(50, 50, 50, 50),
    /** 当前舞台的专属调校：字号 / 动势 / 辉光 / 形态 */
    val lyricStageProfile: StageProfile = StageProfile.Default,
    /** 歌词焦点行在屏幕上的纵向位置（0..1，默认 0.38） */
    val lyricFocus: Float = 0.38f,
    /** 节拍电影镜头：重拍推近、轻拍回弹，横屏沉浸更明显 */
    val lyricBeatCamera: Boolean = true,
    val lyricBeatCameraStrength: Float = 0.5f,
    /** 长音频模式：0=自动 1=强制 2=关闭；降低运动幅度、拉长停留 */
    val lyricLongFormMode: Int = 0,
    /** 视频背景（本地 MP4），空=关闭 */
    val lyricVideoUri: String = "",
    val lyricVideoAlpha: Float = 0.42f,
    /** 中文语义断行：按词组/标点断句，避免单字落行 */
    val lyricSemanticWrap: Boolean = true,
    /** 设置页模式：false=基础（隐藏高阶调校），true=进阶 */
    val settingsAdvanced: Boolean = false,
    /** 减少动效：关闭粒子/节拍镜头/低频光晕，并压低舞台动势 */
    val reduceMotion: Boolean = false,
    /** 歌词层帧率监测（调试） */
    val lyricFrameStats: Boolean = false,
    /** 歌词手势引导是否已看过 */
    val lyricGuideSeen: Boolean = false,
    /** 按歌曲记住上次用的舞台 */
    val lyricStagePerSong: Boolean = false,
    /** 收藏的歌词舞台（0..13） */
    val favoriteStages: List<Int> = emptyList(),
    /** 显示翻译轨（.t.lrc / 在线 tlyric） */
    val lyricTranslation: Boolean = true,
    /** 显示音译/罗马音轨 */
    val lyricRomanization: Boolean = false,
    /** 音频效果预设链：-1=自定义，其余见 FxPreset.entries */
    val fxPreset: Int = -1,
    /** 智能 Automix 过渡开关 */
    val automixEnabled: Boolean = false,
    /** Automix 风格：0=智能淡切 1=柔和长淡 2=直接切换 */
    val automixStyle: Int = 0,
    /** 本地曲库：用户通过 SAF 选择的文件夹（空=未启用） */
    val localFolder: String = "",
    /** 本地曲库已扫描歌曲数 */
    val localSongCount: Int = 0,
    /** 本地曲库扫描中 */
    val localScanning: Boolean = false,
    /** 本地曲库：读取文件夹封面 */
    val localFolderCover: Boolean = true,
    /** 本地曲库：遵循 .junignore / .foliaignore */
    val localIgnore: Boolean = true,
    /** 本地曲库：歌手/专辑别名规则（每行 原名称=新名称） */
    val localAliasRules: String = "",
    /** 本地曲库：把「A / B」这类合唱拆成主歌手，便于按歌手归类 */
    val localSplitArtists: Boolean = false,
    /** 本地曲库最近一次扫描结果说明 */
    val localScanNotice: String = "",
    /** AI 情绪主题生成中 */
    val aiThemeBusy: Boolean = false,
    /** 最近一次 AI 主题名称 */
    val aiThemeName: String = "",
    /** 局域网遥控服务开关 */
    val remoteEnabled: Boolean = false,
    /** 局域网遥控地址（开启后由服务端填充） */
    val remoteUrl: String = "",
    /** 播放音质档位（5.1）；R4 起默认「标准音质」，档位名直接写在播放页那枚按钮上 */
    val audioQuality: AudioQuality = AudioQuality.STANDARD,
    /**
     * 切档位时那条轻提示的文案（5.2 起写在播放页徽章上，6.0 起改成底部小胶囊）。
     * 空串 = 不显示。先写「想要哪档」，解析回来后再改成「实际拿到哪档」——
     * 用户要的是真实，所以最终留下的必须是后者。
     */
    val qualityFlash: String = "",
    /** 音质提示触发计数：ToastHost 靠它重播动画（6.0） */
    val qualityFlashTick: Int = 0,
    /**
     * 音效（均衡器预设）提示文案（6.0 R12）。
     *
     * 用户这条的原话是：「这上面写的什么音效就提示就什么音效，即使那个音效没效果，
     * 也要提示是什么音效」。所以这里**只报名字**——不管本机系统均衡器接管不接管、
     * 这段增强有没有真的改变听感，切到「黑胶」就写「音效 · 黑胶」。
     * 空串 = 不显示。
     */
    val fxFlash: String = "",
    /** 音效提示触发计数：同一档再点一次也要重播动画 */
    val fxFlashTick: Int = 0,
    /** 流式缓存当前占用字节，-1 = 尚未统计 */
    val streamCacheBytes: Long = -1L,
    /** 在线缓存写满提示文案（5.2），空串 = 不提示 */
    val cacheNotice: String = "",
    /** 缓存提示触发计数：ToastHost 靠它重播动画 */
    val cacheNoticeTick: Int = 0,
    /** 倍速，基点：100 = 1.0 倍（5.1） */
    val playbackSpeedBp: Int = 100,
    /** 设置云同步状态文案 */
    val cloudSyncStatus: String = "",
    /** 界面字体索引：0..FONT_INDEX_MAX，取值见 ui/theme/Type.kt 的 JunFonts；setUiFont 会同时写到 lyricFont */
    val uiFont: Int = 0,
    /** 上栏（卡片模式顶栏）完全静止时是否淡到完全透明（关=停在半透明 30%） */
    val topBarAutoHide: Boolean = false,
    /**
     * 上栏静止多少秒后开始淡出（6.9.12 新增，可配）。
     * 旧版写死 5000ms 且 900ms 就淡完 —— 用户"切到横向/堆叠/唱片墙，
     * 上面那排一会儿就没了，根本来不及点"，而且消失得太干脆。
     * 现在默认 10 秒、淡出 1.8 秒，静止时还留一点残影。
     */
    val topBarHideSeconds: Int = 10,
    /** 全局圆角基准值（dp），驱动所有卡片/列表/徽章的圆角 */
    val cornerBase: Float = 38f,
    /** 全局字号缩放倍率，1.0 为系统默认 */
    val fontScale: Float = 1f,
    /** 音乐渐入渐出开关 */
    val fadeEnabled: Boolean = false,
    /** 渐变时长（毫秒），默认 2 秒 */
    val fadeMs: Long = 2000L,
    /** 触感反馈开关 */
    val hapticsEnabled: Boolean = true,
    /** 歌单页显示"为你推荐"区域 */
    val showRecommendations: Boolean = true,
    /** 上栏排序行显示开关（关闭后上栏不再展开排序胶囊） */
    val showSortBar: Boolean = true,
    /** 无声略过（beta）：自动跳过曲首/曲尾静音段 */
    val silenceSkip: Boolean = false,
    /** 低音马达震动（beta · 多维听感） */
    val bassHaptics: Boolean = false,
    /** 震动强度档位：0=轻点 1=标准 2=重击 */
    val bassHapticsIntensity: Int = 1,
    /** 单次震动时长 ms，1-30 */
    val bassHapticsPulseMs: Int = 20,
    /** 触发灵敏度 1-10，越大越容易触发 */
    val bassHapticsSensitivity: Int = 5,
    /** 低音截止频率 Hz：多少 Hz 以下算作低音，120–320，默认 250 */
    val bassHapticsLowCutoff: Int = 250,
    /** 自适应：时长/灵敏度跟随音乐实时变化 */
    val bassHapticsAdaptive: Boolean = false,
    /** 虚拟低音（V2.2 · beta） */
    val virtualBass: Boolean = false,
    /** 虚拟低音混合强度 0-100 */
    val virtualBassStrength: Int = 50,
    /** 动态范围增强（V2.2 · beta） */
    val rangeEnhancer: Boolean = false,
    /** 动态范围增强强度 0-100 */
    val rangeEnhancerStrength: Int = 70,
    /** 虚拟环绕（V2.2 · beta）：立体声声场展宽 */
    val virtualSurround: Boolean = false,
    /** 虚拟环绕强度 0-100 */
    val virtualSurroundStrength: Int = 40,
    /** 宽场环绕（V4.4 · beta）：多频段展宽 + 早期反射，进一步提升空间感 */
    val spatialWide: Boolean = false,
    /** 宽场环绕强度 0-100 */
    val spatialWideStrength: Int = 50,
    /** 防失真限幅（V3.10）：防止推高/增强后削波撕裂 */
    val safeLimiter: Boolean = false,
    /** 防失真限幅强度 0-100（越大余量越足、越安全） */
    val safeLimiterStrength: Int = 30,
    /** 曲间响度均衡（V3.11）：在线测响度、按曲拉平 */
    val loudnorm: Boolean = false,
    /** 拉平力度 0-100（100=完全对齐 -14 LUFS） */
    val loudnormStrength: Int = 70,
    /** 自定义主题主色（ARGB_8888，仅 theme==CUSTOM 时生效） */
    val customPrimaryArgb: Long = 0xFF00F0FFL,
    /** 自定义主题次色（ARGB_8888，仅 theme==CUSTOM 时生效） */
    val customSecondaryArgb: Long = 0xFFFF2E97L,
    /** 自定义背景图 URI（SAF 持久化 URI）；null = 用专辑封面做背景 */
    val customBackgroundUri: String? = null,
    /** 下载提示文案（空=不显示） */
    val downloadNotice: String = "",
    /** 下载提示触发计数 */
    val downloadNoticeTick: Int = 0,
    /** 下载目录路径（空=默认 Music/Jun Music） */
    val downloadPath: String = "",
    /** 是否已确认下载目录 */
    val downloadPathConfirmed: Boolean = false,
    /** 底部小播放器是否常驻（关=空闲淡出） */
    val miniPlayerPersist: Boolean = false,
    /** 下载任务列表 */
    val downloadTasks: List<DownloadTask> = emptyList(),
    /** 「听歌待办」条目（本地备忘，空状态引导用的就是这个列表） */
    val todos: List<ListenTodo> = emptyList(),
    /** 全局错误提示（空 = 无错误）：网络拉取失败等，带「重试」动作 */
    val errorNotice: String = "",
    /** 错误提示触发计数 */
    val errorNoticeTick: Int = 0,
    /** 撤销提示（空 = 没有可撤销的操作）：清空队列 / 删除待办后给 4 秒反悔窗口 */
    val undoNotice: String = "",
    /** 撤销提示触发计数 */
    val undoNoticeTick: Int = 0,
    /** 最近批量操作（5.1）：只在本次会话里留着，可回看；最后一条可通过「撤销」回滚 */
    val batchLog: List<BatchOpEntry> = emptyList(),
    /** 省电动画：关掉高频无限动画（玻璃呼吸、待办脉动、绘本浮动），低端机默认开 */
    val powerSavingAnim: Boolean = false,
    /** 无版权连跳计数（>0 表示正在自动跳；任意一首真正出声就清零） */
    val copyrightSkipStreak: Int = 0,
    /** 无版权兜底条（空=不显示）：连跳满上限后给「搜其他版本」出口 */
    val copyrightTip: String = "",
    /** 兜底条触发计数：同一文案再次出现也要重播动画 */
    val copyrightTipTick: Int = 0,
    /** 「搜其他版本」的搜索词（歌名 + 歌手） */
    val copyrightQuery: String = "",
    /** 本次会话内确认拿不到音频的歌曲 id：列表灰显，30 分钟后自动解禁 */
    val unplayableSongIds: Set<Long> = emptySet(),
    // ---------- 6.9.8 模式探索 / 首页封面 ----------
    /** 模式关键词 → 已定位的网易云歌单（含真实封面），进探索页时后台补齐 */
    val modeCards: Map<String, NeteasePlaylistItem> = emptyMap(),
    /** 6.9.11 气氛模式歌单池：每个关键词存多个合格歌单，刷新轮换、打开时拼接扩容 */
    val modePools: Map<String, List<NeteasePlaylistItem>> = emptyMap(),
    /** 本地歌单封面：歌单 ID → 封面来源（正在听/最近听/歌单内随机一首的封面） */
    val localPlaylistCovers: Map<Long, String> = emptyMap(),
    /** 「我喜欢的音乐」卡封面：最近一首红心歌的封面 */
    val likedPlaylistCover: String = ""
)

data class PlayerUiState(
    /** 服务器下发的热搜词；搜索页直接读这里，后台改完下次进搜索即生效 */
    val hotKeywords: List<String> = com.jun.musiccloud.data.RemoteConfigStore.hotKeywords,
    val allSongs: List<Song> = emptyList(),
    val queue: List<Song> = emptyList(),
    /** 首页纵向列表专用：永远是全部音乐（按文件夹/排序/搜索过滤），不受「我喜欢」/歌单视图影响。
     *  播放队列（queue）与浏览列表（homeSongs）解耦，避免点进我喜欢就把首页内容替换掉。 */
    val homeSongs: List<Song> = emptyList(),
    val folders: List<MusicFolder> = emptyList(),
    val selectedFolders: Set<String> = emptySet(),
    val searchQuery: String = "",
    /** 官方输入联想词（5.1）；接口失败/为空时列表为空，UI 退回本地历史+热搜补全 */
    val searchSuggestions: List<String> = emptyList(),
    val sortOrder: SortOrder = SortOrder.TITLE,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    /** 首页曲库打乱种子：每次刷新重新取一个值，让「全部音乐」的默认排序（TITLE）
     *  实际按这个种子混洗，刷新后底部的歌能跑到上面，而不是永远同一批置顶。 */
    val librarySeed: Long = 0L,
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    // 6.9.0：加载反馈 —— 非空 = 正在取源/切换备用/缓冲中，播放页顶部细条显示
    val loadText: String = "",
    val loadBuffered: Float = -1f,
    val loadCanRetry: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val theme: JunTheme = JunTheme.MONO,
    val mode: JunMode = JunMode.LIGHT,
    val systemDark: Boolean = false,
    /** 表面材质：实色 / 毛玻璃 / 液态玻璃 */
    val surfaceStyle: JunSurfaceStyle = JunSurfaceStyle.SOLID,
    /** 界面排布：纵向列表 / 横向卡片 / 堆叠卡片 */
    val layout: JunLayout = JunLayout.LIST,
    /** 唱片墙方向：false = 纵向（默认），true = 横向（宽画布，左右拖动更顺手） */
    val wallHorizontal: Boolean = false,
    /** 模糊浓度倍率，1.0 为基准（同时作用于毛玻璃与液态玻璃） */
    val glassBlur: Float = GLASS_BLUR_DEFAULT,
    /** 背景封面高斯模糊半径（dp），与材质无关，实色下同样生效 */
    val bgBlur: Float = BG_BLUR_DEFAULT,
    /** 歌单行大小：0=紧凑 1=标准 2=宽松 */
    val rowSize: Int = 1,
    /** 歌单行左侧显示封面 */
    val showRowCover: Boolean = false,
    /** 歌单行显示歌手副标题 */
    val showRowArtist: Boolean = true,
    /** 歌单行右下角显示所属歌单名 */
    val showRowPlaylist: Boolean = true,
    /** 搜索框常驻上栏；关闭后点标题才展开，闲置自动收起 */
    val searchPersistent: Boolean = true,
    /** 点歌后自动滑到播放界面。默认**关**：在列表里连点几首试歌时被迫反复看播放页很烦，
     *  想要这个行为的人可以在 设置 → 播放 里打开。 */
    val autoOpenPlayer: Boolean = false,
    // ---------- 6.6.2 新增设置 ----------
    /** 与其他应用同时播放：开启后不主动抢占音频焦点，允许和其他 App 混音 */
    val playWithOthers: Boolean = false,
    /** 大播放器控件可见性（隐藏后仍生效，其余控件居中重排）。默认全显示。 */
    val hideSpeed: Boolean = false,    // 倍速
    val hideComment: Boolean = false,  // 评论
    val hidePitch: Boolean = false,    // 音调
    val hideQuality: Boolean = false,  // 音质
    val hideDownload: Boolean = false, // 下载
    val hideLike: Boolean = false,     // 喜欢
    val hideSimilar: Boolean = false,  // 相似推荐
    val hideRecent: Boolean = false,   // 最近播放
    val hideRepeat: Boolean = false,   // 循环播放
    val hideSleep: Boolean = false,    // 睡眠定时器
    /** 自定义界面可隐藏的入口：隐藏后仍可通过长按音量+ / 长按双击屏幕召唤自定义界面 */
    val hideSearchEntry: Boolean = false,   // 搜索入口
    val hideSettingsEntry: Boolean = false, // 设置入口
    /** 已登录账号名（空 = 未登录，仅本地） */
    val accountName: String = "",
    /** 已登录账号 id（0 = 未登录） */
    val accountUserId: Long = 0L,
    /** 听歌数据同步版本：每次云端同步完成 +1，个人主页据此重新读缓存 */
    val listenSyncVersion: Long = 0L,
    /**
     * 是否已登录（以 token 为准，不再用昵称是否为空判断）。
     * 昵称可能因为同步失败/老数据为空，用昵称判断会把已登录用户当成未登录。
     */
    val accountLoggedIn: Boolean = false,
    /** 账号头像 URL（本地设置，可编辑） */
    val accountAvatar: String = "",
    /** 账号同步中的状态文案（空 = 空闲） */
    val accountStatus: String = "",
    /**
     * 「意见反馈」入口上的小红点：有官方新回复但用户还没看过的条数。
     *
     * 不做服务端已读表：官网那套服务是独立库，为了一个红点多写一次库不划算。
     * 这里由 [refreshFeedbackUnread] 拉一次「我的反馈」，和本地记的时间戳比对得出，
     * 冷启动 / 每次回前台刷新一次，开销就是一个小请求。
     */
    val feedbackUnread: Int = 0,
    /**
     * 6.6.6：服务端记录的上次活跃时间（毫秒，0=未知）。
     *
     * 用户报「后台显示三分钟前已不在线，点开账号却显示在线」—— 两边看的不是同一个数：
     * 后台看心跳表 last_seen，账号页以前只写「已登录」。现在账号页按这个时间显示
     * 「上次活跃：X 前」，与后台同一口径，不会再出现一个说在线一个说离线。
     */
    val accountLastActiveAt: Long = 0L,
    /** 账号请求进行中（登录 / 注册 / 同步 / 注销）：UI 用它禁用按钮并给出加载态，
     *  避免网络慢时用户连点十几次把同一请求打十遍。 */
    val accountBusy: Boolean = false,
    // ---------- 4.0.3 特大事故补偿：10 年 VIP / 内部尊享会员 ----------
    /** 会员等级：0 普通、1 VIP、2 内部尊享 */
    val vipLevel: Int = 0,
    /** 会员名称（服务端下发） */
    val vipName: String = "普通用户",
    /** 会员到期毫秒时间戳（0 = 未领取） */
    val vipUntil: Long = 0L,
    /** 是否已领取补偿 */
    val vipClaimed: Boolean = false,
    /** 领取请求进行中 */
    val vipBusy: Boolean = false,
    /** 本地资料：未登录也能设置的头像（相册选取，仅本机），独立于云端账号 */
    val localAvatar: String = "",
    /** 本地资料：未登录也能设置的昵称（仅本机显示用） */
    val localNickname: String = "",
    // ---------- 网易云账号（第二个登录，可选） ----------
    /** 网易云是否已登录 */
    val neteaseLoggedIn: Boolean = false,
    /** 网易云昵称 */
    val neteaseName: String = "",
    /** 网易云头像 URL */
    val neteaseAvatar: String = "",
    /** 网易云用户 id */
    val neteaseUserId: String = "",
    /** 网易云扫码登录弹层是否展开 */
    val showNeteaseLogin: Boolean = false,
    /** 二维码内容（UI 据此渲染二维码图片） */
    val neteaseQrContent: String = "",
    /** 扫码状态文案（等待扫码 / 已扫码待确认 / 已过期…） */
    val neteaseQrStatus: String = "",
    /** 凭证登录（手机号/邮箱）是否正在请求中 */
    val neteaseCredentialLoading: Boolean = false,
    /** 凭证登录错误提示（空=无错误） */
    val neteaseCredentialError: String = "",
    /** 需要登录的提醒文案（非空即弹提示）：如发表评论前未登录 */
    val loginPrompt: String = "",
    /** 登录提醒触发计数：同一文案再次提醒也会重新弹出动画 */
    val loginPromptTick: Int = 0,
    // ---------- 歌曲评论弹层 ----------
    /** 评论弹层目标歌名（空 = 弹层关闭） */
    val commentsTitle: String = "",
    /** 评论目标歌曲的网易云 id */
    val commentsSongId: String = "",
    val commentsHot: List<NeteaseApi.NsComment> = emptyList(),
    val commentsItems: List<NeteaseApi.NsComment> = emptyList(),
    val commentsTotal: Int = 0,
    val commentsLoading: Boolean = false,
    val commentsPage: Int = 0,
    val commentsHasMore: Boolean = false,
    /** 评论操作提示（发表成功/失败等） */
    val commentsNotice: String = "",
    /** 当前播放歌曲的评论总数（播放页评论按钮右上角红点角标用） */
    val currentCommentCount: Int = 0,
    // ---------- 网易云·每日推荐 / 我的歌单（第二个登录后可用的扩展内容） ----------
    /** 网易云曲目弹层标题（空 = 弹层关闭） */
    val neteaseListTitle: String = "",
    /** 网易云曲目弹层来源说明（副标题） */
    val neteaseListSubtitle: String = "",
    /** 网易云曲目弹层歌曲 */
    val neteaseListSongs: List<Song> = emptyList(),
    val neteaseListLoading: Boolean = false,
    /** 网易云曲目弹层提示（暂无内容 / 需要登录等） */
    val neteaseListNotice: String = "",
    /** 歌手详情页（null = 关闭） */
    val artistDetail: ArtistDetailState? = null,
    /** 关闭曲目弹层后回到哪个列表："toplist" / "playlists" / "" */
    val neteaseListReturnTo: String = "",
    /** 网易云歌单弹层关闭后返回到哪：空=直接回首页，"localPlaylists"=回到本地歌单弹层 */
    val neteasePlaylistsReturnTo: String = "",
    /** 网易云歌单选择弹层是否展开 */
    val showNeteasePlaylists: Boolean = false,
    val neteasePlaylistsLoading: Boolean = false,
    /** 我的网易云歌单 */
    val neteaseMyPlaylists: List<NeteasePlaylistItem> = emptyList(),
    /** 网易云推荐歌单 */
    val neteaseRecPlaylists: List<NeteasePlaylistItem> = emptyList(),
    /** 首页推荐歌单正在续拉（划到底再拉一批） */
    val recsLoadingMore: Boolean = false,
    /**
     * 首页推荐歌单已经拉到底。续拉时服务端没有给新条目就置真，
     * UI 停止再尝试 —— 否则划到底会无限转圈。
     */
    val recommendAllLoaded: Boolean = false,
    /** 网易云排行榜弹层是否展开 */
    val showNeteaseToplist: Boolean = false,
    val neteaseToplistLoading: Boolean = false,
    /** 网易云所有官方榜单 */
    val neteaseToplists: List<NeteasePlaylistItem> = NeteaseApi.defaultToplists()
        .map { NeteasePlaylistItem(it.first, it.second, it.third) },
    /** 首页榜单区是否正在拉取（用于占位显示「正在加载」，而不是「点击加载」） */
    val neteaseChartsLoading: Boolean = false,
    /** 搜索页那排榜单卡片的预览（榜单 + 前 9 首），一次拉好缓存住 */
    val toplistPreviews: List<ToplistPreview> = emptyList(),
    val toplistPreviewsLoading: Boolean = false,
    /** 歌单行右下角显示点赞角标 */
    val showLikedBadge: Boolean = false,
    /** 已点赞歌曲 ID 集合 */
    val likedSongIds: Set<Long> = emptySet(),
    /** 已点赞歌曲的完整元数据（含搜索出来的、曲库列表里没有的歌），用于「我喜欢」列表 */
    val likedSongs: List<Song> = emptyList(),
    /** 网易云「我喜欢」歌曲（登录后可选来源时使用，独立于 neteaseListSongs 避免弹层冲突） */
    val neteaseLikedSongs: List<Song> = emptyList(),
    /** 网易云「我喜欢」加载中 */
    val neteaseLikedLoading: Boolean = false,
    /** 点赞后的轻提示文案（空 = 不显示） */
    val likeNotice: String = "",
    /** 轻提示触发计数：同一文案再次点赞也会重新弹出动画 */
    val likeNoticeTick: Int = 0,
    /** 队列仅显示点赞歌曲（我的收藏筛选） */
    val onlyLiked: Boolean = false,
    // ---------- 图书馆模式 ----------
    /** 只播放轻音乐 / 纯音乐 / 白噪音 / lofi，带歌词的歌在入队前就被筛掉 */
    val libraryMode: Boolean = false,
    /** 正在筛选纯音乐（开启模式 / 播放新列表后的预筛阶段） */
    val libraryFiltering: Boolean = false,
    /** 图书馆模式轻提示（开启 / 筛选完成 / 没有找到纯音乐等） */
    val libraryNotice: String = "",
    /** 轻提示触发计数：同一文案连续出现也能重新弹出 */
    val libraryNoticeTick: Int = 0,
    // ---------- 6.9.8 模式探索 ----------
    /** 播放列表弹层「我喜欢」来源：0=本地，1=网易云。持久化，关闭弹层不重置。 */
    val likedSource: Int = 0,
    /** 播放列表弹层「歌单」来源：0=本地，1=网易云。持久化，关闭弹层不重置。 */
    val playlistSource: Int = 0,
    val showSettings: Boolean = false,
    val loading: Boolean = true,
    /** 正在刷新音乐库（下拉刷新/刷新按钮），驱动 UI 指示器 */
    val refreshing: Boolean = false,
    /** 海报墙果冻动效：磁贴位移/尺寸变化带弹性果冻拉伸 */
    val jellyAnim: Boolean = false,
    /**
     * 6.3：整体动画速度倍率（1.0 为设计基准，越小越快）。
     * 真正的数值在 JunMotion 里；这里存一份只为设置页能回显当前档位。
     * 注意：这是"设备偏好"，不进账号同步（两台手机性能不同，快慢本来就该各调各的）。
     */
    val motionSpeed: Float = 0.85f,
    /** 6.6 动效风格：JELLY 果冻Q弹 / SMOOTH 顺滑 / BOUNCY 超弹 */
    val motionStyle: String = "JELLY",
    /** 6.3：下载格式（DownloadFormat 的 name），同样只在本机生效 */
    val downloadFormat: String = "FOLLOW",
    /** 睡眠定时器总时长，0 表示未启用 */
    val sleepTimerMs: Long = 0L,
    /** 睡眠定时器到点的绝对时间戳（System.currentTimeMillis 基准） */
    val sleepTimerEndMs: Long = 0L,
    /** 睡眠定时器"播完这首停"：开启后当前曲自然结束即暂停 */
    val sleepStopAfterSong: Boolean = false,
    /** 推荐歌曲列表 */
    val recommendations: List<Song> = emptyList(),
    /** 最近播放会话列表（按时间倒序，最新在前）：首页「最近播放」横向滑动区 */
    val recentPlaybacks: List<RecentPlayback> = emptyList(),
    /** 听歌报告弹层是否展开 */
    val showReport: Boolean = false,
    /** 听歌报告数据（按 [ListenReport.range] 汇总） */
    val report: ListenReport = ListenReport(),
    /** 推荐版本计数器：每次重新计算推荐时递增，用于触发 Crossfade 动画 */
    val recommendationsVersion: Int = 0,
    /**
     * 搜索页「猜你喜欢」固定的一批。
     *
     * 为什么不直接复用 [recommendations]：那个字段每播放一首歌都会被追加一条
     * （addToRecommendationsIfAppropriate），所以「在排行榜里点一首，猜你喜欢就多一首」
     * 其实是同一条数据被两处界面共用出来的错觉。这里给搜索页单独一份，
     * 只由"进页面时抽样 + 用户主动换一批"写，播放行为碰不到它。
     */
    val searchGuess: List<Song> = emptyList(),
    /**
     * 6.3.2：首页「猜你喜欢」，**免登录**。
     *
     * 为什么要有它：用户要求「需要登录的就不要加上去了」。每日推荐必须登录，
     * 未登录时那一块就整块消失，首页第二屏会突然空掉。猜你喜欢用纯本地的
     * [Recommender]（基于播放次数/跳过/收藏算分）算 12 首，不发任何账号请求，
     * 未登录也有内容。
     */
    val homeGuessSongs: List<Song> = emptyList(),
    /**
     * 6.3.2：首页「每日推荐」的预览样本（只取前 12 首，用于渲染入口卡的封面与首数）。
     *
     * 不复用 `neteaseListSongs`：那一份是列表弹层的数据源，首页读它会让"点开弹层"
     * 和"首页卡片"互相覆盖（弹层换成相似推荐时首页卡片也跟着变）。这里单独一份。
     */
    val neteaseDailyPreview: List<Song> = emptyList(),
    /**
     * 6.3.8：首页「每日新歌」（免登录的 `/personalized/newsong`）。
     *
     * 与 [neteaseDailyPreview] 的区别：那个是**登录后**的每日推荐（30 首），
     * 这个是任何人都能拿到的公开新歌池。首页把它按日期轮换 8 首，
     * 做到"每天打开都不一样"而不需要账号。
     */
    val homeNewSongs: List<Song> = emptyList(),
    /**
     * 6.3.8：首页风格栏目（说唱 / 电音 / 古典 …）的预览。
     *
     * key = 栏目 id（榜单 id），value = 该榜前若首。用 Map 而不是 List：
     * 栏目是并行加载的，先回来的先显示，不能等齐了再一起上屏。
     */
    val homeChannels: Map<String, List<Song>> = emptyMap(),
    /** 首页「粤语」栏目（搜索来的歌单曲目，按日期轮换） */
    val homeCantonese: List<Song> = emptyList(),
    /** 首页热门歌手（免登录 /top/artists，按热度排序、已排除不可播的歌手） */
    val homeHotArtists: List<NeteaseApi.NsArtist> = emptyList(),
    /** 上次拉到热门歌手的时间戳（0 = 没拉过）。首页据此判断"要不要再刷一次" */
    val hotArtistsFetchedAt: Long = 0L,
    /** 首页新专辑（免登录 /album/new） */
    val homeNewAlbums: List<NeteaseApi.NsAlbum> = emptyList(),
    /** 首页轮换内容是否已加载完（用于"今天 X 月 X 日更新"的文案） */
    val homeFeedDay: String = "",
    /** 推荐探索度 0-1（0=纯偏好，1=纯探索） */
    val exploration: Float = 0.3f,
    /** 所有自定义歌单（按创建顺序） */
    val playlists: List<Playlist> = emptyList(),
    /** 6.4：播放库体检报告（null = 还没算过，弹层据此显示「正在整理…」） */
    val libraryHealth: Playlists.LibraryHealth? = null,
    val libraryAuditing: Boolean = false,
    /** 当前选中的歌单 ID，null = 显示全部（文件夹/收藏视图） */
    val activePlaylistId: Long? = null,
    /** 在线搜索结果（独立于曲库队列，避免搜索结果污染首页列表） */
    val searchResults: List<Song> = emptyList(),
    /** 搜索进行中（驱动搜索页加载动画） */
    val searching: Boolean = false,
    /** 搜索分类 tab：0=单曲 1=歌手 2=专辑 3=歌单 */
    val searchTab: Int = 0,
    /**
     * 6.9：搜索源。"netease"=网易云官方（默认，四分类齐全）；
     * "qq"=QQ音乐公开搜索（只搜单曲，songId 带 qq: 前缀直达换链）。
     * 切源由 [PlayerViewModel.setSearchSource] 负责，会清结果重搜。
     */
    val searchSource: String = "netease",
    /** 歌手搜索结果 */
    val searchArtists: List<NeteaseApi.NsArtist> = emptyList(),
    /** 专辑搜索结果 */
    val searchAlbums: List<NeteaseApi.NsAlbum> = emptyList(),
    /** 歌单搜索结果 */
    val searchPlaylistsFound: List<NeteaseApi.NsPlaylistSearch> = emptyList(),
    /** 单曲搜索结果是否还有下一页（滚动到底加载更多） */
    val searchHasMore: Boolean = false,
    /** 正在加载下一页（底部显示小转圈） */
    val searchLoadingMore: Boolean = false,
    /** 播放偏好 + 运行时提示这一大类状态（5.2featR3 拆出去，见 [PlayerExtras] 的说明） */
    val extras: PlayerExtras = PlayerExtras()

) {
    // ---------- extras 的平铺读法（拆字段只是为了绕开 dex 上限，读取方一行都不用改） ----------
    val copyrightSkipStreak: Int get() = extras.copyrightSkipStreak
    val copyrightTip: String get() = extras.copyrightTip
    val copyrightTipTick: Int get() = extras.copyrightTipTick
    val copyrightQuery: String get() = extras.copyrightQuery
    val unplayableSongIds: Set<Long> get() = extras.unplayableSongIds
    val visualizerEnabled: Boolean get() = extras.visualizerEnabled
    val tiltSpectrum: Boolean get() = extras.tiltSpectrum
    val coverShake: Boolean get() = extras.coverShake
    val coverLyric: Boolean get() = extras.coverLyric
    val coverTopLyric: Boolean get() = extras.coverTopLyric
    val karaokeLyric: Boolean get() = extras.karaokeLyric
    val lyricAlign: Int get() = extras.lyricAlign
    val lyricFont: Int get() = extras.lyricFont
    val lyricFontSize: Float get() = extras.lyricFontSize
    val lyricStage: Int get() = extras.lyricStage
    val lyricMotion: Int get() = extras.lyricMotion
    val lyricDisplayMode: Int get() = extras.lyricDisplayMode
    val lyricContext: Int get() = extras.lyricContext
    val lyricQuality: Int get() = extras.lyricQuality
    val lyricBeatFx: Boolean get() = extras.lyricBeatFx
    val lyricParticles: Boolean get() = extras.lyricParticles
    val lyricBassGlow: Boolean get() = extras.lyricBassGlow
    val lyricChorusFx: Boolean get() = extras.lyricChorusFx
    val lyricChorusBoost: Float get() = extras.lyricChorusBoost
    val lyricCoverTint: Boolean get() = extras.lyricCoverTint
    val lyricTintStrength: Float get() = extras.lyricTintStrength
    val lyricOffsetMs: Int get() = extras.lyricOffsetMs
    val lyricSongOffsets: Map<Long,Int> get() = extras.lyricSongOffsets
    val lyricStaffCard: Boolean get() = extras.lyricStaffCard
    val lyricLineSpacing: Float get() = extras.lyricLineSpacing
    val lyricLetterSpacing: Float get() = extras.lyricLetterSpacing
    val lyricWeight: Int get() = extras.lyricWeight
    val lyricOutline: Float get() = extras.lyricOutline
    val lyricTexture: Int get() = extras.lyricTexture
    val lyricGlowStyle: Int get() = extras.lyricGlowStyle
    val lyricGlowStrength: Float get() = extras.lyricGlowStrength
    val lyricCoverDepth: Boolean get() = extras.lyricCoverDepth
    val lyricTuning: List<Int> get() = extras.lyricTuning
    val lyricStageProfile: StageProfile get() = extras.lyricStageProfile
    val lyricFocus: Float get() = extras.lyricFocus
    val lyricBeatCamera: Boolean get() = extras.lyricBeatCamera
    val lyricBeatCameraStrength: Float get() = extras.lyricBeatCameraStrength
    val lyricLongFormMode: Int get() = extras.lyricLongFormMode
    val lyricVideoUri: String get() = extras.lyricVideoUri
    val lyricVideoAlpha: Float get() = extras.lyricVideoAlpha
    val lyricSemanticWrap: Boolean get() = extras.lyricSemanticWrap
    val settingsAdvanced: Boolean get() = extras.settingsAdvanced
    val reduceMotion: Boolean get() = extras.reduceMotion
    val lyricFrameStats: Boolean get() = extras.lyricFrameStats
    val lyricGuideSeen: Boolean get() = extras.lyricGuideSeen
    val lyricStagePerSong: Boolean get() = extras.lyricStagePerSong
    val favoriteStages: List<Int> get() = extras.favoriteStages
    val lyricTranslation: Boolean get() = extras.lyricTranslation
    val lyricRomanization: Boolean get() = extras.lyricRomanization
    val fxPreset: Int get() = extras.fxPreset
    val automixEnabled: Boolean get() = extras.automixEnabled
    val automixStyle: Int get() = extras.automixStyle
    val localFolder: String get() = extras.localFolder
    val localSongCount: Int get() = extras.localSongCount
    val localScanning: Boolean get() = extras.localScanning
    val localFolderCover: Boolean get() = extras.localFolderCover
    val localIgnore: Boolean get() = extras.localIgnore
    val localAliasRules: String get() = extras.localAliasRules
    val localSplitArtists: Boolean get() = extras.localSplitArtists
    val localScanNotice: String get() = extras.localScanNotice
    val aiThemeBusy: Boolean get() = extras.aiThemeBusy
    val aiThemeName: String get() = extras.aiThemeName
    val remoteEnabled: Boolean get() = extras.remoteEnabled
    val remoteUrl: String get() = extras.remoteUrl
    val audioQuality: AudioQuality get() = extras.audioQuality
    val qualityFlash: String get() = extras.qualityFlash
    val qualityFlashTick: Int get() = extras.qualityFlashTick
    val fxFlash: String get() = extras.fxFlash
    val fxFlashTick: Int get() = extras.fxFlashTick
    val streamCacheBytes: Long get() = extras.streamCacheBytes
    val cacheNotice: String get() = extras.cacheNotice
    val cacheNoticeTick: Int get() = extras.cacheNoticeTick
    val playbackSpeedBp: Int get() = extras.playbackSpeedBp
    val cloudSyncStatus: String get() = extras.cloudSyncStatus
    val uiFont: Int get() = extras.uiFont
    val topBarAutoHide: Boolean get() = extras.topBarAutoHide
    /** 上栏静止多少秒后淡出（3~30） */
    val topBarHideSeconds: Int get() = extras.topBarHideSeconds
    val cornerBase: Float get() = extras.cornerBase
    val fontScale: Float get() = extras.fontScale
    val fadeEnabled: Boolean get() = extras.fadeEnabled
    val fadeMs: Long get() = extras.fadeMs
    val hapticsEnabled: Boolean get() = extras.hapticsEnabled
    val showRecommendations: Boolean get() = extras.showRecommendations
    val showSortBar: Boolean get() = extras.showSortBar
    val silenceSkip: Boolean get() = extras.silenceSkip
    val bassHaptics: Boolean get() = extras.bassHaptics
    val bassHapticsIntensity: Int get() = extras.bassHapticsIntensity
    val bassHapticsPulseMs: Int get() = extras.bassHapticsPulseMs
    val bassHapticsSensitivity: Int get() = extras.bassHapticsSensitivity
    val bassHapticsLowCutoff: Int get() = extras.bassHapticsLowCutoff
    val bassHapticsAdaptive: Boolean get() = extras.bassHapticsAdaptive
    val virtualBass: Boolean get() = extras.virtualBass
    val virtualBassStrength: Int get() = extras.virtualBassStrength
    val rangeEnhancer: Boolean get() = extras.rangeEnhancer
    val rangeEnhancerStrength: Int get() = extras.rangeEnhancerStrength
    val virtualSurround: Boolean get() = extras.virtualSurround
    val virtualSurroundStrength: Int get() = extras.virtualSurroundStrength
    val spatialWide: Boolean get() = extras.spatialWide
    val spatialWideStrength: Int get() = extras.spatialWideStrength
    val safeLimiter: Boolean get() = extras.safeLimiter
    val safeLimiterStrength: Int get() = extras.safeLimiterStrength
    val loudnorm: Boolean get() = extras.loudnorm
    val loudnormStrength: Int get() = extras.loudnormStrength
    val customPrimaryArgb: Long get() = extras.customPrimaryArgb
    val customSecondaryArgb: Long get() = extras.customSecondaryArgb
    val customBackgroundUri: String? get() = extras.customBackgroundUri
    val downloadNotice: String get() = extras.downloadNotice
    val downloadNoticeTick: Int get() = extras.downloadNoticeTick
    val downloadPath: String get() = extras.downloadPath
    val downloadPathConfirmed: Boolean get() = extras.downloadPathConfirmed
    val miniPlayerPersist: Boolean get() = extras.miniPlayerPersist
    val downloadTasks: List<DownloadTask> get() = extras.downloadTasks
    val todos: List<ListenTodo> get() = extras.todos
    val errorNotice: String get() = extras.errorNotice
    val errorNoticeTick: Int get() = extras.errorNoticeTick
    val undoNotice: String get() = extras.undoNotice
    val undoNoticeTick: Int get() = extras.undoNoticeTick
    val batchLog: List<BatchOpEntry> get() = extras.batchLog
    val powerSavingAnim: Boolean get() = extras.powerSavingAnim

    /** 只改 extras 里的字段：写回时不必拼那张 260 个寄存器的大 copy。 */
    fun withExtras(transform: PlayerExtras.() -> PlayerExtras): PlayerUiState = copy(extras = extras.transform())

    val currentSong: Song? get() = queue.getOrNull(currentIndex)

    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * 播放页音质按钮（5.2feat R4）：**只显示当前选中的档位名**——
 * 标准音质 / 极高音质 / 无损音质 / Hi-Res 音质 / 自动。
 *
 * 之前这枚按钮显示的是"实际解析到什么"（320k MP3 / 在线 / 解析中…），用户明确说不要：
 * 音质按钮的本职工作是告诉他"现在选的是哪一档"，点成无损就该写无损。
 * 真实解析结果并没有被藏起来：切档那一刻浮在按钮上方的闪条（qualityFlash）
 * 写的就是"想要哪档 · 实际拿到哪档"，拿不到高档位会照样在闪条上说清落到了哪一档。
 */
val PlayerUiState.qualityBadge: String
    get() = audioQuality.badgeLabel

/** 搜索页榜单卡片的一条：榜单名 + 它自己的前几首歌 */
data class ToplistPreview(val id: String, val name: String, val songs: List<Song>)

/**
 * 搜索页每张榜单卡列几首歌：卡片高度有限（再多就要在横滑卡里再套一层竖滚），
 * 6.0 从 9 提到 12 —— 用户反馈"每个榜单怎么就九首"，预览这一屏先给够，
 * 真正的整榜交给卡片上的「播放」（[PlayerViewModel.playToplistFull] 取全量）。
 * 预览接口 playlistPreview 的硬上限是 50，这里留足余量。
 * 6.9：12 → 15 —— 用户仍嫌预览太少；15 行卡片还能接受，再往上就该做卡片内滚动。
 */
private const val PREVIEW_SONGS = 15

/** 网易云歌单条目（id / 名称 / 封面） */
data class NeteasePlaylistItem(
    val id: String,
    val name: String,
    val cover: String = "",
    val playCount: Long = 0L,
    /**
     * 6.3.9：卡片上那一行极细的辅助字（榜单卡用它显示「每天更新」，歌单卡留空）。
     * 用户明确说过"不要标格外的字"——所以这里只放**接口原样给的更新频率**，
     * 不放我们自己造的任何标签。
     */
    val subtitle: String = ""
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    init {
        // 必须在下面读 prefs 之前：新装首启把内置默认设置先灌进 jun_prefs，
        // 否则这一批 readXxx() 读到的还是代码硬编码值，首帧会闪一下旧样式。
        DataTransfer.seedDefaultSettingsIfFirstRun(app)
        // 记下"这台机器还没被人动过"的指纹：自动云同步靠它区分「新装/重装」与
        // 「用户自己调过」，前者静默恢复云端，后者一定要人来做选择。
        SettingsCloud.captureDefaultsIfMissing(app)
        // 6.3：动画速度必须在任何界面动起来之前写进 JunMotion —— 它是 @Volatile 静态字段，
        // 首帧的 animateFloatAsState 就会读。放到下面 _state 构造之后也能生效，
        // 但会有一帧"按默认速度起步、第二帧突然变速"的跳动。
        JunMotionSpeed.apply(
            app.getSharedPreferences(
                com.jun.musiccloud.data.AccountScope.LEGACY_PREFS, Context.MODE_PRIVATE
            ).getFloat(JunMotionSpeed.KEY, JunMotionSpeed.DEFAULT)
        )
        // 6.6：动效风格同样要在首帧之前就位（JunMotion.style 是静态字段，
        // 首帧弹簧就按它取参）。没存过时按老的果冻开关推断，老用户设置不丢。
        // 注意这里必须现取 SharedPreferences：类里的 prefs 要到 _state 之后才初始化。
        val bootPrefs = app.getSharedPreferences(
            com.jun.musiccloud.data.AccountScope.LEGACY_PREFS, Context.MODE_PRIVATE
        )
        com.jun.musiccloud.ui.JunMotion.applyStyle(
            com.jun.musiccloud.ui.JunMotion.MotionStyle.of(
                bootPrefs.getString(KEY_MOTION_STYLE, null)
                    ?: if (bootPrefs.getBoolean(KEY_JELLY_ANIM, false)) "JELLY" else "SMOOTH"
            )
        )
        // 账号凭证同样在下面读 accountName 之前加载，冷启动即恢复登录态
        AccountStore.load(app)
        // 6.3：把本地数据层切到"当前登录的这个人"的分区，然后做一次老数据搬迁。
        // 顺序不能反：先 bind 再 migrate —— 迁移是往当前分区里写的。
        // 为什么在 init 里做而不是等登录成功：冷启动时人已经是登录态了，
        // 数据层却还停在上次的分区（或者访客分区），首页一渲染就是别人的歌单。
        AccountScope.bind(app, AccountStore.userId)
        AccountScope.migrateLegacyIfNeeded(app)
        // 网易云会话（第二个登录）同样提前加载，冷启动即恢复登录态
        NeteaseStore.load(app)
        // 本地资料（未登录也能设置的头像/昵称），冷启动即恢复
        LocalProfileStore.load(app)
        // 音质档位快照同样提前加载。播放地址是音频代理在 socket 线程里解析的，
        // 那边只读 AudioQualityStore.current()，不读 SharedPreferences：
        // 以前只有播放服务起来才 load()，用户没播过歌就进播放页连点档位时，
        // 界面记的档位和快照里的档位会错位（R4 顺手修掉）。
        runCatching { AudioQualityStore.load(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)) }
    }

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        // 6.9.6：节拍电影镜头一次性迁移 —— 老版本默认 true 且可能已被云同步固化到
        // prefs，仅改读默认值救不了存量用户；而它的 translationX=sin(ms/90) 横向
        // 摆动正是"歌词一直抖动"的主源。升级首启强制关一次，想开的用户在设置里
        // 手动打开（打开会正常写 prefs，迁移只跑一次不会再关它）。
        if (!prefs.getBoolean("beatcam_off_migrated_696", false)) {
            prefs.edit()
                .putBoolean(KEY_LYRIC_BEAT_CAMERA, false)
                .putBoolean("beatcam_off_migrated_696", true)
                .apply()
        }
    }

    /** 下载管理器：任务表 + 系统 DownloadManager + 手动 HTTP 兜底，独立成类 */
    private val downloadManager by lazy {
        DownloadManager(
            app = getApplication(),
            prefs = prefs,
            state = _state,
            scope = viewModelScope
        )
    }

    private val _state = MutableStateFlow(
        PlayerUiState(
            // 6.9.3：先把上次退出时的进度铺上。进程被杀后曲库是异步加载的，
            // 以前这半秒里进度条恒为 0:00，用户看到的就是"进度回到原点了"。
            positionMs = prefs.getLong(KEY_LAST_POS, 0L).coerceAtLeast(0L),
            theme = readTheme(),
            mode = readMode(),
            surfaceStyle = readSurfaceStyle(),
            sortOrder = readSortOrder(),
            sortDirection = readSortDirection(),
            layout = readLayout(),
            wallHorizontal = prefs.getBoolean(KEY_WALL_HORIZONTAL, false),
            glassBlur = prefs.getFloat(KEY_GLASS_BLUR, GLASS_BLUR_DEFAULT)
                .coerceIn(GLASS_BLUR_MIN, GLASS_BLUR_MAX),
            bgBlur = prefs.getFloat(KEY_BG_BLUR, BG_BLUR_DEFAULT)
                .coerceIn(BG_BLUR_MIN, BG_BLUR_MAX),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = readRepeatMode(),
            rowSize = prefs.getInt(KEY_ROW_SIZE, 0).coerceIn(0, 2),
            showRowCover = prefs.getBoolean(KEY_ROW_COVER, true),
            showRowArtist = prefs.getBoolean(KEY_ROW_ARTIST, true),
            showRowPlaylist = prefs.getBoolean(KEY_ROW_PLAYLIST, true),
            searchPersistent = prefs.getBoolean(KEY_SEARCH_PERSISTENT, true),
            autoOpenPlayer = prefs.getBoolean(KEY_AUTO_OPEN_PLAYER, false),
            playWithOthers = prefs.getBoolean(KEY_PLAY_WITH_OTHERS, false),
            hideSpeed = prefs.getBoolean(KEY_HIDE_SPEED, false),
            hideComment = prefs.getBoolean(KEY_HIDE_COMMENT, false),
            hidePitch = prefs.getBoolean(KEY_HIDE_PITCH, false),
            hideQuality = prefs.getBoolean(KEY_HIDE_QUALITY, false),
            hideDownload = prefs.getBoolean(KEY_HIDE_DOWNLOAD, false),
            hideLike = prefs.getBoolean(KEY_HIDE_LIKE, false),
            hideSimilar = prefs.getBoolean(KEY_HIDE_SIMILAR, false),
            hideRecent = prefs.getBoolean(KEY_HIDE_RECENT, false),
            hideRepeat = prefs.getBoolean(KEY_HIDE_REPEAT, false),
            hideSleep = prefs.getBoolean(KEY_HIDE_SLEEP, false),
            hideSearchEntry = prefs.getBoolean(KEY_HIDE_SEARCH_ENTRY, false),
            hideSettingsEntry = prefs.getBoolean(KEY_HIDE_SETTINGS_ENTRY, false),
            accountName = AccountStore.username,
            accountUserId = AccountStore.userId,
            accountLoggedIn = AccountStore.isLoggedIn,
            accountAvatar = AccountStore.avatar,
            vipLevel = prefs.getInt(KEY_VIP_LEVEL, 0),
            vipName = prefs.getString(KEY_VIP_NAME, "普通用户") ?: "普通用户",
            vipUntil = prefs.getLong(KEY_VIP_UNTIL, 0L),
            vipClaimed = prefs.getBoolean(KEY_VIP_CLAIMED, false),
            localAvatar = LocalProfileStore.avatar,
            localNickname = LocalProfileStore.nickname,
            neteaseLoggedIn = NeteaseStore.isLoggedIn,
            neteaseName = NeteaseStore.nickname,
            neteaseAvatar = NeteaseStore.avatar,
            neteaseUserId = NeteaseStore.userId,
            showLikedBadge = prefs.getBoolean(KEY_LIKED_BADGE, true),
            libraryMode = prefs.getBoolean(KEY_LIBRARY_MODE, false),
            exploration = prefs.getFloat(KEY_EXPLORATION, 0.3f),
            jellyAnim = prefs.getBoolean(KEY_JELLY_ANIM, false),
            // 6.6：风格没存过时按老的果冻开关推断，老用户设置不丢
            motionStyle = prefs.getString(KEY_MOTION_STYLE, null)
                ?: if (prefs.getBoolean(KEY_JELLY_ANIM, false)) "JELLY" else "SMOOTH",
            motionSpeed = prefs.getFloat(JunMotionSpeed.KEY, JunMotionSpeed.DEFAULT)
                .coerceIn(0.4f, 2.5f),
            downloadFormat = DownloadFormatStore.load(app).name,
            selectedFolders = readSelectedFolders(),
            extras = PlayerExtras(                visualizerEnabled = prefs.getBoolean(KEY_VISUALIZER, false),
                tiltSpectrum = prefs.getBoolean(KEY_TILT_SPECTRUM, false),
                coverShake = prefs.getBoolean(KEY_COVER_SHAKE, false),
                coverLyric = prefs.getBoolean(KEY_COVER_LYRIC, false),
                coverTopLyric = prefs.getBoolean(KEY_COVER_TOP_LYRIC, false),
                karaokeLyric = prefs.getBoolean(KEY_KARAOKE_LYRIC, true),
                lyricAlign = prefs.getInt(KEY_LYRIC_ALIGN, 1).coerceIn(0, 2),
                lyricFont = prefs.getInt(KEY_UI_FONT, prefs.getInt(KEY_LYRIC_FONT, 0)).coerceIn(0, FONT_INDEX_MAX),
                lyricFontSize = prefs.getFloat(KEY_LYRIC_FONT_SIZE, 20f).coerceIn(14f, 32f),
                lyricStage = prefs.getInt(KEY_LYRIC_STAGE, 0).coerceIn(0, 13),
                lyricMotion = prefs.getInt(KEY_LYRIC_MOTION, 1).coerceIn(0, 6),
                lyricDisplayMode = prefs.getInt(KEY_LYRIC_DISPLAY_MODE, 2).coerceIn(0, 4),
                lyricContext = prefs.getInt(KEY_LYRIC_CONTEXT, 2).coerceIn(0, 6),
                lyricQuality = prefs.getInt(KEY_LYRIC_QUALITY, 0).coerceIn(0, 3),
                lyricBeatFx = prefs.getBoolean(KEY_LYRIC_BEAT_FX, true),
                lyricParticles = prefs.getBoolean(KEY_LYRIC_PARTICLES, true),
                lyricBassGlow = prefs.getBoolean(KEY_LYRIC_BASS_GLOW, true),
                lyricChorusFx = prefs.getBoolean(KEY_LYRIC_CHORUS_FX, true),
                lyricChorusBoost = prefs.getFloat(KEY_LYRIC_CHORUS_BOOST, 0.7f).coerceIn(0f, 1f),
                lyricCoverTint = prefs.getBoolean(KEY_LYRIC_COVER_TINT, false),
                lyricTintStrength = prefs.getFloat(KEY_LYRIC_TINT_STRENGTH, 0.65f).coerceIn(0f, 1f),
                lyricOffsetMs = prefs.getInt(KEY_LYRIC_OFFSET, 0).coerceIn(-2000, 2000),
                lyricSongOffsets = readSongOffsets(),
                lyricStaffCard = prefs.getBoolean(KEY_LYRIC_STAFF_CARD, true),
                lyricLineSpacing = prefs.getFloat(KEY_LYRIC_LINE_SPACING, 1.18f).coerceIn(1f, 1.8f),
                lyricLetterSpacing = prefs.getFloat(KEY_LYRIC_LETTER_SPACING, 0f).coerceIn(-0.04f, 0.12f),
                lyricWeight = prefs.getInt(KEY_LYRIC_WEIGHT, 1).coerceIn(0, 2),
                lyricOutline = prefs.getFloat(KEY_LYRIC_OUTLINE, 0f).coerceIn(0f, 1f),
                lyricTexture = prefs.getInt(KEY_LYRIC_TEXTURE, 0).coerceIn(0, 3),
                lyricGlowStyle = prefs.getInt(KEY_LYRIC_GLOW_STYLE, 1).coerceIn(0, 3),
                lyricGlowStrength = prefs.getFloat(KEY_LYRIC_GLOW_STRENGTH, 0.6f).coerceIn(0f, 1f),
                lyricCoverDepth = prefs.getBoolean(KEY_LYRIC_COVER_DEPTH, false),
                lyricTuning = readTuning(prefs.getInt(KEY_LYRIC_STAGE, 0).coerceIn(0, 13)),
                lyricStageProfile = readStageProfile(prefs.getInt(KEY_LYRIC_STAGE, 0).coerceIn(0, 13)),
                lyricFocus = prefs.getFloat(KEY_LYRIC_FOCUS, 0.38f).coerceIn(0.24f, 0.62f),
                // 6.9.5：节拍电影镜头默认关（用户实测歌词页"抖动的太狠"——
                // 每个节拍整页缩放脉动，在歌词滚动/行入场动画上叠加后低端机
                // 抖动感明显）。想开的用户在 设置 → 触感与界面动效 里手动打开。
                lyricBeatCamera = prefs.getBoolean(KEY_LYRIC_BEAT_CAMERA, false),
                lyricBeatCameraStrength = prefs.getFloat(KEY_LYRIC_BEAT_CAMERA_STRENGTH, 0.5f).coerceIn(0f, 1f),
                lyricLongFormMode = prefs.getInt(KEY_LYRIC_LONG_FORM, 0).coerceIn(0, 2),
                lyricVideoUri = prefs.getString(KEY_LYRIC_VIDEO_URI, "") ?: "",
                lyricVideoAlpha = prefs.getFloat(KEY_LYRIC_VIDEO_ALPHA, 0.42f).coerceIn(0f, 0.9f),
                lyricSemanticWrap = prefs.getBoolean(KEY_LYRIC_SEMANTIC_WRAP, true),
                settingsAdvanced = prefs.getBoolean(KEY_SETTINGS_ADVANCED, false),
                reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false),
                lyricFrameStats = prefs.getBoolean(KEY_LYRIC_FRAME_STATS, false),
                lyricGuideSeen = prefs.getBoolean(KEY_LYRIC_GUIDE_SEEN, false),
                lyricStagePerSong = prefs.getBoolean(KEY_STAGE_PER_SONG, false),
                favoriteStages = readFavoriteStages(),
                lyricTranslation = prefs.getBoolean(KEY_LYRIC_TRANSLATION, true),
                lyricRomanization = prefs.getBoolean(KEY_LYRIC_ROMANIZATION, false),
                fxPreset = prefs.getInt(KEY_FX_PRESET, -1),
                automixEnabled = prefs.getBoolean(KEY_AUTOMIX, false),
                automixStyle = prefs.getInt(KEY_AUTOMIX_STYLE, 0).coerceIn(0, 2),
                localFolder = prefs.getString(KEY_LOCAL_FOLDER, "") ?: "",
                localFolderCover = prefs.getBoolean(KEY_LOCAL_COVER, true),
                localIgnore = prefs.getBoolean(KEY_LOCAL_IGNORE, true),
                localAliasRules = prefs.getString(KEY_LOCAL_ALIAS, "") ?: "",
                localSplitArtists = prefs.getBoolean(KEY_LOCAL_SPLIT, false),
                remoteEnabled = prefs.getBoolean(KEY_REMOTE_ENABLED, false),
                // 5.2：音质档位与倍速的入口都在播放页，这里只需要把盘上的值读回状态
            audioQuality = AudioQuality.of(prefs.getString(AudioQuality.PREF_KEY, null), AudioQuality.STANDARD),
                playbackSpeedBp = prefs.getInt(MusicService.KEY_PLAYBACK_SPEED_BP, 100),
                aiThemeName = prefs.getString(KEY_AI_THEME_NAME, "") ?: "",
                uiFont = prefs.getInt(KEY_UI_FONT, 0).coerceIn(0, FONT_INDEX_MAX),
                topBarAutoHide = prefs.getBoolean(KEY_TOPBAR_AUTOHIDE, true),
                topBarHideSeconds = prefs.getInt(KEY_TOPBAR_HIDE_SEC, 10).coerceIn(3, 30),
                cornerBase = prefs.getFloat(KEY_CORNER_BASE, 38f),
                fontScale = prefs.getFloat(KEY_FONT_SCALE, 1f).coerceIn(0.85f, 1.30f),
                fadeEnabled = prefs.getBoolean(FadeController.KEY_ENABLED, false),
                fadeMs = prefs.getLong(FadeController.KEY_FADE_MS, FadeController.DEFAULT_FADE_MS),
                hapticsEnabled = prefs.getBoolean(Haptics.KEY_ENABLED, true),
                showRecommendations = prefs.getBoolean(KEY_SHOW_RECS, true),
                showSortBar = prefs.getBoolean(KEY_SHOW_SORT_BAR, false),
                silenceSkip = prefs.getBoolean(SilenceSkipper.KEY_ENABLED, false),
                bassHaptics = prefs.getBoolean(BassHaptics.KEY_ENABLED, false),
                bassHapticsIntensity = prefs.getInt(BassHaptics.KEY_INTENSITY, 1).coerceIn(0, 2),
                bassHapticsPulseMs = prefs.getInt(BassHaptics.KEY_PULSE_MS, 20).coerceIn(1, 30),
                bassHapticsSensitivity = prefs.getInt(BassHaptics.KEY_SENSITIVITY, 5).coerceIn(1, 10),
                bassHapticsLowCutoff = prefs.getInt(BassHaptics.KEY_LOW_CUTOFF, 250).coerceIn(120, 320),
                bassHapticsAdaptive = prefs.getBoolean(BassHaptics.KEY_ADAPTIVE, false),
                virtualBass = prefs.getBoolean(VirtualBass.KEY_ENABLED, false),
                virtualBassStrength = prefs.getInt(VirtualBass.KEY_STRENGTH, 50).coerceIn(0, 100),
                rangeEnhancer = prefs.getBoolean(RangeEnhancer.KEY_ENABLED, false),
                rangeEnhancerStrength = prefs.getInt(RangeEnhancer.KEY_STRENGTH, 70).coerceIn(0, 100),
                virtualSurround = prefs.getBoolean(VirtualSurround.KEY_ENABLED, false),
                virtualSurroundStrength = prefs.getInt(VirtualSurround.KEY_STRENGTH, 40).coerceIn(0, 100),
                spatialWide = prefs.getBoolean(SpatialWide.KEY_ENABLED, false),
                spatialWideStrength = prefs.getInt(SpatialWide.KEY_STRENGTH, 50).coerceIn(0, 100),
                safeLimiter = prefs.getBoolean(SafeLimiter.KEY_ENABLED, false),
                safeLimiterStrength = prefs.getInt(SafeLimiter.KEY_STRENGTH, 30).coerceIn(0, 100),
                loudnorm = prefs.getBoolean(TrackGain.KEY_ENABLED, false),
                loudnormStrength = prefs.getInt(TrackGain.KEY_STRENGTH, 70).coerceIn(0, 100),
                customPrimaryArgb = prefs.getLong(KEY_CUSTOM_PRIMARY, 0xFF00F0FFL),
                customSecondaryArgb = prefs.getLong(KEY_CUSTOM_SECONDARY, 0xFFFF2E97L),
                customBackgroundUri = prefs.getString(KEY_CUSTOM_BG_URI, null),
                downloadPath = prefs.getString(DownloadManager.KEY_DOWNLOAD_PATH, "") ?: "",
                downloadPathConfirmed = prefs.getBoolean(DownloadManager.KEY_DOWNLOAD_PATH_CONFIRMED, false),
                miniPlayerPersist = prefs.getBoolean(DownloadManager.KEY_MINIPLAYER_PERSIST, false)))
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private var controller: MediaController? = null

    /**
     * 6.3.9「点歌立刻出声」：上一次 [writeTimelineFromQueue] 写入的完整时间线顺序，
     * 以及那一次放到第 0 位（当前曲）的歌曲 id。
     *
     * 播放器里先只灌了窗口内的一小段，剩下的靠 [fillTimelineTail] 在后台按这份
     * order 继续追加——所以这份 order 必须留着，不能在写完就丢。
     * anchor id 用于判断"后台补队期间用户是不是已经换了队列"，换了就作废当前批次。
     */
    private var timelineBaseOrder: List<Song>? = null
    private var timelineBaseAnchorId: Long? = null
    private var timelineFillJob: kotlinx.coroutines.Job? = null

    // ===== 无版权自动跳过（R25）=====
    /** songId → 判定时间（elapsedRealtime）。30 分钟内同一首不再重复解析，也用于列表灰显。 */
    private val unplayableAt = HashMap<Long, Long>()
    /** 连续自动跳过次数：任意一首真正出声、或用户手动点歌，就清零 */
    private var skipStreak = 0
    /** 上一次自动跳过的歌 + 时刻：同一次失败的错误回调可能连着来好几下，防抖用 */
    private var lastSkipSongId = -1L
    private var lastSkipAtMs = 0L
    /** 上一次处理"播到末尾"的时刻：防住空音频导致的连续 ENDED 空转 */
    private var lastEndedAtMs = 0L
    /** 上一次自动清缓存重试的时刻：防"清缓存→重试→又失败"的死循环 */
    private var lastCacheHealAt = 0L
    /** 连续播放失败次数（出声即清零）：到 3 次说明播放链路坏了，做一次软复位 */
    private var consecutivePlayFailures = 0
    /** 上一次软复位的时刻：60 秒内只复位一次，避免自己把自己搞成循环 */
    private var lastPlayerResetAt = 0L
    /** 连跳上限：整张歌单都没版权时，跳到这里就停，交回「搜其他版本」。
     *  6.5.3 从 8 降到 3 —— 跳 8 首会一路窜到别的歌手的歌上，
     *  用户的原话是"我点周杰伦，你怎么给我放别的音乐"。宁可早点停。 */
    private val COPYRIGHT_SKIP_LIMIT = 3
    /** 判定有效期：30 分钟内同一首保持灰显 */
    // 6.7.3：30 分钟 → 3 分钟。
    //
    // 这张表的作用是"自动跳过时别在一首死歌上来回撞"。但 30 分钟太长：
    // 一次抖动让一首歌进了表，接下来半小时里整单连跳都会绕过它 ——
    // 用户感受就是"这首歌明明能听，App 就是不给你放"。
    // 3 分钟足够避免死循环，又不至于把好歌长期拉黑。
    private val COPYRIGHT_TTL_MS = 3 * 60 * 1000L

    /**
     * 直接向播放器问当前位置，不经过 500ms 的 tickPosition 中转。
     *
     * 这是修「歌词比唱得快」的关键一步。media3 的 `getCurrentPosition()` 内部
     * 已经用 `MediaUtils.getUpdatedCurrentPositionMs()` 按墙上时间外推过（反编译
     * `MediaControllerImplBase` 确认），而且它是纯算术、不打 Binder、不走 IPC，
     * 每帧读一次的成本跟读一个字段相当。
     * 之前 UI 拿的是 500ms 才刷新一次的 `state.positionMs`，再自己叠一层外推
     * ——等于把同一段时间加了两遍，歌词于是稳定地跑在声音前面 0.25~0.5 秒，
     * 逐字扫光那层叠第三遍，最多能快到 1 秒。
     *
     * 控制器还没连上（冷启/切服务）时退回状态里最后一次采样值。
     */
    // ==================== 6.6.9：歌词平滑时钟 ====================
    //
    // 用户原话：「歌词根本跟不上，有时候快有时候慢，唱到哪儿歌词就该停到哪儿。」
    //
    // 根因：MediaController 的 currentPosition 是**跨进程代理**，底层每秒才同步
    // 一两次快照，直接拿它画歌词，等于用 500ms~1s 的抖动值当逐帧时钟：
    //   · 两次采样之间歌词纹丝不动，一到采样点突然跳半句（"一顿一顿"）；
    //   · 客户端时钟与服务端有偏差时，跳的方向还不一致（"有时快有时慢"）。
    //
    // 修法：以采样值为**锚点**，播放中用 SystemClock 自己外推，两次采样之间
    // 也能逐帧匀速前进；采样略微落后于外推值时只前移不回退（避免歌词视觉上
    // 往回跳半句），只有真正 seek 到前面（倒退 > 1.2s）才采纳。
    @Volatile private var posBaseMs: Long = 0L
    @Volatile private var posBaseAt: Long = 0L
    @Volatile private var posBasePlaying: Boolean = false
    private val posLock = Any()

    /**
     * 6.9.13：音频输出链路延迟补偿（毫秒）。
     *
     * media3 的 `currentPosition` 表示"已经写进 AudioTrack 的位置"，**不含**
     * 声音从设备真正到达耳朵的那段时间。外放这段约 10~40ms，可以忽略；
     * 蓝牙 A2DP/BLE 是 120~200ms，不补的话歌词会稳定跑在人声前面——
     * 用户戴蓝牙耳机听时那种"每个字都抢半拍"就是这个。
     * 大厂播放器都做这一步，只是它们的数值来自平台音频时间戳；这里用保守常量，
     * 宁可少补一点，也不要补过头把准的歌推慢。
     */
    private val audioLatencyMs: Long = runCatching {
        val am = app.getSystemService(android.media.AudioManager::class.java)
            ?: return@runCatching 0L
        val devices = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val bt = devices.any { d ->
            d.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                d.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (android.os.Build.VERSION.SDK_INT >= 31 &&
                    d.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        if (bt) 120L else 0L
    }.getOrDefault(0L)

    /**
     * 播放器回报新位置时刷新锚点（由 syncFromController / seekTo 调用）。
     *
     * 6.9.8 修正「吃预测」阈值：旧版 1200ms 的宽限期是歌词累积漂移的根源——
     * 网络小卡顿（位置冻结几百毫秒、isPlaying 仍为 true）时，外推值一路领先；
     * 只要领先量 < 1200ms，播放器回报的真实位置永远被丢弃，卡一次欠一笔，
     * 越欠越多（"歌词快一大截"）；一旦欠账超过 1.2s 又猛地拉回（"突然变慢"）。
     * 收窄到 200ms：正常跨进程快照的微小滞后仍被外推吸收（感知不到），
     * 任何真实的卡顿/回正都在下一个 500ms 采样内被纠正，误差不再累积。
     */
    private fun notePlayerPosition(ms: Long, playing: Boolean) {
        if (ms < 0L) return
        synchronized(posLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            val predicted = if (posBasePlaying) posBaseMs + (now - posBaseAt) else posBaseMs
            val diff = ms - predicted
            // 6.9.12 治根：**不再渐进收敛**。
            //
            // 6.9.10 的「偏差折 28% 进锚点」看着很优雅，实际制造了用户反复
            // 抱怨的「歌词永远跑得比人声快」：
            //   · 这个函数以前只在 syncFromController（播放状态事件）里被调用，
            //     正常播放时几乎不触发 —— 一次网络小卡顿让外推领先 800ms，
            //     之后十几秒没有任何采样来纠正它；
            //   · 即便纠正，一次也只吃掉 28%，要四五次采样才追上，
            //     中间这段时间歌词就一直冲在前面。
            // 现在改为：偏差落在 80ms 死区内就保持时钟（消除逐帧抖动），
            // 超出则**立即对齐播放器真实位置** —— 一次最高 100ms 的微调，
            // 人耳听不出来，但误差永远不累积。
            //
            // 同时调用点补进 tickPosition 的 500ms 循环（此前完全没接），
            // 并且缓冲中不再外推（见 livePositionMs 的 playing 判定）。
            posBaseMs = when {
                !playing -> ms
                diff in -80L..80L -> predicted
                else -> ms
            }
            posBaseAt = now
            posBasePlaying = playing
        }
    }

    /**
     * 歌词/进度条用的实时位置（毫秒）。
     * 与 [PlayerState.positionMs] 的区别：那个是 500ms 的采样值，这个是逐帧平滑值。
     */
    fun livePositionMs(): Long = synchronized(posLock) {
        val raw = if (!posBasePlaying) {
            posBaseMs.coerceAtLeast(0L)
        } else {
            (posBaseMs + (android.os.SystemClock.elapsedRealtime() - posBaseAt)).coerceAtLeast(0L)
        }
        // 6.9.13：减掉输出链路延迟，让"眼睛看到的"追上"耳朵听到的"。
        // 蓝牙下这是 120ms，外放为 0，不会影响已经对齐好的外放场景。
        val heard = (raw - audioLatencyMs).coerceAtLeast(0L)
        // 夹到真实时长：外推在结尾/buffering 时可能冲过尾巴，
        // 一冲过去最后一句歌词就被 activeIndex 判成"已结束"，整行提前变灰。
        val d = _state.value.durationMs
        if (d > 0L) heard.coerceAtMost(d) else heard
    }

    private var sleepUnsub: (() -> Unit)? = null
    private var searchJob: Job? = null
    /** 输入联想请求（5.1）：与结果搜索分开防抖，边打字边出联想，不拖慢结果搜索 */
    private var suggestJob: Job? = null
    /** 搜索请求序号：慢请求回来时如果用户已经改了关键词，直接丢弃，避免旧结果覆盖新结果 */
    private var searchSeq = 0
    /** 上一次已上报的搜索词：防抖后只上报真正执行的搜索，不重复打点 */
    private var lastSearchTelemetry = ""
    private var searchPageOffset = 0
    /** 歌单搜索的 offset（和单曲的 searchPageOffset 分开算，切 tab 不会互相拽页码） */
    private var searchPlaylistOffset = 0
    /**
     * 6.9.2：QQ 搜索源翻到第几页（接口 p 参数，从 1 起）。
     * QQ 公开搜索单页上限 60，满了才可能有下一页；换词 / 换源一律复位 1。
     */
    private var qqSearchPage = 1
    /** 当前关键词已经搜过哪几类：可左右滑的 tab 靠它决定要不要重发请求 */
    private val searchedTabs = HashSet<Int>()
    /**
     * 6.3.2：正在预加载的分类（见 [preloadSearchTab]）。
     *
     * 和 [searchedTabs] 的区别：那个是"问完了"，这个是"正在问"。
     * 没有它的话，邻页在结果回来之前会反复重组、反复触发预加载，一次划动打三四个请求。
     */
    private val searchPreloading = HashSet<Int>()
    /** searchedTabs 属于哪个关键词；关键词一变就整批作废 */
    private var searchedQuery = ""
    private var reportJob: Job? = null
    /** 网易云二维码轮询任务 */
    private var qrJob: Job? = null

    /**
     * 全局协程异常兜底：任何 launch 内部未捕获的异常（网络解析、JSON、状态更新等）
     * 都在这里吃掉并记日志，绝不让异常冒泡把整个 App 闪退。
     * 历史 bug：网易云扫码登录 803 成功后，后续拉榜单/歌单的协程偶发抛异常直接闪退。
     */
    private val safeHandler = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("JunMusic", "coroutine crashed", throwable)
    }

    /** 带异常兜底的 launch：协程内任何未捕获异常不会导致 App 闪退 */
    private fun launchSafely(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(safeHandler, block = block)

    /** 同上，但直接跑在 IO 线程上（prefs / 文件读写这类不需要主线程的活） */
    private fun launchSafelyIo(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(Dispatchers.IO + safeHandler, block = block)

    // ==================== 播放页音质切换 / 缓存提示（5.2） ====================

    /** 闪条自动收回的定时任务 */
    private var flashJob: Job? = null

    /** 拉「我的反馈」未读数的任务（并发保护：回前台 + 冷启动可能同时触发） */
    private var feedbackJob: Job? = null

    /** 正在等待真实解析结果的歌曲 ID（解析回调按它对齐，别的歌的解析不打扰闪条） */
    private var flashSongId = ""

    /**
     * 6.3.2：已经提示过「只有试听版」的曲目 ID。
     *
     * 解析回调在预取线程也会触发，一首歌可能解析好几次；不去重的话
     * 用户每切一次歌就被弹一次同样的提示。同一首只提醒一遍就够了。
     */
    private val trialNotified: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 6.3.2：已经尝试过「残片补救」的曲目，防止重复触发换源。
     *
     * 和 [trialNotified] 分开：那个是给用户提示用的，这个是给自动换源用的，
     * 两者触发时机不同，混在一起会互相吞掉。
     */
    private val trialFixTried: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        // 解析结果回来 → 把闪条从「想要 X」改成「实际 Y」。回调在 socket / 预取线程触发，
        // 所以一律切回 viewModelScope（Main.immediate）再写状态。
        // R4：音质按钮本身只显示所选档位，不再跟着解析结果改字，所以这里只服务闪条——
        // 少一次全量 state 写入，老机型上每秒的重组也少一处抖动来源。
        AudioProxyServer.onResolved = { id, audio ->
            launchSafely {
                if (id.isBlank()) return@launchSafely
                if (id == flashSongId) {
                    _state.value = _state.value.withExtras {
                        copy(qualityFlash = qualityFlashText(audio), qualityFlashTick = qualityFlashTick + 1)
                    }
                    scheduleFlashClear(3_600L)
                }
                // 6.3.2：解析层已经把「能拿完整版的源」都试过一轮了，走到这里还是残片，
                // 说明这首确实只有试听（版权方没放完整版）。必须说清楚，
                // 否则用户听到 45 秒突然停，只会以为播放器坏了。
                if (audio.trial && id == _state.value.currentSong?.songId && trialNotified.add(id)) {
                    notice("这首只有试听片段（版权限制），暂时拿不到完整版")
                }
            }
        }

        // 在线缓存写满的一次性提示（由播放服务置位）
        launchSafely {
            PlaybackCache.notice.collect { n ->
                if (n.isNullOrBlank()) {
                    _state.value = _state.value.withExtras { copy(cacheNotice = "") }
                } else {
                    _state.value = _state.value.withExtras { copy(cacheNotice = n, cacheNoticeTick = _state.value.cacheNoticeTick + 1) }
                    // 这条不常驻：十几秒后自己清空，避免用户几天后还看到同一条
                    delay(12_000)
                    if (_state.value.cacheNotice == n) {
                        PlaybackCache.consumeNotice()
                        _state.value = _state.value.withExtras { copy(cacheNotice = "") }
                    }
                }
            }
        }
    }

    /**
     * 闪条文案：想要的档位 vs 真正拿到的档位。
     *
     * 两条底线：
     *  1. **仍然只说真话**——选无损但官方只给到 320k 时不能留「无损」两个字骗人；
     *  2. **不说实现细节**（6.1）：以前写「想要无损 · 实际走内置链路」，用户明确否掉——
     *     "内置链路""解析"这些是代码内部的名词，不该出现在给人的提示里。
     *     现在按网易云那个口径说：「无损音质 · 已为你接入」「Hi-Res 暂不可用 · 这首最高无损」。
     */
    private fun qualityFlashText(audio: ResolvedAudio): String {
        val want = _state.value.audioQuality
        val got = audio.badge()                 // 认不出来时是空串，不再吐"内置链路"
        val hit = matchedQuality(audio.level)
        return when {
            // 拿不准到底落在哪一档：只说"已按最优可用音质播放"，不报内部链路。
            got.isBlank() && hit == null -> "已按当前可用的最佳音质为你播放"
            want == AudioQuality.AUTO -> if (hit != null) "智能音质 · 已接入${hit.badgeLabel}"
                                          else "智能音质 · 已按最优可用播放"
            hit == null -> "已接入${got}音质"
            hit == want -> "${want.badgeLabel} · 已为你接入"
            // 拿不到用户要的那一档：说实话，但说成"这首最高是X"，不说"你想要的那档没了"
            else -> "${want.label}暂不可用 · 这首最高${hit.label}"
        }
    }

    /** 把服务端返回的 level 字符串对齐回档位；认不出来返回 null（说明落回了内置链路） */
    private fun matchedQuality(level: String?): AudioQuality? {
        val l = level?.lowercase().orEmpty()
        if (l.isBlank()) return null
        AudioQuality.entries.firstOrNull { it.level == l }?.let { return it }
        return when {
            l.contains("hires") || l.contains("hi_res") || l.contains("master") ||
                l.contains("sky") || l.contains("jyeffect") -> AudioQuality.HI_RES
            l.contains("lossless") || l == "flac" -> AudioQuality.LOSSLESS
            l.contains("exhigh") -> AudioQuality.EXHIGH
            l.contains("standard") -> AudioQuality.STANDARD
            else -> null
        }
    }

    /**
     * 听歌明细缓存。切换日/周/月/年只是同一份数据换个聚合口径，
     * 每次都重读文件既慢又没必要；打开弹层时才刷新（见 [toggleReport]）。
     */
    private var statEntries: List<ListenEntry>? = null

    /** 云端听歌记录里的歌曲元数据（曲库已下架的歌也能显示歌名/歌手） */
    private var statCloudMeta: Map<Long, ListenSongMeta> = emptyMap()

    /** 服务端按天汇总的旧版心跳时长（没有歌曲明细），与明细逐日取 MAX */
    private var statLegacyDaily: Map<Long, Long> = emptyMap()

    /** 云端听歌报告上次同步时间（毫秒），打开报告时据此提示「已同步」 */
    private var listenSyncedAt: Long = 0L

    /**
     * 切歌追踪：上一首歌的 ID 与其【实际播放】的累计时长。
     *
     * 不能用墙钟差：暂停挂机一小时再切歌，墙钟差会把挂机时间全记成"已播放"，
     * 还会把只听了几秒的歌误判成完整播放计数，污染推荐权重。
     * 与 MusicService.settleListenTime 同口径：只在 isPlaying 期间走表。
     */
    private var prevSongId: Long = -1L
    private var prevAccumMs: Long = 0L
    /** 当前"正在播放"时间段的起点（elapsedRealtime），0 = 没有在走表 */
    private var prevLegStartElapsed: Long = 0L
    /** 结算 prevSong 到目前为止实际播放了多久（含未闭合的当前段） */
    private fun prevPlayedMs(): Long {
        val open = if (prevLegStartElapsed > 0L)
            android.os.SystemClock.elapsedRealtime() - prevLegStartElapsed else 0L
        return prevAccumMs + open
    }
    /** isPlaying 变化时闭合/开启当前播放段（不切歌） */
    private fun updatePlayLeg(isPlaying: Boolean) {
        if (prevSongId < 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (isPlaying) {
            if (prevLegStartElapsed == 0L) prevLegStartElapsed = now
        } else if (prevLegStartElapsed > 0L) {
            prevAccumMs += now - prevLegStartElapsed
            prevLegStartElapsed = 0L
        }
    }

    /**
     * 待恢复的曲目与位置。
     *
     * 两种来源：进程复用时播放器里已有的当前曲目，或上次退出时落盘的记录。
     * 库是异步加载的，连上 controller 的那一刻队列还是空的，没法直接定位下标，
     * 所以先把 id 记在这里，等 [applyFilters] 建好队列再对齐。
     */
    private var pendingRestoreId: Long? = null
    private var pendingRestorePos: Long = 0L
    /** 杀进程重启后是否自动续播：上次退出时若在播放则为 true */
    private var pendingAutoPlay: Boolean = false
    /** 杀进程前保存的播放队列（歌曲 ID 列表），重启后恢复完整队列 */
    private var pendingRestoreQueueIds: List<Long>? = null
    private var pendingRestoreQueueIndex: Int = 0

    /**
     * 当前这份 state.queue 是不是"用户亲自选中的那一条列表"（歌单 / 我喜欢 / 搜索结果 /
     * 网易云榜单 / 最近播放会话 / 上次退出时保存的队列），而不是浏览视图算出来的曲库。
     *
     * 为什么需要这个标记：applyFilters 是"按 文件夹→搜索→排序 重算队列"的通用逻辑，
     * 而它在后台被调用的时机一大堆且与用户动作无关——曲库秒开后还有一次在线校正
     * （loadLibrary 尾部）、SAF 本地扫描完成（reloadLibraryWithLocal）、云歌单同步回来
     * （syncPlaylistsQuietly）。用户在歌单里点了第 3 首开始顺序播放，这几次后台重算会把
     * queue 换成整个曲库并重写时间线，于是"播完这首"跳到曲库里随便一首——
     * 正是用户说的"下一首直接跳到别的音乐 / 跳回整个曲库"。
     * 标记为 true 时 applyFilters 只更新首页浏览列表（homeSongs），不动播放队列；
     * 用户主动换视图 / 换筛选 / 换排序 / 重新刷库的那些入口会先把它清掉。
     */
    private var pinnedQueueSession = false

    /**
     * 建立上面那份保护时记录的"浏览视图签名"（视图 / 筛选 / 排序 / 搜索词 / 图书馆模式）。
     * applyFilters 每次都拿当前签名和它比：不相等说明用户自己换了浏览的东西，保护当场失效，
     * 队列按新视图重算。这样就不用去八九个"换视图"的入口各补一句取消保护——
     * 那种写法漏掉一个入口就是一个新的 bug，签名比对是集中且不会漏的。
     */
    private var pinnedQueueView = ""

    /** 当前浏览视图的稳定签名 */
    private fun browseViewKey(s: PlayerUiState): String = buildString {
        append(s.activePlaylistId ?: "none")
        append('|').append(s.onlyLiked)
        append('|').append(s.selectedFolders.sorted().joinToString(","))
        append('|').append(s.sortOrder).append('|').append(s.sortDirection)
        append('|').append(s.libraryMode)
        append('|').append(s.searchQuery.trim().lowercase())
    }

    /**
     * 把当前这份 state.queue 声明成"用户亲自选中的会话队列"（playList / 恢复队列 / 撤销 /
     * 图书馆模式筛好的那份都算），并记下当时的浏览视图签名。
     */
    private fun pinQueueSession() {
        pinnedQueueSession = true
        pinnedQueueView = browseViewKey(_state.value)
    }

    /**
     * 离开「我喜欢 / 自建歌单」这个**用户列表视图**上下文。
     *
     * 这两个标记管着两件事：首页显示哪份列表、以及播放列表面板**打开时落在哪个标签**。
     * 只要有一次「换了播放来源却没清它」，用户下次打开播放列表就会被拽回旧视图 ——
     * 现象就是"我明明在听最近播放的歌，点开播放列表却跳回我喜欢"。
     *
     * 历史上这个坑补过两次：R13 补了 playSong 的 activePlaylistId，R27 补了 playList 的
     * onlyLiked。但 [playRecentPlayback] 是直接 copy(queue=...) 绕过 playList 的第三个入口，
     * 两轮都漏了它 —— 所以现场又复现了。现在收成一处，任何"换整份队列"的入口都先调它。
     *
     * 幂等：本来就在「全部音乐」视图（两个标记都为空）时什么也不做，不写盘。
     */
    private fun clearUserListViewContext() {
        val s = _state.value
        if (s.activePlaylistId == null && !s.onlyLiked) return
        _state.value = s.copy(activePlaylistId = null, onlyLiked = false)
        prefs.edit()
            .putLong(KEY_ACTIVE_PLAYLIST, -1L)
            .putBoolean(KEY_ONLY_LIKED, false)
            .apply()
    }

    /** 上次把播放进度落盘的时间，用于限流（每 5 秒一次足够） */
    private var lastPersistMs = 0L


    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 暂停/继续影响"实际播放时长"走表，先闭合或开启当前段
            updatePlayLeg(isPlaying)
            _state.value = _state.value.copy(isPlaying = isPlaying)
            // R25：只要真正出声，就说明连跳结束，计数与兜底条一起复位
            if (isPlaying && skipStreak > 0) {
                skipStreak = 0
                _state.value = _state.value.withExtras {
                    copy(copyrightSkipStreak = 0, copyrightTip = "", copyrightQuery = "")
                }
            }
            // 出声就说明播放链路是通的：连续故障计数清零
            if (isPlaying) consecutivePlayFailures = 0
            // 暂停是听歌时长的自然落盘点：把最后一段补传，后台不必等到下一次定时
            if (!isPlaying && AccountStore.isLoggedIn) {
                launchSafely { runCatching { syncListenStatsQuietly() } }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 顺序播放（repeatMode=OFF 且未随机）也必须自动连播到下一曲。
            // 这里历史上有一段"在自动前进的瞬间 pause()+seekTo(0)"的拦截，把
            // ExoPlayer 已经推进到的新歌强行摁停，表现就是"顺序播放播完不播下一首，
            // 手动点下一首却正常"（手动是 SEEK 原因码，绕过了拦截）。5.1 起删除。
            // "播完这一首就停"这类需求由 SleepTimer.stopAfterSong 在服务端实现，
            // 不再靠改播放模式的语义来达成。
            //
            // R13 补的另一半：自动切歌走的是【播放器时间线自己往前拱】(REASON_AUTO)，
            // 而用户在列表里点歌走 playAt → 发现时间线和 state.queue 不同源就整条重写。
            // 两条路的"下一首"因此可以不是同一首：时间线被后台曲库刷新 / 队列重排
            // （applyFilters→pushQueue）、被图书馆模式追加条目、被上一次会话的旧队列
            // 残留带偏之后，播完这一首就落到"不相干的歌"上，用户看到的是
            // "顺序播放下一首跳到别的音乐"，而手动点列表里那首又是对的。
            // 修法：自动前进的那一刻校验时间线与 state.queue 是否同源，不同源就
            // 立刻以 state.queue 重建（当前曲就地锚定），并在队列已到末尾时停下，
            // 而不是继续放队列外的歌。SEEK 原因码不校验：那是我们主动写的，
            // 再叠一次重建会把用户刚点的那首打断。
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) healTimelineAfterAutoAdvance(mediaItem)
            syncFromController()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // R26：队列播到底 → 回到第一首接着放。
            //
            // 底层循环模式是 REPEAT_MODE_OFF 时（图标灰色那种"顺序播放"），
            // 放到列表最后一首 ExoPlayer 只会停在 STATE_ENDED，**不会自己绕回开头**。
            // 用户看到的就是"12 首听到最后一首就没声了"。这里统一兜底：
            // 只要不是单曲循环，就把下一首算成 (当前+1) % 队列长度，交给 playAt 续上。
            // 顺带解决"时间线只灌了 12 首窗口、窗口用完就停"的同款现象。
            if (playbackState == Player.STATE_ENDED) {
                // 6.9.2：这首被完整听完了 → 曲库自动回填候选转正。
                // （代理侧只记候选不下载，听完才真正回填进云端曲库，
                //  每天 300MB 额度只花在真正听完的歌上。）
                runCatching {
                    controller?.currentMediaItem?.mediaId?.toLongOrNull()
                        ?.let { com.jun.musiccloud.data.LibraryBackfill.markListenedThrough(it.toString()) }
                }
                val nowMs = android.os.SystemClock.elapsedRealtime()
                // 两秒内连续 ENDED：多半是空音频/解码立即结束，别无限空转
                if (nowMs - lastEndedAtMs < 2000L) {
                    lastEndedAtMs = nowMs
                    runCatching { controller?.pause() }
                    syncFromController()
                    return
                }
                lastEndedAtMs = nowMs
                val q = _state.value.queue
                if (q.size > 1 && _state.value.repeatMode != RepeatMode.ONE) {
                    val curId = runCatching { controller?.currentMediaItem?.mediaId?.toLongOrNull() }
                        .getOrNull()
                    val curIdx = curId?.let { id -> q.indexOfFirst { it.id == id } }
                        ?.takeIf { it >= 0 } ?: _state.value.currentIndex
                    val nextIdx = if (curIdx + 1 < q.size) curIdx + 1 else 0
                    playAt(nextIdx, resetSkipStreak = false)
                    return
                }
            }
            syncFromController()
        }

        /**
         * 播放器报错以前是直接吞掉的：老机型上"点了没声音、也不切歌"就是这么来的。
         * 现在把错误翻译成人话报到错误提示上，用户至少知道该重试还是该换一首。
         */
        // 6.6.9：正在播放的那首做过的一次性"原地重试"记账（见 noAudio 分支）。
        // 每首歌只自动重试一次，防止"报错→重试→报错"死循环。
        private var midPlayRetriedFor: String = ""
        // 6.7.1：改成"限次"而不是"一次性"。用户拖动进度条可能连着抖几次，
        // 只给一次机会的话第二次就判死跳歌。同一首 60 秒内最多重试 3 次。
        private var midPlayRetryCount: Int = 0
        private var midPlayRetryAt: Long = 0L

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            syncFromController()
            // 6.3 大修版：把"能不能听"的判定从"是不是在线歌"改成"代理到底报了什么"。
            // 上一版用「是在线歌」当无版权的判据，结果**任何**一首在线歌只要网络抖一下、
            // 或者播到一半重新缓冲失败，底下就会挂一条"暂时无版权"——用户明明能听。
            // 现在只有代理明确回了 HEADER_UNPLAYABLE（所有源都解析不出地址）才说无版权。
            // 从代理响应里取标记：ExoPlayer 把 HttpDataSource 的错误都包成
            // ERROR_CODE_IO_BAD_HTTP_STATUS，真实状态码在 responseCode 上，头在 cause 里找不到，
            // 所以这里改用「代理自己回写的错误体 + 状态码」来判：204 = 无版权，502 = 上游拒绝。
            val httpCode = readHttpStatus(error)
            // 判定顺序很关键：先问"代理是不是判过这首没地址"（最准），
            // 再看缓存/存储故障。反过来的话，一次"解析失败"里只要异常文案蹭到
            // cache 字样，就会被当成磁盘写坏去清缓存重试，歌永远不跳。
            // R26：判定"这首真放不出来"。
            //
            // 只看 204 是**无效**的：代理解析失败确实回 204，但 204 属于 2xx，
            // DefaultHttpDataSource 当成"请求成功"放过，返回空流，ExoPlayer 接着报
            // 「解不出容器格式 / EOF」，错误码里根本读不到 204 ——
            // 于是下面这个分支从来没执行过。用户现场：周杰伦整张歌单卡在 0:00 不跳。
            //
            // 现在的判据三条，满足任一条就跳：
            //   1. 代理自己记账说这首解析不到地址（最准，不受错误码包装影响）；
            //   2. 碰巧还是带出了 204（部分机型 / 播放器版本会带）；
            //   3. 在线歌、一点音频数据都没读到（duration<=0）、错误又是 HTTP/解析类
            //      —— 连音频头都没有，重试也一样是白等。
            val curMediaId = runCatching { controller?.currentMediaItem?.mediaId }.getOrNull().orEmpty()
            // 用 mediaId 反查队列，而不是读 currentIndex 那一格：错误回调可能比
            // syncFromController 的索引更新更早到，读错一格就会把在线歌当成本地歌放过去。
            val curSong = curMediaId.toLongOrNull()?.let { id ->
                _state.value.queue.firstOrNull { it.id == id }
            } ?: _state.value.currentSong
            // 在线歌的判据是 songId（网易云 ID），不是 filePath：
            // Song.filePath 在在线化之后复用成了「封面 URL」，在线歌这一栏**也是非空的**，
            // 拿它判"本地歌"会把所有在线歌都漏过去，自动跳过永远不会触发。
            val isOnlineSong = curSong != null && curSong.songId.isNotBlank()
            if (isOnlineSong) {
                // 代理记账的 key 是**网易云 songId**（播放 URL 里 ?id= 用的就是它），
                // 不是 mediaId（mediaId 是 stableId 哈希）。拿 mediaId 去查永远查不到 ——
                // 又是一个会让"修了等于没修"的坑。
                val proxyKey = curSong?.songId.orEmpty().ifBlank { curMediaId }
                val playingNow = _state.value.durationMs > 0L
                // ────────────────────────────────────────────────────────
                // 6.7.1：**播放中永远不判"无版权"**（用户这轮最重的反馈：
                // 「拖到中间就暂停、然后播放失败，很少能完整听完」）。
                //
                // 已经出声 = 这首歌有版权、地址也是好的。此刻报错只可能是
                // 这一次 seek 的请求没接上（上游抖动 / 连接被掐 / 短暂 5xx）。
                // 判"无版权"的代价是跳歌，而且不可逆；重试的代价只是半秒。
                // 所以只要 duration > 0，一律原地续播，同一首 60 秒内最多 3 次。
                // ────────────────────────────────────────────────────────
                // ────────────────────────────────────────────────────────
                // 6.7.3：**没出声的歌也先重试，不再直接跳**。
                //
                // 上一版这里有个 `playingNow &&` 的前置条件：只要歌还没出声，
                // 一次失败就立刻 autoSkipUnplayable → 界面弹"无版权已跳过"。
                // 而"没出声"的原因十有八九是这一次解析撞上了限流/超时，
                // 换一条路（降档、换源、清负缓存）就能响 —— 用户看到的是
                // "这首歌以前能听，现在没版权了"，其实一直是同一首、同一个源。
                //
                // 现在两种情形都给机会：
                //   · 正在播（拖动/断流）→ 3 次原地续播（seek 回原位再 prepare）；
                //   · 还没出声（刚点开）→ 2 次强制换源（清掉所有失败记忆后重解析）。
                // 次数用完才判"暂时取不到音源"，这时才跳。
                // ────────────────────────────────────────────────────────
                if (proxyKey.isNotBlank()) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - midPlayRetryAt > 60_000L) midPlayRetryCount = 0
                    // 6.7.4：没出声的只重试 1 次（不是 2 次）。
                    // 重试本身要再走一遍解析，次数越多越容易把链路堵死；
                    // 1 次已经能覆盖"这一次撞上限流"的情况。
                    val maxTries = if (playingNow) 3 else 1
                    if (midPlayRetryCount < maxTries) {
                        midPlayRetryCount++
                        midPlayRetryAt = now
                        midPlayRetriedFor = proxyKey
                        // 三张"失败记忆"必须一起清：
                        //   · 代理的不可播记账 + 已缓存地址（clearUnplayableFor 里一起做）
                        //   · 整条链路 15 秒失败缓存
                        //   · 脚本层"这首没搜到"的 12 秒负缓存 ← 不清这个，重试会秒回 null
                        runCatching { com.jun.musiccloud.playback.AudioProxyServer.clearUnplayableFor(proxyKey) }
                        runCatching { com.jun.musiccloud.data.OnlineMusicApi.clearNegative(proxyKey) }
                        val pos = if (playingNow) _state.value.positionMs.coerceAtLeast(0L) else 0L
                        runCatching {
                            // 关键：seek 回原位再 prepare。只调 prepare 会从"出错的那一帧"
                            // 继续，往往还是同一个断点，重试十次也没用。
                            controller?.seekTo(pos)
                            controller?.prepare()
                            controller?.play()
                        }
                        notice(
                            if (playingNow) "刚才那下没接上，正在原地续播…"
                            else "这首第一次没取到，正在换个源重试…"
                        )
                        return
                    }
                }
                val noAudio = httpCode == 204 ||
                    (proxyKey.isNotBlank() && !playingNow &&
                        com.jun.musiccloud.playback.AudioProxyServer.isUnplayable(proxyKey)) ||
                    (!playingNow && isNoAudioError(error))
                if (noAudio) {
                    // 6.4：这首歌确实放不出来，把歌单里的可播标记纠正过来。
                    // 「只看可播」靠这份标记过滤，不纠正的话失败过的歌会永远被算作可播，
                    // 用户一开过滤就看到一堆红的，等于这个开关白做。
                    runCatching {
                        val sid = curSong?.songId.orEmpty()
                        if (sid.isNotBlank()) {
                            com.jun.musiccloud.data.Playlists.setSongPlayable(sid, false)
                        }
                    }
                    autoSkipUnplayable()
                    return
                }
            }
            if (isCacheOrStorageError(error)) {
                // 同一首 10 秒内只自动清一次缓存：清完重试又失败、再清再失败的死循环，
                // 表现出来就是"点了歌一直不动"，而且每次都在报"正在清理缓存"。
                val nowHeal = android.os.SystemClock.elapsedRealtime()
                if (nowHeal - lastCacheHealAt > 10_000L) {
                    lastCacheHealAt = nowHeal
                    reportError("本机存储或播放缓存异常，正在自动清理缓存后重试…")
                    PlaybackCache.clearAsync(getApplication()) {
                        launchSafely {
                            kotlinx.coroutines.delay(500)
                            retryPlayback()
                        }
                    }
                } else {
                    reportError("本机存储或播放缓存异常，点重试或清空缓存后再试")
                }
                return
            }
            val msg = when {
                httpCode == 502 -> "音频源暂时取不到，点重试或换个音质"
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
                    "网络断了，这首暂时放不出来"
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                    "这条音频链接失效了，重试或换个音质试试"
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                    "这个格式本机放不了，换一首试试"
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                    "这台机器的解码器放不下这首，试试标准音质"
                else -> "播放出错了：${error.errorCodeName}"
            }
            // 6.5.3：连续 3 首都放不出来（而且都不是无版权），说明坏的不是某一首歌，
            // 是整条播放链路 —— 缓存数据源指向已释放对象、管道卡死这类问题，
            // 以前的表现就是"什么都听不了，必须杀进程重开"。
            // 这里做一次软复位：重建缓存管道、把当前曲重新挂上，用户不用退出去。
            consecutivePlayFailures += 1
            if (consecutivePlayFailures >= 3) {
                val nowReset = android.os.SystemClock.elapsedRealtime()
                if (nowReset - lastPlayerResetAt > 60_000L) {
                    lastPlayerResetAt = nowReset
                    consecutivePlayFailures = 0
                    runCatching {
                        com.jun.musiccloud.playback.MusicService.instance?.rebuildCachePipe()
                    }
                    notice("播放器已重新连接，再点一次试试")
                    return
                }
            }
            reportError(msg)
        }

        /**
         * 是否是"存储/播放缓存"类错误（6.3.1）。
         *
         * 判据刻意只看**异常类型 + 关键词**，不去匹配 ExoPlayer 的 errorCode：
         * 缓存层的异常会被包成 ERROR_CODE_IO_UNSPECIFIED / ERROR_CODE_IO_BAD_HTTP_STATUS，
         * 跟真的网络失败完全同码，靠 errorCode 区分不出来。真正的线索在 cause 链的
         * IOException 文案上（"No space left on device"、"cache is full"、
         * media3 的 CacheException / cache span not found 等）。
         */
        private fun isCacheOrStorageError(error: androidx.media3.common.PlaybackException): Boolean {
            var n: Throwable? = error.cause
            var guard = 0
            while (guard++ < 8) {
                val cur = n ?: break
                val m = cur.message.orEmpty()
                val hit = cur is java.io.IOException && (
                    m.contains("ENOSPC", true) ||
                        m.contains("No space left", true) ||
                        // 6.5.2：原来这里只写 "cache"，太宽了 —— ExoPlayer / OkHttp 的
                        // 普通 IO 异常只要文案里带 cache（如 CachedContentIndex、cache key）
                        // 就被当成"磁盘写坏"，于是一次解析失败被误判成缓存故障，
                        // 白清一遍缓存再重试。收紧成真正的"缓存写满/索引坏"特征串。
                        m.contains("cache is full", true) ||
                        m.contains("CacheException", true) ||
                        m.contains("span not found", true) ||
                        m.contains("disk full", true) ||
                        m.contains("Read-only file system", true)
                    )
                if (hit) return true
                n = cur.cause
            }
            return false
        }

        /**
         * 错误码是否属于"拿到了响应、但里面根本没有可解码的音频"这一类（6.5.3）。
         *
         * 这里**故意只留解析类**，不再包含 IO_BAD_HTTP_STATUS / IO_UNSPECIFIED：
         * 那两个是网络层的通用失败（弱网、切网、DNS 抖动都会中），把它们也算成
         * "这首没版权"就会在网抖的时候连着跳好几首 —— 用户看到的是
         * "我点周杰伦，它给我放别人的歌"，比卡住还糟。
         *
         * 真正的无版权走另一条更准的路：代理自己记账（[AudioProxyServer.isUnplayable]）。
         * 这条只兜底"代理回了 200/204 但内容是空的"那种。
         */
        private fun isNoAudioError(error: androidx.media3.common.PlaybackException): Boolean =
            when (error.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> true
                else -> false
            }

        /**
         * 从 PlaybackException 里挖出 HTTP 状态码。
         * HttpDataSource 会把状态码塞在 cause 链里的 HttpDataSource.InvalidResponseCodeException
         * 上；拿不到就返回 0（按普通错误处理，绝不乱报无版权）。
         */
        private fun readHttpStatus(error: androidx.media3.common.PlaybackException): Int {
            var n: Throwable? = error.cause
            var guard = 0
            while (guard++ < 8) {
                val cur = n ?: break
                if (cur is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    return cur.responseCode
                }
                n = cur.cause
            }
            return 0
        }
    }

    private val configCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = refreshSystemDark()
        override fun onLowMemory() { onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) }

        /**
         * 进程被系统提示"内存紧张"时，把可重建的缓存缩掉。
         *
         * 老机型上这一步直接决定"是被系统杀掉重启"还是"变轻一点继续活着"。
         * 封面（ArtworkLoader）是最大的一块，缩它最划算；歌词引擎次之。
         */
        override fun onTrimMemory(level: Int) {
            // 歌词时间轴/排版那两张缓存本身就是有界 LRU，不用动
            runCatching { ArtworkLoader.trimMemory(level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) }
        }
    }
    init {
        PlayHistory.init(app)
        ListenStats.init(app)
        // 6.3：听歌明细文件按账号分开。AccountScope 已在上面 bind 过，这里只需要
        // 让 ListenStats 指向对应 uid 的文件（顺带把旧 listen_stats.csv 复制过来一次）。
        ListenStats.rebind(app, AccountStore.userId)
        ArtworkLoader.init(app)
        Playlists.init(app)
        // 在线播放依赖本地音频代理（解析真实 CDN 地址 + 带 Referer 代理流），
        // 进程内幂等启动，保证点击即播。
        AudioProxyServer.start(app)

        // 点赞集合在 PlayHistory 初始化后读取（prefs lateinit）
        val allPlaylists = Playlists.all()
        // 恢复上次选中的视图（「我喜欢」/ 自定义歌单）：下次打开仍停留在这个列表，
        // 直到用户主动点「首页」按钮或按系统返回键回到「全部音乐」才清除。
        // 歌单可能已被删除，恢复前先校验它仍存在。
        val savedPlaylistId = prefs.getLong(KEY_ACTIVE_PLAYLIST, -1L)
            .takeIf { id -> id > 0L && allPlaylists.any { it.id == id } }
        val savedOnlyLiked = prefs.getBoolean(KEY_ONLY_LIKED, false)
        _state.value = _state.value.copy(        likedSongIds = PlayHistory.getLikes(),
        likedSongs = PlayHistory.likedSongs(),
        playlists = allPlaylists,
        activePlaylistId = savedPlaylistId,
        onlyLiked = savedPlaylistId == null && savedOnlyLiked,
        recentPlaybacks = PlayHistory.recentPlaybacks()).withExtras { copy(            todos = ListenTodoStore.load(app),
            // 省电动画：低端与中端机默认开启（砍掉呼吸描边/插画浮动/加号脉动这类
            // 一直跑的纯装饰动画），交互反馈（按压/翻页/飞入）一律保留，不产生"点了没反应"。
            powerSavingAnim = prefs.getBoolean(
                KEY_POWER_SAVING_ANIM,
                // 低端、中端、以及 Android 11 及以下的新系统代际（nova 6 这一批）
                // 默认开省电：呼吸描边 / 插画浮动 / 星尘这类常驻装饰动画直接关掉，
                // 逐帧 Canvas 降到约 30fps。用户在设置里可以手动改回来（写盘后不再走默认）。
                DevicePerformance.isLowEnd || DevicePerformance.isMidEnd || DevicePerformance.legacy
            )) }
        // 频谱分析器状态跟随持久化开关（服务可能先于本次读取创建）
        SpectrumAnalyzer.enabled = _state.value.visualizerEnabled
        // 触感与渐变：服务进程内的 FadeController 由 MusicService 自行 init，
        // 这里只需保证 UI 进程的 Haptics 拿到配置
        Haptics.init(app)
        // 音效预设需要读写 EQ 曲线；服务侧 attach 之前也要能落盘
        EqualizerController.init(app)
        // 冷启动恢复上次的音效预设（EQ 曲线会等 MusicService attach 后进 DSP）
        // 6.6：用户手动调过自定义 EQ（滑杆 / 手绘曲线 / 「我的 EQ」）后，
        // 绝不能被内置预设在冷启动时覆盖回默认 —— 这是「调完下次全变回去」的第二个根因。
        // 用户此后主动在设置里点别的预设走 setFxPreset，不受这个 gate 影响。
        if (!EqualizerController.hasUserCustom()) {
            FxPreset.of(_state.value.fxPreset)?.let { applyFxPreset(it) }
        }

        // 上次退出时播的那首歌。冷启动时播放器是空的，只能靠这份记录恢复；
        // 若服务还活着（划后台再回来），connectController 会用播放器里的真实曲目覆盖它。
        pendingRestoreId = prefs.getLong(KEY_LAST_SONG, -1L).takeIf { it >= 0 }
        pendingRestorePos = prefs.getLong(KEY_LAST_POS, 0L).coerceAtLeast(0L)
        pendingAutoPlay = prefs.getBoolean(KEY_LAST_PLAYING, false)
        // 恢复完整播放队列：按 ID 列表还原，保证杀进程后还能上下切歌
        pendingRestoreQueueIds = prefs.getString(KEY_LAST_QUEUE_IDS, "")
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
        pendingRestoreQueueIndex = prefs.getInt(KEY_LAST_QUEUE_INDEX, 0).coerceAtLeast(0)

        connectController()

        // R13：下载记录以前只在内存里，进程一死整份丢失——用户"下载了 58 首、
        // 界面显示下载 0 首"就是这个成因（文件都在 Music/Jun Music 里，记录没了）。
        // 这一轮把记录落盘了，同时冷启动要按磁盘真相把老用户的历史下载补回来。
        // 放在 IO 里扫（DownloadManager 内部自己 launch IO），不挡首屏。
        downloadManager.rescanDownloads()

        // 首页第一眼是榜单封面，不是本地曲库：先把榜单请求发出去，别让 16 个榜单的
        // 曲库请求把它挤在后面。缓存先铺，网络结果一到就替换（冷启动秒出）。
        restoreNeteaseHomeCache()
        loadNeteaseHomeData()

        launchSafely {
            loadLibrary()
            // 曲库就绪后静默对齐账号数据：喜欢、歌单、搜索历史全部自动回来，
            // 不需要用户再点任何「同步」按钮。
            syncLikesQuietly()
            syncPlaylistsQuietly()
            syncSearchHistoryQuietly()
            // 听歌报告：把本机历史补传 + 拉回账号累计
            syncListenStatsQuietly()
            // 补偿会员状态：登录态下静默刷新，断网时沿用上次缓存
            refreshVip()
            // 设置也跟着账号走：本机没动过 → 静默恢复云端；本机有改动 → 自动上传，
            // 两边都改过 → 留一条冲突提示让人选（SettingsCloud 里判定）
            afterAccountReady()
        }
        // 播放中每 2 分钟同步一次听歌时长；暂停时会立刻补一次（见 onIsPlayingChanged）。
        // 只在「有新增时长」时才会真正发请求，没变化时 sync 内部直接跳过上传。
        launchSafely {
            while (true) {
                delay(120_000)
                if (AccountStore.isLoggedIn && _state.value.isPlaying) {
                    runCatching { syncListenStatsQuietly() }
                }
            }
        }
        // 头像同步：上次离线换的头像补传；已登录时从服务器拉一次最新资料
        retryPendingAvatar()
        // 6.6.7：上次没同步成功的昵称也补传一次（断网/服务器未更新时改的名字）
        retryPendingNickname()
        refreshProfileFromServer()
        launchSafely { tickPosition() }
        // 6.9.3：常驻的缓冲监听。不点歌也要在，否则播放中 seek/弱网进入 BUFFERING 时
        // 播放页没有任何反馈（用户报的"播放界面没有加载条"）。
        startLoadFeedback(silent = true)
        // 睡眠定时器状态恢复：计时在 SleepTimer（进程级），若服务已 attach 过
        // 或上次定时未到期，把剩余定时映射回 UI（进度环、角标）。
        SleepTimer.attach(getApplication())
        if (SleepTimer.endMs > 0L) {
            _state.value = _state.value.copy(
                sleepTimerMs = SleepTimer.totalMs,
                sleepTimerEndMs = SleepTimer.endMs
            )
        }
        if (SleepTimer.stopAfterSong) {
            _state.value = _state.value.copy(sleepStopAfterSong = true)
        }
        sleepUnsub = SleepTimer.addExpireObserver {
            _state.value = _state.value.copy(sleepTimerMs = 0L, sleepTimerEndMs = 0L)
        }
        SleepTimer.addStopAfterSongObserver {
            _state.value = _state.value.copy(sleepStopAfterSong = SleepTimer.stopAfterSong)
        }
        // 老版本只有"每首歌累计多久"，没有按天明细。把它折算成一条条记录，
        // 升级用户第一次打开报告才不会是空的（只执行一次，见 seedFromLegacy）。
        launchSafelyIo {
            ListenStats.seedFromLegacy(PlayHistory.getTotalPlayedMs(), PlayHistory.getLastPlayed())
        }

        refreshSystemDark()
        app.registerComponentCallbacks(configCallbacks)
    }

    // ==================== 播放控制 ====================

    fun togglePlay() {
        val c = controller ?: return
        // 筛选期间不要恢复播放：当前时间线还是旧队列，按播放会把没确认的人声歌放出来
        if (_state.value.libraryFiltering && !c.isPlaying) {
            noteLibraryNotice("正在筛选纯音乐，请稍候…")
            return
        }
        if (c.isPlaying) {
            c.pause()
            // 暂停后位置就不动了，轮询不会再写，这里补一次
            persistPlayback(throttle = false)
        } else {
            if (c.mediaItemCount == 0) pushQueue(startIndex = 0, startPositionMs = 0L)
            c.play()
        }
    }

    fun previous() {
        val c = controller ?: return
        if (_state.value.libraryFiltering) {
            noteLibraryNotice("正在筛选纯音乐，请稍候…")
            return
        }
        // 播放超过 3 秒时“上一首”先回到开头，符合常见播放器手感
        if (c.currentPosition > 3_000) c.seekTo(0L) else c.seekToPrevious()
    }

    fun next() {
        if (_state.value.libraryFiltering) {
            noteLibraryNotice("正在筛选纯音乐，请稍候…")
            return
        }
        val c = controller ?: return
        // 用户手动切歌：连跳计数复位，别把上一轮的失败算到这一轮
        skipStreak = 0
        // 6.4 切歌提速：先把"接下来真要播的那两首"的地址解析发出去。
        //
        // 必须从**时间线**取，不能从 state.queue 取：随机播放时时间线是洗牌序，
        // queue 的下一个下标根本不是播完这首之后会响的那首——按 queue 预取
        // 等于预取了一首不会被播的歌，真正要播的那首仍然要现解析（用户感受就是"有时候快有时候慢"）。
        runCatching {
            val cur = c.currentMediaItemIndex
            val n = c.mediaItemCount
            val ids = buildList {
                for (off in 1..2) {
                    val idx = cur + off
                    if (idx !in 0 until n) continue
                    val sid = c.getMediaItemAt(idx).mediaId
                    // 本地文件（file:// / content://）的 mediaId 是歌曲 id，不是网易云 id，
                    // 但代理只处理纯数字的在线 id，非数字会被 prefetch 自己过滤掉
                    if (sid.isNotBlank()) add(sid)
                }
            }
            if (ids.isNotEmpty()) com.jun.musiccloud.playback.AudioProxyServer.prefetch(ids)
        }
        c.seekToNext()
    }

    /** 按进度比例跳转，fraction 取值 0f..1f */
    fun seekTo(fraction: Float) {
        val c = controller ?: return
        val duration = c.duration
        if (duration <= 0) return
        val target = (duration * fraction.coerceIn(0f, 1f)).toLong()
        c.seekTo(target)
        // 6.6.9：seek 后立刻把平滑时钟锚到目标位置，否则歌词会按旧位置再"飘"半秒
        notePlayerPosition(target, runCatching { c.isPlaying }.getOrDefault(false))
        _state.value = _state.value.copy(positionMs = target)
    }

    /** 播放队列中指定位置的歌曲 */
    fun playAt(index: Int) = playAt(index, resetSkipStreak = true)

    // ────────────────────────────────────────────────────────────
    // 6.9.0：加载反馈。点歌后这条协程每 250ms 看一眼播放器：
    //   · 没拿到地址 → "正在取源…"（顶部细条不定进度）
    //   · 超过 2 秒  → "正在切换备用音源…"
    //   · 缓冲中    → "正在从 XX 缓冲…" + 真实缓冲百分比
    //   · 超过 5 秒 → 出现"重试"按钮，不再无限干等
    //
    // 6.9.3：**这条协程改成常驻**。用户反馈"播放界面没有加载条"——
    // 旧逻辑一旦 READY 就 break，之后拖动进度条、切网络、CDN 抖动导致的
    // STATE_BUFFERING 再也没有人汇报，播放页看起来就是"卡住但什么都不显示"。
    // 现在常驻只做两件事：取源阶段报进度；播放中一旦进入 BUFFERING 就报"正在缓冲"。
    // 每 250ms 读几个标量，开销可以忽略（和已有的 tickPosition 同量级）。
    // ────────────────────────────────────────────────────────────
    private var loadTicker: Job? = null
    private var loadStartAt = 0L

    /** 是否处于「点歌后的首次取源」阶段。false 时只负责汇报播放中的缓冲 */
    @Volatile private var loadResolving = false
    private var bufferStartAt = 0L
    /** 6.9.5：加载结束后"已开始播放 / 已就绪"要停留到哪个时刻（elapsedRealtime） */
    private var loadLingerUntil = 0L

    private fun startLoadFeedback(silent: Boolean = false) {
        if (silent) {
            // 只为"播放中缓冲"起一条常驻监听，不冒充取源
            loadResolving = false
            if (loadTicker?.isActive == true) return
        } else {
            loadStartAt = android.os.SystemClock.elapsedRealtime()
            bufferStartAt = 0L
            loadResolving = true
            _state.value = _state.value.copy(loadText = "正在取源…", loadBuffered = -1f, loadCanRetry = false)
            if (loadTicker?.isActive == true) return
        }
        loadTicker = viewModelScope.launch {
            while (isActive) {
                delay(250)
                val c = controller ?: continue
                val ps = runCatching { c.playbackState }.getOrDefault(Player.STATE_IDLE)
                val now = android.os.SystemClock.elapsedRealtime()
                val prov = runCatching { OnlineMusicApi.lastProviderName }.getOrDefault("")

                if (loadResolving) {
                    // 取源完成：不立刻抹掉，先亮 1.6 秒「已开始播放」——
                    // 6.9.5 用户要求"显示一段时间"，一闪而过等于没提示。
                    if (ps == Player.STATE_READY) {
                        loadResolving = false
                        loadLingerUntil = now + 1600L
                        _state.value = _state.value.copy(
                            loadText = "已开始播放", loadBuffered = 1f, loadCanRetry = false
                        )
                        continue
                    }
                    val el = now - loadStartAt
                    val buffering = ps == Player.STATE_BUFFERING
                    val text = when {
                        buffering && prov.isNotBlank() -> "正在从 $prov 缓冲…"
                        el < 2000L -> "正在取源…"
                        el < 5000L -> "正在切换备用音源…"
                        else -> "还没取到 · 可点重试"
                    }
                    _state.value = _state.value.copy(
                        loadText = text,
                        loadBuffered = if (buffering) {
                            runCatching { c.bufferedPercentage }.getOrDefault(-1) / 100f
                        } else -1f,
                        loadCanRetry = el >= 5000L
                    )
                } else {
                    // 播放中的二次缓冲（seek / 弱网 / CDN 抖一下）
                    if (ps == Player.STATE_BUFFERING) {
                        if (bufferStartAt == 0L) bufferStartAt = now
                        val el = now - bufferStartAt
                        _state.value = _state.value.copy(
                            loadText = if (prov.isNotBlank()) "正在缓冲 · $prov" else "正在缓冲…",
                            loadBuffered = runCatching { c.bufferedPercentage }.getOrDefault(-1) / 100f,
                            loadCanRetry = false
                        )
                        // 卡超过 8 秒：给重试，不再无限转
                        if (el >= 8000L) {
                            _state.value = _state.value.copy(loadText = "缓冲卡住了 · 可点重试", loadCanRetry = true)
                        }
                    } else {
                        bufferStartAt = 0L
                        val t = _state.value.loadText
                        when {
                            // 缓冲刚结束：先给 1.2 秒「已就绪」，别一闪就没
                            t.startsWith("正在缓冲") || t.startsWith("缓冲卡住") -> {
                                loadLingerUntil = now + 1200L
                                _state.value = _state.value.copy(
                                    loadText = "已就绪 · 继续播放", loadBuffered = 1f, loadCanRetry = false
                                )
                            }
                            // 停留时间到了才真正收掉
                            t.startsWith("已开始播放") || t.startsWith("已就绪") -> {
                                if (now >= loadLingerUntil) {
                                    _state.value = _state.value.copy(
                                        loadText = "", loadBuffered = -1f, loadCanRetry = false
                                    )
                                }
                            }
                            // 别处（比如切歌）刚写的那条不动
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    /** 加载卡住时用户点"重试"：原地重新 prepare 当前曲目 */
    fun retryLoading() {
        val c = controller ?: return
        runCatching {
            c.seekTo(0)
            c.prepare()
            c.play()
        }
        startLoadFeedback()
    }

    /**
     * 6.9.1「我的下载」专听：把已下载的本机文件直接建成队列播放。
     *
     * uri 用 file:// 直读，**不经过解析与代理**——飞行模式也能完整听，
     * 出声的就是本机文件本身，不存在"解析失败 / 无版权"这条失败路径。
     * songId 留空：有 neteaseId 也不走在线解析，那会把"纯本地"偷偷变回"现求人"。
     *
     * [shuffle] = true 时先打上"用户自选过播放模式"的标记再建队列，
     * 否则 playList 里的 ensureDefaultSequential 会把随机按回顺序。
     */
    fun playDownloaded(tasks: List<DownloadTask>, startIndex: Int = 0, shuffle: Boolean = false) {
        val songs = tasks.mapNotNull { t ->
            val p = t.localPath
            if (p.isBlank()) return@mapNotNull null
            val f = File(p)
            // 4KB 以下按残片处理（下载中断的尾巴文件），不放进去
            if (!f.exists() || f.length() < 4096L) return@mapNotNull null
            Song(
                id = t.id,
                title = t.title,
                artist = t.artist.ifBlank { "未知歌手" },
                album = "我的下载",
                durationMs = 0L,
                uri = Uri.fromFile(f),
                albumArtUri = null,
                filePath = p,
                folderPath = "downloads",
                folderName = "我的下载",
                songId = "",
                format = p.substringAfterLast('.', "").lowercase()
            )
        }
        if (songs.isEmpty()) return
        if (shuffle) {
            prefs.edit().putBoolean(KEY_SHUFFLE, true).putBoolean(KEY_PLAYMODE_CHOSEN, true).apply()
            _state.value = _state.value.copy(shuffle = true)
        }
        playList(songs, startIndex, title = "我的下载")
    }


    /**
     * [resetSkipStreak]：用户手动点歌传 true（连跳计数清零）；
     * **自动跳歌必须传 false** —— 否则每跳一首都把计数清零，
     * 整张歌单都没版权时会一直跳下去，永远到不了 [COPYRIGHT_SKIP_LIMIT] 的上限。
     */
    private fun playAt(index: Int, resetSkipStreak: Boolean) {
        // R27：控制器还没连上时不能静默返回——那正是"点了死活没反应"的一种。
        // 记下这次点击，连接建立后自动补播。
        val c = controller ?: run {
            if (index in _state.value.queue.indices) {
                pendingPlayIndex = index
                _state.value = _state.value.copy(currentIndex = index)
                ensureControllerConnected()
            }
            return
        }
        // 用户手动点歌：连跳计数复位，后面再失败从 1 重新数
        if (resetSkipStreak) skipStreak = 0
        val queue = _state.value.queue
        if (index !in queue.indices) return
        // 筛选还没结束时，队列里可能还留着上一份未确认的列表，别让手动点播绕过校验
        if (_state.value.libraryFiltering && queue[index].id !in libraryCheckedIds) {
            noteLibraryNotice("正在筛选纯音乐，请稍候…")
            return
        }
        val wantId = queue[index].id.toString()
        // 6.9.0：点歌即开加载反馈（取源/切换/缓冲全程有状态，不再干等）
        startLoadFeedback()
        // R27：点击立刻把高亮移过去。解析/缓冲最快也要几百毫秒，这期间界面一动不动
        // 用户就会以为"点了没反应"，然后反复点。后面的 syncFromController 会用真实
        // 播放状态校正，这里只是先给一个即时反馈。
        if (_state.value.currentIndex != index) {
            _state.value = _state.value.copy(currentIndex = index)
        }

        // 6.3.9：点歌到出声这一段里，唯一真正"用户能感知"的就是首次字节。
        // 音频地址要现解析（GD 聚合一次 RTT，弱网 1~3 秒），这件事必须在
        // seek/setMediaItems **之前**发起——原来放在 c.play() 后面，
        // 等于"先让播放器去要一个还没解析好的地址，再慢慢解析"，白等一个来回。
        // 6.7.2：这一首要**立即**解析，不能进预取队列排队。
        // 用户可能刚连着点了三首、上一轮预取还占着 6 个线程，
        // 排队意味着"点了之后盯着转圈"——这正是这轮要干掉的东西。
        com.jun.musiccloud.playback.AudioProxyServer.prefetchNow(
            listOfNotNull(queue[index].songId.takeIf { it.isNotBlank() })
        )

        // 播放器时间线开着随机时是洗牌序，主页 index 在时间线里没有对应位置。
        // 直接在时间线上找目标歌的当前下标；找不到（时间线还是旧队列）就重写。
        val playIdx = (0 until c.mediaItemCount).firstOrNull { i ->
            c.getMediaItemAt(i).mediaId == wantId
        }

        if (playIdx != null && timelineMatchesQueue(c)) {
            c.seekTo(playIdx, 0L)
            // 上一首解码失败等原因会让播放器停在 IDLE，此时 play() 不生效
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
        } else {
            // 不能走 pushQueue——它在"当前曲目仍在播放"时会跳过重写直接返回，
            // 于是播放器一直留着旧队列，用户点哪首都只是高亮变了、声音没换。
            // 用户明确点歌时必须以 UI 队列为准重写（随机开启时目标置顶重洗）。
            //
            // R13：进入这一支的判据从"时间线里有这首歌 && 条数相等"改成"时间线与
            // state.queue 逐条同源"。条数恰好相等而顺序不同是真实会出现的（上次会话保存的
            // 队列、后台重算出的整个曲库、换筛选后长度一样的列表），旧判据会让我们 seek
            // 进那份旧时间线：声音是换了这首歌，"播完这首之后的下一首"却还是旧顺序里的
            // 另一首——用户看到的正是"顺序播放不顺序"。
            writeTimelineFromQueue(c, index, 0L)
        }
        c.play()
        _state.value = _state.value.copy(currentIndex = index)
        armStartWatchdog(queue[index])
        // R27：点完 1.6 秒还没动静就强制重来一次，兜住"命令发了但没接管"的静默失败
        verifyStartAfterTap(queue[index], index)
        // 6.4：点歌即播 → 立刻把**接下来真会播的那几首**的真实地址解析起来。
        //
        // 取时间线而不是 queue：上面刚写完时间线，此刻时间线的 next 就是播放器
        // 真正会播的下一首（随机时是洗牌序里的那首）。按 queue 下标预取在随机模式下
        // 会预取错歌——这正是"有的歌切过去秒响、有的要等两秒"的来源。
        //
        // 起点用 0 而不是 currentMediaItemIndex：writeTimelineFromQueue 把目标曲放在了
        // 窗口第 0 位，MediaController 的 currentMediaItemIndex 是**异步**更新的，
        // 这里读到的可能还是旧值。第 0 位就是用户刚点的那首，往后 4 首就是接下来要放的。
        runCatching {
            val n = c.mediaItemCount
            val ids = buildList {
                for (i in 1 until minOf(n, 6)) {
                    val sid = c.getMediaItemAt(i).mediaId
                    if (sid.isNotBlank()) add(sid)
                }
            }
            if (ids.isNotEmpty()) com.jun.musiccloud.playback.AudioProxyServer.prefetch(ids)
        }
        // 预取下一首 / 上一首封面，切歌时封面直接从内存缓存取，不再转圈
        val q = _state.value.queue
        // 预热范围放宽到前后各 8 首：封面墙一打开就有图，不再逐张「转圈补图」
        val prefetchPaths = (-8..8)
            .filter { it != 0 }
            .mapNotNull { q.getOrNull(index + it)?.filePath }
        if (prefetchPaths.isNotEmpty()) ArtworkLoader.prefetch(prefetchPaths)
    }

    /**
     * 按歌曲对象播放（推荐卡片入口）。
     *
     * 不接受"UI 先算好下标再传进来"：推荐基于全库打分，而队列可能被文件夹或
     * 收藏筛选过，推荐的歌不在队列里时 `indexOfFirst` 返回 -1，调用方一句
     * `if (idx >= 0)` 就把点击静默吞掉了。这里改为找不到就把歌插进队列再播。
     */
    fun playSong(song: Song) {
        if (controller == null) return
        // 图书馆模式：确认带人声的直接挡掉；还没判过的先查歌词再决定
        if (_state.value.libraryMode) {
            if (song.id in libraryBlockedIds || LibraryMode.isLikelyVocal(song)) {
                noteLibraryNotice("「${song.title}」带人声歌词，图书馆模式不播放")
                return
            }
            if (song.id !in libraryCheckedIds) {
                verifyLibrarySongThen(song) { playSong(song) }
                return
            }
        }
        // 首页点歌 / 推荐点歌：把这首歌追加到「为你推荐」列表里，
        // 但「我喜欢」视图和自定义歌单视图下不追加——那是用户自己的列表。
        addToRecommendationsIfAppropriate(song)
        val queue = _state.value.queue

        val idx = queue.indexOfFirst { it.id == song.id }
        if (idx >= 0) {
            playAt(idx)
            recordSinglePlay(song)
            return
        }

        // 插到当前曲目后面，保留用户的筛选条件而不是把筛选清掉
        val insertAt = (_state.value.currentIndex + 1).coerceIn(0, queue.size)
        _state.value = _state.value.copy(
            queue = queue.toMutableList().apply { add(insertAt, song) }
        )
        // 队列变长，playAt 会检测到不同步并重写播放器队列
        playAt(insertAt)
        recordSinglePlay(song)
    }

    /**
     * 6.9.5：把「点单曲」这件事记进最近播放。
     *
     * 为什么必须补这一条：以前只有「播放全部 / 播放整张歌单」才会生成一条
     * 最近播放会话（见 [playList] 的 addToRecent）。用户平时大多是**一首一首点着听**，
     * 这种听法一条会话都不留 —— 他刚听的歌在最近播放里永远找不到，
     * 重启后看到的还是很久以前那份"播放全部"的记录，
     * 描述出来就是"最近播放里的歌没了，变回之前的了"。
     *
     * 现在用一个固定的滚动会话「最近听过的单曲」兜住：最新点的排最前，
     * 按歌曲身份去重，最多留 100 首。它总是排在最近播放的第一位，
     * 不和整张歌单的会话混在一起。
     */
    private fun recordSinglePlay(song: Song) {
        if (song.id <= 0L) return
        val title = "最近听过的单曲"
        val cur = _state.value.recentPlaybacks
        val merged = (listOf(song) + cur.firstOrNull { it.title == title }?.songs.orEmpty()
            .filterNot { it.identityKey == song.identityKey })
            .take(100)
        val next = (listOf(RecentPlayback(title = title, songs = merged)) +
            cur.filterNot { it.title == title }).take(12)
        _state.value = _state.value.copy(recentPlaybacks = next)
        PlayHistory.saveRecentPlaybacks(next)
    }

    /**
     * 首页纵向列表点歌：以「当前正在浏览的列表」为播放队列，从点中的这首开始播。
     *
     * 为什么不能直接用 [playAt]：playAt 的下标是**播放队列**下标，而主页列表拿的是
     * homeSongs 下标。从榜单 / 歌单 / 搜索点过歌之后 queue 已经不是 homeSongs，
     * 直接 playAt(index) 会播到队列里另一首歌（或下标越界干脆没反应）。
     * 这里显式把队列切成用户正在看的这一屏，下标语义才重新对齐，
     * 主页高亮 / 侧边定位 / 上下切歌全部随之正确。
     *
     * 队列已经是同一份列表时（连续点本列表的歌）走 [playAt]，不重建时间线，
     * 避免每点一首都重写 MediaItems 导致的短暂卡顿。
     */
    fun playFromHome(index: Int) {
        val songs = _state.value.homeSongs
        if (index !in songs.indices) return
        val tapped = songs[index]
        // 图书馆模式：点的是带人声的歌就明确拦下，不能偷换成列表里的另一首
        val handled = guardLibrarySingle(tapped) {
            val i = _state.value.homeSongs.indexOfFirst { it.id == tapped.id }
            if (i >= 0) playFromHome(i)
        }
        if (handled) return
        val queue = _state.value.queue
        // R13：判据从"长度相同 + 只有被点这一行相同"改成"整份顺序相同"。
        // 只比一行的话，队列是上一次打乱顺序、首页是新一次打乱顺序时（曲库刷新会换
        // librarySeed）也可能恰好撞上同一行是同一首歌——于是走 playAt 不重建队列，
        // 播放队列继续按**旧顺序**往后走，用户在首页看到的却是新顺序，
        // 表现又是"顺序播放下一首不对"。整份比一次，几毫秒的开销，判得干净。
        val sameList = queue.size == songs.size &&
            queue.indices.all { queue[it].id == songs[it].id }
        if (sameList) {
            playAt(index)
            recordSinglePlay(songs[index])
            return
        }
        // 退回到「全部音乐」视图：点首页的歌就是在听曲库，不再停留在我喜欢/歌单视图里
        clearUserListViewContext()
        playList(songs, index, title = "全部音乐", addToRecent = false)
        recordSinglePlay(songs[index])
    }

    /**
     * 把刚播放的歌追加到「最近播放」列表末尾。
     * 仅在首页（全部音乐 / 文件夹）视图下追加；「我喜欢」和自定义歌单视图不追加，
     * 因为那是用户自己的列表，不应混入最近播放。
     * 列表上限 20 首，超出后丢弃最早的。
     */
    private fun addToRecommendationsIfAppropriate(song: Song) {
        val s = _state.value
        if (s.onlyLiked || s.activePlaylistId != null) return
        val cur = s.recommendations
        if (cur.any { it.id == song.id }) return
        val next = (cur + song).takeLast(20)
        _state.value = _state.value.copy(
            recommendations = next,
            recommendationsVersion = s.recommendationsVersion + 1
        )
    }

    /**
     * 下一首播放：把歌曲插到当前曲目之后，正在播放的音乐不中断。
     * 已在队列里则把它搬过来（避免同一首歌在队列中出现两份）。
     */
    fun playNext(song: Song) {
        if (_state.value.libraryMode) {
            if (song.id in libraryBlockedIds || LibraryMode.isLikelyVocal(song)) {
                noteLibraryNotice("「${song.title}」带人声歌词，图书馆模式不播放")
                return
            }
            if (song.id !in libraryCheckedIds) {
                verifyLibrarySongThen(song) { playNext(song) }
                return
            }
        }
        val cur = _state.value.queue
        val list = cur.toMutableList()
        var idx = _state.value.currentIndex
        val exist = list.indexOfFirst { it.id == song.id }
        if (exist >= 0) {
            list.removeAt(exist)
            if (exist < idx) idx--
        }
        val at = (idx + 1).coerceIn(0, list.size)
        list.add(at, song)
        _state.value = _state.value.copy(queue = list, currentIndex = idx)
        rebuildPlayOrder()
    }

    /** 用给定歌单替换播放队列并从第一首开始播放（搜索结果「播放全部」等场景） */
    fun playList(
        songs: List<Song>,
        startIndex: Int = 0,
        title: String = "最近播放",
        addToRecent: Boolean = true,
        /**
         * 6.3.2：是否保留「当前在听哪个自建歌单」的上下文。
         *
         * 用户现场：放着自建歌单 A，去首页点另一个歌单的歌播放，再打开播放列表，
         * 里面还是 A 的歌单界面。根因是 activePlaylistId 只在部分入口被清空，
         * 首页歌单的播放入口没清，于是播放列表一打开就自动下钻回 A。
         *
         * 现在统一收口在 [playList]：换队列默认就清空歌单上下文，
         * 只有「确实是在播这个自建歌单」的两个入口（[playFromPlaylist] /
         * [playPlaylistAll]）显式传 true。
         */
        keepPlaylistContext: Boolean = false
    ) {
        if (songs.isEmpty()) return
        // 换了一整份队列（点歌单/搜索结果播放全部）：连跳计数从头开始
        skipStreak = 0
        if (!keepPlaylistContext) {
            // R27：onlyLiked 之前漏了。用户现场：在「我喜欢」里听歌，去搜索页点一首，
            // 打开播放列表仍然停在「我喜欢」页、里面根本没有刚点的那首。
            // 现在收口在 [clearUserListViewContext]，别再各写一份。
            clearUserListViewContext()
        }
        // 6.1.2：播放模式归位收口在这一处（原来只有 playFromPlaylist / playPlaylistAll /
        // playLikedAll 三个入口调，playSong / playFromHome / playFromSearch / playFromArtist /
        // playFromNeteaseList / playToplistFull / playRecentPlayback / playSongById 这些
        // 全都绕过）。这里是一条不漏的收口：任何把队列换成一整份列表的动作都按顺序起步，
        // 用户真点过随机/单曲循环（KEY_PLAYMODE_CHOSEN）时 ensureDefaultSequential 自己会跳过。
        ensureDefaultSequential()
        if (_state.value.libraryMode) {
            // 图书馆模式：先把列表筛成确认纯音乐，再建队列播放
            val requested = songs.getOrNull(startIndex.coerceIn(0, songs.lastIndex))
            val source = buildLibrarySource(songs)
            if (source.isEmpty()) {
                noteLibraryNotice("图书馆模式：这个列表里没有适合的歌曲")
                return
            }
            startLibrarySession(
                source = source,
                preferred = requested,
                wasPlaying = controller?.isPlaying == true,
                autoPlay = true,
                title = title,
                addToRecent = addToRecent
            )
            return
        }
        val requested = songs.getOrNull(startIndex.coerceIn(0, songs.lastIndex))
        val list = songs.toList()
        // 初筛可能拿掉了目标歌：按身份键重新定位，找不到就从第一首开始
        val found = requested?.let { want ->
            list.indexOfFirst { it.identityKey == want.identityKey }.takeIf { it >= 0 }
        }
        val idx = found ?: if (requested == null) startIndex.coerceIn(0, list.lastIndex) else 0
        if (addToRecent) {
            // 记录到「最近播放」：除「我喜欢」和自定义歌单外，整张播放的歌单都会进最近播放，
            // 左右滑可切回之前听过的歌单。同名的不重复入栈，而是挪到最前。
            val cur = _state.value.recentPlaybacks
            val filtered = cur.filterNot { it.title == title }
            val next = (listOf(RecentPlayback(title = title, songs = list)) + filtered).take(12)
            _state.value = _state.value.copy(queue = list, currentIndex = idx, recentPlaybacks = next)
            PlayHistory.saveRecentPlaybacks(next)
        } else {
            _state.value = _state.value.copy(queue = list, currentIndex = idx)
        }
        // R13：playList 建起来的这份就是"用户亲自选中的列表"（歌单 / 搜索结果 /
        // 网易云榜单 / 我喜欢 / 全部音乐），从这一刻起它就是这个会话的队列，
        // 后台那几次异步 applyFilters 不能再把它换成浏览视图。
        pinQueueSession()
        playAt(idx)
        // 立即落盘播放队列 + 当前曲目，避免杀进程后队列丢失（5 秒节流来不及）
        persistPlayback(throttle = false)
    }

    /**
     * 网易云榜单/歌单里点单首歌：队列切到该榜单/歌单全部歌曲（从这首开始），
     * 便于上下切歌，但不写入「最近播放」——只有「播放全部」才入栈。
     */
    fun playFromNeteaseList(song: Song) {
        val songs = _state.value.neteaseListSongs
        val handled = guardLibrarySingle(song) { playFromNeteaseList(song) }
        if (handled) return
        if (songs.isEmpty()) { playSong(song); return }
        val idx = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playList(songs, idx, title = _state.value.neteaseListTitle.ifBlank { "网易云" }, addToRecent = false)
        // 播放后清空返回目标：用户已切到播放态，再按返回应直接回首页，
        // 而不是回到「全部歌单/排行榜」列表。
        _state.value = _state.value.copy(neteaseListReturnTo = "")
    }

    /** 播放某条最近播放会话（点击「最近播放」里的某一页），可从该会话第 startIndex 首开始 */
    fun playRecentPlayback(rp: RecentPlayback, startIndex: Int = 0) {
        if (rp.songs.isEmpty()) return
        if (_state.value.libraryMode) {
            playList(rp.songs, startIndex, title = rp.title, addToRecent = false)
            return
        }
        val requested = rp.songs.getOrNull(startIndex.coerceIn(0, rp.songs.lastIndex))
        val songs = rp.songs
        val idx = requested?.let { want ->
            songs.indexOfFirst { it.identityKey == want.identityKey }.takeIf { it >= 0 }
        } ?: if (requested == null) startIndex.coerceIn(0, songs.lastIndex) else 0
        // 6.4 修：点「最近播放」= 换了一份新队列，必须先离开「我喜欢 / 歌单」视图。
        // 不清的话 onlyLiked / activePlaylistId 会一直挂着，下次打开播放列表面板
        // 就按旧标记落在「我喜欢」或某个歌单里，而不是用户刚听的这份。
        // 注意要在 pinQueueSession() **之前**清：它会把当前视图签名记下来当"用户亲手选的会话"。
        clearUserListViewContext()
        _state.value = _state.value.copy(queue = songs, currentIndex = idx)
        // R13：点「最近播放」里的某一页同样是一次显式选队列，见 playList 里的说明
        pinQueueSession()
        playAt(idx)
    }

    /** 重命名某条最近播放会话（只改展示名，不动底层歌单） */
    fun renameRecentPlayback(index: Int, newName: String) {
        val n = newName.trim()
        if (n.isEmpty()) return
        val list = _state.value.recentPlaybacks
        if (index !in list.indices) return
        val updated = list.toMutableList()
        updated[index] = updated[index].copy(title = n)
        _state.value = _state.value.copy(recentPlaybacks = updated)
        PlayHistory.saveRecentPlaybacks(updated)
    }

    /**
     * 从「最近播放」删除一条会话。
     * 不可逆，所以删之前挂一条撤销：4 秒窗口内按「撤销」把整份列表按原顺序放回来
     * （存的是删除前的快照，不是"插回一条"，因此连续删多条也能各自撤销到位）。
     */
    fun deleteRecentPlayback(index: Int) {
        val list = _state.value.recentPlaybacks
        if (index !in list.indices) return
        val updated = list.toMutableList().also { it.removeAt(index) }
        val removed = list[index]
        requestUndo("已删除「${removed.title.ifBlank { "未命名" }}」") {
            restoreRecentPlaybacks(list)
        }
        _state.value = _state.value.copy(recentPlaybacks = updated)
        PlayHistory.saveRecentPlaybacks(updated)
    }

    /** 清空最近播放（同样给一次反悔） */
    fun clearRecentPlaybacks() {
        val snapshot = _state.value.recentPlaybacks
        if (snapshot.isEmpty()) return
        requestUndo("已清空最近播放") { restoreRecentPlaybacks(snapshot) }
        _state.value = _state.value.copy(recentPlaybacks = emptyList())
        PlayHistory.saveRecentPlaybacks(emptyList())
    }

    /** 撤销用：把「最近播放」恢复成某份快照 */
    private fun restoreRecentPlaybacks(snapshot: List<RecentPlayback>) {
        if (snapshot.isEmpty()) return
        _state.value = _state.value.copy(recentPlaybacks = snapshot)
        PlayHistory.saveRecentPlaybacks(snapshot)
    }

    /**
     * 把歌曲作为「下一首」插进队列，不打断正在播放的歌。
     * 队列为空（还没有正在播放的歌）时直接播放它。
     * 已在队列里则把它挪到当前曲目的下一首，不重复添加。
     */
    fun enqueueNext(song: Song) {
        if (_state.value.currentIndex < 0 || _state.value.queue.isEmpty()) {
            playSong(song)
        } else {
            playNext(song)
        }
    }

    /**
     * 搜索结果点歌：把歌加进「当前所在的列表」（我喜欢 / 当前歌单），并直接在底部小播放器开播，
     * 不跳到播放页、也不排入"下一首"。首页（全部音乐）下不动任何列表，直接播。
     */
    /**
     * 搜索页点歌：**只试听，不写任何用户数据**（6.3.5 修正）。
     *
     * 旧实现会在两种情况下静默写库，用户完全无感：
     *  - `activePlaylistId != null`（之前打开过某个自建歌单，哪怕早就切走了）→
     *    直接把这首 addToPlaylist 进那个歌单，还会实时同步到云端；
     *  - 当前在「我喜欢」视图 → 直接 toggleLike 帮用户点了赞。
     *
     * 用户现场：「搜索出来的歌，点开随便一个就直接加入我的歌单了，无痛无觉」。
     * 这不是功能，是数据污染——搜索页的语义是「找歌、试听」，不是「往当前列表里收藏」。
     * 而且 activePlaylistId 会被持久化并在下次启动恢复，所以「我明明没打开歌单」
     * 也会中招，表现就是完全莫名其妙。
     *
     * 现在一律只播放。真想加歌单，搜索页每首歌的「⋯ / 加入歌单」是显式入口，
     * 用户点那个才写，且带确认。
     */
    fun playFromSearch(song: Song) {
        if (controller == null) return
        // R27：搜索点歌必须是「一次独立的试听」，不能继承「我喜欢 / 某个歌单」的上下文。
        //
        // 用户现场：在「我喜欢」里听歌 → 去搜索页点一首 → 打开播放列表，
        // 还是「我喜欢」那一页，里面压根没有刚点的歌，只能每次手动滑到「最近播放」。
        //
        // 现在的语义：队列 = 本次搜索结果（只有一首也无所谓），并在「最近播放」里
        // 留一条「搜索：关键词」的会话。打开播放列表默认就是那一页，能直接看到刚点的歌，
        // 还能顺着搜索结果往下听。
        val results = _state.value.searchResults
        val list = if (results.size > 1 && results.any { it.id == song.id }) results else listOf(song)
        val idx = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        val kw = _state.value.searchQuery.trim().take(12)
        playList(
            songs = list,
            startIndex = idx,
            title = if (kw.isBlank()) "搜索" else "搜索：$kw",
            // 入「最近播放」：这样播放列表第一页（最近播放）就能看到这次试听
            addToRecent = true,
            // 显式不保留歌单上下文：搜索 ≠ 往当前歌单里塞歌
            keepPlaylistContext = false
        )
    }


    // ==================== 下载（实现已搬到 playback/DownloadManager） ====================
    // PlayerViewModel 只保留转发：下载是 300 行自成一块的逻辑（任务表、系统 DownloadManager、
    // 手动 HTTP 兜底、进度轮询），留在 ViewModel 里会把它撑成一个什么都管的类。

    fun downloadSong(song: Song) = downloadManager.downloadSong(song)
    fun downloadSongs(songs: List<Song>) = downloadManager.downloadSongs(songs)
    fun deleteDownload(taskId: Long, deleteFile: Boolean = true) = downloadManager.deleteDownload(taskId, deleteFile)
    fun deleteDownloads(taskIds: List<Long>, deleteFile: Boolean = true) = downloadManager.deleteDownloads(taskIds, deleteFile)
    fun setDownloadPath(path: String) = downloadManager.setDownloadPath(path)
    fun setDownloadPathConfirmed(confirmed: Boolean) = downloadManager.setDownloadPathConfirmed(confirmed)
    fun setMiniPlayerPersist(persist: Boolean) = downloadManager.setMiniPlayerPersist(persist)
    fun retryDownload(taskId: Long) = downloadManager.retryDownload(taskId)

    /**
     * R13：重新扫描本机下载目录，把"磁盘上真实存在的歌"同步回下载列表与计数。
     * 冷启动已经在 init 里自动扫过一次（不 force，同一进程只跑一遍）；
     * 这一条是给 UI 留的手动入口——用户在文件管理器里删了歌、或者刚进"下载管理"
     * 页面时调一下，看到的数量就跟手机里真实的歌对得上（SettingsScreen / MainScreen
     * 的下载管理入口挂上即可，不挂也不影响冷启动那次自动扫描）。
     */
    fun rescanDownloads() = downloadManager.rescanDownloads(force = true)

    /**
     * 上一次重扫有没有真的读到下载目录。false 就说明"显示 0 首"是**读不到**、不是**没有**，
     * UI 应该提示用户重新选择/授权下载目录（SettingsScreen 的「下载目录」那一行），
     * 不要让用户以为自己下的歌被删了。
     */
    fun lastDownloadScanReadable(): Boolean = downloadManager.lastScanReadable()




    /**
     * 「我喜欢」列表里点歌：把播放队列切到全部我喜欢的歌，从点中的这首开始播，
     * 并记住「我喜欢」视图（下次打开播放列表还停在我喜欢）。不跳到播放页。
     */
    fun playFromLiked(index: Int) {
        val liked = _state.value.likedSongs
        if (index !in liked.indices) return
        val tapped = liked[index]
        val handled = guardLibrarySingle(tapped) {
            val i = _state.value.likedSongs.indexOfFirst { it.id == tapped.id }
            if (i >= 0) playFromLiked(i)
        }
        if (handled) return
        _state.value = _state.value.copy(onlyLiked = true, activePlaylistId = null)
        prefs.edit()
            .putBoolean(KEY_ONLY_LIKED, true)
            .putLong(KEY_ACTIVE_PLAYLIST, -1L)
            .apply()
        // 6.6.9：**必须传 keepPlaylistContext=true**。
        // 上一行刚把 onlyLiked 置为 true，而 playList 默认会清空"我喜欢/歌单"上下文——
        // 于是刚设上的标记立刻被自己抹掉，打开播放列表永远落在「最近播放」。
        // 用户原话："我在我喜欢里点开的歌，打开播放列表，一定要在我喜欢里面打开。"
        playList(liked, index, title = "我喜欢", addToRecent = false, keepPlaylistContext = true)
    }

    /**
     * 歌单里点歌：把播放队列切到该歌单的全部歌曲，从点中的这首开始播，
     * 并记住这个歌单视图。不跳到播放页。
     * 单曲点击不写入「最近播放」——只有「播放全部」才会入栈，避免点一首就污染最近播放。
     */
    /**
     * R14（用户："歌单播放那个反正默认的话都是顺序播放，刚下这个界面，默认就是顺序播放"）：
     *
     * 规则：**没被用户明确选过的播放模式，一律按"顺序播放"起步**。
     *  - 全新安装：`KEY_SHUFFLE` / `KEY_REPEAT` 从来没写过，取的就是 false / OFF，本来就是顺序；
     *  - 覆盖安装到旧机：prefs 里可能留着上一轮试"随机"按钮时留下的 shuffle=true，
     *    而用户从没打算要它 —— 这种"来历不明的随机"在每次进入播放时按回顺序。
     * 判据用 `KEY_PLAYMODE_CHOSEN`：只有 `toggleShuffle` / `cyclePlayMode`（用户真的点了）
     * 才写 true。写了就从此尊重用户的选择，不再自作主张。
     *
     * 只改状态、不动队列内容：此时 `playList()` 还没铺时间线，改完这两个字段
     * 后面的 `applyPlaybackModes()` / `playOrder()` 自然按顺序铺。
     *
     * 6.1.2 修：这里必须自己下发一次 [applyPlaybackModes]。旧写法只改 `_state` + prefs，
     * 而 `playAt()` 并不碰 repeat/shuffle 的下发（它只 seek / 重写时间线），于是
     * ExoPlayer 还留着上一轮的 `REPEAT_MODE_ONE`——界面写着「顺序播放」，播放器仍在单曲循环。
     * `applyPlaybackModes()` 只依赖 controller 与 `_state`，在铺时间线之前调它是安全的。
     */
    private fun ensureDefaultSequential() {
        if (prefs.getBoolean(KEY_PLAYMODE_CHOSEN, false)) return
        val s = _state.value
        if (!s.shuffle && s.repeatMode == RepeatMode.OFF) return
        _state.value = s.copy(shuffle = false, repeatMode = RepeatMode.OFF)
        prefs.edit()
            .putBoolean(KEY_SHUFFLE, false)
            .putString(KEY_REPEAT, RepeatMode.OFF.name)
            .apply()
        // 改完必须立刻同步给播放器，否则状态与真实行为两份账（界面上是顺序、耳朵里是循环）
        applyPlaybackModes()
    }

    fun playFromPlaylist(playlistId: Long, index: Int) {
        val songs = songsOfPlaylist(playlistId)
        if (index !in songs.indices) return
        val tapped = songs[index]
        val handled = guardLibrarySingle(tapped) {
            val i = songsOfPlaylist(playlistId).indexOfFirst { it.id == tapped.id }
            if (i >= 0) playFromPlaylist(playlistId, i)
        }
        if (handled) return
        _state.value = _state.value.copy(activePlaylistId = playlistId, onlyLiked = false)
        prefs.edit()
            .putLong(KEY_ACTIVE_PLAYLIST, playlistId)
            .putBoolean(KEY_ONLY_LIKED, false)
            .apply()
        val pl = _state.value.playlists.firstOrNull { it.id == playlistId }
        playList(songs, index, title = pl?.name ?: "歌单", addToRecent = false, keepPlaylistContext = true)
    }

    /** 播放整张歌单（「播放全部」按钮）：会写入「最近播放」 */
    fun playPlaylistAll(playlistId: Long) {
        val songs = songsOfPlaylist(playlistId)
        if (songs.isEmpty()) return
        _state.value = _state.value.copy(activePlaylistId = playlistId, onlyLiked = false)
        prefs.edit()
            .putLong(KEY_ACTIVE_PLAYLIST, playlistId)
            .putBoolean(KEY_ONLY_LIKED, false)
            .apply()
        val pl = _state.value.playlists.firstOrNull { it.id == playlistId }
        playList(songs, 0, title = pl?.name ?: "歌单", addToRecent = true, keepPlaylistContext = true)
    }

    /** 播放整张「我喜欢」（「播放全部」按钮）：会写入「最近播放」 */
    fun playLikedAll() {
        val liked = _state.value.likedSongs
        if (liked.isEmpty()) return
        _state.value = _state.value.copy(onlyLiked = true, activePlaylistId = null)
        prefs.edit().putBoolean(KEY_ONLY_LIKED, true).putLong(KEY_ACTIVE_PLAYLIST, -1L).apply()
        // 6.6.9：同上，保留「我喜欢」上下文，别被 playList 的默认清空逻辑抹掉
        playList(liked, 0, title = "我喜欢", addToRecent = true, keepPlaylistContext = true)
    }

    /** 取一个歌单的完整歌曲列表（曲库 + 在线缓存按 ID 还原，按加入顺序） */
    /**
     * 歌单 id → 曲目列表 的缓存。
     *
     * 6.3.2「打开歌单特别慢」的根因：这个函数每次调用都先 `allSongs.associateBy{}`
     * 重建一张全库 HashMap（3000 首≈3ms，本身就能掉一帧），而弹层一次组合里
     * 标题计数、能否多选、列表本体要各调一次——打开一个歌单就是连做 3~4 次全库索引。
     * 现在索引按"曲库版本"缓存一次；歌单内容变了按键失效。
     */
    private var songIndexCache: Map<Long, Song>? = null
    private var songIndexKey: String = ""
    private val playlistSongsCache = HashMap<Long, List<Song>>()

    private fun songIndex(): Map<Long, Song> {
        val all = _state.value.allSongs
        // 用条数 + 首尾 id 做指纹：曲库重扫/切账号都会变，比每次比整表便宜得多
        val key = "${all.size}:${all.firstOrNull()?.id}:${all.lastOrNull()?.id}"
        songIndexCache?.let { if (key == songIndexKey) return it }
        val map = HashMap<Long, Song>(all.size * 2)
        for (s in all) map[s.id] = s
        songIndexCache = map
        songIndexKey = key
        playlistSongsCache.clear()
        return map
    }

    fun songsOfPlaylist(playlistId: Long): List<Song> {
        val byId = songIndex()
        // 歌单曲目本身也缓存：同一歌单在一次弹层里被问 4 次，没必要解析 4 遍
        playlistSongsCache[playlistId]?.let { return it }
        // 6.9.6：先把 knownSongs 建成 map。原来是循环里逐首 knownSong(id)，
        // 那是 O(n) 线性扫描 —— 1000 个 id × 1000 首歌 = 100 万次比较，
        // 大歌单打开歌单页会肉眼可见地卡一下。
        val known = PlayHistory.knownSongs().distinctBy { it.id }.associateBy { it.id }
        val out = Playlists.songs(playlistId).mapNotNull { id ->
            // 第三条兜底 —— 歌单自己的曲目快照。
            // knownSongs() 上限 1000 首且所有歌单共用，导入一个千首歌单会把它挤爆，
            // 挤掉的歌在这里会变成"静默消失"。快照按歌单量存，导入多少留多少。
            byId[id] ?: known[id] ?: Playlists.songById(id)
        }
        if (playlistSongsCache.size > 32) playlistSongsCache.clear()
        playlistSongsCache[playlistId] = out
        return out
    }

    /** 歌单内容改变后调用，下一次读取重新取 */
    fun invalidatePlaylistCache() {
        playlistSongsCache.clear()
    }

    /** 从播放队列移除一首（播放列表弹层用）。移除的正是当前曲目时跳到下一首。 */
    fun removeFromQueue(index: Int) {
        val cur = _state.value.queue
        if (index !in cur.indices) return
        val list = cur.toMutableList()
        list.removeAt(index)
        val idx = _state.value.currentIndex
        if (list.isEmpty()) {
            _state.value = _state.value.copy(queue = emptyList(), currentIndex = -1)
            controller?.clearMediaItems()
            return
        }
        if (index == idx) {
            val next = idx.coerceAtMost(list.size - 1)
            _state.value = _state.value.copy(queue = list, currentIndex = next)
            playAt(next)
        } else {
            val newIdx = if (index < idx) idx - 1 else idx
            _state.value = _state.value.copy(queue = list, currentIndex = newIdx)
            rebuildPlayOrder()
        }
    }

    /** 清空播放队列 */
    fun clearQueue() {
        val snapshot = _state.value.queue
        val snapshotIndex = _state.value.currentIndex
        // 用户主动清空后，不再自动用图书馆模式把队列填回来；
        // 正在跑的预筛会话也要停掉，否则它会把新筛出的歌继续追加回空队列
        librarySessionJob?.cancel()
        librarySessionId++
        libraryAutoPrepare = false
        _state.value = _state.value.copy(libraryFiltering = false)
        if (snapshot.isNotEmpty()) {
            // 可反悔：队列清空是不可逆操作，给 4 秒窗口把队列原样塞回去
            requestUndo("已清空播放列表") { restoreQueue(snapshot, snapshotIndex) }
        }
        _state.value = _state.value.copy(queue = emptyList(), currentIndex = -1)
        controller?.clearMediaItems()
    }

    /** 撤销「清空队列」：把快照塞回队列并恢复到原来的播放位置（不自动播放） */
    fun restoreQueue(songs: List<Song>, index: Int) {
        if (songs.isEmpty()) return
        val list = if (_state.value.libraryMode) {
            // 图书馆模式：只恢复已经确认过的纯音乐
            songs.filter { it.id in libraryCheckedIds }
        } else songs
        if (list.isEmpty()) return
        _state.value = _state.value.copy(queue = list, currentIndex = index.coerceIn(0, list.lastIndex))
        // R13：撤销回来的也是用户要的那份队列，同样打上会话标记
        pinQueueSession()
        // 只恢复 UI 队列并落盘，不打扰正在播放的声音：
        // 用户按「撤销」是想把列表找回来，不是想让音乐突然响起来。
        persistPlayback(throttle = false)
    }

    /** 批量从播放队列移除多首（按下标），保留当前曲目 */
    fun removeSongsFromQueue(indices: List<Int>) {
        if (indices.isEmpty()) return
        val cur = _state.value.queue
        val idxSet = indices.toSet()
        var curIdx = _state.value.currentIndex
        val list = cur.filterIndexed { i, _ -> i !in idxSet }.toMutableList()
        val removedBefore = indices.count { it < curIdx }
        val removedCurrent = curIdx in idxSet
        if (list.isEmpty()) {
            _state.value = _state.value.copy(queue = emptyList(), currentIndex = -1)
            controller?.clearMediaItems()
            return
        }
        if (removedCurrent) {
            curIdx = curIdx.coerceAtMost(list.size - 1)
            _state.value = _state.value.copy(queue = list, currentIndex = curIdx)
            playAt(curIdx)
        } else {
            curIdx -= removedBefore
            _state.value = _state.value.copy(queue = list, currentIndex = curIdx)
            rebuildPlayOrder()
        }
    }

    /** 批量加入「我喜欢」 */
    fun addSongsToLiked(songIds: List<Long>) {
        val index = buildLocalSongIndex()
        songIds.forEach { id ->
            if (!PlayHistory.isLiked(id)) {
                val song = index[id]
                PlayHistory.toggleLike(id)
                song?.let {
                    PlayHistory.saveLikedSong(it)
                    PlayHistory.saveKnownSong(it)
                    // 实时上云：批量收藏也不再依赖「立即同步」
                    pushLikeRealtime(it, true, id)
                }
            }
        }
        _state.value = _state.value.copy(
            likedSongIds = PlayHistory.getLikes(),
            likedSongs = PlayHistory.likedSongs()
        )
        // 列表内容变了：穿透会话保护重算，见 applyFilters 的 listContentChanged
        if (_state.value.onlyLiked) applyFilters(resetToFirst = false, listContentChanged = true)
    }

    /** 批量从「我喜欢」移除 */
    fun removeSongsFromLiked(songIds: List<Long>) {
        val index = buildLocalSongIndex()
        songIds.forEach { id ->
            if (PlayHistory.isLiked(id)) {
                val song = index[id]
                PlayHistory.toggleLike(id)
                PlayHistory.removeLikedSong(id)
                pushLikeRealtime(song, false, id)
            }
        }
        _state.value = _state.value.copy(
            likedSongIds = PlayHistory.getLikes(),
            likedSongs = PlayHistory.likedSongs()
        )
        if (_state.value.onlyLiked) applyFilters(resetToFirst = false, listContentChanged = true)
    }

    /** 批量加入歌单：只有 id 时先尽量还原出 Song 对象，再走对象化批量入库 */
    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        // 6.9.5：不能逐首 addToPlaylist —— 它只查固定的几个 state 列表，
        // 查不到就只存 id 不存元数据，重开歌单时被 mapNotNull 丢掉。
        // 先把 id 全量还原成 Song（本地曲库索引 → known → 歌单快照 → 各 state 列表），
        // 还原不出元数据的 id 才退回逐首 addSong。
        val known = PlayHistory.knownSongs().distinctBy { it.id }.associateBy { it.id }
        val resolved = songIds.mapNotNull { id ->
            songIndex()[id]
                ?: known[id]
                ?: Playlists.songById(id)
                ?: _state.value.queue.firstOrNull { it.id == id }
                ?: _state.value.searchResults.firstOrNull { it.id == id }
                ?: _state.value.neteaseListSongs.firstOrNull { it.id == id }
        }
        if (resolved.isNotEmpty()) addSongsToMyPlaylist(playlistId, resolved)
        val missing = songIds.filter { id -> resolved.none { it.id == id } }
        missing.forEach { id -> addToPlaylist(playlistId, id) }
    }

    /**
     * 6.6.9：**整单收藏**——把一份歌单/榜单的全部歌曲存进本地歌单。
     *
     * 用户原话：「主页那些歌单、搜出来的歌单、排行榜，点开之后都能添加到自己的歌单里，
     * 单纯的添加到一个歌单，下次打开歌单里面都有。」
     *
     * 和 [addSongsToPlaylist] 的区别：那条链路只认"已经在内存列表里的 id"，
     * 而榜单/歌单里大量歌根本没进过本地曲库，按 id 查不到就会静默丢失
     *（历史上"加了很多首只显示几首"就是这个坑）。这里直接拿 Song 对象：
     * 先把元数据落进 known_songs，再按顺序写歌单，一首都不会掉。
     */
    fun addSongsToMyPlaylist(playlistId: Long, songs: List<Song>) {
        if (songs.isEmpty()) return
        runCatching { Haptics.tap() }
        // 6.9.5：**元数据必须落到歌单自己的快照里（rememberSongs，5000 上限）**，
        // 不能只依赖 known_songs —— 那是全 App 共用的 1000 条 LRU：
        // 云同步时几百首云端歌逐条 saveKnownSong，刚批量加进去的歌会被从尾部
        // 挤掉，表现就是用户报的「歌单显示几十首、点进去只剩一两首」
        // （id 还在歌单里所以数量对，元数据没了 mapNotNull 就静默丢弃）。
        // 快照按歌单量存、与 knownSongs 解耦，批量导入后永久可解析。
        runCatching { Playlists.rememberSongs(songs) }
        runCatching { PlayHistory.saveKnownSongs(songs) }
        // 一次批量写入：几百首也只改写一次 prefs（旧版逐首 addSong 是几百次重写）
        runCatching { Playlists.addSongs(playlistId, songs.map { it.id }) }
        invalidatePlaylistCache()
        refreshPlaylists()
        if (_state.value.activePlaylistId == playlistId) {
            applyFilters(resetToFirst = false, listContentChanged = true)
        }
        // 云同步：一次批量对齐（逐首打接口在 200 首的榜单上会把队列堵死）
        if (AccountStore.isLoggedIn) launchSafely { syncPlaylistsQuietly() }
        notice("已添加 ${songs.size} 首到歌单")
    }

    /** 新建歌单并把整份列表存进去（"整单收藏"里的新建分支） */
    fun createPlaylistWithSongs(name: String, songs: List<Song>): Long {
        val id = createPlaylist(name)
        if (id > 0 && songs.isNotEmpty()) addSongsToMyPlaylist(id, songs)
        return id
    }

    fun toggleShuffle() {
        val next = !_state.value.shuffle
        // R14：打上"用户自己选过播放模式"的标记。之后 playList() 就不再把他
        // 明确开过的随机按回顺序（见 ensureDefaultSequential 那段注释）。
        prefs.edit().putBoolean(KEY_SHUFFLE, next).putBoolean(KEY_PLAYMODE_CHOSEN, true).apply()
        _state.value = _state.value.copy(shuffle = next)
        // 随机只改写【播放器时间线】的顺序，state.queue（主页列表）永远保持
        // 固定的 筛选→排序 结果。旧实现直接把 state.queue 洗掉，主页列表跟着
        // 变成乱序——这不是随机播放，这是把用户的排序砸了。
        rebuildPlayOrder()
        applyPlaybackModes()
    }

    /**
     * 按当前 shuffle 状态重建播放器时间线，主页队列（state.queue）不动。
     *
     * 随机序 = 偏好加权洗牌（Recommender.weightedPermutation， exploration 控制
     * 趋近均匀的程度）：当前播放的歌钉在第 0 位续播，其余按权重随机排列。
     * 正在播放时走 syncQueueAroundCurrent 只替换前后条目——声音不中断
     * （直接 setMediaItems 会把正在解码的当前曲也重建，听到"咔"一下）。
     */
    private fun rebuildPlayOrder() {
        val c = controller ?: return
        val base = _state.value.queue
        if (base.isEmpty()) return
        val cur = _state.value.currentSong
        val order = playOrder(base, if (_state.value.shuffle) cur?.id else null)
        val cIdx = order.indexOfFirst { it.id == cur?.id }.coerceAtLeast(0)
        val baseIdx = base.indexOfFirst { it.id == cur?.id }.coerceAtLeast(0)
        val playingId = c.currentMediaItem?.mediaId?.toLongOrNull()
        if (playingId != null && order.getOrNull(cIdx)?.id == playingId && c.isPlaying) {
            // R13：这里必须传 base（主页那份固定序）而不是 order（已洗过的时间线序）。
            // syncQueueAroundCurrent 内部会再 playOrder(queue, queue[index].id) 一次，
            // 传 order 就等于洗两次：洗完的序列既不是 UI 队列也不是上一次的时间线，
            // 随机模式下"下一首"跳到谁全看第二次洗牌结果，用户看到的就是顺序乱了。
            // 顺带补一次同源校验（与 pushQueue 同一判据），时间线没修好就整条重写。
            syncQueueAroundCurrent(c, base, baseIdx)
            if (!timelineMatchesQueue(c)) writeTimelineFromQueue(c, baseIdx, c.currentPosition)
        } else {
            c.setMediaItems(order.toUniqueMediaItems(), cIdx, c.currentPosition.coerceAtLeast(0L))
            c.prepare()
        }
    }

    /** 播放器时间线顺序：shuffle 关闭时等于 base；开启时当前曲置顶 + 其余加权洗牌 */
    private fun playOrder(base: List<Song>, pinId: Long?): List<Song> {
        if (pinId == null) return base
        val curIdx = base.indexOfFirst { it.id == pinId }
        if (curIdx < 0) return base
        val current = base[curIdx]
        val rest = base.filterIndexed { i, _ -> i != curIdx }
        val perm = Recommender.weightedPermutation(rest, exploration = _state.value.exploration)
        return listOf(current) + disperseInitials(perm.map { rest[it] }.toMutableList())
    }
    /**
     * 相邻同名打散：纯随机里"同名/同首字母相连"出现的概率非常高（50 首 10 组的
     * 模拟里约 98% 的洗牌至少出现一对），算法没错，但听感就是"随机坏了"。
     * 贪心单遍：发现与前一首标题首字符（忽略大小写）相同就向后找第一首不同的换过来。
     * 只影响相邻对，整体仍是加权随机的分布。
     */
    private fun disperseInitials(order: MutableList<Song>): List<Song> {
        // 预提取首字符，比较便宜且避免反复 trim
        val ini = CharArray(order.size) { k ->
            order[k].title.trim().firstOrNull()?.uppercaseChar() ?: ' '
        }
        for (i in 1 until order.size) {
            if (ini[i] != ini[i - 1]) continue
            // 找一个 j：交换后 i 位（新歌）与左右邻居、j 位（旧 i 歌）与左右邻居都不撞名。
            // k==i+1 时两边邻居就是被交换的对家，其互异性由第一条 ini[k]!=ini[i-1] 保证。
            val j = (i + 1 until order.size).firstOrNull { k ->
                ini[k] != ini[i - 1] &&
                    (k == i + 1 || i + 1 >= order.size || ini[k] != ini[i + 1]) &&
                    ini[i] != ini[k] &&
                    (k - 1 == i || ini[i] != ini[k - 1]) &&
                    (k + 1 >= order.size || ini[i] != ini[k + 1])
            }
            if (j == null) break // 后面全同名，无解，保留现状
            val t = order[i]; order[i] = order[j]; order[j] = t
            val c = ini[i]; ini[i] = ini[j]; ini[j] = c
        }
        return order
    }

    fun cycleRepeatMode() {
        val next = when (_state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        prefs.edit().putString(KEY_REPEAT, next.name).apply()
        _state.value = _state.value.copy(repeatMode = next)
        applyPlaybackModes()
    }

    /**
     * 播放模式四档循环（播放页副控制行的合并按钮）：
     * 关 → 随机 → 列表循环 → 单曲循环 → 关。
     *
     * 随机与循环底层仍是两个正交状态（shuffle / repeatMode，各自持久化到
     * 原有 key，旧版本数据无缝兼容）；这里只是按"一枚按钮"的心智模型切换。
     * 旧数据里可能存在「随机+循环」同时开的组合（两枚按钮时代的合法状态），
     * 随机视为主导，第一次点击直接落回「列表循环」档归一。
     */
    fun cyclePlayMode() {
        val s = _state.value
        val (nextShuffle, nextRepeat) = when {
            s.shuffle -> false to RepeatMode.ALL                       // 随机（含旧组合）→ 列表循环
            s.repeatMode == RepeatMode.OFF -> true to RepeatMode.OFF   // 关 → 随机
            s.repeatMode == RepeatMode.ALL -> false to RepeatMode.ONE  // 列表循环 → 单曲循环
            else -> false to RepeatMode.OFF                            // 单曲循环 → 关
        }
        prefs.edit()
            .putBoolean(KEY_SHUFFLE, nextShuffle)
            .putString(KEY_REPEAT, nextRepeat.name)
            .putBoolean(KEY_PLAYMODE_CHOSEN, true)
            .apply()
        // 点击播放模式按钮时，弹一条轻提示说明当前是哪种模式，
        // 免得用户只看到一个抽象图标、不知道切换到了什么。
        val modeLabel = when {
            nextShuffle -> "随机播放"
            nextRepeat == RepeatMode.ONE -> "单曲循环"
            nextRepeat == RepeatMode.ALL -> "列表循环"
            else -> "顺序播放"
        }
        _state.value = s.copy(
            shuffle = nextShuffle,
            repeatMode = nextRepeat,
            loginPrompt = modeLabel,
            loginPromptTick = s.loginPromptTick + 1
        )
        if (nextShuffle != s.shuffle) {
            // 随机开/关只重排播放器时间线；主页列表恒定不动
            rebuildPlayOrder()
        }
        applyPlaybackModes()
    }

    // ==================== 库 / 筛选 ====================

    fun reloadLibrary() {
        if (_state.value.refreshing) return
        launchSafely {
            _state.value = _state.value.copy(refreshing = true)
            // 刷新 = 重新拉取在线榜单建库。
            // rescanAndReload 内部走 IO；来源聚合也一并放到 IO，避免大库在主线程算。
            val online = MusicRepository.rescanAndReload(getApplication())
            // 接口整体失败时返回空：保留当前曲库，别把首页刷成一片空白。
            if (online.isEmpty()) {
                _state.value = _state.value.copy(refreshing = false, loading = false)
                lastFailedAction = FailedAction.LIBRARY
                reportError("曲库加载失败，检查一下网络")
                return@launchSafely
            }
            // 刷新只拉在线曲库，本地 SAF 曲目必须并回来，否则刷一次本地歌就全没了
            val localNow = withContext(Dispatchers.IO) { LocalLibrary.cached(getApplication()) }
            val songs = mergeLocal(online, localNow)
            val folders = withContext(Dispatchers.Default) { MusicRepository.buildFolders(songs) }
            // 换一个混洗种子：默认「按标题」排序实际按这个种子打乱，刷新后顺序就变了，
            // 底部的歌能跑到上面，不会永远是同一批置顶。
            val seed = System.nanoTime()
            _state.value = _state.value.copy(
                allSongs = songs,
                folders = folders,
                librarySeed = seed,
                loading = false,
                refreshing = false
            )
            // 正在播放的歌不打断：resetToFirst 仅在没有当前曲目时才从头开始；
            // 只要当前曲目还在新库里，applyFilters 会自动续播原位置。
            applyFilters(resetToFirst = _state.value.currentSong == null)
            // 队列重建后刷新推荐
            updateRecommendations()
            // 报告里的歌名来自 allSongs，库换了要跟着重算
            refreshReportIfNeeded()
            // 收编孤儿数据：JSON 解析 + 归并较重，放 IO 线程，别卡刷新动画收尾那一帧
            withContext(Dispatchers.IO) { DataTransfer.attachOrphans(getApplication(), songs) }
        }
    }

    /**
     * 首页下拉刷新（按当前视图区分刷新范围）：
     *
     * - 正在看自定义歌单 / 「我喜欢」：这些是用户自己的列表，下拉不应把内容打乱——
     *   只刷新上层「为你推荐」，下层列表保持不动。
     * - 其他情况（全部音乐 / 文件夹视图）：整库重新拉取，上层推荐与下层列表一起刷新，
     *   避免"首页永远是那 300 多首、看不出刷新"。
     */
    /**
     * 首页下拉刷新（按当前视图区分刷新范围）：
     *
     * - 正在看自定义歌单 / 「我喜欢」：这些是用户自己的列表，下拉不应把内容打乱——
     *   只刷新上层「为你推荐」，下层列表保持不动。
     * - 其他情况（全部音乐 / 文件夹视图）：整库重新拉取，上层推荐与下层列表一起刷新，
     *   避免"首页永远是那 300 多首、看不出刷新"。
     *
     * 6.3.9（用户要求「尽量能刷新它就刷新，不能刷的话就算了」）：
     * 这里**总是**顺手把首页那几块网易云栏目也重拉一遍——并且带上 force。
     * 以前 loadHomeFeed 有「今天已经拉过就直接返回」的短路，导致同一天里下拉几次，
     * 只有第一次真的打了接口，后面全是假装刷新。现在下拉一律 force，
     * 但 force 也不是无脑打：距离上次成功刷新不到 [HOME_FEED_MIN_REFRESH_MS] 时跳过，
     * 免得用户连拉五下就打五轮接口。失败只保留旧内容，不弹错误——"刷不动就算了"。
     */
    fun refreshHome() {
        val s = _state.value
        if (s.activePlaylistId != null || s.onlyLiked) refreshRecommendations() else reloadLibrary()
        loadHomeFeed(force = true)
    }

    /**
     * 6.3.2：刷新首页「猜你喜欢」（免登录）。
     *
     * 纯本地计算：拿全库 → [Recommender.recommend] 按播放/跳过/收藏打分 → 取 12 首。
     * 不发网络请求，所以未登录、断网都能出内容。
     * 只在 [allSongs] 变化或用户下拉刷新时重算，不在每帧算。
     */
    fun refreshHomeGuess() {
        val all = _state.value.allSongs
        if (all.isEmpty()) {
            if (_state.value.homeGuessSongs.isNotEmpty()) {
                _state.value = _state.value.copy(homeGuessSongs = emptyList())
            }
            return
        }
        val list = runCatching { Recommender.recommend(all, limit = 12, exploration = 0.35f) }
            .getOrDefault(emptyList())
        _state.value = _state.value.copy(homeGuessSongs = list)
    }

    // ==================== 首页每日栏目（免登录，6.3.8） ====================
    //
    // 设计要点：
    //   1. 全部走免登录接口（用户要求"需要登录的就不要加上去了"）；
    //   2. 内容按日期轮换（[HomeFeed.dailyPick]），做到"每天不一样"；
    //   3. 各栏目**并行**加载，谁先回来谁先上屏，不让一个慢接口拖住整屏；
    //   4. 任一栏目失败只影响它自己，其余照常显示。

    /** 正在加载，避免首页反复触发（一次进首页打 6 个接口就够多了）。 */
    @Volatile private var homeFeedLoading = false

    /** 上次成功/尝试刷新首页栏目的时间（force 刷新节流用，见 [loadHomeFeed]） */
    @Volatile private var lastHomeFeedRefreshAt = 0L

    /**
     * 加载首页所有免登录栏目。重复调用会被 [homeFeedLoading] 挡掉。
     *
     * @param force true 时忽略"今天已经加载过"的判断（下拉刷新用）
     */
    fun loadHomeFeed(force: Boolean = false) {
        if (homeFeedLoading) return
        val today = HomeFeed.todayLabel()
        val s = _state.value
        // 今天已经加载过、且确实有内容 —— 直接返回，别每次切回首页都重打接口
        if (!force && s.homeFeedDay == today && s.homeNewSongs.isNotEmpty()) {
            // 6.7：热门歌手单独补刷。以前"今天拉过"直接 return，把热门歌手也焊死成
            // 一天一份（用户："热门歌手每次都不更新，有点假的感觉"）。
            // 列表数据本身一天内也在变，超过 10 分钟就单独重拉一次，其余栏目不动。
            if (hotArtistsCacheAgeMs() > 10 * 60 * 1000L) refreshHotArtists()
            return
        }
        // 6.3.9：force 也做最低间隔节流。用户连拉几下不应该连打几轮接口，
        // 但也不能"一整天只刷一次"。40 秒足够覆盖"看两眼觉得不对再拉一次"的手感。
        if (force && System.currentTimeMillis() - lastHomeFeedRefreshAt < HOME_FEED_MIN_REFRESH_MS) return
        lastHomeFeedRefreshAt = System.currentTimeMillis()
        homeFeedLoading = true
        launchSafely {
            try {
                // 用 coroutineScope 包住：五路并行都结束后才落 finally，
                // 否则外层会先返回、把 homeFeedLoading 提前复位成 false，
                // 用户在加载中途再进一次首页就会重复打五个接口。
                kotlinx.coroutines.coroutineScope {
                // ① 每日新歌：先拿 30 首池子，再按日期挑 8 首 —— 每天不一样
                //
                // 6.3.9：用户问"只有这四首吗"。原来池子取 30、挑 8，UI 又只画 5 行，
                // 三层叠加下来一屏只看到 5 首。现在池子取 60、挑 12，UI 画满 10 行。
                launch {
                    val pool = withContext(Dispatchers.IO) {
                        runCatching { NeteaseApi.newSongRecommend(60) }.getOrDefault(emptyList())
                    }
                    if (pool.isNotEmpty()) {
                        val picked = HomeFeed.dailyPick(pool, 12, salt = 1)
                        val songs = MusicRepository.fromNetease("每日新歌", picked)
                        _state.value = _state.value.copy(homeNewSongs = songs)
                    }
                }

                // ② 风格栏目：并行，每取到一个就先上屏
                HomeFeed.CHANNELS.forEachIndexed { idx, ch ->
                    launch {
                        val raw = withContext(Dispatchers.IO) {
                            runCatching { NeteaseApi.playlistPreview(ch.id, limit = 30) }
                                .getOrDefault(emptyList())
                        }
                        if (raw.isNotEmpty()) {
                            val picked = HomeFeed.dailyPick(raw, 8, salt = 10 + idx)
                            val songs = MusicRepository.fromNetease(ch.name, picked)
                            _state.value = _state.value.copy(
                                homeChannels = _state.value.homeChannels + (ch.id to songs)
                            )
                        }
                    }
                }

                // ③ 粤语：没有独立榜单，用搜索找歌单再取曲目
                launch {
                    val songs = withContext(Dispatchers.IO) {
                        runCatching {
                            val pls = NeteaseApi.searchPlaylists(HomeFeed.CANTONESE_QUERY, limit = 6)
                            if (pls.isEmpty()) return@runCatching emptyList<Song>()
                            // 取第一个有曲目的歌单，再按日期抽 8 首
                            for (pl in pls) {
                                val tracks = NeteaseApi.playlistPreview(pl.id, limit = 40)
                                if (tracks.size >= 5) {
                                    return@runCatching MusicRepository.fromNetease(
                                        "粤语", HomeFeed.dailyPick(tracks, 8, salt = 7)
                                    )
                                }
                            }
                            emptyList<Song>()
                        }.getOrDefault(emptyList())
                    }
                    if (songs.isNotEmpty()) {
                        _state.value = _state.value.copy(homeCantonese = songs)
                    }
                }

                // ④ 热门歌手：6.7 起抽成独立函数 refreshHotArtists()，
                // 见下方——它要在"今天已拉过"的短路路径里也能单独补刷。
                launch { refreshHotArtists() }

                // ⑤ 新碟上架
                launch {
                    val list = withContext(Dispatchers.IO) {
                        runCatching { NeteaseApi.newAlbums("ALL", 12) }.getOrDefault(emptyList())
                    }
                    if (list.isNotEmpty()) {
                        _state.value = _state.value.copy(
                            homeNewAlbums = HomeFeed.dailyPick(list, 10, salt = 5)
                        )
                    }
                }

                _state.value = _state.value.copy(homeFeedDay = today)
                }
            } finally {
                homeFeedLoading = false
            }
        }
    }

    /** 点某个风格栏目的卡片 → 以该栏目列表开播。 */
    fun playHomeChannel(channelId: String, index: Int = 0) {
        val list = _state.value.homeChannels[channelId] ?: return
        if (list.isEmpty()) return
        val name = HomeFeed.CHANNELS.firstOrNull { it.id == channelId }?.name ?: "推荐"
        playList(list, index.coerceIn(0, list.lastIndex), name)
    }

    /** 点「每日新歌」里的某一首 → 从那一首开始播整组。 */
    fun playHomeNewSong(song: Song) {
        val list = _state.value.homeNewSongs
        val idx = list.indexOfFirst { it.songId == song.songId }.coerceAtLeast(0)
        playList(list.ifEmpty { listOf(song) }, idx, "每日新歌")
    }

    /**
     * 6.3.2：首页「猜你喜欢」入口卡被点 → 以这份列表为队列开始播。
     *
     * 不新界面、不弹层：用户点卡片就是要听歌，直接开播比"再点一次播放"少一步。
     */
    fun playHomeGuess() {
        val list = _state.value.homeGuessSongs
        if (list.isEmpty()) {
            refreshHomeGuess()
            val again = _state.value.homeGuessSongs
            if (again.isEmpty()) return
            playList(again, 0, "猜你喜欢")
            return
        }
        playList(list, 0, "猜你喜欢")
    }

    /** 仅重新生成「最近播放」（不重扫媒体、不改队列）：随机抽 3 首 */
    fun refreshRecommendations() {
        if (_state.value.refreshing) return
        _state.value = _state.value.copy(refreshing = true)
        val pool = recommendationPool()
        // 8 首：搜索页「猜你喜欢」是两列排布，3 首只够一行半，撑不满那一屏
        val recs = pool.shuffled().take(8)
        // 延迟 500ms 后重置（保证旋转动画可见）
        launchSafely {
            kotlinx.coroutines.delay(500)
            _state.value = _state.value.copy(
                recommendations = recs,
                recommendationsVersion = _state.value.recommendationsVersion + 1,
                refreshing = false
            )
        }
    }

    /** 搜索页「猜你喜欢」每批几首：两列 × 3 行 */
    private fun guessSize() = 6

    /** 进搜索页时铺一批「猜你喜欢」；已经有内容就不动（不打断用户正在看的那一批） */
    fun ensureSearchGuess() {
        if (_state.value.searchGuess.isNotEmpty()) return
        shuffleSearchGuess()
    }

    /** 重新抽一批「猜你喜欢」。播放行为不参与，所以点歌不会把这一批越点越长。 */
    fun shuffleSearchGuess() {
        val pool = recommendationPool()
        if (pool.isEmpty()) return
        val size = guessSize()
        if (pool.size < size) { _state.value = _state.value.copy(searchGuess = pool); return }
        var next = pool.shuffled().take(size)
        val before = _state.value.searchGuess.map { it.id }
        // 摇出和上一批一模一样时重摇（最多 6 次），否则「换一批」按下去看着像没反应
        var guard = 0
        while (next.map { it.id } == before && guard++ < 6) next = pool.shuffled().take(size)
        _state.value = _state.value.copy(searchGuess = next)
    }

    /**
     * 6.9：切换搜索源（网易云 / QQ音乐）。有词在搜就立刻重搜一遍；
     * QQ 源只出单曲，其它分类结果一律清掉（切回网易云时会按需重新搜）。
     */
    fun setSearchSource(source: String) {
        if (_state.value.searchSource == source) return
        searchJob?.cancel()
        _state.value = _state.value.copy(
            searchSource = source,
            searchTab = 0,
            searchResults = emptyList(),
            searchArtists = emptyList(),
            searchAlbums = emptyList(),
            searchPlaylistsFound = emptyList(),
            searchHasMore = false,
            searchLoadingMore = false
        )
        searchedTabs.clear(); searchPreloading.clear()
        searchPageOffset = 0
        searchPlaylistOffset = 0
        qqSearchPage = 1
        val q = _state.value.searchQuery.trim()
        if (q.isNotEmpty()) updateSearch(q)
    }

    fun updateSearch(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        searchJob?.cancel()
        suggestJob?.cancel()
        val seq = ++searchSeq
        if (query.isBlank()) {
            searchPageOffset = 0
            searchPlaylistOffset = 0
            searchedQuery = ""
            searchedTabs.clear(); searchPreloading.clear()
            lastSearchTelemetry = ""
            _state.value = _state.value.copy(
                searchResults = emptyList(), searching = false,
                searchArtists = emptyList(), searchAlbums = emptyList(),
                searchPlaylistsFound = emptyList(),
                searchHasMore = false, searchLoadingMore = false,
                searchSuggestions = emptyList()
            )
            return
        }
        val q = query.trim()
        if (q.isEmpty()) return
        // 换关键词就作废"这一类已经搜过"的记录：可左右滑的 tab 靠它决定要不要重发请求，
        // 不清的话新词会直接显示上一个词的旧结果。
        if (q != searchedQuery) {
            searchedQuery = q
            searchedTabs.clear(); searchPreloading.clear()
            searchPlaylistOffset = 0
            searchPageOffset = 0
            // R13 修「搜『毛不易』单曲那一格是空的、东西都跑到歌手里」：
            // searchTab 常驻在 state 里，上一轮用户停在"歌手"(1) 没切回来，这一轮搜新词时
            // 下面 `val tab = _state.value.searchTab` 读到的还是 1，于是整条链路只发了
            // **一个**分类的请求（歌手），"单曲"那一格从请求都没发过 → 永远空列表。
            // 用户看到的就是"单曲里没有、内容出现在歌手分类里"。
            // 换词一律把分类复位到 0（单曲）并先搜单曲：这才是用户按下搜索时的默认预期。
            // 与 setSearchTab 的配合：那边只在"这一类没搜过"时才调 updateSearch，
            // 那一刻 q == searchedQuery，进不了这个分支，所以左右滑动切分类不会被打回
            // 单曲，也不会形成 updateSearch → setSearchTab → updateSearch 的递归。
            // 顺带把上一词的结果整体清空：以前这里只置 searching=true，旧结果会继续挂在
            // 新词下面闪几百毫秒（防抖 300ms + 一次网络往返），看起来像"搜错词"。
            _state.value = _state.value.copy(
                searchTab = 0,
                searchResults = emptyList(),
                searchArtists = emptyList(),
                searchAlbums = emptyList(),
                searchPlaylistsFound = emptyList(),
                searchHasMore = false,
                searchLoadingMore = false
            )
        }
        // ---- 输入联想（5.1）----
        // 单独一条链路：220ms 防抖，比结果搜索更灵敏；失败或返回空就什么都不做，
        // 搜索页继续显示本地历史 + 热搜的前缀补全。
        suggestJob = launchSafely {
            delay(220)
            val list = withContext(Dispatchers.IO) {
                runCatching { OnlineMusicApi.suggestKeywords(q) }.getOrDefault(emptyList())
            }
            if (_state.value.searchQuery.trim() != q) return@launchSafely
            _state.value = _state.value.copy(searchSuggestions = list)
        }
        _state.value = _state.value.copy(searching = true, searchLoadingMore = false)
        val tab = _state.value.searchTab
        searchJob = launchSafely {
            // 防抖：连续打字只发最后一次；同时给用户一个明确的「搜索中」状态，
            // 不会出现旧结果先闪一下、过一会儿又消失的观感。
            delay(300)
            // 真正执行搜索时上报一条行为事件（总后台「记录 / 热搜词」用）。
            // 之前只在登录后写 search_history，未登录用户的搜索后台完全看不到。
            if (q != lastSearchTelemetry) {
                lastSearchTelemetry = q
                runCatching {
                    Telemetry.report(getApplication(), "search", q.take(80))
                }
                // 登录用户同时写一份云端搜索历史，后台「用户详情 → 搜索记录」直接可查
                if (AccountStore.isLoggedIn) {
                    launchSafely {
                        runCatching { AuthApi.pushSearchKeyword(q.take(80)) }
                    }
                }
            }
            // 6.9：搜索源 = QQ 音乐 —— 只搜单曲，songId 带 "qq:" 前缀，点播直达 QQ 换链
            //（0.2~0.5 秒出声）。一页 100 首不分页（QQ 公开搜索单页就这么多，
            // loadMoreSearch 的网易云分页逻辑不掺和进来）。
            if (_state.value.searchSource == "qq") {
                qqSearchPage = 1
                val list = withContext(Dispatchers.IO) {
                    runCatching {
                        MusicRepository.fromQQ("QQ音乐", OnlineMusicApi.qqSearchSongs(q, 60, 1))
                    }.getOrDefault(emptyList())
                }
                if (seq != searchSeq || _state.value.searchQuery.trim() != q ||
                    _state.value.searchSource != "qq"
                ) return@launchSafely
                _state.value = _state.value.copy(
                    searchResults = list,
                    searching = false,
                    // 6.9.2：满一页（60 首）才认为可能还有下一页，滚到底自动翻页
                    searchHasMore = list.size >= 60,
                    searchLoadingMore = false
                )
                // 同网易云链路：结果一出就预解析前 2 首，点下去秒响
                runCatching {
                    val ids = list.take(2).mapNotNull { it.songId.takeIf { s -> s.isNotBlank() } }
                    if (ids.isNotEmpty()) com.jun.musiccloud.playback.AudioProxyServer.prefetch(ids)
                }
                return@launchSafely
            }
            // 按 tab 搜索不同类型
            when (tab) {
                1 -> { // 歌手
                    // 必须切到 IO：HttpURLConnection 在主线程会直接抛 NetworkOnMainThreadException，
                    // 被 runCatching 吞掉后就表现为「歌手永远 0 个」。
                    // 6.9.3：QQ 源走 QQ 自己的歌手搜索（t=9）；id 留空，
                    // 点击时按名字回网易云打开（openArtistByName），体验与网易云一致。
                    val list = withContext(Dispatchers.IO) {
                        runCatching {
                            if (_state.value.searchSource == "qq") {
                                OnlineMusicApi.qqSearchSingers(q, 60).map {
                                    NeteaseApi.NsArtist(id = "", name = it.name, pic = it.pic, score = it.songNum.toLong())
                                }
                            } else {
                                NeteaseApi.searchArtists(q, 50)
                            }
                        }.getOrDefault(emptyList())
                    }
                    if (seq != searchSeq || _state.value.searchQuery.trim() != q || _state.value.searchTab != tab) return@launchSafely
                    _state.value = _state.value.copy(searchArtists = list, searching = false, searchHasMore = false)
                    if (list.isNotEmpty()) searchedTabs.add(1)
                    // 6.6.9：搜歌手 tab 直接反映"大家在搜谁"，前三位记热度（热门歌手排序用）
                    bumpSearchedArtists(list.take(3).map { it.name })
                }
                2 -> { // 专辑
                    // 6.9.3：QQ 源走 QQ 专辑搜索（t=8）；id 留空，
                    // 点击时 openAlbumByName 按名字回网易云定位这张专辑。
                    val list = withContext(Dispatchers.IO) {
                        runCatching {
                            if (_state.value.searchSource == "qq") {
                                OnlineMusicApi.qqSearchAlbums(q, 60).map {
                                    NeteaseApi.NsAlbum(id = "", name = it.name, artist = it.artist, pic = it.pic)
                                }
                            } else {
                                NeteaseApi.searchAlbums(q, 50)
                            }
                        }.getOrDefault(emptyList())
                    }
                    if (seq != searchSeq || _state.value.searchQuery.trim() != q || _state.value.searchTab != tab) return@launchSafely
                    _state.value = _state.value.copy(searchAlbums = list, searching = false, searchHasMore = false)
                    if (list.isNotEmpty()) searchedTabs.add(2)
                }
                3 -> { // 歌单
                    // 6.9.3：QQ 公开搜索没有歌单类型（t=0/7/8/9 之外全为空，实测），
                    // 所以两个源的「歌单」tab 都用网易云歌单 —— 点开后照样走四层取源。
                    val list = withContext(Dispatchers.IO) {
                        runCatching {
                            NeteaseApi.searchPlaylists(q, SEARCH_PLAYLIST_PAGE, 0)
                        }.getOrDefault(emptyList())
                    }
                    if (seq != searchSeq || _state.value.searchQuery.trim() != q || _state.value.searchTab != tab) return@launchSafely
                    searchPlaylistOffset = list.size
                    // 用户反馈"歌单往左滑应该有非常多，怎么这么少"：以前 offset 写死 0、
                    // 一次拿 50 条就当全部，滚到底也不会再要。现在按页取、满页就认为还有更多。
                    _state.value = _state.value.copy(
                        searchPlaylistsFound = list, searching = false,
                        searchHasMore = list.size >= SEARCH_PLAYLIST_PAGE
                    )
                    if (list.isNotEmpty()) searchedTabs.add(3)
                }
                else -> { // 单曲：首屏 30 首，滚动到底继续加载
                    val (results, hasMore) = runCatching {
                        MusicRepository.searchOnlinePage(getApplication(), q, SEARCH_PAGE_SIZE, 0)
                    }.getOrDefault(emptyList<Song>() to false)
                    if (seq != searchSeq || _state.value.searchQuery.trim() != q || _state.value.searchTab != tab) return@launchSafely
                    searchPageOffset = results.size
                    if (results.isNotEmpty()) searchedTabs.add(0)
                    // 6.6.9：搜歌名同样能看出"在找谁"——反复出现的歌手记热度
                    bumpSearchedArtistsFromSongs(results)
                    _state.value = _state.value.copy(
                        searchResults = results,
                        searching = false,
                        searchHasMore = hasMore,
                        searchLoadingMore = false
                    )
                    // 6.7.2：搜索结果一出来就把**前 3 首**的真实地址解析好。
                    // 用户看到列表到手指点下去，中间通常有 1~3 秒，足够解析完；
                    // 点的时候就是内存命中，直接出声。这是"搜索里点歌要等很久"最划算的修法。
                    runCatching {
                        val ids = results.take(2).mapNotNull { it.songId.takeIf { s -> s.isNotBlank() } }
                        if (ids.isNotEmpty()) {
                            com.jun.musiccloud.playback.AudioProxyServer.prefetch(ids)
                        }
                    }
                }
            }
        }
    }

    /**
     * 单曲搜索加载下一页。滚动到底部时由 UI 调用。
     * 用 searchSeq 做版本校验：如果用户已经改了关键词 / 切了 tab，旧请求结果直接丢弃。
     */
    /**
     * 搜索滚到底再要一页。单曲（tab 0）和歌单（tab 3）各自有自己的 offset 与去重键；
     * 歌手 / 专辑是一次性给全的，走到这里直接返回。
     */
    fun loadMoreSearch() {
        val s = _state.value
        val tab = s.searchTab
        if (tab != 0 && tab != 3) return
        if (s.searching || s.searchLoadingMore || !s.searchHasMore) return
        val q = s.searchQuery.trim()
        if (q.isEmpty()) return
        val seq = searchSeq
        _state.value = s.copy(searchLoadingMore = true)
        // 6.9.2：QQ 搜索源的分页（p 参数翻页）。只有单曲 tab（tab 0），
        // 单页上限 60，上一页拿满了 60 才会有这一页。
        if (s.searchSource == "qq") {
            if (tab != 0) return
            launchSafely {
                val page = qqSearchPage + 1
                val more = runCatching {
                    withContext(Dispatchers.IO) {
                        MusicRepository.fromQQ("QQ音乐", OnlineMusicApi.qqSearchSongs(q, 60, page))
                    }
                }.getOrDefault(emptyList())
                if (seq != searchSeq || _state.value.searchQuery.trim() != q ||
                    _state.value.searchSource != "qq"
                ) return@launchSafely
                val existing = _state.value.searchResults
                val ids = existing.mapTo(HashSet()) { it.id }
                val add = more.filter { ids.add(it.id) }
                if (add.isNotEmpty()) qqSearchPage = page
                _state.value = _state.value.copy(
                    searchResults = existing + add,
                    searchHasMore = add.size >= 60,
                    searchLoadingMore = false
                )
            }
            return
        }
        launchSafely {
            if (tab == 0) {
                val offset = searchPageOffset
                val (more, hasMore) = runCatching {
                    MusicRepository.searchOnlinePage(getApplication(), q, SEARCH_PAGE_SIZE, offset)
                }.getOrDefault(emptyList<Song>() to false)
                if (seq != searchSeq || _state.value.searchQuery.trim() != q || _state.value.searchTab != 0) return@launchSafely
                val existing = _state.value.searchResults
                val ids = existing.mapTo(HashSet()) { it.id }
                val add = more.filter { ids.add(it.id) }
                if (more.isNotEmpty()) searchPageOffset = offset + more.size
                _state.value = _state.value.copy(
                    searchResults = existing + add,
                    searchHasMore = hasMore && add.isNotEmpty(),
                    searchLoadingMore = false
                )
            } else {
                val offset = searchPlaylistOffset
                val more = runCatching {
                    withContext(Dispatchers.IO) {
                        NeteaseApi.searchPlaylists(q, SEARCH_PLAYLIST_PAGE, offset)
                    }
                }.getOrDefault(emptyList())
                if (seq != searchSeq || _state.value.searchQuery.trim() != q || _state.value.searchTab != 3) return@launchSafely
                val existing = _state.value.searchPlaylistsFound
                val ids = existing.mapTo(HashSet()) { it.id }
                val add = more.filter { ids.add(it.id) }
                // 一整页都没有新增 = 到底了，别再发下一次
                if (more.isNotEmpty()) searchPlaylistOffset = offset + more.size
                _state.value = _state.value.copy(
                    searchPlaylistsFound = existing + add,
                    searchHasMore = more.size >= SEARCH_PLAYLIST_PAGE && add.isNotEmpty(),
                    searchLoadingMore = false
                )
            }
        }
    }

    /** 切换搜索分类 tab，自动重新搜索 */
    fun setSearchTab(tab: Int) {
        if (_state.value.searchTab == tab) return
        _state.value = _state.value.copy(searchTab = tab)
        val q = _state.value.searchQuery.trim()
        if (q.isEmpty()) return
        // 已经搜过这一类就只看缓存：tab 改成能左右滑之后，一次横扫会路过三个 tab，
        // 每个都重发一次请求的话，"搜索结果切着滑"反而变成"切一下就卡"。
        if (q == searchedQuery && tab in searchedTabs) return
        updateSearch(q)
    }

    /**
     * 6.3.2：**只预加载某一类，不切 tab**。
     *
     * 场景：搜索页可以左右滑切「单曲 / 歌手 / 专辑 / 歌单」，HorizontalPager 的
     * beyondViewportPageCount=1 会把邻页提前组合。以前邻页因为"还没轮到它搜"是空的，
     * 于是亮出「歌手还没搜过，点这里切过去」——用户原话是"不要出现那种界面，直接加载就行了"。
     *
     * 和 [setSearchTab] 的区别就在这：那个会改 `searchTab`，从邻页调用会把用户
     * 当前停留的分类顶走（滑一下自己跳走了）；这里只补齐数据，不动用户的停留位置。
     *
     * 去重条件三连，缺一个都会打满请求：
     *  ① 关键词必须与当前输入一致（打完字还没来得及搜索时不预加载）；
     *  ② 这一类不能已经在搜（`searchPreloading` 记录在途分类）；
     *  ③ 已经搜过的（`searchedTabs`）不再重复，哪怕结果是空——空也是结论。
     */
    fun preloadSearchTab(tab: Int) {
        val s = _state.value
        val q = s.searchQuery.trim()
        if (q.isEmpty()) return
        if (s.searching) return
        if (tab in searchedTabs || tab in searchPreloading) return
        if (q != searchedQuery) return
        // 当前页不预加载：当前页的空态可能是"确实搜过且没有"，重复发请求只会空转。
        if (tab == s.searchTab) return

        searchPreloading.add(tab)
        val seq = searchSeq
        val qq = s.searchSource == "qq"   // 6.9.3：预加载按当前搜索源走对应接口
        launchSafely {
            val done = runCatching {
                when (tab) {
                    1 -> {
                        val list = withContext(Dispatchers.IO) {
                            runCatching {
                                if (qq) {
                                    OnlineMusicApi.qqSearchSingers(q, 60).map {
                                        NeteaseApi.NsArtist(id = "", name = it.name, pic = it.pic, score = it.songNum.toLong())
                                    }
                                } else {
                                    NeteaseApi.searchArtists(q, 50)
                                }
                            }.getOrDefault(emptyList())
                        }
                        if (seq == searchSeq && _state.value.searchQuery.trim() == q) {
                            _state.value = _state.value.copy(searchArtists = list)
                            if (list.isNotEmpty()) searchedTabs.add(1)
                        }
                    }
                    2 -> {
                        val list = withContext(Dispatchers.IO) {
                            runCatching {
                                if (qq) {
                                    OnlineMusicApi.qqSearchAlbums(q, 60).map {
                                        NeteaseApi.NsAlbum(id = "", name = it.name, artist = it.artist, pic = it.pic)
                                    }
                                } else {
                                    NeteaseApi.searchAlbums(q, 50)
                                }
                            }.getOrDefault(emptyList())
                        }
                        if (seq == searchSeq && _state.value.searchQuery.trim() == q) {
                            _state.value = _state.value.copy(searchAlbums = list)
                            if (list.isNotEmpty()) searchedTabs.add(2)
                        }
                    }
                    3 -> {
                        val list = withContext(Dispatchers.IO) {
                            runCatching {
                                NeteaseApi.searchPlaylists(q, SEARCH_PLAYLIST_PAGE, 0)
                            }.getOrDefault(emptyList())
                        }
                        if (seq == searchSeq && _state.value.searchQuery.trim() == q) {
                            _state.value = _state.value.copy(
                                searchPlaylistsFound = list,
                                searchHasMore = list.size >= SEARCH_PLAYLIST_PAGE
                            )
                            if (list.isNotEmpty()) searchedTabs.add(3)
                        }
                    }
                }
                // 记住"这一类问过了"，哪怕结果是空：避免邻页反复组合时重复打网络。
                searchedTabs.add(tab)
                true
            }.getOrDefault(false)
            searchPreloading.remove(tab)
            if (!done) Unit
        }
    }

    /**
     * 打开歌手详情页：先立即展示曲库里同歌手的歌，再异步补充网易云热门歌曲。
     * artistId 为空（从本地歌曲点歌手）时只展示曲库内容。
     */
    fun openArtist(artistId: String, artistName: String, avatar: String = "") {
        val name = artistName.trim()
        if (name.isEmpty()) return
        val local = _state.value.homeSongs.filter { artistMatches(it.artist, name) }
        val cover = avatar.ifBlank {
            local.firstOrNull { it.filePath.startsWith("http") }?.filePath.orEmpty()
        }
        _state.value = _state.value.copy(
            artistDetail = ArtistDetailState(
                artistId = artistId,
                name = name,
                avatar = cover,
                songs = local,
                loading = true,
                notice = if (local.isEmpty()) "正在从网易云补全歌曲…" else ""
            )
        )
        launchSafely {
            // 第一步：没带歌手 id 时，先按名字查一次，拿到 id 和头像。
            // 这一步是之前「毛不易只有两首」的根因：从歌曲/播放页点进来时
            // artistId 是空的，旧代码直接 return，根本没有去网易云取歌。
            var id = artistId.trim()
            var pic = cover
            if (id.isBlank()) {
                val found = withContext(Dispatchers.IO) {
                    runCatching { NeteaseApi.searchArtists(name, 8) }.getOrDefault(emptyList())
                }.let { list ->
                    list.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?: list.firstOrNull { it.name.contains(name, true) || name.contains(it.name, true) }
                }
                if (found != null) {
                    id = found.id
                    if (pic.isBlank()) pic = found.pic
                }
            }
            val cur0 = _state.value.artistDetail ?: return@launchSafely
            if (cur0.name != name) return@launchSafely
            _state.value = _state.value.copy(
                artistDetail = cur0.copy(
                    artistId = id,
                    avatar = pic.ifBlank { cur0.avatar }
                )
            )
            if (id.isBlank()) {
                val cur = _state.value.artistDetail ?: return@launchSafely
                if (cur.name != name) return@launchSafely
                _state.value = _state.value.copy(
                    artistDetail = cur.copy(
                        loading = false,
                        notice = if (cur.songs.isEmpty()) "暂时没有找到这位歌手的歌" else ""
                    )
                )
                return@launchSafely
            }

            // 第二步：热门 50 首、按名搜索、专辑三路**同时**发出去。
            // 6.9.7：之前专辑是 async 但紧跟着 await，热门歌要等专辑一起回来才上屏——
            // 专辑接口一慢，整页就白等，用户体感就是"歌手页加载特别慢"。
            // 现在谁先回来谁先上屏：热歌 → 专辑 → 更多歌曲，分三次增量发布。
            val topDeferred = async(Dispatchers.IO) {
                runCatching {
                    val raw = NeteaseApi.artistTopSongs(id)
                    val mine = raw.filter { artistMatches(it.artist, name) }
                    MusicRepository.fromNetease(name, mine.ifEmpty { raw })
                }.getOrDefault(emptyList())
            }
            val moreDeferred = async(Dispatchers.IO) {
                runCatching {
                    val raw = NeteaseApi.searchSongsPaged(name, pages = 4, perPage = 100)
                    val mine = raw.filter { artistMatches(it.artist, name) }
                    MusicRepository.fromNetease(name, mine)
                }.getOrDefault(emptyList())
            }
            val albumsDeferred = async(Dispatchers.IO) {
                runCatching { NeteaseApi.artistAlbums(id, 30) }.getOrDefault(emptyList())
            }

            val top = topDeferred.await()
            val afterTop = mergeArtistSongs(local, top)
            var cur = _state.value.artistDetail ?: return@launchSafely
            if (cur.name != name) return@launchSafely
            _state.value = _state.value.copy(
                artistDetail = cur.copy(
                    artistId = id,
                    avatar = pic.ifBlank { cur.avatar },
                    songs = afterTop,
                    notice = ""
                )
            )

            // 专辑回来就单独补一栏，不等搜索。
            val albums = runCatching { albumsDeferred.await() }.getOrDefault(emptyList())
            cur = _state.value.artistDetail ?: return@launchSafely
            if (cur.name == name && albums.isNotEmpty()) {
                _state.value = _state.value.copy(
                    artistDetail = cur.copy(albums = albums)
                )
            }

            // 第三步：按歌手名翻页搜索的结果回来后再并进列表。
            val more = moreDeferred.await()
            val merged = mergeArtistSongs(afterTop, more)
            cur = _state.value.artistDetail ?: return@launchSafely
            if (cur.name != name) return@launchSafely
            _state.value = _state.value.copy(
                artistDetail = cur.copy(
                    songs = merged,
                    loading = false,
                    avatar = pic.ifBlank { cur.avatar },
                    notice = if (merged.isEmpty()) "暂时没有找到这位歌手的歌" else ""
                )
            )
        }
    }

    /** 合并歌手页歌曲：本地优先、按「歌名 + 歌手」去重，远程最多补到 400 首。 */
    private fun mergeArtistSongs(base: List<Song>, extra: List<Song>): List<Song> {
        val seen = HashSet<String>(base.size * 2 + extra.size)
        val out = ArrayList<Song>(base.size + extra.size)
        for (song in base + extra) {
            val key = song.title.trim().lowercase() + "|" + song.artist.trim().lowercase()
            if (seen.add(key)) out.add(song)
            if (out.size >= 400) break
        }
        return out
    }

    /** 从歌曲行/播放页点歌手名：按名字查网易云，再补全这位歌手的歌。 */
    fun openArtistByName(artistName: String) {
        // 合唱歌曲的 artist 可能是「A, B, C」，取第一位主歌手，避免拿整串去搜导致搜不到。
        val primary = artistName.split('/', '&', ',', '，', '、', ';', '；')
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (primary.isNotBlank()) openArtist("", primary)
    }

    fun closeArtist() {
        if (_state.value.artistDetail != null) {
            _state.value = _state.value.copy(artistDetail = null)
        }
    }

    /** 歌手页点某首歌：队列切到该歌手全部歌曲（从这首开始），不写入最近播放。 */
    fun playFromArtist(song: Song) {
        val detail = _state.value.artistDetail
        val songs = detail?.songs ?: emptyList()
        if (songs.isEmpty()) { playSong(song); return }
        val handled = guardLibrarySingle(song) { playFromArtist(song) }
        if (handled) return
        val idx = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playList(songs, idx, title = "${detail?.name ?: "歌手"} · 歌手", addToRecent = false)
    }

    /** 网易云「我喜欢」列表里点歌：和本地列表一样先过图书馆模式校验 */
    fun playFromNeteaseLiked(index: Int) {
        val liked = _state.value.neteaseLikedSongs
        if (index !in liked.indices) return
        val tapped = liked[index]
        val handled = guardLibrarySingle(tapped) {
            val i = _state.value.neteaseLikedSongs.indexOfFirst { it.id == tapped.id }
            if (i >= 0) playFromNeteaseLiked(i)
        }
        if (handled) return
        playList(liked, index, title = "网易云我喜欢")
    }

    /** 歌手页「播放全部」。 */
    fun playAllArtist() {
        val detail = _state.value.artistDetail ?: return
        if (detail.songs.isEmpty()) return
        playList(detail.songs, 0, title = "${detail.name} · 歌手", addToRecent = false)
    }

    /** 判断歌曲的歌手串是否包含目标歌手（兼容 "A/B"、"A & B" 这类合唱写法）。 */
    private fun artistMatches(songArtist: String, name: String): Boolean {
        if (songArtist.equals(name, ignoreCase = true)) return true
        return songArtist.split('/', '&', ',', '，', '、', ';', '；')
            .any { it.trim().equals(name, ignoreCase = true) }
    }

    /**
     * 点专辑 → 展示专辑歌曲（复用网易云曲目弹层）。
     *
     * 6.3.9：单曲专辑（EP / 单曲）不再弹那个"只有一首歌"的列表页。
     * 用户的判断很直接——列表页是用来"挑歌"的，一首歌没有可挑的东西，
     * 点开就是一屏尴尬的空白。现在先取曲目再决定：
     *   · 只有 1 首 → 直接把这首加进播放队列开播（界面上还能看到"正在播放"）；
     *   · 2 首及以上 → 照旧打开曲目列表。
     * 取曲目期间先不铺列表页，避免"闪一下才消失"。
     */
    /**
     * 点专辑 → 展示专辑曲目。
     *
     * 6.9.7：用户明确「这个专辑不是一点就能播放，专辑就是点开看歌，点单曲才播」。
     * 之前单曲专辑会直接开播，和"点开专辑"的预期不符；现在**任何专辑都先打开曲目列表**，
     * 播放只发生在用户点了某一首歌之后。
     *
     * 点击立即切到"加载中"的列表页——网络慢时也有明确反馈，不再是点完像没反应。
     */
    fun openAlbumSongs(albumId: String, albumName: String, artist: String) {
        _state.value = _state.value.copy(
            neteaseListTitle = albumName.ifBlank { "专辑" },
            neteaseListSubtitle = "专辑 · $artist",
            neteaseListSongs = emptyList(),
            neteaseListLoading = true,
            neteaseListNotice = ""
        )
        launchSafely {
            val songs = withContext(Dispatchers.IO) {
                runCatching {
                    MusicRepository.fromNetease(albumName, NeteaseApi.albumSongs(albumId))
                }.getOrDefault(emptyList())
            }
            // 用户可能在加载期间关掉了弹层 / 换了别的列表：只在标题还是这张专辑时落数据。
            val cur = _state.value
            if (cur.neteaseListTitle != albumName.ifBlank { "专辑" }) return@launchSafely
            _state.value = _state.value.copy(
                neteaseListSongs = songs,
                neteaseListLoading = false,
                neteaseListNotice = if (songs.isEmpty()) "「${albumName.ifBlank { "这张专辑" }}」暂无歌曲" else ""
            )
        }
    }

    /**
     * 6.9.3：QQ 源的专辑点击 —— QQ 接口只给专辑名/歌手名，没有网易云专辑 id。
     * 按名字回网易云搜索定位这张专辑，找到就按网易云专辑打开；找不到退而求其次
     * 打开同名歌手页，绝不给用户一个点了没反应的结果。
     */
    fun openAlbumByName(albumName: String, artist: String) {
        launchSafely {
            val list = withContext(Dispatchers.IO) {
                runCatching { NeteaseApi.searchAlbums(albumName, 10) }.getOrDefault(emptyList())
            }
            val album = list.firstOrNull { it.name.equals(albumName, ignoreCase = true) }
                ?: list.firstOrNull()
            if (album != null) {
                openAlbumSongs(album.id, album.name, album.artist)
            } else if (artist.isNotBlank()) {
                openArtistByName(artist)
            } else {
                notice("没找到「$albumName」这张专辑")
            }
        }
    }

    /** 搜索到的歌单 → 复用歌单曲目加载 */
    fun openSearchedPlaylist(id: String, name: String) {
        _state.value = _state.value.copy(
            neteaseListTitle = name,
            neteaseListSubtitle = "歌单 · $name",
            neteaseListSongs = emptyList(),
            neteaseListLoading = true,
            neteaseListNotice = ""
        )
        launchSafely {
            val songs = withContext(Dispatchers.IO) {
                MusicRepository.fromNetease(name, NeteaseApi.playlistTracks(id))
            }
            if (_state.value.neteaseListTitle != name) return@launchSafely
            _state.value = _state.value.copy(
                neteaseListLoading = false,
                neteaseListSongs = songs,
                neteaseListNotice = if (songs.isEmpty()) "该歌单暂无歌曲" else ""
            )
        }
    }

    fun toggleFolder(path: String) {
        val cur = _state.value.selectedFolders
        val next = if (path in cur) cur - path else cur + path
        _state.value = _state.value.copy(selectedFolders = next)
        saveSelectedFolders(next)
        applyFilters(resetToFirst = true)
        // 推荐候选池跟随筛选范围，选了文件夹推荐也要跟着收窄
        updateRecommendations()
    }

    /** 清空文件夹选择（回到全部音乐） */
    fun clearFolders() {
        _state.value = _state.value.copy(selectedFolders = emptySet())
        saveSelectedFolders(emptySet())
        applyFilters(resetToFirst = true)
        updateRecommendations()
    }

    // ==================== 自定义歌单 ====================

    /** 刷新歌单列表（增删后同步到 state） */
    private fun refreshPlaylists() {
        // 歌单集合变了，歌单曲目缓存一并作废（新建/删除/改名都会走到这里）
        invalidatePlaylistCache()
        _state.value = _state.value.copy(playlists = Playlists.all())
        refreshHomeCovers()
    }

    /**
     * 6.9.8：首页「我的音乐」真实封面。
     *
     * 用户要求：歌单卡要有封面——优先「正在听的这首歌」（正在听这个歌单时封面跟着变），
     * 其次「最近听过的歌单内歌曲」，最后歌单里随机一首；「我喜欢的音乐」卡用
     * 最近一首红心歌的封面。封面来源统一走 Song.filePath（ArtworkLoader 缓存复用）。
     */
    fun refreshHomeCovers() {
        launchSafelyIo {
            val lastPlayed = runCatching { PlayHistory.getLastPlayed() }.getOrDefault(emptyMap())
            val current = _state.value.currentSong
            val map = HashMap<Long, String>()
            for (pl in runCatching { Playlists.all() }.getOrDefault(emptyList())) {
                val ids = runCatching { Playlists.songs(pl.id) }.getOrDefault(emptyList())
                if (ids.isEmpty()) continue
                fun coverOf(song: Song?): String =
                    song?.filePath?.takeIf { it.isNotBlank() }
                        ?: song?.albumArtUri?.toString().orEmpty()
                var pick: Song? = null
                // ① 正在听的歌恰好在这个歌单里 → 封面跟着播放走
                if (current != null && ids.contains(current.id)) pick = current
                // ② 最近听过的一首歌单内歌曲
                if (pick == null) {
                    val bestId = ids.mapNotNull { id -> lastPlayed[id]?.let { id to it } }
                        .maxByOrNull { it.second }?.first
                    if (bestId != null) {
                        pick = runCatching { Playlists.songById(bestId) }.getOrNull()
                            ?: runCatching { PlayHistory.knownSong(bestId) }.getOrNull()
                    }
                }
                // ③ 歌单里随机一首（seed 取歌单 ID，同一歌单每次随机结果一致，不闪变）
                if (pick == null || coverOf(pick).isBlank()) {
                    val rnd = java.util.Random(pl.id)
                    val shuffled = ids.shuffled(rnd)
                    for (id in shuffled) {
                        val song = runCatching { Playlists.songById(id) }.getOrNull()
                            ?: runCatching { PlayHistory.knownSong(id) }.getOrNull()
                        if (song != null && coverOf(song).isNotBlank()) { pick = song; break }
                    }
                }
                coverOf(pick).takeIf { it.isNotBlank() }?.let { map[pl.id] = it }
            }
            // 「我喜欢的音乐」：正在听的歌若已红心优先；否则最近听过的红心歌
            val likedIds = runCatching { PlayHistory.getLikes() }.getOrDefault(emptySet())
            var likedPick: Song? = null
            if (current != null && current.id in likedIds) likedPick = current
            if (likedPick == null) {
                likedPick = runCatching { PlayHistory.likedSongs() }
                    .getOrDefault(emptyList())
                    .filter { it.filePath.isNotBlank() || it.albumArtUri != null }
                    .maxByOrNull { lastPlayed[it.id] ?: 0L }
            }
            val likedCover = likedPick?.filePath?.takeIf { it.isNotBlank() }
                ?: likedPick?.albumArtUri?.toString().orEmpty()
            _state.value = _state.value.withExtras {
                copy(localPlaylistCovers = map, likedPlaylistCover = likedCover)
            }
        }
    }

    /** 本地歌单 ID ↔ 云端歌单 ID 的映射（存在 jun_prefs，重装后会由同名歌单自动重建） */
    private fun cloudPlaylistId(localId: Long): Long = prefs.getLong("cloud_pl_$localId", 0L)

    private fun setCloudPlaylistId(localId: Long, cloudId: Long) {
        if (localId > 0 && cloudId > 0) {
            prefs.edit().putLong("cloud_pl_$localId", cloudId).apply()
        }
    }

    /**
     * 6.4：给「播放库整理」算一份体检报告。
     *
     * 放 IO 线程算（O(n²·m) 求交集），算完直接塞进 state，弹层读它渲染。
     * 只算不改——真正动手是 [applyLibraryTidy]。
     */
    fun auditLibrary() {
        launchSafely {
            val health = withContext(Dispatchers.IO) {
                runCatching { Playlists.audit() }.getOrNull()
            }
            _state.value = _state.value.copy(libraryHealth = health, libraryAuditing = false)
        }
    }

    /**
     * 6.5：打开歌单页时**静默**算一次体检，只为决定「整理」旁边要不要点那颗小圆点。
     *
     * 和 [auditLibrary] 的区别就在"静默"两个字：
     *  · 不带 libraryAuditing 的那套 loading 态——用户打开歌单页是想挑歌，
     *    不是来看我们算东西的，转圈反而碍眼；
     *  · 5 分钟内不重算。audit 是 O(n²·m) 的集合求交，歌单多的时候几十毫秒，
     *    每次拉弹层都算一遍不值得，而歌单也不会在 5 分钟内自己长出几十个来。
     *  · 已经有报告就不算：上一次的结果仍然有效，多一点延迟无所谓。
     */
    fun auditLibraryQuietly() {
        val now = System.currentTimeMillis()
        if (now - lastQuietAuditAt < 5 * 60_000L) return
        if (_state.value.libraryHealth != null) return
        lastQuietAuditAt = now
        launchSafely {
            val health = withContext(Dispatchers.IO) {
                runCatching { Playlists.audit() }.getOrNull()
            }
            if (health != null) _state.value = _state.value.copy(libraryHealth = health)
        }
    }

    /** 上次静默体检的时间戳，见 [auditLibraryQuietly] */
    private var lastQuietAuditAt = 0L

    /**
     * 6.4：执行整理方案（用户确认后调用）。
     *
     * [into] 是碎片歌单的并入目标：-1 = 只删碎片、不搬歌。
     * 做完重算一次报告，并把歌单列表刷到界面——用户能立刻看到"少了 6 个歌单"。
     */
    fun applyLibraryTidy(into: Long = -1L) {
        launchSafely {
            val result = withContext(Dispatchers.IO) {
                runCatching { Playlists.tidyUp(into) }.getOrNull()
            }
            refreshPlaylists()
            val (moved, removed) = result ?: (0 to 0)
            if (removed > 0) {
                notice("已整理：合并 $moved 首、删掉 $removed 个歌单")
            } else {
                notice("歌单已经很干净了，没有需要合并的")
            }
            auditLibrary()
        }
    }

    /** 创建歌单并选中它 */
    fun createPlaylist(name: String): Long {
        val id = Playlists.create(name)
        refreshPlaylists()
        selectPlaylist(id)
        // 已登录则同步到云端
        if (AccountStore.isLoggedIn) {
            launchSafely {
                val cloudId = AuthApi.createPlaylistCloud(name)
                if (cloudId != null) setCloudPlaylistId(id, cloudId)
            }
        }
        return id
    }

    /**
     * 从网易云歌单链接 / 纯歌单 ID 导入到本地歌单。
     *
     * 只支持公开歌单（私密歌单需要登录 Cookie，这里直接给出提示）。
     * 导入的每首歌仍存网易云 songId：有版权直接播；没版权时起播会按
     * 「歌名 + 歌手」自动去 jOOX 找同名曲，所以导入后大部分灰歌也能听。
     */
    /**
     * R13：导入 **Jun 口令**（本 App 自己的歌单分享码，见 data/PlaylistShare.kt）。
     *
     * 为什么和链接导入共用一个入口方法：剪贴板卡片那条链路（MainScreen 里的 ClipboardImportPrompt）
     * 只会调 importNeteasePlaylist(剪贴板文本)，如果口令另开一个入口，卡片那边就得先判类型再分叉，
     * 而"判类型"这件事本来就在数据层（PlaylistShare.looksLikeShareCode）。所以在这里拐一道，
     * 上层两个调用方（卡片 / 分享弹层）都只管把文本递进来。
     *
     * 匹配、建歌单、落盘全在 PlaylistImport.importCode 里（IO 线程、失败不崩）；
     * 这里只负责导完把「我的歌单」刷新一遍，新歌单立刻出现在歌单层，不用重进 App。
     */
    suspend fun importJunShareCode(
        raw: String,
        onProgress: (PlaylistImport.Progress) -> Unit = {}
    ): ShareImportOutcome {
        val out = PlaylistImport.importCode(raw, _state.value.allSongs, onProgress)
        if (out.ok) refreshPlaylists()
        return out
    }

    suspend fun importNeteasePlaylist(
        input: String,
        // 6.9.8：分片进度回调 (已处理, 总数)。页面拿它画确定进度条，
        // 500 首的歌单不再是一句「正在导入…」干等 40 秒。
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        // 6.4：导入成功后回一份结构化统计（可播 / 需换源 / 缺哪几首），
        // 让界面能画成三行而不是糊一句「已导入 264 首」。
        onResult: (com.jun.musiccloud.data.ImportStats) -> Unit = {}
    ): String {
        // R13：先认 Jun 口令。口令里没有 https://，PlaylistLinkDetector 那套链接判据不会误认它；
        // 反过来，一段被截断的口令在这里也会被解成明确的失败原因，而不是当成歌单号去查网易云。
        if (PlaylistShare.looksLikeShareCode(input)) {
            val out = importJunShareCode(input)
            return out.message
        }
        // 优先让自有服务器解析：短链跳转、分享文案里的中文/全角数字、
        // 手机网络访问网易云不稳定，这些问题都在服务器侧解决。
        // 服务器不可达时再退回手机直连，保证离线/服务故障时仍能尝试。
        //
        // 6.9.8：改成「分片拉」。服务端一次最多做 300 首（约 9 秒），
        // 返回 hasMore/nextOffset，这里循环把剩下的要回来。
        // 只有汽水这类逐首搜网易云的平台会真的分片，QQ / 酷狗 / 网易云
        // 第一片就 hasMore=false，行为跟以前完全一样。
        val server = withContext(Dispatchers.IO) { NeteaseApi.playlistInfoFromServer(input, 0) }
        val gathered = ArrayList<NeteaseApi.NsSong>()
        var extraSkipped = 0
        // 6.4：可播统计按片累加。null 表示该平台预判不了，要一路传下去，
        // 不能因为其中一片回了数字就把另一片的 null 覆盖掉。
        var playableSum = 0
        var playableKnown = true
        var needSourceSum = 0
        var predictUnknown = false
        val missingAll = ArrayList<Pair<String, String>>()
        val needSourceIdAll = ArrayList<String>()
        if (server is NeteaseApi.PlaylistServerResult.Ok) {
            gathered.addAll(server.tracks)
            if (server.predictUnknown || server.playable == null) playableKnown = false
            server.playable?.let { playableSum += it }
            server.needSource?.let { needSourceSum += it }
            predictUnknown = server.predictUnknown
            missingAll.addAll(server.missing)
            needSourceIdAll.addAll(server.needSourceIds)
            var doneNow = server.done
            var totalNow = server.total
            var nextOffset = server.nextOffset
            var guard = 0
            // 6.6：某片没拉到时给用户的提示（旧实现静默 break，用户根本不知道少了）。
            var failedShards = 0
            while (server.hasMore && guard < 30) {
                guard += 1
                onProgress(doneNow.coerceAtMost(totalNow), totalNow)
                // 6.6 断点续导：单片失败重试 3 次。
                // 旧实现 `if (next !is Ok) break` —— 一次网络抖动就把后面所有分片
                // 整段丢掉，这正是「汽水导入会缺很多音乐」的主因之一。
                var fetched: NeteaseApi.PlaylistServerResult =
                    NeteaseApi.PlaylistServerResult.Unavailable
                for (attempt in 0 until 3) {
                    fetched = withContext(Dispatchers.IO) {
                        NeteaseApi.playlistInfoFromServer(input, nextOffset)
                    }
                    if (fetched is NeteaseApi.PlaylistServerResult.Ok) break
                    if (attempt < 2) delay(500L * (attempt + 1))
                }
                val ok = fetched as? NeteaseApi.PlaylistServerResult.Ok
                if (ok == null) { failedShards += 1; break }
                gathered.addAll(ok.tracks)
                extraSkipped += ok.skipped
                if (ok.predictUnknown || ok.playable == null) playableKnown = false
                ok.playable?.let { playableSum += it }
                ok.needSource?.let { needSourceSum += it }
                if (ok.predictUnknown) predictUnknown = true
                missingAll.addAll(ok.missing)
                needSourceIdAll.addAll(ok.needSourceIds)
                doneNow = ok.done
                totalNow = ok.total
                nextOffset = ok.nextOffset
                if (!ok.hasMore) break
            }
            if (failedShards > 0) {
                // 让结果文案告诉用户"哪部分没拿到"，而不是假装全部成功
                missingAll.add("有 $failedShards 个分片本次未取到" to "")
            }
            onProgress(gathered.size.coerceAtMost(totalNow), totalNow)
        }
        // QQ / 汽水歌单要先按歌名+歌手匹配到网易云，匹配不上的曲目会体现在返回文案里
        var unmatchedNote = ""
        // 6.9.6：来源标注。QQ / 酷狗的歌带平台原生 id 进来，界面上要让人一眼看出
        // 「这批歌是从哪个平台搬的」，否则用户会疑惑"为什么这首歌的封面/码率跟平时不一样"
        var platformNote = ""
        val (rawName, tracks) = when (server) {
            is NeteaseApi.PlaylistServerResult.Ok -> {
                platformNote = when (server.platform) {
                    "qq" -> " · QQ 音乐"
                    "kugou" -> " · 酷狗"
                    "qishui" -> " · 汽水音乐"
                    else -> ""
                }
                if (server.limited) {
                    // 6.9.7：源站自己只开放一部分（酷狗 gcid 歌单：网页端固定前 10 首）。
                    // 这不是我们的上限、也不是匹配失败，必须说清楚是谁的限制，
                    // 否则用户只会觉得"这 App 连 10 首都导不全，废了"。
                    // 提示要短 —— 它挂在 Toast 尾巴上，超过一行会被系统截掉。
                    val missingCount = (server.total - gathered.size).coerceAtLeast(0)
                    unmatchedNote = if (missingCount > 0) {
                        // 6.9.8：酷狗三条路都试完后才说这句话，所以要把「试过什么」讲清楚，
                        // 否则用户会以为是我们没努力。字数压到一行内，Toast 尾巴放得下。
                        val who = server.ownerName.takeIf { it.isNotBlank() }?.let { "，作者「$it」" } ?: ""
                        if (server.limitedReason == "searchMiss") {
                            "（酷狗仅开放前 ${gathered.size} 首，全单 ${server.total} 首$who，未找到完整歌单）"
                        } else {
                            "（酷狗此链接只开放前 ${gathered.size} 首，歌单实际 ${server.total} 首$who）"
                        }
                    } else {
                        ""
                    }
                } else if (server.direct) {
                    // 6.9.6：自带平台 id 的歌单不存在"匹配不上"，只有"超出单次上限被截断"。
                    // 以前这里会谎报成"1292 首里 1232 首没匹配上"，用户以为是找歌失败了，
                    // 其实是我们主动截断的 —— 说清楚原因比报个数字有用。
                    if (server.total > server.matched) {
                        val cut = server.total - server.matched
                        unmatchedNote = "（原歌单 ${server.total} 首，超出单次导入上限，" +
                            "已取前 ${server.matched} 首，剩下 $cut 首可再贴一次分段导入）"
                    }
                } else if (server.total > server.matched) {
                    val sb = StringBuilder()
                    sb.append("（原歌单 ${server.total} 首，${server.unmatched} 首没匹配上")
                    // 服务器愿意报清单时把名字列出来 —— "有 N 首不见了"和"这 N 首是哪几首"
                    // 是两回事，后者才知道该不该手动补。最多列 15 条，再多就只报数量。
                    val miss = server.missing
                    if (miss.isNotEmpty()) {
                        sb.append("：")
                        sb.append(miss.take(15).joinToString("、") { (t, a) ->
                            if (a.isBlank()) t else "$t（$a）"
                        })
                        if (miss.size > 15) sb.append("…等 ${miss.size} 首")
                    }
                    sb.append("）")
                    unmatchedNote = sb.toString()
                }
                // 6.9.7：还有一批压根没来得及送去匹配（时间预算先到）。
                // 这段和"没搜到"分开说 —— 再点一次导入即可续上，已匹配的走 6 小时缓存，很快。
                if (!server.limited && (server.skipped + extraSkipped) > 0) {
                    val tip = "，另有 ${server.skipped + extraSkipped} 首这次没来得及匹配，再点一次导入即可续上"
                    unmatchedNote = if (unmatchedNote.isBlank()) "（$tip）"
                    else unmatchedNote.dropLast(1) + tip + "）"
                }
                // 分片累计后的完整曲目；没有分片时它就等于 server.tracks
                server.name to gathered
            }
            is NeteaseApi.PlaylistServerResult.Fail -> return server.message
            NeteaseApi.PlaylistServerResult.Unavailable -> {
                // R9 修的是这里：服务器不可达时，原来不分平台都把链接里的数字抠出来
                // 拿去查**网易云** —— QQ 歌单号在网易云那边要么查空（"导入进去不显示"），
                // 要么撞上同号的网易云歌单（导进来一个毫不相干的歌单）。
                // 只有真·网易云来源（域名 / 短链 / 裸 ID）才允许走手机端直连兜底。
                if (!looksNeteaseOnly(input)) {
                    return "导入服务暂时连不上。QQ / 汽水歌单要靠服务器逐首匹配网易云，" +
                        "手机端直连查不到，请稍后重试（不用重新粘贴，直接再点一次导入）"
                }
                val parsed = withContext(Dispatchers.IO) { resolveNeteasePlaylistId(input) }
                val playlistId = when (parsed) {
                    is PlaylistParse.Ok -> parsed.id
                    is PlaylistParse.Fail -> return parsed.message
                }
                withContext(Dispatchers.IO) { NeteaseApi.playlistInfo(playlistId) }
            }
        }
        if (tracks.isEmpty()) {
            val total = (server as? NeteaseApi.PlaylistServerResult.Ok)?.total ?: 0
            if (total > 0) return "歌单共 $total 首，但没有一首能读出歌曲信息，换个歌单试试"
            return "歌单读取失败：可能是私密歌单、链接已失效，或对方服务器暂时不可访问"
        }
        val name = rawName.ifBlank { "导入歌单" }.trim().take(30)
        val list = MusicRepository.fromNetease(name, tracks)
        // 6.6 断点续导：同一个链接再导一次，复用上次那个歌单、只把新匹配到的歌补进去。
        // 旧实现每次都 Playlists.create(name)，"再点一次导入"只会多出一个同名歌单，
        // 缺的那几首永远补不回来 —— 用户体感就是「怎么导都不全」。
        val resumeKey = "import_resume_" + input.hashCode().toString().replace('-', 'n')
        val savedId = runCatching { prefs.getLong(resumeKey, -1L) }.getOrDefault(-1L)
        val hadPrevious = savedId > 0 &&
            runCatching { Playlists.songs(savedId).isNotEmpty() }.getOrDefault(false)
        val localId = if (hadPrevious) savedId else Playlists.create(name)
        runCatching { prefs.edit().putLong(resumeKey, localId).apply() }
        var newlyAdded = 0
        withContext(Dispatchers.IO) {
            // 这三步都是整表重写，批量接口一次落盘，几百首也不会卡住
            PlayHistory.saveKnownSongs(list)
            // 6.9.6：歌单自己的曲目快照。knownSongs 只留 1000 首且所有歌单共用，
            // 千首歌单会把别人的歌挤掉；这里单独存一份，歌单才不会"自己少歌"。
            Playlists.rememberSongs(list)
            // addSongs 内部按 id 去重，续导时不会把已导入的重复塞一遍
            newlyAdded = Playlists.addSongs(localId, list.map { it.id })
            // 6.4：落一份「哪些歌要换源」的标记，歌单详情的「只看可播」靠它过滤。
            // 没有标记的歌一律按可播处理 —— 宁可让用户点进去发现要换源，
            // 也不要把能播的歌藏起来。
            if (needSourceIdAll.isNotEmpty() || playableKnown) {
                val needSet = needSourceIdAll.toHashSet()
                val playableIds = gathered.map { it.id }.filter { it.isNotBlank() && it !in needSet }
                Playlists.markPlayable(playableIds, needSourceIdAll)
            }
        }
        refreshPlaylists()
        selectPlaylist(localId)
        // 导入的歌单也要进云端：登录状态下立刻补建云歌单并上传曲目，
        // 否则换设备后这个歌单只存在于导入它的手机上。
        if (AccountStore.isLoggedIn) {
            launchSafely { syncPlaylistsQuietly() }
        }
        // 6.4：把结构化统计交给界面。这里的 playable 是「预判」，
        // 播放失败时客户端会在本地纠正，所以不会一直错下去。
        (server as? NeteaseApi.PlaylistServerResult.Ok)?.let { ok ->
            runCatching {
                onResult(
                    com.jun.musiccloud.data.ImportStats(
                        total = ok.total,
                        imported = list.size,
                        playable = if (playableKnown) playableSum else null,
                        needSource = if (playableKnown) needSourceSum else null,
                        missing = missingAll,
                        limited = ok.limited,
                        limitedNote = unmatchedNote,
                        skipped = ok.skipped + extraSkipped,
                        direct = ok.direct,
                        predictUnknown = predictUnknown || !playableKnown
                    )
                )
            }
        }
        val resumeNote = when {
            hadPrevious && newlyAdded > 0 -> "（断点续导：本次新增 $newlyAdded 首，已导入的不会重复）"
            hadPrevious && newlyAdded == 0 -> "（这个链接的歌已经导全了，没有新增）"
            else -> ""
        }
        return "已导入 ${list.size} 首到「$name」$platformNote$unmatchedNote$resumeNote"
    }

    /**
     * 这条输入是不是"只能靠网易云自己"的：网易云域名 / 163 短链，或者不带链接的裸 ID。
     *
     * QQ / 汽水 / 淘歌的链接必须走服务器的名字匹配，绝不能拿它们的歌单号去查网易云
     * （号段是两套，撞上就是导错，撞不上就是导空）。
     */
    private fun looksNeteaseOnly(raw: String): Boolean {
        val s = normalizeShareText(raw).lowercase()
        val otherHost = s.contains("y.qq.com") || s.contains("qq.com") ||
            s.contains("kugou") || s.contains("douyin") || s.contains("qishui") ||
            s.contains("taoge")
        if (otherHost) return false
        if (s.contains("music.163.com") || s.contains("163cn.tv")) return true
        // 没写链接、只给一串数字：沿用旧行为，按网易云 ID 处理
        return !s.contains("http")
    }

    private sealed class PlaylistParse {
        data class Ok(val id: String) : PlaylistParse()
        data class Fail(val message: String) : PlaylistParse()
    }

    /**
     * 把分享文案归一化：百分号编码、转义斜杠、全角数字、零宽字符都会让
     * 「复制过来的链接看着没问题、正则却匹配不上」。这里一次性抹平。
     */
    private fun normalizeShareText(raw: String): String {
        var s = runCatching { Uri.decode(raw) }.getOrDefault(raw)
        s = s.replace("\\/", "/").replace("&amp;", "&")
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch in '\uFF10'..'\uFF19' ->
                    sb.append(('0'.code + (ch.code - '\uFF10'.code)).toChar())
                ch == '\u3000' || ch == '\u00A0' || ch == '\u200B' || ch == '\uFEFF' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 从文本里找歌单 ID：先按链接参数找，带链接时不再拿整段里的任意数字兜底 */
    private fun findPlaylistId(text: String): String? {
        val s = text.trim()
        if (s.isEmpty()) return null
        // 纯 ID（允许「歌单ID：123456」这种前缀）
        Regex("(?i)^\\s*(?:歌单\\s*)?(?:id\\s*)?[:：]?\\s*(\\d{4,})\\s*$")
            .find(s)?.groupValues?.getOrNull(1)?.let { return it }
        val patterns = listOf(
            // ?id= / &id= / #/playlist?id=（排除 userid= 这种后缀）
            Regex("(?i)[?&#](?:id|playlistid|discoverid)=(\\d{4,})"),
            Regex("(?i)/playlist/(\\d{4,})"),
            Regex("(?i)(?<![a-z])id=(\\d{4,})"),
            Regex("(?i)playlistid\\D{0,4}(\\d{4,})")
        )
        for (p in patterns) p.find(s)?.groupValues?.getOrNull(1)?.let { return it }
        // 文案里明确写「歌单 ID：123」「playlist 123」才认
        Regex("(?i)(?:歌单|playlist)\\s*(?:id|编号)\\s*[:：=]?\\s*(\\d{4,})")
            .find(s)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("(?i)(?:歌单|playlist)\\s*[:：]\\s*(\\d{4,})")
            .find(s)?.groupValues?.getOrNull(1)?.let { return it }
        // 分享文案里「云村村民1783…」这类创建者 ID 也是 6 位以上数字：
        // 只要文本里带链接，就交给短链跳转解析，绝不把创建者 ID 当歌单 ID。
        if (!Regex("(?i)https?://").containsMatchIn(s)) {
            val all = Regex("(?<!\\d)\\d{6,}(?!\\d)").findAll(s).map { it.value }.toList()
            if (all.size == 1) return all[0]
        }
        return null
    }

    /** 短链（163cn.tv 等）跟随跳转，最多 6 层，兼容 302 与 HTML 里的跳转地址 */
    private fun followShortLink(start: String): String? {
        var current = start
        val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        repeat(6) {
            val conn = runCatching { URL(current).openConnection() as HttpURLConnection }.getOrNull()
                ?: return null
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", ua)
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return null
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                val body = runCatching {
                    conn.inputStream.bufferedReader().use { it.readText().take(200_000) }
                }.getOrNull() ?: return current
                val normalized = body.replace("\\/", "/")
                Regex("https?://[^\\s\"'<>]*music\\.163\\.com[^\\s\"'<>]*")
                    .find(normalized)?.value?.let { return it }
                Regex("playlist\\?id=\\d{4,}").find(normalized)?.value
                    ?.let { return "https://music.163.com/$it" }
                return current
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { conn.disconnect() }
            }
        }
        return current
    }

    /** 解析用户粘贴的内容：直接链接 / 分享文案 / 纯 ID / 短链都能处理 */
    private fun resolveNeteasePlaylistId(raw: String): PlaylistParse {
        val text = normalizeShareText(raw)
        if (text.isBlank()) return PlaylistParse.Fail("先粘贴歌单链接或 ID")
        // 单曲链接里也有 id，先排除掉，避免拿单曲 ID 去查歌单后报「读取失败」
        if (Regex("(?i)/song(\\?|/)").containsMatchIn(text) &&
            !Regex("(?i)playlist").containsMatchIn(text)
        ) {
            return PlaylistParse.Fail("这是单曲链接，请打开歌单页面点分享，复制歌单链接")
        }
        findPlaylistId(text)?.let { return PlaylistParse.Ok(it) }
        // 本地没抠到 ID：可能是 163cn.tv 短链，跟随跳转后再试一次
        val url = Regex("https?://[^\\s\"'<>，。；、）】》]+").find(text)?.value
            ?: return PlaylistParse.Fail("没有识别到歌单 ID，请粘贴网易云歌单链接或纯数字 ID")
        val finalUrl = followShortLink(url)
        if (finalUrl != null) {
            findPlaylistId(normalizeShareText(finalUrl))?.let { return PlaylistParse.Ok(it) }
        }
        return PlaylistParse.Fail("没有识别到歌单 ID：短链可能已失效，请用浏览器打开后复制完整链接")
    }

    /** 重命名歌单 */
    fun renamePlaylist(playlistId: Long, newName: String) {
        Playlists.rename(playlistId, newName)
        refreshPlaylists()
        if (AccountStore.isLoggedIn) {
            val cloudId = cloudPlaylistId(playlistId)
            if (cloudId > 0) {
                launchSafely { runCatching { AuthApi.renamePlaylistCloud(cloudId, newName) } }
            }
        }
    }

    /** 删除歌单；若正选中则回到全部 */
    fun deletePlaylist(playlistId: Long) {
        Playlists.delete(playlistId)
        if (_state.value.activePlaylistId == playlistId) {
            _state.value = _state.value.copy(activePlaylistId = null)
            prefs.edit().putLong(KEY_ACTIVE_PLAYLIST, -1L).apply()
            applyFilters(resetToFirst = true)
        }
        refreshPlaylists()
        // 云同步
        if (AccountStore.isLoggedIn) {
            val cloudId = cloudPlaylistId(playlistId)
            prefs.edit().remove("cloud_pl_$playlistId").apply()
            if (cloudId > 0) {
                launchSafely { AuthApi.deletePlaylistCloud(cloudId) }
            }
        }
    }

    /** 选中歌单（null = 回到文件夹/收藏视图） */
    fun selectPlaylist(playlistId: Long?) {
        // 选中歌单与「我喜欢」互斥
        _state.value = _state.value.copy(activePlaylistId = playlistId, onlyLiked = false)
        prefs.edit()
            .putLong(KEY_ACTIVE_PLAYLIST, playlistId ?: -1L)
            .putBoolean(KEY_ONLY_LIKED, false)
            .apply()
        applyFilters(resetToFirst = true)
    }

    /**
     * 一键回到「全部音乐」首页：同时清掉 onlyLiked 与 activePlaylistId，
     * 只调一次 applyFilters，避免分两步重置时中间态把队列滤成空列表。
     */
    fun goHome() {
        _state.value = _state.value.copy(onlyLiked = false, activePlaylistId = null)
        prefs.edit()
            .putLong(KEY_ACTIVE_PLAYLIST, -1L)
            .putBoolean(KEY_ONLY_LIKED, false)
            .apply()
        // 6.6：只换「浏览视图」，绝不重建播放队列。
        // 旧实现调 applyFilters(resetToFirst=true)，会把 queue 换成整库并 pushQueue
        // 重写 MediaController 时间线 —— 用户「在自建歌单里听歌按返回」会立刻切到
        // 歌单外的歌，同时横向卡片的 pager 还拿着旧 index 去索引新队列（Index:1,Size:1）。
        // 后台 6.5 的 10 条 IndexOutOfBounds 崩溃与这条路径同源。
        applyFilters(resetToFirst = true, browseOnly = true)
    }

    /** 设置「我喜欢」来源（0=本地，1=网易云），持久化到 ViewModel 状态 */
    fun setLikedSource(source: Int) {
        _state.value = _state.value.copy(likedSource = source)
    }

    /** 设置「歌单」来源（0=本地，1=网易云），持久化到 ViewModel 状态 */
    fun setPlaylistSource(source: Int) {
        _state.value = _state.value.copy(playlistSource = source)
    }

    /** 把一首歌加入歌单 */
    fun addToPlaylist(playlistId: Long, songId: Long) {
        Playlists.addSong(playlistId, songId)
        invalidatePlaylistCache()
        // 缓存这首歌的元数据：在线/搜索结果里的歌不在本地曲库 allSongs 里，
        // 若不缓存，重新打开歌单时 mapNotNull 会把这些 ID 丢掉，表现就是「加了很多首但只显示几首」。
        val song = _state.value.allSongs.firstOrNull { it.id == songId }
            ?: _state.value.queue.firstOrNull { it.id == songId }
            ?: _state.value.searchResults.firstOrNull { it.id == songId }
            ?: _state.value.likedSongs.firstOrNull { it.id == songId }
            ?: _state.value.neteaseListSongs.firstOrNull { it.id == songId }
        if (song != null) {
            // 6.9.5：元数据同时落进歌单快照（5000 条，按歌单量存），
            // 防止 known_songs 的 1000 条 LRU 把它挤掉后歌单内容静默消失
            runCatching { Playlists.rememberSongs(listOf(song)) }
            runCatching { PlayHistory.saveKnownSong(song) }
        }
        refreshPlaylists()
        // 列表内容变了：当前正看着这个歌单时必须重算队列，见 applyFilters 的 listContentChanged
        if (_state.value.activePlaylistId == playlistId) applyFilters(resetToFirst = false, listContentChanged = true)
        // 云同步
        if (AccountStore.isLoggedIn) {
            val cloudId = cloudPlaylistId(playlistId)
            if (song != null && cloudId > 0) {
                launchSafely { AuthApi.addSongToPlaylistCloud(cloudId, song) }
            } else if (cloudId <= 0) {
                // 歌单还没上云（离线创建 / 老版本导入）：立刻补建并全量对齐
                launchSafely { syncPlaylistsQuietly() }
            }
        }
    }

    /** 把一首歌从歌单移除 */
    fun removeFromPlaylist(playlistId: Long, songId: Long) {
        Playlists.removeSong(playlistId, songId)
        invalidatePlaylistCache()
        refreshPlaylists()
        if (_state.value.activePlaylistId == playlistId) applyFilters(resetToFirst = false, listContentChanged = true)
        // 云同步：服务器要的是 song_id 字符串，不是本地数据库行号。
        // 旧代码把本地 Long 发过去，服务器按数字当行号删，结果云端一首都没删掉。
        if (AccountStore.isLoggedIn) {
            val cloudId = cloudPlaylistId(playlistId)
            if (cloudId > 0) {
                val sid = buildLocalSongIndex()[songId]?.serverId ?: songId.toString()
                launchSafely {
                    runCatching { AuthApi.removeSongFromPlaylistCloud(cloudId, sid) }
                }
            }
        }
    }

    // ==================== 撤销 / 错误提示 / 省电动画 ====================

    /** 待执行的撤销动作（同时只保留一个：后一次操作覆盖前一次） */
    private var pendingUndo: (() -> Unit)? = null

    /**
     * 弹出一条可撤销提示。
     *
     * 不可逆操作（清空队列、删除待办）在动手**之前**调它：
     * 用户看到的顺序是「操作已生效 + 4 秒内可反悔」，比先弹一个确认对话框更少打断。
     */
    private fun requestUndo(message: String, action: () -> Unit) {
        pendingUndo = action
        _state.value = _state.value.withExtras { copy(undoNotice = message, undoNoticeTick = _state.value.undoNoticeTick + 1) }
    }

    /** 执行撤销（提示上的「撤销」键） */
    fun performUndo() {
        pendingUndo?.invoke()
        pendingUndo = null
        _state.value = _state.value.withExtras { copy(undoNotice = "") }
    }

    /**
     * 记一笔批量操作：进「最近批量操作」记录，同时把这一笔挂成可撤销。
     *
     * 批量喜欢 / 批量加歌单 / 批量下载一旦点错，范围可能是整榜几十上百首，
     * 所以每次批量动作都要留一条反悔的路（同时只保留最后一次，与撤销槽位一致）。
     */
    private fun recordBatchOp(text: String, undo: (() -> Unit)?) {
        val entry = BatchOpEntry(
            timeMs = System.currentTimeMillis(),
            text = text,
            undoable = undo != null
        )
        _state.value = _state.value.withExtras { copy(batchLog = (listOf(entry) + _state.value.batchLog).take(12)) }
        if (undo != null) requestUndo(text, undo) else _state.value = _state.value.withExtras { copy(undoNotice = "") }
    }

    /** 批量喜欢：整批只留一条提示（逐首 toggleLike 会把轻提示刷成弹幕） */
    fun batchLike(ids: List<Long>, source: String) {
        val toAdd = ids.filter { it !in _state.value.likedSongIds }
        if (toAdd.isEmpty()) {
            recordBatchOp("《$source》${ids.size} 首本来就都在「我喜欢」里", null)
            return
        }
        toAdd.forEach { toggleLike(it) }
        _state.value = _state.value.copy(likeNotice = "")
        recordBatchOp("已把《$source》${toAdd.size} 首加入喜欢") {
            toAdd.forEach { id -> if (id in _state.value.likedSongIds) toggleLike(id) }
        }
    }

    /** 批量加入某个歌单（带撤销：从该歌单里把这些 id 摘掉） */
    fun batchAddToPlaylist(playlistId: Long, songs: List<Song>, playlistName: String) {
        val ids = songs.map { it.id }
        songs.forEach { addToPlaylist(playlistId, it.id) }
        recordBatchOp("已把 ${songs.size} 首加进「$playlistName」") {
            ids.forEach { removeFromPlaylist(playlistId, it) }
        }
    }

    /**
     * 批量下载（撤销 = 取消这一批里还没下完的任务，已完成的文件不动）。
     *
     * 6.1.2：整批只出一句话。以前这里直接 `downloadSongs(songs)`，逐首各发一条回执，
     * 全选 200 首就是 200 条提示在同一个毫秒里互相顶掉，屏幕上最后只剩随机一条，
     * 而且已经下好的会被再下一遍、勾重的那首会开两条任务。
     * 判重/跳过/并发都收在 DownloadManager.downloadSongsBatch 一处，这里只负责把统计说成人话。
     */
    fun batchDownload(songs: List<Song>, source: String) {
        if (songs.isEmpty()) return
        val res = downloadManager.downloadSongsBatch(songs)
        val summary = when {
            res.noPerm ->
                "「设置 · 下载」里选的目录已经没有写入授权了，去重新选一次目录就能下"
            res.noSpace ->
                "空间可能不够，这一批 ${songs.size} 首没有开始下载——先到「设置 · 下载」清出点地方"
            res.added == 0 && res.downloaded > 0 && res.queued == 0 ->
                "《$source》这 ${res.downloaded} 首都已经下载好了"
            res.added == 0 ->
                "《$source》没有要新下的歌：已在队列 ${res.queued} 首 · 已下载 ${res.downloaded} 首"
            else -> buildString {
                append("已开始下载 ${res.added} 首")
                if (res.queued > 0) append(" · 已在队列 ${res.queued} 首")
                if (res.downloaded > 0) append(" · 跳过已下载 ${res.downloaded} 首")
            }
        }
        _state.value = _state.value.withExtras {
            copy(downloadNotice = summary, downloadNoticeTick = downloadNoticeTick + 1)
        }
        // 一首都没新开就别记这条批量操作：撤销空转会把上一条真正可撤销的记录挤掉
        if (res.added == 0) return
        val songIds = songs.map { it.id }.toSet()
        recordBatchOp("已开始下载《$source》的 ${res.added} 首") {
            val cancelable = _state.value.downloadTasks.filter {
                it.songId in songIds && it.status != DownloadStatus.COMPLETED
            }
            if (cancelable.isNotEmpty()) deleteDownloads(cancelable.map { it.id })
        }
    }

    /** 清空「最近批量操作」记录 */
    fun clearBatchLog() {
        _state.value = _state.value.withExtras { copy(batchLog = emptyList()) }
    }

    /** 上报一个用户看得见的错误（带「重试」入口） */
    fun reportError(message: String) {
        _state.value = _state.value.withExtras { copy(errorNotice = message, errorNoticeTick = _state.value.errorNoticeTick + 1) }
    }

    /**
     * 一句普通提示（不带「重试」）。
     *
     * 复用 loginPrompt 那对既有字段：它们本来就是一条底部轻提示，语义上就是「说一句话」，
     * 不必为了一句「先勾选要下载的歌」再给 PlayerUiState 加两个字段——
     * 那个 data class 的构造参数已经贴着 dex 寄存器上限了。
     */
    fun notice(message: String) {
        if (message.isBlank()) return
        // loginPrompt / loginPromptTick 是 PlayerUiState 自己的构造参数（不是 extras），
        // 所以这里走 .copy(...)，和 6011 那条「需要登录」提醒同一个写法。
        _state.value = _state.value.copy(
            loginPrompt = message,
            loginPromptTick = _state.value.loginPromptTick + 1
        )
    }

    /**
     * 起播看门狗（R26）。
     *
     * 有些"放不出来"连错误都不报：代理超时、上游把连接挂住、播放器一直 IDLE。
     * 这时 onPlayerError 永远不会来，界面就停在 0:00 一动不动 —— 用户描述正是这样。
     * 这里在每次点歌后挂一个 6 秒的闹钟：到点仍没有时长、没出声、也不是在缓冲，
     * 就判定这首拿不到音频，交给自动跳过处理。
     *
     * 为什么排除 BUFFERING：缓冲中说明地址是通的，只是网慢，这时候跳歌是帮倒忙。
     */
    private var startWatchdogJob: kotlinx.coroutines.Job? = null

    private fun armStartWatchdog(song: Song) {
        startWatchdogJob?.cancel()
        if (song.id <= 0L) return
        val sid = song.songId
        startWatchdogJob = launchSafely {
            delay(6000)
            val c = controller ?: return@launchSafely
            val st = _state.value
            // 已经切到别的歌 / 已经出声 / 已经拿到时长 → 不归它管
            if (st.currentSong?.id != song.id) return@launchSafely
            if (st.durationMs > 0L || c.isPlaying) return@launchSafely
            if (c.playbackState == Player.STATE_BUFFERING) return@launchSafely
            // 6.5.3：只有代理**确实判过**"这首拿不到地址"才跳。
            // 单纯的慢网 / 代理还在解析，绝不跳 —— 否则用户点了 A 却在听 D，
            // 看起来就是"播放器自己乱窜歌"。
            if (sid.isBlank()) return@launchSafely
            if (!com.jun.musiccloud.playback.AudioProxyServer.isUnplayable(sid)) return@launchSafely
            autoSkipUnplayable()
        }
    }

    // ===== R27：点歌"点了没反应"的两道兜底 =====

    /**
     * 控制器还没连上时被点的那一首。
     *
     * [playAt] 开头是 `controller ?: return` —— 服务还没连好（冷启动、服务被系统杀后重建）
     * 的几百毫秒里，用户点歌会**静默丢**：高亮不变、声音不变，看起来就是"点了死活没反应"。
     * 记下来，连上之后立刻补播。
     */
    private var pendingPlayIndex: Int = -1
    /** 是否正在建连接：防止用户连点时起一堆 MediaController */
    private var controllerConnecting: Boolean = false
    /** 点歌后的启动校验：到点还没切过去就强制重来一次 */
    private var startVerifyJob: kotlinx.coroutines.Job? = null

    private fun ensureControllerConnected() {
        if (controller != null || controllerConnecting) return
        // 6.9.8：App 在后台时**不主动拉起前台服务**。
        // Android 12+ 禁止后台 startForegroundService——之前夜里从后台触发
        // （定时器/耳机回连等）会抛 ForegroundServiceStartNotAllowedException，
        // 后台崩溃日志里一大半都是它。改为：后台只标记"待连接"，
        // 回到前台（MainActivity.onResume → onUiResumed）或用户点歌时再连。
        if (!isAppForeground()) return
        controllerConnecting = true
        runCatching { connectController() }
    }

    private fun isAppForeground(): Boolean = runCatching {
        val am = getApplication<Application>()
            .getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(true)

    /**
     * 点歌后的启动校验（R27）。
     *
     * 用户现场：「列表里前后两首都在，点后面那首死活没反应，点前面那首也没反应」。
     * 这类静默失败通常发生在 `setMediaItems` 与 `play()` 之间：控制器是跨进程代理，
     * 命令是异步队列，偶尔会出现"命令发了但播放器没接管"。
     *
     * 这里在点歌 1.6 秒后核对三件事：还在不在这一首、有没有出声、有没有时长。
     * 三样都没有、又不是在缓冲 → 说明这次点歌没被播放器接受，强制重写时间线再起播。
     * 只在"确实没动"时才动手，正常播放完全不受影响。
     */
    private fun verifyStartAfterTap(song: Song, index: Int) {
        startVerifyJob?.cancel()
        startVerifyJob = launchSafely {
            delay(1600)
            val c = controller ?: return@launchSafely
            val st = _state.value
            if (st.currentSong?.id != song.id) return@launchSafely
            if (c.isPlaying || st.durationMs > 0L) return@launchSafely
            if (c.playbackState == Player.STATE_BUFFERING) return@launchSafely
            // 真·无版权交给自动跳过，不要在这里死磕
            if (song.songId.isNotBlank() &&
                com.jun.musiccloud.playback.AudioProxyServer.isUnplayable(song.songId)
            ) return@launchSafely
            runCatching {
                // 走标准写入：目标曲放窗口第 0 位，尾部照常后台补齐
                writeTimelineFromQueue(c, index, 0L)
                c.play()
            }
        }
    }

    /**
     * 无版权自动跳过（R25 / R26）。
     *
     * 代理把所有音源都试完仍拿不到地址时回 204，播放器随即报错、进度停在 0:00。
     * 以前这里只弹一句「暂无版权」，用户必须手动切歌 —— 一张周杰伦歌单会一直卡在第一首。
     *
     * 现在的行为：
     *  - 记下这首不可播（本会话灰显，30 分钟后自动解禁）；
     *  - 立刻切下一首，不等用户点；
     *  - 连跳满 [COPYRIGHT_SKIP_LIMIT] 首就停下，给一次「搜其他版本」的出口，
     *    避免整张歌单都没版权时一路空转；
     *  - 提示合并：第 1 首报歌名，之后只报「已跳过 N 首」，不刷屏。
     */
    private fun autoSkipUnplayable() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val curId = item?.mediaId?.toLongOrNull() ?: _state.value.currentSong?.id ?: -1L
        val title = item?.mediaMetadata?.title?.toString().orEmpty()
            .ifBlank { _state.value.currentSong?.title.orEmpty() }
        val artist = item?.mediaMetadata?.artist?.toString().orEmpty()
            .ifBlank { _state.value.currentSong?.artist.orEmpty() }
        val now = android.os.SystemClock.elapsedRealtime()

        // 同一次失败可能连着触发两三个错误回调：1.2 秒内同一首只算一次，
        // 否则一次报错会连跳好几首，用户看到的是"播放器发疯乱跳"。
        if (curId == lastSkipSongId && now - lastSkipAtMs < 1200L) return
        lastSkipSongId = curId
        lastSkipAtMs = now

        // 过期项先清掉：只留最近 30 分钟判定过的，随机播放一整天也不会把表撑大
        unplayableAt.entries.removeAll { now - it.value > COPYRIGHT_TTL_MS }
        if (curId > 0L) unplayableAt[curId] = now
        skipStreak += 1

        _state.value = _state.value.withExtras {
            copy(
                copyrightSkipStreak = skipStreak,
                unplayableSongIds = unplayableAt.keys.toSet()
            )
        }

        // ── 有没有下一首：看「播放队列」，绝不能看播放器时间线 ──
        //
        // 时间线只灌了「当前曲 + 后面 12 首」这一窗口（fillTimelineTail 只补锚点**之后**），
        // 用户点到歌单靠后的位置时，窗口里可能真的只剩这一首。旧代码用
        // `c.mediaItemCount > 1` 判有没有下一首，于是误判成"到头了"，直接 pause + 提示，
        // 页面就永远停在 0:00 —— 这正是"周杰伦的歌点了一点反应都没有"的第二层原因。
        val queue = _state.value.queue
        val curIdx = queue.indexOfFirst { it.id == curId }.takeIf { it >= 0 }
            ?: _state.value.currentIndex
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (queue.size <= 1) {
            // 队列就这一首：没得跳，停住并给出路
            runCatching { c.pause() }
            showCopyrightTip("《$title》暂无版权", query)
            return
        }
        if (skipStreak > COPYRIGHT_SKIP_LIMIT) {
            // 整张歌单大面积没版权：别再无限跳了，停下给一次「搜其他版本」
            runCatching { c.pause() }
            // 6.7.0：**先把失败记忆清掉再停**。
            //
            // 连续几首失败几乎总是「那一刻音源/网络同时抽风」，而不是这三首歌
            // 真的都没版权。旧版停了就完了，缓存里全是"不可播"，用户再点还是无版权
            // ——看起来就是"整个歌单直接没了"。清掉之后，用户点一下就是重新解析，
            // 网络恢复了立刻能放。
            runCatching { com.jun.musiccloud.playback.AudioProxyServer.resetForForeground() }
            launchSafelyIo { runCatching { OnlineMusicApi.clearAllFailures() } }
            skipStreak = 0
            _state.value = _state.value.withExtras {
                copy(copyrightSkipStreak = 0, unplayableSongIds = emptySet())
            }
            showCopyrightTip("连续几首都取不到音频（音源或网络刚不稳），点播放再试一次", query)
            return
        }

        // 下一首：顺序往后，到底绕回第一首（12 首播完也要能接上）；
        // 已经判过不可播的直接跳过，不为一首注定失败的歌再等一次解析。
        var target = if (curIdx + 1 < queue.size) curIdx + 1 else 0
        var hops = 0
        while (hops < queue.size && unplayableAt.containsKey(queue[target].id)) {
            target = if (target + 1 < queue.size) target + 1 else 0
            hops++
        }
        if (hops >= queue.size) {
            runCatching { c.pause() }
            showCopyrightTip("这张歌单大部分暂时无版权", query)
            return
        }

        if (skipStreak == 1 && title.isNotBlank()) {
            notice("《$title》暂无版权，已跳过")
        } else {
            notice("已跳过 $skipStreak 首无版权歌曲")
        }

        launchSafely {
            // 给提示一点呼吸时间，也避免连续切歌把控制器刷爆
            delay(260)
            // 这 260ms 里用户可能已经自己点了别的歌：那份意图优先，别再把他拽走。
            // 判据用"控制器当前曲"而不是 state.currentSong —— 后者是派生值，
            // 索引还没跟上时会把我们自己误判成"用户切歌了"，于是该跳的时候不跳。
            val nowId = runCatching { controller?.currentMediaItem?.mediaId?.toLongOrNull() }.getOrNull()
            if (nowId != null && nowId != curId) return@launchSafely
            // 走 playAt（按 UI 队列）而不是 seekToNextMediaItem：
            //  · 窗口只有一首时 seekToNextMediaItem 是空操作，会原地卡死；
            //  · 单曲循环模式下它还会重播同一首，等于原地打转；
            //  · 到头时它不会绕回第一首，而 playAt 会。
            playAt(target, resetSkipStreak = false)
        }
    }

    /** 兜底条 + 出口：连跳满上限、或队列只剩这一首时，给用户「搜其他版本」的入口 */
    private fun showCopyrightTip(tip: String, query: String) {
        _state.value = _state.value.withExtras {
            copy(
                copyrightTip = tip,
                copyrightTipTick = copyrightTipTick + 1,
                copyrightQuery = query
            )
        }
    }

    /** 用户点掉 / 点了「搜其他版本」之后收起兜底条 */
    fun clearCopyrightTip() {
        _state.value = _state.value.withExtras { copy(copyrightTip = "", copyrightQuery = "") }
    }

    /**
     * 播放失败后的「重试」（6.3.1）。
     *
     * 原来错误提示上的重试只重跑"刷新首页/搜索"这类动作，跟播放本身无关——
     * 用户点了重试，那首歌还是同一处错误，于是"点重试也没用，还是网络错误"。
     * 这里补上真正的重试：把当前曲目的媒体源重新 prepare 一次。
     */
    fun retryPlayback() {
        val c = controller ?: return
        _state.value = _state.value.withExtras { copy(errorNotice = "") }
        // R27：重试 = 明确要求"再解析一次"。把这首歌的不可播记账、地址缓存、
        // 失败短缓存全部清掉，否则点了重试还是拿旧结论，用户看到的就是"重试没反应"。
        _state.value.currentSong?.songId?.takeIf { it.isNotBlank() }?.let { sid ->
            runCatching { com.jun.musiccloud.playback.AudioProxyServer.clearUnplayableFor(sid) }
            runCatching { com.jun.musiccloud.data.OnlineMusicApi.clearHardFail(sid) }
        }
        runCatching {
            if (c.mediaItemCount == 0) return
            c.prepare()
            c.play()
        }
    }

    /** 错误提示上的「重试」：重跑最近一次失败的动作 */    fun retryLastFailedAction() {
        _state.value = _state.value.withExtras { copy(errorNotice = "") }
        when (lastFailedAction) {
            FailedAction.LIBRARY -> refreshHome()
            FailedAction.NETEASE_HOME -> launchSafely { loadNeteaseHomeData() }
            FailedAction.SEARCH -> updateSearch(_state.value.searchQuery)
            FailedAction.NETEASE_MINE -> refreshNeteaseMyPlaylists(force = true)
            null -> Unit
        }
    }

    /** 最近一次失败的动作，「重试」键据此决定重跑什么 */
    private var lastFailedAction: FailedAction? = null

    private enum class FailedAction { LIBRARY, NETEASE_HOME, NETEASE_MINE, SEARCH }

    /** 省电动画开关：低端机默认打开，用户可在设置里改 */
    fun setPowerSavingAnim(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_POWER_SAVING_ANIM, enabled).apply()
        _state.value = _state.value.withExtras { copy(powerSavingAnim = enabled) }
    }

    // ==================== 听歌待办（空状态引导的落地场景） ====================

    /** 新增一条待办。空白内容直接丢弃，避免无意义的空条目占位。 */
    fun addTodo(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val next = listOf(
            ListenTodo(id = System.nanoTime(), text = t)
        ) + _state.value.todos
        _state.value = _state.value.withExtras { copy(todos = next) }
        ListenTodoStore.save(getApplication(), next)
    }

    /** 勾选 / 取消勾选 */
    fun toggleTodo(id: Long) {
        val next = _state.value.todos.map { if (it.id == id) it.copy(done = !it.done) else it }
        _state.value = _state.value.withExtras { copy(todos = next) }
        ListenTodoStore.save(getApplication(), next)
    }

    /** 删除一条（可撤销：待办删起来太容易了，误删一句就想不起来写过什么） */
    fun removeTodo(id: Long) {
        val removed = _state.value.todos.firstOrNull { it.id == id } ?: return
        val next = _state.value.todos.filter { it.id != id }
        requestUndo("已删除「${removed.text}」") {
            val back = (listOf(removed) + _state.value.todos).sortedByDescending { it.createdAt }
            _state.value = _state.value.withExtras { copy(todos = back) }
            ListenTodoStore.save(getApplication(), back)
        }
        _state.value = _state.value.withExtras { copy(todos = next) }
        ListenTodoStore.save(getApplication(), next)
    }

    /** 清空已完成 */
    fun clearDoneTodos() {
        val next = _state.value.todos.filter { !it.done }
        _state.value = _state.value.withExtras { copy(todos = next) }
        ListenTodoStore.save(getApplication(), next)
    }

    fun setSortOrder(order: SortOrder) {
        prefs.edit().putString(KEY_SORT_ORDER, order.name).apply()
        _state.value = _state.value.copy(sortOrder = order)
        applyFilters(resetToFirst = false)
    }
    fun setSortDirection(direction: SortDirection) {
        prefs.edit().putString(KEY_SORT_DIRECTION, direction.name).apply()
        _state.value = _state.value.copy(sortDirection = direction)
        applyFilters(resetToFirst = false)
    }

    // ==================== 设置 ====================

    fun toggleSettings(show: Boolean) {
        _state.value = _state.value.copy(showSettings = show)
    }

    fun setTheme(theme: JunTheme) {
        prefs.edit().putString(KEY_THEME, theme.name).apply()
        _state.value = _state.value.copy(theme = theme)
    }

    fun setMode(mode: JunMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _state.value = _state.value.copy(mode = mode)
    }

    /**
     * 刷新「意见反馈」入口上的小红点。
     *
     * 冷启动 / 每次回前台各调一次。失败保持原值（不清零）：
     * 断网时把红点抹掉，等于告诉用户"没人回你"，比不显示更糟。
     */
    fun refreshFeedbackUnread() {
        if (feedbackJob?.isActive == true) return
        feedbackJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val items = com.jun.musiccloud.data.FeedbackApi.fetchMine(
                userId = AccountStore.userId,
                deviceId = com.jun.musiccloud.data.Telemetry.deviceId(app)
            )
            val n = com.jun.musiccloud.data.FeedbackApi.unreadCount(
                items, com.jun.musiccloud.data.FeedbackApi.lastSeenAt(app)
            )
            if (n != _state.value.feedbackUnread) {
                _state.value = _state.value.copy(feedbackUnread = n)
            }
        }
    }

    /** 用户看过「我的反馈」了：把红点清掉，并把"已看到此刻"写进本地。 */
    fun markFeedbackRead() {
        val app = getApplication<Application>()
        com.jun.musiccloud.data.FeedbackApi.markSeen(app, System.currentTimeMillis())
        if (_state.value.feedbackUnread != 0) {
            _state.value = _state.value.copy(feedbackUnread = 0)
        }
    }

    /** 表面材质：实色 / 毛玻璃 / 液态玻璃 */
    fun setSurfaceStyle(style: JunSurfaceStyle) {
        prefs.edit()
            .putString(KEY_SURFACE_STYLE, style.name)
            // 只要用户亲手点过材质，就打上"自己选的"标记：
            // 以后任何"改默认值"的迁移都不能覆盖他，见 readSurfaceStyle()
            .putBoolean(KEY_SURFACE_USER, true)
            .apply()
        _state.value = _state.value.copy(surfaceStyle = style)
    }

    /** 自定义主题取色：主/次色（ARGB_8888）。调用方通常随后 setTheme(CUSTOM)。 */
    fun setCustomColors(primaryArgb: Long, secondaryArgb: Long) {
        prefs.edit()
            .putLong(KEY_CUSTOM_PRIMARY, primaryArgb)
            .putLong(KEY_CUSTOM_SECONDARY, secondaryArgb)
            .apply()
        _state.value = _state.value.withExtras { copy(customPrimaryArgb = primaryArgb, customSecondaryArgb = secondaryArgb) }
    }

    /**
     * 自定义背景图。传入 SAF 返回的 content URI；传 null 清除自定义背景、回退专辑封面。
     *
     * 6.2 起不再"只存 URI"。老做法有两个坑，都在这条链路上：
     *   1. content:// 能不能读取决于外部授权还在不在 —— 源文件被删、被清理软件回收、
     *      相册换提供方，都会让"背景"静静失效，而界面还显示"已使用自选图片"，
     *      用户看到的就是"我明明设过背景，怎么每次都要重设"；
     *   2. 换设备/重装时这个 URI 根本无意义，云同步带不走背景本体。
     * 现在选图当场在 filesDir/theme/background.jpg 落一份归一化副本（长边≤1600、JPEG q85），
     * 渲染永远读这份私有文件，另外把副本的 sha256 记成 custom_bg_asset 交给云同步取本体。
     * SAF 的持久读权限还是尝试申请一下，只为"重新导入原图"这类操作用得上。
     */
    fun setCustomBackground(uri: android.net.Uri?) {
        if (uri == null) {
            SettingsCloud.clearBackground(getApplication())
            _state.value = _state.value.withExtras { copy(customBackgroundUri = null) }
            return
        }
        val ctx = getApplication<android.app.Application>()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        // 私有副本存不下来时退回旧行为（直接记 URI），至少不改变用户已经习惯的表现
        val sha = SettingsCloud.installBackground(ctx, uri)
        // 6.8 修：之前这里会先读 prefs 里已存的旧 uri——一旦设过背景，re-upload 永远读到旧值，
        // 表现就是「必须先清除才能换图」。现在每次都按本次选中的新图落盘，覆盖即生效。
        // installBackground 已经把私有副本写到 KEY_BG_URI，这里渲染仍用用户选中的原 uri
        // （带持久读权限），云端同步逻辑不受影响。
        val renderUri = uri.toString()
            .also { prefs.edit().putString(KEY_CUSTOM_BG_URI, it).apply() }
        _state.value = _state.value.withExtras { copy(customBackgroundUri = renderUri) }
        if (sha != null) SettingsCloud.status.value = "背景图已存进 App 私有目录，可随设置一起同步"
    }

    /**
     * 云同步/配置档写回设置后调用：把"只读 prefs"的那些状态刷回界面。
     *
     * 音效链路（均衡器、淡入淡出、虚拟低音那批）里有一部分是在起播时才读 prefs 的，
     * 所以恢复后不需要重启进程也会生效；这里只补界面这一层。
     */
    fun reloadSettingsFromPrefs() {
        reloadSyncedSettings()
        _state.value = _state.value.withExtras {
            copy(customBackgroundUri = prefs.getString(KEY_CUSTOM_BG_URI, null))
        }
    }

    /**
     * 界面排布：纵向列表 / 横向卡片 / 堆叠卡片。
     *
     * 切到卡片模式时把队列位置对齐到一个有效下标：卡片模式没有"未选中"这个状态，
     * currentIndex 还是 -1 的话第一张卡会是空的（冷启动没点过歌就是这个情况）。
     */
    fun setLayout(layout: JunLayout) {
        prefs.edit().putString(KEY_LAYOUT, layout.name).apply()
        _state.value = _state.value.copy(layout = layout)
        if (layout.isDeck && _state.value.currentIndex < 0 && _state.value.queue.isNotEmpty()) {
            _state.value = _state.value.copy(currentIndex = 0)
        }
    }

    /** 唱片墙方向：纵向 / 横向。切换只重排磁贴，不打断播放。 */
    fun setWallHorizontal(horizontal: Boolean) {
        prefs.edit().putBoolean(KEY_WALL_HORIZONTAL, horizontal).apply()
        _state.value = _state.value.copy(wallHorizontal = horizontal)
    }

    /** 模糊浓度倍率，同时作用于毛玻璃半径与液态玻璃各层级半径 */
    fun setGlassBlur(scale: Float) {
        val v = scale.coerceIn(GLASS_BLUR_MIN, GLASS_BLUR_MAX)
        prefs.edit().putFloat(KEY_GLASS_BLUR, v).apply()
        _state.value = _state.value.copy(glassBlur = v)
    }

    /** 背景封面模糊半径（dp）。0 = 封面清晰可见，越大越糊 */
    fun setBgBlur(dp: Float) {
        val v = dp.coerceIn(BG_BLUR_MIN, BG_BLUR_MAX)
        prefs.edit().putFloat(KEY_BG_BLUR, v).apply()
        _state.value = _state.value.copy(bgBlur = v)
    }

    fun setRowSize(size: Int) {
        val s = size.coerceIn(0, 2)
        prefs.edit().putInt(KEY_ROW_SIZE, s).apply()
        _state.value = _state.value.copy(rowSize = s)
    }

    fun setShowRowCover(show: Boolean) {
        prefs.edit().putBoolean(KEY_ROW_COVER, show).apply()
        _state.value = _state.value.copy(showRowCover = show)
    }
    fun setShowRowArtist(show: Boolean) {
        prefs.edit().putBoolean(KEY_ROW_ARTIST, show).apply()
        _state.value = _state.value.copy(showRowArtist = show)
    }
    fun setShowRowPlaylist(show: Boolean) {
        prefs.edit().putBoolean(KEY_ROW_PLAYLIST, show).apply()
        _state.value = _state.value.copy(showRowPlaylist = show)
    }
    fun setSearchPersistent(on: Boolean) {
        prefs.edit().putBoolean(KEY_SEARCH_PERSISTENT, on).apply()
        _state.value = _state.value.copy(searchPersistent = on)
    }

    fun setAutoOpenPlayer(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_OPEN_PLAYER, on).apply()
        _state.value = _state.value.copy(autoOpenPlayer = on)
    }

    // 6.6.2：与其他应用同时播放
    fun setPlayWithOthers(on: Boolean) {
        prefs.edit().putBoolean(KEY_PLAY_WITH_OTHERS, on).apply()
        _state.value = _state.value.copy(playWithOthers = on)
        applyPlayWithOthers(on)
    }

    // 6.6.2：大播放器控件可见性
    fun setHideSpeed(v: Boolean) { prefs.edit().putBoolean(KEY_HIDE_SPEED, v).apply(); _state.value = _state.value.copy(hideSpeed = v) }
    fun setHideComment(v: Boolean) { prefs.edit().putBoolean(KEY_HIDE_COMMENT, v).apply(); _state.value = _state.value.copy(hideComment = v) }
    fun setHidePitch(v: Boolean) { prefs.edit().putBoolean(KEY_HIDE_PITCH, v).apply(); _state.value = _state.value.copy(hidePitch = v) }
    fun setHideQuality(v: Boolean) { prefs.edit().putBoolean(KEY_HIDE_QUALITY, v).apply(); _state.value = _state.value.copy(hideQuality = v) }
    fun setHideDownload(v: Boolean) { prefs.edit().putBoolean(KEY_HIDE_DOWNLOAD, v).apply(); _state.value = _state.value.copy(hideDownload = v) }
    fun setHideLike(v: Boolean) { prefs.edit().putBoolean(KEY_HIDE_LIKE, v).apply(); _state.value = _state.value.copy(hideLike = v) }
    fun setHideSimilar(v: Boolean) { prefs.edit().putBoolean(KEY_HIDE_SIMILAR, v).apply(); _state.value = _state.value.copy(hideSimilar = v) }
    fun setHideRecent(v: Boolean) { prefs.edit().putBoolean(KEY_HIDE_RECENT, v).apply(); _state.value = _state.value.copy(hideRecent = v) }
    fun setHideRepeat(v: Boolean) { prefs.edit().putBoolean(KEY_HIDE_REPEAT, v).apply(); _state.value = _state.value.copy(hideRepeat = v) }
    fun setHideSleep(v: Boolean) { prefs.edit().putBoolean(KEY_HIDE_SLEEP, v).apply(); _state.value = _state.value.copy(hideSleep = v) }

    // 6.6.2：自定义界面隐藏搜索/设置入口
    fun setHideSearchEntry(v: Boolean) { prefs.edit().putBoolean(KEY_HIDE_SEARCH_ENTRY, v).apply(); _state.value = _state.value.copy(hideSearchEntry = v) }
    fun setHideSettingsEntry(v: Boolean) { prefs.edit().putBoolean(KEY_HIDE_SETTINGS_ENTRY, v).apply(); _state.value = _state.value.copy(hideSettingsEntry = v) }

    /** 6.6.2：同步「与其他应用同时播放」开关到播放服务（重新申请/释放音频焦点） */
    private fun applyPlayWithOthers(on: Boolean) {
        runCatching {
            val ctx = getApplication<android.app.Application>()
            ctx.sendBroadcast(android.content.Intent(ACTION_PLAY_WITH_OTHERS).apply {
                setPackage(ctx.packageName)
                putExtra("on", on)
            })
        }
    }

    fun setShowLikedBadge(show: Boolean) {
        prefs.edit().putBoolean(KEY_LIKED_BADGE, show).apply()
        _state.value = _state.value.copy(showLikedBadge = show)
    }

    /**
     * 切换可视化频谱。关闭时 SpectrumAnalyzer 会跳过全部 FFT 计算，
     * 因此这里同步开关分析器而不是只改 UI 标志。
     */
    fun setVisualizerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VISUALIZER, enabled).apply()
        SpectrumAnalyzer.enabled = enabled
        _state.value = _state.value.withExtras { copy(visualizerEnabled = enabled) }
    }

/** 实验性：物理动效开关（频谱倾斜 + 封面摇动 合并，一个开关同时控制）。 */
    fun setPhysicsFx(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TILT_SPECTRUM, enabled).putBoolean(KEY_COVER_SHAKE, enabled).apply()
        _state.value = _state.value.withExtras { copy(tiltSpectrum = enabled, coverShake = enabled) }
    }

    /** 全屏歌词对齐：0=靠左 1=居中 2=靠右 */
    fun setLyricAlign(align: Int) {
        val a = align.coerceIn(0, 2)
        prefs.edit().putInt(KEY_LYRIC_ALIGN, a).apply()
        _state.value = _state.value.withExtras { copy(lyricAlign = a) }
    }
    /** 封面左下角单行歌词开关。 */
    fun setCoverLyric(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COVER_LYRIC, enabled).apply()
        _state.value = _state.value.withExtras { copy(coverLyric = enabled) }
    }
    /** 封面正上方空白区单行歌词开关。 */
    fun setCoverTopLyric(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COVER_TOP_LYRIC, enabled).apply()
        _state.value = _state.value.withExtras { copy(coverTopLyric = enabled) }
    }
    /** 全屏歌词伪逐字扫光开关。 */
    fun setKaraokeLyric(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KARAOKE_LYRIC, enabled).apply()
        _state.value = _state.value.withExtras { copy(karaokeLyric = enabled) }
    }
    /** 上栏静止时是否淡到完全透明。 */
    fun setTopBarAutoHide(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOPBAR_AUTOHIDE, enabled).apply()
        _state.value = _state.value.withExtras { copy(topBarAutoHide = enabled) }
    }

    /** 上栏静止多少秒后淡出。3~30 秒，默认 10。 */
    fun setTopBarHideSeconds(sec: Int) {
        val v = sec.coerceIn(3, 30)
        prefs.edit().putInt(KEY_TOPBAR_HIDE_SEC, v).apply()
        _state.value = _state.value.withExtras { copy(topBarHideSeconds = v) }
    }

    /** 设置全局圆角基准（dp），0=直角，44=接近胶囊 */
    fun setCornerBase(dp: Float) {
        val v = dp.coerceIn(0f, 44f)
        prefs.edit().putFloat(KEY_CORNER_BASE, v).apply()
        _state.value = _state.value.withExtras { copy(cornerBase = v) }
    }
    fun setFontScale(scale: Float) {
        val v = scale.coerceIn(0.85f, 1.30f)
        prefs.edit().putFloat(KEY_FONT_SCALE, v).apply()
        _state.value = _state.value.withExtras { copy(fontScale = v) }
    }

    fun setUiFont(font: Int) {
        val v = font.coerceIn(0, FONT_INDEX_MAX)
        // 全局唯一字体：界面与歌词舞台同步切换，设置一次整个 App 都换掉。
        // 老的 lyric_font 不再双写 —— 读的地方仍兼容（getUiFont 的兜底），
        // 顺手 remove 掉，免得一个已经失去意义的键跟着设置一起云同步。
        prefs.edit().putInt(KEY_UI_FONT, v).remove(KEY_LYRIC_FONT).apply()
        _state.value = _state.value.withExtras { copy(uiFont = v, lyricFont = v) }
    }

    fun setLyricFont(font: Int) {
        // 保留旧入口：歌词字体不再独立，等同全局字体切换。
        setUiFont(font)
    }

    fun setLyricFontSize(size: Float) {
        val v = size.coerceIn(14f, 32f)
        prefs.edit().putFloat(KEY_LYRIC_FONT_SIZE, v).apply()
        _state.value = _state.value.withExtras { copy(lyricFontSize = v) }
    }

    // ==================== 歌词校准（6.9.11） ====================
    /**
     * 原有的全局/单曲偏移（setLyricOffsetMs / setLyricSongOffsetMs）一直存在，
     * 但折叠在「进阶」分组里，用户根本发现不了 —— 这轮治根的关键一步是：
     * 把「歌词时间校准」从 advanced 折叠里提出来，放到歌词区主层。
     * 偏移消费端（全屏歌词已接；这轮补齐悬浮歌词与锁屏）见 FloatingLyric /
     * LockScreenActivity 里对 lyricOffsetGlobal 的读取。
     */
    /** 悬浮歌词 / 锁屏用：当前生效偏移（单曲记忆优先，回退全局），正 = 歌词延后 */
    fun lyricOffsetFor(songId: Long): Int {
        val song = readSongOffsets()[songId]
        if (song != null) return song
        return prefs.getInt(KEY_LYRIC_OFFSET, 0)
    }

    /** 全屏歌词舞台样式：0=流光 … 13=静止（与 LyricStageCatalog 对齐） */
    fun setLyricStage(stage: Int) {
        val v = stage.coerceIn(0, 13)
        prefs.edit().putInt(KEY_LYRIC_STAGE, v).apply()
        _state.value = _state.value.withExtras { copy(lyricStage = v, lyricTuning = readTuning(v), lyricStageProfile = readStageProfile(v)) }
        // 开了「按歌曲记住舞台」时，手动切一次就记到当前歌曲头上。
        if (_state.value.lyricStagePerSong) {
            _state.value.currentSong?.id?.let { saveSongStage(it, v) }
        }
    }

    /** 设置页基础/进阶模式 */
    fun setSettingsAdvanced(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SETTINGS_ADVANCED, enabled).apply()
        _state.value = _state.value.withExtras { copy(settingsAdvanced = enabled) }
    }

    /** 减少动效：粒子、节拍镜头、低频光晕与副歌氛围收掉，逐词点亮保留 */
    fun setReduceMotion(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REDUCE_MOTION, enabled).apply()
        _state.value = _state.value.withExtras { copy(reduceMotion = enabled) }
    }

    fun setLyricFrameStats(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LYRIC_FRAME_STATS, enabled).apply()
        _state.value = _state.value.withExtras { copy(lyricFrameStats = enabled) }
    }

    fun setLyricGuideSeen(seen: Boolean) {
        prefs.edit().putBoolean(KEY_LYRIC_GUIDE_SEEN, seen).apply()
        _state.value = _state.value.withExtras { copy(lyricGuideSeen = seen) }
    }

    /** 按歌曲记住舞台：开启时先把当前歌曲的舞台存下来 */
    fun setLyricStagePerSong(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STAGE_PER_SONG, enabled).apply()
        _state.value = _state.value.withExtras { copy(lyricStagePerSong = enabled) }
        if (enabled) {
            _state.value.currentSong?.id?.let { saveSongStage(it, _state.value.lyricStage) }
        }
    }

    fun toggleFavoriteStage(stage: Int) {
        val v = stage.coerceIn(0, 13)
        val set = _state.value.favoriteStages.toMutableSet()
        if (!set.add(v)) set.remove(v)
        prefs.edit().putStringSet(KEY_FAVORITE_STAGES, set.map { it.toString() }.toSet()).apply()
        _state.value = _state.value.withExtras { copy(favoriteStages = set.sorted()) }
    }

    private fun readFavoriteStages(): List<Int> =
        prefs.getStringSet(KEY_FAVORITE_STAGES, emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.filter { it in 0..13 }
            ?.sorted()
            ?: emptyList()

    private fun readSongStages(): MutableMap<Long, Int> {
        val out = HashMap<Long, Int>()
        prefs.getStringSet(KEY_SONG_STAGES, emptySet()).orEmpty().forEach { raw ->
            val parts = raw.split(':')
            if (parts.size == 2) {
                val id = parts[0].toLongOrNull() ?: return@forEach
                val st = parts[1].toIntOrNull() ?: return@forEach
                if (st in 0..13) out[id] = st
            }
        }
        return out
    }

    private fun saveSongStage(songId: Long, stage: Int) {
        if (songId <= 0L) return
        val map = readSongStages()
        map[songId] = stage.coerceIn(0, 13)
        prefs.edit().putStringSet(KEY_SONG_STAGES, map.map { "${it.key}:${it.value}" }.toSet()).apply()
    }

    /** 换歌时恢复这首歌上次用的舞台（只在「按歌曲记住舞台」开启时生效） */
    private fun applyStageForSong(songId: Long) {
        if (!_state.value.lyricStagePerSong || songId <= 0L) return
        val stage = readSongStages()[songId] ?: return
        if (stage == _state.value.lyricStage) return
        prefs.edit().putInt(KEY_LYRIC_STAGE, stage).apply()
        _state.value = _state.value.withExtras { copy(lyricStage = stage, lyricTuning = readTuning(stage), lyricStageProfile = readStageProfile(stage)) }
    }

    /** 当前舞台专属调校：field 0=字号 1=动势 2=辉光 3=形态 */
    fun setLyricStageProfile(field: Int, value: Int) {
        val stage = _state.value.lyricStage.coerceIn(0, 13)
        val cur = _state.value.lyricStageProfile
        val next = when (field) {
            0 -> cur.copy(font = value.coerceIn(85, 125))
            1 -> cur.copy(motion = value.coerceIn(0, 150))
            2 -> cur.copy(glow = value.coerceIn(0, 150))
            3 -> cur.copy(look = value.coerceIn(0, 3))
            else -> return
        }
        prefs.edit().putString(stageProfileKey(stage), next.encode()).apply()
        _state.value = _state.value.withExtras { copy(lyricStageProfile = next) }
    }

    fun resetLyricStageProfile() {
        val stage = _state.value.lyricStage.coerceIn(0, 13)
        prefs.edit().putString(stageProfileKey(stage), StageProfile.Default.encode()).apply()
        _state.value = _state.value.withExtras { copy(lyricStageProfile = StageProfile.Default) }
    }

    /** 把当前舞台的专属调校复制到全部 14 个舞台。 */
    fun applyLyricStageProfileToAll() {
        val p = _state.value.lyricStageProfile
        val editor = prefs.edit()
        for (stage in 0..13) editor.putString(stageProfileKey(stage), p.encode())
        editor.apply()
    }

    fun setLyricFocus(value: Float) {
        val v = value.coerceIn(0.24f, 0.62f)
        prefs.edit().putFloat(KEY_LYRIC_FOCUS, v).apply()
        _state.value = _state.value.withExtras { copy(lyricFocus = v) }
    }

    fun setLyricBeatCamera(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LYRIC_BEAT_CAMERA, enabled).apply()
        _state.value = _state.value.withExtras { copy(lyricBeatCamera = enabled) }
    }

    fun setLyricBeatCameraStrength(value: Float) {
        val v = value.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_LYRIC_BEAT_CAMERA_STRENGTH, v).apply()
        _state.value = _state.value.withExtras { copy(lyricBeatCameraStrength = v) }
    }

    fun setLyricLongFormMode(mode: Int) {
        val v = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_LYRIC_LONG_FORM, v).apply()
        _state.value = _state.value.withExtras { copy(lyricLongFormMode = v) }
    }

    fun setLyricVideoUri(uri: String?) {
        val v = uri.orEmpty()
        prefs.edit().putString(KEY_LYRIC_VIDEO_URI, v).apply()
        _state.value = _state.value.withExtras { copy(lyricVideoUri = v) }
    }

    fun setLyricVideoAlpha(value: Float) {
        val v = value.coerceIn(0f, 0.9f)
        prefs.edit().putFloat(KEY_LYRIC_VIDEO_ALPHA, v).apply()
        _state.value = _state.value.withExtras { copy(lyricVideoAlpha = v) }
    }

    fun setLyricSemanticWrap(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LYRIC_SEMANTIC_WRAP, enabled).apply()
        _state.value = _state.value.withExtras { copy(lyricSemanticWrap = enabled) }
    }

    /** 歌词运动风格：0=扫光 1=弹跳 2=玻璃 3=漂浮 4=轻快 5=流光 6=故障 */
    fun setLyricMotion(style: Int) {
        val v = style.coerceIn(0, 6)
        prefs.edit().putInt(KEY_LYRIC_MOTION, v).apply()
        _state.value = _state.value.withExtras { copy(lyricMotion = v) }
    }

    /** 歌词显示模式：0=单行 1=双行 2=三行 3=影院 4=自定义 */
    fun setLyricDisplayMode(mode: Int) {
        val v = mode.coerceIn(0, 4)
        prefs.edit().putInt(KEY_LYRIC_DISPLAY_MODE, v).apply()
        _state.value = _state.value.withExtras { copy(lyricDisplayMode = v) }
    }

    /** 自定义显示模式的上下文行数 */
    fun setLyricContext(count: Int) {
        val v = count.coerceIn(0, 6)
        prefs.edit().putInt(KEY_LYRIC_CONTEXT, v).apply()
        _state.value = _state.value.withExtras { copy(lyricContext = v) }
    }

    /** 歌词渲染品质：0=自动 1=流畅 2=均衡 3=精致 */
    fun setLyricQuality(level: Int) {
        val v = level.coerceIn(0, 3)
        prefs.edit().putInt(KEY_LYRIC_QUALITY, v).apply()
        _state.value = _state.value.withExtras { copy(lyricQuality = v) }
    }

    fun setLyricBeatFx(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LYRIC_BEAT_FX, enabled).apply()
        _state.value = _state.value.withExtras { copy(lyricBeatFx = enabled) }
    }

    fun setLyricParticles(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LYRIC_PARTICLES, enabled).apply()
        _state.value = _state.value.withExtras { copy(lyricParticles = enabled) }
    }

    fun setLyricBassGlow(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LYRIC_BASS_GLOW, enabled).apply()
        _state.value = _state.value.withExtras { copy(lyricBassGlow = enabled) }
    }

    // ---- 2.8 歌词增强 ----

    fun setLyricChorusFx(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LYRIC_CHORUS_FX, enabled).apply()
        _state.value = _state.value.withExtras { copy(lyricChorusFx = enabled) }
    }

    fun setLyricChorusBoost(value: Float) {
        val v = value.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_LYRIC_CHORUS_BOOST, v).apply()
        _state.value = _state.value.withExtras { copy(lyricChorusBoost = v) }
    }

    fun setLyricCoverTint(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LYRIC_COVER_TINT, enabled).apply()
        _state.value = _state.value.withExtras { copy(lyricCoverTint = enabled) }
    }

    fun setLyricTintStrength(value: Float) {
        val v = value.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_LYRIC_TINT_STRENGTH, v).apply()
        _state.value = _state.value.withExtras { copy(lyricTintStrength = v) }
    }

    fun setLyricOffsetMs(value: Int) {
        val v = value.coerceIn(-2000, 2000)
        prefs.edit().putInt(KEY_LYRIC_OFFSET, v).apply()
        _state.value = _state.value.withExtras { copy(lyricOffsetMs = v) }
    }

    fun nudgeLyricOffset(delta: Int) = setLyricOffsetMs(_state.value.lyricOffsetMs + delta)

    fun setLyricSongOffsetMs(songId: Long, value: Int) {
        if (songId <= 0L) return
        val v = value.coerceIn(-2000, 2000)
        val map = _state.value.lyricSongOffsets.toMutableMap()
        if (v == 0) map.remove(songId) else map[songId] = v
        writeSongOffsets(map)
        _state.value = _state.value.withExtras { copy(lyricSongOffsets = map) }
    }

    fun nudgeLyricSongOffset(delta: Int) {
        val songId = _state.value.currentSong?.id ?: return
        val cur = _state.value.lyricSongOffsets[songId] ?: 0
        setLyricSongOffsetMs(songId, cur + delta)
    }

    fun setLyricStaffCard(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LYRIC_STAFF_CARD, enabled).apply()
        _state.value = _state.value.withExtras { copy(lyricStaffCard = enabled) }
    }

    fun setLyricLineSpacing(value: Float) {
        val v = value.coerceIn(1f, 1.8f)
        prefs.edit().putFloat(KEY_LYRIC_LINE_SPACING, v).apply()
        _state.value = _state.value.withExtras { copy(lyricLineSpacing = v) }
    }

    fun setLyricLetterSpacing(value: Float) {
        val v = value.coerceIn(-0.04f, 0.12f)
        prefs.edit().putFloat(KEY_LYRIC_LETTER_SPACING, v).apply()
        _state.value = _state.value.withExtras { copy(lyricLetterSpacing = v) }
    }

    fun setLyricWeight(value: Int) {
        val v = value.coerceIn(0, 2)
        prefs.edit().putInt(KEY_LYRIC_WEIGHT, v).apply()
        _state.value = _state.value.withExtras { copy(lyricWeight = v) }
    }

    fun setLyricOutline(value: Float) {
        val v = value.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_LYRIC_OUTLINE, v).apply()
        _state.value = _state.value.withExtras { copy(lyricOutline = v) }
    }

    fun setLyricTexture(value: Int) {
        val v = value.coerceIn(0, 3)
        prefs.edit().putInt(KEY_LYRIC_TEXTURE, v).apply()
        _state.value = _state.value.withExtras { copy(lyricTexture = v) }
    }

    fun setLyricGlowStyle(value: Int) {
        val v = value.coerceIn(0, 3)
        prefs.edit().putInt(KEY_LYRIC_GLOW_STYLE, v).apply()
        _state.value = _state.value.withExtras { copy(lyricGlowStyle = v) }
    }

    fun setLyricGlowStrength(value: Float) {
        val v = value.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_LYRIC_GLOW_STRENGTH, v).apply()
        _state.value = _state.value.withExtras { copy(lyricGlowStrength = v) }
    }

    fun setLyricCoverDepth(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LYRIC_COVER_DEPTH, enabled).apply()
        _state.value = _state.value.withExtras { copy(lyricCoverDepth = enabled) }
    }

    /** 当前舞台调参：axis 0=密度 1=速度 2=发光 3=镜头 */
    fun setLyricTuning(axis: Int, value: Int) {
        if (axis !in 0..3) return
        val stage = _state.value.lyricStage.coerceIn(0, 13)
        val next = _state.value.lyricTuning.toMutableList()
        next[axis] = value.coerceIn(0, 100)
        prefs.edit().putString(tuningKey(stage), next.joinToString(",")).apply()
        _state.value = _state.value.withExtras { copy(lyricTuning = next) }
    }

    fun resetLyricTuning() {
        val stage = _state.value.lyricStage.coerceIn(0, 13)
        val next = listOf(50, 50, 50, 50)
        prefs.edit().putString(tuningKey(stage), next.joinToString(",")).apply()
        _state.value = _state.value.withExtras { copy(lyricTuning = next) }
    }

    /** 把当前舞台的调参复制到全部 14 个舞台。 */
    fun applyLyricTuningToAll() {
        val next = _state.value.lyricTuning
        val editor = prefs.edit()
        for (stage in 0..13) editor.putString(tuningKey(stage), next.joinToString(","))
        editor.apply()
    }

    internal fun applyLyricPreset(preset: LyricPreset) {
        setLyricStage(preset.stage)
        setLyricMotion(preset.motion)
        setLyricDisplayMode(preset.displayMode)
        setLyricContext(preset.context)
        setLyricQuality(preset.quality)
        setLyricChorusFx(preset.chorus)
        setLyricGlowStyle(preset.glowStyle)
        setLyricCoverTint(preset.coverTint)
        setLyricParticles(preset.particles)
        setLyricBassGlow(preset.bassGlow)
        setLyricBeatFx(preset.beatFx)
        preset.tuning.forEachIndexed { axis, value -> setLyricTuning(axis, value) }
        preset.stageProfile.forEachIndexed { field, value -> setLyricStageProfile(field, value) }
    }

    internal fun currentLyricPreset(name: String = "我的预设"): LyricPreset {
        val st = _state.value
        return LyricPreset(
            name = name,
            stage = st.lyricStage,
            motion = st.lyricMotion,
            displayMode = st.lyricDisplayMode,
            context = st.lyricContext,
            quality = st.lyricQuality,
            chorus = st.lyricChorusFx,
            glowStyle = st.lyricGlowStyle,
            coverTint = st.lyricCoverTint,
            particles = st.lyricParticles,
            bassGlow = st.lyricBassGlow,
            beatFx = st.lyricBeatFx,
            tuning = st.lyricTuning,
            stageProfile = listOf(
                st.lyricStageProfile.font,
                st.lyricStageProfile.motion,
                st.lyricStageProfile.glow,
                st.lyricStageProfile.look
            )
        )
    }

    fun saveLyricPresetSlot(slot: Int) {
        if (slot !in 0..7) return
        prefs.edit().putString(presetKey(slot), LyricPresetCatalog.encode(currentLyricPreset())).apply()
    }

    internal fun loadLyricPresetSlot(slot: Int): LyricPreset? =
        LyricPresetCatalog.decode(prefs.getString(presetKey(slot), null))

    fun exportLyricPreset(): String = LyricPresetCatalog.encode(currentLyricPreset())

    fun importLyricPreset(raw: String): Boolean {
        val preset = LyricPresetCatalog.decode(raw.trim()) ?: return false
        applyLyricPreset(preset)
        return true
    }

    private fun tuningKey(stage: Int) = "lyric_tune_$stage"

    private fun stageProfileKey(stage: Int) = "lyric_stage_profile_$stage"

    private fun readStageProfile(stage: Int): StageProfile {
        val p = StageProfile.decode(prefs.getString(stageProfileKey(stage), null))
        // R13 老机适配：把最重的几个「舞台形态」换回该形态最省的那一档，动势/辉光收上限。
        // 判定与开关全在 DevicePerformance（legacy_fx_heavy_stage / legacy_safe_mode），
        // 新机（safeMode=false）clampStageProfile 原样返回，画面一字不变。
        // 之所以在这里夹而不是在 UI 里夹：14 个歌词舞台的绘制路径都从这个函数取参数，
        // 夹在源头 = 一处生效；散在界面上必然漏。
        val c = com.jun.musiccloud.ui.theme.DevicePerformance
            .clampStageProfile(stage, p.font, p.motion, p.glow, p.look)
        return p.copy(font = c[0], motion = c[1], glow = c[2], look = c[3])
    }

    private fun readTuning(stage: Int): List<Int> {
        val raw = prefs.getString(tuningKey(stage), null) ?: return listOf(50, 50, 50, 50)
        val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 4) return listOf(50, 50, 50, 50)
        return parts.map { it.coerceIn(0, 100) }
    }

    private fun presetKey(slot: Int) = "lyric_preset_$slot"

    private fun readSongOffsets(): Map<Long, Int> {
        val raw = prefs.getString(SONG_OFFSETS_KEY, null) ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        val out = HashMap<Long, Int>()
        for (part in raw.split(';')) {
            val kv = part.split('=')
            if (kv.size != 2) continue
            val id = kv[0].toLongOrNull() ?: continue
            val value = kv[1].toIntOrNull() ?: continue
            if (value != 0) out[id] = value.coerceIn(-2000, 2000)
        }
        return out
    }

    private fun writeSongOffsets(map: Map<Long, Int>) {
        val raw = map.entries.joinToString(";") { "${it.key}=${it.value}" }
        prefs.edit().putString(SONG_OFFSETS_KEY, raw).apply()
    }

    /** 翻译轨显示开关 */
    fun setLyricTranslation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LYRIC_TRANSLATION, enabled).apply()
        _state.value = _state.value.withExtras { copy(lyricTranslation = enabled) }
    }

    /** 音译/罗马音轨显示开关 */
    fun setLyricRomanization(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LYRIC_ROMANIZATION, enabled).apply()
        _state.value = _state.value.withExtras { copy(lyricRomanization = enabled) }
    }

    // ==================== 音效预设链 ====================

    /**
     * 一键切换整套音效（EQ + 低音 + 声场 + 限幅）。
     *
     * R12：切完一定要**报出这一档的名字**（底部小胶囊「音效 · 黑胶」），
     * 而且不管这台机器上它实际生不生效都要报——用户要的是"上面写着什么音效，
     * 提示就是什么音效"。生效与否不在提示里解释，界面里该显示的状态照常显示。
     */
    fun setFxPreset(index: Int) {
        val preset = FxPreset.of(index) ?: return
        prefs.edit().putInt(KEY_FX_PRESET, index).apply()
        applyFxPreset(preset)
        _state.value = _state.value.withExtras {
            copy(
                fxPreset = index,
                fxFlash = "音效 · ${preset.label}",
                fxFlashTick = fxFlashTick + 1
            )
        }
    }

    private fun applyFxPreset(preset: FxPreset) {
        EqualizerController.applyCustomLevels(preset.gainsMb.toList())
        EqualizerController.setLoudness(preset.loudness)
        setVirtualBass(preset.virtualBass)
        if (preset.virtualBass) setVirtualBassStrength(preset.bassStrength)
        setRangeEnhancer(preset.rangeEnhancer)
        if (preset.rangeEnhancer) setRangeEnhancerStrength(preset.rangeStrength)
        setVirtualSurround(preset.surround)
        if (preset.surround) setVirtualSurroundStrength(preset.surroundStrength)
        setSpatialWide(preset.spatial)
        if (preset.spatial) setSpatialWideStrength(preset.spatialStrength)
        setSafeLimiter(preset.limiter)
    }

    // ==================== Automix ====================

    fun setAutomixEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOMIX, enabled).apply()
        _state.value = _state.value.withExtras { copy(automixEnabled = enabled) }
    }

    /** 0=智能淡切 1=柔和长淡 2=直接切换 */
    fun setAutomixStyle(style: Int) {
        val v = style.coerceIn(0, 2)
        prefs.edit().putInt(KEY_AUTOMIX_STYLE, v).apply()
        _state.value = _state.value.withExtras { copy(automixStyle = v) }
    }

    // ==================== 播放音质 / 倍速（入口在播放页，5.2） ====================

    /**
     * 播放页那枚音质按钮点一下：自动 → 标准 → 极高 → 无损 → Hi-Res → 自动。
     *
     * 按钮上的字就是刚选中的档位名（标准音质 / 无损音质 / …），点一下就跟着变，
     * 不再显示 MP3、在线、解析中这类和档位无关的东西。这里不只改个设置值：
     *  1. 写盘 + 刷新进程快照（音频代理在 socket 线程只读快照，不碰 SharedPreferences）；
     *  2. 作废代理里的旧解析结果，否则预取过的那几首还会按老档位地址播；
     *  3. 让当前这首歌按新档位重新解析并 seek 回原进度续播（代价是一次短卡顿，用户明确要了）；
     *  4. 底部那枚小胶囊说清真相：先「正在为你接入无损音质…」，解析回来再改成
     *     「无损音质 · 已为你接入」或「Hi-Res 暂不可用 · 这首最高无损」
     *     ——高档位拿不到时不粉饰，但也绝不出现"内置链路""解析"这类内部词（6.1）。
     */
    fun cycleAudioQuality() {
        val all = AudioQuality.entries
        // 以界面上那枚按钮显示的档位为准往下轮：用户看到的和点出来的一定接得上，
        // 不会出现"写着标准、点一下跳到别的档"。
        val cur = _state.value.audioQuality
        val next = all[(cur.ordinal + 1) % all.size]
        AudioQualityStore.save(prefs, next)
        AudioProxyServer.clearResolved()
        // 旧解析结果全部作废：预取过的那几首要按新档位重新解析，否则界面写无损、
        // 实际还在播上一档 320k 的地址。徽章本身只显示档位名，真话在闪条里说。
        _state.value = _state.value.withExtras { copy(audioQuality = next) }

        val nowSong = _state.value.currentSong
        val songId = nowSong?.songId.orEmpty()
        if (songId.isNotBlank()) {
            // 在线曲目：先在后台把新档位解析出来，**成功了才切**
            flashSongId = songId
            _state.value = _state.value.withExtras {
                copy(
                    qualityFlash = "正在为你接入${next.badgeLabel}…",
                    qualityFlashTick = qualityFlashTick + 1
                )
            }
            // 6.3.2：解析可能要 1~3 秒（无损链路长），闪条按 9 秒给足余量。
            scheduleFlashClear(9_000L)
            launchSafely {
                // 6.3.2 关键修复：先解析、后切换。
                //
                // 旧写法是「立刻让播放器换源」，换源后代理才开始解析高档位——
                // 而播放器的读超时只有 8 秒，高档位链路（GD 无损 6 秒超时 +
                // 官方接口 + 备用源）一串下来必然超时，用户看到的就是
                // 「一换音质就网络断了」。现在解析在后台做完、结果直接塞进
                // 代理缓存，换源那一刻是内存命中，毫秒级出数据，原曲全程不停。
                val hit = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        OnlineMusicApi.resolveAudio(
                            songId,
                            nowSong?.title.orEmpty(),
                            nowSong?.artist.orEmpty(),
                            next
                        )
                    }.getOrNull()
                }
                if (hit == null) {
                    // 拿不到就**保持原音质继续播**，绝不为了换档把正在听的歌打断
                    _state.value = _state.value.withExtras {
                        copy(
                            qualityFlash = "这首拿不到${next.badgeLabel}，已保持原音质",
                            qualityFlashTick = qualityFlashTick + 1
                        )
                    }
                    scheduleFlashClear(3_400L)
                } else {
                    AudioProxyServer.seedResolved(songId, hit)
                    runCatching { MusicService.instance?.reloadCurrentForQuality() }
                }
            }
        } else {
            // 本地曲目：档位与它无关，给一句实话就好
            flashSongId = ""
            _state.value = _state.value.withExtras {
                copy(
                    qualityFlash = "本地文件 · 按原曲音质播放",
                    qualityFlashTick = qualityFlashTick + 1
                )
            }
            scheduleFlashClear(3_400L)
        }
    }

    /** 闪条自动收回；解析回来时会把超时改短，所以是一个可取消的 Job */
    private fun scheduleFlashClear(ms: Long) {
        flashJob?.cancel()
        flashJob = launchSafely {
            delay(ms)
            flashSongId = ""
            _state.value = _state.value.withExtras { copy(qualityFlash = "") }
        }
    }

    /** 用户手动关掉闪条（点一下闪条本身） */
    fun dismissQualityFlash() {
        flashJob?.cancel()
        flashSongId = ""
        _state.value = _state.value.withExtras { copy(qualityFlash = "") }
    }

    /**
     * 倍速（基点，100 = 1.0x，75–200）；变速不变调。
     * 播放页给的是「一排档位自己点」，不是循环，所以这里按具体值设置。
     */
    fun setPlaybackSpeedBp(bp: Int) {
        val v = bp.coerceIn(MIN_SPEED_BP, MAX_SPEED_BP)
        prefs.edit().putInt(MusicService.KEY_PLAYBACK_SPEED_BP, v).apply()
        _state.value = _state.value.withExtras { copy(playbackSpeedBp = v) }
        runCatching {
            controller?.playbackParameters =
                androidx.media3.common.PlaybackParameters(v / 100f, 1f)
        }
    }

    // ==================== 在线流式缓存（无开关，只报「写满」，5.2） ====================

    // 缓存没有开关也没有容量选择器：默认开、256MB、满了 LRU 自动覆盖旧歌。
    // 设置页那套按用户要求撤掉了，代价是它变成了一个看不见的行为，
    // 所以这里保留一条「已经写满」的提示和一个清空入口，让隐性行为至少可见可控。

    /** 统计缓存占用（磁盘遍历，放 IO 线程），回到主线程刷新文案 */
    fun refreshStreamCacheUsage() {
        val app = getApplication<Application>()
        launchSafely {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                PlaybackCache.usageBytes(app)
            }
            _state.value = _state.value.withExtras { copy(streamCacheBytes = bytes) }
        }
    }

    /** 清空在线流式缓存；正在播的曲目靠 FLAG_IGNORE_CACHE_ON_ERROR 退化成直连，不断音 */
    fun clearStreamCache() {
        val app = getApplication<Application>()
        _state.value = _state.value.withExtras { copy(streamCacheBytes = 0L, cacheNotice = "") }
        runCatching {
            PlaybackCache.consumeNotice()
            PlaybackCache.clearAsync(app) { refreshStreamCacheUsage() }
        }
    }

    /**
     * 设置在线播放缓存上限（6.3.1 恢复的入口）。
     *
     * [mb] 传 [PlaybackCache.AUTO]（0）表示自动按剩余空间算。
     * 改档位必须 release 现有 SimpleCache：evictor 的容量是构造期定死的，
     * 不重建的话新档位不会生效（用户会以为"选了 2G 还是 256MB"）。
     */
    fun setStreamCacheLimit(mb: Int) {
        val app = getApplication<Application>()
        runCatching {
            app.getSharedPreferences(PlaybackCache.PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putInt(PlaybackCache.KEY_LIMIT_MB, mb).apply()
        }
        // 6.3.2：这里**不能**直接 PlaybackCache.release()。
        //
        // 旧写法就是在这一行把缓存关掉的，结果播放器（构造时绑死了数据源工厂）
        // 手里还握着已释放的缓存对象，下一次读就抛异常，界面报「网络断了」，
        // 而且整个进程都恢复不了，必须杀 App 重开——用户现场就是这样。
        //
        // 现在交给播放服务做「暂停 → 重建 → 换工厂 → 回原进度 → 继续播」，
        // 全程不到一秒，不报错、不断歌。
        runCatching { MusicService.instance?.rebuildCachePipe() }
            .onFailure {
                // 服务没起来（从未播放过）时不会有正在用的缓存，直接放掉也不影响
                runCatching { PlaybackCache.release() }
            }
        notice(
            if (mb == PlaybackCache.AUTO) "缓存改为自动，当前约 ${PlaybackCache.autoLimitMb(app)} MB"
            else "缓存上限已改为 $mb，已即时生效"
        )
        refreshStreamCacheUsage()
    }

    /** 关掉「写满」提示（只收起文案，缓存文件本身不动） */    fun dismissCacheNotice() {
        runCatching { PlaybackCache.consumeNotice() }
        _state.value = _state.value.withExtras { copy(cacheNotice = "") }
    }

    // ==================== 本地 SAF 曲库 ====================

    fun setLocalFolder(uri: android.net.Uri?) {
        if (uri == null) {
            prefs.edit().remove(KEY_LOCAL_FOLDER).apply()
            _state.value = _state.value.withExtras { copy(localFolder = "", localSongCount = 0, localScanNotice = "已关闭本地曲库") }
            launchSafely {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    LocalLibrary.putCached(getApplication(), emptyList())
                }
            }
            reloadLibraryWithLocal(emptyList())
            return
        }
        LocalLibrary.persistPermission(getApplication(), uri)
        prefs.edit().putString(KEY_LOCAL_FOLDER, uri.toString()).apply()
        _state.value = _state.value.withExtras { copy(localFolder = uri.toString()) }
        rescanLocal()
    }

    fun rescanLocal() {
        val raw = _state.value.localFolder
        if (raw.isBlank()) return
        if (_state.value.localScanning) return
        val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull() ?: return
        _state.value = _state.value.withExtras { copy(localScanning = true, localScanNotice = "正在扫描…") }
        val cover = _state.value.localFolderCover
        val ignore = _state.value.localIgnore
        val alias = _state.value.localAliasRules
        val splitArtists = _state.value.localSplitArtists
        launchSafely {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    LocalLibrary.scan(getApplication(), uri, cover, ignore, alias, splitArtists)
                }.getOrElse {
                    LocalLibrary.ScanResult(
                        LocalLibrary.cached(getApplication()),
                        "扫描失败：${it.message ?: "无法读取文件夹"}",
                        0
                    )
                }
            }
            _state.value = _state.value.withExtras { copy(localScanning = false, localSongCount = result.songs.size, localScanNotice = result.notice) }
            reloadLibraryWithLocal(result.songs)
        }
    }

    private fun reloadLibraryWithLocal(local: List<Song>) {
        val s = _state.value
        val online = s.allSongs.filterNot { it.folderPath.startsWith("local:") }
        val merged = mergeLocal(online, local)
        _state.value = s.copy(allSongs = merged, folders = MusicRepository.buildFolders(merged)).withExtras { copy(localSongCount = local.size) }
        reconcileLikedSongs(merged)
        applyFilters(resetToFirst = false)
        updateRecommendations()
    }

    fun setLocalFolderCover(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCAL_COVER, enabled).apply()
        _state.value = _state.value.withExtras { copy(localFolderCover = enabled) }
    }

    fun setLocalIgnore(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCAL_IGNORE, enabled).apply()
        _state.value = _state.value.withExtras { copy(localIgnore = enabled) }
    }

    fun setLocalSplitArtists(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCAL_SPLIT, enabled).apply()
        _state.value = _state.value.withExtras { copy(localSplitArtists = enabled) }
    }

    /** 歌手/专辑别名规则（每行「原名=新名」），保存后点「重新扫描」生效 */
    fun setLocalAliasRules(rules: String) {
        prefs.edit().putString(KEY_LOCAL_ALIAS, rules).apply()
        _state.value = _state.value.withExtras { copy(localAliasRules = rules) }
    }

    // ==================== AI 情绪主题 ====================

    fun generateAiTheme(mood: String = "") {
        if (_state.value.aiThemeBusy) return
        _state.value = _state.value.withExtras { copy(aiThemeBusy = true) }
        launchSafely {
            val theme = AiThemeApi.generate(_state.value.currentSong, mood)
            if (theme != null) {
                setTheme(com.jun.musiccloud.ui.theme.JunTheme.CUSTOM)
                setCustomColors(theme.primaryArgb, theme.secondaryArgb)
                prefs.edit().putString(KEY_AI_THEME_NAME, theme.name).apply()
                _state.value = _state.value.withExtras { copy(aiThemeBusy = false, aiThemeName = theme.name) }
            } else {
                _state.value = _state.value.withExtras { copy(aiThemeBusy = false) }
            }
        }
    }

    // ==================== 设置云同步 ====================
    //
    // 6.2 起这一层只是"按钮到编排层的接线"，真正的判定与网络都在 SettingsCloud：
    // 自动同步（登录后拉、改动后推）、冲突处理、背景图本体、配置档。
    // 老实现里这两段直接把快照 PUT / GET 后 apply，既没有"本机是不是动过"的判断，
    // 也没有拿服务端返回的 updated_at —— 等于谁最后点按钮谁赢，另一台设备的调校会被无声冲掉。

    /** 把编排层的状态行镜像进 UI 状态，设置页那行小字与冲突条都读这个 */
    private fun syncSettingsStatus() {
        _state.value = _state.value.withExtras { copy(cloudSyncStatus = SettingsCloud.status.value) }
    }

    fun uploadSettings() {
        _state.value = _state.value.withExtras { copy(cloudSyncStatus = "正在上传…") }
        launchSafelyIo {
            SettingsCloud.uploadNow(getApplication())
            syncSettingsStatus()
        }
    }

    fun downloadSettings() {
        _state.value = _state.value.withExtras { copy(cloudSyncStatus = "正在读取云端…") }
        launchSafelyIo {
            val n = SettingsCloud.downloadNow(getApplication())
            if (n > 0) reloadSettingsFromPrefs()
            syncSettingsStatus()
        }
    }

    /** 冲突二选一：`useCloud=true` 用云端覆盖本机，false 把本机推上去覆盖云端 */
    fun resolveSettingsConflict(useCloud: Boolean) {
        _state.value = _state.value.withExtras { copy(cloudSyncStatus = "处理中…") }
        launchSafelyIo {
            val n = SettingsCloud.resolve(getApplication(), useCloud)
            if (useCloud && n > 0) reloadSettingsFromPrefs()
            syncSettingsStatus()
        }
    }

    fun dismissSettingsConflict() {
        SettingsCloud.dismissConflict(getApplication())
        syncSettingsStatus()
    }

    /** 回到前台/登录后跑一次：脏了就自动上传，云端更新了就走恢复或冲突分支 */
    fun tickSettingsSync() {
        launchSafelyIo {
            SettingsCloud.tick(getApplication())
            syncSettingsStatus()
            if (SettingsCloud.conflict.value != null) {
                _state.value = _state.value.withExtras { copy(cloudSyncStatus = SettingsCloud.status.value) }
            }
        }
    }

    /**
     * 登录/注册成功后、以及冷启动发现已登录时调用。
     * 这是「下次登录还是上次那套」的主入口：本机没动过 → 静默恢复云端并刷回界面。
     */
    fun afterAccountReady() {
        launchSafelyIo {
            val n = SettingsCloud.onAccountReady(getApplication())
            if (n > 0) reloadSettingsFromPrefs()
            syncSettingsStatus()
        }
    }

    /**
     * 6.3：切账号之后，把界面上的账号数据整体重读一遍。
     *
     * 为什么必须有这一步 —— 这是「换号后我喜欢全串」的第二层原因。
     * 数据层（PlayHistory / Playlists / ListenStats）已经按 uid 换分区了，
     * 但 `_state` 里的 likedSongIds / likedSongs / playlists / recentPlaybacks
     * 是登录那一刻 copy 进去的**旧快照**。不重读的话：
     *   - B 登录后首页「我喜欢」还是 A 的 30 首（UI 层）；
     *   - 用户点一下播放、触发一次落盘，这 30 首就被写进 B 的分区（数据层也脏了）。
     * 所以换号 = 换分区 + 重读 state + 让推荐重算，三件事缺一不可。
     */
    private fun reloadAccountDataFromScope() {
        val likedIds = PlayHistory.getLikes()
        val liked = PlayHistory.likedSongs()
        val pls = Playlists.all()
        val recents = PlayHistory.recentPlaybacks()
        // 推荐是按点赞/播放记录算的，换了人必须整份作废，否则 B 会被推荐 A 的口味
        Recommender.invalidate()
        _state.value = _state.value.copy(
            likedSongIds = likedIds,
            likedSongs = liked,
            playlists = pls,
            recentPlaybacks = recents
        )
        updateRecommendations()
        refreshHomeCovers()
    }

    // ---- 配置档（本机多套快照，见 SettingsPresets） ----

    fun saveSettingsPreset(name: String) {
        launchSafelyIo {
            val item = SettingsPresets.save(getApplication(), name)
            SettingsCloud.status.value = if (item != null) "已保存配置档「${item.name}」（${item.items} 项）"
                                          else "保存失败：当前设置是空的"
            syncSettingsStatus()
        }
    }

    fun applySettingsPreset(id: String, keepCurrentNamed: String = "切换前") {
        launchSafelyIo {
            // 覆盖前先给当前这套留一档，用户切错了还能切回来
            SettingsPresets.save(getApplication(), keepCurrentNamed)
            val n = SettingsPresets.apply(getApplication(), id)
            SettingsCloud.status.value = if (n > 0) "已切换配置档（$n 项，原设置已存为「$keepCurrentNamed」）"
                                          else "配置档不存在或已损坏"
            if (n > 0) {
                reloadSettingsFromPrefs()
                // 配置档内容变了，指纹必然变；顺手推一次，云端跟着最新状态
                SettingsCloud.tick(getApplication())
            }
            syncSettingsStatus()
        }
    }

    fun deleteSettingsPreset(id: String) {
        launchSafelyIo {
            SettingsPresets.delete(getApplication(), id)
            syncSettingsStatus()
        }
    }

    /**
     * 从一段文本导入配置档（分享链接贴过来的、本地备份文件的 settings 段都算）。
     * 只落成一档，**不直接改当前设置** —— 用户导进来可能只是想留着，
     * 静默改他的界面比多一步点「切到这套」更让人恼火。
     */
    fun importSettingsPreset(text: String, name: String = "导入的配置") {
        launchSafelyIo {
            val item = SettingsPresets.importText(getApplication(), text, name)
            SettingsCloud.status.value = if (item != null) "已导入配置档「${item.name}」（${item.items} 项），点「切到这套」生效"
                                          else "剪贴板里没有找到可用的设置内容"
            syncSettingsStatus()
        }
    }

    /** 云端恢复后把白名单里的字段重新读进 UI 状态 */
    private fun reloadSyncedSettings() {
        val s = _state.value
        _state.value = s.copy(        theme = readTheme(),
        mode = readMode(),
        surfaceStyle = readSurfaceStyle(),
        glassBlur = prefs.getFloat(KEY_GLASS_BLUR, GLASS_BLUR_DEFAULT)
                .coerceIn(GLASS_BLUR_MIN, GLASS_BLUR_MAX),
        bgBlur = prefs.getFloat(KEY_BG_BLUR, BG_BLUR_DEFAULT)
                .coerceIn(BG_BLUR_MIN, BG_BLUR_MAX)).withExtras { copy(            cornerBase = prefs.getFloat(KEY_CORNER_BASE, 38f),
            fontScale = prefs.getFloat(KEY_FONT_SCALE, 1f).coerceIn(0.85f, 1.30f),
            uiFont = prefs.getInt(KEY_UI_FONT, 0).coerceIn(0, FONT_INDEX_MAX),
            topBarAutoHide = prefs.getBoolean(KEY_TOPBAR_AUTOHIDE, true),
            topBarHideSeconds = prefs.getInt(KEY_TOPBAR_HIDE_SEC, 10).coerceIn(3, 30),
            visualizerEnabled = prefs.getBoolean(KEY_VISUALIZER, false),
            coverShake = prefs.getBoolean(KEY_COVER_SHAKE, false),
            coverLyric = prefs.getBoolean(KEY_COVER_LYRIC, false),
            coverTopLyric = prefs.getBoolean(KEY_COVER_TOP_LYRIC, false),
            karaokeLyric = prefs.getBoolean(KEY_KARAOKE_LYRIC, true),
            lyricAlign = prefs.getInt(KEY_LYRIC_ALIGN, 1).coerceIn(0, 2),
            lyricFont = prefs.getInt(KEY_UI_FONT, prefs.getInt(KEY_LYRIC_FONT, 0)).coerceIn(0, FONT_INDEX_MAX),
            lyricFontSize = prefs.getFloat(KEY_LYRIC_FONT_SIZE, 20f).coerceIn(14f, 32f),
            lyricStage = prefs.getInt(KEY_LYRIC_STAGE, 0).coerceIn(0, 13),
            lyricMotion = prefs.getInt(KEY_LYRIC_MOTION, 1).coerceIn(0, 6),
            lyricDisplayMode = prefs.getInt(KEY_LYRIC_DISPLAY_MODE, 2).coerceIn(0, 4),
            lyricContext = prefs.getInt(KEY_LYRIC_CONTEXT, 2).coerceIn(0, 6),
            lyricQuality = prefs.getInt(KEY_LYRIC_QUALITY, 0).coerceIn(0, 3),
            lyricBeatFx = prefs.getBoolean(KEY_LYRIC_BEAT_FX, true),
            lyricParticles = prefs.getBoolean(KEY_LYRIC_PARTICLES, true),
            lyricBassGlow = prefs.getBoolean(KEY_LYRIC_BASS_GLOW, true),
            lyricChorusFx = prefs.getBoolean(KEY_LYRIC_CHORUS_FX, true),
            lyricChorusBoost = prefs.getFloat(KEY_LYRIC_CHORUS_BOOST, 0.7f).coerceIn(0f, 1f),
            lyricCoverTint = prefs.getBoolean(KEY_LYRIC_COVER_TINT, false),
            lyricTintStrength = prefs.getFloat(KEY_LYRIC_TINT_STRENGTH, 0.65f).coerceIn(0f, 1f),
            lyricOffsetMs = prefs.getInt(KEY_LYRIC_OFFSET, 0).coerceIn(-2000, 2000),
            lyricSongOffsets = readSongOffsets(),
            lyricStaffCard = prefs.getBoolean(KEY_LYRIC_STAFF_CARD, true),
            lyricLineSpacing = prefs.getFloat(KEY_LYRIC_LINE_SPACING, 1.18f).coerceIn(1f, 1.8f),
            lyricLetterSpacing = prefs.getFloat(KEY_LYRIC_LETTER_SPACING, 0f).coerceIn(-0.04f, 0.12f),
            lyricWeight = prefs.getInt(KEY_LYRIC_WEIGHT, 1).coerceIn(0, 2),
            lyricOutline = prefs.getFloat(KEY_LYRIC_OUTLINE, 0f).coerceIn(0f, 1f),
            lyricTexture = prefs.getInt(KEY_LYRIC_TEXTURE, 0).coerceIn(0, 3),
            lyricGlowStyle = prefs.getInt(KEY_LYRIC_GLOW_STYLE, 1).coerceIn(0, 3),
            lyricGlowStrength = prefs.getFloat(KEY_LYRIC_GLOW_STRENGTH, 0.6f).coerceIn(0f, 1f),
            lyricCoverDepth = prefs.getBoolean(KEY_LYRIC_COVER_DEPTH, false),
            lyricTuning = readTuning(prefs.getInt(KEY_LYRIC_STAGE, 0).coerceIn(0, 13)),
            lyricStageProfile = readStageProfile(prefs.getInt(KEY_LYRIC_STAGE, 0).coerceIn(0, 13)),
            lyricFocus = prefs.getFloat(KEY_LYRIC_FOCUS, 0.38f).coerceIn(0.24f, 0.62f),
            lyricBeatCamera = prefs.getBoolean(KEY_LYRIC_BEAT_CAMERA, false),
            lyricBeatCameraStrength = prefs.getFloat(KEY_LYRIC_BEAT_CAMERA_STRENGTH, 0.5f).coerceIn(0f, 1f),
            lyricLongFormMode = prefs.getInt(KEY_LYRIC_LONG_FORM, 0).coerceIn(0, 2),
            lyricVideoUri = prefs.getString(KEY_LYRIC_VIDEO_URI, "") ?: "",
            lyricVideoAlpha = prefs.getFloat(KEY_LYRIC_VIDEO_ALPHA, 0.42f).coerceIn(0f, 0.9f),
            lyricSemanticWrap = prefs.getBoolean(KEY_LYRIC_SEMANTIC_WRAP, true),
            settingsAdvanced = prefs.getBoolean(KEY_SETTINGS_ADVANCED, false),
            reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false),
            lyricFrameStats = prefs.getBoolean(KEY_LYRIC_FRAME_STATS, false),
            lyricGuideSeen = prefs.getBoolean(KEY_LYRIC_GUIDE_SEEN, false),
            lyricStagePerSong = prefs.getBoolean(KEY_STAGE_PER_SONG, false),
            favoriteStages = readFavoriteStages(),
            lyricTranslation = prefs.getBoolean(KEY_LYRIC_TRANSLATION, true),
            lyricRomanization = prefs.getBoolean(KEY_LYRIC_ROMANIZATION, false),
            fxPreset = prefs.getInt(KEY_FX_PRESET, -1),
            automixEnabled = prefs.getBoolean(KEY_AUTOMIX, false),
            automixStyle = prefs.getInt(KEY_AUTOMIX_STYLE, 0).coerceIn(0, 2),
            localFolderCover = prefs.getBoolean(KEY_LOCAL_COVER, true),
            localIgnore = prefs.getBoolean(KEY_LOCAL_IGNORE, true),
            localAliasRules = prefs.getString(KEY_LOCAL_ALIAS, "") ?: "",
            localSplitArtists = prefs.getBoolean(KEY_LOCAL_SPLIT, false),
            fadeEnabled = prefs.getBoolean(FadeController.KEY_ENABLED, false),
            fadeMs = prefs.getLong(FadeController.KEY_FADE_MS, FadeController.DEFAULT_FADE_MS),
            hapticsEnabled = prefs.getBoolean(Haptics.KEY_ENABLED, true),
            silenceSkip = prefs.getBoolean(SilenceSkipper.KEY_ENABLED, false),
            // 5.2（云恢复后重读）
            audioQuality = AudioQuality.of(prefs.getString(AudioQuality.PREF_KEY, null), AudioQuality.STANDARD),
            playbackSpeedBp = prefs.getInt(MusicService.KEY_PLAYBACK_SPEED_BP, 100),
            bassHaptics = prefs.getBoolean(BassHaptics.KEY_ENABLED, false),
            virtualBass = prefs.getBoolean(VirtualBass.KEY_ENABLED, false),
            virtualBassStrength = prefs.getInt(VirtualBass.KEY_STRENGTH, 50).coerceIn(0, 100),
            rangeEnhancer = prefs.getBoolean(RangeEnhancer.KEY_ENABLED, false),
            rangeEnhancerStrength = prefs.getInt(RangeEnhancer.KEY_STRENGTH, 70).coerceIn(0, 100),
            virtualSurround = prefs.getBoolean(VirtualSurround.KEY_ENABLED, false),
            virtualSurroundStrength = prefs.getInt(VirtualSurround.KEY_STRENGTH, 40).coerceIn(0, 100),
            spatialWide = prefs.getBoolean(SpatialWide.KEY_ENABLED, false),
            spatialWideStrength = prefs.getInt(SpatialWide.KEY_STRENGTH, 50).coerceIn(0, 100),
            safeLimiter = prefs.getBoolean(SafeLimiter.KEY_ENABLED, false),
            safeLimiterStrength = prefs.getInt(SafeLimiter.KEY_STRENGTH, 30).coerceIn(0, 100),
            loudnorm = prefs.getBoolean(TrackGain.KEY_ENABLED, false),
            loudnormStrength = prefs.getInt(TrackGain.KEY_STRENGTH, 70).coerceIn(0, 100),
            customPrimaryArgb = prefs.getLong(KEY_CUSTOM_PRIMARY, 0xFF00F0FFL),
            customSecondaryArgb = prefs.getLong(KEY_CUSTOM_SECONDARY, 0xFFFF2E97L)) }
        // 立即把恢复后的开关同步到各 DSP 静态状态
        FadeController.enabled = _state.value.fadeEnabled
        FadeController.fadeMs = _state.value.fadeMs
        Haptics.enabled = _state.value.hapticsEnabled
        SilenceSkipper.setEnabled(_state.value.silenceSkip)
        VirtualBass.setEnabled(_state.value.virtualBass)
        VirtualBass.setStrength(_state.value.virtualBassStrength)
        RangeEnhancer.setEnabled(_state.value.rangeEnhancer)
        RangeEnhancer.setStrength(_state.value.rangeEnhancerStrength)
        VirtualSurround.setEnabled(_state.value.virtualSurround)
        VirtualSurround.setStrength(_state.value.virtualSurroundStrength)
        SpatialWide.setEnabled(_state.value.spatialWide)
        SpatialWide.setStrength(_state.value.spatialWideStrength)
        SafeLimiter.setEnabled(_state.value.safeLimiter)
        SafeLimiter.setStrength(_state.value.safeLimiterStrength)
        FxPreset.of(_state.value.fxPreset)?.let { applyFxPreset(it) }
    }

    // ==================== 局域网遥控 ====================

    fun setRemoteEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REMOTE_ENABLED, enabled).apply()
        com.jun.musiccloud.playback.MusicService.instance?.syncRemoteControl()
        val url = if (enabled) {
            com.jun.musiccloud.playback.RemoteControlServer.url(getApplication())
        } else ""
        _state.value = _state.value.withExtras { copy(remoteEnabled = enabled, remoteUrl = url) }
    }

    /**
     * 音乐渐入渐出开关。服务与 UI 同进程，FadeController 的静态字段可直接写，
     * 无需经由 MediaController 传命令。
     */
    fun setFadeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(FadeController.KEY_ENABLED, enabled).apply()
        FadeController.enabled = enabled
        if (!enabled) FadeController.resetVolume()
        _state.value = _state.value.withExtras { copy(fadeEnabled = enabled) }
    }

    /** 渐变时长（毫秒） */
    fun setFadeMs(ms: Long) {
        val v = ms.coerceIn(FadeController.MIN_FADE_MS, FadeController.MAX_FADE_MS)
        prefs.edit().putLong(FadeController.KEY_FADE_MS, v).apply()
        FadeController.fadeMs = v
        _state.value = _state.value.withExtras { copy(fadeMs = v) }
    }

    /** 触感反馈开关 */
    fun setHapticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Haptics.KEY_ENABLED, enabled).apply()
        Haptics.enabled = enabled
        _state.value = _state.value.withExtras { copy(hapticsEnabled = enabled) }
        // 打开时立刻给一次反馈，让用户当场感知强度
        if (enabled) Haptics.click()
    }

    /** 歌单页"为你推荐"区域显示开关 */
    fun setShowRecommendations(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_RECS, show).apply()
        _state.value = _state.value.withExtras { copy(showRecommendations = show) }
    }

    /** 上栏排序行显示开关 */
    fun setShowSortBar(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SORT_BAR, show).apply()
        _state.value = _state.value.withExtras { copy(showSortBar = show) }
    }
    /** 海报墙果冻动效开关 */
    fun setJellyAnim(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_JELLY_ANIM, enabled).apply()
        _state.value = _state.value.copy(jellyAnim = enabled)
    }

    /**
     * 6.6 动效风格切换（用户："除了果冻Q弹也弄一个别的效果吧，设置里面能切换"）。
     *
     * 三档：果冻Q弹（原有的软弹簧）/ 顺滑（几乎不过冲、快而稳，老机型推荐）/
     * 超弹（比果冻更荡）。全站弹簧参数集中在 [com.jun.musiccloud.ui.JunMotion]
     * 的 styleDamping/styleStiffness，切一次全 App 生效，无需重启。
     * jellyAnim 状态跟着派生（JELLY = true），老的开关语义不破。
     */
    fun setMotionStyle(name: String) {
        val st = com.jun.musiccloud.ui.JunMotion.MotionStyle.of(name)
        prefs.edit().putString(KEY_MOTION_STYLE, st.name)
            .putBoolean(KEY_JELLY_ANIM, st == com.jun.musiccloud.ui.JunMotion.MotionStyle.JELLY)
            .apply()
        com.jun.musiccloud.ui.JunMotion.applyStyle(st)
        _state.value = _state.value.copy(motionStyle = st.name, jellyAnim = st == com.jun.musiccloud.ui.JunMotion.MotionStyle.JELLY)
    }

    /**
     * 6.3 新增：整体动画速度。
     *
     * 一次调节同时影响三类东西（见 [com.jun.musiccloud.ui.JunMotion] 的 k()/ms()）：
     *   - 小播放器 ↔ 播放页的翻页吸附（pagerSnap）
     *   - 所有果冻回弹、按压形变、弹层入场（全部弹簧）
     *   - 定长的淡入淡出与错峰间隔
     *
     * 写入后**不需要**刷新界面：JunMotion 是静态字段，下一次动画构造时就生效。
     * 设置页里那次按压回弹本身就是最直观的预览——用户滑一下就知道快慢对不对。
     */
    fun setMotionSpeed(scale: Float) {
        val v = scale.coerceIn(0.4f, 2.5f)
        prefs.edit().putFloat(JunMotionSpeed.KEY, v).apply()
        JunMotionSpeed.apply(v)
        _state.value = _state.value.copy(motionSpeed = v)
    }

    /** 6.3 新增：下载格式（跟音质走 / MP3 / FLAC） */
    fun setDownloadFormat(f: DownloadFormat) {
        DownloadFormatStore.save(getApplication(), f)
        _state.value = _state.value.copy(downloadFormat = f.name)
    }

    /** 无声略过（beta） */
    fun setSilenceSkip(enabled: Boolean) {
        prefs.edit().putBoolean(SilenceSkipper.KEY_ENABLED, enabled).apply()
        SilenceSkipper.setEnabled(enabled)
        _state.value = _state.value.withExtras { copy(silenceSkip = enabled) }
    }

    /** 低音马达震动（beta · 多维听感） */
    fun setBassHaptics(enabled: Boolean) {
        prefs.edit().putBoolean(BassHaptics.KEY_ENABLED, enabled).apply()
        // BassHaptics.setEnabled 内部已经 && DevicePerformance.allowBassHaptics（老机安全档否决），
        // 这里同步写回界面状态，是为了不再出现"设置里看着开了、其实马达没动"——
        // 用户遇到的是"这个开关没用"，而不是"这台机器被降级了"。
        BassHaptics.setEnabled(enabled)
        val real = enabled && com.jun.musiccloud.ui.theme.DevicePerformance.allowBassHaptics
        _state.value = _state.value.withExtras { copy(bassHaptics = real) }
    }

    /** 震动强度：0=轻点 1=标准 2=重击 */
    fun setBassHapticsIntensity(level: Int) {
        val lv = level.coerceIn(0, 2)
        prefs.edit().putInt(BassHaptics.KEY_INTENSITY, lv).apply()
        BassHaptics.setIntensity(lv)
        BassHaptics.preview(lv) // 选档即时预览：用该档波形震一下
        _state.value = _state.value.withExtras { copy(bassHapticsIntensity = lv) }
    }

    /** 单次震动时长 ms，1-30 */
    fun setBassHapticsPulseMs(ms: Int) {
        val v = ms.coerceIn(1, 30)
        prefs.edit().putInt(BassHaptics.KEY_PULSE_MS, v).apply()
        BassHaptics.setPulseMs(v)
        _state.value = _state.value.withExtras { copy(bassHapticsPulseMs = v) }
    }

    /** 触发灵敏度 1-10，越大越容易触发 */
    fun setBassHapticsSensitivity(level: Int) {
        val v = level.coerceIn(1, 10)
        prefs.edit().putInt(BassHaptics.KEY_SENSITIVITY, v).apply()
        BassHaptics.setSensitivity(v)
        _state.value = _state.value.withExtras { copy(bassHapticsSensitivity = v) }
    }

    /** 低音截止频率 Hz：120–320，多少 Hz 以下算作低音走底鼓闷震 */
    fun setBassHapticsLowCutoff(hz: Int) {
        val v = hz.coerceIn(120, 320)
        prefs.edit().putInt(BassHaptics.KEY_LOW_CUTOFF, v).apply()
        BassHaptics.setLowCutoffHz(v)
        _state.value = _state.value.withExtras { copy(bassHapticsLowCutoff = v) }
    }

    /** 自适应：时长/灵敏度跟随音乐实时变化 */
    fun setBassHapticsAdaptive(enabled: Boolean) {
        prefs.edit().putBoolean(BassHaptics.KEY_ADAPTIVE, enabled).apply()
        BassHaptics.setAdaptive(enabled)
        _state.value = _state.value.withExtras { copy(bassHapticsAdaptive = enabled) }
    }
    // ==================== V2.2 声音增强 ====================
    /** 虚拟低音（beta · 多维听感）：心理声学谐波合成 */
    fun setVirtualBass(enabled: Boolean) {
        prefs.edit().putBoolean(VirtualBass.KEY_ENABLED, enabled).apply()
        VirtualBass.setEnabled(enabled)
        _state.value = _state.value.withExtras { copy(virtualBass = enabled) }
    }
    /** 虚拟低音混合强度 0-100 */
    fun setVirtualBassStrength(v: Int) {
        val x = v.coerceIn(0, 100)
        prefs.edit().putInt(VirtualBass.KEY_STRENGTH, x).apply()
        VirtualBass.setStrength(x)
        _state.value = _state.value.withExtras { copy(virtualBassStrength = x) }
    }
    /** 动态范围增强（beta · 多维听感）：压缩 + 化妆增益 */
    fun setRangeEnhancer(enabled: Boolean) {
        prefs.edit().putBoolean(RangeEnhancer.KEY_ENABLED, enabled).apply()
        RangeEnhancer.setEnabled(enabled)
        _state.value = _state.value.withExtras { copy(rangeEnhancer = enabled) }
    }
    /** 动态范围增强强度 0-100 */
    fun setRangeEnhancerStrength(v: Int) {
        val x = v.coerceIn(0, 100)
        prefs.edit().putInt(RangeEnhancer.KEY_STRENGTH, x).apply()
        RangeEnhancer.setStrength(x)
        _state.value = _state.value.withExtras { copy(rangeEnhancerStrength = x) }
    }
    /** 虚拟环绕（beta · 多维听感）：立体声声场展宽 */
    fun setVirtualSurround(enabled: Boolean) {
        prefs.edit().putBoolean(VirtualSurround.KEY_ENABLED, enabled).apply()
        VirtualSurround.setEnabled(enabled)
        _state.value = _state.value.withExtras { copy(virtualSurround = enabled) }
    }
    /** 虚拟环绕强度 0-100 */
    fun setVirtualSurroundStrength(v: Int) {
        val x = v.coerceIn(0, 100)
        prefs.edit().putInt(VirtualSurround.KEY_STRENGTH, x).apply()
        VirtualSurround.setStrength(x)
        _state.value = _state.value.withExtras { copy(virtualSurroundStrength = x) }
    }

    /** 宽场环绕（beta · 多维听感）：多频段展宽 + 早期反射，进一步提升空间感 */
    fun setSpatialWide(enabled: Boolean) {
        prefs.edit().putBoolean(SpatialWide.KEY_ENABLED, enabled).apply()
        SpatialWide.setEnabled(enabled)
        _state.value = _state.value.withExtras { copy(spatialWide = enabled) }
    }
    /** 宽场环绕强度 0-100 */
    fun setSpatialWideStrength(v: Int) {
        val x = v.coerceIn(0, 100)
        prefs.edit().putInt(SpatialWide.KEY_STRENGTH, x).apply()
        SpatialWide.setStrength(x)
        _state.value = _state.value.withExtras { copy(spatialWideStrength = x) }
    }

    /** 防失真限幅（V3.10）：防止推高/增强后削波撕裂 */
    fun setSafeLimiter(enabled: Boolean) {
        prefs.edit().putBoolean(SafeLimiter.KEY_ENABLED, enabled).apply()
        SafeLimiter.setEnabled(enabled)
        _state.value = _state.value.withExtras { copy(safeLimiter = enabled) }
    }
    /** 防失真限幅强度 0-100 */
    fun setSafeLimiterStrength(v: Int) {
        val x = v.coerceIn(0, 100)
        prefs.edit().putInt(SafeLimiter.KEY_STRENGTH, x).apply()
        SafeLimiter.setStrength(x)
        _state.value = _state.value.withExtras { copy(safeLimiterStrength = x) }
    }
    /** 曲间响度均衡（V3.11）开关 */
    fun setLoudnorm(enabled: Boolean) {
        prefs.edit().putBoolean(TrackGain.KEY_ENABLED, enabled).apply()
        TrackGain.setEnabled(enabled)
        _state.value = _state.value.withExtras { copy(loudnorm = enabled) }
    }
    /** 响度均衡拉平力度 0-100 */
    fun setLoudnormStrength(v: Int) {
        val x = v.coerceIn(0, 100)
        prefs.edit().putInt(TrackGain.KEY_STRENGTH, x).apply()
        TrackGain.setStrength(x)
        _state.value = _state.value.withExtras { copy(loudnormStrength = x) }
    }

    // ==================== 点赞 ====================

    fun toggleLike(songId: Long) {
        // 点赞对象可能来自曲库、播放队列、搜索结果或推荐——挨个找一遍拿到完整元数据，
        // 才能在「我喜欢」里保住这首歌（否则离开搜索页就丢失了）。
        val song = _state.value.allSongs.firstOrNull { it.id == songId }
            ?: _state.value.queue.firstOrNull { it.id == songId }
            ?: _state.value.searchResults.firstOrNull { it.id == songId }
            ?: _state.value.recommendations.firstOrNull { it.id == songId }
            ?: _state.value.likedSongs.firstOrNull { it.id == songId }

        val liked = PlayHistory.toggleLike(songId)
        if (liked) {
            song?.let {
                PlayHistory.saveLikedSong(it)
                PlayHistory.saveKnownSong(it)
            }
        } else {
            PlayHistory.removeLikedSong(songId)
        }
        _state.value = _state.value.copy(
            likedSongIds = PlayHistory.getLikes(),
            likedSongs = PlayHistory.likedSongs(),
            likeNotice = if (liked) "已添加到我喜欢" else "已取消喜欢",
            likeNoticeTick = _state.value.likeNoticeTick + 1
        )
        // 点赞变化后立即刷新推荐
        updateRecommendations()
        // 收藏筛选模式下点赞取消后要重新过滤队列
        if (_state.value.onlyLiked) applyFilters(resetToFirst = false, listContentChanged = true)
        // 已登录则立即同步到服务器（失败静默，本地状态不回滚，离线也能用）
        pushLikeRealtime(song, liked, songId)
        // 已登录网易云则把红心同步到网易云账号（歌单/点赞跨端同步，失败静默）。
        // 6.9：QQ 源的歌（songId = "qq:<mid>"，非数字）没有网易云身份，不白发请求。
        if (NeteaseStore.isLoggedIn && song != null &&
            song.songId.isNotBlank() && song.songId.all { it.isDigit() }
        ) {
            launchSafelyIo {
                runCatching { NeteaseApi.likeSong(song.songId, liked) }
            }
        }
    }

    /**
     * 点赞元数据回填：老版本或云端同步可能只记了点赞 ID 而没存歌曲元数据，
     * 导致「我喜欢」列表为空但计数不为 0。曲库加载完成后，按 ID 把缺失的歌曲补记进去，
     * 让列表与计数保持一致（曲库里已删除的歌无法补，属正常损耗）。
     */
    private fun reconcileLikedSongs(library: List<Song>) {
        if (library.isEmpty()) return
        val likedIds = PlayHistory.getLikes()
        if (likedIds.isEmpty()) return
        val haveMeta = PlayHistory.likedSongs().mapTo(HashSet()) { it.id }
        val missing = likedIds - haveMeta
        if (missing.isEmpty()) return
        library.forEach { if (it.id in missing) PlayHistory.saveLikedSong(it) }
        _state.value = _state.value.copy(likedSongs = PlayHistory.likedSongs())
    }

    /** 队列仅显示点赞歌曲（我的收藏） */
    fun setOnlyLiked(only: Boolean) {
        // 「我喜欢」与自定义歌单互斥
        _state.value = _state.value.copy(onlyLiked = only, activePlaylistId = null)
        prefs.edit()
            .putBoolean(KEY_ONLY_LIKED, only)
            .putLong(KEY_ACTIVE_PLAYLIST, -1L)
            .apply()
        applyFilters(resetToFirst = true)
    }

    // ==================== 图书馆模式 ====================

    /**
     * 判定结果缓存：checked = 确认没有实质歌词（纯音乐 / 白噪音 / lofi），
     * blocked = 确认带人声歌词。结果会持久化，第二次开模式基本不用再等。
     */
    private val libraryCheckedIds: MutableSet<Long> = run {
        val saved = prefs.getStringSet(KEY_LIBRARY_VERIFIED, emptySet()) ?: emptySet()
        saved.mapNotNullTo(mutableSetOf<Long>()) { it.toLongOrNull() }
    }
    private val libraryBlockedIds: MutableSet<Long> = run {
        val saved = prefs.getStringSet(KEY_LIBRARY_BLOCKED, emptySet()) ?: emptySet()
        saved.mapNotNullTo(mutableSetOf<Long>()) { it.toLongOrNull() }
    }

    /** 预筛会话：开启模式 / 播放新列表时启动，把候选列表筛成确认纯音乐再入队 */
    private var librarySessionJob: Job? = null
    /** 会话代号：换列表 / 关模式后，旧会话的结果直接作废 */
    private var librarySessionId = 0
    /** 冷启动自动准备（只做一次，用户手动清空队列后不再自动填回来） */
    private var libraryAutoPrepare = prefs.getBoolean(KEY_LIBRARY_MODE, false)
    /** 并发拉歌词的批大小 */
    private val libraryBatchSize = 4
    /** 首批凑够几首确认纯音乐就开始播（3 首足够撑到后台继续追加） */
    private val libraryWarmupCount = 3
    /** 单次最多检查多少首未判定的歌（已判定过的不占额度） */
    private val libraryScanLimit = 500

    /**
     * 开启 / 关闭图书馆模式。
     *
     * 开启后不直接播放：先在后台把候选列表的歌词查一遍，确认是纯音乐才入队。
     * 队列里只有「确认过的纯音乐」，所以播放中不会出现播到一半跳歌，
     * 点下一首就是自然地播下一首。
     */
    fun setLibraryMode(enabled: Boolean) {
        if (_state.value.libraryMode == enabled) return
        prefs.edit().putBoolean(KEY_LIBRARY_MODE, enabled).apply()
        librarySessionJob?.cancel()
        librarySessionId++
        libraryAutoPrepare = false
        if (!enabled) {
            _state.value = _state.value.copy(
                libraryMode = false,
                libraryFiltering = false,
                libraryNotice = "图书馆模式已关闭",
                libraryNoticeTick = _state.value.libraryNoticeTick + 1
            )
            applyFilters(resetToFirst = false)
            return
        }

        _state.value = _state.value.copy(
            libraryMode = true,
            libraryFiltering = true,
            libraryNotice = "图书馆模式：正在筛选纯音乐…",
            libraryNoticeTick = _state.value.libraryNoticeTick + 1
        )
        val s = _state.value
        val source = buildLibrarySource(s.queue, s.allSongs)
        if (source.isEmpty()) {
            _state.value = _state.value.copy(libraryFiltering = false)
            noteLibraryNotice("曲库里没有找到纯音乐 / 白噪音 / lofi 歌曲")
            return
        }
        val playing = controller?.isPlaying == true
        val curId = s.currentSong?.id
        // 当前歌还没确认过就先停下，避免人声一直播；确认是纯音乐会原位置续上
        if (playing && (curId == null || curId !in libraryCheckedIds)) {
            controller?.pause()
        }
        startLibrarySession(
            source = source,
            preferred = s.currentSong,
            wasPlaying = playing,
            autoPlay = playing
        )
    }

    private fun noteLibraryNotice(text: String) {
        _state.value = _state.value.copy(
            libraryNotice = text,
            libraryNoticeTick = _state.value.libraryNoticeTick + 1
        )
    }

    /**
     * 单曲点击的统一闸门（首页 / 我喜欢 / 歌单 / 榜单）。
     *
     * 确认带人声的直接提示并拦下；还没判过的先查歌词，查完再重试这次点击。
     * 返回 true 表示这次点击已经被处理，调用方应立刻 return——
     * 不能让它落进 playList，否则「点了 A 却播了 B」。
     */
    private fun guardLibrarySingle(song: Song?, retry: () -> Unit): Boolean {
        if (song == null || !_state.value.libraryMode) return false
        if (song.id in libraryBlockedIds || LibraryMode.isLikelyVocal(song)) {
            noteLibraryNotice("「${song.title}」带人声歌词，图书馆模式不播放")
            return true
        }
        if (song.id !in libraryCheckedIds) {
            verifyLibrarySongThen(song, retry)
            return true
        }
        return false
    }

    /** 候选来源：当前列表优先，不够再接着用整个曲库；元数据上明显人声的先排除 */
    private fun buildLibrarySource(primary: List<Song>, fallback: List<Song> = emptyList()): List<Song> {
        val seen = HashSet<Long>()
        val out = ArrayList<Song>(primary.size + fallback.size)
        for (list in listOf(primary, fallback)) {
            for (song in LibraryMode.prioritize(list)) {
                if (!seen.add(song.id)) continue
                if (song.id in libraryBlockedIds) continue
                out += song
            }
        }
        return out
    }

    /**
     * 把候选列表筛成「确认纯音乐」再播放：
     *  1. 先判当前正在播的那首；确认是纯音乐就原样续播，带人声就暂停；
     *  2. 按候选顺序批量拉歌词（4 路并发），凑够首批或列表扫完就开始播；
     *  3. 后台继续扫剩下的，新确认的纯音乐按顺序追加进队列。
     * 未判定的歌永远不会进队列，所以不会出现「播到一半发现是人声再跳掉」。
     */
    private fun startLibrarySession(
        source: List<Song>,
        preferred: Song?,
        wasPlaying: Boolean,
        autoPlay: Boolean,
        title: String? = null,
        addToRecent: Boolean = false
    ) {
        if (!_state.value.libraryMode) return
        if (source.isEmpty()) {
            noteLibraryNotice("这个列表里没有适合图书馆模式的歌")
            return
        }
        librarySessionJob?.cancel()
        val session = ++librarySessionId
        _state.value = _state.value.copy(libraryFiltering = true)

        librarySessionJob = launchSafely {
            // 1. 当前歌曲先判：它决定「马上续播」还是「先停掉人声」
            var startSong: Song? = null
            var preferredBlocked = false
            if (preferred != null) {
                when {
                    preferred.id in libraryBlockedIds -> preferredBlocked = true
                    preferred.id in libraryCheckedIds -> startSong = preferred
                    LibraryMode.isLikelyVocal(preferred) -> {
                        libraryBlockedIds += preferred.id
                        preferredBlocked = true
                    }
                    else -> {
                        val vocal = withContext(Dispatchers.IO) { classifyLibrarySong(preferred) }
                        if (session != librarySessionId || !_state.value.libraryMode) return@launchSafely
                        if (vocal) {
                            libraryBlockedIds += preferred.id
                            preferredBlocked = true
                        } else {
                            libraryCheckedIds += preferred.id
                            startSong = preferred
                        }
                    }
                }
                persistLibraryCache()
            }
            if (preferredBlocked && wasPlaying) controller?.pause()

            val verified = mutableListOf<Song>()
            startSong?.let { verified += it }
            var scanned = 0
            var index = 0
            var published = false

            if (startSong != null) {
                // 正在播的这首确认是纯音乐：先把队列换成以它开头，声音不断
                published = publishLibraryQueue(
                    songs = verified.toList(),
                    preferredId = startSong.id,
                    wasPlaying = wasPlaying,
                    autoPlay = autoPlay,
                    title = title,
                    addToRecent = addToRecent
                )
            }

            while (index < source.size && scanned < libraryScanLimit) {
                if (session != librarySessionId || !_state.value.libraryMode) return@launchSafely

                // 已确认的先入队，不等这一批网络的返回：
                // 命中缓存时队列能立刻补全，不会出现「点了一首、队列里只剩一首」的空窗
                if (!published && verified.size >= libraryWarmupCount) {
                    published = publishLibraryQueue(
                        songs = verified.toList(),
                        preferredId = preferred?.id,
                        wasPlaying = wasPlaying,
                        autoPlay = autoPlay,
                        title = title,
                        addToRecent = addToRecent
                    )
                } else if (published) {
                    val queued = _state.value.queue.mapTo(HashSet()) { it.id }
                    val add = verified.filter { it.id !in queued }
                    if (add.isNotEmpty()) appendLibrarySongs(add)
                }

                val batch = ArrayList<Song>(libraryBatchSize)
                while (index < source.size && batch.size < libraryBatchSize && scanned < libraryScanLimit) {
                    val song = source[index++]
                    when {
                        song.id == startSong?.id -> Unit
                        song.id in libraryBlockedIds -> Unit
                        song.id in libraryCheckedIds -> if (verified.none { it.id == song.id }) verified += song
                        else -> {
                            batch += song
                            scanned++
                        }
                    }
                }
                if (batch.isNotEmpty()) {
                    val results = coroutineScope {
                        batch.map { song ->
                            async(Dispatchers.IO) { song to classifyLibrarySong(song) }
                        }.awaitAll()
                    }
                    if (session != librarySessionId || !_state.value.libraryMode) return@launchSafely
                    for ((song, vocal) in results) {
                        if (vocal) {
                            libraryBlockedIds += song.id
                        } else {
                            libraryCheckedIds += song.id
                            verified += song
                        }
                    }
                    persistLibraryCache()
                }
            }

            if (session != librarySessionId) return@launchSafely
            if (!published && verified.isNotEmpty()) {
                published = publishLibraryQueue(
                    songs = verified.toList(),
                    preferredId = preferred?.id,
                    wasPlaying = wasPlaying,
                    autoPlay = autoPlay,
                    title = title,
                    addToRecent = addToRecent
                )
            }
            _state.value = _state.value.copy(libraryFiltering = false)
            if (!published) {
                _state.value = _state.value.copy(queue = emptyList(), currentIndex = -1)
                controller?.pause()
                controller?.clearMediaItems()
                noteLibraryNotice("没有找到纯音乐 / 白噪音 / lofi 歌曲")
            } else {
                noteLibraryNotice("图书馆模式：已筛选 ${_state.value.queue.size} 首纯音乐")
            }
        }
    }

    /** 把筛好的歌写进播放队列；当前歌已在队列里时保持播放位置不变 */
    private fun publishLibraryQueue(
        songs: List<Song>,
        preferredId: Long?,
        wasPlaying: Boolean,
        autoPlay: Boolean,
        title: String?,
        addToRecent: Boolean
    ): Boolean {
        if (songs.isEmpty()) return false
        val s = _state.value
        val idx = preferredId
            ?.let { id -> songs.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            ?: 0
        val keepCurrent = s.currentSong?.id == songs[idx].id
        _state.value = s.copy(queue = songs, currentIndex = idx, libraryFiltering = false)
        // 图书馆模式筛好的这份也是"这一轮要听的队列"，打上会话标记（签名里含 libraryMode，
        // 关掉模式时签名对不上，保护自动失效，队列回到浏览视图）。
        pinQueueSession()

        if (title != null && addToRecent) {
            val filtered = _state.value.recentPlaybacks.filterNot { it.title == title }
            val next = (listOf(RecentPlayback(title = title, songs = songs)) + filtered).take(12)
            _state.value = _state.value.copy(recentPlaybacks = next)
            PlayHistory.saveRecentPlaybacks(next)
        }
        if (keepCurrent) {
            // 当前歌没变：只换前后条目，不打断正在播的声音。
            // 开模式时若因「未确认」暂停过，确认是纯音乐后原位置续上
            rebuildPlayOrder()
            if (autoPlay) controller?.play() else controller?.pause()
        } else if (autoPlay) {
            playAt(idx)
        } else {
            // 没在播：只把筛好的队列装进播放器，不发出声音
            val c = controller
            if (c != null) {
                val pinId = if (_state.value.shuffle) songs[idx].id else null
                val order = playOrder(songs, pinId)
                val pIdx = if (pinId != null) 0 else idx
                c.setMediaItems(order.toUniqueMediaItems(), pIdx, 0L)
                c.prepare()
                c.pause()
            }
        }
        persistPlayback(throttle = false)
        return true
    }

    /** 后台新确认的纯音乐追加到队列尾部（不影响当前播放） */
    private fun appendLibrarySongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        val s = _state.value
        val existing = s.queue.mapTo(HashSet()) { it.id }
        val add = songs.filter { it.id !in existing }
        if (add.isEmpty()) return
        _state.value = s.copy(queue = s.queue + add)
        val c = controller
        if (c != null) {
            runCatching { c.addMediaItems(add.toUniqueMediaItems()) }
        }
    }

    /** 拉一次歌词，判断是不是「真的有人唱」 */
    private fun classifyLibrarySong(song: Song): Boolean {
        val lines = runCatching {
            LyricParser.loadLyrics(song.lyricKey.ifBlank { null }, song.durationMs)
        }.getOrDefault(emptyList())
        return LibraryMode.hasVocalLyrics(lines)
    }

    /** 单首歌先查再执行（首页点歌 / 下一首播放用） */
    private fun verifyLibrarySongThen(song: Song, action: () -> Unit) {
        noteLibraryNotice("图书馆模式：正在检查「${song.title}」…")
        launchSafely {
            val vocal = withContext(Dispatchers.IO) { classifyLibrarySong(song) }
            if (!_state.value.libraryMode) return@launchSafely
            if (vocal) {
                libraryBlockedIds += song.id
                persistLibraryCache()
                noteLibraryNotice("「${song.title}」带歌词，图书馆模式不播放")
            } else {
                libraryCheckedIds += song.id
                persistLibraryCache()
                action()
            }
        }
    }

    /** 判定结果写盘：重启后不用从头再筛一遍 */
    private fun persistLibraryCache() {
        prefs.edit()
            .putStringSet(KEY_LIBRARY_VERIFIED, libraryCheckedIds.mapTo(HashSet()) { it.toString() })
            .putStringSet(KEY_LIBRARY_BLOCKED, libraryBlockedIds.mapTo(HashSet()) { it.toString() })
            .apply()
    }

    // ==================== 账号（自建账号体系） ====================

    /** 登录：成功后保存凭证，并把服务器「我喜欢」合并到本地 */
    fun login(username: String, password: String) {
        if (_state.value.accountBusy) return
        _state.value = _state.value.copy(accountStatus = "登录中…", accountBusy = true)
        launchSafely {
            when (val r = AuthApi.login(username, password)) {
                is AuthResult.Ok -> {
                    // 6.6.6：昵称优先用服务端的 nickname，没有才退回用户名。
                    // 以前这里存用户名，改过昵称的人一重登就"变回原来的名字"。
                    val shownName = r.nickname.ifBlank { r.username }
                    AccountStore.save(getApplication(), r.token, r.userId, shownName)
                    // 服务端头像优先：登录时同步服务器头像
                    val avatar = r.avatar.ifBlank { AccountStore.avatar }
                    if (r.avatar.isNotBlank()) AccountStore.setAvatar(getApplication(), r.avatar)
                    _state.value = _state.value.copy(
                        accountName = shownName,
                        accountUserId = r.userId,
                        accountLoggedIn = true,
                        accountAvatar = avatar,
                        accountStatus = "登录成功，正在同步…"
                    )
                    Telemetry.reportAuth(getApplication(), "login")
                    // 换号：先清掉上一个账号的报告缓存，再用新账号身份合并
                    resetListenCloudState()
                    // 6.3：数据层已经切到新账号分区（AccountStore.save 里做的），
                    // 这里把界面上的旧快照重读一遍——否则「我喜欢」显示的还是上一个人的歌。
                    reloadAccountDataFromScope()
                    // 登录后把账号下的收藏、歌单、搜索记录、听歌报告全部拉回本地，
                    // 不再需要用户手动点「立即同步」。
                    refreshVip()
                    syncLikes()
                    launchSafely {
                        syncPlaylistsQuietly()
                        syncSearchHistoryQuietly()
                        syncListenStatsQuietly()
                    }
                    // 换账号后设置要跟着新账号走：本机没动过就静默恢复云端，
                    // 两边都动过就把选择权交回用户（详见 SettingsCloud 的冲突策略）
                    afterAccountReady()
                    // 6.6.7：之前没同步成功的昵称，登录成功就补传
                    retryPendingNickname()
                }
                is AuthResult.Fail -> _state.value = _state.value.copy(
                    accountStatus = r.message, accountBusy = false
                )
            }
        }
    }

    /** 注册：成功后直接进入登录态，并把本地「我喜欢」推一份到云端 */
    fun register(username: String, password: String, nickname: String) {
        if (_state.value.accountBusy) return
        _state.value = _state.value.copy(accountStatus = "注册中…", accountBusy = true)
        launchSafely {
            when (val r = AuthApi.register(username, password, nickname)) {
                is AuthResult.Ok -> {
                    val shownName = r.nickname.ifBlank { r.username }
                    AccountStore.save(getApplication(), r.token, r.userId, shownName)
                    val avatar = r.avatar.ifBlank { AccountStore.avatar }
                    if (r.avatar.isNotBlank()) AccountStore.setAvatar(getApplication(), r.avatar)
                    _state.value = _state.value.copy(
                        accountName = shownName,
                        accountUserId = r.userId,
                        accountLoggedIn = true,
                        accountAvatar = avatar,
                        accountStatus = "注册成功，正在同步…"
                    )
                    Telemetry.reportAuth(getApplication(), "login")
                    // 新账号：把本地已有的喜欢/歌单/听歌历史补传上去，再拉一次云端
                    resetListenCloudState()
                    reloadAccountDataFromScope()
                    refreshVip()
                    syncLikes()
                    launchSafely {
                        syncPlaylistsQuietly()
                        syncSearchHistoryQuietly()
                        syncListenStatsQuietly()
                    }
                    // 新账号云端还没有备份，这一步会把本机这套设置推成第一份云备份
                    afterAccountReady()
                }
                is AuthResult.Fail -> _state.value = _state.value.copy(
                    accountStatus = r.message, accountBusy = false
                )
            }
        }
    }

    /**
     * 退出登录。
     *
     * 6.3 的顺序是刻意的：**先把设置推上云，再清凭证**。
     * 以前是直接 clear，等于把"这次登录期间调过的所有设置"扔掉了——
     * 用户下次登录回来发现字体、背景、均衡器全变回上一次云端的样子，
     * 报的就是「重装/重新登录后配置完全不同步」。这里在 token 还有效的最后一刻
     * 补一次上传（最多等 6 秒，失败就算了，下次登录还会再推）。
     */
    fun logout() {
        // 先上报退出（此时 token 还在，后台能准确记到「这个账号在这台手机上退出」）
        Telemetry.reportAuth(getApplication(), "logout")
        val ctx = getApplication<Application>()
        // 最后一刻把设置推上去；withTimeoutOrNull 保证不会卡住退出流程
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(6_000L) {
                    SettingsCloud.uploadNow(ctx)
                }
            }
        }
        AccountStore.clear(ctx)
        clearVipCache()
        _state.value = _state.value.copy(
            accountName = "", accountUserId = 0L, accountLoggedIn = false, accountAvatar = "",
            accountStatus = "", accountBusy = false,
            vipLevel = 0, vipName = "普通用户", vipUntil = 0L, vipClaimed = false, vipBusy = false
        )
        // 报告切回「本机数据」，不把上一个账号的云端时长留在界面上
        resetListenCloudState()
        // 6.3：界面上属于"上一个人"的喜欢/歌单/最近播放必须立刻清空，
        // 否则退出后到访客界面的那几秒还在显示别人的歌单。
        reloadAccountDataFromScope()
        // 退出登录后：下次打开重新引导一次登录（用户可能只是换号）
        StartupLoginStore.reset(getApplication())
    }

    /**
     * 注销账号：删除服务端个人数据 + 清除本机账号信息。
     *
     * 这是国内合规的硬门槛——用户必须能"把自己的数据删干净"。
     * 服务端接口是 best-effort：连不上或失败只影响"云端是否删干净"，
     * 本地账号与凭证无论如何都要清掉（注销 = 我的数据从我这消失）。
     */
    fun deleteAccount() {
        val ctx = getApplication<Application>()
        val token = AccountStore.token
        if (token.isBlank()) { logout(); return }
        launchSafely {
            val ok = withContext(Dispatchers.IO) { AuthApi.deleteAccount() }
            // 6.3：注销要把这个人的本地分区一起删掉，否则同机再注册新号时
            // AccountScope 里可能残留旧键（分区名按 uid 走，但 uid 有可能被服务器复用）。
            AccountScope.wipe(ctx, AccountStore.userId)
            AccountStore.clear(ctx)
            clearVipCache()
            _state.value = _state.value.copy(
                accountName = "", accountUserId = 0L, accountLoggedIn = false,
                accountAvatar = "", accountBusy = false,
                vipLevel = 0, vipName = "普通用户", vipUntil = 0L, vipClaimed = false, vipBusy = false,
                accountStatus = if (ok) "账号已注销，云端数据已删除" else "云端删除未确认，本机数据已清除"
            )
            resetListenCloudState()
            reloadAccountDataFromScope()
        }
    }

    /** 更新账号昵称（本地） */
    /**
     * 改昵称的统一入口（和 [setAvatarFromUri] 对称）：
     * 已登录 → 写账号昵称；未登录 → 写本机昵称。
     *
     * 之前「我的主页」的改名只走 setAccountName，未登录时把名字写进了账号表，
     * 而界面读的是 localNickname，于是"改完没反应"。
     */
    fun setDisplayName(name: String) {
        if (AccountStore.isLoggedIn) setAccountName(name) else setLocalNickname(name)
    }

    fun setAccountName(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        AccountStore.setName(getApplication(), clean)
        _state.value = _state.value.copy(accountName = clean)
        syncNicknameToServer(clean)
    }

    /**
     * 6.6.7：昵称同步到服务端的统一入口（带断线重试）。
     *
     * 用户报的 bug：改完当场是新的，退出重登又变回旧名 —— 说明那次同步
     * 压根没成功（服务器没更新 / 断网 / 限流），而旧版失败是**静默**的。
     *
     * 现在失败会把目标昵称记在本地（pending_nickname），下次启动或登录后
     * 自动补传一次（参考头像的 retryPendingAvatar 模式）——服务器恢复后
     * 名字最终一定同步上去，重登也就不会再被打回旧名。
     */
    private fun syncNicknameToServer(name: String) {
        launchSafelyIo {
            val outcome = runCatching { AuthApi.changeNicknameDetailed(name) }.getOrNull()
            val confirmed = outcome?.nickname
            if (confirmed != null) {
                prefs.edit().remove(KEY_PENDING_NICKNAME).apply()
                if (confirmed != name) {
                    AccountStore.setName(getApplication(), confirmed)
                    _state.value = _state.value.copy(accountName = confirmed)
                }
                _state.value = _state.value.copy(accountStatus = "昵称已更新，排行榜和重新登录都会用新名字")
            } else {
                // 6.7.0：把真实原因原样告诉用户。
                // 之前这里只会说"服务器没同步成功"，而真正的原因（客户端连 PATCH 都发不出去）
                // 被 catch 吞掉了 —— 用户改了五六个版本都以为是自己操作有问题。
                val reason = outcome?.error.orEmpty().ifBlank { "未知错误" }
                prefs.edit().putString(KEY_PENDING_NICKNAME, name).apply()
                _state.value = _state.value.copy(
                    accountStatus = "昵称没同步上：$reason（已记下，联网后会自动重试）"
                )
            }
        }
    }

    /** 上次没同步成功的昵称，登录后补传一次（与 retryPendingAvatar 对称） */
    private fun retryPendingNickname() {
        if (!AccountStore.isLoggedIn) return
        val pending = prefs.getString(KEY_PENDING_NICKNAME, null)?.trim().orEmpty()
        if (pending.isBlank()) return
        // 已经是这个名字就不用再传（可能上一轮其实成功了）
        if (pending == AccountStore.username) {
            prefs.edit().remove(KEY_PENDING_NICKNAME).apply()
            return
        }
        syncNicknameToServer(pending)
    }

    // ==================== 4.0.3 特大事故补偿：10 年 VIP / 内部尊享 ====================

    /** 写入会员状态：内存 + 本地缓存一起更新，断网也能显示 */
    private fun applyVip(v: VipStatus) {
        prefs.edit()
            .putInt(KEY_VIP_LEVEL, v.level)
            .putString(KEY_VIP_NAME, v.name)
            .putLong(KEY_VIP_UNTIL, v.until)
            .putBoolean(KEY_VIP_CLAIMED, v.claimed)
            .apply()
        _state.value = _state.value.copy(
            vipLevel = v.level,
            vipName = v.name,
            vipUntil = v.until,
            vipClaimed = v.claimed
        )
    }

    /** 退出 / 注销时清掉会员缓存，避免下一个账号继承上一个账号的显示 */
    private fun clearVipCache() {
        prefs.edit()
            .remove(KEY_VIP_LEVEL)
            .remove(KEY_VIP_NAME)
            .remove(KEY_VIP_UNTIL)
            .remove(KEY_VIP_CLAIMED)
            .apply()
    }

    /** 登录 / 启动时静默刷新会员状态；失败保留本地缓存 */
    fun refreshVip() {
        if (!AccountStore.isLoggedIn) return
        launchSafely {
            val v = AuthApi.fetchVip() ?: return@launchSafely
            applyVip(v)
        }
    }

    /**
     * 一键领取 10 年 VIP + 内部尊享会员。
     *
     * 服务端按账号只发一次且幂等：重复点返回已领取状态，不会叠加年限。
     * 领取成功后立即刷新账户弹层与「我的主页」的会员标识。
     */
    fun claimVip() {
        if (!AccountStore.isLoggedIn) {
            _state.value = _state.value.copy(accountStatus = "请先登录账号再领取补偿")
            return
        }
        if (_state.value.vipBusy) return
        if (_state.value.vipClaimed) {
            _state.value = _state.value.copy(accountStatus = "补偿已领取，无需重复操作")
            return
        }
        _state.value = _state.value.copy(vipBusy = true, accountStatus = "正在领取 10 年 VIP 补偿…")
        launchSafely {
            val v = AuthApi.claimVip()
            if (v == null) {
                _state.value = _state.value.copy(
                    vipBusy = false,
                    accountStatus = "领取失败，请检查网络后重试"
                )
                return@launchSafely
            }
            applyVip(v)
            _state.value = _state.value.copy(
                vipBusy = false,
                accountStatus = if (v.claimed) "补偿已到账：10 年 VIP + 内部尊享会员" else "领取成功"
            )
        }
    }

    /** 更新账号头像（本地） */
    fun setAccountAvatar(avatarUrl: String) {
        AccountStore.setAvatar(getApplication(), avatarUrl)
        _state.value = _state.value.copy(accountAvatar = avatarUrl)
    }

    /**
     * 从相册选的图片 URI 保存为本地头像：压缩到 512px 的 JPEG，再上传服务器。
     * 本地立即生效（离线也能看到），登录态下后台上传；失败保留本地，下次启动重试。
     *
     * 6.1.2：改成"自己起协程"。压缩是同步 decode + scale + JPEG 编码，相机原图
     * （nova 6 常见 4000×3000）要几百毫秒，而调用链
     * `ProfileSheet 的 ActivityResult 回调 → MainScreen 的 onPickAvatar → 这里`
     * 全程在主线程、一次 dispatch 都没有 —— 点完头像界面直接僵住，慢的能到 ANR。
     */
    fun setAccountAvatarFromUri(uri: Uri) {
        if (avatarJob?.isActive == true) return
        avatarJob = launchSafely {
            val file = compressAvatar(uri)
            if (file == null) {
                _state.value = _state.value.copy(accountStatus = "换头像失败：读不出这张图，换一张试试")
                return@launchSafely
            }
            // 删掉旧的本地头像文件，避免占空间
            val old = _state.value.accountAvatar
            if (old.isNotBlank() && !old.startsWith("http")) {
                runCatching { java.io.File(old).delete() }
            }
            setAccountAvatar(file.absolutePath)
            uploadAvatarFile(file)
        }
    }

    /** 换头像协程槽：连点两次相册不并发出两份压缩，也顺手当"正在处理"的判据 */
    private var avatarJob: Job? = null

    /**
     * 把任意图片压成最长边 512 的 JPEG（大约 30-80KB），
     * 既保证头像清晰，又远低于服务端 1.8MB 限制，弱网也能传上去。
     *
     * 6.1.2：整段切到 [Dispatchers.IO]。原来它是普通同步函数、被主线程直接调，
     * 一张相机原图要在那里 decode 两遍（先 inJustDecodeBounds 再按 sample 解码）
     * + createScaledBitmap + JPEG 编码，nova 6 上实测卡住数百毫秒。
     */
    private suspend fun compressAvatar(uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getApplication<Application>()
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@runCatching null
            var sample = 1
            while (longest / sample > 1024) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching null
            val maxSide = maxOf(bitmap.width, bitmap.height)
            val scaled = if (maxSide > 512) {
                val s = 512f / maxSide
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * s).toInt().coerceAtLeast(1),
                    (bitmap.height * s).toInt().coerceAtLeast(1),
                    true
                ).also { bitmap.recycle() }
            } else bitmap
            val outFile = File(ctx.filesDir, "avatar_${System.currentTimeMillis()}.jpg")
            java.io.FileOutputStream(outFile).use {
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it)
            }
            scaled.recycle()
            outFile
        }.getOrNull()
    }

    /** 登录态下把本地头像文件传到服务器；成功后本地路径换成服务器 URL */
    private fun uploadAvatarFile(file: File) {
        if (!AccountStore.isLoggedIn || !file.exists()) return
        launchSafely {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { file.readBytes() }.getOrNull()
            } ?: return@launchSafely
            val url = AuthApi.uploadAvatar(bytes)
            if (url.isNullOrBlank()) {
                _state.value = _state.value.copy(
                    accountStatus = "头像已保存到本机，联网后会自动同步到服务器"
                )
            } else {
                AccountStore.setAvatar(getApplication(), url)
                _state.value = _state.value.copy(
                    accountAvatar = url,
                    accountStatus = "头像已同步到服务器"
                )
                runCatching { file.delete() }
            }
        }
    }

    /** 上次没传成功的本地头像，登录后补传一次 */
    private fun retryPendingAvatar() {
        if (!AccountStore.isLoggedIn) return
        val cur = AccountStore.avatar
        if (cur.isBlank() || cur.startsWith("http")) return
        val f = File(cur)
        if (f.exists()) uploadAvatarFile(f)
    }

    /** 从服务器刷新账号资料（换设备登录后头像也能自动带过来） */
    private fun refreshProfileFromServer() {
        if (!AccountStore.isLoggedIn) return
        val cur = AccountStore.avatar
        // 本地还有待上传的头像时先不刷新，避免旧头像把刚换的覆盖掉
        if (cur.isNotBlank() && !cur.startsWith("http")) return
        launchSafely {
            val p = AuthApi.fetchProfile() ?: return@launchSafely
            if (p.avatar.isNotBlank() && p.avatar != AccountStore.avatar) {
                AccountStore.setAvatar(getApplication(), p.avatar)
                _state.value = _state.value.copy(accountAvatar = p.avatar)
            }
            // 6.6.6：把服务端记的"上次活跃"带回来，账号页与后台对齐
            if (p.lastActiveAt > 0L) {
                _state.value = _state.value.copy(accountLastActiveAt = p.lastActiveAt)
            }
            // 昵称也可能在别的设备上改过：以服务端为准。
            // 6.6.9 竞态修复：本地还有"没同步上去的昵称"（pending_nickname）时，
            // 说明用户在这台设备改的新名字还没推上去 —— 这时启动序列里
            // retryPendingNickname（补传）和本方法（拉取）是并发跑的，
            // 若拉取先回来，会把服务端的**旧昵称**写回本地、盖掉刚改的新名，
            // 用户看到的就是"改完重进又变回去了"。有 pending 时跳过昵称覆盖，
            // 等补传成功（syncNicknameToServer 会以服务端确认为准）再统一。
            val pendingNick = prefs.getString(KEY_PENDING_NICKNAME, null)?.trim().orEmpty()
            if (p.nickname.isNotBlank() && p.nickname != _state.value.accountName && pendingNick.isBlank()) {
                AccountStore.setName(getApplication(), p.nickname)
                _state.value = _state.value.copy(accountName = p.nickname)
            }
        }
    }

    /**
     * 从相册选图设为「本地头像」：未登录也能换头像，不强制进登录流程。
     * 同样把图拷进内部存储，旧文件清理，避免占空间。
     *
     * 6.1.2：未登录这一支原来直接 `input.copyTo(output)` 存**原图**——不压缩、且整段在
     * 主线程跑，比登录分支更糟（几十 MB 的相机原图整份落进 filesDir，还顺卡）。
     * 现在两条分支共用 [compressAvatar]：同一个 512px JPEG，同一份 IO 调度。
     */
    fun setLocalAvatarFromUri(uri: Uri) {
        if (avatarJob?.isActive == true) return
        avatarJob = launchSafely {
            val file = compressAvatar(uri)
            if (file == null) {
                _state.value = _state.value.copy(accountStatus = "换头像失败：读不出这张图，换一张试试")
                return@launchSafely
            }
            val old = _state.value.localAvatar
            if (old.isNotBlank() && !old.startsWith("http")) {
                runCatching { java.io.File(old).delete() }
            }
            LocalProfileStore.setAvatar(getApplication(), file.absolutePath)
            _state.value = _state.value.copy(localAvatar = file.absolutePath)
        }
    }

    /** 设置未登录时的本地昵称（仅本机显示） */
    fun setLocalNickname(name: String) {
        val ctx = getApplication<Application>()
        LocalProfileStore.setNickname(ctx, name)
        _state.value = _state.value.copy(localNickname = name)
    }

    /**
     * 头像选择统一入口：已登录云端账号 → 改云端头像；未登录 → 存本地资料头像。
     * 这样「点头像直接换图」在未登录也能用，不会被强制拽进登录流程。
     * 两条分支都是"起协程 + IO 压缩"，调用方（相册回调）立刻返回，界面不卡。
     */
    fun setAvatarFromUri(uri: Uri) {
        if (AccountStore.isLoggedIn) setAccountAvatarFromUri(uri) else setLocalAvatarFromUri(uri)
    }

    /**
     * 修改密码。需要服务器支持 /api/change-password；不支持时返回失败文案。
     * 旧密码校验 + 新密码设置都在服务端完成，本地不存明文密码。
     */
    fun changePassword(oldPwd: String, newPwd: String, onResult: (String) -> Unit) {
        if (oldPwd.isBlank() || newPwd.isBlank()) {
            onResult("密码不能为空")
            return
        }
        if (newPwd.length < 6) {
            onResult("新密码至少 6 位")
            return
        }
        launchSafely {
            val ok = AuthApi.changePassword(oldPwd, newPwd)
            onResult(if (ok) "密码修改成功" else "密码修改失败，请检查旧密码是否正确")
        }
    }

    /** 服务器热搜词更新后同步到 UI 状态（搜索页读它，保证不刷新不重启也能看到） */
    fun applyHotKeywords(list: List<String>) {
        if (list != _state.value.hotKeywords) {
            _state.value = _state.value.copy(hotKeywords = list)
        }
    }

    /** 清空账号弹层的提示文案（弹层关闭时调用） */
    fun clearAccountStatus() {
        if (_state.value.accountStatus.isNotEmpty() || _state.value.accountBusy) {
            _state.value = _state.value.copy(accountStatus = "", accountBusy = false)
        }
    }

    /**
     * 云端同步串行锁：启动、登录、实时点赞/歌单操作都可能同时触发同步，
     * 并发跑会把同一首歌的元数据写乱（列表顺序、重复记录），这里统一排队。
     */
    private val cloudSyncMutex = Mutex()

    /**
     * 「这次同步没成」到底该怎么说。
     *
     * 凭证过期与网络失败在旧写法里是同一句话（"请检查网络"），用户把路由器重启三遍
     * 也等不来歌单。SessionSignals 由 ServerApi/AuthApi 在 401/403 时点亮，这里只读它，
     * 不重复判定——判定分散在两个地方迟早对不上。
     */
    private fun expiredOrFallback(fallback: String): String {
        val s = SessionSignals
        return if (s.expiredTarget.value == SessionSignals.SOURCE_ACCOUNT &&
            s.expiredMessage.value.isNotBlank()
        ) s.expiredMessage.value else fallback
    }

    /** 登录 / 注册 / 启动时拉取服务器「我喜欢」并与本地合并 */
    private fun syncLikes() {
        launchSafely { syncLikesInternal(showStatus = true) }
    }

    /**
     * 「我喜欢」对账的**唯一身份键**（6.3）。
     *
     * 为什么必须统一：
     * 6.2 的对账一边用 `PlayHistory` 的本地 id（[com.jun.musiccloud.data.MusicRepository.stableId]，
     * 是一个 60 位哈希），一边用云端的 `song_id`（"1901371647" 这种），两套 ID 体系
     * 靠 `cloud_like_ids_<uid>` 这个快照粘起来。粘不上的后果有三个，都是用户报过的：
     *   1. 换设备 / 重装后快照丢了 → 本地每一首都被当成"没上过云"，全部重新推送；
     *      meanwhile 云端已经取消的歌又被推回来，表现为「我明明取消了，它又回来了」；
     *   2. 同一个人在两台设备上，一台用 songId、一台只有 stableId（离线点进来的歌），
     *      对账时判成两首不同的歌，列表出现重复；
     *   3. 退出登录 → 换号，快照键按 uid 分开了，但旧的 `cloud_like_ids` 没分会话，
     *      于是 B 的第一轮同步拿 A 的快照做"哪首被取消了"的判断——这就是"串号"的第二层。
     *
     * 现在只用一种键：有网易云 id 用 `sid:`，没有就用 `id:`。云端和本地都能算出来，
     * 不需要额外映射表，也不会因为版本变化而失效。
     */
    private fun likeKey(songId: String?, stableId: Long): String =
        if (!songId.isNullOrBlank()) "sid:$songId" else "id:$stableId"

    private fun likeKeyOf(song: Song): String = likeKey(song.songId, song.id)

    /**
     * 「我喜欢」同步的唯一实现。
     *
     * 手动点同步（[showStatus] = true）和冷启动静默对账走同一条路 —— 6.2 里这是两份
     * 几乎逐字重复的代码，改一处忘一处是常态（当时两边的"哪首该删"判断就已经不一致了）。
     *
     * 对账规则：
     *   - 云端有、本地无 → 加进本地；
     *   - 本地有、云端无 → 上次同步时它在云端 ⇒ 是别的设备取消的，本地删；
     *                      从没上过云 ⇒ 本地点赞还没传上去，补传；
     *   - 两边都有 → 什么都不做（保留本地顺序与元数据）。
     */
    private suspend fun syncLikesInternal(showStatus: Boolean) {
        if (!AccountStore.isLoggedIn) {
            if (showStatus) {
                _state.value = _state.value.copy(accountStatus = "请先登录账号", accountBusy = false)
            }
            return
        }
        if (showStatus) {
            _state.value = _state.value.copy(accountStatus = "同步中…", accountBusy = true)
        }
        cloudSyncMutex.withLock {
            val result = alignLikesWithCloud(showStatus)
            if (showStatus) {
                _state.value = _state.value.copy(
                    accountStatus = when {
                        result < 0 -> expiredOrFallback("同步失败，请检查网络")
                        else -> "已同步 $result 首我喜欢"
                    },
                    accountBusy = false
                )
            }
        }
    }

    /**
     * 冷启动 / 登录后静默对齐。返回同步到的歌数，失败返回 -1。
     *
     * 为什么要返回数量：登录后界面要能说一句"已恢复 N 首我喜欢"，
     * 用户的信任感就来自这句话——他得知道数据是真的回来了，而不是"看起来空的"。
     */
    private suspend fun alignLikesWithCloud(showStatus: Boolean): Int {
        // 1) 先把离线期间取消的赞补删掉，否则它们会在拉取时"复活"
        flushPendingUnlikes()
        // 2) 拉云端收藏（含完整元数据）
        val cloud = AuthApi.fetchLikes() ?: return -1
        val cloudKeys = cloud.mapTo(HashSet()) { likeKey(it.songId, 0L) }
        val previous = loadCloudLikeSnapshot()
        val localIndex = buildLocalSongIndex()
        val serverIndex = buildServerSongIndex()

        // 3) 本地有、云端没有
        val staleLocal = mutableSetOf<Long>()
        val toPush = mutableListOf<Song>()
        for (id in PlayHistory.getLikes()) {
            // 本地 id 可能对应曲库里的歌，也可能只在我喜欢里（离线点进来的）
            val song = localIndex[id] ?: PlayHistory.likedSong(id)
            val key = if (song != null) likeKeyOf(song) else likeKey(null, id)
            if (key in cloudKeys) continue
            if (key in previous) {
                // 上次同步时它确实在云端 → 是别的设备取消的
                staleLocal.add(id)
            } else if (song != null) {
                toPush.add(song)
            } else {
                // 本地连元数据都没有，也不能再推上去 → 当作脏数据清掉，避免永远对不上账
                staleLocal.add(id)
            }
        }
        val pushed = mutableSetOf<String>()
        for (song in toPush) {
            if (AuthApi.pushLike(song, true)) pushed.add(likeKeyOf(song))
        }

        // 4) 合并云端 + 删除别处已取消的本地残留
        mergeCloudLikes(cloud, staleLocal)
        // 5) 记录这一次的云端快照（用统一键，不再是 song_id）
        saveCloudLikeSnapshot(cloudKeys + pushed)

        if (showStatus) {
            _state.value = _state.value.copy(accountStatus = "已同步 ${cloud.size} 首我喜欢")
        }
        return cloud.size
    }

    /**
     * 冷启动静默对齐：多端使用 / 换设备 / 重装后登录，不需要用户手动点任何按钮。
     * 失败也不打扰用户，本地数据照常可用。
     */
    private suspend fun syncLikesQuietly() {
        if (!AccountStore.isLoggedIn) return
        cloudSyncMutex.withLock { runCatching { alignLikesWithCloud(showStatus = false) } }
    }

    /** 补删离线取消的赞；删成功才从队列移除，失败下次继续 */
    private suspend fun flushPendingUnlikes() {
        for (sid in PlayHistory.pendingUnlikes()) {
            if (AuthApi.deleteLikeByServerId(sid)) PlayHistory.removePendingUnlike(sid)
        }
    }

    /**
     * 快照键（6.3 改名）。
     *
     * 旧键 `cloud_like_ids_<who>` 存的是一批 **song_id**，而且 who 在未登录时是用户名、
     * 登录后是 uid —— 同一个账号在两种状态下算出两个键，快照对不上是必然的。
     * `cloud_like_keys_<uid>` 只按 uid 分（未登录不写快照），内容是一批 [likeKey]。
     * 旧键仍在 [AccountScope.SCOPED_PREFIXES] 里，换账号时会被一起隔离/搬迁，
     * 但代码不再读它：读到的是旧格式（纯数字 song_id），跟新格式混用只会更乱。
     */
    private fun cloudLikeSnapshotKey(): String {
        val uid = AccountStore.userId
        return if (uid > 0L) "cloud_like_keys_$uid" else "cloud_like_keys_guest"
    }

    private fun loadCloudLikeSnapshot(): Set<String> =
        prefs.getStringSet(cloudLikeSnapshotKey(), emptySet())?.toSet() ?: emptySet()

    private fun saveCloudLikeSnapshot(ids: Set<String>) {
        prefs.edit().putStringSet(cloudLikeSnapshotKey(), ids).apply()
    }

    /**
     * 把云端收藏写成完整的本地歌曲（ID + 元数据）。
     *
     * 这是「账户显示 5 首、我喜欢里却是 0 首」的根因修复：
     * 以前只拿 song_id 去当前曲库里找，找不到就直接丢掉；现在云端返回的是完整
     * 歌曲信息，即使曲库没加载、歌曲已下架，也能用云端元数据还原出一首可播放的在线歌。
     *
     * @param removeIds 别的设备已经取消、本地需要同步移除的歌曲 ID。
     */
    private fun mergeCloudLikes(cloud: List<CloudSong>, removeIds: Set<Long> = emptySet()) {
        if (removeIds.isNotEmpty()) PlayHistory.removeLikes(removeIds)
        if (cloud.isEmpty()) {
            _state.value = _state.value.copy(
                likedSongIds = PlayHistory.getLikes(),
                likedSongs = PlayHistory.likedSongs()
            )
            updateRecommendations()
            return
        }
        val serverIndex = buildServerSongIndex()
        val ids = mutableSetOf<Long>()
        // 云端按更新时间倒序返回；逐条插到列表头部会反序，所以倒着写，保证最新的仍在最前。
        for (cs in cloud.asReversed()) {
            val song = resolveCloudSong(cs, serverIndex)
            PlayHistory.saveLikedSong(song)
            PlayHistory.saveKnownSong(song)
            ids.add(song.id)
        }
        PlayHistory.addLikes(ids)
        _state.value = _state.value.copy(
            likedSongIds = PlayHistory.getLikes(),
            likedSongs = PlayHistory.likedSongs()
        )
        updateRecommendations()
    }

    /** 本地有、云端没有的喜欢：补传到服务器（离线点赞 / 老版本只存本地） */
    /** 本地 Song 索引：曲库 / 我喜欢 / 在线元数据 / 队列，按本地 ID 查 */
    private fun buildLocalSongIndex(): Map<Long, Song> = buildMap {
        _state.value.allSongs.forEach { putIfAbsent(it.id, it) }
        PlayHistory.likedSongs().forEach { putIfAbsent(it.id, it) }
        PlayHistory.knownSongs().forEach { putIfAbsent(it.id, it) }
        _state.value.queue.forEach { putIfAbsent(it.id, it) }
        _state.value.searchResults.forEach { putIfAbsent(it.id, it) }
        _state.value.neteaseListSongs.forEach { putIfAbsent(it.id, it) }
        _state.value.recommendations.forEach { putIfAbsent(it.id, it) }
    }

    /** 本地 Song 索引：按服务器 song_id 查 */
    private fun buildServerSongIndex(): Map<String, Song> = buildMap {
        _state.value.allSongs.forEach { if (it.serverId.isNotBlank()) putIfAbsent(it.serverId, it) }
        PlayHistory.likedSongs().forEach { if (it.serverId.isNotBlank()) putIfAbsent(it.serverId, it) }
        PlayHistory.knownSongs().forEach { if (it.serverId.isNotBlank()) putIfAbsent(it.serverId, it) }
        _state.value.searchResults.forEach { if (it.serverId.isNotBlank()) putIfAbsent(it.serverId, it) }
        _state.value.neteaseListSongs.forEach { if (it.serverId.isNotBlank()) putIfAbsent(it.serverId, it) }
    }

    /** 云端歌曲 → 本地 Song：优先复用曲库/缓存里的完整对象，找不到再用云端元数据还原 */
    private fun resolveCloudSong(
        cs: CloudSong,
        index: Map<String, Song>,
        folder: String = "我喜欢"
    ): Song = index[cs.songId] ?: cs.toSong(folder)

    /** 点赞/取消点赞立即上云（失败静默，本地状态不回滚，离线也能用） */
    private fun pushLikeRealtime(song: Song?, liked: Boolean, fallbackLocalId: Long = 0L) {
        if (!AccountStore.isLoggedIn) return
        val sid = song?.serverId
            ?: fallbackLocalId.toString().takeIf { fallbackLocalId > 0L }
            ?: return
        if (sid.isBlank()) return
        if (liked) {
            // 重新点赞：撤销之前的“待删除”标记，否则同步时会把它当成已取消。
            PlayHistory.removePendingUnlike(sid)
        } else {
            // 先记待删：这次网络失败也不怕，下次同步会补删。
            PlayHistory.addPendingUnlike(sid)
        }
        launchSafely {
            val ok = runCatching {
                if (song != null) AuthApi.pushLike(song, liked)
                else if (!liked) AuthApi.deleteLikeByServerId(sid)
                else false
            }.getOrDefault(false)
            if (ok && !liked) PlayHistory.removePendingUnlike(sid)
        }
    }

    // ==================== 歌单 / 搜索历史云同步 ====================

    /**
     * 冷启动 / 登录后拉取云端歌单并与本地双向合并。
     *
     * 旧版本只有「本地→云端」单向推送：换设备、重装、清数据后，云端歌单永远回不来；
     * 而且重装后 cloud_pl_* 映射丢失，歌单会被当成新歌单再推一遍，导致云端重复。
     * 现在按「映射 → 同名 → 新建」三级匹配，曲目双向补齐。
     */
    private suspend fun syncPlaylistsQuietly() {
        if (!AccountStore.isLoggedIn) return
        cloudSyncMutex.withLock {
            val cloud = AuthApi.fetchPlaylists() ?: return@withLock
            val localList = Playlists.all()
            val mappedLocal = mutableSetOf<Long>()
            val mappedCloud = mutableSetOf<Long>()

            // 1) 已有映射
            for (pl in localList) {
                val cid = cloudPlaylistId(pl.id)
                if (cid > 0 && cloud.any { it.id == cid }) {
                    mappedLocal.add(pl.id)
                    mappedCloud.add(cid)
                }
            }
            // 2) 同名匹配：重装后本地映射没了，但歌单名还在，按名字认领防止重复创建
            for (cp in cloud) {
                if (cp.id in mappedCloud) continue
                val same = localList.firstOrNull { it.id !in mappedLocal && it.name == cp.name }
                if (same != null) {
                    setCloudPlaylistId(same.id, cp.id)
                    mappedLocal.add(same.id)
                    mappedCloud.add(cp.id)
                }
            }
            // 3) 云端有、本地完全没有：在本地重建歌单
            for (cp in cloud) {
                if (cp.id in mappedCloud) continue
                val newId = Playlists.create(cp.name)
                setCloudPlaylistId(newId, cp.id)
                mappedLocal.add(newId)
                mappedCloud.add(cp.id)
            }
            // 4) 云端曲目落到本地，本地新增曲目补上云
            val serverIndex = buildServerSongIndex()
            for (cp in cloud) {
                val localId = localPlaylistIdForCloud(cp.id) ?: continue
                mergePlaylistSongs(localId, cp, serverIndex)
            }
            // 5) 本地有、云端没有（离线创建 / 网易云导入）：补建云歌单并上传
            for (pl in Playlists.all()) {
                if (pl.id in mappedLocal) continue
                val cid = AuthApi.createPlaylistCloud(pl.name) ?: continue
                setCloudPlaylistId(pl.id, cid)
                mappedLocal.add(pl.id)
                pushPlaylistSongs(cid, Playlists.songs(pl.id))
            }
            refreshPlaylists()
            if (_state.value.activePlaylistId != null) applyFilters(resetToFirst = false)
        }
    }

    private fun localPlaylistIdForCloud(cloudId: Long): Long? =
        Playlists.all().firstOrNull { cloudPlaylistId(it.id) == cloudId }?.id

    private suspend fun mergePlaylistSongs(
        localId: Long,
        cp: CloudPlaylist,
        serverIndex: Map<String, Song>
    ) {
        val localIds = Playlists.songs(localId).toMutableSet()
        val cloudServerIds = cp.songs.mapTo(HashSet()) { it.songId }
        // 云端 → 本地：保留云端顺序，缺的歌一次性补进来（避免几百首逐条写 prefs）
        // 6.9.5：元数据也改**批量**落盘。旧版逐首 saveKnownSong（每首都整份重写
        // 1000 条 LRU），几百首就是几百次重写；而且会把刚批量加进别的歌单的歌
        // 从 known_songs 尾部挤掉 —— 歌单里 id 还在（数量显示对），内容却静默消失。
        // 现在先收集整批，一次性 rememberSongs（歌单快照，5000 上限）+ saveKnownSongs。
        val cloudSongs = cp.songs.map { resolveCloudSong(it, serverIndex, folder = "歌单") }
        val missingLocalIds = mutableListOf<Long>()
        for (song in cloudSongs) {
            if (song.id !in localIds) {
                missingLocalIds.add(song.id)
                localIds.add(song.id)
            }
        }
        if (missingLocalIds.isNotEmpty()) {
            val byId = cloudSongs.distinctBy { it.id }.associateBy { it.id }
            val missingSongs = missingLocalIds.mapNotNull { byId[it] }
            runCatching { Playlists.rememberSongs(missingSongs) }
            runCatching { PlayHistory.saveKnownSongs(missingSongs) }
            Playlists.addSongs(localId, missingLocalIds)
        }
        // 本地 → 云端：云端没有的补上去
        val index = buildLocalSongIndex()
        val missing = Playlists.songs(localId)
            .mapNotNull { index[it] }
            .filter { it.serverId.isNotBlank() && it.serverId !in cloudServerIds }
        pushSongsToCloud(cp.id, missing)
    }

    private suspend fun pushPlaylistSongs(cloudId: Long, localSongIds: List<Long>) {
        val index = buildLocalSongIndex()
        pushSongsToCloud(cloudId, localSongIds.mapNotNull { index[it] })
    }

    /** 4 首一批并发上传：几百首的导入歌单也不会串行等几百个网络往返 */
    private suspend fun pushSongsToCloud(cloudId: Long, songs: List<Song>) {
        if (songs.isEmpty()) return
        for (chunk in songs.chunked(4)) {
            coroutineScope {
                chunk.map { s ->
                    async { runCatching { AuthApi.addSongToPlaylistCloud(cloudId, s) } }
                }.awaitAll()
            }
        }
    }

    /** 搜索历史：登录 / 冷启动就合并云端记录，而不是等用户点进搜索页才拉 */
    private suspend fun syncSearchHistoryQuietly() {
        if (!AccountStore.isLoggedIn) return
        val cloud = AuthApi.fetchSearchHistory() ?: return
        if (cloud.isEmpty()) return
        SearchHistoryStore.mergeFromCloud(getApplication(), cloud)
    }

    /**
     * 打开「我的主页」时顺手同步一次：登录用户即使刚启动、还没等到定时同步，
     * 也能马上看到账号累计和旧版心跳补进来的总时长。
     */
    fun syncListenForProfile() {
        if (!AccountStore.isLoggedIn || AccountStore.userId <= 0L) return
        launchSafely { runCatching { syncListenStatsQuietly() } }
    }

    /**
     * 听歌报告云同步：上传本机增量 + 拉回账号累计。
     *
     * 这是「每个用户的听歌时长都不同步」的根治：
     * 旧版本只把 5 秒心跳发给后台当总量，报告本身完全在本机 CSV 里，换手机/重装
     * 就归零；现在把「天 + 歌曲 + 毫秒」按账号同步，打开报告看到的是账号累计。
     * 失败静默（本地报告照常可用），下一次定时同步会补传。
     */
    private suspend fun syncListenStatsQuietly() {
        if (!AccountStore.isLoggedIn || AccountStore.userId <= 0L) return
        val app = getApplication<Application>()
        val uid = AccountStore.userId
        val snapshot = runCatching {
            ListenCloud.sync(app, uid, buildLocalSongIndex())
        }.getOrNull() ?: return
        statCloudMeta = ListenCloud.metaOf(snapshot.entries)
        statLegacyDaily = snapshot.legacyDaily.associate { it.day to it.ms }
        listenSyncedAt = snapshot.updatedAt
        val nextVersion = _state.value.listenSyncVersion + 1L
        // 报告正开着：把刚拉回来的云端数据并进去，用户不用关掉重开
        if (_state.value.showReport) {
            val entries = withContext(Dispatchers.IO) {
                runCatching { ListenCloud.mergedEntries(app, uid) }.getOrElse { emptyList() }
            }
            statEntries = entries
            _state.value = _state.value.copy(
                report = ListenReportBuilder.build(
                    entries = entries,
                    songs = _state.value.allSongs,
                    range = _state.value.report.range,
                    meta = statCloudMeta,
                    legacyDaily = statLegacyDaily,
                    cloudSyncedAt = listenSyncedAt
                ),
                listenSyncVersion = nextVersion
            )
        } else {
            _state.value = _state.value.copy(listenSyncVersion = nextVersion)
        }
    }

    /** 账号切换 / 退出 / 注销后清掉上一个账号的报告缓存，避免串号 */
    private fun resetListenCloudState() {
        statEntries = null
        statCloudMeta = emptyMap()
        statLegacyDaily = emptyMap()
        listenSyncedAt = 0L
        _state.value = _state.value.copy(listenSyncVersion = _state.value.listenSyncVersion + 1L)
        if (_state.value.showReport) loadReport(_state.value.report.range)
    }

    // ==================== 网易云账号（第二个登录，可选） ====================

    /** 打开网易云扫码登录弹层：拉取二维码并开始轮询扫码状态 */
    fun openNeteaseLogin() {
        _state.value = _state.value.copy(
            showNeteaseLogin = true,
            neteaseQrContent = "",
            neteaseQrStatus = "正在获取二维码…"
        )
        startNeteaseQrFlow()
    }

    fun closeNeteaseLogin() {
        qrJob?.cancel()
        qrJob = null
        _state.value = _state.value.copy(
            showNeteaseLogin = false,
            neteaseQrContent = "",
            neteaseQrStatus = ""
        )
    }

    /**
     * 二维码扫码登录：拿 key → 渲染二维码 → 每 1.5 秒轮询一次状态（约 3 分钟有效期）。
     * 关键修复：key 阶段捕获的 NMTID 会话 cookie 会在 check 时传回，否则一扫就过期。
     */
    private fun startNeteaseQrFlow() {
        qrJob?.cancel()
        qrJob = launchSafely {
            val key = withContext(Dispatchers.IO) { NeteaseApi.qrKey() }
            if (key == null) {
                _state.value = _state.value.copy(neteaseQrStatus = "二维码获取失败，点击刷新重试")
                return@launchSafely
            }
            _state.value = _state.value.copy(
                neteaseQrContent = NeteaseApi.qrContent(key),
                neteaseQrStatus = "请用「网易云音乐」App 扫码登录"
            )
            // 轮询：801 阶段 1.2s/次，802 后加快到 0.6s/次，尽快捕获 803
            var scanned = false
            repeat(180) {
                delay(if (scanned) 600 else 1200)
                val (code, _) = withContext(Dispatchers.IO) { NeteaseApi.qrCheck(key) }
                when (code) {
                    800 -> {
                        _state.value = _state.value.copy(neteaseQrStatus = "二维码已过期，点击刷新二维码")
                        return@launchSafely
                    }
                    802 -> {
                        scanned = true
                        _state.value = _state.value.copy(neteaseQrStatus = "已扫码，请在手机上点击确认登录")
                    }
                    803 -> {
                        _state.value = _state.value.copy(neteaseQrStatus = "登录成功，正在获取账号信息…")
                        // 直接在当前协程获取用户信息，避免协程切换丢状态
                        val profile = runCatching {
                            withContext(Dispatchers.IO) { NeteaseApi.loginStatus() }
                        }.getOrNull()
                        if (profile == null) {
                            _state.value = _state.value.copy(neteaseQrStatus = "登录失败，请刷新重试")
                            return@launchSafely
                        }
                        runCatching { NeteaseStore.save(getApplication(), profile) }
                        _state.value = _state.value.copy(
                            neteaseLoggedIn = true,
                            neteaseName = profile.nickname,
                            neteaseAvatar = profile.avatar,
                            neteaseUserId = profile.userId,
                            showNeteaseLogin = false,
                            neteaseQrContent = "",
                            neteaseQrStatus = "",
                            loginPrompt = "已登录网易云：${profile.nickname}",
                            loginPromptTick = _state.value.loginPromptTick + 1
                        )
                        // 登录成功后后台预加载首页榜单/歌单
                        afterNeteaseSessionChanged()
                        loadNeteaseHomeData()
                        return@launchSafely
                    }
                    // 801 = 等待扫码，其它值也继续
                    else -> Unit
                }
            }
            _state.value = _state.value.copy(neteaseQrStatus = "二维码已过期，点击刷新二维码")
        }
    }

    // ---------- 凭证登录（手机号 / 邮箱） ----------

    /** 登录成功后的统一收尾：落库资料、更新状态、关闭弹层、预加载首页 */
    private fun applyNeteaseLoginSuccess(profile: NeteaseApi.NsProfile) {
        NeteaseStore.save(getApplication(), profile)
        _state.value = _state.value.copy(
            neteaseLoggedIn = true,
            neteaseName = profile.nickname,
            neteaseAvatar = profile.avatar,
            neteaseUserId = profile.userId,
            showNeteaseLogin = false,
            neteaseQrContent = "",
            neteaseQrStatus = "",
            neteaseCredentialLoading = false,
            neteaseCredentialError = "",
            loginPrompt = "已登录网易云：${profile.nickname}",
            loginPromptTick = _state.value.loginPromptTick + 1
        )
        afterNeteaseSessionChanged()
        loadNeteaseHomeData()
    }

    /**
     * 会话换人（登录成功 / 退出登录）之后必须把「我的歌单」的两份缓存作废。
     *
     * 内存那份的 key 里带 cookie，换 cookie 虽然也会 miss，但旧条目还赖在 LRU 里；
     * 磁盘那份 restoreNeteaseHomeCache 读它根本不看 TTL——换账号后冷启动第一帧
     * 铺的还是上一个账号的歌单。用户报的"必须重新登录才出现"的另一半就在这。
     */
    private fun afterNeteaseSessionChanged() {
        NeteaseApi.clearResponseCache("/user/playlist")
        NeteaseApi.clearResponseCache("/recommend/songs")
        mineLastOkAt = 0L
        mineRetryAt = 0L
        neteaseMineFailed.value = false
        neteaseMineNotice.value = ""
        refreshNeteaseMyPlaylists(force = true)
        // 换完账号若正好站在广场上（例如从广场里那行「去登录」登的），自动换新要接上：
        // 起表只在开弹层 / 回前台做，登录这一条路径绕过去，表就一直是停的。
        if (_state.value.showNeteasePlaylists) startMineTicker()
    }

    /** 手机号 + 密码登录 */
    fun loginNeteasePhone(phone: String, password: String) {
        if (_state.value.neteaseCredentialLoading) return
        qrJob?.cancel()
        _state.value = _state.value.copy(
            neteaseCredentialLoading = true,
            neteaseCredentialError = ""
        )
        launchSafely {
            val result = withContext(Dispatchers.IO) { NeteaseApi.loginCellphone(phone, password) }
            when (result) {
                is NeteaseApi.LoginResult.Success -> applyNeteaseLoginSuccess(result.profile)
                is NeteaseApi.LoginResult.Failure -> _state.value = _state.value.copy(
                    neteaseCredentialLoading = false,
                    neteaseCredentialError = result.message
                )
            }
        }
    }

    /** 手机号 + 验证码登录 */
    fun loginNeteasePhoneCaptcha(phone: String, captcha: String) {
        if (_state.value.neteaseCredentialLoading) return
        qrJob?.cancel()
        _state.value = _state.value.copy(
            neteaseCredentialLoading = true,
            neteaseCredentialError = ""
        )
        launchSafely {
            val result = withContext(Dispatchers.IO) { NeteaseApi.loginCellphoneCaptchaWeapi(phone, captcha) }
            when (result) {
                is NeteaseApi.LoginResult.Success -> applyNeteaseLoginSuccess(result.profile)
                is NeteaseApi.LoginResult.Failure -> _state.value = _state.value.copy(
                    neteaseCredentialLoading = false,
                    neteaseCredentialError = result.message
                )
            }
        }
    }

    /** 邮箱 + 密码登录 */
    fun loginNeteaseEmail(email: String, password: String) {
        if (_state.value.neteaseCredentialLoading) return
        qrJob?.cancel()
        _state.value = _state.value.copy(
            neteaseCredentialLoading = true,
            neteaseCredentialError = ""
        )
        launchSafely {
            val result = withContext(Dispatchers.IO) { NeteaseApi.loginEmail(email, password) }
            when (result) {
                is NeteaseApi.LoginResult.Success -> applyNeteaseLoginSuccess(result.profile)
                is NeteaseApi.LoginResult.Failure -> _state.value = _state.value.copy(
                    neteaseCredentialLoading = false,
                    neteaseCredentialError = result.message
                )
            }
        }
    }

    /**
     * 下发短信验证码（挂起函数，由 UI 侧协程调用）。
     * 返回空字符串表示发送成功；非空即可直接展示给用户的失败原因。
     */
    suspend fun sendNeteaseCaptcha(phone: String): String {
        return runCatching {
            withContext(Dispatchers.IO) { NeteaseApi.sendCellphoneCaptchaWeapi(phone) }
        }.getOrDefault("网络错误，请稍后重试")
    }

    /** 清空凭证登录的错误提示（用户重新输入时调用） */
    fun clearNeteaseCredentialError() {
        if (_state.value.neteaseCredentialError.isNotEmpty()) {
            _state.value = _state.value.copy(neteaseCredentialError = "")
        }
    }

    /** 退出网易云账号（与自建账号相互独立，互不影响） */
    fun logoutNetease() {
        qrJob?.cancel()
        qrJob = null
        launchSafely { withContext(Dispatchers.IO) { NeteaseApi.logout() } }
        NeteaseStore.clear(getApplication())
        _state.value = _state.value.copy(
            neteaseLoggedIn = false,
            neteaseName = "",
            neteaseAvatar = "",
            neteaseUserId = ""
        )
        // 6.1.2：退出登录必须把「我的歌单」两份缓存一起清掉。
        // 磁盘那份 restoreNeteaseHomeCache 不看 TTL，不清的话下一个账号（或再登录的
        // 同一账号）冷启动第一帧铺的是上一个账号的歌单——和"新建的歌单不出现"同一个成因。
        NeteaseApi.clearResponseCache("/user/playlist")
        clearHomeCache("mine")
        mineLastOkAt = 0L
        mineRetryAt = 0L
        neteaseMineFailed.value = false
        neteaseMineNotice.value = ""
        _state.value = _state.value.copy(neteaseMyPlaylists = emptyList())
    }

    /** 统一的「需要登录」提醒：弹出轻提示，并直接打开网易云登录弹层 */
    private fun promptNeteaseLogin(message: String, openLogin: Boolean = true) {
        _state.value = _state.value.copy(
            loginPrompt = message,
            loginPromptTick = _state.value.loginPromptTick + 1,
            showNeteaseLogin = if (openLogin) true else _state.value.showNeteaseLogin
        )
        // 打开登录弹层时必须同步启动二维码流程，否则二维码一直显示"正在获取"，
        // 用户得手动点刷新才会加载（历史 bug）。
        if (openLogin && !NeteaseStore.isLoggedIn && _state.value.neteaseQrContent.isBlank()) {
            startNeteaseQrFlow()
        }
    }

    // ==================== 歌曲评论（只读无需登录，发表需登录） ====================

    /** 打开某首歌的评论弹层 */
    fun openComments(song: Song) {
        if (song.songId.isBlank()) {
            promptNeteaseLogin("该歌曲暂时没有可查看的评论", openLogin = false)
            return
        }
        // 6.6：QQ 音乐源没有网易云身份，拿 qq:<mid> 去查 /comment/music 必然返回空。
        // 先按「歌名 + 歌手」映射到网易云 id，评论借用网易云（歌词同理有兜底）。
        if (song.songId.startsWith("qq:")) {
            _state.value = _state.value.copy(
                commentsTitle = song.title,
                commentsSongId = "",
                commentsHot = emptyList(),
                commentsItems = emptyList(),
                commentsTotal = 0,
                commentsLoading = true,
                commentsNotice = ""
            )
            launchSafely {
                val id = withContext(Dispatchers.IO) {
                    // 不能用 runCatching { suspendFn() }：runCatching 的 lambda 不是 suspend。
                    try {
                        resolveNeteaseIdForOnlineSong(song)
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (id.isNullOrBlank()) {
                    _state.value = _state.value.copy(
                        commentsLoading = false,
                        commentsNotice = "这首是 QQ 音乐源，暂时没找到对应的网易云评论"
                    )
                } else {
                    openCommentsWithId(song, id)
                }
            }
            return
        }
        openCommentsWithId(song, song.songId)
    }

    private fun openCommentsWithId(song: Song, songId: String) {
        _state.value = _state.value.copy(
            commentsTitle = song.title,
            commentsSongId = songId,
            commentsHot = emptyList(),
            commentsItems = emptyList(),
            commentsTotal = 0,
            commentsPage = 0,
            commentsHasMore = false,
            commentsLoading = true,
            commentsNotice = ""
        )
        // 首屏缓存命中：同一首歌 5 分钟内重开直接出内容，不再转圈等网络
        commentsFirstPageCache?.let { (cachedId, entry) ->
            if (cachedId == songId && System.currentTimeMillis() - entry.first < 5 * 60_000L) {
                val c = entry.second
                _state.value = _state.value.copy(
                    commentsLoading = false,
                    commentsHot = c.hot,
                    commentsItems = c.items,
                    commentsTotal = c.total,
                    currentCommentCount = c.total,
                    commentsPage = 1,
                    commentsHasMore = c.hasMore
                )
                return
            }
        }
        loadComments(1)
    }

    /** 评论数缓存：songId -> 总数，避免切歌来回时反复请求 */
    private val commentCountCache = HashMap<String, Int>()

    /**
     * 评论首屏缓存：songId -> (时间, total, hot, items)。5 分钟内重开同一首歌的评论
     * 直接秒开 —— 用户反馈"打开评论速度特别慢"，二次打开不再等网络。
     */
    private var commentsFirstPageCache: Pair<String, Pair<Long, NeteaseApi.NsComments>>? = null

    /**
     * 拉取当前歌曲的评论总数（播放页评论按钮右上角角标）。
     * 带缓存，失败静默为 0；只在歌曲没被切走时写回状态，避免串台。
     */
    fun loadCommentCount(songId: String) {
        if (songId.isBlank()) {
            if (_state.value.currentCommentCount != 0) {
                _state.value = _state.value.copy(currentCommentCount = 0)
            }
            return
        }
        commentCountCache[songId]?.let {
            _state.value = _state.value.copy(currentCommentCount = it)
            return
        }
        launchSafely {
            val total = withContext(Dispatchers.IO) {
                runCatching { NeteaseApi.comments(songId, 1, 1).total }.getOrDefault(0)
            }
            commentCountCache[songId] = total
            if (_state.value.currentSong?.songId == songId) {
                _state.value = _state.value.copy(currentCommentCount = total)
            }
        }
    }

    fun closeComments() {
        _state.value = _state.value.copy(
            commentsTitle = "",
            commentsSongId = "",
            commentsHot = emptyList(),
            commentsItems = emptyList(),
            commentsNotice = ""
        )
    }

    /** 触底 / 点击「加载更多」时追加下一页 */
    fun loadMoreComments() {
        val s = _state.value
        if (s.commentsLoading || !s.commentsHasMore || s.commentsSongId.isBlank()) return
        loadComments(s.commentsPage + 1)
    }

    private fun loadComments(page: Int) {
        val songId = _state.value.commentsSongId
        if (songId.isBlank()) return
        _state.value = _state.value.copy(commentsLoading = true)
        launchSafely {
            val res = withContext(Dispatchers.IO) { NeteaseApi.comments(songId, page, 30) }
            // 弹层已关闭 / 已切到别的歌：丢弃这次结果，避免串台
            if (_state.value.commentsSongId != songId) return@launchSafely
            // 接口分页在精彩评论参与时会重叠，同一 commentId 可能在下一页再次出现；
            // 列表 key 必须唯一，这里按 id 去重（空 id 的用「下标」兜底保证不重复）。
            val prev = _state.value.commentsItems
            val merged = if (page == 1) res.items else {
                val seen = prev.mapTo(HashSet()) { it.id }
                prev + res.items.filter { it.id.isNotBlank() && seen.add(it.id) }
            }
            _state.value = _state.value.copy(
                commentsLoading = false,
                commentsHot = if (page == 1) res.hot else _state.value.commentsHot,
                commentsItems = merged,
                commentsTotal = res.total,
                currentCommentCount = res.total,
                commentsPage = page,
                commentsHasMore = res.hasMore
            )
            // 第 1 页写进缓存，重开同一首歌秒出
            if (page == 1) {
                commentsFirstPageCache = songId to (System.currentTimeMillis() to res)
            }
        }
    }

    /** 发表评论（未登录时提醒先去登录） */
    fun postComment(content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        if (!NeteaseStore.isLoggedIn) {
            _state.value = _state.value.copy(commentsNotice = "请先登录网易云账号")
            promptNeteaseLogin("登录网易云账号后即可发表评论")
            return
        }
        val songId = _state.value.commentsSongId
        if (songId.isBlank()) return
        launchSafely {
            val ok = withContext(Dispatchers.IO) { NeteaseApi.postComment(songId, text) }
            if (_state.value.commentsSongId != songId) return@launchSafely
            if (ok) {
                _state.value = _state.value.copy(commentsNotice = "评论已发表")
                loadComments(1)
            } else {
                _state.value = _state.value.copy(commentsNotice = "发表失败，请稍后重试")
            }
        }
    }

    /** 清掉评论操作提示（弹层关闭 / 输入时调用） */
    fun clearCommentNotice() {
        if (_state.value.commentsNotice.isNotEmpty()) {
            _state.value = _state.value.copy(commentsNotice = "")
        }
    }

    // ==================== 网易云：每日推荐 / 我的歌单 ====================

    /**
     * 打开「每日推荐」。
     * 6.3 大修版：**不再要求登录**。已登录网易云走官方日推；未登录时本地生成——
     * 拿「最近播放 + 我喜欢」的歌手/关键词做种子，在线搜出 20 首拼成一张日推歌单，
     * 每天种子不同（按日期轮换），体验和网易云日推一致且零门槛。
     */
    fun openNeteaseDaily(returnTo: String = "") {
        if (!NeteaseStore.isLoggedIn) {
            openLocalDaily(returnTo)
            return
        }
        _state.value = _state.value.copy(
            neteaseListTitle = "每日推荐",
            neteaseListSubtitle = "网易云 · 每日推荐",
            neteaseListSongs = emptyList(),
            neteaseListLoading = true,
            neteaseListNotice = "",
            neteaseListReturnTo = returnTo
        )
        launchSafely {
            val songs = withContext(Dispatchers.IO) {
                MusicRepository.fromNetease("每日推荐", NeteaseApi.recommendSongs())
            }
            if (_state.value.neteaseListTitle != "每日推荐") return@launchSafely
            _state.value = _state.value.copy(
                neteaseListLoading = false,
                neteaseListSongs = songs,
                neteaseListNotice = if (songs.isEmpty()) "暂时没拉到每日推荐，稍后重试" else "",
                // 6.3.2：首页那张「每日推荐」入口卡要用封面和首数，
                // 单独存一份预览（不复用 neteaseListSongs，避免和弹层互相覆盖）
                neteaseDailyPreview = if (songs.isNotEmpty()) songs.take(12) else _state.value.neteaseDailyPreview
            )
        }
    }

    /**
     * 免登录日推的进程内缓存：同一天内再点「每日推荐」直接秒出。
     * key = 当天日期 + 种子指纹，跨天或口味变了自动失效。
     */
    private var dailySeedKey: String = ""
    private var dailySongsCache: List<Song> = emptyList()

    /**
     * 免登录的「每日推荐」：本地种子 + 在线搜索。
     * 种子按日期轮换，保证"今天和昨天给的不一样"；种子不足（新装机没听过歌）时
     * 用热门关键词兜底，保证新用户也能看到一张能播的日推。
     *
     * 6.3.1 提速：不再「等 4 个关键词全部搜完才上屏」。第一个关键词一回来就先把
     * 前 12 首推到界面上，剩下 3 个在后台继续补，补一批刷一次——用户点进去
     * 0.6~1 秒内必看到歌，而不是盯着转圈等 3~5 秒。
     */
    private fun openLocalDaily(returnTo: String) {
        val dayShift = (System.currentTimeMillis() / 86400000L).toInt()
        // 本地读盘很快（有内存缓存），但放 IO 线程更稳
        val seeds = ArrayList<String>()
        PlayHistory.recentPlaybacks().forEach { rp ->
            rp.songs.firstOrNull()?.let { seeds.add(it.artist.ifBlank { it.title }) }
        }
        PlayHistory.likedSongs().forEach { seeds.add(it.artist.ifBlank { it.title }) }
        if (seeds.isEmpty()) seeds.addAll(listOf("热门金曲", "华语经典", "流行精选"))
        val picked = (0 until 4).map { i -> seeds[(dayShift * 3 + i) % seeds.size] }.distinct()
        val seedKey = dayShift.toString() + "#" + picked.joinToString(",")

        // 同一天同一批种子：直接复用上次结果，不再打网络
        if (seedKey == dailySeedKey && dailySongsCache.isNotEmpty()) {
            _state.value = _state.value.copy(
                neteaseListTitle = "每日推荐",
                neteaseListSubtitle = "根据你的口味生成 · 无需登录",
                neteaseListSongs = dailySongsCache,
                neteaseListLoading = false,
                neteaseListNotice = "",
                neteaseListReturnTo = returnTo
            )
            return
        }

        _state.value = _state.value.copy(
            neteaseListTitle = "每日推荐",
            neteaseListSubtitle = "根据你的口味生成 · 无需登录",
            neteaseListSongs = emptyList(),
            neteaseListLoading = true,
            neteaseListNotice = "",
            neteaseListReturnTo = returnTo
        )
        launchSafely {
            val out = LinkedHashMap<Long, Song>()
            withContext(Dispatchers.IO) {
                coroutineScope {
                    // 4 个种子并发搜，**谁先回来谁先上屏**。
                    // 注意不能用 jobs.await() 按创建顺序等——第一个关键词要是慢 3 秒，
                    // 后面已经回来的三批全被堵住，用户看到的还是转圈等 3 秒。
                    // 这里用 Channel 把「先完成的」按完成顺序吐出来。
                    val ch = kotlinx.coroutines.channels.Channel<List<Song>>(picked.size)
                    picked.forEach { kw ->
                        launch {
                            val page0 = runCatching {
                                MusicRepository.searchOnlinePage(getApplication(), kw, 12, 0).first
                            }.getOrDefault(emptyList())
                            ch.send(page0)
                        }
                    }
                    repeat(picked.size) {
                        val page0 = ch.receive()
                        if (page0.isEmpty()) return@repeat
                        val snapshot = synchronized(out) {
                            for (song in page0) {
                                out.put(song.id, song)
                                if (out.size >= 24) break
                            }
                            out.values.toList()
                        }
                        if (snapshot.isNotEmpty() && _state.value.neteaseListTitle == "每日推荐") {
                            withContext(Dispatchers.Main) {
                                if (_state.value.neteaseListTitle == "每日推荐") {
                                    _state.value = _state.value.copy(
                                        neteaseListLoading = false,
                                        neteaseListSongs = snapshot
                                    )
                                }
                            }
                        }
                    }
                    ch.close()
                }
            }
            val songs = synchronized(out) { out.values.toList() }
            if (songs.isNotEmpty()) {
                dailySeedKey = seedKey
                dailySongsCache = songs
            }
            if (_state.value.neteaseListTitle != "每日推荐") return@launchSafely
            _state.value = _state.value.copy(
                neteaseListLoading = false,
                neteaseListSongs = songs,
                neteaseListNotice = if (songs.isEmpty()) "暂时没生成出日推，稍后重试" else ""
            )
        }
    }

    /** 打开「网易云我喜欢」：拉取网易云账号的红心（我喜欢的音乐），需要登录 */
    fun openNeteaseLiked(returnTo: String = "") {
        if (!NeteaseStore.isLoggedIn) {
            promptNeteaseLogin("登录网易云账号后即可查看网易云我喜欢")
            return
        }
        _state.value = _state.value.copy(
            neteaseListTitle = "网易云我喜欢",
            neteaseListSubtitle = "网易云 · 我喜欢的音乐",
            neteaseListSongs = emptyList(),
            neteaseListLoading = true,
            neteaseListNotice = "",
            neteaseListReturnTo = returnTo
        )
        launchSafely {
            val songs = withContext(Dispatchers.IO) {
                val ids = NeteaseApi.likedSongIds(NeteaseStore.userId)
                MusicRepository.fromNetease("网易云我喜欢", NeteaseApi.songDetail(ids))
            }
            if (_state.value.neteaseListTitle != "网易云我喜欢") return@launchSafely
            _state.value = _state.value.copy(
                neteaseListLoading = false,
                neteaseListSongs = songs,
                neteaseListNotice = if (songs.isEmpty()) "网易云我喜欢暂时是空的" else ""
            )
        }
    }

    /**
     * 加载网易云「我喜欢」到独立状态 [neteaseLikedSongs]，供播放队列「我喜欢」标签
     * 切换来源时使用，不走 neteaseListTitle 弹层，避免与网易云曲目弹层冲突。
     */
    fun loadNeteaseLikedSongs() {
        if (!NeteaseStore.isLoggedIn) return
        if (_state.value.neteaseLikedSongs.isNotEmpty() || _state.value.neteaseLikedLoading) return
        _state.value = _state.value.copy(neteaseLikedLoading = true)
        launchSafely {
            val songs = withContext(Dispatchers.IO) {
                runCatching {
                    val ids = NeteaseApi.likedSongIds(NeteaseStore.userId)
                    MusicRepository.fromNetease("网易云我喜欢", NeteaseApi.songDetail(ids))
                }.getOrDefault(emptyList())
            }
            _state.value = _state.value.copy(
                neteaseLikedLoading = false,
                neteaseLikedSongs = songs
            )
        }
    }

    /**
     * 打开「歌单广场」：推荐歌单 + 我的歌单。
     *
     * R13 修正：**不再一进门就拦登录**。推荐歌单走 /personalized、排行榜走 /toplist，都是公开
     * 只读接口（needCookie=false），未登录一样能看完整列表；之前 `if(!isLoggedIn) 提示登录; return`
     * 把整个广场挡在登录页后面，用户点首页「更多」只看到扫码——要账号的其实只是「我的歌单」
     * 这一件事，不该连公开数据一起关进去。未登录时照常开弹层（我的歌单区为空、自然不画），
     * 「每日推荐 / 我喜欢」这类账号动作仍由各自方法逐个提示登录（点了才提示）。
     */
    fun openNeteasePlaylists(returnTo: String = "") {
        val loggedIn = NeteaseStore.isLoggedIn
        _state.value = _state.value.copy(
            showNeteasePlaylists = true,
            // 未登录且推荐池还没回来才转圈；登录了还要等 userPlaylists，保持 loading
            neteasePlaylistsLoading = !loggedIn && _state.value.neteaseRecPlaylists.isEmpty(),
            neteasePlaylistsReturnTo = returnTo
        )
        launchSafely {
            if (_state.value.neteaseRecPlaylists.isEmpty()) loadNeteaseHomeData()
            if (!loggedIn) {
                // 公开列表这一步到这就完了：不碰 userId，不发需要 cookie 的请求
                if (!_state.value.showNeteasePlaylists) return@launchSafely
                _state.value = _state.value.copy(neteasePlaylistsLoading = false)
                return@launchSafely
            }
            // 6.1.2：「我的歌单」收口到 refreshNeteaseMyPlaylists 一处（TTL + 单飞 + 退避 +
            // 可重试状态）。原来这里是一份独立实现：`runCatching{}.getOrDefault(emptyList())`
            // 把 cookie 过期、断网、500 全压成空列表，再 `if (mine.isNotEmpty()) 才覆盖界面`，
            // 于是界面永远停在旧列表上、也不说为什么——正是"新建的歌单在 App 里不存在"。
            // 6.2：进门这一发用 force —— 用户就是来看自己歌单的，站在 TTL 里给他看旧列表
            // 等于没修。代价是每次开弹层多一个 /user/playlist 请求，单飞槽位会挡住并发重复。
            refreshNeteaseMyPlaylists(force = true)
            startMineTicker()
            val rec = withContext(Dispatchers.IO) {
                runCatching {
                    NeteaseApi.recommendPlaylists()
                        .map { NeteasePlaylistItem(it.id, it.name, it.cover, it.playCount) }
                }.getOrDefault(emptyList())
            }
            if (!_state.value.showNeteasePlaylists) return@launchSafely
            if (rec.isNotEmpty()) saveHomeCache("recommend", rec)
            _state.value = _state.value.copy(
                neteasePlaylistsLoading = false,
                neteaseRecPlaylists = if (rec.isNotEmpty()) rec else _state.value.neteaseRecPlaylists
            )
        }
    }

    // ==================== 「我的歌单」刷新链路（6.1.2 重建） ====================
    //
    // 状态不进 PlayerUiState：那个 data class 有 125 个构造参数、已贴着 dex 寄存器上限，
    // 再加字段有 VerifyError 风险。这里用两块独立的 Compose State 承载，界面读 .value 自动订阅。
    // 6.1.1 的现场：用户在网易云建完歌单回到这里看不到，只能退出重登才出现。链路上断在三环——
    //   ① NeteaseApi.call 把 /user/playlist 缓存 10 分钟，而缓存 key 里带了 cookie，
    //      同一份 cookie 一直命中旧响应；重新登录换了 cookie → key 变 → 强制走网络 → "突然出现"；
    //   ② 拉回来 `getOrDefault(emptyList())` + `if (mine.isNotEmpty())` 两道吞掉，
    //      过期/失败与"真的没有歌单"不可区分，界面停在旧列表上还显示成"已加载完成"；
    //   ③ 磁盘副本 saveHomeCache("mine") 没有 TTL，冷启动先铺旧列表，而 openNeteasePlaylists
    //      又被 loadNeteaseHomeData 的 neteaseChartsLoading 单飞门挡住时根本不刷新。
    // 现在：TTL 30 秒（NeteaseApi.ttlFor）+ 显式刷新清缓存 + 三态结果 + 单飞 + 失败退避。

    /**
     * 两次自动刷新之间的最小间隔。
     *
     * 取得和 NeteaseApi 那条 /user/playlist 内存缓存同宽（30 秒）：再短也只是在同一条缓存上
     * 打转，打不出新数据；再长就成了"我在网易云刚建完，切回来还要等两分钟"。
     * 开弹层 / 首页轮询 / 下拉都汇到这一个口，单飞 + 退避保证不会打成请求风暴。
     */
    private val mineTtlMs = 30_000L

    /** 拉取失败后的退避窗口：20 秒内不再自动重试（显式刷新无视它） */
    private val mineBackoffMs = 20_000L

    private var mineLastOkAt = 0L
    private var mineRetryAt = 0L

    /**
     * 单飞槽位本身。
     *
     * 用 Job?.isActive 而不是布尔标志：协程被取消（界面关闭 / ViewModel 清理）时
     * 布尔标志没人负责复位，「我的歌单」会从此再也刷不动——这种卡死比多打一个请求严重得多。
     */
    private var mineJob: Job? = null

    /**
     * 广场开着时「我的歌单」自己换新的循环（6.2）。
     *
     * 为什么要有这条：光把 TTL 调短不够 —— 只有重新开一次弹层才会再打一次网络。
     * 用户在网易云那边建完歌单切回来，人还站在弹层里，看到的仍是进门那一刻的列表，
     * 体验上跟"新建的歌单在 App 里不存在"是同一件事。
     * 循环本身不判断业务，只每隔 TTL 叫一次 refreshNeteaseMyPlaylists
     * （TTL / 单飞 / 退避 / 三态都在那边收口）；弹层关掉或登录掉了就自己 cancel，
     * 所以不需要 open/close/选中 每一处都记得停它 —— 漏一处就是永久空转。
     */
    private var mineTicker: Job? = null

    /**
     * 开一条「我的歌单」自动换新的循环（只在广场开着、且界面在前台时跑，见 onUiPaused）。
     * 重复调用安全：同一时刻最多一条。
     */
    private fun startMineTicker() {
        if (mineTicker?.isActive == true) return
        mineTicker = launchSafely {
            while (isActive) {
                delay(mineTtlMs)
                if (!_state.value.showNeteasePlaylists || !NeteaseStore.isLoggedIn) cancel()
                else refreshNeteaseMyPlaylists(force = false)
            }
        }
    }

    /**
     * 界面回到前台（MainActivity.onResume）。
     *
     * 这条同时服务用户点名的那个场景：在网易云 App 里建完歌单，切回这边 ——
     * 广场还开着，人却站在旧列表上。以前只有"重新登录"会重拉，所以看起来像
     * "新单不存在"。现在回到前台且广场开着就立刻重打一次（force = true 会连
     * 接口侧那份 30 秒内存缓存一起清掉），并把自动换新接回来。
     */
    fun onUiResumed() {
        // 6.9.8：回前台补一次控制器连接——后台被拦的那次（ensureControllerConnected
        // 直接 return）在这里接上，避免"点歌没反应"。
        ensureControllerConnected()
        if (_state.value.showNeteasePlaylists && NeteaseStore.isLoggedIn) {
            refreshNeteaseMyPlaylists(force = true)
            startMineTicker()
        }
        // 回到前台顺带做一次设置脏检查：在设置里调完东西不必回设置页点「上传」
        tickSettingsSync()
        // 6.7.0：**回来后丢掉上一轮的"不可播"记忆**。
        //
        // 用户现场："后台待一会儿再进来，点任何一首歌都显示没版权，紧接着整个歌单都没了。"
        // 那其实不是版权问题：App 在后台被系统回收，回来时脚本还没装载好 / 网络刚恢复，
        // 头几首解析失败被判死，这些判定（本类 30 分钟、代理 5 分钟、解析层 2~60 分钟）
        // 全都还在，于是后面每一首都被"继承"成无版权。
        //
        // 这里在真正回到前台时统一清一遍。节流 20 秒：弹个权限框、切个输入法都会走 onResume，
        // 不能每次都清缓存（那会把正常播放的解析结果也丢掉，反而变慢）。
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundResetAt > 20_000L) {
            lastForegroundResetAt = now
            runCatching {
                unplayableAt.clear()
                skipStreak = 0
                lastSkipSongId = -1L
                _state.value = _state.value.withExtras {
                    copy(copyrightSkipStreak = 0, unplayableSongIds = emptySet())
                }
                com.jun.musiccloud.playback.AudioProxyServer.resetForForeground()
                launchSafelyIo {
                    runCatching { OnlineMusicApi.clearAllFailures() }
                }
            }
        }
    }

    /** 上一次"回前台清失败记忆"的时刻（节流用，见 [onUiResumed]） */
    private var lastForegroundResetAt = 0L

    /**
     * 界面离开前台（MainActivity.onPause）：停掉自动换新。
     *
     * 弹层的开关状态在 ViewModel 里，切到别的 App 后仍是 true，协程也会照跑 ——
     * 那就是后台每 30 秒一次没人看的请求。回来时 onUiResumed 会重新接上。
     */
    fun onUiPaused() {
        mineTicker?.cancel()
        mineTicker = null
        // 6.9.3：离开前台立刻落一次进度（平时是 5 秒节流一次）。
        // 用户按 Home 后进程可能随时被回收，最后这 0~5 秒的进度不能丢。
        runCatching { persistPlayback(throttle = false) }
        // 6.9.5：「最近播放」也同步落一次盘。原来只有 apply()（异步），
        // 点完歌立刻划掉后台时写盘请求可能还没落 flash，进程就没了 ——
        // 下次进来看到的是上一次成功写入的旧列表，用户描述为"最近播放变回之前的"。
        runCatching { com.jun.musiccloud.data.PlayHistory.flushRecentPlaybacks() }
    }

    /** 上一次「我的歌单」没拉成功：界面据此画"加载失败 · 点此重试"，而不是"就是这个列表" */
    val neteaseMineFailed = mutableStateOf(false)

    /** 失败原因文案（未授权 / 网络），与 [neteaseMineFailed] 成对出现 */
    val neteaseMineNotice = mutableStateOf("")

    /**
     * 拉「我的歌单」。
     *
     * @param force 显式刷新（下拉 / 点重试）：先清掉 NeteaseApi 那条 30 秒的内存缓存，
     *   保证"刚建完就点刷新"能拿到新列表；同时跳过 TTL 与退避窗口。
     */
    fun refreshNeteaseMyPlaylists(force: Boolean = false) {
        if (!NeteaseStore.isLoggedIn) return
        val now = System.currentTimeMillis()
        // 单飞：同一时刻多处（开弹层 / 首页自动加载 / 下拉）只留一个请求
        if (mineJob?.isActive == true) return
        if (!force) {
            if (now - mineLastOkAt < mineTtlMs) return
            if (now < mineRetryAt) return
        }
        if (force) {
            NeteaseApi.clearResponseCache("/user/playlist")
            mineLastOkAt = 0L
            mineRetryAt = 0L
        }
        mineJob = launchSafely {
            val res = withContext(Dispatchers.IO) {
                runCatching { NeteaseApi.userPlaylistsResult(NeteaseStore.userId) }
                    .getOrDefault(NeteaseApi.MinePlaylistsResult.Failed)
            }
            when (res) {
                is NeteaseApi.MinePlaylistsResult.Ok -> {
                    mineLastOkAt = System.currentTimeMillis()
                    mineRetryAt = 0L
                    neteaseMineFailed.value = false
                    neteaseMineNotice.value = ""
                    val mine = res.items.map { NeteasePlaylistItem(it.first, it.second, it.third) }
                    // 服务器说有几首就是几首：空列表也照写。原来"空就不覆盖"是为了
                    // 不让失败抖动洗掉列表，但那个"失败"在这里已经分不出去——
                    // 现在 Ok(empty) 是真没有，Failed/Unauthorized 走下面两支，不再靠保守掩盖。
                    _state.value = _state.value.copy(neteaseMyPlaylists = mine)
                    if (mine.isNotEmpty()) saveHomeCache("mine", mine) else clearHomeCache("mine")
                }
                NeteaseApi.MinePlaylistsResult.Unauthorized -> {
                    // 过期要清掉两份缓存：留着旧列表，用户会以为自己新建立的歌单"不存在"
                    mineRetryAt = System.currentTimeMillis() + mineBackoffMs
                    neteaseMineFailed.value = true
                    neteaseMineNotice.value = "网易云登录已过期，重新登录后才能看到最新歌单"
                    NeteaseApi.clearResponseCache("/user/playlist")
                    clearHomeCache("mine")
                    _state.value = _state.value.copy(neteaseMyPlaylists = emptyList())
                    SessionSignals.markExpired(SessionSignals.SOURCE_NETEASE)
                }
                NeteaseApi.MinePlaylistsResult.Failed -> {
                    mineRetryAt = System.currentTimeMillis() + mineBackoffMs
                    neteaseMineFailed.value = true
                    neteaseMineNotice.value = "我的歌单没拉到，可以点重试"
                    lastFailedAction = FailedAction.NETEASE_MINE
                    reportError(neteaseMineNotice.value)
                }
            }
        }
    }

    fun closeNeteasePlaylists() {
        // 广场一关就停表：循环自己也会在下一跳退出，但那一跳的 30 秒里没人看列表
        mineTicker?.cancel()
        mineTicker = null
        _state.value = _state.value.copy(showNeteasePlaylists = false, neteasePlaylistsReturnTo = "")
    }

    /** 选中某个网易云歌单 → 加载其曲目到曲目弹层 */
    // ==================== 6.9.8 模式探索 ====================

    /** 正在定位的模式关键词，防止重复请求 */
    private val modeCardJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 从搜索结果里挑最像「这个模式」的那张歌单。
     *
     * 6.9.10 修「古风里混进一堆不是古风的歌」：旧实现 `firstOrNull` 直接拿第一条，
     * 而网易云搜「古风」排在前面的常是「古风+流行+民谣 500首」这种大杂烩，
     * 点进去自然一半不对味。现在按「名字是否真的在讲这件事」打分：
     *  · 名字完全等于关键词 → 最高分；
     *  · 名字包含关键词 → 高分；
     *  · 关键词被拆开但每两个字都出现 → 中分；
     *  · 名字里带「合集 / 精选 / 100首 / 大全」→ 降权（大杂烩）；
     *  · 名字比关键词长很多 → 轻微降权（越长越不专）。
     * 全都不沾边的直接丢弃，宁可这张卡继续显示渐变底，也不塞一个错歌单。
     */
    private fun pickModePlaylist(
        keyword: String,
        list: List<NeteaseApi.NsPlaylistSearch>
    ): NeteaseApi.NsPlaylistSearch? = pickModePlaylists(keyword, list, 1).firstOrNull()

    /** 同一套打分，但返回**全部合格**的歌单（按分排序）—— 6.9.11 歌单池用 */
    private fun pickModePlaylists(
        keyword: String,
        list: List<NeteaseApi.NsPlaylistSearch>,
        max: Int
    ): List<NeteaseApi.NsPlaylistSearch> {
        if (list.isEmpty()) return emptyList()
        val k = keyword.lowercase().replace(" ", "").replace("　", "")
        if (k.isEmpty()) return list.filter { it.id.isNotBlank() }.take(max)
        fun score(p: NeteaseApi.NsPlaylistSearch): Int {
            val n = p.name.lowercase().replace(" ", "").replace("　", "")
            if (n.isEmpty()) return Int.MIN_VALUE
            var s = when {
                n == k -> 100
                n.contains(k) -> 50
                k.length >= 4 && k.chunked(2).all { n.contains(it) } -> 24
                else -> return Int.MIN_VALUE
            }
            s -= ((n.length - k.length).coerceAtLeast(0)) / 3
            if (n.contains("合集") || n.contains("精选") || n.contains("大全") ||
                n.contains("首") && (n.contains("100") || n.contains("500") || n.contains("300"))
            ) s -= 12
            if (p.cover.isNotBlank()) s += 4
            return s
        }
        return list.map { it to score(it) }
            .filter { it.second != Int.MIN_VALUE && it.first.id.isNotBlank() }
            .sortedByDescending { it.second }
            .take(max)
            .map { it.first }
    }

    /**
     * 进探索页时补齐模式卡：每个模式按关键词搜一个真实网易云歌单，
     * 卡片封面直接用歌单封面（用户："封面太丑了，跟主 UI 一样"）。
     * 4 个一批并发，谁先回来谁先上屏；搜不到的模式保持渐变底，不影响其它卡。
     */
    fun ensureModeCards(keywords: List<String>) {
        val missing = keywords.filter {
            it.isNotBlank() &&
                _state.value.extras.modeCards[it] == null &&
                !modeCardJobs.contains(it)
        }
        if (missing.isEmpty()) return
        modeCardJobs.addAll(missing)
        launchSafely {
            try {
                missing.chunked(4).forEach { batch ->
                    val results = batch.map { kw ->
                        async(Dispatchers.IO) {
                            runCatching {
                                // 6.9.11：一次拿一池合格歌单（打分排序），刷新轮换、打开拼接
                                pickModePlaylists(kw, NeteaseApi.searchPlaylists(kw, 30), 8)
                            }.getOrNull()
                        }
                    }.awaitAll()
                    results.forEachIndexed { i, pls ->
                        val kw = batch[i]
                        val pool = pls.orEmpty()
                        if (pool.isNotEmpty() && _state.value.extras.modeCards[kw] == null) {
                            val items = pool.map { NeteasePlaylistItem(it.id, it.name, it.cover) }
                            _state.value = _state.value.withExtras {
                                copy(
                                    modeCards = modeCards + (kw to items.first()),
                                    modePools = modePools + (kw to items)
                                )
                            }
                        }
                    }
                }
            } finally {
                modeCardJobs.removeAll(missing.toSet())
            }
        }
    }

    /**
     * 手动刷新全部模式卡：清空后按同一套打分重挑。
     * 用户要求"这个也出来个刷新功能吧"——歌单是别人在维护的，
     * 过一阵子换封面、改名、下架都很常见，给一颗按钮让人自己拉最新的。
     */
    /** 每个关键词已轮换到池里的第几个（内存态，进程内有效） */
    private val modePoolCursor = HashMap<String, Int>()

    fun refreshModeCards(keywords: List<String>) {
        modeCardJobs.clear()
        val pools = _state.value.extras.modePools
        val rotated = HashMap<String, NeteasePlaylistItem>()
        val newPools = HashMap<String, List<NeteasePlaylistItem>>()
        var rotatedCount = 0
        for (kw in keywords) {
            val pool = pools[kw].orEmpty()
            if (pool.size >= 2) {
                // 轮换：从池里取下一个没用过的歌单当卡面，刷新就有新内容
                val cur = (modePoolCursor[kw] ?: 0) + 1
                val next = pool[cur % pool.size]
                modePoolCursor[kw] = cur
                rotated[kw] = next
                rotatedCount++
            } else if (pool.isNotEmpty()) {
                newPools[kw] = pool
                rotated[kw] = pool.first()
            }
        }
        _state.value = _state.value.withExtras {
            copy(
                modeCards = modeCards + rotated,
                // 轮换过的池把用过的排到后面，打开模式时拼接的是"还没听过的"
                modePools = modePools + rotated.entries.associate { (kw, item) ->
                    val pool = (modePools[kw].orEmpty()).toMutableList()
                    pool.remove(item); pool.add(item)
                    kw to pool
                } + newPools
            )
        }
        // 池里没得换的模式重新搜一轮
        val missing = keywords.filter { pools[it].orEmpty().size < 2 && !modeCardJobs.contains(it) }
        if (missing.isNotEmpty()) ensureModeCards(missing)
        notice(if (rotatedCount > 0) "已换一批模式歌单" else "正在刷新模式歌单…")
    }

    /**
     * 点开一个模式：优先用已定位的网易云歌单（整单都是这个模式的内容）；
     * 还没定位就现场搜；搜索也失败再退到按关键词搜歌。
     */
    fun openMode(keyword: String, label: String) {
        _state.value.extras.modeCards[keyword]?.let { card ->
            openModePlaylist(keyword, card, label)
            return
        }
        launchSafely {
            val pl = withContext(Dispatchers.IO) {
                runCatching { pickModePlaylist(keyword, NeteaseApi.searchPlaylists(keyword, 30)) }.getOrNull()
            }
            if (pl != null) {
                val card = NeteasePlaylistItem(pl.id, pl.name, pl.cover)
                if (_state.value.extras.modeCards[keyword] == null) {
                    _state.value = _state.value.withExtras {
                        copy(modeCards = modeCards + (keyword to card))
                    }
                }
                openModePlaylist(keyword, card, label)
            } else {
                val songs = withContext(Dispatchers.IO) {
                    runCatching {
                        MusicRepository.fromNetease(label, NeteaseApi.searchSongsPaged(keyword, 1, 30))
                    }.getOrDefault(emptyList())
                }
                if (songs.isNotEmpty()) {
                    _state.value = _state.value.copy(
                        neteaseListTitle = label,
                        neteaseListSubtitle = "模式探索 · 按关键词匹配",
                        neteaseListSongs = songs,
                        neteaseListLoading = false,
                        neteaseListNotice = ""
                    )
                } else {
                    notice("「$label」暂时没有找到内容，稍后再试试")
                }
            }
        }
    }

    /**
     * 6.9.11 治「气氛模式歌太少」：点开模式不再只拉一个歌单。
     * 主歌单先上屏（秒开），随后并行拉歌单池里另外两个歌单，按歌曲 id
     * 去重合并进同一个列表 —— 一个模式轻松凑出两三百首，而且池子会随
     * 「刷新」轮换，每次听到的组合都不一样。
     */
    private fun openModePlaylist(keyword: String, card: NeteasePlaylistItem, label: String) {
        val pool = _state.value.extras.modePools[keyword].orEmpty()
        val extraPls = pool.filter { it.id != card.id }.take(2)
        _state.value = _state.value.copy(
            showNeteasePlaylists = false,
            neteaseListReturnTo = "playlists",
            neteaseListTitle = label,
            neteaseListSubtitle = "聚合多平台 · $label",
            neteaseListSongs = emptyList(),
            neteaseListLoading = true,
            neteaseListNotice = ""
        )
        launchSafely {
            val first = withContext(Dispatchers.IO) {
                runCatching { MusicRepository.fromNetease(label, NeteaseApi.playlistTracks(card.id)) }
                    .getOrDefault(emptyList())
            }
            if (_state.value.neteaseListTitle != label) return@launchSafely
            _state.value = _state.value.copy(
                neteaseListLoading = false,
                neteaseListSongs = first,
                neteaseListNotice = if (first.isEmpty()) "该歌单暂时没有可播放的歌曲" else ""
            )
            if (extraPls.isEmpty() || first.size >= 150) return@launchSafely
            val more = extraPls.map { pl ->
                async(Dispatchers.IO) {
                    runCatching { MusicRepository.fromNetease(label, NeteaseApi.playlistTracks(pl.id)) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
            if (_state.value.neteaseListTitle != label) return@launchSafely
            val seen = HashSet<Long>()
            val merged = buildList {
                for (song in first + more) {
                    if (song.id > 0L && seen.add(song.id)) add(song)
                }
            }
            if (merged.size > _state.value.neteaseListSongs.size) {
                _state.value = _state.value.copy(neteaseListSongs = merged)
            }
        }
    }

    fun selectNeteasePlaylist(id: String, name: String) {
        _state.value = _state.value.copy(
            showNeteasePlaylists = false,
            neteaseListReturnTo = "playlists",
            neteaseListTitle = name,
            // R13：来源不再点名单一平台（用户："不能后面提示是网易云的"）。
            neteaseListSubtitle = "聚合多平台 · $name",
            neteaseListSongs = emptyList(),
            neteaseListLoading = true,
            neteaseListNotice = ""
        )
        launchSafely {
            val songs = withContext(Dispatchers.IO) {
                MusicRepository.fromNetease(name, NeteaseApi.playlistTracks(id))
            }
            if (_state.value.neteaseListTitle != name) return@launchSafely
            _state.value = _state.value.copy(
                neteaseListLoading = false,
                neteaseListSongs = songs,
                neteaseListNotice = if (songs.isEmpty()) "该歌单暂时没有可播放的歌曲" else ""
            )
        }
    }

    fun closeNeteaseList() {
        _state.value = _state.value.copy(
            neteaseListTitle = "",
            neteaseListSubtitle = "",
            neteaseListSongs = emptyList(),
            neteaseListNotice = "",
            neteaseListReturnTo = "",
            // 直接返回主页：不再弹回「全部榜单 / 歌单」列表（用户反馈此为严重 bug）。
            // 需再次浏览可重新从入口打开。
            showNeteasePlaylists = false,
            showNeteaseToplist = false
        )
    }

    // ==================== 网易云排行榜（无需登录） ====================

    /**
     * 搜索页那排榜单卡片的预览数据：前 [count] 个榜、每个榜只留前 [PREVIEW_SONGS] 首。
     *
     * R4 把这里整个换成「并发 + 逐张上屏 + 磁盘副本」，因为用户反馈"榜单拉取特别慢"：
     *  - 旧实现每个榜走 playlistTracks()＝ detail(1000 首) + 20 批 /song/detail，
     *    5 个榜串行 = 100 多个请求，卡片半天出不来；现在每榜 1 个请求（playlistPreview）。
     *  - 旧实现要等 5 个榜全回才一次性上屏；现在谁先回来谁先变成卡片，第一张约 1 秒内就位。
     *  - 结果落一份精简磁盘副本，下次冷启动进搜索页直接铺上，不再干等网络。
     */
    private var toplistPreviewJob: Job? = null
    fun loadToplistPreviews(force: Boolean = false, count: Int = TOP_PREVIEW_COUNT) {
        if (toplistPreviewJob?.isActive == true) return
        // 「有内容就不重拉」要带上数量：只把默认值从 5 提到 10、这条早退不动的话，
        // 老缓存里那 5 张会一直用下去，用户看到的还是那一排。
        if (!force && _state.value.toplistPreviews.size >= count) return
        val tops = _state.value.neteaseToplists.ifEmpty {
            NeteaseApi.defaultToplists().map { NeteasePlaylistItem(it.first, it.second, it.third) }
        }.take(count)
        if (tops.isEmpty()) return
        _state.value = _state.value.copy(toplistPreviewsLoading = true)
        toplistPreviewJob = launchSafely {
            // 只收集，不在每回来一个榜时写 state：原来每来一张就整份 copy 一次，
            // 10 张 = 10 次整页重组，搜索页正是在这一串里最卡的。攒齐后一次上屏。
            val got = java.util.concurrent.ConcurrentHashMap<Int, ToplistPreview>()
            supervisorScope {
                tops.mapIndexed { idx, t ->
                    launch(Dispatchers.IO) {
                        val songs = runCatching {
                            MusicRepository.fromNetease(
                                t.name, NeteaseApi.playlistPreview(t.id, PREVIEW_SONGS)
                            ).take(PREVIEW_SONGS)
                        }.getOrDefault(emptyList())
                        if (songs.isEmpty()) return@launch
                        got[idx] = ToplistPreview(t.id, t.name, songs)
                    }
                }
            }
            val list = (0 until tops.size).mapNotNull { got[it] }
            _state.value = _state.value.copy(
                toplistPreviews = if (list.isNotEmpty()) list else _state.value.toplistPreviews,
                toplistPreviewsLoading = false
            )
            if (list.isNotEmpty()) savePreviewCache(list)
        }
    }

    /** 榜单预览落盘：只存卡片要的字段，整包几十 KB，冷启动秒铺。 */
    private fun savePreviewCache(list: List<ToplistPreview>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { p ->
                val songs = JSONArray()
                p.songs.take(PREVIEW_SONGS).forEach { s ->
                    songs.put(
                        JSONObject()
                            .put("sid", s.songId).put("ti", s.title).put("ar", s.artist)
                            .put("al", s.album).put("du", s.durationMs).put("cv", s.filePath)
                    )
                }
                arr.put(JSONObject().put("id", p.id).put("name", p.name).put("songs", songs))
            }
            neteaseHomePrefs().edit()
                .putString("toplist_previews", arr.toString())
                // 数量也进缓存指纹：判新时既要"没过期"也要"够张数"，
                // 否则从 5 张升到 10 张这件事会被旧缓存永久挡住
                .putInt("toplist_previews_n", list.size)
                .putLong("toplist_previews_at", System.currentTimeMillis())
                .apply()
        }
    }

    /** 读回上次的榜单预览（损坏/缺字段直接跳过那一条，不影响界面启动） */
    private fun loadPreviewCache(): List<ToplistPreview> = runCatching {
        val raw = neteaseHomePrefs().getString("toplist_previews", null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val name = o.optString("name")
            val list = o.optJSONArray("songs") ?: return@mapNotNull null
            val songs = (0 until list.length()).mapNotNull { j ->
                val s = list.optJSONObject(j) ?: return@mapNotNull null
                val sid = s.optString("sid")
                if (sid.isBlank()) return@mapNotNull null
                val title = s.optString("ti")
                val artist = s.optString("ar")
                val cover = s.optString("cv")
                Song(
                    id = MusicRepository.stableId(sid),
                    title = title,
                    artist = artist,
                    album = s.optString("al"),
                    durationMs = s.optLong("du"),
                    uri = android.net.Uri.parse(
                        com.jun.musiccloud.playback.AudioProxyServer.audioUrl(sid, title, artist)
                    ),
                    albumArtUri = cover.takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse(it) },
                    filePath = cover,
                    folderPath = name,
                    folderName = name,
                    songId = sid,
                    format = "在线"
                )
            }
            if (songs.isEmpty()) null else ToplistPreview(id, name, songs)
        }
    }.getOrDefault(emptyList())

    /** 打开「排行榜」弹层：列出所有官方榜单，点榜单进曲目弹层 */
    fun openNeteaseToplist() {
        _state.value = _state.value.copy(
            showNeteaseToplist = true,
            neteaseToplistLoading = true
        )
        launchSafely {
            // 首页还没数据时，打开弹层顺便触发一次完整加载（带缓存与重试）
            if (_state.value.neteaseToplists.isEmpty()) loadNeteaseHomeData()
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    NeteaseApi.toplists().map { NeteasePlaylistItem(it.first, it.second, it.third) }
                }.getOrDefault(emptyList())
            }
            if (!_state.value.showNeteaseToplist) return@launchSafely
            _state.value = _state.value.copy(
                neteaseToplistLoading = false,
                neteaseToplists = if (list.isNotEmpty()) mergeCustomToplists(list) else _state.value.neteaseToplists
            )
            if (list.isNotEmpty()) saveHomeCache("toplists", list)
        }
    }

    /**
     * 6.9.6：把内置扩充分类（网络热歌榜 / 红果热门BGM / 潮流风向 / 国风 / 民谣 / 摇滚）
     * 合并进服务器榜单全表。服务器 /toplist/detail 只回官方榜，直接覆盖会把
     * 6.9.5 新增的分类冲掉 —— 用户"分类在哪了"的根因。按 id 去重后插到
     * 热歌榜后面（首页榜单横排取前 12 个，插这个位置保证新分类在首页可见）。
     */
    private fun mergeCustomToplists(server: List<NeteasePlaylistItem>): List<NeteasePlaylistItem> {
        val haveIds = server.mapTo(HashSet()) { it.id }
        val extra = NeteaseApi.defaultToplists()
            .map { NeteasePlaylistItem(it.first, it.second, it.third) }
            .filter { it.id !in haveIds }
        if (extra.isEmpty()) return server
        val idx = server.indexOfFirst { it.name.contains("热歌") }
        return if (idx >= 0) {
            val out = server.toMutableList()
            out.addAll(idx + 1, extra)
            out
        } else extra + server
    }

    fun closeNeteaseToplist() {
        _state.value = _state.value.copy(showNeteaseToplist = false)
    }

    // ==================== 首页网易云数据：磁盘缓存 + 自动重试 ====================
    //
    // 之前的问题：冷启动网络稍慢/接口抽风时 toplists() 返回空，首页就永远停在
    // 「点击加载榜单」。现在三件事：
    //   1) 上一次成功的数据落盘，下次进软件先秒出缓存，再在后台刷新；
    //   2) 失败自动重试 3 次，不让一次抖动把首页变成占位；
    //   3) 只有拿到非空结果才覆盖界面，避免把已有封面刷没。

    private fun neteaseHomePrefs() =
        getApplication<Application>().getSharedPreferences("jun_netease_home", Context.MODE_PRIVATE)

    private fun saveHomeCache(key: String, list: List<NeteasePlaylistItem>) {
        if (list.isEmpty()) return
        runCatching {
            val arr = JSONArray()
            list.take(60).forEach { p ->
                arr.put(
                    JSONObject()
                        .put("id", p.id).put("name", p.name).put("cover", p.cover)
                        // 播放数也存下来：以前只存三个字段，冷启动第一帧铺的是缓存，
                        // 于是封面下方的「▷ 12.3万」整排都是空的，网络回来才突然长出数字。
                        .put("plays", p.playCount)
                )
            }
            neteaseHomePrefs().edit()
                .putString(key, arr.toString())
                // 存一份时间戳：这份封面列表是多久前的。榜单预览早就有判新逻辑（3 小时），
                // 推荐歌单以前没有，缓存永不过期，第一眼看到的可能是几天前的内容。
                .putLong("${key}_at", System.currentTimeMillis())
                .apply()
        }
    }

    /** 这份首页缓存是多少毫秒前存的；没存过返回 Long.MAX_VALUE（调用方按"很旧"处理） */
    private fun homeCacheAgeMs(key: String): Long {
        val at = neteaseHomePrefs().getLong("${key}_at", 0L)
        return if (at <= 0L) Long.MAX_VALUE else (System.currentTimeMillis() - at).coerceAtLeast(0L)
    }

    /**
     * 抹掉一份磁盘副本（连同时间戳）。
     *
     * 「我的歌单」在 cookie 过期或确认换账号时必须清：`restoreNeteaseHomeCache()` 读它
     * 是**不看 TTL** 的，冷启动第一帧就把上一个账号/上一次成功拉的列表铺上去，
     * 用户新建的歌单就"在 App 里不存在"。留着旧数据 = 把一次拉取失败伪装成"账号就是这样"。
     */
    private fun clearHomeCache(key: String) {
        runCatching { neteaseHomePrefs().edit().remove(key).remove("${key}_at").apply() }
    }

    private fun loadHomeCache(key: String): List<NeteasePlaylistItem> = runCatching {
        val raw = neteaseHomePrefs().getString(key, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) null
            else NeteasePlaylistItem(id, o.optString("name"), o.optString("cover"), o.optLong("plays"))
        }
    }.getOrDefault(emptyList())

    // ==================== 首页热门歌手（6.3.9） ====================

    /**
     * 首页不该出现的歌手。
     *
     * 头号是周杰伦：他的正版曲目在网易云基本全线下架，点进歌手页十首有八首播不出来，
     * 「热门歌手」摆在首页第一屏，点一次听不到就再没人点第二次了。
     * 这里按名字匹配（含各种常见写法），不写死 id——id 会变，名字不会。
     */
    private fun isExcludedArtist(name: String): Boolean {
        val n = name.trim().lowercase().replace(" ", "")
        if (n.isEmpty()) return true
        return EXCLUDED_ARTISTS.any { n == it || n.contains(it) }
    }

    /** 热门歌手请求进行中标记（loadHomeFeed 短路路径与下拉刷新可能并发触发） */
    @Volatile private var hotArtistsLoading = false

    /** 本次进程内刷新次数：轮换取人起点用，见 [refreshHotArtists] */
    @Volatile private var hotArtistsRefreshCount = 0

    /**
     * 首页热门歌手（6.7 重写）。
     *
     * 用户的原话：「热门歌手每次都不更新，有点假的感觉，要弄那种真的感觉」。
     * 两个根因：
     *   1. loadHomeFeed 有"今天拉过就 return"的短路，热门歌手被焊死成一天一份；
     *   2. 就算重拉，/top/artists 前 40 名本身几天都不变，每次固定取前 12 个，
     *      用户看到的永远是同一排头像——数据是真的，**观感**是假的。
     *
     * 修法：
     *   - 从 loadHomeFeed 拆出来，短路路径上超过 10 分钟就单独补刷，其余栏目不受影响；
     *   - 取人起点按刷新次数轮换（40 人池、每次前进 7 位，互质，40 次才转回同一组合），
     *     每次都能看到一批**不同但都是真在热榜上**的歌手。
     */
    fun refreshHotArtists() {
        if (hotArtistsLoading) return
        hotArtistsLoading = true
        launchSafely {
            try {
                val list = withContext(Dispatchers.IO) {
                    runCatching { NeteaseApi.topArtists(0, 40) }.getOrDefault(emptyList())
                }
                // 6.6.9：用户要求「热门歌手按大家搜得多的来，补不齐再随机/API 热门补」。
                // 先拿本机搜索热度最高的几位（真实用户行为），再拿网易云官方热榜补满。
                val searched = withContext(Dispatchers.IO) { resolveSearchedArtistCards(6) }
                val apiHot = list
                    .filterNot { isExcludedArtist(it.name) }
                    .let { raw ->
                        if (raw.any { it.score > 0L }) raw.sortedByDescending { it.score } else raw
                    }
                    .distinctBy { it.id }
                // 合并：搜索热度 → 官方热榜（同名去重）→ 还不够就随机补
                val merged = LinkedHashMap<String, NeteaseApi.NsArtist>()
                searched.forEach { a -> if (!isExcludedArtist(a.name)) merged.putIfAbsent(a.name, a) }
                apiHot.forEach { a -> if (merged.size < 12) merged.putIfAbsent(a.name, a) }
                if (merged.size < 12 && apiHot.isNotEmpty()) {
                    val rnd = java.util.Random(System.currentTimeMillis() + hotArtistsRefreshCount * 31L)
                    apiHot.shuffled(rnd).forEach { a -> if (merged.size < 12) merged.putIfAbsent(a.name, a) }
                }
                val filtered = merged.values.toList()
                if (filtered.isNotEmpty()) {
                    hotArtistsRefreshCount++
                    _state.value = _state.value.copy(
                        homeHotArtists = filtered,
                        hotArtistsFetchedAt = System.currentTimeMillis()
                    )
                    saveHomeArtistsCache(filtered)
                }
            } finally {
                hotArtistsLoading = false
            }
        }
    }

    // ==================== 搜索过的歌手（6.6.9） ====================

    /**
     * 记录"用户搜过哪些歌手"。
     *
     * 用户原话：「热门歌手一直不动，你这啥意思？这个是根据他们搜索的歌手来的，
     * 搜得多就显示在上面，补不齐再随机几个，或者用 API 的热门歌手接着加。」
     *
     * 落盘在 neteaseHomePrefs，键 [KEY_ARTIST_SEARCH_COUNTS]（名字 → 次数）。
     * 只留高频的前 40 个，避免无限膨胀；纯本地统计，不上传。
     */
    private fun bumpSearchedArtists(names: List<String>) {
        if (names.isEmpty()) return
        runCatching {
            val p = neteaseHomePrefs()
            val raw = p.getString(KEY_ARTIST_SEARCH_COUNTS, null)
            val obj = if (raw.isNullOrBlank()) JSONObject()
            else runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            names.map { it.trim() }
                .filter { it.isNotBlank() && it.length <= 24 && !it.contains("/") }
                .distinct().take(6)
                .forEach { n -> obj.put(n, obj.optInt(n, 0) + 1) }
            val trimmed = JSONObject()
            obj.keys().asSequence().sortedByDescending { obj.optInt(it, 0) }
                .take(40).forEach { trimmed.put(it, obj.optInt(it, 0)) }
            p.edit().putString(KEY_ARTIST_SEARCH_COUNTS, trimmed.toString()).apply()
        }
    }

    /**
     * 从单曲搜索结果里提炼"用户真正在找的歌手"。
     *
     * 不能无条件把结果里的歌手都记一笔：搜「反季节」会带出一堆翻唱歌手，
     * 全都算热度就把榜单刷脏了。判据取"在结果里反复出现"——前 10 条里
     * 同一歌手出现 ≥2 次，说明这次搜索就是冲着他来的（搜歌名时尤其准）。
     */
    private fun bumpSearchedArtistsFromSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        val counts = HashMap<String, Int>()
        songs.take(10).forEach { s ->
            s.artist.split("/", "、", "&", "，", ",")
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length <= 24 }
                .distinct()
                .forEach { n -> counts[n] = (counts[n] ?: 0) + 1 }
        }
        val hot = counts.entries.filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .map { it.key }
        if (hot.isNotEmpty()) bumpSearchedArtists(hot)
    }

    /** 按搜索次数取前 N 位歌手名（次数降序） */    private fun topSearchedArtistNames(limit: Int): List<String> = runCatching {
        val raw = neteaseHomePrefs().getString(KEY_ARTIST_SEARCH_COUNTS, null)
            ?: return emptyList()
        val obj = JSONObject(raw)
        obj.keys().asSequence()
            .filter { obj.optInt(it, 0) > 0 }
            .sortedByDescending { obj.optInt(it, 0) }
            .take(limit).toList()
    }.getOrDefault(emptyList())

    /**
     * 把"搜索过的歌手名"解析成可点击的歌手卡片（id + 头像）。
     *
     * 搜索结果里的歌只有歌手名、没有歌手 id/头像，所以这里按名字反查一次
     * `searchArtists`；解析结果按名字缓存进 [KEY_ARTIST_CARDS]，只查一次。
     * 查不到的名字直接跳过（不影响其它歌手展示）。
     */
    private fun resolveSearchedArtistCards(limit: Int): List<NeteaseApi.NsArtist> {
        val names = topSearchedArtistNames(limit)
        if (names.isEmpty()) return emptyList()
        val p = neteaseHomePrefs()
        val cacheRaw = p.getString(KEY_ARTIST_CARDS, null)
        val cache = if (cacheRaw.isNullOrBlank()) JSONObject()
        else runCatching { JSONObject(cacheRaw) }.getOrDefault(JSONObject())
        var dirty = false
        val out = ArrayList<NeteaseApi.NsArtist>()
        names.forEach { name ->
            val hit = cache.optString(name, "")
            if (hit.contains("|")) {
                val id = hit.substringBefore("|")
                val pic = hit.substringAfter("|")
                if (id.isNotBlank()) out.add(NeteaseApi.NsArtist(id, name, pic))
                return@forEach
            }
            val found = runCatching {
                NeteaseApi.searchArtists(name, 5)
                    .firstOrNull { it.name == name } ?: NeteaseApi.searchArtists(name, 5).firstOrNull()
            }.getOrNull()
            if (found != null) {
                cache.put(name, "${found.id}|${found.pic}")
                dirty = true
                out.add(NeteaseApi.NsArtist(found.id, name, found.pic))
            }
        }
        if (dirty) runCatching { p.edit().putString(KEY_ARTIST_CARDS, cache.toString()).apply() }
        return out
    }

    /** 首页热门歌手的磁盘副本（10 分钟 TTL：够快，又能"实时更新"） */
    private fun saveHomeArtistsCache(list: List<NeteaseApi.NsArtist>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { a ->
                arr.put(JSONObject().apply {
                    put("id", a.id); put("name", a.name); put("pic", a.pic); put("score", a.score)
                })
            }
            neteaseHomePrefs().edit()
                .putString("hot_artists", arr.toString())
                .putLong("hot_artists_at", System.currentTimeMillis())
                .apply()
        }
    }

    private fun loadHomeArtistsCache(): List<NeteaseApi.NsArtist> = runCatching {
        val raw = neteaseHomePrefs().getString("hot_artists", null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val name = o.optString("name")
            if (id.isBlank() || name.isBlank()) null
            else NeteaseApi.NsArtist(id, name, o.optString("pic"), o.optLong("score"))
        }
    }.getOrDefault(emptyList())

    private fun hotArtistsCacheAgeMs(): Long =
        System.currentTimeMillis() - neteaseHomePrefs().getLong("hot_artists_at", 0L)

    /** 冷启动先把上次的榜单/推荐封面铺上，用户看到的是内容而不是占位。 */
    private fun restoreNeteaseHomeCache() {
        val tops = loadHomeCache("toplists")
        val rec = loadHomeCache("recommend")
        val mine = loadHomeCache("mine")
        // 搜索页那排榜单卡的上次结果也一起铺上：进搜索页第一帧就是内容，不是骨架
        val previews = loadPreviewCache()
        // 6.3.9：热门歌手也有磁盘副本。冷启动第一帧就有头像，不等 /top/artists 回来。
        // 只在 30 分钟内新鲜时铺——再旧的头像和排名就该重新问了。
        val artists = if (hotArtistsCacheAgeMs() < 30 * 60 * 1000L) loadHomeArtistsCache()
        else emptyList()
        if (tops.isEmpty() && rec.isEmpty() && mine.isEmpty() && previews.isEmpty() && artists.isEmpty()) return
        _state.value = _state.value.copy(
            toplistPreviews = previews,
            neteaseToplists = if (tops.isNotEmpty()) mergeCustomToplists(tops) else _state.value.neteaseToplists,
            neteaseRecPlaylists = if (rec.isNotEmpty()) rec else _state.value.neteaseRecPlaylists,
            neteaseMyPlaylists = if (mine.isNotEmpty()) mine else _state.value.neteaseMyPlaylists,
            homeHotArtists = if (artists.isNotEmpty()) artists else _state.value.homeHotArtists
        )
    }

    /**
     * 榜单预览的磁盘副本还新不新（3 小时内）。
     * 热歌榜这种东西一天也就变一点点，冷启动没必要每次都重新打 5 个请求；
     * 超过 3 小时就让用户进搜索页时看到新的。
     */
    private fun previewCacheFresh(count: Int = TOP_PREVIEW_COUNT): Boolean {
        val p = neteaseHomePrefs()
        return p.getInt("toplist_previews_n", 0) >= count &&
            System.currentTimeMillis() - p.getLong("toplist_previews_at", 0L) < 3 * 60 * 60 * 1000L
    }

    /**
     * 后台预加载首页网易云内容（不弹层），启动与登录成功后调用。
     *
     * R9 起顺序反过来：首页第一眼是「推荐歌单」而不是榜单，所以退避预算跟着挪 ——
     * 推荐 6 次（0.5s→8s），榜单降到 3 次。榜单现在只服务搜索页那排卡片预览
     * 和抽屉里的「排行榜」入口，不再占首页首屏。
     */
    fun loadNeteaseHomeData() {
        // 开屏可能同时被 init、登录成功、榜单区自动重试调用：只跑一份，避免并发打接口。
        if (_state.value.neteaseChartsLoading) return
        _state.value = _state.value.copy(neteaseChartsLoading = true)
        launchSafely {
            try {
                // 冷启动网络还没就绪时，一次失败就停在「点击加载」是最伤体验的。
                // 这里给足 6 次退避（0.5s→8s，总计约 15.5s），弱网下也能自己恢复。
                var rec = emptyList<NeteasePlaylistItem>()
                for (attempt in 0..5) {
                    rec = withContext(Dispatchers.IO) {
                        runCatching {
                            NeteaseApi.recommendPlaylists()
                                .map { NeteasePlaylistItem(it.id, it.name, it.cover, it.playCount) }
                        }.getOrDefault(emptyList())
                    }
                    if (rec.isNotEmpty()) break
                    if (attempt < 5) delay(minOf(8_000L, 500L shl attempt))
                }
                if (rec.isNotEmpty()) {
                    _state.value = _state.value.copy(neteaseRecPlaylists = rec)
                    saveHomeCache("recommend", rec)
                }

                var tops = emptyList<NeteasePlaylistItem>()
                for (attempt in 0..2) {
                    tops = withContext(Dispatchers.IO) {
                        runCatching {
                            // 6.3.9：改用 toplistCards()，多拿「更新频率」给卡片做那一行细字，
                            // 请求次数不变（同一个 /toplist/detail）。
                            NeteaseApi.toplistCards().map {
                                NeteasePlaylistItem(
                                    id = it.id,
                                    name = it.name,
                                    cover = it.cover,
                                    playCount = it.playCount,
                                    subtitle = it.updateFrequency
                                )
                            }
                        }.getOrDefault(emptyList())
                    }
                    if (tops.isNotEmpty()) break
                    if (attempt < 2) delay(500L * (attempt + 1))
                }
                if (tops.isNotEmpty()) {
                    _state.value = _state.value.copy(neteaseToplists = tops)
                    saveHomeCache("toplists", tops)
                }

                // R4：这里原本会逐个拉 8 个榜的**完整**曲目（每榜 20 批 /song/detail），
                // 开机就是 160 个请求，把弱网和老机型的带宽全占光，搜索页那排卡片反而更慢。
                // 现在只预热搜索页要用的前 9 首（每榜 1 个请求，并发跑 + 落盘）；
                // 用户真点进某个榜要看整单时，selectNeteaseToplist 再去拉完整曲目。
                loadToplistPreviews(force = tops.isNotEmpty() && !previewCacheFresh())

                // 我的歌单：收口到 refreshNeteaseMyPlaylists（单飞 + TTL + 三态 + 失败退避）。
                // 原来这一份是第二处独立实现，同样是"空就保留旧缓存"，所以 cookie 过期时
                // 首页那一排"我的歌单"会一直铺着上一次的列表，看起来像加载成功了。
                refreshNeteaseMyPlaylists(force = false)
            } finally {
                _state.value = _state.value.copy(neteaseChartsLoading = false)
            }
        }
    }

    /**
     * 首页推荐歌单「划到底再拉一批」。
     *
     * `/personalized` 只认 `limit`、不认 offset，所以做法是拿更大的 limit 重申一次，
     * 再按 id 去重把新增的接在后面。服务端把返回条数钳死时（常见是 30），第二次
     * 就没有新增 —— 这时把 recommendAllLoaded 置真，UI 不再尝试，不会无限转圈。
     * 缓存超过 6 小时的话先静默换新整批，避免在一份很旧的列表后面续新内容。
     */
    fun loadMoreRecommendPlaylists() {
        val s = _state.value
        if (s.recsLoadingMore || s.recommendAllLoaded) return
        if (s.neteaseRecPlaylists.isEmpty()) { loadNeteaseHomeData(); return }
        // 旧缓存先换新：续拉出来的内容和六天前的封面混在一起会显得很乱
        if (homeCacheAgeMs("recommend") > 6 * 60 * 60 * 1000L) { loadNeteaseHomeData(); return }
        _state.value = _state.value.copy(recsLoadingMore = true)
        launchSafely {
            val known = _state.value.neteaseRecPlaylists
            val fresh = withContext(Dispatchers.IO) {
                runCatching {
                    NeteaseApi.recommendPlaylists(limit = (known.size + 30).coerceAtMost(90))
                        .map { NeteasePlaylistItem(it.id, it.name, it.cover, it.playCount) }
                }.getOrDefault(emptyList())
            }
            val merged = LinkedHashMap<String, NeteasePlaylistItem>()
            known.forEach { merged[it.id] = it }
            fresh.forEach { if (!merged.containsKey(it.id)) merged[it.id] = it }
            val out = merged.values.toList()
            _state.value = _state.value.copy(
                neteaseRecPlaylists = out,
                recsLoadingMore = false,
                // 一次都没有新增 = 服务端就给这么多，别再试了
                recommendAllLoaded = out.size == known.size
            )
            if (out.size > known.size) saveHomeCache("recommend", out)
        }
    }

    /** 选中某个榜单 → 复用歌单曲目加载逻辑（榜单本质是歌单） */
    fun selectNeteaseToplist(id: String, name: String) {
        _state.value = _state.value.copy(
            showNeteaseToplist = false,
            neteaseListReturnTo = "toplist",
            neteaseListTitle = name,
            // R13：同上，榜单来源写「聚合多平台」
            neteaseListSubtitle = "聚合多平台 · 排行榜 · $name",
            neteaseListSongs = emptyList(),
            neteaseListLoading = true,
            neteaseListNotice = ""
        )
        launchSafely {
            val songs = withContext(Dispatchers.IO) {
                MusicRepository.fromNetease(name, NeteaseApi.playlistTracks(id))
            }
            if (_state.value.neteaseListTitle != name) return@launchSafely
            _state.value = _state.value.copy(
                neteaseListLoading = false,
                neteaseListSongs = songs,
                neteaseListNotice = if (songs.isEmpty()) "该榜单暂时没有可播放的歌曲" else ""
            )
        }
    }

    /**
     * 搜索页榜单卡上的「播放」：把整个榜取回来再播（6.0）。
     *
     * 之前这里直接把预览数组丢给 [playList]，而预览只有 [PREVIEW_SONGS] 首 ——
     * 用户反馈的"点播放之后播放列表里就只有那九首歌"就是这么来的。榜单本质是歌单，
     * 走 [NeteaseApi.playlistTracks] 取全量（trackIds 上限 1000，按 50 首一片批量查详情），
     * 公开榜单不需要登录。取全量要几个来回，所以先给一条轻提示，拿不到就退回预览那几首，
     * 至少不会出现"点了什么反应都没有"。
     */
    fun playToplistFull(id: String, name: String, preview: List<Song> = emptyList()) {
        if (id.isBlank()) {
            if (preview.isNotEmpty()) playList(preview, title = name)
            return
        }
        if (toplistPlayInFlight == id) return          // 连点不去重会开两份请求
        toplistPlayInFlight = id
        _state.value = _state.value.copy(
            loginPrompt = "正在载入「$name」完整榜单…",
            loginPromptTick = _state.value.loginPromptTick + 1
        )
        launchSafely {
            val full = withContext(Dispatchers.IO) {
                runCatching { MusicRepository.fromNetease(name, NeteaseApi.playlistTracks(id)) }
                    .getOrDefault(emptyList())
            }
            toplistPlayInFlight = ""
            val songs = if (full.isNotEmpty()) full else preview
            if (songs.isEmpty()) {
                _state.value = _state.value.copy(
                    loginPrompt = "这个榜单暂时取不到歌曲",
                    loginPromptTick = _state.value.loginPromptTick + 1
                )
                return@launchSafely
            }
            if (full.isNotEmpty()) {
                _state.value = _state.value.copy(
                    loginPrompt = "已载入整榜 ${songs.size} 首",
                    loginPromptTick = _state.value.loginPromptTick + 1
                )
            }
            playList(songs, title = name)
        }
    }

    /** [playToplistFull] 的去重标记：同一个榜在取全量期间不重复开 */
    private var toplistPlayInFlight = ""

    // ==================== 相似歌曲推荐（无需登录） ====================

    /** 基于当前播放歌曲推荐相似歌曲，结果展示在网易云曲目弹层里 */
    fun openSimiSongs() {
        val cur = _state.value.currentSong ?: run {
            promptNeteaseLogin("播放一首歌曲后才能查看相似推荐", openLogin = false)
            return
        }
        if (cur.songId.isBlank()) {
            // 6.3 大修版：本地歌曲也能给相似推荐——按歌手在线搜一批，不再要求登录网易云
            openLocalSimiSongs(cur, returnTo = "")
            return
        }
        _state.value = _state.value.copy(
            neteaseListTitle = "相似推荐",
            neteaseListSubtitle = "基于「${cur.title}」",
            neteaseListSongs = emptyList(),
            neteaseListLoading = true,
            neteaseListNotice = ""
        )
        launchSafely {
            val songs = withContext(Dispatchers.IO) {
                MusicRepository.fromNetease("相似推荐", NeteaseApi.simiSongs(cur.songId))
            }
            if (_state.value.neteaseListTitle != "相似推荐") return@launchSafely
            _state.value = _state.value.copy(
                neteaseListLoading = false,
                neteaseListSongs = songs,
                neteaseListNotice = if (songs.isEmpty()) "暂时没有找到相似歌曲" else ""
            )
        }
    }

    /**
     * 免登录的相似推荐：拿当前歌的歌手名在线搜一批，去掉当前这首。
     * 网易云登录走官方 simiSongs；这条是本地歌曲 / 未登录时的兜底。
     */
    private fun openLocalSimiSongs(base: Song, returnTo: String) {
        _state.value = _state.value.copy(
            neteaseListTitle = "相似推荐",
            neteaseListSubtitle = "基于「${base.title}」",
            neteaseListSongs = emptyList(),
            neteaseListLoading = true,
            neteaseListNotice = "",
            neteaseListReturnTo = returnTo
        )
        launchSafely {
            val seedArtist = base.artist.split("/").firstOrNull()?.trim().orEmpty()
            val songs = withContext(Dispatchers.IO) {
                runCatching {
                    MusicRepository.searchOnlinePage(getApplication(), seedArtist.ifBlank { base.title }, 20, 0)
                        .first.filter { it.id != base.id }
                }.getOrDefault(emptyList())
            }
            if (_state.value.neteaseListTitle != "相似推荐") return@launchSafely
            _state.value = _state.value.copy(
                neteaseListLoading = false,
                neteaseListSongs = songs,
                neteaseListNotice = if (songs.isEmpty()) "暂时没有找到相似歌曲" else ""
            )
        }
    }

    /**
     * 相似歌曲：拉相似推荐，全部加入当前播放队列（下一首起依次排）。
     * 与 [openSimiSongs] 的区别：不弹列表面板，直接把歌塞进队列里接着听。
     *
     * [base] = 以哪首歌为种子。歌曲行的 ⋮ 传的是"被长按的那首"，它不一定是正在播的歌，
     * 所以这里必须收参数；不传（老调用点、播放页按钮）才回退到当前曲目。
     */
    fun enqueueSimiSongs(base: Song? = null) {
        val cur = base ?: _state.value.currentSong ?: run {
            _state.value = _state.value.copy(loginPrompt = "播放一首歌曲后才能获取相似推荐", loginPromptTick = _state.value.loginPromptTick + 1)
            return
        }
        if (cur.songId.isBlank()) {
            _state.value = _state.value.copy(loginPrompt = "当前歌曲不支持相似推荐", loginPromptTick = _state.value.loginPromptTick + 1)
            return
        }
        _state.value = _state.value.copy(loginPrompt = "正在获取相似歌曲…", loginPromptTick = _state.value.loginPromptTick + 1)
        launchSafely {
            val songs = withContext(Dispatchers.IO) {
                MusicRepository.fromNetease("相似推荐", NeteaseApi.simiSongs(cur.songId))
            }
            if (songs.isEmpty()) {
                _state.value = _state.value.copy(loginPrompt = "暂时没有找到相似歌曲", loginPromptTick = _state.value.loginPromptTick + 1)
                return@launchSafely
            }
            // 依次插在"正在播的下一首"起：先插第一首在 idx+1，再插第二首在 idx+2…
            // 用 playNext 已有的去重逻辑，避免重复加歌。
            // 注意插入锚点始终是当前曲目，不是 [base]：队列里没有按下标插歌的接口，
            // 要为"以队列中另一首为种子"新写一套下标插入，代价和风险都不值这一次改动。
            var insertIdx = _state.value.currentIndex
            for (s in songs) {
                playNext(s)
                insertIdx++
            }
            _state.value = _state.value.copy(
                loginPrompt = "已添加 ${songs.size} 首相似歌曲到播放队列",
                loginPromptTick = _state.value.loginPromptTick + 1
            )
        }
    }

// ==================== 睡眠定时器 ====================
    // 这里只负责：下发设置 + 把到期/恢复映射到 UI 状态。

    /** 设置睡眠定时器，minutes <= 0 视为取消 */
    fun setSleepTimer(minutes: Int) {
        val app = getApplication<Application>()
        if (minutes <= 0) {
            SleepTimer.cancel()
            _state.value = _state.value.copy(sleepTimerMs = 0L, sleepTimerEndMs = 0L)
            return
        }
        val totalMs = minutes * 60_000L
        val end = SleepTimer.set(app, minutes)
        _state.value = _state.value.copy(sleepTimerMs = totalMs, sleepTimerEndMs = end)
    }

    fun cancelSleepTimer() {
        SleepTimer.cancel()
        if (_state.value.sleepTimerMs > 0) {
            _state.value = _state.value.copy(sleepTimerMs = 0L, sleepTimerEndMs = 0L)
        }
        if (_state.value.sleepStopAfterSong) {
            _state.value = _state.value.copy(sleepStopAfterSong = false)
        }
    }

    /** 设置/取消"播完这首停"。开启会清掉分钟定时，二者互斥。 */
    fun setSleepStopAfterSong(enable: Boolean) {
        val app = getApplication<Application>()
        SleepTimer.setStopAfterSong(app, enable)
        _state.value = _state.value.copy(
            sleepStopAfterSong = enable,
            // 开启续播时清掉分钟定时显示，避免两个状态同时亮
            sleepTimerMs = if (enable) 0L else _state.value.sleepTimerMs,
            sleepTimerEndMs = if (enable) 0L else _state.value.sleepTimerEndMs
        )
    }

    // ==================== 听歌报告 ====================

    /**
     * 打开/关闭听歌报告。
     *
     * 每次打开都重读一次明细：音乐是在服务进程里记账的，UI 这边缓存下来会越看越旧
     * （挂后台听了一小时回来还是打开前的数字）。读文件 + 聚合都在 IO 线程。
     */
    fun toggleReport(show: Boolean) {
        _state.value = _state.value.copy(showReport = show)
        if (show) {
            loadReport(_state.value.report.range)
            // 打开报告顺手同步一次：把刚听的时长传上去，也把别的设备的数据拉回来
            if (AccountStore.isLoggedIn) {
                launchSafely { runCatching { syncListenStatsQuietly() } }
            }
        }
    }

    /** 切换日/周/月/年。明细已在内存里就地重算，不再碰磁盘 */
    fun setReportRange(range: StatRange) {
        val cached = statEntries
        if (cached != null) {
            _state.value = _state.value.copy(
                report = ListenReportBuilder.build(
                    entries = cached,
                    songs = _state.value.allSongs,
                    range = range,
                    meta = statCloudMeta,
                    legacyDaily = statLegacyDaily,
                    cloudSyncedAt = listenSyncedAt
                )
            )
        } else {
            loadReport(range)
        }
    }

    /** 从报告里点歌：按 ID 找回 Song 再走正常播放路径 */
    fun playSongById(songId: Long) {
        val song = _state.value.allSongs.firstOrNull { it.id == songId } ?: return
        playSong(song)
    }

    private fun loadReport(range: StatRange) {
        reportJob?.cancel()
        // loading 只在没有旧数据时置位：切范围时保留上一份，避免整块闪一下"统计中"
        if (statEntries == null) {
            _state.value = _state.value.copy(report = _state.value.report.copy(loading = true))
        }
        reportJob = launchSafely {
            val app = getApplication<Application>()
            val uid = AccountStore.userId
            val logged = AccountStore.isLoggedIn && uid > 0L
            val entries = withContext(Dispatchers.IO) {
                if (logged) runCatching { ListenCloud.mergedEntries(app, uid) }.getOrElse { ListenStats.load() }
                else ListenStats.load()
            }
            statEntries = entries
            if (logged) {
                statCloudMeta = withContext(Dispatchers.IO) { ListenCloud.cachedMeta(app, uid) }
                val snap = withContext(Dispatchers.IO) { ListenCloud.cachedSnapshot(app, uid) }
                statLegacyDaily = snap.legacyDaily.associate { it.day to it.ms }
                if (snap.updatedAt > listenSyncedAt) listenSyncedAt = snap.updatedAt
            } else {
                statLegacyDaily = emptyMap()
            }
            val report = withContext(Dispatchers.Default) {
                ListenReportBuilder.build(
                    entries = entries,
                    songs = _state.value.allSongs,
                    range = range,
                    meta = statCloudMeta,
                    legacyDaily = statLegacyDaily,
                    cloudSyncedAt = listenSyncedAt
                )
            }
            _state.value = _state.value.copy(report = report)
        }
    }

    /**
     * 库变化后重算已生成的报告。
     *
     * 明细里只有歌曲 ID，标题/艺术家/曲长都要现查 allSongs。库是异步加载的，
     * 若报告先建好，排行榜会整列显示"已移除的歌曲"，刷新库后新增的歌也对不上。
     */
    private fun refreshReportIfNeeded() {
        val entries = statEntries ?: return
        _state.value = _state.value.copy(
            report = ListenReportBuilder.build(
                entries = entries,
                songs = _state.value.allSongs,
                range = _state.value.report.range,
                meta = statCloudMeta,
                legacyDaily = statLegacyDaily,
                cloudSyncedAt = listenSyncedAt
            )
        )
    }

    // ==================== 内部实现 ====================

    private fun connectController() {
        val app = getApplication<Application>()
        val token = SessionToken(app, ComponentName(app, MusicService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()

        future.addListener({
            controllerConnecting = false
            runCatching {
                val c = future.get()
                c.addListener(listener)
                controller = c
                applyPlaybackModes()

                // R27：连接建立前用户点过的歌，优先补播（比恢复上次进度更贴近用户当下意图）
                val pend = pendingPlayIndex
                pendingPlayIndex = -1
                if (pend >= 0 && pend < _state.value.queue.size) {
                    pendingRestoreId = null
                    pendingRestorePos = 0L
                    launchSafely { playAt(pend) }
                    launchSafely { warmUpSync() }
                    return@runCatching
                }

                // 服务还活着（划后台再回来、Activity 被重建）：播放器里那首才是真相，
                // 用它覆盖 prefs 里的记录，免得恢复成更早的一首。
                c.currentMediaItem?.mediaId?.toLongOrNull()?.let { id ->
                    pendingRestoreId = id
                    pendingRestorePos = c.currentPosition.coerceAtLeast(0L)
                    // 服务还在：播放状态以服务为准，不用 prefs 的 auto-play 标记
                    pendingAutoPlay = false
                }

                // 这里不能无条件 pushQueue。连上的瞬间 UI 队列通常还是空的（库在异步加载中），
                // 而 pushQueue 见到空队列会 clearMediaItems，把服务里正在放的队列直接掐掉，
                // 随后 loadLibrary 又从第 0 首重建 —— "划后台回来变回第一首歌"就是这么来的。
                //
                // 队列已就绪才推：这条兜底是必要的，库有可能比连接先加载完，那一次
                // applyFilters 里的 pushQueue 会因为 controller 还是 null 而空转。
                if (_state.value.queue.isNotEmpty()) {
                    // 优先对齐要恢复的那首；找不到就退回队列现有下标
                    val restoreId = pendingRestoreId
                    val hit = if (restoreId != null) {
                        _state.value.queue.indexOfFirst { it.id == restoreId }
                    } else -1
                    pendingRestoreId = null
                    val startPos = if (hit >= 0) pendingRestorePos else c.currentPosition.coerceAtLeast(0L)
                    pendingRestorePos = 0L
                    pushQueue(
                        startIndex = if (hit >= 0) hit else _state.value.currentIndex.coerceAtLeast(0),
                        startPositionMs = startPos
                    )
                }
                launchSafely { warmUpSync() }
            }
        }, MoreExecutors.directExecutor())
    }

    /** 连接建立初期 controller 状态可能还没就绪，短轮询几次直到拿到有效索引 */
    private suspend fun warmUpSync() {
        repeat(10) {
            delay(200)
            syncFromController()
            if (_state.value.currentIndex >= 0) return
        }
    }

    /** 播放进度轮询：仅在播放中且进度变化时刷新，减少无谓重组 */
    private suspend fun tickPosition() {
        var lastPos = -1L
        // 听歌时长心跳：每累计满 5 秒上报一次；暂停 / 切歌时把尾巴补报，
        // 所以从第 1 秒开始都算，不会出现「听了 40 秒后台还是 0」。
        var listenAccumMs = 0L
        var lastListenTickAt = 0L
        var lastNowSongId = -1L
        while (true) {
            val c = controller
            if (c != null && c.isPlaying) {
                val tickNow = System.currentTimeMillis()
                if (lastListenTickAt > 0L) {
                    listenAccumMs += (tickNow - lastListenTickAt).coerceIn(0L, 1500L)
                }
                lastListenTickAt = tickNow
                // 队列重建窗口（随机/筛选）里索引错位：跳过这一拍，
                // 等下次轮询时队列与播放器对齐了再刷新进度
                val song = _state.value.currentSong
                val cid = c.currentMediaItem?.mediaId?.toLongOrNull()
                if (song != null && cid != null && song.id != cid) {
                    delay(200)
                    continue
                }
                if (listenAccumMs >= 5_000L) {
                    val secs = (listenAccumMs / 1000L).toInt()
                    if (secs > 0) {
                        Telemetry.reportListen(
                            getApplication(),
                            secs,
                            _state.value.currentSong?.title.orEmpty()
                        )
                        listenAccumMs -= secs * 1000L
                    }
                }
                // 当前播放歌曲：换歌时上报一次，后台用户详情直接显示
                val nowSong = _state.value.currentSong
                if (nowSong != null && nowSong.id != lastNowSongId) {
                    lastNowSongId = nowSong.id
                    Telemetry.reportNowPlaying(getApplication(), nowSong.title, nowSong.artist)
                    applyStageForSong(nowSong.id)
                }
                val pos = c.currentPosition
                // 6.9.3：恢复播放的那一瞬间 currentPosition 还是 0（seek 尚未落地），
                // 不能拿它盖掉刚刚恢复好的进度。
                val pendingSeek = pos <= 800L && _state.value.positionMs > 3_000L
                if (pos != lastPos && !pendingSeek) {
                    lastPos = pos
                    _state.value = _state.value.copy(
                        positionMs = pos,
                        durationMs = c.duration.coerceAtLeast(0L)
                    )
                    persistPlayback(throttle = true)
                }
                // 6.9.8：云端曲库回填放宽——累计听到全曲 80% 就转正，
                // 不再强等自然播完（STATE_ENDED）。用户经常在结尾淡出时切歌，
                // 严格等 ENDED 会让候选永远不转正（"云端曲库一直 27 首"）。
                val dur8 = _state.value.durationMs
                if (dur8 > 0L && pos > 0L && pos * 10L >= dur8 * 8L) {
                    cid?.let { com.jun.musiccloud.data.LibraryBackfill.markListenedEnough(it.toString()) }
                }
                // 6.9.12 治根：歌词时钟的采样以前只挂在 syncFromController（状态事件）上，
                // 正常播放时几乎不触发，一次小卡顿让外推领先之后就没人管了 ——
                // 用户听到的就是"歌词永远跑在前面"。这里并进 500ms 心跳：
                // 每拍都把锚点对齐一次真实位置，误差不可能累积。
                // 缓冲中（STATE_BUFFERING）传 false：位置在冻结，必须停止外推，
                // 否则 buffering 持续 2 秒，歌词就白白冲出去 2 秒。
                val ready = runCatching { c.playbackState }.getOrDefault(Player.STATE_IDLE) == Player.STATE_READY
                // pendingSeek 期间位置还是 0（seek 未落地），拿它当锚点会把歌词时钟
                // 一把甩回首句 —— 恢复播放时"歌词先跳回开头再追上来"就是这么来的。
                if (!pendingSeek) {
                    runCatching { notePlayerPosition(pos, ready && c.isPlaying) }
                }
            } else {
                // 6.9.12 治根（暂停仍在走的那一格）：
                // 这里以前什么都不做，posBasePlaying 还是上一次的 true，
                // livePositionMs() 就继续按墙上时间往前外推 —— 暂停十秒再续播，
                // 歌词已经跑到十秒之后，看起来就是"歌词一直在前面飞"。
                // 暂停瞬间必须把时钟冻在当前播放器位置。
                runCatching {
                    notePlayerPosition(c?.currentPosition ?: _state.value.positionMs, false)
                }
                // 暂停/无控制器：先把不满 5 秒的尾巴上报（1 秒也算），
                // 否则「听了几十秒就关」的那部分永远不会进后台统计。
                if (listenAccumMs >= 1_000L) {
                    val secs = (listenAccumMs / 1000L).toInt()
                    Telemetry.reportListen(
                        getApplication(),
                        secs,
                        _state.value.currentSong?.title.orEmpty()
                    )
                    listenAccumMs -= secs * 1000L
                }
                // 位置不会变，降频到 2s 一次只为感知播放恢复
                lastListenTickAt = 0L
                delay(2000)
                continue
            }
            delay(500)
        }
    }

    /**
     * 记录"当前在放哪首、放到哪儿"。
     *
     * 进程被杀之后 MediaSession 也没了，重启只能靠这份记录恢复，
     * 否则冷启动永远从第一首开始。写得很频繁没意义，默认每 5 秒落一次。
     */
    private fun persistPlayback(throttle: Boolean) {
        val song = _state.value.currentSong ?: return
        val now = System.currentTimeMillis()
        if (throttle && now - lastPersistMs < 5_000L) return
        lastPersistMs = now
        val queueIds = _state.value.queue.joinToString(",") { it.id.toString() }
        prefs.edit()
            .putLong(KEY_LAST_SONG, song.id)
            .putLong(KEY_LAST_POS, (controller?.currentPosition ?: _state.value.positionMs).coerceAtLeast(0L))
            .putBoolean(KEY_LAST_PLAYING, controller?.isPlaying == true)
            .putString(KEY_LAST_QUEUE_IDS, queueIds)
            .putInt(KEY_LAST_QUEUE_INDEX, _state.value.currentIndex.coerceAtLeast(0))
            .apply()
    }

    /**
     * 6.3.2：播放侧残片兜底——用「实际时长」和「歌曲真实时长」对账。
     *
     * 为什么解析层已经探测过大小，这里还要再判一次：
     * 解析层那次探测是拿文件的 Content-Length，手机网络下 CDN 超时/拒答都会返回 0，
     * 而我们没法把 0 当成残片（会误伤正常歌）。所以补一道**拿到手才算数**的校验：
     * 播放器读完文件头会给出真实时长，和网易云元数据一比，差太多就是残片。
     *
     * 一旦命中：跳过 meting（它只会再给同一个残片），直接向 GD 要完整文件，
     * 拿到就无缝换源，用户完全无感——不用再手动去切音质。
     *
     * 只对「元数据时长 ≥ 2 分钟」的歌生效，避免短曲/纯音乐误判。
     */
    private fun detectTrialByDuration(c: androidx.media3.common.Player) {
        val cur = _state.value.currentSong ?: return
        val meta = cur.durationMs
        if (meta < 120_000L) return
        val actual = c.duration
        if (actual <= 5_000L) return
        // 差 30% 以上才算残片：编码差异、结尾静音裁剪都不该触发
        if (actual >= meta * 0.7) return
        val id = cur.songId
        if (id.isBlank()) return
        if (!trialFixTried.add(id)) return
        launchSafely {
            val hit = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    OnlineMusicApi.resolveAudio(
                        id, cur.title, cur.artist, AudioQualityStore.current(),
                        preferFormat = null, skipFast = true
                    )
                }.getOrNull()
            }
            if (hit == null || hit.trial) {
                // 完整源也没有：说清楚，别让用户以为播放器坏了
                if (trialNotified.add(id)) {
                    notice("这首只有试听片段（版权限制），暂时拿不到完整版")
                }
                return@launchSafely
            }
            // 6.3.4：换源前必须先把这首歌的旧缓存字节清掉。
            // 缓存键是「歌曲ID + 档位」，不含地址；不清的话 reload 之后播放器
            // 仍然命中那份 30 秒残片，换源等于白换（这正是「改了也没用」的原因）。
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.jun.musiccloud.data.PlaybackCache.evictSong(id) }
            }
            AudioProxyServer.seedResolved(id, hit)
            runCatching { MusicService.instance?.reloadCurrentForQuality() }
        }
    }

    private fun syncFromController() {
        val c = controller ?: return
        // 6.3.2：残片兜底检测（放在最前面，拿到最新 duration 就判）
        runCatching { detectTrialByDuration(c) }

        // UI 的 currentIndex 必须落在【主页队列】里正在播的那首上。
        // 播放器时间线开着随机时是洗牌序，c.currentMediaItemIndex 与主页下标
        // 完全不对应——必须按 mediaId 反查主页队列下标，高亮才是对的。
        val cid = c.currentMediaItem?.mediaId?.toLongOrNull()
        val newIndex = if (cid != null) _state.value.queue.indexOfFirst { it.id == cid }
            .takeIf { it >= 0 } ?: c.currentMediaItemIndex else c.currentMediaItemIndex
        val newSong = _state.value.queue.getOrNull(newIndex)

        // 队列重建中途（筛选/收藏）：UI 队列已换新，controller 索引还指着
        // 旧位置，读到的 position/duration 是脏的。（按 id 对齐后此错位已基本消除，
        // 保留防御：id 都对得上才继续。）
        if (newSong != null && cid != null && newSong.id != cid) return

        // 切歌追踪：如果歌曲变了，记录上一首歌的播放时长
        if (newSong != null && newSong.id != prevSongId && prevSongId >= 0) {
            val playedMs = prevPlayedMs()
            val prevDuration = _state.value.allSongs.firstOrNull { it.id == prevSongId }?.durationMs ?: 0L
            PlayHistory.recordPlay(prevSongId, playedMs, prevDuration)
            // 已登录则把这首歌的实际收听时长上报服务器（后台统计「今天听了多久」）
            if (AccountStore.isLoggedIn && playedMs > 0L) {
                val prevSong = _state.value.queue.firstOrNull { it.id == prevSongId }
                    ?: _state.value.allSongs.firstOrNull { it.id == prevSongId }
                if (prevSong != null) launchSafely { AuthApi.pushPlay(prevSong, playedMs) }
            }
        }
        if (newSong != null && newSong.id != prevSongId) {
            prevSongId = newSong.id
            // 新曲从头走表：闭合段清零，当前段按播放器状态开启
            prevAccumMs = 0L
            prevLegStartElapsed = if (c.isPlaying) android.os.SystemClock.elapsedRealtime() else 0L
            // 切歌是关键节点，不走限流，立刻落盘
            // 统计上报：同一设备 5 分钟内只报一次，后台用于「今日播放」看板
            runCatching { Telemetry.report(getApplication(), "play", newSong.title) }
            _state.value = _state.value.copy(currentIndex = newIndex)
            persistPlayback(throttle = false)
        }

        // 6.6.9：顺手刷新歌词平滑时钟的锚点。
        // 6.9.12：把"是否真的在推进"和 isPlaying 分开判 —— buffering 时 isPlaying 仍为 true，
        // 位置却冻结；继续外推等于让歌词自己往前跑（用户："歌词老是跑得快"）。
        runCatching {
            val ready = c.playbackState == Player.STATE_READY
            notePlayerPosition(c.currentPosition, ready && c.isPlaying)
        }

        _state.value = _state.value.copy(
            currentIndex = newIndex,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = c.duration.coerceAtLeast(0L)
        )
        // 6.9.8：正在听的歌若属于某个本地歌单，首页那张歌单卡的封面跟着换
        if (newSong != null && newSong.id != prevSongId) refreshHomeCovers()

        // 切歌后不自动刷新推荐——推荐保持稳定，手动刷新或点赞时才更新
    }

    /** 基于用户行为生成推荐（独立打分，不依赖当前歌曲）。候选池跟随选中文件夹。 */
    private fun updateRecommendations() {
        val pool = recommendationPool()
        if (pool.isEmpty()) return
        // 「最近播放」初始 3 首：从候选池里随机抽 3 首（纯随机，不打分），
        // 后续用户在首页点的歌会追加进来（见 addToRecommendationsIfAppropriate）。
        val shuffled = pool.shuffled()
        val recs = shuffled.take(3)
        _state.value = _state.value.copy(
            recommendations = recs,
            recommendationsVersion = _state.value.recommendationsVersion + 1
        )
    }

    /**
     * 推荐候选池：选了文件夹就只从那几个文件夹里出。
     * 推荐区应当反映"我正在看的范围"——用户圈定 L1 后还总推荐别目录的歌，
     * 体验上就像推荐不认筛选。排行/点赞等打分逻辑不变，只是候选集收窄。
     */
    private fun recommendationPool(): List<Song> {
        val all = _state.value.allSongs
        val sel = _state.value.selectedFolders
        return if (sel.isEmpty()) all else all.filter { it.folderPath in sel }
    }

    /** 库加载完成/刷新后调用 */
    private fun onLibraryChanged() = updateRecommendations()


    fun setExploration(value: Float) {
        val v = value.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_EXPLORATION, v).apply()
        _state.value = _state.value.copy(exploration = v)
        // 探索度变化后立即刷新推荐
        updateRecommendations()
    }

    private fun applyPlaybackModes() {
        val c = controller ?: return
        // 随机播放由本 App 自己重排【播放器时间线】实现（playOrder 的偏好加权洗牌），
        // 所以播放器的 shuffleModeEnabled 必须保持关闭。
        // 两者同时开会叠加两层随机：播放器在已洗好的队列上再套一个均匀随机序，
        // 偏好权重被抹掉，而且"下一首"跳到的行和列表里高亮行的下一行不是同一首。
        c.shuffleModeEnabled = false
        c.repeatMode = when (_state.value.repeatMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    /** 在线曲库 + 本地 SAF 曲库；本地曲目 id 由 URI 稳定哈希，撞号时让在线曲目优先 */
    private fun mergeLocal(online: List<Song>, local: List<Song>): List<Song> {
        if (local.isEmpty()) return online
        val ids = online.mapTo(HashSet(online.size)) { it.id }
        return online + local.filter { it.id !in ids }
    }

    private suspend fun loadLibrary() {
        // 秒开：先只读元数据缓存直接出歌（毫秒级），不等在线拉取。
        val cachedOnline = MusicRepository.loadFromCache(getApplication())
        val local = LocalLibrary.cached(getApplication())
        val cached = mergeLocal(cachedOnline, local)
        if (cached.isNotEmpty()) {
            _state.value = _state.value.copy(        allSongs = cached,
        folders = MusicRepository.buildFolders(cached),
        librarySeed = System.nanoTime(),
        loading = false).withExtras { copy(localSongCount = local.size) }
            reconcileLikedSongs(cached)
            applyFilters(resetToFirst = true)
            updateRecommendations()
            refreshReportIfNeeded()
        }

        // 后台校正：重新拉取在线榜单一次（曲目变动会在这里被纠正并回写缓存）。
        // 缓存为空（首次安装/清过数据）时这就是唯一数据来源，需要显示加载态。
        if (cached.isEmpty()) _state.value = _state.value.copy(loading = true)
        // 在线曲库是唯一数据来源，拉不到就等于"打开是个空 App"——这必须让用户看见，
        // 而不是默默显示 0 首。失败时给一条带「重试」的提示，重试即重跑 loadLibrary。
        val online = try {
            MusicRepository.loadSongs(getApplication())
        } catch (t: Throwable) {
            lastFailedAction = FailedAction.LIBRARY
            reportError("曲库加载失败，检查一下网络")
            if (cached.isNotEmpty()) return else emptyList()
        }
        val localNow = LocalLibrary.cached(getApplication())
        val songs = mergeLocal(online, localNow)
        // 榜单接口整体失败时 fetchChart 返回空列表而不是抛异常。此时绝不能用空结果
        // 覆盖已经加载好的缓存曲库，否则首页会突然只剩「音乐榜」，看起来像歌全丢了。
        if (online.isEmpty() && cached.isNotEmpty()) {
            _state.value = _state.value.copy(loading = false)
            return
        }
        if (songs.isEmpty()) {
            _state.value = _state.value.copy(loading = false)
            lastFailedAction = FailedAction.LIBRARY
            reportError("曲库加载失败，检查一下网络")
            return
        }
        // 拉取结果与缓存版一致时不重建队列，避免"进来又跳一下/闪一下"。
        if (cached.isNotEmpty() && songs.size == cached.size &&
            songs.map { it.id }.toSet() == cached.map { it.id }.toSet()
        ) {
            _state.value = _state.value.copy(loading = false).withExtras { copy(localSongCount = localNow.size) }
            return
        }
        _state.value = _state.value.copy(        allSongs = songs,
        folders = MusicRepository.buildFolders(songs),
        librarySeed = System.nanoTime(),
        // 库有增删移动时重新拉一遍按 ID 记账的状态：文件移动会触发 ID 迁移，
            // 点赞/歌单不重读的话，收藏心标和歌单内容会停在旧 ID 上显示丢失。
            likedSongIds = PlayHistory.getLikes(),
        playlists = Playlists.all(),
        loading = false).withExtras { copy(localSongCount = localNow.size) }
        reconcileLikedSongs(songs)
        applyFilters(resetToFirst = true)
        // 随机序由 applyFilters→pushQueue 内部按 shuffle 状态自动写入播放器时间线，
        // 主页队列始终保持排序。
        // 首次库加载完成即生成推荐（不依赖当前歌曲）
        updateRecommendations()
        // 库比报告晚到时（打开报告后才扫完库）补一次，避免排行榜显示"已移除的歌曲"
        refreshReportIfNeeded()
    }

    /**
     * 按 文件夹 → 搜索 → 排序 重建队列。
     * 当前曲目仍在结果中时保留它的播放位置，否则按 resetToFirst 决定是否从头开始。
     *
     * [listContentChanged]：这次重算是因为"用户自己那份列表的内容变了"（往当前歌单加了
     * 一首、取消赞、云端歌单同步回来）。这种调用必须穿透下面的会话保护，否则用户加完歌
     * 列表里看不到；后台曲库刷新那几处都不传，保持被保护。
     */
    private fun applyFilters(resetToFirst: Boolean, ignoreQuery: Boolean = false, listContentChanged: Boolean = false, browseOnly: Boolean = false) {
        val s = _state.value
        val playing = s.currentSong

        // ===== homeSongs：首页纵向列表专用，永远是全部音乐（文件夹/排序/搜索过滤） =====
        // 不受「我喜欢」/歌单视图影响，保证首页永远展示推荐歌单 + 排行榜 + 全部歌曲。
        var homeList = s.allSongs
        val homeQuery = if (ignoreQuery) "" else s.searchQuery.trim().lowercase()
        if (homeQuery.isNotEmpty()) {
            homeList = homeList.filter {
                it.title.lowercase().contains(homeQuery) ||
                    it.artist.lowercase().contains(homeQuery) ||
                    it.album.lowercase().contains(homeQuery)
            }
        } else if (s.selectedFolders.isNotEmpty()) {
            homeList = homeList.filter { it.folderPath in s.selectedFolders }
        }
        val seed = s.librarySeed
        homeList = when (s.sortOrder) {
            SortOrder.TITLE -> homeList.sortedBy { (it.id xor seed).hashCode() }
            SortOrder.ARTIST -> homeList.sortedBy { it.artist.lowercase() }
            SortOrder.DURATION -> homeList.sortedBy { it.durationMs }
            SortOrder.RECENT -> homeList.sortedBy { File(it.filePath).lastModified() }
        }
        if (s.sortDirection == SortDirection.DESCENDING) homeList = homeList.asReversed()

        // ===== queue：播放队列，受「我喜欢」/歌单视图影响 =====
        var list = s.allSongs
        val query = if (ignoreQuery) "" else s.searchQuery.trim().lowercase()
        // 这份列表的顺序是用户/上一次会话定下来的，不该再被排序档重排（见下面 userListView 那段）
        var sessionOrderPinned = false

        // 杀进程重启恢复：用保存的队列 ID 还原完整播放队列，保证上下切歌不断。
        // 只在首次库加载（pendingRestoreQueueIds 非空）时生效，之后清空。
        val savedQueueIds = pendingRestoreQueueIds
        if (savedQueueIds != null) {
            val byId = list.distinctBy { it.id }.associateBy { it.id }
            val restored = savedQueueIds.mapNotNull { id -> byId[id] ?: PlayHistory.knownSong(id) }
            if (restored.isNotEmpty()) {
                list = restored
                pendingRestoreQueueIds = null
                // 恢复出来的这份是"上次退出时用户真正在听的那条列表"，不是浏览视图：
                // 打上会话标记，免得紧接着的后台曲库校正（loadLibrary 尾部那次 applyFilters）
                // 把它换成整个曲库——冷启动后"播完这首跳到别的音乐"就是这条路径。
                pinQueueSession()
                sessionOrderPinned = true
            } else {
                pendingRestoreQueueIds = null
            }
        } else if (query.isEmpty()) {
            val activePid = _state.value.activePlaylistId
            if (activePid != null) {
                val byId = list.distinctBy { it.id }.associateBy { it.id }
                // 同 songsOfPlaylist：先把 knownSongs 建成 map，别在循环里线性扫
                val knownMap = PlayHistory.knownSongs().distinctBy { it.id }.associateBy { it.id }
                list = Playlists.songs(activePid).mapNotNull { id ->
                    byId[id] ?: knownMap[id] ?: Playlists.songById(id)
                }
            } else {
                if (_state.value.onlyLiked) {
                    list = _state.value.likedSongs
                }
                if (_state.value.selectedFolders.isNotEmpty()) {
                    list = list.filter { it.folderPath in _state.value.selectedFolders }
                }
            }
        } else {
            list = list.filter {
                it.title.lowercase().contains(query) ||
                    it.artist.lowercase().contains(query) ||
                    it.album.lowercase().contains(query)
            }
        }

        // R13：「我喜欢」/自定义歌单这两份列表**不参与排序重排**。
        // 为什么：播放列表弹层里那两页画的是 state.likedSongs 和 songsOfPlaylist(pid)
        // （收藏顺序 / 加入歌单顺序），点第 k 行走 playFromLiked(k) / playFromPlaylist(pid, k)，
        // 下标全按那份顺序算。而这里过去对所有列表都套一遍 sort：TITLE 档用的还是
        // `(id xor librarySeed).hashCode()` 这个**首页打乱种子**（本意只是让"全部音乐"
        // 每次刷新换个脸），于是后台任何一次 applyFilters（曲库在线校正、本地扫描完成、
        // 云歌单同步）都把播放队列重排成另一种顺序并重写时间线——
        // 用户在歌单里点的是第 3 首，播完这首却按重排后的顺序走了"别的音乐"，
        // 也就是「顺序播放不顺序、下一首跳到别的歌」。首页那份（homeSongs）继续用种子打乱，
        // 它和 queue 在这一档下必须一致，所以只有"浏览全部音乐"这条路保留打乱。
        // 冷启动恢复出来的上次会话队列（sessionOrderPinned）同理：那份顺序就是上次真实收听的顺序，
        // 重排之后"上一首 / 下一首"会回到别的歌上。
        val userListView = query.isEmpty() && (s.activePlaylistId != null || s.onlyLiked)
        if (!userListView && !sessionOrderPinned) {
            list = when (s.sortOrder) {
                SortOrder.TITLE -> list.sortedBy { (it.id xor seed).hashCode() }
                SortOrder.ARTIST -> list.sortedBy { it.artist.lowercase() }
                SortOrder.DURATION -> list.sortedBy { it.durationMs }
                SortOrder.RECENT -> list.sortedBy { File(it.filePath).lastModified() }
            }
            if (s.sortDirection == SortDirection.DESCENDING) list = list.asReversed()
        }

        // 图书馆模式：播放队列由预筛引擎维护（只放确认过的纯音乐）。
        // 浏览筛选（搜索 / 文件夹 / 我喜欢）只更新首页列表，不把未筛过的歌塞回播放队列。
        if (s.libraryMode) {
            _state.value = _state.value.copy(homeSongs = homeList)
            if (libraryAutoPrepare && _state.value.queue.isEmpty() && homeList.isNotEmpty()) {
                libraryAutoPrepare = false
                val autoPlay = pendingAutoPlay
                val restoreId = pendingRestoreId
                if (controller != null) {
                    pendingAutoPlay = false
                    pendingRestoreId = null
                    pendingRestorePos = 0L
                }
                val preferred = _state.value.currentSong
                    ?: restoreId?.let { id ->
                        list.firstOrNull { it.id == id } ?: homeList.firstOrNull { it.id == id }
                    }
                startLibrarySession(
                    source = buildLibrarySource(list, homeList),
                    preferred = preferred,
                    wasPlaying = false,
                    autoPlay = autoPlay
                )
            }
            return
        }

        // 6.6：仅切换浏览视图（goHome 返回首页）。homeList 上面已经按新的筛选算好，
        // 这里只把它贴到界面上，queue / currentIndex / MediaController 时间线全部不动。
        // 必须放在 pinnedQueueSession 判断之前：activePlaylistId 一变，那条保护的
        // 视图签名就对不上了，会掉到下面去重建队列。
        if (browseOnly) {
            _state.value = _state.value.copy(homeSongs = homeList)
            return
        }

        // R13：显式会话队列保护。
        // 到这里的调用分两类：一类是用户主动换了浏览视图/筛选/排序，另一类是后台异步回调
        // （曲库在线校正、本地扫描完成、云歌单同步…）。
        // 第二类不该动用户正在听的这份列表：过去它会静默把队列换成整个曲库并重写
        // 播放器时间线，播完当前这首就走到"别的音乐"——正是本轮报的顺序播放 bug。
        // 保护在"浏览视图签名一变"就自动失效：所以不用去每个 换视图/换筛选/换排序 的入口
        // 里补一句取消保护（漏一个就是一个新的 bug），签名对不上就照常重算。
        // 首页浏览列表 homeSongs 一律照常更新，曲库刷新在界面上仍然看得见。
        // 判据里带上 queue.isNotEmpty()：playList 刚建好队列那一刻 currentSong 还是 null
        // （回调要等播放器确认），这半秒里来的后台刷新一样会把刚选的列表换掉。
        if (!listContentChanged && pinnedQueueSession &&
            (s.queue.isNotEmpty() || playing != null) &&
            pinnedQueueView == browseViewKey(s)
        ) {
            _state.value = _state.value.copy(homeSongs = homeList)
            return
        }

        val keptIndex = if (playing != null) list.indexOfFirst { it.id == playing.id } else -1

        // 首次建队列时按上次那首歌定位。库是异步加载的，队列建好之前 currentSong 一直是 null，
        // 光靠 keptIndex 只能落到 0，于是每次冷启动/回前台都从第一首开始。
        val restoreId = pendingRestoreId
        val restoreIndex = if (keptIndex < 0 && restoreId != null) {
            list.indexOfFirst { it.id == restoreId }
        } else -1
        val restorePos = if (restoreIndex >= 0) pendingRestorePos else 0L
        // 杀进程恢复的队列索引：优先用保存的下标，找不到再按 ID 对齐
        val savedQueueIdx = if (savedQueueIds != null && pendingRestoreQueueIndex in list.indices) {
            pendingRestoreQueueIndex
        } else -1
        // 只在真的能把位置喂给播放器时才消费掉这条记录。controller 还没连上时
        // pushQueue 会空转，记录必须留着让 connectController 那边接手。
        if (controller != null && (restoreIndex >= 0 || list.isNotEmpty())) {
            pendingRestoreId = null
            pendingRestorePos = 0L
            pendingRestoreQueueIndex = 0
        }

        val newIndex = when {
            keptIndex >= 0 -> keptIndex
            savedQueueIdx >= 0 -> savedQueueIdx
            restoreIndex >= 0 -> restoreIndex
            list.isEmpty() -> -1
            resetToFirst -> 0
            else -> -1
        }

        // 浏览类筛选（搜索防抖等，resetToFirst=false）且当前歌被筛掉：不动播放器。
        // 但 homeSongs 仍要更新（搜索/文件夹过滤可能改变首页显示列表）。
        if (playing != null && newIndex < 0 && !resetToFirst) {
            _state.value = _state.value.copy(homeSongs = homeList)
            return
        }

        _state.value = _state.value.copy(queue = list, homeSongs = homeList, currentIndex = newIndex)
        // 冷启动后首屏这批歌提前解析地址（6.5）。
        // 放在 pushQueue 之前：这里的 homeList 就是马上要画出来的那份，
        // 用户下一次点歌大概率落在前几首上。
        warmUpFirstScreen(homeList)
        // 当前曲目仍在队列里则续播原位置，否则用恢复位置（都没有就从头）
        val keepPosition = when {
            keptIndex >= 0 -> controller?.currentPosition ?: 0L
            restoreIndex >= 0 -> restorePos
            else -> 0L
        }
        pushQueue(startIndex = newIndex.coerceAtLeast(0), startPositionMs = keepPosition)
    }

    /**
     * 冷启动后把首屏这批歌的播放地址提前解析好（6.5）。
     *
     * 解决的是 6.4 之后剩下的最后一处"看得见的延迟"：App 刚打开、用户还没点过任何歌时，
     * 时间线里没有任何地址，第一次点歌必须先解析一次（一个 RTT，实测 0.3~0.7 秒）。
     * 之后才有预取可用。
     *
     * 三个约束，写在这里免得以后被人"优化"掉：
     *  1. **只做一次**。[warmUpDone] 是进程级的，应用在前台/后台来回切不会重复触发——
     *     每次回前台都解析一遍等于拿电量换一个用户根本感觉不到的好处。
     *  2. **只做前 6 首**。首屏（约 4.8 寸高度）能看见的歌大约 5~7 行，
     *     再往后用户要滑一下才点得到，那时滑屏的时间足够解析完。
     *  3. **只在有网时做**。没网还发请求，就是 6 次必然失败的超时等待，
     *     白白占着预取线程池的 4 条线程，反而把用户真要点的那首挤到后面。
     *
     * 为什么不是"曲库加载完就全部预取"：全库几百首，等于打开 App 就发几百个请求。
     * 那是拿服务器压力和用户流量换一个几乎察觉不到的好处，不划算。
     */
    private fun warmUpFirstScreen(list: List<Song>) {
        if (warmUpDone) return
        if (list.isEmpty()) return
        // 只在首页（未筛选、未进歌单）时预热：用户一进来就点进某个歌单的场景下，
        // 预热的这 6 首他可能一首都不会碰。
        val s = _state.value
        if (s.libraryMode) return
        val ids = list.asSequence()
            .map { it.songId }
            .filter { it.isNotBlank() && it.all { ch -> ch.isDigit() } }
            .distinct()
            .take(6)
            .toList()
        if (ids.isEmpty()) return
        val online = runCatching {
            val cm = getApplication<android.app.Application>()
                .getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(false)
        if (!online) return
        warmUpDone = true
        // 走同一个预取池：优先级按列表顺序，第 1 首最先跑。
        // 交给池子而不是自己起线程，是为了和用户随后点歌的预取共用限流。
        runCatching {
            com.jun.musiccloud.playback.AudioProxyServer.prefetch(ids)
        }
    }

    /** 首屏预热是否已做过（进程级，见 [warmUpFirstScreen]） */
    private var warmUpDone = false

    /** 把当前队列写入 controller；队列为空则清空播放器 */
    private fun pushQueue(startIndex: Int, startPositionMs: Long) {
        val c = controller ?: return
        val queue = _state.value.queue

        if (queue.isEmpty()) {
            c.clearMediaItems()
            _state.value = _state.value.copy(currentIndex = -1)
            return
        }

        val index = startIndex.coerceIn(0, queue.lastIndex)

        // 目标位置正好是当前正在播放的那一首：保住这首不重建，只同步它前后的条目。
        // 原先这里直接 return，播放器会一直留着旧队列——筛选后 UI 显示 30 首、
        // 播放器里还是全库 500 首，自动切歌会放到筛选外的歌，点歌也会因为
        // 队列不同步而失效（看起来就是点了没反应）。
        val playingId = c.currentMediaItem?.mediaId?.toLongOrNull()
        if (playingId != null && queue.getOrNull(index)?.id == playingId && c.isPlaying) {
            syncQueueAroundCurrent(c, queue, index)
            _state.value = _state.value.copy(currentIndex = index)
            // R13：这条"保住当前曲不重建"的捷径只在删/加都成功时才等价于重写。
            // 服务刚被系统重启、时间线为空（mediaItemCount==0）、或个别 ROM 上
            // removeMediaItems 被吞掉时，syncQueueAroundCurrent 走完时间线仍然是旧的或空的，
            // 于是"UI 显示这份列表、播放器里跑着那份列表"，自动切歌就切到别的音乐上。
            // 补一次同源校验：不对就整条重写（此时已经确认当前曲就是这首，重写代价只有一次解码）。
            if (!timelineMatchesQueue(c)) writeTimelineFromQueue(c, index, c.currentPosition)
            return
        }

        // 播放器时间线按 shuffle 状态排（随机序只存在于这里）；
        // state.queue 始终是主页那份固定排序。
        writeTimelineFromQueue(c, index, startPositionMs)
        // 杀进程重启后恢复：上次若在播放则自动续播，保持"打开还是那首歌在播"的体验
        if (pendingAutoPlay) {
            pendingAutoPlay = false
            c.play()
        }
    }

    /**
     * 以 state.queue 为唯一真相重写播放器时间线，把第 [index] 首（主页下标）放到当前位。
     *
     * pushQueue / playAt / 自动切歌自愈 三处都要做同一件事，统一走这里，
     * 免得某一条路径漏掉 shuffle 或漏掉 prepare() 而留下两份顺序。
     */
    private fun writeTimelineFromQueue(c: MediaController, index: Int, positionMs: Long) {
        val queue = _state.value.queue
        if (queue.isEmpty() || index !in queue.indices) return
        val pinId = if (_state.value.shuffle) queue[index].id else null
        val order = playOrder(queue, pinId)
        val playIdx = if (pinId != null) 0 else index

        // 6.3.9 起播提速（用户原话：「点新歌要延迟很长时间才播放，这个 bug 实在太严重了」）
        //
        // 根因：MediaController 是跨进程代理，setMediaItems(list) 会在 binder 线程里
        // **逐条**发一个 add 请求。榜单/歌单动辄 200 首 = 200 次跨进程往返，
        // 而下面紧跟的 prepare() 又排在它们后面，播放器必须等整条队列灌完才开始解第一首。
        // 队列越长，"点下去到出声"的延迟越大——正是用户描述的现象。
        //
        // 做法：只把「当前曲 + 后面几首」交给播放器并把当前曲标为原子起点，
        // 先让它立刻起播；剩下的尾巴交给 [fillTimelineTail] 在后台补齐。
        // 播放语义不变：补齐完成后时间线与 queue 逐条同源，next / 上一首照旧；
        // 补齐之前用户就点了「下一首」的话，尾部还没灌进去，但 next 命中的那首
        // 一定在窗口里（窗口取 [TIMELINE_TAIL_WINDOW]），所以不会卡。
        val tail = order.drop(playIdx)
        val head = tail.take(TIMELINE_TAIL_WINDOW)
        if (head.isEmpty()) return
        c.setMediaItems(head.toUniqueMediaItems(), 0, positionMs.coerceAtLeast(0L))
        c.prepare()
        // 6.9.3：把 UI 进度直接锚到恢复点，别让 tickPosition 在 seek 落地前
        // 先读到 0 并写回 state（那就是用户看到的"进度条回到原点"）
        _state.value = _state.value.copy(
            currentIndex = index,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = queue.getOrNull(index)?.durationMs ?: _state.value.durationMs
        )
        // 记下这次写入的完整顺序：尾部补齐要按同一份顺序追加，中途用户切歌也能对上号
        timelineBaseOrder = order
        timelineBaseAnchorId = head.first().id
        fillTimelineTail(c)
    }

    /**
     * 把 [writeTimelineFromQueue] 省下的队列尾部在后台补进播放器。
     *
     * 一次 addMediaItems 带 40 条而不是整条：binder 事务有大小上限（1MB），
     * 一次塞 200 条带封面的 MediaItem 容易撞 TransactionTooLarge；
     * 分批也让播放线程有空隙继续解第一首。
     */
    private fun fillTimelineTail(c: MediaController) {
        val order = timelineBaseOrder ?: return
        val anchor = timelineBaseAnchorId ?: return
        val start = order.indexOfFirst { it.id == anchor }
        if (start < 0) return
        val rest = order.drop(start + TIMELINE_TAIL_WINDOW)
        if (rest.isEmpty()) return
        timelineFillJob?.cancel()
        timelineFillJob = launchSafely {
            // 让第一首先解起来：起播前的几百毫秒别拿来灌队列
            kotlinx.coroutines.delay(TIMELINE_FILL_DELAY_MS)
            var from = 0
            while (from < rest.size) {
                val chunk = rest.subList(from, minOf(from + TIMELINE_FILL_CHUNK, rest.size))
                // 只在"当前时间线还是这次写入的那条"时追加，否则说明用户已经换了队列
                val ok = runCatching {
                    val c2 = controller ?: return@runCatching false
                    if (timelineBaseAnchorId != anchor) return@runCatching false
                    c2.addMediaItems(chunk.toUniqueMediaItems())
                    true
                }.getOrDefault(false)
                if (!ok) return@launchSafely
                from += TIMELINE_FILL_CHUNK
                kotlinx.coroutines.delay(30)
            }
        }
    }

    /** 分批补队的窗口 / 批次大小 / 起播后延迟（毫秒） */


    /**
     * 时间线是否与 state.queue 同源。
     *
     * 「顺序播放」要求**逐条同序**：用户在列表里看到的下一行，必须就是播完这首之后的下一首。
     * 「随机播放」的顺序本来就是 playOrder 现算的洗牌序，重算一次并不等于原来那份，
     * 所以随机只要求**同一批歌、条数一致**（不许混进队列外的歌），顺序交给时间线自己保持。
     *
     * 为什么不用"条数相同"当同步判据（旧写法）：条数相同而顺序不同的两份列表太容易出现了
     * ——上一次会话保存的队列、后台重算出的曲库、换筛选后同长度的列表都能撞上，
     * 撞上之后 playAt 会 seek 进旧时间线、pushQueue 会走"保住当前曲"的捷径，
     * 时间线就永远修不好了。这里按 mediaId 逐条比。
     */
    private fun timelineMatchesQueue(c: MediaController): Boolean {
        val queue = _state.value.queue
        val n = queue.size
        if (n == 0) return c.mediaItemCount == 0
        val m = c.mediaItemCount
        // 6.3.9：窗口写入 + 后台补尾期间，时间线条数会**少于**队列（补齐前 / 补齐中途），
        // 这不是"不同源"，只是尾巴还没灌进去。判据改为：
        //   · 时间线不能比队列长（长出来的一定是旧队列混进来的歌）；
        //   · 时间线里的每一条，都必须是队列里"对应位置"的那一首。
        // 头部的对齐关系看当前曲：顺序播放时 queue 下标 = 时间线下标；
        // 随机播放时时间线是洗牌序，只校验"这批歌都在队列里"。
        if (m > n) return false
        if (m == 0) return false
        val shuffle = _state.value.shuffle
        if (shuffle) {
            val ids = HashSet<Long>(n)
            queue.forEach { ids.add(it.id) }
            for (i in 0 until m) {
                val id = runCatching { c.getMediaItemAt(i) }.getOrNull()
                    ?.mediaId?.toLongOrNull() ?: return false
                if (id !in ids) return false
            }
            return true
        }
        // 顺序播放：以当前曲目为锚点，把时间线第 i 条对上 queue 的第 (base + i) 条
        val curIdx = c.currentMediaItemIndex
        val curId = runCatching { c.currentMediaItem }.getOrNull()?.mediaId?.toLongOrNull()
        val base = if (curIdx in 0 until m && curId != null) {
            val qi = queue.indexOfFirst { it.id == curId }
            if (qi >= 0) qi - curIdx else return false
        } else {
            0
        }
        if (base < 0) return false
        for (i in 0 until m) {
            val item = runCatching { c.getMediaItemAt(i) }.getOrNull() ?: return false
            val id = item.mediaId?.toLongOrNull() ?: return false
            val want = queue.getOrNull(base + i) ?: return false
            if (id != want.id) return false
        }
        return true
    }

    /**
     * 自动切歌（ExoPlayer 自己往前拱，REASON_AUTO）之后的自愈：
     * 时间线和 UI 队列不同源时，立刻以 UI 队列重建，让"下一首"就是列表里的下一行。
     *
     * 三种情况分开处理：
     * 1. 正在播的这首还在 UI 队列里，只是它后面那些条目来自旧时间线 →
     *    整条按队列重写并锚定在这一首（它才刚开始，位置归 0 没有听感损失）；
     * 2. 正在播的这首**根本不在** UI 队列里（时间线是旧队列 / 整个曲库）→
     *    回到 UI 队列里"上一首"的下一首继续；找不到上一首就退回队列第 0 首；
     * 3. 上一首已经是队列最后一首 → **顺序播放就该停**：把时间线换回队列、停在最后一首并暂停，
     *    绝不能继续放队列外的歌（这就是用户说的"一直听到最后它不是顺序播放"）。
     */
    private fun healTimelineAfterAutoAdvance(transitioned: MediaItem?) {
        val c = controller ?: return
        val queue = _state.value.queue
        if (queue.isEmpty()) return
        if (timelineMatchesQueue(c)) return
        val curId = transitioned?.mediaId?.toLongOrNull() ?: c.currentMediaItem?.mediaId?.toLongOrNull()
        val curIdx = curId?.let { id -> queue.indexOfFirst { it.id == id } } ?: -1
        if (curIdx >= 0) {
            writeTimelineFromQueue(c, curIdx, 0L)
            applyPlaybackModes()
            return
        }
        // prevSongId = 上一次同步到 UI 队列的那首歌（切歌前结算时留下的），
        // 它比 currentIndex 可靠：currentIndex 可能是上一条时间线错位时反查失败兜出来的下标
        val anchorId = prevSongId.takeIf { it >= 0L } ?: _state.value.currentSong?.id
        val anchorIdx = anchorId?.let { id -> queue.indexOfFirst { it.id == id } } ?: -1
        if (anchorIdx < 0) {
            writeTimelineFromQueue(c, 0, 0L)
            applyPlaybackModes()
            return
        }
        val nextIdx = anchorIdx + 1
        if (nextIdx >= queue.size) {
            // R26：播到列表末尾 → 绕回第一首继续，而不是 pause。
            // 用户原话：「12 首歌听到最后一首就直接不放了，它不放第一首吗？」
            // 另一类情形也落在这里：时间线只灌了 12 首的窗口，窗口用尽时同样算"到底"。
            writeTimelineFromQueue(c, 0, 0L)
            applyPlaybackModes()
            runCatching { c.play() }
            return
        }
        writeTimelineFromQueue(c, nextIdx, 0L)
        applyPlaybackModes()
    }

    /**
     * 只替换当前曲目之外的条目：正在放的那一首原地留着，播放不中断。
     * 先删尾再删头，否则删头会让当前曲目的下标位移。
     *
     * [queue]/[index] 是主页那份固定排序；实际写入播放器前换成时间线顺序
     * （开着随机就是洗牌序），当前曲目在两份顺序里指向同一首歌。
     */
    private fun syncQueueAroundCurrent(c: MediaController, queue: List<Song>, index: Int) {
        val pinId = if (_state.value.shuffle) queue[index].id else null
        val order = playOrder(queue, pinId)
        // 当前曲目在时间线顺序里的位置（随机置顶后是 0；非随机时与主页下标一致）
        val playIdx = order.indexOfFirst { it.id == queue[index].id }.coerceAtLeast(0)
        val cur = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (cur + 1 < count) c.removeMediaItems(cur + 1, count)
        if (cur > 0) c.removeMediaItems(0, cur)

        // 6.3.9：这里也要跟着"只补一窗口"的写入策略走，否则整队列一次 push 的
        // 卡顿又会从这个入口冒出来。同步点选在"当前曲 + 一窗口"，尾巴交给后台补。
        val after = order.drop(playIdx + 1).take(TIMELINE_TAIL_WINDOW)
        val before = order.take(playIdx)
        if (after.isNotEmpty()) c.addMediaItems(after.toUniqueMediaItems())
        if (before.isNotEmpty()) c.addMediaItems(0, before.toUniqueMediaItems())
        timelineBaseOrder = order
        timelineBaseAnchorId = queue[index].id
        fillTimelineTail(c)
    }

    private fun refreshSystemDark() {
        val dark = (getApplication<Application>().resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        _state.value = _state.value.copy(systemDark = dark)
    }

    private fun readTheme(): JunTheme =
        runCatching { JunTheme.valueOf(prefs.getString(KEY_THEME, JunTheme.MONO.name)!!) }
            .getOrDefault(JunTheme.MONO)

    private fun readMode(): JunMode =
        runCatching { JunMode.valueOf(prefs.getString(KEY_MODE, JunMode.LIGHT.name)!!) }
            .getOrDefault(JunMode.LIGHT)

    /**
     * 读取材质。
     *
     * 6.4.1 修正默认值：新用户（从没存过这个 key）一律 **液态玻璃**。
     *
     * 以前的写法是「没存过就看老的 haze_enabled，缺省 true → 毛玻璃」。
     * 这条迁移规则是给 1.x 老用户准备的，但 SharedPreferences 里没有这个开关的新装机器
     * 也会走到同一个分支，于是**所有新下载的用户第一次打开都是毛玻璃**：
     * 宣传图是液态玻璃、装上却是糊一层灰的卡片，落差极大（用户原话：
     * 「这让人感觉我这个宣传图是假的」）。
     *
     * 现在：
     *   · 用户亲手选过 → 尊重他的选择，一个字节都不动；
     *   · 从没手动选过（老版本被"默认毛玻璃"带进去的）→ 一次性迁到 LIQUID；
     *   · 全新安装 → LIQUID（宣传图是什么，第一眼就是什么）。
     *
     * 低端机不需要在这里特殊处理——[com.jun.musiccloud.ui.theme.JunMusicTheme] 会把
     * 液态玻璃降级成「轻量档」：保留半透明本体与内缘高光，不跑逐元件背板取样，
     * 观感仍是液态玻璃，绘制成本只比实色多一层填充。
     */
    private fun readSurfaceStyle(): JunSurfaceStyle {
        // 用户亲手点过材质吗（6.4.1 起才写这个标记）
        val userPicked = prefs.getBoolean(KEY_SURFACE_USER, false)
        val saved = prefs.getString(KEY_SURFACE_STYLE, null)

        if (!userPicked) {
            // 从没选过：一律液态玻璃。老版本把"没选过"当成了 haze_enabled=true，
            // 结果所有人第一次打开都是毛玻璃——这条迁移就是来纠正它的。
            if (saved != JunSurfaceStyle.LIQUID.name) {
                prefs.edit().putString(KEY_SURFACE_STYLE, JunSurfaceStyle.LIQUID.name).apply()
            }
            return JunSurfaceStyle.LIQUID
        }
        return runCatching { JunSurfaceStyle.valueOf(saved ?: "") }
            .getOrDefault(JunSurfaceStyle.LIQUID)
    }

        private fun readSortOrder(): SortOrder =
        runCatching { SortOrder.valueOf(prefs.getString(KEY_SORT_ORDER, SortOrder.TITLE.name)!!) }
            .getOrDefault(SortOrder.TITLE)
    private fun readSortDirection(): SortDirection =
        runCatching { SortDirection.valueOf(prefs.getString(KEY_SORT_DIRECTION, SortDirection.ASCENDING.name)!!) }
            .getOrDefault(SortDirection.ASCENDING)
    /** 界面排布。未存过（老版本升级）时为纵向列表，也就是原有观感 */
    private fun readLayout(): JunLayout =
        runCatching { JunLayout.valueOf(prefs.getString(KEY_LAYOUT, JunLayout.LIST.name)!!) }
            .getOrDefault(JunLayout.LIST)

    private fun readSelectedFolders(): Set<String> {
        val raw = prefs.getString(KEY_SELECTED_FOLDERS, null) ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        return raw.split(',').mapNotNull { it.trim() }.toSet()
    }

    /** 循环模式：其它开关都持久化了，这两个原先是漏的，重启会回到默认 */
    private fun readRepeatMode(): RepeatMode =
        runCatching { RepeatMode.valueOf(prefs.getString(KEY_REPEAT, RepeatMode.OFF.name)!!) }
            .getOrDefault(RepeatMode.OFF)

    private fun saveSelectedFolders(sets: Set<String>) {
        prefs.edit().putString(KEY_SELECTED_FOLDERS, sets.joinToString(",")).apply()
    }

    /**
     * 6.9.6：把一串歌写成 MediaItem 时间线，并保证 mediaId 全局唯一。
     *
     * media3 的 MediaPeriodQueue 内部用不可变 Map 按 period 归组，同一时间线里
     * 出现两条相同 mediaId（同一首重复入队/本地与在线同 id 撞车）会触发
     * Guava「Multiple entries with same key」直接崩溃（后台崩溃日志实录）。
     * 首次出现保持纯数字 id（历史恢复逻辑 mediaId.toLongOrNull() 依赖它），
     * 重复的从第二次起追加 "#n" 后缀 —— 所有 toLongOrNull() 消费点对它
     * 返回 null 并自然走兜底，不影响任何现有链路。
     */
    private fun List<Song>.toUniqueMediaItems(): List<MediaItem> {
        val seen = HashMap<Long, Int>()
        return map { s ->
            val n = seen.merge(s.id, 1, Int::plus) ?: 1
            if (n > 1) s.toMediaItem("${s.id}#${n - 1}") else s.toMediaItem()
        }
    }

    private fun Song.toMediaItem(mediaIdOverride: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaIdOverride ?: id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(albumArtUri)
                    // 悬浮歌词在服务侧解析需要真实文件路径（content URI 的 path 不是文件路径），
                    // 用 extras 透传 filePath
                    .setExtras(
                        android.os.Bundle().apply {
                            putString("file_path", filePath)
                            putString("lyric_key", lyricKey)
                            // 6.3.2 车机歌词：解析歌词要时长来裁掉尾巴，这里先带过去
                            putLong("duration_ms", durationMs)
                        }
                    )
                    .build()
            )
            .build()

    override fun onCleared() {
        sleepUnsub?.let { it() }; sleepUnsub = null
        // 界面没了就把解析回调摘掉，别让 socket 线程继续往一个死掉的 ViewModel 写状态
        AudioProxyServer.onResolved = null
        flashJob?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        getApplication<Application>().unregisterComponentCallbacks(configCallbacks)
        super.onCleared()
    }

    private companion object {
        /**
         * 6.3.9 起播提速：一次 setMediaItems 先只推「当前曲 + 后面这么些首」。
         * 12 首足够覆盖用户"点完歌马上连按几下下一首"的操作，又短到起播几乎无感。
         */
        const val TIMELINE_TAIL_WINDOW = 12

        /** 尾部补齐每次带多少条（binder 事务有 1MB 上限，分批避免 TransactionTooLarge） */
        const val TIMELINE_FILL_CHUNK = 40

        /** 起播后等多久再开始补队列（要留够第一首解出声音的时间） */
        const val TIMELINE_FILL_DELAY_MS = 900L

        /**
         * 首页「热门歌手」的排除名单（小写、去空格后做包含匹配）。
         * 周杰伦：网易云正版曲目基本全下架，点进去听不到歌，不该占首页第一屏。
         */
        val EXCLUDED_ARTISTS = listOf("周杰伦")

        /** 首页栏目 force 刷新的最小间隔（40 秒内重复下拉只算一次） */
        const val HOME_FEED_MIN_REFRESH_MS = 40_000L

        const val PREFS = "jun_prefs"
        /** 单曲搜索每页条数（云搜索单页上限 100）。之前 30 首首屏被用户误以为"平台就这几首歌"，
         *  首屏直接给满 100，滚到底继续按 100 一页加载。 */
        const val SEARCH_PAGE_SIZE = 100
        /** 搜索页那排榜单卡要几张。R9 从 5 提到 10 —— 用户反馈"往左滑应该有非常多"。
         *  网易云官方榜有 20+ 个，5 张是我们自己截的，不是接口只给这么多。 */
        const val TOP_PREVIEW_COUNT = 10
        /** 歌单搜索每页条数（同样走 offset 分页，之前 offset 写死 0 所以永远只有第一页） */
        const val SEARCH_PLAYLIST_PAGE = 50
private const val KEY_THEME = "theme"
    /** 6.6.7：上次没同步到服务器的昵称（断线/服务器未更新时暂存，下次启动补传） */
    private const val KEY_PENDING_NICKNAME = "pending_nickname"
    /** 6.6.9：用户搜索过的歌手计数（JSON：歌手名 → 次数），热门歌手按它排序 */
    private const val KEY_ARTIST_SEARCH_COUNTS = "artist_search_counts"
    /** 6.6.9：搜过的歌手名 → "id|头像" 的解析缓存，避免每次刷新都反查 */
    private const val KEY_ARTIST_CARDS = "artist_cards"
    private const val KEY_POWER_SAVING_ANIM = "power_saving_anim"
        const val KEY_MODE = "mode"
        const val KEY_HAZE = "haze_enabled"
        const val KEY_SURFACE_STYLE = "surface_style"
        /** 6.4.1：用户是否亲手选过材质。只有亲手选过，才允许在"改默认值"时保留。 */
        const val KEY_SURFACE_USER = "surface_style_user"
        const val KEY_CUSTOM_PRIMARY = "custom_primary"
        const val KEY_CUSTOM_SECONDARY = "custom_secondary"
        const val KEY_CUSTOM_BG_URI = "custom_bg_uri"
        /** 上次选中的歌单 ID，-1 = 无（全部/文件夹视图）。需持久化，否则重启回到默认全部。 */
        const val KEY_ACTIVE_PLAYLIST = "active_playlist_id"
        /** 上次是否停留在「我喜欢」视图。需持久化，与 KEY_ACTIVE_PLAYLIST 互斥。 */
        const val KEY_ONLY_LIKED = "only_liked_view"
        const val KEY_LIBRARY_MODE = "library_mode"
        /** 图书馆模式：确认没有实质歌词的歌 ID（字符串） */
        const val KEY_LIBRARY_VERIFIED = "library_verified_ids"
        /** 图书馆模式：确认带人声歌词的歌 ID（字符串） */
        const val KEY_LIBRARY_BLOCKED = "library_blocked_ids"
        const val KEY_LAYOUT = "ui_layout"
        /** 唱片墙方向：true = 横向 */
        const val KEY_WALL_HORIZONTAL = "ui_wall_horizontal"
        const val KEY_SORT_ORDER = "sort_order"
        const val KEY_SORT_DIRECTION = "sort_direction"
        const val KEY_GLASS_BLUR = "glass_blur"
        const val KEY_BG_BLUR = "bg_blur"
        const val KEY_ROW_SIZE = "row_size"
        const val KEY_ROW_COVER = "row_cover"
        const val KEY_ROW_ARTIST = "row_artist"
        const val KEY_ROW_PLAYLIST = "row_playlist"
        const val KEY_SEARCH_PERSISTENT = "search_persistent"
        const val KEY_AUTO_OPEN_PLAYER = "auto_open_player"
        // 4.0.3 补偿会员本地缓存：断网也能显示已领取与到期时间
        const val KEY_VIP_LEVEL = "vip_level"
        const val KEY_VIP_NAME = "vip_name"
        const val KEY_VIP_UNTIL = "vip_until"
        const val KEY_VIP_CLAIMED = "vip_claimed"
        const val KEY_LIKED_BADGE = "liked_badge"
        const val KEY_VISUALIZER = "visualizer_enabled"
        const val KEY_TILT_SPECTRUM = "tilt_spectrum"
        const val KEY_COVER_SHAKE = "cover_shake"
        const val KEY_COVER_LYRIC = "cover_lyric"
        const val KEY_COVER_TOP_LYRIC = "cover_top_lyric"
        const val KEY_KARAOKE_LYRIC = "karaoke_lyric"
        const val KEY_LYRIC_ALIGN = "lyric_align"
        const val KEY_LYRIC_FONT = "lyric_font"
        const val KEY_LYRIC_FONT_SIZE = "lyric_font_size"
        const val KEY_LYRIC_STAGE = "lyric_stage"
        const val KEY_LYRIC_MOTION = "lyric_motion"
        const val KEY_LYRIC_DISPLAY_MODE = "lyric_display_mode"
        const val KEY_LYRIC_CONTEXT = "lyric_context"
        const val KEY_LYRIC_QUALITY = "lyric_quality"
        const val KEY_LYRIC_BEAT_FX = "lyric_beat_fx"
        const val KEY_LYRIC_PARTICLES = "lyric_particles"
        const val KEY_LYRIC_BASS_GLOW = "lyric_bass_glow"
        const val KEY_LYRIC_CHORUS_FX = "lyric_chorus_fx"
        const val KEY_LYRIC_CHORUS_BOOST = "lyric_chorus_boost"
        const val KEY_LYRIC_COVER_TINT = "lyric_cover_tint"
        const val KEY_LYRIC_TINT_STRENGTH = "lyric_tint_strength"
        const val KEY_LYRIC_OFFSET = "lyric_offset"
        const val KEY_LYRIC_STAFF_CARD = "lyric_staff_card"
        const val KEY_LYRIC_LINE_SPACING = "lyric_line_spacing"
        const val KEY_LYRIC_LETTER_SPACING = "lyric_letter_spacing"
        const val KEY_LYRIC_WEIGHT = "lyric_weight"
        const val KEY_LYRIC_OUTLINE = "lyric_outline"
        const val KEY_LYRIC_TEXTURE = "lyric_texture"
        const val KEY_LYRIC_GLOW_STYLE = "lyric_glow_style"
        const val KEY_LYRIC_GLOW_STRENGTH = "lyric_glow_strength"
        const val KEY_LYRIC_COVER_DEPTH = "lyric_cover_depth"
        const val KEY_LYRIC_FOCUS = "lyric_focus"
        const val KEY_LYRIC_BEAT_CAMERA = "lyric_beat_camera"
        const val KEY_LYRIC_BEAT_CAMERA_STRENGTH = "lyric_beat_camera_strength"
        const val KEY_LYRIC_LONG_FORM = "lyric_long_form"
        const val KEY_LYRIC_VIDEO_URI = "lyric_video_uri"
        const val KEY_LYRIC_VIDEO_ALPHA = "lyric_video_alpha"
        const val KEY_LYRIC_SEMANTIC_WRAP = "lyric_semantic_wrap"
        const val KEY_SETTINGS_ADVANCED = "settings_advanced"
        const val KEY_REDUCE_MOTION = "reduce_motion"
        const val KEY_LYRIC_FRAME_STATS = "lyric_frame_stats"
        const val KEY_LYRIC_GUIDE_SEEN = "lyric_guide_seen_v1"
        const val KEY_STAGE_PER_SONG = "lyric_stage_per_song"
        const val KEY_FAVORITE_STAGES = "lyric_stage_favorites"
        const val KEY_SONG_STAGES = "lyric_song_stages"
        const val SONG_OFFSETS_KEY = "lyric_song_offsets"
        const val KEY_LYRIC_TRANSLATION = "lyric_translation"
        const val KEY_LYRIC_ROMANIZATION = "lyric_romanization"
        const val KEY_FX_PRESET = "fx_preset"
        const val KEY_AUTOMIX = "automix_enabled"
        const val KEY_AUTOMIX_STYLE = "automix_style"
        const val KEY_LOCAL_FOLDER = "local_folder"
        const val KEY_LOCAL_COVER = "local_folder_cover"
        const val KEY_LOCAL_IGNORE = "local_ignore"
        const val KEY_LOCAL_ALIAS = "local_alias_rules"
        const val KEY_LOCAL_SPLIT = "local_split_artists"
        const val KEY_REMOTE_ENABLED = "remote_control_enabled"
        /** 倍速上下限（基点，100 = 1.0x），与 MusicService.applyPlaybackSpeed 的夹取一致 */
        const val MIN_SPEED_BP = 75
        const val MAX_SPEED_BP = 200
        const val KEY_AI_THEME_NAME = "ai_theme_name"
        const val KEY_UI_FONT = "ui_font"
        const val KEY_TOPBAR_AUTOHIDE = "topbar_autohide"
        /** 上栏静止多少秒后淡出（3~30，默认 10） */
        const val KEY_TOPBAR_HIDE_SEC = "topbar_hide_sec"
        const val KEY_CORNER_BASE = "corner_base"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_EXPLORATION = "exploration"
        const val KEY_SELECTED_FOLDERS = "selected_folders"
        const val KEY_SHUFFLE = "shuffle_enabled"

        /** R14：用户是否**明确**选过播放模式（点过随机或模式按钮）。没点过 = 默认顺序播放 */
        const val KEY_PLAYMODE_CHOSEN = "playmode_chosen"
        const val KEY_REPEAT = "repeat_mode"
        const val KEY_LAST_SONG = "last_song_id"
        const val KEY_LAST_POS = "last_position_ms"
        const val KEY_LAST_PLAYING = "last_was_playing"
        const val KEY_LAST_QUEUE_IDS = "last_queue_ids"
        const val KEY_LAST_QUEUE_INDEX = "last_queue_index"
        const val KEY_SHOW_RECS = "show_recommendations"
        const val KEY_SHOW_SORT_BAR = "show_sort_bar"
        const val KEY_JELLY_ANIM = "jelly_anim"
        /** 6.6 动效风格持久化键 */
        const val KEY_MOTION_STYLE = "motion_style"
        /** 下载目录路径（自定义），空 = 使用默认 Music/Jun Music */
        /** 是否已确认下载目录（首次下载时弹窗选择） */
        /** 底部小播放器是否常驻（不关=空闲时淡出） */
        // 6.6.2：与其他应用同时播放（不抢占音频焦点）
        const val KEY_PLAY_WITH_OTHERS = "play_with_others"
        // 6.6.2：大播放器控件可隐藏（隐藏后仍生效，其余控件居中重排）
        const val KEY_HIDE_SPEED = "hide_speed"           // 倍速
        const val KEY_HIDE_COMMENT = "hide_comment"       // 评论
        const val KEY_HIDE_PITCH = "hide_pitch"           // 音调
        const val KEY_HIDE_QUALITY = "hide_quality"       // 音质
        const val KEY_HIDE_DOWNLOAD = "hide_download"     // 下载
        const val KEY_HIDE_LIKE = "hide_like"             // 喜欢
        const val KEY_HIDE_SIMILAR = "hide_similar"       // 相似推荐
        const val KEY_HIDE_RECENT = "hide_recent"         // 最近播放
        const val KEY_HIDE_REPEAT = "hide_repeat"         // 循环播放
        const val KEY_HIDE_SLEEP = "hide_sleep"           // 睡眠定时器
        // 6.6.2：自定义界面可隐藏搜索/设置入口（隐藏后靠手势召唤自定义界面）
        const val KEY_HIDE_SEARCH_ENTRY = "hide_search_entry"
        const val KEY_HIDE_SETTINGS_ENTRY = "hide_settings_entry"
    }
}