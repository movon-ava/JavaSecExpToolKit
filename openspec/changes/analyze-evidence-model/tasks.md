# Tasks

## 1. 结论状态与证据

写入域：`src/analyzer/Finding.java`、`VulnerabilityAnalyzer.java`（角色：exploit）

- [x] 1.1 `Finding` 新增 `Status`（五档）与 `missingEvidence`，保留旧构造器签名；验证：`AnalyzeCheck` 断言
- [x] 1.2 静态规则命中一律记 `suspected`，不因来源可靠而升级；验证：状态断言
- [x] 1.3 报告印出「成立还需要（缺一不可）」与「来源:」；验证：`UiNavigationCheck` 端到端断言
- [x] 1.4 单行摘要同时含可信度与状态；验证：`line()` 断言

## 2. 规则库治理

写入域：`src/analyzer/VulnerabilityRules.java`、`VulnerabilityAnalyzer.java`（角色：exploit）

- [x] 2.1 每条规则登记判定来源，漏填在构造期抛异常；验证：遍历规则表断言全部非空
- [x] 2.2 规则库带版本号与维护日期并进报告；验证：`maintained()` 格式断言
- [x] 2.3 按 id 取规则（不存在返回 null）；验证：`of()` 断言

## 3. 可达性表达

写入域：`python/jar_report.py`（角色：exploit）

- [x] 3.1 `paths` 输出完整链路序列与路径长度；验证：`test_paths_show_full_chain_and_length`
- [x] 3.2 深度不足时给出断点与「往上还有调用者」，不暗示不可达；验证：`test_paths_report_cut_point_when_depth_insufficient`
- [x] 3.3 明确声明不产出污点分析；验证：`test_paths_state_no_taint_analysis`

## 4. 签名库治理与判定口径

写入域：`python/jar_signatures.py`、`python/vuln_signatures.json`（角色：exploit）

- [x] 4.1 修掉 `return` 之后的死代码：前提 / 反证校验此前从未生效；验证：坏库用例报错
- [x] 4.2 每条特征补 `source` / `tag`，每种类型补 `applicable` / `references`；验证：`LibraryFormatTest`
- [x] 4.3 绕过手法结构化（来源 / 适用 / 前提 / 标签）并按本次命中筛选；验证：`test_bypass_filtered_by_hit_facts`
- [x] 4.4 报告补「本次分析的能力边界」与状态口径；验证：`test_report_states_capability_boundary`
- [x] 4.5 机器可读 JSON 导出（含数据源状态与局限，状态恒为 suspected）；验证：`test_json_export_is_machine_readable`

## 5. 分析上下文交接

写入域：`src/analyze/AnalysisContext.java`、`AnalyzeReport.java`、`AnalyzeEngine.java`（角色：exploit）

- [x] 5.1 上下文模型（来源 / 目标 / 证据 / 数据源 / 局限 / 来源页 / 时间）；验证：`contextChecks`
- [x] 5.2 依赖口径与代码口径的结论都挂载上下文；验证：端到端交接条断言
- [x] 5.3 局限说明按查询类型区分；验证：`limitationsOf` 断言
- [x] 5.4 证据摘取只收结论行并剔除 NAV 行、上限 12 条；验证：`evidenceLines` 断言

## 6. 界面与配置

写入域：`src/ui/AnalysisContextBar.java`、`AnalyzeWorker.java`、`WorkbenchPages.java`、`src/Main.java`、`AnalyzeController.java`、`ConfigForm.java`、`ConfigController.java`、`WidgetRegistry.java`（角色：ui）

- [x] 6.1 交接条跨页显示，可回跳来源页；验证：`UiNavigationCheck` 断言
- [x] 6.2 交接条不预填高风险参数（不出现 cmd / http://）；验证：横幅内容断言
- [x] 6.3 「导出机器可读报告」开关与目录进配置页并落盘；验证：配置页控件断言
- [x] 6.4 JSON 导出路径按配置计算，关掉时不写文件；验证：端到端与配置断言

## 7. 端到端门槛（P0）

写入域：`tests/test_backend_e2e.py`（角色：测试）

- [x] 7.1 真实后端 + 真实字节码走完建库 → 表结构 → 查询 → 判定；验证：本模块
- [x] 7.2 后端缺席时跳过并写明「未验证（不是通过）」；验证：`skip_reason()` 断言
- [x] 7.3 后端搜索顺序稳定可复现；验证：`test_backend_search_is_deterministic`
- [x] 7.4 与主程序一致地优先用发行包自带运行期；验证：`bundled_java` 断言

## 8. 交互筛选与可复现比对

写入域：`python/jar_signatures.py`、`src/analyze/AnalyzeEngine.java`、`AnalyzeCommand.java`、
`src/ui/AnalyzeChainPage.java`、`AnalyzeController.java`、`WidgetRegistry.java`（角色：exploit + ui）

- [x] 8.1 按漏洞类型 / 证据来源 / 命中标签筛选，未知取值报错并列出可选值；
      验证：`test_filters_narrow_the_signature_set` / `test_unknown_filter_value_fails_readably`
- [x] 8.2 取值由脚本给出（`--list-filters`）不依赖数据库；验证：`test_list_filters_works_without_database`
- [x] 8.3 筛选口径写进报告头（「本次筛选」与「不代表其余特征未命中」）；
      验证：同上断言
- [x] 8.4 类型下拉框的选项在后台补全且与类型 id 对齐，取不到时回落不筛选；
      验证：`typeIdOf` 三条断言 + `UiNavigationCheck` 下拉框断言
- [x] 8.5 导出带任务元数据（工具版本 / 数据库 SHA-256 / 生成时间 / 本次筛选）；
      验证：`test_export_records_task_metadata`
- [x] 8.6 同输入重跑比对（新增 / 不再命中 / 命中数变化），基线不可用时回落——不让本次分析失败；
      验证：`test_baseline_comparison_reports_changes` / `test_baseline_change_is_detected` / `BaselineTest` 5 项
- [x] 8.7 结论超过 30 分钟后在交接条上提示重新核对；验证：后端实测 + 横幅文案断言
- [x] 8.8 失败与成功必须可分辨：脚本退出码不再被丢弃，失败时报告前置失败横幅；
      验证：`ScriptRunner.Outcome` 断言

## 9. 收尾

- [x] 9.1 `python -m unittest discover -s tests` 全绿（219 项）
- [x] 9.2 `AnalyzeCheck`（134 条）与其余 Java 自检全绿
- [x] 9.3 `python tools/audit_boundary.py` 全部通过
- [x] 9.4 `README.md` / `README.en.md` 同步更新
- [x] 9.5 `AI_REPORT.md` / `PROGRESS.md` / `docs/DESIGN-analyze.md` 更新
