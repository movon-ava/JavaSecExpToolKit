package payload;

import java.nio.charset.StandardCharsets;

/**
 * 构建过程写入上下文的一条数据（网页版 CONTEXT 面板里的一行）。
 *
 * <p>上下文是 java-chains 在构建链时留下的中间产物：字节码节点产出 class 字节、
 * 序列化节点产出对象、还有一批用于后续节点决策的标记（SuperClassName 之类）。
 * 排查「链没触发」时最需要看的就是它，因此这里把键、来源节点、类型与预览都保留下来。
 *
 * <p>二进制条目只保留字节与长度，不做文本化：把任意字节当字符串展示会得到乱码，
 * 使用者反而误以为载荷坏了。文本条目才给 {@link #text}。
 */
public final class PayloadContextEntry {

    /** 上下文键，例如 {@code #4 · Exec}。 */
    public final String key;
    /** 写入该条目的节点标识，来自上游的上下文元数据；取不到时为空串。 */
    public final String source;
    /** 是否为二进制条目。 */
    public final boolean binary;
    /** 优先级，来自上游元数据。 */
    public final int priority;
    /** 二进制条目的字节；文本条目为空数组。 */
    public final byte[] bytes;
    /** 文本条目的内容；二进制条目为空串。 */
    public final String text;

    PayloadContextEntry(String key, String source, boolean binary, int priority, byte[] bytes, String text) {
        this.key = key == null ? "" : key;
        this.source = source == null ? "" : source;
        this.binary = binary;
        this.priority = priority;
        this.bytes = bytes == null ? new byte[0] : bytes;
        this.text = text == null ? "" : text;
    }

    /** 条目大小：二进制取字节数，文本取 UTF-8 字节数。 */
    public int sizeBytes() {
        return binary ? bytes.length : text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 一行预览：二进制给出长度，文本压平空白并截断。 */
    public String preview(int limit) {
        if (binary) return "byte[" + bytes.length + "]";
        String flat = text.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
        if (limit > 0 && flat.length() > limit) return flat.substring(0, limit) + "\u2026";
        return flat;
    }

    /** 可直接复制 / 保存的文本形态：二进制条目给出十六进制。 */
    public String copyable() {
        if (!binary) return text;
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }
}
