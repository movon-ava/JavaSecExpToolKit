# Proposal: 恶意服务器与预设链（JavaChains 工作台）

## Why

上一轮 change `payload-generation` 只交付了「引擎层 + 生成页」，界面仍缺两块网页版
java-chains 的核心能力，导致工具在实战里断了链路：

- **没有发布端**：载荷构建出来后无处投送。使用者必须自己另外起一个 LDAP / HTTP / MySQL
  伪装服务，把生成的载荷挂上去，再让目标回连——这一步在网页版里由「恶意服务器」面板直接完成。
- **没有模板入口**：java-chains 内置的 52 条预设链没有任何界面入口，使用者只能从空链自己搭；
  而多数实战场景用的就是这些现成模板。

同时，上一轮代码里存在一个**真实缺陷**：Shiro 回显链生成后无法被本机密钥解密。
本次一并修复（根因见 design.md）。

## What Changes

- 新增恶意服务器页：真实启停 JNDI / HTTP / TCP / FakeMySQL / JRMP 五类服务，
  支持发布载荷并给出可直接使用的回连地址。
- 新增预设链页：读取内置 52 条预设链，渲染链步骤与可填输入，本地生成本地载荷。
- 修复载荷双重 Base64 编码缺陷：文本形态载体（Shiro）的载荷被当成字节再编码一次。
- 侧边栏重组：一级分类对齐网页版（主页 / Payload / 服务 / 代理 / FastJson / 配置），
  主窗口默认最大化。
- 新增可持久化配置进配置页：恶意服务器的绑定地址、公布地址、七类端口，以及预设链默认分类。
- 依赖边界契约扩展：声明分层接纳 `service` 与 `preset` 两个新包。
- 新增界面自检断言：预设链端到端生成、服务页控件与端口初值来源、配置页新分组。

## Capabilities

### New Capabilities

- `services/malicious-server`：恶意服务器的启停、端口绑定、载荷发布与状态呈现。
- `presets/preset-chain`：内置预设链的读取、分类筛选、链步骤渲染与本地生成。

### Modified Capabilities

- `codebase/dependency-boundary`：声明分层新增 `service`（只依赖 `payload`）与
  `preset`（不依赖任何项目包）两个包，并把二者纳入叶子层与通用组件断言范围。

## Non-goals

- **不做 HTTPS 中间人解密**：代理仍是明文 HTTP + CONNECT 透传，本次不改变。
- **不改 Fastjson 探测引擎**：`python/fj_probe.py` 本次只读。
- **不改 Shiro 利用逻辑**：仅修复载荷编码缺陷，检测 / 爆破 / 回显策略不变。
- **不引入 Spring 或任何 Web 容器**：上游服务端适配器在纯 JDK 17 下可直接驱动，
  已实测无需 Spring（详见 design.md 的实测结论）。
- **不引入新第三方依赖**：仍只用 java-chains 2.0.0-beta4，`src/pom.xml` 本次不动。
- **不暴露分支型链**：预设里出现分叉的链仍按线性顺序构建，这个限制如实保留。
- **不新增投递能力**：恶意服务器只负责「监听 + 发布」，不主动向目标发送载荷。

## Impact

| 路径 | 写入域 | 动作 |
| --- | --- | --- |
| `src/service/*` | exploit | 新增：服务描述、状态、发布结果与服务端适配收敛层 |
| `src/preset/*` | exploit | 新增：预设链纯数据与读取层 |
| `src/ui/ServicePage.java`、`src/ui/ServiceController.java` | ui | 新增：恶意服务器页视图与行为 |
| `src/ui/PresetPage.java`、`src/ui/PresetController.java` | ui | 新增：预设链页视图与行为 |
| `src/ui/ChainEditor.java` | ui | 新增：两个页面共用的链编辑状态 |
| `src/payload/PayloadEngine.java` | exploit | 改：新增原始对象构建入口，修复双重编码 |
| `src/Main.java` | ui | 改：导航重组、默认最大化、新页面装配、配置页新分组 |
| `tests/UiNavigationCheck.java` | 测试 | 改：导航断言改为按展开状态现算，新增新页与配置断言 |
| `tests/test_decoupling.py`、`tools/audit_boundary.py` | 测试 / 主 agent | 改：边界规则同步新包 |
| `docs/AGENT-ROLES.md` 等 | 主 agent | 改：角色写入域同步新包 |
