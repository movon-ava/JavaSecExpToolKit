# 探测准确性与可读性改造设计文档

版本：1.0
日期：2026-09-19
范围：`Fastjson 识别` / `版本识别` / `期望类` 三个识别模式的结果准确性，以及探测结果区的可读性
适用读者：本仓库维护者
关联文档：`docs/DESIGN.md`（总体设计）、`README.md`、`AI_REPORT.md`

---

## 一、任务背景

在授权目标 `http://211.154.20.67:7779/index/fastjson` 上实测时出现两个问题：

1. **结果自相矛盾（准确性）**：`识别` 模式判定「非 Fastjson，置信度 0.028」，
   `版本识别` 模式却给出「`1.2.70-1.2.80`，置信度 0.8」。
2. **可读性差**：界面结果区只显示一整行原始 JSON，字段深、层级多，
   `evidence` 里每条探针的状态码、命中特征与响应摘录需要人工逐段解析。

任务要求：修正识别/版本/期望类的判定准确性，把结果改成人类可读的报告，
并为本次改造补一份设计文档。

---

## 二、根因分析（先诊断后动手）

### 2.1 目标端点的事实

对 `http://211.154.20.67:7779/index/fastjson` 直接发起 POST：

```text
HTTP/1.1 405 Method Not Allowed
Allow: GET, HEAD
Content-Length: 0
```

即该 URL 是**只接受 GET 的登录页**，不是 JSON 反序列化接口。全部探针都在 HTTP 层
被拒绝，响应体为空，没有任何 JSON 解析器参与。

### 2.2 判定链路中的缺陷

| 位置 | 原行为 | 后果 |
| --- | --- | --- |
| `_version_response_errored()` | `status >= 400` 直接判为「解析器报错」 | 11 个布尔探针全部被标成 `errored=True` |
| `_infer_version()` | 依据 `offline_*` 四个探针的报错组合推断区间 | 全 `True` 恰好命中 `1.2.70-1.2.80` 档位 |
| `_infer_version()` | 无 `reported_version` 时仍返回 `confidence=0.9` | 假区间被赋予高置信度 |
| `_fingerprint()` | `confidence` 计算与 `is_fastjson` 无强绑定 | 出现 `0.028` 这种无解释力的尾数 |
| GUI 结果区 | 原样打印引擎 stdout（JSON 一行） | 结论淹没在字段里，不可读 |

关键点：**「HTTP >= 400」并不等于「JSON 解析器报错」**。基础设施层
（反向代理、网关、认证拦截、路由不匹配）同样会返回 4xx/5xx，但响应体往往为空，
此时任何基于响应体差异的推断都失去依据。旧实现把这两种语义混为一谈，
因此产生了与 `识别` 模式结论相矛盾的假阳性。

### 2.3 为什么不能只改 `version`

`识别` 模式虽然结论正确（`is_fastjson=false`），但输出的是「可能是路径、请求格式或
错误响应未暴露解析器特征」这类猜测式描述，且 `confidence=0.028` 没有可操作性。
`期望类` 模式存在同类风险（`baseline` 探针一旦失败即污染全部推断）。
因此需要在**三个模式共用的位置**统一判定「探针是否根本没到达解析器」。

---

## 二之补：登录拦截判定缺口（本轮新增）

**用户反馈**：靶场 `http://211.154.20.67:7779` 确实存在 Fastjson 漏洞，但工具探测不到。
实测后发现缺口不在探针，而在「探针被拦之后的解释」。

| 请求 | 实测响应 |
| --- | --- |
| `POST /vulnapi/Fastjson/vul` | `405` + `Allow: GET, HEAD` + 空响应体 |
| `GET /vulnapi/Fastjson/vul` | `200`，但响应体是登录页 HTML（4442 字节，含「请先登录」） |

**为什么旧逻辑两边都不一定错**：「405 + `Allow: GET`」确实意味着 POST 不可用；
「所有探针返回同一份响应」也确实说明没有解析器参与。
缺的是第三种语义：**目标加了登录拦截，把请求转发到了登录页**。

