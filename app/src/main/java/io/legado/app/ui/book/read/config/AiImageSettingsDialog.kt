package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import io.legado.app.utils.putPrefString
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.widget.SwitchCompat
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiImageTemplate
import io.legado.app.help.ApiEntry
import io.legado.app.help.MiyueKeyManager
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AiImageGenerator
import io.legado.app.model.AiImagePresets
import io.legado.app.model.AiImagePromptPresetStore
import io.legado.app.model.ReadBook
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefString
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiImageSettingsDialog : BaseDialogFragment(0) {

    /** 当前编辑中的模板 */
    private var currentTemplate: AiImageTemplate? = null

    // 输入控件引用（保存时读取）
    private lateinit var etModelUrl: EditText
    private lateinit var etModelName: EditText
    private lateinit var etModelKey: EditText
    private lateinit var etImageSize: EditText
    private lateinit var etImageStyle: EditText
    private lateinit var etPromptTemplate: EditText
    private lateinit var etNegativePrompt: EditText
    private lateinit var etFilterWords: EditText
    private lateinit var etCharCount: EditText
    private lateinit var etContextCount: EditText
    private lateinit var etRetryCount: EditText
    private lateinit var etRequestInterval: EditText

    private lateinit var btnSelectTemplate: Button
    private lateinit var switchBindBook: SwitchCompat
    private lateinit var bindBookRow: LinearLayout
    private lateinit var bindHint: TextView

    /** 测试连接旁的开关：开启后使用 base64 方式获取图片（请求体附带 response_format=b64_json） */
    private lateinit var switchBase64: SwitchCompat
    private lateinit var switchAnnotate: SwitchCompat
    private lateinit var switchStrictFormat: SwitchCompat
    private lateinit var switchCompress: SwitchCompat

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val scrollView = NestedScrollView(context).apply {
            isFillViewport = false
            isNestedScrollingEnabled = true
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx())
        }
        scrollView.addView(root)

        fun label(text: String) {
            root.addView(TextView(context).apply {
                this.text = text
                textSize = 14f
                setPadding(0, 10.dpToPx(), 0, 4.dpToPx())
            })
        }

        fun labelWithHelp(text: String, helpTitle: String, helpMessage: String): LinearLayout {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10.dpToPx(), 0, 4.dpToPx())
            }
            row.addView(TextView(context).apply {
                this.text = text
                textSize = 14f
            })
            row.addView(TextView(context).apply {
                this.text = "  ?"
                textSize = 16f
                setTextColor(android.graphics.Color.parseColor("#1976D2"))
                setPadding(6.dpToPx(), 0, 0, 0)
                setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle(helpTitle)
                        .setMessage(helpMessage)
                        .setPositiveButton("知道了", null)
                        .show()
                }
            })
            root.addView(row)
            return row
        }

        /**
         * 题材/画风下拉框。展开后顶部「＋ 新增」，其余每行右侧「⋮」→ 删除/修改/复制/置顶。
         *
         * 注意 onItemSelected 在**设置 adapter 时就会被系统回调一次**（position=0），
         * 所以必须用 [ignoreSelection] 挡掉，否则一进页面就会弹出"新增"框。
         * 收起态初始显示「自定义」，与改造前一致。
         */
        fun attachPresetSpinner(
            row: LinearLayout,
            kind: String,
            onApplied: (AiImagePromptPresetStore.Item) -> Unit
        ): Spinner {
            // 先建 Spinner 再挂 adapter。
            // 不要写成 `lateinit var spinner` + `Spinner(context).apply { ...引用 spinner... }`：
            // apply 块是在构造期间执行的，而 lateinit 变量要等整条赋值语句结束才完成初始化，
            // 块里读它会抛 UninitializedPropertyAccessException（这个坑踩过一次）。
            val spinner = Spinner(context)
            spinner.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = 8.dpToPx() }

            val presetAdapter = PresetSpinnerAdapter(
                defaultContent = if (kind == AiImagePromptPresetStore.KIND_GENRE) {
                    AiImageGenerator.DEFAULT_PROMPT_TEMPLATE
                } else {
                    AiImageGenerator.DEFAULT_STYLE_SUFFIX
                },
                onAddClick = {
                    // 新增后直接把新预设选中并套用，省得再点一次。
                    // 下标直接从存储查（此时 onChanged 已先刷新过，顺序与 adapter 一致），
                    // 不能引用 presetAdapter——它正在初始化中，构造参数里取不到自己。
                    showPresetEditor(kind, null, onChanged = { reloadPresetSpinner(spinner) }) { item ->
                        val ctx = context
                        val idx = ctx?.let {
                            AiImagePromptPresetStore.list(it, kind).indexOfFirst { i -> i.id == item.id }
                        } ?: -1
                        if (idx >= 0) {
                            // +2 是因为下拉 0=「默认」、1=「＋ 新增」
                            spinner.setSelection(idx + 2, false)
                            onApplied(item)
                            toastOnUi("已新增并套用「${item.name}」")
                        }
                    }
                },
                onItemClick = { item, position ->
                    // 代码更新选中项，收起态文案随之显示该预设名
                    spinner.setSelection(position, false)
                    onApplied(item)
                    toastOnUi("已填入「${item.name}」，可继续手动微调")
                },
                onDefaultClick = { content ->
                    spinner.setSelection(0, false)
                    onApplied(AiImagePromptPresetStore.Item("", DEFAULT_ITEM_LABEL, content, kind))
                    toastOnUi("已恢复最初始的默认值")
                },
                onMoreClick = { item, anchor ->
                    showPresetMoreMenu(item, anchor) { reloadPresetSpinner(spinner) }
                }
            )
            // 【不要给 Spinner 设 onItemSelectedListener】
            // 设了之后要靠"守卫标记"区分程序选中与用户点击，一旦守卫没被消费
            // （例如刷新列表后没有后续回调），用户第一次真实点击就会被误吞掉，
            // 表现为「点新增没反应、点条目文字也没反应，只有 ⋮ 有反应」。
            // 改成：每一行自己 setOnClickListener 吃掉触摸，Spinner 选中项只在套用时
            // 由代码 setSelection 更新（收起态文案随之刷新）。
            spinner.adapter = presetAdapter
            row.addView(spinner)
            presetSpinnerAdapters[kind] = presetAdapter
            presetSpinnerKind[spinner] = kind
            reloadPresetSpinner(spinner)
            return spinner
        }

        fun edit(text: String, hint: String): EditText {
            return EditText(context).apply {
                setText(text)
                this.hint = hint
            }.also(root::addView)
        }

        fun multiLineEdit(text: String, hint: String): EditText {
            return EditText(context).apply {
                setText(text)
                this.hint = hint
                minLines = 3
                maxLines = 6
                isVerticalScrollBarEnabled = true
            }.also(root::addView)
        }

        fun secretEdit(text: String, hint: String): EditText {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val input = EditText(context).apply {
                setText(text)
                this.hint = hint
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                transformationMethod = PasswordTransformationMethod.getInstance()
                setSingleLine(true)
                setSelection(this.text?.length ?: 0)
            }
            var visible = false
            val toggle = ImageButton(context).apply {
                setImageResource(R.drawable.ic_visibility_off)
                contentDescription = "显示密钥"
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(10.dpToPx())
                setOnClickListener {
                    visible = !visible
                    input.transformationMethod = if (visible) {
                        HideReturnsTransformationMethod.getInstance()
                    } else {
                        PasswordTransformationMethod.getInstance()
                    }
                    setImageResource(if (visible) R.drawable.ic_daytime else R.drawable.ic_visibility_off)
                    contentDescription = if (visible) "隐藏密钥" else "显示密钥"
                    input.setSelection(input.text?.length ?: 0)
                }
            }
            row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(toggle, LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx()))
            root.addView(row)
            return input
        }

        // ========== 顶部：选择模板按钮 ==========
        btnSelectTemplate = Button(context).apply {
            text = "选择模板"
            setOnClickListener { showTemplateListDialog() }
        }
        root.addView(btnSelectTemplate)

        // ========== 绑定当前书籍开关 ==========
        bindBookRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dpToPx(), 0, 4.dpToPx())
        }
        val bindLabel = TextView(context).apply {
            text = "绑定当前书籍"
            textSize = 14f
        }
        switchBindBook = SwitchCompat(context)
        bindBookRow.addView(bindLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bindBookRow.addView(switchBindBook)
        root.addView(bindBookRow)

        bindHint = TextView(context).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#888888"))
            setPadding(0, 0, 0, 8.dpToPx())
        }
        root.addView(bindHint)

        // 绑定开关变化即时生效（支持多模板绑定轮换）
        switchBindBook.setOnCheckedChangeListener { _, isChecked ->
            val template = currentTemplate ?: return@setOnCheckedChangeListener
            val book = ReadBook.book ?: return@setOnCheckedChangeListener

            if (isChecked) {
                val ok = AiImageGenerator.addBoundTemplateId(book.name, book.bookUrl, template.id)
                if (ok) {
                    toastOnUi("已将「${template.name}」加入当前书籍的生图模板")
                } else {
                    switchBindBook.isChecked = false
                    toastOnUi("每本书最多绑定 ${AiImageGenerator.MAX_BOUND_TEMPLATES} 个模板")
                }
            } else {
                AiImageGenerator.removeBoundTemplateId(book.name, book.bookUrl, template.id)
                toastOnUi("已将「${template.name}」从当前书籍移除")
            }
        }

        // 分隔线
        root.addView(View(context).apply {
            setBackgroundColor(android.graphics.Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1.dpToPx()
            ).apply { bottomMargin = 12.dpToPx() }
        })

        // 启用AI生图开关已移动到「背景音乐设置」界面的「保存AI图片」上方（每本书单独设置），此处不再展示。

        // 分析字数 + 上下文字数（并排两列）
        labelWithHelp(
            "分析字数",
            "AI生图分析字数说明",
            """每朗读多少字触发一次AI生图切换，同时也是每次提取「正文」的字数。

【说明】
1. 朗读进度每累计达到此字数，自动切换到下一张AI图片；
2. 每次提取的「正文」长度等于此字数；
3. 默认200字，范围50~5000字；
4. 首次开始朗读时立即生成第一张图片，之后每达到此字数切换一次；
5. 切换前会提前预生成下一张图片，实现秒切无等待。"""
        )

        val charCountRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            setPadding(0, 0, 0, 4.dpToPx())
        }

        // 分析字数
        val charCountWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, 6.dpToPx(), 0)
        }
        etCharCount = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(AppConfig.aiImageCharCount.toString())
            hint = "默认200，50~5000"
        }
        charCountWrap.addView(TextView(context).apply {
            text = "正文分析字数"
            textSize = 11f
            setPadding(0, 0, 0, 2.dpToPx())
        })
        charCountWrap.addView(etCharCount)

        // 上下文字数
        val contextCountWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(6.dpToPx(), 0, 0, 0)
        }
        etContextCount = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(AppConfig.aiImageContextCharCount.toString())
            hint = "默认50，0~2000"
        }
        contextCountWrap.addView(TextView(context).apply {
            text = "上文/后续字数"
            textSize = 11f
            setPadding(0, 0, 0, 2.dpToPx())
        })
        contextCountWrap.addView(etContextCount)

        charCountRow.addView(charCountWrap)
        charCountRow.addView(contextCountWrap)
        root.addView(charCountRow)

        // 重试次数 + 请求间隔时间（并排两列）
        val rowRetryInterval = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        // 左列：重试次数
        val colRetry = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        colRetry.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dpToPx(), 0, 4.dpToPx())
            addView(TextView(context).apply {
                text = "重试次数"
                textSize = 14f
            })
            addView(TextView(context).apply {
                text = "  ?"
                textSize = 16f
                setTextColor(android.graphics.Color.parseColor("#1976D2"))
                setPadding(6.dpToPx(), 0, 0, 0)
                setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("AI生图重试次数说明")
                        .setMessage("""AI生图失败后的重试次数。

【说明】
1. 默认重试1次，可设置为0~20次；
2. 每次重试会重新根据当前朗读位置收集正文内容（而非使用上次文本）；
3. 重试次数耗尽则放弃本次生图，等待下次触发。""")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            })
        })
        etRetryCount = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(AppConfig.aiImageRetryCount.toString())
            hint = "默认1，范围0~20"
        }
        colRetry.addView(etRetryCount)
        // 右列：请求间隔时间
        val colInterval = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(12.dpToPx(), 0, 0, 0)
        }
        colInterval.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dpToPx(), 0, 4.dpToPx())
            addView(TextView(context).apply {
                text = "请求间隔时间"
                textSize = 14f
            })
            addView(TextView(context).apply {
                text = "  ?"
                textSize = 16f
                setTextColor(android.graphics.Color.parseColor("#1976D2"))
                setPadding(6.dpToPx(), 0, 0, 0)
                setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("AI生图请求间隔时间说明")
                        .setMessage("""每次发送生图请求（含首次与重试）前的等待时间。

【说明】
1. 默认 1.0 秒，可设置为 0~60 秒（支持小数）；
2. 正常请求和重试都会按此间隔等待，避免触发部分 API 的频率限制；
3. 设为 0 表示不等待，立即请求。""")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            })
        })
        etRequestInterval = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(AppConfig.aiImageRequestInterval.toString())
            hint = "默认1.0，范围0~60秒"
        }
        colInterval.addView(etRequestInterval)
        rowRetryInterval.addView(colRetry)
        rowRetryInterval.addView(colInterval)
        root.addView(rowRetryInterval)

        // 分隔线
        root.addView(View(context).apply {
            setBackgroundColor(android.graphics.Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1.dpToPx()
            ).apply { bottomMargin = 12.dpToPx() }
        })

        label("AI 模型地址")
        etModelUrl = edit(AiImageGenerator.DEFAULT_MODEL_URL, "例如 https://api.siliconflow.cn/v1")

        label("模型名")
        // 模型名输入框 + 右侧「获取模型」按钮（复用密钥管理的模型列表弹窗）
        val modelNameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 4.dpToPx())
        }
        etModelName = EditText(context).apply {
            setText(AiImageGenerator.DEFAULT_MODEL_NAME)
            hint = "例如 Kwai-Kolors/Kolors"
        }
        modelNameRow.addView(
            etModelName,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val btnFetchModels = Button(context).apply {
            text = "获取模型"
            setPadding(8.dpToPx())
            setOnClickListener { fetchModelsForImage() }
        }
        modelNameRow.addView(
            btnFetchModels,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8.dpToPx() }
        )
        root.addView(modelNameRow)

        labelWithHelp(
            "API 密钥",
            "多密钥轮换说明",
            """填写生图接口的 API 密钥。

【多密钥轮换】
支持填写多个密钥，用 @@ 分隔，例如：
sk-aaa@@sk-bbb@@sk-ccc

生图时按顺序轮换使用：
1. 每次生图请求（含重试）使用下一个密钥；
2. 当前模板的密钥全部用完后，自动切换到下一个绑定模板；
3. 所有模板的密钥全部用完后，循环回到第一个。

【说明】
1. 只填一个密钥则不轮换；
2. 留空且接口地址、模型名也留空时，使用内置免费魔搭接口。"""
        )
        etModelKey = secretEdit("", "sk-xxx@@sk-yyy（多个用 @@ 分隔，生图时轮换）")

        label("图片尺寸")
        etImageSize = edit("784x1168", "例如 784x1168 / 1024x576")

        // 画风选择框挂在标题右边：选中后把该画风的风格描述整段写进下面的输入框
        val artStyleLabelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dpToPx(), 0, 4.dpToPx())
        }
        artStyleLabelRow.addView(TextView(context).apply {
            text = "风格提示词后缀"
            textSize = 14f
        })
        root.addView(artStyleLabelRow)
        attachPresetSpinner(
            artStyleLabelRow,
            AiImagePromptPresetStore.KIND_ART_STYLE
        ) { item ->
            etImageStyle.setText(item.content)
        }
        etImageStyle = edit(AiImageGenerator.DEFAULT_STYLE_SUFFIX, "例如 ，中国风插画风格，精美细腻")

        // 提示词模板
        val promptTemplateLabelRow = labelWithHelp(
            "提示词模板",
            "提示词模板使用说明",
            """提示词模板会作为生成 AI 图片的指令发送给模型。可使用以下占位符，生成时会自动替换为实际内容：

【占位符说明】
{text}  → 内容片段：小说正文片段（上文+正文+后续，超过 350 字会自动截断）
{book}  → 书名：当前朗读的书名（替换为“出自小说《书名》。”）
{style} → 风格后缀：即上方的“风格提示词后缀”设置项的值

【使用方法】
1. 占位符可放在模板任意位置，生成时按顺序自动替换；
2. 某个占位符对应的值若为空，则该占位符替换为空字符串（不占位）；
3. 可自由删除不需要的占位符，例如不想要书名就去掉 {book}；
4. 除占位符外的其它文字会原样发送给模型；
5. 点击标题右边的下拉框可按题材快速套用整套模板，套用后可继续手动微调；
6. 点击“恢复默认提示词”可一键还原为内置默认值。"""
        )
        // 题材选择框挂在「提示词模板」标题同一行的右边：选中后把该题材的整套模板写进下面的输入框
        attachPresetSpinner(
            promptTemplateLabelRow,
            AiImagePromptPresetStore.KIND_GENRE
        ) { item ->
            etPromptTemplate.setText(item.content)
        }
        etPromptTemplate = multiLineEdit(AiImageGenerator.DEFAULT_PROMPT_TEMPLATE, "占位符：{text} 内容片段 {book} 书名 {style} 风格后缀")

        // 负面提示词
        label("负面提示词")
        etNegativePrompt = multiLineEdit(AiImageGenerator.DEFAULT_NEGATIVE_PROMPT, "例如：凝重的眼神，愁眉苦脸，丑陋，畸形，低质量")

        // 过滤词语
        labelWithHelp(
            "过滤词语",
            "正文过滤词语说明",
            """小说正文内容在发送给 API 生成图片前，会先删除这里填写的词语。

【填写格式】
多个词语用竖线 | 分隔，例如：
敏感词1|敏感词2|敏感词3

【说明】
1. 正文中所有匹配到的词语都会被删除（替换为空）；
2. 每个词语前后的空格会自动忽略；
3. 不填则不做任何过滤。"""
        )
        etFilterWords = edit("", "多个词语用 | 分隔，例如：词语1|词语2|词语3")

        val btnTest = Button(context).apply {
            text = "测试连接"
            setOnClickListener {
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        AiImageGenerator.testModel(
                            modelUrl = etModelUrl.text?.toString()?.trim().orEmpty(),
                            modelName = etModelName.text?.toString()?.trim().orEmpty(),
                            modelKey = etModelKey.text?.toString()?.trim().orEmpty(),
                            imageSize = etImageSize.text?.toString()?.trim().orEmpty(),
                            imageStyle = etImageStyle.text?.toString()?.trim().orEmpty(),
                            promptTemplate = etPromptTemplate.text?.toString()?.trim().orEmpty(),
                            negativePrompt = etNegativePrompt.text?.toString()?.trim().orEmpty(),
                            useBase64Response = switchBase64.isChecked,
                            strictFormat = switchStrictFormat.isChecked,
                        )
                    }
                    result.onSuccess {
                        toastOnUi("测试成功，图片已生成")
                    }.onFailure {
                        toastOnUi("测试失败：${it.localizedMessage}")
                    }
                }
            }
        }

        // 第一行：测试连接上方 —— Base64 方式开关 + 严格格式开关（同一行）
        val testRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dpToPx(), 0, 0)
        }
        switchBase64 = SwitchCompat(context)
        // 开关变化实时保存，使测试连接和正式生图立即生效，无需手动点保存
        switchBase64.setOnCheckedChangeListener { _, isChecked ->
            val template = currentTemplate ?: return@setOnCheckedChangeListener
            currentTemplate = template.copy(useBase64Response = isChecked)
            lifecycleScope.launch(Dispatchers.IO) {
                appDb.aiImageTemplateDao.insert(currentTemplate!!)
            }
        }
        val base64Label = TextView(context).apply {
            text = "Base64 方式"
            textSize = 14f
            setPadding(0, 0, 6.dpToPx(), 0)
        }
        val base64LabelWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { switchBase64.isChecked = !switchBase64.isChecked }
        }
        base64LabelWrap.addView(base64Label)
        base64LabelWrap.addView(switchBase64)
        testRow.addView(
            base64LabelWrap,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        // 严格格式开关：默认关闭，开启后只发送接口白名单字段，避免严格校验接口返回未知字段错误
        switchStrictFormat = SwitchCompat(context)
        switchStrictFormat.setOnCheckedChangeListener { _, isChecked ->
            val template = currentTemplate ?: return@setOnCheckedChangeListener
            currentTemplate = template.copy(strictFormat = isChecked)
            lifecycleScope.launch(Dispatchers.IO) {
                appDb.aiImageTemplateDao.insert(currentTemplate!!)
            }
        }
        val strictLabel = TextView(context).apply {
            text = "严格格式"
            textSize = 14f
            setPadding(16.dpToPx(), 0, 6.dpToPx(), 0)
        }
        val strictLabelWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { switchStrictFormat.isChecked = !switchStrictFormat.isChecked }
        }
        strictLabelWrap.addView(strictLabel)
        strictLabelWrap.addView(switchStrictFormat)
        testRow.addView(
            strictLabelWrap,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        root.addView(testRow)

        // 图片压缩开关：PNG 生图常达 2~3MB，超过 500KB 自动转 JPEG，体积通常降到十分之一
        switchCompress = SwitchCompat(context)
        switchCompress.setOnCheckedChangeListener { _, isChecked ->
            val template = currentTemplate ?: return@setOnCheckedChangeListener
            currentTemplate = template.copy(compressImage = isChecked)
            lifecycleScope.launch(Dispatchers.IO) {
                appDb.aiImageTemplateDao.insert(currentTemplate!!)
            }
        }
        val compressLabel = TextView(context).apply {
            text = "图片压缩"
            textSize = 14f
            setPadding(0, 0, 6.dpToPx(), 0)
        }
        val compressHelp = TextView(context).apply {
            text = "  ?"
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#1976D2"))
            setPadding(6.dpToPx(), 0, 0, 0)
            setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("图片压缩说明")
                    .setMessage("""开启后，AI 生成的图片会先判断体积：

【规则】
1. 小于等于 500KB：原样保存，不做任何处理；
2. 超过 500KB：自动转成 JPEG 保存，体积通常能降到十分之一左右；
3. 转成 JPEG 后扩展名仍为 .png（内容是 JPEG），不影响查看与清理。

【说明】
1. 只压缩新生成的图片，已经生成的旧图不会回溯压缩；
2. JPEG 不支持透明通道，若模型返回的是带透明背景的 PNG，转成 JPEG 后透明处会变黑底。""")
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
        val compressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dpToPx(), 0, 0)
        }
        val compressLabelWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { switchCompress.isChecked = !switchCompress.isChecked }
        }
        compressLabelWrap.addView(compressLabel)
        compressLabelWrap.addView(compressHelp)
        compressRow.addView(compressLabelWrap)
        compressRow.addView(switchCompress)
        root.addView(compressRow)

        // 角色标注开关 + 测试连接按钮（同一行）
        switchAnnotate = SwitchCompat(context)
        val annotateLabel = TextView(context).apply {
            text = "角色标注"
            textSize = 14f
            setPadding(0, 0, 6.dpToPx(), 0)
        }
        val annotateHelp = TextView(context).apply {
            text = "  ?"
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#1976D2"))
            setPadding(6.dpToPx(), 0, 0, 0)
            setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("角色标注说明")
                    .setMessage("""开启后，发给 AI 生图的正文会在每个左双引号“右侧插入角色标注。

【说明】
1. 角色信息来自 AI 章节缓存文件（与导出小说“添加标注”同源）；
2. 标注形如：<<姓名（性别/年龄）>>，仅在正文含左双引号且存在缓存时生效；
3. 关闭则按原始正文发送给模型。""")
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
        val annotateRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dpToPx(), 0, 0)
        }
        val annotateLabelWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { switchAnnotate.isChecked = !switchAnnotate.isChecked }
        }
        annotateLabelWrap.addView(annotateLabel)
        annotateLabelWrap.addView(annotateHelp)
        annotateRow.addView(annotateLabelWrap)
        annotateRow.addView(switchAnnotate)
        // 测试连接按钮放右侧，占据剩余宽度
        annotateRow.addView(
            btnTest,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8.dpToPx()
            }
        )
        root.addView(annotateRow)

        val btnResetPrompt = Button(context).apply {
            text = "恢复默认提示词"
            setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("恢复默认提示词")
                    .setMessage("将提示词模板、负面提示词、风格后缀恢复为内置默认值，确认继续？")
                    .setPositiveButton("恢复") { _, _ ->
                        etPromptTemplate.setText(AiImageGenerator.DEFAULT_PROMPT_TEMPLATE)
                        etNegativePrompt.setText(AiImageGenerator.DEFAULT_NEGATIVE_PROMPT)
                        etImageStyle.setText(AiImageGenerator.DEFAULT_STYLE_SUFFIX)
                        toastOnUi("已恢复默认提示词，请点击保存生效")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        val btnSave = Button(context).apply {
            text = "保存"
            setOnClickListener { saveCurrentTemplate() }
        }

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dpToPx(), 0, 0)
        }
        btnRow.addView(
            btnResetPrompt,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        btnRow.addView(
            btnSave,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(btnRow)

        // 同步加载上次编辑的模板（或默认模板）填充界面
        loadInitialTemplate()

        return scrollView
    }

    /** 加载初始模板：优先上次编辑的模板，否则默认模板 */
    private fun loadInitialTemplate() {
        val lastId = appCtx.getPrefString(PreferKey.aiImageLastEditTemplateId, null)?.toLongOrNull()
        var template: AiImageTemplate? = null
        if (lastId != null) {
            template = appDb.aiImageTemplateDao.getById(lastId)
        }
        if (template == null) {
            template = appDb.aiImageTemplateDao.getDefault()
        }
        if (template == null) {
            // 数据库尚无模板（首次使用），用内置默认值构建一个临时模板展示
            template = AiImageTemplate(
                id = AiImageGenerator.DEFAULT_TEMPLATE_ID,
                name = "默认模板",
                modelUrl = AiImageGenerator.DEFAULT_MODEL_URL,
                modelName = AiImageGenerator.DEFAULT_MODEL_NAME,
                imageSize = "784x1168",
                imageStyle = AiImageGenerator.DEFAULT_STYLE_SUFFIX,
                promptTemplate = AiImageGenerator.DEFAULT_PROMPT_TEMPLATE,
                negativePrompt = AiImageGenerator.DEFAULT_NEGATIVE_PROMPT,
                isDefault = true
            )
        }
        applyTemplateToUi(template)
    }

    /** 将模板配置填充到各输入框，并更新顶部按钮与绑定开关状态 */
    private fun applyTemplateToUi(template: AiImageTemplate) {
        currentTemplate = template
        btnSelectTemplate.text = if (template.isDefault) "模板：${template.name}" else "模板：${template.name}"
        etModelUrl.setText(template.modelUrl)
        etModelName.setText(template.modelName)
        etModelKey.setText(template.modelKey)
        etImageSize.setText(template.imageSize)
        etImageStyle.setText(template.imageStyle)
        etPromptTemplate.setText(template.promptTemplate)
        etNegativePrompt.setText(template.negativePrompt)
        etFilterWords.setText(template.filterWords)
        // 让两个预设下拉框的选中项与刚填进来的内容对上，
        // 否则老模板明明用的是某条预设，下拉框却还停在第 0 项。
        syncPresetSpinnerSelection(
            AiImagePromptPresetStore.KIND_ART_STYLE,
            template.imageStyle,
            AiImageGenerator.DEFAULT_STYLE_SUFFIX
        )
        syncPresetSpinnerSelection(
            AiImagePromptPresetStore.KIND_GENRE,
            template.promptTemplate,
            AiImageGenerator.DEFAULT_PROMPT_TEMPLATE
        )
        // 同步 base64 开关状态（临时移除 listener 避免触发保存）
        switchBase64.setOnCheckedChangeListener(null)
        switchBase64.isChecked = template.useBase64Response
        switchBase64.setOnCheckedChangeListener { _, isChecked ->
            val tmpl = currentTemplate ?: return@setOnCheckedChangeListener
            currentTemplate = tmpl.copy(useBase64Response = isChecked)
            lifecycleScope.launch(Dispatchers.IO) {
                appDb.aiImageTemplateDao.insert(currentTemplate!!)
            }
        }
        // 同步角色标注开关状态（临时移除 listener 避免触发保存）
        switchAnnotate.setOnCheckedChangeListener(null)
        switchAnnotate.isChecked = template.annotateRoles
        switchAnnotate.setOnCheckedChangeListener { _, isChecked ->
            val tmpl = currentTemplate ?: return@setOnCheckedChangeListener
            currentTemplate = tmpl.copy(annotateRoles = isChecked)
            lifecycleScope.launch(Dispatchers.IO) {
                appDb.aiImageTemplateDao.insert(currentTemplate!!)
            }
        }
        // 同步严格格式开关状态（临时移除 listener 避免触发保存）
        switchStrictFormat.setOnCheckedChangeListener(null)
        switchStrictFormat.isChecked = template.strictFormat
        switchStrictFormat.setOnCheckedChangeListener { _, isChecked ->
            val tmpl = currentTemplate ?: return@setOnCheckedChangeListener
            currentTemplate = tmpl.copy(strictFormat = isChecked)
            lifecycleScope.launch(Dispatchers.IO) {
                appDb.aiImageTemplateDao.insert(currentTemplate!!)
            }
        }
        // 同步图片压缩开关状态（临时移除 listener 避免触发保存）
        switchCompress.setOnCheckedChangeListener(null)
        switchCompress.isChecked = template.compressImage
        switchCompress.setOnCheckedChangeListener { _, isChecked ->
            val tmpl = currentTemplate ?: return@setOnCheckedChangeListener
            currentTemplate = tmpl.copy(compressImage = isChecked)
            lifecycleScope.launch(Dispatchers.IO) {
                appDb.aiImageTemplateDao.insert(currentTemplate!!)
            }
        }
        // 记住上次编辑的模板
        appCtx.putPrefString(PreferKey.aiImageLastEditTemplateId, template.id.toString())
        // 更新绑定开关状态
        refreshBindSwitch(template)
    }

    /** 根据当前书与模板关系刷新绑定开关 */
    private fun refreshBindSwitch(template: AiImageTemplate) {
        val book = ReadBook.book
        if (book == null) {
            bindBookRow.visibility = View.GONE
            bindHint.visibility = View.GONE
            return
        }
        bindBookRow.visibility = View.VISIBLE
        bindHint.visibility = View.VISIBLE
        val bound = AiImageGenerator.isBookBoundToTemplate(book.name, book.bookUrl, template.id, template.isDefault)
        // 避免触发 listener
        switchBindBook.setOnCheckedChangeListener(null)
        switchBindBook.isChecked = bound
        switchBindBook.setOnCheckedChangeListener { _, isChecked ->
            val tmpl = currentTemplate ?: return@setOnCheckedChangeListener
            val b = ReadBook.book ?: return@setOnCheckedChangeListener

            if (isChecked) {
                val ok = AiImageGenerator.addBoundTemplateId(b.name, b.bookUrl, tmpl.id)
                if (ok) {
                    toastOnUi("已将「${tmpl.name}」加入当前书籍的生图模板")
                } else {
                    switchBindBook.isChecked = false
                    toastOnUi("每本书最多绑定 ${AiImageGenerator.MAX_BOUND_TEMPLATES} 个模板")
                }
            } else {
                AiImageGenerator.removeBoundTemplateId(b.name, b.bookUrl, tmpl.id)
                toastOnUi("已将「${tmpl.name}」从当前书籍移除")
            }
        }
        val boundCount = AiImageGenerator.getBoundTemplateIds(book.name, book.bookUrl).size
        bindHint.text = if (boundCount == 0) {
            "未绑定任何模板，将使用默认模板。可开启多个模板实现轮换生图。"
        } else {
            "当前书籍已绑定 ${boundCount}/${AiImageGenerator.MAX_BOUND_TEMPLATES} 个模板，生图时依次轮换。"
        }
    }

    /** 弹出模板列表对话框 */
    private fun showTemplateListDialog() {
        val currentId = currentTemplate?.id ?: 0
        AiImageTemplateListDialog.newInstance(
            currentEditId = currentId,
            onSelect = { selected ->
                applyTemplateToUi(selected)
            },
            onBindChanged = {
                // 绑定状态变化后刷新当前界面的绑定开关提示
                currentTemplate?.let { refreshBindSwitch(it) }
            },
            onRenamed = { renamed ->
                // 重命名的是当前编辑的模板，重新填充界面同步顶部模板名
                // 注意：会覆盖本界面尚未保存的修改
                if (currentTemplate?.id == renamed.id) {
                    applyTemplateToUi(renamed)
                }
            }
        ).show(childFragmentManager, "aiImageTemplateList")
    }

    /**
     * 获取当前 AI 生图接口的可用模型：复用密钥管理里的模型列表弹窗（MiyueKeyManager.showModelsDialog）。
     * 弹窗自带搜索框、点整行切换、右侧复制按钮，逻辑与密钥管理完全一致。
     * 仅用当前「AI 模型地址」拼 /models、用「API 密钥」做鉴权；选中后直接填入模型名输入框。
     */
    private fun fetchModelsForImage() {
        val url = etModelUrl.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) {
            toastOnUi("请先填写 AI 模型地址")
            return
        }
        val entry = ApiEntry(
            endpoint = url,
            model = etModelName.text?.toString()?.trim().orEmpty(),
            key = etModelKey.text?.toString()?.trim().orEmpty(),
            scene = "aiImage",
        )
        MiyueKeyManager.showModelsDialog(requireContext(), entry) { picked ->
            etModelName.setText(picked)
            toastOnUi("已选择模型：$picked")
        }
    }

    /** 保存当前模板配置到数据库 */
    private fun saveCurrentTemplate() {
        val template = currentTemplate ?: run {
            toastOnUi("模板未加载，无法保存")
            return
        }
        // 启用AI生图开关已迁移到背景音乐设置界面，这里不再修改该开关。

        // 保存分析字数、上下文字数和重试次数
        val charCount = etCharCount.text?.toString()?.trim()?.toIntOrNull()
            ?.coerceIn(50, 5000) ?: 200
        AppConfig.aiImageCharCount = charCount
        val contextCount = etContextCount.text?.toString()?.trim()?.toIntOrNull()
            ?.coerceIn(0, 2000) ?: 50
        AppConfig.aiImageContextCharCount = contextCount
        val retryCount = etRetryCount.text?.toString()?.trim()?.toIntOrNull()
            ?.coerceIn(0, 20) ?: 1
        AppConfig.aiImageRetryCount = retryCount
        val requestInterval = etRequestInterval.text?.toString()?.trim()?.toFloatOrNull()
            ?.coerceIn(0f, 60f) ?: 1.0f
        AppConfig.aiImageRequestInterval = requestInterval

        val updated = template.copy(
            modelUrl = etModelUrl.text?.toString()?.trim().orEmpty(),
            modelName = etModelName.text?.toString()?.trim().orEmpty(),
            modelKey = etModelKey.text?.toString()?.trim().orEmpty(),
            imageSize = etImageSize.text?.toString()?.trim().orEmpty(),
            imageStyle = etImageStyle.text?.toString()?.trim().orEmpty(),
            promptTemplate = etPromptTemplate.text?.toString()?.trim().orEmpty(),
            negativePrompt = etNegativePrompt.text?.toString()?.trim().orEmpty(),
            filterWords = etFilterWords.text?.toString()?.trim().orEmpty(),
            useBase64Response = switchBase64.isChecked,
            annotateRoles = switchAnnotate.isChecked,
            strictFormat = switchStrictFormat.isChecked,
            lastUpdateTime = System.currentTimeMillis()
        )

        lifecycleScope.launch(Dispatchers.IO) {
            // 非默认模板保存后固定在默认模板之后的第 2 位；默认模板保持最前不变
            if (!updated.isDefault) {
                val minSort = appDb.aiImageTemplateDao.getMinSortOrderOfNonDefault() ?: 0
                updated.sortOrder = minSort - 1
            }
            appDb.aiImageTemplateDao.insert(updated)
            withContext(Dispatchers.Main) {
                currentTemplate = updated
                toastOnUi("模板「${updated.name}」已保存")
                dismissAllowingStateLoss()
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit

    /**
     * 打开已有模板时，让下拉框选中项与输入框里的实际内容一致。
     *
     * 规则：内容等于原始默认值 → 选中第 0 项「默认」；
     * 否则在预设列表里按内容找同一条，找到就选中它；都找不到说明是纯手工内容，保持原样。
     * 只改选中项、不重新套用内容（内容已经由调用方填好了）。
     */
    private fun syncPresetSpinnerSelection(kind: String, content: String, defaultContent: String) {
        val adapter = presetSpinnerAdapters[kind] ?: return
        val spinner = presetSpinnerKind.entries.firstOrNull { it.value == kind }?.key ?: return
        if (content == defaultContent) {
            spinner.setSelection(0, false)
            return
        }
        val index = adapter.items.indexOfFirst { it.content == content }
        spinner.setSelection(if (index >= 0) index + 2 else 0, false)
    }

    /** 重新从存储读取并刷新下拉列表（增删改置顶后调用）。 */
    private fun reloadPresetSpinner(spinner: Spinner) {
        val kind = presetSpinnerKind[spinner] ?: return
        val ctx = context ?: return
        presetSpinnerAdapters[kind]?.refresh(AiImagePromptPresetStore.list(ctx, kind))
    }

    /** 「⋮」菜单：删除 / 修改 / 复制 / 置顶。 */
    private fun showPresetMoreMenu(
        item: AiImagePromptPresetStore.Item,
        anchor: View,
        onChanged: () -> Unit
    ) {
        val pm = PopupMenu(requireContext(), anchor)
        pm.menu.add(MENU_DELETE)
        pm.menu.add(MENU_EDIT)
        pm.menu.add(MENU_DUPLICATE)
        pm.menu.add(if (item.pinned) MENU_UNPIN else MENU_PIN)
        pm.setOnMenuItemClickListener {
            when (it.title.toString()) {
                MENU_DELETE -> AlertDialog.Builder(requireContext())
                    .setTitle("删除「${item.name}」？")
                    .setMessage("删除后该预设将从下拉列表中移除。内置题材/画风删除后不会再自动出现。")
                    .setPositiveButton("删除") { _, _ ->
                        AiImagePromptPresetStore.delete(requireContext(), item)
                        toastOnUi("已删除「${item.name}」")
                        onChanged()
                    }
                    .setNegativeButton("取消", null)
                    .show()

                MENU_EDIT -> showPresetEditor(item.kind, item, onChanged)

                MENU_DUPLICATE -> {
                    AiImagePromptPresetStore.duplicate(requireContext(), item)
                    toastOnUi("已复制「${item.name}」，副本已置顶")
                    onChanged()
                }

                MENU_PIN -> {
                    AiImagePromptPresetStore.setPinned(requireContext(), item, true)
                    toastOnUi("已置顶「${item.name}」")
                    onChanged()
                }

                MENU_UNPIN -> {
                    AiImagePromptPresetStore.setPinned(requireContext(), item, false)
                    toastOnUi("已取消置顶「${item.name}」")
                    onChanged()
                }
            }
            true
        }
        pm.show()
    }

    /**
     * 新增 / 修改预设。名称 + 内容（题材填提示词模板，画风填风格描述）。
     * [existing] 为 null 表示新增，否则为修改。
     */
    private fun showPresetEditor(
        kind: String,
        existing: AiImagePromptPresetStore.Item?,
        onChanged: () -> Unit,
        onCreated: (AiImagePromptPresetStore.Item) -> Unit = {}
    ) {
        val isGenre = kind == AiImagePromptPresetStore.KIND_GENRE
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
        }
        val etName = EditText(requireContext()).apply {
            hint = "名称"
            setText(existing?.name.orEmpty())
        }
        val etContent = EditText(requireContext()).apply {
            hint = if (isGenre) "提示词模板" else "风格描述"
            setText(existing?.content.orEmpty())
            minLines = if (isGenre) 5 else 3
            maxLines = 12
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        box.addView(etName)
        box.addView(etContent)

        AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) "新增${if (isGenre) "题材" else "画风"}" else "修改「${existing.name}」")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text?.toString()?.trim().orEmpty()
                val content = etContent.text?.toString()?.trim().orEmpty()
                if (content.isBlank()) {
                    toastOnUi("内容不能为空")
                    return@setPositiveButton
                }
                if (existing == null) {
                    val created = AiImagePromptPresetStore
                        .add(requireContext(), kind, name, content)
                    onChanged()
                    onCreated(created)
                } else {
                    AiImagePromptPresetStore.update(requireContext(), existing, name, content)
                    toastOnUi("已保存修改")
                }
                onChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    private companion object {
        /** 下拉框第 0 项：把内容恢复成 AiImageGenerator 里最初的默认值 */
        const val DEFAULT_ITEM_LABEL = "默认"
        /** 下拉框第 1 项，点它新建一条预设 */
        const val ADD_ITEM_LABEL = "＋ 新增"
        /** 收起态在"没选任何预设"时显示的文案 */
        const val CUSTOM_ITEM_LABEL = "自定义"
        const val MENU_DELETE = "删除"
        const val MENU_EDIT = "修改"
        const val MENU_DUPLICATE = "复制"
        const val MENU_PIN = "置顶"
        const val MENU_UNPIN = "取消置顶"
    }

    /** kind -> 适配器，供增删改后刷新下拉列表用 */
    private val presetSpinnerAdapters = mutableMapOf<String, PresetSpinnerAdapter>()

    /** Spinner -> kind，用于反查该刷新哪个适配器 */
    private val presetSpinnerKind = mutableMapOf<Spinner, String>()

    /**
     * 题材/画风下拉框适配器。
     *
     * 下拉列表的固定结构：
     * ```
     * position 0     「默认」      → 写入 AiImageGenerator 的原始默认值（不可改、不带 ⋮）
     * position 1     「＋ 新增」   → 打开新建弹窗（不可改、不带 ⋮）
     * position 2..N  预设条目      → 套用；右侧「⋮」可 删除/修改/复制/置顶
     * ```
     * 第 0 项必须是「默认」：用户改成别的预设后，需要能一键回到最初那套默认值，
     * 而原始默认值并不在 AiImagePresets 的题材/画风列表里，只能单独占一个固定项。
     *
     * 【关键约束】Spinner 的下拉列表只允许 **1 种 view type**
     * （Spinner.setAdapter 会校验 adapter.getViewTypeCount() != 1 就抛
     * IllegalArgumentException: "Spinner adapter view type count must be 1"）。
     * 所以展开列表里的**所有行必须共用同一个布局**，不能按 position 返回不同布局，
     * 否则复用池会把不同根节点的类型混着用，导致 inflate 跳过、findViewById 拿到 null 而 NPE。
     *
     * 注意：getView（收起态）与 getDropDownView（展开态）可以各用不同布局，
     * view type 约束只作用于展开列表。
     */
    inner class PresetSpinnerAdapter(
        /** 「默认」项写入的内容：题材是原始提示词模板，画风是原始风格后缀 */
        private val defaultContent: String,
        val onAddClick: () -> Unit,
        val onItemClick: (AiImagePromptPresetStore.Item, Int) -> Unit,
        val onDefaultClick: (String) -> Unit,
        val onMoreClick: (AiImagePromptPresetStore.Item, View) -> Unit
    ) : BaseAdapter() {

        var items: List<AiImagePromptPresetStore.Item> = emptyList()
            private set

        fun refresh(newItems: List<AiImagePromptPresetStore.Item>) {
            items = newItems
            notifyDataSetChanged()
        }

        /** 实际预设数（不含「默认」和「＋ 新增」两项）。 */
        val presetCount: Int get() = items.size

        override fun getCount(): Int = items.size + 2

        override fun getItem(position: Int): Any? = when {
            position == 0 -> null
            position == 1 -> null
            else -> items[position - 2]
        }

        override fun getItemId(position: Int): Long = position.toLong()

        /** 收起态：Spinner 本体显示的单行。 */
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_preset_spinner_selected, parent, false)) as TextView
            view.text = when {
                position == 0 -> DEFAULT_ITEM_LABEL
                position == 1 -> CUSTOM_ITEM_LABEL
                else -> items[position - 2].name
            }
            return view
        }

        /**
         * 展开态：所有 position 共用同一个布局。
         * 复用时必须重置「⋮ 按钮的可见性」和「整行的点击监听」，
         * 否则从固定项(⋮ 隐藏)复用到预设行时会看不到 ⋮、点了也没反应。
         */
        override fun getDropDownView(
            position: Int,
            convertView: View?,
            parent: ViewGroup
        ): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_preset_spinner_dropdown, parent, false)
            val nameView = view.findViewById<TextView>(R.id.tv_preset_name)
            val moreBtn = view.findViewById<ImageButton>(R.id.btn_preset_more)
            when {
                position == 0 -> {
                    // 「默认」：写回 AiImageGenerator 的原始默认值
                    nameView.text = DEFAULT_ITEM_LABEL
                    moreBtn.visibility = View.GONE
                    moreBtn.setOnClickListener(null)
                    view.setOnClickListener { onDefaultClick(defaultContent) }
                }

                position == 1 -> {
                    // 整行点击 = 新增。子 View 吃掉触摸后 Spinner 选中项不变，不会误触发套用
                    nameView.text = ADD_ITEM_LABEL
                    moreBtn.visibility = View.GONE
                    moreBtn.setOnClickListener(null)
                    view.setOnClickListener { onAddClick() }
                }

                else -> {
                    val item = items[position - 2]
                    nameView.text = if (item.pinned) "📌 ${item.name}" else item.name
                    moreBtn.visibility = View.VISIBLE
                    moreBtn.setOnClickListener { onMoreClick(item, moreBtn) }
                    // 整行点击 = 套用这条预设
                    view.setOnClickListener { onItemClick(item, position) }
                }
            }
            return view
        }
    }
}
