package ui;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import config.AppConfig;
import service.ServiceDefaults;
import shiro.ShiroEngine;
import shiro.ShiroExploit;
import util.Platform;

/**
 * 配置页的行为：读取 / 保存 / 下发「配置 → 各功能页控件」。
 *
 * <p>配置是全应用唯一的持久化状态，因此读写入口只此一处：其它页面通过
 * {@link #python()} 这类只读取值方法拿默认值，不直接碰 {@link Properties}。
 * 这样「配置键有哪些、默认值是什么」只有一份真相，新增一个键不会漏改读写两端。
 *
 * <p>下发时机固定为两次：启动时一次、保存后一次。两次都走 {@link #applyToForms()}，
 * 避免出现「保存后探测页生效、代理页没生效」这类不一致。
 */
public final class ConfigController {

    /** 报告详细度的配置值 / 显示文本，一一对应。 */
    private static final String[] REPORT_VALUES = {"brief", "detail"};
    private static final String[] REPORT_LABELS = {"精简", "详细"};

    /** 页面回写与跨页联动：界面层实现，控制器不直接持有任何页面对象。 */
    public interface View {
        /** 把配置里的监听参数下发到已创建的恶意服务器页。 */
        void applyServiceDefaults(ServiceDefaults defaults);

        /** 把配置里的默认分类下发到已创建的预设链页。 */
        void applyPresetCategory(String category);
    }

    private final Properties config;
    private final ConfigForm form;
    private final ProbePage.Widgets probe;
    private final ProxyPage.Widgets proxy;
    private final CapturePage.Widgets capture;
    private final ShiroPage.Widgets shiro;
    private View view;

    private ProbeController probeController;

    public ConfigController(Properties config, ConfigForm form, ProbePage.Widgets probe,
                            ProxyPage.Widgets proxy, CapturePage.Widgets capture, ShiroPage.Widgets shiro,
                            View view) {
        this.config = config;
        this.form = form;
        this.probe = probe;
        this.proxy = proxy;
        this.capture = capture;
        this.shiro = shiro;
        this.view = view;
    }

    /** 配置控制器与探测控制器互相需要对方的一小部分能力，构造完成后接线，避免构造期环。 */
    public void attachProbe(ProbeController controller) {
        this.probeController = controller;
    }

    /**
     * 接线跨页联动。
     *
     * <p>载荷工作台三页在组合根里晚于配置控制器创建（它要拿到面板才能懒加载），
     * 因此用一次 setter 补上；两者之间没有构造期相互依赖，不会形成环。
     */
    public void attachView(View target) {
        this.view = target;
        // 刷新一次：配置已在启动时读过，联动方需要立刻拿到服务器默认值与预设分类
        if (target != null) {
            target.applyServiceDefaults(serverDefaults());
            target.applyPresetCategory(config.getProperty("preset_category", "").trim());
        }
    }

    // ------------------------------------------------------------------
    // 只读取值：其它页面取默认值统一走这里
    // ------------------------------------------------------------------

    public String python() {
        return config.getProperty("python", "");
    }

    public String probeReport() {
        return config.getProperty("probe_report", "brief");
    }

    public String ceyeToken() {
        return config.getProperty("ceye_token", "").trim();
    }

    public String ceyeDomain() {
        return config.getProperty("ceye_domain", "").trim();
    }

    public String property(String key, String fallback) {
        return config.getProperty(key, fallback);
    }

    // ------------------------------------------------------------------
    // 读 / 写 / 下发
    // ------------------------------------------------------------------

    /** 读取用户目录下的 config.properties；文件不存在属首次启动，静默跳过。 */
    public void load() {
        try {
            AppConfig.load(config);
        } catch (IllegalStateException e) {
            form.status.setText(e.getMessage());
        }
    }

