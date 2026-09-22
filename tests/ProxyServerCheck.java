import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import proxy.ProxyServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 代理自检：明文 HTTP 完整记录；HTTPS 走 CONNECT 隧道透传（不解密）。 */
public final class ProxyServerCheck {

    public static void main(String[] args) throws Exception {
        // 与其他自检保持同一套下线清理：本自检只发 HTTP，不会构建载荷，
        // 但统一装上守卫可以让「跑完全部自检」永远不留计算器进程
        TestProcessGuard.install("ProxyServerCheck");
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/echo", new EchoHandler());
        origin.start();
        int originPort = origin.getAddress().getPort();

        ProxyServer proxy = new ProxyServer(100);
        final List<ProxyServer.HttpFlow> seen = new ArrayList<ProxyServer.HttpFlow>();
        proxy.addListener(new ProxyServer.FlowListener() {
            public void onFlow(ProxyServer.HttpFlow flow) {
                synchronized (seen) { seen.add(flow); }
            }
        });
        proxy.start(0);
        try {
            Proxy javaProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", proxy.port()));
            check("代理已启动并分配端口", proxy.isRunning() && proxy.port() > 0);

            // 1) 明文 HTTP：请求行 / 头 / 体 / 状态码 / 响应头体都要记录
            HttpURLConnection plain = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + originPort + "/echo?x=1").openConnection(javaProxy);
            plain.setRequestMethod("POST");
            plain.setRequestProperty("Content-Type", "application/json");
            plain.setRequestProperty("Cookie", "JWT_TOKEN=abc123");
            plain.setDoOutput(true);
            plain.getOutputStream().write("{\"age\":20,\"name\":\"Bob\"}".getBytes(StandardCharsets.UTF_8));
            String plainBody = read(plain.getInputStream());
            System.out.println("明文响应: " + plainBody);
            check("明文 HTTP 响应正确回传", plainBody.contains("\"age\":20"));
            check("明文 HTTP 状态码为 200", plain.getResponseCode() == 200);

            // 2) 明文 GET + 404：验证错误响应也可记录
            HttpURLConnection missing = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + originPort + "/nope").openConnection(javaProxy);
            check("404 也能经代理返回", missing.getResponseCode() == 404);

