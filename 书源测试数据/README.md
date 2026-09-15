# AI 书源生成器·测试数据

## 全量书源_5541站.json

5541 个去重书源（按 bookSourceUrl 去重），用途：

1. **模式库来源**：`AiBookSourceGenerator.PATTERN_LIBRARY` 的规则模式
   （搜索模板 / bookList / 详情 / 目录 / 正文容器 / 净化正则）即从本文件统计提炼。
2. **测试基准**：修改生成器后，可抽样本文件中的书源对应的站点做回归验证。

### 数据来源

| 来源 | 原始条目 | 说明 |
|---|---|---|
| aoaostar/legado 全量书源 | 3907 | GitHub 自动同步，2026-09 活跃 |
| 酷安@开源阅读软件合集 | 2117 | 同仓库 sources/3bb7b751.json |
| 酷安@三舞313 | 1554 | 同仓库 sources/2a1f129b.json |
| shidahuilang 书源 | 939 | 同仓库 sources/e29e19ee.json |
| daiaji/LegadoSource 书源.json | 1374 | GitHub，2023 |
| 1629 精品书源（gitcode） | 1629 | 2022 |
| 破冰/XIU2/关耳女频等 | 各 20~128 | 精品小合集 |

以上合并后按 bookSourceUrl 去重得 5541 条。

### 统计结论速览（详见生成器内 PATTERN_LIBRARY）

- 搜索：纯 GET 2196 / POST+选项 1271 / GET+选项 624 / JS 动态 141；杰奇 CMS 模板覆盖 500+
- 正文容器 top：content(1637) > htmlContent(194) > nr1(142) > chaptercontent(131) > booktxt(65) > nr(37) 等 23 种
- 70% 站点目录就在详情页；nextTocUrl 766 个、nextContentUrl 1346 个书源在用
- 知名站 JSON 搜索接口示例：纵横 search.zongheng.com/search/book?keyword=
