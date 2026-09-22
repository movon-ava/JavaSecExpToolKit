# 设计：漏洞分析（本地依赖分析 + 调用链查询 + 反编译）

关联：`openspec/changes/analyze-local-deps/`（C 打底：依赖坐标与规则判定）、
`openspec/changes/ui-analyze-view/`（界面与调用链查询）、
`src/analyzer/`（分析内核）、`src/analyze/`（编排层）、`python/jar_report.py`（数据库查询）。

---

## 一、为什么是「C 打底 + B 深挖」而不是只做一种

拿到一个来源不明的 jar，使用者真正要回答的是两个不同层级的问题：

| 问题 | 需要的数据 | 代价 |
| --- | --- | --- |
| 这里面**引入了什么组件**，版本落在哪些公开公告的受影响区间里 | jar 内打包的 Maven 坐标 | 秒级 |
| 这些组件之间**怎么被调用**，某条危险链是否真的可达 | 全量字节码的调用图 + 字符串常量 + Spring 路由 | 分钟级 |

只做后者：使用者每换一个文件都要等几分钟，绝大多数时候结论在第一步就已经明确。
只做前者：所有结论都停在「可能受影响」，无法判断链是否可达。

因此把两者**分层**：本地依赖分析（下称 C）永远先跑，秒级出结论；
调用链分析（下称 B）由使用者在需要时显式触发，结果落成 SQLite 数据库供反复查询。
反编译是第三层，只在「这条规则到底成不成立」需要看源码时用。

三层共用一个输入（同一个 jar / 目录），因此合成一页而不是三个二级菜单：
使用路径本来就是「先秒级出结论 → 需要时再深挖 → 对可疑点定点看源码」，
拆开会导致每换一步都要重新选一次文件。

### 1.1 C 方案的数据来源与优先级

单个 jar 里可能同时存在多份坐标声明，可信度完全不同，因此**按来源分层采信**，
并且把来源一路带到结论里（`Dependency.Source`）：

| 优先级 | 来源 | 为什么 |
| --- | --- | --- |
| 1 | `META-INF/maven/**/pom.properties` | 打包时由 Maven 写进去的**事实** |
| 2 | `META-INF/maven/**/pom.xml` | 同样是打包产物，但可能被 shade 改写 |
| 3 | `META-INF/MANIFEST.MF`（`Implementation-Version` 等） | 由打包插件写入，粒度粗 |
| 4 | 文件名推断 | **只是推测**：`fastjson-1.2.24.jar` / `fastjson.jar` / `com.alibaba.fastjson-1.2.24.jar` 都常见 |

为什么以 pom.properties 为主而不是文件名：这个功能的**全部结论都建立在版本之上**，
靠文件名猜版本必然出错，而出错的表现是「结论看起来正常但判定相反」，界面上看不出异常。

读取全部走 `JarFile` 的条目枚举：不解压、不落盘、**不加载任何类**。
目标 jar 可能是任意来源的产物，解析期间不得让它获得执行机会。

### 1.2 踩过的坑（都是静默错误）

**坑 1：`pom.properties` 的键大小写。**
读取时为了兼容大小写不一致的打包工具，把键统一转成小写再存入，
取值却用驼峰常量 `groupId` —— 于是**永远取不到值**，整份清单静默退化成
「按文件名推断」，版本判定随之全部失真。修法是小写常量，并在常量上注明原因。

**坑 2：版本比较的缺段语义。**
原本把缺失段按数字 `0` 补齐，于是 `1.0-rc1` 被判成**大于** `1.0`：预发布版倒挂。
修法引入 `MISSING` 哨兵对象——缺段对数字段按 0 看待，对字符串段（`rc1` / `beta2`）
视为「正式发布」，即比任何预发布标记都大。同时修正了比较方向（自检抓出）。

实测：

| 表达式 | 结果 | 说明 |
| --- | --- | --- |
| `1.2.80` vs `1.2.9` | `>` | 逐段比较，不能按字符串比 |
| `1.2` vs `1.2.0` | `=` | 缺段按 0 补齐 |
| `1.0-rc1` vs `1.0` | `<` | 预发布小于正式发布 |
| `1.0-beta2` vs `1.0-rc1` | `<` | 预发布之间按标记字符串比 |
| `""` / `null` vs `1.0` | 不可比 | 返回 `null`，规则一律不匹配 |

