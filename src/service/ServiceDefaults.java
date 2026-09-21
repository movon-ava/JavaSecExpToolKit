package service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 恶意服务器页的默认参数：来自配置页，可长期保存。
 *
 * <p>为什么单独建一个类而不是让 {@code ServiceManager} 直接读配置文件：
 * 本包不碰界面也不碰配置文件读写，默认值由界面层从配置里取出后传进来，
 * 这样「服务端适配」与「配置存储」两件事不会互相绑死，也便于自检直接构造一组默认值。
 *
 * <p>端口键用「服务标识.端口键」的复合形式（如 {@code jndi.ldap}）：
 * 上游对不同服务复用同一个端口键（HTTP / TCP / FakeMySQL / JRMP 都叫 main），
 * 只用端口键会互相覆盖。
 */
public final class ServiceDefaults {

    /** 默认绑定地址；空串表示沿用上游默认（127.0.0.1）。 */
    public final String bindHost;
    /** 默认公布地址；空串表示与绑定地址相同。 */
    public final String advertiseHost;
    /** 端口覆盖值：键为「服务标识.端口键」。 */
    private final Map<String, Integer> ports;

    public ServiceDefaults(String bindHost, String advertiseHost, Map<String, Integer> ports) {
        this.bindHost = bindHost == null ? "" : bindHost.trim();
        this.advertiseHost = advertiseHost == null ? "" : advertiseHost.trim();
        this.ports = Collections.unmodifiableMap(ports == null
                ? new LinkedHashMap<String, Integer>() : new LinkedHashMap<String, Integer>(ports));
    }

    /** 无任何默认值：界面全部沿用上游默认端口。 */
    public static ServiceDefaults empty() {
        return new ServiceDefaults("", "", new LinkedHashMap<String, Integer>());
    }

    /** 端口键的复合形式，读写配置与本类取值的唯一约定。 */
    public static String portKey(String serviceKey, String portKey) {
        return (serviceKey == null ? "" : serviceKey.trim()) + "." + (portKey == null ? "" : portKey.trim());
    }

    /** 取某个端口的默认值；未配置时返回 fallback（通常是 {@code ServiceSpec} 里的上游默认端口）。 */
    public int port(String serviceKey, String portKey, int fallback) {
        Integer value = ports.get(portKey(serviceKey, portKey));
        if (value == null || value.intValue() <= 0) return fallback;
        return value.intValue();
    }
}
