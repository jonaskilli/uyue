package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiChapterFixTemplate
import io.legado.app.databinding.DialogAiChapterFixBinding
import io.legado.app.model.ReadBook
import io.legado.app.service.AiChapterFixService
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * 「AI处理章节」对话框（模板化）。
 *
 * 对齐 AI 生图 [AiImageSettingsDialog] 的模板状态管理：
 * - 「当前编辑模板」独立持久化在 [PreferKey.aiChapterFixLastEditTemplateId]，
 *   顶部模板名、输入框、保存目标全部由 [applyTemplateToUi] 这一个入口同步；
 * - 「当前书绑定模板」只决定后台自动处理用哪个模板，绑定变化不会篡改编辑目标；
 * - 保存按钮在点击瞬间固定目标模板 id，保存成功后才关闭，
 *   不再有「输入即保存 / 关闭兜底保存」与异步回显互相覆盖的问题。
 *
 * 多组接口轮换格式：接口@@模型@@密钥@@接口@@模型@@密钥@@...（3 段一组，可无限追加）。
 * 点「处理当前章节」/「处理下一章」会先保存当前编辑模板，再用保存后的模板执行处理。
 */
class AiChapterFixDialog : BaseDialogFragment(R.layout.dialog_ai_chapter_fix) {

    private val binding by viewBinding(DialogAiChapterFixBinding::bind)

    /** 当前编辑的模板：显示名、输入框、保存目标、处理目标的唯一来源 */
    private var currentTemplate: AiChapterFixTemplate? = null

    private var bookName: String = ""
    private var bookUrl: String = ""
    private var hasBook: Boolean = false

    /** 模板加载请求序号，避免异步回显晚到覆盖用户刚选中的模板 */
    private var loadVersion = 0
    private var hintVersion = 0

    /** 点处理按钮后回调：(已保存的模板, 是否从当前章开始, 是否忽略已完成记录) */
    var onConfirm: ((template: AiChapterFixTemplate, fromCurrent: Boolean, force: Boolean) -> Unit)? =
        null

