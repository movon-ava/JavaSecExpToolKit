package ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
 * 漏洞分析 · 调用链查询页的行为：代码口径分析。
 *
 * <p>本页与「组件与漏洞」页是两个独立页面，因此这里只留代码口径的动作：
 * 建库（外部引擎，分钟级）、查询数据库（事实）、漏洞特征匹配（判定）、反编译（定点确认）。
 * 依赖口径的本地分析与 pom 分析在 {@link AnalyzeScanController}，两者的代价与输入都不同。
 *
 * <p>为什么全部走后台线程：调用链分析是分钟级、反编译会产出大量文件，
 * 放在事件分发线程会让界面假死，使用者会以为程序坏了。
 * 界面更新一律回到事件分发线程（由 {@link AnalyzeWorker} 负责）。
 */
public final class AnalyzeController {

    private final AnalyzeChainPage.Widgets widgets;
    private final UiKit.FontSink fonts;
    private final AnalyzeWorker worker;
    private final ConfigController config;

    /** 已构建的数据库路径：查询、特征匹配与反编译都从这里取上下文。 */
    private Path database;
    /** 已释放到本地的报告脚本（jar_report.py），避免每次动作都重复释放。 */
    private Path reportScript;
    /** 已释放到本地的特征匹配脚本（jar_signatures.py）。 */
    private Path signatureScript;
    /** 已释放到本地的签名库（vuln_signatures.json）。 */
    private Path signatureLibrary;

