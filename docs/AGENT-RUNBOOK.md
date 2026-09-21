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
| 需要多开终端吗 | **不需要** | 一个终端用 `Start-Process ... -WindowStyle Hidden` 后台并发多个角色，前台终端仍可用；实测杀掉一个不影响另一个 |
| 主 agent 能自动派发子 agent 吗 | **不能，但编排器可以代它派发** | 实测：agent 沙箱无法写 `.git/refs/heads/**`，`git worktree add` 报 `cannot lock ref`。改用 `tools/orchestrate.ps1` 在沙箱外完成「拆解 → 分批 → 派发 → 合并 → 汇报」，见四之三节 |
| 监督 agent 需要另开终端实时监督吗 | **不需要** | 它是阶段末的一次只读审计，不是实时监控；各角色已提交的分支它能直接读到 |
| 怎么分辨“在干活”与“卡住” | 看护 agent 读日志判三态 | 实测：静默 771 秒的正常等待判为 `WAITING` 不终止，反复重试同一命令判为 `STUCK` 并终止 |
| 能否只给一句任务就自动拆解分发 | **能** | `tools/orchestrate.ps1 -Goal "..."`：LLM 按角色矩阵产出计划，按依赖与写入域分批派发，成功后自动合并并汇报；端到端实测 4 步 3 批全绿 |
| 新派的角色能看到前一个角色的产出吗 | **不能，除非先合并** | 实测：`git worktree add` 从当前 HEAD 建，不合并就看不到前置分支的新文件；合并后可见 |
| 哪些必须串行 | UI agent、有依赖关系的任务、收尾三项（后者由脚本代理） | `src/Main.java` 是全部页面的唯一汇合点；`.backups/`、`AI_REPORT.md`、构建产物是共享资源 |

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

脚本会**阻塞到 agent 结束**，但并发不需要多开终端：
用 `Start-Process ... -WindowStyle Hidden` 后台启动即可（见 2.4 节）。

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

### 2.4 多终端还是单终端（实测）

**结论：不需要多开终端。** 方式二的脚本会阻塞到 agent 结束，
但只要用 `Start-Process` 后台启动，一个终端就能同时跑多个角色，
前台终端仍可继续下令：

```powershell
# 后台并发两个执行角色（写入域不重叠）
Start-Process powershell -ArgumentList @(
  '-NoProfile','-ExecutionPolicy','Bypass','-File','tools\agent.ps1',
  '-Role','probe','-Slug','version-blindspot','-Task','补齐版本识别盲区'
) -WindowStyle Hidden

Start-Process powershell -ArgumentList @(
  '-NoProfile','-ExecutionPolicy','Bypass','-File','tools\agent.ps1',
  '-Role','traffic','-Slug','header-tolerance','-Task','补强请求头容错'
) -WindowStyle Hidden

# 终端立即可用；看进度直接读日志
Get-Content "$env:TEMP\jset-agent-log-probe-version-blindspot.err.txt" -Tail 20
```

实测证据：

| 项 | 结果 |
| --- | --- |
| 一个终端同时后台跑两个角色 | 成功，前台终端未被占用，中途仍可执行其它命令 |
| 两个 worktree 目录 | 各自独立生成，互不可见 |
| 主仓库污染 | 无（`git status` 不受影响） |
| A 分支提交后，B 能否看到 | 看不到，B 的同名文件未变，主仓库也未变 |
| 杀掉 A，B 是否受影响 | 不受影响，B 继续跑到自然结束（退出码 0） |
| 两个 agent 同时提交 | 各自提交到自己分支，未出现 `index.lock` 冲突 |

**什么时候才值得多开终端**：只有当你想中途看到实时输出并手动插话时。
但脚本用的是 `codex exec`（非交互式）且 stdin 被重定向到提示词文件，
**会中途无法输入**；人能做的只有等、看日志、或终止（终止后以 `-ReuseWorktree` 接着上）。
真要人工实时接管，用方式一的交互式会话，代价是角色约束靠自觉。

---

### 2.5 超时上限（防止任务跑飞）

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

