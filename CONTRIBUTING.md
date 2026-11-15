# 提交规范

依据 [约定式提交 1.0.0](https://www.conventionalcommits.org/zh-hans/v1.0.0/)（Conventional Commits）。

## 格式

```
<类型>[可选范围]: <描述>

[可选正文]

[可选脚注]
```

- 冒号为英文半角，其后必须跟一个空格。
- 类型全小写，本项目统一使用小写。
- 描述为对变更为的简短总结，结尾不加句号。

## 类型

| 类型 | 用途 |
|---|---|
| `feat` | 新增功能 |
| `fix` | 修复缺陷 |
| `docs` | 仅文档变更 |
| `style` | 格式调整，不影响逻辑（缩进、空格、分号等） |
| `refactor` | 重构，既不修缺陷也不加功能 |
| `perf` | 性能优化 |
| `test` | 测试用例的增删改 |
| `build` | 构建系统或外部依赖变更 |
| `ci` | 持续集成配置变更 |
| `chore` | 其它不修改业务代码的杂项 |

## 范围

范围为描述代码所属部分的名词，置于圆括号内。本仓库常用的范围：

`read`（阅读页）、`tts`（朗读）、`log`（日志）、`build`（构建）、`ci`、`docs`。

## 破坏性变更

在类型或类型(范围)后紧跟 `!`，或在脚注中写 `BREAKING CHANGE: <说明>`。

```
feat(read)!: 翻页手势改为可配置
```

## 正文与脚注

- 正文起始于描述后的一个空行，用于说明动机、根因与影响。
- 引用用户决定时写明日期，例如 `（用户定案 11-12）`。
- 引用 CI 时写明运行编号，例如 `（CI 43031934592）`。
- 脚注采用 `令牌: 值` 形式，如 `Refs: #123`。

## 工作流运行名称

`run-name` 为可选项，仅在能提供区分度时使用，否则保持 GitHub 默认编号（`#1`、`#2`）。

| 触发方式 | 是否设 `run-name` |
|---|---|
| push 自动触发（编译） | 是，带提交信息 |
| 手动触发（打包） | 否，避免同一人重复触发时标题完全相同 |
| 标签触发（发版） | 是，带标签名 |

GitHub 表达式无 `split`，无法只取提交信息首行；运行列表按单行截断显示，完整内容在运行详情页可见。

## 合并上游（z阅读）

完整流程（查新/合并/冲突裁决/清非源码/判据速查）已集中到
`/sdcard/DSH Go/流程/上游同步与合并流程.md`（11-15 起，含本节全部细节并持续维护）——**看那份**。

本仓要点速记：
- 源自 `cnb.cool/mingwuyan/iyuedu` 的 `yuedutts` 分支；两仓内容同源、历史独立
  （graft 已于 11-13 接通，见 `.git/refs/replace/`）。
- 查上游更新只看 yuedutts 分支顶；合并后清非源码、编译结论以 CI 为准。

## 禁止 AI 工具署名

提交信息中**禁止**出现指向 AI 工作平台的 `Co-Authored-By` 尾注：

```
Co-Authored-By: traeagent <traeagent@users.noreply.github.com>    ❌
Co-Authored-By: Claude Code <noreply@anthropic.com>              ❌
Co-Authored-By: Cursor Agent <...>                              ❌
```

**原因**：GitHub 会把 `Co-Authored-By` 中的账号计入仓库 Contributors。
Trae、Claude Code、Cursor 这类 AI 工作平台生成的尾注，会让 AI 以「贡献者」身份
出现在仓库首页——贡献者只应是人类维护者。

### 已配置的防护

仓库自带 `commit-msg` 钩子（`scripts/hooks/commit-msg`），提交时自动拦截
含 `trae|cursor|claude|copilot|codeium|windsurf|aider|devin|openai|anthropic|gemini|glm|deepseek|chatgpt`
等关键词的 `Co-Authored-By` 行，命中即拒绝提交。

钩子随仓库分发、有提交记录可追溯。Git 出于安全不会在克隆后自动执行钩子，
**换机器或重新克隆后需启用一次**：

```bash
bash scripts/install-hooks.sh
```

该脚本设置 `core.hooksPath=scripts/hooks`，之后该目录下所有钩子自动生效。
验证：提交一条带 `Co-Authored-By: traeagent` 尾注的信息，应被拒绝。

### 已经混入历史的尾注怎么处理

```bash
git branch backup-before-ai-trailer-removal    # 先备份
git filter-branch -f --msg-filter '<删尾注的脚本>' <最早受影响提交>^..HEAD
```

⚠️ 改写会使之后所有提交的 hash 变化，**若有发布标签指向这些提交，必须一并重指**：

```bash
git tag -f <标签名> <新提交sha>
git push --force origin <分支>
git push --force origin <标签1> <标签2> ...
```

处理完查 `repos/<owner>/<repo>/contributors` 确认只剩人类账号。

## 签名与密钥（转公开前必读）

本仓库的 APK 签名配置：

```
gradle.properties:
  RELEASE_STORE_FILE=release-testkey.jks       ← 相对 app/ 目录
  RELEASE_STORE_PASSWORD=<明文>
  RELEASE_KEY_ALIAS=android
  RELEASE_KEY_PASSWORD=<明文>

app/build.gradle → storeFile file(RELEASE_STORE_FILE)
                 → 实际使用 app/release-testkey.jks
```

**签名文件与口令均在仓库内，CI 无需配置任何 Secrets。**

### 风险提示

- **口令以明文提交在 `gradle.properties` 中**。仓库当前为私有，风险可控；
  但**若转为公开仓库，等于同时公开签名密钥的口令**。
- 该密钥库（`release-testkey.jks`）源自上游开源阅读项目的测试密钥，
  并非本仓库维护者的个人密钥。风险是「他人可签出同签名的包」，
  而非「个人身份泄露」。**因此本仓库产出的 APK 不应对外分发**。
- `app/release.jks` 为上游遗留文件，当前**未被任何配置引用**。
  保留作备用，勿随意删除——上游可能仍在使用。

### 若要转公开仓库

1. 生成自己的密钥库，替换 `RELEASE_STORE_FILE` 与口令
2. 将口令移出仓库（用 CI Secrets 或本地 `~/.gradle/gradle.properties`）
3. 确认 `release-testkey.jks` 是否仍需保留（涉及上游签名兼容性）

## 本仓库相对上游的改动范围

U阅 自 2026-10-09 从上游 `5aa885e` 起步，**改动刻意保持最小**，便于随上游更新时低成本合并。

### 代码改动（共 5 处）

| 文件 | 改动 |
|---|---|
| `ServerConfigDialog.kt` | 修输入行 `Missing required view with ID: editText` 崩溃（改为直接持有 binding，不走 root.id 反查） |
| `SourceLoginDialog.kt` | 同上，同类崩溃 |
| `ReadView.kt` | 修左右滑翻页误触朗读面板：纵向手势判定由 `absY >= absX * 0.9f` 收紧为 1.5 倍 |
| `LogUtils.kt` | 日志目录改存 `Download/uyue/logs`，无权限时回退私有缓存目录 |
| `values/strings.xml`、`values-zh/strings.xml` | 应用名 `Z·阅读` → `U阅`，另补若干字符串资源 |

### 非代码改动

- 删除上游根目录的非源码文件（文档、脚本、朗读脚本、书源、音效库等，共 420 项）
- 新增 CI 工作流、`CONTRIBUTING.md`、`scripts/`（钩子）

### 明确没改的部分

**UI 布局、图标、主题均未改动**，与上游一致。

> 注意：用 `git diff` 对比上游时，`res/drawable/*.xml`、`res/layout/*.xml` 等大量文件会显示「有差异」，
> 那**只是 CRLF/LF 换行符差异**，内容完全相同。判断真实改动时须加 `--ignore-cr-at-eol`：
>
> ```bash
> git diff --ignore-cr-at-eol <上游提交>:<路径> <路径>
> ```
