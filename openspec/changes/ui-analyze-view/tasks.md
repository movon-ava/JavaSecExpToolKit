# Tasks

## 1. 页面与行为

写入域：`src/ui/AnalyzePage.java`、`src/ui/AnalyzeController.java`（角色：ui）
- [x] 1.1 视图：分析目标 / pom / 类名 / 输出目录 / 超时 / 查询下拉 / 关键字 + 四组按钮 + 报告区；验证：`UiNavigationCheck` 控件断言通过
- [x] 1.2 行为：本地分析、调用链分析、数据库查询、反编译四类动作；验证：端到端断言通过
- [x] 1.3 全部动作走后台线程，界面回写走 EDT；同一时刻只允许一个动作；验证：状态栏断言通过
- [x] 1.4 跳转建议渲染成按钮并接上导航；验证：跳转断言通过
- [x] 1.5 未选目标 / 目标不存在 / 未配置引擎 / 数据库缺失 / 超时低于下限五类失败都给可读结论；验证：失败路径断言通过

## 2. 接线

写入域：`src/ui/NavController.java`、`src/ui/WorkbenchPages.java`、`src/ui/WidgetRegistry.java`、`src/Main.java`（角色：ui）
- [x] 2.1 新增一级分类「漏洞分析」与两个二级项；验证：导航项数与顺序断言通过
- [x] 2.2 懒加载入口与退出时关停；验证：`shutdown()` 断言通过
- [x] 2.3 控件登记到 `WidgetRegistry`；验证：`UiHandle` 取件断言通过
- [x] 2.4 两条路由与注册表赋值；验证：跳转断言通过

## 3. 配置

写入域：`src/ui/ConfigForm.java`、`src/ui/ConfigController.java`（角色：ui）
- [x] 3.1 新增「漏洞分析配置」分组与 5 个控件；验证：配置页断言通过
- [x] 3.2 回填与落盘 5 个键；验证：默认值断言通过

## 4. 自检

写入域：`tests/**`（角色：测试）
- [x] 4.1 导航 7→8 项、展开 8→9；验证：`UiNavigationCheck` 通过
- [x] 4.2 分析页 24 条控件与行为断言；验证：同上
- [x] 4.3 端到端真实造 jar 跑一次本地分析；验证：报告断言通过
- [x] 4.4 配置页 5 条断言；验证：同上

## 5. 收尾

写入域：`README*.md`、`PROGRESS.md`、`AI_REPORT.md`（角色：主 agent）
- [x] 5.1 中英 README 同步；验证：两处小节一一对应
- [x] 5.2 追加 AI 报告与进度；验证：两份文件含本轮改动
- [x] 5.3 构建并复验 JAR 时间；验证：`build.ps1` 输出