import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import ui.CaptureController;
import ui.CapturePage;
import ui.ConfigController;
import ui.ConfigForm;
import ui.ConfigPage;
import ui.HomePage;
import ui.NavController;
import ui.NavItem;
import ui.ProbeController;
import ui.ProbePage;
import ui.ProxyController;
import ui.ProxyPage;
import ui.ShiroController;
import ui.ShiroPage;
import ui.ToolsUploadController;
import ui.ToolsUploadPage;
import ui.StartupSplash;
import ui.StartupWarmup;
import ui.UiHandle;
import ui.UiKit;
import ui.WidgetRegistry;
import ui.WorkbenchPages;

/**
 * 组合根：把各功能页的控件、控制器与配置装配起来，并负责窗口骨架。
 *
 * <p>这里刻意只做三件事——建控件、接线、切页。页面的行为都在 {@code ui/*Controller} 里，
 * 配置的读写都在 {@link ConfigController} 里，导航展开与路由都在 {@link NavController} 里，
 * 载荷工作台三页的懒加载都在 {@link WorkbenchPages} 里。这样单页出问题时改动面
 * 就是那一个类，不会再牵动整个主窗口。
 *
 * <p>装配顺序有两条硬约束：
 * 一是配置必须在建控件之后读，否则默认值无处可写；
 * 二是控制器之间只允许「向后」依赖（抓包 → 探测 / Shiro、代理 → 抓包 → 配置），
 * 因此构造顺序固定为 配置 → 探测 → 抓包 → 代理，不靠 setter 互相注入。
 */
public final class Main implements UiHandle.Source {

    /** 导航树：一级分类的展开状态存在 NavItem 上，跨次重建得以保留。 */
    private static final List<NavItem> NAV_ITEMS = NavController.defaultTree();

    private final JFrame frame = new JFrame("JavaSecExpToolKit");
    private final JPanel content = new JPanel(new BorderLayout());
    /**
     * 当前缩放系数：新建控件时按它立即定字号。
     *
     * <p>字号基准记在每个控件自己身上（见 {@link UiKit#trackFont}），这里只保存「现在的缩放是多少」，
     * 因此界面层不再持有任何控件引用——重建页面时旧控件可以被回收，
     * 不会出现「切页越切越慢」（实测原实现三轮导航后登记表从 19 涨到 1007 项）。
     */
    private double currentScale = 1.0;
    private final Properties config = new Properties();
    /** 自检门面的登记表：控件清单集中在那里，组合根只负责填引用。 */
    private final WidgetRegistry registry = new WidgetRegistry();

    /** 配置页表单：配置项集中在 ConfigForm，新增一项只改那一个类。 */
    private final ConfigForm configForm = new ConfigForm();
    private final ProbePage.Widgets probeWidgets = ProbePage.defaults();
    private final CapturePage.Widgets captureWidgets = CapturePage.defaults();
    private final ShiroPage.Widgets shiroWidgets = ShiroPage.defaults();
    private final ProxyPage.Widgets proxyWidgets = ProxyPage.defaults();
    private final ToolsUploadPage.Widgets toolsUploadWidgets = ToolsUploadPage.defaults();

    private final ConfigController configController;
    private final ProbeController probeController;
    private final CaptureController captureController;
    private final ProxyController proxyController;
    private final ShiroController shiroController;
    private final ToolsUploadController toolsUploadController;
    private final WorkbenchPages workbench;

    private JPanel navigation;
    private JList<NavItem> navigationList;
    private NavController navController;

