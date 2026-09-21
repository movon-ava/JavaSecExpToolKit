package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

import preset.PresetItem;

/**
 * 预设链页：左侧按分类筛选的链清单，右侧链步骤明细与生成出来的载荷。
 *
 * <p>对应网页版的「预设链」页：预设本身是写好的链模板，使用者最多改几个输入
 * （命令、地址之类）就能出载荷。这里不复刻网页版的全部交互，只保留
 * 「挑链 → 看步骤 → 填输入 → 生成」这条主路径，并额外提供「发到恶意服务器」。
 */
public final class PresetPage {

    private static final int LIST_WIDTH = 380;
    private static final int OUTPUT_MIN_HEIGHT = 200;

    /** 一条输入对应的控件与说明。 */
    public static final class InputField {
        public final PresetItem.Input input;
        public final JComponent field;

        InputField(PresetItem.Input input, JComponent field) {
            this.input = input;
            this.field = field;
        }
    }

    public static final class Widgets {
        public final JComboBox<String> category = new JComboBox<String>();
        public final DefaultListModel<String> presets = new DefaultListModel<String>();
        public final JList<String> presetList = new JList<String>(presets);
        public final JLabel title = new JLabel("");
        public final JLabel meta = new JLabel("");
        public final JTextArea description = new JTextArea();
        public final JLabel stepsTitle = new JLabel("链步骤");
        public final JPanel steps = new JPanel();
        public final JPanel inputs = new JPanel();
        public final JButton build = new JButton("生成载荷");
        public final JButton copy = new JButton("复制");
        public final JButton toServers = new JButton("发到恶意服务器");
        public final JButton toCapture = new JButton("填入抓包页");
        public final JLabel status = new JLabel("");
        public final JTextArea output = new JTextArea();
        /** 当前渲染出的输入控件，供自检与取值使用。 */
        public List<InputField> fields = new ArrayList<InputField>();
        /** 当前选中的预设；由控制器维护。 */
        public PresetItem current;
        public CaptureSink onSendToCapture;
        public ServersSink onSendToServers;
    }

    public interface CaptureSink {
        void send(String payload);
    }

    /** 把当前链交给恶意服务器页，避免在预设页重复一遍服务器配置。 */
    public interface ServersSink {
        void send(String payloadId, List<String> gadgets, Map<String, Object> params);
    }

    private PresetPage() {
    }

    public static JPanel build(Widgets widgets, UiKit.FontSink sink) {
        JPanel page = UiKit.page();
        page.add(UiKit.pageHeading("PRESETS", "预设链",
                "java-chains 内置模板  ·  链步骤只读  ·  输入可改  ·  本地生成", sink), BorderLayout.NORTH);

        JScrollPane listScroll = new JScrollPane(widgets.presetList);
        listScroll.setBorder(BorderFactory.createLineBorder(UiKit.BORDER));
        listScroll.getViewport().setBackground(Color.WHITE);
        widgets.presetList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        widgets.presetList.setBackground(Color.WHITE);
        widgets.presetList.setFixedCellHeight(52);
        sink.track(widgets.presetList, Font.PLAIN, 14);

        JPanel left = UiKit.surface(new BorderLayout(0, 12));
        JPanel filter = new JPanel(new BorderLayout(8, 0));
        filter.setOpaque(false);
        filter.add(UiKit.label("分类", Font.BOLD, 13, UiKit.TEXT, sink), BorderLayout.WEST);
        UiKit.styleCombo(widgets.category, 240, sink);
        filter.add(widgets.category, BorderLayout.CENTER);
        left.add(filter, BorderLayout.NORTH);
        left.add(listScroll, BorderLayout.CENTER);
        left.setPreferredSize(new Dimension(LIST_WIDTH, 0));

        JScrollPane right = new JScrollPane(detail(widgets, sink));
        right.setBorder(BorderFactory.createEmptyBorder());
        right.getViewport().setBackground(UiKit.BACKGROUND);
        right.getVerticalScrollBar().setUnitIncrement(18);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.0);
        split.setDividerSize(10);
        split.setBorder(null);
        split.setOpaque(false);

