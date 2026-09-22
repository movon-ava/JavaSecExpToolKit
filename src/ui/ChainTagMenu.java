package ui;

import java.util.List;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;

/**
 * 标签筛选菜单：多选标签、选择匹配方式（并集 / 交集）、清除筛选。
 *
 * <p>从 {@link PayloadChainSelector} 拆出来独立成类，原因是这一块是纯粹的「弹出菜单并回写选择」，
 * 与列构建、渲染、事件处理无关。菜单需要改动的状态仍在选择器里，通过 {@link Handler} 回写，
 * 避免两处各存一份筛选状态。
 */
final class ChainTagMenu {

    /** 选择器侧的状态读写：菜单只负责界面，不自己存标签与匹配方式。 */
    interface Handler {
        /** 第 index 列已选中的标签，可直接增删。 */
        List<String> chosen(int index);

        /** 第 index 列是否按交集匹配。 */
        boolean isIntersect(int index);

        /** 设置第 index 列的匹配方式。 */
        void apply(int index, boolean intersect);

        /** 选择变化后刷新该列的按钮文字与列表。 */
        void refresh(int index);
    }

    private ChainTagMenu() {
    }

    static void show(javax.swing.JComponent anchor, final int index, ChainColumn data,
                     final Handler handler) {
        final javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        if (data.availableTags.isEmpty()) {
            javax.swing.JMenuItem empty = new javax.swing.JMenuItem("本列候选没有标签");
            empty.setEnabled(false);
            menu.add(empty);
        } else {
            final List<String> chosen = handler.chosen(index);
            for (final String tag : data.availableTags) {
                final javax.swing.JCheckBoxMenuItem item = new javax.swing.JCheckBoxMenuItem(tag, chosen.contains(tag));
                item.addActionListener(event -> {
                    if (item.isSelected()) {
                        if (!chosen.contains(tag)) chosen.add(tag);
                    } else {
                        chosen.remove(tag);
                    }
                    handler.refresh(index);
                });
                menu.add(item);
            }
            menu.addSeparator();
            final javax.swing.ButtonGroup modes = new javax.swing.ButtonGroup();
            javax.swing.JRadioButtonMenuItem union = new javax.swing.JRadioButtonMenuItem(
                    "并集：命中任一选中标签即可", !handler.isIntersect(index));
            javax.swing.JRadioButtonMenuItem intersect = new javax.swing.JRadioButtonMenuItem(
                    "交集：必须同时包含全部选中标签", handler.isIntersect(index));
            modes.add(union);
            modes.add(intersect);
            union.addActionListener(event -> handler.apply(index, false));
            intersect.addActionListener(event -> handler.apply(index, true));
            menu.add(union);
            menu.add(intersect);
            menu.addSeparator();
            javax.swing.JMenuItem clear = new javax.swing.JMenuItem("清除标签筛选");
            clear.addActionListener(event -> {
                chosen.clear();
                handler.refresh(index);
            });
            menu.add(clear);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }
}
