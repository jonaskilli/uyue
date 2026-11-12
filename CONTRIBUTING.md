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

本仓库源自 `cnb.cool/mingwuyan/iyuedu` 的 `yuedutts` 分支（下文称上游）。
两仓**内容同源但历史独立**：本仓库是 10-09 从上游某时点拷贝出来单独 `git init` 的，
因此没有共同祖先。直接 `git merge` 会把同名文件两两对比，产生数百个假冲突。

### 一次性准备（已完成，换机器才需重做）

1. **建立共同祖先**。找到本仓库首次提交的内容对应上游哪个提交（当前是 `5aa885e`，
   10-09「视频下载容错」），用 graft 给根提交补上这个父：

   ```bash
   git fetch /root/workspace/yuedutts '+refs/heads/yuedutts:refs/remotes/cnbsrc/yuedutts'
   git fetch /root/workspace/yuedutts 5aa885eb616cc1f795a999876b4c2886d1a4f4cc
   git replace --graft e714c3736759f92a6b6ee376ca8b97817e0385d2 5aa885eb616cc1f795a999876b4c2886d1a4f4cc
   ```

   `e714c37` 是本仓库**原始的**首次提交（`U阅 首次提交`）。建 graft 后，
   `git rev-list --max-parents=0 master` 会显示为 cnb 的根 `3a464bd`——这是正常的，
   说明历史已接上。用 `git replace -l` 可查当前被替换的提交。

   ⚠️ **前提是本地仓库不是浅克隆**。本仓库曾因浅克隆导致 `merge-base` 算不出共同祖先，
   表现为 graft 建好了、`merge-base` 仍返回空、冲突数不降。
   先确认 `git rev-parse --is-shallow-repository` 为 `false`，否则先 `git fetch --unshallow`。

2. **验证**：`git merge-base master cnbsrc/yuedutts` 应输出 `5aa885e`。

### 每次上游更新后的流程

```bash
cd /root/workspace/uyue-gh

# 1. 拉上游最新（本地 cnb 克隆是完整历史，用它当中转）
git fetch /root/workspace/yuedutts '+refs/heads/yuedutts:refs/remotes/cnbsrc/yuedutts'

# 2. 合并
git merge cnbsrc/yuedutts
```

3. **解决冲突**。正常情况下冲突只会出现在两边都改过的文件（本次实测仅 2 个）。
   裁决原则：
   - 上游的改动更严谨更安全（如异常兜底、边界校验）→ 采纳上游
   - 本仓库有意删除的东西（如清理掉的杂物目录）→ 维持删除
   - 两边都改了同一处逻辑 → 逐行看语义，不无脑选一边

4. **清理非源码文件**。上游根目录的朗读脚本、书源、插件配置、`docs/`、
   `scene-effects/` 等会随合并带入。`.gitignore` 已挡住大部分，但它只对新文件生效；
   **上游若「修改」了已被删除的文件，merge 仍会把它带回**，需手工再清一遍：

   ```bash
   git diff --name-only --diff-filter=A <合并前> HEAD | grep -vE '^(app|modules|gradle)/'
   # 逐个确认后用 git rm 删除
   ```

5. **编译验证**。push 后等 `Build Test` 跑绿再算完成。合并带来的上游代码可能依赖
   本仓库没有的接口，**编译结论以 CI 为准，不看本地**（本机无 JDK）。

6. **提交与推送**。合并提交的信息写明「合并上游至 <上游sha>」，清理单独一笔提交。

### 判据速查

| 现象 | 原因 |
|---|---|
| 冲突数百个 | 没建 graft，或 graft 建了但本地是浅克隆 |
| `merge-base` 返回空 | 同上 |
| 合并后多了朗读脚本/md 文档 | 上游修改了已删文件，需手工清 |
| 编译突然报找不到类 | 上游删了本仓库依赖的接口，看 CI 日志后补适配 |

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
