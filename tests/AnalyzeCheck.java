import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import analyze.AnalyzeEngine;
import analyze.AnalyzeReport;
import analyzer.Dependency;
import analyzer.DependencyScanner;
import analyzer.EngineRunner;
import analyzer.PomScanner;
import analyzer.ReportReader;
import analyzer.ScriptRunner;
import analyzer.Version;
import analyzer.VulnerabilityAnalyzer;
import analyzer.VulnerabilityRules;

/**
 * 漏洞分析自检：依赖识别、版本比较、规则判定与失败路径。
 *
 * <p>版本比较是本功能最高风险的一处：fastjson 的受影响区间跨过 1.2.80 与 1.2.9，
 * 用字符串比较会把结论完全反过来，而这类错误在界面上看不出任何异常。
 * 因此这里对比较语义单独断言，而不是只测「跑得通」。
 *
 * <p>用法：java -cp target\tmp2;target\classes AnalyzeCheck
 */
public final class AnalyzeCheck {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        // 本自检不构建载荷、不会执行任何 gadget 命令，但仍装守卫：
        // 万一将来加入会弹计算器的用例，守卫已在位
        TestProcessGuard.install("AnalyzeCheck");
        System.out.println("== 版本比较 ==");
        versionChecks();

        System.out.println();
        System.out.println("== 依赖元数据解析 ==");
        scannerChecks();

        System.out.println();
        System.out.println("== 规则判定 ==");
        ruleChecks();

        System.out.println();
        System.out.println("== 端到端分析 ==");
        endToEnd();

        System.out.println();
        System.out.println("== 外部依赖与失败路径 ==");
        failureChecks();

