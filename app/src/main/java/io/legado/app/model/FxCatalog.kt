package io.legado.app.model

import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx

/**
 * 开源序列帧特效目录（`assets/fx/catalog.json`，由 patch-gg/pixelfx MIT 全量导出生成）。
 *
 * 两级结构：52 个画面 kind（大类）→ 每个大类下 10~33 个具体序列帧特效。
 * 管理界面可展开到第二级逐条试播/禁用；运行时 pick() 会跳过被禁用的具体特效。
 */
object FxCatalog {

    /** 一个具体特效（对应 assets/fx/<id>.png + .json） */
    data class FxInfo(
        val id: String,
        val name: String,
        val zh: String,
        val cat: String,
        val catName: String,
        val frames: Int,
        val loop: Boolean
    )

    private val CAT_NAMES = mapOf(
        "arcane" to "奥术", "critters" to "生物", "explosions" to "爆炸",
        "fire" to "火焰", "holy" to "神圣", "ice" to "冰霜",
        "impacts" to "命中", "lightning" to "雷电", "melee" to "近战",
        "moves" to "招式", "nature" to "自然", "pickups" to "拾取",
        "projectiles" to "投射", "scifi" to "科幻", "shadow" to "暗影",
        "spawn" to "现身", "status" to "状态", "transitions" to "转场",
        "ui" to "界面", "water" to "水", "weather" to "天气"
    )

    private val items = ArrayList<FxInfo>()
    private val itemMap = HashMap<String, FxInfo>()
    private val byKind = HashMap<String, List<String>>()
    private val byWord = HashMap<String, MutableList<String>>()

    @Volatile
    private var ready = false

    private fun load() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            try {
                val json = JSONObject(
                    String(appCtx.assets.open("fx/catalog.json").readBytes())
                )
                val arr = json.optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isEmpty()) continue
                    val cat = o.optString("cat")
                    val info = FxInfo(
                        id = id,
                        name = o.optString("name"),
                        zh = o.optString("zh").ifBlank { o.optString("name") },
                        cat = cat,
                        catName = CAT_NAMES[cat] ?: cat,
                        frames = o.optInt("frames", 0),
                        loop = o.optBoolean("loop")
                    )
                    items.add(info)
                    itemMap[id] = info
                    val kwArr = o.optJSONArray("kw") ?: JSONArray()
                    for (j in 0 until kwArr.length()) {
                        val word = kwArr.optString(j)
                        if (word.isEmpty()) continue
                        byWord.getOrPut(word) { ArrayList() }.add(id)
                    }
                }
                val kinds = json.optJSONObject("kinds") ?: JSONObject()
                val keys = kinds.keys()
                while (keys.hasNext()) {
                    val kind = keys.next()
                    val list = kinds.optJSONArray(kind) ?: continue
                    val ids = ArrayList<String>(list.length())
                    for (j in 0 until list.length()) {
                        val v = list.optString(j)
                        if (v.isNotEmpty()) ids.add(v)
                    }
                    byKind[kind] = ids
                }
            } catch (e: Exception) {
                // 目录不可用时保持空表，全部走算法绘制
            }
            ready = true
        }
    }

    /** 目录里的具体特效总数 */
    fun size(): Int {
        load()
        return items.size
    }

    /** 当前仍启用的具体特效数（管理界面统计用） */
    fun enabledSize(): Int {
        load()
        return items.count { SceneEffectSettings.isFxEnabled(it.id) }
    }

    /** 某个大类下的全部具体特效，按目录顺序 */
    fun itemsFor(kind: SceneEffectKind): List<FxInfo> {
        load()
        return byKind[kind.name].orEmpty().mapNotNull { itemMap[it] }
    }

    fun info(id: String): FxInfo? {
        load()
        return itemMap[id]
    }

    /** 该大类下「可用」的具体特效数（过滤被单独禁用的） */
    fun countFor(kind: SceneEffectKind): Int =
        itemsFor(kind).count { SceneEffectSettings.isFxEnabled(it.id) }

    private fun usable(id: String): Boolean = SceneEffectSettings.isFxEnabled(id)

    /** 关键词 → 素材：长词优先，避免「火」抢走「篝火」；跳过被禁用的素材 */
    fun match(text: String?): String? {
        if (text.isNullOrBlank()) return null
        load()
        if (byWord.isEmpty()) return null
        var best: String? = null
        var bestLen = 0
        for ((word, ids) in byWord) {
            if (word.length <= bestLen || !text.contains(word)) continue
            val usableIds = ids.filter { usable(it) }
            if (usableIds.isEmpty()) continue
            val idx = ((text.hashCode() % usableIds.size) + usableIds.size) % usableIds.size
            best = usableIds[idx]
            bestLen = word.length
        }
        return best
    }

    /**
     * 为一条 cue 挑素材：hint（音效名/正文片段）命中优先，
     * 否则在该 kind 仍启用的候选池里确定性轮播。
     */
    fun pick(kind: SceneEffectKind, hint: String?, seed: Int): String? {
        load()
        val pool = byKind[kind.name].orEmpty().filter { usable(it) }
        val hinted = match(hint)
        if (hinted != null && pool.contains(hinted)) return hinted
        if (pool.isEmpty()) return null
        return pool[((seed % pool.size) + pool.size) % pool.size]
    }
}
