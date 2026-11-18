package com.jun.musiccloud.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 批量歌词导出。
 *
 * 之前导出跑在设置页的 rememberCoroutineScope 里：一旦手滑关掉设置，
 * 协程随组合一起销毁，压到一半的 ZIP 就留成垃圾文件。
 * 这里用进程级 scope 跑导出，离开设置页、切到别的 tab 都不影响，
 * 进度通过 StateFlow 回传；用户真正退出 App 时进程结束，导出自然停止。
 *
 * 不做成 WorkManager：这个项目只发单 APK，导出是用户主动等待的短任务，
 * 加一整个 work-runtime 依赖不划算。
 */
data class LyricExportState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val status: String = "",
    /** 导出的 ZIP；分享完成后由 UI 调 [LyricExportManager.markShared] 清掉。 */
    val zipFile: File? = null,
    val exportedCount: Int = 0
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

object LyricExportManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LyricExportState())
    val state: StateFlow<LyricExportState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(context: Context, songs: List<Song>) {
        if (_state.value.running) return
        val exportable = songs.count { it.lyricKey.isNotBlank() }
        if (exportable == 0) {
            _state.value = LyricExportState(status = "曲库里还没有可导出的歌词")
            return
        }
        val appContext = context.applicationContext
        job = scope.launch {
            _state.value = LyricExportState(running = true, total = exportable)
            val dir = File(appContext.cacheDir, "export_lyrics").apply { mkdirs() }
            val zip = File(dir, "JunMusic-歌词导出-${System.currentTimeMillis()}.zip")
            var count = 0
            val used = HashSet<String>()
            try {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { zos ->
                    for (song in songs) {
                        // 每首导出前检查取消：用户点「取消」后立刻停手，不再继续压包。
                        ensureActive()
                        if (song.lyricKey.isBlank()) continue
                        val lines = runCatching {
                            LyricParser.loadLyrics(song.lyricKey, song.durationMs)
                        }.getOrDefault(emptyList())
                        if (lines.isNotEmpty()) {
                            var name = "${song.artist} - ${song.title}"
                                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                .take(60).ifBlank { "lyrics" }
                            if (!used.add(name.lowercase())) {
                                name += " (${song.id})"
                                used.add(name.lowercase())
                            }
                            zos.putNextEntry(ZipEntry("$name.lrc"))
                            zos.write(LyricParser.exportEnhancedLrc(lines).toByteArray())
                            zos.closeEntry()
                            count++
                        }
                        _state.value = _state.value.copy(
                            done = _state.value.done + 1
                        )
                    }
                }
                if (count == 0) {
                    zip.delete()
                    _state.value = LyricExportState(
                        status = "没有找到可导出的歌词（需要本地或已缓存的歌词文件）"
                    )
                } else {
                    _state.value = LyricExportState(
                        done = exportable,
                        total = exportable,
                        status = "已打包 $count 首增强 LRC",
                        zipFile = zip,
                        exportedCount = count
                    )
                }
            } catch (ce: CancellationException) {
                zip.delete()
                _state.value = LyricExportState(status = "已取消，未完成的压缩包已删除")
                throw ce
            } catch (t: Throwable) {
                zip.delete()
                _state.value = LyricExportState(
                    status = "导出失败：${t.message ?: "无法写入文件"}"
                )
            } finally {
                job = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /** 分享面板已经拉起，清掉文件引用，避免每次重组重复分享。 */
    fun markShared() {
        if (_state.value.zipFile != null) {
            _state.value = _state.value.copy(zipFile = null)
        }
    }
}
