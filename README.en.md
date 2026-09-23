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
- `Payload` — first-level category; click it to expand / collapse `Payload 生成`, `预设链`,
  `toString 链` and `HTTP 带外 Jar`
- `服务` — first-level category; click it to expand / collapse `恶意服务器` and `Shiro 漏洞利用`
- `代理` — first-level category; click it to expand / collapse `代理抓包` (local HTTP proxy)
  and `抓包转换` (capture one request, inspect the raw response, convert formats)
- `FastJson` — first-level category; click it to expand / collapse `Fastjson 探测`
- `漏洞分析` — first-level category; click it to expand / collapse `组件与漏洞` (read dependency
  coordinates, judge known vulnerabilities and which gadget chains are present) and `调用链查询`
  (build a call-graph database with an external engine, gather facts, judge vulnerability types
  and bypass techniques)
- `小工具` — first-level category; click it to expand / collapse `文件上传` (pick a local file,
  upload it as multipart to a target endpoint, and keep the raw response)
- `配置` — settings, grouped into `通用配置` and per-feature sections

The order mirrors the left-hand menu of the java-chains web UI. The main window now starts
**maximized**: the server page has to fit the service list, listen parameters, payload publishing
and the output pane at once, and the parameter column gets squeezed at smaller widths.

First-level categories show a `▾` / `▸` marker pinned to the right edge of the row; clicking one toggles its children without leaving the current page. `打开探测` on the home card expands the group and jumps straight to the probe page.

## Startup warm-up

Everything that needs a one-off initialisation is done on a background thread at launch, so
entering a page later never stalls:

- the java-chains engine (`MetadataRegistry` measured at about 1.1 s; 429 nodes, 28 carriers, 52 presets)
- the preset-chain catalog and the malicious-server service adapters

A **splash screen** appears first while a background thread warms up; the main window is built on
the event dispatch thread, and if the warm-up has not finished, the remaining steps are completed
before the window settles. The warm-up is **idempotent**: a second call returns in 0 ms, so revisiting
a page never re-initialises anything. The progress text is the per-step timings, so it is obvious
which step is slow.

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
- `toString 链配置` — default toString template (the choices come from the built-in templates
  themselves) and the default payload command
- `带外 Jar 配置` — default bind address, default port (50001), default download URL,
  default on-disk path (`/tmp/payload.bin`) and default exec parameters
- `恶意服务器配置` — default bind address, default advertised address, and the JNDI LDAP / RMI / HTTP
  ports plus the HTTP service, JRMP, FakeMySQL and TCP ports (blank or 0 keeps the default)
- `预设链配置` — default category filter for the preset page (choices come from the built-in preset file itself)
- `小工具配置` — the file-upload page defaults: upload URL, form field name (default `file`) and
  upload timeout (default 30 seconds)
- `日志配置` — whether logging is on (on by default), the log level (`仅错误` / `警告与错误` / `常规` /
  `调试`, default `常规`), the log directory (blank writes under the user home; the `JSETK_LOG_DIR`
  env var overrides it), the retention window in days (default 7, today included) and whether to
  echo records to the console (off by default)
- `漏洞分析配置` — default scan target (jar / dependency directory), external engine JAR
  (`jar-analyzer-engine`; blank means local analysis only), engine working directory (the engine
  always writes `jar-analyzer.db` there), analysis timeout (default 300 seconds), the
  decompile output directory (blank writes to `decompiled` under the working directory) and the
  external gadget rules file (blank keeps the built-in rule table only; format is described in
  the "Available gadgets" section), whether to also export a machine-readable JSON after signature
  matching (on by default) and the machine-readable report directory (blank writes
  `analyze-report.json` under the backend working directory)

