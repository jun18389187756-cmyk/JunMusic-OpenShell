package com.jun.musiccloud.ui

/**
 * 「整体动画快慢」的桥。
 *
 * 为什么要有这么一层看起来多余的东西
 * ------------------------------
 * [JunMotion] 在 `ui/` 包里，[PlayerViewModel] 在 `player/` 包里。如果 ViewModel 直接
 * `import com.jun.musiccloud.ui.JunMotion`，就形成 `player → ui` 的依赖，而 `ui` 里的
 * 组件又依赖 `player` 的状态，成了一个环。这种环在 Kotlin 里编得过，但一旦下一步有人
 * 在 JunMotion 的顶层加个 @Composable，就会变成「ui 顶层初始化引用 player 顶层，
 * player 顶层又引用 ui 顶层」的启动期空指针——历史上踩过一次，所以这里留一道薄墙。
 *
 * 真正的数值只在 [JunMotion] 里存一份，这里只转发。
 */
object JunMotionSpeed {

    /** 默认档位：比设计基准快 15%（见 JunMotion.DEFAULT_SCALE 的注释） */
    const val DEFAULT: Float = JunMotion.DEFAULT_SCALE

    /** 设置项在 prefs 里的键。放常量而不是散着写字符串，避免读写键名不一致这种低级错 */
    const val KEY = "motion_speed_scale"

    /** 启动时把持久化的值灌进 JunMotion */
    fun apply(scale: Float) = JunMotion.setSpeed(scale)

    /** 当前值（设置页回显用） */
    fun current(): Float = JunMotion.timeScale

    /** 当前档位名 */
    fun label(scale: Float = JunMotion.timeScale): String = JunMotion.speedLabel(scale)
}