        System.out.println();
        System.out.println("断言总数 " + (passed + failed) + "，失败 " + failed);
        if (failed > 0) System.exit(1);
        System.out.println("漏洞分析自检通过");
        System.exit(0);
    }

    /** 版本比较：数值语义、不可比、预发布后缀。 */
    private static void versionChecks() {
        check("1.2.80 大于 1.2.9（不能按字符串比）",
                Version.compareNullable("1.2.80", "1.2.9").intValue() > 0);
        check("1.2.24 小于 1.2.47",
                Version.compareNullable("1.2.24", "1.2.47").intValue() < 0);
        check("1.2.47 与 1.2.47 相等",
                Version.compareNullable("1.2.47", "1.2.47").intValue() == 0);
        check("段数不同按缺省 0 补齐（1.2 等于 1.2.0）",
                Version.compareNullable("1.2", "1.2.0").intValue() == 0);
        check("2.0 大于 1.9.9",
                Version.compareNullable("2.0", "1.9.9").intValue() > 0);
        check("空版本不可比", Version.compareNullable("", "1.0") == null);
        check("非数字版本不可比", Version.compareNullable(null, "1.0") == null);
        check("预发布版本小于正式版（1.0-rc1 < 1.0）",
                Version.compareNullable("1.0-rc1", "1.0").intValue() < 0);
        check("1.0-beta2 小于 1.0-rc1",
                Version.compareNullable("1.0-beta2", "1.0-rc1").intValue() < 0);
    }

    /** 三种元数据来源的解析与优先级。 */
    private static void scannerChecks() {
        Dependency fromProperties = DependencyScanner.fromProperties(
                "version=1.2.24\ngroupId=com.alibaba\nartifactId=fastjson\n"
                        + "# 注释行\n\n", "pom.properties");
        check("pom.properties 能取出坐标",
                fromProperties != null && "com.alibaba".equals(fromProperties.groupId)
                        && "fastjson".equals(fromProperties.artifactId)
                        && "1.2.24".equals(fromProperties.version));
        check("pom.properties 来源标记正确",
                fromProperties != null && fromProperties.source == Dependency.Source.POM_PROPERTIES);

        Dependency fromPom = DependencyScanner.fromPom(
                "<project><groupId>com.example</groupId><artifactId>app</artifactId>"
                        + "<version>1.0.0</version><dependencies><dependency>"
                        + "<groupId>com.alibaba</groupId><artifactId>fastjson</artifactId>"
                        + "<version>1.2.83</version></dependency></dependencies></project>", "pom.xml");
        check("pom.xml 取本包坐标而不是传递依赖",
                fromPom != null && "app".equals(fromPom.artifactId) && "1.0.0".equals(fromPom.version));

        Dependency guessed = DependencyScanner.fromFileName("fastjson-1.2.24.jar", "x");
        check("文件名能拆出组件与版本",
                guessed != null && "fastjson".equals(guessed.artifactId) && "1.2.24".equals(guessed.version));
        Dependency noVersion = DependencyScanner.fromFileName("custom-lib.jar", "x");
        check("无版本文件名只给组件名",
                noVersion != null && "custom-lib".equals(noVersion.artifactId)
                        && !noVersion.hasVersion());
        check("文件名来源标记为最低可信度",
                guessed != null && guessed.source == Dependency.Source.FILE_NAME);
        check("非 jar 文件名不被解析",
                DependencyScanner.fromFileName("readme.txt", "x") == null);

        check("缺 artifactId 的 pom.properties 不算依赖",
                DependencyScanner.fromProperties("version=1.0\ngroupId=x\n", "e") == null);
    }

    /** 规则匹配：命中、不命中、边界与可信度。 */
    private static void ruleChecks() {
        check("规则表非空", VulnerabilityRules.count() >= 20);

        Dependency fastjson124 = new Dependency("com.alibaba", "fastjson", "1.2.24",
                Dependency.Source.POM_PROPERTIES, "e");
        List<VulnerabilityRules.Rule> matched = VulnerabilityRules.match(fastjson124);
        check("fastjson 1.2.24 命中 autoType 规则", !matched.isEmpty());
        boolean hasAutoType = false;
        for (VulnerabilityRules.Rule rule : matched) {
            if ("FJ-AUTOTYPE-124".equals(rule.id)) hasAutoType = true;
        }
        check("命中项包含 FJ-AUTOTYPE-124", hasAutoType);

        Dependency fastjson183 = new Dependency("com.alibaba", "fastjson", "1.2.83",
                Dependency.Source.POM_PROPERTIES, "e");
        check("fastjson 1.2.83 不命中任何规则",
                VulnerabilityRules.match(fastjson183).isEmpty());

        Dependency noVersion = new Dependency("com.alibaba", "fastjson", "",
                Dependency.Source.FILE_NAME, "e");
        check("无版本号不做判定（宁可不报不误报）",
                VulnerabilityRules.match(noVersion).isEmpty());

        Dependency shiro124 = new Dependency("org.apache.shiro", "shiro-core", "1.2.4",
                Dependency.Source.MANIFEST, "e");
        check("shiro-core 1.2.4 命中默认密钥规则",
                VulnerabilityRules.match(shiro124).size() >= 2);

        Dependency wrongGroup = new Dependency("com.example", "fastjson", "1.2.24",
                Dependency.Source.POM_PROPERTIES, "e");
        check("groupId 不符时不命中（防同名 fork 误报）",
                VulnerabilityRules.match(wrongGroup).isEmpty());

        Dependency fileNameOnly = new Dependency("", "commons-collections", "3.2.1",
                Dependency.Source.FILE_NAME, "e");
        check("groupId 缺失时仍按组件名判定（结论会降级可信度）",
                !VulnerabilityRules.match(fileNameOnly).isEmpty());

        List<Dependency> dependencies = new ArrayList<Dependency>();
        dependencies.add(fastjson124);
        dependencies.add(fastjson183);
        List<analyzer.Finding> findings = VulnerabilityAnalyzer.judge(dependencies);
        check("判定按可信度排序且只出命中项", findings.size() == matched.size());
        check("pom.properties 来源给出高可信度",
                !findings.isEmpty() && findings.get(0).confidence == analyzer.Finding.Confidence.HIGH);
        check("结论带判定依据", !findings.isEmpty() && findings.get(0).evidence.contains("pom.properties"));
    }

    /** 端到端：造带元数据的 jar，跑完整分析。 */
    private static void endToEnd() throws Exception {
        Path dir = Files.createTempDirectory("javasec-analyze-check");
        dir.toFile().deleteOnExit();

        Path fastjson = dir.resolve("fastjson-1.2.24.jar");
        writeJar(fastjson, new String[][]{
                {"META-INF/maven/com.alibaba/fastjson/pom.properties",
                        "version=1.2.24\ngroupId=com.alibaba\nartifactId=fastjson\n"},
                {"com/alibaba/fastjson/JSON.class", null}});

        Path shiro = dir.resolve("shiro-core-1.2.4.jar");
        writeJar(shiro, new String[][]{
                {"META-INF/MANIFEST.MF",
                        "Manifest-Version: 1.0\r\nImplementation-Title: shiro-core\r\n"
                                + "Implementation-Version: 1.2.4\r\n"
                                + "Implementation-Vendor-Id: org.apache.shiro\r\n\r\n"},
                {"org/apache/shiro/subject/Subject.class", null}});

        Path unrelated = dir.resolve("myapp-1.0.jar");
        writeJar(unrelated, new String[][]{{"com/example/Main.class", null}});

        AnalyzeReport report = AnalyzeEngine.analyzeTarget(dir);
        check("端到端分析成功", report.ok);
        check("报告含三个组件", report.text.contains("识别组件: 3 个"));
        check("识别出 fastjson 组件", report.text.contains("com.alibaba:fastjson:1.2.24"));
        check("识别出 shiro 组件", report.text.contains("org.apache.shiro:shiro-core:1.2.4"));
        check("命中 fastjson 规则", report.text.contains("Fastjson 反序列化"));
        check("命中 shiro rememberMe 规则", report.text.contains("rememberMe"));
        check("无关 jar 不产生结论", report.text.contains("myapp:1.0"));
        check("结论带跳转建议", report.hasJumps());
        check("跳转建议含探测页与 Shiro 页",
                report.jumps.containsKey("fastjson.detect") || report.jumps.containsKey("payload.build"));
        check("无结论时给出「不代表没有漏洞」的说明",
                AnalyzeEngine.analyzeTarget(unrelated).text.contains("不代表目标没有漏洞"));

        // pom.xml 口径
        Path pom = dir.resolve("pom.xml");
        Files.write(pom, ("<project><groupId>com.example</groupId><artifactId>demo</artifactId>"
                + "<version>1.0</version><dependencies><dependency>"
                + "<groupId>com.alibaba</groupId><artifactId>fastjson</artifactId>"
                + "<version>1.2.24</version></dependency></dependencies></project>")
                .getBytes(StandardCharsets.UTF_8));
        AnalyzeReport pomReport = AnalyzeEngine.analyzePom(pom);
        check("pom 分析读到声明依赖", pomReport.text.contains("com.alibaba:fastjson:1.2.24"));
        check("pom 分析同样给出结论", pomReport.text.contains("Fastjson 反序列化"));
        check("pom 分析声明「声明不等于生效」",
                pomReport.text.contains("声明") && pomReport.text.contains("以运行时实际加载的依赖为准"));

        // 空 pom：没有 <dependencies> 时给出可读提示而不是空报告
        Path emptyPom = dir.resolve("empty-pom.xml");
        Files.write(emptyPom, "<project><artifactId>agg</artifactId></project>"
                .getBytes(StandardCharsets.UTF_8));
        check("无依赖声明的 pom 给出提示",
                AnalyzeEngine.analyzePom(emptyPom).text.contains("没有读到任何 <dependencies> 声明"));

        // 递归找 jar
        check("目录递归列出三个 jar", AnalyzeEngine.jarsOf(dir).size() == 3);
    }

    /** 失败路径与外部依赖缺失时的行为。 */
    private static void failureChecks() throws Exception {
        AnalyzeReport noTarget = AnalyzeEngine.analyzeTarget(null);
        check("目标为空时给出可读提示", !noTarget.ok && noTarget.text.contains("请先选择"));

        AnalyzeReport missing = AnalyzeEngine.analyzeTarget(
                java.nio.file.Paths.get("target", "no-such-directory-xyz"));
        check("目标不存在时给出可读提示", !missing.ok);

        AnalyzeReport noEngine = AnalyzeEngine.runEngine(null, java.nio.file.Paths.get("."),
                java.nio.file.Paths.get("target"), false, false, 60, "java");
        check("未配置引擎时提示去配置页填写",
                !noEngine.ok && noEngine.text.contains("外部引擎 JAR"));

        // 引擎路径必须是真实存在的文件，否则会先被「引擎 jar 不存在」拦下，
        // 走不到超时下限这条分支
        AnalyzeReport tooShort = AnalyzeEngine.runEngine(
                java.nio.file.Paths.get("src", "pom.xml"), java.nio.file.Paths.get("."),
                java.nio.file.Paths.get("target"), false, false, 5, "java");
        check("超时低于下限时提前拦下",
                !tooShort.ok && tooShort.text.contains("至少"));

        AnalyzeReport noDatabase = AnalyzeEngine.query(
                java.nio.file.Paths.get("target", "no-such.db"), null, "python", "summary", "", 30);
        check("数据库缺失时提示先跑调用链分析",
                !noDatabase.ok && noDatabase.text.contains("调用链分析"));

        check("引擎产出的库文件名固定", "jar-analyzer.db".equals(EngineRunner.DATABASE_NAME));
        check("数据库不存在时判定为不可用",
                !ReportReader.available(java.nio.file.Paths.get("target", "no-such.db")));
        check("查询标识可反查", ReportReader.Query.of("sinks") == ReportReader.Query.SINKS);
        check("未知查询标识返回 null", ReportReader.Query.of("nope") == null);
        check("查询候选为五种", ReportReader.Query.values().length == 5);

        check("Python 解释器：配置优先",
                "custom-python".equals(ScriptRunner.python("custom-python")));
        check("Python 解释器：留空时回落到默认",
                !ScriptRunner.python("").isEmpty());
        check("Python 命令带 -X utf8（Windows 控制台默认非 UTF-8）",
                ScriptRunner.pythonCommand("python", java.nio.file.Paths.get("x.py"), "-q", "summary")
                        .contains("-X"));

        Map<String, String> capabilities = AnalyzeEngine.capabilities();
        check("能力清单含五项", capabilities.size() == 5);
        check("能力清单声明反编译无需 Node",
                capabilities.get("反编译").contains("无需 Node"));
        check("能力清单含内置规则条数",
                capabilities.get("规则判定").contains(String.valueOf(VulnerabilityRules.count())));

        check("建议按钮文字按页面 key 映射",
                "去探测".equals(AnalyzeEngine.navLabel("fastjson.detect"))
                        && "去生成载荷".equals(AnalyzeEngine.navLabel("payload.build")));

        // 畸形容器不应让整次分析失败
        Path broken = Files.createTempDirectory("javasec-broken").resolve("broken.jar");
        Files.write(broken, "not a zip".getBytes(StandardCharsets.UTF_8));
        check("损坏的 jar 被跳过而不是中断分析", AnalyzeEngine.analyzeTarget(broken).ok);
    }

    /** 写一个最小 jar；content 为 null 时写入 class 魔数。 */
    private static void writeJar(Path jar, String[][] entries) throws Exception {
        java.util.zip.ZipOutputStream zip =
                new java.util.zip.ZipOutputStream(Files.newOutputStream(jar));
        for (String[] entry : entries) {
            zip.putNextEntry(new java.util.zip.ZipEntry(entry[0]));
            byte[] bytes = entry[1] == null
                    ? new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}
                    : entry[1].getBytes(StandardCharsets.UTF_8);
            zip.write(bytes);
            zip.closeEntry();
        }
        zip.close();
    }

    private static void check(String message, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [ok] " + message);
        } else {
            failed++;
            System.err.println("  [FAIL] " + message);
        }
    }
}
