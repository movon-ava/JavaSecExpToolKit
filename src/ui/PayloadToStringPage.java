package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

import payload.ToStringPreset;

/**
 * toString 链生成页：左侧模板清单，右侧链步骤与自定义目标类，底部载荷输出。
 *
 * <p>从 Payload 生成页独立出来的原因：toString 触发链的使用方式与通用链不同——
 * 触发节点几乎不参与参数配置，使用者真正要改的是「以哪个类为触发点」与末端命令。
 * 把它混在通用选链里，需要在上百个候选里先挑触发节点、再逐个确认链路，
 * 而这里直接给出实测可行的模板，改两个输入就能出载荷。
 *
 * <p>页面只描述结构：模板与链的判定在 {@link ToStringPreset}，行为在
 * {@link PayloadToStringController}，控件构造也只在 {@link #defaults()} 里发生。
 */
public final class PayloadToStringPage {

    /** 左侧清单宽度：与预设链页保持一致，避免两页来回切时左右跳动。 */
    private static final int LIST_WIDTH = 360;

    /** 本页需要的控件与回调。 */
    public static final class Widgets {
        public final DefaultListModel<String> templates = new DefaultListModel<String>();
        public final JList<String> templateList = new JList<String>(templates);
        public final JLabel title = new JLabel("");
        public final JLabel meta = new JLabel("");
        public final JTextArea summary = new JTextArea();
        public final JLabel stepsTitle = new JLabel("链步骤");
        public final JPanel steps = new JPanel();
        /** 自定义目标类：留空则沿用引擎随机类名。 */
        public final JTextField targetClass = new JTextField("", 32);
        /** 末端命令：留空则用计算器（与上游预设一致）。 */
        public final JTextField command = new JTextField("", 32);
        public final JButton build = new JButton("生成载荷");
        public final JButton copyTemplate = new JButton("复制链模板");
        public final JButton copy = new JButton("复制载荷");
        public final JButton toCapture = new JButton("填入抓包页");
        /** 用 {@link WrappedLabel} 承接长状态：窄栏里普通标签会截成省略号。 */
        public final JLabel status = new WrappedLabel("");
        public final JTextArea output = new JTextArea();
        /** 当前选中的模板标识；由控制器维护。 */
        public String currentId = "";
        /** 把载荷填入抓包页；由界面层提供，未提供时按钮给出提示。 */
        public PayloadPage.CaptureSink onSendToCapture;
    }

    /** 控件的默认实例：初值与布局所需参数都留在视图类内。 */
    public static Widgets defaults() {
        Widgets widgets = new Widgets();
        widgets.templateList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        widgets.templateList.setBackground(Color.WHITE);
        widgets.templateList.setFixedCellHeight(52);
        widgets.output.setEditable(false);
        widgets.summary.setEditable(false);
        widgets.summary.setOpaque(false);
        widgets.summary.setLineWrap(true);
        widgets.summary.setWrapStyleWord(true);
        widgets.steps.setOpaque(false);
        widgets.steps.setLayout(new BoxLayout(widgets.steps, BoxLayout.Y_AXIS));
        return widgets;
    }

    private PayloadToStringPage() {
    }

