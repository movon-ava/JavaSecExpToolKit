package ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

/**
 * 配置页：把可长期保存的参数按功能分组呈现。
 *
 * <p>只负责搭界面与收集控件，读写与生效逻辑仍由界面层回调处理；
 * 分组信息由调用方以 {@link Group} 列表传入，新增一项配置只需在界面层加一行。
 */
public final class ConfigPage {

    /** 一行配置：标签 + 输入控件 + 右侧提示。 */
    public static final class Row {
        public final String label;
        public final JComponent field;
        public final String hint;

        public Row(String label, JComponent field, String hint) {
            this.label = label;
            this.field = field;
            this.hint = hint;
        }
    }

    /** 一组配置：标题 + 说明 + 若干行。 */
    public static final class Group {
        public final String title;
        public final String hint;
        public final Row[] rows;

        public Group(String title, String hint, Row[] rows) {
            this.title = title;
            this.hint = hint;
            this.rows = rows;
        }
    }

    /** 本页需要的控件与回调。 */
    public static final class Widgets {
        public Group[] groups = new Group[0];
        public JLabel status;
        public String statusText = "";
        public Runnable onSave;
        public Runnable onReset;
    }

    private ConfigPage() {
    }

    public static JPanel build(Widgets widgets, UiKit.FontSink sink) {
        JPanel page = UiKit.page();
        page.add(UiKit.pageHeading("SETTINGS", "配置", "分类保存  ·  仅保存在本机", sink), BorderLayout.NORTH);

        JPanel stack = new JPanel();
        stack.setOpaque(false);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setBorder(BorderFactory.createEmptyBorder(28, 0, 0, 0));
        boolean firstGroup = true;
        for (Group group : widgets.groups) {
            if (!firstGroup) stack.add(Box.createVerticalStrut(18));
            firstGroup = false;
            stack.add(group(group, sink));
        }

        JScrollPane scroll = new JScrollPane(stack);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(18);

        JPanel work = new JPanel(new BorderLayout(0, 16));
        work.setOpaque(false);
        work.add(scroll, BorderLayout.CENTER);
        work.add(actions(widgets, sink), BorderLayout.SOUTH);
        page.add(work, BorderLayout.CENTER);
        return page;
    }

    private static JPanel group(Group group, UiKit.FontSink sink) {
        JPanel panel = UiKit.surface(new GridBagLayout());
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints c = UiKit.constraints();
        int row = 0;
        c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 2, 0); c.anchor = GridBagConstraints.WEST;
        panel.add(UiKit.label(group.title, Font.BOLD, 15, UiKit.TEXT, sink), c);
        row++;
        c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.insets = new Insets(0, 0, 14, 0);
        panel.add(UiKit.label(group.hint, Font.PLAIN, 12, UiKit.MUTED, sink), c);
        row++;
        for (Row entry : group.rows) {
            row = row(panel, c, row, entry, sink);
        }
        return panel;
    }

    private static int row(JPanel form, GridBagConstraints c, int row, Row entry, UiKit.FontSink sink) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, 8, 0); c.anchor = GridBagConstraints.WEST;
        form.add(UiKit.label(entry.label, Font.BOLD, 13, UiKit.TEXT, sink), c);
        JPanel holder = new JPanel(new BorderLayout(10, 0));
        holder.setOpaque(false);
        if (entry.field instanceof JTextField) UiKit.styleField((JTextField) entry.field, sink);
        else if (entry.field instanceof JPasswordField) UiKit.styleField((JPasswordField) entry.field, sink);
        holder.add(entry.field, BorderLayout.CENTER);
        holder.add(UiKit.label(entry.hint, Font.PLAIN, 12, UiKit.MUTED, sink), BorderLayout.EAST);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 16, 8, 0);
        form.add(holder, c);
        return row + 1;
    }

    private static JPanel actions(Widgets widgets, UiKit.FontSink sink) {
        JPanel panel = UiKit.surface(new BorderLayout(12, 0));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton save = UiKit.primaryButton("保存配置", sink);
        JButton reset = new JButton("恢复默认");
        UiKit.styleSecondaryButton(reset, sink);
        if (widgets.onSave != null) save.addActionListener(e -> widgets.onSave.run());
        if (widgets.onReset != null) reset.addActionListener(e -> widgets.onReset.run());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        buttons.setOpaque(false);
        buttons.add(save);
        buttons.add(reset);
        panel.add(buttons, BorderLayout.WEST);
        JLabel status = widgets.status == null ? new JLabel(widgets.statusText) : widgets.status;
        status.setForeground(UiKit.MUTED);
        sink.track(status, Font.PLAIN, 13);
        panel.add(status, BorderLayout.CENTER);
        return panel;
    }
}

