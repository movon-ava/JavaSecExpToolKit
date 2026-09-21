# Proposal: Payload 生成页对齐 JavaChains Generate

## Why

现状（改动前实测）：`Payload 生成` 页的选链交互被拆成四个控件——

1. `载体分组` 下拉框；
2. `载荷载体` 下拉框；
3. `追加节点` 下拉框（内容随当前链末节点变化）；
4. `追加节点` 按钮。

后果是可验证的，不是主观判断：

- 每追加一个节点都要「点开下拉 → 找到候选 → 选它 → 点一次按钮」，链越长步骤越多；
- 候选规模不小：实测单个载体的首节点候选可达上百个（28 个载体全量首节点校验耗时 71 ms），
  在一个下拉框里翻找上百项既慢又看不到「这一层还能接什么」；
- 追加之后界面只回显一行链文本，看不到「上一层选了什么、下一层能选什么」，
  要改中间某一层只能靠「删除末节点」反复退。
- 分组分类是为「数量少的载体」设计的辅助（28 个载体分 7 组），
  在列式列表下没有存在必要，反而固定占掉两行垂直空间。

网页版 java-chains 的 `Generate` 页把这件事压成一次点击：左侧一个列式选择器，
第一列是载荷载体，选了载体就自动在右边展开该载体的首节点候选，
再选一项又展开下一列；点某一列的新候选即从该列重开链。使用者点几次就看到完整的链。

## What Changes

- `Payload 生成` 页的选链交互改为列式选择器：点候选即追加、自动展开下一列，
  点前面某一列的候选即从该列重开链（等价于「删掉它之后的所有节点」）。
- 每列带一个关键字过滤框：候选上百个时先过滤再点，不再要求逐个翻找。
- 载体的选择并入第一列，删除 `载体分组` 与 `载荷载体` 两个下拉框。
- 删除 `追加节点` 下拉框与 `追加节点` 按钮，追加动作由点击候选承担。
- 保留「删除末节点」与「清空链」两个基本编辑动作，保留只读的当前链文本。
- 参数区、生成、复制、导出文件、填入抓包页四个动作与既有配置键保持不变。
- 载荷引擎新增只读的节点显示名查询（列内展示可读名称，取不到时回退成标识），
  不改变任何既有方法的行为与签名。

## Capabilities

### New Capabilities

- `payload/generate-view`：载荷生成页的选链交互契约。规定候选只来自引擎、
  逐列展开、点某一列即从该列重开链、候选量大的列必须可过滤、链只能由点击构建不得手填。

### Modified Capabilities

无。既有 `payload/chain-generation` 契约（候选来自引擎、参数按节点动态提供、
非法链给出可读失败结论）保持不变，本次只改这些能力在界面上的呈现方式。

## Non-goals

- 不引入网页版 Generate 的周边复杂能力：暴力矩阵、步进调试生成、分享链、
  常用链路统计、保存预设、Tag 筛选与并集/交集匹配、输出解析（反编译 / 序列化解析）一律不做。
- 不改载荷引擎的既有行为：`payloadIds` / `nodeIds` / `firstNodes` / `nextNodes` /
  `paramsOf` / `isChainValid` / `build` / `buildRaw` 的签名与语义均不变，
  载体分组表继续为恶意服务器页服务。
- 不改预设链页：预设链仍是独立页面（`Payload → 预设链`），不并入本页。
- 不改配置键名、配置页分组与 `%USERPROFILE%\.JavaSecExpToolKit\config.properties` 的读写。
- 不引入任何第三方依赖，不改 `src/pom.xml`，不改共享内核 `src/config/**`、`src/util/**`。
- 不动 `python/fj_probe.py`、`src/proxy/**`、`src/shiro/**`、`src/service/**`、`src/preset/**`。

## Impact

| 路径 | 角色写入域 | 性质 |
| --- | --- | --- |
| `src/ui/PayloadChainSelector.java` | 界面 agent（ui） | 新增：列式链选择器视图组件 |
| `src/ui/PayloadPage.java` | 界面 agent（ui） | 表单区换列式选择器；控件清单增删 |
| `src/ui/PayloadController.java` | 界面 agent（ui） | 行为改为按列展开与按列重开 |
| `src/payload/PayloadEngine.java` | 功能开发 agent（exploit） | 新增只读的节点显示名查询 |
| `tests/UiNavigationCheck.java` | 测试 agent（test） | Payload 页断言同步为列式交互 |
| `tests/PayloadCheck.java` | 测试 agent（test） | 新增节点显示名断言 |
| `openspec/specs/payload/generate-view/spec.md` | 主 agent | 归档时写入主 spec |
| `docs/DESIGN-payload.md`、`README.md`、`README.en.md`、`PROGRESS.md`、`AI_REPORT.md` | 主 agent | 交互与结论同步 |

对既有功能的影响：恶意服务器页、预设链页、Shiro 页、抓包页与配置页的行为与控件零改动；
`PayloadCheck` 既有 61 条断言一条不改（只新增显示名断言）。

## 实测复现步骤（改动前后的差异可复现）

1. 以 `run.ps1` 启动，左侧点 `Payload → Payload 生成`。
2. 改动前：需要先选 `载体分组`、再选 `载荷载体`、再在 `追加节点` 下拉里找候选、最后点 `追加节点`；
   改中间层只能连点 `删除末节点`。
3. 改动后：第一列直接列 28 个载体，点一个即在右侧展开该载体的首节点候选；
   在第二列点一项即展开第三列；点回第二列的另一个候选即从第二列重开链。
   每列上方的过滤框输入关键字后候选即时收敛。
4. 两版都在参数区填 `Clojure.cmd` 后点 `生成载荷`，输出区得到的 Base64 与摘要一致。
