package ui;

import java.awt.Desktop;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import analyze.AnalyzeCommand;
import analyze.AnalyzeEngine;
import analyze.AnalyzeReport;
import analyzer.EngineRunner;
import analyzer.ReportReader;
import analyzer.ScriptRunner;

/**
 * 漏洞分析页的行为：把三类代价差异极大的动作分派出去，并在后台线程执行。
 *
 * <p>为什么全部走后台线程：本地分析虽然是秒级，但选中的依赖目录可能有上百个 jar；
 * 调用链分析更是分钟级。放在事件分发线程会让界面假死，使用者会以为程序坏了。
 * 界面更新一律回到事件分发线程（{@code SwingUtilities.invokeLater}）。
 *
 * <p>同一时刻只允许一个动作在跑：两个分析同时写同一个工作目录里的数据库会互相覆盖。
 */
public final class AnalyzeController {
    /** 页面回写：控制器不直接持有页面对象，与仓库既有约定一致。 */
    public interface View {
        void setStatus(String text);
        void setOutput(String text);
        void setBusy(boolean busy);
        /** 按报告里的建议渲染跳转按钮。 */
        void showJumps(Map<String, String> jumps, Consumer<String> onJump);
    }

    /** 一次分析最多给出的建议按钮数：太多会让工具条挤成一片。 */
    private static final int MAX_JUMPS = 6;

    private final AnalyzePage.Widgets widgets;
    private final UiKit.FontSink fonts;
    private final View view;
    private final Consumer<String> navigator;
    private final ConfigController config;

    /** 已构建的数据库路径：查询与反编译都从这里取上下文。 */
    private Path database;
    /** 已释放到本地的查询脚本，避免每次动作都重复释放。 */
    private Path reportScript;

    /** 是否有动作正在执行；用于拒绝并发。 */
    private volatile boolean busy;

    public AnalyzeController(AnalyzePage.Widgets widgets, UiKit.FontSink fonts, View view,
                             Consumer<String> navigator, ConfigController config) {
        this.widgets = widgets;
        this.fonts = fonts;
        this.view = view;
        this.navigator = navigator;
        this.config = config;
    }

    /** 接线全部按钮：视图类只负责摆放控件，事件在这里绑定。 */
    public void bind() {
        widgets.chooseTarget.addActionListener(event -> chooseTarget());
        widgets.choosePom.addActionListener(event -> choosePom());
        widgets.chooseOutput.addActionListener(event -> chooseOutput());
        widgets.analyzeLocal.addActionListener(event -> analyzeLocal());
        widgets.runEngine.addActionListener(event -> runEngine());
        widgets.query.addActionListener(event -> query());
        widgets.decompile.addActionListener(event -> decompile());
        widgets.openOutput.addActionListener(event -> openOutput());
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
        widgets.timeoutSeconds.setText(config.property("analyze_timeout", "300").trim());
        String decompileDir = config.property("analyze_decompile_dir", "").trim();
        if (widgets.outputDir.getText().trim().isEmpty()) {
            widgets.outputDir.setText(decompileDir);
        }
        widgets.status.setText(AnalyzeEngine.rulesSummary());
    }

