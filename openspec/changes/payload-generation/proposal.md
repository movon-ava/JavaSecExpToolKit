# Proposal: Payload 生成（引擎层）

## Why

java-chains 2.0.0-beta4 的全部能力（实测 429 个节点、28 种载荷载体）当前只以 6 条 Shiro 预置链的形式对外暴露，
通用能力被封在具体功能模块里：载荷载体的枚举、节点导航、参数查询与载荷构建这套与漏洞类型无关的机制，
只服务于 Shiro 场景，其它功能无法复用。而「直接复用」会立刻产生反向依赖（通用机制位于具体功能模块内），
这在上一轮 change `decouple-chain-engine` 建立的依赖边界里会被自检判为越界。
本次把该通用机制下沉到独立的 payload 包，使「选载体 → 追加节点 → 配参数 → 产出载荷」这条路径成为
可被任意功能调用的独立能力，同时不产生任何反向依赖。

## What Changes

- 新增 `payload` 包，承载通用链构建能力：载体与节点目录、后继节点导航、节点参数查询、
  载荷构建、结果模型、以及按用途维护的载体分组常量表。
- `shiro` 侧的链条封装改为委派新包：公开 API 与嵌套结果类型保持不变，既有调用方与自检零改动（**非 BREAKING**）。
  shiro 侧只保留 Shiro 专属的预置链模板与默认参数，不再自持通用实现。
- 依赖边界契约扩展：声明分层新增 `payload` 包的允许方向（`payload` 只依赖共享内核；
  `ui` 与 `shiro` 可以依赖 `payload`），并把「通用组件不得依赖具体功能模块」的断言范围扩展到新包。
- 新增引擎自检：把载体数量、载体分组的完整性、节点导航稳定性、非法链/非法载体/空链的可读失败结论、
  载荷双形态往返一致、默认回连地址为空、载荷正文不入日志与文件，全部做成可执行断言。
- 既有五套 Java 自检与 Python 单测必须保持全绿，`ShiroCheck` 的标准输出在改动前后逐字符一致。

## Capabilities

### New Capabilities

- `payload/chain-generation`：通用载荷生成能力。规定载体目录与节点导航必须来自运行时引擎、
  载体分组必须完整覆盖目录、链式追加只接受引擎认可的后继节点、参数按节点动态提供、
  载荷同时产出 Base64 文本与原始字节且两者可互相还原、非法输入必须给出可读失败结论、
  生成过程完全本地且默认不内置回连地址、载荷正文不进入日志与文件。

### Modified Capabilities

- `codebase/dependency-boundary`：声明分层需要接纳新增的通用包
  （新增包只依赖共享内核，具体功能模块可以依赖它），
  且「通用组件不得依赖具体功能模块」的判定范围需要从既有通用链引擎扩展到新包内的全部源文件。
  既有四条约束（无环、叶子层无出边、共享内核单向被依赖、边界检查可机械执行）不变。

## Non-goals

- **不做界面**：不新增导航项、不新增页面、不新增按钮，本 change 不触碰任何界面文件与其行为。
- **不做界面行为相关的实测复现**：本次没有任何用户可见的界面变化，故不提供界面复现步骤；
  界面部分由后续 change 承担（见下「后续 change」）。
- **不新增可持久化配置项**：不改 `src/config/AppConfig.java` 的键，也不改配置页分组。
  默认载体、默认链、默认输出格式、默认导出目录这四项配置随界面 change 一起落地。
- **不改共享内核**：`src/util/**` 与 `src/config/**` 本 change 只读。Base64 编解码内核只被使用，不被修改。
- **不引入任何新依赖**：继续只用现有 java-chains 2.0.0-beta4，不新增第三方库，不改 `src/pom.xml`。
- **不移动既有调用点**：`src/Main.java`、Shiro 模块内的调用方式本次不变
  （`src/Main.java` 属于 UI 写入域，跨域搬迁推迟到界面 change）。
- **不新增投递能力、不内置靶场、不批量枚举参数组合**：本功能只生成载荷，不负责发送；
  不生成参数空间的全量载荷。
- **不放宽既有断言**：既有自检只运行、不修改；`tests/ShiroCheck.java` 等断言数量只增不减。

## Impact

| 路径 | 写入域 | 动作 |
| --- | --- | --- |
| `src/payload/PayloadEngine.java` | exploit | 新增：载体与节点目录、节点导航、参数查询、载荷构建 |
| `src/payload/PayloadResult.java` | exploit | 新增：结果模型（Base64 文本 / 原始字节 / 长度 / 摘要）与失败结论 |
| `src/payload/PayloadCatalog.java` | exploit | 新增：按用途维护的载体分组常量表 |
| `src/shiro/ChainsEngine.java` | exploit | 改：通用实现改为委派新包，公开 API 与结果类型不变 |
| `tests/PayloadCheck.java` | 测试 | 新增：引擎自检（本 change 的验收入口） |
| `tests/test_decoupling.py` | 测试 | 改：允许边集合与通用组件范围同步到新包 |
| `openspec/changes/payload-generation/**` | 主 agent | 规划件（本文件所在目录） |
| `openspec/specs/payload/chain-generation/spec.md`、`openspec/specs/codebase/dependency-boundary/spec.md` | 主 agent | 归档时由 delta 合并 |

对既有功能的影响：无用户可见行为变化。Shiro 模块的载荷生成、抓包、探测三条路径的入口与输出均不变，
判据是五套既有 Java 自检的标准输出与改动前逐字符一致。

### 后续 change（本次明确不做，写入域预先登记，避免将来跨域返工）

按 `openspec/config.yaml` 的「先内核后功能」规则，payload 生成能力整体拆成三个 change，
本 change 是功能侧的第一个；其余两个的写入域与顺序如下，均不在本 change 内提交：

| 顺序 | change 名 | 角色 | 写入域 | 内容 |
| --- | --- | --- | --- | --- |
| 1 | `payload-config-keys` | 主 agent | `src/config/AppConfig.java` | 四个可持久化键：默认载体、默认链、默认输出格式、默认导出目录 |
| 2 | `payload-page` | UI | `src/ui/PayloadPage.java`、`src/ui/ConfigPage.java`、`src/Main.java` | 页面、导航项、配置分组 |
| 3 | `payload-handoff` | UI | `src/ui/PayloadPage.java`、`src/ui/CapturePage.java`、`src/ui/ShiroPage.java` | 「填入抓包页 / 填入 Shiro 页」联动 |

内核 change 必须先于界面 change 合并，否则界面会引用尚未存在的配置键。

### 写入域与拆分说明

本 change 触碰两个写入域：功能开发 exploit（`src/payload/**`、`src/shiro/**`）与测试（`tests/**`）。
自检随实现落在同一个 change 内（先例：change `decouple-chain-engine` 同时包含源码改动与依赖边界自检），
否则本 change 的新引擎没有任何可执行验收，回归无法在一次请求内关闭。
界面写入域与共享内核写入域一律不碰，相关能力已在上表登记为后续 change。