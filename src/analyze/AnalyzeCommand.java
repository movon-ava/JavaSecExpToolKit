package analyze;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import analyzer.ScriptRunner;

/**
 * 组装分析要用的命令行。
 *
 * <p>单独成类而不是散在 {@link AnalyzeEngine} 里：命令行是本功能与外部程序之间的
 * **唯一契约**，参数名写错只会表现为「引擎没反应」，排查成本高。集中一处便于对照
 * 上游文档逐项核对，也便于自检直接断言拼出来的参数。
 */
public final class AnalyzeCommand {

    /** 内置数据库查询脚本在 JAR 内的路径。 */
    public static final String REPORT_RESOURCE = "/python/jar_report.py";

    /** 漏洞特征匹配脚本在 JAR 内的路径。 */
    public static final String SIGNATURE_RESOURCE = "/python/jar_signatures.py";

    /** 签名库在 JAR 内的路径：特征匹配脚本需要它才能判定。 */
    public static final String SIGNATURE_LIBRARY_RESOURCE = "/python/vuln_signatures.json";

    /** 反编译脚本在 JAR 内的路径（可选后端）。 */
    public static final String DECOMPILE_RESOURCE = "/python/jar_decompile.py";

    /** 利用路径查询的默认回溯深度；与脚本侧默认值保持一致。 */
    public static final int DEFAULT_PATH_DEPTH = 6;

    /**
     * 本工具版本号，写进机器可读报告，用于回答「这份结论是哪一版跑出来的」。
     *
     * <p>必须与 {@code src/pom.xml} 的 {@code <version>} 一致：
     * 两者漂移时导出里的版本会指向一个不存在的构建，因此由自检逐字比对。
     */
    public static final String TOOL_VERSION = "0.1.0";

    private AnalyzeCommand() {
    }

    /**
     * 数据库查询命令。
     *
     * @param python    Python 解释器
     * @param script    已释放到临时目录的 jar_report.py
     * @param database  jar-analyzer.db 路径
     * @param query     查询标识（summary / entries / sinks / strings / components）
     * @param keyword   字符串检索关键字；非字符串查询传空串
     * @param limit     每节最多条数
     */
    public static List<String> report(String python, Path script, Path database, String query,
                                      String keyword, int limit) {
        return report(python, script, database, query, keyword, limit, DEFAULT_PATH_DEPTH);
    }

    /**
     * 数据库查询命令（可指定利用路径的回溯深度）。
     *
     * <p>深度只对「利用路径」查询有意义：它是沿调用边向上做可达性搜索的层数上限。
     * 放得太大时，大库上会把时间耗在远离入口的外围调用上，因此由调用方显式给出并设上限。
     *
     * @param depth 向上回溯的最大层数；越界时回落到 {@link #DEFAULT_PATH_DEPTH}
     */
    public static List<String> report(String python, Path script, Path database, String query,
                                      String keyword, int limit, int depth) {
        List<String> command = ScriptRunner.pythonCommand(python, script,
                "-db", database.toAbsolutePath().toString(),
                "-q", query == null ? "summary" : query,
                "-n", String.valueOf(limit <= 0 ? 40 : limit),
                "-d", String.valueOf(depth <= 0 ? DEFAULT_PATH_DEPTH : depth));
        if (keyword != null && !keyword.trim().isEmpty()) {
            command.add("-k");
            command.add(keyword.trim());
        }
        return command;
    }

    /**
     * 漏洞特征匹配命令。
     *
     * <p>与普通库查询分开：它需要额外指定签名库路径，而且输出包含判定结论而非纯事实，
     * 因此单独一个方法而不是给 report 加参数。
     *
     * @param script      已释放到临时目录的 jar_signatures.py
     * @param library     已释放到临时目录的 vuln_signatures.json
     * @param minSeverity 最低严重度（high / medium / low）；空串表示不过滤
     */
    public static List<String> signatures(String python, Path script, Path library,
                                          Path database, String minSeverity) {
        return signatures(python, script, library, database, minSeverity, null);
    }

    /**
     * 漏洞特征匹配命令（可同时导出一份机器可读 JSON）。
     *
     * <p>为什么把 JSON 导出放在同一条命令里而不是再跑一次：两次执行会存在时间差，
     * 文本报告与 JSON 的命中集合可能不一致，使用者对账时无从判断哪一份是对的。
     * 一次执行、一份结论、两种呈现。
     *
     * @param jsonOut 机器可读报告的落地路径；null 表示不导出
     */
    public static List<String> signatures(String python, Path script, Path library,
                                          Path database, String minSeverity, Path jsonOut) {
        return signatures(python, script, library, database, minSeverity, jsonOut, null, "",
                "");
    }

