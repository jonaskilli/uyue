package io.legado.app.service

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.model.SceneEffectCue
import io.legado.app.model.SceneEffectKind
import io.legado.app.model.SceneEffectSettings
import io.legado.app.ui.book.toc.AiCacheFileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 场景特效生成器：整章按字数区间提前分析，每条绑定触发段落。
 *
 * 完全复用 [DanmakuGenerator] 的调度与缓存骨架，但内容不同：
 * - **本地关键词规则是主路径**：不配任何 AI 也能「读到下雪就飘雪」；
 * - **AI 增强可选**：复用 BGM 的 AI Provider（与弹幕同通道），只用于更准地识别
 *   室内外、强度、以及「是不是比喻」；
 * - 段落级缓存落盘，contentHash 不一致时整体失效，重读不重复分析。
 *
 * 参考 /workspace/scene-effects/mapping/weather.json 的 lv 1..4 数值。
 */
object SceneEffectGenerator {

    /** 默认播放时长；闪电是短促叠加，单独给短时长 */
    private const val DEFAULT_DURATION_MS = 8000L
    /** 站点 lightning 预设为 8 秒：场景渐暗 → 四道分叉闪电依次照亮 → 渐亮 */
    private const val LIGHTNING_DURATION_MS = 8000L
    private const val WIND_DURATION_MS = 6000L
    /** 雨/雪是持续氛围，原来 8 秒偏短，拉长到 16 秒 */
    private const val WEATHER_DURATION_MS = 16000L
    /** 规则表版本：新增特效后旧缓存里「已检查无特效」的段落要重新分析 */
    // 规则表改动（新增动作正则）后，旧缓存里「已检查但无特效」的段落必须重新分析
    private const val CACHE_VERSION = 7

    private val INDOOR_WORDS = listOf(
        "屋内", "室内", "房里", "房内", "屋里", "殿内", "车厢", "舱内",
        "洞内", "帐内", "宫里", "府里", "屋子里", "室内"
    )
    private val WIND_WORDS = listOf("狂风", "大风", "风声", "风呼啸", "刮风", "起风", "风骤", "冷风", "夜风")
    private val LIGHTNING_WORDS = listOf("闪电", "电闪", "惊雷", "雷鸣", "雷声", "雷电")

    private val SNOW_WORDS = listOf(
        "飘雪", "雪花", "下雪", "大雪", "暴雪", "小雪", "细雪", "积雪",
        "雪片", "风雪", "雪地", "雪夜", "落雪", "雪中", "白雪", "雪峰",
        "下起了雪", "雪落", "雪纷飞", "飘起雪", "下大的", "雪下大了"
    )
    private val RAIN_WORDS = listOf(
        "下雨", "雨幕", "雨点", "暴雨", "细雨", "大雨", "小雨", "雨丝",
        "雨声", "风雨", "雨中", "雨水", "倾盆", "骤雨", "阴雨",
        "下起了雨", "落雨", "雨落", "雨下", "雨滴", "淋雨", "雨势"
    )
    private val LEAVES_WORDS = listOf("落叶", "秋叶", "树叶飘", "枯叶", "叶子落", "叶落",
        "叶子飘", "树叶落", "黄叶飘", "叶纷飞")
    private val PETALS_WORDS = listOf("花瓣", "落花", "樱花", "花瓣雨", "花雨", "桃花落",
        "花落", "花瓣落", "落英", "飞花")
    private val FIREFLIES_WORDS = listOf("萤火虫", "流萤", "萤光")
    private val EMBERS_WORDS = listOf("火星", "余烬", "篝火", "火星四溅", "灰烬")
    private val COMIC_WORDS = listOf(
        "砰", "轰隆", "哐当", "哗啦", "咔嚓", "嘭", "咚", "噼啪", "嗖", "唰"
    )

