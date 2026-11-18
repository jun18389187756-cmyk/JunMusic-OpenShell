package com.jun.musiccloud.data

import android.util.Log
import org.json.JSONObject

/**
 * 6.8.0：**远程音源开关** —— 源挂了不用重新打包。
 *
 * ## 为什么需要
 *
 * 前面几个版本的教训：某个第三方接口一停，用户就得等一版新 APK。
 * 而这类接口的生命周期常常只有几周（用户手上 6 个脚本已经死了两个）。
 * 让"关掉一个源"变成后台点一下，是把这个 App 从"每次出问题都要发版"
 * 里救出来的唯一办法。
 *
 * ## 怎么生效
 *
 * 服务器 `GET /api/app/config` 的 `flags.sources` 下发，App 每次拉配置时应用。
 * 拉不到配置 → **保持上一次的值**，首次安装默认全开。
 *
 * 这个默认值的选择很关键：网络异常时如果默认关闭，用户会突然"什么都不能听"。
 * 宁可带着一个已经失效的源多试一次，也不能因为一次配置拉取失败就自断经脉。
 */
object SourceSwitches {

    private const val TAG = "SourceSwitches"
    private const val PREFS = "jun_source_switches"

    /** 内置 QQ 直连（实测最快的一条，也是目前的主力） */
    @Volatile var qq = true
        private set

    /** 音源脚本链（覆盖面最广，也是最不稳的一条） */
    @Volatile var script = true
        private set

    /** meting / GD 这类公共 API 快链 */
    @Volatile var meting = true
        private set

    /** 自建曲库（第二层后路） */
    @Volatile var library = true
        private set

    private var loaded = false

    @Volatile private var appCtx: android.content.Context? = null

    fun init(context: android.content.Context) {
        appCtx = context.applicationContext
        if (loaded) return
        loaded = true
        runCatching {
            val p = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            qq = p.getBoolean("qq", true)
            script = p.getBoolean("script", true)
            meting = p.getBoolean("meting", true)
            library = p.getBoolean("library", true)
        }
    }

    /**
     * 应用服务端下发的 `flags.sources`。
     *
     * 任何一个字段缺省都**保持当前值**（不是重置为 true）：
     * 后台只想关掉一个源时，不必把其余三个也一起写进 JSON。
     */
    fun apply(sources: JSONObject?) {
        if (sources == null) return
        val oldQQ = qq
        val oldScript = script
        val oldMeting = meting
        val oldLib = library

        if (sources.has("qq")) qq = sources.optBoolean("qq", qq)
        if (sources.has("script")) script = sources.optBoolean("script", script)
        if (sources.has("meting")) meting = sources.optBoolean("meting", meting)
        if (sources.has("library")) library = sources.optBoolean("library", library)

        if (oldQQ != qq || oldScript != script || oldMeting != meting || oldLib != library) {
            Log.i(TAG, "远程开关变更：qq=$qq script=$script meting=$meting library=$library")
            runCatching {
                val ctx = appCtx ?: return@runCatching
                ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("qq", qq)
                    .putBoolean("script", script)
                    .putBoolean("meting", meting)
                    .putBoolean("library", library)
                    .apply()
            }
        }
    }
}
