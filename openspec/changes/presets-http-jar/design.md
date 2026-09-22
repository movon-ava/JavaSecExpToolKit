# Design: HTTP 带外 Jar

## 一、Root Cause：为什么不能直接用 ServiceManager

**现象**：用 `ServiceManager` 启动 HTTP 服务再发布 Jar，接口返回成功并给出地址，
但拿到的地址指向**上游默认端口 50000**，而不是实际监听的端口，URL 打不开。

**成因**：上游的 HTTP 服务在发布载荷时按请求回落到**自己绑定的端口**。如果「启动服务」
与「发布载荷」用的不是同一个 `ServiceManager` 实例（各自 new 一个），发布时的实例
并没有监听端口，于是地址按默认值拼出来。实测确认：

```
另一个实例发布 → 返回的地址是 50000，GET 打不开
同一实例发布   → 返回的地址是实际监听端口，GET 拿到合法的 PK 开头字节
```

**改法**：新增 `src/service/OobJarService.java`，把「启动」与「发布」收在**同一个实例**里，
并把这条约束封在类内——界面层不必知道它，也就不会有人误用。

## 二、产物形态：Jar 包装类型 × 末端动作

包装类型决定产物形态与加载方式，末端动作决定 Jar 被加载后做什么。实测
（本机 JDK 17，附 run.ps1 的两个 `--add-opens`）5 种类型 × 5 种动作共 25 种组合
**全部可构建，且产物均以 `PK\x03\x04` 开头**：

| Jar 类型 | 说明 |
| --- | --- |
| `jar` | 标准 JAR 包装 |
| `charsetjarconvert2` | Charset SPI，适用于 SpringBoot 写 Jar 落地 |
| `groovyjarconvert` | ASTTransformation SPI |
| `snakeyamljarconvert` | ScriptEngineFactory SPI |
| `jdbcdriverjarconvert` | java.sql.Driver SPI |

| 末端动作 | 需要的输入 |
| --- | --- |
| `downloadexec` | URL / 执行参数 / 落地路径 |
| `exec` | 命令 |
| `httpreq` | 回连 URL |
| `download` | URL / 落地路径 |
| `dnslog` | DNSLog 域名 |

**实测排除项**：`CharsetJarConvert`（旧版 Charset 包装）在本机报
`ClassNotFoundException: sun.nio.cs.ext.MyExtendedCharsets`，因此只保留可用的
`CharsetJarConvert2`。

链序固定为 `otherpayload → <包装类型> → bytecodeconvert → <末端动作>`：
中间必须有字节码转换节点，否则 Jar 里只有空的包装结构、没有可用类。

## 三、失败路径的可观测结论（不静默）

| 情况 | 结论 |
| --- | --- |
| 未选 Jar 类型或末端动作 | `请先选择 Jar 类型与末端动作。` |
| 端口非整数或越界 | `监听端口必须是 1-65535 之间的整数。` |
| 动作需要 URL 但留空 | `<动作的 URL 标签> 不能为空：<动作名> 需要该地址。` |
| 端口被占用 / 启动失败 | `托管失败：启动 HTTP 服务失败：<原因>` |
| 载荷构建失败 | `托管失败：生成载荷失败：<原因>` |
| 发布失败 | `托管失败：发布 Jar 失败：<原因>` |
| 产物不是合法 Zip | 输出里给出「警告：产物不是合法 Zip」，不静默当成功 |
| 尚未托管就复制地址 | `还没有可复制的地址，请先托管 Jar。` |
| 未托管就停止 | `停止托管失败：<原因>` |

## 四、边界：本设计不改变哪些既有行为

- 恶意服务器页的五类服务、端口与发布链路一律不动；本页使用**独立**的托管实例，
  端口默认 50001，与 HTTP 服务的 50000 错开；
- 预设链页与生成页的产出仍是载荷字节，不接入托管；
- 配置页既有分组与配置键不动，只在末尾新增一个分组；
- 退出时停止托管（`WorkbenchPages.shutdown()`）：托管一旦启动就真实占用端口，
  不释放会让下次启动撞上「端口被占用」。

## 五、界面结构

沿用仓库既有的「视图 `*Page` + 行为 `*Controller`」两段式：

- 视图：`OobJarPage`（`Widgets` + `defaults()` + `build()`），行为类不 new 任何控件；
- 行为：`OobJarController` 负责动作联动、后台线程托管、复制地址、停止托管；
- URL / 命令 / 路径三个输入框的**标签随动作变化**（同一个框在不同动作下含义不同），
  不需要的输入框置灰禁用，避免使用者填一个永远不会被下发的字段。
