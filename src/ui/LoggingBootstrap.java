package ui;

import java.util.Properties;

import config.AppConfig;
import util.Log;
import util.LogFiles;
import util.Platform;

/**
 * 启动期的日志配置：把配置页里的日志项翻译成一次 {@link Log#configure} 调用。
 *
 * <p>为什么不放在 {@code util}：日志内核必须是叶子层（不依赖任何项目包），
 * 而「配置键叫什么、默认值是多少」属于配置语义，只能由界面层或组合根来承担。
 * 这样内核保持可独立复用，配置改动也不必碰内核。
 *
 * <p>启动顺序上的硬约束：这一步必须早于启动画面与预热。预热要读 java-chains 的元数据、
 * 解析内置预设，是最容易出问题的一段，日志必须在那之前就绪，否则最需要日志的时刻反而没有日志。
 */
public final class LoggingBootstrap {

    /** 是否启用日志。默认开启：排错能力不该需要使用者先手动打开。 */
    public static final String KEY_ENABLED = "log_enabled";
    /** 日志级别：off / error / warn / info / debug。 */
    public static final String KEY_LEVEL = "log_level";
    /** 日志目录；留空写到用户目录下的默认位置。 */
    public static final String KEY_DIR = "log_dir";
    /** 保留天数（含今天）。 */
    public static final String KEY_KEEP_DAYS = "log_keep_days";
    /** 是否同时打印到控制台。 */
    public static final String KEY_CONSOLE = "log_console";

    /** 默认级别。 */
    public static final String DEFAULT_LEVEL = "info";
    /** 默认保留天数。 */
    public static final String DEFAULT_KEEP_DAYS = String.valueOf(LogFiles.DEFAULT_RETENTION_DAYS);

    private LoggingBootstrap() {
    }

    /**
     * 按配置开启日志。
     *
     * <p>只读取、不写入配置：本方法可能在配置页尚未保存过时执行，此时全部取默认值，
     * 并立刻具备写日志的能力。真正落盘由配置页的保存动作负责。
     */
    public static void configure(Properties config) {
        Properties values = config == null ? new Properties() : config;
        boolean enabled = flag(values.getProperty(KEY_ENABLED, "true"), true);
        boolean console = flag(values.getProperty(KEY_CONSOLE, "false"), false);
        Log.configure(LogFiles.directory(System.getProperty("user.home"),
                        values.getProperty(KEY_DIR, "")),
                values.getProperty(KEY_LEVEL, DEFAULT_LEVEL),
                keepDays(values.getProperty(KEY_KEEP_DAYS, DEFAULT_KEEP_DAYS)),
                console,
                enabled);
    }

    /**
     * 记录一次启动摘要。
     *
     * <p>内容取舍以「事后能否据此刻画现场」为准：Java 版本决定字节码 gadget 的兼容性，
     * 配置与日志路径决定「使用者看到的现象能不能对上同一份配置」。
     */
    public static void logStartup(Properties config) {
        if (!Log.isActive()) return;
        Log.info("启动 JavaSecExpToolKit：Java " + System.getProperty("java.version", "?")
                + "，操作系统 " + System.getProperty("os.name", "?")
                + "，工作目录 " + System.getProperty("user.dir", "?"));
        Log.info("配置文件 " + AppConfig.FILE + "，日志目录 " + Log.directory()
                + "，级别 " + Log.levelId() + "，保留 " + Log.retentionDays() + " 天");
        if (config != null && !config.isEmpty()) {
            Log.info("已载入配置项 " + config.size() + " 项");
        }
    }

    /** 一行可读的日志状态，供配置页回显。 */
    public static String summary() {
        if (!Log.isActive()) return "日志已关闭。";
        StringBuilder text = new StringBuilder();
        text.append("日志目录 ").append(Log.directory());
        text.append("，级别 ").append(Log.levelId());
        text.append("，保留 ").append(Log.retentionDays()).append(" 天");
        if (Log.lastCleanupRemoved() > 0) {
            text.append("，启动时清理了 ").append(Log.lastCleanupRemoved()).append(" 个过期文件");
        }
        return text.toString();
    }

    /** 保留天数解析：非数字、越界一律回落到默认值（配置页已有提示）。 */
    private static int keepDays(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return LogFiles.DEFAULT_RETENTION_DAYS;
        try {
            int value = Integer.parseInt(text);
            // 上限一年：再长就失去「定期清理」的意义，且多半是填错了位数
            return value >= 1 && value <= 365 ? value : LogFiles.DEFAULT_RETENTION_DAYS;
        } catch (NumberFormatException invalid) {
            return LogFiles.DEFAULT_RETENTION_DAYS;
        }
    }

    /** 勾选框型配置：空值取回落到默认值，其余按 true / 1 / yes 判定。 */
    private static boolean flag(String raw, boolean fallback) {
        String text = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (text.isEmpty()) return fallback;
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
    }

    /** 供组合根判断是否需要提示「日志目录不可写」；当前仅用于自检读取路径。 */
    public static String resolveDirectory(Properties config) {
        Properties values = config == null ? new Properties() : config;
        return LogFiles.directory(Platform.valueOr(System.getProperty("user.home"), "."),
                values.getProperty(KEY_DIR, "")).toString();
    }
}