**为什么必须用完整响应体**：登录页的关键标记（`/user/login`、`name="password"`）
出现在正文 2300+ 字节处，而 `evidence` 里的 `response_excerpt` 只截 500 字。
从截断后的内容里看，一张登录页和一个空响应没有区别。

**实现**：

1. `LOGIN_PAGE_MARKERS` 列出 8 个登录页特征（中文与英文各半），
   按**多特征同时命中**而非单一关键词判定，避免把普通页面当成登录页。
2. `_detect_login_gate()` 在「探针被整层拒绝且无任何 `matched`」时，
   **单独补发一条不带 payload 的 `GET`**，并用完整响应体判断。
3. `_annotate_login_gate()` 只改 `summary` / `notes` 并写入 `login_gate=True`，
   **不动 `evidence`**：判定依据来自补投的 GET，不应混进探针明细。
   `detect` / `version` / `expect` 共用，结论前缀分别为
   `无法探测：` / `版本未能收敛：` / `未能完成探测：`。
4. 已携带会话 Cookie 仍被拦时，结论追加「已携带会话 Cookie 仍被拦截，
   Cookie 可能已过期或权限不足」，区分「没填」与「填了但失效」。

**会话 Cookie 下发**：探测页新增 `会话 Cookie` 输入框（位于 `请求头（JSON）` 之后），
`--session-cookie` 下发；`_clean_session_cookie()` 允许直接粘贴 `Cookie: a=1; b=2` 整行；
`_merge_session_cookie()` 并入请求头时**同名不覆盖**，按名字补齐（抓到的 `JWT_TOKEN`
与配置里的 `JSESSIONID` 合并后仍是同一个 `Cookie` 头）。配置页同样持久化
`session_cookie`，探测页每次打开都会被回填。

**端到端验证**（`tests/UiSwitchEndToEndCheck.java`）：桩服务新增 `/gated`（登录拦截）
与 `/auth`（会话 Cookie 有效才走解析器）两个端点，断言：

| 检查点 | 结果 |
| --- | --- |
| 未登录时 `detect` / `version` / `expect` 分别给出三种前缀的登录拦截结论 | 通过 |
| 登录拦截结论不再出现「该路径只接受 GET」的误导描述 | 通过 |
| 未带 Cookie 时判 `无法探测` 且 `是否 Fastjson: 否` | 通过 |
| 携带有效会话 Cookie 后同一端点判命中 Fastjson | 通过 |
| Cookie 失效（服务端拒绝）时结论区分「已携带仍被拦截」 | 通过 |
| 抓包页「一键发送」带过的会话 Cookie 真正下发到引擎并解锁端点 | 通过 |

---

## 三、设计目标与约束

| 目标 | 落地方式 |
| --- | --- |
| 传输层失败不再产生假版本/假识别 | 新增共用判定 `_transport_block()`，先判可探测性再判结论 |
| 真阳性不被误杀 | 只要观察到任一解析器特征即放弃传输层判定 |
| 结论可读、可执行 | 引擎新增 `format_report()`；GUI 改用 `--format text` |
| 需要登录的目标也能测 | GUI 新增 `请求头（JSON）` 输入框 → `--headers` |
| 不破坏既有集成方式 | CLI 默认仍是 `--format json`，`run()` 返回值结构保持兼容 |
| 不引入新依赖 | 仅使用标准库 `collections` |

明确不做的事（沿用项目边界）：不投递利用链、不执行命令、不读写文件、不驻留内存马；
本轮不对 CEYE 做实测。

---

## 四、详细设计

### 4.1 传输层失败判定 `_transport_block(pairs)`

输入为 `(probe_id, status, response_excerpt, error)` 四元组序列，输出失败原因字符串或 `None`。

判定顺序（短路求值）：

```text
1. pairs 为空                                  → None（无依据，不判定）
2. 全部 status is None                          → "所有探针均无法连接到目标（<首个连接错误>）"
3. 存在 status is None 且存在非 None            → None（部分可达，交回既有逻辑）
4. 任一响应体命中 ALL_PARSER_MARKERS            → None（解析器可达，禁止判定）
5. set(statuses) ⊆ INFRA_STATUSES               → "所有探针均返回 HTTP <code>（<状态说明>）..."
6. 全部 status >= 400 且响应体全为空            → "所有探针均返回 HTTP <code>（<状态说明>），且响应体为空..."
7. 其它                                         → None
```

