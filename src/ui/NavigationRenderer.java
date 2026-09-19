package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;

/**
 * 侧边栏渲染：文字靠左，展开箭头固定在整行的最右侧。
 *
 * <p>用「面板 + 两个标签」而不是拼接字符串，是为了让箭头对齐到行尾，
 * 不随文字长度漂移。
 */
public final class NavigationRenderer implements ListCellRenderer<NavItem> {

    private final JPanel row = new JPanel(new BorderLayout());
    private final JLabel caption = new JLabel();
    private final JLabel arrow = new JLabel();

    public NavigationRenderer() {
        row.setOpaque(true);
        caption.setOpaque(false);
        arrow.setOpaque(false);
        row.add(caption, BorderLayout.CENTER);
        row.add(arrow, BorderLayout.EAST);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends NavItem> list, NavItem item,
                                                  int index, boolean selected, boolean focus) {
        Font base = list.getFont() == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 15) : list.getFont();
        boolean group = item != null && item.hasChildren();
        row.setBorder(BorderFactory.createEmptyBorder(0, item != null && item.isChild() ? 34 : 14, 0, 14));
        row.setBackground(selected ? UiKit.SIDEBAR_ACTIVE : UiKit.SIDEBAR);
        Color color = selected ? Color.WHITE
                : item != null && item.isChild() ? UiKit.CHILD_NAV : UiKit.NAV_TEXT;
        caption.setForeground(color);
        arrow.setForeground(color);
        caption.setFont(group ? base.deriveFont(Font.BOLD) : base);
        arrow.setFont(base);
        caption.setText(item == null ? "" : item.label);
        arrow.setText(group ? (item.expanded ? "\u25be" : "\u25b8") : "");
        return row;
    }
}

