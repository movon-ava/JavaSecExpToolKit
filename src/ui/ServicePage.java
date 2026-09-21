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
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

import service.ServiceDefaults;
import service.ServiceSpec;

/**
 * 恶意服务器页：左侧服务清单，右侧该服务的监听参数、载荷发布与运行状态。
 *
 * <p>只负责界面结构；启停与发布的行为在 {@link ServiceController} 里，本页不持有运行状态。
 * 左侧用服务清单而不是页签，是因为同时只能有一类服务在改参数，清单能顺带把
 * 「哪几类已经在跑」直接显示在行上。
 */
public final class ServicePage {

    /** 左侧清单宽度。 */
    private static final int LIST_WIDTH = 280;
    /** 右侧参数区最小高度，保证发布与输出区不被挤掉。 */
    private static final int OUTPUT_MIN_HEIGHT = 180;

    /** 本页需要的控件与回调。 */
    public static final class Widgets {
        /** 服务清单模型，元素是服务标识。 */
        public final DefaultListModel<String> services = new DefaultListModel<String>();
        public final JList<String> serviceList = new JList<String>(services);
        public final JLabel title = new JLabel("");
        public final JLabel summary = new JLabel("");
        public final JLabel stateChip = new JLabel("");
        public final JLabel status = new JLabel("");

        /** 端口输入框：键为端口键。由本页在切换服务时重建。 */
        public final Map<String, JTextField> portFields = new LinkedHashMap<String, JTextField>();
        /** 端口行的容器，切换服务时整体重建。 */
        public final JPanel portPanel = new JPanel();
        public final JTextField bindHost = new JTextField("", 18);
        public final JTextField advertiseHost = new JTextField("", 18);

        public final JComboBox<String> group = new JComboBox<String>();
        public final JComboBox<String> kind = new JComboBox<String>();
        public final JTextField chain = new JTextField("", 36);
        public final JComboBox<String> next = new JComboBox<String>();
        public final JButton addNode = new JButton("追加节点");
        public final JButton undo = new JButton("删除末节点");
        public final JButton clearChain = new JButton("清空链");
        public final JPanel params = new JPanel();

        public final JButton start = new JButton("启动服务");
        public final JButton stop = new JButton("停止服务");
        public final JButton publish = new JButton("发布载荷");
        public final JButton refresh = new JButton("刷新状态");
        public final JButton copyAddress = new JButton("复制地址");
        public final JButton toCapture = new JButton("填入抓包页");

        public final JTextArea output = new JTextArea();

        /** 当前选中服务的标识；由控制器维护。 */
        public String currentKey = "";
        /** 把地址填入抓包页；由界面层提供。 */
        public CaptureSink onSendToCapture;
        /** 当前渲染出的参数行，供自检读取。 */
        public List<PayloadPage.ParamField> fields = new ArrayList<PayloadPage.ParamField>();
    }

    /** 把发布地址交给抓包页，复用该页已有的目标 URL 输入框。 */
    public interface CaptureSink {
        void send(String address);
    }

    private ServicePage() {
    }

    public static JPanel build(Widgets widgets, UiKit.FontSink sink) {
        JPanel page = UiKit.page();
        page.add(UiKit.pageHeading("SERVERS", "恶意服务器",
                "java-chains 服务端  ·  真实监听  ·  载荷发布  ·  仅限授权环境", sink), BorderLayout.NORTH);

        JScrollPane listScroll = new JScrollPane(widgets.serviceList);
        listScroll.setBorder(BorderFactory.createLineBorder(UiKit.BORDER));
        listScroll.getViewport().setBackground(Color.WHITE);
        widgets.serviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        widgets.serviceList.setBackground(Color.WHITE);
        widgets.serviceList.setFixedCellHeight(46);
        widgets.serviceList.setCellRenderer(new ServiceCellRenderer());
        sink.track(widgets.serviceList, Font.PLAIN, 14);
        JPanel left = UiKit.surface(new BorderLayout(0, 12));
        left.add(UiKit.sectionTitle("服务清单", "选中后配置", sink), BorderLayout.NORTH);
        left.add(listScroll, BorderLayout.CENTER);
        left.setPreferredSize(new Dimension(LIST_WIDTH, 0));

        JScrollPane right = new JScrollPane(rightSide(widgets, sink));
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

    private static JPanel rightSide(Widgets widgets, UiKit.FontSink sink) {
        JPanel stack = new JPanel();
        stack.setOpaque(false);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.add(headCard(widgets, sink));
        stack.add(Box.createVerticalStrut(16));
        stack.add(listenCard(widgets, sink));
        stack.add(Box.createVerticalStrut(16));
        stack.add(publishCard(widgets, sink));
        stack.add(Box.createVerticalStrut(16));
        stack.add(outputCard(widgets, sink));
        return stack;
    }

    private static JPanel headCard(Widgets widgets, UiKit.FontSink sink) {
        JPanel card = UiKit.surface(new BorderLayout(16, 0));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel texts = new JPanel();
        texts.setOpaque(false);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        sink.track(widgets.title, Font.BOLD, 22);
        widgets.title.setForeground(UiKit.TEXT);
        sink.track(widgets.summary, Font.PLAIN, 13);
        widgets.summary.setForeground(UiKit.MUTED);
        widgets.summary.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        texts.add(widgets.title);
        texts.add(widgets.summary);
        card.add(texts, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        sink.track(widgets.stateChip, Font.BOLD, 12);
        widgets.stateChip.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 10));
        actions.add(widgets.stateChip);
        UiKit.stylePrimaryButton(widgets.start, sink);
        UiKit.styleSecondaryButton(widgets.stop, sink);
        UiKit.styleSecondaryButton(widgets.refresh, sink);
        actions.add(widgets.start);
        actions.add(widgets.stop);
        actions.add(widgets.refresh);
        card.add(actions, BorderLayout.EAST);
        return card;
    }

