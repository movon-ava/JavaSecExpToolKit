package ui;

/**
 * 载荷导出的落盘规则。
 *
 * <p>从 {@link PayloadController} 拆出来独立成类：这一块是「给定载荷与命名规则，写到哪个文件」
 * 的纯逻辑，不碰控件也不改状态。目录取配置页的「默认导出目录」，留空落到用户目录；
 * 文件名优先用输出栏填的名字，留空时按载体与时间戳自动命名，避免多次导出互相覆盖。
 * 扩展名按编码形态给出：Raw 是 .bin.txt（内容为十六进制文本），其余是 .txt。
 */
final class PayloadExporter {

    private PayloadExporter() {
    }

    /** 导出结果：成功带落盘路径，失败带原因。 */
    static final class Result {
        final boolean ok;
        final String text;

        private Result(boolean ok, String text) {
            this.ok = ok;
            this.text = text == null ? "" : text;
        }

        static Result ok(String path) {
            return new Result(true, path);
        }

        static Result fail(String message) {
            return new Result(false, message);
        }
    }

    /**
     * 导出到文件。
     *
     * <p>目录取配置页的「默认导出目录」，留空则落到用户目录；文件名优先用「保存/下载 文件名」，
     * 留空时按载体与时间戳自动命名，避免多次导出互相覆盖。扩展名按编码形态给出：
     * Raw 是 .bin（内容用十六进制文本交付），其余是 .txt。
     */
    /** 导出结果：成功给落盘路径，失败给原因。 */
    static Result write(String payload, String directory, String baseName,
                        String head, boolean rawForm) {
        String dir = directory == null || directory.trim().isEmpty()
                ? System.getProperty("user.home") : directory.trim();
        String name = baseName == null ? "" : baseName.trim();
        if (name.isEmpty()) {
            name = "payload-" + head + "-"
                    + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        }
        String extension = rawForm ? ".bin.txt" : ".txt";
        java.nio.file.Path target = java.nio.file.Paths.get(dir, name + extension);
        try {
            java.nio.file.Path parent = target.getParent();
            if (parent != null) java.nio.file.Files.createDirectories(parent);
            java.nio.file.Files.write(target, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException error) {
            return Result.fail(error.getMessage());
        }
        return Result.ok(String.valueOf(target));
    }
}