    public static JPanel build(Widgets widgets, UiKit.FontSink sink) {
        JPanel page = UiKit.page();
        page.add(UiKit.pageHeading("PAYLOAD", "toString 链",
                "toString 触发链模板  ·  自定义目标类  ·  模板可复制  ·  本地生成", sink),
                BorderLayout.NORTH);

        JPanel work = new JPanel(new BorderLayout());
        work.setOpaque(false);
        work.setBorder(BorderFactory.createEmptyBorder(28, 0, 0, 0));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                left(widgets, sink), right(widgets, sink));
        split.setResizeWeight(0.0);
        split.setDividerSize(10);
        split.setBorder(null);
        split.setOpaque(false);
        work.add(split, BorderLayout.CENTER);
        page.add(work, BorderLayout.CENTER);
        return page;
    }

    /** 左侧：模板清单。行内只显示名字，细节放右侧，避免左栏需要横向滚动。 */
    private static JPanel left(Widgets widgets, UiKit.FontSink sink) {
        JScrollPane scroll = new JScrollPane(widgets.templateList);
        scroll.setBorder(BorderFactory.createLineBorder(UiKit.BORDER));
        scroll.getViewport().setBackground(Color.WHITE);
        sink.track(widgets.templateList, Font.PLAIN, 14);

        JPanel panel = UiKit.surface(new BorderLayout(0, 12));
        panel.add(UiKit.sectionTitle("链模板", "全部为实测可构建的链", sink), BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(LIST_WIDTH, 0));
        return panel;
    }

    /** 右侧：模板详情 + 自定义输入 + 载荷输出。 */
    private static JPanel right(Widgets widgets, UiKit.FontSink sink) {
        JPanel stack = new JPanel();
        stack.setOpaque(false);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.add(head(widgets, sink));
        stack.add(Box.createVerticalStrut(16));
        stack.add(stepsCard(widgets, sink));
        stack.add(Box.createVerticalStrut(16));
        stack.add(inputsCard(widgets, sink));
        stack.add(Box.createVerticalStrut(16));
        stack.add(outputCard(widgets, sink));

        JScrollPane scroll = new JScrollPane(stack);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(UiKit.BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(18);

        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(scroll, BorderLayout.CENTER);
        return holder;
    }

    private static JPanel head(Widgets widgets, UiKit.FontSink sink) {
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
        widgets.summary.setForeground(UiKit.BODY_TEXT);
        sink.track(widgets.summary, Font.PLAIN, 13);
        card.add(widgets.summary, BorderLayout.CENTER);
        return card;
    }

    private static JPanel stepsCard(Widgets widgets, UiKit.FontSink sink) {
        JPanel card = UiKit.surface(new BorderLayout(0, 12));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(UiKit.sectionTitle("链步骤", "顺序与节点来自实测模板，不可编辑", sink),
                BorderLayout.NORTH);
        card.add(widgets.steps, BorderLayout.CENTER);
        card.setMinimumSize(new Dimension(0, 110));
        return card;
    }

    /** 自定义输入：目标类与命令。两者都不是链结构的一部分，因此单独成卡。 */
    private static JPanel inputsCard(Widgets widgets, UiKit.FontSink sink) {
        JPanel card = UiKit.surface(new BorderLayout(0, 12));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(UiKit.sectionTitle("自定义输入", "留空则用模板默认值", sink), BorderLayout.NORTH);

        JPanel rows = new JPanel();
        rows.setOpaque(false);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.add(fieldRow("自定义目标类", widgets.targetClass,
                "例如 com.example.User；留空则用引擎随机类名", sink));
        rows.add(Box.createVerticalStrut(8));
        rows.add(fieldRow("末端命令", widgets.command,
                "链路末端执行的动作；留空用默认命令", sink));
        card.add(rows, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        actions.setOpaque(false);
        UiKit.stylePrimaryButton(widgets.build, sink);
        UiKit.styleSecondaryButton(widgets.copyTemplate, sink);
        UiKit.styleSecondaryButton(widgets.copy, sink);
        UiKit.styleSecondaryButton(widgets.toCapture, sink);
        actions.add(widgets.build);
        actions.add(widgets.copyTemplate);
        actions.add(widgets.copy);
        actions.add(widgets.toCapture);
        card.add(actions, BorderLayout.SOUTH);
        return card;
    }

    /** 一行输入：左标签 + 中控件 + 右提示。 */
    private static JPanel fieldRow(String label, JTextField field, String hint, UiKit.FontSink sink) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        JLabel caption = UiKit.label(label, Font.BOLD, 13, UiKit.TEXT, sink);
        caption.setPreferredSize(new Dimension(120, 34));
        row.add(caption, BorderLayout.WEST);
        UiKit.styleField(field, sink);
        row.add(field, BorderLayout.CENTER);
        row.add(UiKit.label(hint, Font.PLAIN, 12, UiKit.MUTED, sink), BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        return row;
    }

    private static JPanel outputCard(Widgets widgets, UiKit.FontSink sink) {
        JPanel card = UiKit.surface(new BorderLayout(0, 12));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(UiKit.sectionTitle("载荷输出", "Base64 文本、长度与链序", sink), BorderLayout.NORTH);
        UiKit.styleMonospaceArea(widgets.output, sink);
        card.add(UiKit.scroll(widgets.output), BorderLayout.CENTER);
        sink.track(widgets.status, Font.PLAIN, 13);
        widgets.status.setForeground(UiKit.MUTED);
        widgets.status.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        card.add(widgets.status, BorderLayout.SOUTH);
        card.setMinimumSize(new Dimension(0, 240));
        card.setPreferredSize(new Dimension(0, 280));
        return card;
    }

    /** 填充模板清单：行内显示名字与 JDK 适用范围。 */
    public static void fillTemplates(Widgets widgets, List<ToStringPreset.Template> items) {
        String selected = widgets.templateList.getSelectedValue();
        widgets.templates.clear();
        for (ToStringPreset.Template item : items) {
            widgets.templates.addElement(item.name + "    [" + item.jdk + "]");
        }
        if (selected != null) {
            for (int index = 0; index < widgets.templates.size(); index++) {
                if (widgets.templates.get(index).equals(selected)) {
                    widgets.templateList.setSelectedIndex(index);
                    return;
                }
            }
        }
        if (!widgets.templates.isEmpty()) widgets.templateList.setSelectedIndex(0);
    }

    /** 渲染链步骤：序号 + 节点标识 + 节点显示名。 */
    public static void renderSteps(Widgets widgets, ToStringPreset.Template item,
                                   UiKit.FontSink sink) {
        widgets.steps.removeAll();
        if (item == null) {
            widgets.steps.add(UiKit.label("请选择一条链模板。", Font.PLAIN, 13, UiKit.MUTED, sink));
            widgets.steps.revalidate();
            widgets.steps.repaint();
            return;
        }
        List<String> nodes = new ArrayList<String>();
        nodes.add(ToStringPreset.carrier());
        nodes.addAll(item.gadgets);
        for (int index = 0; index < nodes.size(); index++) {
            JPanel row = new JPanel(new BorderLayout(12, 0));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
            row.add(UiKit.label((index + 1) + ".", Font.BOLD, 12, UiKit.MUTED, sink), BorderLayout.WEST);
            String node = nodes.get(index);
            row.add(UiKit.label(node + "    " + label(node), Font.PLAIN, 13, UiKit.TEXT, sink),
                    BorderLayout.CENTER);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            widgets.steps.add(row);
        }
        widgets.steps.revalidate();
        widgets.steps.repaint();
    }

    /** 节点显示名；取不到时回退成标识，不让链上出现空行。 */
    private static String label(String nodeId) {
        String name = payload.PayloadEngine.nodeLabel(nodeId);
        return name == null || name.trim().isEmpty() ? "" : name;
    }
}
