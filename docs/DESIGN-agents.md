# JavaSecExpToolKit 多 Agent 协同开发框架设计

版本：0.1.0
更新日期：2026-09-21
适用读者：本仓库维护者
关联文档：`docs/DESIGN.md`（总设计）、`docs/DESIGN-modularization.md`（Java 模块化）、
`docs/DESIGN-payload.md`（payload 生成）、`openspec/config.yaml`（OpenSpec 约束）

---

## 一、目标与边界

本框架要解决三个已经发生过的具体问题：

1. **写入冲突**：多个执行体同时改 `src/Main.java` 必然互相覆盖。
   现状 `Main.java` 承载 5 个页面的全部状态，是所有改动绕不开的汇合点。
2. **验收漂移**：功能改动后缺少统一的回归入口，依赖人工记忆挑自检。
3. **越权修改**：`AGENTS.md` 已写明「禁止修改没有要求修改的功能」，
   但缺少可执行的边界定义，无法机械判定某次改动是否越界。

本框架只覆盖**开发过程**，不改变工具的运行时行为。落地不新增任何第三方依赖：
约束全部通过 OpenSpec 的 `config.yaml` 与本文档的角色卡表达。

---

## 二、事实基础

以下数据均为 2026-09-21 在本仓库实测，是划分角色的依据，不是经验估计。

### 2.1 代码规模

| 模块 | 行数 | 文件数 | 说明 |
| --- | ---: | ---: | --- |
| `src/(root)` | 1533 | 1 | 只有 `Main.java` |
| `src/ui` | 1463 | 10 | 纯视图构建器，不持有状态 |
| `src/shiro` | 1176 | 3 | Shiro 引擎 / 利用 / java-chains 封装 |
| `src/proxy` | 768 | 1 | 本地 HTTP 代理 |
| `src/util` | 333 | 3 | 共享内核 |
| `src/probe` | 310 | 3 | 探测驱动与抓包桥接 |
| `src/config` | 47 | 1 | 配置读写 |
| **Java 合计** | **5630** | **22** | |
| `python/fj_probe.py` | 2811 | 1 | 探测引擎本体 |
| `tests/test_probe.py` | 1301 | 1 | 引擎单元测试 |
| `tests/Ui*.java` 等 | 2064 | 5 | 五套自检 |

`Main.java` 单文件 1533 行、70 个方法、100 个 `private final` 字段、36 条 import。

### 2.2 耦合事实

- `src/util/HttpText.java` 被 `CaptureBridge`、`FlowRenderer`、`Main` 三处引用，
  属跨模块共享内核，**不能**由单个功能 agent 独占。
- `src/probe/CaptureBridge.java` 只被 `Main` 引用，可独立演进。
- `src/shiro/ChainsEngine.java` 调用 `ShiroEngine.base64(...)`，payload 包若直接复用
  `ChainsEngine` 会反向依赖 shiro 包（解法见 `docs/DESIGN-payload.md`）。
- `tests/Ui*.java` 通过反射访问 `Main` 的 **80 个成员**（实测），
  任何拆分 `Main` 的动作都必须同步处理测试。
- java-chains 实测注册 **429 个节点**、**28 种 payload 载体**，
  payload 功能的数据面很大，但复用成本低。

### 2.3 现有验证入口

| 入口 | 覆盖 | 调用方式 |
| --- | --- | --- |
| `tests/test_probe.py` | 探测引擎 | `python -m unittest discover -s tests` |
| `tests/ShiroCheck.java` | Shiro 引擎与加解密 | `java ... ShiroCheck` |
| `tests/ProxyServerCheck.java` | 代理与拦截改包 | `java ... ProxyServerCheck` |
| `tests/UiNavigationCheck.java` | 导航与各页装配 | `java ... UiNavigationCheck` |
| `tests/UiShiroCheck.java` | Shiro 页 | `java ... UiShiroCheck` |
| `tests/UiSwitchEndToEndCheck.java` | 抓包到探测端到端 | `java ... UiSwitchEndToEndCheck` |

---

## 三、角色划分