### 2.6 看护 agent：区分「推进中」「长等待」与「卡死」

超时上限只能回答「进程有没有退出」，回答不了「它是在干活还是卡住了」。
实测证据：执行 `ping -n 400` 时日志会静默数分钟且毫无新增，但这是**正常等待**；
若按「静默多久」一刀切就会误杀正常会话。

因此 `tools/agent.ps1` 在等待期间轮询日志，出现连续静默时唤起**只读看护 agent**
读日志尾部（默认 120 行）判定，输出三态：

| 结论 | 含义 | 启动脚本的动作 |
| --- | --- | --- |
| `PROGRESSING` | 有推进痕迹（新的工具调用、新的输出块） | 继续等待 |
| `WAITING` | 在等一个明确的耗时操作（长命令、完整测试、构建） | 继续等待，**不终止** |
| `STUCK` | 反复重试同一命令、重复同一错误、无等待对象且零输出 | 终止会话及子进程，保留现场与日志 |
| `UNKNOWN` | 判定不可用（输出无法解析、看护自身超时） | 保守继续等待，由总超时兜底 |

**判定方不终止进程**：看护 agent 以只读沙箱运行，没有杀进程的权限；
终止由持有进程句柄的启动脚本执行。这是设计与权限的双重保证。

相关参数：

```powershell
# 每 60 秒检查一次，连续 2 轮日志无变化才判定（默认值，用 -PollSeconds / -StallRounds 调整）
.\tools\agent.ps1 -Role probe -Slug demo -Task "示例任务"

# 关闭看护判定，回到「只有总超时」的行为
.\tools\agent.ps1 -Role probe -Slug demo -Task "示例任务" -NoWatchdog

# 对任意日志手动跑一次判定（可独立使用，不启动 agent）
.\tools\watchdog.ps1 -LogPath "$env:TEMP\jset-agent-log-probe-demo.err.txt"

# 只看结论，便于脚本读取
.\tools\watchdog.ps1 -LogPath <日志> -Quiet
```

实测定论（两种样本对比）：

| 样本 | 日志特征 | 判定 | 结果 |
| --- | --- | --- | --- |
| 真实长等待 | 执行 `ping -n 400`，静默 771 秒，日志写明在等待该命令 | `WAITING` | 未终止，继续等待 |
| 合成真卡死 | 同一命令连续 5 次、同一错误重复、无等待对象 | `STUCK` | 已终止进程树 |

端到端链路实测（用真实 `tools/agent.ps1` + 冻结日志的假会话，而非单测函数）：

| 场景 | 观察到的行为 | 耗时 |
| --- | --- | --- |
| 冻结「反复重试同一命令」日志 | `[看护] 日志已静默 1 轮` → `[看护] 判定结论：STUCK` → `[卡死] ... 正在终止` | 30 秒 |
| 冻结「长等待」日志 | 连续 3 次 `WAITING`，**未误杀**，最后由总超时兜底终止 | 72 秒 |
| 加 `-NoWatchdog` | 完全不调用看护，行为回到纯超时 | 73 秒 |

判定方本身的侧效应也已实测：

| 输入 | 结果 |
| --- | --- |
| 日志写明在等 `ping -n 400` | `WAITING`（理由指向那一行） |
| 日志三轮重试同一命令 + 同一错误 | `STUCK`（理由为重试模式本身，非静默时长） |
| 判定输出里没有 `VERDICT` 行 | `UNKNOWN`（不崩溃、不误判为 `STUCK`） |
| 日志文件不存在 | `UNKNOWN` 且退出码 2 |

注意：`STUCK` 判定需要“静默到一定程度”才会被唤起。真实 codex 会话在长等待期间会持续输出心跳文本，
日志一直在增长，因此端到端跑真实 agent 时看护通常不会触发——这是正确行为，
不是缺陷；验证停滞检测本身必须用冻结日志的方式。

判定历史会打印在卡死提示里（例如 `WAITING -> WAITING -> STUCK`），
便于事后回溯为什么做了这个决定。

---

## 二之二、监督 agent 的接入点（实测）