Saved values pre-fill **every feature page** (probe, proxy, capture, Shiro, Payload, presets,
malicious servers, the toString page, the out-of-band Jar page and the analysis page) and are pushed to already-open
pages right after saving, with no restart needed. After the proxy
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
| `src/` | Composition root `Main.java` (352 lines: wiring and page switching only), `pom.xml`, the local proxy (`proxy/ProxyServer.java`), and the Shiro module (`shiro/`) |
| `src/ui/` | Per-feature **views** and **behaviour** kept apart: views `*Page` / `*Form` (`HomePage` / `ProbePage` / `CapturePage` / `ProxyPage` / `ShiroPage` / `PayloadPage` / `PresetPage` / `ServicePage` / `ConfigPage` / `ConfigForm` / `ToolsUploadPage` / `PayloadToStringPage` / `OobJarPage` / `AnalyzeScanPage` / `AnalyzeChainPage`), behaviour `*Controller` (`NavController` / `ProbeController` / `CaptureController` / `ProxyController` / `ShiroController` / `ConfigController` / `PayloadController` / `PresetController` / `ServiceController` / `ToolsUploadController` / `PayloadToStringController` / `OobJarController` / `AnalyzeScanController` / `WorkbenchPages`), plus styling `UiKit`, the shared `ChainEditor`, startup warm-up `StartupWarmup` / splash `StartupSplash`, the column selector `PayloadChainSelector` (the payload page's chain picker, holding no chain state) with its collaborators `ChainSelectorSizing` (geometry) / `ChainColumn` / `ChainColumnState` / `ChainColumnPanel` (per-column panel and filter state) / `ChainResizeHandle` (vertical drag bar) / `ChainColumnFilter` (filter predicate) / `ChainNodeRenderer` (row rendering) / `ChainTagMenu` (tag menu), the payload page split-outs `PayloadPanels` (panel building) / `PayloadColumns` (column mapping) / `PayloadOutputText` (output text) / `PayloadExporter` (export to disk), the shared background runner for both analysis pages `AnalyzeWorker` (keeps the UI responsive, serialises actions per page, truncates suggestion buttons), and the self-check facade `UiHandle` + `WidgetRegistry` |
| `src/payload/` | Generic payload generation (`PayloadEngine` / `PayloadCatalog` / `PayloadResult`), decoupled from concrete features such as Shiro; feature templates `JarPreset` (out-of-band Jar kinds × tail actions), `ToStringPreset` (toString chain templates) and the node-ownership rule `ChainScope` |
| `src/service/` | Malicious servers (`ServiceManager` / `ServiceSpec` / `ServiceEndpoint` / `ServiceDefaults`) plus out-of-band Jar hosting (`OobJarService`): the only package that talks to the java-chains server-side adapters |
| `src/preset/` | Built-in preset chains (`PresetCatalogService` / `PresetItem`): the only package that touches the upstream preset model, exposing plain data |
| `src/probe/` | Engine invocation and command-line assembly (`ProbeEngine` / `ProbeCommand` / `CaptureBridge`) |
| `src/analyzer/` | Vulnerability-analysis kernel (a leaf package that depends on no project package): dependency coordinates `Dependency` / `DependencyScanner` / `PomScanner`, version comparison `Version`, component rule table `VulnerabilityRules`, findings `Finding` / `VulnerabilityAnalyzer`, gadget judgement `GadgetRule` (requirement / version range / wildcard) / `GadgetRules` (built-in table) / `GadgetRuleFile` (external rule-file parsing) / `GadgetInventory` (judgement and rendering), external engine `EngineRunner`, query enum `ReportReader`, script execution `ScriptRunner`, decompilation `Decompiler` |
| `src/analyze/` | Vulnerability-analysis orchestration (depends on `analyzer` only): `AnalyzeEngine` (local analysis / engine run / query / signature match / decompile), `AnalyzeReport`, `AnalyzeCommand` (the command line is the single contract, kept in one place so it can be diffed against upstream docs) |
| `src/config/` `src/util/` | `config.properties` read/write; message parsing and platform differences; the logging kernel `Log` (record channel) and `LogFiles` (file names and retention) |
| `python/` | probe engine (`fj_probe.py`), the call-graph database query script (`jar_report.py`) and vulnerability signature matching (`jar_signatures.py` plus the library `vuln_signatures.json`), all packaged into the JAR |
| `tests/` | Python unit tests and Java UI self-checks; `TestProcessGuard.java` kills calculator processes spawned during a self-check run |
| `tools/` | maintenance helpers: `agent.ps1` (launch a role-scoped agent), `dispatch.ps1` (dispatch one role and auto-merge), `orchestrate.ps1` (one-sentence goal, automatic decomposition and orchestration), `watchdog.ps1` (stall watchdog), `lib/` (role matrix and execution primitives), `audit_boundary.py` (dependency audit), `apply_patch.py` |
| `docs/` | design documentation (`DESIGN.md`, `DESIGN-shiro.md`, `DESIGN-probe-accuracy.md`, `DESIGN-agents.md`, `DESIGN-modularization.md`, `DESIGN-payload.md`, `DESIGN-services.md`, `DESIGN-analyze.md`) plus the multi-agent role guide (`AGENT-ROLES.md`) and the runbook (`AGENT-RUNBOOK.md`) |
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

The tooling has its own regression check (`tools\check_agent_tools.ps1`, 91 mechanical
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

## File upload

`小工具 → 文件上传` sends one local file to a target upload endpoint and hands the raw response
back to you:

- `目标 URL` is the upload endpoint; `选择文件` opens the native file dialog (the path field is
  read-only so an unreachable path cannot be typed in);
- `表单字段名` is the form field the target reads the file from, default `file`
  (other frameworks commonly use `upload` / `multipartFile`);
- `附加表单字段（JSON）` carries plain form fields the endpoint expects, e.g. `{"csrf":"abc"}`;
- `请求头（JSON）` carries the session, e.g. `{"Cookie":"JWT=xxx"}`;
- `上传` sends exactly **one** multipart POST and does not follow 3xx redirects; `复制结果` copies
  the report to the clipboard.

The body is assembled from **raw bytes**, so the file content is never rewritten by a text codec,
and the boundary declared in `Content-Type` always matches the one used in the body. The per-file
limit is 8 MB; larger files are skipped with a readable reason.

Every failure path produces a readable conclusion instead of failing silently: empty target URL,
no file selected, missing or unreadable file, size over the limit, extra fields that are not valid
JSON, connection failure, 3xx redirect, 401 / 403, 404, and 5xx.

Upload is a "content is the result" feature, so its report is **always detailed** (same as capture
and convert) and does not follow the brief / detailed switch of `探测报告配置`.

```powershell
# Equivalent CLI invocation
python python\fj_probe.py --mode upload --upload-url http://host/upload ^
  --upload-file payload.txt --upload-field file --upload-fields "{\"csrf\":\"abc\"}" --format text
```

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

The layout follows the **measured proportion** of the web UI: driving headless Chrome over CDP
against the upstream `#/Generate/<payload>` route gives a console (`.studio-top-console`) of 360 px
and a chain area (`.chain-builder-block`) of 486 px, a 360 : 486 split. The vertical divider therefore
matches the **ratio** (the chain area takes about 57.4% of the two blocks) instead of copying the
360 px absolute value. The list is 320 px by default, draggable between 160 px and 640 px, and a
double-click on the drag bar toggles between 320 px and 480 px; column width is shared evenly
between 300 px and 500 px and falls back to horizontal scrolling when the columns do not fit —
all of these numbers match the web UI.

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
intersection/union, and output decompilation or serialization parsing. Preset chains, toString chains and the
out-of-band Jar builder are separate pages (`Payload → 预设链` / `toString 链` / `HTTP 带外 Jar`),
and the malicious-server page keeps its own carrier-group dropdown (that one is a publishing flow).

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

## toString chains

`主页 → Payload → toString 链` generates exploit chains that fire when some class is
`toString()`-ed, with a customisable target class and copyable templates. The picker splits
**trigger** from **tail**: choose the trigger node (which class gets toString-ed), then the relay
node; the tail is fixed to bytecode execution.

Five templates are shipped, all verified to build (`ToStringPreset`):

| Template | Trigger node | Relay | Dependency |
| --- | --- | --- | --- |
| `CC3 toString + Jackson` | `CaseInsensitiveMap3.toString` | Jackson | commons-collections:3.x |
| `CC4 toString + Jackson` | `CaseInsensitiveMap4.toString` | Jackson | commons-collections4 |
| `CC3 toString + Fastjson` | `CaseInsensitiveMap3.toString` | Fastjson | fastjson + commons-collections:3.x |
| `EventListenerList toString` | `EventListenerList.toString` | Jackson | jackson-databind |
| `GString compareTo toString` | `GStringCompareTo.toString` | Jackson | groovy + jackson-databind |

The chain order is always **carrier → trigger → relay → bytecode execution**:

```
javanativepayload -> <trigger> -> <relay> -> templatesimpl -> bytecodeconvert -> exec
```

The trigger node has to be the **first** gadget: an earlier version left it outside the template and
all five templates failed with "chain not accepted by the engine". Only `JacksonToString` and
`FastjsonToString1` can relay into a bytecode chain; `XString*` / `XalanXString*` do not form a valid
chain under `JreFilter`, and templates with the `HighJDK` suffix need `java.io` / `java.util` opened
up, so none of them are included. This is deliberately **not** every upstream toString node — only
the ones measured to produce a payload.

`自定义目标类` goes through `BytecodeConvert.classNameMode=manual` and
`BytecodeConvert.className`: the tail still performs a real action, and the class name only decides
the name of the class that lands, not how the chain fires. Blank keeps the engine's random name.
Templates can be copied in one click for further editing elsewhere.

Node ownership lives in exactly **one rule** (`src/payload/ChainScope.java`, 23 trigger nodes): the
toString page reads it to decide which triggers are available, and the generic generation page reads
it to remove those triggers from its candidates. The filter sits at the candidate layer and is not
put into the shared `ChainEditor` — doing that would also strip the malicious-server page's ability
to publish toString payloads.

## Out-of-band HTTP Jar

`主页 → Payload → HTTP 带外 Jar` builds a Jar that can be loaded and run once it
lands, and hosts it from this machine for the target to pull. The page asks two things: what kind of
Jar to build, and what that Jar should do once it runs.

Five wrapper kinds:

| Kind | Use case |
| --- | --- |
| Plain JAR | standard wrapper, loadable by the target ClassLoader directly |
| Charset SPI JAR | for SpringBoot write-a-Jar-to-disk scenarios |
| Groovy SPI JAR | auto-loaded when the target ships Groovy |
| SnakeYAML SPI JAR | auto-loaded when the target ships SnakeYAML |
| JDBC Driver JAR | for cases where a driver can be uploaded or loaded |

Five tail actions: `download from URL and exec`, `exec a command`, `callback HTTP request`,
`download a file from a URL`, and `DNSLog probe`. **All 25 kind × action combinations build, and
every artifact is a valid Zip (`PK\x03\x04` magic).**

The chain order is always **carrier → wrapper → bytecode convert → tail action**:

```
otherpayload -> <kind> -> bytecodeconvert -> <action>
```

Inputs follow the selected action: the page enables only the fields the action **declares**
(letting someone fill in a field that is never sent is worse than not showing it). Blank values are
never sent, because sending an empty string overwrites the upstream default — which in practice
produced payloads with an empty command. Ticking "write `Main-Class`" makes the artifact directly
executable.

The hosting area offers `start` / `stop`: once started it prints a **copy-ready callback URL** in the
output pane, so a browser or `curl` can pull the Jar back to verify it. Hosting **must use the same
service instance** — publishing through another instance reports success but falls back to the
upstream default port 50000, and that URL does not open. The constraint lives inside
`src/service/OobJarService.java`, so callers cannot break it. Closing the main window stops hosting
before exiting.

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

## Vulnerability analysis

`主页 → 漏洞分析` answers two different levels of question, so it is split into **two genuinely
independent pages** with three very different costs:

| Sub-item | Angle | What it does | Cost |
| --- | --- | --- | --- |
| `组件与漏洞` | Dependencies | Reads the Maven coordinates inside a jar, judges known vulnerabilities against 25 built-in rules, and reports which gadget chains are present on the classpath | Seconds; needs no external program |
| `调用链查询` | Code | Runs an external engine to turn a jar into a call-graph database, gathers facts (sink hits / entry points / string constants), judges **likely vulnerability types and bypass techniques**, and can decompile a class to confirm | Minutes; needs the engine jar |

Both pages have **their own widgets, report pane and suggestion-button container**: a finding on the
dependency page never overwrites the report on the code page, and vice versa. Previously both
sub-items pointed at the same view, so clicking either one showed the same thing and there was no way
to tell which angle a finding came from. Their headings now read `ANALYZE · DEPENDENCIES` and
`ANALYZE · CODE`.

### Components and vulnerabilities (local analysis)

The target can be a **single jar** or a **dependency directory** (every jar inside is taken
recursively); you can additionally point at a source project's `pom.xml` to compare what the project
declares against what the artifact actually contains.

Coordinates are trusted by source, and that source travels all the way into the finding (it decides
the confidence):

1. `META-INF/maven/**/pom.properties` — a fact written by Maven at packaging time
2. `META-INF/maven/**/pom.xml` — also a packaging artifact, but shading may rewrite it
3. `META-INF/MANIFEST.MF` — written by packaging plugins, coarser
4. File-name inference — a guess only, lowest confidence

Reading goes entirely through `JarFile` entry enumeration: **nothing is unpacked, nothing is written
to disk, no class is loaded**. The target jar can come from anywhere, and it must not get a chance
to execute while being analysed.

The rule table has 25 entries covering fastjson (six of them split by autoType bypass technique),
fastjson2, Shiro (four: the default rememberMe key plus two authorisation bypasses), gadget
dependencies (CommonsCollections 3 / 4, Groovy, ROME, CommonsBeanutils, c3p0, Hutool) and component
RCEs (two Log4Shell ranges, Spring4Shell, XStream, SnakeYAML, Jackson-databind, FakeMySQL scenarios).

The rules are **written in this project**: upstream `jar-analyzer`'s rule files are GPLv3 and cannot
be copied into this repository. The content comes from each component's public advisories, and only
entries that lead to a next action inside this toolkit are included. Findings are sorted by
confidence, and the report always ends with "a miss does not mean there is no vulnerability".

Every finding carries two independent dimensions: **confidence** (how reliable the source is) and
**status** (how far verification has got). Status is one of `observed` / `suspected` / `confirmed` /
`not-reproduced` / `unknown`, and a static rule hit is **always `suspected`**: high confidence is not
the same as a confirmed vulnerability. Only both dimensions together stop "high confidence from pom
metadata" from being read as "the vulnerability is confirmed".

A finding also answers "**what is still missing**": the report has "still required (all of them)" and
"refutation conditions" columns, plus the **rule source** the judgement rests on. The rule table
carries a version and a maintenance date which the report header prints: a rule library without a
version cannot answer "which version of the rules produced this", nor explain why a conclusion changed
after the rules were updated.

Once a finding is produced, an **analysis hand-off bar** appears at the top of the content area: it
names the source, target, hit evidence and limitations, with a one-click jump back to the page that
produced it. It **only displays, never pre-fills**: class names, constants and inferred callback
addresses are never written into commands, callback addresses or target URLs, and it sends nothing,
generates no payload and starts no service. After 30 minutes the bar adds a reminder suggesting you
re-check before reusing the conclusion, otherwise a judgement made a while ago looks like it was just
produced.

Engine invocations decide success by the **real exit code of the child process**: the exit code used
to be discarded, so "the query did not run" and "the target is clean" looked exactly the same in the
UI. Now any non-zero exit code or timeout puts a failure banner ahead of the report stating that this
is not a conclusion and does not mean the target is clean, and the status line never shows "done".

Measured boundaries: local dependency analysis takes about 30 ms per jar, building the database is a
minute-scale job, and signature matching runs over the 65 features in roughly a hundred milliseconds.

Each suggestion is a button that jumps straight to the right page (probe / Shiro / Payload
generation / toString chains / malicious servers / capture conversion), so you never have to hunt
through the sidebar.

#### Available gadgets (dependency angle)

The third section of the report answers a different question: **given these dependencies, which
chains can this toolkit actually build?** It is split from the rule table on purpose — the rule table
judges whether a component itself has a known vulnerability, while this section judges the
*combination*: CommonsCollections is not a vulnerability, it is the ability to reach RCE when a
deserialisation entry point exists.

The criterion is **Maven coordinates plus a version range**, not jar file names. This is the biggest
difference from the gadget analysis in upstream `jar-analyzer`, which decides by "are all these jar
names present in the scanned directory" and therefore counts
`commons-collections-3.2.2.jar` (where the InvokerTransformer chain was patched) as usable.
This project's requirement model supports four things:

- `groupId` prefix plus `artifactId` (with `*` wildcards)
- version ranges (`lower` / `upper`) and **excluded versions**
- a requirement with an incomparable or missing version counts as **not satisfied** (better to stay
  silent than to misreport)
- every dependency of a chain must be satisfied **at the same time** (upstream's AND semantics)

There are 39 built-in rules, grouped by type: native deserialisation (CC3 / CC4 / Beanutils / c3p0 /
Groovy / Jython / BCEL / AspectJWeaver / JDK7u21), Hessian, JDBC drivers (MySQL both coordinates /
PostgreSQL / H2 / Derby), fastjson, Jackson, toString triggers (Rome) and templates and expressions
(Velocity / Freemarker / javax.el / Xalan).

The rules are **written in this project**: upstream `jar-analyzer` is GPLv3 and its `gadget.dat`
cannot enter this repository; only the idea of multi-dependency AND matching is borrowed. The
inclusion bar is the existing one — a chain must lead to a next action, and components that lead
nowhere are left out.

The report also lists **missing chains and what is missing** (for example
"[缺失] CommonsCollections 4　需要 org.apache.commons:commons-collections4 不限版本").
Reporting only what is present is not enough: you would keep trying a chain in "Payload generation"
that can never be built.

The section always ends with its boundary: **it only proves the gadgets are on the classpath, not
that the chain is reachable**. Reachability depends on whether a deserialisation entry point exists
and whether its arguments are attacker-controlled; follow up with sink hits and signature matching
on the call-graph page.

#### External rules file

The built-in table only covers chains that lead to a next action inside this toolkit, but a real
target may use something else. So that "analyse more gadgets" does not require a rebuild, the
settings page (`漏洞分析配置`) lets you point at an **external rules file**.

The format follows upstream's `gadget.dat` shape (**the format only — none of its data files are
included**): one rule per line as `jar名,…|类型|结果`, `#` starts a comment, and the line is split
on `|` into three parts:

```
# every jar name is translated into coordinates plus a version range
commons-collections-3.2.1.jar|NATIVE|external rule: CC3
*-collections4.jar|NATIVE|wildcard artifactId
commons-collections-!3.2.1.jar|NATIVE|exclude everything except 3.2.1
```

Unlike upstream's literal file-name comparison, each jar name is parsed into three things:
`commons-collections-3.2.1.jar` becomes artifactId `commons-collections` with upper bound `3.2.1`;
`!3.2.2` becomes an excluded version; `*-core.jar` becomes a wildcard artifactId.

**Unparsable lines are reported line by line** (with the line number) instead of being skipped
silently: a mis-written rule makes you believe the chain was evaluated and found absent, when in fact
it was never loaded. Valid lines still take effect. Leaving the field blank disables the file
entirely, and only the built-in table is used.

### Call-graph query (external engine)

Call-graph analysis needs the `外部引擎 JAR` (`jar-analyzer-engine`, MIT, a CLI) set on the settings
page. Its database file is fixed at `jar-analyzer.db` and **can only land in the working directory**
(there is no output-path argument), hence the `引擎工作目录` field. Without an engine the page does
not error; it just tells you to fill it in.

Building a large jar takes minutes, so there is a `调用链超时` (default 300 seconds; anything below
30 seconds is rejected up front). On timeout the **process tree is killed** and the temporary
directory is cleaned. Once the database exists you can query five kinds of content repeatedly:

| Query | Purpose |
| --- | --- |
| 总览 (summary) | Class / method / call-edge counts — confirm the database built successfully |
| 入口点 (entry points) | Spring Controller / Servlet / Filter / Listener |
| Sink 命中 (sink hits) | Match dangerous calls against the built-in sink list (command execution / JNDI / deserialisation / SQL / file / SSRF — 26 entries) |
| 字符串常量 (string constants) | Search SQL, URLs, keys and other sensitive strings (the only query that uses the keyword) |
| 组件清单 (components) | Component versions from the engine's own view, to compare against the local analysis |

The sink list is likewise written in this project (upstream's sink and SCA data live in the GPLv3 GUI,
not in the engine). Queries go through Python's standard-library `sqlite3`, always opened `mode=ro`
— **read-only**, never writing to the database.

#### Signature matching (judging)

The five queries above answer "what is in the database" — the output is a fact. To answer "**what do
these facts look like**", click `漏洞特征匹配` on the same page (optionally filtering by severity with
the dropdown next to it).

All four kinds of evidence are read from the database with read-only SELECTs:

| Evidence | Source table | What it reveals |
| --- | --- | --- |
| Constants | `string_table` | Hard-coded keys, JNDI addresses, Log4j lookup expressions, upload suffix blacklists |
| Classes | `class_table` | Deserialisation implementation classes, entry classes (Filter / Servlet / Controller), template engines |
| Methods | `method_table` | Callbacks and entry points such as `readObject` / `lookup` / `doGet` |
| Calls | `method_call_table` | Dangerous calls such as `Runtime.exec` / `ObjectInputStream.readObject` / `InitialContext.lookup` (the same data as the sink list) |

The signature library (`python/vuln_signatures.json`) covers **14 high-risk Java vulnerability
classes**: deserialisation, JNDI injection, expression injection, server-side template injection,
command execution, code execution (class loading / bytecode), SSRF, SQL injection, path traversal /
arbitrary file access, XXE, file upload, configuration / sensitive-information exposure, credential
leakage and denial of service. Each class records its **trigger condition** and the **bypass
techniques seen in practice**, and the report lists them by severity.

For example, the deserialisation class lists blacklist bypasses (fastjson's `L` prefix, doubled `LL`,
the `[` prefix, the `TypeUtils` cache and `Throwable` expected classes), Jackson's `@class` single
value and array wrapping, SnakeYAML's `!!javax.script...`, XStream chains outside the blacklist,
second-order deserialisation (`SignedObject` / `RMIConnector` / c3p0) and the point that changing a
field on the target class requires recomputing `serialVersionUID` or the payload simply fails.

The report reads: summary, then **likely vulnerability types and bypass techniques** (each with its
trigger condition, the evidence it rests on and the bypass list), then hit details (feature id, hit
count, samples, with `[入口点]` marking externally reachable classes), then the list of features that
did not hit. That last part draws the line between "it ran" and "it did not".

The signature library and the sink list **must stay identical**: `tests/test_signatures.py` compares
the `(class, method)` pairs of both sides, so any drift fails the suite. Drift would be silent — the
report would simply miss one class of vulnerability without erroring. The same test also guards
"every vulnerability class is reachable by at least one feature", otherwise that class could never
be reported.

Matching is a **judgement, not a verdict**: a hit only means the feature is present. Whether the
argument is controllable and whether the target really parses that data still needs a human. If a
single feature has an invalid regex, or points at an unregistered vulnerability class, the whole run
fails and says which feature it was — skipping silently would make a false negative look like a clean
result.

Every finding carries a **status** and the **rule source**: static signature hits are always
"`suspected`", the header prints the rule-library version and maintenance date, and each finding lists
"still required (all of them)" and "refutation conditions". Bypass techniques are filtered by the
**facts that actually hit** instead of being pasted for a whole vulnerability class — most entries in
such a list rely on components that are not in the target at all.

#### Filtering by type and evidence source

Three filter controls sit next to the signature-matching button: `severity`, `evidence source`
(all evidence / sink calls only / string constants only / class names only / method names only) and
`vulnerability type` (all types / command execution / deserialisation / …).

Filtering happens **in the script, not by filtering rows in the UI**: the aggregation, the bypass
filtering and the "not hit" list all depend on which features took part this time, and filtering in the
UI would split the hit counts from that list so they no longer add up. The report header states the
filter and notes that the "not hit" list only covers the filtered features.

**An unknown value is an error listing the valid ones**, never silently ignored: silently ignoring it
would make "filtered to nothing" look like "the target is clean". When a filter selects nothing the
report says so explicitly, and states that this does not mean the rest of the library missed.

The vulnerability-type choices are not hard-coded in the UI; the signature library provides them via
`--list-filters` (it reads the library only and **touches no database**, about 90 ms measured), and the
page fills the combo box in a background thread. If the values cannot be read, the filter falls back to
"no filter" rather than filtering everything away.

#### Re-running the same input for comparison

`与上次比对` (on by default) uses the previously exported machine-readable report as a baseline and
appends the differences: **newly hit**, **no longer hit** and **hit-count changed**. "This differs
from last time" is a signal in itself — the target may have changed, or the rule library may just have
been updated — and a single current result cannot explain why the conclusion moved.

If the baseline is missing, corrupt or written with a different schema, the comparison **falls back to
not comparing** and the run still succeeds: the comparison is extra information and its absence must
not fail the analysis. The report also keeps "no differences" and "no baseline" apart.

#### Machine-readable export

With "export machine-readable report" enabled in the settings page (on by default), signature matching
also writes a JSON file (by default `analyze-report.json` under the backend working directory) with the
schema `jsetk.analyze.signatures/1`. It contains the data-source status, dimension notes and
limitations, plus **task metadata**: tool version, database path and SHA-256, database size and
modification time, generation time and the filters that were in effect. Without those fields the file
is only "a result", not "a reproducible result".

If the export fails, the report says "not written" and gives the path instead of showing success:
reporting success while nothing was written sends people to downstream tooling with a file that does
not exist.

### Decompilation

When you need the source to confirm whether a rule or a signature really holds, fill in `指定类名`
(blank decompiles the whole jar) on the call-graph page and click `反编译`; the output goes to
`反编译输出目录`.

It uses the **CFR already bundled with the runtime dependency**, so **no new dependency, no extra
process and no Node** (measured: 84 classes decompiled in 3.5 seconds). The output is **write-only**:
this toolkit never parses those sources, it just leaves them for your editor. CFR is a synchronous
API and cannot be interrupted, so a timeout here means "reported after the fact" rather than "cut
off" — an over-budget run is flagged but the output is kept.

See [docs/DESIGN-analyze.md](docs/DESIGN-analyze.md) (Chinese).

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

# file upload (multipart, exactly one request; --upload-file may be repeated, the UI sends one)
python .\python\fj_probe.py --mode upload --upload-url http://127.0.0.1:8080/upload ^
  --upload-file .\payload.txt --upload-field file --upload-fields '{"csrf":"abc"}' --format text
```

`CEYE_TOKEN` / `CEYE_DOMAIN` / `CEYE_API` environment variables work as well.

## Logging

Runtime exceptions and key actions are written per day to
`%USERPROFILE%\.JavaSecExpToolKit\logs\app-YYYY-MM-DD.log`, so that "the button
did nothing" can be investigated afterwards. Design tradeoffs are in `docs/DESIGN-logging.md`.

- **One file per day** — every line carries a timestamp, a level and the thread name; exceptions
  are recorded with their full stack. The writer switches to a new file when the date rolls over,
  so a long-running session does not pile everything into one file.
- **On by default** — troubleshooting should not require turning it on first. Once disabled, no new
  files are created and existing logs are kept.
- **Scheduled cleanup** — the last 7 days (today included) are kept by default; cleanup runs at
  startup and whenever the file rolls over to a new day. Cleanup only considers files this tool
  named, so anything else in that directory is left alone.
- **Never blocks the main path** — a full disk, an unwritable directory or a locked file only drops
  that single record and never affects any feature. The log contains no request bodies, response
  bodies, keys or tokens.

Four levels are available: `仅错误` / `警告与错误` / `常规` (default) / `调试`. Switch to `调试`
when the usual records are not enough — it also logs normal wind-down paths such as tunnels
closing, streams ending and nodes having no successors.

Exceptions from background threads (the proxy accept loop, tunnel pumps, engine output readers) are
captured by the default uncaught-exception handler: those threads are not on the event dispatch
thread, so by default their failures appear neither in a dialog nor anywhere in the UI.

The log directory is resolved as "env var `JSETK_LOG_DIR` > value in the settings page > default
under the user home". Changes take effect on save with no restart; the settings status line shows
the effective absolute path and how many files this startup cleaned up, so it is easy to confirm
that the setting really took effect.

## Tests

```powershell
python -m unittest discover -s tests
```

219 tests in total, split by capability:

| Module | Coverage |
| --- | --- |
| `test_probe.py` | argument assembly and mode combinations of the fastjson probe engine |
| `test_jar_report.py` | the five fact queries over the call-graph database, plus tolerance for missing tables |
| `test_signatures.py` | signature-library format, **sink features matching `jar_report.SINKS` entry by entry**, every vulnerability class being reachable, filtering by severity / type / evidence source / tag with the failure paths for unknown values, export task metadata, same-input baseline comparison (including the fallback for a corrupt or foreign-schema baseline), and the failure paths for broken JSON / bad regex / unregistered types |
| `test_backend_e2e.py` | **real backend end to end** (P0): really build the database, check the schema, run a query and run signature matching; when the backend is absent it **skips and says "unverified" instead of passing** |
| `test_logging.py` | file names, retention policy and level filtering of the logging kernel |
| `test_decoupling.py` | package dependency boundaries and the UI file-size caps (`src/ui/*.java` ≤ 600 lines, `src/Main.java` ≤ 400 lines) |
| `test_selfcheck_hygiene.py` | every self-check entry installing the side-effect guard |
| `test_java8_source_level.py` | no Java 9+ APIs or syntax under `src/` |

The Java self-checks run against the compiled classes and print their own assertions:

```powershell
# build first (produces target\classes and lib\java-chains-cli-2.0.0-beta4.jar), then compile the checks
.\build.ps1
E:\java\jdk17\bin\javac.exe -encoding UTF-8 -cp "target\classes;lib\java-chains-cli-2.0.0-beta4.jar" -d target\tmp2 tests\*.java

# all eight checks (--add-opens lets bytecode gadgets reach the JDK-internal xalan classes; run.ps1 already adds them)
$opens = @('--add-opens', 'java.xml/com.sun.org.apache.xalan.internal.xsltc.trax=ALL-UNNAMED',
           '--add-opens', 'java.xml/com.sun.org.apache.xalan.internal.xsltc.runtime=ALL-UNNAMED')
E:\java\jdk17\bin\java.exe @opens -cp "target\tmp2;target\classes;lib\java-chains-cli-2.0.0-beta4.jar" UiNavigationCheck
E:\java\jdk17\bin\java.exe @opens -cp "target\tmp2;target\classes;lib\java-chains-cli-2.0.0-beta4.jar" UiSwitchEndToEndCheck
E:\java\jdk17\bin\java.exe @opens -cp "target\tmp2;target\classes;lib\java-chains-cli-2.0.0-beta4.jar" ProxyServerCheck
E:\java\jdk17\bin\java.exe @opens -cp "target\tmp2;target\classes;lib\java-chains-cli-2.0.0-beta4.jar" AnalyzeCheck
E:\java\jdk17\bin\java.exe @opens -cp "target\tmp2;target\classes;lib\java-chains-cli-2.0.0-beta4.jar" LogCheck
```

| Self-check | Coverage | Needs `--add-opens` |
| --- | --- | --- |
| `UiNavigationCheck` | Sidebar expand / collapse, per-page widgets and end-to-end flows (including the toString page, the out-of-band Jar page, both analysis pages with their filter controls and hand-off bar, and the new settings groups), plus one real jar scanned end to end by the local analysis; asserts that the two analysis pages have different headings and widget sets (the regression guard for the page split); screenshots go to `target/ui-check/` | Yes (preset end-to-end build) |
| `UiSwitchEndToEndCheck` | Captured headers fed to the probe page through the real Python engine; the out-of-band Jar page really hosts a Jar, fetches it back over HTTP as a valid Zip, and the port can be bound again after stopping | Yes |
| `UiShiroCheck` | Full Shiro page flow | Yes |
| `ShiroCheck` | Shiro engine (detect / crack / chain build / echo) | Yes |
| `PayloadCheck` | Payload engine (catalog, groups, navigation, node display names, dual form, failure paths, safety) plus the 25 out-of-band Jar combinations and toString trigger ownership (100 assertions in total) | Yes |
| `ProxyServerCheck` | The proxy itself: plain-HTTP capture, 404 handling, `CONNECT` tunneling with byte pass-through, callbacks, `find` / `clear` | No |
| `LogCheck` | The logging kernel (79 assertions): file-name parsing (including unpadded months and impossible dates such as 30 February), retention boundaries (month / year / leap-year crossings, 0 treated as 1), directory resolution priority, level filtering, per-day files with day rollover, stack expansion, cleanup restricted to files this tool named, silent failure when writing is impossible, and uncaught exceptions reaching the log | No |
| `AnalyzeCheck` | The analysis kernel (134 assertions): version-comparison semantics (including `1.2.80 > 1.2.9` and pre-releases sorting below releases), the four coordinate sources and their priority, rule hits and misses, no false positives when the group differs, end-to-end analysis with jump suggestions, the failure paths of the external engine and the database, and gadget judgement (coordinates plus version ranges, patched versions not reported, new coordinates not missed, external rule-file syntax and its failure paths), finding status and missing evidence, rule sources and versions, the analysis context and its evidence cap, filter argument assembly and the type-combo mapping | No |

Dependency-boundary, source-level and toolchain checks:

```powershell
python -X utf8 tools\audit_boundary.py        # package dependency boundaries (acyclic / layering / generic components / shared kernel)
python -X utf8 -m unittest tests.test_java8_source_level  # no Java 9+ APIs or syntax under src/
powershell -File tools\check_agent_tools.ps1  # 91 mechanical assertions for the multi-agent toolchain
```

The source-level check is not redundant: `src/pom.xml` pins the source level to release 8, while the
hand-written `javac` commands above **do not pass `--release 8`**. A Java 9+ API slipped into `src/`
therefore passes every self-check and only fails inside `build.ps1` with "cannot find symbol".
This assertion turns that constraint into a check that runs in seconds.

Self-checks execute the command embedded in gadget parameters while building chains (the upstream `Clojure` / `Exec` nodes default to `calc`), so all eight entry points call `tests/TestProcessGuard.java` on startup: it snapshots the existing `CalculatorApp` processes and installs a JVM shutdown hook that force-kills only the ones started after the snapshot, logging a `[calc-guard]` line. A leftover calculator after a run means that entry point is missing the guard.

Only run this against systems where testing is explicitly authorized.
