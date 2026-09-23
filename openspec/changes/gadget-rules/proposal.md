# Proposal: gadget 规则表（坐标 + 版本区间口径 + 外部规则文件）

## Why

上游 `jar-analyzer` 的 gadget 分析按 **jar 文件名** 判定：规则文件里一串 jar 名全部出现在
扫描目录里就报可用。这套口径有三个缺口，都会产生**看起来正常但结论是错的**判定：

- **不带版本约束**：`commons-collections-3.2.2.jar` 已修补 InvokerTransformer 的可用性，
  但「存在这个 jar」的判定会把它算成可用链；
- **不带 groupId**：同名 artifact 在不同 group 下可能是完全不同的库，只按文件名无法区分；
- **只报凑齐了什么**：不报「还差哪个组件」，使用者只能反复试一条注定构造不出来的链。

本工具此前只有一份 8 条的硬编码 gadget 清单，且判据是 «artifactId 相等»，
既无法表达版本区间，也无法在不改代码的前提下扩充；而「能分析更多 gadget」
恰恰是这类功能最有价值的扩展方向。

## What Changes

- 新增自研 gadget 规则表（`GadgetRule` / `GadgetRules`）：判据从「artifactId 相等」
  升级为 **groupId 前缀 + artifactId（支持通配）+ 版本区间（含排除版本）**，
  保留上游的 AND 语义（一条链的所有依赖必须同时满足）。
- 规则条数从 8 条扩到 40 余条，按类型分组：原生反序列化 / Hessian / JDBC 驱动 /
  fastjson / Jackson / toString 触发 / 模板与表达式 / 类加载器。
- 新增外部规则文件支持（`GadgetRuleFile`）：格式沿用上游 `gadget.dat` 的
  `jar名,…|类型|结果` 形态（**只读格式，不引入它的数据文件，避免 GPLv3 数据分发问题**），
  解析时把 jar 名翻译成坐标 + 版本区间，并支持 `!版本` 排除与 `*` 通配。
- 规则文件的解析问题**逐行进报告**而不是静默跳过：一条写错的规则会让使用者
  以为「这条链判定过了、目标没有」。
- 报告新增「可用 gadget（依赖口径）」段：按类型分组列可用链（含依据坐标、能力、下一步、直达页），
  并逐条列出缺失链与「还差什么」。
- 外部规则文件路径进配置页「漏洞分析配置」分组。

## Capabilities

### New Capabilities

- `analyze/gadget-rules`：依赖组合 → 可用链的判定口径、规则表与外部规则文件。

### Modified Capabilities

- `analyze/local-deps`：本地依赖分析报告新增 gadget 段。

## Non-goals

- **不引入上游的任何规则数据**：`jar-analyzer` 为 GPLv3，其 `gadget.dat` /
  `dfs-sink.json` / SCA 规则文件一律不进本仓库；本项目只借鉴「多依赖 AND 判定」这一思路，
  规则内容与判据实现全部自研。
- **不判定链是否可达**：依赖口径只能证明「gadget 在 classpath 上」。是否可达取决于有没有
  反序列化入口、入口参数能否被外部控制，仍需调用链分析（sink 命中 + 特征匹配）继续往上追；
  报告里明确写出这条边界。
- **不改动组件漏洞规则表**：`VulnerabilityRules` 的 25 条规则回答「组件自身有没有已知漏洞」，
  本 change 回答「依赖组合能构建哪些链」，两者不重复收录同一条结论。
- **不做自动化利用**：判定结果只用于指导使用者在 Payload / 预设链 / 恶意服务器页出载荷。
- **不改动其它页面与配置**：只在「漏洞分析配置」分组内新增一项。

## Impact

| 路径 | 写入域 | 动作 |
| --- | --- | --- |
| `src/analyzer/GadgetRule.java` | exploit | 新增：需求 / 版本区间 / 通配匹配与规则模型 |
| `src/analyzer/GadgetRules.java` | exploit | 新增：40 余条自研规则表 |
| `src/analyzer/GadgetRuleFile.java` | exploit | 新增：外部规则文件解析（jar 名 → 坐标 + 版本区间） |
| `src/analyzer/GadgetInventory.java` | exploit | 改：判定按坐标 + 版本区间，缺失项逐条列出，按类型分组渲染 |
| `src/analyze/AnalyzeEngine.java` | exploit | 改：两份分析报告都拼入 gadget 段，新增规则文件读取入口 |
| `src/ui/AnalyzeScanController.java` | ui | 改：分析前读规则文件，解析问题写入报告 |
| `src/ui/ConfigForm.java`、`src/ui/ConfigController.java` | ui | 改：新增「外部 gadget 规则文件」 |
| `tests/AnalyzeCheck.java` | 测试 | 改：版本排除、新坐标、规则文件语法与失败路径断言 |
| `README.md`、`README.en.md`、`PROGRESS.md`、`AI_REPORT.md` | 主 agent | 改：文档同步 |
