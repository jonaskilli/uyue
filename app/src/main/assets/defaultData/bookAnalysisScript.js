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
/**
 * 与内置朗读脚本 Z3.3 的 scanBelowDialogs 同口径，额外保留左引号在下文里的偏移，
 * 供 Kotlin 把结果写回对应章节缓存。
 */
function _scanBelowDialogs(belowText) {
    var lines = String(belowText || "").split(/\r?\n/);
    var out = [], lineStart = 0;
    for (var li = 0; li < lines.length; li++) {
        var line = lines[li];
        if (!_isChapterTitleLine(line)) {
            var idx = 0;
            while (idx < line.length) {
                var left = line.indexOf("“", idx);
                if (left < 0) break;
                var right = line.indexOf("”", left + 1);
                var rawText = right >= 0
                    ? line.substring(left + 1, right)
                    : line.substring(left + 1, Math.min(line.length, left + 1000));
                var text = String(rawText || "").replace(/^【[0-9]+】/, "");
                if (text && text.trim() !== "") out.push({ text: text, sourceOffset: lineStart + left });
                idx = right >= 0 ? right + 1 : line.length;
            }
        }
        lineStart += line.length + 1;
    }
    return out;
}

/**
 * 与内置朗读脚本 Z3.3 的 generateBatchSeqContent 完全同口径：
 * 当前段对话重包引号 + 下文 1000 字「原文」（保留叙述、动作、"XX说道"等提示词），
 * 补右引号后只给前 80% 的左引号编号，尾部留作只供参考的上下文。
 * 返回的 pairs 仍带 isCurrent / sourceOffset，供 Kotlin 定位章节缓存。
 */
function _buildSeqContent(t) {
    var currentDialogs = _isArray(t.dialogs) ? t.dialogs : [];
    var below = String(t.below || "");

    var cleanedDialogues = "";
    for (var i = 0; i < currentDialogs.length; i++) {
        var dialogText = String(currentDialogs[i].text || "").replace(/^【\d+】/, "");
        cleanedDialogues += "“" + dialogText + "”" + NL;
    }
    var cleanedBelow = below.replace(/【\d+】/g, "");
    var fullRawText = cleanedDialogues + cleanedBelow;
    fullRawText = fullRawText.replace(/(“[^“”\n]*)(\n[^“”]+)($|“)/g, "$1”$2$3");
    fullRawText = fullRawText.replace(/(“[^“”\n]*)($|“)/g, "$1”$2");
    fullRawText = fullRawText.replace(/【\d{1,4}】/g, "");
    fullRawText = fullRawText.replace(/[『「【〈〉〔'']/g, "");

    var allLeftQuotes = fullRawText.match(/“/g);
    var totalQuoteCount = allLeftQuotes ? allLeftQuotes.length : 0;
    var stopAddIndex = totalQuoteCount <= 5
        ? totalQuoteCount
        : Math.max(Math.floor(totalQuoteCount * 0.8), 1);
    // 80% 截断只给下文尾部留缓冲，当前段对话必须全部编号
    if (stopAddIndex < currentDialogs.length) stopAddIndex = currentDialogs.length;
    var seqCounter = 0;
    var seqContent = fullRawText.replace(/“/g, function (match) {
        seqCounter++;
        if (seqCounter <= stopAddIndex) return "【" + padZero(seqCounter, 2) + "】" + match;
        return match;
    });

    var pairs = [];
    var belowDialogs = _scanBelowDialogs(below);
    var seqReg = /【(\d{1,4})】“([^”]*)”/g;
    var m, k = 0;
    while ((m = seqReg.exec(seqContent)) !== null) {
        var isCurrent = k < currentDialogs.length;
        var sourceOffset = 0;
        if (!isCurrent) {
            var bd = belowDialogs[k - currentDialogs.length];
            sourceOffset = bd ? bd.sourceOffset : 0;
        }
        pairs.push({
            localSeq: m[1],
            text: m[2],
            isCurrent: isCurrent,
            sourceOffset: sourceOffset
        });
        k++;
    }
    if (pairs.length === 0) {
        for (var f = 0; f < currentDialogs.length; f++) {
            pairs.push({
                localSeq: padZero(f + 1, 2),
                text: String(currentDialogs[f].text || "").replace(/^【\d+】/, ""),
                isCurrent: true,
                sourceOffset: 0
            });
        }
    }
    return { text: seqContent, pairs: pairs };
}

