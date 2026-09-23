# Tasks

## 1. 判定内核

写入域：`src/analyzer/GadgetRule.java`、`GadgetRules.java`、`GadgetRuleFile.java`、
`GadgetInventory.java`（角色：exploit）

- [x] 1.1 需求模型支持 groupId 前缀 + artifactId 通配 + 版本区间（含排除版本）；验证：`AnalyzeCheck` 断言
- [x] 1.2 版本不可比时判为不满足（宁可不报，不可误报）；验证：区间判定断言
- [x] 1.3 规则表按类型分组，覆盖 8 类；验证：类型去重且非空断言
- [x] 1.4 已修复版本不再判为可用（CC 3.2.2）；验证：`AnalyzeCheck` 断言
- [x] 1.5 新坐标不漏判（c3p0 / mysql-connector-j / jython / el-api）；验证：逐条断言
- [x] 1.6 外部规则文件解析：注释与空行跳过、`!版本` 排除、`*` 通配、坏行逐行报告；验证：`AnalyzeCheck` 断言

## 2. 报告

写入域：`src/analyze/AnalyzeEngine.java`（角色：exploit）

- [x] 2.1 两份报告（jar / pom）都拼入 gadget 段；验证：端到端报告断言
- [x] 2.2 按类型分组列可用链，并给出依据坐标 / 能力 / 下一步 / 直达页；验证：报告断言
- [x] 2.3 缺失链逐条列出「还差什么」；验证：报告断言
- [x] 2.4 规则文件解析问题进报告而不是仅写状态行；验证：`UiNavigationCheck` 端到端

## 3. 配置与接线

写入域：`src/ui/ConfigForm.java`、`src/ui/ConfigController.java`、`src/ui/AnalyzeScanController.java`（角色：ui）

- [x] 3.1 新增「外部 gadget 规则文件」配置项并落盘；验证：配置页控件断言
- [x] 3.2 每次分析重读规则文件（改完直接重跑即生效）；验证：端到端断言
- [x] 3.3 规则文件缺失时给出可读问题而不抛异常；验证：`AnalyzeCheck` 断言

## 4. 自检

写入域：`tests/**`（角色：测试）

- [x] 4.1 gadget 判定断言组（规则表 / 版本区间 / 新坐标 / 外部规则）；验证：`AnalyzeCheck` 85 项全绿
- [x] 4.2 报告含 gadget 段与缺失清单；验证：`UiNavigationCheck` 端到端断言

## 5. 收尾

写入域：`README*.md`、`AI_REPORT.md`、`PROGRESS.md`、`openspec/**`、`tools/**`、`docs/**`（角色：主 agent）

- [x] 5.1 新文件纳入 probe 角色写入域并同步职责文档；验证：`check_agent_tools.ps1` 91 项通过
- [x] 5.2 中英 README 同步（分析章节 / 项目结构 / 测试章）；验证：两处小节一一对应
- [x] 5.3 构建并复验 JAR 时间晚于全部源文件；验证：`build.ps1` 输出
