package com.jun.musiccloud

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import com.jun.musiccloud.BuildConfig
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.jun.musiccloud.security.AppIntegrity

/**
 * 全局崩溃兜底：捕获未处理异常，写入本地 crash.log，避免闪退时黑屏无提示。
 * 下次启动可读取上次崩溃记录，在 UI 上弹"上次异常退出"提示。
 */
class JunApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 机型分档必须是第一件做的事：流式缓存上限、封面内存预算、逐帧动效节流、
        // 频谱采样全都读它。原来只在 MainActivity.onCreate 里 init，于是
        // Application / ViewModel / 播放服务这些更早的路径全部拿到默认值（不降级）——
        // 老机型等于从来没被识别过。
        runCatching { com.jun.musiccloud.ui.theme.DevicePerformance.init(this) }
        // 6.8：全局字号 / 排版密度（标准 / 紧凑 / 宽松）初始化，需在读取前完成。
        runCatching { com.jun.musiccloud.ui.JunUiConfig.init(this) }
        // 6.6：玻璃质感调参（边缘高光 / 内阴影 / 跟手光斑 / 按压回弹）恢复。
        // junSurface 在首帧就会读它，必须在 Application 里先 init，否则重启后丢设置。
        runCatching { com.jun.musiccloud.ui.GlassTuning.init(this) }
        // 应用完整性自检：调试包 / 非官方渠道 / 签名不符时记录，供「关于」页提示
        runCatching { AppIntegrity.run(this) }
        // 性能监控：冷启动窗口裁决是否切实色；随后由玻璃渲染调度器常驻监控滚动期掉帧
        runCatching { initFrameMonitor() }
        runCatching { com.jun.musiccloud.ui.theme.GlassRuntime.startFrameWatch() }
        // 上次异常退出：把本地 crash.log 补报到总后台「崩溃中心」。
        // 放后台线程，不拖慢冷启动；上报成功才写标记，失败下次启动继续试。
        runCatching { Thread { uploadPendingCrash() }.start() }
        // 网络切换时让缓存的音频直链失效（6.5）：不清会出现
        // 「Wi-Fi 听的歌，切到 4G 再点就报无版权，再点一次又好」的抽风。
        runCatching { com.jun.musiccloud.data.NetworkWatcher.install(this) }
        // 6.6.1：恢复音源脚本。首次安装时这里会把两个实测可用的聚合源铺到私有目录
        // 并**默认全部启用**（用户不需要先进设置点一圈就能听）。
        //
        // 放后台线程：脚本要起 QuickJS 并跑 init，有的还会先做一次更新检查，
        // 放主线程会把冷启动拖慢一秒多。
        //
        // 幂等：进程内只跑一次（Activity 重建 / 多进程都不会重复加载）。
        runCatching {
            com.jun.musiccloud.data.sourceplugin.LxScriptHost.install(this)
            Thread({
                if (SOURCE_PLUGIN_STARTED.compareAndSet(false, true)) {
                    runCatching { com.jun.musiccloud.data.sourceplugin.SourcePluginStore.restore(this) }
                    // 6.9.2：拉取服务器下发音源（后台加的 .js 脚本，全网自动装/删）。
                    // 放 restore 之后：restore 先把本地那套跑起来，这里失败也不影响播放。
                    runCatching { com.jun.musiccloud.data.sourceplugin.SourcePluginStore.syncRemoteSources(this) }
                }
            }, "source-plugin-init").start()
        }
        // 6.3.4：磁盘缓存语义修正（一次性）。
        // 6.3.3 及以前缓存键是「歌曲ID + 档位」，不含音频地址，于是曾经拿过
        // 30/45 秒试听片段的歌会在磁盘上一直留着半首歌，换源也顶不掉。
        // 升级后作废一次，之后每首歌都按真实地址重新缓存。后台线程，不拖慢启动。
        runCatching {
            com.jun.musiccloud.data.PlaybackCache.migrateIfNeeded(
                this,
                getSharedPreferences(
                    com.jun.musiccloud.data.PlaybackCache.PREFS,
                    Context.MODE_PRIVATE
                )
            )
        }
        // ────────────────────────────────────────────────────────────
        // 6.8.0：**本地播放索引**（整个"后路"体系的地基）。
        //
        // 初始化必须同步：`AudioProxyServer` 在播放第一首歌时就要查它，
        // 而播放可能发生在 Application.onCreate 之后几百毫秒（点开 App 就播）。
        // 初始化本身只是读一个 SharedPreferences JSON，毫秒级。
        //
        // 扫描回填放后台线程：要在 `Music/` 目录里逐文件判断，
        // 几千首歌的手机上可能要跑一两秒，绝不能挡冷启动。
        // 扫描的作用是把"升级前就已经下载好的文件"补进索引 ——
        // 不做这一步，老用户升级完等于没这个功能。
        // ────────────────────────────────────────────────────────────
        runCatching {
            com.jun.musiccloud.data.LocalAudioIndex.init(this)
            Thread({
                runCatching { com.jun.musiccloud.data.LocalAudioIndex.scanDownloads() }
            }, "local-index-scan").start()
        }
        // 6.8.0：自建曲库清单（第二层后路）。先读本地缓存（毫秒级，断网也有），
        // 再后台刷新。刷新失败保持旧清单，绝不让这一层因为一次网络失败就消失。
        runCatching {
            com.jun.musiccloud.data.RemoteLibrary.init(this)
            com.jun.musiccloud.data.RemoteLibrary.refresh(this)
        }
        // 6.8.0：远程音源开关（读上一次缓存的值，拉配置后再更新）+ 失败上报
        runCatching {
            com.jun.musiccloud.data.SourceSwitches.init(this)
            com.jun.musiccloud.data.SourceHealth.init(this)
        }
        // 6.8.1：曲库自动回填。这里只注册工作线程，不做任何网络动作 ——
        // 真正干活是在"一首歌播稳之后"，且只在 Wi-Fi 下。
        runCatching { com.jun.musiccloud.data.LibraryBackfill.init(this) }
        // 6.9.3：后台推送。冷启动拉一次（force 跳过 5 分钟节流），
        // 之后每次回前台由 MainActivity.onResume 再补一次。
        runCatching {
            com.jun.musiccloud.data.PushInbox.ensureChannel(this)
            Thread({
                runCatching { com.jun.musiccloud.data.PushInbox.poll(this, true) }
            }, "push-poll").start()
        }
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 6.9.6：非致命系统异常白名单 —— 只记日志、不杀进程。
            //
            // ForegroundServiceStartNotAllowedException：app 在后台时 media3 的
            // MediaController 因耳机线控/悬浮歌词点按/自动恢复触发服务重启，Android 12+
            // 不允许后台 startForegroundService。播放没接上而已，UI 全部健在，
            // 用户回前台任何一次操作都会正常重连 —— 杀进程反而把"正在听的歌"丢了。
            // 后台崩溃日志里 6 条同类崩溃全部来自它，吞掉是标准处理方式。
            val swallows = generateSequence(throwable as Throwable?) { it.cause }
                .take(8)
                .any {
                    it is android.app.ForegroundServiceStartNotAllowedException ||
                        (it?.javaClass?.name
                            ?.endsWith("ForegroundServiceStartNotAllowedException") == true)
                }
            if (swallows) {
                // 6.6：这类**不是崩溃**——系统只是拒绝了后台启动前台服务，进程没死、
                // 界面与播放状态都健在。旧实现仍 writeCrashLog 上报，导致后台崩溃页
                // 常年挂着十几条"崩溃"，实际没有一次真的闪退。改成只打本地日志；
                // 源头另在 ReminderService / ensureControllerConnected 收口。
                android.util.Log.w("JunApp", "FGS start rejected in background — swallowed", throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            runCatching { writeCrashLog(thread, throwable) }
            // 6.9.8：把"这次是真实崩溃"记进 jun_prefs，下次启动据此进入安全模式。
            // 只在真正的崩溃路径（非 swallow）上写——进程被系统异常杀死才会走到这里，
            // 普通退出 / 轻微异常都不会触发，所以安全模式绝不会误伤正常用户。
            // 守卫：若上次崩溃还没被处理（safemode_pending 仍为真），不要覆盖原现场，
            // 保留最初的崩溃签名与版本，便于后台定位那一次真正的闪退。
            runCatching { markSafeModePending(throwable) }
            // 崩溃前先把"伸到系统里的手"收回来：马达、悬浮歌词窗、歌词壁纸、休眠定时器。
            // 这些资源的生命周期都超出本进程（窗口挂在 WindowManager、振动器归 vibratorservice、
            // 壁纸由 system_server 驱动），进程被异常打断时系统侧的清理时机并不由我们掌握。
            // 用户遇到的"闪到密码屏后划不动、只能长按重启"，最像这类残留层造成的现场，
            // 所以哪怕后面就要死，也必须先同步收干净。
            // 注意：全部 runCatching + 当前线程同步调用，不起新线程、不建 Looper、不联网。
            runCatching { releaseSystemResourcesBeforeDeath() }
            // 交给系统默认处理器（弹崩溃对话框 / 退出进程）
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 崩溃收尾：主动释放那些"超出本进程生命周期"的系统资源。
     *
     * 只调用现成对象的同步方法，顺序按"离用户最近 → 最远"排：
     *  1) 鼓点振动： vibrator 不停就是熄屏后还在震，最直接的"把系统弹醒"路径；
     *  2) 悬浮歌词窗： 唯一会压在 Keyguard 之上的第三方层，removeViewImmediate 同步摘掉；
     *  3) 歌词动态壁纸： 让壁纸 Engine 自己停绘制循环（我们不能、也不该跨进程强杀它）；
     *  4) 休眠定时器： 取消 pending 的 AlarmManager 回调，避免进程死后还剩一个会唤醒系统的闹钟。
     *
     * 前台播放服务（MusicService）这里刻意不 stopService：崩溃线程上做 binder 调用
     * 可能阻塞，而进程一死服务与它的通知必然随之回收——阻塞的风险换不到任何收益。
     * 服务内部的播放器释放由它自己的 onDestroy 负责（Media3 会走这条路径）。
     */
    private fun releaseSystemResourcesBeforeDeath() {
        runCatching { com.jun.musiccloud.audio.BassHaptics.shutdown() }
        runCatching { com.jun.musiccloud.playback.FloatingLyric.forceHide() }
        runCatching { com.jun.musiccloud.playback.LyricWallpaperService.requestShutdown() }
        runCatching { com.jun.musiccloud.playback.SleepTimer.releaseAlarms() }
        // 帧监控挂号是纯进程内的 Choreographer 回调，进程死了自然没有，
        // 但把"渲染已停"记进日志有助于事后判断崩在哪一段渲染链上。
        runCatching { com.jun.musiccloud.ui.theme.GlassRuntime.stopFrameWatch() }
    }

    /**
     * 崩溃现场的设备画像（老机适配专项）。
     *
     * 现有上报里只有 model/brand/sdk，看不出"这台机器当时到底被降级了什么"。
     * nova 6 的反馈必须能回答：安全档有没有生效、悬浮窗当时挂着没有、
     * 屏幕是不是已熄——否则下次拿到日志还是只能猜。全部读内存里的 volatile 字段，
     * 崩溃线程上零 IO、零 binder。
     */
    private fun crashDeviceContext(): String {
        val dp = com.jun.musiccloud.ui.theme.DevicePerformance
        // 只读 PowerManager 里那个由系统回调维护的缓存字段，不是 binder 调用：
        // 崩溃线程上绝不能出现可能阻塞的跨进程请求。minSdk 24 直接用 isInteractive（API 20+）。
        val screen = runCatching {
            (getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isInteractive
        }.getOrNull()
        val overlay = runCatching {
            com.jun.musiccloud.playback.FloatingLyric.diagnosticState()
        }.getOrNull() ?: "unavailable"
        val report = runCatching { dp.safeModeReport().joinToString("/") }
            .getOrNull().orEmpty()
        return buildString {
            appendLine("Device context:")
            appendLine("  safeMode=${dp.safeMode} emuiOldOs=${dp.isEmuiOldOs} huaweiRom=${dp.isHuaweiRom}")
            appendLine("  api29OrLower=${dp.isApi29OrLower} lowEnd=${dp.isLowEnd} oldOs=${dp.isOldOs} tightMemory=${dp.tightMemory}")
            appendLine("  cores=${dp.coresCount} ramMb=${dp.ramMb} autoDegraded=${dp.autoDegraded}")
            appendLine("  disabledFx=${report.ifBlank { "(none)" }}")
            appendLine("  screen=${screen ?: "unknown"} overlayLyric[$overlay]")
        }
    }

    /**
     * 掉帧监控 + 运行时自适应降级（P5）。
     *
     * 冷启动窗口（前 240 帧，约 2–4 秒，覆盖"刚进去"和首次进入播放页这两段最卡的时段）
     * 统计掉帧率：>32ms 记为一次掉帧。若掉帧率超过 30%，说明本机扛不住当前画质，
     * 立刻把整棵树切到实色 + 关装饰动画（[DevicePerformance.markRuntimeDegrade]），
     * 无需重启即刻生效。这样高端机保留 120Hz + 液态玻璃，跑不动的机器自动退到实色，
     * 真正实现"同一份包按机型自适应"。
     *
     * 采样窗口结束后：release 直接停止回调，零持续开销；debug 继续监控并在累计
     * 50 次掉帧时打日志，方便定位卡顿。回调本身在主线程，单次开销可忽略。
     */
    private fun initFrameMonitor() {
        val choreographer = android.view.Choreographer.getInstance()
        var lastFrameNs = System.nanoTime()
        var frames = 0
        var slowFrames = 0
        var decided = false
        val callback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val now = System.nanoTime()
                val dtMs = (now - lastFrameNs) / 1_000_000.0
                lastFrameNs = now
                frames++
                val slow = dtMs > 32.0
                if (slow) slowFrames++

                // 冷启动窗口结束：按实测掉帧率决定是否降级
                if (!decided && frames >= 240) {
                    decided = true
                    val ratio = slowFrames.toFloat() / frames.coerceAtLeast(1)
                    if (ratio > 0.30f) {
                        if (BuildConfig.DEBUG) {
                            Log.w(
                                "JunPerf",
                                "冷启动掉帧率 ${(ratio * 100).toInt()}%（$slowFrames/$frames），自动降级到实色"
                            )
                        }
                        com.jun.musiccloud.ui.theme.DevicePerformance.markRuntimeDegrade()
                    }
                }
                // 采样完成后 release 停手，避免常驻开销
                if (decided && !BuildConfig.DEBUG) return

                if (slow) {
                    slowFrameCount++
                    if (slowFrameCount % 50L == 0L) {
                        Log.w("JunPerf", "掉帧累计 $slowFrameCount 次（最近一帧 ${dtMs.toInt()}ms），检查主线程耗时操作")
                    }
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
    }

    /** 掉帧计数（仅 debug 监控用，可在「关于」页展示，release 恒为 0） */
    private var slowFrameCount: Long = 0L

    /**
     * 内存告急时主动吐还原可以重建的大块（5.2feat R4，老机型专项）。
     *
     * 之前这里是空的（`onLowMemory() {}`）：系统在低内存时明确通知进程"该减重了"，
     * 我们没接，于是只能等系统自己判断——它的判断结果通常不是"你再释放一点"，
     * 而是"直接杀了"。nova 6 这一批 Android 10/EMUI 机器尤其明显：
     * 用户反馈的"听着听着自己重启"，多数就是进程被 LMK 杀掉后系统重新拉起。
     *
     * 封面是最好扔的一块：全 App 最大、且丢了下次还能从磁盘缓存秒回。
     * 这里绝不做的事：不 recycle 正在显示的 Bitmap、不清播放缓存的磁盘文件
     * （那会让下次起播重新联网拉流，代价比省这几 MB 更大）。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val full = level == TRIM_MEMORY_COMPLETE ||
            level == TRIM_MEMORY_BACKGROUND ||
            level == TRIM_MEMORY_MODERATE ||
            level == TRIM_MEMORY_UI_HIDDEN
        if (level < TRIM_MEMORY_BACKGROUND) return
        runCatching { com.jun.musiccloud.ui.ArtworkLoader.trimMemory(full) }
            .onFailure { Log.w("JunApp", "artwork trim failed: ${it.message}") }
    }

    /** 老系统（API < 14 之外基本不会走到）兜底：低内存通知同样缩容 */
    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { com.jun.musiccloud.ui.ArtworkLoader.trimMemory(true) }
    }

    /**
     * 把本地崩溃日志补报到服务器。
     *
     * 崩溃发生的瞬间不能做网络请求（会卡住系统崩溃流程，甚至超时 8 秒），
     * 所以改为「下次启动时补报」：既不影响用户，也不会丢崩溃现场。
     *
     * 6.2 两处改动（都冲着「后台那个崩溃日志看不出东西」这一条）：
     * ① message 不再恒等于「上次运行异常退出（启动时自动补报）」——
     *    现场里第一条例外标题（`java.lang.SecurityException: Disallowed URI …`）
     *    才是人要看的东西。原来后台 28 条记录全是同一句占位文本，
     *    按 message 分组等于只分出一组，等于没有统计。
     * ② 日志里攒了多段时，以前只报最后一段并把整个文件标记为「已报」，
     *    于是断网期间攒下的前 N-1 次崩溃永久丢失。现在按段去重、逐段补报
     *    （最多 5 段，从最新往旧），已报过的段用各自的指纹跳过。
     */
    private fun uploadPendingCrash() {
        val file = File(getExternalFilesDir(null), "crash/crash.log")
        if (!file.exists()) return
        val marker = File(file.parentFile, "crash_uploaded.txt")
        val text = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
        if (text.isBlank()) return
        // 老版本的标记是「文件的 lastModified」一个字符串，跟新的按段指纹格式不通用。
        // 遇到旧标记就把**当前文件里已有的段**全部当作已报：那是上一版逻辑报过（或故意丢掉）的旧现场，
        // 重新发一遍只会在后台刷出一堆重复行，换不来任何新信息。真正的新崩溃指纹不在集合里，照常补报。
        val legacyStamp = file.lastModified().toString()
        val allBlocks = text.split("===== Jun Music Crash =====")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val uploaded = runCatching { marker.readText().trim() }
            .getOrDefault("")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableSet()
        val isLegacyMarker = uploaded.size == 1 && uploaded.first() == legacyStamp
        if (isLegacyMarker || uploaded.isEmpty()) {
            uploaded.clear()
            if (isLegacyMarker) allBlocks.forEach { uploaded += fingerprintOf(it) }
        }
        val blocks = allBlocks.asReversed()   // 最新一段先报
            .take(5)
        var any = false
        for (block in blocks) {
            val fp = fingerprintOf(block)
            if (fp in uploaded) continue
            val threadName = Regex("Thread: (.*)").find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val ok = com.jun.musiccloud.data.ServerApi.uploadCrash(
                message = crashTitleOf(block),
                stack = block,
                thread = threadName.ifBlank { "unknown" },
                versionCode = BuildConfig.VERSION_CODE,
                versionName = BuildConfig.VERSION_NAME,
                deviceKey = com.jun.musiccloud.data.Telemetry.deviceId(this),
                userId = com.jun.musiccloud.data.AccountStore.userId,
                username = com.jun.musiccloud.data.AccountStore.username,
                model = android.os.Build.MODEL.orEmpty(),
                brand = android.os.Build.BRAND.orEmpty(),
                os = "Android ${android.os.Build.VERSION.RELEASE}",
                sdk = android.os.Build.VERSION.SDK_INT
            )
            if (!ok) break        // 网络不通就别再往下刷了，下次启动接着补
            uploaded += fp
            any = true
        }
        if (any) {
            // 标记只留最近 50 段指纹：crash.log 本身有段数上限，
            // 但标记文件如果一直追加，会在几年后变成一个没人读的大文本。
            val keep = uploaded.toList().takeLast(50)
            runCatching { marker.writeText(keep.joinToString("\n")) }
        }
    }

    /** 一段崩溃现场的指纹：内容 + 长度，够用且不用真算哈希（同一台机器上比对而已） */
    private fun fingerprintOf(block: String): String = block.hashCode().toString() + ":" + block.length

    /**
     * 从崩溃现场里取出「这一条叫什么」：栈里第一条 `XxxException: 描述`（含 Caused by 之前那条）。
     * 取不到就退回原来那句占位文本，保证 message 永不为空（服务器以 message/stack 二者非空为准）。
     */
    private fun crashTitleOf(block: String): String {
        val fallback = "上次运行异常退出（启动时自动补报）"
        val line = block.lineSequence().firstOrNull {
            val t = it.trim()
            t.contains(Regex("[A-Za-z_$][\\w$]*(Exception|Error)(:|\$)")) && !t.startsWith("at ")
        }?.trim().orEmpty()
        if (line.isBlank()) return fallback
        // 服务器 message 列上限 800，这里再收一刀，免得长 URI 把列表撑成一堵墙
        return line.take(200)
    }

    /**
     * 6.9.8：把"真实崩溃"标记进 jun_prefs，供下次启动进入安全模式。
     *
     * 守卫：若 safemode_pending 已经为真（上一次崩溃现场还没被用户处理），则保留原值、
     * 不覆盖最初的崩溃签名 / 版本——只认第一次没被处理的崩溃，避免多次连环崩溃互相覆盖，
     * 也让后台统计到的是用户真正没逃掉的那个版本。
     */
    private fun markSafeModePending(throwable: Throwable) {
        val prefs = getSharedPreferences("jun_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("safemode_pending", false)) return
        prefs.edit().apply {
            putBoolean("safemode_pending", true)
            putString("safemode_crash_sig", buildCrashSig(throwable))
            putInt("safemode_version", BuildConfig.VERSION_CODE)
            putLong("safemode_at", System.currentTimeMillis())
            apply()
        }
    }

    /**
     * 一段稳定的崩溃签名：异常类名 + 第一条落在我们包名下的栈帧
     * （类.方法）。同一处崩溃签名恒定，后台可按签名分组、定向推修复包。
     */
    private fun buildCrashSig(e: Throwable): String {
        val name = e.javaClass.simpleName
        val frame = e.stackTrace
            .firstOrNull { it.className.contains("jun.musiccloud") }
            ?.let { "${it.className}.${it.methodName}" } ?: "unknown"
        return "$name:$frame"
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.appendLine("===== Jun Music Crash =====")
        sb.appendLine("Time: $time")
        sb.appendLine("PID: ${Process.myPid()}")
        sb.appendLine("Thread: ${thread.name}")
        sb.appendLine("Package: $packageName")
        // 老机适配专项：机型画像 + 当时的窗口/屏幕状态。
        // 放在栈之前，因为日志是按段上传的，头部字段服务器上第一眼就能看到。
        runCatching { sb.append(crashDeviceContext()) }.onFailure { sb.appendLine("Device context: unavailable") }
        sb.appendLine()
        sb.appendLine("Stacktrace:")
        val sw = java.io.StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        sb.append(sw.toString())
        sb.appendLine()

        runCatching {
            val dir = File(getExternalFilesDir(null), "crash")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "crash.log")
            FileWriter(file, true).use { fw ->
                fw.write(sb.toString())
            }
        }
        Log.e("JunApp", "Uncaught exception in ${thread.name}", throwable)
    }

    companion object {
        /** 音源脚本只在进程内加载一次：Activity 重建、多入口都别重复跑 */
        private val SOURCE_PLUGIN_STARTED = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 读取上次崩溃日志，供 UI 判断是否弹"上次异常退出"提示 */
        fun readLastCrash(app: Application): String? {
            return runCatching {
                val file = File(app.getExternalFilesDir(null), "crash/crash.log")
                if (file.exists()) file.readText() else null
            }.getOrNull()
        }

        /** 清空崩溃日志（用户确认后调用） */
        fun clearCrashLog(app: Application) {
            runCatching {
                val file = File(app.getExternalFilesDir(null), "crash/crash.log")
                if (file.exists()) file.delete()
            }
        }
    }
}
