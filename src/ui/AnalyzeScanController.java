package ui;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import analyze.AnalyzeEngine;
import analyze.AnalyzeReport;

/**
 * 「组件与漏洞」页的行为：依赖口径的本地分析（秒级）。
 *
 * <p>只做一件事：选目标（jar / 依赖目录，可另附源码工程 pom.xml）→ 跑本地规则
 * → 回写报告。所有动作都在后台线程执行，界面更新一律回到事件分发线程。
 *
 * <p>与 {@link AnalyzeController}（调用链页）的分工：本页**不碰外部引擎**，
 * 因此永远秒级、无需任何额外程序；代价高的动作全部收在调用链页。
 */
public final class AnalyzeScanController {

    private final AnalyzeScanPage.Widgets widgets;
    private final UiKit.FontSink fonts;
    private final AnalyzeWorker worker;
    private final ConfigController config;

    public AnalyzeScanController(AnalyzeScanPage.Widgets widgets, UiKit.FontSink fonts,
                                 AnalyzeWorker.View view, Consumer<String> navigator,
                                 ConfigController config) {
        this.widgets = widgets;
        this.fonts = fonts;
        this.worker = new AnalyzeWorker(view, navigator);
        this.config = config;
    }

    /** 接线全部按钮：视图类只负责摆放控件，事件在这里绑定。 */
    public void bind() {
        widgets.chooseTarget.addActionListener(event -> chooseTarget());
        widgets.choosePom.addActionListener(event -> choosePom());
        widgets.run.addActionListener(event -> analyzeLocal());
    }

    /**
     * 应用配置页的默认值。
     *
     * <p>每次进页都调用：使用者在配置页改完路径回到本页应当立刻生效。
     */
    public void applyDefaults() {
        widgets.defaultDirectory = config.property("payload_export_dir", "").trim();
        if (widgets.target.getText().trim().isEmpty()) {
            widgets.target.setText(config.property("analyze_scan_target", "").trim());
        }
        widgets.status.setText(AnalyzeEngine.rulesSummary());
    }

    /** 选择分析目标：jar 文件或整个依赖目录。 */
    private void chooseTarget() {
        widgets.targetChooser.setCurrentDirectory(directory());
        if (widgets.targetChooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.io.File chosen = widgets.targetChooser.getSelectedFile();
        if (chosen != null) widgets.target.setText(chosen.getAbsolutePath());
    }

    private void choosePom() {
        widgets.pomChooser.setCurrentDirectory(directory());
        if (widgets.pomChooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.io.File chosen = widgets.pomChooser.getSelectedFile();
        if (chosen != null) widgets.pomPath.setText(chosen.getAbsolutePath());
    }

    /**
     * 本地依赖分析：秒级、纯本地。
     *
     * <p>填了 pom.xml 就顺带一起分析：两者的输入通常是同一个工程，
     * 让使用者点两次没有意义。
     */
    private void analyzeLocal() {
        if (worker.isBusy()) return;
        final String targetText = widgets.target.getText().trim();
        final String pomText = widgets.pomPath.getText().trim();
        if (targetText.isEmpty() && pomText.isEmpty()) {
            widgets.status.setText(AnalyzeScanPage.NO_TARGET_HINT);
            return;
        }
        // 外部 gadget 规则在点击时读取一次：规则文件由使用者手工维护，
        // 每次分析都重读才能让「改完直接重跑」生效，而不必重启工具
        final analyzer.GadgetRuleFile.Parsed parsed =
                AnalyzeEngine.loadGadgetRules(config.property("analyze_gadget_rules", "").trim());
        worker.run("本地依赖分析", new AnalyzeWorker.Task() {
            @Override
            public AnalyzeReport run() {
                AnalyzeReport report = null;
                if (!targetText.isEmpty()) {
                    report = AnalyzeEngine.analyzeTarget(Paths.get(targetText), parsed.rules,
                            config.property("analyze_gadget_rules", "").trim());
                }
                if (!pomText.isEmpty()) {
                    AnalyzeReport pomReport = AnalyzeEngine.analyzePom(Paths.get(pomText), parsed.rules);
                    report = merge(report, pomReport);
                }
                if (report != null && !parsed.problems.isEmpty()) {
                    report = withRuleProblems(report, parsed.problems);
                }
                return report;
            }
        });
    }

    /**
     * 把外部规则文件的解析问题追加到报告末尾。
     *
     * <p>不能只写进状态行：状态行会被下一次动作覆盖，而规则写错是本工具唯一
     * 由使用者手写的输入，必须留在报告里可以回看。
     */
    private static AnalyzeReport withRuleProblems(AnalyzeReport report, List<String> problems) {
        StringBuilder text = new StringBuilder(report.text);
        text.append(System.lineSeparator()).append("===== 外部 gadget 规则文件的问题 =====")
                .append(System.lineSeparator());
        for (String problem : problems) {
            text.append("  - ").append(problem).append(System.lineSeparator());
        }
        text.append("  以上规则未生效：写错的规则不会参与判定，"
                + "因此「某条链缺失」的结论可能只是规则没被读到。").append(System.lineSeparator());
        return AnalyzeReport.of("本地依赖分析", text.toString(), report.ok, report.millis,
                report.findings);
    }

    /** 停止本页占用的资源。 */
    public void shutdown() {
        worker.shutdown();
    }

    /** 合并两份报告：本地分析与 pom 分析的结论都保留，跳转取并集。 */
    private static AnalyzeReport merge(AnalyzeReport first, AnalyzeReport second) {
        if (first == null) return second;
        if (second == null) return first;
        List<analyzer.Finding> findings = new ArrayList<analyzer.Finding>();
        findings.addAll(first.findings);
        findings.addAll(second.findings);
        String text = first.text + System.lineSeparator() + System.lineSeparator() + second.text;
        return AnalyzeReport.of("本地依赖分析", text, first.ok || second.ok,
                first.millis + second.millis, findings);
    }

    /** 选择对话框的初始目录：优先配置里的导出目录，其次用户目录。 */
    private java.io.File directory() {
        String candidate = widgets.defaultDirectory;
        if (candidate != null && !candidate.isEmpty()) {
            java.io.File file = new java.io.File(candidate);
            if (file.isDirectory()) return file;
        }
        return new java.io.File(System.getProperty("user.home"));
    }

    /** 供装配层渲染跳转按钮。 */
    static void fillJumps(AnalyzeScanPage.Widgets widgets, Map<String, String> jumps,
                          Consumer<String> onJump, UiKit.FontSink fonts) {
        List<java.awt.event.ActionListener> listeners = new ArrayList<java.awt.event.ActionListener>();
        List<String> labels = AnalyzeWorker.labels(jumps, listeners, onJump);
        AnalyzeScanPage.fillJumps(widgets.jumps, labels, listeners, fonts);
    }
}