**结论：监督 agent 不是实时监督程序，而是阶段末的一次只读审计。**

原因有三层：

1. **它没有实时信源**。它的只读沙箱只能读文件，无法流式读取另一个会话的输出；
   执行中的会话在自己的 worktree 里，尚未提交的内容它看不到。
2. **它的判定依据是整体状态，不是时序**。十项清单里的断言数量对比、JAR 时间校验、
   空实现检测，都需要待产出落定后才成立。
3. **真正做实时入供断定的是看护 agent**（见 2.6 节）：它读会话日志，
   在会话出现静默时判断「推进中 / 长等待 / 卡死」。两者职责不同，不要混用。

### 它能看到什么（实测）

监督 agent 直接在**主仓库**以 `read-only` 运行，不建 worktree。
实测：其提示词写的是「审计对象就是当前工作区的未提交改动」，
但仓库对象库是共享的，因此它**同时能读到各执行角色已提交的分支**：

```powershell
# 监督 agent 在主仓库里实际可用的命令
git log --oneline main..agent/probe/<slug>
git diff --stat main..agent/probe/<slug>
git show agent/probe/<slug>:python/fj_probe.py
```

所以审计建议在**执行角色已提交但尚未合并**时进行：此时分支与主线的差异就是完整的待审内容。
若已合并，审计对象变成主干的新提交，需明确告诉它对比哪两个点。

### 推荐的步骤

```powershell
# 1) 执行角色先在自己分支提交（脚本代提交）
.\tools\agent.ps1 -Role exploit -Slug payload-core -Task "按 tasks.md 实现通用载荷包"

# 2) 只读审计（不新开终端，可后台）
.\tools\agent.ps1 -Role supervisor -Slug audit-payload \`
  -Task "审计 agent/exploit/payload-core 是否越界"

# 3) 审计结论为「可归档」后再合并
git merge --no-ff agent/exploit/payload-core
```

**不要用监督 agent 取代回归测试**：它只读且不跑构建，
事实性结论靠它独立复算，而「能不能跑」靠你在主干跑自检。

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

## 四之二、逐条派发（实际工作流）

派发权在沙箱外的 `tools/agent.ps1`，每条命令只派一个角色。
一个 change 的完整流程是四步：

```
主 agent propose  →  执行角色（可并发）  →  测试角色  →  监督角色  →  主 agent 收尾
```

### 关键约束：新派的角色只看得到当前 HEAD（已实测）

`git worktree add` 从**当前 HEAD**建工作区。实测：

| 步骤 | 结果 |
| --- | --- |
| 在 `agent/exploit/dep-a` 里提交 `src/payload/PayloadEngine.java` | 提交成功 |
| 立即从 main 派发下一个角色 | 新 worktree 里 **看不到** 那个文件 |
| 先 `git merge --no-ff agent/exploit/dep-a` 再派 | 新 worktree 里**能**看到 |

**因此**：有依赖关系的任务必须**先合并前置分支，再派后续角色**；
无依赖的任务才能并发。例如 `payload-generation` 的第 4、5 组依赖第 1–3 组的新包，
就不能与之并发；而第 4 组（改 `tests/test_decoupling.py`）与第 1–3 组写入域不重叠，
可以并发（只要第 4 组不依赖新包已存在——实际上它会，所以仍应串行）。

### 逐条派发的四条命令

```powershell
# 第 1 条：主 agent 产出 change（若已有 change 则跳过）
.\tools\agent.ps1 -Role orchestrator -Slug payload-plan \`
  -Task "用 OpenSpec propose 产出 payload 生成的 change 规划件" -TimeoutMinutes 15

# 第 2 条：执行角色（tasks.md 里标了写入域与角色）
.\tools\agent.ps1 -Role exploit -Slug payload-core \`
  -Task "按 openspec/changes/payload-generation/tasks.md 第 1-3 组实现" -TimeoutMinutes 40

# 第 3 条：测试角色（此时前置已合并，它能看到新包）
.\tools\agent.ps1 -Role test -Slug payload-checks \`
  -Task "按 tasks.md 第 4-6 组补齐自检与回归" -TimeoutMinutes 40

# 第 4 条：监督只读复核
.\tools\agent.ps1 -Role supervisor -Slug audit-payload \`
  -Task "按十项清单复核本轮改动，给出阻断结论" -TimeoutMinutes 60
```

