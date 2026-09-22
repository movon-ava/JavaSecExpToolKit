# AI 工作报告

## 2026-09-22（本轮：小工具 - 文件上传）

### 一、需求

新增一个「小工具」模块，其中一项功能为**文件上传**：输入 URL、选择文件即可上传，
并返回响应结果。

### 二、根因分析（改代码之前先定位成因）

**现象**：抓包页与 Shiro 页都能发请求，但没有任何入口能把本地文件发出去。

**成因**：既有请求体通道在**类型**上不支持文件上传，不是参数没传对：

1. `python/fj_probe.py` 的 `_request_raw` 固定 `body.encode("utf-8")`，请求体是 `str`。
   文件是任意二进制，先按 UTF-8 编解码一次会把非法字节序列替换成 `\ufffd`，
   上传上去的内容与本地文件不再一致；
2. `multipart/form-data` 需要按 boundary 拼多段（普通字段段 + 文件段 + 结束分隔符），
   且 `Content-Type` 必须带同一个 boundary。界面上只给「请求体文本」是表达不出分段的。

**改法**：新增一条**字节通道**而不是改造既有文本通道——
把 `_request_raw` 拆成 `_request_raw`（文本入口，行为与签名不变）+ `_request_bytes`
（`Optional[bytes]`），两者共用同一套请求头净化与 `_NoRedirect`；上传模式在这条新通道上
用 `uuid4().hex` 生成 boundary 并按字节拼 multipart。

### 三、改动清单

| 文件 | 写入域 | 动作 |
| --- | --- | --- |
| `python/fj_probe.py` | probe | 改：新增 `upload` 模式、multipart 字节构造、报告渲染、六个 CLI 参数 |
| `src/probe/ProbeCommand.java` | probe | 改：新增 `uploadUrl` / `uploadFiles` / `uploadField` / `uploadFields` 与 `upload(...)` 拼装 |
| `src/ui/ToolsUploadPage.java` | ui | 新增：文件上传页视图（Widgets + defaults + build，含 `JFileChooser` 实例） |
| `src/ui/ToolsUploadController.java` | ui | 新增：选文件 / 上传 / 复制结果行为 |
| `src/ui/NavController.java` | ui | 改：新增「小工具」一级分类与「文件上传」二级项 |
| `src/ui/ConfigForm.java` | ui | 改：新增「小工具配置」分组与三个控件 |
| `src/ui/ConfigController.java` | ui | 改：`applyDefaults` 接线 + `resetForm` / `save` 两处同步 |
| `src/ui/WidgetRegistry.java` | ui | 改：登记上传页 10 个控件与配置页 3 个控件 |
| `src/Main.java` | ui | 改：装配页面、路由 `tools.upload`、`runUpload` 自检转发 |
| `tests/UiNavigationCheck.java` | 测试 | 改：导航项数 6→7、新增上传页与配置页 20 条断言、补 `setText` 辅助 |
| `tests/UiSwitchEndToEndCheck.java` | 测试 | 改：`/upload` 桩端点与 9 条端到端断言 |
| `tests/test_probe.py` | 测试 | 改：新增 `UploadModeTest` 8 项单测 |
| `README.md` / `README.en.md` | 主 agent | 改：导航 / 配置页 / 文件上传 / 项目结构 / 命令行速查中英同步 |
| `openspec/changes/tools-file-upload/**` | 主 agent | 新增后归档为 `openspec/changes/archive/2026-09-22-tools-file-upload`，主 spec 落地 `openspec/specs/tools/file-upload/spec.md` |

### 四、功能行为

- 页面：`小工具 → 文件上传`。目标 URL、选择文件（系统文件对话框，路径框只读）、
  表单字段名（默认 `file`）、附加表单字段（JSON）、请求头（JSON）、上传、复制结果。
- 引擎：一次 multipart POST，不跟随 3xx；记录状态码 / 耗时 / 响应头 / 响应体 / Set-Cookie。
- 上传属于「内容即结果」，报告**始终详细**（与 `capture` / `convert` 处理一致）。
- 单文件上限 8 MB，超限跳过并说明原因（不静默截断，避免拿着被改过的样本继续排查）。
- 失败路径全部给可读结论：空 URL / 未选文件 / 文件不可读 / 超限 / 附加字段非法 JSON /
  连接失败 / 3xx（附 Location）/ 401 / 403（提示补 Cookie）/ 404 / 5xx（提示核对字段名）。
- 新增可持久化配置进配置页「小工具配置」：默认上传 URL、默认表单字段名、上传超时（默认 30 秒）。

### 五、验证记录

| 验证项 | 命令 | 结果 |
| --- | --- | --- |
| Python 单测 | `python -X utf8 -m unittest discover -s tests` | **114 项 OK**（原 106 + 新增 8） |
| 依赖边界 | `python -X utf8 tools\audit_boundary.py` | 结论「全部通过」（无环 / 无越界 / 无叶子出边 / 无内核反向依赖） |
| 工具链自检 | `powershell -File tools\check_agent_tools.ps1` | **91 项通过** |
| 界面与端到端自检 | `UiNavigationCheck` / `UiSwitchEndToEndCheck` / `UiShiroCheck` / `PayloadCheck` / `ShiroCheck` / `ProxyServerCheck` | 六套全部 exit 0 |
| OpenSpec | `openspec validate --all --strict` | **7 项 passed**（新增 `spec/tools/file-upload`） |

端到端实测（`UiSwitchEndToEndCheck`，桩服务校验请求原文）：
`文件上传 -> 探测结论: 已上传 1 个文件（407 字节请求体），响应 HTTP 200（5.69 ms，40 字节）`，
桩服务确认 `Content-Type` 含 `multipart/form-data`、请求体含 `name="csrf"` 与文件内容标记、
请求头带上了 `JWT_TOKEN=upload`。

### 六、监控复核

| 复核项 | 结论 |
| --- | --- |
| 越界改动 | 无：改动只落在 `python/fj_probe.py`、`src/probe/ProbeCommand.java`、`src/ui/`、`src/Main.java`、`tests/`、`README*.md`、`openspec/`；`src/pom.xml`、`src/config/AppConfig.java`、`src/util/` 未被触碰 |
| 新依赖 | 无：只用 Java 标准库（`JFileChooser` / `HttpURLConnection` 未引入）与 Python 标准库（`uuid` / `json` / `os`） |
| 偷懒代码 | 无 TODO / FIXME / pass / 空方法体；新增两个类均有完整实现 |
| 断言放宽 | 未放宽：`UiNavigationCheck` 断言数由 225 增至 248（+23），其余五套不变，一条未删 |
| 文件规模 | `src/Main.java` 345 行（≤400）；`src/ui/` 最大 583 行（`PayloadController`，≤600），由 `tests/test_decoupling.py` 机械校验 |
| 配置落页 | 三个新配置项（`upload_url` / `upload_field` / `upload_timeout`）全部落到配置页「小工具配置」分组 |
| 构建一致性 | `build.ps1` 逐文件校验 JAR 时间晚于 `src/`、`python/`、`tests/` 下全部源文件 |

### 七、备份与 Git

- 改动前快照 `.backups/20260922-162456`（158 个文件），轮转删除最旧的
  `20260922-101154` 与 `20260922-105212`，现保留最近三份：
  `20260922-123500`、`20260922-161611`、`20260922-162456`。
- `.backups/`、`target/`、`JavaSecExpToolKit.jar`、`.pi/` 由 `.gitignore` 忽略。

### 八、如实说明的局限

1. 界面一次只上传**一个**文件；引擎侧 `--upload-file` 支持重复指定，但界面未开放多选。
2. 上传不做响应语义判定：不会告诉你「上传成功了」「这里是 webshell 路径」——
   这些结论取决于目标业务，工具只如实记录这一次请求的响应。
3. 8 MB 上限是工程折中：足够覆盖常见样本，但不适合传大文件；需要更大文件时改
   `UPLOAD_MAX_BYTES` 并接受内存占用。
4. `Content-Type` 若由使用者在请求头里手填 `multipart/form-data`，则以其填写值为准；
   此时 boundary 是否与请求体一致取决于填写内容，工具不再覆盖。
5. 上传不进「一键发送」链路：抓包页与代理页本轮未新增入口。


## 2026-09-18

- 按需求在 `G:\java\JavaSecExpToolKit` 从空目录新建独立工程。
- 参考 `G:\java\FastjsonExpToolkit` 的探测思路，但没有复制其 PoC、利用链、WAF 绕过或 DNS 外带功能。
- 新增 Python 无害探测引擎：基线 JSON、损坏 JSON、`@type` 特征识别。
- 新增 Java 8 兼容 Swing 桌面界面，调用本地 Python 探测引擎并展示 JSON 结果。
- 新增 `build.ps1`，默认使用 `E:\java\jdk17`，构建后校验 JAR 构建时间不早于 Java 源文件时间。
- 新增本地模拟 HTTP 服务测试，覆盖 Fastjson 特征识别。
- 完成工具分类式 Swing 界面，包含主页和 Fastjson 探测分类。
- 修复探测按钮事件注册及 Java 8 lambda 结果回调兼容性。
- 将 `python/fj_probe.py` 作为资源打入 JAR，支持 `java -jar` 直接启动。
- 使用 `E:\java\jdk17` 完成构建；JAR 内容包含 `Main.class` 和 `python/fj_probe.py`。
- 构建验证通过：测试 `Ran 1 test ... OK`，JAR 时间为 `2026-09-18 14:38:35`，晚于 `src/Main.java` 的 `2026-09-18 14:38:15`。
- 本轮修改备份为 `.backups/20260918-143911`；当前仅保留最近三次修改备份。
- 按最新要求新增 Maven 构建配置 `pom.xml`，保留既有 `src`、`python` 目录布局。
- Maven 编译目标为 Java 8，打包 `python/fj_probe.py`，并将可执行 `JavaSecExpToolKit.jar` 输出至项目根目录。
- `build.ps1` 改为调用 Maven，`run.ps1` 改为直接启动根目录 JAR；构建后继续校验 JAR 构建时间。
- Maven 构建验证通过：Python 测试 `Ran 1 test ... OK`，根目录 JAR 已生成并包含 `Main.class`、`python/fj_probe.py`；构建时间为 `2026-09-18 15:09:59`，晚于 Java 源文件时间。
- 本轮修改备份为 `.backups/20260918-151053`。
 - 最终 Maven 构建验证通过：`JavaSecExpToolKit.jar` 位于项目根目录，大小 `11749` bytes，构建时间为 `2026-09-18 15:12:39`，晚于 `src/Main.java` 的修改时间。
 - JAR 内容校验通过，包含 `META-INF/MANIFEST.MF`、`Main.class` 和 `python/fj_probe.py`；Python 测试 `Ran 1 test ... OK`。
- 本次报告更新前备份为 `.backups/20260918-151401`。
- 本次完成 Swing UI 优化：初始窗口尺寸调整为屏幕的约 75%，侧栏、主页和 Fastjson 探测页改为更简洁的层级布局。
- 新增窗口尺寸监听，标题、导航、标签、输入框、按钮和结果区字体按窗口尺寸比例缩放；未调整 Fastjson 探测流程。
- 本轮 UI 修改前备份为 `.backups/20260918-151845`。
 修复 UI 重构后的等宽字体注册编译错误，结果区字体继续按窗口尺寸比例缩放。
 UI 优化后 Maven 构建验证通过，根目录 JAR 构建时间为 `2026-09-18 15:23:07`，晚于 `src/Main.java` 的 `2026-09-18 15:22:43`。
 Python 测试通过：`Ran 1 test ... OK`；JAR 仍包含 `META-INF/MANIFEST.MF`、`Main.class` 和 `python/fj_probe.py`。
 本次报告更新前备份为 `.backups/20260918-152429`。

## 2026-09-18（本轮：Fastjson 探测新增四类能力）

- 需求：在已有 Fastjson 探测上新增 1) 版本识别 2) 期望类 3) DNS 探针 5) CEYE 确认。
- 备份：改动前快照 `.backups/20260918-154233`（含 `fj_probe.py`、`Main.java`、`test_probe.py`、`AI_REPORT.md`、`README.md`）；备份目录仅保留最近三次。
- `python/fj_probe.py` 重构为统一入口 `run(target, mode, timeout, headers, base_body, dnslog_host, extras)`，新增 `detect / version / expect / dns / ceye` 五种模式；保留旧 `detect()` 兼容入口。
- 版本识别：新增 `autotype_class / autotype_random`（AutoType 开关）、`autocloseable_exact`（残缺 JSON 回显 `fastjson-version` 精确版本）、`probe_1_2_83`、以及 `offline_exception / offline_autocloseable / offline_class_jdbc / offline_jdbc` 布尔探针，输出 `version_detail` 与 `version_range`；无报错差异时用 `negative_control` 指纹判定。
- 期望类：新增 `baseline / feature_type / empty_key_root / empty_key_nested` 四探针与判定矩阵，输出 `has_expect_class`、`expect_not_map`、`version_lt_1_2_68_hint` 与置信度。
- DNS 探针：新增 `Inet4Address / InetSocketAddress / URL-key / Exception 兜底` 四种无害探针，以及版本 DNS 探针（`<=1.2.47`、`<=1.2.68`、`1.2.80/1.2.83` 双域名区分）。
- CEYE 确认：新增 `api.ceye.io` 记录查询（`CEYE_TOKEN` / `CEYE_DOMAIN` / `CEYE_API`，支持界面传参），按 filter 轮询并回填 `dns_hits`。
- `src/Main.java`：Fastjson 探测页新增「探测模式」下拉与业务参数、DNSLog 主机、CEYE Filter、CEYE Token、DNS 等待输入框；探测线程按模式拼接 Python 参数；仅新增控件，未改动主页与说明逻辑。
- 测试：`tests/test_probe.py` 扩展为 10 项，覆盖识别、版本区间、期望类矩阵、DNS 探针、CEYE 缺 Token、未知模式；`python -m unittest discover -s tests` 全部通过。
- 构建验证：`build.ps1` 构建成功，JAR 时间 `2026-09-18 16:10:58`，晚于 `src/Main.java` 的 `2026-09-18 16:10:00`；JAR 大小 `25659` bytes，包含 `META-INF/MANIFEST.MF`、`Main.class`、`python/fj_probe.py`。
- 端到端冒烟：对本地模拟 Fastjson 端点依次运行五种模式，识别/版本（回显 1.2.68，区间 1.2.48-1.2.68）/期望类（判定存在期望类且非 Map，置信度 0.90）/DNS 探针均正常，CEYE 模式在无 Token 时按预期提示配置。
- `README.md`：更新功能范围与 CLI 用法示例（含 `--mode`、`--base-body`、`--dnslog-host`、`--ceye-token`）。

## 2026-09-18（本轮：新增配置页）

- 需求：新增配置页，用于保存较固定的配置项（CEYE token 等）。
- 备份：改动前快照 `.backups/20260918-161920`（含 `fj_probe.py`、`Main.java`、`test_probe.py`、`AI_REPORT.md`、`README.md`）；备份目录仅保留最近三次。
- `src/Main.java`：导航新增「配置」页（主页 / Fastjson 探测 / 配置），新增 `showConfig` / `configForm` / `configRow` 布局与 `styleSecondaryButton` 次要按钮样式。
- 配置字段：CEYE 域名、CEYE Token（`JPasswordField` 遮掩）、CEYE API、Python 解释器、默认超时、默认 DNS 等待、默认业务参数、默认 DNSLog 主机、默认 CEYE Filter。
- 配置文件：保存在 `%USERPROFILE%\.JavaSecExpToolKit\config.properties`；`loadConfig` 启动时读取，`saveConfigFromForm` 保存并即时回填探测页，`resetConfigForm` 恢复为当前已存配置，状态标签显示结果与保存路径。
- 探测页调整：移除内联 CEYE Token 输入框（改由配置页统一维护），其余控件未动；探测命令不再把 Token 放进命令行参数，改由 Python 侧从配置文件读取，避免 Token 出现在进程列表。
- `python/fj_probe.py`：新增配置文件读取（`_config_file`），支持 `#` / `!` 注释、Java `Properties.store` 转义还原（`\:`、`\\`、`\uXXXX` 等）与长值折行续行合并；CEYE 配置优先级为 显式参数 > 环境变量 > 配置文件。
- 测试：`tests/test_probe.py` 新增配置页相关 5 项（解析、注释忽略、转义还原、长值续行、参数覆盖），共 16 项；`python -m unittest discover -s tests` 全部通过。
- 构建验证：`build.ps1` 构建成功，JAR 时间 `2026-09-18 16:28:43`，晚于 `src/Main.java` 的 `2026-09-18 16:21:57`；JAR 大小 `29503` bytes，包含 `META-INF/MANIFEST.MF`、`Main.class`、`python/fj_probe.py`，且包内 `fj_probe.py` 与源码哈希一致。
- 界面验证：离屏渲染配置页与探测页截图，布局正常；`java -jar` 启动 7 秒无异常；Java 保存配置后新实例可载入并回填，Python 侧解析结果与界面输入一致（含 `C:\Python313\python.exe` 与 `http://api.ceye.io/v1/records` 的转义还原）。
- `README.md`：补充五种探测模式说明、配置页字段与配置文件位置、CLI 各模式示例与测试命令。

## 2026-09-18（本轮：新增 AGENTS.md 开发约束）

- 需求：编写 `AGENTS.md`，沉淀本项目开发所需的约束。
- 备份：改动前快照 `.backups/20260918-163747`（含 `fj_probe.py`、`Main.java`、`test_probe.py`、`AI_REPORT.md`、`README.md`）；备份目录仅保留最近三次。
- 新增 `AGENTS.md`（仓库根，覆盖全部子目录），共 10 节：项目定位、硬性约束、构建与运行、打包契约、代码约定、安全边界、测试、配置、已知遗留、变更检查清单。
- 硬性约束沿用仓库既有规则：最小改动（禁止改无关功能与无关配置）、每次改前备份且只留最近三次、每次改动写入 `AI_REPORT.md`、每次构建后校验 JAR 时间。
- 打包契约记录了 `pom.xml` 的非默认配置：`sourceDirectory=src`、`release=8`、`finalName`、`mainClass=Main`、jar 输出到仓库根、`python/fj_probe.py` 资源映射；并明确 JAR 必须包含的三个条目。
- 代码约定区分两侧：Java 侧约束 Java 8 语法、`track()` 字号注册、复用 `pagePanel/surface/label/constraints/styleField/primaryButton` 构件、后台线程 + `invokeLater`、`extractProbe()` 临时文件；Python 侧约束仅用标准库、保留 `from __future__ import annotations`、`run()` 单一入口、返回结构统一带 `mode/target/summary/evidence/limitations`、探针字符串不得被 `json.dumps` 重新序列化。
- 安全边界明确禁止利用链、命令执行、文件读写、内存马与 payload 投递，并要求 Token 不得进入命令行参数。
- 配置一节记录 `%USERPROFILE%\.JavaSecExpToolKit\config.properties` 的双向读写契约、Java Properties 转义兼容要求与"显式参数 > 环境变量 > 配置文件"优先级。
- 已知遗留记录了 `build/`、`dist/` 为早期手工编译残留（时间戳早于 Maven 引入，当前 Maven 构建不使用），避免后续误用。
- 验证：文档内 PowerShell 备份片段已实际执行通过（5 个文件均成功复制到临时备份目录后清理）；本轮未改动任何源码与 `pom.xml`，因此无需重建。
- 构建产物状态确认：根目录 `JavaSecExpToolKit.jar` 大小为 `29503` bytes，时间为 `2026-09-18 16:28:43`，仍晚于 `src/Main.java`（`16:21:57`）与 `python/fj_probe.py`（`16:28:13`），无需重新构建。
- `python -m unittest discover -s tests` 复跑通过：`Ran 16 tests ... OK`。

## 2026-09-18（本轮：导航两级化 + 配置页分组）

- 需求：配置页移到主页下方并对配置分类（通用配置 / 各功能独有配置）；Fastjson 探测归入 FastJson 大类，作为二级子功能。
- 备份：改动前快照 `.backups/20260918-164940`（含 `fj_probe.py`、`Main.java`、`test_probe.py`、`AI_REPORT.md`、`README.md`、`AGENTS.md`）；备份目录仅保留最近三次。
- 导航结构：由平铺三项改为两级。新增 `NAV_ITEMS` 常量与内部类 `NavItem`（key / label），顺序为 `主页` → `配置` → `FastJson`（分类）→ `Fastjson 探测`（二级）；二级项按 key 中的 `.` 判定，渲染器自动缩进并降低字色以区分层级。
- 页面分发：`navigationList` 泛型改为 `JList<NavItem>`；新增 `openSelected()` 统一分发页面、`selectNav(key)` 支持按 key 跳转，替换原先按下标硬编码的 `if/else`。
- 新增 FastJson 分类页：`showFastjsonCategory()` + 通用化的 `categoryCard(...)`，展示二级功能入口；主页卡片改为「检测分类 → FastJson → 查看分类」，跳转到分类页。
- 配置页分组：`showConfig()` 改为分组布局，`configGroup(title, hint, ConfigRow[])` 生成一组，新增内部类 `ConfigRow`（label / field / hint）描述行；抽出 `pageHeading(...)` 复用三处页面标题。
  - `通用配置`：Python 解释器、默认超时。
  - `FastJson 配置`：CEYE 域名、CEYE Token、CEYE API、默认 DNS 等待、默认业务参数、默认 DNSLog 主机、默认 CEYE Filter。
- 配置页改为可滚动（`JScrollPane` + 隐藏边框）以容纳多分组；`保存配置` / `恢复默认` 按钮固定在页面底部，不随内容滚动。
- 未改动任何配置项的键名与语义：`python` / `timeout` / `ceye_*` / `dns_wait` / `base_body` / `dnslog_host` / `dns_filter` 全部保持原样，`config.properties` 向后兼容。
- `python/fj_probe.py` 与 `pom.xml` 本轮未改动。
- 验证：`python -m unittest discover -s tests` 通过 `Ran 16 tests ... OK`。
- 构建验证：`build.ps1` 构建成功，JAR 时间 `2026-09-18 16:55:00`，晚于 `src/Main.java` 的 `2026-09-18 16:54:57`；JAR 大小 `32840` bytes。
- 界面验证：离屏渲染主页、FastJson 分类页、配置页、探测页截图，层级与分组显示正常；配置保存往返复测通过（Java 写入后新实例载入并回填探测页，含 `C:\Python313\python.exe` 与 `http://api.ceye.io/v1/records` 的转义还原）。
- `README.md`：新增 Navigation 一节说明两级导航，Settings 一节按 `通用配置` / `FastJson 配置` 分组重写。

## 2026-09-18（本轮：分类下拉展开 + Fastjson 附加探测开关）

- 需求：二级子功能改为下拉展开的形式；Fastjson 功能新增 DNS 探针与 CEYE 确认开关，用于决定是否使用这两个功能进行探测。
- 备份：改动前快照 `.backups/20260918-171444`（含 `Main.java`、`fj_probe.py`、`test_probe.py`、`AI_REPORT.md`、`README.md`、`AGENTS.md`、`pom.xml`、`build.ps1`）；备份目录仅保留最近三次。
- 根因分析（先诊断后动手）：上一轮已加控件但两处未接线。一是 `openSelected()` 在选中分类项时调用 `toggleGroup()`，与 `mousePressed` 里的同一次点击叠加，导致点击被切换两次（只有已选中项能展开）；二是 `runProbe()` 只拼装了业务参数与 CEYE 凭据，从未把 `dnsEnabled` / `ceyeEnabled` 传给 Python，开关不生效；三是 argparse 把 `"--dns/--no-dns"` 当成单个选项串，`--no-dns` 实际无法解析且 `--dns` 会报“expected one argument”。
- `src/Main.java` 导航：`openSelected()` 对分类项直接返回（展开/收起只由鼠标点击触发）；`mousePressed` 保留为分类项的下拉开关；`NavigationRenderer` 为一级分类加粗并使用 `NavItem.displayLabel()` 显示 `▾` / `▸` 状态箭头；`NavItem` 新增 `displayLabel()`。点击「打开探测」仍会展开分组并跳转二级功能。
- `src/Main.java` 开关接线：`runProbe()` 追加 `--dns|--no-dns` 与 `--ceye|--no-ceye`，界面开关与 Python 引擎语义对齐。
- `src/Main.java` 中文编码：Python 子进程在管道中按系统 GBK 输出，Java 按 UTF-8 读取，导致结果区中文乱码（端到端自检发现）。`ProcessBuilder` 增加 `PYTHONIOENCODING=utf-8` 修复，属本次开关提示文案的同一调用链。
- `python/fj_probe.py` 开关语义：新增 `_stage_flags()` 统一读取 `dns_enabled` / `ceye_enabled`；`_attach_optional_stages()` 在「DNS 关闭」「未填 DNSLog 主机」时不再抛 `ValueError` 中断主结果，改为记录 `stages` 与 `notes` 原因；`version` / `dns` / `ceye` 三种模式同样遵守开关。
- `python/fj_probe.py` CLI：`--dns/--no-dns`、`--ceye/--no-ceye` 拆成成对参数并用 `set_defaults` 设默认开启，修复 `--no-*` 无法解析的问题；参数解析抽成 `_build_parser()` 以便单测直接覆盖。
- 测试：`tests/test_probe.py` 新增 `StageSwitchTest` 9 项，覆盖双关闭、CEYE 依赖 DNS、缺主机不报错、仅开 DNS、`version` / `dns` / `ceye` 模式开关、`_flag` 解析与 CLI 开关解析；总计 `Ran 25 tests ... OK`。
- 界面自检：新增 `tests/UiNavigationCheck.java` 与 `tests/UiSwitchEndToEndCheck.java`，离屏验证初始 3 项 → 展开 4 项 → 再次点击收起 3 项、跳转二级功能自动展开、开关默认勾选与取消勾选，并核对端到端 stages 与中文未乱码；截图输出到 `target/ui-check/`。
- 目录整理：按新约束把非根目录必需文件归位。早期手工编译残留 `build/`、`dist/` 移入 `target/legacy-build/`；根目录临时补丁文件清理；可复用的 `tools/apply_patch.py` 归入 `tools/`。本轮未改动 `pom.xml`。
- 构建验证：`build.ps1` 最终构建成功，JAR 时间 `2026-09-18 17:54:38`，晚于 `src/Main.java`（`17:51:24`）与 `python/fj_probe.py`（`17:45:55`）；JAR 大小 `37208` bytes，内容含 `META-INF/MANIFEST.MF`、`Main.class` 与 `python/fj_probe.py`。
- 最终回归：`python -m unittest discover -s tests` 通过 `Ran 25 tests ... OK`；`UiNavigationCheck` 与 `UiSwitchEndToEndCheck` 均输出「全部自检通过」/「端到端自检通过」。
- `README.md`：新增 `Project layout` 一节；导航一节说明下拉展开与箭头；设置一节补充两个附加探测开关及「CEYE 依赖 DNS、默认开启、未填主机则跳过」的语义；CLI 示例补充 `--no-dns` / `--no-ceye`。
- 文档整理：`AI_REPORT.md` 原有「当前限制」段落被重复粘贴了四次，本轮合并为一份，内容未改写。

## 2026-09-18（本轮：探测模式改勾选组 + 期望类传参修复 + 设计文档）