**坑 3：groupId 缺失时的匹配取舍。**
只按 artifactId 匹配会把 fork 或同名包误判成受影响的组件；
但直接拒绝匹配又会让「只有文件名来源」的依赖永远判不出结论。
取舍：groupId **存在时必须核对前缀**（同名 artifact 在不同 group 下可能是完全不同的库），
缺失时放行但把结论可信度降到 `低`，由 `Finding.Confidence` 明确告知使用者
「这条判定建立在推断之上」。

`src/analyzer/PomScanner.java` 走 DOM 解析并**关掉 XXE**，只取顶层 `<dependencies>`，
跳过 `dependencyManagement` 与 `profiles`——后者是「声明但未生效」的依赖，
混进来会产生大量假阳性。报告里因此明确写出「声明不等于生效」。

---

## 二、规则表：25 条，自己写

`src/analyzer/VulnerabilityRules.java` 的规则**是我们自己写的**，不是从上游工具拷来的：
`jar-analyzer` 是 GPLv3，把它的规则文件搬进本仓库会带来许可问题。
规则内容取自各组件公开的受影响版本公告，且只保留「本工具能接上后续动作」的那些
——判定出来却无处可去，对使用者没有价值。

每条规则必须能回答三个问题：**依据什么版本判定**、**成立的前提是什么**、
**接下来在本工具里做什么**。答不上来的不进表。

版本区间只支持三种形态，足够表达已知公告：

| `Bound` | 语义 | 例子 |
| --- | --- | --- |
| `BELOW` | `< upper` | shiro `< 1.11.0` |
| `AT_MOST` | `<= upper` | fastjson `<= 1.2.24`（该版本本身受影响） |
| `RANGE` | `lower <= v <= upper` | log4j-core `2.0-beta9 ~ 2.14.1` |

25 条规则分布：

| 分类 | 条数 | 规则 id |
| --- | --- | --- |
| fastjson | 7 | `FJ-AUTOTYPE-124`、`FJ-AUTOTYPE-141`、`FJ-AUTOTYPE-142`、`FJ-AUTOTYPE-143`、`FJ-AUTOTYPE-168`、`FJ-AUTOTYPE-180`、`FJ2-AUTOTYPE` |
| Shiro | 4 | `SHIRO-REMEMBERME`、`SHIRO-REMEMBERME-KEY`、`SHIRO-PATHBYPASS-17510`、`SHIRO-PATHBYPASS-32532` |
| gadget 依赖 | 7 | `CC3-GADGET`、`CC4-GADGET`、`GROOVY-GADGET`、`ROME-GADGET`、`BEANUTILS-GADGET`、`C3P0-GADGET`、`HUTOOL-GADGET` |
| 组件 RCE | 7 | `LOG4J2-JNDI`、`LOG4J2-JNDI-2150`、`SPRING4SHELL`、`XSTREAM-RCE`、`SNAKEYAML-RCE`、`JACKSON-DATABIND`、`MYSQL-FAKESERVER` |

fastjson 的 6 条按 autoType 的绕过方式分段（`<=1.2.24` 默认开启；
`1.2.25~1.2.41` 用 `L` 前缀；`1.2.42` 双写 `LL`；
`1.2.43~1.2.47` 用 `[` 前缀与 TypeUtils 缓存；`1.2.48~1.2.68` 走
Throwable / AutoCloseable；`1.2.69~1.2.80` 需要目标另有可用 gadget 依赖）。
分段的依据是**绕过手法不同**，不是版本号好看。

规则命中的结论按可信度排序展示，报告末尾固定带一句「不代表没有漏洞」的免责说明：
规则表覆盖率有限，没命中不等于安全。

---

## 三、B 方案：外部引擎 + Python 只读查询

### 3.1 为什么引擎要用户自备

`jar-analyzer/jar-analyzer-engine`（MIT）是 CLI，产出单文件 `jar-analyzer.db`。
它**没有把 `org/sqlite` 打进产物**，因此不能当库引用；本仓库又禁止新增第三方依赖。
所以设计成「用户在配置页填引擎 jar 路径 → 本工具用 `java -jar` 调它 →
用 Python 标准库 `sqlite3` 读产物」。留空则只用 C 方案，功能不会失效。