    /** 选择分析目标：jar 文件或整个依赖目录。 */
    private void chooseTarget() {
        widgets.targetChooser.setCurrentDirectory(directory());
        if (widgets.targetChooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.io.File chosen = widgets.targetChooser.getSelectedFile();
        if (chosen == null) return;
        widgets.target.setText(chosen.getAbsolutePath());
        // 选了 fat jar 时把「解析嵌套 jar」默认打开：Spring Boot 的依赖全在 BOOT-INF/lib 下，
        // 不解析会一个第三方组件也识别不到
        if (chosen.isFile()) widgets.innerJars.setSelected(true);
    }

    private void choosePom() {
        widgets.pomChooser.setCurrentDirectory(directory());
        if (widgets.pomChooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.io.File chosen = widgets.pomChooser.getSelectedFile();
        if (chosen != null) widgets.pomPath.setText(chosen.getAbsolutePath());
    }

    private void chooseOutput() {
        widgets.outputChooser.setCurrentDirectory(directory());
        if (widgets.outputChooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.io.File chosen = widgets.outputChooser.getSelectedFile();
        if (chosen != null) widgets.outputDir.setText(chosen.getAbsolutePath());
    }

    /**
     * 本地依赖分析：秒级、纯本地。
     *
     * <p>填了 pom.xml 就顺带一起分析：两者的输入通常是同一个工程，
     * 让使用者点两次没有意义。
     */
    private void analyzeLocal() {
        if (busy) return;
        final String targetText = widgets.target.getText().trim();
        final String pomText = widgets.pomPath.getText().trim();
        if (targetText.isEmpty() && pomText.isEmpty()) {
            view.setStatus("请先选择分析目标（jar / 依赖目录）或 pom.xml。");
            return;
        }
        runInBackground("本地依赖分析", new Task() {
            @Override
            public AnalyzeReport run() {
                AnalyzeReport report = null;
                if (!targetText.isEmpty()) report = AnalyzeEngine.analyzeTarget(Paths.get(targetText));
                if (!pomText.isEmpty()) {
                    AnalyzeReport pomReport = AnalyzeEngine.analyzePom(Paths.get(pomText));
                    report = merge(report, pomReport);
                }
                return report;
            }
        });
    }

    /** 调用外部引擎构建调用链数据库；耗时可能到分钟级，因此必须有超时。 */
    private void runEngine() {
        if (busy) return;
        final String targetText = widgets.target.getText().trim();
        if (targetText.isEmpty()) {
            view.setStatus("请先选择要分析的 jar 或依赖目录。");
            return;
        }
        final String engineText = config.property("analyze_engine_jar", "").trim();
        final String workDirText = config.property("analyze_work_dir", "").trim();
        final boolean quick = widgets.quickMode.isSelected();
        final boolean inner = widgets.innerJars.isSelected();
        final int timeout = parseTimeout(widgets.timeoutSeconds.getText(),
                config.property("analyze_timeout", "300"));
        if (timeout < AnalyzeEngine.MIN_ENGINE_TIMEOUT) {
            view.setStatus("调用链超时至少 " + AnalyzeEngine.MIN_ENGINE_TIMEOUT
                    + " 秒：构建数据库是分钟级任务。");
            return;
        }
        runInBackground("调用链分析", new Task() {
            @Override
            public AnalyzeReport run() {
                Path engine = engineText.isEmpty() ? null : Paths.get(engineText);
                Path workDir = workDirText.isEmpty()
                        ? Paths.get(System.getProperty("user.home"), ".JavaSecExpToolKit", "analyze")
                        : Paths.get(workDirText);
                AnalyzeReport report = AnalyzeEngine.runEngine(engine, Paths.get(targetText), workDir,
                        quick, inner, timeout, EngineRunner.currentJava());
                if (report.ok) {
                    database = workDir.resolve(EngineRunner.DATABASE_NAME);
                    EngineRunner.cleanTemp(workDir);
                }
                return report;
            }
        });
    }

    /** 查询已构建的数据库：只读，走 Python 标准库 sqlite3。 */
    private void query() {
        if (busy) return;
        final Path databasePath = database != null ? database : defaultDatabase();
        if (!ReportReader.available(databasePath)) {
            view.setStatus("还没有可查询的数据库，请先执行一次「调用链分析」。");
            return;
        }
        final String queryId = queryId(widgets.queryKind.getSelectedIndex());
        final String keyword = widgets.keyword.getText().trim();
        final String python = ScriptRunner.python(config.python());
        runInBackground("数据库查询", new Task() {
            @Override
            public AnalyzeReport run() {
                Path script = script(AnalyzeCommand.REPORT_RESOURCE);
                if (script == null) {
                    return AnalyzeReport.failed("数据库查询", "JAR 内缺少 jar_report.py 资源。");
                }
                return AnalyzeEngine.query(databasePath, script, python, queryId, keyword,
                        AnalyzeEngine.DEFAULT_LOCAL_TIMEOUT);
            }
        });
    }

    /** 反编译：内置 CFR，无额外程序依赖。 */
    private void decompile() {
        if (busy) return;
        final String targetText = widgets.target.getText().trim();
        if (targetText.isEmpty()) {
            view.setStatus("请先选择要反编译的 jar。");
            return;
        }
        final String className = widgets.className.getText().trim();
        final Path output = widgets.outputDir.getText().trim().isEmpty()
                ? AnalyzeEngine.defaultDecompileDir(Paths.get(
                        System.getProperty("user.home"), ".JavaSecExpToolKit", "analyze"))
                : Paths.get(widgets.outputDir.getText().trim());
        runInBackground("反编译", new Task() {
            @Override
            public AnalyzeReport run() {
                return AnalyzeEngine.decompile(Paths.get(targetText), className, output,
                        AnalyzeEngine.DEFAULT_LOCAL_TIMEOUT * 5);
            }
        });
    }

    /** 打开反编译输出目录；没有则打开系统默认位置。 */
    private void openOutput() {
        String text = widgets.outputDir.getText().trim();
        Path directory = text.isEmpty()
                ? AnalyzeEngine.defaultDecompileDir(Paths.get(
                        System.getProperty("user.home"), ".JavaSecExpToolKit", "analyze"))
                : Paths.get(text);
        if (!Files.isDirectory(directory)) {
            view.setStatus("输出目录还不存在：" + directory);
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(directory.toFile());
                view.setStatus("已打开 " + directory);
            } else {
                view.setStatus("当前平台不支持自动打开，请手工访问：" + directory);
            }
        } catch (IOException | RuntimeException error) {
            view.setStatus("打开失败：" + error.getMessage() + "，请手工访问 " + directory);
        }
    }

    /** 停止本页占用的资源；当前没有常驻资源，保留接口以便后续扩展。 */
    public void shutdown() {
        busy = false;
    }

    // ------------------------------------------------------------------
    // 内部：任务执行与结果回写
    // ------------------------------------------------------------------

    /** 一次可执行的动作。 */
    private interface Task {
        AnalyzeReport run();
    }

    private void runInBackground(final String title, final Task task) {
        busy = true;
        view.setBusy(true);
        view.setStatus(title + " 执行中…");
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                AnalyzeReport report;
                try {
                    report = task.run();
                } catch (RuntimeException error) {
                    report = AnalyzeReport.failed(title, "执行失败：" + error.getMessage());
                }
                final AnalyzeReport result = report == null
                        ? AnalyzeReport.failed(title, "没有产生任何结果。") : report;
                javax.swing.SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        finish(title, result);
                    }
                });
            }
        }, "analyze-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void finish(String title, AnalyzeReport report) {
        busy = false;
        view.setBusy(false);
        String header = "===== " + title + " =====  " + (report.millis) + " ms"
                + System.lineSeparator() + System.lineSeparator();
        view.setOutput(header + report.text);
        if (report.ok) {
            view.setStatus(title + "完成（" + report.millis + " ms）"
                    + (report.hasJumps() ? "　可点击下方按钮继续利用" : ""));
        } else {
            view.setStatus(title + "未成功，原因见报告。");
        }
        view.showJumps(limit(report.jumps), new Consumer<String>() {
            @Override
            public void accept(String navKey) {
                if (navigator != null) navigator.accept(navKey);
            }
        });
    }

    /** 建议按钮按登记顺序截断，避免工具条被挤爆。 */
    private static Map<String, String> limit(Map<String, String> jumps) {
        Map<String, String> limited = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : jumps.entrySet()) {
            if (limited.size() >= MAX_JUMPS) break;
            limited.put(entry.getKey(), entry.getValue());
        }
        return limited;
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

    /** 释放脚本资源；失败返回 null，由调用方给出可读提示。 */
    private Path script(String resource) {
        if (reportScript != null && Files.isRegularFile(reportScript)) return reportScript;
        try {
            reportScript = ScriptRunner.extract(resource);
            return reportScript;
        } catch (IOException error) {
            return null;
        }
    }

    /** 默认数据库位置：引擎工作目录下的 jar-analyzer.db。 */
    private Path defaultDatabase() {
        String workDirText = config.property("analyze_work_dir", "").trim();
        Path workDir = workDirText.isEmpty()
                ? Paths.get(System.getProperty("user.home"), ".JavaSecExpToolKit", "analyze")
                : Paths.get(workDirText);
        return workDir.resolve(EngineRunner.DATABASE_NAME);
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

    /** 查询下拉框序号 → 查询标识。 */
    static String queryId(int index) {
        String[] ids = {"summary", "entries", "sinks", "strings", "components"};
        if (index < 0 || index >= ids.length) return "summary";
        return ids[index];
    }

    /** 读取超时输入；非法值回落到配置值，再回落到默认值。 */
    static int parseTimeout(String text, String fallback) {
        return parse(text, parse(fallback, 300));
    }

    private static int parse(String text, int fallback) {
        if (text == null) return fallback;
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    /** 数据库查询脚本在 JAR 内的路径：直接引用编排层常量，避免两处各写一份。 */
    static final String REPORT_RESOURCE = AnalyzeCommand.REPORT_RESOURCE;
}
