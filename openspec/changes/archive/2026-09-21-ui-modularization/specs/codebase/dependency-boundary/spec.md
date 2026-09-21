# Spec Delta: codebase/dependency-boundary

## Purpose

规定本仓库 Java 代码的包级依赖边界，使「是否被正确解耦」成为可机械判定的客观事实。
本次补充界面层的结构约束：页面行为必须与视图分离，单文件规模必须有上限，
自检必须通过稳定门面取控件。

## MODIFIED Requirements

### Requirement: 包级依赖必须符合声明分层

系统 SHALL 只允许声明过的包间依赖方向。允许的方向为：
`ui` 依赖 `probe` / `proxy` / `shiro` / `config` / `util`；
`probe` 依赖 `util`；`config`、`proxy`、`util` 不得依赖其它项目包。

#### Scenario: 叶子层保持无出边
- **WHEN** 检查 `config`、`proxy`、`util` 三个包的 import 与限定名引用
- **THEN** 三者对其它项目包的依赖数为零

#### Scenario: 未声明的依赖方向被拒绝
- **WHEN** 某次改动让 `util` 依赖了 `shiro`
- **THEN** 自检失败并给出该依赖所在的文件与行号

#### Scenario: 界面控制器之间无反向依赖
- **WHEN** 检查 `src/ui/` 下各控制器的构造参数与字段类型
- **THEN** 依赖方向只允许「抓包 → 探测 / Shiro」「代理 → 抓包 → 配置」，
  不存在两个控制器互相持有对方实例

## ADDED Requirements

### Requirement: 页面行为必须与视图分离

系统的每个功能页 SHALL 把「界面结构」与「行为」放在不同类中：
视图类只描述结构与控件初值、不发起网络或引擎调用；行为类只操作控件、不创建控件。

#### Scenario: 视图类不持有执行逻辑
- **WHEN** 检查 `src/ui/*Page.java` 的方法与调用
- **THEN** 其中不出现网络请求、进程启动、引擎调用，也不出现可变状态字段

#### Scenario: 行为类不创建控件
- **WHEN** 检查 `src/ui/*Controller.java`
- **THEN** 控件全部来自构造参数注入，类内不出现 `new JButton` / `new JTextField` 一类控件构造

### Requirement: 单个界面文件规模必须有上限

系统的单个 Java 界面源文件 SHALL 不超过 600 行，组合根 SHALL 不超过 400 行，
避免再次出现「一个文件承担全部页面职责」的退化。

#### Scenario: 组合根规模
- **WHEN** 统计 `src/Main.java` 行数
- **THEN** 不超过 400 行（实测 311 行）

#### Scenario: 界面文件规模
- **WHEN** 统计 `src/ui/` 下每个 `.java` 文件行数
- **THEN** 每个文件都不超过 600 行

### Requirement: 自检必须通过稳定门面取控件

系统的界面自检 SHALL 通过名字到控件的映射取用控件，
不得直接对界面类的字段做 `getDeclaredField`，使界面内部拆分不影响断言。

#### Scenario: 拆分界面内部不动断言
- **WHEN** 把控件从界面类移到登记表或另一个类
- **THEN** 自检的断言文字、数量与通过状态均不变

#### Scenario: 未登记的控件名报错而非静默为空
- **WHEN** 自检请求一个既不在登记表、也不在任何父类字段里的名字
- **THEN** 抛出异常并给出该名字，而不是返回 `null` 让断言变成恒真
