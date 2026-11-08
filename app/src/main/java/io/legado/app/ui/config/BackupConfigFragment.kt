package io.legado.app.ui.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import java.io.File
import android.text.InputType
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.ImportOldData
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.TtsBackup
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.applyTint
import io.legado.app.utils.checkWrite
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toEditable
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class BackupConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    MenuProvider {

    private val viewModel by activityViewModels<ConfigViewModel>()
    private val waitDialog by lazy { WaitDialog(requireContext()) }
    private var backupJob: Job? = null
    private var restoreJob: Job? = null

    private val selectBackupPath = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
            } else {
                AppConfig.backupPath = uri.path
            }
        }
    }
    private val backupDir = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
                backup(uri.toString())
            } else {
                uri.path?.let { path ->
                    AppConfig.backupPath = path
                    backup(path)
                }
            }
        }
    }
    private val restoreDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            waitDialog.setText("恢复中…")
            waitDialog.show()
            val task = Coroutine.async {
                Restore.restore(appCtx, uri)
            }.onFinally {
                waitDialog.dismiss()
            }
            waitDialog.setOnCancelListener {
                task.cancel()
            }
        }
    }
    private val restoreOld = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ImportOldData.importUri(appCtx, uri)
        }
    }

    /** TTS 备份是独立 zip，和主备份格式不通用，单独导出/导入 */
    private val ttsMimeTypes = arrayOf("application/zip", "*/*")

    /** CreateDocument 的 launch 只能传文件名, 勾选的模块用成员变量带过去 */
    private var pendingTtsBackupKeys: List<String> = emptyList()
    private val ttsBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri ?: return@registerForActivityResult
        val keys = pendingTtsBackupKeys
        pendingTtsBackupKeys = emptyList()
        waitDialog.setText("TTS备份中…")
        waitDialog.show()
        val task = Coroutine.async {
            TtsBackup.backup(appCtx, uri, keys)
        }.onError {
            AppLog.put("TTS备份失败\n${it.localizedMessage}", it)
            appCtx.toastOnUi("TTS备份失败\n${it.localizedMessage}")
        }.onSuccess {
            appCtx.toastOnUi(R.string.backup_success)
        }.onFinally {
            waitDialog.dismiss()
        }
        waitDialog.setOnCancelListener {
            task.cancel()
        }
    }
    private val ttsRestoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        unpackTtsBackup { TtsBackup.unpackFromUri(appCtx, uri) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_backup)
        findPreference<EditTextPreference>(PreferKey.webDavPassword)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.inputType =
                    InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
                editText.setSelection(editText.text.length)
            }
        }
        findPreference<EditTextPreference>(PreferKey.webDavDir)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.text = AppConfig.webDavDir?.toEditable()
                editText.setSelection(editText.text.length)
            }
        }
        findPreference<EditTextPreference>(PreferKey.webDavDeviceName)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.text = AppConfig.webDavDeviceName?.toEditable()
                editText.setSelection(editText.text.length)
            }
        }
        findPreference<EditTextPreference>(PreferKey.webDavBackupKeepCount)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_NUMBER
                editText.setSelection(editText.text.length)
            }
        }
        upPreferenceSummary(PreferKey.webDavUrl, getPrefString(PreferKey.webDavUrl))
        upPreferenceSummary(PreferKey.webDavAccount, getPrefString(PreferKey.webDavAccount))
        upPreferenceSummary(PreferKey.webDavPassword, getPrefString(PreferKey.webDavPassword))
        upPreferenceSummary(PreferKey.webDavDir, AppConfig.webDavDir)
        upPreferenceSummary(PreferKey.webDavDeviceName, AppConfig.webDavDeviceName)
        upPreferenceSummary(PreferKey.backupPath, getPrefString(PreferKey.backupPath))
        upPreferenceSummary(
            PreferKey.webDavBackupKeepCount,
            AppConfig.webDavBackupKeepCount.toString()
        )
        findPreference<io.legado.app.lib.prefs.Preference>("web_dav_restore")
            ?.onLongClick {
                restoreFromLocal()
                true
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.backup_restore)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        listView.setEdgeEffectColor(primaryColor)
        activity?.addMenuProvider(this, viewLifecycleOwner)
        if (!LocalConfig.backupHelpVersionIsLast) {
            showHelp("webDavHelp")
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.backup_restore, menu)
        menu.applyTint(requireContext())
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_help -> {
                showHelp("webDavHelp")
                return true
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.backupPath -> upPreferenceSummary(key, getPrefString(key))
            PreferKey.webDavUrl,
            PreferKey.webDavAccount,
            PreferKey.webDavPassword,
            PreferKey.webDavDir -> listView.post {
                upPreferenceSummary(key, appCtx.getPrefString(key))
                viewModel.upWebDavConfig()
            }

            PreferKey.webDavDeviceName -> upPreferenceSummary(key, getPrefString(key))
            PreferKey.webDavBackupKeepCount -> upPreferenceSummary(key, getPrefString(key))
        }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.webDavUrl ->
                if (value.isNullOrBlank()) {
                    preference.summary = getString(R.string.web_dav_url_s)
                } else {
                    preference.summary = value
                }

            PreferKey.webDavAccount ->
                if (value.isNullOrBlank()) {
                    preference.summary = getString(R.string.web_dav_account_s)
                } else {
                    preference.summary = value
                }

            PreferKey.webDavPassword ->
                if (value.isNullOrEmpty()) {
                    preference.summary = getString(R.string.web_dav_pw_s)
                } else {
                    preference.summary = "*".repeat(value.length)
                }

            PreferKey.webDavDir -> preference.summary = when (value) {
                null -> "legado"
                else -> value
            }

            PreferKey.webDavBackupKeepCount -> {
                val count = value?.toIntOrNull() ?: AppConfig.webDavBackupKeepCount
                preference.summary = if (count <= 0) {
                    getString(R.string.webdav_backup_keep_count_unlimited)
                } else {
                    getString(R.string.webdav_backup_keep_count_s, count.toString())
                }
            }

            else -> {
                if (preference is ListPreference) {
                    val index = preference.findIndexOfValue(value)
                    // Set the summary to reflect the new value.
                    preference.summary = if (index >= 0) preference.entries[index] else null
                } else {
                    preference.summary = value
                }
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.backupPath -> selectBackupPath.launch()
            PreferKey.restoreIgnore -> backupIgnore()
            "web_dav_backup" -> backup()
            "web_dav_restore" -> restore()
            "import_old" -> restoreOld.launch()
            "tts_backup" -> ttsBackup()
            "tts_restore" -> ttsRestore()
        }
        return super.onPreferenceTreeClick(preference)
    }

    /**
     * 备份忽略设置
     */
    private fun backupIgnore() {
        val checkedItems = BooleanArray(BackupConfig.ignoreKeys.size) {
            BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[it]] ?: false
        }
        alert(R.string.restore_ignore) {
            multiChoiceItems(BackupConfig.ignoreTitle, checkedItems) { _, which, isChecked ->
                BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[which]] = isChecked
            }
            onDismiss {
                BackupConfig.saveIgnoreConfig()
            }
        }
    }


    fun backup() {
        val backupPath = AppConfig.backupPath
        if (backupPath.isNullOrEmpty()) {
            backupDir.launch()
        } else {
            if (backupPath.isContentScheme()) {
                lifecycleScope.launch {
                    val canWrite = withContext(IO) {
                        FileDoc.fromDir(backupPath).checkWrite()
                    }
                    if (canWrite) {
                        backup(backupPath)
                    } else {
                        backupDir.launch()
                    }
                }
            } else {
                backupUsePermission(backupPath)
            }
        }
    }

    /**
     * TTS 备份入口: 先在 IO 线程统计各模块大小, 再弹多选让用户勾选。
     */
    private fun ttsBackup() {
        waitDialog.setText("统计备份内容…")
        waitDialog.show()
        val job = lifecycleScope.launch {
            try {
                val items = withContext(IO) { TtsBackup.packableItems() }
                if (!isAdded) return@launch
                showTtsBackupPicker(items)
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                AppLog.put("统计TTS备份内容失败\n${e.localizedMessage}", e)
                appCtx.toastOnUi("统计TTS备份内容失败\n${e.localizedMessage}")
            } finally {
                waitDialog.dismiss()
            }
        }
        waitDialog.setOnCancelListener {
            job.cancel()
        }
    }

    private fun showTtsBackupPicker(items: List<TtsBackup.TtsBackupItem>) {
        val titles = items.map { "${it.title}（${it.detail}）" }.toTypedArray()
        // 角色立绘在外部文件夹且体积可能很大，默认不参与备份；
        // 只有手动勾上才会写进 zip，其余模块仍保持默认全选。
        val checked = BooleanArray(items.size) { index ->
            items[index].key != TtsBackup.K_AVATARS
        }
        // 与恢复同理: 提示只能放标题, setMessage 会让 multiChoiceItems 整个不渲染
        val title = "%s（%s）".format(
            getString(R.string.tts_backup_t),
            getString(R.string.tts_backup_pick_msg)
        )
        alert(title) {
            multiChoiceItems(titles, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            okButton {
                val keys = items.filterIndexed { index, _ -> checked[index] }.map { it.key }
                if (keys.isEmpty()) {
                    appCtx.toastOnUi("没有选择任何内容")
                    return@okButton
                }
                chooseTtsBackupTarget(keys)
            }
            cancelButton()
        }
    }

    /**
     * 选好备份内容后再问导出到哪。没配 webDav 就直接存到本地。
     */
    private fun chooseTtsBackupTarget(keys: List<String>) {
        if (!AppWebDav.isOk) {
            pendingTtsBackupKeys = keys
            ttsBackupLauncher.launch(TtsBackup.defaultFileName())
            return
        }
        requireContext().selector(items = listOf("导出到本地", "上传到 WebDav")) { _, index ->
            when (index) {
                0 -> {
                    pendingTtsBackupKeys = keys
                    ttsBackupLauncher.launch(TtsBackup.defaultFileName())
                }
                1 -> uploadTtsBackup(keys)
            }
        }
    }

    /**
     * TTS 恢复入口
     */
    private fun ttsRestore() {
        if (!AppWebDav.isOk) {
            ttsRestoreLauncher.launch(ttsMimeTypes)
            return
        }
        requireContext().selector(items = listOf("从本地文件恢复", "从 WebDav 恢复")) { _, index ->
            when (index) {
                0 -> ttsRestoreLauncher.launch(ttsMimeTypes)
                1 -> listTtsBackupFromWebDav()
            }
        }
    }

    private fun uploadTtsBackup(keys: List<String>) {
        waitDialog.setText("TTS备份中…")
        waitDialog.show()
        val task = Coroutine.async {
            val fileName = TtsBackup.defaultFileName()
            val file = File(appCtx.externalFiles, fileName)
            TtsBackup.backupToFile(file, keys)
            if (!AppWebDav.upTtsBackup(fileName, file)) {
                throw NoStackTraceException("上传TTS备份失败")
            }
            FileUtils.delete(file.absolutePath)
        }.onError {
            AppLog.put("TTS备份失败\n${it.localizedMessage}", it)
            appCtx.toastOnUi("TTS备份失败\n${it.localizedMessage}")
        }.onSuccess {
            appCtx.toastOnUi(R.string.backup_success)
        }.onFinally {
            waitDialog.dismiss()
        }
        waitDialog.setOnCancelListener {
            task.cancel()
        }
    }

    private fun listTtsBackupFromWebDav() {
        waitDialog.setText(R.string.loading)
        waitDialog.show()
        val task = Coroutine.async {
            val names = AppWebDav.getTtsBackupNames()
            if (names.isEmpty()) {
                throw NoStackTraceException("WebDav 上没有 TTS 备份")
            }
            withContext(Main) {
                requireContext().selector(
                    title = requireContext().getString(R.string.select_restore_file),
                    items = names
                ) { _, index ->
                    if (index in 0 until names.size) {
                        restoreTtsFromWebDav(names[index])
                    }
                }
            }
        }.onError {
            AppLog.put("获取TTS备份列表失败\n${it.localizedMessage}", it)
            appCtx.toastOnUi("获取TTS备份列表失败\n${it.localizedMessage}")
        }.onFinally {
            waitDialog.dismiss()
        }
        waitDialog.setOnCancelListener {
            task.cancel()
        }
    }

    private fun restoreTtsFromWebDav(name: String) {
        waitDialog.setText("下载备份中…")
        waitDialog.show()
        val task = Coroutine.async {
            val file = File(appCtx.externalFiles, name)
            AppWebDav.downTtsBackup(name, file)
            val items = TtsBackup.unpackFromFile(file)
            FileUtils.delete(file.absolutePath)
            if (items.isEmpty()) {
                throw NoStackTraceException("备份里没有可恢复的内容")
            }
            withContext(Main) {
                showTtsRestorePicker(items)
            }
        }.onError {
            AppLog.put("TTS恢复失败\n${it.localizedMessage}", it)
            appCtx.toastOnUi("TTS恢复失败\n${it.localizedMessage}")
            lifecycleScope.launch(IO) { TtsBackup.finish() }
        }.onFinally(Main) {
            waitDialog.dismiss()
        }
        waitDialog.setOnCancelListener {
            task.cancel()
        }
    }

    /**
     * 解压备份后弹出多选, 先只解压不写库, 用户勾选完才真正恢复
     */
    private fun unpackTtsBackup(unpack: suspend () -> List<TtsBackup.TtsBackupItem>) {
        waitDialog.setText("读取备份中…")
        waitDialog.show()
        val task = Coroutine.async {
            val items = unpack()
            if (items.isEmpty()) {
                throw NoStackTraceException("备份里没有可恢复的内容")
            }
            withContext(Main) {
                showTtsRestorePicker(items)
            }
        }.onError {
            AppLog.put("读取TTS备份失败\n${it.localizedMessage}", it)
            appCtx.toastOnUi("读取TTS备份失败\n${it.localizedMessage}")
            lifecycleScope.launch(IO) { TtsBackup.finish() }
        }.onFinally(Main) {
            waitDialog.dismiss()
        }
        waitDialog.setOnCancelListener {
            task.cancel()
        }
    }

    private fun showTtsRestorePicker(items: List<TtsBackup.TtsBackupItem>) {
        val titles = items.map { "${it.title}（${it.detail}）" }.toTypedArray()
        val checked = BooleanArray(items.size) { true }
        // 提示只能放进标题, 不能调 setMessage: AlertDialog 的 message 与复选列表
        // 互斥, 同时设置时 AlertController 不会把 ListView 加进布局, 结果就是
        // 只剩提示文字和按钮、中间的勾选列表整个消失
        val title = "%s（%s）".format(
            getString(R.string.tts_restore_t),
            getString(R.string.tts_restore_pick_msg)
        )
        alert(title) {
            multiChoiceItems(titles, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            okButton {
                val keys = items.filterIndexed { index, _ -> checked[index] }.map { it.key }
                if (keys.isEmpty()) {
                    appCtx.toastOnUi("没有选择任何内容")
                    lifecycleScope.launch(IO) { TtsBackup.finish() }
                    return@okButton
                }
                applyTtsRestore(keys)
            }
            cancelButton {
                lifecycleScope.launch(IO) { TtsBackup.finish() }
            }
        }
    }

    private fun applyTtsRestore(keys: List<String>) {
        waitDialog.setText("TTS恢复中…")
        waitDialog.show()
        val task = Coroutine.async {
            TtsBackup.apply(keys)
        }.onError {
            AppLog.put("TTS恢复失败\n${it.localizedMessage}", it)
            appCtx.toastOnUi("TTS恢复失败\n${it.localizedMessage}")
        }.onSuccess {
            appCtx.toastOnUi(R.string.restore_success)
        }.onFinally(Main) {
            waitDialog.dismiss()
            lifecycleScope.launch(IO) { TtsBackup.finish() }
        }
        waitDialog.setOnCancelListener {
            task.cancel()
        }
    }

    private fun backup(backupPath: String) {
        waitDialog.setText("备份中…")
        waitDialog.setOnCancelListener {
            backupJob?.cancel()
        }
        waitDialog.show()
        backupJob?.cancel()
        backupJob = lifecycleScope.launch {
            try {
                Backup.backupLocked(requireContext(), backupPath)
                appCtx.toastOnUi(R.string.backup_success)
            } catch (e: Throwable) {
                ensureActive()
                AppLog.put("备份出错\n${e.localizedMessage}", e)
                appCtx.toastOnUi(
                    appCtx.getString(
                        R.string.backup_fail,
                        e.localizedMessage
                    )
                )
            } finally {
                ensureActive()
                waitDialog.dismiss()
            }
        }
    }

    private fun backupUsePermission(path: String) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                backup(path)
            }
            .request()
    }

    fun restore() {
        waitDialog.setText(R.string.loading)
        waitDialog.setOnCancelListener {
            restoreJob?.cancel()
        }
        waitDialog.show()
        Coroutine.async {
            restoreJob = coroutineContext[Job]
            showRestoreDialog(requireContext())
        }.onError {
            AppLog.put("恢复备份出错WebDavError\n${it.localizedMessage}", it)
            if (context == null) {
                return@onError
            }
            alert {
                setTitle(R.string.restore)
                setMessage("WebDavError\n${it.localizedMessage}\n将从本地备份恢复。")
                okButton {
                    restoreFromLocal()
                }
                cancelButton()
            }
        }.onFinally {
            waitDialog.dismiss()
        }
    }

    private suspend fun showRestoreDialog(context: Context) {
        val names = withContext(IO) { AppWebDav.getBackupNames() }
        if (AppWebDav.isJianGuoYun && names.size > 700) {
            context.toastOnUi("由于坚果云限制列出文件数量，部分备份可能未显示，请及时清理旧备份")
        }
        if (names.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            withContext(Main) {
                context.selector(
                    title = context.getString(R.string.select_restore_file),
                    items = names
                ) { _, index ->
                    if (index in 0 until names.size) {
                        listView.post {
                            restoreWebDav(names[index])
                        }
                    }
                }
            }
        } else {
            throw NoStackTraceException("Web dav no back up file")
        }
    }

    private fun restoreWebDav(name: String) {
        waitDialog.setText("恢复中…")
        waitDialog.show()
        val task = Coroutine.async {
            AppWebDav.restoreWebDav(name)
        }.onError {
            AppLog.put("WebDav恢复出错\n${it.localizedMessage}", it)
            appCtx.toastOnUi("WebDav恢复出错\n${it.localizedMessage}")
        }.onFinally {
            waitDialog.dismiss()
        }
        waitDialog.setOnCancelListener {
            task.cancel()
        }
    }

    private fun restoreFromLocal() {
        restoreDoc.launch {
            title = getString(R.string.select_restore_file)
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("zip")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        waitDialog.dismiss()
    }

}