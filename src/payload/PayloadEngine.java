package payload;

import org.vulhub.javachains.api.BuildResult;
import org.vulhub.javachains.api.Gadget;
import org.vulhub.javachains.common.GadgetParam;
import org.vulhub.javachains.common.Result;
import org.vulhub.javachains.core.ChainsRuntime;
import org.vulhub.javachains.core.ExecutionEngine;
import org.vulhub.javachains.core.GadgetFactory;
import org.vulhub.javachains.core.metadata.MetadataRegistry;
import org.vulhub.javachains.core.metadata.NodeMeta;
import org.vulhub.javachains.api.BuildMetrics;
import org.vulhub.javachains.api.ContextValueMeta;
import org.vulhub.javachains.api.GadgetContext;
import org.vulhub.javachains.core.step.ChainStepHook;
import org.vulhub.javachains.core.step.StepCheckpoint;

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

    /** 上游标记末端节点的标签：带它的节点之后没有可接的节点。 */
    private static final String END_TAG = "END";

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

    /**
     * 节点的可读显示名。
     *
     * <p>列式选链要在列内展示节点名称，而节点标识是引擎注册时小写的类名
     * （例如 {@code templatesimpl}），直接展示既看不出用途也不便挑选。
     * 显示名来自引擎自己的元数据，本方法只读，不改变引擎状态。
     *
     * <p>标识为空、节点不在目录内或引擎没有登记显示名时返回空串，
     * 由调用方决定回退成标识——上游实测有 3 个节点没有显示名，
     * 这里不能把「没有名字」伪造成一个名字。
     */
    public static String nodeLabel(String nodeId) {
        init();
        if (!initialized || nodeId == null || nodeId.trim().isEmpty()) return "";
        try {
            NodeMeta meta = MetadataRegistry.getNodeMeta(nodeId.trim());
            if (meta == null) return "";
            String name = meta.getName();
            return name == null ? "" : name.trim();
        } catch (Throwable error) {
            return "";
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
        Run run = execute(payloadId, gadgets, params, false);
        return run.raw == null ? RawPayload.fail("构建失败。") : run.raw;
    }

    // ------------------------------------------------------------------
    // 节点元信息：标签 / 末端判定 / 依赖，供界面按网页版的形式展示候选
    // ------------------------------------------------------------------

    /**
     * 一个节点的只读元信息。
     *
     * <p>界面要在候选右侧显示标签徽标、按标签收敛候选、标出末端节点，
     * 这些判据全部来自上游元数据。上游没有登记该节点时返回
     * {@link NodeInfo#unknown}，由调用方回退展示标识——不能凭标识猜一个标签出来，
     * 猜出来的标签会与引擎的实际判据不一致。
     */
    public static NodeInfo nodeInfo(String nodeId) {
        init();
        if (!initialized || nodeId == null || nodeId.trim().isEmpty()) return NodeInfo.unknown(nodeId);
        String id = nodeId.trim();
        try {
            NodeMeta meta = MetadataRegistry.getNodeMeta(id);
            if (meta == null) return NodeInfo.unknown(id);
            return new NodeInfo(id, meta.getName(), meta.getKind(), meta.getDescription(),
                    safeList(meta.getTags()), safeList(meta.getAliases()), safeList(meta.getDependencies()),
                    safeList(meta.getModes()), meta.isPayload(), isEnd(meta.getTags()));
        } catch (Throwable error) {
            return NodeInfo.unknown(id);
        }
    }

    /** 批量取元信息，顺序与入参一致；空入参返回空列表。 */
    public static List<NodeInfo> nodeInfos(List<String> nodeIds) {
        List<NodeInfo> infos = new ArrayList<NodeInfo>();
        if (nodeIds == null) return infos;
        for (String id : nodeIds) infos.add(nodeInfo(id));
        return infos;
    }

    /** 某个节点是否为末端节点：带 END 标签的节点之后没有可接的节点。 */
    public static boolean isEndNode(String nodeId) {
        return nodeInfo(nodeId).end;
    }

    /**
     * 按标签收敛候选：候选只要命中任一选定标签就保留；标签为空表示不过滤。
     *
     * <p>用「任一命中」而不是「全部命中」：网页版的标签是多选筛选器，
     * 选两个标签是想看这两类节点，而不是想看同时属于两类的节点
     * （同时属于两类的节点在实测里极少，用「全部命中」会让筛选结果几乎恒为空）。
     */
    public static List<String> filterByTags(List<String> nodeIds, List<String> tags) {
        List<String> result = new ArrayList<String>();
        if (nodeIds == null) return result;
        if (tags == null || tags.isEmpty()) {
            result.addAll(nodeIds);
            return result;
        }
        for (String id : nodeIds) {
            NodeInfo info = nodeInfo(id);
            for (String tag : tags) {
                if (tag != null && info.hasTag(tag)) {
                    result.add(id);
                    break;
                }
            }
        }
        return result;
    }

    /** 候选集合里出现过的全部标签，按字母序；供界面生成标签筛选器。 */
    public static List<String> tagsOf(List<String> nodeIds) {
        List<String> tags = new ArrayList<String>();
        if (nodeIds == null) return tags;
        for (String id : nodeIds) {
            for (String tag : nodeInfo(id).tags) {
                if (!tag.trim().isEmpty() && !tags.contains(tag)) tags.add(tag);
            }
        }
        Collections.sort(tags);
        return tags;
    }

    private static List<String> safeList(String[] values) {
        List<String> items = new ArrayList<String>();
        if (values == null) return items;
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) items.add(value.trim());
        }
        return items;
    }

    private static List<String> safeList(java.util.Collection<String> values) {
        List<String> items = new ArrayList<String>();
        if (values == null) return items;
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) items.add(value.trim());
        }
        return items;
    }

    private static boolean isEnd(String[] tags) {
        if (tags == null) return false;
        for (String tag : tags) {
            if (END_TAG.equals(tag)) return true;
        }
        return false;
    }

    /** 节点类名 -> 显示名：调试模式的每一步只拿得到类名，靠这张表换回可读名称。 */
    private static String labelForClassName(String className) {
        if (className == null || className.trim().isEmpty()) return "";
        String simple = className;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) simple = simple.substring(dot + 1);
        try {
            for (Map.Entry<String, NodeMeta> entry : MetadataRegistry.getNodeMetaMap().entrySet()) {
                NodeMeta meta = entry.getValue();
                if (meta == null || meta.getClassName() == null) continue;
                String registered = meta.getClassName();
                if (registered.equals(className) || registered.endsWith("." + simple)) {
                    String name = meta.getName();
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch (Throwable ignored) {
            // 元数据不可用时回退成类名，不影响构建本身
        }
        return simple;
    }

    // ------------------------------------------------------------------
    // 详细构建：载荷 + 上下文 + 指标 + 逐步产物
    // ------------------------------------------------------------------

    /**
     * 构建载荷并带回上下文、指标与逐步产物。
     *
     * <p>与 {@link #build} 走同一条构建路径，区别只在返回值：这里把网页版 Generate 页
     * 需要的旁证一并带出。上下文与载荷必须来自**同一次**构建——分开构建两次会得到
     * 不同的随机类名，上下文就对不上载荷了，因此不能靠「再跑一次顺便采上下文」实现。
     *
     * @param debug 是否采集逐步产物；开启会挂上链钩子，非调试时不开销这份性能
     */
    public static PayloadBuild buildDetailed(String payloadId, List<String> gadgets,
                                             Map<String, Object> params, boolean debug) {
        Run run = execute(payloadId, gadgets, params, debug);
        if (run == null || run.raw == null || !run.raw.success) {
            return PayloadBuild.fail(run == null || run.raw == null ? "构建失败。" : run.raw.message);
        }
        PayloadResult result = toResult(payloadId, gadgets, run.raw);
        if (!result.success) return PayloadBuild.fail(result.message);
        return PayloadBuild.ok(result, run.context, run.durationMs, run.rawSize, run.md5, run.steps);
    }

    /** 把原始构建结果整理成对外结果：文本载体原样交付，字节载体给出 Base64。 */
    private static PayloadResult toResult(String payloadId, List<String> gadgets, RawPayload raw) {
        List<String> chain = cleanChain(gadgets);
        if (raw.object instanceof String) {
            return PayloadResult.okText(payloadId.trim(), chain, (String) raw.object);
        }
        if (raw.bytes.length > 0) return PayloadResult.ok(payloadId.trim(), chain, raw.bytes);
        return PayloadResult.fail("载体没有产出任何可交付的载荷：" + payloadId.trim());
    }

    /** 一次构建的执行详情：原始结果 + 旁证。 */
    private static final class Run {
        RawPayload raw;
        final List<PayloadContextEntry> context = new ArrayList<PayloadContextEntry>();
        final List<PayloadBuild.Step> steps = new ArrayList<PayloadBuild.Step>();
        long durationMs;
        long rawSize = -1L;
        String md5 = "";
    }

    /**
     * 统一的构建实现。
     *
     * <p>{@code buildRaw} 与 {@code buildDetailed} 都走这里，保证「文本载体解码规则」、
     * 「链合法性校验」、「失败原因措辞」只有一份，不会出现某条路径悄悄换了一套判据。
     */
    private static Run execute(String payloadId, List<String> gadgets, Map<String, Object> params,
                               boolean debug) {
        Run run = new Run();
        init();
        if (!initialized) {
            run.raw = RawPayload.fail(initMessage);
            return run;
        }
        if (payloadId == null || payloadId.trim().isEmpty()) {
            run.raw = RawPayload.fail("请先选择 payload 载体。");
            return run;
        }
        List<String> chain = cleanChain(gadgets);
        if (chain.isEmpty()) {
            run.raw = RawPayload.fail("请至少追加一个 gadget 节点：载体自身不是完整利用链。");
            return run;
        }
        List<String> full = new ArrayList<String>();
        full.add(payloadId.trim());
        full.addAll(chain);
        if (!isChainValid(full)) {
            run.raw = RawPayload.fail("这条链不被引擎认可：" + String.join(" -> ", full)
                    + "。请调整节点顺序或改选后继节点。");
            return run;
        }
        try {
            Gadget payload = GadgetFactory.create(payloadId.trim());
            if (payload == null) {
                run.raw = RawPayload.fail("找不到载体：" + payloadId);
                return run;
            }
            ExecutionEngine engine = ExecutionEngine.create(payload);
            engine.addAll(chain);
            if (params != null && !params.isEmpty()) {
                engine.setAll(new LinkedHashMap<String, Object>(params));
            }
            if (debug) engine.setStepHook(stepRecorder(run));
            GadgetContext context = new GadgetContext();
            BuildResult<?> result = engine.build(context);
            if (result == null) {
                run.raw = RawPayload.fail("引擎没有返回构建结果。");
                return run;
            }
            if (!result.isSuccess()) {
                run.raw = RawPayload.fail(result.getMessage() == null ? "构建失败。" : result.getMessage());
                return run;
            }
            readMetrics(result, run);
            readContext(context, run);
            run.raw = rawOf(result.getData());
            // 大小的兜底：上游指标缺失时用实际载荷长度，界面不该显示 -1
            if (run.rawSize < 0) run.rawSize = run.raw.bytes.length;
            return run;
        } catch (Throwable error) {
            run.raw = RawPayload.fail(describe(error));
            return run;
        }
    }

    /** 把上游构建产物转成原始载荷：字节原样，文本按「已是 Base64」解码。 */
    private static RawPayload rawOf(Object data) {
        if (data instanceof byte[]) return RawPayload.ok((byte[]) data, data);
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
    }

    private static void readMetrics(BuildResult<?> result, Run run) {
        try {
            BuildMetrics metrics = result.getMetrics();
            if (metrics == null) return;
            run.durationMs = metrics.getDurationMs();
            if (metrics.getRawSizeBytes() != null) run.rawSize = metrics.getRawSizeBytes().longValue();
            run.md5 = metrics.getRawMd5() == null ? "" : metrics.getRawMd5();
        } catch (Throwable ignored) {
            // 指标只是旁证，取不到不影响载荷交付
        }
    }

    private static void readContext(GadgetContext context, Run run) {
        try {
            Map<String, Object> data = context.getContextData();
            if (data == null) return;
            Map<String, ContextValueMeta> meta = context.getContextMeta();
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                String key = entry.getKey();
                if (key == null) continue;
                Object value = entry.getValue();
                ContextValueMeta valueMeta = meta == null ? null : meta.get(key);
                boolean binary = value instanceof byte[] || (valueMeta != null && valueMeta.isBin());
                run.context.add(new PayloadContextEntry(key,
                        valueMeta == null ? "" : valueMeta.getSource(),
                        binary,
                        valueMeta == null ? 0 : valueMeta.getPriority(),
                        value instanceof byte[] ? (byte[]) value : null,
                        value instanceof byte[] ? "" : String.valueOf(value)));
            }
        } catch (Throwable ignored) {
            // 上下文同样只是旁证
        }
    }

    /** 逐步产物采集：钩子只记录，不修改链上的任何值。 */
    private static ChainStepHook stepRecorder(final Run run) {
        return new ChainStepHook() {
            @Override
            public void onStepStarted(StepCheckpoint checkpoint) {
                // 开始事件不单独成行：产物要等 afterInvoke 才有，先记会得到一行空产物
            }

            @Override
            public Object afterInvoke(StepCheckpoint checkpoint, Object value) {
                try {
                    int index = checkpoint == null ? -1 : checkpoint.getStepIndex();
                    String className = checkpoint == null ? "" : checkpoint.getGadgetClassName();
                    String node = labelForClassName(className);
                    if (node.isEmpty() && checkpoint != null) node = checkpoint.getGadgetSimpleName();
                    String artifact = value == null ? "null" : value.getClass().getSimpleName();
                    int size = value instanceof byte[] ? ((byte[]) value).length : -1;
                    run.steps.add(new PayloadBuild.Step(index, node, artifact, size));
                } catch (Throwable ignored) {
                    // 记录失败不能影响构建：钩子抛异常会中断整条链
                }
                return value;
            }
        };
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
