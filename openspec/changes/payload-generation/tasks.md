# Tasks

统一说明：

- 写入域是本次允许改动的路径清单，超出即越界，须先停下来说明方案。
- Java 自检的公共运行方式（本仓库既有惯例）：先 `.\build.ps1` 产出 `target\classes` 与
  `lib\java-chains-cli-2.0.0-beta4.jar`，再 `javac` 编译 `tests\*.java` 到 `target\tmp2`，
  最后用 `-cp "target\tmp2;target\classes;lib\java-chains-cli-2.0.0-beta4.jar"` 运行，
  并带上两个 `--add-opens java.xml/...xalan...=ALL-UNNAMED`（`run.ps1` 已内置）。
- 与其它 change 并发时，改为在本 worktree 内执行 `mvn -f src\pom.xml clean package`，
  不触碰根目录共享产物。

## 1. 通用包：目录与结果模型

写入域：`src/payload/**`（角色：exploit）

- [ ] 1.1 新增载体分组常量表：按用途分组（序列化、JSON 解析器、JNDI、框架专用、特殊协议、MySQL 伪装、其他），
  分组并集必须等于运行时载体目录；验证：编译通过，且第 5 组的「分组并集等于目录」用例通过
- [ ] 1.2 新增结果模型：成功标志、失败原因、Base64 文本、原始字节、字节长度、
  由「载体标识 + 节点序列 + 长度」拼成的可记录摘要，并提供成功与失败两个构造入口；
  验证：编译通过，且第 5 组的「双形态」「摘要不含正文」用例通过
- [ ] 1.3 结果模型的失败入口保证 Base64 文本与原始字节为空、长度为零，
  不得返回 null 字段；验证：第 5 组「非法输入」用例里对失败结果的字段断言通过
- [ ] 1.4 新包内的类名不得与 `src/shiro/` 既有类重名（依赖边界自检用「简单类名 → 包」索引解析引用，
  同名会互相覆盖导致扫描失真）；验证：`Get-ChildItem src\payload` 与 `src\shiro` 的类名集合交集为空

## 2. 通用包：引擎

写入域：`src/payload/**`（角色：exploit）

- [ ] 2.1 新增引擎类：初始化、就绪状态与状态信息、节点目录、载体目录、节点参数查询、后继节点查询、
  参数归一化、载荷构建（载体 + 节点序列 + 参数）；实现全部委托给既有 java-chains 依赖，不新写第二套链逻辑；
  验证：编译通过，且第 5 组 2.1–2.4、3.1–3.2 用例通过
- [ ] 2.2 引擎只依赖共享内核（`util`）与第三方依赖，不引用 `shiro` / `probe` / `proxy` / `ui` 中任何类型；
  验证：`python -m unittest tests.test_decoupling` 中的「载荷生成组件可独立复用」用例通过
- [ ] 2.3 初始化失败时状态信息给出可操作原因（缺 `--add-opens` 时提示使用 `run.ps1`），
  且此时构建请求返回失败结论而不是成功；验证：第 5 组「引擎未就绪不得报告成功」用例通过
- [ ] 2.4 默认参数里不内置任何公网回连地址：JNDI 一类载体的主机与 URL 默认为空字符串；
  验证：第 5 组「默认回连地址为空」用例通过
- [ ] 2.5 全部构建动作在本地内存完成，不写文件、不发网络请求；验证：第 5 组「生成不写文件」用例通过

## 3. Shiro 侧改为委派

写入域：`src/shiro/**`（角色：exploit）

- [ ] 3.1 `src/shiro/ChainsEngine.java` 的通用方法（节点目录、载体目录、参数查询、后继节点、构建、参数归一化、
  初始化与状态）改为委派到新包，并把结果适配回原有嵌套结果类型；
  **公开方法签名与嵌套结果类型保持不变**；验证：`javac` 编译 `src\**` 与 `tests\**` 均为退出码 0
- [ ] 3.2 删除该类中已下沉到新包的重复实现，只保留 Shiro 专属内容（6 条预置链模板与默认参数生成）；
  验证：类内不再出现对 java-chains 引擎类的直接调用；`ShiroCheck` 通过
- [ ] 3.3 既有调用方（`src/shiro/ShiroExploit.java`、`src/Main.java`、`tests/ShiroCheck.java`）零改动；
  验证：`git diff --name-only` 中不出现这三个文件
- [ ] 3.4 `src/shiro/ChainsEngine.java` 仍不得出现对 Shiro 专用引擎类的引用；
  验证：`python -m unittest tests.test_decoupling` 全部用例通过

## 4. 依赖边界自检同步

写入域：`tests/test_decoupling.py`（角色：测试）

- [ ] 4.1 允许边集合新增 `payload` 包：`payload` 只允许依赖 `util`；`shiro` 增加允许依赖 `payload`；
  `ui` 增加允许依赖 `payload`；验证：`python -m unittest tests.test_decoupling` 通过，
  且 `payload` 出现指向 `shiro` / `probe` / `proxy` / `ui` 的引用时该用例失败
- [ ] 4.2 通用组件断言范围扩展到 `src/payload/` 下全部源文件（不得引用四个具体功能包）；
  验证：临时在新包内加入一处 `shiro.` 引用时用例失败，移除后恢复通过
- [ ] 4.3 断言数只增不减：改动前后用例数与断言数逐项比对，缺少任一项即视为放宽断言；
  验证：`python -m unittest tests.test_decoupling -v` 的用例数不少于改动前，且新增反向用例通过

