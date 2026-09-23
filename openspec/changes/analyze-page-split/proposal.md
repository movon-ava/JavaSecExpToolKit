# Proposal: 漏洞分析拆页 + 漏洞特征匹配

## Why

「漏洞分析」下有两个二级项，但 `WorkbenchPages.analyzeScan()` 与 `analyzeChain()`
**返回的是同一个页面**（都 `return AnalyzePage.build(analyzeWidgets, fonts)`），
`analyzeChain()` 只多做一步 `queryKind.setSelectedIndex(2)`。注释里把这件事写成
「共用一页是刻意的」，但实际后果是：点哪个二级项看到的完全一样，使用者无法判断
「我现在看的是依赖结论还是代码结论」；且分钟级的建库动作与秒级的本地分析混在同一个
按钮组里，代价差异表达不出来。

同时，漏洞分析此前只能回答「库里有什么」（总览 / 入口点 / sink 命中 / 字符串 / 组件），
回答不了「这些事实像什么漏洞、这类漏洞常见怎么绕」。按调用图找漏洞的价值，
一半在后者。

## What Changes

- 把漏洞分析的两个二级项拆成两个**真正不同的页面**：
  `组件与漏洞`（依赖口径，秒级、纯本地）与 `调用链查询`（代码口径，需外部引擎，分钟级建库）。
- 两个页面各自持有控件、标题、副标题与报告区，互不覆盖；跳转建议按钮分别渲染到各自容器。
- 新增「漏洞特征匹配」动作：把库里的四类证据（字符串常量 / 类名 / 方法名 / sink 调用）
  与自研签名库对照，输出**可能的漏洞类型**与该类型下常见的**绕过手法**，并可按严重度过滤。
- 签名库覆盖 14 种 Java 领域高危类型（反序列化 / JNDI / 表达式 / 模板 / 命令执行 / 代码执行 /
  SSRF / SQL 注入 / 路径穿越 / XXE / 文件上传 / 信息暴露 / 凭据泄露 / 拒绝服务）。
- 报告新增「可用 gadget（依赖口径）」段（见另一 change `gadget-rules`）。
- 新增可持久化配置项「外部 gadget 规则文件」进配置页「漏洞分析配置」分组。

## Capabilities

### New Capabilities

- `ui/analyze-pages`：两个分析页各自的导航、控件、交互与结论口径划分。
- `analyze/signature-match`：代码特征与签名库的匹配、漏洞类型判定与绕过手法输出。

### Modified Capabilities

- `ui/analyze-view`：由「单页承载三类动作」改为「两页分工」，控件与报告区各自独立。

## Non-goals

- **不改动依赖口径的判定逻辑**：`VulnerabilityRules` 的 25 条规则与 `VulnerabilityAnalyzer`
  的排序、可信度语义一律不动；本轮只改「结论怎么被看到」。
- **不改动外部引擎的调用方式**：仍只支持 jar-analyzer-engine，仍按超时终止进程树；
  不引入新的引擎或新的第三方依赖。
- **不把特征匹配做进数据库查询**：查询回答事实、匹配下判断，两者输出契约不同，
  因此匹配走独立按钮与独立脚本，不进查询下拉框。
- **不改动其它页面**：探测页、代理页、抓包页、Shiro 页、Payload 各页、服务页、
  小工具页一律不动；配置页只新增一个配置项，不改既有分组。
- **不做自动修复或自动利用**：匹配结论只给「可能的类型 + 绕过手法」，是否可达仍需人工确认。

## Impact

| 路径 | 写入域 | 动作 |
| --- | --- | --- |
| `src/ui/AnalyzeScanPage.java` | ui | 新增：组件与漏洞页视图 |
| `src/ui/AnalyzeChainPage.java` | ui | 新增：调用链查询页视图 |
| `src/ui/AnalyzeWorker.java` | ui | 新增：两页共用的后台执行器与建议按钮截断 |
| `src/ui/AnalyzeScanController.java` | ui | 新增：依赖口径页的行为 |
| `src/ui/AnalyzeController.java` | ui | 改：收窄为代码口径页的行为，新增特征匹配动作 |
| `src/ui/AnalyzePage.java` | ui | 删除：内容已拆入两页与共用执行器 |
| `src/ui/WorkbenchPages.java` | ui | 改：两页各自的懒加载入口、默认值下发与跳转渲染 |
| `src/ui/WidgetRegistry.java`、`src/Main.java` | ui | 改：两页控件分别登记与装配 |
| `src/ui/ConfigForm.java`、`src/ui/ConfigController.java` | ui | 改：新增「外部 gadget 规则文件」 |
| `src/analyze/AnalyzeCommand.java`、`src/analyze/AnalyzeEngine.java` | exploit | 改：新增特征匹配命令与编排 |
| `src/analyzer/ReportReader.java`、`src/analyzer/ScriptRunner.java` | exploit | 改：查询枚举与资源后缀 |
| `python/jar_signatures.py`、`python/vuln_signatures.json` | probe | 新增：特征匹配脚本与签名库 |
| `src/pom.xml` | 主 agent | 改：把两个新资源打进 JAR |
| `tests/AnalyzeCheck.java`、`tests/UiNavigationCheck.java` | 测试 | 改：能力清单、查询枚举、两页控件与回归守卫 |
| `tests/test_signatures.py` | 测试 | 新增：签名库格式、sink 一致性、过滤与失败路径 |
| `README.md`、`README.en.md`、`PROGRESS.md`、`AI_REPORT.md` | 主 agent | 改：文档同步 |
