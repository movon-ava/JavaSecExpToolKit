package analyze;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import analyzer.Decompiler;
import analyzer.DependencyScanner;
import analyzer.GadgetInventory;
import analyzer.GadgetRule;
import analyzer.GadgetRuleFile;
import analyzer.EngineRunner;
import analyzer.PomScanner;
import analyzer.ReportReader;
import analyzer.ScriptRunner;
import analyzer.VulnerabilityAnalyzer;
import analyzer.VulnerabilityRules;

/**
 * 漏洞分析的总编排：本地规则打底、外部引擎补深度、反编译做定点确认。
 *
 * <p>为什么不把这些步骤合成一个方法一次跑完：三件事的**代价差异极大**。
 * 本地依赖分析是秒级、纯本地、无副作用；外部引擎构建数据库是分钟级、要起子进程、
 * 会在工作目录里写库文件；反编译会产出大量源码文件。
 * 把它们分开，使用者可以先秒级拿到结论，只在需要时再付出分钟级代价。
 *
 * <p>本类不碰任何 Swing 控件：界面只提交参数、接收 {@link AnalyzeReport}。
 * 因此它可以在无界面环境下被自检直接驱动。
 */
public final class AnalyzeEngine {

    /** 单个分析动作的默认超时（秒）：本地动作应远快于此，给足余量防卡死。 */
    public static final int DEFAULT_LOCAL_TIMEOUT = 60;

    /** 外部引擎的最小超时下限：低于此值大 jar 必然跑不完，提前拦下并提示。 */
    public static final int MIN_ENGINE_TIMEOUT = 30;

    /** 数据库查询输出条数上限。 */
    public static final int QUERY_LIMIT = 60;

    private AnalyzeEngine() {
    }

    /**
     * 功能页 key → 建议按钮文字。
     *
     * <p>映射写在这里而不是让界面自己判断：结论里的 navKey 是 analyzer 包产出的，
     * 谁能跳、跳过去叫什么，应该与产出结论的地方保持一致。
     */
    public static String navLabel(String navKey) {
        if (navKey == null) return "";
        if ("fastjson.detect".equals(navKey)) return "去探测";
        if ("payload.build".equals(navKey)) return "去生成载荷";
        if ("payload.preset".equals(navKey)) return "去预设链";
        if ("payload.tostring".equals(navKey)) return "去 toString 链";
        if ("payload.oobjar".equals(navKey)) return "去带外 Jar";
        if ("shiro.exploit".equals(navKey)) return "去 Shiro 利用";
        if ("service.servers".equals(navKey)) return "去恶意服务器";
        if ("capture".equals(navKey)) return "去抓包转换";
        return "打开 " + navKey;
    }

    /**
     * 分析一个 jar 或目录：逐个读元数据、跑规则、出报告。
     *
     * @param target  jar 文件或依赖目录
     */
    public static AnalyzeReport analyzeTarget(Path target) {
        return analyzeTarget(target, java.util.Collections.<GadgetRule>emptyList(), "");
    }

    /**
     * 分析一个 jar 或目录，并可用外部规则文件扩充 gadget 判定。
     *
     * <p>外部规则的作用是「不改代码就能分析更多 gadget」：规则文件的类型与依赖名
     * 完全由使用者提供，本工具只负责按坐标与版本区间核对依赖是否齐备。
     * 文件里的问题**如实写进报告**而不是静默跳过——一条写错的规则会让使用者
     * 以为「这条链判定过了、目标没有」，而实际上它根本没被加载。
     *
     * @param target     jar 文件或依赖目录
     * @param extraRules 外部 gadget 规则；空表示只用内置规则表
     * @param ruleSource 外部规则文件路径，仅用于报告里标注来源；可空
     */
    public static AnalyzeReport analyzeTarget(Path target, List<GadgetRule> extraRules,
                                              String ruleSource) {
        long started = System.currentTimeMillis();
        if (target == null || !Files.exists(target)) {
            return AnalyzeReport.failed("本地依赖分析", "请先选择一个 jar 文件或依赖目录。");
        }
        try {
            VulnerabilityAnalyzer.Result result = VulnerabilityAnalyzer.analyze(target);
            String text = VulnerabilityAnalyzer.render(result, target.toString());
            // gadget 段直接拼进报告：依赖清单的价值有一半在「能拿它做什么」，
            // 使用者不必自己把组件名翻成链
            StringBuilder extra = new StringBuilder(text);
            extra.append(System.lineSeparator())
                    .append(GadgetInventory.render(result.dependencies, extraRules));
            if (extraRules != null && !extraRules.isEmpty()) {
                extra.append(System.lineSeparator()).append("外部 gadget 规则: ")
                        .append(extraRules.size()).append(" 条")
                        .append(ruleSource == null || ruleSource.isEmpty()
                                ? "" : "（来源 " + ruleSource + "）")
                        .append(System.lineSeparator());
            }
            List<String> hints = VulnerabilityAnalyzer.hints(result.dependencies);
            if (!hints.isEmpty()) {
                extra.append(System.lineSeparator()).append("===== 组合提示 =====")
                        .append(System.lineSeparator());
                for (String hint : hints) {
                    extra.append("  - ").append(hint).append(System.lineSeparator());
                }
            }
            text = extra.toString();
            return AnalyzeReport.of("本地依赖分析", text, true,
                    System.currentTimeMillis() - started, result.findings);
        } catch (IOException | RuntimeException error) {
            return AnalyzeReport.failed("本地依赖分析", "分析失败：" + error.getMessage());
        }
    }

