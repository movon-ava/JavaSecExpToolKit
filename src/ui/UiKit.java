package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.LayoutManager;
import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.Border;

/**
 * 界面外观的唯一来源：配色、间距、控件样式都集中在这里。
 *
 * <p>各功能页面只描述自己的结构，不再各自 setFont / setBorder：这样改一次配色
 * 就能影响全部页面，也不会出现「某个页面漏改字号」这类不一致。
 *
 * <p>字号缩放需要按窗口大小重算，因此样式方法都接收一个 {@link FontSink}
 * 回调，由调用方登记需要缩放的组件（界面层持有该清单）。
 */
public final class UiKit {

    public static final Color SIDEBAR = new Color(23, 32, 46);
    public static final Color SIDEBAR_ACTIVE = new Color(43, 62, 84);
    public static final Color ACCENT = new Color(35, 119, 204);
    public static final Color TEXT = new Color(31, 41, 55);
    public static final Color MUTED = new Color(100, 116, 139);
    public static final Color BORDER = new Color(226, 232, 240);
    public static final Color BACKGROUND = new Color(248, 250, 252);
    public static final Color BODY_TEXT = new Color(51, 65, 85);
    public static final Color FIELD_BORDER = new Color(203, 213, 225);
    public static final Color SUBTLE = new Color(241, 245, 249);
    public static final Color CHILD_NAV = new Color(148, 163, 184);
    public static final Color NAV_TEXT = new Color(203, 213, 225);

    /** 基准窗口尺寸：缩放到该比例，让界面在大屏上不至于显得过小。 */
    public static final int BASE_WIDTH = 1440;
    public static final int BASE_HEIGHT = 900;
    /** 侧边栏与导航行的基准尺寸，同样参与缩放。 */
    public static final int SIDEBAR_WIDTH = 250;
    public static final int NAV_ROW_HEIGHT = 48;

    private UiKit() {
    }

    /** 登记需要随窗口缩放的字体。 */
    public interface FontSink {
        void track(javax.swing.JComponent component, int style, int baseSize);
    }

    /** 页面容器：统一的背景色与四周留白。 */
    public static JPanel page() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(BACKGROUND);
        page.setBorder(BorderFactory.createEmptyBorder(42, 52, 42, 52));
        return page;
    }

    /** 页面标题区：左侧眉题 + 大标题，右侧说明文字。 */
    public static JPanel pageHeading(String eyebrow, String title, String scope, FontSink sink) {
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        JPanel headings = new JPanel();
        headings.setOpaque(false);
        headings.setLayout(new BoxLayout(headings, BoxLayout.Y_AXIS));
        headings.add(label(eyebrow, Font.BOLD, 12, ACCENT, sink));
        JLabel headingTitle = label(title, Font.BOLD, 30, TEXT, sink);
        headingTitle.setBorder(BorderFactory.createEmptyBorder(7, 0, 0, 0));
        headings.add(headingTitle);
        heading.add(headings, BorderLayout.WEST);
        if (scope != null) {
            JLabel headingScope = label(scope, Font.PLAIN, 13, MUTED, sink);
            headingScope.setBorder(BorderFactory.createEmptyBorder(20, 0, 0, 0));
            heading.add(headingScope, BorderLayout.EAST);
        }
        return heading;
    }

    /** 卡片容器：白底 + 细边框 + 内边距。 */
    public static JPanel surface(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setBackground(Color.WHITE);
        Border border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER), BorderFactory.createEmptyBorder(22, 24, 22, 24));
        panel.setBorder(border);
        return panel;
    }

    public static JLabel label(String text, int style, int size, Color color, FontSink sink) {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        sink.track(label, style, size);
        return label;
    }

    public static JButton primaryButton(String text, FontSink sink) {
        JButton button = new JButton(text);
        stylePrimaryButton(button, sink);
        return button;
    }

    public static void stylePrimaryButton(JButton button, FontSink sink) {
        button.setForeground(Color.WHITE);
        button.setBackground(ACCENT);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sink.track(button, Font.BOLD, 14);
    }

    public static void styleSecondaryButton(JButton button, FontSink sink) {
        button.setForeground(TEXT);
        button.setBackground(SUBTLE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FIELD_BORDER),
                BorderFactory.createEmptyBorder(9, 16, 9, 16)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sink.track(button, Font.BOLD, 14);
    }

    public static void styleField(JTextField field, FontSink sink) {
        field.setForeground(TEXT);
        field.setBackground(Color.WHITE);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FIELD_BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        sink.track(field, Font.PLAIN, 14);
    }

    public static void styleSwitch(JCheckBox box, FontSink sink) {
        box.setOpaque(false);
        box.setForeground(TEXT);
        box.setFocusPainted(false);
        box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sink.track(box, Font.PLAIN, 14);
    }

    /** 输出区文本框：等宽字体，方便对齐报文。 */
    public static void styleArea(JTextArea area, int rows, FontSink sink) {
        area.setRows(rows);
        styleMonospaceArea(area, sink);
    }

    /** 只读展示区：等宽字体、自动换行、无行数限制。 */
    public static void styleMonospaceArea(JTextArea area, FontSink sink) {
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setForeground(BODY_TEXT);
        area.setBackground(Color.WHITE);
        area.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        sink.track(area, Font.PLAIN, 13);
    }

    /** 给输出区套一层滚动面板。 */
    public static JScrollPane scroll(JTextArea area) {
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        scroll.getViewport().setBackground(Color.WHITE);
        return scroll;
    }

    public static GridBagConstraints constraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    /** 按窗口大小计算缩放系数：限制在 0.85~1.45，避免过大或过小。 */
    public static double scaleFor(int width, int height) {
        double scale = Math.min(width / (double) BASE_WIDTH, height / (double) BASE_HEIGHT);
        return Math.max(0.85, Math.min(1.45, scale));
    }

    public static Dimension scaledSidebar(double scale) {
        return new Dimension((int) Math.round(SIDEBAR_WIDTH * scale), 0);
    }

    public static int scaledNavRowHeight(double scale) {
        return (int) Math.round(NAV_ROW_HEIGHT * scale);
    }
}

