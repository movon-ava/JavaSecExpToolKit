# Tasks

## 1. 启动与生命周期

写入域：`src/ui/StartupWarmup.java`、`src/ui/WorkbenchPages.java`（角色：界面 agent）
- [x] 1.1 预热单步失败记录异常类型与栈，仍不阻断启动；验证：既有自检全绿
- [x] 1.2 关闭阶段的异常被记录，仍不阻断退出；验证：既有自检全绿

## 2. 代理与抓包

写入域：`src/proxy/**`（角色：抓包 agent）
- [x] 2.1 监听循环异常在运行中时记录为警告，停止时不记录；验证：`ProxyServerCheck` 全绿
- [x] 2.2 单条连接处理失败记录异常；验证：`ProxyServerCheck` 全绿
- [x] 2.3 隧道两端转发中断分别记录；验证：`ProxyServerCheck` 隧道断言全绿
- [x] 2.4 上游连接失败记录目标与端口；验证：`ProxyServerCheck` 全绿

## 3. 利用链与漏洞分析

写入域：`src/payload/**`、`src/shiro/**`、`src/analyzer/**`（角色：利用链 agent）
- [x] 3.1 载荷构建失败记录异常栈；节点查询、指标与上下文读取失败记录为调试级；验证：`PayloadCheck` 全绿
- [x] 3.2 Shiro 探测请求与响应体读取异常记录；验证：`ShiroCheck` 全绿
- [x] 3.3 引擎与脚本输出读取中断、临时目录清理失败记录；验证：`AnalyzeCheck` 全绿
- [x] 3.4 引擎超时记录为「超时已终止」并附上限，与报告口径一致；验证：`AnalyzeCheck` 全绿

## 4. 自检

写入域：`tests/**`（角色：测试）
- [x] 4.1 内核自检：命名、保留策略、目录解析、级别、跨天切换、失败静默、未捕获异常；验证：`LogCheck` exit 0
- [x] 4.2 Python 单测覆盖保留边界与文件名解析；验证：`python -m unittest` 通过
- [x] 4.3 新入口登记到自检守卫清单；验证：`test_selfcheck_hygiene.py` 通过
- [x] 4.4 依赖边界自检覆盖新增内核；验证：`audit_boundary.py` 结论「全部通过」

## 5. 收尾

写入域：`README*.md`、`PROGRESS.md`、`AI_REPORT.md`（角色：主 agent）
- [x] 5.1 中英 README 同步新增小节；验证：两处小节一一对应
- [x] 5.2 追加 AI 报告与进度；验证：两份文件含本轮改动
- [x] 5.3 构建并复验 JAR 时间晚于全部源文件；验证：`build.ps1` 输出
