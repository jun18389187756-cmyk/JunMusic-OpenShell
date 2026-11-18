package com.jun.musiccloud.security

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.jun.musiccloud.BuildConfig

/**
 * 应用完整性自检（轻量防篡改）。
 *
 * 这是"提高破解成本"而非"绝对防破解"——任何客户端校验都能被逆向绕过，真正的防线在
 * 服务端（ token 校验、请求签名、风控）。这里做三件成本低、收益实在的事：
 *
 * 1. **安装来源校验**：非官方渠道（非应用商店 / 非系统安装器）安装的包给出提醒。
 *    侧载的破解包、被重签发的仿冒包都走不到官方商店，能拦下绝大部分"随手下的盗版"。
 * 2. **调试/可调试校验**：release 包本不该带 `debuggable`，带了的一定是被重新打包过。
 * 3. **签名哈希钉选（可选）**：把正式签名证书的 SHA-256 写进 [BuildConfig.EXPECTED_SIGNATURE]，
 *    运行时比对当前包签名。仿冒包重签名后哈希必然不同，直接判定为篡改。
 *    未配置（空串）时跳过——本项目源码不含签名私钥，无法在构建脚本里自动算哈希，
 *    需你拿到自己的 jks 后，在 `app/build.gradle.kts` 里注入 `EXPECTED_SIGNATURE` 才会生效。
 *
 * 调用方返回 [Issue] 列表，由 UI 决定是否提示（建议只提示，不直接退出，
 * 避免误伤合法用户导致进不去 App）。
 */
object AppIntegrity {

    /** 自检发现的问题 */
    data class Issue(val level: Level, val message: String) {
        enum class Level { WARN, ERROR }
    }

    /** 最近一次自检结果（启动时跑一次），UI 在「关于」页读取展示 */
    var lastIssues: List<Issue> = emptyList()
        private set

    /** 跑一次自检并缓存结果。启动早期调用一次即可。 */
    fun run(context: Context) {
        lastIssues = runCatching { check(context) }.getOrDefault(emptyList())
        for (it in lastIssues) {
            android.util.Log.w("AppIntegrity", "[${it.level}] ${it.message}")
        }
    }

    /**
     * 执行自检。返回非空列表表示发现疑点。
     * 正常情况返回空列表（无问题）。
     */
    fun check(context: Context): List<Issue> {
        val issues = mutableListOf<Issue>()

        // 1) 安装来源
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()

        val isOfficialSource = installer in OFFICIAL_INSTALLERS || installer == context.packageName
        if (!isOfficialSource && !BuildConfig.DEBUG) {
            issues += Issue(
                Issue.Level.WARN,
                "该应用并非从官方应用商店安装（来源：${installer ?: "未知"}），请注意甄别真伪。"
            )
        }

        // 2) 可调试
        val appInfo = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, 0)
        }.getOrNull()
        if (appInfo != null && (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            if (!BuildConfig.DEBUG) {
                issues += Issue(
                    Issue.Level.ERROR,
                    "检测到此安装包为可调试版本，极可能是被重新打包的篡改包，请勿输入账号密码。"
                )
            }
        }

        // 3) 签名哈希钉选（可选）
        val expected = BuildConfig.EXPECTED_SIGNATURE
        if (expected.isNotBlank()) {
            val actual = runCatching { currentSignatureSha256(context) }.getOrNull()
            if (actual != null && !sameDigest(actual, expected)) {
                issues += Issue(
                    Issue.Level.ERROR,
                    "应用签名与官方不符，可能是被重签名的仿冒包，请勿输入账号密码。"
                )
            }
        }

        return issues
    }

    private fun currentSignatureSha256(context: Context): String? {
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
        )
        val sig: Signature? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures?.firstOrNull()
        } ?: return null
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(sig!!.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun sameDigest(a: String, b: String): Boolean =
        a.equals(b, ignoreCase = true)

    /** 视为"官方"的安装来源包名 */
    private val OFFICIAL_INSTALLERS = setOf(
        "com.android.vending",          // Google Play
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.appstore", // 三星
        "com.huawei.appmarket",         // 华为
        "com.xiaomi.market",            // 小米
        "com.oppo.market",              // OPPO
        "com.bbk.appstore",             // vivo
        "com.meizu.mstore"              // 魅族
    )
}
