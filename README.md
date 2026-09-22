# JavaSecExpToolKit

面向**授权**安全测试的 Java + Python 桌面工具集：Fastjson 指纹识别、本地拦截代理、
抓包与格式转换，以及 Shiro 利用模块。

语言：[中文](README.md) | [English](README.en.md)

## 适用范围

Fastjson 探测对授权范围内的 HTTP JSON 接口做**无害探测**：发送正常 JSON、残缺 JSON 与
无副作用的 `@type` 标记，对比响应特征，共五种模式：

- `detect`（识别）— 判断是否 Fastjson，并与 Jackson / Gson / org.json / Hutool 区分
- `version`（版本）— AutoType 状态、回显的 `fastjson-version`、离线布尔探针（`version_detail` / `version_range`）
- `expect`（期望类）— 反序列化点是否绑定了具体的 Java 类型
- `dns`（DNS 探针）— 四种无害 DNS 探针（`Inet4Address` / `InetSocketAddress` / URL 键 / `Exception`）
- `ceye`（CEYE 确认）— 通过 CEYE 接口确认 DNS 记录

每个模式默认输出**中文报告**（`--format text`）。报告详细度由 `--report` 控制：

- `--report brief`（**默认**）— 精简报告：每段只给结论首句与关键判定（是否 Fastjson、
  可能版本、PoC 档位、AutoType / SafeMode、置信度），不留段间空行。五个模式一起跑
  合计 16 行，界面结果区一屏就能看完。
- `--report detail` — 详细报告：额外附上目标、结论尾句（原因与建议）、逐条探针明细
  （状态码与命中特征）、提示、阶段小结与已知限制，用于排查假阳性。

抓包 / 转换模式始终按详细方式渲染（它们本身就是「内容即结果」）。需要结构化结果时用
`--format json`，`summary` 等字段与详细度无关，始终完整。

**传输层失败按实际情况说明，而不是猜**：若全部探针都在 HTTP 层被拒绝（例如登录页对任何请求
都回 `405` + `Allow: GET, HEAD` + 空响应体），且没有任何解析器特征，`detect` 会给出
「无法探测」且置信度为 `0.0`，`version` 保持「未能收敛」，不再推出一个假版本区间。

**登录拦截会被单独识别**，而不是被误读成「该路径只接受 GET」。当探针被整层拒绝且没有任何
命中特征时，引擎会**额外补发一条不带 payload 的 `GET`**，并用**完整响应体**检查登录页特征
（`/user/login`、`name="password"` 等）。必须用完整响应体：这些标记出现在正文深处，
远超 `evidence` 里 500 字的截断长度。结论会带模式前缀（`无法探测：` / `版本未能收敛：` /
`未能完成探测：`）与原因，JSON 结果里写入 `login_gate`。若已填写会话 Cookie 仍被拦，
结论会明确指出，以区分「没填 Cookie」与「Cookie 已失效」。

接口需要登录态时，用 `--session-cookie 'JWT_TOKEN=...; JSESSIONID=...'`（或
`--headers '{"Cookie":"..."}'`）。`--session-cookie` 按名字并入 `Cookie` 头，**同名不覆盖**，
因此代理抓到的 `JWT_TOKEN` 与配置里的 `JSESSIONID` 能同时保留。

Fastjson 探测模式**从不投递 payload**：不做利用链、命令执行、文件读写或内存马。

另有一个独立的 **Shiro** 模块，包含利用能力（rememberMe 识别、密钥爆破、回显链投递与
命令执行）。**仅可用于你获得明确书面授权的目标**，详见
[docs/DESIGN-shiro.md](docs/DESIGN-shiro.md)。

抓包相关能力有两块：

- **抓包转换** — 用任意方法发送一次请求并查看原始响应（不跟随跳转，便于观察登录流程），
  再把结果导出为 `json`、`curl`、`raw`、`cookie-header`、`cookie-json` 或 `cookie-netscape`。
  直接粘贴 Burp 或浏览器的原始报文可**完全离线**解析，便于从登录流程里取出会话 Cookie
  交给探测页。
- **代理抓包** — 本地 HTTP 代理（默认端口 `8899`，默认绑定本机联网 IP），把浏览器或浏览器
  插件的代理指向它，即可像 Burp 一样实时看到请求与响应。明文 HTTP 完整记录（请求行、请求头、
  请求体、状态码、响应头、响应体）。勾选 `拦截请求` 可在请求到达目标前拦下、编辑请求包，
  再按 `放行` 发出或 `丢弃` 放弃；响应显示在下方。该勾选框**立即生效**，包括对已在运行的代理；
  同一时刻只拦一个请求（其余排队），因此后台流量不会覆盖你正在编辑的内容。请求包仅在拦截
  开启时可编辑；取消勾选后，仍在等待的请求会被原样放行。
  **HTTPS 仅做隧道转发：`CONNECT` 按字节透传、不解密**，因此 HTTPS 浏览正常但内容不可见
  （也无法拦截改包）。

探测还支持指定请求方法（`--probe-method`，默认 `POST`）。若全部探针都在 HTTP 层被拒绝，
引擎会用 `GET` / `PUT` / `PATCH` 依次重试，并在报告里记录尝试过的方法。所有探针响应完全一致
（例如一张静态登录页，哪怕状态码是 `200`）会被明确判定为「未到达解析器」，不会当作命中 Fastjson。

## 环境要求

- Windows
- Python 3.10+
- JDK 17（构建与运行；Shiro 模块在 JDK 17 下需要 `--add-opens`，`run.ps1` 已代加）
- Maven 3.9+（构建脚本默认使用 `E:\java\maven\apache-maven-3.9.4`）

