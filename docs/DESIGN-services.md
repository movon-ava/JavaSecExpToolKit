# 设计：恶意服务器与预设链

关联：`docs/DESIGN-payload.md`（通用载荷生成引擎）、
`openspec/changes/java-chains-workbench/`（本功能的 change 规划件）、
`openspec/changes/java-chains-workbench/specs/services/malicious-server/spec.md`、
`openspec/changes/java-chains-workbench/specs/presets/preset-chain/spec.md`
（archive 后成为 `openspec/specs/` 下的主 spec）。

---

## 一、为什么不需要 Spring（实测结论）

引入前先做了验证，而不是照着「上游是 Web 项目」的印象加依赖：

| 实测项 | 结论 |
| --- | --- |
| `ServiceLifecycleService` + `ProtocolRuntimeRegistry` 直接 `new` 组装 | 可运行 |
| 五类服务真实绑定端口（JNDI / HTTP / TCP / FakeMySQL / JRMP） | 全部成功 |
| LDAP 端口被真实客户端取回已发布对象 | 成功 |
| 是否依赖 Spring / Servlet 容器 | **不依赖**，适配器是纯 JDK 实现 |

因此 `src/pom.xml` 未新增任何依赖，符合本仓库「禁止新增第三方依赖」的硬约束。

---

## 二、Root Cause：载荷双重 Base64 编码

**现象**：`ShiroCheck` 的「生成的 payload 可被本机密钥解密」失败；`UiShiroCheck` 同样失败。

**成因**：`PayloadEngine.build` 拆分出 `buildRaw` 后，`buildRaw` 对 `String` 形态产物做了
`getBytes(UTF_8)`，于是 `build` 里的 `raw.bytes.length > 0` **恒真**，永远走字节分支；
`PayloadResult.ok` 对该字节再做一次 Base64，而 Shiro 载体产出的本就是 Base64 文本，
于是得到双重编码结果，解密必然失败。

**修法**：`build` 先判产物是否本就是 `String`，是则直接按文本交付；
`buildRaw` 的文本转字节改为「先 Base64 解码、失败再按 UTF-8」，
使其字节语义与 `PayloadResult.okText` 一致——两个入口指向同一份载荷字节。

---

## 三、上游实测约束

### 3.1 端口键

| ServiceKind | 端口键 |
| --- | --- |
| JNDI | `ldap` / `rmi` / `http` / `ldaps` |
| HTTP / MYSQL / JRMP / TCP | `main` |

非 JNDI 服务写成 `http` / `tcp` 之类会被绑定层直接拒绝，因此 `ServiceSpec` 对每类服务
显式声明端口键，而不是按服务名推导。

### 3.2 LDAPS 的可选性

上游要求「启用 LDAPS 就必须同时给出 JKS 证书路径」，默认下发会让**整个 JNDI 启动被拒**
（实测 `invalid start configuration`）。`ServiceSpec.PortSpec.optional=true` 表示
「未显式填写就不下发」，界面在标签上标注「留空则不启用」。

### 3.3 载荷类型与协议

| 协议 | 可接受类型 |
| --- | --- |
| JRMP | 仅 `OBJECT` |
| JNDI / FakeMySQL | `OBJECT` / `BYTES` / `COMPOUND`（不收 `TEXT`） |
| HTTP / TCP | `BYTES` / `TEXT` |

类型不符时返回带原因的失败结论，不把「上游拒绝」当成成功。

### 3.4 发布入口

三参 `publish(...)` 必然被拒（`SCOPE_FORBIDDEN: runtime:control is required when autoStart=true`）。
四参版本可用，但 `ProtocolRuntimePort.putPublication(PutPublicationCommand)`
绕开了 autoStart 与载荷构建契约层，行为更可控，本次采用后者。

### 3.5 地址拼装

上游返回的 `output` 对 JNDI 不含地址、对 TCP / JRMP 只有 `advertisedHost:port` 占位符。
`ServiceManager.addressOf` 按协议补出可直接复制的地址；JNDI 额外给出已启用端口的
LDAP / RMI / HTTP 三个入口（多行，抓包页取第一行）。

---

## 四、模块边界

```
ui ──> service ──> payload ──> util
 └───> preset
```

| 包 | 定位 | 出边 |
| --- | --- | --- |
| `src/service/` | 唯一调用 java-chains 服务端适配器的包；对上层只暴露纯数据类 | 只依赖 `payload` |
| `src/preset/` | 唯一引用上游预设模型的包；对上层只暴露纯数据类 | 无 |

两者都被 `tests/test_decoupling.py` 机械校验。收敛意图：上游的类型多且会随版本变化，
若让界面直接引用，上游一改版就要连界面一起改。

界面层继续沿用「视图 `*Page` + 行为 `*Controller`」三段式：
`ServicePage` / `PresetPage` 只搭结构，启停与生成行为在对应控制器里，页面本身不持有运行状态。

`ChainEditor` 被 `PayloadController` 与 `ServiceController` 共用：两个页面都要「载体不可移除、
只接受引擎认可的后继节点」这套规则，各写一份迟早会不一致。

---

## 五、本设计不改变哪些既有行为

- Fastjson 探测引擎、代理抓包、抓包转换：完全不变。
- Shiro 检测 / 爆破 / 回显策略：不变；本次只修载荷编码缺陷。
- 既有五套 Java 自检与 Python 单测：断言只增不减，全部保持通过。
- `src/config/**` 与 `src/util/**`：只读。
- 界面自检的公共运行方式：不变，仍需 JDK 17 与两个 `--add-opens`。