    /** 启动与保存后统一下发配置：探测页 + 代理 / 抓包转换 / Shiro 页。 */
    public void applyToForms() {
        probe.timeout.setText(config.getProperty("timeout", "8"));
        String configuredMethod = config.getProperty("probe_method", "POST").trim().toUpperCase(Locale.ROOT);
        for (int index = 0; index < probe.probeMethod.getItemCount(); index++) {
            if (probe.probeMethod.getItemAt(index).equals(configuredMethod)) {
                probe.probeMethod.setSelectedIndex(index);
                break;
            }
        }
        probe.dnsWait.setText(config.getProperty("dns_wait", "13"));
        probe.baseBody.setText(config.getProperty("base_body", ""));
        probe.requestHeaders.setText(config.getProperty("headers", ""));
        probe.sessionCookie.setText(config.getProperty("session_cookie", ""));
        probe.dnslogHost.setText(dnslogHost());
        probe.dnsFilter.setText(config.getProperty("dns_filter", ""));
        applyToToolForms();
        resetForm();
        if (probeController != null) probeController.applyStageFieldState();
    }

    /**
     * 把代理 / 抓包转换 / Shiro 的持久化配置应用到对应页面控件。
     *
     * <p>这些功能此前只在页面里写死默认值，关掉程序就丢；统一在这里读取，与探测页
     * 共享同一次「配置 → 界面」的下发时机（启动时与保存后各一次）。
     */
    public void applyToToolForms() {
        String bindHost = config.getProperty("proxy_bind_host", "").trim();
        if (!bindHost.isEmpty()) proxy.bindHost.setText(bindHost);
        String port = config.getProperty("proxy_port", "").trim();
        if (!port.isEmpty()) proxy.port.setText(port);
        proxy.intercept.setSelected(flagFrom("proxy_intercept", false));

        selectOption(capture.method, config.getProperty("capture_method", "POST"));
        setComboByValue(form.probeReport, REPORT_VALUES, REPORT_LABELS,
                config.getProperty("probe_report", "brief"));
        String contentType = config.getProperty("capture_content_type", "").trim();
        if (!contentType.isEmpty()) capture.contentType.setText(contentType);
        selectOption(capture.convertTarget, config.getProperty("capture_target", "json"));

        String shiroTarget = config.getProperty("shiro_url", "").trim();
        if (!shiroTarget.isEmpty()) shiro.url.setText(shiroTarget);
        String cookieName = config.getProperty("shiro_cookie_name", "").trim();
        if (!cookieName.isEmpty()) shiro.cookieName.setText(cookieName);
        String key = config.getProperty("shiro_key", "").trim();
        if (!key.isEmpty()) shiro.key.setText(key);
        shiro.gcm.setSelected(flagFrom("shiro_gcm", false));
        String echoHeader = config.getProperty("shiro_echo_header", "").trim();
        if (!echoHeader.isEmpty()) shiro.echoHeader.setText(echoHeader);
        selectChain(config.getProperty("shiro_chain", ""));
        String command = config.getProperty("shiro_command", "").trim();
        if (!command.isEmpty()) shiro.command.setText(command);
        String shiroBodyText = config.getProperty("shiro_body", "").trim();
        if (!shiroBodyText.isEmpty()) shiro.body.setText(shiroBodyText);

        // 控制器只在用户进过页之后才存在；不存在时无需下发，进页时自会带上默认值
        if (view != null) {
            view.applyServiceDefaults(serverDefaults());
            view.applyPresetCategory(config.getProperty("preset_category", "").trim());
        }
    }