构建脚本默认使用 `E:\java\jdk17` 与 `E:\java\maven\apache-maven-3.9.4`，需要时显式覆盖：

```powershell
.\build.ps1 -JavaHome E:\java\jdk21 -MavenHome E:\java\maven\apache-maven-3.9.4
.\run.ps1 -JavaHome E:\java\jdk21 -MavenHome E:\java\maven\apache-maven-3.9.4
```

若要在桌面程序中指定 Python 解释器：

```powershell
$env:FJ_PYTHON = "C:\Python313\python.exe"
.\run.ps1
```

## 构建与运行

用 Maven 构建可执行 JAR：

```powershell
.\build.ps1
```

直接运行根目录 JAR：

```powershell
E:\java\jdk17\bin\java.exe -jar .\JavaSecExpToolKit.jar
```

`run.ps1` 是便捷包装：JAR 缺失时会先自动构建。

Maven 的 POM 位于 Java 工作区（`src/pom.xml`，与源码同级），产物写回仓库根目录
（`target/`、`JavaSecExpToolKit.jar`）。Maven 会把 `python/fj_probe.py` 与
`src/shiro/res/shiro-keys.txt` 打进 JAR，入口设为 `Main`，并把运行时依赖复制到 `lib/`。
构建脚本会校验生成的 JAR 时间**不早于**关键源文件。桌面程序运行时仍需要 Python 3.10+；
`python` 不在 `PATH` 时请设置 `FJ_PYTHON`。

## 导航

侧边栏按功能分组：

- `主页` — 工作区概览
- `Payload` — 一级分类；点击可展开 / 收起二级项 `Payload 生成`、`预设链`、`toString 链`、
  `HTTP 带外 Jar`
- `服务` — 一级分类；点击可展开 / 收起二级项 `恶意服务器`、`Shiro 漏洞利用`
- `代理` — 一级分类；点击可展开 / 收起二级项 `代理抓包`（本地 HTTP 代理）
  与 `抓包转换`（单次抓包、格式转换）
- `FastJson` — 一级分类；点击可展开 / 收起二级项 `Fastjson 探测`
- `小工具` — 一级分类；点击可展开 / 收起二级项 `文件上传`（选择本地文件按 multipart
  上传到指定接口，并记录原始响应）
- `配置` — 设置项，分为 `通用配置` 与各功能分组

顺序对齐网页版 java-chains 的左侧菜单。主窗口**默认最大化**：
服务页要同时放下服务清单、监听参数、载荷发布与运行输出四块，非最大化时参数区会被压窄。

一级分类行右侧固定显示 `▾` / `▸` 标记；点击它只展开 / 收起子项，不会离开当前页面。
主页卡片上的 `打开探测` 会展开分组并直接跳到探测页。

## 启动预热

启动时先把需要初始化的一次性动作在后台做完，中途进页面不再等：

- java-chains 引擎（`MetadataRegistry` 实测约 1.1 秒，429 个节点、28 个载体、52 条预设）
- 预设链目录与恶意服务器的服务适配器

界面上先出现**启动画面**并在后台线程预热；主窗口在事件分发线程上构建，预热若还没结束，
剩余的步骤会在主窗口出现前补齐。预热是**幂等**的：第二次调用直接返回 0 ms，
因此重复进页面不会重复初始化。进度文字即各项耗时，便于判断是哪一步慢。

## 配置页

`配置` 页把固定参数保存在 `%USERPROFILE%\.JavaSecExpToolKit\config.properties`，分组如下：

- `通用配置` — Python 解释器（在 `FJ_PYTHON` 未设置时生效）、默认超时、默认探测请求方法
- `FastJson 配置` — CEYE 域名、CEYE Token、CEYE API 地址、默认 DNS 等待、默认业务参数、
  默认请求头、会话 Cookie、默认 DNSLog 主机、默认 CEYE Filter
- `探测报告配置` — 报告详细度（`精简` / `详细`，默认 `精简`）
- `代理配置` — 默认监听地址、默认监听端口、启动后是否默认拦截请求
- `抓包转换配置` — 默认请求方法、默认 Content-Type、默认转换目标
- `Shiro 配置` — 默认目标 URL、Cookie 名、密钥、AES-GCM、回显请求头、利用链、命令、默认请求体
- `Payload 生成配置` — Payload 生成页的默认导出目录（留空则写入用户目录）
- `toString 链配置` — 默认 toString 模板（候选取自内置模板本身）、默认末端命令
- `带外 Jar 配置` — 默认绑定地址、默认端口（默认 50001）、默认下载 URL、
  默认落地路径（默认 `/tmp/payload.bin`）、默认执行参数
- `恶意服务器配置` — 默认绑定地址、默认公布地址，以及 JNDI 的 LDAP / RMI / HTTP 端口、
  HTTP 服务端口、JRMP 端口、FakeMySQL 端口、TCP 端口（留空或填 0 表示沿用默认值）
- `预设链配置` — 预设链页的默认分类筛选（候选值来自内置预设文件本身）
- `小工具配置` — 文件上传页的默认上传 URL、默认表单字段名（默认 `file`）、上传超时（默认 30 秒）

保存后的值会预填到**全部功能页**（探测页 + 代理页 + 抓包页 + Shiro 页 + Payload 页 +
预设链页 + 恶意服务器页 + toString 链页 + 带外 Jar 页），保存后即刻下发到已经打开的页面，不需要重开程序。代理启动后会
把**实际使用的**地址与端口写回配置，所以配置页里的代理端口反映真实使用情况。
Python 引擎读取同一份文件；**显式命令行参数始终优先于**存储值。

