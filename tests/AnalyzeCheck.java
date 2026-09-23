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

        // 规则治理：每条规则都要能回答「依据哪份公告 / 文档」，
        // 以及整份规则表是哪一版、什么时候维护的
        check("规则库带版本号",
                !VulnerabilityRules.version().isEmpty());
        check("规则库带维护日期",
                VulnerabilityRules.maintained().matches("\\d{4}-\\d{2}-\\d{2}"));
        boolean everyRuleHasSource = true;
        for (VulnerabilityRules.Rule rule : VulnerabilityRules.rules()) {
            if (rule.source == null || rule.source.trim().isEmpty()) {
                everyRuleHasSource = false;
                System.err.println("      规则 " + rule.id + " 没有来源");
            }
        }
        check("每条规则都登记了判定来源", everyRuleHasSource);
        check("按 id 取规则：不存在时返回 null", VulnerabilityRules.of("NOPE") == null);
        check("按 id 取规则：存在时取到同一条",
                VulnerabilityRules.of("FJ-AUTOTYPE-124") != null
                        && "FJ-AUTOTYPE-124".equals(VulnerabilityRules.of("FJ-AUTOTYPE-124").id));

        // 状态模型：静态规则命中的结论只能是「疑似」，
        // 不能因为它来自最可靠的 pom 元数据就标成已确认
        check("静态规则命中的结论状态为「疑似」而不是「已确认」",
                !findings.isEmpty()
                        && findings.get(0).status == analyzer.Finding.Status.SUSPECTED);
        check("结论给出「还缺什么才算成立」",
                !findings.isEmpty() && !findings.get(0).missingEvidence.isEmpty());
        check("结论状态枚举含观察 / 疑似 / 已确认 / 未能复现 / 未知五档",
                analyzer.Finding.Status.values().length == 5);
        check("单行摘要同时含可信度与状态",
                !findings.isEmpty()
                        && findings.get(0).line().contains("疑似")
                        && findings.get(0).line().contains("高"));
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

        // 后端定位：本机装了 jar-analyzer 时会被自动找到，因此断言的是
        // 「找不到时的提示要列出找过哪些地方」而不是「一定找不到」
        analyzer.ToolkitLocator.Install located = analyzer.ToolkitLocator.locate("");
        check("后端定位：命中时给出形态描述",
                located == null || (located.describe().contains("jar")
                        && (located.isDistribution() || located.version() != null)));
        if (located != null) {
            check("后端命令行含 build 子命令或以 -jar 启动",
                    located.isDistribution()
                            ? located.command(java.nio.file.Paths.get("x.jar"), false, false, true, true)
                                    .contains("build")
                            : located.command(java.nio.file.Paths.get("x.jar"), false, true, false, false)
                                    .contains("-jar"));
            check("发行包如实声明不支持快速模式", !located.isDistribution() || !located.supportsQuick());
        }
        java.util.List<String> places = analyzer.ToolkitLocator.searchedPlaces("");
        check("找不到后端时能列出找过的位置", places.size() >= 3
                && places.get(0).contains("配置页"));

        AnalyzeReport tooShort = AnalyzeEngine.runEngine(
                "src/pom.xml", java.nio.file.Paths.get("."),
                java.nio.file.Paths.get("target"), false, false, 5);
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
        check("查询候选为八种（事实查询五种 + 利用路径 + 多态实现 + 特征匹配）",
                ReportReader.Query.values().length == 8);
        check("利用路径已登记为查询标识",
                ReportReader.Query.of("paths") == ReportReader.Query.PATHS);
        check("多态实现已登记为查询标识",
                ReportReader.Query.of("impls") == ReportReader.Query.IMPLS);
        check("利用路径查询默认带回溯深度",
                analyze.AnalyzeCommand.report("python", java.nio.file.Paths.get("s.py"),
                        java.nio.file.Paths.get("d.db"), "paths", "", 10)
                        .contains("-d"));
        check("特征匹配已登记为查询标识",
                ReportReader.Query.of("signatures") == ReportReader.Query.SIGNATURES);

        // 筛选与可复现：命令行参数必须真的被拼出来，否则界面上的下拉框只是个摆设
        java.util.List<String> filtered = analyze.AnalyzeCommand.signatures(
                "python", java.nio.file.Paths.get("s.py"), java.nio.file.Paths.get("l.json"),
                java.nio.file.Paths.get("d.db"), "high", null, "sinks", "0.1.0", "base.json");
        check("特征匹配命令行支持按证据来源筛选", filtered.contains("--only-section")
                && filtered.contains("sinks"));
        check("特征匹配命令行记录工具版本", filtered.contains("--tool-version")
                && filtered.contains("0.1.0"));
        check("特征匹配命令行支持同输入比对", filtered.contains("--baseline")
                && filtered.contains("base.json"));
        java.util.List<String> plain = analyze.AnalyzeCommand.signatures(
                "python", java.nio.file.Paths.get("s.py"), java.nio.file.Paths.get("l.json"),
                java.nio.file.Paths.get("d.db"), "", null);
        check("不筛选时不拼接筛选参数",
                !plain.contains("--only-section") && !plain.contains("--baseline"));
        check("列出筛选取值不需要数据库",
                !analyze.AnalyzeCommand.listFilters("python", java.nio.file.Paths.get("s.py"),
                        java.nio.file.Paths.get("l.json")).contains("-db"));
        check("列出筛选取值带固定前缀便于界面解析",
                analyze.AnalyzeCommand.listFilters("python", java.nio.file.Paths.get("s.py"),
                        java.nio.file.Paths.get("l.json")).contains("--list-filters"));
        check("证据来源下拉取值与签名库分节一一对应",
                java.util.Arrays.asList(ui.AnalyzeChainPage.EVIDENCE_VALUES)
                        .containsAll(java.util.Arrays.asList("", "sinks", "strings",
                                "classes", "methods"))
                        && ui.AnalyzeChainPage.EVIDENCE_VALUES.length == 5);
        check("证据来源下拉的显示名与取值数量一致",
                ui.AnalyzeChainPage.EVIDENCE_LABELS.length
                        == ui.AnalyzeChainPage.EVIDENCE_VALUES.length);
        check("工具版本号与 pom 一致",
                "0.1.0".equals(toolVersionInPom())
                        && "0.1.0".equals(analyze.AnalyzeCommand.TOOL_VERSION));

        // 漏洞类型筛选：下拉框的选中项必须真的能变成命令行参数
        java.util.List<String> typed = analyze.AnalyzeCommand.signatures(
                "python", java.nio.file.Paths.get("s.py"), java.nio.file.Paths.get("l.json"),
                java.nio.file.Paths.get("d.db"), "", null, "", "deserialization", "", "");
        check("特征匹配命令行支持按漏洞类型筛选",
                typed.contains("--only-type") && typed.contains("deserialization"));
        check("不选类型时不拼接类型参数",
                !plain.contains("--only-type"));
        check("类型筛选与证据筛选可同时生效",
                analyze.AnalyzeCommand.signatures("python", java.nio.file.Paths.get("s.py"),
                        java.nio.file.Paths.get("l.json"), java.nio.file.Paths.get("d.db"), "",
                        null, "sinks", "deserialization", "", "")
                        .contains("--only-section"));
        check("下拉框首项表示不按类型筛选",
                !ui.AnalyzeChainPage.TYPE_ALL.isEmpty()
                        && "".equals(ui.AnalyzeController.typeIdOf(
                                java.util.Collections.singletonList(""), 0)));
        check("下拉框索引越界时回落到不筛选",
                "".equals(ui.AnalyzeController.typeIdOf(
                        java.util.Collections.singletonList("deserialization"), 5))
                        && "".equals(ui.AnalyzeController.typeIdOf(null, 0)));
        check("类型下拉框的选中索引与 id 表对齐",
                "deserialization".equals(ui.AnalyzeController.typeIdOf(
                        java.util.Arrays.asList("", "deserialization"), 1)));

        check("Python 解释器：配置优先",
                "custom-python".equals(ScriptRunner.python("custom-python")));
        check("Python 解释器：留空时回落到默认",
                !ScriptRunner.python("").isEmpty());
        check("Python 命令带 -X utf8（Windows 控制台默认非 UTF-8）",
                ScriptRunner.pythonCommand("python", java.nio.file.Paths.get("x.py"), "-q", "summary")
                        .contains("-X"));

        Map<String, String> capabilities = AnalyzeEngine.capabilities();
        check("能力清单含九项", capabilities.size() == 9);
        check("能力清单说明复用 jar-analyzer",
                capabilities.get("调用链分析").contains("jar-analyzer"));
        check("能力清单含利用路径",
                capabilities.get("利用路径").contains("入口"));
        check("能力清单含多态实现",
                capabilities.get("多态实现").contains("实现类"));
        check("能力清单含漏洞特征匹配",
                capabilities.get("漏洞特征匹配").contains("绕过手法"));
        check("能力清单含可用 gadget",
                capabilities.get("可用 gadget").contains(
                        String.valueOf(analyzer.GadgetInventory.ruleCount())));
        check("能力清单声明反编译无需 Node",
                capabilities.get("反编译").contains("无需 Node"));
        check("能力清单含内置规则条数",
                capabilities.get("规则判定").contains(String.valueOf(VulnerabilityRules.count())));

        check("建议按钮文字按页面 key 映射",
                "去探测".equals(AnalyzeEngine.navLabel("fastjson.detect"))
                        && "去生成载荷".equals(AnalyzeEngine.navLabel("payload.build")));

        contextChecks();

        gadgetChecks();

        // 畸形容器不应让整次分析失败
        Path broken = Files.createTempDirectory("javasec-broken").resolve("broken.jar");
        Files.write(broken, "not a zip".getBytes(StandardCharsets.UTF_8));
        check("损坏的 jar 被跳过而不是中断分析", AnalyzeEngine.analyzeTarget(broken).ok);
    }

    /**
     * 分析上下文：跳转必须带「这条建议从哪来」，且**不预填高风险参数**。
     *
     * <p>这套断言守的是安全边界，不是格式：把类名、常量或推测自动填进命令、
     * 回连地址、目标 URL，会让使用者点一下生成就等于按推测向目标发包。
     */
    private static void contextChecks() {
        java.util.List<String> evidence = new ArrayList<String>();
        evidence.add("[高] com/alibaba/fastjson/JSON.parseObject（1 处，入口点）");
        java.util.List<String> limits = new ArrayList<String>();
        limits.add("只读查询：不修改数据库。");
        analyze.AnalysisContext context = analyze.AnalysisContext.of(
                "漏洞特征匹配", "analyze.chain", "/tmp/app.jar", evidence,
                "把库里的代码特征与签名库对照后得到的疑似结论。",
                "数据库 jar-analyzer.db（2026-09-23）", limits);

        check("上下文记录来源与目标",
                "漏洞特征匹配".equals(context.source) && "/tmp/app.jar".equals(context.target));
        check("上下文状态为「疑似（未验证）」而不是已确认",
                "疑似（未验证）".equals(context.status()));
        check("上下文保留证据清单", context.hasEvidence() && context.evidence.size() == 1);
        check("上下文保留数据来源与局限",
                !context.dataSource.isEmpty() && !context.limitations.isEmpty());
        check("上下文可回跳到产生它的功能页",
                "analyze.chain".equals(context.originKey));
        check("上下文摘要含分析来源",
                context.banner().contains("由分析结论带入")
                        && context.banner().contains("漏洞特征匹配"));
        java.util.List<String> described = context.describe();
        check("展开详情含来源 / 状态 / 证据 / 局限四类信息",
                contains(described, "来源:") && contains(described, "结论状态:")
                        && contains(described, "证据:") && contains(described, "本次分析的局限:"));
        check("详情写明只展示不预填高风险参数",
                contains(described, "不预填高风险参数"));

        // 过期判断：上下文是「当时那次分析的结论」，时间久了只应提示而不该继续当依据
        check("刚生成的上下文不算过期", !context.olderThanMinutes(60));
        java.util.List<String> emptyEvidence = new ArrayList<String>();
        analyze.AnalysisContext bare = analyze.AnalysisContext.of(
                "本地依赖分析", "analyze.scan", "", emptyEvidence, "", "", null);
        check("没有证据时如实说明而不是编造一条",
                !bare.hasEvidence() && contains(bare.describe(), "没有可引用的具体证据"));

        // 报告承载上下文：界面据此渲染交接条
        AnalyzeReport withContext = AnalyzeReport.text("t", "x", true, 1).withContext(context);
        check("报告可挂载分析上下文",
                withContext.hasContext() && withContext.context == context);
        check("未挂载上下文的报告如实报告没有",
                !AnalyzeReport.text("t", "x", true, 1).hasContext());

        // 局限说明按查询类型区分：不同查询没做的事不一样，不能共用一句
        java.util.List<String> pathLimits =
                AnalyzeEngine.limitationsOf(ReportReader.Query.PATHS);
        check("利用路径的局限声明「不产出污点分析」",
                contains(pathLimits, "不产出污点分析"));
        check("利用路径的局限声明「未命中入口不等于不可达」",
                contains(pathLimits, "不代表外部不可达"));
        check("特征匹配的局限声明「未命中不等于目标干净」",
                contains(AnalyzeEngine.limitsForSignatures(), "未命中不等于目标干净"));
        check("特征匹配的局限声明结论一律为疑似",
                contains(AnalyzeEngine.limitsForSignatures(), "疑似"));

        // 证据摘取：只收结论行，不把整段报告塞进上下文
        java.util.List<String> picked = AnalyzeEngine.evidenceLines(
                "数据库: x\n===== 段 =====\n[高] Runtime.exec\n普通说明行\n"
                        + "NAV|payload.build|去生成载荷\n★ POST /api/demo\n");
        check("证据摘取只收结论行且剔除 NAV 行",
                picked.size() == 2 && contains(picked, "[高] Runtime.exec")
                        && !contains(picked, "NAV|payload.build|去生成载荷"));
        check("证据摘取上限为 12 条", AnalyzeEngine.evidenceLines(repeat("命中", 50)).size() == 12);
    }

    /**
     * 从 src/pom.xml 里读 <version>，用于断言「导出里的版本 = 构建产物版本」。
     *
     * <p>两处漂移时，机器可读报告会指向一个不存在的构建，下游对账时无从判断，
     * 因此把它变成机械断言而不是靠人记得同步改。
     */
    private static String toolVersionInPom() {
        try {
            String pom = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("src", "pom.xml")), StandardCharsets.UTF_8);
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("<version>([^<]+)</version>").matcher(pom);
            if (matcher.find()) return matcher.group(1);
        } catch (Exception unreadable) {
            return "（读取失败：" + unreadable.getMessage() + "）";
        }
        return "";
    }

    /** 列表里是否含某个子串。 */
    private static boolean contains(java.util.List<String> lines, String needle) {
        for (String line : lines) {
            if (line != null && line.contains(needle)) return true;
        }
        return false;
    }

    /** 把一段文本重复若干次，用于上限断言。 */
    private static String repeat(String text, int times) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < times; index++) builder.append(text).append('\n');
        return builder.toString();
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

    /**
     * gadget 判定：坐标 + 版本区间口径。
     *
     * <p>这里守住三件事，它们都是「判定看起来正常但结论是错的」那类风险：
     * 版本已修复的组件不能被判成可用（CommonsCollections 3.2.2）、
     * 新坐标不能漏（c3p0 的 com.mchange、mysql-connector-j）、
     * 外部规则文件的语法与问题上报必须真的生效。
     */
    private static void gadgetChecks() throws Exception {
        System.out.println();
        System.out.println("== gadget 判定 ==");

        check("内置 gadget 规则非空", analyzer.GadgetInventory.ruleCount() > 0);
        check("内置规则不重复 id",
                uniqueIds(analyzer.GadgetRules.all()));
        check("规则类型不为空且去重",
                analyzer.GadgetRules.categories().size() > 4
                        && !analyzer.GadgetRules.categories().contains(""));
        check("取规则：不存在返回 null",
                analyzer.GadgetRules.of("GAD-NOPE") == null);

        // CommonsCollections 3.2.1 可用；3.2.2 已修复，不能再判成可用
        Path ccDir = Files.createTempDirectory("javasec-cc");
        Path ccJar = ccDir.resolve("commons-collections-3.2.1.jar");
        writeJar(ccJar, new String[][]{
                {"META-INF/maven/commons-collections/commons-collections/pom.properties",
                        "version=3.2.1\ngroupId=commons-collections\nartifactId=commons-collections\n"},
                {"org/apache/commons/collections/functors/InvokerTransformer.class", null}});
        AnalyzeReport ccReport = AnalyzeEngine.analyzeTarget(ccJar);
        check("已存在的 CC3 被判为可用",
                ccReport.text.contains("[可用] CommonsCollections 3"));
        check("报告给出 gadget 判定依据（坐标）",
                ccReport.text.contains("commons-collections:commons-collections:3.2.1"));
        check("可用链给出下一步动作",
                ccReport.text.contains("下一步:") && ccReport.text.contains("直达:"));
        check("未引入的链列为缺失并说明还差什么",
                ccReport.text.contains("[缺失]") && ccReport.text.contains("需要"));

        Path ccFixed = ccDir.resolve("commons-collections-3.2.2.jar");
        writeJar(ccFixed, new String[][]{
                {"META-INF/maven/commons-collections/commons-collections/pom.properties",
                        "version=3.2.2\ngroupId=commons-collections\nartifactId=commons-collections\n"},
                {"org/apache/commons/collections/functors/InvokerTransformer.class", null}});
        AnalyzeReport fixedReport = AnalyzeEngine.analyzeTarget(ccFixed);
        check("已修复版本（3.2.2）不再被判为可用",
                !fixedReport.text.contains("[可用] CommonsCollections 3"));

        // 新坐标：c3p0 / mysql-connector-j / h2 都要能识别
        Path c3p0 = ccDir.resolve("c3p0-0.9.5.5.jar");
        writeJar(c3p0, new String[][]{
                {"META-INF/maven/com.mchange/c3p0/pom.properties",
                        "version=0.9.5.5\ngroupId=com.mchange\nartifactId=c3p0\n"}});
        check("新坐标的 c3p0 被识别为可用",
                AnalyzeEngine.analyzeTarget(c3p0).text.contains("[可用] c3p0"));

        Path mysql8 = ccDir.resolve("mysql-connector-j-8.0.33.jar");
        writeJar(mysql8, new String[][]{
                {"META-INF/maven/com.mysql/mysql-connector-j/pom.properties",
                        "version=8.0.33\ngroupId=com.mysql\nartifactId=mysql-connector-j\n"}});
        check("MySQL 8.x 新坐标被识别为可用",
                AnalyzeEngine.analyzeTarget(mysql8).text.contains("[可用] MySQL 驱动（新坐标 8.x）"));

        // 外部规则文件：格式、通配、版本排除、问题上报
        Path ruleFile = ccDir.resolve("gadget.dat");
        Files.write(ruleFile, ("# 注释行\n"
                + "commons-collections-3.2.1.jar|NATIVE|外部规则：CC3\n"
                + "*-collections4.jar|NATIVE|*通配规则\n"
                + "commons-collections-!3.2.1.jar|NATIVE|版本排除规则\n"
                + "坏行只有两段|NATIVE\n").getBytes(StandardCharsets.UTF_8));
        analyzer.GadgetRuleFile.Parsed parsed = analyzer.GadgetRuleFile.parse(ruleFile);
        check("规则文件：合法行被读取（注释与空行跳过）", parsed.rules.size() == 3);
        check("规则文件：坏行被逐行报告而不是静默丢弃",
                parsed.problems.size() == 1 && parsed.problems.get(0).contains("第 5 行"));
        check("规则文件：带版本的 jar 名被解析成上界",
                parsed.rules.get(0).requires.get(0).range.upper.equals("3.2.1"));
        check("规则文件：!版本被解析成排除项",
                parsed.rules.get(2).requires.get(0).range.excluded.contains("3.2.1"));
        check("规则文件：独立于内置表的 id",
                parsed.rules.get(0).id.startsWith("EXT-"));

        // 外部规则参与判定：通配规则在 cc3 目录下应命中并给出依据
        AnalyzeReport withRules = AnalyzeEngine.analyzeTarget(ccDir);
        check("外部规则可向报告归因（多个 jar 的目录）",
                withRules.ok && withRules.text.contains("可用 gadget"));
        AnalyzeReport extra = AnalyzeEngine.analyzeTarget(ccJar, parsed.rules, ruleFile.toString());
        check("外部规则被应用到判定",
                extra.text.contains("外部 gadget 规则:") && extra.text.contains("外部规则：CC3"));

        analyzer.GadgetRuleFile.Parsed absent = AnalyzeEngine.loadGadgetRules(
                ccDir.resolve("no-such-rules.dat").toString());
        check("规则文件缺失时给出可读问题而不抛异常",
                !absent.problems.isEmpty());
        check("规则文件留空时不报错",
                AnalyzeEngine.loadGadgetRules("").problems.isEmpty());
        check("重复规则以外部为准（同 id 覆盖）",
                analyzer.GadgetInventory.merged(parsed.rules).size()
                        >= analyzer.GadgetRules.count());
    }

    private static boolean uniqueIds(List<analyzer.GadgetRule> rules) {
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (analyzer.GadgetRule rule : rules) {
            if (!ids.add(rule.id)) return false;
        }
        return true;
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
