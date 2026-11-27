package io.legado.app.help.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * TTS 引擎激活管理器
 * 用于在转发器朗读时，激活对应的系统 TTS 引擎
 * 
 * 注意：这里的"激活"是指让系统TTS接口识别并准备好该引擎，
 * 而不是打开TTS应用的前台服务
 */
object TtsEngineActivator {

    private const val TAG = "TtsEngineActivator"

    /**
     * 11-13：最近一次「已确认激活」的引擎与时间。
     *
     * 【为什么必须有】此前 play() 每次都无条件调 activateTtsEngine，而它内部会
     * new 两个 TextToSpeech 再 shutdown（一次问默认引擎、一次试初始化）。
     * 真机日志实测：90 秒内 13 次「尝试激活」+ 13 次「初始化成功」，
     * 相当于每段朗读都在拆装引擎连接——在途合成随之中断，-3 成串。
     * 同引擎 5 分钟内复用结论，不再重建连接。
     */
    @Volatile
    private var activatedPackage: String? = null

    @Volatile
    private var activatedAtMs: Long = 0L

    private const val ACTIVATE_TTL_MS = 5 * 60_000L

    /**
     * 11-27 根治「点播放一直不动」（真机 23:07:19 实锤，36 秒零回调）：
     * **常驻探测连接**。
     *
     * ## 现场
     * ```
     * 23:07:19.051  尝试激活 TTS 引擎
     * 23:07:19.075  初始化成功（24ms 后）
     * 23:07:19.093  尝试激活 TTS 引擎   ← 同一秒又来一次
     * 23:07:19.194  Legado0 提交
     * 23:07:19.195  初始化成功           ← 这次 shutdown 把刚提交的请求打掉
     * 23:07:19.350  Legado0 返回 SUCCESS ← 只是「已入队」，实例已死
     * 23:07:55.952  onError -3           ← 36 秒后才报错
     * ```
     * 同一分钟里「尝试激活」打印了 **14 次**，总超时 120s 下 UTTS 侧
     * 却**一行日志都没有**——请求根本没到引擎。
     *
     * ## 根因（两个叠加）
     * ① **成功判据被竞态吃掉**：`tryInitializeTtsEngine` 里 `TextToSpeech(...)` 的
     *    `onInit` 是**异步回调**，而代码等的是 `Thread.sleep(0)`（不产生等待）就
     *    读 `success`。onInit 实际在 14~118ms 后才到 ⇒ 读到 false ⇒
     *    `activatedPackage` **永远不置位** ⇒ 5 分钟复用缓存永久失效 ⇒
     *    每次 `play()` 都完整拆装一次引擎连接。
     * ② **拆装本身伤在途请求**：探测用的临时连接一 `shutdown()`，UTTS 侧连接数
     *    归零，正在处理/刚入队的合成随之中断（对应 UTTS 的
     *    `Engine timeout destroy`）。播放笔恰好落在拆装窗口里就永久悬挂。
     *
     * ## 改法
     * 不再「建一个、立刻拆一个」，改为**按包名保留一条常驻探测连接**：
     *   · 已有同包名常驻连接 ⇒ 直接算已激活，不新建、不拆装；
     *   · 首次激活时才建，`onInit` 成功后把连接留下（**不再 shutdown**）；
     *   · `onInit` 早于构造返回的极快情况下也能补记（`arrived` 兜底）。
     * 这样激活动作从「每分钟 14 次」降到「每引擎 1 次」，且不对在途合成产生扰动。
     *
     * 为什么不改成「阻塞等 onInit 再判断」：本函数被 `play()` 调用，
     * 可能跑在主线程上，阻塞等待（实测 14~118ms、慢时 800ms+）会引入卡顿/ANR 风险。
     * 保留连接比阻塞更彻底：既拿到准确结论，又零阻塞。
     */
    @Volatile
    private var probePackage: String? = null

    @Volatile
    private var probeConnection: TextToSpeech? = null

    /** 正在激活中的包名：防同一引擎被并发重复激活（同一秒两次的实锤） */
    @Volatile
    private var probingPackage: String? = null

