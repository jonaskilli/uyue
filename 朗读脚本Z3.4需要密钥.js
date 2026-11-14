// ===================== 朗读脚本 Z3.4：进度指针 + 章节缓存 + 无引号多行匹配 + 严格标注 =====================
var EXT_DIR = "/storage/emulated/0/Download/chajian/mingwuyan/";
var CACHE_ROOT = "/storage/emulated/0/Download/chajian/xiaoshuo/";
var KEY_FILE = EXT_DIR + "miyue.txt";
var DATA_FILE = EXT_DIR + "data.json";
var BOOK_LIST_FILE = EXT_DIR + "liebiao.json";
var PROGRESS_FILE = CACHE_ROOT + "reading_progress.json";
// 历史段落文件：联合定位的锚点来源。上文展示已改为从 data.json 实时截取
// （见 getAboveContext），但「上一段 + 当前段」定位仍需要它，故恢复保存；
// 切书时随进度指针一起清空（见 handleBookSwitch），不会串书。
var HISTORY_FILE = EXT_DIR + "paragraphHistory.json";
var MAX_HISTORY = 20;

var NAME_ANALYZE_TIMEOUT = 25000;
var RACE_DELAY = 25000;
var AI_TIMEOUT = 25000;
var MAX_RETRY = 5;
var EXTRACT_LENGTH = 1000;
var MAX_CHARACTER = 800;
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
  ['女/女青年', '女', '女青年', '女青年', 999],
  ['男/男青年', '男', '男青年', '男青年', 999],
  ['女/女中年', '女', '女中年', '女中年', 999],
  ['男/男中年', '男', '男中年', '男中年', 999],
  ['女/女老年', '女', '女老年', '女老年', 999],
  ['男/男老年', '男', '男老年', '男老年', 999],
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
// 外部提供 fayinren.json，内容为发音人标签数组，例如 ["少女01","男青年986", ...]
// 规则：
// 1. 读取成功且能提取出可用发音人 → 完全使用 fayinren.json 里的标签，不再和内部表取交集；
// 2. 读取失败、格式错误、空数组、或完全提取不到可分组发音人 → 使用内部函数生成的发音人池；
// 3. 标签末尾数字允许 1~4 位，例如 男青年986。
var ALL_VOICE_TAGS = [];
var VOICE_TAG_TO_KEY = {};
var genderAgeToVoices = {};
var VOICE_TAG_TO_GA_EXACT = {};
var VOICE_PREFIX_TO_GA = {};

function compareVoiceTags(a, b) {
    return String(a).localeCompare(String(b), 'zh-CN', { numeric: true });
}
function registerVoicePrefixes() {
    for (var key in GENSHIN_CHARACTERS) {
        if (!GENSHIN_CHARACTERS.hasOwnProperty(key)) continue;
        var c = GENSHIN_CHARACTERS[key];
        var voice = String(c.voice || "").trim();
        if (!voice) continue;
        var ga = String(c.gender || "") + String(c.age || "");
        VOICE_TAG_TO_GA_EXACT[voice] = ga;
        var m = /^(.*?)(\d{1,4})$/.exec(voice);
        if (m && m[1]) VOICE_PREFIX_TO_GA[m[1]] = ga;
    }
}
function genderAgeFromVoiceTag(tag) {
    var s = String(tag || "").trim();
    if (!s) return "";
    if (VOICE_TAG_TO_GA_EXACT[s]) return VOICE_TAG_TO_GA_EXACT[s];
    var m = /^(.*?)(\d{1,4})$/.exec(s);
    var prefix = m ? m[1] : s;
    if (VOICE_PREFIX_TO_GA[prefix]) return VOICE_PREFIX_TO_GA[prefix];
    if (prefix.indexOf("男青年") >= 0) return "男男青年";
    if (prefix.indexOf("女青年") >= 0) return "女女青年";
    if (prefix.indexOf("男中年") >= 0) return "男男中年";
    if (prefix.indexOf("女中年") >= 0) return "女女中年";
    if (prefix.indexOf("男老年") >= 0) return "男男老年";
    if (prefix.indexOf("女老年") >= 0) return "女女老年";
    if (prefix.indexOf("少年") >= 0) return "男少年";
    if (prefix.indexOf("少女") >= 0) return "女少女";
    if (prefix.indexOf("男童") >= 0) return "男男童";
    if (prefix.indexOf("女童") >= 0) return "女女童";
    return "";
}
function addVoiceTagToPool(tag, ga) {
    tag = String(tag || "").trim();
    if (!tag || !ga) return;
    if (!genderAgeToVoices[ga]) genderAgeToVoices[ga] = [];
    genderAgeToVoices[ga].push(tag);
    ALL_VOICE_TAGS.push(tag);
    VOICE_TAG_TO_KEY[tag] = tag;
}
function buildInternalVoicePool() {
    ALL_VOICE_TAGS = [];
    VOICE_TAG_TO_KEY = {};
    genderAgeToVoices = {};
    for (var key in GENSHIN_CHARACTERS) {
        if (!GENSHIN_CHARACTERS.hasOwnProperty(key)) continue;
        var c = GENSHIN_CHARACTERS[key];
        addVoiceTagToPool(c.voice, String(c.gender || "") + String(c.age || ""));
    }
}
function readFayinrenTags() {
    try {
        var raw = java.readExternalFile(EXT_DIR + "fayinren.json");
        if (!raw || String(raw).trim() === "") return [];
        var arr = JSON.parse(String(raw));
        if (!isArray(arr)) return [];
        var out = [];
        for (var i = 0; i < arr.length; i++) {
            var tag = String(arr[i] || "").trim();
            if (tag) out.push(tag);
        }
        return uniqueArr(out);
    } catch (e) {
        return [];
    }
}
function buildFayinrenVoicePool() {
    ALL_VOICE_TAGS = [];
    VOICE_TAG_TO_KEY = {};
    genderAgeToVoices = {};
    var tags = readFayinrenTags();
    if (tags.length === 0) return false;
    for (var i = 0; i < tags.length; i++) {
        var tag = tags[i];
        var ga = genderAgeFromVoiceTag(tag);
        if (!ga) continue;
        addVoiceTagToPool(tag, ga);
    }
    return ALL_VOICE_TAGS.length > 0;
}
registerVoicePrefixes();
if (!buildFayinrenVoicePool()) {
    buildInternalVoicePool();
}
for (var gaKey in genderAgeToVoices) {
    if (genderAgeToVoices.hasOwnProperty(gaKey)) {
        genderAgeToVoices[gaKey] = uniqueArr(genderAgeToVoices[gaKey]).sort(compareVoiceTags);
    }
}
ALL_VOICE_TAGS = uniqueArr(ALL_VOICE_TAGS).sort(compareVoiceTags);

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
    java.writeExternalFile(PROGRESS_FILE, "{}");
    // 定位锚点历史随切书一起清空：避免用上一本书的段落把新书定位带偏
    java.writeExternalFile(HISTORY_FILE, "[]");
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
// 【上文来源改造】原先上文来自 paragraphHistory.json（每读一段就落盘追加），
// 该历史无条件保存，切书后残留的仍是上一本的内容，会把上一本书的角色带进新书，
// 干扰姓名识别。改为与下文同一套来源：直接从 data.json 的当前章节全文里截取。
// 好处：上文永远属于当前这一本书、当前这一章，天然不会串书，且无需落盘。
/**
 * 用 ttsMeta 的「章节标题 + 段落号 + 当前段文本」在 data.json 里定位当前段。
 * 供 getAboveContext / getBelowContentInfo 取上下文用：AI 识别时的上文、下文、
 * startPos 必须和 seqList 出自同一个位置，不能退回全文 indexOf（重复段落会取错章）。
 * 旧版本没有 ttsMeta、或定位/文本比对失败时返回 null，由调用方走原来的兜底链。
 */
