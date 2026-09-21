# Proposal: 用 agent 读日志区分「推进中」「长等待」与「卡住」

## Why

现有的超时上限只能回答「进程有没有退出」，回答不了「它是在干活还是卡住了」。
实测证据：执行 `ping -n 400` 这类耗时命令时，会话日志会静默数分钟且毫无新增，
但这是**正常等待**，不是故障。纯机械判据（例如「日志多久没增长」）
在这种场景下必然误报，进而导致错误终止一个正在正常工作的会话。

另一方面，真正的卡死是有特征的：反复重试同一条命令、重复输出同一个错误、
在无明确等待理由的情况下长时间零输出。区分这两类情况需要读懂日志内容，
这正是 LLM 判断擅长而机械规则不擅长的部分。

## What Changes

- 新增 `tools/watchdog.ps1`：对任意会话日志做一次判定，输出
  `PROGRESSING` / `WAITING` / `STUCK` 三态结论与理由；可单独手动运行。
- 新增 `tools/lib/CodexCli.ps1`：抽出「定位 codex 可执行文件、启动会话、
  终止进程树」三件事，供启动脚本与看护脚本共用，避免两处维护。
- 改造 `tools/agent.ps1` 的等待过程：不再一次性阻塞等待，而是周期性检查
  日志增长；出现停滞时唤起看护 agent 读日志判定，再据此决定「继续等」还是
  「终止会话」。总超时上限仍然保留，作为兜底。
- 判定为卡死时由启动脚本终止会话（拥有进程句柄的是它，不是 LLM），
  看护 agent 自身始终只读、不参与终止动作。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `process/multi-agent-concurrency`: 新增「停滞判定必须区分长等待与卡死」的需求，
  规定判定依据来自日志内容而非单纯的时间阈值，且看护 agent 必须只读。

## Impact

受影响文件与写入域（主 agent / orchestrator）：

- `tools/watchdog.ps1`（新增，只读判定脚本）
- `tools/lib/CodexCli.ps1`（新增，共用库）
- `tools/agent.ps1`（改：等待循环接入停滞判定）
- `docs/AGENT-ROLES.md`（改：新增看护 agent 角色）
- `docs/AGENT-RUNBOOK.md`（改：新增使用说明与实测结论）
- `openspec/specs/process/multi-agent-concurrency/spec.md`（改：归档时并入新增需求）
- `README.md` / `README.en.md`（改：多 Agent 章节补一句）
- `AI_REPORT.md` / `PROGRESS.md`（改：收尾记录）

不涉及 `src/`、`python/`、`tests/`，因此不改动工具运行时行为，也不影响既有自检入口。

## Non-goals

- 不改运行时工具的探测、代理、Shiro 与界面行为。
- 不引入任何第三方依赖；判定只用 PowerShell 标准能力与既有的 codex CLI。
- 不让 LLM 直接终止进程：终止动作只由持有进程句柄的启动脚本执行。
- 不做「自动修复卡死」（例如自动重试或改写任务），只做判定与终止。