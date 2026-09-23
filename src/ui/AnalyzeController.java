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
import analyzer.ToolkitLocator;
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

    /**
     * 「漏洞类型」下拉框的取值，与控件下拉项一一对应。
     *
     * <p>为什么单独存一份 id：下拉框只能显示一个字符串，而脚本接受的是类型 id。
     * 把「显示名」当成「id」传会在类型名与 id 不同时静默筛空，只能靠报告里没结论发现。
     */
    private final java.util.List<String> typeIds = new java.util.ArrayList<String>();

    /** 类型选项是否已补全；已补全就不再起一个 Python 进程。 */
    private volatile boolean typeOptionsLoaded;
    /** 正在补全：防止反复进页叠起来几个查询线程。 */
    private volatile boolean typeOptionsLoading;

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
        widgets.status.setText(AnalyzeEngine.rulesSummary()
                + "　后端: " + backendSummary());
        loadTypeOptions();
    }

    /**
     * 后台补全「漏洞类型」下拉框的选项。
     *
     * <p>取值来自签名库，得起一个 Python 进程问一次（实测约 90 ms）。
     * 进页时同步执行，在解释器冷启动或磁盘忙时会卡几百毫秒；
     * 因此先把「全部类型」摆着，取到再补齐，取不到就保持不筛。
     *
     * <p>只取一次：签名库在运行期不会变，重复问只是白付进程启动的代价。
     */
    private void loadTypeOptions() {
        if (typeOptionsLoaded || typeOptionsLoading) return;
        typeOptionsLoading = true;
        Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<String> ids = new ArrayList<String>();
                final java.util.List<String> labels = new ArrayList<String>();
                try {
                    Path script = script(signatureScript, AnalyzeCommand.SIGNATURE_RESOURCE);
                    Path library = script(signatureLibrary,
                            AnalyzeCommand.SIGNATURE_LIBRARY_RESOURCE);
                    if (script != null && library != null) {
                        signatureScript = script;
                        signatureLibrary = library;
                        for (String row : AnalyzeEngine.listFilters(script, library,
                                ScriptRunner.python(config.python()),
                                AnalyzeEngine.DEFAULT_LOCAL_TIMEOUT)) {
                            // 行形如 type|deserialization|反序列化：证据来源另有一个下拉框，这里只收类型
                            String[] parts = row.split("\\|", 3);
                            if (parts.length == 3 && "type".equals(parts[0])) {
                                ids.add(parts[1]);
                                labels.add(parts[2]);
                            }
                        }
                    }
                } catch (RuntimeException error) {
                    util.Log.warn("读取漏洞类型选项失败：" + error.getMessage(), error);
                }
                if (ids.isEmpty()) {
                    // 取不到就不置一个筛不到东西的假选项；下次进页再试
                    typeOptionsLoading = false;
                    return;
                }
                javax.swing.SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        applyTypeOptions(ids, labels);
                    }
                });
            }
        }, "analyze-type-filters");
        loader.setDaemon(true);
        loader.start();
    }

    /** 把取到的类型选项写进下拉框；保留使用者已选中的一项。 */
    private void applyTypeOptions(java.util.List<String> ids, java.util.List<String> labels) {
        Object previous = widgets.onlyType.getSelectedItem();
        typeIds.clear();
        // 下拉框第 0 项是「全部类型」，对应空串：两者的下标必须始终对齐
        typeIds.add("");
        typeIds.addAll(ids);
        widgets.onlyType.removeAllItems();
        widgets.onlyType.addItem(AnalyzeChainPage.TYPE_ALL);
        for (String label : labels) widgets.onlyType.addItem(label);
        if (previous != null) {
            for (int i = 1; i < widgets.onlyType.getItemCount(); i++) {
                if (previous.equals(widgets.onlyType.getItemAt(i))) {
                    widgets.onlyType.setSelectedIndex(i);
                    break;
                }
            }
        }
        typeOptionsLoaded = true;
        typeOptionsLoading = false;
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
        // 后端位置交给定位器解析（配置页 > 环境变量 > 约定位置）：
        // 使用者「顺手解压到项目目录」也能直接用，不必先手工填路径
        final String engineText = config.property("analyze_backend_home",
                config.property("analyze_engine_jar", "")).trim();
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
                Path workDir = workDirText.isEmpty()
                        ? Paths.get(System.getProperty("user.home"), ".JavaSecExpToolKit", "analyze")
                        : Paths.get(workDirText);
                AnalyzeReport report = AnalyzeEngine.runEngine(engineText, Paths.get(targetText),
                        workDir, quick, inner, timeout);
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
        final int depth = parseTimeout(widgets.pathDepth.getText(),
                String.valueOf(AnalyzeCommand.DEFAULT_PATH_DEPTH));
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
                        AnalyzeEngine.DEFAULT_LOCAL_TIMEOUT, depth);
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
        final String evidenceSection =
                evidenceSection(widgets.evidenceSection.getSelectedIndex());
        final String onlyType = typeIdOf(typeIds, widgets.onlyType.getSelectedIndex());
        final boolean compare = widgets.compareBaseline.isSelected();
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
                // 机器可读报告按配置导出：文本给人读、JSON 给后续流程读。
                // 关掉时传 null，脚本便不会写文件——不做「配置关了还偷偷写」这种事。
                final boolean exportJson =
                        "true".equalsIgnoreCase(config.property("analyze_json_export", "true").trim());
                java.nio.file.Path jsonDir = null;
                if (exportJson) {
                    String configured = config.property("analyze_json_dir", "").trim();
                    jsonDir = configured.isEmpty()
                            ? AnalyzeEngine.defaultJsonReport(defaultWorkDir())
                            : Paths.get(configured).resolve("analyze-report.json");
                }
                // 与上一次同输入比对：基线就是上一份导出文件。
                // 关掉开关时显式不传，而不是「传了但脚本忽略」——两种状态在报告里必须能分辨
                final String baseline = compare && jsonDir != null
                        && java.nio.file.Files.isRegularFile(jsonDir)
                        ? jsonDir.toAbsolutePath().toString() : "";
                return AnalyzeEngine.signatures(databasePath, script, library, python, minSeverity,
                        AnalyzeEngine.DEFAULT_LOCAL_TIMEOUT * 2, jsonDir, evidenceSection, onlyType,
                        AnalyzeCommand.TOOL_VERSION, baseline);
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

    /**
     * 后端定位摘要：进页时就把「找到了没有、是哪个形态」摆在状态栏。
     *
     * <p>不等到点「调用链分析」才告知：使用者往往先点了按钮又等了几分钟才发现路径没配，
     * 提前显示可以省掉这一轮。
     */
    private String backendSummary() {
        String configured = config.property("analyze_backend_home",
                config.property("analyze_engine_jar", "")).trim();
        ToolkitLocator.Install install = ToolkitLocator.locate(configured);
        return install == null
                ? "未找到 jar-analyzer（可在配置页指定安装目录，或设置环境变量）"
                : install.describe();
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
        String[] ids = {"summary", "entries", "sinks", "paths", "impls", "strings", "components"};
        if (index < 0 || index >= ids.length) return "summary";
        return ids[index];
    }

    /** 严重度下拉框序号 -> 过滤值；越界时回落到「不过滤」。 */
    static String severity(int index) {
        if (index < 0 || index >= AnalyzeChainPage.SEVERITY_VALUES.length) return "";
        return AnalyzeChainPage.SEVERITY_VALUES[index];
    }

    /**
     * 证据来源下拉框序号 -> 分节名；越界时回落到「不过滤」。
     *
     * <p>回落到不过滤而不是回落到某一个分节：越界说明界面与脚本的取值已经漂移，
     * 此时筛掉一部分特征会让使用者拿到偏窄的结论，而看不出问题。
     */
    static String evidenceSection(int index) {
        if (index < 0 || index >= AnalyzeChainPage.EVIDENCE_VALUES.length) return "";
        return AnalyzeChainPage.EVIDENCE_VALUES[index];
    }

    /**
     * 漏洞类型下拉框序号 -> 类型 id。
     *
     * <p>序号 0 是「全部类型」，对应空串（不筛）。越界同样回落到不筛：
     * 宁可不筛，也不要因为界面与库漂移而惄惄窄化结论。
     */
    public static String typeIdOf(java.util.List<String> typeIds, int index) {
        if (typeIds == null || index < 0 || index >= typeIds.size()) return "";
        String id = typeIds.get(index);
        return id == null ? "" : id;
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