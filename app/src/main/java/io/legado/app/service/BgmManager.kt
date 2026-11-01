package io.legado.app.service

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.*
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 背景音乐管理器
 * 负责音频播放、AI智能切换及音量淡入淡出逻辑
 *
 * 【核心原则】
 * 1. loadBgmFiles 只读取文件名列表（从txt或遍历），不做任何文件查找/URI解析。
 * 2. 只有播放时才按需查找单个音频文件，且必须在 IO 线程执行。
 * 3. 彻底避免主线程同步执行文件/SAF操作导致 ANR。
 */
object BgmManager {

    // 双播放器交叉淡出
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    private var activePlayer: ExoPlayer? = null
    private var standbyPlayer: ExoPlayer? = null

    private val audioExtensions = arrayOf("mp3", "wav", "ogg", "flac", "m4a", "aac")
    // 只存储音频文件名（不含扩展名），从txt读取或回退遍历，不加载任何文件
    private val bgmNames: MutableList<String> = mutableListOf()
    // 文件名（无扩展名）到完整文件名（带扩展名，如 txt 中所写）的映射，用于直接查找
    private val bgmFullNames: MutableMap<String, String> = mutableMapOf()
    // 缓存当前音频文件的准确时长（毫秒），按需读取（仅播放时）
    private val audioDurations = ConcurrentHashMap<Int, Long>()

    // 当前实际播放的索引
    private var currentPlayingIndex = -1

    /** 当前播放索引（供朗读服务做"推荐不得与当前相同"校验） */
    fun getCurrentPlayingIndex(): Int = currentPlayingIndex

