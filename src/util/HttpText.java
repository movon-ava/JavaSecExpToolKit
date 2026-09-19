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

