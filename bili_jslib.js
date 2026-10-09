/* =====================================================================
 * 哔哩哔哩听书 · Legado 书源公共库 (jsLib)
 * ---------------------------------------------------------------------
 * 逻辑严格对齐 abts (github.com/fengqiao57/abts) 的 lib/core/network/
 * wbi_signer.dart 与 lib/services/bili_api.dart。
 *
 * 注意：Rhino 环境下访问不到 java.md5Encode，因此这里自带纯 JS MD5。
 * 所有函数均为纯计算（不触碰 java/http），网络动作在各 rule 的 JS 里完成。
 * ===================================================================== */

var BILI_HOME = 'https://www.bilibili.com';
var BILI_API = 'https://api.bilibili.com';
var BILI_SEARCH_HOME = 'https://search.bilibili.com';
var BILI_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

/* ---------- 状态码：命中这些视为风控，需要重试 ---------- */
var BILI_RISK_CODES = { '-412': 1, '-352': 1, '-509': 1 };

/* =====================================================================
 * 一、纯 JS MD5（UTF-8 友好，中文关键词必须走 UTF-8）
 * ===================================================================== */

function biliUtf8Bytes(str) {
  var out = [], i = 0, c, c2, cc;
  var s = String(str);
  while (i < s.length) {
    c = s.charCodeAt(i++);
    if (c < 0x80) {
      out.push(c);
    } else if (c < 0x800) {
      out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    } else if (c >= 0xd800 && c < 0xdc00 && i < s.length) {
      // 代理对
      c2 = s.charCodeAt(i++);
      cc = 0x10000 + ((c & 0x3ff) << 10) + (c2 & 0x3ff);
      out.push(
        0xf0 | (cc >> 18),
        0x80 | ((cc >> 12) & 0x3f),
        0x80 | ((cc >> 6) & 0x3f),
        0x80 | (cc & 0x3f)
      );
    } else {
      out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    }
  }
  return out;
}

var BILI_MD5_S = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
];

var BILI_MD5_K = (function () {
  var k = [], i;
  for (i = 0; i < 64; i++) {
    k[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) & 0xffffffff;
  }
  return k;
})();

function biliHex32(n) {
  var r = '', v, k;
  for (k = 0; k < 4; k++) {
    v = (n >>> (k * 8)) & 0xff;
    r += (v < 16 ? '0' : '') + v.toString(16);
  }
  return r;
}

function biliMd5(str) {
  var b = biliUtf8Bytes(str);
  var len = b.length;
  var i, j;
  // 填充
  b.push(0x80);
  while (b.length % 64 !== 56) b.push(0);
  var bitHi = Math.floor((len * 8) / 4294967296);
  var bitLo = (len * 8) >>> 0;
  b.push(bitLo & 0xff, (bitLo >>> 8) & 0xff, (bitLo >>> 16) & 0xff, (bitLo >>> 24) & 0xff);
  b.push(bitHi & 0xff, (bitHi >>> 8) & 0xff, (bitHi >>> 16) & 0xff, (bitHi >>> 24) & 0xff);

  var v1 = 0x67452301, v2 = 0xefcdab89, v3 = 0x98badcfe, v4 = 0x10325476;
  var M = [], A, B, C, D, F, g;

  function rotl(x, n) {
    return ((x << n) | (x >>> (32 - n))) >>> 0;
  }

  for (i = 0; i < b.length; i += 64) {
    for (j = 0; j < 16; j++) {
      M[j] = (b[i + j * 4] |
        (b[i + j * 4 + 1] << 8) |
        (b[i + j * 4 + 2] << 16) |
        (b[i + j * 4 + 3] << 24)) >>> 0;
    }
    A = v1; B = v2; C = v3; D = v4;
    for (j = 0; j < 64; j++) {
      if (j < 16) {
        F = (B & C) | (~B & D); g = j;
      } else if (j < 32) {
        F = (D & B) | (~D & C); g = (5 * j + 1) % 16;
      } else if (j < 48) {
        F = B ^ C ^ D; g = (3 * j + 5) % 16;
      } else {
        F = C ^ (B | ~D); g = (7 * j) % 16;
      }
      F = (F + A + BILI_MD5_K[j] + M[g]) >>> 0;
      A = D; D = C; C = B;
      B = (B + rotl(F, BILI_MD5_S[j])) >>> 0;
    }
    v1 = (v1 + A) >>> 0;
    v2 = (v2 + B) >>> 0;
    v3 = (v3 + C) >>> 0;
    v4 = (v4 + D) >>> 0;
  }
  return biliHex32(v1) + biliHex32(v2) + biliHex32(v3) + biliHex32(v4);
}