设计要点：

- **`INFRA_STATUSES`** 取 `{404, 405, 406, 415, 429, 500, 501, 502, 503, 504}`。
  这些状态在真实解析器接口上「全部出现且无任何解析器特征」的概率极低，
  更可能是路由/网关/限流/认证问题。
- **`ALL_PARSER_MARKERS`** 由 `FASTJSON_MARKERS` 与 `OTHER_MARKERS`（Jackson / Gson /
  org.json / Hutool）展平拼接。命中任一即说明解析器确实参与了处理，
  此时哪怕状态码是 4xx 也不能判定为传输层失败——这条规则保证了既有的
  `DetectTest` / `VersionTest` / `ExpectTest` 真阳性用例不受影响。
- **混合连接失败不判定**：避免「一个探针超时 + 其余返回正常特征」这类场景被误判。
- **`HTTP_STATUS_HINTS`** 把状态码翻译为人类可读原因（405 → 「请求方法不被允许
  （该路径可能只接受 GET）」），使结论行本身就能定位问题。
- **`TRANSPORT_BLOCK_HINT`** 是统一的可操作提示：核对 URL 是否指向 JSON 接口、
  请求方法是否为 POST、接口是否需要登录（把 Cookie 作为请求头传入）。

### 4.2 三个模式的接入方式

| 模式 | 接入点 | 命中后的处理 |
| --- | --- | --- |
| `detect` | `_fingerprint()` 算出 `is_fastjson` 后 | `is_fastjson=False`、`confidence=0.0`，`summary` 改「无法探测：…」，`notes` 附提示 |
| `version` | `_version()` 组装 `result` 之后 | `reported_version` / `version_detail` / `version_range` 置 `None`，`confidence=0.0`，`autotype_enabled` / `safemode_enabled` 置 `None`，`summary` 改「版本未能收敛：…」 |
| `expect` | `_expect()` 分支判定之后 | `has_expect_class` 保持 `None`，`confidence=0.0`，`summary` 改「未能完成探测：…」 |

补充修正：`_infer_version()` 在四个离线探针均无定论（`known` 为假）时，
不再给出 `0.9` 置信度而是 `0.0`；此前该分支的置信度语义不成立。

`detect` 的判定刻意用 `if transport_block and not is_fastjson` 守卫：
只要已判定为 Fastjson，就保留原结论（传输层判定退居次要）。

### 4.3 结果渲染 `format_report(result, mode)`

渲染层与探测逻辑解耦：探测函数继续返回结构化 dict，渲染函数只读不写。

```text
===== <模式标题> =====
目标: <target>

探测结论: <summary 的第一分句>
          <summary 其余部分：原因与建议，缩进 10 空格>
<该模式关键字段…>

探针明细:
   1. <探针中文名>            HTTP <code> / 连接失败    命中特征: <a、b / 无>
      响应: <单行折叠、最多 220 字符；仅当响应确实非空时打印>
      错误: <仅当 error 非空>

提示:
  - <notes（与 summary 重复的条目会被丢弃）>
阶段: DNS 探针 已执行/未执行 / CEYE 确认 已执行/未执行
已知限制:
  - <limitations>
```

- `MODE_TITLES` 提供 `detect` / `version` / `expect` / `dns` / `ceye` 的中文标题；
  `STATUS_NOTES` 提供探针 ID 到中文名的映射（覆盖 `diff_*`、`offline_*`、`dns_*` 等）。
- `_one_line()` 折叠换行与连续空白并截断，避免代理返回的大段 HTML/堆栈冲垮排版。
- `_render_evidence()` 最多展示 12 条，超出以「其余 N 条探针已省略（均为无特征响应）」收尾；
  **响应体为空时不再逐条打印`响应: （空响应）`**——绝大多数探针本来就是空响应（405 / 403 /
  统一错误页），逐条打印会让结果区大半篇幅都在重复同一句话。状态列仍然逐条给出，
  使用者可以确认探针确实执行过。实测识别模式报告由 38 行降到 28 行。
