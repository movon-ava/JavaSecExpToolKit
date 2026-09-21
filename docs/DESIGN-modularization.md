# JavaSecExpToolKit Java 模块化设计

版本：1.0.0
更新日期：2026-09-21（已完成实施，见第九节实测结果）
适用读者：本仓库维护者
关联文档：`docs/DESIGN.md`（总设计）、`docs/DESIGN-agents.md`（多 agent 框架）

---

## 一、结论先行

**`Main.java` 确实过大**，但它不是「一个需要重写的烂文件」，
而是「一个承担了过多职责的装配点」。拆分已按本文方案完成：
`Main.java` 从 1757 行降到 311 行，行为零变化（六套 Java 自检 + 99 项 Python 测试全绿）。
实施结果与两处对原方案的修正见第九节。

判断「过大」的依据不是行数本身，而是三个实测指标同时越线：

| 指标 | 实测值 | 经验阈值 | 结论 |
| --- | ---: | ---: | --- |
| 单文件行数 | 1533 | 600 | 越线 2.5 倍 |
| 单文件方法数 | 70 | 30 | 越线 2.3 倍 |
| 单文件 `private final` 字段 | 100 | 20 | 越线 5 倍 |

而问题的实质在第 100 个字段上：这些字段是**跨 5 个功能页共享的可变状态**。
行数只是表象，字段数才说明职责耦合。

---

## 二、现状测量

### 2.1 模块规模（实测）

| 模块 | 行数 | 文件数 |
| --- | ---: | ---: |
| `src/(root)` = `Main.java` | 1533 | 1 |
| `src/ui` | 1463 | 10 |
| `src/shiro` | 1176 | 3 |
| `src/proxy` | 768 | 1 |
| `src/util` | 333 | 3 |
| `src/probe` | 310 | 3 |
| `src/config` | 47 | 1 |
| **合计** | **5630** | **22** |

### 2.2 `Main.java` 的职责分布（按方法归属统计，实测）

| 职责簇 | 行数 | 代表方法 |
| --- | ---: | --- |
| 代理页 | 278 | `toggleProxy`、`forwardIntercepted`、`exportProxyDetail` |
| 配置页 | 264 | `loadConfig`、`saveConfigFromForm`、`applyConfigToForms` |
| 抓包转换页 | 240 | `startCapture`、`startConvert`、`sendCaptureTo` |
| Shiro 页 | 236 | `startShiroDetect`、`startShiroCrack`、`buildShiroPayload` |
| 导航与框架 | 213 | `navigation`、`selectNav`、`rebuildNavigation` |
| Fastjson 探测页 | 61 | `startDetection`、`modeKeys` |
| 未归类（构造与 `main`） | 34 | `Main`、`main` |
| **方法体合计** | **1326** | |
| 字段声明 | 约 100 行 | |
| import 与包声明 | 约 40 行 | |

### 2.3 已经做对的部分

`src/ui/` 下的 `ShiroPage`、`CapturePage`、`ConfigPage`、`ProbePage`、`ProxyPage`
已经是**静态视图构建器**：它们通过 `Widgets` 结构体接收控件引用，
通过 `build(widgets, fonts)` 返回装配好的面板，自身不持有任何状态。

例如 `src/ui/ShiroPage.java` 的注释明确写着「只负责界面结构……本页不持有任何执行状态」。
这说明拆分方向早就定了，只是**停在了半路**：视图抽出去了，状态和行为都还留在 `Main`。

所以本次模块化的性质是**完成既有方向**，而非另起炉灶。

---

## 三、拆分前的硬约束：测试反射耦合

这是必须先处理的前置问题，否则拆分无法进行。

### 3.1 事实

`tests/UiNavigationCheck.java`、`UiShiroCheck.java`、`UiSwitchEndToEndCheck.java`
通过反射直接读写 `Main` 的成员，实测访问 **80 个成员**：

```java
private static Object fieldQuiet(Object target, String name) {
    Field field = target.getClass().getDeclaredField(name);   // 只查本类
    field.setAccessible(true);
    return field.get(target);
}
```