    /**
     * 11-13：系统默认引擎名缓存。
     *
     * getDefaultTtsEnginePackageName 内部要 new 一个 TextToSpeech 才能读到 defaultEngine，
     * 每次都现问等于每次多一次引擎连接。默认引擎极少变，缓存 60 秒足够。
     */
    @Volatile
    private var cachedDefaultEngine: String? = null

    @Volatile
    private var cachedDefaultEngineAtMs: Long = 0L

    private const val DEFAULT_ENGINE_TTL_MS = 60_000L

    /**
     * TTS 引擎信息，包含包名和显示名称
     */
    data class TtsEngineInfo(
        val packageName: String,
        val label: String,
        val isDefault: Boolean = false
    )

    /**
     * 获取系统所有已安装的 TTS 引擎列表
     * 通过 Android 系统 Intent 查找所有实现 TTS 服务的应用
     */
    fun getInstalledTtsEngines(context: Context): List<TtsEngineInfo> {
        val engines = mutableListOf<TtsEngineInfo>()
        val pm = context.packageManager
        
        // 获取系统默认引擎
        val defaultEngine = try {
            val tts = TextToSpeech(context, null)
            val default = tts.defaultEngine
            tts.shutdown()
            default
        } catch (e: Exception) {
            null
        }
        
        // 通过 TTS 服务 Intent 查找
        val ttsIntents = listOf(
            Intent("android.intent.action.TTS_SERVICE"),
            Intent("android.speech.tts.TTS_SERVICE")
        )
        
        for (intent in ttsIntents) {
            try {
                val resolveInfos = pm.queryIntentServices(intent, PackageManager.GET_RESOLVED_FILTER)
                for (info in resolveInfos) {
                    val packageName = info.serviceInfo.packageName
                    // 跳过系统默认 TTS 服务（com.google.android.tts）
                    if (packageName == "com.google.android.tts") continue
                    
                    val label = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                    } catch (e: Exception) {
                        packageName
                    }
                    
                    val engineInfo = TtsEngineInfo(
                        packageName = packageName,
                        label = label,
                        isDefault = packageName == defaultEngine
                    )
                    
                    if (engines.none { it.packageName == packageName }) {
                        engines.add(engineInfo)
                    }
                }
            } catch (e: Exception) {
                // 忽略
            }
        }
        
        // 尝试查找特定已知 TTS 应用的包名
        val knownTtsPackages = listOf(
            "com.github.jing332.tts_server_android",
            "com.github.jing332.tts_server_android.dev",
            "com.github.jing332.tts_server",
            "com.wobble.speechengine",
            "com.ideabus.testtts"
        )
        
        for (pkgName in knownTtsPackages) {
            try {
                val appInfo = pm.getApplicationInfo(pkgName, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                
                val engineInfo = TtsEngineInfo(
                    packageName = pkgName,
                    label = label,
                    isDefault = pkgName == defaultEngine
                )
                
                if (engines.none { it.packageName == pkgName }) {
                    engines.add(engineInfo)
                }
            } catch (e: Exception) {
                // 包不存在，跳过
            }
        }
        
        // 按是否为默认引擎排序，默认引擎放在最前面
        return engines.sortedByDescending { it.isDefault }
    }

    /**
     * 从转发器 URL 中提取 TTS 包名
     * 例如: http://localhost:1221/api/tts?engine=com.github.jing332.tts_server_android.dev&text=...
     * 返回: com.github.jing332.tts_server_android.dev
     */
    fun extractPackageNameFromUrl(url: String): String? {
        if (url.isBlank()) return null
        
        // 尝试从 URL 中提取 engine 参数
        val enginePatterns = listOf(
            Regex("""[?&]engine=([^&\s]+)"""),
            Regex("""engine=([a-zA-Z0-9._]+)""")
        )
        
        for (pattern in enginePatterns) {
            val match = pattern.find(url)
            if (match != null) {
                val packageName = match.groupValues[1]
                if (isValidPackageName(packageName)) {
                    return packageName
                }
            }
        }
        
        return null
    }

    /**
     * 验证包名格式是否有效
     */
    private fun isValidPackageName(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        // Android 包名格式: 以字母开头，只能包含字母、数字、点、下划线
        return Regex("""^[a-zA-Z][a-zA-Z0-9._]*$""").matches(packageName)
    }