探测页有 `请求方法` 下拉框（`POST` / `GET` / `PUT` / `PATCH` / `DELETE` / `OPTIONS`，默认 `POST`），
对应 `--probe-method`；接口只从查询串读 JSON 时用 `GET`。`GET` / `HEAD` 探针会把 payload
URL 编码进查询串。

它还有一个 `请求头（JSON）` 输入框（始终可编辑），非空时下发 `--headers`——可用于已登录会话，
例如 `{"Cookie":"JWT_TOKEN=...; JSESSIONID=..."}`。

紧随其后的 `会话 Cookie` 输入框非空时下发 `--session-cookie`。目标把未登录请求转发到登录页时
就该用它：它只表达「这是已登录会话」，并按名字并入 `Cookie` 头。可直接粘贴整行 `Cookie:`；
该值同时以 `session_cookie` 存入配置页，探测页每次打开都会预填。Shiro 页会把它与 `rememberMe`
合并成同一个 `Cookie` 头。

`探测模式` 组有五个互相独立的勾选框（`Fastjson 识别` / `版本识别` / `期望类` / `DNS 探针` /
`CEYE 确认`），默认全部勾选，且都不带 `启用` 前缀。勾选的模式按该固定顺序执行，每段结果以
`===== <模式> =====` 开头；取消勾选即跳过该阶段。输入框可用性跟随对应勾选框：
`业务参数（期望类）` 依赖 `期望类`，`DNSLog 主机` 与 `DNS 等待` 依赖 `DNS 探针`，
`CEYE Filter` 依赖 `CEYE 确认`，因此未勾选的模式不会进入 Python 命令行。

`DNS 探针` 执行 `--mode dns`（映射为 `--dns` / `--no-dns`），`CEYE 确认` 执行 `--mode ceye`
（`--ceye` / `--no-ceye`）。五个勾选框是**并列模式**：勾选的 DNS 探针不会被再次挂到
detect / version / expect 的结果上，五个模式同时执行时也不会重复投递。引擎侧，
CEYE 确认作为附属阶段时仍依赖 DNS 阶段。未填 Token 就执行 `CEYE 确认` 会提前停止并给出
可读提示（而不是引擎异常）——请在配置页填写 CEYE Token，或设置 `CEYE_TOKEN` 环境变量。

## 项目结构

| 路径 | 用途 |
| --- | --- |
| `src/` | 组合根 `Main.java`（301 行，只做装配与切页）、`pom.xml`、本地代理（`proxy/ProxyServer.java`）与 Shiro 模块（`shiro/`） |
| `src/ui/` | 各功能页的**视图**与**行为**分开放：视图 `*Page` / `*Form`（`HomePage` / `ProbePage` / `CapturePage` / `ProxyPage` / `ShiroPage` / `PayloadPage` / `PresetPage` / `ServicePage` / `ConfigPage` / `ConfigForm` / `ToolsUploadPage` / `PayloadToStringPage` / `OobJarPage`），行为 `*Controller`（`NavController` / `ProbeController` / `CaptureController` / `ProxyController` / `ShiroController` / `ConfigController` / `PayloadController` / `PresetController` / `ServiceController` / `ToolsUploadController` / `PayloadToStringController` / `OobJarController` / `WorkbenchPages`），另有样式 `UiKit`、共用链编辑 `ChainEditor`、启动预热 `StartupWarmup` / 启动画面 `StartupSplash`、列式链选择器 `PayloadChainSelector`（载荷生成页的选链主体，不持有链状态）及其协作类 `ChainSelectorSizing`（几何换算）/ `ChainColumn` / `ChainColumnState` / `ChainColumnPanel`（每列的面板与筛选状态）/ `ChainResizeHandle`（竖向拖拽条）/ `ChainColumnFilter`（过滤判定）/ `ChainNodeRenderer`（条目渲染）/ `ChainTagMenu`（标签菜单）、载荷页拆分出的 `PayloadPanels`（面板构建）/ `PayloadColumns`（列换算）/ `PayloadOutputText`（输出文本）/ `PayloadExporter`（导出落盘）、自检门面 `UiHandle` + `WidgetRegistry` |
| `src/payload/` | 通用载荷生成（`PayloadEngine` / `PayloadCatalog` / `PayloadResult`），与 Shiro 等具体功能解耦；功能模板 `JarPreset`（带外 Jar 的包装 × 末端动作）、`ToStringPreset`（toString 链模板）与节点归属规则 `ChainScope` |
| `src/service/` | 恶意服务器（`ServiceManager` / `ServiceSpec` / `ServiceEndpoint` / `ServiceDefaults`）与带外 Jar 托管 `OobJarService`：唯一直接调用 java-chains 服务端适配器的包 |
| `src/preset/` | 内置预设链读取（`PresetCatalogService` / `PresetItem`）：唯一引用上游预设模型的包，纯数据出参 |
| `src/probe/` | 引擎调用与命令行拼装（`ProbeEngine` / `ProbeCommand` / `CaptureBridge`） |
| `src/config/` `src/util/` | `config.properties` 读写；报文解析与平台差异 |
| `python/` | 探测引擎（`fj_probe.py`），打进 JAR |
| `tests/` | Python 单元测试与 Java 界面自检；`TestProcessGuard.java` 在自检退出时清理构建期弹出的计算器进程 |
| `tools/` | 维护辅助脚本：`agent.ps1`（启动角色化 agent）、`dispatch.ps1`（单条派发并自动合并）、`orchestrate.ps1`（一句话目标自动拆解编排）、`watchdog.ps1`（会话停滞看护）、`lib/`（角色矩阵与执行原语）、`audit_boundary.py`（依赖边界审计）、`apply_patch.py` |
| `docs/` | 设计文档（`DESIGN.md`、`DESIGN-shiro.md`、`DESIGN-probe-accuracy.md`、`DESIGN-agents.md`、`DESIGN-modularization.md`、`DESIGN-payload.md`、`DESIGN-services.md`）与多 Agent 职责说明（`AGENT-ROLES.md`）与运行手册（`AGENT-RUNBOOK.md`） |
| `openspec/` | 规格驱动开发：`specs/` 存能力规格，`changes/` 存待办与归档的变更，`config.yaml` 约束 AI 生成规划件 |
| `.agents/` | OpenSpec 生成的 AI 工具指令（本仓库目标工具为 codex） |
| `target/` | Maven 构建输出（经 `src/pom.xml` 写入） |
| `.backups/` | 带时间戳的快照，只保留最近三次 |
| 根目录 | `build.ps1`、`run.ps1`、`README.md`、`README.en.md`、`AGENTS.md`、`AI_REPORT.md`、构建出的 `JavaSecExpToolKit.jar`，以及 `lib/`（运行时依赖）与 `libs-repo/`（java-chains 的离线 Maven 仓库） |

