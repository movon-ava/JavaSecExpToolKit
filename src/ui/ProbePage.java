package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * Fastjson 探测页：五个探测模式的参数表单与结果区。
 *
 * <p>控件由界面层持有（结果区要按模式拼接多段报告），本类只负责摆放与样式，
 * 因此通过 {@link Widgets} 传入；模式勾选后的联动由 {@code onModeChanged} 回调处理。
 */
public final class ProbePage {

    /** 本页需要的控件与回调。 */
    public static final class Widgets {
        public JCheckBox modeDetect;
        public JCheckBox modeVersion;
        public JCheckBox modeExpect;
        public JCheckBox dnsEnabled;
        public JCheckBox ceyeEnabled;
        public JComboBox<String> probeMethod;
        public JTextField target;
        public JTextField timeout;
        public JTextField baseBody;
        public JTextField requestHeaders;
        public JTextField sessionCookie;
        public JTextField dnslogHost;
        public JTextField dnsFilter;
        public JTextField dnsWait;
        public JButton detect;
        public JLabel status;
        public JTextArea result;
        /** 模式勾选变化后刷新输入框可用性。 */
        public Runnable onModeChanged;
    }

    private ProbePage() {
    }

    public static JPanel build(Widgets widgets, UiKit.FontSink sink) {
        JPanel page = UiKit.page();

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        JPanel headings = new JPanel();
        headings.setOpaque(false);
        headings.setLayout(new BoxLayout(headings, BoxLayout.Y_AXIS));
        headings.add(UiKit.label("FASTJSON", Font.BOLD, 12, UiKit.ACCENT, sink));
        JLabel title = UiKit.label("Fastjson 探测", Font.BOLD, 30, UiKit.TEXT, sink);
        title.setBorder(BorderFactory.createEmptyBorder(7, 0, 0, 0));
        headings.add(title);
        heading.add(headings, BorderLayout.WEST);
        JLabel scope = UiKit.label("识别 / 版本 / 期望类 / DNS 探针 / CEYE 确认  ·  授权目标",
                Font.PLAIN, 13, UiKit.MUTED, sink);
        scope.setBorder(BorderFactory.createEmptyBorder(20, 0, 0, 0));
        heading.add(scope, BorderLayout.EAST);
        page.add(heading, BorderLayout.NORTH);

        JPanel work = new JPanel(new BorderLayout(0, 18));
        work.setOpaque(false);
        work.setBorder(BorderFactory.createEmptyBorder(28, 0, 0, 0));
        work.add(form(widgets, sink), BorderLayout.NORTH);
        work.add(resultPanel(widgets, sink), BorderLayout.CENTER);
        page.add(work, BorderLayout.CENTER);
        return page;
    }

    private static JPanel form(Widgets widgets, UiKit.FontSink sink) {
        JPanel form = UiKit.surface(new GridBagLayout());
        GridBagConstraints c = UiKit.constraints();
        c.weightx = 0; c.fill = GridBagConstraints.NONE; c.gridy = 0;
        form.add(UiKit.label("探测模式", Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 16, 10, 0);
        // 五个模式可同时勾选，按固定顺序依次执行
        JPanel modeSwitches = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 0));
        modeSwitches.setOpaque(false);
        JCheckBox[] boxes = {widgets.modeDetect, widgets.modeVersion, widgets.modeExpect,
                widgets.dnsEnabled, widgets.ceyeEnabled};
        for (JCheckBox box : boxes) {
            UiKit.styleSwitch(box, sink);
            modeSwitches.add(box);
        }
        form.add(modeSwitches, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE; c.insets = new Insets(0, 0, 10, 0);
        form.add(UiKit.label("请求方法", Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 16, 10, 0);
        widgets.probeMethod.setPreferredSize(new Dimension(160, 34));
        widgets.probeMethod.setBackground(Color.WHITE);
        widgets.probeMethod.setForeground(UiKit.TEXT);
        widgets.probeMethod.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.BORDER),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        sink.track(widgets.probeMethod, Font.PLAIN, 14);
        form.add(widgets.probeMethod, c);

        field(form, c, sink, 2, "目标 URL", widgets.target, 10);
        field(form, c, sink, 3, "超时（秒）", widgets.timeout, 8);
        field(form, c, sink, 4, "业务参数（期望类）", widgets.baseBody, 8);
        field(form, c, sink, 5, "请求头（JSON）", widgets.requestHeaders, 8);
        field(form, c, sink, 6, "会话 Cookie", widgets.sessionCookie, 8);
        field(form, c, sink, 7, "DNSLog 主机", widgets.dnslogHost, 8);
        field(form, c, sink, 8, "CEYE Filter", widgets.dnsFilter, 8);
        field(form, c, sink, 9, "DNS 等待（秒）", widgets.dnsWait, 8);

        c.gridx = 0; c.gridy = 10; c.gridwidth = 2; c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(10, 0, 0, 0);
        JPanel actions = new JPanel(new BorderLayout(12, 0));
        actions.setOpaque(false);
        UiKit.stylePrimaryButton(widgets.detect, sink);
        actions.add(widgets.detect, BorderLayout.WEST);
        widgets.status.setForeground(UiKit.MUTED);
        sink.track(widgets.status, Font.PLAIN, 13);
        actions.add(widgets.status, BorderLayout.CENTER);
        form.add(actions, c);

        // 输入框可用性随模式勾选的默认状态刷新
        if (widgets.onModeChanged != null) widgets.onModeChanged.run();
        return form;
    }

    /** 一行「标签 + 输入框」。 */
    private static void field(JPanel form, GridBagConstraints c, UiKit.FontSink sink,
                              int row, String label, JTextField input, int bottomInset) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0;
        c.fill = GridBagConstraints.NONE; c.insets = new Insets(0, 0, row == 2 ? bottomInset : bottomInset, 0);
        form.add(UiKit.label(label, Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 16, bottomInset, 0);
        UiKit.styleField(input, sink);
        form.add(input, c);
    }

    private static JPanel resultPanel(Widgets widgets, UiKit.FontSink sink) {
        JPanel panel = UiKit.surface(new BorderLayout(0, 12));
        panel.add(UiKit.label("探测结果", Font.BOLD, 14, UiKit.TEXT, sink), BorderLayout.NORTH);
        widgets.result.setEditable(false);
        UiKit.styleMonospaceArea(widgets.result, sink);
        if (widgets.result.getText().isEmpty()) {
            widgets.result.setText("勾选探测模式，输入授权的 JSON 反序列化接口，然后点击“开始探测”。\n");
        }
        panel.add(UiKit.scroll(widgets.result), BorderLayout.CENTER);
        return panel;
    }
}

