<#
.SYNOPSIS
  把一个高层任务自动拆解，派给多个角色 agent 完成，最后汇报完成情况。

.DESCRIPTION
  这是「主 agent 编排」的可执行实现。全流程四步：

  1. 拆解：以只读会话让 LLM 依据角色矩阵产出计划（JSON），限定可用的角色与写入域。
  2. 校验：计划必须通过机械校验——角色存在、步数不超上限、每步写入域不与同批冲突。
  3. 执行：按「依赖 + 写入域冲突」分批。同批先串行预建 worktree（避免并发建分支的竞争），
     再并行启动；每步结束后按 tools/agent.ps1 的结果文件判断能否自动合并。
  4. 汇报：逐步给出状态（已合并 / 无需改动 / 需人工 / 被阻塞）、提交范围与原因。

  安全原则：只要有任何不确定就停手保留现场，绝不为了「跑完」而合并可疑产出。

.EXAMPLE
  # 看它会怎么拆解，不实际执行
  .\tools\orchestrate.ps1 -Goal "给项目加 payload 生成功能" -DryRun

.EXAMPLE
  # 真正执行（每一步成功后自动合并）
  .\tools\orchestrate.ps1 -Goal "补齐 fastjson 1.2.73-1.2.80 的版本识别盲区"

.EXAMPLE
  # 执行但不合并，全部留给人工审阅
  .\tools\orchestrate.ps1 -Goal "..." -NoMerge
#>
[CmdletBinding()]
param(
    # 高层任务描述，用自然语言写
    [Parameter(Mandatory = $true)]
    [string]$Goal,

    # 单步 agent 的超时上限（分钟）；0 表示用该角色的默认预算
    [ValidateRange(0, 600)]
    [int]$StepTimeoutMinutes = 0,

    # 拆解阶段（只读规划会话）的超时上限
    [ValidateRange(1, 60)]
    [int]$PlanTimeoutMinutes = 8,

    # 允许拆出的最多步数，防止计划失控
    [ValidateRange(1, 20)]
    [int]$MaxSteps = 6,

    # 不执行监督复核步骤
    [switch]$SkipSupervisor,

    # 只拆解并打印计划，不创建 worktree、不启动 agent
    [switch]$DryRun,

    # 执行但不自动合并
    [switch]$NoMerge,

    # 计划直接由文件提供（调试用，走 .json）
    [string]$PlanFile = ""
)

$ErrorActionPreference = "Stop"
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false } catch { }

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$agentScript = Join-Path $PSScriptRoot "agent.ps1"

. (Join-Path $PSScriptRoot "lib\CodexCli.ps1")
. (Join-Path $PSScriptRoot "lib\RoleMatrix.ps1")
. (Join-Path $PSScriptRoot "lib\AgentRun.ps1")

foreach ($required in @($agentScript, (Join-Path $PSScriptRoot "lib\RoleMatrix.ps1"), (Join-Path $PSScriptRoot "lib\AgentRun.ps1"))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "找不到依赖：$required" }
}

$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$reportPath = Join-Path $env:TEMP "jset-orchestrate-$runId.md"
$stageDirectory = Join-Path $env:TEMP "jset-orchestrate-$runId"
if (-not (Test-Path -LiteralPath $stageDirectory)) { New-Item -ItemType Directory -Path $stageDirectory -Force | Out-Null }

$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"

# ---- 汇报收集 ----
$script:stepRecords = @()

function Add-StepRecord {
    param(
        [Parameter(Mandatory = $true)][string]$Step,
        [Parameter(Mandatory = $true)][string]$Role,
        [Parameter(Mandatory = $true)][string]$Status,
        [string]$Detail = "",
        [string]$Commits = "",
        # 该步交给角色做的事，用于汇报里的「主要工作」
        [string]$Task = "",
        # 该步实际改动的文件（逗号分隔），用于汇报里的「产出文件」
        [string]$Files = ""
    )
    $script:stepRecords += @{
        Step = $Step; Role = $Role; Status = $Status; Detail = $Detail; Commits = $Commits
        Task = $Task; Files = $Files
    }
}