## 多 Agent 并行开发

本仓库定义了角色化的多 Agent 协同开发流程，用于并行推进多个功能而不互相覆盖。
完整说明见 [docs/AGENT-RUNBOOK.md](docs/AGENT-RUNBOOK.md)（运行手册）与
[docs/AGENT-ROLES.md](docs/AGENT-ROLES.md)（职责与写入域）。

```powershell
# 启动一个功能开发 agent：独立 worktree + 独立分支，不影响主仓库
.\tools\agent.ps1 -Role probe -Slug version-blindspot -Task "补齐 1.2.73-1.2.80 的版本识别盲区"

# 并发：在另一个终端同时启动写入域不重叠的角色
.\tools\agent.ps1 -Role traffic -Slug header-tolerance -Task "补强抓包请求头容错解析"

# 只看提示词、不启动
.\tools\agent.ps1 -Role probe -Slug demo -Task "示例任务" -DryRun

# 一句话目标：自动拆解 → 按依赖与写入域分批派发 → 成功后自动合并 → 汇报
.\tools\orchestrate.ps1 -Goal "补齐 fastjson 1.2.73-1.2.80 的版本识别盲区"

# 只拆解并打印计划，不创建 worktree、不启动 agent
.\tools\orchestrate.ps1 -Goal "同上" -DryRun
```

| 角色 | 写入域 | 沙箱 |
| --- | --- | --- |
| 主 agent | 根文档、`openspec/**`、`docs/**`、`tools/**` + 共享内核 `src/config/**`、`src/util/**`、`src/pom.xml` | 可写 |
| probe | `python/fj_probe.py`、`src/probe/Probe*.java` | 可写 |
| exploit | `src/shiro/**`、`src/payload/**`、`src/service/**`、`src/preset/**` | 可写 |
| traffic | `src/proxy/**`、`src/probe/CaptureBridge.java` | 可写 |
| ui | `src/ui/**`、`src/Main.java` | 可写 |
| test | `tests/**` | 可写 |
| supervisor | 无（纯只读审计） | 只读，强制 |
| watchdog | 无（只读判定会话状态） | 只读，强制 |

隔离靠独立 worktree 与分支；agent 只改文件，提交由脚本在沙箱外完成并机械校验写入域。
每个会话都有超时上限；出现静默时会唤起只读看护 agent 读日志判定
是「推进中 / 长等待 / 卡死」，避免把等待耗时命令的正常会话误杀。

并发期间不碰共享资源：`.backups/`、`AI_REPORT.md`、`PROGRESS.md` 与构建产物的收尾
统一由主 agent 合并后执行。

并发不需要多开终端：用 `Start-Process ... -WindowStyle Hidden` 后台启动即可，
前台终端仍可继续下令（实测杀掉一个会话不影响另一个）。
主 agent 不能自己派发子 agent：agent 沙箱无法写 `.git/refs/heads/**`，
`git worktree add` 会报 `cannot lock ref`；派发由沙箱外的 `tools/agent.ps1` 完成。
若想「只给一句任务」，用 `tools/orchestrate.ps1`：它在沙箱外代主 agent 拆解、分批、派发与合并，
并逐步汇报「已合并 / 无需改动 / 需人工处理」。自动合并有硬门槛——
超时、被判卡死、越界改动、无实际提交，任一命中都保留现场交人工。

每次派发结束都会汇报「调用了哪些角色、各自做了什么、改到哪些文件」：
编排器先按角色聚合（每个角色几步、主要工作、产出文件），再给逐步表格；
单条派发也会打印角色、主要工作与产出文件清单。