function locateParagraphByMeta(paragraph) {
    var meta = getTtsMeta();
    if (!meta) return null;
    var chapterTitle = String(meta.chapterTitle || "").trim();
    if (!chapterTitle) return null;
    var pIdx = parseInt(meta.paragraphIndex, 10);
    if (isNaN(pIdx) || pIdx < 0) return null;
    var metaText = String(meta.paragraphText || "");
    if (metaText && cleanDialogText(metaText) !== cleanDialogText(paragraph)) return null;
    try {
        var dataStr = String(java.readExternalFile(DATA_FILE));
        if (!dataStr || dataStr.trim() === "") return null;
        var fullText = String(JSON.parse(dataStr).texts || "");
        if (!fullText) return null;
        var lines = fullText.split(/\r?\n/);
        var titleLine = -1;
        for (var li = 0; li < lines.length; li++) {
            if (String(lines[li]).trim() === chapterTitle) { titleLine = li; break; }
        }
        if (titleLine < 0) return null;
        // 逐行在原串里推进，拿到真实字符偏移（不受 \r\n、段首缩进长度影响）
        var cursor = 0;
        for (var lj = 0; lj <= titleLine; lj++) {
            var ltext = String(lines[lj]);
            var at0 = fullText.indexOf(ltext, cursor);
            if (at0 < 0) return null;
            cursor = at0 + ltext.length;
        }
        var body = [];
        for (var bi = titleLine + 1; bi < lines.length; bi++) {
            var ln = String(lines[bi]);
            if (ln.trim() === "") continue;
            if (ln.charAt(0) !== "　" && ln.charAt(0) !== " ") break;
            var at = fullText.indexOf(ln, cursor);
            if (at < 0) return null;
            body.push({ text: ln, pos: at, end: at + ln.length });
            cursor = at + ln.length;
        }
        var item = body[pIdx];
        if (!item) return null;
        if (cleanDialogText(item.text) !== cleanDialogText(paragraph)) return null;
        return {
            startPos: item.pos,
            endPos: item.end,
            chapterTitle: chapterTitle,
            fullText: fullText,
            chapters: getChaptersFromFullText(fullText)
        };
    } catch (e) {
        return null;
    }
}

function getAboveContext(currentParagraph) {
    try {
        var dataStr = String(java.readExternalFile(DATA_FILE));
        if (!dataStr || dataStr.trim() === "") return "";
        var data = JSON.parse(dataStr);
        if (!data || !data.texts) return "";
        var fullChapter = String(data.texts);
        // 优先用「章节标题 + 段落号 + 当前段文本」定位，和 seqList 同源；
        // 旧版本拿不到 meta 或定位失败，再退回原来的单段 indexOf / 前 50 字。
        var located = locateParagraphByMeta(currentParagraph);
        var targetPos = located ? located.startPos : -1;
        if (targetPos === -1) {
            targetPos = fullChapter.indexOf(currentParagraph);
            if (targetPos === -1) {
                var shortPara = currentParagraph.substring(0, Math.min(currentParagraph.length, 50));
                targetPos = fullChapter.indexOf(shortPara);
                if (targetPos === -1) return "";
            }
        }
        if (targetPos <= 0) return "";
        var startExtractPos = Math.max(0, targetPos - EXTRACT_LENGTH);
        var aboveText = fullChapter.substring(startExtractPos, targetPos);
        return annotateAboveContext(aboveText, startExtractPos, located);
    } catch (e) { return ""; }
}

