package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;

import payload.PayloadCodec;


/**
 * Payload 生成页的装配入口：版面骨架与两处可复用的渲染器。
 *
 * <p>版面与网页版 java-chains 的 Generate 页逐块对应：上半是控制台（横向三栏且可拖拽调宽：
 * 左 OUTPUT、中 CONTEXT、右 ENCODE / OPTIONS），下半是链路区（链路条 + 列式选链条），
 * 上下之间同样可拖拽调高。逐块的面板构建在 {@link PayloadPanels} 里。
 *
 * <p>与网页版的差异只有一处是刻意的：节点参数放在 CONTEXT 栏下半部分常驻，
 * 而网页版把它收进点击齿轮弹出的 Params 对话框。本工具的生成流程要求「先看参数再生成」，
 * 常驻可见比弹窗少一次点击。
 *
 * <p>本页只负责界面结构：链状态、编码与引擎调用在 {@link PayloadController} 里；
 * 链路徽标样式与参数行渲染放在这里，是因为它们被恶意服务器页与预设链页共用。
 */
public final class PayloadPage {

    /** 链路徽标的最大字数：超出截断，完整标识靠悬停提示看。 */
    private static final int CHIP_TEXT_LIMIT = 22;


    /** 把生成的载荷交给抓包页：复用该页已有的请求体输入框。 */
    public interface CaptureSink {
        void send(String payload);
    }

    /** 一个待渲染的参数：来自引擎的节点参数声明。 */
    public static final class ParamField {
        /** 引擎认识的完整键，例如 Exec.cmd。 */
        public final String key;
        /** 展示名，例如「命令」。 */
        public final String label;
        /** 参数说明，作为控件提示。 */
        public final String hint;
        /** 可选值；为空表示自由输入。 */
        public final String[] choices;
        /** 是否必填。 */
        public final boolean required;
        /** 渲染出来的控件，由本页赋值。 */
        public JComponent field;

        public ParamField(String key, String label, String hint, String[] choices, boolean required) {
            this.key = key;
            this.label = label;
            this.hint = hint;
            this.choices = choices;
            this.required = required;
        }
    }

    /** 本页需要的控件与回调。 */
    public static final class Widgets {
        /** 只读的当前链文本：链只能由点击列内候选构建，不允许手填。 */
        public final JTextField chain = new JTextField("", 40);
        /** 链信息：节点数 / 末端是否可继续。 */
        public final JLabel chainMeta = new JLabel("");
        /** 链路徽标行：每节一个按钮，点击定位到对应列（不改链）。 */
        public final JPanel chainChips = new JPanel();
        public final JButton undo = new JButton("删除末节点");
        public final JButton clear = new JButton("清空链");
        /** 列式链选择器：由界面层按字体回调创建后注入。 */
        public PayloadChainSelector selector;

        public final JTextArea output = new JTextArea();
        /** 输出体积徽标：1.5 KB 这类。 */
        public final JLabel outputSize = new JLabel("-");
        /** 编辑开关：打开后输出区可手改，复制 / 导出用改后的内容（网页版 OUTPUT 的 Edit）。 */
        public final JToggleButton edit = new JToggleButton("编辑");
        public final JButton copy = new JButton("复制");
        public final JButton export = new JButton("导出文件");
        public final JButton toCapture = new JButton("填入抓包页");
        /** 展开所有：把输出区里的内容撑到完整长度（网页版 Expand）。 */
        public final JButton expand = new JButton("展开所有");

        /** CONTEXT 栏标题：带条目数，等价于网页版的 CONTEXT (10)。 */
        public final JLabel contextTitle = new JLabel("CONTEXT");
        /** 上下文搜索框：按键或来源过滤条目（网页版 CONTEXT 栏的 Search context keys）。 */
        public final JTextField contextSearch = new JTextField("", 16);
        /** 上下文列表：网页版 CONTEXT 面板里的一行行条目。 */
        public final JList<String> contextList = new JList<String>();
        /** 选中上下文条目的内容预览 / 可复制文本。 */
        public final JTextArea contextDetail = new JTextArea();
        public final JButton copyContext = new JButton("复制该条");

        public final JPanel params = new JPanel();

        /**
         * 编码单选按钮：Raw / Base64 / Hex / Gzip，与网页版 ENCODE 一行一致。
         *
         * <p>按钮在这里就建好而不是等到进页构建：控制器构造期要设默认选中，
         * 那一刻若按钮还不存在，默认编码就落不到界面上（实测踩到：四个按钮全是未选中）。
         */
        public final Map<PayloadCodec.Option, JToggleButton> encode =
                new EnumMap<PayloadCodec.Option, JToggleButton>(PayloadCodec.Option.class);
        /** 编码按钮的互斥组：同样只建一次，反复进页不会累积分组。 */
        public final ButtonGroup encodeGroup = new ButtonGroup();
        {
            for (PayloadCodec.Option option : PayloadCodec.Option.values()) {
                JToggleButton button = new JToggleButton(option.label);
                encodeGroup.add(button);
                encode.put(option, button);
            }
        }
        /** 是否再套一层 URL 编码（网页版 ENCODE 行右侧的 URL）。 */
        public final JToggleButton urlEncode = new JToggleButton("URL");
        /** 生成后自动复制到剪贴板（网页版 BEHAVIOR 的自动复制）。 */
        public final JCheckBox autoCopy = new JCheckBox("自动复制", false);
        /** 改链后自动生成（网页版 BEHAVIOR 的自动生成）。 */
        public final JCheckBox autoBuild = new JCheckBox("自动生成", false);
        /** 生成后直接展开完整载荷，不等使用者点「展开」（网页版 BEHAVIOR 的展开所有）。 */
        public final JCheckBox autoExpand = new JCheckBox("展开所有", false);
        /** 鼠标滑过候选即选中（网页版 BEHAVIOR 的悬停选链）。 */
        public final JCheckBox hoverSelect = new JCheckBox("悬停选链", false);
        /** 调试生成：采集逐步产物（网页版「调试生成」）。 */
        public final JButton buildDebug = new JButton("调试生成");
        public final JButton build = new JButton("生成");
        /** 导出 / 保存时的文件名前缀（网页版「保存/下载 文件名」）。 */
        public final JTextField fileName = new JTextField("", 18);