    /**
     * 新增实体类特效的关键词规则，按「具体 → 泛化」排序，第一个命中生效。
     * 站点环境/人物表现中的屏幕实体（fog/smoke/bubbles/rays/dust/butterflies/
     * birds/漫画符号）与 tsParticles(MIT) 预设里适合小说的效果类型。
     */
    private val EXTRA_RULES: List<Triple<SceneEffectKind, List<String>, Int>> = listOf(
        // 瞬时爆发类：音效关闭时也能由正文兜底触发，画 1.2 秒就收，不拖到整段结束
        Triple(SceneEffectKind.EXPLOSION, listOf("爆炸", "爆裂", "炸开", "炸毁", "爆破", "轰隆", "轰然", "轰炸", "炸", "砰", "嘭"), 4),
        Triple(SceneEffectKind.SLASH, listOf("拔刀", "出鞘", "剑气", "刀光", "剑鸣", "挥刀", "挥剑", "一刀斩", "斩下", "斩击", "劈下", "劈开", "砍下", "刀锋", "剑锋", "比武", "过招", "唰"), 3),
        Triple(SceneEffectKind.IMPACT, listOf("暴击", "重击", "重创", "击中", "命中", "撞击", "打斗", "格挡", "拳脚", "一拳", "一掌", "咚", "哐"), 3),
        Triple(SceneEffectKind.SHOCKWAVE, listOf("冲击波", "气浪", "震波", "震荡", "余波", "威压", "地动山摇"), 3),
        Triple(SceneEffectKind.FIREWORKS, listOf("烟花", "焰火", "礼花", "爆竹", "烟花绽放"), 3),
        Triple(SceneEffectKind.METEORS, listOf("流星", "陨星", "划过天际", "星雨", "流星雨"), 2),
        Triple(SceneEffectKind.BIRDS_CROW, listOf("乌鸦", "寒鸦", "鸦群", "老鸹", "乌鹊"), 2),
        Triple(SceneEffectKind.BIRDS_GULL, listOf("海鸥", "白鸥", "鸥鸟", "海鸟", "沙鸥"), 2),
        Triple(SceneEffectKind.BIRD_FLOCK, listOf("鸟群", "群鸟", "雁阵", "大雁", "飞鸟", "惊鸟", "雀群", "归鸟"), 2),
        Triple(SceneEffectKind.BUTTERFLIES, listOf("蝴蝶", "彩蝶", "粉蝶", "蝶舞", "蝶翼", "花蝶"), 2),
        Triple(SceneEffectKind.BUBBLES, listOf("气泡", "水泡", "冒泡", "泡沫", "气泡上浮", "吐泡泡"), 2),
        Triple(SceneEffectKind.FOUNTAIN, listOf("喷泉", "水花", "水柱", "浪花", "溅起", "水雾", "飞溅"), 2),
        Triple(SceneEffectKind.FIRE, listOf("火焰", "烈火", "火光", "燃烧", "火苗", "火舌", "着火", "熊熊"), 3),
        Triple(SceneEffectKind.FOG, listOf("薄雾", "雾气", "白雾", "迷雾", "雾霭", "晨雾", "浓雾", "云雾", "起雾"), 2),
        Triple(SceneEffectKind.SMOKE, listOf("炊烟", "青烟", "烟雾", "烟气", "烟尘", "轻烟", "浓烟", "香烟"), 2),
        Triple(SceneEffectKind.RAYS, listOf("光束", "晨光", "斜射", "透过窗", "光线", "霞光", "夕照", "光柱", "阳光洒"), 2),
        Triple(SceneEffectKind.DUST, listOf("尘土", "扬尘", "黄沙", "沙尘", "飞沙", "灰土", "尘烟", "浮尘"), 2),
        Triple(SceneEffectKind.STARS, listOf("星空", "星辰", "繁星", "星河", "银河", "夜空", "星子", "满天星", "星光"), 2),
        Triple(SceneEffectKind.CONFETTI, listOf("彩纸", "彩带", "纸屑", "庆祝", "欢呼", "喜庆", "彩旗"), 2),
        Triple(SceneEffectKind.DANDELION, listOf("蒲公英", "柳絮", "飞絮", "杨花", "芦花", "绒球"), 2),
        Triple(SceneEffectKind.FEATHERS, listOf("羽毛", "白羽", "羽落", "翎毛", "鹤羽", "羽绒"), 2),
        Triple(SceneEffectKind.BOKEH, listOf("光斑", "光点", "微光", "朦胧", "斑驳", "光影", "粼粼"), 2),
        Triple(SceneEffectKind.SPARKLES, listOf("闪光", "闪耀", "亮晶晶", "晶莹", "闪烁", "熠熠", "亮光"), 2),
        Triple(SceneEffectKind.LIGHTBULB, listOf("灵光一闪", "恍然", "顿悟", "茅塞顿开", "灵机一动", "忽然想到"), 2),
        Triple(SceneEffectKind.HEART, listOf("心动", "怦然", "脸红", "心跳加速", "脸颊发烫", "心头一跳"), 2),
        Triple(SceneEffectKind.SWEAT, listOf("冷汗", "冒汗", "汗珠", "汗流", "额角汗", "捏了把汗", "冷汗直流"), 2),
        Triple(SceneEffectKind.ANGER, listOf("青筋", "怒火", "恼怒", "气得", "暴跳", "咬牙切齿", "怒气"), 2),
        Triple(SceneEffectKind.ZZZ, listOf("睡意", "打盹", "呼噜", "入眠", "困倦", "打哈欠", "昏昏欲睡"), 2),
        Triple(SceneEffectKind.QUESTION, listOf("满头问号", "疑惑不解", "一头雾水", "纳闷", "不明所以", "百思不得"), 2),
        // 本轮从新收集的开源特效中新增的 14 类（全部有对应序列帧素材）
        Triple(SceneEffectKind.MAGIC_CIRCLE, listOf("魔法阵", "法阵亮起", "符文亮", "阵法", "召唤阵", "法阵"), 3),
        Triple(SceneEffectKind.ARCANE, listOf("咒语", "符文", "灵力爆发", "奥术", "结界"), 3),
        Triple(SceneEffectKind.HOLY, listOf("圣光", "神光", "净化", "祝福", "神圣", "佛光"), 2),
        Triple(SceneEffectKind.SHADOW, listOf("黑雾", "暗影", "阴影", "魔气", "黑气", "幽影"), 2),
        Triple(SceneEffectKind.FROST, listOf("冰霜", "结冰", "冻结", "寒冰", "霜花", "冰封"), 3),
        Triple(SceneEffectKind.PORTAL, listOf("传送门", "空间裂缝", "裂隙", "门户洞开"), 3),
        Triple(SceneEffectKind.PROJECTILE, listOf("箭矢", "万箭", "飞箭", "弩箭", "飞镖", "弹丸"), 3),
        Triple(SceneEffectKind.SPARK_JET, listOf("火花四溅", "火星喷射", "迸出火花", "火花飞溅"), 3),
        Triple(SceneEffectKind.RIPPLE, listOf("涟漪", "水波荡漾", "波纹扩散", "水面泛起"), 2),
        Triple(SceneEffectKind.WAVE, listOf("波浪", "浪潮", "波涛", "水波起伏"), 2),
        Triple(SceneEffectKind.SHIMMER, listOf("流光", "寒光一闪", "光华流转", "闪过一道光"), 2),
        Triple(SceneEffectKind.RED_PACKET, listOf("红包雨", "撒钱", "金币雨", "漫天金币", "天降宝物"), 2),
        Triple(SceneEffectKind.KOI, listOf("锦鲤", "游鱼", "红鲤", "鱼群游过"), 2),
        Triple(SceneEffectKind.DOOM_FIRE, listOf("烈焰滔天", "火海", "焚天", "岩浆", "熊熊烈焰"), 3),
        Triple(SceneEffectKind.COMIC, COMIC_WORDS, 2)
    )

    /**
     * 动作句式正则：小说里动词和宾语常被拆开
     * （「斩出一道剑芒」「挥手向前方斩去」「搭箭、拉弓、离弦」），
     * 整词 contains 一个都碰不到。这里允许同一句内插入 0~8 个非标点字，
     * 用 <g> 占位；不跨逗号/句号，避免把前后两句硬拼成一次命中。
     *
     * 名词物象类（烟花/落叶/花瓣/雾/雪/蝴蝶…）由词表负责，这里只补动作。
     * 规则按「具体 → 泛化」排列，第一个命中生效。
     */
    private val GAP = "[^，。！？；：、,.!?;:\\n]{0,8}"
    /** 动词与单字结果词之间只允许 0~3 字，避免「劈头盖脸…落」这类跨字误判 */
    private val SHORT_GAP = "[^，。！？；：、,.!?;:\\n]{0,3}"
    private fun rx(pattern: String): Regex =
        Regex(pattern.replace("<g>", GAP).replace("<s>", SHORT_GAP))