    private static JPanel listenCard(Widgets widgets, UiKit.FontSink sink) {
        JPanel card = UiKit.surface(new BorderLayout(0, 14));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(UiKit.sectionTitle("监听参数", "留空用默认值", sink), BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(hostRow("绑定地址", widgets.bindHost, "服务实际监听的网卡地址，留空用 127.0.0.1", sink));
        body.add(Box.createVerticalStrut(10));
        body.add(hostRow("公布地址", widgets.advertiseHost,
                "写进载荷的回连地址：目标要能访问到它，跨机测试时填本机内网 IP", sink));
        body.add(Box.createVerticalStrut(10));
        widgets.portPanel.setOpaque(false);
        widgets.portPanel.setLayout(new BoxLayout(widgets.portPanel, BoxLayout.Y_AXIS));
        body.add(widgets.portPanel);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private static JPanel hostRow(String labelText, JTextField field, String hint, UiKit.FontSink sink) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        JLabel label = UiKit.label(labelText, Font.BOLD, 13, UiKit.TEXT, sink);
        label.setPreferredSize(new Dimension(96, 34));
        row.add(label, BorderLayout.WEST);
        UiKit.styleField(field, sink);
        JPanel holder = new JPanel(new BorderLayout(12, 0));
        holder.setOpaque(false);
        holder.add(field, BorderLayout.WEST);
        holder.add(UiKit.label(hint, Font.PLAIN, 12, UiKit.MUTED, sink), BorderLayout.CENTER);
        row.add(holder, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        return row;
    }

    private static JPanel publishCard(Widgets widgets, UiKit.FontSink sink) {
        JPanel card = UiKit.surface(new BorderLayout(0, 14));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(UiKit.sectionTitle("载荷发布", "选载体与链 → 发布到本服务", sink), BorderLayout.NORTH);

        JPanel stack = new JPanel();
        stack.setOpaque(false);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.add(comboRow("载体分组", widgets.group, "按用途分组的载荷载体", sink));
        stack.add(Box.createVerticalStrut(10));
        stack.add(comboRow("载荷载体", widgets.kind, "决定载荷的封装形式", sink));
        stack.add(Box.createVerticalStrut(10));

        JPanel chainRow = new JPanel(new BorderLayout(12, 0));
        chainRow.setOpaque(false);
        JLabel chainLabel = UiKit.label("当前链", Font.BOLD, 13, UiKit.TEXT, sink);
        chainLabel.setPreferredSize(new Dimension(96, 34));
        chainRow.add(chainLabel, BorderLayout.WEST);
        widgets.chain.setEditable(false);
        UiKit.styleField(widgets.chain, sink);
        chainRow.add(widgets.chain, BorderLayout.CENTER);
        chainRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        stack.add(chainRow);
        stack.add(Box.createVerticalStrut(10));

        JPanel nodeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        nodeRow.setOpaque(false);
        JLabel nextLabel = UiKit.label("可追加节点", Font.BOLD, 13, UiKit.TEXT, sink);
        nextLabel.setPreferredSize(new Dimension(96, 34));
        nextLabel.setHorizontalAlignment(JLabel.LEFT);
        nodeRow.add(nextLabel);
        UiKit.styleCombo(widgets.next, 320, sink);
        nodeRow.add(widgets.next);
        UiKit.styleSecondaryButton(widgets.addNode, sink);
        UiKit.styleSecondaryButton(widgets.undo, sink);
        UiKit.styleSecondaryButton(widgets.clearChain, sink);
        nodeRow.add(widgets.addNode);
        nodeRow.add(widgets.undo);
        nodeRow.add(widgets.clearChain);
        stack.add(nodeRow);
        stack.add(Box.createVerticalStrut(12));

        widgets.params.setOpaque(false);
        widgets.params.setLayout(new BoxLayout(widgets.params, BoxLayout.Y_AXIS));
        stack.add(widgets.params);
        stack.add(Box.createVerticalStrut(12));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        actions.setOpaque(false);
        UiKit.stylePrimaryButton(widgets.publish, sink);
        UiKit.styleSecondaryButton(widgets.copyAddress, sink);
        UiKit.styleSecondaryButton(widgets.toCapture, sink);
        actions.add(widgets.publish);
        actions.add(widgets.copyAddress);
        actions.add(widgets.toCapture);
        sink.track(widgets.status, Font.PLAIN, 13);
        widgets.status.setForeground(UiKit.MUTED);
        actions.add(widgets.status);
        stack.add(actions);
        card.add(stack, BorderLayout.CENTER);
        return card;
    }

    private static JPanel comboRow(String labelText, JComboBox<String> box, String hint, UiKit.FontSink sink) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        JLabel label = UiKit.label(labelText, Font.BOLD, 13, UiKit.TEXT, sink);
        label.setPreferredSize(new Dimension(96, 34));
        row.add(label, BorderLayout.WEST);
        JPanel holder = new JPanel(new BorderLayout(12, 0));
        holder.setOpaque(false);
        UiKit.styleCombo(box, 300, sink);
        holder.add(box, BorderLayout.WEST);
        holder.add(UiKit.label(hint, Font.PLAIN, 12, UiKit.MUTED, sink), BorderLayout.CENTER);
        row.add(holder, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        return row;
    }

    private static JPanel outputCard(Widgets widgets, UiKit.FontSink sink) {
        JPanel card = UiKit.surface(new BorderLayout(0, 12));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(UiKit.sectionTitle("运行输出", "发布地址与失败原因", sink), BorderLayout.NORTH);
        widgets.output.setEditable(false);
        UiKit.styleMonospaceArea(widgets.output, sink);
        card.add(UiKit.scroll(widgets.output), BorderLayout.CENTER);
        // 没有最小高度时，滚动面板会按内容收缩到一条细线（与 Payload 页同一问题）
        card.setMinimumSize(new Dimension(0, OUTPUT_MIN_HEIGHT));
        card.setPreferredSize(new Dimension(0, 260));
        return card;
    }

    /**
     * 按服务重塑端口输入行。切换服务时调用，旧行一并丢弃，避免残留失效的控件。
     *
     * <p>输入框初值取配置页保存的默认端口：端口是「可长期保存的配置」，
     * 每次都从代码里的常量重填，等于让使用者每次开机都要再改一遍。
     */
    public static void rebuildPorts(Widgets widgets, ServiceSpec spec,
                                    service.ServiceDefaults defaults, UiKit.FontSink sink) {
        widgets.portPanel.removeAll();
        widgets.portFields.clear();
        if (spec == null) {
            widgets.portPanel.revalidate();
            widgets.portPanel.repaint();
            return;
        }
        for (ServiceSpec.PortSpec port : spec.ports) {
            int initial = defaults == null
                    ? port.defaultPort : defaults.port(spec.key, port.key, port.defaultPort);
            JTextField field = new JTextField(String.valueOf(initial), 8);
            UiKit.styleField(field, sink);
            String hint = port.optional ? port.label + "（留空则不启用）" : port.label;
            widgets.portPanel.add(portRow(hint, field, sink));
            widgets.portPanel.add(Box.createVerticalStrut(10));
            widgets.portFields.put(port.key, field);
        }
        widgets.portPanel.revalidate();
        widgets.portPanel.repaint();
    }

    private static JPanel portRow(String labelText, JTextField field, UiKit.FontSink sink) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        JLabel label = UiKit.label(labelText, Font.BOLD, 13, UiKit.TEXT, sink);
        label.setPreferredSize(new Dimension(96, 34));
        row.add(label, BorderLayout.WEST);
        JPanel holder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        holder.setOpaque(false);
        holder.add(field);
        row.add(holder, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        return row;
    }

    /** 清单行：左侧服务名，右侧状态点，运行中的服务一眼可辨。 */
    private static final class ServiceCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            String key = value == null ? "" : String.valueOf(value);
            ServiceSpec spec = ServiceSpec.byKey(key);
            setText(spec == null ? key : spec.title);
            setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
            setForeground(selected ? Color.WHITE : UiKit.TEXT);
            setBackground(selected ? UiKit.ACCENT : Color.WHITE);
            return this;
        }
    }

    /** 更新清单模型：保留选中项，避免刷新后跳回第一项。 */
    public static void fillServices(Widgets widgets, List<ServiceSpec> specs) {
        String selected = widgets.serviceList.getSelectedValue();
        widgets.services.clear();
        for (ServiceSpec spec : specs) widgets.services.addElement(spec.key);
        if (selected != null) widgets.serviceList.setSelectedValue(selected, false);
    }

    /** 用下拉模型填充载体分组。 */
    public static void fillGroups(Widgets widgets, List<String> groups) {
        widgets.group.setModel(new DefaultComboBoxModel<String>(groups.toArray(new String[0])));
    }

    /** 供自检读取：当前端口输入框的键集合。 */
    public static Map<String, JTextField> portFields(Widgets widgets) {
        return widgets.portFields;
    }

    /** 供自检使用的空参数占位，避免外部访问到 null。 */
    public static JPanel paramsOf(Widgets widgets) {
        return widgets.params;
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
