package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogBgmConfigBinding
import io.legado.app.databinding.DialogFullBookAnalysisBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.AiEffectIndex
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.BgmManager
import io.legado.app.service.BookScriptAnalysisService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.sceneeffect.SceneEffectManageActivity
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import android.text.Editable
import android.text.TextWatcher
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.IntentAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 背景音乐设置对话框
 * 修改点：位置居中、标题文案更新、样式优化
 */
class BgmConfigDialog : BaseDialogFragment(R.layout.dialog_bgm_config) {

    private val binding by viewBinding(DialogBgmConfigBinding::bind)

    private var analysisStatusJob: Job? = null
    private var analysisDialog: AlertDialog? = null
    private var analysisDialogBinding: DialogFullBookAnalysisBinding? = null

    // SAF 目录选择
    private val selectDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            AppConfig.bgmPath = uri.toString()
            binding.tvPath.text = uri.path?.substringAfterLast("/") ?: "已选择"
            BgmManager.loadBgmFiles()
            if (AppConfig.isBgmEnabled) {
                BgmManager.play()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            // 居中弹窗通常需要背景变暗，这里设置暗度
            val attr = attributes
            attr.dimAmount = 0.5f  
            attr.gravity = Gravity.CENTER // 【修改】从 BOTTOM 改为 CENTER
            attributes = attr
            
            // 设置透明背景，以支持 XML 中定义的圆角
            setBackgroundDrawableResource(R.color.transparent)
            
            // 宽度 90% 屏幕。高度动态取 min(内容实际高度, 屏幕高度的 85%)：
            // 内容没超过上限时弹窗贴合内容（不留白边、也不滚动）；
            // 内容超出上限（例如系统字体调大）时才固定到上限，由 ScrollView 提供滚动。
            // （摘自上游 1a5b531）
            val maxHeight = (resources.displayMetrics.heightPixels * 0.85f).toInt()
            setLayout(0.9f, maxHeight)
            // 布局完成后按内容实际高度收窄弹窗
            binding.root.post {
                if (!isAdded) return@post
                val contentHeight = binding.rootView.measuredHeight
                val finalHeight = minOf(contentHeight, maxHeight)
                dialog?.window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                    finalHeight
                )
            }

            // 内容收紧后整体再略微上移，底部四个按钮不用够着屏幕最下方点
            val offset = (24 * resources.displayMetrics.density).toInt()
            val lp = attributes
            lp.y = -offset
            attributes = lp
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        analysisStatusJob?.cancel()
        analysisDialog?.dismiss()
        analysisDialog = null
        analysisDialogBinding = null
        (activity as? ReadBookActivity)?.let {
            if (it.bottomDialog > 0) it.bottomDialog--
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        if (activity is ReadBookActivity) {
            (activity as ReadBookActivity).bottomDialog++
        }

        // 动态配色
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)

        binding.run {
            rootView.setBackgroundColor(bg)
            // 【修改】显式设置标题文字
            tvTitle.text = "背景音乐开关"
            
        switchBgm.setTextColor(textColor)
        tvTitle.setTextColor(textColor)
        tvPathLabel.setTextColor(textColor)
        tvPath.setTextColor(textColor)
        btnSelectFolder.setTextColor(textColor)
        tvVolLabel.setTextColor(textColor)
        tvVolumeValue.setTextColor(textColor)
        tvBgmVolReduce.setTextColor(textColor)
        tvBgmVolAdd.setTextColor(textColor)
        tvSoundEffectVolLabel.setTextColor(textColor)
        tvSoundEffectVolumeValue.setTextColor(textColor)
        tvSoundEffectVolReduce.setTextColor(textColor)
        tvSoundEffectVolAdd.setTextColor(textColor)
        tvMixBgmOnMerge.setTextColor(textColor)
        tvBgmMinSegmentLabel.setTextColor(textColor)
        tvMixBgmOnMerge.setTextColor(textColor)
        tvBgmMinSegmentLabel.setTextColor(textColor)
        tvDanmakuEnable.setTextColor(textColor)
        switchBgm.setTextColor(textColor)
        tvAiImageEnable.setTextColor(textColor)
        tvAiImageSave.setTextColor(textColor)
        }