    /**
     * 读外部 gadget 规则文件。
     *
     * <p>返回值里的问题清单必须交给报告：规则文件是本工具唯一由使用者手写的输入，
     * 写错一行就会静默少判一条链，因此解析问题必须与结论一起呈现。
     */
    public static GadgetRuleFile.Parsed loadGadgetRules(String path) {
        if (path == null || path.trim().isEmpty()) {
            return new GadgetRuleFile.Parsed(new ArrayList<GadgetRule>(), new ArrayList<String>());
        }
        try {
            return GadgetRuleFile.parse(Paths.get(path.trim()));
        } catch (IOException | RuntimeException error) {
            List<String> problems = new ArrayList<String>();
            problems.add("读取外部 gadget 规则文件失败：" + error.getMessage());
            return new GadgetRuleFile.Parsed(new ArrayList<GadgetRule>(), problems);
        }
    }

    /**
     * 解析源码工程的 pom.xml，列出声明的依赖。
     *
     * <p>与 {@link #analyzeTarget} 是互补口径：那边看的是构建产物里「实际打包了什么」，
     * 这边看的是工程「声明要什么」。两者不一致（被 exclusions 排除、profile 未激活、
     * jar 被裁剪）本身就是有用的排查线索。
     */
    public static AnalyzeReport analyzePom(Path pom) {
        return analyzePom(pom, java.util.Collections.<GadgetRule>emptyList());
    }

    /** 解析 pom.xml，并可用外部规则文件扩充 gadget 判定。 */
    public static AnalyzeReport analyzePom(Path pom, List<GadgetRule> extraRules) {
        long started = System.currentTimeMillis();
        if (pom == null || !Files.isRegularFile(pom)) {
            return AnalyzeReport.failed("pom.xml 分析", "请选择一个 pom.xml 文件。");
        }
        List<analyzer.Dependency> dependencies = PomScanner.dependencies(pom);
        if (dependencies.isEmpty()) {
            return AnalyzeReport.text("pom.xml 分析",
                    "没有读到任何 <dependencies> 声明。\n"
                            + "确认该文件是工程 pom（而不是仅声明 <parent> 的聚合 pom），"
                            + "且父 pom 里的依赖不会被这里列出。",
                    false, System.currentTimeMillis() - started);
        }
        List<analyzer.Finding> findings = VulnerabilityAnalyzer.judge(dependencies);
        StringBuilder text = new StringBuilder();
        text.append("pom.xml 依赖分析").append(System.lineSeparator());
        text.append("文件: ").append(pom).append(System.lineSeparator());
        text.append("声明依赖: ").append(dependencies.size()).append(" 个")
                .append("　命中结论: ").append(findings.size()).append(" 条")
                .append(System.lineSeparator()).append(System.lineSeparator());
        text.append("===== 声明的依赖 =====").append(System.lineSeparator());
        for (analyzer.Dependency dependency : dependencies) {
            text.append("  ").append(dependency.coordinate())
                    .append("　(").append(dependency.evidence).append(")")
                    .append(System.lineSeparator());
        }
        text.append(System.lineSeparator()).append("===== 结论 =====").append(System.lineSeparator());
        if (findings.isEmpty()) {
            text.append("未命中任何已知规则。").append(System.lineSeparator());
        }
        for (analyzer.Finding finding : findings) {
            text.append("[").append(finding.confidence.label()).append("] ")
                    .append(finding.component).append(System.lineSeparator());
            text.append("    结论: ").append(finding.title).append(System.lineSeparator());
            text.append("    说明: ").append(finding.detail).append(System.lineSeparator());
            text.append("    建议: ").append(finding.suggestion).append(System.lineSeparator());
        }
        text.append(System.lineSeparator())
                .append(GadgetInventory.render(dependencies, extraRules))
                .append(System.lineSeparator())
                .append("注意：这里读的是**声明**，实际生效版本还可能被 dependencyManagement、")
                .append("父 pom 或 profile 覆盖；以运行时实际加载的依赖为准。")
                .append(System.lineSeparator());
        return AnalyzeReport.of("pom.xml 分析", text.toString(), true,
                System.currentTimeMillis() - started, findings);
    }

