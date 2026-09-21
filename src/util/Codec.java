package util;

import java.util.Base64;

/**
 * Base64 编解码：项目共享内核之一，供各功能模块复用。
 *
 * <p>本类只承载与具体漏洞类型无关的纯编解码能力，因此不依赖任何项目内的其它包，
 * 也不感知调用方是谁。之所以集中在这里，是因为它同时被 Shiro 模块与通用链引擎使用；
 * 若各自实现一份，日后调整容错策略就需要同步修改多处。
 *
 * <p>解码刻意采用「标准解码失败后退回 MIME 解码」的策略：手工复制的密钥常带换行或空格，
 * 标准解码器会拒绝，而 MIME 解码器可以接受。
 */
public final class Codec {

    private Codec() {
    }

    /**
     * Base64 编码。
     *
     * @param data 原始字节，允许为 null
     * @return 编码结果；入参为 null 时返回空字符串而不是 null，便于直接拼接展示
     */
    public static String base64(byte[] data) {
        return data == null ? "" : Base64.getEncoder().encodeToString(data);
    }

    /**
     * Base64 解码，容忍换行与空格。
     *
     * @param text 待解码文本，允许为 null
     * @return 解码后的字节；无法解码时返回 null，由调用方决定如何提示
     */
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
}