package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

/**
 * 列式链选择器：把「当前链的下一层能接什么」按列并列展开，点候选即改链。
 *
 * <p>对应网页版 java-chains Generate 页的选择器形态：第一列是载荷载体，
 * 选中后在其右侧展开首节点候选，再选中又展开下一列；点回前面某一列的其它候选，
 * 即以该列为界重开链。相比「一个下拉框 + 一个追加按钮」，改中间某一层
 * 从「反复删除末节点」变成一次点击。
 *
 * <p>本组件不持有链状态：每列展示什么、哪一项是当前选中项，全部由调用方按列传入
 * （见 {@link Column}），点击只转成 {@link SelectionSink} 回调。链的权威仍是
 * {@code ChainEditor}，两处各存一份必然漂移。
 *
 * <p>过滤框的关键字属于本组件的展示状态，不进入链：过滤只收敛该列渲染出的项，
 * 且当前选中项始终保留在列表里——否则使用者会看到「链上有这一项、列表里却没有」。
 */
public final class PayloadChainSelector {

    /** 列宽：够放「TemplatesImpl加载字节码」这类中文显示名，又不至于一屏塞不下两列。 */
    private static final int COLUMN_WIDTH = 244;
    /** 每列列表的高度：约 10 行，候选上百项时靠列内滚动。 */
    private static final int LIST_HEIGHT = 254;
    /** 整条选择器的高度：标题 + 过滤框 + 列表 + 内边距。 */
    private static final int STRIP_HEIGHT = 334;
    /** 列内单行高度：固定行高让长显示名被截断成省略号，而不是撑出横向滚动条。 */
    private static final int ROW_HEIGHT = 26;

    /** 一列的展示数据：标题、候选值、候选显示名、当前选中值。 */
    public static final class Column {
        public final String title;
        public final List<String> values;
        public final List<String> labels;
        public final String selected;

        public Column(String title, List<String> values, List<String> labels, String selected) {
            this.title = title;
            this.values = values == null ? new ArrayList<String>() : values;
            this.labels = labels == null ? new ArrayList<String>() : labels;
            this.selected = selected;
        }
    }

    /** 点击某一列的某一项：调用方据此截断链并重算后续列。 */
    public interface SelectionSink {
        void select(int columnIndex, String value);
    }

    private final JPanel strip = new JPanel();
    private final JScrollPane scroll;
    private final List<String> filters = new ArrayList<String>();
    /** 每列的候选列表：供自检读取列数与项数。 */
    private final List<JList<String>> lists = new ArrayList<JList<String>>();
    /** 每列当前渲染出的值，与列表下标一一对应。 */
    private final List<List<String>> shownValues = new ArrayList<List<String>>();
    /** 每列的过滤框：供自检设置关键字，也为将来「聚焦到第 N 列」留出入口。 */
    private final List<JTextField> filterFields = new ArrayList<JTextField>();

    /** 字体回调：列内控件与其它页面同源，窗口缩放时一并缩放。 */
    private final UiKit.FontSink fonts;
    private SelectionSink sink;
    /** 重建期间抑制回调：程序性设置选中项不能被当成使用者点击。 */
    private boolean rebuilding;
    /** 最近一次渲染的数据，过滤变化时据此重绘。 */
    private List<Column> columns = new ArrayList<Column>();

