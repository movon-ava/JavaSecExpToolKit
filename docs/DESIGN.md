# JavaSecExpToolKit 设计文档

版本：0.4.0
更新日期：2026-09-19
适用读者：本仓库维护者

---

## 一、目标与范围

`JavaSecExpToolKit` 是一个 Java + Python 的桌面工具，面向**已授权**的安全测试场景，
提供三块能力：

1. **Fastjson 探测**：对 HTTP JSON 接口做指纹识别、版本区间推断、期望类判定，并可附带
   DNS 探针与 CEYE 确认；默认只发送无害请求（合法 JSON、畸形 JSON、无副作用的 `@type`
   标记、仅触发 DNS 查询的探针）。
2. **代理抓包 / 抓包转换**：本地 HTTP 代理、可拦截改包后放行，以及单次抓包与报文格式转换。
3. **Shiro 漏洞利用**：rememberMe 指纹识别、密钥爆破、回显链与命令执行链投递（见 3.9）。

能力边界与授权要求：

- 探测模式只做识别，不投递 payload；版本区间存在盲区（见「已知限制」）。
- Shiro 模块属于**利用**功能：它会在目标上执行命令。仅可在取得书面授权的目标上使用，
  且使用者需自行确认授权范围。
- 工具不写入目标文件、不驻留进程、不生成内存马；投递的链只做一次命令回显。

---

## 二、总体架构

```
┌──────────────────────────────────────────┐        ┌──────────────────────────────┐
│ Java Swing 界面 (src/Main.java)           │        │ Python 探测引擎               │
│                                           │        │ (python/fj_probe.py)          │
│ · 侧边栏导航（主页 / 配置 / 代理 /         │        │ · detect  指纹识别            │
│   FastJson / Shiro）                      │        │ · version 版本区间            │
│ · 代理分组 = 代理抓包 + 抓包转换          │        │ · expect  期望类              │
│ · 代理抓包页（本地 HTTP 代理 + 拦截改包） │ 子进程  │ · dns     DNS 探针            │
│ · 抓包转换页（单次抓包 / 格式转换）       │ ────► │ · ceye    CEYE 确认           │
│ · 探测表单（模式勾选 + 参数）             │ stdout │ · capture 单次抓包            │
│ · 结果区（引擎渲染的中文报告）            │        │ · convert 格式转换            │
│ · 配置页（写入用户目录）                  │        └──────────────────────────────┘
│ · Shiro 分组 = Shiro 漏洞利用             │
└──────────────────────────────────────────┘        ┌──────────────────────────────┐
        ▲                                            │ Shiro 利用引擎（Java 原生）   │
        │ 读取 %USERPROFILE%\.JavaSecExpToolKit\      │ src/shiro/ShiroEngine.java    │
        │                              config.properties│ src/shiro/ShiroExploit.java  │
        └────────────────────────────────────────────►│ src/shiro/ChainsEngine.java   │
                                                      │  └─ java-chains 2.0.0-beta4   │
                                                      └──────────────────────────────┘
```

设计取舍：

- **界面与引擎解耦**：Java 只负责收集参数、拼接命令行、展示结果；所有探测逻辑集中在
  `fj_probe.py`，使引擎可脱离界面单独用 CLI 或单元测试驱动。
- **Shiro 模块走 Java 原生路径**：Shiro 的 AES-CBC/GCM 加解密与 rememberMe 投递全部在本进程
 完成，链生成交给 `java-chains`（见 3.9），不再经由 Python 子进程。
- **Java 侧按功能分层**：源码不再堆在单个文件里，根目录只保留 `Main.java`（界面壳：
  导航、事件接线、跨页状态），其余按职责落到各自包中：

  | 包 | 文件 | 职责 |
  | --- | --- | --- |
  | `ui/` | `UiKit` / `NavItem` / `NavigationRenderer` | 样式与配色的唯一来源、导航模型与渲染 |
  | `ui/` | `HomePage` / `ProbePage` / `CapturePage` / `ProxyPage` / `ShiroPage` / `ConfigPage` | 各功能页的视图（只搭结构、不持有状态） |
  | `ui/` | `FlowRenderer` | 代理流量的只读渲染 |
  | `probe/` | `ProbeEngine` / `ProbeCommand` / `CaptureBridge` | 引擎调用、命令行拼装、抓包结果到探测参数的桥接 |
  | `config/` | `AppConfig` | `config.properties` 的读写 |
  | `util/` | `HttpText` / `JsonText` / `Platform` | 报文解析、JSON 取值、平台差异 |
  | `proxy/` `shiro/` | `ProxyServer` / `ShiroEngine` / `ShiroExploit` / `ChainsEngine` | 代理与 Shiro 引擎（本就独立） |

  页面类只接收控件与回调（`Widgets` + `Runnable` / `Consumer`），行为一律留给 `Main`
  里的事件方法，因此视图可单独替换、也能被自检直接断言。
- **JAR 自带引擎**：`python/fj_probe.py` 作为资源打包进 JAR，运行时解压到临时文件再调用，
  避免依赖部署目录结构。
- **结果渲染下沉到引擎**：`fj_probe.py` 提供 `format_report()`，把结果 JSON 渲染成带分段标题、
  结论摘要、探针明细、提示与已知限制的中文报告；Java 只负责按固定顺序拼接各模式的报告文本。
  CLI 仍保留 `--format json`，供脚本与回归测试取结构化结果。

---

## 三、探测模式设计

### 3.1 模式与勾选组

探测页的 `探测模式` 是一组**五个独立勾选框**，默认全部勾选：

| 勾选框文字 | 引擎 mode | 作用 |
| --- | --- | --- |
| `Fastjson 识别` | `detect` | 识别 Fastjson 并与 Jackson / Gson / org.json / Hutool 区分 |
| `版本识别` | `version` | AutoType 状态、回显的 `fastjson-version`、离线布尔探针（`version_detail` / `version_range`） |
| `期望类` | `expect` | 判断接口是否绑定了期望的 Java 类（而非 Map） |
| `DNS 探针` | `dns` | 往 DNSLog / CEYE 域投递无害 `@type` 探针，观察解析行为 |
| `CEYE 确认` | `ceye` | 通过 CEYE API 查询 filter 命中的 DNS 记录 |

`代理` 分类下的 `抓包转换` 页另有两个**页面级 mode**，不参与探测页勾选，
也不会被 `开始探测` 触发：

| 页面按钮 | 引擎 mode | 作用 |
| --- | --- | --- |
| `抓包` | `capture` | 按指定方法发送一次请求，记录原始响应（不跟随 30x）并导出多种格式 |
| `解析并转换` | `convert` | 离线解析粘贴的原始请求，导出 Cookie / cURL / 原始报文 / JSON |

设计要点：

- 文字**不加「启用」前缀**，勾选即代表执行；五个模式互不排斥，可任意组合。
- 执行顺序固定为 `detect → version → expect → dns → ceye`，与勾选先后无关，保证结果可复现。
- 多模式结果按 `===== <模式名> =====` 分段；单模式不加标题，保持原有输出格式不变。
- 未勾选任何模式时直接提示「请至少勾选一个探测模式」，不启动子进程。
- 勾选状态联动输入框可用性：`期望类` → `业务参数（期望类）`，`DNS 探针` → `DNSLog 主机` /
  `DNS 等待`，`CEYE 确认` → `CEYE Filter`；取消勾选后对应输入框禁用。

### 3.2 DNS 探针 / CEYE 确认的模式语义

DNS 探针与 CEYE 确认**已并入上述勾选组**，与 `detect` / `version` / `expect` 同等对待：

