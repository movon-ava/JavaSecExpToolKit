package ui;

import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;

/**
 * 会按宽度折行的状态标签：文字超出可用宽度时换行显示，而不是被截成省略号。
 *
 * <p>窄栏里的状态文字经常比栏宽还长（Payload 页 OUTPUT 栏只有三百来像素，
 * 「已选载体 blazedsamf3ampayload，请在下方列中继续选择节点。」实测需要 438px），
 * 普通 JLabel 会把它截成「已选载体 blazedsamf3ampay…」，使用者看不到结论。
 *
 * <p>不用 HTML 标签来实现折行，是因为那会改掉 {@code getText()} 的返回值：
 * 上游按原始文本做判断（自检里就有「状态栏以『生成成功』开头」这类断言），
 * 包装过的文本会让这些判断全部落空。这里让 {@code getText()} 保持原样，
 * 只在自己的 {@link #paintComponent} 里按当前宽度画折行文本。
 *
 * <p>折行宽度取控件当前宽度（首帧为 0 时回落到父容器宽度），因此行数只取决于宽度、
 * 不取决于高度，不会出现「越高越折、越折越高」的抖动；宽度变化后重新校验一次即可。
 */
final class WrappedLabel extends JLabel {

    /** 最多折几行：再多会把窄栏里的其它内容挤没，超出部分截断加省略号。 */
    private static final int MAX_LINES = 3;

    WrappedLabel(String text) {
        super(text == null ? "" : text);
        setOpaque(false);
        // 宽度变化后行数可能变，必须重新问一次首选高度，否则会停在旧行数上
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                revalidate();
            }
        });
    }

    @Override
    public void setText(String text) {
        super.setText(text == null ? "" : text);
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        List<String> lines = wrap(text(), availableWidth(), metrics);
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, metrics.stringWidth(line));
        }
        return new Dimension(width, lines.size() * metrics.getHeight() + 2);
    }

    @Override
    public Dimension getMinimumSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        return new Dimension(40, metrics.getHeight());
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        // 刻意不调用 super：父类的 UI 委托会把整段文本画成一行并截断
        FontMetrics metrics = graphics.getFontMetrics(getFont());
        List<String> lines = wrap(text(), availableWidth(), metrics);
        graphics.setColor(getForeground());
        int y = metrics.getAscent();
        for (String line : lines) {
            graphics.drawString(line, 0, y);
            y += metrics.getHeight();
        }
    }

    /** 原始文本；本类不改写它，{@code getText()} 因此与调用方写入的一致。 */
    private String text() {
        String value = super.getText();
        return value == null ? "" : value;
    }

    /** 折行宽度：控件当前宽度优先，首帧为 0 时回落到父容器宽度，再不行给一个保守值。 */
    private int availableWidth() {
        if (getWidth() > 0) return getWidth();
        Container parent = getParent();
        if (parent != null && parent.getWidth() > 0) return parent.getWidth();
        return 320;
    }

    /**
     * 按宽度贪心折行：优先在空格处断，长标识没有空格时按字符硬断。
     *
     * <p>硬断是必需的：载荷标识这类长串中间没有空格，只按词断会让整行溢出，
     * 也就回到了「被截断」的老问题。超过 {@link #MAX_LINES} 行时最后一行以省略号收尾，
     * 避免窄栏里的状态文字把其它内容挤出可视区。
     */
    private static List<String> wrap(String text, int width, FontMetrics metrics) {
        List<String> lines = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }
        int index = 0;
        int length = text.length();
        while (index < length) {
            if (lines.size() == MAX_LINES) {
                lines.set(MAX_LINES - 1, shorten(lines.get(MAX_LINES - 1)));
                return lines;
            }
            int end = index;
            int lastSpace = -1;
            while (end < length) {
                char ch = text.charAt(end);
                if (ch == '\n') break;
                if (metrics.stringWidth(text.substring(index, end + 1)) > width && end > index) break;
                if (ch == ' ') lastSpace = end;
                end++;
            }
            if (end >= length) {
                lines.add(text.substring(index).trim());
                return lines;
            }
            // 断在词中间时退回到最近的空格：英文单词因此不会被拦腰截断
            int cut = end;
            if (cut < length && !isBreakable(text.charAt(cut)) && lastSpace >= index) cut = lastSpace;
            if (cut <= index) cut = index + 1;
            lines.add(text.substring(index, cut).trim());
            index = cut;
            while (index < length && (text.charAt(index) == ' ' || text.charAt(index) == '\n')) index++;
        }
        if (lines.isEmpty()) lines.add(text.trim());
        return lines;
    }

    /** 该字符之前是否允许断行：中文与空格可以直接断，英文单词内部不断。 */
    private static boolean isBreakable(char ch) {
        return ch == ' ' || ch >= 0x2E80;
    }

    /** 超过最大行数时把最后一行换成省略号结尾。 */
    private static String shorten(String line) {
        String value = line == null ? "" : line.trim();
        return value.length() <= 1 ? value : value.substring(0, value.length() - 1) + "\u2026";
    }
}