    public AnalyzeController(AnalyzeChainPage.Widgets widgets, UiKit.FontSink fonts,
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
        widgets.chooseOutput.addActionListener(event -> chooseOutput());
        widgets.runEngine.addActionListener(event -> runEngine());
        widgets.query.addActionListener(event -> query());
        widgets.signatures.addActionListener(event -> signatures());
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

    private void chooseOutput() {
        widgets.outputChooser.setCurrentDirectory(directory());
        if (widgets.outputChooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.io.File chosen = widgets.outputChooser.getSelectedFile();
        if (chosen != null) widgets.outputDir.setText(chosen.getAbsolutePath());
    }

    /** 调用外部引擎构建调用链数据库；耗时可能到分钟级，因此必须有超时。 */
    private void runEngine() {
        if (worker.isBusy()) return;
        final String targetText = widgets.target.getText().trim();
        if (targetText.isEmpty()) {
            widgets.status.setText("请先选择要分析的 jar 或依赖目录。");
            return;
        }
        final String engineText = config.property("analyze_engine_jar", "").trim();
        final String workDirText = config.property("analyze_work_dir", "").trim();
        final boolean quick = widgets.quickMode.isSelected();
        final boolean inner = widgets.innerJars.isSelected();
        final int timeout = parseTimeout(widgets.timeoutSeconds.getText(),
                config.property("analyze_timeout", "300"));
        if (timeout < AnalyzeEngine.MIN_ENGINE_TIMEOUT) {
            widgets.status.setText("调用链超时至少 " + AnalyzeEngine.MIN_ENGINE_TIMEOUT
                    + " 秒：构建数据库是分钟级任务。");
            return;
        }
        worker.run("调用链分析", new AnalyzeWorker.Task() {
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
        if (worker.isBusy()) return;
        final Path databasePath = database != null ? database : defaultDatabase();
        if (!ReportReader.available(databasePath)) {
            widgets.status.setText("还没有可查询的数据库，请先执行一次「调用链分析」。");
            return;
        }
        final String queryId = queryId(widgets.queryKind.getSelectedIndex());
        final String keyword = widgets.keyword.getText().trim();
        final String python = ScriptRunner.python(config.python());
        worker.run("数据库查询", new AnalyzeWorker.Task() {
            @Override
            public AnalyzeReport run() {
                Path script = script(reportScript, AnalyzeCommand.REPORT_RESOURCE);
                if (script == null) {
                    return AnalyzeReport.failed("数据库查询", "JAR 内缺少 jar_report.py 资源。");
                }
                reportScript = script;
                return AnalyzeEngine.query(databasePath, script, python, queryId, keyword,
                        AnalyzeEngine.DEFAULT_LOCAL_TIMEOUT);
            }
        });
    }

    /**
     * 漏洞特征匹配：把库里的代码特征与签名库对照，得出可能的漏洞类型与绕过手法。
     *
     * <p>与 {@link #query} 分开成独立按钮：查询回答「库里有什么」，本动作回答「这些事实像什么漏洞」。
     * 后者需要两份额外资源（脚本 + 签名库），释放失败时必须给出可读提示而不是静默不出结果。
     */
    private void signatures() {
        if (worker.isBusy()) return;
        final Path databasePath = database != null ? database : defaultDatabase();
        if (!ReportReader.available(databasePath)) {
            widgets.status.setText("还没有可查询的数据库，请先执行一次「调用链分析」。");
            return;
        }
        final String python = ScriptRunner.python(config.python());
        final String minSeverity = severity(widgets.minSeverity.getSelectedIndex());
        worker.run("漏洞特征匹配", new AnalyzeWorker.Task() {
            @Override
            public AnalyzeReport run() {
                Path script = script(signatureScript, AnalyzeCommand.SIGNATURE_RESOURCE);
                if (script == null) {
                    return AnalyzeReport.failed("漏洞特征匹配",
                            "JAR 内缺少 jar_signatures.py 资源。");
                }
                signatureScript = script;
                Path library = script(signatureLibrary, AnalyzeCommand.SIGNATURE_LIBRARY_RESOURCE);
                if (library == null) {
                    return AnalyzeReport.failed("漏洞特征匹配",
                            "JAR 内缺少 vuln_signatures.json 签名库。");
                }
                signatureLibrary = library;
                return AnalyzeEngine.signatures(databasePath, script, library, python, minSeverity,
                        AnalyzeEngine.DEFAULT_LOCAL_TIMEOUT * 2);
            }
        });
    }

    /** 反编译：内置 CFR，无额外程序依赖。 */
    private void decompile() {
        if (worker.isBusy()) return;
        final String targetText = widgets.target.getText().trim();
        if (targetText.isEmpty()) {
            widgets.status.setText("请先选择要反编译的 jar。");
            return;
        }
        final String className = widgets.className.getText().trim();
        final Path output = widgets.outputDir.getText().trim().isEmpty()
                ? AnalyzeEngine.defaultDecompileDir(defaultWorkDir())
                : Paths.get(widgets.outputDir.getText().trim());
        worker.run("反编译", new AnalyzeWorker.Task() {
            @Override
            public AnalyzeReport run() {
                return AnalyzeEngine.decompile(Paths.get(targetText), className, output,
                        AnalyzeEngine.DEFAULT_LOCAL_TIMEOUT * 5);
            }
        });
    }

    /** 打开反编译输出目录；没有则给出可读提示。 */
    private void openOutput() {
        String text = widgets.outputDir.getText().trim();
        Path directory = text.isEmpty()
                ? AnalyzeEngine.defaultDecompileDir(defaultWorkDir())
                : Paths.get(text);
        if (!Files.isDirectory(directory)) {
            widgets.status.setText("输出目录还不存在：" + directory);
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(directory.toFile());
                widgets.status.setText("已打开 " + directory);
            } else {
                widgets.status.setText("当前平台不支持自动打开，请手工访问：" + directory);
            }
        } catch (IOException | RuntimeException error) {
            widgets.status.setText("打开失败：" + error.getMessage() + "，请手工访问 " + directory);
        }
    }

    /** 停止本页占用的资源。 */
    public void shutdown() {
        worker.shutdown();
    }

    /** 释放脚本资源；失败返回 null，由调用方给出可读提示。 */
    private static Path script(Path cached, String resource) {
        if (cached != null && Files.isRegularFile(cached)) return cached;
        try {
            return ScriptRunner.extract(resource);
        } catch (IOException error) {
            util.Log.warn("释放分析脚本失败：" + resource + "（" + error.getMessage() + "）", error);
            return null;
        }
    }

    /** 引擎工作目录：与配置页「引擎工作目录」一致，留空时用用户目录下的默认位置。 */
    private Path defaultWorkDir() {
        String workDirText = config.property("analyze_work_dir", "").trim();
        return workDirText.isEmpty()
                ? Paths.get(System.getProperty("user.home"), ".JavaSecExpToolKit", "analyze")
                : Paths.get(workDirText);
    }

    /** 默认数据库位置：引擎工作目录下的 jar-analyzer.db。 */
    private Path defaultDatabase() {
        return defaultWorkDir().resolve(EngineRunner.DATABASE_NAME);
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

    /** 查询下拉框序号 -> 查询标识。 */
    static String queryId(int index) {
        String[] ids = {"summary", "entries", "sinks", "strings", "components"};
        if (index < 0 || index >= ids.length) return "summary";
        return ids[index];
    }

    /** 严重度下拉框序号 -> 过滤值；越界时回落到「不过滤」。 */
    static String severity(int index) {
        if (index < 0 || index >= AnalyzeChainPage.SEVERITY_VALUES.length) return "";
        return AnalyzeChainPage.SEVERITY_VALUES[index];
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

    /** 供装配层渲染跳转按钮。 */
    static void fillJumps(AnalyzeChainPage.Widgets widgets, Map<String, String> jumps,
                          Consumer<String> onJump, UiKit.FontSink fonts) {
        List<java.awt.event.ActionListener> listeners = new ArrayList<java.awt.event.ActionListener>();
        List<String> labels = AnalyzeWorker.labels(jumps, listeners, onJump);
        AnalyzeChainPage.fillJumps(widgets.jumps, labels, listeners, fonts);
    }

    /** 数据库查询脚本在 JAR 内的路径：直接引用编排层常量，避免两处各写一份。 */
    static final String REPORT_RESOURCE = AnalyzeCommand.REPORT_RESOURCE;
}