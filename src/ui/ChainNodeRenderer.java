package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;

/**
 * 列内条目渲染：显示名 + 右侧等宽标识徽标 + 末端徽标。
 *
 * <p>与网页版 {@code .chain-option} 的行内结构一致：左侧是 {@code .option-name}
 * （占满剩余宽度、超出截断），右侧是 {@code .option-id-tag}（等宽字体、浅色底）
 * 与 END 徽标。只给显示名无法区分同名不同链的节点，标识才是链上真正用的值。
 *
 * <p>从 {@link PayloadChainSelector} 拆出来独立成类，是因为它只做「一条候选画成什么样子」，
 * 与选择器的列构建、过滤与事件处理无关；渲染所需的映射由构造参数传入。
 *
 * <p>行宽由选择器在布局后回填：列宽会随窗口与列数变化，渲染时再按当前宽度算名字能占多少，
 * 否则名字会把右侧徽标挤出可视区。
 */
final class ChainNodeRenderer implements ListCellRenderer<String> {

    private final ChainColumn data;
    private final int column;
    private final UiKit.FontSink fonts;
    /** 每列当前渲染出的项在原始候选里的下标：标签与末端标记按原始下标取。 */
    private final List<List<Integer>> shownIndices;
    /** 行宽提供者：返回当前列内容区的可用宽度（不含内边距）。 */
    private final java.util.function.IntSupplier rowWidth;

    ChainNodeRenderer(ChainColumn data, int column, UiKit.FontSink fonts,
                      List<List<Integer>> shownIndices, java.util.function.IntSupplier rowWidth) {
        this.data = data;
        this.column = column;
        this.fonts = fonts;
        this.shownIndices = shownIndices;
        this.rowWidth = rowWidth;
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends String> list, String value, int index,
                                                  boolean selected, boolean focused) {
        int original = originalIndex(index);
        String identifier = original >= 0 ? data.values.get(original) : String.valueOf(value);
        String tags = original < 0 ? "" : data.tagAt(original);
        boolean end = original >= 0 && data.endAt(original);

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(true);
        row.setBackground(selected ? list.getSelectionBackground() : Color.WHITE);
        // 行底细线 + 内边距：与网页版 .chain-option 的 border-bottom 一致，
        // 长列表里靠分隔线比靠斑马纹更容易扫读
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiKit.BORDER),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        row.setToolTipText(identifier + (tags.isEmpty() ? "" : "  \u00b7 " + tags));

        JLabel name = new JLabel(String.valueOf(value));
        name.setForeground(selected ? list.getSelectionForeground()
                : (end ? UiKit.ACCENT : UiKit.BODY_TEXT));
        fonts.track(name, Font.PLAIN, 13);

        JPanel badges = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        badges.setOpaque(false);
        badges.add(identifierTag(identifier, selected));
        if (end) badges.add(endTag(selected));

        int width = rowWidth == null ? 0 : rowWidth.getAsInt();
        int badgeWidth = badges.getPreferredSize().width;
        // 名字占满剩余宽度：先按可用宽度给首选宽度，再让标签自己截断成省略号。
        // 留 10px 余量，避免四舍五入后刚好差一像素又把徽标挤出去。
        int nameWidth = Math.max(60, width - badgeWidth - 10);
        name.setPreferredSize(new Dimension(nameWidth, ChainSelectorSizing.ROW_HEIGHT - 6));
        row.add(name, BorderLayout.CENTER);
        row.add(badges, BorderLayout.EAST);
        row.setPreferredSize(new Dimension(Math.max(80, width), ChainSelectorSizing.ROW_HEIGHT));
        return row;
    }

    /** 标识徽标：等宽字体、浅底，与网页版 .option-id-tag 同形。 */
    private JLabel identifierTag(String identifier, boolean selected) {
        JLabel tag = new JLabel(identifier);
        tag.setOpaque(true);
        tag.setBackground(selected ? Color.WHITE : UiKit.SUBTLE);
        tag.setForeground(UiKit.MUTED);
        tag.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.BORDER),
                BorderFactory.createEmptyBorder(1, 6, 1, 6)));
        tag.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        fonts.track(tag, Font.PLAIN, 11);
        return tag;
    }

    /** 末端徽标：带 END 标签的节点之后没有可接节点。 */
    private JLabel endTag(boolean selected) {
        JLabel tag = new JLabel("END", SwingConstants.CENTER);
        tag.setOpaque(true);
        tag.setBackground(selected ? Color.WHITE : UiKit.ACCENT);
        tag.setForeground(selected ? UiKit.ACCENT : Color.WHITE);
        tag.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
        fonts.track(tag, Font.BOLD, 10);
        return tag;
    }

    /** 列表第 row 行对应的原始候选下标；越界返回 -1，渲染时回退成不带徽标。 */
    private int originalIndex(int row) {
        if (row < 0 || column < 0 || column >= shownIndices.size()) return -1;
        List<Integer> rows = shownIndices.get(column);
        return row < rows.size() ? rows.get(row).intValue() : -1;
    }
}
