package ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/**
 * 启动画面：初始化期间把「正在做什么」显示出来。
 *
 * <p>启动期的初始化合计约一到两秒（java-chains 节点表最重），这段时间主窗口还没建好。
 * 没有回显的话，使用者看到的是「双击图标之后什么都不发生」，会误以为程序没启动。
 * 这里用一块无边框小窗显示当前步骤与一条不确定进度条，初始化结束由 {@link #close()} 收掉。
 *
 * <p>无图形环境（自检、CI）下 {@link #show()} 直接返回，不会因为画不出窗口而失败。
 */
public final class StartupSplash implements StartupWarmup.View {

    private static final int WIDTH = 440;
    private static final int HEIGHT = 152;

    private final JWindow window = new JWindow();
    private final JLabel stepLabel = new JLabel("正在准备运行环境…");
    private final JProgressBar progress = new JProgressBar();

    public StartupSplash() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.BORDER),
                BorderFactory.createEmptyBorder(22, 26, 22, 26)));

        card.add(line("JAVASEC EXP TOOLKIT", Font.BOLD, 11, UiKit.ACCENT, 0));
        card.add(line("正在初始化", Font.BOLD, 20, UiKit.TEXT, 6));

        stepLabel.setForeground(UiKit.MUTED);
        stepLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        stepLabel.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
        stepLabel.setAlignmentX(0.0f);
        card.add(stepLabel);

        progress.setIndeterminate(true);
        progress.setBorderPainted(false);
        progress.setPreferredSize(new Dimension(WIDTH - 52, 6));
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        progress.setAlignmentX(0.0f);
        card.add(progress);

        window.setContentPane(card);
        window.setSize(WIDTH, HEIGHT);
        window.setAlwaysOnTop(true);
        // 初始化期间不让启动画面抢焦点：否则主窗口起来后焦点还留在这一块上
        window.setFocusableWindowState(false);
    }

    private static JLabel line(String text, int style, int size, Color color, int topGap) {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(new Font(Font.SANS_SERIF, style, size));
        label.setBorder(BorderFactory.createEmptyBorder(topGap, 0, 0, 0));
        label.setAlignmentX(0.0f);
        return label;
    }

    /** 居中显示；无图形环境时静默跳过。 */
    public void show() {
        if (GraphicsEnvironment.isHeadless()) return;
        window.setLocationRelativeTo(null);
        window.setVisible(true);
    }

    /** 初始化进度回显；调用方在后台线程，这里切回事件分发线程改文字。 */
    @Override
    public void step(String text) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                stepLabel.setText(text);
            }
        });
    }

    /** 收掉启动画面。 */
    public void close() {
        window.setVisible(false);
        window.dispose();
    }
}
