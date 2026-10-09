/**
 * 构建脚本：把 bili_jslib.js 打包成可直接导入 Legado 的书源 JSON。
 * 用法：node build_bili_source.js
 * 产物：哔哩哔哩听书.json
 */
const fs = require('fs');
const path = require('path');

const jslib = fs.readFileSync(path.join(__dirname, 'bili_jslib.js'), 'utf8');

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

// 注意：Referer 必须是无尾斜杠的 https://www.bilibili.com，
// 带斜杠会被 B 站风控直接打回 412（已对照实验确认）。
const HEADER = {
  'User-Agent': UA,
  Referer: 'https://www.bilibili.com',
  Origin: 'https://www.bilibili.com',
};

const BOOK_LIST =
  "<js>biliSearchBody(java,cache,result,cache.get('bili_search_keyword')||'',cache.get('bili_search_page')||1)</js>";

const searchFields = {
  bookList: BOOK_LIST,
  name: '$.name',
  bookUrl: '$.bookUrl',
  author: '$.author',
  coverUrl: '$.cover',
  intro: '$.intro',
  kind: '$.kind',
  wordCount: '$.sdur',
  lastChapter: '$.dur',
};

const source = {
  bookSourceName: '哔哩哔哩听书',
  bookSourceGroup: '听书',
  bookSourceType: 1, // BookSourceType.audio
  bookSourceUrl: 'https://www.bilibili.com',
  bookUrlPattern: '',
  customOrder: 0,
  enabled: true,
  enabledExplore: true,
  enabledCookieJar: true,
  concurrentRate: '1/400',
  header: JSON.stringify(HEADER),
  jsLib: jslib,

  searchUrl: "<js>biliSearchUrl(java,cache,key,page,'')</js>",
  exploreUrl: '<js>biliExploreUrl()</js>',

  ruleSearch: { ...searchFields },
  ruleExplore: { ...searchFields },

  ruleBookInfo: {
    name: '$.data.title',
    author: '$.data.owner.name',
    intro: '$.data.desc',
    kind: "<js>biliJson(result).data.tname||'有声书'</js>",
    coverUrl: '$.data.pic@js:biliNormalizePic(result)',
    wordCount: '$.data.duration',
    lastChapter: "<js>biliJson(result).data.videos+' 集'</js>",
    tocUrl: 'https://api.bilibili.com/x/player/pagelist?bvid={$.data.bvid}',
  },

  ruleToc: {
    chapterList: '<js>biliChapterList(result,baseUrl)</js>',
    chapterName: '$.part',
    chapterUrl: '$.url',
    updateTime: '$.dur',
  },

  ruleContent: {
    content:
      '<js>biliAudioUrl(java,cache,biliParseUrl(baseUrl).bvid,biliParseUrl(baseUrl).cid)</js>',
  },

  bookSourceComment: [
    '把 B 站当有声书库：搜索/发现 -> 视频 = 一本书，分P = 一章，播放该分P的音频轨。',
    '逻辑严格对齐开源项目 abts（github.com/fengqiao57/abts）：WBI 签名、听书过滤规则同源。',
    '免登录可用。首次使用会先预热 Cookie 并做 gaia 风控激活，稍等即可。',
    '未登录最高 64K 音质（dash id 30216）；导入含 SESSDATA 的 Cookie 可解锁更高音质。',
    '音频流有时效（约 2 小时），但每章播放前都会重新取流，不存在过期问题。',
    '遇到 -412「网络繁忙」已内置自动退避重试；持续报错可稍后再试。',
  ].join('\n'),
};

const out = path.join(__dirname, '哔哩哔哩听书.json');
fs.writeFileSync(out, JSON.stringify(source, null, 2), 'utf8');

console.log('已生成:', out);
console.log('大小:', (fs.statSync(out).size / 1024).toFixed(1), 'KB');
console.log('jsLib 长度:', jslib.length, '字符');