# 按角色汇总本次调用情况：谁被调用、做了几步、主要做什么、产出哪些文件。
# 汇报里给出这一节，是为了让人一眼看清「哪些角色参与了、各自负责什么」，
# 而不必从逐步表格里自己反推。
function Get-RoleBreakdown {
    $order = @()
    $byRole = @{}
    foreach ($record in $script:stepRecords) {
        if (-not $byRole.ContainsKey($record.Role)) {
            $byRole[$record.Role] = @{ Steps = @(); Tasks = @(); Files = @(); Statuses = @() }
            $order += $record.Role
        }
        $entry = $byRole[$record.Role]
        $entry.Steps += $record.Step
        if ($record.Task) { $entry.Tasks += $record.Task }
        if ($record.Files) { $entry.Files += ($record.Files -split ', ') }
        $entry.Statuses += $record.Status
    }
    return @{ Order = $order; ByRole = $byRole }
}

# ---- 角色清单文本（喂给拆解用的 LLM，避免它凭空发明角色） ----
function Get-RoleCatalogText {
    $lines = @()
    foreach ($role in Get-AllRoles) {
        if ($role -eq "supervisor") { continue }
        $scope = (Get-RoleWriteScope -Role $role) -join "、"
        $lines += "- $role：写入域 $scope"
    }
    return ($lines -join "`n")
}

