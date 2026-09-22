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
| 载体选择 | 28 种 payload 载体，作为列式选择器的第一列直接列出 |
| 列式选链 | 每级一列，点候选即追加并展开下一列；点回前面某列即从那里重开链 |
| 候选过滤 | 每列一个关键字过滤框（按名称或标识），候选上百项时先收敛再点 |
| 参数配置 | 按选中节点动态渲染其可配置参数（`paramsOf`） |
| 载荷生成 | 产出 Base64 或原始字节的载荷 |
| 载荷导出 | 复制到剪贴板、导出文件、直接填入抓包页 |

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
- 候选列表来自引擎（首节点逐个校验、后继查引擎的后继表），不可手填，
  避免产生引擎无法构建的非法链。
- 生成按钮在链不合法时给出明确原因（引擎的 `message` 直接展示）。

### 5.1 列式交互（2026-09-21 改造，见 change `payload-generate-view`）

原交互是「`载体分组` → `载荷载体` → `追加节点` → `追加节点` 按钮」四个控件串成的流水线。
成因是状态模型按「链尾」组织（`ChainEditor.candidates()` 只回答「末尾还能接什么」），
加上候选被绑死在一维下拉框里，于是「改中间第 K 层」只能靠连点 `删除末节点` 退回去。
改造把**层级**引入视图：状态仍是同一条链，但界面按列展开。

```
┌─ Payload 生成 ───────────────────────────────────────────────┐
│ 当前链 [javanativepayload -> clojure        ] [删除末节点][清空链] │
│ ┌ 选择利用链 ──────────────┐ ┌ 节点参数 ──────────┐          │
│ │ 载荷载体 │ 第 1 级节点 │ 第 2 级节点 │ 名称  [输入]     │          │
│ │ [过滤]   │ [过滤]      │ [过滤]      │ 命令  [输入]     │          │
│ │ Amf...   │ Templates...│ 加载字节码   │ ...             │          │
│ │ Java原生…│ 执行命令     │ ...         │                 │          │
│ └──────────┴─────────────┴─────────────┘ └─────────────────┘          │
│ [生成载荷][复制][导出文件][填入抓包页]  状态行                        │
├──────────────────────────────────────────────────────────────┤
│ 载荷输出（等宽字体，可滚动）                                  │
└──────────────────────────────────────────────────────────────┘
```

四条规则（均已由 `UiNavigationCheck` 断言）：

1. 列数 = 链长 + 1；末端无后继时不再展开空列（叶子节点选中后不会多出一列空列表）。
2. 点第 N 列的候选 = 链截断为「前 N-1 项 + 新候选」，第 N 列之后全部丢弃并重算。
3. 重复点当前选中项不改变链（Swing 对「再次选中同一项」不发事件，
   因此该判定收在 `PayloadChainSelector.choose(...)` 一处，鼠标与程序调用行为一致）。
4. 过滤只收敛该列渲染项，且当前选中项始终保留在列表里；
   过滤框本身不参与整体重建（否则输入第二个字符时光标已随控件丢失）。

参数区与选择器**并排**而不是上下排：竖排时两者高度相加会超出分栏给表单的高度，
默认最大化下就出滚动条（实测竖排 592px 被裁掉约 40px）。

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

- 既有 Java 自检全绿，输出无行为漂移（截至 2026-09-22 为六套）。
- `python -m unittest discover -s tests` 全绿（截至 2026-09-22 为 106 项）。
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
2. ~~**change B-1**：`src/payload/` 引擎层 + `tests/PayloadCheck.java`~~ —— **已完成**
   （2026-09-21）：`PayloadEngine` / `PayloadCatalog` / `PayloadResult` 三个类，
   `ChainsEngine` 通用实现全部委派、公开签名不变；`PayloadCheck` 61 条断言。
3. ~~**change B-2**：`src/ui/PayloadPage.java` + 导航 + 配置分组~~ —— **已完成**
   （2026-09-21）：页面与控制器分离，配置键 `payload_export_dir` 落进独立分组
   `Payload 生成配置`；界面断言并入既有的 `tests/UiNavigationCheck.java`
   （未另建 `UiPayloadCheck`：本仓库的 UI 自检统一入口是导航自检，另开一个文件会让
   「五套 Java 自检」变成六套，收益不足以抵消入口分裂）。
4. ~~**change C**：语法糖 —— 「填入抓包页」联动~~ —— **已完成**（2026-09-21）：
   `PayloadPage.CaptureSink` 由 `Main` 注入，写 `captureBody` 后跳到 `抓包转换` 页。
   「填入 Shiro 页」未做：Shiro 页的载荷输入框是只读回显区，填入需要另改该页语义，
   超出本次范围。
