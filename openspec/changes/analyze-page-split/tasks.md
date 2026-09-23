# Tasks

## 1. 特征匹配内核

写入域：`python/jar_signatures.py`、`python/vuln_signatures.json`（角色：probe）

- [x] 1.1 签名库登记 14 种 Java 领域漏洞类型（触发条件 + 绕过手法）；验证：`test_signatures.py` 逐类型断言
- [x] 1.2 四类特征全覆盖（strings / classes / methods / sinks）；验证：每类至少一条特征
- [x] 1.3 sink 特征与 `jar_report.SINKS` 逐条一致；验证：`test_sink_signatures_match_jar_report`
- [x] 1.4 每种漏洞类型至少被一条特征指向；验证：`test_every_vulnerability_type_is_reachable`
- [x] 1.5 非法正则 / 未登记类型 / 坏 JSON 一律报错，不静默跳过；验证：三条失败路径断言

## 2. 编排与命令

写入域：`src/analyze/*`、`src/analyzer/ReportReader.java`、`src/analyzer/ScriptRunner.java`（角色：exploit）

- [x] 2.1 新增特征匹配命令（`-db` / `-s` / `--min-severity`）；验证：`AnalyzeCheck` 断言
- [x] 2.2 数据库缺失时给出「先跑调用链分析」的可读提示；验证：`AnalyzeCheck` 失败路径
- [x] 2.3 查询枚举新增特征匹配标识且与下拉解耦；验证：`Query.values().length == 6` 断言
- [x] 2.4 资源释放按后缀区分脚本与签名库；验证：`test_signatures.py` 端到端

## 3. 界面拆页

写入域：`src/ui/*`、`src/Main.java`（角色：ui）

- [x] 3.1 两页各自持控件、标题与报告区，互不覆盖；验证：`UiNavigationCheck` 内容标签集不同
- [x] 3.2 删除共用的 `AnalyzePage`，行为拆成两个控制器 + 一个共用执行器；验证：视图类存在性断言
- [x] 3.3 特征匹配按钮与严重度下拉框接线，未建库时给可读提示；验证：`UiNavigationCheck` 断言
- [x] 3.4 外部 gadget 规则文件配置项进配置页并落盘；验证：配置页控件断言

## 4. 自检

写入域：`tests/**`（角色：测试）

- [x] 4.1 两页不再共用一个视图的回归守卫；验证：`viewClassPresent` 断言
- [x] 4.2 两页报告区互不覆盖、跳转容器各自独立；验证：`UiNavigationCheck` 断言
- [x] 4.3 查询候选为五种事实查询（特征匹配不进下拉）；验证：候选数断言
- [x] 4.4 能力清单七项、查询枚举六项；验证：`AnalyzeCheck` 断言

## 5. 收尾

写入域：`README*.md`、`AI_REPORT.md`、`PROGRESS.md`、`openspec/**`（角色：主 agent）

- [x] 5.1 中英 README 同步（分析章节 / 项目结构 / 测试章）；验证：两处小节一一对应
- [x] 5.2 追加 AI 报告与进度；验证：两份文件含本轮改动
- [x] 5.3 构建并复验 JAR 时间晚于全部源文件；验证：`build.ps1` 输出