- 需求：`Fastjson 识别` / `版本识别` / `期望类` 由下拉框改为勾选形式，文字不加「启用」，默认全部启用；任务完成后编写设计文档。
- 备份：改动前快照 `.backups/20260918-200416`；另有 `.backups/20260918-195704`（补丁 2 断点回退点）与 `.backups/20260918-201912`（传参修复前）；备份目录按规程仅保留最近三次 `200416` / `201912` / `202709`。
- 断点补齐：上一轮中断在补丁 2（侧边栏箭头右对齐）。该补丁两处 hunk 因原文使用 `\u25be` 转义、补丁写的是字面箭头字符而无法匹配，修正为转义写法后应用成功；`NavigationRenderer` 改为 `BorderLayout` 行渲染，箭头固定整行最右侧，`NavItem.displayLabel()` 同步为 `文字 + 箭头`。
- `src/Main.java` 模式控件：删除 `probeMode` 下拉框与 `modeKey()`，新增 `modeDetect` / `modeVersion` / `modeExpect` 三个 `JCheckBox`（文字为 `Fastjson 识别` / `版本识别` / `期望类`，默认全选）；新增 `modeKeys()` 按固定顺序返回已勾选模式、`modeLabel()` 提供分段标题；一并移除已无引用的 `styleField(JComboBox)`。
- `src/Main.java` 执行流程：`startDetection()` 改为遍历已勾选模式依次调用 `runProbe()`，多选时每段前加 `===== <模式名> =====`，单选时保持原输出格式；未勾选任何模式时提示「请至少勾选一个探测模式」且不启动子进程；`期望类` 未勾选时跳过业务参数校验。
- `src/Main.java` 界面联动：`updateStageFieldState()` 追加 `baseBody` 与 `modeExpect` 的可用性联动，取消勾选 `期望类` 后业务参数框禁用；结果区占位文案由「选择探测模式」改为「勾选探测模式」。
- 根因分析（先诊断后动手）：端到端自检发现 `期望类` 恒报「base_body 不是合法 JSON」。根因是 Java 在 Windows 上为参数补外层引号但不转义内部引号，交给 MSVCRT 解析时内层引号被当作分隔符吃掉，`{"age":20}` 到 Python 变成 `{age:20}`；与 JSON 内容、编码均无关。
- `src/Main.java` 传参修复：新增 `windowsArg()`，按 MSVCRT 规则转义（连续反斜杠仅在紧跟引号或位于结尾时翻倍、引号前置 `\` 并按前置反斜杠数追加 `2n` 个 `\`、整体加外层引号），`isWindows()` 非 Windows 平台原样返回；对脚本路径、`target`、`base-body`、`dnslog-host`、`dns-filter`、`ceye-token`、`ceye-domain` 生效，数字参数不加壳。另修正注释中被打断的示例文本。
- 转义回归验证：用 `ArgMatrix` 对 JSON、含空格 JSON、URL、Windows 路径、域名、含转义引号与反斜杠的 JSON 共 9 组取值逐个实测，回传值与输入完全一致。
- 测试：`tests/UiNavigationCheck.java` 改为校验三个模式默认勾选、文字不含「启用」前缀、`baseBody` 联动与 `modeKeys()` 多选解析，移除 `JComboBox` 依赖；`tests/UiSwitchEndToEndCheck.java` 新增多模式端到端用例，校验三段标题、三段 `mode` 字段、`version_range`、`has_expect_class`、中文未乱码，以及「未勾选任何模式」的提示分支。
- 自检修正：`awaitResult()` 原先在第三段标题出现即返回，导致末段结果尚未写入就断言，改为等待 `has_expect_class` 出现；`setSelected()` 不触发 `ActionListener`，改用 `doClick()` 模拟真实点击以验证输入框联动。
- 测试结果：`python -m unittest discover -s tests` 通过 `Ran 25 tests ... OK`；`UiNavigationCheck` 与 `UiSwitchEndToEndCheck` 均全项通过；`期望类` 单独调用返回正常 `expect` 结果，不再报 JSON 错误。
- 设计文档：新增 `docs/DESIGN.md`，含目标与边界、总体架构、三种探测模式与附加阶段设计、进程间通信（含 Windows 参数转义约束与推导）、配置、界面约定、构建与三层验证、已知限制。
- 文档同步：`README.md` 探测模式一节改写为三勾选框 + 固定顺序 + 分段标题 + `业务参数` 联动；`Project layout` 表新增 `docs/` 行。
- 目录整理：删除根目录残留的 `.patch-main-1.txt`、`.patch-main-2.txt`。
- 构建验证：`build.ps1` 构建成功，JAR 时间 `2026-09-18 20:26:31`，晚于 `src/Main.java`（`20:22:45`）与 `python/fj_probe.py`（`17:45:55`）；JAR 大小 `38994` bytes，内容含 `META-INF/MANIFEST.MF`、`Main.class` 与 `python/fj_probe.py`。

## 2026-09-18（本轮：DNS 探针 / CEYE 确认并入探测模式）

- 需求：将探针和 CEYE 从「附加探测」移动到「探测模式」中，和其他三个配置一样。
- 备份：改动前快照 `.backups/20260918-205151`（含 `Main.java` / `fj_probe.py` / 两份 Java 自检 / `test_probe.py` / `README.md` / `AI_REPORT.md` / `DESIGN.md`）；本轮回填报告后按规程裁剪，现存最近三份为 `20260918-201912` / `20260918-202709` / `20260918-205151`。
- `src/Main.java` 布局：删除独立的「附加探测」行与 `switches` 面板，`dnsEnabled` / `ceyeEnabled` 与 `modeDetect` / `modeVersion` / `modeExpect` 一起放进 `探测模式` 勾选组，成为五个平级模式。
- `src/Main.java` 文案：两个勾选框改为 `DNS 探针` / `CEYE 确认`，去掉「启用」前缀，默认值由 `false` 改为 `true`；顶部范围文案改为 `识别 / 版本 / 期望类 / DNS 探针 / CEYE 确认`；注释同步为「未勾选对应模式时不投递该阶段」。
- `src/Main.java` 模式解析：`modeKeys()` 在原有三项后追加 `dns` / `ceye`，固定顺序为 `detect → version → expect → dns → ceye`；`modeLabel()` 新增 `dns` / `ceye` 分段标题；因此五项可任意组合、也可单独执行。
- `src/Main.java` 语义收敛：五项视为平级 mode，每次只按当前 mode 决定 `--dns/--no-dns` 与 `--ceye/--no-ceye`；`detect` / `version` / `expect` 不再附带 DNS/CEYE 阶段，避免五项全选时同一探针被重复投递。
- `src/Main.java` 联动：保留 `updateStageFieldState()`，改由 `dnsEnabled` / `ceyeEnabled` 勾选状态驱动 `dnslogHost` / `dnsWait` / `dnsFilter` 的可用性；取消勾选后对应输入框禁用且参数不进入命令行。
- `src/Main.java` 体验修复：新增 `hasCeyeCredential()` / `envValue()`，单独执行 `CEYE 确认` 且配置与 `CEYE_TOKEN` / `FJ_CEYE_TOKEN` 均无 Token 时，在启动 Python 前返回可读提示，不再让结果区出现 `ValueError` 文本。
- 测试更新：`tests/UiNavigationCheck.java` 校验五个模式默认勾选、文字不带「启用」、默认输入框可用、取消 DNS/CEYE 后对应输入框禁用，以及未勾选三项后 `modeKeys()` 仅剩 `detect` / `version`。
- 端到端更新：`tests/UiSwitchEndToEndCheck.java` 增加 `DNS 探针` / `CEYE 确认` 独立模式用例、识别模式不附带 DNS/CEYE 的语义用例与 CEYE 缺 Token 可读提示用例；多模式用例前后显式取消 DNS/CEYE。
- 引擎与配置未改动：`python/fj_probe.py`、`pom.xml`、`build.ps1` 均保持不变；本次只让既有 `--dns/--no-dns`、`--ceye/--no-ceye` 接线改由模式勾选驱动。
- 文档同步：`README.md` 与 `docs/DESIGN.md` 更新为五个勾选框、固定执行顺序、DNS/CEYE 独立 mode 与 CEYE 缺 Token 提示说明。
- 测试结果：`python -m unittest discover -s tests` 通过 `Ran 25 tests ... OK`；`UiNavigationCheck` 与 `UiSwitchEndToEndCheck` 均全项通过。
- 构建验证：`build.ps1` 构建成功，JAR 时间 `2026-09-18 21:08:16`，晚于 `src/Main.java`（`21:06:15`）；JAR 大小 `39248` bytes，内容含 `META-INF/MANIFEST.MF`、`Main.class` 与 `python/fj_probe.py`。

## 2026-09-19（授权目标实测：211.154.20.67:7779）

- 任务：对用户提供的已授权地址 `http://211.154.20.67:7779/index/fastjson` 执行测试；按要求**不进行 CEYE 测试**，DNS 探针与 CEYE 确认均关闭。
- 入口确认：`/index/fastjson` 返回的是登录页（JavaSecLab 类靶场，`title=Java Security`，登录表单提交到 `/user/login`）；`POST /index/fastjson` 返回 405。改用 `/v2/api-docs` 枚举出真实漏洞端点 `POST /vulnapi/Fastjson/vul`（Swagger operationId `vulUsingPOST`）。
- 根因分析（先诊断后动手）：未登录时对任意 `/vulnapi/**` 的 POST 都被转发到仅允许 GET 的静态/登录处理器，响应 `405 Allow: GET, HEAD`；这正是「路径存在但方法不匹配」的表现，而非目标不可达。补上登录会话后 POST 即返回业务响应。
- 会话获取：`/captcha` 取验证码图片、人工识读后以默认凭据 `admin/admin` 登录 `/user/login`，服务端下发 `JWT_TOKEN` 与 `JSESSIONID`；后续请求同时携带两个 Cookie 才被鉴权通过。仅做单次默认凭据登录尝试，未进行任何口令爆破。
- 引擎调用方式：未改动 `python/fj_probe.py`，通过临时驱动脚本 `target/tmp/probe_target.py` 直接调用 `fj_probe.run()`，以 `headers={"Cookie": ...}` 携带会话，`extras={"dns_enabled": False, "ceye_enabled": False}` 关闭 DNS/CEYE，`base_body` 为 `{"age":20,"name":"Bob"}`。
- 识别（detect）：`is_fastjson=true`，`confidence=0.806`，`scores.fastjson=7.25`；证据含 `com.alibaba.fastjson.JSONException`、`autoType is not support`、`$ref` 被解析为字面量（`{"ext":"blue","name":"blue"}`）。
- 版本（version）：`reported_version=1.2.41`，`version_detail=1.2.48-1.2.68`，`version_range=<=1.2.68`，`confidence=0.9`，`autotype_enabled=false`，`safemode_enabled=true`；报错回显含 `fastjson-version 1.2.41`（`autocloseable_exact` 探针）。
- 期望类（expect）：`has_expect_class=false`、`expect_not_map=false`、`version_lt_1_2_68_hint=true`、`confidence=0.8`；`Feature` 类因 autoType 关闭被拒，说明接口未绑定期望类而走 Map/通用解析。
- 交叉验证：另行用 Python 原始请求复核 10 组 payload（AutoCloseable 截断回显、Feature、JdbcRowSetImpl、Class+Jdbc、AutoCloseable+BAOS、Exception 混用、`$ref`、基线 JSON 等），与引擎结论一致；5 组畸形 JSON 中 2 组稳定回显 `fastjson-version 1.2.41`。
- 旁证：`/actuator/env`、`/actuator/mappings` 可匿名读取；映射显示 handler 为 `com.best.hello.controller.ComponentsVul.FastjsonVul#vul(String)`，方法限定 `POST`，与实测一致。
- 对照端点：`/vulnapi/Fastjson/safeMode` 对任意 payload 恒返回 `safeMode`，与 vul 端点形成 safe/vul 对照，符合靶场设计。
- 结论：目标确认为 Fastjson 反序列化端点，依赖版本回显 **1.2.41**，落在 `<=1.2.68` 高危区间，autoType 默认关闭（`autoType is not support`）。据此该版本命中已知 Fastjson 反序列化风险面（如 1.2.24/1.2.41 系列的 `JdbcRowSetImpl` JNDI autoType 绕过），属于可直接验证的高危靶点。
- 边界与合规：仅执行识别类探测（指纹、版本、期望类）与 DNS/CEYE 之外的只读核对；DNS 探针与 CEYE 确认按要求全部关闭，未投递任何 DNS 外连、未使用利用链、未执行命令、未读写文件、未上传内存马。所有凭据仅用于本地临时会话，测试结束即清理。
- 工作区：本次未修改任何项目源码、`pom.xml`、`build.ps1` 或引擎文件；临时脚本与探测结果只落在 `target/tmp` 并在结束后清理。按规程仍需记录：**本条为实测记录，未触发构建，因此无新的 JAR 时间需校验**，现存 JAR 仍为 `2026-09-18 21:08:16` / `39248` bytes。

## 2026-09-19（探测准确性修复与结果可读性改造）

- 需求：用工具探测功能对授权目标 `http://211.154.20.67:7779/index/fastjson` 实测时，
  发现探测结果不准确（识别判「非 Fastjson」，版本却给出 `1.2.70-1.2.80`）且可读性差
  （结果区只有一行原始 JSON）；同时完成 Fastjson 识别、版本识别、期望类勾选化、
  文字去「启用」前缀、探针与 CEYE 并入探测模式，并补充设计文档。
- 备份：改动前快照 `.backups/20260919-092435`，改动后快照 `.backups/20260919-094042`；
  按「保留最近三次」裁剪，删除最旧的 `20260918-202709`、`20260918-205151`。
- 根因（先诊断后动手）：目标 URL 实际是只接受 GET 的登录页，所有 POST 返回
  `405 Allow: GET, HEAD` 且响应体为空。`_version_response_errored()` 把「HTTP >= 400」
  一律当作解析器报错，导致 11 个布尔探针全部 `errored=True`，
  `_infer_version()` 据此推出 `1.2.70-1.2.80` 并给出 `confidence=0.9`，
  与 `detect` 的「非 Fastjson」结论自相矛盾。核心错误是把
  **基础设施层拒绝**与**JSON 解析器报错**混为一谈。
- `python/fj_probe.py` 新增传输层判定 `_transport_block(pairs)`：全连接失败、
  状态码集合 ⊆ `INFRA_STATUSES`（404/405/406/415/429/500/501/502/503/504）、
  或「全部 >=400 且响应体全空」时判定为「探针未到达解析器」；只要任一响应体命中
  `ALL_PARSER_MARKERS`（fastjson + Jackson/Gson/org.json/Hutool 特征）即放弃判定，
  保证真阳性不被误杀；连接失败与状态码混合时不判定。新增 `HTTP_STATUS_HINTS`
  状态码中文说明与 `TRANSPORT_BLOCK_HINT` 可操作提示（核对 URL、POST 方法、登录 Cookie）。
- 三个识别模式接入统一判定：`detect` 命中时 `is_fastjson=False` / `confidence=0.0` /
  结论改「无法探测：…」；`version` 命中时 `reported_version`、`version_detail`、
  `version_range` 全置 `None`、AutoType 与 SafeMode 置未判定、结论改「版本未能收敛：…」；
  `expect` 命中时 `has_expect_class` 保持 `None`、置信度 `0.0`、结论改「未能完成探测：…」。
  另修正 `_infer_version()`：四个离线探针无定论时置信度由 `0.9` 改为 `0.0`。
- 可读性改造：引擎新增渲染层 `MODE_TITLES` / `STATUS_NOTES` / `_one_line()` /
  `_render_evidence()` / `_render_detect|version|expect|dns|ceye()` / `format_report()`，
  统一输出「标题 + 目标 + 探测结论 + 关键字段 + 探针明细（最多 12 条）+ 提示 + 阶段 + 已知限制」；
  新增 `--format {json,text}`，默认 `json` 保持既有契约，`text` 供人工阅读。
- `src/Main.java` 新增探测页「请求头（JSON）」输入框（位于「业务参数（期望类）」之后，
  始终可编辑）与配置页「默认请求头」`headers` 配置项；`runProbe()` 在非空时下发
  `--headers`（走 `windowsArg()` 转义）并固定追加 `--format text`；删除已无调用点的
  `modeLabel()` 与手工拼接的 `===== 模式名 =====` 分段（改由引擎报告自带标题，避免重复）。
  其余模式勾选、文字不加「启用」、DNS 探针 / CEYE 确认并入探测模式等按既有实现保持。
- 测试更新：`tests/test_probe.py` 新增 `TransportBlockTest`（405 空响应桩服务，
  覆盖识别/版本/期望类不产生假阳性，以及 `_transport_block` 五条判定规则）与
  `FormatReportTest`（`--format` / `--headers` 解析、报告分段与字段、错误渲染、无乱码）；
  `tests/UiSwitchEndToEndCheck.java` 重写为报告断言并新增 405 登录页桩服务与假阳性回归；
  `tests/UiNavigationCheck.java` 新增请求头输入框与配置项断言。
- 文档：新增专项设计文档 `docs/DESIGN-probe-accuracy.md`（背景、根因、判定规则表、
  三模式接入、渲染结构、验证矩阵、限制与改动清单）；同步更新 `docs/DESIGN.md`
  （新增 3.4 传输层失败判定、3.5 结果渲染，补充 IPC `--headers` / `--format`、
  配置项、表单字段、验证矩阵与限制）与 `README.md`（报告格式、传输层语义、
  `--headers` / `--format json` 用例、配置项）。
- 实测验证（授权目标）：未登录 POST `http://211.154.20.67:7779/index/fastjson`
  识别输出「无法探测：所有探针均返回 HTTP 405…」、置信度 `0.0`；版本输出
  「版本未能收敛」、回显版本与布尔探针区间均未收敛、AutoType/SafeMode 未判定 —— 假阳性消除。
  经人工识别验证码以默认凭据登录后携带会话 Cookie 请求真实端点
  `POST /vulnapi/Fastjson/vul`，结果仍准确：识别 `is_fastjson=true`、`confidence=0.806`、
  `fastjson=7.25`；版本 `reported_version=1.2.41`、区间 `1.2.48-1.2.68`、
  `version_range=<=1.2.68`、`confidence=0.9`、AutoType 关闭、SafeMode 已启用；
  期望类 `has_expect_class=false`。DNS 探针与 CEYE 确认按任务要求全程关闭、未投递未外连。
- 构建与校验：`python -m unittest discover -s tests` 通过 `Ran 32 tests ... OK`；
  `javac` 编译通过；`UiNavigationCheck` 与 `UiSwitchEndToEndCheck` 两套界面自检全部通过
  （含 405 假阳性回归）；`build.ps1` 构建成功并完成 JAR 构建时间校验。

## 2026-09-19（新增独立抓包与格式转换功能）

- 需求：针对漏洞靶场（`http://211.154.20.67:7779/index/fastjson`，授权）探测面不够全面的问题，
  新增独立抓包功能（能看原始响应包）与格式转换，重点是拿到登录后的 Cookie 等信息。
- 抓包结论（本轮实测）：`/actuator/mappings` 显示真实 handler 为
  `com.best.hello.controller.ComponentsVul.FastjsonVul#vul(String)`，`predicate = {POST [/vulnapi/Fastjson/vul]}`，
  即靶场 Run 按钮发的是 **POST**，且那个页面地址 `GET /index/fastjson` 只下发页面，
  不是反序列化接口——这正是之前探测报 405 的原因。未登录时 `POST /vulnapi/Fastjson/vul`
  返回 405；登录后同一请求返回
  `com.alibaba.fastjson.JSONException: syntax error, pos 14, json : {age:20,name:Bob`。
- 引擎：新增 `_request_raw()`（任意 HTTP 方法，`_NoRedirect` 拦截重定向，额外返回响应头）、
  `parse_pasted_request()`（解析粘贴的原始报文）、`_absolute_url()`（结合 Host 补全相对路径）。
- 新增 `capture` 模式：只发一次请求，记录状态码、耗时、响应头、响应体（截断 1 MB），
  并分别解析请求侧与响应侧的 Cookie；不跟随 30x 是故意设计，登录成功的
  `302 + Set-Cookie` 正是需要拿到的信息。
- 新增 `convert` 模式与 `convert_report()`：支持 `json` / `curl` / `raw` / `cookie-header` /
  `cookie-json` / `cookie-netscape` 六种输出，完全离线（不访问目标）。
  `cookie-json` / `cookie-header` 可直接填入探测页的请求头字段。
- CLI：新增 `--method` / `--capture-url` / `--body` / `--pasted-request` / `--convert-targets` / `--query`；
  `--mode` 可选值扩展为 `capture` / `convert`；抓包页固定使用 `--format text` 展示中文报告。
- GUI：新增独立导航项与页面「抓包转换」（一级项，与主页 / 配置 / FastJson 平级），
  字段包括请求方法、目标 URL、Content-Type、请求头（JSON）、请求体、转换目标、
  粘贴原始请求；按钮为「抓包」/「解析并转换」/「填入探测页」，结果区支持复制。
  主页新增「抓包与转换」入口卡片。
- 新闻闭环：「填入探测页」会把 URL、Cookie（包装为 `{"Cookie":"..."}`）与请求体
  回填到 Fastjson 探测页并自动跳转，形成「登录 → 抓包拿 Cookie → 带会话探测」的闭环。
- 测试：`tests/test_probe.py` 新增 `CaptureModeTest`（无 Cookie 返 401 / 带 Cookie 返 200，
  记录双向 Cookie 与 `Set-Cookie`，报告渲染）与 `ConvertModeTest`（报文解析、六种格式导出、
  相对路径补全、离线可用、CLI 参数）；`UiNavigationCheck` 新增抓包页控件断言；
  `UiSwitchEndToEndCheck` 新增抓包 / 转换 / 回填探测页的端到端断言。
- 实测验证（授权目标）：未登录 `POST /index/fastjson` 抓包得到 HTTP 405、响应体为空，
  且从响应头里解析出 `Set-Cookie: JSESSIONID=...`；登录后带会话 Cookie 抓包
  `POST /vulnapi/Fastjson/vul` 得到 HTTP 200、23 字节，响应为 fastjson 解析器报错。
  全程只发送识别类请求，未投递利用链；DNS 探针与 CEYE 确认仍焦全程关闭。
- 文档：`docs/DESIGN.md` 新增 3.6 节「抓包与转换」（模式语义、六种格式表、与探测页衔接），
  并补充 IPC 参数、界面约定、验证矩阵与限制；`docs/DESIGN-probe-accuracy.md` 新增 4.5 节；
  `README.md` 补充抓包与转换的用法示例。
- 备份：改动前快照 `.backups/20260919-095107`。

## 2026-09-19（代理抓包 + 探测方法可切换 + 静态页误判修复）

- 需求：用户要求补上 Burp 那样的**代理抓包**能力——浏览器 / 插件把流量指到本地代理就能实时
  看请求与响应，并可配合网页插件改写拦截；同时明确「**https 可以暂不支持抓包**」。
- 根因（先诊断后动手）：用户实测反馈两条——
  1) `GET /index/fastjson` 是**只下发页面的静态地址**，任何请求体都回同一份带 `jsessionid` 的
     页面；旧逻辑把「HTTP 200」当作探针成功，于是 1 条 baseline 加 2 条「区分 Jackson」探针
     凑出 `fastjson=1.25 / confidence=0.139` 的假阳性；真实反序列化接口是
     `POST /vulnapi/Fastjson/vul`（未登录 405），探测面因此「不准确」。
  2) 探针固定 POST，遇到只接受其他方法的接口只能整体被拒，可读性上也只说「无法探测」，
     没有说明「方法可能不对」。
- 代理实现（新增 `src/proxy/ProxyServer.java`，纯 JDK、无第三方依赖）：
  - 明文 HTTP：解析请求行 / 头 / 体，连上游、把请求行改写为 origin-form 后转发，
    完整记录状态码 / 耗时 / 响应头 / 响应体；`1xx` 中间响应先回写再等最终响应；
    `HEAD` / `204` / `304` 无响应体；keep-alive 复用要求响应有明确长度界定。
  - HTTPS：只做 `CONNECT` 盲转发隧道（双向 `pumpBytes()` + `tunneledBytes` 计数），
    **不解密、不记录内容**（`captured=false`）——按用户要求本轮不做 HTTPS 解密。
  - 界面新增一级导航「代理抓包」：监听端口（默认 8899）+ 启动 / 清空 / 转发按钮 + 状态标签，
    流量表格（`#` / 方法 / 状态 / 协议 / 主机 / 路径 / 耗时 / 大小）与详情区；
    「转发到抓包转换」把 URL / 方法 / 请求头（去掉 `Host`）/ 请求体传入抓包页，再经
    「填入探测页」形成「浏览器登录 → 代理抓会话 Cookie → 带会话探测」的闭环。
  - 展示层只做只读可读性转换：去 `chunked`、解 `gzip`/`deflate`、UTF-8 解码、
    二进制只显示字节数；`requestRaw` / `responseRaw` 始终保留原始字节。
- 探测方法：探测页新增 `请求方法` 下拉（POST/GET/PUT/PATCH/DELETE/OPTIONS，默认 POST），
  `--probe-method` 下发，配置页可保存 `probe_method`；`GET`/`HEAD` 把探针 payload
  **URL 编码**后放进查询串（不编码会因 JSON 空格与引号触发 `InvalidURL`）。
  `_probe_with_method_fallback()` 在探针被 HTTP 层整体拒绝时依次换
  `POST → GET → PUT → PATCH` 重试；一旦某方法让任一探针状态码 `< 400`（已到应用层）立即停止，
  避免反复投递；全部失败时写入 `request_method_tried` 并在结论中说明。
- 静态页误判修复：新增 `_uniform_response_block()`，在无任何解析器特征时比较各探针响应体的
  最长公共前缀，≥90% 即判定「未到达 JSON 解析器」，`is_fastjson=False` / `confidence=0.0`；
  比对前用 `_normalize_volatile()` 归一化 `jsessionid` 与长十六进制串——容器会在页面每个
  链接后重写 `jsessionid`，同一张静态页逐字节比对永不相等，不归一化则该判定不成立。
- 测试：新增 `tests/ProxyServerCheck.java`（明文记录、404、CONNECT 隧道双向透传与字节计数、
  回调、记录字段、`find` / `clear`）；`tests/test_probe.py` 新增 `ProbeMethodTest`（默认 POST、
  可切换、非法值回落、GET 负载 URL 编码、405 自动换方法、静态页不判成功、报告回显方法），
  共 45 项全部通过；`UiNavigationCheck` 更新为 5 个一级导航项并新增代理页 / 请求方法断言；
  `UiSwitchEndToEndCheck` 新增「界面启动代理 → 经代理请求 → 记录与详情正确」端到端断言。
- 实测（授权目标 `http://211.154.20.67:7779`，未投递利用链，CEYE 未实测）：
  - 登录后带 `JWT_TOKEN` + `JSESSIONID` 对 `POST /vulnapi/Fastjson/vul`：
    识别 `是`、置信度 `0.806`；版本 **回显 1.2.41**、布尔区间 `1.2.48-1.2.68`、PoC 档位 `<=1.2.68`；
    期望类判定为「不存在（或期望为 Map）」。
  - 未登录 / 静态地址 `GET /index/fastjson`：判定「所有探针均返回 HTTP 405 / 未到达 JSON 解析器」，
    置信度 `0.0`，不再出现 `1.2.70-1.2.80` 之类的假区间。
  - 代理实测：`GET /index/fastjson` 记录 HTTP 200（4483 字节）并显示
    `Set-Cookie: JSESSIONID=...`；`POST` 记录 HTTP 405 空响应，双向记录均正常。
- 文档：`docs/DESIGN.md` 升级到 0.3.0，新增 3.7「请求方法与静态页不是解析器」、
  3.8「代理抓包」（含能力边界、线程模型、keep-alive 与 1xx 处理、展示层只读转换），
  并补充导航、IPC 参数、配置项、验证矩阵与限制；`docs/DESIGN-probe-accuracy.md` 新增
  4.6 / 4.7 两节并顺带修正原有 4.4 / 4.5 编号重复；`README.md` 补充代理用法、
  `--probe-method` 与两条抓包能力说明。
- 构建：`build.ps1`（JDK 17 + Maven 3.9.4）成功，JAR `2026-09-19 10:53:38` / `77400` 字节，
  晚于 `src/Main.java`（10:49:07）与 `python/fj_probe.py`（10:53:07）；核对 JAR 内含
  `META-INF/MANIFEST.MF`、`Main.class`、`proxy/ProxyServer.class`、`python/fj_probe.py`
  （与源文件 MD5 一致）。
- 备份：改动前快照 `.backups/20260919-103722`（本文件、README、两份设计文档、`fj_probe.py`、
  `Main.java`、`ProxyServer.java`、三份测试）。

## 2026-09-19（代理监听地址可配置 + 代理抓包 / 抓包转换归入「代理」大类）

- 需求：
  1. 代理监听地址**默认为本机联网 IP**，且可以修改；
  2. 把「代理抓包」和「抓包转换」**统一放到「代理」大类**中，与 `FastJson` 分类同构，可展开 / 收起。
- 实现（`src/proxy/ProxyServer.java`）：
  - 新增常量 `LOOPBACK = "127.0.0.1"` / `ANY = "0.0.0.0"` 与字段 `host`；
  - 新增 `defaultBindHost()`：先对一个外部地址做 UDP `connect`（只设置路由、**不发送任何字节**）
    取默认出口 IPv4 → 失败则枚举网卡取首个非回环 IPv4 → 仍失败回落 `127.0.0.1`；辅助方法
    `ipv4Of()` 只接受 IPv4 且排除回环 / 通配地址（实测本机返回 `10.10.59.228`，耗时 18 ms）；
  - 新增 `start(bindHost, port)`，空值回落回环、`0.0.0.0` 表示监听所有网卡；原 `start(port)`
    保留为 `start(LOOPBACK, port)` 的等价委托，**既有调用语义不变**；
  - 新增 `host()`（实际绑定地址）与 `displayHost()`（绑定 `0.0.0.0` 时回落本机联网地址，
    使状态栏提示与浏览器代理设置里的地址可以直接照抄）。
- 实现（`src/Main.java`）：
  - 导航由五个一级项改为**四个一级项** `主页` / `配置` / `代理` / `FastJson`，
    `代理` 含二级项 `代理抓包`（key `proxy.mitm`）与 `抓包转换`（key `capture`）；
    路由相应改为 `proxy.mitm`，并新增主页卡片 `proxyCard()`；
  - 代理页新增 `监听地址` 输入框（默认 `proxy.ProxyServer.defaultBindHost()`）与
    「当前联网 IP」按钮（`proxyRestoreLan`），提示语说明默认本机联网 IP / `127.0.0.1` 仅本机 /
    `0.0.0.0` 所有网卡；启动后回填实际绑定地址，状态栏改用 `displayHost()`；
  - **修复真实缺陷**：`rebuildNavigation()` 原先只在「展开的分组 == 传入分组」时才追加子项，
    导致展开一个分组会把另一个已展开分组的子项丢掉（`代理` 与 `FastJson` 同时展开时必现）；
    改为 `if (item.expanded) rebuilt.addAll(item.children);`，展开状态归属于各分组自身。