    private val ACTION_RULES: List<Triple<SceneEffectKind, List<Regex>, Int>> = listOf(
        Triple(SceneEffectKind.SPARK_JET, listOf(
            rx("(迸|溅|喷|射|擦|炸|爆)<g>(出|起|开|落)<g>(火花|火星|星火|光点)"),
            rx("(火花|火星|星火)<g>(四溅|飞溅|迸射|四射|喷涌|飞散|乱迸|四散)"),
            rx("(刀|剑|铁|石|金属|兵刃)<g>(相交|相击|碰撞|摩擦)<g>(火花|火星|迸|溅)")
        ), 3),
        Triple(SceneEffectKind.PORTAL, listOf(
            rx("(撕|裂|划|打|开|洞|破)<g>(空间|虚空|裂缝|门户|通道|光门)"),
            rx("(空间|虚空|空气|夜色)<g>(裂|开|撕|扭曲|塌陷|荡漾|洞开)"),
            rx("(一道|一扇|一处)<g>(裂缝|裂隙|门户|光门|漩涡)")
        ), 3),
        Triple(SceneEffectKind.WAVE, listOf(
            rx("(浪|潮|波|涛)<g>(涌|拍|卷|翻|起|推|扑|打)"),
            rx("(一阵|层层|一排)<g>(浪|潮|波)<g>(接|连|涌|拍)")
        ), 2),
        // ===== 斩击：重点补「动词—兵器」被拆开的写法 =====
        Triple(SceneEffectKind.SLASH, listOf(
            rx("(斩|劈|砍|挥|削|撩|扫|刺|戳)<g>(刀|剑|刃|锋|芒|气|光|招|势|影)"),
            rx("(刀|剑|刃|锋|芒|气)<g>(斩|劈|砍|挥|削|扫|掠|划|破空|离鞘)"),
            rx("(挥|扬|抬|举|抡|甩)<g>(手|臂|腕|刀|剑|刃)<g>(斩|劈|砍|削|扫|刺|挥)"),
            rx("(斩|劈|砍|削)<s>(出|下|去|开|断|落|向|向前|过来|过去|为两半|成两截)"),
            rx("(横|斜|竖|反手|回身|转身|旋身|凌空|顺势|探身)<g>(斩|劈|挥|削|扫|刺)"),
            rx("(一道|一记|一缕|一片|数道|几道|一抹|一线)<g>(刀|剑|刃|芒|气|光|寒|弧|白练)"),
            rx("(剑|刀|刃|兵)<g>(芒|气|光|锋|影|风|意)<g>(闪|掠|划|劈|过|起|爆|涨|大盛)"),
            rx("(拔|抽|擎|掣|拽|提|握|持|仗)<g>(刀|剑|刃|兵刃|兵器)"),
            rx("(出鞘|离鞘|拔剑|拔刀|亮剑|亮刀|横刀|握刀|持剑|仗剑|刀剑相|兵刃相接)"),
            rx("(交手|过招|拆招|交锋|搏杀|厮杀|白刃|近身|缠斗|对招)"),
            rx("(刺|戳|捅|突刺|直刺|斜刺)<g>(出|向|进|去|穿|破|入)"),
            rx("(刀光|剑光|刀芒|剑芒|剑气|刀气|刃光|寒芒|弧光|匹练|白练|剑影|刀影)"),
            rx("(一刀|一剑|一斧|一记)<g>(劈|斩|砍|挥|落下|递出)"),
            rx("(斩|劈)<g>(了个空|落空|为两|成两|到底)"),
            rx("(刀锋|剑锋|锋刃|刃口)<g>(闪|掠|划|过|寒|逼|抵)"),
            rx("(七点|数点|点点|森寒|凛冽|璀璨)<g>(星芒|寒芒|剑芒|光点|冷芒)"),
            rx("(星芒|寒芒|剑芒|刀芒|冷芒)<g>(笼罩|直取|射向|钉向|锁定|逼向)"),
            rx("(剑|刀)<g>(势|招|法)<g>(展开|施出|递出|连绵|如虹)"),
            rx("(剑尖|刀尖|刃尖|枪尖)<g>(震|颤|抖|挑|点|刺|迸|吐|划)<g>(星|芒|光|寒|点|影)"),
            rx("(剑尖|刀尖|刃尖)<g>(森寒|寒星|星芒|寒芒|光点|冷芒)"),
            rx("(化|化做|化作|凝成|迸成|聚成)<g>(一道|一缕|数道|漫天)<g>(飞虹|长虹|银芒|青芒|剑光|刀光|白练|匹练|光影)"),
            rx("(刀剑|兵刃|金铁|剑刃|刀刃|枪剑)<g>(交击|交锋|相击|相撞|碰撞|交接|交加|交鸣|齐鸣)"),
            rx("(长剑|长刀|剑身|刀身|铁剑|锈剑|宝剑)<g>(一颤|一振|一抖|嗡鸣|震颤|长鸣|龙吟|清鸣|铮然|平展|斜斜|高举|挥舞|舞动|挥转)"),
            rx("(剑鸣|刀鸣|龙吟|金铁交鸣|铮然|铿然|破空声)"),
            rx("(剑芒|刀芒|剑气|刀气|青芒|紫芒|血芒|寒芒|剑光|刀光)<g>(劈|斩|落|扫|卷|撞|绞|暴涨|放大|绽放|爆发|凝聚|划|刺|直取)"),
            rx("(剑光|剑气|刀光|刀芒|剑影)<g>(汇聚|凝聚|合而为一|重叠|融合|暴涨|大盛|纵横|弥漫|笼罩|搅碎|绞碎)"),
            rx("(挥出|斩出|刺出|连出|劈出|扫出|递出|抖出|挽出)<g>(数|十|百|几|一|连|漫天)<g>(剑|刀|招|剑花|刀花)"),
            rx("(剑花|刀花|剑势|刀势|剑意|刀意)<g>(凌厉|如虹|暴涨|大盛|席卷|逼人|凛冽|纵横|乍现|喷薄)"),
            rx("(直取|直刺|直扫|疾刺|斜刺|横扫|平削|反撩|当头|迎面|拦腰)<g>(咽喉|心口|眉心|后胸|要害|面门|前胸|头颅|脖颈|斩|劈|扫)"),
            rx("(刀|剑|刃|枪|箭|兵刃)<g>(破空|呼啸|尖啸|锐响|铮然|铿然)<g>(声|之声|而起|而来|响起)"),
            rx("(剑光|刀光|流光|寒光|白光|青光|银光|血光)<g>(破空|划过|掠过|闪过|照亮|一闪|暴涨|凝聚|如电|如雪|如虹|如银河|陡然绽放)"),
            rx("(拦腰|齐根|一剑|一刀|一记|奋力|顺势|反手)<g>(斩断|劈断|削断|斩落|劈落|斩为|斩成|扫断|绞碎)"),
            rx("(斩|劈|削|绞|砍)<s>(为两段|成两截|为两半|成两半|断|碎|裂|落)"),
            rx("(兵刃|长短兵刃|刀剑|枪戟|神兵)<g>(飞舞|齐出|迎上|相交|齐飞|交织|成网)"),
            rx("(战作一团|杀在一处|你来我往|招式往来|交手数十|交手十余|十余招|数十招|连过数招|缠斗不休|拆了数招)"),
            rx("(刀光|剑光|剑气|刀芒|剑芒)<g>(如|似|若|化作)<g>(雪|电|虹|银河|匹练|瀑布|霜|练|龙)"),
            rx("(剑尖|刀尖|刀背|剑脊|剑锋|刀锋)<g>(挑|磕|架|格|拨|点|刺|抵|逼近|划开|撕裂)"),
            rx("(身形|身法|手腕|臂膀|掌中|袖中)<g>(一转|一抖|一振|一沉|一闪|急拔|斜飞|猛地|陡然)<g>(剑|刀|挥|斩|刺|劈|出)"),
            rx("(出鞘|离鞘|拔刀|抽刀|横刀|提刀|擎剑|仗剑)<g>(脆响|清响|寒光|一闪|同时|几乎)"),
            rx("(劲气|剑气|刀气|杀气|罡气)<g>(横飞|纵横|四溢|弥漫|笼罩|扫过|所过|裂空)"),
            rx("(一剑|一刀|一枪|一戟|一斧)<g>(刺出|递出|挥出|劈出|斩出|扫出|点出|挑出|甩出)"),
            rx("(剑|刀|刃)<g>(随身|划出|挽出|甩出|抖出)<g>(弧|光|花|芒|影|风)"),
            rx("(森寒|凛冽|刺骨|逼人|凌厉)<g>(剑气|刀气|剑光|刀光|寒芒|杀意)"),
            rx("(空气|夜色|长空|虚空)<g>(被|让|叫)<g>(剑光|刀芒|剑气|匹练)<g>(划|撕|劈|照亮)"),
            rx("(剑|刀|兵刃)<g>(撞|碰|格|架|磕)<g>(在一起|一记|一声|出火星|迸出)"),
        ), 3),

        // ===== 命中 / 打击 =====
        Triple(SceneEffectKind.IMPACT, listOf(
            rx("(击|打|砸|撞|踢|踹|拍|劈|轰)<s>(中|在|上|到|得|了个正着)"),
            rx("(拳|掌|肘|膝|脚|腿|鞭|棍|棒)<g>(落|砸|轰|击|到|扫|砸下|挥出)"),
            rx("(狠狠|重重|稳稳|正正|霎时|猛地|骤然)<g>(击|打|砸|撞|踹|拍)"),
            rx("(正|恰|刚好|恰好|堪堪)<g>(击|打|砸|撞|挡|架)"),
            rx("(撞|砸|摔|跌|拍|磕)<g>(上|在|向|到|进|落)"),
            rx("(打|砸|撞|踢)<g>(得|了|出)<g>(一声|一个|一口|踉跄|倒退|闷响)"),
            rx("(拳脚|拳掌|近身|贴身|拳风|掌风)<g>(相交|相加|往来|相撞|带起)"),
            rx("(一拳|一掌|一脚|一肘|一记)<g>(砸|轰|打|捣|挥|落在)"),
            rx("(巨锤|铁拳|拳头|掌|长枪|棍|鞭|拐杖|枪尖)<g>(砸在|砸向|轰在|落在|击打|撞上|硬撼|直扣)"),
            rx("(拳头|拳|掌|肘|膝)<g>(击打在|砸在|轰在|与|相交|交错)<g>(盾|甲|面门|胸|墙|水盾)"),
            rx("(硬拼|硬撼|相撞|对撞|碰撞|交击|擦肩|硬碰硬)<g>(一记|一声|在一起|刹那|瞬间|数万次)"),
            rx("(两股力|两道|双方|枪与剑|刀与剑)<g>(在|于|正中)<g>(相撞|碰撞|对撞|轰炸)"),
            rx("(金铁|金属|兵器|枪剑|刀剑)<g>(交鸣|相击|碰撞|密集|之声)<g>(声|响|不绝)"),
            rx("(撞翻|掀翻|掼倒|砸落|倒飞|摔|踉跄)<g>(在地|出去|翻滚|三步|几步|落地)"),
            rx("(拳风|掌风|巨力|反震之力|一股)<g>(袭来|传来|倒卷|震得|逼得|让人胆寒)"),
            rx("(闷响|脆响|当的一声|叮的一声|咚)<g>(响彻|传来|落在|砸在|撞)"),
        ), 3),

        // ===== 爆炸 =====
        Triple(SceneEffectKind.EXPLOSION, listOf(
            rx("(炸|爆|轰|崩)<s>(开|裂|响|起|飞|散|出|腾|了|一声)"),
            rx("(轰|砰|嘭|啪)<g>(然|隆|地|的一声|的一下|然一声)"),
            rx("(突然|猛地|骤然|瞬间|霎时|倏然)<g>(炸|爆|轰|崩|裂)"),
            rx("(四分五裂|炸成|炸飞|崩碎|碎裂|爆散|粉身碎骨)"),
            rx("(爆|炸)<g>(出|开|起)<g>(一团|一朵|一片|火光|气浪|烟尘)"),
            rx("(一声|只听|忽听|陡然)<g>(巨响|轰鸣|爆响|闷响|炸响)"),
            rx("(火球|雷球|能量团|光球|炮弹)<g>(砸撞|相撞|碰撞|对撞|全盘炸裂|炸裂|爆发|宣泄|引爆|轰落)"),
            rx("(火球|雷球|光球)<g>(与|和|跟|同)<g>(火球|雷球|光球|铠甲|护罩)<g>(相撞|碰撞|对撞)"),
            rx("(轰鸣|巨响|爆响|炸响|闷响|轰隆|连环巨响)<g>(骤然|炸开|响起|落地|传遍|不绝|震彻|一声)"),
            rx("(一声|只听|毫无征兆|骤然|陡然|忽地)<g>(巨响|轰鸣|沉闷|震彻|极致|连环)<g>(炸开|响|落地|翻上来)"),
            rx("(炸得|轰得|震得)<g>(碎石|砖石|山岩|钢铁|残骸|城门|闸门)<g>(飞溅|崩碎|四射|腾空|变形)"),
            rx("(蘑菇云|火光|火柱|烈焰|火海|黑烟|烟尘|硝烟)<g>(冲天|升起|吞没|照亮|席卷|翻滚|弥漫|遮蔽|浸染|封锁)"),
            rx("(引爆|点燃|引燃|炸开|爆破|炸毁)<g>(自身|修为|火药|地雷|手雷|城门|闸门|引线|城池)"),
            rx("(碎石|碎片|木屑|残骸|砖石|残肢|弹片)<g>(飞溅|四射|腾空|飞起|砸落|席卷|散落)"),
            rx("(爆燃|气体膨胀|连环爆炸|大爆炸|毁灭性|蘑菇云|爆炸中心)<g>(冲天|席卷|炸开|骤然|横扫|吞没)"),
            rx("(地雷|手雷|炮弹|火药|炸弹|雷符)<g>(爆炸|炸开|炸响|轰|引爆|开了花)"),
            rx("(火焰能量|狂暴能量|能量洪流|灵力)<g>(全盘炸裂|彻底宣泄|肆意爆发|层层碾压|撕裂)"),
        ), 4),

        // ===== 冲击波 =====
        Triple(SceneEffectKind.SHOCKWAVE, listOf(
            rx("(气浪|劲风|罡风|余波|震波|冲击|气劲|劲气)<g>(扩散|荡开|推开|掀|卷|扫|四散|炸开|撞)"),
            rx("(震|荡|掀|轰|冲)<s>(得|飞|退|倒|散|出去)"),
            rx("(一股|一阵|一圈|层层)<g>(气浪|劲风|波纹|震荡|冲击|涟漪)"),
            rx("(地动山摇|天摇地动|山摇地动|四下震荡|空气震颤)"),
            rx("(劲风|掌风|拳风|罡风|气劲|劲气|威压|剑风)<g>(所过|未至|到处|过处|席卷|扫过|掀起|压得|逼得)"),
            rx("(地面|石板|大地|崖石|山岩|城墙|桥面)<g>(裂开|崩裂|震颤|塌陷|犁出|被掀|寸寸|摇摇|寸裂|炸开)"),
            rx("(掀飞|震飞|震得|轰得|冲得|推得|压得)<g>(群山|大地|大桥|众人|倒退|翻滚|摇晃|弯下|跪地|飞出去)"),
            rx("(被|给|让)<g>(气浪|冲击波|巨力|劲风|能量洪流)<g>(掀|掀翻|推|卷|震|砸|冲|吞)"),
            rx("(冲击波|气浪|能量洪流|火焰风暴|黑尘|余波)<g>(横扫|碾压|席卷|层层|向四周|疯狂|冲天|坠落|环形|扩散)"),
            rx("(空气|天地|四下|周遭|虚空)<g>(震荡|震颤|扭曲|凝固|寂静|轰鸣|大盛|翻涌)"),
            rx("(裂痕|沟壑|纹路|裂缝|深坑)<g>(蔓延|扩散|向四面八方|犁出|蛛网|寸寸)"),
            rx("(气浪|热浪|飓风|狂潮)<g>(裹挟|碾压|扑面|席卷四方|冲天而起|呈环形)"),
            rx("(地面已裂|地面被犁|威压如山|无形巨锤|沛然莫御|激得)"),
        ), 3),

        // ===== 火花喷射 =====

        // ===== 飞矢 =====
        Triple(SceneEffectKind.PROJECTILE, listOf(
            rx("(搭|拉|弯|张|引|扣|满)<g>(箭|弓|弦|弩)"),
            rx("(射|放|发|飙|疾|飞|激)<g>(出|去|向|来)<g>(箭|矢|镖|弹|弩|羽)"),
            rx("(箭|矢|镖|弹|弩箭|羽箭)<g>(破空|离弦|呼啸|疾射|飞掠|如雨|齐发|穿空)"),
            rx("(万箭|箭雨|乱箭|飞矢|连弩|弓弦)<g>(齐发|齐射|如雨|破空|响|震动)"),
            rx("(法剑|飞剑|暗器|飞刀|毒针|冷芒|流光|银芒)<g>(飞出|激射|暴射|旋出|齐出|钉进|划破|飞旋)"),
            rx("(袖中|衣袖|腰带|掌心|指尖|袖底)<g>(暴射|激射|飞出|甩出|喷出|划出)<g>(冷芒|暗器|飞刀|流光|法剑)"),
            rx("(枪尖|枪影|箭尖|镖|弹|飞刀)<g>(破空|疾刺|呼啸|飞旋|首刺|直取|割向)"),
            rx("(银芒|冷芒|青芒|紫芒|流光|匹练)<g>(飞旋|激射|暴射|划破|穿梭|如雨)"),
            rx("(数道|七道|几道|漫天|暴雨般)<g>(冷芒|剑气|枪影|飞刀|暗器|流光)<g>(齐出|飞射|笼罩|落下)"),
        ), 3),

        // ===== 法阵 / 法术 =====
        Triple(SceneEffectKind.MAGIC_CIRCLE, listOf(
            rx("(画|刻|绘|布|摆|踏|立|结|起)<g>(阵|法阵|阵法|符阵|图纹|符箓)"),
            rx("(法阵|阵法|符阵|符文|阵纹|结界)<g>(亮|起|转|浮现|展开|流转|旋转|亮起|张开)"),
            rx("(脚下|地面|身周|半空)<g>(浮现|亮起|展开|升起)<g>(阵|环|纹|光轮)")
        ), 3),
        Triple(SceneEffectKind.ARCANE, listOf(
            rx("(念|诵|吟|喝|掐|结|捏|催|运)<g>(咒|诀|印|符|法|术|灵力|法力)"),
            rx("(灵力|法力|魔力|元气|真气|内力|咒力)<g>(涌|暴涨|爆发|流转|灌注|凝聚|催动)"),
            rx("(咒语|法诀|口诀|符箓|法印)<g>(念|起|响|落|成|亮)"),
            rx("(施|展|催|运|使)<g>(法|术|咒|诀|招)<g>(起|出|开|落)"),
            rx("(灵力|法力|真气|内力|仙元|灵气|魔气|元气|灵压)<g>(沸腾|翻腾|狂涌|涌动|流转|暴涨|凝聚|灌注|运转|澎湃|席卷|压成|升起|骤然)"),
            rx("(剑诀|法诀|口诀|咒诀|雷诀|指诀|法印|手印|真言|咒语)<g>(运转|催动|掐|捏|念|施展|一指|朝|疾变|念罢|落下|变化)"),
            rx("(按照|依着|循着|默念|念动)<g>(剑诀|口诀|法诀|心法|咒语)<g>(运转|催动|运行|施展)"),
            rx("(法剑|飞剑|仙剑|灵剑|本命剑|飞刃)<g>(飞出|激射|旋出|齐出|合为一体|结成|化作|落下|斩落|飞旋|归位)"),
            rx("(袖袍|袖中|掌心|指尖|袖底|掌中)<g>(一抖|一点|一指|飞出|喷出|凝出|推出|划出|掐)<g>(法剑|飞剑|剑光|紫光|雷弧|流光|灵力)"),
            rx("(掐|捏|结|扣|变|换|转)<g>(剑诀|法诀|雷诀|指诀|印诀|手印|法印|雷印)"),
            rx("(掐诀|捏诀|结印|指诀|法诀)<g>(施法|念咒|一点|一指|疾变|朝下|朝前|催动)"),
            rx("(口中|嘴里|低声|默|高声)<g>(念念有词|念动|念罢|吟唱|喝道|默念|轻喝)<g>(咒|诀|法|真言|一声)"),
            rx("(念念有词|咒语念罢|口诀念罢|真言落下|一声轻叱)"),
            rx("(灵压|威压|气势|杀气|威势|法力波动)<g>(升起|暴涨|笼罩|压下|席卷|骤然|倏然|弥漫|如山)"),
            rx("(雷诀|雷弧|雷印|雷霆|天雷|紫光|雷球|电芒)<g>(落下|散开|化作|凝聚|轰|劈|点|砸|撕裂)"),
            rx("(剑阵|法阵|阵法|光幕|结界|护罩|剑匣)<g>(结成|展开|亮起|凝聚|流转|笼罩|破碎|开启|挡下|应声)"),
            rx("(灵纹|符文|剑纹|道纹|法纹|金色纹路|玄奥)<g>(遍布|浮现|亮起|流转|闪烁|铭刻)"),
            rx("(体内|丹田|经脉|周身|四肢百骸)<g>(灵力|真气|法力|内力|仙元)<g>(运转|涌动|狂涌|翻腾|沸腾)"),
            rx("(掌心|指尖|袖底|掌中)<g>(浮起|凝出|炸开|凝聚|迸出|托出)<g>(光|球|芒|印|符|剑|雷)"),
            rx("(化作|凝成|聚成|汇成)<g>(一道|一柄|一团|数道)<g>(灵力|剑光|法剑|光幕|雷弧|火球)"),
            rx("(术成|法成|阵成|诀成|印成|水刃术成|道法激发|功法运转)"),
        ), 3),

        // ===== 传送门 =====

        // ===== 圣光 / 暗影 =====
        Triple(SceneEffectKind.HOLY, listOf(
            rx("(光柱|圣光|神光|金光|佛光|白光)<g>(降|洒|落|照|倾泻|笼罩|贯)"),
            rx("(天|云|穹|半空)<g>(降下|洒下|投下|垂下)<g>(光|辉|芒|柱)"),
            rx("(净化|祝福|加持|圣洁|神圣)<g>(之光|之力|降临|笼罩|泛起)")
        ), 2),
        Triple(SceneEffectKind.SHADOW, listOf(
            rx("(黑|暗|阴|幽|魔|影)<g>(雾|气|影|霾|潮)<g>(涌|漫|笼|罩|吞|散|起|聚)"),
            rx("(阴影|黑影|暗影|黑雾|魔气)<g>(笼罩|漫过|涌出|吞没|蔓延|聚拢|散开)")
        ), 2),

        // ===== 火焰（先烈的再普通的，避免抢走火海） =====
        Triple(SceneEffectKind.DOOM_FIRE, listOf(
            rx("(火|焰|炎|燃|烧)<g>(海|滔天|冲天|吞|卷|蔓延|肆虐|燎原)"),
            rx("(烈焰|火海|岩浆|赤炎)<g>(吞|卷|燃|烧|冲|起|爆)"),
            rx("(赤红|滔天|翻滚|无边|沸腾|赤色)<g>(火海|烈焰|火焰风暴|热浪|火光)"),
            rx("(火海|烈焰|火光|火焰|热浪|火焰风暴)<g>(滔滔|滔天|席卷|封锁|浸染|吞没|蒸煮|翻滚|冲天)"),
        ), 3),
        Triple(SceneEffectKind.FIRE, listOf(
            rx("(燃|烧|点|升|窜|腾|起)<g>(起|着|上|开)<g>(火|焰|火苗|火光|火舌)"),
            rx("(火苗|火舌|火光|火焰|火堆|篝火)<g>(跳|窜|摇|舔|升|起|亮|旺)"),
            rx("(一簇|一团|一点|一片)<g>(火|焰|火光|火苗)"),
            rx("(火苗|火舌|火光|火焰|火堆|篝火|火球)<g>(舔|窜|爬|卷|扑|腾|燃|照亮|映出|落下|腾起)"),
            rx("(划|点|引|燃)<g>(火柴|火折|引线|火种|柴火|木柴)<g>(亮|起|着|燃)"),
            rx("(柴房|柴草|木柴|稻草|屋檐|门框)<g>(着|燃|烧|先着|起火)"),
            rx("(火光|烈火|火焰)<g>(把|将|映|照)<g>(半张脸|四周|夜空|天地)"),
        ), 3),

        // ===== 雾 / 烟 / 光束 =====
        Triple(SceneEffectKind.FOG, listOf(
            rx("(起|升|涌|漫|笼|罩|浮)<g>(雾|白雾|雾气|薄雾)"),
            rx("(雾|雾气|白雾)<g>(漫|笼|罩|散|浓|起|涌|遮)"),
            rx("(薄雾|云雾|雾气|白雾)<g>(压在|贴着|缓缓爬|翻|笼罩|散开|移动|凝成)"),
        ), 2),
        Triple(SceneEffectKind.SMOKE, listOf(
            rx("(冒|升|腾|飘|起|吐)<g>(烟|青烟|白烟|轻烟|烟尘)"),
            rx("(烟|烟雾|烟尘|炊烟)<g>(升|绕|散|滚|腾|遮|笼)"),
            rx("(浓烟|黑烟|烟尘|硝烟|黑尘)<g>(冲|弥漫|四起|蔽目|翻滚|遮蔽|裹挟|往外扑)"),
        ), 2),
        Triple(SceneEffectKind.RAYS, listOf(
            rx("(光|光柱|光束|晨光|夕光|霞光)<g>(透|斜|射|洒|倾|穿|落)<g>(进|下|过|入|在|到)"),
            rx("(透过|穿过|自)<g>(窗|云|叶|缝|隙)<g>(光|照|洒|射)"),
            rx("(一线|一道|一缕)<g>(光柱|光束|晨光|光线)<g>(斜插|斜射|穿过|从云缝|透过|落在)"),
        ), 2),

        // ===== 气泡 / 喷泉 / 波浪 =====
        Triple(SceneEffectKind.BUBBLES, listOf(
            rx("(冒|吐|浮|升|泛|鼓)<g>(出|起|上)<g>(泡|气泡|水泡)"),
            rx("(气泡|水泡|泡沫)<g>(上浮|浮起|破裂|升起|串串)")
        ), 2),
        Triple(SceneEffectKind.FOUNTAIN, listOf(
            rx("(喷|涌|溅|射|冲)<g>(出|起|上|开)<g>(水|水柱|水花|浪)"),
            rx("(水花|水柱|浪花|泉水)<g>(溅|飞|起|落|扬|喷)")
        ), 2),

        // ===== 情绪 / 状态符号 =====
        Triple(SceneEffectKind.HEART, listOf(
            rx("(心|心头|心跳|脸|颊|胸口)<g>(跳|热|红|烫|怦|一紧|一颤|漏了)"),
            rx("(怦然|脸红|心跳|心动)<g>(加速|起来|不止|如擂|漏一拍)"),
            rx("(怦然|心头|脸红|心跳|脸颊)<g>(一下|加速|先于|漏|漫上来|自己都|发烫)"),
        ), 2),
        Triple(SceneEffectKind.SWEAT, listOf(
            rx("(额|鬓|背|手心|掌心|颈|后颈)<g>(沁|冒|渗|出|满是|滑下)<g>(汗|冷汗|汗珠|细汗)"),
            rx("(汗|冷汗|汗珠)<g>(滴|落|滑|渗|冒|湿|透)"),
            rx("(捏|攥|抹|擦)<g>(了把|一把)<g>(汗|冷汗)"),
            rx("(鬓角|额角|脊背|掌心|指节|手心)<g>(沁出|沁|渗出|满是|滑落|顺着|捏出)<g>(冷汗|汗|细汗|汗珠)"),
        ), 2),
        Triple(SceneEffectKind.ANGER, listOf(
            rx("(怒|气|恼|愤|怒火)<g>(极|得|上涌|冲|翻|烧|不打一处|直冒)"),
            rx("(青筋|额角|太阳穴)<g>(暴|跳|起|凸)"),
            rx("(咬|攥|跺|拍案)<g>(牙|拳|脚|案)<g>(切齿|紧|起|怒)")
        ), 2),
        Triple(SceneEffectKind.ZZZ, listOf(
            rx("(困|睡|盹|哈欠|眼皮|脑袋)<g>(意|了|起来|沉|打架|发沉|一点一点)"),
            rx("(打|伸|揉)<g>(了个|着|了)<g>(哈欠|盹|懒腰)"),
            rx("(昏昏|恹恹|迷糊)<g>(欲睡|睡|沉|间)"),
            rx("(眼皮|脑袋|困意|睡意)<g>(发沉|打架|一点一点|上涌|朦胧|沉沉)"),
        ), 2),
        Triple(SceneEffectKind.LIGHTBULB, listOf(
            rx("(忽然|猛地|陡然|蓦地|灵光|心头|脑中)<g>(想|明|悟|闪过|冒出|一动)"),
            rx("(灵光|念头|主意|点子)<g>(一闪|一动|闪过|冒出|浮现)"),
            rx("(茅塞|恍然|豁然)<g>(顿开|大悟|开朗|明白)")
        ), 2),
        Triple(SceneEffectKind.SPARKLES, listOf(
            rx("(闪|亮|耀|烁|泛)<g>(出|起|着|过)<g>(光|亮|芒|星|晶)"),
            rx("(一闪|一亮|晶亮|亮晶晶|熠熠|粼粼|闪烁)<g>(而过|生辉|发光|夺目|不定)")
        ), 2),

        // ===== 演出类（名词够用，补动作化写法） =====
        Triple(SceneEffectKind.FIREWORKS, listOf(
            rx("(烟花|焰火|礼花|爆竹)<g>(升|绽|放|炸|散|开|亮|腾)"),
            rx("(放|点|燃|升)<g>(起|上|了)<g>(烟花|焰火|礼花|爆竹)"),
            rx("(夜空|天际|半空)<g>(绽|炸|开)<g>(出|一朵|一片|五彩)")
        ), 3),
        Triple(SceneEffectKind.METEORS, listOf(
            rx("(流星|陨星|星子|光点)<g>(划|掠|坠|落|飞|滑)<g>(过|下|向|去)"),
            rx("(划|掠|拖)<g>(过|着)<g>(天际|夜空|长空|天幕|尾焰)")
        ), 2),
        Triple(SceneEffectKind.STARS, listOf(
            rx("(星|星辰|繁星|星河|银河)<g>(闪|亮|布满|铺|挂|洒|悬)"),
            rx("(抬头|仰|望)<g>(去|天|空)<g>(星|夜|银河)")
        ), 2),
        Triple(SceneEffectKind.CONFETTI, listOf(
            rx("(撒|抛|扬|飘|落|飞舞)<g>(出|起|下|满)<g>(彩|纸|花|带|屑)"),
            rx("(彩纸|彩带|纸屑|彩旗)<g>(飞|飘|撒|落|舞|扬)")
        ), 2),
        Triple(SceneEffectKind.DANDELION, listOf(
            rx("(蒲公英|柳絮|飞絮|杨花|绒球|絮)<g>(飘|飞|散|吹|扬|起)"),
            rx("(吹|风)<g>(散|起|过)<g>(絮|绒|籽|白絮)")
        ), 2),
        Triple(SceneEffectKind.FEATHERS, listOf(
            rx("(羽|翎|白羽|羽毛)<g>(落|飘|飞|旋|坠|扬)"),
            rx("(一片|一根|几片)<g>(羽|翎|羽绒)")
        ), 2),
        Triple(SceneEffectKind.BOKEH, listOf(
            rx("(光|影|灯|火)<g>(斑|点|晕|圈)<g>(晃|闪|散|浮|摇)"),
            rx("(斑驳|朦胧|粼粼|摇曳)<g>(的|光|影|斑)")
        ), 2),
        Triple(SceneEffectKind.DUST, listOf(
            rx("(尘|沙|灰|土)<g>(扬|飞|起|腾|卷|落|浮|弥漫)"),
            rx("(扬|卷|掀|踏)<g>(起|着|得)<g>(尘|沙|土|灰)"),
            rx("(尘土|黄沙|尘烟|灰土|白尘)<g>(扬起|卷成|卷着|浮着|弥漫|遮蔽|扑面|不落)"),
        ), 2)
    )