    /** 把表单控件复位到当前配置值（配置页每次打开都反映真实保存值）。 */
    public void resetForm() {
        form.ceyeDomain.setText(config.getProperty("ceye_domain", ""));
        form.ceyeToken.setText(config.getProperty("ceye_token", ""));
        form.ceyeApi.setText(config.getProperty("ceye_api", ""));
        form.python.setText(config.getProperty("python", ""));
        form.timeout.setText(config.getProperty("timeout", "8"));
        form.probeMethod.setText(config.getProperty("probe_method", "POST"));
        form.dnsWait.setText(config.getProperty("dns_wait", "13"));
        form.baseBody.setText(config.getProperty("base_body", ""));
        form.headers.setText(config.getProperty("headers", ""));
        form.sessionCookie.setText(config.getProperty("session_cookie", ""));
        form.dnslogHost.setText(config.getProperty("dnslog_host", ""));
        form.dnsFilter.setText(config.getProperty("dns_filter", ""));
        form.proxyBindHost.setText(config.getProperty("proxy_bind_host", ""));
        form.proxyPort.setText(config.getProperty("proxy_port", "8899"));
        form.proxyIntercept.setSelected(flagFrom("proxy_intercept", false));
        selectOption(form.captureMethod, config.getProperty("capture_method", "POST"));
        form.captureContentType.setText(config.getProperty("capture_content_type", "application/json"));
        selectOption(form.captureTarget, config.getProperty("capture_target", "json"));
        setComboByValue(form.probeReport, REPORT_VALUES, REPORT_LABELS,
                config.getProperty("probe_report", "brief"));
        form.shiroUrl.setText(config.getProperty("shiro_url", ""));
        form.shiroCookieName.setText(config.getProperty("shiro_cookie_name", "rememberMe"));
        form.shiroKey.setText(config.getProperty("shiro_key", ""));
        form.shiroGcm.setSelected(flagFrom("shiro_gcm", false));
        form.shiroEchoHeader.setText(config.getProperty("shiro_echo_header", ""));
        selectConfigChain(config.getProperty("shiro_chain", ""));
        form.shiroCommand.setText(config.getProperty("shiro_command", ""));
        form.shiroBody.setText(config.getProperty("shiro_body", ""));
        form.payloadExportDir.setText(config.getProperty("payload_export_dir", ""));
        selectOption(form.payloadEncode, config.getProperty("payload_encode", "base64"));
        form.payloadUrlEncode.setSelected(flagFrom("payload_url_encode", false));
        form.payloadAutoCopy.setSelected(flagFrom("payload_auto_copy", false));
        form.payloadAutoBuild.setSelected(flagFrom("payload_auto_build", false));
        form.payloadAutoExpand.setSelected(flagFrom("payload_auto_expand", false));
        form.payloadHoverSelect.setSelected(flagFrom("payload_hover_select", false));
        form.serverBindHost.setText(config.getProperty("server_bind_host", ""));
        form.serverAdvertiseHost.setText(config.getProperty("server_advertise_host", ""));
        form.serverJndiLdap.setText(config.getProperty("server_jndi_ldap_port", "50389"));
        form.serverJndiRmi.setText(config.getProperty("server_jndi_rmi_port", "50388"));
        form.serverJndiHttp.setText(config.getProperty("server_jndi_http_port", "58080"));
        form.serverHttp.setText(config.getProperty("server_http_port", "50000"));
        form.serverJrmp.setText(config.getProperty("server_jrmp_port", "13999"));
        form.serverMysql.setText(config.getProperty("server_mysql_port", "3308"));
        form.serverTcp.setText(config.getProperty("server_tcp_port", "11527"));
        selectOption(form.presetCategory, config.getProperty("preset_category", "全部分类"));
        form.status.setText("已载入当前配置");
    }

