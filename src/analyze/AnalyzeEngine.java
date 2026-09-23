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
import analyzer.Finding;
import analyzer.ToolkitLocator;
import analyzer.PomScanner;
import analyzer.ReportReader;
import analyzer.ScriptRunner;
import analyzer.VulnerabilityAnalyzer;
import analyzer.VulnerabilityRules;

/**
 * 漏洞分析的总编排：本地规则打底、**复用 jar-analyzer 的结果补深度**、反编译做定点确认。
 *
 * <p>为什么不自研字节码分析与调用链推导：那件事 jar-analyzer 做了五年、覆盖
 * ASM 字节码解析 / 调用图 / 继承与多态 / Spring 路由 / DFS 调用链 / CFG / 污点分析，
 * 自研版本在**准确性与覆盖面**上都不可能在短期内追上，而它们恰恰是这个工具
 * 后来要接的利用动作的前提。本工具的力气应该花在「把分析结果接上利用」上。
 * 因此这里的定位是**编排与二次利用**：调用后端、读它的库、把结论翻成能直接下手的动作。
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
            AnalyzeReport report = AnalyzeReport.of("本地依赖分析", text, true,
                    System.currentTimeMillis() - started, result.findings);
            // 依赖口径的结论也可交接：它同样会给出「去哪个页面出载荷 / 检测」的建议，
            // 带上依据以后使用者到了目标页才知道为什么被带过来
            return report.withContext(AnalysisContext.of(
                    "本地依赖分析", "analyze.scan", target.toString(),
                    findingEvidence(result.findings),
                    "按构建产物里的依赖坐标与版本区间得到的疑似结论。",
                    "本地构建产物（" + result.scannedJars + " 个 jar / "
                            + result.dependencies.size() + " 个组件）",
                    limitsForLocalScan()));
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
     * 调用外部后端 jar-analyzer 构建调用链数据库。
     *
     * <p>后端位置由 {@link ToolkitLocator} 解析：配置页 > 环境变量 > 约定位置。
     * 这样使用者「顺手解压到项目目录」也能直接用，不必先手工填路径。
     *
     * @param configured  配置页填写的安装目录 / jar；可空
     * @param target      待分析 jar 或目录
     * @param workDir     工作目录，数据库落在这里
     * @param quick       快速模式（完整发行包不支持，报告里会写明按完整模式执行）
     * @param innerJars   解析嵌套 jar（fat jar 需要）
     * @param timeoutSeconds 超时上限
     */
    public static AnalyzeReport runEngine(String configured, Path target, Path workDir, boolean quick,
                                          boolean innerJars, int timeoutSeconds) {
        ToolkitLocator.Install install = ToolkitLocator.locate(configured);
        if (install == null) {
            StringBuilder text = new StringBuilder();
            text.append("没有找到 jar-analyzer，无法建库。").append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append("请做完下面任一件事后重试：").append(System.lineSeparator())
                    .append("  1. 在配置页填写「jar-analyzer 安装目录或 JAR」（")
                    .append("解压出来的 jar-analyzer-x.y-windows-full 目录，或它的主 jar）；")
                    .append(System.lineSeparator())
                    .append("  2. 设置环境变量 ").append(ToolkitLocator.ENV_HOME)
                    .append(" 指向安装目录；").append(System.lineSeparator())
                    .append("  3. 把它解压到本工具目录下（会自动识别）。")
                    .append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append("已经找过这些位置：").append(System.lineSeparator());
            for (String place : ToolkitLocator.searchedPlaces(configured)) {
                text.append("  - ").append(place).append(System.lineSeparator());
            }
            text.append(System.lineSeparator())
                    .append("不需要后端也能用的功能：「组件与漏洞」页的本地依赖分析。");
            return AnalyzeReport.failed("调用链分析", text.toString());
        }
        if (target == null || !Files.exists(target)) {
            return AnalyzeReport.failed("调用链分析", "请先选择要分析的 jar 文件或目录。");
        }
        if (timeoutSeconds < MIN_ENGINE_TIMEOUT) {
            return AnalyzeReport.failed("调用链分析",
                    "分析超时至少 " + MIN_ENGINE_TIMEOUT + " 秒：构建数据库是分钟级任务，"
                            + "超时过短会每次都被强制终止。");
        }
        EngineRunner.Result result = EngineRunner.build(install, target, workDir,
                quick, innerJars, timeoutSeconds);
        StringBuilder text = new StringBuilder();
        text.append("后端: ").append(install.describe()).append(System.lineSeparator());
        if (!result.ok) {
            text.append(result.output).append(System.lineSeparator())
                    .append("耗时 ").append(result.millis / 1000).append(" 秒");
            return AnalyzeReport.text("调用链分析", text.toString(), false, result.millis);
        }
        text.append("调用链分析完成").append(System.lineSeparator());
        text.append("===== 本次分析与数据来源 =====").append(System.lineSeparator());
        text.append("后端: ").append(install.describe()).append(System.lineSeparator());
        text.append("目标: ").append(target).append(System.lineSeparator());
        text.append("数据库: ").append(result.database).append(System.lineSeparator());
        text.append("库生成时间: ").append(databaseStamp(result.database)).append(System.lineSeparator());
        text.append("耗时: ").append(result.millis / 1000).append(" 秒")
                .append(quick ? "（请求快速模式）" : "（完整模式）").append(System.lineSeparator())
                .append(System.lineSeparator());
        // 分析结论必须可追溯到「哪一版工具、哪一个库」：
        // 换过后端版本后 schema 可能变，报告要能解释两次结果为什么不同
        text.append("说明：下面的查询与特征匹配都读这个库；结论只对这个输入有效。")
                .append(System.lineSeparator()).append(System.lineSeparator());
        text.append("下一步：用上方的查询下拉框读结果——先看「利用路径」找入口，看它是否命中外部入口；")
                .append(System.lineSeparator());
        text.append("再看「漏洞特征匹配」拿漏洞类型与绕过手法。");
        if (!result.output.trim().isEmpty()) {
            text.append(System.lineSeparator()).append(System.lineSeparator())
                    .append("===== 后端输出 =====").append(System.lineSeparator())
                    .append(result.output);
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
        return query(database, script, python, queryId, keyword, timeoutSeconds,
                AnalyzeCommand.DEFAULT_PATH_DEPTH);
    }

    /**
     * 查询已构建的数据库（可指定利用路径的回溯深度）。
     *
     * @param depth 「利用路径」查询向上回溯的最大层数；对其它查询无影响
     */
    public static AnalyzeReport query(Path database, Path script, String python, String queryId,
                                      String keyword, int timeoutSeconds, int depth) {
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
                keyword, QUERY_LIMIT, depth);
        ScriptRunner.Outcome outcome = ScriptRunner.runDetailed(command,
                timeoutSeconds <= 0 ? DEFAULT_LOCAL_TIMEOUT : timeoutSeconds);
        long millis = System.currentTimeMillis() - started;
        String output = outcome.output;
        // 脚本会在末尾给出「NAV|功能页key|按钮文字」：分析结论必须能一键接到利用动作，
        // 否则使用者看完报告还得自己回想「生成载荷在哪一页」
        String queryText = withoutNav(output);
        if (!outcome.ok()) {
            // 退出码非 0 表示这次查询没跑成（结构不兼容 / 参数错 / 查询失败）。
            // 不写明的话，界面上的空结果会被读成「目标干净」——这是最危险的误判
            queryText = failureBanner("数据库查询", outcome) + queryText;
        }
        AnalyzeReport report = AnalyzeReport.of("数据库查询 · " + query.label(), queryText,
                outcome.ok(), millis, navFindings(output));
        return report.withContext(AnalysisContext.of(
                "数据库查询 · " + query.label(), "analyze.chain",
                database.toAbsolutePath().toString(),
                evidenceLines(output),
                "查询「" + query.label() + "」得到的原始事实。",
                "数据库 " + database.getFileName() + "（" + databaseStamp(database) + "）",
                limitationsOf(query)));
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
        return signatures(database, script, library, python, minSeverity, timeoutSeconds, null);
    }

    /**
     * 漏洞特征匹配（可同时导出机器可读 JSON）。
     *
     * @param jsonOut 机器可读报告的落地路径；null 表示只出文本报告
     */
    public static AnalyzeReport signatures(Path database, Path script, Path library, String python,
                                          String minSeverity, int timeoutSeconds, Path jsonOut) {
        return signatures(database, script, library, python, minSeverity, timeoutSeconds, jsonOut,
                "", "", "");
    }

    /**
     * 漏洞特征匹配（可筛选、可导出、可与上一次同输入比对）。
     *
     * @param evidenceSection 只保留某个证据来源（strings / classes / methods / sinks）；空串不筛
     * @param toolVersion     写进机器可读导出的工具版本号；空串不写
     * @param baseline        上一次导出的报告路径，用于同输入比对；空串不比对
     */
    public static AnalyzeReport signatures(Path database, Path script, Path library, String python,
                                          String minSeverity, int timeoutSeconds, Path jsonOut,
                                          String evidenceSection, String toolVersion,
                                          String baseline) {
        return signatures(database, script, library, python, minSeverity, timeoutSeconds, jsonOut,
                evidenceSection, "", toolVersion, baseline);
    }

    /**
     * 漏洞特征匹配（另按漏洞类型筛选）。
     *
     * @param onlyType 只保留该漏洞类型（类型 id，来自 {@code --list-filters}）；
     *                 空串表示不筛
     */
    public static AnalyzeReport signatures(Path database, Path script, Path library, String python,
                                          String minSeverity, int timeoutSeconds, Path jsonOut,
                                          String evidenceSection, String onlyType, String toolVersion,
                                          String baseline) {
        if (!ReportReader.available(database)) {
            return AnalyzeReport.failed("漏洞特征匹配",
                    "找不到 " + EngineRunner.DATABASE_NAME + "：" + database
                            + "\n请先执行一次「调用链分析」。");
        }
        long started = System.currentTimeMillis();
        List<String> command = AnalyzeCommand.signatures(python, script, library, database,
                minSeverity, jsonOut, evidenceSection, onlyType, toolVersion, baseline);
        ScriptRunner.Outcome outcome = ScriptRunner.runDetailed(command,
                timeoutSeconds <= 0 ? DEFAULT_LOCAL_TIMEOUT : timeoutSeconds);
        long millis = System.currentTimeMillis() - started;
        String output = outcome.output;
        String text = withoutNav(output);
        if (jsonOut != null) {
            // 导出结果如实写进报告：写失败却显示成功，使用者会拿着不存在的文件去对接下游流程
            boolean written = Files.isRegularFile(jsonOut);
            text = text + System.lineSeparator() + System.lineSeparator()
                    + (written
                        ? "机器可读报告已导出: " + jsonOut.toAbsolutePath()
                        : "机器可读报告未写出，请检查导出目录是否可写：" + jsonOut.toAbsolutePath())
                    + System.lineSeparator();
        }
        if (!outcome.ok()) {
            // 与库查询同理：签名库报错或查询失败时，报告里的「未命中」不是结论
            text = failureBanner("漏洞特征匹配", outcome) + text;
        }
        AnalyzeReport report = AnalyzeReport.of("漏洞特征匹配", text, outcome.ok(), millis,
                navFindings(output));
        return report.withContext(AnalysisContext.of(
                "漏洞特征匹配", "analyze.chain", database.toAbsolutePath().toString(),
                evidenceLines(output),
                "把库里的代码特征与签名库对照后得到的疑似结论。",
                "数据库 " + database.getFileName() + "（" + databaseStamp(database) + "）",
                limitsForSignatures()));
    }

    /**
     * 机器可读报告的默认落地位置：工作目录下的 analyze-report.json。
     *
     * <p>与文本报告并存而不是替换：文本给人读，JSON 给下游流程读。
     */
    public static Path defaultJsonReport(Path workDir) {
        Path base = workDir == null ? Paths.get(".") : workDir;
        return base.resolve("analyze-report.json");
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

    /**
     * 从脚本输出里摘出证据行，供分析上下文引用。
     *
     * <p>只取「看起来是结论」的行，不做语义解析：证据摘要是给人看的线索，
     * 不是数据契约；把它做成结构化字段会让报告措辞变成接口协议，改一次措辞就失效。
     * 上限 12 条，避免上下文横幅被长报告淹没。
     */
    public static List<String> evidenceLines(String output) {
        List<String> evidence = new ArrayList<String>();
        if (output == null || output.isEmpty()) return evidence;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("NAV|")) continue;
            // 只收带标记的结论行，避免把整段报告塞进上下文
            boolean marked = trimmed.startsWith("★") || trimmed.startsWith("[")
                    || trimmed.contains("命中") || trimmed.startsWith("状态:");
            if (!marked) continue;
            evidence.add(trimmed.length() > 120 ? trimmed.substring(0, 117) + "..." : trimmed);
            if (evidence.size() >= 12) break;
        }
        return evidence;
    }

    /** 各类查询的局限说明：不同查询没做的事情不一样，不能共用一句。 */
    public static List<String> limitationsOf(ReportReader.Query query) {
        List<String> limits = new ArrayList<String>();
        limits.add("只读查询：不修改数据库，也不重新分析目标。");
        if (query == ReportReader.Query.PATHS) {
            limits.add("调用图回溯只能说明「存在调用关系」，不能证明参数受外部控制。");
            limits.add("未命中入口不代表外部不可达：可能只是回溯深度不够或入口未被识别。");
        } else if (query == ReportReader.Query.SINKS) {
            limits.add("sink 命中只说明「调用了敏感方法」，不说明参数可控。");
        } else if (query == ReportReader.Query.STRINGS) {
            limits.add("常量命中需要人工判断是真凭据还是占位符。");
        } else if (query == ReportReader.Query.IMPLS) {
            limits.add("实现关系不代表运行期一定会走到该实现类。");
        }
        limits.add("本引擎不产出污点分析，因此不判断过滤器可否被绕过。");
        return limits;
    }

    /** 依赖口径分析的局限说明。 */
    static List<String> limitsForLocalScan() {
        List<String> limits = new ArrayList<String>();
        limits.add("只读构建产物的元数据，不做字节码分析，也不判断代码路径是否可达。");
        limits.add("版本来自包内元数据；fat jar 裁剪、依赖覆盖都会改变运行时实际加载的版本。");
        limits.add("结论一律为「疑似」：组件存在不等于漏洞成立，需人工确认。");
        return limits;
    }

    /**
     * 把结论清单压成简短的证据行。
     *
     * <p>只取前若干条：交接条是给人看线索用的，不是结论的完整副本。
     */
    static List<String> findingEvidence(List<Finding> findings) {
        List<String> evidence = new ArrayList<String>();
        if (findings == null) return evidence;
        for (Finding finding : findings) {
            evidence.add("[" + finding.status.label() + "] " + finding.component
                    + " — " + finding.title);
            if (evidence.size() >= 8) break;
        }
        return evidence;
    }

    /** 特征匹配的局限说明。 */
    public static List<String> limitsForSignatures() {
        List<String> limits = new ArrayList<String>();
        limits.add("静态特征命中一律记为「疑似」，不等于漏洞成立。");
        limits.add("扫描未命中不等于目标干净：未收录的特征与业务逻辑缺陷都需要人工确认。");
        limits.add("本引擎不产出污点分析，绕过手法为知识提示，需对照目标代码核对。");
        limits.add("数据库缺表或查询失败属于「未知」，不能读成「目标没有问题」。");
        return limits;
    }

    /** 数据库文件的时间与大小，用于在报告里标注「这份结论是哪个库出的」。 */
    static String databaseStamp(Path database) {
        if (database == null || !Files.isRegularFile(database)) return "（库文件不存在）";
        try {
            java.nio.file.attribute.FileTime time = Files.getLastModifiedTime(database);
            long size = Files.size(database);
            return time.toString() + "　" + (size / 1024) + " KB";
        } catch (IOException unreadable) {
            return "（读取失败：" + unreadable.getMessage() + "）";
        }
    }

    /**
     * 从脚本输出里取出后续动作提示，转成界面的跳转按钮。
     *
     * <p>脚本用固定前缀 {@code NAV|key|文字} 表达「这一步之后该去哪个功能页」。
     * 用前缀而不是让界面反解中文报告：报告措辞随时会改，而按钮不该跟着失效。
     */
    static List<Finding> navFindings(String output) {
        List<Finding> findings = new ArrayList<Finding>();
        if (output == null || output.isEmpty()) return findings;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("NAV|")) continue;
            String[] parts = trimmed.split("\\|", 3);
            if (parts.length < 3 || parts[1].trim().isEmpty()) continue;
            String navKey = parts[1].trim();
            findings.add(new Finding("", "NAV", parts[2].trim(),
                    "分析结论指向的后续利用动作。", "点击按钮继续", Finding.Confidence.HIGH,
                    "", navKey));
        }
        return findings;
    }

    /**
     * 去掉报告正文里的 NAV 行。
     *
     * <p>这些行已经渲染成按钮了，留在正文里只会让使用者看到一行
     * {@code NAV|payload.build|去生成载荷} 而不知其意。
     */
    static String withoutNav(String output) {
        if (output == null || output.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        for (String line : output.split("\\R")) {
            if (line.trim().startsWith("NAV|")) continue;
            text.append(line).append(System.lineSeparator());
        }
        return text.toString();
    }

    /**
     * 脚本没跑成时的提示横幅。
     *
     * <p>为什么必须显式写出来：退出码非 0、超时被终止这两种情况在报告里
     * 与「跑完了但没命中」长得一模一样——空结果。分析类工具最危险的误判
     * 就是把「查询没执行成功」读成「目标干净」，因此这里把状态放在正文最前面。
     */
    static String failureBanner(String title, ScriptRunner.Outcome outcome) {
        StringBuilder text = new StringBuilder();
        text.append("===== ").append(title).append(" 未成功 =====")
                .append(System.lineSeparator());
        text.append("状态: ").append(outcome.describe())
                .append(System.lineSeparator());
        text.append("说明: 下面的内容**不是结论**，本次没有得出任何判定；")
                .append(System.lineSeparator());
        text.append("      尤其不要把它读成「目标干净」——这只代表这次没有跑完。")
                .append(System.lineSeparator());
        text.append(System.lineSeparator());
        return text.toString();
    }

    /**
     * 列出可用的筛选取值（证据来源 / 漏洞类型 / 命中标签）。
     *
     * <p>取值来自签名库，因此必须问脚本而不是在界面写死：写死一份，
     * 库一改就出现「下拉里的选项脚本不认」，只能靠报错发现。
     * 返回空列表表示取不到（脚本或库缺失），由界面回落到「不筛选」。
     */
    public static List<String> listFilters(Path script, Path library, String python,
                                           int timeoutSeconds) {
        if (script == null || library == null) return new ArrayList<String>();
        List<String> command = AnalyzeCommand.listFilters(python, script, library);
        ScriptRunner.Outcome outcome = ScriptRunner.runDetailed(command,
                timeoutSeconds <= 0 ? DEFAULT_LOCAL_TIMEOUT : timeoutSeconds);
        List<String> lines = new ArrayList<String>();
        if (!outcome.ok()) return lines;
        for (String line : outcome.output.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("FILTER|")) continue;
            lines.add(trimmed.substring("FILTER|".length()));
        }
        return lines;
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
        capabilities.put("调用链分析",
                "复用 jar-analyzer 的结果：自动定位其安装（配置页 / 环境变量 / 约定位置）后建库");
        capabilities.put("数据库查询", "Python 标准库 sqlite3，只读");
        capabilities.put("利用路径",
                "把 sink 沿调用图向上反推，标出落在 Spring / JavaWeb 入口上的候选路径");
        capabilities.put("多态实现", "接口 / 父类方法对应的真实实现类，判断入口调用往哪走");
        capabilities.put("反编译", "内置 CFR（随运行期依赖提供），无需 Node");
        capabilities.put("漏洞特征匹配",
                "字符串常量 / 类名 / 方法名 / sink 调用与签名库对照，给出漏洞类型与绕过手法");
        capabilities.put("可用 gadget",
                GadgetInventory.ruleCount() + " 条内置规则，按 Maven 坐标 + 版本区间判定；"
                        + "可用外部规则文件补充（gadget.dat 语法）");
        return Collections.unmodifiableMap(capabilities);
    }
}