    /** 无命中时交给按 VN 场景常驻的 idle 层，不再随机插入短特效打断它 */
    private const val AMBIENT_FALLBACK_RATE = 0.0
    private fun frac(v: Double): Double = v - kotlin.math.floor(v)
    private fun pseudo(i: Int, seed: Int): Double =
        frac(kotlin.math.sin(i * 127.1 + seed * 311.7) * 43758.5453)

    private fun ambientFallback(para: Int): Pair<SceneEffectKind, Int>? {
        if (pseudo(para, 77) >= AMBIENT_FALLBACK_RATE) return null
        val kinds = listOf(
            SceneEffectKind.BOKEH, SceneEffectKind.FOG,
            SceneEffectKind.DUST, SceneEffectKind.SPARKLES
        )
        val idx = (pseudo(para, 91) * kinds.size).toInt().coerceIn(0, kinds.size - 1)
        return kinds[idx] to 2
    }

    fun getCacheFile(bookName: String, chapterTitle: String): File =
        File(
            File(
                "/storage/emulated/0/Download/chajian/scene_effect/" +
                    AiCacheFileUtil.sanitizeBookName(bookName)
            ).apply { mkdirs() },
            AiCacheFileUtil.sanitizeChapterFileName(chapterTitle) + ".json"
        )

