# JavaSecExpToolKit 多 Agent 运行手册

版本：1.0.0
更新日期：2026-09-21
适用读者：本仓库维护者
关联文档：`docs/AGENT-ROLES.md`（角色职责）、`docs/DESIGN-agents.md`（框架设计）、
`openspec/config.yaml`（强制规则）、`tools/agent.ps1`（启动脚本）

本手册只回答一件事：**怎样同时开多个 agent，让它们互不干扰又能配合**。
文中每条结论都在本仓库实测过，未实测的会明确标注。

---

## 一、结论速览

| 问题 | 结论 | 依据 |
| --- | --- | --- |
| 多个 agent 同时改代码会互相覆盖吗 | **不会** | 每个 agent 一个独立 Git worktree + 独立分支，实测在 worktree 内覆盖 `src/Main.java` 后主仓库该文件未变 |
| 多个 agent 能同时跑测试吗 | **能** | 全部自检用 `InetSocketAddress(host, 0)` 动态端口，并把 `user.home` 指向临时目录，实测无端口冲突、无配置互相污染 |
| 监督 agent 会不会改坏东西 | **不会** | 监督角色强制 `-s read-only`，实测写入被拒（连系统临时目录也拒） |
| agent 会自己提交吗 | **不会，由脚本代提交** | agent 退出后由 `tools/agent.ps1` 在沙箱外提交，并校验写入域 |
| 哪些必须串行 | UI agent、收尾三项（后者由脚本代理） | `src/Main.java` 是全部页面的唯一汇合点；`.backups/`、`AI_REPORT.md`、构建产物是共享资源 |

**一句话**：隔离靠 **worktree + 分支 + 沙箱**，配合靠 **Git 分支 + OpenSpec change**。

---

## 二、三种开启方式

### 2.1 方式一：交互式多开终端（人要盯着）

开 N 个终端窗口，每个窗口 `cd` 到**同一个 worktree** 后启动交互式会话：

```powershell
# 终端 1
git worktree add G:\java\jset-agents\probe-a -b agent/probe/probe-a
codex -C G:\java\jset-agents\probe-a

# 终端 2
git worktree add G:\java\jset-agents\exploit-a -b agent/exploit/exploit-a
codex -C G:\java\jset-agents\exploit-a
```

进入会话后，第一条消息自己写角色卡（内容可抄 `docs/AGENT-ROLES.md`）。
这种方式最灵活，但角色约束靠自觉，容易越界。

### 2.2 方式二：脚本化启动（推荐）

`tools/agent.ps1` 把「建 worktree + 建分支 + 套角色卡 + 定沙箱」一次做完：

```powershell
.\tools\agent.ps1 -Role probe -Slug version-blindspot -Task "补齐 1.2.73-1.2.80 的版本识别盲区"
```

脚本会：在 `G:\java\jset-agents\<role>-<slug>` 建 worktree（不污染主仓库）、
建分支 `agent/<role>/<slug>`、把角色卡与本次任务拼成提示词、按角色选沙箱、
把 agent 的最终回复写到临时目录，最后启动 `codex exec`。

脚本会**阻塞到 agent 结束**，所以「并发」= 同时开多个终端各跑一条命令。

**提交由脚本完成，agent 不自己提交**。原因见第四节：沙箱对共享的
`.git/objects` 只有部分写权限，agent 自行 `git commit` 会失败，
还可能在对象库里留下不可达对象。脚本在沙箱外提交，并做两件事：

- **打印本次改动的全部文件**，供人工核对；
- **机械校验写入域**：只要有一个文件不在该角色的写入域内，
  就整体撤出暂存、拒绝提交并列出越界文件。这条已实测：
  让 traffic 角色改 `python/` 下的文件，脚本拒绝提交；
  改成 `src/proxy/` 下的文件则正常提交。

### 2.3 方式三：会话延续与分叉

