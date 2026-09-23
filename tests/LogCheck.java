import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import util.Log;
import util.LogFiles;

/**
 * 日志内核自检：命名与保留策略、级别过滤、按天分文件、失败静默、未捕获异常。
 *
 * <p>为什么逐条断言而不是「跑一遍看有没有文件」：本功能的两个高风险点是
 * **保留天数算错日期**与**跨天写进了旧文件**，两者的表现都是「文件确实存在」，
 * 只有对着具体日期与文件内容断言才能发现。跨天无法靠等待验证，因此日期来源可注入。
 *
 * <p>用法：java -cp target\tmp2;target\classes LogCheck
 */
public final class LogCheck {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        // 本自检不构建载荷、不会执行任何 gadget 命令，但仍装守卫：
        // 万一将来加入会弹计算器的用例，守卫已在位
        TestProcessGuard.install("LogCheck");

        System.out.println("== 文件名与保留策略 ==");
        namingChecks();

        System.out.println();
        System.out.println("== 目录解析与级别 ==");
        levelChecks();

        System.out.println();
        System.out.println("== 按日期分文件 ==");
        rolloverChecks();

        System.out.println();
        System.out.println("== 清理与安全边界 ==");
        cleanupChecks();

        System.out.println();
        System.out.println("== 失败静默与未捕获异常 ==");
        failureChecks();