# ---- 第 1 步：拆解 ----
function Get-PlanFromModel {
    param([Parameter(Mandatory = $true)][string]$GoalText)

    $catalog = Get-RoleCatalogText
    $prompt = @"
你是本仓库的任务拆解者。只读，不修改任何文件。

仓库：JavaSecExpToolKit（Java + Python 桌面安全测试工具）。
本仓库使用 spec-driven 开发：功能改动必须先有 OpenSpec change，再改代码。

## 可用角色及其写入域

$catalog

## 约束

- 一步只能派一个角色；角色的写入域就是它允许改的路径，越界会被拒绝提交。
- 同一批并行的步骤，写入域不得重叠；有依赖关系的步骤要写进 dependsOn。
- 改动界面（ui）的任务会与其它角色冲突，应单独成步。
- 新增可持久化配置要拆成两步：orchestrator 步骤改 src/config 的配置键（共享内核，
  只有 orchestrator 的写入域覆盖它），ui 步骤改 src/ui/ConfigPage.java 的分组；
  后者 dependsOn 前者。功能开发角色（probe/exploit/traffic）无权改这两处。
- 测试改动（tests）通常依赖实现步骤先完成，写进 dependsOn。
- 不要把同一个角色拆成多个重复步骤。
- 步骤数不超过 $MaxSteps。

## 待拆解的任务

$GoalText

## 输出要求

只输出一个 JSON 对象，不要任何解释文字，不要 markdown 代码块围栏。
格式：

{
  "summary": "一句话说明这次拆解的思路",
  "steps": [
    {"role": "角色名", "slug": "小写连字符短名", "task": "这一步要做什么，交给该角色执行的完整描述", "dependsOn": ["依赖的 slug"]}
  ]
}

dependsOn 为空数组表示无依赖。每一步的 slug 在同一计划内必须唯一。
"@

    $promptFile = Join-Path $stageDirectory "plan-prompt.txt"
    $answerFile = Join-Path $stageDirectory "plan-answer.txt"
    $logFile = Join-Path $stageDirectory "plan-log.txt"
    $errFile = Join-Path $stageDirectory "plan-err.txt"
    [System.IO.File]::WriteAllText($promptFile, $prompt, (New-Object System.Text.UTF8Encoding $false))
    if (Test-Path -LiteralPath $answerFile) { [System.IO.File]::Delete($answerFile) }

    $executable = Get-CodexExecutable
    $codexArgs = @("exec", "-C", $root, "-s", "read-only", "--add-dir", $stageDirectory, "-o", "`"$answerFile`"", "-")

    Write-Host "拆解中（只读会话，上限 $PlanTimeoutMinutes 分钟）..."
    $process = Start-Process -FilePath $executable -ArgumentList $codexArgs -NoNewWindow -PassThru `
        -RedirectStandardInput $promptFile -RedirectStandardOutput $logFile -RedirectStandardError $errFile
    $process | Wait-Process -Timeout ($PlanTimeoutMinutes * 60) -ErrorAction SilentlyContinue
    if (-not $process.HasExited) {
        Stop-ProcessTree -ProcessId $process.Id | Out-Null
        throw "拆解会话超时（$PlanTimeoutMinutes 分钟），未取得计划。"
    }
    if (-not (Test-Path -LiteralPath $answerFile)) {
        throw "拆解会话没有产出结果文件；日志见 $errFile"
    }

    $raw = Get-Content -Raw -Encoding UTF8 $answerFile
    if (-not $raw) { throw "拆解结果为空；日志见 $errFile" }

    # 模型可能仍会加上代码块围栏，剥掉后再解析
    $text = $raw.Trim()
    $fence = [regex]::Match($text, '(?s)```(?:json)?\s*(?<body>.*?)```')
    if ($fence.Success) { $text = $fence.Groups['body'].Value.Trim() }
    $start = $text.IndexOf('{')
    $end = $text.LastIndexOf('}')
    if ($start -lt 0 -or $end -le $start) { throw "拆解结果里找不到 JSON 对象；原文见 $answerFile" }
    $text = $text.Substring($start, $end - $start + 1)

    try {
        $plan = $text | ConvertFrom-Json
    } catch {
        throw "拆解结果不是合法 JSON：$($_.Exception.Message)；原文见 $answerFile"
    }
    return @{ Plan = $plan; AnswerFile = $answerFile }
}

# ---- 第 2 步：校验计划 ----
function Test-Plan {
    param([Parameter(Mandatory = $true)]$Plan)

    $problems = @()
    if (-not $Plan.PSObject.Properties.Name.Contains('steps')) {
        return @{ Ok = $false; Problems = @("计划里没有 steps 字段") }
    }
    $steps = @($Plan.steps)
    if ($steps.Count -eq 0) { return @{ Ok = $false; Problems = @("计划的 steps 为空") } }
    if ($steps.Count -gt $MaxSteps) {
        $problems += "步数 $($steps.Count) 超过上限 $MaxSteps"
    }

    $knownRoles = Get-AllRoles
    $slugs = @()
    foreach ($step in $steps) {
        foreach ($field in @('role', 'slug', 'task')) {
            if (-not $step.PSObject.Properties.Name.Contains($field) -or -not $step.$field) {
                $problems += "有步骤缺少 $field 字段"
            }
        }
        if ($step.role -and ($knownRoles -notcontains $step.role)) {
            $problems += "未知角色：$($step.role)"
        }
        if ($step.slug) {
            if ($step.slug -notmatch '^[a-z0-9][a-z0-9-]{0,40}$') {
                $problems += "slug 不合规（只允许小写字母数字与连字符，且不超过 41 字符）：$($step.slug)"
            }
            if ($slugs -contains $step.slug) { $problems += "slug 重复：$($step.slug)" }
            $slugs += $step.slug
        }
    }

    foreach ($step in $steps) {
        $dependencies = @()
        if ($step.PSObject.Properties.Name.Contains('dependsOn') -and $step.dependsOn) { $dependencies = @($step.dependsOn) }
        foreach ($dependency in $dependencies) {
            if ($slugs -notcontains $dependency) {
                $problems += "步骤 $($step.slug) 依赖了不存在的 slug：$dependency"
            }
            if ($dependency -eq $step.slug) {
                $problems += "步骤 $($step.slug) 依赖了自己"
            }
        }
    }

    return @{ Ok = ($problems.Count -eq 0); Problems = $problems }
}

# ---- 第 3 步：分批（依赖 + 写入域冲突） ----
function Get-PlanBatches {
    param([Parameter(Mandatory = $true)][array]$Steps)

    $remaining = @($Steps)
    $done = @()
    $batches = @()

    while ($remaining.Count -gt 0) {
        # 依赖已满足的候选步骤
        $ready = @()
        foreach ($step in $remaining) {
            $dependencies = @()
            if ($step.PSObject.Properties.Name.Contains('dependsOn') -and $step.dependsOn) { $dependencies = @($step.dependsOn) }
            $unsatisfied = @($dependencies | Where-Object { $done -notcontains $_ })
            if ($unsatisfied.Count -eq 0) { $ready += $step }
        }
        if ($ready.Count -eq 0) {
            # 依赖成环，交给调用方报错
            return @{ Batches = $batches; Cyclic = $true }
        }

        $batch = @()
        foreach ($step in $ready) {
            $conflict = $false
            foreach ($chosen in $batch) {
                if (Test-RoleConflict -LeftRole $chosen.role -RightRole $step.role) { $conflict = $true; break }
            }
            if (-not $conflict) { $batch += $step }
        }

        if ($batch.Count -eq 0) { $batch = @($ready[0]) }
        $batches += ,@($batch)
        foreach ($step in $batch) {
            $done += $step.slug
            $remaining = @($remaining | Where-Object { $_.slug -ne $step.slug })
        }
    }

    return @{ Batches = $batches; Cyclic = $false }
}

# 取某一步实际改动的文件（相对路径，逗号分隔）。
# 传入的区间应能唯一定位「这一步带来的改动」（合并后取 <合并提交>^1..<合并提交>）；
# 取不到时返回空串，由调用方按「无产出」处理。
function Get-StepFiles {
    param(
        [Parameter(Mandatory = $true)][string]$Range,
        [int]$Limit = 6
    )
    $result = Invoke-GitOn -Repository $root -Options @("diff", "--name-only") -Operands @($Range)
    if ($result.Code -ne 0) { return "" }
    $files = @($result.Output | Where-Object { $_ })
    if ($files.Count -eq 0) { return "" }
    $shown = @($files | Select-Object -First $Limit)
    $joined = $shown -join ', '
    if ($files.Count -gt $Limit) { $joined = "$joined 等 $($files.Count) 个" }
    return $joined
}

# ---- 第 4 步：执行一个步骤 ----
function Invoke-PlanStep {
    param(
        [Parameter(Mandatory = $true)]$Step,
        [Parameter(Mandatory = $true)][int]$Index,
        [Parameter(Mandatory = $true)][bool]$Parallel
    )

    $role = $Step.role
    $slug = $Step.slug
    $task = $Step.task
    $branch = Get-AgentBranchName -Role $role -Slug $slug
    $resultFile = Join-Path $stageDirectory "result-$slug.txt"

    if ($role -eq "supervisor") {
        # 只读审计：没有分支，也没有可合并产出
        Write-Host "`n[$Index] $role/$slug（只读审计）"
        if ($DryRun) { return }
        $outFile = Join-Path $stageDirectory "supervisor-$slug.md"
        $timeout = $StepTimeoutMinutes
        if ($timeout -le 0) { $timeout = Get-RoleDefaultTimeout -Role $role }
        $arguments = @(
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $agentScript,
            "-Role", $role, "-Slug", $slug, "-Task", $task,
            "-TimeoutMinutes", $timeout
        )
        & powershell @arguments | Out-Host
        $code = $LASTEXITCODE
        $detail = "退出码 $code"
        if ($code -eq 0) {
            Add-StepRecord -Step "$Index" -Role $role -Status "已执行" -Detail $detail -Task $task
        } else {
            Add-StepRecord -Step "$Index" -Role $role -Status "失败" -Detail $detail -Task $task
        }
        return
    }

    if ($DryRun) {
        Write-Host "`n[$Index] $role/$slug"
        Write-Host "    任务：$task"
        Write-Host "    分支：$branch"
        return
    }

    $baseline = (Invoke-GitOn -Repository $root -Options @("rev-parse") -Operands @("HEAD")).Output[0]

    # 并行批次的 worktree 由调用方串行预建（避免并发写 .git/worktrees 的竞争）
    $reuse = $false
    if (Test-Path -LiteralPath (Get-AgentWorktreePath -Repository $root -Role $role -Slug $slug)) { $reuse = $true }

    $timeout = $StepTimeoutMinutes
    if ($timeout -le 0) { $timeout = Get-RoleDefaultTimeout -Role $role }

    $arguments = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $agentScript,
        "-Role", $role, "-Slug", $slug, "-Task", $task,
        "-TimeoutMinutes", $timeout, "-ResultFile", $resultFile
    )
    if ($reuse) { $arguments += "-ReuseWorktree" }

    $outFile = Join-Path $stageDirectory "agent-$slug.out.txt"
    $errFile = Join-Path $stageDirectory "agent-$slug.err.txt"
    if (Test-Path -LiteralPath $resultFile) { [System.IO.File]::Delete($resultFile) }

    if ($Parallel) {
        # 并行：后台启动，由调用方统一收集
        $process = Start-Process -FilePath "powershell" -ArgumentList $arguments -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput $outFile -RedirectStandardError $errFile
        return @{ Process = $process; Role = $role; Slug = $slug; Step = $Step; Index = $Index; Baseline = $baseline; Branch = $branch; ResultFile = $resultFile; Task = $task }
    }

    & powershell @arguments | Out-Host
    $code = $LASTEXITCODE
    $result = Get-AgentRunResult -ResultFile $resultFile


    if ($NoMerge) {
        Add-StepRecord -Step "$Index" -Role $role -Status "已执行（未合并）" -Detail "退出码 $code；分支 $branch" -Task $task
        return
    }

    $decision = Get-MergeDecision -Repository $root -Result $result -Branch $branch
    switch ($decision.Action) {
        "skip" {
            Remove-AgentWorkspace -Repository $root -Role $role -Slug $slug | Out-Null
            Add-StepRecord -Step "$Index" -Role $role -Status "无需改动" -Detail $decision.Reason -Task $task
        }
        "manual" {
            Add-StepRecord -Step "$Index" -Role $role -Status "需人工处理" -Detail "$($decision.Reason)；现场：$branch" -Task $task
        }
        "merge" {
            $merge = Invoke-GitOn -Repository $root -Options @("merge", "--no-ff", "--no-edit") -Operands @($branch)
            if ($merge.Code -ne 0) {
                Invoke-GitOn -Repository $root -Options @("merge", "--abort") | Out-Null
                Add-StepRecord -Step "$Index" -Role $role -Status "需人工处理" -Detail "合并失败：$($merge.Output -join ' ')" -Task $task
            } else {
                $newHead = (Invoke-GitOn -Repository $root -Options @("rev-parse") -Operands @("HEAD")).Output[0]
                $commits = (Invoke-GitOn -Repository $root -Options @("log", "--oneline") -Operands @("$baseline..$newHead")).Output
                Remove-AgentWorkspace -Repository $root -Role $role -Slug $slug | Out-Null
                Add-StepRecord -Step "$Index" -Role $role -Status "已合并" -Detail "分支 $branch" -Task $task -Files (Get-StepFiles -Range "$newHead^1..$newHead") -Commits (($commits | Select-Object -First 3) -join ' / ')
            }
        }
    }
}

