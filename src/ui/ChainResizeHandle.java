package ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;

/**
 * 列式选链的竖向拖拽条：往上拖把候选列表调高，双击在默认档与展开档之间切换。
 *
 * <p>对应网页版的 {@code .chain-columns-resize-handle}（10px 高、圆角胶囊、上下拖动）
 * 与它右侧的展开按钮（双击等价）。网页版把「拖」和「双击切换」放在同一条上，
 * 这里保持一致：同一个控件既能拖又能双击，少一个按钮少一处歧义。
 *
 * <p>拖拽只上报位移量，不自己改高度：高度是 {@link PayloadChainSelector} 的状态，
 * 由它统一收敛到 [最小, 最大] 并把变化量转给外层分栏，避免两处各算一套。
 */
final class ChainResizeHandle extends JComponent {

    /** 拖拽与双击的回调：正数表示列表应当变高。 */
    interface Listener {
        void dragged(int delta);

        void toggled();
    }

    private final Listener listener;
    private int startY;
    private boolean dragging;
    private boolean hover;

    ChainResizeHandle(Listener listener) {
        this.listener = listener;
        setPreferredSize(new Dimension(0, ChainSelectorSizing.HANDLE_HEIGHT));
        setMinimumSize(new Dimension(0, ChainSelectorSizing.HANDLE_HEIGHT));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, ChainSelectorSizing.HANDLE_HEIGHT));
        setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
        setToolTipText("上下拖动调整候选列表高度，双击在默认与展开之间切换");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                startY = event.getY();
                dragging = false;
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                dragging = false;
                repaint();
            }

            @Override
            public void mouseEntered(MouseEvent event) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hover = false;
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && ChainResizeHandle.this.listener != null) {
                    ChainResizeHandle.this.listener.toggled();
                }
            }
        });
        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent event) {
                // 拖拽期间不触发双击，否则「按住拖两下」会被当成双击切换
                if (event.getClickCount() > 1) return;
                int delta = startY - event.getY();
                if (delta == 0) return;
                startY = event.getY();
                dragging = true;
                repaint();
                if (ChainResizeHandle.this.listener != null) ChainResizeHandle.this.listener.dragged(delta);
            }
        });
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int barWidth = Math.max(60, getWidth() - 24);
            int x = (getWidth() - barWidth) / 2;
            int y = Math.max(2, getHeight() / 2 - 4);
            g.setColor(hover || dragging ? UiKit.ACCENT : UiKit.BORDER);
            g.fillRoundRect(x, y, barWidth, 8, 8, 8);
            // 中间的短横线：与网页版 handle 的 ::after 一致，给出「这里能拖」的暗示
            int grip = 40;
            g.setColor(hover || dragging ? Color.WHITE : UiKit.MUTED);
            g.fillRoundRect((getWidth() - grip) / 2, y + 3, grip, 2, 2, 2);
        } finally {
            g.dispose();
        }
    }
}
