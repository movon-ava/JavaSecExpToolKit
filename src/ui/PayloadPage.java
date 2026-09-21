package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Payload 生成页：选载体 → 逐级追加节点 → 配参数 → 生成载荷。
 *
 * <p>只负责界面结构：链的状态与引擎调用在 {@link PayloadController} 里，
 * 本页不持有执行状态，也不直接调用 java-chains。
 */
public final class PayloadPage {

    /** 表单区高度：与 Shiro 页同一量级，保证输出区留足空间。 */
    private static final int FORM_HEIGHT = 340;
    private static final int FORM_MIN_HEIGHT = 150;
    private static final double SPLIT_RATIO = 0.66;
    /** 输出区最小高度：约 7 行等宽文本，保证生成的 Base64 一眼能看到头。 */
    private static final int OUTPUT_MIN_HEIGHT = 240;

    /** 把生成的载荷交给抓包页：复用该页已有的请求体输入框。 */
    public interface CaptureSink {
        void send(String payload);
    }

    /** 一个待渲染的参数：来自引擎的节点参数声明。 */
    public static final class ParamField {
        /** 引擎认识的完整键，例如 Exec.cmd。 */
        public final String key;
        /** 展示名，例如「命令」。 */
        public final String label;
        /** 参数说明，作为控件提示。 */
        public final String hint;
        /** 可选值；为空表示自由输入。 */
        public final String[] choices;
        /** 是否必填。 */
        public final boolean required;
        /** 渲染出来的控件，由本页赋值。 */
        public JComponent field;

        public ParamField(String key, String label, String hint, String[] choices, boolean required) {
            this.key = key;
            this.label = label;
            this.hint = hint;
            this.choices = choices;
            this.required = required;
        }
    }

    /** 本页需要的控件与回调。 */
    public static final class Widgets {
        public JComboBox<String> group = new JComboBox<String>();
        public JComboBox<String> kind = new JComboBox<String>();
        public JTextField chain = new JTextField("", 44);
        public JComboBox<String> next = new JComboBox<String>();
        public JButton addNode = new JButton("追加节点");
        public JButton undo = new JButton("删除末节点");
        public JButton clear = new JButton("清空链");
        public JPanel params = new JPanel();
        public JButton build = new JButton("生成载荷");
        public JButton copy = new JButton("复制");
        public JButton export = new JButton("导出文件");
        public JButton toCapture = new JButton("填入抓包页");
        public JLabel status = new JLabel("");
        public JTextArea output = new JTextArea();
        /** 导出目录；空表示落到用户目录。由界面层从配置页注入。 */
        public String exportDirectory = "";
        /** 把载荷填入抓包页；由界面层提供，未提供时按钮给出提示。 */
        public CaptureSink onSendToCapture;
        /** 当前渲染出的参数行，供自检读取。 */
        public List<ParamField> fields = new ArrayList<ParamField>();
    }

    private PayloadPage() {
    }

