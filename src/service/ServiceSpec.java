package service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一类恶意服务器的静态描述：界面据此渲染服务列表与端口表单。
 *
 * <p>该类只有纯数据，不引用 java-chains 的任何类型。界面层因此不必知道上游枚举与
 * 契约类的存在，上游换版本时改动范围被限制在 {@link ServiceManager} 一个文件里。
 */
public final class ServiceSpec {

    /** 一个端口输入项：键名必须与上游约定一致，否则启动会被直接拒绝。 */
    public static final class PortSpec {
        /** 上游端口键，例如 JNDI 的 ldap / http / rmi / ldaps。 */
        public final String key;
        /** 界面标签。 */
        public final String label;
        /** 默认端口，取自上游默认值，避免与网页版行为不一致。 */
        public final int defaultPort;
        /**
         * 是否只在显式填写端口时才启用。
         *
         * <p>LDAPS 属于这一类：上游要求「启用 LDAPS 就必须同时给出 JKS 证书路径」，
         * 而本工具并未内置证书，默认下发该端口会让整个 JNDI 启动被拒
         * （实测错误为 {@code invalid start configuration}）。因此默认不下发。
         */
        public final boolean optional;

        PortSpec(String key, String label, int defaultPort) {
            this(key, label, defaultPort, false);
        }

        PortSpec(String key, String label, int defaultPort, boolean optional) {
            this.key = key;
            this.label = label;
            this.defaultPort = defaultPort;
            this.optional = optional;
        }
    }

    /** 界面使用的稳定标识，同时是配置键的一部分。 */
    public final String key;
    /** 界面标题。 */
    public final String title;
    /** 一句话说明，用于列表行与页面说明区。 */
    public final String summary;
    /** 端口项；非 JNDI 的服务只有一个名为 main 的端口。 */
    public final List<PortSpec> ports;

    private ServiceSpec(String key, String title, String summary, List<PortSpec> ports) {
        this.key = key;
        this.title = title;
        this.summary = summary;
        this.ports = Collections.unmodifiableList(new ArrayList<PortSpec>(ports));
    }

    /** 首个端口的键：单端口服务就是 main，JNDI 是 ldap。 */
    public String primaryPortKey() {
        return ports.isEmpty() ? "main" : ports.get(0).key;
    }

    /** 全部服务的展示顺序，与网页版左侧导航一致。 */
    public static List<ServiceSpec> all() {
        List<ServiceSpec> specs = new ArrayList<ServiceSpec>();
        specs.add(new ServiceSpec("jndi", "JNDI",
                "LDAP / RMI / HTTP 三件套，配合 JNDI 注入链使用；需要 HTTPS 回调时启用 LDAPS。",
                one("ldap", "LDAP 端口", 50389)
                        .add(new PortSpec("rmi", "RMI 端口", 50388))
                        .add(new PortSpec("http", "HTTP 端口", 58080))
                        .add(new PortSpec("ldaps", "LDAPS 端口", 50636, true))
                        .build()));
        specs.add(new ServiceSpec("http", "HTTP 服务", "把生成的载荷挂到固定路径上，供目标远程拉取。",
                one("main", "监听端口", 50000).build()));
        specs.add(new ServiceSpec("tcp", "TCP 服务", "原始 TCP 载荷投递，适合自定义协议与 JRMP 之外的通道。",
                one("main", "监听端口", 11527).build()));
        specs.add(new ServiceSpec("mysql", "FakeMySQL", "伪装 MySQL 服务端，接管 JDBC 连接并下发恶意序列化数据。",
                one("main", "监听端口", 3308).build()));
        specs.add(new ServiceSpec("jrmp", "JRMP", "监听 JRMP 请求并返回序列化对象，用于绕过 JNDI 的版本限制。",
                one("main", "监听端口", 13999).build()));
        return Collections.unmodifiableList(specs);
    }

    /** 按标识查描述；找不到返回 null，由调用方给出可读提示。 */
    public static ServiceSpec byKey(String key) {
        if (key == null) return null;
        for (ServiceSpec spec : all()) {
            if (spec.key.equals(key.trim())) return spec;
        }
        return null;
    }

    private static Builder one(String key, String label, int port) {
        return new Builder().add(new PortSpec(key, label, port));
    }

    /** 便于把多个端口项串起来写的构建器。 */
    private static final class Builder {
        private final List<PortSpec> ports = new ArrayList<PortSpec>();

        Builder add(PortSpec port) {
            ports.add(port);
            return this;
        }

        List<PortSpec> build() {
            return ports;
        }
    }
}