工具链自身的回归用 `tools\check_agent_tools.ps1`（84 项机械断言），
覆盖参数与变量同名、开关写错导致死代码、缺 BOM、`.gitignore` 未锚定等已实际发生过的缺陷：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check_agent_tools.ps1
```

## 抓包格式直接探测


从代理或 Burp 复制出来的报文，无需改写就能直接探测。`附加请求头` 支持四种写法：

- 每行 `Key: Value`：抓包页「一键发送」自动生成的形式；
- 裸 Cookie 值：`JWT_TOKEN=a; JSESSIONID=b`——抓包页 `cookie-header` 转换目标的
  原始输出，粘贴进来就能用；
- 整行 `Cookie: a=1; b=2`；
- JSON 对象 `{"Cookie":"JWT=x"}`，即界面自产的形式。

同名头会合并而不是互相覆盖，因此抓包到的会话 Cookie 与 `rememberMe`
能共存于同一个 `Cookie` 头；无法识别的内容会给出可读错误，不静默丢弃。

两类请求头会被自动排除，因为它们会让探测**必然失败**：

- `Content-Length` 描述的是原请求的体长，沿用旧值会让目标一直等一个
  永远不会到来的请求体，表现为所有探针超时；
- `Accept-Encoding: gzip` 会让目标压缩响应，而引擎按明文解析，
  会误判成「所有探针响应一致 → 未到达解析器」。转发时会换成 `identity`，
  hop-by-hop 头（`Connection` / `Proxy-Connection` 等）同样不会跟着转发。

未勾选「拦截请求」时，代理页面板展示的就是最近一条流量，此时
`转发到抓包转换` 也能正常导出。`一键发送` 会把 URL、方法、请求头**与
请求体**一起带过去：抓包得到的 POST 体通常就是接口的业务参数，不带上只能拿到校验错误。

## 文件上传

`小工具 → 文件上传` 用于把一个本地文件发到目标上传接口，并把这一次请求的原始响应交回给你：

- `目标 URL` 填上传接口；`选择文件` 打开系统文件对话框（路径框只读，避免填出不存在的路径）；
- `表单字段名` 是目标读取文件的参数名，默认 `file`（不同框架常用 `upload` / `multipartFile`）；
- `附加表单字段（JSON）` 用于补目标要求的普通字段，例如 `{"csrf":"abc"}`；
- `请求头（JSON）` 用于带登录态，例如 `{"Cookie":"JWT=xxx"}`；
- `上传` 只发**一次** multipart POST，不跟随 3xx 跳转；`复制结果` 把报告复制到剪贴板。

请求体由引擎按**原始字节**构造，文件内容不会被文本编解码改写；`Content-Type` 声明的
boundary 与请求体里使用的 boundary 始终一致。单文件上限 8 MB，超限会被跳过并说明原因。

失败路径都给可读结论而不是静默失败：目标 URL 为空、未选择文件、文件不存在或不可读、
大小超限、附加字段不是合法 JSON、连接失败、3xx 跳转、401 / 403、404、5xx。

上传是「内容即结果」的功能，因此报告**始终详细**（与抓包 / 转换一致），不随
`探测报告配置` 的精简 / 详细切换而变化。

```powershell
# 命令行等价用法
python pythonj_probe.py --mode upload --upload-url http://host/upload ^
  --upload-file payload.txt --upload-field file --upload-fields "{\"csrf\":\"abc\"}" --format text
```

## 抓包与格式转换

```powershell
# 发送一次 POST 并保留原始响应，同时导出多种格式
python .\python\fj_probe.py --mode capture --method POST `
  --capture-url http://127.0.0.1:8080/api/json `
  --headers '{"Cookie":"JWT_TOKEN=..."}' --body '{"age":20}' `
  --convert-targets cookie-json,curl

# 离线解析粘贴的原始报文（完全不联网）
python .\python\fj_probe.py --mode convert --pasted-request "POST /api HTTP/1.1`nHost: x`nCookie: a=1" `
  --convert-targets cookie-json,cookie-header
