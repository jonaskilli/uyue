package io.legado.app.ui.sceneeffect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.databinding.ActivitySceneEffectManageBinding
import io.legado.app.databinding.ItemSceneEffectBinding
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ItemSceneEffectFxBinding
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.SceneEffectGenerator
import io.legado.app.model.FxCatalog
import io.legado.app.model.SceneEffectCue
import io.legado.app.model.SceneEffectKind
import io.legado.app.model.SceneEffectSettings
import io.legado.app.utils.postEvent

/**
 * 屏幕特效管理：两级列表，像音效规则管理一样逐条试播 / 启用禁用。
 *
 * 一级 = 52 个画面效果大类（整类开关）；
 * 二级 = 每个大类下的 10~33 个具体开源序列帧特效（共 244 个，点箭头展开，
 *        每条都能单独播放、单独禁用，禁用后正常朗读不会再抽到它）。
 *
 * 播放按钮把 SceneEffectView 铺满当前界面演 4 秒；预览模式绕过总开关和禁用状态，
 * 所以关掉的特效依然可以试播对比。
 */
class SceneEffectManageActivity : AppCompatActivity() {

    private sealed class Row {
        data class Header(
            val entry: SceneEffectSettings.Entry,
            val expanded: Boolean,
            val totalFx: Int,
            val usableFx: Int
        ) : Row()

        /** 每个大类展开后的第一条：项目原生的矢量/算法绘制版本 */
        data class Algo(val entry: SceneEffectSettings.Entry) : Row()

        data class Fx(val parent: SceneEffectKind, val info: FxCatalog.FxInfo) : Row()
    }

    private lateinit var binding: ActivitySceneEffectManageBinding
    private val effectAdapter = EffectAdapter()
    private val expanded = HashSet<String>()

    private val hidePreview = Runnable {
        binding.previewView.clearSceneEffects()
        binding.previewView.fxOnlyPreview = false
        binding.previewView.algoOnlyPreview = false
        binding.previewView.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySceneEffectManageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.previewView.previewMode = true

        binding.tvBack.setOnClickListener { finish() }
        binding.tvCopyDisabled.setOnClickListener { copyDisabled() }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = effectAdapter

        binding.btnEnableAll.setOnClickListener {
            SceneEffectSettings.setAll(true)
            SceneEffectSettings.setAllFx(allFxIds(), true)
            SceneEffectSettings.setAllAlgo(true)
            applyFilter(currentKey())
            Toast.makeText(this, "已启用全部特效", Toast.LENGTH_SHORT).show()
        }
        binding.btnDisableAll.setOnClickListener {
            SceneEffectSettings.setAll(false)
            SceneEffectSettings.setAllFx(allFxIds(), false)
            SceneEffectSettings.setAllAlgo(false)
            applyFilter(currentKey())
            Toast.makeText(this, "已禁用全部特效", Toast.LENGTH_SHORT).show()
        }
        binding.btnReset.setOnClickListener { resetEverything() }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        applyFilter("")
    }

    private fun currentKey(): String = binding.etSearch.text?.toString().orEmpty()

    private fun allFxIds(): List<String> =
        SceneEffectSettings.entries
            .flatMap { FxCatalog.itemsFor(it.kind) }
            .map { it.id }
            .distinct()

    /** 搜「矢量 / 算法」时把所有大类的原版绘制条目展开 */
    private fun matchesAlgo(key: String): Boolean = key.isNotEmpty() &&
        ("原版矢量绘制".contains(key, true) || "算法".contains(key, true))

    private fun matchesHeader(entry: SceneEffectSettings.Entry, key: String): Boolean =
        entry.name.contains(key, true) || entry.group.contains(key, true) ||
            entry.desc.contains(key, true) || entry.kind.name.contains(key, true) ||
            matchesAlgo(key)

    private fun matchesFx(info: FxCatalog.FxInfo, key: String): Boolean =
        info.zh.contains(key, true) || info.name.contains(key, true) ||
            info.id.contains(key, true) || info.catName.contains(key, true) ||
            info.cat.contains(key, true)

    private fun toggle(kind: SceneEffectKind) {
        if (!expanded.add(kind.name)) expanded.remove(kind.name)
        applyFilter(currentKey())
    }

