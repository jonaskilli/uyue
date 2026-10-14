package io.legado.app.ui.association

import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.os.postDelayed
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.TtsScript
import io.legado.app.utils.GSON
import io.legado.app.utils.readText
import io.legado.app.databinding.ActivityTranslucenceBinding
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.audiobook.JReadVoiceEngine
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileUtils
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.canRead
import io.legado.app.utils.checkWrite
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.readUri
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream

class FileAssociationActivity :
    VMBaseActivity<ActivityTranslucenceBinding, FileAssociationViewModel>() {

    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        intent.data?.let { uri ->
            it.uri?.let { treeUri ->
                AppConfig.defaultBookTreeUri = treeUri.toString()
                importBook(treeUri, uri)
            } ?: let {
                val storageHelp = String(assets.open("storageHelp.md").readBytes())
                toastOnUi(storageHelp)
                importBook(null, uri)
            }
        }
    }

    override val binding by viewBinding(ActivityTranslucenceBinding::inflate)

    override val viewModel by viewModels<FileAssociationViewModel>()

    private val handler by lazy {
        buildMainHandler()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.rotateLoading.visible()
        viewModel.importBookLiveData.observe(this) { uri ->
            importBook(uri)
        }
        viewModel.onLineImportLive.observe(this) {
            startActivity<OnLineImportActivity> {
                data = it
            }
            finish()
        }
        viewModel.successLive.observe(this) {
            when (it.first) {
                "bookSource" -> showDialogFragment(ImportBookSourceDialog(it.second, true))
                "rssSource" -> showDialogFragment(ImportRssSourceDialog(it.second, true))
                "replaceRule" -> showDialogFragment(ImportReplaceRuleDialog(it.second, true))
                "httpTts" -> showDialogFragment(ImportHttpTtsDialog(it.second, true))
                "theme" -> showDialogFragment(ImportThemeDialog(it.second, true))
                "txtRule" -> showDialogFragment(ImportTxtTocRuleDialog(it.second, true))
                "dictRule" -> showDialogFragment(ImportDictRuleDialog(it.second, true))
                "ttsScript" -> importTtsScript(it.second)
                // TTS 插件 / 配置包：直接导入，不需要弹选择框（导入函数自带去重合并）
                "voicePlugin" -> importVoicePlugin(it.second)
                "voiceConfig" -> importVoiceConfig(it.second)
            }
        }
        viewModel.errorLive.observe(this) {
            binding.rotateLoading.gone()
            toastOnUi(it)
            handler.postDelayed(2000) {
                finish()
            }
        }
        viewModel.openBookLiveData.observe(this) {
            binding.rotateLoading.gone()
            startActivityForBook(it)
            finish()
        }
        viewModel.notSupportedLiveData.observe(this) { data ->
            binding.rotateLoading.gone()
            alert(
                title = appCtx.getString(R.string.draw),
                message = appCtx.getString(R.string.file_not_supported, data.second)
            ) {
                yesButton {
                    importBook(data.first)
                }
                noButton {
                    finish()
                }
                onCancelled {
                    finish()
                }
            }
        }
        intent.data?.let { data ->
            if (data.isContentScheme() && data.canRead()) {
                viewModel.dispatchIntent(data)
            } else {
                PermissionsCompat.Builder()
                    .addPermissions(*Permissions.Group.STORAGE)
                    .rationale(R.string.tip_perm_request_storage)
                    .onGranted {
                        viewModel.dispatchIntent(data)
                    }.onDenied {
                        toastOnUi("请求存储权限失败。")
                        handler.postDelayed(2000) {
                            finish()
                        }
                    }.request()
            }
        } ?: finish()
    }

    private fun importBook(uri: Uri) {
        if (uri.isContentScheme()) {
            val treeUriStr = AppConfig.defaultBookTreeUri
            if (treeUriStr.isNullOrEmpty()) {
                localBookTreeSelect.launch {
                    title = getString(R.string.select_book_folder)
                    mode = HandleFileContract.DIR_SYS
                }
            } else {
                importBook(Uri.parse(treeUriStr), uri)
            }
        } else {
            importBook(null, uri)
        }
    }

    private fun importBook(treeUri: Uri?, uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                withContext(IO) {
                    if (treeUri == null) {
                        viewModel.importBook(uri)
                    } else if (treeUri.isContentScheme()) {
                        val treeDoc =
                            DocumentFile.fromTreeUri(this@FileAssociationActivity, treeUri)
                        if (!treeDoc!!.checkWrite()) {
                            throw InvalidBooksDirException(
                                "请重新设置书籍保存位置\nPermission Denial"
                            )
                        }
                        readUri(uri) { fileDoc, inputStream ->
                            val name = fileDoc.name
                            var doc = treeDoc.findFile(name)
                            if (doc == null || fileDoc.lastModified > doc.lastModified()) {
                                if (doc == null) {
                                    doc = treeDoc.createFile(FileUtils.getMimeType(name), name)
                                        ?: throw InvalidBooksDirException(
                                            "请重新设置书籍保存位置\nPermission Denial"
                                        )
                                }
                                contentResolver.openOutputStream(doc.uri)!!.use { oStream ->
                                    inputStream.copyTo(oStream)
                                    oStream.flush()
                                }
                            }
                            viewModel.importBook(doc.uri)
                        }
                    } else {
                        val treeFile = File(treeUri.path ?: treeUri.toString())
                        if (!treeFile.checkWrite()) {
                            throw InvalidBooksDirException(
                                "请重新设置书籍保存位置\nPermission Denial"
                            )
                        }
                        readUri(uri) { fileDoc, inputStream ->
                            val name = fileDoc.name
                            val file = treeFile.getFile(name)
                            if (!file.exists() || fileDoc.lastModified > file.lastModified()) {
                                FileOutputStream(file).use { oStream ->
                                    inputStream.copyTo(oStream)
                                    oStream.flush()
                                }
                            }
                            viewModel.importBook(Uri.fromFile(file))
                        }
                    }
                }
            }.onFailure {
                when (it) {
                    is InvalidBooksDirException -> localBookTreeSelect.launch {
                        title = getString(R.string.select_book_folder)
                        mode = HandleFileContract.DIR_SYS
                    }

                    else -> {
                        val msg = "导入书籍失败\n${it.localizedMessage}"
                        AppLog.put(msg, it)
                        toastOnUi(msg)
                        handler.postDelayed(2000) {
                            finish()
                        }
                    }
                }
            }
        }
    }

    /**
     * 从外部（文件管理器/其他应用分享）导入 TTS 插件包。
     * 走 [JReadVoiceEngine.importPluginsFromJson]，与界面内导入完全同一条逻辑：
     * 按 id/pluginId 命中已存在的就覆盖，否则新增，不会产生重复插件。
     */
    private fun importVoicePlugin(uriStr: String) {
        lifecycleScope.launch {
            runCatching {
                withContext(IO) {
                    val text = Uri.parse(uriStr).readText(this@FileAssociationActivity)
                    JReadVoiceEngine.importPluginsFromJson(appCtx, text)
                }
            }.onSuccess { count ->
                binding.rotateLoading.gone()
                toastOnUi(if (count > 0) "TTS插件导入成功，共 $count 个" else "没有可导入的插件")
                handler.postDelayed(2000) { finish() }
            }.onFailure {
                binding.rotateLoading.gone()
                val msg = "TTS插件导入失败\n${it.localizedMessage}"
                AppLog.put(msg, it)
                toastOnUi(msg)
                handler.postDelayed(2000) { finish() }
            }
        }
    }

    /** 从外部导入 TTS 配置包（发音人列表），逻辑与界面内导入一致 */
    private fun importVoiceConfig(uriStr: String) {
        lifecycleScope.launch {
            runCatching {
                withContext(IO) {
                    val text = Uri.parse(uriStr).readText(this@FileAssociationActivity)
                    JReadVoiceEngine.importConfigsFromJson(appCtx, text)
                }
            }.onSuccess { count ->
                binding.rotateLoading.gone()
                toastOnUi(if (count > 0) "TTS配置导入成功，共 $count 项" else "没有可导入的配置")
                handler.postDelayed(2000) { finish() }
            }.onFailure {
                binding.rotateLoading.gone()
                val msg = "TTS配置导入失败\n${it.localizedMessage}"
                AppLog.put(msg, it)
                toastOnUi(msg)
                handler.postDelayed(2000) { finish() }
            }
        }
    }

    private fun importTtsScript(uriStr: String) {
        lifecycleScope.launch {
            runCatching {
                withContext(IO) {
                    val text = Uri.parse(uriStr).readText(this@FileAssociationActivity)
                    GSON.fromJson(text, Array<TtsScript>::class.java)?.let { scripts ->
                        appDb.ttsScriptDao.insert(*scripts)
                        scripts.size
                    } ?: 0
                }
            }.onSuccess { count ->
                binding.rotateLoading.gone()
                toastOnUi("朗读脚本导入成功，共 $count 条")
                handler.postDelayed(2000) {
                    finish()
                }
            }.onFailure {
                binding.rotateLoading.gone()
                val msg = "朗读脚本导入失败\n${it.localizedMessage}"
                AppLog.put(msg, it)
                toastOnUi(msg)
                handler.postDelayed(2000) {
                    finish()
                }
            }
        }
    }

}