5. ~~**change D**：选链交互对齐网页版 Generate~~ —— **已完成**
   （2026-09-21，change `payload-generate-view`）：`追加节点` 下拉框 + 按钮换成列式选择器，
   `载体分组` 与 `载荷载体` 两个下拉框并入第一列；新增 `PayloadEngine.nodeLabel` 只读显示名查询；
   引擎既有方法签名与语义零改动，`PayloadCheck` 由 61 条断言增至 68 条。

每个 change 独立走备份、自检、报告、构建校验流程。

## 九、实现期间的实测发现（与原设计的差异）

| 项 | 原设计 | 实测 | 处理 |
| --- | --- | --- | --- |
| 载体下拉项数 | 28 | 28 | 一致 |
| 首节点查询 | 用 `nextNodes(载体)` | 载体自身无后继，`nextNodes` 返回**空集** | 改为逐个候选做 `validateChainTags` 校验 |
| 非法链 | 预期抛异常 | 返回带原因的失败结论 | 断言改为「带原因的失败且不产出载荷」 |
| 逐个载体创建 | 预期 28 个全部成功 | 不带参数时 1 个成功、27 个被必填参数拒绝 | 断言改为「结论确定」而非「全部成功」 |
| 参数键 | 未明确 | 完整键（`Exec.cmd` / `ShiroPayload.shiroKey`） | 参数表单直接渲染完整键 |
| 输出区高度 | > 100px | 首版实测 62px | `outputPanel` 补最小高度（根因：分栏缺最小高度） |
| 分组一致性 | 未明确 | `PayloadCatalog.diff(runtime)` = `[]` | 加断言固化为零偏差 |

### 9.1 列式改造期间的实测发现

| 项 | 预期 | 实测 | 处理 |
| --- | --- | --- | --- |
| 在末尾列追加节点 | 正常追加 | 被边界写成 `column > chain.size() - 1` 挡掉，点候选毫无反应 | 修正为 `column > chain.size()`；由 `UiNavigationCheck` 的「点第二列候选后链变为两段」抓出 |
| `clojure` 的后继 | 认为可继续接 | 实测 `nextNodes("clojure")` 为**空集**（叶子节点） | 断言改用真实存在的路径 `aspectjweaver -> storeablecachingmap` |
| `templatesimpl` 是否在 `javanativepayload` 的首节点里 | 假设在 | 实测**不在**；首节点按「载体 + 候选」校验，与 `nextNodes` 是两套判据 | 测试序列改为只用实测通过的关系 |
| 节点显示名 | 以为都可取 | 429 个节点里 3 个没有显示名（`HutoolJndiDSFactory` 等） | 引擎返回空串、由界面回退成标识，并加断言 |
| 过滤后的当前选中项 | 会被过滤掉 | 按规格必须保留 | 断言改为「除保留项外都命中关键字」 |
| 过滤输入连续性 | 整体重建即可 | 整体重建会换掉过滤框，光标丢失，第二个字符落空 | 过滤只重绘列表（`repaintColumns`），不重建列结构 |
| 单列首节点候选规模 | 未知 | `javanativepayload` 实测 104 项、`objectpayload` 达 118 项；28 个载体合计 1748 项，全量校验 49 ms | 列式列表 + 列内过滤；不需要缓存 |
| 表单区高度 | 竖排 592px 够放 | 默认最大化下被裁掉约 40px，出滚动条 | 选择器与参数区改为**并排**，表单 424px |
| 列内横向滚动条 | 无 | 显示名较长时列内出现横向滚动条 | 固定单元格宽度（`setFixedCellWidth`）+ 行高，长名截断成省略号，靠工具提示补全 |

---

## 十、界面层拆分与自检副作用清理（2026-09-22）

### 10.1 为什么必须拆

列式改造把交互逻辑堆进了三个文件，越过了
`openspec/specs/codebase/dependency-boundary/spec.md` 的「单个界面文件 ≤ 600 行」：
`PayloadPage` 787 行、`PayloadController` 739 行、`PayloadChainSelector` 729 行。
契约要求拆分而不是放宽上限（放宽会让「一个文件承担全部页面职责」的退化重新变得合法）。

拆分按「职责」而非「行数」切：

| 原文件 | 拆分前 | 拆分后 | 新增协作类 | 协作类的职责 |
| --- | ---: | ---: | --- | --- |
| `src/ui/PayloadPage.java` | 787 | 252 | `PayloadPanels`（520）、`PayloadColumns`（72） | 面板构建；列宽 / 列索引换算 |
| `src/ui/PayloadController.java` | 739 | 546 | `PayloadOutputText`（95）、`PayloadExporter`（56） | 输出区文本渲染；导出落盘 |
| `src/ui/PayloadChainSelector.java` | 729 | 549 | `ChainColumnFilter`（89）、`ChainNodeRenderer`（59）、`ChainTagMenu`（78） | 过滤判定；条目渲染；标签菜单 |

