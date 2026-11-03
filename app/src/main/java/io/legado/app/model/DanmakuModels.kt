package io.legado.app.model

/**
 * AI 弹幕（朗读弹幕）公共数据模型。
 *
 * 朗读服务按字数区间提前生成弹幕（与 AI 音效同款调度），
 * 每条绑定触发段落（contentList 下标）；朗读到该段落时
 * 通过 EventBus.VN_PARAGRAPH 命中并从屏幕上方错开飘过。
 */

/** 一条弹幕：触发段落下标 + 弹幕文本 */
data class DanmakuItem(
    /** 触发段落在章节 contentList 中的下标 */
    val para: Int,
    /** 弹幕文本 */
    val text: String
)

/** 事件 EventBus.DANMAKU_READY 的载荷：章节 key + 该章当前全部弹幕 */
data class DanmakuReady(
    /** 章节 key（书名|章节标题），消费方用于识别切章 */
    val key: String,
    /** 该章当前已生成的全部弹幕（增量合并后全量下发） */
    val items: List<DanmakuItem>
)

/**
 * 当前章节弹幕的内存暂存：朗读弹窗打开/重建时无需等服务重新生成，
 * 直接从这里取最新一章的结果。
 */
object DanmakuStore {

    /** 章节 key（书名|章节标题），用于判断暂存结果是否仍是当前章 */
    @Volatile
    var key: String? = null
        private set

    @Volatile
    private var items: List<DanmakuItem> = emptyList()

    fun put(newKey: String, newItems: List<DanmakuItem>) {
        key = newKey
        items = newItems
    }

    /**
     * 合并一个区间的生成结果并返回该章全量弹幕。
     * key 不匹配（已切章）时替换为新结果。
     */
    @Synchronized
    fun merge(newKey: String, newItems: List<DanmakuItem>): List<DanmakuItem> {
        if (key != newKey) {
            key = newKey
            items = newItems
        } else {
            items = (items + newItems).distinctBy { it.para to it.text }
        }
        return items
    }

    /** 取指定章节的弹幕；key 不匹配（已切章）时返回空 */
    fun get(forKey: String): List<DanmakuItem> =
        if (key == forKey) items else emptyList()

    fun clear() {
        key = null
        items = emptyList()
    }
}
