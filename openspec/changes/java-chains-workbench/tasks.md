# Tasks

统一说明：

- 写入域是本次允许改动的路径清单，超出即越界，须先停下来说明方案。
- Java 自检的公共运行方式（本仓库既有惯例）：先 `.uild.ps1` 产出 `target\classes` 与
  `lib\java-chains-cli-2.0.0-beta4.jar`，再 `javac` 编译 `tests\*.java` 到 `target\tmp2`，
  最后用 `-cp "target\tmp2;target\classes;lib\java-chains-cli-2.0.0-beta4.jar"` 运行，
  并带上两个 `--add-opens java.xml/...xalan...=ALL-UNNAMED`（`run.ps1` 已内置）。

## 1. 修复载荷双重编码缺陷

写入域：`src/payload/**`（角色：exploit）

- [x] 1.1 先写根因分析（见 design.md 第一节）：`buildRaw` 把文本形态产物按 UTF-8 转成字节，
  导致 `build` 的字节分支恒真，`PayloadResult.ok` 对已是 Base64 的文本又编码一次；
  验证：根因段落明确写出「哪一行导致哪一步走错」且不依赖试错
- [x] 1.2 `build` 对文本形态产物直接走文本入口，不再经过字节分支；
  验证：`ShiroCheck` 的「生成的 payload 可被本机密钥解密」通过
- [x] 1.3 `buildRaw` 的文本转字节改为「先 Base64 解码、失败再按 UTF-8」，
  使其与文本结果的字节语义一致；验证：`PayloadCheck` 双形态往返断言通过
- [x] 1.4 载体无任何可交付产物时返回带原因的失败结论，不得返回成功；
  验证：`PayloadCheck` 失败路径断言通过

## 2. 恶意服务器：服务端适配层

写入域：`src/service/**`（角色：exploit）

- [x] 2.1 新增服务描述（纯数据）：五类服务的标识、标题、说明与端口项；
  可选端口标记为「未填写则不参与启动」；验证：编译通过，服务页列出五项
- [x] 2.2 新增服务状态与发布结果（纯数据）：状态、绑定地址与端口、已发布条数、最近错误；
  验证：服务页状态行渲染五项
- [x] 2.3 新增服务端适配收敛层：本项目唯一引用上游服务端类型的类；
  启动、停止、状态查询、载荷发布、退出前统一停止；
  非 JNDI 服务使用主端口键，JNDI 使用各协议端口键；
  验证：端到端实测五类服务可启停、四类可发布、LDAP 可被真实取回
- [x] 2.4 发布时按协议挑选载荷类型，类型不符时给出可读原因；
  验证：实测 JNDI / FakeMySQL 拒收文本类载荷时返回失败而不是成功
- [x] 2.5 按协议补出可复制的回连地址（JNDI 给出各协议入口）；
  验证：服务页发布后地址可直接复制
- [x] 2.6 新增默认监听参数（纯数据）：绑定地址、公布地址、端口覆盖值，
  端口键用「服务标识.端口键」复合形式；验证：端口初值来自配置的断言通过

## 3. 预设链：读取层

写入域：`src/preset/**`（角色：exploit）

- [x] 3.1 新增预设纯数据：标识、名称、分类、说明、标签、可用载体、链步骤与可填输入；
  验证：编译通过
- [x] 3.2 新增预设读取层：本项目唯一引用上游预设模型的类，
  读取失败返回空清单而不是抛异常，并单独提供失败原因；验证：读取到 52 条内置预设

## 4. 界面：恶意服务器页与预设链页

写入域：`src/ui/**`、`src/Main.java`（角色：ui）

- [x] 4.1 新增服务页视图（`*Page` 三段式，只搭结构不持状态）；
  验证：`UiNavigationCheck` 的服务页控件断言通过
- [x] 4.2 新增服务页行为：启停、发布、复制地址、填入抓包页、退出前统一停止；
  验证：界面自检通过
- [x] 4.3 新增预设链页视图与行为：分类筛选、步骤渲染、输入渲染、生成、复制、
  发到恶意服务器、填入抓包页；验证：预设链端到端生成断言通过
- [x] 4.4 抽出两个页面共用的链编辑状态，消除重复实现；
  验证：两页的链规则一致，`UiNavigationCheck` / `PayloadCheck` 通过
- [x] 4.5 侧边栏重组为网页版顺序（主页 / Payload / 服务 / 代理 / FastJson / 配置）；
  验证：`UiNavigationCheck` 导航断言通过
- [x] 4.6 主窗口默认最大化，最小尺寸保证四块区域可用；
  验证：界面自检运行期间窗口即为最大化
- [x] 4.7 配置页新增「恶意服务器配置」（绑定地址、公布地址、七类端口）与
  「预设链配置」（默认分类）；验证：配置页分组断言与端口初值断言通过
- [x] 4.8 配置保存后重新下发到已存在的控制器，无需重开程序；
  验证：实测改配置保存后服务页端口随之变化
