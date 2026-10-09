package io.legado.app.model

import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import splitties.init.appCtx
import java.net.URI

/**
 * AI 生成书源的「历史成功规则」记录。
 *
 * 背景：生成书源有偶然性——同一网址这次可能搜索没过、下次可能正文没过，退出重进往往又能过别的环节。
 * 书源本身很小，所以每生成一次就把「本次通过的环节 + 当时的书源」按网址存下来；
 * 下次同一网址再生成、仍有环节没过时，直接从历史里把对应环节的规则搬过来补上。
 *
 * 存储：SharedPreferences 单 key 存整份 JSON（不进 Room，免数据库迁移），重启 App 仍在。
 */
object AiSourceHistory {

    /** 一条历史记录：当时的书源 JSON + 通过的环节 + 记录时间 */
    data class Record(
        var sourceJson: String = "",
        var okSteps: List<String> = emptyList(),
        var time: Long = 0
    )

    /** 每个网址最多保留的条数（书源很小，留够几次重试的量即可） */
    private const val MAX_PER_URL = 10

    /** 归一化成网址主键：同站点的 www./m. 前缀与大小写差异都归到一起 */
    fun keyOf(url: String): String {
        val raw = url.trim()
        val host = runCatching { URI(raw).host }.getOrNull()
        return (host ?: raw)
            .removePrefix("www.").removePrefix("m.")
            .lowercase()
            .ifBlank { raw.lowercase() }
    }

    private fun loadAll(): MutableMap<String, MutableList<Record>> {
        val json = appCtx.getPrefString(PreferKey.aiSourceRuleHistory)
        if (json.isNullOrBlank()) return mutableMapOf()
        return GSON.fromJsonObject<MutableMap<String, MutableList<Record>>>(json).getOrNull()
            ?: mutableMapOf()
    }

    private fun persist(all: Map<String, List<Record>>) {
        val json = runCatching { GSON.toJson(all) }.getOrNull()
        appCtx.putPrefString(PreferKey.aiSourceRuleHistory, json)
    }

    /** 记录一次生成结果：只记通过的环节，供下次同一网址补齐 */
    @Synchronized
    fun save(url: String, source: BookSource, okSteps: List<String>) {
        if (okSteps.isEmpty()) return
        val json = runCatching { GSON.toJson(source) }.getOrNull() ?: return
        val key = keyOf(url)
        val all = loadAll()
        val list = all.getOrPut(key) { mutableListOf() }
        val last = list.lastOrNull()
        if (last != null && last.sourceJson == json && last.okSteps == okSteps) {
            // 与上次结果一致：只刷新时间，避免堆重复记录
            last.time = System.currentTimeMillis()
        } else {
            list.add(Record(json, okSteps, System.currentTimeMillis()))
            while (list.size > MAX_PER_URL) list.removeAt(0)
        }
        persist(all)
    }

    /**
     * 挑一条最能覆盖 [failedSteps] 的历史记录（覆盖环节数相同时取最新的）。
     * 没有任何记录能补上当前失败项时返回 null。
     */
    fun pickBest(url: String, failedSteps: Set<String>): Record? {
        if (failedSteps.isEmpty()) return null
        val list = loadAll()[keyOf(url)] ?: return null
        return list.asReversed()
            .filter { rec -> failedSteps.any { it in rec.okSteps } }
            .maxByOrNull { rec -> rec.okSteps.count { it in failedSteps } }
    }

    /** 把历史记录还原成书源（JSON 损坏时返回 null） */
    fun parse(record: Record): BookSource? =
        runCatching { GSON.fromJsonObject<BookSource>(record.sourceJson).getOrNull() }.getOrNull()

    /** 已记录的网址条数（用于界面提示与清除确认） */
    fun urlCount(): Int = loadAll().size

    /** 清除所有网址的历史记录 */
    @Synchronized
    fun clearAll() {
        appCtx.removePref(PreferKey.aiSourceRuleHistory)
    }
}
