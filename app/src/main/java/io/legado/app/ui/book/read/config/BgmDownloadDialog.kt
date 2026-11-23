package io.legado.app.ui.book.read.config

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogBgmDownloadBinding
import io.legado.app.help.EngineDataDir
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.Cronet
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.alert
import io.legado.app.service.AiEffectIndex
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.BgmManager
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import splitties.init.appCtx
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 下载音效和音乐对话框。
 *
 * 四个下载项，全部来自魔搭仓库 mingwuyan/bdyinxiao-ttsrv-backup（zip 整包，下载后自动解压归位）：
 *  1. 背景音乐 —— 背景音乐.zip：下载前**必须**重新选择一个文件夹（避免新下载内容和
 *     原文件夹里已有的内容混在一起），解压到所选文件夹（mp3 + 背景音乐的关键词.json 等）；
 *  2. 全部音效 —— 全部音效.zip：根目录的 ttsrv-replaces*.json 规则文件 → 当前引擎
 *     数据目录（EngineDataDir），bendiyinxiao2/ 下的音效 json → chajian/bendiyinxiao2/；
 *  3. 全部音效旧版 —— 全部音效旧版.zip：与「全部音效」相同的解压规则，只是使用更新前的旧包；
 *  4. AI广播剧音效库 —— AI广播剧音效库.zip：zip 内 广播剧音效库/ 前缀去掉后整个解压到
 *     音效库目录（AppConfig.aiEffectLocalDir），根目录的索引 json 额外落一份为
 *     ai_effect_index_kw.json（App 读取索引用的文件名），完成后刷新索引缓存。
 */
class BgmDownloadDialog : BaseDialogFragment(R.layout.dialog_bgm_download) {

    private val binding by viewBinding(DialogBgmDownloadBinding::bind)

    @Volatile
    private var isDownloadCancelled = false

    private var downloadJob: Job? = null

    /** 背景音乐：等待用户重选文件夹后再下载 */
    private var pendingBgmDownload = false

    /** 背景音乐下载前强制重选文件夹（与音画设置页的目录选择器同一套契约） */
    private val selectDir = registerForActivityResult(HandleFileContract()) { result ->
        if (!pendingBgmDownload) return@registerForActivityResult
        pendingBgmDownload = false
        val uri = result.uri
        if (uri == null) {
            showProgress("已取消选择文件夹，未下载背景音乐")
            return@registerForActivityResult
        }
        AppConfig.bgmPath = uri.toString()
        // 无论原先是否设置过背景音乐路径，这里都以本次选择的为准：
        // 原先未设定 → 该路径成为背景音乐目录；原先已设定 → 覆盖为新路径。
        // 解压完成后 loadBgmFiles() 会按新路径重新加载文件列表。
        startDownload(TARGET_BGM)
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.85f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnCancel.setOnClickListener { onCancelClicked() }
        binding.btnDownload.setOnClickListener { onDownloadClicked() }
    }

    private fun onDownloadClicked() {
        if (downloadJob?.isActive == true) return
        val target = when {
            binding.rbBgm.isChecked -> TARGET_BGM
            binding.rbAllEffect.isChecked -> TARGET_ALL_SFX
            binding.rbAllEffectOld.isChecked -> TARGET_ALL_SFX_OLD
            else -> TARGET_AI_LIB
        }
        // 下载前提示：会覆盖原有文件，须用户点确定才继续
        alert("下载提示", OVERWRITE_WARNING) {
            okButton { proceedAfterConfirm(target) }
            cancelButton()
        }.show()
    }

    /** 用户点确定后：背景音乐先选文件夹，其余直接开始下载 */
    private fun proceedAfterConfirm(target: Int) {
        isDownloadCancelled = false
        when (target) {
            TARGET_BGM -> {
                // 背景音乐：必须先重新选择文件夹
                pendingBgmDownload = true
                showProgress("请先选择背景音乐保存文件夹...")
                selectDir.launch { mode = HandleFileContract.DIR }
            }
            TARGET_ALL_SFX, TARGET_ALL_SFX_OLD -> startDownload(target)
            else -> startDownload(TARGET_AI_LIB)
        }
    }

    private fun onCancelClicked() {
        if (downloadJob?.isActive == true) {
            isDownloadCancelled = true
            binding.btnDownload.isEnabled = false
            showProgress("正在取消，等待当前步骤完成...")
        } else {
            dismiss()
        }
    }

    private fun showProgress(text: String) {
        binding.tvProgress.visibility = View.VISIBLE
        binding.tvProgress.text = text
    }

    private fun setDownloading(downloading: Boolean) {
        binding.btnDownload.isEnabled = !downloading
        binding.btnDownload.text = if (downloading) "下载中..." else "下载"
    }

