package ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

import analyze.AnalysisContext;

/**
 * 「由分析结论带入」交接条：显示当前这页是顺着哪条分析结论过来的。
 *
 * <p>为什么需要它：分析功能的价值在于把结论接上利用动作，但如果跳过来之后
 * 界面什么都不说，使用者就得自己回想「刚才报告里的哪一条指向这里」，
 * 也就无从判断该不该按这条建议继续。
 *
 * <p>刻意只做展示与回跳，**不预填任何参数**：类名、常量、字符串都是推测性信息，
 * 自动塞进命令、回连地址、目标 URL 会让使用者点一下生成就等于按推测向目标发包。
 * 因此这里只有一段说明文字、一个「查看依据」（悬停提示）与一个「返回分析结论」。
 *
 * <p>没有上下文时整条隐藏：空条占着位置会让页面看起来少了一块。
 */
public final class AnalysisContextBar {

    private final JPanel panel = new JPanel(new BorderLayout(12, 0));
    private final JLabel label = new JLabel();
    private final JButton back = new JButton("返回分析结论");
    private java.util.function.Consumer<String> navigator;
    /** 最近一次上下文对应的来源页：回跳按钮用它。与 show 一起更新，保证不会指错页。 */
    private String originKey = "";
    /**
     * 最近一次显示的上下文。
     *
     * <p>留一份引用是为了「同一份上下文待久了要会自己变旧」：横幅是跨页常驻的，
     * 使用者可能十分钟后才回到利用页。那时横幅上写的还是「刚刚的分析结论」，
     * 需要重新渲染一次把时间差表达出来。
     */
    private AnalysisContext current;
    /**
     * 超过这个分钟数就在横幅上标出「本次结论已生成多久」。
     *
     * <p>30 分钟是个经验值：一次授权验证的节奏通常在这个量级，
     * 超过之后「刚才那次分析的结论」开始需要重新确认，而不是直接照着做。
     */
    private static final long STALE_MINUTES = 30;

    public AnalysisContextBar(UiKit.FontSink fonts) {
        panel.setBackground(UiKit.SUBTLE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiKit.FIELD_BORDER),
                BorderFactory.createEmptyBorder(8, 16, 8, 16)));
        label.setForeground(UiKit.MUTED);
        fonts.track(label, Font.PLAIN, 12);
        panel.add(label, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        UiKit.styleSecondaryButton(back, fonts);
        back.addActionListener(event -> goBack());
        right.add(back);
        panel.add(right, BorderLayout.EAST);
        panel.setVisible(false);
    }

    /** 交接条本体，由装配层放进内容区顶部。 */
    public JPanel component() {
        return panel;
    }

    /** 回跳目标：点击「返回分析结论」时打开的功能页 key。 */
    public void setNavigator(java.util.function.Consumer<String> navigator) {
        this.navigator = navigator;
    }

    /**
     * 显示一条交接上下文。
     *
     * @param originKey 产生该结论的功能页 key；为空时隐藏「返回」按钮
     */
    public void show(AnalysisContext context, String originKey) {
        if (context == null) {
            clear();
            return;
        }
        this.originKey = originKey == null ? "" : originKey;
        this.current = context;
        label.setText(context.banner());
        // 把完整依据放进悬停提示：正文只留一行，避免把页面顶部的空间吃掉
        label.setToolTipText(html(context.describe()));
        back.setVisible(!this.originKey.isEmpty());
        panel.setVisible(true);
        panel.revalidate();
        panel.repaint();
    }

    /**
     * 按当前时间重新渲染横幅。
     *
     * <p>换页时调用：上下文是「当时那次分析的结论」，放置久了必须能看出来，
     * 否则使用者会拿着半小时前的证据当作刚刚的结论直接下手。
     * 未超过阈值时不加任何提示，避免横幅被无意义的信息占满。
     */
    public void refresh() {
        if (current == null || !panel.isVisible()) return;
        String text = current.banner();
        if (current.olderThanMinutes(STALE_MINUTES)) {
            text = text + "　·　注意：本次分析结论已超过 "
                    + minutesOf(current) + " 分钟，建议重新核对后再沿用";
        }
        label.setText(text);
    }

    /** 上下文生成至今的分钟数，用于提示文案。 */
    private static long minutesOf(AnalysisContext context) {
        long millis = System.currentTimeMillis() - context.generatedAt;
        return Math.max(1L, millis / 60000L);
    }

    /** 清空并隐藏。 */
    public void clear() {
        current = null;
        label.setText("");
        label.setToolTipText(null);
        originKey = "";
        panel.setVisible(false);
        panel.revalidate();
        panel.repaint();
    }

    /** 当前是否显示了上下文；供自检断言。 */
    public boolean isShowing() {
        return panel.isVisible();
    }

    /** 最近一次显示的文本；供自检断言。 */
    public String text() {
        return label.getText() == null ? "" : label.getText();
    }

    private void goBack() {
        if (navigator != null && !originKey.isEmpty()) navigator.accept(originKey);
    }

    private static String html(java.util.List<String> lines) {
        StringBuilder text = new StringBuilder("<html>");
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) text.append("<br>");
            text.append(escape(lines.get(index)));
        }
        return text.append("</html>").toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
