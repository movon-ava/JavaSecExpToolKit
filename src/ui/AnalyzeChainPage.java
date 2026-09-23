package ui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * 漏洞分析 · 调用链查询页：代码口径分析（需外部引擎，分钟级建库）。
 *
 * <p>本页承载三件相互衔接的事，顺序就是使用顺序：
 * ① 建库（调用链分析，分钟级，需要配置页里的引擎 jar）→
 * ② 取事实（数据库查询：总览 / 入口点 / Sink 命中 / 字符串常量 / 组件清单）→
 * ③ 下判断（特征匹配：给出可能的**漏洞类型**与该类型下常见的**绕过手法**）。
 *
 * <p>反编译也在本页：它服务于「定点确认」——特征匹配报了某个方法可疑，
 * 接下来就该看那个方法的真实源码，因此和②③放在一起最顺手。
 *
 * <p>页面只描述结构，行为在 {@link AnalyzeController}。
 */
public final class AnalyzeChainPage {

    /** 特征匹配的最低严重度候选：与签名库的 severity 取值一一对应。 */
    public static final String[] SEVERITY_VALUES = {"", "high", "medium", "low"};
    /** 严重度下拉的显示名，顺序与 {@link #SEVERITY_VALUES} 一致。 */
    public static final String[] SEVERITY_LABELS = {"全部", "仅高危", "高危 + 中危", "全部（含低危）"};

    /** 本页需要的控件与回调。 */
    public static final class Widgets {
        /** 待分析的 jar 或依赖目录。 */
        public JTextField target;
        public JButton chooseTarget;
        public JFileChooser targetChooser;

        /** 运行外部引擎构建数据库（分钟级）。 */
        public JButton runEngine;
        public JCheckBox quickMode;
        public JCheckBox innerJars;
        public JTextField timeoutSeconds;

        /** 数据库查询。 */
        public JComboBox<String> queryKind;
        public JTextField keyword;
        public JButton query;
        /** 特征匹配：把代码特征与签名库对照，输出漏洞类型与绕过手法。 */
        public JButton signatures;
        public JComboBox<String> minSeverity;

        /** 反编译（内置 CFR）。 */
        public JTextField className;
        public JTextField outputDir;
        public JButton chooseOutput;
        public JFileChooser outputChooser;
        public JButton decompile;
        public JButton openOutput;

        public JButton copy;
        public JLabel status;
        public JProgressBar progress;
        public JTextArea output;
        /** 建议动作按钮所在容器：按结论动态填充。 */
        public JPanel jumps;

        /** 复制报告。 */
        public Runnable onCopy;
        /** 选择目标的默认目录；由行为类设置。 */
        public String defaultDirectory = "";
    }

    /** 本页控件的默认实例。 */
    public static Widgets defaults() {
        Widgets widgets = new Widgets();
        widgets.target = new JTextField("", 34);
        widgets.chooseTarget = new JButton("选择 jar / 目录");
        widgets.targetChooser = new JFileChooser();
        widgets.targetChooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        widgets.targetChooser.setDialogTitle("选择待分析的 jar 或依赖目录");

        widgets.runEngine = new JButton("调用链分析");
        widgets.quickMode = new JCheckBox("快速模式", false);
        widgets.innerJars = new JCheckBox("解析嵌套 jar", true);
        widgets.timeoutSeconds = new JTextField("300", 8);

        widgets.queryKind = new JComboBox<String>(new String[]{
                "总览", "入口点", "Sink 命中", "字符串常量", "组件清单"});
        widgets.keyword = new JTextField("", 18);
        widgets.query = new JButton("查询数据库");
        widgets.signatures = new JButton("漏洞特征匹配");
        widgets.minSeverity = new JComboBox<String>(SEVERITY_LABELS);

        widgets.className = new JTextField("", 34);
        widgets.outputDir = new JTextField("", 34);
        widgets.chooseOutput = new JButton("选择输出目录");
        widgets.outputChooser = new JFileChooser();
        widgets.outputChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        widgets.outputChooser.setDialogTitle("选择反编译输出目录");
        widgets.decompile = new JButton("反编译");
        widgets.openOutput = new JButton("打开输出目录");

        widgets.copy = new JButton("复制报告");
        widgets.status = new JLabel("");
        widgets.progress = new JProgressBar();
        widgets.output = new JTextArea();
        widgets.jumps = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        return widgets;
    }

    private AnalyzeChainPage() {
    }

    public static JPanel build(Widgets widgets, UiKit.FontSink sink) {
        JPanel page = UiKit.page();
        page.add(UiKit.pageHeading("ANALYZE · CODE", "调用链查询",
                "建调用图 → 取事实 → 判漏洞类型与绕过手法  ·  需要外部引擎，建库是分钟级任务", sink),
                BorderLayout.NORTH);

        JPanel work = new JPanel(new BorderLayout(0, 16));
        work.setOpaque(false);
        work.setBorder(BorderFactory.createEmptyBorder(24, 0, 0, 0));
        work.add(form(widgets, sink), BorderLayout.NORTH);
        work.add(outputPanel(widgets, sink), BorderLayout.CENTER);
        page.add(work, BorderLayout.CENTER);
        return page;
    }

