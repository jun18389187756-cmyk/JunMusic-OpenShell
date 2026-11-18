/*
 * This file is part of JUN Music.
 *
 * JUN Music is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JUN Music is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JUN Music.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.jun.musiccloud.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 全局矢量图标集（Material Symbols Rounded 路径）。
 *
 * 项目不引 material-icons-extended（省 5MB+），图标一律用内联 path 现建。
 * 原先 PlayerCard / PosterWall / NightFlightScene 各自复制了一份 `icon()`
 * 构建器和 Play/Pause 的路径字符串——同一份数据散在三处，改一个漏两个。
 * 这里收拢成唯一来源：所有 ImageVector 都 [lazy] 构建、构建一次全进程复用。
 */
internal object JunIcons {
    /** 24dp viewport 的单路径图标构建器。填充色固定黑，实际着色由 Icon(tint=) 决定。 */
    private fun icon(path: String): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = SolidColor(Color.Black)
        )
    }.build()

    val Play by lazy { icon("M8,5.14v13.72c0,0.79 0.87,1.27 1.54,0.84l10.79,-6.86c0.62,-0.39 0.62,-1.29 0,-1.69L9.54,4.29C8.87,3.87 8,4.34 8,5.14z") }
    val Pause by lazy { icon("M8,19c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2S6,5.9 6,7v10C6,18.1 6.9,19 8,19zM16,19c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2s-2,0.9 -2,2v10C14,18.1 14.9,19 16,19z") }
    /** 歌曲行末尾的「更多」：竖排三点（Material more_vert）。 */
    /**
     * 竖三点。原版是 Material MoreVert：r=2、圆心 y=6/12/18 → 圆心间距 6、
     * 边缘间隙只有 2（gap/pitch = 0.333）。行尾按 15~17dp 渲染时，两侧各吃掉约 1px
     * 反锯齿后只剩 1px 缝，三个点在真机上糊成两个 —— 用户反馈的「为什么是两个点」
     * 就是这个，不是少画了一个点。
     * 这里换成参考实现的几何：r=1.85、圆心 y=5.5/12/18.5 → 间距 6.5、间隙 2.8
     * （gap/pitch = 0.431），小尺寸下三点仍分得开。
     */
    val More by lazy { icon("M12,5.5m-1.85,0a1.85,1.85 0,1 1,3.7 0a1.85,1.85 0,1 1,-3.7 0M12,12m-1.85,0a1.85,1.85 0,1 1,3.7 0a1.85,1.85 0,1 1,-3.7 0M12,18.5m-1.85,0a1.85,1.85 0,1 1,3.7 0a1.85,1.85 0,1 1,-3.7 0") }
    val Shuffle by lazy {
        icon("M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5V4h-5.5zM14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z")
    }
    val Repeat by lazy {
        icon("M7,7h10v3l4,-4 -4,-4v3L5,5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z")
    }
    val Bedtime by lazy {
        icon("M9.37,5.51C9.19,6.15,9.1,6.82,9.1,7.5c0,4.08,3.32,7.4,7.4,7.4c0.68,0,1.35,-0.09,1.99,-0.27C17.45,17.19,14.93,19,12,19c-3.86,0,-7,-3.14,-7,-7C5,9.07,6.81,6.55,9.37,5.51zM12,3c-4.97,0,-9,4.03,-9,9s4.03,9,9,9s9,-4.03,9,-9c0,-0.46,-0.04,-0.92,-0.1,-1.36c-0.98,1.37,-2.58,2.26,-4.4,2.26c-2.98,0,-5.4,-2.42,-5.4,-5.4c0,-1.81,0.89,-3.42,2.26,-4.4C12.92,3.04,12.46,3,12,3L12,3z")
    }
    /** 均衡器：Material Tune（三条推子） */
    val Tune by lazy {
        icon("M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6H13zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2H21zM15,9h2V7h4V5h-4V3h-2V9z")
    }
    /** 设置：Material Symbols Rounded 齿轮（外齿 + 中心圆孔） */
    val Settings by lazy {
        icon("M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z")
    }
    /** 点赞：Material Favorite（实心爱心） */
    val Favorite by lazy {
        icon("M12,21.35l-1.45,-1.32C5.4,15.36,2,12.28,2,8.5C2,5.42,4.42,3,7.5,3c1.74,0,3.41,0.81,4.5,2.09C13.09,3.81,14.76,3,16.5,3C19.58,3,22,5.42,22,8.5c0,3.78,-3.4,6.86,-8.55,11.54L12,21.35z")
    }
    /** 取消点赞：Material FavoriteBorder（空心爱心） */
    val FavoriteBorder by lazy {
        icon("M16.5,3c-1.74,0,-3.41,0.81,-4.5,2.09C10.91,3.81,9.24,3,7.5,3C4.42,3,2,5.42,2,8.5c0,3.78,3.4,6.86,8.55,11.54L12,21.35l1.45,-1.32C18.6,15.36,22,12.28,22,8.5C22,5.42,19.58,3,16.5,3zM12.1,18.55l-0.1,0.1l-0.1,-0.1C7.14,14.24,4,11.39,4,8.5C4,6.5,5.5,5,7.5,5c1.54,0,3.04,0.99,3.57,2.36h1.87C13.46,5.99,14.96,5,16.5,5c2,0,3.5,1.5,3.5,3.5C20,11.39,16.86,14.24,12.1,18.55z")
    }
    /** 首页：Material Home */
    val Home by lazy {
        icon("M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z")
    }
    /** 排列方式：Material Dashboard（四宫格） */
    val Layout by lazy {
        icon("M3,13h8V3H3v10zM3,21h8v-6H3v6zM13,21h8V11h-8v10zM13,3v6h8V3h-8z")
    }
    /** 播放列表：Material Queue Music（三横线 + 音符） */
    val QueueMusic by lazy {
        icon("M15,6H3v2h12V6zM15,10H3v2h12v-2zM3,16h8v-2H3v2zM17,6v8.18C16.69,14.07 16.35,14 16,14c-1.66,0 -3,1.34 -3,3s1.34,3 3,3s3,-1.34 3,-3V8h3V6h-5z")
    }
    /** 上一曲：Material Skip Previous（竖线 + 左三角） */
    val SkipPrevious by lazy {
        icon("M6,6h2v12H6V6zM9.5,12l8.5,6V6l-8.5,6z")
    }
    /** 下一曲：Material Skip Next（右三角 + 竖线） */
    val SkipNext by lazy {
        icon("M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    }
    /** 添加到歌单：Material Playlist Add（列表 + 加号） */
    val PlaylistAdd by lazy {
        icon("M14,10H3v2h11v-2zM14,6H3v2h11V6zM3,16h7v-2H3v2zM19,13v-3h-2v3h-3v2h3v3h2v-3h3v-2h-3z")
    }
    /** 下一首播放：Material Queue Next（横线 + 右下箭头） */
    val QueueNext by lazy {
        icon("M3,17h12v-2H3v2zM3,11v2h8v-2H3zM17,15v-6h-2V7l4,-4 4,4v-2h-2v6H17zM3,7v2h12V7H3z")
    }
    /**
     * 下一首播放：左边两行队列、右边一枚实心播放三角。
     *
     * 不用 [QueueNext]：它是 Material queue_music，箭头那段路径的 x 一路画到 25，
     * 而 viewport 只有 24 —— 三角的右半边在 24dp 图标里被直接裁掉，小尺寸下看
     * 过去就是个"缺角的方块"，这就是用户说的"下一首播放那个按钮不好看"。
     * 这里所有坐标都收在 2..22、上下居中在 y=12，任何尺寸下都是完整形状。
     */
    val PlayNext by lazy {
        icon("M2,6.4H12.2V8.2H2V6.4zM2,10.1H9.4V11.9H2V10.1zM13.6,17.4V6.6L22,12L13.6,17.4z")
    }
    /** 下载：Material Download（向下箭头 + 底线） */
    val Download by lazy {
        icon("M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    }
    /** 重命名：Material Edit（铅笔） */
    val Edit by lazy {
        icon("M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z")
    }
    /** 搜索：Material Search（圆 + 斜线） */
    val Search by lazy {
        icon("M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")
    }
    /** 全选：Material Check Box（勾选方框） */
    val CheckBox by lazy {
        icon("M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z")
    }
    /**
     * 未勾选方框：Material check_box_outline_blank（只有一圈边框、里面是空的）。
     *
     * 6.0 新增。之前"全选"那颗钮不管有没有选中都用 [CheckBox]，而这个字形里**自带对勾**，
     * 于是没选任何东西的时候也看到一个对勾 —— 用户的原话：「正常的话都是没有那个对勾的，
     * 点开全选之后才会有」。未选中态必须画空框，勾选态才画带勾的框。
     */
    /**
     * 单独一枚对勾：Material check。
     *
     * 6.0 新增。勾选框里那颗勾要**只有勾**：以前是把 [CheckBox]（自带方框的
     * check_box）整枚画进已经填好底色的方框里，等于框里再套一个框，小尺寸下
     * 两层边框糊在一起，勾反而看不清。
     */
    val Check by lazy {
        icon("M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    }
    val CheckBoxBlank by lazy {
        icon("M19,5V19H5V5H19M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2z")
    }
    /** 换一批 / 刷新：Material Refresh */
    val Refresh by lazy {
        icon("M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z")
    }
    /** 分享：Material Share */
    val Share by lazy {
        icon("M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92s2.92,-1.31 2.92,-2.92 -1.31,-2.92 -2.92,-2.92z")
    }
    /** 文件夹：Material Folder */
    val Folder by lazy {
        icon("M10,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    }
    /** 评论：Material Comment（聊天气泡） */
    val Comment by lazy {
        icon("M21.99,4c0,-1.1 -0.89,-2 -1.99,-2H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h14l4,4 -0.01,-18z")
    }
    /** 退出/登出：Material Logout（门 + 右箭头） */
    val Logout by lazy {
        icon("M17,7l-1.41,1.41L18.17,11H8v2h10.17l-2.58,2.58L17,17l5,-5zM4,5h8V3H4c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h8v-2H4V5z")
    }
    /** 相似歌曲：交叉箭头（两箭头交叉，表示相似/相关推荐） */
    val Similar by lazy {
        icon("M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z")
    }
    /** 横屏沉浸：Material Screen Rotation（斜放的手机 + 旋转箭头） */
    val ScreenRotate by lazy {
        icon("M16.48,2.52c3.27,1.55 5.61,4.72 5.97,8.48h1.5C23.44,4.84 18.29,0 12,0l-0.66,0.03 3.81,3.81 1.33,-1.32zM10.23,1.75c-0.59,-0.59 -1.54,-0.59 -2.12,0L1.75,8.11c-0.59,0.59 -0.59,1.54 0,2.12l12.02,12.02c0.59,0.59 1.54,0.59 2.12,0l6.36,-6.36c0.59,-0.59 0.59,-1.54 0,-2.12L10.23,1.75zM14.83,21.19L2.81,9.17l6.36,-6.36 12.02,12.02 -6.36,6.36zM7.52,21.48C4.25,19.94 1.91,16.76 1.55,13H0.05C0.56,19.16 5.71,24 12,24l0.66,-0.03 -3.81,-3.81 -1.33,1.32z")
    }
    /** 最近批量操作：Material History（带逆时针箭头的表盘） */
    val History by lazy {
        icon("M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.54,19.99 10.61,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.25,2.52 0.75,-1.23 -3.5,-2.08L13.5,8L12,8z")
    }
}
