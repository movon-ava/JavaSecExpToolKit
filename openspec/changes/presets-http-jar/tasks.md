# Tasks

## 1. 模板与托管

写入域：`src/payload/JarPreset.java`、`src/service/OobJarService.java`（角色：exploit）

- [x] 1.1 Jar 包装类型与末端动作模板（含各自的参数键与默认值）；验证：`PayloadCheck` 断言通过
- [x] 1.2 链序固定为「载体 → 包装类型 → 字节码转换 → 末端动作」；验证：链文本断言通过
- [x] 1.3 空值参数不下发，避免覆盖引擎默认值；验证：空参数断言通过
- [x] 1.4 托管由同一 `ServiceManager` 实例完成启动与发布；验证：端到端取回合法 Jar
- [x] 1.5 产物做 Zip 魔数校验；验证：25 种组合断言全部为合法 Zip

## 2. 界面

写入域：`src/ui/OobJarPage.java`、`src/ui/OobJarController.java`、`src/ui/NavController.java`、
`src/ui/WorkbenchPages.java`、`src/ui/WidgetRegistry.java`、`src/Main.java`（角色：ui）

- [x] 2.1 新增带外 Jar 页视图与行为；验证：`UiNavigationCheck` 控件断言通过
- [x] 2.2 导航新增「HTTP 带外 Jar」二级项并接线懒加载；验证：导航与路由断言通过
- [x] 2.3 动作联动：不需要的输入框禁用、标签随动作变化、路径预填动作默认值；验证：联动断言通过
- [x] 2.4 参数校验拦在托管之前（端口、必填 URL）；验证：断言给出可读提示
- [x] 2.5 退出时停止托管并释放端口；验证：停止后端口可再次绑定

## 3. 配置

写入域：`src/ui/ConfigForm.java`、`src/ui/ConfigController.java`（角色：ui）

- [x] 3.1 新增「带外 Jar 配置」分组（绑定地址 / 端口 / 默认 URL / 默认路径 / 默认执行参数）；验证：配置页断言通过
- [x] 3.2 进页时下发默认值，端口缺省回落 50001；验证：端口默认值断言通过

## 4. 自检

写入域：`tests/**`（角色：测试）

- [x] 4.1 模板完备性断言（类型数、动作数、载体、链序、未知类型拒绝、参数组装）；验证：`PayloadCheck` 通过
- [x] 4.2 全部类型 × 动作组合可构建且产物为合法 Zip；验证：`PayloadCheck` 通过
- [x] 4.3 页面控件、动作联动与参数校验的界面断言；验证：`UiNavigationCheck` 通过
- [x] 4.4 端到端：真实托管 → 按返回地址取回 Jar → 校验 PK 魔数 → 停止后端口释放；验证：`UiSwitchEndToEndCheck` 通过

## 5. 收尾

写入域：`README*.md`、`AI_REPORT.md`、`PROGRESS.md`、`openspec/**`（角色：主 agent）

- [x] 5.1 中英 README 同步（导航 / 配置页 / 项目结构 / 测试）；验证：两处小节一一对应
- [x] 5.2 追加 AI 报告与进度；验证：两份文件含本轮改动
- [x] 5.3 构建并复验 JAR 时间晚于全部源文件；验证：`build.ps1` 输出
