package probe;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import util.HttpText;
import util.JsonText;

/**
 * 抓包结果与探测页 / Shiro 页之间的转换：把「一次捕获」翻译成各功能能用的参数。
 *
 * <p>这里刻意不依赖任何 Swing 控件，只接收抓包页那几个文本域的内容，
 * 便于单独验证「Cookie 是否被带上」「请求头是否合法」这类容易出错的细节。
 */
public final class CaptureBridge {

    /** 抓包页当前填写的输入。 */
    public static final class Input {
        /** 请求头 JSON，例如 {@code {"Cookie":"JWT=xxx"}}。 */
        public String headersJson = "";
        /** 粘贴的原始请求报文（可选）。 */
        public String pastedRequest = "";
        /** 抓包结果（引擎输出，含 cookie-header 字段）。 */
        public String captureResult = "";
        /** 抓包页「请求体」输入框。 */
        public String body = "";
    }

    private CaptureBridge() {
    }

    /** 从抓包输入里取会话 Cookie；取不到时返回空串。 */
    public static String cookieHeader(Input input) {
        String headers = trim(input.headersJson);
        if (!headers.isEmpty()) {
            String cookie = JsonText.flatValue(headers, "cookie");
            if (!cookie.isEmpty()) return cookie;
        }
        String pasted = input.pastedRequest;
        if (pasted != null && !pasted.isEmpty()) {
            Matcher matcher = Pattern.compile("(?im)^\\s*Cookie\\s*:\\s*(.+)$").matcher(pasted);
            if (matcher.find()) return matcher.group(1).trim();
        }
        return JsonText.field(input.captureResult, "cookie-header");
    }

    /** 探测页要用的请求头 JSON：优先沿用抓包页填写的，其次按粘贴报文补出 Cookie。 */
    public static String probeHeaders(Input input) {
        String headers = trim(input.headersJson);
        if (!headers.isEmpty()) return headers;
        String pasted = trim(input.pastedRequest);
        if (!pasted.isEmpty()) {
            String cookie = cookieHeader(input);
            if (!cookie.isEmpty()) return headerJson(cookie);
        }
        return "";
    }

    /** 把 JSON 请求头转成 Shiro 页使用的「每行一个 Key: Value」。 */
    public static String headerLines(Input input, String cookieHeader) {
        Map<String, String> entries = JsonText.flatObject(trim(input.headersJson));
        StringBuilder lines = new StringBuilder();
        if (entries != null) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                if (!forwardable(entry.getKey())) continue;
                lines.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        } else {
            Matcher matcher = Pattern.compile("(?m)^([A-Za-z0-9\\-]+):\\s*(.+)$")
                    .matcher(input.pastedRequest == null ? "" : input.pastedRequest);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (!forwardable(name)) continue;
                lines.append(name).append(": ").append(matcher.group(2).trim()).append("\n");
            }
        }
        // Cookie 必须带上：Shiro 页只会在 rememberMe 之外再追加会话 Cookie，
        // 两条 Cookie 会合并成一行发送，登录态才不会丢
        boolean hasCookie = false;
        for (String line : lines.toString().split("\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("cookie:")) hasCookie = true;
        }
        if (!hasCookie && cookieHeader != null && !cookieHeader.isEmpty()) {
            lines.append("Cookie: ").append(cookieHeader).append("\n");
        }
        return lines.toString().trim();
    }

    /**
     * 抓包页当前输入对应的请求体。
     *
     * <p>优先用粘贴的原始报文（那是从代理直接带过来的真实报文），
     * 其次用「请求体」输入框；拿不到时返回空串。
     */
    public static String requestBody(Input input) {
        String pasted = trim(input.pastedRequest);
        if (!pasted.isEmpty()) {
            String body = HttpText.bodyText(pasted);
            if (!body.isEmpty()) return body;
        }
        return trim(input.body);
    }

    /**
     * 该请求头能否原样带给探测 / Shiro 页。
     *
     * <p>{@code Host} 由目标 URL 自己决定；其余跳转头与
     * {@code Content-Length} 由 {@link HttpText#forwardable(String)} 统一排除——
     * 否则抓包得到的长度会跟着探测请求一起发出去，目标会等一个永远不到的请求体。
     */
    private static boolean forwardable(String name) {
        return name != null && !name.equalsIgnoreCase("host") && HttpText.forwardable(name);
    }

    /** Cookie 文本转成探测页的请求头 JSON。 */
    public static String headerJson(String cookieHeader) {
        return "{\"Cookie\":\"" + cookieHeader.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}

