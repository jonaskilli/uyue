// ===================== 全书分析专用脚本 =====================
// 本脚本只负责「姓名识别 / 别名合并」两个阶段，不参与朗读、发音人、音效、进度指针。
// Kotlin 侧以函数调用方式驱动；慢的 HTTP 请求由 Kotlin 并发调度。
// 可用绑定：analysisJson / taskJson / recordsJson / nameResultJson / aliasMetaJson / body / java
var MAX_CHARACTER = 800;
var AI_TIMEOUT = 25000;
var MAX_RACE_RETRY = 5;
var NL = String.fromCharCode(10);
var DEFAULT_API_CONFIG = {
    endpoint: "https://open.bigmodel.cn/api/paas/v4/chat/completions",
    model: "glm-4-flash",
    key: "此处替换成你的智谱AI密钥"
};

function _cfg() { return JSON.parse(analysisJson || "{}"); }
function _task(taskJson) { return JSON.parse(taskJson || "{}"); }
function _readText(path) {
    try { return String(java.readExternalFile(path) || ""); } catch (e) { return ""; }
}
function _log(msg) { try { java.log("[全书分析] " + msg); } catch (e) {} }
function padZero(num, len) {
    var s = String(num);
    while (s.length < len) s = "0" + s;
    return s;
}
function _isArray(a) { return Object.prototype.toString.call(a) === "[object Array]"; }
function _isDigits(s) {
    s = String(s || "");
    if (s.length < 1 || s.length > 4) return false;
    for (var i = 0; i < s.length; i++) {
        var c = s.charAt(i);
        if (c < "0" || c > "9") return false;
    }
    return true;
}
function _trimTrailingSlash(s) {
    s = String(s || "");
    while (s.length > 0 && s.charAt(s.length - 1) === "/") s = s.substring(0, s.length - 1);
    return s;
}
function _stripSeqPrefix(s) {
    s = String(s || "");
    if (s.charAt(0) === "【") {
        var end = s.indexOf("】");
        if (end > 0) s = s.substring(end + 1);
    }
    return s;
}
function _stripFence(c) {
    c = String(c == null ? "" : c).trim();
    if (c.indexOf("```") === 0) {
        c = c.substring(3).trim();
        if (c.toLowerCase().indexOf("json") === 0) c = c.substring(4).trim();
        var end = c.lastIndexOf("```");
        if (end >= 0) c = c.substring(0, end).trim();
    }
    return c;
}
function _parseApiConfigs(content) {
    content = String(content || "").trim();
    if (content === "") return [];
    if (content.indexOf("@@") === -1) {
        return [{ endpoint: DEFAULT_API_CONFIG.endpoint, model: DEFAULT_API_CONFIG.model, key: content }];
    }
    var arr = content.split("@@"), out = [];
    for (var i = 0; i + 2 < arr.length; i += 3) {
        var endpoint = String(arr[i] || "").trim();
        var model = String(arr[i + 1] || "").trim();
        var key = String(arr[i + 2] || "").trim();
        if (!key) continue;
        endpoint = _trimTrailingSlash(endpoint);
        if (endpoint && endpoint.indexOf("/chat/completions") === -1) endpoint += "/chat/completions";
        out.push({
            endpoint: endpoint || DEFAULT_API_CONFIG.endpoint,
            model: model || DEFAULT_API_CONFIG.model,
            key: key
        });
    }
    return out;
}
function _apiConfigs(scene, keyIndex) {
    var raw = _readText(_cfg().keyPath).trim();
    var namePart = raw, aliasPart = raw;
    if (raw.indexOf("##") !== -1) {
        var parts = raw.split("##");
        namePart = parts[0] || "";
        aliasPart = parts[1] || namePart;
    }
    var list = _parseApiConfigs(scene === "alias" ? aliasPart : namePart);
    if (list.length === 0) list = [DEFAULT_API_CONFIG];
    return list;
}
function _requestList(systemPrompt, userContent, scene, keyIndex, expectedKeys) {
    var cfgs = _apiConfigs(scene, keyIndex || 0);
    var out = [];
    for (var i = 0; i < MAX_RACE_RETRY; i++) {
        var cfg = cfgs[((keyIndex || 0) + i) % cfgs.length];
        var body = JSON.stringify({
            model: cfg.model,
            messages: [
                { role: "system", content: systemPrompt },
                { role: "user", content: userContent }
            ],
            temperature: 0.1,
            thinking_mode: false,
            thinking: { type: "disabled" },
            disable_think: true,
            no_chain_of_thought: true,
            do_sample: false
        });
        out.push(JSON.stringify({
            url: cfg.endpoint,
            method: "POST",
            body: body,
            headers: { "Content-Type": "application/json", "Authorization": "Bearer " + cfg.key },
            timeout: AI_TIMEOUT,
            validScene: scene === "alias" ? "alias" : "name",
            validExpected: _isArray(expectedKeys) ? expectedKeys : []
        }));
    }
    return out;
}
function _extractJsonTolerant(content) {
    var c = _stripFence(content);
    if (c === "") throw new Error("返回内容为空");
    var candidates = ["{" + c + "}", "{" + c, c + "}", c];
    var fs = c.indexOf("{"), ls = c.lastIndexOf("}");
    if (fs !== -1 && ls !== -1 && ls >= fs) candidates.push(c.substring(fs, ls + 1));
    for (var i = 0; i < candidates.length; i++) {
        try { return JSON.parse(candidates[i]); } catch (e) {}
    }
    throw new Error("返回内容无法解析为JSON");
}
function _parseBody(body) {
    var res = JSON.parse(String(body || ""));
    if (!res.choices || !res.choices[0] || !res.choices[0].message || !res.choices[0].message.content) {
        throw new Error("API返回格式错误");
    }
    return String(res.choices[0].message.content).trim();
}
function _readRecords(recordsJson) {
    try {
        var a = JSON.parse(recordsJson || "[]");
        return _isArray(a) ? a : [];
    } catch (e) { return []; }
}
function _items(nameResultJson) {
    try {
        var o = JSON.parse(nameResultJson || "{}");
        return o.items || {};
    } catch (e) { return {}; }
}
function _isChapterTitleLine(line) {
    var s = String(line || "");
    return /^\s*第\s*[0-9一二三四五六七八九十百千零〇两]+\s*章/.test(s);
}
function _buildSeqContent(t) {
    var currentDialogs = _isArray(t.dialogs) ? t.dialogs : [];
    var below = String(t.below || "");
    var lines = below.split(NL);
    var belowDialogs = [], offset = 0;
    for (var li = 0; li < lines.length; li++) {
        var line = lines[li];
        if (!_isChapterTitleLine(line)) {
            var idx = 0;
            while (idx < line.length) {
                var left = line.indexOf("“", idx);
                if (left < 0) break;
                var right = line.indexOf("”", left + 1);
                var rawText = right >= 0 ? line.substring(left + 1, right) : line.substring(left + 1, Math.min(line.length, left + 1000));
                var dialogText = String(rawText || "")
                    .replace(/^【[0-9]+】/, "")
                    .replace(/[『「【〈〉〔'']/g, "");
                if (dialogText && dialogText.trim() !== "") {
                    belowDialogs.push({ text: dialogText, sourceOffset: offset + left });
                }
                idx = right >= 0 ? right + 1 : line.length;
            }
        }
        offset += line.length + 1;
    }
    var all = [];
    for (var i = 0; i < currentDialogs.length; i++) {
        all.push({
            text: _stripSeqPrefix(String(currentDialogs[i].text || "")),
            isCurrent: true,
            sourceOffset: 0
        });
    }
    for (var j = 0; j < belowDialogs.length; j++) {
        all.push({
            text: belowDialogs[j].text,
            isCurrent: false,
            sourceOffset: belowDialogs[j].sourceOffset
        });
    }
    var seqContent = "", pairs = [];
    for (var k = 0; k < all.length; k++) {
        var localSeq = padZero(k + 1, 2);
        var dialogText2 = String(all[k].text || "").replace(/^【[0-9]+】/, "");
        seqContent += "【" + localSeq + "】“" + dialogText2 + "”" + NL;
        pairs.push({
            localSeq: localSeq,
            text: dialogText2,
            isCurrent: all[k].isCurrent,
            sourceOffset: all[k].sourceOffset || 0
        });
    }
    return { text: seqContent, pairs: pairs };
}

