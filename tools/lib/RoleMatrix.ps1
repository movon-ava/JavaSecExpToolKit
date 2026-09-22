<#
.SYNOPSIS
  角色矩阵：写入域、超时预算与只读属性。

.DESCRIPTION
  这份矩阵原先内联在 tools/agent.ps1 里。tools/orchestrate.ps1 需要读同一份数据
  才能判断「哪两个角色可以并行」——复制一份必然会漂移，所以抽到这里共用。

  矩阵必须与 docs/AGENT-ROLES.md 的角色总览表一致；
  tools/check_agent_tools.ps1 会逐项核对，防止两处说法不一。
#>

# 角色的写入域（glob 形式，与 agent.ps1 提交前的机械校验用同一份数据）
function Get-RoleWriteScopes {
    [CmdletBinding()]
    param()
    return @{
        orchestrator = @(
            "AGENTS.md", "README.md", "README.en.md", "PROGRESS.md", "AI_REPORT.md",
            "build.ps1", "run.ps1", "tools/*", "docs/*", "openspec/*",
            # 共享内核：openspec/config.yaml 规定「任何角色不得直接改，需主 agent 单独开 change」。
            # 该规定的主体就是主 agent，因此这里必须给它写入域，否则共享内核改动无处可提交（实测过）。
            "src/config/*", "src/util/*", "src/pom.xml"
        )
        probe   = @("python/fj_probe.py", "python/jar_report.py", "src/probe/Probe*")
        exploit = @("src/shiro/*", "src/payload/*", "src/service/*", "src/preset/*",
                    "src/analyzer/*", "src/analyze/*")
        traffic = @("src/proxy/*", "src/probe/CaptureBridge.java")
        ui      = @("src/ui/*", "src/Main.java")
        test    = @("tests/*")
        supervisor = @()
    }
}

# 单个角色的超时预算（分钟）。
# 依据是本仓库已有的实测耗时：单个执行角色通常数分钟，
# 监督审计需要独立重算依赖图与构建时间，耗时更高。
function Get-RoleDefaultTimeouts {
    [CmdletBinding()]
    param()
    return @{
        orchestrator = 45
        probe        = 40
        exploit      = 40
        traffic      = 40
        ui           = 40
        test         = 40
        supervisor   = 60
    }
}

function Get-RoleDefaultTimeout {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Role)

    $timeouts = Get-RoleDefaultTimeouts
    if (-not $timeouts.ContainsKey($Role)) { throw "未定义超时预算的角色：$Role" }
    return $timeouts[$Role]
}

# 只读角色：不给写权限，也不产出可合并的分支
function Get-ReadOnlyRoles {
    [CmdletBinding()]
    param()
    return @("supervisor")
}

function Get-RoleWriteScope {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Role)

    $scopes = Get-RoleWriteScopes
    if (-not $scopes.ContainsKey($Role)) { throw "未定义写入域：$Role" }
    return $scopes[$Role]
}

# 角色的中文显示名。
#
# 汇报里若直接打印 probe / traffic 这样的标识，读的人还要对照文档才知道是谁；
# 显示名用于面向人的输出（汇报、收尾打印），角色标识仍用于分支名、写入域与命令参数，
# 两者不要混用：显示名一旦被当成参数传给 -Role，参数校验会直接拒绝。
function Get-RoleDisplayNames {
    [CmdletBinding()]
    param()
    return @{
        orchestrator = "主 agent"
        probe        = "探测 agent"
        exploit      = "利用链 agent"
        traffic      = "抓包 agent"
        ui           = "界面 agent"
        test         = "测试 agent"
        supervisor   = "监督 agent"
        watchdog     = "看护 agent"
    }
}

# 取某个角色的显示名；未登记时原样返回标识，保证不会因为漏登记而丢失信息。
function Get-RoleDisplayName {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Role)

    $names = Get-RoleDisplayNames
    if ($names.ContainsKey($Role)) { return $names[$Role] }
    return $Role
}

# 返回全部角色名。
#
# 调用方必须用 @(…) 包一层：
# 不能用 `return ,@(...)`（那会在 foreach 直接消费时把整个数组当成一个元素），
# 也不能直接 `return (…)`（单元素时会退化成字符串）。
function Get-AllRoles {
    [CmdletBinding()]
    param()
    return @((Get-RoleWriteScopes).Keys | Sort-Object)
}

# 判断两个写入域 glob 是否可能命中同一个文件。
#
# 保守策略：只在能证明不相交时才返回不重叠，其余一律按重叠处理（宁可串行）。
# 例如 probe 的 src/probe/Probe* 与 traffic 的 src/probe/CaptureBridge.java
# 目录相同但文件名模式不互相匹配，可以证明不相交。
function Test-WriteScopeConflict {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Left,
        [Parameter(Mandatory = $true)][string]$Right
    )

    if ($Left -eq $Right) { return $true }

    $leftNormalized = $Left -replace '\\', '/'
    $rightNormalized = $Right -replace '\\', '/'

    # 目录前缀：最后一个 / 之前的部分（无 / 表示根目录下的文件，前缀为空串）
    $leftDirectory = ""
    $rightDirectory = ""
    if ($leftNormalized.Contains('/')) { $leftDirectory = $leftNormalized.Substring(0, $leftNormalized.LastIndexOf('/')) }
    if ($rightNormalized.Contains('/')) { $rightDirectory = $rightNormalized.Substring(0, $rightNormalized.LastIndexOf('/')) }

    $sameDirectory = ($leftDirectory -eq $rightDirectory)
    $prefixRelated = $leftDirectory.StartsWith($rightDirectory) -or $rightDirectory.StartsWith($leftDirectory)

    if (-not $sameDirectory -and -not $prefixRelated) {
        # 目录互不包含：例如 src/shiro 与 tests，绝不可能命中同一文件
        return $false
    }

    # 目录相同或互相包含：再看能否证明文件名层面不相交
    $leftIsLiteral = -not $leftNormalized.Contains('*')
    $rightIsLiteral = -not $rightNormalized.Contains('*')

    if (-not $leftIsLiteral -and -not $rightIsLiteral) {
        # 两边都带通配符且目录相关，无法证明不相交
        return $true
    }
    if ($rightIsLiteral -and ($rightNormalized -like $leftNormalized)) { return $true }
    if ($leftIsLiteral -and ($leftNormalized -like $rightNormalized)) { return $true }

    if ($leftIsLiteral -and $rightIsLiteral) { return $false }
    # 一边是通配符、另一边是字面量，且互不匹配
    return $false
}

# 两个角色的写入域是否存在冲突
function Test-RoleConflict {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$LeftRole,
        [Parameter(Mandatory = $true)][string]$RightRole
    )

    if ($LeftRole -eq $RightRole) { return $true }

    $leftScopes = Get-RoleWriteScope -Role $LeftRole
    $rightScopes = Get-RoleWriteScope -Role $RightRole

    foreach ($left in $leftScopes) {
        foreach ($right in $rightScopes) {
            if (Test-WriteScopeConflict -Left $left -Right $right) { return $true }
        }
    }
    return $false
}

# 一组角色能否在同一阶段并行（两两不冲突，且不含只读角色）
function Get-RoleConflictPairs {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string[]]$Roles)

    $pairs = @()
    for ($i = 0; $i -lt $Roles.Count; $i++) {
        for ($j = $i + 1; $j -lt $Roles.Count; $j++) {
            if (Test-RoleConflict -LeftRole $Roles[$i] -RightRole $Roles[$j]) {
                $pairs += "$($Roles[$i]) 与 $($Roles[$j])"
            }
        }
    }
    return $pairs
}
