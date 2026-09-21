# Tasks

统一说明：

- 写入域是本次允许改动的路径清单，超出即越界，须先停下来说明方案。
- Java 自检的公共运行方式（本仓库既有惯例）：先 `.\build.ps1` 产出 `target\classes`，
  再 `javac` 编译 `tests\*.java` 到 `target\tmp2`，
  最后以 `-cp "target\tmp2;target\classes;lib\java-chains-cli-2.0.0-beta4.jar"` 运行，
  并带上两个 `--add-opens java.xml/...xalan...=ALL-UNNAMED`（`run.ps1` 已内置）。

## 1. 载荷引擎：节点显示名只读查询

写入域：`src/payload/**`（角色：exploit）

- [x] 1.1 新增节点显示名查询：按节点标识返回引擎登记的显示名，标识为空、节点未知或显示名为空时返回空串；
  验证：`PayloadCheck` 新增的「未知节点显示名为空」断言通过
- [x] 1.2 显示名查询不得改动引擎状态：连续查询两次结果一致，且查询前后可选节点目录与参数声明不变；
  验证：`PayloadCheck` 新增的「显示名查询无副作用」断言通过
- [x] 1.3 既有 10 个公开方法的签名与语义零改动；
  验证：`git diff` 中 `src/payload/PayloadEngine.java` 的既有方法体无修改，`PayloadCheck` 既有 61 条断言全绿

## 2. 界面：列式链选择器

写入域：`src/ui/PayloadChainSelector.java`（角色：ui）

- [x] 2.1 新增列式选择器视图组件：横向排列的列，每列含标题、关键字过滤框与候选列表；
  候选为 0 时不渲染该列；验证：编译通过，且页面上第一列列出 28 个载体
- [x] 2.2 组件不持有链状态：候选与选中项全部由调用方按列传入，组件只负责渲染与把点击转成回调；
  验证：类内不出现链状态字段，也不出现 `new PayloadEngine` 一类引擎调用
- [x] 2.3 组件不创建可持久化配置、不读写文件；验证：类内不出现 `Files` / `AppConfig` 引用
- [x] 2.4 列宽固定并支持横向滚动：列数超过可视宽度时可滚动到最后一列；
  验证：界面自检的「最后一列在可视区域内」断言通过

## 3. 界面：Payload 页接上列式交互

写入域：`src/ui/PayloadPage.java`、`src/ui/PayloadController.java`（角色：ui）

- [x] 3.1 删除 `载体分组` 与 `载荷载体` 两个下拉框；载体并入第一列；
  验证：`UiNavigationCheck` 的「Payload 页不再有分组与载体下拉框」断言通过
- [x] 3.2 删除 `追加节点` 下拉框与 `追加节点` 按钮；追加动作改由点击候选承担；
  验证：`UiNavigationCheck` 的「Payload 页不再有追加节点下拉框与按钮」断言通过
- [x] 3.3 保留 `删除末节点`、`清空链`、只读链文本与全部既有按钮（生成 / 复制 / 导出 / 填入抓包页）；
  验证：`UiNavigationCheck` 的控件断言通过
- [x] 3.4 控制器按 `ChainEditor` 现算每列候选：第 0 列取载体目录，第 K 列取第 K-1 项的候选；
  点击第 N 列候选即截断后重算；验证：`UiNavigationCheck` 的「点第二列换一项后链只剩两项」断言通过
- [x] 3.5 关键字过滤只影响该列渲染，且选中项始终可见；
  验证：`UiNavigationCheck` 的「过滤后候选收敛」「清空关键字后恢复」「选中项不被过滤掉」三条断言通过
- [x] 3.6 参数区随链重建的既有行为保持不变，追加节点后必填参数仍会渲染；
  验证：`UiNavigationCheck` 的「链上节点的必填参数已渲染」断言通过
- [x] 3.7 端到端仍可生成：列式选链选中 `clojure` 后点生成，输出含 Base64 与摘要；
  验证：`UiNavigationCheck` 的生成断言通过

## 4. 自检与门面同步

写入域：`tests/**`（角色：test）

- [x] 4.1 `PayloadCheck` 新增节点显示名断言（未知节点为空、无副作用、已知节点非空）；
  验证：`PayloadCheck` 退出码 0，断言数由 61 增至 64
- [x] 4.2 `UiNavigationCheck` 的 Payload 页断言同步为列式交互：控件增删、列数、点列重开、过滤；
  验证：`UiNavigationCheck` 退出码 0
- [x] 4.3 `WidgetRegistry` 的 Payload 控件登记随控件清单同步（存在 `UiHandle` 未登记的控件会让自检报错）；
  验证：`UiNavigationCheck` 不出现「未登记的控件」

## 5. 回归验证

写入域：无（只运行命令与比对输出；发现缺陷回到对应写入域修复）

- [x] 5.1 六套 Java 自检全绿；
  验证：`ProxyServerCheck` / `ShiroCheck` / `PayloadCheck` / `UiShiroCheck` / `UiNavigationCheck` /
  `UiSwitchEndToEndCheck` 退出码均为 0
- [x] 5.2 Python 回归全绿；验证：`python -X utf8 -m unittest discover -s tests` 全部 OK
- [x] 5.3 依赖边界审计通过；验证：`python -X utf8 tools\audit_boundary.py` 结论「全部通过」
- [x] 5.4 规划件合法；验证：`openspec validate --all --strict` 全部 passed
- [x] 5.5 依赖合规：`src/pom.xml` 未新增依赖；验证：`git diff --name-only` 中不出现 `src/pom.xml`

## 6. 收尾三项（主 agent 在合并后统一执行）

写入域：`.backups/**`、`AI_REPORT.md`、`PROGRESS.md`、`docs/**`、`README*.md` 与构建产物（仅主 agent）

- [x] 6.1 备份本次改动，且 `.backups/` 只保留最近三次快照；
  验证：`Get-ChildItem .backups` 的时间戳目录数不超过 3，且包含本次时间戳
- [x] 6.2 追加 `AI_REPORT.md` 本轮章节并更新 `PROGRESS.md`；
  验证：两个文件都出现本次记录，且含实际命令与结论
- [x] 6.3 重新构建并校验产物时间；验证：`.\build.ps1` 退出码 0，
  JAR 的 `LastWriteTime` 晚于 `src/`、`python/`、`tests/` 下全部源文件
- [x] 6.4 文档同步：`README.md` 与 `README.en.md` 的 Payload 生成章节改为列式交互说明、
  `docs/DESIGN-payload.md` 补交互说明与实测差异表；验证：两份 README 章节一一对应，无单边内容

## 7. 监督复核

写入域：无（只读）

- [x] 7.1 按 `docs/AGENT-ROLES.md` 的清单独立复核：越界改动、空承诺、断言放宽、依赖合规、
  构建一致性、文档同步、配置落页、测试真实性；验证：复核结论写入 `AI_REPORT.md` 本轮章节
- [x] 7.2 独立复算：自己跑 `PayloadCheck`、`UiNavigationCheck`、`python -m unittest discover -s tests`
  与 `.\build.ps1`，不看实现者输出；验证：四项输出均记录在报告中
