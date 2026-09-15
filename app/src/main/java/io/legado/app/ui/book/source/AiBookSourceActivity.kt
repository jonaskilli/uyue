package io.legado.app.ui.book.source

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityAiBookSourceBinding
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.AiBookSourceGenerator
import io.legado.app.utils.GSON
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 生成书源。
 *
 * 用法：填一个小说网站地址 → 点「开始生成」。
 * 过程是「算法抓页面 + AI 写规则」，详见 [AiBookSourceGenerator]。
 * 生成成功后可直接保存到书源列表，之后就能像普通书源那样搜索、加书架、阅读。
 */
class AiBookSourceActivity : BaseActivity<ActivityAiBookSourceBinding>() {

    override val binding by viewBinding(ActivityAiBookSourceBinding::inflate)

    /** 生成结果，保存按钮据此落库 */
    private var generated: BookSource? = null
    private var generateJob: Job? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.btnGenerate.setOnClickListener { startGenerate() }
        binding.btnSave.setOnClickListener { saveSource() }
        binding.btnLogin.setOnClickListener { gotoLogin() }
    }

    /** 打开 WebView 登录，登录后 cookie 按域名存入 CookieStore，生成与阅读都会自动带上 */
    private fun gotoLogin() {
        val loginUrl = binding.etLoginUrl.text?.toString()?.trim().orEmpty()
        if (loginUrl.isBlank()) {
            toastOnUi("请先填写登录地址")
            return
        }
        val siteUrl = binding.etUrl.text?.toString()?.trim().orEmpty()
            .ifBlank { loginUrl }
        WebViewLoginActivity.start(this, loginUrl, siteUrl)
    }

    override fun onDestroy() {
        super.onDestroy()
        generateJob?.cancel()
    }

    private fun startGenerate() {
        val url = binding.etUrl.text?.toString()?.trim().orEmpty()
        if (url.isBlank()) {
            toastOnUi("请先填写小说网站地址")
            return
        }
        val keyword = binding.etKeyword.text?.toString()?.trim()
            .takeIf { !it.isNullOrBlank() } ?: "剑"
        val loginUrl = binding.etLoginUrl.text?.toString()?.trim()
            .takeIf { !it.isNullOrBlank() }
        val includeExplore = binding.cbExplore.isChecked
        val fixRounds = binding.etFixRounds.text?.toString()?.trim()
            ?.toIntOrNull()?.coerceIn(0, 8) ?: 2

        generated = null
        binding.etResult.setText("")
        binding.btnSave.alpha = 0.4f
        binding.btnGenerate.alpha = 0.4f
        binding.tvProgress.text = "准备中…"

        generateJob?.cancel()
        generateJob = lifecycleScope.launch {
            val result = AiBookSourceGenerator.generate(
                url, keyword, loginUrl, includeExplore, fixRounds
            ) { message ->
                // 回调在 IO 线程，进度必须切回主线程更新 UI
                runOnUiThread { binding.tvProgress.text = message }
            }
            binding.btnGenerate.alpha = 1f
            result.onSuccess { res ->
                val source = res.source
                generated = source
                binding.tvProgress.text = buildString {
                    append("生成完成：").append(source.bookSourceName)
                    if (res.fixRounds > 0) {
                        append("（AI 修正 ").append(res.fixRounds).append(" 轮）")
                    }
                    append('\n')
                    append("五链路验证：\n")
                    res.verify.forEach { v ->
                        append('[').append(if (v.ok) "通过" else "失败").append("] ")
                            .append(v.step).append("：").append(v.message).append('\n')
                    }
                    if (res.verify.any { !it.ok }) {
                        append("（存在失败环节的规则可能不可用，建议手动校验后再用）")
                    }
                }
                binding.etResult.setText(runCatching { GSON.toJson(source) }.getOrDefault(""))
                binding.btnSave.alpha = if (res.verify.all { it.ok }) 1f else 0.7f
            }.onFailure {
                binding.tvProgress.text = "生成失败：${it.message}"
                toastOnUi(it.message ?: "生成失败")
            }
        }
    }

    private fun saveSource() {
        val source = generated ?: return
        binding.btnSave.alpha = 0.4f
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { SourceHelp.insertBookSource(source) }.exceptionOrNull()
            }
            if (error == null) {
                toastOnUi("已保存到书源列表：${source.bookSourceName}")
                finish()
            } else {
                binding.btnSave.alpha = 1f
                toastOnUi("保存失败：${error.message}")
            }
        }
    }

    companion object {
        /** 直接进入本界面的入口（供设置页跳转） */
        val startActivityIntent get() = AiBookSourceActivity::class.java
    }
}
