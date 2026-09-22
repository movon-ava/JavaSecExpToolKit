package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;

import payload.PayloadCodec;


/**
 * Payload 生成页的版面构建：控制台三栏、链路区、以及两者之间的可拖拽分栏。
 *
 * <p>从 {@link PayloadPage} 拆出来独立成类，理由是这些方法只做「把控件摆进容器」，
 * 不持有任何状态，也不参与页面的对外契约（控件清单、徽标样式、参数渲染仍留在页面类）。
 * 页面类因此只描述有哪些块、怎么装配，具体每块长什么样在这里。
 *
 * <p>分栏比例的落地方式见 {@link #splitPane}：Swing 在建好时还不知道尺寸，
 * 必须等拿到真实尺寸再按比例摆放，否则比例会算成 0。
 */
final class PayloadPanels {

    /**
     * 控制台默认高度与上下分栏比例。
     *
     * <p>网页版 Generate 页的控制台是固定 360px、选链区吃掉剩余空间（实测 1280×900 下
     * 选链区约占整页 61%）。本工具的窗口高度有限，因此对齐的是**比例**而不是绝对值：
     * 分栏按 {@link #CONSOLE_WEIGHT} 分配，控制台落在三百来像素、选链区拿到六成左右，
     * 与网页版的版面比例一致。使用者随时可以拖分栏或拖选链区下方那条拖拽条再调。
     */
    /**
     * 网页版 Generate 页的实测高度：控制台 {@code .studio-top-console} 360px，
     * 选链区 {@code .chain-builder-block} 486px。
     *
     * <p>这两个值是量出来的，不是估的：用 CDP 驱动无头 Chrome 打开上游
     * {@code #/Generate/<payload>} 页，再读 {@code getBoundingClientRect()}。
     * 窗口高矮只影响网页版要不要滚动，两块自身的比例是固定的，因此本工具对齐的
     * 就是「选链区占两块之和」的比例（486 / 846 ≈ 57.4%），而不是照搬 360px 这个绝对值。
     */
    private static final int WEB_CONSOLE_HEIGHT = 360;
    /** 网页版选链区高度：{@code .chain-builder-block} 实测 486px。 */
    private static final int WEB_CHAIN_HEIGHT = 486;
    private static final int CONSOLE_HEIGHT = 330;
    /** 控制台最小高度：再矮就会把输出区压成一条细线（自检要求可视高度 > 120px）。 */
    private static final int CONSOLE_MIN_HEIGHT = 250;
    /** 控制台在上下分栏里的占比：与网页版「控制台 360 / 选链区 61%」的比例一致。 */
    static final double CONSOLE_WEIGHT =
            (double) WEB_CONSOLE_HEIGHT / (WEB_CONSOLE_HEIGHT + WEB_CHAIN_HEIGHT);
    /** 链路条高度：链路输入行 + 链路徽标行 + 链信息行 + 卡片内边距。 */
    private static final int CHAIN_STRIP_HEIGHT = 138;
    /** 链路输入行高度：按次级按钮的实际首选高度给，矮了会把按钮裁掉 5px（实测踩到）。 */
    private static final int CHAIN_TOP_HEIGHT = 46;
    /** 链路徽标行高度：一行徽标加横滚条。 */
    private static final int CHAIN_CHIP_HEIGHT = 34;
    /** 选链标题行高度：与其它页面的分组标题同高。 */
    private static final int SELECTOR_TITLE_HEIGHT = 22;
    /** 链路区默认高度：链路条 + 选链标题 + 列式列表（与选择器首选高度对齐）。 */
    private static final int CHAIN_BLOCK_HEIGHT = CHAIN_STRIP_HEIGHT + 10
            + SELECTOR_TITLE_HEIGHT + 8 + ChainSelectorSizing.HANDLE_HEIGHT
            + ChainSelectorSizing.stripHeight(ChainSelectorSizing.DEFAULT_LIST_HEIGHT);
    /** 链路区最小高度：保证链路条完整可见，候选列表至少露出最小档。 */
    private static final int CHAIN_BLOCK_MIN_HEIGHT = CHAIN_STRIP_HEIGHT + 10
            + SELECTOR_TITLE_HEIGHT + 8 + ChainSelectorSizing.HANDLE_HEIGHT
            + ChainSelectorSizing.stripHeight(ChainSelectorSizing.MIN_LIST_HEIGHT);
    /** 参数区高度：约三行参数行，再多就在区内滚动。 */
    private static final int PARAMS_MIN_HEIGHT = 150;
    /** CONTEXT 列表高度：约六行条目。 */
    private static final int CONTEXT_LIST_HEIGHT = 124;
    /** 条目内容区高度：约四行等宽文本。 */
    private static final int CONTEXT_DETAIL_HEIGHT = 76;

