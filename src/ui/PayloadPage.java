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
 * Payload 生成页：列式选链（载体 → 逐级节点）→ 配参数 → 生成载荷。
 *
 * <p>对应网页版 java-chains 的 Generate 页：左侧一列列展开可接的节点，
 * 点某一列的候选即以该列为界重开链，不再需要「选下拉框再点追加」两步一次。
 *
 * <p>只负责界面结构：链的状态与引擎调用在 {@link PayloadController} 里，
 * 本页不持有执行状态，也不直接调用 java-chains。
 */
public final class PayloadPage {

    /**
     * 表单区高度：链文本行 + 一条列式选择器（334px）+ 卡片内边距。
     *
     * <p>参数区与选择器**并排**而不是上下排：竖着排的话两者相加会超出分栏给表单的高度，
     * 默认最大化下就会出滚动条，「选完链要滚下去填参数」。（实测竖排 592px 被裁掉约 40px。）
     */
    private static final int FORM_HEIGHT = 424;
    private static final int FORM_MIN_HEIGHT = 220;
    /** 参数侧栏宽度：标签 + 输入框刚好放得下，且不挤占选择器的列宽。 */
    private static final int PARAMS_WIDTH = 400;
    private static final double SPLIT_RATIO = 0.66;
    /** 输出区最小高度：约 7 行等宽文本，保证生成的 Base64 一眼能看到头。 */
    private static final int OUTPUT_MIN_HEIGHT = 220;

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
        /** 只读的当前链文本：链只能由点击列内候选构建，不允许手填。 */
        public final JTextField chain = new JTextField("", 44);
        public final JButton undo = new JButton("删除末节点");
        public final JButton clear = new JButton("清空链");
        /** 列式链选择器：由界面层按字体回调创建后注入。 */
        public PayloadChainSelector selector;
        public final JPanel params = new JPanel();
        public final JButton build = new JButton("生成载荷");
        public final JButton copy = new JButton("复制");
        public final JButton export = new JButton("导出文件");
        public final JButton toCapture = new JButton("填入抓包页");
        public final JLabel status = new JLabel("");
        public final JTextArea output = new JTextArea();
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
                "java-chains 载体与节点  ·  列式选链  ·  本地生成  ·  授权测试", sink),
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

        c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.weightx = 1; c.weighty = 1;
        c.fill = GridBagConstraints.BOTH; c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 0, 0);
        JPanel band = new JPanel(new BorderLayout(16, 0));
        band.setOpaque(false);
        band.add(selectorBlock(widgets, sink), BorderLayout.CENTER);
        band.add(paramsBlock(widgets, sink), BorderLayout.EAST);
        form.add(band, c);
        return form;
    }

    /** 左半块：列式选链。 */
    private static JPanel selectorBlock(Widgets widgets, UiKit.FontSink sink) {
        JPanel block = new JPanel(new BorderLayout(0, 8));
        block.setOpaque(false);
        block.add(UiKit.sectionTitle("选择利用链",
                "点某一列的候选即追加并展开下一列；点回前面某一列即从那里重开链", sink),
                BorderLayout.NORTH);
        block.add(widgets.selector.component(), BorderLayout.CENTER);
        return block;
    }

    /** 右半块：当前链的节点参数。 */
    private static JPanel paramsBlock(Widgets widgets, UiKit.FontSink sink) {
        JPanel block = new JPanel(new BorderLayout(0, 8));
        block.setOpaque(false);
        block.add(UiKit.sectionTitle("节点参数", "随所选节点变化", sink), BorderLayout.NORTH);
        widgets.params.setOpaque(false);
        widgets.params.setLayout(new BoxLayout(widgets.params, BoxLayout.Y_AXIS));
        JScrollPane paramsScroll = new JScrollPane(widgets.params);
        paramsScroll.setBorder(BorderFactory.createLineBorder(UiKit.BORDER));
        paramsScroll.getViewport().setBackground(Color.WHITE);
        paramsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        Dimension size = new Dimension(PARAMS_WIDTH, 0);
        block.setPreferredSize(size);
        block.setMinimumSize(size);
        block.add(paramsScroll, BorderLayout.CENTER);
        return block;
    }

    /**
     * 按引擎给出的参数声明渲染参数行。
     *
     * <p>参数随链上节点变化，因此每次改链都整块重建。
     */
    public static void renderParams(JPanel panel, List<ParamField> fields, UiKit.FontSink sink) {
        renderParams(panel, fields, sink, false);
    }

    /**
     * 渲染参数行；{@code compact} 为真时用窄标签与窄输入框，供侧栏使用。
     *
     * <p>紧凑模式把参数说明从行尾挪到控件提示：侧栏只有 400px，
     * 行尾再挂一段说明会把输入框挤到看不见。
     */
    public static void renderParams(JPanel panel, List<ParamField> fields, UiKit.FontSink sink,
                                   boolean compact) {
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
            int labelWidth = compact ? 108 : 150;
            JLabel label = UiKit.label(field.label + (field.required ? " *" : ""), Font.BOLD, 13, UiKit.TEXT, sink);
            label.setPreferredSize(new Dimension(labelWidth, 34));
            if (compact && field.hint != null && !field.hint.isEmpty()) label.setToolTipText(field.hint);
            row.add(label, BorderLayout.WEST);

            if (field.choices != null && field.choices.length > 0) {
                JComboBox<String> combo = new JComboBox<String>(field.choices);
                styleCombo(combo, new Dimension(compact ? 168 : 220, 34), sink);
                if (compact && field.hint != null && !field.hint.isEmpty()) combo.setToolTipText(field.hint);
                field.field = combo;
                row.add(holder(combo), BorderLayout.CENTER);
            } else {
                JTextField input = new JTextField("", compact ? 16 : 24);
                UiKit.styleField(input, sink);
                if (compact && field.hint != null && !field.hint.isEmpty()) input.setToolTipText(field.hint);
                field.field = input;
                row.add(input, BorderLayout.CENTER);
            }
            if (!compact) {
                row.add(UiKit.label(field.hint == null ? "" : field.hint, Font.PLAIN, 12, UiKit.MUTED, sink),
                        BorderLayout.EAST);
            }
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