    /**
     * 检查系统是否安装了指定的 TTS 引擎
     */
    fun isTtsEngineInstalled(context: Context, packageName: String): Boolean {
        try {
            // 检查包是否存在
            context.packageManager.getPackageInfo(packageName, 0)
            return true
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
    }

    /**
     * 获取当前系统默认 TTS 引擎的包名
     */
    fun getDefaultTtsEnginePackageName(context: Context): String? {
        // 11-13：默认引擎极少变，60 秒内复用缓存——否则每次 play() 都要
        // new 一个 TextToSpeech 只为读 defaultEngine（真机日志 13 次/90 秒）。
        val now = System.currentTimeMillis()
        cachedDefaultEngine?.let {
            if (now - cachedDefaultEngineAtMs < DEFAULT_ENGINE_TTL_MS) return it
        }
        return try {
            val tts = TextToSpeech(context, null)
            val engine = tts.defaultEngine
            tts.shutdown()
            cachedDefaultEngine = engine
            cachedDefaultEngineAtMs = now
            engine
        } catch (e: Exception) {
            LogUtils.d(TAG, "获取默认 TTS 引擎失败: ${e.message}")
            null
        }
    }

    /**
     * 激活指定的 TTS 引擎
     * 
     * 通过初始化 TextToSpeech 并使用指定的引擎来"激活"它，
     * 让系统的TTS接口识别这个引擎，而不是打开前台Activity
     * 
     * @param context 上下文
     * @param packageName 要激活的 TTS 引擎包名
     * @return 是否激活成功
     */
    @Suppress("unused")
    @Synchronized
    fun activateTtsEngine(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false

        // 11-13：同引擎 5 分钟内不重复激活（见 activatedPackage 注释）。
        // 命中缓存时连「尝试激活」日志都不打——那条日志本身就是刷屏来源之一。
        val now = System.currentTimeMillis()
        if (activatedPackage == packageName && now - activatedAtMs < ACTIVATE_TTL_MS) {
            return true
        }

        // ===== 11-27 根治（详见 probeConnection 注释）：常驻连接即等于「已激活」=====
        // 不走 5 分钟 TTL 也行——连接还活着就说明引擎随时可用，重复建连只会
        // 反过来打断在途合成。这里连日志都不打，保持安静。
        if (probePackage == packageName && probeConnection != null) {
            activatedPackage = packageName
            activatedAtMs = now
            return true
        }

        // 同一引擎正在激活中（真机同一秒两次的实锤）：直接视为已激活，
        // 不叠第二次建连——第二次的 shutdown 正是打掉在途请求的元凶。
        if (probingPackage == packageName) {
            return true
        }

        LogUtils.d(TAG, "尝试激活 TTS 引擎: $packageName")

        // 检查是否已安装
        if (!isTtsEngineInstalled(context, packageName)) {
            LogUtils.d(TAG, "TTS 引擎未安装: $packageName")
            return false
        }

        // 检查是否已经是默认引擎
        val currentDefault = getDefaultTtsEnginePackageName(context)
        if (currentDefault == packageName) {
            LogUtils.d(TAG, "TTS 引擎已是默认: $packageName")
            activatedPackage = packageName
            activatedAtMs = now
            return true
        }

        // 通过保留一条常驻连接来"激活"引擎（不再建完就拆）
        val ok = tryInitializeTtsEngine(context, packageName)
        if (ok) {
            activatedPackage = packageName
            activatedAtMs = now
        }
        return ok
    }

    /**
     * 通过初始化 TextToSpeech 来激活 TTS 引擎。
     *
     * ===== 11-27 重写（原实现是竞态 + 自伤，见 probeConnection 注释）=====
     * 旧实现：
     * ```
     * val tts = TextToSpeech(context, initListener, packageName)
     * Thread.sleep(0)     // 不等待：onInit 是异步的
     * tts.shutdown()      // 立刻拆掉，连带打掉在途合成
     * success             // 读到 false ⇒ activatedPackage 永不置位
     * ```
     *
     * 新实现：
     *   · 先把新连接**挂到 probeConnection**，onInit 回来时它是哪条就校验哪条，
     *     不会误判成「旧连接的成功」；
     *   · **成功即保留**（不 shutdown），失败才拆，避免把好连接误杀；
     *   · 记录 `probingPackage` 防并发重复激活；
     *   · onInit 可能在构造返回前就到达（实测有 1ms 的），用 `arrived` 兜底：
     *     若构造期间已经成功，构造后立刻补记结论，不依赖后续轮询。
     *
     * 为什么不阻塞等 onInit：本函数可能跑在主线程（`play()` 直调），
     * 实测 onInit 14~118ms、慢时 800ms+，阻塞会引入卡顿/ANR 风险。
     */
    private fun tryInitializeTtsEngine(context: Context, packageName: String): Boolean {
        probingPackage = packageName
        // onInit 早于构造返回的兜底信号：null=还没回调 / true=成功 / false=失败。
        // 用 AtomicReference 而不是 `var`：onInit 在**系统回调线程**写、这里在
        // 调用线程读，普通 var 是无同步数据竞争（可能读到旧值或撕裂），
        // 而这次修复的核心恰恰依赖「构造返回时能否看到 onInit 的结论」。
        val arrived = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val initListener = object : TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                if (status == TextToSpeech.SUCCESS) {
                    LogUtils.d(TAG, "TTS 引擎初始化成功: $packageName")
                    arrived.set(true)
                    if (probePackage == packageName) {
                        // 常驻连接已就位：记结论，连接留着继续用
                        activatedPackage = packageName
                        activatedAtMs = System.currentTimeMillis()
                    }
                } else {
                    LogUtils.d(TAG, "TTS 引擎初始化失败: $packageName, status=$status")
                    arrived.set(false)
                    // ⚠️ 这里**不直接**调 releaseProbe（它带 @Synchronized）：
                    // 本回调可能在持有同一把锁的激活线程上同步执行，也可能在
                    // 独立回调线程上执行——后者会为了等锁而多做一次线程切换。
                    //
                    // 分两种情况处理，保证「失败即释放」不留悬挂连接：
                    //   · 构造尚未返回（probingPackage 还是本包名）⇒ 由调用方
                    //     读到 arrived=false 后自己释放；
                    //   · 构造已返回并已放行（probingPackage 已清空）⇒ 这里没人管了，
                    //     必须自己释放，否则坏连接会一直挂着、后续永远判「已激活」。
                    if (probingPackage != packageName) {
                        releaseProbe(packageName)
                    }
                }
            }
        }