### 3.1 总览

| 角色 | 数量 | 写入域 | 专职产出 |
| --- | ---: | --- | --- |
| 主 agent（orchestrator） | 1 | 根目录文档、`openspec/**` | change 全生命周期、集成、Git |
| 功能开发（probe） | 1 | `python/fj_probe.py`、`src/probe/Probe*.java` | 探测引擎改动 |
| 功能开发（exploit） | 1 | `src/shiro/**`、`src/payload/**` | 利用链与 payload 生成 |
| 功能开发（traffic） | 1 | `src/proxy/**`、`src/probe/CaptureBridge.java` | 代理与抓包转换 |
| UI | 1 | `src/ui/**`、`src/Main.java` | 界面装配与交互 |
| 测试 | 1 | `tests/**` | 自检与回归 |
| 监督（supervisor） | 1 | **无**（只读） | 审计报告 |

### 3.2 功能开发 agent 是否细分：是，细分为三个

判断依据是「写入集合是否重叠」与「验证入口是否相同」，两者都指向拆分：

| 维度 | probe | exploit | traffic |
| --- | --- | --- | --- |
| 主语言 | Python | Java | Java |
| 关键依赖 | Python 标准库子进程 | java-chains（429 节点） | JDK ServerSocket |
| 规模 | 2811 行 | 1176 行 | 768 + 125 行 |
| 验证入口 | `test_probe.py` | `ShiroCheck` | `ProxyServerCheck` |
| 共享点 | 无 | `ShiroEngine.base64` | `util/HttpText` |

三者的写入路径完全不重叠，验证入口互不包含，拆成三个不会引入协同成本，
反而消除了唯一的冲突热点。反向证据同样成立：若把 probe 与 exploit 合成一个角色，
它需同时精通 Python 探测逻辑与 Java 反序列化链，单次任务上下文达 3987 行，
超出可维护范围。

### 3.3 职责边界（每个角色的「不做什么」）

明确写「不做什么」，否则边界会自然漂移：

- **主 agent**：不写功能代码。它的产物是 change 目录、集成结果与 Git 记录。
  唯一允许直接编辑的代码文件是 `AGENTS.md` 与构建脚本。
- **功能开发 agent**：不改 `src/ui/**`（界面外观归 UI agent），
  不改 `tests/**`（断言归测试 agent），不改配置项键名。
- **UI agent**：不改引擎行为。它只接线：把已有的引擎调用暴露到界面上。
  行业约定「配置可长期保存的项必须进配置页」由本角色负责落实。
- **测试 agent**：不为了让测试通过而放宽断言。断言失败时向对应功能 agent
  报缺陷，而不是改测试。
- **监督 agent**：不修改任何文件，只产出报告。

---

## 四、写入域隔离矩阵

`W` = 可写，`R` = 只读，`-` = 无关。

| 路径 | 主 | probe | exploit | traffic | UI | 测试 | 监督 |
| --- | :-: | :-: | :-: | :-: | :-: | :-: | :-: |
| `openspec/**` | W | R | R | R | R | R | R |
| `AGENTS.md` | W | R | R | R | R | R | R |
| `build.ps1` / `run.ps1` | W | R | R | R | R | R | R |
| `PROGRESS.md` | W | R | R | R | R | R | R |
| `AI_REPORT.md` | W | R | R | R | R | R | R |
| `README.md` / `README.en.md` | W | R | R | R | R | R | R |
| `docs/**` | W | R | R | R | R | R | R |
| `python/fj_probe.py` | R | W | - | - | - | R | R |
| `src/probe/Probe*.java` | R | W | - | - | - | R | R |
| `src/probe/CaptureBridge.java` | R | R | - | W | - | R | R |
| `src/shiro/**` | R | - | W | - | - | R | R |
| `src/payload/**` | R | - | W | - | - | R | R |
| `src/proxy/**` | R | - | - | W | - | R | R |
| `src/ui/**` | R | - | - | - | W | R | R |
| `src/Main.java` | R | - | - | - | W | R | R |
| `src/config/**` | R | R | R | R | R | R | R |
| `src/util/**` | R | R | R | R | R | R | R |
| `tests/**` | R | R | R | R | R | W | R |