    /** 保存：表单 → 配置对象 → 落盘 → 再下发一次，保证界面与磁盘一致。 */
    public void save() {
        config.setProperty("ceye_domain", form.ceyeDomain.getText().trim());
        config.setProperty("ceye_token", new String(form.ceyeToken.getPassword()).trim());
        config.setProperty("ceye_api", form.ceyeApi.getText().trim());
        config.setProperty("python", form.python.getText().trim());
        config.setProperty("timeout", Platform.valueOr(form.timeout.getText(), "8"));
        config.setProperty("probe_method", Platform.valueOr(form.probeMethod.getText().toUpperCase(Locale.ROOT),
                "POST"));
        config.setProperty("dns_wait", Platform.valueOr(form.dnsWait.getText(), "13"));
        config.setProperty("base_body", form.baseBody.getText().trim());
        config.setProperty("headers", form.headers.getText().trim());
        config.setProperty("session_cookie", form.sessionCookie.getText().trim());
        config.setProperty("dnslog_host", form.dnslogHost.getText().trim());
        config.setProperty("dns_filter", form.dnsFilter.getText().trim());
        config.setProperty("proxy_bind_host", form.proxyBindHost.getText().trim());
        config.setProperty("proxy_port", Platform.valueOr(form.proxyPort.getText(), "8899"));
        config.setProperty("proxy_intercept", String.valueOf(form.proxyIntercept.isSelected()));
        config.setProperty("capture_method", String.valueOf(form.captureMethod.getSelectedItem()));
        config.setProperty("capture_content_type",
                Platform.valueOr(form.captureContentType.getText(), "application/json"));
        config.setProperty("capture_target", String.valueOf(form.captureTarget.getSelectedItem()));
        config.setProperty("probe_report",
                REPORT_VALUES[Math.max(0, Math.min(REPORT_VALUES.length - 1,
                        form.probeReport.getSelectedIndex()))]);
        config.setProperty("shiro_url", form.shiroUrl.getText().trim());
        config.setProperty("shiro_cookie_name",
                Platform.valueOr(form.shiroCookieName.getText(), ShiroEngine.REMEMBER_ME));
        config.setProperty("shiro_key", form.shiroKey.getText().trim());
        config.setProperty("shiro_gcm", String.valueOf(form.shiroGcm.isSelected()));
        config.setProperty("shiro_echo_header", form.shiroEchoHeader.getText().trim());
        Object chain = form.shiroChain.getSelectedItem();
        config.setProperty("shiro_chain", chain == null ? "" : ((ShiroExploit.ChainKind) chain).name());
        config.setProperty("shiro_command", form.shiroCommand.getText().trim());
        config.setProperty("shiro_body", form.shiroBody.getText().trim());
        config.setProperty("payload_export_dir", form.payloadExportDir.getText().trim());
        config.setProperty("payload_encode", encodeId(String.valueOf(form.payloadEncode.getSelectedItem())));
        config.setProperty("payload_url_encode", String.valueOf(form.payloadUrlEncode.isSelected()));
        config.setProperty("payload_auto_copy", String.valueOf(form.payloadAutoCopy.isSelected()));
        config.setProperty("payload_auto_build", String.valueOf(form.payloadAutoBuild.isSelected()));
        config.setProperty("payload_auto_expand", String.valueOf(form.payloadAutoExpand.isSelected()));
        config.setProperty("payload_hover_select", String.valueOf(form.payloadHoverSelect.isSelected()));
        config.setProperty("server_bind_host", form.serverBindHost.getText().trim());
        config.setProperty("server_advertise_host", form.serverAdvertiseHost.getText().trim());
        config.setProperty("server_jndi_ldap_port", Platform.valueOr(form.serverJndiLdap.getText(), "50389"));
        config.setProperty("server_jndi_rmi_port", Platform.valueOr(form.serverJndiRmi.getText(), "50388"));
        config.setProperty("server_jndi_http_port", Platform.valueOr(form.serverJndiHttp.getText(), "58080"));
        config.setProperty("server_http_port", Platform.valueOr(form.serverHttp.getText(), "50000"));
        config.setProperty("server_jrmp_port", Platform.valueOr(form.serverJrmp.getText(), "13999"));
        config.setProperty("server_mysql_port", Platform.valueOr(form.serverMysql.getText(), "3308"));
        config.setProperty("server_tcp_port", Platform.valueOr(form.serverTcp.getText(), "11527"));
        Object presetCategory = form.presetCategory.getSelectedItem();
        config.setProperty("preset_category", presetCategory == null ? "全部分类"
                : String.valueOf(presetCategory));
        try {
            AppConfig.save(config);
            applyToForms();
            form.status.setText("已保存到 " + AppConfig.FILE);
        } catch (IOException e) {
            form.status.setText("保存失败：" + e.getMessage());
        }
    }

    /**
     * 记住最近一次成功监听的地址与端口。
     *
     * <p>使用者常会反复调整端口；把它写回同一份配置，下次启动与下次打开配置页都能看到
     * 实际用过的值——否则配置页里的「代理端口」只是个从不生效的摆设。
     */
    public void rememberProxyEndpoint(String host, int port) {
        if (host == null || host.trim().isEmpty() || port <= 0) return;
        config.setProperty("proxy_bind_host", host.trim());
        config.setProperty("proxy_port", String.valueOf(port));
        try {
            AppConfig.save(config);
        } catch (IOException e) {
            form.status.setText("代理已启动，但记录监听参数失败：" + e.getMessage());
        }
    }

