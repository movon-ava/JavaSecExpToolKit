package ui;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.SwingUtilities;

import proxy.ProxyServer;

/**
 * 代理抓包页的行为：启停监听、拦截改包放行、流量回填、导出到抓包页。
 *
 * <p>从界面层抽出的第一个执行角色页：它只依赖 {@link ProxyServer}，不依赖任何其它
 * 功能页的状态，因此搬迁风险最低。控件仍由界面层持有并通过 {@link ProxyPage.Widgets}
 * 注入，本类不 new 控件，反复进出页面不会累积状态。
 *
 * <p>拦截相关的状态（当前待放行流量、放行决定、是否已编辑报文）全部收在本类里：
 * 它们只服务于拦截这一件事，放在界面层会让「谁改了 pendingFlow」难以追踪。
 */
public final class ProxyController implements ActionListener {

    /** 拦截等待上限：超时自动放行，避免浏览器一直卡住。 */
    private static final long INTERCEPT_TIMEOUT_MS = 120000;

    private final ProxyPage.Widgets widgets;
    private final CaptureController capture;
    private final ConfigController config;

    private ProxyServer server;
    /** 上一次成功监听的端口；端口框被随机端口回填后再启动时以它为准。 */
    private int lastPort;
    /** 最近一条被放行的流量，用于把请求包抓到抓包转换页。 */
    private volatile ProxyServer.HttpFlow lastFlow;
    /**
     * 正在等返回包的那条流量 id（放行或丢弃后记下）。
     *
     * <p>拦截开启时返回包面板只认它：排队接位的下一条请求、浏览器后台的无关请求都不能
     * 覆盖它，否则使用者刚放行看到的「已放行，等待返回包…」会被别人的响应冲掉。
     */
    private volatile long awaitingResponseFlowId;
    /** 拦截等待：连接线程在这里挂起，直到界面点击放行/丢弃或超时。 */
    private final Object interceptLock = new Object();
    private volatile ProxyServer.HttpFlow pendingFlow;
    /** 拦截总开关：与勾选框同步，供连接线程判断是否还需要继续等待。 */
    private volatile boolean interceptActive;
    private volatile int interceptDecision;
    private volatile byte[] forwardedHead;
    private volatile byte[] forwardedBody;

    public ProxyController(ProxyPage.Widgets widgets, CaptureController capture, ConfigController config) {
        this.widgets = widgets;
        this.capture = capture;
        this.config = config;
        wire();
    }

    private void wire() {
        widgets.toggle.addActionListener(this);
        widgets.clear.addActionListener(this);
        widgets.export.addActionListener(this);
        widgets.intercept.addActionListener(this);
        widgets.forward.addActionListener(this);
        widgets.drop.addActionListener(this);
        // 面板重建后同步一次：请求包可否编辑取决于拦截开关与运行状态
        widgets.onBuilt = this::updateInterceptState;
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        Object source = event.getSource();
        if (source == widgets.toggle) toggle();
        else if (source == widgets.clear) clearFlows();
        else if (source == widgets.export) exportDetail();
        else if (source == widgets.intercept) updateInterceptState();
        else if (source == widgets.forward) forward();
        else if (source == widgets.drop) drop();
    }

    /**
     * 记住最近一次成功监听的地址与端口。
     *
     * <p>使用者常会反复调整端口；把它写回同一份配置，下次启动与下次打开配置页都能看到
     * 实际用过的值——否则配置页里的「代理端口」只是个从不生效的摆设。
     */
    public void rememberEndpoint(String host, int port) {
        config.rememberProxyEndpoint(host, port);
    }

