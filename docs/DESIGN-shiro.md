# JavaSecExpToolKit Shiro 模块设计文档

版本：0.1.0  
更新日期：2026-09-19  
适用读者：本仓库维护者  
关联文档：`docs/DESIGN.md`（总设计）、`docs/DESIGN-probe-accuracy.md`（探测准确性专项）

---

## 一、背景与目标

### 1.1 需求来源

在 `JavaSecExpToolKit` 的既有能力（Fastjson 探测、抓包转换、代理抓包）之外，需要把
`ShiroExploit-1.0.2.jar` 的功能吸收成本工具的一部分：

1. **识别**：判断目标是否使用 Shiro、是否存在 rememberMe 解密路径；
2. **爆破**：用常见密钥字典破解 rememberMe 的 AES 密钥；
3. **利用**：命中密钥后注入回显链并执行命令。

同时要把 `java-chains`（`https://github.com/vulhub/java-chains`，本仓库使用 `v2.0.0-beta4`）
**作为依赖**集成进来，用它的链式 payload 生成能力替换原工具里写死的链构造逻辑。

### 1.2 与其它模块的边界

这是本工具**唯一**带利用性质的模块。产品边界因此调整为：

| 模块 | 性质 | 是否会改动目标 |
| --- | --- | --- |
| Fastjson 探测 | 识别 | 否，只发无害请求 |
| 抓包转换 / 代理抓包 | 观测与改包 | 否，只转发（拦截改包由使用者主动发起） |
| **Shiro 漏洞利用** | **利用** | **是，会在目标上执行命令** |

因此界面在 Shiro 页明确标注「授权目标」，并且使用前需要使用者自行确认授权范围。

---

## 二、模块结构

```
src/shiro/
├── ShiroEngine.java     加解密 + 指纹 + 爆破 + HTTP 投递（纯 JDK 实现）
├── ChainsEngine.java    java-chains 封装（节点查询、参数、链构建）
├── ShiroExploit.java    利用编排（回显链 / 命令链 / 取回显）
└── res/
    └── shiro-keys.txt   1108 条去重常见密钥字典（随 JAR 打包）
```

分层原则：**协议与密码学在 `ShiroEngine`，链生成在 `ChainsEngine`，业务流程在
`ShiroExploit`**。三者互不越界：`ShiroExploit` 不碰 JCE，`ShiroEngine` 不认识 java-chains。

### 2.1 为什么用 Java 原生实现而不是走 Python

Fastjson 探测走 Python 子进程，是因为探针多、需要 HTTP 指纹比对与报告渲染。
Shiro 模块不同：它需要**高频、低延迟**地做 AES 加解密（爆破要对上千条密钥逐个试解），
每次跨进程调用 Python 的代价远高于加解密本身；而 JDK 自带 JCE 与
`java.net.HttpURLConnection`，实现成本很低。因此 Shiro 模块全程在 Java 进程内完成，
只有链生成交给 java-chains（也是 Java 库）。

---

## 三、指纹识别

### 3.1 原理

Shiro 在 `Cookie: rememberMe=<Base64>` 无法解密时，会返回
`Set-Cookie: rememberMe=deleteMe`。这是最稳定的 Shiro 特征。

### 3.2 实现（`ShiroEngine.detect`）

1. 发一条**不带** rememberMe 的基线请求，记录响应头；
2. 发一条带**随机** rememberMe 的请求，记录响应头；
3. 比较两次响应中 `rememberMe=deleteMe` 的出现次数：出现次数增加即判定命中。

用「计数差」而不是「是否出现」的原因：有些目标在基线请求上也会下发 `deleteMe`
（如会话初始化），只看是否存在会产生假阳性。

### 3.3 已知局限

- 目标若自行改写了 rememberMe 的处理逻辑（例如统一返回登录页），识别会失败。
- 目标是前后端分离且 rememberMe 由前端 Java 服务处理时，需要填对实际接口路径。

---

## 四、加解密设计（`ShiroEngine`）

