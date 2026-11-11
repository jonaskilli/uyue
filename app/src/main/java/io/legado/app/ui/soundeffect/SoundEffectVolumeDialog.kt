package io.legado.app.ui.soundeffect

import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.legado.app.databinding.DialogSoundEffectVolumeBinding
import io.legado.app.databinding.ItemVolumeSegmentBinding
import io.legado.app.help.audio.Mp3GainUtil
import java.util.Locale

/**
 * 「修改音量」对话框：一个音效 JSON 的 `audios` 数组有几条，就显示几段。
 *
 * - 打开时只做 base64 解码与格式校验，**不转换音频、不落盘**；
 * - 每段：标题「音效一」+ 播放按钮 + 0.0~3.0 的线性滑块，初值读自 MP3 标签（无标签 1.0）；
 * - **只有点该段播放按钮时**才按当前滑块值在内存里转换并播放，
 *   拖动滑块不转换（避免反复试调产生无谓开销）；试听不叠加全局「音效音量」滑块；
 * - 点「保存」时一次性生成全部段的结果，由 Activity 写回 JSON；
 * - 不在支持列表的段禁用滑块、给出原因，保存时保持原字节不变。
 */
class SoundEffectVolumeDialog(
    private val activity: SoundEffectRuleActivity,
    private val segments: List<Segment>,
    private val onSave: (List<SegmentState>) -> Unit
) {

    data class Segment(
        val effectName: String,
        val indexInFile: Int,
        /** 外显名字：列表里看到的规则名 / AI 音效名 */
        val displayName: String,
        /** 用来识别音效的括号名字，如「(打雷音效)」；AI 音效为空 */
        val effectLabel: String,
        /** 段序号：音效一 / 音效二… */
        val label: String,
        val originalBytes: ByteArray,
        val supported: Boolean,
        val reason: String,
        val currentGain: Float,
        /** AI 音效（广播剧音效库）是裸 MP3：非空时保存直接写回这个文件，不走 JSON 的 base64 流程 */
        val audioFile: java.io.File? = null
    )

    data class SegmentState(val segment: Segment, val gain: Float, val bytes: ByteArray)

    fun show() {
        val binding = DialogSoundEffectVolumeBinding.inflate(activity.layoutInflater)
        if (segments.isEmpty()) {
            binding.tvHint.text = "没有找到关联音效"
        }
        val gains = segments.map { it.currentGain }.toMutableList()
        // 同一个音效名字（同一个 JSON）里的多个轮换音频共用一个组标题：
        // 外显名字与括号音效名字只在该组第一段显示，后续段只显示「音效N + 按钮 + 滑块」
        val firstIndexByEffect = HashMap<String, Int>()
        segments.forEachIndexed { i, seg ->
            if (!firstIndexByEffect.containsKey(seg.effectName)) {
                firstIndexByEffect[seg.effectName] = i
            }
        }

        segments.forEachIndexed { index, seg ->
            val item = ItemVolumeSegmentBinding.inflate(
                LayoutInflater.from(activity), binding.llSegments, false
            )
            // 名字层次：外显名字 → (音效名字) → 音效一 / 音效二…
            // 前三种：播放按钮与倍率在「音效N」这一行，每段各自一个按钮 + 一个滑块
            // AI 音效：没有括号名字、也没有轮换段，按钮与倍率直接跟在外显名字后面
            val isAi = seg.audioFile != null
            val showHeader = isAi || firstIndexByEffect[seg.effectName] == index
            item.tvName.text = seg.displayName
            item.tvName.visibility = if (showHeader) View.VISIBLE else View.GONE
            item.tvEffect.text = seg.effectLabel
            item.tvEffect.visibility = when {
                isAi || !showHeader || seg.effectLabel.isEmpty() -> View.GONE
                else -> View.VISIBLE
            }
            item.tvSegment.text = seg.label
            item.llSegment.visibility = if (isAi) View.GONE else View.VISIBLE
            item.tvGainAi.visibility = if (isAi) View.VISIBLE else View.GONE
            item.btnPlayAi.visibility = if (isAi) View.VISIBLE else View.GONE

            val gainView = if (isAi) item.tvGainAi else item.tvGain
            val playButton = if (isAi) item.btnPlayAi else item.btnPlay

            /** 统一写入倍率：文字 + 滑块位置；滑块回设 progress 时不再反向覆盖倍率 */
            fun applyGain(value: Float) {
                val g = value.coerceIn(0f, Mp3GainUtil.MAX_GAIN)
                gains[index] = g
                gainView.text = format(g)
                item.seekGain.progress = Mp3GainUtil.gainToSeek(g)
            }

            gainView.text = format(gains[index])
            item.seekGain.progress = Mp3GainUtil.gainToSeek(gains[index])
            if (!seg.supported) {
                item.seekGain.isEnabled = false
                item.btnMinus.isEnabled = false
                item.btnPlus.isEnabled = false
                item.tvError.visibility = View.VISIBLE
                item.tvError.text = "格式不支持修改音量：${seg.reason}"
            }
            item.seekGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    // 由 applyGain 回设 progress 触发的回调要忽略，否则整数进度会反算覆盖精确倍率
                    if (!fromUser) return
                    val gain = Mp3GainUtil.seekToGain(progress)
                    gains[index] = gain
                    gainView.text = format(gain)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
            item.btnMinus.setOnClickListener {
                if (seg.supported) applyGain(stepGain(gains[index], up = false))
            }
            item.btnPlus.setOnClickListener {
                if (seg.supported) applyGain(stepGain(gains[index], up = true))
            }
            playButton.setOnClickListener {
                // 打开时是多少、现在还是多少 → 直接播当前音频，不做任何转换
                val changed = seg.supported &&
                    kotlin.math.abs(gains[index] - seg.currentGain) > 0.005f
                val bytes = if (changed) {
                    val r = Mp3GainUtil.render(seg.originalBytes, gains[index])
                    if (r.ok) r.bytes ?: seg.originalBytes else {
                        toast(r.reason)
                        seg.originalBytes
                    }
                } else {
                    seg.originalBytes
                }
                SoundEffectPlayer.playBytes(bytes) { error -> error?.let { toast(it) } }
            }
            binding.llSegments.addView(item.root)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("修改音量")
            .setView(binding.root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        // 用 setOnClickListener 而非 Builder 的回调，校验失败时可以不关闭对话框
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val states = segments.mapIndexed { index, seg ->
                val gain = gains[index]
                val bytes = if (seg.supported) {
                    val r = Mp3GainUtil.render(seg.originalBytes, gain)
                    if (r.ok && r.bytes != null) r.bytes else seg.originalBytes
                } else {
                    seg.originalBytes
                }
                SegmentState(seg, gain, bytes)
            }
            onSave(states)
            dialog.dismiss()
        }
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

    private fun format(gain: Float): String = String.format(Locale.US, "%.2f", gain)

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
