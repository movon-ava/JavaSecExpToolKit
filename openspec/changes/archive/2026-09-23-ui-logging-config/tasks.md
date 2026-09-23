# Tasks

## 1. 配置项与界面

写入域：`src/ui/ConfigForm.java`、`src/ui/ConfigController.java`、`src/ui/WidgetRegistry.java`（角色：界面 agent）
- [x] 1.1 新增「日志配置」分组五项控件；验证：`UiNavigationCheck` 分组断言通过
- [x] 1.2 复位时按配置回填、保存时落盘；验证：`UiNavigationCheck` 回填断言通过
- [x] 1.3 控件登记进自检门面；验证：自检能按名字取到五项控件

## 2. 启动初始化

写入域：`src/ui/LoggingBootstrap.java`、`src/Main.java`（角色：界面 agent）
- [x] 2.1 读配置之前用默认值初始化一次；验证：`LogCheck` 启动路径断言通过
- [x] 2.2 读配置之后按配置再初始化一次；验证：配置值生效断言通过
- [x] 2.3 安装未捕获异常处理器；验证：`LogCheck` 处理器断言通过
- [x] 2.4 记录启动摘要；验证：当天日志含 Java 版本与日志目录

## 3. 收尾

写入域：`README*.md`、`PROGRESS.md`、`AI_REPORT.md`（角色：主 agent）
- [x] 3.1 中英 README 的配置页小节补上「日志配置」；验证：两处小节一一对应
- [x] 3.2 追加 AI 报告与进度；验证：两份文件含本轮改动
- [x] 3.3 构建并复验 JAR 时间晚于全部源文件；验证：`build.ps1` 输出