/**
 * 给上文 1000 字里每句已分析过的对话，在左引号前补上「[之前识别的说话人]」。
 * 名字直接取章节缓存里当时保存的原样结果，不归一化、不附加其它信息。
 * 定位口径与 buildCrossChapterResults 一致：绝对位置 → 所属章 → 章内对话序号。
 */
function annotateAboveContext(aboveText, startPos, located) {
    if (!aboveText || !located || !located.chapters || located.chapters.length === 0) return aboveText;
    try {
        var dialogs = scanBelowDialogs(aboveText);
        var cacheByTitle = {};
        var inserts = [];
        for (var i = 0; i < dialogs.length; i++) {
            var d = dialogs[i];
            var absPos = startPos + d.sourceOffset;
            var chapter = null;
            for (var ci = 0; ci < located.chapters.length; ci++) {
                var ch = located.chapters[ci];
                if (absPos >= ch.bodyStart && absPos < ch.end) { chapter = ch; break; }
            }
            if (!chapter) continue;
            var offset = Math.max(0, Math.min(absPos - chapter.bodyStart, chapter.bodyText.length));
            var realSeq = (chapter.bodyText.substring(0, offset).match(/“/g) || []).length + 1;
            var cache = cacheByTitle[chapter.title];
            if (!cache) { cache = readChapterCache(chapter.title); cacheByTitle[chapter.title] = cache; }
            var item = cache.results ? cache.results[String(realSeq)] : null;
            if (!item || !item.name) continue;
            if (item.dialogText && cleanDialogText(item.dialogText) !== cleanDialogText(d.text)) continue;
            inserts.push({ pos: d.sourceOffset, text: "[" + String(item.name) + "]" });
        }
        if (inserts.length === 0) return aboveText;
        inserts.sort(function (a, b) { return b.pos - a.pos; });
        var out = aboveText;
        for (var k = 0; k < inserts.length; k++) {
            out = out.substring(0, inserts[k].pos) + inserts[k].text + out.substring(inserts[k].pos);
        }
        return out;
    } catch (e) { return aboveText; }
}
// ===================== 朗读历史（定位锚点）=====================
// 【定位准确性】单段 indexOf 在「几人说着同样的话」（多段对话文本完全相同）
// 或段落被轻微改写时会找错位置，导致章节判断与 beforeCount 全错。
// 用「上一段 + 当前段」联合定位可唯一确定位置；上一段即取自这里。
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
    try {
        var historyArr = readParagraphHistory();
        historyArr.push(para);
        if (historyArr.length > MAX_HISTORY) historyArr.shift();
        java.writeExternalFile(HISTORY_FILE, JSON.stringify(historyArr));
    } catch (e) {}
}
/** 上一段（联合定位锚点）：历史里的最后一条；没有则返回空串 */
function getPrevParagraph() {
    var h = readParagraphHistory();
    return h.length === 0 ? "" : String(h[h.length - 1]);
}
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
function isChapterTitleLine(line) {
    var s = String(line || "");
    return /^\s*第\s*[0-9一二三四五六七八九十百千零〇两]+\s*章/.test(s);
}
function getChaptersFromFullText(fullText) {
    var lines = String(fullText || "").split(/\r?\n/);
    var chapters = [];
    var currentTitle = null;
    var currentBody = "";
    var chapterStart = 0;
    var bodyStart = 0;
    var pos = 0;
    function pushChapter(endPos) {
        if (!currentTitle) return;
        chapters.push({
            title: currentTitle,
            start: chapterStart,
            end: endPos,
            bodyStart: bodyStart,
            bodyText: currentBody,
            totalDialogs: (currentBody.match(/“/g) || []).length
        });
    }
    for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        var lineStart = pos;
        pos += line.length;
        if (i < lines.length - 1) pos += 1;
        if (line.trim() === "") continue;
        var startsWithIndent = (line.charAt(0) === "　" || line.charAt(0) === " " || line.charAt(0) === "\t");
        var isTitle = isChapterTitleLine(line) || (!startsWithIndent && currentTitle !== null && line.length < 120);
        if (!startsWithIndent && !currentTitle) {
            currentTitle = line.trim();
            currentBody = "";
            chapterStart = lineStart;
            bodyStart = pos;
        } else if (isTitle) {
            pushChapter(lineStart);
            currentTitle = line.trim();
            currentBody = "";
            chapterStart = lineStart;
            bodyStart = pos;
        } else {
            currentBody += line + "\n";
        }
    }
    pushChapter(pos);
    return chapters;
}
function getBelowContentInfo(currentParagraph) {
    var empty = { text: "", startPos: -1, fullText: "", chapters: [] };
    try {
        var dataStr = String(java.readExternalFile(DATA_FILE));
        if (!dataStr || dataStr.trim() === "") return empty;
        var data = JSON.parse(dataStr);
        if (!data || !data.texts) return empty;
        var fullChapter = String(data.texts);
        // 优先用「章节标题 + 段落号 + 当前段文本」定位；取不到再退回
        // 上一段历史联合锚点 → 单段 indexOf → 前 50 字。
        var located = locateParagraphByMeta(currentParagraph);
        var targetPos = located ? located.startPos : -1;
        if (targetPos === -1) {
            var prevPara = getPrevParagraph();
            targetPos = locateByPrevAnchor(fullChapter, prevPara, currentParagraph);
            if (targetPos === -1) targetPos = fullChapter.indexOf(currentParagraph);
            if (targetPos === -1) {
                var shortPara = currentParagraph.substring(0, Math.min(currentParagraph.length, 50));
                targetPos = fullChapter.indexOf(shortPara);
                if (targetPos === -1) return empty;
            }
        }
        var startExtractPos = located ? located.endPos : (targetPos + currentParagraph.length);
        var remainLen = fullChapter.length - startExtractPos;
        if (remainLen <= 0) return empty;
        return {
            text: fullChapter.substring(startExtractPos, startExtractPos + Math.min(EXTRACT_LENGTH, remainLen)),
            startPos: startExtractPos,
            fullText: fullChapter,
            chapters: located ? located.chapters : getChaptersFromFullText(fullChapter)
        };
    } catch (e) {
        return empty;
    }
}
function getBelowContent(currentParagraph) {
    return getBelowContentInfo(currentParagraph).text;
}
function scanBelowDialogs(belowText) {
    var lines = String(belowText || "").split(/\r?\n/);
    var out = [];
    var lineStart = 0;
    for (var li = 0; li < lines.length; li++) {
        var line = lines[li];
        if (!isChapterTitleLine(line)) {
            var idx = 0;
            while (idx < line.length) {
                var left = line.indexOf("“", idx);
                if (left < 0) break;
                var right = line.indexOf("”", left + 1);
                var rawText = right >= 0 ? line.substring(left + 1, right) : line.substring(left + 1, Math.min(line.length, left + 1000));
                var text = String(rawText || "").replace(/^【[0-9]+】/, "");
                if (text && text.trim() !== "") {
                    out.push({ text: text, sourceOffset: lineStart + left });
                }
                idx = right >= 0 ? right + 1 : line.length;
            }
        }
        lineStart += line.length + 1;
    }
    return out;
}
function addChapterResult(groups, names, chapterTitle, realSeq, aiInfo, dialogText) {
    if (!chapterTitle || !realSeq || realSeq < 1 || !aiInfo) return;
    if (!groups[chapterTitle]) groups[chapterTitle] = {};
    groups[chapterTitle][String(realSeq)] = {
        name: aiInfo.name,
        gender: aiInfo.gender,
        age: aiInfo.age,
        dialogText: dialogText
    };
    names.push({ name: aiInfo.name, gender: aiInfo.gender, age: aiInfo.age });
}
function buildCrossChapterResults(currentChapterTitle, seqList, allPairs, analyzeResult, currentCount, belowInfo, locationTotalDialogs) {
    var groups = {};
    var names = [];
    var belowDialogs = scanBelowDialogs(belowInfo.text);
    var lastCurrentRealSeq = (seqList && seqList.length) ? seqList[seqList.length - 1] : 0;
    for (var p = 0; p < allPairs.length; p++) {
        var pair = allPairs[p];
        var aiInfo = analyzeResult[pair.localSeq];
        if (!aiInfo) continue;
        if (p < currentCount) {
            addChapterResult(groups, names, currentChapterTitle, seqList[p], aiInfo, pair.text);
            continue;
        }
        var belowIndex = p - currentCount;
        var bd = belowDialogs[belowIndex];
        if (!bd) continue;
        var absPos = belowInfo.startPos + bd.sourceOffset;
        var targetChapter = null;
        for (var ci = 0; ci < belowInfo.chapters.length; ci++) {
            var ch = belowInfo.chapters[ci];
            if (absPos >= ch.bodyStart && absPos < ch.end) {
                targetChapter = ch;
                break;
            }
        }
        if (targetChapter) {
            var offset = Math.max(0, Math.min(absPos - targetChapter.bodyStart, targetChapter.bodyText.length));
            var beforeText = targetChapter.bodyText.substring(0, offset);
            var realSeq = (beforeText.match(/“/g) || []).length + 1;
            if (targetChapter.totalDialogs && realSeq > targetChapter.totalDialogs) continue;
            addChapterResult(groups, names, targetChapter.title, realSeq, aiInfo, pair.text);
        } else {
            // 无法解析章节时退回旧口径，但仍不允许超过当前章范围污染当前章缓存。
            var fallbackSeq = lastCurrentRealSeq + 1 + belowIndex;
            if (locationTotalDialogs && fallbackSeq > locationTotalDialogs) continue;
            addChapterResult(groups, names, currentChapterTitle, fallbackSeq, aiInfo, pair.text);
        }
    }
    return { groups: groups, names: names };
}
function mergeChapterResultGroups(groups) {
    for (var title in groups) {
        if (!groups.hasOwnProperty(title)) continue;
        if (Object.keys(groups[title]).length > 0) {
            mergeChapterResults(title, groups[title]);
        }
    }
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
    var stopAddIndex = totalQuoteCount <= 5 ? totalQuoteCount : Math.max(Math.floor(totalQuoteCount * 0.8), 1);
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
    // ★ 只认「这个序号 + 当前这一句完全匹配」：相邻段落与历史进度都只是辅助定位，
    //   绝不允许拿邻近序号、或缓存里多行文本的某一行，来顶替当前句的角色。
    var cachedItem = results[String(predictedSeq)];
    if (!cachedItem) return null;
    var dialogText = cachedItem.dialogText;
    if (dialogText && cleanDialogText(dialogText) === cleanCurrent) return cachedItem;
    return null;
}