    private static JPanel form(Widgets widgets, UiKit.FontSink sink) {
        JPanel form = UiKit.surface(new GridBagLayout());
        GridBagConstraints c = UiKit.constraints();
        int row = 0;

        row = fileRow(form, c, row, "分析目标（jar / 依赖目录）", widgets.target,
                widgets.chooseTarget, "依赖目录会递归分析其中全部 jar", sink);
        row = timeoutRow(form, c, row, widgets, sink);

        // 第一组：建库
        c.gridx = 0; c.gridy = row; c.gridwidth = 3; c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(8, 0, 0, 0);
        JPanel buildRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        buildRow.setOpaque(false);
        UiKit.stylePrimaryButton(widgets.runEngine, sink);
        UiKit.styleSwitch(widgets.quickMode, sink);
        UiKit.styleSwitch(widgets.innerJars, sink);
        buildRow.add(UiKit.label("① 建库", Font.BOLD, 13, UiKit.TEXT, sink));
        buildRow.add(widgets.runEngine);
        buildRow.add(widgets.quickMode);
        buildRow.add(widgets.innerJars);
        form.add(buildRow, c);
        row++;

        // 第二组：事实查询 + 第三组：特征匹配
        c.gridy = row; c.insets = new Insets(10, 0, 0, 0);
        JPanel queryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        queryRow.setOpaque(false);
        UiKit.styleCombo(widgets.queryKind, 12, sink);
        UiKit.styleField(widgets.keyword, sink);
        UiKit.styleSecondaryButton(widgets.query, sink);
        queryRow.add(UiKit.label("② 取事实", Font.BOLD, 13, UiKit.TEXT, sink));
        queryRow.add(widgets.queryKind);
        queryRow.add(widgets.keyword);
        queryRow.add(widgets.query);
        queryRow.add(UiKit.label("字符串常量检索填关键字；其它查询忽略", Font.PLAIN, 12, UiKit.MUTED, sink));
        form.add(queryRow, c);
        row++;

        c.gridy = row; c.insets = new Insets(8, 0, 0, 0);
        JPanel signatureRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        signatureRow.setOpaque(false);
        UiKit.styleSecondaryButton(widgets.signatures, sink);
        UiKit.styleCombo(widgets.minSeverity, 14, sink);
        signatureRow.add(UiKit.label("③ 下判断", Font.BOLD, 13, UiKit.TEXT, sink));
        signatureRow.add(widgets.signatures);
        signatureRow.add(widgets.minSeverity);
        signatureRow.add(UiKit.label("按严重度过滤；结论含漏洞类型与该类型的绕过手法",
                Font.PLAIN, 12, UiKit.MUTED, sink));
        form.add(signatureRow, c);
        row++;

        // 第四组：定点确认
        row = plainRow(form, c, row, "指定类名（可选）", widgets.className,
                "留空则整包反编译；填 com.example.Foo 只反编译该类", sink);
        row = fileRow(form, c, row, "反编译输出目录", widgets.outputDir,
                widgets.chooseOutput, "留空则用工作目录下的 decompiled", sink);

        c.gridy = row; c.insets = new Insets(8, 0, 0, 0);
        JPanel confirmRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        confirmRow.setOpaque(false);
        UiKit.styleSecondaryButton(widgets.decompile, sink);
        UiKit.styleSecondaryButton(widgets.openOutput, sink);
        confirmRow.add(UiKit.label("④ 定点确认", Font.BOLD, 13, UiKit.TEXT, sink));
        confirmRow.add(widgets.decompile);
        confirmRow.add(widgets.openOutput);
        form.add(confirmRow, c);
        row++;

        c.gridy = row; c.insets = new Insets(6, 0, 0, 0);
        widgets.status.setForeground(UiKit.MUTED);
        sink.track(widgets.status, Font.PLAIN, 13);
        form.add(widgets.status, c);
        return form;
    }