### 4.1 CBC 模式（Shiro < 1.4.2）

```
cipher = AES/CBC/PKCS5Padding
IV     = key[0..15]        // Shiro 的既有约定：IV 就是密钥前 16 字节
```

加密：`Base64( AES-CBC(key, IV=key[0..15], payload) )`  
解密：先 Base64 解码，再用同一密钥与 IV 解密。

### 4.2 GCM 模式（Shiro ≥ 1.4.2）

```
cipher = AES/GCM/NoPadding
IV     = 随机 16 字节，前置在密文之前
```

即 `Base64( IV || AES-GCM(key, IV, payload) )`。解密时从密文前 16 字节切出 IV。

`gcmCipher()` 优先申请 `AES/GCM/NoPadding`；若运行环境不提供（部分受限 JDK），
回落到 `PKCS5Padding`，**保证模块仍可用**而不是直接抛异常。这会让该环境下的
GCM 目标无法命中，但不会让整个模块不可用。

### 4.3 爆破用的合法载荷

`serializeEmptyPrincipal()` 使用 Apache Shiro 的
`org.apache.shiro.subject.SimplePrincipalCollection`（空主体）序列化后加密。
用合法载荷而不是随机字节：非法载荷会在目标侧反序列化时报错，导致目标可能
不返回 `deleteMe` 而是 500，从而产生**假阴性**。

---

## 五、密钥爆破（`ShiroEngine.crack`）

- 字典：`src/shiro/res/shiro-keys.txt`，1108 条去重常见密钥（默认密钥、各厂商默认值）。
- 并发：固定线程池并发试解，**命中即停**（用 `AtomicBoolean` 通知其它线程退出）。
- 进度：通过回调把「已尝试 / 总数」推给界面进度条。
- 停止：界面「停止」按钮设置 `AtomicBoolean`；已发出的连接不会被强杀，
  只是不再派发新任务，因此停止是**秒级**响应的。
- 结果：命中后把密钥与 GCM 标志回填界面，后续生成 payload 直接沿用，避免重复爆破。

为什么用「解密响应是否含 deleteMe」而不是「解密是否成功」判定：目标是
**先解密再判断**，密钥正确时目标能解出空主体，因此不会返回 `deleteMe`；
密钥错误时才返回 `deleteMe`。所以判据是「响应里**没有** deleteMe」。

---

## 六、链生成（`ChainsEngine`）

### 6.1 java-chains 的模型

java-chains 把 payload 生成拆成两类节点：

- **payload 载体**（`shiropayload`、`fastjsonpayload`、`javanativepayload`…）：
  决定最终产物的外层形态。`shiropayload` 会额外做 Shiro 的 AES 加密与 rememberMe 包装。
- **gadget 节点**（`commonscollectionsk1`、`templatesimpl`、`bytecodeconvert`、`tomcatecho`…）：
  依次追加，引擎按节点之间声明的 tag 约束校验链是否合法。

### 6.2 封装层做过的关键适配

| 问题 | 处理 |
| --- | --- |
| 节点 id 大小写敏感 | 统一小写后再查表（`tomcatecho` 而不是 `TomcatEcho`） |
| 参数名带前缀（`Exec.cmd`） | `normalizeParams()` 同时接受 `Exec.cmd` 与 `cmd` |
| 无链构建会报 `gadget tags is empty` | 封装层返回结构化 `Generated.fail(原因)`，不抛异常 |
| 引擎依赖 JDK 内部 xalan 实现 | Java 17 需在启动参数开放 `--add-opens`（见 `run.ps1`） |
| 初始化失败（含模块访问异常） | 不抛异常，把原因写进 `statusMessage()` 供界面展示 |

### 6.3 预置链模板（`templates()`）

