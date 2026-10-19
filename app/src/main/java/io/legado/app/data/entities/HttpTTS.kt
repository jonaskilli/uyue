package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.jayway.jsonpath.DocumentContext
import io.legado.app.utils.GSON
import io.legado.app.utils.jsonPath
import io.legado.app.utils.readLong
import io.legado.app.utils.readString

/**
 * 在线朗读引擎
 */
@Entity(tableName = "httpTTS")
data class HttpTTS(
    @PrimaryKey
    val id: Long = System.currentTimeMillis(),
    var name: String = "",
    var url: String = "",
    var contentType: String? = null,
    @ColumnInfo(defaultValue = "0")
    override var concurrentRate: String? = "0",
    override var loginUrl: String? = null,
    override var loginUi: String? = null,
    override var header: String? = null,
    override var jsLib: String? = null,
    @ColumnInfo(defaultValue = "0")
    override var enabledCookieJar: Boolean? = false,
    var loginCheckJs: String? = null,
    @ColumnInfo(defaultValue = "0")
    var lastUpdateTime: Long = System.currentTimeMillis(),
    /**
     * 转发器使用的TTS包名，用于在朗读前激活对应的系统TTS
     * 例如：com.github.jing332.tts_server_android.dev
     * 如果为空，则从url中尝试提取engine参数
     */
    var ttsPackageName: String? = null
) : BaseSource {

    override fun getTag(): String {
        return name
    }

    override fun getKey(): String {
        return "httpTts:$id"
    }

    fun equal(source: HttpTTS): Boolean {
        return name == source.name &&
                url == source.url &&
                contentType == source.contentType &&
                concurrentRate == source.concurrentRate &&
                loginUrl == source.loginUrl &&
                loginUi == source.loginUi &&
                header == source.header &&
                jsLib == source.jsLib &&
                enabledCookieJar == source.enabledCookieJar &&
                loginCheckJs == source.loginCheckJs &&
                ttsPackageName == source.ttsPackageName
    }

    @Suppress("MemberVisibilityCanBePrivate")
    companion object {

        /**
         * 内置 TTS 插件引擎的固定 id（assets/defaultData/httpTTS.json 里那条正数 id）。
         *
         * **这个 id 绝对不能改**：`Book.config.ttsEngine` 与 `AppConfig.ttsEngine`
         * 存的就是它的字符串形式（见 ReadAloud.getReadAloudClass 按 id 查引擎），
         * 改了会让所有选中该引擎的书和全局设置静默退回系统 TTS。
         *
         * 它又不在 `deleteDefault()`（id < 0）的覆盖范围内，所以内置引擎更新时
         * 必须按这个常量显式删除后重新插入，见 DefaultData.importDefaultHttpTTS。
         */
        const val BUILTIN_TTS_PLUGIN_ID: Long = 1780805418993L

        fun fromJsonDoc(doc: DocumentContext): Result<HttpTTS> {
            return kotlin.runCatching {
                val loginUi = doc.read<Any>("$.loginUi")
                HttpTTS(
                    id = doc.readLong("$.id") ?: System.currentTimeMillis(),
                    name = doc.readString("$.name")!!,
                    url = doc.readString("$.url")!!,
                    contentType = doc.readString("$.contentType"),
                    concurrentRate = doc.readString("$.concurrentRate"),
                    loginUrl = doc.readString("$.loginUrl"),
                    loginUi = if (loginUi is List<*>) GSON.toJson(loginUi) else loginUi?.toString(),
                    header = doc.readString("$.header"),
                    loginCheckJs = doc.readString("$.loginCheckJs"),
                    lastUpdateTime = doc.readLong("$.lastUpdateTime") ?: System.currentTimeMillis(),
                    jsLib = doc.readString("$.jsLib"),
                    ttsPackageName = doc.readString("$.ttsPackageName")
                )
            }
        }

        fun fromJson(json: String): Result<HttpTTS> {
            return fromJsonDoc(jsonPath.parse(json))
        }

        fun fromJsonArray(jsonArray: String): Result<ArrayList<HttpTTS>> {
            return kotlin.runCatching {
                val sources = arrayListOf<HttpTTS>()
                val doc = jsonPath.parse(jsonArray).read<List<*>>("$")
                doc.forEach {
                    val jsonItem = jsonPath.parse(it)
                    fromJsonDoc(jsonItem).getOrThrow().let { source ->
                        sources.add(source)
                    }
                }
                return@runCatching sources
            }
        }

    }

}