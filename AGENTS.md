# AGENTS.md

本文件约束 `JavaSecExpToolKit` 的开发工作。作用域：本仓库根目录及其所有子目录。
子目录若新增 `AGENTS.md`，以更深的文件为准；用户当次指令优先于本文件。



每次给我的回复必须是中文

---

### 项目结构约束

除了规定放在根目录中的文件外，其他文件应当放在属于各自功能的目录中

Java代码应该按功能进行分配，不能堆叠在一个文件中，要符合合适的框架架构，例如MVC等



每次完成一个阶段后，自动更新一个 [PROGRESS.md](http://PROGRESS.md) 或 [TODO.md](http://TODO.md)。



### Git管理

[https://github.com/movon-ava/JavaSecExpToolKit.git](https://github.com/movon-ava/JavaSecExpToolKit.git)

这是项目的git地址，每次版本更新或者功能更新要进行合理的git管理



&nbsp;

### 一、 范围与文件权限约束（Scope &amp; File Safety）

- **越界禁止（No Out-of-Scope Modifications）**：
  - 约束 AI：*“严禁修改当前工作目录或指定范围之外的文件。如果某个修改需要牵连其他文件，必须先向我描述方案并征得同意，再动手。”*
- **禁止凭空捏造依赖（No Unplanned Dependencies）**：
  - 约束 AI：*“未经允许，绝对不允许在 package.json、requirements.txt 或 pom.xml 中引入新的第三方库。必须使用现有项目已有的库来完成功能。”*

---

### 二、 代码风格与架构约束（Coding Style &amp; Architecture）

- **技术栈与版本锁死**：
  - 约束 AI 严格遵守当前项目的特定版本（例如：“本项目使用 Next.js 14 App Router，严禁使用旧版的 Pages Router 写法”）。
- **规范与设计模式**：
  - 规定命名规范（如驼峰、下划线）、错误处理方式（如统一抛出自定义异常而不是直接 print 或 console.log）。
- **禁止编写“偷懒代码”**：
  - 约束 AI：*“禁止使用 TODO、pass 或在代码里写‘此处省略实现’，所有生成的代码必须是完整、可运行、可生产落地的（Production-ready）。”*

---

### 三、 调试与测试约束（Debugging &amp; Testing）

- **先诊断后动手（Think Before Coding）**：
  - 约束 AI 在面对 Bug 时：*“在修改代码前，必须先列出导致该 Bug 的根本原因（Root Cause分析），严禁盲目尝试（Trial and Error）修改。”*
- **测试驱动或自测约束**：
  - 约束 AI：*“为新写的功能或修复的 Bug 补充对应的单元测试（Unit Test），并且在修改完成后，必须确保项目能够通过编译和现有测试。”*


## 规格驱动开发（OpenSpec）

本仓库已引入 OpenSpec，规格与变更记录在 `openspec/` 下：

- `openspec/specs/`：能力的现行规格，是需求的事实源。
- `openspec/changes/`：待实施的变更规划件；`changes/archive/` 为已归档变更。
- `openspec/config.yaml`：项目上下文与每类规划件的强制规则，跨角色约束写在这里，
  避免在多处重复维护。

多 Agent 协同的角色划分与写入域矩阵见 `docs/DESIGN-agents.md`；
Java 模块化的分阶段方案见 `docs/DESIGN-modularization.md`；
payload 生成功能的方案见 `docs/DESIGN-payload.md`。

改动跨文件时先有 change 规划件再动手；验证入口与收尾三项要求已写入
`openspec/config.yaml` 的 `rules` 与 `operations`。


### 功能

新功能的添加要将一些可以永久保存的配置放进配置页中

