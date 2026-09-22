# Tasks

## 1. 模板与归属规则

写入域：`src/payload/ToStringPreset.java`、`src/payload/ChainScope.java`（角色：exploit）

- [x] 1.1 模板显式记录触发节点并拼出完整 gadget 序列；验证：5 条模板 `isChainValid` 全为 true
- [x] 1.2 逐条构建验证模板可用；验证：5 条模板 `build` 全部 `success`
- [x] 1.3 触发节点归属清单与运行时目录一致；验证：`ChainScope.issues(runtimeNodes)` 为空
- [x] 1.4 候选过滤不改动传入清单、也不剔除 toString 以外的节点；验证：`PayloadCheck` 断言

## 2. 界面

写入域：`src/ui/PayloadToStringPage.java`、`src/ui/PayloadToStringController.java`、
`src/ui/PayloadColumns.java`、`src/ui/PayloadController.java`、`src/ui/NavController.java`、
`src/ui/WorkbenchPages.java`、`src/ui/WidgetRegistry.java`、`src/Main.java`（角色：ui）

- [x] 2.1 新增 toString 链页视图与行为（模板清单 / 步骤 / 目标类 / 命令 / 生成 / 复制模板）；验证：`UiNavigationCheck` 控件断言通过
- [x] 2.2 导航新增「toString 链」二级项并接线懒加载；验证：导航与路由断言通过
- [x] 2.3 生成页候选列过滤 toString 触发节点；验证：`UiNavigationCheck` 过滤断言通过
- [x] 2.4 生成页状态栏与链信息行的候选计数同步过滤；验证：断言计数与列内项数一致
- [x] 2.5 登记控件并提供自检转发入口；验证：`UiNavigationCheck` 通过

## 3. 配置

写入域：`src/ui/ConfigForm.java`、`src/ui/ConfigController.java`（角色：ui）

- [x] 3.1 新增「toString 链配置」分组（默认链模板 / 默认末端命令）；验证：配置页断言通过
- [x] 3.2 模板候选项从模板表现取，不写死；验证：候选项数等于模板数
- [x] 3.3 配置里存稳定标识、界面显示模板名，两处按下标对应；验证：往返转换断言通过

## 4. 自检

写入域：`tests/**`（角色：测试）

- [x] 4.1 模板完备性断言（模板数、载体、默认模板、未知模板拒绝、参数组装）；验证：`PayloadCheck` 通过
- [x] 4.2 模板逐条可构建断言；验证：`PayloadCheck` 通过
- [x] 4.3 过滤规则断言（不含触发节点、只剔除触发节点、不改动传入清单）；验证：`PayloadCheck` 通过
- [x] 4.4 页面控件、配置分组与候选过滤的界面断言；验证：`UiNavigationCheck` 通过

## 5. 收尾

写入域：`README*.md`、`AI_REPORT.md`、`PROGRESS.md`、`openspec/**`（角色：主 agent）

- [x] 5.1 中英 README 同步（导航 / 配置页 / 项目结构 / 测试）；验证：两处小节一一对应
- [x] 5.2 追加 AI 报告与进度；验证：两份文件含本轮改动
- [x] 5.3 构建并复验 JAR 时间晚于全部源文件；验证：`build.ps1` 输出
