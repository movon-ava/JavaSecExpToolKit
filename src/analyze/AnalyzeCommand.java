package analyze;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import analyzer.EngineRunner;
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
        List<String> command = ScriptRunner.pythonCommand(python, script,
                "-db", database.toAbsolutePath().toString(),
                "-q", query == null ? "summary" : query,
                "-n", String.valueOf(limit <= 0 ? 40 : limit));
        if (keyword != null && !keyword.trim().isEmpty()) {
            command.add("-k");
            command.add(keyword.trim());
        }
        return command;
    }

    /**
     * 外部引擎命令。
     *
     * <p>这里只做拼装与转发，真正的执行与超时处理在 {@link EngineRunner}：
     * 引擎需要「指定工作目录 + 超时终止进程树」，那部分逻辑不该在命令行构造里重复一遍。
     */
    public static List<String> engine(String javaExe, Path engineJar, Path target, boolean quick,
                                      boolean innerJars) {
        List<String> command = new ArrayList<String>();
        command.add(javaExe);
        command.add("-Dfile.encoding=UTF-8");
        command.add("-jar");
        command.add(engineJar.toAbsolutePath().toString());
        command.add("--jar");
        command.add(target.toAbsolutePath().toString());
        if (quick) command.add("--quick");
        if (innerJars) {
            command.add("--inner-jars");
            command.add("--fix-class");
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
        List<String> command = ScriptRunner.pythonCommand(python, script,
                "-db", database.toAbsolutePath().toString(),
                "-s", library.toAbsolutePath().toString());
        if (minSeverity != null && !minSeverity.trim().isEmpty()) {
            command.add("--min-severity");
            command.add(minSeverity.trim());
        }
        return command;
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
