package com.jun.musiccloud.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 听歌报告「本机 + 云端」合并口径的回归测试。
 *
 * 这组断言对应线上的三种真实场景：
 *  1. 本机已上传：云端 ms 包含本机 own，不能把本机那份再加一遍；
 *  2. 刚听完还没上传：本地比 own 大，要取本地；
 *  3. 换手机：另一台设备的时长要加进来。
 */
class ListenCloudMergeTest {

    @Test
    fun localOnlyKeepsLocal() {
        val local = listOf(ListenEntry(100L, 7L, 60_000L))
        assertEquals(local, ListenCloud.mergeEntries(local, emptyList()))
    }

    @Test
    fun uploadedLocalIsNotDoubleCounted() {
        val local = listOf(ListenEntry(100L, 7L, 60_000L))
        val cloud = listOf(CloudListenEntry(100L, 7L, ms = 60_000L, ownMs = 60_000L))
        val merged = ListenCloud.mergeEntries(local, cloud)
        assertEquals(1, merged.size)
        assertEquals(60_000L, merged.first().ms)
    }

    @Test
    fun newLocalTimeIsKeptWhenNotUploadedYet() {
        val local = listOf(ListenEntry(100L, 7L, 90_000L))
        val cloud = listOf(CloudListenEntry(100L, 7L, ms = 60_000L, ownMs = 60_000L))
        val merged = ListenCloud.mergeEntries(local, cloud)
        assertEquals(90_000L, merged.first().ms)
    }

    @Test
    fun otherDeviceTimeIsAdded() {
        val local = listOf(ListenEntry(100L, 7L, 30_000L))
        // 另一台设备听了 60 秒；本机还没上传过，own = 0
        val cloud = listOf(CloudListenEntry(100L, 7L, ms = 60_000L, ownMs = 0L))
        val merged = ListenCloud.mergeEntries(local, cloud)
        assertEquals(90_000L, merged.first().ms)
    }

    @Test
    fun oldDeviceDataIsAddedWhenLocalHasNoRow() {
        val local = emptyList<ListenEntry>()
        val cloud = listOf(
            CloudListenEntry(100L, 7L, ms = 60_000L, ownMs = 0L),
            CloudListenEntry(101L, 8L, ms = 30_000L, ownMs = 30_000L)
        )
        val merged = ListenCloud.mergeEntries(local, cloud)
        assertEquals(2, merged.size)
        assertEquals(60_000L, merged.first { it.songId == 7L }.ms)
        assertEquals(30_000L, merged.first { it.songId == 8L }.ms)
    }
}