```

## 代理抓包

```powershell
# 从界面启动代理（代理 → 代理抓包，默认端口 8899），再把浏览器代理指向它。
# 监听地址默认是本机联网 IP，可编辑：
#   <本机 IP>:8899   局域网内可访问（默认）
#   127.0.0.1:8899   仅本机   |   0.0.0.0:8899   所有网卡
#   HTTPS 走 CONNECT 隧道，不解密
```

`请求包` 显示当前正在发送的请求（开启拦截时为等待放行的请求），`返回包（放行后捕获）`
显示最近一次放行请求的响应。`转发到抓包转换` 会把当前显示的请求交给抓包转换页，
再用该页的 `填入探测页` 把 URL、会话 Cookie 与请求体带进探测页。

已抓到的请求 / 返回内容在切页后**不会丢失**：离开代理页再回来内容仍在
（占位提示只在文本域为空时写入）。只有 `清空记录` 会清掉它。

抓包页的 `一键发送` 只回填目标页并跳转，**不会自行开跑**——请先确认模式 / 请求体，
再按 `开始探测`（Shiro 页是 `一键检测`）。早期版本会立即发起探测，
在参数尚未确认时就把流量打到目标上。

`--probe-method` 示例：

```powershell
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode detect --probe-method GET
```

探针由 `_send_probes()` 通过有界线程池发送（`PROBE_CONCURRENCY = 4`），
执行时间不再随探针数线性增长。在不可达目标上（超时 3 秒），`detect` 从约 20.6 秒降到约 0.2 秒；
报告只在响应确实非空时打印 `响应:` 行，且已被结论覆盖的提示不会重复。

## Shiro 模块

`主页 → 服务 → Shiro 漏洞利用` 覆盖 rememberMe 识别、字典密钥爆破（内置 1108 条）、
回显链生成与命令执行。链由 [java-chains](https://github.com/vulhub/java-chains) `2.0.0-beta4`
生成，该依赖声明在 `src/pom.xml`；`lib/` 存放运行时 JAR，清单把它加入 `Class-Path`。

该模块会在目标上执行命令，**请仅在你已获授权的场景使用**。`生成 Payload` 只构建 payload
而不投递，便于交给其它工具。加密、爆破与链的细节见
[docs/DESIGN-shiro.md](docs/DESIGN-shiro.md)。

页面上的 `请求体` 会进入检测与利用请求：部分接口必须带业务参数才能走到
rememberMe 解密分支，不带只会拿到校验错误；`GET` / `HEAD` 等方法不发送请求体，
避免被标准库静默降级成 POST。

Shiro 页为**每个动作保留独立回显框**（`指纹检测` / `密钥爆破` / `生成 Payload` / `执行命令`），
因此冗长的爆破日志或命令输出不会覆盖检测结论或已生成的 payload。

## Payload 生成

`主页 → Payload → Payload 生成` 把 java-chains 的「载体 + gadget 节点」能力做成了可视化界面，
用来为各类 Java 漏洞生成利用链载荷。它**只在本地内存里构建载荷**：不发起网络请求，也不写文件。

用法是**点列选链**（对齐网页版 java-chains 的 Generate）：

1. 第一列直接列出全部 28 个**载荷载体**（如 `javanativepayload`、`fastjsonpayload`、`shiropayload`），
   点一个即在右侧展开该载体的第一级节点候选
2. 在第二列点一项，链上就追加这一项，并在右边继续展开下一列；如此逐级往下，
   列内显示的是引擎登记的中文名称（例如「TemplatesImpl加载字节码」），鼠标停上去可看到节点标识
3. **改中间某一层只需一次点击**：点回前面某一列的另一个候选，链即以该列为界重开，
   它之后的节点自动丢弃——不必再靠「删除末节点」反复退
4. 候选上百项时用每列上方的过滤框收敛（按名称或标识匹配；当前已选中的项不会被过滤掉）
5. 节点参数按链自动生成表单（含下拉框选项与必填标记），点 `生成载荷` 得到 Base64

候选节点始终是**引擎自己的**判据（首节点按「载体 + 候选节点」逐个校验，后继节点查引擎的后继表），
界面与本仓库都不内置第二套节点关系表。链文本只读——链只能由点击构建，不能手填。
若某个节点没有后继，就不再展开空列；叶子节点选中后不会再多出一列空列表。

版面按**网页版实测比例**摆放：用 CDP 驱动无头 Chrome 打开上游 `#/Generate/<payload>` 页量到
控制台 `.studio-top-console` 360px、选链区 `.chain-builder-block` 486px，两边之比 360 : 486，
因此上下分栏对齐的是**比例**（选链区约占两块之和的 57.4%）而不是照搬 360px 绝对值。
列表默认 320px、可拖到 160~640px，双击拖拽条在 320 与 480 之间切换，列宽在 300~500px 之间
按容器宽度均分，列放不下时转横向滚动——这几个数与网页版一致。

生成结果给出链序、字节长度、不含正文的摘要与 Base64。`复制` 写入剪贴板，`导出文件` 按
配置页的默认导出目录落盘（文件名带载体与时间戳），`填入抓包页` 把载荷送进 `抓包转换` 的请求体，
可以直接在那里发送。

本页**不替使用者填任何回连地址**：上游对 JNDI / SSRF 一类节点的示例值原样保留，需要什么地址由使用者自己决定。
载体分组表与运行时目录的一致性由 `PayloadCheck` 断言，出现漏登记或多登记会直接失败。

网页版 Generate 的**周边功能一律没做**：暴力矩阵、步进调试生成、分享链、常用链路统计、
保存预设、Tag 筛选与并集/交集匹配、输出反编译与序列化解析。预设链、toString 链与 HTTP 带外 Jar 同为独立页面
（`Payload → 预设链` / `toString 链` / `HTTP 带外 Jar`），恶意服务器页也保留它自己的
「载体分组 + 载体」下拉框（那是发布流程）。

实现上，通用部分在 `src/payload/`（与漏洞类型无关，Shiro 模块只是它的一个使用者），
界面装配在 `src/ui/PayloadPage.java`、`src/ui/PayloadController.java` 与列式选择器
`src/ui/PayloadChainSelector.java`，设计见 [docs/DESIGN-payload.md](docs/DESIGN-payload.md)。

## 预设链

`主页 → Payload → 预设链` 把 java-chains 内置的预设模板（实测 52 条、7 个分类）做成可视化入口：
不用每次从空链自己搭，挑一条现成模板即可出载荷。

- 左侧按**分类**筛选，清单显示预设名与分类；分类候选项来自内置预设文件本身，不是代码里写死的常量
- 右侧展示该预设的**链步骤**（只读，含每步的默认参数）与**可填输入**；
  输入控件形态由预设声明的类型决定——布尔型渲染成勾选框，带候选值的渲染成下拉框，
  不需要使用者去猜哪些拼写合法
- `生成载荷` 在本地构建并给出链序、字节长度、不含正文的摘要与 Base64；
  `复制` 写入剪贴板，`填入抓包页` 送进 `抓包转换` 的请求体
- `发到恶意服务器` 把当前预设的载体、链与已填参数交给服务页，选好服务后直接发布

载体名与节点名在预设里是大驼峰写法、引擎按小写注册，页面会自动转换；
参数按「步骤 id → 节点名」反查后再拼成引擎认识的形式，直接用步骤 id 会被判为未知参数。

## toString 链

`主页 → Payload → toString 链` 专门生成「某个类被 `toString()` 时触发」的利用链，
支持自定义目标类，模板可复制。选链区把「触发」与「后续」拆开：先选**触发节点**
（哪个类被 toString），再选中继节点，末端固定接到字节码执行。

内置 5 条实测可用模板（`ToStringPreset`）：