    /**
     * 运行外部引擎构建调用链数据库。
     *
     * @param engineJar    引擎 jar（使用者自备）
     * @param target       待分析 jar 或目录
     * @param workDir      工作目录，数据库落在这里
     * @param quick        快速模式
     * @param innerJars    解析嵌套 jar（fat jar 需要）
     * @param timeoutSeconds 超时上限
     */
    public static AnalyzeReport runEngine(Path engineJar, Path target, Path workDir, boolean quick,
                                          boolean innerJars, int timeoutSeconds, String javaExe) {
        if (engineJar == null || !Files.isRegularFile(engineJar)) {
            return AnalyzeReport.failed("调用链分析",
                    "请先在配置页填写「外部引擎 JAR」的路径（jar-analyzer-engine 的 jar）。\n"
                            + "未配置时仍可使用「本地依赖分析」，它不需要任何外部程序。");
        }
        if (target == null || !Files.exists(target)) {
            return AnalyzeReport.failed("调用链分析", "请先选择要分析的 jar 文件或目录。");
        }
        if (timeoutSeconds < MIN_ENGINE_TIMEOUT) {
            return AnalyzeReport.failed("调用链分析",
                    "分析超时至少 " + MIN_ENGINE_TIMEOUT + " 秒：构建数据库是分钟级任务，"
                            + "超时过短会每次都被强制终止。");
        }
        EngineRunner.Result result = EngineRunner.build(javaExe, engineJar, target, workDir,
                quick, innerJars, timeoutSeconds);
        if (!result.ok) {
            return AnalyzeReport.text("调用链分析",
                    result.output + System.lineSeparator() + "耗时 "
                            + (result.millis / 1000) + " 秒", false, result.millis);
        }
        StringBuilder text = new StringBuilder();
        text.append("调用链分析完成").append(System.lineSeparator());
        text.append("目标: ").append(target).append(System.lineSeparator());
        text.append("数据库: ").append(result.database).append(System.lineSeparator());
        text.append("耗时: ").append(result.millis / 1000).append(" 秒")
                .append(quick ? "（快速模式）" : "（完整模式）").append(System.lineSeparator())
                .append(System.lineSeparator());
        text.append("下一步：用上方的查询下拉框读取数据库（总览 / 入口点 / Sink 命中 / 字符串）。")
                .append(System.lineSeparator());
        if (!result.output.trim().isEmpty()) {
            text.append(System.lineSeparator()).append("===== 引擎输出 =====")
                    .append(System.lineSeparator()).append(result.output);
        }
        return AnalyzeReport.text("调用链分析", text.toString(), true, result.millis);
    }

    /**
     * 查询已构建的数据库。
     *
     * @param script  已释放的 jar_report.py
     * @param python  Python 解释器
     */
    public static AnalyzeReport query(Path database, Path script, String python, String queryId,
                                      String keyword, int timeoutSeconds) {
        if (!ReportReader.available(database)) {
            return AnalyzeReport.failed("数据库查询",
                    "找不到 " + EngineRunner.DATABASE_NAME + "：" + database
                            + "\n请先执行一次「调用链分析」。");
        }
        ReportReader.Query query = ReportReader.Query.of(queryId);
        if (query == null) {
            return AnalyzeReport.failed("数据库查询", "未知查询类型：" + queryId);
        }
        long started = System.currentTimeMillis();
        List<String> command = AnalyzeCommand.report(python, script, database, query.id(),
                keyword, QUERY_LIMIT);
        String output = ScriptRunner.run(command, timeoutSeconds <= 0 ? DEFAULT_LOCAL_TIMEOUT : timeoutSeconds);
        long millis = System.currentTimeMillis() - started;
        return AnalyzeReport.text("数据库查询 · " + query.label(), output, true, millis);
    }

