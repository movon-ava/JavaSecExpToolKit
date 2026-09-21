package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * 代理抓包页：监听控制条 + 请求 / 返回双栏。
 *
 * <p>这里只描述界面结构；代理的启停、拦截放行、流量回填仍由界面层驱动引擎完成，
 * 因此本页只接收控件与一个「构建完成」回调，不持有任何代理状态。
 */
public final class ProxyPage {

    /** 请求包占位提示：只在文本域为空时写入，避免切页回来时冲掉已捕获内容。 */
    public static final String REQUEST_PLACEHOLDER =
            "等待流量…\n\n启用「拦截请求」后，命中的请求会停在这里，编辑后点「放行」发往目标。";
    /** 返回包占位提示，与请求包同理。 */
    public static final String RESPONSE_PLACEHOLDER =
            "等待返回…\n\n点「放行」之后，目标返回的响应包会显示在这里。";

    /** 本页需要的控件与回调。 */
    public static final class Widgets {
        public JTextField bindHost;
        public JTextField port;
        public JButton toggle;
        public JButton clear;
        public JButton export;
        public JLabel status;
        public JCheckBox intercept;
        public JButton forward;
        public JButton drop;
        public JTextArea requestText;
        public JTextArea responseText;
        /** 面板构建完成后调用：同步拦截开关到运行中的代理。 */
        public Runnable onBuilt;
    }

    /** 本页控件的默认实例：监听地址取自引擎的本机联网 IP，端口默认 8899。 */
    public static Widgets defaults() {
        Widgets widgets = new Widgets();
        widgets.bindHost = new JTextField(proxy.ProxyServer.defaultBindHost(), 14);
        widgets.port = new JTextField("8899", 6);
        widgets.toggle = new JButton("启动代理");
        widgets.clear = new JButton("清空记录");
        widgets.export = new JButton("转发到抓包转换");
        widgets.intercept = new JCheckBox("拦截请求");
        widgets.forward = new JButton("放行");
        widgets.drop = new JButton("丢弃");
        widgets.status = new JLabel("代理未启动");
        widgets.requestText = new JTextArea();
        widgets.responseText = new JTextArea();
        return widgets;
    }

    private ProxyPage() {
    }

    public static JPanel build(Widgets widgets, UiKit.FontSink sink) {
        JPanel page = UiKit.page();
        page.add(UiKit.pageHeading("PROXY", "代理抓包",
                "浏览器 / 插件流量指向本地代理  ·  可拦截改包后放行  ·  授权目标", sink),
                BorderLayout.NORTH);

        JPanel work = new JPanel(new BorderLayout(0, 18));
        work.setOpaque(false);
        work.setBorder(BorderFactory.createEmptyBorder(28, 0, 0, 0));
        work.add(controlPanel(widgets, sink), BorderLayout.NORTH);
        work.add(bodyPanel(widgets, sink), BorderLayout.CENTER);
        page.add(work, BorderLayout.CENTER);
        // 面板构建完再同步一次：请求包是否可编辑取决于拦截开关与运行状态，
        // 必须在文本域样式落地之后调用，否则会被只读设置覆盖。
        if (widgets.onBuilt != null) widgets.onBuilt.run();
        return page;
    }

    private static JPanel controlPanel(Widgets widgets, UiKit.FontSink sink) {
        JPanel panel = UiKit.surface(new BorderLayout(0, 12));
        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        form.setOpaque(false);
        form.add(UiKit.label("监听地址", Font.BOLD, 13, UiKit.TEXT, sink));
        widgets.bindHost.setPreferredSize(new Dimension(150, 34));
        UiKit.styleField(widgets.bindHost, sink);
        form.add(widgets.bindHost);
        form.add(UiKit.label("端口", Font.BOLD, 13, UiKit.TEXT, sink));
        widgets.port.setPreferredSize(new Dimension(90, 34));
        UiKit.styleField(widgets.port, sink);
        form.add(widgets.port);
        UiKit.stylePrimaryButton(widgets.toggle, sink);
        UiKit.styleSecondaryButton(widgets.clear, sink);
        UiKit.styleSecondaryButton(widgets.export, sink);
        form.add(widgets.toggle);
        form.add(widgets.clear);
        form.add(widgets.export);
        widgets.status.setForeground(UiKit.MUTED);
        sink.track(widgets.status, Font.PLAIN, 13);
        form.add(widgets.status);
        panel.add(form, BorderLayout.NORTH);

        JPanel interceptBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        interceptBar.setOpaque(false);
        UiKit.styleSwitch(widgets.intercept, sink);
        UiKit.stylePrimaryButton(widgets.forward, sink);
        UiKit.styleSecondaryButton(widgets.drop, sink);
        interceptBar.add(widgets.intercept);
        interceptBar.add(widgets.forward);
        interceptBar.add(widgets.drop);
        panel.add(interceptBar, BorderLayout.CENTER);

        JLabel hint = UiKit.label("浏览器或插件把 HTTP 代理指向「监听地址:端口」即可实时看到流量；"
                + "地址默认为本机联网 IP（同一局域网的其他设备也能用），改成 127.0.0.1 则仅本机可用，"
                + "填 0.0.0.0 表示监听所有网卡。勾选「拦截请求」后请求会停在上方等待，"
                + "改完请求包点「放行」才发往目标，「丢弃」则直接断开。HTTPS 目前只透传不解密（不会记录明文）。",
                Font.PLAIN, 12, UiKit.MUTED, sink);
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }

    private static JPanel bodyPanel(Widgets widgets, UiKit.FontSink sink) {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setOpaque(false);

        styleTextArea(widgets.requestText, REQUEST_PLACEHOLDER, sink);
        styleTextArea(widgets.responseText, RESPONSE_PLACEHOLDER, sink);

        JPanel requestPanel = UiKit.surface(new BorderLayout());
        requestPanel.add(UiKit.label("请求包", Font.BOLD, 14, UiKit.TEXT, sink), BorderLayout.NORTH);
        requestPanel.add(scroll(widgets.requestText), BorderLayout.CENTER);

        JPanel responsePanel = UiKit.surface(new BorderLayout());
        responsePanel.add(UiKit.label("返回包（放行后捕获）", Font.BOLD, 14, UiKit.TEXT, sink),
                BorderLayout.NORTH);
        responsePanel.add(scroll(widgets.responseText), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, requestPanel, responsePanel);
        split.setResizeWeight(0.5);
        split.setDividerSize(10);
        split.setBorder(null);
        split.setOpaque(false);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private static JScrollPane scroll(JTextArea area) {
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createLineBorder(UiKit.BORDER));
        scroll.getViewport().setBackground(Color.WHITE);
        return scroll;
    }

    private static void styleTextArea(JTextArea area, String placeholder, UiKit.FontSink sink) {
        area.setEditable(false);
        UiKit.styleMonospaceArea(area, sink);
        // 只在还没有内容时写占位提示：代理页每次进入都会重建面板，
        // 若无条件 setText，离开再回来时已捕获的请求包 / 返回包会被清空
        if (area.getText().isEmpty()) area.setText(placeholder);
    }
}
