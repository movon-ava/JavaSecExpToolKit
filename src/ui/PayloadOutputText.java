package ui;

import java.util.List;
import java.util.Locale;

import payload.PayloadBuild;
import payload.PayloadCodec;


/**
 * 输出区与状态栏的文字渲染。
 *
 * <p>从 {@link PayloadController} 拆出来独立成类：这些方法只做「构建结果 → 给人看的文字」，
 * 既不读控件也不改状态，控制器因此只剩「什么时候渲染」这一层决策。
 */
final class PayloadOutputText {

    private PayloadOutputText() {
    }

    /** 输出区的全部内容：载荷正文在前，旁证在后，与网页版 OUTPUT / CONTEXT 的分工一致。 */


    static String of(PayloadBuild build, PayloadCodec.Encoded encoded, boolean debug,
                     String head, String headLabel, String display,
                     List<PayloadBuild.Step> steps) {
        StringBuilder text = new StringBuilder();
        text.append("载体：").append(head).append("（").append(headLabel).append("）\n");
        text.append("链：").append(display).append("\n");
        text.append("编码：").append(encoded.option.label)
                .append(encoded.urlEncoded ? " + URL" : "")
                .append("  原始 ").append(build.result.byteLength()).append(" 字节")
                .append("  输出 ").append(encoded.length()).append(" 字节\n");
        text.append("摘要：").append(build.result.digest).append("\n");
        if (build.rawSize >= 0 || !build.md5.isEmpty() || build.durationMs > 0) {
            text.append("构建：");
            if (build.durationMs > 0) text.append("耗时 ").append(build.durationMs).append("ms  ");
            if (build.rawSize >= 0) text.append("原始 ").append(build.rawSize).append(" 字节  ");
            if (!build.md5.isEmpty()) text.append("MD5 ").append(build.md5);
            text.append("\n");
        }
        if (debug) {
            text.append("\n逐步产物（调试生成）：\n");
            if (steps.isEmpty()) {
                text.append("  引擎没有报告步骤产物。\n");
            } else {
                for (payload.PayloadBuild.Step step : steps) {
                    text.append("  ").append(step.index).append(". ").append(step.node)
                            .append(" -> ").append(step.artifact);
                    if (step.size >= 0) text.append("  ").append(step.size).append(" 字节");
                    text.append("\n");
                }
            }
        }
        text.append("\n").append(encoded.option.label).append("：\n");
        text.append(PayloadCodec.display(encoded)).append("\n");
        return text.toString();
    }

    /** 切换编码 / URL 开关时的重绘：结构不变，只换载荷正文与体积。 */
    static String fromLast(PayloadCodec.Encoded encoded, String display) {
        StringBuilder text = new StringBuilder();
        text.append("当前链：").append(display).append("\n");
        text.append("编码：").append(encoded.option.label)
                .append(encoded.urlEncoded ? " + URL" : "").append("\n\n");
        text.append(encoded.option.label).append("：\n");
        text.append(PayloadCodec.display(encoded)).append("\n");
        return text.toString();
    }

    static String buildStatus(PayloadBuild build, PayloadCodec.Encoded encoded, boolean autoCopy) {
        if (encoded.failed()) {
            return "编码失败，已回退成原始字节：" + encoded.error;
        }
        StringBuilder text = new StringBuilder();
        text.append("生成成功：").append(build.result.byteLength()).append(" 字节");
        if (encoded.option != PayloadCodec.Option.RAW) {
            text.append("，").append(encoded.option.label).append(" 后 ").append(encoded.length()).append(" 字节");
        }
        text.append("。");
        if (autoCopy) text.append(" 已自动复制。");
        return text.toString();
    }

    static String sizeText(PayloadBuild build, PayloadCodec.Encoded encoded) {
        if (encoded.option == PayloadCodec.Option.RAW && !encoded.urlEncoded) {
            return bytesText(encoded.length());
        }
        return bytesText(build.result.byteLength()) + " \u2192 " + bytesText(encoded.length());
    }

    static String bytesText(int length) {
        if (length < 1024) return length + " B";
        if (length < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", length / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.2f MB", length / (1024.0 * 1024.0));
    }
}
