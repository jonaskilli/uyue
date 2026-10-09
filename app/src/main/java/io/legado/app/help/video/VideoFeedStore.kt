package io.legado.app.help.video

/**
 * 搜索/发现入口 → 播放器的视频列表传递站。
 *
 * 列表是**动态供给**的：[Feed.provider] 每次切换时实时求值，
 * 发现页/搜索页后续分页加载出来的视频会自动进入可切换范围。
 *
 * [Feed.loadMore]：切换到当前视频时，若后续列表不足则立即后台加载下一页，
 * 保证"当前页在播时下一页已加载"，可以一直向下切换到列表真正的末尾。
 *
 * 有效性约束：消费时要求列表包含当前书籍且未过期（10 分钟），
 * 避免小说等非视频链路的陈旧列表串到无关播放里。
 */
object VideoFeedStore {

    class Feed(
        val provider: () -> List<String>,
        val bookUrl: String,
        val time: Long,
        val loadMore: (() -> Unit)? = null,
        /**
         * 切到某个视频时从第几集开始播，默认第 0 集。
         * 「已下载视频」列表用它跳到该视频第一集已下载的集，避免切过去后从没下载的集开始播。
         */
        val startIndexOf: (String) -> Int = { 0 },
        /**
         * put 时刻的列表快照。
         *
         * provider 是"实时求值"的，好处是分页新加载的视频能自动进入切换范围；
         * 但播放页一开（ExoPlayer + 嗅探用的 WebView 都很吃内存），后台的发现页/搜索页
         * 很容易被系统回收，provider 随之返回空列表——于是"下一个视频"永远算不出来，
         * 预缓存静默失效。这里留一份快照兜底：provider 空了就退回它。
         */
        val snapshot: List<String> = emptyList(),
    ) {
        /** 当前时刻的完整列表（含分页新加载的部分） */
        fun urls(): List<String> {
            val list = runCatching { provider() }.getOrDefault(emptyList())
            return if (list.isEmpty()) snapshot else list
        }
    }

    @Volatile
    private var feed: Feed? = null

    /**
     * 常驻 feed（无限刷专用）。
     *
     * [take] 是一次性的（取走即清空），进程被杀后 Activity 重建时会话里的列表也没了，
     * 结果就是"无限刷退化成单视频、滑不动"。无限刷的列表由 [provider] 实时供给，
     * 本就应该长期有效，所以这里额外留一份**不会被 take 清掉**的引用，
     * 供播放器在任何时候重新挂上。
     */
    @Volatile
    private var persistent: Feed? = null

    /** [currentBookUrl]：点击的那一条，用于记录起始位置 */
    fun put(
        provider: () -> List<String>,
        currentBookUrl: String,
        loadMore: (() -> Unit)? = null,
        startIndexOf: (String) -> Int = { 0 },
    ) {
        val list = runCatching { provider() }.getOrDefault(emptyList())
        if (list.size < 2 || !list.contains(currentBookUrl)) return
        feed = Feed(provider, currentBookUrl, System.currentTimeMillis(), loadMore, startIndexOf, list)
    }

    /**
     * 登记一条常驻 feed（无限刷）：不设过期、不被 [take] 清掉，
     * 播放页任何时候都能用 [peekPersistent] 重新挂上。
     */
    fun putPersistent(
        provider: () -> List<String>,
        currentBookUrl: String,
        loadMore: (() -> Unit)? = null,
    ) {
        val list = runCatching { provider() }.getOrDefault(emptyList())
        if (list.isEmpty()) return
        persistent = Feed(provider, currentBookUrl, System.currentTimeMillis(), loadMore, { 0 }, list)
    }

    /** 取用常驻 feed（列表里包含当前视频才有效） */
    fun peekPersistent(bookUrl: String): Feed? {
        val f = persistent ?: return null
        return if (f.urls().contains(bookUrl)) f else null
    }

    /** 退出播放器时清掉常驻 feed：下次从分类/搜索进入都使用当时的列表 */
    fun clearPersistent() {
        persistent = null
    }

    /** 播放器消费：命中当前书籍且未过期才返回，无论是否命中都清空 */
    fun take(bookUrl: String): Feed? {
        val f = feed
        feed = null
        if (f == null) return null
        if (System.currentTimeMillis() - f.time > 10 * 60_000L) return null
        if (!f.urls().contains(bookUrl)) return null
        return f
    }
}
