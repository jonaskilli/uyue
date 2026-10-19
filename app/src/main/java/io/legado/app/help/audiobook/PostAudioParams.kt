package io.legado.app.help.audiobook

/**
 * 后处理音频参数（合成后、格式转换时生效）
 * speed/volume/pitch 取值 0f 表示"跟随上级"，实际值范围 0.1~3.0
 */
data class PostAudioParams(
    var speed: Float = FOLLOW,
    var volume: Float = FOLLOW,
    var pitch: Float = FOLLOW,
) {
    companion object {
        const val FOLLOW = 0f
        const val DEFAULT = 1f
        const val MIN = 0.1f
        const val MAX = 3.0f
    }

    /**
     * 如果当前值为 FOLLOW(0)，则取 parent 的值
     */
    fun copyIfFollow(parent: PostAudioParams): PostAudioParams {
        return PostAudioParams(
            speed = if (speed <= 0f) parent.speed else speed,
            volume = if (volume <= 0f) parent.volume else volume,
            pitch = if (pitch <= 0f) parent.pitch else pitch,
        )
    }

    /**
     * 与上级**相乘**合并（各级音频参数的关系是累乘，不是覆盖）。
     *
     * 规则：
     * - 本级为 FOLLOW(0) → 视为 1.0，即本级不干预，只保留上级；
     * - 上级为 FOLLOW(0) → 同样视为 1.0；
     * - 两边都有值时直接相乘，使「全局 × 大分组 × 中分组 × 小分组 × 条目」逐级叠加。
     *
     * 与 [copyIfFollow] 的区别：后者是「本级有值就顶掉上级」，会让上级设置静默失效。
     */
    fun timesWithParent(parent: PostAudioParams): PostAudioParams {
        return PostAudioParams(
            speed = (if (speed <= 0f) DEFAULT else speed) *
                (if (parent.speed <= 0f) DEFAULT else parent.speed),
            volume = (if (volume <= 0f) DEFAULT else volume) *
                (if (parent.volume <= 0f) DEFAULT else parent.volume),
            pitch = (if (pitch <= 0f) DEFAULT else pitch) *
                (if (parent.pitch <= 0f) DEFAULT else parent.pitch),
        )
    }

    /**
     * 解析为实际生效值（FOLLOW 视为 1.0）
     */
    fun resolved(): PostAudioParams {
        return PostAudioParams(
            speed = if (speed <= 0f) DEFAULT else speed,
            volume = if (volume <= 0f) DEFAULT else volume,
            pitch = if (pitch <= 0f) DEFAULT else pitch,
        )
    }

    fun isFollow(): Boolean = speed <= 0f && volume <= 0f && pitch <= 0f

    fun isDefault(): Boolean {
        val r = resolved()
        return r.speed == DEFAULT && r.volume == DEFAULT && r.pitch == DEFAULT
    }
}
