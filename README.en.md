# JavaSecExpToolKit

Java + Python desktop toolkit for authorized security testing: Fastjson
fingerprinting, a local intercepting proxy, capture/format conversion, and a
Shiro exploitation module.

Language: [中文](README.md) | [English](README.en.md)

## Scope

The Fastjson probe performs non-destructive probing against an authorized HTTP
JSON endpoint. It sends normal JSON, malformed JSON, and harmless `@type`
markers to compare response characteristics, and supports five modes:

- `detect` — identify Fastjson and separate it from Jackson / Gson / org.json / Hutool
- `version` — AutoType state, echoed `fastjson-version`, and offline boolean probes (`version_detail` / `version_range`)
- `expect` — whether the endpoint binds an expected Java class
- `dns` — harmless DNS probes (`Inet4Address` / `InetSocketAddress` / `URL` key / `Exception`)
- `ceye` — confirm DNS records through the CEYE API

Each mode prints a rendered Chinese report by default (`--format text`). Report verbosity is
controlled by `--report`:

- `--report brief` (**default**) — condensed report: the first sentence of the conclusion plus
  the key verdicts (is it Fastjson, likely version, PoC tier, AutoType / SafeMode, confidence),
  with no blank separators. All five modes together take 16 lines, so the UI result pane shows
  them in a single screen.
- `--report detail` — full report: adds the target, the conclusion's trailing sentences (reason
  and advice), per-probe detail with status and matched markers, notes, stage summary, and known
  limitations. Use it when chasing false positives.

Capture and convert always render in detail mode because their content *is* the result. Use
`--format json` for the raw structured result; fields such as `summary` are always complete
regardless of verbosity.

Transport-layer failures are reported instead of guessed: if every probe is rejected at the HTTP
layer (for example a login page answering `405 Allow: GET, HEAD` with an empty body) and no parser
marker shows up, `detect` reports "cannot probe" with confidence `0.0` and `version` leaves the
version undetermined instead of inferring a bogus range.

A login interceptor is reported as such instead of being misread as "this path only accepts GET".
When every probe is rejected at the HTTP layer and none of them matched a parser marker, the engine
re-sends one payload-free `GET` and checks the **full** response body for login-page markers
(`/user/login`, `name="password"`, ...). This matters because those markers sit well past the
500-character excerpt kept in `evidence`. The conclusion then carries a mode-specific prefix
(`无法探测：` / `版本未能收敛：` / `未能完成探测：`) plus the reason, and `login_gate` is set in the
JSON result. If a session cookie was supplied and the target still bounced the request, the
conclusion says so explicitly, which separates "no cookie given" from "cookie expired".

Send `--session-cookie 'JWT_TOKEN=...; JSESSIONID=...'` (or `--headers '{"Cookie":"..."}'`) when
the endpoint needs a session. `--session-cookie` merges into the `Cookie` header by name and never
overwrites a cookie that was already captured, so a `JWT_TOKEN` from the proxy and a `JSESSIONID`
from the config both survive.

The Fastjson probe modes never deliver a payload: no exploit chains, command
execution, file writes, or memory shells.

A separate **Shiro** module does include exploitation (rememberMe detection, key
cracking, echo-chain delivery and command execution). Use it only against targets
you are explicitly authorized to test. See [docs/DESIGN-shiro.md](docs/DESIGN-shiro.md).

Two capture features ship with it:

- **Capture & convert** — send one request with any method and inspect the raw response
  (redirects are not followed, so login flows stay visible), then export what you captured as
  `json`, `curl`, `raw`, `cookie-header`, `cookie-json`, or `cookie-netscape`. Pasting a raw
  request from Burp or the browser works offline and converts in place, which makes it easy to
  pull a session cookie out of a login flow and hand it to the probe page.
- **Proxy** — a local HTTP proxy (default port `8899`, bound to your LAN IP) you point a browser or browser
  extension at, so requests and responses show up live as you browse, the way Burp works.
  Plain HTTP is recorded in full (request line, headers, body, status, response headers and
  body). Tick `拦截请求` to hold a request before it reaches the target, edit the request pane,
  then press `放行` to send it or `丢弃` to drop it; the response shows up in the pane below.
  The checkbox takes effect immediately, including on a proxy that is already running, and only
  one request is held at a time (the rest queue up) so background traffic cannot overwrite what
  you are editing. The request pane is editable only while interception is on; untick the box and
  any request still waiting is released unchanged.
  **HTTPS is tunneled only — `CONNECT` is forwarded byte-for-byte and never decrypted**,
  so HTTPS browsing keeps working but its content stays invisible (and cannot be intercepted).