- `_wrap_conclusion()` 把 `探测结论:` 拆成「结论」+「原因/建议」两行（按 `。` 再按 `；`
  在 8~120 字区间切分），单行不再超长；`summary` 字段本身保持完整，不影响程序化消费。
- `_dedupe_notes()` 丢掉与 `summary` 重复的 `notes`（按前 12 字比对）：跳过 DNS / CEYE 阶段时
  引擎会把同一条原因同时写进 `summary` 与 `notes`，不渲染层去重就会连续读到两段相同文字。
- `format_report()` 对含 `error` 字段的结果直接返回「探测失败: <error>」。
- 多模式拼接由 Java 完成：引擎已自带分段标题，因此 `Main.startDetection()` 中
  原先手工拼接的 `===== <模式名> =====` 被移除（否则标题会重复）。

### 4.3.1 探针并发 `_send_probes()`

串行发探针时总耗时 ≈ 探针数 × 单次请求耗时：正常目标看不出问题（8 条 456 ms），
但目标不可达时每条都要耗满 `--timeout`（黑洞地址 8 条探针实测 20581 ms）。
`_send_probes()` 用 `ThreadPoolExecutor` 并发发送，并发度取
`PROBE_CONCURRENCY = 4` 与探针数的较小值，四处调用点
（`_fingerprint()` / `_version()` / `_expect()` / `_dns()`）统一走它：

- **按原下标回填**，`evidence` 与 `zip(probes, sent)` 的顺序关系不变；
- **并发度有上界**，授权测试场景下不制造瞬时压力；
- **单条异常收敛为「连接失败」**，不影响同批其它探针。

> 探针表既有 3 元组 `(id, description, payload)` 也有 4 元组
> `(id, category, description, payload)`，payload 一律在末位，必须用 `probe[-1]` 取。
> 实测：黑洞地址识别模式 20581 ms → 169 ms、版本模式 26648 ms → 8340 ms；
> 真实目标识别模式 456 ms → 175 ms。

并发还把两个**早已存在、只在高频请求下才暴露**的判定缺陷放大成必现，本节一并修掉：

**缺陷一：没拿到响应被当成「未报错」（假版本区间）**

`_version_response_errored()` / `_expect_errored()` 原先对 `status is None`（连接中断 /
超时）直接返回 `False`。布尔差分的结论完全建立在「基线正常、离线探针报错」之上，
把一个没有响应的探针记成「未报错」会让区间推断凭空成立——实测推出
`version_detail=1.2.70-1.2.80`、`confidence=0.8` 的彻头彻尾的假区间，
`expect` 模式则给出 `confidence=0.45` 的假结论。

修复：两个函数改为**三态**（`True` 报错 / `False` 未报错 / `None` 未知），
`_infer_version()` 只要四条离线探针有一条是 `None` 就整体不收敛，
`_infer_autotype()` 同理，`_expect()` 新增 `missing_probes` 分支把结论降级为
「未能完成探测」。

**缺陷二：`5xx` 与连接失败被算成「解析器报错」/「响应有差异」**

- `501 / 502 / 503` 是网关、方法不支持等**整体拒绝**，与本次 payload 无关。
  旧实现把 `status >= 400` 一律当成解析器报错，一个 501 就能让四条离线探针全变
  `True`，直接落进 `1.2.70-1.2.80` 档位。现在 `>= 500` 返回 `None`（未知），
  但**响应体里出现解析器特征时仍算命中**（统一错误页可能真包了 fastjson 异常）。
- `_probe_result_varied()` 用 `{item.get("status") for ...}` 判断响应是否有差异，
  连接失败留下的 `None` 会让集合凭空多出一个元素，于是「混了连接失败的同一份 501 错误页」
  被判成「响应随 payload 变化」，`_probe_with_method_fallback()` 据此误报换方法成功。
  现在只统计真的拿到响应的探针。

> 这两处修复互相独立：缺陷一让「未知」不再伪装成「未报错」，缺陷二让「基础设施拒绝」
> 不再伪装成「解析差异」。A/B 对照（同一桩服务、15 轮）：修复前 2/15 轮出现假区间，
> 修复后 0/15，且连续 12 轮全量测试无失败。