| 需求 | 命令 | 说明 |
| --- | --- | --- |
| 接着上次的会话继续 | `codex resume --last` | 交互式，恢复最近一次 |
| 接着指定会话继续 | `codex exec resume <session-id> "继续"` | 非交互式 |
| 从某个会话分叉出新的 | `codex fork --last` | 交互式 |
| 非交互式分叉 | `codex exec fork <session-id> "新任务"` | 两条线互不影响 |
| 用 Codex 自带的托管工作区 | `codex exec --enable worktrees --worktree -C <仓库> "任务"` | 实测工作区落在 `$CODEX_HOME\worktrees\<hash>\JavaSecExpToolKit`，**detached HEAD**，不能直接当分支合并 |

注意两点（实测）：

- `--worktree` 必须配 `--enable worktrees`，单独用会报
  `--worktree requires the worktrees feature`。
- 托管工作区是 detached HEAD。要合并回主线的场景，用方式一或方式二；
  托管工作区适合「一次性、不需要留分支」的探索任务。

---

### 2.4 超时上限（防止任务跑飞）

没有超时的会话会无限期占用终端，而且**拿不到任何产出**——本仓库实际发生过一次：
监督审计跑了约 15 分钟仍无结论，进程最终被回收，只留下提示词文件。

`tools/agent.ps1` 因此内置超时上限，超时后会终止 agent 及其子进程，
并保留现场与日志供判断进度。

| 角色 | 默认上限 | 依据 |
| --- | ---: | --- |
| 主 agent（orchestrator） | 45 分钟 | 规划件与集成工作量大 |
| 功能开发（probe / exploit / traffic） | 40 分钟 | 含实现与自检 |
| UI | 40 分钟 | 含三套界面自检 |
| 测试 | 40 分钟 | 全量回归 |
| 监督 | 60 分钟 | 需独立复算依赖图与构建时间 |

显式覆盖：

```powershell
# 任务确实更久，就显式调大（上限 600 分钟）
.\tools\agent.ps1 -Role supervisor -Slug audit-003 -Task "全量审计" -TimeoutMinutes 90
```

超时后的处置：

- 输出里会打印 `[超时]` 段与**日志尾部**（`jset-agent-log-<role>-<slug>.err.txt`），
  据此判断 agent 做到哪一步；
- **不要直接合并**：超时意味着结果不完整；
- 需续做时加 `-ReuseWorktree` 并缩小任务范围，或拆成更小的任务重新派发；
- 现场保留在对应 worktree 中（未提交），确认无用后按 5.4 清理。

**结论**：任何可能长时间运行的任务都必须有超时上限；这是默认行为，
不需要每次手动设置，仅在「任务确实更久」时才显式调大。

---

## 三、各角色启动命令

角色、写入域与验证入口见 `docs/AGENT-ROLES.md`。这里只给可直接复制的命令。

```powershell
# 功能开发：探测引擎（Python 侧）
.\tools\agent.ps1 -Role probe -Slug version-blindspot -Task "补齐 1.2.73-1.2.80 的版本识别盲区"

# 功能开发：利用链与 payload
.\tools\agent.ps1 -Role exploit -Slug payload-chain -Task "实现 payload 生成的链选择与预览"

# 功能开发：代理与抓包转换
.\tools\agent.ps1 -Role traffic -Slug header-tolerance -Task "补强抓包请求头容错解析"

# UI：界面与配置页
.\tools\agent.ps1 -Role ui -Slug payload-page -Task "新增 Payload 生成页并同步配置页"

# 测试：自检与回归
.\tools\agent.ps1 -Role test -Slug decoupling-cases -Task "补充依赖边界的环检测用例"

# 监督：只读审计（沙箱强制 read-only，无需手动指定）
.\tools\agent.ps1 -Role supervisor -Slug audit-002 -Task "审计本轮改动是否越界"

# 主 agent：自己动手时用它，或用交互式会话
.\tools\agent.ps1 -Role orchestrator -Slug plan-001 -Task "产出 payload 生成的 change 规划件"

# 只想看提示词、不真的启动
.\tools\agent.ps1 -Role probe -Slug demo -Task "示例任务" -DryRun
```

**并发示例**（两个终端，同时开）：

```powershell
# 终端 A
.\tools\agent.ps1 -Role probe   -Slug version-blindspot -Task "补齐版本识别盲区"

# 终端 B（与 A 同时跑）
.\tools\agent.ps1 -Role traffic -Slug header-tolerance  -Task "补强请求头容错解析"
```

