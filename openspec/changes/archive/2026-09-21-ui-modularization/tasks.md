# Tasks

## 1. 解除测试耦合（先做，否则拆不动）

- [x] 1.1 新增 `src/ui/UiHandle.java`：先查登记表，再沿继承链反射兜底；
  验证：把控件从界面类挪走后，按名字取用仍能成功
- [x] 1.2 新增 `src/ui/WidgetRegistry.java`：登记 90 余项控件，
  展开后的导航序列用取值器登记；验证：展开 / 收起后断言读到的是最新序列而非快照
- [x] 1.3 三个 UI 自检的 `fieldQuiet` / `read` / `hasDeclaredField` 改为调用门面；
  验证：`UiNavigationCheck` / `UiShiroCheck` / `UiSwitchEndToEndCheck` 全绿，
  且断言数量仍为 169 / 27 / 95

## 2. 视图侧控件工厂

- [x] 2.1 `ProbePage` / `CapturePage` / `ShiroPage` / `ProxyPage` 各新增 `defaults()`；
  验证：控件初值与拆分前逐项一致（`ProxyServerCheck`、`UiShiroCheck` 覆盖端口与密钥默认值）
- [x] 2.2 新增 `ConfigForm`：38 项控件 + 10 个分组清单；
  验证：`UiNavigationCheck` 中配置页 10 个分组断言全部通过
- [x] 2.3 `ConfigPage` 新增 `widgets(form, onSave, onReset)` 装配助手；
  验证：配置页可打开且保存 / 重置按钮可用

## 3. 页面行为搬到控制器

- [x] 3.1 `NavController`：侧边栏构建、展开收起、选中、路由回调；
  验证：`UiNavigationCheck` 中 6 项 / 7 项展开收起断言通过
- [x] 3.2 `ProbeController`：模式勾选、参数拼装、引擎调用、阶段字段联动；
  验证：`modeKeys` 五种组合断言与 `UiSwitchEndToEndCheck` 探测链路通过
- [x] 3.3 `CaptureController`：抓包 / 转换 / 复制 / 一键发送 / 代理流量导入；
  验证：`UiSwitchEndToEndCheck` 抓包链路与「抓包头陷阱过滤」通过
- [x] 3.4 `ShiroController`：检测 / 爆破 / 生成载荷 / 执行命令；
  验证：`UiShiroCheck` 全绿，页签数仍为 4
- [x] 3.5 `ProxyController`：启停 / 拦截放行 / 流量回填 / 导出；
  验证：`UiSwitchEndToEndCheck` 代理链路 20 余条断言通过
- [x] 3.6 `ConfigController`：配置读 / 写 / 下发、服务器默认值、下拉框助手；
  验证：配置页保存后各页控件按新值生效
- [x] 3.7 `WorkbenchPages`：Payload / 预设链 / 恶意服务器三页懒加载与互发；
  验证：`UiNavigationCheck` 服务页与预设链页断言通过

## 4. 收敛组合根

- [x] 4.1 `Main` 只保留：`main`、构造、窗口配置、导航构建、页面路由、控制器装配、
  自检转发方法；验证：`wc -l src/Main.java` ≤ 400（实测 311）
- [x] 4.2 依赖边界审计仍通过；验证：`python tools/audit_boundary.py` 结论「全部通过」

## 5. 回归与收尾

- [x] 5.1 六套 Java 自检全部退出码 0，断言数与拆分前一致；
  验证：37 / 46 / 61 / 27 / 169 / 95
- [x] 5.2 `python -m unittest discover -s tests` 全绿（99 项）；
  验证：输出 `OK`，失败数 0
- [x] 5.3 `build.ps1` 成功且 JAR 时间晚于全部源文件；
  验证：脚本输出 JAR 时间与「Source freshness checked」计数
- [x] 5.4 备份到 `.backups/`（仅保留最近三次）、追加 `AI_REPORT.md`、更新 `PROGRESS.md`
  与中英 README 的项目结构；验证：`.backups/` 恰好三个时间戳目录
- [x] 5.5 运行 `openspec validate --all --strict`；验证：退出码 0，passed 数增加

## 6. 监督复核

- [x] 6.1 按 `docs/AGENT-ROLES.md` 监督清单独立复核：复算断言数量、
  复跑依赖边界审计、复核 JAR 时间，不复用实现者结论；
  验证：复核结论写入 `AI_REPORT.md` 本轮章节
