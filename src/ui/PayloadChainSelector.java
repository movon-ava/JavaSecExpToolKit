package ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

/**
 * 列式链选择器：把「当前链的下一层能接什么」按列并列展开，点候选即改链。
 *
 * <p>对应网页版 java-chains Generate 页的选择器形态：第一列是载荷载体，
 * 选中后在其右侧展开首节点候选，再选中又展开下一列；点回前面某一列的其它候选，
 * 即以该列为界重开链。相比「一个下拉框 + 一个追加按钮」，改中间某一层
 * 从「反复删除末节点」变成一次点击。
 *
 * <p>几何全部对齐网页版前端（常量见 {@link ChainSelectorSizing}）：列宽按容器宽度与列数
 * 在 300~500 之间自适应、列间距 18、候选列表默认 320 高并可用竖向拖拽条调整、
 * 双击拖拽条在默认与展开档之间切换。一列的行内结构也与网页版一致：
 * 显示名 + 右侧等宽标识徽标 + END 徽标（见 {@link ChainNodeRenderer}）。
 *
 * <p>本组件不持有链状态：每列展示什么、哪一项是当前选中项，全部由调用方按列传入
 * （见 {@link ChainColumn}），点击只转成 {@link SelectionSink} 回调。链的权威仍是
 * {@code ChainEditor}，两处各存一份必然漂移。每列的筛选状态由 {@link ChainColumnState}
 * 统一持有，一列的面板由 {@link ChainColumnPanel} 构建：本类只做「列调度」——
 * 有哪些列、列多宽、列表多高、过滤后剩哪些行。
 *
 * <p>过滤框的关键字属于本组件的展示状态，不进入链：过滤只收敛该列渲染出的项，
 * 且当前选中项始终保留在列表里——否则使用者会看到「链上有这一项、列表里却没有」。
 *
 * <p>每列还带网页版那样的两项辅助：一个候选计数（候选上百项时先看数字再决定要不要过滤），
 * 一个标签筛选器（按上游标签收敛候选，例如只看 Bytecode 末端节点）。标签筛选与关键字过滤
 * 是「与」的关系：两者同时生效，命中的候选才渲染出来。
 */
public final class PayloadChainSelector implements ChainTagMenu.Handler, ChainColumnPanel.Host {

    /** 点击某一列的某一项：调用方据此截断链并重算后续列。 */
    public interface SelectionSink {
        void select(int columnIndex, String value);
    }

    /** 列表高度变化：正数表示列表变高，外层分栏据此把高度补给选链区。 */
    public interface HeightSink {
        void changed(int delta);
    }

    private final JPanel strip = new JPanel();
    private final JScrollPane scroll;
    /** 每列的面板：列宽与列表高度变化时逐个套用新尺寸，不重建列结构。 */
    private final List<ChainColumnPanel> columnPanels = new ArrayList<ChainColumnPanel>();
    /** 每列当前渲染出的值，与列表下标一一对应。 */
    private final List<List<String>> shownValues = new ArrayList<List<String>>();
    /** 每列当前渲染出的项在原始候选里的下标：标签与末端标记按原始下标取。 */
    private final List<List<Integer>> shownIndices = new ArrayList<List<Integer>>();
    /** 每列的筛选状态：关键字、已选标签与标签匹配方式。 */
    private final ChainColumnState state = new ChainColumnState();

