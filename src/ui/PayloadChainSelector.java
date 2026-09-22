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
import javax.swing.JToggleButton;
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
 *
 * <p>每列还带网页版那样的两项辅助：一个候选计数（候选上百项时先看数字再决定要不要过滤），
 * 一个标签筛选器（按上游标签收敛候选，例如只看 Bytecode 末端节点）。标签筛选与关键字过滤
 * 是「与」的关系：两者同时生效，命中的候选才渲染出来。
 */
public final class PayloadChainSelector implements ChainTagMenu.Handler {

    /** 列宽：够放「TemplatesImpl加载字节码」这类中文显示名，又不至于一屏塞不下两列。 */
    private static final int COLUMN_WIDTH = 244;
    /** 每列列表的高度：约九行，候选上百项时靠列内滚动。 */
    public static final int LIST_HEIGHT = 168;
    /** 整条选择器的高度：标题 + 过滤框 + 列表 + 内边距。 */
    public static final int STRIP_HEIGHT = 248;
    /** 列内单行高度：固定行高让长显示名被截断成省略号，而不是撑出横向滚动条。 */
    private static final int ROW_HEIGHT = 26;

    /** 一列的展示数据：标题、候选值、候选显示名、标签与末端标记、当前选中值。 */
    public static final class Column {
        public final String title;
        public final List<String> values;
        public final List<String> labels;
        /** 与 values 一一对应的标签（逗号分隔展示用），可为空。 */
        public final List<String> tags;
        /** 与 values 一一对应的末端标记。 */
        public final List<Boolean> ends;
        /** 本列候选出现过的全部标签，供标签筛选器列出可选项。 */
        public final List<String> availableTags;
        public final String selected;

        public Column(String title, List<String> values, List<String> labels, String selected) {
            this(title, values, labels, null, null, null, selected);
        }

        public Column(String title, List<String> values, List<String> labels, List<String> tags,
                      List<Boolean> ends, List<String> availableTags, String selected) {
            this.title = title;
            this.values = values == null ? new ArrayList<String>() : values;
            this.labels = labels == null ? new ArrayList<String>() : labels;
            this.tags = tags == null ? new ArrayList<String>() : tags;
            this.ends = ends == null ? new ArrayList<Boolean>() : ends;
            this.availableTags = availableTags == null ? new ArrayList<String>() : availableTags;
            this.selected = selected;
        }

        /** 第 index 项的标签文本；没有标签时为空串。 */
        String tagAt(int index) {
            return index >= 0 && index < tags.size() && tags.get(index) != null ? tags.get(index) : "";
        }

