package io.legado.app.ui.soundeffect

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.databinding.DialogSoundEffectVolumeBinding
import io.legado.app.databinding.ItemVolumeSegmentBinding
import io.legado.app.help.audio.Mp3GainUtil
import io.legado.app.utils.getCompatColor
import java.io.File
import java.util.Collections
import java.util.Locale

/**
 * 音效文件编辑对话框。
 *
 * 前三种模式：按规则里的 `(音效名)` 分组，每组一段 Header + 多个 MP3 卡片 + 添加按钮；
 * 卡片复用 TTS 配置列表的圆角描边样式，长按可在同一 JSON 内拖动排序。
 * 拖动时 label 跟着段对象走，不重新编号；保存后 Activity 按显示顺序重写 audios，
 * 下次打开再由下标重新生成「音效一 / 音效二…」。
 *
 * AI 音效模式：只有一个裸 MP3，沿用原来的音量调整，不显示分组头、删除和添加。
 */
class SoundEffectVolumeDialog(
    private val activity: SoundEffectRuleActivity,
    sourceSegments: List<Segment>,
    private val onPickAudios: (String) -> Unit,
    private val onSave: (List<SegmentState>) -> Unit,
    private val onDismiss: () -> Unit = {}
) {

    private val groups = LinkedHashMap<String, MutableList<Segment>>()
    private lateinit var binding: DialogSoundEffectVolumeBinding
    private lateinit var adapter: FileAdapter
    private val cn = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")

    data class Segment(
        val effectName: String,
        val indexInFile: Int,
        /** 外显名字：列表里看到的规则名 / AI 音效名 */
        val displayName: String,
        /** 用来识别音效的括号名字，如「(打雷音效)」；AI 音效为空 */
        val effectLabel: String,
        /** 段序号：音效一 / 音效二…；拖动时不重新计算，跟着对象走 */
        val label: String,
        val originalBytes: ByteArray,
        val supported: Boolean,
        val reason: String,
        /** 打开时从 MP3 标签读到的原始倍率 */
        val currentGain: Float,
        /** 当前倍率 */
        var gain: Float = currentGain,
        /** 已标记删除，保存时跳过 */
        var deleted: Boolean = false,
        /** 本次新添加、尚未写盘的音频 */
        val isNew: Boolean = false,
        /** AI 音效（广播剧音效库）是裸 MP3：非空时保存直接写回这个文件，不走 JSON 的 base64 流程 */
        val audioFile: File? = null
    )

    data class SegmentState(val segment: Segment, val gain: Float, val bytes: ByteArray)

    private sealed class Row {
        data class Header(val displayName: String, val effectLabel: String) : Row()
        data class SegmentRow(val segment: Segment) : Row()
        data class Add(val effectName: String) : Row()
        object Divider : Row()
    }

    init {
        sourceSegments.forEach { segment ->
            groups.getOrPut(segment.effectName) { mutableListOf() }.add(segment)
        }
    }

    fun show() {
        binding = DialogSoundEffectVolumeBinding.inflate(activity.layoutInflater)
        binding.rvSegments.layoutManager = LinearLayoutManager(activity)
        adapter = FileAdapter()
        binding.rvSegments.adapter = adapter
        adapter.createItemTouchHelper().attachToRecyclerView(binding.rvSegments)
        updateHint()
        render()

        val editable = groups.values.flatten().any { it.audioFile == null }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (editable) "修改音效文件" else "修改音量")
            .setView(binding.root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val states = groups.values.flatten().filter { !it.deleted }.map { segment ->
                val bytes = if (segment.supported) {
                    val r = Mp3GainUtil.render(segment.originalBytes, segment.gain)
                    if (r.ok && r.bytes != null) r.bytes else segment.originalBytes
                } else {
                    segment.originalBytes
                }
                SegmentState(segment, segment.gain, bytes)
            }
            onSave(states)
            dialog.dismiss()
        }
    }

    /** 文件选择器选中后，把音频追加到对应音效分组，先只改内存，点保存才写盘。 */
    fun addAudios(effectName: String, bytesList: List<ByteArray>) {
        if (bytesList.isEmpty()) return
        val group = groups[effectName] ?: return
        val template = group.firstOrNull() ?: return
        var offset = group.count { !it.deleted }

        val added = bytesList.map { bytes ->
            val info = Mp3GainUtil.readGain(bytes)
            val v = Mp3GainUtil.validate(bytes)
            val label = "新增音频" + (cn.getOrNull(offset) ?: (offset + 1).toString())
            offset++
            Segment(
                effectName = effectName,
                indexInFile = -1,
                displayName = template.displayName,
                effectLabel = template.effectLabel,
                label = label,
                originalBytes = bytes,
                supported = v.ok,
                reason = v.reason,
                currentGain = info.current,
                gain = info.current,
                isNew = true
            )
        }
        group.addAll(added)
        render()
        toast("已添加 ${added.size} 个音频，点保存后写入")
    }

    private fun render() {
        if (!::adapter.isInitialized) return
        adapter.submit(buildRows())
    }

    private fun buildRows(): List<Row> {
        val rows = ArrayList<Row>()
        val keys = groups.keys.toList()
        groups.entries.forEachIndexed { groupIndex, (effectName, segments) ->
            val isAiGroup = segments.firstOrNull()?.audioFile != null
            if (!isAiGroup) {
                val first = segments.firstOrNull()
                rows.add(Row.Header(first?.displayName.orEmpty(), first?.effectLabel.orEmpty()))
            }
            segments.forEach { rows.add(Row.SegmentRow(it)) }
            if (!isAiGroup) rows.add(Row.Add(effectName))
            if (groupIndex < keys.size - 1) rows.add(Row.Divider)
        }
        return rows
    }

    private fun updateHint() {
        val all = groups.values.flatten()
        binding.tvHint.text = when {
            all.isEmpty() -> "没有找到关联音效"
            all.any { it.audioFile == null } ->
                "长按卡片可上下拖动排序；点播放试听；只有点「保存」才会写回音效文件"
            else -> "调整倍数后点右侧播放按钮试听；只有点「保存」才会写回音效文件"
        }
    }

    private fun confirmDelete(segment: Segment) {
        AlertDialog.Builder(activity)
            .setTitle("删除音效")
            .setMessage("删除「${segment.label}」？保存后生效。")
            .setPositiveButton("删除") { _, _ ->
                val group = groups[segment.effectName] ?: return@setPositiveButton
                if (group.count { !it.deleted } <= 1) {
                    toast("至少保留一个音频")
                    return@setPositiveButton
                }
                if (segment.isNew) {
                    group.remove(segment)
                } else {
                    segment.deleted = true
                }
                updateHint()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class FileAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val rows = ArrayList<Row>()

        fun submit(newRows: List<Row>) {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        fun createItemTouchHelper(): ItemTouchHelper {
            val callback = object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ): Int {
                    val row = rows.getOrNull(viewHolder.bindingAdapterPosition)
                    return if (row is Row.SegmentRow &&
                        row.segment.audioFile == null &&
                        !row.segment.deleted
                    ) {
                        makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                    } else {
                        0
                    }
                }

                override fun isLongPressDragEnabled(): Boolean = true

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    val fromRow = rows.getOrNull(from)
                    val toRow = rows.getOrNull(to)
                    if (fromRow !is Row.SegmentRow || toRow !is Row.SegmentRow) return false
                    if (fromRow.segment.effectName != toRow.segment.effectName) return false

                    val group = groups[fromRow.segment.effectName] ?: return false
                    val fromIndex = group.indexOf(fromRow.segment)
                    val toIndex = group.indexOf(toRow.segment)
                    if (fromIndex < 0 || toIndex < 0) return false

                    Collections.swap(group, fromIndex, toIndex)
                    Collections.swap(rows, from, to)
                    notifyItemMoved(from, to)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
            }
            return ItemTouchHelper(callback)
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.SegmentRow -> TYPE_SEGMENT
            is Row.Add -> TYPE_ADD
            Row.Divider -> TYPE_DIVIDER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                TYPE_HEADER -> HeaderHolder(makeHeaderView(parent.context))
                TYPE_SEGMENT -> SegmentHolder(
                    ItemVolumeSegmentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                )
                TYPE_ADD -> AddHolder(makeAddView(parent.context))
                else -> DividerHolder(makeDivider(parent.context))
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).textView.text = buildString {
                    append(row.displayName)
                    if (row.effectLabel.isNotBlank()) {
                        append('\n')
                        append(row.effectLabel)
                    }
                }
                is Row.SegmentRow -> bindSegment((holder as SegmentHolder).binding, row.segment)
                is Row.Add -> (holder as AddHolder).textView.apply {
                    text = "＋ 添加音频到「${row.effectName}」"
                    setOnClickListener { onPickAudios(row.effectName) }
                }
                Row.Divider -> Unit
            }
        }

        override fun getItemCount(): Int = rows.size

        private fun bindSegment(item: ItemVolumeSegmentBinding, segment: Segment) {
            val isAi = segment.audioFile != null

            item.tvName.visibility = if (isAi) View.VISIBLE else View.GONE
            item.tvName.text = if (isAi) segment.displayName else ""
            item.tvEffect.visibility = View.GONE
            item.tvSegment.text = if (segment.deleted) "${segment.label}（已删）" else segment.label
            item.tvSize.text = "${sizeKb(segment.originalBytes.size)} KB"
            item.root.alpha = if (segment.deleted) 0.45f else 1f

            item.llSegment.visibility = if (isAi) View.GONE else View.VISIBLE
            item.tvGainAi.visibility = if (isAi) View.VISIBLE else View.GONE
            item.btnPlayAi.visibility = if (isAi) View.VISIBLE else View.GONE
            item.btnDragHandle.visibility = if (isAi) View.GONE else View.VISIBLE
            item.btnDeleteSegment.visibility = if (isAi) View.GONE else View.VISIBLE
            item.btnDeleteSegment.isEnabled = !segment.deleted
            item.btnDeleteSegment.setOnClickListener { confirmDelete(segment) }

            val gainView = if (isAi) item.tvGainAi else item.tvGain
            val playButton = if (isAi) item.btnPlayAi else item.btnPlay

            fun applyGain(value: Float) {
                val g = value.coerceIn(0f, Mp3GainUtil.MAX_GAIN)
                segment.gain = g
                gainView.text = format(g)
                item.seekGain.progress = Mp3GainUtil.gainToSeek(g)
            }

            gainView.text = format(segment.gain)
            item.seekGain.progress = Mp3GainUtil.gainToSeek(segment.gain)
            if (!segment.supported || segment.deleted) {
                item.seekGain.isEnabled = false
                item.btnMinus.isEnabled = false
                item.btnPlus.isEnabled = false
                if (!segment.supported) {
                    item.tvError.visibility = View.VISIBLE
                    item.tvError.text = "格式不支持修改音量：${segment.reason}"
                }
            }
            playButton.isEnabled = !segment.deleted

            item.seekGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || segment.deleted) return
                    val gain = Mp3GainUtil.seekToGain(progress)
                    segment.gain = gain
                    gainView.text = format(gain)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
            item.btnMinus.setOnClickListener {
                if (segment.supported && !segment.deleted) applyGain(stepGain(segment.gain, up = false))
            }
            item.btnPlus.setOnClickListener {
                if (segment.supported && !segment.deleted) applyGain(stepGain(segment.gain, up = true))
            }
            playButton.setOnClickListener {
                if (segment.deleted) return@setOnClickListener
                val changed = segment.supported &&
                    kotlin.math.abs(segment.gain - segment.currentGain) > 0.005f
                val bytes = if (changed) {
                    val r = Mp3GainUtil.render(segment.originalBytes, segment.gain)
                    if (r.ok) r.bytes ?: segment.originalBytes else {
                        toast(r.reason)
                        segment.originalBytes
                    }
                } else {
                    segment.originalBytes
                }
                SoundEffectPlayer.playBytes(bytes) { error -> error?.let { toast(it) } }
            }
        }

        private fun makeHeaderView(context: android.content.Context): TextView =
            TextView(context).apply {
                textSize = 14f
                setTextColor(context.getCompatColor(R.color.primaryText))
                setPadding(12, 10, 12, 4)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }

        private fun makeAddView(context: android.content.Context): TextView =
            TextView(context).apply {
                textSize = 14f
                setTextColor(context.getCompatColor(R.color.primaryText))
                setPadding(12, 10, 12, 10)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }

        private fun makeDivider(context: android.content.Context): View {
            val height = context.resources.displayMetrics.density.toInt().coerceAtLeast(1)
            return View(context).apply {
                setBackgroundColor(context.getCompatColor(R.color.divider))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    height
                ).apply {
                    topMargin = 8
                    bottomMargin = 8
                }
            }
        }
    }

    private class HeaderHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
    private class AddHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
    private class DividerHolder(view: View) : RecyclerView.ViewHolder(view)
    private class SegmentHolder(val binding: ItemVolumeSegmentBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SEGMENT = 1
        const val TYPE_ADD = 2
        const val TYPE_DIVIDER = 3
    }

    /**
     * 加减按钮的 0.05 步进。
     * - 当前已在 0.05 倍数上：直接 ±0.05；
     * - 不在网格上（拖动滑块或从标签读回的值）：本次点击先吸附到相邻网格点
     *   （加向上、减向下），后续点击才按 0.05 走，保证单次变化不超过 0.05 且方向不倒退。
     */
    private fun stepGain(current: Float, up: Boolean): Float {
        val step = 0.05f
        val units = current / step
        val epsilon = 0.0001f
        val aligned = if (up) {
            kotlin.math.ceil(units - epsilon) * step
        } else {
            kotlin.math.floor(units + epsilon) * step
        }
        val onGrid = kotlin.math.abs(current - aligned) < 0.0025f
        val next = if (onGrid) aligned + (if (up) step else -step) else aligned
        return next.coerceIn(0f, Mp3GainUtil.MAX_GAIN)
    }

    private fun sizeKb(bytes: Int): Int = maxOf(1, Math.round(bytes / 1024f))

    private fun format(gain: Float): String = String.format(Locale.US, "%.2f", gain)

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
