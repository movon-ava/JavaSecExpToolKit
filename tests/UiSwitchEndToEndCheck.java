import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import javax.swing.AbstractButton;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * 端到端自检：界面开关 -> Python 命令行 -> 引擎结果。
 *
 * 本机起两个桩服务：一个 Fastjson 风格 JSON 端点，一个恒返回 405 空响应的
 * 登录页端点。分别调用 Main.runProbe，确认界面参数正确下传、结果区展示的是
 * 引擎渲染的中文报告，且传输层失败不会被误判成 Fastjson 版本。
 *
 * 用法：java -cp target\classes UiSwitchEndToEndCheck
 */
public final class UiSwitchEndToEndCheck {

    public static void main(String[] args) throws Exception {
        // 一键发送会走真实构建路径：自检退出时把弹出的计算器进程收掉
        TestProcessGuard.install("UiSwitchEndToEndCheck");
        // 自检会真实写配置（启动代理会记住监听端口、保存探测报告详细度）：
        // 先把 user.home 指向临时目录，避免污染使用者真实的 config.properties。
        java.io.File isolatedHome = java.nio.file.Files.createTempDirectory("javasec-ui-check").toFile();
        isolatedHome.deleteOnExit();
        System.setProperty("user.home", isolatedHome.getAbsolutePath());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", new FastjsonLikeHandler());
        server.createContext("/loginpage", new MethodNotAllowedHandler());
        server.createContext("/gated", new LoginGatedHandler());
        server.createContext("/auth", new SessionAuthHandler());
        server.createContext("/upload", new UploadHandler());
        server.start();
        final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        final String blockedUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/loginpage";
        final String gatedUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/gated";
        final String authUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/auth";
        final String uploadUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/upload";
        final java.io.File uploadFile = java.io.File.createTempFile("jset-upload-", ".txt");
        uploadFile.deleteOnExit();
        java.nio.file.Files.write(uploadFile.toPath(),
                ("prefix-" + UploadHandler.MARKER + "-suffix").getBytes(StandardCharsets.UTF_8));
        try {
            Constructor<?> constructor = Class.forName("Main").getDeclaredConstructor();
            constructor.setAccessible(true);
            final Object main = constructor.newInstance();

            setText(main, "target", url);
            setSelected(main, "dnsEnabled", false);
            setSelected(main, "ceyeEnabled", false);
            String detectOnly = runProbe(main, url, "detect");
            System.out.println("识别模式 -> " + conclusion(detectOnly));
            check("识别模式输出可读文本报告",
                    detectOnly.contains("===== Fastjson 识别 =====") && detectOnly.contains("探测结论:"));
            check("识别模式判定命中 Fastjson",
                    detectOnly.contains("是否 Fastjson: 是") && detectOnly.contains("置信度:"));
            // 默认精简报告：只给结论与关键判定，多模式一起跑也能一屏看完
            check("默认精简报告不含探针明细", !detectOnly.contains("探针明细:"));
            check("默认精简报告不含阶段小结", !detectOnly.contains("阶段:"));
            check("默认精简报告不含目标行", !detectOnly.contains("目标: " + url));
            check("结果区中文未乱码", !detectOnly.contains("\ufffd"));

            // 配置页的「报告详细度」切到详细后，同一目标应重新给出探针明细
            setConfigProperty(main, "probe_report", "detail");
            String detailed = runProbe(main, url, "detect");
            System.out.println("详细报告行数 -> " + detailed.split("\\R").length);
            check("详细报告含探针明细",
                    detailed.contains("探针明细:") && detailed.contains("指纹得分: fastjson="));
            check("详细报告含阶段小结",
                    detailed.contains("阶段: DNS 探针 未执行 / CEYE 确认 未执行"));
            check("详细报告含目标行", detailed.contains("目标: " + url));
            setConfigProperty(main, "probe_report", "brief");

            // 独立模式：DNS 探针与 CEYE 确认可单独执行，与其他模式同等对待
            setSelected(main, "dnsEnabled", true);
            setSelected(main, "ceyeEnabled", false);
            check("勾选 DNS 探针后模式列表含 dns", modeKeys(main).contains("dns"));
            String dnsOnly = runProbe(main, url, "dns");
            System.out.println("DNS 探针模式 -> " + conclusion(dnsOnly));
            check("DNS 探针模式可独立执行", dnsOnly.contains("===== DNS 探针 ====="));

            setSelected(main, "dnsEnabled", false);
            setSelected(main, "ceyeEnabled", true);
            String ceyeOnly = runProbe(main, url, "ceye");
            System.out.println("CEYE 确认模式 -> " + ceyeOnly.trim());
            check("CEYE 缺 Token 时给出可读提示而非内部异常",
                    ceyeOnly.contains("CEYE 确认需要 Token") || ceyeOnly.contains("===== CEYE 确认 ====="));

            // 多模式：三个识别模式全选后应依次执行，并带引擎渲染的分段标题
            setText(main, "target", url);
            setText(main, "baseBody", "{\"age\":20}");
            setText(main, "timeout", "5");
            setSelected(main, "dnsEnabled", false);
            setSelected(main, "ceyeEnabled", false);
            setSelected(main, "modeDetect", true);
            setSelected(main, "modeVersion", true);
            setSelected(main, "modeExpect", true);
            startDetection(main);
            String multi = awaitResult(main, 90000);
            System.out.println("多模式分段: " + summarizeModes(multi));
            check("多模式含识别分段标题", multi.contains("===== Fastjson 识别 ====="));
            check("多模式含版本识别分段标题", multi.contains("===== 版本识别 ====="));
            check("多模式含期望类分段标题", multi.contains("===== 期望类 ====="));
            check("多模式含版本识别回显版本", multi.contains("回显版本: 1.2.68"));
            check("多模式含期望类判定字段", multi.contains("是否存在期望类: 未判定"));
            check("多模式结果区中文未乱码", !multi.contains("\ufffd"));

            // 传输层失败：恒 405 空响应不能被当成 Fastjson 解析器，也不能推出假版本区间
            setText(main, "baseBody", "{\"age\":20,\"name\":\"Bob\"}");
            String blockedDetect = runProbe(main, blockedUrl, "detect");
            System.out.println("405 端点识别 -> " + conclusion(blockedDetect));
            check("405 端点识别判定为无法探测",
                    blockedDetect.contains("无法探测") && blockedDetect.contains("是否 Fastjson: 否"));
            check("405 端点识别置信度为 0", blockedDetect.contains("置信度: 0.0"));
            String blockedVersion = runProbe(main, blockedUrl, "version");
            System.out.println("405 端点版本 -> " + conclusion(blockedVersion));
            check("405 端点版本未能收敛而非给出假区间",
                    blockedVersion.contains("版本未能收敛") && blockedVersion.contains("可能版本: 未能收敛"));
            check("405 端点不再出现假版本区间", !blockedVersion.contains("1.2.70-1.2.80"));

            // 登录拦截：靶场未登录时把请求 forward 到登录页，POST 只拿到 405 空响应、
            // GET 返回登录页 HTML。此时不能推出「该路径只接受 GET」，要给出可执行的结论
            setText(main, "requestHeaders", "");
            setText(main, "sessionCookie", "");
            String gatedDetect = runProbe(main, gatedUrl, "detect");
            System.out.println("登录拦截识别 -> " + conclusion(gatedDetect));
            check("登录拦截的识别模式结论标明无法探测",
                    gatedDetect.contains("无法探测：") && gatedDetect.contains("登录页"));
            check("登录拦截的识别模式不再给出只接受 GET 的误导结论",
                    !gatedDetect.contains("只接受 GET）重试"));
            check("登录拦截的识别模式置信度为 0", gatedDetect.contains("置信度: 0.0"));
            check("登录拦截的识别模式判定不是 Fastjson", gatedDetect.contains("是否 Fastjson: 否"));
            String gatedVersion = runProbe(main, gatedUrl, "version");
            System.out.println("登录拦截版本 -> " + conclusion(gatedVersion));
            check("登录拦截的版本模式结论为版本未能收敛",
                    gatedVersion.contains("版本未能收敛") && gatedVersion.contains("登录页"));
            String gatedExpect = runProbe(main, gatedUrl, "expect");
            System.out.println("登录拦截期望类 -> " + conclusion(gatedExpect));
            check("登录拦截的期望类模式结论为未能完成探测",
                    gatedExpect.contains("未能完成探测") && gatedExpect.contains("登录页"));

            // 会话 Cookie：未带 Cookie 时被登录页拦住，带上有效会话后同一路径可正常探测
            String authBlocked = runProbe(main, authUrl, "detect");
            System.out.println("会话场景未带 Cookie -> " + conclusion(authBlocked));
            check("未携带会话 Cookie 时判定为登录拦截", authBlocked.contains("无法探测："));
            check("未携带会话 Cookie 时未误判命中 Fastjson", authBlocked.contains("是否 Fastjson: 否"));
            setText(main, "sessionCookie", "JWT_TOKEN=fresh");
            String authUnlocked = runProbe(main, authUrl, "detect");
            System.out.println("会话场景带有效 Cookie -> " + conclusion(authUnlocked));
            check("携带会话 Cookie 后可解锁真实端点", !authUnlocked.contains("无法探测："));
            check("携带会话 Cookie 后命中 Fastjson", authUnlocked.contains("是否 Fastjson: 是"));
            check("携带会话 Cookie 后置信度非 0", !authUnlocked.contains("置信度: 0.0"));
            setText(main, "sessionCookie", "JWT_TOKEN=stale");
            String authStale = runProbe(main, authUrl, "detect");
            System.out.println("会话场景 Cookie 失效 -> " + conclusion(authStale));
            check("会话 Cookie 失效时区分已携带仍被拦截",
                    authStale.contains("已携带会话 Cookie 仍被拦截"));
            setText(main, "sessionCookie", "");

            setSelected(main, "modeDetect", false);
            setSelected(main, "modeVersion", false);
            setSelected(main, "modeExpect", false);
            setSelected(main, "dnsEnabled", false);
            setSelected(main, "ceyeEnabled", false);
            check("取消全部模式后无可执行模式", modeKeys(main).isEmpty());
            startDetection(main);
            check("未勾选任何模式时提示且不执行", resultText(main).contains("请至少勾选一个探测模式"));

            // 抓包功能：独立页发送请求并记录原始响应，再从响应里解析 Cookie
            setText(main, "captureUrl", url);
            setText(main, "captureHeaders", "{\"Cookie\":\"JWT_TOKEN=abc; JSESSIONID=xyz\"}");
            setText(main, "captureBody", "{\"age\":20,\"name\":\"Bob\"}");
            setCombo(main, "captureTarget", "cookie-json");
            String capture = runCapture(main, url);
            System.out.println("抓包 -> " + conclusion(capture));
            check("抓包返回可读报告", capture.contains("===== HTTP 抓包 =====") && capture.contains("响应状态:"));
            check("抓包记录响应头", capture.contains("响应头:") && capture.contains("Content-Type"));
            check("抓包回显请求 Cookie", capture.contains("JWT_TOKEN") && capture.contains("JSESSIONID"));
            check("抓包输出格式转换", capture.contains("格式转换:") && capture.contains("cookie-json"));
            check("抓包结果中文未乱码", !capture.contains("\ufffd"));

            // 报文转换：粘贴原始请求 -> 导出多种格式
            setCombo(main, "captureTarget", "cookie-header");
            setText(main, "pastedRequest",
                    "POST /api HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: a=1; b=2\r\n\r\n{\"age\":20}");
            String convert = runConvert(main, url);
            System.out.println("转换 -> " + conclusion(convert));
            check("转换返回可读报告", convert.contains("===== 报文转换 =====") && convert.contains("格式转换:"));
            check("转换按所选目标导出 cookie-header", convert.contains("--- cookie-header ---") && convert.contains("a=1; b=2"));
            check("转换把相对路径补全为绝对 URL", convert.contains("http://127.0.0.1/api"));

            // 抓包页可把 URL / 请求头 / 请求体回填到探测页
            setCombo(main, "captureTarget", "json");
            setText(main, "captureBody", "{\"age\":20,\"name\":\"Bob\"}");
            invokeVoid(main, "fillProbeFromCapture");
            Thread.sleep(200);
            check("填入探测页带过目标 URL",
                    ((JTextField) read(main, "target")).getText().equals(url));
            check("填入探测页带过业务参数",
                    ((JTextField) read(main, "baseBody")).getText().contains("\"age\":20"));

            // 一键发送：把抓到的请求直接交给另一个功能去探测（而不是手工再复制一遍）
            setCombo(main, "captureSendTo", "Fastjson 探测");
            setText(main, "captureHeaders", "{\"Cookie\":\"JWT_TOKEN=oneshot\"}");
            setText(main, "captureBody", "{\"age\":20,\"name\":\"Bob\"}");
            setText(main, "target", "");
            setCombo(main, "probeMethod", "GET");
            setCombo(main, "captureMethod", "POST");
            invokeVoid(main, "sendCaptureTo");
            Thread.sleep(300);
            check("一键发送把目标 URL 带到 Fastjson 探测页",
                    ((JTextField) read(main, "target")).getText().equals(url));
            check("一键发送把请求方法带到 Fastjson 探测页",
                    "POST".equals(String.valueOf(((javax.swing.JComboBox<?>) read(main, "probeMethod")).getSelectedItem())));
            check("一键发送把请求头带到 Fastjson 探测页",
                    ((JTextField) read(main, "requestHeaders")).getText().contains("JWT_TOKEN=oneshot"));
            check("一键发送把请求体带到 Fastjson 探测页",
                    ((JTextField) read(main, "baseBody")).getText().contains("\"age\":20"));
            // 一键发送把抓到的 Cookie 写进「请求头（JSON）」（与抓包页的写法保持一致），
            // 因此这里检查的是它真能下发到引擎：指向登录拦截端点时应当被解锁而不是被拦回
            check("一键发送把会话 Cookie 写进探测页请求头",
                    ((JTextField) read(main, "requestHeaders")).getText().contains("JWT_TOKEN=oneshot"));
            setText(main, "baseBody", "{\"age\":20,\"name\":\"Bob\"}");
            String sentWithCookie = runProbe(main, authUrl, "detect");
            System.out.println("一键发送后带 Cookie 探测 -> " + conclusion(sentWithCookie));
            check("一键发送带过的会话 Cookie 真正下发到引擎",
                    !sentWithCookie.contains("无法探测：") && sentWithCookie.contains("是否 Fastjson: 是"));
            // 一键发送只回填参数并跳页，不替使用者开跑：抓到的报文往往还要改探测模式
            // 或业务参数，自动发请求既浪费往返，也容易在参数没确认时把流量打到目标
            check("一键发送提示等待确认而非已开跑",
                    ((javax.swing.JLabel) read(main, "captureStatus")).getText().contains("确认参数后点"));
            Thread.sleep(1200);
            check("一键发送不自动执行探测",
                    !((javax.swing.JTextArea) read(main, "result")).getText().contains("正在发送")
                            && !((javax.swing.JTextArea) read(main, "result")).getText().contains("Fastjson 识别"));
            check("一键发送后探测按钮仍可点击",
                    ((javax.swing.JButton) read(main, "detect")).isEnabled());

            // 一键发送到 Shiro：URL / 方法 / 会话 Cookie 都要带过，且走「每行一个头」的格式
            setCombo(main, "captureSendTo", "Shiro 漏洞利用");
            invokeVoid(main, "sendCaptureTo");
            Thread.sleep(300);
            check("一键发送把目标 URL 带到 Shiro 页",
                    ((JTextField) read(main, "shiroUrl")).getText().equals(url));
            check("一键发送把会话 Cookie 带到 Shiro 附加请求头",
                    ((JTextArea) read(main, "shiroHeaders")).getText().contains("JWT_TOKEN=oneshot"));
            check("一键发送到 Shiro 不带 host 头",
                    !((JTextArea) read(main, "shiroHeaders")).getText().toLowerCase(java.util.Locale.ROOT).contains("host:"));
            check("一键发送到 Shiro 提示等待确认",
                    ((javax.swing.JLabel) read(main, "captureStatus")).getText().contains("确认参数后点"));
            Thread.sleep(1200);
            check("一键发送到 Shiro 不自动执行",
                    !((JTextArea) read(main, "shiroDetectOutput")).getText().contains("Shiro 指纹检测"));

            // 代理抓包：界面启动本地代理，经代理请求一次明文 HTTP，请求包与返回包应被记录
            java.net.ServerSocket probe = new java.net.ServerSocket(0);
            int freePort = probe.getLocalPort();
            probe.close();
            setText(main, "proxyBindHost", "127.0.0.1");
            setText(main, "proxyPort", String.valueOf(freePort));
            invokeVoid(main, "toggleProxy");
            Thread.sleep(400);
            int proxyPort = Integer.parseInt(((JTextField) read(main, "proxyPort")).getText());
            check("界面可启动本地代理并回填端口", proxyPort > 0);
            check("默认监听地址为本机联网 IP", !proxy.ProxyServer.LOOPBACK.equals(
                    proxy.ProxyServer.defaultBindHost()));
            check("启动后监听地址回填为 127.0.0.1",
                    "127.0.0.1".equals(((JTextField) read(main, "proxyBindHost")).getText()));

            sendViaProxy(url, proxyPort, "JWT_TOKEN=proxy-session");
            String response = awaitProxyResponse(main, "{\"ok\":true}", 10000);
            check("放行后捕获返回包", response.contains("HTTP 200") && response.contains("{\"ok\":true}"));
            check("返回包含响应头", response.toLowerCase(java.util.Locale.ROOT).contains("content-type"));
            check("返回包中文未乱码", !response.contains("\ufffd"));

            // 未勾选拦截时请求包显示实际发出的报文（含 Cookie），方便直接排错
            String request = ((JTextArea) read(main, "proxyRequestText")).getText();
            check("请求包显示请求行", request.startsWith("POST ") && request.contains(" HTTP/1.1"));
            check("请求包含 Cookie", request.contains("JWT_TOKEN=proxy-session"));
            check("请求包含请求体", request.contains("{\"age\":20}"));
            check("未拦截时请求包为只读观察视图",
                    !((JTextArea) read(main, "proxyRequestText")).isEditable());

            // 代理页每次进入都会重建面板；已捕获的报文不能被占位提示覆盖回去，
            // 否则切去别的功能看一眼再回来，刚抓到的请求 / 返回包就没了
            String capturedRequest = ((JTextArea) read(main, "proxyRequestText")).getText();
            String capturedResponse = ((JTextArea) read(main, "proxyResponseText")).getText();
            selectNav(main, "home");
            selectNav(main, "proxy.mitm");
            Thread.sleep(200);
            check("切页返回后请求包内容仍在",
                    ((JTextArea) read(main, "proxyRequestText")).getText().equals(capturedRequest));
            check("切页返回后返回包内容仍在",
                    ((JTextArea) read(main, "proxyResponseText")).getText().equals(capturedResponse));
            check("切页返回后内容未回落到占位提示",
                    !((JTextArea) read(main, "proxyRequestText")).getText().startsWith("等待流量"));

            // 拦截：请求停住不转发，改包后放行，目标应收到改过的请求体
            clickCheckBox(main, "proxyIntercept", true);
            invokeVoid(main, "toggleProxy");
            Thread.sleep(200);
            check("停止代理后放行按钮不可用", !((javax.swing.JButton) read(main, "proxyForward")).isEnabled());
            invokeVoid(main, "toggleProxy");
            Thread.sleep(400);
            proxyPort = Integer.parseInt(((JTextField) read(main, "proxyPort")).getText());
            check("重新启动后勾选拦截，放行按钮可用",
                    ((javax.swing.JButton) read(main, "proxyForward")).isEnabled());

            // 先停掉代理，改为「启动后再勾选拦截」的用法
            invokeVoid(main, "toggleProxy");
            Thread.sleep(200);
            clickCheckBox(main, "proxyIntercept", false);
            invokeVoid(main, "toggleProxy");
            Thread.sleep(400);
            proxyPort = Integer.parseInt(((JTextField) read(main, "proxyPort")).getText());
            check("未勾选拦截时放行按钮不可用",
                    !((javax.swing.JButton) read(main, "proxyForward")).isEnabled());
            clickCheckBox(main, "proxyIntercept", true);
            Thread.sleep(150);
            check("运行中勾选拦截后放行按钮立即可用",
                    ((javax.swing.JButton) read(main, "proxyForward")).isEnabled());

            final String beforeIntercept = ((JTextArea) read(main, "proxyResponseText")).getText();
            final java.util.concurrent.atomic.AtomicReference<String> intercepted =
                    new java.util.concurrent.atomic.AtomicReference<String>();
            final int interceptPort = proxyPort;
            Thread client = new Thread(() -> {
                try {
                    intercepted.set(sendViaProxy(url, interceptPort, "JWT_TOKEN=intercepted"));
                } catch (Exception e) {
                    intercepted.set("ERROR " + e);
                }
            });
            client.setDaemon(true);
            client.start();
            String pending = awaitProxyText(main, "proxyRequestText", "intercepted", 10000);
            check("拦截命中时请求停在上方等待放行", pending.contains("JWT_TOKEN=intercepted"));
            check("拦截命中后请求包可编辑改包",
                    ((JTextArea) read(main, "proxyRequestText")).isEditable());
            check("等待放行期间返回包保持上一次内容",
                    ((JTextArea) read(main, "proxyResponseText")).getText().equals(beforeIntercept));

            // 浏览器后台还会持续产生请求；拦截期间它们不得刷新请求包面板
            final String pendingSnapshot = ((JTextArea) read(main, "proxyRequestText")).getText();
            sendViaProxyQuietly(url, interceptPort, "JWT_TOKEN=background");
            Thread.sleep(600);
            check("等待放行期间不被无关请求刷屏",
                    ((JTextArea) read(main, "proxyRequestText")).getText().equals(pendingSnapshot));

            setText(main, "proxyRequestText", pending.replace("{\"age\":20}", "{\"age\":21}"));
            invokeVoid(main, "forwardIntercepted");
            client.join(10000);
            check("放行后客户端拿到响应",
                    intercepted.get() != null && intercepted.get().contains("\"ok\":true"));
            // 面板更新在 EDT 上、返回包在客户端读完后才产生，必须轮询而不是立即断言
            String after = awaitProxyResponse(main, "\"age\":21", 10000);
            check("放行后返回包展示的是本次请求的响应",
                    after.contains("HTTP 200") && after.contains("\"age\":21"));
            check("改包后的请求体已发往目标", lastOriginBody().contains("\"age\":21"));

            // 前面的无关请求在队列里等着，上一条放行后它应自动接位停在面板上
            String queued = awaitProxyText(main, "proxyRequestText", "JWT_TOKEN=background", 10000);
            check("排队中的请求随后自动接位停在面板上", queued.contains("JWT_TOKEN=background"));
            check("排队请求接位后返回包仍是上一次的结果",
                    ((JTextArea) read(main, "proxyResponseText")).getText().contains("\"age\":21"));

            invokeVoid(main, "toggleProxy");
            Thread.sleep(200);
            check("代理可再次点击停止", !((javax.swing.JLabel) read(main, "proxyStatus")).getText().contains("运行中"));


            // 抓包 -> Shiro 链路：未勾选「拦截请求」时，面板展示的就是最近一条流量，
            // 必须能直接导出到抓包页并一键发送到 Shiro——否则「抓包格式直接探测」
            // 在最常见的观察模式下根本不成立。
            clickCheckBox(main, "proxyIntercept", false);
            invokeVoid(main, "toggleProxy");
            Thread.sleep(400);
            proxyPort = Integer.parseInt(((JTextField) read(main, "proxyPort")).getText());
            sendViaProxy(url, proxyPort, "JWT_TOKEN=capture-to-shiro");
            awaitProxyText(main, "proxyRequestText", "capture-to-shiro", 10000);
            invokeVoid(main, "exportProxyDetail");
            Thread.sleep(300);
            check("观察模式下也能把抓到的请求导到抓包页",
                    ((JTextArea) read(main, "pastedRequest")).getText().contains("capture-to-shiro"));
            check("导出的请求头不再带跳转头",
                    !((JTextField) read(main, "captureHeaders")).getText().contains("Proxy-Connection"));
            setCombo(main, "captureSendTo", "Shiro 漏洞利用");
            invokeVoid(main, "sendCaptureTo");
            Thread.sleep(300);
            String shiroHeaders = ((JTextArea) read(main, "shiroHeaders")).getText();
            System.out.println("一键发送后 Shiro 附加请求头 -> " + shiroHeaders.replace("\n", " | "));
            check("一键发送后 Shiro 附加请求头非空", !shiroHeaders.trim().isEmpty());
            check("附加请求头带上了抓包到的会话 Cookie",
                    shiroHeaders.contains("capture-to-shiro"));
            check("附加请求头不照搬抓包里的 Host",
                    !shiroHeaders.toLowerCase(java.util.Locale.ROOT).contains("host:"));
            invokeVoid(main, "toggleProxy");
            Thread.sleep(200);

            // 小工具 - 文件上传：真实发一次 multipart 请求，响应结果回填到结果区；
            // 同时验证「选择本地文件 → 上传」这条链路与引擎的字节级请求体一致。
            setText(main, "toolsUploadUrl", uploadUrl);
            setText(main, "toolsUploadFile", uploadFile.getAbsolutePath());
            setText(main, "toolsUploadField", "file");
            setText(main, "toolsUploadFields", "{\"csrf\":\"tok\"}");
            setText(main, "toolsUploadHeaders", "{\"Cookie\":\"JWT_TOKEN=upload\"}");
            String upload = runUpload(main, uploadUrl, uploadFile.getAbsolutePath(), "file",
                    "{\"csrf\":\"tok\"}", "{\"Cookie\":\"JWT_TOKEN=upload\"}");
            System.out.println("文件上传 -> " + conclusion(upload));
            check("上传返回可读报告", upload.contains("===== 文件上传 =====") && upload.contains("响应状态:"));
            check("上传报告回显文件与字段",
                    upload.contains(uploadFile.getName()) && upload.contains("附加字段: csrf=tok"));
            check("上传报告回显响应体", upload.contains("\"uploaded\""));
            check("上传结果中文未乱码", !upload.contains("\ufffd"));
            check("上传请求为 multipart 且带表单字段",
                    UploadHandler.lastContentType != null
                            && UploadHandler.lastContentType.contains("multipart/form-data")
                            && UploadHandler.lastBody != null
                            && UploadHandler.lastBody.contains("name=\"csrf\"")
                            && UploadHandler.lastBody.contains(UploadHandler.MARKER));
            check("上传请求带上了请求头里的会话 Cookie",
                    UploadHandler.lastCookie != null && UploadHandler.lastCookie.contains("JWT_TOKEN=upload"));

            // 未选择文件时给出可读提示，而不是发一个空请求
            String noFile = runUpload(main, uploadUrl, "", "file", "", "");
            check("未选择文件时给出可读结论", noFile.contains("未选择文件"));
            // 本地文件不存在时同样不发请求
            String badFile = runUpload(main, uploadUrl,
                    uploadFile.getAbsolutePath() + ".missing", "file", "", "");
            check("文件不存在时给出可读结论", badFile.contains("未发送请求"));

            // 抓包头里常带的两个陷阱必须被过滤：旧 Content-Length 会让所有探针
            // 等到超时，Accept-Encoding: gzip 会让探针拿到一堆乱码。
            setText(main, "target", url);
            setText(main, "requestHeaders",
                    "{\"Content-Length\":\"9999\",\"Accept-Encoding\":\"gzip, deflate\","
                            + "\"Proxy-Connection\":\"keep-alive\",\"Cookie\":\"JWT_TOKEN=trap\"}");
            String trapped = runProbe(main, url, "detect");
            System.out.println("抓包头陷阱 -> " + conclusion(trapped));
            check("抓包头里的旧 Content-Length / gzip / 跳转头不会让探测失败",
                    !trapped.contains("无法探测"));
            check("抓包头陷阱下仍能判定 Fastjson",
                    trapped.contains("是否 Fastjson: 是"));
            setText(main, "requestHeaders", "");

            // HTTP 带外 Jar：真实托管后必须能从返回的地址上取回一个合法 Zip。
            // 这条断言咬住的是「发布用的 ServiceManager 实例与启动服务的是同一个」——
            // 换个实例发布时接口会返回成功，但地址回落到上游默认端口 50000，URL 打不开。
            int oobPort = freePort();
            // 必须先真正进一次页面：控制器是懒加载的，没进过页就没有按钮监听器，
            // 点了也不会有反应；而进页会把配置里的默认端口写进控件，因此端口要在进页之后再填
            selectNav(main, "payload.oobjar");
            setText(main, "oobJarPort", String.valueOf(oobPort));
            setCombo(main, "oobJarAction", "执行命令");
            setText(main, "oobJarCommand", "whoami");
            click(main, "oobJarHost");
            String oobStatus = awaitStatus(main, "oobJarStatus", 90000);
            String oobOutput = ((JTextArea) read(main, "oobJarOutput")).getText();
            System.out.println("带外 Jar 托管状态: " + oobStatus);
            check("带外 Jar 托管成功", oobStatus.startsWith("托管成功"));
            check("带外 Jar 托管输出给出地址与监听端点",
                    oobOutput.contains("地址：http://") && oobOutput.contains("监听：http://"));
            check("带外 Jar 托管输出给出 Zip 魔数校验结论",
                    oobOutput.contains("Zip 魔数校验通过"));
            check("带外 Jar 地址端口就是配置里填的端口",
                    oobStatus.contains(":" + oobPort + "/"));

            String jarUrl = firstAddress(oobOutput);
            byte[] jarBytes = httpGet(jarUrl);
            System.out.println("带外 Jar 拉取: " + jarUrl + " -> " + jarBytes.length + " 字节");
            check("按返回的地址能真正取到 Jar 字节", jarBytes.length > 0);
            check("取回的字节以 PK 魔数开头（是合法 Zip）",
                    jarBytes.length > 4 && jarBytes[0] == 0x50 && jarBytes[1] == 0x4B
                            && jarBytes[2] == 0x03 && jarBytes[3] == 0x04);

            // 停止后地址应立即失效：端口没释放出去会让下次启动撞上「端口被占用」
            click(main, "oobJarStop");
            String stopped = ((javax.swing.JLabel) read(main, "oobJarStatus")).getText();
            System.out.println("带外 Jar 停止状态: " + stopped);
            check("停止托管后状态栏报告已释放端口", stopped.contains("已停止托管"));
            check("停止托管后端口可再次绑定", canBind(oobPort));

            System.out.println("端到端自检通过");

        } finally {
            server.stop(0);
            awaitCleanup();
        }
    }

