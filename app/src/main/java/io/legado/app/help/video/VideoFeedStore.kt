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
