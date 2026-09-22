package ui;

/**
 * 列式选链的几何：列宽自适应、列表高度档位与切换规则。
 *
 * <p>常量全部对齐网页版 java-chains Generate 页的前端实现，避免「看着像、量起来不一样」：
 * <ul>
 *   <li>列宽：{@code In=500} 上限、{@code So=300} 下限、{@code $o=18} 列间距、{@code Eo=8} 左右内边距，
 *       由 {@code xo()} 按容器宽度与列数算出等宽列；算不下时取下限并靠横向滚动条。</li>
 *   <li>列表高：{@code Ze=320} 默认、{@code vs=160} 下限、{@code ws=640} 上限、{@code fr=480} 展开，
 *       由 {@code Aa()} 收敛；双击切换用 {@code kf()} 的「过半即收、未过半即展」规则。</li>
 * </ul>
 *
 * <p>从 {@link PayloadChainSelector} 拆出来独立成类，是因为这一块是纯算术与常量，
 * 不碰控件也不持状态；选择器只负责把结果套到组件上。
 */
final class ChainSelectorSizing {

    /** 列宽下限：够放「TemplatesImpl加载字节码」这类中文显示名与右侧标识徽标。 */
    static final int MIN_COLUMN_WIDTH = 300;
    /** 列宽上限：网页版 --chain-col-width 的上限。 */
    static final int MAX_COLUMN_WIDTH = 500;
    /** 列间距：网页版 .chain-column-selector 的 gap。 */
    static final int COLUMN_GAP = 18;
    /** 选择器左右内边距：网页版 xo() 的 paddingX。 */
    static final int PADDING_X = 8;

    /** 列表默认高度：网页版 --chain-option-list-height 的默认值。 */
    static final int DEFAULT_LIST_HEIGHT = 320;
    /** 列表最小高度：再矮就只剩三四行，列内滚动失去意义。 */
    static final int MIN_LIST_HEIGHT = 160;
    /** 列表最大高度：拖拽上限。 */
    static final int MAX_LIST_HEIGHT = 640;
    /** 列表展开高度：双击拖拽条的展开档。 */
    static final int EXPANDED_LIST_HEIGHT = 480;

    /** 列内单行高度：固定行高让过长的显示名被截成省略号，而不是撑出横向滚动条。 */
    static final int ROW_HEIGHT = 26;
    /** 列内除列表之外的固定高度：标题行 + 过滤行 + 间距 + 内边距 + 边框。 */
    static final int COLUMN_CHROME = 84;
    /** 横向滚动条预留高度：列数多到一屏放不下时会出现。 */
    static final int H_SCROLLBAR = 17;
    /** 竖向拖拽条高度：网页版 .chain-columns-resize-handle 的 10px，外加两侧留白。 */
    static final int HANDLE_HEIGHT = 14;

    private ChainSelectorSizing() {
    }

    /**
     * 按容器宽度与列数算列宽，等价于网页版的 {@code xo()}。
     *
     * <p>三档：算出来比上限宽就取上限（列不会无限胖）；落在上下限之间就取实际值（正好铺满）；
     * 比下限还窄就取下限（宁可横向滚动也不把列压得看不清内容）。
     */
    static int columnWidth(int containerWidth, int columnCount) {
        int count = Math.max(1, columnCount);
        if (containerWidth <= 0) return MAX_COLUMN_WIDTH;
        int available = containerWidth - PADDING_X - COLUMN_GAP * Math.max(0, count - 1);
        int each = Math.max(0, available) / count;
        if (each >= MAX_COLUMN_WIDTH) return MAX_COLUMN_WIDTH;
        if (each >= MIN_COLUMN_WIDTH) return each;
        return MIN_COLUMN_WIDTH;
    }

    /** 列表高度收敛到 [最小, 最大]，非数值时回落到默认档。 */
    static int clampListHeight(int height) {
        return Math.max(MIN_LIST_HEIGHT, Math.min(MAX_LIST_HEIGHT, height));
    }

    /** 双击切换：当前偏矮就展开，偏高就收成默认档，等价于网页版的 {@code kf()}。 */
    static int toggledListHeight(int current) {
        int middle = (DEFAULT_LIST_HEIGHT + EXPANDED_LIST_HEIGHT) / 2;
        return current >= middle ? DEFAULT_LIST_HEIGHT : EXPANDED_LIST_HEIGHT;
    }

    /** 一列的总高度：列表高度加上列头与内边距。 */
    static int columnHeight(int listHeight) {
        return listHeight + COLUMN_CHROME;
    }

    /** 整条选择器的高度：列高再加横向滚动条的预留。 */
    static int stripHeight(int listHeight) {
        return columnHeight(listHeight) + H_SCROLLBAR;
    }
}