- 下发展开：`DNS 探针` ↔ `--dns` / `--no-dns`，`CEYE 确认` ↔ `--ceye` / `--no-ceye`。
- 单独勾选时，与 `detect` / `version` / `expect` 一样可作为独立 mode 执行
  （`--mode dns` / `--mode ceye`）。
- 两个模式默认**勾选**；取消勾选时其参数（`DNSLog 主机`、`DNS 等待`、`CEYE Filter`）
  在界面上禁用，且**不进入命令行**，避免未勾选的探测仍产生网络行为。
- CEYE 确认依赖 DNS 阶段：DNS 关闭时 CEYE 一并跳过，并在 `notes` 中写明原因。
- 两阶段与 `detect` / `version` / `expect` 平级、互斥地各自成为独立 mode；界面不会把 DNS/CEYE
  附加到识别、版本、期望类结果里，避免五项全选时同一探针被重复投递。

### 3.3 引擎侧开关语义

`fj_probe.py` 通过 `_stage_flags(ctx)` 统一读取 `dns_enabled` / `ceye_enabled`，
CLI 用 `--dns/--no-dns` 与 `--ceye/--no-ceye` 成对参数（`set_defaults` 默认开启）映射。
跳过 DNS / CEYE 阶段时不会抛异常中断主结果，而是回填 `stages` 与 `notes` 说明原因。
Java 侧在单独执行 `CEYE 确认` 且未发现 Token（配置项或 `CEYE_TOKEN` / `FJ_CEYE_TOKEN`）
时提前返回可读提示，不把引擎的 `ValueError` 直接抛到结果区。

### 3.4 传输层失败判定（避免假阳性）

**背景（根因）**：`version` 模式的布尔探针原本用「HTTP 状态码 >= 400 即视为解析器报错」
判定 `errored`。当目标 URL 实际是只接受 GET 的登录页时，所有 POST 都返回
`405 Allow: GET, HEAD` 且响应体为空，11 个探针于是全部被判为「报错」，
`_infer_version()` 由 `offline_*` 组合推出 `1.2.70-1.2.80` —— 与 `detect` 的
「非 Fastjson」结论自相矛盾，属于**假阳性**。

**判定规则**：`_transport_block(pairs)` 统一回答「探针是否根本没到达 JSON 解析器」，
`detect` / `version` / `expect` 三个模式共用：

| 条件 | 结论 |
| --- | --- |
| 全部探针连接失败（`status is None`） | 判定为传输层失败，原因取首个连接错误 |
| 连接失败与 HTTP 状态码混合 | 不判定，交回既有逻辑（避免误杀部分可达目标） |
| 任一响应体命中任一解析器特征 | 不判定（已证实解析器可达） |
| 状态码集合 ⊆ `INFRA_STATUSES`（404/405/406/415/429/500/501/502/503/504） | 判定为传输层失败 |
| 全部状态码 >= 400 且响应体全为空 | 判定为传输层失败 |

命中后的处理：

- `detect`：`is_fastjson=False`、`confidence=0.0`，结论改为
  「无法探测：<原因>。<提示>」，`notes` 给出可操作提示。
- `version`：`reported_version` / `version_detail` / `version_range` 全部置 `None`、
  置信度 `0.0`、AutoType 与 SafeMode 置「未判定」，结论改为「版本未能收敛：<原因>」。
- `expect`：`has_expect_class` 保持 `None`、置信度 `0.0`，结论改为「未能完成探测：<原因>」。

提示语 `TRANSPORT_BLOCK_HINT` 引导用户核对三件事：URL 是否指向真正的 JSON 接口、
请求方法是否为 POST、接口是否需要登录会话（可把 Cookie 作为请求头传入）。
配套地，`_infer_version()` 在四个离线探针没有定论时不再给出 `0.9` 置信度。

### 3.5 结果渲染

`format_report(result, mode, detail=False)` 按模式与**详细度**选择渲染器。详细度由
`--report brief|detail` 控制，**默认 `brief`**（界面固定使用精简报告）：

- **精简报告（默认）**：每段只给「结论首句 + 关键判定」，不留段间空行。实测五个模式
  一起跑合计 16 行，正好落在探测页结果区的可视高度内，做到一屏看完。判定字段做同类合并：
  识别给「是否 Fastjson / 置信度」；版本给「可能版本 + 回显版本 + PoC 档位」一行、
  「AutoType + SafeMode + 置信度」一行；期望类合并在同一行内；DNS 给「主机 + filter + 命中」。
  登录拦截单独成行（`login_gate` / `login_gate_with_session_cookie` 两个字段），
  因为它决定使用者下一步是补 Cookie 还是先登录，不能随结论尾句一起被截掉。
- **详细报告（`detail=True`）**：完整结构，供排查假阳性使用。

详细报告的结构如下：

1. `===== <模式名> =====` 标题与 `目标:` 行；
2. `探测结论:` **第一分句**（结论本身），紧随其后一行缩进 10 空格的**原因与建议**
   （`_wrap_conclusion()` 按 `。` 再按 `；` 在 8~120 字区间切分；引擎 JSON 里的
   `summary` 字段保持完整不变，只影响渲染），随后是 `探测方法:`（实际生效的 HTTP 方法）
   与该模式的关键字段
   （识别：是否 Fastjson / 置信度 / 指纹得分；
   版本：回显版本 / 布尔探针区间 / PoC 档位 / 置信度 / AutoType / SafeMode；
   期望类：是否存在期望类 / 期望类型非 Map / 置信度）；
3. `探针明细:` 逐条列出探针名、状态（`HTTP <code>` 或「连接失败」）与命中特征；
   **只有响应正文或错误确实非空时**才附带的缩进行（响应单行折叠并截断到 220 字），
   最多 12 条，多出的以「其余 N 条探针已省略（均为无特征响应）」收尾；
4. `提示:` 列出 `notes`；`阶段:` 汇总 DNS / CEYE 是否执行；
5. `已知限制:` 列出该模式的 `limitations`。

错误结果（含 `error` 字段）渲染为单行「探测失败: <error>」。Java 结果区直接展示该报告，
因此探测页不再出现整行原始 JSON。

抓包 / 转换模式例外：它们本身就是「内容即结果」的功能，**始终按详细方式渲染**，
否则导出格式与响应体会被精简掉。

**本轮针对「回显内容过多」的两处收敛**

- **空响应不再逐条打印**：绝大多数探针的响应体是空的（405 / 403 / 统一错误页等），
  过去每条探针都固定打印一行`响应: （空响应）`，结果区大半篇幅都在重复同一句话，
  真正的结论反而被淹没。现在改为只在有内容时附行；`_status_text()` 仍逐条给出状态，
  使用者可以确认「探针确实跑过」。实测识别模式报告由 38 行降到 28 行。
- **`notes` 与 `summary` 去重**：跳过 DNS / CEYE 阶段时，引擎会把同一条原因分别写进
  `summary` 与 `notes`（JSON 里两处都保留，便于程序化消费）。渲染时 `_dedupe_notes()`
  按前 12 字比对丢掉重复项，避免连续读到两段一模一样的文字；`notes` 字段本身不变。
- **识别模式的 `limitations` 由三条合并为一条**：三条原文都在说同一件事（结果只是远程
  指纹、仅限授权目标、不执行利用链），合并后结果区少两行噪声。

---

### 3.6 抓包与转换（辅助工具）

抓包转换与代理抓包同属左侧导航的 `代理` 分类（二级项 `代理抓包` / `抓包转换`），
解决两个实际问题：目标接口需要登录时拿不到会话 Cookie；以及需要把某个请求复用到别的工具里。

**抓包（mode=`capture`）**

- 按用户选定的方法（GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS）发送**一次**请求，不做变形、不重复投递。
- **不跟随 30x 跳转**：`_NoRedirect` 拦截重定向，保留原始响应与 `Location`，
  方便观察登录流程（例如`302 -> /index`）。
