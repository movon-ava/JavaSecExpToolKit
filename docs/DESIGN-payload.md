# JavaSecExpToolKit Payload 生成功能设计

版本：0.1.0
更新日期：2026-09-21
适用读者：本仓库维护者
关联文档：`docs/DESIGN-agents.md`（多 agent 框架）、`docs/DESIGN-shiro.md`（Shiro 模块）、
`docs/DESIGN-modularization.md`（Java 模块化）

---

## 一、需求与定位

### 1.1 需求

新增独立的「Payload 生成」功能：选择一种 payload 载体与若干 gadget 节点，
生成对应的利用链载荷，用于授权测试中的漏洞验证。

### 1.2 与既有 Shiro 模块的关系

Shiro 模块**已经具备** payload 生成能力，但它是为 Shiro 场景定制的：
`src/shiro/ChainsEngine.java` 的 `templates()` 只预置 6 条链
（Shiro + CB1 回显、Shiro + CCK1 回显、Shiro + CCK1 命令执行、Shiro 探测链、
Fastjson 回显、Jackson 回显），参数默认值也围绕 Shiro 密钥组织。

新功能不是重复造轮子，而是**把已有的通用能力暴露出来**。
实测 java-chains 2.0.0-beta4 在本项目环境下注册了 **429 个节点**、
其中 **28 种 payload 载体**：

```
blazedsamf3ampayload      blazedsamf3remotingpayload  bytecodepayload
expressionpayload         fakemysqlbrutechainpayload  fakemysqlpayload
fakemysqlreadpayload      fastjsonpayload             hessian2payload
hessian2tostringpayload   hessianpayload              javanativepayload
jdbcpayload               jndibasicpayload            jndibrutechainpayload
jndildapdeserializepayload jndirefbypasspayload       jndireferencepayload
jndiresourcerefpayload    jndirmideserializepayload   jrmplistenerpayload
jsfpayload                objectpayload               otherpayload
shiropayload              usercustompayload           xmldecoderpayload
xstreampayload
```

**关键结论：不需要新增任何第三方依赖，零成本复用。**

---

## 二、前置重构：解除反向依赖

### 2.1 问题

`src/shiro/ChainsEngine.java:195` 调用了 `ShiroEngine.base64(...)`：

```java
Object data = result.getData();
if (data instanceof byte[]) {
    return Generated.ok(ShiroEngine.base64((byte[]) data));   // 反向依赖
}
```

若把 `ChainsEngine` 作为通用组件搬进新的 `src/payload/` 包，
它会反向依赖 `shiro` 包，形成 `payload → shiro` 的耦合。
这会导致：payload 功能的改动牵连 Shiro 模块，违反写入域隔离。

### 2.2 方案：把 `base64` 下沉到 `src/util/`

1. 在 `src/util/` 新增编解码工具（例如 `Codec.java`），提供 `base64(byte[])` 与
   `decodeBase64(String)`。
2. `ShiroEngine` 的内部实现改为委托到该类，**保持 `ShiroEngine.base64` 方法签名不变**，
   避免既有调用点（`ShiroExploit`、`tests/ShiroCheck.java`、`tests/UiShiroCheck.java`）与测试受影响。
3. 新增 `src/payload/ChainsEngine.java` 承接通用链构建能力，
   或把现有 `ChainsEngine` 整体迁入 `src/payload/` 并更新 `ChainsEngine` 的引用方。

### 2.3 影响面（实测）

- `ShiroEngine.base64` 的调用点：`ChainsEngine.java:195` 与 `ShiroEngine` 内部。
- `ChainsEngine` 的调用点：`src/shiro/ShiroExploit.java`、`src/Main.java`、`tests/ShiroCheck.java`。
- 该重构属于**共享内核改动**，按 `docs/DESIGN-agents.md` 第四节的规则，
  必须由主 agent 单独开一个 change，并在 Impact 段列出上述调用方。

**这是 payload 功能的第一个 change，不是它的附属任务。**

---

## 三、功能设计

### 3.1 能力范围