## 5. 引擎自检

写入域：`tests/PayloadCheck.java`（角色：测试）

- [ ] 5.1 新增自检骨架：初始化引擎、打印状态信息与实测节点数、按 `check(名称, 条件)` 汇总 PASS/FAIL，
  存在失败即非零退出码；验证：文件存在且 `javac` 编译通过
- [ ] 5.2 目录断言：载体数恰为 28 且去重；节点目录不少于 400 且等于自检记录的实测值（打印实际数量，
  不一致即失败）；验证：运行 `PayloadCheck`，两条断言 PASS
- [ ] 5.3 分组断言：每个载体恰好属于一个分组、分组并集等于载体目录、分组内无目录之外的标识；
  验证：运行 `PayloadCheck`，分组相关断言 PASS
- [ ] 5.4 导航断言：已知节点的后继集合非空且重复查询结果一致；`templatesimpl` 的后继包含 `bytecodeconvert`；
  验证：运行 `PayloadCheck`，导航断言 PASS
- [ ] 5.5 载体创建断言：28 个载体逐一创建，每个都返回成功结果或带原因的失败结论，不得抛异常；
  验证：运行 `PayloadCheck`，28 条载体断言 PASS
- [ ] 5.6 失败路径断言：目录外的载体标识、空链、不被认可的后继节点，三者均返回带原因的失败结论且不产出载荷；
  验证：运行 `PayloadCheck`，失败路径断言 PASS
- [ ] 5.7 双形态断言：成功结果的原始字节长度大于 0；Base64 文本标准解码后与原始字节长度相同、
  首尾各 4 字节逐字节相同；解码失败即判失败；验证：运行 `PayloadCheck`，往返断言 PASS
- [ ] 5.8 安全断言：JNDI 一类载体默认参数的回连地址为空、可记录摘要不含载荷正文任意连续 16 个字符、
  连续构建后工作区无新增文件；验证：运行 `PayloadCheck`，安全断言 PASS
- [ ] 5.9 自检输出按 `[PASS]` / `[FAIL]` 前缀逐条打印，末尾打印总数与失败数；
  验证：运行输出中失败数为 0，进程退出码为 0

## 6. 回归验证

写入域：无（只运行命令与比对输出，不改任何源码；如发现缺陷，回到对应写入域的任务修复）

- [ ] 6.1 `ShiroCheck` 标准输出与改动前逐字符一致（先保存改动前输出为基线再比对）：
  验证：`run: java --add-opens ... -cp "target\tmp2;target\classes;lib\java-chains-cli-2.0.0-beta4.jar" ShiroCheck`
  输出与基线 `fc /b` 等价比对无差异，退出码 0
- [ ] 6.2 其余四套 Java 自检全绿：`ProxyServerCheck`、`UiNavigationCheck`、`UiShiroCheck`、`UiSwitchEndToEndCheck`；
  验证：四条命令退出码均为 0，且各自断言失败数为 0
- [ ] 6.3 Python 侧回归全绿；验证：`python -X utf8 -m unittest discover -s tests` 失败数为 0
- [ ] 6.4 规划件自身合法；验证：`openspec validate --all --strict` 退出码 0，且 change 校验为 passed
- [ ] 6.5 依赖合规：`src/pom.xml` 未新增依赖、未引入新第三方库；
  验证：`git diff --name-only` 中不出现 `src/pom.xml`，且 `python -m unittest tests.test_decoupling` 通过

## 7. 收尾三项（主 agent 在合并后统一执行）

写入域：`.backups/**`、`AI_REPORT.md`、`PROGRESS.md` 与构建产物（仅主 agent）

- [ ] 7.1 备份本次改动，且 `.backups/` 只保留最近三次快照（超出部分轮转删除）；
  验证：`Get-ChildItem .backups` 的时间戳目录数不超过 3，且包含本次时间戳
- [ ] 7.2 追加 `AI_REPORT.md` 本轮章节（改动内容、实测数字、未解决项与已知局限）并更新 `PROGRESS.md`；
  验证：两个文件都出现本次 change 的记录，且记录含实际命令与结论
- [ ] 7.3 重新构建并校验产物时间：验证：`.\build.ps1` 退出码 0，JAR 的 `LastWriteTime` 晚于
  `src/`、`python/`、`tests/` 下全部源文件（构建脚本自身会逐文件校验并在不满足时报错）
- [ ] 7.4 本 change 不含新增可持久化配置项，因此没有「写配置键 + 加入配置页」两步任务；
  对应工作已登记为后续 change `payload-config-keys` 与 `payload-page`；
  验证：`git diff --name-only` 中不出现 `src/config/AppConfig.java` 与 `src/ui/ConfigPage.java`

## 8. 监督复核

写入域：无（只读）

- [ ] 8.1 按 `docs/AGENT-ROLES.md` 的十项清单独立复核，不引用实现者结论：
  越界改动、空承诺、断言放宽、依赖合规、构建一致性、文档同步、测试真实性；
  验证：复核结论写入 `AI_REPORT.md` 本轮章节，每项给出文件路径与行号
- [ ] 8.2 独立复算依赖边界与 JAR 时间（自己跑命令，不看实现者输出）；
  验证：`python -m unittest tests.test_decoupling` 与 `.\build.ps1` 由复核者亲自执行并记录输出