    private static int timeoutRow(JPanel form, GridBagConstraints c, int row, Widgets widgets,
                                  UiKit.FontSink sink) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0;
        c.fill = GridBagConstraints.NONE; c.insets = new Insets(0, 0, 8, 0);
        form.add(UiKit.label("调用链超时（秒）", Font.BOLD, 13, UiKit.TEXT, sink), c);
        UiKit.styleField(widgets.timeoutSeconds, sink);
        JPanel holder = new JPanel(new BorderLayout(10, 0));
        holder.setOpaque(false);
        holder.add(widgets.timeoutSeconds, BorderLayout.WEST);
        holder.add(UiKit.label("大 jar 构建数据库是分钟级任务，超时会终止引擎",
                Font.PLAIN, 12, UiKit.MUTED, sink), BorderLayout.CENTER);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 16, 8, 0);
        form.add(holder, c);
        return row + 1;
    }

    /** 路径行：只读路径框 + 选择按钮 + 右侧说明。 */
    private static int fileRow(JPanel form, GridBagConstraints c, int row, String labelText,
                               JTextField field, JButton button, String hint, UiKit.FontSink sink) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0;
        c.fill = GridBagConstraints.NONE; c.insets = new Insets(0, 0, 8, 0);
        form.add(UiKit.label(labelText, Font.BOLD, 13, UiKit.TEXT, sink), c);

        UiKit.styleField(field, sink);
        UiKit.styleSecondaryButton(button, sink);
        JPanel holder = new JPanel(new BorderLayout(10, 0));
        holder.setOpaque(false);
        holder.add(field, BorderLayout.CENTER);
        holder.add(button, BorderLayout.EAST);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 16, 8, 0);
        form.add(holder, c);
        c.gridx = 2; c.weightx = 0; c.fill = GridBagConstraints.NONE; c.insets = new Insets(0, 12, 8, 0);
        form.add(UiKit.label(hint, Font.PLAIN, 12, UiKit.MUTED, sink), c);
        return row + 1;
    }

    private static int plainRow(JPanel form, GridBagConstraints c, int row, String labelText,
                                javax.swing.JComponent field, String hint, UiKit.FontSink sink) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0;
        c.fill = GridBagConstraints.NONE; c.insets = new Insets(0, 0, 8, 0);
        form.add(UiKit.label(labelText, Font.BOLD, 13, UiKit.TEXT, sink), c);
        JPanel holder = new JPanel(new BorderLayout(10, 0));
        holder.setOpaque(false);
        holder.add(field, BorderLayout.WEST);
        holder.add(UiKit.label(hint, Font.PLAIN, 12, UiKit.MUTED, sink), BorderLayout.CENTER);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 16, 8, 0);
        form.add(holder, c);
        return row + 1;
    }

    private static JPanel outputPanel(Widgets widgets, UiKit.FontSink sink) {
        JPanel panel = UiKit.surface(new BorderLayout(0, 10));

        JPanel head = new JPanel(new BorderLayout(12, 0));
        head.setOpaque(false);
        head.add(UiKit.label("分析报告", Font.BOLD, 14, UiKit.TEXT, sink), BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        UiKit.styleSecondaryButton(widgets.copy, sink);
        if (widgets.onCopy != null) widgets.copy.addActionListener(e -> widgets.onCopy.run());
        right.add(widgets.copy);
        head.add(right, BorderLayout.EAST);
        panel.add(head, BorderLayout.NORTH);

        widgets.jumps.setOpaque(false);
        widgets.progress.setVisible(false);
        widgets.progress.setIndeterminate(true);
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(widgets.jumps, BorderLayout.NORTH);
        top.add(widgets.progress, BorderLayout.SOUTH);
        widgets.output.setEditable(false);
        UiKit.styleMonospaceArea(widgets.output, sink);
        widgets.output.setText(defaultText());
        panel.add(top, BorderLayout.NORTH);
        panel.add(UiKit.scroll(widgets.output), BorderLayout.CENTER);
        return panel;
    }

    private static String defaultText() {
        StringBuilder text = new StringBuilder();
        text.append("用法：①选目标并点「调用链分析」建库（分钟级，需在配置页填引擎 jar）→")
                .append(System.lineSeparator());
        text.append("      ②用「查询数据库」看事实（有哪些 sink、哪些字符串、哪些入口点）→")
                .append(System.lineSeparator());
        text.append("      ③点「漏洞特征匹配」拿判断：可能的漏洞类型 + 该类型的绕过手法")
                .append(System.lineSeparator());
        text.append("      ④对可疑方法用「反编译」看真实源码做定点确认")
                .append(System.lineSeparator());
        text.append(System.lineSeparator());
        text.append("②与③的区别：②回答「库里有什么」，输出即事实；③回答「这些事实像什么漏洞」，")
                .append(System.lineSeparator());
        text.append("带严重度、命中依据、漏洞类型与绕过手法，是这份分析里最该先看的一段。")
                .append(System.lineSeparator());
        text.append(System.lineSeparator());
        text.append("边界：③的命中只代表「出现了这类特征」，参数是否可控、目标是否真的解析这段数据，")
                .append(System.lineSeparator());
        text.append("仍需人工确认。本页不会替你下「一定有漏洞」的结论。").append(System.lineSeparator());
        return text.toString();
    }

    /** 把跳转按钮填进容器；由控制器调用。 */
    public static void fillJumps(JPanel container, List<String> labels,
                                 List<ActionListener> listeners, UiKit.FontSink sink) {
        container.removeAll();
        if (labels != null) {
            for (int index = 0; index < labels.size(); index++) {
                JButton button = new JButton(labels.get(index));
                UiKit.styleSecondaryButton(button, sink);
                if (listeners != null && index < listeners.size() && listeners.get(index) != null) {
                    button.addActionListener(listeners.get(index));
                }
                container.add(button);
            }
        }
        container.revalidate();
        container.repaint();
    }
}