/* =====================================================================
 * 二、WBI 签名 —— 对齐 wbi_signer.dart
 * ===================================================================== */

var BILI_MIXIN_TAB = [
  46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
  27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
  37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
  22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
];

function biliMixinKey(orig) {
  var s = '', i;
  for (i = 0; i < 32; i++) s += orig.charAt(BILI_MIXIN_TAB[i]);
  return s;
}

/** 从 https://i0.hdslb.com/bfs/wbi/xxx.png 提取 xxx */
function biliKeyOf(url) {
  var name = url.substring(url.lastIndexOf('/') + 1);
  var dot = name.lastIndexOf('.');
  return dot > 0 ? name.substring(0, dot) : name;
}

/** 对齐 Dart: 先剔除 !'()* 再做 encodeURIComponent */
function biliWbiEncode(v) {
  var s = String(v).replace(/[!'()*]/g, '');
  return encodeURIComponent(s);
}

/**
 * 对参数对象做 WBI 签名，返回可直接拼接的 query string（不含前导 ?）
 * params: 普通对象；会就地加入 wts 与 w_rid
 */
function biliSignQuery(params, imgUrl, subUrl) {
  var mk = biliMixinKey(biliKeyOf(imgUrl) + biliKeyOf(subUrl));
  params.wts = Math.floor(Date.now() / 1000);
  var keys = [], k, i;
  for (k in params) {
    if (params.hasOwnProperty(k) && params[k] !== null && params[k] !== undefined) keys.push(k);
  }
  keys.sort();
  var buf = '';
  for (i = 0; i < keys.length; i++) {
    buf += biliWbiEncode(keys[i]) + '=' + biliWbiEncode(params[keys[i]]) + '&';
  }
  var noTail = buf.substring(0, buf.length - 1);
  params.w_rid = biliMd5(noTail + mk);
  var qs = '', head = '';
  for (i = 0; i < keys.length; i++) {
    qs += head + encodeURIComponent(keys[i]) + '=' + encodeURIComponent(params[keys[i]]);
    head = '&';
  }
  qs += '&w_rid=' + params.w_rid;
  return qs;
}

/* =====================================================================
 * 三、听书内容判定 —— 对齐 bili_api.dart 的 _isAudiobookItem
 * ===================================================================== */

var BILI_AUDIO_HINTS = [
  '有声小说', '有声书', '有声剧', '有声读物', '有声', '听书', '评书', '相声',
  '广播剧', '演播', '朗读', '诵读', '长书', '连播', '说书', '单口', '播讲',
  '多人剧', '小说剧'
];
var BILI_NOISE_HINTS = [
  '一口气看完', '漫推', '漫画', '解说', '讲解', '解读', '动画', '鬼畜', '混剪',
  '沙雕', '速看', 'reaction', '预告', '游戏实况', '配音秀'
];
var BILI_GENRE_HINTS = [
  '评书', '相声', '广播剧', '听书', '演播', '朗读', '诵读', '长书', '连播',
  '说书', '单口', '播讲'
];
var BILI_AUDIO_TYPE_ID = 195;

function biliHas(str, arr) {
  var i;
  for (i = 0; i < arr.length; i++) {
    if (str.indexOf(arr[i]) >= 0) return true;
  }
  return false;
}

/** 去掉搜索结果标题里的 <em class="keyword"> 高亮标签 */
function biliCleanTitle(t) {
  return String(t == null ? '' : t)
    .replace(/<em class="keyword">/g, '')
    .replace(/<\/em>/g, '');
}

/** 封面协议归一化：//i0.hdslb.com 与 http:// 统一转 https */
function biliNormalizePic(u) {
  var s = String(u == null ? '' : u).trim();
  if (!s) return s;
  if (s.indexOf('//') === 0) return 'https:' + s;
  if (s.indexOf('http://') === 0) return 'https://' + s.substring(7);
  return s;
}

/** duration 可能是秒数，也可能是 "1:02:03" / "5152:33" 字符串 */
function biliDurSec(v) {
  if (v === null || v === undefined) return 0;
  if (typeof v === 'number') return v;
  var s = String(v).trim();
  if (!s) return 0;
  var p = s.split(':');
  if (p.length >= 2) {
    var t = 0, i;
    for (i = 0; i < p.length; i++) t = t * 60 + (parseInt(p[i].trim(), 10) || 0);
    return t;
  }
  return parseInt(s, 10) || 0;
}

/**
 * 是否算听书内容。
 * title/tag/desc/typeid/duration 来自搜索条目，keyword 为搜索词（可空）。
 */
function biliIsAudiobook(title, tag, desc, typeid, duration, keyword) {
  title = biliCleanTitle(title);
  var meta = String(tag == null ? '' : tag) + ' ' + String(desc == null ? '' : desc);
  if (biliHas(title + ' ' + meta, BILI_NOISE_HINTS)) return false;

  var sec = biliDurSec(duration);
  if (biliHas(title, BILI_AUDIO_HINTS) && sec >= 300) return true;
  if (parseInt(typeid, 10) === BILI_AUDIO_TYPE_ID && sec >= 600) return true;
  if (biliHas(meta, BILI_AUDIO_HINTS) && sec >= 1800) return true;
  if (keyword && biliHas(String(keyword), BILI_GENRE_HINTS) && sec >= 1800) return true;
  return false;
}

/** 时长秒 -> "1:02:03" / "12:34" */
function biliDurText(sec) {
  var n = parseInt(sec, 10) || 0;
  if (n <= 0) return '';
  var h = Math.floor(n / 3600), m = Math.floor((n % 3600) / 60), s = n % 60;
  var mm = m < 10 ? '0' + m : '' + m;
  var ss = s < 10 ? '0' + s : '' + s;
  if (h > 0) return h + ':' + mm + ':' + ss;
  return mm + ':' + ss;
}

/** 从 JSON 字符串安全取对象 */
function biliJson(s) {
  return JSON.parse(String(s));
}

/* =====================================================================
 * 四、需要网络的函数
 * ---------------------------------------------------------------------
 * Rhino 中 jsLib 被挂拼成 prototype scope，函数体里访问不到调用者的
 * java/cache binding（词法作用域在 sharedScope）。因此这里统一把
 * java(实现了 JsExtensions 的对象) 与 cache(CacheManager) 显式入参。
 * 这样规则里写 <js>fn(java,cache,...)</js> 一行即可调用，也便于离线测试。
 * ===================================================================== */

/* ---- 缓存 key ---- */
var BILI_CK_WBI = 'bili_wbi_keys';
var BILI_CK_READY = 'bili_warmup_ready';
var BILI_CK_KW = 'bili_search_keyword';
var BILI_CK_PAGE = 'bili_search_page';

/* =====================================================================
 * 发现页分类：[显示名, 搜索关键词, 排序方式]
 * 严格对齐 abts lib/data/seed_books.dart 的 DiscoverSeeds.categories
 * 与 hotKeys。
 * ===================================================================== */
var BILI_CATEGORIES = [
  ['热门推荐', '有声小说', 'click', 1],
  ['广播剧', '广播剧 全集', '', 1],
  ['评书', '评书 单田芳', '', 1],
  ['相声', '相声 德云社', '', 1],
  ['悬疑', '悬疑有声小说', '', 1],
  ['玄幻仙侠', '玄幻小说听书', '', 1],
  ['名著悦读', '世界名著朗读', '', 1],
  ['名家主播', '', '', 1],
  ['周建龙', '周建龙 有声小说', ''],
  ['紫襟', '紫襟 有声小说', ''],
  ['王明军', '王明军 有声小说', ''],
  ['艾宝良', '艾宝良 有声小说', ''],
  ['李野默', '李野默 有声小说', ''],
  ['青雪', '青雪 有声小说', ''],
  ['单田芳', '单田芳 评书', ''],
  ['刘兰芳', '刘兰芳 评书', ''],
  ['袁阔成', '袁阔成 评书', ''],
  ['田连元', '田连元 评书', ''],
  ['郭德纲', '郭德纲 相声', ''],
  ['马三立', '马三立 相声', ''],
  ['光合积木', '光合积木 广播剧', ''],
  ['729声工场', '729声工场 广播剧', ''],
  ['边江工作室', '边江工作室 广播剧', ''],
  ['头陀渊', '头陀渊 有声小说', ''],
  ['桑梓', '桑梓 有声小说', ''],
  ['道听途说', '道听途说 有声小说', '']
];

/* 分类项默认占满一行；主播类一行排 4 个，避免发现页过长 */
var BILI_ANCHOR_BASIS = 0.25;

/* 发现页搜索框的标题，同时也是 infoMap 里的取值 key。
 * ⚠️ 改这里时必须同步改下面 biliExploreUrl 里 action 中的 '🔍 关键词' 字面量
 *    （那段必须与番茄短剧保持逐字一致，不能改成变量拼接以外的差异写法）。 */
var BILI_SEARCH_KEY = '🔍 关键词';

/**
 * 生成发现页（ExploreKind 数组，JSON 字符串）。
 *
 * 与「名::url」纯文本格式不同，这里返回 JSON 数组，从而支持：
 *  1. type:'text'  输入框，输入值会落进 infoMap[title]
 *  2. type:'button' 按钮，点击时执行 action
 * 两者配合即可在发现页内直接发起搜索（跳过手输 pencil）。
 *
 * 分类的 url 仍是一段延迟执行的 <js>，真正请求时才由 AnalyzeUrl 计算签名，
 * 避免 exploreKinds 把已签名的 w_rid 缓存下来导致过期。
 */
function biliExploreUrl() {
  var items = [];

  // ── 搜索区：输入框 + 搜索按钮 ──
  items.push({
    title: BILI_SEARCH_KEY,
    type: 'text',
    style: { layout_flexGrow: 1, layout_flexBasisPercent: 0.65 }
  });
  items.push({
    title: '📤 搜索',
    type: 'button',
    // ⚠️ 这一段与番茄短剧的实现逐字保持一致，不要擅自加 .replace() 之类改造：
    //    source.bookSourceName 拿到的是 java.lang.String，Rhino 里对它调
    //    replace(/:/g,'') 会命中 Java 的 replace(CharSequence,CharSequence)
    //    而非 ECMAScript 的 String.prototype.replace，RegExp 传不进去会抛错，
    //    而 evalButtonClick 会静默吞掉异常，表现就是「点按钮没反应」。
    //    scope 取「书源名::书源地址」，与 RssJsExtensions 里的做法一致。
    action:
      "java.searchBook(infoMap['🔍 关键词']||'',source.bookSourceName+'::'+source.getKey())",
    style: { layout_flexGrow: 1, layout_flexBasisPercent: -1 }
  });

  // ── 分类区 / 主播区 ──
  for (var i = 0; i < BILI_CATEGORIES.length; i++) {
    var c = BILI_CATEGORIES[i];
    // 第 4 项为布局宽度；主分类显式给 1（占满一行），主播缺省则用 0.25（一行四个）
    var basis = c.length > 3 ? c[3] : BILI_ANCHOR_BASIS;
    if (!c[1]) {
      // 纯标题行，url 为空 -> 点击无响应，仅作分组标签
      items.push({
        title: c[0],
        url: '',
        style: { layout_flexGrow: 1, layout_flexBasisPercent: basis }
      });
      continue;
    }
    items.push({
      title: c[0],
      url:
        '<js>biliSearchUrl(java,cache,' +
        JSON.stringify(c[1]) +
        ',1,' +
        JSON.stringify(c[2]) +
        ')</js>',
      style: { layout_flexGrow: 1, layout_flexBasisPercent: basis }
    });
  }

  return JSON.stringify(items);
}

function biliSleep(ms) {
  var t = Date.now() + ms;
  while (Date.now() < t) {
    /* 退避等待，规避 B 站风控 */
  }
}

/* ---- CacheManager 薄封装 ----
 * 不依赖 put(key,value,saveTime) 的 Int 形参（Rhino 传 double 易踩类型转换坑），
 * 改为自行存储时间戳来判断有效期。
 */
function biliCacheGet(cache, k) {
  try {
    var v = cache.get(k);
    return v === null || v === undefined ? '' : String(v);
  } catch (e) {
    return '';
  }
}

function biliCacheSet(cache, k, v) {
  try {
    cache.put(k, String(v));
  } catch (e) {
    try {
      cache.put(k, String(v), 0);
    } catch (e2) {}
  }
}

function biliCacheDel(cache, k) {
  try {
    cache.delete(k);
  } catch (e) {}
}

/**
 * 预热：访问首页种 buvid3/b_nut，激活一次 gaia 风控闭环。
 * 成功后在 1 小时内不再重复。
 */
function biliWarmup(java, cache) {
  var ready = biliCacheGet(cache, BILI_CK_READY);
  var rp = ready.split('|');
  if (rp.length === 2 && Date.now() - parseInt(rp[1], 10) < 3600000) return true;
  try {
    java.ajax(BILI_HOME + '/');
  } catch (e) {
    /* 预热失败不阻塞 */
  }
  try {
    // gaia：Buvid 激活，显著压低 v_voucher / -412 概率
    var png = biliBase64Bytes(biliRandomPngBytes());
    var payload = JSON.stringify({
      '3064': 1,
      '39c8': '333.1387.fp.risk',
      '3c43': { adca: 'Linux', bfe9: png.substring(png.length - 50) }
    });
    java.ajax(
      BILI_API +
        '/x/internal/gaia-gateway/ExClimbWuzhi,{"method":"POST","body":' +
        JSON.stringify({ payload: payload }) +
        '}'
    );
  } catch (e) {
    /* 静默 */
  }
  biliCacheSet(cache, BILI_CK_READY, '1|' + Date.now());
  return true;
}

/** 拼接一段伪造 PNG 的字节（gaia 上报用，对齐 BiliClient._ensureBuvidActivated） */
function biliRandomPngBytes() {
  var bytes = [], i;
  for (i = 0; i < 32; i++) bytes.push(Math.floor(Math.random() * 256));
  bytes = bytes.concat([0, 0, 0, 0, 73, 69, 78, 68]); // IEND 尾块
  for (i = 0; i < 4; i++) bytes.push(Math.floor(Math.random() * 256));
  return bytes;
}

/** base64 编码字节数组（Rhino 无 btoa，手写一版） */
function biliBase64Bytes(bytes) {
  var T =
    'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  var out = '', i, b1, b2, b3;
  for (i = 0; i < bytes.length; i += 3) {
    b1 = bytes[i];
    b2 = bytes[i + 1];
    b3 = bytes[i + 2];
    var triplet = (b1 << 16) | ((b2 === undefined ? 0 : b2) << 8) | (b3 === undefined ? 0 : b3);
    out += T.charAt((triplet >> 18) & 0x3f) + T.charAt((triplet >> 12) & 0x3f);
    if (b2 === undefined) {
      out += '==';
    } else {
      out += T.charAt((triplet >> 6) & 0x3f);
      out += b3 === undefined ? '=' : T.charAt(triplet & 0x3f);
    }
  }
  return out;
}

/**
 * 取 WBI 公钥对 [imgUrl, subUrl]，按 24 小时缓存。
 * 对齐 BiliClient._refreshWbiKeys + BiliApi 的按天刷新策略。
 */
function biliWbiKeys(java, cache, force) {
  if (!force) {
    var p = biliCacheGet(cache, BILI_CK_WBI).split('|');
    if (p.length === 3 && p[0] && p[1]) {
      if (Date.now() - parseInt(p[2], 10) < 86400000) return [p[0], p[1]];
    }
  }
  var txt;
  try {
    txt = java.ajax(BILI_API + '/x/web-interface/nav');
  } catch (e) {
    return null;
  }
  if (!txt) return null;
  var j;
  try {
    j = JSON.parse(String(txt));
  } catch (e) {
    return null;
  }
  var d = j.data || {};
  var w = d.wbi_img;
  if (!w || !w.img_url || !w.sub_url) return null;
  biliCacheSet(cache, BILI_CK_WBI, w.img_url + '|' + w.sub_url + '|' + Date.now());
  return [w.img_url, w.sub_url];
}

/**
 * 生成带 WBI 签名的 search/type 完整 URL
 * @param order 可选：'click' 按播放排序（榜单），'' 综合排序
 */
function biliSearchUrl(java, cache, word, page, order) {
  biliWarmup(java, cache);
  var ks = biliWbiKeys(java, cache);
  if (!ks) throw 'B站初始化失败：取不到 WBI 公钥';
  var n = parseInt(page, 10);
  if (!n || n < 1) n = 1;
  var p = {
    search_type: 'video',
    keyword: word,
    page: n,
    page_size: 20,
    platform: 'pc',
    web_location: 1430654
  };
  if (order) p.order = order;
  biliCacheSet(cache, BILI_CK_KW, word);
  biliCacheSet(cache, BILI_CK_PAGE, String(n));
  return BILI_API + '/x/web-interface/wbi/search/type?' + biliSignQuery(p, ks[0], ks[1]);
}

/**
 * 解析并过滤 search/type 响应。
 * @param body  响应体字符串
 * @param word  搜索词（用于“题材放宽”判定，可为空）
 * @param page  页码（补搜时会用到）
 * @return 数组，每项为一个 JSON 字符串
 */
function biliSearchBody(java, cache, body, word, page) {
  var j;
  try {
    j = JSON.parse(String(body));
  } catch (e) {
    return [];
  }
  var d = j.data || {};

  // 风控兜底：v_voucher / -412 / -352 时刷新公钥后重试一次
  if (j.code !== 0 || !d.result) {
    if (d.v_voucher || j.code === -412 || j.code === -352) {
      if (java && cache) {
        biliSleep(1200);
        var ks2 = biliWbiKeys(java, cache, true);
        if (ks2) {
          var n = parseInt(page, 10);
          if (!n || n < 1) n = 1;
          var p2 = {
            search_type: 'video',
            keyword: word,
            page: n,
            page_size: 20,
            platform: 'pc',
            web_location: 1430654
          };
          var u2 =
            BILI_API + '/x/web-interface/wbi/search/type?' + biliSignQuery(p2, ks2[0], ks2[1]);
          try {
            j = JSON.parse(String(java.ajax(u2)));
            d = j.data || {};
          } catch (e2) {
            /* 保持原值 */
          }
        }
      }
    }
  }

  var list = d.result || [];
  var out = [];
  var i;
  for (i = 0; i < list.length; i++) {
    var m = list[i];
    var bvid = m.bvid || '';
    if (!bvid) continue;
    if (!biliIsAudiobook(m.title, m.tag, m.description, m.typeid, m.duration, word)) continue;
    out.push(m);
  }

  // 对齐 BiliApi.search：命中太少且关键词本身不含听书词时，补搜 “关键词 有声小说”
  if (out.length < 6 && java && cache && !biliHas(String(word), BILI_AUDIO_HINTS)) {
    try {
      var extras = biliSearchRawList(java, cache, word + ' 有声小说', page);
      var seen = {};
      for (i = 0; i < out.length; i++) seen[out[i].bvid] = 1;
      for (i = 0; i < extras.length; i++) {
        var e3 = extras[i];
        if (e3.bvid && !seen[e3.bvid]) {
          seen[e3.bvid] = 1;
          out.push(e3);
        }
      }
    } catch (e4) {
      /* 补搜失败不影响主结果 */
    }
  }

  // 输出成 Legado bookList 可直接消费的形态：每项一个 JSON 字符串
  var res = [];
  for (i = 0; i < out.length; i++) {
    var it = out[i];
    res.push(
      JSON.stringify({
        bvid: it.bvid,
        aid: it.aid,
        mid: it.mid,
        name: biliCleanTitle(it.title),
        author: it.author || '',
        cover: biliNormalizePic(it.pic),
        intro: it.description || '',
        kind: it.typename || '',
        tag: it.tag || '',
        play: it.play || 0,
        pubdate: it.pubdate || 0,
        sdur: biliDurSec(it.duration),
        dur: biliDurText(biliDurSec(it.duration)),
        bookUrl: BILI_API + '/x/web-interface/view?bvid=' + it.bvid
      })
    );
  }
  return res;
}

/** 内部：取某个关键词的已过滤列表（补搜用） */
function biliSearchRawList(java, cache, kw, page) {
  var ks = biliWbiKeys(java, cache);
  if (!ks) return [];
  var n = parseInt(page, 10);
  if (!n || n < 1) n = 1;
  var p = {
    search_type: 'video',
    keyword: kw,
    page: n,
    page_size: 20,
    platform: 'pc',
    web_location: 1430654
  };
  var u = BILI_API + '/x/web-interface/wbi/search/type?' + biliSignQuery(p, ks[0], ks[1]);
  var body = java.ajax(u);
  var j;
  try {
    j = JSON.parse(String(body));
  } catch (e) {
    return [];
  }
  var d = j.data || {};
  var list = d.result || [];
  var out = [];
  for (var i = 0; i < list.length; i++) {
    var m = list[i];
    if (!m.bvid) continue;
    if (!biliIsAudiobook(m.title, m.tag, m.description, m.typeid, m.duration, kw)) continue;
    out.push(m);
  }
  return out;
}

/**
 * 取某个分P的音频地址（DASH audio）。
 * 对齐 BiliApi.playUrl + BookAudio.pickPreferred。
 */
function biliAudioUrl(java, cache, bvid, cid) {
  if (!bvid) throw '缺少 bvid';
  if (!cid) throw '缺少 cid';
  biliWarmup(java, cache);
  var p = {
    bvid: bvid,
    cid: cid,
    qn: 80,
    fnval: 4048,
    fnver: 0,
    fourk: 1,
    try_look: 1,
    gaia_source: 'prefer-ua'
  };
  var ks = biliWbiKeys(java, cache);
  if (!ks) throw 'B站初始化失败：取不到 WBI 公钥';
  var j = biliPlayurl(java, cache, p, ks);
  if (j.code !== 0) {
    // 风控：刷新公钥重试一次
    biliSleep(1200);
    ks = biliWbiKeys(java, cache, true);
    if (ks) j = biliPlayurl(java, cache, p, ks);
  }
  if (j.code !== 0) throw '取流失败 ' + j.code + ' ' + j.message;

  var d = j.data || {};
  var dash = d.dash || {};
  // 优先无损/杜比，其次按带宽降序
  if (dash.flac && dash.flac.audio && dash.flac.audio.baseUrl) return dash.flac.audio.baseUrl;
  if (dash.dolby && dash.dolby.audio && dash.dolby.audio.length && dash.dolby.audio[0].baseUrl) {
    return dash.dolby.audio[0].baseUrl;
  }
  var arr = dash.audio || [];
  if (!arr.length) throw '该分P没有可用音轨';
  arr = arr.slice(0).sort(function (a, b) {
    return (b.bandwidth || 0) - (a.bandwidth || 0);
  });
  return arr[0].baseUrl;
}

/** 内部：调 playurl 并返回 parsed json */
function biliPlayurl(java, cache, p, ks) {
  var u = BILI_API + '/x/player/wbi/playurl?' + biliSignQuery(p, ks[0], ks[1]);
  var txt = java.ajax(u);
  try {
    return JSON.parse(String(txt));
  } catch (e) {
    return { code: -1, message: '解析失败' };
  }
}

/**
 * 从任意包含 bvid / cid 的 URL 中抽取参数。
 * chapterUrl 形如 https://api.bilibili.com/x/player/pagelist?bvid=BV1xx&cid=123
 */
function biliParseUrl(urlStr) {
  var s = String(urlStr);
  var b = /[?&]bvid=([^&]+)/.exec(s);
  var c = /[?&]cid=([^&]+)/.exec(s);
  return {
    bvid: b ? decodeURIComponent(b[1]) : '',
    cid: c ? decodeURIComponent(c[1]) : ''
  };
}

/**
 * 解析分P列表（pagelist 响应）成 Legado 章节数组。
 * pagelist 的每一项不含 bvid，因此从当前请求 URL(baseUrl) 里补出来，
 * 并预先拼好 chapterUrl，让 ruleToc 只需走纯 JsonPath。
 * @return 数组，每项为一个 JSON 字符串
 */
function biliChapterList(body, base) {
  var j;
  try {
    j = JSON.parse(String(body));
  } catch (e) {
    return [];
  }
  var arr = j.data || [];
  var bv = biliParseUrl(base).bvid;
  var out = [], i;
  for (i = 0; i < arr.length; i++) {
    var p = arr[i];
    if (!p || !p.cid) continue;
    out.push(
      JSON.stringify({
        cid: p.cid,
        page: p.page,
        part: p.part || '',
        sdur: biliDurSec(p.duration),
        dur: biliDurText(biliDurSec(p.duration)),
        bvid: bv,
        url: BILI_API + '/x/player/pagelist?bvid=' + bv + '&cid=' + p.cid
      })
    );
  }
  return out;
}