### 3.2 引擎的三处实测约束（都已处理）

| 约束 | 处理 |
| --- | --- |
| 固定把 `jar-analyzer.db` 写到**工作目录**，没有输出路径参数 | `EngineRunner.build` 显式接收 `workDir`，产物路径由本工具算出来，不猜 |
| 构建大型 jar 是**分钟级** | 超时上限进配置页（默认 300 秒），超时 `destroyForcibly` **杀进程树**并清临时目录 |
| 构建期产生 `jar-analyzer-temp` 临时目录 | 分析完成后 `cleanTemp` 清掉，不留给用户收拾 |

超时低于下限（30 秒）时提前拦下并说明原因，而不是让用户等一个注定失败的任务。

### 3.3 查询为什么走 Python

SQLite 是文件格式而不是 JDK 能力，要读它就得引 JDBC 驱动，与「禁止新增依赖」冲突。
Python 标准库自带 `sqlite3`，且本项目的探测引擎本来就是
「Java 收集参数 → Python 执行」，这条既有通道正好承接查询。

`python/jar_report.py` 只做两件事：把查询名映射到 SQL、把结果渲染成可读文本。
连接一律 `mode=ro`（只读），**绝不写库**；缺表时给可读提示而不是抛栈。

Java 侧 `src/analyzer/ReportReader.java` 只把「哪张表、要什么」翻译成命令行，
**不在 Java 侧复制一份 SQL**——否则两处 SQL 迟早不一致。

### 3.4 sink 清单为什么自己写

上游的 sink 规则（`dfs-sink.json`）与 SCA 数据都在 GUI 侧，**不在 engine 里**，
且 GUI 是 GPLv3。因此 `python/jar_report.py` 的 `SINKS` 是**我们自己写的** 26 项
（命令执行 3 / 代码执行 2 / JNDI 2 / 反序列化 10 / SQL 3 / 文件 4 / SSRF 2），
收录标准同样是「命中后在本工具里能接上后续动作」。

### 3.5 五类查询

| 标识 | 界面名 | 用途 |
| --- | --- | --- |
| `summary` | 总览 | 类 / 方法 / 调用边数量，先确认库构建成功 |
| `entries` | 入口点 | Spring Controller / Servlet / Filter / Listener |
| `sinks` | Sink 命中 | 按内置 sink 清单匹配危险调用 |
| `strings` | 字符串常量 | 检索 SQL、URL、密钥等敏感信息（唯一用关键字的一类） |
| `components` | 组件清单 | 引擎侧口径的组件与版本，可与 C 的结果对照 |

---

## 四、反编译：内置 CFR，零新增依赖

实测 `lib/java-chains-cli-2.0.0-beta4.jar` 已含 **CFR**（`org/benf/cfr`）与 **ASM**
（`org/objectweb/asm`，`Opcodes.ASM9`）。直接调用等于：

- **零新增依赖**（`src/pom.xml` 未动依赖段）；
- **零额外进程**（不另起 Node）；
- 实测 84 个类整包反编译 **3.5 秒**。

外部反编译器（`jar-analyzer/jsd`，MIT，npm `@jar-analyzer/jsd@1.2.0`，产物 276KB，
需 Node）只有在「本机没有 JVM」这类场景才有优势，因此**只保留命令行拼装能力
（`AnalyzeCommand.nodeDecompile`）作为备选，不作为默认路径**，也不释放资源进 JAR。

### 4.1 一个只在 Maven 构建下现形的缺陷

`Process#descendants()` 是 Java 9 才加入的 API，而 `src/pom.xml` 的
`maven.compiler.release` 是 **8**。七套自检此前**全绿**——因为它们用的 `javac` 命令
没有 `--release 8`，默认按 JDK 17 编译，9+ API 照样通过；只有 `build.ps1` 会报
「找不到符号」。**错误在最后一刻才现形，而且看起来像构建环境问题。**

修法两层：

1. `src/analyzer/ProcessTree.java`：反射调用 `Process#descendants()` 与
   `ProcessHandle#destroyForcibly()`，**按运行期可用性降级**——
   Java 9+ 收整棵树，Java 8 只强杀父进程（Java 8 本来就没有等价能力，降级是唯一诚实的做法）。
