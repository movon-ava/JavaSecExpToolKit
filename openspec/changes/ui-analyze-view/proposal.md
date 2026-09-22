# Proposal: 漏洞分析界面

## Why

漏洞分析的内核（`src/analyzer/`、`src/analyze/`、`python/jar_report.py`）已经在
change `analyze-local-deps` 中落地，但**界面上看不到**：没有导航入口，没有页面，
配置项也不在配置页里。内核判定得再准，使用者够不到就没有价值。

同时，三类动作的代价差异极大（秒级 / 分钟级 / 秒级但产物大），若混在一个按钮里，
使用者要么被迫为每次分析付出分钟级代价，要么拿不到需要的结论。
因此界面必须把「代价」这件事表达出来，由使用者决定何时付费。

## What Changes

- 新增一级导航分类「漏洞分析」，含两个二级项：`组件与漏洞`（默认）与 `调用链查询`。
- 新增分析页：分析目标（jar / 依赖目录）、源码工程 `pom.xml`、指定类名、反编译输出目录、
  调用链超时；按钮分四组——本地分析、调用链分析、数据库查询、反编译。
- 三类动作全部在后台线程执行，界面更新回到事件分发线程；同一时刻只允许一个动作在跑
  （两个分析同时写同一个工作目录里的数据库会互相覆盖）。
- 报告里的跳转建议渲染成按钮，点击直达对应功能页。
- 新增「漏洞分析配置」分组（5 个可持久化配置项）。

## Capabilities

### New Capabilities

- `ui/analyze-view`：漏洞分析页的导航、控件、交互与配置项。

## Non-goals

- **不新增第二个分析页**：三类动作共用同一个输入（同一个 jar），拆页会导致每换一步
  都要重新选一次文件。
- **不改其它页面**：探测页、代理页、抓包页、Shiro 页、Payload 各页、服务页、小工具页
  一律不动；配置页只新增一个分组，不改既有分组。
- **不做进度条与取消按钮**：本轮只保证「不假死 + 超时能终止」，
  细粒度进度需要引擎输出解析，暂不做。

## Impact

| 路径 | 写入域 | 动作 |
| --- | --- | --- |
| `src/ui/AnalyzePage.java` | ui | 新增：分析页视图 |
| `src/ui/AnalyzeController.java` | ui | 新增：后台线程与界面回写 |
| `src/ui/NavController.java` | ui | 改：新增一级分类与两个二级项 |
| `src/ui/WorkbenchPages.java` | ui | 改：懒加载入口、跳转按钮渲染、退出时关停 |
| `src/ui/WidgetRegistry.java` | ui | 改：登记控件名 |
| `src/ui/ConfigForm.java`、`src/ui/ConfigController.java` | ui | 改：新增分组与读写映射 |
| `src/Main.java` | ui | 改：路由与注册表 |
| `tests/UiNavigationCheck.java` | 测试 | 改：导航项数、页面控件、端到端真实 jar 扫描、配置页断言 |
| `README*.md`、`PROGRESS.md`、`AI_REPORT.md` | 主 agent | 改：文档同步 |