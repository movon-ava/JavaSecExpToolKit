# Design: 界面层模块化

## Root Cause

`Main.java` 之所以长到 1757 行且迟迟拆不动，根因不是「写得乱」，而是两条约束互相锁死：

1. **控制器缺位**：`src/ui/*Page.java` 早已是静态视图构建器（只描述结构、不持有状态），
   但**状态与行为没有对应去处**，只能留在组合根。于是每加一个功能页，
   `Main` 就同时多出控件声明、事件接线、业务方法三段代码。
2. **自检按字段名反射**：三个 UI 自检用
   `target.getClass().getDeclaredField(name)` 取控件，只查本类、不含父类。
   字段一旦搬走，断言立刻失败。这让「拆分类」与「保持测试通过」直接对立，
   于是每次都以「先不动」收场。

第 2 条是关键：它把内部结构变成了测试契约的一部分。因此本次不是先拆再补测试，
而是**先修测试的取数方式，再拆分**。

## 处置

### 一、稳定门面（先做，解除锁死）

新增 `ui/UiHandle`：先查界面层登记表，再沿继承链反射兜底。
新增 `ui/WidgetRegistry` 承载登记清单（90 余项），
可变的项（展开后的导航序列）用取值器登记，避免把快照固化。

三个自检的取数辅助方法改为调用门面：
`fieldQuiet` / `read` / `hasDeclaredField` 三个函数各改一行函数体，
调用点、断言文字、断言数量全部不动。

### 二、页面行为搬到控制器

按页划分，每页一个控制器，依赖只允许「向后」：

```
NavController（无依赖，只认 NavItem 与 NavigationRenderer）
ProbeController   → ConfigController（只读取值）
CaptureController → ProbePage.Widgets / ShiroPage.Widgets / ConfigController
ProxyController   → CaptureController（导出抓包）/ ConfigController（记住端口）
ShiroController   → ProbePage.Widgets（复用超时框）
ConfigController  → 各页控件（下发默认值）+ View 回调（跨页联动）
WorkbenchPages    → PayloadPage / PresetPage / ServicePage 的懒加载与互发
```

构造顺序固定为 配置 → 探测 → 抓包 → 代理，不靠 setter 互相注入；
唯一需要后置接线的是 `ConfigController` 与 `WorkbenchPages`（后者要拿到面板才能懒加载），
用一次 `attachView` 补上，并把「配置已在启动时读过」这一事实显式刷新一次。

### 三、状态收进控制器，控件由视图工厂持有

原设计设想另建 `ProxyState` / `ConfigState` / `ShiroState` 承载字段，实施后放弃：
这些字段是**控件引用**而非状态。真正的状态是「代理是否在监听、待放行的是哪条流量、
当前拦截决定」这类不变量，把它们与控件混在一起才会难追。
因此：状态收进控制器私有字段，控件由视图类 `defaults()` 与 `ConfigForm` 持有，
组合根只保留引用与装配。

### 四、包结构平铺

原设计建议 `ui/capture/`、`ui/config/` 等子包。实施时发现
`tests/test_decoupling.py` 与 `tools/audit_boundary.py` 按 `package` 声明建包名，
新子包会成为新包名并需要同步补 `ALLOWED_EDGES`；
平铺在 `ui` 包内则两条规则零改动。权衡后选择平铺，按
`*Page`（视图）/ `*Controller`（行为）/ `Nav*`（导航）/ `Ui*`（外观与门面）四类命名区分。

## 边界

- 不改界面外观：颜色、间距、字号、控件文本全部保持原样，
  `UiNavigationCheck` 中关于可见性与高度的断言不变。
- 不改配置键名与默认值：`ConfigForm` 只是把控件与分组清单换了个位置。
- 不改交互时序：例如「进页时才消费预设页交来的链」这条既有约定原样保留。
- 不引入新依赖：只用既有 JDK 与 java-chains。

## 验证

| 入口 | 期望 |
| --- | --- |
| `ProxyServerCheck` / `ShiroCheck` / `PayloadCheck` | 全绿，断言数 37 / 46 / 61 |
| `UiNavigationCheck` / `UiShiroCheck` / `UiSwitchEndToEndCheck` | 全绿，断言数 169 / 27 / 95 |
| `python -m unittest discover -s tests` | 99 项全绿 |
| `python tools/audit_boundary.py` | 无环、无越界、无叶子层出边、无内核反向依赖 |
| `build.ps1` | 构建成功，JAR 时间晚于全部源文件 |
| 手工冒烟 | 五页可打开，代理可启停与拦截放行，抓包可转换，Shiro 页四页签不增长 |
