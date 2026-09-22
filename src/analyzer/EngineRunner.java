package analyzer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 调用外部引擎（jar-analyzer-engine）构建分析数据库。
 *
 * <p>这是 B 方案：本地规则只能看到「引入了什么组件」，看不到「组件之间怎么调用」。
 * 需要判断某条链是否真的可达时，得先把目标 jar 解析成调用图数据库，
 * 再由 {@link ReportReader} 交给 Python 侧用 SQL 查询。
 *
 * <p>引擎的两个实测约束必须在这里处理掉：
 * <ul>
 *   <li>它固定把 jar-analyzer.db 写到**工作目录**，没有输出路径参数，
 *       因此必须显式指定工作目录并去那里取产物；</li>
 *   <li>构建大型 jar 是**分钟级**任务，必须设超时上限并能在超时后终止进程树。</li>
 * </ul>
 */
public final class EngineRunner {

    /** 引擎产出的数据库文件名，固定，不可配置。 */
    public static final String DATABASE_NAME = "jar-analyzer.db";

    /** 引擎的临时目录名，构建期间产生，分析完成可删。 */
    private static final String TEMP_DIR_NAME = "jar-analyzer-temp";

    /** 一次引擎调用的结果。 */
    public static final class Result {
        public final boolean ok;
        public final String output;
        public final Path database;
        public final long millis;

        Result(boolean ok, String output, Path database, long millis) {
            this.ok = ok;
            this.output = output == null ? "" : output;
            this.database = database;
            this.millis = millis;
        }
    }

    private EngineRunner() {
    }

    /**
     * 运行引擎构建数据库。
     *
     * @param engineJar  引擎 jar 路径（用户自备）
     * @param javaExe    java 可执行文件路径
     * @param target     待分析的 jar 或目录
     * @param workDir    工作目录，数据库会落在这里
     * @param quick      是否快速模式（跳过继承 / 字符串 / Spring 分析）
     * @param innerJars  是否解析嵌套 jar（Spring Boot fat jar 需要）
     * @param timeoutSeconds 超时上限，必须大于 0
     */
    public static Result build(String javaExe, Path engineJar, Path target, Path workDir,
                               boolean quick, boolean innerJars, int timeoutSeconds) {
        if (engineJar == null || !Files.isRegularFile(engineJar)) {
            return new Result(false, "引擎 jar 不存在：" + engineJar, null, 0);
        }
        if (target == null || !Files.exists(target)) {
            return new Result(false, "分析目标不存在：" + target, null, 0);
        }
        if (timeoutSeconds <= 0) {
            return new Result(false, "必须在配置页填写大于 0 的分析超时（秒）。", null, 0);
        }
        try {
            Files.createDirectories(workDir);
        } catch (IOException error) {
            return new Result(false, "无法创建工作目录：" + error.getMessage(), null, 0);
        }

        List<String> command = new ArrayList<String>();
        command.add(javaExe == null || javaExe.trim().isEmpty() ? "java" : javaExe.trim());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-jar");
        command.add(engineJar.toAbsolutePath().toString());
        command.add("--jar");
        command.add(target.toAbsolutePath().toString());
        if (quick) command.add("--quick");
        if (innerJars) {
            command.add("--inner-jars");
            // fat jar 的 class 在 BOOT-INF/classes 下，不修正类名会全部解析失败
            command.add("--fix-class");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        Process process = null;
        long started = System.currentTimeMillis();
        StringBuilder output = new StringBuilder();
        try {
            process = builder.start();
            final Process running = process;
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (BufferedReader source = new BufferedReader(
                            new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = source.readLine()) != null) {
                            synchronized (output) {
                                output.append(line).append(System.lineSeparator());
                            }
                        }
                    } catch (IOException ignored) {
                        // 进程被终止时读流会抛异常，属正常收尾路径
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            long millis = System.currentTimeMillis() - started;
            if (!finished) {
                // 超时必须连子进程一起收掉：引擎自己会再起 JVM 子任务，
                // 只 destroy 父进程会留下孤儿进程继续吃 CPU
                ProcessTree.kill(process);
                return new Result(false, "引擎超时（超过 " + timeoutSeconds + " 秒）已终止。"
                        + "可在配置页提高超时，或改用快速模式先摸底。", null, millis);
            }
            Path database = workDir.resolve(DATABASE_NAME);
            boolean exists = Files.isRegularFile(database);
            synchronized (output) {
                String text = output.toString();
                if (!exists) {
                    return new Result(false, "引擎没有产出 " + DATABASE_NAME + "。\n" + text, null, millis);
                }
                if (process.exitValue() != 0) {
                    // 引擎偶尔以非 0 退出但数据库已完整写出，这种情况按成功处理并附上日志
                    return new Result(true, text + "\n（引擎退出码 " + process.exitValue()
                            + "，但数据库已生成）", database, millis);
                }
                return new Result(true, text, database, millis);
            }
        } catch (IOException error) {
            return new Result(false, "无法启动引擎：" + error.getMessage(), null,
                    System.currentTimeMillis() - started);
        } catch (InterruptedException interrupted) {
            ProcessTree.kill(process);
            Thread.currentThread().interrupt();
            return new Result(false, "引擎调用被中断。", null, System.currentTimeMillis() - started);
        } finally {
            if (process != null && process.isAlive()) ProcessTree.kill(process);
        }
    }

    /** 删除引擎构建期产生的临时目录，失败静默（不影响分析结论）。 */
    public static void cleanTemp(Path workDir) {
        if (workDir == null) return;
        Path temp = workDir.resolve(TEMP_DIR_NAME);
        if (!Files.isDirectory(temp)) return;
        try {
            java.util.stream.Stream<Path> walk = Files.walk(temp);
            try {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 占用中的文件删不掉：留给使用者手工清理
                    }
                });
            } finally {
                walk.close();
            }
        } catch (IOException ignored) {
            // 清理失败不影响结论
        }
    }

    /** 本机 java 可执行文件路径；优先用当前 JVM 自己的，避免依赖 PATH。 */
    public static String currentJava() {
        String home = System.getProperty("java.home");
        if (home == null || home.isEmpty()) return "java";
        Path candidate = java.nio.file.Paths.get(home, "bin", "java.exe");
        if (Files.isRegularFile(candidate)) return candidate.toString();
        Path unix = java.nio.file.Paths.get(home, "bin", "java");
        return Files.isRegularFile(unix) ? unix.toString() : "java";
    }
}
