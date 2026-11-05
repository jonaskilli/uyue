package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewConfiguration
import kotlin.math.abs
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.TransitionDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogReadAloudBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.loadGif
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.BookCover
import io.legado.app.help.BgmMarqueeSpeed
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Color
import io.legado.app.model.AiImageGenerator
import io.legado.app.model.DanmakuItem
import io.legado.app.model.DanmakuReady
import io.legado.app.model.DanmakuStore
import io.legado.app.model.SceneEffectCue
import io.legado.app.model.SceneEffectReady
import io.legado.app.model.SceneEffectStore
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.VnParagraph
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.BgmManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.legado.app.service.HttpReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.*
import io.legado.app.ui.book.read.page.DialogRoleManager
import java.io.File
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.Timer
import java.util.TimerTask
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Window
import androidx.appcompat.app.AlertDialog

class ReadAloudDialog : BaseDialogFragment(R.layout.dialog_read_aloud) {

    companion object {
        /** 弹幕发射统一延后时长（毫秒）：AI 弹幕按段落起点触发，延后 1 秒让弹幕与朗读情节对齐 */
        const val DANMAKU_LAUNCH_DELAY_MS = 1000L

        /**
         * 手势序列超时（毫秒）。
         * 超过此间隔没有事件即视为新序列，用于 ACTION_UP 丢失后的状态自愈。
         */
        private const val GESTURE_SEQUENCE_TIMEOUT_MS = 400L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 关键：使用 dialog_style_material（标准不透明主题），而非默认的 0（会继承
        // 阅读页 Activity 的透明状态栏主题）。只有标准不透明主题下，
        // statusBarColor / FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS 才会生效，
        // 全屏背景图才能真正延伸到状态栏之下并遮住底层阅读页文字。
        setStyle(STYLE_NO_FRAME, R.style.dialog_style_material)
    }

    private val callBack: CallBack? get() = activity as? CallBack
    private val binding by viewBinding(DialogReadAloudBinding::bind)
    private var isSeekingChapterProgress = false
    private var oldBgmStartedCallback: (() -> Unit)? = null

    /** 当前章节的 AI 弹幕（DANMAKU_READY 事件写入；按段落锚点在 VN_PARAGRAPH 时发射） */
    private var currentDanmaku: List<DanmakuItem> = emptyList()

    /** 弹幕章节 key（识别切章用） */
    private var danmakuKey: String? = null

    /** 已发射过的段落锚点：区间生成晚于朗读时，读到锚点段照样补发 */
    private val firedAnchors = mutableSetOf<Int>()

    /** 当前章节场景特效（SCENE_EFFECT_READY 写入；VN_PARAGRAPH 按锚点播放） */
    private var currentSceneEffects: List<SceneEffectCue> = emptyList()
    private var sceneEffectKey: String? = null
    private val firedSceneAnchors = mutableSetOf<Int>()

    /** 直接持有特效 View，避免 onDestroyView 后通过 binding 触发 viewLifecycleOwner 异常 */
    private var sceneEffectViewRef: SceneEffectView? = null
    private var imageTimer: Timer? = null
    private var currentImageIndex = 0
    private var bookImageFiles: List<File> = emptyList()

    /** 旁白试听播放器，与配置列表条目试听同一套链路；离开弹窗/销毁时释放 */
    private var narrationPreviewPlayer: android.media.MediaPlayer? = null

    /** AI 生图历史浏览：当前显示的历史图片索引（在 aiHistoryImages 中的位置） */
    private var aiHistoryImages: List<File> = emptyList()
    private var aiHistoryIndex = -1
    /** 用户是否正在手动浏览历史图片（图库已按朗读顺序排列，不能再用 index>0 判断） */
    private var aiUserBrowsing = false
    /** 当前窗口是否为全屏模式（由 applyWindowMode 维护）；用于手势判定半屏专属操作 */
    private var isFullscreen = false
    /** 窗口级手势的 Window.Callback 包装，非 null 表示已挂载 */
    private var swipeWindowCallback: Window.Callback? = null
    /** 包装前的原始 Window.Callback，卸载时还原 */
    private var swipeBaseCallback: Window.Callback? = null
    /** 手势已触发：吞掉后续 MOVE/UP，避免子 View（图片/字幕）误响应 */
    private var gestureHandled = false
    /** 手势序列中最后一次事件的时间戳，用于手势异常中断后的超时自愈 */
    private var gestureLastEventTime = 0L
    /** 手势进行中（DOWN 之后、UP/CANCEL 之前）。用于模式切换重装手势时保留已触发状态 */
    private var gestureInProgress = false
    /** 浏览历史图片后，30秒无操作自动回到最新图片的定时器 */
    private var aiHistoryResetTimer: Timer? = null

    /**
     * 加载图片文件并按 View 的宽高比从图片中间裁剪，使裁剪后图片比例与 View 完全一致，
     * 避免不同比例图片被拉伸变形。View 尺寸保持不变。
     * 裁剪在后台线程执行，完成后切回主线程做交叉淡入显示。
     */
    /**
     * 判断文件是否为 GIF 动图（按扩展名，不区分大小写）。
     */
    private fun isGifFile(file: File): Boolean {
        return file.extension.equals("gif", ignoreCase = true)
    }

    private fun loadImageWithCenterCrop(iv: ImageView, file: File) {
        if (isGifFile(file)) {
            // GIF：复用书架封面同款 Glide 加载链路（ImageLoader + into(view)），本构建可正常播放动画
            iv.loadGif(file, ImageView.ScaleType.CENTER_CROP)
            return
        }
        val viewW = iv.layoutParams.width
        val viewH = iv.layoutParams.height
        if (viewW <= 0 || viewH <= 0) {
            // View 尺寸还未确定，退回直接加载
            loadWithCrossFade(iv, file)
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val croppedBitmap = withContext(Dispatchers.IO) {
                centerCropBitmapFromFile(file, viewW, viewH)
            }
            if (croppedBitmap != null) {
                loadWithCrossFade(iv, croppedBitmap)
            } else {
                // 裁剪失败（如文件损坏），退回直接加载
                loadWithCrossFade(iv, file)
            }
        }
    }

    // 全屏背景图与主预览同步显示同一张图（延伸到状态栏）
    private fun loadIntoBoth(file: File) {
        // 界面已销毁/未挂载时 Glide 会抛 "You cannot start a load for a destroyed activity"
        if (!isAdded || activity?.isDestroyed == true) return
        loadImageWithCenterCrop(binding.ivBookCover, file)
        binding.ivFullscreenBg.let { bg ->
            if (isGifFile(file)) bg.loadGif(file, ImageView.ScaleType.CENTER_CROP)
            else Glide.with(bg).load(file).into(bg)
        }
    }

    private fun loadIntoBoth(model: Any?) {
        loadWithCrossFade(binding.ivBookCover, model)
        binding.ivFullscreenBg.let { bg ->
            Glide.with(bg).load(model).into(bg)
        }
    }

    /** 直接加载已配置好 placeholder/error 的 RequestBuilder（用于书籍封面） */
    private fun loadIntoBoth(builder: com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable>) {
        builder.into(binding.ivBookCover)
        binding.ivFullscreenBg.let { bg ->
            Glide.with(bg).load(builder).into(bg)
        }
    }

    /**
     * 从图片文件解码并按目标宽高比从中间裁剪。
     * - 读取图片实际尺寸，计算 inSampleSize 降采样防止 OOM
     * - 按目标比例（targetW:targetH）从图片中间裁出最大区域
     * - 返回裁剪后的 Bitmap，比例与目标一致
     */
    private fun centerCropBitmapFromFile(file: File, targetW: Int, targetH: Int): Bitmap? {
        return try {
            // 1. 先读尺寸
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
            val imgW = boundsOpts.outWidth
            val imgH = boundsOpts.outHeight
            if (imgW <= 0 || imgH <= 0) return null

            // 2. 计算降采样倍数，避免大图 OOM（目标 View 尺寸的 2 倍即可保证清晰度）
            val reqW = targetW * 2
            val reqH = targetH * 2
            var sampleSize = 1
            if (imgW > reqW || imgH > reqH) {
                val halfW = imgW / 2
                val halfH = imgH / 2
                while (halfW / sampleSize >= reqW && halfH / sampleSize >= reqH) {
                    sampleSize *= 2
                }
            }

            // 3. 降采样解码
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val sampled = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
            val sW = sampled.width
            val sH = sampled.height

            // 4. 按目标宽高比从中间裁剪
            val targetRatio = targetW.toFloat() / targetH.toFloat()
            val srcRatio = sW.toFloat() / sH.toFloat()
            val cropX: Int
            val cropY: Int
            val cropW: Int
            val cropH: Int
            if (srcRatio > targetRatio) {
                // 图片更宽：高度填满，左右裁掉
                cropH = sH
                cropW = (sH * targetRatio).toInt()
                cropX = (sW - cropW) / 2
                cropY = 0
            } else if (srcRatio < targetRatio) {
                // 图片更高：宽度填满，上下裁掉
                cropW = sW
                cropH = (sW / targetRatio).toInt()
                cropX = 0
                cropY = (sH - cropH) / 2
            } else {
                // 比例一致，不裁
                return sampled
            }
            Bitmap.createBitmap(sampled, cropX, cropY, cropW, cropH)
        } catch (e: Exception) {
            null
        }
    }

