package ui;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

import shiro.ShiroExploit;

/**
 * 配置页的全部输入控件与分组清单。
 *
 * <p>从界面层抽出来独立成类，理由只有一个：配置项会持续增长（当前 38 项），
 * 全部堆在界面层会让「新增一项配置」需要同时改控件声明、分组清单、读写映射三处，
 * 越往后越容易漏。集中在这里后，控件、分组、读写映射三者相邻，新增一项只改本类。
 *
 * <p>本类不持有任何行为：读取 / 保存 / 下发仍由 {@link ConfigController} 完成，
 * 这样「配置长什么样」与「配置怎么生效」互不牵连。
 */
public final class ConfigForm {

    // 通用 / Fastjson
    public final JTextField ceyeDomain = new JTextField("", 32);
    public final JPasswordField ceyeToken = new JPasswordField("", 32);
    public final JTextField ceyeApi = new JTextField("", 32);
    public final JTextField python = new JTextField("", 32);
    public final JTextField timeout = new JTextField("8", 5);
    public final JTextField probeMethod = new JTextField("POST", 8);
    public final JTextField dnsWait = new JTextField("13", 5);
    public final JTextField baseBody = new JTextField("", 32);
    public final JTextField headers = new JTextField("", 32);
    public final JTextField sessionCookie = new JTextField("", 32);
    public final JTextField dnslogHost = new JTextField("", 32);
    public final JTextField dnsFilter = new JTextField("", 12);
    /** 探测报告详细度：精简只给结论与关键判定，详细才附探针明细。 */
    public final JComboBox<String> probeReport = new JComboBox<String>(new String[]{"精简", "详细"});

    // 代理
    public final JTextField proxyBindHost = new JTextField("", 24);
    public final JTextField proxyPort = new JTextField("8899", 8);
    public final JCheckBox proxyIntercept = new JCheckBox("启动代理后默认拦截请求", false);

    // 抓包转换
    public final JComboBox<String> captureMethod = new JComboBox<String>(
            new String[]{"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"});
    public final JTextField captureContentType = new JTextField("application/json", 24);
    public final JComboBox<String> captureTarget = new JComboBox<String>(
            new String[]{"json", "curl", "raw", "cookie-json", "cookie-header", "cookie-netscape"});

    // Shiro
    public final JTextField shiroUrl = new JTextField("", 32);
    public final JTextField shiroCookieName = new JTextField("rememberMe", 12);
    public final JTextField shiroKey = new JTextField("", 26);
    public final JCheckBox shiroGcm = new JCheckBox("AES-GCM（Shiro ≥ 1.4.2）", false);
    public final JTextField shiroEchoHeader = new JTextField("", 14);
    public final JComboBox<ShiroExploit.ChainKind> shiroChain =
            new JComboBox<ShiroExploit.ChainKind>(ShiroExploit.ChainKind.values());
    public final JTextField shiroCommand = new JTextField("", 22);
    /** 默认请求体：目标接口需要业务参数时，探测与利用都要带上它。 */
    public final JTextField shiroBody = new JTextField("", 22);

    // Payload / 预设链
    public final JTextField payloadExportDir = new JTextField("", 32);
    /** 默认编码：与生成页 ENCODE 一行的四个按钮取值一致。 */
    public final JComboBox<String> payloadEncode = new JComboBox<String>(
            new String[]{"Base64", "Raw", "Hex", "Gzip"});
    public final JCheckBox payloadUrlEncode = new JCheckBox("生成后默认再做 URL 编码", false);
    public final JCheckBox payloadAutoCopy = new JCheckBox("生成成功后默认自动复制", false);
    public final JCheckBox payloadAutoBuild = new JCheckBox("改链后默认自动重新生成", false);
    /** 生成后是否直接展开完整载荷：对应生成页 BEHAVIOR 的「展开所有」。 */
    public final JCheckBox payloadAutoExpand = new JCheckBox("生成后默认展开完整载荷", false);
    /** 是否默认开启悬停选链：对应生成页 BEHAVIOR 的「悬停选链」。 */
    public final JCheckBox payloadHoverSelect = new JCheckBox("默认开启悬停选链", false);
    public final JComboBox<String> presetCategory = new JComboBox<String>(new String[]{"全部分类"});