两者写入域不重叠（`python/fj_probe.py` + `src/probe/Probe*.java` vs
`src/proxy/**` + `src/probe/CaptureBridge.java`），可安全并行。

> 注意：`src/probe/**` 同时出现在 probe 与 traffic 的写入域里，
> 并行时只允许 probe 改 `src/probe/Probe*.java`、traffic 改
> `src/probe/CaptureBridge.java`，不得交叉。

---

## 四、互不干扰：隔离机制与实测结论

### 4.1 四层隔离

| 层 | 机制 | 实测 |
| --- | --- | --- |
| 文件 | 每个 agent 一个 Git worktree | worktree 内覆盖 `src/Main.java`、新建文件，主仓库 `git status` 与本文件内容均不变 |
| 版本 | 每个 agent 独立分支 `agent/<role>/<slug>` | 两个 agent 并发提交，各得独立 commit，互不覆盖 |
| 权限 | 按角色选沙箱，监督强制 `read-only` | 只读会话写入被拒，且不会静默成功 |
| 运行时 | 自检全用动态端口 + 临时 `user.home` | `InetSocketAddress("127.0.0.1", 0)`、`ServerSocket(0)`；各 UI 自检把 `user.home` 指向临时目录 |

### 4.2 沙箱与 Git 权限（实测，务必按此配置）

