# Design: 通用链引擎解耦与依赖边界守护

## Context

`src/shiro/` 下有 3 个文件，实测 1176 行：

| 文件 | 行数 | 性质 |
| --- | ---: | --- |
| `ShiroEngine.java` | 719 | Shiro 专用：加解密、指纹、爆破 |
| `ShiroExploit.java` | 192 | Shiro 专用：利用编排 |
| `ChainsEngine.java` | 265 | **通用**：java-chains 封装 |

`ChainsEngine` 是通用组件：它封装的是「选载荷载体 → 追加 gadget 节点 → 构建」这套与漏洞类型
无关的机制，实测可驱动 429 个节点、28 种载体（覆盖 hessian、jndi、xstream、blazeDS 等）。
但它现在位于 `shiro` 包内，并直接调用 `ShiroEngine.base64(...)`。

本次改动的原因是：后续 payload 生成功能需要复用该引擎。若不先解耦，
要么接受 `payload → shiro` 的反向依赖，要么在功能开发中途做跨模块重构。

## Goals / Non-Goals

**Goals**

- 去掉 `ChainsEngine` 对 `ShiroEngine` 的依赖，使其可在任意功能包中复用。
- 把依赖边界变成可机械校验的断言，防止后续改动重新引入耦合。
- 保持 `ShiroEngine` 的公开签名不变，做到对既有调用方与自检零影响。

**Non-Goals**

- 不移动 `ChainsEngine` 的文件位置。搬迁需要同时改 4 处调用方与 import，
  与解耦混在一起会让回归难以定位；解耦完成后再搬迁是零语义改动。
- 不为 `ShiroEngine`（719 行）本身做拆分。它有独立自检覆盖，
  且行数在可维护范围内，拆分收益与风险不成比例。
- 不引入依赖注入框架或任何新第三方库。

## Decisions

### 决策一：下沉 Base64 到 `src/util/Codec.java`

`base64(byte[])` 与 `decodeBase64(String)` 是与 Shiro 无关的通用编解码能力，
只是恰好先被 Shiro 模块用上。放进共享内核后，
`ChainsEngine` 与 `ShiroEngine` 都能以单向依赖方式使用它。

**备选方案与取舍**

| 方案 | 问题 |
| --- | --- |
| 把 Base64 内联进 `ChainsEngine` | 产生两份实现，日后修改需同步两处 |
| 让 `ChainsEngine` 接受外部传入的编码器 | 需要改动 `build` 的签名，波及 4 处调用方，且调用方并不关心编码器 |
| 下沉到 `src/util/Codec.java`（选中） | 无签名变更，符合既有「共享内核」约定 |

### 决策二：`ShiroEngine` 保留原方法签名，内部委托

`ShiroEngine.base64` / `decodeBase64` 被 `ShiroExploit`（第 176 行）与
`tests/ShiroCheck.java`、`tests/UiShiroCheck.java` 直接调用。
保留签名做委托，可让本次改动对调用方完全透明（非 BREAKING）。

### 决策三：依赖边界用 Python 单测守护，而非新增构建插件

项目已有 `python -m unittest discover -s tests` 作为统一回归入口（当前 84 项），
且 `AGENTS.md` 禁止新增第三方依赖。用标准库做源码静态扫描即可满足需求，
不引入 pylint、import-linter 之类的工具。

检查项与判定规则：

| 检查 | 判定方法 |
| --- | --- |
| 无环 | 构建包依赖有向图，检测双向边 |
| 符合分层 | 与声明的允许边集合比对 |
| 通用组件不依赖具体功能 | `ChainsEngine` 源码不出现 `ShiroEngine` 标识符 |
| 共享内核单向 | `util` / `config` 的源文件不出现上层包名 |

### 决策四：判定「必须解耦」与「可不改」的标准

本仓库不追求为解耦而解耦。判定标准是**该组件是否需要在另一个功能中复用**：

- **必须解耦**：通用机制被具体功能模块持有，且已预见到复用需求。
  `ChainsEngine` 属于此类。
- **可不改**：`ui` 包对 `shiro`、`proxy` 的依赖（`ShiroPage` 引用 `ShiroExploit.ChainKind`、
  `FlowRenderer` 引用 `ProxyServer.HttpFlow`）。界面层引用其展示对象的数据类型是正常的
  单向依赖，不构成环，也不阻碍复用，保持现状。
- **不做**：`Main` 对全部模块的装配依赖。装配是 `Main` 的职责，不属于耦合问题。

这条标准同时写入监督角色的审计清单，避免其机械地要求「一切依赖都不得跨包」。

## Risks / Trade-offs

- **[新增文件带来一点复杂度]** → `Codec` 只有两个方法，且是纯函数，测试成本极低。
- **[静态扫描可能误判]** 例如注释里提到 `ShiroEngine` 也会触发命中 →
  扫描时先剥离注释与字符串字面量；同时该误判方向是保守的（宁可多报）。
- **[`ChainsEngine` 仍留在 `shiro` 包内]** → 这是刻意的范围控制。
  搬迁后 `ui` 与 `Main` 的 import 需要同步改动，属于独立 change。

## Migration Plan

1. 新增 `src/util/Codec.java`。
2. `ShiroEngine` 内部改为委托 `Codec`，公开签名不变。
3. `ChainsEngine` 改为引用 `Codec`，移除对 `ShiroEngine` 的引用。
4. 新增 `tests/test_decoupling.py`，把边界规则固化为断言。
5. 回归：五套 Java 自检 + Python 全套 + 构建校验。

回退策略：`.backups/` 保留最近三次快照，本 change 的改动集中在 2 个源文件，
可直接从最近一次快照恢复。

## Open Questions

（无。所有决策均可在本次改动内定稿，不涉及需要推迟的未知项。）