// ★ 全文定位（浮动失败后使用）
/**
 * 在 text 里用「上一段 + 当前段」联合定位当前段落的起始位置；失败返回 -1。
 * 单段 indexOf 在段落文本重复（几人说着同样的话）时会命中错误的位置。
 */
function isAnchorWhitespace(chCode) {
    return chCode === 9 || chCode === 10 || chCode === 13 || chCode === 32 || chCode === 160 || chCode === 12288;
}
function stripParagraphIndent(s) {
    return String(s || "").replace(/^[\s\u3000\u00A0]+/, "");
}
function locateByPrevAnchor(text, prevPara, paragraph) {
    if (!prevPara || !text || !paragraph) return -1;
    text = String(text);
    prevPara = String(prevPara);
    paragraph = String(paragraph);

    var paragraphTrimmed = stripParagraphIndent(paragraph);
    var indentLen = paragraph.length - paragraphTrimmed.length;
    var prevPos = text.indexOf(prevPara);
    while (prevPos !== -1) {
        var afterPrev = prevPos + prevPara.length;

        // 先算换行长度，rawCandidate 保留当前段原本的缩进。
        var lineBreakLen = 0;
        var c0 = text.charCodeAt(afterPrev);
        if (c0 === 13 && text.charCodeAt(afterPrev + 1) === 10) lineBreakLen = 2;
        else if (c0 === 10 || c0 === 13) lineBreakLen = 1;
        var rawCandidate = afterPrev + lineBreakLen;

        // 情况1：data.json / chapterFullText 与当前段都带同样的缩进。
        if (text.substring(rawCandidate, rawCandidate + paragraph.length) === paragraph) {
            return rawCandidate;
        }

        // 情况2：中间有换行、空格、全角空格或空行；跳过后按“去缩进的当前段”比较。
        var skip = 0;
        while (skip < 16) {
            var chCode = text.charCodeAt(rawCandidate + skip);
            if (isAnchorWhitespace(chCode)) skip++;
            else break;
        }
        var noIndentPos = rawCandidate + skip;
        if (paragraphTrimmed && text.substring(noIndentPos, noIndentPos + paragraphTrimmed.length) === paragraphTrimmed) {
            // 尽量返回带缩进的真实起点，保证调用方用 targetPos + paragraph.length 不错位。
            var withIndent = noIndentPos - indentLen;
            if (withIndent >= afterPrev
                && text.substring(withIndent, withIndent + paragraph.length) === paragraph) {
                return withIndent;
            }
            return noIndentPos;
        }

        prevPos = text.indexOf(prevPara, prevPos + 1);
    }
    return -1;
}