            // 3) HTTPS：只做隧道透传，浏览器语义不受影响（这里用普通 socket 模拟 CONNECT）
            int tlsPort = startFakeTlsOrigin();
            Socket tunnelClient = new Socket("127.0.0.1", proxy.port());
            tunnelClient.setSoTimeout(8000);
            OutputStream tunnelOut = tunnelClient.getOutputStream();
            tunnelOut.write(("CONNECT 127.0.0.1:" + tlsPort + " HTTP/1.1\r\nHost: 127.0.0.1:"
                    + tlsPort + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            tunnelOut.flush();
            String established = readUntilBlankLine(tunnelClient.getInputStream());
            System.out.println("CONNECT 响应: " + established);
            check("CONNECT 返回 200 建立隧道", established.contains("200"));
            // 隧道建立后双向字节应原样透传
            byte[] marker = "TUNNEL-PAYLOAD-1234567890".getBytes(StandardCharsets.US_ASCII);
            tunnelOut.write(marker);
            tunnelOut.flush();
            byte[] echoed = new byte[marker.length];
            int got = 0;
            while (got < marker.length) {
                int read = tunnelClient.getInputStream().read(echoed, got, marker.length - got);
                if (read < 0) break;
                got += read;
            }
            check("隧道内字节双向透传", new String(echoed, 0, Math.max(got, 0), StandardCharsets.US_ASCII)
                    .equals(new String(marker, StandardCharsets.US_ASCII)));
            tunnelClient.close();

            Thread.sleep(400);
            List<ProxyServer.HttpFlow> flows;
            synchronized (seen) { flows = new ArrayList<ProxyServer.HttpFlow>(seen); }
            System.out.println("记录条数: " + flows.size());
            check("回调收到流量事件", flows.size() >= 3);

            ProxyServer.HttpFlow post = findByMethod(flows, "POST");
            check("记录含请求方法", "POST".equals(post.method));
            check("记录含完整 URL", post.url().startsWith("http://127.0.0.1")
                    && post.url().contains("/echo?x=1"));
            check("记录含请求头（含 Cookie）", headerOf(post.requestHeaders, "Cookie").contains("JWT_TOKEN=abc123"));
            check("记录含请求体", new String(post.requestBody, StandardCharsets.UTF_8).contains("\"age\":20"));
            check("记录含响应状态", post.status == 200);
            check("记录含响应头", !post.responseHeaders.isEmpty());
            check("记录含响应体", new String(post.responseBody, StandardCharsets.UTF_8).contains("\"age\":20"));
            check("记录无错误", post.error == null);
            check("记录标记为已解密捕获", post.captured);

            ProxyServer.HttpFlow connect = findConnect(flows);
            check("CONNECT 记录标记为未解密", !connect.captured);
            check("CONNECT 记录为 https 协议", "https".equals(connect.scheme));
            check("CONNECT 记录主机与端口", "127.0.0.1".equals(connect.host) && connect.port == tlsPort);
            check("CONNECT 记录隧道字节数", connect.tunneledBytes > 0);

            check("可按 id 取回记录", proxy.find(post.id) == post);
            check("可清空记录", clearAndCount(proxy) == 0);

            // 4) 监听地址：默认取本机联网 IP，可改绑回环 / 通配
            String lan = ProxyServer.defaultBindHost();
            System.out.println("默认监听地址: " + lan);
            check("默认监听地址非空", lan != null && !lan.trim().isEmpty());

            ProxyServer lanBound = new ProxyServer(10);
            lanBound.start(lan, 0);
            try {
                check("可按指定联网地址启动", lanBound.isRunning() && lanBound.host().equals(lan));
                check("联网地址监听端口有效", lanBound.port() > 0);
                check("displayHost 回显绑定地址", lanBound.displayHost().equals(lan));
            } finally {
                lanBound.stop();
            }

            ProxyServer anyBound = new ProxyServer(10);
            anyBound.start(ProxyServer.ANY, 0);
            try {
                check("可绑定通配地址", ProxyServer.ANY.equals(anyBound.host()));
                check("通配绑定时 displayHost 回落为可填写地址",
                        !ProxyServer.ANY.equals(anyBound.displayHost())
                                && !"::".equals(anyBound.displayHost()));
            } finally {
                anyBound.stop();
            }

            // 5) 请求拦截：改包后放行，目标应收到改过的报文；丢弃则不转发
            ProxyServer intercepted = new ProxyServer(10);
            intercepted.setInterceptor(new ProxyServer.Interceptor() {
                public ProxyServer.Rewrite onRequest(ProxyServer.HttpFlow flow, byte[] head, byte[] body) {
                    String headText = new String(head, StandardCharsets.ISO_8859_1);
                    String replaced = headText.replace("JWT_TOKEN=abc123", "JWT_TOKEN=rewritten");
                    String bodyText = new String(body, StandardCharsets.UTF_8).replace("\"age\":20", "\"age\":21");
                    byte[] newBody = bodyText.getBytes(StandardCharsets.UTF_8);
                    String rebuilt = replaced.replaceAll("(?i)Content-Length: \\d+",
                            "Content-Length: " + newBody.length);
                    return new ProxyServer.Rewrite(rebuilt.getBytes(StandardCharsets.ISO_8859_1), newBody);
                }
            });
            intercepted.start(0);
            try {
                HttpURLConnection rewritten = (HttpURLConnection) new URL(
                        "http://127.0.0.1:" + originPort + "/echo").openConnection(
                        new Proxy(Proxy.Type.HTTP,
                                new InetSocketAddress("127.0.0.1", intercepted.port())));
                rewritten.setRequestMethod("POST");
                rewritten.setRequestProperty("Cookie", "JWT_TOKEN=abc123");
                rewritten.setDoOutput(true);
                rewritten.getOutputStream().write("{\"age\":20}".getBytes(StandardCharsets.UTF_8));
                String body = read(rewritten.getInputStream());
                System.out.println("拦截后响应: " + body);
                check("放行后目标收到改写过的请求体", body.contains("\"age\":21"));
                List<ProxyServer.HttpFlow> rewrittenFlows = intercepted.snapshot();
                check("放行后记录里保存的是改写过的请求头", !rewrittenFlows.isEmpty()
                        && "JWT_TOKEN=rewritten".equals(
                                headerValue(rewrittenFlows.get(0).requestHeaders, "Cookie")));
            } finally {
                intercepted.stop();
            }

            ProxyServer dropper = new ProxyServer(10);
            dropper.setInterceptor(new ProxyServer.Interceptor() {
                public ProxyServer.Rewrite onRequest(ProxyServer.HttpFlow flow, byte[] head, byte[] body) {
                    return ProxyServer.Rewrite.DROP;
                }
            });
            dropper.start(0);
            try {
                HttpURLConnection dropped = (HttpURLConnection) new URL(
                        "http://127.0.0.1:" + originPort + "/echo").openConnection(
                        new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", dropper.port())));
                dropped.setConnectTimeout(3000);
                dropped.setReadTimeout(3000);
                boolean failed = false;
                try {
                    dropped.getResponseCode();
                } catch (IOException e) {
                    failed = true;
                }
                check("丢弃时不连接上游且客户端拿不到响应", failed
                        || dropped.getResponseCode() != 200);
                List<ProxyServer.HttpFlow> droppedFlows = dropper.snapshot();
                check("被丢弃的流量也会记录以便排查",
                        !droppedFlows.isEmpty() && droppedFlows.get(0).error != null);
            } finally {
                dropper.stop();
            }

            // 6) 运行中装卸拦截器：代理先启动、随后才开启拦截是最常见用法，
            //    若只在 start() 时读一次开关，勾选了也完全不拦（历史缺陷）
            ProxyServer lateBound = new ProxyServer(10);
            lateBound.start(0);
            try {
                HttpURLConnection before = (HttpURLConnection) new URL(
                        "http://127.0.0.1:" + originPort + "/echo").openConnection(
                        new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", lateBound.port())));
                before.setRequestMethod("POST");
                before.setDoOutput(true);
                before.getOutputStream().write("{\"age\":20}".getBytes(StandardCharsets.UTF_8));
                String passThrough = read(before.getInputStream());
                check("未装载拦截器时请求直接转发", passThrough.contains("\"age\":20"));

                lateBound.setInterceptor(new ProxyServer.Interceptor() {
                    public ProxyServer.Rewrite onRequest(ProxyServer.HttpFlow flow, byte[] head, byte[] body) {
                        byte[] rewritten = new String(body, StandardCharsets.UTF_8)
                                .replace("\"age\":20", "\"age\":99")
                                .getBytes(StandardCharsets.UTF_8);
                        return new ProxyServer.Rewrite(null, rewritten);
                    }
                });
                HttpURLConnection afterStart = (HttpURLConnection) new URL(
                        "http://127.0.0.1:" + originPort + "/echo").openConnection(
                        new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", lateBound.port())));
                afterStart.setRequestMethod("POST");
                afterStart.setDoOutput(true);
                afterStart.getOutputStream().write("{\"age\":20}".getBytes(StandardCharsets.UTF_8));
                String lateIntercepted = read(afterStart.getInputStream());
                check("运行中装载拦截器立即生效", lateIntercepted.contains("\"age\":99"));

                lateBound.setInterceptor(null);
                HttpURLConnection detached = (HttpURLConnection) new URL(
                        "http://127.0.0.1:" + originPort + "/echo").openConnection(
                        new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", lateBound.port())));
                detached.setRequestMethod("POST");
                detached.setDoOutput(true);
                detached.getOutputStream().write("{\"age\":20}".getBytes(StandardCharsets.UTF_8));
                String afterDetach = read(detached.getInputStream());
                check("卸载拦截器后恢复原样转发", afterDetach.contains("\"age\":20"));
            } finally {
                lateBound.stop();
            }

            ProxyServer blankBound = new ProxyServer(10);
            blankBound.start("   ", 0);
            try {
                check("空白监听地址回落回环", ProxyServer.LOOPBACK.equals(blankBound.host()));
            } finally {
                blankBound.stop();
            }
            System.out.println("代理自检通过");
        } finally {
            proxy.stop();
            origin.stop(0);
        }
    }

    private static int clearAndCount(ProxyServer proxy) {
        proxy.clear();
        return proxy.snapshot().size();
    }

    private static String headerValue(java.util.Map<String, String> headers, String name) {
        for (java.util.Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    private static ProxyServer.HttpFlow findByMethod(List<ProxyServer.HttpFlow> flows, String method) {
        for (ProxyServer.HttpFlow flow : flows) {
            if (method.equals(flow.method)) return flow;
        }
        throw new IllegalStateException("未找到 " + method + " 流量");
    }

    private static ProxyServer.HttpFlow findConnect(List<ProxyServer.HttpFlow> flows) {
        for (ProxyServer.HttpFlow flow : flows) {
            if ("CONNECT".equals(flow.method)) return flow;
        }
        throw new IllegalStateException("未找到 CONNECT 流量");
    }

    private static String headerOf(java.util.Map<String, String> headers, String name) {
        for (java.util.Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return "";
    }

    /** 起一个假 TLS 端口：隧道建好后把收到的字节原样回送，用于验证透传。 */
    private static int startFakeTlsOrigin() throws IOException {
        final ServerSocket server = new ServerSocket(0);
        Thread thread = new Thread(() -> {
            try {
                Socket socket = server.accept();
                socket.setSoTimeout(8000);
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                    output.flush();
                }
                socket.close();
            } catch (IOException ignored) {
                // 测试结束
            }
        }, "fake-tls-origin");
        thread.setDaemon(true);
        thread.start();
        return server.getLocalPort();
    }

    /** \u8bfb\u5230\u7a7a\u884c\u4e3a\u6b62\uff08\u542b\uff09\uff0c\u907f\u514d\u6b8b\u7559\u7684 CRLF \u6c61\u67d3\u540e\u7eed\u900f\u4f20\u6570\u636e\u3002 */
    private static String readUntilBlankLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        int read;
        while ((read = input.read()) >= 0) {
            buffer.write(read);
            if ((matched == 0 || matched == 2) && read == '\r') matched++;
            else if ((matched == 1 || matched == 3) && read == '\n') matched++;
            else matched = 0;
            if (matched == 4) break;
        }
        String text = new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
        int end = text.indexOf("\r\n");
        return end < 0 ? text.trim() : text.substring(0, end);
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int read;
        while ((read = input.read()) >= 0) {
            if (read == '\n') break;
            buffer.write(read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1).trim();
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int got;
        while ((got = input.read(chunk)) > 0) buffer.write(chunk, 0, got);
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void check(String message, boolean condition) {
        if (!condition) throw new IllegalStateException("自检失败: " + message);
        System.out.println("  [ok] " + message);
    }

    private static final class EchoHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int got;
            while ((got = exchange.getRequestBody().read(chunk)) > 0) buffer.write(chunk, 0, got);
            if (exchange.getRequestURI().getPath().startsWith("/nope")) {
                byte[] notFound = "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(404, notFound.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(notFound); }
                return;
            }
            String request = buffer.toString("UTF-8");
            String response = request.isEmpty() ? "{\"ok\":true}" : request;
            byte[] payload = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
    }
}
