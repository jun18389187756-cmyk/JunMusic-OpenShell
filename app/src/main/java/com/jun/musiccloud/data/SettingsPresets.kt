package com.jun.musiccloud.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 配置档（6.2 新增）：把"我现在这一整套调校"存成一个有名字的快照，随时切回去。
 *
 * 和云同步的区别
 * --------------
 * 云端只有**一个**槽位（`user_settings` 一人一条），语义是"当前这台设备的镜像"，
 * 换设备/重装自动带回，解决的是"别让我重调"。
 * 配置档是**多份**，语义是"我想要的几种样子"：夜里沉浸一套、省电流一套、
 * 接外放时一套。云同步不会替你保存历史版本，配置档会。
 *
 * 存的是同一份快照格式（[SettingsSync.snapshot]），所以：
 *   - 配置档可以直接当云快照上传；
 *   - 云快照恢复前可以先存成一个配置档保底；
 *   - 导出的 JSON 与本地备份文件的 settings 段可以互读（类型标记两种写法都认）。
 *
 * 存在 filesDir/presets/<id>.json，不进数据库、不占同步配额；卸载即消失，
 * 所以设置页上写的是"配置档只在本机，要带走请导出或上传云端"。
 */
object SettingsPresets {

    data class Item(val id: String, val name: String, val createdAt: Long, val items: Int)

    private fun dir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "presets").apply { if (!exists()) mkdirs() }

    /** 文件名只用哈希，用户起的名字可能有斜杠/emoji/重名，直接拼路径会穿越 */
    private fun idOf(name: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((name.trim() + '|' + System.currentTimeMillis()).toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 6).joinToString("") { "%02x".format(it) }

    private fun read(file: File): Item? {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val snap = o.optString("settings")
        if (snap.isBlank()) return null
        return Item(
            id = file.nameWithoutExtension,
            name = o.optString("name").ifBlank { "未命名" },
            createdAt = o.optLong("created_at"),
            items = SettingsSync.countOf(snap)
        )
    }

    fun list(ctx: Context): List<Item> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { read(it) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    /** 以当前设置新建一档；名字重复时自动加序号，避免覆盖掉用户已有的档 */
    fun save(ctx: Context, rawName: String): Item? {
        val name = rawName.trim().take(24).ifBlank { "配置档" }
        val base = dir(ctx)
        val taken = list(ctx).map { it.name }.toSet()
        val finalName = if (name !in taken) name
                        else (2..20).firstOrNull { "$name $it" !in taken }?.let { "$name $it" } ?: "$name 20"
        val snapshot = SettingsSync.snapshot(ctx)
        if (SettingsSync.countOf(snapshot) == 0) return null
        val id = idOf(finalName)
        val file = File(base, "$id.json")
        val json = JSONObject()
            .put("name", finalName)
            .put("created_at", System.currentTimeMillis())
            .put("settings", snapshot)
        if (runCatching { file.writeText(json.toString()) }.isFailure) return null
        return read(file)
    }

    /** 应用一档，返回写入项数；找不到返回 -1 */
    fun apply(ctx: Context, id: String): Int {
        val file = safeFile(ctx, id) ?: return -1
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return -1
        val snap = o.optString("settings")
        if (snap.isBlank()) return -1
        return SettingsSync.apply(ctx, snap)
    }

    fun snapshotOf(ctx: Context, id: String): String {
        val file = safeFile(ctx, id) ?: return ""
        return runCatching { JSONObject(file.readText()).optString("settings") }.getOrDefault("")
    }

    fun delete(ctx: Context, id: String): Boolean = safeFile(ctx, id)?.delete() == true

    /** 只改显示名，id 与内容不动（id 来自时间戳哈希，改名不会让导出/深链失效） */
    fun rename(ctx: Context, id: String, rawName: String): Boolean {
        val file = safeFile(ctx, id) ?: return false
        val name = rawName.trim().take(24).ifBlank { return false }
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return false
        o.put("name", name)
        return runCatching { file.writeText(o.toString()) }.isSuccess
    }

    /**
     * 导入一份快照文本（来自本地备份 JSON 的 settings 段、别人分享的配置档、
     * 或云端拉下来的快照）。返回新档，失败返回 null。
     */
    fun importText(ctx: Context, raw: String, name: String): Item? {
        val snapshot = runCatching {
            val o = JSONObject(raw)
            // 兼容整份备份文件：{name?, settings:"..."} 与裸快照 {"键":{t,v}} 两种
            if (o.has("settings") && o.optString("settings").isNotBlank()) o.optString("settings") else raw
        }.getOrDefault(raw)
        if (SettingsSync.countOf(snapshot) == 0) return null
        val id = idOf(name)
        val file = File(dir(ctx), "$id.json")
        val json = JSONObject()
            .put("name", name.trim().take(24).ifBlank { "导入的配置" })
            .put("created_at", System.currentTimeMillis())
            .put("settings", snapshot)
        if (runCatching { file.writeText(json.toString()) }.isFailure) return null
        return read(file)
    }

    /**
     * 只按 [id] 取文件。id 是自己生成的十六进制串，但入参可能来自剪贴板/深链，
     * 这里做白名单校验，防止 "../" 之类的路径穿越读到包外文件。
     */
    private fun safeFile(ctx: Context, id: String): File? {
        if (!id.matches(Regex("^[0-9a-f]{12}$"))) return null
        val f = File(dir(ctx), "$id.json")
        return if (f.isFile) f else null
    }
}