function prepareNameRequest(taskJson) {
    var t = _task(taskJson);
    var seq = _buildSeqContent(t);
    var userContent = "";
    var prevPara = String(t.prevParagraph || "");
    if (prevPara !== "") userContent += "【上一段落】" + NL + prevPara + NL;
    var above = String(t.above || "");
    if (above !== "") userContent += "【上文历史内容】" + NL + above + NL;
    userContent += "【当前待分析对话内容】" + NL + seq.text;
    var expectedKeys = [];
    for (var ei = 0; ei < seq.pairs.length; ei++) expectedKeys.push(seq.pairs[ei].localSeq);
    var requests = _requestList(buildAnalyzePrompt(), userContent, "name", t.keyIndex || 0, expectedKeys);
    return JSON.stringify({ requests: requests, pairs: seq.pairs });
}

function parseNameResponse(taskJson, body) {
    try {
        var parsed = _extractJsonTolerant(_parseBody(body));
        var items = {};
        for (var key in parsed) {
            if (!parsed.hasOwnProperty(key) || !_isDigits(key)) continue;
            var it = parsed[key];
            if (!it || typeof it.name !== "string" || typeof it.gender !== "string" || typeof it.age !== "string") continue;
            var name = it.name.trim();
            if (name === "") continue;
            items[padZero(parseInt(key, 10), 2)] = { name: name, gender: it.gender.trim(), age: it.age.trim() };
        }
        return JSON.stringify({ items: items });
    } catch (e) {
        _log("姓名结果解析失败: " + e);
        return JSON.stringify({ items: {} });
    }
}
function _roleIndex(records) {
    var main = {}, alias = {};
    for (var i = 0; i < records.length; i++) {
        var r = records[i];
        if (!r || !r.name) continue;
        var n = String(r.name).trim();
        main[n] = r;
        var aliases = String(r.aliases || "").split("|");
        for (var j = 0; j < aliases.length; j++) {
            var a = String(aliases[j] || "").trim();
            if (a && a !== n) alias[a] = n;
        }
    }
    return { main: main, alias: alias };
}
var Q = String.fromCharCode(34);