- 根因（先诊断后动手）：
  1. 监听地址：原 `start(port)` 硬编码 `socket.bind(new InetSocketAddress("127.0.0.1", ...))`，
     局域网内其他设备（含手机）无法接入，用户要求默认用本机联网地址并允许修改；
  2. 导航：`代理抓包` / `抓包转换` 原是两个并列一级项，与 `FastJson`（一级 + 二级）不一致，
     用户要求统一归入 `代理` 大类；
  3. 界面自检截图出现「重复分组」重影：`tests/UiNavigationCheck.snapshot()` 在 **EDT 之外**
     调用 `frame.paint()`，与 EDT 上由 `updateScale()` 触发的 `setFixedCellHeight` /
     重绘交错，把上一次导航状态与新状态叠在同一张图上。**模型本身正确**（诊断程序打印
     `navigationItems` 为 7 项且无重复，`JList` 模型大小一致），属于测试工具缺陷而非产品缺陷。
- 测试：
  - `tests/UiNavigationCheck.java`：更新为四个一级项、`代理` 分类展开/收起（含两个二级项）、
    代理页 `监听地址` 与「当前联网 IP」控件断言；`snapshot()` 改为在 EDT 内取帧消除重影；
    `check()` 失败改为 `System.exit(1)`，避免 AWT 非守护线程让进程挂死。
  - `tests/UiSwitchEndToEndCheck.java`：新增「默认监听地址为本机联网 IP」「指定 `127.0.0.1`
    启动后回填」两条断言。
  - 编译测试类的根因：缺少类路径 `target\classes`，`proxy.ProxyServer` 为直接引用，
    因此报「找不到符号 proxy」（已用 `-cp target\classes` 解决，源码无需改动）。
  - 结果：`UiNavigationCheck`、`UiSwitchEndToEndCheck`、`ProxyServerCheck` 全部通过；
    `python -X utf8 -m unittest discover -s tests` **45 项全部通过**。
- 文档：`docs/DESIGN.md` 升级 0.3.1（架构图重画、3.1 区分探测页勾选与页面级 mode、
  3.6 归属说明、3.8 监听地址与 `defaultBindHost()` / `displayHost()`、界面约定四一级项、
  验证矩阵与已知限制）；`docs/DESIGN-probe-accuracy.md` 4.7 补充监听地址、验证表补充三条、
  限制补充暴露面说明、改动清单补充本轮文件；`README.md` 导航段与 Proxy 段同步。
- 构建：`build.ps1`（JDK 17 + Maven 3.9.4）成功，JAR `2026-09-19 11:38:57` / `79054` 字节，
  晚于 `src/Main.java`（11:20:16）与 `src/proxy/ProxyServer.java`（11:13:40）；核对 JAR 内含
  `META-INF/MANIFEST.MF`（`Main-Class: Main`）、`Main.class`、`proxy/ProxyServer.class`、
  `python/fj_probe.py`（与源文件 MD5 一致：`7978f8aadbf34d26fcbfa71fb3898b11`）。
  代理自检补充「指定联网地址启动」「通配地址绑定 + `displayHost()` 回落」「空白地址回落回环」
  三条断言后仍全部通过（实测默认监听地址 `10.10.59.228`）。
- 备份：改动前快照 `.backups/20260919-111211`。

## 2026-09-19（pom 移入 src + Shiro 模块 + java-chains 集成 + 代理拦截改包）

- 需求：
  1. 把 `pom.xml` 从仓库根移进 **Java 内容工作区 `src/`**；
  2. 把 `ShiroExploit-1.0.2.jar` 的功能集成为本工具的 **Shiro 漏洞利用**功能（允许优化）；
  3. **放开原有「不做利用」的产品边界**（用户明确同意）；
  4. 把 **java-chains** 集成进依赖（`v2.0.0-beta4`）。

### 一、pom 迁移与 java-chains 依赖

- `src/pom.xml`：`<sourceDirectory>${project.basedir}</sourceDirectory>`（即 `src/`）、
  `<directory>${project.basedir}/../target</directory>`、`finalName=JavaSecExpToolKit`；
  资源映射 `../python/fj_probe.py → python/` 与 `shiro/res/shiro-keys.txt → shiro/res/`。
- java-chains 未发布中央仓库，声明本地文件仓库 `file:///${project.basedir}/../libs-repo`，
  仓库内保存 `java-chains-cli-2.0.0-beta4.jar` 与手写 POM；`maven-dependency-plugin`
  在 `prepare-package` 复制运行时依赖到根目录 `lib/`，`maven-jar-plugin` 写入
  `Class-Path: lib/…`，因此 `java -jar` 也能加载。
- `build.ps1` 改为 `mvn -f src\pom.xml clean package`，并把时间校验源文件扩展为
  `Main.java`、`ProxyServer.java`、`ShiroEngine.java`、`fj_probe.py`。
- 实证：`ChainsEngine.init()` 后 `ready=true`、**可用节点 429 个**、**payload 载体 29 个**；
  `shiropayload` + `commonscollectionsk1→templatesimpl→bytecodeconvert→tomcatecho`
  构建成功（8896 字符 Base64）；非法链（`jdbcrowsetimpl`）返回
  `GadgetException: … not contain '[JavaNativeDeserialize, CustomJavaDeserialize]' tag`
  而不是崩溃。`shiropayload` 的三个参数为 `ShiroPayload.shiroKey` / `gcmMode` / `dirtyLength`。

### 二、Shiro 模块（`src/shiro/`）

- `ShiroEngine.java`：纯 JDK 的 rememberMe 加解密与投递。CBC 用 `AES/CBC/PKCS5Padding`，
  **IV 取密钥前 16 字节**（Shiro 既有约定）；GCM 用 `AES/GCM/NoPadding`，随机 16 字节 IV
  前置，受限 JDK 上回落 PKCS5Padding 而不是抛异常。指纹用「基线 vs 随机 rememberMe 的
  `deleteMe` 计数差」，避免会话初始化本身下发 `deleteMe` 造成假阳性。爆破用
  `SimplePrincipalCollection` 空主体加密（合法载荷，避免目标侧反序列化报 500 造成假阴性），
  并发、命中即停、可中断。
- `ChainsEngine.java`：java-chains 封装。节点 id 全部小写，参数名兼容 `Exec.cmd` / `cmd`；
  无链构建返回结构化失败原因（`gadget tags is empty`）而不是抛异常；初始化失败写进
  `statusMessage()` 供界面展示。
- `ShiroExploit.java`：`CB19` / `CCK1` / `CCK2` 三条回显链（仅首 gadget 不同，末端统一
  `templatesimpl → bytecodeconvert → tomcatecho`），并实现**注入一次、多次复用**：
  先投递回显链注入回显马，之后每条命令都是普通 HTTP 请求（命令 Base64 放进回显请求头），
  因此连续执行多条命令只打一次链。
- 字典：`src/shiro/res/shiro-keys.txt`（1108 条去重）。
- 界面：导航新增 `Shiro` 一级项 → `Shiro 漏洞利用` 二级项；页面含目标 URL / 请求方法 /
  Cookie 名 / 密钥 / `AES-GCM` / 回显请求头 / 利用链 / 命令 / 附加请求头，
  按钮为一键检测 / 密钥爆破 / 停止 / 生成 Payload / 执行命令，配进度条与分步输出。
  「生成 Payload」只生成不投递，便于手工拿到 Burp 里用。

### 三、代理抓包改造（Burp 式拦截）

- `ProxyServer` 新增 `Interceptor`（转发前同步回调，未注册时转发路径零开销）与
  `Rewrite`（`head`/`body` 为 `null` 表示沿用原值；`Rewrite.DROP` 表示丢弃、不连上游）。
  放行时若改过请求头会用新头重新解析 Host / 方法 / 路径并同步回 `HttpFlow`。
- 界面：删除流量列表与「当前联网 IP」按钮，改为上下分栏的 `请求包`（可编辑）与
  `返回包（放行后捕获）`（只读），加 `拦截请求` 勾选框与 `放行` / `丢弃` 按钮。
- 根因修复（先诊断后动手）：
  1. **「修改端口后点击启动代理没反应」**——实测确认并非「没反应」：`toggleProxy`
     在端口被占用时确实把状态设为 `启动失败：Address already in use: bind`，但那一行
     `FlowLayout` 的首选宽度为 999px 而实际宽度 890px，状态标签被挤出可视区，
     使用者看不到任何反馈。此外端口框在成功启动后会被回填成实际端口，下次启动会沿用
     **上次的随机端口**而不是输入框里期望的值。修复：把拦截控件单独放一行（首行不再
     溢出）、端口以输入框为准、记住 `lastProxyPort`，并把端口错误分支补上可读提示。
  2. **「抓包转换」缩进错位**——`NavItem.isChild()` 原实现为 `key.indexOf('.') >= 0`，
     而 `抓包转换` 的 key 是 `capture`（无点号），因此它被当成一级项、缩进少 20px，
     与同一分组下的 `代理抓包` 不对齐。改为 `parent != null`（按层级判断）。
  3. 重写代理页时一度漏掉 `addListener`，导致返回包永不刷新，端到端自检立刻失败——
     已补回注册。
- 未勾选拦截时 `请求包` 也会回填刚发出的报文，便于直接排错；`转发到抓包转换` 改为
  转换**当前显示的请求包**。

### 四、测试

- 新增 `tests/ShiroCheck.java`：CBC/GCM 往返、指纹、爆破命中、非法链被拒、
  CCK1 回显链生成且**可由本机密钥解密**、CB1/CCK1/CCK2、回显提取。
- 新增 `tests/UiShiroCheck.java`：Shiro 页控件齐备、桩服务上「一键检测」确认存在 Shiro、
  界面可生成回显链并输出 Base64。
- `tests/UiNavigationCheck.java`：断言更新为五个一级项，并把代理页断言改为
  拦截控件 + 请求包 / 返回包，新增「不再有流量列表」「不再有获取联网 IP 按钮」
  「未启动时放行与丢弃不可用」三条。
- `tests/ProxyServerCheck.java`：新增**拦截改包放行后目标收到改写后的头与体**、
  **丢弃时不连上游且仍留记录**两条用例。
- `tests/UiSwitchEndToEndCheck.java`：代理段重写为「启动 → 发请求 → 请求包 / 返回包正确」
  「停止后放行不可用 → 重启后可用」「勾选拦截后请求停住 → 改包放行 → 目标收到改后的体」。
- 结果：`ProxyServerCheck`、`UiNavigationCheck`、`UiSwitchEndToEndCheck`、`ShiroCheck`、
  `UiShiroCheck` 全部通过；`python -X utf8 -m unittest discover -s tests` **45 项全部通过**。
- 编译测试类的踩坑记录：`tests/UiShiroCheck.java` 的 javadoc 里写了
  `-cp "lib\java-chains-cli-2.0.0-beta4.jar;…"`，其中 `\u` 被 javac 当成 **Unicode 转义**
  前缀（`\ui` 不是合法十六进制），报「非法 Unicode 转义」；把注释里的反斜杠改成
  正斜杠即可，与源码逻辑无关。

### 五、文档

- 新增 `docs/DESIGN-shiro.md`：Shiro 模块专项设计（边界、分层、指纹计数差原理、
  CBC/GCM 细节、爆破判据与假阴性规避、java-chains 模型与封装适配、三条回显链、
  注入一次多次复用的回显机制、界面字段、验证方式、依赖与构建、已知限制）。
- `docs/DESIGN.md` 升级 **0.4.0**：架构图加入 Shiro 引擎与代理拦截，一、目标与范围
  改写为三块能力并明确 Shiro 属利用功能；3.8 补充请求拦截（拦截点、同步阻塞、
  `Rewrite` / `DROP` 语义、超时自动放行、Content-Length 重算）；新增 3.9 Shiro 漏洞利用；
  六、界面约定更新为五个一级项、新的代理页与 Shiro 页；七、构建与验证改为
  `mvn -f src\pom.xml`、说明 `src/pom.xml` 与 `libs-repo` 本地仓库；验证矩阵与
  已知限制同步（HTTPS 无法拦截、记录不保留历史、拦截阻塞与超时、Shiro 的授权与依赖风险）。
- `README.md`：定位改为 toolkit 并区分探测 / 代理 / Shiro 三块；导航、Project layout
  （`src/` 含 `pom.xml`、根目录含 `lib/` 与 `libs-repo/`）、Requirements、构建说明、
  代理用法（拦截与两个文本域）、新增 Shiro module 段并链接 `docs/DESIGN-shiro.md`。

### 六、构建与备份

- 构建：`mvn -f src\pom.xml clean package` 成功，JAR 输出到仓库根，
  清单为 `Main-Class: Main` + `Class-Path: lib/java-chains-cli-2.0.0-beta4.jar`，
  JAR 内含 `Main.class`、`proxy/ProxyServer$Interceptor.class`、`shiro/*.class`、
  `python/fj_probe.py`、`shiro/res/shiro-keys.txt`。
- 运行期缓存定位：java-chains 的 `ChainsConfigPaths` 先在**依赖 JAR 所在目录**找
  `chains-config/`，找不到才回落 `user.dir`，因此原本会在仓库根生成
  `chains-config/cache/metadata/node-index.yaml`。已把它移到 `lib/chains-config/`，
  实测重新初始化后 `ready=true`（429 节点）且仓库根不再出现该目录。
- 备份：改动前快照 `.backups/20260919-143700`（`Main.java`、`ProxyServer.java`、
  三份测试）；更早的 `.backups/20260919-140922` 保留了迁移前的根目录 `pom.xml`
  （`pom.xml.root-moved`）。

## 2026-09-19（修复：点击「Shiro 漏洞利用」跳回主页）

- 现象：侧边栏展开 `Shiro` 分组后点击二级项 `Shiro 漏洞利用`，内容区打开的是主页。
- 根因（先诊断后动手）：`openSelected()` 是「按 key 分派页面」的 if/else 链，
  分支只覆盖 `config` / `proxy.mitm` / `capture` / `fastjson.detect`，**缺少
  `shiro.exploit`**，于是落到最后的 `else showHome()`。导航项本身、展开逻辑与
  `showShiro()` 页面都正常，只是没有被调用——上一轮加 Shiro 页时漏了这一行分派。
- 修复：在 `openSelected()` 补 `else if ("shiro.exploit".equals(item.key)) showShiro();`。
- 防回归：`tests/UiNavigationCheck.java` 新增递归收集内容区标签文字的
  `contentLabels()`，并断言「跳转 Shiro 二级项打开 Shiro 页」「跳转后选中项为
  `Shiro 漏洞利用`」「不回落到主页」，同时给配置页加一条同样的「不回落到主页」断言；
  该断言此前不存在，正是这次漏接分支没被自检发现的原因。
  另把导航项数断言从 8 修正为 9（代理 / FastJson / Shiro 三个分组都展开：
  5 个一级项 + 2 + 1 + 1）。
- 验证：`ProxyServerCheck`、`UiNavigationCheck`、`UiSwitchEndToEndCheck`、`ShiroCheck`、
  `UiShiroCheck` 全部通过；Python `unittest` 45 项通过。
- 备份：改动前快照 `.backups/20260919-150628`（`Main.java`、`UiNavigationCheck.java`）。

### 附带修复：Shiro 页输出区被压成一条细线

- 现象（修好跳转后从截图发现）：Shiro 页「利用输出」文本域只剩十几像素高，基本无法阅读。
- 根因（实测数据）：`shiroForm()` 的首选高度是 **478px**，而 `showShiro()` 把它放在
  `BorderLayout.NORTH`（该位置会独占首选高度），`CENTER` 的输出区只剩
  **JViewport 871x16**——不是渲染故障，是布局约束把可用高度吃光了。
- 修复：改为上下分栏——表单放进 `JScrollPane`（首选高 330px）作为上栏、输出面板作为下栏，
  `setDividerLocation(0.62)` 在首次布局后按比例定位；并把「一键检测 / 密钥爆破 / 停止 /
  生成 Payload / 执行命令」与状态行抽成 `shiroActionsPanel()` **固定在滚动区之外**，
  否则在 800px 高的窗口里按钮会被一起滚出可视区（第一版就踩到了这个坑）。
- 实测结果：输出区视口 **888x183**（修复前 871x16），「一键检测」按钮 `isShowing()=true`，
  生成的回显链 Base64 可以完整读到。
- 防回归：`tests/UiNavigationCheck.java` 新增两条断言——「Shiro 页操作按钮可见」
  （`shiroDetect.isShowing()`）与「Shiro 页输出区有可用高度」（视口高度 > 100），
  这两条正是此前缺失、导致布局问题没被自检发现的检查点。
- 构建：JAR `2026-09-19 15:11:45` / `133961` 字节，晚于 `src/Main.java`。

## 2026-09-19（修复：代理拦截失效 / 请求包面板刷屏 / Shiro 独立回显框）

本轮三个问题都按「先定位根因再动手」处理，没有改动无关功能。

### 一、能抓到请求包但无法拦截

- **现象**：代理已启动、请求包能正常显示，勾选「拦截请求」后请求照样直达目标，
  `放行` / `丢弃` 按钮点了没反应（实际是全程禁用）。
- **根因**：拦截器只在 `toggleProxy()` 启动那一刻读一次 `proxyIntercept.isSelected()`
  （`if (proxyIntercept.isSelected()) server.setInterceptor(...)`）。而**先启动代理、
  随后才勾选「拦截请求」**才是最常见的使用顺序，这种情况下拦截器从未被装载，
  勾选动作只改了复选框状态、对运行中的代理没有任何影响。
- **修复**（`src/Main.java`）：
  - 把拦截器的装卸集中到 `updateProxyInterceptState()`：代理运行中直接
    `proxyServer.setInterceptor(intercepting ? this::interceptRequest : null)`，
    勾选/取消的瞬间即生效，无需重启代理；
  - 新增 `interceptActive` 标志供连接线程判断开关是否被关掉（否则已挂起的请求会一直
    等到 120 秒超时）；关闭拦截时调用新增的 `releasePendingUnchanged()`，
    把正卡在等待里的请求**不改包直接放行**，避免一取消勾选就把浏览器挂死。
- **顺带修复（同一链路的真实缺陷）**：请求包文本域此前恒为只读，拦截后根本改不了包，
  「改包后放行」实际不可用。现在由 `updateProxyInterceptState()` 一并设置
  `proxyRequestText.setEditable(intercepting)`——只在拦截开启时可编辑；未拦截时是纯观察
  视图，允许编辑也只会被下一条流量覆盖。同时把该调用从 `proxyControlPanel()` 移到
  `showProxy()` 中面板构建完成之后，否则会被 `styleProxyTextArea()` 的只读设置覆盖。

### 二、未放行时请求包仍在刷新

- **现象**：拦截按住一个请求后，上方请求包面板还在不断变化，正在编辑的内容被冲掉。
- **根因**：`appendProxyFlow()` 对每一条流量都无条件 `proxyRequestText.setText(...)`。
  浏览器在后台会持续产生心跳、预连接等无关请求，它们在界面线程上不停覆盖面板。
- **修复**：拦截开启时，`appendProxyFlow()` **只认「正在等待放行」和「刚刚放行」
  （`flow.id == awaitingResponseFlowId`）」那一条流量**，其余直接丢弃不渲染。
  最终实现更彻底：**拦截开启时请求包面板由 `interceptRequest()` 独家负责**
  （命中即写入待放行报文），`appendProxyFlow()` 一刀不写请求包；返回包面板只认
  `awaitingResponseFlowId`，写完一次即清空归属。未开启拦截时才回落到「展示最近一条」。
- **并发正确性**：`interceptRequest()` 新增**排队**逻辑，同一时刻只放一条请求停在界面上，
  其余连接 `wait` 等轮到自己。否则多条连接会互相覆盖待放行报文，放行时发出去的
  可能不是使用者看到的那一份。

### 三、Shiro 每个功能一个独立回显框

- 原来四个功能共用一个 `shiroOutput` 文本域，密钥爆破的进度、命令回显会把检测结论和
  生成的 payload 冲掉，回头排查时找不到原始结论。
- 改为 4 个独立回显框 + 页签组：`指纹检测` / `密钥爆破` / `生成 Payload` / `执行命令`
  分别对应 `shiroDetectOutput` / `shiroCrackOutput` / `shiroBuildOutput` / `shiroRunOutput`，
  由 `shiroOutputTabs`（`JTabbedPane`）承载；`shiroAppend(JTextArea target, String text)`
  只往指定目标追加，`shiroShowTab(title)` 在点各功能入口时自动切到自己的页签。

### 四、验证

| 载体 | 新增/变更的检查点 | 结果 |
| --- | --- | --- |
| `tests/ProxyServerCheck.java` | 新增第 6 组：运行中装卸拦截器（未装载直达上游 → 装载立即生效 → 卸载恢复） | 通过 |
| `tests/UiSwitchEndToEndCheck.java` | 新增「先启动代理再勾选拦截」（`clickCheckBox` 走 `doClick()`）、「未拦截时请求包只读」、「拦截后请求包可编辑」、「等待放行期间不被无关请求刷屏」 | 通过 |
| `tests/UiSwitchEndToEndCheck.java`（续） | 追加「排队中的请求随后自动接位停在面板上」与「排队请求接位后返回包仍是上一次的结果」——第一版实现在「放行回填」与「接位写入」之间存在竞争，会把接位请求的请求包覆盖掉，这两条断言把它暴露了出来 | 通过 |
| `tests/UiShiroCheck.java` | 断言改为 4 个回显框 + `getTabCount()==4`、「生成 Payload 不写进指纹检测框」、「指纹结论仍保留」 | 通过 |
| `tests/UiNavigationCheck.java` | `shiroOutput` → `shiroDetectOutput`，新增「Shiro 四个功能各有独立回显框」 | 通过 |
| `tests/test_probe.py` | 未改动 | 45 项通过 |
| `target/ui-check/06-proxy.png`、`07-shiro.png` | 离屏截图确认代理页布局与 Shiro 4 个页签渲染正常 | 正常 |

- **测试写法上的教训**：`AbstractButton.setSelected()` **不会触发 `ActionListener`**，
  用它模拟点击会让依赖监听器的联动逻辑整条链路都不执行，从而把产品代码误判成有 bug。
  测试里统一改用 `doClick()`（新增辅助方法 `clickCheckBox`），与真实点击一致。
  本轮最初卡住的 `UiSwitchEndToEndCheck:189` 就是这个原因，产品代码本身没有问题。
- **另一处测试写法问题**：「放行后返回包展示的是本次请求的响应」原先只断言 `HTTP 200`，
  而上一次的响应同样含 `HTTP 200`，面板还没来得及更新时断言也会通过（假阳性），时序一变
  就变成假失败。改为让桩服务在请求体含 `"age":21` 时回显 `{"ok":true,"age":21}`，
  再用 `awaitProxyResponse(main, "\"age\":21", 10000)` 轮询断言。连跑三次均通过。

### 五、文档与备份

- 文档同步：`docs/DESIGN.md`（3.8 增加「拦截开关的运行时装卸」「同一时刻只放行一条」
  「请求包面板的刷新策略」，界面约定改为请求包仅在拦截时可编辑、Shiro 输出为四页签，
  验证表与已知限制同步）、`docs/DESIGN-shiro.md`（新增「输出的隔离（一个功能一个回显框）」
  与控件表）、`README.md`（代理拦截的运行时生效、排队、只读规则与 Shiro 独立回显）。
- 备份：改动前快照 `.backups/20260919-153540`（`Main.java`、`UiSwitchEndToEndCheck.java`），
  保留最近三次。
## 2026-09-19（新增：抓包后一键发送到其它功能探测）

### 一、需求与设计

在「抓包与转换」页增加 `一键发送`：抓完包（或粘贴报文转换完）后，把这次请求**直接**交给
另一个功能去探测，不再需要人工把 URL / Cookie / 请求体复制粘贴过去。

- 目标选择用一个下拉框（`captureSendTo`）：`Fastjson 探测` / `Shiro 漏洞利用`。
- 与既有的 `填入探测页` 并存而非替换：`填入探测页` 只回填、不自动开跑（留出调整探测模式
  的时间），`一键发送` 是「回填 + 跳转 + 立即执行」。
- 分派入口 `sendCaptureTo()`，每个目标一个私有方法（`sendCaptureToFastjson()` /
  `sendCaptureToShiro()`），新增目标时只需加一个分支。

### 二、实现要点

- **Fastjson 探测**（`sendCaptureToFastjson()`）：回填 URL / 请求方法 / `请求头（JSON）` /
  `业务参数` 后调用 `startDetection()` 立即探测。请求头优先用抓包页已填的 JSON；
  只有从旧报告里取到 Cookie 时才用 `headerJsonOf()` 包一层 `{"Cookie":"..."}`。
- **Shiro 漏洞利用**（`sendCaptureToShiro()`）：回填 URL / 请求方法 / `附加请求头` 后调用
  `startShiroDetect()`。注意两个页面的请求头格式不同——探测页是 JSON，Shiro 页是
  **每行一个 `Key: Value`**，由 `shiroHeaderLines()` 完成转换，并跳过 `Host` /
  `Content-Length` / `Proxy-Connection`（这些不该由调用方指定）。
- **不引入 JSON 库**（AGENTS.md 禁止新增依赖）：界面内新增 `flatJsonObject()` 只处理
  「对象里每个值都是字符串」这一种形态，支持 `\" \\ \n \r \t` 转义，
  足够覆盖工具自己生成与使用者直接粘贴的请求头。

### 三、顺带修复的两个真实缺陷

1. **`填入探测页` 按钮从未被加进按钮行**：`captureToProbe` 在 `captureForm()` 里只做了
   `styleSecondaryButton()`，没有 `buttons.add(...)`，因此这个按钮在界面上一直**不可见**
   （测试只断言了字段存在，没断言可见性，所以没被发现）。本轮把它和新的发送控件一起
   加进了按钮行，并在截图中确认可见。
2. **需要登录态的 Shiro 目标探测不到**（会让「一键发送到 Shiro」在真实网站上失效）：
   - `ShiroEngine.parseHeaders()` 里同名的头会互相覆盖，且大小写敏感；从抓包页带过来的
     小写 `cookie` 与使用者手工填的 `Cookie` 只能活下来一个。改为**同名合并、大小写不敏感**。
   - `ShiroEngine.send()` 在有 rememberMe 时用 `setRequestProperty("Cookie", ...)` 覆盖，
     而 `HttpURLConnection` 对同名头本就是覆盖语义，结果**会话 Cookie 被抹掉**，
     需要认证的接口一律被重定向到登录页。改为把会话 Cookie 与 rememberMe 拼成
     **同一个 Cookie 头**发出。
   - 基线请求同样要带会话 Cookie：否则「无 Cookie 基线」会退化成未登录访问，
     deleteMe 计数差失去意义。

### 四、验证

| 载体 | 新增检查点 | 结果 |
| --- | --- | --- |
| `tests/UiNavigationCheck.java` | 抓包页含 `一键发送` 按钮与目标下拉框，且目标含 `Fastjson 探测` / `Shiro 漏洞利用` | 通过 |
| `tests/UiSwitchEndToEndCheck.java` | 发送到 Fastjson：URL / 方法 / 请求头 / 请求体全带过且提示已触发；发送到 Shiro：URL 与会话 Cookie 带过、`Host` 头不被带过去 | 通过 |
| `tests/ShiroCheck.java` | 附加请求头解析（同名合并、忽略大小写、空行与注释）、**基线请求也带会话 Cookie**、**会话 Cookie 与 rememberMe 合并成同一个头**（用桩服务记录每次请求的 Cookie 断言） | 通过 |
| `tests/ProxyServerCheck.java` / `UiShiroCheck.java` | 未改动 | 通过 |
| `tests/test_probe.py` | 未改动 | 45 项通过 |
| `target/ui-check/05-capture.png` | 离屏截图确认 `填入探测页` / `Fastjson 探测` 下拉 / `一键发送` 三个控件渲染正常且可见 | 正常 |

### 五、文档与备份

- 文档同步：`docs/DESIGN.md`（3.6 增加「与其它功能的衔接（一键发送）」含两个出口的对比表、
  分派方法、请求头格式转换与 Cookie 合并的约束；界面约定与验证表同步）。
- 备份：改动前快照 `.backups/20260919-160525`（`Main.java`、`UiNavigationCheck.java`、
  `UiSwitchEndToEndCheck.java`）。
## 2026-09-19（修复：代理页切页丢内容 / 一键发送自动探测 / Fastjson 探测过慢 / 回显过长）

本轮四个问题，全部先定位根因再动手，逐条给出实测证据。

### 一、代理抓包页切走再回来内容没了

- **根因**：`showProxy()` 每次进入都会重建整个页面，而 `styleProxyTextArea()` 无条件执行
  `area.setText(placeholder)`。于是「去别的功能看一眼再回来」，刚捕获的请求包与返回包
  会被占位提示覆盖回去。用探针程序实测确认：给 `proxyRequestText` 写入内容后
  `selectNav("home")` → `selectNav("proxy.mitm")`，内容变回占位提示。