- 记录请求与响应双向信息：方法、URL、请求头、请求体、状态码、耗时、
  响应头、响应体（截断 1 MB）。
- `Set-Cookie` 会被单独解析成**响应 Cookie 对**；请求头里的 `Cookie` 同样会被拆分，
  便于比对“带什么 Cookie 去、回什么 Cookie”。
- 若响应体为空或状态为 30x，`notes` 会给出可操作提示（如“该路径可能只接受其他方法”、
  “请把会话 Cookie 填入请求头”）。

**格式转换（`convert_report`）**

转换在本地完成，不访问任何目标，支持七种输出：

| 格式 | 内容 |
| --- | --- |
| `json` | 结构化对象（url / method / headers / content_type / query / cookies / body） |
| `curl` | 可直接粘贴执行的 `curl` 命令（含请求头与 `--data-raw`） |
| `raw` | 重建的 HTTP/1.1 原始请求文本（补 `Host` / `Content-Length`） |
| `cookie-header` | `a=1; b=2` 形式，可直接填入探测页的 `Cookie` 请求头 |
| `cookie-json` | Cookie 的 JSON 对象，便于程序化处理或填入 `请求头（JSON）` |
| `cookie-netscape` | Netscape 格式的 Cookie 文件，可直接给 curl / 浏览器扩展导入 |
| 其他值 | 回退为 `json` |

**粘贴解析（mode=`convert`）**

- `parse_pasted_request()` 解析“请求行 + 请求头 + 空行 + 请求体”的原始报文（兼容 CRLF / LF）。
- 相对路径会结合 `Host` 头补全为绝对 URL（`_absolute_url()`），使转换结果可直接复用。
- 转换模式**可完全离线**：允许 target 为空，适合把 Burp / 浏览器里拷来的请求直接转成工具链需要的格式。

**与其它功能的衔接（一键发送）**

抓包页提供两个出口，面向两类使用者：

| 出口 | 行为 |
| --- | --- |
| `填入探测页` | 把 URL / 方法 / 请求头 / 请求体回填到 Fastjson 探测页并跳转，**不自动开跑**，留出调整模式的时间 |
| `一键发送` | 选择目标后一次性「回填 + 跳转」，把抓到的请求整套搬给目标功能，**同样不自动开跑**，确认参数后由使用者自己点「开始探测」 |

> 本轮修复：`一键发送` 原先会紧接着调用 `startDetection()` / `startShiroDetect()`，
> 抓完包一按就自动把流量打到目标上。但抓到的报文往往还要改探测模式、业务参数或期望类，
> 参数没确认就发请求既浪费一次往返，也在页面上留下了「我还没点就在跑」的困惑。
> 现在只做回填与跳转，状态栏提示「已发送到 ……，确认参数后点『开始探测』」，
> 探测按钮保持可用。**自动探测的入口只剩页面上的「开始探测」/「一键检测」两个按钮**。

`一键发送` 的目标由 `captureSendTo` 下拉框给出（当前为 `Fastjson 探测` / `Shiro 漏洞利用`），
分派入口是 `sendCaptureTo()`，两个目标各自一个私有方法：

- **Fastjson 探测**：`sendCaptureToFastjson()` 回填 URL / 方法 / 请求头 / 请求体后跳转到
  探测页。请求头优先用抓包页填写的 JSON；只有从旧报告里拿到 Cookie 时才用
  `headerJsonOf()` 包一层 `{"Cookie":"..."}`。
- **Shiro 漏洞利用**：`sendCaptureToShiro()` 回填 URL / 方法与「附加请求头」后跳转到
  Shiro 页。请求头要换一种格式——Shiro 页用的是**每行一个 `Key: Value`**，
  由 `shiroHeaderLines()` 从抓包页的 JSON 请求头或粘贴报文转换而来，并跳过 `Host` /
  `Content-Length` / `Proxy-Connection` 这些不该由调用方指定的头。

两处请求头解析都走界面内部的 `flatJsonObject()`：只处理「对象里每个值都是字符串」这一种
形态，支持 `\" \\ \n \r \t` 转义，**刻意不引入 JSON 库**（AGENTS.md 禁止新增依赖，
而这里的输入是工具自己刚生成或使用者直接粘贴的简单对象）。

落地的闭环是「浏览器登录 -> 抓包 / 代理抓到会话 Cookie -> 一键发送 -> 带会话探测」。
其中 `ShiroEngine` 侧需要配合：`parseHeaders()` 对**同名头做合并**而不是互相覆盖
（大小写不敏感），`send()` 把会话 Cookie 与 rememberMe 拼成**同一个 `Cookie` 头**发出
（`HttpURLConnection` 对同名头是覆盖语义，分两次 `set` 只会留下最后一个），
因此需要登录态的 Shiro 目标也能被正确检测。

---

### 3.7 请求方法与「静态页不是解析器」

**背景（根因）**：探测最初只发 POST。遇到只接受其他方法（或只接受 GET 的登录页）的接口时，
全部探针被 HTTP 层拒绝（典型 405），既拿不到解析器特征，也无法区分「路径不对」与「方法不对」。
同时，把任何 HTTP 200 都当作「探针成功」会把一张静态登录页误判成 Fastjson
（实测靶场 `GET /index/fastjson` 返回同一份带 `jsessionid` 的页面，曾得到 `confidence=0.139`）。

**请求方法可切换**：探测页新增 `请求方法` 下拉（`POST` / `GET` / `PUT` / `PATCH` / `DELETE` / `OPTIONS`，
默认 `POST`），通过 `--probe-method` 传给引擎；配置页可保存 `probe_method` 作为默认值。
`GET` / `HEAD` 没有请求体，引擎把探针 JSON **URL 编码**后放进查询串
（不编码会因 JSON 中的空格与引号触发 `http.client.InvalidURL`），其余方法按原样发送请求体。

**405 自动换方法**：`run()` 对 `detect` / `version` / `expect` 三个模式包了一层
`_probe_with_method_fallback()`：

1. 先按当前方法探测一轮；
2. 若 `_transport_block()` 判定「全部探针都被 HTTP 层整体拒绝」且没有任何解析器特征，
   依次改用 `POST → GET → PUT → PATCH`（跳过已用过的）重试；
3. 换方法后只要**有任一探针状态码 < 400**，说明请求已经到达应用层，立即停止重试
   （避免对同一个接口反复投递）；此时若响应确实随 payload 变化，则采用该轮结果，
   并在 `notes` / `summary` 中写明「首次 X 被拒绝，改用 Y」；
4. 所有方法都被整体拒绝时，把 `request_method_tried` 写进结果，
   结论改为「……（已尝试 POST、GET，均未拿到解析器响应）」。

**静态页判定**：`_uniform_response_block(pairs)` 在所有探针都没命中任何解析器特征时，
比较各探针响应体的**最长公共前缀**；若前缀占最短响应体的 90% 以上，判定为
「所有探针返回同一份响应」，`is_fastjson=False`、`confidence=0.0`。

比对前先做 `_normalize_volatile()`：把 `;jsessionid=...`、`JSESSIONID=...`、长十六进制串
替换为占位符。**容器会在页面每个链接后重写 `jsessionid`**，同一张静态页逐字节比对永远不等，
不归一化就会把静态页误判成「响应随 payload 变化」，这一步是判定成立的前提。

---

#### 3.7.1 探针并发（探测耗时）

**根因（先诊断后动手）**：原先每条探针都串行发送，一轮探测的总耗时 ≈ 探针数 × 单次请求耗时。
正常目标上单次往返只有几十毫秒，所以「8 条探针 456 ms」看不出问题；但一旦目标不可达或
超时（浏览器插件、防火墙丢包、黑洞地址），每条探针都要耗满 `--timeout`：

