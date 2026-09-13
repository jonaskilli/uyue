package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityRoleChatBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.AiChatManager
import io.legado.app.model.AiImageGenerator
import io.legado.app.model.AiProfileService
import io.legado.app.model.CharacterProfileManager
import io.legado.app.model.CharacterVoiceBinding
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.RoleVoiceSpeaker
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * 「与角色对话」galgame 式界面。
 *
 * 视觉小说式布局：上方大立绘（无图时显示角色首字占位）、底部台词框只显示
 * AI 的流式回复（打字机逐字输出），用户输入不显示在界面上，仅作为对话
 * 上下文；台词框上方「📜 历史」可查看完整会话记录。
 *
 * 接口配置与「AI处理章节」同格式（接口@@模型@@密钥，可多组轮换），
 * 在「⚙ 接口」弹窗里填写；回复完成可用角色绑定的发音人朗读。
 *
 * 生命周期：进入本页暂停朗读、退出自动恢复（只恢复本页暂停的那一次）。
 */
class RoleChatActivity : BaseActivity<ActivityRoleChatBinding>() {

    override val binding by viewBinding(ActivityRoleChatBinding::inflate)

    private val bookName by lazy { intent.getStringExtra(EXTRA_BOOK) ?: "默认" }
    private val characterName by lazy { intent.getStringExtra(EXTRA_CHARACTER) ?: "" }

    /** 角色绑定的发音人 tag（空 = 未绑定，不朗读） */
    private var voiceTag: String = ""

    /** 角色性别 / 年龄（用于占位与档案提示），格式如 "男" / "青年" */
    private var gender: String = ""
    private var age: String = ""

    /** 会话历史（含 user 与 assistant，不含 system） */
    private val history = mutableListOf<AiChatManager.ChatMessage>()

    /** 流式接收缓冲：网络线程追加，打字机协程按节奏消费 */
    private val received = StringBuilder()
    private var printed = 0

    private var generating = false
    private var streamJob: Job? = null
    private var printerJob: Job? = null

    /** 当前立绘位图（整图，MATRIX 顶格自适应显示） */
    private var portraitBitmap: Bitmap? = null