### 4.4 抓包与格式转换

与探测平级的独立功能，目的是“先拿到会话，再做探测”：

- 引擎新增 `_request_raw()`（任意方法 + `_NoRedirect` 拦截重定向 + 返回响应头）。
- 新增 `capture` 模式：**只发一次请求**，记录状态码 / 耗时 / 响应头 / 响应体，
  并单独解析请求与响应两侧的 Cookie。不跟随跳转是故意设计：登录成功的 `302 + Set-Cookie`
  正是需要拿到的信息。
- 新增 `convert` 模式：离线解析粘贴报文，导出 `json` / `curl` / `raw` / `cookie-header` /
  `cookie-json` / `cookie-netscape`；相对路径会结合 `Host` 补全为绝对 URL。
- GUI 新增独立页面与导航项，并提供“填入探测页”把 URL / Cookie / 请求体回填，
  形成“登录 → 抓包 → 带会话探测”的闭环。

详细设计见 `docs/DESIGN.md` 的 3.6 节。

### 4.5 请求头输入（`--headers`）

- 引擎：`--headers` 接收 JSON 对象字符串，`_main()` 用 `json.loads` 解析并校验为 dict；
  解析结果作为 `run()` 的 `headers` 参数，最终合并进 `urllib.request.Request` 的请求头
  （在 `Content-Type` 之后展开，因此调用方可覆盖默认 `Content-Type`）。
- GUI：探测页新增 `请求头（JSON）` 行（位于 `业务参数（期望类）` 之后），
  始终可编辑、不随模式勾选联动；非空时通过 `windowsArg()` 转义后传入 `--headers`。
- 配置：新增 `headers` 配置项，配置页 `FastJson 配置` 组新增「默认请求头」行，
  保存后回填探测页。
- 该字段是本轮定位登录页问题的关键：目标真实接口需要 `JWT_TOKEN` + `JSESSIONID`，
  没有请求头输入框时用户只能靠改代码或外部脚本。

**会话 Cookie（本轮新增）**：`--session-cookie` 是 `--headers` 的补充而非替代——
它只表达「登录态」这一件事，`_merge_session_cookie()` 会把它并入 `Cookie` 头，
同名不覆盖（抓到的 `JWT_TOKEN` 与配置里的 `JSESSIONID` 合并后仍是同一个头）。
探测页把它做成独立输入框（`请求头（JSON）` 之后），配置页持久化 `session_cookie`。

### 4.6 请求方法与静态页误判

**根因一（方法单一）**：探针固定用 `POST`。遇到只接受 GET（或只接受其他方法）的接口时，
全部探针被 HTTP 层拒绝，既拿不到解析器特征，也无法把「路径不对」与「方法不对」区分开。

**根因二（静态页被当成解析器）**：把「HTTP 200」当成探针成功的信号时，
一张静态登录页会被判成 Fastjson。实测靶场 `GET /index/fastjson` 对任何请求体都回同一份页面，
`baseline` 探针因此拿到 `+0.25`、两个「区分 Jackson」探针再各拿 `+0.5`，
凑出 `fastjson=1.25 / confidence=0.139` 的假阳性。

**设计**：

1. 探测页新增 `请求方法` 下拉（默认 `POST`），`--probe-method` 下发；
   `GET` / `HEAD` 把探针 payload **URL 编码**后放进查询串（不编码会因 JSON 里的空格与引号
   触发 `http.client.InvalidURL`），其余方法照常发送请求体。
2. `_probe_with_method_fallback()`：探针被 HTTP 层**整体拒绝**且无任何解析器特征时，
   依次改用 `POST → GET → PUT → PATCH` 重试；一旦某方法让任一探针状态码 `< 400`
   （请求已到应用层）就停止重试，避免反复投递；全部失败时在结果里写入
   `request_method_tried` 并在结论中说明。
3. `_uniform_response_block()`：所有探针都没命中解析器特征时，比较响应体的最长公共前缀；
   前缀占比 ≥ 90% 即判定「所有探针返回同一份响应」，直接 `is_fastjson=False` / `confidence=0.0`。
