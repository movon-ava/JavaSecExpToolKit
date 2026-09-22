package payload;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.vulhub.javachains.web.service.payload.EncodedPayload;
import org.vulhub.javachains.web.service.payload.PayloadEncodingService;

/**
 * 载荷编码：把构建出来的原始字节按使用者的选择转成可直接投放的形态。
 *
 * <p>编码规则整体委托给 java-chains 自己的 {@code PayloadEncodingService}，不另写一套：
 * 上游的 Gzip 是「gzip 后 Base64」、Hex 是小写十六进制，这些细节自己实现必然与网页版
 * 产生差异，使用者会发现「同一个开关在网页版与本工具出的是两种载荷」。
 *
 * <p>上游编码服务是无状态工具，可安全重复构造；本类把它收在一处，
 * 使界面层不需要引用任何 {@code org.vulhub} 类型。
 */
public final class PayloadCodec {

    /** 编码选项：与网页版 ENCODE 一行里的按钮一一对应。 */
    public enum Option {
        /** 原始字节，不做任何编码。 */
        RAW("raw", "Raw"),
        /** 标准 Base64。 */
        BASE64("base64", "Base64"),
        /** 小写十六进制。 */
        HEX("hex", "Hex"),
        /** gzip 后再 Base64（上游对「体积大的序列化载荷」推荐这种）。 */
        GZIP("gzip_base64", "Gzip");

        /** 传给上游编码服务的取值。 */
        public final String id;
        /** 界面上显示的短名。 */
        public final String label;

        Option(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** 按标识查找；找不到返回 {@link #BASE64}，与网页版默认编码一致。 */
        public static Option of(String value) {
            if (value != null) {
                String wanted = value.trim();
                for (Option option : values()) {
                    if (option.id.equalsIgnoreCase(wanted) || option.name().equalsIgnoreCase(wanted)
                            || option.label.equalsIgnoreCase(wanted)) {
                        return option;
                    }
                }
            }
            return BASE64;
        }

        public static List<String> ids() {
            List<String> ids = new ArrayList<String>();
            for (Option option : values()) ids.add(option.id);
            return ids;
        }
    }

    /** 显示文本里可安全展示的字符数：超过这个长度就该靠文件与复制交付。 */
    private static final int PREVIEW_LIMIT = 200000;

    private PayloadCodec() {
    }

    /**
     * 按选项编码。
     *
     * @param bytes     原始载荷字节
     * @param option    编码选项
     * @param urlEncode 是否再套一层 URL 编码（网页版 ENCODE 行右侧的 URL 开关）
     * @return 编码结果；输入为空时返回空字节数组而不是 null
     */
    public static Encoded encode(byte[] bytes, Option option, boolean urlEncode) {
        byte[] safe = bytes == null ? new byte[0] : bytes;
        Option chosen = option == null ? Option.BASE64 : option;
        try {
            EncodedPayload encoded = new PayloadEncodingService()
                    .encode(safe, chosen.id, urlEncode);
            byte[] out = encoded == null ? null : encoded.getBytes();
            return new Encoded(out == null ? new byte[0] : out, chosen, urlEncode);
        } catch (Throwable error) {
            // 上游编码失败（例如畸形字节导致的 gzip 异常）不能把界面打崩：
            // 回退成原始字节并记录原因，由调用方在状态栏说明。
            return new Encoded(safe, chosen, urlEncode, describe(error));
        }
    }

    /**
     * 编码结果的展示文本。
     *
     * <p>二进制与文本两种形态分开处理：编码后仍是任意字节时（Raw、Gzip 之外的原始形态）
     * 按 ISO-8859-1 逐字节映射，保证「文本 ↔ 字节」可逆，不做有损的 UTF-8 替换。
     * 超长内容截断并给出提示，避免把几十兆文本塞进文本框拖垮界面。
     */
    public static String display(Encoded encoded) {
        if (encoded == null) return "";
        if (encoded.isText()) {
            String text = new String(encoded.bytes, StandardCharsets.UTF_8);
            return truncate(text);
        }
        StringBuilder hex = new StringBuilder(Math.min(encoded.bytes.length, PREVIEW_LIMIT) * 2 + 64);
        int limit = Math.min(encoded.bytes.length, PREVIEW_LIMIT / 2);
        for (int index = 0; index < limit; index++) {
            byte value = encoded.bytes[index];
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        if (limit < encoded.bytes.length) {
            hex.append("\n\u2026\uff08\u4ec5\u5c55\u793a\u524d ").append(limit)
                    .append(" \u5b57\u8282\uff0c\u5b8c\u6574\u5185\u5bb9\u8bf7\u7528\u4e0b\u8f7d\uff09");
        }
        return hex.toString();
    }

    /** 可落盘 / 复制的文本：文本形态原样给出，二进制形态给十六进制。 */
    public static String portable(Encoded encoded) {
        if (encoded == null) return "";
        if (encoded.isText()) return new String(encoded.bytes, StandardCharsets.UTF_8);
        StringBuilder hex = new StringBuilder(encoded.bytes.length * 2);
        for (byte value : encoded.bytes) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    private static String truncate(String text) {
        if (text.length() <= PREVIEW_LIMIT) return text;
        return text.substring(0, PREVIEW_LIMIT) + "\n\u2026\uff08\u5df2\u622a\u65ad\uff0c\u5b8c\u6574\u5185\u5bb9\u8bf7\u7528\u4e0b\u8f7d\uff09";
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** 编码结果：字节 + 用的是哪种编码 + 是否失败。 */
    public static final class Encoded {
        public final byte[] bytes;
        public final Option option;
        public final boolean urlEncoded;
        /** 编码失败的原因；成功时为空串。 */
        public final String error;

        Encoded(byte[] bytes, Option option, boolean urlEncoded) {
            this(bytes, option, urlEncoded, "");
        }

        Encoded(byte[] bytes, Option option, boolean urlEncoded, String error) {
            this.bytes = bytes == null ? new byte[0] : bytes;
            this.option = option;
            this.urlEncoded = urlEncoded;
            this.error = error == null ? "" : error;
        }

        public boolean failed() {
            return !error.isEmpty();
        }

        public int length() {
            return bytes.length;
        }

        /** 是否是可直接阅读的文本：Base64、Hex、Gzip 与显式 URL 编码的产物都是 ASCII 文本。 */
        public boolean isText() {
            if (urlEncoded) return true;
            if (option == Option.RAW) return false;
            return true;
        }
    }
}
