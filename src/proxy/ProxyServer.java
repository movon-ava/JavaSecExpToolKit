package proxy;

import util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地 HTTP 代理：把浏览器 / 插件的流量指过来即可实时看到请求与响应。
 *
 * 能力边界：
 * <ul>
 *   <li>明文 HTTP：解析请求行与头，转发到上游，记录完整请求与响应（含请求体、响应体、头）。</li>
 *   <li>HTTPS：只做 CONNECT 盲转发隧道，让浏览器照常访问，但**不解密、不记录内容**
 *       （需要中间人证书与 TLS 解密，属于后续能力）。</li>
 * </ul>
 * 只记录与转发，不修改流量。
 */
public final class ProxyServer {

    private static final int MAX_HEADER_BYTES = 128 * 1024;
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15000;

    /** 默认回环地址：仅在显式指定或探测不到联网地址时使用。 */
    public static final String LOOPBACK = "127.0.0.1";
    /** 通配地址：监听所有网卡。 */
    public static final String ANY = "0.0.0.0";

    /**
     * 探测本机联网地址（默认出口网卡上的 IPv4）。
     *
     * 先对一个外部地址做 UDP `connect`（只设置路由，不发送任何字节）拿到默认出口地址；
     * 失败时再枚举网卡取第一个非回环 IPv4；仍然拿不到时回落到 `127.0.0.1`。
     */
    public static String defaultBindHost() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            String address = ipv4Of(socket.getLocalAddress());
            if (address != null) return address;
        } catch (Exception ignored) {
            // 无默认路由时继续枚举网卡
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    String address = ipv4Of(addresses.nextElement());
                    if (address != null) return address;
                }
            }
        } catch (SocketException ignored) {
            // 网卡枚举失败即回落到回环地址
        }
        return LOOPBACK;
    }

    /** 只接受 IPv4 且非回环 / 非通配的地址；其他情况返回 null。 */
    private static String ipv4Of(InetAddress address) {
        if (address == null) return null;
        if (address instanceof java.net.Inet6Address) return null;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()) return null;
        String text = address.getHostAddress();
        return text == null || text.isEmpty() ? null : text;
    }

    /** 一条流量记录。HTTPS 隧道只记录 CONNECT 本身，`captured` 为 false。 */
    public static final class HttpFlow {
        public final long id;
        public final long startedAt;
        public volatile String scheme = "http";
        public volatile String host = "";
        public volatile int port = 80;
        public volatile String method = "";
        public volatile String target = "";
        public volatile String version = "HTTP/1.1";
        public volatile Map<String, String> requestHeaders = new LinkedHashMap<String, String>();
        public volatile byte[] requestBody = new byte[0];
        public volatile byte[] requestRaw = new byte[0];
        public volatile int status;
        public volatile String reason = "";
        public volatile Map<String, String> responseHeaders = new LinkedHashMap<String, String>();
        public volatile byte[] responseBody = new byte[0];
        public volatile byte[] responseRaw = new byte[0];
        public volatile long elapsedMs;
        public volatile String error;
        /** 是否解出了明文内容；CONNECT 隧道为 false。 */
        public volatile boolean captured = true;
        public volatile long tunneledBytes;

        HttpFlow(long id) {
            this.id = id;
            this.startedAt = System.currentTimeMillis();
        }

        public String url() {
            StringBuilder text = new StringBuilder();
            text.append(scheme).append("://").append(host);
            if (!(("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443))) {
                text.append(':').append(port);
            }
            text.append(target == null || target.isEmpty() ? "/" : target);
            return text.toString();
        }
    }

    /** 外部（界面）订阅抓包事件的回调。 */
    public interface FlowListener {
        void onFlow(HttpFlow flow);
    }

    /** 拦截后放行时使用的报文；head 为空表示沿用原请求头，body 为 null 表示沿用原请求体。 */
    public static final class Rewrite {
        /** 丢弃该请求：不连接上游，直接关闭客户端连接。 */
        public static final Rewrite DROP = new Rewrite(true, null, null);

        public final boolean drop;
        public final byte[] head;
        public final byte[] body;

        public Rewrite(byte[] head, byte[] body) {
            this(false, head, body);
        }

        public Rewrite(boolean drop, byte[] head, byte[] body) {
            this.drop = drop;
            this.head = head;
            this.body = body;
        }
    }

    /**
     * 请求拦截回调：在请求转发到上游之前同步调用，实现方可阻塞等待使用者决定放行或丢弃。
     */
    public interface Interceptor {
        /**
         * @param flow 待转发的流量（请求头与请求体已填充）
         * @param head 原始请求头字节（含请求行）
         * @param body 原始请求体字节
         * @return null 表示原样放行；否则按 {@link Rewrite} 中的报文转发
         */
        Rewrite onRequest(HttpFlow flow, byte[] head, byte[] body);
    }

    private final List<HttpFlow> flows = new ArrayList<HttpFlow>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final List<FlowListener> listeners = new ArrayList<FlowListener>();
    private final int maxFlows;
    private volatile Interceptor interceptor;

    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private volatile int port;
    private volatile String host = LOOPBACK;

    public ProxyServer(int maxFlows) {
        this.maxFlows = maxFlows > 0 ? maxFlows : 500;
    }

    public synchronized void addListener(FlowListener listener) {
        if (listener != null) listeners.add(listener);
    }

    /** 设置请求拦截回调；传 null 表示关闭拦截（转发路径零开销）。 */
    public void setInterceptor(Interceptor interceptor) {
        this.interceptor = interceptor;
    }

    public boolean isRunning() {
        return running;
    }

    public int port() {
        return port;
    }

    /** 实际绑定的监听地址。 */
    public String host() {
        return host;
    }

    /**
     * 供使用者填写到浏览器代理设置里的地址。
     *
     * 绑定 ``0.0.0.0`` 时回落到本机联网地址，便于局域网内其他设备直接使用。
     */
    public String displayHost() {
        if (ANY.equals(host) || "::".equals(host)) return defaultBindHost();
        return host;
    }

    /** 启动代理并绑定回环地址，等价于 {@code start("127.0.0.1", port)}。 */
    public synchronized void start(int requestedPort) throws IOException {
        start(LOOPBACK, requestedPort);
    }

    /**
     * 启动代理并绑定指定地址。
     *
     * `bindHost` 为空或空白时回落回环地址；传 {@link #ANY} 表示监听所有网卡。
     * `requestedPort` 为 0 时由系统分配随机端口。
     */
    public synchronized void start(String bindHost, int requestedPort) throws IOException {
        if (running) return;
        String resolved = bindHost == null ? "" : bindHost.trim();
        if (resolved.isEmpty()) resolved = LOOPBACK;
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(resolved, requestedPort));
        this.serverSocket = socket;
        this.host = resolved;
        this.port = socket.getLocalPort();
        this.running = true;
        Thread thread = new Thread(this::acceptLoop, "proxy-accept");
        thread.setDaemon(true);
        thread.start();
        Log.info("代理已启动，监听 " + resolved + ":" + this.port);
    }

    public synchronized void stop() {
        boolean wasRunning = running;
        running = false;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException closed) {
                // 监听已关闭：属正常收尾
                Log.debug("关闭代理监听端口：" + closed.getMessage());
            }
        }
        if (wasRunning) Log.info("代理已停止，释放端口 " + this.port);
    }

    public List<HttpFlow> snapshot() {
        synchronized (flows) {
            return new ArrayList<HttpFlow>(flows);
        }
    }

    public HttpFlow find(long id) {
        synchronized (flows) {
            for (HttpFlow flow : flows) {
                if (flow.id == id) return flow;
            }
        }
        return null;
    }

    public void clear() {
        synchronized (flows) {
            flows.clear();
        }
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket socket = serverSocket;
            if (socket == null) return;
            try {
                Socket client = socket.accept();
                Thread worker = new Thread(() -> handle(client), "proxy-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                // 停止时 accept 必然抛异常，属正常收尾；运行中失败才需要留痕
                if (!running) return;
                Log.warn("代理接受连接失败：" + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(0);
            client.setTcpNoDelay(true);
            InputStream input = client.getInputStream();
            byte[] head = readUntilHeaderEnd(input);
            if (head == null || head.length == 0) {
                client.close();
                return;
            }
            RequestHead request = parseRequestHead(head);
            if ("CONNECT".equalsIgnoreCase(request.method)) {
                tunnel(client, request);
            } else {
                handlePlain(client, request, head);
            }
        } catch (Exception error) {
            // 单条连接异常不影响代理整体，但必须留痕：否则表现为「代理没反应」
            Log.warn("代理处理连接异常：" + error.getClass().getSimpleName() + ": " + error.getMessage(),
                    error);
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
                // 连接已关闭
            }
        }
    }

    /** CONNECT：盲转发隧道，浏览器侧 HTTPS 照常工作，但内容不解密。 */
    private void tunnel(Socket client, RequestHead request) throws IOException {
        HttpFlow flow = new HttpFlow(nextId.getAndIncrement());
        flow.scheme = "https";
        flow.host = request.host;
        flow.port = request.port > 0 ? request.port : 443;
        flow.method = "CONNECT";
        flow.target = request.target;
        flow.version = request.version;
        flow.requestHeaders.putAll(request.headers);
        flow.captured = false;
        long started = System.nanoTime();

        Socket upstream = null;
        boolean recorded = false;
        try {
            upstream = new Socket();
            upstream.connect(new InetSocketAddress(flow.host, flow.port), CONNECT_TIMEOUT_MS);
            upstream.setTcpNoDelay(true);
            client.getOutputStream().write(
                    "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            flow.status = 200;
            flow.reason = "Connection Established";
            // 隧道一建立就先登记，界面可以立即看到这条 HTTPS 连接；
            // 后续只更新同一对象上的字节计数，不再重复记录。
            flow.elapsedMs = (System.nanoTime() - started) / 1000000;
            record(flow);
            recorded = true;

            final Socket upstreamRef = upstream;
            Thread pump = new Thread(() -> {
                try {
                    pumpBytes(client, upstreamRef, flow);
                } catch (IOException ended) {
                    // 任一方向断开即结束隧道；连接正常关闭也会走到这里，故用调试级
                    Log.debug("隧道方向 客户端→上游 结束：" + ended.getMessage());
                }
            }, "proxy-tunnel");
            pump.setDaemon(true);
            pump.start();
            try {
                pumpBytes(upstream, client, flow);
            } catch (IOException ended) {
                // 客户端断开属常见情况，故用调试级
                Log.debug("隧道方向 上游→客户端 结束：" + ended.getMessage());
            }
            pump.join(1000);
        } catch (Exception error) {
            flow.error = error.getClass().getSimpleName() + ": " + error.getMessage();
            Log.warn("HTTPS 隧道无法连接上游 " + flow.host + ":" + flow.port + "："
                    + flow.error, error);
        } finally {
            flow.elapsedMs = (System.nanoTime() - started) / 1000000;
            if (!recorded) record(flow);
            if (upstream != null) {
                try {
                    upstream.close();
                } catch (IOException ignored) {
                    // 上游已关闭
                }
            }
        }
    }

    /** 双向复制隧道字节；直接累加到 flow 上，界面可实时看到已传输量。 */
    private void pumpBytes(Socket from, Socket to, HttpFlow flow) throws IOException {
        InputStream input = from.getInputStream();
        OutputStream output = to.getOutputStream();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = input.read(buffer)) > 0) {
            output.write(buffer, 0, read);
            output.flush();
            flow.tunneledBytes += read;
        }
    }

    private void handlePlain(Socket client, RequestHead first, byte[] head) throws IOException {
        InputStream input = client.getInputStream();
        OutputStream output = client.getOutputStream();
        RequestHead request = first;
        byte[] headerBytes = head;
        while (running) {
            boolean keepAlive = forward(request, headerBytes, input, output);
            if (!keepAlive) return;
            byte[] next = readUntilHeaderEnd(input);
            if (next == null || next.length == 0) return;
            request = parseRequestHead(next);
            headerBytes = next;
        }
    }

    /**
     * 转发一条明文请求：读取请求体、连上游、把响应原样回写，同时记录完整流量。
     *
     * @return 是否保持连接
     */
    private boolean forward(RequestHead request, byte[] headerBytes, InputStream clientIn,
                            OutputStream clientOut) throws IOException {
        HttpFlow flow = new HttpFlow(nextId.getAndIncrement());
        flow.scheme = "http";
        flow.host = request.host;
        flow.port = request.port > 0 ? request.port : 80;
        flow.method = request.method;
        flow.target = request.target;
        flow.version = request.version;
        flow.requestHeaders.putAll(request.headers);
        long started = System.nanoTime();

        byte[] body = readMessageBody(clientIn, request.headers);
        flow.requestBody = body;
        flow.requestRaw = concat(headerBytes, body);

        Interceptor hook = interceptor;
        if (hook != null) {
            Rewrite rewrite = hook.onRequest(flow, headerBytes, body);
            if (rewrite != null && rewrite.drop) {
                flow.error = "请求已被拦截并丢弃";
                flow.elapsedMs = (System.nanoTime() - started) / 1000000;
                record(flow);
                return false;
            }
            if (rewrite != null) {
                if (rewrite.head != null && rewrite.head.length > 0) {
                    headerBytes = rewrite.head;
                    request = parseRequestHead(headerBytes);
                    flow.host = request.host;
                    flow.port = request.port > 0 ? request.port : 80;
                    flow.method = request.method;
                    flow.target = request.target;
                    flow.version = request.version;
                    flow.requestHeaders.clear();
                    flow.requestHeaders.putAll(request.headers);
                }
                if (rewrite.body != null) body = rewrite.body;
                flow.requestBody = body;
                flow.requestRaw = concat(headerBytes, body);
            }
        }

        Socket upstream = null;
        try {
            upstream = new Socket();
            upstream.connect(new InetSocketAddress(flow.host, flow.port), CONNECT_TIMEOUT_MS);
            upstream.setSoTimeout(60000);
            OutputStream upstreamOut = upstream.getOutputStream();
            upstreamOut.write(rewriteRequestLine(headerBytes, request));
            upstreamOut.write(body);
            upstreamOut.flush();

            InputStream upstreamIn = upstream.getInputStream();
            byte[] responseHead;
            StatusHead status;
            while (true) {
                responseHead = readUntilHeaderEnd(upstreamIn);
                if (responseHead == null) {
                    flow.error = "上游未返回响应";
                    flow.elapsedMs = (System.nanoTime() - started) / 1000000;
                    record(flow);
                    return false;
                }
                status = parseStatusHead(responseHead);
                // 1xx 是中间响应（典型为 Expect: 100-continue），原样回写后继续等最终响应
                if (status.code >= 100 && status.code < 200 && status.code != 101) {
                    clientOut.write(responseHead);
                    clientOut.flush();
                    continue;
                }
                break;
            }
            flow.status = status.code;
            flow.reason = status.reason;
            flow.responseHeaders.putAll(status.headers);
            boolean bodyless = "HEAD".equals(request.method)
                    || status.code == 204 || status.code == 304
                    || (status.code >= 100 && status.code < 200);
            byte[] responseBody = bodyless ? new byte[0] : readMessageBody(upstreamIn, status.headers);
            flow.responseBody = responseBody;
            flow.responseRaw = concat(responseHead, responseBody);

            clientOut.write(responseHead);
            clientOut.write(responseBody);
            clientOut.flush();
            flow.elapsedMs = (System.nanoTime() - started) / 1000000;
            record(flow);
            if (bodyless) return true;
            return isKeepAlive(request.version, request.headers, status.code, status.headers);
        } catch (Exception error) {
            flow.error = error.getClass().getSimpleName() + ": " + error.getMessage();
            record(flow);
            Log.warn("转发到上游失败 " + flow.method + " " + flow.host + ":" + flow.port
                    + "：" + flow.error, error);
            throw new IOException(flow.error, error);
        } finally {
            flow.elapsedMs = flow.elapsedMs == 0 ? (System.nanoTime() - started) / 1000000 : flow.elapsedMs;
            if (upstream != null) {
                try {
                    upstream.close();
                } catch (IOException ignored) {
                    // 上游已关闭
                }
            }
        }
    }

    /**
     * 是否复用客户端连接继续读下一条请求。
     *
     * 判定依据（缺一不可）：双方都没有声明 close；响应有明确长度界定（Content-Length 或 chunked），
     * 否则只能靠关闭连接表示结束；HTTP/1.0 默认短连接，只有显式 keep-alive 才复用。
     */
    private boolean isKeepAlive(String version, Map<String, String> requestHeaders, int status,
                                Map<String, String> responseHeaders) {
        String requestConnection = headerValue(requestHeaders, "connection");
        if (requestConnection == null) requestConnection = headerValue(requestHeaders, "proxy-connection");
        String responseConnection = headerValue(responseHeaders, "connection");
        if (requestConnection != null && requestConnection.toLowerCase(Locale.ROOT).contains("close")) return false;
        if (responseConnection != null && responseConnection.toLowerCase(Locale.ROOT).contains("close")) return false;
        boolean delimited = headerValue(responseHeaders, "content-length") != null
                || headerValue(responseHeaders, "transfer-encoding") != null;
        if (!delimited) return false;
        if (version != null && version.endsWith("1.0")) {
            return requestConnection != null
                    && requestConnection.toLowerCase(Locale.ROOT).contains("keep-alive");
        }
        return true;
    }

    private void record(HttpFlow flow) {
        synchronized (flows) {
            flows.add(flow);
            while (flows.size() > maxFlows) flows.remove(0);
        }
        List<FlowListener> copy;
        synchronized (listeners) {
            copy = new ArrayList<FlowListener>(listeners);
        }
        for (FlowListener listener : copy) {
            try {
                listener.onFlow(flow);
            } catch (RuntimeException failed) {
                // 界面回调异常不应影响转发，但要留痕：否则表现为「抓包列表不更新」
                Log.warn("抓包回调异常：" + failed.getClass().getSimpleName() + ": " + failed.getMessage(),
                        failed);
            }
        }
    }

    // ---------- 报文解析 ----------

    static final class RequestHead {
        String method = "";
        String target = "";
        String version = "HTTP/1.1";
        String host = "";
        int port = -1;
        final Map<String, String> headers = new LinkedHashMap<String, String>();
    }

    static final class StatusHead {
        int code;
        String reason = "";
        final Map<String, String> headers = new LinkedHashMap<String, String>();
    }

    /** 读取到首个空行（含）为止；流结束且无数据时返回 null。 */
    static byte[] readUntilHeaderEnd(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        while (true) {
            int read = input.read();
            if (read < 0) {
                if (buffer.size() == 0) return null;
                return buffer.toByteArray();
            }
            buffer.write(read);
            if (buffer.size() > MAX_HEADER_BYTES) throw new IOException("报文头部过大");
            if ((matched == 0 || matched == 2) && read == '\r') matched++;
            else if ((matched == 1 || matched == 3) && read == '\n') matched++;
            else matched = 0;
            if (matched == 4) return buffer.toByteArray();
        }
    }

    static RequestHead parseRequestHead(byte[] head) throws IOException {
        String text = new String(head, StandardCharsets.ISO_8859_1);
        String[] lines = text.split("\r\n", -1);
        RequestHead result = new RequestHead();
        if (lines.length == 0 || lines[0].trim().isEmpty()) throw new IOException("空请求行");
        String[] parts = lines[0].split(" ");
        result.method = parts[0].toUpperCase(Locale.ROOT);
        if (parts.length > 1) result.target = parts[1];
        if (parts.length > 2) result.version = parts[2];
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            String existing = result.headers.get(name);
            result.headers.put(name, existing == null ? value : existing + ", " + value);
        }
        String hostHeader = headerValue(result.headers, "host");
        if (hostHeader != null) {
            int colon = hostHeader.lastIndexOf(':');
            if (colon > 0 && hostHeader.indexOf(':') == colon) {
                result.host = hostHeader.substring(0, colon);
                try {
                    result.port = Integer.parseInt(hostHeader.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                    result.port = -1;
                }
            } else {
                result.host = hostHeader;
            }
        }
        // 绝对形式（代理请求）以 URL 为准，并改写成 origin-form 再转发
        if (result.target.startsWith("http://") || result.target.startsWith("https://")) {
            java.net.URL url = new java.net.URL(result.target);
            result.host = url.getHost();
            result.port = url.getPort() > 0 ? url.getPort() : url.getDefaultPort();
            result.target = (url.getPath() == null || url.getPath().isEmpty() ? "/" : url.getPath())
                    + (url.getQuery() == null ? "" : "?" + url.getQuery());
        }
        return result;
    }

    static StatusHead parseStatusHead(byte[] head) {
        String text = new String(head, StandardCharsets.ISO_8859_1);
        String[] lines = text.split("\r\n", -1);
        StatusHead result = new StatusHead();
        if (lines.length > 0) {
            String[] parts = lines[0].split(" ", 3);
            if (parts.length > 1) {
                try {
                    result.code = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    result.code = 0;
                }
            }
            if (parts.length > 2) result.reason = parts[2].trim();
        }
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            result.headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        return result;
    }

    static byte[] readMessageBody(InputStream input, Map<String, String> headers) throws IOException {
        String transferEncoding = headerValue(headers, "transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunked(input);
        }
        String contentLength = headerValue(headers, "content-length");
        if (contentLength != null) {
            int length;
            try {
                length = Integer.parseInt(contentLength.trim());
            } catch (NumberFormatException e) {
                return new byte[0];
            }
            if (length <= 0) return new byte[0];
            return readFully(input, Math.min(length, MAX_BODY_BYTES));
        }
        return new byte[0];
    }

    /** 原样保留分块编码（长度行 + 数据 + CRLF + trailer），保证回写字节一致。 */
    private static byte[] readChunked(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            String sizeText = readLine(input, 64);
            byte[] sizeBytes = (sizeText + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
            buffer.write(sizeBytes, 0, sizeBytes.length);
            String value = sizeText.trim();
            int semicolon = value.indexOf(';');
            if (semicolon >= 0) value = value.substring(0, semicolon);
            int size;
            try {
                size = Integer.parseInt(value.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("非法分块长度: " + sizeText);
            }
            if (size > MAX_BODY_BYTES || buffer.size() > MAX_BODY_BYTES) throw new IOException("报文过大");
            byte[] chunk = readFully(input, size);
            buffer.write(chunk, 0, chunk.length);
            if (size == 0) {
                String trailer = readLine(input, MAX_HEADER_BYTES);
                while (!trailer.isEmpty()) {
                    byte[] line = (trailer + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
                    buffer.write(line, 0, line.length);
                    trailer = readLine(input, MAX_HEADER_BYTES);
                }
                buffer.write('\r');
                buffer.write('\n');
                return buffer.toByteArray();
            }
            byte[] crlf = readFully(input, 2);
            buffer.write(crlf, 0, crlf.length);
        }
    }

    /** 读一行（不含 CRLF），用于分块长度与 trailer。 */
    private static String readLine(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (buffer.size() < limit) {
            int read = input.read();
            if (read < 0) break;
            if (read == '\n') {
                byte[] raw = buffer.toByteArray();
                int length = raw.length;
                if (length > 0 && raw[length - 1] == '\r') length--;
                return new String(raw, 0, length, StandardCharsets.ISO_8859_1);
            }
            buffer.write(read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(buffer, offset, length - offset);
            if (read < 0) break;
            offset += read;
        }
        if (offset == length) return buffer;
        byte[] trimmed = new byte[offset];
        System.arraycopy(buffer, 0, trimmed, 0, offset);
        return trimmed;
    }

    /** 把请求行改写成 origin-form（去掉 scheme://host），其余字节原样保留。 */
    static byte[] rewriteRequestLine(byte[] head, RequestHead request) {
        String text = new String(head, StandardCharsets.ISO_8859_1);
        int lineEnd = text.indexOf("\r\n");
        if (lineEnd < 0) return head;
        String requestLine = request.method + " " + request.target + " " + request.version + "\r\n";
        return concat(requestLine.getBytes(StandardCharsets.ISO_8859_1),
                text.substring(lineEnd + 2).getBytes(StandardCharsets.ISO_8859_1));
    }

    static String headerValue(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