| 场景 | 并发前 | 并发后 |
| --- | --- | --- |
| 黑洞地址（timeout 3 s）· 识别模式 8 条探针 | 20581 ms | 169 ms |
| 黑洞地址（timeout 3 s）· 版本模式 11 条探针 | 26648 ms | 8340 ms |
| 真实目标 `211.154.20.67:7779/index/fastjson` · 识别 | 456 ms | 175 ms |

**设计**：`_send_probes(probes, target, timeout, headers, content_type, method)` 用
`ThreadPoolExecutor` 并发发送，并发度取 `PROBE_CONCURRENCY = 4` 与探针数的较小值
（`max(1, min(...))`），四处调用点（`_fingerprint()` / `_version()` / `_expect()` / `_dns()`）
统一走它。三条硬约束：

1. **按原下标回填**：结果列表与传入的探针表一一对应，`evidence` 的顺序、`zip(probes, sent)`
   的解包关系都不受影响（否则表格里「探针名」与「状态」会整体错位）；
2. **并发度有上界**：授权测试场景下不宜把目标打得太猛，4 是「明显缩短等待」与
   「不制造瞬时压力」之间的折中；探针数少于 4 时不额外开线程；
3. **单条异常收敛为失败**：某条探针抛异常只把该条记为「连接失败」，不影响同批其它探针。

> 实现上有一个必须注意的坑：探针表里既有 3 元组 `(id, description, payload)`，
> 也有 4 元组 `(id, category, description, payload)`，**payload 一律在末位**，
> 不能用固定下标 `[3]` 取；`_send_probes` 统一按 `probe[-1]` 解析。
> 这个错误不会让程序崩溃到看不见（会被收敛成「连接失败」），但会让探测结果整体失真，
> 因此 `tests/test_probe.py::ProbeConcurrencyTest` 专门为 3/4 元组混合与保序各写了一条断言。

**并发暴露出的两个既有判定缺陷（已一并修复）**

并发本身只是让请求更密集，却把两个早已存在、只在高频下才复现的
「假信号」缺陷放大成必现，两处都属于**结论可信度**问题，必须和并发一起修：

1. **没拿到响应 ≠ 未报错**：`_version_response_errored()` / `_expect_errored()`
   原先对连接中断返回 `False`，于是「基线正常 + 离线探针不报错」同时成立，
   `_infer_version()` 会给出 `1.2.70-1.2.80`、`confidence=0.8` 这种**假版本区间**。
   改为三态（`True/False/None`）：只要有一条离线探针未知，整体就不收敛；
   `_infer_autotype()` 与 `_expect()` 同理（后者降级为「未能完成探测」）。
2. **`5xx` 与连接失败不算解析差异**：`501/502/503` 是网关或方法层面的整体拒绝，
   与 payload 无关，一律按「未知」处理（但响应体带解析器特征时仍算命中）；
   `_probe_result_varied()` 不再把 `status=None` 计入状态集合，
   否则「同一份 501 错误页 + 一条连接失败」会被判成「响应随 payload 变化」，
   让换方法逻辑误报成功。

> 这两条合起来保证了「并发只改变**耗时**、不改变**结论**」。
> 测试侧由 `tests/test_probe.py::UnknownResponseTest` 锁住（三态语义、5xx 处理、
> 未知探针下的区间抑制、连接失败不影响差异判定）。

---

### 3.8 代理抓包（本地 HTTP 代理）

与「抓包转换」的单次抓包不同，代理抓包是一个持续运行的**本地 HTTP 代理**，
把浏览器或浏览器插件的流量指过来即可实时看到请求与响应，对应 Burp 的工作方式。
实现为纯 JDK 的 `src/proxy/ProxyServer.java`，不引入任何第三方依赖。

**监听与线程模型**

- `start(bindHost, port)` 绑定指定监听地址，**默认取本机联网 IP**（默认端口 `8899`，
  `0` 表示随机端口）；`start(port)` 保留为显式绑定 `127.0.0.1` 的等价形式。
- 监听地址在界面可改：留空或点「当前联网 IP」回到本机联网地址，填 `127.0.0.1` 仅本机可用，
  填 `0.0.0.0` 监听所有网卡。`displayHost()` 在绑定 `0.0.0.0` 时回落本机联网地址，
  使状态提示与浏览器代理设置里的地址可以直接照抄。
- `defaultBindHost()` 的探测顺序：先对一个外部地址做 UDP `connect`（只设置路由、
  不发送任何字节）取默认出口 IPv4 → 失败则枚举网卡取首个非回环 IPv4 → 仍失败回落 `127.0.0.1`。
- 一个 accept 线程 + 每连接一个处理线程；`stop()` 关闭监听并让 accept 循环退出。
- 流量记录为 `HttpFlow`，通过 `FlowListener` 回调推给界面；
  内部 `maxFlows` 环形裁剪（界面按 500 条创建），避免长时间抓包占满内存。

**明文 HTTP（完整记录）**

1. 读取请求行与请求头（上限 128 KB）；
2. 按 `Content-Length` 或 `Transfer-Encoding: chunked` 读取请求体，**按原始字节保存**；
3. 连接上游、把请求行改写为 origin-form（去掉绝对形式的 `scheme://host`）后转发；
4. 读取响应头与响应体原样回写，并记录状态码、耗时、响应头、响应体；
5. `1xx` 中间响应（典型 `Expect: 100-continue`）先原样回写，再继续等最终响应；
6. 连接复用由 `isKeepAlive()` 判定：双方都没声明 `close`、响应有明确长度界定
   （`Content-Length` 或 `chunked`）、且 HTTP/1.0 仅在显式 `keep-alive` 时复用；
   `HEAD` / `204` / `304` 视为无响应体。

**HTTPS（只透传，不解密）**

`CONNECT` 请求只做盲转发隧道：与上游建连后回 `200 Connection Established`，
两个方向各起一条 `pumpBytes()` 线程原样转发字节并累加 `tunneledBytes`。
隧道**不解密、不记录内容**，`HttpFlow.captured=false`，列表状态列显示「透传」、
大小列显示已传输字节数，详情区说明这是 HTTPS 隧道。

> 本轮明确**不做** HTTPS 解密：中间人解密需要自签 CA、把 CA 导入浏览器信任链与 TLS 终结逻辑，
> 收益与风险不匹配。浏览器访问 HTTPS 站点经本代理仍可正常继续，只是内容不可见。

**请求拦截（类似 Burp 的 Proxy → Intercept）**

- `Interceptor` 是可选回调：未注册时转发路径零开销，`setInterceptor(null)` 即可关闭。
- 回调在转发**之前**、于连接线程上同步执行；界面在这里阻塞等待使用者决定，因此同一个
  连接不会被并发下发两次。
- `Rewrite` 描述放行时使用的报文：`head` 为 `null` 表示沿用原请求头、`body` 为 `null`
  表示沿用原请求体；`Rewrite.DROP` 表示丢弃——不连接上游、直接关闭客户端连接。
- 放行时若改过请求头，代理会用新的请求头重新解析 `Host` / 方法 / 路径并同步回 `HttpFlow`，
  再改写请求行为 origin-form 发往上游；改过请求体时由界面侧的 `extractHeaderBytes()`
  重算 `Content-Length`，使用者不必自己数长度。
- 等待上限 `PROXY_INTERCEPT_TIMEOUT_MS = 120000`：超时自动放行，避免浏览器一直挂着。

**拦截开关的运行时装卸（本轮修复）**

拦截器**不能只在启动时判断一次**：先启动代理、随后才勾选「拦截请求」是最常见的用法，
只判断一次会导致勾选了却完全不拦截。因此 `updateProxyInterceptState()` 承担两件事：

