# Design: toString 链生成页

## 一、Root Cause：模板此前为什么一条都生成不出来

**现象**：`ToStringPreset.templates()` 里的 5 条模板，在本机逐条构建时全部失败，
报「链不被引擎认可」，界面上一旦接上就会表现为「点生成就失败」。

**成因**：模板漏掉了链的**第一个 gadget**——toString 触发节点。实测该载体
（`javanativepayload`）的首节点里确实含这些触发节点，但模板直接从「中继节点」
（`jacksontostring` / `fastjsontostring1`）写起，于是引擎拿到的链是
`... → jacksontostring → templatesimpl → ...`，缺了触发段。

实测证据（本机 JDK 17，附 run.ps1 的两个 `--add-opens`）：

```
补上触发节点前：五种模板 valid=false，错误信息为「链不被引擎认可」
                      javanativepayload -> jacksontostring -> templatesimpl -> bytecodeconvert -> exec
补上触发节点后：ts.cc3.jackson            valid=true build=true len=1555
                ts.cc4.jackson            valid=true build=true len=1552
                ts.cc3.fastjson           valid=true build=true len=1468
                ts.eventlistener.jackson  valid=true build=true len=2031
                ts.gstring.jackson        valid=true build=true len=1819
```

**改法**：模板显式记录 `trigger` 字段，并由它拼出完整 gadget 序列；构建入口不变。

## 二、模板取舍：为什么只给 5 条

逐条实测过「能否作为触发」「能否作为中继」，结论决定收录与排除：

| 节点 | 作为触发 | 作为中继 | 处置 |
| --- | --- | --- | --- |
| `caseinsensitivemap3tostring` / `caseinsensitivemap4tostring` | 可用 | — | 收录（CC3 / CC4） |
| `eventlistenerlisttostring` | 可用 | — | 收录（JDK 内置） |
| `gstringcomparetotostring` | 可用 | — | 收录（Groovy） |
| `jacksontostring` | 不可用 | 可用 | 只作中继 |
| `fastjsontostring1` | 不可用 | 可用 | 只作中继 |
| `fastjsontostring2`、`rometostringbean1/2` | 不可用 | 可用 | 不收录（中继已有更通用选择） |
| `xbeantostring` | 不可用 | 不可用 | 不收录 |
| `xstringtostring1..3`、`xalanxstringtostring1..3` | 不构成合法链 | — | 不收录 |
| `badattributevalueexpexceptiontostring` | 合法但构建抛异常 | — | 不收录（`IllegalArgumentException: Can not set java.lang.String field ... val`） |
| 带 `HighJDK` 后缀 / `textandmnemonic*` | 需额外 `--add-opens java.io`、`java.util` | — | 不收录（run.ps1 只有两个 xalan opens） |

「宁可少给也不给点了就报错的模板」是本页的取舍原则：模板的作用是让使用者改两个输入就出载荷，
收录一条会报错的模板等于把排查成本转嫁给使用者。

## 三、「移动」的边界：为什么过滤放在列而不放在编辑器

`src/ui/ChainEditor.java` 由 Payload 生成页与恶意服务器页**共用**。若在编辑器层过滤
toString 触发节点，恶意服务器页也会一并失去发布这类载荷的能力——而本次并未要求改动服务页，
这属于越界。

因此过滤只落在生成页专用的两处：

- `PayloadColumns.of(...)`：逐级候选列与末列；
- `PayloadController.candidates()`：状态栏与链信息行的候选计数（不跟着过滤会出现
  「写着还可选择、列里却没有」的自相矛盾）。

**只过滤候选，不过滤载体，也不改动链的合法性判定**：链是否成立仍由引擎决定。

## 四、边界：本设计不改变哪些既有行为

- `PayloadEngine` 的公开方法签名与语义不变；`ChainEditor` 的候选计算恢复为原样；
- 预设链页、抓包页、探测页、代理页、Shiro 页与恶意服务器页一律不动；
- 配置页既有分组与配置键不动，只在末尾新增一个分组；
- 载荷的构建入口仍是 `PayloadEngine.build`，本页不另写一套构建逻辑。

## 五、失败路径的可观测结论（不静默）

| 情况 | 结论 |
| --- | --- |
| 未选择模板 | `请选择一条 toString 链模板。` |
| 引擎判链不合法 | 输出「生成失败：<引擎原因>」，状态栏给出同一原因 |
| 构建期抛异常（类名非法、依赖缺失） | 输出「生成失败：<异常类型>：<原因>」，不冒到事件分发线程 |
| 尚未生成就复制 / 填入抓包页 | 提示先选择模板 / 先生成载荷 |

## 六、界面结构

沿用仓库既有的「视图 `*Page` + 行为 `*Controller`」两段式：

- 视图：`PayloadToStringPage`（`Widgets` + `defaults()` + `build()`），行为类不 new 任何控件；
- 行为：`PayloadToStringController` 负责渲染步骤、后台线程构建、复制、填入抓包页；
- 构建与渲染在后台线程完成后经 `SwingUtilities.invokeLater` 回填，避免构建期卡界面。
