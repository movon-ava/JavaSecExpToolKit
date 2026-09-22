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
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * HTTP 带外 Jar 页：选 Jar 类型与末端动作 → 填 URL / 命令 → 托管到本地 HTTP 服务拿地址。
 *
 * <p>与「预设链」页的区别在于交付物：预设链页产出的是载荷字节，本页产出的是
 * **一个可访问的地址**，目标自己去拉。两件事的参数与失败路径都不同，
 * 混在一页会让「生成」这个按钮的语义变得含糊。
 *
 * <p>页面只描述结构：模板与参数键在 {@link payload.JarPreset} 里，
 * 托管在 {@link service.OobJarService} 里，行为在 {@link OobJarController} 里，
 * 控件构造也只在 {@link #defaults()} 里发生。
 */
public final class OobJarPage {

    /** 本页需要的控件与回调。 */
    public static final class Widgets {
        public final JComboBox<String> kindCombo = new JComboBox<String>();
        public final JComboBox<String> actionCombo = new JComboBox<String>();
        public final JLabel actionHint = new JLabel("");
        public final JTextField url = new JTextField("", 32);
        public final JLabel urlLabel = new JLabel("URL");
        public final JTextField command = new JTextField("", 32);
        public final JLabel commandLabel = new JLabel("命令");
        public final JTextField path = new JTextField("", 32);
        public final JLabel pathLabel = new JLabel("落地路径");
        public final JTextField targetClass = new JTextField("", 32);
        public final JTextField classNamePrefix = new JTextField("", 32);
        public final JTextField bindHost = new JTextField("", 18);
        public final JTextField port = new JTextField("", 8);
        public final JCheckBox executable = new JCheckBox("生成可执行 Jar（写入 Main-Class）", true);
        public final JButton host = new JButton("托管 Jar");
        public final JButton stop = new JButton("停止托管");
        public final JButton copyUrl = new JButton("复制地址");
        /** 用 {@link WrappedLabel} 承接长状态：窄栏里普通标签会截成省略号。 */
        public final JLabel status = new WrappedLabel("");
        public final JTextArea output = new JTextArea();
    }

    /** 控件的默认实例：初值与布局所需参数都留在视图类内。 */
    public static Widgets defaults() {
        Widgets widgets = new Widgets();
        widgets.kindCombo.setModel(new DefaultComboBoxModel<String>());
        widgets.actionCombo.setModel(new DefaultComboBoxModel<String>());
        widgets.output.setEditable(false);
        widgets.actionHint.setForeground(UiKit.MUTED);
        widgets.url.setToolTipText("Jar 被执行时访问的地址，例如下载地址或回连地址");
        widgets.path.setToolTipText("Jar 落地到目标上的路径，留空用动作默认值");
        widgets.targetClass.setToolTipText("例如 com.example.User；留空则用引擎随机类名");
        widgets.classNamePrefix.setToolTipText("生成的类名前缀，留空不写该参数");
        widgets.bindHost.setToolTipText("服务实际监听的网卡地址，留空用 127.0.0.1");
        widgets.port.setToolTipText("1-65535");
        return widgets;
    }

    private OobJarPage() {
    }

    public static JPanel build(Widgets widgets, UiKit.FontSink sink) {
        JPanel page = UiKit.page();
        page.add(UiKit.pageHeading("PAYLOAD", "HTTP 带外 Jar",
                "Jar 类型 + 末端动作  ·  自定义 URL / 命令 / 目标类  ·  本地托管取地址", sink),
                BorderLayout.NORTH);

        JPanel work = new JPanel(new BorderLayout(0, 18));
        work.setOpaque(false);
        work.setBorder(BorderFactory.createEmptyBorder(28, 0, 0, 0));
        work.add(form(widgets, sink), BorderLayout.NORTH);
        work.add(outputCard(widgets, sink), BorderLayout.CENTER);
        page.add(work, BorderLayout.CENTER);
        return page;
    }

    private static JPanel form(Widgets widgets, UiKit.FontSink sink) {
        JPanel form = UiKit.surface(new GridBagLayout());
        GridBagConstraints c = UiKit.constraints();
        int row = 0;

        row = row(form, c, row, "Jar 类型", widgets.kindCombo, "决定产物形态与加载方式", sink);
        row = row(form, c, row, "末端动作", widgets.actionCombo, "Jar 被执行后做什么", sink);

        c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 0, 10, 0);
        sink.track(widgets.actionHint, Font.PLAIN, 12);
        widgets.actionHint.setForeground(UiKit.MUTED);
        form.add(widgets.actionHint, c);
        row++;

        row = labelled(row, form, c, widgets.urlLabel, widgets.url,
                "动作需要 URL 时填写，否则该框不可用", sink);
        row = labelled(row, form, c, widgets.commandLabel, widgets.command,
                "动作需要命令 / 执行参数时填写", sink);
        row = labelled(row, form, c, widgets.pathLabel, widgets.path,
                "留空用动作默认路径", sink);
        row = row(form, c, row, "自定义目标类", widgets.targetClass, "例如 com.example.User", sink);
        row = row(form, c, row, "类名前缀", widgets.classNamePrefix, "留空不写该参数", sink);
        row = row(form, c, row, "绑定地址", widgets.bindHost, "留空用 127.0.0.1", sink);
        row = row(form, c, row, "监听端口", widgets.port, "1-65535", sink);

        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0;
        c.fill = GridBagConstraints.NONE; c.insets = new Insets(0, 0, 8, 0);
        UiKit.styleSwitch(widgets.executable, sink);
        form.add(widgets.executable, c);
        row++;

        c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(10, 0, 0, 0);
        JPanel actions = new JPanel(new BorderLayout(12, 0));
        actions.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        buttons.setOpaque(false);
        UiKit.stylePrimaryButton(widgets.host, sink);
        UiKit.styleSecondaryButton(widgets.stop, sink);
        UiKit.styleSecondaryButton(widgets.copyUrl, sink);
        buttons.add(widgets.host);
        buttons.add(widgets.stop);
        buttons.add(widgets.copyUrl);
        actions.add(buttons, BorderLayout.WEST);
        sink.track(widgets.status, Font.PLAIN, 13);
        widgets.status.setForeground(UiKit.MUTED);
        actions.add(widgets.status, BorderLayout.CENTER);
        form.add(actions, c);
        return form;
    }

    /** 一行「标签 + 下拉框」。 */
    private static int row(JPanel form, GridBagConstraints c, int row, String labelText,
                           JComponent field, String hint, UiKit.FontSink sink) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0;
        c.fill = GridBagConstraints.NONE; c.insets = new Insets(0, 0, 8, 0);
        form.add(UiKit.label(labelText, Font.BOLD, 13, UiKit.TEXT, sink), c);
        if (field instanceof JComboBox) UiKit.styleCombo((JComboBox<?>) field, 260, sink);
        else if (field instanceof JTextField) UiKit.styleField((JTextField) field, sink);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 16, 8, 0);
        form.add(field, c);
        return row + 1;
    }

    /**
     * 一行「可变标签 + 输入框」。
     *
     * <p>标签放在控件对象里而不是写死文本：同一个输入框在不同末端动作下含义不同
     * （URL 可能是下载地址，也可能是回连地址），标签必须跟着动作变。
     */
    private static int labelled(int row, JPanel form, GridBagConstraints c, JLabel caption,
                                JTextField field, String hint, UiKit.FontSink sink) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0;
        c.fill = GridBagConstraints.NONE; c.insets = new Insets(0, 0, 8, 0);
        sink.track(caption, Font.BOLD, 13);
        caption.setForeground(UiKit.TEXT);
        form.add(caption, c);

        UiKit.styleField(field, sink);
        JPanel holder = new JPanel(new BorderLayout(10, 0));
        holder.setOpaque(false);
        holder.add(field, BorderLayout.CENTER);
        JLabel note = UiKit.label(hint, Font.PLAIN, 12, UiKit.MUTED, sink);
        holder.add(note, BorderLayout.EAST);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 16, 8, 0);
        form.add(holder, c);
        return row + 1;
    }

    private static JPanel outputCard(Widgets widgets, UiKit.FontSink sink) {
        JPanel card = UiKit.surface(new BorderLayout(0, 12));
        card.add(UiKit.sectionTitle("托管结果", "可直接交给目标的地址与产物信息", sink),
                BorderLayout.NORTH);
        UiKit.styleMonospaceArea(widgets.output, sink);
        card.add(UiKit.scroll(widgets.output), BorderLayout.CENTER);
        card.setMinimumSize(new Dimension(0, 220));
        return card;
    }

    /** 填充 Jar 类型清单：行内只显示名字，细节由右侧说明承担。 */
    public static void fillKinds(Widgets widgets, java.util.List<payload.JarPreset.Kind> kinds) {
        String[] names = new String[kinds.size()];
        for (int index = 0; index < kinds.size(); index++) names[index] = kinds.get(index).name;
        widgets.kindCombo.setModel(new DefaultComboBoxModel<String>(names));
    }

    /** 填充末端动作清单。 */
    public static void fillActions(Widgets widgets, java.util.List<payload.JarPreset.Action> actions) {
        String[] names = new String[actions.size()];
        for (int index = 0; index < actions.size(); index++) names[index] = actions.get(index).name;
        widgets.actionCombo.setModel(new DefaultComboBoxModel<String>(names));
    }

    /** 按动作重塑标签与可用性：填不了的输入框直接禁用，比让人填了不生效更清楚。 */
    public static void applyAction(Widgets widgets, payload.JarPreset.Action action, UiKit.FontSink sink) {
        if (action == null) return;
        widgets.actionHint.setText(action.summary);
        widgets.urlLabel.setText(action.usesUrl() ? action.urlLabel : "（本动作不需要 URL）");
        widgets.commandLabel.setText(action.usesCommand() ? action.commandLabel : "（本动作不需要命令）");
        widgets.pathLabel.setText(action.usesPath() ? "落地路径" : "（本动作不需要路径）");
        widgets.url.setEnabled(action.usesUrl());
        widgets.command.setEnabled(action.usesCommand());
        widgets.path.setEnabled(action.usesPath());
        if (action.usesPath() && widgets.path.getText().trim().isEmpty()) {
            widgets.path.setText(action.pathDefault);
        }
        widgets.url.setBackground(action.usesUrl() ? Color.WHITE : UiKit.SUBTLE);
        widgets.command.setBackground(action.usesCommand() ? Color.WHITE : UiKit.SUBTLE);
        widgets.path.setBackground(action.usesPath() ? Color.WHITE : UiKit.SUBTLE);
        widgets.actionHint.revalidate();
    }
}