        System.out.println();
        System.out.println("断言总数 " + (passed + failed) + "，失败 " + failed);
        if (failed > 0) System.exit(1);
        System.out.println("日志内核自检通过");
        System.exit(0);
    }

    /** 命名规则：严格 ISO 日期，非本工具命名一律不认。 */
    private static void namingChecks() {
        check("按日期拼出的文件名带日期",
                "app-2026-09-23.log".equals(LogFiles.fileName(LocalDate.of(2026, 9, 23))));
        check("文件名前缀为 app-",
                LogFiles.fileName(LocalDate.of(2026, 1, 1)).startsWith("app-"));
        check("日期为 null 时返回空串", LogFiles.fileName(null).isEmpty());

        check("可从文件名反解日期",
                LocalDate.of(2026, 9, 23).equals(LogFiles.dateOf("app-2026-09-23.log")));
        check("前缀不符的文件不被认作日志",
                LogFiles.dateOf("other-2026-09-23.log") == null);
        check("后缀不符的文件不被认作日志",
                LogFiles.dateOf("app-2026-09-23.txt") == null);
        check("非 ISO 写法（月份不补零）不被认作日志",
                LogFiles.dateOf("app-2026-9-23.log") == null);
        check("日期越界的文件名不被认作日志",
                LogFiles.dateOf("app-2026-13-01.log") == null);
        check("日期越界的文件名不被认作日志（二月三十）",
                LogFiles.dateOf("app-2026-02-30.log") == null);
        check("空名字不被认作日志", LogFiles.dateOf("") == null);
        check("null 名字不被认作日志", LogFiles.dateOf(null) == null);

        LocalDate today = LocalDate.of(2026, 9, 23);
        List<String> names = Arrays.asList(
                "app-2026-09-17.log", "app-2026-09-16.log",
                "app-2026-09-23.log", "notes.txt", "app-2026-09-18.log");

        List<String> expired = LogFiles.expired(names, today, 7);
        check("保留 7 天：超出范围的被选中", expired.contains("app-2026-09-16.log"));
        check("保留 7 天：范围内的不被选中", !expired.contains("app-2026-09-17.log"));
        check("保留 7 天：当天不被选中", !expired.contains("app-2026-09-23.log"));
        check("非日志名字不进入删除名单", !expired.contains("notes.txt"));
        check("删除名单按名称升序",
                expired.equals(Arrays.asList("app-2026-09-16.log")));

        check("保留 1 天：只留当天",
                LogFiles.expired(names, today, 1)
                        .equals(Arrays.asList("app-2026-09-16.log", "app-2026-09-17.log",
                                "app-2026-09-18.log")));
        check("保留天数 0 按 1 处理（不清空）",
                LogFiles.expired(names, today, 0).contains("app-2026-09-18.log"));
        check("保留天数负数同样按 1 处理",
                LogFiles.expired(names, today, -5).contains("app-2026-09-18.log"));
        check("保留天数 0 时当天文件必须保留",
                !LogFiles.expired(names, today, 0).contains("app-2026-09-23.log"));
        check("保留天数足够大时没有文件过期",
                LogFiles.expired(names, today, 365).isEmpty());

        check("跨月边界：9 月 1 日保留 7 天只留 8 月 26 日起",
                LogFiles.expired(Arrays.asList("app-2026-08-25.log", "app-2026-08-26.log"),
                        LocalDate.of(2026, 9, 1), 7).equals(Arrays.asList("app-2026-08-25.log")));
        check("跨年边界：1 月 2 日保留 7 天会删到上一年",
                LogFiles.expired(Arrays.asList("app-2025-12-26.log", "app-2025-12-27.log"),
                        LocalDate.of(2026, 1, 2), 7).equals(Arrays.asList("app-2025-12-26.log")));

        check("空名单返回空结果", LogFiles.expired(new ArrayList<String>(), today, 7).isEmpty());
        check("名单为 null 不抛异常", LogFiles.expired(null, today, 7).isEmpty());
        check("当天为 null 不抛异常", LogFiles.expired(names, null, 7).isEmpty());
    }

    /** 目录解析优先级与级别过滤。 */
    private static void levelChecks() {
        Path fromHome = LogFiles.directory("/home/tester", "");
        check("未配置时用用户目录下的默认目录",
                fromHome.toString().replace('\\', '/').endsWith(".JavaSecExpToolKit/logs"));
        check("默认目录在用户目录之内",
                fromHome.toString().replace('\\', '/').contains("/home/tester/"));

        Path configured = LogFiles.directory("/home/tester", "/var/log/jsetk");
        check("配置优先于用户目录默认值",
                configured.toString().replace('\\', '/').endsWith("/var/log/jsetk"));
        check("环境变量 JSETK_LOG_DIR 优先于配置",
                util.Platform.env("JSETK_LOG_DIR").isEmpty()
                        || LogFiles.directory("/home/tester", "/var/log/jsetk")
                                .toString().replace('\\', '/')
                                .endsWith(util.Platform.env("JSETK_LOG_DIR").replace('\\', '/')));
        check("空白配置按未配置处理",
                LogFiles.directory("/home/tester", "   ")
                        .toString().replace('\\', '/').endsWith(".JavaSecExpToolKit/logs"));

        check("级别标识可解析", Log.Level.of("debug") == Log.Level.DEBUG);
        check("级别中文名可解析", Log.Level.of("仅错误") == Log.Level.ERROR);
        check("无法识别的级别回落到常规", Log.Level.of("verbose") == Log.Level.INFO);
        check("空级别回落到常规", Log.Level.of("") == Log.Level.INFO);
        check("常规级别放行常规记录", Log.Level.INFO.allows(Log.Level.INFO));
        check("常规级别放行错误记录", Log.Level.INFO.allows(Log.Level.ERROR));
        check("常规级别拦下调试记录", !Log.Level.INFO.allows(Log.Level.DEBUG));
        check("仅错误级别拦下警告记录", !Log.Level.ERROR.allows(Log.Level.WARN));
        check("关闭级别拦下一切", !Log.Level.OFF.allows(Log.Level.ERROR));
    }

    /** 按当天落盘、跨天切换文件。 */
    private static void rolloverChecks() throws Exception {
        Path dir = Files.createTempDirectory("jsetk-log-roll");
        try {
            final LocalDate first = LocalDate.of(2026, 3, 1);
            final LocalDate second = LocalDate.of(2026, 3, 2);
            Log.setDateSupplier(new java.util.function.Supplier<LocalDate>() {
                @Override
                public LocalDate get() {
                    return CURRENT[0];
                }
            });
            CURRENT[0] = first;
            Log.configure(dir, "debug", 7, false, true);

            Log.info("第一天第一条");
            Log.warn("第一天第二条");
            Path dayOne = dir.resolve(LogFiles.fileName(first));
            check("当天文件已创建", Files.isRegularFile(dayOne));
            String firstText = read(dayOne);
            check("记录写入当天文件", firstText.contains("第一天第一条"));
            check("记录含级别标识", firstText.contains("[INFO]"));
            check("警告记录含级别标识", firstText.contains("[WARN]"));
            check("记录含线程名", firstText.contains("[" + Thread.currentThread().getName() + "]"));
            check("当前打开的文件指向当天文件", dayOne.equals(Log.currentFile()));

            // 异常记录必须带栈
            Log.error("故意抛一个异常", new IllegalStateException("boom"));
            String withStack = read(dayOne);
            check("异常记录含异常类型", withStack.contains("IllegalStateException"));
            check("异常记录含异常消息", withStack.contains("boom"));
            check("异常记录含栈帧", withStack.contains("at LogCheck"));

            CURRENT[0] = second;
            Log.info("第二天第一条");
            Path dayTwo = dir.resolve(LogFiles.fileName(second));
            check("跨天后创建新文件", Files.isRegularFile(dayTwo));
            check("跨天后当前文件切到新日期", dayTwo.equals(Log.currentFile()));
            check("新内容进入新文件", read(dayTwo).contains("第二天第一条"));
            check("旧文件不再被追加",
                    !read(dayOne).contains("第二天第一条"));

            // 同一天重复记录仍然追加到同一文件
            Log.info("第二天第二条");
            String secondText = read(dayTwo);
            check("同一天多次记录写同一文件",
                    secondText.contains("第二天第一条") && secondText.contains("第二天第二条"));

            // 关闭后不再写
            Log.configure(dir, "debug", 7, false, false);
            Path beforeOff = dir.resolve(LogFiles.fileName(second));
            long sizeBefore = Files.size(beforeOff);
            Log.error("关闭后不应写入");
            check("关闭后文件内容不增长", Files.size(beforeOff) == sizeBefore);
            check("关闭后不再处于活动状态", !Log.isActive());
            check("关闭后不报告当前文件", Log.currentFile() == null);
        } finally {
            Log.setDateSupplier(null);
            Log.configure(null, "info", 7, false, false);
            deleteTree(dir);
        }
    }

    /** 清理的破坏边界：不该删的东西一个都不能删。 */
    private static void cleanupChecks() throws Exception {
        Path dir = Files.createTempDirectory("jsetk-log-clean");
        try {
            LocalDate today = LocalDate.of(2026, 5, 20);
            Files.write(dir.resolve("app-2026-05-10.log"), "old".getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("app-2026-05-19.log"), "keep".getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("app-2026-05-20.log"), "today".getBytes(StandardCharsets.UTF_8));
            // 必须万无一失的三类「不该删」：非本工具命名、日期非法、目录
            Files.write(dir.resolve("app-2026-99-99.log"), "junk".getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("unrelated.txt"), "mine".getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("app-backup.log"), "mine".getBytes(StandardCharsets.UTF_8));
            Path nested = Files.createDirectories(dir.resolve("app-2026-05-01.log"));

            int removed = LogFiles.cleanExpired(dir, today, 7);
            check("清理删除了 1 个过期日志", removed == 1);
            check("过期日志被删除", !Files.exists(dir.resolve("app-2026-05-10.log")));
            check("保留范围内的日志仍在", Files.exists(dir.resolve("app-2026-05-19.log")));
            check("当天日志仍在", Files.exists(dir.resolve("app-2026-05-20.log")));
            check("日期非法的同名文件不被删除", Files.exists(dir.resolve("app-2026-99-99.log")));
            check("非本工具命名的文件不被删除", Files.exists(dir.resolve("unrelated.txt")));
            check("前缀不符的文件不被删除", Files.exists(dir.resolve("app-backup.log")));
            check("同名目录不被删除", Files.isDirectory(nested));

            check("目录不存在时返回 0",
                    LogFiles.cleanExpired(dir.resolve("no-such-dir"), today, 7) == 0);
            check("目录为 null 时返回 0", LogFiles.cleanExpired(null, today, 7) == 0);

            // 启动即清理：预置一个过期文件，configure 后应当消失
            Files.write(dir.resolve("app-2026-01-01.log"), "old".getBytes(StandardCharsets.UTF_8));
            Log.setDateSupplier(new java.util.function.Supplier<LocalDate>() {
                @Override
                public LocalDate get() {
                    return LocalDate.of(2026, 5, 20);
                }
            });
            Log.configure(dir, "info", 7, false, true);
            check("启用日志时会清理过期文件",
                    !Files.exists(dir.resolve("app-2026-01-01.log")));
            check("清理结果可回显", Log.lastCleanupRemoved() >= 1);
            check("保留天数可读回", Log.retentionDays() == 7);
            check("级别标识可读回", "info".equals(Log.levelId()));
            Log.close();
            check("关闭后当前文件为 null", Log.currentFile() == null);
        } finally {
            Log.setDateSupplier(null);
            Log.configure(null, "info", 7, false, false);
            deleteTree(dir);
        }
    }

    /** 写入失败不得抛出；未捕获异常必须进日志。 */
    private static void failureChecks() throws Exception {
        // 用一个已存在的普通文件当「目录」：createDirectories 必然失败
        Path blocker = Files.createTempFile("jsetk-log-blocker", ".txt");
        try {
            Log.configure(blocker, "debug", 7, false, true);
            boolean threw = false;
            try {
                Log.error("目录不可写时记录", new RuntimeException("nested"));
                Log.info("目录不可写时记录普通信息");
                Log.warn("目录不可写时记录警告");
            } catch (Throwable error) {
                threw = true;
            }
            check("目录不可写时记录动作不抛异常", !threw);
            Log.close();
        } finally {
            Files.deleteIfExists(blocker);
        }

        // 超长描述被截断而不是无限写入
        Path dir = Files.createTempDirectory("jsetk-log-long");
        try {
            Log.configure(dir, "debug", 7, false, true);
            StringBuilder longText = new StringBuilder();
            for (int index = 0; index < 9000; index++) longText.append('x');
            Log.info(longText.toString());
            Path file = dir.resolve(LogFiles.fileName(LocalDate.now()));
            check("超长描述被截断", Files.isRegularFile(file) && Files.size(file) < 9000);
            check("超长描述不抛异常", true);
            Log.close();
        } finally {
            Log.configure(null, "info", 7, false, false);
            deleteTree(dir);
        }

        // 未捕获异常处理器
        Path crashDir = Files.createTempDirectory("jsetk-log-uncaught");
        try {
            Log.configure(crashDir, "debug", 7, false, true);
            Log.installUncaughtHandler();
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    throw new IllegalArgumentException("uncaught-probe");
                }
            }, "jsetk-uncaught-probe");
            thread.start();
            thread.join(5000L);
            Path file = crashDir.resolve(LogFiles.fileName(LocalDate.now()));
            String text = Files.isRegularFile(file) ? read(file) : "";
            check("后台线程未捕获异常被记录", text.contains("uncaught-probe"));
            check("记录中含线程名", text.contains("jsetk-uncaught-probe"));
            check("记录中含异常类型", text.contains("IllegalArgumentException"));
            Log.close();

            // 未启用时不该产生文件
            Path offDir = Files.createTempDirectory("jsetk-log-off");
            Log.configure(offDir, "info", 7, false, false);
            check("未启用时记录动作不创建目录",
                    !Files.exists(offDir.resolve(LogFiles.fileName(LocalDate.now()))));
            Log.close();
            deleteTree(offDir);
        } finally {
            Log.configure(null, "info", 7, false, false);
            deleteTree(crashDir);
        }
    }

    /** 当前日期：跨天用例通过它推进，避免真的等一天。 */
    private static final LocalDate[] CURRENT = {LocalDate.of(2026, 3, 1)};

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** 递归删除临时目录；日志句柄已关闭，删除失败不影响断言结论。 */
    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            java.util.stream.Stream<Path> walk = Files.walk(root);
            try {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(new java.util.function.Consumer<Path>() {
                    @Override
                    public void accept(Path path) {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException locked) {
                            // 临时目录清理失败不影响断言结论
                        }
                    }
                });
            } finally {
                walk.close();
            }
        } catch (IOException failed) {
            // 同上：清理失败只是留下临时目录
        }
    }

    private static void check(String message, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [ok] " + message);
        } else {
            failed++;
            System.err.println("  [FAIL] " + message);
        }
    }
}