- [x] 4.9 关闭窗口时先停止全部服务再退出；
  验证：退出后端口不再被占用

## 5. 依赖边界与角色矩阵同步

写入域：`tests/test_decoupling.py`（测试）、`tools/**`、`docs/**`（主 agent）

- [x] 5.1 允许边集合新增 `service`（只依赖 `payload`）与 `preset`（不依赖任何项目包）；
  验证：`python -m unittest discover -s tests` 99 项全绿
- [x] 5.2 叶子层集合纳入 `preset`，通用组件断言范围纳入本次新增类名；
  验证：`python tools\audit_boundary.py` 结论为「全部通过」
- [x] 5.3 规则文件的两份副本保持一致；验证：两份文件按同一份规则判定同一份源码
- [x] 5.4 角色矩阵把 `src/service/*` 与 `src/preset/*` 归入功能开发 exploit 写入域，
  并同步 `docs/AGENT-ROLES.md`、`docs/DESIGN-agents.md`、`tools/agent.ps1` 与中英 README；
  验证：`tools\check_agent_tools.ps1` 91 项全绿

## 6. 界面自检

写入域：`tests/**`（角色：测试）

- [x] 6.1 导航断言改为按 `NAV_ITEMS` 的展开状态现算项数，
  不再写死数字与索引；验证：`UiNavigationCheck` 通过且调整导航顺序后无需改断言
- [x] 6.2 新增预设链页断言：控件、清单已载入、端到端生成出 Base64；
  验证：`UiNavigationCheck` 通过
- [x] 6.3 新增服务页断言：清单五项、端口容器、按钮、输出只读、未启动时停止不可用、
  端口初值来自配置；验证：`UiNavigationCheck` 通过
- [x] 6.4 新增配置页断言：两个新分组、地址与端口输入框、默认值与上游一致、预设分类下拉框；
  验证：`UiNavigationCheck` 通过
- [x] 6.5 前置断言明示 `--add-opens` 依赖，缺参数时能一眼看出原因而不是报「生成失败」；
  验证：缺参数运行时报出该条断言

## 7. 回归验证

写入域：无（只运行命令与比对输出；如发现缺陷，回到对应写入域的任务修复）

- [x] 7.1 六套 Java 自检全绿；验证：`ShiroCheck` / `PayloadCheck` / `ProxyServerCheck` /
  `UiNavigationCheck` / `UiShiroCheck` / `UiSwitchEndToEndCheck` 退出码均为 0
- [x] 7.2 Python 回归全绿；验证：`python -X utf8 -m unittest discover -s tests` 99 项 OK
- [x] 7.3 依赖边界审计通过；验证：`python -X utf8 tools\audit_boundary.py` 结论「全部通过」
- [x] 7.4 工具链自检通过；验证：`tools\check_agent_tools.ps1` 91 项通过
- [x] 7.5 规划件合法；验证：`openspec validate --all --strict` 5 项全 passed
- [x] 7.6 依赖合规：`src/pom.xml` 未新增依赖，未引入 Spring 或任何新第三方库；
  验证：`git diff --name-only` 中不出现 `src/pom.xml`

## 8. 收尾三项（主 agent 在合并后统一执行）

写入域：`.backups/**`、`AI_REPORT.md`、`PROGRESS.md` 与构建产物（仅主 agent）

- [ ] 8.1 备份本次改动，且 `.backups/` 只保留最近三次快照（超出部分轮转删除）；
  验证：`Get-ChildItem .backups` 的时间戳目录数不超过 3，且包含本次时间戳
- [ ] 8.2 追加 `AI_REPORT.md` 本轮章节（改动内容、实测数字、未解决项与已知局限）
  并更新 `PROGRESS.md`；验证：两个文件都出现本次记录，且含实际命令与结论
- [ ] 8.3 重新构建并校验产物时间；验证：`.\build.ps1` 退出码 0，
  JAR 的 `LastWriteTime` 晚于 `src/`、`python/`、`tests/` 下全部源文件
- [ ] 8.4 文档同步：`README.md` 与 `README.en.md` 新增恶意服务器与预设链章节、
  配置页分组、导航说明、项目结构，并保持中英一一对应；
  验证：两份 README 的章节与表格行数一致，无单边内容

## 9. 监督复核

写入域：无（只读）

- [ ] 9.1 按 `docs/AGENT-ROLES.md` 的十项清单独立复核，不引用实现者结论：
  越界改动、空承诺、断言放宽、依赖合规、构建一致性、文档同步、配置落页、测试真实性；
  验证：复核结论写入 `AI_REPORT.md` 本轮章节，每项给出文件路径与行号
- [ ] 9.2 独立复算依赖边界与 JAR 时间（自己跑命令，不看实现者输出）；
  验证：`python -m unittest discover -s tests` 与 `.\build.ps1` 由复核者亲自执行并记录输出