1. **运行时同步装卸**：代理运行中调用 `setInterceptor(intercepting ? this::interceptRequest : null)`，
   勾选/取消的瞬间即生效，不需要重启代理；
2. **维护 `interceptActive`**：连接线程在等待循环里也要能感知开关被关掉，否则已挂起的
   请求会一直等到 120 秒超时。

关闭拦截时还会调用 `releasePendingUnchanged()`：把正卡在等待里的请求**不改包直接放行**，
避免使用者一取消勾选就把自己浏览器的请求挂死。

**同一时刻只放行一条（排队）**

`interceptRequest()` 在拿到「当前无待放行请求」之前先排队等待：多个连接同时到达时，
如果都往同一个文本域里写，会互相覆盖，使用者看到的与最终放行的内容就不是同一份。
排队后每次只有一条请求停在界面上，其余连接等轮到自己；队伍里的连接同样受
`PROXY_INTERCEPT_TIMEOUT_MS` 约束，超时即原样放行。

**请求包面板的刷新策略（本轮修复）**

- 拦截关闭时：面板是纯观察视图（`setEditable(false)`），`appendProxyFlow()` 直接展示
  最近一条流量的请求包与返回包；
- 拦截开启时：**请求包面板由 `interceptRequest()` 独家负责**——命中即写入待放行报文，
  `appendProxyFlow()` 一刀不写请求包。浏览器在后台还会持续发出心跳、预连接等请求，
  如果每条都往面板里灌，等待放行期间就会被无关报文刷屏，把使用者正在编辑的请求包冲掉，
  这正是「未放行时面板还在刷新」的根因；
- 返回包面板在拦截开启时只认 `awaitingResponseFlowId`（放行或丢弃时记下），
  写完一次即清空归属。这样排队接位的下一条请求不会覆盖刚放行那一条的返回包，
  也保证了「已放行，等待返回包…」期间面板不会先被别人的响应占住；
- 请求包文本域只在拦截开启时可编辑（`setEditable(intercepting)`）：未拦截时即便允许
  编辑，改动也会被下一条流量覆盖，既发不出去也留不住。

> 实现上要注意一个坑：`updateProxyInterceptState()` 里的 `setEditable()` 必须在
> `proxyBodyPanel()` 构建文本域**之后**才调用，因此它从 `proxyControlPanel()` 移到了
> `showProxy()` 末尾（面板全部构建完成的位置），否则会被 `styleProxyTextArea()` 的
> 只读设置覆盖。

**切页返回后内容不丢（本轮修复）**

`showProxy()` 每次进入都会重建整个代理页，而 `styleProxyTextArea()` 原先无条件
`area.setText(placeholder)`，于是「去别的功能看一眼再回到代理页」时，刚捕获的请求包与
返回包会被占位提示覆盖回去。现在两个占位提示抽成 `PROXY_REQUEST_PLACEHOLDER` /
`PROXY_RESPONSE_PLACEHOLDER` 常量，且只在 `area.getText().isEmpty()` 时写入，
文本域自身作为内容载体跨页面重建保留。

> 注意 `proxyStatus`（代理未启动 / 运行中）与按钮文案仍随面板重建回落到初始值，
> 但 `updateProxyInterceptState()` 会按 `proxyServer.isRunning()` 重新同步可用性，
> 已被捕获的报文内容与代理运行状态都不受影响。真正的清空入口只有一个：
> 「清空记录」按钮（`clearProxyFlows()`，它仍会显式写回占位提示）。

**展示层的可读性处理**

代理记录的 `requestRaw` / `responseRaw` 始终是未经修改的原始字节（便于与抓包工具对齐）。
界面只做**只读**渲染：请求包直接还原字节（去掉 `Proxy-Connection`），响应包按
`Transfer-Encoding: chunked` 去分块后再按 UTF-8 解码；`looksBinary()` 判定为二进制时
只显示「二进制内容 N 字节」，避免出现乱码。

**与其它功能的衔接**

代理页「转发到抓包转换」会把当前请求包的 URL、方法、请求头（转成 JSON 对象，自动去掉
`Host`）与请求体填入抓包转换页并跳转过去；随后可用「填入探测页」把 URL / Cookie / 请求体
带进探测页，形成「浏览器登录 → 代理抓到会话 Cookie → 转发 → 带会话探测」的闭环。

---

### 3.9 Shiro 漏洞利用

Shiro 模块位于 `src/shiro/`，是唯一带**利用**性质的模块，接口严格限定在「已授权目标」：

| 类 | 职责 |
| --- | --- |
| `ShiroEngine.java` | rememberMe 加解密（CBC / GCM）、`deleteMe` 指纹识别、密钥字典爆破、请求发送 |
| `ChainsEngine.java` | 对 `java-chains` 的封装：查询节点、参数、预置链模板、构建 payload |
| `ShiroExploit.java` | 编排：生成回显链 / 命令链、投递、取回显 |

**指纹识别**

先发一次不带 `rememberMe` 的基线请求，再发一次带随机 `rememberMe` 的请求：若响应出现
`Set-Cookie: rememberMe=deleteMe`，说明目标会对无法解密的 rememberMe 做删除处理，
即存在 Shiro 的 rememberMe 解密路径。计数差成立时判定「确认存在 Shiro」。

**加解密（`ShiroEngine`）**

- CBC：`AES/CBC/PKCS5Padding`，**IV 取密钥前 16 字节**（Shiro 的既有实现约定）。
- GCM：`AES/GCM/NoPadding`，随机 16 字节 IV 前置在密文前；若运行环境不提供 GCM 则回落
  `PKCS5Padding`，保证在受限 JDK 上仍可用。
- 序列化：`serializeEmptyPrincipal()` 用 `SimplePrincipalCollection` 生成合法载荷，
  避免爆破时因反序列化异常产生假阴性。

**密钥爆破（`crack`）**

- 内置字典 `src/shiro/res/shiro-keys.txt`（1108 条去重常见密钥），随 JAR 打包。
- 多线程并发、命中即停，进度回调驱动进度条；界面「停止」按钮通过 `AtomicBoolean` 打断，
  已发出的连接不会被强杀，只是不再派发新任务。
- 命中后把密钥与 GCM 模式回填界面，后续生成 payload 直接沿用。

**链生成（`ChainsEngine`）**

`java-chains` 2.0.0-beta4（`org.vulhub:java-chains-cli`，本地文件仓库 `libs-repo/`）是链式
payload 生成引擎：先选 payload 载体（`shiropayload`），再依次追加 gadget 节点，
引擎按节点间的 tag 约束校验链合法性。封装层要点：

- 节点 id **全部小写**（`tomcatecho`、`commonscollectionsk1`…），类名查询需先归一化。
- `shiropayload` 参数为 `ShiroPayload.shiroKey` / `ShiroPayload.gcmMode` / `ShiroPayload.dirtyLength`；
  回显头由 `TomcatEcho.header` 指定，命令由 `Exec.cmd` 指定。
- 引擎依赖 JDK 内部 xalan 实现生成字节码 gadget，Java 17 需在启动参数开放
  `java.xml/com.sun.org.apache.xalan.internal.xsltc.trax` 与 `...xsltc.runtime`（见 `run.ps1`）。
- 初始化失败（含模块访问异常）不抛出，而是把原因写进 `statusMessage()`，界面直接展示提示。

**利用编排（`ShiroExploit`）**

提供三条回显链 `CB19` / `CCK1` / `CCK2`（第一条 gadget 不同，末端统一为
`templatesimpl → bytecodeconvert → tomcatecho`），流程为：

1. 生成回显链并投递到 `rememberMe`，在目标上注入回显马；
2. 之后每条命令都是**普通 HTTP 请求**：命令 Base64 后放进约定的回显请求头；
3. 从响应体中提取回显（会剔除 `inptrj` 标记行）。

