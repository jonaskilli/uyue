package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogBgmConfigBinding
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
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
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
            
            // 【修改】居中弹窗宽度不建议 MATCH_PARENT，改为屏幕宽度的 90%
            setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
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
        tvSoundEffectVolLabel.setTextColor(textColor)
        tvSoundEffectVolumeValue.setTextColor(textColor)
        tvMixBgmOnMerge.setTextColor(textColor)
        tvBgmMinSegmentLabel.setTextColor(textColor)
        tvMixBgmOnMerge.setTextColor(textColor)
        tvBgmMinSegmentLabel.setTextColor(textColor)
        tvDanmakuEnable.setTextColor(textColor)
        switchBgm.setTextColor(textColor)
        tvAiImageEnable.setTextColor(textColor)
        tvAiImageSave.setTextColor(textColor)
        tvAnalysisStatus.setTextColor(textColor)
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

        // AI 弹幕：总开关 + 每章条数
        switchDanmakuEnable.isChecked = AppConfig.danmakuEnabled
        etDanmakuCount.setText(AppConfig.danmakuCount.toString())
    }

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

        // 每章弹幕条数
        etDanmakuCount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.toIntOrNull() ?: 6
                AppConfig.danmakuCount = value.coerceIn(1, 20)
            }
        })

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

        binding.rootView.findViewById<View>(R.id.btn_ai_image)?.setOnClickListener {
            AiImageSettingsDialog().show(parentFragmentManager, "aiImageSettingsDialog")
        }

        binding.rootView.findViewById<View>(R.id.btn_full_book_analysis)?.setOnClickListener {
            showFullBookAnalysisDialog()
        }

        binding.rootView.findViewById<View>(R.id.btn_stop_analysis)?.setOnClickListener {
            val intent = Intent(requireContext(), BookScriptAnalysisService::class.java).apply {
                action = IntentAction.stop
            }
            requireContext().startService(intent)
            toastOnUi("已请求停止全书分析")
        }

        binding.rootView.findViewById<View>(R.id.btn_ai_config)?.setOnClickListener {
            BgmAISettingsDialog().show(parentFragmentManager, "bgmAISettings")
        }

        binding.rootView.findViewById<View>(R.id.btn_download_bgm)?.setOnClickListener {
            BgmDownloadDialog().show(parentFragmentManager, "bgmDownload")
        }

        startAnalysisStatusPolling()
    }

    /** 实时展示全书分析进度：每 500ms 轮询服务暴露的状态（分析中才显示状态区） */
    private fun startAnalysisStatusPolling() {
        analysisStatusJob?.cancel()
        analysisStatusJob = lifecycleScope.launch {
            while (true) {
                refreshAnalysisStatus()
                delay(500)
            }
        }
    }

    private fun refreshAnalysisStatus() = binding.run {
        if (!BookScriptAnalysisService.isRun) {
            llAnalysisStatus.visibility = View.GONE
            return@run
        }
        llAnalysisStatus.visibility = View.VISIBLE
        val parts = mutableListOf<String>()
        BookScriptAnalysisService.runningBookName?.let { parts.add("《$it》") }
        BookScriptAnalysisService.currentChapterTitle?.let { parts.add("「${it.take(16)}」") }
        BookScriptAnalysisService.statusText?.let { parts.add(it) }
        tvAnalysisStatus.text = parts.joinToString(" · ").ifBlank { "分析中…" }
    }

    /**
     * 全书分析：调用朗读脚本把整本书按章节顺序做一遍角色标注/别名合并，
     * 只分析不合成音频。分析到哪个位置就把 data.json 刷成哪个位置的上下文。
     */
    private fun showFullBookAnalysisDialog() {
        val book = ReadBook.book
        if (book == null) {
            toastOnUi("未打开书籍")
            return
        }
        val items = arrayOf(
            "从当前章开始分析（第${ReadBook.durChapterIndex + 1}章）",
            "从小说开头开始分析"
        )
        // 注意：AlertDialog 的 setMessage 与 setItems 互斥（消息会顶掉选项列表），
        // 说明文字并进标题，保证两个选项可见
        AlertDialog.Builder(requireContext())
            .setTitle("全书分析 · ${book.name}（共${ReadBook.chapterSize}章，不合成音频）")
            .setItems(items) { _, which ->
                val startIndex = if (which == 0) ReadBook.durChapterIndex else 0
                startFullBookAnalysis(startIndex)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startFullBookAnalysis(startIndex: Int) {
        // 分析与朗读互斥（共用 data.json 脚本上下文）：开始分析先停止朗读，
        // 分析期间朗读启动会被拦截（ReadAloud.play 统一拦截）
        if (BaseReadAloudService.isRun) {
            ReadAloud.stop(requireContext())
            toastOnUi("已停止朗读，开始全书分析")
        } else {
            toastOnUi("全书分析已开始")
        }
        val intent = Intent(requireContext(), BookScriptAnalysisService::class.java).apply {
            putExtra(BookScriptAnalysisService.EXTRA_START_INDEX, startIndex)
        }
        requireContext().startForegroundServiceCompat(intent)
    }
}
