package analyzer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import util.Log;

/**
 * 运行 Python 辅助脚本并回收输出。
 *
 * <p>放在 analyzer 包内而不是复用探测引擎的同类方法：分析功能必须能独立编译与测试，
 * 一旦反向依赖 {@code probe} 包，依赖边界就出现「下层依赖上层」，之后想单独换掉
 * 探测引擎会牵动分析功能。这里的实现只做三件事——释放资源、起进程、收输出。
 *
 * <p>与 {@link EngineRunner} 一致，必须支持超时终止：外部脚本同样可能卡住。
 */
public final class ScriptRunner {

    /** Python 解释器候选顺序：配置值优先，其次环境变量，最后 PATH。 */
    private static final String ENV_PYTHON = "FJ_PYTHON";

    private ScriptRunner() {
    }

    /**
     * 把 JAR 内的脚本资源释放成临时文件。
     *
     * @param resource JAR 内路径，例如 {@code /python/jar_report.py}
     */
    public static Path extract(String resource) throws IOException {
        InputStream input = ScriptRunner.class.getResourceAsStream(resource);
        if (input == null) throw new IOException("JAR 内缺少资源：" + resource);
        String suffix = resource.endsWith(".py") ? ".py" : ".tmp";
        Path file = Files.createTempFile("jsetk-analyzer-", suffix);
        file.toFile().deleteOnExit();
        try (InputStream source = input; OutputStream target = Files.newOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) >= 0) target.write(buffer, 0, count);
        }
        return file;
    }

    /**
     * 选择 Python 解释器。
     *
     * @param configured 配置页里填的解释器路径；为空时依次尝试环境变量与 PATH
     */
    public static String python(String configured) {
        if (configured != null && !configured.trim().isEmpty()) return configured.trim();
        String fromEnv = System.getenv(ENV_PYTHON);
        if (fromEnv != null && !fromEnv.trim().isEmpty()) return fromEnv.trim();
        return "python";
    }

    /**
     * 执行命令并返回合并后的输出。
     *
     * @param timeoutSeconds 超时上限；小于等于 0 表示不限制（调用方应避免）
     */
    public static String run(List<String> command, int timeoutSeconds) {
        ProcessBuilder builder = new ProcessBuilder(command);
        // 管道里按系统编码输出会让中文结果乱码，显式要求 UTF-8
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.redirectErrorStream(true);
        Process process = null;
        StringBuilder output = new StringBuilder();
        try {
            process = builder.start();
            final Process running = process;
            final StringBuilder sink = output;
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (BufferedReader source = new BufferedReader(
                            new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = source.readLine()) != null) {
                            synchronized (sink) {
                                sink.append(line).append(System.lineSeparator());
                            }
                        }
                    } catch (IOException closed) {
                        // 进程结束时读流中断属正常路径
                        Log.debug("脚本输出流已结束：" + closed.getMessage());
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = timeoutSeconds <= 0
                    ? waitForever(process) : process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                ProcessTree.kill(process);
                Log.warn("脚本超时已终止（上限 " + timeoutSeconds + " 秒）。");
                synchronized (output) {
                    return output + System.lineSeparator() + "命令超时（超过 " + timeoutSeconds + " 秒）已终止。";
                }
            }
            reader.join(2000L);
            synchronized (output) {
                return output.toString();
            }
        } catch (IOException error) {
            Log.error("无法启动命令：" + error.getMessage(), error);
            return "无法启动命令：" + error.getMessage();
        } catch (InterruptedException interrupted) {
            ProcessTree.kill(process);
            Thread.currentThread().interrupt();
            Log.warn("命令被中断，已终止进程树。", interrupted);
            return "命令被中断。";
        } finally {
            if (process != null && process.isAlive()) ProcessTree.kill(process);
        }
    }

    private static boolean waitForever(Process process) throws InterruptedException {
        process.waitFor();
        return true;
    }

    /** 组装「python 脚本 参数…」的命令行。 */
    public static List<String> pythonCommand(String python, Path script, String... arguments) {
        List<String> command = new ArrayList<String>();
        command.add(python);
        command.add("-X");
        command.add("utf8");
        command.add(script.toAbsolutePath().toString());
        if (arguments != null) {
            for (String argument : arguments) {
                if (argument != null) command.add(argument);
            }
        }
        return command;
    }
}