        /** 第 index 项是否为末端节点。 */
        boolean endAt(int index) {
            return index >= 0 && index < ends.size() && Boolean.TRUE.equals(ends.get(index));
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
    /** 每列当前渲染出的项在原始候选里的下标：标签与末端标记按原始下标取。 */
    private final List<List<Integer>> shownIndices = new ArrayList<List<Integer>>();
    /** 每列的过滤框：供自检设置关键字，也为将来「聚焦到第 N 列」留出入口。 */
    private final List<JTextField> filterFields = new ArrayList<JTextField>();
    /** 每列选中的标签：与关键字过滤是「与」的关系。 */
    private final List<List<String>> tagFilters = new ArrayList<List<String>>();
    /**
     * 每列的标签匹配方式：true 为交集（须同时带全部所选标签），false 为并集（任一命中即可）。
     *
     * <p>网页版把这两种模式摆在标签菜单里。默认并集：多选标签的常见意图是
     * 「这几类都想看」，交集在候选里几乎恒为空。
     */
    private final List<Boolean> tagIntersect = new ArrayList<Boolean>();
    /** 每列的标题标签与计数标签，重绘时更新计数。 */
    private final List<JLabel> countLabels = new ArrayList<JLabel>();
    /** 每列的标签筛选按钮，供自检读取与程序性切换。 */
    private final List<JToggleButton> tagButtons = new ArrayList<JToggleButton>();

    /** 字体回调：列内控件与其它页面同源，窗口缩放时一并缩放。 */
    private final UiKit.FontSink fonts;
    /** 悬停选链：鼠标滑过候选即选中（网页版的「悬停选链」，默认关闭）。 */
    private boolean hoverSelect;
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
     * 开关悬停选链。
     *
     * <p>打开后鼠标滑过候选即等同于点击：网页版把它做成默认关闭的可选项，
     * 因为滑过就改链在有误触顾虑时是反效果。这里保持同样的默认值。
     */
    public void setHoverSelect(boolean enabled) {
        this.hoverSelect = enabled;
    }

    public boolean hoverSelect() {
        return hoverSelect;
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

    /** 第 index 列当前渲染出的候选数（已应用标签与关键字过滤）；越界返回 0。 */
    public int countAt(int index) {
        return listAt(index) == null ? 0 : listAt(index).getModel().getSize();
    }

    /** 第 index 列可筛选的标签数；越界返回 0。 */
    public int tagCountAt(int index) {
        if (index < 0 || index >= columns.size()) return 0;
        return columns.get(index).availableTags.size();
    }

    /** 第 index 列的第 position 个可筛标签；越界返回空串。 */
    public String tagAt(int index, int position) {
        if (index < 0 || index >= columns.size()) return "";
        java.util.List<String> tags = columns.get(index).availableTags;
        return position < 0 || position >= tags.size() ? "" : tags.get(position);
    }

    /** 该列当前选中的标签，供自检核对。 */
    public java.util.List<String> selectedTagsAt(int index) {
        return index < 0 || index >= tagFilters.size()
                ? new ArrayList<String>() : new ArrayList<String>(tagFilters.get(index));
    }

    /**
     * 设置某一列的标签筛选：整列替换，不做叠加。
     *
     * <p>给自检与将来的「记住上次筛选」留出入口；界面上的多选菜单走的就是这个方法，
     * 保证「程序性设置」与「人手点选」最终落到同一处状态。
     */
    public void setTagFilter(int column, java.util.List<String> tags) {
        if (column < 0 || column >= tagFilters.size()) return;
        java.util.List<String> target = tagFilters.get(column);
        target.clear();
        if (tags != null) {
            for (String tag : tags) {
                if (tag != null && !tag.trim().isEmpty()) target.add(tag.trim());
            }
        }
        resetColumnViews(column);
        repaintColumns();
    }

    /** 清除某一列的标签筛选。 */
    public void clearTagFilter(int column) {
        setTagFilter(column, null);
    }

    /**
     * 把第 index 列滚进可视区。
     *
     * <p>链路上的徽标点击后要「定位到对应列」：链一长，目标列就落在可视区之外，
     * 只改选中态而不滚动，使用者看到的还是原来那一屏。
     */
    public void focusColumn(int index) {
        if (index < 0 || index >= columns.size()) return;
        final int target = index;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (target >= strip.getComponentCount()) return;
                Component column = strip.getComponent(target * 2);
                if (column == null) return;
                strip.scrollRectToVisible(new Rectangle(column.getX(), 0, column.getWidth(), 1));
            }
        });
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
            // 标签选择按列保留：换载体后同一列位置上的标签筛选不该被静默清掉，
            // 但新列的候选若不再含该标签，过滤器会因「零命中」在过滤里被自然忽略
            while (tagFilters.size() < columns.size()) tagFilters.add(new ArrayList<String>());
            while (tagFilters.size() > columns.size()) tagFilters.remove(tagFilters.size() - 1);
            while (tagIntersect.size() < columns.size()) tagIntersect.add(Boolean.FALSE);
            while (tagIntersect.size() > columns.size()) tagIntersect.remove(tagIntersect.size() - 1);
            strip.removeAll();
            lists.clear();
            shownValues.clear();
            shownIndices.clear();
            filterFields.clear();
            countLabels.clear();
            tagButtons.clear();
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
        JPanel titleRow = new JPanel(new BorderLayout(8, 0));
        titleRow.setBackground(Color.WHITE);
        titleRow.add(UiKit.label(data.title, Font.BOLD, 13, UiKit.TEXT, fonts), BorderLayout.WEST);
        // 候选计数：与网页版每个 Gadget 列头右侧的数字一致，先看总数再决定要不要过滤
        JLabel count = UiKit.label(String.valueOf(data.values.size()), Font.BOLD, 12, UiKit.MUTED, fonts);
        count.setToolTipText("本列候选数（受标签与关键字过滤影响）");
        countLabels.add(count);
        titleRow.add(count, BorderLayout.EAST);
        head.add(titleRow, BorderLayout.NORTH);