# ---- 主流程 ----
Write-Host "== 编排 =="
Write-Host "目标    : $Goal"
Write-Host "仓库    : $root"

$ready = Test-RepoReadyForMerge -Repository $root
if (-not $ready.Ready) {
    throw "$($ready.Reason)。`n编排会做自动合并，必须先让主仓库处于可合并状态。"
}

# 拆解
if ($PlanFile) {
    if (-not (Test-Path -LiteralPath $PlanFile)) { throw "找不到计划文件：$PlanFile" }
    $plan = (Get-Content -Raw -Encoding UTF8 $PlanFile) | ConvertFrom-Json
    Write-Host "计划来源: $PlanFile"
} else {
    $planResult = Get-PlanFromModel -GoalText $Goal
    $plan = $planResult.Plan
    Write-Host "计划来源: $($planResult.AnswerFile)"
}

if ($plan.summary) { Write-Host "`n拆解思路: $($plan.summary)" }

$validation = Test-Plan -Plan $plan
if (-not $validation.Ok) {
    Write-Host "`n计划未通过校验："
    $validation.Problems | ForEach-Object { Write-Host "  - $_" }
    throw "计划不合规，已停止（未创建任何 worktree）。"
}

$steps = @($plan.steps)
if ($SkipSupervisor) { $steps = @($steps | Where-Object { $_.role -ne 'supervisor' }) }

