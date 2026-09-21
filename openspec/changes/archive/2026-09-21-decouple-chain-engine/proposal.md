# Proposal: 通用链引擎解耦与依赖边界守护

## Why

`src/shiro/ChainsEngine.java` 是 java-chains 的**通用**封装层（实测 429 个节点、28 种 payload 载体），
却依赖 `src/shiro/ShiroEngine.java` 的 Base64 工具方法（第 195 行）。
这让一个通用组件被绑定在 Shiro 这一具体功能上：payload 生成等后续功能若要复用该引擎，
必须先接受 `payload → shiro` 的反向依赖，或先做一次跨模块重构。
同时项目缺少可机械执行的依赖边界检查，判断「是否被正确解耦」只能靠人工阅读，
无法在改动中自动拦截退化。

## What Changes

- 新增共享内核 `src/util/Codec.java`，承载 Base64 编解码这对通用能力。
- `ShiroEngine` 与 `ChainsEngine` 改为委托该内核，`ShiroEngine.base64` / `decodeBase64`
  的公开签名保持不变，既有调用方与自检不受影响（**非 BREAKING**）。
- 明确并固化包级分层规则：谁允许依赖谁、谁必须是叶子层。
- 新增可机械执行的依赖边界自检，把上述规则变成会失败的断言，
  供监督角色在归档前复算，而不是依赖人工判断。

## Capabilities

### New Capabilities

- `codebase/dependency-boundary`: 依赖边界契约。规定包级依赖必须无环、必须符合声明分层、
  通用组件不得依赖具体功能模块，且这些约束必须可机械校验。

### Modified Capabilities

（无。本次不改变任何用户可见行为，仅调整内部依赖方向。）

## Non-goals

- 不改变任何界面、文案、配置键名与网络行为。
- 不移动 `ChainsEngine` 的文件位置；解耦完成后搬迁才是零改动的后续工作。
- 不重写 `ProxyServer`、`ShiroEngine` 内部实现，不拆分 `Main.java`。
- 不引入任何第三方依赖。

## Impact

| 路径 | 角色写入域 | 性质 |
| --- | --- | --- |
| `src/util/Codec.java` | 共享内核（主 agent 批准） | 新增 |
| `src/shiro/ShiroEngine.java` | exploit agent | 改为委托，签名不变 |
| `src/shiro/ChainsEngine.java` | exploit agent | 去掉对 ShiroEngine 的引用 |
| `tests/test_decoupling.py` | test agent | 新增依赖边界自检 |
| `docs/AGENT-ROLES.md` | 主 agent | 新增多 Agent 职责说明 |
| `docs/DESIGN-agents.md` | 主 agent | 补充监督角色的解耦审计职责 |

对既有功能的影响：无。`ShiroCheck`、`UiShiroCheck` 等自检调用的是保持不变的公开签名。