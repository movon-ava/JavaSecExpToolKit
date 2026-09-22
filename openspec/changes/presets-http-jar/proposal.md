# Proposal: HTTP 带外 Jar

## Why

实战里把命令回显或内存马打进目标时，最常见的交付方式是让目标自己去拉一个 Jar：
JNDI 注入、反序列化落地、SPI 自动加载都走这条路。本工具此前只能生成载荷字节，
没有任何入口能把产物**托管成一个可访问的地址**——使用者只能自己起 HTTP 服务、
把 Base64 解出来存成文件再挂上去，中间每一步都可能出错（尤其是 Jar 必须保持合法 Zip 结构）。

同时，`src/payload/JarPreset.java` 与 `src/service/OobJarService.java` 已按实测结论落盘，
但**没有任何入口调用它们**：模板与托管逻辑都是死代码，界面上完全看不到。

## What Changes

- 新增二级项「Payload → HTTP 带外 Jar」：选 Jar 包装类型与末端动作，
  填 URL / 命令 / 落地路径 / 自定义目标类，一键生成并托管到本机 HTTP 服务，交回可访问地址。
- 末端动作与输入框联动：不需要的输入框直接禁用，避免填了不生效。
- 产物给出 Zip 魔数校验结论，拦截「地址通了但拿到空文件」。
- 新增可持久化配置进配置页「带外 Jar 配置」分组：默认绑定地址、监听端口、
  默认下载 / 回连 URL、默认落地路径、默认执行参数。

## Capabilities

### New Capabilities

- `presets/http-oob-jar`：Jar 包装类型 × 末端动作的产物生成与本地托管。

## Non-goals

- **不做反向连接服务**：不内置监听回连、不接收目标主动连回来的数据；只提供 HTTP 拉取一个方向。
- **不改动恶意服务器页**：五类服务的端口、启停与发布链路一律不动；本页用独立的托管实例。
- **不改动预设链页与生成页**：这两个页面的产出仍是载荷字节，不接入托管。
- **不做定时 / 自动托管**：托管与停止都由使用者显式触发。
- **不引入新第三方依赖**：仍只用 Java 标准库与 java-chains。

## Impact

| 路径 | 写入域 | 动作 |
| --- | --- | --- |
| `src/payload/JarPreset.java` | exploit | 已有：Jar 包装类型、末端动作与参数键模板 |
| `src/service/OobJarService.java` | exploit | 已有：生成 + 托管（同一实例启动与发布） |
| `src/ui/OobJarPage.java` | ui | 新增：带外 Jar 页视图 |
| `src/ui/OobJarController.java` | ui | 新增：托管、停止、复制地址、动作联动 |
| `src/ui/NavController.java` | ui | 改：新增「HTTP 带外 Jar」二级项 |
| `src/ui/WorkbenchPages.java` | ui | 改：懒加载入口、默认值下发、退出时停止托管 |
| `src/ui/ConfigForm.java`、`src/ui/ConfigController.java` | ui | 改：「带外 Jar 配置」分组与读写 |
| `src/ui/WidgetRegistry.java`、`src/Main.java` | ui | 改：登记控件、路由、装配 |
| `tests/PayloadCheck.java` | 测试 | 改：模板完备性与产物 Zip 魔数断言 |
| `tests/UiNavigationCheck.java` | 测试 | 改：页面控件、动作联动、参数校验断言 |
| `tests/UiSwitchEndToEndCheck.java` | 测试 | 改：真实托管 → HTTP 取回 Jar → 停止后端口释放 |
| `README.md`、`README.en.md`、`AI_REPORT.md`、`PROGRESS.md` | 主 agent | 改：文档同步 |
