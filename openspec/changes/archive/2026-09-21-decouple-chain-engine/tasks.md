# Tasks

## 1. 共享内核下沉

- [x] 1.1 新增 `src/util/Codec.java`，提供 `base64(byte[])` 与 `decodeBase64(String)`；
  验证：编译通过，且 `Codec` 不 import 任何项目内的其它包
- [x] 1.2 `src/shiro/ShiroEngine.java` 的 `base64` / `decodeBase64` 改为委托 `Codec`，
  公开签名保持不变；验证：`ShiroCheck` 中 `cryptoRoundTrip` 全部断言通过
- [x] 1.3 `src/shiro/ChainsEngine.java` 第 195 行改用 `Codec.base64`，
  并移除该文件对 `ShiroEngine` 的引用；验证：`grep ShiroEngine src/shiro/ChainsEngine.java` 无结果

## 2. 依赖边界自检

- [x] 2.1 新增 `tests/test_decoupling.py`，实现包级依赖图构建与四种边界检查；
  验证：`python -m unittest tests.test_decoupling` 全部通过
- [x] 2.2 为检查器补反向用例：构造含环的临时依赖图，断言检查器能报出该环及位置；
  验证：新增的反向用例通过（证明检查器不是恒真）
- [x] 2.3 把「通用组件不得依赖具体功能模块」固化为对 `ChainsEngine` 的断言；
  验证：临时在源码中加入 `ShiroEngine` 引用时该断言失败，移除后恢复通过

## 3. 文档

- [x] 3.1 新增 `docs/AGENT-ROLES.md`，写明六个角色的职责、输入输出与协作顺序；
  验证：文档中每个角色都有「负责」「不负责」「交付物」三节
- [x] 3.2 在 `docs/DESIGN-agents.md` 补充监督角色的解耦审计职责，
  写明判定标准（必须解耦 / 可不改 / 不做）；验证：含三类判定标准与实例
- [x] 3.3 更新 `README.md` / `README.en.md` 的项目结构与文档索引；
  验证：中英两版条目一一对应

## 4. 回归与收尾

- [x] 4.1 运行五套 Java 自检，确认输出与改动前逐字符一致（除类名）；
  验证：`ShiroCheck`、`ProxyServerCheck`、`UiNavigationCheck`、`UiShiroCheck`、
  `UiSwitchEndToEndCheck` 全部退出码 0
- [x] 4.2 运行 `python -m unittest discover -s tests`，确认全部通过；
  验证：输出中失败数为 0
- [x] 4.3 运行 `build.ps1` 并校验 JAR 构建时间晚于 `src/`、`python/`、`tests/` 全部源文件；
  验证：构建脚本输出 JAR 时间与字节数，且校验通过
- [x] 4.4 备份到 `.backups/`（仅保留最近三次）、追加 `AI_REPORT.md`、更新 `PROGRESS.md`；
  验证：`.backups/` 内恰好三个时间戳目录，两个文档含本轮记录
- [x] 4.5 运行 `openspec validate --all --strict`，确认规划件合法；
  验证：退出码 0，且规格校验 passed 数增加

## 5. 监督复核

- [x] 5.1 按 `docs/AGENT-ROLES.md` 中监督角色的清单复核本次改动，
  独立复算依赖边界与 JAR 时间，不得复用实现者的结论；
  验证：复核结论写入 `AI_REPORT.md` 的本轮章节