    /** 使用后台加载实现交叉淡入：旧图片继续显示，新图片加载完成后叠加，800ms 过渡 */
    private fun loadWithCrossFade(imageView: ImageView, model: Any?) {
        val currentDrawable = imageView.drawable ?: run {
            Glide.with(imageView.context).load(model).into(imageView)
            return
        }
        Glide.with(imageView.context)
            .asDrawable()
            .load(model)
            .override(Target.SIZE_ORIGINAL)
            .into(object : CustomTarget<Drawable>(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL) {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    if (resource is Animatable) {
                        imageView.setImageDrawable(resource)
                        resource.start()
                        return
                    }
                    val transitionDrawable = TransitionDrawable(
                        arrayOf(currentDrawable, resource)
                    )
                    transitionDrawable.isCrossFadeEnabled = true
                    imageView.setImageDrawable(transitionDrawable)
                    transitionDrawable.startTransition(800)
                }
                override fun onLoadCleared(placeholder: Drawable?) {
                    // 不清理，保留当前图片避免空白
                }
            })
    }

    /** 对 RequestBuilder 版本做交叉淡入 */
    private fun loadWithCrossFade(imageView: ImageView, builder: RequestBuilder<Drawable>) {
        val currentDrawable = imageView.drawable ?: run {
            builder.into(imageView)
            return
        }
        builder.into(object : CustomTarget<Drawable>(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                if (resource is Animatable) {
                    imageView.setImageDrawable(resource)
                    resource.start()
                    return
                }
                val transitionDrawable = TransitionDrawable(
                    arrayOf(currentDrawable, resource)
                )
                transitionDrawable.isCrossFadeEnabled = true
                imageView.setImageDrawable(transitionDrawable)
                transitionDrawable.startTransition(800)
            }
            override fun onLoadCleared(placeholder: Drawable?) {
                // 不清理，保留当前图片避免空白
            }
        })
    }

    override fun onStart() {
        super.onStart()
        // 根据是否显示封面字幕面板，选择全屏延伸 / 底部面板两套窗口模式。
        // 不附加 isRun 条件：未朗读时也要能显示全屏界面并左右滑切换
        applyWindowMode(AppConfig.showReadAloudCoverSubtitle)
        // 必须在 applyWindowMode 之后：isFullscreen 由它赋值，顺序颠倒会导致手势挂不上
        installWindowGestures()
    }

    override fun onStop() {
        // 窗口即将销毁，强制结束手势状态
        gestureInProgress = false
        uninstallWindowGestures()
        super.onStop()
    }

    /**
     * 两套窗口模式：
     * - showImage = true （显示封面字幕面板）：全屏窗口，背景图延伸到状态栏之下，
     *   彻底遮住底层阅读页文字，呈现沉浸式大图效果。
     * - showImage = false（关闭封面显示）：底部面板模式，只占屏幕下半部分，
     *   不延伸到状态栏，露出上方原文，不遮挡阅读内容。
     */
    @SuppressLint("WrongConstant")
    private fun applyWindowMode(showImage: Boolean) {
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            isFullscreen = showImage
            // 根 view 背景必须按模式显式设置。
            // 布局里 root_view 声明的是 @android:color/transparent（半屏时露出原文），
            // 但 BaseDialogFragment.onViewCreated 会无条件改成不透明的
            // ThemeStore.backgroundColor()，需要在此覆盖：
            // - 全屏：根 view 铺满整屏，必须不透明才能遮住底层阅读页
            // - 半屏：根 view 必须透明，否则它会铺满窗口把上半屏原文整个遮住
            //   （从全屏切回半屏时窗口高度若未及时收缩，残留的不透明背景正好覆盖上半屏）
            view?.setBackgroundColor(
                if (showImage) ThemeStore.backgroundColor() else Color.TRANSPARENT
            )
            val hideIcons = ReadBookConfig.hideStatusBar
            if (showImage) {
                // 全屏延伸模式
                decorView.setPadding(0, 0, 0, 0)
                // 恢复弹幕层（半屏模式已按下方说明移除）
                binding.danmakuView.visibility =
                    if (AppConfig.danmakuEnabled) View.VISIBLE else View.GONE
                binding.sceneEffectView.visibility =
                    if (AppConfig.sceneEffectEnabled) View.VISIBLE else View.GONE
                // 清掉半屏模式加的穿透标记：全屏时窗口铺满整屏，触摸应只由本窗口处理，
                // 否则运行时由半屏切回全屏会残留该标记
                clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                WindowCompat.setDecorFitsSystemWindows(this, false)
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                statusBarColor = Color.TRANSPARENT
                navigationBarColor = Color.TRANSPARENT
                // 允许窗口延伸进挖孔/刘海区（封面自己用 displayCutout 安全区躲避摄像头），
                // 否则部分机型隐藏状态栏后拿不到挖孔 inset
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val cutoutAttrs = attributes
                    cutoutAttrs.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    attributes = cutoutAttrs
                }
                val attr = attributes
                attr.gravity = Gravity.FILL
                attr.dimAmount = 0.0f
                attributes = attr
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                WindowInsetsControllerCompat(this, decorView).apply {
                    if (hideIcons) {
                        hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                    } else {
                        show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                    }
                    // 黑色状态栏图标，在白色主题/浅色背景上清晰可见
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            } else {
                // 底部面板模式：只占屏幕下半部分（WRAP_CONTENT + BOTTOM），
                // 上半屏空白不在窗口内，且通过 FLAG_NOT_TOUCH_MODAL 让窗口外触摸穿透到下层阅读页，
                // 可直接点字翻页、操作原文。
                // 必须彻底复位全屏延伸模式对 decorView / 系统栏的改动，否则穿透会失效：
                //
                // 关键：半屏必须用 GONE 彻底移除两个全屏专属元素，不能用 INVISIBLE。
                // - ll_read_aloud_top 内的 NestedScrollView 是 0dp + weight=1，在 wrap_content 的
                //   content_panel 里会被 weight 分配近乎整个屏幕高度；只要它仍参与测量，
                //   content_panel 就维持全屏高度，其不透明背景整屏遮挡阅读页
                //   —— 这就是全屏切回半屏出现白底的直接原因。
                // - iv_fullscreen_bg 是 match_parent，残留可见同样会把窗口撑满。
                // 放在此处（窗口模式的唯一入口）可确保任何调用顺序下都成立。
                binding.llReadAloudTop.gone()
                binding.ivFullscreenBg.gone()
                // 弹幕层同为 match_parent，若保持可见会把 WRAP_CONTENT 的半屏窗口
                // 重新撑满整屏、面板错位（与上方 iv_fullscreen_bg 必须移除同理），
                // 因此半屏模式彻底移除弹幕层并清空在屏弹幕
                binding.danmakuView.clearDanmaku()
                binding.danmakuView.gone()
                // 场景特效层同样 match_parent，半屏模式必须彻底移除，否则撑满窗口
                binding.sceneEffectView.clearSceneEffects()
                binding.sceneEffectView.gone()
                WindowCompat.setDecorFitsSystemWindows(this, true)
                decorView.setPadding(0, 0, 0, 0)
                statusBarColor = Color.TRANSPARENT
                navigationBarColor = Color.TRANSPARENT
                clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                // 让窗口外（上半屏）触摸穿透到下层阅读页
                addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                WindowInsetsControllerCompat(this, decorView).apply {
                    show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
                val attr = attributes
                attr.gravity = Gravity.BOTTOM
                attr.dimAmount = 0.0f
                attributes = attr
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        // 强制重新测量：窗口高度在 MATCH_PARENT 与 WRAP_CONTENT 之间切换时，
        // 根 view 是 MATCH_PARENT，可能沿用上一次的窗口高度，导致切回半屏后
        // 尺寸未收缩。post 到下一帧确保窗口 relayout 后再测量一次。
        view?.post {
            dialog?.window?.decorView?.requestLayout()
            view?.requestLayout()
        }
    }

    override fun onDestroyView() {
        sceneEffectViewRef?.cancelPendingEffects()
        sceneEffectViewRef?.setIdleEffect(null)
        sceneEffectViewRef = null
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? ReadBookActivity)?.let { it.bottomDialog-- }
        if (view != null) binding.ivBookCover.setMarqueeEnabled(false)
        BgmManager.onBgmStarted = oldBgmStartedCallback
        oldBgmStartedCallback = null
        stopImageTimer()
        cancelAiHistoryReset()
        // 关掉朗读面板时同时停掉旁白试听，避免后台继续播放
        narrationPreviewPlayer?.release()
        narrationPreviewPlayer = null
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val bottomDialog = (activity as ReadBookActivity).bottomDialog++
        if (bottomDialog > 0) {
            dismiss()
            return
        }
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)
        binding.run {
            // 根布局保持透明：图片层全屏延伸状态栏，仅底部 content_panel 不透明，
            // 避免整屏不透明背景遮盖原文阅读区（关闭/无图时露出原文）
            contentPanel.setBackgroundColor(bg)
            tvPre.setTextColor(textColor)
            tvNext.setTextColor(textColor)
            ivPlayPrev.setColorFilter(textColor)
            ivPlayPause.setColorFilter(textColor)
            ivPlayNext.setColorFilter(textColor)
            ivStop.setColorFilter(textColor)
            ivTimer.setColorFilter(textColor)
            tvTimer.setTextColor(textColor)
            ivTtsSpeechReduce.setColorFilter(textColor)
            tvTtsSpeed.setTextColor(textColor)
            tvTtsSpeedValue.setTextColor(textColor)
            ivTtsSpeechAdd.setColorFilter(textColor)
            ivCatalog.setColorFilter(textColor)
            tvCatalog.setTextColor(textColor)
            ivMainMenu.setColorFilter(textColor)
            tvMainMenu.setTextColor(textColor)
            ivLog.setColorFilter(textColor)
            tvLog.setTextColor(textColor)
            ivSetting.setColorFilter(textColor)
            tvSetting.setTextColor(textColor)
            cbTtsFollowSys.setTextColor(textColor)
            tvReadAloudSubtitle.setTextColor(textColor)
            tvChapterProgressStart.setTextColor(textColor)
            tvChapterProgressEnd.setTextColor(textColor)
        }
        applyChapterProgressVisibility()
        initData()
        initEvent()
        initDanmaku()
        initSceneEffect()
        // 注意：窗口手势不在此挂载。onFragmentCreated 由 onViewCreated 调用，
        // 早于 onStart，此时 isFullscreen 还没被 applyWindowMode 赋值，挂上去会直接 return。
        // 统一在 onStart 的 applyWindowMode 之后由 installWindowGestures 挂载。
        // 根据系统导航栏高度动态调整底部 padding，避免三键导航遮挡按钮
        binding.contentPanel.applyNavigationBarPadding(withInitialPadding = true)
    }

    /**
     * 安装窗口级手势，两种模式都会挂载：
     * - 全屏模式：自上而下滑动 → 退出朗读界面
     * - 两种模式通用：向左 / 向右滑动 → 在全屏（图片+字幕）与半屏（仅控制条）之间切换
     *
     * 只拦截"已确认命中"的手势，其余事件一律继续下发给视图树，
     * 因此不会影响按钮点击、字幕点击、封面切图，也不影响半屏模式上半屏的触摸穿透。
     *
     * 必须在 [applyWindowMode] 之后调用：那时 [isFullscreen] 才被赋值。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun installWindowGestures() {
        uninstallWindowGestures()
        val window = dialog?.window ?: return
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        // 切换模式会在手势进行中重装本监听器，此时不能复位，
        // 否则手指尚未抬起，残余的 MOVE/UP 会漏到新视图树造成误触
        if (!gestureInProgress) gestureHandled = false
        val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                startX = e.x
                startY = e.y
                gestureInProgress = true
                gestureHandled = false
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (gestureHandled) return false
                val dy = e2.y - startY
                val dx = e2.x - startX
                // 全屏模式：明显向下、且以纵向为主 → 退出
                // 排除顶部 10% 起手：该区域让给下拉状态栏，避免误触。
                // 全屏时窗口铺满整屏，decorView 高度即屏幕高度，坐标原点为屏幕顶部；
                // 半屏时该分支本就被 isFullscreen 挡掉，不受影响。
                if (isFullscreen
                    && startY > window.decorView.height * 0.1f
                    && dy > touchSlop * 3
                    && dy > abs(dx)
                ) {
                    gestureHandled = true
                    dismiss()
                    return true
                }
                // 明显横向 → 切换朗读界面模式。
                // 阈值取 4 倍 slop 并要求明显横向，避免误伤点击与斜向滚动；
                // 封面（左右滑切图）与拖动条（调语速/定时/进度）区域内不触发。
                if (!isInGestureExcludeZone(startX, startY)
                    && abs(dx) > touchSlop * 4
                    && abs(dx) > abs(dy) * 1.5f
                ) {
                    gestureHandled = true
                    toggleReadAloudMode()
                    return true
                }
                return false
            }
        })
        val base = window.callback ?: return
        val wrapper = SwipeWindowCallback(base) { event ->
            val now = System.currentTimeMillis()
            // 超时自愈：由全屏切回半屏时窗口会从全屏收缩到底部约一屏高的 1/4，
            // 手指所在的中/上部瞬间落到窗口之外，后续 MOVE 与 ACTION_UP 都会因
            // FLAG_NOT_TOUCH_MODAL 穿透到阅读页，本窗口再也收不到，
            // 于是下面的 UP 复位永远执行不到，gestureInProgress 会永久卡在 true，
            // 进而使 gestureHandled 永不复位、手势彻底失效。
            // 因此不能只依赖 UP 复位：DOWN 距上次事件超过阈值即判定为新序列并强制复位。
            if (event.actionMasked == MotionEvent.ACTION_DOWN
                && now - gestureLastEventTime > GESTURE_SEQUENCE_TIMEOUT_MS
            ) {
                gestureInProgress = false
                gestureHandled = false
            }
            gestureLastEventTime = now
            // 手势结束（抬手 / 被父级取消）后复位
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    gestureInProgress = false
                }
            }
            gestureDetector.onTouchEvent(event)
            // 只吞掉"已确认命中手势"的事件；ACTION_DOWN 等一律继续下发给子 View，
            // 否则封面点击、字幕点击、左右滑切图会全部失效
            gestureHandled
        }
        window.callback = wrapper
        swipeBaseCallback = base
        swipeWindowCallback = wrapper
    }

    private fun uninstallWindowGestures() {
        val window = dialog?.window ?: return
        val wrapper = swipeWindowCallback ?: return
        if (window.callback === wrapper) {
            swipeBaseCallback?.let { window.callback = it }
        }
        swipeWindowCallback = null
        swipeBaseCallback = null
        // 切换模式时是在手势进行中重装的：此时不能复位 gestureHandled，
        // 否则手指尚未抬起，残余的 MOVE/UP 会漏到新视图树造成误触或重复切换
        if (!gestureInProgress) gestureHandled = false
    }

    /**
     * 是否落在「横向手势已被占用」的区域：书籍封面（左右滑切图）与各个拖动条。
     * 这些区域内的左右滑保留原有功能，不触发模式切换。
     */
    private fun isInGestureExcludeZone(x: Float, y: Float): Boolean = binding.run {
        isPointInView(ivBookCover, x, y)
            || isPointInView(seekChapterProgress, x, y)
            || isPointInView(seekTtsSpeechRate, x, y)
            || isPointInView(seekTimer, x, y)
    }

    /**
     * 判断窗口坐标 (x, y) 是否落在 view 内。
     * MotionEvent 坐标与 getLocationInWindow 同为窗口坐标，可直接比较。
     */
    private fun isPointInView(view: View, x: Float, y: Float): Boolean {
        if (!isReallyVisible(view)) return false
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        return x >= loc[0] && x <= loc[0] + view.width
            && y >= loc[1] && y <= loc[1] + view.height
    }

    /**
     * view 是否真正可见（自身与所有祖先均 VISIBLE，且已完成测量）。
     *
     * 不能只看自身的 visibility：ivBookCover 与 seekChapterProgress 位于
     * ll_read_aloud_top 内，半屏模式下该容器被 GONE，但它俩自身仍是 VISIBLE，
     * 且 width/height 保留着全屏时测量出的尺寸（约 240dp×340dp），
     * getLocationInWindow 又返回残留位置。若仅按自身可见性判定，
     * 这两个"已隐藏但仍带尺寸"的 View 会把几乎整个半屏面板误判为排除区，
     * 导致半屏下横滑切换彻底失效。
     */
    private fun isReallyVisible(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (view.width <= 0 || view.height <= 0) return false
        var parent = view.parent
        while (parent is View) {
            if (parent.visibility != View.VISIBLE) return false
            parent = parent.parent
        }
        return true
    }

    /**
     * 在全屏（图片+字幕）与半屏（仅控制条）朗读界面之间切换，
     * 与设置里的开关 [AppConfig.showReadAloudCoverSubtitle] 完全等价、双向同步。
     */
    private fun toggleReadAloudMode() {
        val toFullscreen = !AppConfig.showReadAloudCoverSubtitle
        AppConfig.showReadAloudCoverSubtitle = toFullscreen
        // 内部会重新 applyWindowMode 并重装手势
        upTopSection()
        toastOnUi(if (toFullscreen) "已切换到全屏朗读界面" else "已切换到半屏朗读界面")
    }

    /**
     * 只拦截 dispatchTouchEvent 的 Window.Callback 包装，其余方法全部委托给原始 callback。
     *
     * 用 Window.Callback 而非 decorView 的 OnTouchListener 的原因：
     * DecorView.dispatchTouchEvent 会先把事件交给 Window.Callback（即 Dialog），
     * 由它再下发给整个视图树；因此 DecorView 自己的 OnTouchListener
     * 基本收不到事件，且子 View 消费后更不会回传。
     */
    private class SwipeWindowCallback(
        private val base: Window.Callback,
        private val shouldIntercept: (MotionEvent) -> Boolean
    ) : Window.Callback by base {

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (shouldIntercept(event)) return true
            return base.dispatchTouchEvent(event)
        }
    }

    /**
     * 根据「隐藏章节进度条」设置控制进度条可见性。
     * 开关默认开启（hide=true）→ 彻底移除进度条及其占用的空间（GONE），让图片更大；
     * 关闭 → 显示进度条。
     * 注意：不能用 visible(false)（那是 INVISIBLE，会保留空间）。
     */
    private fun applyChapterProgressVisibility() = binding.run {
        llChapterProgress.visibility =
            if (AppConfig.readAloudHideChapterProgress) View.GONE else View.VISIBLE
    }

    private fun initData() = binding.run {
        upPlayState()
        upTimerText(BaseReadAloudService.timeMinute)
        cbTtsFollowSys.isChecked = requireContext().getPrefBoolean("ttsFollowSys", true)
        upTtsSpeechRateEnabled(!cbTtsFollowSys.isChecked)
        upSeekTimer()
        upTopSection()
        // 主动请求当前字幕，解决暂停/初始进入时字幕空白问题
        HttpReadAloudService.requestCurrentSubtitle()
        // 设置 BGM 切换回调，联动跑马灯速度
        // 必须链式调用旧回调（BaseReadAloudService 的 triggerBgmAnalysis），否则 AI 预分析会被截断
        oldBgmStartedCallback = BgmManager.onBgmStarted
        BgmManager.onBgmStarted = {
            oldBgmStartedCallback?.invoke()
            if (AppConfig.readAloudCoverMarqueeEnabled) {
                val baseSpeed = AppConfig.readAloudCoverMarqueeSpeed.coerceIn(500, 10000)
                val bgmName = BgmManager.getCurrentBgmName()
                val finalSpeed = BgmMarqueeSpeed.calculateSpeed(baseSpeed, bgmName)
                ivBookCover.setMarqueeSpeed(finalSpeed)
            }
        }
    }

    /**
     * 初始化上半部分：受「显示封面字幕」设置开关控制。
     * 不附加 isRun 条件，这样未朗读时也能进入全屏界面并左右滑切换模式。
     */
    private fun upTopSection() = binding.run {
        val show = AppConfig.showReadAloudCoverSubtitle
        // 必须用 gone(!show)，不能用 visible(show)：
        // visible(false) 是 INVISIBLE，会保留占位并继续参与测量。
        // ll_read_aloud_top 内的 NestedScrollView 是 0dp + weight=1，
        // 在 wrap_content 的 content_panel 里会被 weight 分配几乎整个屏幕高度；
        // 只要它仍参与测量，content_panel 就会维持全屏高度，
        // 其不透明背景（applyTint 中设置的 bg）将整屏遮挡下方阅读页。
        llReadAloudTop.gone(!show)
        if (show) {
            if (AppConfig.readAloudCoverAutoFit) {
                // 自动适应：先把布局换成确定性的一套（面板铺满整屏，
                // 主题色盖住状态栏，不再露出背景图条），再算封面尺寸
                applyDeterministicLayout(true)
                applyAutoFitCover()
            } else {
                // 手动模式恢复 XML 原始布局参数
                applyDeterministicLayout(false)
                // 用户设定尺寸：不再限制图片最大高度，图片完全按设定宽度与比例显示，可设得更大。
                // llReadAloudTop 使用 layout_weight=1 自动占据屏幕剩余空间，下方内容不会被挤掉。
                val widthDp = AppConfig.readAloudCoverWidth.coerceIn(80, 600)
                val heightDp = (widthDp * 340f / 240f).toInt()
                val widthPx = widthDp.dpToPx()
                val heightPx = heightDp.dpToPx()

                ivBookCover.layoutParams = ivBookCover.layoutParams.apply {
                    width = widthPx
                    height = heightPx
                }
                // 应用「图片与顶部的距离」设置（dp）
                val topMarginPx = AppConfig.readAloudCoverTopMargin.coerceAtLeast(0).dpToPx()
                (ivBookCover.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                    topMargin = topMarginPx
                }
            }

            // 初始化跑马灯边框（直接画在封面 View 上，避免层级遮挡问题）
            // 仅在朗读播放中才启动跑马灯，暂停时不启动
            if (AppConfig.readAloudCoverMarqueeEnabled && !BaseReadAloudService.pause) {
                val baseSpeed = AppConfig.readAloudCoverMarqueeSpeed.coerceIn(500, 10000)
                val bgmName = BgmManager.getCurrentBgmName()
                val finalSpeed = BgmMarqueeSpeed.calculateSpeed(baseSpeed, bgmName)
                ivBookCover.setMarqueeSpeed(finalSpeed)
                ivBookCover.setMarqueeEnabled(true)
            } else {
                ivBookCover.setMarqueeEnabled(false)
            }

            // 加载书籍封面或 AI 生图或小说文件夹图片
            ReadBook.book?.let { book ->
                if (AppConfig.readAloudAiImage) {
                    // AI生图已改为字数驱动，优先显示最近生成的图片
                    val lastImage = AiImageGenerator.getLastGeneratedImage()
                    if (lastImage != null) {
                        loadIntoBoth(lastImage)
                    } else {
                        // AI 生图开启但没缓存时，显示书籍封面
                        loadIntoBoth(BookCover.load(requireContext(), book.getDisplayCover()))
                    }
                    stopImageTimer()
                    // 始终请求 Service 同步当前场景图片：即使本地有缓存图片，
                    // Service 可能已切换到更新的场景，需让其通知最新图片，避免退出重进时显示过期或默认封面
                    postEvent(EventBus.AI_IMAGE_REQUEST, "0")
                } else {
                    loadBookCoverOrLocalImages(book, ivBookCover)
                }
                // 设置书名
                tvBookName.text = book.name
            }
            // 设置章节名
            tvChapterName.text = ReadBook.curTextChapter?.title ?: ""
            // 初始化进度条位置（使用阅读位置估算，服务会随后推送精确值）
            ReadBook.curTextChapter?.let { tc ->
                val total = tc.getContent().length.coerceAtLeast(1)
                val current = ReadBook.durChapterPos.coerceIn(0, total)
                val progress = (current.toFloat() / total * 1000).toInt()
                seekChapterProgress.progress = progress
                tvChapterProgressStart.text = "${(progress / 10f).toInt()}%"
            }
            // 同步全屏背景图可见性
            ivFullscreenBg.visible(show)
        } else {
            // 关闭封面显示：隐藏全屏背景图
            ivFullscreenBg.gone()
        }
        // 根据开关切换窗口模式（全屏延伸 / 底部面板）
        if (dialog?.isShowing == true) {
            applyWindowMode(show)
            // 模式可能已切换（全屏 ↔ 底部面板），同步重装窗口手势
            installWindowGestures()
        }
    }

    /**
     * 自动模式下把布局换成**确定性**的一套（手动模式恢复 XML 原参数）。
     *
     * XML 原布局是 content_panel(wrap_content) → ll_read_aloud_top(wrap_content)
     * → NestedScrollView(0dp+weight=1)：weight 嵌在两层 wrap_content 里，
     * 面板实际高度"晃"——实测自动模式下面板顶不到屏幕最上面，刚好差一个
     * 状态栏高度，把顶部一条让给了背后的全屏背景图（iv_fullscreen_bg），
     * 状态栏就露出一条放大的图片背景。
     *
     * 确定性布局：面板直接 MATCH_PARENT 铺满整屏（主题底色盖住状态栏，
     * 不再露背景图），上半区 0dp+weight=1 占面板内剩余空间，
     * 滚动区 MATCH_PARENT 填满上半区——这时滚动区高度 = 屏幕减按钮区，
     * 与封面大小完全无关，量出来才是准的。
     */
    private fun applyDeterministicLayout(auto: Boolean) = binding.run {
        val panelLp = contentPanel.layoutParams
        val topLp = llReadAloudTop.layoutParams as? LinearLayout.LayoutParams ?: return@run
        val scrollLp = svReadAloudTop.layoutParams
        if (auto) {
            if (panelLp.height == ViewGroup.LayoutParams.MATCH_PARENT &&
                topLp.height == 0 && topLp.weight == 1f &&
                scrollLp.height == ViewGroup.LayoutParams.MATCH_PARENT
            ) return@run
            panelLp.height = ViewGroup.LayoutParams.MATCH_PARENT
            topLp.height = 0
            topLp.weight = 1f
            scrollLp.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            // 恢复 XML 原始值：面板 wrap_content、上半区 wrap_content、滚动区 0dp+weight=1
            if (panelLp.height == ViewGroup.LayoutParams.WRAP_CONTENT &&
                topLp.height == ViewGroup.LayoutParams.WRAP_CONTENT && topLp.weight == 0f &&
                scrollLp.height == 0
            ) return@run
            panelLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            topLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            topLp.weight = 0f
            scrollLp.height = 0
        }
        contentPanel.layoutParams = panelLp
        llReadAloudTop.layoutParams = topLp
        svReadAloudTop.layoutParams = scrollLp
        root.requestLayout()
    }

    /**
     * 封面自动适应：忽略手动宽度/顶部距离，按滚动区实际可用高度算出最大可放大小。
     * 依赖 [applyDeterministicLayout] 先把布局换成确定性的一套，
     * 这时滚动区高度与封面无关，量一次就准。
     *
     * - 顶部：紧贴摄像头底部（状态栏/挖孔安全区边界，不留空隙）；
     * - 可用高度 = 滚动区高度 − 标题/章节/字幕/进度条等固定内容及其边距；
     * - 封面按 240:340 比例缩放进这个空间（宽也受滚动区宽度约束），
     *   底部按钮永远在屏内，最下面一层不会再被挤掉。
     */
    private fun applyAutoFitCover() = binding.run {
        // 布局已就绪就立刻算；否则挂一次性布局监听，
        // 等确定性布局生效、各部分完成首次布局后再算
        if (llReadAloudTop.visibility == View.VISIBLE && svReadAloudTop.height > 0) {
            computeAutoFitCover()
        }
        root.doOnNextLayout {
            if (llReadAloudTop.visibility == View.VISIBLE) computeAutoFitCover()
        }
    }

    /** 真正的自动适应计算：仅在滚动区已完成后调用 */
    private fun computeAutoFitCover() = binding.run {
        if (!isAdded || llReadAloudTop.visibility != View.VISIBLE) return@run
        val scroll = svReadAloudTop
        if (scroll.width <= 0 || scroll.height <= 0) return@run
        // 顶部避开摄像头：状态栏 inset 和挖孔（displayCutout）安全区取较大者。
        // 隐藏状态栏时 statusBars.top 为 0，摄像头位置只有 displayCutout 能拿到；
        // 显示状态栏时挖孔一般含在状态栏内，两者取大不会多让。
        // 不额外留空隙：图片顶部紧贴摄像头底部（正好在安全区边界上），图片能显示得更大
        val insets = ViewCompat.getRootWindowInsets(root)
        val statusTop = insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        val cutoutTop = insets?.displayCutout?.safeInsetTop ?: 0
        val topMarginPx = maxOf(statusTop, cutoutTop)
        val content = scroll.getChildAt(0) as? ViewGroup ?: return@run
        // 封面以外的固定内容（书名/章节/字幕/进度条/分隔线）及其外边距，
        // 与封面大小无关（确定性布局下滚动区高度不随封面变）
        var fixed = 0
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            if (child.id == R.id.iv_book_cover || child.visibility == View.GONE) continue
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
            fixed += child.height + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
        }
        val availH = scroll.height - fixed - topMarginPx
        // 可用空间异常小（切换动画中/极端屏幕）时不折腾
        if (availH < 120.dpToPx()) return@run
        var w = scroll.width
        var h = w * 340 / 240
        if (h > availH) {
            h = availH
            w = h * 240 / 340
        }
        val lp = ivBookCover.layoutParams as? ViewGroup.MarginLayoutParams ?: return@run
        // 参数没变化就不重设，避免"改→布局→监听又触发→再改"的循环
        if (lp.width == w && lp.height == h && lp.topMargin == topMarginPx) return@run
        lp.width = w
        lp.height = h
        lp.topMargin = topMarginPx
        ivBookCover.requestLayout()
    }

    /**
     * 加载书籍封面或扫描小说文件夹中的图片
     */
    private fun loadBookCoverOrLocalImages(book: io.legado.app.data.entities.Book, ivBookCover: io.legado.app.ui.widget.image.CoverImageView) {
        // 扫描小说文件夹中的图片
        val bookImages = scanBookImages(book.name)
        if (bookImages.isNotEmpty()) {
            bookImageFiles = bookImages
            currentImageIndex = 0
            // 显示第一张图片
            loadIntoBoth(bookImageFiles[0])
            // 启动定时器轮换图片
            startImageTimer(ivBookCover)
        } else {
            // 没有本地图片，显示书籍封面（使用带占位/错误图的加载链路，避免白屏）
            loadIntoBoth(BookCover.load(requireContext(), book.getDisplayCover()))
            stopImageTimer()
        }
    }

    /**
     * 扫描小说文件夹中的图片文件（实现见 [DialogRoleManager.scanBookImages]）
     */
    private fun scanBookImages(bookName: String): List<File> {
        return DialogRoleManager.scanBookImages(bookName)
    }

    /**
     * 启动图片轮换定时器
     */
    private fun startImageTimer(ivBookCover: io.legado.app.ui.widget.image.CoverImageView) {
        stopImageTimer()
        val intervalSec = AppConfig.readAloudImageInterval.coerceAtLeast(0)
        if (intervalSec <= 0) return // 0 表示不自动轮播，仅手动切换
        if (bookImageFiles.size <= 1) return
        val period = intervalSec * 1000L
        imageTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    activity?.runOnUiThread {
                        currentImageIndex = (currentImageIndex + 1) % bookImageFiles.size
                        val nextImage = bookImageFiles[currentImageIndex]
                        loadIntoBoth(nextImage)
                    }
                }
            }, period, period)
        }
    }

    /**
     * 停止图片轮换定时器
     */
    private fun stopImageTimer() {
        imageTimer?.cancel()
        imageTimer = null
    }

    /**
     * 手动切换本地小说文件夹图片。direction: 1 上一张，-1 下一张（循环）。
     * 切换后重置轮播定时器，5 秒后继续自动轮播。
     */
    private fun switchLocalImage(direction: Int) {
        if (bookImageFiles.isEmpty()) return
        val size = bookImageFiles.size
        // 本地图片的方向约定与 AI 历史相反，取反使 direction=-1=下一张、1=上一张，与手势一致
        currentImageIndex = (currentImageIndex - direction + size) % size
        val file = bookImageFiles[currentImageIndex]
        if (file.exists()) {
            loadIntoBoth(file)
        }
        // 手动切换后重新启动轮播定时器
        startImageTimer(binding.ivBookCover)
    }

    private fun initEvent() = binding.run {
        // 点击字幕区域：进入全屏 AI 图片预览界面
        // 使用 activity 的 supportFragmentManager 而非 childFragmentManager，
        // 使全屏 Dialog 获得独立的顶层窗口，不被本 Dialog（底部定位、WRAP_CONTENT）的窗口范围限制，
        // 避免顶部透出底层阅读页文字
        tvReadAloudSubtitle.setOnClickListener {
            // 视觉小说舞台开关：开→舞台界面，关→原全屏图界面
            if (AppConfig.vnStageEnabled) {
                VnStageDialog().show(requireActivity().supportFragmentManager, "vnStageDialog")
            } else {
                FullscreenAiImageDialog().show(requireActivity().supportFragmentManager, "fullscreenAiImageDialog")
            }
        }
        llMainMenu.setOnClickListener {
            callBack?.showMenuBar()
            dismissAllowingStateLoss()
        }
        llSetting.setOnClickListener {
            ReadAloudConfigDialog().show(childFragmentManager, "readAloudConfigDialog")
        }
        tvPre.setOnClickListener { ReadBook.moveToPrevChapter(upContent = true, toLast = false) }
        tvNext.setOnClickListener { ReadBook.moveToNextChapter(true) }
        ivStop.setOnClickListener {
            ReadAloud.stop(requireContext())
            dismissAllowingStateLoss()
        }
        ivPlayPause.setOnClickListener { callBack?.onClickReadAloud() }
        ivPlayPrev.setOnClickListener { ReadAloud.prevParagraph(requireContext()) }
        ivPlayNext.setOnClickListener { ReadAloud.nextParagraph(requireContext()) }
        llCatalog.setOnClickListener { callBack?.openChapterList() }
        llBgm.setOnClickListener { callBack?.showBgmConfig() }
        // 日志：查看运行日志（带筛选与刷新）
        llLog.setOnClickListener {
            AppLogDialog().show(childFragmentManager, "appLogDialog")
        }
        llCharacterManager.setOnClickListener {
            CharacterManagerDialog().show(childFragmentManager, "characterManagerDialog")
        }
        llConfigList.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), io.legado.app.ui.tts.plugin.TtsPluginActivity::class.java))
        }
        // 打开当前 TTS 应用
        llOpenTtsApp.setOnClickListener {
            openCurrentTtsApp()
        }
        // 打开朗读引擎选择页面
        llSpeakEngine.setOnClickListener {
            SpeakEngineDialog().show(childFragmentManager, "speakEngineDialog")
        }
        // 朗读引擎 - 长按弹出清理缓存弹窗
        llSpeakEngine.setOnLongClickListener {
            SpeakEngineDialog.showClearCacheDialog(requireContext())
            true
        }
        cbTtsFollowSys.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.ttsFlowSys = isChecked
            upTtsSpeechRateEnabled(!isChecked)
            upTtsSpeechRate()
        }
        // 本书专属旁白：点击弹出旁白音色选择（默认 = 不指定，按默认逻辑挑）
        tvTtsNarration.setOnClickListener { showNarrationPicker() }
        upNarrationText()
        // 加减按钮：若当前值不在 0.05 步进刻度上（如拖动条产生的 1.02），
        // 第一次点击先吸附到最近的刻度（加→向上取整，减→向下取整），之后再按步进走
        ivTtsSpeechReduce.setOnClickListener {
            val cur = AppConfig.ttsSpeechRateFine
            val v = if (cur % 5 == 0) {
                (cur - 5).coerceAtLeast(0)
            } else {
                cur / 5 * 5
            }
            seekTtsSpeechRate.progress = v
            AppConfig.ttsSpeechRateFine = v
            upTtsSpeechRate()
        }
        ivTtsSpeechAdd.setOnClickListener {
            val cur = AppConfig.ttsSpeechRateFine
            val v = if (cur % 5 == 0) {
                (cur + 5).coerceAtMost(450)
            } else {
                (cur + 4) / 5 * 5
            }
            seekTtsSpeechRate.progress = v
            AppConfig.ttsSpeechRateFine = v
            upTtsSpeechRate()
        }
        ivTimer.setOnClickListener {
            AppConfig.ttsTimer = seekTimer.progress
            toastOnUi("保存设定时间成功！")
        }
        tvTimer.setOnClickListener {
            val times = intArrayOf(0, 5, 10, 15, 30, 60, 90, 180)
            val timeKeys = times.map { "$it 分钟" }
            context?.selector("设定时间", timeKeys) { _, index ->
                ReadAloud.setTimer(requireContext(), times[index])
            }
        }
        //设置保存的默认值（精细刻度 0~450，步进 0.01）
        seekTtsSpeechRate.progress = AppConfig.ttsSpeechRateFine
        seekTtsSpeechRate.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                super.onProgressChanged(seekBar, progress, fromUser)
                upTtsSpeechRateText(progress)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                AppConfig.ttsSpeechRateFine = seekBar.progress
                upTtsSpeechRate()
            }
        })
        seekTimer.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                upTimerText(progress)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                ReadAloud.setTimer(requireContext(), seekTimer.progress)
            }
        })
        // 章节进度条拖动
        seekChapterProgress.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    isSeekingChapterProgress = true
                    tvChapterProgressStart.text = "${(progress / 10f).toInt()}%"
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isSeekingChapterProgress = false
                seekToChapterProgress(seekBar.progress)
            }
        })
        // 监听朗读播放/暂停状态，同步控制跑马灯：暂停时保持在当前位置，不消失
        observeEvent<Int>(EventBus.ALOUD_STATE) { state ->
            when (state) {
                Status.PLAY -> {
                    if (AppConfig.readAloudCoverMarqueeEnabled) {
                        ivBookCover.resumeMarquee()
                    }
                }
                Status.PAUSE -> {
                    ivBookCover.pauseMarquee()
                }
                Status.STOP -> {
                    ivBookCover.stopMarquee()
                }
            }
        }
    }

    /**
     * 根据进度条位置跳转朗读：直接发送段落跳转事件，不停止播放
     * @param progress 千分比 0~1000
     */
    private fun seekToChapterProgress(progress: Int) {
        postEvent(EventBus.READ_ALOUD_SEEK_PARAGRAPH, progress)
    }

    private fun upTtsSpeechRateEnabled(enabled: Boolean) {
        binding.run {
            upTtsSpeechRateText(AppConfig.ttsSpeechRateFine)
            tvTtsSpeedValue.visible(enabled)
            seekTtsSpeechRate.isEnabled = enabled
            ivTtsSpeechReduce.isEnabled = enabled
            ivTtsSpeechAdd.isEnabled = enabled
        }
    }

    private fun upPlayState() {
        if (!BaseReadAloudService.pause) {
            binding.ivPlayPause.setImageResource(R.drawable.ic_pause_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.pause)
        } else {
            binding.ivPlayPause.setImageResource(R.drawable.ic_play_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.audio_play)
        }
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)
        binding.ivPlayPause.setColorFilter(textColor)
    }

    private fun upSeekTimer() {
        binding.seekTimer.post {
            if (BaseReadAloudService.timeMinute > 0) {
                binding.seekTimer.progress = BaseReadAloudService.timeMinute
            } else {
                binding.seekTimer.progress = AppConfig.ttsTimer
            }
        }

        // AI 生图图片：滑动手势 + 长按菜单
        setupAiImageGesture()
    }

    /**
     * 为图片设置手势（AI 生图与本地图片轮播通用）：
     * - 左右滑动：切换上一张 / 下一张
     * - 单击左 1/3：上一张；单击右 1/3：下一张
     * - 单击中 1/3：进入全屏图片预览（两种模式通用）
     * - 长按：仅 AI 生图模式弹出菜单选择保存当前图片或保存全部
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupAiImageGesture() {
        val iv = binding.ivBookCover
        // 记录按下起点，用于 onScroll 判断方向
        var downX = 0f
        var downY = 0f
        var moved = false
        val gesture = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // 若发生了滑动切换则不触发单击动作
                if (moved) return false
                val viewWidth = iv.width.takeIf { it > 0 } ?: return false
                when {
                    e.x < viewWidth / 3f -> {
                        // 左 1/3：前一张（朗读顺序上一张，已读过的场景）
                        if (AppConfig.readAloudAiImage) switchAiHistoryImage(-1) else switchLocalImage(1)
                    }
                    e.x > viewWidth * 2f / 3f -> {
                        // 右 1/3：后一张（朗读顺序下一张，通常是后续场景）
                        if (AppConfig.readAloudAiImage) switchAiHistoryImage(1) else switchLocalImage(-1)
                    }
                    else -> {
                        // 中 1/3：进入大图界面（视觉小说舞台开→舞台，关→全屏图）
                        if (AppConfig.vnStageEnabled) {
                            VnStageDialog().show(requireActivity().supportFragmentManager, "vnStageDialog")
                        } else {
                            FullscreenAiImageDialog().show(requireActivity().supportFragmentManager, "fullscreenAiImageDialog")
                        }
                    }
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                // 已切换过则忽略本次手势后续移动
                if (moved) return false
                val startX = e1?.x ?: downX
                val dx = e2.x - startX
                val dy = e2.y - (e1?.y ?: downY)
                // 横向位移需大于纵向，且超过阈值即切换（大范围慢速滑动也能触发）
                if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) {
                    if (AppConfig.readAloudAiImage) {
                        if (dx > 0) {
                            // 从左向右滑：前一张（朗读顺序上一张，已读过的场景）
                            switchAiHistoryImage(-1)
                        } else {
                            // 从右向左滑：后一张（朗读顺序下一张，后续场景）
                            switchAiHistoryImage(1)
                        }
                    } else {
                        if (dx > 0) switchLocalImage(1) else switchLocalImage(-1)
                    }
                    moved = true
                }
                return false
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                // 切换逻辑已在 onScroll 中处理，Fling 不再重复触发
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                // 若发生了滑动则不触发长按菜单
                if (moved) return
                val viewWidth = iv.width.takeIf { it > 0 } ?: return
                when {
                    e.x < viewWidth / 3f || e.x > viewWidth * 2f / 3f -> {
                        // 左/右 1/3：仅 AI 生图模式弹保存菜单（保持原行为）
                        if (AppConfig.readAloudAiImage) showSaveMenu()
                    }
                    else -> {
                        // 中 1/3：弹出图片设置菜单（含轮播时间设置）
                        showImageSettingsMenu()
                    }
                }
            }
        })
        iv.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 复位标记
                }
            }
            gesture.onTouchEvent(event)
            true
        }
    }

    /**
     * 切换 AI 历史图片。图库按朗读顺序排列：
     * direction = +1 后一张（朗读顺序的下一张，通常是尚未读到的后续场景）
     * direction = -1 前一张（朗读顺序的上一张，已读过的场景）
     *
     * 滑动后停留在目标图片，并启动 30 秒倒计时，无操作则自动回到当前图片恢复自动生成切换。
     */
    private fun switchAiHistoryImage(direction: Int) {
        if (aiHistoryImages.isEmpty()) {
            aiHistoryImages = AiImageGenerator.getDisplayGallery()
            if (aiHistoryImages.isEmpty()) {
                toastOnUi("暂无历史图片")
                return
            }
            // 按朗读顺序定位当前显示的那张，不能再假设下标 0
            aiHistoryIndex = AiImageGenerator.getGalleryIndexOfCurrent(aiHistoryImages)
        }
        val newIndex = aiHistoryIndex + direction
        if (newIndex < 0) {
            toastOnUi("已是第一张")
            return
        }
        if (newIndex >= aiHistoryImages.size) {
            toastOnUi("已是最后一张")
            return
        }
        aiHistoryIndex = newIndex
        aiUserBrowsing = true
        val file = aiHistoryImages[aiHistoryIndex]
        if (file.exists()) {
            loadIntoBoth(file)
        }
        // 停留在滑动到的图片，启动 30 秒后回当前图片的定时器
        scheduleAiHistoryReset()
    }

    /**
     * 启动 30 秒定时器，到期后回到当前图片（朗读进度对应的那张）并恢复自动生成切换。
     */
    private fun scheduleAiHistoryReset() {
        aiHistoryResetTimer?.cancel()
        aiHistoryResetTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    activity?.runOnUiThread {
                        // 回到朗读进度对应的那张（按朗读顺序重新定位，不是下标 0）
                        aiHistoryImages = AiImageGenerator.getDisplayGallery()
                        aiHistoryIndex = AiImageGenerator.getGalleryIndexOfCurrent(aiHistoryImages)
                        aiUserBrowsing = false
                        val current = aiHistoryImages.getOrNull(aiHistoryIndex)
                        if (current != null && current.exists()) {
                            loadIntoBoth(current)
                        }
                        aiHistoryResetTimer?.cancel()
                        aiHistoryResetTimer = null
                    }
                }
            }, 30000L)
        }
    }

    /** 取消历史浏览回当前图片定时器 */
    private fun cancelAiHistoryReset() {
        aiHistoryResetTimer?.cancel()
        aiHistoryResetTimer = null
        aiUserBrowsing = false
    }

    /**
     * 长按中间弹出图片设置菜单：含轮播时间设置；AI 生图模式额外含保存选项。
     */
    private fun showImageSettingsMenu() {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        items.add("轮播时间设置")
        actions.add { showIntervalSetting() }
        // 图片宽度设置 / 图片与顶部距离设置：与朗读界面设置联通
        items.add("图片宽度设置")
        actions.add { showCoverWidthSetting() }
        items.add("图片与顶部距离设置")
        actions.add { showCoverTopMarginSetting() }
        if (AppConfig.readAloudAiImage) {
            items.add("保存当前图片")
            actions.add { saveCurrentAiImage() }
            items.add("保存全部图片")
            actions.add { showSaveAllMenu() }
            // 缓存数量与清空临时缓存（AI 生图相关，仅开关开启时显示）
            items.add("设置缓存数量")
            actions.add { showCacheCountSetting() }
            items.add("清空临时缓存图片")
            actions.add { confirmClearTempCache() }
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("图片设置")
            .setItems(items.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 设置 AI 生图缓存数量（默认 50）：保存后写入 AppConfig，并立即按新上限清理超量旧图。
     */
    private fun showCacheCountSetting() {
        val editText = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(AppConfig.aiImageCacheCount.toString())
            hint = "缓存图片数量，默认 50"
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("设置缓存数量")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val count = editText.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 50
                AppConfig.aiImageCacheCount = count
                AiImageGenerator.applyCacheLimit()
                toastOnUi("已设为最多缓存 ${count} 张")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 清空 AI 生图临时缓存：确认后删除缓存目录下的所有生图文件，
     * 画廊与内存引用同步重置，并回退显示书籍封面。不影响「保存AI图片」的本地永久图。
     */
    private fun confirmClearTempCache() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("清空临时缓存图片")
            .setMessage("将删除 AI 生图临时缓存（最多缓存的 ${AppConfig.aiImageCacheCount} 张），不影响已保存到本地的图片，确定？")
            .setPositiveButton("清空") { _, _ ->
                val deleted = AiImageGenerator.clearTempCache()
                // 重置画廊，回退到书籍封面（与「AI 生图开启但无缓存」一致）
                aiHistoryImages = emptyList()
                aiHistoryIndex = -1
                aiUserBrowsing = false
                stopImageTimer()
                ReadBook.book?.let { book ->
                    loadWithCrossFade(binding.ivBookCover, BookCover.load(requireContext(), book.getDisplayCover()))
                }
                toastOnUi("已清空 $deleted 张临时缓存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 轮播时间设置：输入秒数（0=不自动轮播），保存后重启轮播定时器。
     */
    private fun showIntervalSetting() {
        val editText = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(AppConfig.readAloudImageInterval.toString())
            hint = "秒数，0=不自动轮播"
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("轮播时间设置（秒）")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val sec = editText.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 5
                AppConfig.readAloudImageInterval = sec
                // 重启轮播定时器以应用新间隔（0 则不轮播，仅手动切换）
                startImageTimer(binding.ivBookCover)
                val tip = if (sec <= 0) "已设为不自动轮播（仅手动切换）" else "已设为 ${sec} 秒轮播"
                toastOnUi(tip)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 图片宽度设置：与朗读界面设置（readAloudCoverWidth）联通。
     * 保存后重新布局上半部分以应用新宽度。
     */
    private fun showCoverWidthSetting() {
        if (AppConfig.readAloudCoverAutoFit) {
            toastOnUi("已开启「封面自动适应屏幕」，手动宽度暂不生效")
            return
        }
        val editText = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(AppConfig.readAloudCoverWidth.toString())
            hint = "宽度（dp），范围 80~600"
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("图片宽度设置（dp）")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val width = editText.text.toString().toIntOrNull()?.coerceIn(80, 600) ?: 240
                AppConfig.readAloudCoverWidth = width
                upTopSection()
                toastOnUi("图片宽度已设为 ${width}dp")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 图片与顶部距离设置：与朗读界面设置（readAloudCoverTopMargin）联通。
     * 保存后重新布局上半部分以应用新顶部距离。
     */
    private fun showCoverTopMarginSetting() {
        if (AppConfig.readAloudCoverAutoFit) {
            toastOnUi("已开启「封面自动适应屏幕」，顶部距离会自动对齐摄像头下方")
            return
        }
        val editText = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(AppConfig.readAloudCoverTopMargin.toString())
            hint = "距离顶部（dp），默认 16"
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("图片与顶部距离（dp）")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val margin = editText.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 16
                AppConfig.readAloudCoverTopMargin = margin
                upTopSection()
                toastOnUi("图片与顶部距离已设为 ${margin}dp")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 保存当前显示的 AI 图片 */
    private fun saveCurrentAiImage() {
        val currentFile = if (aiHistoryImages.isNotEmpty() && aiHistoryIndex in aiHistoryImages.indices) {
            aiHistoryImages[aiHistoryIndex]
        } else {
            AiImageGenerator.getCurrentDisplayImage()
        }
        saveAiImage(currentFile)
    }

    /**
     * 长按 AI 图片：弹出菜单选择保存当前图片或保存全部。
     */
    private fun showSaveMenu() {
        val currentFile = if (aiHistoryImages.isNotEmpty() && aiHistoryIndex in aiHistoryImages.indices) {
            aiHistoryImages[aiHistoryIndex]
        } else {
            AiImageGenerator.getCurrentDisplayImage()
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("保存图片")
            .setItems(arrayOf("保存当前图片", "保存全部图片")) { _, which ->
                when (which) {
                    0 -> saveAiImage(currentFile)
                    1 -> showSaveAllMenu()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 长按 AI 图片弹出菜单：确认是否保存所有历史图片。
     */
    private fun showSaveAllMenu() {
        val allImages = AiImageGenerator.getDisplayGallery()
        if (allImages.isEmpty()) {
            toastOnUi("暂无 AI 生图可保存")
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("保存所有 AI 生图")
            .setMessage("将保存 ${allImages.size} 张历史图片到 /storage/emulated/0/Download/AI生图/，是否继续？")
            .setPositiveButton("保存全部") { _, _ ->
                saveAllAiImages(allImages)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 保存单张 AI 图片到指定目录 */
    private fun saveAiImage(file: File?) {
        if (file == null || !file.exists()) {
            toastOnUi("图片不存在")
            return
        }
        val targetDir = java.io.File("/storage/emulated/0/Download/AI生图/")
        if (!targetDir.exists()) targetDir.mkdirs()
        // 用缓存文件的时间戳命名，重复保存也只会覆盖同名文件，不会产生重复
        val target = java.io.File(targetDir, "${file.lastModified()}.png")
        try {
            file.copyTo(target, overwrite = true)
            toastOnUi("已保存到 ${target.absolutePath}")
        } catch (e: Exception) {
            toastOnUi("保存失败：${e.localizedMessage}")
        }
    }

    /** 保存所有 AI 历史图片到指定目录 */
    private fun saveAllAiImages(images: List<File>) {
        if (images.isEmpty()) {
            toastOnUi("暂无图片可保存")
            return
        }
        val targetDir = java.io.File("/storage/emulated/0/Download/AI生图/")
        if (!targetDir.exists()) targetDir.mkdirs()
        var success = 0
        images.forEach { f ->
            if (f.exists()) {
                try {
                    // 用缓存文件的时间戳命名，重复保存也只会覆盖同名文件，不会产生重复
                    val target = java.io.File(targetDir, "${f.lastModified()}.png")
                    f.copyTo(target, overwrite = true)
                    success++
                } catch (_: Exception) {}
            }
        }
        toastOnUi("已保存 $success/${images.size} 张到 ${targetDir.absolutePath}")
    }

    private fun upTimerText(timeMinute: Int) {
        if (timeMinute < 0) {
            binding.tvTimer.text = requireContext().getString(R.string.timer_m, 0)
        } else {
            binding.tvTimer.text = requireContext().getString(R.string.timer_m, timeMinute)
        }
    }

    @SuppressLint("SetTextIsNull")
    private fun upTtsSpeechRateText(value: Int) {
        // 精细刻度：显示系数 = (进度 + 50) / 100，如进度 52 → 1.02
        binding.tvTtsSpeedValue.text = String.format("%.2f", (value + 50) / 100f)
    }

    /** 刷新「旁白」位置的文字：未设置显示「默认旁白」，已设置显示该配置的名字 */
    private fun upNarrationText() {
        val book = ReadBook.book ?: return
        val presetId = book.config.ttsNarrationConfigId
        binding.tvTtsNarration.text = if (presetId.isNullOrBlank()) {
            getString(io.legado.app.R.string.narration_default)
        } else {
            val config = runCatching {
                io.legado.app.help.audiobook.JReadVoiceEngine
                    .findConfigById(requireContext(), presetId)
            }.getOrNull()
            // 预设被删除时也显示「默认旁白」，与合成时的回退行为保持一致
            config?.displayName?.ifBlank { config.voiceTag }?.ifBlank { null }
                ?: getString(io.legado.app.R.string.narration_default)
        }
    }

    /**
     * 本书专属旁白选择弹窗。
     *
     * 列出**所有旁白标签的配置**，与是否禁用无关（旁白默认只启用一个，其余是禁用态，
     * 但仍可作为本书旁白，所以不显示「已禁用」字样，避免误解为不可用）。
     * 第一项是「默认旁白(实际生效的那条)」，选它等于清除本书预设。
     * 每项右侧带编辑按钮，可直接进入该旁白的 TTS 配置编辑页改音色/语速。
     */
    private fun showNarrationPicker() {
        val context = context ?: return
        val configs = runCatching {
            io.legado.app.help.audiobook.JReadVoiceEngine.listNarrationConfigs(context)
        }.getOrDefault(emptyList())
        // 当前真正生效的默认旁白（未设本书预设时合成实际用的那条）。
        // findPlayableConfig 内部对旁白标签走的就是默认挑选逻辑。
        val defaultConfig = configs.firstOrNull { it.enabled }
            ?: runCatching {
                io.legado.app.help.audiobook.JReadVoiceEngine
                    .findPlayableConfig(context, "旁白")
            }.getOrNull()
        val defaultLabel = defaultConfig?.displayName
            ?.ifBlank { defaultConfig.voiceTag }?.ifBlank { null } ?: "自动"

        val names = mutableListOf("默认旁白($defaultLabel)")
        val ids = mutableListOf("")
        configs.forEach { config ->
            names.add(config.displayName.ifBlank { config.voiceTag })
            ids.add(config.id)
        }
        val currentId = ReadBook.book?.config?.ttsNarrationConfigId.orEmpty()
        val checked = ids.indexOf(currentId).coerceAtLeast(0)

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        var pickerDialog: androidx.appcompat.app.AlertDialog? = null
        names.forEachIndexed { index, name ->
            io.legado.app.databinding.ItemNarrationPickBinding.inflate(layoutInflater).apply {
                rbPick.isChecked = index == checked
                tvName.text = name
                val configId = ids[index]
                // 第一项是「默认旁白」，不对应具体配置，没有试听/编辑入口
                ivPlay.visible(index > 0)
                ivEdit.visible(index > 0)
                ivPlay.setOnClickListener {
                    val config = configs.getOrNull(index - 1) ?: return@setOnClickListener
                    previewNarrationConfig(config)
                }
                ivEdit.setOnClickListener { openTtsConfigEditor(configId) }
                // 点行即选中并关闭弹窗：旁白切换是一次性操作，无需再确认
                root.setOnClickListener {
                    pickerDialog?.dismiss()
                    applyNarrationPreset(configId)
                }
            }.root.let { container.addView(it) }
        }
        android.widget.TextView(context).apply {
            text = "旁白标签默认只启用一个，其余为禁用态；指定为本书旁白后即可正常合成。"
            textSize = 11f
            setPadding(36, 10, 36, 4)
        }.let { container.addView(it) }

        pickerDialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("本书旁白")
            .setView(container)
            .setNegativeButton("取消", null)
            .create()
        pickerDialog.show()
    }

    /**
     * 试听某条旁白配置：与配置列表条目上的试听同一套链路
     * （合成 → 后处理参数 → 快节奏 → 播放），保证听到的就是朗读时的效果。
     */
    private fun previewNarrationConfig(
        config: io.legado.app.help.audiobook.JReadVoiceEngine.VoiceConfig
    ) {
        val context = context ?: return
        toastOnUi("正在试听: ${config.voiceTag}")
        narrationPreviewPlayer?.release()
        CoroutineScope(Dispatchers.Main).launch {
            val requestId = java.util.UUID.randomUUID().toString()
            val pointerJson = org.json.JSONObject().put("voiceTag", config.voiceTag).toString()
            val audioBytes = withContext(Dispatchers.IO) {
                runCatching {
                    io.legado.app.help.audiobook.JReadVoiceEngine.synthesizeConfigLineAudio(
                        context, config,
                        config.previewText
                            .ifBlank { io.legado.app.help.audiobook.JReadVoiceEngine.DEFAULT_PREVIEW_TEXT },
                        pointerJson, requestId
                    )
                }.getOrNull()
            }
            if (audioBytes == null || audioBytes.isEmpty()) {
                toastOnUi("试听失败")
                return@launch
            }
            val postParams = io.legado.app.help.audiobook.JReadVoiceEngine
                .resolvePostAudioParams(context, config)
            val needSilenceSkip = io.legado.app.help.config.AppConfig.ttsSilenceSkipEnabled
            val silenceSkip = io.legado.app.utils.AudioDecodeUtil.SilenceSkipConfig(
                enabled = needSilenceSkip,
                minDurationMs = io.legado.app.help.config.AppConfig.ttsSilenceSkipMinMs,
            )
            val playedBytes = if (postParams.isDefault() && !needSilenceSkip) {
                audioBytes
            } else {
                withContext(Dispatchers.IO) {
                    io.legado.app.utils.AudioDecodeUtil
                        .decodeToStandardWav(
                            audioBytes,
                            postParams = postParams,
                            silenceSkip = silenceSkip
                        )
                } ?: audioBytes
            }
            if (!isAdded) return@launch
            val tempFile = withContext(Dispatchers.IO) {
                java.io.File(requireContext().cacheDir, "narration_preview_$requestId.wav")
                    .apply { writeBytes(playedBytes) }
            }
            narrationPreviewPlayer?.release()
            narrationPreviewPlayer = android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(tempFile.absolutePath)
                prepare()
                setOnCompletionListener { it.release(); narrationPreviewPlayer = null }
                start()
            }
        }
    }

    /** 打开某条 TTS 配置的编辑页（改音色 / 语速等） */
    private fun openTtsConfigEditor(configId: String) {
        if (configId.isBlank()) return
        runCatching {
            startActivity(
                Intent(
                    requireContext(),
                    io.legado.app.ui.tts.plugin.TtsConfigEditorActivity::class.java
                ).apply {
                    putExtra(
                        io.legado.app.ui.tts.plugin.TtsConfigEditorActivity.EXTRA_CONFIG_ID,
                        configId
                    )
                    putExtra(
                        io.legado.app.ui.tts.plugin.TtsConfigEditorActivity.EXTRA_IS_NEW,
                        false
                    )
                }
            )
        }.onFailure { toastOnUi("打不开配置编辑页：${it.localizedMessage}") }
    }

    /** 应用本书旁白预设并刷新显示 */
    private fun applyNarrationPreset(selectedId: String) {
        val book = ReadBook.book ?: return
        if (book.config.ttsNarrationConfigId == selectedId) {
            // 已选中仍要收起弹窗
            return
        }
        book.config.ttsNarrationConfigId = selectedId
        upNarrationText()
        // 旁白是按段合成的，改完立刻让新设置对后续段落生效
        if (!BaseReadAloudService.pause) {
            io.legado.app.model.ReadAloud.pause(requireContext())
            io.legado.app.model.ReadAloud.resume(requireContext())
        }
        // 落库用一次性 IO 作用域（与本文件其它处一致，避免 DialogFragment
        // 的 lifecycleScope 在 dismiss 后取消导致写入丢失）
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { appDb.bookDao.update(book) }
        }
    }

    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(requireContext())
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    /**
     * 弹幕层初始化：按开关显示浮层；弹窗重建（重开朗读面板）时
     * 直接从 DanmakuStore 恢复本章弹幕，无需等服务重新生成。
     */
    private fun initDanmaku() {
        binding.danmakuView.visibility =
            if (AppConfig.danmakuEnabled) View.VISIBLE else View.GONE
        if (!AppConfig.danmakuEnabled) return
        val book = ReadBook.book ?: return
        val title = ReadBook.curTextChapter?.title ?: return
        danmakuKey = DanmakuStore.key
        currentDanmaku = DanmakuStore.get("${book.name}|$title")
    }

    /** 场景特效层初始化：按开关显示；弹窗重建时从 Store 恢复本章结果 */
    private fun initSceneEffect() {
        sceneEffectViewRef = binding.sceneEffectView
        syncSceneEffectVisibility()
        if (!AppConfig.sceneEffectEnabled) return
        val book = ReadBook.book ?: return
        val title = ReadBook.curTextChapter?.title ?: return
        sceneEffectKey = SceneEffectStore.key
        currentSceneEffects = SceneEffectStore.get("${book.name}|$title")
    }

    /** 朗读弹窗使用默认常驻层；半屏 GONE 时由 View 自己停帧，回全屏自动续播 */
    private fun syncSceneEffectVisibility() {
        val target = sceneEffectViewRef ?: return
        val enabled = AppConfig.sceneEffectEnabled
        target.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) {
            target.setIdleEffect(VnBackgroundManager.defaultIdleCue())
        } else {
            target.clearSceneEffects()
            target.setIdleEffect(null)
        }
    }

    override fun observeLiveBus() {
        observeEvent<Int>(EventBus.ALOUD_STATE) { upPlayState() }
        observeEvent<Int>(EventBus.READ_ALOUD_DS) { binding.seekTimer.progress = it }
        observeEvent<String>(EventBus.READ_ALOUD_SUBTITLE) {
            binding.tvReadAloudSubtitle.text = it
        }
        observeEvent<Int>(EventBus.READ_ALOUD_CHAPTER_PROGRESS) {
            if (!isSeekingChapterProgress) {
                binding.seekChapterProgress.progress = it
                binding.tvChapterProgressStart.text = "${(it / 10f).toInt()}%"
            }
        }
        observeEvent<Boolean>(EventBus.READ_ALOUD_CONFIG_CHANGED) {
            upTopSection()
            syncSceneEffectVisibility()
        }
        observeEvent<String>(EventBus.READ_ALOUD_CHAPTER_CHANGED) {
            // 章节变化时更新章节名
            binding.tvChapterName.text = ReadBook.curTextChapter?.title ?: it
            // 切章后上一章的弹幕锚点已失效，清空待发射列表与在屏弹幕
            currentDanmaku = emptyList()
            firedAnchors.clear()
            binding.danmakuView.clearDanmaku()
            currentSceneEffects = emptyList()
            firedSceneAnchors.clear()
            binding.sceneEffectView.clearSceneEffects()
        }
        // 音效联动画面：消费音效播放收口的事件，不新增 AI 调用、不新建词表配置
        observeEvent<io.legado.app.model.SoundEffectFired>(EventBus.SOUND_EFFECT_FIRED) { fired ->
            if (!AppConfig.sceneEffectEnabled) return@observeEvent
            val cue = io.legado.app.model.SoundEffectSceneMapper.map(fired)
                ?: return@observeEvent
            if (cue.burst) {
                binding.sceneEffectView.burst(cue)
            } else {
                binding.sceneEffectView.show(cue)
            }
        }
        observeEvent<SceneEffectReady>(EventBus.SCENE_EFFECT_READY) { ready ->
            if (ready.key != sceneEffectKey) {
                sceneEffectKey = ready.key
                firedSceneAnchors.clear()
                binding.sceneEffectView.clearSceneEffects()
            }
            currentSceneEffects = ready.items
        }
        observeEvent<VnParagraph>(EventBus.VN_PARAGRAPH) { p ->
            // 新章第一段：清掉上一章可能还在播的特效
            if (p.index == 0) {
                firedSceneAnchors.clear()
                binding.sceneEffectView.clearSceneEffects()
            }
            if (!AppConfig.sceneEffectEnabled || currentSceneEffects.isEmpty()) return@observeEvent
            currentSceneEffects.filter { it.para <= p.index && firedSceneAnchors.add(it.para) }
                .forEach { cue ->
                    val keyAtSchedule = sceneEffectKey
                    // 与弹幕一致延后 1 秒：读到描写时才出现，不提前剧透
                    binding.sceneEffectView.postEffectDelayed(1000L) { target ->
                        if (sceneEffectKey == keyAtSchedule) target.show(cue)
                    }
                }
        }
        observeEvent<DanmakuReady>(EventBus.DANMAKU_READY) { ready ->
            if (ready.key != danmakuKey) {
                // 切章：重置已发射记录与在屏弹幕
                danmakuKey = ready.key
                firedAnchors.clear()
                binding.danmakuView.clearDanmaku()
            }
            // 区间增量结果全量下发，只更新列表，不打断在屏弹幕
            currentDanmaku = ready.items
        }
        observeEvent<VnParagraph>(EventBus.VN_PARAGRAPH) { p ->
            if (!AppConfig.danmakuEnabled || currentDanmaku.isEmpty()) return@observeEvent
            // 锚点 <= 当前段落即发射（追发错过的），firedAnchors 保证每条只发一次。
            // 发射统一延后 1 秒：AI 弹幕按段落起点触发，情节常在段落中后段才读到，
            // 立即发射会「剧透」——延后让弹幕出现时朗读刚好讲到这里。
            currentDanmaku.filter { it.para <= p.index && firedAnchors.add(it.para) }
                .forEach { item ->
                    val keyAtSchedule = danmakuKey
                    binding.danmakuView.postDanmakuDelayed(DANMAKU_LAUNCH_DELAY_MS) { target ->
                        // 延迟期间切章 / 关闭弹幕开关：丢弃，避免旧弹幕在新章开头冒出来
                        if (danmakuKey == keyAtSchedule) target.show(item.text)
                    }
                }
        }
        observeEvent<String>(EventBus.AI_IMAGE_CHANGED) { imagePath ->
            if (imagePath.isNotBlank() && File(imagePath).exists()) {
                // 用户正在手动浏览历史图片时不打断，仅刷新列表数据
                if (aiUserBrowsing) {
                    aiHistoryImages = AiImageGenerator.getDisplayGallery()
                    return@observeEvent
                }
                stopImageTimer() // AI 生图开启时不进行本地图片轮换
                loadIntoBoth(File(imagePath))
                // 新图片生成，按朗读顺序重新定位当前图，并取消回最新定时器
                cancelAiHistoryReset()
                aiHistoryImages = AiImageGenerator.getDisplayGallery()
                aiHistoryIndex = AiImageGenerator.getGalleryIndexOfCurrent(aiHistoryImages)
            } else {
                // 恢复书籍封面或本地图片
                ReadBook.book?.let { book ->
                    loadBookCoverOrLocalImages(book, binding.ivBookCover)
                }
                cancelAiHistoryReset()
                aiHistoryImages = emptyList()
                aiHistoryIndex = -1
            }
        }
    }

    /**
     * 打开当前 TTS 引擎对应的应用
     */
    @SuppressLint("SetTextIsNull")
    private fun openCurrentTtsApp() {
        val ttsEngine = ReadAloud.ttsEngine

        // 1. 转发器模式（数字 ID）
        if (!ttsEngine.isNullOrBlank() && StringUtils.isNumeric(ttsEngine)) {
            val httpTtsId = ttsEngine.toLongOrNull()
            if (httpTtsId != null) {
                val httpTts = appDb.httpTTSDao.get(httpTtsId)
                val packageName = httpTts?.ttsPackageName
                if (!packageName.isNullOrBlank()) {
                    openTtsAppByPackageName(packageName)
                } else {
                    toastOnUi("当前为转发器模式，请在角色管理中配置TTS包名")
                }
            } else {
                openSystemTtsApp()
            }
            return
        }

        // 2. 系统 TTS 模式（空/null 或 JSON）
        // 优先从 JSON 解析包名，其次使用手动设置的包名
        val packageName = when {
            ttsEngine.isNullOrBlank() -> AppConfig.sysTtsPackageName
            ttsEngine.startsWith("{") -> {
                GSON.fromJsonObject<io.legado.app.lib.dialogs.SelectItem<String>>(ttsEngine)
                    .getOrNull()?.value?.takeIf { it.isNotBlank() }
                    ?: AppConfig.sysTtsPackageName
            }
            else -> ttsEngine.takeIf { it.isNotBlank() }
        }

        if (!packageName.isNullOrBlank()) {
            openTtsAppByPackageName(packageName)
        } else {
            openSystemTtsApp()
        }
    }

    /**
     * 打开系统 TTS 设置页面
     */
    private fun openSystemTtsApp() {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            toastOnUi("无法打开系统设置")
        }
    }

    /**
     * 根据包名打开 TTS 应用
     */
    private fun openTtsAppByPackageName(packageName: String) {
        try {
            val packageManager = requireContext().packageManager
            // 尝试获取应用启动 Intent
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            } else {
                // 如果没有启动 Intent，尝试打开应用详情页面
                openAppDetailsPage(packageName)
            }
        } catch (e: Exception) {
            toastOnUi("无法打开应用: $packageName")
        }
    }

    /**
     * 根据 TTS 引擎名称打开应用
     */
    private fun openTtsAppByEngineName(engineName: String) {
        try {
            val packageManager = requireContext().packageManager
            // 获取所有 TTS 引擎
            val ttsIntent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
            val resolveInfos = packageManager.queryIntentActivities(ttsIntent, 0)

            for (info in resolveInfos) {
                // 获取引擎的包名
                val enginePackageName = info.activityInfo.packageName
                try {
                    val appInfo = packageManager.getApplicationInfo(enginePackageName, 0)
                    val appName = packageManager.getApplicationLabel(appInfo).toString()
                    // 检查是否匹配（模糊匹配）
                    if (appName.contains(engineName) || engineName.contains(appName)) {
                        openTtsAppByPackageName(enginePackageName)
                        return
                    }
                } catch (e: Exception) {
                    continue
                }
            }

            // 如果没找到匹配，尝试直接打开应用详情页
            openAppDetailsPage(engineName)
        } catch (e: Exception) {
            toastOnUi("无法找到对应的 TTS 应用")
        }
    }

    /**
     * 打开应用详情页面
     */
    private fun openAppDetailsPage(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // 如果 ACTION_APPLICATION_DETAILS_SETTINGS 不可用，尝试打开市场页面
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("market://details?id=$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e2: Exception) {
                toastOnUi("无法打开应用详情")
            }
        }
    }

    private fun showAiImageSettings() {
        AiImageSettingsDialog().show(childFragmentManager, "aiImageSettingsDialog")
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun onClickReadAloud()
        fun showBgmConfig()
        fun finish()
    }
}