    /** 复位用：删除全部已落盘的段落特效缓存，让关键词重新分析 */
    fun clearAllCache(): Int {
        val root = File("/storage/emulated/0/Download/chajian/scene_effect")
        var count = 0
        runCatching {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .forEach { if (it.delete()) count++ }
        }
        return count
    }

    /**
     * 生成一个区间的场景特效。
     * @param aiEnhanced 是否调用大模型增强（关闭弹幕 / 无 AI Provider 时自动降级为纯本地规则）
     */
    suspend fun generateRange(
        bookName: String,
        chapterTitle: String,
        contentHash: String,
        paragraphs: List<Pair<Int, String>>,
        aiEnhanced: Boolean
    ): List<SceneEffectCue> = withContext(Dispatchers.IO) {
        if (paragraphs.isEmpty()) return@withContext emptyList()
        val cacheFile = getCacheFile(bookName, chapterTitle)
        val records = readCacheRecords(cacheFile, contentHash)

        // 「已检查但无特效」的段落也要记下来，避免每次重读都重复分析
        val todo = paragraphs.filter { !records.containsKey(it.first) }
        if (todo.isNotEmpty()) {
            val local = detectLocal(todo)
            val ai = if (aiEnhanced) requestAi(bookName, chapterTitle, todo) else emptyList()
            // AI 优先覆盖本地（同一段落同一类型只留一条）
            val merged = (ai + local).distinctBy { it.para to it.kind }
            merged.forEach { cue ->
                records.getOrPut(cue.para) { mutableListOf() }.add(cue)
            }
            todo.forEach { (index, _) -> records.getOrPut(index) { mutableListOf() } }
            writeCacheRecords(cacheFile, contentHash, records)
            AppLog.put("场景特效: 区间[${todo.first().first}~${todo.last().first}] 命中${merged.size}条")
        }

        paragraphs.flatMap { (index, _) -> records[index].orEmpty() }
            .sortedBy { it.para }
    }