    // 恶意服务器
    public final JTextField serverBindHost = new JTextField("", 24);
    public final JTextField serverAdvertiseHost = new JTextField("", 24);
    public final JTextField serverJndiLdap = new JTextField("50389", 8);
    public final JTextField serverJndiRmi = new JTextField("50388", 8);
    public final JTextField serverJndiHttp = new JTextField("58080", 8);
    public final JTextField serverHttp = new JTextField("50000", 8);
    public final JTextField serverJrmp = new JTextField("13999", 8);
    public final JTextField serverMysql = new JTextField("3308", 8);
    public final JTextField serverTcp = new JTextField("11527", 8);

    public final JLabel status = new JLabel("配置保存在用户目录下");

    /**
     * 分组清单：新增一项配置只需在这里加一行。
     *
     * <p>顺序刻意按「通用 → 各功能」排列，与使用者排查问题的顺序一致：
     * 先确认通用默认值，再看具体功能。
     */
    public ConfigPage.Group[] groups() {
        return new ConfigPage.Group[]{
                new ConfigPage.Group("通用配置", "所有功能共用；未单独配置的功能沿用这里的默认值。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("Python 解释器", python, "留空则使用 PATH 中的 python"),
                                new ConfigPage.Row("默认超时（秒）", timeout, "探测请求超时"),
                                new ConfigPage.Row("默认请求方法", probeMethod, "探测接口常见为 POST")}),
                new ConfigPage.Group("FastJson 配置",
                        "Fastjson 探测（识别 / 版本 / 期望类 / DNS 探针 / CEYE 确认）专用。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("CEYE 域名", ceyeDomain, "例如 abc.ceye.io"),
                                new ConfigPage.Row("CEYE Token", ceyeToken, "在 ceye.io 个人中心获取"),
                                new ConfigPage.Row("CEYE API", ceyeApi, "默认 http://api.ceye.io/v1/records"),
                                new ConfigPage.Row("默认 DNS 等待（秒）", dnsWait, "DNS 探针等待记录时间"),
                                new ConfigPage.Row("默认业务参数", baseBody, "期望类模式的 base_body"),
                                new ConfigPage.Row("默认请求头", headers, "JSON 对象，例如 {\"Cookie\":\"JWT=xxx\"}"),
                                new ConfigPage.Row("会话 Cookie", sessionCookie,
                                        "已登录会话，例如 JWT_TOKEN=x; JSESSIONID=y"),
                                new ConfigPage.Row("默认 DNSLog 主机", dnslogHost, "例如 abc.ceye.io"),
                                new ConfigPage.Row("默认 CEYE Filter", dnsFilter, "最长 20 字符")}),
                new ConfigPage.Group("探测报告配置",
                        "决定探测结果区显示多少内容；想一屏看完选「精简」，排查假阳性时选「详细」。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("报告详细度", probeReport,
                                        "精简=结论与关键判定；详细=附探针明细与已知限制")}),
                new ConfigPage.Group("代理配置",
                        "「代理抓包」的默认监听参数；留空时按本机联网 IP 与 8899 端口启动。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("默认监听地址", proxyBindHost, "留空则用本机联网 IP"),
                                new ConfigPage.Row("默认监听端口", proxyPort, "1-65535，默认 8899"),
                                new ConfigPage.Row("启动后默认拦截请求", proxyIntercept,
                                        "等同于代理页勾选「拦截请求」")}),
                new ConfigPage.Group("抓包转换配置",
                        "「抓包转换」页的默认请求参数；URL 与请求体每次都不同，故不保存。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("默认请求方法", captureMethod, "抓包使用的 HTTP 方法"),
                                new ConfigPage.Row("默认 Content-Type", captureContentType,
                                        "例如 application/json"),
                                new ConfigPage.Row("默认转换目标", captureTarget, "抓包后自动导出的格式")}),
                new ConfigPage.Group("Shiro 配置",
                        "「Shiro 漏洞利用」的默认参数；密钥留空则沿用内置常见密钥。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("默认目标 URL", shiroUrl, "例如 http://127.0.0.1:8080/"),
                                new ConfigPage.Row("默认 Cookie 名", shiroCookieName, "通常为 rememberMe"),
                                new ConfigPage.Row("默认密钥", shiroKey, "Base64；留空则用内置密钥"),
                                new ConfigPage.Row("默认使用 AES-GCM", shiroGcm, "Shiro ≥ 1.4.2 常用"),
                                new ConfigPage.Row("默认回显请求头", shiroEchoHeader, "例如 X-Authorization"),
                                new ConfigPage.Row("默认利用链", shiroChain, "回显链的 gadget 前缀"),
                                new ConfigPage.Row("默认命令", shiroCommand, "例如 whoami"),
                                new ConfigPage.Row("默认请求体", shiroBody, "目标接口的业务参数")}),
                new ConfigPage.Group("Payload 生成配置",
                        "生成利用链载荷时的默认参数；导出目录留空则写入用户目录。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("默认导出目录", payloadExportDir,
                                        "载荷导出文件的落盘目录，留空则用用户目录"),
                                new ConfigPage.Row("默认编码", payloadEncode,
                                        "进入生成页时预选的编码；与页面上的 ENCODE 按钮一一对应"),
                                new ConfigPage.Row("默认 URL 编码", payloadUrlEncode,
                                        "在编码结果之上再套一层 URL 编码"),
                                new ConfigPage.Row("默认自动复制", payloadAutoCopy,
                                        "生成成功后自动把载荷复制到剪贴板"),
                                new ConfigPage.Row("默认自动生成", payloadAutoBuild,
                                        "改链后立即重新生成，便于连续试链"),
                                new ConfigPage.Row("默认展开载荷", payloadAutoExpand,
                                        "生成后直接给出完整载荷正文，等价于页面上的「展开所有」"),
                                new ConfigPage.Row("默认悬停选链", payloadHoverSelect,
                                        "鼠标滑过候选节点即选中，等价于页面上的「悬停选链」")}),
                new ConfigPage.Group("恶意服务器配置",
                        "「恶意服务器」的默认监听参数；端口留空或填 0 表示不启用该项。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("默认绑定地址", serverBindHost,
                                        "服务实际监听的网卡地址，留空用 127.0.0.1"),
                                new ConfigPage.Row("默认公布地址", serverAdvertiseHost,
                                        "写进载荷的回连地址，跨机测试填本机内网 IP"),
                                new ConfigPage.Row("JNDI LDAP 端口", serverJndiLdap, "默认 50389"),
                                new ConfigPage.Row("JNDI RMI 端口", serverJndiRmi, "默认 50388"),
                                new ConfigPage.Row("JNDI HTTP 端口", serverJndiHttp, "默认 58080"),
                                new ConfigPage.Row("HTTP 服务端口", serverHttp, "默认 50000"),
                                new ConfigPage.Row("JRMP 端口", serverJrmp, "默认 13999"),
                                new ConfigPage.Row("FakeMySQL 端口", serverMysql, "默认 3308"),
                                new ConfigPage.Row("TCP 端口", serverTcp, "默认 11527")}),
                new ConfigPage.Group("预设链配置",
                        "「预设链」页的默认分类筛选；链与参数每次都不同，故不保存。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("默认分类", presetCategory, "打开预设链页时预选的分类")})};
    }
}
