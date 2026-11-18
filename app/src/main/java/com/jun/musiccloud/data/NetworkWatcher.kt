package com.jun.musiccloud.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * 网络切换时让缓存的音频直链失效（6.5）。
 *
 * 为什么需要这个：网易云给的直链是一个**带过期时间的签名 URL**，而且签名里绑了
 * 出口 IP / CDN 节点。App 里 [AudioProxyServer] 会把这个 URL 缓存 10 分钟，
 * 于是会出现一个很难复现的 bug ——
 *
 *   用 Wi-Fi 听了一首 → 走出门切到 4G → 再点那首 → 转圈几秒 → 报「暂时无版权」
 *   → 用户再点一次 → 正常播放
 *
 * 用户眼里这是"随机抽风"，实际上是缓存了一条在新网络下已经失效的直链。
 * 第二次能放，是因为第一次失败时已经把这条缓存作废了。
 *
 * 处理方式：监听默认网络切换，变了就清快源缓存（[OnlineMusicApi.invalidateFastCache]）。
 * 只清这一层，其它链路（GD / eapi）不动——它们的鉴权方式不同，且重建成本高。
 *
 * 注册在 Application，全进程只注册一次；回调发生在 ConnectivityManager 的内部线程，
 * 清缓存本身是 ConcurrentHashMap.clear()，无锁竞争、不碰 UI。
 */
object NetworkWatcher {

    private const val TAG = "JunNet"

    @Volatile
    private var registered = false

    /**
     * 上一次「网络指纹」：传输类型 + 是否计费网络。
     *
     * 不能只比 `Network` 对象：同一张 SIM 卡从 Wi-Fi 断到 4G 时，系统给的是两个
     * 不同的 Network 实例，这没问题；但地铁里 4G 频繁重连时会给出一串新实例，
     * 每次清缓存等于把预取全废掉，反而更慢。所以只在**传输类型真的变了**时才清。
     */
    @Volatile
    private var lastFingerprint: String = ""

    fun install(context: Context) {
        if (registered) return
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        lastFingerprint = fingerprintOf(cm)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleChange(cm)
            }

            override fun onLost(network: Network) {
                // 断网时不清：此时清掉，等重新联网后预取要全部重来一遍，
                // 而断网期间本来就放不了，清了纯亏。真正的切换由 onAvailable 兜住。
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities
            ) {
                handleChange(cm)
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { registered = true }
            .onFailure { Log.w(TAG, "registerNetworkCallback failed: ${it.message}") }
    }

    private fun handleChange(cm: ConnectivityManager) {
        val now = fingerprintOf(cm)
        if (now.isBlank() || now == lastFingerprint) return
        val before = lastFingerprint
        lastFingerprint = now
        // 首次拿到指纹（从 "" 变成有值）不算切换
        if (before.isBlank()) return
        Log.i(TAG, "network switched: $before -> $now, invalidate audio cache")
        runCatching { OnlineMusicApi.invalidateFastCache() }
    }

    /**
     * 网络指纹：传输类型 + 计费标记。
     *
     * 取 activeNetwork 的能力，而不是遍历所有 network——缓存失效只关心
     * 「App 现在走的是哪条路」，多网并存（Wi-Fi + 蜂窝）时其它网络的
     * 变化不该让当前播放链路失效。
     */
    private fun fingerprintOf(cm: ConnectivityManager): String {
        return runCatching {
            val n = cm.activeNetwork ?: return ""
            val c = cm.getNetworkCapabilities(n) ?: return ""
            val kind = when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            val metered = if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "free" else "metered"
            "$kind/$metered"
        }.getOrDefault("")
    }
}
