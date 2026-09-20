import config.AppConfig;
import ui.ConfigPage;
import ui.CapturePage;
import ui.ProxyPage;
import ui.HomePage;
import ui.ShiroPage;
import ui.ProbePage;
import ui.FlowRenderer;
import ui.NavItem;
import ui.UiKit;
import probe.CaptureBridge;
import probe.ProbeCommand;
import probe.ProbeEngine;
import javax.swing.*;
import util.HttpText;
import util.JsonText;
import util.Platform;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public final class Main {
    // 调色板与尺寸集中在 ui.UiKit，这里只做别名，避免各页面各自硬编码颜色
    private static final Color SIDEBAR = UiKit.SIDEBAR;
    private static final Color SIDEBAR_ACTIVE = UiKit.SIDEBAR_ACTIVE;
    private static final Color ACCENT = UiKit.ACCENT;
    private static final Color TEXT = UiKit.TEXT;
    private static final Color MUTED = UiKit.MUTED;
    private static final Color BORDER = UiKit.BORDER;
    private static final Color BACKGROUND = UiKit.BACKGROUND;
    /** 拦截等待上限：超时自动放行，避免浏览器一直卡住。 */
    private static final long PROXY_INTERCEPT_TIMEOUT_MS = 120000;
    private static final List<ui.NavItem> NAV_ITEMS = Arrays.asList(
            new ui.NavItem("home", "主页"),
            new ui.NavItem("config", "配置"),
            new ui.NavItem("proxy", "代理", Arrays.asList(
                    new ui.NavItem("proxy.mitm", "代理抓包"),
                    new ui.NavItem("capture", "抓包转换"))),
            new ui.NavItem("fastjson", "FastJson", Arrays.asList(
                    new ui.NavItem("fastjson.detect", "Fastjson 探测"))),
            new ui.NavItem("shiro", "Shiro", Arrays.asList(
                    new ui.NavItem("shiro.exploit", "Shiro 漏洞利用"))));

    private final JFrame frame = new JFrame("JavaSecExpToolKit");
    private final JPanel content = new JPanel(new BorderLayout());
    private final List<FontBinding> fontBindings = new ArrayList<FontBinding>();
    private final Properties config = new Properties();
    private final JTextField target = new JTextField("http://127.0.0.1:8080/api/json", 32);
    private final JTextField timeout = new JTextField("8", 5);
    private final JCheckBox modeDetect = new JCheckBox("Fastjson 识别", true);
    private final JCheckBox modeVersion = new JCheckBox("版本识别", true);
    private final JCheckBox modeExpect = new JCheckBox("期望类", true);
    private final JComboBox<String> probeMethod = new JComboBox<String>(
            new String[]{"POST", "GET", "PUT", "PATCH", "DELETE", "OPTIONS"});
    private final JTextField baseBody = new JTextField("", 32);
    private final JTextField requestHeaders = new JTextField("", 32);
    /**
     * 已登录会话 Cookie：目标带登录拦截时，未携带会话的探针只会拿到登录页。
     *
     * <p>刻意做成独立输入框而不是要求写进「请求头」JSON：会话 Cookie 是最常变、
     * 最需要反复替换的一项（每次重新登录都会变），单独一栏可以直接粘贴
     * {@code JWT_TOKEN=...; JSESSIONID=...} 而不用手工拼 JSON。
     */
    private final JTextField sessionCookie = new JTextField("", 32);
    private final JTextField dnslogHost = new JTextField("", 32);
    private final JTextField dnsFilter = new JTextField("", 12);
    private final JTextField dnsWait = new JTextField("13", 5);
    // DNS 探针 / CEYE 确认并入探测模式，与其他三个模式同等对待：文字不带「启用」、默认勾选
    private final JCheckBox dnsEnabled = new JCheckBox("DNS 探针", true);
    private final JCheckBox ceyeEnabled = new JCheckBox("CEYE 确认", true);
    private final JTextField configCeyeDomain = new JTextField("", 32);
    private final JPasswordField configCeyeToken = new JPasswordField("", 32);
    private final JTextField configCeyeApi = new JTextField("", 32);
    private final JTextField configPython = new JTextField("", 32);
    private final JTextField configTimeout = new JTextField("8", 5);
    private final JTextField configProbeMethod = new JTextField("POST", 8);
    private final JTextField configDnsWait = new JTextField("13", 5);
    private final JTextField configBaseBody = new JTextField("", 32);
    private final JTextField configHeaders = new JTextField("", 32);
    private final JTextField configSessionCookie = new JTextField("", 32);
    private final JTextField configDnslogHost = new JTextField("", 32);
    private final JTextField configDnsFilter = new JTextField("", 12);
    // 代理 / 抓包 / Shiro 的持久化配置：这些功能的默认值同样应能在配置页里长期保存
    private final JTextField configProxyBindHost = new JTextField("", 24);
    /** 探测报告详细度：精简只给结论与关键判定，详细才附探针明细。 */
    private final JComboBox<String> configProbeReport = new JComboBox<String>(
            new String[]{"精简", "详细"});
    private final JTextField configProxyPort = new JTextField("8899", 8);
    private final JCheckBox configProxyIntercept = new JCheckBox("启动代理后默认拦截请求", false);
    private final JTextField configCaptureContentType = new JTextField("application/json", 24);
    private final JComboBox<String> configCaptureMethod = new JComboBox<String>(
            new String[]{"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"});
    private final JComboBox<String> configCaptureTarget = new JComboBox<String>(
            new String[]{"json", "curl", "raw", "cookie-json", "cookie-header", "cookie-netscape"});
    private final JTextField configShiroUrl = new JTextField("", 32);
    private final JTextField configShiroCookieName = new JTextField("rememberMe", 12);
    private final JTextField configShiroKey = new JTextField("", 26);
    private final JCheckBox configShiroGcm = new JCheckBox("AES-GCM（Shiro ≥ 1.4.2）", false);
    private final JTextField configShiroEchoHeader = new JTextField("", 14);
    private final JComboBox<shiro.ShiroExploit.ChainKind> configShiroChain =
            new JComboBox<shiro.ShiroExploit.ChainKind>(shiro.ShiroExploit.ChainKind.values());
    private final JTextField configShiroCommand = new JTextField("", 22);
    /** 默认请求体：目标接口需要业务参数时，探测与利用都要带上它。 */
    private final JTextField configShiroBody = new JTextField("", 22);
    private final JLabel configStatus = new JLabel("配置保存在用户目录下");
    private final JTextArea result = new JTextArea();
    private final JButton detect = new JButton("开始探测");
    private final JLabel status = new JLabel("仅用于已授权测试目标");
    // 抓包转换页字段（独立功能，与 Fastjson 探测参数互不影响）
    private final JTextField captureUrl = new JTextField("", 32);
    private final JComboBox<String> captureMethod = new JComboBox<String>(
            new String[]{"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"});
    private final JTextField captureContentType = new JTextField("application/json", 24);
    private final JTextField captureHeaders = new JTextField("", 32);
    private final JComboBox<String> captureTarget = new JComboBox<String>(
            new String[]{"json", "curl", "raw", "cookie-json", "cookie-header", "cookie-netscape"});
    private final JTextArea captureBody = new JTextArea(4, 32);
    private final JTextArea pastedRequest = new JTextArea(6, 32);
    private final JTextArea captureResult = new JTextArea();
    private final JButton captureRun = new JButton("抓包");
    private final JButton captureConvert = new JButton("解析并转换");
    private final JButton captureCopy = new JButton("复制结果");
    private final JButton captureToProbe = new JButton("填入探测页");
    /** 一键发送：把刚抓到的请求直接交给另一个功能去探测。 */
    private final JComboBox<String> captureSendTo = new JComboBox<String>(
            new String[]{"Fastjson 探测", "Shiro 漏洞利用"});
    private final JButton captureSend = new JButton("一键发送");
    private final JLabel captureStatus = new JLabel("抓包与转换均在本地执行，仅访问你填写的目标");
    // 代理抓包页字段（独立功能，与探测参数互不影响）
    private final JTextField proxyBindHost = new JTextField(proxy.ProxyServer.defaultBindHost(), 14);
    private final JTextField proxyPort = new JTextField("8899", 6);
    private final JButton proxyToggle = new JButton("启动代理");
    private final JButton proxyClear = new JButton("清空记录");
    private final JButton proxyExport = new JButton("转发到抓包转换");
    private final JCheckBox proxyIntercept = new JCheckBox("拦截请求");
    private final JButton proxyForward = new JButton("放行");
    private final JButton proxyDrop = new JButton("丢弃");
    private final JLabel proxyStatus = new JLabel("代理未启动");
    private final JTextArea proxyRequestText = new JTextArea();
    private final JTextArea proxyResponseText = new JTextArea();
// Shiro 漏洞利用页字段（独立功能，与探测参数互不影响）
    private final JTextField shiroUrl = new JTextField("http://127.0.0.1:8080/", 32);
    private final JComboBox<String> shiroRequestMethod = new JComboBox<String>(new String[]{"GET", "POST"});
    private final JTextField shiroCookieName = new JTextField("rememberMe", 12);
    private final JTextField shiroKey = new JTextField("kPH+bIxk5D2deZiIxcaaaA==", 26);
    private final JCheckBox shiroGcm = new JCheckBox("AES-GCM（Shiro \u2265 1.4.2）", false);
    private final JTextField shiroEchoHeader = new JTextField("X-Authorization", 14);
    private final JComboBox<shiro.ShiroExploit.ChainKind> shiroChain = new JComboBox<shiro.ShiroExploit.ChainKind>(shiro.ShiroExploit.ChainKind.values());
    private final JTextField shiroCommand = new JTextField("whoami", 22);
    private final JTextArea shiroHeaders = new JTextArea(3, 32);
    /** 请求体：抓包得到的 POST 体往往是目标接口的必填参数，不带上就只能拿到校验错误。 */
    private final JTextArea shiroBody = new JTextArea(3, 32);
    private final JButton shiroDetect = new JButton("\u4e00\u952e\u68c0\u6d4b");
    private final JButton shiroCrack = new JButton("\u5bc6\u94a5\u7206\u7834");
    private final JButton shiroStop = new JButton("\u505c\u6b62");
    private final JButton shiroBuild = new JButton("\u751f\u6210 Payload");
    private final JButton shiroRun = new JButton("\u6267\u884c\u547d\u4ee4");
    private final JLabel shiroStatus = new JLabel("Shiro 模块未开始");
    /** 指纹检测 / 密钥爆破 / 生成 Payload / 执行命令 各自独立的回显框。 */
    private final JTextArea shiroDetectOutput = new JTextArea();
    private final JTextArea shiroCrackOutput = new JTextArea();
    private final JTextArea shiroBuildOutput = new JTextArea();
    private final JTextArea shiroRunOutput = new JTextArea();
    private final JTabbedPane shiroOutputTabs = new JTabbedPane();
    private final javax.swing.JProgressBar shiroProgress = new javax.swing.JProgressBar();
    private final java.util.concurrent.atomic.AtomicBoolean shiroCancel = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile String shiroCrackedKey = "";
    private volatile boolean shiroCrackedGcm;
    private proxy.ProxyServer proxyServer;
    /** 上一次成功监听的端口；端口框被随机端口回填后再启动时以它为准。 */
    private int lastProxyPort;
    /** 最近一条被放行的流量，用于把请求包抓到抓包转换页。 */
    private volatile proxy.ProxyServer.HttpFlow lastProxyFlow;
    /**
     * 正在等返回包的那条流量 id（放行或丢弃后记下）。
     *
     * 拦截开启时返回包面板只认它：排队接位的下一条请求、浏览器后台的无关请求都不能
     * 覆盖它，否则使用者刚放行看到的「已放行，等待返回包…」会被别人的响应冲掉。
     */
    private volatile long awaitingResponseFlowId;
    /** 拦截等待：连接线程在这里挂起，直到界面点击放行/丢弃或超时。 */
    private final Object interceptLock = new Object();
    private volatile proxy.ProxyServer.HttpFlow pendingFlow;
    /** 拦截总开关：与勾选框同步，供连接线程判断是否还需要继续等待。 */
    private volatile boolean interceptActive;
    private volatile int interceptDecision;
    private volatile byte[] forwardedHead;
    private volatile byte[] forwardedBody;
    private JPanel navigation;
    private JList<ui.NavItem> navigationList;
    private List<ui.NavItem> navigationItems = new ArrayList<ui.NavItem>();

    private Main() {
        loadConfig();
        configureFrame();
        navigation = navigation();
        frame.add(navigation, BorderLayout.WEST);
        frame.add(content, BorderLayout.CENTER);
        detect.addActionListener(e -> startDetection());
        proxyToggle.addActionListener(e -> toggleProxy());
        proxyClear.addActionListener(e -> clearProxyFlows());
        proxyExport.addActionListener(e -> exportProxyDetail());
        proxyIntercept.addActionListener(e -> updateProxyInterceptState());
        proxyForward.addActionListener(e -> forwardIntercepted());
        proxyDrop.addActionListener(e -> dropIntercepted());
        captureRun.addActionListener(e -> startCapture());
        captureConvert.addActionListener(e -> startConvert());
        captureToProbe.addActionListener(e -> fillProbeFromCapture());
        captureSend.addActionListener(e -> sendCaptureTo());
        shiroDetect.addActionListener(e -> startShiroDetect());
        shiroCrack.addActionListener(e -> startShiroCrack());
        shiroStop.addActionListener(e -> shiroCancel.set(true));
        shiroBuild.addActionListener(e -> buildShiroPayload());
        shiroRun.addActionListener(e -> runShiroCommand());
        // 模式勾选状态同时决定对应输入框是否可用
        dnsEnabled.addActionListener(e -> updateStageFieldState());
        ceyeEnabled.addActionListener(e -> updateStageFieldState());
        modeExpect.addActionListener(e -> updateStageFieldState());
        applyConfigToForms();
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { updateScale(); }
        });
        showHome();
        updateScale();
    }

    private void configureFrame() {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int width = Math.max(1050, (int) (screen.width * 0.75));
        int height = Math.max(700, (int) (screen.height * 0.75));
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(width, height);
        frame.setMinimumSize(new Dimension(980, 640));
        frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout());
        frame.getContentPane().setBackground(BACKGROUND);
    }

    private JPanel navigation() {
        JPanel panel = new JPanel(new BorderLayout(0, 22));
        panel.setBackground(SIDEBAR);
        panel.setBorder(BorderFactory.createEmptyBorder(28, 20, 24, 20));
        panel.setPreferredSize(new Dimension(250, 0));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel brand = label("JavaSec", Font.BOLD, 22, Color.WHITE);
        JLabel product = label("EXP TOOLKIT", Font.PLAIN, 11, new Color(148, 163, 184));
        product.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        header.add(brand);
        header.add(product);
        panel.add(header, BorderLayout.NORTH);

        navigationItems = new ArrayList<ui.NavItem>(NAV_ITEMS);
        navigationList = new JList<ui.NavItem>(navigationItems.toArray(new NavItem[0]));
        navigationList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        navigationList.setSelectedIndex(0);
        navigationList.setBackground(SIDEBAR);
        navigationList.setForeground(new Color(226, 232, 240));
        navigationList.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        navigationList.setFixedCellHeight(48);
        navigationList.setCellRenderer(new ui.NavigationRenderer());
        track(navigationList, Font.PLAIN, 15);
        navigationList.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                int index = navigationList.locationToIndex(event.getPoint());
                if (index < 0 || index >= navigationItems.size()) return;
                ui.NavItem hit = navigationItems.get(index);
                if (!hit.hasChildren()) return;
                // locationToIndex 会把列表下方空白回落到最后一行，必须先确认命中的是真实单元格
                Rectangle bounds = navigationList.getCellBounds(index, index);
                if (bounds == null || !bounds.contains(event.getPoint())) return;
                // 一级分类：点击该行展开二级子功能，再次点击收起
                toggleGroup(hit);
            }
        });
        navigationList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) openSelected();
        });
        panel.add(navigationList, BorderLayout.CENTER);

        JLabel footer = label("v0.1  |  授权探测", Font.PLAIN, 12, new Color(148, 163, 184));
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private void showHome() {
        setContent(HomePage.build(fonts, this::selectNav));
    }

    private void openSelected() {
        NavItem item = navigationList.getSelectedValue();
        if (item == null) return;
        if (item.hasChildren()) {
            // 分组项本身不是页面：展开/收起只由鼠标点击触发，避免同一次点击被切换两次
            return;
        }
        if ("config".equals(item.key)) showConfig();
        else if ("proxy.mitm".equals(item.key)) showProxy();
        else if ("capture".equals(item.key)) showCapture();
        else if ("fastjson.detect".equals(item.key)) showFastjson();
        else if ("shiro.exploit".equals(item.key)) showShiro();
        else showHome();
    }

    private void toggleGroup(NavItem group) {
        NavItem existing = findNavItem(group.key);
        if (existing != null) group = existing;
        if (group.children.isEmpty()) return;
        boolean expanded = !group.expanded;
        group.expanded = expanded;
        rebuildNavigation(group);
    }

    private ui.NavItem findNavItem(String key) {
        return findNavItem(NAV_ITEMS, key);
    }

    private ui.NavItem findNavItem(List<ui.NavItem> items, String key) {
        for (NavItem item : items) {
            if (item.key.equals(key)) return item;
            NavItem found = findNavItem(item.children, key);
            if (found != null) return found;
        }
        return null;
    }

    private void rebuildNavigation(NavItem group) {
        String selectedKey = navigationList.getSelectedValue() == null ? "home" : navigationList.getSelectedValue().key;
        List<ui.NavItem> rebuilt = new ArrayList<ui.NavItem>();
        for (NavItem item : NAV_ITEMS) {
            rebuilt.add(item);
            // 展开状态属于每个分组自己：只补当前分组会让另一个已展开的分组丢掉子项
            if (item.expanded) rebuilt.addAll(item.children);
        }
        navigationItems = rebuilt;
        navigationList.setListData(navigationItems.toArray(new NavItem[0]));
        for (int index = 0; index < navigationItems.size(); index++) {
            if (navigationItems.get(index).key.equals(selectedKey)) {
                navigationList.setSelectedIndex(index);
                break;
            }
        }
        updateScale();
        navigationList.revalidate();
        navigationList.repaint();
    }

    private void selectNav(String key) {
        NavItem target = findNavItem(key);
        if (target != null && target.parent != null && !target.parent.expanded) {
            target.parent.expanded = true;
            rebuildNavigation(target.parent);
        }
        for (int index = 0; index < navigationItems.size(); index++) {
            if (navigationItems.get(index).key.equals(key)) { navigationList.setSelectedIndex(index); return; }
        }
    }
    private void showProxy() {
        ProxyPage.Widgets widgets = new ProxyPage.Widgets();
        widgets.bindHost = proxyBindHost;
        widgets.port = proxyPort;
        widgets.toggle = proxyToggle;
        widgets.clear = proxyClear;
        widgets.export = proxyExport;
        widgets.status = proxyStatus;
        widgets.intercept = proxyIntercept;
        widgets.forward = proxyForward;
        widgets.drop = proxyDrop;
        widgets.requestText = proxyRequestText;
        widgets.responseText = proxyResponseText;
        widgets.onBuilt = this::updateProxyInterceptState;
        setContent(ProxyPage.build(widgets, fonts));
    }

    /**
     * 记住最近一次成功监听的地址与端口。
     *
     * <p>使用者常会反复调整端口；把它写回同一份配置，下次启动与下次打开配置页都能看到
     * 实际用过的值——否则配置页里的「代理端口」只是个从不生效的摆设。
     */
    private void rememberProxyEndpoint(String host, int port) {
        if (host == null || host.trim().isEmpty() || port <= 0) return;
        config.setProperty("proxy_bind_host", host.trim());
        config.setProperty("proxy_port", String.valueOf(port));
        try {
            AppConfig.save(config);
        } catch (IOException e) {
            configStatus.setText("代理已启动，但记录监听参数失败：" + e.getMessage());
        }
    }

    /**
     * 同步拦截开关到运行中的代理，并决定放行 / 丢弃是否可用。
     *
     * 拦截器必须在这里装卸，而不能只在启动时判断一次：代理先启动、随后才勾选「拦截请求」
     * 是最常见的用法，只判断一次会导致勾选了却完全不拦截。
     */
    private void updateProxyInterceptState() {
        boolean running = proxyServer != null && proxyServer.isRunning();
        boolean intercepting = running && proxyIntercept.isSelected();
        interceptActive = intercepting;
        if (running) proxyServer.setInterceptor(intercepting ? this::interceptRequest : null);
        proxyForward.setEnabled(intercepting);
        proxyDrop.setEnabled(intercepting);
        // 只有拦截开启时请求包才可编辑：未拦截时面板是纯观察视图，
        // 若允许编辑，后续流量一来就会被覆盖，使用者改的内容既发不出去也留不住。
        proxyRequestText.setEditable(intercepting);
        if (!intercepting) {
            // 关掉拦截后放走可能正卡在等待里的请求，面板回到纯观察模式
            awaitingResponseFlowId = 0;
            releasePendingUnchanged();
        }
    }

    /** 关闭拦截时放走仍在等待的请求：不改包直接放行，避免浏览器一直挂着。 */
    private void releasePendingUnchanged() {
        synchronized (interceptLock) {
            if (pendingFlow == null) return;
            forwardedHead = null;
            forwardedBody = null;
            interceptDecision = 1;
            interceptLock.notifyAll();
        }
    }

    /**
     * 启动 / 停止本地代理。
     *
     * 端口以输入框为准（留空按 8899），启动失败时状态栏会显示具体原因；
     * 记住上次成功启动的端口，避免下次启动被输入框里残留的随机端口带偏。
     */
    private void toggleProxy() {
        if (proxyServer != null && proxyServer.isRunning()) {
            proxyServer.setInterceptor(null);
            proxyServer.stop();
            proxyToggle.setText("启动代理");
            proxyStatus.setText("代理已停止");
            updateProxyInterceptState();
            return;
        }
        String bindHost = proxyBindHost.getText().trim();
        if (bindHost.isEmpty()) bindHost = proxy.ProxyServer.defaultBindHost();
        int requested = lastProxyPort > 0 ? lastProxyPort : 8899;
        String typed = proxyPort.getText().trim();
        if (!typed.isEmpty()) {
            try {
                requested = Integer.parseInt(typed);
            } catch (NumberFormatException e) {
                proxyStatus.setText("端口必须是数字");
                return;
            }
        }
        if (requested < 1 || requested > 65535) {
            proxyStatus.setText("端口范围 1-65535");
            return;
        }
        proxy.ProxyServer server = new proxy.ProxyServer(500);
        server.addListener(flow -> SwingUtilities.invokeLater(() -> appendProxyFlow(flow)));
        if (proxyIntercept.isSelected()) server.setInterceptor(this::interceptRequest);
        try {
            server.start(bindHost, requested);
        } catch (Exception e) {
            proxyStatus.setText("启动失败：" + e.getMessage());
            return;
        }
        proxyServer = server;
        lastProxyPort = requested;
        proxyBindHost.setText(server.host());
        proxyPort.setText(String.valueOf(server.port()));
        rememberProxyEndpoint(server.host(), server.port());
        proxyToggle.setText("停止代理");
        proxyStatus.setText("代理运行中：" + server.displayHost() + ":" + server.port());
        updateProxyInterceptState();
    }

    /**
     * 请求拦截回调（运行在代理的连接线程上）。
     *
     * 把请求包展示到界面并阻塞等待放行；超过 {@code PROXY_INTERCEPT_TIMEOUT_MS} 未操作则自动放行，
     * 避免使用者的浏览器一直挂着。放行时读取界面上的请求包，因此直接改包即可生效。
     */
    private proxy.ProxyServer.Rewrite interceptRequest(proxy.ProxyServer.HttpFlow flow,
                                                       byte[] head, byte[] body) {
        if (proxyServer == null || !proxyServer.isRunning() || !interceptActive) return null;
        String shown = FlowRenderer.request(flow, head, body);
        synchronized (interceptLock) {
            // 排队：同一时刻只让一个请求停在界面上，其余等轮到自己。
            // 否则多个连接同时进入会把彼此的待放行报文互相覆盖，放行时发错内容。
            long queueDeadline = System.currentTimeMillis() + PROXY_INTERCEPT_TIMEOUT_MS;
            while (pendingFlow != null && interceptActive) {
                long remaining = queueDeadline - System.currentTimeMillis();
                if (remaining <= 0) return null;
                try {
                    interceptLock.wait(Math.min(remaining, 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (!interceptActive) return null;

            interceptDecision = 0;
            forwardedHead = null;
            forwardedBody = null;
            pendingFlow = flow;
            SwingUtilities.invokeLater(() -> {
                proxyRequestText.setText(shown);
                proxyRequestText.setCaretPosition(0);
                proxyStatus.setText("已拦截，等待放行：" + flow.method + " " + flow.url());
            });

            long deadline = System.currentTimeMillis() + PROXY_INTERCEPT_TIMEOUT_MS;
            while (interceptDecision == 0 && interceptActive) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    interceptLock.wait(Math.min(remaining, 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            boolean dropped = interceptDecision == 2;
            pendingFlow = null;
            interceptDecision = 0;
            // 唤醒可能正在排队等轮次的连接
            interceptLock.notifyAll();
            if (dropped) return proxy.ProxyServer.Rewrite.DROP;
            return new proxy.ProxyServer.Rewrite(forwardedHead, forwardedBody);
        }
    }

    /** 放行：把界面上编辑后的请求包交给代理转发，并记录原始报文供返回包回溯。 */
    private void forwardIntercepted() {
        String edited = proxyRequestText.getText();
        synchronized (interceptLock) {
            if (pendingFlow == null) {
                proxyStatus.setText("当前没有等待放行的请求");
                return;
            }
            byte[] head = HttpText.headerBytes(edited);
            if (head == null) {
                proxyStatus.setText("请求包格式不正确：缺少空行分隔");
                return;
            }
            forwardedHead = head;
            forwardedBody = HttpText.bodyBytes(edited);
            lastProxyFlow = pendingFlow;
            // 这条请求的返回包将占据面板；期间接位的下一条请求不得覆盖它
            awaitingResponseFlowId = pendingFlow.id;
            interceptDecision = 1;
            interceptLock.notifyAll();
        }
        proxyStatus.setText("已放行，等待返回包…");
    }

    /** 丢弃：不连接上游，直接断开这条请求。 */
    private void dropIntercepted() {
        synchronized (interceptLock) {
            if (pendingFlow == null) {
                proxyStatus.setText("当前没有等待放行的请求");
                return;
            }
            awaitingResponseFlowId = pendingFlow.id;
            interceptDecision = 2;
            interceptLock.notifyAll();
        }
        proxyStatus.setText("已丢弃该请求");
    }

    /**
     * 一条流量结束（或 HTTPS 隧道建立）时刷新界面。
     *
     * 拦截开启时，请求包面板由 {@link #interceptRequest} 单独负责：命中即写入待放行报文，
     * 使用者随后可以编辑。这里若再按「最近一条」刷新，浏览器后台的心跳、预连接等请求就会
     * 在等待放行期间不停冲掉正在编辑的请求包——这正是「未放行时面板还在刷新」的根因。
     * 因此拦截开启时请求包面板一刀不写，只按 {@code awaitingResponseFlowId} 回填返回包。
     *
     * 未开启拦截时属于纯观察，直接展示最近一条即可。
     */
    private void appendProxyFlow(proxy.ProxyServer.HttpFlow flow) {
        if (proxyIntercept.isSelected()) {
            // 返回包只认刚放行 / 刚丢弃的那一条；一次响应写完就把归属清空，
            // 后续接位的请求不会把这块内容覆盖掉
            if (flow.id != awaitingResponseFlowId) return;
            awaitingResponseFlowId = 0;
        } else {
            // 观察模式下请求面板展示的就是这条流：必须记下来，否则未勾选「拦截请求」时
            // 点「转发到抓包转换」永远提示「还没有可转换的请求包」，抓包结果根本带不出去。
            lastProxyFlow = flow;
            proxyRequestText.setText(FlowRenderer.rawRequest(flow));
            proxyRequestText.setCaretPosition(0);
        }
        proxyResponseText.setText(FlowRenderer.response(flow));
        proxyResponseText.setCaretPosition(0);
    }

    /** 从原始字节还原请求包文本；解析不出时回落到「请求行 + 头」的字段拼装。 */
    private void clearProxyFlows() {
        if (proxyServer != null) proxyServer.clear();
        proxyRequestText.setText(ProxyPage.REQUEST_PLACEHOLDER);
        proxyResponseText.setText(ProxyPage.RESPONSE_PLACEHOLDER);
        proxyStatus.setText("已清空记录");
    }

    /** 把当前显示的请求包抓到抓包转换页，方便导出 Cookie 等格式。 */
    private void exportProxyDetail() {
        String raw = proxyRequestText.getText();
        if (raw == null || raw.trim().isEmpty() || lastProxyFlow == null) {
            proxyStatus.setText("还没有可转换的请求包");
            return;
        }
        proxy.ProxyServer.HttpFlow flow = lastProxyFlow;
        captureUrl.setText(flow.url());
        captureMethod.setSelectedItem(flow.method);
        pastedRequest.setText(raw);
        StringBuilder headers = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> header : flow.requestHeaders.entrySet()) {
            // 跳转头与原请求的 Content-Length 不能跟着转发：前者只对原连接有效，
            // 后者会让目标一直等一个不会到来的请求体。这里过滤后，
            // 抓包页导出的头就是可以直接拿去探测的一组。
            if (!HttpText.forwardable(header.getKey())) continue;
            if (!first) headers.append(",");
            first = false;
            headers.append("\"").append(header.getKey()).append("\":\"")
                    .append(header.getValue().replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        headers.append("}");
        captureHeaders.setText(headers.toString());
        captureBody.setText(new String(HttpText.bodyBytes(raw), StandardCharsets.UTF_8));
        selectNav("capture");
        captureStatus.setText("已从代理请求包导入，可直接转换或抓包");
    }

    private void showCapture() {
        CapturePage.Widgets widgets = new CapturePage.Widgets();
        widgets.method = captureMethod;
        widgets.url = captureUrl;
        widgets.contentType = captureContentType;
        widgets.headers = captureHeaders;
        widgets.body = captureBody;
        widgets.convertTarget = captureTarget;
        widgets.pastedRequest = pastedRequest;
        widgets.run = captureRun;
        widgets.convert = captureConvert;
        widgets.toProbe = captureToProbe;
        widgets.sendTo = captureSendTo;
        widgets.send = captureSend;
        widgets.status = captureStatus;
        widgets.result = captureResult;
        widgets.copy = captureCopy;
        widgets.onCopy = this::copyCaptureResult;
        setContent(CapturePage.build(widgets, fonts));
    }

    private void copyCaptureResult() {
        String text = captureResult.getText();
        if (text.isEmpty()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        captureStatus.setText("已复制到剪贴板");
    }

    private void showFastjson() {
        ProbePage.Widgets widgets = new ProbePage.Widgets();
        widgets.modeDetect = modeDetect;
        widgets.modeVersion = modeVersion;
        widgets.modeExpect = modeExpect;
        widgets.dnsEnabled = dnsEnabled;
        widgets.ceyeEnabled = ceyeEnabled;
        widgets.probeMethod = probeMethod;
        widgets.target = target;
        widgets.timeout = timeout;
        widgets.baseBody = baseBody;
        widgets.requestHeaders = requestHeaders;
        widgets.sessionCookie = sessionCookie;
        widgets.dnslogHost = dnslogHost;
        widgets.dnsFilter = dnsFilter;
        widgets.dnsWait = dnsWait;
        widgets.detect = detect;
        widgets.status = status;
        widgets.result = result;
        widgets.onModeChanged = this::updateStageFieldState;
        setContent(ProbePage.build(widgets, fonts));
    }

    private void showConfig() {
        ConfigPage.Widgets widgets = new ConfigPage.Widgets();
        widgets.groups = new ConfigPage.Group[]{
                new ConfigPage.Group("通用配置", "所有功能共用；未单独配置的功能沿用这里的默认值。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("Python 解释器", configPython, "留空则使用 PATH 中的 python"),
                                new ConfigPage.Row("默认超时（秒）", configTimeout, "探测请求超时"),
                                new ConfigPage.Row("默认请求方法", configProbeMethod, "探测接口常见为 POST")}),
                new ConfigPage.Group("FastJson 配置",
                        "Fastjson 探测（识别 / 版本 / 期望类 / DNS 探针 / CEYE 确认）专用。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("CEYE 域名", configCeyeDomain, "例如 abc.ceye.io"),
                                new ConfigPage.Row("CEYE Token", configCeyeToken, "在 ceye.io 个人中心获取"),
                                new ConfigPage.Row("CEYE API", configCeyeApi, "默认 http://api.ceye.io/v1/records"),
                                new ConfigPage.Row("默认 DNS 等待（秒）", configDnsWait, "DNS 探针等待记录时间"),
                                new ConfigPage.Row("默认业务参数", configBaseBody, "期望类模式的 base_body"),
                                new ConfigPage.Row("默认请求头", configHeaders, "JSON 对象，例如 {\"Cookie\":\"JWT=xxx\"}"),
                                new ConfigPage.Row("会话 Cookie", configSessionCookie, "已登录会话，例如 JWT_TOKEN=x; JSESSIONID=y"),
                                new ConfigPage.Row("默认 DNSLog 主机", configDnslogHost, "例如 abc.ceye.io"),
                                new ConfigPage.Row("默认 CEYE Filter", configDnsFilter, "最长 20 字符")}),
                new ConfigPage.Group("探测报告配置",
                        "决定探测结果区显示多少内容；想一屏看完选「精简」，排查假阳性时选「详细」。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("报告详细度", configProbeReport,
                                        "精简=结论与关键判定；详细=附探针明细与已知限制")}),
                new ConfigPage.Group("代理配置",
                        "「代理抓包」的默认监听参数；留空时按本机联网 IP 与 8899 端口启动。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("默认监听地址", configProxyBindHost, "留空则用本机联网 IP"),
                                new ConfigPage.Row("默认监听端口", configProxyPort, "1-65535，默认 8899"),
                                new ConfigPage.Row("启动后默认拦截请求", configProxyIntercept, "等同于代理页勾选「拦截请求」")}),
                new ConfigPage.Group("抓包转换配置",
                        "「抓包转换」页的默认请求参数；URL 与请求体每次都不同，故不保存。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("默认请求方法", configCaptureMethod, "抓包使用的 HTTP 方法"),
                                new ConfigPage.Row("默认 Content-Type", configCaptureContentType, "例如 application/json"),
                                new ConfigPage.Row("默认转换目标", configCaptureTarget, "抓包后自动导出的格式")}),
                new ConfigPage.Group("Shiro 配置",
                        "「Shiro 漏洞利用」的默认参数；密钥留空则沿用内置常见密钥。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("默认目标 URL", configShiroUrl, "例如 http://127.0.0.1:8080/"),
                                new ConfigPage.Row("默认 Cookie 名", configShiroCookieName, "通常为 rememberMe"),
                                new ConfigPage.Row("默认密钥", configShiroKey, "Base64；留空则用内置密钥"),
                                new ConfigPage.Row("默认使用 AES-GCM", configShiroGcm, "Shiro ≥ 1.4.2 常用"),
                                new ConfigPage.Row("默认回显请求头", configShiroEchoHeader, "例如 X-Authorization"),
                                new ConfigPage.Row("默认利用链", configShiroChain, "回显链的 gadget 前缀"),
                                new ConfigPage.Row("默认命令", configShiroCommand, "例如 whoami"),
                                new ConfigPage.Row("默认请求体", configShiroBody, "目标接口的业务参数")})};
        widgets.status = configStatus;
        widgets.onSave = this::saveConfigFromForm;
        widgets.onReset = this::resetConfigForm;
        setContent(ConfigPage.build(widgets, fonts));
    }

    // ------------------------------------------------------------------
    // Shiro 漏洞利用页
    // ------------------------------------------------------------------

    private void showShiro() {
        ShiroPage.Widgets widgets = new ShiroPage.Widgets();
        widgets.url = shiroUrl;
        widgets.requestMethod = shiroRequestMethod;
        widgets.cookieName = shiroCookieName;
        widgets.key = shiroKey;
        widgets.gcm = shiroGcm;
        widgets.chain = shiroChain;
        widgets.echoHeader = shiroEchoHeader;
        widgets.command = shiroCommand;
        widgets.headers = shiroHeaders;
        widgets.body = shiroBody;
        widgets.detect = shiroDetect;
        widgets.crack = shiroCrack;
        widgets.stop = shiroStop;
        widgets.build = shiroBuild;
        widgets.run = shiroRun;
        widgets.progress = shiroProgress;
        widgets.status = shiroStatus;
        widgets.outputTabs = shiroOutputTabs;
        widgets.detectOutput = shiroDetectOutput;
        widgets.crackOutput = shiroCrackOutput;
        widgets.buildOutput = shiroBuildOutput;
        widgets.runOutput = shiroRunOutput;
        setContent(ShiroPage.build(widgets, fonts));
    }

    private shiro.ShiroEngine.Options shiroOptions() {
        shiro.ShiroEngine.Options options = new shiro.ShiroEngine.Options();
        options.url = shiroUrl.getText().trim();
        options.method = String.valueOf(shiroRequestMethod.getSelectedItem());
        options.cookieName = Platform.valueOr(shiroCookieName.getText().trim(), shiro.ShiroEngine.REMEMBER_ME);
        options.extraHeaders = shiroHeaders.getText();
        options.body = shiroBody.getText();
        options.timeoutSeconds = Integer.parseInt(Platform.valueOr(timeout.getText(), "8"));
        return options;
    }

    /** 往指定功能的回显框追加内容，并把它滚到底部。 */
    private void shiroAppend(JTextArea target, String text) {
        target.append(text);
        if (!text.endsWith(System.lineSeparator())) target.append(System.lineSeparator());
        target.setCaretPosition(target.getDocument().getLength());
    }

    /** 切换输出区页签（按标题查找，找不到则保持当前页签）。 */
    private void shiroShowTab(String title) {
        for (int index = 0; index < shiroOutputTabs.getTabCount(); index++) {
            if (title.equals(shiroOutputTabs.getTitleAt(index))) {
                shiroOutputTabs.setSelectedIndex(index);
                return;
            }
        }
    }

    private void startShiroDetect() {
        final shiro.ShiroEngine.Options options = shiroOptions();
        if (options.url.isEmpty()) {
            shiroStatus.setText("目标 URL 不能为空");
            return;
        }
        shiroStatus.setText("正在检测 Shiro 指纹…");
        shiroDetectOutput.setText("");
        shiroShowTab("指纹检测");
        shiroAppend(shiroDetectOutput, "===== Shiro 指纹检测 =====");
        shiroAppend(shiroDetectOutput, "目标：" + options.url + "（" + options.method + "）");
        new Thread(() -> {
            shiro.ShiroEngine.FingerprintResult result = shiro.ShiroEngine.detect(options);
            SwingUtilities.invokeLater(() -> {
                for (String line : result.evidence) shiroAppend(shiroDetectOutput, line);
                shiroAppend(shiroDetectOutput, result.message);
                if (result.shiroDetected) {
                    shiroAppend(shiroDetectOutput, "下一步：可点击「密钥爆破」用内置 "
                            + shiro.ShiroEngine.builtinKeys().size() + " 条字典识别密钥。");
                    shiroStatus.setText("确认存在 Shiro，等待密钥识别");
                } else {
                    shiroStatus.setText("未确认 Shiro");
                }
            });
        }, "shiro-detect").start();
    }

    private void startShiroCrack() {
        final shiro.ShiroEngine.Options options = shiroOptions();
        if (options.url.isEmpty()) {
            shiroStatus.setText("目标 URL 不能为空");
            return;
        }
        final java.util.List<String> keys = shiro.ShiroEngine.builtinKeys();
        if (keys.isEmpty()) {
            shiroStatus.setText("内置密钥字典为空，请确认资源文件已打包");
            return;
        }
        final boolean gcm = shiroGcm.isSelected();
        shiroCancel.set(false);
        shiroProgress.setVisible(true);
        shiroProgress.setMaximum(keys.size());
        shiroProgress.setValue(0);
        shiroProgress.setString("0 / " + keys.size());
        shiroShowTab("密钥爆破");
        shiroAppend(shiroCrackOutput, "===== Shiro 密钥爆破 =====");
        shiroAppend(shiroCrackOutput, "字典 " + keys.size() + " 条，加密方式 "
                + shiro.ShiroEngine.describeMode(gcm));
        shiroStatus.setText("正在爆破密钥…");
        new Thread(() -> {
            shiro.ShiroEngine.FingerprintResult fingerprint = shiro.ShiroEngine.detect(options);
            if (!fingerprint.shiroDetected) {
                SwingUtilities.invokeLater(() -> {
                    shiroAppend(shiroCrackOutput, "未确认 Shiro，已终止爆破：" + fingerprint.message);
                    shiroStatus.setText("未确认 Shiro，爆破终止");
                    shiroProgress.setVisible(false);
                });
                return;
            }
            final int baseline = fingerprint.probeDeleteMe;
            shiro.ShiroEngine.KeyResult result = shiro.ShiroEngine.crack(options, keys, gcm, baseline, 8,
                    new shiro.ShiroEngine.CrackListener() {
                        @Override public void onProgress(int tested, int total, String currentKey) {
                            SwingUtilities.invokeLater(() -> {
                                shiroProgress.setValue(tested);
                                shiroProgress.setString(tested + " / " + total);
                            });
                        }

                        @Override public void onFound(String key, boolean foundGcm, int tested) {
                            SwingUtilities.invokeLater(() -> shiroAppend(shiroCrackOutput, "命中密钥：" + key
                                    + "（第 " + tested + " 条，" + shiro.ShiroEngine.describeMode(foundGcm) + "）"));
                        }
                    }, shiroCancel);
            SwingUtilities.invokeLater(() -> {
                shiroProgress.setVisible(false);
                shiroAppend(shiroCrackOutput, result.message);
                if (result.matched) {
                    shiroCrackedKey = result.key;
                    shiroCrackedGcm = result.gcm;
                    shiroKey.setText(result.key);
                    shiroGcm.setSelected(result.gcm);
                    shiroStatus.setText("已识别密钥：" + result.key);
                } else {
                    shiroStatus.setText("未识别到密钥（已试 " + result.tested + " 条）");
                }
            });
        }, "shiro-crack").start();
    }

    private void buildShiroPayload() {
        final shiro.ShiroExploit.ChainKind kind = (shiro.ShiroExploit.ChainKind) shiroChain.getSelectedItem();
        final String command = shiroCommand.getText().trim();
        final String key = shiroKey.getText().trim();
        final boolean gcm = shiroGcm.isSelected();
        if (key.isEmpty()) {
            shiroStatus.setText("请先填写 Shiro 密钥");
            return;
        }
        shiroShowTab("生成 Payload");
        shiroAppend(shiroBuildOutput, "===== 生成 Payload =====");
        new Thread(() -> {
            shiro.ShiroExploit exploit = new shiro.ShiroExploit(shiroOptions(), key, gcm,
                    shiroEchoHeader.getText().trim());
            if (!command.isEmpty()) {
                shiro.ChainsEngine.Generated generated = exploit.buildCommandPayload(kind, command);
                SwingUtilities.invokeLater(() -> {
                    shiroAppend(shiroBuildOutput, "链条：" + kind.display() + " → exec（" + command + "）");
                    shiroAppend(shiroBuildOutput, generated.success
                            ? generated.message + "\n" + generated.payload : generated.message);
                    shiroStatus.setText(generated.success ? "Payload 生成完成" : "Payload 生成失败");
                });
                return;
            }
            shiro.ChainsEngine.Generated generated = exploit.buildEchoPayload(kind);
            SwingUtilities.invokeLater(() -> {
                shiroAppend(shiroBuildOutput, "链条：" + kind.display());
                shiroAppend(shiroBuildOutput, generated.success
                        ? generated.message + "\n" + generated.payload : generated.message);
                shiroStatus.setText(generated.success ? "Payload 生成完成" : "Payload 生成失败");
            });
        }, "shiro-build").start();
    }

    private void runShiroCommand() {
        final shiro.ShiroExploit.ChainKind kind = (shiro.ShiroExploit.ChainKind) shiroChain.getSelectedItem();
        final String command = shiroCommand.getText().trim();
        final String key = shiroKey.getText().trim();
        final boolean gcm = shiroGcm.isSelected();
        final shiro.ShiroEngine.Options options = shiroOptions();
        if (options.url.isEmpty() || command.isEmpty() || key.isEmpty()) {
            shiroStatus.setText("目标 URL、密钥、命令都不能为空");
            return;
        }
        shiroShowTab("执行命令");
        shiroAppend(shiroRunOutput, "===== 执行命令 =====");
        shiroStatus.setText("正在执行命令…");
        new Thread(() -> {
            shiro.ShiroExploit exploit = new shiro.ShiroExploit(options, key, gcm, shiroEchoHeader.getText().trim());
            try {
                shiro.ShiroExploit.Result result = exploit.execute(kind, command);
                SwingUtilities.invokeLater(() -> {
                    for (String line : result.steps) shiroAppend(shiroRunOutput, line);
                    shiroStatus.setText(result.message);
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    shiroAppend(shiroRunOutput, "执行失败：" + error.getMessage());
                    shiroStatus.setText("执行失败");
                });
            }
        }, "shiro-run").start();
    }

    private JPanel pagePanel() { return UiKit.page(); }

    private JPanel surface(LayoutManager layout) { return UiKit.surface(layout); }

    private JLabel label(String text, int style, int size, Color color) {
        return UiKit.label(text, style, size, color, fonts);
    }

    private JButton primaryButton(String text) { return UiKit.primaryButton(text, fonts); }

    private void styleButton(JButton button) { UiKit.stylePrimaryButton(button, fonts); }

    private void styleSecondaryButton(JButton button) { UiKit.styleSecondaryButton(button, fonts); }

    private void styleField(JTextField field) { UiKit.styleField(field, fonts); }

    private void styleSwitch(JCheckBox box) { UiKit.styleSwitch(box, fonts); }

    /** 登记需要随窗口缩放的字体；实现 {@link ui.UiKit.FontSink}，供 UiKit 回调。 */
    private final ui.UiKit.FontSink fonts = (component, style, baseSize) ->
            this.fontBindings.add(new FontBinding(component, style, baseSize));

    private void updateScale() {
        double scale = UiKit.scaleFor(frame.getWidth(), frame.getHeight());
        for (FontBinding binding : fontBindings) {
            binding.component.setFont(new Font(binding.family(), binding.style,
                    Math.max(11, (int) Math.round(binding.baseSize * scale))));
        }
        if (navigation != null) navigation.setPreferredSize(UiKit.scaledSidebar(scale));
        if (navigationList != null) navigationList.setFixedCellHeight(UiKit.scaledNavRowHeight(scale));
        frame.revalidate();
    }

    private static GridBagConstraints constraints() { return UiKit.constraints(); }

    private void track(JComponent component, int style, int baseSize) {
        fonts.track(component, style, baseSize);
    }

    private void setContent(JPanel panel) {
        content.removeAll();
        content.add(panel, BorderLayout.CENTER);
        content.revalidate();
        content.repaint();
        updateScale();
    }

    private void startDetection() {
        String url = target.getText().trim();
        final String probeTimeout = timeout.getText().trim();
        final List<String> modes = modeKeys();
        if (url.isEmpty()) { result.setText("目标 URL 不能为空。\n"); return; }
        if (modes.isEmpty()) { result.setText("请至少勾选一个探测模式。\n"); return; }
        if (modes.contains("expect") && baseBody.getText().trim().isEmpty()) {
            result.setText("期望类探测需要填写业务参数，例如 {\"age\":20,\"name\":\"Bob\"}。\n"); return;
        }
        detect.setEnabled(false); status.setText("探测中..."); result.setText("正在发送无害识别请求，请等待结果...\n");
        Thread worker = new Thread(() -> {
            StringBuilder text = new StringBuilder();
            for (String mode : modes) {
                // 引擎已按模式输出中文报告（含分段标题），这里只负责按固定顺序拼接
                String segment;
                try { segment = runProbe(url, probeTimeout, mode); } catch (Exception e) { segment = "启动探测失败: " + e; }
                text.append(segment);
                // 报告自带换行；仅在缺失时补一个，避免段间空行挤占结果区
                if (!segment.endsWith("\n")) text.append(System.lineSeparator());
            }
            final String finalOutput = text.toString();
            SwingUtilities.invokeLater(() -> { result.setText(finalOutput); detect.setEnabled(true); status.setText("探测完成"); });
        }, "fastjson-probe");
        worker.setDaemon(true);
        worker.start();
    }

    /** 勾选的探测模式，按固定顺序返回对应的引擎 mode（含 DNS 探针 / CEYE 确认）。 */
    private List<String> modeKeys() {
        List<String> keys = new ArrayList<String>();
        if (modeDetect.isSelected()) keys.add("detect");
        if (modeVersion.isSelected()) keys.add("version");
        if (modeExpect.isSelected()) keys.add("expect");
        if (dnsEnabled.isSelected()) keys.add("dns");
        if (ceyeEnabled.isSelected()) keys.add("ceye");
        return keys;
    }

    private void startCapture() {
        final String url = captureUrl.getText().trim();
        if (url.isEmpty()) { captureResult.setText("目标 URL 不能为空。\n"); return; }
        final String method = String.valueOf(captureMethod.getSelectedItem());
        final String contentType = captureContentType.getText().trim();
        final String headers = captureHeaders.getText().trim();
        final String body = captureBody.getText();
        final String target = String.valueOf(captureTarget.getSelectedItem());
        captureRun.setEnabled(false);
        captureStatus.setText("抓包中...");
        captureResult.setText("正在发送 " + method + " 请求并等待响应...\n");
        Thread worker = new Thread(() -> {
            String output;
            try {
                output = runCapture(url, method, contentType, headers, body, target);
            } catch (Exception e) {
                output = "启动抓包失败: " + e + System.lineSeparator();
            }
            final String finalOutput = output;
            SwingUtilities.invokeLater(() -> {
                captureResult.setText(finalOutput);
                captureResult.setCaretPosition(0);
                captureRun.setEnabled(true);
                captureStatus.setText("抓包完成");
            });
        }, "capture-probe");
        worker.setDaemon(true);
        worker.start();
    }

    private void startConvert() {
        final String pasted = pastedRequest.getText().trim();
        final String target = String.valueOf(captureTarget.getSelectedItem());
        final String url = captureUrl.getText().trim();
        final String headers = captureHeaders.getText().trim();
        final String body = captureBody.getText();
        final String method = String.valueOf(captureMethod.getSelectedItem());
        final String contentType = captureContentType.getText().trim();
        captureConvert.setEnabled(false);
        captureStatus.setText("转换中...");
        Thread worker = new Thread(() -> {
            String output;
            try {
                output = runConvert(pasted, url, method, contentType, headers, body, target);
            } catch (Exception e) {
                output = "转换失败: " + e + System.lineSeparator();
            }
            final String finalOutput = output;
            SwingUtilities.invokeLater(() -> {
                captureResult.setText(finalOutput);
                captureResult.setCaretPosition(0);
                captureConvert.setEnabled(true);
                captureStatus.setText("转换完成");
            });
        }, "convert-probe");
        worker.setDaemon(true);
        worker.start();
    }

    /** 把抓包得到的 URL / 方法 / 请求头 / 请求体回填到 Fastjson 探测页。 */
    private void fillProbeFromCapture() {
        String url = probeUrlFromCapture();
        if (url.isEmpty()) { captureStatus.setText("请先填写或抓取一个 URL"); return; }
        target.setText(url);
        String capturedMethod = String.valueOf(captureMethod.getSelectedItem());
        probeMethod.setSelectedItem(capturedMethod);
        String headers = CaptureBridge.probeHeaders(captureInput());
        if (!headers.isEmpty()) requestHeaders.setText(headers);
        String bodyText = captureBody.getText().trim();
        if (!bodyText.isEmpty()) baseBody.setText(bodyText);
        selectNav("fastjson.detect");
        captureStatus.setText("已填入探测页");
    }

    private String probeUrlFromCapture() {
        String typed = captureUrl.getText().trim();
        if (!typed.isEmpty()) return typed;
        String parsed = JsonText.field(captureResult.getText(), "url");
        if (!parsed.isEmpty()) return parsed;
        String raw = JsonText.requestTarget(captureResult.getText());
        return raw;
    }

    /**
     * 一键发送：把刚抓到的请求原样交给选中的功能，不做自动探测。
     *
     * <p>与「填入探测页」的区别是这里不再手工复制粘贴，而是直接把 URL、方法、请求头、
     * 请求体整套搬过去并跳到对应页面，由使用者确认参数后再自己点「开始探测」；
     * 抓包阶段已经带上了真实 Cookie，因此需要登录态的接口可以直接用。
     *
     * <p>刻意**不**自动开跑：抓包得到的报文往往还需要调整探测模式、业务参数或期望类，
     * 一进来就发请求既浪费一次往返，也容易在参数还没确认时就把流量打到目标上。
     * 新增目标时只需在这里加一个分支。
     */
    private void sendCaptureTo() {
        String url = probeUrlFromCapture();
        if (url.isEmpty()) {
            captureStatus.setText("请先填写或抓取一个 URL");
            return;
        }
        String destination = String.valueOf(captureSendTo.getSelectedItem());
        String method = captureMethodOf();
        String cookieHeader = CaptureBridge.cookieHeader(captureInput());
        if ("Shiro 漏洞利用".equals(destination)) {
            sendCaptureToShiro(url, method, cookieHeader);
            return;
        }
        sendCaptureToFastjson(url, method, cookieHeader);
    }

    /** 抓包结果送往 Fastjson 探测页，等待使用者确认参数后自己开跑。 */
    private void sendCaptureToFastjson(String url, String method, String cookieHeader) {
        target.setText(url);
        probeMethod.setSelectedItem(method);
        String headers = CaptureBridge.probeHeaders(captureInput());
        if (headers.isEmpty() && !cookieHeader.isEmpty()) headers = CaptureBridge.headerJson(cookieHeader);
        if (!headers.isEmpty()) requestHeaders.setText(headers);
        String bodyText = captureBody.getText().trim();
        if (!bodyText.isEmpty()) baseBody.setText(bodyText);
        selectNav("fastjson.detect");
        captureStatus.setText("已发送到 Fastjson 探测，确认参数后点「开始探测」");
    }

    /**
     * 抓包结果送往 Shiro 页，等待使用者确认参数后自己开跑。
     *
     * <p>抓到的会话 Cookie 会连同其它请求头一起写进「附加请求头」，这样需要登录态的
     * 接口也能直接检测；不带任何抓包结果时会退回当前页面上已有的配置。
     */
    private void sendCaptureToShiro(String url, String method, String cookieHeader) {
        shiroUrl.setText(url);
        shiroRequestMethod.setSelectedItem(method);
        String lines = CaptureBridge.headerLines(captureInput(), cookieHeader);
        if (!lines.isEmpty()) shiroHeaders.setText(lines);
        // 请求体必须一并带过去：抓包得到的 POST 体通常就是接口的业务参数，
        // 不带上则目标只会返回校验错误，探测结论不可信。
        shiroBody.setText(CaptureBridge.requestBody(captureInput()));
        captureStatus.setText("已发送到 Shiro 漏洞利用，确认参数后点「一键检测」");
    }

    /** 抓包页当前输入（请求头 JSON / 粘贴报文 / 抓包结果），供参数转换复用。 */
    private CaptureBridge.Input captureInput() {
        CaptureBridge.Input input = new CaptureBridge.Input();
        input.headersJson = captureHeaders.getText();
        input.pastedRequest = pastedRequest.getText();
        input.captureResult = captureResult.getText();
        input.body = captureBody.getText();
        return input;
    }

    /** 抓包页当前选择的请求方法（探测页 / Shiro 页按同一方法发请求）。 */
    private String captureMethodOf() {
        Object selected = captureMethod.getSelectedItem();
        String method = selected == null ? "" : String.valueOf(selected).trim();
        return method.isEmpty() ? "POST" : method;
    }

    private String runCapture(String url, String method, String contentType, String headers,
                              String body, String target) throws Exception {
        return ProbeEngine.run(captureCommand(url, method, contentType, headers, body, target, true));
    }

    private String runConvert(String pasted, String url, String method, String contentType,
                              String headers, String body, String target) throws Exception {
        List<String> command = captureCommand(url, method, contentType, headers, body, target, false);
        if (!pasted.isEmpty()) { command.add("--pasted-request"); command.add(Platform.commandArg(pasted)); }
        command.add("--mode"); command.add("convert");
        return ProbeEngine.run(command);
    }

    /** 抓包 / 转换共用的启动参数；具体拼装交给 {@link ProbeCommand}。 */
    private List<String> captureCommand(String url, String method, String contentType, String headers,
                                        String body, String target, boolean capture) throws Exception {
        ProbeCommand.Options options = new ProbeCommand.Options();
        options.python = ProbeEngine.resolvePython(config.getProperty("python", ""));
        options.script = ProbeEngine.extractScript().toString();
        options.timeout = Platform.valueOr(timeout.getText(), "8");
        options.method = method;
        options.contentType = contentType;
        options.headers = headers;
        options.body = body;
        options.captureUrl = url;
        options.convertTargets = target;
        options.capture = capture;
        return ProbeCommand.capture(options);
    }

    private String runProbe(String url, String seconds, String mode) throws Exception {
        if ("ceye".equals(mode) && !hasCeyeCredential()) {
            return "CEYE 确认需要 Token：请在「配置」页填写 CEYE Token，或设置 CEYE_TOKEN 环境变量后重试。"
                    + System.lineSeparator();
        }
        ProbeCommand.Options options = new ProbeCommand.Options();
        options.python = ProbeEngine.resolvePython(config.getProperty("python", ""));
        options.script = ProbeEngine.extractScript().toString();
        options.target = url;
        options.timeout = seconds;
        options.mode = mode;
        options.probeMethod = String.valueOf(probeMethod.getSelectedItem());
        options.baseBody = baseBody.getText().trim();
        options.headers = requestHeaders.getText().trim();
        options.sessionCookie = sessionCookie.getText().trim();
        options.report = config.getProperty("probe_report", "brief");
        options.dnslogHost = dnslogHost.getText().trim();
        options.dnsWait = dnsWait.getText().trim();
        options.dnsFilter = dnsFilter.getText().trim();
        options.ceyeToken = config.getProperty("ceye_token", "").trim();
        options.ceyeDomain = config.getProperty("ceye_domain", "").trim();
        return ProbeEngine.run(ProbeCommand.probe(options));
    }

    /** CEYE Token 来源与 Python 侧一致：配置页写值 > CEYE_TOKEN / FJ_CEYE_TOKEN 环境变量。 */
    private boolean hasCeyeCredential() {
        if (!config.getProperty("ceye_token", "").trim().isEmpty()) return true;
        return !Platform.env("CEYE_TOKEN").isEmpty() || !Platform.env("FJ_CEYE_TOKEN").isEmpty();
    }

    /** 读取用户目录下的 config.properties；文件不存在属首次启动，静默跳过。 */
    private void loadConfig() {
        try {
            AppConfig.load(config);
        } catch (IllegalStateException e) {
            configStatus.setText(e.getMessage());
        }
    }

    /** 启动与保存后统一下发配置：探测页 + 代理 / 抓包转换 / Shiro 页。 */
    private void applyConfigToForms() {
        timeout.setText(config.getProperty("timeout", "8"));
        String configuredMethod = config.getProperty("probe_method", "POST").trim().toUpperCase();
        for (int index = 0; index < probeMethod.getItemCount(); index++) {
            if (probeMethod.getItemAt(index).equals(configuredMethod)) {
                probeMethod.setSelectedIndex(index);
                break;
            }
        }
        dnsWait.setText(config.getProperty("dns_wait", "13"));
        baseBody.setText(config.getProperty("base_body", ""));
        requestHeaders.setText(config.getProperty("headers", ""));
        sessionCookie.setText(config.getProperty("session_cookie", ""));
        dnslogHost.setText(dnslogHostFromConfig());
        dnsFilter.setText(config.getProperty("dns_filter", ""));
        applyConfigToToolForms();
        resetConfigForm();
        updateStageFieldState();
    }

    /**
     * 把代理 / 抓包转换 / Shiro 的持久化配置应用到对应页面控件。
     *
     * <p>这些功能此前只在页面里写死默认值，关掉程序就丢；统一在这里读取，与探测页
     * 共享同一次「配置 → 界面」的下发时机（启动时与保存后各一次）。
     */
    private void applyConfigToToolForms() {
        String bindHost = config.getProperty("proxy_bind_host", "").trim();
        if (!bindHost.isEmpty()) proxyBindHost.setText(bindHost);
        String port = config.getProperty("proxy_port", "").trim();
        if (!port.isEmpty()) proxyPort.setText(port);
        proxyIntercept.setSelected(flagFrom(config, "proxy_intercept", false));

        selectOption(captureMethod, config.getProperty("capture_method", "POST"));
        setComboByValue(configProbeReport, REPORT_VALUES, REPORT_LABELS,
                config.getProperty("probe_report", "brief"));
        String contentType = config.getProperty("capture_content_type", "").trim();
        if (!contentType.isEmpty()) captureContentType.setText(contentType);
        selectOption(captureTarget, config.getProperty("capture_target", "json"));

        String shiroTarget = config.getProperty("shiro_url", "").trim();
        if (!shiroTarget.isEmpty()) shiroUrl.setText(shiroTarget);
        String cookieName = config.getProperty("shiro_cookie_name", "").trim();
        if (!cookieName.isEmpty()) shiroCookieName.setText(cookieName);
        String key = config.getProperty("shiro_key", "").trim();
        if (!key.isEmpty()) shiroKey.setText(key);
        shiroGcm.setSelected(flagFrom(config, "shiro_gcm", false));
        String echoHeader = config.getProperty("shiro_echo_header", "").trim();
        if (!echoHeader.isEmpty()) shiroEchoHeader.setText(echoHeader);
        selectChain(config.getProperty("shiro_chain", ""));
        String command = config.getProperty("shiro_command", "").trim();
        if (!command.isEmpty()) shiroCommand.setText(command);
        String shiroBodyText = config.getProperty("shiro_body", "").trim();
        if (!shiroBodyText.isEmpty()) shiroBody.setText(shiroBodyText);
    }

    /** 勾选框型配置：只认 true/false，其他值按默认值处理。 */
    private static boolean flagFrom(Properties values, String key, boolean fallback) {
        String raw = values.getProperty(key, "").trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) return fallback;
        return "true".equals(raw) || "1".equals(raw) || "yes".equals(raw);
    }

    /** 报告详细度的配置值 / 显示文本，一一对应。 */
    private static final String[] REPORT_VALUES = {"brief", "detail"};
    private static final String[] REPORT_LABELS = {"精简", "详细"};

    /** 下拉框按候选项文本选中；配置值不存在时保持原选择。 */
    private static void selectOption(JComboBox<String> combo, String value) {
        String wanted = value == null ? "" : value.trim();
        if (wanted.isEmpty()) return;
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (combo.getItemAt(index).equalsIgnoreCase(wanted)) {
                combo.setSelectedIndex(index);
                return;
            }
        }
    }

    /** 配置值（英文）与显示文本（中文）分离的下拉框：按下标对应选中。 */
    private static void setComboByValue(JComboBox<String> combo, String[] values,
                                        String[] labels, String wanted) {
        String target = wanted == null ? "" : wanted.trim().toLowerCase(Locale.ROOT);
        for (int index = 0; index < values.length && index < labels.length; index++) {
            if (values[index].equals(target)) {
                combo.setSelectedItem(labels[index]);
                return;
            }
        }
        combo.setSelectedIndex(0);
    }

    /** 按枚举名选中利用链；找不到则保持默认。 */
    private void selectChain(String name) {
        String wanted = name == null ? "" : name.trim();
        if (wanted.isEmpty()) return;
        for (shiro.ShiroExploit.ChainKind kind : shiro.ShiroExploit.ChainKind.values()) {
            if (kind.name().equalsIgnoreCase(wanted) || kind.label.equals(wanted)) {
                shiroChain.setSelectedItem(kind);
                return;
            }
        }
    }

    /** DNSLog 主机优先取专用配置，未单独填写时回落到 CEYE 域名（两者同为 dnslog 域）。 */
    private String dnslogHostFromConfig() {
        String host = config.getProperty("dnslog_host", "").trim();
        return host.isEmpty() ? config.getProperty("ceye_domain", "").trim() : host;
    }

    /** 按模式勾选刷新输入框可用性：未勾选的模式不允许再输入该阶段参数。 */
    private void updateStageFieldState() {
        boolean dnsStage = dnsEnabled.isSelected();
        boolean ceyeStage = ceyeEnabled.isSelected();
        if (dnslogHost == null) return;
        setFieldEnabled(baseBody, modeExpect.isSelected());
        setFieldEnabled(dnslogHost, dnsStage);
        setFieldEnabled(dnsWait, dnsStage);
        setFieldEnabled(dnsFilter, ceyeStage);
    }

    private void setFieldEnabled(JTextField field, boolean enabled) {
        field.setEnabled(enabled);
        field.setBackground(enabled ? Color.WHITE : new Color(241, 245, 249));
        field.setForeground(enabled ? TEXT : MUTED);
        field.setDisabledTextColor(MUTED);
        field.setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.TEXT_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private void resetConfigForm() {
        configCeyeDomain.setText(config.getProperty("ceye_domain", ""));
        configCeyeToken.setText(config.getProperty("ceye_token", ""));
        configCeyeApi.setText(config.getProperty("ceye_api", ""));
        configPython.setText(config.getProperty("python", ""));
        configTimeout.setText(config.getProperty("timeout", "8"));
        configProbeMethod.setText(config.getProperty("probe_method", "POST"));
        configDnsWait.setText(config.getProperty("dns_wait", "13"));
        configBaseBody.setText(config.getProperty("base_body", ""));
        configHeaders.setText(config.getProperty("headers", ""));
        configSessionCookie.setText(config.getProperty("session_cookie", ""));
        configDnslogHost.setText(config.getProperty("dnslog_host", ""));
        configDnsFilter.setText(config.getProperty("dns_filter", ""));
        configProxyBindHost.setText(config.getProperty("proxy_bind_host", ""));
        configProxyPort.setText(config.getProperty("proxy_port", "8899"));
        configProxyIntercept.setSelected(flagFrom(config, "proxy_intercept", false));
        selectOption(configCaptureMethod, config.getProperty("capture_method", "POST"));
        configCaptureContentType.setText(config.getProperty("capture_content_type", "application/json"));
        selectOption(configCaptureTarget, config.getProperty("capture_target", "json"));
        setComboByValue(configProbeReport, REPORT_VALUES, REPORT_LABELS,
                config.getProperty("probe_report", "brief"));
        configShiroUrl.setText(config.getProperty("shiro_url", ""));
        configShiroCookieName.setText(config.getProperty("shiro_cookie_name", "rememberMe"));
        configShiroKey.setText(config.getProperty("shiro_key", ""));
        configShiroGcm.setSelected(flagFrom(config, "shiro_gcm", false));
        configShiroEchoHeader.setText(config.getProperty("shiro_echo_header", ""));
        selectConfigChain(config.getProperty("shiro_chain", ""));
        configShiroCommand.setText(config.getProperty("shiro_command", ""));
        configShiroBody.setText(config.getProperty("shiro_body", ""));
        configStatus.setText("已载入当前配置");
    }

    /** 配置页的利用链下拉框按下拉项选中。 */
    private void selectConfigChain(String name) {
        String wanted = name == null ? "" : name.trim();
        if (wanted.isEmpty()) {
            configShiroChain.setSelectedIndex(0);
            return;
        }
        for (shiro.ShiroExploit.ChainKind kind : shiro.ShiroExploit.ChainKind.values()) {
            if (kind.name().equalsIgnoreCase(wanted) || kind.label.equals(wanted)) {
                configShiroChain.setSelectedItem(kind);
                return;
            }
        }
        configShiroChain.setSelectedIndex(0);
    }

    private void saveConfigFromForm() {
        config.setProperty("ceye_domain", configCeyeDomain.getText().trim());
        config.setProperty("ceye_token", new String(configCeyeToken.getPassword()).trim());
        config.setProperty("ceye_api", configCeyeApi.getText().trim());
        config.setProperty("python", configPython.getText().trim());
        config.setProperty("timeout", Platform.valueOr(configTimeout.getText(), "8"));
        config.setProperty("probe_method", Platform.valueOr(configProbeMethod.getText().toUpperCase(), "POST"));
        config.setProperty("dns_wait", Platform.valueOr(configDnsWait.getText(), "13"));
        config.setProperty("base_body", configBaseBody.getText().trim());
        config.setProperty("headers", configHeaders.getText().trim());
        config.setProperty("session_cookie", configSessionCookie.getText().trim());
        config.setProperty("dnslog_host", configDnslogHost.getText().trim());
        config.setProperty("dns_filter", configDnsFilter.getText().trim());
        config.setProperty("proxy_bind_host", configProxyBindHost.getText().trim());
        config.setProperty("proxy_port", Platform.valueOr(configProxyPort.getText(), "8899"));
        config.setProperty("proxy_intercept", String.valueOf(configProxyIntercept.isSelected()));
        config.setProperty("capture_method", String.valueOf(configCaptureMethod.getSelectedItem()));
        config.setProperty("capture_content_type", Platform.valueOr(configCaptureContentType.getText(),
                "application/json"));
        config.setProperty("capture_target", String.valueOf(configCaptureTarget.getSelectedItem()));
        config.setProperty("probe_report",
                REPORT_VALUES[Math.max(0, configProbeReport.getSelectedIndex())]);
        config.setProperty("shiro_url", configShiroUrl.getText().trim());
        config.setProperty("shiro_cookie_name", Platform.valueOr(configShiroCookieName.getText(),
                shiro.ShiroEngine.REMEMBER_ME));
        config.setProperty("shiro_key", configShiroKey.getText().trim());
        config.setProperty("shiro_gcm", String.valueOf(configShiroGcm.isSelected()));
        config.setProperty("shiro_echo_header", configShiroEchoHeader.getText().trim());
        Object chain = configShiroChain.getSelectedItem();
        config.setProperty("shiro_chain", chain == null ? "" : ((shiro.ShiroExploit.ChainKind) chain).name());
        config.setProperty("shiro_command", configShiroCommand.getText().trim());
        config.setProperty("shiro_body", configShiroBody.getText().trim());
        try {
            AppConfig.save(config);
            applyConfigToForms();
            configStatus.setText("已保存到 " + AppConfig.FILE);
        } catch (IOException e) {
            configStatus.setText("保存失败：" + e.getMessage());
        }
    }

    private void show() { frame.setVisible(true); }

    public static void main(String[] args) { SwingUtilities.invokeLater(() -> new Main().show()); }

    private static final class FontBinding {
        private final JComponent component;
        private final int style;
        private final int baseSize;

        private FontBinding(JComponent component, int style, int baseSize) {
            this.component = component;
            this.style = style;
            this.baseSize = baseSize;
        }

        private String family() {
            return component.getFont() == null ? Font.SANS_SERIF : component.getFont().getFamily();
        }
    }

    private static final class ConfigRow {
        private final String label;
        private final JComponent field;
        private final String hint;

        private ConfigRow(String label, JComponent field, String hint) {
            this.label = label;
            this.field = field;
            this.hint = hint;
        }
    }
}