        JPanel work = new JPanel(new BorderLayout());
        work.setOpaque(false);
        work.setBorder(BorderFactory.createEmptyBorder(28, 0, 0, 0));
        work.add(split, BorderLayout.CENTER);
        page.add(work, BorderLayout.CENTER);
        return page;
    }

    private static JPanel detail(Widgets widgets, UiKit.FontSink sink) {
        JPanel stack = new JPanel();
        stack.setOpaque(false);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.add(headCard(widgets, sink));
        stack.add(Box.createVerticalStrut(16));
        stack.add(stepsCard(widgets, sink));
        stack.add(Box.createVerticalStrut(16));
        stack.add(inputsCard(widgets, sink));
        stack.add(Box.createVerticalStrut(16));
        stack.add(outputCard(widgets, sink));
        return stack;
    }

    private static JPanel headCard(Widgets widgets, UiKit.FontSink sink) {
        JPanel card = UiKit.surface(new BorderLayout(0, 10));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel texts = new JPanel();
        texts.setOpaque(false);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        sink.track(widgets.title, Font.BOLD, 20);
        widgets.title.setForeground(UiKit.TEXT);
        sink.track(widgets.meta, Font.PLAIN, 12);
        widgets.meta.setForeground(UiKit.MUTED);
        widgets.meta.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        texts.add(widgets.title);
        texts.add(widgets.meta);
        card.add(texts, BorderLayout.NORTH);

        widgets.description.setEditable(false);
        widgets.description.setOpaque(false);
        widgets.description.setLineWrap(true);
        widgets.description.setWrapStyleWord(true);
        widgets.description.setForeground(UiKit.BODY_TEXT);
        sink.track(widgets.description, Font.PLAIN, 13);
        card.add(widgets.description, BorderLayout.CENTER);
        return card;
    }

    private static JPanel stepsCard(Widgets widgets, UiKit.FontSink sink) {
        JPanel card = UiKit.surface(new BorderLayout(0, 12));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(UiKit.sectionTitle("链步骤", "顺序与节点来自内置预设，不可编辑", sink), BorderLayout.NORTH);
        widgets.steps.setOpaque(false);
        widgets.steps.setLayout(new BoxLayout(widgets.steps, BoxLayout.Y_AXIS));
        card.add(widgets.steps, BorderLayout.CENTER);
        card.setMinimumSize(new Dimension(0, 120));
        return card;
    }

    private static JPanel inputsCard(Widgets widgets, UiKit.FontSink sink) {
        JPanel card = UiKit.surface(new BorderLayout(0, 12));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(UiKit.sectionTitle("输入参数", "留空则用预设默认值", sink), BorderLayout.NORTH);
        widgets.inputs.setOpaque(false);
        widgets.inputs.setLayout(new BoxLayout(widgets.inputs, BoxLayout.Y_AXIS));
        card.add(widgets.inputs, BorderLayout.CENTER);
        return card;
    }

    private static JPanel outputCard(Widgets widgets, UiKit.FontSink sink) {
        JPanel card = UiKit.surface(new BorderLayout(0, 12));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(UiKit.sectionTitle("载荷输出", "Base64 文本与长度", sink), BorderLayout.NORTH);
        widgets.output.setEditable(false);
        UiKit.styleMonospaceArea(widgets.output, sink);
        card.add(UiKit.scroll(widgets.output), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        actions.setOpaque(false);
        UiKit.stylePrimaryButton(widgets.build, sink);
        UiKit.styleSecondaryButton(widgets.copy, sink);
        UiKit.styleSecondaryButton(widgets.toServers, sink);
        UiKit.styleSecondaryButton(widgets.toCapture, sink);
        actions.add(widgets.build);
        actions.add(widgets.copy);
        actions.add(widgets.toServers);
        actions.add(widgets.toCapture);
        sink.track(widgets.status, Font.PLAIN, 13);
        widgets.status.setForeground(UiKit.MUTED);
        actions.add(widgets.status);
        card.add(actions, BorderLayout.SOUTH);
        card.setMinimumSize(new Dimension(0, OUTPUT_MIN_HEIGHT));
        card.setPreferredSize(new Dimension(0, 300));
        return card;
    }

    /** 渲染链步骤：序号 + 节点名 + 该步默认参数。 */
    public static void renderSteps(Widgets widgets, PresetItem item, UiKit.FontSink sink) {
        widgets.steps.removeAll();
        if (item == null || item.steps.isEmpty()) {
            widgets.steps.add(UiKit.label("请选择左侧的一条预设链。", Font.PLAIN, 13, UiKit.MUTED, sink));
        } else {
            int index = 1;
            for (PresetItem.Step step : item.steps) {
                JPanel row = new JPanel(new BorderLayout(10, 0));
                row.setOpaque(false);
                row.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
                JLabel order = UiKit.label(String.valueOf(index++), Font.BOLD, 12, UiKit.ACCENT, sink);
                order.setPreferredSize(new Dimension(22, 26));
                row.add(order, BorderLayout.WEST);
                row.add(UiKit.label(step.gadget, Font.BOLD, 13, UiKit.TEXT, sink), BorderLayout.CENTER);
                if (!step.args.isEmpty()) {
                    row.add(UiKit.label(step.args.toString(), Font.PLAIN, 12, UiKit.MUTED, sink),
                            BorderLayout.EAST);
                }
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
                widgets.steps.add(row);
            }
        }
        widgets.steps.revalidate();
        widgets.steps.repaint();
    }

    /**
     * 渲染输入控件。
     *
     * <p>布尔型输入给勾选框、带 choices 的给下拉框、其余给文本框：
     * 预设里的类型信息就是为这一步准备的，全部当文本框会让使用者去猜合法取值。
     */
    public static void renderInputs(Widgets widgets, PresetItem item, UiKit.FontSink sink) {
        widgets.inputs.removeAll();
        widgets.fields.clear();
        if (item == null || !item.hasInputs()) {
            widgets.inputs.add(UiKit.label("该预设没有可填输入，直接点「生成载荷」。",
                    Font.PLAIN, 13, UiKit.MUTED, sink));
            widgets.inputs.revalidate();
            widgets.inputs.repaint();
            return;
        }
        for (PresetItem.Input input : item.inputs) {
            JPanel row = new JPanel(new BorderLayout(12, 0));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
            JLabel label = UiKit.label(input.label + (input.required ? " *" : ""),
                    Font.BOLD, 13, UiKit.TEXT, sink);
            label.setPreferredSize(new Dimension(130, 34));
            row.add(label, BorderLayout.WEST);

            JComponent field;
            if ("boolean".equalsIgnoreCase(input.type)) {
                JCheckBox box = new JCheckBox();
                box.setSelected("true".equalsIgnoreCase(input.defaultValue));
                UiKit.styleSwitch(box, sink);
                field = box;
            } else if (input.type != null && input.type.toLowerCase(java.util.Locale.ROOT).startsWith("choice")
                    && !input.choicesText().isEmpty()) {
                JComboBox<String> box = new JComboBox<String>(input.choicesText().split(","));
                UiKit.styleCombo(box, 260, sink);
                box.setSelectedItem(input.defaultValue);
                field = box;
            } else {
                JTextField text = new JTextField(input.defaultValue, 24);
                UiKit.styleField(text, sink);
                field = text;
            }
            row.add(field, BorderLayout.CENTER);
            if (!input.description.isEmpty()) {
                JLabel hint = UiKit.label(input.description, Font.PLAIN, 12, UiKit.MUTED, sink);
                row.add(hint, BorderLayout.EAST);
            }
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            widgets.inputs.add(row);
            widgets.fields.add(new InputField(input, field));
        }
        widgets.inputs.revalidate();
        widgets.inputs.repaint();
    }

    /** 清单行只显示链名与分类，细节放到右侧，避免左侧过宽。 */
    public static void fillList(Widgets widgets, List<PresetItem> items) {
        String selected = widgets.presetList.getSelectedValue();
        widgets.presets.clear();
        for (PresetItem item : items) {
            widgets.presets.addElement(item.name + "    [" + item.category + "]");
        }
        if (selected != null) {
            for (int index = 0; index < widgets.presets.size(); index++) {
                if (widgets.presets.get(index).equals(selected)) {
                    widgets.presetList.setSelectedIndex(index);
                    return;
                }
            }
        }
        if (!widgets.presets.isEmpty()) widgets.presetList.setSelectedIndex(0);
    }

    /** 从已渲染控件读回当前输入值。 */
    public static Map<String, String> readInputs(Widgets widgets) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (InputField entry : widgets.fields) {
            if (entry.field instanceof JTextField) {
                values.put(entry.input.key, ((JTextField) entry.field).getText().trim());
            } else if (entry.field instanceof JCheckBox) {
                values.put(entry.input.key, ((JCheckBox) entry.field).isSelected() ? "true" : "false");
            } else if (entry.field instanceof JComboBox) {
                Object selected = ((JComboBox<?>) entry.field).getSelectedItem();
                values.put(entry.input.key, selected == null ? "" : String.valueOf(selected));
            }
        }
        return values;
    }

    private static GridBagConstraints unusedConstraints() {
        return UiKit.constraints();
    }

    private static Insets unusedInsets() {
        return new Insets(0, 0, 0, 0);
    }

    private static GridBagLayout unusedLayout() {
        return new GridBagLayout();
    }
}
