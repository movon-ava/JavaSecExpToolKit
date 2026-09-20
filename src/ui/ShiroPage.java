package ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Shiro 漏洞利用页：字段表单 + 固定操作按钮 + 四个独立回显页签。
 *
 * <p>只负责界面结构，检测 / 爆破 / 生成链 / 执行命令的行为由界面层调用
 * {@code shiro.ShiroEngine} 完成，本页不持有任何执行状态。
 */
public final class ShiroPage {

    /** 表单区高度：约 330px 可视，其余留给输出区。 */
    private static final int FORM_HEIGHT = 330;
    private static final int FORM_MIN_HEIGHT = 150;
    /** 上下分栏比例：表单区占 62%。 */
    private static final double SPLIT_RATIO = 0.62;

    /** 本页需要的控件与回调。 */
    public static final class Widgets {
        public JTextField url;
        public JComboBox<String> requestMethod;
        public JTextField cookieName;
        public JTextField key;
        public JCheckBox gcm;
        public JComboBox<shiro.ShiroExploit.ChainKind> chain;
        public JTextField echoHeader;
        public JTextField command;
        public JTextArea headers;
        public JTextArea body;
        public JButton detect;
        public JButton crack;
        public JButton stop;
        public JButton build;
        public JButton run;
        public JProgressBar progress;
        public JLabel status;
        public JTabbedPane outputTabs;
        public JTextArea detectOutput;
        public JTextArea crackOutput;
        public JTextArea buildOutput;
        public JTextArea runOutput;
    }

    private ShiroPage() {
    }

