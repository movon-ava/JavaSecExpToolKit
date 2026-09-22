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
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * 小工具 - 文件上传页：填目标 URL、选本地文件、发一次 multipart 请求并记录原始响应。
 *
 * <p>与「抓包转换」是两件事：抓包页复现的是 JSON / 表单请求，这里要处理的是
 * 二进制文件与 multipart 分段，请求体由引擎按原始字节拼装，界面只负责收集参数。
 *
 * <p>页面只描述结构：文件对话框实例也在 {@link #defaults()} 里建好并交给行为类使用，
 * 行为类因此不需要自己 new 任何控件（与仓库既有的视图 / 行为分离约定一致）。
 */
public final class ToolsUploadPage {

    /** 本页需要的控件与回调。 */
    public static final class Widgets {
        public JTextField url;
        public JTextField filePath;
        public JButton choose;
        public JFileChooser chooser;
        public JTextField field;
        public JTextField fields;
        public JTextField headers;
        public JButton run;
        public JButton copy;
        public JLabel status;
        public JTextArea result;
        /** 选择文件：由行为类实现，界面不直接读写磁盘。 */
        public Runnable onChoose;
        /** 复制结果到剪贴板。 */
        public Runnable onCopy;
    }

    /** 本页控件的默认实例：初值与布局所需参数都留在视图类内。 */
    public static Widgets defaults() {
        Widgets widgets = new Widgets();
        widgets.url = new JTextField("", 32);
        widgets.filePath = new JTextField("", 32);
        widgets.choose = new JButton("选择文件");
        widgets.chooser = new JFileChooser();
        widgets.chooser.setDialogTitle("选择要上传的文件");
        widgets.chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        widgets.field = new JTextField("file", 16);
        widgets.fields = new JTextField("", 32);
        widgets.headers = new JTextField("", 32);
        widgets.run = new JButton("上传");
        widgets.copy = new JButton("复制结果");
        widgets.status = new JLabel("只发送一次上传请求，仅访问你填写的目标");
        widgets.result = new JTextArea();
        return widgets;
    }

    private ToolsUploadPage() {
    }

    public static JPanel build(Widgets widgets, UiKit.FontSink sink) {
        JPanel page = UiKit.page();
        page.add(UiKit.pageHeading("TOOLS", "文件上传",
                "选择本地文件按 multipart 上传  ·  记录原始响应  ·  授权目标", sink), BorderLayout.NORTH);

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

        row = row(form, c, row, "目标 URL", widgets.url, "例如 http://host/upload", sink);
        row = fileRow(form, c, row, widgets, sink);
        row = row(form, c, row, "表单字段名", widgets.field, "目标接口读取文件的参数名", sink);
        row = row(form, c, row, "附加表单字段（JSON）", widgets.fields,
                "{\"csrf\":\"abc\"}", sink);
        row = row(form, c, row, "请求头（JSON）", widgets.headers,
                "{\"Cookie\":\"JWT=xxx\"}", sink);

        c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(10, 0, 0, 0);
        JPanel actions = new JPanel(new BorderLayout(12, 0));
        actions.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        buttons.setOpaque(false);
        UiKit.stylePrimaryButton(widgets.run, sink);
        UiKit.styleSecondaryButton(widgets.choose, sink);
        buttons.add(widgets.run);
        actions.add(buttons, BorderLayout.WEST);
        widgets.status.setForeground(UiKit.MUTED);
        sink.track(widgets.status, Font.PLAIN, 13);
        actions.add(widgets.status, BorderLayout.CENTER);
        form.add(actions, c);
        return form;
    }

    /** 选择文件一行：只读路径框 + 选择按钮，路径框不允许手改，避免填出不存在的路径。 */
    private static int fileRow(JPanel form, GridBagConstraints c, int row, Widgets widgets,
                               UiKit.FontSink sink) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, 8, 0);
        form.add(UiKit.label("本地文件", Font.BOLD, 13, UiKit.TEXT, sink), c);

        UiKit.styleField(widgets.filePath, sink);
        widgets.filePath.setEditable(false);
        widgets.filePath.setBackground(UiKit.SUBTLE);
        widgets.filePath.setForeground(UiKit.MUTED);
        UiKit.styleSecondaryButton(widgets.choose, sink);

        JPanel holder = new JPanel(new BorderLayout(10, 0));
        holder.setOpaque(false);
        holder.add(widgets.filePath, BorderLayout.CENTER);
        holder.add(widgets.choose, BorderLayout.EAST);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 16, 8, 0);
        form.add(holder, c);
        return row + 1;
    }

    private static JPanel outputPanel(Widgets widgets, UiKit.FontSink sink) {
        JPanel panel = UiKit.surface(new BorderLayout(0, 12));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(UiKit.label("上传结果", Font.BOLD, 14, UiKit.TEXT, sink), BorderLayout.WEST);
        UiKit.styleSecondaryButton(widgets.copy, sink);
        if (widgets.onCopy != null) widgets.copy.addActionListener(e -> widgets.onCopy.run());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(widgets.copy);
        head.add(right, BorderLayout.EAST);
        panel.add(head, BorderLayout.NORTH);

        widgets.result.setEditable(false);
        UiKit.styleMonospaceArea(widgets.result, sink);
        widgets.result.setText("填写目标上传接口、选择本地文件后点“上传”。\n"
                + "支持附加普通表单字段（JSON）与请求头，响应原文与状态码会显示在这里。\n");
        panel.add(UiKit.scroll(widgets.result), BorderLayout.CENTER);
        return panel;
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
}