    /** 本地关键词规则：每段最多产出一条主特效 */
    private fun detectLocal(paragraphs: List<Pair<Int, String>>): List<SceneEffectCue> =
        paragraphs.mapNotNull { (index, text) -> detectOne(index, text) }

    private fun detectOne(para: Int, text: String): SceneEffectCue? {
        val t = text.take(300)
        val indoor = INDOOR_WORDS.any { t.contains(it) }
        val wind = WIND_WORDS.any { t.contains(it) }
        val hasLightning = LIGHTNING_WORDS.any { t.contains(it) }

        val hit: Pair<SceneEffectKind, Int>? = when {
            SNOW_WORDS.any { t.contains(it) } ->
                SceneEffectKind.SNOW to snowLevel(t)
            RAIN_WORDS.any { t.contains(it) } ->
                SceneEffectKind.RAIN to rainLevel(t)
            LEAVES_WORDS.any { t.contains(it) } -> SceneEffectKind.LEAVES to 2
            PETALS_WORDS.any { t.contains(it) } -> SceneEffectKind.PETALS to 2
            FIREFLIES_WORDS.any { t.contains(it) } -> SceneEffectKind.FIREFLIES to 2
            EMBERS_WORDS.any { t.contains(it) } -> SceneEffectKind.EMBERS to 2
            else -> {
                // 被整类移除 / 在管理页关掉的大类不参与匹配：否则会先命中一条
                // 不可用的规则，把同一段里本来能触发的效果顶掉
                val rule = EXTRA_RULES.firstOrNull { (kind, words, _) ->
                    SceneEffectSettings.isEnabled(kind) && words.any { t.contains(it) }
                }
                // 名词词表没命中时，再补一轮动作句式正则
                val action = if (rule == null) {
                    ACTION_RULES.firstOrNull { (kind, patterns, _) ->
                        SceneEffectSettings.isEnabled(kind) &&
                            patterns.any { it.containsMatchIn(t) }
                    }
                } else null
                when {
                    rule != null -> rule.first to rule.third
                    action != null -> action.first to action.third
                    hasLightning -> SceneEffectKind.LIGHTNING to 3
                    wind -> SceneEffectKind.WIND to if (t.contains("狂风")) 4 else 2
                    else -> ambientFallback(para)
                }
            }
        }
        if (hit == null) return null

        val kind = hit.first
        // 管理界面里被关闭的效果：本地规则直接不产出，也不写进段落缓存
        if (!SceneEffectSettings.isEnabled(kind)) return null
        // 室内不播室外天气/植物类，避免「屋里下雪」的违和感
        if (indoor && SceneEffectKind.isOutdoorOnly(kind)) return null

        val duration = when (kind) {
            SceneEffectKind.LIGHTNING -> LIGHTNING_DURATION_MS
            SceneEffectKind.WIND -> WIND_DURATION_MS
            SceneEffectKind.RAIN, SceneEffectKind.SNOW -> WEATHER_DURATION_MS
            SceneEffectKind.SPARKLES, SceneEffectKind.QUESTION, SceneEffectKind.SWEAT,
            SceneEffectKind.HEART, SceneEffectKind.ANGER, SceneEffectKind.ZZZ,
            SceneEffectKind.LIGHTBULB, SceneEffectKind.COMIC -> 4000L
            SceneEffectKind.EXPLOSION, SceneEffectKind.SLASH,
            SceneEffectKind.IMPACT, SceneEffectKind.SHOCKWAVE -> 1200L
            SceneEffectKind.FIREWORKS, SceneEffectKind.METEORS,
            SceneEffectKind.CONFETTI -> 6000L
            else -> if (SceneEffectKind.isBurst(kind)) 1200L else DEFAULT_DURATION_MS
        }
        val comicText = if (kind == SceneEffectKind.COMIC)
            COMIC_WORDS.firstOrNull { t.contains(it) } else null
        return SceneEffectCue(
            para = para,
            kind = kind,
            level = hit.second.coerceIn(1, 4),
            durationMs = duration,
            indoors = indoor,
            wind = wind,
            // 雨雪段落里若同时写了雷电，叠加闪电轨道
            lightning = hasLightning && (kind == SceneEffectKind.RAIN || kind == SceneEffectKind.SNOW),
            source = "local",
            text = comicText
        )
    }

