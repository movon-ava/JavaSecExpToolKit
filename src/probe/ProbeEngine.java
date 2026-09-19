package probe;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Python 探测引擎的调用入口：负责把 JAR 内的脚本释放到临时文件并执行。
 *
 * <p>界面只关心「拿到引擎输出的中文报告」，因此这里统一按 UTF-8 读取子进程输出；
 * 命令行参数转义交给 {@link util.Platform#commandArg}，避免各调用方自行拼串。
 */
public final class ProbeEngine {

    private ProbeEngine() {
    }

    /** 把 JAR 内的 fj_probe.py 释放成临时文件，供子进程执行。 */
    public static Path extractScript() throws IOException {
        InputStream input = ProbeEngine.class.getResourceAsStream("/python/fj_probe.py");
        if (input == null) throw new FileNotFoundException("JAR 内缺少 Python 探测资源");
        Path file = Files.createTempFile("fj-probe-", ".py");
        file.toFile().deleteOnExit();
        try (InputStream source = input; OutputStream target = Files.newOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = source.read(buffer)) >= 0) target.write(buffer, 0, n);
        }
        return file;
    }

    /**
     * 执行引擎命令并返回全部输出。
     *
     * <p>Python 在管道里默认按系统编码输出，这里显式要求 UTF-8，避免结果区中文乱码。
     */
    public static String run(List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append(System.lineSeparator());
        }
        int code = process.waitFor();
        if (code == 0 || text.length() > 0) return text.toString();
        return "探测引擎退出状态码: " + code;
    }

    /** 解析 Python 解释器：环境变量 FJ_PYTHON 优先，其次配置页，最后回落到 PATH。 */
    public static String resolvePython(String configured) {
        String value = System.getenv("FJ_PYTHON");
        if (value != null && !value.trim().isEmpty()) return value.trim();
        String fromConfig = configured == null ? "" : configured.trim();
        return fromConfig.isEmpty() ? "python" : fromConfig;
    }
}

