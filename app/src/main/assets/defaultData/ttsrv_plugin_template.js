// TTS 引擎插件模板（新建插件时自动填充，可随意修改）
//
// 变量声明（vars）里的每一项都会出现在「设置变量」界面，插件里用 ttsrv.userVars['key'] 读取。
// 常用字段：label（显示名）、hint（输入框提示）、def（默认值）、loginUrl（登录页地址）、binding（取值方式）
//
// 可用 API（ttsrv）：
//   httpGetString(url, headers) / httpPost(url, body, headers) / httpGetStream(url, headers)
//   readTxtFile(name) / writeTxtFile(name, text) / fileExist(name) / deleteFile(name)
//   md5Encode(text) / base64Encode(text) / randomUUID()
//   console.log(...) / ttsrv.tts.data（界面上用户填的自定义数据）

let key = ttsrv.userVars['key'] || ''
let sampleRate = 24000
let isNeedDecode = true // 返回 mp3/opus 等需要解码的格式时为 true，返回 raw/pcm 时为 false

let PluginJS = {
    "name": "新插件",
    "id": "com.example.plugin",
    "author": "",
    "description": "",
    "version": 1,
    "vars": {
        key: { label: "API 密钥" },
    },

    "onLoad": function () {
        if (!key) {
            throw "请先在「设置变量」里填写 API 密钥"
        }
    },

    // 合成入口：返回音频 InputStream 或 ByteArray
    // rate/volume/pitch 由朗读界面的语速/音量/音高换算而来（基准 100 / 100 / 100）
    "getAudio": function (text, locale, voice, rate, volume, pitch) {
        throw "请在 getAudio() 里实现合成逻辑并返回音频数据"
    },
}

// 编辑器保存 / 试听时会调用下面这些 EditorJS 方法
let EditorJS = {
    "getAudioSampleRate": function (locale, voice) {
        return sampleRate
    },

    "isNeedDecode": function (locale, voice) {
        return isNeedDecode
    },

    "getLocales": function () {
        return ["zh-CN"]
    },

    "getVoices": function (locale) {
        return {}
    },

    // 加载音色数据，运行在 IO 线程
    "onLoadData": function () {
    },

    // 自绘配置界面（可选）：ctx 为 Context，linerLayout 为容器
    "onLoadUI": function (ctx, linerLayout) {
    },
}
