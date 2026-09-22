# Proposal: 漏洞分析 - 本地依赖分析与调用链查询

## Why

拿到一个来源不明的 jar 时，使用者要回答两个不同层级的问题，而本工具此前**一个都答不了**：

1. 这里面引入了什么组件，版本落在哪些公开公告的受影响区间里；
2. 这些组件之间怎么被调用，某条危险链是否真的可达。

只做第 2 件事，每换一个文件都要等几分钟，而绝大多数时候结论在第 1 步就已经明确；
只做第 1 件事，所有结论都停在「可能受影响」，无法判断链是否可达。
因此做成**分层**：本地依赖分析永远先跑（秒级），调用链分析按需触发（分钟级），
反编译只在需要看源码确认时用。

## What Changes

- 新增本地依赖分析：读 jar 内的 Maven 坐标，按来源分层采信
  （`pom.properties` > `pom.xml` > `MANIFEST.MF` > 文件名推断），
  来源决定结论可信度；同时支持读源码工程的 `pom.xml` 做口径对照。
- 新增 25 条内置规则：fastjson 6 条（按 autoType 绕过手法分段）+ fastjson2 1 条、
  Shiro 4 条、gadget 依赖 7 条、组件 RCE 7 条；版本区间只支持
  「低于 / 不高于 / 区间」三种形态。
- 新增调用链分析：调用外部 CLI 引擎把 jar 解析成 SQLite 调用图数据库，
  再用 Python 标准库只读查询五类内容（总览 / 入口点 / Sink 命中 / 字符串常量 / 组件清单）。
- 新增反编译：用运行期依赖已带的 CFR 还原指定类或整包源码，供人工确认规则命中。
- 新增 5 个可持久化配置键（默认扫描目标、外部引擎 JAR、引擎工作目录、分析超时、反编译输出目录）。

## Capabilities

### New Capabilities

- `analyze/local-deps`：依赖坐标读取、来源分层与规则判定。
- `analyze/call-graph`：外部引擎建库、超时终止、只读查询。

## Non-goals

- **不做漏洞扫描器的完整替代**：规则只收录「判定后能接上本工具后续动作」的组件，
  不做 CVE 全量匹配，也不给 CVSS 打分。
- **不内置外部引擎**：不把 `jar-analyzer-engine` 的 jar 打进产物，也不新增任何第三方依赖；
  未配置引擎时只跑本地分析，功能不失效。
- **不解析反编译产物**：输出只写不读，本工具不去分析这些源码。
- **不改变既有功能**：Fastjson 探测、代理抓包、抓包转换、Shiro 利用、Payload 生成、
  预设链、toString 链、带外 Jar、恶意服务器、文件上传一律不动。
- **不改共享内核**：`src/config/**` 与 `src/util/**` 只读。

## Impact

| 路径 | 写入域 | 动作 |
| --- | --- | --- |
| `src/analyzer/**` | exploit | 新增：分析内核（叶子层，不依赖任何项目包） |
| `src/analyze/**` | exploit | 新增：编排层（只依赖 `analyzer`） |
| `python/jar_report.py` | probe | 新增：调用链数据库只读查询脚本 |
| `src/pom.xml` | 主 agent | 改：`<resources>` 的 python includes 增加一行（不新增依赖） |
| `tests/AnalyzeCheck.java` | 测试 | 新增：内核自检 |
| `tests/test_jar_report.py` | 测试 | 新增：查询脚本单测（模拟库） |
| `tests/test_decoupling.py`、`tools/audit_boundary.py` | 测试 / 主 agent | 改：新增两个包的依赖边界规则 |
| `tools/lib/RoleMatrix.ps1`、`docs/AGENT-ROLES.md` | 主 agent | 改：写入域矩阵同步 |
| 界面文件与配置分组 | ui | 见 change `ui-analyze-view` |
| `README*.md`、`PROGRESS.md`、`AI_REPORT.md`、`docs/DESIGN-analyze.md` | 主 agent | 改：文档同步 |