交付形状上的两条约束：

1. **不引入状态复制**。`PayloadChainSelector` 仍是链状态的唯一持有者，`ChainTagMenu`
   通过 `Handler` 接口（`chosen` / `isIntersect` / `apply` / `refresh`）回调，自己一份状态都不存。
2. **不改引擎语义**。`src/payload/` 本轮只新增只读类型（`NodeInfo` / `PayloadBuild` /
   `PayloadCodec` / `PayloadContextEntry`），`PayloadEngine` 既有公开方法签名与返回语义不变，
   `PayloadCheck` 的 68 条断言逐条保留（只新增显示名相关断言）。

`PayloadPanels` 的 `console` / `chainArea` / `splitPane` 是**包级可见**而非私有：
页面类需要把这三个控件挂进分栏，隐藏它们就只能再加一层转发方法。

### 10.2 自检会弹出计算器进程

**现象**：跑 `UiNavigationCheck` 时机器上弹出计算器窗口。

**根因**（实测，不是猜测）：上游 java-chains 的 `Clojure` / `Exec` 节点把命令写在**参数值**里，
且登记表给的默认值就是 `calc`：

```
== clojure
   key=Clojure.cmd value=calc required=true
== exec
   key=Exec.cmd value=calc required=true
```

自检为了验证「生成 / 调试生成 / 预设链生成」三条路径，必须在构建期把参数交给引擎，
引擎随即执行 → 弹出计算器。也就是说这不是自检写错了，而是自检**如实覆盖了真实链路**，
副作用来自被测能力本身。

**处置**：新增 `tests/TestProcessGuard.java`，六个自检入口在 `main` 第一行调用
`TestProcessGuard.install("<入口名>")`：

1. 启动时枚举 `CalculatorApp` 进程并记录 **pid + 启动时刻**（快照）；
2. **先快照、再注册 JVM 退出钩子**（顺序反了守卫会静默失效）；
3. 退出时只 `destroyForcibly()` 启动时刻晚于快照的进程，最多扫 5 轮 × 300 ms，
   覆盖「刚被拉起、进程还没出现在枚举里」的窗口。

用**退出钩子**而不是 `try/finally`，因为自检收尾走 `System.exit`，`finally` 不保证执行。
只用 JDK 8 可用的 `ProcessHandle`；`allProcesses()` 返回 `Stream`，必须用 `.forEach()`
（for-each 在 `Stream` 上编译不过）。

**不清理策略**：用户自己开的计算器（快照里已存在）一律不动；
把 `calc` 换成别的命令时守卫不生效——这是刻意的，守卫只解决「自检把计算器留在机器上」，
不做通用进程清理。

实测结果：`UiNavigationCheck` 一轮弹出 2 个计算器并被全部回收
（日志 `[calc-guard] UiNavigationCheck：已关闭自检期间弹出的 2 个计算器进程 [...]`），
其余五个入口日志为「本轮未弹出计算器进程」，六轮跑完 `CalculatorApp` 零残留。

### 10.3 配置页补漏

列式改造引入的两个开关本轮才落页，属于「新增功能的持久化配置必须进配置页」的补欠：

| 配置键 | 界面文案 | 默认 |
| --- | --- | --- |
| `payload_auto_expand` | 生成后默认展开完整载荷 | 开 |
| `payload_hover_select` | 默认开启悬停选链 | 开 |

## 十一、启动预热与版面比例对齐（2026-09-22）

### 11.1 为什么要有启动预热

改版后单页要塞下更多内容，进页面时的第一帧会做一次性的重活：java-chains 的
`MetadataRegistry.init()` 实测 **1074 ms**（连插件与 gadget 注册合计约 1.2 s），
而它当时是在**第一次进 Payload 页**、并且**在事件分发线程上**同步跑的。
表现就是「第一次点进 Payload 卡几秒」——恶意服务器页同样是首次进页才建适配器。

**方案**：新增 `src/ui/StartupWarmup.java`，把三件事做成一次性的幂等预热：

| 步骤 | 实测 | 内容 |
| --- | --- | --- |
| java-chains 引擎 | 1154 ms | `PayloadEngine.init()`，429 节点 / 28 载体 |
| 预设链目录 | 46 ms | 52 条预设 |
| 服务适配器 | 22 ms | 五类恶意服务器 |