因此连续执行多条命令不必反复打链，降低了对目标的扰动。

---

### 3.10 登录拦截识别与会话 Cookie

**背景（根因）**：授权靶场 `http://211.154.20.67:7779` 确实存在 Fastjson 漏洞，但工具探测不到。
实测发现靶场的登录拦截器拦下 `/**`，未登录时把请求转发到 `/login`：

| 请求 | 实测响应 |
| --- | --- |
| `POST /vulnapi/Fastjson/vul` | `405` + `Allow: GET, HEAD` + 空响应体 |
| `GET /vulnapi/Fastjson/vul` | `200`，但返回的是登录页 HTML（4442 字节，含"请先登录"） |

旧实现据此得出「该路径只接受 GET」，而换方法拿到的登录页又被
`_uniform_response_block()` 当成「所有探针返回同一份响应」丢掉——两者都没错，
缺的是第三种可能：**目标根本没让请求到达 JSON 解析器，而是把它转发到了登录页**。

**判定（引擎侧）**：

- `LOGIN_PAGE_MARKERS`：`请先登录` / `用户登录` / `name="password"` / `name='password'` /
  `/user/login` / `/captcha` / `记住我` / `remember me`，按**多特征同时命中**识别，
  避免把普通页面误判成登录页。
- `_detect_login_gate(ctx, used_session_cookie)`：在探针被整层拒绝、且没有任何
  `matched` 特征时，**单独补发一条不带 payload 的 `GET`**，用**完整响应体**判断登录页。
  必须用完整响应体：登录标记出现在正文 2300 字节之后，而 `evidence` 里的
  `response_excerpt` 只截 500 字，从截断内容里永远看不出是登录页。
- `_annotate_login_gate(ctx, result)`：只改 `summary` / `notes` 并写入 `login_gate=True`，
  **不动 `evidence`**（判定来自补投的 GET，不属于探针明细）。三个模式共用统一前缀：

  | 模式 | 结论前缀 |
  | --- | --- |
  | `detect` | `无法探测：` |
  | `version` | `版本未能收敛：` |
  | `expect` | `未能完成探测：` |

- 已带会话 Cookie 仍被拦时，结论追加「已携带会话 Cookie 仍被拦截，Cookie 可能已过期或权限不足」，
  便于区分「没填 Cookie」与「Cookie 失效」。

**会话 Cookie（引擎 + 界面 + 配置）**：

- CLI 新增 `--session-cookie`；`_clean_session_cookie()` 允许直接粘贴 `Cookie: a=1; b=2` 整行。
- `_merge_session_cookie(headers, session_cookie)` 把会话 Cookie 并入请求头，
  **同名 Cookie 不覆盖**、缺的按名字补齐：抓到的 `JWT_TOKEN` 与配置里的 `JSESSIONID`
  各自只出现一次，合并后仍是同一个 `Cookie` 头。
- 探测页新增 `会话 Cookie` 输入框（位于 `请求头（JSON）` 之后），配置页 `FastJson 配置`
  组新增 `会话 Cookie` 行（键名 `session_cookie`），两者都走 `--session-cookie` 下发。
  刻意做成独立输入框而不是要求写进「请求头」JSON：会话 Cookie 是最常变、最需要
  从浏览器复制粘贴的一项，单独一栏才能配合「代理抓包 → 一键发送」直接回填。

**闭环**：浏览器登录 → 代理抓包 / 抓包转换拿到会话 Cookie → 一键发送（或填入探测页）
→ 带会话 Cookie 探测。未携带会话时工具明确报「登录拦截」，不再给出「只接受 GET」这种
指向错误方向的结论。

---

## 四、进程间通信

### 4.1 调用方式

Java 通过 `ProcessBuilder` 以命令行参数调用 Python：

```
python <解压后的 fj_probe.py> <target> --timeout <秒> --mode <mode>
       [--headers <JSON 对象>] [--base-body <JSON>]
       [--session-cookie <a=1; b=2>]
       [--dnslog-host <主机>] [--dns-filter <filter>]
       [--dns-wait <秒>] [--ceye-token <token>] [--ceye-domain <域名>]
       [--dns|--no-dns] [--ceye|--no-ceye] [--format json|text]
       [--probe-method POST|GET|PUT|PATCH|DELETE|OPTIONS]
       [--method GET|POST|...] [--capture-url <url>] [--body <文本>]
       [--pasted-request <原始报文>] [--convert-targets <逗号分隔格式>]
       [--query <JSON>]
```

- 引擎路径：`extractProbe()` 把 JAR 内 `/python/fj_probe.py` 解压到临时文件（`deleteOnExit`）。
- Python 解释器解析顺序：环境变量 `FJ_PYTHON` → 配置项 `python` → `PATH` 中的 `python`。
- 输出编码：设置 `PYTHONIOENCODING=utf-8`，Java 按 UTF-8 读取，避免中文乱码。
- 错误流与标准流合并，保证异常信息也能显示在结果区。

### 4.2 Windows 参数转义（关键约束）

**问题**：Java 在 Windows 上为含空格或引号的参数补外层引号，但**不转义参数内部的引号**，
C 运行时会把内层引号当分隔符吃掉。表现为 `{"age":20}` 传到 Python 时变成 `{age:20}`，
`期望类` 模式必然报「base_body 不是合法 JSON」。

**方案**：`windowsArg(String)` 按 MSVCRT 规则自行完成转义后再交给 `ProcessBuilder`：

