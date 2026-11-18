package com.jun.musiccloud.ui

import org.json.JSONObject

/**
 * 单个歌词舞台的专属调校。
 *
 * 和「四轴调参」（密度/速度/发光/镜头）不同，这里给每个舞台一套更贴近它自身
 * 语义的参数：字号、动势、辉光，以及一个 0..3 的「形态」档位。
 * 形态档位在不同舞台里含义不同（云阶=引导线，回环=轴线，商籁=胶片后期…），
 * 由各舞台自己解释；StageScaffold 也会兜底画一层通用装饰，保证每个舞台
 * 切换形态时都有可见变化。
 *
 * 存成 "font,motion,glow,look" 四个整数，和四轴调参一样落 SharedPreferences，
 * 不引入序列化依赖。
 */
data class StageProfile(
    val font: Int = 100,   // 85..125 (%)
    val motion: Int = 100, // 0..150 (%)
    val glow: Int = 100,   // 0..150 (%)
    val look: Int = 0      // 0..3
) {
    val fontScale: Float get() = font.coerceIn(70, 150) / 100f
    val motionScale: Float get() = motion.coerceIn(0, 180) / 100f
    val glowScale: Float get() = glow.coerceIn(0, 180) / 100f
    val lookIndex: Int get() = look.coerceIn(0, 3)

    fun encode(): String = "$font,$motion,$glow,$look"

    companion object {
        val Default = StageProfile()

        fun decode(raw: String?): StageProfile {
            if (raw.isNullOrBlank()) return Default
            val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (parts.size != 4) return Default
            return StageProfile(
                font = parts[0].coerceIn(85, 125),
                motion = parts[1].coerceIn(0, 150),
                glow = parts[2].coerceIn(0, 150),
                look = parts[3].coerceIn(0, 3)
            )
        }
    }
}

internal data class StageProfileSpec(
    val motionLabel: String,
    val glowLabel: String,
    val lookLabel: String,
    val looks: List<String>
)

/**
 * 十四个舞台各自的文案与形态档位。
 * 文案只影响设置页，不影响渲染；渲染只认 0..3 的 look。
 */
internal object LyricStageProfiles {

    private val specs: Map<Int, StageProfileSpec> = mapOf(
        0 to StageProfileSpec(
            "流动", "辉光", "排版",
            listOf("标准", "紧凑", "宽松", "极简")
        ),
        1 to StageProfileSpec(
            "推镜", "辉光", "光柱",
            listOf("标准", "无光柱", "窄幅", "宽幅")
        ),
        2 to StageProfileSpec(
            "错峰", "辉光", "辅助线",
            listOf("无", "引导线", "中轴", "网格")
        ),
        3 to StageProfileSpec(
            "镜头", "辉光", "装饰",
            listOf("平滑镜头", "步进镜头", "隐藏印刷符号", "极简")
        ),
        4 to StageProfileSpec(
            "呼吸", "辉光", "画框",
            listOf("画框", "无框", "大肖像", "小肖像")
        ),
        5 to StageProfileSpec(
            "跳动", "辉光", "头像",
            listOf("封面头像", "纯色头像", "无头像", "紧凑")
        ),
        6 to StageProfileSpec(
            "倾斜", "辉光", "配色",
            listOf("默认", "主次互换", "全强调色", "全主色")
        ),
        7 to StageProfileSpec(
            "转速", "辉光", "轴线",
            listOf("无", "中轴", "椭圆环", "双环")
        ),
        8 to StageProfileSpec(
            "漂浮", "辉光", "粒子",
            listOf("标准", "强粒子", "无粒子", "灵魂残影")
        ),
        9 to StageProfileSpec(
            "摆幅", "辉光", "表盘",
            listOf("标准", "无齿轮", "表盘封面", "极简")
        ),
        10 to StageProfileSpec(
            "运镜", "辉光", "胶片",
            listOf("标准", "颗粒", "暗角", "颗粒+暗角")
        ),
        11 to StageProfileSpec(
            "入场", "辉光", "切句",
            listOf("半句", "整行", "强入场", "弱入场")
        ),
        12 to StageProfileSpec(
            "光流", "辉光", "光线",
            listOf("标准", "强光", "柔光", "无尘")
        ),
        13 to StageProfileSpec(
            "呼吸", "辉光", "留白",
            listOf("标准", "大字", "宽行距", "极简")
        )
    )

    fun of(stage: Int): StageProfileSpec =
        specs[stage.coerceIn(0, 13)] ?: specs.getValue(0)

    fun lookAt(stage: Int, look: Int): String =
        of(stage).looks.getOrElse(look.coerceIn(0, 3)) { "标准" }

    /** 设置页「应用到全部舞台」用：只复制数值，不复制文案。 */
    fun encode(p: StageProfile): String = p.encode()

    fun json(p: StageProfile): JSONObject = JSONObject().apply {
        put("font", p.font)
        put("motion", p.motion)
        put("glow", p.glow)
        put("look", p.look)
    }

    fun fromJson(o: JSONObject?): StageProfile {
        if (o == null) return StageProfile.Default
        return StageProfile(
            font = o.optInt("font", 100).coerceIn(85, 125),
            motion = o.optInt("motion", 100).coerceIn(0, 150),
            glow = o.optInt("glow", 100).coerceIn(0, 150),
            look = o.optInt("look", 0).coerceIn(0, 3)
        )
    }
}
