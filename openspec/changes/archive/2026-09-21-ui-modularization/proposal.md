# Proposal: 界面层模块化（拆开 Main.java）

## Why

`src/Main.java` 实测 1757 行、66 个方法、约 100 个字段，承担了整个应用的全部页面行为：
导航展开、代理拦截、抓包转换、Shiro 利用、探测参数、配置读写都挤在一个类里。
后果是可验证的，不是主观判断：

- 任意一个页面的改动都要改同一个文件，两个页面同时开发必然冲突；
- 自检只能通过反射按字段名访问 `Main` 的成员，于是「字段放在哪个类」被自检绑死，
  想拆分必须先动测试；
- 单文件已超过 600 行的经验阈值近 3 倍，阅读与定位成本随功能线性增长。

设计文档 `docs/DESIGN-modularization.md` 早就给出了三阶段方案，但一直未实施，
原因正是上面的第二条：拆分与自检互相锁死。

## What Changes

- 把页面**行为**从 `Main` 搬到 `src/ui/` 下按页划分的控制器：
  `NavController`、`ProbeController`、`CaptureController`、`ProxyController`、
  `ShiroController`、`ConfigController`，以及载荷工作台三页的装配类 `WorkbenchPages`。
- 把配置页的 38 项控件与 10 个分组清单搬到 `ConfigForm`，使「新增一项配置」只改一处。
- 各视图类新增 `defaults()` 控件工厂，把控件初值从组合根挪回视图类内。
- 新增 `ui/UiHandle` 稳定门面与 `ui/WidgetRegistry` 登记表：自检按**名字**取控件，
  不再依赖控件声明在哪个类。三个 UI 自检的取数辅助方法改为调用该门面，
  **132 处字段访问与 3 个方法调用的断言文字与数量一条不改**。
- `Main` 收敛为组合根：保留窗口骨架、页面路由、控制器装配与自检转发方法。

## Capabilities

### New Capabilities

- `ui/modularization`: 界面层结构契约。规定页面行为必须与视图分离、
  单文件规模必须有上限、自检必须通过稳定门面取控件，且这些约束可机械核对。

### Modified Capabilities

- `codebase/dependency-boundary`: 新增「界面层文件规模」与「自检不得直接反射界面内部字段」
  两条要求，把本次的结构约束纳入既有边界契约。

## Non-goals

- 不改变任何界面外观、文案、交互与配置键名。
- 不改变任何自检的断言内容、数量与输出文字（除取数路径）。
- 不引入任何第三方依赖，不引入 DI 框架，页面间依赖用构造器传参显式表达。
- 不拆分 `src/proxy/ProxyServer.java`（768 行）与 `src/shiro/ShiroEngine.java`（715 行）：
  两者都在 800 行以内且有独立自检，不在本次范围。
- 不动 `python/fj_probe.py`（单文件引擎，按命令行参数分发模式）。

## Impact

| 路径 | 角色写入域 | 性质 |
| --- | --- | --- |
| `src/Main.java` | ui agent | 1757 → 311 行，仅剩装配与切页 |
| `src/ui/NavController.java` | ui agent | 新增 |
| `src/ui/ProbeController.java` | ui agent | 新增 |
| `src/ui/CaptureController.java` | ui agent | 新增 |
| `src/ui/ShiroController.java` | ui agent | 新增 |
| `src/ui/ProxyController.java` | ui agent | 新增 |
| `src/ui/ConfigForm.java` | ui agent | 新增 |
| `src/ui/ConfigController.java` | ui agent | 新增 |
| `src/ui/WorkbenchPages.java` | ui agent | 新增 |
| `src/ui/WidgetRegistry.java` | ui agent | 新增 |
| `src/ui/UiHandle.java` | ui agent | 新增 |
| `src/ui/{Probe,Capture,Shiro,Proxy}Page.java` | ui agent | 各新增一个 `defaults()` 控件工厂 |
| `src/ui/ConfigPage.java` | ui agent | 新增 `widgets(...)` 装配助手 |
| `tests/Ui*Check.java` | test agent | 取数辅助方法改走 `ui.UiHandle` |
| `openspec/specs/codebase/dependency-boundary/spec.md` | 主 agent | 补两条界面层约束 |
| `docs/DESIGN-modularization.md` | 主 agent | 实施记录与三处方案修正 |
| `README.md` / `README.en.md` / `PROGRESS.md` / `AI_REPORT.md` | 主 agent | 结构与结论同步 |

对既有功能的影响：无。六套 Java 自检与 99 项 Python 测试在改动前后同为全绿，
自检断言数为 37 / 46 / 61 / 27 / 169 / 95，未增未减。
