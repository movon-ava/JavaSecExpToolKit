# Tasks

写入域：`tools/**`、`docs/**`、`README.md`、`README.en.md`、`openspec/**`、`AI_REPORT.md`、`PROGRESS.md`（主 agent）。

## 1. 抽出共用启动库

- [x] 1.1 新增 `tools/lib/CodexCli.ps1`，导出 `Get-CodexExecutable`（定位原生 codex.exe，
  找不到时回落 `codex`）与 `Stop-ProcessTree`（按 PID 终止整个进程树）；
  验证：`tools/agent.ps1` 与 `tools/watchdog.ps1` 都改为调用它，且脚本能正常解析
  （`[Parser]::ParseFile` 无错误）

## 2. 看护脚本

- [x] 2.1 新增 `tools/watchdog.ps1`：接收日志路径，把末尾窗口（默认 120 行）交给
  只读 agent，要求返回 `VERDICT: PROGRESSING|WAITING|STUCK` 与理由；
  验证：对「正常等待」样本与「真卡死」样本各跑一次，结论不同且与预期一致
- [x] 2.2 输出解析：识别 `VERDICT:` 行并归一化；解析不到时返回 `UNKNOWN`；
  验证：构造缺少 `VERDICT` 行的假回答，脚本返回 `UNKNOWN` 而不是崩溃或误判为 `STUCK`
- [x] 2.3 看护 agent 强制只读沙箱，且不具备终止进程的能力；
  验证：脚本中终止动作只出现在调用方，`watchdog.ps1` 自身无终止调用

## 3. 启动脚本接入停滞判定

- [x] 3.1 `tools/agent.ps1` 的等待改为轮询：每 `-PollSeconds`（默认 60）检查日志
  大小与修改时间，连续 `-StallRounds`（默认 2）轮无变化才唤起看护判定；
  验证：单测式跑一次正常任务，日志有推进时不触发看护
- [x] 3.2 依据判定决定动作：`STUCK` 则终止并打印日志尾部；`WAITING` / `PROGRESSING`
  / `UNKNOWN` 则继续等待，直至总超时上限；
  验证：用耗时命令构造 `WAITING` 场景，确认会话不被误杀且最终正常结束
- [x] 3.3 新增参数并设默认值，`-NoWatchdog` 可关闭判定回到纯超时行为；
  验证：`-NoWatchdog` 时脚本行为与改动前一致

## 4. 文档

- [x] 4.1 `docs/AGENT-ROLES.md` 新增看护 agent 角色（只读、判定依据、不作为终止方）；
  验证：角色总览表含该角色，且「不负责」明确写了不终止进程
- [x] 4.2 `docs/AGENT-RUNBOOK.md` 新增章节：何时触发、三态含义、如何手动对
  任意日志跑一次判定、判定不可用时的行为；
  验证：含手动运行示例与实测结论
- [x] 4.3 `README.md` / `README.en.md` 的多 Agent 章节补充一句说明；
  验证：中英两版对应

## 5. 回归与收尾

- [x] 5.1 回归：先手工验证 `openspec validate --all --strict` 通过
- [x] 5.2 新增 `tools/check_agent_tools.ps1`：把本轮实际踩到的缺陷固化成 34 项机械断言
  （参数与计数器同名、开关名写错形成死代码、缺 BOM、
  字面量转义残留、`.gitignore` 未锚定、判定解析与保守行为、看护权限边界）；
  验证：基线通过，且对 6 个人工注入的变体全部能报失败

## 6. 收尾三项

- [x] 6.1 备份到 `.backups/`（仅保留最近三次）
- [x] 6.2 追加 `AI_REPORT.md` 并更新 `PROGRESS.md`
- [x] 6.3 重新构建并校验 JAR 构建时间晚于 `src/`、`python/`、`tests/` 全部源文件