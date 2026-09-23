# Proposal: 日志配置进入配置页

## Why

日志内核的内建默认值能保证「开箱有日志」，但使用者需要能改三件事：
关掉它（自检或干净环境）、换目录（安装目录只读或想集中收集）、
调保留天数（长期驻留的机器不想只留一周）。按仓库既有约定，
这些可长期保存的配置必须落在配置页，否则等于写死在代码里。

## What Changes

- 配置页新增「日志配置」分组：是否启用、级别、目录、保留天数、是否回显到控制台。
- 启动时按配置初始化记录通道，并记录一行启动摘要（Java 版本、配置路径、日志路径）。
- 安装未捕获异常处理器，把后台线程异常写进日志。
- 配置页显示当前日志目录与本次启动清理掉的文件数，便于确认配置是否真的生效。

## Capabilities

### New Capabilities

- `ui/logging-settings`：日志相关配置项在配置页的呈现与下发。

## Non-goals

- **不做日志查看器**：不做界面内浏览、搜索或高亮日志。
- **不做日志级别热切换的界面开关之外的能力**：不提供按模块设置级别。
- **不改变既有分组**：既有分组与配置项一律不动，只新增一个分组。
- **不改共享内核**：`src/util/**` 的改动见 change `logging-core`。

## Impact

| 路径 | 写入域 | 动作 |
| --- | --- | --- |
| `src/ui/ConfigForm.java`、`src/ui/ConfigController.java`、`src/ui/LoggingBootstrap.java` | 界面 agent | 新增分组、读写映射、启动初始化 |
| `src/ui/WidgetRegistry.java` | 界面 agent | 改：登记新控件供自检读取 |
| `src/Main.java` | 界面 agent | 改：启动时初始化日志并安装未捕获处理器 |
| `tests/UiNavigationCheck.java` | 测试 | 改：新增分组与控件断言（只增不减） |
| `README*.md`、`PROGRESS.md`、`AI_REPORT.md` | 主 agent | 改：文档同步 |