function _batchNames(items) {
    var keys = [];
    for (var key in items) {
        if (items.hasOwnProperty(key)) keys.push(key);
    }
    keys.sort(function (a, b) { return parseInt(a, 10) - parseInt(b, 10); });
    var out = [], seen = {};
    for (var i = 0; i < keys.length; i++) {
        var it = items[keys[i]];
        if (!it) continue;
        var name = String(it.name || "").trim();
        if (!name || name === "未知" || seen[name]) continue;
        seen[name] = true;
        out.push({
            name: name,
            gender: String(it.gender || "男").trim() || "男",
            age: String(it.age || "青年").trim() || "青年"
        });
    }
    return out;
}
function _unknownFromBatch(records, batchNames) {
    var idx = _roleIndex(records), out = [];
    for (var i = 0; i < batchNames.length; i++) {
        var b = batchNames[i];
        if (idx.main[b.name] || idx.alias[b.name]) continue;
        out.push({ name: b.name, gender: b.gender, age: b.age });
    }
    return out;
}
function _existingRolesList(records, limit) {
    var list = [], seen = {}, aliasToMainMap = {}, usedRecords = 0;
    for (var i = 0; i < records.length && usedRecords < limit; i++) {
        var r = records[i];
        usedRecords++;
        if (!r || !r.name) continue;
        var mainName = String(r.name).trim();
        if (!mainName || seen[mainName]) continue;
        seen[mainName] = true;
        var roleObj = { name: mainName };
        if (r.aliases && String(r.aliases).trim()) roleObj.aliases = String(r.aliases).trim();
        list.push(roleObj);
        var aliases = String(r.aliases || "").split("|");
        for (var j = 0; j < aliases.length; j++) {
            var alias = String(aliases[j] || "").trim();
            if (alias && alias !== mainName) aliasToMainMap[alias] = mainName;
        }
    }
    return { list: list, aliasToMainMap: aliasToMainMap };
}

