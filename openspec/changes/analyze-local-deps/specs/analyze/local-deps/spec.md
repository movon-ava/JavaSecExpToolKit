# Spec Delta: analyze/local-deps

## Purpose

规定「本地依赖分析」的能力：从一个 jar 或依赖目录里读出 Maven 坐标，
按来源分层采信，再按内置规则判定已知漏洞，并给出可执行的下一步。

## ADDED Requirements

### Requirement: 从 jar 读取依赖坐标

系统 SHALL 从所选目标读取依赖坐标，并对单个 jar、依赖目录、源码工程 `pom.xml` 三种输入
都给出结论；读取过程 MUST NOT 执行目标代码。

#### Scenario: 单个 jar

- **WHEN** 使用者选择一个 jar 并执行本地分析
- **THEN** 报告列出该 jar 的组件坐标
- **AND** 结论中给出坐标的来源

#### Scenario: 依赖目录

- **WHEN** 使用者选择一个目录
- **THEN** 系统递归取出其中全部 jar 并逐个分析
- **AND** 报告给出本次分析的 jar 数量

#### Scenario: 不加载目标代码

- **WHEN** 目标 jar 内含恶意类
- **THEN** 分析过程中不加载任何目标类
- **AND** 不把 jar 解压落盘

#### Scenario: 损坏的 jar 不中断分析

- **WHEN** 目录中某个 jar 不是合法 Zip
- **THEN** 跳过该文件并继续分析其余文件
- **AND** 整体分析仍然给出报告

### Requirement: 坐标来源决定可信度

系统 SHALL 按打包产物的可信度分层采信坐标，并把来源一路带到结论里。

#### Scenario: pom.properties 优先

- **WHEN** 同一 jar 内既有 `pom.properties` 又有 `pom.xml`
- **THEN** 取 `pom.properties` 的坐标

#### Scenario: 只能靠文件名时的降级

- **WHEN** jar 内没有任何坐标声明，只能从文件名推断
- **THEN** 结论被标注为最低可信度
- **AND** 报告说明该判定建立在推断之上

### Requirement: 版本区间判定

系统 SHALL 按逐段比较的方式比较版本，并支持「低于 / 不高于 / 区间」三种区间形态。

#### Scenario: 多位数段不能按字符串比较

- **WHEN** 比较 `1.2.80` 与 `1.2.9`
- **THEN** 判定 `1.2.80` 更大

#### Scenario: 预发布版小于正式版

- **WHEN** 比较 `1.0-rc1` 与 `1.0`
- **THEN** 判定 `1.0-rc1` 更小

#### Scenario: 无法比较的版本不做判定

- **WHEN** 依赖的版本号为空或无法解析
- **THEN** 不命中任何规则
- **AND** 报告不出现基于该依赖的漏洞结论

### Requirement: 规则表与结论

系统 SHALL 内置一组组件到漏洞的规则，每条规则给出判定依据、成立前提与下一步动作。

#### Scenario: 命中规则

- **WHEN** 依赖的坐标与版本落在某条规则的区间内
- **THEN** 报告列出该规则的标题、区间依据与建议动作

#### Scenario: 不同 group 的同名组件不误报

- **WHEN** 依赖的 artifactId 与规则同名但 groupId 不同
- **THEN** 不命中该规则

#### Scenario: 未命中时的说明

- **WHEN** 全部依赖都没有命中任何规则
- **THEN** 报告说明「未命中不代表目标不存在漏洞」

#### Scenario: 结论按可信度排序

- **WHEN** 报告含多条结论
- **THEN** 可信度高的排在前面

### Requirement: 下一步动作可直达

系统 SHALL 为每条结论给出可执行的下一步，并允许使用者从报告直接跳到对应功能页。

#### Scenario: 跳转建议

- **WHEN** 报告包含需要后续利用的结论
- **THEN** 报告给出对应的功能页入口
- **AND** 点击入口打开该功能页

#### Scenario: 无建议时不显示空按钮

- **WHEN** 结论没有对应的后续动作
- **THEN** 不给出跳转入口

### Requirement: 源码工程声明与产物口径对照

系统 SHALL 支持读取源码工程的 `pom.xml`，并说明声明与生效的区别。

#### Scenario: 读工程声明

- **WHEN** 使用者给出一个工程的 `pom.xml`
- **THEN** 报告列出该工程声明的依赖
- **AND** 说明「声明不等于最终生效」

#### Scenario: 无依赖声明的工程

- **WHEN** 给出的 `pom.xml` 内没有依赖声明
- **THEN** 给出可读提示而不是空报告

#### Scenario: 外部实体不被解析

- **WHEN** 给出的 `pom.xml` 含外部实体声明
- **THEN** 解析不请求任何外部资源

### Requirement: 本地分析不依赖外部程序

系统 SHALL 在没有任何外部程序的情况下完成本地依赖分析。

#### Scenario: 未配置外部引擎

- **WHEN** 未配置外部引擎路径
- **THEN** 本地依赖分析仍然可用
- **AND** 界面提示外部引擎为可选

#### Scenario: 耗时在秒级

- **WHEN** 对单个 jar 执行本地依赖分析
- **THEN** 报告给出本次耗时
- **AND** 该耗时以毫秒计