**关键在于 `getDeclaredField` 只搜索本类，不含父类。**
一旦把字段或方法移到新类（编译器可能生成合成访问器，但 `getDeclaredField` 解析的是名字），
这些断言会立刻以 `IllegalStateException` 失败。

### 3.2 处置方案（三选一，推荐 A，实际执行了 A + B 的组合）

**方案 A：测试改走稳定门面（推荐）**
新增包级可见的测试门面类（例如 `src/ui/UiHandle.java`），
以稳定的字符串键暴露控件：

```java
public final class UiHandle {
    public static javax.swing.JComponent widget(String key) { ... }
    public static java.util.List<String> modeKeys() { ... }
}
```

测试改为 `UiHandle.widget("shiro.url")`，不再依赖字段名与所属类。
代价：需要一次性改写三个测试文件的取数辅助方法（约 80 处调用点），
但之后任何内部拆分都不再影响测试。

**方案 B：测试沿继承链查找**
把 `fieldQuiet` 改为逐级 `getSuperclass()` 搜索。
代价极小（改 3 个文件里的 3 个辅助方法），但只是把问题推迟，
且仍强绑定字段名，字段改名即失效。

**方案 C：不改测试**
拆分时把这 80 个成员全部留在 `Main` 里。
这等于放弃拆分——字段正是要搬走的东西。

**结论**：先做方案 B 保证不阻塞（成本 10 分钟），
再随第一阶段一起做方案 A（根治）。两者不冲突。

---

## 四、目标结构

拆分后每个页面拥有自己的状态与行为，`Main` 退回为装配器与导航控制器。

```
src/
  Main.java                  约 350 行  仅：启动、导航、页面切换、字体缩放
  ui/
    UiKit.java               既有，共享样式
    NavItem.java             既有
    NavigationRenderer.java  既有
    HomePage.java            既有
    FlowRenderer.java        既有
    capture/
      CapturePage.java       既有（视图）
      CaptureController.java 新增：startCapture / convert / sendTo
      CaptureState.java      新增：14 个抓包字段
    proxy/
      ProxyPage.java         既有（视图）
      ProxyController.java   新增：toggle / forward / drop / export
      ProxyState.java        新增：11 个代理字段
    probe/
      ProbePage.java         既有（视图）
      ProbeController.java   新增：startDetection / modeKeys
      ProbeState.java        新增：探测表单字段
    shiro/
      ShiroPage.java         既有（视图）
      ShiroController.java   新增：detect / crack / build / run
      ShiroState.java        新增：23 个 Shiro 字段
    config/
      ConfigPage.java        既有（视图）
      ConfigController.java  新增：load / apply / reset / save
      ConfigState.java       新增：29 个配置字段
```

字段归属已按实测前缀归类：配置 29、Shiro 23、抓包 14、代理 11、DNS 4、探测模式 3、CEYE 1。

**包结构选择说明**：采用 `ui/capture/` 而非平铺的 `ui/CaptureController`，
是因为拆完后 `ui` 根部会有 15 个文件，按功能再分一层更易导航。
注意 `src/ui/shiro/` 与已有的 `src/shiro/` 包名不同名不冲突
（Java 中 `ui.shiro` 与 `shiro` 是不同包），但为避免阅读混淆，
实现时建议用 `ui/shiropage/` 之类的名字，由实施者按实际观感定。

---

## 五、分三阶段实施

每阶段独立可验收、可回滚。阶段之间不要求连续完成，可穿插功能开发。

### 第一阶段：解除约束 + 抽离最独立的一页

**目标**：验证拆分方法可行，风险最低。

1. 测试辅助方法改为沿继承链查找（方案 B），确保后续搬迁不阻塞。
2. 抽出 `ui/proxy/ProxyController` + `ProxyState`。
   选代理的理由：278 行、依赖面最窄（只用 `ProxyServer`），
   且已有 `ProxyServerCheck` 独立自检，改动可从外部验证。