$batches = Get-PlanBatches -Steps $steps
if ($batches.Cyclic) { throw "计划里的 dependsOn 存在循环依赖，已停止。" }

Write-Host "`n== 计划（$($steps.Count) 步，$($batches.Batches.Count) 批）=="
$index = 0
foreach ($batch in $batches.Batches) {
    Write-Host "批次："
    foreach ($step in $batch) {
        $index++
        $dependencies = ""
        if ($step.PSObject.Properties.Name.Contains('dependsOn') -and $step.dependsOn) {
            $dependencies = "  依赖：$($step.dependsOn -join '、')"
        }
        Write-Host ("  [{0}] {1}/{2}{3}" -f $index, $step.role, $step.slug, $dependencies)
        Write-Host "      $($step.task)"
    }
}

if ($DryRun) {
    Write-Host "`n[DryRun] 未创建 worktree、未启动 agent。"
    $ErrorActionPreference = $previous
    return
}

# 执行
Write-Host "`n== 执行 =="
$stepIndex = 0
foreach ($batch in $batches.Batches) {
    if ($batch.Count -eq 1) {
        $stepIndex++
        Invoke-PlanStep -Step $batch[0] -Index $stepIndex -Parallel $false
        continue
    }

    Write-Host "`n--- 并行批次（$($batch.Count) 步）---"
    # 串行预建 worktree：并发执行 git worktree add 会争用 .git/worktrees 与 refs
    foreach ($step in $batch) {
        $stepIndex++
        Write-Host "[$stepIndex] $($step.role)/$($step.slug) 预建工作区"
        $created = New-AgentWorktree -Repository $root -Role $step.role -Slug $step.slug
        if (-not $created.Created) {
            Write-Host "    预建失败：$($created.Reason)"
        }
    }

    $running = @()
    $stepIndex = $stepIndex - $batch.Count
    foreach ($step in $batch) {
        $stepIndex++
        Write-Host "[$stepIndex] 启动 $($step.role)/$($step.slug)"
        $handle = Invoke-PlanStep -Step $step -Index $stepIndex -Parallel $true
        if ($handle) { $running += $handle }
    }

    foreach ($handle in $running) {
        Write-Host "`n等待 $($handle.Role)/$($handle.Slug) ..."
        $handle.Process.WaitForExit()
    }

    # 并行批次结束后，逐个按顺序合并，避免同时改 main
    foreach ($handle in $running) {
        $role = $handle.Role
        $slug = $handle.Slug
        $branch = $handle.Branch
        $result = Get-AgentRunResult -ResultFile $handle.ResultFile
        $exitCode = $handle.Process.ExitCode

        if ($NoMerge) {
            Add-StepRecord -Step "$($handle.Index)" -Role $role -Status "已执行（未合并）" -Detail "退出码 $exitCode；分支 $branch" -Task $handle.Task
            continue
        }

        $decision = Get-MergeDecision -Repository $root -Result $result -Branch $branch
        switch ($decision.Action) {
            "skip" {
                Remove-AgentWorkspace -Repository $root -Role $role -Slug $slug | Out-Null
                Add-StepRecord -Step "$($handle.Index)" -Role $role -Status "无需改动" -Detail $decision.Reason -Task $handle.Task
            }
            "manual" {
                Add-StepRecord -Step "$($handle.Index)" -Role $role -Status "需人工处理" -Detail "$($decision.Reason)；现场：$branch" -Task $handle.Task
            }
            "merge" {
                $merge = Invoke-GitOn -Repository $root -Options @("merge", "--no-ff", "--no-edit") -Operands @($branch)
                if ($merge.Code -ne 0) {
                    Invoke-GitOn -Repository $root -Options @("merge", "--abort") | Out-Null
                    Add-StepRecord -Step "$($handle.Index)" -Role $role -Status "需人工处理" -Detail "合并失败：$($merge.Output -join ' ')" -Task $handle.Task
                } else {
                    $newHead = (Invoke-GitOn -Repository $root -Options @("rev-parse") -Operands @("HEAD")).Output[0]
                    $commits = (Invoke-GitOn -Repository $root -Options @("log", "--oneline") -Operands @("$($handle.Baseline)..$newHead")).Output
                    Remove-AgentWorkspace -Repository $root -Role $role -Slug $slug | Out-Null
                    Add-StepRecord -Step "$($handle.Index)" -Role $role -Status "已合并" -Detail "分支 $branch" -Task $handle.Task -Files (Get-StepFiles -Range "$newHead^1..$newHead") -Commits (($commits | Select-Object -First 3) -join ' / ')
                }
            }
        }
    }
}