function getTtsMeta() {
    try {
        if (typeof ttsMeta === "undefined" || ttsMeta === null || ttsMeta === "") return null;
        var m = (typeof ttsMeta === "string") ? JSON.parse(ttsMeta) : ttsMeta;
        if (!m || typeof m !== "object") return null;
        return m;
    } catch (e) {
        return null;
    }
}
function getFixedLocationFromMeta(paragraph) {
    var meta = getTtsMeta();
    if (!meta) return null;
    var chapterTitle = String(meta.chapterTitle || "").trim();
    if (!chapterTitle) return null;
    var dialogStartSeq = parseInt(meta.dialogStartSeq, 10);
    var chapterDialogueCount = parseInt(meta.chapterDialogueCount, 10);
    if (isNaN(dialogStartSeq) || isNaN(chapterDialogueCount)
        || dialogStartSeq < 1 || chapterDialogueCount < 1) return null;
    var metaText = String(meta.paragraphText || "");
    if (metaText && cleanDialogText(metaText) !== cleanDialogText(paragraph)) return null;

    // ★ 标题 + 段落号联合定位：按标题在 data.json 里找到那一章，
    //   用 paragraphIndex 取候选段落，再和当前正在合成的段落逐字比对。
    //   对不上就返回 null 交给全文定位，绝不采信脏 meta 的对话序号。
    try {
        var dataStr = String(java.readExternalFile(DATA_FILE));
        if (!dataStr || dataStr.trim() === "") return null;
        var dataObj = JSON.parse(dataStr);
        var lines = String(dataObj.texts || "").split(/\r?\n/);
        var titleLine = -1;
        for (var li = 0; li < lines.length; li++) {
            if (String(lines[li]).trim() === chapterTitle) { titleLine = li; break; }
        }
        if (titleLine < 0) return null;
        // 标题之后的非空行是本章段落；再遇到无缩进行（下一章标题）就结束
        var bodyLines = [];
        for (var bi = titleLine + 1; bi < lines.length; bi++) {
            var ln = String(lines[bi]);
            if (ln.trim() === "") continue;
            if (ln.charAt(0) !== "　" && ln.charAt(0) !== " ") break;
            bodyLines.push(ln);
        }
        var pIdx = parseInt(meta.paragraphIndex, 10);
        if (isNaN(pIdx) || pIdx < 0 || pIdx >= bodyLines.length) return null;
        var candidate = String(bodyLines[pIdx] || "");
        if (cleanDialogText(candidate) !== cleanDialogText(paragraph)) return null;
    } catch (e) {
        return null;
    }

    var dialogs = extractDialogs(paragraph);
    if (!dialogs || dialogs.length === 0) return null;
    var seqList = [];
    if (isArray(meta.dialogSeqs) && meta.dialogSeqs.length >= dialogs.length) {
        for (var si = 0; si < dialogs.length; si++) {
            var s = parseInt(meta.dialogSeqs[si], 10);
            if (isNaN(s) || s < 1 || s > chapterDialogueCount) return null;
            seqList.push(s);
        }
    } else {
        for (var i = 0; i < dialogs.length; i++) {
            var seq = dialogStartSeq + i;
            if (seq < 1 || seq > chapterDialogueCount) return null;
            seqList.push(seq);
        }
    }
    return {
        chapterTitle: chapterTitle,
        seqList: seqList,
        totalDialogs: chapterDialogueCount,
        fixed: true
    };
}

