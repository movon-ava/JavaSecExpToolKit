package service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一类服务在某一时刻的对外状态：纯数据，界面直接渲染。
 *
 * <p>只保留界面用得上的字段，不把上游的 {@code ServiceView} 原样透出：
 * 上游每次改版都会增删字段，界面若直接依赖它就会跟着一起改。
 */
public final class ServiceEndpoint {

    /** 一个已绑定端口：键名沿用上游约定。 */
    public static final class BoundPort {
        public final String key;
        public final int port;

        BoundPort(String key, int port) {
            this.key = key;
            this.port = port;
        }
    }

    /** 服务标识，与 {@link ServiceSpec#key} 一致。 */
    public final String key;
    /** 运行状态文本：STOPPED / STARTING / READY / DEGRADED / STOPPING / ERROR。 */
    public final String state;
    /** 实际监听地址；未启动时为空串。 */
    public final String boundHost;
    /** 实际监听端口，按注册顺序。 */
    public final List<BoundPort> boundPorts;
    /** 对外公布地址；未启动时为空串。 */
    public final String advertisedHost;
    /** 当前生效的载荷条数，来自上游的活跃发布统计。 */
    public final int publicationCount;
    /** 最近一次失败原因；无则为空串。 */
    public final String lastError;

    ServiceEndpoint(String key, String state, String boundHost, List<BoundPort> boundPorts,
                    String advertisedHost, int publicationCount, String lastError) {
        this.key = key == null ? "" : key;
        this.state = state == null ? "STOPPED" : state;
        this.boundHost = boundHost == null ? "" : boundHost;
        this.boundPorts = Collections.unmodifiableList(new ArrayList<BoundPort>(boundPorts));
        this.advertisedHost = advertisedHost == null ? "" : advertisedHost;
        this.publicationCount = publicationCount;
        this.lastError = lastError == null ? "" : lastError;
    }

    /** 是否处于「已经在监听」的状态。 */
    public boolean isRunning() {
        return "READY".equals(state) || "DEGRADED".equals(state) || "STARTING".equals(state);
    }

    /** 绑定端口转成键到端口的映射，便于按端口键取值。 */
    public Map<String, Integer> portMap() {
        Map<String, Integer> ports = new LinkedHashMap<String, Integer>();
        for (BoundPort port : boundPorts) ports.put(port.key, port.port);
        return ports;
    }

    /** 取某个端口键的实际端口；未绑定时返回 0。 */
    public int portOf(String portKey) {
        Integer port = portMap().get(portKey);
        return port == null ? 0 : port;
    }

    /** 「host:port」形式的摘要，多个端口时用首个已绑定端口。 */
    public String summary() {
        if (!isRunning() || boundPorts.isEmpty()) return state;
        if (boundPorts.size() == 1) return boundHost + ":" + boundPorts.get(0).port;
        StringBuilder text = new StringBuilder();
        for (BoundPort port : boundPorts) {
            if (text.length() > 0) text.append("  ");
            text.append(port.key).append("=").append(port.port);
        }
        return boundHost + "  " + text;
    }

    /** 未启动时的占位状态，避免界面出现 null 分支。 */
    public static ServiceEndpoint stopped(String key) {
        return new ServiceEndpoint(key, "STOPPED", "", new ArrayList<BoundPort>(), "", 0, "");
    }
}
