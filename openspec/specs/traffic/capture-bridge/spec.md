# 抓包格式直接探测能力

## Purpose

把代理或单次抓包得到的原始报文，在不手工清洗的前提下直接交给探测或利用功能使用，
目标是让「抓到的包」与「发出去的包」在语义上等价。

## Requirements

### Requirement: 请求头容错解析

系统 SHALL 接受多种请求头输入形式，并在任一种形式下都还原出等价的请求头集合。

支持的输入形式：标准 `Key: Value` 文本、JSON 对象、以及不含冒号的裸 Cookie 值。

#### Scenario: 裸 Cookie 值被识别为 Cookie 头
- **WHEN** 输入为 `JWT_TOKEN=abc.def; JSESSIONID=xyz`（无冒号）
- **THEN** 解析结果包含 `Cookie: JWT_TOKEN=abc.def; JSESSIONID=xyz`
- **AND** 该行不被丢弃

#### Scenario: JSON 对象输入
- **WHEN** 输入为 `{"Cookie":"JWT=xxx","X-Token":"yyy"}`
- **THEN** 解析结果包含两个对应请求头

#### Scenario: 标准冒号形式
- **WHEN** 输入为 `Cookie: JWT=xxx`
- **THEN** 解析结果包含 `Cookie: JWT=xxx`

### Requirement: 请求头净化

系统 SHALL 在转发抓包结果前剔除会破坏探测的请求头，且不得因此改变其余头的语义。

必须剔除或改写的头：`Content-Length`（由实际请求体重新计算）、
`Accept-Encoding`（改写为 `identity`）、以及 `Connection` 等逐跳头。

#### Scenario: 残留 Content-Length 不影响探测
- **WHEN** 抓包头含与实际请求体不符的 `Content-Length: 9999`
- **THEN** 发出的请求不携带该值
- **AND** 探测正常返回结论，不出现超时

#### Scenario: gzip 声明被改写
- **WHEN** 抓包头含 `Accept-Encoding: gzip`
- **THEN** 发出的请求改为 `Accept-Encoding: identity`
- **AND** 各探针不再返回同质化响应

### Requirement: 请求体一并转发

系统 SHALL 在转发抓包结果时同时转发原始请求体，并按原始方法决定是否发送请求体。

#### Scenario: POST 请求体被复现
- **WHEN** 抓到的包是携带 JSON 体的 POST 请求
- **THEN** 转发时按原方法发送同样的请求体
- **AND** 请求头沿用抓包时的 `Content-Type`，不强制改写为表单类型

#### Scenario: GET 不被降级为 POST
- **WHEN** 抓到的包是 GET 请求
- **THEN** 转发时仍以 GET 发出，不因携带空请求体而变成 POST

### Requirement: 观察模式下可导出抓包

系统 SHALL 在未开启请求拦截时同样记录最近一次请求，使抓包结果可被导出。

#### Scenario: 未勾选拦截仍可转发
- **WHEN** 代理处于观察模式（未勾选「拦截请求」）且已流过至少一个请求
- **THEN** 点击「转发到抓包转换」能导出最近一次请求
- **AND** 不出现「还没有可转换的请求包」提示

#### Scenario: 无流量时给出明确提示
- **WHEN** 代理启动后尚无任何请求流过
- **THEN** 导出操作给出明确提示而不是静默失败

### Requirement: 敏感头不进入报告

系统 SHALL 不在探测报告中回显完整 Cookie 或凭据值。

#### Scenario: 报告脱敏
- **WHEN** 探测请求携带会话 Cookie
- **THEN** 报告与日志中不出现该 Cookie 的完整值