package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

/**
 * 一列的面板：列头（标题 + 计数 + 过滤框 + 标签按钮）与候选列表。
 *
 * <p>从 {@link PayloadChainSelector} 拆出来独立成类，原因是「一列长什么样」与
 * 「有哪些列、链怎么变」是两件事：选择器负责后者（列数、选中项、过滤结果），
 * 本类只负责前者（把一列的数据与状态渲染成控件，并把点击 / 输入转成回调）。
 * 选择器因此只剩列调度与对外契约，单列内部的布局调整也不会再动到它。
 *
 * <p>行内渲染交给 {@link ChainNodeRenderer}，过滤判定交给 {@link ChainColumnFilter}，
 * 筛选状态存在 {@link ChainColumnState} 里：本类只做装配，不自己算任何结果。
 */
final class ChainColumnPanel {

    /** 选择器侧的能力：本类只读状态、只回调，不直接改链。 */
    interface Host {
        ChainColumnState state();

        UiKit.FontSink fonts();

        /** 每列当前渲染出的项在原始候选里的下标，按列共享一份。 */
        List<List<Integer>> shownIndices();

        /** 一行内容区的可用宽度。 */
        int rowWidth();

        /** 当前候选列表高度。 */
        int listHeight();

        /** 当前列宽。 */
        int columnWidth();

        /** 是否开启悬停选链。 */
        boolean hoverSelect();

        /** 是否正在重建列结构：重建期间程序性选中不能被当成使用者点击。 */
        boolean rebuilding();

        /** 选中某列的某项：链的变化由选择器转给调用方。 */
        void select(int column, String value);

        /** 过滤条件变化后重绘各列内容。 */
        void refreshColumn();

        /** 弹出某列的标签筛选菜单。 */
        void openTagMenu(JComponent anchor, int index, ChainColumn data);
    }

    private final JPanel panel = new JPanel(new BorderLayout(0, 6));
    private final JList<String> list = new JList<String>(new DefaultListModel<String>());
    private final JTextField filter;
    private final JLabel count;
    private final JToggleButton tagButton;
    private final JScrollPane listScroll;

    ChainColumnPanel(final int index, final ChainColumn data, final Host host) {
        final UiKit.FontSink fonts = host.fonts();
        panel.setBackground(Color.WHITE);
        // 列尺寸由选择器在拿到真实容器宽度后统一套用（见 PayloadChainSelector#applyColumnWidth）：
        // 这里再设一次只会被覆盖，且首帧宽度为 0，设了也是错的
        // 列框与网页版 .chain-column 一致：细边框 + 稍紧的内边距，
        // 让候选行尽量贴近列宽，行内的省略号才发生在该发生的地方
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.BORDER),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));

        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setBackground(Color.WHITE);
        JPanel titleRow = new JPanel(new BorderLayout(8, 0));
        titleRow.setBackground(Color.WHITE);
        titleRow.add(UiKit.label(data.title, Font.BOLD, 13, UiKit.TEXT, fonts), BorderLayout.WEST);
        // 候选计数：与网页版每个 Gadget 列头右侧的数字一致，先看总数再决定要不要过滤
        count = UiKit.label(String.valueOf(data.values.size()), Font.BOLD, 12, UiKit.MUTED, fonts);
        count.setToolTipText("本列候选数（受标签与关键字过滤影响）");
        titleRow.add(count, BorderLayout.EAST);
        head.add(titleRow, BorderLayout.NORTH);

        filter = new JTextField(host.state().filter(index));
        UiKit.styleField(filter, fonts);
        filter.setToolTipText("按节点名称或标识过滤本列候选；当前已选中的节点不会被过滤掉");

        // 过滤框与标签筛选器同一行：竖着排要多占一行高度，列内的候选列表就少一行，
        // 而这两者本来就是「缩小候选范围」的同一类动作，并排更符合使用顺序
        tagButton = new JToggleButton(host.state().tagSummary(index));
        tagButton.setForeground(UiKit.TEXT);
        tagButton.setBackground(Color.WHITE);
        tagButton.setFocusPainted(false);
        tagButton.setToolTipText("按上游标签筛选本列候选");
        tagButton.setMargin(new java.awt.Insets(0, 0, 0, 0));
        tagButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.FIELD_BORDER),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        fonts.track(tagButton, Font.PLAIN, 12);
        tagButton.addActionListener(event -> host.openTagMenu(tagButton, index, data));

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

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Color.WHITE);
        list.setForeground(UiKit.BODY_TEXT);
        list.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        list.setCellRenderer(new ChainNodeRenderer(data, index, fonts, host.shownIndices(),
                new java.util.function.IntSupplier() {
                    @Override
                    public int getAsInt() {
                        return host.rowWidth();
                    }
                }));
        fonts.track(list, Font.PLAIN, 13);
        // 固定单元格宽度：列内因此不会出现横向滚动条，过长的显示名由标签自行截断成省略号
        list.setFixedCellWidth(host.rowWidth());
        list.setFixedCellHeight(ChainSelectorSizing.ROW_HEIGHT);

        list.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent event) {
                if (event.getValueIsAdjusting() || host.rebuilding()) return;
                int row = list.getSelectedIndex();
                List<Integer> rows = host.state().rows(index, data);
                if (row < 0 || row >= rows.size()) return;
                host.select(index, data.values.get(rows.get(row).intValue()));
            }
        });
        list.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent event) {
                if (!host.hoverSelect() || host.rebuilding()) return;
                int row = list.locationToIndex(event.getPoint());
                List<Integer> rows = host.state().rows(index, data);
                if (row < 0 || row >= rows.size()) return;
                host.select(index, data.values.get(rows.get(row).intValue()));
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
                String text = filter.getText() == null ? "" : filter.getText().trim();
                if (text.equals(host.state().filter(index))) return;
                host.state().setFilter(index, text);
                host.refreshColumn();
            }
        });

        listScroll = new JScrollPane(list);
        listScroll.setBorder(BorderFactory.createLineBorder(UiKit.BORDER));
        listScroll.getViewport().setBackground(Color.WHITE);
        listScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        listScroll.setPreferredSize(new Dimension(0, host.listHeight()));
        listScroll.setMinimumSize(new Dimension(0, Math.min(host.listHeight(), 60)));
        panel.add(listScroll, BorderLayout.CENTER);
    }

    JPanel panel() {
        return panel;
    }

    JList<String> list() {
        return list;
    }

    JTextField filterField() {
        return filter;
    }

    JLabel countLabel() {
        return count;
    }

    JToggleButton tagButton() {
        return tagButton;
    }

    JScrollPane listScroll() {
        return listScroll;
    }
}