    public static JPanel build(Widgets widgets, UiKit.FontSink sink) {
        JPanel page = UiKit.page();
        page.add(UiKit.pageHeading("PAYLOAD", "Payload 生成",
                "java-chains 载体与节点  ·  链式构建  ·  本地生成  ·  授权测试", sink),
                BorderLayout.NORTH);

        // 表单较高：直接放 BorderLayout.NORTH 会把输出区压成细线，因此放进滚动面板再上下分栏
        JScrollPane formScroll = new JScrollPane(form(widgets, sink));
        formScroll.setBorder(BorderFactory.createEmptyBorder());
        formScroll.getViewport().setBackground(UiKit.BACKGROUND);
        formScroll.setPreferredSize(new Dimension(0, FORM_HEIGHT));
        formScroll.setMinimumSize(new Dimension(0, FORM_MIN_HEIGHT));

        JPanel work = new JPanel(new BorderLayout(0, 18));
        work.setOpaque(false);
        work.setBorder(BorderFactory.createEmptyBorder(28, 0, 0, 0));
        JPanel upper = new JPanel(new BorderLayout(0, 14));
        upper.setOpaque(false);
        upper.add(formScroll, BorderLayout.CENTER);
        upper.add(actions(widgets, sink), BorderLayout.SOUTH);

        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, outputPanel(widgets, sink));
        split.setResizeWeight(SPLIT_RATIO);
        split.setDividerSize(10);
        split.setBorder(null);
        split.setOpaque(false);
        work.add(split, BorderLayout.CENTER);
        page.add(work, BorderLayout.CENTER);
        SwingUtilities.invokeLater(() -> split.setDividerLocation(SPLIT_RATIO));
        return page;
    }

    private static JPanel form(Widgets widgets, UiKit.FontSink sink) {
        JPanel form = UiKit.surface(new GridBagLayout());
        GridBagConstraints c = UiKit.constraints();
        int row = 0;

        row = comboRow(form, c, row, "载体分组", widgets.group, "按用途分组，便于挑选", sink);
        row = comboRow(form, c, row, "载荷载体", widgets.kind, "载体决定载荷的外层协议形态", sink);

        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, 10, 0);
        form.add(UiKit.label("当前链", Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 16, 10, 0);
        JPanel chainHolder = new JPanel(new BorderLayout(10, 0));
        chainHolder.setOpaque(false);
        widgets.chain.setEditable(false);
        UiKit.styleField(widgets.chain, sink);
        chainHolder.add(widgets.chain, BorderLayout.CENTER);
        JPanel chainActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        chainActions.setOpaque(false);
        UiKit.styleSecondaryButton(widgets.undo, sink);
        UiKit.styleSecondaryButton(widgets.clear, sink);
        chainActions.add(widgets.undo);
        chainActions.add(widgets.clear);
        chainHolder.add(chainActions, BorderLayout.EAST);
        form.add(chainHolder, c);
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, 10, 0);
        form.add(UiKit.label("追加节点", Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 16, 10, 0);
        JPanel nodeHolder = new JPanel(new BorderLayout(10, 0));
        nodeHolder.setOpaque(false);
        styleCombo(widgets.next, new Dimension(360, 34), sink);
        nodeHolder.add(widgets.next, BorderLayout.CENTER);
        UiKit.stylePrimaryButton(widgets.addNode, sink);
        nodeHolder.add(widgets.addNode, BorderLayout.EAST);
        form.add(nodeHolder, c);
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.NORTHWEST; c.insets = new Insets(0, 0, 0, 0);
        form.add(UiKit.label("节点参数", Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 16, 0, 0);
        widgets.params.setOpaque(false);
        widgets.params.setLayout(new BoxLayout(widgets.params, BoxLayout.Y_AXIS));
        JScrollPane paramsScroll = new JScrollPane(widgets.params);
        paramsScroll.setBorder(BorderFactory.createEmptyBorder());
        paramsScroll.getViewport().setBackground(Color.WHITE);
        paramsScroll.setPreferredSize(new Dimension(0, 120));
        form.add(paramsScroll, c);
        return form;
    }

    /**
     * 按引擎给出的参数声明渲染参数行。
     *
     * <p>参数随链上节点变化，因此每次改链都整块重建。
     */
    public static void renderParams(JPanel panel, List<ParamField> fields, UiKit.FontSink sink) {
        panel.removeAll();
        if (fields == null || fields.isEmpty()) {
            panel.add(UiKit.label("当前链没有可配置参数。", Font.PLAIN, 13, UiKit.MUTED, sink));
            panel.revalidate();
            panel.repaint();
            return;
        }
        for (ParamField field : fields) {
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
            JLabel label = UiKit.label(field.label + (field.required ? " *" : ""), Font.BOLD, 13, UiKit.TEXT, sink);
            label.setPreferredSize(new Dimension(150, 34));
            row.add(label, BorderLayout.WEST);

            if (field.choices != null && field.choices.length > 0) {
                JComboBox<String> combo = new JComboBox<String>(field.choices);
                styleCombo(combo, new Dimension(220, 34), sink);
                field.field = combo;
                row.add(holder(combo), BorderLayout.CENTER);
            } else {
                JTextField input = new JTextField("", 24);
                UiKit.styleField(input, sink);
                field.field = input;
                row.add(input, BorderLayout.CENTER);
            }
            row.add(UiKit.label(field.hint == null ? "" : field.hint, Font.PLAIN, 12, UiKit.MUTED, sink),
                    BorderLayout.EAST);
            panel.add(row);
        }
        panel.add(Box.createVerticalGlue());
        panel.revalidate();
        panel.repaint();
    }

    private static JPanel actions(Widgets widgets, UiKit.FontSink sink) {
        JPanel actions = new JPanel(new BorderLayout(12, 0));
        actions.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        buttons.setOpaque(false);
        UiKit.stylePrimaryButton(widgets.build, sink);
        UiKit.styleSecondaryButton(widgets.copy, sink);
        UiKit.styleSecondaryButton(widgets.export, sink);
        UiKit.styleSecondaryButton(widgets.toCapture, sink);
        buttons.add(widgets.build);
        buttons.add(widgets.copy);
        buttons.add(widgets.export);
        buttons.add(widgets.toCapture);
        actions.add(buttons, BorderLayout.WEST);
        widgets.status.setForeground(UiKit.MUTED);
        sink.track(widgets.status, Font.PLAIN, 13);
        actions.add(widgets.status, BorderLayout.CENTER);
        return actions;
    }

    private static JPanel outputPanel(Widgets widgets, UiKit.FontSink sink) {
        JPanel panel = UiKit.surface(new BorderLayout(0, 12));
        panel.add(UiKit.label("载荷输出", Font.BOLD, 14, UiKit.TEXT, sink), BorderLayout.NORTH);
        widgets.output.setEditable(false);
        UiKit.styleMonospaceArea(widgets.output, sink);
        panel.add(UiKit.scroll(widgets.output), BorderLayout.CENTER);
        // 分栏在首次布局时按首选尺寸 + resizeWeight 分配：输出区没有最小高度就会被表单
        // 挤成一条细线（实测 62px）。170px 只够三行等宽文本，载荷 Base64 完全看不全，
        // 因此这里给到 OUTPUT_MIN_HEIGHT，拖动分隔条可以随时再放大。
        panel.setMinimumSize(new Dimension(0, OUTPUT_MIN_HEIGHT));
        return panel;
    }

    private static int comboRow(JPanel form, GridBagConstraints c, int row, String labelText,
                                JComboBox<String> box, String hint, UiKit.FontSink sink) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, 10, 0);
        form.add(UiKit.label(labelText, Font.BOLD, 13, UiKit.TEXT, sink), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 16, 10, 0);
        styleCombo(box, new Dimension(300, 34), sink);
        JPanel holderRow = new JPanel(new BorderLayout(10, 0));
        holderRow.setOpaque(false);
        holderRow.add(holder(box), BorderLayout.WEST);
        holderRow.add(UiKit.label(hint, Font.PLAIN, 12, UiKit.MUTED, sink), BorderLayout.CENTER);
        form.add(holderRow, c);
        return row + 1;
    }

    private static JPanel holder(JComponent field) {
        JPanel holder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        holder.setOpaque(false);
        holder.add(field);
        return holder;
    }

    private static void styleCombo(JComboBox<String> box, Dimension size, UiKit.FontSink sink) {
        box.setPreferredSize(size);
        box.setBackground(Color.WHITE);
        box.setForeground(UiKit.TEXT);
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.FIELD_BORDER),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        sink.track(box, Font.PLAIN, 14);
    }
}