### 4.1 共享内核规则

`src/config/**` 与 `src/util/**` 是全项目共享内核，**任何角色都不可直接写**。
需要改动的，由主 agent 单独开一个 change，并在 proposal 的 Impact 段
列出全部调用方（实测：`HttpText` 3 处、`JsonText` 2 处、`AppConfig` 1 处）。
这与 `AGENTS.md`「如果某个修改需要牵连其他文件，必须先向我描述方案并征得同意」一致。

### 4.2 跨域变更规则

一次改动若需要触碰两个以上角色的写入域，必须由主 agent 拆成多个 change，
禁止在单个 change 内跨域提交。理由：拆分后每个 change 都能独立回滚，
而跨域的单次提交一旦回滚会连带撤销不相干的改动。

---

## 五、OpenSpec 工作流映射

OpenSpec 的六个工作流与角色的对应关系：

| 工作流 | 执行角色 | 说明 |
| --- | --- | --- |
| `explore` | 任意（通常主 agent） | 只讨论方案，不产出 change，不写码 |
| `propose` | 主 agent | 产出 proposal / specs / design / tasks 四件套 |
| `apply` | 对应功能 agent 或 UI agent | 按 tasks.md 逐条实现 |
| `update` | 主 agent | 实施中发现计划需修订时回改规划件 |
| `sync` | 主 agent | 把 delta spec 合入主 spec |
| `archive` | 主 agent | 归档 change 并更新主 spec |

关键约束（源自 OpenSpec 的 planning boundary）：`propose` 只产出规划件，
**不写代码**；实施必须由新的一次请求触发 `apply`。这与本项目
「先诊断后动手、禁止试错式修改」的既有约束天然一致。

### 5.1 change 命名与任务的对应

`tasks.md` 的每个任务组对应一个可独立提交的工作单元。任务卡必须自带：

1. **写入域**：本次允许改动的路径清单（取自第四节矩阵）。
2. **验证命令**：完成后要跑的那条自检，以及期望输出。
3. **收尾三项**：备份到 `.backups/`（仅保留最近三次）、追加 `AI_REPORT.md`
   与更新 `PROGRESS.md`、重编译并校验 JAR 构建时间晚于全部源文件。

这三点已同步写入 `openspec/config.yaml` 的 `rules.tasks`，
使约束在每次生成任务卡时自动生效，而不依赖执行者记忆。

---

## 六、交接协议

角色之间不共享内存，只通过文件交接。四类交接物：

| 交接物 | 方向 | 内容 |
| --- | --- | --- |
| change 目录 | 主 → 执行 | 本次做什么、验收标准、写入域 |
| 源码改动 | 执行 → 测试 | 可编译的实现 |
| 自检结果 | 测试 → 主 | 通过/失败清单，失败附最小复现步骤 |
| 审计报告 | 监督 → 主 | 越界、空承诺、断言放宽等问题清单 |

### 6.1 禁止的交接方式

- 禁止口头交接（例如「我改好了，你看着办」）：必须落成自检输出或文件。
- 禁止执行者自行宣布完成：完成标志是**测试 agent 的自检全绿**，
  加上 JAR 时间校验通过，二者缺一不可。
- 禁止跳过 OpenSpec 直接改码：任何跨文件改动都应先有 change 目录。

### 6.2 失败回退

自检失败时按固定顺序处理：测试 agent 给出失败清单 →
功能 agent 定位根因（必须先写根因分析，禁止试错）→ 修复 →
重跑全部受影响自检 → 主 agent 决定是否继续本 change 或回退。
回退时从 `.backups/` 取最近一次快照，三次以内的历史都可回滚。

---

## 七、监督 agent

### 7.1 定位

监督 agent 是**只读审计者**。它不写代码、不改测试、不改文档，
只输出报告。它的价值在于发现「通过了但不对」的改动，
这类问题自检本身发现不了。

### 7.2 审计清单

