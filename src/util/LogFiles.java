package util;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 日志文件的命名规则与保留策略。
 *
 * <p>抽成独立一类的理由：这一层必须能被「不看文件系统也能判定」地验证。
 * 文件名怎么拼、哪个名字代表哪一天、哪些文件该被删，都是纯计算；
 * 真正的写入动作留在 {@link Log}，删除动作由 {@link #cleanExpired} 兜住。
 * 两者混在一起时，「保留天数是否算对」只能靠造文件去猜。
 *
 * <p>清理有两条硬约束，都是为了让「定期清理」不至于变成「定期误删」：
 * 只认本工具自己拼出来的名字（{@code app-yyyy-MM-dd.log}），目录里其它文件一律不碰；
 * 删除目标必须落在日志目录之内，且必须是常规文件。
 */
public final class LogFiles {

    /** 文件名前缀：清理时据此把本工具的日志与目录里的其它文件区分开。 */
    public static final String PREFIX = "app-";

    /** 文件名后缀。 */
    public static final String SUFFIX = ".log";

    /** 默认保留天数：一周的日志足够回溯一次测试过程。 */
    public static final int DEFAULT_RETENTION_DAYS = 7;

    /** 覆盖日志目录的环境变量：自检与便携场景用它把日志引到临时目录。 */
    private static final String ENV_DIR = "JSETK_LOG_DIR";

    /** 用户目录下的工具目录，与 config.properties 同级。 */
    private static final String HOME_FOLDER = ".JavaSecExpToolKit";

    /** 工具目录下的日志子目录。 */
    private static final String LOG_FOLDER = "logs";

    /** 严格 ISO 日期的长度：宽松解析会让日期与文件名互相矛盾。 */
    private static final int ISO_DATE_LENGTH = 10;

    private LogFiles() {
    }

    /**
     * 解析日志目录，优先级从高到低：环境变量、配置页填写值、用户目录默认值。
     *
     * <p>与探测引擎解析解释器的口径一致：环境变量永远赢过配置，
     * 便于在不改配置的情况下把日志引到别处（自检即用这条）。
     *
     * @param userHome         用户目录；为空时按当前目录处理
     * @param configuredFolder 配置页填写的目录；为空时用默认值
     * @return 绝对且已规范化的目录，不保证存在（由写入方按需创建）
     */
    public static Path directory(String userHome, String configuredFolder) {
        String fromEnv = Platform.env(ENV_DIR);
        if (!fromEnv.isEmpty()) return Paths.get(fromEnv).toAbsolutePath().normalize();
        String value = configuredFolder == null ? "" : configuredFolder.trim();
        if (!value.isEmpty()) return Paths.get(value).toAbsolutePath().normalize();
        String home = userHome == null || userHome.trim().isEmpty() ? "." : userHome.trim();
        return Paths.get(home, HOME_FOLDER, LOG_FOLDER).toAbsolutePath().normalize();
    }

    /** 某一天的日志文件名；日期为 null 时返回空串。 */
    public static String fileName(LocalDate day) {
        return day == null ? "" : PREFIX + day.toString() + SUFFIX;
    }

    /**
     * 从文件名反解日期；不是本工具命名的文件返回 null。
     *
     * <p>要求严格 {@code yyyy-MM-dd}：宽一点的写法（如 {@code app-2026-9-3.log}）
     * 也返回 null，否则同一天会有多种文件名，清理时容易被漏掉。
     */
    public static LocalDate dateOf(String name) {
        String value = name == null ? "" : name.trim();
        if (!value.startsWith(PREFIX) || !value.endsWith(SUFFIX)) return null;
        String middle = value.substring(PREFIX.length(), value.length() - SUFFIX.length());
        if (middle.length() != ISO_DATE_LENGTH) return null;
        LocalDate day;
        try {
            day = LocalDate.parse(middle);
        } catch (DateTimeParseException invalid) {
            return null;
        }
        // 回写一遍再比对：只有能被 LocalDate 原样打印出来的才是合法日期
        return day.toString().equals(middle) ? day : null;
    }

    /**
     * 保留策略：给出应被删除的文件名。
     *
     * <p>语义是「保留最近 N 天（含今天）」，因此今天往前数第 N 天的日志已在保留范围之外。
     * 保留天数小于 1 时按 1 处理：写成 0 或负数多半是手滑，直接理解为「只留今天」，
     * 而不是把日志目录清空（后者会连带删掉本次会话正在写的文件）。
     *
     * @param names         目录内的文件名
     * @param today         当天
     * @param retentionDays 保留天数
     * @return 需要删除的文件名，按名称升序；不含无法解析日期的名字
     */
    public static List<String> expired(List<String> names, LocalDate today, int retentionDays) {
        List<String> victims = new ArrayList<String>();
        if (names == null || today == null) return victims;
        LocalDate oldest = today.minusDays(Math.max(1, retentionDays) - 1L);
        for (String name : names) {
            LocalDate day = dateOf(name);
            if (day != null && day.isBefore(oldest)) victims.add(name);
        }
        Collections.sort(victims);
        return victims;
    }

    /**
     * 删除日志目录内的过期日志，返回实际删除的文件数。
     *
     * <p>失败一律静默：清理只是维护动作，不能因为它读不了目录、删不掉被占用的文件
     * 而影响「写日志」这条主线。删不掉的留到下一次清理再试。
     */
    public static int cleanExpired(Path logDirectory, LocalDate today, int retentionDays) {
        if (logDirectory == null || !Files.isDirectory(logDirectory)) return 0;
        Path base = logDirectory.toAbsolutePath().normalize();
        List<String> names = new ArrayList<String>();
        DirectoryStream<Path> stream = null;
        try {
            stream = Files.newDirectoryStream(logDirectory);
            for (Path entry : stream) {
                // 越界校验：只有父目录恰好是日志目录本身的常规文件才纳入候选
                if (!Files.isRegularFile(entry)) continue;
                Path child = entry.toAbsolutePath().normalize();
                if (child.getParent() == null || !child.getParent().equals(base)) continue;
                names.add(child.getFileName().toString());
            }
        } catch (IOException unreadable) {
            return 0;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException closed) {
                    // 关闭失败无需处理：候选名单已经读完了
                }
            }
        }
        int removed = 0;
        for (String name : expired(names, today, retentionDays)) {
            try {
                if (Files.deleteIfExists(base.resolve(name))) removed++;
            } catch (IOException locked) {
                // 正被别的进程占用：留给下一次清理
            }
        }
        return removed;
    }
}