# ---- 汇报 ----
$finalStatus = Invoke-GitOn -Repository $root -Options @("status", "--porcelain")
$worktrees = Invoke-GitOn -Repository $root -Options @("worktree", "list")
$branches = Invoke-GitOn -Repository $root -Options @("branch", "--list", "agent/*")
$headLine = (Invoke-GitOn -Repository $root -Options @("log", "--oneline", "-1")).Output[0]

$mergedCount = @($script:stepRecords | Where-Object { $_.Status -eq '已合并' }).Count
$manualCount = @($script:stepRecords | Where-Object { $_.Status -eq '需人工处理' }).Count
$failedCount = @($script:stepRecords | Where-Object { $_.Status -eq '失败' }).Count
$skipCount = @($script:stepRecords | Where-Object { $_.Status -eq '无需改动' }).Count
$unmergedCount = @($script:stepRecords | Where-Object { $_.Status -eq '已执行（未合并）' }).Count

$report = New-Object System.Collections.Generic.List[string]
$report.Add("# 编排汇报（$runId）")
$report.Add("")
$report.Add("- 目标：$Goal")
$report.Add("- 计划：$($steps.Count) 步 / $($batches.Batches.Count) 批")
$report.Add("- 当前主线：$headLine")
$report.Add("")

# 先给结论：这次调用了哪些角色、各自做了多少步。
# 逐步明细在下面，但人第一眼想看的是「谁参与了」。
$breakdown = Get-RoleBreakdown
$report.Add("## 本次调用的角色")
$report.Add("")
foreach ($roleName in $breakdown.Order) {
    $entry = $breakdown.ByRole[$roleName]
    $report.Add("- **$roleName**：$($entry.Steps.Count) 步（第 $($entry.Steps -join '、') 步）")
}
$report.Add("")