    /** 字体回调：列内控件与其它页面同源，窗口缩放时一并缩放。 */
    private final UiKit.FontSink fonts;
    /** 悬停选链：鼠标滑过候选即选中（网页版的「悬停选链」，默认关闭）。 */
    private boolean hoverSelect;
    private SelectionSink sink;
    /** 列表高度变化的外层接收方：没有接收方时只改本组件高度。 */
    private HeightSink heightSink;
    /** 重建期间抑制回调：程序性设置选中项不能被当成使用者点击。 */
    private boolean rebuilding;
    /** 最近一次渲染的数据，过滤变化时据此重绘。 */
    private List<ChainColumn> columns = new ArrayList<ChainColumn>();
    /** 候选列表高度：默认 320，可由拖拽条调整；网页版把这一项记在 localStorage。 */
    private int listHeight = ChainSelectorSizing.DEFAULT_LIST_HEIGHT;
    /** 当前列宽：随容器宽度与列数重算。 */
    private int columnWidth = ChainSelectorSizing.MAX_COLUMN_WIDTH;
    /** 竖向拖拽条：往上拖调高列表，双击切换默认 / 展开。 */
    private final ChainResizeHandle handle = new ChainResizeHandle(new ChainResizeHandle.Listener() {
        @Override
        public void dragged(int delta) {
            resizeBy(delta);
        }

        @Override
        public void toggled() {
            setListHeight(ChainSelectorSizing.toggledListHeight(listHeight));
        }
    });

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
        // 视口宽度变化要重算列宽：列宽是按「容器宽度 ÷ 列数」算出来的，
        // 只在渲染时算一次的话，拖窗口大小后列会停在旧宽度上
        scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                applyColumnWidth();
            }
        });
        applyListHeight();
    }

    public JScrollPane component() {
        return scroll;
    }

    /** 竖向拖拽条：由页面装配方摆在选择器下方，拖动即可调高候选列表。 */
    public JComponent resizeHandle() {
        return handle;
    }

    /** 列表高度变化的外层接收方：页面装配方把它接到上下分栏，拖高时同步让出高度。 */
    public void setHeightSink(HeightSink sink) {
        this.heightSink = sink;
    }

    /** 当前候选列表高度。 */
    public int listHeight() {
        return listHeight;
    }

    /**
     * 设置候选列表高度（会收敛到 [160, 640]）。
     *
     * <p>高度变化量同时上报给 {@link HeightSink}：本组件只能决定「列有多高」，
     * 「页面给不给得出这么多高度」由外层分栏决定，两者必须一起动才看得见效果。
     */
    public void setListHeight(int height) {
        int next = ChainSelectorSizing.clampListHeight(height);
        int delta = next - listHeight;
        listHeight = next;
        applyListHeight();
        if (delta != 0 && heightSink != null) heightSink.changed(delta);
    }

    /** 拖拽增量：正数表示列表要变高。 */
    private void resizeBy(int delta) {
        if (delta == 0) return;
        int next = ChainSelectorSizing.clampListHeight(listHeight + delta);
        int applied = next - listHeight;
        if (applied == 0) return;
        listHeight = next;
        applyListHeight();
        // 上报实际生效的增量：已经在上下限时上报原始增量会让外层分栏白让一块高度
        if (heightSink != null) heightSink.changed(applied);
    }

    /** 把列表高度套到外层滚动面板与各列上。 */
    private void applyListHeight() {
        int stripHeight = ChainSelectorSizing.stripHeight(listHeight);
        scroll.setPreferredSize(new Dimension(0, stripHeight));
        // 最小高度按最小档给：按当前档给会把整页的最小高度也一起抬高，
        // 窗口变小时页面反而更早溢出（列高由各列自己随可用高度收缩，见 applyColumnWidth）
        scroll.setMinimumSize(new Dimension(0,
                ChainSelectorSizing.stripHeight(ChainSelectorSizing.MIN_LIST_HEIGHT)));
        for (ChainColumnPanel panel : columnPanels) {
            panel.listScroll().setPreferredSize(new Dimension(0, listHeight));
            panel.listScroll().setMinimumSize(new Dimension(0, Math.min(listHeight, 60)));
        }
        applyColumnWidth();
        strip.revalidate();
        strip.repaint();
    }

    /** 按容器宽度与列数重算列宽并套到各列；宽度没变时直接返回，避免无谓重排。 */
    private void applyColumnWidth() {
        int containerWidth = scroll.getViewport().getWidth();
        if (containerWidth <= 0) containerWidth = scroll.getWidth();
        int width = ChainSelectorSizing.columnWidth(containerWidth, Math.max(1, columns.size()));
        if (width == columnWidth && !columnPanels.isEmpty()) return;
        columnWidth = width;
        for (ChainColumnPanel panel : columnPanels) {
            applyPanelSize(panel);
        }
        strip.revalidate();
        strip.repaint();
    }

    /** 把一个列面板的尺寸套成当前列宽与当前列表高度。 */
    private void applyPanelSize(ChainColumnPanel panel) {
        Dimension preferred = new Dimension(columnWidth, ChainSelectorSizing.columnHeight(listHeight));
        // 列高只给「首选」，最大高度放开：选链区实际拿到的高度由外层分栏决定，
        // 把最小 / 最大都钉死在首选高度上，列就会被视口裁掉底部（实测踩到：
        // 列需要 404px、视口只有 283px，候选列表最后 5 行看不到且没有滚动条）
        Dimension minimum = new Dimension(columnWidth, ChainSelectorSizing.columnHeight(
                ChainSelectorSizing.MIN_LIST_HEIGHT));
        Dimension maximum = new Dimension(columnWidth, Integer.MAX_VALUE);
        panel.panel().setPreferredSize(preferred);
        panel.panel().setMinimumSize(minimum);
        panel.panel().setMaximumSize(maximum);
        panel.list().setFixedCellWidth(rowWidth());
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
        ChainColumn data = columns.get(column);
        if (value.equals(data.selected)) return false;
        if (!data.values.contains(value)) return false;
        if (sink != null) sink.select(column, value);
        return true;
    }

    /** 当前列数（含载体列）。 */
    public int columnCount() {
        return columnPanels.size();
    }

    /** 第 index 列的候选列表；越界返回 null。 */
    public JList<String> listAt(int index) {
        return index < 0 || index >= columnPanels.size() ? null : columnPanels.get(index).list();
    }

    /** 第 index 列当前渲染出的值序列，与列表下标一一对应。 */
    public List<String> valuesAt(int index) {
        return index < 0 || index >= shownValues.size() ? new ArrayList<String>() : shownValues.get(index);
    }

    /** 第 index 列的过滤框文本；越界返回空串。 */
    public String filterAt(int index) {
        return state.filter(index);
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
        List<String> tags = columns.get(index).availableTags;
        return position < 0 || position >= tags.size() ? "" : tags.get(position);
    }

    /** 该列当前选中的标签，供自检核对。 */
    public List<String> selectedTagsAt(int index) {
        return state.tags(index);
    }

    /**
     * 设置某一列的标签筛选：整列替换，不做叠加。
     *
     * <p>给自检与将来的「记住上次筛选」留出入口；界面上的多选菜单走的就是这个方法，
     * 保证「程序性设置」与「人手点选」最终落到同一处状态。
     */
    public void setTagFilter(int column, List<String> tags) {
        state.setTags(column, tags);
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
        return index < 0 || index >= columnPanels.size() ? null : columnPanels.get(index).filterField();
    }

    /**
     * 按新的列数据整体重绘。
     *
     * <p>过滤关键字按列下标保留：追加节点后重绘时，使用者刚在前一列输入的关键字不该消失。
     */
    public void render(List<ChainColumn> next) {
        rebuilding = true;
        try {
            columns = next == null ? new ArrayList<ChainColumn>() : next;
            state.resize(columns.size());
            strip.removeAll();
            columnPanels.clear();
            shownValues.clear();
            shownIndices.clear();
            for (int index = 0; index < columns.size(); index++) {
                shownIndices.add(new ArrayList<Integer>());
                shownValues.add(new ArrayList<String>());
                ChainColumnPanel panel = new ChainColumnPanel(index, columns.get(index), this);
                columnPanels.add(panel);
                applyPanelSize(panel);
                strip.add(panel.panel());
                if (index < columns.size() - 1) {
                    strip.add(Box.createHorizontalStrut(ChainSelectorSizing.COLUMN_GAP));
                }
            }
            // 列建好后再算一次：首帧容器宽度为 0，只有拿到真实宽度才能定出列宽
            applyColumnWidth();
            repaintColumns();
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

    // ------------------------------------------------------------------
    // ChainColumnPanel.Host：一列面板要读的状态与要发的回调
    // ------------------------------------------------------------------

    @Override
    public void select(int column, String value) {
        choose(column, value);
    }

    @Override
    public ChainColumnState state() {
        return state;
    }

    @Override
    public UiKit.FontSink fonts() {
        return fonts;
    }

    @Override
    public List<List<Integer>> shownIndices() {
        return shownIndices;
    }

    @Override
    public int rowWidth() {
        return Math.max(80, columnWidth - 46);
    }

    @Override
    public int columnWidth() {
        return columnWidth;
    }

    @Override
    public boolean rebuilding() {
        return rebuilding;
    }

    @Override
    public void refreshColumn() {
        repaintColumns();
    }

    @Override
    public void openTagMenu(JComponent anchor, int index, ChainColumn data) {
        ChainTagMenu.show(anchor, index, data, this);
    }

    // ------------------------------------------------------------------
    // ChainTagMenu.Handler：标签菜单的回写
    // ------------------------------------------------------------------

    /** 菜单回写：已选标签直接交给菜单增删，匹配方式与刷新走这里。 */
    @Override
    public List<String> chosen(int index) {
        return state.chosenTags(index);
    }

    @Override
    public boolean isIntersect(int index) {
        return state.intersect(index);
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

    /** 第 index 列的标签是否按交集匹配；越界返回 false（并集）。 */
    public boolean tagMatchIsIntersect(int index) {
        return state.intersect(index);
    }

    /** 设置第 index 列的标签匹配方式；true 为交集。 */
    public void setTagMatch(int index, boolean intersect) {
        if (!state.setIntersect(index, intersect)) return;
        resetColumnViews(index);
        repaintColumns();
    }

    /** 重绘后按钮文字与计数要跟着更新；单个列的按钮按列下标取。 */
    private void resetColumnViews(int index) {
        if (index >= 0 && index < columnPanels.size()) {
            columnPanels.get(index).tagButton().setText(state.tagSummary(index));
        }
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
            for (int index = 0; index < Math.min(columnPanels.size(), columns.size()); index++) {
                ChainColumn data = columns.get(index);
                List<Integer> rows = state.rows(index, data);
                shownValues.set(index, valuesOf(data, rows));
                shownIndices.set(index, rows);
                DefaultListModel<String> model = new DefaultListModel<String>();
                fill(model, data, rows);
                JList<String> list = columnPanels.get(index).list();
                list.setModel(model);
                int selectedRow = rows.indexOf(Integer.valueOf(
                        ChainColumnFilter.indexOf(data.values, data.selected)));
                if (selectedRow >= 0) list.setSelectedIndex(selectedRow);
                // 计数显示的是「当前渲染出的项数」：过滤后还要看到真实候选数就没意义了，
                // 使用者要判断的正是「过滤后还剩几条可选」
                columnPanels.get(index).countLabel().setText(String.valueOf(rows.size()));
                columnPanels.get(index).tagButton().setText(state.tagSummary(index));
            }
        } finally {
            rebuilding = false;
        }
    }

    private static void fill(DefaultListModel<String> model, ChainColumn data, List<Integer> rows) {
        for (int position = 0; position < rows.size(); position++) {
            model.addElement(ChainColumnFilter.label(data, rows.get(position).intValue()));
        }
    }

    private static List<String> valuesOf(ChainColumn data, List<Integer> rows) {
        List<String> values = new ArrayList<String>();
        for (int position = 0; position < rows.size(); position++) {
            values.add(data.values.get(rows.get(position).intValue()));
        }
        return values;
    }
}