每条命令之间的人工动作只有三件：读结果、合并、测关。

```powershell
# 看这条分支带来了什么（脚本已代提交）
git log --oneline main..agent/exploit/payload-core

# 合并（前置完成后才能派下一个角色）
git merge --no-ff agent/exploit/payload-core

# 清理工作区与分支
git worktree remove G:\java\jset-agents\exploit-payload-core --force
git worktree prune
git branch -D agent/exploit/payload-core
```

### 一条派发命令内部发生什么

| 阶段 | 该角色做什么 | 你需要做什么 |
| --- | --- | --- |
| 启动 | 建 worktree（`G:\java\jset-agents\<role>-<slug>`）与分支（`agent/<role>/<slug>`），套角色卡 | 无 |
| 运行 | 改代码，不自己提交 | 可等待，或读实时日志看进度 |
| 静默时 | 看护 agent 读日志判三态 | 无（自动） |
| 退出 | 脚本在沙箱外**校验写入域后提交** | 看“agent 改动的文件”清单，确认无越界 |
| 结束后 | — | 合并、测关、清理、派下一条 |

**写入域校验是硬门槛**：只要有一个文件不在该角色写入域内，
脚本就整体撤回暂存、拒绝提交并列出越界文件。

---

## 四之三、自动拆解编排（主 agent 代拆任务）

逐条派发要求人先读懂 tasks.md、自己判断依赖与写入域冲突，再依次敲命令。
`tools/orchestrate.ps1` 把这件事交给 LLM：**你只给一句目标，它自己拆解、分批、派发、合并、汇报**。
这就是你前面问的「主 agent 能否自动拆解任务并分发给不同角色」——能，但形态是**脚本里的编排器**，
不是让主 agent 去当调度器（原因见第七节第 5 条：主 agent 的沙箱建不了 worktree）。

```powershell
# 只看它会怎么拆，不真的执行
.\tools\orchestrate.ps1 -Goal "补齐 fastjson 1.2.73-1.2.80 的版本识别盲区" -DryRun

# 真正执行：拆解 → 分批 → 并发派发 → 自动合并 → 汇报
.\tools\orchestrate.ps1 -Goal "补齐 fastjson 1.2.73-1.2.80 的版本识别盲区"
```

### 它内部做什么

| 阶段 | 动作 | 失败时 |
| --- | --- | --- |
| 1 拆解 | 以**只读**会话让 LLM 按角色矩阵产出 JSON 计划（角色、slug、任务、dependsOn） | 超时即报错退出，不留半成品 |
| 2 校验 | 角色必须存在、slug 合规且唯一、依赖必须指向本计划内的 slug、步数不超 `-MaxSteps`、依赖不许成环 | 校验不过则**不创建任何 worktree** |
| 3 执行 | 按「依赖 + 写入域冲突」分批；同批先串行预建 worktree，再并行启动各角色 | 单步失败只影响该步，其余照常 |
| 4 汇报 | 逐步列出「已合并 / 无需改动 / 需人工处理」，附提交与残留现场，写入 `%TEMP%\jset-orchestrate-<runId>.md` | — |

分批规则就是 `tools/lib/RoleMatrix.ps1` 的冲突判定：写入域证不出不相交就**串行**（宁可慢，不可撞）。
`probe` 与 `traffic` 可同批（`src/probe/Probe*` 与 `src/probe/CaptureBridge.java` 文件名层面不相交），
`ui` 与谁都不同批（`src/Main.java` 是唯一汇合点）。

### 常用参数