    /** 键盘可视区监听（见 [setupKeyboardSync]） */
    private var keyboardListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        initData()
        initView()
        bindEvent()
        setupKeyboardSync()
    }

    /**
     * 全屏窗口下的边距适配：
     * 1. 顶部工具条避开状态栏（全屏模式下布局从 y=0 开始，会与状态栏重叠）；
     * 2. 键盘弹出时整体上移（adjustResize 在全屏窗口下不生效）。
     * 两者统一在可视区监听里维护根布局 padding。
     */
    private fun setupKeyboardSync() {
        val root = binding.root
        val statusBarHeight = runCatching {
            val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) resources.getDimensionPixelSize(resId) else 0
        }.getOrDefault(0)
        keyboardListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(rect)
            val screenHeight = root.rootView.height
            val keypadHeight = (screenHeight - rect.bottom).coerceAtLeast(0)
            val bottomPad = if (keypadHeight > screenHeight * 0.15) keypadHeight else 0
            root.setPadding(0, statusBarHeight, 0, bottomPad)
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(keyboardListener)
    }

    private fun initData() {
        history.addAll(AiChatManager.loadHistory(bookName, characterName))
        val binding2 = CharacterVoiceBinding.getGenderAge(bookName, characterName)
        gender = binding2.first
        age = binding2.second
        voiceTag = CharacterVoiceBinding.getVoice(bookName, characterName)
    }

    @SuppressLint("SetTextI18n")
    private fun initView() {
        binding.tvTitle.text = if (bookName.isBlank() || bookName == "默认") {
            characterName
        } else "$characterName · 《$bookName》"
        binding.tvRoleName.text = characterName
        binding.tvPlaceholder.text = characterName.firstOrNull()?.toString() ?: "?"
        binding.tvDialogue.movementMethod = ScrollingMovementMethod()
        updateVoiceLabel()
        // 立绘区尺寸变化（输入法弹出/收起顶动布局）时重算顶格矩阵
        binding.ivPortrait.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPortraitMatrix()
        }
        loadPortrait()
        // 空会话给一句开场引导；有历史时默认回显上一次 AI 的回复
        if (history.isEmpty()) {
            showText("（轻声）来了呀…想聊点什么？")
            printed = 0
        } else {
            val lastReply = history.lastOrNull { it.role == "assistant" }?.content
            showText(lastReply?.takeIf { it.isNotBlank() } ?: "（轻声）来了呀…想聊点什么？")
            printed = 0
        }
    }

    private fun bindEvent() {
        binding.tvBack.setOnClickListener { finish() }
        binding.tvSend.setOnClickListener { sendInput() }
        binding.tvHistory.setOnClickListener {
            RoleChatHistoryDialog.newInstance(bookName, characterName).apply {
                // 弹窗里清空历史后，同步清掉本页内存中的会话并恢复开场白
                onCleared = {
                    history.clear()
                    showText("（轻声）来了呀…想聊点什么？")
                    printed = 0
                }
            }.show(supportFragmentManager, "roleChatHistory")
        }
        binding.tvApi.setOnClickListener {
            AiChatApiDialog().show(supportFragmentManager, "aiChatApiDialog")
        }
        binding.tvVoice.setOnClickListener {
            AppConfig.roleChatVoiceEnabled = !AppConfig.roleChatVoiceEnabled
            updateVoiceLabel()
            if (!AppConfig.roleChatVoiceEnabled) RoleVoiceSpeaker.release()
        }
    }

    private fun updateVoiceLabel() {
        val on = AppConfig.roleChatVoiceEnabled
        binding.tvVoice.text = if (on) "🔊 开" else "🔇 关"
        binding.tvVoice.alpha = if (on) 1f else 0.55f
    }

    // ------------------------------------------------------------------
    // 发送与流式打字机
    // ------------------------------------------------------------------

    private fun sendInput() {
        if (generating) {
            toastOnUi("角色正在说话，请稍候")
            return
        }
        val text = binding.etInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (!AiChatManager.hasApiConfig()) {
            toastOnUi("请先在「⚙ 接口」里填写 API 配置")
            AiChatApiDialog().show(supportFragmentManager, "aiChatApiDialog")
            return
        }
        binding.etInput.setText("")

        // 用户输入只进会话上下文，不上屏（galgame 式：界面上只有角色的话）
        history.add(AiChatManager.ChatMessage("user", text))
        AiChatManager.saveHistory(bookName, characterName, history)

        startStream()
    }

    @SuppressLint("SetTextI18n")
    private fun startStream() {
        generating = true
        received.clear()
        printed = 0
        binding.tvSend.alpha = 0.4f
        binding.tvRoleName.text = "$characterName（说话中…）"
        showText("…")

        // 档案（A 功能）存在时注入完整人设，否则退化为通用扮演指令
        val profileSummary = CharacterProfileManager.getProfile(bookName, characterName)?.summary()
        val persona = AiChatManager.buildPersonaPrompt(
            bookName, characterName, gender, age, profileSummary
        )
        val request = AiChatManager.buildRequestMessages(
            persona, history.dropLast(1), history.last().content
        )

        streamJob = lifecycleScope.launch {
            // 打字机：网络增量写入 received，本协程按 ~30ms/字 消费，观感即逐字输出
            printerJob = launch(Dispatchers.Main) { printerLoop() }
            val result = AiChatManager.chatStream(request, object : AiChatManager.ChatCallback {
                override fun onDelta(delta: String) {
                    synchronized(received) { received.append(delta) }
                }

                override fun onDone(fullText: String) {}

                override fun onError(message: String) {
                    synchronized(received) {
                        // 中断时保留已收到的一半话，避免整段消失
                        received.append("\n\n（对话中断：").append(message).append("）")
                    }
                }
            })
            // 等打字机把缓冲打完（最多再等 8 秒，超时直接整段显示）
            var wait = 0
            while (printed < received.length && wait < 8000) {
                delay(100); wait += 100
            }
            result.onSuccess { full ->
                history.add(AiChatManager.ChatMessage("assistant", full))
                AiChatManager.saveHistory(bookName, characterName, history)
                speakReply(full)
            }
            finishStream()
        }
    }

    /** 打字机协程：逐字符上屏，尾部带闪烁光标 */
    private suspend fun printerLoop() {
        var cursorOn = true
        while (true) {
            val sb = StringBuilder()
            val shown: String
            synchronized(received) {
                val end = minOf(printed + 1, received.length)
                if (printed < received.length) {
                    sb.append(received.substring(printed, end))
                    printed = end
                }
                shown = received.substring(0, printed)
            }
            if (sb.isNotEmpty()) {
                val cursor = if (generating && cursorOn) "▌" else ""
                showText(shown + cursor)
                cursorOn = !cursorOn
                delay(28)
            } else if (generating) {
                val cursor = if (cursorOn) "▌" else ""
                showText(shown.ifEmpty { "…" } + cursor)
                cursorOn = !cursorOn
                delay(160)
            } else {
                showText(shown)
                break
            }
        }
    }

    private fun finishStream() {
        generating = false
        binding.tvSend.alpha = 1f
        binding.tvRoleName.text = characterName
        showText(received.toString())
    }

    private fun showText(text: String) {
        binding.tvDialogue.text = text
        binding.tvDialogue.post {
            runCatching {
                val layout = binding.tvDialogue.layout ?: return@post
                val line = layout.lineCount - 1
                if (line >= 0) {
                    binding.tvDialogue.scrollTo(
                        0, layout.getLineBottom(line) - binding.tvDialogue.height
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 语音 / 立绘
    // ------------------------------------------------------------------

    private fun speakReply(text: String) {
        if (!AppConfig.roleChatVoiceEnabled || voiceTag.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            RoleVoiceSpeaker.speak(appCtx, text, voiceTag)
        }
    }

    /**
     * 加载角色立绘：与 AI 立绘功能的缓存约定一致
     * （cacheDir/ai_images/ai_avatars/avatar_<书名>_<角色名>.jpg）。
     * 显示为**从顶部裁剪的正方形**（保证头部完整）；无立绘时保持首字占位，
     * 同时后台触发建档/生成立绘，生成完成后立即刷新本页显示。
     */
    private fun loadPortrait() {
        val file = AiImageGenerator.getCachedAvatar(bookName, characterName)
        if (file == null) {
            // 无立绘：后台建档/生成立绘（异步、可并发去重），完成后自动刷新
            AiProfileService.ensureProfileAndAvatar(
                bookName, characterName,
                ReadBook.durChapterIndex,
                ReadBook.curTextChapter?.chapter?.title.orEmpty()
            )
            pollPortrait()
            return
        }
        showPortraitBitmap(file)
    }

    private fun showPortraitBitmap(file: java.io.File) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 解码整图（不预裁），由 Matrix 顶格自适应：空间够显示更多身体部分
            val bmp = AiImageGenerator.decodeAvatarFull(file, 720)
            withContext(Dispatchers.Main) {
                if (bmp != null && !isDestroyed && !isFinishing) {
                    portraitBitmap = bmp
                    binding.ivPortrait.scaleType = android.widget.ImageView.ScaleType.MATRIX
                    binding.ivPortrait.setImageBitmap(bmp)
                    // 先 VISIBLE 再算矩阵：GONE 状态下宽高为 0，提前算会
                    // early-return 残留单位矩阵（左上对齐不缩放，左右不对称）
                    binding.ivPortrait.visibility = android.view.View.VISIBLE
                    binding.tvPlaceholder.visibility = android.view.View.GONE
                    applyPortraitMatrix()
                    // 兜底：VISIBLE 后首帧 layout 完成时尺寸才真正就绪
                    binding.ivPortrait.post { applyPortraitMatrix() }
                }
            }
        }
    }

    /**
     * 立绘顶格矩阵：
     * - 宽度铺满立绘区、顶部顶格（不居中悬浮）；区域下方有空间时继续显示
     *   图片方形以下的身体部分，底部超出自然被裁；
     * - 输入法把区域压到装不下完整正方形时，按「方形边长=区域高」整体缩小
     *   （以正方形为单位缩放），保证头部方形始终完整可见。
     *
     * 注意：ImageView.onDraw 在应用 imageMatrix 前已执行
     * canvas.translate(paddingLeft, paddingTop)，即矩阵在「内容区坐标系」
     * （原点=内容区左上角）中生效。因此这里的平移**不再叠加 padding**，
     * 只需在内容区内定位；否则 padding 被算两次，图片向右下多偏 18dp，
     * 表现为左侧空白加倍、右侧贴边（左右不对称的根因）。
     */
    private fun applyPortraitMatrix() {
        val bmp = portraitBitmap ?: return
        val iv = binding.ivPortrait
        val vw = iv.width - iv.paddingLeft - iv.paddingRight
        val vh = iv.height - iv.paddingTop - iv.paddingBottom
        if (vw <= 0 || vh <= 0) return
        // 方形边长 = 图片宽；空间装得下方形就按宽度铺满，装不下就缩到区域高
        val scale = if (vh >= vw) {
            vw.toFloat() / bmp.width
        } else {
            vh.toFloat() / bmp.width
        }
        val matrix = Matrix().apply {
            setScale(scale, scale)
            // 在内容区内定位：水平居中、垂直顶格（padding 由系统画布处理）
            postTranslate(
                (vw - bmp.width * scale) / 2f,
                0f
            )
        }
        iv.imageMatrix = matrix
    }

    /** 立绘尚未生成时轮询缓存（每 5 秒，最多 5 分钟），生成完成立即刷新本页 */
    private fun pollPortrait() {
        lifecycleScope.launch(Dispatchers.IO) {
            repeat(60) {
                if (!isActive) return@launch
                delay(5000)
                val file = AiImageGenerator.getCachedAvatar(bookName, characterName)
                if (file != null) {
                    showPortraitBitmap(file)
                    return@launch
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 朗读暂停 / 恢复
    // ------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        if (BaseReadAloudService.isRun && !BaseReadAloudService.pause) {
            ReadAloud.pause(this)
            pausedReadAloud = true
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations && pausedReadAloud) {
            pausedReadAloud = false
            if (BaseReadAloudService.isRun && BaseReadAloudService.pause) {
                ReadAloud.resume(this)
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        keyboardListener?.let { binding.root.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        keyboardListener = null
        streamJob?.cancel()
        printerJob?.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BOOK = "bookName"
        const val EXTRA_CHARACTER = "characterName"

        /**
         * 本页是否暂停了朗读。
         *
         * 放在伴生对象（静态）而不是实例字段：配置变更会重建 Activity，
         * 实例字段会随旧实例丢失，导致退出页面时无法恢复朗读。
         */
        @Volatile
        private var pausedReadAloud = false

        fun start(activity: android.app.Activity, bookName: String, characterName: String) {
            activity.startActivity(
                android.content.Intent(activity, RoleChatActivity::class.java)
                    .putExtra(EXTRA_BOOK, bookName)
                    .putExtra(EXTRA_CHARACTER, characterName)
            )
        }
    }
}
