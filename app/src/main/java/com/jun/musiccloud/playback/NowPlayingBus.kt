package com.jun.musiccloud.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 播放状态的只读快照，供动态壁纸 / 局域网遥控等非 UI 消费者使用 */
data class NowPlayingSnapshot(
    val title: String = "Jun Music",
    val artist: String = "",
    val album: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lyricKey: String = "",
    val artworkKey: String = "",
    val currentIndex: Int = -1,
    val queueSize: Int = 0,
    val volume: Float = 1f,
    val updatedAt: Long = 0L
)

/**
 * 进程内播放状态总线。
 *
 * MusicService 是唯一写入方，壁纸与遥控 HTTP 服务只读。用 StateFlow 而不是广播：
 * 没有序列化成本，订阅方拿到的一定是完整快照，不会读到半更新的字段。
 */
object NowPlayingBus {
    private val _state = MutableStateFlow(NowPlayingSnapshot())
    val state: StateFlow<NowPlayingSnapshot> = _state.asStateFlow()

    fun publish(snapshot: NowPlayingSnapshot) {
        _state.value = snapshot
    }
}