function locateParagraphInFullText(paragraph) {
    var fixedLocation = getFixedLocationFromMeta(paragraph);
    if (fixedLocation) return fixedLocation;

    try {
        var dataStr = String(java.readExternalFile(DATA_FILE));
        if (!dataStr || dataStr.trim() === "") return null;
        var data = JSON.parse(dataStr);
        var fullText = String(data.texts);
    } catch (e) { return null; }

    // ★ 联合定位：优先用「上一段 + 当前段」确定位置。单段 indexOf 在段落文本
    // 重复（几人说着同样的话）或被轻微改写时会命中错误的位置，导致章节判断
    // 和 beforeCount 全错；带上上一段做锚点可唯一确定。失败则回退单段定位。
    var prevPara = getPrevParagraph();
    var paraPos = locateByPrevAnchor(fullText, prevPara, paragraph);
    if (paraPos === -1) paraPos = fullText.indexOf(paragraph);
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

    // 章内重新定位同样先用「上一段」锚点：同一段文本可能在章内重复出现
    //（几人说着同样的话），单段 indexOf 会命中第一次出现的位置，导致 beforeCount 算错。
    var paraPosInChapter = locateByPrevAnchor(chapterFullText, prevPara, paragraph);
    if (paraPosInChapter === -1) paraPosInChapter = chapterFullText.indexOf(paragraph);
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
        "   - 【群众互相对话必须区分】当多个群众（无法确定身份的角色）之间互相对话、且性别年龄相同时，必须在名字后追加“甲/乙/丙/丁…”加以区分（例如“群众男青年甲”“群众男青年乙”），确保对话交替中的每个群众都是不同的具体人物；严禁把互相对话的多个群众识别成同一个名字；\n" +
        "4. 必须包含文本中所有序号的对话结果，不能遗漏、不能多返回、不能少返回。\n" +
        "5. 输出前，请仔细核对每个序号对应的对话内容与上下文，确保说话人归属无误；如遇歧义，优先选择上下文中最合理的角色，并避免因序号相邻而误判。\n" +
        "**【上文标注说明】**\n" +
        "【上文历史内容】里每句对话左引号前的「[某某]」是之前分析得到的说话人，不是小说原文，也不是对话序号。\n" +
        "如果方括号里的某个角色在本次【当前待分析对话内容】里继续说话，而这次正文给出了他的真实姓名，就在该序号的 name 里写真名，并额外返回 alias，值为上文方括号里那个名字；\n" +
        "否则照旧只返回 name/gender/age，不要返回 alias。输出仍只覆盖本次的【NN】序号。\n" +
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

function callAnalyzeApi(seqContent, aboveContext) {
    var userContent = "";
    if (aboveContext && aboveContext.trim() !== "") userContent += "【上文历史内容】\n" + aboveContext + "\n";
    userContent += "【当前待分析对话内容】\n" + seqContent;
    var configs = getNextApiConfigs("nameAnalyze", MAX_RETRY);
    if (configs.length === 0) configs = [DEFAULT_API_CONFIG];
    var expectedKeys = [];
    var seenExpected = {};
    var seqKeyReg = /【(\d{1,4})】/g;
    var seqKeyMatch;
    while ((seqKeyMatch = seqKeyReg.exec(seqContent)) !== null) {
        var expectedSeq = padZero(parseInt(seqKeyMatch[1], 10), 2);
        if (!seenExpected[expectedSeq]) {
            seenExpected[expectedSeq] = true;
            expectedKeys.push(expectedSeq);
        }
    }
    var requestList = [];
    for (var i = 0; i < MAX_RETRY; i++) {
        var cfg = configs[i % configs.length];
        var requestBody = JSON.stringify({
            model: cfg.model,
            messages: [{ role: "system", content: buildAnalyzePrompt() }, { role: "user", content: userContent }],
            temperature: 0.1, thinking_mode: false, thinking: { type: "disabled" }, disable_think: true, no_chain_of_thought: true, do_sample: false
        });
        requestList.push(JSON.stringify({
            url: cfg.endpoint, method: "POST", body: requestBody,
            headers: { "Content-Type": "application/json", "Authorization": "Bearer " + cfg.key },
            timeout: AI_TIMEOUT,
            validScene: "name",
            validExpected: expectedKeys
        }));
    }
    var raceBody = java.ajaxRaceDelayedValidated(requestList, RACE_DELAY);
    if (!raceBody) {
        log("【姓名分析-日志】API无返回内容（raceBody为空），模型：" + (configs[0] ? configs[0].model : "未知"));
        return null;
    }
    var resJson;
    try {
        resJson = JSON.parse(raceBody);
    } catch (e) {
        log("【姓名分析-日志】响应体非合法JSON，AI返回内容：\n" + apiLogTruncate(raceBody));
        return null;
    }
    if (!resJson.choices || !resJson.choices[0] || !resJson.choices[0].message || !resJson.choices[0].message.content) {
        log("【姓名分析-日志】API返回格式错误（无content字段），AI返回内容：\n" + apiLogTruncate(raceBody));
        return null;
    }
    var content = resJson.choices[0].message.content.trim();
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
        var outItem = { name: nItem.name, gender: nItem.gender, age: nItem.age };
        if (typeof nItem.alias === 'string' && nItem.alias.trim() !== "") outItem.alias = nItem.alias.trim();
        normalized[padZero(parseInt(nKey, 10), 2)] = outItem;
    }
    return normalized;
}