    public static JPanel build(Widgets widgets, UiKit.FontSink sink) {
        JPanel page = UiKit.page();
        page.add(UiKit.pageHeading("SHIRO", "Shiro 漏洞利用",
                "指纹检测  ·  密钥爆破  ·  java-chains 链生成  ·  命令回显  ·  授权目标", sink),
                BorderLayout.NORTH);

        // 表单本身约 480px 高：直接放 BorderLayout.NORTH 会吃掉全部高度，输出区被压成
        // 一条细线。这里把表单放进滚动面板再上下分栏，保证两个区域始终有可用高度。
        JScrollPane formScroll = new JScrollPane(form(widgets, sink));
        formScroll.setBorder(BorderFactory.createEmptyBorder());
        formScroll.getViewport().setBackground(UiKit.BACKGROUND);
        formScroll.setPreferredSize(new Dimension(0, FORM_HEIGHT));
        formScroll.setMinimumSize(new Dimension(0, FORM_MIN_HEIGHT));

        JPanel work = new JPanel(new BorderLayout(0, 18));
        work.setOpaque(false);
        work.setBorder(BorderFactory.createEmptyBorder(28, 0, 0, 0));
        // 上：字段（可滚动）→ 中间：固定的操作按钮 → 下：输出
        JPanel upper = new JPanel(new BorderLayout(0, 14));
        upper.setOpaque(false);
        upper.add(formScroll, BorderLayout.CENTER);
        upper.add(actionsPanel(widgets, sink), BorderLayout.SOUTH);

        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, outputPanel(widgets, sink));
        split.setResizeWeight(SPLIT_RATIO);
        split.setDividerSize(10);
        split.setBorder(null);
        split.setOpaque(false);
        work.add(split, BorderLayout.CENTER);
        page.add(work, BorderLayout.CENTER);
        // 分栏在首次布局时按首选尺寸分配，需在布局完成后按比例重新定位
        SwingUtilities.invokeLater(() -> split.setDividerLocation(SPLIT_RATIO));
        return page;
    }

    private static JPanel form(Widgets widgets, UiKit.FontSink sink) {
        JPanel form = UiKit.surface(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = UiKit.constraints();
        int row = 0;

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = java.awt.GridBagConstraints.NONE;
        c.insets = new java.awt.Insets(0, 0, 10, 0);
        form.add(UiKit.label("目标 URL", Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.insets = new java.awt.Insets(0, 16, 10, 0);
        UiKit.styleField(widgets.url, sink);
        form.add(widgets.url, c);
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = java.awt.GridBagConstraints.NONE;
        c.insets = new java.awt.Insets(0, 0, 10, 0);
        form.add(UiKit.label("请求方法", Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.insets = new java.awt.Insets(0, 16, 10, 0);
        styleCombo(widgets.requestMethod, new Dimension(140, 34), sink);
        form.add(holder(widgets.requestMethod), c);
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = java.awt.GridBagConstraints.NONE;
        c.insets = new java.awt.Insets(0, 0, 10, 0);
        form.add(UiKit.label("Cookie 名", Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.insets = new java.awt.Insets(0, 16, 10, 0);
        UiKit.styleField(widgets.cookieName, sink);
        form.add(widgets.cookieName, c);
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = java.awt.GridBagConstraints.NONE;
        c.insets = new java.awt.Insets(0, 0, 10, 0);
        form.add(UiKit.label("Shiro 密钥", Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.insets = new java.awt.Insets(0, 16, 10, 0);
        JPanel keyHolder = new JPanel(new BorderLayout(10, 0));
        keyHolder.setOpaque(false);
        UiKit.styleField(widgets.key, sink);
        keyHolder.add(widgets.key, BorderLayout.CENTER);
        UiKit.styleSwitch(widgets.gcm, sink);
        keyHolder.add(widgets.gcm, BorderLayout.EAST);
        form.add(keyHolder, c);
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = java.awt.GridBagConstraints.NONE;
        c.insets = new java.awt.Insets(0, 0, 10, 0);
        form.add(UiKit.label("利用链", Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.insets = new java.awt.Insets(0, 16, 10, 0);
        JPanel chainHolder = new JPanel(new BorderLayout(10, 0));
        chainHolder.setOpaque(false);
        styleCombo(widgets.chain, new Dimension(240, 34), sink);
        chainHolder.add(widgets.chain, BorderLayout.WEST);
        chainHolder.add(UiKit.label("回显请求头", Font.BOLD, 13, UiKit.TEXT, sink), BorderLayout.CENTER);
        UiKit.styleField(widgets.echoHeader, sink);
        chainHolder.add(widgets.echoHeader, BorderLayout.EAST);
        form.add(chainHolder, c);
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = java.awt.GridBagConstraints.NONE;
        c.insets = new java.awt.Insets(0, 0, 10, 0);
        form.add(UiKit.label("命令", Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.insets = new java.awt.Insets(0, 16, 10, 0);
        UiKit.styleField(widgets.command, sink);
        form.add(widgets.command, c);
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = java.awt.GridBagConstraints.NONE;
        c.anchor = java.awt.GridBagConstraints.NORTHWEST; c.insets = new java.awt.Insets(0, 0, 10, 0);
        form.add(UiKit.label("附加请求头", Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = java.awt.GridBagConstraints.BOTH;
        c.insets = new java.awt.Insets(0, 16, 10, 0);
        widgets.headers.setLineWrap(true);
        widgets.headers.setWrapStyleWord(true);
        widgets.headers.setForeground(UiKit.TEXT);
        widgets.headers.setBackground(java.awt.Color.WHITE);
        widgets.headers.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.FIELD_BORDER),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        sink.track(widgets.headers, Font.PLAIN, 13);
        JScrollPane headerScroll = new JScrollPane(widgets.headers);
        headerScroll.setBorder(BorderFactory.createEmptyBorder());
        headerScroll.setPreferredSize(new Dimension(0, 76));
        form.add(headerScroll, c);
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = java.awt.GridBagConstraints.NONE;
        c.anchor = java.awt.GridBagConstraints.NORTHWEST; c.insets = new java.awt.Insets(0, 0, 10, 0);
        form.add(UiKit.label("请求体", Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = java.awt.GridBagConstraints.BOTH;
        c.insets = new java.awt.Insets(0, 16, 10, 0);
        widgets.body.setLineWrap(true);
        widgets.body.setWrapStyleWord(true);
        widgets.body.setForeground(UiKit.TEXT);
        widgets.body.setBackground(java.awt.Color.WHITE);
        widgets.body.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.FIELD_BORDER),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        sink.track(widgets.body, Font.PLAIN, 13);
        JScrollPane bodyScroll = new JScrollPane(widgets.body);
        bodyScroll.setBorder(BorderFactory.createEmptyBorder());
        bodyScroll.setPreferredSize(new Dimension(0, 76));
        form.add(bodyScroll, c);
        return form;
    }

    /**
     * 操作按钮与状态行。
     *
     * 单独抽出来固定在滚动区之外：表单字段本身接近 480px 高，若按钮跟着一起滚动，
     * 800px 高的窗口里按钮会被滚出可视区，使用者看不到「一键检测」等入口。
     */
    private static JPanel actionsPanel(Widgets widgets, UiKit.FontSink sink) {
        JPanel panel = UiKit.surface(new BorderLayout(0, 8));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        actions.setOpaque(false);
        UiKit.stylePrimaryButton(widgets.detect, sink);
        UiKit.styleSecondaryButton(widgets.crack, sink);
        UiKit.styleSecondaryButton(widgets.stop, sink);
        UiKit.styleSecondaryButton(widgets.build, sink);
        UiKit.styleSecondaryButton(widgets.run, sink);
        widgets.progress.setPreferredSize(new Dimension(180, 22));
        widgets.progress.setStringPainted(true);
        widgets.progress.setVisible(false);
        actions.add(widgets.detect);
        actions.add(widgets.crack);
        actions.add(widgets.stop);
        actions.add(widgets.build);
        actions.add(widgets.run);
        actions.add(widgets.progress);
        panel.add(actions, BorderLayout.NORTH);
        widgets.status.setForeground(UiKit.MUTED);
        sink.track(widgets.status, Font.PLAIN, 13);
        panel.add(widgets.status, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * 输出区：每个功能一个独立页签。
     *
     * 指纹检测 / 密钥爆破 / 生成 Payload / 执行命令 的结果互不覆盖，
     * 便于对照「检测结论 → 爆破出的密钥 → 生成的链 → 命令回显」这条链路。
     */
    private static JPanel outputPanel(Widgets widgets, UiKit.FontSink sink) {
        JPanel panel = UiKit.surface(new BorderLayout(0, 12));
        panel.add(UiKit.label("利用输出（每个功能独立回显）", Font.BOLD, 14, UiKit.TEXT, sink),
                BorderLayout.NORTH);

        widgets.detectOutput.setText("填写授权的 Shiro 目标，点「一键检测」确认框架是否存在 rememberMe 解密路径。"
                + System.lineSeparator());
        widgets.crackOutput.setText("点「密钥爆破」用内置字典识别 rememberMe 的 AES 密钥，进度显示在按钮行。"
                + System.lineSeparator());
        widgets.buildOutput.setText("点「生成 Payload」只生成链并打印 Base64，不向目标投递。"
                + System.lineSeparator());
        widgets.runOutput.setText("点「执行命令」会投递回显链并取回命令结果，全过程打印在这里。"
                + System.lineSeparator());

        // 页签只在首次构建时添加：outputTabs 由界面层持有并在多次进入页面之间复用，
        // 每次构建都 addTab 会让页签数翻倍（4 → 8 → 12）。
        if (widgets.outputTabs.getTabCount() == 0) {
            widgets.outputTabs.addTab("指纹检测", outputScroll(widgets.detectOutput, sink));
            widgets.outputTabs.addTab("密钥爆破", outputScroll(widgets.crackOutput, sink));
            widgets.outputTabs.addTab("生成 Payload", outputScroll(widgets.buildOutput, sink));
            widgets.outputTabs.addTab("执行命令", outputScroll(widgets.runOutput, sink));
            sink.track(widgets.outputTabs, Font.PLAIN, 13);
        }

        panel.add(widgets.outputTabs, BorderLayout.CENTER);
        panel.setMinimumSize(new Dimension(0, 170));
        return panel;
    }

    private static JScrollPane outputScroll(JTextArea area, UiKit.FontSink sink) {
        area.setEditable(false);
        UiKit.styleMonospaceArea(area, sink);
        return UiKit.scroll(area);
    }

    private static void styleCombo(JComboBox<?> box, Dimension size, UiKit.FontSink sink) {
        box.setPreferredSize(size);
        box.setBackground(java.awt.Color.WHITE);
        box.setForeground(UiKit.TEXT);
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.BORDER),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        sink.track(box, Font.PLAIN, 14);
    }

    private static JPanel holder(javax.swing.JComponent field) {
        JPanel holder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        holder.setOpaque(false);
        holder.add(field);
        return holder;
    }
}
