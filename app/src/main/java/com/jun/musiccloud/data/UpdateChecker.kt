/*
 * This file is part of JUN Music.
 * Copyright (C) 2026 WWRJ
 *
 * JUN Music is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.jun.musiccloud.data

import com.jun.musiccloud.BuildConfig

/**
 * 检查更新：向自有服务器 GET /api/app/version，与本地 versionCode 比较。
 *
 * 与旧版（GitHub Releases）的区别：返回的不只是「有没有新版」，而是完整更新描述
 * （下载地址 / 更新日志 / 是否强制）。是否强更由服务器决定：
 *   - force = true            → App 弹不可取消的更新框
 *   - 本地 versionCode 低于服务器 minVersionCode → 同样视为强制
 *
 * 未配置服务器（[ServerApi.BASE_URL] 为空）时直接返回 [Result.Unconfigured]，完全不联网。
 */
object UpdateChecker {

    /** 一次可用的更新描述 */
    data class Update(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String,
        val force: Boolean,
        /** 安装包大小（字节）；0 表示服务器没给，下载进度按实际响应计算 */
        val size: Long = 0L,
        /** 安装包 SHA-256；非空时下载完必须校验，防止下到坏包 / 被替换的包 */
        val sha256: String = ""
    )

    sealed class Result {
        /** 已是最新（或服务器版本不比本地新） */
        object UpToDate : Result()
        /** 服务器有更新版本 */
        data class NewVersion(val update: Update) : Result()
        /** 网络错误 / 解析失败 */
        object Failed : Result()
        /** 尚未接入服务器（BASE_URL 为空） */
        object Unconfigured : Result()
    }

    /** HTTP 在 IO 线程执行（ServerApi 内部已切 IO），主线程可安全调用。 */
    suspend fun check(context: android.content.Context? = null): Result {
        if (!ServerApi.isConfigured) return Result.Unconfigured
        // 渠道 + 稳定分桶：后台灰度发布时同一台设备每次结果一致
        val bucket = context?.let { Telemetry.installBucket(it) } ?: -1
        // 设备/账号标识：后台可只给指定设备或账号推送测试版本；匿名也能正常检查公开版本
        val deviceKey = context?.let { Telemetry.hwId(it) } ?: ""
        val remote = ServerApi.fetchVersion(
            channel = "stable",
            bucket = bucket,
            deviceKey = deviceKey,
            userId = AccountStore.userId,
            versionCode = BuildConfig.VERSION_CODE,
            model = android.os.Build.MODEL ?: ""
        ) ?: return Result.Failed
        val localCode = BuildConfig.VERSION_CODE
        if (remote.versionCode <= localCode) return Result.UpToDate
        // 服务器显式强更，或本地低于最低支持版本，都按强制处理
        val force = remote.force ||
            (remote.minVersionCode > 0 && localCode < remote.minVersionCode)
        return Result.NewVersion(
            Update(
                versionCode = remote.versionCode,
                versionName = remote.versionName,
                apkUrl = remote.apkUrl,
                changelog = remote.changelog,
                force = force,
                size = remote.size,
                sha256 = remote.sha256
            )
        )
    }
}