    private PayloadPanels() {
    }


    /**
     * 控制台：OUTPUT | CONTEXT | ENCODE / OPTIONS 三栏，两处分隔线都能拖拽。
     *
     * <p>分栏比例按网页版的实测宽度给：OUTPUT 约 29%、CONTEXT 约 39%、ENCODE 约 32%。
     * 外层取 0.69 得到「左 69% / 右 31%」，内层再按 0.43 切出 OUTPUT，
     * 落点即 69% × 43% ≈ 参考宽度（比例是相对父分栏的，不是相对整页）。
     */
    static JComponent console(PayloadPage.Widgets widgets, UiKit.FontSink sink) {
        JSplitPane left = SplitPaneKit.splitPane(JSplitPane.HORIZONTAL_SPLIT,
                outputPanel(widgets, sink), contextPanel(widgets, sink), 0.43, 10);
        JSplitPane all = SplitPaneKit.splitPane(JSplitPane.HORIZONTAL_SPLIT,
                left, optionsPanel(widgets, sink), 0.69, 10);
        all.setPreferredSize(new Dimension(0, CONSOLE_HEIGHT));
        all.setMinimumSize(new Dimension(0, CONSOLE_MIN_HEIGHT));
        return all;
    }

    /** 链路区：链路条（链路 ID + 徽标 + 链信息）与列式选链条。 */
    static JComponent chainArea(PayloadPage.Widgets widgets, UiKit.FontSink sink) {
        JPanel block = new JPanel(new BorderLayout(0, 10));
        block.setOpaque(false);
        block.add(chainStrip(widgets, sink), BorderLayout.NORTH);
        block.add(selectorBar(widgets, sink), BorderLayout.CENTER);
        block.setPreferredSize(new Dimension(0, CHAIN_BLOCK_HEIGHT));
        block.setMinimumSize(new Dimension(0, CHAIN_BLOCK_MIN_HEIGHT));
        return block;
    }

