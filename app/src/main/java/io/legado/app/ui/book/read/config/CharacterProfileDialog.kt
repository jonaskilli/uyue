package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.google.gson.JsonParser
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.CharacterProfile
import io.legado.app.databinding.DialogCharacterProfileBinding
import io.legado.app.model.CharacterProfileManager
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers

/**
 * AI 角色档案详情弹窗。
 *
 * 展示角色的完整人设（性别年龄/身份/性格/说话风格/特征/关系/履历/时间线），
 * 支持手动编辑、AI 重新生成、删除，以及跳转「与角色对话」。
 */
class CharacterProfileDialog : BaseDialogFragment(R.layout.dialog_character_profile) {

    private val binding by viewBinding(DialogCharacterProfileBinding::bind)

    private val bookName get() = requireArguments().getString(EXTRA_BOOK) ?: "默认"
    private val characterName get() = requireArguments().getString(EXTRA_CHARACTER) ?: ""

    private var profile: CharacterProfile? = null

    /** 选一张本地图片作为角色头像 */
    private val selectLocalAvatar =
        registerForActivityResult(io.legado.app.utils.SelectImageContract()) { result ->
            val uri = result.uri ?: return@registerForActivityResult
            importLocalAvatar(uri)
        }

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvTitle.text = "角色档案 · $characterName"
        binding.tvChat.setOnClickListener {
            // 无档案时后台建档并补立绘，不阻塞进入对话页
            io.legado.app.model.AiProfileService.ensureProfileAndAvatar(
                bookName, characterName,
                io.legado.app.model.ReadBook.durChapterIndex,
                io.legado.app.model.ReadBook.curTextChapter?.chapter?.title.orEmpty()
            )
            dismissAllowingStateLoss()
            RoleChatActivity.start(requireActivity(), bookName, characterName)
        }
        binding.tvDelete.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("删除档案")
                .setMessage("将删除「$characterName」的档案（朗读时不再自动重建），确定？")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    CharacterProfileManager.delete(bookName, characterName)
                    toastOnUi("已删除档案")
                    dismissAllowingStateLoss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.tvSettings.setOnClickListener {
            AiProfileDialog().show(childFragmentManager, "aiProfileDialog")
        }
        setupTemplateSpinner()
        binding.tvEdit.setOnClickListener { showEditDialog() }
        binding.tvRegen.setOnClickListener { regenerate() }
        binding.tvPortrait.setOnClickListener { generatePortrait() }
        binding.tvLocalAvatar.setOnClickListener { selectLocalAvatar.launch(null) }
        // 点立绘图片 → 立绘历史：重新生成过的旧图都存着，可在这里换回来或删除
        binding.ivPortrait.setOnClickListener { showAvatarHistory() }
        // 历史里换回某张立绘后，刷新本弹窗的预览
        childFragmentManager.setFragmentResultListener(
            AvatarHistoryDialog.RESULT_KEY, viewLifecycleOwner
        ) { _, _ -> loadPortrait() }
        loadProfile()
        loadPortrait()
    }

    private fun loadProfile() {
        execute {
            CharacterProfileManager.getProfile(bookName, characterName)
                ?: CharacterProfileManager.getProfiles(bookName)
                    .firstOrNull { p ->
                        p.aliases.split("|").map { it.trim() }.contains(characterName)
                    }
        }.onSuccess(Dispatchers.Main) { p ->
            // 查询是异步的，回调时弹窗可能已关闭：先判 View 存活
            if (!isAdded || view == null) return@onSuccess
            fill(p)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun fill(p: CharacterProfile?) {
        profile = p
        if (p == null) {
            binding.tvGenderAge.text = ""
            binding.tvIdentity.text = "该角色暂无档案。"
            binding.tvPersonality.text =
                "可在朗读过一段该角色的内容后，点「补全信息」让 AI 建档；朗读中也会自动补全。"
            binding.tvSpeakingStyle.text = ""
            binding.tvTraits.text = ""
            binding.tvRelations.text = ""
            binding.tvBackground.text = ""
            binding.tvTimeline.text = ""
            binding.tvMeta.text = ""
            return
        }
        val genderAge = listOf(p.gender, p.age).filter { it.isNotBlank() }.joinToString("")
        binding.tvGenderAge.text = genderAge.ifBlank { "性别年龄未知" }
        binding.tvIdentity.text = "【身份】${p.identity.ifBlank { "待补全" }}"
        binding.tvPersonality.text = "【性格】${p.personality.ifBlank { "待补全" }}"
        binding.tvSpeakingStyle.text = "【说话风格】${p.speakingStyle.ifBlank { "待补全" }}"
        binding.tvTraits.text = "【特征】${prettyJsonArray(p.traits).ifBlank { "待补全" }}"
        binding.tvRelations.text = "【关系】${prettyRelations(p.relations).ifBlank { "待补全" }}"
        binding.tvBackground.text = "【背景】${p.background.ifBlank { "待补全" }}"
        binding.tvTimeline.text = prettyTimeline(p.timeline)
        binding.tvMeta.text =
            "出场 ${p.mentionCount} 次 · 信息充分度 ${(p.confidence * 100).toInt()}%" +
                    (if (p.lastChapter.isNotBlank()) " · 最近：${p.lastChapter}" else "")
    }

    private fun showEditDialog() {
        // 无档案（如旁白）也允许编辑：以空档案进入，保存时创建档案记录。
        // 这样旁白可以自定义身份/性格/背景，再「生成立绘」按这些内容生成。
        val p = profile ?: CharacterProfile(
            bookUrl = CharacterProfileManager.bookKey(bookName),
            name = characterName
        )
        val ctx = requireContext()
        val scroll = android.widget.ScrollView(ctx)
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val fields = linkedMapOf(
            "性别（男/女）" to p.gender,
            "年龄段（童/少年/青年/中年/老年）" to p.age,
            "身份" to p.identity,
            "性格" to p.personality,
            "说话风格" to p.speakingStyle,
            "背景履历" to p.background
        )
        val edits = HashMap<String, android.widget.EditText>()
        fields.forEach { (label, value) ->
            layout.addView(android.widget.TextView(ctx).apply { text = label; textSize = 12f })
            val et = android.widget.EditText(ctx).apply {
                setText(value)
                textSize = 13f
            }
            layout.addView(et)
            edits[label] = et
        }
        scroll.addView(layout)
        android.app.AlertDialog.Builder(ctx)
            .setTitle("编辑档案 · $characterName")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val (g, a) = CharacterProfileManager.splitGenderAge(
                    edits["性别（男/女）"]?.text?.toString()?.trim().orEmpty(),
                    edits["年龄段（童/少年/青年/中年/老年）"]?.text?.toString()?.trim().orEmpty()
                )
                val updated = p.copy(
                    gender = g,
                    age = a,
                    identity = edits["身份"]?.text?.toString()?.trim().orEmpty(),
                    personality = edits["性格"]?.text?.toString()?.trim().orEmpty(),
                    speakingStyle = edits["说话风格"]?.text?.toString()?.trim().orEmpty(),
                    background = edits["背景履历"]?.text?.toString()?.trim().orEmpty(),
                    confidence = maxOf(p.confidence, 0.8f)
                )
                CharacterProfileManager.save(updated)
                fill(updated)
                toastOnUi("已保存")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun regenerate() {
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "正在让 AI 补全档案…（只补空缺，不覆盖已有信息；可能需要几十秒）"
        binding.tvRegen.isEnabled = false
        val chapterIndex = io.legado.app.model.ReadBook.durChapterIndex
        val chapterTitle = io.legado.app.model.ReadBook.curTextChapter?.chapter?.title.orEmpty()
        CharacterProfileManager.regenerate(
            bookName, characterName,
            if (chapterIndex >= 0 && chapterTitle.isNotBlank()) chapterIndex else -1,
            chapterTitle
        ) { p, error ->
            // AI 耗时几十秒，回调时弹窗可能已关闭（View 已销毁）：
            // 访问 binding 会抛 IllegalStateException，必须先判 View 存活
            if (isAdded && view != null) {
                binding.tvRegen.isEnabled = true
                if (p == null) {
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.text = "建档失败：$error"
                    toastOnUi("建档失败：$error")
                } else {
                    binding.tvStatus.visibility = View.GONE
                    fill(p)
                    toastOnUi("档案已更新")
                }
            }
        }
    }

    /**
     * 生图模板选择框：列出全部生图模板，选中后立绘生成固定使用该模板。
     * 选择持久化到 AppConfig（重启不丢）；第一项「默认模板」= 跟随默认。
     */
    private fun setupTemplateSpinner() {
        execute {
            io.legado.app.data.appDb.aiImageTemplateDao.getAllList()
        }.onSuccess(Dispatchers.Main) { templates ->
            // 查询是异步的，回调时弹窗可能已关闭：先判 View 存活
            if (!isAdded || view == null) return@onSuccess
            val items = mutableListOf("默认模板（跟随 AI 生图设置）")
            items.addAll(templates.map { it.name.ifBlank { "未命名模板" } })
            val adapter = android.widget.ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                items
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            binding.spTemplate.adapter = adapter
            // 回显已保存的选择（重启后保持）
            val savedId = io.legado.app.help.config.AppConfig.aiPortraitTemplateId
            val savedIndex = if (savedId == 0L) 0 else templates.indexOfFirst { it.id == savedId } + 1
            binding.spTemplate.setSelection(if (savedIndex in 1..templates.size) savedIndex else 0)
            binding.spTemplate.onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        val templateId = if (position <= 0) 0L else templates.getOrNull(position - 1)?.id ?: 0L
                        io.legado.app.help.config.AppConfig.aiPortraitTemplateId = templateId
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
        }
    }

    /** 按（性别年龄 + 档案人设）生成角色立绘，成功后自动被「与角色对话」和气泡复用 */
    /**
     * 选中的本地图片 → 覆盖当前立绘。
     *
     * 读字节在后台线程做（图片可能几 MB），期间禁用按钮防重复点击；
     * 写入复用 AI 立绘的缓存文件，所以对话页/气泡头像会立刻生效。
     */
    private fun importLocalAvatar(uri: android.net.Uri) {
        binding.tvLocalAvatar.isEnabled = false
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "正在导入本地图片…"
        execute {
            requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.onSuccess(Dispatchers.Main) { bytes ->
            if (!isAdded || view == null) return@onSuccess
            binding.tvLocalAvatar.isEnabled = true
            if (bytes == null || bytes.isEmpty()) {
                binding.tvStatus.text = "读取图片失败"
                return@onSuccess
            }
            execute {
                io.legado.app.model.AiImageGenerator
                    .setLocalAvatar(bookName, characterName, bytes)
            }.onSuccess(Dispatchers.Main) { file ->
                // 导入耗时，回调时弹窗可能已关闭：先判 View 存活
                if (!isAdded || view == null) return@onSuccess
                if (file == null) {
                    binding.tvStatus.text = "设置本地头像失败"
                    toastOnUi("设置本地头像失败")
                } else {
                    binding.tvStatus.visibility = View.GONE
                    loadPortrait()
                    toastOnUi("已设为本地头像，原头像可在立绘历史里换回")
                }
            }
        }
    }

    /** 点立绘图片：打开立绘历史，可换回旧图或删除历史 */
    private fun showAvatarHistory() {
        AvatarHistoryDialog.newInstance(bookName, characterName)
            .show(childFragmentManager, "avatarHistoryDialog")
    }

    private fun generatePortrait() {
        val p = profile
        // 已有立绘：确认后重新生成（覆盖旧图）；无立绘：直接生成
        val hasCached = io.legado.app.model.AiImageGenerator
            .getCachedAvatar(bookName, characterName) != null
        if (hasCached) {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("重新生成立绘")
                .setMessage("「$characterName」已有立绘，重新生成将覆盖现有图片，确定？")
                .setPositiveButton("重新生成") { _, _ -> doGeneratePortrait(regenerate = true) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            doGeneratePortrait(regenerate = false)
        }
    }

    private fun doGeneratePortrait(regenerate: Boolean) {
        val p = profile
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = if (regenerate) {
            "正在重新生成角色立绘…（覆盖旧图，可能需要几十秒）"
        } else {
            "正在生成角色立绘…（可能需要几十秒）"
        }
        binding.tvPortrait.isEnabled = false
        val customPrompt = runCatching {
            io.legado.app.model.CharacterProfileManager.getTemplate()?.imagePrompt?.trim().orEmpty()
        }.getOrDefault("")
        io.legado.app.model.AiImageGenerator.generateCharacterAvatar(
            characterName = characterName,
            genderAge = listOf(p?.gender.orEmpty(), p?.age.orEmpty())
                .filter { it.isNotBlank() }.joinToString(""),
            bookName = bookName,
            identity = p?.identity.orEmpty(),
            personality = p?.personality.orEmpty(),
            background = p?.background.orEmpty(),
            regenerate = regenerate,
            templateId = io.legado.app.help.config.AppConfig.aiPortraitTemplateId,
            customPrompt = customPrompt,
            onSuccess = { file ->
                // 生成耗时几十秒，回调时弹窗可能已关闭（View 已销毁）：
                // 访问 binding 会抛 IllegalStateException，必须先判 View 存活
                if (isAdded && view != null) {
                    binding.tvPortrait.isEnabled = true
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.text = "立绘已生成：${file.name}"
                    loadPortrait()
                    toastOnUi("立绘已生成，与角色对话页将自动使用")
                }
            },
            onFailure = { e ->
                if (isAdded && view != null) {
                    binding.tvPortrait.isEnabled = true
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.text = "立绘生成失败：${e.localizedMessage}"
                }
            }
        )
    }

    /**
     * 顶部立绘预览：显示**正方形**（从图片最顶端裁剪，保证看得到头部），
     * 居中不铺满两侧；无立绘显示默认人形占位。
     */
    private fun loadPortrait() {
        val file = io.legado.app.model.AiImageGenerator.getCachedAvatar(bookName, characterName)
        if (file == null) {
            binding.ivPortrait.scaleType = ImageView.ScaleType.CENTER_INSIDE
            binding.ivPortrait.setImageResource(R.drawable.ic_portrait_person)
            return
        }
        execute {
            io.legado.app.model.AiImageGenerator.decodeAvatarTopSquare(file, 440)
        }.onSuccess(Dispatchers.Main) { bmp ->
            // 解码是异步的，回调时弹窗可能已关闭：先判 View 存活
            if (!isAdded || view == null) return@onSuccess
            if (bmp != null) {
                binding.ivPortrait.scaleType = ImageView.ScaleType.FIT_CENTER
                binding.ivPortrait.setImageBitmap(bmp)
            } else {
                binding.ivPortrait.scaleType = ImageView.ScaleType.CENTER_INSIDE
                binding.ivPortrait.setImageResource(R.drawable.ic_portrait_person)
            }
        }
    }

    // ---- JSON 数组字段的展示美化 ----

    private fun prettyJsonArray(json: String): String = runCatching {
        JsonParser.parseString(json).asJsonArray
            .mapNotNull { e -> if (e.isJsonPrimitive) e.asString else null }
            .joinToString("；")
    }.getOrDefault("")

    private fun prettyRelations(json: String): String = runCatching {
        JsonParser.parseString(json).asJsonArray
            .mapNotNull { e ->
                val o = e.asJsonObject ?: return@mapNotNull null
                val t = o.get("target")?.asString.orEmpty()
                val r = o.get("relation")?.asString.orEmpty()
                if (t.isBlank()) null else "$t（${r.ifBlank { "相关" }}）"
            }.joinToString("；")
    }.getOrDefault("")

    private fun prettyTimeline(json: String): String = runCatching {
        val items = JsonParser.parseString(json.ifBlank { "[]" }).asJsonArray
            .mapNotNull { e ->
                val o = e.asJsonObject ?: return@mapNotNull null
                val c = o.get("chapter")?.asString.orEmpty()
                val ev = o.get("event")?.asString.orEmpty()
                if (ev.isBlank()) null else "${c.ifBlank { "·" }}：$ev"
            }
        if (items.isEmpty()) "" else "【经历】\n" + items.joinToString("\n")
    }.getOrDefault("")

    companion object {
        private const val EXTRA_BOOK = "bookName"
        private const val EXTRA_CHARACTER = "characterName"

        fun newInstance(bookName: String, characterName: String): CharacterProfileDialog {
            return CharacterProfileDialog().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_BOOK, bookName)
                    putString(EXTRA_CHARACTER, characterName)
                }
            }
        }
    }
}
