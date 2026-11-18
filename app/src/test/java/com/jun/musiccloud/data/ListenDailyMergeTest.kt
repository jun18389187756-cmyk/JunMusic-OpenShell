package com.jun.musiccloud.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 旧版心跳（只有每天总秒数）与新版明细（天 + 歌曲 + 毫秒）的合并口径。
 *
 * 线上真实情况：老账号只有 app_daily_user 里的心跳秒数，没有歌曲明细；
 * 升级后本机 CSV 会把同一段听歌补传成明细。两者必须逐日取 MAX，不能相加。
 */
class ListenDailyMergeTest {

    @Test
    fun legacyOnlyDayIsCounted() {
        val entries = emptyList<ListenEntry>()
        val legacy = mapOf(20_000L to 100_000L)
        val daily = ListenReportBuilder.mergedDaily(entries, legacy)
        assertEquals(100_000L, daily[20_000L])
        assertEquals(100_000L, ListenReportBuilder.legacyGapMs(entries, legacy))
    }

    @Test
    fun detailCoversLegacyWithoutGap() {
        val entries = listOf(ListenEntry(20_000L, 7L, 120_000L))
        val legacy = mapOf(20_000L to 100_000L)
        val daily = ListenReportBuilder.mergedDaily(entries, legacy)
        assertEquals(120_000L, daily[20_000L])
        assertEquals(0L, ListenReportBuilder.legacyGapMs(entries, legacy))
    }

    @Test
    fun partialLegacyDayAddsOnlyTheMissingPart() {
        val entries = listOf(ListenEntry(20_000L, 7L, 60_000L))
        val legacy = mapOf(20_000L to 100_000L)
        val daily = ListenReportBuilder.mergedDaily(entries, legacy)
        assertEquals(100_000L, daily[20_000L])
        assertEquals(40_000L, ListenReportBuilder.legacyGapMs(entries, legacy))
    }

    @Test
    fun legacyAcrossDaysAndReportBuild() {
        val entries = listOf(
            ListenEntry(20_000L, 7L, 60_000L),
            ListenEntry(20_001L, 8L, 30_000L)
        )
        val legacy = mapOf(
            20_000L to 50_000L,
            20_001L to 80_000L,
            19_000L to 10_000L
        )
        val daily = ListenReportBuilder.mergedDaily(entries, legacy)
        assertEquals(60_000L, daily[20_000L])
        assertEquals(80_000L, daily[20_001L])
        assertEquals(10_000L, daily[19_000L])

        val report = ListenReportBuilder.build(
            entries = entries,
            songs = emptyList(),
            range = StatRange.DAY,
            today = 20_001L,
            legacyDaily = legacy
        )
        assertEquals(80_000L, report.totalMs)
        assertEquals(150_000L, report.allTimeMs)
        assertEquals(60_000L, report.legacyGapMs)
        assertEquals(2, report.legacyDays)
    }
}
