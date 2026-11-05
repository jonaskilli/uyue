package io.legado.app.model

import org.json.JSONObject
import splitties.init.appCtx

/**
 * 把「已经真正触发的音效」映射成一条瞬时画面特效。
 *
 * 设计要点：**不新增触发配置、不改任何已有配置文件**。
 * - 规则音效的 name 来自 ttsrv-replaces*.json 的 replacement 生成的 "(爆炸音效)" 标记；
 * - AI 音效的 name / fileName 来自 AiEffectPick（候选库就是 ai_effect_index_kw.json 的「正名|关键词」）；
 * - 这里只做「音效语义 → 画面 kind」的一层薄映射，词表沿用
 *   SceneEffectGenerator.EXTRA_RULES 的分类思路，按「具体 → 泛化」排序，第一个命中生效。
 *
 * 因此用户原有的正则替换规则、AI 音效提示词、音效库索引全都无需改动，
 * 加一条新规则即自动带出对应画面。
 */
object SoundEffectSceneMapper {

    private val RULES: List<Triple<SceneEffectKind, List<String>, Int>> = listOf(
        // ===== 瞬时爆发类（本次新增，最贴合爆炸 / 火焰 / 比武斩杀） =====
        Triple(
            SceneEffectKind.EXPLOSION,
            listOf("爆炸", "爆裂", "炸开", "炸毁", "爆破", "轰隆", "轰然", "轰炸", "炸", "砰", "嘭"), 4
        ),
        Triple(
            SceneEffectKind.SLASH,
            listOf("拔刀", "出鞘", "剑气", "刀光", "剑鸣", "挥刀", "挥剑", "斩", "劈", "砍", "刺出", "剑锋", "刀锋", "比武", "过招", "唰"), 3
        ),
        Triple(
            SceneEffectKind.IMPACT,
            listOf("暴击", "重击", "重创", "击中", "命中", "撞击", "碰撞", "打斗", "格挡", "拳", "掌风", "咚", "哐"), 3
        ),
        Triple(
            SceneEffectKind.SHOCKWAVE,
            listOf("冲击波", "气浪", "震波", "震荡", "余波", "威压", "地动", "山摇"), 3
        ),
        // ===== 火 / 光 / 电 =====
        Triple(
            SceneEffectKind.FIRE,
            listOf("火焰", "烈火", "燃烧", "着火", "火球", "火海", "焚烧", "熊熊", "火舌", "火苗", "篝火", "火光", "起火"), 3
        ),
        Triple(
            SceneEffectKind.LIGHTNING,
            listOf("雷电", "闪电", "惊雷", "雷鸣", "雷击", "雷声", "电闪", "落雷"), 4
        ),
        Triple(
            SceneEffectKind.FIREWORKS,
            listOf("烟花", "焰火", "礼花", "爆竹", "鞭炮", "绽放"), 3
        ),
        Triple(
            SceneEffectKind.SPARKLES,
            listOf("闪光", "闪耀", "亮晶晶", "晶莹", "闪烁", "熠熠", "寒光", "火花"), 2
        ),
        Triple(
            SceneEffectKind.RAYS,
            listOf("光束", "光柱", "晨光", "霞光", "夕照", "光线", "阳光洒"), 2
        ),
        Triple(
            SceneEffectKind.SMOKE,
            listOf("浓烟", "烟雾", "烟尘", "炊烟", "青烟", "轻烟", "硝烟"), 2
        ),
        Triple(
            SceneEffectKind.FOG,
            listOf("浓雾", "迷雾", "雾气", "白雾", "薄雾", "雾霭", "起雾"), 2
        ),
        Triple(
            SceneEffectKind.EMBERS,
            listOf("火星", "余烬", "灰烬", "飞灰", "炭火"), 2
        ),
        // ===== 天气 / 自然 =====
        Triple(
            SceneEffectKind.RAIN,
            listOf("暴雨", "倾盆", "大雨", "雨声", "雨点", "雨幕", "下雨", "雨"), 2
        ),
        Triple(
            SceneEffectKind.SNOW,
            listOf("暴雪", "大雪", "雪花", "飘雪", "下雪", "落雪", "风雪"), 2
        ),
        Triple(
            SceneEffectKind.WIND,
            listOf("狂风", "大风", "风声", "呼啸", "刮风", "起风", "风"), 2
        ),
        Triple(
            SceneEffectKind.PETALS,
            listOf("花瓣", "落花", "樱花", "花雨", "桃花落"), 2
        ),
        Triple(
            SceneEffectKind.LEAVES,
            listOf("落叶", "秋叶", "枯叶", "叶落", "树叶飘"), 2
        ),
        Triple(
            SceneEffectKind.FIREFLIES,
            listOf("萤火虫", "流萤", "萤光"), 2
        ),
        Triple(
            SceneEffectKind.STARS,
            listOf("星空", "星辰", "繁星", "星河", "银河", "满天星", "星光"), 2
        ),
        Triple(
            SceneEffectKind.METEORS,
            listOf("流星", "陨星", "流星雨", "划过天际", "星雨"), 2
        ),
        Triple(
            SceneEffectKind.DUST,
            listOf("尘土", "扬尘", "黄沙", "沙尘", "飞沙", "灰土", "尘烟"), 2
        ),
        Triple(
            SceneEffectKind.FOUNTAIN,
            listOf("喷泉", "水花", "水柱", "浪花", "溅起", "水雾", "飞溅"), 2
        ),
        Triple(
            SceneEffectKind.BUBBLES,
            listOf("气泡", "水泡", "冒泡", "泡沫", "吐泡泡"), 2
        ),
        Triple(
            SceneEffectKind.BUTTERFLIES,
            listOf("蝴蝶", "彩蝶", "粉蝶", "蝶舞", "蝶翼", "花蝶"), 2
        ),
        Triple(
            SceneEffectKind.BIRD_FLOCK,
            listOf("鸟群", "群鸟", "雁阵", "大雁", "飞鸟", "惊鸟", "雀群"), 2
        ),
        Triple(
            SceneEffectKind.BIRDS_CROW,
            listOf("乌鸦", "寒鸦", "鸦群", "老鸹", "乌鹊"), 2
        ),
        Triple(
            SceneEffectKind.BIRDS_GULL,
            listOf("海鸥", "白鸥", "鸥鸟", "海鸟", "沙鸥"), 2
        ),
        Triple(
            SceneEffectKind.FEATHERS,
            listOf("羽毛", "白羽", "羽落", "翎毛", "鹤羽"), 2
        ),
        Triple(
            SceneEffectKind.DANDELION,
            listOf("蒲公英", "柳絮", "飞絮", "杨花", "芦花", "绒球"), 2
        ),
        Triple(
            SceneEffectKind.BOKEH,
            listOf("光斑", "光点", "微光", "朦胧", "斑驳", "光影", "粼粼"), 2
        ),
        // ===== 人物表现 / 漫画符号 =====
        Triple(
            SceneEffectKind.CONFETTI,
            listOf("彩纸", "彩带", "纸屑", "庆祝", "欢呼", "喜庆", "彩旗"), 2
        ),
        Triple(
            SceneEffectKind.HEART,
            listOf("心动", "怦然", "脸红", "心跳加速", "脸颊发烫"), 2
        ),
        Triple(
            SceneEffectKind.SWEAT,
            listOf("冷汗", "冒汗", "汗珠", "汗流", "捏了把汗"), 2
        ),
        Triple(
            SceneEffectKind.ANGER,
            listOf("青筋", "怒火", "恼怒", "气得", "暴跳", "咬牙切齿"), 2
        ),
        Triple(
            SceneEffectKind.QUESTION,
            listOf("满头问号", "疑惑不解", "一头雾水", "纳闷", "不明所以"), 2
        ),
        Triple(
            SceneEffectKind.LIGHTBULB,
            listOf("灵光一闪", "恍然", "顿悟", "茅塞顿开", "灵机一动"), 2
        ),
        Triple(
            SceneEffectKind.ZZZ,
            listOf("睡意", "打盹", "呼噜", "入眠", "困倦", "打哈欠"), 2
        ),
        Triple(
            SceneEffectKind.COMIC,
            listOf("哐当", "哗啦", "咔嚓", "噼啪", "嗖", "咚", "轰", "砰"), 2
        )
    )

