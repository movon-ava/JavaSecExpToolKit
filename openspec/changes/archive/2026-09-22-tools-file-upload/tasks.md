# Tasks

## 1. 引擎：upload 模式

写入域：`python/fj_probe.py`（角色：probe）

- [x] 1.1 把请求发送路径拆成文本 / 字节两条入口，共用同一套请求头净化与不跟随跳转；验证：既有 106 项测试全绿
- [x] 1.2 新增 upload 模式：按原始字节构造 multipart 请求体，boundary 与 Content-Type 一致；验证：multipart 请求体含文件字节与普通字段
- [x] 1.3 记录状态码 / 耗时 / 响应头 / 响应体 / Set-Cookie；验证：响应记录断言通过
- [x] 1.4 上传与抓包一致按详细报告渲染；验证：精简模式下仍给出请求与响应全文
- [x] 1.5 失败路径逐条给出可读结论（空 URL / 未选文件 / 文件不可读 / 超限 / 附加字段非法 / 连接失败 / 3xx / 401 / 5xx / 404）；验证：单测覆盖
- [x] 1.6 新增 CLI 参数 `--upload-url` / `--upload-file` / `--upload-field` / `--upload-fields` 并透传到 extras；验证：CLI 解析断言通过

## 2. Java：命令行与页面

写入域：`src/probe/ProbeCommand.java`、`src/ui/**`、`src/Main.java`（角色：probe / ui）

- [x] 2.1 `ProbeCommand.Options` 新增上传选项并提供 `upload(...)` 拼装；验证：编译通过
- [x] 2.2 新增文件上传页视图与行为（选文件 / 字段名 / 附加字段 / 请求头 / 上传 / 复制结果）；验证：`UiNavigationCheck` 控件断言通过
- [x] 2.3 导航新增「小工具」一级分类与「文件上传」二级项；验证：导航断言通过
- [x] 2.4 新增「小工具配置」分组（默认上传 URL / 表单字段名 / 上传超时）并完成读写与下发；验证：配置页断言通过
- [x] 2.5 登记控件并提供自检转发入口；验证：`UiNavigationCheck` 通过

## 3. 自检

写入域：`tests/**`（角色：测试）

- [x] 3.1 导航项数断言随一级分类增长同步更新；验证：`UiNavigationCheck` 通过
- [x] 3.2 新增上传页断言（控件、只读结果区、默认字段名、失败路径提示）；验证：`UiNavigationCheck` 通过
- [x] 3.3 端到端：真实发送 multipart 请求，桩服务校验分段、字段与文件字节；验证：`UiSwitchEndToEndCheck` 通过
- [x] 3.4 引擎单测覆盖 multipart 构造、响应记录、报告渲染与全部失败路径；验证：`python -m unittest discover -s tests` 全绿

## 4. 收尾

写入域：`README*.md`、`AI_REPORT.md`、`PROGRESS.md`、`openspec/**`（角色：主 agent）

- [x] 4.1 中英 README 同步（导航 / 配置页 / 项目结构 / 测试）；验证：两处小节一一对应
- [x] 4.2 追加 AI 报告与进度；验证：两份文件含本轮改动
- [x] 4.3 构建并复验 JAR 时间晚于全部源文件；验证：`build.ps1` 输出
