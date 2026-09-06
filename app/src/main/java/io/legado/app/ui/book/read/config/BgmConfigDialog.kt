package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.SeekBar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogBgmConfigBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.service.AiEffectIndex
import io.legado.app.service.BgmManager
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import android.text.Editable
import android.text.TextWatcher
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 背景音乐设置对话框
 * 修改点：位置居中、标题文案更新、样式优化
 */
class BgmConfigDialog : BaseDialogFragment(R.layout.dialog_bgm_config) {

    private val binding by viewBinding(DialogBgmConfigBinding::bind)

    // SAF 目录选择
    private val selectDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            AppConfig.bgmPath = uri.toString()
            binding.tvPath.text = uri.path?.substringAfterLast("/") ?: "已选择"
            BgmManager.loadBgmFiles()
            if (AppConfig.isBgmEnabled) {
                BgmManager.play()
                upPlayState()
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
            tvTitle.text = "背景音乐设置" 
            
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
        ivPrev.setColorFilter(textColor)
        ivPlayPause.setColorFilter(textColor)
        ivNext.setColorFilter(textColor)
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
        val bookUrl = ReadBook.book?.bookUrl
        // readAloudAiImage 已改为按当前书籍存储，直接读取即可
        switchAiImageEnable.isChecked = bookUrl != null && AppConfig.readAloudAiImage
        switchAiImageEnable.isEnabled = bookUrl != null
        switchAiImageSave.isChecked = bookUrl != null && AppConfig.isAiImageSaveEnabled(bookUrl)
        switchAiImageSave.isEnabled = bookUrl != null
        tvPath.text = AppConfig.bgmPath?.substringAfterLast("/") ?: "未选择"
        seekVolume.progress = AppConfig.bgmVolume
        tvVolumeValue.text = "${AppConfig.bgmVolume}%"
        upPlayState()

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
                        upPlayState()
                    }
                }
            } else {
                BgmManager.pause()
                upPlayState()
            }
        }

        // 启用AI生图开关：仅对当前书生效（readAloudAiImage 已按书籍存储）
        switchAiImageEnable.setOnCheckedChangeListener { _, isChecked ->
            if (ReadBook.book?.bookUrl.isNullOrBlank()) return@setOnCheckedChangeListener
            AppConfig.readAloudAiImage = isChecked
        }

        // 保存AI图片开关：仅对当前书生效
        switchAiImageSave.setOnCheckedChangeListener { _, isChecked ->
            val bookUrl = ReadBook.book?.bookUrl ?: return@setOnCheckedChangeListener
            AppConfig.setAiImageSaveEnabled(bookUrl, isChecked)
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

        ivPrev.setOnClickListener { BgmManager.prev(); upPlayState() }
        ivNext.setOnClickListener { BgmManager.next(); upPlayState() }
        ivPlayPause.setOnClickListener {
            if (BgmManager.isPlaying()) {
                BgmManager.pause()
                ivPlayPause.setImageResource(R.drawable.ic_play_24dp)
            } else {
                BgmManager.play()
                ivPlayPause.setImageResource(R.drawable.ic_pause_24dp)
            }
        }
        
        binding.rootView.findViewById<View>(R.id.btn_ai_image)?.setOnClickListener {
            AiImageSettingsDialog().show(parentFragmentManager, "aiImageSettingsDialog")
        }

        binding.rootView.findViewById<View>(R.id.btn_ai_config)?.setOnClickListener {
            BgmAISettingsDialog().show(parentFragmentManager, "bgmAISettings")
        }

        binding.rootView.findViewById<View>(R.id.btn_download_bgm)?.setOnClickListener {
            BgmDownloadDialog().show(parentFragmentManager, "bgmDownload")
        }
    }

    private fun upPlayState() {
        if (BgmManager.isPlaying()) {
            binding.ivPlayPause.setImageResource(R.drawable.ic_pause_24dp)
        } else {
            binding.ivPlayPause.setImageResource(R.drawable.ic_play_24dp)
        }
    }
}
