package io.legado.app.ui.soundeffect

import android.app.Application
import android.os.Handler
import android.os.Looper
import io.legado.app.base.BaseViewModel
import io.legado.app.service.AiEffectIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 音效规则管理界面的数据读写。
 *
 * 规则只存在于 JSON 文件中（朗读服务直接读文件），这里没有数据库。
 * [data] 为当前模式的内存副本，只在主线程访问与修改。
 */
class SoundEffectRuleViewModel(application: Application) : BaseViewModel(application) {

    var mode: String = SoundEffectRuleManager.MODE_ALL
        private set

    val data = mutableListOf<SoundEffectGroupWithRules>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 打开界面时调用：确保两个规则文件都存在，缺失的直接下载，
     * 然后载入当前模式的数据。
     * @param onProgress 进度文案（主线程回调）
     * @param onDone 结束回调，参数为错误信息，成功为 null（主线程回调）
     */
    fun prepare(onProgress: (String) -> Unit, onDone: (String?) -> Unit) {
        execute {
            SoundEffectRuleManager.allModes().forEach { m ->
                val name = SoundEffectRuleManager.fileNameOf(m)
                post { onProgress("检查 $name …") }
                val error = SoundEffectRuleManager.ensureFile(m) { cur, total ->
                    post {
                        onProgress(
                            if (total > 0) {
                                "下载 $name：${cur / 1024}KB / ${total / 1024}KB"
                            } else {
                                "下载 $name：${cur / 1024}KB"
                            }
                        )
                    }
                }
                if (error != null) return@execute error
            }
            post { onProgress("正在读取规则…") }
            val loaded = SoundEffectRuleManager.load(mode)
            withContext(Dispatchers.Main) {
                data.clear()
                data.addAll(loaded)
            }
            null
        }.onError {
            onDone(it.message ?: "读取规则失败")
        }.onSuccess {
            onDone(it)
        }
    }

    /**
     * 切换到另一个模式。会先由外部保存当前模式，这里只负责载入。
     */
    fun switchMode(newMode: String, onDone: (String?) -> Unit) {
        execute {
            val loaded = loadForMode(newMode)
            withContext(Dispatchers.Main) {
                mode = newMode
                data.clear()
                data.addAll(loaded)
            }
            null
        }.onError {
            onDone(it.message ?: "切换失败")
        }.onSuccess {
            onDone(it)
        }
    }

    /**
     * 按模式载入数据。
     * [SoundEffectRuleManager.MODE_AI] 走音效库索引（需先确保索引已加载），
     * 其余走规则 JSON 文件。
     */
    private suspend fun loadForMode(targetMode: String): MutableList<SoundEffectGroupWithRules> {
        if (targetMode == SoundEffectRuleManager.MODE_AI) {
            // 外部索引不存在时会从内置导出一份，故这里必须等它完成
            AiEffectIndex.ensureLoaded()
            return SoundEffectRuleManager.loadAiIndex()
        }
        return SoundEffectRuleManager.load(targetMode)
    }

    /**
     * 把内存中的规则写回当前模式的文件。
     * @param onDone 参数为错误信息，成功为 null（主线程回调）
     */
    fun save(onDone: ((String?) -> Unit)? = null) {
        // 快照一份再切到 IO 线程，避免写入过程中界面继续修改
        val snapshot = data.map { gwr ->
            SoundEffectGroupWithRules(gwr.group.copy(), gwr.list.map { it.copy() }.toMutableList())
        }
        val currentMode = mode
        execute {
            if (currentMode == SoundEffectRuleManager.MODE_AI) {
                SoundEffectRuleManager.saveAiIndex(snapshot)
            } else {
                SoundEffectRuleManager.save(currentMode, snapshot)
            }
        }.onError {
            onDone?.invoke(it.message ?: "保存失败")
        }.onSuccess {
            onDone?.invoke(it)
        }
    }

    /** 用导入的数据整体替换当前模式的数据 */
    fun replaceAll(newData: List<SoundEffectGroupWithRules>) {
        data.clear()
        data.addAll(newData)
    }

    private fun post(block: () -> Unit) {
        mainHandler.post { block() }
    }
}