    private fun applyFilter(key: String) {
        val k = key.trim()
        val rows = ArrayList<Row>()
        SceneEffectSettings.entries.forEach { entry ->
            val all = FxCatalog.itemsFor(entry.kind)
            val headerHit = k.isEmpty() || matchesHeader(entry, k)
            val hitFx = if (k.isEmpty()) all else all.filter { matchesFx(it, k) }
            if (k.isNotEmpty() && !headerHit && hitFx.isEmpty()) return@forEach
            // 搜到大类就展开它下面的全部素材；只搜到具体素材就只列命中的那些
            val visible = if (k.isEmpty() || headerHit) all else hitFx
            if (visible.isEmpty() && !headerHit) return@forEach
            val isExpanded = expanded.contains(entry.kind.name) ||
                (k.isNotEmpty() && hitFx.isNotEmpty()) || matchesAlgo(k)
            rows.add(
                Row.Header(
                    entry, isExpanded, all.size,
                    all.count { SceneEffectSettings.isFxEnabled(it.id) }
                )
            )
            if (isExpanded) {
                // 原版矢量/算法绘制固定排在最前，之后才是该类的序列帧素材
                rows.add(Row.Algo(entry))
                visible.forEach { rows.add(Row.Fx(entry.kind, it)) }
            }
        }
        effectAdapter.submit(rows)
        updateSummary()
    }

    private fun updateSummary() {
        val kinds = SceneEffectSettings.entries.size
        binding.tvSummary.text = buildString {
            append("类 ").append(kinds - SceneEffectSettings.disabledCount()).append("/")
            append(kinds).append(" · 算法 ").append(kinds - SceneEffectSettings.algoDisabledCount())
            append("/").append(kinds).append(" · 特效 ").append(FxCatalog.enabledSize())
            append("/").append(FxCatalog.size())
        }
    }