function _reorderByBatch(records, batchNames) {
    var idx = _roleIndex(records);
    var top = [], topSeen = {};
    for (var i = 0; i < batchNames.length; i++) {
        var bn = String(batchNames[i] && batchNames[i].name ? batchNames[i].name : "").trim();
        if (!bn) continue;
        var mainName = idx.main[bn] ? bn : (idx.alias[bn] || "");
        var rec = mainName ? idx.main[mainName] : null;
        if (rec && !topSeen[mainName]) {
            top.push(rec);
            topSeen[mainName] = true;
        }
    }
    var rest = [];
    for (var j = 0; j < records.length; j++) {
        var r = records[j];
        var n = r && r.name ? String(r.name).trim() : "";
        if (!n || !topSeen[n]) rest.push(r);
    }
    return top.concat(rest);
}
function prepareAliasRequest(taskJson, recordsJson, nameResultJson) {
    var t = _task(taskJson);
    var records = _readRecords(recordsJson);
    var items = _items(nameResultJson);
    var batchNames = _batchNames(items);
    var unknown = _unknownFromBatch(records, batchNames);
    var candidates = _existingRolesList(records, 50);
    var existingRolesList = candidates.list;
    var requests = [];
    if (unknown.length > 0) {
        var nameSeqText = "";
        for (var i = 0; i < unknown.length; i++) nameSeqText += "【" + padZero(i + 1, 2) + "】" + unknown[i].name + NL;
        var ctxText = (
            String(t.prevParagraph || "") + NL +
            String(t.above || "") + NL +
            String(t.paragraph || "") + NL +
            String(t.below || "")
        ).slice(0, 3000);
        var prompt = "你是专业的小说人物别名识别AI。请判断【待确认名字列表】中每个名字是否为【已有角色列表】中某个角色的别名（字号、绰号、昵称、不同称呼等）。" + NL + NL +
            "【核心原则】" + NL +
            "1. 别名必须是同一人物的不同称呼" + NL +
            "2. 不要仅凭字面相似度推断，需要合理关联" + NL +
            "3. 不确定的默认判定为非别名" + NL +
            "4. 全新角色（从未出现过）判定为非别名" + NL + NL +
            (ctxText ? "【小说上下文片段】" + NL + ctxText + NL + NL : "") +
            "【已有角色列表】" + NL + JSON.stringify(existingRolesList) + NL + NL +
            "【待确认名字列表】" + NL + nameSeqText + NL +
            "【输出要求】" + NL +
            "输出严格JSON格式，键为序号（如" + Q + "01" + Q + "），值为 {isAlias: true/false, mainName: " + Q + "匹配的主名或null" + Q + "}。" + NL +
            "必须覆盖所有序号。";
        var aliasExpectedKeys = [];
        for (var aki = 0; aki < unknown.length; aki++) aliasExpectedKeys.push(padZero(aki + 1, 2));
        requests = _requestList("严格遵守格式要求，仅输出JSON", prompt, "alias", t.keyIndex || 0, aliasExpectedKeys);
    }
    return JSON.stringify({
        requests: requests,
        unknownNames: unknown,
        batchNames: batchNames,
        aliasToMainMap: candidates.aliasToMainMap,
        candidateCount: existingRolesList.length
    });
}

