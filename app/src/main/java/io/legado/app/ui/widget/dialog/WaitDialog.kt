package io.legado.app.ui.widget.dialog

import android.app.Dialog
import android.content.Context
import android.view.WindowManager
import io.legado.app.databinding.DialogWaitBinding


@Suppress("unused")
class WaitDialog(context: Context) : Dialog(context) {

    val binding = DialogWaitBinding.inflate(layoutInflater)

    init {
        setCanceledOnTouchOutside(false)
        setContentView(binding.root)
        // 放宽窗口宽度（默认 wrap_content 会把长进度文本压成单行省略），
        // 配合布局里的多行设置，长文本可完整换行显示
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.8f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    fun setText(text: String): WaitDialog {
        binding.tvMsg.text = text
        return this
    }

    fun setText(res: Int): WaitDialog {
        binding.tvMsg.setText(res)
        return this
    }

}