Probes also accept a request method (`--probe-method`, default `POST`). If every probe is
rejected at the HTTP layer, the engine retries with `GET` / `PUT` / `PATCH` and records which
methods it tried. Responses that are identical across every probe (a static login page, even a
`200`) are explicitly not treated as a Fastjson hit.

## Requirements

- Windows
- Python 3.10+
- JDK 17 for building and running (the Shiro module needs `--add-opens` on JDK 17; `run.ps1` adds them)
- Maven 3.9+ (the build script defaults to `E:\java\maven\apache-maven-3.9.4`)

The supplied build script uses `E:\java\jdk17` and `E:\java\maven\apache-maven-3.9.4` by default. Override them when needed:

```powershell
.\build.ps1 -JavaHome E:\java\jdk21 -MavenHome E:\java\maven\apache-maven-3.9.4
.\run.ps1 -JavaHome E:\java\jdk21 -MavenHome E:\java\maven\apache-maven-3.9.4
```

To use a specific Python interpreter from the desktop application:

```powershell
$env:FJ_PYTHON = "C:\Python313\python.exe"
.\run.ps1
```

## Build and Run

Build the executable JAR with Maven:

```powershell
.\build.ps1
```

Run the desktop application directly from the root-level JAR:

```powershell
E:\java\jdk17\bin\java.exe -jar .\JavaSecExpToolKit.jar
```

`run.ps1` remains available as a convenience wrapper and builds the JAR if it is missing.

The Maven POM lives in the Java workspace (`src/pom.xml`), next to the sources, and writes its
output back to the repository root (`target/`, `JavaSecExpToolKit.jar`). Maven packages
`python/fj_probe.py` and `src/shiro/res/shiro-keys.txt` inside the JAR, sets `Main` as the entry
point, and copies runtime dependencies to `lib/`. The build script checks that the generated JAR
timestamp is not earlier than the key source files. The desktop application still requires
Python 3.10+ at runtime; set `FJ_PYTHON` when `python` is not on `PATH`.

## Navigation

The sidebar is grouped by feature:

- `主页` — workspace overview
- `Payload` — first-level category; click it to expand / collapse `Payload 生成` and `预设链`
- `服务` — first-level category; click it to expand / collapse `恶意服务器` and `Shiro 漏洞利用`
- `代理` — first-level category; click it to expand / collapse `代理抓包` (local HTTP proxy)
  and `抓包转换` (capture one request, inspect the raw response, convert formats)
- `FastJson` — first-level category; click it to expand / collapse `Fastjson 探测`
- `配置` — settings, grouped into `通用配置` and per-feature sections

The order mirrors the left-hand menu of the java-chains web UI. The main window now starts
**maximized**: the server page has to fit the service list, listen parameters, payload publishing
and the output pane at once, and the parameter column gets squeezed at smaller widths.

First-level categories show a `▾` / `▸` marker pinned to the right edge of the row; clicking one toggles its children without leaving the current page. `打开探测` on the home card expands the group and jumps straight to the probe page.

## Settings page

The `配置` page stores fixed parameters in
`%USERPROFILE%\.JavaSecExpToolKit\config.properties`, grouped as:

- `通用配置` — Python interpreter (overrides `FJ_PYTHON` when that env var is unset), default timeout, default probe request method
- `FastJson 配置` — CEYE domain, CEYE token, CEYE API endpoint, default DNS wait, default base body, default request headers, session cookie, default DNSLog host, default CEYE filter
- `探测报告配置` — report verbosity (`精简` / `详细`, default `精简`)
- `代理配置` — default listen address, default listen port, whether the proxy starts with interception on
- `抓包转换配置` — default request method, default Content-Type, default convert target
- `Shiro 配置` — default target URL, cookie name, key, AES-GCM, echo header, gadget chain, command, default request body
- `Payload 生成配置` — default export directory for generated payloads (blank writes to the user home)
- `恶意服务器配置` — default bind address, default advertised address, and the JNDI LDAP / RMI / HTTP
  ports plus the HTTP service, JRMP, FakeMySQL and TCP ports (blank or 0 keeps the default)