    private fun startDownload(target: Int) {
        val zipName = when (target) {
            TARGET_BGM -> "背景音乐.zip"
            TARGET_ALL_SFX -> "全部音效.zip"
            TARGET_ALL_SFX_OLD -> "全部音效旧版.zip"
            else -> "AI广播剧音效库.zip"
        }
        setDownloading(true)
        showProgress("正在下载：$zipName")
        downloadJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                downloadAndUnzip(target, zipName)
            } catch (e: Exception) {
                e.printStackTrace()
                "下载失败：${e.localizedMessage}"
            }
            withContext(Dispatchers.Main) {
                setDownloading(false)
                showProgress(result)
                toastOnUi(result)
                if (target == TARGET_BGM) {
                    BgmManager.loadBgmFiles()
                } else if (target == TARGET_AI_LIB) {
                    // 索引文件已更新，丢弃内存缓存让它重读
                    AiEffectIndex.reload()
                }
            }
        }
    }

    /** 下载 zip 到缓存目录，解压归位；返回给用户看的结果文案 */
    private suspend fun downloadAndUnzip(target: Int, zipName: String): String {
        val url = MODELSCOPE_BASE + Uri.encode(zipName)
        val tempFile = File(appCtx.cacheDir, "bgm_download_${System.currentTimeMillis()}.zip")
        try {
            val request = Request.Builder().url(url).build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return "下载失败：HTTP ${response.code}"
                val body = response.body ?: return "下载失败：服务器无内容"
                val total = body.contentLength()
                var done = 0L
                var lastPct = -1
                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (isDownloadCancelled) return "已取消下载"
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct && pct % 5 == 0) {
                                    lastPct = pct
                                    updateProgress(
                                        "正在下载：$zipName  $pct%%（%.1fMB）".format(total / 1048576.0)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (tempFile.length() == 0L) return "下载失败：文件为空"
            updateProgress("下载完成，正在解压...")
            return when (target) {
                TARGET_BGM -> unzipToBgm(tempFile)
                TARGET_ALL_SFX -> unzipToSfxDirs(tempFile)
                TARGET_ALL_SFX_OLD -> unzipToSfxDirs(tempFile, "全部音效旧版")
                else -> unzipToAiEffectDir(tempFile)
            }
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun updateProgress(text: String) = withContext(Dispatchers.Main) {
        showProgress(text)
    }

    /** 背景音乐：解压到用户刚选择的文件夹（支持 SAF 目录） */
    private suspend fun unzipToBgm(zipFile: File): String {
        val bgmPath = AppConfig.bgmPath
        if (bgmPath.isNullOrBlank()) return "未选择背景音乐文件夹"
        var count = 0
        forEachZipEntry(zipFile) { entry, stream ->
            if (entry.isDirectory) return@forEachZipEntry
            val bytes = stream.readBytes()
            if (saveToBgmDir(bgmPath, entry.name, bytes)) count++
            updateProgress("正在解压：${entry.name}（已完成 $count 个）")
        }
        return "背景音乐下载完成，共 $count 个文件\n" +
            "背景音乐已切换为该文件夹：${describePath(bgmPath)}"
    }

    /** 全部音效（含旧版）：规则文件 → 引擎数据目录；音效 json → bendiyinxiao2 */
    private suspend fun unzipToSfxDirs(zipFile: File, label: String = "全部音效"): String {
        val sfxDir = File(BaseReadAloudService.SOUND_EFFECT_DIR)
        if (!sfxDir.exists()) sfxDir.mkdirs()
        val ruleDir = File(EngineDataDir.baseDir())
        if (!ruleDir.exists()) ruleDir.mkdirs()
        var ruleCount = 0
        var sfxCount = 0
        forEachZipEntry(zipFile) { entry, stream ->
            if (entry.isDirectory) return@forEachZipEntry
            val name = entry.name
            val isSfx = name.startsWith("bendiyinxiao2/")
            val dest = when {
                isSfx -> File(sfxDir, name.removePrefix("bendiyinxiao2/"))
                name.endsWith(".json") -> {
                    ruleCount++
                    File(ruleDir, name)
                }
                else -> null
            } ?: return@forEachZipEntry
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out -> stream.copyTo(out) }
            if (isSfx) sfxCount++
            updateProgress("正在解压：$name")
        }
        return "${label}下载完成：规则文件 $ruleCount 个 → ${ruleDir.absolutePath}\n" +
            "音效文件 $sfxCount 个 → ${sfxDir.absolutePath}"
    }

    /** AI广播剧音效库：去掉 zip 内 广播剧音效库/ 前缀解压到音效库目录；索引落为 ai_effect_index_kw.json */
    private suspend fun unzipToAiEffectDir(zipFile: File): String {
        val libDir = File(AppConfig.aiEffectLocalDir)
        if (!libDir.exists()) libDir.mkdirs()
        var count = 0
        var indexOk = false
        forEachZipEntry(zipFile) { entry, stream ->
            if (entry.isDirectory) return@forEachZipEntry
            val name = entry.name
            val dest = when {
                // 索引文件：App 用 ai_effect_index_kw.json 这个名字读取
                name == AI_LIB_INDEX_ZIP_NAME -> {
                    indexOk = true
                    File(libDir, AiEffectIndex.INDEX_FILE_NAME)
                }
                name.startsWith(AI_LIB_PREFIX) ->
                    File(libDir, name.removePrefix(AI_LIB_PREFIX))
                else -> null
            } ?: return@forEachZipEntry
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out -> stream.copyTo(out) }
            count++
            updateProgress("正在解压：$name（已完成 $count 个）")
        }
        return if (count == 0) {
            "解压失败：压缩包内容为空"
        } else {
            "AI广播剧音效库下载完成，共 $count 个文件\n" +
                "保存到：${libDir.absolutePath}\n" +
                if (indexOk) "索引文件已更新" else "警告：压缩包内未找到索引文件"
        }
    }

    /** 遍历 zip 条目（强制 UTF-8 文件名，中文不乱码），取消时中断 */
    private inline fun forEachZipEntry(
        zipFile: File,
        onEntry: (ZipEntry, InputStream) -> Unit
    ) {
        ZipInputStream(
            BufferedInputStream(zipFile.inputStream()),
            Charset.forName("UTF-8")
        ).use { zis ->
            while (true) {
                if (isDownloadCancelled) return
                val entry = zis.nextEntry ?: break
                onEntry(entry, zis)
                zis.closeEntry()
            }
        }
    }

    /** 保存单个文件到背景音乐文件夹（File 路径或 SAF 目录均支持） */
    private fun saveToBgmDir(bgmPath: String, fileName: String, bytes: ByteArray): Boolean {
        val mimeType = when {
            fileName.endsWith(".wav", true) -> "audio/wav"
            fileName.endsWith(".ogg", true) -> "audio/ogg"
            fileName.endsWith(".flac", true) -> "audio/flac"
            fileName.endsWith(".m4a", true) -> "audio/mp4"
            fileName.endsWith(".aac", true) -> "audio/aac"
            fileName.endsWith(".json", true) -> "application/json"
            fileName.endsWith(".txt", true) -> "text/plain"
            else -> "audio/mpeg"
        }
        return if (bgmPath.startsWith("content://")) {
            val docFile = DocumentFile.fromTreeUri(appCtx, Uri.parse(bgmPath)) ?: return false
            docFile.findFile(fileName)?.delete()
            val newFile = docFile.createFile(mimeType, fileName) ?: return false
            appCtx.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                out.write(bytes)
                return true
            }
            false
        } else {
            val dir = File(bgmPath)
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).writeBytes(bytes)
            true
        }
    }

    private fun describePath(path: String): String =
        if (path.startsWith("content://")) "SAF目录" else path

    companion object {
        /** 下载确认提示：任何一项下载都会覆盖原有文件 */
        private const val OVERWRITE_WARNING =
            "下载会覆盖原先的音效/音乐文件，如果你修改过音效，请在此之前做好备份，否则修改会丢失。" +
                "确定要继续下载吗？"

        /**
         * 大文件下载专用客户端：剔除 Cronet 拦截器，走原生 OkHttp（HTTP/2 over TCP）。
         * 原因：Cronet 走 QUIC/HTTP3 时，魔搭 CDN 会报 ERR_QUIC_PROTOCOL_ERROR
         * （InternalErrorCode=-356，Retryable=false，不会回退重试）；
         * TCP 通道完全正常。同时放开 60 秒 callTimeout——最大包 340MB，总时限会误杀。
         */
        private val downloadClient: OkHttpClient by lazy {
            okHttpClient.newBuilder().apply {
                val cronet = Cronet.interceptor
                if (cronet != null) {
                    interceptors().removeAll { it === cronet }
                }
            }
                .callTimeout(0, TimeUnit.SECONDS)          // 不限总时长，靠 readTimeout 兜底
                .readTimeout(120, TimeUnit.SECONDS)        // 允许 CDN 慢速传输
                .retryOnConnectionFailure(true)
                .build()
        }

        /** 魔搭仓库直链前缀（文件名需 percent-encode 后拼接） */
        private const val MODELSCOPE_BASE =
            "https://www.modelscope.cn/models/mingwuyan/bdyinxiao-ttsrv-backup/resolve/master/"

        /** AI广播剧音效库 zip 内的目录前缀与索引文件名 */
        private const val AI_LIB_PREFIX = "广播剧音效库/"
        private const val AI_LIB_INDEX_ZIP_NAME = "广播剧音效库_目录索引_带关键词.json"

        private const val TARGET_BGM = 0
        private const val TARGET_ALL_SFX = 1
        private const val TARGET_AI_LIB = 2
        private const val TARGET_ALL_SFX_OLD = 3
    }
}
