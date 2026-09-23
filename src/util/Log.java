package util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 文件日志：把运行期的异常与关键动作落到按日期分开的文件里，便于事后排查。
 *
 * <p>本类只做一件事——把一行文字与可选的异常栈追加到当天文件。之所以独立于界面与各功能模块，
 * 是因为它必须在**任何**模块里可用：探测、代理、Shiro、载荷、漏洞分析都要能记录，
 * 而这些模块之间的依赖方向已被约束为单向，日志只能沉在共享内核里。
 *
 * <p>三条设计红线：
 * <ul>
 *   <li><b>绝不抛出</b>：写日志属于旁路动作，磁盘满、目录无权限、文件被占用都不该让正常流程失败。
 *       本类所有对外方法都兜住全部异常，最坏情况是这一条日志丢掉。</li>
 *   <li><b>按天分文件</b>：文件名取自当天日期，跨天时自动关闭旧文件、打开新文件；
 *       长时间开着程序不会把日志全部堆进同一个文件。</li>
 *   <li><b>默认安静</b>：未显式启用时全部方法立即返回。库与自检场景下不该凭空产生文件。</li>
 * </ul>
 *
 * <p>日志目录的解析规则见 {@link LogFiles#directory}，保留与清理规则见
 * {@link LogFiles#cleanExpired}。
 */
public final class Log {

    /** 日志级别，数值越大越啰嗦；低于阈值的记录被丢弃。 */
    public enum Level {
        OFF(0, "off", "关闭"),
        ERROR(1, "error", "仅错误"),
        WARN(2, "warn", "警告与错误"),
        INFO(3, "info", "常规"),
        DEBUG(4, "debug", "调试");

        /** 比较用的序号。 */
        public final int weight;
        /** 配置里保存的标识。 */
        public final String id;
        /** 配置页显示的中文名。 */
        public final String label;

        Level(int weight, String id, String label) {
            this.weight = weight;
            this.id = id;
            this.label = label;
        }

        /** 按标识或显示名解析级别；无法识别时回落到 {@link #INFO}。 */
        public static Level of(String raw) {
            String wanted = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (wanted.isEmpty()) return INFO;
            for (Level level : values()) {
                if (level.id.equals(wanted) || level.label.equals(raw == null ? "" : raw.trim())) {
                    return level;
                }
            }
            return INFO;
        }

        /** 当前级别是否允许记录该级别。 */
        public boolean allows(Level other) {
            return other != null && other.weight <= weight;
        }
    }

    /** 每行时间戳的格式：到毫秒，排查时序问题时够用且不至于过长。 */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    /** 单条记录最多保留的字符数：避免把整段报文写进日志。 */
    private static final int MAX_MESSAGE_CHARS = 4000;

    private static final Object LOCK = new Object();

    /** 已解析的日志目录；null 表示尚未配置。 */
    private static Path directory;
    private static Level threshold = Level.INFO;
    private static int retentionDays = LogFiles.DEFAULT_RETENTION_DAYS;
    private static boolean consoleEnabled;
    private static boolean enabled;

    /** 当前打开的当天文件与写入器；跨天时整体替换。 */
    private static Path openFile;
    private static BufferedWriter writer;

    /** 最近一次清理删除的文件数，供自检与界面回显。 */
    private static int lastCleanupRemoved;

    /** 日期来源：默认取系统时间，自检可替换成固定日期以验证跨天切换。 */
    private static Supplier<LocalDate> clock = new Supplier<LocalDate>() {
        @Override
        public LocalDate get() {
            return LocalDate.now();
        }
    };

    private Log() {
    }

    /**
     * 配置日志：启动时调用一次即可，之后各模块直接用静态方法记录。
     *
     * <p>重复调用会重新解析目录并按新设置重开文件，因此「保存配置」后可以直接再调一次。
     *
     * @param logDirectory  日志目录；为 null 时按默认规则解析
     * @param levelName     级别标识或中文名；无法识别按 INFO
     * @param keepDays      保留天数；小于 1 按 1 处理
     * @param echoConsole   是否同时打印到标准错误（控制台直接跑时看得到）
     * @param switchOn      是否启用；false 时所有记录动作立即返回
     */
    public static void configure(Path logDirectory, String levelName, int keepDays,
                                 boolean echoConsole, boolean switchOn) {
        synchronized (LOCK) {
            try {
                directory = logDirectory;
                threshold = Level.of(levelName);
                retentionDays = Math.max(1, keepDays);
                consoleEnabled = echoConsole;
                enabled = switchOn;
                closeWriter();
                openFile = null;
                if (enabled && threshold != Level.OFF) {
                    Files.createDirectories(directory);
                    // 启动即清理一次：把「上次运行留下的过期日志」在开始写新日志之前收掉
                    lastCleanupRemoved = LogFiles.cleanExpired(directory, today(), retentionDays);
                }
            } catch (Throwable ignored) {
                // 配置失败不能让程序起不来：后续记录动作会自行判断是否可用
            }
        }
    }

    /** 是否已启用且级别不是 OFF。 */
    public static boolean isActive() {
        synchronized (LOCK) {
            return enabled && threshold != Level.OFF && directory != null;
        }
    }

    /** 当前正在写入的文件；未开启或尚未写入任何内容时为 null。 */
    public static Path currentFile() {
        synchronized (LOCK) {
            return openFile;
        }
    }

    /** 日志目录；未配置时为 null。 */
    public static Path directory() {
        synchronized (LOCK) {
            return directory;
        }
    }

    /** 保留天数。 */
    public static int retentionDays() {
        synchronized (LOCK) {
            return retentionDays;
        }
    }

    /** 最近一次清理删除的文件数。 */
    public static int lastCleanupRemoved() {
        synchronized (LOCK) {
            return lastCleanupRemoved;
        }
    }

    /** 当前级别标识。 */
    public static String levelId() {
        synchronized (LOCK) {
            return threshold.id;
        }
    }

    /**
     * 替换日期来源，仅供自检使用。
     *
     * <p>跨天切换是本功能最容易出错的一处（写错就是「第二天的日志进了第一天的文件」），
     * 而真实等待一天无法在自检里完成，因此把日期做成可替换的来源。
     * 传 null 恢复系统时间。
     */
    public static void setDateSupplier(Supplier<LocalDate> supplier) {
        synchronized (LOCK) {
            clock = supplier == null ? new Supplier<LocalDate>() {
                @Override
                public LocalDate get() {
                    return LocalDate.now();
                }
            } : supplier;
        }
    }

    /** 立即关闭当前文件；自检在删除目录前调用，避免句柄占住文件。 */
    public static void close() {
        synchronized (LOCK) {
            try {
                closeWriter();
            } catch (Throwable ignored) {
                // 关闭失败无可挽回，且不该向外传播
            }
        }
    }

    // ------------------------------------------------------------------
    // 记录入口
    // ------------------------------------------------------------------

    public static void debug(String message) {
        write(Level.DEBUG, message, null);
    }

    public static void info(String message) {
        write(Level.INFO, message, null);
    }

    public static void warn(String message) {
        write(Level.WARN, message, null);
    }

    public static void warn(String message, Throwable error) {
        write(Level.WARN, message, error);
    }

    public static void error(String message) {
        write(Level.ERROR, message, null);
    }

    public static void error(String message, Throwable error) {
        write(Level.ERROR, message, error);
    }

    /**
     * 记录一条日志。
     *
     * <p>本方法是唯一的写入路径：级别判断、跨天切换、异常栈展开、落盘与回显都在这里，
     * 各个公开的记录方法只是它的薄封装，避免出现「某个级别忘了切文件」这类不一致。
     */
    public static void write(Level level, String message, Throwable error) {
        synchronized (LOCK) {
            if (!isActive() || !threshold.allows(level)) return;
            try {
                ensureOpen();
                if (writer == null) return;
                String text = message == null || message.trim().isEmpty() ? "(无描述)" : message.trim();
                String line = stamp() + " [" + level.id.toUpperCase(Locale.ROOT) + "] ["
                        + Thread.currentThread().getName() + "] " + clip(text);
                writer.write(line);
                writer.newLine();
                if (error != null) {
                    writer.write(stackOf(error));
                }
                writer.flush();
                if (consoleEnabled) {
                    try {
                        System.err.println(line);
                    } catch (Throwable ignored) {
                        // 控制台不可用时同样不向外传播
                    }
                }
            } catch (Throwable ignored) {
                // 写日志失败不影响调用方：丢弃这一条即可
            }
        }
    }

    /**
     * 记录未被捕获的异常。
     *
     * <p>把处理器装到 {@link Thread#setDefaultUncaughtExceptionHandler}：界面线程之外的
     * 后台线程（代理、隧道、引擎读取）抛出的异常默认只会打印一行栈到标准错误，
     * 使用者看不到、事后也查不到，正是本功能要解决的场景。
     */
    public static void installUncaughtHandler() {
        try {
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable error) {
                    write(Level.ERROR, "线程 " + (thread == null ? "?" : thread.getName())
                            + " 抛出未捕获异常", error);
                }
            });
        } catch (Throwable ignored) {
            // 无法安装时保持默认行为，不阻塞启动
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private static LocalDate today() {
        LocalDate day = null;
        try {
            day = clock.get();
        } catch (Throwable ignored) {
            day = null;
        }
        return day == null ? LocalDate.now() : day;
    }

    /** 按当天日期打开文件；已打开同一天的文件时直接复用，跨天时替换并顺带清理。 */
    private static void ensureOpen() throws IOException {
        LocalDate day = today();
        Path wanted = directory.resolve(LogFiles.fileName(day));
        if (writer != null && wanted.equals(openFile)) return;
        closeWriter();
        boolean rolled = openFile != null;
        openFile = wanted;
        Files.createDirectories(directory);
        writer = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(wanted, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.APPEND),
                StandardCharsets.UTF_8));
        // 跨天时清理一次：清理频率跟着「日志换文件」走，既不会天天重复扫目录，
        // 也不会因为程序一直开着而永远不清理
        if (rolled) lastCleanupRemoved = LogFiles.cleanExpired(directory, day, retentionDays);
    }

    private static void closeWriter() throws IOException {
        if (writer == null) return;
        BufferedWriter target = writer;
        writer = null;
        target.close();
    }

    private static String stamp() {
        return LocalDateTime.now().format(STAMP);
    }

    private static String clip(String text) {
        return text.length() <= MAX_MESSAGE_CHARS ? text : text.substring(0, MAX_MESSAGE_CHARS) + "…";
    }

    /** 异常栈展开成文本；末尾统一以换行结束，保证与下一条记录不会贴在一起。 */
    private static String stackOf(Throwable error) {
        StringWriter buffer = new StringWriter();
        PrintWriter printer = new PrintWriter(buffer);
        try {
            error.printStackTrace(printer);
        } catch (Throwable ignored) {
            buffer.write(String.valueOf(error));
        } finally {
            printer.flush();
        }
        String text = buffer.toString();
        return text.endsWith(System.lineSeparator()) || text.endsWith("\n") ? text : text + System.lineSeparator();
    }
}