- 连续反斜杠仅在「其后紧跟引号」或「位于参数结尾」时需要翻倍；
- 引号本身前置 `\`，并按前置反斜杠数量追加 `2n` 个 `\`；
- 非 Windows 平台直接返回原值，不做任何处理。

需要转义的参数：脚本路径、目标 URL、`headers`、`base_body`、`dnslog_host`、`dns_filter`、
`ceye_token`、`ceye_domain`。纯数字（`--timeout`、`--dns-wait`）与固定开关（含
`--format text`）不需转义。

`--headers` 接收 JSON 对象字符串（如 `{"Cookie":"JWT=xxx; JSESSIONID=yyy"}`），
用于把已登录会话 Cookie 或自定义请求头带给目标；缺省不传，行为与既有版本一致。

### 4.3 约定

- 引擎把结果打印到 stdout：默认 `--format json` 输出结构化 JSON，`--format text` 输出
  渲染后的中文报告；进程返回码非 0 时 Java 也会展示已捕获内容。
- Java 侧不对 JSON 做解析，只按固定顺序拼接各模式报告，避免双份解析逻辑。
- 界面调用固定使用 `--format text`，因此结果区展示的是可读报告；需要结构化结果时改用
  CLI 的 `--format json`。

---

## 五、配置

配置以明文保存在 `%USERPROFILE%\.JavaSecExpToolKit\config.properties`：

- `通用配置`：`python`、`timeout`、`probe_method`
- `FastJson 配置`：`ceye_domain`、`ceye_token`、`ceye_api`、`dns_wait`、
  `base_body`、`headers`、`session_cookie`、`dnslog_host`、`dns_filter`
- `探测报告配置`：`probe_report`（`brief` / `detail`，默认 `brief`）
- `代理配置`：`proxy_bind_host`、`proxy_port`、`proxy_intercept`
- `抓包转换配置`：`capture_method`、`capture_content_type`、`capture_target`
- `Shiro 配置`：`shiro_url`、`shiro_cookie_name`、`shiro_key`、`shiro_gcm`、
  `shiro_echo_header`、`shiro_chain`、`shiro_command`

「可长期保存的配置放进配置页」是硬性要求：代理端口、Shiro 密钥、抓包默认方法这类
每次都要重填的值都必须有配置项，否则只能写死在代码里。

规则：

- 界面「配置」页保存后回填**全部功能页**（探测页 + 代理页 + 抓包页 + Shiro 页），
  统一下发入口是 `applyConfigToForms()`，启动与保存后各调用一次。
- 代理启动成功后会通过 `rememberProxyEndpoint()` 把**实际使用的**地址与端口写回
  `proxy_bind_host` / `proxy_port`，因此配置页里的端口反映真实使用情况而不是摆设；
  端口框留空时按上次成功监听的端口，其次回落到 8899。
- Python 引擎也会读取同一文件。
- 显式命令行参数优先于配置值；探测页 `DNSLog 主机` 在未单独填写时回落到 `ceye_domain`。
- Token 为明文存储，属于已知限制，需用户自行保护。

---

## 六、界面约定

- **导航**：侧边栏为五个一级项 `主页` / `配置` / `代理`（二级 `代理抓包` / `抓包转换`）/
  `FastJson`（二级 `Fastjson 探测`）/ `Shiro`（二级 `Shiro 漏洞利用`）；一级分类可展开/收起
  二级功能，`▾` / `▸` 箭头固定在整行最右侧、文字靠左；点击分类行切换展开状态，
  跳转二级功能会自动展开所在分组。展开状态属于各分组自身：重建导航列表时保留每个已展开
  分组的子项，因此展开一个分组不会把另一个已展开分组的子项丢掉。
  二级项的缩进由 `NavItem.isChild()` 判定，实现为 `parent != null`（按层级而不是按 key
  是否含点号），保证 key 为 `capture` 这类无点号的子项与其它子项对齐。
- **缩放**：字体与侧边栏宽度按窗口尺寸相对 1440×900 基准等比缩放，限制在 0.85–1.45 倍。
- **结果区**：只读文本域，自动换行；占位提示为「勾选探测模式，输入授权的 JSON 反序列化接口，
  然后点击"开始探测"」。
- **探测页布局**：与 Shiro 页一致，采用上下分栏。上栏是字段表单（放在 `JScrollPane`
  中，窗口偏矮时可滚动）+ **固定在滚动区外**的操作行（`开始探测` 按钮与状态文字），
  下栏是结果区。按钮若跟着表单一起滚走，界面看起来就像没有执行入口；分栏比例
  `SPLIT_RATIO = 0.38`（表单占 38%），默认 1280×800 窗口下结果区约 16 行可视，
  刚够五模式精简报告（16 行）。字段区高度上限 300px，超出部分滚动而不挤占结果区。
- **探测表单字段自上而下**：`探测模式` → `请求方法` → `目标 URL` → `超时（秒）` →
  `业务参数（期望类）` → `请求头（JSON）` → `DNSLog 主机` → `CEYE Filter` → `DNS 等待（秒）`。
  `请求头（JSON）` 始终可编辑（不随模式勾选联动），留空时不传 `--headers`。
  `会话 Cookie` 紧随 `请求头（JSON）` 之后，同样始终可编辑，留空时不传 `--session-cookie`；
  它是「登录拦截」场景的推荐填法（见 3.10）。
- **探测按钮**：执行期间禁用，状态栏显示「探测中...」/「探测完成」。
- **抓包页**：请求方法下拉、目标 URL、Content-Type、请求头（JSON）、请求体、
  转换目标下拉、粘贴原始请求；按钮为 `抓包` / `解析并转换` / `填入探测页` /
  `一键发送`，`一键发送` 左侧是目标下拉框（`Fastjson 探测` / `Shiro 漏洞利用`），
  结果区右上角提供 `复制结果`。
- **结果区内容**：由引擎渲染的中文报告（见 3.5），多模式时按
  `detect → version → expect → dns → ceye` 顺序依次拼接。报告自带换行，拼接时只在
  缺失换行时补一个，避免每两段之间多出一个空行——五模式就白占 4 行，正好把结果区顶出屏幕。
- **代理页**：第一行是 `监听地址` 输入框（默认本机联网 IP）+ `监听端口` 输入框（默认 8899）
  + `启动代理` / `清空记录` / `转发到抓包转换` 三个按钮 + 状态标签；第二行是
  `拦截请求` 勾选框与 `放行` / `丢弃` 两个按钮（未启动代理或未勾选拦截时禁用）；
  下方上下分栏为 `请求包` 与 `返回包（放行后捕获）`（均只读渲染，二进制只显示字节数）。
  `请求包` **仅在拦截开启时可编辑**（放行时按编辑后的内容发出）；未拦截时属于纯观察视图，
  允许编辑只会被后续流量覆盖。勾选框状态与代理运行状态都在 `updateProxyInterceptState()`
  里同步，拦截器运行时装卸、请求包可编辑性、`放行` / `丢弃` 的可用性由同一个方法决定，
  避免三处状态各自为政。不再提供流量列表与「获取当前联网 IP」按钮。
  点击「启动代理」后地址框回填实际绑定地址，端口框回填实际端口；端口以输入框为准，
  启动失败时状态栏直接显示原因（如端口被占用），不会静默无反应。
- **Shiro 页**：字段为 `目标 URL` / `请求方法` / `Cookie 名` / `密钥` / `AES-GCM` 勾选框 /
  `回显请求头` / `利用链` / `命令` / `附加请求头`；按钮为 `一键检测` / `密钥爆破` /
  `停止` / `生成 Payload` / `执行命令`，配进度条与 `利用输出` 页签组。
  **利用输出是四个独立页签**（`指纹检测` / `密钥爆破` / `生成 Payload` / `执行命令`），
  每个功能只写自己的回显框：四个按钮分别对应 `shiroDetectOutput` /
  `shiroCrackOutput` / `shiroBuildOutput` / `shiroRunOutput`，点击入口时会自动切到
  自己的页签（`shiroShowTab()`）。这样检测结论、爆破出的密钥、生成的链与命令回显
  互不覆盖，可以对照「检测 → 密钥 → 链 → 回显」整条链路排查。
  页面为上下分栏：上栏是字段（放在 `JScrollPane` 中，窗口偏矮时可滚动），下栏是输出区，
  分割位置按 `0.62` 比例定位；**操作按钮与状态行固定在滚动区之外**，避免表单字段把
  按钮顶出可视区（表单首选高约 480px，直接放 `BorderLayout.NORTH` 会把输出区压成细线）。
- **导航分派**：`openSelected()` 按 key 分派页面，每个二级项都必须有对应分支，
  否则会落到末尾的 `showHome()`。「跳转二级项打开正确页面且不回落到主页」由
  `UiNavigationCheck` 通过递归收集内容区标签文字来断言。

---

## 七、构建与验证

构建（`build.ps1`，默认 JDK 17 + Maven 3.9.4）：

1. `mvn -f src\pom.xml clean package`——**`pom.xml` 位于 Java 内容工作区 `src/`**，
   与 Java 源码同级；构建输出目录与 JAR 输出目录通过 `../` 指回仓库根（`target/`、
   `JavaSecExpToolKit.jar`），使根目录只保留构建脚本与产物；
2. 编译目标 Java 8，打包资源 `python/fj_probe.py`（→ `python/`）与
   `src/shiro/res/shiro-keys.txt`（→ `shiro/res/`）；
3. `maven-dependency-plugin` 把运行时依赖（`org.vulhub:java-chains-cli:2.0.0-beta4`）
   复制到根目录 `lib/`，`maven-jar-plugin` 在清单里写入 `Class-Path: lib/…`，
   因此双击 JAR 运行也能加载 java-chains；
4. **校验 JAR 构建时间不早于关键源文件时间**（`Main.java`、`ProxyServer.java`、
   `ShiroEngine.java`、`fj_probe.py`），否则构建失败。

`java-chains-cli` 未发布到中央仓库，因此 `src/pom.xml` 声明了一个本地文件仓库
`file:///${project.basedir}/../libs-repo`，仓库里直接保存该 JAR 与其手写 POM；
换机器时把 `libs-repo/` 一起带走即可离线构建。