4. `_normalize_volatile()`：比对前把 `;jsessionid=...` / `JSESSIONID=...` / 长十六进制串
   归一化。**容器会在页面每个链接后重写 `jsessionid`**，同一张静态页逐字节比对永不相等，
   不归一化则第 3 条永远不成立（这是本轮最隐蔽的一处坑，已用实测数据确认）。
5. 报告新增 `探测方法:` 行；`TRANSPORT_BLOCK_HINT` 补充「可换其他请求方法重试，
   或从抓包转换页沿用浏览器的真实方法与请求体」。

### 4.7 代理抓包

纯 JDK 实现的本地 HTTP 代理（`src/proxy/ProxyServer.java`），不引入第三方依赖：

- 明文 HTTP 完整记录请求行 / 头 / 体 / 状态码 / 响应头 / 响应体，并把请求行改写为
  origin-form 后转发；`1xx` 中间响应先回写再等最终响应；`HEAD` / `204` / `304` 视为无响应体；
  keep-alive 复用要求响应有明确长度界定，HTTP/1.0 需显式 `keep-alive`。
- HTTPS 只做 `CONNECT` 盲转发隧道（双向 `pumpBytes()` + `tunneledBytes` 计数），
  **不解密、不记录内容**（`captured=false`）——本轮明确不做 HTTPS 解密。
- 详情区渲染时按 `chunked` 去分块、按 `Content-Encoding` 解 `gzip` / `deflate`、
  二进制只显示字节数；记录的原始字节始终不被修改。
- 监听地址可配置，默认取本机联网 IP：`start(bindHost, port)` 绑定任意地址，
  `defaultBindHost()` 依次用「UDP connect 取默认出口 IPv4 → 枚举网卡 → 回环」探测，
  界面提供「当前联网 IP」按钮回填；`displayHost()` 在绑定 `0.0.0.0` 时回落本机联网地址。

代理抓包与抓包转换同属导航 `代理` 分类；详细设计见 `docs/DESIGN.md` 的 3.6 / 3.8 节。

### 4.8 GUI 结果区

`runProbe()` 增加 `--format text`，结果区直接展示引擎渲染的报告；
手工分段拼接逻辑删除。若后续需要结构化输出，CLI 仍可用 `--format json`。

---

## 五、验证设计

| 层次 | 用例 | 断言要点 |
| --- | --- | --- |
| 单元测试 | `TransportBlockTest.test_detect_refuses_false_positive` | 405 空响应端点：`is_fastjson=False`、`confidence=0.0`、`summary` 含「无法探测」 |
| 单元测试 | `TransportBlockTest.test_version_does_not_invent_range` | `version_*` 三字段为 `None`、置信度 `0.0`、AutoType/SafeMode 为 `None`、`summary` 含「版本未能收敛」 |
| 单元测试 | `TransportBlockTest.test_expect_refuses_false_positive` | `has_expect_class is None`、置信度 `0.0` |
| 单元测试 | `TransportBlockTest.test_transport_block_detection_rules` | 空输入不判定；全 405 判定；带解析器特征不判定；连接失败与状态码混合不判定；全连接失败判定 |
| 单元测试 | `FormatReportTest` | `--format` / `--headers` 解析契约；报告分段、关键字段、错误渲染、无乱码 |
| 单元测试 | 既有 25 项（`DetectTest` / `VersionTest` / `ExpectTest` / `StageSwitchTest` …） | 真阳性与阶段开关语义未被破坏（回归） |
| 界面自检 | `UiNavigationCheck` | 新增断言：`请求头（JSON）` 默认空且可编辑；配置页存在 `默认请求头` 输入框 |
| 端到端自检 | `UiSwitchEndToEndCheck` | 桩服务（Fastjson 正常端点 + 405 登录页）经 GUI → CLI → 报告全链路；多模式分段；DNS/CEYE 独立执行；CEYE 缺 Token 提示；405 端点不产生假阳性 |
| 单元测试 | `ProbeMethodTest` | 默认 POST；指定 GET 后因 405 自动改用 POST 并写入 notes；非法方法回落 POST；GET 探针 URL 编码（无 `InvalidURL`）；全静态页端点 `is_fastjson=False` / `confidence=0.0` |
| 代理自检 | `ProxyServerCheck` | 明文 HTTP 完整记录、404、`CONNECT` 隧道双向透传与字节计数、回调、`find` / `clear` |
| 界面自检 | `UiNavigationCheck` | 四个一级项、`代理` 分类展开/收起并含 `代理抓包` + `抓包转换` 两个二级项、代理页 `监听地址` 与「当前联网 IP」控件、截图无重影 |
| 端到端自检 | `UiSwitchEndToEndCheck` | 在 `监听地址=127.0.0.1` 下启动代理并回填地址，经代理请求后列表与详情正确记录 |
| 实测 | 授权目标 `http://211.154.20.67:7779` | 未登录 POST `/index/fastjson` 返回 405 → 判定「无法探测」；带会话 Cookie POST `/vulnapi/Fastjson/vul` → 识别 `is_fastjson=true`、版本 `1.2.41`（区间 `1.2.48-1.2.68`）；`GET /index/fastjson` 静态页 → 被判「未到达 JSON 解析器」而非假阳性；代理抓取 `GET /index/fastjson` 得 HTTP 200 + `Set-Cookie: JSESSIONID`，`POST` 得 405 空响应 |