function _parseAliasResult(body) {
    var parsed = _extractJsonTolerant(_parseBody(body));
    var out = {};
    for (var key in parsed) {
        if (!parsed.hasOwnProperty(key) || !_isDigits(key)) continue;
        var it = parsed[key];
        if (!it || typeof it.isAlias !== "boolean") continue;
        out[padZero(parseInt(key, 10), 2)] = { isAlias: it.isAlias, mainName: String(it.mainName || "").trim() };
    }
    return out;
}
function parseAliasResponse(taskJson, body, recordsJson, nameResultJson, aliasMetaJson) {
    try {
        var records = _readRecords(recordsJson);
        var meta = JSON.parse(aliasMetaJson || "{}");
        var unknown = _isArray(meta.unknownNames) ? meta.unknownNames : [];
        var batchNames = _isArray(meta.batchNames) ? meta.batchNames : [];
        var aliasToMainMap = meta.aliasToMainMap || {};
        var result = {};
        if (body && String(body).trim() !== "") result = _parseAliasResult(body);
        var idx = _roleIndex(records);
        for (var i = 0; i < unknown.length; i++) {
            var u = unknown[i];
            var seq = padZero(i + 1, 2);
            var res = result[seq];
            var name = String(u.name || "").trim();
            if (!name) continue;
            var target = null;
            if (res && res.isAlias && res.mainName) {
                // 按当前内置朗读脚本：mainName 可能是主名，也可能是别名，
                // 通过 mainNameSet / aliasToMainMap 找到真正的主名记录。
                var aiMain = String(res.mainName || "").trim();
                var mappedMain = aliasToMainMap[aiMain] || idx.alias[aiMain] || aiMain;
                target = idx.main[aiMain] || idx.main[mappedMain];
            }
            if (target) {
                var aliasGender = String(u.gender || "").trim();
                var mainGender = String(target.gender || "").trim();
                if (aliasGender && mainGender && aliasGender.charAt(0) !== mainGender.charAt(0)) {
                    _log("【别名校验】" + name + " 性别(" + aliasGender + ")与主名性别(" + mainGender + ")不符，按新角色处理");
                    target = null;
                }
            }
            if (target) {
                var aliases = String(target.aliases || "").split("|");
                var found = false;
                for (var j = 0; j < aliases.length; j++) if (String(aliases[j] || "").trim() === name) found = true;
                if (!found) aliases.push(name);
                target.aliases = aliases.filter(function (s) { return String(s || "").trim() !== ""; }).join("|");
            } else {
                records.unshift({
                    name: name,
                    aliases: name,
                    gender: String(u.gender || "男"),
                    age: String(u.age || "青年"),
                    voice: "",
                    usageCount: 100,
                    fixedVoice: false
                });
            }
        }
        // 本批姓名AI识别出来的角色统一置顶：
        // 已有角色、合并到主名的别名、新建角色都按本批出现顺序排到最前面。
        // 这样后续别名识别取前50条角色记录时，最近活跃角色不会被挤到后面。
        records = _reorderByBatch(records, batchNames);
        while (records.length > MAX_CHARACTER) records.pop();
        return JSON.stringify(records);
    } catch (e) {
        _log("别名结果解析失败: " + e);
        return recordsJson;
    }
}

function resolveRecordsTarget() {
    var cfg = _cfg();
    var raw = _readText(cfg.cunfangPath).trim();
    if (raw.charAt(0) === "\"") { try { raw = JSON.parse(raw); } catch (e) {} }
    raw = String(raw || "").trim();
    if (raw !== "" && raw === String(cfg.bookName || "").trim()) return cfg.characterRecordsPath;
    return cfg.shumingPath;
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
        "输出格式示例：\n{\n  \"01\": {\n    \"name\": \"分析出的说话人姓名\",\n    \"gender\": \"性别（男/女/特殊）\",\n    \"age\": \"年龄分类（女性：女童/少女/女青年/女中年/女老年）；（男性：男童/少年/男青年/男中年/男老年）；（特殊：系统/旁白）\"\n  },\n  \"02\": { ... }\n}\n";
}
