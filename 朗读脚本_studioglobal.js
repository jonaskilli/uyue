// ===================== 朗读脚本（内置 studioglobal 版）：无需密钥、开箱即用 =====================
// 本版本使用 studioglobal 通道（凭证运行时从响应头自动申请，有效期 30 天，缓存于 sg_session.json），不依赖任何 API Key。
// 原需要密钥的版本仍保留为：朗读脚本Z2需要密钥.json
var EXT_DIR = "/storage/emulated/0/Download/chajian/mingwuyan/";
var CACHE_ROOT = "/storage/emulated/0/Download/chajian/xiaoshuo/";
var KEY_FILE = EXT_DIR + "miyue.txt";
var DATA_FILE = EXT_DIR + "data.json";
var HISTORY_FILE = EXT_DIR + "paragraphHistory.json";
var BOOK_LIST_FILE = EXT_DIR + "liebiao.json";
var PROGRESS_FILE = CACHE_ROOT + "reading_progress.json";

var NAME_ANALYZE_TIMEOUT = 25000;
var RACE_DELAY = 25000;
var MAX_RETRY = 3;
var EXTRACT_LENGTH = 1000;
var MAX_HISTORY = 20;
var MAX_CHARACTER = 100;
var saveMassCharacter = 1;

var DEFAULT_API_CONFIG = {
    endpoint: "https://open.bigmodel.cn/api/paas/v4/chat/completions",
    model: "glm-4-flash",
    key: "此处替换成你的智谱AI密钥"
};

var DEFAULT_VOICE_IDX = "少女01";
var IS_BOOK_SWITCHED = false;
var markMode = 2;

// ===================== 发音人角色配置 =====================
var MAIN_ROLES_CONFIG = [];
var BATCH_ROLES = [
  ['主角 男主', '主角', '男主', '男主', 20],
  ['主角 女主', '主角', '女主', '女主', 20],
  ['女/少女',   '女', '少女',   '少女',   100],
  ['男/少年',   '男', '少年',   '少年',   100],
  ['女/女青年', '女', '女青年', '女青年', 100],
  ['男/男青年', '男', '男青年', '男青年', 100],
  ['女/女中年', '女', '女中年', '女中年', 100],
  ['男/男中年', '男', '男中年', '男中年', 100],
  ['女/女老年', '女', '女老年', '女老年', 100],
  ['男/男老年', '男', '男老年', '男老年', 100],
  ['女/女童',   '女', '女童',   '女童',   100],
  ['男/男童',   '男', '男童',   '男童',   100]
];
var SPECIAL_ROLES = [
  ['【】括号发音人', '特殊', '系统', '括号1'],
  ['在线音效',       '特',   '特殊', '括号2'],
  ['「」括号发音人', '特',   '特殊', '括号3'],
  ['『对话旁白』',   '特殊', '旁白', '括号4']
];

var GENSHIN_CHARACTERS = (function () {
  var chars = {};
  for (var idx = 0; idx < MAIN_ROLES_CONFIG.length; idx++) {
      var cfg = MAIN_ROLES_CONFIG[idx];
      var displayPrefix = cfg[0], gender = cfg[1], age = cfg[2], voicePrefix = cfg[3], count = cfg[4];
      for (var i = 1; i <= count; i++) {
          var seqDisplay = padZero(i, 2);
          var seqVoice = (voicePrefix === '男主') ? i.toString() : padZero(i, 2);
          var name = '【' + displayPrefix + seqDisplay + '】';
          chars[name] = { gender: gender, age: age, voice: voicePrefix + seqVoice };
      }
  }
  for (var bi = 0; bi < BATCH_ROLES.length; bi++) {
      var item = BATCH_ROLES[bi];
      var type = item[0], gender = item[1], age = item[2], voicePre = item[3], count = item[4];
      for (var i = 1; i <= count; i++) {
          var seq = padZero(i, 2);
          var name = '【' + type + seq + '】';
          chars[name] = { gender: gender, age: age, voice: voicePre + seq };
      }
  }
  for (var si = 0; si < SPECIAL_ROLES.length; si++) {
      var item = SPECIAL_ROLES[si];
      chars[item[0]] = { gender: item[1], age: item[2], voice: item[3] };
  }
  return chars;
})();

// ===================== 从 fayinren.json 读取可用发音人标签 =====================
// 外部提供 fayinren.json，内容为发音人标签数组，例如 ["少女01","少女02",...]
// 如果文件不存在或格式错误，则启用所有 voice（enabledTags 为 null）
var enabledTags = null; // null 表示全部启用
try {
    var fayinrenRaw = java.readExternalFile(EXT_DIR + "fayinren.json");
    if (fayinrenRaw && fayinrenRaw.trim() !== "") {
        var fayinrenList = JSON.parse(fayinrenRaw);
        if (isArray(fayinrenList)) {
            enabledTags = {};
            for (var fi = 0; fi < fayinrenList.length; fi++) {
                var vtag = String(fayinrenList[fi]).trim();
                if (vtag) enabledTags[vtag] = true;
            }
        }
    }
} catch (e) {
    // 读取失败，启用全部
    enabledTags = null;
}

var ALL_VOICE_TAGS = [];
var VOICE_TAG_TO_KEY = {};
var genderAgeToVoices = {};
for (var key in GENSHIN_CHARACTERS) {
    if (GENSHIN_CHARACTERS.hasOwnProperty(key)) {
        var c = GENSHIN_CHARACTERS[key];
        var voice = c.voice;
        // 如果 enabledTags 为 null 或该 voice 被启用，则加入
        if (!enabledTags || enabledTags[voice]) {
            var ga = c.gender + c.age;
            if (!genderAgeToVoices[ga]) genderAgeToVoices[ga] = [];
            genderAgeToVoices[ga].push(voice);
            ALL_VOICE_TAGS.push(voice);
            VOICE_TAG_TO_KEY[voice] = key;
        }
    }
}
for (var ga in genderAgeToVoices) {
    if (genderAgeToVoices.hasOwnProperty(ga)) {
        genderAgeToVoices[ga] = uniqueArr(genderAgeToVoices[ga]).sort();
    }
}
ALL_VOICE_TAGS = uniqueArr(ALL_VOICE_TAGS).sort(function(a, b) { return a.localeCompare(b, 'zh-CN', { numeric: true }); });

var genderAgeMap = {
    "男-男童":   "男男童", "男-少年":   "男少年", "男-男青年": "男男青年",
    "男-男中年": "男男中年", "男-男老年": "男男老年", "男-主角":   "男主角",
    "女-女童":   "女女童", "女-少女":   "女少女", "女-女青年": "女女青年",
    "女-女中年": "女女中年", "女-女老年": "女女老年", "女-主角":   "女主角",
    "男-儿童":   "男男童", "女-儿童":   "女女童", "男-青年":   "男男青年",
    "男-中年":   "男男中年", "男-老年":   "男男老年", "女-青年":   "女女青年",
    "女-中年":   "女女中年", "女-老年":   "女女老年"
};