$report.Add("## 各角色主要工作")
$report.Add("")
foreach ($roleName in $breakdown.Order) {
    $entry = $breakdown.ByRole[$roleName]
    $report.Add("### $roleName")
    $report.Add("")
    foreach ($record in ($script:stepRecords | Where-Object { $_.Role -eq $roleName })) {
        $report.Add("- 第 $($record.Step) 步[$($record.Status)]：$($record.Task)")
        if ($record.Files) { $report.Add("  - 产出文件：$($record.Files)") }
    }
    $report.Add("")
}

$report.Add("## 逐步结果")
$report.Add("")
$report.Add("| 步 | 角色 | 状态 | 主要工作 | 说明 | 提交 |")
$report.Add("| --- | --- | --- | --- | --- | --- |")
foreach ($record in $script:stepRecords) {
    $report.Add("| $($record.Step) | $($record.Role) | $($record.Status) | $($record.Task) | $($record.Detail) | $($record.Commits) |")
}
$report.Add("")
$report.Add("## 汇总")
$report.Add("")
$report.Add("- 调用角色数：$($breakdown.Order.Count)（$($breakdown.Order -join '、')）")
$report.Add("- 已合并：$mergedCount")
$report.Add("- 无需改动：$skipCount")
$report.Add("- 需人工处理：$manualCount")
if ($unmergedCount -gt 0) { $report.Add("- 已执行但未合并（-NoMerge）：$unmergedCount") }
if ($failedCount -gt 0) { $report.Add("- 失败：$failedCount") }
$report.Add("")
$report.Add("## 现场")
$report.Add("")
$report.Add("未提交改动：")
foreach ($line in $finalStatus.Output) { if ($line) { $report.Add("- $line") } }
$report.Add("")
$report.Add("工作区：")
foreach ($line in $worktrees.Output) { if ($line) { $report.Add("- $line") } }
$report.Add("")
$report.Add("残留分支：")
if ($branches.Output.Count -eq 0) { $report.Add("- （无）") } else { foreach ($line in $branches.Output) { if ($line) { $report.Add("- $line") } } }
$report.Add("")

$reportText = ($report -join "`n")
[System.IO.File]::WriteAllText($reportPath, $reportText, (New-Object System.Text.UTF8Encoding $false))

Write-Host "`n$reportText"
Write-Host "`n汇报已写入：$reportPath"
Write-Host "下一步：轮到你做测试与验收，再提修改建议。"

$ErrorActionPreference = $previous