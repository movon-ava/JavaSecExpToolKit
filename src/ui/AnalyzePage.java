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
 * 漏洞分析页：本地依赖分析 + 外部调用链 + 反编译，三块能力放在一页。
 *
 * <p>为什么合成一页而不是三个二级菜单：三者的**输入是同一个 jar**，
 * 使用路径是「先秒级出结论 → 需要时再深挖 → 对可疑点定点看源码」，
 * 拆开会导致每换一步都要重新选一次文件。这里只把「代价差异大」的动作
 * 用按钮区分开，让使用者自己决定何时付出分钟级代价。
 *
 * <p>页面只描述结构，行为在 {@link AnalyzeController}；文件对话框实例在
 * {@link #defaults()} 里建好交给行为类，行为类因此不需要 new 任何控件。
 */
public final class AnalyzePage {

    /** 本页需要的控件与回调。 */
    public static final class Widgets {
        /** 待分析的 jar 或依赖目录。 */
        public JTextField target;
        public JButton chooseTarget;
        public JFileChooser targetChooser;
        /** 源码工程 pom.xml。 */
        public JTextField pomPath;
        public JButton choosePom;
        public JFileChooser pomChooser;
        /** 反编译输出目录。 */
        public JTextField outputDir;
        public JButton chooseOutput;
        public JFileChooser outputChooser;
        /** 要单独反编译的类名；留空表示整包反编译。 */
        public JTextField className;

        /** 本地依赖分析（秒级）。 */
        public JButton analyzeLocal;
        /** 运行外部引擎构建数据库（分钟级）。 */
        public JButton runEngine;
        public JCheckBox quickMode;
        public JCheckBox innerJars;
        public JTextField timeoutSeconds;
        /** 数据库查询。 */
        public JComboBox<String> queryKind;
        public JTextField keyword;
        public JButton query;
        /** 反编译（内置 CFR）。 */
        public JButton decompile;
        /** 打开输出目录。 */
        public JButton openOutput;

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

    /** 本页控件的默认实例：初值、候选列表与对话框都留在视图类内。 */
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

        widgets.outputDir = new JTextField("", 34);
        widgets.chooseOutput = new JButton("选择输出目录");
        widgets.outputChooser = new JFileChooser();
        widgets.outputChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        widgets.outputChooser.setDialogTitle("选择反编译输出目录");

        widgets.className = new JTextField("", 34);
        widgets.analyzeLocal = new JButton("本地依赖分析");
        widgets.runEngine = new JButton("调用链分析");
        widgets.quickMode = new JCheckBox("快速模式", false);
        widgets.innerJars = new JCheckBox("解析嵌套 jar", true);
        widgets.timeoutSeconds = new JTextField("300", 8);
        widgets.queryKind = new JComboBox<String>(new String[]{
                "总览", "入口点", "Sink 命中", "字符串常量", "组件清单"});
        widgets.keyword = new JTextField("", 18);
        widgets.query = new JButton("查询数据库");
        widgets.decompile = new JButton("反编译");
        widgets.openOutput = new JButton("打开输出目录");
        widgets.copy = new JButton("复制报告");
        widgets.status = new JLabel("本地分析为秒级、不需要外部程序；调用链分析需要自备引擎 jar");
        widgets.progress = new JProgressBar();
        widgets.output = new JTextArea();
        widgets.jumps = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        return widgets;
    }

    private AnalyzePage() {
    }

    public static JPanel build(Widgets widgets, UiKit.FontSink sink) {
        JPanel page = UiKit.page();
        page.add(UiKit.pageHeading("ANALYZE", "漏洞分析",
                "读依赖坐标 → 判定已知漏洞 → 需要时深挖调用链与源码  ·  仅分析你选择的文件", sink),
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
        UiKit.styleField(widgets.className, sink);
        row = plainRow(form, c, row, "指定类名（可选）",
                widgets.className, "留空则整包反编译；填 com.example.Foo 只反编译该类", sink);

        row = fileRow(form, c, row, "反编译输出目录", widgets.outputDir,
                widgets.chooseOutput, "留空则用工作目录下的 decompiled", sink);

        row = timeoutRow(form, c, row, widgets, sink);

        c.gridx = 0; c.gridy = row; c.gridwidth = 3; c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(8, 0, 0, 0);
        JPanel actions = new JPanel(new BorderLayout(12, 0));
        actions.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        buttons.setOpaque(false);
        UiKit.stylePrimaryButton(widgets.analyzeLocal, sink);
        UiKit.styleSecondaryButton(widgets.runEngine, sink);
        UiKit.styleSecondaryButton(widgets.decompile, sink);
        UiKit.styleSecondaryButton(widgets.openOutput, sink);
        UiKit.styleSwitch(widgets.quickMode, sink);
        UiKit.styleSwitch(widgets.innerJars, sink);
        buttons.add(widgets.analyzeLocal);
        buttons.add(widgets.runEngine);
        buttons.add(widgets.decompile);
        buttons.add(widgets.openOutput);
        buttons.add(widgets.quickMode);
        buttons.add(widgets.innerJars);
        actions.add(buttons, BorderLayout.WEST);
        form.add(actions, c);
        row++;

        c.gridy = row; c.insets = new Insets(10, 0, 0, 0);
        JPanel queryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        queryRow.setOpaque(false);
        UiKit.styleCombo(widgets.queryKind, 12, sink);
        UiKit.styleField(widgets.keyword, sink);
        UiKit.styleSecondaryButton(widgets.query, sink);
        queryRow.add(UiKit.label("数据库查询", Font.BOLD, 13, UiKit.TEXT, sink));
        queryRow.add(widgets.queryKind);
        queryRow.add(widgets.keyword);
        queryRow.add(widgets.query);
        JLabel queryHint = UiKit.label("字符串常量检索填关键字；其它查询忽略", Font.PLAIN, 12, UiKit.MUTED, sink);
        queryRow.add(queryHint);
        form.add(queryRow, c);
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

    /** 目标 / pom / 输出目录一行：只读路径框 + 选择按钮。 */
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
        JLabel hintLabel = UiKit.label(hint, Font.PLAIN, 12, UiKit.MUTED, sink);
        c.gridx = 2; c.weightx = 0; c.fill = GridBagConstraints.NONE; c.insets = new Insets(0, 12, 8, 0);
        form.add(hintLabel, c);
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
        text.append("本地分析读的是构建产物里的元数据：").append(System.lineSeparator());
        text.append("  META-INF/maven/**/pom.properties  （最准，标为「高」可信度）").append(System.lineSeparator());
        text.append("  MANIFEST.MF 的 Implementation-*  （标为「中」）").append(System.lineSeparator());
        text.append("  jar 文件名                        （标为「低」，版本可能不准）").append(System.lineSeparator());
        text.append(System.lineSeparator());
        text.append("需要确认某条链是否真的可达时，再点「调用链分析」——它会调用外部引擎"
                + "构建调用图数据库，耗时可能到分钟级，并会真实占用 CPU。").append(System.lineSeparator());
        text.append(System.lineSeparator());
        text.append("「反编译」用内置 CFR，不需要额外安装任何程序；只对可疑点使用即可，"
                + "整包反编译会产出大量源码文件。").append(System.lineSeparator());
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