        final JTextField filter = new JTextField(filters.get(index));
        UiKit.styleField(filter, fonts);
        filter.setToolTipText("按节点名称或标识过滤本列候选；当前已选中的节点不会被过滤掉");
        filterFields.add(filter);

        // 过滤框与标签筛选器同一行：竖着排要多占一行高度，列内的候选列表就少一行，
        // 而这两者本来就是「缩小候选范围」的同一类动作，并排更符合使用顺序
        final JToggleButton tagButton = new JToggleButton(tagSummary(index));
        tagButton.setForeground(UiKit.TEXT);
        tagButton.setBackground(Color.WHITE);
        tagButton.setFocusPainted(false);
        tagButton.setToolTipText("按上游标签筛选本列候选");
        tagButton.setMargin(new java.awt.Insets(0, 0, 0, 0));
        tagButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.FIELD_BORDER),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        fonts.track(tagButton, Font.PLAIN, 12);
        tagButtons.add(tagButton);
        tagButton.addActionListener(event -> ChainTagMenu.show(tagButton, index, data, this));

        JPanel filterRow = new JPanel(new BorderLayout(6, 0));
        filterRow.setBackground(Color.WHITE);
        filterRow.add(filter, BorderLayout.CENTER);
        JPanel tagHolder = new JPanel(new BorderLayout(0, 0));
        tagHolder.setBackground(Color.WHITE);
        tagHolder.add(tagButton, BorderLayout.CENTER);
        tagHolder.setPreferredSize(new Dimension(74, 34));
        filterRow.add(tagHolder, BorderLayout.EAST);
        head.add(filterRow, BorderLayout.CENTER);
        panel.add(head, BorderLayout.NORTH);

