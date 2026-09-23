package analyzer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import util.Log;

/**
 * 调用外部后端 jar-analyzer 构建分析数据库。
 *
 * <p>本工具**不再自己实现**字节码分析与调用链推导，而是把 jar-analyzer 当作后端复用它的结果：
 * 它一次建库、之后任意组合查询都是秒级响应，且结果精确到具体类与方法。
 * 我们自己只做两件它不做的事——**把结果接上后续利用动作**（载荷生成 / 探测 / 服务端），
 * 以及**按漏洞类型与绕过手法下判断**。
 *
 * <p>后端的两个实测约束必须在这里处理掉：
 * <ul>
 *   <li>它固定把 {@code jar-analyzer.db} 写到**工作目录**，没有输出路径参数，
 *       因此必须显式指定工作目录并去那里取产物；</li>
 *   <li>构建大型 jar 是**分钟级**任务，必须设超时上限并能在超时后终止进程树。</li>
 * </ul>
 */
public final class EngineRunner {

    /** 后端产出的数据库文件名，固定，不可配置。 */
    public static final String DATABASE_NAME = "jar-analyzer.db";

    /** 后端构建期产生的临时目录名，分析完成可删。 */
    public static final String TEMP_DIR_NAME = "jar-analyzer-temp";

    /** 一次后端调用的结果。 */
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
     * 运行后端构建数据库。
     *
     * @param install    已定位的后端安装（形态与命令行由 {@link ToolkitLocator} 决定）
     * @param target     待分析的 jar 或目录
     * @param workDir    工作目录，数据库会落在这里
     * @param quick      是否快速模式（完整发行包不支持，调用方需在报告里说明）
     * @param innerJars  是否解析嵌套 jar（Spring Boot fat jar 需要）
     * @param timeoutSeconds 超时上限，必须大于 0
     */
    public static Result build(ToolkitLocator.Install install, Path target, Path workDir,
                               boolean quick, boolean innerJars, int timeoutSeconds) {
        if (install == null) {
            return new Result(false, "还没有定位到 jar-analyzer，无法建库。", null, 0);
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

        // 发行包不支持快速模式：如实告知而不是静默忽略，否则使用者会以为「已经快了」
        boolean effectiveQuick = quick && install.supportsQuick();
        List<String> command = install.command(target, innerJars, quick, true, true);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
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
                    } catch (IOException closed) {
                        // 进程被终止时读流会抛异常，属正常收尾路径
                        Log.debug("后端输出流已结束：" + closed.getMessage());
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            long millis = System.currentTimeMillis() - started;
            if (!finished) {
                // 超时必须连子进程一起收掉：后端自己会再起 JVM 子任务，
                // 只 destroy 父进程会留下孤儿进程继续吃 CPU
                ProcessTree.kill(process);
                Log.warn("后端超时已终止（上限 " + timeoutSeconds + " 秒，目标 " + target + "）。");
                return new Result(false, "后端超时（超过 " + timeoutSeconds + " 秒）已终止。"
                        + "可在配置页提高超时，或先用完整发行包（它没有快速模式，但更稳）。", null, millis);
            }
            Path database = workDir.resolve(DATABASE_NAME);
            boolean exists = Files.isRegularFile(database);
            synchronized (output) {
                String text = output.toString();
                if (quick && !effectiveQuick && !text.contains("不支持快速模式")) {
                    text = text + System.lineSeparator()
                            + "（提示：当前是完整发行包，它的 build 命令不支持快速模式，本次按完整模式执行。）";
                }
                if (!exists) {
                    // 目标本身不含 class（例如空 jar）时后端不会产出数据库，
                    // 这不是后端故障，因此提示要指向「目标是否选对」而不是「后端坏了」
                    return new Result(false, "后端没有产出 " + DATABASE_NAME + "。\n"
                            + "先确认目标里真的有 class 文件（空 jar / 纯资源 jar 建不出库）。\n" + text,
                            null, millis);
                }
                if (process.exitValue() != 0) {
                    // 后端偶尔以非 0 退出但数据库已完整写出，这种情况按成功处理并附上日志
                    return new Result(true, text + "\n（后端退出码 " + process.exitValue()
                            + "，但数据库已生成）", database, millis);
                }
                return new Result(true, text, database, millis);
            }
        } catch (IOException error) {
            Log.error("无法启动后端：" + error.getMessage(), error);
            return new Result(false, "无法启动后端：" + error.getMessage(), null,
                    System.currentTimeMillis() - started);
        } catch (InterruptedException interrupted) {
            ProcessTree.kill(process);
            Thread.currentThread().interrupt();
            Log.warn("后端调用被中断，已终止进程树。", interrupted);
            return new Result(false, "后端调用被中断。", null, System.currentTimeMillis() - started);
        } finally {
            if (process != null && process.isAlive()) ProcessTree.kill(process);
        }
    }

    /** 删除后端构建期产生的临时目录，失败静默（不影响分析结论）。 */
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
                    } catch (IOException locked) {
                        // 占用中的文件删不掉：留给使用者手工清理
                        Log.debug("临时文件删除失败，留给人工清理：" + path + "（"
                                + locked.getMessage() + "）");
                    }
                });
            } finally {
                walk.close();
            }
        } catch (IOException failed) {
            // 清理失败不影响结论
            Log.warn("清理后端临时目录失败：" + temp + "（" + failed.getMessage() + "）", failed);
        }
    }

    /** 后端产出的数据库是否可读（供界面判断能否直接查询）。 */
    public static boolean databaseReady(Path workDir) {
        return workDir != null && Files.isRegularFile(workDir.resolve(DATABASE_NAME));
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