function prepareNameRequest(taskJson) {
    var t = _task(taskJson);
    var seq = _buildSeqContent(t);
    var userContent = "";
    var above = String(t.above || "");
    if (above.trim() !== "") userContent += "【上文历史内容】" + NL + above + NL;
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
            var outItem = { name: name, gender: it.gender.trim(), age: it.age.trim() };
            if (typeof it.alias === "string" && it.alias.trim() !== "") outItem.alias = it.alias.trim();
            items[padZero(parseInt(key, 10), 2)] = outItem;
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
    // 姓名 AI 把同一次出场的主名+别名错当成「真名+旧称呼」返回时，
    // 两个名字都不在名单里 → 一起交给别名 API，各占一个编号；
    // 只要有一个已在名单里 → 维持保护，不交给别名 API。
    var idx0 = _roleIndex(records);
    var pairs = [], excludedNames = {};
    for (var ak in items) {
        if (!items.hasOwnProperty(ak)) continue;
        var ai = items[ak];
        if (!ai || !ai.name || !ai.alias) continue;
        var px = String(ai.name).trim(), py = String(ai.alias).trim();
        if (!px || !py || px === py) continue;
        var pxKnown = !!(idx0.main[px] || idx0.alias[px]);
        var pyKnown = !!(idx0.main[py] || idx0.alias[py]);
        if (!pxKnown && !pyKnown) {
            pairs.push({
                main: px, alias: py,
                gender: String(ai.gender || "男").trim() || "男",
                age: String(ai.age || "青年").trim() || "青年"
            });
        } else {
            excludedNames[px] = true;
        }
    }
    var unknown = [];
    var rawUnknown = _unknownFromBatch(records, batchNames);
    for (var ui = 0; ui < rawUnknown.length; ui++) {
        if (!excludedNames[String(rawUnknown[ui].name).trim()]) unknown.push(rawUnknown[ui]);
    }
    var seenUnknown = {};
    for (var si = 0; si < unknown.length; si++) seenUnknown[String(unknown[si].name).trim()] = true;
    for (var pi = 0; pi < pairs.length; pi++) {
        var pr = pairs[pi];
        var two = [
            { name: pr.main, gender: pr.gender, age: pr.age },
            { name: pr.alias, gender: pr.gender, age: pr.age }
        ];
        for (var ti = 0; ti < two.length; ti++) {
            if (!seenUnknown[two[ti].name]) {
                seenUnknown[two[ti].name] = true;
                unknown.push(two[ti]);
            }
        }
    }
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
            (ctxText ? "【小说上下文片段】（每句前的 [某某] 是之前自动识别的说话人，仅供参考、可能有误；本片段不带对话序号）" + NL + ctxText + NL + NL : "") +
            "【已有角色列表】" + NL + JSON.stringify(existingRolesList) + NL + NL +
            "【待确认名字列表】" + NL + nameSeqText + NL + NL +
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
        candidateCount: existingRolesList.length,
        pairs: pairs
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
// 姓名结果带 alias 时，把旧的那个称呼并到真名下；voice 等字段一律保留。
function _namesOfRecord(rec) {
    var out = [];
    var arr = String(rec.aliases || "").split("|");
    for (var i = 0; i < arr.length; i++) {
        var s = String(arr[i] || "").trim();
        if (s && out.indexOf(s) === -1) out.push(s);
    }
    return out;
}

function _findMainRecord(records, name) {
    var n = String(name || "").trim();
    if (!n) return null;
    for (var i = 0; i < records.length; i++) {
        var r = records[i];
        if (r && String(r.name || "").trim() === n) return r;
    }
    for (var j = 0; j < records.length; j++) {
        var rec = records[j];
        if (rec && _namesOfRecord(rec).indexOf(n) >= 0) return rec;
    }
    return null;
}

// alias 必须是当前名单里独立的主名记录；降为别人的别名就永久失去合并资格。
// strict=true（真名已在原名单）时，还要求这条记录的别名栏只有它自己。
function _findMergeableRecord(records, aliasName, strict) {
    var alias = String(aliasName || "").trim();
    if (!alias) return null;
    var pure = null, count = 0;
    for (var i = 0; i < records.length; i++) {
        var r = records[i];
        if (!r) continue;
        var names = _namesOfRecord(r);
        var main = String(r.name || "").trim();
        if (main === alias) {
            pure = r;
            count++;
            if (strict) {
                for (var k = 0; k < names.length; k++) if (names[k] !== alias) return null;
            }
        } else if (names.indexOf(alias) >= 0) {
            return null;
        }
    }
    return count === 1 ? pure : null;
}

function _mergeIntoMainRecord(records, aliasName, mainName) {
    var alias = String(aliasName || "").trim();
    var main = String(mainName || "").trim();
    if (!alias || !main || alias === main) return records;
    // 真名已在原名单 → 严谨；真名是全新的 → 宽松，允许旧称呼带着它的别名一起改名
    var mainRec = _findMainRecord(records, main);
    var strict = !!mainRec;
    var pure = _findMergeableRecord(records, alias, strict);
    if (!pure) return records;
    if (mainRec) {
        var finalMain = String(mainRec.name || "").trim() || main;
        var merged = _namesOfRecord(mainRec);
        if (merged.indexOf(finalMain) === -1) merged.unshift(finalMain);
        var extra = _namesOfRecord(pure);
        extra.unshift(alias);
        for (var i = 0; i < extra.length; i++) {
            if (extra[i] && merged.indexOf(extra[i]) === -1) merged.push(extra[i]);
        }
        mainRec.aliases = merged.join("|");
        var out = [];
        for (var j = 0; j < records.length; j++) {
            var r = records[j];
            if (!r || r === pure) continue;
            if (r !== mainRec && String(r.name || "").trim() === finalMain) continue;
            out.push(r);
        }
        return out;
    }
    var oldName = String(pure.name || "").trim() || alias;
    var names = _namesOfRecord(pure);
    if (names.indexOf(oldName) === -1) names.unshift(oldName);
    if (names.indexOf(main) === -1) names.push(main);
    pure.aliases = names.join("|");
    pure.name = main;
    return records.slice();
}

function _dedupeByMainName(records) {
    var seen = {}, out = [];
    for (var i = 0; i < records.length; i++) {
        var r = records[i];
        if (!r) continue;
        var n = String(r.name || "").trim();
        if (!n) continue;
        if (!seen[n]) { seen[n] = r; out.push(r); continue; }
        var merged = _namesOfRecord(seen[n]);
        var extra = _namesOfRecord(r);
        for (var j = 0; j < extra.length; j++) {
            if (merged.indexOf(extra[j]) === -1) merged.push(extra[j]);
        }
        seen[n].aliases = merged.join("|");
    }
    return out;
}

function _applyAliasFromNameResult(records, nameResultJson) {
    var items = _items(nameResultJson);
    var corrections = {}, skipped = {};
    for (var key in items) {
        if (!items.hasOwnProperty(key)) continue;
        var it = items[key];
        if (!it || !it.alias || !it.name) continue;
        var a = String(it.alias).trim(), n = String(it.name).trim();
        if (!a || !n || a === n || skipped[a]) continue;
        if (corrections[a] && corrections[a] !== n) { delete corrections[a]; skipped[a] = true; continue; }
        corrections[a] = n;
    }
    for (var a in corrections) {
        if (!corrections.hasOwnProperty(a)) continue;
        records = _mergeIntoMainRecord(records, a, corrections[a]);
    }
    return _dedupeByMainName(records);
}

function parseAliasResponse(taskJson, body, recordsJson, nameResultJson, aliasMetaJson) {
    try {
        var records = _applyAliasFromNameResult(_readRecords(recordsJson), nameResultJson);
        var meta = JSON.parse(aliasMetaJson || "{}");
        var unknown = _isArray(meta.unknownNames) ? meta.unknownNames : [];
        var batchNames = _isArray(meta.batchNames) ? meta.batchNames : [];
        var aliasToMainMap = meta.aliasToMainMap || {};
        var result = {};
        if (body && String(body).trim() !== "") result = _parseAliasResult(body);
        var idx = _roleIndex(records);
        // 双新组按组统一处理：任意一个判成别名 → 两个名字一起挂到那个主角色后面；
        // 分别判给不同主名 → 以「主名」那条为准；都没判成别名 → 一起新建一条记录。
        var pairs = _isArray(meta.pairs) ? meta.pairs : [];
        var handled = {};
        for (var pi = 0; pi < pairs.length; pi++) {
            var pr = pairs[pi];
            var sx = -1, sy = -1;
            for (var uu = 0; uu < unknown.length; uu++) {
                var un = String(unknown[uu].name || "").trim();
                if (un === String(pr.main || "").trim()) sx = uu;
                if (un === String(pr.alias || "").trim()) sy = uu;
            }
            if (sx < 0 || sy < 0) continue;
            var kx = padZero(sx + 1, 2), ky = padZero(sy + 1, 2);
            handled[kx] = true;
            handled[ky] = true;
            var rx = result[kx], ry = result[ky];
            var mn = "";
            if (rx && rx.isAlias && rx.mainName) mn = String(rx.mainName).trim();
            else if (ry && ry.isAlias && ry.mainName) mn = String(ry.mainName).trim();
            var mappedMain = mn ? (aliasToMainMap[mn] || idx.alias[mn] || mn) : "";
            var target = mn ? (idx.main[mn] || idx.main[mappedMain]) : null;
            if (target) {
                var arr = String(target.aliases || "").split("|");
                var merged = [];
                for (var mi = 0; mi < arr.length; mi++) {
                    var ss = String(arr[mi] || "").trim();
                    if (ss && merged.indexOf(ss) === -1) merged.push(ss);
                }
                var pm = String(pr.main || "").trim(), pa = String(pr.alias || "").trim();
                if (pm && merged.indexOf(pm) === -1) merged.push(pm);
                if (pa && merged.indexOf(pa) === -1) merged.push(pa);
                target.aliases = merged.join("|");
            } else {
                records.unshift({
                    name: String(pr.main || "").trim(),
                    aliases: String(pr.main || "").trim() + "|" + String(pr.alias || "").trim(),
                    gender: String(pr.gender || "男"),
                    age: String(pr.age || "青年"),
                    voice: "",
                    usageCount: 100,
                    fixedVoice: false
                });
            }
        }
        for (var i = 0; i < unknown.length; i++) {
            var u = unknown[i];
            var seq = padZero(i + 1, 2);
            if (handled[seq]) continue;
            var res = result[seq];
            var name = String(u.name || "").trim();
            if (!name) continue;
            // 已是别人的别名就不再参与合并，避免泛称呼把一串角色连起来
            if (idx.alias[name]) continue;
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
        "**【上文标注说明】**\n" +
        "【上文历史内容】里每句对话左引号前的「[某某]」是之前分析得到的说话人，不是小说原文，也不是对话序号。\n" +
        "如果方括号里的某个角色在本次【当前待分析对话内容】里继续说话，而这次正文给出了他的真实姓名，就在该序号的 name 里写真名，并额外返回 alias，值为上文方括号里那个名字；\n" +
        "否则照旧只返回 name/gender/age，不要返回 alias。输出仍只覆盖本次的【NN】序号。\n" +
        "输出格式示例：\n{\n  \"01\": {\n    \"name\": \"分析出的说话人姓名\",\n    \"gender\": \"性别（男/女/特殊）\",\n    \"age\": \"年龄分类（女性：女童/少女/女青年/女中年/女老年）；（男性：男童/少年/男青年/男中年/男老年）；（特殊：系统/旁白）\"\n  },\n  \"02\": { ... }\n}\n";
}