---

## 六、已知限制与后续

- 传输层判定是启发式：若真实解析器接口在全部探针上只返回空响应体、状态码又恰好
  落在 `INFRA_STATUSES`，会被判为不可探测。GUI 通过提示语引导用户核对
  URL、请求方法与登录态。
- 状态码集合判定依赖「有解析器参与就会留下特征」这一前提；对完全静默处理
  （如吞掉异常并统一返回空 200）的接口无效。
- `识别` 模式的 `confidence` 仍是 `fastjson_score / 9.0` 的线性映射，
  本轮只修正了假阳性时的取值，未重新标定分数区间。
- 未对 CEYE 确认做实测（按任务要求）；DNS 相关改动仅覆盖参数解析与跳过语义。
- 「405 自动换方法」最多尝试 `POST` / `GET` / `PUT` / `PATCH` 四种；仍失败时需要人工
  用「抓包转换」沿用浏览器的真实方法与请求体。
- 静态页判定基于最长公共前缀（阈值 90%），若真实接口的响应体本身高度模板化
  （仅个别字段不同）且无任何解析器特征，可能被一并判为未到达解析器。
- 登录拦截识别也是启发式：依赖网页关键词（`请先登录` / `/user/login` / `password` 等），
  且要求多特征同时命中。目标若用 JSON 响应或纯状态码表达未登录，仍会走旧的传输层失败提示。
- 工具不会自动登录（验证码等交互无法自动化），会话 Cookie 需从浏览器或「代理抓包」取得；
  会话也会过期，结论只区分「未携带 Cookie」与「已携带但仍被拦」。
- 代理只覆盖明文 HTTP；HTTPS 保持隧道透传，不做中间人解密。
- 代理**默认监听本机联网 IP**（便于局域网内其他设备 / 手机接入），暴露面大于仅回环监听；
  如需收敛请把 `监听地址` 改为 `127.0.0.1`。绑定 `0.0.0.0` 时为所有网卡。
- `GET` 探测会把 payload 放进查询串，可能与接口的原始入参形态不一致，仅用于方法探测，
  拿到真实请求体后应回到「抓包转换 → 填入探测页」的路径。

---

## 七、改动文件清单