    /**
     * 取一个当前空闲的端口。
     *
     * <p>不能写死 50001：本机上可能已经跑着使用者的服务，碰撞会让自检在
     * 「端口被占用」上失败，看起来像功能坏了。绑 0 让系统分配后再立刻释放，
     * 保留「刚释放、大概率还空着」的窗口。
     */
    private static int freePort() throws Exception {
        java.net.ServerSocket probe = new java.net.ServerSocket(0);
        int port = probe.getLocalPort();
        probe.close();
        return port;
    }

    /** 端口能否再次绑定：用于断言托管停止后确实释放了监听。 */
    private static boolean canBind(int port) {
        java.net.ServerSocket probe = null;
        try {
            probe = new java.net.ServerSocket(port);
            return true;
        } catch (IOException error) {
            return false;
        } finally {
            if (probe != null) {
                try {
                    probe.close();
                } catch (IOException ignored) {
                    // 关闭阶段的异常不该影响断言结论
                }
            }
        }
    }

    /** 从托管输出里取第一行地址：格式固定为「地址：<url>」。 */
    private static String firstAddress(String output) {
        for (String line : output.split("\\R")) {
            if (line.startsWith("地址：")) return line.substring("地址：".length()).trim();
        }
        return "";
    }

    /** 取一次 HTTP 响应的全部字节：不跟随跳转，失败时返回空数组由断言暴露。 */
    private static byte[] httpGet(String url) throws Exception {
        java.net.HttpURLConnection connection =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        java.io.InputStream stream = null;
        try {
            int code = connection.getResponseCode();
            stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) return new byte[0];
            return readAll(stream);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // 关闭阶段的异常不该影响取回的字节
                }
            }
            connection.disconnect();
        }
    }

    /**
     * 轮询状态标签，直到它给出一条结论。
     *
     * <p>判据是「非空且不含正在进行时的省略号」：点完按钮先读到的是「正在生成…」，
     * 而进页前状态还是空串，只判「不等于进行中的那条」会在空串上立刻返回。
     */
    private static String awaitStatus(Object main, String field, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String text = "";
        while (System.currentTimeMillis() < deadline) {
            text = String.valueOf(((javax.swing.JLabel) read(main, field)).getText());
            if (!text.trim().isEmpty() && !text.endsWith("…")) return text;
            Thread.sleep(150);
        }
        return text;
    }

    /** 在 EDT 上点击某个按钮：必须走真实点击路径，否则监听器不会触发。 */
    private static void click(Object main, String name) throws Exception {
        final javax.swing.AbstractButton button =
                (javax.swing.AbstractButton) read(main, name);
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() { button.doClick(); }
        });
    }

    /** Python 子进程句柄回收需要时间，脚本退出前留出缓冲时间。 */
    private static void awaitCleanup() throws Exception {
        Thread.sleep(200);
    }

    /** 直接改配置对象里的值，模拟配置页保存后的效果（不落盘，避免污染使用者的配置）。 */
    private static void setConfigProperty(Object main, String key, String value) throws Exception {
        Field field = main.getClass().getDeclaredField("config");
        field.setAccessible(true);
        java.util.Properties properties = (java.util.Properties) field.get(main);
        properties.setProperty(key, value);
    }

    /** 直接调用文件上传动作：不依赖按钮点击，与探针 / 抓包自检的做法一致。 */
    private static String runUpload(Object main, String url, String file, String field,
                                    String fields, String headers) throws Exception {
        Method method = main.getClass().getDeclaredMethod("runUpload", String.class, String.class,
                String.class, String.class, String.class);
        method.setAccessible(true);
        return String.valueOf(method.invoke(main, url, file, field, fields, headers));
    }

    private static String runProbe(Object main, String url, String mode) throws Exception {
        Method method = main.getClass().getDeclaredMethod("runProbe", String.class, String.class, String.class);
        method.setAccessible(true);
        return String.valueOf(method.invoke(main, url, "5", mode));
    }

    private static void startDetection(Object main) throws Exception {
        Method method = main.getClass().getDeclaredMethod("startDetection");
        method.setAccessible(true);
        method.invoke(main);
    }

    /** 直接调用抓包 / 转换动作，等待结果写入抓包结果区。 */
    private static String runCapture(Object main, String url) throws Exception {
        return waitCapture(main, "startCapture");
    }

    private static String runConvert(Object main, String url) throws Exception {
        return waitCapture(main, "startConvert");
    }

    private static String waitCapture(Object main, String action) throws Exception {
        captureTextArea(main).setText("");
        Method method = main.getClass().getDeclaredMethod(action);
        method.setAccessible(true);
        method.invoke(main);
        long deadline = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < deadline) {
            String text = captureText(main);
            if (text.contains("格式转换:") && text.contains("探测结论:")) return text;
            Thread.sleep(150);
        }
        return captureText(main);
    }

    private static String captureText(Object main) {
        return captureTextArea(main).getText();
    }

    private static javax.swing.JTextArea captureTextArea(Object main) {
        return (javax.swing.JTextArea) read(main, "captureResult");
    }

    /** 等待后台探测线程按分段顺序写回结果区（以期望类分段末尾写入为完成信号）。 */
    private static String awaitResult(Object main, long millis) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            String text = resultText(main);
            if (text.contains("===== 期望类 =====") && text.contains("是否存在期望类:")) return text;
            Thread.sleep(200);
        }
        return resultText(main);
    }

    private static String resultText(Object main) {
        return ((javax.swing.JTextArea) read(main, "result")).getText();
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> modeKeys(Object main) throws Exception {
        Method method = main.getClass().getDeclaredMethod("modeKeys");
        method.setAccessible(true);
        return (java.util.List<String>) method.invoke(main);
    }

    private static String summarizeModes(String output) {
        StringBuilder text = new StringBuilder();
        for (String label : new String[]{"Fastjson 识别", "版本识别", "期望类"}) {
            if (text.length() > 0) text.append(" + ");
            text.append(output.contains("===== " + label + " =====") ? label : ("缺少 " + label));
        }
        return text.toString();
    }

    /** 经本地代理发送一次 POST，返回响应文本。 */
    private static String sendViaProxy(String url, int proxyPort, String cookie) throws Exception {
        java.net.HttpURLConnection viaProxy = (java.net.HttpURLConnection) new java.net.URL(url)
                .openConnection(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                        new java.net.InetSocketAddress("127.0.0.1", proxyPort)));
        viaProxy.setRequestMethod("POST");
        viaProxy.setRequestProperty("Content-Type", "application/json");
        viaProxy.setRequestProperty("Cookie", cookie);
        viaProxy.setDoOutput(true);
        viaProxy.getOutputStream().write("{\"age\":20}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.io.InputStream stream = viaProxy.getResponseCode() >= 400
                ? viaProxy.getErrorStream() : viaProxy.getInputStream();
        return readStream(stream);
    }

    /** 后台发一条请求并立即放弃读取：用于模拟浏览器的心跳等无关流量。 */
    private static void sendViaProxyQuietly(String url, int proxyPort, String cookie) {
        Thread thread = new Thread(() -> {
            try {
                sendViaProxy(url, proxyPort, cookie);
            } catch (Exception ignored) {
                // 无关流量失败与否不影响断言
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /** 轮询代理返回包文本域，直到出现期望内容或超时。 */
    private static String awaitProxyResponse(Object main, String needle, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String text = "";
        while (System.currentTimeMillis() < deadline) {
            text = ((JTextArea) read(main, "proxyResponseText")).getText();
            if (text.contains(needle)) return text;
            Thread.sleep(150);
        }
        return text;
    }

    /** 轮询指定文本域，直到出现期望内容或超时。 */
    private static String awaitProxyText(Object main, String field, String needle, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String text = "";
        while (System.currentTimeMillis() < deadline) {
            text = ((JTextArea) read(main, field)).getText();
            if (text.contains(needle)) return text;
            Thread.sleep(100);
        }
        return text;
    }

    /** 桩服务最后一次收到的请求体，用于确认放行时改过的包真的发到了目标。 */
    private static String lastOriginBody() {
        return FastjsonLikeHandler.lastBody;
    }

    private static String readStream(java.io.InputStream stream) throws Exception {
        if (stream == null) return "";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        stream.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void setText(Object main, String field, String value) {
        Object component = read(main, field);
        if (component instanceof javax.swing.JTextArea) {
            ((javax.swing.JTextArea) component).setText(value);
        } else {
            ((JTextField) component).setText(value);
        }
    }

    private static void setSelected(Object main, String field, boolean value) {
        ((AbstractButton) read(main, field)).setSelected(value);
    }

    /**
     * 切换勾选框并触发 ActionListener。
     *
     * {@code setSelected()} 只改状态、不派发事件，用它来模拟点击会让依赖监听器的联动逻辑
     * （例如拦截开关运行时装卸拦截器）整条链路都不执行，从而把产品代码误判成有 bug。
     * 这里统一走 {@code doClick()}，与真实点击一致。
     */
    private static void clickCheckBox(Object main, String field, boolean value) {
        AbstractButton box = (AbstractButton) read(main, field);
        if (box.isSelected() != value) box.doClick();
    }

    @SuppressWarnings("unchecked")
    private static void setCombo(Object main, String field, String value) {
        ((javax.swing.JComboBox<String>) read(main, field)).setSelectedItem(value);
    }

    private static void invokeVoid(Object main, String name) throws Exception {
        Method method = main.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(main);
    }

    /** 模拟左侧导航切换：走真实的 selectNav，触发页面重建逻辑。 */
    private static void selectNav(Object main, String key) throws Exception {
        Method method = main.getClass().getDeclaredMethod("selectNav", String.class);
        method.setAccessible(true);
        method.invoke(main, key);
    }

    /** 取控件：走 ui.UiHandle 稳定门面，界面内部拆分不再影响断言。 */
    private static Object read(Object main, String name) {
        return ui.UiHandle.get(main, name);
    }

    private static String conclusion(String output) {
        for (String line : output.split("\\R")) {
            if (line.startsWith("探测结论:")) return line.trim();
        }
        return output.trim();
    }

    private static void check(String message, boolean condition) {
        if (!condition) {
            throw new IllegalStateException("自检失败: " + message);
        }

        System.out.println("  [ok] " + message);
    }

    /** 模拟真实 Fastjson 端点：对不同探针给出解析器报错或正常回显。 */
    private static final class FastjsonLikeHandler implements HttpHandler {
        private static volatile String lastBody = "";

        public void handle(HttpExchange exchange) throws IOException {
            String body = new String(readAll(exchange), StandardCharsets.UTF_8);
            lastBody = body;
            String response;
            int status = 200;
            if (body.trim().equals("{\"@type\":")) {
                response = "com.alibaba.fastjson.JSONException: syntax error, expect {";
                status = 400;
            } else if (body.contains("Test.TestException")) {
                response = "com.alibaba.fastjson.JSONException: autoType is not support. Test.TestException";
                status = 400;
            } else if (body.contains("support.geo.Feature")) {
                response = "autoType is not support. com.alibaba.fastjson.support.geo.Feature";
                status = 400;
            } else if (body.contains("\"@type\":\"whatever\"")) {
                response = "autoType is not support. whatever";
                status = 400;
            } else if (body.contains("new a(1)")) {
                response = "{\"a\":1,\"b\":\"EQ==\",\"c\":[{}]}";
            } else if (body.contains("$ref")) {
                response = "{\"ext\":\"blue\",\"name\":\"blue\"}";
            } else if (body.contains("{}:{}")) {
                response = "com.alibaba.fastjson.JSONException: syntax error, expect {, actual {";
                status = 400;
            } else if (body.contains("\"age\":21")) {
                // 回显请求体特征：让「返回包属于本次改包后的请求」可被断言，
                // 否则面板里上一次的响应同样含 "ok":true，断言会恒真（时序一变就假失败）
                response = "{\"ok\":true,\"age\":21}";
            } else if (body.startsWith("{\"@type\":\"java.lang.AutoCloseable\"")) {
                response = "com.alibaba.fastjson.JSONException: syntax error, fastjson-version 1.2.68";
                status = 400;
            } else if (body.contains("JdbcRowSetImpl")) {
                response = "autoType is not support. com.sun.rowset.JdbcRowSetImpl";
                status = 400;
            } else if (body.contains("Random.String")) {
                response = "autoType is not support. Random.String";
                status = 400;
            } else if (body.contains("java.lang.Exception")) {
                response = "com.alibaba.fastjson.JSONException: autoType is not support";
                status = 400;
            } else if (body.contains("java.io.ByteArrayOutputStream")) {
                response = "{\"ok\":true}";
            } else {
                response = "{\"ok\":true}";
            }
            reply(exchange, status, response);
        }
    }

    /** 模拟只接受 GET 的登录页：任何 POST 都返回 405 且响应体为空。 */
    private static final class MethodNotAllowedHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            readAll(exchange);
            exchange.getResponseHeaders().add("Allow", "GET, HEAD");
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        }
    }

    /**
     * 模拟带登录拦截的靶场（Hello-Java-Sec 的 LoginHandlerInterceptor 行为）：
     * 未登录请求被 forward 到登录视图，POST 只得到 405 空响应，GET 返回登录页 HTML。
     * 登录页的 password 表单刻意放在正文深处，只有完整响应体才认得出来。
     */
    private static final class LoginGatedHandler implements HttpHandler {
        static final String PAGE = "<!DOCTYPE html><html><head><title>登录</title></head><body>"
                + "<form action=\"/user/login\" method=\"post\">"
                + "<input name=\"username\" /><input name=\"password\" />"
                + "<img src=\"/captcha\" />"
                + "<div style=\"display:none\">" + repeat("<!-- filler -->", 60) + "</div>"
                + "请先登录</form></body></html>";

        public void handle(HttpExchange exchange) throws IOException {
            readAll(exchange);
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                reply(exchange, 200, "text/html;charset=UTF-8", PAGE);
                return;
            }
            exchange.getResponseHeaders().add("Allow", "GET, HEAD");
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        }
    }

    /**
     * 模拟「登录后才可用」的 Fastjson 端点：会话 Cookie 合法时走真实解析器分支，
     * 否则与 LoginGatedHandler 一样返回 405 空响应，用来验证会话 Cookie 的解锁效果。
     */
    private static final class SessionAuthHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            String body = new String(readAll(exchange), StandardCharsets.UTF_8);
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                reply(exchange, 200, "text/html;charset=UTF-8", LoginGatedHandler.PAGE);
                return;
            }
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            boolean authorized = cookie != null && cookie.contains("JWT_TOKEN=")
                    && !cookie.contains("stale");
            if (!authorized) {
                exchange.getResponseHeaders().add("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            if (body.contains("\"@type\"")) {
                reply(exchange, 400, "application/json",
                        "com.alibaba.fastjson.JSONException: autoType is not support. whatever");
                return;
            }
            if (body.contains("$ref")) {
                reply(exchange, 200, "application/json", "{\"ext\":\"blue\",\"name\":\"blue\"}");
                return;
            }
            if (body.contains("new a(1)") || !body.trim().endsWith("}")) {
                reply(exchange, 400, "application/json",
                        "com.alibaba.fastjson.JSONException: syntax error, expect {");
                return;
            }
            reply(exchange, 200, "application/json", "{\"ok\":true}");
        }
    }

    /** 模拟上传接口：记录收到的 multipart 请求，返回可断言的 JSON 响应。 */
    private static final class UploadHandler implements HttpHandler {
        /** 文件内容里的标记，用来证明文件字节真的到达了目标。 */
        static final String MARKER = "JavaSecExpToolKit-upload-marker";
        static volatile String lastContentType = "";
        static volatile String lastBody = "";
        static volatile String lastCookie = "";

        public void handle(HttpExchange exchange) throws IOException {
            lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            lastCookie = exchange.getRequestHeaders().getFirst("Cookie");
            lastBody = new String(readAll(exchange), StandardCharsets.ISO_8859_1);
            reply(exchange, 200, "application/json",
                    "{\"code\":0,\"msg\":\"uploaded\",\"size\":\"" + lastBody.length() + "\"}");
        }
    }

    private static String repeat(String text, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) builder.append(text);
        return builder.toString();
    }

    private static void reply(HttpExchange exchange, int status, String response) throws IOException {
        reply(exchange, status, "application/json", response);
    }

    /** 指定 Content-Type 的应答：登录页必须按 HTML 返回，否则登录页特征不会被识别。 */
    private static void reply(HttpExchange exchange, int status, String contentType, String response)
            throws IOException {
        byte[] payload = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** 读一个任意输入流到字节数组：托管取 Jar 与桩服务读取请求体共用同一套写法。 */
    private static byte[] readAll(java.io.InputStream stream) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static byte[] readAll(HttpExchange exchange) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = exchange.getRequestBody().read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