    /**
     * 精确绑定表：由 `tmp/生成音效清单与绑定表.py` 从两套音效配置全量生成。
     * 每个画面效果绑定多个真实音效名（EXPLOSION 31 个、SLASH 70 个、IMPACT 56 个…），
     * 任一音效名被触发就立刻画对应效果。可直接编辑 JSON 调整，无需改 Kotlin。
     */
    private val exactBindings: Map<String, SceneEffectKind> by lazy { loadBindings() }

    private fun loadBindings(): Map<String, SceneEffectKind> = try {
        val json = JSONObject(
            String(appCtx.assets.open("fx_sound_bindings.json").readBytes())
        ).getJSONObject("bindings")
        val map = HashMap<String, SceneEffectKind>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val kind = SceneEffectKind.from(key) ?: continue
            val arr = json.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val n = normalize(arr.optString(i))
                if (n.isNotEmpty()) map[n] = kind
            }
        }
        map
    } catch (e: Exception) {
        emptyMap()
    }

    /** 统一 `(爆炸音效)` / `爆炸.mp3` / `01_通用母库/拟音/爆炸.mp3` 三种写法 */
    private fun normalize(raw: String): String = raw.trim()
        .removePrefix("(").removeSuffix(")")
        .substringAfterLast('/')
        .removeSuffix(".mp3").removeSuffix(".json")
        .removeSuffix("音效")
        .trim()

    /** 音效名 → 瞬时特效；无匹配返回 null（不强行画画面，避免每个脚步声都闪一下） */
    fun map(fired: SoundEffectFired): SceneEffectCue? {
        val name = normalize(fired.name)
        val fileName = normalize(fired.fileName)
        if (name.isEmpty() && fileName.isEmpty()) return null

        // 1. 精确音效名优先：一个效果绑定多个真实音效名，任一命中即触发
        val exact = exactBindings[name] ?: exactBindings[fileName]
        if (exact != null) {
            val level = RULES.firstOrNull { it.first == exact }?.third ?: 3
            return buildCue(fired, name, exact, level)
        }

        // 2. 兜底：具体词（≥2 字，避免「倒车雷达」被单字「雷」误判成闪电）
        //    匹配音效名与 AI 音效库的相对路径类目
        val haystack = "$name ${fired.fileName}"
        val hit = RULES.firstOrNull { (_, words, _) ->
            words.any { it.length >= 2 && haystack.contains(it) }
        } ?: return null
        return buildCue(fired, name, hit.first, hit.third)
    }

    private fun buildCue(
        fired: SoundEffectFired, name: String, kind: SceneEffectKind, level: Int
    ): SceneEffectCue {
        val ambient = kind == SceneEffectKind.FOG || kind == SceneEffectKind.SMOKE
        val duration = when (kind) {
            SceneEffectKind.EXPLOSION -> 1100L
            SceneEffectKind.SLASH -> 800L
            SceneEffectKind.IMPACT -> 750L
            SceneEffectKind.SHOCKWAVE -> 1200L
            SceneEffectKind.FIRE, SceneEffectKind.FIREWORKS -> 1800L
            SceneEffectKind.FOG, SceneEffectKind.SMOKE -> 8000L
            else -> 1600L
        }
        return SceneEffectCue(
            para = fired.para,
            kind = kind,
            level = level,
            durationMs = duration,
            source = if (fired.isAiEffect) "ai-effect" else "rule-effect",
            burst = !ambient,
            ratio = fired.ratio,
            sourceEffectName = name.ifEmpty { fired.fileName },
            // 与 assets/fx/<id>.png 约定：放入开源导出的序列帧即自动替换算法绘制
            fxId = kind.name.lowercase()
        )
    }
}