- `预设链配置` — default category filter for the preset page (choices come from the built-in preset file itself)

Saved values pre-fill **every feature page** (probe, proxy, capture, Shiro, Payload, presets,
malicious servers) and are pushed to already-open pages right after saving, with no restart needed. After the proxy
starts, the address and port actually used are written back into the settings, so the port shown
on the settings page reflects real usage. The Python engine reads the same file, and explicit
CLI arguments always win over stored values.

The probe page has a `请求方法` dropdown (`POST` / `GET` / `PUT` / `PATCH` / `DELETE` / `OPTIONS`,
default `POST`) that passes `--probe-method`; use `GET` for endpoints that only read JSON from the
query string. `GET` / `HEAD` probes URL-encode the probe payload into the query string.

It also has a `请求头（JSON）` field, always editable, which passes
`--headers` when non-empty — use it for a logged-in session cookie, e.g.
`{"Cookie":"JWT_TOKEN=...; JSESSIONID=..."}`.

Right below it, `会话 Cookie` (session cookie) passes `--session-cookie` when non-empty. It is the
field to use when a target bounces unauthenticated requests to a login page: it says only "this is
the logged-in session" and merges into the `Cookie` header by name. A `Cookie:` line can be pasted
as-is; the same value is stored as `session_cookie` on the config page and pre-filled every time
the probe page opens. The Shiro page merges it with `rememberMe` into a single `Cookie` header.

The `探测模式` group holds five independent checkboxes (`Fastjson 识别` / `版本识别` /
`期望类` / `DNS 探针` / `CEYE 确认`), all selected by default and none prefixed with `启用`.
Checked modes run in that fixed order and each result is prefixed with a
`===== <mode> =====` header; unchecking one simply skips that stage. Field availability
follows the matching checkbox: `业务参数（期望类）` needs `期望类`, `DNSLog 主机` and
`DNS 等待` need `DNS 探针`, and `CEYE Filter` needs `CEYE 确认`, so an unchecked mode never
reaches the Python command line.

`DNS 探针` runs `--mode dns` (mapped to `--dns` / `--no-dns`) and `CEYE 确认` runs
`--mode ceye` (`--ceye` / `--no-ceye`). All five checkboxes are peer modes, so a checked
DNS probe is not re-attached to detect/version/expect results and cannot be delivered twice
when five modes run together. Engine-side, CEYE confirmation still depends on the DNS stage
when used as an attached stage. Running `CEYE 确认` without a token stops early with a
readable hint instead of an engine error — set CEYE Token on the settings page or export
`CEYE_TOKEN`.

## Project layout

