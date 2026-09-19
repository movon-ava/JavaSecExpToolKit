package ui;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import proxy.ProxyServer;
import util.HttpText;

/**
 * 代理报文的文本渲染：把一次流量渲染成「请求行 + 头 + 空行 + 体」这种可直接编辑的形态。
 *
 * <p>只做纯字符串处理，不触碰界面控件，便于单独验证（见 {@code tests/ProxyServerCheck}）。
 */
public final class FlowRenderer {

    private FlowRenderer() {
    }

    /** 完整请求包：优先还原原始字节，缺失时按已解析的头与体重建。 */
    public static String rawRequest(ProxyServer.HttpFlow flow) {
        byte[] raw = flow.requestRaw;
        if (raw == null || raw.length == 0) return request(flow, null, flow.requestBody);
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int split = text.indexOf("\r\n\r\n");
        String headText = split < 0 ? text : text.substring(0, split);
        StringBuilder rebuilt = new StringBuilder();
        for (String line : headText.split("\r\n")) {
            // 逐跳头不应转发给目标，展示时统一去掉
            if (line.toLowerCase(Locale.ROOT).startsWith("proxy-connection:")) continue;
            rebuilt.append(line).append("\r\n");
        }
        rebuilt.append("\r\n");
        byte[] body = split < 0 ? new byte[0]
                : text.substring(split + 4).getBytes(StandardCharsets.ISO_8859_1);
        rebuilt.append(HttpText.body(body, flow.requestHeaders));
        return rebuilt.toString();
    }

    /** 请求包渲染：请求行 + 请求头 + 空行 + 请求体，可直接编辑后放行。 */
    public static String request(ProxyServer.HttpFlow flow, byte[] head, byte[] body) {
        StringBuilder text = new StringBuilder();
        text.append(flow.method).append(' ').append(flow.target).append(' ').append(flow.version)
                .append("\r\n");
        for (Map.Entry<String, String> header : flow.requestHeaders.entrySet()) {
            text.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        text.append("\r\n");
        text.append(HttpText.body(body, flow.requestHeaders));
        return text.toString();
    }

    /** 返回包渲染：状态行 + 响应头（含 Set-Cookie）+ 响应体；HTTPS 隧道单独说明。 */
    public static String response(ProxyServer.HttpFlow flow) {
        StringBuilder text = new StringBuilder();
        if (!flow.captured) {
            text.append("[HTTPS 隧道] ").append(flow.host).append(':').append(flow.port).append("\r\n\r\n")
                    .append("该连接是 CONNECT 隧道，未解密，仅记录目标与已传输 ")
                    .append(flow.tunneledBytes).append(" 字节。\r\n");
            return text.toString();
        }
        text.append("HTTP ").append(flow.status).append(' ').append(flow.reason)
                .append("  (").append(flow.elapsedMs).append(" ms)").append("\r\n");
        for (Map.Entry<String, String> header : flow.responseHeaders.entrySet()) {
            text.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        text.append("\r\n");
        text.append(HttpText.body(flow.responseBody, flow.responseHeaders));
        if (flow.error != null) text.append("\r\n\r\n错误: ").append(flow.error);
        return text.toString();
    }
}