2. `tests/test_java8_source_level.py`：把「不许用 9+ API 与语法」变成可机械判定的断言，
   扫描前剥离注释与字符串，因此文档里提到 `List.of` 之类的说明文字不会误报；
   已用变体验证它能抓到原缺陷。

### 4.2 实现细节

- 指定类名反编译时把 jar 加进 CFR 的 `extraclasspath`，
  否则被反编译的类引用到同包其它类时 CFR 解析不到父类 / 接口，只能输出残缺源码。
  （CFR 的 `Builder` **没有** `withClassPath`，只能用 `extraclasspath` 选项。）
- CFR 是**同步 API，无法中途打断**。因此超时在这里不是「中断」而是「完成后判定」：
  超出预算时结果被标注但仍保留产物，由使用者决定是否丢弃。这是已知局限。

反编译产物**只写不读**：输出到独立目录供使用者用编辑器打开，本工具不去解析这些源码。

---

## 五、模块边界

| 包 | 定位 | 出边 |
| --- | --- | --- |
| `src/analyzer/` | 分析内核：读文件 / 跑规则 / 调外部程序 | **无**（叶子层） |
| `src/analyze/` | 编排层：把内核能力按使用场景串起来 | 只依赖 `analyzer` |
| `src/ui/AnalyzePage.java`、`AnalyzeController.java` | 视图与行为 | 走既有的 `*Page` + `*Controller` 三段式 |

`analyzer` 作为叶子层有一个实际好处：自检可以直接驱动它
（`AnalyzeCheck` 61 条断言全部不经过界面），也能被界面之外的入口复用。

内核类清单：

| 类 | 职责 |
| --- | --- |
| `Dependency` | 坐标 + 来源枚举 + 去重键 |
| `DependencyScanner` | pom.properties → pom.xml → MANIFEST → 文件名推断 |
| `PomScanner` | 工程 pom.xml 的 DOM 解析（关 XXE，只取顶层 dependencies） |
| `Version` | 逐段比较，`MISSING` 哨兵 |
| `VulnerabilityRules` | 25 条规则与区间判定 |
| `Finding` / `VulnerabilityAnalyzer` | 结论、可信度、证据、渲染 |
| `EngineRunner` | 外部引擎调用、工作目录、超时杀树、清临时目录 |
| `ProcessTree` | 进程树终止：反射调用 `Process#descendants()`，按运行期可用性降级 |
| `ReportReader` | 查询枚举，只映射不复制 SQL |
| `ScriptRunner` | 释放 JAR 内脚本、取 Python 解释器、带超时执行 |
| `Decompiler` | CFR 封装 |

---

## 六、本设计不改动哪些既有行为

- Fastjson 探测、代理抓包、抓包转换、Shiro 利用：完全不变。
- `src/config/**`、`src/util/**`：只读；配置键通过既有的
  「控件 → 分组 → 读写映射三处相邻」方式新增，未改共享内核。
- `src/pom.xml`：只在 `<resources>` 的 `python` 目录 includes 里加了一行
  `jar_report.py`，**未新增任何依赖**。
- 既有七套 Java 自检与 Python 单测：断言只增不减，全部保持通过。

## 七、已知局限（如实记录）

- 规则表只覆盖上面 25 条；上限是「本工具能接上后续动作」的那部分组件。
- B 方案需要使用者自备 `jar-analyzer-engine` 的 jar；未配置时该页只跑 C 方案。
- 反编译超预算时**无法中途打断**（CFR 为同步 API），只能完成后标注。
- 源码级别门禁是显式黑名单（20 条正则），不是全量 API 比对：能挡住已知高频 9+ API，
  但不保证穷尽。
- `ProcessTree` 在 Java 8 运行时只能强杀父进程，收不掉孙进程。
- 未实测真实的 `jar-analyzer-engine` 端到端（本机未持有该 jar），
  因此引擎调用路径的验证止于「命令行拼装 + 失败路径 + 数据库查询」三层，
  数据库查询用模拟库（`tests/test_jar_report.py` 的 14 张表子集）覆盖。
- `jsd` 仅保留命令行拼装能力，未接入界面。