| 能力 | 说明 |
| --- | --- |
| 载体选择 | 28 种 payload 载体，按用途分组 |
| 链式构建 | 逐级选择可追加的 gadget 节点（`nextNodes` 动态驱动） |
| 参数配置 | 按选中节点动态渲染其可配置参数（`paramsOf`） |
| 载荷生成 | 产出 Base64 或原始字节的载荷 |
| 载荷导出 | 复制到剪贴板、导出文件、直接填入抓包/Shiro 页 |

### 3.2 明确的非目标

- **不新增投递能力**：本功能只生成载荷，不负责发送。
  投递复用既有抓包页与 Shiro 页，避免出现第二套投递逻辑。
- **不内置漏洞靶场**：不附带测试目标。
- **不自动枚举参数组合**：不生成参数空间的全量载荷，
  这对授权测试没有必要，且容易被滥用。

### 3.3 载体分组（按实际用途，非按字母序）

| 分组 | 载体 |
| --- | --- |
| 序列化（通用） | `javanativepayload`、`hessianpayload`、`hessian2payload`、`objectpayload` |
| JSON 解析器 | `fastjsonpayload`、`xstreampayload` |
| JNDI | `jndibasicpayload`、`jndireferencepayload`、`jndildapdeserializepayload`、`jndirmideserializepayload`、`jndibrutechainpayload`、`jndirefbypasspayload`、`jndiresourcerefpayload` |
| 框架专用 | `shiropayload`、`xmldecoderpayload`、`jsfpayload`、`expressionpayload`、`jdbcpayload` |
| 特殊协议 | `blazedsamf3ampayload`、`blazedsamf3remotingpayload`、`jsfpayload` |
| MySQL 伪装 | `fakemysqlpayload`、`fakemysqlreadpayload`、`fakemysqlbrutechainpayload` |
| 其他 | `bytecodepayload`、`jrmplistenerpayload`、`usercustompayload`、`otherpayload`、`hessian2tostringpayload` |

分组清单以 `src/payload/` 内的常量表维护，实施时按 `paramsOf` 的实际返回校正。

---

## 四、涉及文件与写入域

按 `docs/DESIGN-agents.md` 的矩阵，本功能拆成两个 change：

### change A：前置重构（归属：主 agent 主导 + exploit agent 执行）

| 文件 | 动作 |
| --- | --- |
| `src/util/Codec.java` | 新增（共享内核，需主 agent 批准） |
| `src/shiro/ShiroEngine.java` | 改：`base64` 委托到 `Codec`，签名不变 |
| `src/shiro/ChainsEngine.java` | 改：不再直接引用 `ShiroEngine` |

### change B：payload 功能（归属：exploit agent 写引擎 + UI agent 写界面）

| 文件 | 归属 | 动作 |
| --- | --- | --- |
| `src/payload/PayloadEngine.java` | exploit | 新增：载体分组、节点导航、构建 |
| `src/payload/PayloadResult.java` | exploit | 新增：结果模型与导出格式 |
| `src/payload/ChainsEngine.java` | exploit | 由 `src/shiro/` 迁入（或保留原位 + 新增门面） |
| `src/ui/PayloadPage.java` | UI | 新增：视图构建器，遵循 `ShiroPage` 的三段式惯例 |
| `src/Main.java` | UI | 改：新增导航项与页面装配 |
| `src/config/AppConfig.java` | 主 agent | 改：新增可持久化配置键 |
| `src/ui/ConfigPage.java` | UI | 改：新增配置分组 |

### 配置落页（硬性要求）

按 `AGENTS.md`「新功能的添加要将一些可以永久保存的配置放进配置页中」，
以下参数需进入配置页的新分组「Payload 生成配置」：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| 默认 payload 载体 | `javanativepayload` | 打开页面时预选 |
| 默认 gadget 链 | 空 | 上次使用的节点序列，逗号分隔 |
| 默认输出格式 | `base64` | 可选 `base64` / `raw` |
| 默认导出目录 | 空 | 导出文件时的起始目录 |

**不放进配置页的**：单次任务的参数（如具体命令、目标 URL），
它们属于会话状态而非长期配置。

---

## 五、界面设计

沿用 `src/ui/ShiroPage.java` 已验证的三段式结构，保证交互一致：