| 参数 | 用途 |
| --- | --- |
| `-Goal` | 必填，一句话目标，自然语言 |
| `-DryRun` | 只拆解并打印计划，不建 worktree、不启动 agent |
| `-NoMerge` | 真跑但不自动合并，全部留给人工审阅 |
| `-MaxSteps` | 步数上限（默认 6），防止计划失控 |
| `-StepTimeoutMinutes` | 单步超时上限；0 表示用该角色的默认预算（见 `RoleMatrix.ps1`） |
| `-PlanTimeoutMinutes` | 拆解会话的超时上限（默认 8 分钟） |
| `-SkipSupervisor` | 不追加监督复核步骤 |
| `-PlanFile` | 直接用现成 JSON 计划（调试或复跑同一计划） |

### 自动合并的安全边界

**它不会为了让流程跑完而合并可疑产出。** 只有以下条件全部满足才自动合并：

- agent 正常退出、未超时、未被看护判为卡死；
- 改动全部落在该角色写入域内（越界会被 `agent.ps1` 拒绝提交，退出码 3）；
- 分支上确实产生了新提交。

任一不满足就记「需人工处理」并**保留分支与 worktree**，合并冲突时执行 `git merge --abort`，不留半个合并状态。
收尾三项（备份 / `AI_REPORT.md` / `PROGRESS.md` / 构建校验）不在编排器里做，仍由主 agent 合并后统一执行——
它们是全仓库共享资源，并发期间碰会互相覆盖。

### 端到端实测（本仓库，用假 codex 替代真实 LLM）

计划 4 步 3 批：`probe` ∥ `traffic` → `test` → `supervisor`。

| 观测项 | 结果 |
| --- | --- |
| 分批 | 4 步分成 3 批，同批两个角色并行启动 |
| 合并 | 3 个执行步全部自动合并，各自 `--no-ff` 产生合并提交 |
| 监督步 | 在主仓库以 read-only 运行，退出码 0，不建 worktree |
| 清理 | 结束后 `git worktree list` 只剩主仓库，`agent/*` 分支为空 |
| 主仓库状态 | 结束后工作区干净 |
| 汇报 | 写出逐步表格与汇总，列出「已合并 3 / 需人工处理 0」 |
| 外层退出码 | 0 |

只读审计步骤通过 `-SkipSupervisor` 可跳过；`-NoMerge` 与 `-DryRun` 也各自实测过。

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
| 脚本启动后立即报 `value 0 is not a valid value for the StallRounds variable` | 计数器变量与 `-StallRounds` 参数同名（PowerShell 变量名大小写不敏感），赋值 `0` 会触发参数校验 | 已修复：计数器改名为 `$stallStreak` |
| `-NoWatchdog` 不生效 | 判断处写成了未赋值的 `$Watchdog` | 已修复：改为 `$NoWatchdog` |
| `tools/lib/CodexCli.ps1` 无法入库 | `.gitignore` 的 `lib/` 未锚定根目录，兼容忽略了 `tools/lib/` | 已修复：改为 `/lib/` |
| 会话被看护误判为卡死而终止 | 日志不足以看出等待对象 | 调大 `-StallRounds`，或用 `-NoWatchdog` 关闭判定 |
| 看护判定总返回 `UNKNOWN` | 判定方输出未含 `VERDICT` 行，或看护自身超时 | 用 `tools/watchdog.ps1 -LogPath <日志>` 单独跑一次看原因；此时不会误杀，由总超时兜底 |
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
5. **主 agent 不能自行派发子 agent**（已实测，见下）。沙箱给了本次 worktree、
   `.git\worktrees\<name>` 与 `.git\lfs`，但 `.git\refs`、`.git\logs`、`.git\objects` 不在内，
   因此 `git worktree add`（需新建分支引用）会报
   `fatal: cannot lock ref 'refs/heads/agent/...': unable to create directory`。
   同时 `G:\java\jset-agents\` 对 agent 只读，连子 worktree 目录也建不出来。
   **这是设计上的隔离而非缺陷**：写入域与并发度由脚本集中掌控，
   避免 agent 自行无限分裂出不受控的工作区；代价是派发动作必须由人（或主 agent）在终端发起。
   若要改成能自行派发，需额外放行 `.git\refs\heads\agent\**`、`.git\logs\**`
   与 `G:\java\jset-agents\`，代价是隔离性下降。