- **幂等**：`warmUp` 完成后 `isDone()` 为真，再次调用 **0 ms**，重复进页面不会重复初始化。
- **不阻塞界面**：`main` 先弹 `StartupSplash`（无边框启动画面，headless 下自动跳过），
  **在后台线程**预热，主窗口在事件分发线程上构建；窗口出现时预热若还没完，
  剩余步骤在 `show(splash)` 里同步补齐，预热结束才关掉启动画面。
- 启动画面上的进度文字就是各步耗时，慢在哪一步一眼可见。

实测：首次 1263 ms，二次 0 ms，预设 52 条。

### 11.2 选链区高度：对齐的是比例，不是绝对值

网页版 Generate 页的选链区不是按比例分的，而是**控制台固定 360px、选链区吃掉剩余高度**。
所以「和网页版一样高」这个说法本身不成立——网页版会随窗口变，比例才是不变量。

为避免再靠估，本轮改成**实测**：用 CDP（Chrome DevTools Protocol）驱动无头 Chrome 打开
上游 `#/Generate/<payloadKey>` 页，读 `getBoundingClientRect()`，得到权威数值：

| 元素 | 选择器 | 实测高度 |
| --- | --- | --- |
| 控制台 | `.studio-top-console` | 360 px |
| 选链区 | `.chain-builder-block` | 486 px |
| 列面板 | `.chain-column` | 389 px |
| 候选列表 | `.option-list` | 320 px |

两块之比为 360 : 486，即**选链区占两块之和的 57.4%**。因此
`PayloadPanels.CONSOLE_WEIGHT` 由常量直接算出来：

```java
static final double CONSOLE_WEIGHT =
        (double) WEB_CONSOLE_HEIGHT / (WEB_CONSOLE_HEIGHT + WEB_CHAIN_HEIGHT);
```

**实测对照**（窗口 1721×1033，可用高 817px）：

| | 控制台 | 选链区 | 选链区占比 | 候选列表可视高 |
| --- | --- | --- | --- | --- |
| 网页版（设计值） | 360 px | 486 px | 57.4% | 320 px |
| 本工具（实测） | 343 px | 462 px | 56.5% | 309 px |

列宽、列表档位沿用第十节已对齐的网页版常量（300~500px 列宽、160~640px 列表高、
默认 320px、双击在 320/480 间切换），本轮不变。

### 11.3 分割线必须在「布局那一刻」生效

**根因**（实测，不是猜测）：原来靠 `componentResized` / `componentShown` 事件回调去
`setDividerLocation`。组件事件是**异步投递**的，布局结束到事件被处理之间存在一个空窗，
此时分割线还停在上一次尺寸算出来的位置。同一个构建里因此能读到两个值：

```
[probe2] round=0 split=817 divider=290 output=101   <- 自检读到的就是这个空窗
[probe2] round=1 split=817 divider=322 output=133   <- 事件处理完之后
```

这既让 `UiNavigationCheck` 的「输出区可视高度 > 120px」失败，也意味着使用者拖窗口时
分割线会先停在错处再跳一下。

**方案**：新增 `src/ui/RatioSplitPane.java`，把比例校正放进 `doLayout()`——每次布局先按
当前尺寸摆好分割线，布局一结束位置就是对的，不依赖任何后续事件。要点：

- `doLayout()` 里调 `setDividerLocation` 会触发 `revalidate()`，正处在布局过程中会重入，
  因此用 `applying` 标志挡住重入；
- 用户一旦按在分割线上拖动（或调用 `lock()`），比例立即**失效**，不再把使用者调好的位置拽回去；
- 选链区拖拽条改高度时走 `SplitPaneKit.setDivider(...)`，它先 `lock()` 再挪分割线；
  不锁的话下一次布局会立刻按比例拽回原位，使用者看到的是「拖了没反应」。

修完后 `divider` 第一次读就是 322px，输出区可视高 133px（改为比例后为 154px）。

### 11.4 界面文件行数与新增文件

新增的类都保持单一职责、均远低于 600 行上限：

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `src/ui/RatioSplitPane.java` | 87 | 在布局期按比例摆放分割线的分栏 |
| `src/ui/SplitPaneKit.java` | 64 | 分栏构造、锁定式 `setDivider`、固定高度/拉伸工具 |
| `src/ui/StartupWarmup.java` | 138 | 幂等启动预热 |
| `src/ui/StartupSplash.java` | 96 | 无边框启动画面 |
| `src/ui/ChainSelectorSizing.java` | 85 | 选链几何常量与纯算术 |
| `src/ui/ChainColumnPanel.java` | 220 | 一列的面板（列头 + 过滤 + 列表） |
| `src/ui/WrappedLabel.java` | 151 | 按宽度折行的标签（`getText()` 仍是原始文本） |
