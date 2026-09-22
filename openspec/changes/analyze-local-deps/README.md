# analyze-local-deps

漏洞分析：C 打底（本地依赖坐标 + 内置规则判定）与 B 深挖（外部引擎调用图 + Python 只读查询）+ 反编译。

包含两个 capability：

- `analyze/local-deps`：本地依赖坐标读取与规则判定
- `analyze/call-graph`：外部引擎建库与数据库查询

界面（`src/ui/AnalyzePage.java`、`src/ui/AnalyzeController.java`、导航与配置分组）在另一个 change
`ui-analyze-view` 中，因为写入域不同（exploit vs ui），按 `openspec/config.yaml` 的
「跨两个以上写入域的改动必须拆成多个 change」拆开。