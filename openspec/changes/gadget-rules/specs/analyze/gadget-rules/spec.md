# Spec Delta: analyze/gadget-rules

## Purpose

规定「依赖组合 → 可用 gadget 链」的判定口径：判据必须是 Maven 坐标与版本区间，
而不是 jar 文件名；缺失链必须给出补齐所需的组件；结论必须声明它只证明 classpath 上具备。

## ADDED Requirements

### Requirement: 判据是坐标与版本区间

系统 SHALL 按 groupId 前缀、artifactId 与版本区间判定依赖需求是否满足，
且一条链的全部依赖必须同时满足才判为可用。

#### Scenario: 版本已修复则不判为可用
- **WHEN** 仅存在已修补该链可用性的版本
- **THEN** 该链不被判为可用

#### Scenario: 版本落在区间内
- **WHEN** 依赖版本落在规则声明的区间内且坐标匹配
- **THEN** 该链被判为可用并给出命中的坐标

#### Scenario: 版本不可比较
- **WHEN** 依赖版本无法解析或缺失而规则限定了版本
- **THEN** 该需求判为不满足，不按「可能匹配」放行

#### Scenario: artifactId 支持通配
- **WHEN** 规则以通配形式声明 artifactId
- **THEN** 形式匹配的坐标被计入

#### Scenario: 版本排除
- **WHEN** 依赖版本等于规则声明的排除版本
- **THEN** 该需求判为不满足

#### Scenario: groupId 缺失时放行并如实标注
- **WHEN** 依赖只有 artifactId 没有 groupId（来自文件名推断）
- **THEN** 该需求仍可满足
- **AND** 报告里的来源列标出该依赖来自文件名推断

### Requirement: 判定结论可回溯且给出下一步

系统 SHALL 为每条可用链给出命中的依赖坐标、该链的能力说明与在本工具内的下一步动作。

#### Scenario: 可用链给出依据
- **WHEN** 一条链被判为可用
- **THEN** 报告给出它命中的坐标与能力说明

#### Scenario: 可用链给出直达入口
- **WHEN** 该链在工具内有对应功能页
- **THEN** 报告给出该功能页的跳转标识

### Requirement: 缺失链给出补齐清单

系统 SHALL 列出未满足的链，并逐条说明还缺哪个组件（含坐标与版本区间）。

#### Scenario: 缺失链列出缺口
- **WHEN** 某条链的部分依赖未引入
- **THEN** 报告在该链下列出未满足的需求描述

#### Scenario: 全部具备时也有明确结论
- **WHEN** 全部内置规则都满足
- **THEN** 报告明确说明不存在缺失链

### Requirement: 结论声明可达性边界

系统 SHALL 在 gadget 段中说明该判定只证明组件在 classpath 上，不代表链可达，
并指向继续追查的入口。

#### Scenario: 报告含边界说明
- **WHEN** 查看 gadget 段
- **THEN** 段末给出「不等于可达」的说明与后续追查入口

### Requirement: 外部规则文件

系统 SHALL 支持由配置指定的外部规则文件扩充判定，规则按行声明，
每行给出依赖清单、类型与结论，且解析问题必须逐行上报。

#### Scenario: 外部规则参与判定
- **WHEN** 配置了合法的外部规则文件
- **THEN** 其中的规则参与判定，并在报告中标注规则来源与条数

#### Scenario: 带版本的依赖名
- **WHEN** 规则行里的依赖名带版本段
- **THEN** 该版本被解析成区间上界

#### Scenario: 排除版本
- **WHEN** 规则行里的依赖名带排除标记
- **THEN** 该版本被解析成排除项

#### Scenario: 坏行逐行上报
- **WHEN** 规则文件里有无法解析的行
- **THEN** 报告逐行给出该行的问题与行号
- **AND** 合法行仍然生效

#### Scenario: 规则文件缺失或留空
- **WHEN** 配置的规则文件不存在
- **THEN** 报告给出可读问题并继续只用内置规则
- **WHEN** 配置留空
- **THEN** 只用内置规则且不报错
