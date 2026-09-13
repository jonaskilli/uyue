package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvTitle.text = "角色档案 · $characterName"
        binding.tvChat.setOnClickListener {
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
        loadProfile()
    }

    private fun loadProfile() {
        execute {
            CharacterProfileManager.getProfile(bookName, characterName)
                ?: CharacterProfileManager.getProfiles(bookName)
                    .firstOrNull { p ->
                        p.aliases.split("|").map { it.trim() }.contains(characterName)
                    }
        }.onSuccess(Dispatchers.Main) { p ->
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
                "可在朗读过一段该角色的内容后，点「重新生成」让 AI 建档；朗读中也会自动补全。"
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
        val p = profile
        if (p == null) {
            toastOnUi("暂无档案可编辑，请先建档")
            return
        }
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
        binding.tvStatus.text = "正在让 AI 重新生成档案…（可能需要几十秒）"
        binding.tvRegen.isEnabled = false
        val chapterIndex = io.legado.app.model.ReadBook.durChapterIndex
        val chapterTitle = io.legado.app.model.ReadBook.curTextChapter?.chapter?.title.orEmpty()
        CharacterProfileManager.regenerate(
            bookName, characterName,
            if (chapterIndex >= 0 && chapterTitle.isNotBlank()) chapterIndex else -1,
            chapterTitle
        ) { p, error ->
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

    /**
     * 生图模板选择框：列出全部生图模板，选中后立绘生成固定使用该模板。
     * 选择持久化到 AppConfig（重启不丢）；第一项「默认模板」= 跟随默认。
     */
    private fun setupTemplateSpinner() {
        execute {
            io.legado.app.data.appDb.aiImageTemplateDao.getAllList()
        }.onSuccess(Dispatchers.Main) { templates ->
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
    private fun generatePortrait() {
        val p = profile
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "正在生成角色立绘…（可能需要几十秒）"
        binding.tvPortrait.isEnabled = false
        io.legado.app.model.AiImageGenerator.generateCharacterAvatar(
            characterName = characterName,
            genderAge = listOf(p?.gender.orEmpty(), p?.age.orEmpty())
                .filter { it.isNotBlank() }.joinToString(""),
            bookName = bookName,
            identity = p?.identity.orEmpty(),
            personality = p?.personality.orEmpty(),
            background = p?.background.orEmpty(),
            regenerate = false,
            templateId = io.legado.app.help.config.AppConfig.aiPortraitTemplateId,
            onSuccess = { file ->
                binding.tvPortrait.isEnabled = true
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.text = "立绘已生成：${file.name}"
                toastOnUi("立绘已生成，与角色对话页将自动使用")
            },
            onFailure = { e ->
                binding.tvPortrait.isEnabled = true
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.text = "立绘生成失败：${e.localizedMessage}"
            }
        )
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