| 模板 | 触发节点 | 中继 | 依赖 |
| --- | --- | --- | --- |
| `CC3 toString + Jackson` | `CaseInsensitiveMap3.toString` | Jackson | commons-collections:3.x |
| `CC4 toString + Jackson` | `CaseInsensitiveMap4.toString` | Jackson | commons-collections4 |
| `CC3 toString + Fastjson` | `CaseInsensitiveMap3.toString` | Fastjson | fastjson + commons-collections:3.x |
| `EventListenerList toString` | `EventListenerList.toString` | Jackson | jackson-databind |
| `GString compareTo toString` | `GStringCompareTo.toString` | Jackson | groovy + jackson-databind |

链序固定为 **载体 → 触发节点 → 中继 → 字节码执行**：

```
javanativepayload -> <trigger> -> <relay> -> templatesimpl -> bytecodeconvert -> exec
```

触发节点必须是链的**第一个** gadget：早期版本把触发节点漏在模板之外，5 条模板全部报
「链不被引擎认可」。中继只有 `JacksonToString` 与 `FastjsonToString1` 能接上字节码链路；
`XString*` / `XalanXString*` 在 `JreFilter` 下不构成合法链，带 `HighJDK` 后缀的模板需要
额外开放 `java.io` / `java.util`，都不收录——因此**不是**把上游所有 toString 节点都搬了进来，
只保留实测能出载荷的那些。

`自定义目标类` 走 `BytecodeConvert.classNameMode=manual` 与 `BytecodeConvert.className`：
链路末端仍有实际执行动作，类名只决定落地类的名字，不改变触发方式。留空则沿用引擎随机类名。
模板可一键复制，用于在别处二次修改。

节点归属只有**一份规则**（`src/payload/ChainScope.java`，23 个触发节点）：toString 页按它
确定可用触发节点，通用生成页按它把这批触发节点从候选里剔除。过滤只放在候选层，
不放进共用的 `ChainEditor`——否则会连带砍掉恶意服务器页发布 toString 载荷的能力。

## HTTP 带外 Jar

`主页 → Payload → HTTP 带外 Jar` 生成「落地后可被加载执行」的 Jar，并用本机托管出来
给目标拉取。界面分两步：先选**打成什么 Jar**，再选**Jar 落地后做什么**。

包装类型 5 种：

| 类型 | 用途 |
| --- | --- |
| 普通 JAR | 标准包装，可被目标 ClassLoader 直接加载 |
| Charset SPI JAR | 适用于 SpringBoot 写 Jar 落地场景 |
| Groovy SPI JAR | 目标依赖 Groovy 时可被自动加载 |
| SnakeYAML SPI JAR | 目标依赖 SnakeYAML 时可被自动加载 |
| JDBC Driver JAR | 适用于驱动可上传 / 可加载的场景 |

末端动作 5 种：`从 URL 下载并执行`、`执行命令`、`回连 HTTP 请求`、`从 URL 下载文件`、
`DNSLog 探测`。**25 种组合全部实测构建成功，产物全部是合法 Zip（`PK\x03\x04` 魔数）**。

链序固定为 **载体 → 包装 → 字节码转换 → 末端动作**：

```
otherpayload -> <kind> -> bytecodeconvert -> <action>
```

输入框跟随所选动作启用：界面按动作**声明的参数键**决定哪些输入可用（填一个永远不会被
下发的字段比少一个输入框更糟）。空值一律不下发，因为下发空串会把上游默认值覆盖成空
（实测表现为载荷里命令为空）。勾选「写入 `Main-Class`」后产物可直接执行。

托管区给出 `启动托管` / `停止托管`：启动后在输出区给出**可直接复制的回连地址**，
用浏览器或 `curl` 取回即可验证。托管**必须用同一个服务实例**——用另一个实例发布时上游
虽然返回成功，地址却会回落到上游默认端口 50000，那个 URL 打不开；约束收在
`src/service/OobJarService.java` 类内，调用方无法绕过。关闭主窗口会先停止托管再退出。

## 恶意服务器

`主页 → 服务 → 恶意服务器` 真实启停 java-chains 的五类服务端，用于把已构建的载荷发布出去、
让目标回连拉取。**只在本地监听，不主动向任何目标发送载荷。**

| 服务 | 用途 | 默认端口 |
| --- | --- | --- |
| JNDI | LDAP / RMI / HTTP 三件套，配合 JNDI 注入链；需要 HTTPS 回调时启用 LDAPS | LDAP 50389、RMI 50388、HTTP 58080 |
| HTTP | 托管载荷字节，供目标按 URL 拉取 | 50000 |
| TCP | 反序列化载荷的裸 TCP 投递 | 11527 |
| FakeMySQL | 伪装 MySQL 服务端，诱导 JDBC 反序列化 | 3308 |
| JRMP | 监听并回传序列化对象 | 13999 |

用法是三步：选服务 → 填端口（初值来自配置页）→ `启动服务`，然后在下方选载体与链、
`发布载荷`，输出区给出**可直接复制的回连地址**：

- HTTP 给出 `http://<公布地址>:<端口>/<发布标识>`
- TCP / JRMP 给出协议地址，FakeMySQL 给出带用户名的 JDBC URL
- JNDI 上游不返回地址，本工具按已启用端口补出 LDAP / RMI / HTTP 三个入口（多行取第一行填入抓包页）