    /** 自动处理开关变化回调（按书生效，用于同步工具栏图标状态） */
    var onAutoChangedCallback: ((Boolean) -> Unit)? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        ReadBook.book?.let {
            bookName = it.name
            bookUrl = it.bookUrl
            hasBook = true
        }
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnCurrent.setOnClickListener { confirm(fromCurrent = true) }
        binding.btnNext.setOnClickListener { confirm(fromCurrent = false) }
        // 右上角保存：对齐 AI 生图的显式保存按钮，写入当前编辑模板后关闭
        binding.btnSave.setOnClickListener { saveAndThen(null) }
        binding.btnTemplateList.setOnClickListener { showTemplateList() }
        initAutoSwitch()
        // 用 FragmentResult 传递模板变化，避免 Fragment 重建后 lambda 回调丢失
        childFragmentManager.setFragmentResultListener(
            AiChapterFixTemplateListDialog.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle -> handleTemplateResult(bundle) }
        loadInitialTemplate()
        refreshBindingHint()
    }

    /** 自动处理开关只读写当前书的 key，绝不写旧版全局开关 */
    private fun initAutoSwitch() {
        binding.switchAuto.isEnabled = hasBook
        binding.switchAuto.setOnCheckedChangeListener(null)
        binding.switchAuto.isChecked = if (hasBook) {
            AiChapterFixService.isAutoFixEnabled(bookName, bookUrl)
        } else {
            false
        }
        binding.switchAuto.setOnCheckedChangeListener { _, isChecked ->
            AiChapterFixService.setAutoFixEnabled(bookName, bookUrl, isChecked)
            onAutoChangedCallback?.invoke(isChecked)
        }
    }

    /** 打开时优先恢复上次编辑的模板；id 失效才回退到当前书生效模板 */
    private fun loadInitialTemplate() {
        val request = ++loadVersion
        lifecycleScope.launch(Dispatchers.IO) {
            val lastId = appCtx
                .getPrefString(PreferKey.aiChapterFixLastEditTemplateId, null)
                ?.toLongOrNull()
            val template = lastId?.let { appDb.aiChapterFixTemplateDao.getById(it) }
                ?: AiChapterFixService.getOrCreateEffectiveTemplate(bookName, bookUrl)
            withContext(Dispatchers.Main) {
                if (request != loadVersion || !isAdded || view == null) return@withContext
                applyTemplateToUi(template)
            }
        }
    }

    /**
     * 唯一的状态同步入口（对齐 AI 生图 applyTemplateToUi）：
     * 同时更新当前模板、顶部名称、全部输入框和「上次编辑模板 id」。
     */
    private fun applyTemplateToUi(template: AiChapterFixTemplate) {
        currentTemplate = template
        binding.tvTemplateName.text = buildString {
            append("编辑模板：")
            append(template.name)
            if (template.isDefault) append("（默认）")
        }
        binding.etApi.setText(template.apiConfig)
        // 提示词留空表示使用内置默认，回显时不再擅自把默认提示词写进模板
        binding.etPrompt.setText(template.prompt)
        binding.etCount.setText(template.chapterCount.toString())
        binding.etConcurrency.setText(template.concurrency.toString())
        binding.etMaxLength.setText(template.maxLength.toString())
        binding.etRetryCount.setText(template.retryCount.toString())
        binding.etContextLength.setText(template.contextLength.toString())
        binding.etTimeout.setText(template.timeoutSeconds.toString())
        appCtx.putPrefString(
            PreferKey.aiChapterFixLastEditTemplateId,
            template.id.toString()
        )
        refreshBindingHint()
    }

    /** 只刷新「自动处理实际使用哪个模板」的提示，不改编辑目标 */
    private fun refreshBindingHint() {
        if (!hasBook) {
            binding.tvBindingHint.text = "未打开书籍；自动处理开关不生效"
            return
        }
        val request = ++hintVersion
        lifecycleScope.launch(Dispatchers.IO) {
            val effective = AiChapterFixService.getOrCreateEffectiveTemplate(bookName, bookUrl)
            val boundId = AiChapterFixService.getBoundTemplateId(bookName, bookUrl)
            val hint = if (boundId > 0) {
                "自动处理使用：${effective.name}（本书已绑定）"
            } else {
                "自动处理使用默认模板：${effective.name}（未绑定）"
            }
            withContext(Dispatchers.Main) {
                if (request != hintVersion || !isAdded || view == null) return@withContext
                binding.tvBindingHint.text = hint
            }
        }
    }

    private fun showTemplateList() {
        AiChapterFixTemplateListDialog.newInstance(
            currentEditId = currentTemplate?.id ?: 0L,
            bookName = bookName,
            bookUrl = bookUrl
        ).show(childFragmentManager, AiChapterFixTemplateListDialog.TAG)
    }

    private fun handleTemplateResult(bundle: Bundle) {
        val id = bundle.getLong(AiChapterFixTemplateListDialog.RESULT_TEMPLATE_ID, 0L)
        when (bundle.getString(AiChapterFixTemplateListDialog.RESULT_ACTION)) {
            AiChapterFixTemplateListDialog.ACTION_SELECT ->
                loadTemplate(id, applyAlways = true)

            AiChapterFixTemplateListDialog.ACTION_RENAMED ->
                loadTemplate(id, applyOnlyIfCurrent = true)

            AiChapterFixTemplateListDialog.ACTION_DELETED -> onTemplateDeleted(id)

            AiChapterFixTemplateListDialog.ACTION_BIND_CHANGED -> refreshBindingHint()
        }
    }

    private fun loadTemplate(
        id: Long,
        applyAlways: Boolean = false,
        applyOnlyIfCurrent: Boolean = false
    ) {
        val request = ++loadVersion
        lifecycleScope.launch(Dispatchers.IO) {
            val template = appDb.aiChapterFixTemplateDao.getById(id)
                ?: AiChapterFixService.getOrCreateEffectiveTemplate(bookName, bookUrl)
            withContext(Dispatchers.Main) {
                if (request != loadVersion || !isAdded || view == null) return@withContext
                val shouldApply = applyAlways ||
                    (applyOnlyIfCurrent && currentTemplate?.id == id)
                if (shouldApply) {
                    applyTemplateToUi(template)
                } else {
                    refreshBindingHint()
                }
            }
        }
    }

    private fun onTemplateDeleted(id: Long) {
        if (currentTemplate?.id != id) {
            refreshBindingHint()
            return
        }
        val request = ++loadVersion
        lifecycleScope.launch(Dispatchers.IO) {
            val template = AiChapterFixService.getOrCreateEffectiveTemplate(bookName, bookUrl)
            withContext(Dispatchers.Main) {
                if (request != loadVersion || !isAdded || view == null) return@withContext
                applyTemplateToUi(template)
                toastOnUi("当前编辑模板已删除，已切换到生效模板")
            }
        }
    }

    /**
     * 显式保存：点击瞬间固定 [currentTemplate] 的 id，
     * 用输入框内容构造 updated，再以数据库最新记录为基准落库（保留重命名/默认标记/排序）。
     */
    private fun saveAndThen(afterSave: ((AiChapterFixTemplate) -> Unit)?) {
        val template = currentTemplate ?: run {
            toastOnUi("模板尚未加载完成，请稍后再保存")
            return
        }
        binding.etApi.error = null
        val api = binding.etApi.text?.toString()?.trim().orEmpty()
        if (api.isEmpty()) {
            binding.etApi.error = "请填写接口地址"
            return
        }
        val updated = template.copy(
            apiConfig = api,
            prompt = binding.etPrompt.text?.toString()?.trim().orEmpty(),
            chapterCount = binding.etCount.text?.toString()?.trim()?.toIntOrNull()
                ?.coerceIn(1, AiChapterFixService.MAX_COUNT) ?: template.chapterCount,
            concurrency = binding.etConcurrency.text?.toString()?.trim()?.toIntOrNull()
                ?.coerceIn(1, AiChapterFixService.MAX_CONCURRENCY) ?: template.concurrency,
            maxLength = binding.etMaxLength.text?.toString()?.trim()?.toIntOrNull()
                ?.coerceAtLeast(0) ?: template.maxLength,
            retryCount = binding.etRetryCount.text?.toString()?.trim()?.toIntOrNull()
                ?.coerceAtLeast(0) ?: template.retryCount,
            contextLength = binding.etContextLength.text?.toString()?.trim()?.toIntOrNull()
                ?.coerceAtLeast(0) ?: template.contextLength,
            timeoutSeconds = binding.etTimeout.text?.toString()?.trim()?.toIntOrNull()
                ?.coerceAtLeast(10) ?: template.timeoutSeconds,
            lastUpdateTime = System.currentTimeMillis()
        )
        currentTemplate = updated
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = appDb.aiChapterFixTemplateDao
            // 模板可能刚被重命名/删除：以库里最新记录为基准，避免保存时把名字改回去或复活已删模板
            val base = dao.getById(updated.id)
                ?: AiChapterFixService.getOrCreateDefaultTemplate()
            val toSave = base.copy(
                apiConfig = updated.apiConfig,
                prompt = updated.prompt,
                chapterCount = updated.chapterCount,
                concurrency = updated.concurrency,
                maxLength = updated.maxLength,
                retryCount = updated.retryCount,
                timeoutSeconds = updated.timeoutSeconds,
                contextLength = updated.contextLength,
                lastUpdateTime = updated.lastUpdateTime
            )
            dao.insert(toSave)
            withContext(Dispatchers.Main) {
                if (!isAdded || view == null) return@withContext
                // 用保存后的对象再回显一次，保证界面内容与落库内容完全一致
                applyTemplateToUi(toSave)
                toastOnUi("模板「${toSave.name}」已保存")
                afterSave?.invoke(toSave)
                dismissAllowingStateLoss()
            }
        }
    }

    private fun confirm(fromCurrent: Boolean) {
        val force = binding.switchForce.isChecked
        saveAndThen { saved ->
            onConfirm?.invoke(saved, fromCurrent, force)
        }
    }
}