    // 协程控制音量动画
    private var fadeJob: Job? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)

    /**
     * 初始化播放器
     */
    var getNextBgmIndex: (() -> Int)? = null
    var onBgmStarted: (() -> Unit)? = null

    // AI预存的下一首BGM索引（由BaseReadAloudService更新）
    var pendingNextBgmIndex: Int = -1

    fun init(context: Context) {
        if (playerA == null) {
            mainScope.launch {
                playerA = createPlayer(context)
                playerB = createPlayer(context)
                activePlayer = playerA
                standbyPlayer = playerB
            }
        }
    }

    private fun createPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            shuffleModeEnabled = false
            volume = 0f // 初始音量设为0，等待播放时淡入
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        // 只有 activePlayer 的结束才触发自动切换
                        if (this@apply == activePlayer) {
                            // 朗读已暂停/停止时不再自动切歌，直接停——
                            // 否则朗读停了 BGM 还会一首接一首无限播下去
                            if (!BaseReadAloudService.isPlay()) {
                                pause()
                                return
                            }
                            handlePlaybackEnded()
                        }
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val currentIdx = currentPlayingIndex
                    val fileName = bgmNames.getOrNull(currentIdx) ?: "未知"
                    when (reason) {
                        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
                            io.legado.app.constant.AppLog.put("AI背景音乐: BGM自动顺序切换(异常), index=$currentIdx, name=$fileName")
                            pause()
                            if (BaseReadAloudService.isPlay()) {
                                onBgmStarted?.invoke()
                            }
                        }
                        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                            io.legado.app.constant.AppLog.put("AI背景音乐: BGM切换完成(SEEK), index=$currentIdx, name=$fileName")
                            onBgmStarted?.invoke()
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val fileName = bgmNames.getOrNull(currentPlayingIndex) ?: "未知"
                    io.legado.app.utils.LogUtils.d("BgmManager", "❌ BGM播放错误: ${error.message}, index=$currentPlayingIndex, name=$fileName")
                    io.legado.app.constant.AppLog.put("AI背景音乐: 播放错误 $fileName，自动跳过: ${error.localizedMessage}")
                    // 播放出错时自动跳过到下一首
                    // 朗读已暂停/停止时不再跳歌，避免无人管住 BGM
                    if (!BaseReadAloudService.isPlay()) return
                    mainScope.launch {
                        // 取局部 size，避免检查与取模之间 bgmNames 被清空导致 % 0
                        val size = bgmNames.size
                        if (size > 0) {
                            val nextIndex = (currentPlayingIndex + 1) % size
                            playAudioByIndex(nextIndex)
                            animateVolume(activePlayer, AppConfig.bgmVolume / 100f, duration = 500L)
                        }
                    }
                }
            })
        }
    }

    private fun handlePlaybackEnded() {
        io.legado.app.constant.AppLog.put("AI背景音乐: BGM播完，handlePlaybackEnded触发")
        mainScope.launch {
            // 使用AI预存的推荐结果。
            // 【用完即弃】：不管本轮播什么，推荐值先清掉——否则预分析一旦没跑成
            // （防抖/文本收集失败/AI失败），旧推荐会残留，每次播完都切同一首死循环
            val nextIndex = pendingNextBgmIndex
            pendingNextBgmIndex = -1
            // 推荐必须有效且不能和刚播完这首相同（相同说明是残留的旧推荐）
            val isAIRecommended = nextIndex >= 0 && nextIndex < bgmNames.size &&
                nextIndex != currentPlayingIndex
            if (isAIRecommended) {
                val fileName = bgmNames.getOrNull(nextIndex) ?: "未知"
                io.legado.app.utils.LogUtils.d("BgmManager", "🎵 BGM结束，AI推荐切换: index=$nextIndex, name=$fileName")
                io.legado.app.constant.AppLog.put("AI背景音乐: BGM结束自动切换，AI推荐: $fileName (index=$nextIndex)")
                playAudioByIndex(nextIndex)
            } else {
                if (nextIndex >= 0) {
                    io.legado.app.constant.AppLog.put("AI背景音乐: AI推荐与新播BGM相同或无效，改随机")
                }
                // 无有效AI推荐：随机播放，且排除最近播放（避免随机也连着重复）
                // 清单为空时 random() 会抛 NoSuchElementException，先判断是否为空
                if (bgmNames.isEmpty()) {
                    io.legado.app.constant.AppLog.put("AI背景音乐: BGM清单为空，无法随机播放下一首")
                    return@launch
                }
                val recent = BgmKeywordMatcher.getRecentBgms().toSet()
                var nextRandom = -1
                // 最多尝试10次找一首不在最近播放里的；全被排除就完全随机
                repeat(10) {
                    if (nextRandom < 0) {
                        val cand = (0 until bgmNames.size).random()
                        val base = bgmNames[cand].substringBeforeLast(".").trim()
                        if (recent.none { it.equals(base, ignoreCase = true) }) {
                            nextRandom = cand
                        }
                    }
                }
                if (nextRandom < 0) nextRandom = (0 until bgmNames.size).random()
                val fileName = bgmNames.getOrNull(nextRandom) ?: "未知"
                io.legado.app.utils.LogUtils.d("BgmManager", "🎵 BGM结束，无AI推荐，随机播放: index=$nextRandom, name=$fileName")
                io.legado.app.constant.AppLog.put("AI背景音乐: BGM结束，无AI推荐，随机播放: $fileName")
                playAudioByIndex(nextRandom)
            }
            // onBgmStarted 由 playAudioByIndex 末尾统一触发
        }
    }

    /**
     * 音量平滑过渡动画（指定播放器）
     * @param player 目标播放器
     * @param targetVolume 目标音量 (0.0 - 1.0)
     * @param duration 持续时间 (毫秒)
     */
    private suspend fun animateVolume(player: ExoPlayer?, targetVolume: Float, duration: Long = 500L) {
        fadeJob?.cancel()
        fadeJob?.join()
        val startVolume = player?.volume ?: 0f

        fadeJob = mainScope.launch {
            val steps = 20
            val interval = duration / steps
            val delta = (targetVolume - startVolume) / steps

            for (i in 1..steps) {
                delay(interval)
                player?.volume = startVolume + delta * i
            }
            player?.volume = targetVolume
        }
        fadeJob?.join()
    }

    /**
     * 加载背景音乐名称列表
     * 只读取文件名字符串，不做任何文件查找或 URI 解析。
     * 优先读取“背景音乐的名字.txt”，若txt不存在才回退遍历文件夹。
     * txt 中写的是完整文件名（含扩展名），则保留到 bgmFullNames 中，
     * 播放时直接按完整文件名查找，不再试多种扩展名。
     */
    fun loadBgmFiles() {
        val uriStr = AppConfig.bgmPath
        if (uriStr.isNullOrBlank()) return

        bgmNames.clear()
        bgmFullNames.clear()
        try {
            val txtFileName = "背景音乐的名字.txt"

            if (uriStr.startsWith("content://")) {
                val docFile = DocumentFile.fromTreeUri(appCtx, Uri.parse(uriStr))
                docFile?.findFile(txtFileName)?.let { txtDoc ->
                    appCtx.contentResolver.openInputStream(txtDoc.uri)?.use { input ->
                        input.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                val trimmed = line.trim()
                                if (trimmed.isNotBlank()) {
                                    val name = getFileNameWithoutExtension(trimmed)
                                    if (name.isNotBlank() && !bgmNames.contains(name)) {
                                        bgmNames.add(name)
                                        bgmFullNames[name] = trimmed
                                    }
                                }
                            }
                        }
                    }
                } ?: run {
                    // txt 不存在才回退到遍历文件夹
                    docFile?.listFiles()?.forEach { file ->
                        val fileName = file.name ?: return@forEach
                        if (file.isFile && isAudioFile(fileName)) {
                            val name = getFileNameWithoutExtension(fileName)
                            if (name.isNotBlank() && !bgmNames.contains(name)) {
                                bgmNames.add(name)
                                bgmFullNames[name] = fileName
                            }
                        }
                    }
                }
            } else {
                val dir = File(uriStr)
                if (dir.exists() && dir.isDirectory) {
                    val txtFile = File(dir, txtFileName)
                    if (txtFile.exists()) {
                        txtFile.useLines { lines ->
                            lines.forEach { line ->
                                val trimmed = line.trim()
                                if (trimmed.isNotBlank()) {
                                    val name = getFileNameWithoutExtension(trimmed)
                                    if (name.isNotBlank() && !bgmNames.contains(name)) {
                                        bgmNames.add(name)
                                        bgmFullNames[name] = trimmed
                                    }
                                }
                            }
                        }
                    } else {
                        // txt 不存在才回退到遍历文件夹
                        dir.listFiles()?.forEach { file ->
                            if (file.isFile && isAudioFile(file.name)) {
                                val name = getFileNameWithoutExtension(file.name)
                                if (name.isNotBlank() && !bgmNames.contains(name)) {
                                    bgmNames.add(name)
                                    bgmFullNames[name] = file.name
                                }
                            }
                        }
                    }
                }
            }

            if (bgmNames.isNotEmpty()) {
                bgmNames.shuffle()
                BgmKeywordMatcher.loadConfig(uriStr)
                if (BgmKeywordMatcher.isConfigValid && !BgmKeywordMatcher.hasFileNames()) {
                    BgmKeywordMatcher.setFileNames(bgmNames.toList())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 重新扫描背景音乐文件夹内的所有音频文件，将完整文件名列表（含扩展名）
     * 覆盖写入“背景音乐的名字.txt”，随后重新加载内存中的 BGM 名称列表。
     * 用于总开关每次打开时，让 txt 清单与文件夹实际内容保持同步。
     * 必须在 IO 线程中调用，避免主线程阻塞。
     */
    fun refreshBgmNamesFromFolder() {
        val uriStr = AppConfig.bgmPath
        if (uriStr.isNullOrBlank()) return
        try {
            val txtFileName = "背景音乐的名字.txt"
            val names = collectAudioFileNames(uriStr)
            // 扫描结果为 0 时不覆盖：多半是目录不可访问/路径不对，避免把已有清单清空
            if (names.isEmpty()) {
                io.legado.app.constant.AppLog.put("AI背景音乐: ⚠️ 扫描到 0 个音频，已跳过写入，保留原「$txtFileName」，目录=$uriStr")
            } else {
                val ok = writeBgmNamesTxt(uriStr, txtFileName, names)
                io.legado.app.constant.AppLog.put(
                    "AI背景音乐: 扫描到 ${names.size} 个音频，写入「$txtFileName」${if (ok) "成功" else "失败❌(目录无写权限或路径不对)"}"
                )
                io.legado.app.utils.LogUtils.d(
                    "BgmManager", "刷新BGM清单: 扫描=${names.size}, 写入成功=$ok, 目录=$uriStr"
                )
            }
            // 重新加载内存列表，使后续播放使用最新清单
            loadBgmFiles()
        } catch (e: Exception) {
            e.printStackTrace()
            io.legado.app.constant.AppLog.put("AI背景音乐: 刷新BGM清单异常 ${e.localizedMessage}")
            // 即使写入失败，也尝试重新加载，保证不崩溃
            runCatching { loadBgmFiles() }
        }
    }

    /**
     * 收集文件夹内所有音频文件的完整文件名（含扩展名）。
     */
    private fun collectAudioFileNames(uriStr: String): List<String> {
        val names = mutableListOf<String>()
        if (uriStr.startsWith("content://")) {
            val docFile = DocumentFile.fromTreeUri(appCtx, Uri.parse(uriStr))
            docFile?.listFiles()?.forEach { child ->
                val fileName = child.name
                if (child.isFile && isAudioFile(fileName) && fileName != null && !names.contains(fileName)) {
                    names.add(fileName)
                }
            }
        } else {
            val dir = File(uriStr)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    val fileName = file.name
                    if (file.isFile && isAudioFile(fileName) && fileName != null && !names.contains(fileName)) {
                        names.add(fileName)
                    }
                }
            }
        }
        return names.sorted()
    }

    /**
     * 将音频文件名列表写入“背景音乐的名字.txt”（每行一个完整文件名）。
     * @return 是否真的写入成功；失败时调用方可以提示用户，而不是让用户以为清单已刷新。
     */
    private fun writeBgmNamesTxt(uriStr: String, txtFileName: String, names: List<String>): Boolean {
        val content = names.joinToString("\n")
        return try {
            if (uriStr.startsWith("content://")) {
                val docFile = DocumentFile.fromTreeUri(appCtx, Uri.parse(uriStr))
                if (docFile == null || !docFile.isDirectory) {
                    io.legado.app.constant.AppLog.put("AI背景音乐: BGM目录不可访问，写入失败，uri=$uriStr")
                    return false
                }
                // 必须先删掉旧文件再新建：
                // 1) SAF 的 createFile 遇到同名存在时会自动改名成「xxx (1).txt」，新清单会写进副本；
                // 2) 只覆盖不删除时，新内容比旧内容短，尾部会残留旧行（已删除的音频仍在清单里）。
                deleteBgmTxt(docFile, txtFileName)
                if (docFile.findFile(txtFileName) != null) {
                    io.legado.app.constant.AppLog.put("AI背景音乐: ⚠️ 旧「$txtFileName」删除失败，改为截断覆盖")
                }
                val txtDoc = docFile.findFile(txtFileName) ?: docFile.createFile("text/plain", txtFileName)
                if (txtDoc == null) {
                    io.legado.app.constant.AppLog.put("AI背景音乐: 创建「$txtFileName」失败")
                    return false
                }
                if (txtDoc.name != txtFileName) {
                    io.legado.app.constant.AppLog.put("AI背景音乐: ⚠️ SAF 生成了副本「${txtDoc.name}」，请手动清理")
                }
                writeTextToDoc(txtDoc.uri, content)
            } else {
                val dir = File(uriStr)
                if (!dir.exists() || !dir.isDirectory) {
                    io.legado.app.constant.AppLog.put("AI背景音乐: BGM目录不存在，写入失败，path=$uriStr")
                    return false
                }
                // 同样先删后写，杜绝旧内容残留
                val txtFile = File(dir, txtFileName)
                if (txtFile.exists() && !txtFile.delete()) {
                    io.legado.app.constant.AppLog.put("AI背景音乐: 旧「$txtFileName」删除失败，改为覆盖写入")
                }
                txtFile.writeText(content, Charsets.UTF_8)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            io.legado.app.constant.AppLog.put("AI背景音乐: 写入「$txtFileName」异常 ${e.localizedMessage}")
            false
        }
    }

    /**
     * 删除 BGM 目录下已存在的清单文件，失败时再用 DocumentsContract 兜底删一次。
     */
    private fun deleteBgmTxt(docFile: DocumentFile, txtFileName: String) {
        val old = docFile.findFile(txtFileName) ?: return
        if (old.delete()) return
        runCatching {
            android.provider.DocumentsContract.deleteDocument(appCtx.contentResolver, old.uri)
        }.onFailure {
            io.legado.app.constant.AppLog.put("AI背景音乐: 删除旧清单失败 ${it.localizedMessage}")
        }
    }

    /**
     * 把文本真正"截断后写入"到文档：优先用文件描述符的 TRUNCATE（由系统 ftruncate 保证清空），
     * 失败再退回 openOutputStream("rwt")。
     */
    private fun writeTextToDoc(uri: Uri, content: String): Boolean {
        val bytes = content.toByteArray(Charsets.UTF_8)
        runCatching {
            appCtx.contentResolver.openFileDescriptor(uri, "wt")?.use { pfd ->
                java.io.FileOutputStream(pfd.fileDescriptor).use { it.write(bytes) }
                return true
            }
        }
        val out = appCtx.contentResolver.openOutputStream(uri, "rwt")
        if (out == null) {
            io.legado.app.constant.AppLog.put("AI背景音乐: 打开清单输出流失败(无写权限?)")
            return false
        }
        out.use { it.write(bytes) }
        return true
    }

    /**
     * 根据无扩展名的名字，在文件夹中查找对应的音频文件并返回 URI。
     * 只在播放时调用，每次只查一个文件。
     * 若 txt 中写了完整文件名（含扩展名），则直接按完整文件名查找，不再试多种扩展名。
     * 此函数必须在 IO 线程中调用，避免主线程阻塞。
     */
    private fun resolveAudioUri(nameWithoutExt: String): Uri? {
        val uriStr = AppConfig.bgmPath ?: return null
        try {
            val fullName = bgmFullNames[nameWithoutExt]
            if (uriStr.startsWith("content://")) {
                val docFile = DocumentFile.fromTreeUri(appCtx, Uri.parse(uriStr))
                // 优先使用完整文件名直接查找（一次命中）
                if (fullName != null) {
                    docFile?.findFile(fullName)?.let { child ->
                        if (child.isFile) return child.uri
                    }
                }
                // 回退：按无扩展名试 6 种扩展名
                audioExtensions.forEach { ext ->
                    docFile?.findFile("$nameWithoutExt.$ext")?.let { child ->
                        if (child.isFile) return child.uri
                    }
                }
            } else {
                val dir = File(uriStr)
                if (dir.exists() && dir.isDirectory) {
                    // 优先使用完整文件名直接查找（一次命中）
                    if (fullName != null) {
                        val file = File(dir, fullName)
                        if (file.exists() && file.isFile) return Uri.fromFile(file)
                    }
                    // 回退：按无扩展名试 6 种扩展名
                    audioExtensions.forEach { ext ->
                        val file = File(dir, "$nameWithoutExt.$ext")
                        if (file.exists() && file.isFile) return Uri.fromFile(file)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun isAudioFile(name: String?): Boolean {
        return name != null && audioExtensions.any { name.endsWith(".$it", true) }
    }

    private fun readAudioDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appCtx, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    /**
     * 根据内容切换背景音乐（AI智能识别）
     */
    suspend fun switchBgmByContent(content: String) = withContext(Dispatchers.IO) {
        // 如果音频文件列表为空，先加载一次
        if (bgmNames.isEmpty()) {
            io.legado.app.utils.LogUtils.d("BgmManager", "音频文件列表为空，尝试加载")
            loadBgmFiles()
            // 加载后仍然为空，说明用户没有设置背景音乐文件夹
            if (bgmNames.isEmpty()) {
                io.legado.app.utils.LogUtils.d("BgmManager", "用户未设置背景音乐文件夹")
                return@withContext
            }
        }

        io.legado.app.utils.LogUtils.d("BgmManager", "========== 开始AI分析文本 ==========")
        io.legado.app.utils.LogUtils.d("BgmManager", "文本长度=${content.length}")
        io.legado.app.utils.LogUtils.d("BgmManager", "文本前500字: ${content.take(500)}")
        io.legado.app.utils.LogUtils.d("BgmManager", "可用的音频文件: $bgmNames")

        // 输出到AppLog，方便用户在应用日志中查看
        io.legado.app.constant.AppLog.putDebug("AI背景音乐: 开始分析文本，长度=${content.length}")

        try {
            val recommendedFileName = BgmAIService.analyzeContent(content)
            io.legado.app.utils.LogUtils.d("BgmManager", "AI推荐文件名=$recommendedFileName")

            // 输出AI返回的文件名到AppLog
            io.legado.app.constant.AppLog.putDebug("AI背景音乐: AI返回推荐文件名=$recommendedFileName")

            if (!recommendedFileName.isNullOrEmpty()) {
                // 在bgmNames中查找匹配的索引
                val matchedIndex = findMediaItemIndexByName(recommendedFileName)
                if (matchedIndex >= 0) {
                    withContext(Dispatchers.Main) {
                        io.legado.app.utils.LogUtils.d("BgmManager", "✅ 精确匹配成功，切换到: $recommendedFileName")
                        io.legado.app.constant.AppLog.put("AI背景音乐: ✅ 精确匹配成功，切换到: $recommendedFileName")
                        playAudioByIndex(matchedIndex)
                    }
                } else {
                    // 没有找到匹配的文件，使用模糊匹配
                    io.legado.app.utils.LogUtils.d("BgmManager", "精确匹配失败，尝试模糊匹配")
                    io.legado.app.constant.AppLog.putDebug("AI背景音乐: 精确匹配失败，尝试模糊匹配，推荐文件名=$recommendedFileName")
                    val fuzzyIndex = findMediaItemIndexFuzzy(recommendedFileName)
                    if (fuzzyIndex >= 0) {
                        withContext(Dispatchers.Main) {
                            val fileName = bgmNames.getOrNull(fuzzyIndex) ?: "未知"
                            io.legado.app.utils.LogUtils.d("BgmManager", "✅ 模糊匹配成功，切换到: $fileName")
                            io.legado.app.constant.AppLog.put("AI背景音乐: ✅ 模糊匹配成功，推荐文件名=$recommendedFileName，实际切换到: $fileName")
                            playAudioByIndex(fuzzyIndex)
                        }
                    } else {
                        io.legado.app.utils.LogUtils.d("BgmManager", "❌ 未找到匹配的音频文件: $recommendedFileName")
                        io.legado.app.utils.LogUtils.d("BgmManager", "可用的音频文件列表: $bgmNames")
                        io.legado.app.constant.AppLog.put("AI背景音乐: ❌ 未找到匹配的音频文件，推荐文件名=$recommendedFileName，可用文件: $bgmNames")
                    }
                }
            } else {
                io.legado.app.utils.LogUtils.d("BgmManager", "⚠️ AI未返回推荐文件名")
                io.legado.app.constant.AppLog.put("AI背景音乐: ⚠️ AI未返回推荐文件名")
            }
        } catch (e: Exception) {
            io.legado.app.utils.LogUtils.d("BgmManager", "❌ 切换背景音乐失败: ${e.message}")
            io.legado.app.constant.AppLog.put("AI背景音乐: ❌ 切换失败\n${e.localizedMessage}", e)
            e.printStackTrace()
        }
        io.legado.app.utils.LogUtils.d("BgmManager", "========== AI分析结束 ==========")
        io.legado.app.constant.AppLog.putDebug("AI背景音乐: 分析结束")
    }

    /**
     * 在bgmNames中精确查找索引
     */
    fun findMediaItemIndexByName(fileName: String): Int {
        return bgmNames.indexOfFirst { it.equals(fileName, ignoreCase = true) }
    }

    /**
     * 在bgmNames中模糊查找索引
     */
    fun findMediaItemIndexFuzzy(keyword: String): Int {
        val lowerKeyword = keyword.lowercase()
        return bgmNames.indexOfFirst {
            it.lowercase().contains(lowerKeyword) ||
            lowerKeyword.contains(it.lowercase())
        }
    }

    /**
     * 通过索引播放指定的音频文件（按需查找文件并创建MediaItem）
     * 带淡入淡出，避免硬切换突兀
     * 若找不到文件则自动跳过到下一首
     * 【关键】resolveAudioUri 在 IO 线程执行，避免主线程阻塞
     */
    private suspend fun playAudioByIndex(index: Int) {
        if (index < 0 || index >= bgmNames.size) return
        // 已经在播放同一首，无需切换
        if (currentPlayingIndex == index && activePlayer?.isPlaying == true) {
            return
        }
        val name = bgmNames[index]
        // 在 IO 线程查找文件，避免主线程阻塞（SAF 路径的 findFile 是 IPC 调用）
        val uri = withContext(Dispatchers.IO) { resolveAudioUri(name) }

        if (uri == null) {
            io.legado.app.utils.LogUtils.d("BgmManager", "❌ 找不到音频文件: $name")
            io.legado.app.constant.AppLog.put("AI背景音乐: 找不到音频文件: $name，自动跳过")
            // 自动跳过到下一首
            // 注意：上面的 withContext(IO) 挂起期间，loadBgmFiles() 可能已清空 bgmNames，
            // 此处必须重新校验 size，不能用函数开头那次检查的结果，否则 % 0 崩溃
            val size = bgmNames.size
            if (size <= 0) return
            val nextIndex = (index + 1) % size
            if (nextIndex != index && nextIndex >= 0 && nextIndex < bgmNames.size) {
                playAudioByIndex(nextIndex)
            }
            return
        }

        io.legado.app.utils.LogUtils.d("BgmManager", "▶️ 播放BGM: index=$index, name=$name")
        io.legado.app.constant.AppLog.putDebug("AI背景音乐: 播放 $name (index=$index)")

        currentPlayingIndex = index
        BgmKeywordMatcher.recordPlayedBgm(name)
        // 读取时长也在 IO 线程
        withContext(Dispatchers.IO) {
            val duration = readAudioDuration(uri)
            if (duration > 0) {
                audioDurations[index] = duration
            }
        }
        val mediaItem = MediaItem.fromUri(uri)

        if (activePlayer?.isPlaying == true) {
            // 交叉淡出：standbyPlayer 播放新音乐，两者同时淡入淡出
            standbyPlayer?.setMediaItem(mediaItem)
            standbyPlayer?.prepare()
            standbyPlayer?.play()

            val fadeOutJob = mainScope.launch {
                animateVolume(activePlayer, 0f, duration = 500L)
            }
            val fadeInJob = mainScope.launch {
                animateVolume(standbyPlayer, AppConfig.bgmVolume / 100f, duration = 500L)
            }

            fadeOutJob.join()
            fadeInJob.join()

            activePlayer?.pause()

            // 交换角色
            val temp = activePlayer
            activePlayer = standbyPlayer
            standbyPlayer = temp
        } else {
            // 未播放时直接设置单首并淡入
            activePlayer?.setMediaItem(mediaItem)
            activePlayer?.prepare()
            activePlayer?.play()
            animateVolume(activePlayer, AppConfig.bgmVolume / 100f, duration = 500L)
        }
        onBgmStarted?.invoke()
    }

    /**
     * 获取不含扩展名的文件名
     */
    private fun getFileNameWithoutExtension(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex > 0) fileName.substring(0, lastDotIndex) else fileName
    }

    /**
     * 获取所有音频文件的文件名列表（不含扩展名）
     */
    fun getAudioFileList(): List<String> {
        return bgmNames.toList()
    }

    /**
     * 供导出流程使用：按 BGM 名（不含扩展名）取得本地可访问的音频文件。
     * - 普通文件路径：直接返回 File
     * - SAF(content://)：先拷贝到 workDir 下的临时文件再返回（content Uri 无法当 File 用）
     * 必须在 IO 线程调用。
     */
    fun resolveBgmFile(nameWithoutExt: String, workDir: File): File? {
        if (bgmNames.isEmpty()) loadBgmFiles()
        val uri = resolveAudioUri(nameWithoutExt) ?: return null
        val path = uri.path
        if (uri.scheme == "file" && !path.isNullOrBlank()) {
            val f = File(path)
            return if (f.exists() && f.length() > 0L) f else null
        }
        return try {
            workDir.mkdirs()
            val out = File(workDir, "bgm_${Integer.toHexString(nameWithoutExt.hashCode())}.tmp")
            if (!out.exists() || out.length() == 0L) {
                appCtx.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (out.exists() && out.length() > 0L) out else null
        } catch (e: Exception) {
            io.legado.app.constant.AppLog.put(
                "BGM导出: 复制音频失败 $nameWithoutExt, ${e.localizedMessage}"
            )
            null
        }
    }

    /**
     * 当前 BGM 已播放到的位置（毫秒），跨章节接续时作为下一章的起始偏移。
     */
    fun getCurrentBgmPosition(): Long {
        return activePlayer?.currentPosition?.coerceAtLeast(0) ?: 0L
    }

    /**
     * 是否已播放过（用于区分首次启动和恢复播放）
     */
    var hasPlayedOnce = false
        private set

    /**
     * 播放指定索引的BGM（带淡入）
     */
    fun playByIndex(index: Int) {
        if (!AppConfig.isBgmEnabled) return
        mainScope.launch {
            if (bgmNames.isEmpty()) {
                withContext(Dispatchers.IO) { loadBgmFiles() }
            }
            if (bgmNames.isNotEmpty()) {
                playAudioByIndex(index.coerceIn(0, bgmNames.size - 1))
                hasPlayedOnce = true
            }
        }
    }

    /**
     * 开始播放（带淡入），首次播放时走默认索引0
     */
    fun play() {
        if (!AppConfig.isBgmEnabled) return
        mainScope.launch {
            if (bgmNames.isEmpty()) {
                withContext(Dispatchers.IO) { loadBgmFiles() }
            }
            if (bgmNames.isNotEmpty() && activePlayer?.isPlaying != true) {
                var ready = false
                if (activePlayer?.currentMediaItem == null) {
                    val defaultIndex = 0.coerceAtMost(bgmNames.size - 1)
                    val name = bgmNames[defaultIndex]
                    val uri = withContext(Dispatchers.IO) { resolveAudioUri(name) }
                    if (uri != null) {
                        currentPlayingIndex = defaultIndex
                        BgmKeywordMatcher.recordPlayedBgm(name)
                        activePlayer?.setMediaItem(MediaItem.fromUri(uri))
                        activePlayer?.prepare()
                        ready = true
                    }
                } else {
                    ready = true
                }
                if (ready) {
                    activePlayer?.play()
                    animateVolume(activePlayer, AppConfig.bgmVolume / 100f)
                    hasPlayedOnce = true
                }
            }
            onBgmStarted?.invoke()
        }
    }

    /**
     * 暂停（带淡出）
     *
     * 先无条件暂停两个播放器再做淡出装饰：
     * 旧的实现只暂停当时的 activePlayer，若恰好撞上交叉淡出切歌的窗口
     * （standbyPlayer 已 play、角色尚未交换），暂停会落空——新歌淡入后
     * 无人再管，而朗读已停止，BGM 就一直播下去了。
     * 恢复播放时 play()/playAudioByIndex() 会重新淡入设置音量，先停无副作用。
     */
    fun pause() {
        mainScope.launch {
            playerA?.pause()
            playerB?.pause()
            animateVolume(activePlayer, 0f)
        }
    }

    /**
     * 下一首（交叉淡出）
     */
    fun next() {
        mainScope.launch {
            val size = bgmNames.size
            if (size <= 0) return@launch
            val nextIndex = (currentPlayingIndex + 1) % size
            playAudioByIndex(nextIndex)
            io.legado.app.utils.LogUtils.d("BgmManager", "手动切换到下一首: index=$nextIndex")
        }
    }

    /**
     * 上一首
     */
    fun prev() {
        mainScope.launch {
            val size = bgmNames.size
            if (size <= 0) return@launch
            val prevIndex = (currentPlayingIndex - 1 + size) % size
            playAudioByIndex(prevIndex)
            io.legado.app.utils.LogUtils.d("BgmManager", "手动切换到上一首: index=$prevIndex")
        }
    }

    /**
     * 实时调整音量
     */
    fun setVolume(progress: Int) {
        AppConfig.bgmVolume = progress
        mainScope.launch {
            fadeJob?.cancel()
            activePlayer?.volume = progress / 100f
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        mainScope.launch {
            fadeJob?.cancel()
            playerA?.release()
            playerB?.release()
            playerA = null
            playerB = null
            activePlayer = null
            standbyPlayer = null
            bgmNames.clear()
            bgmFullNames.clear()
            audioDurations.clear()
            currentPlayingIndex = -1
            hasPlayedOnce = false
            BgmKeywordMatcher.clearRecentBgms()
        }
    }

    fun isPlaying(): Boolean {
        return activePlayer?.isPlaying == true
    }

    /**
     * 获取当前BGM剩余播放时长（毫秒）
     */
    fun getRemainingDuration(): Long {
        val player = activePlayer ?: return 0L
        if (currentPlayingIndex < 0) return 0L
        val cachedDuration = audioDurations[currentPlayingIndex]
        val duration = if (cachedDuration != null && cachedDuration > 0) {
            cachedDuration
        } else {
            player.duration.coerceAtLeast(0)
        }
        val position = player.currentPosition.coerceAtLeast(0)
        return (duration - position).coerceAtLeast(0)
    }

    /**
     * 获取当前正在播放的BGM文件名（不含扩展名）
     */
    fun getCurrentBgmName(): String? {
        return bgmNames.getOrNull(currentPlayingIndex)
    }

    /**
     * 获取当前正在播放的BGM索引
     */
    fun getCurrentBgmIndex(): Int = currentPlayingIndex
}
