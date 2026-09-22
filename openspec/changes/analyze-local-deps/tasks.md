# Tasks

## 1. 分析内核

写入域：`src/analyzer/**`（角色：exploit）
- [x] 1.1 依赖坐标读取：四类来源分层采信，键统一小写；`JarFile` 条目枚举不解压不落盘不加载类；验证：`AnalyzeCheck` 解析断言通过
- [x] 1.2 工程 `pom.xml` DOM 解析（关 XXE，只取顶层 dependencies）；验证：`AnalyzeCheck` pom 断言通过
- [x] 1.3 版本比较：逐段比较 + `MISSING` 哨兵；验证：`1.2.80 > 1.2.9`、`1.0-rc1 < 1.0` 断言通过
- [x] 1.4 规则表 25 条与区间判定；验证：命中 / 不命中 / group 不符三条断言通过
- [x] 1.5 结论按可信度排序并带依据与跳转 key；验证：排序与依据断言通过
- [x] 1.6 外部引擎调用：显式工作目录、超时杀进程树、清临时目录；验证：失败路径断言通过
- [x] 1.7 反编译：CFR 封装 + `extraclasspath`；验证：能力清单断言通过

## 2. 编排层

写入域：`src/analyze/**`（角色：exploit）
- [x] 2.1 本地分析 / 调引擎 / 查询 / 反编译四个入口；验证：`AnalyzeCheck` 端到端断言通过
- [x] 2.2 命令行拼装集中一处（链路唯一契约）；验证：命令行断言通过
- [x] 2.3 超时低于下限提前拦下并说明原因；验证：下限断言通过

## 3. 查询脚本

写入域：`python/jar_report.py`（角色：probe）
- [x] 3.1 只读 `mode=ro` 连接，五类查询；验证：`test_jar_report.py` 通过
- [x] 3.2 自写 26 项 sink 清单；验证：清单条数断言通过
- [x] 3.3 缺表时给可读提示而不是抛栈；验证：缺表断言通过

## 4. 资源与边界

写入域：`src/pom.xml`、`tests/test_decoupling.py`、`tools/audit_boundary.py`、`tools/lib/RoleMatrix.ps1`、`docs/AGENT-ROLES.md`（角色：主 agent / 测试）
- [x] 4.1 `jar_report.py` 打进 JAR 的 python 目录；验证：构建后 JAR 内含该资源
- [x] 4.2 `analyzer` 列为叶子层、`analyze` 只依赖 `analyzer`；验证：`audit_boundary.py` 结论「全部通过」
- [x] 4.3 角色矩阵与职责文档同步新增两个包的写入域；验证：`check_agent_tools.ps1` 通过

## 5. 自检

写入域：`tests/**`（角色：测试）
- [x] 5.1 内核自检 61 条（版本语义 / 四类来源 / 规则判定 / 端到端 / 失败路径）；验证：`AnalyzeCheck` exit 0
- [x] 5.2 查询脚本单测 13 项（模拟库）；验证：`python -m unittest` 通过

## 6. 收尾

写入域：`README*.md`、`PROGRESS.md`、`AI_REPORT.md`、`docs/DESIGN-analyze.md`（角色：主 agent）
- [x] 6.1 设计文档成文；验证：`docs/DESIGN-analyze.md` 存在且含根因段
- [x] 6.2 中英 README 同步；验证：两处小节一一对应
- [x] 6.3 追加 AI 报告与进度；验证：两份文件含本轮改动
- [x] 6.4 构建并复验 JAR 时间晚于全部源文件；验证：`build.ps1` 输出