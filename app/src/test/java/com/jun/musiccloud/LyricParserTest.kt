package com.jun.musiccloud

import com.jun.musiccloud.data.LyricParser
import org.junit.Assert.assertTrue
import org.junit.Test

/** 歌词解析：时间标签、空行、非法行都要扛住 */
class LyricParserTest {

    @Test
    fun `标准 LRC 能解析出多行`() {
        val lrc = "[00:12.50]第一行\n[00:15.20]第二行\n[00:20.00]"
        val lines = LyricParser.parseText(lrc, 60_000L)
        assertTrue("应解析出至少两行，实际 ${lines.size}", lines.size >= 2)
    }

    @Test
    fun `空歌词不会抛异常`() {
        val lines = LyricParser.parseText("", 0L)
        assertTrue(lines.isEmpty() || lines.isNotEmpty())
    }

    @Test
    fun `没有时间标签的文本不应崩`() {
        val lines = LyricParser.parseText("这是一段没有时间轴的纯文本\n第二行", 0L)
        assertTrue(lines.isEmpty() || lines.isNotEmpty())
    }
}