    /**
     * 同步拦截开关到运行中的代理，并决定放行 / 丢弃是否可用。
     *
     * <p>拦截器必须在这里装卸，而不能只在启动时判断一次：代理先启动、随后才勾选「拦截请求」
     * 是最常见的用法，只判断一次会导致勾选了却完全不拦截。
     */
    public void updateInterceptState() {
        boolean running = server != null && server.isRunning();
        boolean intercepting = running && widgets.intercept.isSelected();
        interceptActive = intercepting;
        if (running) server.setInterceptor(intercepting ? this::interceptRequest : null);
        widgets.forward.setEnabled(intercepting);
        widgets.drop.setEnabled(intercepting);
        // 只有拦截开启时请求包才可编辑：未拦截时面板是纯观察视图，
        // 若允许编辑，后续流量一来就会被覆盖，使用者改的内容既发不出去也留不住。
        widgets.requestText.setEditable(intercepting);
        if (!intercepting) {
            // 关掉拦截后放走可能正卡在等待里的请求，面板回到纯观察模式
            awaitingResponseFlowId = 0;
            releasePendingUnchanged();
        }
    }

    /** 关闭拦截时放走仍在等待的请求：不改包直接放行，避免浏览器一直挂着。 */
    public void releasePendingUnchanged() {
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
     * <p>端口以输入框为准（留空按 8899），启动失败时状态栏会显示具体原因；
     * 记住上次成功启动的端口，避免下次启动被输入框里残留的随机端口带偏。
     */
    public void toggle() {
        if (server != null && server.isRunning()) {
            server.setInterceptor(null);
            server.stop();
            widgets.toggle.setText("启动代理");
            widgets.status.setText("代理已停止");
            updateInterceptState();
            return;
        }
        String bindHost = widgets.bindHost.getText().trim();
        if (bindHost.isEmpty()) bindHost = ProxyServer.defaultBindHost();
        int requested = lastPort > 0 ? lastPort : 8899;
        String typed = widgets.port.getText().trim();
        if (!typed.isEmpty()) {
            try {
                requested = Integer.parseInt(typed);
            } catch (NumberFormatException e) {
                widgets.status.setText("端口必须是数字");
                return;
            }
        }
        if (requested < 1 || requested > 65535) {
            widgets.status.setText("端口范围 1-65535");
            return;
        }
        ProxyServer started = new ProxyServer(500);
        started.addListener(flow -> SwingUtilities.invokeLater(() -> appendFlow(flow)));
        if (widgets.intercept.isSelected()) started.setInterceptor(this::interceptRequest);
        try {
            started.start(bindHost, requested);
        } catch (Exception e) {
            widgets.status.setText("启动失败：" + e.getMessage());
            return;
        }
        server = started;
        lastPort = requested;
        widgets.bindHost.setText(started.host());
        widgets.port.setText(String.valueOf(started.port()));
        rememberEndpoint(started.host(), started.port());
        widgets.toggle.setText("停止代理");
        widgets.status.setText("代理运行中：" + started.displayHost() + ":" + started.port());
        updateInterceptState();
    }

    /**
     * 请求拦截回调（运行在代理的连接线程上）。
     *
     * <p>把请求包展示到界面并阻塞等待放行；超过 {@link #INTERCEPT_TIMEOUT_MS} 未操作则自动放行，
     * 避免使用者的浏览器一直挂着。放行时读取界面上的请求包，因此直接改包即可生效。
     */
    public ProxyServer.Rewrite interceptRequest(ProxyServer.HttpFlow flow, byte[] head, byte[] body) {
        if (server == null || !server.isRunning() || !interceptActive) return null;
        String shown = FlowRenderer.request(flow, head, body);
        synchronized (interceptLock) {
            // 排队：同一时刻只让一个请求停在界面上，其余等轮到自己。
            // 否则多个连接同时进入会把彼此的待放行报文互相覆盖，放行时发错内容。
            long queueDeadline = System.currentTimeMillis() + INTERCEPT_TIMEOUT_MS;
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
                widgets.requestText.setText(shown);
                widgets.requestText.setCaretPosition(0);
                widgets.status.setText("已拦截，等待放行：" + flow.method + " " + flow.url());
            });

            long deadline = System.currentTimeMillis() + INTERCEPT_TIMEOUT_MS;
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
            if (dropped) return ProxyServer.Rewrite.DROP;
            return new ProxyServer.Rewrite(forwardedHead, forwardedBody);
        }
    }

    /** 放行：把界面上编辑后的请求包交给代理转发，并记录原始报文供返回包回溯。 */
    public void forward() {
        String edited = widgets.requestText.getText();
        synchronized (interceptLock) {
            if (pendingFlow == null) {
                widgets.status.setText("当前没有等待放行的请求");
                return;
            }
            byte[] head = util.HttpText.headerBytes(edited);
            if (head == null) {
                widgets.status.setText("请求包格式不正确：缺少空行分隔");
                return;
            }
            forwardedHead = head;
            forwardedBody = util.HttpText.bodyBytes(edited);
            lastFlow = pendingFlow;
            // 这条请求的返回包将占据面板；期间接位的下一条请求不得覆盖它
            awaitingResponseFlowId = pendingFlow.id;
            interceptDecision = 1;
            interceptLock.notifyAll();
        }
        widgets.status.setText("已放行，等待返回包…");
    }

    /** 丢弃：不连接上游，直接断开这条请求。 */
    public void drop() {
        synchronized (interceptLock) {
            if (pendingFlow == null) {
                widgets.status.setText("当前没有等待放行的请求");
                return;
            }
            awaitingResponseFlowId = pendingFlow.id;
            interceptDecision = 2;
            interceptLock.notifyAll();
        }
        widgets.status.setText("已丢弃该请求");
    }

    /**
     * 一条流量结束（或 HTTPS 隧道建立）时刷新界面。
     *
     * <p>拦截开启时，请求包面板由 {@link #interceptRequest} 单独负责：命中即写入待放行报文，
     * 使用者随后可以编辑。这里若再按「最近一条」刷新，浏览器后台的心跳、预连接等请求就会
     * 在等待放行期间不停冲掉正在编辑的请求包——这正是「未放行时面板还在刷新」的根因。
     * 因此拦截开启时请求包面板一刀不写，只按 {@code awaitingResponseFlowId} 回填返回包。
     *
     * <p>未开启拦截时属于纯观察，直接展示最近一条即可。
     */
    public void appendFlow(ProxyServer.HttpFlow flow) {
        if (widgets.intercept.isSelected()) {
            // 返回包只认刚放行 / 刚丢弃的那一条；一次响应写完就把归属清空，
            // 后续接位的请求不会把这块内容覆盖掉
            if (flow.id != awaitingResponseFlowId) return;
            awaitingResponseFlowId = 0;
        } else {
            // 观察模式下请求面板展示的就是这条流：必须记下来，否则未勾选「拦截请求」时
            // 点「转发到抓包转换」永远提示「还没有可转换的请求包」，抓包结果根本带不出去。
            lastFlow = flow;
            widgets.requestText.setText(FlowRenderer.rawRequest(flow));
            widgets.requestText.setCaretPosition(0);
        }
        widgets.responseText.setText(FlowRenderer.response(flow));
        widgets.responseText.setCaretPosition(0);
    }

    /** 清空代理侧记录并把两个面板复位到占位提示。 */
    public void clearFlows() {
        if (server != null) server.clear();
        widgets.requestText.setText(ProxyPage.REQUEST_PLACEHOLDER);
        widgets.responseText.setText(ProxyPage.RESPONSE_PLACEHOLDER);
        widgets.status.setText("已清空记录");
    }

    /** 把当前显示的请求包抓到抓包转换页，方便导出 Cookie 等格式。 */
    public void exportDetail() {
        String raw = widgets.requestText.getText();
        if (raw == null || raw.trim().isEmpty() || lastFlow == null) {
            widgets.status.setText("还没有可转换的请求包");
            return;
        }
        capture.importFromProxy(lastFlow, raw);
    }
}