    /**
     * 复制所有被禁用项：中文名 + 素材 id。
     * 带上 id 是为了能直接定位到 assets/fx/<id>.png 并把不想要的素材删掉省空间。
     */
    private fun copyDisabled() {
        val lines = LinkedHashSet<String>()
        SceneEffectSettings.entries.forEach { entry ->
            if (!SceneEffectSettings.isEnabled(entry.kind)) {
                lines.add("[大类] ${entry.name}（${entry.kind.name}）")
            }
            if (!SceneEffectSettings.isAlgoEnabled(entry.kind)) {
                lines.add("[算法] ${entry.name} / 原版矢量绘制（${entry.kind.name}）")
            }
            FxCatalog.itemsFor(entry.kind).forEach { info ->
                if (!SceneEffectSettings.isFxEnabled(info.id)) {
                    lines.add("[特效] ${entry.name} / ${info.zh}（${info.id}）")
                }
            }
        }
        val text = if (lines.isEmpty()) "当前没有被禁用的特效" else lines.joinToString("\n")
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("禁用的屏幕特效", text))
        Toast.makeText(this, "已复制 ${lines.size} 项禁用名字", Toast.LENGTH_SHORT).show()
    }

    /**
     * 复位：覆盖安装后，图片素材时代残留的「大类关闭 / 具体素材禁用 / 算法版关闭」
     * 以及旧版写下的「该段落已检查但无特效」缓存，会让新版本很多效果触发不了。
     * 这里一次性把三组开关清回默认全启用、删掉旧缓存，并通知朗读界面重排区间。
     */
    private fun resetEverything() {
        SceneEffectSettings.resetAll()
        val cleared = SceneEffectGenerator.clearAllCache()
        applyFilter(currentKey())
        postEvent(EventBus.READ_ALOUD_CONFIG_CHANGED, true)
        if (BaseReadAloudService.isRun) {
            BaseReadAloudService.instance?.triggerDanmakuGenerationManual()
        }
        Toast.makeText(
            this,
            "已复位：开关全部恢复启用，清除 $cleared 个旧特效缓存",
            Toast.LENGTH_LONG
        ).show()
    }

    /** 播放大类：算法层 + 该类中仍启用的一个具体素材（合成效果） */
    private fun playKind(entry: SceneEffectSettings.Entry) {
        val fxId = if (SceneEffectKind.prefersSprite(entry.kind)) {
            FxCatalog.pick(entry.kind, null, entry.kind.ordinal * 31)
        } else null
        showPreview(
            SceneEffectCue(
                para = 0,
                kind = entry.kind,
                level = 3,
                durationMs = 4000L,
                wind = true,
                source = "manage-preview",
                fxId = fxId
            )
        )
        if (fxId == null && SceneEffectKind.prefersSprite(entry.kind)) {
            Toast.makeText(this, "该类素材已全部禁用，仅显示算法效果", Toast.LENGTH_SHORT).show()
        }
    }

    /** 播放原版矢量/算法效果：不叠任何序列帧素材 */
    private fun playAlgo(entry: SceneEffectSettings.Entry) {
        showPreview(
            SceneEffectCue(
                para = 0,
                kind = entry.kind,
                level = 3,
                durationMs = 4000L,
                wind = true,
                source = "manage-preview-algo"
            ),
            algoOnly = true
        )
    }

    /** 播放某个具体序列帧：只画这一张素材，不叠大类算法层 */
    private fun playFx(kind: SceneEffectKind, info: FxCatalog.FxInfo) {
        showPreview(
            SceneEffectCue(
                para = 0,
                kind = kind,
                level = 3,
                durationMs = 4000L,
                source = "manage-preview-fx",
                fxId = info.id,
                burst = true
            ),
            fxOnly = true
        )
    }

    private fun showPreview(
        cue: SceneEffectCue,
        fxOnly: Boolean = false,
        algoOnly: Boolean = false
    ) {
        val view = binding.previewView
        view.removeCallbacks(hidePreview)
        view.fxOnlyPreview = fxOnly
        view.algoOnlyPreview = algoOnly
        view.visibility = View.VISIBLE
        view.clearSceneEffects()
        view.post { view.show(cue) }
        view.postDelayed(hidePreview, 4300L)
    }

    override fun onDestroy() {
        binding.previewView.removeCallbacks(hidePreview)
        binding.previewView.clearSceneEffects()
        super.onDestroy()
    }

    private inner class EffectAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<Row> = emptyList()

        fun submit(list: List<Row>) {
            rows = list
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderVH(ItemSceneEffectBinding.inflate(inflater, parent, false))
            } else {
                FxVH(ItemSceneEffectFxBinding.inflate(inflater, parent, false))
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderVH).bind(row)
                is Row.Algo -> (holder as FxVH).bindAlgo(row)
                is Row.Fx -> (holder as FxVH).bind(row)
            }
        }

        inner class HeaderVH(val b: ItemSceneEffectBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(row: Row.Header) {
                val e = row.entry
                val kindOn = SceneEffectSettings.isEnabled(e.kind)
                b.tvName.text = e.name
                // 每个大类都能展开：第一条固定是原版算法效果，之后才是素材
                b.tvExpand.text = if (row.expanded) "▾" else "▸"
                b.tvExpand.setOnClickListener { toggle(e.kind) }
                b.root.setOnClickListener { toggle(e.kind) }
                b.tvDesc.text = if (row.totalFx == 0) {
                    "${e.group} · ${e.desc} · 仅原版算法效果（素材已删除）· 点箭头展开"
                } else {
                    buildString {
                        append(e.group).append(" · ").append(e.desc)
                        append(" · 特效 ").append(row.usableFx).append("/").append(row.totalFx)
                        append(" · 点箭头展开")
                    }
                }
                b.root.alpha = if (kindOn) 1f else 0.5f
                b.swtEnabled.setOnCheckedChangeListener(null)
                b.swtEnabled.isChecked = kindOn
                b.swtEnabled.setOnCheckedChangeListener { _, checked ->
                    SceneEffectSettings.setEnabled(e.kind, checked)
                    b.root.alpha = if (checked) 1f else 0.5f
                    binding.recyclerView.post { applyFilter(currentKey()) }
                }
                b.ivPlay.setOnClickListener { playKind(e) }
            }
        }

        inner class FxVH(val b: ItemSceneEffectFxBinding) :
            RecyclerView.ViewHolder(b.root) {

            /** 原版矢量/算法绘制条目：单独播放、单独开关 */
            fun bindAlgo(row: Row.Algo) {
                val e = row.entry
                val on = SceneEffectSettings.isAlgoEnabled(e.kind)
                b.tvFxName.text = "原版矢量绘制 · ${e.name}"
                b.tvFxDesc.text = "项目原生 Canvas 算法效果（无素材）· 可单独开关"
                b.root.alpha = if (on) 1f else 0.5f
                b.swtFxEnabled.setOnCheckedChangeListener(null)
                b.swtFxEnabled.isChecked = on
                b.swtFxEnabled.setOnCheckedChangeListener { _, checked ->
                    SceneEffectSettings.setAlgoEnabled(e.kind, checked)
                    binding.recyclerView.post { applyFilter(currentKey()) }
                }
                b.ivFxPlay.setOnClickListener { playAlgo(e) }
            }

            fun bind(row: Row.Fx) {
                val info = row.info
                val on = SceneEffectSettings.isFxEnabled(info.id)
                b.tvFxName.text = info.zh
                b.tvFxDesc.text = buildString {
                    append(info.catName).append(" · ").append(info.frames).append(" 帧 · ")
                    append(if (info.loop) "循环" else "单次")
                }
                b.root.alpha =
                    if (on && SceneEffectSettings.isEnabled(row.parent)) 1f else 0.5f
                b.swtFxEnabled.setOnCheckedChangeListener(null)
                b.swtFxEnabled.isChecked = on
                b.swtFxEnabled.setOnCheckedChangeListener { _, checked ->
                    SceneEffectSettings.setFxEnabled(info.id, checked)
                    binding.recyclerView.post { applyFilter(currentKey()) }
                }
                b.ivFxPlay.setOnClickListener { playFx(row.parent, info) }
            }
        }
    }
}