| 名称 | 载体 | 节点链 | 用途 |
| --- | --- | --- | --- |
| Shiro + CB1 回显 | `shiropayload` | `commonsbeanutils1 → templatesimpl → bytecodeconvert → tomcatecho` | CB 依赖环境回显 |
| Shiro + CCK1 回显 | `shiropayload` | `commonscollectionsk1 → …` | CC 依赖环境回显 |
| Shiro + CCK1 命令执行 | `shiropayload` | `commonscollectionsk1 → … → exec` | 无回显，直接执行 |
| Shiro 探测链 | `shiropayload` | `commonscollectionsk1` | 仅验证密钥能否解密 |
| Fastjson 回显 | `fastjsonpayload` | `exec` | Fastjson 场景载荷 |
| Jackson 回显 | `javanativepayload` | `commonscollectionsk1` | Jackson 场景载荷 |

---

## 七、利用编排（`ShiroExploit`）

### 7.1 三条回显链

`ChainKind` 枚举定义 `CB19` / `CCK1` / `CCK2`，区别只在**第一条 gadget**：

```
CB19: commonsbeanutils1   → templatesimpl → bytecodeconvert → tomcatecho
CCK1: commonscollectionsk1 → templatesimpl → bytecodeconvert → tomcatecho
CCK2: commonscollectionsk2 → templatesimpl → bytecodeconvert → tomcatecho
```

选择依据是目标的依赖：有 CommonsBeanutils 用 CB19，有 CommonsCollections 3 用 CCK1，
有 CommonsCollections 4 用 CCK2。界面下拉框直接展示这三项。

### 7.2 回显机制

传统 Shiro 利用每执行一条命令就要打一次链，噪声大且慢。本模块采用**注入一次、
多次复用**的方式：

1. **注入阶段**：生成回显链（末端 `tomcatecho`）并投递到 rememberMe Cookie，
   在目标上注入「回显马」；回显马的回显请求头由界面「回显请求头」指定（默认
   `X-Authorization`）。
2. **执行阶段**：之后每条命令都是**普通 HTTP 请求**——命令 Base64 后放进该请求头。
3. **取回显**：从响应体中提取结果（回显马会把命令输出放在独占一行，以 `inptrj` 作标记，
   提取时会剔除标记行）。

因此连续执行多条命令只打一次链，显著降低对目标的扰动，也更容易在日志告警前完成验证。

### 7.3 命令 Base64 的原因

命令可能含中文、空格、引号，直接放进 HTTP 头会因为头部字符集限制被截断或变形。
统一 Base64 后放进头部可以无损传输，由回显马自行解码。

---

## 八、界面设计

Shiro 页（`主页 → Shiro → Shiro 漏洞利用`）字段与按钮：

| 控件 | 作用 |
| --- | --- |
| `目标 URL` | 要投递的地址，通常是带 rememberMe 处理的业务接口 |
| `请求方法` | `GET` / `POST`，投递 payload 时使用 |
| `Cookie 名` | 默认 `rememberMe`，个别目标会改名 |
| `密钥` | Base64 形式的 AES 密钥，可直接填或由爆破回填 |
| `AES-GCM` | 目标是否为 Shiro ≥ 1.4.2 的 GCM 模式 |
| `回显请求头` | 默认 `X-Authorization`，回显马从该头读命令 |
| `利用链` | `CB19` / `CCK1` / `CCK2` |
| `命令` | 要执行的命令，默认 `whoami` |
| `附加请求头` | 需要登录态时填 `Cookie: JWT_TOKEN=…` 等 |
| `一键检测` | 指纹识别 |
| `密钥爆破` / `停止` | 字典爆破与中断，带进度条 |
| `生成 Payload` | 只生成并打印 Base64 payload，**不投递** |
| `执行命令` | 生成回显链 → 投递 → 执行命令 → 取回显，全过程打印到输出区 |

「生成 Payload」与「执行命令」分开，是为了让使用者可以先只生成 payload（用于
手工投递到 Burp 等工具），确认无误后再让工具直接投递。

**输出的隔离（一个功能一个回显框）**

四个按钮分别写入四个独立文本域，点入口时自动切到自己的页签：