| 文件 | 改动 |
| --- | --- |
| `python/fj_probe.py` | 新增 `_transport_block()` 及常量、三个模式接入判定、`_infer_version()` 置信度修正、`format_report()` 渲染层、`--format` 参数 |
| `src/Main.java` | 新增 `请求头（JSON）` 输入框与 `headers` 配置项、`--headers` 与 `--format text` 下发、删除手工分段标题与失效的 `modeLabel()` |
| `tests/test_probe.py` | 新增 `TransportBlockTest`、`FormatReportTest` 与 405 桩服务 |
| `tests/UiSwitchEndToEndCheck.java` | 重写为报告断言，新增 405 登录页桩服务与假阳性回归 |
| `tests/UiNavigationCheck.java` | 新增请求头输入框与配置项断言 |
| `docs/DESIGN.md` | 补充 3.4 传输层判定、3.5 结果渲染、IPC 与配置说明、验证矩阵 |
| `README.md` | 补充报告格式、传输层语义、`--headers` 用法与配置项 |
| `AI_REPORT.md` | 记录本轮根因、改动与验证结论 |
| `python/fj_probe.py`（登录拦截） | 新增 `LOGIN_PAGE_MARKERS` / `LOGIN_GATE_HINT` / `_detect_login_gate()` / `_annotate_login_gate()` / `_LOGIN_GATE_PREFIXES`、`_clean_session_cookie()` / `_merge_session_cookie()`、`--session-cookie` 参数 |
| `src/Main.java`、`src/ui/ProbePage.java`、`src/ui/ConfigPage.java`、`src/probe/ProbeCommand.java` | 探测页与配置页新增 `会话 Cookie` 输入框、`--session-cookie` 下发、`session_cookie` 配置项回填 |
| `tests/test_probe.py` | 新增 `LoginGatedHandler` / `SessionAuthHandler` 桩服务与 `SessionCookieTest`（7 条） |
| `tests/UiSwitchEndToEndCheck.java` | 新增 `/gated` 与 `/auth` 桩服务、`reply(..., contentType, ...)` 重载，新增登录拦截 3 模式 + 会话 Cookie 解锁/失效 + 一键发送带 Cookie 的端到端断言 |
| `python/fj_probe.py`（抓包） | `_request_raw()` / `_NoRedirect` / `parse_pasted_request()` / `_absolute_url()`、`capture` 与 `convert` 模式、`convert_report()` 与六种格式渲染、`_render_capture()` / `_render_convert()` |
| `python/fj_probe.py`（方法） | `_request()` 支持 `method` 参数与 GET 负载 URL 编码、`_request_method()`、`--probe-method`、`_probe_with_method_fallback()` / `_probe_result_blocked()` / `_probe_result_varied()`、`_uniform_response_block()` / `_normalize_volatile()`、`SUPPORTED_REQUEST_METHODS` / `PROBE_METHOD_FALLBACKS`、报告 `探测方法:` 行 |
| `src/proxy/ProxyServer.java` | 新增本地 HTTP 代理：`HttpFlow` / `FlowListener`、明文转发与完整记录、`CONNECT` 隧道透传、`isKeepAlive()`、报文解析与分块读取 |
| `src/Main.java`（代理页） | 新增 `代理抓包` 导航项与页面、`toggleProxy()` / `appendProxyFlow()` / `clearProxyFlows()` / `showProxyDetail()` / `renderProxyFlow()` / `exportProxyDetail()` / `buildRawRequest()` / `bodyForDisplay()` 等 |
| `src/Main.java`（方法） | 新增 `请求方法` 下拉、`--probe-method` 下发、配置项 `probe_method`、`填入探测页` 同步方法 |
| `tests/ProxyServerCheck.java` | 新增代理自检（明文记录、404、CONNECT 隧道、回调、记录字段、`find` / `clear`） |
| `tests/UiNavigationCheck.java` | 导航项由 4 项改为 5 项并新增索引调整、代理页控件与表头断言、`请求方法` 下拉断言、新增 `06-proxy.png` 截图 |
| `src/Main.java`（抓包页） | 新增 `抓包转换` 导航项与独立页面、`startCapture()` / `startConvert()` / `fillProbeFromCapture()` / `runEngineCommand()` |
| `src/proxy/ProxyServer.java`（监听） | 新增 `LOOPBACK` / `ANY` / `defaultBindHost()` / `ipv4Of()`、`start(bindHost, port)`、`start(port)` 委托、`host()` / `displayHost()` |
| `src/Main.java`（导航与代理页） | 导航归并为四个一级项（`代理` 含 `代理抓包` + `抓包转换`）、`rebuildNavigation()` 保留所有已展开分组的子项、代理页新增 `监听地址` 输入框与「当前联网 IP」按钮 |
| `tests/UiNavigationCheck.java`（截图） | `snapshot()` 改到 EDT 内取帧，消除非 EDT 取帧导致的重影 |