    /**
     * 链路条：链路 ID 输入行 + 可点击定位的徽标行 + 链信息行，对应网页版的「链路 ID」行。
     *
     * <p>三行用盒式布局串起来而不是 BorderLayout 的三段：后者在高度不足时会把 CENTER
     * 压成 0 高，链路徽标整行消失、看上去像「链上什么都没有」（实测踩到）。
     * 盒式布局按各行的首选高度依次排，高度不够时宁可整体溢出到外层滚动。
     */
    private static JPanel chainStrip(PayloadPage.Widgets widgets, UiKit.FontSink sink) {
        JPanel bar = UiKit.surface(null);
        // 卡片内边距收紧：链路条有三行内容，22px 的上下留白会把它顶到 150 以上，
        // 而这块高度是从候选列表那里借来的（网页版这一条也没有那么厚的留白）
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.BORDER),
                BorderFactory.createEmptyBorder(12, 18, 12, 18)));
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setPreferredSize(new Dimension(0, CHAIN_STRIP_HEIGHT));

        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setOpaque(false);
        top.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        top.add(UiKit.label("链路 ID", Font.BOLD, 13, UiKit.TEXT, sink), BorderLayout.WEST);

        widgets.chain.setEditable(false);
        UiKit.styleField(widgets.chain, sink);
        widgets.chain.setToolTipText("当前链的可复制文本形态；改链请在下方各列点候选");
        top.add(widgets.chain, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        UiKit.styleSecondaryButton(widgets.undo, sink);
        UiKit.styleSecondaryButton(widgets.clear, sink);
        actions.add(widgets.undo);
        actions.add(widgets.clear);
        top.add(actions, BorderLayout.EAST);
        // 行高按按钮的实际首选高度给：次级按钮带 9px 上下内边距，实际 43px 高，
        // 写死 38 会把「删除末节点 / 清空链」两个按钮各裁掉 5px（实测踩到）
        SplitPaneKit.setFixedHeight(top, CHAIN_TOP_HEIGHT);
        bar.add(top);
        bar.add(Box.createVerticalStrut(4));

        widgets.chainChips.setOpaque(false);
        widgets.chainChips.setLayout(new BoxLayout(widgets.chainChips, BoxLayout.X_AXIS));
        JScrollPane chips = new JScrollPane(widgets.chainChips);
        chips.setBorder(BorderFactory.createEmptyBorder());
        chips.setOpaque(false);
        chips.getViewport().setOpaque(false);
        chips.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        chips.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        chips.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        // 这里不能铺满：链路徽标行宽度由徽标个数决定，铺满会把滚动条拉成整行宽
        SplitPaneKit.setFixedHeight(chips, CHAIN_CHIP_HEIGHT);
        chips.setMaximumSize(new Dimension(Integer.MAX_VALUE, CHAIN_CHIP_HEIGHT));
        bar.add(chips);
        bar.add(Box.createVerticalStrut(4));

        widgets.chainMeta.setForeground(UiKit.MUTED);
        sink.track(widgets.chainMeta, Font.PLAIN, 12);
        widgets.chainMeta.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        widgets.chainMeta.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        bar.add(widgets.chainMeta);
        return bar;
    }

    private static JPanel outputPanel(PayloadPage.Widgets widgets, UiKit.FontSink sink) {
        JPanel card = UiKit.surface(new BorderLayout(0, 6));
        // 内边距比通用卡片紧两档：这一栏要塞「标题 + 工具行 + 输出区 + 按钮 + 状态」五块，
        // 而控制台在上下分栏里只占四成高；留白按 22px 给会把输出区可视高度压到 120px 以下
        // （自检有「输出区可视高度 > 120px」的硬断言）
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.BORDER),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        card.setMinimumSize(new Dimension(300, 0));

        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));

        JPanel titleRow = new JPanel(new BorderLayout(10, 0));
        titleRow.setOpaque(false);
        titleRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        titleRow.add(UiKit.label("OUTPUT", Font.BOLD, 13, UiKit.MUTED, sink), BorderLayout.WEST);
        widgets.outputSize.setForeground(UiKit.ACCENT);
        sink.track(widgets.outputSize, Font.BOLD, 12);
        titleRow.add(widgets.outputSize, BorderLayout.EAST);
        head.add(titleRow);
        head.add(Box.createVerticalStrut(2));

        // 按钮另起一行并用等分网格：窄栏里和标题挤一行会把标题盖掉，而 FlowLayout
        // 换行后按「一行的高度」上报首选尺寸，第二行会被裁掉（实测踩到：
        // 「展开所有」跑到下一行且只露一半）。等分网格无论栏多窄都挤在四个格里，不会换行。
        JPanel tools = new JPanel(new GridLayout(1, 4, 6, 0));
        tools.setOpaque(false);
        tools.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        UiKit.styleSecondaryButton(widgets.copy, sink);
        UiKit.styleSecondaryButton(widgets.export, sink);
        UiKit.styleSecondaryButton(widgets.expand, sink);
        styleToggle(widgets.edit, sink);
        // 按钮文案一律压到两字：OUTPUT 栏按比例只有四百来像素，
        // 「导出文件 / 展开所有」四个字会被截成「导…」「展…」（实测踩到）
        widgets.edit.setText("编辑");
        widgets.copy.setText("复制");
        widgets.export.setText("导出");
        widgets.expand.setText("展开");
        for (javax.swing.AbstractButton button : new javax.swing.AbstractButton[] {
                widgets.edit, widgets.copy, widgets.export, widgets.expand }) {
            button.setMargin(new Insets(0, 0, 0, 0));
        }
        widgets.edit.setToolTipText("打开后可直接修改下方正文，复制 / 导出 / 填入抓包页都会用修改后的内容");
        widgets.copy.setToolTipText("复制当前正文（编辑态下复制的是改后的内容）");
        widgets.export.setToolTipText("导出为文件，文件名取右侧 OUTPUT 栏里的文件名");
        widgets.expand.setToolTipText("展开完整载荷；长载荷默认只给预览");
        tools.add(widgets.edit);
        tools.add(widgets.copy);
        tools.add(widgets.export);
        tools.add(widgets.expand);
        SplitPaneKit.stretch(tools, 38);
        head.add(tools);
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        card.add(head, BorderLayout.NORTH);

        widgets.output.setEditable(false);
        UiKit.styleMonospaceArea(widgets.output, sink);
        JScrollPane scroll = UiKit.scroll(widgets.output);
        scroll.setPreferredSize(new Dimension(0, 320));
        scroll.setMinimumSize(new Dimension(0, 120));
        card.add(scroll, BorderLayout.CENTER);

        // 状态文字另起一行：与「填入抓包页」并排时，窄栏里状态会被按钮挤成半句
        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        JPanel sendRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        sendRow.setOpaque(false);
        sendRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        UiKit.styleSecondaryButton(widgets.toCapture, sink);
        sendRow.add(widgets.toCapture);
        footer.add(sendRow);
        footer.add(Box.createVerticalStrut(2));
        widgets.status.setForeground(UiKit.MUTED);
        sink.track(widgets.status, Font.PLAIN, 13);
        widgets.status.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        // 状态文字比栏宽还长（实测需要 438px，栏里只有 333px），给足宽度让它折行，
        // 否则会截成「已选载体 blazedsamf3ampay…」，结论看不到
        widgets.status.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2 * 20 + 2));
        footer.add(widgets.status);
        card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    /**
     * 中栏：CONTEXT 列表 + 该条内容 + 节点参数。
     *
     * <p>两块用竖向分栏分配高度：盒式布局按首选尺寸排，高度不够时会把最后一块整段裁掉
     * （实测踩到：节点参数整段不可见），分栏则按比例分配真实高度。
     */
    private static JComponent contextPanel(PayloadPage.Widgets widgets, UiKit.FontSink sink) {
        widgets.contextList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        widgets.contextList.setBackground(Color.WHITE);
        widgets.contextList.setForeground(UiKit.BODY_TEXT);
        widgets.contextList.setCellRenderer(new ContextRenderer(sink));
        sink.track(widgets.contextList, Font.PLAIN, 12);
        widgets.contextList.setFixedCellHeight(28);
        JScrollPane listScroll = new JScrollPane(widgets.contextList);
        listScroll.setBorder(BorderFactory.createLineBorder(UiKit.BORDER));
        listScroll.getViewport().setBackground(Color.WHITE);
        listScroll.setPreferredSize(new Dimension(0, CONTEXT_LIST_HEIGHT));
        // 下限给小：CONTEXT 栏的高度是从控制台分栏里分来的，给大了会把下方的
        // 「条目内容」整块挤成负高（实测踩到：条目内容区 41px 里塞 43px 的标题行）
        listScroll.setMinimumSize(new Dimension(0, 40));

        widgets.params.setOpaque(false);
        widgets.params.setLayout(new BoxLayout(widgets.params, BoxLayout.Y_AXIS));
        JScrollPane paramsScroll = new JScrollPane(widgets.params);
        paramsScroll.setBorder(BorderFactory.createLineBorder(UiKit.BORDER));
        paramsScroll.getViewport().setBackground(Color.WHITE);
        paramsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        paramsScroll.setPreferredSize(new Dimension(0, PARAMS_MIN_HEIGHT));
        paramsScroll.setMinimumSize(new Dimension(0, 90));

        JPanel contextCard = new JPanel(new BorderLayout(0, 8));
        contextCard.setOpaque(false);
        contextCard.add(contextHead(widgets, sink), BorderLayout.NORTH);
        // 清单与条目内容按比例分高度：把条目内容写死成 SOUTH，外层高度一紧
        // 就会被 CENTER 抢光，清单只剩一条缝（实测踩到）
        contextCard.add(SplitPaneKit.splitPane(JSplitPane.VERTICAL_SPLIT, listScroll,
                detailBlock(widgets, sink), 0.58, 8), BorderLayout.CENTER);
        contextCard.setMinimumSize(new Dimension(200, 150));

        JPanel paramsBlock = new JPanel(new BorderLayout(0, 6));
        paramsBlock.setOpaque(false);
        paramsBlock.add(UiKit.sectionTitle("节点参数", "随所选节点变化", sink), BorderLayout.NORTH);
        paramsBlock.add(paramsScroll, BorderLayout.CENTER);
        paramsBlock.setMinimumSize(new Dimension(0, 110));

        return SplitPaneKit.splitPane(JSplitPane.VERTICAL_SPLIT, contextCard, paramsBlock, 0.6, 10);
    }

    /** CONTEXT 栏标题行：标题带条目数与搜索框，对应网页版的「CONTEXT (10) + 搜索框」。 */
    private static JPanel contextHead(PayloadPage.Widgets widgets, UiKit.FontSink sink) {
        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));

        JPanel titleRow = new JPanel(new BorderLayout(10, 0));
        titleRow.setOpaque(false);
        titleRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        widgets.contextTitle.setForeground(UiKit.MUTED);
        sink.track(widgets.contextTitle, Font.BOLD, 13);
        titleRow.add(widgets.contextTitle, BorderLayout.WEST);
        titleRow.add(UiKit.label("构建写入的数据", Font.PLAIN, 12, UiKit.MUTED, sink), BorderLayout.EAST);
        head.add(titleRow);
        head.add(Box.createVerticalStrut(6));

        UiKit.styleField(widgets.contextSearch, sink);
        widgets.contextSearch.setToolTipText("按上下文键或来源节点过滤条目；清空即显示全部");
        widgets.contextSearch.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        SplitPaneKit.stretch(widgets.contextSearch, 34);
        head.add(widgets.contextSearch);
        return head;
    }

    /** 条目内容块：标题 + 复制按钮 + 内容区。 */
    private static JPanel detailBlock(PayloadPage.Widgets widgets, UiKit.FontSink sink) {
        JPanel detail = new JPanel(new BorderLayout(0, 6));
        detail.setOpaque(false);
        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setOpaque(false);
        head.add(sectionLabel("条目内容", sink), BorderLayout.WEST);
        UiKit.styleSecondaryButton(widgets.copyContext, sink);
        head.add(widgets.copyContext, BorderLayout.EAST);
        detail.add(head, BorderLayout.NORTH);

        widgets.contextDetail.setEditable(false);
        UiKit.styleMonospaceArea(widgets.contextDetail, sink);
        JScrollPane scroll = UiKit.scroll(widgets.contextDetail);
        scroll.setPreferredSize(new Dimension(0, CONTEXT_DETAIL_HEIGHT));
        scroll.setMinimumSize(new Dimension(0, 0));
        detail.add(scroll, BorderLayout.CENTER);
        return detail;
    }

    /** 右栏：ENCODE / OPTIONS，对应网页版最右侧那块。 */
    private static JComponent optionsPanel(PayloadPage.Widgets widgets, UiKit.FontSink sink) {
        JPanel card = UiKit.surface(new GridBagLayout());
        card.setMinimumSize(new Dimension(240, 0));
        GridBagConstraints c = UiKit.constraints();
        int row = 0;

        c.gridx = 0; c.gridy = row++; c.gridwidth = 1; c.weightx = 1; c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 10, 0);
        card.add(UiKit.label("ENCODE / OPTIONS", Font.BOLD, 13, UiKit.MUTED, sink), c);

        c.gridy = row++; c.insets = new Insets(0, 0, 6, 0);
        card.add(UiKit.label("ENCODE", Font.BOLD, 12, UiKit.BODY_TEXT, sink), c);

        // 编码按钮用网格排：四个按钮加 URL 开关在窄栏里一行放不下，交给 FlowLayout
        // 换行会被后续布局按「一行的高度」裁掉后面那几个（实测踩到）
        JPanel encodeRow = new JPanel(new GridLayout(0, 3, 6, 6));
        encodeRow.setOpaque(false);
        for (PayloadCodec.Option option : PayloadCodec.Option.values()) {
            JToggleButton button = widgets.encode.get(option);
            styleToggle(button, sink);
            encodeRow.add(button);
        }
        styleToggle(widgets.urlEncode, sink);
        widgets.urlEncode.setToolTipText("在编码结果之上再套一层 URL 编码（网页版 ENCODE 行的 URL）");
        encodeRow.add(widgets.urlEncode);
        // 三列排：五个按钮落成两行。两列会排成三行，右栏的「生成」按钮就被挤到
        // 滚动区之外，看起来像「没有生成入口」（实测踩到）
        int encodeRows = (PayloadCodec.Option.values().length + 1 + 2) / 3;
        SplitPaneKit.stretch(encodeRow, 34 * encodeRows + 6 * (encodeRows - 1));
        c.gridy = row++; c.insets = new Insets(0, 0, 14, 0);
        card.add(encodeRow, c);

        c.gridy = row++; c.insets = new Insets(0, 0, 6, 0);
        card.add(UiKit.label("OUTPUT", Font.BOLD, 12, UiKit.BODY_TEXT, sink), c);

        UiKit.styleField(widgets.fileName, sink);
        widgets.fileName.setToolTipText("导出文件名前缀；留空则按载体与时间戳自动命名");
        c.gridy = row++; c.insets = new Insets(0, 0, 14, 0);
        card.add(widgets.fileName, c);

        c.gridy = row++; c.insets = new Insets(0, 0, 6, 0);
        card.add(UiKit.label("BEHAVIOR", Font.BOLD, 12, UiKit.BODY_TEXT, sink), c);

        JPanel behavior = new JPanel(new GridLayout(2, 2, 6, 2));
        behavior.setOpaque(false);
        UiKit.styleSwitch(widgets.autoBuild, sink);
        UiKit.styleSwitch(widgets.autoCopy, sink);
        UiKit.styleSwitch(widgets.autoExpand, sink);
        UiKit.styleSwitch(widgets.hoverSelect, sink);
        widgets.autoBuild.setToolTipText("改链后立即重新生成（网页版 BEHAVIOR 的自动生成）");
        widgets.autoCopy.setToolTipText("生成成功后自动复制到剪贴板（网页版 BEHAVIOR 的自动复制）");
        widgets.autoExpand.setToolTipText("生成后直接展开完整载荷，不用再点「展开」（网页版 BEHAVIOR 的展开所有）");
        widgets.hoverSelect.setToolTipText("鼠标滑过候选即选中该节点，便于逐个查看参数（网页版 BEHAVIOR 的悬停选链）");
        behavior.add(widgets.autoBuild);
        behavior.add(widgets.autoCopy);
        behavior.add(widgets.autoExpand);
        behavior.add(widgets.hoverSelect);
        SplitPaneKit.stretch(behavior, 2 * 28 + 2);
        c.gridy = row++; c.insets = new Insets(0, 0, 16, 0);
        card.add(behavior, c);

        JPanel buttons = new JPanel(new GridLayout(1, 2, 8, 0));
        buttons.setOpaque(false);
        UiKit.styleSecondaryButton(widgets.buildDebug, sink);
        widgets.buildDebug.setToolTipText("采集每一步的中间产物，用于排查链在哪一步出了问题");
        UiKit.stylePrimaryButton(widgets.build, sink);
        buttons.add(widgets.buildDebug);
        buttons.add(widgets.build);
        SplitPaneKit.stretch(buttons, 40);
        c.gridy = row++; c.insets = new Insets(0, 0, 0, 0);
        card.add(buttons, c);

        // 最后一行吃掉剩余高度：内容不足时按钮仍贴着上方排，不会被拉到栏底
        c.gridy = row; c.weighty = 1; c.fill = GridBagConstraints.BOTH;
        card.add(new JPanel(), c);
        // 右栏整体可竖向滚动：栏高由分栏给定，内容超出时宁可滚动也不能静默裁掉
        // 「自动复制」这类开关（实测在 900px 高的窗口下会被裁掉）
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(card, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(holder);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /** 中间栏的小节标题：与其它页面的分组标题同字号，左对齐由盒式布局负责。 */
    private static JLabel sectionLabel(String text, UiKit.FontSink sink) {
        JLabel label = UiKit.label(text, Font.BOLD, 13, UiKit.MUTED, sink);
        label.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        return label;
    }

    /** 底部选链条：每级一列，对应网页版的 Gadget1 / Gadget2 …。 */
    private static JPanel selectorBar(PayloadPage.Widgets widgets, UiKit.FontSink sink) {
        JPanel block = new JPanel(new BorderLayout(0, 8));
        block.setOpaque(false);
        JPanel title = UiKit.sectionTitle("选择利用链",
                "点某一列的候选即追加并展开下一列；点回前面某一列即从那里重开链；带 END 的节点之后没有可接的节点",
                sink);
        SplitPaneKit.setFixedHeight(title, SELECTOR_TITLE_HEIGHT);
        block.add(title, BorderLayout.NORTH);
        block.add(widgets.selector.component(), BorderLayout.CENTER);
        // 竖向拖拽条摆在选择器下方：往上拖把候选列表调高（网页版同一条的位置），
        // 高度变化会经 HeightSink 让出控制台的高度，所以这里必须和分栏一起接线
        JPanel handleRow = new JPanel(new BorderLayout());
        handleRow.setOpaque(false);
        handleRow.add(widgets.selector.resizeHandle(), BorderLayout.CENTER);
        SplitPaneKit.setFixedHeight(handleRow, ChainSelectorSizing.HANDLE_HEIGHT);
        block.add(handleRow, BorderLayout.SOUTH);
        return block;
    }

    private static void styleToggle(JToggleButton button, UiKit.FontSink sink) {
        button.setForeground(UiKit.TEXT);
        button.setBackground(Color.WHITE);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.FIELD_BORDER),
                BorderFactory.createEmptyBorder(7, 14, 7, 14)));
        sink.track(button, Font.BOLD, 13);
    }

    /** 上下文条目渲染：序号 + 类型 + 名称 + 来源，与网页版 CONTEXT 面板的每行一致。 */
    private static final class ContextRenderer extends javax.swing.DefaultListCellRenderer {
        private final UiKit.FontSink fonts;

        ContextRenderer(UiKit.FontSink fonts) {
            this.fonts = fonts;
        }

        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
            label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            label.setToolTipText(String.valueOf(value));
            if (!selected) label.setForeground(UiKit.BODY_TEXT);
            fonts.track(label, Font.PLAIN, 12);
            return label;
        }
    }
}
