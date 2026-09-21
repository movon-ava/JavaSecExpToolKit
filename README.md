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
- `配置` — 设置项，分为 `通用配置` 与各功能分组
- `代理` — 一级分类，含两个流量工具；点击可展开 / 收起二级项 `代理抓包`（本地 HTTP 代理）
  与 `抓包转换`（单次抓包、格式转换）
- `FastJson` — 一级分类；点击可展开 / 收起二级项 `Fastjson 探测`
- `Shiro` — 一级分类；点击可展开 / 收起二级项 `Shiro 漏洞利用`

一级分类行右侧固定显示 `▾` / `▸` 标记；点击它只展开 / 收起子项，不会离开当前页面。
主页卡片上的 `打开探测` 会展开分组并直接跳到探测页。

## 配置页

`配置` 页把固定参数保存在 `%USERPROFILE%\.JavaSecExpToolKit\config.properties`，分组如下：

- `通用配置` — Python 解释器（在 `FJ_PYTHON` 未设置时生效）、默认超时、默认探测请求方法
- `FastJson 配置` — CEYE 域名、CEYE Token、CEYE API 地址、默认 DNS 等待、默认业务参数、
  默认请求头、会话 Cookie、默认 DNSLog 主机、默认 CEYE Filter
- `探测报告配置` — 报告详细度（`精简` / `详细`，默认 `精简`）
- `代理配置` — 默认监听地址、默认监听端口、启动后是否默认拦截请求
- `抓包转换配置` — 默认请求方法、默认 Content-Type、默认转换目标
- `Shiro 配置` — 默认目标 URL、Cookie 名、密钥、AES-GCM、回显请求头、利用链、命令、默认请求体

保存后的值会预填到**全部功能页**（探测页 + 代理页 + 抓包页 + Shiro 页）。代理启动后会
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
| `src/` | Java Swing 界面（`Main.java`）、`pom.xml`、本地代理（`proxy/ProxyServer.java`）与 Shiro 模块（`shiro/`） |
| `src/ui/` | 各功能页视图（`HomePage` / `ProbePage` / `CapturePage` / `ProxyPage` / `ShiroPage` / `ConfigPage`）与样式（`UiKit`） |
| `src/probe/` | 引擎调用与命令行拼装（`ProbeEngine` / `ProbeCommand` / `CaptureBridge`） |
| `src/config/` `src/util/` | `config.properties` 读写；报文解析与平台差异 |
| `python/` | 探测引擎（`fj_probe.py`），打进 JAR |
| `tests/` | Python 单元测试与 Java 界面自检 |
| `tools/` | 维护辅助脚本，例如 `apply_patch.py` |
| `docs/` | 设计文档（`DESIGN.md`、`DESIGN-shiro.md`、`DESIGN-probe-accuracy.md`、`DESIGN-agents.md`、`DESIGN-modularization.md`、`DESIGN-payload.md`） |
| `openspec/` | 规格驱动开发：`specs/` 存能力规格，`changes/` 存待办与归档的变更，`config.yaml` 约束 AI 生成规划件 |
| `.agents/` | OpenSpec 生成的 AI 工具指令（本仓库目标工具为 codex） |
| `target/` | Maven 构建输出（经 `src/pom.xml` 写入） |
| `.backups/` | 带时间戳的快照，只保留最近三次 |
| 根目录 | `build.ps1`、`run.ps1`、`README.md`、`README.en.md`、`AGENTS.md`、`AI_REPORT.md`、构建出的 `JavaSecExpToolKit.jar`，以及 `lib/`（运行时依赖）与 `libs-repo/`（java-chains 的离线 Maven 仓库） |

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

`主页 → Shiro → Shiro 漏洞利用` 覆盖 rememberMe 识别、字典密钥爆破（内置 1108 条）、
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
```

`CEYE_TOKEN` / `CEYE_DOMAIN` / `CEYE_API` 环境变量同样可用。

## 测试

```powershell
python -m unittest discover -s tests
```

Java 界面自检针对已编译的 class 运行，并自行打印断言：

```powershell
E:\java\jdk17\bin\javac.exe -encoding UTF-8 -cp target\classes -d target\classes tests\*.java
E:\java\jdk17\bin\java.exe -Dfile.encoding=UTF-8 -cp target\classes UiNavigationCheck
E:\java\jdk17\bin\java.exe -Dfile.encoding=UTF-8 -cp target\classes UiSwitchEndToEndCheck
E:\java\jdk17\bin\java.exe -Dfile.encoding=UTF-8 -cp target\classes ProxyServerCheck
```

`UiNavigationCheck` 校验侧边栏展开 / 收起行为，并把页面截图写入 `target/ui-check/`；
`UiSwitchEndToEndCheck` 通过真实 Python 引擎驱动界面开关（含从界面启动代理并读取记录）；
`ProxyServerCheck` 覆盖代理本身——明文抓包、404、`CONNECT` 隧道字节透传、回调与
`find` / `clear`。另有 `ShiroCheck`（Shiro 引擎）与 `UiShiroCheck`（Shiro 页）可一并运行。

**请仅在获得明确测试授权的系统上运行。**
