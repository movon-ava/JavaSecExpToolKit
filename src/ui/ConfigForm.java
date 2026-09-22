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
 * <p>从界面层抽出来独立成类，理由只有一个：配置项会持续增长（当前 45 项），
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

    // toString 链 / HTTP 带外 Jar
    /** 默认 toString 模板：候选取自 {@link payload.ToStringPreset}，模板增删不必改本处。 */
    public final JComboBox<String> tostringTemplate =
            new JComboBox<String>(templateNames());
    /** 默认末端命令：留空则用模板自己的默认值。 */
    public final JTextField tostringCommand = new JTextField("", 22);
    // 漏洞分析
    /** 默认扫描目标：jar 文件或依赖目录；留空则每次进页自行选择。 */
    public final JTextField analyzeScanTarget = new JTextField("", 32);
    /** 外部引擎（jar-analyzer-engine）的 jar 路径；留空表示只用本地分析。 */
    public final JTextField analyzeEngineJar = new JTextField("", 32);
    /** 引擎工作目录：引擎固定把 jar-analyzer.db 写到工作目录，这里必须显式指定。 */
    public final JTextField analyzeWorkDir = new JTextField("", 32);
    /** 引擎分析超时：大 jar 构建数据库是分钟级，必须给上限。 */
    public final JTextField analyzeTimeout = new JTextField("300", 8);
    /** 反编译产物目录；走内置 CFR 时输出到这里。 */
    public final JTextField analyzeDecompileDir = new JTextField("", 32);

    /** 带外 Jar 的托管参数：绑定地址、端口与三个动作默认值。 */
    public final JTextField oobjarBindHost = new JTextField("", 24);
    public final JTextField oobjarPort = new JTextField("50001", 8);
    public final JTextField oobjarDefaultUrl = new JTextField("", 32);
    public final JTextField oobjarDefaultPath = new JTextField("/tmp/payload.bin", 24);
    public final JTextField oobjarDefaultCommand = new JTextField("", 22);

    // 小工具
    /** 文件上传页的默认目标 URL；上传接口每次都不同，留空则进页时自行填写。 */
    public final JTextField uploadUrl = new JTextField("", 32);
    /** 文件对应的表单字段名：不同框架默认值不同（file / upload / multipartFile）。 */
    public final JTextField uploadField = new JTextField("file", 16);
    /** 上传请求超时（秒）：上传大文件比探测慢，默认放宽到 30 秒。 */
    public final JTextField uploadTimeout = new JTextField("30", 5);

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
     * toString 模板的显示名清单。
     *
     * <p>从 {@link payload.ToStringPreset#templates()} 现取，不写死：模板增删时
     * 配置页的候选项会自动跟上，否则会出现「新模板在功能页有、在配置页选不到」。
     */
    private static String[] templateNames() {
        java.util.List<payload.ToStringPreset.Template> templates = payload.ToStringPreset.templates();
        String[] names = new String[templates.size()];
        for (int index = 0; index < templates.size(); index++) names[index] = templates.get(index).name;
        return names;
    }

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
                new ConfigPage.Group("小工具配置",
                        "「小工具 → 文件上传」的默认参数；每次上传的文件与响应都不保存。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("默认上传 URL", uploadUrl,
                                        "例如 http://host/upload；留空则每次手工填写"),
                                new ConfigPage.Row("默认表单字段名", uploadField,
                                        "目标接口读取文件的参数名，默认 file"),
                                new ConfigPage.Row("上传超时（秒）", uploadTimeout,
                                        "上传大文件时比探测耗时更长，默认 30")}),
                new ConfigPage.Group("漏洞分析配置",
                        "「漏洞分析」页的默认参数；引擎路径留空时只跑本地规则分析。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("默认扫描目标", analyzeScanTarget,
                                        "jar 文件或依赖目录；留空则进页时再选"),
                                new ConfigPage.Row("外部引擎 JAR", analyzeEngineJar,
                                        "jar-analyzer-engine 的 jar；留空只用本地分析"),
                                new ConfigPage.Row("引擎工作目录", analyzeWorkDir,
                                        "引擎固定把 jar-analyzer.db 写到工作目录"),
                                new ConfigPage.Row("分析超时（秒）", analyzeTimeout,
                                        "大 jar 构建数据库是分钟级，默认 300"),
                                new ConfigPage.Row("反编译输出目录", analyzeDecompileDir,
                                        "留空则输出到工作目录下的 decompiled")}),
                new ConfigPage.Group("toString 链配置",
                        "「Payload → toString 链」的默认参数；链模板与命令改完下次进页生效。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("默认链模板", tostringTemplate,
                                        "打开 toString 链页时预选的模板"),
                                new ConfigPage.Row("默认末端命令", tostringCommand,
                                        "链路末端执行的命令；留空用模板默认值")}),
                new ConfigPage.Group("带外 Jar 配置",
                        "「Payload → HTTP 带外 Jar」的默认托管参数；端口用于本机 HTTP 服务。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("默认绑定地址", oobjarBindHost,
                                        "服务实际监听的网卡地址，留空用 127.0.0.1"),
                                new ConfigPage.Row("默认监听端口", oobjarPort, "默认 50001"),
                                new ConfigPage.Row("默认下载 / 回连 URL", oobjarDefaultUrl,
                                        "留空则每次进页自行填写"),
                                new ConfigPage.Row("默认落地路径", oobjarDefaultPath,
                                        "Jar 落到目标上的路径，默认 /tmp/payload.bin"),
                                new ConfigPage.Row("默认执行参数", oobjarDefaultCommand,
                                        "「从 URL 下载并执行」动作的执行参数")}),
                new ConfigPage.Group("预设链配置",
                        "「预设链」页的默认分类筛选；链与参数每次都不同，故不保存。",
                        new ConfigPage.Row[]{
                                new ConfigPage.Row("默认分类", presetCategory, "打开预设链页时预选的分类")})};
    }
}