    /**
     * 漏洞特征匹配命令（带筛选项与可复现参数）。
     *
     * <p>筛选项做在脚本侧而不是界面按行过滤：报告的聚合、绕过手法筛选与
     * 「未命中清单」都依赖「本次参与了哪些特征」这个输入，界面过滤会把
     * 命中计数与清单割裂，使用者对不上账。
     *
     * @param evidenceSection  只保留某个证据来源（{@code strings} / {@code classes} /
     *                         {@code methods} / {@code sinks}）；空串表示不筛
     * @param toolVersion      写进导出的工具版本号；空串表示不写
     * @param baseline         上一次导出的机器可读报告路径；空串表示不做比对
     */
    public static List<String> signatures(String python, Path script, Path library,
                                          Path database, String minSeverity, Path jsonOut,
                                          String evidenceSection, String toolVersion,
                                          String baseline) {
        return signatures(python, script, library, database, minSeverity, jsonOut, evidenceSection,
                "", toolVersion, baseline);
    }

    /**
     * 漏洞特征匹配命令（另按漏洞类型筛选）。
     *
     * <p>类型筛选单独占一个参数而不合并进证据来源：两者是不同维度
     * 的问题（「这条命中靠什么证据」与「我只关心命令执行」），合并后无法同时表达。
     * 取值来自签名库（{@code --list-filters}），写死在界面侧必然与库漂移。
     *
     * @param onlyType 只保留该漏洞类型（类型 id）；空串表示不筛
     */
    public static List<String> signatures(String python, Path script, Path library,
                                          Path database, String minSeverity, Path jsonOut,
                                          String evidenceSection, String onlyType,
                                          String toolVersion, String baseline) {
        List<String> command = ScriptRunner.pythonCommand(python, script,
                "-db", database.toAbsolutePath().toString(),
                "-s", library.toAbsolutePath().toString());
        if (minSeverity != null && !minSeverity.trim().isEmpty()) {
            command.add("--min-severity");
            command.add(minSeverity.trim());
        }
        if (evidenceSection != null && !evidenceSection.trim().isEmpty()) {
            command.add("--only-section");
            command.add(evidenceSection.trim());
        }
        if (onlyType != null && !onlyType.trim().isEmpty()) {
            command.add("--only-type");
            command.add(onlyType.trim());
        }
        if (toolVersion != null && !toolVersion.trim().isEmpty()) {
            command.add("--tool-version");
            command.add(toolVersion.trim());
        }
        if (baseline != null && !baseline.trim().isEmpty()) {
            command.add("--baseline");
            command.add(baseline.trim());
        }
        if (jsonOut != null) {
            command.add("--json-out");
            command.add(jsonOut.toAbsolutePath().toString());
        }
        return command;
    }

    /**
     * 列出可用的筛选取值（供界面渲染下拉框）。
     *
     * <p>取值来自签名库，因此必须由脚本给出：在界面侧写死一份，库一改就会
     * 出现「下拉里的选项脚本不认」，只能靠报错发现。
     */
    public static List<String> listFilters(String python, Path script, Path library) {
        return ScriptRunner.pythonCommand(python, script,
                "-s", library.toAbsolutePath().toString(), "--list-filters");
    }

    /**
     * 外部反编译后端命令（jsd，需要 Node）。
     *
     * <p>默认不启用：本机已有 CFR（运行期依赖里自带），无需 Node 与额外 bundle。
     * 保留这条路径是为了「目标 class 用了 CFR 还原不出来的新语法」这类情况。
     */
    public static List<String> nodeDecompile(String nodeExe, Path script, Path jar, String className,
                                             Path output) {
        List<String> command = new ArrayList<String>();
        command.add(nodeExe);
        command.add(script.toAbsolutePath().toString());
        command.add("--jar");
        command.add(jar.toAbsolutePath().toString());
        if (className != null && !className.trim().isEmpty()) {
            command.add("--class");
            command.add(className.trim());
        }
        command.add("--out");
        command.add(output.toAbsolutePath().toString());
        return command;
    }
}
