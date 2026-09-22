package ui;

import java.awt.Component;
import java.awt.Font;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JLabel;
import javax.swing.JList;

/**
 * 列内条目渲染：显示名 + 右侧标签徽标 + 末端标记。
 *
 * <p>与网页版每个候选右侧的 Tag 一致：使用者挑节点时看的是「这个节点属于哪一类」，
 * 只给一个名字无法区分同名不同链的节点。末端节点额外给出 END 标记——它之后的列不会展开。
 *
 * <p>从 {@link PayloadChainSelector} 拆出来独立成类，是因为它只做「一条候选画成什么样子」，
 * 与选择器的列构建、过滤与事件处理无关；渲染所需的映射由构造参数传入。
 */
final class ChainNodeRenderer extends DefaultListCellRenderer {

    private final PayloadChainSelector.Column data;
    private final int column;
    private final UiKit.FontSink fonts;
    /** 每列当前渲染出的项在原始候选里的下标：标签与末端标记按原始下标取。 */
    private final List<List<Integer>> shownIndices;

    ChainNodeRenderer(PayloadChainSelector.Column data, int column, UiKit.FontSink fonts,
                      List<List<Integer>> shownIndices) {
        this.data = data;
        this.column = column;
        this.fonts = fonts;
        this.shownIndices = shownIndices;
    }

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                  boolean selected, boolean focused) {
        JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
        label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        int original = originalIndex(index);
        String identifier = original >= 0 ? data.values.get(original) : String.valueOf(value);
        label.setToolTipText(identifier);
        String tags = original < 0 ? "" : data.tagAt(original);
        boolean end = original >= 0 && data.endAt(original);
        String identifierText = String.valueOf(value);
        if (!tags.isEmpty()) label.setText(identifierText + "  \u00b7 " + tags);
        if (end) label.setText(label.getText() + "  END");
        if (!selected) {
            label.setForeground(end ? UiKit.ACCENT : UiKit.BODY_TEXT);
        }
        fonts.track(label, Font.PLAIN, 13);
        return label;
    }

    /** 列表第 row 行对应的原始候选下标；越界返回 -1，渲染时回退成不带徽标。 */
    private int originalIndex(int row) {
        if (row < 0 || column < 0 || column >= shownIndices.size()) return -1;
        List<Integer> rows = shownIndices.get(column);
        return row < rows.size() ? rows.get(row).intValue() : -1;
    }
}
