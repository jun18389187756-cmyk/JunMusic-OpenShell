package com.jun.musiccloud.audio

/**
 * 音效预设链。
 *
 * 把散落在设置里的 EQ / 虚拟低音 / 声场 / 动态范围组合成一条条可直接听的
 * 「成品链」，用户不需要理解每个参数，一键就是完整听感。
 *
 * 增益单位为 mB（1 dB = 100 mB），顺序与 [EqProcessor.BAND_FREQS] 一致：
 * 31 / 63 / 125 / 250 / 500 / 1k / 2k / 4k / 8k / 16k Hz。
 */
enum class FxPreset(
    val label: String,
    val subtitle: String,
    val gainsMb: IntArray,
    val virtualBass: Boolean = false,
    val bassStrength: Int = 0,
    val rangeEnhancer: Boolean = false,
    val rangeStrength: Int = 60,
    val surround: Boolean = false,
    val surroundStrength: Int = 0,
    val spatial: Boolean = false,
    val spatialStrength: Int = 0,
    val limiter: Boolean = true,
    val loudness: Boolean = false
) {
    FLAT(
        label = "原声",
        subtitle = "关闭所有增强，还原文件本身的听感",
        gainsMb = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        limiter = false
    ),
    VINYL(
        label = "黑胶",
        subtitle = "暖厚低频 + 柔化高频，像把唱片放进客厅",
        gainsMb = intArrayOf(300, 260, 120, 40, -40, -80, -40, -160, -360, -800),
        virtualBass = true,
        bassStrength = 30,
        rangeEnhancer = true,
        rangeStrength = 55,
        surround = true,
        surroundStrength = 18,
        limiter = true
    ),
    RADIO(
        label = "电台",
        subtitle = "收窄频宽 + 轻压缩，深夜电台的贴身感",
        gainsMb = intArrayOf(-1200, -900, -300, 80, 260, 300, 220, 20, -500, -1200),
        rangeEnhancer = true,
        rangeStrength = 80,
        limiter = true
    ),
    HALL(
        label = "现场",
        subtitle = "宽声场 + 早期反射，把舞台推到房间外",
        gainsMb = intArrayOf(180, 120, 60, 0, -60, -20, 80, 180, 260, 120),
        surround = true,
        surroundStrength = 55,
        spatial = true,
        spatialStrength = 70,
        limiter = true
    ),
    VOCAL(
        label = "人声",
        subtitle = "抬中频、收低频，咬字和气息更靠前",
        gainsMb = intArrayOf(-360, -240, -60, 120, 300, 360, 260, 100, -40, -260),
        rangeEnhancer = true,
        rangeStrength = 40,
        virtualBass = false,
        limiter = true
    ),
    BASS(
        label = "重低音",
        subtitle = "下潜与冲击一起加，注意音量",
        gainsMb = intArrayOf(650, 520, 360, 160, 0, -40, 0, 20, 40, 0),
        virtualBass = true,
        bassStrength = 65,
        rangeEnhancer = true,
        rangeStrength = 45,
        limiter = true
    );

    companion object {
        fun of(index: Int): FxPreset? = entries.getOrNull(index)
    }
}
