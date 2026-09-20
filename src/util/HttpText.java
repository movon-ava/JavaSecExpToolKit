package util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * 原始 HTTP 报文的字节级处理：分块编码还原、请求头 / 请求体切分、二进制判定。
 *
 * <p>界面只做明文展示与改包，因此这里只处理「头 + 体」两段式结构，
 * 不解析 transfer-encoding 之外的编码，也不重排任何请求头。
 */
public final class HttpText {

    private HttpText() {
    }

    /**
     * 判断一个请求头是否可以原样转发给「重新发起的探测请求」。
     *
     * <p>抓包得到的请求头不能照搬的有两类：
     * <ul>
     *   <li>hop-by-hop 头（{@code Proxy-Connection} / {@code Connection} /
     *       {@code Keep-Alive} 等）只对原连接有效；</li>
     *   <li>{@code Content-Length} 描述的是原请求体长度。探测请求自己决定体，
     *       若沿用旧值，目标会一直等一个不会到来的请求体而超时。</li>
     * </ul>
     */
    public static boolean forwardable(String name) {
        if (name == null) return false;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        return !"proxy-connection".equals(lower)
                && !"connection".equals(lower)
                && !"keep-alive".equals(lower)
                && !"content-length".equals(lower)
                && !"transfer-encoding".equals(lower)
                && !"te".equals(lower)
                && !"trailer".equals(lower)
                && !"upgrade".equals(lower)
                && !"via".equals(lower)
                && !"expect".equals(lower);
    }

    /** 按名字（不区分大小写）取请求头 / 响应头，缺失返回 null。 */
    public static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey().equalsIgnoreCase(name)) return header.getValue();
        }
        return null;
    }

    /** 请求体 / 响应体展示：按 UTF-8 解码，二进制只给长度提示。 */
    public static String body(byte[] body, Map<String, String> headers) {
        if (body == null || body.length == 0) return "";
        String transferEncoding = header(headers, "transfer-encoding");
        byte[] decoded = transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")
                ? dechunk(body) : body;
        if (looksBinary(decoded)) return "（二进制内容 " + decoded.length + " 字节）";
        return new String(decoded, StandardCharsets.UTF_8);
    }

    /**
     * 把界面上的请求包切分成「请求头字节」。
     *
     * <p>只重算 Content-Length，不改动其它请求头：使用者手工改包时无需自己数长度。
     * 缺少空行分隔（请求头与请求体之间的空行）时按无请求体处理。
     */
    public static byte[] headerBytes(String raw) {
        if (raw == null) return null;
        String normalized = raw.replace("\r\n", "\n");
        int split = normalized.indexOf("\n\n");
        String headText;
        String bodyText;
        if (split < 0) {
            headText = normalized;
            bodyText = "";
        } else {
            headText = normalized.substring(0, split);
            bodyText = normalized.substring(split + 2);
        }
        if (headText.trim().isEmpty()) return null;
        StringBuilder rebuilt = new StringBuilder();
        for (String line : headText.split("\n")) {
            if (line.trim().isEmpty()) continue;
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) continue;
            rebuilt.append(line).append("\r\n");
        }
        if (!bodyText.isEmpty()) {
            rebuilt.append("Content-Length: ")
                    .append(bodyText.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
        }
        rebuilt.append("\r\n");
        return rebuilt.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 取出界面请求包里空行之后的部分作为请求体。 */
    public static byte[] bodyBytes(String raw) {
        if (raw == null) return new byte[0];
        String normalized = raw.replace("\r\n", "\n");
        int split = normalized.indexOf("\n\n");
        if (split < 0) return new byte[0];
        return normalized.substring(split + 2).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 取出原始报文里空行之后的请求体文本；没有空行或没有体时返回空串。
     *
     * <p>与 {@link #bodyBytes(String)} 区别：这里直接给文本，且会按
     * {@code transfer-encoding: chunked} 还原，供「把抓包得到的报文转成探测参数」使用。
     */
    public static String bodyText(String raw) {
        if (raw == null) return "";
        String normalized = raw.replace("\r\n", "\n");
        int split = normalized.indexOf("\n\n");
        if (split < 0) return "";
        String head = normalized.substring(0, split);
        String body = normalized.substring(split + 2);
        if (body.isEmpty()) return "";
        if (isChunked(head)) {
            byte[] decoded = dechunk(body.getBytes(StandardCharsets.ISO_8859_1));
            if (decoded != null && decoded.length > 0) return new String(decoded, StandardCharsets.UTF_8);
        }
        return body;
    }

    /** 请求头块里是否声明了 {@code transfer-encoding: chunked}。 */
    public static boolean isChunked(String headText) {
        if (headText == null) return false;
        for (String line : headText.split("\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) return true;
        }
        return false;
    }

    /** 还原分块编码：长度行 + 数据交替，遇到 0 长度块结束。 */
    public static byte[] dechunk(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int index = 0;
        while (index < raw.length) {
            int lineEnd = index;
            while (lineEnd + 1 < raw.length && !(raw[lineEnd] == '\r' && raw[lineEnd + 1] == '\n')) lineEnd++;
            if (lineEnd + 1 >= raw.length) break;
            String sizeText = new String(raw, index, lineEnd - index, StandardCharsets.ISO_8859_1).trim();
            int semicolon = sizeText.indexOf(';');
            if (semicolon >= 0) sizeText = sizeText.substring(0, semicolon).trim();
            int size;
            try {
                size = Integer.parseInt(sizeText, 16);
            } catch (NumberFormatException e) {
                break;
            }
            index = lineEnd + 2;
            if (size == 0) break;
            int end = Math.min(index + size, raw.length);
            out.write(raw, index, end - index);
            index = end + 2;
        }
        byte[] decoded = out.toByteArray();
        return decoded.length == 0 && raw.length > 0 ? raw : decoded;
    }

    /** 粗略判断是否为二进制：出现 NUL 或可打印字符占比过低。 */
    public static boolean looksBinary(byte[] body) {
        int printable = 0;
        int limit = Math.min(body.length, 2048);
        for (int index = 0; index < limit; index++) {
            int value = body[index] & 0xff;
            if (value == 0) return true;
            if (value >= 32 || value == 9 || value == 10 || value == 13) printable++;
        }
        return limit > 0 && printable * 100 < limit * 90;
    }
}