// ===================== 标注与别名 =====================
// 姓名结果带 alias 时，把旧的那个称呼并到真名下。
// 只改 name 和 aliases，voice/gender/age 一律保留，已分配的声音不变。
function applyAliasFromNameResult(analyzeResult) {
    try {
        var corrections = {}, skipped = {};
        var keys = Object.keys(analyzeResult);
        for (var i = 0; i < keys.length; i++) {
            var item = analyzeResult[keys[i]];
            if (!item || !item.alias || !item.name) continue;
            var alias = String(item.alias).trim();
            var name = String(item.name).trim();
            if (!alias || !name || alias === name || skipped[alias]) continue;
            if (corrections[alias] && corrections[alias] !== name) {
                delete corrections[alias];
                skipped[alias] = true;
                continue;
            }
            corrections[alias] = name;
        }
        var changed = false;
        var records = getLatestCharacterRecords();
        for (var aliasKey in corrections) {
            if (!corrections.hasOwnProperty(aliasKey)) continue;
            var realName = corrections[aliasKey];
            var target = null;
            for (var ri = 0; ri < records.length; ri++) {
                if (isNameMatch(records[ri], aliasKey)) { target = records[ri]; break; }
            }
            if (!target || String(target.name).trim() === realName) continue;
            var oldName = String(target.name).trim();
            var aliases = String(target.aliases || oldName).split("|");
            for (var ai = 0; ai < aliases.length; ai++) aliases[ai] = String(aliases[ai]).trim();
            if (aliases.indexOf(oldName) === -1) aliases.unshift(oldName);
            if (aliases.indexOf(realName) === -1) aliases.push(realName);
            target.aliases = aliases.filter(function (s) { return s; }).join("|");
            target.name = realName;
            changed = true;
        }
        if (changed) {
            java.writeExternalFile(EXT_DIR + "characterRecords.json", JSON.stringify(records, null, 2));
            log("【真名扶正】已合并 " + Object.keys(corrections).length + " 个旧称呼");
        }
    } catch (e) { log("【真名扶正】失败：" + e.message); }
}

function genMarkText(name, voiceDisplay) { return "<<" + voiceDisplay + ">>"; }