| Path | Purpose |
| --- | --- |
| `src/` | Composition root `Main.java` (301 lines: wiring and page switching only), `pom.xml`, the local proxy (`proxy/ProxyServer.java`), and the Shiro module (`shiro/`) |
| `src/ui/` | Per-feature **views** and **behaviour** kept apart: views `*Page` / `*Form` (`HomePage` / `ProbePage` / `CapturePage` / `ProxyPage` / `ShiroPage` / `PayloadPage` / `PresetPage` / `ServicePage` / `ConfigPage` / `ConfigForm`), behaviour `*Controller` (`NavController` / `ProbeController` / `CaptureController` / `ProxyController` / `ShiroController` / `ConfigController` / `PayloadController` / `PresetController` / `ServiceController` / `WorkbenchPages`), plus styling `UiKit`, the shared `ChainEditor`, the column selector `PayloadChainSelector` (the payload page's chain picker, holding no chain state) with its collaborators `ChainColumnFilter` (filter predicate) / `ChainNodeRenderer` (row rendering) / `ChainTagMenu` (tag menu), the payload page split-outs `PayloadPanels` (panel building) / `PayloadColumns` (column mapping) / `PayloadOutputText` (output text) / `PayloadExporter` (export to disk), and the self-check facade `UiHandle` + `WidgetRegistry` |
| `src/payload/` | Generic payload generation (`PayloadEngine` / `PayloadCatalog` / `PayloadResult`), decoupled from concrete features such as Shiro |
| `src/service/` | Malicious servers (`ServiceManager` / `ServiceSpec` / `ServiceEndpoint` / `ServiceDefaults`): the only package that talks to the java-chains server-side adapters |
| `src/preset/` | Built-in preset chains (`PresetCatalogService` / `PresetItem`): the only package that touches the upstream preset model, exposing plain data |
| `src/probe/` | Engine invocation and command-line assembly (`ProbeEngine` / `ProbeCommand` / `CaptureBridge`) |
| `src/config/` `src/util/` | `config.properties` read/write; message parsing and platform differences |
| `python/` | probe engine (`fj_probe.py`), packaged into the JAR |
| `tests/` | Python unit tests and Java UI self-checks; `TestProcessGuard.java` kills calculator processes spawned during a self-check run |
| `tools/` | maintenance helpers: `agent.ps1` (launch a role-scoped agent), `dispatch.ps1` (dispatch one role and auto-merge), `orchestrate.ps1` (one-sentence goal, automatic decomposition and orchestration), `watchdog.ps1` (stall watchdog), `lib/` (role matrix and execution primitives), `audit_boundary.py` (dependency audit), `apply_patch.py` |
| `docs/` | design documentation (`DESIGN.md`, `DESIGN-shiro.md`, `DESIGN-probe-accuracy.md`, `DESIGN-agents.md`, `DESIGN-modularization.md`, `DESIGN-payload.md`, `DESIGN-services.md`) plus the multi-agent role guide (`AGENT-ROLES.md`) and the runbook (`AGENT-RUNBOOK.md`) |
| `openspec/` | spec-driven development: `specs/` holds capability specs, `changes/` holds active and archived changes, `config.yaml` constrains AI-generated planning artifacts |
| `.agents/` | AI tool instructions generated by OpenSpec (target tool for this repo is codex) |
| `target/` | Maven build output (Maven writes here via `src/pom.xml`) |
| `.backups/` | timestamped snapshots, most recent three are kept |
| root | `build.ps1`, `run.ps1`, `README.md`, `README.en.md`, `AGENTS.md`, `AI_REPORT.md`, the built `JavaSecExpToolKit.jar`, plus `lib/` (runtime deps) and `libs-repo/` (offline Maven repo for java-chains) |

## Parallel multi-agent development

This repository defines a role-based multi-agent workflow so several features can be
developed in parallel without overwriting each other. See
[docs/AGENT-RUNBOOK.md](docs/AGENT-RUNBOOK.md) (how to run) and
[docs/AGENT-ROLES.md](docs/AGENT-ROLES.md) (duties and write scopes).

```powershell
# start a feature agent: its own worktree and branch, main repo untouched
.\tools\agent.ps1 -Role probe -Slug version-blindspot -Task "close the 1.2.73-1.2.80 version blind spot"

# concurrency: start a role with a disjoint write scope in another terminal
.\tools\agent.ps1 -Role traffic -Slug header-tolerance -Task "harden captured-header parsing"

# print the prompt without starting anything
.\tools\agent.ps1 -Role probe -Slug demo -Task "example task" -DryRun

# one sentence in: decomposition -> batched dispatch (by dependency and write scope) -> auto-merge -> report
.\tools\orchestrate.ps1 -Goal "close the fastjson 1.2.73-1.2.80 version blind spot"

# decompose and print the plan only; no worktree, no agent started
.\tools\orchestrate.ps1 -Goal "same as above" -DryRun
```

| Role | Write scope | Sandbox |
| --- | --- | --- |
| orchestrator | root docs, `openspec/**`, `docs/**`, `tools/**`, shared kernel `src/config/**`, `src/util/**`, `src/pom.xml` | writable |
| probe | `python/fj_probe.py`, `src/probe/Probe*.java` | writable |
| exploit | `src/shiro/**`, `src/payload/**`, `src/service/**`, `src/preset/**` | writable |
| traffic | `src/proxy/**`, `src/probe/CaptureBridge.java` | writable |
| ui | `src/ui/**`, `src/Main.java` | writable |
| test | `tests/**` | writable |
| supervisor | none (read-only audit) | read-only, enforced |
| watchdog | none (read-only session health verdict) | read-only, enforced |

Every session has a timeout ceiling; when a session goes quiet, a read-only watchdog
agent reads its log and decides whether it is progressing, waiting on a genuinely slow
command, or stuck — so a session waiting on a long command is not killed by mistake.

Every dispatch ends with a report of **which roles were invoked, what each one did, and which
files it produced**: the orchestrator groups results by role first (steps per role, main work,
produced files) and then lists step-by-step detail; a single dispatch prints the role, its work
and the produced files as well.

The tooling has its own regression check (`tools\check_agent_tools.ps1`, 84 mechanical
assertions) covering defects that actually happened: a counter colliding with a parameter
name, a misspelled switch turning a branch into dead code, a missing BOM, an unanchored
`.gitignore` rule, a `,@()` return that silently disabled two guard branches, and managed
paths that no role was allowed to write.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check_agent_tools.ps1
```

Concurrency does not require extra terminal windows: launch roles in the background with
`Start-Process ... -WindowStyle Hidden` and keep using the current terminal (killing one
session was verified not to affect another). The main agent cannot spawn sub-agents by
itself: an agent sandbox cannot write `.git/refs/heads/**`, so `git worktree add` fails with
`cannot lock ref`; dispatching is done by `tools/agent.ps1` outside the sandbox.
To just hand over one sentence, use `tools/orchestrate.ps1`: it decomposes, batches, dispatches
and merges on the main agent's behalf (outside the sandbox) and reports each step as
merged / nothing to do / needs a human. Auto-merge has hard gates — a timeout, a stall verdict,
an out-of-scope edit, or no actual commit each leaves the scene for a human instead.

Isolation comes from per-agent worktrees and branches. Agents only edit files; the
script commits outside the sandbox and mechanically checks the write scope. Shared
resources (`.backups/`, `AI_REPORT.md`, `PROGRESS.md`, build output) are never touched
concurrently; the main agent handles that after merging.

## Using captured traffic as-is


Traffic copied out of the proxy or Burp can be probed without rewriting it. The
`附加请求头` (extra headers) field accepts four shapes:

- one `Key: Value` per line, which is what the capture page's one-click send generates;
- a bare cookie value `JWT_TOKEN=a; JSESSIONID=b`, which is exactly what the `cookie-header`
  convert target prints and therefore the most likely thing to be pasted;
- a whole `Cookie: a=1; b=2` line;
- a JSON object `{"Cookie":"JWT=x"}`, the shape the UI itself produces.

Headers with the same name merge instead of overwriting each other, so a captured session cookie
and `rememberMe` share one `Cookie` header. Input that cannot be recognised produces a readable
error rather than being silently dropped.

Two headers are always stripped because they make probing **fail for certain**:

- `Content-Length` describes the *original* request body. Keeping the stale value makes the target
  wait forever for a body that never arrives, which shows up as every probe timing out.
- `Accept-Encoding: gzip` makes the target compress its response while the engine parses plain
  text, which reads as "all probes returned the same response, the parser was never reached".
  Forwarding rewrites it to `identity`; hop-by-hop headers (`Connection`, `Proxy-Connection`, ...)
  are dropped as well.

When `拦截请求` (intercept) is off, the proxy panel shows the most recent flow and
`转发到抓包转换` still exports it. One-click send carries the URL,
method, headers **and body** across: a captured POST body is usually the endpoint's business
payload, and omitting it only yields a validation error.

## Capture & convert

```powershell
# send one POST and keep the raw response, exporting several formats
python .\python\fj_probe.py --mode capture --method POST `
  --capture-url http://127.0.0.1:8080/api/json `
  --headers '{"Cookie":"JWT_TOKEN=..."}' --body '{"age":20}' `
  --convert-targets cookie-json,curl

# parse a pasted raw request offline (no network access at all)
python .\python\fj_probe.py --mode convert --pasted-request "POST /api HTTP/1.1`nHost: x`nCookie: a=1" `
  --convert-targets cookie-json,cookie-header
```

## Proxy

```powershell
# start the proxy from the UI (代理 → 代理抓包, default port 8899), then point a browser at it.
# The listen address defaults to this machine's LAN IP and is editable:
#   <LAN IP>:8899  reachable from the local network (default)
#   127.0.0.1:8899 this machine only   |   0.0.0.0:8899 all interfaces
#   HTTPS works through CONNECT but is not decrypted
```

`请求包` shows the request currently being sent (or waiting for release when interception is
on) and `返回包（放行后捕获）` shows the response of the last request that was released.
`转发到抓包转换` hands the displayed request over to the capture page, whose `填入探测页`
button then carries the URL, session cookie and body into the probe page.

Already captured request / response text survives navigation: leaving the proxy page and
coming back keeps the content (the placeholder is only written when the text area is still
empty). Only `清空记录` clears it.

`一键发送` on the capture page fills the target page in and jumps there, but **does not start
the probe by itself** — confirm the mode / body first, then press `开始探测` (`一键检测` on the
Shiro page). Previously it fired a detection run immediately, which sent traffic to the target
before the parameters had been reviewed.

`--probe-method` example:

```powershell
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode detect --probe-method GET
```

Probes are sent by `_send_probes()` with a bounded thread pool (`PROBE_CONCURRENCY = 4`), so
the run time no longer grows linearly with the probe count. On an unreachable target (3 s
timeout) `detect` dropped from ~20.6 s to ~0.2 s; the report only prints a `响应:` line when
the response is actually non-empty, and notes already covered by the summary are dropped.

## Shiro module

`主页 → 服务 → Shiro 漏洞利用` covers rememberMe detection, dictionary key cracking
(1108 built-in keys), echo-chain generation and command execution. Chains are built with
[java-chains](https://github.com/vulhub/java-chains) `2.0.0-beta4`, which is a declared
dependency of `src/pom.xml`; `lib/` holds the runtime jar and the manifest adds it to
`Class-Path`.

This module executes commands on the target, so use it only where you are authorized.
`生成 Payload` builds the payload without delivering it, which is useful when you want to
hand it to another tool. See [docs/DESIGN-shiro.md](docs/DESIGN-shiro.md) for the crypto,
cracking and chain details.

The `请求体` (request body) field is sent with detection and exploitation requests: some
endpoints only reach the rememberMe decryption branch when their business parameters are
present. `GET` / `HEAD` never send a body, so the standard library cannot silently downgrade
them to POST.

The Shiro page keeps **one output pane per action** (`指纹检测` / `密钥爆破` / `生成 Payload` /
`执行命令`) so a long key-cracking log or a long command output never overwrites the detection
conclusion or the generated payload.

## Payload generation

`主页 → Payload → Payload 生成` turns java-chains' "carrier + gadget node" model into a
visual page for generating exploit-chain payloads for Java vulnerabilities. It builds payloads
**in local memory only**: no network requests, no files written.

The interaction is **pick-a-column** (matching the web UI's Generate page):

1. The first column lists all 28 **payload carriers** (`javanativepayload`, `fastjsonpayload`,
   `shiropayload`, ...). Click one and the first-level node candidates open to its right
2. Click an entry in the second column and it is appended to the chain, which opens the next
   column to the right; and so on. Entries show the engine's registered display name (for example
   `TemplatesImpl加载字节码`); hover to see the node id
3. **Changing a middle level costs one click**: click a different candidate in an earlier column
   and the chain restarts at that column, dropping everything after it — no more repeatedly
   clicking `删除末节点` to back up
4. When a column has hundreds of candidates, narrow it with the filter box above that column
   (matches name or id; the currently selected node is never filtered out)
5. Node parameters become a form automatically (including dropdown choices and required markers);
   `生成载荷` produces the Base64 payload

Candidates always come from the **engine's own** criterion (first-level nodes validate each
`carrier + candidate` pair; successors come from the engine's successor table). Neither the page
nor this repository keeps a second node-relationship table. The chain text is read-only — a chain
is built by clicking, never typed. A node without successors simply does not open another column,
so a leaf never leaves an empty list behind.

The result shows the chain order, byte length, a body-free digest and the Base64 text. `复制`
copies it, `导出文件` writes it to the default export directory from the settings page (the
filename carries the carrier and a timestamp), and `填入抓包页` drops it into the request body
of `抓包转换` so it can be sent from there.

The page **never fills in a callback address for you**: the upstream sample values for JNDI / SSRF
nodes are kept as-is, and choosing an address stays the operator's decision. The consistency of the
carrier group table with the runtime catalog is asserted by `PayloadCheck`, so a missing or extra
registration fails outright.

The web UI's **surrounding features are deliberately not implemented**: brute-force matrix, stepped
debug generate, chain sharing, usage statistics, saving presets, tag filtering with
intersection/union, and output decompilation or serialization parsing. Preset chains stay a separate
page (`Payload → 预设链`), and the malicious-server page keeps its own carrier-group dropdown
(that one is a publishing flow).

The generic part lives in `src/payload/` (independent of any vulnerability type — the Shiro
module is just one consumer), the UI wiring is in `src/ui/PayloadPage.java`,
`src/ui/PayloadController.java` plus the column selector `src/ui/PayloadChainSelector.java`.
See [docs/DESIGN-payload.md](docs/DESIGN-payload.md).

## Preset chains

`主页 → Payload → 预设链` turns the built-in java-chains preset templates (52 of them across
7 categories) into a visual entry point, so you do not have to build every chain from scratch.

- The left side filters by **category**; the choices come from the built-in preset file itself,
  not from constants hard-coded in the source
- The right side shows the preset's **chain steps** (read-only, with each step's default
  parameters) and its **inputs**; the widget type follows the declared input type — booleans
  become checkboxes and inputs with candidate values become dropdowns, so you never have to
  guess which spellings are legal
- `生成载荷` builds locally and reports the chain order, byte length, a body-free digest and the
  Base64 text; `复制` copies it and `填入抓包页` drops it into the request body of `抓包转换`
- `发到恶意服务器` hands the carrier, chain and entered parameters to the server page, ready to publish

Preset carrier and node names are written in upper camel case while the engine registers them in
lower case, so the page converts them automatically. Parameters are resolved through
"step id → node name" before being assembled into the form the engine expects; using the step id
directly would be rejected as an unknown parameter.

## Malicious servers

`主页 → 服务 → 恶意服务器` really starts and stops the five java-chains server-side services so a
built payload can be published and pulled back by a target. It **only listens locally and never
sends a payload to any target on its own.**

| Service | Purpose | Default port |
| --- | --- | --- |
| JNDI | LDAP / RMI / HTTP trio for JNDI injection chains; enable LDAPS when an HTTPS callback is needed | LDAP 50389, RMI 50388, HTTP 58080 |
| HTTP | Hosts payload bytes for a target to fetch by URL | 50000 |
| TCP | Raw TCP delivery of a deserialization payload | 11527 |
| FakeMySQL | Masquerades as a MySQL server to trigger JDBC deserialization | 3308 |
| JRMP | Listens and returns serialized objects | 13999 |

Three steps: pick a service, fill in the ports (pre-filled from the settings page), click
`启动服务`; then choose a carrier and chain below and click `发布载荷`. The output pane prints a
**ready-to-copy callback address**:

- HTTP yields `http://<advertised host>:<port>/<publication id>`
- TCP / JRMP yield protocol addresses, FakeMySQL yields a JDBC URL carrying the user name
- JNDI: upstream returns no address, so the tool assembles the LDAP / RMI / HTTP entries from the
  enabled ports (multi-line; the first line is what gets filled into the capture page)

Several measured constraints are encoded in the implementation: non-JNDI services must use the
port key `main` while JNDI must use its per-protocol keys; LDAPS additionally requires a JKS
certificate path, so that port is **not sent** unless explicitly filled in (otherwise the whole
JNDI start is rejected); payload type must match the protocol — JRMP accepts objects only,
JNDI / FakeMySQL reject text, and HTTP / TCP take bytes or text.

**Closing the main window stops every service before exiting**: once started, a service really
holds its ports, and leaving them behind makes the next launch fail with "address already in use".

`src/service/` is the only package in this project that talks to the java-chains server-side
adapters (it exposes plain data classes upwards); the UI wiring lives in `src/ui/ServicePage.java`
and `src/ui/ServiceController.java`, with the design in
[docs/DESIGN-services.md](docs/DESIGN-services.md). **No Spring or web container is needed**: the upstream adapters
are plain-JDK and were measured to run directly on Java 17.

## CLI smoke test

```powershell
# identify
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode detect

# version range
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode version

# expected class (base body should look like the real business request)
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode expect --base-body '{"age":20,"name":"Bob"}'

# DNS probes with CEYE confirmation
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode dns --dnslog-host abc.ceye.io --ceye-token <token>

# confirmation only
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode ceye --ceye-token <token> --dns-filter fjtest

# turn an optional stage off for any mode
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode detect --no-dns --no-ceye
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode dns --dnslog-host abc.ceye.io --no-ceye

# send a logged-in session cookie
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode detect --headers '{"Cookie":"JWT_TOKEN=..."}'

# same thing through the dedicated switch (merges with --headers instead of replacing it)
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode detect --session-cookie "JWT_TOKEN=x; JSESSIONID=y"

# full detail report (the default is the condensed report)
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode detect --report detail

# keep the raw structured result instead of the rendered report
python .\python\fj_probe.py http://127.0.0.1:8080/api/json --mode version --format json
```

`CEYE_TOKEN` / `CEYE_DOMAIN` / `CEYE_API` environment variables work as well.

## Tests

```powershell
python -m unittest discover -s tests
```

The Java self-checks run against the compiled classes and print their own assertions:

```powershell
# build first (produces target\classes and lib\java-chains-cli-2.0.0-beta4.jar), then compile the checks
.\build.ps1
E:\java\jdk17\bin\javac.exe -encoding UTF-8 -cp "target\classes;lib\java-chains-cli-2.0.0-beta4.jar" -d target\tmp2 tests\*.java

# all six checks (--add-opens lets bytecode gadgets reach the JDK-internal xalan classes; run.ps1 already adds them)
$opens = @('--add-opens', 'java.xml/com.sun.org.apache.xalan.internal.xsltc.trax=ALL-UNNAMED',
           '--add-opens', 'java.xml/com.sun.org.apache.xalan.internal.xsltc.runtime=ALL-UNNAMED')
E:\java\jdk17\bin\java.exe @opens -cp "target\tmp2;target\classes;lib\java-chains-cli-2.0.0-beta4.jar" UiNavigationCheck
E:\java\jdk17\bin\java.exe @opens -cp "target\tmp2;target\classes;lib\java-chains-cli-2.0.0-beta4.jar" UiSwitchEndToEndCheck
E:\java\jdk17\bin\java.exe @opens -cp "target\tmp2;target\classes;lib\java-chains-cli-2.0.0-beta4.jar" ProxyServerCheck
```

| Self-check | Coverage | Needs `--add-opens` |
| --- | --- | --- |
| `UiNavigationCheck` | Sidebar expand / collapse, per-page widgets and end-to-end flows; screenshots go to `target/ui-check/` | Yes (preset end-to-end build) |
| `UiSwitchEndToEndCheck` | Captured headers fed to the probe page through the real Python engine | Yes |
| `UiShiroCheck` | Full Shiro page flow | Yes |
| `ShiroCheck` | Shiro engine (detect / crack / chain build / echo) | Yes |
| `PayloadCheck` | Payload engine (catalog, groups, navigation, node display names, dual form, failure paths, safety) | Yes |
| `ProxyServerCheck` | The proxy itself: plain-HTTP capture, 404 handling, `CONNECT` tunneling with byte pass-through, callbacks, `find` / `clear` | No |

Dependency-boundary and toolchain checks:

```powershell
python -X utf8 tools\audit_boundary.py        # package dependency boundaries (acyclic / layering / generic components / shared kernel)
powershell -File tools\check_agent_tools.ps1  # 91 mechanical assertions for the multi-agent toolchain
```

Self-checks execute the command embedded in gadget parameters while building chains (the upstream `Clojure` / `Exec` nodes default to `calc`), so all six entry points call `tests/TestProcessGuard.java` on startup: it snapshots the existing `CalculatorApp` processes and installs a JVM shutdown hook that force-kills only the ones started after the snapshot, logging a `[calc-guard]` line. A leftover calculator after a run means that entry point is missing the guard.

Only run this against systems where testing is explicitly authorized.
