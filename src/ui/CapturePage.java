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
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * 抓包与转换页：单次请求抓包 + 报文格式转换。
 *
 * <p>与「代理抓包」是两件事：这里只发一次请求并保留原始响应，
 * 不发散流量，因此独立成页，参数也各自保存。
 */
public final class CapturePage {

    /** 本页需要的控件与回调。 */
    public static final class Widgets {
        public JComboBox<String> method;
        public JTextField url;
        public JTextField contentType;
        public JTextField headers;
        public JTextArea body;
        public JComboBox<String> convertTarget;
        public JTextArea pastedRequest;
        public JButton run;
        public JButton convert;
        public JButton toProbe;
        public JComboBox<String> sendTo;
        public JButton send;
        public JLabel status;
        public JTextArea result;
        public JButton copy;
        /** 复制结果到剪贴板。 */
        public Runnable onCopy;
    }

    private CapturePage() {
    }

    public static JPanel build(Widgets widgets, UiKit.FontSink sink) {
        JPanel page = UiKit.page();
        page.add(UiKit.pageHeading("CAPTURE", "抓包与转换",
                "发送一次请求并记录原始响应  ·  格式转换  ·  授权目标", sink), BorderLayout.NORTH);

        JPanel work = new JPanel(new BorderLayout(0, 18));
        work.setOpaque(false);
        work.setBorder(BorderFactory.createEmptyBorder(28, 0, 0, 0));
        work.add(form(widgets, sink), BorderLayout.NORTH);
        work.add(outputPanel(widgets, sink), BorderLayout.CENTER);
        page.add(work, BorderLayout.CENTER);
        return page;
    }

    private static JPanel form(Widgets widgets, UiKit.FontSink sink) {
        JPanel form = UiKit.surface(new GridBagLayout());
        GridBagConstraints c = UiKit.constraints();
        int row = 0;

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, 8, 0);
        form.add(UiKit.label("请求方法", Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 16, 8, 0);
        combo(widgets.method, new Dimension(200, 34), sink);
        form.add(holder(widgets.method), c);
        row++;

        row = row(form, c, row, "目标 URL", widgets.url, "例如 http://host/path", sink);
        row = textRow(form, c, row, "Content-Type", widgets.contentType, sink);
        row = row(form, c, row, "请求头（JSON）", widgets.headers, "{\"Cookie\":\"JWT=xxx\"}", sink);
        row = areaRow(form, c, row, "请求体", widgets.body, 4, sink);

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, 8, 0);
        form.add(UiKit.label("转换目标", Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 16, 8, 0);
        combo(widgets.convertTarget, new Dimension(220, 34), sink);
        form.add(holder(widgets.convertTarget), c);
        row++;

        row = areaRow(form, c, row, "粘贴原始请求", widgets.pastedRequest, 6, sink);

        c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(10, 0, 0, 0);
        JPanel actions = new JPanel(new BorderLayout(12, 0));
        actions.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        buttons.setOpaque(false);
        UiKit.stylePrimaryButton(widgets.run, sink);
        UiKit.styleSecondaryButton(widgets.convert, sink);
        UiKit.styleSecondaryButton(widgets.toProbe, sink);
        combo(widgets.sendTo, new Dimension(150, 34), sink);
        UiKit.stylePrimaryButton(widgets.send, sink);
        buttons.add(widgets.run);
        buttons.add(widgets.convert);
        buttons.add(widgets.toProbe);
        buttons.add(widgets.sendTo);
        buttons.add(widgets.send);
        actions.add(buttons, BorderLayout.WEST);
        widgets.status.setForeground(UiKit.MUTED);
        sink.track(widgets.status, Font.PLAIN, 13);
        actions.add(widgets.status, BorderLayout.CENTER);
        form.add(actions, c);
        return form;
    }

    private static JPanel outputPanel(Widgets widgets, UiKit.FontSink sink) {
        JPanel panel = UiKit.surface(new BorderLayout(0, 12));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(UiKit.label("抓包结果", Font.BOLD, 14, UiKit.TEXT, sink), BorderLayout.WEST);
        UiKit.styleSecondaryButton(widgets.copy, sink);
        if (widgets.onCopy != null) widgets.copy.addActionListener(e -> widgets.onCopy.run());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(widgets.copy);
        head.add(right, BorderLayout.EAST);
        panel.add(head, BorderLayout.NORTH);

        widgets.result.setEditable(false);
        UiKit.styleMonospaceArea(widgets.result, sink);
        widgets.result.setText("点击“抓包”发送一次请求并查看原始响应；"
                + "或把浏览器 / Burp 里的请求粘贴到上方后点“解析并转换”。\n");
        panel.add(UiKit.scroll(widgets.result), BorderLayout.CENTER);
        return panel;
    }

    private static void combo(JComboBox<String> box, Dimension size, UiKit.FontSink sink) {
        box.setPreferredSize(size);
        box.setBackground(Color.WHITE);
        box.setForeground(UiKit.TEXT);
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.FIELD_BORDER),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        sink.track(box, Font.PLAIN, 14);
    }

    private static JPanel holder(JComponent field) {
        JPanel holder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        holder.setOpaque(false);
        holder.add(field);
        return holder;
    }

    private static int row(JPanel form, GridBagConstraints c, int row, String labelText,
                           JComponent field, String hint, UiKit.FontSink sink) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, 8, 0);
        form.add(UiKit.label(labelText, Font.BOLD, 13, UiKit.TEXT, sink), c);
        JPanel holder = new JPanel(new BorderLayout(10, 0));
        holder.setOpaque(false);
        if (field instanceof JTextField) UiKit.styleField((JTextField) field, sink);
        holder.add(field, BorderLayout.CENTER);
        holder.add(UiKit.label(hint, Font.PLAIN, 12, UiKit.MUTED, sink), BorderLayout.EAST);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 16, 8, 0);
        form.add(holder, c);
        return row + 1;
    }

    private static int textRow(JPanel form, GridBagConstraints c, int row, String labelText,
                               JTextField field, UiKit.FontSink sink) {
        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, 8, 0);
        form.add(UiKit.label(labelText, Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 16, 8, 0);
        UiKit.styleField(field, sink);
        form.add(field, c);
        return row + 1;
    }

    private static int areaRow(JPanel form, GridBagConstraints c, int row, String labelText,
                               JTextArea area, int rows, UiKit.FontSink sink) {
        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, 8, 0);
        form.add(UiKit.label(labelText, Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 16, 8, 0);
        UiKit.styleArea(area, rows, sink);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createLineBorder(UiKit.FIELD_BORDER));
        scroll.getViewport().setBackground(Color.WHITE);
        form.add(scroll, c);
        return row + 1;
    }
}