每次 change 归档前，监督 agent 按以下清单逐条核对：

1. **写入域合规**：把本次 diff 涉及的全部路径与第四节矩阵对照，
   列出越界项。越界即阻断归档。
2. **范围合规**：检查是否修改了 tasks.md 之外的功能或配置项，
   对应 `AGENTS.md` 的「禁止修改没有要求修改的功能」。
3. **承诺兑现**：tasks.md 里每条任务是否有对应的代码或文档改动，
   有无勾选但未实现的项。
4. **空实现检测**：全量搜索 `TODO`、`FIXME`、`pass`、`此处省略`、
   空方法体、返回常量桩。命中即阻断。
5. **断言完整性**：对比本次改动前后的自检断言数量，
   只减不增视为放宽断言，需要给出理由。
6. **依赖合规**：确认 `src/pom.xml` 与 Python 侧未引入新第三方依赖。
7. **构建一致性**：JAR 构建时间晚于全部源文件；
   JAR 内 `python/fj_probe.py` 与源文件哈希一致。
8. **文档同步**：功能改动是否同步了 `README.md`、`README.en.md`、
   `PROGRESS.md`、`AI_REPORT.md` 与相关 `docs/DESIGN-*.md`。
9. **配置落页**：新增的可持久化配置项是否已进入配置页
   （`src/ui/ConfigPage.java` 的分组清单 + `src/config/AppConfig.java` 的键）。
10. **测试真实性**：自检是否指向真实行为而非桩（例如
    `UiSwitchEndToEndCheck` 是否真的起了桩服务端）。

### 7.3 报告格式

监督报告追加到 `AI_REPORT.md` 对应的 change 章节，固定三段：

- **通过项**：逐条列出已核对且无问题的条目编号。
- **发现问题**：每条含文件路径与行号、问题类型、建议处置。
- **阻断结论**：`可归档` 或 `阻断归档（原因）`。

### 7.4 与自我审查的区别

监督 agent 必须**独立复算**，不能复用执行者的结论。例如核对
「JAR 时间晚于源文件」时，它要自己重新枚举 `src/`、`python/`、`tests/`
下的文件并与 JAR 的 `LastWriteTime` 比较，而不是引用执行者贴出的结论。
这是本角色存在的唯一理由。

---

## 八、并发与仲裁

- 主 agent 同时最多派发**两个**执行角色，且必须写入域不重叠。
  这是一条硬约束：并发数本身不构成收益，写入域隔离才是。
- 三个功能 agent 之间天然无重叠，可并行；UI agent 与任意功能 agent
  并行时需注意 `src/Main.java` 是唯一汇合点，UI 改动应串行。
- 出现分歧时，以 `docs/DESIGN.md` 与 `openspec/specs/` 的主 spec 为准；
  两者都没有覆盖的，暂停并交由维护者裁决，不允许自行发明约定。

---

## 九、落地步骤

按顺序执行，每步可独立验收：

1. **写入 OpenSpec 约束**：以 `openspec/config.yaml` 承载跨角色的
   context / rules / operations，使约束在每次生成规划件时自动生效。
2. **写入本文档**：角色卡与写入域矩阵作为唯一事实源，
   `AGENTS.md` 只保留一句指引，避免两处维护。
3. **回填首个 change**：用已完成的「抓包格式直接可探测」建立样板
   （`openspec/changes/archive/`），验证流程是否顺手。
4. **处理共享内核耦合**：把 `ShiroEngine.base64` 下沉到 `src/util/`，
   解除 payload 包对 shiro 包的反向依赖（见 `docs/DESIGN-payload.md`）。
5. **实施 payload 生成功能**：按 `docs/DESIGN-payload.md` 的拆分，
   由 exploit agent 与 UI agent 各承担一半，主 agent 拆成两个 change。
6. **重构成熟后**：按 `docs/DESIGN-modularization.md` 分三阶段收敛 `Main.java`。

第 1、2 步是纯文档改动，无编译风险；第 3 步不产生代码改动；
第 4 步起需要走完整的备份、自检、报告、构建校验流程。