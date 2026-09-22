package ui;

import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JSplitPane;

/**
 * 按比例摆放分割线的分栏。
 *
 * <p>比例必须在「布局那一刻」生效，不能等 {@code componentResized} 事件排到队首再补：
 * 组件事件是异步投递的，布局结束到事件被处理之间存在一个空窗，此时分割线还停在上一次尺寸
 * 算出来的位置上。使用者拖窗口时看到的是条抖动的分割线，自检则更直接——在同一个构建上
 * 能读到两个不同的值（实测输出区 101px 与 133px，差的就是这个空窗）。
 *
 * <p>因此这里把比例校正放进 {@link #doLayout()}：每次布局先按当前尺寸摆好分割线，
 * 布局一结束位置就是对的，不依赖任何后续事件。
 *
 * <p>比例只在用户没有手动调过时生效。一旦用户拖过分栏（或拖过选链区那条拖拽条、
 * 由外层调用 {@link #lock()}），比例立即失效，不再把使用者调好的位置拽回去。
 */
final class RatioSplitPane extends JSplitPane {

    /** 第一块占的比例（0~1）。 */
    private final double weight;
    /** 用户是否已手动调过：调过之后比例不再生效。 */
    private boolean locked;

    RatioSplitPane(int orientation, Component first, Component second, double weight, int divider) {
        super(orientation, first, second);
        this.weight = weight;
        setResizeWeight(weight);
        setDividerSize(divider);
        setContinuousLayout(true);
        setBorder(null);
        setOpaque(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (nearDivider(event.getX(), event.getY())) locked = true;
            }
        });
    }

    /** 外部把分割线调走（例如选链区的拖拽条改高度）时调用：比例就此失效。 */
    void lock() {
        locked = true;
    }

    boolean isLocked() {
        return locked;
    }

    /**
     * 布局前先按当前尺寸摆好分割线。
     *
     * <p>{@code setDividerLocation} 会触发 {@code revalidate()}，而这里正处于布局过程中，
     * 重入一次布局既无意义又可能来回振荡，因此用一个标志把重入挡在外面。
     */
    @Override
    public void doLayout() {
        if (!locked && !applying) {
            int size = getOrientation() == VERTICAL_SPLIT ? getHeight() : getWidth();
            int target = (int) Math.round(weight * (size - getDividerSize()));
            if (target > 0 && getDividerLocation() != target) {
                applying = true;
                try {
                    setDividerLocation(target);
                } finally {
                    applying = false;
                }
            }
        }
        super.doLayout();
    }

    /** 防止 {@code setDividerLocation} 触发的重新布局再次进来。 */
    private boolean applying;

    /** 鼠标按在这个点上是否落在分割线上（含两侧各 3px 的容差）。 */
    private boolean nearDivider(int x, int y) {
        int location = getDividerLocation();
        int size = getDividerSize();
        int position = getOrientation() == VERTICAL_SPLIT ? y : x;
        return position >= location - 3 && position <= location + size + 3;
    }
}