    public PayloadChainSelector(UiKit.FontSink fonts) {
        this.fonts = fonts;
        strip.setLayout(new BoxLayout(strip, BoxLayout.X_AXIS));
        strip.setBackground(Color.WHITE);
        scroll = new JScrollPane(strip);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Color.WHITE);
        // 列数随链增长，必须能横向滚动，否则后面的列会被裁掉且看不到
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(0, STRIP_HEIGHT));
        scroll.setMinimumSize(new Dimension(0, STRIP_HEIGHT));
    }

    public JScrollPane component() {
        return scroll;
    }

    public void setSink(SelectionSink sink) {
        this.sink = sink;
    }

    /**
     * 选取第 column 列的 value：这是本组件唯一的「改链」入口。
     *
     * <p>列表的选中事件与程序性调用都走这里，判定只有一份：
     * 重复选取当前选中项不产生任何变化（Swing 对「再次选中同一项」不发事件，
     * 键盘与鼠标的行为因此可能不同，这里统一成明确规则）。
     *
     * @return 是否真的产生了新的选取（供调用方判断要不要重绘）
     */
    public boolean choose(int column, String value) {
        if (rebuilding || value == null || value.trim().isEmpty()) return false;
        if (column < 0 || column >= columns.size()) return false;
        Column data = columns.get(column);
        if (value.equals(data.selected)) return false;
        if (!data.values.contains(value)) return false;
        if (sink != null) sink.select(column, value);
        return true;
    }

    /** 当前列数（含载体列）。 */
    public int columnCount() {
        return lists.size();
    }

    /** 第 index 列的候选列表；越界返回 null。 */
    public JList<String> listAt(int index) {
        return index < 0 || index >= lists.size() ? null : lists.get(index);
    }

    /** 第 index 列当前渲染出的值序列，与列表下标一一对应。 */
    public List<String> valuesAt(int index) {
        return index < 0 || index >= shownValues.size() ? new ArrayList<String>() : shownValues.get(index);
    }

    /** 第 index 列的过滤框文本；越界返回空串。 */
    public String filterAt(int index) {
        return index < 0 || index >= filters.size() ? "" : filters.get(index);
    }

    /** 第 index 列的过滤框控件；越界返回 null。 */
    public JTextField filterFieldAt(int index) {
        return index < 0 || index >= filterFields.size() ? null : filterFields.get(index);
    }

    /**
     * 按新的列数据整体重绘。
     *
     * <p>过滤关键字按列下标保留：追加节点后重绘时，使用者刚在前一列输入的关键字不该消失。
     */
    public void render(List<Column> next) {
        rebuilding = true;
        try {
            columns = next == null ? new ArrayList<Column>() : next;
            while (filters.size() < columns.size()) filters.add("");
            while (filters.size() > columns.size()) filters.remove(filters.size() - 1);
            strip.removeAll();
            lists.clear();
            shownValues.clear();
            filterFields.clear();
            for (int index = 0; index < columns.size(); index++) {
                strip.add(column(index));
                if (index < columns.size() - 1) strip.add(Box.createHorizontalStrut(10));
            }
            strip.revalidate();
            strip.repaint();
        } finally {
            rebuilding = false;
        }
        scrollLastIntoView();
    }

    /**
     * 把最后一列滚进可视区。
     *
     * <p>展开新列后若不滚动，新列会落在可视区之外：使用者点完一项看不到下一步，
     * 会误以为「没有可接的节点」。
     */
    private void scrollLastIntoView() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                int count = strip.getComponentCount();
                if (count == 0) return;
                Component last = strip.getComponent(count - 1);
                if (last == null) return;
                strip.scrollRectToVisible(new Rectangle(last.getX(), 0, last.getWidth(), 1));
            }
        });
    }

    private JPanel column(final int index) {
        final Column data = columns.get(index);
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBackground(Color.WHITE);
        Dimension size = new Dimension(COLUMN_WIDTH, STRIP_HEIGHT - 10);
        panel.setPreferredSize(size);
        panel.setMinimumSize(size);
        panel.setMaximumSize(size);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.FIELD_BORDER),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)));

        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setBackground(Color.WHITE);
        head.add(UiKit.label(data.title, Font.BOLD, 13, UiKit.TEXT, fonts), BorderLayout.NORTH);

        final JTextField filter = new JTextField(filters.get(index));
        UiKit.styleField(filter, fonts);
        filter.setToolTipText("按节点名称或标识过滤本列候选；当前已选中的节点不会被过滤掉");
        filterFields.add(filter);
        head.add(filter, BorderLayout.CENTER);
        panel.add(head, BorderLayout.NORTH);

        final DefaultListModel<String> model = new DefaultListModel<String>();
        final JList<String> list = new JList<String>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Color.WHITE);
        list.setForeground(UiKit.BODY_TEXT);
        list.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        list.setCellRenderer(new NodeRenderer());
        fonts.track(list, Font.PLAIN, 13);
        // 固定单元格宽度：列内因此不会出现横向滚动条，过长的显示名由标签自行截断成省略号
        list.setFixedCellWidth(COLUMN_WIDTH - 48);
        list.setFixedCellHeight(ROW_HEIGHT);
        lists.add(list);

        final List<Integer> rows = filteredRows(data, filters.get(index));
        shownValues.add(valuesOf(data, rows));
        fill(model, data, rows);
        int selectedRow = rows.indexOf(Integer.valueOf(indexOf(data.values, data.selected)));
        if (selectedRow >= 0) list.setSelectedIndex(selectedRow);

        final SelectionSink target = sink;
        list.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent event) {
                if (event.getValueIsAdjusting() || rebuilding) return;
                int row = list.getSelectedIndex();
                if (row < 0 || row >= rows.size()) return;
                String value = data.values.get(rows.get(row).intValue());
                if (target == null) return;
                choose(index, value);
            }
        });
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                apply();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                apply();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                apply();
            }

            private void apply() {
                if (rebuilding) return;
                String text = filter.getText() == null ? "" : filter.getText().trim();
                if (text.equals(filters.get(index))) return;
                filters.set(index, text);
                repaintColumns();
            }
        });

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setBorder(BorderFactory.createLineBorder(UiKit.BORDER));
        listScroll.getViewport().setBackground(Color.WHITE);
        listScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        listScroll.setPreferredSize(new Dimension(COLUMN_WIDTH - 22, LIST_HEIGHT));
        panel.add(listScroll, BorderLayout.CENTER);
        return panel;
    }

    /**
     * 只重绘列表内容，不重建列结构。
     *
     * <p>过滤时若整体重建，正在输入的过滤框会被换掉，光标随之丢失，
     * 连续输入第二个字符就会落空。
     */
    private void repaintColumns() {
        rebuilding = true;
        try {
            for (int index = 0; index < Math.min(lists.size(), columns.size()); index++) {
                Column data = columns.get(index);
                List<Integer> rows = filteredRows(data, filters.get(index));
                shownValues.set(index, valuesOf(data, rows));
                DefaultListModel<String> model = new DefaultListModel<String>();
                fill(model, data, rows);
                lists.get(index).setModel(model);
                int selectedRow = rows.indexOf(Integer.valueOf(indexOf(data.values, data.selected)));
                if (selectedRow >= 0) lists.get(index).setSelectedIndex(selectedRow);
            }
        } finally {
            rebuilding = false;
        }
    }

    private static void fill(DefaultListModel<String> model, Column data, List<Integer> rows) {
        for (int position = 0; position < rows.size(); position++) {
            model.addElement(labelOf(data, rows.get(position).intValue()));
        }
    }

    private static List<String> valuesOf(Column data, List<Integer> rows) {
        List<String> values = new ArrayList<String>();
        for (int position = 0; position < rows.size(); position++) {
            values.add(data.values.get(rows.get(position).intValue()));
        }
        return values;
    }

    /**
     * 按关键字过滤一列，返回保留项在原始值序列中的下标。
     *
     * <p>当前选中项无条件保留：它与关键字不匹配时若被过滤掉，
     * 使用者会看到「链上有这一项、列表里却没有」，进而以为链坏了。
     */
    private static List<Integer> filteredRows(Column data, String keyword) {
        List<Integer> rows = new ArrayList<Integer>();
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        for (int index = 0; index < data.values.size(); index++) {
            String value = data.values.get(index);
            if (value == null || value.trim().isEmpty()) continue;
            if (needle.isEmpty() || matches(data, index, needle)) rows.add(Integer.valueOf(index));
        }
        int selected = indexOf(data.values, data.selected);
        if (selected >= 0 && !rows.contains(Integer.valueOf(selected))) {
            rows.add(0, Integer.valueOf(selected));
        }
        return rows;
    }

    private static int indexOf(List<String> values, String wanted) {
        if (wanted == null || wanted.isEmpty()) return -1;
        for (int index = 0; index < values.size(); index++) {
            if (wanted.equals(values.get(index))) return index;
        }
        return -1;
    }

    /** 关键字匹配：值与显示名任一命中即可，便于按中文名或英文标识查找。 */
    private static boolean matches(Column data, int index, String needle) {
        String value = data.values.get(index);
        String label = labelOf(data, index);
        return (value != null && value.toLowerCase(Locale.ROOT).contains(needle))
                || (label != null && label.toLowerCase(Locale.ROOT).contains(needle));
    }

    private static String labelOf(Column data, int index) {
        if (index < 0 || index >= data.values.size()) return "";
        String value = data.values.get(index);
        String label = index < data.labels.size() ? data.labels.get(index) : null;
        // 上游实测有 3 个节点没有显示名：回退成标识，不能让列里出现空行
        return label == null || label.trim().isEmpty() ? value : label;
    }

    /** 列内条目的工具提示给出节点标识：显示名被截断时靠它确认选中的是哪一个。 */
    private static final class NodeRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focused);
            label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            label.setToolTipText(String.valueOf(value));
            return label;
        }
    }
}
