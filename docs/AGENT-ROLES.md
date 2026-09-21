# JavaSecExpToolKit 多 Agent 职责说明

版本：1.0.0
更新日期：2026-09-21
适用读者：本仓库维护者
关联文档：`docs/AGENT-RUNBOOK.md`（运行手册，启动命令与实测结论）、
`docs/DESIGN-agents.md`（框架设计）、`openspec/config.yaml`（强制规则）、
`openspec/specs/codebase/dependency-boundary/spec.md`（依赖边界契约）

---

## 一、角色总览

| 角色 | 写入域 | 核心产出 | 完成标志 |
| --- | --- | --- | --- |
| 主 agent | `AGENTS.md`、`README.md`、`README.en.md`、`PROGRESS.md`、`AI_REPORT.md`、`build.ps1`、`run.ps1`、`tools/*`、`docs/*`、`openspec/*`、`src/config/*`、`src/util/*`、`src/pom.xml` | change 全生命周期、集成、Git | change 归档且校验通过 |
| 功能开发 probe | `python/fj_probe.py`、`src/probe/Probe*` | 探测引擎改动 | `test_probe.py` 全绿 |
| 功能开发 exploit | `src/shiro/*`、`src/payload/*`、`src/service/*`、`src/preset/*` | 利用链、载荷生成与恶意服务器 | `ShiroCheck` 全绿 |
| 功能开发 traffic | `src/proxy/*`、`src/probe/CaptureBridge.java` | 代理与抓包转换 | `ProxyServerCheck` 全绿 |
| UI | `src/ui/*`、`src/Main.java` | 界面装配与交互 | 三个 `Ui*Check` 全绿 |
| 测试 | `tests/*` | 自检与回归 | 全部自检通过 |
| 监督 | **无**（只读） | 审计报告 | 报告含明确阻断结论 |
| 看护 | **无**（只读） | 停滞判定结论 | 输出 PROGRESSING / WAITING / STUCK |

功能开发拆成三个而不是一个，依据是写入域零重叠、验证入口互不包含（见 `DESIGN-agents.md` 第三节）。

### 1.1 角色标识与中文显示名

汇报里一律用**中文显示名**，标识只用于分支名、worktree 目录、写入域与 `-Role` 参数：

| 角色标识 | 中文显示名 | 用在哪 |
| --- | --- | --- |
| `orchestrator` | 主 agent | `-Role orchestrator`、写入域校验、汇报 |
| `probe` | 探测 agent | 同上 |
| `exploit` | 利用链 agent | 同上 |
| `traffic` | 抓包 agent | 同上 |
| `ui` | 界面 agent | 同上 |
| `test` | 测试 agent | 同上 |
| `supervisor` | 监督 agent | 同上 |
| `watchdog` | 看护 agent | 同上 |

映射表在 `tools/lib/RoleMatrix.ps1` 的 `Get-RoleDisplayNames` / `Get-RoleDisplayName`。
未登记的角色原样回退成标识，避免漏登记让汇报丢信息；显示名一律写作
`主 agent（orchestrator）` 这种「显示名（标识）」形式，两套名字不互相替代——
把显示名当参数传给 `-Role` 会被参数校验直接拒绝。

---

## 二、主 Agent（orchestrator）

**负责**

- 用 OpenSpec 的 `propose` 产出 change 规划件（proposal / specs / design / tasks）。
- 判定一次改动是否跨写入域；跨域时拆成多个 change。
- 集成各角色的产出，执行 `openspec validate --all --strict`。
- 归档 change，更新主 spec，执行 Git 提交与推送。
- 维护根文档：`AGENTS.md`、`README.md`、`README.en.md`、`PROGRESS.md`、`AI_REPORT.md`。
- 独占共享内核（`src/config/**`、`src/util/**`）与 `src/pom.xml` 的写入域：

**不负责**

- 不写功能代码。唯一可直接编辑的代码文件是 `AGENTS.md`、构建脚本，以及共享内核 `src/config/**`、`src/util/**`。
- 不代替测试 agent 判断测试是否通过。

**交付物**：`openspec/changes/<name>/` 四件套、归档目录、Git 提交记录。

**关键约束**：`propose` 只产出规划件，不写代码；实施必须由新的一次请求触发 `apply`。

