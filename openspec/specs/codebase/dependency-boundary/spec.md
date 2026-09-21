# codebase/dependency-boundary Specification

## Purpose
规定本仓库 Java 代码的包级依赖边界，使「是否被正确解耦」成为可机械判定的客观事实，
而不是依赖人工阅读得出的主观结论。边界用于在改动中自动拦截依赖退化。

## Requirements

### Requirement: 包级依赖必须无环

系统的 Java 包依赖图 SHALL 不含任何环，包括两个包互相依赖，
以及三个及以上包构成的更长的依赖回路。

#### Scenario: 检查现有代码
- **WHEN** 对 `src/` 下全部 Java 源文件做依赖分析
- **THEN** 包级依赖图中不存在任何双向边

#### Scenario: 新增反向依赖被拦截
- **WHEN** 某次改动引入了 `a -> b` 且已存在 `b -> a`
- **THEN** 依赖边界自检失败并指出构成环的那条新增引用

### Requirement: 包级依赖必须符合声明分层

系统 SHALL 只允许声明过的包间依赖方向。允许的方向为：
`ui` 依赖 `probe` / `proxy` / `shiro` / `config` / `util`；
`probe` 与 `shiro` 依赖 `util`；
`config`、`proxy`、`util` 不得依赖其它项目包。

#### Scenario: 叶子层保持无出边
- **WHEN** 检查 `config`、`proxy`、`util` 三个包的 import 与限定名引用
- **THEN** 三者对其它项目包的依赖数为零

#### Scenario: 未声明的依赖方向被拒绝
- **WHEN** 某次改动让 `util` 依赖了 `shiro`
- **THEN** 自检失败并给出该依赖所在的文件与行号

### Requirement: 通用组件不得依赖具体功能模块

系统的通用组件 SHALL 不依赖任何具体功能模块，使其可在不同功能之间复用。

通用性判定标准：该组件封装的是与具体漏洞类型无关的机制（序列化、编解码、链式构建），
而非某一漏洞的专用逻辑。

#### Scenario: 通用链引擎可独立复用
- **WHEN** 检查 `shiro` 包中的 java-chains 封装层
- **THEN** 该文件不出现对 `ShiroEngine` 的任何引用
- **AND** 其 Base64 编码能力来自共享内核而非具体功能模块

### Requirement: 共享内核保持单向被依赖

系统的共享内核 `src/util/` 与 `src/config/` SHALL 只被依赖，不反过来依赖调用者。

#### Scenario: 内核对上层无感知
- **WHEN** 检查 `src/util/` 与 `src/config/` 下所有文件
- **THEN** 其中不出现对 `probe`、`proxy`、`shiro`、`ui` 包的引用

### Requirement: 边界检查必须可机械执行

系统 SHALL 提供一条命令即可运行的依赖边界检查，其结论不依赖人工判断。

#### Scenario: 一条命令得到结论
- **WHEN** 运行 Python 单元测试
- **THEN** 依赖边界检查作为其中一个用例执行
- **AND** 违反任一边界规则时该用例失败并指出具体位置
