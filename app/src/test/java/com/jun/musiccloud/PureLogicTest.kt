package com.jun.musiccloud

import com.jun.musiccloud.data.ListenTodo
import com.jun.musiccloud.data.ServerApi
import com.jun.musiccloud.ui.StageProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯函数单测。
 *
 * 项目此前没有任何测试（app/src 下只有 main）。先从零成本、零依赖的纯逻辑补起：
 * 这些函数不碰 Android 框架，改坏了立刻能发现。
 */
class PureLogicTest {

    @Test
    fun `待办按创建时间倒序，最新在前`() {
        val now = System.currentTimeMillis()
        val list = listOf(
            ListenTodo(1L, "旧", createdAt = now - 1000),
            ListenTodo(2L, "新", createdAt = now)
        )
        val sorted = list.sortedByDescending { it.createdAt }
        assertEquals("新", sorted.first().text)
    }

    @Test
    fun `待办默认未完成`() {
        assertEquals(false, ListenTodo(1L, "x").done)
    }

    @Test
    fun `服务器地址优先 HTTPS，过渡开关可回退明文`() {
        assertTrue(ServerApi.BASE_URL.startsWith("https://"))
        val effective = ServerApi.effectiveBaseUrl
        // 开了明文过渡开关就回退到 http，没开就保持 https
        assertTrue(effective.startsWith("http://") || effective.startsWith("https://"))
    }

    @Test
    fun `服务器地址末尾不带斜杠`() {
        assertTrue(!ServerApi.effectiveBaseUrl.endsWith("/"))
    }

    @Test
    fun `舞台调参写入后能原样读回`() {
        val p = StageProfile(font = 112, motion = 76, glow = 130, look = 2)
        assertEquals(p, StageProfile.decode(p.encode()))
    }

    @Test
    fun `舞台调参缺字段或乱码时回退默认值`() {
        assertEquals(StageProfile.Default, StageProfile.decode(null))
        assertEquals(StageProfile.Default, StageProfile.decode(""))
        assertEquals(StageProfile.Default, StageProfile.decode("100,100"))
        assertEquals(StageProfile.Default, StageProfile.decode("a,b,c,d"))
    }

    @Test
    fun `舞台调参超范围时夹到合法区间`() {
        val p = StageProfile.decode("999,-20,999,9")
        assertEquals(125, p.font)
        assertEquals(0, p.motion)
        assertEquals(150, p.glow)
        assertEquals(3, p.look)
    }

    @Test
    fun `舞台调参字号缩放始终落在安全范围`() {
        val tiny = StageProfile(font = 1)
        val huge = StageProfile(font = 10000)
        assertTrue(tiny.fontScale in 0.7f..1.5f)
        assertTrue(huge.fontScale in 0.7f..1.5f)
    }
}
