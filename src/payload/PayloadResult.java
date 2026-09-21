package payload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import util.Codec;

/**
 * 载荷构建结果：同时给出 Base64 文本与原始字节两种形态。
 *
 * <p>两种形态都要保留，是因为下游用途不同：粘贴到请求体里需要文本，
 * 而写入二进制协议载荷需要原始字节。只留一种会导致调用方自己再做一次转换，
 * 转换逻辑一旦分散就难以保证两处一致。
 *
 * <p>本类不持有任何引擎引用，也不感知调用方是谁：它只是数据。
 */
public final class PayloadResult {

    /** 是否成功。失败结果不携带载荷，调用方必须先判断本字段。 */
    public final boolean success;
    /** 成功时为空串；失败时是可直接展示给使用者的原因。 */
    public final String message;
    /** Base64 文本形态；失败时为空串而不是 null。 */
    public final String base64;
    /** 原始字节形态；失败时为空数组而不是 null。 */
    public final byte[] bytes;
    /** 可记录的摘要：由载体标识、节点序列与长度拼成，不含载荷正文。 */
    public final String digest;

    private PayloadResult(boolean success, String message, String base64, byte[] bytes, String digest) {
        this.success = success;
        this.message = message == null ? "" : message;
        this.base64 = base64 == null ? "" : base64;
        this.bytes = bytes == null ? new byte[0] : bytes;
        this.digest = digest == null ? "" : digest;
    }

    /** 载荷字节长度。 */
    public int byteLength() {
        return bytes.length;
    }

    /** 成功结果。载荷正文只出现在 base64 与 bytes 里，摘要不含正文。 */
    public static PayloadResult ok(String payloadId, List<String> gadgets, byte[] data) {
        byte[] safe = data == null ? new byte[0] : data;
        return new PayloadResult(true, "", Codec.base64(safe), safe, buildDigest(payloadId, gadgets, safe.length));
    }

    /** 成功结果：载体直接产出文本（部分载体 marshal 后本就是字符串）。 */
    public static PayloadResult okText(String payloadId, List<String> gadgets, String text) {
        String safe = text == null ? "" : text;
        byte[] decoded = Codec.decodeBase64(safe);
        byte[] bytes = decoded == null ? safe.getBytes(java.nio.charset.StandardCharsets.UTF_8) : decoded;
        return new PayloadResult(true, "", safe, bytes, buildDigest(payloadId, gadgets, bytes.length));
    }

    /**
     * 失败结果。
     *
     * <p>失败时两个载荷字段都必须为空：否则调用方可能把上一次的结果误当成本次产出。
     */
    public static PayloadResult fail(String message) {
        return new PayloadResult(false, message, "", new byte[0], "");
    }

    /** 由载体标识 + 节点序列 + 长度拼成摘要，便于记录日志而不泄露载荷本身。 */
    private static String buildDigest(String payloadId, List<String> gadgets, int length) {
        List<String> chain = new ArrayList<String>();
        chain.add(payloadId == null ? "" : payloadId);
        if (gadgets != null) {
            for (String gadget : gadgets) chain.add(gadget == null ? "" : gadget);
        }
        return "载体=" + chain.get(0)
                + " 链=" + join(chain.subList(1, chain.size()))
                + " 字节=" + length;
    }

    private static String join(List<String> items) {
        if (items.isEmpty()) return "(空)";
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) text.append(" -> ");
            text.append(items.get(index));
        }
        return text.toString();
    }

    /** 只读的节点序列，供界面回显。 */
    public static List<String> emptyChain() {
        return Collections.emptyList();
    }
}