    private Main() {
        configController = new ConfigController(config, configForm, probeWidgets, proxyWidgets,
                captureWidgets, shiroWidgets, null);
        configController.load();
        configureFrame();
        probeController = new ProbeController(probeWidgets, configController);
        configController.attachProbe(probeController);
        captureController = new CaptureController(captureWidgets, probeWidgets, shiroWidgets,
                configController, this::selectNav);
        proxyController = new ProxyController(proxyWidgets, captureController, configController);
        shiroController = new ShiroController(shiroWidgets, probeWidgets);
        toolsUploadController = new ToolsUploadController(toolsUploadWidgets, configController);
        configController.attachUpload(toolsUploadController);
        buildNavigation();
        workbench = new WorkbenchPages(fonts, configController, captureWidgets, this::selectNav);
        configController.attachView(workbench);
        fillRegistry();
        frame.add(navigation, BorderLayout.WEST);
        frame.add(content, BorderLayout.CENTER);
        configController.applyToForms();
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { updateScale(); }
        });
        showHome();
        updateScale();
    }

    /**
     * 主窗口默认全屏。
     *
     * <p>界面改版后单页要同时放下服务清单、监听参数、载荷发布与运行输出四块，
     * 75% 屏宽时参数区会被压成需要横向滚动。这里直接最大化：Swing 的 MAXIMIZED_BOTH
     * 仍保留标题栏与任务栏，使用者随时可以还原，比无边框全屏更稳妥。
     *
     * <p>最小尺寸按「四块区域都能用」反推：侧边栏 250 + 清单 280 + 参数列 700 左右。
     */
    private void configureFrame() {
        // 关闭窗口即退出进程：恶意服务器会真实占用端口，必须先把它们停掉，
        // 否则下次启动会撞上「端口被占用」而看起来像工具坏了。
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                stopAllServicesThenExit();
            }
        });
        frame.setMinimumSize(new Dimension(1180, 720));
        frame.setLayout(new BorderLayout());
        frame.getContentPane().setBackground(UiKit.BACKGROUND);
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
    }

    private void buildNavigation() {
        navigationList = new JList<NavItem>();
        NavController[] holder = new NavController[1];
        navigation = NavController.build(navigationList, NAV_ITEMS, fonts, holder, this::openKey);
        navController = holder[0];
    }

    /** 退出前停掉全部服务并释放端口；失败也不阻塞退出，避免关不掉窗口。 */
    private void stopAllServicesThenExit() {
        workbench.shutdown();
        frame.dispose();
        System.exit(0);
    }

    private void showHome() {
        setContent(HomePage.build(fonts, this::selectNav));
    }

    /** 导航选中二级功能后，由本方法决定打开哪一个页面。 */
    private void openKey(String key) {
        if ("config".equals(key)) showConfig();
        else if ("proxy.mitm".equals(key)) showProxy();
        else if ("capture".equals(key)) showCapture();
        else if ("fastjson.detect".equals(key)) showFastjson();
        else if ("shiro.exploit".equals(key)) showShiro();
        else if ("payload.build".equals(key)) setContent(workbench.payload());
        else if ("payload.preset".equals(key)) setContent(workbench.preset());
        else if ("payload.tostring".equals(key)) setContent(workbench.tostring());
        else if ("payload.oobjar".equals(key)) setContent(workbench.oobJar());
        else if ("service.servers".equals(key)) setContent(workbench.servers());
        else if ("tools.upload".equals(key)) showToolsUpload();
        else showHome();
    }

    // ------------------------------------------------------------------
    // 页面装配：只把控件交给视图类，行为在各自的控制器里
    // ------------------------------------------------------------------

    private void showProxy() {
        setContent(ProxyPage.build(proxyWidgets, fonts));
    }

    private void showCapture() {
        setContent(CapturePage.build(captureWidgets, fonts));
    }

    private void showFastjson() {
        setContent(ProbePage.build(probeWidgets, fonts));
    }

    private void showShiro() {
        setContent(ShiroPage.build(shiroWidgets, fonts));
    }

    private void showToolsUpload() {
        setContent(ToolsUploadPage.build(toolsUploadWidgets, fonts));
    }

    private void showConfig() {
        // 预设分类候选来自内置预设文件：写死分类名会在预设升级后变成无效选项
        List<String> presetCategories = ui.PresetController.categoryNames(preset.PresetCatalogService.load());
        configForm.presetCategory.setModel(new javax.swing.DefaultComboBoxModel<String>(
                presetCategories.toArray(new String[0])));
        // 换模型会把选中项重置到第一项：这里按配置值重新选一次，
        // 否则配置页显示的「当前值」与实际保存值不一致
        ConfigController.selectOption(configForm.presetCategory,
                configController.property("preset_category", "全部分类"));
        setContent(ConfigPage.build(ConfigPage.widgets(configForm, configController::save,
                configController::resetForm), fonts));
    }

    // ------------------------------------------------------------------
    // 自检用的转发：方法名保持既有约定，行为在各自控制器里
    // ------------------------------------------------------------------

    /** 按 key 选中导航项；收起的分类会先展开。 */
    public void selectNav(String key) {
        navController.selectNav(key);
    }

    public NavItem findNavItem(String key) {
        return navController.findNavItem(key);
    }

    public void toggleGroup(NavItem group) {
        navController.toggleGroup(group);
    }

    public List<String> modeKeys() {
        return probeController.modeKeys();
    }

    public String runProbe(String url, String seconds, String mode) throws Exception {
        return probeController.runProbe(url, seconds, mode);
    }

    public void startDetection() {
        probeController.startDetection();
    }

    /** 自检入口：直接跑一次文件上传，不依赖按钮点击。 */
    public String runUpload(String url, String file, String field, String fields, String headers)
            throws Exception {
        return toolsUploadController.runUpload(url, file, field, fields, headers);
    }

    public void startCapture() {
        captureController.startCapture();
    }

    public void startConvert() {
        captureController.startConvert();
    }

    public void fillProbeFromCapture() {
        captureController.fillProbeFromCapture();
    }

    public void sendCaptureTo() {
        captureController.sendCaptureTo();
    }

    public void toggleProxy() {
        proxyController.toggle();
    }

    public void forwardIntercepted() {
        proxyController.forward();
    }

    public void exportProxyDetail() {
        proxyController.exportDetail();
    }

    /** 自检与调试入口：按名字取界面控件（清单见 {@link WidgetRegistry}）。 */
    @Override
    public Map<String, Object> namedWidgets() {
        return registry.build();
    }

    /** 登记表只保存引用，不复制控件：界面层换页面时控件实例保持不变。 */
    private void fillRegistry() {
        registry.frame = frame;
        registry.content = content;
        registry.navigationList = navigationList;
        registry.navigationItems = () -> navController.items();
        registry.probe = probeWidgets;
        registry.capture = captureWidgets;
        registry.proxy = proxyWidgets;
        registry.shiro = shiroWidgets;
        registry.toolsUpload = toolsUploadWidgets;
        registry.configForm = configForm;
        registry.payload = workbench.payloadWidgets;
        registry.payloadSelector = workbench.payloadSelector;
        registry.preset = workbench.presetWidgets;
        registry.service = workbench.serviceWidgets;
        registry.tostring = workbench.tostringWidgets;
        registry.oobJar = workbench.oobJarWidgets;
    }

    /** 登记需要随窗口缩放的字体；实现 {@link UiKit.FontSink}，供 UiKit 回调。 */
    private final UiKit.FontSink fonts = (component, style, baseSize) ->
            UiKit.trackFont(component, style, baseSize, currentScale);

    private void updateScale() {
        currentScale = UiKit.scaleFor(frame.getWidth(), frame.getHeight());
        // 按当前组件树重算字号：旧控件已随页面一起被回收，不在树上也就不会被处理
        UiKit.scaleFonts(frame.getContentPane(), currentScale);
        if (navController != null) navController.scale(currentScale);
        frame.revalidate();
    }

    private void setContent(JPanel panel) {
        content.removeAll();
        content.add(panel, BorderLayout.CENTER);
        content.revalidate();
        content.repaint();
        updateScale();
    }

    /**
     * 显示主窗口。
     *
     * <p>窗口起来之前先把启动画面收掉：预热在后台线程做，窗口先出来时预热可能还没完，
     * 此时保留启动画面会挡住主窗口；预热若还没做完，剩余步骤会在这里同步补完
     * （幂等，已完成时立即返回）。
     */
    private void show(StartupSplash splash) {
        frame.setVisible(true);
        StartupWarmup.warmUp(splash);
        if (splash != null) splash.close();
    }

    /**
     * 启动：先弹启动画面，预热放到后台线程，主窗口在事件分发线程上建。
     *
     * <p>预热必须离开事件分发线程：java-chains 的 {@code MetadataRegistry.init()} 实测约 1.07s
     * （连同插件与 gadget 注册合计约 1.2s），放在事件分发线程上会把启动画面一起冻住，
     * 等于没有启动画面。放到后台线程后，启动画面能边转边报进度，主窗口也能尽早出现。
     */
    public static void main(String[] args) {
        final StartupSplash splash = new StartupSplash();
        splash.show();
        Thread warmup = new Thread(() -> StartupWarmup.warmUp(splash), "startup-warmup");
        warmup.setDaemon(true);
        warmup.start();
        SwingUtilities.invokeLater(() -> new Main().show(splash));
    }

}