3. 验收：`ProxyServerCheck`、`UiNavigationCheck`、`UiShiroCheck`、
   `UiSwitchEndToEndCheck` 全绿；手动跑一次代理启停与转发。

**风险**：`exportProxyDetail` 依赖 `lastProxyFlow`，搬迁时需保证
观察模式与拦截模式的赋值路径都跟着走（`src/Main.java:599` 附近）。

### 第二阶段：抽离其余三页 + 建立测试门面

**目标**：`Main` 降到 600 行以内。

1. 抽出 `ui/config/ConfigController` + `ConfigState`（264 行，29 字段）。
2. 抽出 `ui/capture/CaptureController` + `CaptureState`（240 行，14 字段）。
3. 抽出 `ui/shiro/ShiroController` + `ShiroState`（236 行，23 字段）。
4. 实现方案 A 的 `UiHandle` 门面，把三个 UI 自检从字段名改为稳定键。
5. 验收：五套 Java 自检 + Python 全套通过；
   `UiNavigationCheck` 的页签计数断言仍为 4（对应 Shiro 页四页签）。

**风险**：配置页的 `ConfigPage.Group` 清单在 `Main` 中内联构建
（`src/Main.java:706` 起，约 45 行），搬迁时保持「新增配置只需改一行」的既有设计。

### 第三阶段：收敛 `Main`

**目标**：`Main` 只做装配与导航。

1. 抽出 `ui/navigation/NavController`（213 行）。
2. 抽出 `ui/probe/ProbeController`（61 行）。
3. `Main` 最终只保留：`main`、构造、`configureFrame`、`setContent`、
   `updateScale`、`showHome`、页面路由。
4. 验收：全部自检通过；`Main.java` 行数 ≤ 400。

**预期收益**：

| 指标 | 现在 | 拆分后 |
| --- | ---: | ---: |
| `Main.java` 行数 | 1533 | ≤ 400 |
| `Main.java` 字段数 | 100 | ≤ 10 |
| 最大单文件行数 | 1533 | 约 380 |
| 页面改动的冲突面 | 全文件 | 单页目录 |

---

## 六、不做的事

明确划出边界，避免自我扩权：

- **不引入任何新第三方依赖**：不使用 Spring、Guice、Javalin 等，
  不引入 DI 框架。页面间依赖用构造器传参显式表达。
- **不改变现有行为**：拆分是纯搬迁，不改交互、不改文案、不改配置键名。
  唯一的例外是 `src/config/AppConfig.java` 的键若需归位，须单独提 change。
- **不重写 `src/proxy/ProxyServer.java`（768 行）与 `src/shiro/ShiroEngine.java`（719 行）**：
  两者都在 800 行以内，且有独立自检，不在本次范围内。
  它们若将来需要拆分，另开文档。
- **不动 `python/fj_probe.py`（2811 行）**：它是单文件引擎，
  按命令行参数分发模式，拆分收益与风险不成比例。若将来要拆，
  应先把模式分派与 HTTP 层分开，属于独立课题。
- **不改 UI 外观**：颜色、间距、字号全部保持现状，
  确保 `tests/UiNavigationCheck.java` 中关于可见性与高度的断言不变。

---

## 七、验收标准

每次拆分提交必须同时满足：

1. 五套 Java 自检全部通过，输出与拆分前逐字符一致（除类名）。
2. `python -m unittest discover -s tests` 全绿（84 项）。
3. `build.ps1` 构建成功，JAR 构建时间晚于全部源文件。
4. JAR 内 `python/fj_probe.py` 与源文件哈希一致。
5. 手工冒烟：五页均能打开，代理能启停，抓包能转换，Shiro 页四页签不增长。
6. `PROGRESS.md` 与 `AI_REPORT.md` 记录本阶段结论。

第 1 条是本次拆分的核心验收点——**行为零变化**。
若某条自检输出的文字发生了变化（而非类名变化），视为行为漂移，必须回退重做。

---

## 八、实施记录（2026-09-21 完成）