worktree 的真实 Git 目录是 `<主仓库>\.git\worktrees\<name>\`，
对象库在 `<主仓库>\.git\objects\`，Git LFS 临时目录在 `<主仓库>\.git\lfs\`。
这三处都在工作区之外，默认沙箱都不放行，因此**直接开 agent 连 `git status`
都会失败**（实测报 `external filter 'git-lfs filter-process' failed`）。

`tools/agent.ps1` 为可写角色放行本次 worktree 的索引与 LFS 临时目录：

```powershell
--add-dir "<仓库>\.git\worktrees\<name>"   # 本 worktree 的索引与 HEAD
--add-dir "<仓库>\.git\lfs"                # Git LFS 临时目录
```

只读角色（监督）**不放行**任何 Git 目录，保持「只能读」。

| 沙箱配置 | 改文件 | `git status` / `diff` | `git add` / `commit` |
| --- | --- | --- | --- |
| 默认 `workspace-write` | 可以 | 失败（LFS 临时目录被拒） | 失败（`index.lock` 被拒） |
| 放行上述两个目录 | 可以 | 成功 | 不建议让 agent 做，见下 |
| `read-only` | 不行 | 成功 | 不行 |
| `danger-full-access` | 可以 | 成功 | 成功 |

**为什么不让 agent 自己提交**：对象库 `<仓库>\.git\objects` 是**全部
worktree 共享**的，放行它才能提交；而实测该目录下部分子目录的 ACL 并未
继承沙箱用户权限，agent 提交会**偶发失败**（报
`insufficient permission for adding an object to repository database`），
重试或修复 ACL 后又会把探针对象留在共享对象库里。

权衡后采用更可靠的做法：**agent 只改文件，提交由 `tools/agent.ps1`
在沙箱外完成**，并顺带做写入域校验。这样对象库完全不被 agent 触碰，
并发时也不会互相干扰。

### 4.3 六个共享资源与处置（如实说明）

worktree 隔离的是**工作区文件**，以下资源仍然是全仓库共享的，并发时是真冲突点：

| 共享资源 | 并发风险 | 处置 |
| --- | --- | --- |
| `.backups/` | 各 agent 各自备份 + 轮转删除，互相删掉对方的快照 | 并发时**不备份**，由主 agent 合并后统一执行 |
| `AI_REPORT.md` / `PROGRESS.md` | 同时追加会冲突 | 同上，主 agent 统一追加 |
| 根目录 `JavaSecExpToolKit.jar`、`target/`、`lib/` | `build.ps1` 路径写死在主仓库，并发构建互相覆盖 | 并发时**不构建**；构建属于收尾三项 |
| `src/util/**`、`src/config/**` | 共享内核，多角色都想改 | 由主 agent 单独开 change，串行修改 |
| Maven 本地仓库 `D:\develop\maven\apache-maven-3.9.4\mvn_repo` | 跨 worktree 共享，并发下载可能撞锁 | 依赖已离线缓存，正常不再下载；构建反正被收尾串行化 |
| `src/Main.java` | UI agent 独占，是全部页面的汇合点 | UI 改动串行执行 |

**规则**：并发期间各 agent 只在自己的分支提交**源码改动**；
**备份 / 写报告 / 构建校验三项一律不在并发时做**，合并后由主 agent 统一执行。
这条已写进 `tools/agent.ps1` 的提示词与 `openspec/config.yaml`。

### 4.4 怎么验证「确实没互相干扰」

```powershell
# 1) 应能看到每个 agent 的工作区与各自分支
git worktree list

# 2) 主仓库只应显示主 agent 自己的改动，不应出现子 agent 的文件
git status --short

# 3) 各分支的提交应互相独立
git log --oneline -1 agent/probe/version-blindspot
git log --oneline -1 agent/traffic/header-tolerance
```

---

## 五、互相配合：合并与收尾

### 5.1 流程

```
主 agent propose → 各执行角色（worktree 并行）→ 测试 agent → 监督 agent（只读）
                                                            ↓
                                      主 agent 合并 → 收尾三项 → archive + Git
```

### 5.2 合并

```powershell
# 先看这条分支带来了什么
git log --oneline main..agent/probe/version-blindspot

# 合并（保留分支历史，便于回溯）
git merge --no-ff agent/probe/version-blindspot
```

两条分支改了同一文件时，冲突由**主 agent** 解决，不交给子 agent 互相对抗。

### 5.3 收尾三项（合并后统一做）

1. 备份到 `.backups/`，仅保留最近三次；
2. 追加 `AI_REPORT.md`，更新 `PROGRESS.md`；
3. 重新构建并校验 JAR 构建时间晚于 `src/`、`python/`、`tests/` 全部源文件。

监督 agent 随后只读复核这三项，并验证本次 diff 未越出写入域。

### 5.4 清理

```powershell
git worktree remove G:\java\jset-agents\probe-version-blindspot --force
git worktree prune
git branch -d agent/probe/version-blindspot
```

---

## 六、排错

| 现象 | 原因 | 处置 |
| --- | --- | --- |
| `工作区已存在` | 上次的 worktree 没清理 | 加 `-ReuseWorktree` 继续用，或换 `-Slug` |
| `git worktree add 失败` | 分支名已存在 | 换 `-Slug`，或先 `git branch -D agent/<role>/<slug>` |
| agent 报告 `index.lock: Permission denied` | 沙箱未放行 worktree 的 Git 目录 | 用 `tools/agent.ps1` 启动；手工启动时自己加 `--add-dir` |
| agent 报告 `external filter 'git-lfs filter-process' failed` | 未放行 `.git\lfs` | 同上 |
| agent 报 `insufficient permission for adding an object` | agent 在自己提交（沙箱对 `.git\objects` 只有部分写权限） | 不要让 agent 提交；用 `tools/agent.ps1`，它在沙箱外提交 |
| `--worktree requires the worktrees feature` | 忘记开特性开关 | 加 `--enable worktrees` |
| 提示词里的中文变成 `?` | PowerShell 5.1 管道传参会按 ASCII 编码 | 把提示词作为**参数**传给 `codex exec`，不要走管道 |

---

## 七、已知限制

1. `tools/agent.ps1` 只在 Windows PowerShell 5.1 上实测过；脚本含中文，
   文件必须保持 **UTF-8 带 BOM**，否则解析出错。
2. 脚本只放行本次 worktree 的索引与 `.git\lfs`，
   对全仓库共享的 `.git\objects` **不授权**，因此 agent 无法提交，
   也无法在对象库里留下任何东西；代价是提交必须经过脚本。
   若手工启动 agent 并期望它自行提交，需自行放行 `objects` / `logs` / `refs\heads`，
   并接受上文描述的偶发失败风险。
3. 监督 agent 的只读沙箱**不能**执行 `git add` / `git commit`，
   这也是「它不会改坏仓库」的技术保证，而不只是纪律要求。
4. 并发上限仍是**两个执行角色**（写入域不重叠为前提），
   这不是脚本限制，而是为了让冲突面保持在可人工审阅的范围内。