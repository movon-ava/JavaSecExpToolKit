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
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * 漏洞分析 · 组件与漏洞页：依赖口径分析（秒级、纯本地）。
 *
 * <p>为什么与「调用链查询」分成两页：两者回答的是**不同口径**的问题。
 * 本页回答「引入了哪些组件、它们的版本有没有已知漏洞、classpath 上有哪些 gadget 可用」——
 * 依据是构建产物里的 Maven 元数据；调用链页回答「代码里有没有危险调用、像什么漏洞」——
 * 依据是外部引擎建出的调用图。
 *
 * <p>此前两页共用一个视图，结果是点哪个二级项看到的都一样，使用者无法判断
 * 「我现在看的是依赖结论还是代码结论」。拆开之后，需要分钟级代价的动作
 * 全部收在调用链页，本页永远是秒级的。
 *
 * <p>页面只描述结构，行为在 {@link AnalyzeScanController}；文件对话框实例在
 * {@link #defaults()} 里建好交给行为类，行为类因此不需要 new 任何控件。
 */
public final class AnalyzeScanPage {

    /** 未选目标时的提示：两个页面都用到，放在这里避免两处各写一份措辞。 */
    public static final String NO_TARGET_HINT = "请先选择要分析的 jar 文件或依赖目录。";

    /** 本页需要的控件与回调。 */
    public static final class Widgets {
        /** 待分析的 jar 或依赖目录。 */
        public JTextField target;
        public JButton chooseTarget;
        public JFileChooser targetChooser;
        /** 源码工程 pom.xml（可选）。 */
        public JTextField pomPath;
        public JButton choosePom;
        public JFileChooser pomChooser;

        /** 本地依赖分析（秒级）。 */
        public JButton run;
        public JButton copy;
        public JLabel status;
        public JProgressBar progress;
        public JTextArea output;
        /** 建议动作按钮所在容器：按结论动态填充。 */
        public JPanel jumps;

        /** 复制报告。 */
        public Runnable onCopy;
        /** 选择目标的默认目录；由行为类设置，避免视图层依赖平台判断。 */
        public String defaultDirectory = "";
    }

    /** 本页控件的默认实例：初值、候选与对话框都留在视图类内。 */
    public static Widgets defaults() {
        Widgets widgets = new Widgets();
        widgets.target = new JTextField("", 34);
        widgets.chooseTarget = new JButton("选择 jar / 目录");
        widgets.targetChooser = new JFileChooser();
        // 依赖目录是「lib 目录整包排查」的常见入口，必须允许选目录
        widgets.targetChooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        widgets.targetChooser.setDialogTitle("选择待分析的 jar 或依赖目录");

        widgets.pomPath = new JTextField("", 34);
        widgets.choosePom = new JButton("选择 pom.xml");
        widgets.pomChooser = new JFileChooser();
        widgets.pomChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        widgets.pomChooser.setFileFilter(new FileNameExtensionFilter("Maven pom.xml", "xml"));
        widgets.pomChooser.setDialogTitle("选择源码工程 pom.xml");

        widgets.run = new JButton("本地依赖分析");
        widgets.copy = new JButton("复制报告");
        widgets.status = new JLabel("");
        widgets.progress = new JProgressBar();
        widgets.output = new JTextArea();
        widgets.jumps = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        return widgets;
    }

    private AnalyzeScanPage() {
    }

    /** 构建页面。 */
    public static JPanel build(Widgets widgets, UiKit.FontSink sink) {
        JPanel page = UiKit.page();
        page.add(UiKit.pageHeading("ANALYZE · DEPENDENCIES", "组件与漏洞",
                "读依赖坐标 → 判定已知漏洞 → 列出可用 gadget  ·  秒级，不需要任何外部程序", sink),
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
        row = fileRow(form, c, row, "源码工程 pom.xml（可选）", widgets.pomPath,
                widgets.choosePom, "读工程声明的依赖，与产物口径对照", sink);

        c.gridx = 0; c.gridy = row; c.gridwidth = 3; c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(8, 0, 0, 0);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        actions.setOpaque(false);
        UiKit.stylePrimaryButton(widgets.run, sink);
        actions.add(widgets.run);
        form.add(actions, c);
        row++;

        c.gridy = row; c.insets = new Insets(6, 0, 0, 0);
        widgets.status.setForeground(UiKit.MUTED);
        sink.track(widgets.status, Font.PLAIN, 13);
        form.add(widgets.status, c);
        return form;
    }

    /** 目标 / pom 一行：只读路径框 + 选择按钮 + 右侧说明。 */
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
        // 建议按钮与进度条固定在上方，报告区占据其余全部空间：
        // 报告可能很长（几十条结论），必须让它吃掉剩余高度而不是被压成几行
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
        text.append("用法：选一个 jar 或依赖目录 → 点「本地依赖分析」秒级拿到结论。").append(System.lineSeparator());
        text.append(System.lineSeparator());
        text.append("报告分三段：").append(System.lineSeparator());
        text.append("  1. 结论     —— 按版本区间判定的已知漏洞（带依据与可信度）").append(System.lineSeparator());
        text.append("  2. gadget   —— 这份依赖上哪几条链是齐备的、缺哪些").append(System.lineSeparator());
        text.append("  3. 组件清单 —— 识别到的坐标与来源").append(System.lineSeparator());
        text.append(System.lineSeparator());
        text.append("本地分析读的是构建产物里的元数据：").append(System.lineSeparator());
        text.append("  META-INF/maven/**/pom.properties  （最准，标为「高」可信度）").append(System.lineSeparator());
        text.append("  META-INF/maven/**/pom.xml         （同上）").append(System.lineSeparator());
        text.append("  MANIFEST.MF 的 Implementation-*  （标为「中」）").append(System.lineSeparator());
        text.append("  jar 文件名                        （标为「低」，版本可能不准）").append(System.lineSeparator());
        text.append(System.lineSeparator());
        text.append("本页只看依赖，不下判断到代码层。要找「代码里的危险调用与可能的漏洞类型」，")
                .append("到「调用链查询」页跑一次特征匹配。").append(System.lineSeparator());
        return text.toString();
    }

    /** 把跳转按钮填进容器；由控制器调用，按键点击后跳到对应功能页。 */
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