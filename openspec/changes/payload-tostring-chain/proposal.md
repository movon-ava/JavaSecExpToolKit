# Proposal: toString 链生成页

## Why

toString 触发链在 java-chains 里是一类独立用法：触发节点几乎不参与参数配置，使用者真正要改的是
「以哪个类为触发点」与末端动作。本工具此前只在通用 Payload 生成页里把它们当普通 gadget 暴露，
实际使用要在一百多个候选里先挑触发节点、再逐个确认后继是否接得上，而其中若干条链（`XString` /
`XalanXString` 系列）在当前运行方式下并不成立，点下去只会拿到一句「链不被引擎认可」。

同时，`src/payload/ToStringPreset.java` 与 `src/payload/ChainScope.java` 已按实测结论落盘，
但**没有任何入口调用它们**：模板清单、触发节点归属规则都是死代码，界面上完全看不到。

## What Changes

- 新增二级项「Payload → toString 链」：左侧为实测可构建的模板清单，右侧为链步骤、
  自定义目标类与末端命令，底部输出载荷；模板可一键复制。
- 模板与节点归属规则接入界面：`ToStringPreset` 提供模板与参数，`ChainScope` 提供
  「哪些节点属于 toString 功能」的唯一判据。
- **移动**：通用 Payload 生成页的候选列不再列出 toString 触发节点（`ChainScope` 登记的 23 个），
  这些节点统一由新页提供。
- 新增可持久化配置进配置页「toString 链配置」分组：默认链模板、默认末端命令。

## Capabilities

### New Capabilities

- `payload/tostring-chain`：toString 触发链的模板生成、自定义目标类与末端动作。

### Modified Capabilities

- `payload/generate-view`：通用生成页的候选集合收窄（不再包含 toString 触发节点）。

## Non-goals

- **不改动恶意服务器页**：`ChainEditor` 由生成页与服务页共用，若在编辑器层过滤会连带砍掉
  服务页发布 toString 载荷的能力，因此过滤只放在生成页专用的 `PayloadColumns`。
- **不改动链的合法性判定**：一条链成立与否仍由引擎决定，本页只做候选的界面归属划分。
- **不新增触发节点模板**：只收录本机实测可构建的 5 条；`XString` / `XalanXString` /
  `BadAttributeValueExpException` / 带 `HighJDK` 后缀的模板实测不可用（原因见 design），
  宁可少给也不给点了就报错的模板。
- **不改动引擎与既有页面**：`PayloadEngine` 公开方法签名与语义不变，预设链页、抓包页、
  探测页、代理页、Shiro 页一律不动。
- **不引入新第三方依赖**：仍只用 Java 标准库与 java-chains。

## Impact

| 路径 | 写入域 | 动作 |
| --- | --- | --- |
| `src/payload/ToStringPreset.java` | exploit | 改：模板补上触发节点（首个 gadget） |
| `src/payload/ChainScope.java` | exploit | 已有：触发节点归属与候选过滤判据 |
| `src/ui/PayloadToStringPage.java` | ui | 新增：toString 链页视图 |
| `src/ui/PayloadToStringController.java` | ui | 新增：模板选择、生成、复制、填入抓包页 |
| `src/ui/PayloadColumns.java` | ui | 改：候选列过滤掉 toString 触发节点 |
| `src/ui/PayloadController.java` | ui | 改：状态栏与链信息行的候选计数同步过滤 |
| `src/ui/NavController.java` | ui | 改：新增「toString 链」二级项 |
| `src/ui/WorkbenchPages.java` | ui | 改：懒加载入口与默认值下发 |
| `src/ui/ConfigForm.java`、`src/ui/ConfigController.java` | ui | 改：「toString 链配置」分组与读写 |
| `src/ui/WidgetRegistry.java`、`src/Main.java` | ui | 改：登记控件、路由、装配 |
| `tests/PayloadCheck.java` | 测试 | 改：模板完备性与过滤规则断言 |
| `tests/UiNavigationCheck.java` | 测试 | 改：页面控件、配置分组、候选过滤断言 |
| `README.md`、`README.en.md`、`AI_REPORT.md`、`PROGRESS.md` | 主 agent | 改：文档同步 |