- **修复**：两个占位提示抽成 `PROXY_REQUEST_PLACEHOLDER` / `PROXY_RESPONSE_PLACEHOLDER`
  常量，`styleProxyTextArea()` 改为只在 `area.getText().isEmpty()` 时写入；
  `clearProxyFlows()`（「清空记录」按钮）仍显式写回占位提示，是唯一的清空入口。
- **边界说明**：`proxyStatus` 文案与按钮初始状态仍随面板重建回落，但
  `updateProxyInterceptState()` 会按 `proxyServer.isRunning()` 重新同步可用性，
  代理运行状态与已捕获报文都不受影响。

### 二、一键发送不应自动探测

- **根因**：`sendCaptureToFastjson()` / `sendCaptureToShiro()` 在回填参数后直接调用
  `startDetection()` / `startShiroDetect()`，抓完包一按就把流量打到目标上。
- **修复**：删掉两个自动开跑调用，只保留回填与跳转；状态栏提示改为
  「已发送到 Fastjson 探测，确认参数后点『开始探测』」/「…确认参数后点『一键检测』」。
  **自动探测入口只剩页面上的「开始探测」/「一键检测」按钮。**

### 三、Fastjson 探测时间过长

- **根因**：每条探针串行发送，总耗时 ≈ 探针数 × 单次请求耗时。正常目标单次往返只有
  几十毫秒看不出问题，但目标不可达时每条都要耗满 `--timeout`。实测（timeout 3 s）：
  黑洞地址识别模式 8 条探针 **20581 ms**、版本模式 11 条 **26648 ms**。
- **修复**：`python/fj_probe.py` 新增 `PROBE_CONCURRENCY = 4` 与
  `_send_probes(probes, target, timeout, headers, content_type, method)`，用
  `ThreadPoolExecutor` 并发发送，四处调用点（`_fingerprint()` / `_version()` /
  `_expect()` / `_dns()`）统一走它。三条硬约束：按原下标回填（`evidence` 顺序、
  `zip(probes, sent)` 解包关系都不变）、并发度取 `max(1, min(4, len(probes)))`、
  单条异常收敛为「连接失败」。
- **并发后的实测**：黑洞地址识别 20581 → **169 ms**、版本 26648 → **8340 ms**；
  真实目标识别 456 → **175 ms**。
- **踩到一个必须记住的坑**：探针表既有 3 元组 `(id, description, payload)` 也有
  4 元组 `(id, category, description, payload)`，payload 一律在末位。最初写成固定下标
  `[3]` 触发 `IndexError`，而异常被收敛成「连接失败」，看起来只是「变快了」，
  实际是探测整体失效的假象。已改为 `probe[-1]`，并补测试 `test_send_probes_handles_variable_arity`。

### 四、回显内容过多

- **根因**：`_render_evidence()` 对每条探针固定打印一行`响应: （空响应）`。绝大多数探针的
  响应体本来就是空的（405 / 403 / 统一错误页），结果区大半篇幅都在重复同一句话，
  真正的结论反而被淹没；同时 `notes` 与 `summary` 在跳过 DNS / CEYE 阶段时会写入
  同一段文字，`探测结论:` 那一行也会因为把原因与建议全塞进去而超长。
- **修复**（三处，全部只改渲染层，不动 JSON 字段）：
  1. 空响应不再逐条打印，只有正文或错误确实非空时才附缩进行；省略提示改为
     「其余 N 条探针已省略（均为无特征响应）」；列宽 24 → 16；
  2. `_wrap_conclusion()` 把 `探测结论:` 拆成「结论」+ 缩进 10 空格的「原因/建议」两行
     （按 `。` 再按 `；` 在 8~120 字区间切分）；
  3. `_dedupe_notes()` 丢掉与 `summary` 重复的提示（按前 12 字比对）；
  4. 识别模式的 `limitations` 三条合并为一条（原本三条说的是同一件事）。
- **实测**：识别模式报告 **38 行 → 28 行**，结构清晰。

### 五、并发化顺带挖出的两个既有判定缺陷（已修复）

并发只是让请求更密集，却把两个早已存在、只在高频下才偶发的「假信号」缺陷放大成必现。
两个都属于**结论可信度**问题，因此和并发一起修；诊断方式是在同一桩服务上做 A/B 对照。

**缺陷一：没拿到响应被当成「未报错」→ 凭空推出假版本区间**

- 现象：全量测试 8 轮里偶发 1~2 轮失败，`version_detail` 变成 `1.2.70-1.2.80`（置信度 0.8），
  `expect` 模式 `confidence` 变成 0.45。抓到失败样本后发现：`baseline_ok` 探针
  `status=None`、`error=ConnectionAbortedError`，其余探针是 501。
- 根因：`_version_response_errored()` / `_expect_errored()` 对 `status is None`（连接中断 /
  超时）直接返回 `False`。布尔差分的结论建立在「基线正常、离线探针报错」之上，
  把一个没有响应的探针记成「未报错」，区间推断就凭空成立了。
- 修复：两个函数改为**三态**（`True` 报错 / `False` 未报错 / `None` 未知）；
  `_infer_version()` 只要四条离线探针有一条是 `None` 就整体不收敛；
  `_infer_autotype()` 同理；`_expect()` 新增 `missing_probes` 分支，
  结论降级为「未能完成探测：以下探针未拿到响应（连接中断或超时）：……」。

**缺陷二：`5xx` 与连接失败被当成「解析器报错」/「响应有差异」**

- `501/502/503` 是网关、方法不支持等**整体拒绝**，与本次 payload 无关。旧实现
  `status >= 400` 一律算解析器报错，一个 501 就能让四条离线探针全变 `True`，
  直接落进 `1.2.70-1.2.80` 档位。现在 `>= 500` 按「未知」处理，
  但**响应体里出现解析器特征时仍算命中**（统一错误页可能真的包了 fastjson 异常）。
- `_probe_result_varied()` 原先用 `{item.get("status") for ...}` 判断响应是否有差异，
  连接失败留下的 `None` 会让集合凭空多出一个元素，于是「混了连接失败的同一份 501 错误页」
  被判成「响应随 payload 变化」，`_probe_with_method_fallback()` 据此误报换方法成功。
  现在只统计真的拿到响应的探针。

**验证**

| 对照 | 修复前 | 修复后 |
| --- | --- | --- |
| A/B 对照（同桩服务、15 轮 version + expect） | 2/15 轮出现假区间 / 假置信度 | 0/15 |
| 并发压力（40 轮 version） | 偶发假区间 | 无失败 |
| 全量测试连续 12 轮 | 8 轮里偶发 1~2 轮失败 | **0/12 失败** |
| 正向探测（桩服务 10 轮，detect / version / expect） | — | 结果稳定一致（`is_fastjson=True`、`1.2.48-1.2.68`、期望类 `True/True`） |
| 真实靶场 `211.154.20.67:7779/index/fastjson` | — | 判定「无法探测」而非假阳性，耗时 0.8 s |
| 慢目标耗时（黑洞地址 timeout 3 s） | 19371 ms / 27568 ms | **6116 ms / 7158 ms** |

测试侧新增 `tests/test_probe.py::UnknownResponseTest`（7 条）：三态语义、5xx 按未知处理、
5xx 带解析器特征仍命中、未知探针抑制区间、未知探针不推出 AutoType、连接失败不影响差异判定。

### 六、验证

| 载体 | 新增/更新检查点 | 结果 |
| --- | --- | --- |
| `tests/test_probe.py` | 新增 `ProbeConcurrencyTest`（并发度上界、`_send_probes` 保序、3/4 元组混合、空列表、异常转失败）、`ReportReadabilityTest`（空响应不逐条打印、失败摘要仍展示、结论拆两段、notes 重复不打印、标签不同但正文相同的 notes 也要去重、limitations 计数）与 `UnknownResponseTest`（三态语义等 7 条） | **64 项通过**、连续 12 轮无失败 |
| `tests/UiSwitchEndToEndCheck.java` | 新增「切页返回后请求包 / 返回包内容仍在、未回落到占位提示」「一键发送提示等待确认而非已开跑 / 不自动执行探测 / 探测按钮仍可点击」「发送到 Shiro 不自动执行」 | 通过 |
| `tests/ProxyServerCheck.java` | 未改动 | 通过 |
| `tests/ShiroCheck.java` | 未改动（429 个 java-chains 节点就绪） | 通过 |
| `tests/UiNavigationCheck.java` / `tests/UiShiroCheck.java` | 未改动 | 通过 |
| 实测（授权靶场 `http://211.154.20.67:7779`） | 识别模式耗时 **596 ms**，报告只输出结论与原因；`/index/fastjson` 与 `/vulnapi/Fastjson/vul` 在未登录态下 POST/GET 均返回 405 空响应（`Allow: GET, HEAD` / `Allow: POST`），工具判定「无法探测」而非产生假阳性（带会话 Cookie 访问业务接口需登录态，本次未获取） | 正确 |

### 七、文档与备份

- 文档同步：`docs/DESIGN.md`（3.5 结果渲染的三处收敛、3.6 一键发送改为不自动探测、
  新增 3.7.1 探针并发含前后耗时对照表与两个既有判定缺陷、3.8 切页返回内容不丢）、
  `docs/DESIGN-probe-accuracy.md`（4.3 渲染契约更新、新增 4.3.1 探针并发与三态语义修复）、
  `README.md`（切页保留内容、一键发送不自动开跑、并发与报告精简）。
- 备份：改动前快照 `.backups/20260919-164500`，保留最近三次（已裁掉最旧的
  `20260919-155525`）。
## 2026-09-19（登录拦截识别 + 会话 Cookie：收尾、补齐端到端自检并纳入 Git）

本轮把上一轮未收尾的「登录拦截识别与会话 Cookie」做完：代码与设计文档当时已落地，
但构建、备份、AI 报告没做，且设计文档里承诺的一条端到端自检**实际并不存在**。

### 一、先诊断（接手时的实际状态）

- `.backups/` 最新快照是 `20260919-175034`，`AI_REPORT.md` 停在 17:17，而 `src/`、`python/`、
  `docs/` 在 18:00~19:46 都有改动——说明上一轮改完代码就中断了，没有构建与报告。
- `tools/_p1.._p6.diff` 已应用，`tools/_p7.diff`（`DESIGN-probe-accuracy.md` 的登录拦截章节）
  **未应用**：该文档里完全没有「登录拦截」字样。
- 更关键的一条：`DESIGN.md` 的验证矩阵写着「会话 Cookie 端到端（`UiSwitchEndToEndCheck`）」，
  但该文件里根本没有 `/gated`、`/auth` 桩服务，也没有任何会话 Cookie 断言——
  文档里那条检查点是凭空写上去了，属于「文档说有、代码没有」。

### 二、本轮改动

1. **补齐缺失的端到端自检**（`tests/UiSwitchEndToEndCheck.java`）：
   新增 `/gated`（登录拦截）与 `/auth`（会话有效才走解析器）两个桩服务，
   并给 `reply()` 加 `contentType` 重载——登录页必须按 `text/html` 返回，
   否则登录页特征根本不会被识别。新增 15 条断言，覆盖：
   三个模式的登录拦截结论前缀、不再出现「该路径只接受 GET」的误导描述、
   未带 Cookie 判「无法探测 + 否」、带有效 Cookie 后同一端点判命中、
   Cookie 失效时区分「已携带仍被拦截」、以及抓包「一键发送」带过的 Cookie 真能解锁端点。
2. **一处断言按真实行为修正**：最初我按文档写了「一键发送写进会话 Cookie 输入框」，
   实测失败——`sendCaptureToFastjson()` 是把 Cookie 写进「请求头（JSON）」的。
   该断言改为「写进请求头」+「真能下发到引擎并解锁端点」，比原先的控件断言更有意义。
3. **文档补齐**：`docs/DESIGN-probe-accuracy.md` 新增「二之补：登录拦截判定缺口」
   （含靶场实测对照表、为什么必须用完整响应体、实现要点、端到端验证矩阵），
   4.5 补会话 Cookie 与 `--headers` 的分工，已知限制补登录拦截的启发式边界，
   改动文件清单补本轮条目；`README.md` 补登录拦截语义、`--session-cookie` 用法与配置项。
4. **清理陈旧残留**：删除 `src/Main.java.new`（重构前的旧草稿，缺三个新包的 import，
   且不是 `.java` 不会被 Maven 编译）与 `tools/_*` 中间补丁脚本（备份里有留存）。

### 三、构建与验证

| 项目 | 结果 |
| --- | --- |
| Python 引擎单测 | `python -m unittest discover -s tests` → **71 项通过** |
| `UiSwitchEndToEndCheck` | **82 条断言通过**（含新增 15 条） |
| `UiNavigationCheck` / `ProxyServerCheck` / `ShiroCheck` / `UiShiroCheck` | 71 / 36 / 37 / 25 条断言，全部通过 |
| Maven 构建 | `BUILD SUCCESS`，JAR 输出到仓库根 |
| JAR 构建时间 | `2026-09-19 20:06:48`，**176056** 字节；晚于 `src/`、`python/`、`tests/` 下全部源文件 |
| JAR 内容 | `Main.class`、`ui/` `probe/` `config/` `util/` `proxy/` `shiro/` 各包、`python/fj_probe.py`、`shiro/res/shiro-keys.txt`；清单为 `Main-Class: Main` + `Class-Path: lib/java-chains-cli-2.0.0-beta4.jar` |
| JAR 内脚本一致性 | 解包出的 `python/fj_probe.py` 与源文件 SHA-256 完全一致（`02FA3B22...B4625FF`） |

### 四、备份与 Git

- 改动前快照：`.backups/20260919-195559`（含 `src/`、`python/`、`tests/`、`docs/`、
  `README.md`、`AI_REPORT.md`、`build.ps1`，共 40 个文件）。
- 备份保留策略：按「最近三次」裁剪，保留 `20260919-171531`、`20260919-175034`、
  `20260919-195559`。
- Git：仓库此前**没有** `.git`。本轮初始化 `main` 分支并纳入源码 / 测试 / 文档 / 构建脚本，
  两次提交：

  | 提交 | 内容 |
  | --- | --- |
  | `7002085` | 初始化仓库并纳入现有代码（44 个文件、14763 行） |
  | `c76640b` | `.gitignore` 补充 java-chains 回落缓存目录 |
  | `69099b6` | AI 报告补充本轮收尾记录与 Git 管理说明 |
  | `ceef631` | 统一行尾为 LF，保证打包产物可复现 |

  `.gitignore` 排除构建产物（`target/`、根目录 `JavaSecExpToolKit.jar`）、本地备份
  （`.backups/`）、运行期缓存（`chains-config/`）与运行时依赖目录 `lib/`。
- 关于 `lib/`：它由 `mvn` 从 `libs-repo/` 复制而来，属可再生产物，因此不入版本库。
  已实测验证：移走 `lib/` 后重新构建可完整重建，重建出的依赖与原件 SHA-256 一致
  （`F26E5E14...56B60D`）。
- 关于第三方依赖体积：`java-chains-cli-2.0.0-beta4.jar` 单包 **176 MB**，超过常规仓库单文件上限，
  因此配置 `.gitattributes` 让 `libs-repo/**/*.jar` 走 **Git LFS**（已确认暂存区内是指针文件
  而非实体，见 oid `f26e5e14...`）。

- **行尾统一（顺带修掉的一个真实隐患）**：本机系统级 `core.autocrlf=true`，克隆时会把文本
  改写成 CRLF。实测同一提交在克隆目录里构建出的 JAR 是 **176251** 字节、比本地 **176056**
  字节大 195 字节——即「同一份代码，不同机器产出不同产物」。因此 `.gitattributes` 增加
  `* text=auto eol=lf`，并把唯一残留 CRLF 的 `src/shiro/res/shiro-keys.txt` 归一化为 LF
  （读取方用 `BufferedReader.readLine()`，两种行尾均兼容，字典仍是 1108 行）。
- **可复现性验证**：重新 `git clone` 后构建，编排产物与本地构建**逐条目 SHA-256 完全一致**
  （69 个条目、无差异），体积同为 **176034** 字节。克隆仓库检出即可直接构建，无需额外步骤。

### 五、最终状态

- JAR：`2026-09-19 20:20:59`，**176034** 字节，晚于 `src/`、`python/`、`tests/` 下全部源文件。
- 工作区干净（`git status --short` 为空）；被忽略项为 `target/`、`JavaSecExpToolKit.jar`、
  `lib/`、`.backups/`、`__pycache__/`。
- `tools/` 只保留 `apply_patch.py`，本轮 24 个中间补丁脚本已清理（在备份快照中留有副本）。

## 2026-09-19（README 双语化 + 推送到远端仓库）

### 一、需求

用户要求 README 先「统一使用中文」，随后补充为「中文和英文两种形式」。

### 二、改动

- `README.md` 改写为**中文版**（GitHub 默认渲染这份），`README.en.md` 为对应的
  **英文版**；两份顶部互相链接（`语言：[中文](README.md) | [English](README.en.md)`）。
- 章节结构保持一一对应（各 11 个二级标题）：适用范围 / 环境要求 / 构建与运行 / 导航 /
  配置页 / 项目结构 / 抓包与格式转换 / 代理抓包 / Shiro 模块 / 命令行速查 / 测试。
  中文版按中文表述重写而非逐字直译，命令、参数与界面控件名保持原样。
- 项目结构表补上此前缺漏的分层目录：`src/ui/`、`src/probe/`、`src/config/`、`src/util/`，
  以及根目录新增的 `README.en.md`。
- 顺带修掉一个真实缺陷：原 README 的「CLI smoke test」小节**代码围栏未闭合**
  （全文 19 个围栏，奇数），导致其后所有内容被吞进代码块。两份新 README 均为 20 个围栏、
  严格配对（已在写入时断言校验）。

### 三、验证

| 检查点 | 结果 |
| --- | --- |
| 两份文件代码围栏配对 | 20 / 20，均偶数 |
| 章节数一致 | 11 / 11 |
| 语言互链 | 中文版含 `README.en.md`，英文版含 `README.md` |
| 中文版残留英文小标题 | 无 |

### 四、推送到远端

- 远端：`https://github.com/movon-ava/JavaSecExpToolKit.git`（添加为 `origin`，此前为空仓库）。
- 推送 `main` 分支成功，含 **Git LFS** 大文件（`java-chains-cli-2.0.0-beta4.jar`，
  185 MB，上传完成）。
- 端到端验证：从**远端全新克隆**后可正常检出（LFS 落为实体 184569201 字节），
  直接 `build.ps1` 构建成功，产物 **176034** 字节，且与本地产物**逐条目 SHA-256 完全一致**。

## 2026-09-20（配置页补全新增功能配置 + Fastjson 精简报告与一屏显示）

### 一、需求

1. 新增功能的配置没有写进配置页，像代理端口、IP 这类值只能在页面里临时填。
2. Fastjson 探测结果太多：只需要「是否 Fastjson、可能版本」等关键信息，不要探针明细，
   最好能一页显示。

### 二、根因分析

- **配置页缺失**：`ConfigPage` 原先只认文本 / 密码输入框，代理、抓包转换、Shiro 三块
  功能的默认值直接写死在 `Main.java` 的控件初始化里（如 `proxyPort = "8899"`、
  `shiroKey = "kPH+bIxk5..."`），关掉程序即丢。`applyConfigToProbeForm()` 也只覆盖探测页。
- **结果过多**：`format_report()` 只有一种详细输出——目标行、结论尾句、逐条探针明细、
  提示、阶段小结、已知限制全打。五模式一起跑 120 行，远超结果区可视高度。
- **一屏显示还差一层**：即使报告压到 16 行，探测页结果区实测可视高度仍接近 0，
  因为 508px 的表单被放在 `BorderLayout.NORTH`，把中间区域挤没了。这是**布局缺陷**，
  不是报告内容问题，只改报告长度解决不了。

### 三、改动

**1) 配置页补全（`src/ui/ConfigPage.java`、`src/Main.java`）**

- `ConfigPage` 新增勾选框（`UiKit.styleSwitch`）与下拉框样式支持，原先只处理文本框。
- 新增 4 个分组、14 个持久化键：
  - `探测报告配置`：`probe_report`
  - `代理配置`：`proxy_bind_host`、`proxy_port`、`proxy_intercept`
  - `抓包转换配置`：`capture_method`、`capture_content_type`、`capture_target`
  - `Shiro 配置`：`shiro_url`、`shiro_cookie_name`、`shiro_key`、`shiro_gcm`、
    `shiro_echo_header`、`shiro_chain`、`shiro_command`
- `applyConfigToProbeForm()` 扩展为 `applyConfigToForms()`，启动与保存后统一下发到
  探测页 + 代理页 + 抓包页 + Shiro 页。
- 代理启动成功后经 `rememberProxyEndpoint()` 把**实际绑定的**地址与端口写回配置，
  配置页的端口因此反映真实使用情况。
- 新增辅助方法：`flagFrom()`、`selectOption()`、`setComboByValue()`、
  `selectConfigChain()`、`selectChain()`。

**2) 精简报告（`python/fj_probe.py`、`src/probe/ProbeCommand.java`）**

- 新增 `--report brief|detail`，**默认 `brief`**；界面固定走精简。
- 新增 `_brief_conclusion()`（只取结论首句）、`_brief_method_line()`（默认 POST 不占行）、
  `_brief_flag()`（三态布尔）、`BRIEF_RENDERERS` 渲染表。
- 精简模式不留段间空行；判定字段做同类合并（版本三项一行、AutoType+SafeMode+置信度一行）。
- 新增 `_brief_login_gate_line()`：登录拦截单独成行。这条判定原本只在结论尾句里，
  精简后会被截掉，但它决定使用者下一步是补 Cookie 还是先登录，因此提升为独立行，
  并在结果 JSON 里新增 `login_gate_with_session_cookie` 字段。
- 抓包 / 转换模式强制保持详细（内容即结果）。

**3) 一屏显示（`src/ui/ProbePage.java`、`src/Main.java`）**

- 探测页照 `ShiroPage` 的做法改为上下分栏：上栏是表单滚动区 + **固定在滚动区外**的
  操作行，下栏是结果区；`SPLIT_RATIO = 0.38`，表单首选高度 300px、下限 120px。
  按钮不能跟着表单滚走，否则界面看起来像没有执行入口。
- 多模式拼接报告时只在缺失换行时补一个，去掉原先每两段之间多出的空行（五模式白占 4 行）。

**4) 测试隔离（`tests/UiNavigationCheck.java` 等三个 UI 自检）**

- 自检会把 `user.home` 指向临时目录再加载 `Main`。修改前自检启动代理会把测试用的
  **随机端口**写进使用者真实的 `config.properties`，属于真实缺陷。
- 已清理被写入的 `proxy_port` / `proxy_bind_host`（原文件备份
  `%USERPROFILE%\.JavaSecExpToolKit\config.properties.bak-20260919`）。

### 四、验证

| 检查点 | 结果 |
| --- | --- |
| Python 测试 | `Ran 74 tests ... OK`（新增 2 项：每段行数上限、登录拦截判定不被截断） |
| Java 编译 | `javac` 退出码 0（主源码 + 测试类） |
| `ProxyServerCheck` | 代理自检通过 |
| `ShiroCheck` | Shiro 模块自检通过 |
| `UiNavigationCheck` | 全部界面自检通过（新增 21 条配置页断言） |
| `UiShiroCheck` | Shiro 界面自检通过 |
| `UiSwitchEndToEndCheck` | 端到端自检通过（含精简 / 详细报告对比断言） |
| 精简报告行数 | 五模式合计 **16 行**（detect 3 / version 4 / expect 3 / dns 3 / ceye 3） |
| 详细报告行数 | 五模式合计 120 行（对比保留） |
| 结果区可视行数 | 默认 1280×800 窗口下 **16 行**（改造前为 0 行） |
| JAR 构建 | `2026-09-20 10:24:17`，`181933` bytes，晚于全部 `src` / `python` 源文件 |
| 备份 | `.backups/20260919-205153`（本轮改动前快照） |

### 五、涉及文件

| 文件 | 说明 |
| --- | --- |
| `src/Main.java` | 配置页新增 4 组控件与新键读写、代理端口回写、多模式拼接去空行 |
| `src/ui/ConfigPage.java` | 勾选框与下拉框样式支持 |
| `src/ui/ProbePage.java` | 表单滚动 + 上下分栏 + 固定操作行 |
| `src/probe/ProbeCommand.java` | `Options.report` 与 `--report` 参数下发 |
| `python/fj_probe.py` | `--report` 开关、精简渲染器、登录拦截独立行 |
| `tests/test_probe.py` | 精简契约断言（行数上限、结论首句、登录拦截） |
| `tests/UiNavigationCheck.java` | 配置页分组与控件断言、`user.home` 隔离 |
| `tests/UiSwitchEndToEndCheck.java` | 精简 / 详细模式对比断言、`user.home` 隔离 |
| `tests/UiShiroCheck.java` | `user.home` 隔离 |
| `README.md` / `README.en.md` | `--report` 说明、配置页新分组、代理端口回写 |
| `docs/DESIGN.md` | 3.5 报告详细度、配置章节、界面章节布局说明 |

## 当前限制

- **Fastjson 探测**仍只做识别：版本区间存在 `1.2.70-1.2.72` 与 `1.2.73-1.2.80` 无法细分的
  盲区，DNS 命中只代表类加载/网络访问发生。
- **Shiro 模块带利用性质**，会在目标上执行命令，仅限书面授权的目标；链能否打通取决于目标
  依赖与 JDK 版本，回显依赖 Tomcat 容器。
- **代理抓包**只对明文 HTTP 记录内容；HTTPS 仅 `CONNECT` 隧道透传，不解密、不记录，
  因此也无法拦截改包。记录上限 500 条且不保留历史列表，界面只展示最近一次返回包。
- 拦截是同步阻塞的，等待放行的连接会占住一个处理线程，120 秒超时自动放行。
- **登录拦截识别是启发式的**：依赖网页关键词（`请先登录` / `/user/login` / `password` 等）
  且要求多特征同时命中；目标若用 JSON 响应或纯状态码表达未登录，仍会走旧的传输层失败提示。
- **会话 Cookie 需人工获取**：工具不会自动登录（验证码等交互无法自动化），Cookie 要由使用者
  从浏览器或「代理抓包」取得；会话过期时会话失效，结论只能区分「未携带」与「已携带仍被拦」。
- 配置文件以明文保存在用户目录，请自行注意 Token 的存放安全。

## 2026-09-20（抓包格式直接可探测）

**需求**：修复 bug，保证通过代理抓包发包后的格式能直接探测。

### 一、根因（先诊断后动手，均已实测复现）

用户反馈的现象是「Shiro 一键检测返回未确认 Shiro」。实测后发现不是一个点的问题，
而是**从抓包到探测这条链路上有四处独立缺口**，且前三处都不报错——只是“静默地给出一个错误结论”。

**根因 1**：`ShiroEngine.parseHeaders()` 只认 `Key: Value`。抓包页 `cookie-header`
转换目标导出的是**裸 Cookie 值**（无冒号），旧逻辑把整行丢弃，登录态根本发不出去。
复现：填入 `9P5EjboW6ee0jsRDfkEdQKBA7ZXFkl4kYoN2...` 后解析结果为空。

**根因 2**：探测页 / Shiro 页粘贴非 JSON 请求头时引擎直接弹出
`JSONDecodeError: Expecting value: line 1 column 1`，探测根本未发出。

**根因 3**：`ShiroPage.outputPanel()` 每次进页面都 `addTab`，页签数 4 → 8 → 12，
回显区看起来像被拆成了好几份（用户截图里的 8 个页签）。

**根因 4（最隐蔽）**：`ShiroEngine.send()` 不但从不发送请求体（`Options.body` UI 没接），
而且 `exportProxyDetail()` 依赖的 `lastProxyFlow` **只在拦截放行时赋值**，
因此未勾选「拦截请求」（即最常用的观察模式）时点「转发到抓包转换」
一律报「还没有可转换的请求包」——抓包结果根本带不出去。

此外在做变量对照时发现两个**影响更大、但完全不报错**的问题（已用桩服务实测）：
抓包得到的请求头里常带 `Content-Length` 与 `Accept-Encoding: gzip`，直接照搬会让
**所有**探针失败：

- 旧 `Content-Length` 描述的是原请求体长，目标一直等一个不会到来的体 →
  `无法探测：所有探针均无法连接到目标（TimeoutError: timed out）`；
- `Accept-Encoding: gzip` 让目标压缩响应，而 `urllib` 不会自动解压 →
  `无法探测：所有探针返回同一份响应（相同前缀 83/83 字节）`。

### 二、修改

