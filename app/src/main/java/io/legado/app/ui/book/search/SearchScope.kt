package io.legado.app.ui.book.search

import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.dao.getVideoSourceParts
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.splitNotBlank
import splitties.init.appCtx

/**
 * 搜索范围
 */
@Suppress("unused")
data class SearchScope(private var scope: String) {

    companion object {
        /**
         * 「所有视频源」范围标记。
         * 不含 "::" 与 ","，所以不会和单源 / 分组这两种解析方式冲突。
         * 视频源是按条件筛出来的一批源，不是一个分组，只能这样表达。
         */
        const val VIDEO_SCOPE = "@video@"

        fun video(): SearchScope = SearchScope(VIDEO_SCOPE)
    }

    constructor(groups: List<String>) : this(groups.joinToString(","))

    constructor(source: BookSource) : this(
        "${source.bookSourceName.replace(":", "")}::${source.bookSourceUrl}"
    )

    constructor(source: BookSourcePart) : this(
        "${source.bookSourceName.replace(":", "")}::${source.bookSourceUrl}"
    )

    override fun toString(): String {
        return scope
    }

    val stateLiveData = MutableLiveData(scope)

    fun update(scope: String, postValue: Boolean = true, save: Boolean = true) {
        this.scope = scope
        if (postValue) stateLiveData.postValue(scope)
        if (save) { //不对单书源的搜索进行缓存，防止下次依旧为单书源搜索（单书源搜索需要每次都指定）
            save()
        }
    }

    fun update(groups: List<String>) {
        scope = groups.joinToString(",")
        stateLiveData.postValue(scope)
        save()
    }

    fun update(source: BookSource) {
        scope = "${source.bookSourceName}::${source.bookSourceUrl}"
        stateLiveData.postValue(scope)
        if (!isSource()) {
            save()
        }
    }

    fun isSource(): Boolean {
        return scope.contains("::")
    }

    val display: String
        get() {
            if (scope == VIDEO_SCOPE) {
                return appCtx.getString(R.string.video)
            }
            if (scope.contains("::")) {
                return scope.substringBefore("::")
            }
            if (scope.isEmpty()) {
                return appCtx.getString(R.string.all_source)
            }
            return scope
        }

    /**
     * 搜索范围显示
     */
    val displayNames: List<String>
        get() {
            val list = arrayListOf<String>()
            if (scope.contains("::")) {
                list.add(scope.substringBefore("::"))
            } else {
                scope.splitNotBlank(",").forEach {
                    list.add(it)
                }
            }
            return list
        }

    fun remove(scope: String) {
        if (isSource()) {
            this.scope = ""
        } else {
            val stringBuilder = StringBuilder()
            this.scope.split(",").forEach {
                if (it != scope) {
                    if (stringBuilder.isNotEmpty()) {
                        stringBuilder.append(",")
                    }
                    stringBuilder.append(it)
                }
            }
            this.scope = stringBuilder.toString()
        }
        stateLiveData.postValue(this.scope)
    }

    /**
     * 搜索范围书源
     */
    fun getBookSourceParts(): List<BookSourcePart> {
        val list = hashSetOf<BookSourcePart>()
        // 视频源范围：直接取判定的视频源。这里不能走下面的"取不到就退回全部源"兜底，
        // 否则视频源一个都没有时会变成全库搜索
        if (scope == VIDEO_SCOPE) {
            return appDb.bookSourceDao.getVideoSourceParts()
        }
        if (scope.isEmpty()) {
            list.addAll(appDb.bookSourceDao.allEnabledPart)
        } else {
            if (scope.contains("::")) {
                scope.substringAfter("::").let {
                    appDb.bookSourceDao.getBookSourcePart(it)?.let { source ->
                        list.add(source)
                    }
                }
            } else {
                val oldScope = scope.splitNotBlank(",")
                val newScope = oldScope.filter {
                    val bookSources = appDb.bookSourceDao.getEnabledPartByGroup(it)
                    list.addAll(bookSources)
                    bookSources.isNotEmpty()
                }
                if (oldScope.size != newScope.size) {
                    update(newScope)
                    stateLiveData.postValue(scope)
                }
            }
            if (list.isEmpty()) {
                scope = ""
                appDb.bookSourceDao.allEnabledPart.let {
                    if (it.isNotEmpty()) {
                        stateLiveData.postValue(scope)
                        list.addAll(it)
                    }
                }
            }
        }
        return excludeVideoSource(list.sortedBy { it.customOrder })
    }

    /**
     * 「不搜索视频源」（书架搜索菜单里的勾选项）：勾上后搜索只走普通书源。
     *
     * 视频源有独立的入口（视频页里的搜索），混在书架搜索里既拖速度、
     * 又总把视频结果排在前面，所以给一个开关彻底排除。
     * 两点边界：
     * - 范围是「所有视频源」([VIDEO_SCOPE]) 时不过滤——那本来就是搜视频的入口；
     * - 单源搜索（[isSource]，即换源时指定的那一个源）不过滤——那是用户显式点名的源。
     */
    private fun excludeVideoSource(list: List<BookSourcePart>): List<BookSourcePart> {
        if (!appCtx.getPrefBoolean(PreferKey.searchExcludeVideo, false)) return list
        if (isSource()) return list
        val videoUrls = appDb.bookSourceDao.getVideoSourceParts()
            .mapTo(hashSetOf()) { it.bookSourceUrl }
        return list.filterNot { it.bookSourceUrl in videoUrls }
    }

    fun isAll(): Boolean {
        return scope.isEmpty()
    }

    fun save() {
        AppConfig.searchScope = scope
        if (isAll() || isSource() || scope.contains(",")) {
            AppConfig.searchGroup = ""
        } else {
            AppConfig.searchGroup = scope
        }
    }

}