    /**
     * 从配置里取出恶意服务器的默认监听参数。
     *
     * <p>端口键用「服务标识.端口键」的复合形式，与 {@code ServiceDefaults.portKey} 一致：
     * 上游对四类服务复用同一个端口键（都叫 main），只用端口键会互相覆盖。
     */
    public ServiceDefaults serverDefaults() {
        Map<String, Integer> ports = new LinkedHashMap<String, Integer>();
        putPort(ports, "jndi.ldap", form.serverJndiLdap.getText());
        putPort(ports, "jndi.rmi", form.serverJndiRmi.getText());
        putPort(ports, "jndi.http", form.serverJndiHttp.getText());
        putPort(ports, "http.main", form.serverHttp.getText());
        putPort(ports, "jrmp.main", form.serverJrmp.getText());
        putPort(ports, "mysql.main", form.serverMysql.getText());
        putPort(ports, "tcp.main", form.serverTcp.getText());
        return new ServiceDefaults(form.serverBindHost.getText(), form.serverAdvertiseHost.getText(), ports);
    }

    /** 端口填写容错：空、非数字、越界一律视为「沿用上游默认值」。 */
    private static void putPort(Map<String, Integer> ports, String key, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return;
        try {
            int port = Integer.parseInt(text);
            if (port >= 1 && port <= 65535) ports.put(key, Integer.valueOf(port));
        } catch (NumberFormatException ignored) {
            // 非法输入按未配置处理：界面已在配置页给出 1-65535 的提示
        }
    }

    /** 勾选框型配置：只认 true/false，其他值按默认值处理。 */
    private boolean flagFrom(String key, boolean fallback) {
        String raw = config.getProperty(key, "").trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) return fallback;
        return "true".equals(raw) || "1".equals(raw) || "yes".equals(raw);
    }

    /** 下拉框按候选项文本选中；配置值不存在时保持原选择。 */
    public static void selectOption(javax.swing.JComboBox<String> combo, String value) {
        String wanted = value == null ? "" : value.trim();
        if (wanted.isEmpty()) return;
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (combo.getItemAt(index).equalsIgnoreCase(wanted)) {
                combo.setSelectedIndex(index);
                return;
            }
        }
    }

    /** 配置值（英文）与显示文本（中文）分离的下拉框：按下标对应选中。 */
    public static void setComboByValue(javax.swing.JComboBox<String> combo, String[] values,
                                       String[] labels, String wanted) {
        String target = wanted == null ? "" : wanted.trim().toLowerCase(Locale.ROOT);
        for (int index = 0; index < values.length && index < labels.length; index++) {
            if (values[index].equals(target)) {
                combo.setSelectedItem(labels[index]);
                return;
            }
        }
        combo.setSelectedIndex(0);
    }

    /** 按枚举名选中利用链；找不到则保持默认。 */
    public void selectChain(String name) {
        String wanted = name == null ? "" : name.trim();
        if (wanted.isEmpty()) return;
        for (ShiroExploit.ChainKind kind : ShiroExploit.ChainKind.values()) {
            if (kind.name().equalsIgnoreCase(wanted) || kind.label.equals(wanted)) {
                shiro.chain.setSelectedItem(kind);
                return;
            }
        }
    }

    /** 配置页的利用链下拉框按下拉项选中。 */
    public void selectConfigChain(String name) {
        String wanted = name == null ? "" : name.trim();
        if (wanted.isEmpty()) {
            form.shiroChain.setSelectedIndex(0);
            return;
        }
        for (ShiroExploit.ChainKind kind : ShiroExploit.ChainKind.values()) {
            if (kind.name().equalsIgnoreCase(wanted) || kind.label.equals(wanted)) {
                form.shiroChain.setSelectedItem(kind);
                return;
            }
        }
        form.shiroChain.setSelectedIndex(0);
    }

    /** 编码显示名转成配置值：配置里存英文标识，界面显示的是按钮短名。 */
    private static String encodeId(String label) {
        if (label == null) return "base64";
        String wanted = label.trim();
        for (payload.PayloadCodec.Option option : payload.PayloadCodec.Option.values()) {
            if (option.label.equalsIgnoreCase(wanted) || option.id.equalsIgnoreCase(wanted)) return option.id;
        }
        return "base64";
    }

    /** DNSLog 主机优先取专用配置，未单独填写时回落到 CEYE 域名（两者同为 dnslog 域）。 */
    public String dnslogHost() {
        String host = config.getProperty("dnslog_host", "").trim();
        return host.isEmpty() ? config.getProperty("ceye_domain", "").trim() : host;
    }
}