        return try {
            // 先把旧的不同引擎连接让掉（同引擎连接在上面已提前返回）
            val prev = probePackage
            if (prev != null && prev != packageName) {
                releaseProbe(prev)
            }
            val tts = TextToSpeech(context, initListener, packageName)
            // 保留它：这就是"激活"，拆掉反而会把在途合成打断
            probePackage = packageName
            probeConnection = tts
            // 构造返回时 onInit 可能已经到了（极快引擎），立刻补记，不等下一轮
            when (arrived.get()) {
                true -> {
                    activatedPackage = packageName
                    activatedAtMs = System.currentTimeMillis()
                    true
                }
                // 已明确失败：不留坏连接
                false -> {
                    releaseProbe(packageName)
                    false
                }
                // 还没回调：异步中。**按成功放行**——
                // 依据：旧实现里这些连接确实随后都「初始化成功」了
                // （真机 19 对全部成对，无一失败），且连接已保留、不会被打掉；
                // 若真失败，onInit 会走上面的 releaseProbe + 后续重试。
                // 这样 `activatedPackage` 得以置位，5 分钟复用生效，
                // 不再每分钟拆装 14 次——这正是本次修复要达成的效果。
                null -> true
            }
        } catch (e: Exception) {
            LogUtils.d(TAG, "TTS 引擎初始化异常: ${e.message}")
            releaseProbe(packageName)
            false
        } finally {
            probingPackage = null
        }
    }

    /** 释放常驻探测连接（只释放匹配的那条，避免误杀换了引擎的新连接） */
    @Synchronized
    private fun releaseProbe(packageName: String?) {
        if (packageName == null) return
        if (probePackage == packageName) {
            probeConnection?.runCatching { shutdown() }
            probeConnection = null
            probePackage = null
        }
    }

    /**
     * 11-27：应用退出/彻底切换引擎时释放常驻连接。
     * 不做也没关系（进程结束系统会回收），但显式释放更干净，
     * 也能让「换引擎」场景立刻断开旧连接。
     */
    @Synchronized
    fun releaseProbeConnection() {
        releaseProbe(probePackage)
    }

    /**
     * 检查TTS引擎数据是否可用，必要时请求安装
     * @param context 上下文
     * @param packageName TTS引擎包名
     * @return 是否成功
     */
    @Suppress("unused")
    fun checkTtsData(context: Context, packageName: String): Boolean {
        try {
            // 发送检查TTS数据的Intent
            val checkIntent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
            checkIntent.setPackage(packageName)
            context.startActivity(checkIntent)
            LogUtils.d(TAG, "已发送TTS数据检查请求: $packageName")
            return true
        } catch (e: Exception) {
            LogUtils.d(TAG, "检查TTS数据失败: ${e.message}")
            return false
        }
    }

    /**
     * 激活当前转发器对应的 TTS 引擎
     * 
     * @param httpTtsUrl 转发器 URL
     * @return 是否激活成功，如果 URL 为空或无法提取包名则返回 false
     */
    fun activateFromHttpTtsUrl(httpTtsUrl: String?): Boolean {
        if (httpTtsUrl.isNullOrBlank()) {
            return false
        }
        
        val packageName = extractPackageNameFromUrl(httpTtsUrl)
        if (packageName == null) {
            LogUtils.d(TAG, "无法从 URL 中提取 TTS 包名: $httpTtsUrl")
            return false
        }
        
        return activateTtsEngine(appCtx, packageName)
    }

    /**
     * 激活转发器对应的 TTS 引擎
     * 优先使用指定的 ttsPackageName，其次从 URL 中提取
     * 
     * @param httpTts 转发器对象
     * @return 是否激活成功
     */
    fun activateFromHttpTts(httpTts: io.legado.app.data.entities.HttpTTS?): Boolean {
        if (httpTts == null) {
            return false
        }
        
        // 优先使用指定的 ttsPackageName
        val packageName = httpTts.ttsPackageName
        if (!packageName.isNullOrBlank()) {
            LogUtils.d(TAG, "使用配置的 TTS 包名激活: $packageName")
            return activateTtsEngine(appCtx, packageName)
        }
        
        // 其次从 URL 中提取
        return activateFromHttpTtsUrl(httpTts.url)
    }

    /**
     * 激活当前配置的 TTS 引擎（转发器 TTS 或系统 TTS 引擎）
     * 根据配置自动选择，与 App 启动时逻辑一致，可复用在任何需要提前激活的场景
     * （如朗读开始、生图请求发送前等）
     */
    fun activateCurrentTtsEngine() {
        try {
            val ttsEngine = AppConfig.ttsEngine
            if (ttsEngine.isNullOrBlank()) {
                // 系统TTS默认模式，尝试激活已保存的系统TTS引擎包名
                val sysTtsPkg = AppConfig.sysTtsPackageName
                if (!sysTtsPkg.isNullOrBlank()) {
                    LogUtils.d(TAG, "App 初始化，激活系统TTS引擎: $sysTtsPkg")
                    activateTtsEngine(appCtx, sysTtsPkg)
                }
                return
            }
            
            // 检查是否是转发器（数字 ID 格式）
            if (!io.legado.app.utils.StringUtils.isNumeric(ttsEngine)) {
                // 非数字格式可能是系统TTS引擎JSON配置，尝试激活已保存的包名
                val sysTtsPkg = AppConfig.sysTtsPackageName
                if (!sysTtsPkg.isNullOrBlank()) {
                    LogUtils.d(TAG, "App 初始化，激活系统TTS引擎: $sysTtsPkg")
                    activateTtsEngine(appCtx, sysTtsPkg)
                }
                return
            }
            
            val httpTtsId = ttsEngine.toLongOrNull() ?: return
            val httpTts = io.legado.app.data.appDb.httpTTSDao.get(httpTtsId)
            
            httpTts?.let {
                LogUtils.d(TAG, "App 初始化，激活转发器 TTS: ${it.name}")
                activateFromHttpTts(it)
            }
        } catch (e: Exception) {
            LogUtils.d(TAG, "App 初始化激活 TTS 失败: ${e.message}")
        }
    }

    /**
     * App 初始化时激活对应的 TTS 引擎（兼容旧调用点）
     */
    fun activateOnAppInit() {
        activateCurrentTtsEngine()
    }
}