        final DefaultListModel<String> model = new DefaultListModel<String>();
        final JList<String> list = new JList<String>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Color.WHITE);
        list.setForeground(UiKit.BODY_TEXT);
        list.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        list.setCellRenderer(new ChainNodeRenderer(data, index, fonts, shownIndices));
        fonts.track(list, Font.PLAIN, 13);
        // 固定单元格宽度：列内因此不会出现横向滚动条，过长的显示名由标签自行截断成省略号
        list.setFixedCellWidth(COLUMN_WIDTH - 48);
        list.setFixedCellHeight(ROW_HEIGHT);
        lists.add(list);

        final List<Integer> rows = rowsFor(index, data);
        shownValues.add(valuesOf(data, rows));
        shownIndices.add(rows);
        fill(model, data, rows);
        int selectedRow = rows.indexOf(Integer.valueOf(
                    ChainColumnFilter.indexOf(data.values, data.selected)));
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
        list.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent event) {
                if (!hoverSelect || rebuilding) return;
                int row = list.locationToIndex(event.getPoint());
                if (row < 0 || row >= rows.size()) return;
                choose(index, data.values.get(rows.get(row).intValue()));
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
        listScroll.setPreferredSize(new Dimension(COLUMN_WIDTH - 22, LIST_HEIGHT - 44));
        panel.add(listScroll, BorderLayout.CENTER);
        return panel;
    }

    /** 标签筛选按钮的文字：未选标签时提示可筛，选了就给出数量与匹配方式。 */
    private String tagSummary(int index) {
        List<String> chosen = index < tagFilters.size() ? tagFilters.get(index) : new ArrayList<String>();
        if (chosen.isEmpty()) return "标签 \u25be";
        boolean intersect = index < tagIntersect.size() && Boolean.TRUE.equals(tagIntersect.get(index));
        return "标签 " + chosen.size() + (intersect ? " \u2229" : " \u222a") + " \u25be";
    }

    /** 第 index 列的标签是否按交集匹配；越界返回 false（并集）。 */
    public boolean tagMatchIsIntersect(int index) {
        return index >= 0 && index < tagIntersect.size() && Boolean.TRUE.equals(tagIntersect.get(index));
    }

    /** 设置第 index 列的标签匹配方式；true 为交集。 */
    public void setTagMatch(int index, boolean intersect) {
        if (index < 0 || index >= tagIntersect.size()) return;
        if (Boolean.valueOf(intersect).equals(tagIntersect.get(index))) return;
        tagIntersect.set(index, Boolean.valueOf(intersect));
        resetColumnViews(index);
        repaintColumns();
    }


    /** 重绘后按钮文字与计数要跟着更新；单个列的按钮按列下标取。 */
    private void resetColumnViews(int index) {
        if (index < tagButtons.size()) tagButtons.get(index).setText(tagSummary(index));
    }

    /** 菜单回写：已选标签直接交给菜单增删，匹配方式与刷新走这里。 */
    @Override
    public java.util.List<String> chosen(int index) {
        return index >= 0 && index < tagFilters.size() ? tagFilters.get(index) : new ArrayList<String>();
    }

    @Override
    public boolean isIntersect(int index) {
        return tagMatchIsIntersect(index);
    }

    @Override
    public void apply(int index, boolean intersect) {
        setTagMatch(index, intersect);
    }

    @Override
    public void refresh(int index) {
        resetColumnViews(index);
        repaintColumns();
    }

    /**
     * 只重绘列表内容，不重建列结构。
     *
     * <p>过滤时若整体重建，正在输入的过滤框会被换掉，光标随之丢失，
     * 连续输入第二个字符就会落空。
     */
    /** 取第 index 列按当前筛选条件保留的行；筛选条件与列对象都在本类手里。 */
    private List<Integer> rowsFor(int index, Column data) {
        List<String> tags = index >= 0 && index < tagFilters.size()
                ? tagFilters.get(index) : new ArrayList<String>();
        boolean intersect = index >= 0 && index < tagIntersect.size()
                && Boolean.TRUE.equals(tagIntersect.get(index));
        return ChainColumnFilter.rows(data, filters.get(index), tags, intersect);
    }

    private void repaintColumns() {
        rebuilding = true;
        try {
            for (int index = 0; index < Math.min(lists.size(), columns.size()); index++) {
                Column data = columns.get(index);
                List<Integer> rows = rowsFor(index, data);
                shownValues.set(index, valuesOf(data, rows));
                if (index < shownIndices.size()) shownIndices.set(index, rows);
                DefaultListModel<String> model = new DefaultListModel<String>();
                fill(model, data, rows);
                lists.get(index).setModel(model);
                int selectedRow = rows.indexOf(Integer.valueOf(
                    ChainColumnFilter.indexOf(data.values, data.selected)));
                if (selectedRow >= 0) lists.get(index).setSelectedIndex(selectedRow);
                // 计数显示的是「当前渲染出的项数」：过滤后还要看到真实候选数就没意义了，
                // 使用者要判断的正是「过滤后还剩几条可选」
                if (index < countLabels.size()) countLabels.get(index).setText(String.valueOf(rows.size()));
                if (index < tagButtons.size()) tagButtons.get(index).setText(tagSummary(index));
            }
        } finally {
            rebuilding = false;
        }
    }

    private static void fill(DefaultListModel<String> model, Column data, List<Integer> rows) {
        for (int position = 0; position < rows.size(); position++) {
            model.addElement(ChainColumnFilter.label(data, rows.get(position).intValue()));
        }
    }

    private static List<String> valuesOf(Column data, List<Integer> rows) {
        List<String> values = new ArrayList<String>();
        for (int position = 0; position < rows.size(); position++) {
            values.add(data.values.get(rows.get(position).intValue()));
        }
        return values;
    }









}
