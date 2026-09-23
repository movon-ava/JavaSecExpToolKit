# Proposal: 分析证据模型（状态 / 证据来源 / 可达性表达 / 可复现与导出）

## Why

`astra/漏洞分析模块设计建议.md` 对本模块的定位给出了一条明确口径：

> 外部分析器负责尽可能完整地提取事实；本项目负责把事实转成**有证据、有边界、可验证的安全假设**。

按这条口径自查，当前实现有四处**看起来正常但结论方向可能相反**的缺口：

- **结论只有「可信度」一个维度**：它说的是「来源有多可靠」，而不是「验证走到哪一步」。
  于是「来自 pom 元数据的高可信度」会被读成「漏洞已确认」。该文件明确要求区分
  `observed` / `suspected` / `confirmed` / `not-reproduced` / `unknown`，
  并写明「静态特征命中不得标 confirmed；扫描失败不得标 clean」。
- **每条结论答不出「还缺什么」**：只给结论、不给反证与缺失证据，
  使用者无从判断该证实还是排除。
- **推荐动作只传页面路由**：跳转之后目标页不说「为什么带我到这」，
  使用者无法判断该不该按这条建议继续。
- **利用路径只有一个扁平集合**：读的人无法判断「某个调用者」与「某个入口」
  到底是不是同一条链上的两跳。该文件要求展示入口到 Sink 的路径及路径长度 / 断点。
- **绕过手法按漏洞大类整段贴出**：该文件明确反对这一点——技巧清单里多数条目
  依赖的组件根本不在目标里，会把「某版本才成立的手法」说成通用结论。

另外，规则库此前没有来源与版本：「这条判定依据哪份公告」「这是哪一版规则」都答不出来。

## What Changes

- **结论状态模型**：`Finding` 新增 `Status`（observed / suspected / confirmed /
  not-reproduced / unknown）与 `missingEvidence` 两栏；静态规则命中一律记
  `suspected`，并在报告里印出「成立还需要（缺一不可）」。
- **规则库治理**：`vulnerabilityTypes` 与每条特征补 `source` / `applicable` /
  `references`；规则库带 `ruleVersion` 与 `maintained`；Java 侧规则表补 `source`
  与版本号，构造期校验（漏填来源即抛异常）。报告印出规则来源、版本与维护日期。
- **绕过手法条件化**：绕过条目从字符串升级为对象（`text` / `source` / `appliesTo` /
  `requires` / `tags`），报告**按本次命中事实筛选**后再列出，不再整段贴出。
- **可达性表达**：`paths` 查询改为输出完整链路序列、路径长度与断点；
  深度不足时明确写出「往上还有调用者」，而不是给出「没找到入口」的暗示。
- **能力边界声明**：报告显式写明「本引擎不产出污点分析，因此不声称可绕过」，
  以及「查询失败一律记 unknown，不得记为目标干净」。
- **分析上下文交接**：新增 `AnalysisContext`（来源 / 目标 / 证据 / 数据源 / 局限 /
  生成时间）与界面交接条；跳转时显示「由哪条分析结论带入」并可回跳。
  **只展示、不预填高风险参数**（命令 / 回连地址 / 目标 URL）。
- **可复现与导出**：特征匹配可同时导出机器可读 JSON（schema 版本、数据源状态、
  状态口径、维度说明、局限），走配置项控制；导出结果如实写进报告。
- **P0 真实端到端门槛**：新增 `tests/test_backend_e2e.py`，用真实后端与真实字节码
  走完建库 → 表结构 → 查询 → 判定；后端缺席时**跳过并写明「未验证」**，不写成通过。
- **筛选在脚本侧而不在界面侧**：按漏洞类型 / 证据来源 / 命中标签筛选，筛选口径写进报告头。
  界面按行过滤会把命中计数与「未命中清单」割裂，使用者对不上账；
  **未知取值必须报错并列出可选值**，静默忽略会被读成「目标干净」。
  下拉框选项由脚本的 `--list-filters` 给出（不依赖数据库），避免写死后与签名库漂移。
- **可复现：任务元数据 + 同输入重跑比对**：导出带工具版本、数据库 SHA-256、
  生成时间与本次筛选；给定基线时额外给出差异（新增 / 不再命中 / 命中数变化）。
  基线损坏或 schema 不同时**回落为不比对**，不让本次分析失败。
- **结论过期提示**：交接条在结论超过 30 分钟后提醒重新核对，
  避免把一段时间之前的判定当成刚跑出来的结果。
- **失败必须可分辨**：引擎调用此前丢弃子进程退出码，于是「查询没跑成」与「目标干净」
  在界面上长得一模一样；现按真实退出码定成败，并在报告前置失败横幅。

## Capabilities

### New Capabilities

- `analyze/evidence-model`：结论状态、证据来源、可达性表达、分析上下文交接与报告导出。

### Modified Capabilities

- `analyze/signature-match`：状态口径、规则治理字段、绕过手法按命中筛选、能力边界声明。
- `analyze/call-graph`：利用路径给出完整链路、路径长度与断点。

## Non-goals

- **不实现污点分析**：本 change 只把「没有做污点分析」这件事说清楚，
  不新增源 / 传播 / 净化 / Sink 的推断。该文件也要求这类扩展先做小范围准确率验证。