        public final JLabel status = new JLabel("");
        /** 导出目录；空表示落到用户目录。由界面层从配置页注入。 */
        public String exportDirectory = "";
        /** 把载荷填入抓包页；由界面层提供，未提供时按钮给出提示。 */
        public CaptureSink onSendToCapture;
        /** 当前渲染出的参数行，供自检读取。 */
        public List<ParamField> fields = new ArrayList<ParamField>();
        /** 当前是否处于「展开所有」状态，供自检读取。 */
        public boolean expanded;
        /** 当前是否处于编辑态，供自检读取。 */
        public boolean editing;
    }

    private PayloadPage() {
    }

    public static JPanel build(Widgets widgets, UiKit.FontSink sink) {
        JPanel page = UiKit.page();
        page.add(UiKit.pageHeading("PAYLOAD", "Payload 生成",
                "java-chains 载体与节点  ·  列式选链  ·  编码输出  ·  本地生成  ·  授权测试", sink),
                BorderLayout.NORTH);

        JPanel work = new JPanel(new BorderLayout(0, 12));
        work.setOpaque(false);
        work.setBorder(BorderFactory.createEmptyBorder(20, 0, 0, 0));

        // 控制台与链路区上下分栏：网页版同样可以拖拽中间那条分隔线调高度
        work.add(PayloadPanels.splitPane(javax.swing.JSplitPane.VERTICAL_SPLIT,
                PayloadPanels.console(widgets, sink), PayloadPanels.chainArea(widgets, sink),
                0.45, 12), BorderLayout.CENTER);
        page.add(work, BorderLayout.CENTER);
        return page;
    }

    /** 链路徽标的样式：小圆角按钮，点击只定位到对应列，不改变链。 */
    public static JButton chipButton(String text, String tooltip, UiKit.FontSink sink, int baseSize) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setForeground(UiKit.BODY_TEXT);
        button.setBackground(Color.WHITE);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiKit.FIELD_BORDER),
                BorderFactory.createEmptyBorder(3, 9, 3, 9)));
        sink.track(button, Font.PLAIN, baseSize);
        return button;
    }

    /** 链路徽标的展示文本：过长时截断，完整标识由调用方放进悬停提示。 */
    public static String chipText(String label) {
        String text = label == null ? "" : label.trim();
        return text.length() <= CHIP_TEXT_LIMIT ? text : text.substring(0, CHIP_TEXT_LIMIT) + "\u2026";
    }

    /**
     * 按引擎给出的参数声明渲染参数行。
     *
     * <p>参数随链上节点变化，因此每次改链都整块重建。
     */
    public static void renderParams(JPanel panel, List<ParamField> fields, UiKit.FontSink sink) {
        renderParams(panel, fields, sink, false);
    }

    /**
     * 渲染参数行；{@code compact} 为真时用窄标签与窄输入框，供侧栏使用。
     *
     * <p>紧凑模式把参数说明从行尾挪到控件提示：侧栏只有四百来像素，
     * 行尾再挂一段说明会把输入框挤到看不见。
     */
    public static void renderParams(JPanel panel, List<ParamField> fields, UiKit.FontSink sink,
                                   boolean compact) {
        panel.removeAll();
        if (fields == null || fields.isEmpty()) {
            panel.add(UiKit.label("当前链没有可配置参数。", Font.PLAIN, 13, UiKit.MUTED, sink));
            panel.revalidate();
            panel.repaint();
            return;
        }
        for (ParamField field : fields) {
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
            int labelWidth = compact ? 118 : 150;
            JLabel label = UiKit.label(field.label + (field.required ? " *" : ""), Font.BOLD, 13, UiKit.TEXT, sink);
            label.setPreferredSize(new Dimension(labelWidth, 34));
            if (field.hint != null && !field.hint.isEmpty()) label.setToolTipText(field.hint);
            row.add(label, BorderLayout.WEST);

            if (field.choices != null && field.choices.length > 0) {
                JComboBox<String> combo = new JComboBox<String>(field.choices);
                combo.setPreferredSize(new Dimension(compact ? 168 : 220, 34));
                combo.setBackground(Color.WHITE);
                combo.setForeground(UiKit.TEXT);
                combo.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(UiKit.FIELD_BORDER),
                        BorderFactory.createEmptyBorder(4, 8, 4, 8)));
                sink.track(combo, Font.PLAIN, 14);
                if (field.hint != null && !field.hint.isEmpty()) combo.setToolTipText(field.hint);
                field.field = combo;
                row.add(combo, BorderLayout.CENTER);
            } else {
                JTextField input = new JTextField("", compact ? 16 : 24);
                UiKit.styleField(input, sink);
                if (field.hint != null && !field.hint.isEmpty()) input.setToolTipText(field.hint);
                field.field = input;
                row.add(input, BorderLayout.CENTER);
            }
            if (!compact) {
                row.add(UiKit.label(field.hint == null ? "" : field.hint, Font.PLAIN, 12, UiKit.MUTED, sink),
                        BorderLayout.EAST);
            }
            panel.add(row);
        }
        panel.add(Box.createVerticalGlue());
        panel.revalidate();
        panel.repaint();
    }
}