function annotateText(paragraph, dialogs, charResults) {
  var result = paragraph;
  // 从后往前处理，避免索引错乱
  for (var i = dialogs.length - 1; i >= 0; i--) {
      var seq = padZero(i + 1, 2);
      var info = charResults[seq];
      if (!info) continue;
      var d = dialogs[i];

      // ★ 已带标记的对话完全跳过：混合段落（只有部分引号未标注）时，
      // 已标注的保持原样（不叠加第二个标记、不做旁白摘除），只给没标的补标。
      if (result.substring(d.index + 1, d.index + 3) === "<<") continue;

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


// 别名识别用的小说上下文：去掉【NN】序号，改成在左引号前标 [本批识别出的说话人]。
function buildAliasContextText(seqContent, nameResult) {
    if (!seqContent) return "";
    return String(seqContent).replace(/【(\d{1,4})】/g, function (m, seq) {
        var info = nameResult ? nameResult[padZero(parseInt(seq, 10), 2)] : null;
        return (info && info.name) ? "[" + String(info.name) + "]" : "";
    });
}

function updateCharacterRecords(dialogList, chapterContext, nameResult) {
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
    var ctxText = chapterContext ? buildAliasContextText(chapterContext, nameResult).slice(0, 3000) : "";
    var aliasPrompt = "你是专业的小说人物别名识别AI。请判断【待确认名字列表】中每个名字是否为【已有角色列表】中某个角色的别名（字号、绰号、昵称、不同称呼等）。\n\n" +
        "【核心原则】\n1. 别名必须是同一人物的不同称呼\n2. 不要仅凭字面相似度推断，需要合理关联\n3. 不确定的默认判定为非别名\n4. 全新角色（从未出现过）判定为非别名\n\n" +
        (ctxText ? "【小说上下文片段】（每句前的 [某某] 是之前自动识别的说话人，仅供参考、可能有误；本片段不带对话序号）\n" + ctxText + "\n\n" : "") +
        "【已有角色列表】\n" + JSON.stringify(existingRolesList) + "\n\n" +
        "【待确认名字列表】\n" + nameSeqText + "\n\n" +
        "【输出要求】\n输出严格JSON格式，键为序号（如\"01\"），值为 {isAlias: true/false, mainName: \"匹配的主名或null\"}。\n必须覆盖所有序号。";
    var aliasResult = null;
    var aliasExpectedKeys = [];
    for (var aki = 0; aki < unknownNames.length; aki++) aliasExpectedKeys.push(padZero(aki + 1, 2));
    var aliasConfigs = getNextApiConfigs("aliasAnalyze", MAX_RETRY);
    if (aliasConfigs.length === 0) aliasConfigs = [DEFAULT_API_CONFIG];
    var aliasRequestList = [];
    for (var i = 0; i < MAX_RETRY; i++) {
        var cfg = aliasConfigs[i % aliasConfigs.length];
        var aliasBody = JSON.stringify({
            model: cfg.model, messages: [{ role: "system", content: "严格遵守格式要求，仅输出JSON" }, { role: "user", content: aliasPrompt }],
            temperature: 0.1, thinking_mode: false, thinking: { type: "disabled" }, disable_think: true, no_chain_of_thought: true, do_sample: false
        });
        aliasRequestList.push(JSON.stringify({
            url: cfg.endpoint, method: "POST", body: aliasBody,
            headers: { "Content-Type": "application/json", "Authorization": "Bearer " + cfg.key },
            timeout: AI_TIMEOUT,
            validScene: "alias",
            validExpected: aliasExpectedKeys
        }));
    }
    var aliasRaceBody = java.ajaxRaceDelayedValidated(aliasRequestList, RACE_DELAY);
    if (!aliasRaceBody) {
        log("【别名校验-日志】API无返回内容（aliasRaceBody为空）");
        return;
    }
    var aliasResJson;
    try {
        aliasResJson = JSON.parse(aliasRaceBody);
    } catch (e) {
        log("【别名校验-日志】响应体非合法JSON，AI返回内容：\n" + apiLogTruncate(aliasRaceBody));
        return;
    }
    if (!aliasResJson.choices || !aliasResJson.choices[0] || !aliasResJson.choices[0].message || !aliasResJson.choices[0].message.content) {
        log("【别名校验-日志】API返回格式错误（无content字段），AI返回内容：\n" + apiLogTruncate(aliasRaceBody));
        return;
    }
    var aliasContent = aliasResJson.choices[0].message.content.trim();
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

/** 段落是否已标注过：存在左双引号，且所有左双引号右侧都紧跟 <<发音人>> 标记 */
function isAlreadyAnnotated(paragraph) {
    var pos = -1, count = 0;
    while ((pos = paragraph.indexOf("“", pos + 1)) !== -1) {
        count++;
        if (paragraph.substring(pos + 1, pos + 3) !== "<<") return false;
    }
    return count > 0;
}

// ===================== 主执行逻辑 =====================
(function() {
    var originalText = text;


    originalText = originalText.replace(/(“[^“”\n]*)(“)/g, "$1”$2");

// ★ 只拦截“有右引号但无左引号”的特殊段落，其他情况（包括有左无右）交给后续正常流程
    if (originalText.indexOf("”") !== -1 && originalText.indexOf("“") === -1) {
    var specialResult = handleSpecialQuoteCases(originalText);
    if (specialResult) { saveCurrentToHistory(originalText); return specialResult; }
    }

    if (originalText && originalText.indexOf("“") > -1) handleBookSwitch();

    // ★ 已标注段落直接放行：左双引号右侧已是 <<发音人>> 标记，说明这段已经处理过
    //（多份脚本副本/重复合成时会出现），不再重复标注，直接进入后续合成。
    if (originalText.indexOf("“") > -1 && isAlreadyAnnotated(originalText)) {
        saveCurrentToHistory(originalText);
        return text;
    }

    var dialogs = extractDialogs(originalText);

    // ★ 如果没有找到任何对话，尝试用多行缓存匹配
    if (dialogs.length === 0) {
        var noQuoteResult = handleNoQuoteText(originalText);
        if (noQuoteResult) { saveCurrentToHistory(originalText); return noQuoteResult; }
        // 实在匹配不到，保留原样
        
        saveCurrentToHistory(originalText);
        return text;
    }

    var bookName = getBookNameSafely();
    var progress = readProgress();
    var useProgress = progress && progress.bookName === bookName && !IS_BOOK_SWITCHED;
    // ★ 进度/历史只是辅助：历史文件记的是「合成顺序」，不是真正的上一句
    //   （例如在第三章合成到一半，退回第二章删音频重合成，历史仍停在第三章）。
    //   必须先用当前正在合成的段落做全文定位，章节与起始序号都对得上，
    //   才允许按 lastSeq+1 继续；否则强制走全文定位重新算序号。
    if (useProgress) {
        var verify = locateParagraphInFullText(originalText);
        if (!verify || verify.chapterTitle !== progress.chapterTitle
            || verify.seqList.length < dialogs.length
            || verify.seqList[0] !== progress.lastSeq + 1) {
            useProgress = false;
        }
    }

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
                var aboveContext = getAboveContext(originalText);
                var belowInfo = getBelowContentInfo(originalText);
                var belowContent = belowInfo.text;
                var seqContent = generateBatchSeqContent(originalText, dialogs, belowContent);
                var analyzeResult = callAnalyzeApi(seqContent, aboveContext);

                if (analyzeResult) {
                    applyAliasFromNameResult(analyzeResult);
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

                    var currentCount = dialogs.length;
                    var savePlan = buildCrossChapterResults(
                        chapterTitle,
                        seqList,
                        allPairs,
                        analyzeResult,
                        currentCount,
                        belowInfo,
                        location.totalDialogs
                    );
                    mergeChapterResultGroups(savePlan.groups);
                    if (savePlan.names.length > 0) {
                        updateCharacterRecords(savePlan.names, seqContent, analyzeResult);
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