        initData()
        initEvent()
    }

    private fun initData() = binding.run {
        switchBgm.isChecked = AppConfig.isBgmEnabled
        // 启用AI生图 / 保存AI图片开关：均每本书单独设置，默认关闭；无打开书籍时禁用
        val book = ReadBook.book
        val bookUrl = book?.bookUrl
        // readAloudAiImage 已改为按当前书籍存储，直接读取即可
        switchAiImageEnable.isChecked = book != null && AppConfig.readAloudAiImage
        switchAiImageEnable.isEnabled = book != null
        switchAiImageSave.isChecked = book != null && AppConfig.isAiImageSaveEnabled(book.name, bookUrl)
        switchAiImageSave.isEnabled = book != null
        tvPath.text = AppConfig.bgmPath?.substringAfterLast("/") ?: "未选择"
        seekVolume.progress = AppConfig.bgmVolume
        tvVolumeValue.text = "${AppConfig.bgmVolume}%"

        // 初始化音效替换 Spinner
        val soundEffectItems = arrayOf("关闭", "普通音效", "全部音效")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, soundEffectItems)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSoundEffect.adapter = adapter
        val mode = AppConfig.soundEffectMode
        val selectedIndex = when (mode) {
            "normal" -> 1
            "all" -> 2
            else -> 0
        }
        spinnerSoundEffect.setSelection(selectedIndex)

        // 初始化当前书籍音效 Spinner
        val bookSoundEffectItems = arrayOf("跟随", "关闭", "普通音效", "全部音效")
        val bookAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, bookSoundEffectItems)
        bookAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBookSoundEffect.adapter = bookAdapter
        val bookMode = if (bookUrl != null) AppConfig.getBookSoundEffectMode(bookUrl) else "follow"
        val bookSelectedIndex = when (bookMode) {
            "off" -> 1
            "normal" -> 2
            "all" -> 3
            else -> 0
        }
        spinnerBookSoundEffect.setSelection(bookSelectedIndex)
        // 无打开书籍时禁用
        spinnerBookSoundEffect.isEnabled = bookUrl != null

        // 初始化音效偏移字数
        etSoundEffectOffset.setText(AppConfig.soundEffectOffsetChars.toString())

        // 初始化音效音量
        seekSoundEffectVolume.progress = AppConfig.soundEffectVolume
        tvSoundEffectVolumeValue.text = "${AppConfig.soundEffectVolume}%"

        // 初始化「导出时混入背景音乐」：音频与视频共用，音量取朗读播放的 bgmVolume
        switchMixBgmOnMerge.isChecked = AppConfig.mixBgmOnMerge
        etBgmMinSegment.setText(AppConfig.bgmMinSegmentMs.toString())

        // AI 音效总开关
        switchAiEffectEnable.isChecked = AppConfig.aiEffectEnabled

        // AI 弹幕：总开关（条数固定，不再提供设置）
        switchDanmakuEnable.isChecked = AppConfig.danmakuEnabled

        // 场景特效：总开关（本地关键词规则为主，AI 为可选增强）
        switchSceneEffectEnable.isChecked = AppConfig.sceneEffectEnabled
    }

    /**
     * 点加减调音量：当前值不是整五时，第一次只吸附到 5 的倍数
     * （减向下取整、加向上取整），之后再按 5% 步进；与朗读界面语速加减的吸附规则一致。
     */
    private fun stepVolume(seekBar: SeekBar, delta: Int): Int {
        val cur = seekBar.progress
        val v = if (cur % 5 == 0) {
            (cur + delta).coerceIn(0, seekBar.max)
        } else if (delta > 0) {
            ((cur + 4) / 5 * 5).coerceIn(0, seekBar.max)
        } else {
            (cur / 5 * 5).coerceIn(0, seekBar.max)
        }
        seekBar.progress = v
        return v    }

    private fun initEvent() = binding.run {
        switchBgm.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.isBgmEnabled = isChecked
            if (isChecked) {
                // 总开关打开时，先重新扫描文件夹并刷新“背景音乐的名字.txt”，再播放
                lifecycleScope.launch(Dispatchers.IO) {
                    BgmManager.refreshBgmNamesFromFolder()
                    withContext(Dispatchers.Main) {
                        BgmManager.play()
                    }
                }
            } else {
                BgmManager.pause()
            }
        }

        // 启用AI生图开关：仅对当前书生效（readAloudAiImage 已按书籍存储）
        switchAiImageEnable.setOnCheckedChangeListener { _, isChecked ->
            if (ReadBook.book?.bookUrl.isNullOrBlank()) return@setOnCheckedChangeListener
            AppConfig.readAloudAiImage = isChecked
        }

        // 保存AI图片开关：仅对当前书生效
        switchAiImageSave.setOnCheckedChangeListener { _, isChecked ->
            val book = ReadBook.book ?: return@setOnCheckedChangeListener
            AppConfig.setAiImageSaveEnabled(book.name, book.bookUrl, isChecked)
        }

        spinnerSoundEffect.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val mode = when (position) {
                    1 -> "normal"
                    2 -> "all"
                    else -> "off"
                }
                AppConfig.soundEffectMode = mode
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        spinnerBookSoundEffect.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val bookUrl = ReadBook.book?.bookUrl ?: return
                val mode = when (position) {
                    1 -> "off"
                    2 -> "normal"
                    3 -> "all"
                    else -> "follow"
                }
                AppConfig.setBookSoundEffectMode(bookUrl, mode)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        etSoundEffectOffset.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.toIntOrNull() ?: 5
                AppConfig.soundEffectOffsetChars = value.coerceAtLeast(0)
            }
        })

        seekSoundEffectVolume.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    AppConfig.soundEffectVolume = progress
                    tvSoundEffectVolumeValue.text = "$progress%"
                }
            }
        })

        // 回设 progress 触发的是 fromUser=false，上面的监听不会生效，
        // 所以加减按钮里要显式写配置并刷新百分比
        tvSoundEffectVolReduce.setOnClickListener {
            val v = stepVolume(seekSoundEffectVolume, -5)
            AppConfig.soundEffectVolume = v
            tvSoundEffectVolumeValue.text = "$v%"
        }
        tvSoundEffectVolAdd.setOnClickListener {
            val v = stepVolume(seekSoundEffectVolume, 5)
            AppConfig.soundEffectVolume = v
            tvSoundEffectVolumeValue.text = "$v%"
        }

        // 导出（合并音频 / 合成视频）时混入背景音乐
        switchMixBgmOnMerge.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.mixBgmOnMerge = isChecked
        }

        // AI 音效：朗读时用 AI 从广播剧音效库选音效。
        // 打开时先把索引加载好，避免第一次朗读才解析造成卡顿。
        switchAiEffectEnable.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.aiEffectEnabled = isChecked
            if (isChecked) {
                lifecycleScope.launch(Dispatchers.IO) {
                    AiEffectIndex.ensureLoaded()
                }
            }
        }

        // AI 弹幕总开关：朗读运行中打开时，立即为当前章生成一次弹幕
        switchDanmakuEnable.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.danmakuEnabled = isChecked
            if (isChecked && BaseReadAloudService.isRun) {
                BaseReadAloudService.instance?.triggerDanmakuGenerationManual()
            }
        }

        // 场景特效总开关：与弹幕共用区间调度，打开时立即为当前章排一次区间
        switchSceneEffectEnable.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.sceneEffectEnabled = isChecked
            if (isChecked && BaseReadAloudService.isRun) {
                BaseReadAloudService.instance?.triggerDanmakuGenerationManual()
            }
            postEvent(EventBus.READ_ALOUD_CONFIG_CHANGED, true)
        }

        // 屏幕特效管理：逐条试播与启用/禁用
        tvManageSceneEffect.setOnClickListener {
            startActivity(
                Intent(requireContext(), SceneEffectManageActivity::class.java)
            )
        }

        // 单首最少持续时长：铺够该时长后才允许切下一首，避免音乐过碎
        etBgmMinSegment.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                AppConfig.bgmMinSegmentMs =
                    s?.toString()?.toLongOrNull()?.coerceAtLeast(0L) ?: 60000L
            }
        })

        btnSelectFolder.setOnClickListener {
            selectDir.launch { mode = HandleFileContract.DIR }
        }

        seekVolume.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    BgmManager.setVolume(progress)
                    tvVolumeValue.text = "$progress%"
                }
            }
        })

        tvBgmVolReduce.setOnClickListener {
            val v = stepVolume(seekVolume, -5)
            BgmManager.setVolume(v)
            tvVolumeValue.text = "$v%"
        }
        tvBgmVolAdd.setOnClickListener {
            val v = stepVolume(seekVolume, 5)
            BgmManager.setVolume(v)
            tvVolumeValue.text = "$v%"
        }
        binding.rootView.findViewById<View>(R.id.btn_ai_image)?.setOnClickListener {
            AiImageSettingsDialog().show(parentFragmentManager, "aiImageSettingsDialog")
        }

        binding.rootView.findViewById<View>(R.id.btn_full_book_analysis)?.setOnClickListener {
            showFullBookAnalysisDialog()
        }

        binding.rootView.findViewById<View>(R.id.btn_ai_config)?.setOnClickListener {
            BgmAISettingsDialog().show(parentFragmentManager, "bgmAISettings")
        }

        binding.rootView.findViewById<View>(R.id.btn_download_bgm)?.setOnClickListener {
            BgmDownloadDialog().show(parentFragmentManager, "bgmDownload")
        }

        startAnalysisStatusPolling()
    }

    /** 每 500ms 刷新「全书分析」弹窗里的状态与进度 */
    private fun startAnalysisStatusPolling() {
        ReadBook.book?.let { book ->
            lifecycleScope.launch(Dispatchers.IO) {
                BookScriptAnalysisService.refreshAnalyzedCount(book)
                withContext(Dispatchers.Main) {
                    refreshAnalysisProgress()
                    refreshAnalysisStatus()
                }
            }
        }
        analysisStatusJob?.cancel()
        analysisStatusJob = lifecycleScope.launch {
            while (true) {
                refreshAnalysisStatus()
                delay(500)
            }
        }
    }

    /** 右下角按钮：分析运行中显示「后台运行」，避免与「停止」混淆；未运行时仍为「取消」 */
    private fun updateAnalysisNegativeButton(running: Boolean) {
        val button = analysisDialog?.getButton(DialogInterface.BUTTON_NEGATIVE) ?: return
        val text = if (running) "后台运行" else "取消"
        if (button.text?.toString() != text) {
            button.text = text
        }
    }

    private fun refreshAnalysisStatus() {
        val dialogBinding = analysisDialogBinding ?: return
        refreshAnalysisProgress()
        val running = BookScriptAnalysisService.isRun
        updateAnalysisNegativeButton(running)
        if (!running) {
            dialogBinding.llAnalysisStatus.visibility = View.GONE
            return
        }
        dialogBinding.llAnalysisStatus.visibility = View.VISIBLE
        val bookName = BookScriptAnalysisService.runningBookName ?: "未知"
        val chapterTitle = BookScriptAnalysisService.currentChapterTitle?.take(16)
        dialogBinding.tvAnalysisBook.text = "书名：《$bookName》"
        dialogBinding.tvAnalysisChapter.text = if (!chapterTitle.isNullOrBlank()) {
            "「$chapterTitle」"
        } else {
            "第${BookScriptAnalysisService.currentChapterIndex}章"
        }
        val p = BookScriptAnalysisService.currentParagraphIndex
        val pTotal = BookScriptAnalysisService.currentParagraphTotal
        dialogBinding.tvAnalysisParagraph.text = if (pTotal > 0) {
            "段落进度：第 $p / $pTotal 段"
        } else {
            "段落进度：准备中"
        }
    }

    /** 粗略进度：当前书已落盘的章节缓存文件数 / 本书总章数 */
    private fun refreshAnalysisProgress() {
        val dialogBinding = analysisDialogBinding ?: return
        val total = BookScriptAnalysisService.analyzedTotalCount
        val max = total.coerceAtLeast(1)
        val analyzed = BookScriptAnalysisService.analyzedChapterCount.coerceIn(0, max)
        dialogBinding.pbAnalysisProgress.max = max
        dialogBinding.pbAnalysisProgress.progress = analyzed
        val percent = if (total > 0) analyzed * 100 / total else 0
        dialogBinding.tvAnalysisProgress.text = if (total > 0) {
            "已分析 $analyzed / 共 $total 章（$percent%）"
        } else {
            "已分析 0 / 共 0 章（0%）"
        }
    }

    /** 全书分析选项弹窗：三个选项下面直接显示进度、状态与停止按钮 */
    private fun showFullBookAnalysisDialog() {
        val book = ReadBook.book
        if (book == null) {
            toastOnUi("未打开书籍")
            return
        }
        analysisDialog?.dismiss()
        val dialogBinding = DialogFullBookAnalysisBinding.inflate(layoutInflater)
        dialogBinding.tvAnalysisFromCurrent.text =
            "从当前章开始分析（第${ReadBook.durChapterIndex + 1}章）"
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("全书分析 · ${book.name}（共${ReadBook.chapterSize}章，不合成音频）")
            .setView(dialogBinding.root)
            .setNegativeButton("取消", null)
            .create()

        dialogBinding.tvAnalysisFromCurrent.setOnClickListener {
            startFullBookAnalysis(ReadBook.durChapterIndex)
        }
        dialogBinding.tvAnalysisFromStart.setOnClickListener {
            startFullBookAnalysis(0)
        }
        dialogBinding.tvAnalysisScript.setOnClickListener {
            startActivity(BookAnalysisScriptEditActivity.startIntent(requireContext()))
        }
        dialogBinding.btnStopAnalysis.setOnClickListener {
            val intent = Intent(requireContext(), BookScriptAnalysisService::class.java).apply {
                action = IntentAction.stop
            }
            requireContext().startService(intent)
            toastOnUi("已请求停止全书分析")
        }

        analysisDialog = dialog
        analysisDialogBinding = dialogBinding
        dialog.setOnDismissListener {
            analysisDialogBinding = null
            analysisDialog = null
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        refreshAnalysisProgress()
        refreshAnalysisStatus()
        ReadBook.book?.let { currentBook ->
            lifecycleScope.launch(Dispatchers.IO) {
                BookScriptAnalysisService.refreshAnalyzedCount(currentBook)
                withContext(Dispatchers.Main) {
                    refreshAnalysisProgress()
                    refreshAnalysisStatus()
                }
            }
        }
    }

    private fun startFullBookAnalysis(startIndex: Int) {
        // 全书分析已改用专用脚本，不再读写 data.json / 进度指针等朗读运行时文件，
        // 因此不再停止朗读，也不再由 ReadAloud.play 拦截。
        // 弹窗启动后不再关闭，防止用户重复点击重复发起分析
        if (BookScriptAnalysisService.isRun) {
            toastOnUi("全书分析正在进行中")
            return
        }
        toastOnUi("全书分析已开始")
        val intent = Intent(requireContext(), BookScriptAnalysisService::class.java).apply {
            putExtra(BookScriptAnalysisService.EXTRA_START_INDEX, startIndex)
        }
        requireContext().startForegroundServiceCompat(intent)
    }
}