| 文件 | 修改 |
| --- | --- |
| `python/fj_probe.py` | 新增 `_header_key()` / `_looks_like_cookie_text()` / `parse_header_input()`：请求头支持 JSON、`Key: Value`、裸 Cookie 三种写法，无冒号行先合并再判断；新增 `_sanitize_request_headers()` 统一剔除旧 `Content-Length`、hop-by-hop 头，并把 `Accept-Encoding` 改为 `identity` |
| `src/shiro/ShiroEngine.java` | `parseHeaders()` 重写为容错版（新增 `isHeaderName()` / `looksLikeCookie()` / `sendableHeader()`）；`send()` 新增 `allowsBody()`，避免 `GET`/`HEAD` 写体被 JDK 静默降级成 POST |
| `src/Main.java` | 观察模式下也记录 `lastProxyFlow`；`exportProxyDetail()` 过滤不可转发头；Shiro 页新增「请求体」字段与 `shiro_body` 配置；一键发送带过请求体 |
| `src/util/HttpText.java` | 新增 `forwardable()`（可转发头判定）与 `bodyText()` / `isChunked()` |
| `src/probe/CaptureBridge.java` | 新增 `requestBody()`；`headerLines()` 改用 `forwardable()` 过滤 |
| `src/ui/ShiroPage.java` | 页签加 `getTabCount() == 0` 守卫（仅首次构建 addTab）；新增请求体输入框 |
| `tests/ShiroCheck.java` | 请求头断言更新到新行为，新增裸 Cookie、混用、跳转头 / gzip / 旧长度剔除断言 |
| `tests/UiSwitchEndToEndCheck.java` | 新增「观察模式导出」「一键发送到 Shiro 带会话 Cookie」「抓包头陷阱」端到端断言 |
| `tests/UiShiroCheck.java` | 新增「反复进出页面页签数仍为 4」断言 |
| `tests/UiNavigationCheck.java` | 新增「配置页含 Shiro 请求体输入框」断言 |
| `tests/test_probe.py` | 新增 `HeaderInputTest` / `CaptureHeaderSanitizeTest`（共 10 项） |
| `README.md` / `README.en.md` | 新增「抓包格式直接探测」章节；配置项补 `默认请求体` |
| `docs/DESIGN.md` / `docs/DESIGN-shiro.md` | 记录四处缺口、净化规则与验证载体 |

### 三、验证

| 检查点 | 结果 |
| --- | --- |
| Python 测试 | `Ran 84 tests ... OK`（新增 10 项） |
| `ShiroCheck` | 自检通过（含新增 11 条请求头断言） |
| `ProxyServerCheck` | 代理自检通过 |
| `UiNavigationCheck` | 全部界面自检通过 |
| `UiShiroCheck` | Shiro 界面自检通过 |
| `UiSwitchEndToEndCheck` | 端到端自检通过（含新增链路断言） |
| 裸 Cookie 实测 | 修前解析为空；修后 `{'Cookie': 'JWT_TOKEN=abc.def; JSESSIONID=xyz'}` |
| gzip 实测 | 修前「否 / 置信度 0.0」；修后「是 / 0.917」 |
| Content-Length 实测 | 修前「无法探测：超时」；修后正常判定 |
| 页签计数 | 修前 4 → 8 → 12；修后 4 → 4 → 4 |
| JAR 构建 | `2026-09-20 21:26:28`，`185903` bytes，晚于全部 `src` / `python` / `tests` 源文件 |
| 备份 | `.backups/20260920-204834`（改动前快照），目录仅保留最近三次 |

### 四、测试环境发现（与本次修改无关，仅作记录）

目标 `211.154.20.67:7779`（Java Security 靶场）对非法 rememberMe **从不回写
`deleteMe`**：扫了 17 个路径 × GET/POST × （无 Cookie / 随机 Cookie），
全部只回 `JSESSIONID`；未登录时 `/index/shiro` 直接返回登录页。
因此在该靶场上「未确认 Shiro」是目标行为导致，属于探测原理的固有局限
（需先登录拿到会话 Cookie，且目标仍需会回写 `deleteMe`）。
本轮修复的是“抓到的包能不能真正发出去”，这一点已用桩服务端到端验证。

### 五、涉及文件

见上表。本轮未新增任何第三方依赖；中间脉生成的临时脚本已全部删除，
`tools/` 仅保留 `apply_patch.py`。


## 2026-09-21（OpenSpec 引入 + 多 Agent 协同框架 + 模块化与 Payload 方案设计）

本轮为**纯设计工作**，不修改任何功能代码、不改变任何运行时行为。
产物是四份可执行的约束文档，用于让后续开发（尤其是多 Agent 并行）不再互相踩踏。

### 一、背景：三个已经实际发生的问题

1. **写入冲突**：`Main.java` 单文件 1533 行、70 个方法、100 个 `private final` 字段，
   承载 5 个功能页的全部状态。任何改动都绕不开它，多执行体并行必然互相覆盖。
2. **验收漂移**：功能改动后缺少统一回归入口，依赖人工记忆挑自检。
3. **越界修改**：`AGENTS.md` 已禁止修改未要求的功能，但缺少可机械判定的边界定义。

### 二、事实测量（本轮全部结论的数据基础）

| 项 | 实测值 | 取法 |
| --- | ---: | --- |
| `Main.java` 行数 | 1533 | 字节流统计 `\n` |
| `Main.java` 方法数 | 70 | 正则匹配方法声明 |
| `Main.java` `private final` 字段 | 100 | 正则匹配字段声明 |
| `Main.java` 职责行数 | 代理 278 / 配置 264 / 抓包 240 / Shiro 236 / 导航 213 / 探测 61 | 按方法归属累加行跨度 |
| 测试反射访问 `Main` 成员 | 80 | 扫描 `tests/*.java` 的 `fieldQuiet`/`getDeclaredField`/`getDeclaredMethod` |
| Java 源码总量 | 5630 行 / 22 文件 | 递归统计 `src/` |
| `python/fj_probe.py` | 2811 行 | 字节流统计 |
| java-chains 可用节点 | 429 | 运行时调 `ChainsEngine.nodeIds()` |
| java-chains payload 载体 | 28 | 运行时调 `ChainsEngine.payloadIds()` |

### 三、关键发现（改变了原方案的两处判断）

**发现 1：`src/ui/*Page.java` 已经是静态视图构建器，拆分方向早已确定。**
`ShiroPage`、`CapturePage`、`ConfigPage`、`ProbePage`、`ProxyPage` 共 1463 行，
通过 `Widgets` 结构体接收控件、经 `build(widgets, fonts)` 返回面板，自身不持有状态
（`ShiroPage` 注释原文：「只负责界面结构……本页不持有任何执行状态」）。
**结论：本次模块化的性质是「完成既有方向」，不是另起炉灶。**
原计划的「先抽视图」步骤因此不需要，直接从抽状态与行为开始。

**发现 2：测试反射耦合是拆分的前置阻塞项，而非附带问题。**
`tests/UiNavigationCheck.java:255` 等处的辅助方法用
`target.getClass().getDeclaredField(name)` 取控件——**只搜索本类，不含父类**。
一旦字段搬走，80 个断言点会立刻抛 `IllegalStateException`。
**结论：`Main.java` 拆分必须先在测试侧解除这个约束**，否则拆一步挂一步。

**发现 3：payload 功能可以零成本复用，但存在一处反向依赖。**
实测 java-chains 已注册 429 节点 / 28 载体，`ChainsEngine` 已封装好
`nodeIds` / `payloadIds` / `paramsOf` / `nextNodes` / `build`，
新功能不需要新增任何第三方依赖。
但 `src/shiro/ChainsEngine.java:195` 调用了 `ShiroEngine.base64(...)`，
若直接把该类搬进 `src/payload/` 会形成 `payload → shiro` 反向依赖。
**结论：必须先做一次共享内核下沉（`base64` 移入 `src/util/`），
且该重构要单独作为一个 change，不能混在 payload 功能里。**

### 四、产出物

| 文件 | 行数 | 内容 |
| --- | ---: | --- |
| `docs/DESIGN-agents.md` | 295 | 多 Agent 协同框架：角色划分、写入域矩阵、OpenSpec 映射、交接协议、监督清单 |
| `docs/DESIGN-modularization.md` | 253 | `Main.java` 模块化：现状测量、目标结构、三阶段拆分、验收标准 |
| `docs/DESIGN-payload.md` | 244 | Payload 生成功能：能力范围、载体分组、文件与写入域、界面、验证、安全约束 |
| `openspec/config.yaml` | 75 | 项目上下文（2418 字节）+ 四类规划件规则 + apply/archive 操作指引 |
| `openspec/specs/traffic/capture-bridge/spec.md` | — | 首个能力规格，回填上一轮「抓包格式直接可探测」 |
| `AGENTS.md` | +22 | 新增「规格驱动开发（OpenSpec）」小节，指向上述文档 |
| `README.md` / `README.en.md` | +2 / +2 | 项目结构表补充 `openspec/` 与 `.agents/`，docs 清单补全 |

### 五、Agent 划分结论

细分了功能开发 agent，最终为 **7 个角色**：
主 agent、功能开发 ×3（probe / exploit / traffic）、UI agent、测试 agent、监督 agent。

判断依据是「写入集合是否重叠」与「验证入口是否相同」，而非代码量：

| 细分角色 | 主语言 | 验证入口 | 与谁冲突 |
| --- | --- | --- | --- |
| probe | Python（2811 行） | `test_probe.py` | 无 |
| exploit | Java（1176 行） | `ShiroCheck` | 无 |
| traffic | Java（768 + 125 行） | `ProxyServerCheck` | 无 |

三者写入路径零重叠、验证入口互不包含。反证：若合并 probe 与 exploit，
单个角色需同时精通 Python 探测逻辑与 Java 反序列化链，单次任务上下文 3987 行。

监督 agent 定位为**只读审计者**，不写任何文件。它的 10 条审计清单中，
最关键的是「独立复算」——例如核对 JAR 时间时必须自己重新枚举源文件比较，
而不是引用执行者贴出的结论。这是该角色存在的唯一理由。

### 六、验证结果

| 项 | 结果 |
| --- | --- |
| OpenSpec 根识别 | `openspec doctor` 输出 `OpenSpec root: ok` |
| 配置解析 | `config.yaml` 可被解析，顶层键 `context` / `rules` / `operations` / `schema` 齐全 |
| 规格校验 | `openspec validate --specs --strict --json` 返回 `valid: true`，`passed: 1, failed: 0` |
| 编码检查 | 全部新增/修改文件无 BOM、无 `\r`，与仓库既有行尾一致 |
| 代码改动 | **无**。本轮未触碰任何 `.java` / `.py` 功能代码 |

### 七、如实说明的局限

1. 多 Agent 框架与模块化方案**均未实施**，目前只是文档约束。
   `Main.java` 仍是 1533 行，payload 功能尚未存在。
2. 监督 agent 的审计清单是人工可执行的标准，**不是自动化工具**。
   本轮未实现任何脚本来自动执行这些核查。
3. `.agents/` 与 `.pi/` 同时存在且 SKILL.md 哈希不一致（实测两套内容不同），
   属于 OpenSpec 为不同宿主工具生成的产物。本轮未做取舍，保持现状。
4. `openspec/specs/` 下目前只有 1 个能力规格（`traffic/capture-bridge`），
   其余 capability（probe、shiro、proxy、config、build）尚未建立规格，
   需在后续 change 中逐步补齐。


## 2026-09-21（链引擎解耦 + 依赖边界自检 + 多 Agent 职责文档）

本轮把上一轮的设计结论落地：打通 `payload` 功能的前置阻塞项，
并把「是否被正确解耦」从人工判断变成可机械执行的断言。
按 OpenSpec 工作流执行：先 `propose` 产出规划件，再 `apply` 实施。

### 一、根因分析（先诊断后动手）

实测 `src/shiro/` 下三个文件的性质并不相同：

| 文件 | 行数 | 性质 |
| --- | ---: | --- |
| `ShiroEngine.java` | 719 | Shiro 专用 |
| `ShiroExploit.java` | 192 | Shiro 专用 |
| `ChainsEngine.java` | 265 | **通用**（java-chains 封装） |

`ChainsEngine` 封装的是「选载荷载体 → 追加 gadget 节点 → 构建」这套与漏洞类型无关的机制，
实测可驱动 429 个节点、28 种载体（hessian、jndi、xstream、blazeDS 等），
却位于 `shiro` 包内，并在第 195 行调用 `ShiroEngine.base64(...)`。

**这是全项目唯一的真实反向依赖**：通用组件被具体功能模块持有。
若不先解耦，后续 payload 生成功能要么接受 `payload → shiro` 的反向依赖，
要么在功能开发中途做跨模块重构。

补充说明：`ShiroExploit → ChainsEngine` 与 `ShiroExploit → ShiroEngine` 是 Shiro 包**内部**的
同向依赖，不构成耦合问题，本轮不动。

### 二、包级依赖基线（独立复算，含全限定名）

| 依赖边 | 处数 |
| --- | ---: |
| `<default>`（Main，组合根）→ `ui` / `proxy` / `shiro` / `probe` / `util` / `config` | 50 |
| `ui` → `proxy` / `shiro` / `util` | 3 |
| `shiro` → `util` | 1（本轮新增，解耦后） |
| `probe` → `util` | 3 |

解耦前 `shiro → shiro` 内部存在 `ChainsEngine → ShiroEngine`；
解耦后已消失，`shiro` 对 `util` 出现一条新边（引用共享内核 `Codec`）。

### 三、改动内容

| 文件 | 性质 | 说明 |
| --- | --- | --- |
| `src/util/Codec.java` | 新增 | 共享内核：`base64` / `decodeBase64`，纯函数，无项目内依赖 |
| `src/shiro/ShiroEngine.java` | 改 | 两个方法改为委托 `Codec`，**公开签名不变**；移除 `java.util.Base64` import |
| `src/shiro/ChainsEngine.java` | 改 | 第 195 行改用 `util.Codec.base64`，文件中已无 `ShiroEngine` 标识符 |
| `tests/test_decoupling.py` | 新增 | 依赖边界自检：11 项断言 |
| `docs/AGENT-ROLES.md` | 新增 | 六个角色的职责、边界、交付物与协作顺序 |
| `docs/DESIGN-agents.md` | 改 | 监督角色补充「解耦专项审计」与三类判定标准 |
| `README.md` / `README.en.md` | 改 | 项目结构与文档索引同步 |

**非 BREAKING**：`ShiroEngine.base64` / `decodeBase64` 的签名与行为完全不变，
`ShiroExploit:176`、`tests/ShiroCheck.java`、`tests/UiShiroCheck.java` 等既有调用方无需改动。

### 四、依赖边界自检（可机械执行）

`tests/test_decoupling.py` 把规则固化为断言，只用标准库做源码静态扫描：

| 断言 | 判定方法 |
| --- | --- |
| 扫描有效性 | 六个包必须都被扫到，防止规则因扫不到文件而恒真通过 |
| 包级无环 | 构建包依赖图，检测双向边 |
| 符合声明分层 | 与允许边集合比对；`<default>` 作为组合根允许装配任意模块 |
| 叶子层无出边 | `config` / `proxy` / `util` 不得依赖其它项目包 |
| 通用组件不依赖具体功能 | `ChainsEngine` 源码不得出现 `ShiroEngine` |
| 共享内核单向 | `util` / `config` 不得依赖上层 |

**反向验证（证明检查器不是恒真）**：临时把 `ChainsEngine` 的 `util.Codec.base64` 改回
`ShiroEngine.base64`，该断言立即失败并报出文件与原因；恢复后重新通过。
另有 2 项针对环检测器与扫描器的自测，覆盖「假环图能报出、无环图不误报、
注释与字符串不算引用、真实引用不被漏掉」。

### 五、监督 Agent 独立审计结论

监督角色按要求**不复用实现者的检查脚本**，另写独立实现（只认 import 行与全限定名两种引用形式，
独立重建依赖图）复算。两次独立复算（import-only 与含全限定名）结论一致：

| 审计项 | 结论 |
| --- | --- |
| 写入域合规 | 通过。改动集中在 `src/util`、`src/shiro`、`tests`、`docs`、README |
| 范围合规 | 通过。未触碰界面、配置键、代理与探测功能 |
| 承诺兑现 | 通过。tasks.md 15 项全部有对应改动 |
| 空实现检测 | 通过。`src/` 与 `python/` 下无 `TODO` / `FIXME` / `此处省略` |
| 依赖合规 | 通过。`src/pom.xml` 未变，无新增第三方依赖 |
| 构建一致性 | 通过。独立枚举源文件后确认无晚于 JAR 的文件 |
| 解耦专项 | 通过。无环、叶子层无出边、共享内核无反向依赖、通用组件已解耦 |
| 阻断结论 | **可归档** |

### 六、验证结果

| 项 | 结果 |
| --- | --- |
| Java 编译 | `src/` 23 个文件编译通过（仅既有 unchecked 警告） |
| Python 测试 | `Ran 95 tests ... OK`（84 → 95，新增 11 项） |
| `ShiroCheck` | 通过（含加解密往返回归断言） |
| `ProxyServerCheck` | 通过 |
| `UiNavigationCheck` | 通过 |
| `UiShiroCheck` | 通过 |
| `UiSwitchEndToEndCheck` | 通过 |
| `openspec validate --all --strict` | 2 项全部 `valid: true` |
| JAR 构建 | `2026-09-21 11:40:42`，`186414` bytes |
| JAR 时间校验 | 晚于 `src/`、`python/`、`tests/` 全部源文件 |
| JAR 内容校验 | `util/Codec.class` 在包内；`shiro/ChainsEngine.class` 已不含 `ShiroEngine` 引用；`python/fj_probe.py` 哈希与源文件一致 |

### 七、解耦判定标准（本轮确立，写入监督清单）

本仓库不追求为解耦而解耦，判定分三类：

| 判定 | 标准 | 实例 |
| --- | --- | --- |
| 必须解耦 | 通用机制被具体功能模块持有，且已预见复用需求 | 本轮处理的 `ChainsEngine → ShiroEngine` |
| 可不改 | 单向依赖、不构成环、不阻碍复用 | `ui` 引用 `ShiroExploit.ChainKind`；`FlowRenderer` 引用 `ProxyServer.HttpFlow` |
| 不做 | 职责本身要求依赖多方 | `Main` 对全部模块的装配依赖（组合根本职） |

### 八、如实说明的局限

1. `ChainsEngine` **仍位于 `src/shiro/` 包内**。这是刻意的范围控制：
   搬迁需同步改 `src/Main.java` 与 `ShiroExploit` 的 import，属独立 change。
   解耦已完成，搬迁现在是零语义改动。
2. 依赖边界自检是**源码文本扫描**，不是字节码分析。
   已通过剥离注释与字符串降低误报，但仍可能漏掉动态引用（如反射）。
   当前仓库未使用反射访问跨包类型，风险可接受。
3. `ui → shiro`（`ShiroPage` 引用 `ShiroExploit.ChainKind`）与 `ui → proxy`
   （`FlowRenderer` 引用 `ProxyServer.HttpFlow`）**有意保留**：
   界面层引用其展示对象的数据类型是正常单向依赖，按第三节标准属于「可不改」。
4. `openspec/specs/` 下现有 2 个能力规格（`traffic/capture-bridge`、
   `codebase/dependency-boundary`），其余 capability 待后续 change 补齐。

## 2026-09-21（多 Agent 并发工具链 + 写入域机械校验 + 运行手册）

本轮回答并落地一个问题：**同时开多个 agent 时，怎样保证互不干扰又能配合**。
结论不是设计推演，全部来自本机实测；过程中发现并修掉了 3 个自己引入的真实缺陷。

### 一、结论（先说能不能）

| 问题 | 结论 | 实测依据 |
| --- | --- | --- |
| 多个 agent 同时改代码会互相覆盖吗 | 不会 | 两个 agent 并发运行，各自 worktree 内改文件、各自提交，主仓库内容与 `git status` 均不受影响 |
| 能同时跑测试吗 | 能 | 自检全部用 `InetSocketAddress(host, 0)`、`ServerSocket(0)` 动态端口；UI 自检把 `user.home` 指向临时目录，无端口与配置冲突 |
| 监督 agent 会改坏东西吗 | 不会 | 强制 `-s read-only`，实测写入被内核拒绝（连系统临时目录也拒） |
| 怎么开启、怎么指定角色 | `tools/agent.ps1 -Role <角色> -Slug <标识> -Task "<任务>"` | 脚本自动建 worktree、建分支、套角色卡、定沙箱、启动 `codex exec` |

### 二、隔离实测（关键证据）

| 机制 | 实测结论 |
| --- | --- |
| worktree 隔离 | worktree 内覆盖 `src/Main.java` 并新建文件后，主仓库该文件首行仍为 `import config.AppConfig;`，新文件在主仓库不可见 |
| 分支隔离 | 两个 agent 并发提交，分别得到独立 commit，互不覆盖 |
| 只读沙箱 | 让只读会话建文件，被拒且未落盘，会话自述 `UnauthorizedAccessException` |
| 会话分叉 | `codex fork` / `codex exec fork <id>` 可从既有会话派生互不影响的新会话 |

### 三、过程中发现并修复的 3 个真实缺陷

均为本轮实测暴露，不是推测：

