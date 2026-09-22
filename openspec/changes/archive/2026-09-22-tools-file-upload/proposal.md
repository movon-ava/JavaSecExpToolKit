# Proposal: 小工具 - 文件上传

## Why

工具目前只能发 JSON / 表单请求（Fastjson 探测、抓包转换、Shiro 利用），**没有发文件的能力**。
而实战里遇到上传点时的第一步就是把一个文件发上去看响应：

- 需要确认目标上传接口是否存在、是否需要登录态、字段名是什么；
- 需要看到第一手的响应状态码与响应体（而不是靠浏览器读到的二次转述）；
- 需要一个不依赖浏览器 / Burp 的独立入口，方便在拿到接口后立刻验证。

现有功能都做不到这件事：抓包页的请求体是文本，塞不进二进制文件，也拼不出 multipart 分段。

## What Changes

- 新增一级分类「小工具」，二级项「文件上传」：填目标 URL、选本地文件、可选填表单字段名 /
  附加普通字段（JSON）/ 请求头，发送一次 multipart 请求并展示原始响应。
- 引擎新增 `upload` 模式：按原始字节构造 `multipart/form-data` 请求体，一次请求、不跟随跳转，
  返回状态码、耗时、响应头、响应体与 Set-Cookie。
- 新增可持久化配置进配置页「小工具配置」分组：默认上传 URL、默认表单字段名、上传超时。

## Capabilities

### New Capabilities

- `tools/file-upload`：单文件 multipart 上传、响应记录与失败路径的可读结论。

## Non-goals

- **不做批量 / 目录上传**：一次只上传一个文件，界面不做队列。
- **不做上传后的利用**：不解析响应语义、不猜测 webshell 路径、不做写文件或命令执行。
- **不改动既有探测逻辑**：`detect` / `version` / `expect` / `dns` / `ceye` 五个模式与
  `capture` / `convert` 两个模式的行为一律不动。
- **不改动抓包页与代理页**：上传不进抓包转换的「一键发送」链路。
- **不引入新第三方依赖**：仍只用 Java 标准库与 Python 标准库。

## Impact

| 路径 | 写入域 | 动作 |
| --- | --- | --- |
| `python/fj_probe.py` | probe | 改：新增 `upload` 模式、multipart 构造与报告渲染 |
| `src/probe/ProbeCommand.java` | probe | 改：新增 `upload` 启动参数拼装与四个选项字段 |
| `src/ui/ToolsUploadPage.java`、`src/ui/ToolsUploadController.java` | ui | 新增：文件上传页视图与行为 |
| `src/ui/NavController.java` | ui | 改：新增「小工具」一级分类 |
| `src/ui/ConfigForm.java`、`src/ui/ConfigController.java` | ui | 改：新增「小工具配置」分组与读写 |
| `src/ui/WidgetRegistry.java`、`src/Main.java` | ui | 改：登记控件、装配页面、新增自检转发 |
| `tests/UiNavigationCheck.java` | 测试 | 改：导航项数断言、上传页与配置页断言 |
| `tests/UiSwitchEndToEndCheck.java` | 测试 | 改：上传桩端点与端到端断言 |
| `tests/test_probe.py` | 测试 | 改：upload 模式单测 |
| `README.md`、`README.en.md`、`AI_REPORT.md`、`PROGRESS.md` | 主 agent | 改：文档同步 |