| 功能 | 回显框 | 页签 |
| --- | --- | --- |
| `一键检测` | `shiroDetectOutput` | `指纹检测` |
| `密钥爆破` | `shiroCrackOutput` | `密钥爆破` |
| `生成 Payload` | `shiroBuildOutput` | `生成 Payload` |
| `执行命令` | `shiroRunOutput` | `执行命令` |

这四个文本域由 `JTabbedPane shiroOutputTabs` 承载，`shiroAppend(target, text)` 只往
指定目标追加。隔离的意义在于：密钥爆破会打上百行进度，执行命令输出很长，
若都写进同一个文本域，「检测结论 → 爆破出的密钥 → 生成的链 → 命令回显」这条链路
会被互相冲掉，回头排查时找不到原始结论。

---

## 九、验证

| 载体 | 覆盖内容 |
| --- | --- |
| `tests/ShiroCheck.java` | CBC/GCM 加解密往返、`deleteMe` 指纹、爆破命中、非法链被拒、CCK1 回显链生成且可由本机密钥解密、CB1/CCK1/CCK2 三条链、回显提取 |
| `tests/UiShiroCheck.java` | Shiro 页控件齐备、桩服务上「一键检测」确认存在 Shiro、界面可生成回显链并输出 Base64、四个功能各有独立回显框（生成 Payload 不写进指纹检测框、指纹结论仍保留、页签数为 4） |

`ShiroCheck` 的关键设计：**用本机密钥解自己生成的 payload**，验证「链能构建 +
能被正确密钥解密」。这同时证明了 java-chains 的 `shiropayload` 载体与
`ShiroEngine.encrypt` 的输出格式一致（都是 `Base64(IV||GCM(...))` 或
`Base64(CBC(..., IV=key[0..15]))`）。

运行需要开放的模块（java-chains 生成字节码 gadget 时访问 JDK 内部实现）：

```powershell
java --add-opens java.xml/com.sun.org.apache.xalan.internal.xsltc.trax=ALL-UNNAMED `
     --add-opens java.xml/com.sun.org.apache.xalan.internal.xsltc.runtime=ALL-UNNAMED `
     -cp "target/tmp2;target/classes;lib/java-chains-cli-2.0.0-beta4.jar" ShiroCheck
```

`run.ps1` 已包含这两个 `--add-opens`，正常使用不会踩到。

---

## 十、依赖与构建

- 依赖：`org.vulhub:java-chains-cli:2.0.0-beta4`。
- 该构件未发布到中央仓库，因此 `src/pom.xml` 声明本地文件仓库
  `file:///${project.basedir}/../libs-repo`，仓库内直接保存 JAR 与手写 POM。
- `maven-dependency-plugin` 在 `prepare-package` 阶段把运行时依赖复制到根目录 `lib/`；
  `maven-jar-plugin` 在清单写入 `Class-Path: lib/…`，因此 `java -jar` 也能加载。
- 打包资源：`src/shiro/res/shiro-keys.txt` → JAR 内 `shiro/res/shiro-keys.txt`。
- java-chains 启动时会在**依赖 JAR 所在目录**寻找 `chains-config/`，找不到才回落到
  `user.dir`。由于依赖被复制到 `lib/`，该目录一并放在 `lib/chains-config/`
  （内含 `cache/metadata/node-index.yaml`），避免把运行时缓存写到仓库根。

---

## 十一、已知限制

- **授权要求**：模块会在目标上执行命令，仅限书面授权的目标。
- 链能否打通取决于目标依赖（CommonsBeanutils / CommonsCollections 版本）与 JDK 版本；
  失败时输出区给出链的构建报错，可据此更换利用链。
- 回显依赖目标容器为 Tomcat；非 Tomcat 容器需要改用其它回显 gadget。
- 密钥爆破会持续发送请求，可能触发日志告警与账号锁定策略。
- GCM 模式在不提供 `AES/GCM/NoPadding` 的受限 JDK 上会回落 PKCS5Padding，
  此时 GCM 目标无法命中（界面不报错，只是爆破不到）。
- 字典为固定 1108 条，命中不了自定义密钥；可手工把密钥填进「密钥」输入框。