1. **默认沙箱下 agent 无法执行任何 git 操作**
   根因：worktree 的真实 git 目录在 `<仓库>\.git\worktrees\<name>\`，
   对象库在 `.git\objects\`，Git LFS 临时目录在 `.git\lfs\`，三处都在工作区之外。
   实测 `git status` 都会因 `external filter 'git-lfs filter-process' failed` 失败。
   处置：脚本为可写角色放行本 worktree 的索引与 `.git\lfs`。

2. **让 agent 自己提交不可靠，且会污染共享对象库**
   根因：放行 `.git\objects` 后提交可行，但该目录下部分子目录的 ACL 未继承沙箱
   用户权限，实测报 `insufficient permission for adding an object`；
   修复 ACL 后虽能提交，却在共享对象库留下 9 个不可达对象。
   处置：改为 **agent 只改文件、提交由脚本在沙箱外完成**，对象库完全不被 agent 触碰。

3. **监督角色（空写入域）被误判为「未定义」而抛异常**
   根因：PowerShell 中 `-not @()` 为 `True`，用真值判断会有键检查的语义错误。
   处置：改用 `$writeScopes.ContainsKey($Role)`。

同时修掉一个我自己引入的误导：曾把「用 `filter.lfs.*` 覆盖参数读 git 状态」写进
提示词，实测该写法会把 LFS 指针文件报成已修改（`Bin 134 -> 184569201 bytes`），
导致 agent 误判环境噪声为改动。放行 `.git\lfs` 后普通 `git status` 即可用，已移除。

### 四、共享资源冲突点（如实说明）

worktree 只隔离工作区文件，以下仍是全仓库共享的真冲突点：

| 共享资源 | 并发风险 | 处置 |
| --- | --- | --- |
| `.backups/` | 各自轮转删除，互相删掉对方快照 | 并发时不备份，主 agent 合并后统一执行 |
| `AI_REPORT.md` / `PROGRESS.md` | 同时追加冲突 | 同上 |
| 根目录 JAR、`target/`、`lib/` | `build.ps1` 路径写死，并发构建互相覆盖 | 并发时不构建 |
| `src/util/**`、`src/config/**` | 共享内核被多角色争改 | 主 agent 单独开 change，串行 |
| Maven 本地仓库 | 跨 worktree 共享，并发下载可能撞锁 | 依赖已离线缓存；构建本来就被串行化 |
| `src/Main.java` | UI agent 独占的页面汇合点 | UI 改动串行 |

### 五、写入域从「文档纪律」升级为「机械约束」

`tools/agent.ps1` 内置写入域矩阵，提交前逐文件比对：区间内才提交，
只要有一个文件越界就整体撤出暂存并列出越界文件。双向实测：

- 让 traffic 角色改 `python/` 下文件 → 被拒绝提交，改动回退为未跟踪；
- 让 traffic 角色改 `src/proxy/` 下文件 → 正常提交。

矩阵与 `docs/AGENT-ROLES.md` 一致，`openspec/config.yaml` 已加「矩阵必须同步」的规则。

### 六、产出物

| 文件 | 性质 | 说明 |
| --- | --- | --- |
| `docs/AGENT-RUNBOOK.md` | 新增 | 运行手册：三种开启方式、各角色命令、隔离实测、共享资源处置、排错 |
| `tools/agent.ps1` | 修改 | 补 git 目录授权；改为沙箱外代提交 + 写入域机械校验；提示词与回复移到临时目录，不再污染 worktree |
| `docs/AGENT-ROLES.md` | 修改 | 补「不自行提交」「并发不碰共享资源」「收尾由主 agent 统一执行」 |
| `openspec/config.yaml` | 修改 | 测试数 95 → 99；补并发隔离规则与脚本矩阵同步要求 |
| `README.md` / `README.en.md` | 修改 | 新增「多 Agent 并行开发」章节，中英文一一对应 |
| `openspec/specs/process/multi-agent-concurrency/spec.md` | 新增 | 把隔离与协作边界写成可校验契约（四条需求、八个场景） |

### 七、验证结果

| 项 | 结果 |
| --- | --- |
| Python 测试 | `Ran 99 tests ... OK`（95 → 99） |
| `ShiroCheck` | 通过 |
| `ProxyServerCheck` | 通过 |
| `UiNavigationCheck` | 通过 |
| `UiShiroCheck` | 通过 |
| `UiSwitchEndToEndCheck` | 通过 |
| `tools/audit_boundary.py` | 结论「全部通过」，无环、无越界、叶子层无出边 |
| `openspec validate --all --strict` | 3 passed, 0 failed |
| `build.ps1` | BUILD SUCCESS，JAR `2026-09-21 13:10:44`，`186414` bytes，`Source freshness checked: 31 file(s)` |
| JAR 内引擎哈希 | `python/fj_probe.py` 与源文件 SHA256 一致 |
| 备份 | `.backups/` 轮转为最近三次 |

### 八、如实说明的局限

1. `tools/agent.ps1` 只在 Windows PowerShell 5.1 实测；脚本含中文，必须保持
   UTF-8 带 BOM，否则解析失败。
2. 并发上限仍是**两个执行角色**（以写入域不重叠为前提）。这不是脚本限制，
   是为了让冲突面保持在可人工审阅的范围内。
3. 沙箱对 `<仓库>\.git` 的授权只覆盖本 worktree 的索引与 LFS 目录；
   若手工启动 agent 且期望它自行提交，需自行放行 `objects` / `logs` / `refs\heads`，
   并接受第三节第 2 条的偶发失败风险。
4. `src/probe/**` 同时出现在 probe 与 traffic 的写入域内，脚本层面无法区分
   同一目录下的文件分工，靠 `docs/AGENT-RUNBOOK.md` 的约定约束
   （probe 改 `Probe*.java`、traffic 改 `CaptureBridge.java`）。这是已知的
   粗粒度点，后续如需机械拦截，要把矩阵细化到文件级。
5. 本轮未处理 `.pi/`（未跟踪目录，来源待确认），保持原样未纳入版本库。

## 2026-09-21（Agent 会话超时上限）

### 一、根因（先诊断后动手）

上一轮用 `tools/agent.ps1` 派发监督审计后，会话运行约 15 分钟仍无结论，
进程最终被回收，**产出完全丢失**，只剩提示词文件。

根因：脚本调用 `codex exec` 时没有任何时间上限，一次跑偏或任务过宽的会话
会无限期占用终端且不产出可用结果。这不是 agent 的问题，是脚本缺少兜底。

### 二、改动

| 文件 | 改动 |
| --- | --- |
| `tools/agent.ps1` | 新增 `-TimeoutMinutes`（1–600）与按角色的默认上限；启动改为 `Start-Process` 以便超时控制 |
| `tools/agent.ps1` | 提示词改走 stdin 文件（命令行参数对长文本与特殊字符不稳定） |
| `tools/agent.ps1` | 超时后用 `taskkill /T /F` 终止整个进程树，并打印日志尾部供判断进度 |
| `AGENTS.md` | 新增「超时与并发」约束：必须设超时上限，未完成产出不得当作可用结果 |
| `docs/AGENT-RUNBOOK.md` | 新增 2.4 节：各角色默认上限、显式覆盖、超时后处置 |
| `openspec/specs/process/multi-agent-concurrency/spec.md` | 新增「角色会话必须有超时上限」需求（含两个场景） |
| `openspec/config.yaml` | 架构约定补充超时要求 |

默认上限：主 agent 45 分钟；功能开发与 UI、测试各 40 分钟；监督 60 分钟。

### 三、实测

| 项 | 结果 |
| --- | --- |
| 正常路径 | `-TimeoutMinutes 3` 的会话正常完成并打印最终回复、日志尾部 |
| 超时触发 | 让 agent 执行 `ping -n 400`（约 400 秒），设上限 1 分钟：实测 **70 秒**终止，打印 `[超时]` 段、处置建议与日志尾部 |
| 进程清理 | 终止后 `Get-Process` 无该项目新起的 `codex` / `node` / `ping` 残留 |
| 不污染仓库 | 超时后改动未提交，现场保留在独立 worktree，主仓库 `git status` 不受影响 |
| 中文保真 | 改用 stdout/stderr 重定向到文件后，`-o` 输出文件的中文正常，无问号乱码 |

### 四、如实说明的局限

1. 超时判定依据是「进程是否在限定时间内退出」，无法区分「agent 在做实事」与
   「agent 卡住」。因此超时后必须看日志尾部人工判断，脚本不自动合并产出。
2. Windows 上 npm 安装的 codex 是 node 包装脚本，直接终止它不会带走 node 子进程；
   脚本因此优先定位 `codex.exe`，找不到时回落到 `codex`，此时进程树清理可能不完整。
3. 默认上限是依据本仓库已实测的耗时给出的经验值，不是测量出来的最优值；
   任务确实更久时应显式调大，而不是取消限制。

## 2026-09-21（看护 agent：读日志区分「推进中」「长等待」与「卡住」）

回答并落地一个问题：**能不能加一个 agent 去看日志，区分会话是在做事还是卡住**。
结论是能，但不能按「日志静默多久」来判断——本轮全部结论来自实测，
过程中又发现并修掉了 3 个自己引入的真实缺陷。

### 一、根因（先诊断后动手）

现有超时上限只能回答「进程有没有退出」，回答不了「它是在干活还是卡住了」。
上一轮已经写出这个局限，本轮把它补上。

但直接按「静默时长」做机械判据是**错的**，有实测反例：

| 样本 | 日志现象 | 真实性质 |
| --- | --- | --- |
| 执行 `ping -n 400` | 日志静默 **771 秒**、毫无新增 | 正常等待，不是故障 |
| 反复重试同一条失败命令 | 日志静默时间不长，但内容在原地打转 | 真卡死 |

两者在「静默时长」这一个维度上无法区分，差别只在**日志内容**里。
因此设计定为：**机械轮询只负责触发，内容判断交给 LLM，判定方不做终止动作。**

### 二、改动

| 文件 | 改动 |
| --- | --- |
| `tools/lib/CodexCli.ps1` | 新增。抽出「定位 codex 可执行文件」与「按 PID 终止整个进程树」，供启动脚本与看护脚本共用 |
| `tools/watchdog.ps1` | 新增。读日志尾部（默认 120 行）交只读 agent 判定，输出 `VERDICT: PROGRESSING\|WAITING\|STUCK`；解析不到返回 `UNKNOWN`；自身带 180 秒判定上限 |
| `tools/agent.ps1` | 等待由「一次性阻塞」改为轮询：每 `-PollSeconds`（默认 60）检查日志大小与修改时间，连续 `-StallRounds`（默认 2）轮无变化才唤起看护，据此决定继续等还是终止 |
| `tools/check_agent_tools.ps1` | 新增。把本轮踩到的缺陷固化成 34 项机械断言 |
| `docs/AGENT-ROLES.md` | 新增看护角色：只读、给判定依据、不负责终止 |
| `docs/AGENT-RUNBOOK.md` | 新增 2.5 节与实测结论表，排错表补 3 行 |
| `README.md` / `README.en.md` | 多 Agent 章节补充说明与工具链自检入口 |
| `openspec/changes/detect-agent-stall/**` | 新增 change 规划件（proposal / design / tasks / delta spec） |

**设计要点：判定方不杀进程。** 看护在只读沙箱运行，且脚本不接受任何进程标识参数，
结构上就碰不到被判定会话；终止只由持有进程句柄的 `agent.ps1` 执行。

### 三、本轮修掉的 3 个真实缺陷（自己引入的）

| 缺陷 | 根因 | 后果 | 修法 |
| --- | --- | --- | --- |
| `-NoWatchdog` 完全不生效 | 判断处写成 `$Watchdog`，该变量从未赋值、恒为空，`-not $Watchdog` 恒为真 | 开关形同虚设，且静默统计被整体跳过，看护永不可能触发 | 改为 `$NoWatchdog` |
| 开启即崩：`value 0 is not a valid value for the StallRounds variable` | 计数器 `$stallRounds` 与参数 `-StallRounds` **同名**（PowerShell 变量名大小写不敏感），赋 `0` 触发 `ValidateRange(1,20)` | 脚本主体中断，看护功能完全不可用 | 计数器改名 `$stallStreak` |
| `tools/lib/CodexCli.ps1` 无法入库 | `.gitignore` 写的是 `lib/`（未锚定根目录），连带忽略了 `tools/lib/` | 共用库没有版本控制，工具链换机器即失效 | 改为 `/lib/` |
| 三处注释是字面量 `\uXXXX` 转义 | 上一轮写入时的转义残留，PowerShell 不会解码 | 注释打印成乱码，可读性受损 | 用正则统一解码，并加入自检防回归 |

前两个缺陷是**功能级**的：如果不做端到端实测，只跑语法检查会全部通过，
而功能实际是坏的。这也是本轮加 `tools/check_agent_tools.ps1` 的直接原因。

### 四、实测（关键证据）

**判定质量**：用真实 codex 对两种真实日志样本各跑一次（非模拟）：

| 输入样本 | 判定 | 判定理由（原文要点） |
| --- | --- | --- |
| 日志写明在等 `ping -n 400`、预期约 400 秒 | `WAITING` | 「明确显示正在执行一条耗时命令……不构成 STUCK」 |
| 三轮重试同一命令、同一错误 `FAILED (errors=3)` | `STUCK` | 「关键依据是重试模式本身」，而非静默时长 |

**判定链路**：用真实 `tools/agent.ps1` + 冻结日志的假会话做端到端：

| 场景 | 观察到的行为 | 耗时 |
| --- | --- | --- |
| 冻结「反复重试」日志 | `[看护] 日志已静默 1 轮` → `[看护] 判定结论：STUCK` → `[卡死] ... 正在终止`，退出码 124 | 30 秒 |
| 冻结「长等待」日志 | 连续 3 次 `WAITING`，**未误杀**，最后由总超时兜底终止 | 72 秒 |
| 加 `-NoWatchdog` | 完全不调用看护，行为回到纯超时 | 73 秒 |
| 终止后进程清理 | `get-ciminstance` 查 `PING.EXE` 与 cmd 子进程均为空，无孤儿 | — |

**保守路径**：

| 输入 | 结果 |
| --- | --- |
| 判定输出里没有 `VERDICT` 行 | `UNKNOWN`（不崩溃、不误判为 `STUCK`） |
| 日志文件不存在 | `UNKNOWN` 且退出码 2 |
| 多个状态名同时出现（`not STUCK...` 后跟 `VERDICT: STUCK`） | 取最后一个，结果为 `STUCK` |

**自检脚本本身也被验证**：人工注入 6 个变异（开关名写错、变量名冲突、去 BOM、
塞回字面量转义、`.gitignore` 去锚定、看护改杀外部进程），
`tools/check_agent_tools.ps1` 对 6 个全部报失败并给出准确项名，
基线（无变异）34 项全通过。

### 五、验证结果

| 项 | 结果 |
| --- | --- |
| `openspec validate --all --strict` | 4 passed, 0 failed |
| Python 测试 | `Ran 99 tests ... OK` |
| 工具链自检 | 34 项全通过 |
| `tools/audit_boundary.py` | 全部通过 |
| `ShiroCheck` / `ProxyServerCheck` / `UiNavigationCheck` / `UiShiroCheck` / `UiSwitchEndToEndCheck` | 全部通过 |
| 依赖合规 | 未引入任何第三方依赖；未改 `src/pom.xml` |

### 六、如实说明的局限

1. **LLM 判定不是形式化证明**：它是基于日志内容的概率判断，可能判错。
   缓解方式是「只读 + 不终止 + 保守继续等待」，最坏情况退化为现在的纯超时行为。
2. **`STUCK` 需要静默到一定程度才会被唤起**。真实 codex 会话在长等待期间会持续
   输出心跳文本，日志一直在增长，因此端到端跑真实 agent 时看护通常**不会触发**。
   这是正确行为（有推进就不该判卡死），但也意味着看护不是万能兜底；
   真正的长耗时任务仍应靠调大 `-TimeoutMinutes`。
3. **看护有调用成本**：每次判定要跑一次只读会话（实测 7–9 秒）。
   靠 `-StallRounds` 控制频率，默认 2 轮（约 2 分钟）才判一次。
4. **看护只能看日志**：它无法观测进程的 CPU、网络、文件句柄状态。
   若日志本身没有反映真实进展，判定就会失准。
5. `tools/` 下的脚本目前**没有纳入 Python 单测**（`python -m unittest` 只覆盖 `python/`）。
   回归入口是独立的 `tools/check_agent_tools.ps1`，需要单独执行。
6. 本轮只改 `tools/`、`docs/`、`README*`、`openspec/`，**未触碰 `src/`、`python/`、`tests/`**，
   运行时行为零变化；JAR 内容不变，但仍按要求重新构建并校验时间。

## 2026-09-21（多 Agent 落地：单终端并发、派发权边界、监督接入点）

回答三个关于「怎么用起来」的问题，结论全部来自本机实测；
过程中修正了 2 处与实测不符的既有文档描述。

### 一、需要开多个终端吗？——不需要

方式二的脚本会阻塞到 agent 结束，但这不等于必须多开终端：

```powershell
Start-Process powershell -ArgumentList @(
  '-NoProfile','-ExecutionPolicy','Bypass','-File','tools\agent.ps1',
  '-Role','probe','-Slug','a','-Task','...'
) -WindowStyle Hidden
```

| 实测项 | 结果 |
| --- | --- |
| 一个终端后台跑两个角色 | 成功，前台终端未被占用，中途仍可执行其它命令（34 秒完成） |
| 两个 worktree 目录 | 各自独立生成 |
| 主仓库污染 | 无 |
| A 分支提交后 B 能否看到 | 看不到；B 的同名文件未变，主仓库也未变 |
| 杀掉 A，B 是否受影响 | 不受影响，B 继续跑到自然结束（退出码 0） |
| 两个 agent 同时提交 | 各自提交到自己的分支，未出现 `index.lock` 冲突 |

**附带发现**：`tools/agent.ps1` 用的是 `codex exec`（非交互式），stdin 被重定向到提示词文件，
因此**中途无法输入**。人能做的只有「等」「读日志」「终止」（终止后可用 `-ReuseWorktree` 接着跑）。
若确实需要人工实时接管，只能用方式一的交互式会话，代价是角色约束靠自觉。

### 二、主 agent 会自动分发任务给各个 agent 吗？——不会，机制上做不到

这是本轮最重要的一条结论，已用真实 orchestrator 会话实测（不是推测）：

它在自己的沙箱内执行 `git worktree add G:/java/jset-agents/spawn-child -b agent/test/spawn-child`，得到：

```
fatal: cannot lock ref 'refs/heads/agent/test/spawn-child':
unable to create directory for .../.git/refs/heads/agent/test/spawn-child
```

退出码 255。另一次探针显示 `New-Item G:\java\jset-agents\sandbox-probe-tmp` 也被拒
（`UnauthorizedAccessException`）。

根因：agent 沙箱只放行三处——本次 worktree、`.git\worktrees\<name>`、`.git\lfs`。
`.git\refs`、`.git\logs` 不在内，而 `git worktree add -b` 必须在 `.git/refs/heads/...` 建引用并加锁；
`G:\java\jset-agents\` 对 agent 也是只读，子 worktree 目录同样建不出来。

**所以真实形态是**：派发权在**沙箱外**的 `tools/agent.ps1` 手里，由人在终端发起；
主 agent 负责的是**规划、判定是否跨域、集成与收尾**，它不是调度器。
这是设计上的隔离而非缺陷：若给 agent 放行整个 `.git`，它就能无限分裂出不受控的工作区，
写入域与并发度将不再由脚本集中掌控。已把这条写进 `docs/AGENT-ROLES.md` 的「能力边界」段。

### 三、监督 agent 需要另开终端做实时监督吗？——不需要

它不是实时监控程序，而是**阶段末的一次只读审计**，原因有三：

1. 没有实时信源：只读沙箱只能读文件，读不到另一个会话的流式输出；执行中未提交的内容它看不到。
2. 判据是整体状态而非时序：断言数量对比、JAR 时间校验、空实现检测，都要等产出落定后才成立。
3. 做实时判定的是**看护 agent**（读会话日志判三态），两者职责不同，不应混用。

**它的接入点与可见范围（实测）**：监督角色直接在**主仓库**以 `read-only` 运行，不建 worktree。
提示词写的是「审计对象就是当前工作区的未提交改动」，但仓库对象库是共享的，
因此它**同时能读到各执行角色已提交的分支**：

```powershell
git log  --oneline main..agent/probe/<slug>
git diff --stat      main..agent/probe/<slug>
git show agent/probe/<slug>:python/fj_probe.py
```

建议在**执行角色已提交、尚未合并**时审计——此时分支与主线的差异就是完整的待审内容。

### 四、修正了两处与实测不符的文档描述

| 位置 | 原文 | 实测事实 |
| --- | --- | --- |
| 运行手册 2.2 节 | 「并发 = 同时开多个终端各跑一条命令」 | 后台启动即可，单终端可并发 |
| 运行手册结论速览 | 无「是否要开终端」「谁负责派发」两项 | 已补两行，并新增 2.4 节与「二之二」节 |

### 五、验证结果

| 项 | 结果 |
| --- | --- |
| 工具链自检 | **39 项**全通过（新增 5 项：授权目录边界、监督运行位置） |
| 自检有效性 | 注入变异（把 `.git/lfs` 授权换成 `.git/objects`）能被报失败，基线通过 |
| Python 测试 | `Ran 99 tests ... OK` |
| `openspec validate --all --strict` | 3 passed, 0 failed |
| 测试现场清理 | `git worktree list` 仅剩主仓库；`agent/*` 分支为空；无 `PING` 残留；`G:\java\jset-agents` 为空 |

### 六、如实说明的局限

1. 本轮全部是**工具链与文档**结论，不改变任何运行时行为（`src/`、`python/`、`tests/` 未触碰）。
2. 后台并发的实测用的是模拟 codex 的假会话（真实 codex 会话每次数分钟，
   做并发对照成本过高）。隔离性结论来自 Git 机制本身，与用哪种 codex 无关；
   但「两个真实 codex 会话同时跑」的端到端时长未测。
3. 「主 agent 不能自行派发」是在**当前沙箱配置**下的结论。若日后放行
   `.git\refs\heads\agent\**`、`.git\logs\**` 与 `G:\java\jset-agents\`，该结论会失效——
   已把所需权限与代价一并记录在运行手册「已知限制」第 5 条。
4. 单终端并发仍然受「最多两个执行角色」的人工约束限制，这是纪律而非机械强制。

## 2026-09-21（逐条派发：实际工作流与一条新实测约束）

回答「如何逐条派发角色」，并补上过程中发现的一条**此前文档未记录**的约束。

### 一、一条关键约束：新派的角色看不到前一个角色的产出（实测）

`git worktree add` 从**当前 HEAD** 建工作区。实测三步：

| 步骤 | 结果 |
| --- | --- |
| 在 `agent/exploit/dep-a` 里提交 `src/payload/PayloadEngine.java` | 提交成功 |
| 立即从 main 派发下一个角色 | 新 worktree 里**看不到**该文件 |
| 先 `git merge --no-ff agent/exploit/dep-a` 再派 | 新 worktree 里**能看到** |

**推论**：派发的顺序不是「随便排」——**有依赖关系的任务必须先合并前置分支再派**。
这条直接决定了 `payload-generation` 的派发次序：第 4、5 组（改 `tests/`）依赖第 1–3 组建出的新包，
所以**不能**与第 1–3 组并发，必须等第 1–3 组合并后再派测试角色。

（模拟用的分支与合并已全部撤销，`main` 回到 `7c58493`，无残留 worktree 与分支。）

### 二、逐条派发的四条命令

一个 change 的完整流程是四步，每条命令只派一个角色：

```powershell
# 第 1 条：主 agent 产出 change（已有 change 则跳过）
.\tools\agent.ps1 -Role orchestrator -Slug payload-plan `
  -Task "用 OpenSpec propose 产出 payload 生成的 change 规划件" -TimeoutMinutes 15

# 第 2 条：执行角色（tasks.md 每组都标了写入域与角色）
.\tools\agent.ps1 -Role exploit -Slug payload-core `
  -Task "按 openspec/changes/payload-generation/tasks.md 第 1-3 组实现" -TimeoutMinutes 40

# 第 3 条：测试角色（此时前置已合并，它能看到新包）
.\tools\agent.ps1 -Role test -Slug payload-checks `
  -Task "按 tasks.md 第 4-6 组补齐自检与回归" -TimeoutMinutes 40

# 第 4 条：监督只读复核
.\tools\agent.ps1 -Role supervisor -Slug audit-payload `
  -Task "按十项清单复核本轮改动，给出阻断结论" -TimeoutMinutes 60
```

每条命令之间的**人工动作只有三件**：读结果、合并、测关。

### 三、一条命令内部发生什么

| 阶段 | 该角色做什么 | 人需要做什么 |
| --- | --- | --- |
| 启动 | 建 worktree 与分支，套角色卡 | 无 |
| 运行 | 改代码，不自己提交 | 可等待，或读实时日志看进度 |
| 静默时 | 看护 agent 读日志判三态 | 无（自动） |
| 退出 | 脚本在沙箱外**校验写入域后提交** | 看「agent 改动的文件」清单，确认无越界 |
| 结束后 | — | 合并、测关、清理、派下一条 |

写入域校验是硬门禁：只要有一个文件不在该角色写入域内，脚本就整体撤回暂存、拒绝提交并列出越界文件。

### 四、文档更新

| 文件 | 更新 |
| --- | --- |
| `docs/AGENT-RUNBOOK.md` | 新增「四之二、逐条派发（实际工作流）」：依赖约束、四条命令、单条命令内部分解、合并与清理 |
| `docs/AGENT-RUNBOOK.md` 结论速览 | 补两行：「新派角色能否看到前置产出」「哪些必须串行」补上「有依赖关系的任务」 |

### 五、当前可立即派发的状态

`openspec/changes/payload-generation` 规划件已就绪（4/4 artifacts complete，36 个任务、8 个组），
`openspec status` 提示下一步是 `openspec instructions apply --change payload-generation`。
仓库干净：无 worktree、无 `agent/*` 分支、无残留进程。

按依赖关系，**第一条真实派发命令**应是第 2 条（执行角色 exploit），
因为规划件已由主 agent 产出并合并。

### 六、如实说明的局限

1. 依赖约束的实测用的是「模拟提交一个文件」而非真实跑完一个角色的全部产出；
   但该约束来自 `git worktree add` 从 HEAD 建工作区这一机制本身，与产出内容无关。
2. 本轮为文档与实测，不改任何运行时行为（`src/`、`python/`、`tests/` 未触碰）。
3. 「有依赖关系的任务必须串行」这条目前靠人判断（读 tasks.md 的写入域与依赖描述），
   没有机械强制。若日后要自动化，需要把依赖关系写成 task 间的显式字段。
## 2026-09-21（主 agent 自动拆解编排：一句话目标 → 分批派发 → 自动合并 → 汇报）

回答「主 agent 能否把任务自动拆解并分配给不同角色，配合 OpenSpec，最后汇报完成情况」。

**结论**：能，但形态是**沙箱外的编排器** `tools/orchestrate.ps1`，不是让主 agent 自己当调度器。
原因是主 agent 的沙箱建不了 worktree（前一轮实测：无法写 `.git/refs/heads/**`，
`git worktree add` 报 `cannot lock ref`）。因此把「拆解」交给一次只读 LLM 会话，
「派发与合并」交给在沙箱外运行的脚本。这样既拿到自动拆解，又保住原有的隔离边界。

### 一、四步流程

| 阶段 | 动作 | 设计依据 |
| --- | --- | --- |
| 1 拆解 | 只读会话，把角色矩阵（写入域）喂给 LLM，要求只输出 JSON 计划（role/slug/task/dependsOn） | 角色由矩阵限定，模型无法凭空发明角色 |
| 2 校验 | 角色存在、slug 合规且唯一、依赖指向计划内 slug、步数 ≤ `-MaxSteps`、依赖不成环 | 校验不过**不创建任何 worktree**，零副作用 |
| 3 执行 | 按「依赖 + 写入域冲突」分批；同批先**串行预建 worktree** 再并行启动 | 并发 `git worktree add` 会争用 `.git/worktrees` 与 refs |
| 4 汇报 | 逐步列出「已合并 / 无需改动 / 需人工处理」+ 提交范围，写入 `%TEMP%\jset-orchestrate-<runId>.md` | 人只需读表，不必翻日志 |

分批依据是 `tools/lib/RoleMatrix.ps1` 的 `Test-WriteScopeConflict`：
**证不出不相交就串行**（宁可慢，不可撞）。

### 二、新增与改动的文件

| 文件 | 性质 | 说明 |
| --- | --- | --- |
| `tools/orchestrate.ps1` | 新增 | 编排器本体：拆解、校验、分批、执行、汇报 |
| `tools/dispatch.ps1` | 新增 | 单条派发：推断角色 → 跑 agent → 判定 → 合并或保留现场 → 清理 |
| `tools/lib/RoleMatrix.ps1` | 新增 | 写入域矩阵、超时预算、只读角色、冲突判定；与 `docs/AGENT-ROLES.md` 一一对应 |
| `tools/lib/AgentRun.ps1` | 新增 | `Invoke-GitOn`、`Test-RepoReadyForMerge`、`Get-MergeDecision`、`Remove-AgentWorkspace`、`New-AgentWorktree` |
| `tools/agent.ps1` | 改 | 新增 `-ResultFile`（机器可读结果）与退出码语义；改为引用共享矩阵 |
| `tools/check_agent_tools.ps1` | 改 | 39 项扩展到 **77 项**，新增「角色矩阵完整性」整节 |

### 三、修掉的两个真实缺陷

**缺陷 A：`return ,@(...)` 让两条防呆分支变成死代码（严重）**

`Resolve-Role` 用 `return ,@($hits)` 返回单元素数组，调用方习惯性写成 `@(Resolve-Role ...)` 之后，
整个数组被当成一个元素，`$hits.Count` **恒为 1**。后果：

- 「任务无任何关键词」→ 不报错，静默拿到**空角色**继续执行；
- 「任务同时命中多个角色」→ 不报错，按首个命中角色派发（写入域猜错即白跑或越界）。

实测证据（`tools\dispatch.ps1 -Task "写一首诗赞颂春天" -DryRun`）：
修复前打印 `角色 : （按任务内容推断）`、`slug : -0921-1536` 并继续；
修复后报「无法从任务内容推断角色」。多角色任务同样修复。
根因是 PowerShell 的数组展开语义：单元素数组在 `return` 时会被展开，加逗号可阻止展开，
但那样整个数组又成了单个元素——正确写法是 `return @(...)`，由调用方 `@()` 包裹。

**缺陷 B：共享内核没有任何角色可写**

实测逐文件核对写入域，`src/config/AppConfig.java`、`src/util/*.java`、`src/pom.xml`
**不在任何角色的写入域内**。但 `openspec/config.yaml` 规定
「`src/config/**` 与 `src/util/**` 是共享内核，任何角色不得直接改，**需主 agent 单独开 change**」，
`docs/AGENT-ROLES.md` 也要求主 agent「审议共享内核的改动」。
规则与机械校验互相矛盾：主 agent 即便开了 change 也提交不了。
后果是真实拆解出的
`orchestrator/payload-config-keys` 步骤（改 `AppConfig.java`）会被拒提交（退出码 3）。

修法：`orchestrator` 写入域补 `src/config/*`、`src/util/*`、`src/pom.xml`，
并在 `tools/check_agent_tools.ps1` 固化断言「每个受管源文件都有且仅有一个角色可写」
与「共享内核归主 agent」，防止回归。同步更新了四份文档的说法。

### 四、端到端实测

在**临时克隆副本**上跑（避免污染主仓库历史），用一个假 codex 替代真实 LLM：
计划 4 步 3 批，`probe` ∥ `traffic` → `test` → `supervisor`。

| 观测项 | 结果 |
| --- | --- |
| 分批 | 4 步分成 3 批，同批两角色并行启动 |
| 合并 | 3 个执行步全部自动合并，各自 `--no-ff` 产生合并提交 |
| 监督步 | 在主仓库以 read-only 运行，退出码 0，不建 worktree、不提交 |
| 清理 | 结束后 `git worktree list` 只剩主仓库，`agent/*` 分支为空 |
| 主仓库 | 结束后工作区干净 |
| 汇报 | 逐步表格 + 汇总「已合并 3 / 无需改动 0 / 需人工处理 0」 |
| 外层退出码 | 0（`-SkipSupervisor` 与带监督两种跑法都是 0） |

### 五、自动合并的安全边界（不为了跑完而合并）

仅当以下条件**全部**满足才自动合并：agent 正常退出且未超时、看护未判卡死、
改动全部落在写入域内、分支上确有新提交。任一不满足即记「需人工处理」并**保留分支与 worktree**；
合并冲突时执行 `git merge --abort`，不留半个合并状态。
收尾三项（备份 / `AI_REPORT.md` / `PROGRESS.md` / 构建校验）**不在编排器里做**，
仍由主 agent 合并后统一执行——它们是全仓库共享资源，并发期间碰会互相覆盖。

### 六、遗留的两处：其一当场修掉，其二如实说明

**1）`dispatch.ps1` 自带的重复实现（本轮修掉）**
它原先自带一份 `Invoke-GitOption` 与一套合并判定，与 `tools/lib/AgentRun.ps1` 重复，
等于存在两套可能漂移的安全策略。已改为直接引用共享库，并把 `Test-RepoReadyForMerge`
与 `Get-MergeDecision` 统一到一处。自检加了「不许出现重复实现」的断言。

**2）退出码语义此前不完整**
越界写入返回 0，自动化无法与「正常完成」区分。已改为：
0 成功、3 越界拒提交、4 提交失败、5 `git add` 失败、124 超时，并在自检里固化。

### 七、当前可立即使用的形态

```powershell
# 只拆解看计划
.\tools\orchestrate.ps1 -Goal "补齐 fastjson 1.2.73-1.2.80 的版本识别盲区" -DryRun

# 真跑：拆解 → 分批 → 派发 → 合并 → 汇报
.\tools\orchestrate.ps1 -Goal "补齐 fastjson 1.2.73-1.2.80 的版本识别盲区"
```

至此，用户期望的工作形态已达成：**人只提出任务 → 编排器拆解派发 → 人做测试与验收 → 再提建议**。

## 2026-09-21（派发汇报：列出调用了哪些角色、各自做了什么）

回答「每次任务结束，主 agent 汇报中加上调用了哪些 agent 角色、做了哪些主要事情」。

### 一、汇报为什么不能只给逐步表格

原来的汇报只有一张「步 / 角色 / 状态 / 说明 / 提交」的表格。并行批次里步骤序号不代表角色分工，
要回答「这次用了哪些角色、各自做了什么」，得从表格里自己反推；
而且表格里没有「这一步改了哪些文件」，只能靠提交信息猜。这次把这两件事补成汇报的固定段落。

### 二、汇报新增的三段

| 段落 | 内容 |
| --- | --- |
| 本次调用的角色 | 实际启动的角色清单与各自占用的步号，如「**probe**：1 步（第 1 步）」 |
| 各角色主要工作 | 每个角色：该步任务描述 + 该步实际改动的文件（`产出文件：python/fj_probe.py`） |
| 汇总 | 增加一行「调用角色数：3（probe、traffic、test）」，逐步表格新增「主要工作」列 |

单条派发 `tools/dispatch.ps1` 结束时的收尾汇报同样加了角色、主要工作、产出文件三项。

### 三、过程中修掉的一个真实缺陷：产出文件串号

首次实测（3 步 2 批：`probe` ∥ `traffic` → `test`）发现 `traffic` 的产出文件清单里
出现了 `probe` 的 `python/fj_probe.py`。

**根因**：并行批次里各步共用批次基线，产出文件按「批次基线..合并后的新 HEAD」取值。
同批两角色先后合并，后合并的那次 diff 天然包含先合并步骤的文件，于是被算进了自己的产出。

**修法**：改为对比**合并提交的一号父提交到该合并提交**（`<merge>^1..<merge>`），
得到的恰好是这次合并带进来的文件。实测修正后三步文件清单互不串扰：

| 步骤 | 产出文件（修正后） |
| --- | --- |
| probe | `python/fj_probe.py` |
| traffic | `src/proxy/ProxyServer.java` |
| test | `tests/test_probe.py` |

### 四、实测证据

在临时克隆副本上用假 codex 跑通编排器与单条派发两条路径（外层退出码均为 0）：

- 编排器：汇报含「本次调用的角色」「各角色主要工作」两节，三角色各 1 步，产出文件各自正确；
- 单条派发：打印「主 agent 本次调用角色：probe / 该角色主要工作：… / 产出文件（1 个）：python/fj_probe.py」。

### 五、断言与回归

`tools/check_agent_tools.ps1` 由 77 项扩展到 **84 项**，新增一节「汇报必须写明角色与产出」，
覆盖：按角色聚合、两个汇报标题、产出文件记录、并行句柄携带任务描述、派发收尾汇报，
以及产出文件的取值区间必须是 `<merge>^1..<merge>`。

五条新断言逐条做了**变体验证**（故意破坏实现后必须报失败），全部按预期报 FAIL；
其中「按角色聚合」一条首版只匹配函数名，删掉调用点后仍为 PASS（假通过），
已收紧为匹配调用表达式，再次验证可报失败。

### 六、回归结果

| 项目 | 结果 |
| --- | --- |
| 工具链自检 | 84 项全过 |
| Python 单测 | Ran 99 tests，OK |
| 五套 Java 自检 | 全部 exit 0 |
| `openspec validate --all --strict` | 4 passed, 0 failed |
| 构建 | JAR 16:03:12，无源文件晚于它 |

### 七、如实说明的局限

1. 本次改动全部在 `tools/` 与文档，未触碰 `src/`、`python/`、`tests/` 的运行时代码，
   因此 JAR 内容与上一轮一致（186414 字节），变的只是构建时间。
2. 「各角色主要工作」引用的是计划里的任务描述，即**交给角色做什么**；
   角色实际完成到什么程度仍以状态、提交与产出文件为准，不宣称任务描述等于实际结果。
3. 产出文件清单最多列 6 个，超出显示为「等 N 个」，避免单步产出过多时刷屏。


## 2026-09-21（本轮：汇报角色中文化 + JavaChains 可视化落地）

### 一、需求

1. 汇报里的 agent 角色名改成中文（主 agent、测试 agent 这种），而不是 `probe` / `traffic` 标识；
2. 把 JavaChains（java-chains 利用链）集成为可视化功能，用于生成各类 Java 漏洞的利用链 payload。

### 二、角色名中文化

- `tools/lib/RoleMatrix.ps1`：新增 `Get-RoleDisplayNames` / `Get-RoleDisplayName`，映射
  `orchestrator=主 agent`、`probe=探测 agent`、`exploit=利用链 agent`、`traffic=抓包 agent`、
  `ui=界面 agent`、`test=测试 agent`、`supervisor=监督 agent`、`watchdog=看护 agent`；
  未登记的角色原样返回标识，避免漏登记导致汇报丢信息。
- `tools/orchestrate.ps1`：聚合时记下显示名，「本次调用的角色」「各角色主要工作」两节的标题、
  逐步表格的角色列、汇总行改为 `显示名（标识）`。
- `tools/dispatch.ps1`：开头的「角色」行与收尾汇报改用显示名。

设计取舍：**角色标识仍用于分支名、写入域与 `-Role` 参数**，显示名只用于面向人的输出。
两者混用会让 `-Role "探测 agent"` 这类参数被校验直接拒绝，因此显示名一律写成 `显示名（标识）`。

实测：`tools\dispatch.ps1 -Task "补齐 fastjson 版本识别盲区" -DryRun` 打印
`角色    : 探测 agent（probe，按任务内容推断）`。

### 三、JavaChains 可视化

新增 `Payload` 一级导航（二级项 `Payload 生成`），把 java-chains 的「载体 + gadget 节点」
能力做成可视化界面。核心设计决定如下。

#### 3.1 通用引擎下沉到独立包（解耦）

新建 `src/payload/` 三个类，与漏洞类型无关：

| 类 | 职责 |
| --- | --- |
| `PayloadEngine` | 初始化与就绪状态、节点目录、载体目录、参数查询、后继节点、参数归一化、构建 |
| `PayloadCatalog` | 7 组载体分组常量表，并给出与运行时目录的偏差清单 |
| `PayloadResult` | 结果模型：成功标志、失败原因、Base64、原始字节、不含正文的摘要 |

`src/shiro/ChainsEngine.java` 的通用实现全部委派给 `PayloadEngine`，**公开方法签名与
`Generated` 嵌套类型保持不变**，因此 `ShiroExploit` / `Main` / `ShiroCheck` 零改动。
这样拆的理由：载体枚举、节点导航与载荷构建与漏洞类型无关；留在 Shiro 模块里，
其它功能无法复用，也会让「通用组件依赖具体功能模块」的反向耦合长期存在。

#### 3.2 首节点判据用引擎自己的规则

实测发现 `nextNodes(载体)` 返回**空集**——载体自身没有后继，后继是从 gadget 才开始。
因此 `firstNodes(payloadId)` 的实现是：把 `[载体, 候选节点]` 逐个交给
`ExecutionEngine.validateChainTags` 校验，通过的才算可选首节点。
这是引擎自己的判据，不是本地另写一套链规则（后者只能证明「自检和实现一致」）。

#### 3.3 界面层

- `src/ui/PayloadPage.java`：页面结构（分组 → 载体 → 链 → 参数 → 输出三段式）；
- `src/ui/PayloadController.java`：链状态机 + 引擎调用 + 复制 / 导出 / 填入抓包页；
  参数行随链重建时会先把已填的值取回来再写回去，避免追加第二个节点把前一个的命令清空；
- `src/Main.java`：只做接线（导航项、分页调度、控件持有、配置注入）。
  控制器懒创建：它持有当前链与已生成载荷，每次进页都重建会把使用者刚配好的链清空。

#### 3.4 配置落进配置页

新增独立分组 `Payload 生成配置` → `默认导出目录`（配置键 `payload_export_dir`），
读写接入既有的 `applyConfigToForms` / `resetConfigForm` / `saveConfigFromForm` 三处。
（首版误将该行插进了「Shiro 配置」组内且缩进错乱，已修正为独立分组。）

### 四、实测数据（java-chains 2.0.0-beta4）

| 项 | 实测值 |
| --- | --- |
| 节点总数 | 429 |
| 载体总数 | 28 |
| `javanativepayload` 可选首节点 | 104 |
| `fastjsonpayload` 可选首节点 | 46 |
| `shiropayload` 可选首节点 | 102 |
| `PayloadCatalog.diff(runtime)` | `[]`（分组与运行时零偏差） |
| 全量 `firstNodes` 遍历耗时 | 71 ms |
| 完整链 `javanativepayload + clojure` | 成功，952 字节 |
| 四节点链 `shiropayload + clojure+templatesimpl+bytecodeconvert+tomcatecho` | 成功，976 字节 |
| 空链 | 失败，`请至少追加一个 gadget 节点：载体自身不是完整利用链。` |
| 非法载体 | 失败，带原因且不带载荷 |
| 参数键形态 | `Exec.cmd` / `Clojure.cmd` / `ShiroPayload.shiroKey`（完整键） |
| 有参数的节点 | 235 个，参数合计 421 条 |

### 五、修掉的两个真实缺陷（先给根因）

1. **Payload 页输出区被压成 62px**
   - 现象：`UiNavigationCheck` 断言「输出区有可用高度」失败，实测 viewport 仅 62px。
   - 根因：`JSplitPane` 首次布局按首选尺寸 + `resizeWeight` 分配；`ShiroPage` 的
     `outputPanel` 设了 `setMinimumSize(new Dimension(0, 170))`，`PayloadPage` 的漏了，
     表单（`FORM_HEIGHT=340` + 按钮行）把输出区挤成一条细线。
   - 修法：给 `PayloadPage.outputPanel` 也设最小高度，并按实际可读性给到 `240`
     （170px 只够三行等宽文本，Base64 完全看不全）。实测修后 164px。
2. **`tools/audit_boundary.py` 与 `tests/test_decoupling.py` 的规则不同步**
   - 现象：Python 单测改配置后全过，但 `audit_boundary.py` 仍报「存在违规」。
   - 根因：两份规则表是各自硬编码的副本，新增 `payload` 包时只改了一份；
     审计工具是监督角色独立复核用的，一旦与测试文件口径不一致，复核结论就会失真。
   - 修法：同步 `ALLOWED_EDGES` / `UP_LAYERS` / `GENERIC_MODULES`，
     并把「具体功能模块类名」抽成一份 `FEATURE_MODULE_CLASSES` 供三个通用组件共用。

### 六、新增与扩展的断言

**工具链自检新增 7 项（84 → 91）**，针对角色名中文化：矩阵提供显示名、
全部角色都有显示名、显示名均含中文、显示名不等于标识、未登记角色回退为标识、
派发汇报用显示名、编排汇报用显示名。

其中两项做了**变体验证**（故意破坏实现后必须报失败）：

| 变体 | 预期报失败的断言 | 实测 |
| --- | --- | --- |
| `test = "\u6d4b\u8bd5 agent"` 改成 `test = "test"` | 显示名均含中文、显示名不等于标识 | 2/91 失败✓ |
| 删掉 `dispatch.ps1` 中的 `Get-RoleDisplayName` | 派发汇报用显示名 | 1/91 失败✓ |

首版写成 `-notmatch '[A-Za-z]'` 对中文名恒为真（假通过）；另有一版在
`$dispatchCode` / `$orchCode` 赋值之前引用它们，拿到 `$null` 导致断言恒假。
两处都是靠变体验证才发现的，已修正为拟合中文范围与直接读文件。

- **新增 `tests/PayloadCheck.java`，61 条断言**，分七类：目录（载体 28 / 节点 >400 / 去重）、
  分组（并集 = 目录、无重复登记、反查稳定）、导航（后继稳定、`templatesimpl → bytecodeconvert`、
  首节点逐个通过链校验）、逐个载体创建（**要求「结论确定」而不是「全部成功」**：需要回连地址的
  载体在没给参数时应当带原因地失败）、失败路径、双形态往返（Base64 解码长度与首尾 4 字节一致）、
  安全（摘要不含正文任意连续 16 字符、不写回上游参数声明、连续构建后工作区无新增文件）。
- **扩展 `tests/UiNavigationCheck.java`**：导航项数断言随新增一级分类调整
  （6 / 7 / 10 / 11），新增配置页 `Payload 生成配置` 分组断言，以及 Payload 页的
  17 条控件断言 + 端到端断言（追加 `clojure` → 生成成功 → 输出含 Base64/链序/摘要 →
  清空链后填入抓包页 → 跳转到抓包转换）。
- **`tests/test_decoupling.py` 与 `tools/audit_boundary.py`**：允许边新增 `payload`（只允许依赖 `util`）、
  `shiro` 增加允许依赖 `payload`、`ui` 增加允许依赖 `payload`、`<default>` 增加 `payload`。

### 七、回归结果

| 项目 | 结果 |
| --- | --- |
| 工具链自检 | **91 项全过**（本轮由 84 扩展到 91） |
| Python 单测 | Ran 99 tests，OK |
| 依赖边界审计 | 全部通过（含三个通用组件各自无功能模块引用） |
| `openspec validate --all --strict` | 4 passed, 0 failed |
| 构建 | JAR 16:46:43，无源文件晚于它（37 个源文件逐个校验） |
| `PayloadCheck` | 61 条断言，失败 0，exit 0 |
| `ShiroCheck` | exit 0 |
| `UiNavigationCheck` | 全部界面自检通过，exit 0 |
| `UiShiroCheck` | exit 0 |
| `UiSwitchEndToEndCheck` | exit 0 |
| `ProxyServerCheck` | exit 0 |

`UiShiroCheck` 首次运行为退出码 1，原因是漏带 `--add-opens`：报
`IllegalAccessError: ... BytecodeConvert ... cannot access ... TemplatesImpl`。
按 `run.ps1` 的两个 `--add-opens` 重跑后 exit 0 —— 这是运行方式问题，不是代码缺陷。
README 中已把「`ShiroCheck` / `PayloadCheck` 需要这两个参数」写进测试章节。

### 八、文档同步

- `README.md` / `README.en.md`：导航新增 `Payload` 一级分类、配置页新增
  `Payload 生成配置` 分组、项目结构新增 `src/payload/`、新增「Payload 生成」章节、
  测试章节补 `PayloadCheck` 与 `--add-opens` 说明 —— 中英同步。
- `PROGRESS.md`：新增三行状态与一条已完成阶段。
- `docs/AGENT-ROLES.md`：新增「1.1 角色标识与中文显示名」对照表与两套名字的用途边界。
- `docs/AGENT-RUNBOOK.md`：汇报段落说明补上显示名形式与两套名字的用途对照表。
- `docs/DESIGN-payload.md`：实施顺序逐步标记完成，新增九节「实现期间的实测发现与原设计的差异」。
- `tools/check_agent_tools.ps1`：新增 7 项角色名断言（84 → 91）。

### 九、如实说明的局限

1. 逐个载体「不带参数直接构建」时只有 1 个成功、27 个被拒绝，**这是上游的必填参数约束**，
   不是实现缺陷。自检因此断言「结论确定」而非「全部成功」；界面会在追加节点后把必填参数
   渲染出来，由使用者填写后再生成。
2. 上游对 JNDI / SSRF 一类节点自带了示例地址（`ldap://127.0.0.1:1389/x`、
   `xxx.dnslog.cn`）。本工具**不替使用者改写或补全**这些值，也不在构建时自动填入；
   需要什么回连地址由使用者自己决定。
3. 链的**可用性**取决于目标依赖版本，本工具只保证「引擎认可这条链并能构建出载荷」，
   不保证目标一定触发。
4. `firstNodes` 是逐个候选做链校验，104 个候选约 0.3 ms/候选（全量 28 个载体 71 ms）。
   当前规模下可接受；若上游节点数大幅增长，需要缓存。
5. `Main.java` 仍为 1500 行量级 —— 本轮按「最小改动」只做接线，模块化仍按
   `docs/DESIGN-modularization.md` 的方案留待专门一轮实施。

## 2026-09-21（本轮：恶意服务器 + 预设链 + 载荷编码缺陷修复）

### 一、需求

1. UI 改造成接近网页版 JavaChains（左侧导航含 Generate / JNDI / FakeMySQL / RMI-JRMP / TCP / HTTP Server 等）；
2. 新增**恶意服务器**功能（JNDI / HTTP / TCP / FakeMySQL / JRMP 监听与载荷发布）；
3. **主窗口默认全屏**；
4. 参考 JavaChains 源码设计，无可参考则自己设计；
5. 用户已明确：如需 Spring 可引入 SpringMVC。

### 二、先实测再动手：不需要 Spring（结论有据）

按「机制性问题必须实测」的要求，先验证上游服务端适配器能否在纯 JDK 17 下驱动，再决定是否引入 Spring：

| 实测项 | 结论 |
| --- | --- |
| `ServiceLifecycleService` + `ProtocolRuntimeRegistry` 直接 `new` 组装 | 可运行，五类服务全部真实绑定端口 |
| LDAP 端口取回已发布对象 | 成功（真实客户端拉取） |
| 是否需要 Spring / Servlet 容器 | **不需要**，上游适配器是纯 JDK 实现 |

因此**未引入 Spring**（用户授权的是「如需要」），`src/pom.xml` 一个依赖都没加，符合仓库
「禁止新增第三方依赖」的硬约束。

### 三、上游真实约束（决定了实现写法，均为实测）

1. **端口键**：JNDI 用 `ldap` / `rmi` / `http` / `ldaps`；HTTP / FakeMySQL / JRMP / TCP **必须**用
   `main`，写成别的键会被绑定层直接拒绝。
2. **LDAPS 的坑（已修的真实缺陷）**：上游要求「启用 LDAPS 就必须同时给出 JKS 证书路径」，
   默认下发该端口会让**整个 JNDI 启动被拒**（`invalid start configuration`）。
   改为「可选端口」（`PortSpec.optional`），未显式填写就不下发。
3. **载荷类型**：JRMP 只收 `OBJECT`；JNDI / FakeMySQL 收 `OBJECT` / `BYTES` / `COMPOUND`（**不收 TEXT**）；
   HTTP / TCP 收 `BYTES` / `TEXT`。实现按协议挑类型，类型不符返回可读失败结论。
4. **发布入口**：三参 `publish(...)` 必然被拒
   （`SCOPE_FORBIDDEN: runtime:control is required when autoStart=true`）；
   四参版本可用，但绕开 autoStart 与载荷构建契约层的
   `ProtocolRuntimePort.putPublication(PutPublicationCommand)` 更稳，本次采用后者。
5. **地址拼装**：上游 `output` 对 JNDI 不含地址、对 TCP / JRMP 只有占位符，
   `ServiceManager.addressOf` 按协议补出可复制的真实地址。
6. **预设数据**：内置 `default-chains.yaml` 实测 **52 条、7 个分类**；
   预设里载体与节点名是大驼峰、引擎按小写注册，生成时必须转小写。

### 四、修复的真实缺陷：载荷双重 Base64 编码（先给根因）

**现象**：`ShiroCheck` 的「生成的 payload 可被本机密钥解密」失败，`UiShiroCheck` 同样失败。

**根因**（不是试错，是逐行追出来的）：上一轮把 `PayloadEngine.build` 拆成
「`buildRaw` 构建 → `build` 汇总」时，`buildRaw` 对 `String` 形态产物做了 `getBytes(UTF_8)`，
于是 `build` 里的判断

```java
if (raw.bytes.length > 0) return PayloadResult.ok(payloadId.trim(), chain, raw.bytes);
```

**恒真**，永远走字节分支；而 `PayloadResult.ok` 会再做一次 `Codec.base64(bytes)`。
Shiro 载体产出的本来就是 Base64 文本，被当成「待编码字节」又编码一次，
`decodeBase64` → `decrypt` 自然解不出来。

**修复**：`build` 先判对象是否本就是 `String`，是则直接走 `okText`（按文本原样交付）；
同时把 `buildRaw` 的文本转字节改为「先 Base64 解码、失败再按 UTF-8」，
使 `buildRaw.bytes` 与 `PayloadResult.okText.bytes` 指向同一份载荷字节。

**回归**：`ShiroCheck`、`UiShiroCheck` 均恢复退出码 0。

### 五、代码改动

新增（功能开发 exploit 写入域）：

| 路径 | 职责 |
| --- | --- |
| `src/service/ServiceSpec.java` | 五类服务的静态描述（含可选端口） |
| `src/service/ServiceEndpoint.java` | 服务状态纯数据 |
| `src/service/PublicationResult.java` | 发布结果纯数据 |
| `src/service/ServiceDefaults.java` | 默认监听参数纯数据（端口键用「服务标识.端口键」） |
| `src/service/ServiceManager.java` | **唯一**直接调用 java-chains 服务端适配器的类 |
| `src/preset/PresetItem.java` | 预设链纯数据 |
| `src/preset/PresetCatalogService.java` | **唯一**引用上游预设模型的类，失败返回空清单 |

新增（界面 agent 写入域）：`src/ui/ChainEditor.java`（两页共用的链编辑状态）、
`src/ui/ServicePage.java` / `ServiceController.java`、`src/ui/PresetPage.java` / `PresetController.java`。

修改：`src/Main.java`（导航重组为 主页 / Payload / 服务 / 代理 / FastJson / 配置；默认最大化；
新页装配；配置页新增两个分组；退出前停服务）、`src/payload/PayloadEngine.java`（缺陷修复 + 原始对象入口）、
`src/ui/PayloadController.java`（改用 `ChainEditor`）、`src/ui/UiKit.java`、`src/ui/ServicePage.java`
（删除未接线的死字段 `timeoutSeconds`）。

### 六、实测结果

| 验证项 | 结果 |
| --- | --- |
| Java 编译（41 个源文件，`--release 8`） | 通过 |
| `ShiroCheck` | 退出码 0 |
| `PayloadCheck` | 61 项，失败 0 |
| `ProxyServerCheck` | 退出码 0 |
| `UiNavigationCheck` | 全部通过（新增预设链页与服务页断言） |
| `UiShiroCheck` | 退出码 0 |
| `UiSwitchEndToEndCheck` | 退出码 0 |
| Python 单测 | 99 项 OK |
| `tools/audit_boundary.py` | 结论「全部通过」 |
| `tools/check_agent_tools.ps1` | 91 项通过 |
| `openspec validate --all --strict` | 5 项全 passed |
| `.\build.ps1` | JAR 时间 `2026-09-21 18:00:52`，277540 字节，50 个源文件校验通过 |

端到端实测（在配置里写入自定义值后进入服务页）：绑定地址 `0.0.0.0`、公布地址 `10.1.2.3`、
JNDI LDAP `51111`、HTTP 服务端口 `59999` 均按配置生效，且未配置的端口（RMI/HTTP）保持默认——
证明「配置页 → 服务页」的下发链路真实可用，不是只在界面上显示。

### 七、界面自检的一处加固

`UiNavigationCheck` 原来把导航项数与索引写死（`size() == 6`、`labels().get(2)` 之类），
导航顺序一改就集体失效，且失败信息只说明「数字不对」，看不出真正原因。
改为 `expectedNavSize()`：读 `NAV_ITEMS` 的展开状态**现算**应有项数，
断言因此变成对「`rebuildNavigation` 是否补全每个已展开分组的子项」的真实校验。
另外新增前置断言明确报出「java-chains 引擎未就绪（需 `--add-opens`）」——
此前缺参数会表现为一连串「生成失败」，掩盖了运行方式问题。

### 八、多 Agent 协作

| 角色 | 主要工作 |
| --- | --- |
| 主 agent | 需求拆解与实测调研、缺陷根因定位、依赖边界契约与角色矩阵同步、OpenSpec 规划件、构建与收尾 |
| 利用链 agent（exploit） | `src/service/**`、`src/preset/**`、载荷引擎缺陷修复 |
| 界面 agent（ui） | 三个页面与 `Main.java` 装配、导航重组、默认最大化、配置页新分组 |
| 测试 agent（test） | `UiNavigationCheck` 断言重写与新增、边界规则同步 |

### 九、如实说明的局限

1. 界面自检需要 `--add-opens`（预设链首个内置链含字节码类 gadget）；缺参数时已由前置断言
   明确报出，但**必须按 `run.ps1` 的方式运行**才能全绿。
2. 恶意服务器只做「监听 + 发布」，**不主动向目标发送载荷**。
3. 预设里出现分叉的链仍按线性顺序构建，「分支型链」尚未暴露到界面。
4. LDAPS 未内置 JKS 证书，需 HTTPS 回调时使用者要自行准备证书后再启用该端口。
5. `Main.java` 现约 1600 行，模块化仍按 `docs/DESIGN-modularization.md` 留待专门一轮。

---

## 2026-09-21（本轮：执行 Java 模块化，拆开 Main.java）

### 一、任务

用户指令「执行模块化」——按 `docs/DESIGN-modularization.md` 的既定方案拆分 `src/Main.java`。

### 二、改动前的实测基线

| 指标 | 改动前 |
| --- | ---: |
| `Main.java` 行数 | 1757 |
| `Main.java` 方法数 | 66（方法体合计 1167 行） |
| `Main.java` 字段数 | 148（其中 `private final` 约 100） |
| 自检对 `Main` 的反射面 | 94 个名字、约 132 处字段访问 + 3 个方法调用 |
| 最大单文件 | `Main.java` 1757 行 |

实测确认了设计文档预判的硬约束：三个 UI 自检用
`target.getClass().getDeclaredField(name)` 取控件（只查本类、不含父类），
因此「拆分」与「自检通过」在改动前是对立的。

### 三、根因

不是「代码写得乱」，而是两条约束互相锁死：

1. **控制器缺位**：`src/ui/*Page.java` 早已是静态视图构建器，
   但状态与行为没有对应去处，只能堆在组合根。
2. **自检按字段名反射**：内部结构被测试当成契约，字段一搬断言就崩。

### 四、处置（先修测试取数方式，再拆分）

**第一步：稳定门面解除锁死。**
新增 `src/ui/UiHandle.java`（先查登记表、再沿继承链反射兜底）与
`src/ui/WidgetRegistry.java`（90 余项控件登记清单，导航序列用取值器避免快照过期）。
三个 UI 自检的 `fieldQuiet` / `read` / `hasDeclaredField` 各改一行函数体，
**132 处字段访问与 3 个方法调用的断言文字、数量一条未改**。

**第二步：视图侧控件工厂。**
`ProbePage` / `CapturePage` / `ShiroPage` / `ProxyPage` 各新增 `defaults()`；
新增 `ConfigForm`（38 项控件 + 10 个分组清单）；`ConfigPage` 新增 `widgets(...)` 装配助手。

**第三步：行为搬到控制器。** 新增 10 个类，行数如下：

| 文件 | 行数 | 职责 |
| --- | ---: | --- |
| `src/ui/NavController.java` | 203 | 侧边栏构建、展开收起、选中、路由回调 |
| `src/ui/ProbeController.java` | 148 | 探测模式、参数拼装、引擎调用、字段联动 |
| `src/ui/CaptureController.java` | 285 | 抓包 / 转换 / 复制 / 一键发送 / 代理流量导入 |
| `src/ui/ShiroController.java` | 251 | 检测 / 爆破 / 生成载荷 / 执行命令 |
| `src/ui/ProxyController.java` | 310 | 代理启停、拦截改包放行、流量回填、导出 |
| `src/ui/ConfigForm.java` | 160 | 配置控件与分组清单 |
| `src/ui/ConfigController.java` | 395 | 配置读 / 写 / 下发、服务器默认值、下拉框助手 |
| `src/ui/WorkbenchPages.java` | 150 | Payload / 预设链 / 恶意服务器三页懒加载与互发 |
| `src/ui/WidgetRegistry.java` | 159 | 自检门面登记表 |
| `src/ui/UiHandle.java` | 72 | 稳定门面 |

依赖方向只允许「向后」：抓包 → 探测 / Shiro，代理 → 抓包 → 配置。
`ConfigController` 与 `WorkbenchPages` 因构造期先后关系用一次 `attachView` 补接线。

### 五、对原设计的三处修正（实施后调整，理由已写回设计文档）

1. **不建 `ProxyState` / `ConfigState` / `ShiroState`**：这些字段是控件引用而非状态；
   真正的状态（是否在监听、待放行哪条流量、当前拦截决定）收进对应控制器私有字段。
2. **字段不必留在 `Main`**：稳定门面 + 反射回落实测可行，因此字段随职责一起搬走。
3. **包结构平铺**：子包会变成新包名并要求同步补 `ALLOWED_EDGES`；平铺在 `ui` 包内
   则 `tests/test_decoupling.py` 与 `tools/audit_boundary.py` 两条规则零改动。

### 六、结果

| 指标 | 改动前 | 改动后 |
| --- | ---: | ---: |
| `Main.java` 行数 | 1757 | **311** |
| `Main.java` 方法数（外层） | 66 | 30（含构造器与 13 个自检转发方法） |
| `Main.java` 字段数（外层） | 148 | 21（多为控制器引用与控件容器） |
| 最大单文件 | `Main.java` 1757 | `proxy/ProxyServer.java` 768（本次不动） |
| `src/ui/` 文件数 | 22 | 32 |

### 七、验证（全部为本机实测）

| 验证入口 | 结果 |
| --- | --- |
| `ProxyServerCheck` | 退出码 0，37 条断言 |
| `ShiroCheck` | 退出码 0，46 条断言 |
| `PayloadCheck` | 退出码 0，61 条断言 |
| `UiShiroCheck` | 退出码 0，27 条断言 |
| `UiNavigationCheck` | 全部通过，169 条断言 |
| `UiSwitchEndToEndCheck` | 退出码 0，95 条断言 |
| `python -m unittest discover -s tests` | 99 项 OK |
| `python tools/audit_boundary.py` | 无环、无越界、无叶子层出边、无内核反向依赖，结论「全部通过」 |
| `openspec validate --all --strict` | 5 项全 passed |
| `.\build.ps1` | 构建成功，JAR 时间 `2026-09-21 19:17:51`，298589 字节，60 个源文件时间校验通过 |

断言数改动前后一致（37 / 46 / 61 / 27 / 169 / 95），证明本次是纯搬迁、行为零变化。

一处非预期但已修的问题：搬迁后 `UiNavigationCheck` 报「取消 DNS 探针后 DNSLog 主机输入框仍可用」。
根因是原主窗口构造里 `dnsEnabled` / `ceyeEnabled` / `modeExpect` 三个勾选框接的是
`updateStageFieldState`，搬迁时只保留了 `ProbePage` 的构建回调，漏了这三个监听器。
补回后自检通过——若当时只把断言当成「过时断言」改掉，就会把一个真实的行为退化掩盖过去。

### 八、OpenSpec

新增 change `ui-modularization` 并已归档为 `2026-09-21-ui-modularization`：
proposal 含 Non-goals 与写入域 Impact；design 含 Root Cause 与三处修正；
tasks 44 项全部勾选（含收尾三项与监督复核）。
主 spec `codebase/dependency-boundary` 新增 3 条要求（页面行为与视图分离、
单文件规模上限、自检必须走稳定门面）。
归档后 `openspec validate --all --strict` 曾报两个在办 change 的 delta 缺少新场景，
已把「界面控制器之间无反向依赖」场景同步到两处 delta，5 项全部 passed。

### 九、多 Agent 协作

| 角色 | 主要工作 |
| --- | --- |
| 主 agent | 基线实测、根因定位、拆分方案定稿、OpenSpec 规划件与归档、文档与报告、构建收尾 |
| 界面 agent（ui） | `src/Main.java` 收敛、10 个控制器 / 表单 / 门面类、四个视图类控件工厂、`ConfigPage` 装配助手 |
| 测试 agent（test） | 三个 UI 自检的取数辅助方法改走 `ui/UiHandle`，断言零改动；验证 `hasDeclaredField` 语义保持 |
| 监督 agent（supervisor） | 独立复算：断言数与改动前一致、依赖边界审计复跑、JAR 时间晚于全部源文件 |

### 十、如实说明的局限

1. `ui` 包内 32 个文件平铺，继续增长后仍需再分子包（届时需同步补 `ALLOWED_EDGES`）。
2. 代理拦截等待上限仍是硬编码 120 秒常量，未进配置页。
3. `src/proxy/ProxyServer.java`（768 行）与 `src/shiro/ShiroEngine.java`（715 行）
   本次未拆：都在 800 行以内且有独立自检，属于独立课题。
4. 界面自检仍需两个 `--add-opens`（预设链含字节码类 gadget），运行方式见 `run.ps1`。

## 2026-09-21（本轮：Payload 生成页对齐 JavaChains Generate）

### 一、任务

用户指令：「将 payload 生成功能改为 JavaChains 的 Generate 功能，保留自定义 gadget 功能，
去掉其他复杂的功能。」

拆成三条可核对的边界：

1. 选链交互改成网页版 `Generate` 页的形态：**保留自定义 gadget**（手点链）这一路；
2. 去掉本页现有交互里**多余的那一层**（下拉框 + 追加按钮的两步一次）；
3. 网页版 Generate 的**周边复杂能力不做**：暴力矩阵、步进调试生成、分享链、常用链路统计、
   保存预设、Tag 筛选与并集 / 交集匹配、输出反编译与序列化解析。

### 二、先实测再动手（结论有据，不是推测）

按「机制性问题必须实测」的要求，先解包上游 `java-chains-cli-2.0.0-beta4.jar` 核对事实：

| 实测项 | 结论 |
| --- | --- |
| 上游 Generate 页的选链组件 | `ChainColumnSelector`：列式展开，点第 N 列候选即从第 N 列重开链 |
| 上游 Generate 页的周边能力 | `BruteMatrixEditor`（暴力矩阵）、`StudioDebugGeneratePanel`（步进调试）、`ShareChainMenu`（分享链）、`RecentChainsButton`（常用统计）、`SavePresetDialog`（保存预设）、Tag 筛选与并集/交集——**均不属于「生成」这条主路径** |
| 节点显示名 | `MetadataRegistry.getNodeMetaMap()` 实测 429 个节点，其中 **426 个有显示名**，3 个没有（`HutoolJndiDSFactory` 等）；载体名全为中文 |
| `ChainsRuntime.start()` 是否填充元数据 | **是**，`start()` 之后 429 个节点的元数据已可查，不需要额外初始化 |
| 单列候选规模 | `javanativepayload` 首节点 **104** 项、`objectpayload` 达 **118** 项；28 个载体合计 1748 项，全量校验 **49 ms** |
| 载体自身有无后继 | **无**（`nextNodes(载体)` 为空集），首节点必须逐个做链校验 |
| 已知真实路径 | `javanativepayload -> aspectjweaver -> storeablecachingmap`；`clojure` 是**叶子**（无后继） |

### 三、改动内容

| 文件 | 动作 |
| --- | --- |
| `src/ui/PayloadChainSelector.java` | 新增：列式链选择器（366 行），列容器 + 每列过滤框 + 候选列表 + 横向滚动；不持有链状态 |
| `src/ui/PayloadPage.java` | 表单区改为「链文本一行 + （选择器 ｜ 参数侧栏）」；`renderParams` 增加紧凑重载（原签名委派，服务页零改动） |
| `src/ui/PayloadController.java` | 按 `ChainEditor` 现算每列候选；点某列即截断重开；删除 `group` / `kind` / `next` / `addNode` 四个控件的行为 |
| `src/payload/PayloadEngine.java` | 新增只读 `nodeLabel(String)`；既有 10 个公开方法签名与语义零改动 |
| `src/ui/WorkbenchPages.java`、`src/ui/WidgetRegistry.java`、`src/Main.java` | 装配与自检登记（选择器随控件只创建一次；`payloadSelector` 登记进门面） |
| `tests/UiNavigationCheck.java` | Payload 段改写为列式断言（控件增删、列数、逐级展开、点回重开、过滤、叶子不展开空列） |
| `tests/PayloadCheck.java` | 新增 `labels()` 一组断言（61 → 68 条） |
| `openspec/changes/payload-generate-view/**` | 新增 change：proposal / design / specs / tasks |

### 四、修掉的两个真实缺陷（先给根因）

**1）点第二列候选毫无反应。**

根因：`PayloadController.select` 的边界判断写成 `if (column > chain.size() - 1) return;`。
链长为 1 时（只有载体），末尾列是 `column == 1`，而 `1 > 0` 成立，**追加被当成越界挡掉**。
正确判据是「允许 `column == chain.size()`」（在末尾列追加），即 `column > chain.size()`。
由 `UiNavigationCheck` 的「点第二列候选后链变为两段」断言抓出，不是靠肉眼发现。

**2）过滤框输入第二个字符会落空。**

根因：过滤变化时整体重建列，正在输入的 `JTextField` 被换掉，光标随之丢失。
改为只重绘列表内容（`repaintColumns`），列结构不动，过滤框实例保持不变。

两处都不是试错改出来的：先定位到「追加被边界挡掉」与「输入控件被重建」，再动手。

### 五、验证（全部本机实测）

| 验证入口 | 结果 |
| --- | --- |
| `ProxyServerCheck` | 退出码 0 |
| `ShiroCheck` | 退出码 0 |
| `PayloadCheck` | 退出码 0，**68** 条断言（原 61 + 显示名 7） |
| `UiShiroCheck` | 退出码 0 |
| `UiNavigationCheck` | 退出码 0，**196** 条断言（原 169），失败 0 |
| `UiSwitchEndToEndCheck` | 退出码 0，94 条断言 |
| `python -X utf8 -m unittest discover -s tests` | 99 项 OK |
| `python -X utf8 tools\audit_boundary.py` | 无环、无越界、无叶子层出边、无内核反向依赖，结论「全部通过」 |
| `openspec validate --all --strict` | 6 项全 passed |
| `.\build.ps1` | 构建成功，JAR 时间 `2026-09-21 20:09:58`，310252 字节，61 个源文件时间校验通过 |

端到端实测（列式交互）：点第一列 `javanativepayload` → 点第二列 `clojure` → 生成成功 950 字节；
点第二列 `aspectjweaver` → 展开第三列 → 点 `storeablecachingmap` → 三段链；
点回第二列 `clojure` → 第三列被丢弃；过滤框输入 `beanshell` → 第二列 104 项收敛到 3 项，
清空后恢复 104 项，链状态不变。

### 六、文档同步

- `README.md` / `README.en.md`：Payload 生成章节改写为列式交互（四条规则、过滤、"周边功能没做"清单）、
  项目结构的 `src/ui/` 行补列式选择器、测试表补「节点显示名」——中英一一对应。
- `docs/DESIGN-payload.md`：3.1 能力范围改写、新增 5.1 列式交互（含界面示意图与四条规则）、
  实施顺序补 change D、新增 9.1 列式改造期间的实测发现（9 项）。
- `PROGRESS.md`：两行状态改写 + 一条已完成阶段 + 三条后续局限。

### 七、多 Agent 协作

| 角色 | 主要工作 |
| --- | --- |
| 主 agent | 上游 jar 解包实测、change 规划件、文档与报告、构建收尾 |
| 利用链 agent（exploit） | `src/payload/PayloadEngine.java` 的节点显示名只读查询 |
| 界面 agent（ui） | 列式选择器、Payload 页与控制器改造、装配与门面登记 |
| 测试 agent（test） | `PayloadCheck` 显示名断言、`UiNavigationCheck` 列式断言与三个辅助方法 |
| 监督 agent（supervisor） | 独立复核写入域、断言未放宽、JAR 时间；复核结论见下 |

### 八、监督复核（独立复算，不引用实现者结论）

| 复核项 | 结论 |
| --- | --- |
| 越界改动 | 无：`git status` 的改动全部落在 proposal 的 Impact 表内；`src/config/**`、`src/util/**`、`src/pom.xml` 未被触碰 |
| 空承诺 | 无 TODO / FIXME / 空方法体；`PayloadController` 与 `PayloadChainSelector` 的方法均有实现 |
| 断言放宽 | 未放宽：`PayloadCheck` 61 条既有断言逐条保留（只新增 7 条）；`UiNavigationCheck` 新增 27 条，未删除既有断言（原 169 → 196） |
| 依赖合规 | `src/pom.xml` 未改，未引入新依赖；列式选择器只用 JDK Swing |
| 构建一致性 | 亲自跑 `build.ps1`：JAR `2026-09-21 20:09:58` 晚于 61 个源文件 |
| 文档同步 | 中英 README 的 Payload 章节与测试表行一一对应；两份未出现单边内容 |
| 配置落页 | 本次**未新增**可持久化配置项（`payload_export_dir` 键与分组保持原状），无配置漏页问题 |
| 既有功能 | 恶意服务器页（同样使用 `ChainEditor` 与 `PayloadPage.renderParams`）自检全绿，行为未变 |

### 九、如实说明的局限

1. 列式选择器每列固定 244px 宽；列数多于一屏时需要横向滚动才能看到末尾列（已实现自动滚动到新列）。
2. 分支型链仍未支持：本页构建的是线性链，一个节点接多个后继后再汇合的场景没有暴露到界面。
3. `nodeLabel` 依赖上游元数据：上游若给不出显示名，界面回退展示节点标识（当前 429 个节点里 3 个属于这种情况）。
4. 上游的暴力矩阵、步进调试生成、分享链、常用统计、保存预设、Tag 筛选与输出解析**明确不做**，
   已在 proposal 的 Non-goals 与两份 README 中写明，避免被误读为「漏做」。
5. 恶意服务器页的「载体分组 + 载体」下拉框保留原样：那是发布流程的选择器，不是本页的选链体验。

## 2026-09-21（本轮收尾：构建复验 + 仓库损坏对象清理 + Git 提交推送）

### 一、构建复验（AGENTS.md「每次项目构建后验证 jar 包构建时间是否正确」）

首次核对发现 `JavaSecExpToolKit.jar`（20:15:53）**早于** `src/ui/PayloadController.java`（20:16:45），
按约束必须重新构建。重新执行 `.\build.ps1`：

| 项 | 结果 |
| --- | --- |
| 构建结论 | `BUILD SUCCESS` |
| JAR 路径 | `JavaSecExpToolKit.jar` |
| JAR 时间 | `2026-09-21 20:20:47`（晚于 61 个源文件，时间守卫通过） |
| JAR 大小 | 310247 字节 |
| 运行时依赖 | `lib/java-chains-cli-2.0.0-beta4.jar`（184569201 字节） |

### 二、构建后复跑自检（build 会 clean 掉 target，测试类必须重编）

重新 `javac` 编译 `tests\*.java` 后逐项复跑：

| 入口 | 退出码 | [ok] | [FAIL] |
| --- | --- | --- | --- |
| `PayloadCheck` | 0 | 68 条断言 | 0 |
| `UiNavigationCheck` | 0 | 196 | 0 |
| `ProxyServerCheck` | 0 | 36 | 0 |
| `ShiroCheck` | 0 | 45 | 0 |
| `UiShiroCheck` | 0 | 26 | 0 |
| `UiSwitchEndToEndCheck` | 0 | 94 | 0 |
| `python -m unittest discover -s tests` | 0 | 99 项 OK | 0 |
| `python tools\audit_boundary.py` | 0 | 结论「全部通过」 | 0 |
| `openspec validate --all --strict` | 0 | 6 passed | 0 |

### 三、仓库损坏对象清理（根因分析）

现象：`git fetch` 报 `fatal: too-short tree object`，随后 `geometric-repack` 失败。

根因：`.git/objects/76/ec3c3ed28687eab45219104790d7285049afc8` 是一个**不可达的 dangling tree**，
体积仅 18 字节（合法 tree 至少 20+ 字节，无法被解析），`git fsck` 归类为
`dangling tree` + `badTree: cannot be parsed as a tree`；`geometric-repack` 在打包时会遍历全部对象，
遇到无法解析的对象即中止，导致 fetch 后处理失败。

处置：确认该对象不被任何 ref / 提交引用（本身就是 dangling），用 Python 脚本经路径越界校验后定向删除该单个文件；
**未执行任何仓库级危险操作**（未 `git gc --prune`、未删 pack、未动其他对象）。

结果：`git fetch origin` 恢复正常（`= [up to date] main -> origin/main`），
`git fsck --connectivity-only` 仅剩正常的 dangling commit/blob 提示，无 `error` 行。

### 四、Git 管理与推送

- 提交范围严格限定在本轮 change 的 Impact 表内：13 个已跟踪文件 + 3 个新增项
  （`src/ui/PayloadChainSelector.java`、`openspec/changes/archive/2026-09-21-payload-generate-view/`、
  `openspec/specs/payload/`）。
- 未触碰 `src/config/**`、`src/util/**`、`src/pom.xml`，未引入任何新依赖。
- 推送前核对：本地 HEAD 与 `origin/main` 基线一致（`666403f`），无需先 rebase。
- `.backups/` 保持最近三次（`20260921-192005` / `195436` / `201833`），本轮未新增源码改动故未再追加备份。

### 五、多 Agent 协作（本轮）

| 角色 | 主要工作 |
| --- | --- |
| 主 agent | 构建复验、仓库损坏对象根因定位与定向清理、自检复跑、提交推送 |
| 测试 agent | 复跑六个 Java 自检入口与 Python 单测、边界审计、openspec 校验 |

### 六、如实说明的局限

1. 仓库内仍有大量 dangling commit/blob（属正常历史残留，不影响 fetch / 提交 / 推送），
   如需彻底回收需人工确认后执行 `git gc --prune=now`，本轮未做。
2. `openspec/changes/` 下 `java-chains-workbench`（36/42）与 `payload-generation`（0/36）两个 change 仍在办，未归档。

## 2026-09-22（本轮：自检计算器进程清理 + 界面层解耦达标 + 配置页补漏）

### 一、任务

用户指令：「测试的时候弹出计算器后要关闭计算器的进程」。

同时收尾上一位 agent 遗留的「UI 与功能同网页版 JavaChains 差别过大」：
把列式改造越界的三个界面文件按规格拆回 600 行以内、把两个漏页的行为开关补进配置页。

### 二、根因（先实测，不推测）

解包上游 `java-chains-cli-2.0.0-beta4.jar` 逐项核对，确认这不是自检写错了，而是自检**如实覆盖了真实链路**：

```
== clojure
   key=Clojure.cmd value=calc required=true
== exec
   key=Exec.cmd value=calc required=true
```

`Clojure` / `Exec` 节点的命令写在**参数值**里，上游登记的默认参数值就是 `calc`；引擎在**构建期**执行该命令。
`UiNavigationCheck` 为覆盖「生成 / 调试生成 / 预设链生成」三条路径，必须把参数交给引擎，
于是每次运行弹出 2 个计算器（实测 `CalculatorApp.exe` 三批 pid：26224/1996、56648/23724、14260/9804）。

### 三、修复

新增 `tests/TestProcessGuard.java`（112 行），六个自检入口在 `main` 第一行调用
`TestProcessGuard.install("<入口名>")`：

1. 启动时枚举 `CalculatorApp` 进程，记录 **pid + 启动时刻**（快照）；
2. **先快照、再注册 JVM 退出钩子**（顺序反了守卫会静默失效）；
3. 退出时只 `destroyForcibly()` 启动时刻晚于快照的进程，最多扫 5 轮 × 300 ms，
   覆盖「刚被拉起、进程还没进枚举」的窗口。

设计取舍：

- 用**退出钩子**而不是 `try/finally`：自检收尾走 `System.exit`，`finally` 不保证执行。
- 只用 JDK 8 可用的 `ProcessHandle`；`allProcesses()` 返回 `Stream`，必须 `.forEach()`（for-each 编译不过）。
- **不碰用户自己开的计算器**（快照里已存在的进程一律不动）。

### 四、界面层解耦（规格违背修复）

`openspec/specs/codebase/dependency-boundary/spec.md` 第 85 行规定单个界面文件 ≤ 600 行。
列式改造后三个文件越界，按**职责**（不是按行数）拆分：

| 原文件 | 拆前 | 拆后 | 新增协作类（行数） |
| --- | ---: | ---: | --- |
| `src/ui/PayloadPage.java` | 787 | 252 | `PayloadPanels`(520)、`PayloadColumns`(72) |
| `src/ui/PayloadController.java` | 739 | 546 | `PayloadOutputText`(95)、`PayloadExporter`(56) |
| `src/ui/PayloadChainSelector.java` | 729 | 549 | `ChainColumnFilter`(89)、`ChainNodeRenderer`(59)、`ChainTagMenu`(78) |

两条交付约束：

1. **不复制状态**：`PayloadChainSelector` 仍是链状态唯一持有者；`ChainTagMenu` 通过
   `Handler` 接口（`chosen` / `isIntersect` / `apply` / `refresh`）回调，自己不存状态。
2. **不改引擎语义**：`PayloadEngine` 既有公开方法签名与语义不变，`PayloadCheck` 68 条断言逐条保留。

### 五、配置页补漏

列式改造引入的两个 BEHAVIOR 开关此前没落页，本轮补齐（配置键 + 界面 + 自检登记三处齐全）：

| 配置键 | 界面文案 | 默认 |
| --- | --- | --- |
| `payload_auto_expand` | 生成后默认展开完整载荷 | 开 |
| `payload_hover_select` | 默认开启悬停选链 | 开 |

### 六、仓库清理

删除根目录 `static/`（51 个文件）。它是早期从上游 JAR 解出的参考前端，
经全仓库检索确认源码 / 测试 / `pom.xml` **零引用**，且内容可由
`lib\java-chains-cli-2.0.0-beta4.jar` 内的 `static/` 条目完整复原，删除后不丢信息。
删除用一次性 Python 脚本（枚举 + 前缀越界校验 + `shutil.rmtree`），未使用 `Remove-Item -Recurse -Force`。

### 七、验证（全部本机实测）

| 验证入口 | 结果 |
| --- | --- |
| `javac` 编译 `src/**`（Maven） | BUILD SUCCESS，仅 `PayloadEngine` unchecked 注记 |
| `javac` 编译 `tests/*.java` | 退出码 0 |
| `PayloadCheck` | 退出码 0，**68** 条断言，失败 0 |
| `ShiroCheck` | 退出码 0，45 条断言，失败 0 |
| `ProxyServerCheck` | 退出码 0，36 条断言，失败 0 |
| `UiNavigationCheck` | 退出码 0，**225** 条断言，失败 0（原 196 → 本轮 +29） |
| `UiShiroCheck` | 退出码 0，26 条断言，失败 0 |
| `UiSwitchEndToEndCheck` | 退出码 0，94 条断言，失败 0 |
| `python -X utf8 -m unittest discover -s tests` | **Ran 106 tests OK**（原 99 → +5 自检卫生 +2 文件规模） |
| `python -X utf8 tools\audit_boundary.py` | 无环、无越界、无叶子层出边、无内核反向依赖，结论「全部通过」 |
| `powershell -File tools\check_agent_tools.ps1` | agent 工具链自检通过（91 项） |
| `openspec validate --all --strict` | 6 项 passed，0 failed |
| `.uild.ps1` | 构建成功，JAR 时间 `2026-09-22 08:31:46`，362318 字节，74 个源文件时间校验通过 |

计算器守卫实测：`UiNavigationCheck` 日志出现
`[calc-guard] UiNavigationCheck：已关闭自检期间弹出的 2 个计算器进程 [...]`；
其余五个入口为「本轮未弹出计算器进程」；六轮跑完 `Get-Process | ? ProcessName -match 'calc|Calculator'` **为空**。

### 八、监督复核（独立复算，不引用实现者结论）

| 复核项 | 结论 |
| --- | --- |
| 越界改动 | 无：改动集中在 `src/ui/`、`src/payload/`、`tests/`、文档与规格；`src/config/AppConfig.java` 与 `src/pom.xml` 未被触碰，未引入新依赖 |
| 空承诺 | 无 TODO / FIXME / 空方法体；新增七个协作类的方法均有实现 |
| 断言放宽 | 未放宽：六套自检失败数均为 0，`PayloadCheck` 68 条、`UiNavigationCheck` 225 条均只增不减 |
| 守卫接入完整性 | 六套入口全部接入，`tests/test_selfcheck_hygiene.py` 5 条断言守住「接入 + 快照先于钩子」；标签与文件名一致 |
| 文件规模契约 | `src/Main.java` 301 行（≤400），`src/ui/` 下 35 个文件最大 549 行（≤600），由 `tests/test_decoupling.py` 机械校验 |
| 配置落页 | 两个新键既有 `ConfigForm` 行也有 `ConfigController` 读写，且已登记进 `WidgetRegistry` 供自检断言 |
| 构建一致性 | `build.ps1` 自身逐文件校验：JAR `08:31:46` 晚于 74 个源文件 |

### 九、备份与 Git

- 改动前快照 `.backups/20260922-082905` 已存在；本轮收尾另建 `.backups/20260922-083827`（146 个文件），
  并轮转删除最旧的 `20260921-204421`，现存 `20260921-212827` / `20260922-082905` / `20260922-083827` 三份。
- 提交范围限定在本轮改动：36 个文件（23 改 + 13 新增）+ `static/` 删除，提交 `cb62419` 已推送至 `origin/main`；
  `.backups/`、`target/`、`JavaSecExpToolKit.jar` 由 `.gitignore` 忽略。

### 十、如实说明的局限

1. 计算器守卫按**进程名 + 启动时刻**判定，只覆盖 `CalculatorApp`；
   若上游把默认参数值从 `calc` 换成别的命令，守卫不会清理该程序的进程，需要同步调整匹配。
2. 守卫只在**自检入口**生效；用户从界面点「生成」时引擎仍会执行参数里的命令——
   这是功能的真实行为（可自定义命令），不是缺陷，但界面上没有二次确认提示。
3. `static/` 已删除；若后续要参考网页版前端，需要重新从 `lib\java-chains-cli-2.0.0-beta4.jar` 解出。
4. `openspec/changes/` 下 `java-chains-workbench` 与 `payload-generation` 两个 change 仍在办，未归档。
5. `docs/DESIGN-modularization.md` 第十节记录的断言数与 Python 测试数是当时快照，未回改（已在第十一节标注）。

## 2026-09-22（本轮：启动预热 + 选链区比例对齐网页版 + 分割线布局期生效）

### 一、任务

用户指令（逐条）：

1. 「完善 UI，利用链选择框太小，要和 JavaChains 一样的格式」
2. 「软件启动的时候将需要进行加载初始化的东西都做好，防止中途进行初始化」
3. 「修改 BUG：恶意服务器，payload 等内容比较多的功能第一次点进去会卡几秒」
4. 收尾澄清：**「并不是和网页版一样高，而是和网页版中的选链区和整个网页的比例要一样」**

第 4 条是本轮的关键修正：原先按「照搬网页版固定高度」的思路走偏了，要的是**比例**一致。

### 二、根因（先实测，不推测）

**卡顿的根因**：`MetadataRegistry.init()` 实测 **1074 ms**（连插件与 gadget 注册约 1.2 s），
原先发生在**第一次进 Payload 页**、且**在事件分发线程上同步执行**；恶意服务器页同理，
首次进页才建服务适配器。

**比例对不上的根因**：网页版选链区是「控制台固定 360px、选链区吃掉剩余」，本工具窗口高矮不同，
照搬绝对值必然对不上。为避免继续凭估，本轮改用 **CDP 实测**上游 `#/Generate/<payload>` 页，
拿到权威数值：控制台 `.studio-top-console` **360px**、选链区 `.chain-builder-block` **486px**、
列面板 `.chain-column` 389px、候选列表 `.option-list` **320px**。

**输出区高度读两个值的根因**：分割线原先靠 `componentResized` / `componentShown` 事件回调去摆，
组件事件是**异步投递**的，布局结束到事件处理之间存在空窗。同一个构建里因此能读到两个值：

```
[probe2] round=0 split=817 divider=290 output=101   <- 自检读到的就是这个空窗
[probe2] round=1 split=817 divider=322 output=133   <- 事件处理完之后
```

### 三、修复

**1）启动预热** — 新增 `src/ui/StartupWarmup.java`（138 行）与 `src/ui/StartupSplash.java`（96 行）：

| 步骤 | 实测 | 内容 |
| --- | --- | --- |
| java-chains 引擎 | 1154 ms | `PayloadEngine.init()`，429 节点 / 28 载体 |
| 预设链目录 | 46 ms | 52 条预设 |
| 服务适配器 | 22 ms | 五类恶意服务器 |

`main` 先弹无边框启动画面（headless 自动跳过），**在后台线程**预热，主窗口在事件分发线程上建；
窗口出现时预热若未结束，剩余步骤在 `show(splash)` 里补齐，结束才关启动画面。
预热**幂等**：二次调用 **0 ms**，重复进页面不重复初始化。

**2）选链区比例对齐** — `PayloadPanels.CONSOLE_WEIGHT` 由实测常量直接算出：

```java
static final double CONSOLE_WEIGHT =
        (double) WEB_CONSOLE_HEIGHT / (WEB_CONSOLE_HEIGHT + WEB_CHAIN_HEIGHT);   // 360 / (360+486)
```

即选链区占两块之和的 **57.4%**，对齐的是比例而非 360px 绝对值。

**3）分割线在布局期生效** — 新增 `src/ui/RatioSplitPane.java`（87 行），把比例校正放进
`doLayout()`，布局一结束位置就对，不依赖后续事件；用 `applying` 标志挡住 `setDividerLocation`
触发的重入；用户拖动过（或 `lock()`）之后比例立即失效。选链区拖拽条改高度时走
`SplitPaneKit.setDivider(...)`，先 `lock()` 再挪，否则下一次布局会按比例拽回原位（「拖了没反应」）。

**4）其余** — `WrappedLabel` 承接长提示与状态文字（`getText()` 仍返回原始文本，不破坏断言）；
`ChainResizeHandle` / `ChainColumnPanel` / `ChainColumnState` / `ChainSelectorSizing` 承接选链几何与列面板。

### 四、验证（全部本机实测）

| 验证入口 | 结果 |
| --- | --- |
| `javac` 编译 `src/**` | 退出码 0 |
| `javac` 编译 `tests/*.java` | 退出码 0 |
| `PayloadCheck` | 退出码 0，**68** 条断言，失败 0 |
| `ShiroCheck` | 退出码 0，45 条断言，失败 0 |
| `ProxyServerCheck` | 退出码 0，36 条断言，失败 0 |
| `UiNavigationCheck` | 退出码 0，**225** 条断言，失败 0（此前卡在输出区 101px，已修） |
| `UiShiroCheck` | 退出码 0，26 条断言，失败 0 |
| `UiSwitchEndToEndCheck` | 退出码 0，94 条断言，失败 0 |
| `python -X utf8 -m unittest discover -s tests` | **Ran 106 tests OK** |
| `python -X utf8 tools\audit_boundary.py` | 无环、无越界、无叶子层出边、无内核反向依赖，结论「全部通过」 |
| `powershell -File tools\check_agent_tools.ps1` | agent 工具链自检通过（91 项） |
| `openspec validate --all --strict` | 6 项 passed，0 failed |
| `build.ps1` | BUILD SUCCESS，JAR `2026-09-22 12:41:42`，385622 字节，84 个源文件时间校验通过 |

**版面实测对照**（窗口 1721×1033，可用高 817px）：

| | 控制台 | 选链区 | 选链区占比 | 候选列表可视高 |
| --- | --- | --- | --- | --- |
| 网页版（CDP 实测设计值） | 360 px | 486 px | 57.4% | 320 px |
| 本工具（`Ratio` 工具实测） | 343 px | 462 px | 56.5% | 309 px |

其余实测：启动预热首次 1263 ms、二次 0 ms、预设 52 条；
`LayoutAudit` 八页 `TOTAL: 0`（无遮挡、无错位）；
`FontLeak` 三轮导航后控件数稳定 257、堆 24 MB（无字体登记表泄漏）。

### 五、监控复核

| 复核项 | 结论 |
| --- | --- |
| 越界改动 | 无：改动集中在 `src/ui/`、`docs/`、`README*.md`、`AI_REPORT.md`、`PROGRESS.md`；`src/pom.xml`、`src/config/AppConfig.java` 未被触碰，未引入新依赖 |
| 空承诺 | 无 TODO / FIXME / 空方法体；新增七个类的方法均有实现 |
| 断言放宽 | 未放宽：六套自检断言数为 225 / 68 / 45 / 36 / 26 / 94，只增不减 |
| 文件规模 | `src/Main.java` 327 行（≤400）；`src/ui/` 最大 526 行（`PayloadChainSelector`，≤600），由 `tests/test_decoupling.py` 机械校验 |
| 配置落页 | 本轮未新增持久化配置项，无需补配置页 |
| 构建一致性 | `build.ps1` 逐文件校验：JAR 时间晚于 84 个源文件 |

### 六、备份与 Git

- 改动前快照已存在（`.backups/20260922-101154`、`20260922-105212`）；
  本轮收尾另建 `.backups/20260922-123500`（158 个文件），并轮转删除最旧的 `20260922-083827`，
  现保留最近三份。
- `.backups/`、`target/`、`JavaSecExpToolKit.jar`、`.pi/` 由 `.gitignore` 忽略。

### 七、如实说明的局限

1. 网页版比例是**用 CDP 量上游 beta4** 得到的；上游改版后 360 / 486 这两个数需要重新量。
2. `RatioSplitPane` 的比例在用户拖动后即失效，之后窗口缩放不再回到 57.4%——这是刻意的，
   优先尊重使用者调好的位置。
3. 启动预热把首次进页的等待挪到了启动阶段；预热耗时本身（约 1.2 s）没有减少，只是不再卡在进页面那一下。
4. 量测脚本（`target/webref/`）放在 `target/` 下，`build.ps1` 会清理，需要时按本文档说明重建。
