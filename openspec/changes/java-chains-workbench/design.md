# Design: 恶意服务器与预设链

## 一、Root Cause：Shiro 回显链无法被本机密钥解密

**现象**：`ShiroCheck` 的「生成的 payload 可被本机密钥解密」断言失败；
`UiShiroCheck` 的「Shiro 链生成结果可被本机密钥解密」同样失败。

**根因**：上一轮把 `PayloadEngine.build` 拆成「`buildRaw` 构建 → `build` 汇总」时，
新增了一条按字节通用处理的路径：

```
List<String> chain = cleanChain(gadgets);
if (raw.bytes.length > 0) return PayloadResult.ok(payloadId.trim(), chain, raw.bytes);
return PayloadResult.okText(payloadId.trim(), chain, String.valueOf(raw.object));
```

`buildRaw` 对 `String` 形态的产物做了 `getBytes(UTF_8)`，于是
`raw.bytes.length > 0` **恒真**，`build` 永远走 `PayloadResult.ok(...)` 分支。
而 `PayloadResult.ok` 会再做一次 `Codec.base64(bytes)`——Shiro 载体产出的本来就是
Base64 文本，被当成「待编码的字节」又编码了一次，得到的是双重编码结果，
`ShiroEngine.decodeBase64` → `decrypt` 自然解不出来。

**修复**：`build` 先判对象是否本就是 `String`，是则直接走 `okText`（按文本原样交付），
不再经过字节分支；同时把 `buildRaw` 中 `String → bytes` 的转换从「UTF-8 取字节」
改成「先 Base64 解码，失败再按 UTF-8」，使 `buildRaw` 的 `bytes` 与
`PayloadResult.okText` 的 `bytes` 语义一致——两个入口指向同一份载荷字节。

**回归证据**：修复后 `ShiroCheck`（含该断言）与 `UiShiroCheck` 均退出码 0。

## 二、实测结论：为什么不需要 Spring

上游 java-chains 的服务端适配器（`ProtocolRuntimeAdapter` 系列）与
`ServiceLifecycleService` 都是纯 JDK 实现，不依赖 Spring 容器、不依赖 Servlet 容器。
本仓库直接用 `new` 组装后即可真实监听端口。实测：五类服务全部启动成功，
LDAP 端口可被真实客户端取回已发布对象。

因此 **不引入 Spring**（用户已授权可在需要时引入，但实测无此需要）。
这样 `src/pom.xml` 不需要新增依赖，符合仓库「禁止新增第三方依赖」的硬约束。

## 三、实测结论：端口键与载荷类型（决定了实现写法）

| ServiceKind | 端口键 | 说明 |
| --- | --- | --- |
| JNDI | `ldap` / `rmi` / `http` / `ldaps` | 四个独立端口 |
| HTTP / MYSQL / JRMP / TCP | `main` | 上游复用同一个键名，非 JNDI 服务写别的键会被绑定层直接拒绝 |

| 协议 | 可接受的载荷类型 |
| --- | --- |
| JRMP | 仅 `OBJECT` |
| JNDI / FakeMySQL | `OBJECT` / `BYTES` / `COMPOUND`（**不接受** `TEXT`） |
| HTTP / TCP | `BYTES` / `TEXT` |

**LDAPS 的坑**：上游要求「启用 LDAPS 就必须同时给出 JKS 证书路径」，
默认下发该端口会让整个 JNDI 启动被拒（实测 `invalid start configuration`）。
因此 `ServiceSpec.PortSpec.optional=true`，只有显式填写端口才下发。

**发布入口的选择**：三参 `publish(...)` 必然被拒
（`SCOPE_FORBIDDEN: runtime:control is required when autoStart=true`）；
四参版本可用，但绕开 autoStart 与载荷构建契约层的
`ProtocolRuntimePort.putPublication(PutPublicationCommand)` 更稳，本次采用后者。

**地址拼装**：上游返回的 `output` 对 JNDI 不含地址、对 TCP / JRMP 只有占位符。
`ServiceManager.addressOf` 按协议补出可直接使用（可点击、可复制）的地址。

## 四、边界：本次不改变哪些既有行为

- Fastjson 探测引擎、代理抓包、抓包转换、Shiro 检测 / 爆破 / 回显策略：全部不变。
- 既有五套 Java 自检（`ShiroCheck` / `ProxyServerCheck` / `UiShiroCheck` /
  `UiSwitchEndToEndCheck` / `PayloadCheck`）的断言只增不减。
- `src/config/**` 与 `src/util/**` 两个共享内核本次只读。
- 界面自检的公共运行方式不变：仍需 JDK 17 与两个 `--add-opens`。

## 五、与既有模块的边界

`service` 包只依赖 `payload`（把链交给引擎、把结果发布到端口），
不依赖 `ui`；`preset` 包是纯读取层，不依赖任何项目包。
界面层（`ui`）依赖这两个包，方向单一，不构成环。

`ui` 内部按「视图 `*Page` + 行为 `*Controller`」三段式拆分，
两个新页与既有页保持一致：视图只搭结构，行为放控制器，页面不持有运行状态。