几处实测约束写在实现里：非 JNDI 服务的端口键必须是 `main`，JNDI 必须用各协议端口键；
LDAPS 需要同时提供 JKS 证书路径，未显式填写端口时**不下发**该端口（否则整个 JNDI 启动会被拒）；
载荷类型必须与协议匹配——JRMP 只收对象形态，JNDI / FakeMySQL 不收文本形态，HTTP / TCP 收字节或文本。

**关闭主窗口会先停止全部服务再退出**：服务一旦启动就真实占用端口，
不释放的话下次启动会撞上「端口被占用」。

实现上，`src/service/` 是本项目唯一直接调用 java-chains 服务端适配器的包（对上层只暴露纯数据类），
界面装配在 `src/ui/ServicePage.java` 与 `src/ui/ServiceController.java`，
设计见 [docs/DESIGN-services.md](docs/DESIGN-services.md)。
**不需要 Spring 或任何 Web 容器**：上游适配器是纯 JDK 实现，实测在 Java 17 下可直接驱动。

## 命令行速查

```powershell
# 识别
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode detect

# 版本区间
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode version

# 期望类（业务参数应贴近真实业务请求）
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode expect --base-body '{"age":20,"name":"Bob"}'

# DNS 探针 + CEYE 确认
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode dns --dnslog-host abc.ceye.io --ceye-token <token>

# 仅做确认
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode ceye --ceye-token <token> --dns-filter fjtest

# 任意模式下关闭可选阶段
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode detect --no-dns --no-ceye
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode dns --dnslog-host abc.ceye.io --no-ceye

# 携带已登录会话 Cookie
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode detect --headers '{"Cookie":"JWT_TOKEN=..."}'

# 用专用参数下发（与 --headers 合并而不是替换）
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode detect --session-cookie "JWT_TOKEN=x; JSESSIONID=y"

# 完整详细报告（默认是精简报告）
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode detect --report detail

# 保留原始结构化结果，而不是渲染后的报告
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode version --format json

# 文件上传（multipart，一次请求；可重复 --upload-file，但当前界面只发一个）
python .\python\fj_probe.py --mode upload --upload-url http://127.0.0.1:8080/upload ^
  --upload-file .\payload.txt --upload-field file --upload-fields '{"csrf":"abc"}' --format text
```

`CEYE_TOKEN` / `CEYE_DOMAIN` / `CEYE_API` 环境变量同样可用。

## 测试

```powershell
python -m unittest discover -s tests
```

Java 界面自检针对已编译的 class 运行，并自行打印断言：

```powershell
# 先构建（产出 target\classes 与 lib\java-chains-cli-2.0.0-beta4.jar），再编译自检
.\build.ps1
E:\java\jdk17\bin\javac.exe -encoding UTF-8 -cp "target\classes;lib\java-chains-cli-2.0.0-beta4.jar" -d target\tmp2 tests\*.java

# 六套自检（--add-opens 用于字节码类 gadget 访问 JDK 内部 xalan 实现，run.ps1 已内置）
$opens = @('--add-opens', 'java.xml/com.sun.org.apache.xalan.internal.xsltc.trax=ALL-UNNAMED',
           '--add-opens', 'java.xml/com.sun.org.apache.xalan.internal.xsltc.runtime=ALL-UNNAMED')
E:\java\jdk17\bin\java.exe @opens -cp "target\tmp2;target\classes;lib\java-chains-cli-2.0.0-beta4.jar" UiNavigationCheck
E:\java\jdk17\bin\java.exe @opens -cp "target\tmp2;target\classes;lib\java-chains-cli-2.0.0-beta4.jar" UiSwitchEndToEndCheck
E:\java\jdk17\bin\java.exe @opens -cp "target\tmp2;target\classes;lib\java-chains-cli-2.0.0-beta4.jar" ProxyServerCheck
```

| 自检 | 覆盖范围 | 是否需要 `--add-opens` |
| --- | --- | --- |
| `UiNavigationCheck` | 侧边栏展开 / 收起、各页面控件与端到端流程（含 toString 链页、带外 Jar 页与新增配置分组），截图写入 `target/ui-check/` | 需要（预设链端到端生成） |
| `UiSwitchEndToEndCheck` | 抓包头经真实 Python 引擎送入探测页；带外 Jar 页真实托管 → HTTP 取回合法 Zip → 停止后端口可再次绑定 | 需要 |
| `UiShiroCheck` | Shiro 页全流程 | 需要 |
| `ShiroCheck` | Shiro 引擎（检测 / 爆破 / 链生成 / 回显） | 需要 |
| `PayloadCheck` | 载荷生成引擎（目录、分组、导航、节点显示名、双形态、失败路径、安全），外加带外 Jar 25 种组合与 toString 触发节点归属（共 100 条断言） | 需要 |
| `ProxyServerCheck` | 代理本身：明文抓包、404、`CONNECT` 隧道字节透传、回调与 `find` / `clear` | 不需要 |

依赖边界自检与工具链自检：

```powershell
python -X utf8 tools\audit_boundary.py        # 包级依赖边界（无环 / 分层 / 通用组件 / 共享内核）
powershell -File tools\check_agent_tools.ps1  # 多 Agent 工具链 91 项机械断言
```

自检会在构建期执行 gadget 参数里的命令（上游 `Clojure` / `Exec` 节点的默认参数值是 `calc`），因此六套入口都会在启动时由 `tests/TestProcessGuard.java` 给现存 `CalculatorApp` 进程拍快照并注册退出钩子，退出时只强制结束快照之后新起的计算器进程，日志里会打印 `[calc-guard]` 一行。若自检结束后仍有残留，说明该入口没有接入守卫。

**请仅在获得明确测试授权的系统上运行。**