- **不改判定口径**：组件与版本区间的判定逻辑（`VulnerabilityRules` / `GadgetRule`）
  不在本次范围内，只补来源与状态两个维度。
- **不引入任何新依赖**：JSON 导出用 Python 标准库 `json`；界面不引入 JSON 库。
- **不引入上游规则数据**：`jar-analyzer` 为 GPLv3，其 `gadget.dat` /
  `dfs-sink.json` / `vulnerability.yaml` 一律不进本仓库。
- **不改其它页面与既有配置项**：只在「漏洞分析配置」分组内新增两项（导出开关与目录）。
- **不做自动发包**：上下文交接只预填展示文本，任何网络动作仍由使用者显式触发。

## Impact

| 路径 | 写入域 | 动作 |
| --- | --- | --- |
| `src/analyzer/Finding.java` | exploit | 改：新增 Status 与 missingEvidence |
| `src/analyzer/VulnerabilityRules.java` | exploit | 改：规则来源表、版本号与按 id 取规则 |
| `src/analyzer/VulnerabilityAnalyzer.java` | exploit | 改：状态与缺失证据、来源渲染 |
| `src/analyze/AnalysisContext.java` | exploit | 新增：分析上下文模型 |
| `src/analyze/AnalyzeReport.java` | exploit | 改：可挂载上下文 |
| `src/analyze/AnalyzeEngine.java` | exploit | 改：上下文构建、证据摘取、局限说明、JSON 导出 |
| `src/analyze/AnalyzeCommand.java` | exploit | 改：签名命令支持 JSON 导出、筛选与基线比对，新增 `--list-filters` |
| `src/analyzer/ScriptRunner.java` | exploit | 改：新增 `Outcome`（退出码 / 超时），失败不再被当成空结果 |
| `python/jar_report.py` | exploit | 改：完整链路与断点表达 |
| `python/jar_signatures.py` | exploit | 改：死代码修复、规则治理校验、按命中筛选与 JSON 导出 |
| `python/vuln_signatures.json` | exploit | 改：来源 / 适用范围 / 参考 / 结构化绕过手法 |
| `src/ui/AnalysisContextBar.java` | ui | 新增：交接条 |
| `src/ui/AnalyzeWorker.java`、`src/ui/WorkbenchPages.java`、`src/Main.java` | ui | 改：接线交接条 |
| `src/ui/ConfigForm.java`、`src/ui/ConfigController.java`、`src/ui/WidgetRegistry.java` | ui | 改：导出开关与目录 |
| `src/ui/AnalyzeController.java` | ui | 改：按配置传导出路径，筛选与比对接线，后台补全类型下拉框 |
| `src/ui/AnalyzeChainPage.java`、`src/ui/AnalysisContextBar.java` | ui | 改：筛选控件与过期提示 |
| `tests/AnalyzeCheck.java`、`tests/UiNavigationCheck.java` | 测试 | 改：状态 / 来源 / 上下文 / 交接条断言 |
| `tests/test_signatures.py`、`tests/test_jar_report.py` | 测试 | 改：治理字段、筛选、基线比对、链路与导出断言 |
| `tests/test_backend_e2e.py` | 测试 | 新增：真实后端端到端（P0） |
| `README.md`、`README.en.md`、`docs/DESIGN-analyze.md`、`PROGRESS.md`、`AI_REPORT.md` | 主 agent | 改：文档同步 |

## 实测复现步骤

1. 打开「漏洞分析 → 组件与漏洞」，选一个含 `META-INF/maven/**/pom.properties`
   的 jar（例如 fastjson 1.2.24）→ 点「本地依赖分析」。
   - 预期：结论行为 `[高 / 疑似]`；有「成立还需要（缺一不可）」；
     有「来源:」；报告头有规则库版本与维护日期。
2. 看内容区顶部：出现「由分析结论带入：本地依赖分析 …」横幅，可点「返回分析结论」。
3. 配置页 → 漏洞分析配置：勾选「导出机器可读报告」，目录留空
   （默认写到后端工作目录下的 `analyze-report.json`）。
4. 「漏洞分析 → 调用链查询」→ 建库后点「漏洞特征匹配」。
   - 预期：报告含「状态: 疑似」「适用范围」「绕过手法（按本次命中筛选…」每条带
     来源 / 适用 / 前提；末尾有「本次分析的能力边界」；并提示 JSON 已导出。
   - 用 `paths` 查询：每条命中给出完整链路、路径长度与断点。
5. 在同一页把「漏洞类型」下拉框选成一个具体类型（选项在进页后由后台补全）后再跑一次特征匹配：
   - 预期：报告头出现「本次筛选」并与下拉框一致；不选时不出现该行。
6. 再跑一次（同一输入、同一筛选）：
   - 预期：报告尾部出现「与上一次同输入的比对」，且结论与基线一致；
     把对比开关取消后重跑：应显示「本次未做比对」而不是静默略过。
7. 环境变量 `JSETK_TEST_JAVA` 指向 17+ 的 java 后跑
   `python -m unittest tests.test_backend_e2e -v`：应真的建库并断言表结构。
   把后端目录改名后重跑：应显示 **skipped 且写明「端到端未验证（不是通过）」**。