**能力边界：主 agent 不能自己派发子 agent（已实测）。**
它的沙箱只放行本次 worktree、`.git\worktrees\<name>` 与 `.git\lfs`，
`.git\refs`、`.git\logs` 不在内，因此 `git worktree add`（需新建分支引用）
会报 `fatal: cannot lock ref 'refs/heads/agent/...': unable to create directory`；
`G:\java\jset-agents\` 对它也是只读，子 worktree 目录建不出来。

**因此派发动作的真实形态是**：人在终端调用 `tools/agent.ps1`，
由脚本（在沙箱外）分配 worktree 与分支并启动对应角色；
主 agent 负责的是**规划、判定是否跨域、集成与收尾**，不是当调度器。
详见 `docs/AGENT-RUNBOOK.md` 第二之二节与第七节。

**但「一句话任务自动拆解分发」这件事是可用的**：`tools/orchestrate.ps1` 在沙箱外代主 agent 完成
「拆解 → 校验 → 分批 → 派发 → 合并 → 汇报」。拆解由一次只读 LLM 会话按角色矩阵产出 JSON 计划，
派发与合并仍由脚本执行，主 agent 的沙箱限制不构成阻碍。
见 `docs/AGENT-RUNBOOK.md` 四之三节。

---

## 三、功能开发 Agent（probe / exploit / traffic）

三个角色共用同一套职责模型，仅写入域与验证入口不同。

**负责**

- 按 tasks.md 逐条实现，只改写入域内的文件。
- Bug 修复前先写出根因（Root Cause）分析，禁止试错式修改。
- 为自己的改动补齐或更新对应自检。
- 新增可持久化配置时，自己不写配置项：向主 agent 提出「共享内核 + 配置页」的需求，
  由主 agent 开 change 改 `src/config/AppConfig.java` 的键，由 UI 角色改 `src/ui/ConfigPage.java` 的分组。
  这两个路径都不在功能开发角色的写入域内，硬性越界会被拒绝提交。

**不负责**

- 不改 `src/ui/**`（界面外观归 UI agent）。
- 不改 `tests/**`（断言归测试 agent）。
- 不改配置项键名（避免让既有用户配置失效）。
- 不改 `src/util/**`、`src/config/**` 与 `src/pom.xml`（共享内核，需主 agent 单独开 change）。
- 不自行 `git commit`：沙箱对共享的 `.git/objects` 只有部分写权限，自行提交会失败，
  且可能在对象库留下不可达对象。提交由 `tools/agent.ps1` 在沙箱外统一完成。

**交付物**：可编译的源码改动 + 自检通过输出。

| 角色 | 写入域 | 验证命令 |
| --- | --- | --- |
| probe | `python/fj_probe.py`、`src/probe/Probe*` | `python -m unittest discover -s tests` |
| exploit | `src/shiro/*`、`src/payload/*`、`src/service/*`、`src/preset/*` | `ShiroCheck` |
| traffic | `src/proxy/*`、`src/probe/CaptureBridge.java` | `ProxyServerCheck` |

---

## 四、UI Agent

**负责**

- `src/ui/**` 的视图构建与 `src/Main.java` 的页面装配。
- 把已有引擎能力接线到界面，落实「可长期保存的配置进配置页」。
- 保持既有交互惯例：表单固定高度、输出区占剩余空间、一屏可见。

**不负责**

- 不改引擎行为。界面只调用既有引擎接口，不修改其逻辑。
- 不改 `tests/**`，即使界面上线导致自检失败也应由测试 agent 判定。

**交付物**：界面改动 + `UiNavigationCheck` / `UiShiroCheck` / `UiSwitchEndToEndCheck` 通过输出。

**特别说明**：`src/Main.java` 是全部页面的唯一汇合点，UI 角色的改动应串行执行，不与其它角色并行。

---

## 五、测试 Agent

**负责**

- 维护 `tests/**`：Python 单元测试与 Java 自检。
- 为新增功能或修复的 Bug 补充对应断言。
- 维护可机械执行的边界检查（如依赖边界自检）。
- 给出失败清单与最小复现步骤。

**不负责**

- 不为让测试通过而放宽断言。断言失败时向对应功能 agent 报缺陷，而不是改断言。
- 不实现功能代码。测试中发现的问题交回对应角色修复。

**交付物**：自检结果清单（通过/失败）+ 失败项的最小复现步骤。

**关键约束**：断言数量只增不减；删减断言须给出理由并经主 agent 确认。

---

## 六、监督 Agent（只读）

**负责**

- 独立复算结论，不复用执行者的判断。
- 按十项清单审计每次 change（见下）。
- 输出固定三段报告：通过项、发现问题、阻断结论。

**不负责**

- **不直接写仓库文件**。这是硬约束，也是它能保持独立的前提：
  报告原文由主 agent 追加进 `AI_REPORT.md`，并标注「监督 agent 提供，主 agent 落盘」。
- 不参与方案设计，不代替主 agent 决策。

**交付物**：写入 `AI_REPORT.md` 对应章节的审计报告。

### 6.1 十项审计清单

| # | 审计项 | 判定方法 |
| ---: | --- | --- |
| 1 | 写入域合规 | 本次 diff 的全部路径与写入域矩阵对照，列出越界项 |
| 2 | 范围合规 | 是否改了 tasks.md 之外的功能或配置项 |
| 3 | 承诺兑现 | tasks.md 每条任务是否有对应改动，有无勾选但未实现 |
| 4 | 空实现检测 | 全量搜索 `TODO`、`FIXME`、`pass`、`此处省略`、空方法体、返回常量桩 |
| 5 | 断言完整性 | 对比改动前后断言数量，只减不增视为放宽断言 |
| 6 | 依赖合规 | `src/pom.xml` 与 Python 侧未引入新第三方依赖 |
| 7 | 构建一致性 | 自行枚举 `src/`、`python/`、`tests/` 全部源文件，与 JAR 的 `LastWriteTime` 比较 |
| 8 | 文档同步 | `README.md`、`README.en.md`、`PROGRESS.md`、`AI_REPORT.md`、相关 `docs/DESIGN-*.md` 是否同步 |
| 9 | 配置落页 | 新增可持久化配置项是否已进配置页（`AppConfig` 键 + `ConfigPage` 分组） |
| 10 | 测试真实性 | 自检是否指向真实行为而非桩 |

### 6.2 解耦专项审计

这是监督 agent 的常设任务，用于判断代码是否被正确解耦。
判定标准分三类，**不要求为解耦而解耦**：

| 判定 | 标准 | 本仓库实例 |
| --- | --- | --- |
| 必须解耦 | 通用机制被具体功能模块持有，且已预见复用需求 | 通用链引擎曾依赖 Shiro 的 Base64 工具 |
| 可不改 | 单向依赖、不构成环、不阻碍复用 | `ui` 引用 `ShiroExploit.ChainKind`、`FlowRenderer` 引用 `ProxyServer.HttpFlow` |
| 不做 | 职责本身要求依赖多方 | `Main` 对全部模块的装配依赖 |

复核方法（必须自己跑，不得引用执行者结论）：

```
python -m unittest tests.test_decoupling
```

该自检把边界规则固化为断言，覆盖四项：包级无环、符合声明分层、
通用组件不依赖具体功能模块、共享内核单向被依赖。

**审计结论的写法要求**：每项须给出文件路径与行号、问题类型、建议处置；
不得只写「已检查，无问题」。

---

## 六之二、看护 Agent（只读）

**负责**

- 依据会话日志内容，判定该会话处于推进、长等待还是卡死。
- 输出三态结论：`PROGRESSING` / `WAITING` / `STUCK`，并给出判定依据。

**不负责**

- **不终止被判定的会话**：终止只由持有进程句柄的启动脚本执行。
  技术保证有两层：`watchdog.ps1` 不接受任何进程标识参数（拿不到对象），
  且它在只读沙箱中运行（无法修改仓库）。这两点由 `tools/check_agent_tools.ps1`
  逐读代码校验，而不依赖声明。
- 不修改任何文件，不代替执行者继续任务。

**关键约束**：判定必须区分「长等待」与「卡死」。
实测：执行 `ping -n 400` 时日志会静默数分钟且毫无新增，
这属于正常等待；若按「静默时长」一刀切就会误杀。

**交付物**：一行机器可读结论 `VERDICT: <状态>` 加两句判定理由。

运行方式：由 `tools/agent.ps1` 在会话出现静默时自动唤起，
也可用 `tools/watchdog.ps1` 对任意日志手动跑一次（见运行手册）。

---

## 七、协作顺序

```
主 agent propose                    （产出 change 四件套，不写码）
        ↓
执行角色 apply                      （功能 agent / UI agent 按 tasks.md 实施）
        ↓
测试 agent 回归                     （给出通过/失败清单）
        ↓
监督 agent 审计                     （只读复核，给出阻断结论）
        ↓
主 agent archive + Git              （归档、合并 spec、提交推送）
```

### 7.1 交接物

| 交接物 | 方向 | 内容 |
| --- | --- | --- |
| change 目录 | 主 → 执行 | 做什么、验收标准、写入域 |
| 源码改动 | 执行 → 测试 | 可编译的实现 |
| 自检结果 | 测试 → 主 | 通过/失败清单 |
| 审计报告 | 监督 → 主 | 越界、空承诺、断言放宽等问题 |

### 7.2 禁令

- 禁止口头交接（如「我改好了，你看着办」）：必须落成自检输出或文件。
- 禁止执行者自行宣布完成：完成标志是自检全绿 **且** JAR 时间校验通过。
- 禁止跳过 change 直接改码。

### 7.3 并发

主 agent 同时最多派发**两个**执行角色，且写入域不得重复。
三个功能 agent 之间天然无重叠可并行；UI agent 因独占 `Main.java` 应串行。

隔离手段与实测结论见 `docs/AGENT-RUNBOOK.md`：每个 agent 一个独立
worktree 与分支，并发时互不可见；但 `.backups/`、`AI_REPORT.md`、
`PROGRESS.md` 与构建产物是全仓库共享资源，**并发期间一律不碰**。

### 7.4 分歧仲裁

以 `docs/DESIGN.md` 与 `openspec/specs/` 的主 spec 为准；
两者都未覆盖的，暂停并交维护者裁决，不允许自行发明约定。

---

## 八、收尾三项（由主 agent 统一执行）

执行角色在 worktree 内**不做**下列三项：它们面向全仓库，
并发时会争抢同一批资源。统一由主 agent 在合并后执行：

1. 备份到 `.backups/`，且该目录仅保留最近三次快照。
2. 追加 `AI_REPORT.md` 的工作内容，并更新 `PROGRESS.md`。
3. 重新构建并校验 JAR 构建时间晚于 `src/`、`python/`、`tests/` 下全部源文件。

这三项已写入 `openspec/config.yaml` 的 `rules.tasks`，生成任务卡时自动生效。