    private fun snowLevel(t: String): Int = when {
        t.contains("暴雪") || t.contains("鹅毛") -> 4
        t.contains("大雪") -> 3
        t.contains("小雪") || t.contains("细雪") -> 1
        else -> 2
    }

    private fun rainLevel(t: String): Int = when {
        t.contains("暴雨") || t.contains("倾盆") -> 4
        t.contains("大雨") -> 3
        t.contains("细雨") || t.contains("小雨") -> 1
        else -> 2
    }

    // ==================== AI 增强 ====================

    private suspend fun requestAi(
        bookName: String,
        chapterTitle: String,
        paragraphs: List<Pair<Int, String>>
    ): List<SceneEffectCue> {
        val provider = appDb.bgmAIProviderDao.getEnabled() ?: return emptyList()
        val result = try {
            BgmAIService.callOpenAIApi(
                provider,
                buildPrompt(bookName, chapterTitle, paragraphs),
                // 与弹幕共用区间队列，硬停止时同样掐断在途的 AI 增强请求
                onCallCreated = DanmakuRuntime::registerCall            )
        } catch (e: Exception) {
            AppLog.put("场景特效: AI增强失败 ${e.localizedMessage}")
            return emptyList()
        } finally {
            DanmakuRuntime.clearInFlight()        }
        return parseAiResult(result, paragraphs.map { it.first }.toSet())
    }

