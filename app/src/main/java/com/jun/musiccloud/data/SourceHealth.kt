package com.jun.musiccloud.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * 6.8.0：**音源健康上报** —— 回答"到底是哪个源不能用了"。
 *
 * ## 解决什么问题
 *
 * 之前所有失败在日志里只有一句"解析失败"，用户手机上看不到、开发者也看不到。
 * 用户只能凭感觉说"有些歌以前能听现在不能"，而真正的原因可能是
 * QQ 直连的 apikey 被限流、meting 空响应、某个脚本的作者停更了。
 * 没有区分能力时，修一次只能靠猜一次 —— 前面几个版本就是这么反复的。
 *
 * ## 上报什么
 *
 * 只报两类事件，且**只报异常路径**（正常成功不报，否则一天几千条）：
 *   · `resolve_fail`   两档全部失败 —— 这首确实拿不到；
 *   · `resolve_rescue` 第一档失败、降档/换源后救回来了 —— **最有价值的一类**。
 *     它说明"这个源挂了，但用户没受影响"，是判断某个源是否该下线的直接依据。
 *
 * ## 走哪条通道
 *
 * 复用 `Telemetry` 已经建好的队列与签名（离线不丢、断网补报、服务端去重）。
 * 不新建通道：多一条通道就多一处可能坏的地方，而这条链路本身就是"出问题时的眼睛"，
 * 它自己必须最可靠。
 */
object SourceHealth {

    private const val TAG = "SourceHealth"

    /** `resolve_rescue` 采样率：5 次报 1 次。失败全报，救援采样，避免刷爆队列 */
    private const val RESCUE_SAMPLE = 5

    @Volatile private var appCtx: Context? = null

    private val rescueCount = AtomicLong(0)

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /** 两档全失败：这首歌确实拿不到 */
    fun reportFail(songId: String, title: String, artist: String, ms: Long, detail: String = "") {
        val ctx = appCtx ?: return
        val extra = runCatching {
            JSONObject()
                .put("kind", "fail")
                .put("song", songId.take(32))
                .put("title", title.take(48))
                .put("artist", artist.take(32))
                .put("ms", ms)
                .put("detail", detail.take(80))
                .toString()
        }.getOrNull() ?: return
        runCatching { Telemetry.report(ctx, "resolve_fail", extra) }
            .onFailure { Log.w(TAG, "失败上报异常：${it.message}") }
        Log.w(TAG, "解析失败：$title / $artist（${ms}ms）$detail")
    }

    /**
     * 第一档失败、后续档位救回来了。
     *
     * `by` = 最终给出地址的通道（qq / meting / script / official / gd / library / local）。
     * 后台按 `by` 分组就能看出：**哪个源在做主力**、**哪个源已经完全不干活了**。
     */
    fun reportRescue(by: String, ms: Long, title: String = "") {
        val ctx = appCtx ?: return
        val n = rescueCount.incrementAndGet()
        if (n % RESCUE_SAMPLE != 0L) return
        val extra = runCatching {
            JSONObject()
                .put("kind", "rescue")
                .put("by", by.ifBlank { "unknown" })
                .put("title", title.take(48))
                .put("ms", ms)
                .toString()
        }.getOrNull() ?: return
        runCatching { Telemetry.report(ctx, "resolve_rescue", extra) }
    }
}