```
┌─ Payload 生成 ──────────────────────────────┐
│ 载体   [javanativepayload      ▾]           │  表单区（约 260px，可滚动）
│ 节点   [commonscollectionsk1   ▾] [+ 追加]  │
│ 链     commonscollectionsk1 → templatesimpl │
│        [删除末节点]                          │
│ 参数   ┌──────────────────────────────────┐ │
│        │ Exec.cmd        [whoami         ]│ │  参数按选中节点动态渲染
│        │ ShiroPayload.key[             ]  │ │
│        └──────────────────────────────────┘ │
│ 格式   (·) Base64  ( ) 原始字节             │
│        [生成] [复制] [导出文件] [填入抓包页] │
├─────────────────────────────────────────────┤
│ 输出区（等宽字体，可滚动，显示长度与摘要）   │
└─────────────────────────────────────────────┘
```

界面约束（沿用既有惯例，保证自检可断言）：

- 表单区固定高度，输出区占剩余空间，保证一屏可见。
- 参数区按 `paramsOf(nodeId)` 动态渲染，节点变化时重建。
- 「追加节点」的候选列表来自 `nextNodes(当前节点)`，不可手填，
  避免产生引擎无法构建的非法链。
- 生成按钮在链不合法时给出明确原因（引擎的 `message` 直接展示）。

---

## 六、验证方案

### 6.1 新增自检 `tests/PayloadCheck.java`

覆盖点（每条一个断言）：

1. 引擎初始化成功，节点数 429、载体数 28 与实测一致（数字变化即报警）。
2. 28 个载体 id 全部能创建成功（`build` 返回后 `payload` 非空或给出明确失败原因）。
3. `nextNodes` 对已知节点返回非空且结果稳定（如 `commonscollectionsk1`）。
4. 非法链给出明确失败信息而非抛异常（如直接追加不兼容节点）。
5. `base64` 下沉后 `ShiroEngine.base64` 行为不变（回归断言）。
6. 配置项读写往返一致（写入 → 读取 → 值相同）。

### 6.2 新增界面自检 `tests/UiPayloadCheck.java`

1. 导航项「Payload 生成」可打开，不回落到主页。
2. 载体下拉框选项数 28。
3. 追加节点后链路文本更新。
4. 参数区随节点变化重建。
5. 输出区高度 > 100px（一屏可见）。
6. 生成结果非空且长度与报告一致。

### 6.3 回归

- 五套既有 Java 自检全绿，输出无行为漂移。
- `python -m unittest discover -s tests` 全绿（84 项）。
- 构建后校验 JAR 时间晚于全部源文件。

---

## 七、安全与合规约束

本功能生成的是**可执行利用载荷**，必须遵守既有约束并补充以下几条：

1. 界面顶部保留与 Shiro 页一致的授权提示文案，
   明确「仅用于已授权测试目标」。
2. 不提供批量生成、不提供自动投递、不提供载荷库分享能力。
3. 生成的载荷不在日志、报告、`AI_REPORT.md` 中留存完整内容，
   只记录载体名、节点序列与长度。
4. 不内置任何公网回连地址（如 DNSLog、CEYE 的默认域名保持为空）。
5. 不新增网络能力：全部生成过程在本地内存完成，
   与 `ChainsEngine` 现有注释「所有生成动作都在本地内存中完成，不会发起任何网络请求」一致。

---

## 八、实施顺序

1. ~~**change A**：`base64` 下沉 + `ChainsEngine` 解耦~~ —— **已完成**
   （2026-09-21，`a149f2b`，change `decouple-chain-engine`）。
2. **change B-1**：`src/payload/` 引擎层 + `tests/PayloadCheck.java`（exploit agent，可先于界面完成）。
3. **change B-2**：`src/ui/PayloadPage.java` + 导航 + 配置分组 + `tests/UiPayloadCheck.java`（UI agent）。
4. **change C**：语法糖 —— 「填入抓包页」「填入 Shiro 页」的联动（UI agent，依赖 B-2）。

每个 change 独立走备份、自检、报告、构建校验流程。
第 1 步完成前不得开始第 2 步，否则会在反向依赖上返工。