    /**
     * 漏洞特征匹配：把库里的代码特征与内置签名库对照，
     * 给出可能的漏洞类型与该类型下常见的绕过手法。
     *
     * <p>与 {@link #query} 分开的理由：库查询输出的是事实（有哪些 sink、有哪些字符串），
     * 本方法输出的是判定（这些事实像什么漏洞）。两者的输出契约与演进节奏都不同。
     *
     * @param script  已释放的 jar_signatures.py
     * @param library     已释放的 vuln_signatures.json
     * @param minSeverity 最低严重度（high / medium / low）；空串表示不过滤
     */
    public static AnalyzeReport signatures(Path database, Path script, Path library, String python,
                                          String minSeverity, int timeoutSeconds) {
        if (!ReportReader.available(database)) {
            return AnalyzeReport.failed("漏洞特征匹配",
                    "找不到 " + EngineRunner.DATABASE_NAME + "：" + database
                            + "\n请先执行一次「调用链分析」。");
        }
        long started = System.currentTimeMillis();
        List<String> command = AnalyzeCommand.signatures(python, script, library, database,
                minSeverity);
        String output = ScriptRunner.run(command,
                timeoutSeconds <= 0 ? DEFAULT_LOCAL_TIMEOUT : timeoutSeconds);
        long millis = System.currentTimeMillis() - started;
        return AnalyzeReport.text("漏洞特征匹配", output, true, millis);
    }

    /**
     * 反编译目标到指定目录（内置 CFR）。
     *
     * @param timeoutSeconds 预算上限；CFR 是同步 API 无法中途打断，超预算时结果会被标注但仍保留产物
     */
    public static AnalyzeReport decompile(Path jar, String className, Path output, int timeoutSeconds) {
        if (jar == null || !Files.exists(jar)) {
            return AnalyzeReport.failed("反编译", "请先选择要反编译的 jar 文件。");
        }
        Decompiler.Result result = (className == null || className.trim().isEmpty())
                ? Decompiler.decompile(jar, output, timeoutSeconds)
                : Decompiler.decompileClass(jar, className, output, timeoutSeconds);
        StringBuilder text = new StringBuilder();
        if (result.ok()) {
            text.append("反编译完成").append(System.lineSeparator());
        } else {
            text.append("反编译未完全成功").append(System.lineSeparator());
            text.append("原因: ").append(result.error).append(System.lineSeparator());
        }
        text.append("源码文件: ").append(result.classCount).append(" 个").append(System.lineSeparator());
        text.append("输出目录: ").append(result.directory).append(System.lineSeparator());
        text.append("耗时: ").append(result.millis).append(" ms").append(System.lineSeparator());
        text.append(System.lineSeparator())
                .append("用编辑器打开输出目录查看源码，用于确认规则命中的链是否真的可达。")
                .append(System.lineSeparator());
        return AnalyzeReport.text("反编译", text.toString(), result.ok(), result.millis);
    }

    /** 规则库摘要，供界面在标题下显示「本次用的是哪一版规则」。 */
    public static String rulesSummary() {
        return "内置规则 " + VulnerabilityRules.count() + " 条"
                + "　本地分析为秒级、不需要任何外部程序";
    }

    /** 递归找出目录下的 jar，供界面展示「将要分析哪些文件」。 */
    public static List<Path> jarsOf(Path root) {
        try {
            return DependencyScanner.jarsIn(root);
        } catch (IOException | RuntimeException error) {
            return new ArrayList<Path>();
        }
    }

    /** 反编译输出目录的默认值：工作目录下的 decompiled。 */
    public static Path defaultDecompileDir(Path workDir) {
        Path base = workDir == null ? Paths.get(".") : workDir;
        return base.resolve("decompiled");
    }

    /** 探查结果摘要，供自检与界面显示「哪一步做了什么」。 */
    public static Map<String, String> capabilities() {
        Map<String, String> capabilities = new LinkedHashMap<String, String>();
        capabilities.put("本地依赖分析", "读 pom.properties / pom.xml / MANIFEST + 文件名推断，秒级");
        capabilities.put("规则判定", VulnerabilityRules.count() + " 条内置规则");
        capabilities.put("调用链分析", "需在配置页指定 jar-analyzer-engine 的 jar");
        capabilities.put("数据库查询", "Python 标准库 sqlite3，只读");
        capabilities.put("反编译", "内置 CFR（随运行期依赖提供），无需 Node");
        capabilities.put("漏洞特征匹配",
                "字符串常量 / 类名 / 方法名 / sink 调用与签名库对照，给出漏洞类型与绕过手法");
        capabilities.put("可用 gadget",
                GadgetInventory.ruleCount() + " 条内置规则，按 Maven 坐标 + 版本区间判定；"
                        + "可用外部规则文件补充（gadget.dat 语法）");
        return Collections.unmodifiableMap(capabilities);
    }
}
