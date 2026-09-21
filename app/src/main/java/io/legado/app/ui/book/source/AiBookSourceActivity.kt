package io.legado.app.ui.book.source

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.ActivityAiBookSourceBinding
import io.legado.app.help.book.removeType
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.AiBookSourceGenerator
import io.legado.app.model.AiSourceHistory
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 生成书源。
 *
 * 用法：填一个小说网站地址 → 点「开始生成」。
 * 过程是「算法抓页面 + AI 写规则」，详见 [AiBookSourceGenerator]。
 * 生成成功后可直接保存到书源列表，之后就能像普通书源那样搜索、加书架、阅读。
 */
class AiBookSourceActivity : BaseActivity<ActivityAiBookSourceBinding>() {

    override val binding by viewBinding(ActivityAiBookSourceBinding::inflate)

    /** 生成结果，保存按钮据此落库 */
    private var generated: BookSource? = null
    private var generateJob: Job? = null

    /** 导入书籍：用户选中的书源地址 */
    private var selectedSourceUrl: String? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.btnGenerate.setOnClickListener { startGenerate() }
        // 导入书籍：按详情页网址匹配同域书源，选中后用下方「导入书架」按钮执行
        binding.btnGenerateOne.setOnClickListener { importBook() }
        binding.btnImport.setOnClickListener { importSelected() }
        binding.btnSave.setOnClickListener { saveSource() }
        binding.btnLogin.setOnClickListener { gotoLogin() }
    }

    /** 右上角菜单 */
    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ai_book_source, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_clear_history -> clearHistory()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /**
     * 清除所有网址的历史成功规则记录。
     * 只清「生成时自动记录的成功项」，不影响已经保存到书源列表里的书源。
     */
    private fun clearHistory() {
        val count = AiSourceHistory.urlCount()
        if (count == 0) {
            toastOnUi("当前还没有任何网址记录")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清除网址记录")
            .setMessage(
                "将清除已记录的 $count 个网址的历史成功规则。\n" +
                    "清除后，再生成同一网址时不会再用历史规则补齐。\n" +
                    "（不影响已保存到书源列表的书源）"
            )
            .setPositiveButton("确定") { _, _ ->
                AiSourceHistory.clearAll()
                toastOnUi("已清除 $count 个网址的记录")
                binding.tvProgress.text = "已清除全部网址记录（共 $count 个）"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 导入书籍第一步：粘贴详情页网址 → 匹配同域书源 → 弹框选择。
     * 选中后启用「导入书架」按钮，导入动作由用户显式触发，每一步都有可见反馈。
     */
    private fun importBook() {
        val infoUrl = binding.etBookInfoUrl.text?.toString()?.trim().orEmpty()
        if (infoUrl.isBlank()) {
            toastOnUi("请先在「书籍信息页网址」里填这本书的详情页地址")
            return
        }
        binding.tvProgress.text = "正在按网址匹配书源…"
        lifecycleScope.launch {
            val matched = withContext(Dispatchers.IO) { matchSources(infoUrl) }
            if (matched.isEmpty()) {
                binding.tvProgress.text = "所有书源里没有与该网址同域的"
                AlertDialog.Builder(this@AiBookSourceActivity)
                    .setTitle("未找到匹配的书源")
                    .setMessage("所有书源里没有与该网址同域的书源。\n是否用这个网址生成一个单本书源（只生成目录与正文规则）？")
                    .setPositiveButton("生成") { _, _ -> startGenerate(singleBook = true) }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                binding.tvProgress.text = "找到 ${matched.size} 个同域书源，请选择"
                val names = matched.map { it.getDisPlayNameGroup() }.toTypedArray()
                AlertDialog.Builder(this@AiBookSourceActivity)
                    .setTitle("选择用哪个书源打开这本书")
                    .setSingleChoiceItems(names, 0) { dialog, which ->
                        dialog.dismiss()
                        selectedSourceUrl = matched[which].bookSourceUrl
                        binding.btnImport.text = "导入书架（书源：${matched[which].bookSourceName}）"
                        binding.btnImport.alpha = 1f
                        binding.tvProgress.text =
                            "已选书源「${matched[which].bookSourceName}」，点下方「导入书架」开始导入"
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    /** 导入书籍第二步：把选中的书源与详情页交给导入流程 */
    private fun importSelected() {
        val sourceUrl = selectedSourceUrl
        if (sourceUrl == null) {
            toastOnUi("请先点「导入书籍」选择书源")
            return
        }
        val infoUrl = binding.etBookInfoUrl.text?.toString()?.trim().orEmpty()
        if (infoUrl.isBlank()) {
            toastOnUi("请先填写书籍详情页网址")
            return
        }
        importBookWith(sourceUrl, infoUrl)
    }

    /**
     * 网址匹配：详情页 host 与书源地址 host 比——先精确一致，
     * 再按主域（去掉 m./www. 前缀）一致，兼容 www.x.com 与 m.x.com 的差异
     */
    private fun matchSources(infoUrl: String): List<BookSourcePart> {
        val host = runCatching { java.net.URI(infoUrl).host }.getOrNull().orEmpty()
        if (host.isBlank()) return emptyList()
        val mainDomain = host.removePrefix("www.").removePrefix("m.")
        fun sourceHost(u: String) = runCatching { java.net.URI(u).host }.getOrNull().orEmpty()
        val sources = appDb.bookSourceDao.allEnabledPart
        val exact = sources.filter { sourceHost(it.bookSourceUrl) == host }
        if (exact.isNotEmpty()) return exact
        return sources.filter {
            val h = sourceHost(it.bookSourceUrl)
            h.endsWith(".$mainDomain") ||
                h.removePrefix("www.").removePrefix("m.") == mainDomain
        }
    }

    /** 用选中的书源打开详情页：解析书籍信息与目录后加入书架，每步都有进度反馈 */
    private fun importBookWith(sourceUrl: String, infoUrl: String) {
        binding.btnImport.alpha = 0.4f
        binding.tvProgress.text = "① 正在读取书源…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val source = appDb.bookSourceDao.getBookSource(sourceUrl)
                        ?: error("书源不存在或已被删除")
                    withContext(Dispatchers.Main) {
                        binding.tvProgress.text = "② 正在用「${source.bookSourceName}」获取书籍信息…"
                    }
                    val book = Book(
                        bookUrl = infoUrl,
                        origin = source.bookSourceUrl,
                        originName = source.bookSourceName,
                        type = BookType.text,
                        order = appDb.bookDao.minOrder - 1
                    )
                    val info = runCatching {
                        WebBook.getBookInfoAwait(source, book, canReName = true)
                    }.getOrElse { throw Exception("获取书籍信息失败：${it.message}") }
                    if (info.name.isBlank()) throw Exception("获取书籍信息失败：未能解析出书名")
                    withContext(Dispatchers.Main) {
                        binding.tvProgress.text = "③ 已解析出《${info.name}》，正在获取目录…"
                    }
                    val toc = runCatching {
                        WebBook.getChapterListAwait(source, info).getOrNull().orEmpty()
                    }.getOrElse { throw Exception("获取目录失败：${it.message}") }
                    if (toc.isEmpty()) {
                        throw Exception("目录解析出 0 章——该书源的目录规则对这个页面不适用，请在书源列表里换其他同域书源")
                    }
                    withContext(Dispatchers.Main) {
                        binding.tvProgress.text = "④ 目录 ${toc.size} 章，正在加入书架…"
                    }
                    if (info.tocUrl.isBlank()) info.tocUrl = infoUrl
                    info.removeType(BookType.notShelf)
                    info.order = appDb.bookDao.minOrder - 1
                    info.save()
                    appDb.bookChapterDao.insert(*toc.toTypedArray())
                    info to toc.size
                }
            }
            if (result.isSuccess) {
                val (book, count) = result.getOrNull()!!
                binding.btnImport.alpha = 0.4f
                binding.tvProgress.text = "已加入书架：《${book.name}》共 $count 章（书源：${book.originName}）"
                toastOnUi("已加入书架：《${book.name}》")
                // 导入成功直接跳到这本书的详情页
                val intent = Intent(this@AiBookSourceActivity, BookInfoActivity::class.java)
                intent.putExtra("name", book.name)
                intent.putExtra("author", book.author)
                intent.putExtra("bookUrl", book.bookUrl)
                startActivity(intent)
            } else {
                // 失败原因完整显示在进度区（不只靠一闪而过的 toast），便于定位
                binding.btnImport.alpha = 1f
                binding.tvProgress.text = "导入失败：${result.exceptionOrNull()?.message}"
                toastOnUi("导入失败：${result.exceptionOrNull()?.message}")
            }
        }
    }

    /** 打开 WebView 登录，登录后 cookie 按域名存入 CookieStore，生成与阅读都会自动带上 */
    private fun gotoLogin() {
        val loginUrl = binding.etLoginUrl.text?.toString()?.trim().orEmpty()
        if (loginUrl.isBlank()) {
            toastOnUi("请先填写登录地址")
            return
        }
        val siteUrl = binding.etUrl.text?.toString()?.trim().orEmpty()
            .ifBlank { loginUrl }
        WebViewLoginActivity.start(this, loginUrl, siteUrl)
    }

    override fun onDestroy() {
        super.onDestroy()
        generateJob?.cancel()
    }

    private fun startGenerate(singleBook: Boolean = false) {
        // 手动指定：填了就直接用这两个页面，跳过「程序猜详情页 / 猜目录页」
        val bookInfoUrl = binding.etBookInfoUrl.text?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
        val tocUrl = binding.etTocUrl.text?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
        // 单本模式下可以只填书籍信息页，站点地址不是必需的
        val url = binding.etUrl.text?.toString()?.trim().orEmpty()
            .ifBlank { bookInfoUrl.orEmpty() }
        if (url.isBlank()) {
            toastOnUi("请先填写小说网站地址（单本模式也可只填书籍信息页网址）")
            return
        }
        if (singleBook && bookInfoUrl == null) {
            toastOnUi("单本模式需要填写书籍信息页网址")
            return
        }
        // 留空则由程序自动取站点热词；一旦填了就以用户输入为准，
        // 生成器侧不会再拿 AI 建议词覆盖它（见 AiBookSourceGenerator.generate）
        val keyword = binding.etKeyword.text?.toString()?.trim()
            .takeIf { !it.isNullOrBlank() }
        val loginUrl = binding.etLoginUrl.text?.toString()?.trim()
            .takeIf { !it.isNullOrBlank() }
        val includeExplore = binding.cbExplore.isChecked
        val codeAssist = binding.cbCodeAssist.isChecked
        val videoMode = binding.cbVideo.isChecked
        val fixRounds = binding.etFixRounds.text?.toString()?.trim()
            ?.toIntOrNull()?.coerceIn(0, 8) ?: 2

        generated = null
        binding.etResult.setText("")
        binding.btnSave.alpha = 0.4f
        binding.btnGenerate.alpha = 0.4f
        binding.btnGenerateOne.alpha = 0.4f
        binding.tvProgress.text = "准备中…"

        generateJob?.cancel()
        generateJob = lifecycleScope.launch {
            val result = AiBookSourceGenerator.generate(
                url, keyword, loginUrl, includeExplore, fixRounds,
                bookInfoUrl, tocUrl, singleBook,
                codeAssist = codeAssist, videoMode = videoMode
            ) { message ->
                // 回调在 IO 线程，进度必须切回主线程更新 UI
                runOnUiThread { binding.tvProgress.text = message }
            }
            binding.btnGenerate.alpha = 1f
            binding.btnGenerateOne.alpha = 1f
            result.onSuccess { res ->
                val source = res.source
                generated = source
                binding.tvProgress.text = buildString {
                    append("生成完成：").append(source.bookSourceName)
                    if (res.fixRounds > 0) {
                        append("（AI 修正 ").append(res.fixRounds).append(" 轮）")
                    }
                    if (res.patchedSteps.isNotEmpty()) {
                        append("\n（已用该网址的历史记录补齐并通过：")
                            .append(res.patchedSteps.joinToString("、")).append("）")
                    }
                    append('\n')
                    append("五链路验证：\n")
                    res.verify.forEach { v ->
                        append('[').append(if (v.ok) "通过" else "失败").append("] ")
                            .append(v.step).append("：").append(v.message).append('\n')
                    }
                    if (res.verify.any { !it.ok }) {
                        append("（存在失败环节的规则可能不可用，建议手动校验后再用）")
                    }
                    val okCount = res.verify.count { it.ok }
                    if (!singleBook && okCount > 0) {
                        append("\n已记录该网址的 $okCount 项成功规则，下次生成同一网址可直接补齐。")
                    }
                }
                binding.etResult.setText(runCatching { GSON.toJson(source) }.getOrDefault(""))
                binding.btnSave.alpha = if (res.verify.all { it.ok }) 1f else 0.7f
                if (singleBook) askAddToBookshelf(source)
            }.onFailure {
                binding.tvProgress.text = "生成失败：${it.message}"
                toastOnUi(it.message ?: "生成失败")
            }
        }
    }

    /** 单本模式生成完毕：询问是否加入书架 */
    private fun askAddToBookshelf(source: BookSource) {
        AlertDialog.Builder(this)
            .setTitle("是否添加到书架")
            .setMessage("书源「${source.bookSourceName}」已生成并保存，是否把这本书添加到书架？")
            .setPositiveButton("同意") { _, _ -> addToBookshelf(source) }
            .setNegativeButton("暂不", null)
            .show()
    }

    /** 单本生成完的书源先落库，再复用导入逻辑把书加入书架 */
    private fun addToBookshelf(source: BookSource) {
        val infoUrl = binding.etBookInfoUrl.text?.toString()?.trim().orEmpty()
        if (infoUrl.isBlank()) return
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { SourceHelp.insertBookSource(source) }.isSuccess
            }
            if (!saved) {
                toastOnUi("书源保存失败，无法加入书架")
                return@launch
            }
            importBookWith(source.bookSourceUrl, infoUrl)
        }
    }

    private fun saveSource() {
        val source = generated ?: return
        binding.btnSave.alpha = 0.4f
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { SourceHelp.insertBookSource(source) }.exceptionOrNull()
            }
            if (error == null) {
                toastOnUi("已保存到书源列表：${source.bookSourceName}")
                finish()
            } else {
                binding.btnSave.alpha = 1f
                toastOnUi("保存失败：${error.message}")
            }
        }
    }

    companion object {
        /** 直接进入本界面的入口（供设置页跳转） */
        val startActivityIntent get() = AiBookSourceActivity::class.java
    }
}
