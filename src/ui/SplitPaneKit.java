package ui;

import java.awt.Dimension;
import javax.swing.JComponent;
import javax.swing.JSplitPane;

/**
 * 按比例摆放分隔线的分栏：建好时尺寸还是 0，必须等拿到真实尺寸再定比例。
 *
 * <p>从 {@link PayloadPanels} 拆出来独立成类，原因是这一段是通用布局逻辑
 * （载荷页的上下分栏、控制台三栏都用它），与「载荷页有哪些块」无关；
 * 它也是踩过坑最多的一段，单独放着才好把三条实测结论写清楚。
 */
final class SplitPaneKit {

    private SplitPaneKit() {
    }

    /**
     * 一个可拖拽的分栏：第一块占 {@code weight}，第二块吃掉其余空间。
     *
     * <p>比例不能在建好时用 {@code setDividerLocation(double)} 定：那时分栏还没有尺寸，
     * Swing 会按「当前尺寸 × 比例」算成 0，比例因此完全不生效（实测踩到：三栏宽度按各自
     * 首选尺寸乱分，OUTPUT 吃掉大半、CONTEXT 被压成一条）。
     *
     * <p>返回的 {@link RatioSplitPane} 在每次 {@code doLayout()} 里按当前尺寸重算分割线，
     * 既避开「建好时尺寸为 0」，也避开「靠异步组件事件补算」留下的空窗。
     */
    static JSplitPane splitPane(int orientation, JComponent first, JComponent second,
                                final double weight, int divider) {
        return new RatioSplitPane(orientation, first, second, weight, divider);
    }

    /**
     * 把分割线挪到指定位置，并让比例就此失效。
     *
     * <p>选链区的拖拽条改的是「列表多高」，多出来的高度要转成上下分栏的分割线位置：
     * 不先锁住比例的话，下一次布局会立刻按比例把分割线拽回原位，使用者看到的是「拖了没反应」。
     */
    static void setDivider(JSplitPane split, int location) {
        if (split instanceof RatioSplitPane) {
            ((RatioSplitPane) split).lock();
        }
        split.setDividerLocation(location);
    }

    /** 只固定高度：宽度交给布局拉伸，与整块分栏配合使用。 */
    static void setFixedHeight(JComponent component, int height) {
        component.setPreferredSize(new Dimension(component.getPreferredSize().width, height));
        component.setMinimumSize(new Dimension(0, height));
    }

    /**
     * 固定高度并允许横向铺满。
     *
     * <p>盒式布局按最大尺寸决定能给多少宽度，而 {@code JComponent} 未显式设置时
     * 最大尺寸等于首选尺寸：只设首选高度会让组件停在首选宽度上，一排按钮因此被裁掉最后一个
     * （实测踩到：OUTPUT 行的「展开」只剩「展…」）。
     */
    static void stretch(JComponent component, int height) {
        setFixedHeight(component, height);
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }
}