    private fun buildPrompt(
        bookName: String,
        chapterTitle: String,
        paragraphs: List<Pair<Int, String>>
    ): String {
        val sb = StringBuilder()
        paragraphs.forEach { (index, text) ->
            sb.append("【段落").append(index).append("】")
                .append(text.trim().take(160)).append('\n')
        }
        return """你是小说演出标注器。下面是《$bookName》章节「$chapterTitle」的部分段落，每段前有【段落N】编号。

## 任务
只标注**真实发生**的场景环境，供朗读时播放对应的画面特效（雨雪、飘落物、雾烟气泡光束、蝴蝶与鸟群、漫画符号、星空流星烟花等）。

## 规则
1. 只标正文明确写出的天气/景物，不推测；「心在下雪」「泪如雨下」「气氛冰冷」等比喻一律不标。
2. 室内（屋内、殿内、车厢等）不要标 RAIN/SNOW/PETALS/LEAVES/LIGHTNING，把 indoors 填 true 即可。
3. 同一段落最多一条；没有合适特效的段落不要输出。
4. para 必须照抄【段落N】里的 N，是该段的真实编号，不是序号。
5. level 只能 1~4：1=微弱，2=正常，3=明显，4=暴雨/暴雪/狂风。
6. 雨雪段落若同时出现雷电，把 lightning 填 true；有风把 wind 填 true。
7. durationMs 用 8000；闪电、漫画符号用 4000；烟花/流星/彩屑用 6000。
8. EXPLOSION/SLASH/IMPACT/SHOCKWAVE 及 ARCANE/FROST/PORTAL 等瞬时类，只在正文明确写出对应动作或法术时标，durationMs 用 1200；没有这些描写一律不标。

## 输出
只输出 JSON 数组，不要解释、不要 markdown 代码块：
[{"para":12,"kind":"SNOW","level":2,"indoors":false,"wind":true,"lightning":false,"durationMs":8000}]

kind 白名单：RAIN, SNOW, PETALS, LEAVES, LIGHTNING, FIREFLIES, EMBERS, WIND,
FOG, SMOKE, BUBBLES, RAYS, DUST, BUTTERFLIES, BIRDS_GULL, BIRDS_CROW, BIRD_FLOCK,
SPARKLES, QUESTION, SWEAT, HEART, ANGER, ZZZ, LIGHTBULB, COMIC,
STARS, METEORS, FIREWORKS, FIRE, CONFETTI, FOUNTAIN, BOKEH, FEATHERS, DANDELION,
EXPLOSION, SLASH, IMPACT, SHOCKWAVE,
ARCANE, HOLY, SHADOW, FROST, PORTAL, PROJECTILE, MAGIC_CIRCLE,
RIPPLE, WAVE, SHIMMER, SPARK_JET, RED_PACKET, KOI, DOOM_FIRE

## 段落正文
$sb"""
    }

    private fun parseAiResult(result: String, validParas: Set<Int>): List<SceneEffectCue> {
        if (result.isBlank() || validParas.isEmpty()) return emptyList()
        val cleaned = result
            .replace(Regex("(?s)<think>.*?</think>"), " ")
            .replace(Regex("(?s)<\\|.*?\\|>"), " ")
            .replace("```", " ")
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return try {
            val arr = JSONArray(cleaned.substring(start, end + 1))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val para = o.optInt("para", -1)
                val kind = SceneEffectKind.from(o.optString("kind")) ?: return@mapNotNull null
                if (!SceneEffectSettings.isEnabled(kind)) return@mapNotNull null
                if (para !in validParas) return@mapNotNull null
                val cue = SceneEffectCue(
                    para = para,
                    kind = kind,
                    level = o.optInt("level", 2).coerceIn(1, 4),
                    durationMs = o.optLong("durationMs", DEFAULT_DURATION_MS).coerceIn(1000L, 20000L),
                    indoors = o.optBoolean("indoors", false),
                    wind = o.optBoolean("wind", false),
                    lightning = o.optBoolean("lightning", false),
                    source = "ai"
                )
                if (cue.indoors && SceneEffectKind.isOutdoorOnly(cue.kind)) return@mapNotNull null
                cue
            }
        } catch (e: Exception) {
            AppLog.put("场景特效: AI结果解析失败 ${e.localizedMessage}")
            emptyList()
        }
    }

    // ==================== 段落级缓存 ====================

    private fun readCacheRecords(
        file: File,
        contentHash: String
    ): MutableMap<Int, MutableList<SceneEffectCue>> {
        val result = HashMap<Int, MutableList<SceneEffectCue>>()
        runCatching {
            if (!file.exists()) return result
            val json = JSONObject(file.readText())
            if (json.optString("contentHash") != contentHash) return result
            if (json.optInt("version", 1) != CACHE_VERSION) return result
            val records = json.optJSONObject("records") ?: return result
            records.keys().forEach { key ->
                val para = key.toIntOrNull() ?: return@forEach
                val arr = records.optJSONArray(key) ?: return@forEach
                val list = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { cueFromJson(para, it) }
                }
                result[para] = list.toMutableList()
            }
        }
        return result
    }

    private fun writeCacheRecords(
        file: File,
        contentHash: String,
        records: Map<Int, List<SceneEffectCue>>
    ) {
        runCatching {
            val json = JSONObject().apply {
                put("version", CACHE_VERSION)
                put("contentHash", contentHash)
                put("records", JSONObject().apply {
                    records.forEach { (para, cues) ->
                        put(para.toString(), JSONArray().also { arr ->
                            cues.forEach { arr.put(cueToJson(it)) }
                        })
                    }
                })
            }
            file.writeText(json.toString())
        }
    }

    private fun cueToJson(c: SceneEffectCue) = JSONObject().apply {
        put("kind", c.kind.name)
        put("level", c.level)
        put("durationMs", c.durationMs)
        put("indoors", c.indoors)
        put("wind", c.wind)
        put("lightning", c.lightning)
        put("source", c.source)
        c.text?.let { put("text", it) }
    }

    private fun cueFromJson(para: Int, o: JSONObject): SceneEffectCue? {
        val kind = SceneEffectKind.from(o.optString("kind")) ?: return null
        return SceneEffectCue(
            para = para,
            kind = kind,
            level = o.optInt("level", 2).coerceIn(1, 4),
            durationMs = o.optLong("durationMs", DEFAULT_DURATION_MS),
            indoors = o.optBoolean("indoors", false),
            wind = o.optBoolean("wind", false),
            lightning = o.optBoolean("lightning", false),
            source = o.optString("source", "local"),
            text = o.optString("text", "").takeIf { it.isNotEmpty() }
        )
    }
}