三阶段一次完成，`Main.java` = 1757 → **311 行**，不再是最大文件（最大为 `proxy/ProxyServer.java` 768 行）。

| 新增文件 | 行数 | 职责 |
| --- | ---: | --- |
| `src/ui/NavController.java` | 203 | 侧边栏构建、展开收起、选中、路由回调 |
| `src/ui/ProbeController.java` | 148 | 探测模式、参数拼装、引擎调用、阶段字段联动 |
| `src/ui/CaptureController.java` | 285 | 抓包 / 转换 / 复制 / 一键发送、代理流量导入 |
| `src/ui/ShiroController.java` | 251 | 指纹检测 / 密钥爆破 / 载荷生成 / 命令回显 |
| `src/ui/ProxyController.java` | 310 | 代理启停、拦截改包放行、流量回填、导出 |
| `src/ui/ConfigForm.java` | 160 | 38 项配置控件与 10 个分组清单 |
| `src/ui/ConfigController.java` | 395 | 配置读 / 写 / 下发、服务器默认值、下拉框助手 |
| `src/ui/WorkbenchPages.java` | 150 | Payload / 预设链 / 恶意服务器三页的懒加载与互发 |
| `src/ui/WidgetRegistry.java` | 159 | 自检门面的控件登记表（90 余项） |
| `src/ui/UiHandle.java` | 72 | 稳定门面：按名字取控件，沿继承链回落 |

视图类各新增一个 `defaults()` 控件工厂（`ProbePage` / `CapturePage` / `ShiroPage` / `ProxyPage`），
把「控件长什么样、初值是什么」从界面层挪回视图类内。

## 九、对原方案的两处修正（实测后调整）

**修正一：字段不必留在原类。** 原第三节给的方案 A（稳定门面）实测可行，
因此采用了「门面 + 反射回落」的组合：自检先查界面层登记表，查不到再沿继承链找字段。
三个 UI 自检的取数辅助方法（`fieldQuiet` / `read` / `hasDeclaredField`）改为
调用 `ui.UiHandle`，132 处字段访问与 3 个方法调用（`selectNav` / `toggleGroup` / `findNavItem`）
的**断言文字与数量一条未改**，只是取数路径换了。

**修正二：不建 `ProxyState` / `ConfigState` / `ShiroState`。** 原第四节设想把 100 个字段
按前缀归到状态类。实施后发现这些字段是**控件引用**而非状态：真正的状态是
「代理是否在监听、待放行的是哪条流量、当前拦截决定」这类不变量，
把它们与控件分开后，状态类会变成一堆 getter / setter，反而更难追。
因此改为：状态收进对应控制器（`ProxyController` 的 `pendingFlow` / `interceptDecision` 等），
控件由视图类的 `defaults()` 与 `ConfigForm` 持有，组合根只保留引用与装配。

**修正三：包结构平铺。** 原第四节建议 `ui/capture/`、`ui/config/` 等子包。
实施时发现 `tests/test_decoupling.py` 与 `tools/audit_boundary.py` 按 `package` 声明建包名，
子包会成为新包名并需要同步补 `ALLOWED_EDGES`；平铺在 `ui` 包内则规则零改动。
权衡后选择平铺：`src/ui/` 下 32 个文件，按 `*Page`（视图）/ `*Controller`（行为）/
`Nav*`（导航）/ `Ui*`（外观与门面）四类命名区分，无需改边界规则。

## 十、本次不变量（实测确认）

- 六套 Java 自检断言数不变：`ProxyServerCheck` 37、`ShiroCheck` 46、`PayloadCheck` 61、
  `UiShiroCheck` 27、`UiNavigationCheck` 169、`UiSwitchEndToEndCheck` 95。
- `python -m unittest discover -s tests`：99 项全绿（含依赖边界与反向用例）。
- `tools/audit_boundary.py`：无环、无越界、无叶子层出边、无内核反向依赖。
- `build.ps1`：构建成功，JAR 时间晚于 `src/`、`python/`、`tests/` 共 60 个源文件。