// ===================== 工具函数 =====================
function log(msg) { try { java.log(msg); } catch (e) {} }
function padZero(num, length) { num = num.toString(); while (num.length < length) num = '0' + num; return num; }
function uniqueArr(arr) { var n = []; for (var i = 0; i < arr.length; i++) if (n.indexOf(arr[i]) === -1) n.push(arr[i]); return n; }
function isArray(obj) { return Object.prototype.toString.call(obj) === "[object Array]"; }
function cleanDialogText(text) {
    return text.replace(/[\s　\u2000-\u200F\u2028-\u202F\uFEFF]/g, "").replace(/【\d+】/g, "").replace(/[“”‘’"']/g, "").replace(/[^一-龥。？！，、；：“”‘’（）【】《》…—·a-zA-Z0-9]/g, "").trim();
}
function isValidVoiceNum(num) { return ALL_VOICE_TAGS.indexOf(num) >= 0; }
function extractVoiceDisplay(voiceNum) { return voiceNum || ""; }
function getEffectiveVoice(record) { if (!record) return null; var v = record.voice; if (v && isValidVoiceNum(v)) return v; return null; }
function isNameMatch(record, targetName) {
    if (!record || !targetName) return false;
    var mainName = String(record.name || "").trim();
    var aliases = String(record.aliases || mainName).trim();
    var allNames = uniqueArr(aliases.split("|").filter(function(s) { return s && s.trim(); }).concat([mainName]));
    var clean = String(targetName).trim();
    for (var i = 0; i < allNames.length; i++) if (String(allNames[i]).trim() === clean) return true;
    return false;
}

// ===================== 书籍列表 =====================
function readBookList() {
    try {
        var content = java.readExternalFile(BOOK_LIST_FILE);
        if (!content || content.trim() === "") return [];
        var list = JSON.parse(content);
        return isArray(list) ? list : [];
    } catch (e) { return []; }
}
function writeBookList(list) {
    try { java.writeExternalFile(BOOK_LIST_FILE, JSON.stringify(list, null, 2)); } catch (e) {}
}
function addBookToList(bookName) {
    if (!bookName || bookName.trim() === "") return;
    var list = readBookList();
    var pureName = bookName.trim();
    if (list.indexOf(pureName) === -1) { list.push(pureName); writeBookList(list); }
}

// ===================== 角色记录 =====================
function getLatestCharacterRecords() {
    try {
        var gengxinPath = EXT_DIR + "gengxin.json";
        var rawGengxin = java.readExternalFile(gengxinPath);
        if (rawGengxin && rawGengxin.trim() !== "") {
            var gengxinArr = JSON.parse(rawGengxin);
            if (isArray(gengxinArr)) {
                java.writeExternalFile(EXT_DIR + "characterRecords.json", JSON.stringify(gengxinArr, null, 2));
                java.deleteExternalFile(gengxinPath);
                return gengxinArr;
            }
        }
    } catch (e) {}
    try {
        var charPath = EXT_DIR + "characterRecords.json";
        var rawChar = java.readExternalFile(charPath);
        if (rawChar && rawChar.trim() !== "") {
            var charArr = JSON.parse(rawChar);
            if (isArray(charArr)) {
                var needSave = false;
                for (var i = 0; i < charArr.length; i++) {
                    var rec = charArr[i];
                    if (rec.usedVoices) { delete rec.usedVoices; needSave = true; }
                    if (rec.voiceNum && !rec.voice) { rec.voice = rec.voiceNum; needSave = true; }
                    delete rec.voiceNum;
                }
                if (needSave) java.writeExternalFile(charPath, JSON.stringify(charArr, null, 2));
                return charArr;
            }
        }
    } catch (e) {}
    return [];
}
function readBookCharacters() {
    try {
        var globalRecords = getLatestCharacterRecords();
        var result = [];
        for (var ri = 0; ri < globalRecords.length; ri++) {
            var r = globalRecords[ri];
            var g = String(r.gender || "").trim();
            var a = String(r.age || "").trim();
            var mainName = String(r.name || "").trim();
            var aliases = String(r.aliases || "").trim();
            result.push({ name: mainName, aliases: aliases, gender: g, age: a, genderAge: g + a, voice: r.voice || "", saveTime: new Date().getTime() });
        }
        return result;
    } catch (e) { return []; }
}
function saveBookCharacters(charArr) {
    try {
        var managerArr = [];
        for (var ci = 0; ci < charArr.length; ci++) {
            var r = charArr[ci];
            var ga = r.genderAge || "";
            var g = "", a = "";
            if (ga.indexOf("男") === 0) { g = "男"; a = ga.substring(1); }
            else if (ga.indexOf("女") === 0) { g = "女"; a = ga.substring(1); }
            var mainName = r.name || "";
            var aliases = r.aliases || mainName;
            managerArr.push({ name: mainName, aliases: aliases, voice: r.voice || "", gender: g, age: a, usageCount: 100, fixedVoice: !!(r.voice) });
        }
        java.writeExternalFile(EXT_DIR + "characterRecords.json", JSON.stringify(managerArr, null, 2));
    } catch (e) {}
}

// ===================== 发音人分配 =====================
function getTargetVoiceNum(genderAge, existingVoice, extraUsedVoices) {
    if (existingVoice && isValidVoiceNum(existingVoice)) return existingVoice;
    var allVoices = genderAgeToVoices[genderAge];
    if (!allVoices || allVoices.length === 0) {
        if (genderAge && genderAge.charAt(0) === '男') allVoices = genderAgeToVoices['男男青年'];
        else if (genderAge && genderAge.charAt(0) === '女') allVoices = genderAgeToVoices['女女青年'];
        else allVoices = genderAgeToVoices['男男青年'];
    }
    if (!allVoices || allVoices.length === 0) allVoices = genderAgeToVoices['男男青年'] || [];
    var savedChars = readBookCharacters();
    var usedVoices = [];
    for (var i = 0; i < savedChars.length; i++) {
        var ch = savedChars[i];
        var effV = getEffectiveVoice(ch);
        if (effV && allVoices.indexOf(effV) !== -1) usedVoices.push(effV);
    }
    if (extraUsedVoices && extraUsedVoices.length > 0) usedVoices = usedVoices.concat(extraUsedVoices);
    usedVoices = uniqueArr(usedVoices);
    var remainVoices = [];
    for (var i = 0; i < allVoices.length; i++) if (usedVoices.indexOf(allVoices[i]) === -1) remainVoices.push(allVoices[i]);
    if (remainVoices.length > 0) return remainVoices[0];
    var seenVoice = {}, dedupedChars = [];
    for (var i = 0; i < savedChars.length; i++) {
        var ch = savedChars[i];
        if (ch.genderAge && ch.genderAge.indexOf("主角") === -1) {
            var effV = getEffectiveVoice(ch);
            if (effV && !seenVoice[effV] && allVoices.indexOf(effV) !== -1) {
                seenVoice[effV] = true;
                dedupedChars.push({ name: ch.name, voice: effV });
            }
        }
    }
    if (dedupedChars.length > 0) return dedupedChars[dedupedChars.length - 1].voice;
    return allVoices[0] || DEFAULT_VOICE_IDX;
}

function resolveNameToRecord(inputName, records) {
    if (!inputName || !records) return null;
    var name = String(inputName).trim();
    for (var i = 0; i < records.length; i++) {
        var rec = records[i];
        if (!rec || !rec.name) continue;
        var mainName = String(rec.name).trim();
        if (name === mainName) {
            return { mainName: mainName, gender: rec.gender, age: rec.age, effectiveVoice: getEffectiveVoice(rec) };
        }
        if (rec.aliases) {
            var aliasArr = String(rec.aliases).split("|");
            for (var j = 0; j < aliasArr.length; j++) {
                var alias = String(aliasArr[j]).trim();
                if (alias === name) {
                    return { mainName: mainName, gender: rec.gender, age: rec.age, effectiveVoice: getEffectiveVoice(rec) };
                }
            }
        }
    }
    return null;
}

function saveCharacter(name, genderAge, voiceNum, voice) {
    if (saveMassCharacter === 0 && name.indexOf("群众") >= 0) return;
    if (!genderAgeToVoices[genderAge] && genderAge.indexOf("主角") === -1 || !name || name.length < 1 || name.length > 30) return;
    var charArr = readBookCharacters();
    var existingEntry = null;
    for (var i = 0; i < charArr.length; i++) {
        if (isNameMatch(charArr[i], name)) { existingEntry = charArr[i]; break; }
    }
    var existingEffectiveVoice = getEffectiveVoice(existingEntry);
    var finalVoice;
    if (existingEffectiveVoice) {
        finalVoice = existingEffectiveVoice;
    } else {
        finalVoice = isValidVoiceNum(voice) ? voice : (isValidVoiceNum(voiceNum) ? voiceNum : getTargetVoiceNum(genderAge, null, []));
    }
    var preservedAliases = (existingEntry && existingEntry.aliases) ? existingEntry.aliases : name;
    var aliasArr = preservedAliases.split("|").filter(function(s) { return s.trim(); });
    if (aliasArr.indexOf(name) === -1) aliasArr.unshift(name);
    var newAliases = aliasArr.join("|");
    var newCharArr = [];
    for (var i = 0; i < charArr.length; i++) if (!isNameMatch(charArr[i], name)) newCharArr.push(charArr[i]);
    newCharArr.unshift({ name: name, genderAge: genderAge, voice: finalVoice, aliases: newAliases });
    if (newCharArr.length > MAX_CHARACTER) newCharArr.pop();
    saveBookCharacters(newCharArr);
}

// ===================== 书籍切换 =====================
function getBookNameSafely() {
    var bookName = "";
    try {
        var dataStr = String(java.readExternalFile(DATA_FILE));
        if (!dataStr || dataStr === "") return "";
        var data = JSON.parse(dataStr);
        if (!data || !data.bookName) return "";
        bookName = data.bookName ? String(data.bookName).trim() : "";
    } catch (e) { bookName = ""; }
    return bookName.replace(/[\\/:*?"<>|]/g, "_").substring(0, 50);
}
function readCurrentBookName() {
    try {
        var nameFile = EXT_DIR + "cunfang.txt";
        var rawBook = java.readExternalFile(nameFile);
        var bookStr = rawBook ? String(rawBook) : "";
        if (bookStr && bookStr.charAt(0) === '"') { try { bookStr = JSON.parse(bookStr); } catch (e) {} }
        var bookName = (typeof bookStr === "string" ? bookStr : "").trim();
        return bookName;
    } catch (e) { return ""; }
}
function saveCurrentBookName(bookName) {
    try { java.writeExternalFile(EXT_DIR + "cunfang.txt", (typeof bookName === "string" ? bookName : "").trim() || "default_book"); } catch (e) {}
}
function handleBookSwitch() {
    var currBookName = getBookNameSafely();
    // 【朗读规则2 门槛1】data.json 未就绪（读取失败/无书名）→ 跳过切书，什么都不动
    if (!currBookName) return;
    var validCurrBook = currBookName;
    // 【朗读规则2 门槛2】cunfang.txt 读不到（文件不存在）→ 跳过切书，什么都不动
    var oldBookName = "";
    var cunfangReadSuccess = false;
    try {
        var rawCunfang = java.readExternalFile(EXT_DIR + "cunfang.txt");
        if (rawCunfang) {
            var cunfangStr = String(rawCunfang).trim();
            if (cunfangStr.charAt(0) === "\"") { try { cunfangStr = JSON.parse(cunfangStr); } catch (e2) {} }
            oldBookName = String(cunfangStr).trim();
            cunfangReadSuccess = true;
        }
    } catch (e) {}
    addBookToList(validCurrBook);
    if (!cunfangReadSuccess) return;
    if (oldBookName === validCurrBook) return;
    IS_BOOK_SWITCHED = true;
    var charMainPath = EXT_DIR + "characterRecords.json";
    var newBackupPath = EXT_DIR + "shuming." + validCurrBook + ".json";
    // 旧书角色备份（cunfang.txt 存在但为空时无法确认旧书 → 跳过备份【同朗读规则2：宁丢勿串】）
    if (oldBookName) {
        var oldBackupPath = EXT_DIR + "shuming." + oldBookName + ".json";
        var rawContent = "";
        try { rawContent = java.readExternalFile(charMainPath); } catch (err) { rawContent = "[]"; }
        try { java.writeExternalFile(oldBackupPath, rawContent); } catch (err) {}
    }
    var newRaw = "[]";
    try { newRaw = java.readExternalFile(newBackupPath); if (!newRaw || newRaw.trim() === "") newRaw = "[]"; } catch (err) { newRaw = "[]"; }
    try { java.writeExternalFile(charMainPath, newRaw); } catch (err) {}
    java.writeExternalFile(HISTORY_FILE, "[]");
    java.writeExternalFile(PROGRESS_FILE, "{}");
    saveCurrentBookName(validCurrBook);
}

// ===================== 进度指针 =====================
function readProgress() {
    try {
        var content = java.readExternalFile(PROGRESS_FILE);
        if (!content || content.trim() === "") return null;
        var prog = JSON.parse(content);
        if (prog && prog.bookName && prog.chapterTitle && typeof prog.lastSeq === "number") return prog;
    } catch (e) {}
    return null;
}
function writeProgress(bookName, chapterTitle, lastSeq) {
    try {
        java.writeExternalFile(PROGRESS_FILE, JSON.stringify({
            bookName: bookName,
            chapterTitle: chapterTitle,
            lastSeq: lastSeq
        }));
    } catch (e) {}
}

// ===================== 上下文与对话提取 =====================
function readParagraphHistory() {
    try {
        var content = String(java.readExternalFile(HISTORY_FILE));
        if (!content || content.trim() === "") return [];
        var historyArr = JSON.parse(content);
        return isArray(historyArr) ? historyArr : [];
    } catch (e) { return []; }
}
function saveCurrentToHistory(para) {
    if (!para || IS_BOOK_SWITCHED) return;
    var historyArr = readParagraphHistory();
    historyArr.push(para);
    if (historyArr.length > MAX_HISTORY) historyArr.shift();
    java.writeExternalFile(HISTORY_FILE, JSON.stringify(historyArr));
}
function getAboveContext() { var h = readParagraphHistory(); return h.length === 0 ? "" : h.join("\n"); }
function extractDialogs(paragraph) {
    var dialogs = [];
    var len = paragraph.length, idx = 0;
    while (idx < len) {
        var leftPos = paragraph.indexOf("“", idx);
        if (leftPos === -1) break;
        var rightPos = paragraph.indexOf("”", leftPos + 1);
        var content, hasRightQuote;
        if (rightPos !== -1) { content = paragraph.substring(leftPos + 1, Math.min(rightPos, leftPos + 1 + 1000)); hasRightQuote = true; }
        else { content = paragraph.substring(leftPos + 1, Math.min(len, leftPos + 1 + 1000)); hasRightQuote = false; }
        if (content && content.length >= 1) {
            dialogs.push({ content: content, index: leftPos, length: hasRightQuote ? content.length + 2 : content.length + 1, hasRightQuote: hasRightQuote });
        }
        idx = hasRightQuote ? rightPos + 1 : len;
    }
    return dialogs;
}
function getBelowContent(currentParagraph) {
    try {
        var dataStr = String(java.readExternalFile(DATA_FILE));
        if (!dataStr || dataStr.trim() === "") return "";
        var data = JSON.parse(dataStr);
        if (!data || !data.texts) return "";
        var fullChapter = String(data.texts);
        var targetPos = fullChapter.indexOf(currentParagraph);
        if (targetPos === -1) {
            var shortPara = currentParagraph.substring(0, Math.min(currentParagraph.length, 50));
            targetPos = fullChapter.indexOf(shortPara);
            if (targetPos === -1) return "";
        }
        var startExtractPos = targetPos + currentParagraph.length;
        var remainLen = fullChapter.length - startExtractPos;
        if (remainLen <= 0) return "";
        return fullChapter.substring(startExtractPos, startExtractPos + Math.min(EXTRACT_LENGTH, remainLen));
    } catch (e) { return ""; }
}

function generateBatchSeqContent(currentParagraph, dialogs, belowContent) {
    var cleanedDialogues = "";
    for (var i = 0; i < dialogs.length; i++) {
        var dialogText = dialogs[i].content || "";
        dialogText = dialogText.replace(/^【\d+】/, "");
        if (dialogs[i].hasRightQuote) {
            cleanedDialogues += "“" + dialogText + "”\n";
        } else {
            cleanedDialogues += "“" + dialogText + "\n";
        }
    }
    var cleanedBelow = (belowContent || "").replace(/【\d+】/g, "");
    var fullRawText = cleanedDialogues + cleanedBelow;


    fullRawText = fullRawText.replace(/(“[^“”\n]*)(\n[^“”]+)($|“)/g, "$1”$2$3");

    fullRawText = fullRawText.replace(/(“[^“”\n]*)($|“)/g, "$1”$2");

    fullRawText = fullRawText.replace(/【\d{1,4}】/g, "");
    fullRawText = fullRawText.replace(/[『「【〈〉〔'']/g, "");
    var allLeftQuotes = fullRawText.match(/“/g);
    var totalQuoteCount = allLeftQuotes ? allLeftQuotes.length : 0;
    var stopAddIndex = totalQuoteCount <= 5 ? totalQuoteCount : Math.max(Math.floor(totalQuoteCount * 0.9), 1);
    var seqCounter = 0;
    return fullRawText.replace(/“/g, function(match) {
        seqCounter++;
        if (seqCounter <= stopAddIndex) return "【" + padZero(seqCounter, 2) + "】" + match;
        return match;
    });
}

// ===================== 章节缓存 =====================
function sanitizeFileName(name) { return name.replace(/\?/g, "？").replace(/[\\/:*?"<>|]/g, "＿"); }
function getBookDir() { return CACHE_ROOT + sanitizeFileName(getBookNameSafely()) + "/"; }
function ensureBookDir() {
    var dir = getBookDir();
    try { java.ensureDirectory(dir); } catch (e) {}
    return dir;
}
function getChapterCachePath(chapterTitle) { return getBookDir() + sanitizeFileName(chapterTitle) + ".json"; }
function readChapterCache(chapterTitle) {
    try {
        var path = getChapterCachePath(chapterTitle);
        var content = java.readExternalFile(path);
        if (!content || content.trim() === "") return { title: chapterTitle, results: {} };
        var cache = JSON.parse(String(content));
        return (cache && cache.results) ? cache : { title: chapterTitle, results: {} };
    } catch (e) { return { title: chapterTitle, results: {} }; }
}
function writeChapterCache(chapterTitle, cacheData) {
    try { java.writeExternalFile(getChapterCachePath(chapterTitle), JSON.stringify(cacheData, null, 2)); } catch (e) { log("【缓存】写入失败：" + e.message); }
}
function mergeChapterResults(chapterTitle, newResults) {
    var cache = readChapterCache(chapterTitle);
    if (!cache.results) cache.results = {};
    for (var key in newResults) {
        if (newResults.hasOwnProperty(key)) {
            cache.results[key] = newResults[key];
        }
    }
    writeChapterCache(chapterTitle, cache);
}

function tryMatchTextWithNewlines(cachedText, cleanCurrent) {
    if (!cachedText) return false;
    if (cleanDialogText(cachedText) === cleanCurrent) return true;
    var lines = cachedText.split("\n");
    for (var i = 0; i < lines.length; i++) {
        if (cleanDialogText(lines[i]) === cleanCurrent) return true;
    }
    return false;
}

function matchInChapterCacheBySeq(chapterTitle, predictedSeq, cleanCurrent) {
    var cache = readChapterCache(chapterTitle);
    var results = cache.results || {};
    for (var offset = -2; offset <= 2; offset++) {
        var trySeq = predictedSeq + offset;
        if (trySeq < 1) continue;
        var key = String(trySeq);
        var cachedItem = results[key];
        if (!cachedItem) continue;
        var dialogText = cachedItem.dialogText;
        if (dialogText && tryMatchTextWithNewlines(dialogText, cleanCurrent)) {
            return cachedItem;
        }
    }
    return null;
}

// ★ 全文定位（浮动失败后使用）
function locateParagraphInFullText(paragraph) {
    try {
        var dataStr = String(java.readExternalFile(DATA_FILE));
        if (!dataStr || dataStr.trim() === "") return null;
        var data = JSON.parse(dataStr);
        var fullText = String(data.texts);
    } catch (e) { return null; }

    var paraPos = fullText.indexOf(paragraph);
    if (paraPos === -1) {
        var shortPara = paragraph.substring(0, Math.min(paragraph.length, 50));
        paraPos = fullText.indexOf(shortPara);
        if (paraPos === -1) return null;
    }

    var lines = fullText.split(/\r?\n/);
    var chapters = [];
    var currentTitle = null, currentBody = "";
    for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        if (line.trim() === "") continue;
        var startsWithIndent = (line.charAt(0) === "　" || line.charAt(0) === " ");
        if (!startsWithIndent && !currentTitle) {
            currentTitle = line.trim();
            currentBody = "";
        } else if (!startsWithIndent && currentTitle) {
            if (currentBody.trim() !== "") chapters.push({ title: currentTitle, fullText: currentBody });
            currentTitle = line.trim();
            currentBody = "";
        } else {
            currentBody += line + "\n";
        }
    }
    if (currentTitle && currentBody.trim() !== "") chapters.push({ title: currentTitle, fullText: currentBody });

    var chapterTitle = null;
    var chapterFullText = "";
    var cumulativeLength = 0;
    for (var i = 0; i < chapters.length; i++) {
        var ch = chapters[i];
        var nextCumulative = cumulativeLength + ch.fullText.length;
        if (paraPos < nextCumulative) {
            chapterTitle = ch.title;
            chapterFullText = ch.fullText;
            break;
        }
        cumulativeLength = nextCumulative;
    }
    if (!chapterTitle) return null;

    var paraPosInChapter = chapterFullText.indexOf(paragraph);
    if (paraPosInChapter === -1) {
        paraPosInChapter = chapterFullText.indexOf(paragraph.substring(0, Math.min(paragraph.length, 50)));
        if (paraPosInChapter === -1) return null;
    }
    var beforeText = chapterFullText.substring(0, paraPosInChapter);
    var beforeCount = (beforeText.match(/“/g) || []).length;
    var dialogs = extractDialogs(paragraph);
    var seqList = [];
    for (var n = 0; n < dialogs.length; n++) seqList.push(beforeCount + n + 1);

    var totalDialogs = (chapterFullText.match(/“/g) || []).length;

    return {
        chapterTitle: chapterTitle,
        seqList: seqList,
        totalDialogs: totalDialogs
    };
}

// ===================== 无引号文本匹配（仅限多行缓存对话） =====================
function handleNoQuoteText(originalText) {
    var progress = readProgress();
    if (!progress) return null;

    var chapterTitle = progress.chapterTitle;
    var cache = readChapterCache(chapterTitle);
    var results = cache.results || {};
    var cleanText = cleanDialogText(originalText);
    if (!cleanText) return null;

    for (var key in results) {
        if (results.hasOwnProperty(key)) {
            var cachedItem = results[key];
            var dialogText = cachedItem.dialogText || "";
            // ★ 关键限制：缓存中的对话必须包含换行符，即原本就是多行分割的对话
            if (dialogText.indexOf("\n") === -1) continue;
            if (tryMatchTextWithNewlines(dialogText, cleanText)) {
                var finalName = cachedItem.name || "未知";
                var finalGender = cachedItem.gender || "男";
                var finalAge = cachedItem.age || "青年";
                var latestRecords = readBookCharacters();
                var resRecord = resolveNameToRecord(finalName, latestRecords);
                var voice;
                if (resRecord && resRecord.effectiveVoice) {
                    voice = resRecord.effectiveVoice;
                    if (resRecord.gender) finalGender = resRecord.gender;
                    if (resRecord.age) finalAge = resRecord.age;
                } else {
                    var gaKey = finalGender + "-" + finalAge;
                    var genderAge = genderAgeMap[gaKey] || ((finalGender === "男") ? "男男青年" : "女女青年");
                    voice = getTargetVoiceNum(genderAge, null, []);
                }
                var voiceMark = "<<" + extractVoiceDisplay(voice) + ">>";
                log("【无引号匹配】命中多行缓存 → 章节:" + chapterTitle + " 序号:" + key + " → " + finalName + " [" + voice + "]");
                saveCurrentToHistory(originalText);
                // 补全双引号并标注
                return "“" + voiceMark + originalText + "”";
            }
        }
    }
    return null;
}

// ===================== 异常引号处理（已修复旁白丢失） =====================
function handleSpecialQuoteCases(originalText) {
  var hasLeftQuote = originalText.indexOf("“") !== -1;
  var hasRightQuote = originalText.indexOf("”") !== -1;
  // 如果引号已完整，交给正常流程
  if (hasLeftQuote && hasRightQuote) return null;

  var matchContent = "";
  var afterQuote = "";    // ★ 存放右引号后面的内容（旁白）
  var leftPos = -1, rightPos = -1;

  if (hasLeftQuote && !hasRightQuote) {
      // 只有左引号，缺右引号
      leftPos = originalText.indexOf("“");
      matchContent = originalText.substring(leftPos + 1);
      afterQuote = "";   // 右引号缺失，后续内容为空
  } else if (!hasLeftQuote && hasRightQuote) {
      // 只有右引号，缺左引号（如 “你好呀！”张三说。）
      rightPos = originalText.indexOf("”");
      matchContent = originalText.substring(0, rightPos);
      afterQuote = originalText.substring(rightPos + 1);   // ★ 保留右引号后的旁白
  } else {
      // 完全无引号，交 handleNoQuoteText 处理
      return null;
  }

  var cleanContent = cleanDialogText(matchContent);
  if (cleanContent.length === 0) return null;

  // --- 尝试在缓存中匹配说话人（逻辑不变） ---
  var finalName = null, finalGender = "男", finalAge = "青年", voice = null;
  var progress = readProgress();
  if (progress) {
      var cache = readChapterCache(progress.chapterTitle);
      var results = cache.results || {};
      for (var key in results) {
          if (results.hasOwnProperty(key)) {
              var cachedItem = results[key];
              var cachedDialog = cachedItem.dialogText || "";
              // 缺左引号时，只匹配多行拆分的对话（避免误伤）
              if (!hasLeftQuote && hasRightQuote && cachedDialog.indexOf("\n") === -1) continue;
              if (tryMatchTextWithNewlines(cachedDialog, cleanContent)) {
                  finalName = cachedItem.name;
                  finalGender = cachedItem.gender || "男";
                  finalAge = cachedItem.age || "青年";
                  break;
              }
          }
      }
  }

  // --- 确定最终使用的发音人 ---
  if (!finalName) {
      finalName = "群众";
      voice = getTargetVoiceNum("男男青年", null, []);
  } else {
      var latestRecords = readBookCharacters();
      var resRecord = resolveNameToRecord(finalName, latestRecords);
      if (resRecord && resRecord.effectiveVoice) {
          voice = resRecord.effectiveVoice;
          if (resRecord.gender) finalGender = resRecord.gender;
          if (resRecord.age) finalAge = resRecord.age;
      } else {
          var gaKey = finalGender + "-" + finalAge;
          var genderAge = genderAgeMap[gaKey] || ((finalGender === "男") ? "男男青年" : "女女青年");
          voice = getTargetVoiceNum(genderAge, null, []);
      }
  }

  var voiceMark = "<<" + extractVoiceDisplay(voice) + ">>";
  var resultText = "";

  // ★ 关键修复：拼接时把旁白（afterQuote）补回去
  if (!hasLeftQuote) {
      // 缺左引号：补左引号 + 标记 + 对话内容 + 右引号（原段落后段本就是右引号） + 旁白
      resultText = "“" + voiceMark + matchContent + "”" + afterQuote;
  } else {
      // 缺右引号：左引号已存在，在左引号后插入标记，并在段落末尾补上右引号
      var beforeLeft = originalText.substring(0, leftPos + 1);
      var afterLeft = originalText.substring(leftPos + 1);
      resultText = beforeLeft + voiceMark + afterLeft + "”";
  }

  log("【异常引号修复】" + finalName + " [" + voice + "] → 已保留旁白");
  saveCurrentToHistory(originalText);
  return resultText;
}

// ===================== API 调用（智谱AI：本地密钥 + Bearer + 非流式 + 密钥池轮询竞速） =====================
var _keyPools = {
    nameAnalyze: { list: [], index: 0, indexFile: "nameKeyIndex.txt" },
    aliasAnalyze: { list: [], index: 0, indexFile: "aliasKeyIndex.txt" }
};
function _loadKeyIndex(scene) {
    try {
        var idx = parseInt(String(java.readExternalFile(EXT_DIR + _keyPools[scene].indexFile)), 10);
        _keyPools[scene].index = !isNaN(idx) && idx >= 0 ? idx : 0;
    } catch (e) { _keyPools[scene].index = 0; }
}
function _saveKeyIndex(scene) {
    try { java.writeExternalFile(EXT_DIR + _keyPools[scene].indexFile, _keyPools[scene].index.toString()); } catch (e) {}
}
function _parseApiConfigs(content) {
    if (!content || content.trim() === "") return [];
    var contentTrim = content.trim();
    if (contentTrim.indexOf("@@") !== -1) {
        var splitArr = contentTrim.split("@@");
        var configs = [];
        for (var i = 0; i + 2 < splitArr.length; i += 3) {
            var endpoint = String(splitArr[i] || "").trim(), model = String(splitArr[i + 1] || "").trim(), key = String(splitArr[i + 2] || "").trim();
            if (key) {
                if (endpoint) {
                    if (endpoint.lastIndexOf("/") === endpoint.length - 1) endpoint = endpoint.slice(0, -1);
                    if (endpoint.indexOf("/chat/completions") === endpoint.length - 17) endpoint = endpoint.slice(0, -17);
                    endpoint += "/chat/completions";
                }
                configs.push({ endpoint: endpoint || DEFAULT_API_CONFIG.endpoint, model: model || DEFAULT_API_CONFIG.model, key: key });
            }
        }
        return configs;
    } else {
        return [{ endpoint: DEFAULT_API_CONFIG.endpoint, model: DEFAULT_API_CONFIG.model, key: contentTrim }];
    }
}
function loadApiConfigs(scene) {
    try {
        var fileContent = String(java.readExternalFile(KEY_FILE)).trim();
        var hasSplit = fileContent.indexOf("##") !== -1;
        var nameContent, aliasContent;
        if (hasSplit) {
            var parts = fileContent.split("##");
            nameContent = parts[0] ? parts[0].trim() : "";
            aliasContent = parts[1] ? parts[1].trim() : "";
        } else {
            nameContent = fileContent;
            aliasContent = fileContent;
        }
        _keyPools.nameAnalyze.list = _parseApiConfigs(nameContent);
        _keyPools.aliasAnalyze.list = _parseApiConfigs(aliasContent);
        _loadKeyIndex("nameAnalyze");
        if (hasSplit) _loadKeyIndex("aliasAnalyze");
        else {
            _keyPools.aliasAnalyze.index = _keyPools.nameAnalyze.index;
            _keyPools.aliasAnalyze.indexFile = _keyPools.nameAnalyze.indexFile;
        }
        var scenes = ["nameAnalyze", "aliasAnalyze"];
        for (var sIdx = 0; sIdx < scenes.length; sIdx++) {
            var s = scenes[sIdx];
            var pool = _keyPools[s];
            if (pool.list.length > 0 && pool.index >= pool.list.length) {
                pool.index = pool.index % pool.list.length;
                _saveKeyIndex(s);
            }
        }
        return _keyPools[scene] && _keyPools[scene].list ? _keyPools[scene].list : [];
    } catch (e) { return []; }
}
function getNextApiConfigs(scene, needCount) {
    loadApiConfigs(scene);
    var pool = _keyPools[scene];
    if (!pool || pool.list.length === 0) return [];
    if (!needCount || needCount <= 0) needCount = pool.list.length;
    if (needCount > pool.list.length) needCount = pool.list.length;
    var result = [];
    var currentIndex = pool.index;
    for (var i = 0; i < needCount; i++) {
        var targetIndex = (currentIndex + i) % pool.list.length;
        result.push(pool.list[targetIndex]);
    }
    pool.index = (pool.index + 1) % pool.list.length;
    _saveKeyIndex(scene);
    return result;
}

function buildAnalyzePrompt() {
    return "你是喜马拉雅听书软件中智能朗读功能的人声分配AI，任务是精准判断小说手稿中所有带【01】【02】序号标记的对话的说话人，每个序号对应一段对话。\n\n" +
        "你要具备下面的能力，中文小说说话人识别（专业名称为「对话归因/说话人归属识别」），核心是将小说中的对话精准匹配到对应人物：\n" +
        "1. 指代消解能力：人称代词（他/她）、身份代称（门主/师兄）、昵称与本名的精准对应，是该任务的核心难点，直接决定复杂场景的准确率；\n" +
        "2. 隐式对话识别能力：无“XX说/道”等明确提示词的连续对话，能否通过上下文语境、人物交替逻辑正确归因；\n" +
        "3. 中文小说语料适配度：对网文叙事习惯、对话格式、神态动作绑定话术的熟悉程度，避免把旁白和对话混淆、动作发出者与说话人错位；\n" +
        "4. 多人对话追踪能力：3人以上交叉对话的逻辑链维护，避免连续对话中出现说话人错位。\n" +
        "**【核心原则 - 最高优先级】**\n" +
        "1. 严禁将对话双引号“”**内部**提及的人名当作说话人，双引号内名字是「说话者谈论的其他人」，除非是本人自我介绍；\n" +
        "2. 示例：`张伟说：“别提了，都是为了王明那个项目。”` 中，说话人是**张伟**，绝非王明。\n" +
        "3. 连续对话中，说话人通常交替出现，若某角色连续多句对话，需检查是否有明确提示词（如“他接着说”）或上下文支持，避免错归为同一人。\n" +
        "4. 【多人同时说话】当一句话明确是多人同时说（如“张三和李四齐声说”“两人异口同声”“众人附和”“你一言我一语”），**只返回其中一个人的姓名**，不要返回“张三和李四”这类组合名字；\n" +
        "   - 优先返回地位更高、声音更强势、或在该场景中主导发言的那一个（发号施令者、带头说话者、长辈上级），确保能对应到已有角色；\n" +
        "   - 【严禁】返回含“和/与/、/及/跟/同”等连接词的组合人名，姓名必须是**单个具体人物**；\n" +
        "   - 这一句由这一个人代表朗读即可，其余人不必单独标注。\n" +
        "**【输出要求】**\n" +
        "1. 分析文本中所有带【01】【02】...【9999】序号标记的对话，每个序号对应一个结果，序号和对话一一对应，不能错位；\n" +
        "2. 返回严格的JSON格式，key为对话的序号（如'01'、'02'，必须和文本里的序号完全一致），value为对应角色信息；\n" +
        "3. 如果无法确定说话人姓名，就用前后对这个人的描述作为名字，如果连描述也没有，就根据性别年龄填写“群众男青年”“群众男中年”“群众男老年”“群众男童”“群众少女”“群众女青年”“群众女中年”“群众女老年”“群众女童”“系统”其中的一个；\n" +
        "4. 必须包含文本中所有序号的对话结果，不能遗漏、不能多返回、不能少返回。\n" +
        "5. 输出前，请仔细核对每个序号对应的对话内容与上下文，确保说话人归属无误；如遇歧义，优先选择上下文中最合理的角色，并避免因序号相邻而误判。\n" +
        "输出格式示例：\n{\n  \"01\": {\n    \"name\": \"分析出的说话人姓名\",\n    \"gender\": \"性别（男/女/特殊）\",\n    \"age\": \"年龄分类（女性：女童/少女/女青年/女中年/女老年）；（男性：男童/少年/男青年/男中年/男老年）；（特殊：系统/旁白）\"\n  },\n  \"02\": { ... }\n}\n";
}

// ===================== 容错解析增强（兼容智谱等模型返回格式差异）=====================
function apiLogTruncate(s, len) {
    len = len || 800;
    s = String(s == null ? "" : s);
    if (s.length <= len * 2) return s;
    return s.substring(0, len) + "\n...[已截断，共 " + s.length + " 字符]...\n" + s.substring(s.length - len);
}

// 容错提取 JSON：兼容缺失最外层大括号 / markdown 代码围栏 / 前后多余文本。
function extractJsonTolerant(content) {
    var c = String(content == null ? "" : content).trim();
    c = c.replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/i, "").trim();
    if (c === "") throw new Error("返回内容为空");
    var candidates = [];
    candidates.push("{" + c + "}");
    candidates.push("{" + c);
    candidates.push(c + "}");
    candidates.push(c);
    var fs = c.indexOf("{"), ls = c.lastIndexOf("}");
    if (fs !== -1 && ls !== -1 && ls >= fs) candidates.push(c.substring(fs, ls + 1));
    for (var i = 0; i < candidates.length; i++) {
        try { return JSON.parse(candidates[i]); } catch (e) {}
    }
    throw new Error("返回内容无法解析为JSON");
}

// 剔除 reason 字段：AI 常在 reason 里写未转义双引号导致整段 JSON 非法，剔除后重试。
function stripReasonField(content) {
    var c = String(content == null ? "" : content);
    return c.replace(/,\s*"reason"\s*:\s*"[\s\S]*?"\s*}/g, "}");
}

// ===================== 智谱「首条抽风」兜底：逐条宽松抽取 =====================
// 实测：智谱流式输出时经常只有「第一个条目」被截断或污染（序号 key 丢失、
// 字段名错位、值被截断，例如 "gender": "男e群众"er": "男"），而第 2 条起完全正常。
// 整段 JSON.parse 会因首条损坏而全盘失败，故整段解析失败时改为逐条正则抽取，
// 并对被污染的值做归一化，能救回多少是多少。
var VALID_AGES = ["群众男青年", "群众男中年", "群众男老年", "群众少女", "群众女青年",
    "群众女中年", "群众女老年", "群众男童", "群众女童", "女童", "少女", "女青年",
    "女中年", "女老年", "男童", "少年", "男青年", "男中年", "男老年", "系统", "旁白"];

// 性别值归一化：污染值如 "男e群众"、"男  " 只要含性别关键字即可还原
function normGenderValue(v) {
    v = String(v == null ? "" : v);
    if (v.indexOf("女") >= 0) return "女";
    if (v.indexOf("男") >= 0) return "男";
    if (v.indexOf("特殊") >= 0 || v.indexOf("旁白") >= 0 || v.indexOf("系统") >= 0) return "特殊";
    return null;
}

// 年龄值归一化：在合法词表里做包含匹配
function normAgeValue(v) {
    v = String(v == null ? "" : v);
    for (var i = 0; i < VALID_AGES.length; i++) {
        if (v.indexOf(VALID_AGES[i]) >= 0) return VALID_AGES[i];
    }
    return null;
}

// 取 key 对应的字符串值；字段被污染时取最后一次匹配（实测最后一次通常最干净）
function pickFieldLoose(block, key) {
    var re = new RegExp("[\"']?" + key + "[\"']?\\s*[:：]\\s*[\"']([^\"']*)[\"']", "g");
    var last = null, m;
    while ((m = re.exec(block)) !== null) { last = m[1]; }
    return (last == null) ? null : String(last).trim();
}

// 由一段文本块构建 {name, gender, age}；任一字段缺失/非法则返回 null
function buildNameItem(block) {
    if (!block) return null;
    var name = pickFieldLoose(block, "name");
    var gender = normGenderValue(pickFieldLoose(block, "gender"));
    var age = normAgeValue(pickFieldLoose(block, "age"));
    if (!name || !gender || !age) return null;
    return { name: name, gender: gender, age: age };
}

// 逐条抽取所有序号条目
function extractNameItemsLoose(content) {
    var c = String(content == null ? "" : content);
    // 去掉 markdown 围栏（含损坏形态，如 ```n / ```json）
    c = c.replace(/^\s*```[A-Za-z0-9]*/, "").replace(/```\s*$/, "");
    var result = {};
    // 形态A：标准/近似标准 "01": { ... }，容忍 key 缺引号、多余空格
    var re = /["']?(\d{1,2})["']?\s*[:：]\s*\{([^{}]*)\}/g;
    var m;
    while ((m = re.exec(c)) !== null) {
        var it = buildNameItem(m[2]);
        if (it) result[padZero(parseInt(m[1], 10), 2)] = it;
    }
    // 形态B：首个条目连 { 都丢了（如 `"01    "name": "姜峰",`），
    // 取开头到「第二个序号」之前的部分单独提取
    if (!result["01"]) {
        var headEnd = c.search(/["']?0?2["']?\s*[:：]/);
        if (headEnd > 0) {
            var head = c.substring(0, headEnd);
            var hi = buildNameItem(head);
            if (hi) result["01"] = hi;
        }
    }
    return result;
}

// ===================== studioglobal（凭证全部运行时自动获取，无内置硬编码）=====================
// 工作方式：
//   1) 首次运行 / 凭证缺失 → 自动申请
//   2) sg_session.json 里有未过期凭证 → 直接复用，不发申请请求
//   3) 凭证已过期或 24 小时内过期、或接口返回 401 → 自动重新申请并写回 sg_session.json
//
// ★ 认证从【响应头】获取，实测结论（2026-09-28）：
//   GET  /zh-cn                        → 200，响应头里没有 Set-Cookie，拿不到认证
//   GET  /api/creator/anonymous-session→ 405，不支持 GET
//   POST /api/creator/anonymous-session（body "{}"）→ 200，
//        响应头 set-cookie: studio_anonymous_session=eyJ2...（有效期 30 天），响应体只是 {"ready":true}
//   所以必须用 POST 那一条。
//
// ★ 响应头获取原理：java.ajaxRaceDelayed 的请求 JSON 里带 saveHeaders:true 时，
//   App 端 executeAjaxRequest() 会把该次请求的全部响应头（含 Set-Cookie）按 URL 写入
//   Download 目录下的 response_headers.json，脚本再读该文件解析即可拿到认证。
//   要求 App 端 POST 分支也支持落盘（旧版只在 GET + text/html 时落盘，拿不到本认证的）。
var SG_BASE = "https://www.studioglobal.ai";
var SG_UA = "Mozilla/5.0 (Linux; Android 15; V2352A Build/AP3A.240905.015.A2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.71 Mobile Safari/537.36";
var SG_MODEL = "gpt6Sol";

var SG_CRED_FILE = EXT_DIR + "sg_session.json";
var SG_API_SESSION = SG_BASE + "/api/creator/anonymous-session";
var SG_API_CHAT = SG_BASE + "/api/creator/chat/send-chat";

// ---- 响应头侧存相关常量 ----
var SG_COOKIE_NAME = "studio_anonymous_session";
var SG_HEADERS_FILE = EXT_DIR + "response_headers.json";   // App 端侧存文件（勿改名）
var SG_CRED_RENEW_BEFORE = 24 * 60 * 60 * 1000;   // 距过期不足 24 小时就提前续期

var _sgSession = "";
var _sgGuestId = "";
var _sgCredChecked = false;   // 每次脚本运行只做一次过期检查
var _sgRefreshed = false;     // 401 时只允许自动续期一次

// 载入凭证：只从 sg_session.json 读（无内置硬编码，读不到就靠 sgEnsureCred 自动申请）
function sgInitCred() {
    if (_sgSession) return;
    try {
        var raw = java.readExternalFile(SG_CRED_FILE);
        if (raw && String(raw).trim() !== "") {
            var o = JSON.parse(String(raw));
            if (o && o.guestId) _sgGuestId = String(o.guestId);
            if (o && o.session) _sgSession = String(o.session);
            log("【SG】已从 sg_session.json 载入凭证");
        }
    } catch (e) {}
}

function sgGetGuestId() {
    sgEnsureCred();
    // guestId 只是请求头里带的一个随机标识，服务端以 cookie 里的 session 为准，本地生成即可
    if (!_sgGuestId) _sgGuestId = String(java.randomUUID());
    return _sgGuestId;
}

function sgGetSession() {
    sgEnsureCred();
    return _sgSession;
}

// 保存当前凭证到外部文件（便于后续覆盖/排查）
function sgSaveCred() {
    try {
        java.writeExternalFile(SG_CRED_FILE, JSON.stringify({
            guestId: _sgGuestId,
            session: _sgSession,
            savedAt: new Date().getTime()
        }, null, 2));
    } catch (e) {}
}

// ===================== 从【响应头】获取认证（App 端 saveHeaders 侧存机制）=====================
// 读 App 端侧存的 response_headers.json，返回该 URL 的响应头 Map（key 统一小写）；没有则返回 null
function sgReadRespHeaders(url) {
    try {
        var raw = java.readExternalFile(SG_HEADERS_FILE);
        if (!raw || String(raw).trim() === "") return null;
        var map = JSON.parse(String(raw));
        var hdr = map[url];
        if (!hdr) {
            // 兜底：URL 可能被重定向/带尾斜杠，退化成互相包含匹配
            for (var k in map) {
                if (k && url && (String(k).indexOf(url) === 0 || url.indexOf(String(k)) === 0)) {
                    hdr = map[k];
                    break;
                }
            }
        }
        if (!hdr) return null;
        var lower = {};
        for (var n in hdr) lower[String(n).toLowerCase()] = String(hdr[n]);
        return lower;
    } catch (e) {
        log("【SG】读取响应头文件失败：" + e.message);
        return null;
    }
}

// 从 set-cookie 串里取指定 cookie 的值（多个 Set-Cookie 被 App 端用 "; " 合并，故按 ; 或 , 截断）
function sgParseCookie(setCookie, name) {
    if (!setCookie) return "";
    var m = String(setCookie).match(new RegExp(name + "=([^;,\\s]+)"));
    return (m && m[1]) ? m[1] : "";
}

// base64url 解码（用于读 session 里的 expiresAt）
var SG_B64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
function sgB64UrlDecode(s) {
    s = String(s).replace(/-/g, "+").replace(/_/g, "/");
    while (s.length % 4 !== 0) s += "=";
    var out = "", i = 0;
    while (i < s.length) {
        var c1 = SG_B64_CHARS.indexOf(s.charAt(i++));
        var c2 = SG_B64_CHARS.indexOf(s.charAt(i++));
        var c3 = SG_B64_CHARS.indexOf(s.charAt(i++));
        var c4 = SG_B64_CHARS.indexOf(s.charAt(i++));
        if (c2 < 0) break;
        out += String.fromCharCode((c1 << 2) | (c2 >> 4));
        if (c3 < 0) break;
        out += String.fromCharCode(((c2 & 15) << 4) | (c3 >> 2));
        if (c4 < 0) break;
        out += String.fromCharCode(((c3 & 3) << 6) | c4);
    }
    return out;
}

// 取 session 的过期时间（毫秒）；解析不出来返回 0
function sgSessionExpireAt(sess) {
    try {
        var parts = String(sess).split(".");
        if (parts.length < 2) return 0;
        var payload = JSON.parse(sgB64UrlDecode(parts[1]));
        return Number(payload && payload.expiresAt) || 0;
    } catch (e) {
        return 0;
    }
}

// ★ 核心：POST /api/creator/anonymous-session，从侧存的响应头里取回新认证
//   实测：该接口只接受 POST（GET 会 405），认证在响应头 set-cookie 里，响应体只有 {"ready":true}
function sgRefreshCredFromHeaders() {
    try {
        var req = [JSON.stringify({
            url: SG_API_SESSION,
            method: "POST",
            body: "{}",
            saveHeaders: true,           // ★ 触发 App 端侧存响应头（POST 分支也要支持，见 App 源码改动）
            headers: {
                "User-Agent": SG_UA,
                "Accept": "*/*",
                "Content-Type": "application/json",
                "Origin": SG_BASE,
                "Referer": SG_BASE + "/zh-cn"
            }
        })];
        java.ajaxRaceDelayed(req, 5000);

        var hdr = sgReadRespHeaders(SG_API_SESSION);
        if (!hdr) {
            log("【SG】未读到侧存响应头（response_headers.json 里没有 " + SG_API_SESSION + "）");
            return false;
        }
        var sess = sgParseCookie(hdr["set-cookie"], SG_COOKIE_NAME);
        if (!sess) {
            log("【SG】响应头里没有 " + SG_COOKIE_NAME + "，实际响应头：" + JSON.stringify(hdr).substring(0, 300));
            return false;
        }
        _sgSession = sess;
        sgSaveCred();                   // 写回 sg_session.json，下次直接复用
        log("【SG】★ 已从响应头获取新凭证，过期时间：" + new Date(sgSessionExpireAt(sess)).toLocaleString());
        return true;
    } catch (e) {
        log("【SG】从响应头申请凭证异常：" + e.message);
    }
    return false;
}

// 每次脚本运行只检查一次：无凭证 / 已过期 / 即将过期 → 自动申请
function sgEnsureCred() {
    sgInitCred();
    if (_sgCredChecked) return;
    _sgCredChecked = true;
    var exp = sgSessionExpireAt(_sgSession);
    if (!_sgSession || !exp || exp < new Date().getTime() + SG_CRED_RENEW_BEFORE) {
        log("【SG】无可用凭证或凭证已/即将过期，开始从响应头自动申请");
        sgRefreshCredFromHeaders();
    }
}

// 解析 studioglobal 的 SSE
//   事件：status / token / completed；completed 里带完整文本 content
function sgParseSse(sseText) {
    var lines = String(sseText).split("\n");
    var curEvent = "";
    var full = "";
    var joined = "";
    for (var i = 0; i < lines.length; i++) {
        var line = lines[i].replace(/\r$/, "");
        if (line.indexOf("event:") === 0) {
            curEvent = line.substring(6).replace(/^\s+|\s+$/g, "");
            continue;
        }
        if (line.indexOf("data:") === 0) {
            var payload = line.substring(5).replace(/^\s+/, "");
            if (!payload || payload === "[DONE]") continue;
            try {
                var o = JSON.parse(payload);
                if (curEvent === "token" && o.token) {
                    joined += o.token;
                } else if (curEvent === "completed" && o.content) {
                    full = String(o.content);
                }
            } catch (e) {}
        }
    }
    return full || joined;
}

// 调用 studioglobal，返回模型文本（失败返回 null）
function sgChat(userContent, systemPrompt, retryTimes) {
    if (retryTimes === undefined) retryTimes = 1;
    var text = systemPrompt ? (systemPrompt + "\n\n" + userContent) : userContent;
    for (var attempt = 0; attempt <= retryTimes; attempt++) {
        var sess = sgGetSession();
        var gid = sgGetGuestId();
        if (!sess) { log("【SG】无凭证且自动申请失败，跳过本次分析"); return null; }
        var bodyObj = {
            turnId: String(java.randomUUID()),
            aiModel: SG_MODEL,
            description: [{
                role: "user",
                content: [{ type: "text", text: text }],
                metadata: {
                    routeMode: "search", appMode: "chat", chatMode: "search",
                    modePreset: "fast", aiModel: SG_MODEL, chatUseCase: "general",
                    searchSources: [], searchEngine: "perplexity",
                    searchDateRange: "any", researchGoal: "answer",
                    primarySourcesOnly: false, includedDomains: [],
                    excludedDomains: [], searchRegion: "global",
                    searchLanguage: "auto", responseDetail: "auto",
                    reasoningLevel: "low", modeSwitchRouteMode: "search"
                }
            }],
            chatContext: "chat",
            inputTokens: text.length,
            outputLanguage: "Default",
            brandVoice: "",
            searchEngine: "perplexity",
            searchDateRange: "any",
            primarySourcesOnly: false,
            includedDomains: [],
            excludedDomains: [],
            searchRegion: "global",
            searchLanguage: "auto",
            reasoningLevel: "low"
        };
        var req = [JSON.stringify({
            url: SG_API_CHAT,
            method: "POST",
            body: JSON.stringify(bodyObj),
            headers: {
                "User-Agent": SG_UA,
                "Accept": "text/event-stream",
                "Content-Type": "application/json",
                "Origin": SG_BASE,
                "Referer": SG_BASE + "/zh-cn",
                "x-studio-guest-id": gid,
                "x-studio-guest-request": "1",
                "Cookie": "studio_anonymous_session=" + sess
            }
        })];
        var sse = java.ajaxRaceDelayed(req, 120000);
        if (!sse) {
            log("【SG】聊天无返回（第 " + (attempt + 1) + " 次）");
            continue;
        }
        var head = String(sse).substring(0, 300);
        if (head.indexOf("401") !== -1) {
            // ★ 凭证失效：先尝试从响应头自动申请新凭证，成功则用新凭证重试（不消耗重试次数）
            if (!_sgRefreshed && sgRefreshCredFromHeaders()) {
                _sgRefreshed = true;
                log("【SG】已获取新凭证，重试本次请求");
                attempt--;
                continue;
            }
            log("【SG】★ 凭证已失效（401），且自动申请失败。请确认 App 端已支持 POST 响应头侧存。");
            log("【SG】原始返回：" + head);
            return null;
        }
        var content = sgParseSse(sse);
        if (content) return content;
        log("【SG】解析失败，原始返回：" + String(sse).substring(0, 300));
    }
    return null;
}

function callAnalyzeApi(seqContent, aboveContext) {
    var userContent = "";
    if (aboveContext && aboveContext.trim() !== "") userContent += "【上文历史内容】\n" + aboveContext + "\n";
    userContent += "【当前待分析对话内容】\n" + seqContent;
    // 内置免登录智谱（游客模式）：无需任何密钥配置
    var content = sgChat(userContent, buildAnalyzePrompt());
    if (!content) return null;
    var parsed;
    try {
        parsed = extractJsonTolerant(content);
    } catch (e) {
        try { parsed = extractJsonTolerant(stripReasonField(content)); }
        catch (e2) {
            // 兜底：智谱「首条抽风」时整段必然解析失败，改逐条宽松抽取，能救回多少是多少
            parsed = extractNameItemsLoose(content);
            if (Object.keys(parsed).length === 0) {
                log("【姓名分析-日志】JSON解析失败且逐条抽取无结果，AI返回内容：\n" + apiLogTruncate(content));
                return null;
            }
            log("【姓名分析-日志】整段JSON解析失败，已启用逐条抽取救回 " + Object.keys(parsed).length + " 条，AI返回内容：\n" + apiLogTruncate(content));
        }
    }
    var keys = Object.keys(parsed);
    if (keys.length === 0) {
        log("【姓名分析-日志】返回格式错误：空结果，AI返回内容：\n" + apiLogTruncate(content));
        return null;
    }
    var normalized = {};
    for (var ni = 0; ni < keys.length; ni++) {
        var nKey = keys[ni];
        if (!/^\d{1,2}$/.test(nKey)) {
            log("【姓名分析-日志】序号" + nKey + "不符合规范，AI返回内容：\n" + apiLogTruncate(content));
            return null;
        }
        var nItem = parsed[nKey];
        if (!nItem || typeof nItem.name !== 'string' || nItem.name.trim() === ''
            || typeof nItem.gender !== 'string' || nItem.gender.trim() === ''
            || typeof nItem.age !== 'string' || nItem.age.trim() === '') {
            log("【姓名分析-日志】序号" + nKey + "字段无效（name/gender/age 必须为非空字符串），AI返回内容：\n" + apiLogTruncate(content));
            return null;
        }
        normalized[padZero(parseInt(nKey, 10), 2)] = nItem;
    }
    return normalized;
}

// ===================== 标注与别名 =====================
function genMarkText(name, voiceDisplay) { return "<<" + voiceDisplay + ">>"; }

function annotateText(paragraph, dialogs, charResults) {
  var result = paragraph;
  // 从后往前处理，避免索引错乱
  for (var i = dialogs.length - 1; i >= 0; i--) {
      var seq = padZero(i + 1, 2);
      var info = charResults[seq];
      if (!info) continue;
      var d = dialogs[i];

      if (info.name === "旁白") {
          // 旁白：去掉整个引号对（左右引号），只保留文字内容
          result = result.substring(0, d.index) +
                   d.content +
                   result.substring(d.index + d.length);
      } else {
          // 普通角色：在左引号后插入发音人标记
          var mark = genMarkText(info.name, info.voiceDisplay);
          result = result.substring(0, d.index) +
                   result.substring(d.index, d.index + 1) +
                   mark +
                   result.substring(d.index + 1);
      }
  }
  return result;
}


function updateCharacterRecords(dialogList, chapterContext) {
    var RECORDS_FILE = EXT_DIR + "characterRecords.json";
    var records = getLatestCharacterRecords();
    var mainNameSet = {}, aliasToMainMap = {};
    for (var i = 0; i < records.length; i++) {
        var rec = records[i];
        if (!rec || !rec.name) continue;
        var mainName = rec.name.trim();
        mainNameSet[mainName] = rec;
        if (rec.aliases && rec.aliases.trim()) {
            var aliasArr = rec.aliases.split("|");
            for (var j = 0; j < aliasArr.length; j++) {
                var alias = String(aliasArr[j]).trim();
                if (alias && alias !== mainName) aliasToMainMap[alias] = mainName;
            }
        }
    }
    var unknownNames = [], nameToInfo = {}, seen = {};
    for (var i = 0; i < dialogList.length; i++) {
        var item = dialogList[i];
        if (!item || !item.name) continue;
        var name = item.name.trim();
        if (name === "未知" || seen[name]) continue;
        seen[name] = true;
        if (mainNameSet[name] || aliasToMainMap[name]) continue;
        unknownNames.push(name);
        nameToInfo[name] = { gender: item.gender, age: item.age };
    }
    if (unknownNames.length === 0) return;
    var nameSeqText = "";
    for (var i = 0; i < unknownNames.length; i++) nameSeqText += "【" + padZero(i + 1, 2) + "】" + unknownNames[i] + "\n";
    var existingRolesList = [];
    for (var mn in mainNameSet) {
        if (mainNameSet.hasOwnProperty(mn)) {
            var r = mainNameSet[mn];
            var roleObj = { name: mn };
            if (r.aliases && r.aliases.trim()) roleObj.aliases = r.aliases;
            existingRolesList.push(roleObj);
        }
    }
    var ctxText = chapterContext ? String(chapterContext).slice(0, 3000) : "";
    var aliasPrompt = "你是专业的小说人物别名识别AI。请判断【待确认名字列表】中每个名字是否为【已有角色列表】中某个角色的别名（字号、绰号、昵称、不同称呼等）。\n\n" +
        "【核心原则】\n1. 别名必须是同一人物的不同称呼\n2. 不要仅凭字面相似度推断，需要合理关联\n3. 不确定的默认判定为非别名\n4. 全新角色（从未出现过）判定为非别名\n\n" +
        (ctxText ? "【小说上下文片段】\n" + ctxText + "\n\n" : "") +
        "【已有角色列表】\n" + JSON.stringify(existingRolesList) + "\n\n" +
        "【待确认名字列表】\n" + nameSeqText + "\n\n" +
        "【输出要求】\n输出严格JSON格式，键为序号（如\"01\"），值为 {isAlias: true/false, mainName: \"匹配的主名或null\"}。\n必须覆盖所有序号。";
    var aliasResult = null;
    // 内置免登录智谱（游客模式）：无需任何密钥配置
    var aliasContent = sgChat(aliasPrompt, "严格遵守格式要求，仅输出JSON");
    if (!aliasContent) return;
    var aliasParsed;
    try {
        aliasParsed = extractJsonTolerant(aliasContent);
    } catch (e) {
        try { aliasParsed = extractJsonTolerant(stripReasonField(aliasContent)); }
        catch (e2) {
            log("【别名校验-日志】JSON解析失败，AI返回内容：\n" + apiLogTruncate(aliasContent));
            return;
        }
    }
    var aliasKeys = Object.keys(aliasParsed);
    if (aliasKeys.length === 0) {
        log("【别名校验-日志】返回格式错误：空结果，AI返回内容：\n" + apiLogTruncate(aliasContent));
        return;
    }
    var aliasNormalized = {};
    for (var ai = 0; ai < aliasKeys.length; ai++) {
        var aKey = aliasKeys[ai];
        if (!/^\d{1,2}$/.test(aKey)) {
            log("【别名校验-日志】序号" + aKey + "不符合规范，AI返回内容：\n" + apiLogTruncate(aliasContent));
            return;
        }
        var aItem = aliasParsed[aKey];
        if (!aItem || typeof aItem.isAlias !== "boolean") {
            log("【别名校验-日志】序号" + aKey + "字段无效（isAlias 必须为布尔值），AI返回内容：\n" + apiLogTruncate(aliasContent));
            return;
        }
        aliasNormalized[padZero(parseInt(aKey, 10), 2)] = aItem;
    }
    aliasResult = aliasNormalized;
    if (!aliasResult) return;
    for (var i = 0; i < unknownNames.length; i++) {
        var seq = padZero(i + 1, 2), name = unknownNames[i], res = aliasResult[seq];
        if (!res) continue;
        if (res.isAlias && res.mainName) {
            var targetMain = mainNameSet[res.mainName] || mainNameSet[aliasToMainMap[res.mainName]];
            // ★ 性别校验：AI 识别出的别名性别与主名性别不一致 → 不合并，按新角色处理
            //（别名识别请求未携带性别信息，AI 偶尔会把男性角色错归为女性角色的别名）
            if (targetMain) {
                var aliasGender = String((nameToInfo[name] || {}).gender || "").trim();
                var mainGender = String(targetMain.gender || "").trim();
                if (aliasGender && mainGender && aliasGender.charAt(0) !== mainGender.charAt(0)) {
                    log("【别名校验】" + name + " 性别(" + aliasGender + ")与主名 " + res.mainName + " 性别(" + mainGender + ")不符，按新角色处理");
                    var infoNG = nameToInfo[name] || { gender: "男", age: "青年" };
                    records.unshift({ name: name, aliases: name, gender: infoNG.gender, age: infoNG.age, voice: "" });
                    continue;
                }
                var aliases = [];
                if (targetMain.aliases) {
                    var aliasParts = targetMain.aliases.split("|");
                    for (var ai = 0; ai < aliasParts.length; ai++) aliases.push(String(aliasParts[ai]).trim());
                }
                if (aliases.indexOf(name) === -1) aliases.push(name);
                targetMain.aliases = aliases.join("|");
            } else {
                var info = nameToInfo[name] || { gender: "男", age: "青年" };
                records.unshift({ name: name, aliases: name, gender: info.gender, age: info.age, voice: "" });
            }
        } else {
            var info = nameToInfo[name] || { gender: "男", age: "青年" };
            records.unshift({ name: name, aliases: name, gender: info.gender, age: info.age, voice: "" });
        }
    }
    try { java.writeExternalFile(RECORDS_FILE, JSON.stringify(records, null, 2)); } catch (e) {}
}

// ===================== 主执行逻辑 =====================
(function() {
    var originalText = text;


    originalText = originalText.replace(/(“[^“”\n]*)(“)/g, "$1”$2");

// ★ 只拦截“有右引号但无左引号”的特殊段落，其他情况（包括有左无右）交给后续正常流程
    if (originalText.indexOf("”") !== -1 && originalText.indexOf("“") === -1) {
    var specialResult = handleSpecialQuoteCases(originalText);
    if (specialResult) return specialResult;
    }

    if (originalText && originalText.indexOf("“") > -1) handleBookSwitch();
    var dialogs = extractDialogs(originalText);

    // ★ 如果没有找到任何对话，尝试用多行缓存匹配
    if (dialogs.length === 0) {
        var noQuoteResult = handleNoQuoteText(originalText);
        if (noQuoteResult) return noQuoteResult;
        // 实在匹配不到，保留原样
        saveCurrentToHistory(originalText);
        return text;
    }

    var bookName = getBookNameSafely();
    var progress = readProgress();
    var useProgress = progress && progress.bookName === bookName && !IS_BOOK_SWITCHED;

    var finalCharResults = {};
    var chapterTitle = null;
    var seqList = [];
    var location = null;

    // 尝试用进度指针顺序匹配
    if (useProgress) {
        chapterTitle = progress.chapterTitle;
        var lastSeq = progress.lastSeq;
        var allCachedByProgress = true;
        for (var i = 0; i < dialogs.length; i++) {
            var predictedSeq = lastSeq + 1 + i;
            var cleanCur = cleanDialogText(dialogs[i].content);
            var cachedItem = matchInChapterCacheBySeq(chapterTitle, predictedSeq, cleanCur);
            if (cachedItem) {
                finalCharResults[padZero(i + 1, 2)] = cachedItem;
            } else {
                allCachedByProgress = false;
                break;
            }
        }
        if (!allCachedByProgress) {
            log("【进度】浮动匹配失败，回退全文定位");
            location = locateParagraphInFullText(originalText);
            if (location) {
                chapterTitle = location.chapterTitle;
                seqList = location.seqList;
                finalCharResults = {};
                var allCachedAfterLocate = true;
                for (var i = 0; i < dialogs.length; i++) {
                    var realSeq = seqList[i];
                    var cleanCur = cleanDialogText(dialogs[i].content);
                    var cachedItem = matchInChapterCacheBySeq(chapterTitle, realSeq, cleanCur);
                    if (cachedItem) {
                        finalCharResults[padZero(i + 1, 2)] = cachedItem;
                    } else {
                        allCachedAfterLocate = false;
                        break;
                    }
                }
                if (!allCachedAfterLocate) {
                    location.needAI = true;
                }
                writeProgress(bookName, chapterTitle, seqList[seqList.length - 1]);
            } else {
                for (var i = 0; i < dialogs.length; i++) {
                    var vn = getTargetVoiceNum("男男青年", null, []);
                    finalCharResults[padZero(i + 1, 2)] = { name: "群众", voiceDisplay: extractVoiceDisplay(vn), genderAge: "男男青年", voiceNum: vn };
                }
                var annotated = annotateText(originalText, dialogs, finalCharResults);
                saveCurrentToHistory(originalText);
                return annotated;
            }
        } else {
            writeProgress(bookName, chapterTitle, lastSeq + dialogs.length);
        }
    }

    if (!useProgress || (location && location.needAI) || (!location && !useProgress)) {
        if (!location) {
            location = locateParagraphInFullText(originalText);
        }
        if (location) {
            chapterTitle = location.chapterTitle;
            seqList = location.seqList;
            writeProgress(bookName, chapterTitle, seqList[seqList.length - 1]);

            if (!finalCharResults || Object.keys(finalCharResults).length === 0) {
                for (var i = 0; i < dialogs.length; i++) {
                    var realSeq = seqList[i];
                    var cleanCur = cleanDialogText(dialogs[i].content);
                    var cachedItem = matchInChapterCacheBySeq(chapterTitle, realSeq, cleanCur);
                    if (cachedItem) {
                        finalCharResults[padZero(i + 1, 2)] = cachedItem;
                    }
                }
            }

            var needAI = false;
            for (var i = 0; i < dialogs.length; i++) {
                if (!finalCharResults[padZero(i + 1, 2)]) {
                    needAI = true;
                    break;
                }
            }

            if (needAI) {
                log("【缓存】仍缺失，启动AI分析（限制长度）");
                var aboveContext = getAboveContext();
                var belowContent = getBelowContent(originalText);
                var seqContent = generateBatchSeqContent(originalText, dialogs, belowContent);
                var analyzeResult = callAnalyzeApi(seqContent, aboveContext);

                if (analyzeResult) {
                    var allPairs = [];
                    var seqReg = /【(\d{1,4})】“([^”]*)”/g;
                    var m;
                    while ((m = seqReg.exec(seqContent)) !== null) {
                        allPairs.push({ localSeq: m[1], text: m[2] });
                    }
                    if (allPairs.length === 0) {
                        for (var i = 0; i < dialogs.length; i++) {
                            allPairs.push({ localSeq: padZero(i + 1, 2), text: dialogs[i].content });
                        }
                    }

                    var chapterResultsToMerge = {};
                    var allNamesForAlias = [];
                    var currentCount = dialogs.length;
                    var lastCurrentRealSeq = seqList[seqList.length - 1];
                    var nextRealSeq = lastCurrentRealSeq + 1;

                    for (var p = 0; p < allPairs.length; p++) {
                        var pair = allPairs[p];
                        var aiInfo = analyzeResult[pair.localSeq];
                        if (!aiInfo) continue;
                        var isCurrent = p < currentCount;
                        var realSeq;
                        if (isCurrent) {
                            realSeq = seqList[p];
                        } else {
                            realSeq = nextRealSeq + (p - currentCount);
                            if (location.totalDialogs && realSeq > location.totalDialogs) continue;
                        }
                        chapterResultsToMerge[String(realSeq)] = {
                            name: aiInfo.name,
                            gender: aiInfo.gender,
                            age: aiInfo.age,
                            dialogText: pair.text
                        };
                        allNamesForAlias.push({ name: aiInfo.name, gender: aiInfo.gender, age: aiInfo.age });
                    }
                    if (Object.keys(chapterResultsToMerge).length > 0) {
                        mergeChapterResults(chapterTitle, chapterResultsToMerge);
                    }
                    if (allNamesForAlias.length > 0) {
                        updateCharacterRecords(allNamesForAlias, seqContent);
                    }

                    var tempAssignedVoices = {};
                    for (var i = 0; i < dialogs.length; i++) {
                        var localSeq = padZero(i + 1, 2);
                        var aiInfo = analyzeResult[localSeq];
                        var charName = aiInfo ? aiInfo.name : "未知";
                        var finalGender = aiInfo ? aiInfo.gender : "男";
                        var finalAge = aiInfo ? aiInfo.age : "青年";

                        var latestRecords = readBookCharacters();
                        var resRecord = resolveNameToRecord(charName, latestRecords);
                        var finalName = resRecord ? resRecord.mainName : charName;
                        if (resRecord) {
                            if (resRecord.gender && (resRecord.gender === "男" || resRecord.gender === "女" || resRecord.gender === "特殊")) finalGender = resRecord.gender;
                            if (resRecord.age) finalAge = resRecord.age;
                        }
                        var gaKey = finalGender + "-" + finalAge;
                        var genderAge = genderAgeMap[gaKey] || ((finalGender === "男") ? "男男青年" : "女女青年");
                        var voiceNum;
                        if (resRecord && resRecord.effectiveVoice) {
                            voiceNum = resRecord.effectiveVoice;
                        } else {
                            var extraUsed = tempAssignedVoices[genderAge] || [];
                            voiceNum = getTargetVoiceNum(genderAge, null, extraUsed);
                            if (!tempAssignedVoices[genderAge]) tempAssignedVoices[genderAge] = [];
                            if (tempAssignedVoices[genderAge].indexOf(voiceNum) === -1) tempAssignedVoices[genderAge].push(voiceNum);
                        }
                        finalCharResults[localSeq] = {
                            name: finalName,
                            voiceDisplay: extractVoiceDisplay(voiceNum),
                            genderAge: genderAge,
                            voiceNum: voiceNum
                        };
                        if (finalName.indexOf("群众") === -1 || saveMassCharacter === 1) {
                            saveCharacter(finalName, genderAge, voiceNum, "");
                        }
                    }
                } else {
                    for (var i = 0; i < dialogs.length; i++) {
                        var vn = getTargetVoiceNum("男男青年", null, []);
                        finalCharResults[padZero(i + 1, 2)] = { name: "群众", voiceDisplay: extractVoiceDisplay(vn), genderAge: "男男青年", voiceNum: vn };
                    }
                }
            } else {
                for (var i = 0; i < dialogs.length; i++) {
                    var localSeq = padZero(i + 1, 2);
                    var cached = finalCharResults[localSeq];
                    if (!cached) continue;
                    var finalName = cached.name || "未知";
                    var finalGender = cached.gender || "男";
                    var finalAge = cached.age || "青年";
                    var latestRecords = readBookCharacters();
                    var resRecord = resolveNameToRecord(finalName, latestRecords);
                    if (resRecord) {
                        finalName = resRecord.mainName;
                        if (resRecord.gender && (resRecord.gender === "男" || resRecord.gender === "女" || resRecord.gender === "特殊")) finalGender = resRecord.gender;
                        if (resRecord.age) finalAge = resRecord.age;
                    }
                    var gaKey = finalGender + "-" + finalAge;
                    var genderAge = genderAgeMap[gaKey] || ((finalGender === "男") ? "男男青年" : "女女青年");
                    var voiceNum;
                    if (resRecord && resRecord.effectiveVoice) {
                        voiceNum = resRecord.effectiveVoice;
                    } else {
                        voiceNum = getTargetVoiceNum(genderAge, null, []);
                    }
                    finalCharResults[localSeq] = {
                        name: finalName,
                        voiceDisplay: extractVoiceDisplay(voiceNum),
                        genderAge: genderAge,
                        voiceNum: voiceNum
                    };
                    saveCharacter(finalName, genderAge, voiceNum, "");
                }
            }
        } else {
            for (var i = 0; i < dialogs.length; i++) {
                var vn = getTargetVoiceNum("男男青年", null, []);
                finalCharResults[padZero(i + 1, 2)] = { name: "群众", voiceDisplay: extractVoiceDisplay(vn), genderAge: "男男青年", voiceNum: vn };
            }
        }
    } else if (useProgress && finalCharResults && Object.keys(finalCharResults).length === dialogs.length) {
        for (var i = 0; i < dialogs.length; i++) {
            var localSeq = padZero(i + 1, 2);
            var cached = finalCharResults[localSeq];
            if (!cached) continue;
            var finalName = cached.name || "未知";
            var finalGender = cached.gender || "男";
            var finalAge = cached.age || "青年";
            var latestRecords = readBookCharacters();
            var resRecord = resolveNameToRecord(finalName, latestRecords);
            if (resRecord) {
                finalName = resRecord.mainName;
                if (resRecord.gender && (resRecord.gender === "男" || resRecord.gender === "女" || resRecord.gender === "特殊")) finalGender = resRecord.gender;
                if (resRecord.age) finalAge = resRecord.age;
            }
            var gaKey = finalGender + "-" + finalAge;
            var genderAge = genderAgeMap[gaKey] || ((finalGender === "男") ? "男男青年" : "女女青年");
            var voiceNum;
            if (resRecord && resRecord.effectiveVoice) {
                voiceNum = resRecord.effectiveVoice;
            } else {
                voiceNum = getTargetVoiceNum(genderAge, null, []);
            }
            finalCharResults[localSeq] = {
                name: finalName,
                voiceDisplay: extractVoiceDisplay(voiceNum),
                genderAge: genderAge,
                voiceNum: voiceNum
            };
            saveCharacter(finalName, genderAge, voiceNum, "");
        }
    }

    var annotatedText = annotateText(originalText, dialogs, finalCharResults);
    annotatedText = annotatedText.replace(/“(<<[^<>]+>>)?([\u4E00-\u9FFF]{1,15})”/g, "$2");




    saveCurrentToHistory(originalText);
    IS_BOOK_SWITCHED = false;
    return annotatedText;
})();