验证层次：

| 层次 | 载体 | 覆盖内容 |
| --- | --- | --- |
| 引擎单测 | `tests/test_probe.py` | 模式开关、版本区间、DNS/CEYE 阶段语义（`python -m unittest discover -s tests`） |
| 抓包回归 | `tests/test_probe.py（CaptureModeTest）` | 无 Cookie 时 401、带 Cookie 时 200，记录请求/响应双向 Cookie、Set-Cookie 解析、报告渲染 |
| 转换回归 | `tests/test_probe.py（ConvertModeTest）` | 粘贴报文解析、Cookie 三种导出、curl/raw/json 导出、相对路径补全、离线可用 |
| 一键发送回归 | `tests/UiSwitchEndToEndCheck.java` | 抓包结果发送到 Fastjson 探测（URL / 方法 / 请求头 / 请求体全带过且提示已触发）、发送到 Shiro（URL / 会话 Cookie 带过、不带 `Host`） |
| 传输层回归 | `tests/test_probe.py`（`TransportBlockTest`） | 405 空响应端点：不误判 `is_fastjson`、不推出假版本区间、`has_expect_class` 保持未判定 |
| 登录拦截回归 | `tests/test_probe.py`（`SessionCookieTest`） | 登录拦截按完整响应体识别并写入 `login_gate`；`detect` / `version` / `expect` 各自的结论前缀；带会话 Cookie 后可解锁真实端点；Cookie 合并不覆盖同名项、支持 `Cookie:` 整行；CLI `--session-cookie` 解析 |
| 会话 Cookie 端到端 | `tests/UiSwitchEndToEndCheck.java` | 探测页填写会话 Cookie 后经 GUI → CLI 下发；抓包一键发送把会话 Cookie 带到探测页与 Shiro 页 |
| 渲染契约 | `tests/test_probe.py`（`FormatReportTest`） | `--format text` / `--headers` / `--report` 参数解析、报告分段与关键字段、错误结果渲染、**精简模式每段行数上限（一屏显示契约）**、**登录拦截判定不被精简截断** |
| 配置页契约 | `tests/UiNavigationCheck.java` | 配置页四个新分组齐备、探测报告详细度 / 代理端口 / 抓包默认项 / Shiro 参数控件存在且默认值正确；自检通过 `user.home` 隔离，不写使用者真实配置 |
| 界面自检 | `tests/UiNavigationCheck.java` | 导航五个一级项与代理分类展开/收起、箭头位置、五个模式默认勾选与联动、输入框可用性（含 `请求头（JSON）`）、抓包页一键发送控件、代理页监听地址与拦截控件、Shiro 页控件与利用链选项、**配置页四个新分组与新增控件**；输出截图到 `target/ui-check/`（截图在 EDT 内取帧，避免重影） |
| 探测方法回归 | `tests/test_probe.py`（`ProbeMethodTest`） | 默认 POST、可切换方法、非法方法回落 POST、GET 探针 URL 编码、405 自动换方法、静态页不判成功、报告回显方法 |
| 代理自检 | `tests/ProxyServerCheck.java` | 明文 HTTP 完整记录（请求行/头/体、状态码、响应头/体）、404、CONNECT 隧道双向透传与字节计数、回调、`find` / `clear`、**拦截改包放行后目标收到改写后的头与体**、**丢弃时不连上游且仍留记录**、**运行中装卸拦截器**（未装载直达上游 → 装载立即生效 → 卸载恢复） |
| 端到端自检 | `tests/UiSwitchEndToEndCheck.java` | 本机桩服务 → 界面参数 → Python 命令行 → 中文报告；覆盖多模式分段、**默认精简报告不含探针明细 / 阶段小结 / 目标行、切到详细模式后重新给出明细**、DNS/CEYE 独立 mode 执行、CEYE 缺 Token 提示、405 端点不产生假阳性，以及「界面启动代理 → 经代理发请求 → 请求包与返回包正确记录」、「指定监听地址启动后回填」、「**先启动代理再勾选拦截**（运行时装卸）」、「勾选拦截后请求停住 → 请求包变为可编辑 → 改包放行 → 目标收到改后的体」、「等待放行期间不被无关请求刷屏」、「排队中的请求随后自动接位且不覆盖上一次的返回包」 |
| Shiro 模块自检 | `tests/ShiroCheck.java` | JCE 加解密往返、附加请求头解析（同名合并 / 忽略大小写 / 空行与注释）、**会话 Cookie 与 rememberMe 合并发出且基线请求也带会话 Cookie**、`deleteMe` 指纹识别、字典爆破命中、非法链被拒、CCK1 回显链生成且可由本机密钥解密、CB1/CCK1/CCK2 三条链、回显提取 |
| Shiro 页面自检 | `tests/UiShiroCheck.java` | Shiro 页控件齐备、桩服务上「一键检测」确认存在 Shiro、界面可生成回显链并输出 Base64、**四个功能各有独立回显框且互不串台**（生成 Payload 不写进指纹检测框、指纹结论仍保留） |

---

## 八、已知限制

- 版本区间存在 `1.2.70-1.2.72` 与 `1.2.73-1.2.80` 无法细分的盲区。
- 生产环境若统一返回 500/error，离线区间推断会失真。
- 传输层失败判定是启发式的：若真实解析器接口只回空响应体且状态码恰为
  `INFRA_STATUSES`，会被判为「无法探测」。界面会提示核对 URL、请求方法与登录态。
- 登录拦截识别同样是启发式：依赖网页关键词（`/user/login` / `password` 等），
  若目标用 JSON 响应或纯状态码表达未登录，则不会被识别为登录拦截，仍走旧的传输层失败提示。
  已登录会话也会过期：结论会区分「未携带 Cookie」与「已携带但仍被拦」，
  但工具不会自动登录（验证码等交互无法自动化），会话 Cookie 需由使用者从浏览器 / 代理抓包取得。
- DNS 命中只代表类加载或网络访问发生，需 CEYE 侧确认。
- 配置文件中 Token 为明文。
- 抓包不跟随 30x、不校验 TLS 证书，响应体最多保留 1 MB。
- 报文解析是启发式的，畸形报文可能解析不全。
- 代理抓包只对**明文 HTTP** 记录内容；HTTPS 仅做 `CONNECT` 隧道透传，不解密、不记录明文，
  因此也无法拦截、改包或转换 HTTPS 请求。
- 代理默认监听本机联网 IP，同一局域网内的设备可直接使用；如需收敛暴露面请改填 `127.0.0.1`。
- 代理记录上限由 `ProxyServer(500)` 环形裁剪，界面只展示最近一条返回包，不保留历史列表。
- 拦截是同步阻塞的：等待放行的连接会占住一个处理线程，超时 120 秒自动放行。
- 拦截期间同一时刻只允许一条请求停在界面上（其余排队），等待放行的连接同样占线程；
  取消勾选「拦截请求」会立即放走正在等待的请求。
- Shiro 模块会在目标上执行命令，属于利用功能，仅限授权目标；链能否打通取决于目标依赖
  （CommonsBeanutils / CommonsCollections 版本）与 JDK 版本，失败时输出区会给出链的构建报错。
- Shiro 密钥爆破会持续发送请求，可能触发目标的日志告警与账号锁定策略，请自行评估影响。
- 「405 自动换方法」最多尝试 `POST` / `GET` / `PUT` / `PATCH` 四种，仍失败时以
  `request_method_tried` 说明已尝试过的方法，需人工用「抓包转换」沿用浏览器的真实方法与请求体。
