package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.utils.inputStream
import io.legado.app.utils.jsonPath

abstract class BaseAssociationViewModel(application: Application) : BaseViewModel(application) {

    val successLive = MutableLiveData<Pair<String, String>>()
    val errorLive = MutableLiveData<String>()

    fun importJson(uri: Uri) {
        val map = uri.inputStream(context).getOrThrow().use {
            jsonPath.parse(it).read<Map<String, *>>("$[0]")
        } ?: uri.inputStream(context).getOrThrow().use {
            jsonPath.parse(it).read("$")
        }

        when {
            // 悦读/TTS 引擎的插件包与配置包：顶层带 format 标记，是最可靠的识别方式
            // （导出格式见 JReadVoiceEngine.exportPluginJson / exportConfigsJson）
            map["format"] == "jread_voice_plugin_bundle" ->
                successLive.postValue("voicePlugin" to uri.toString())

            map["format"] == "jread_voice_config_bundle" ->
                successLive.postValue("voiceConfig" to uri.toString())

            map.containsKey("bookSourceUrl") ->
                successLive.postValue("bookSource" to uri.toString())

            map.containsKey("sourceUrl") ->
                successLive.postValue("rssSource" to uri.toString())

            map.containsKey("pattern") ->
                successLive.postValue("replaceRule" to uri.toString())

            map.containsKey("themeName") ->
                successLive.postValue("theme" to uri.toString())

            map.containsKey("showRule") ->
                successLive.postValue("dictRule" to uri.toString())

            map.containsKey("name") && map.containsKey("rule") ->
                successLive.postValue("txtRule" to uri.toString())

            map.containsKey("name") && map.containsKey("url") ->
                successLive.postValue("httpTts" to uri.toString())

            map.containsKey("name") && map.containsKey("code") ->
                successLive.postValue("ttsScript" to uri.toString())

            else -> errorLive.postValue("格式不对")
        }
    }

}