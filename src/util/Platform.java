package util;

/**
 * 与操作系统相关的细节集中在这里：命令行转义与默认值回落。
 */
public final class Platform {

    private Platform() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Windows 下把参数交给子进程前按 MSVCRT 规则转义。
     *
     * <p>Java 只会给含空格或引号的参数补外层引号，不会转义参数内部的引号，
     * 于是 JSON 业务参数（例如 age 字段）的引号会被 C 运行时当作分隔符吃掉，
     * Python 侧收到的是非法 JSON。这里自行完成引号与反斜杠转义后再交给
     * ProcessBuilder，避免依赖 JDK 版本的具体拼接行为。
     */
    public static String commandArg(String value) {
        if (!isWindows()) return value;
        StringBuilder out = new StringBuilder("\"");
        int backslashes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\') { backslashes++; continue; }
            if (current == '"') {
                for (int n = 0; n < backslashes * 2 + 1; n++) out.append('\\');
                out.append('"');
            } else {
                for (int n = 0; n < backslashes; n++) out.append('\\');
                out.append(current);
            }
            backslashes = 0;
        }
        for (int n = 0; n < backslashes * 2; n++) out.append('\\');
        return out.append('"').toString();
    }

    /** 空串 / null 时回落到默认值。 */
    public static String valueOr(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    /** 环境变量取值：缺失或空白都返回空串。 */
    public static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }
}

