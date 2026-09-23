package analyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import util.Log;

/**
 * 反编译：把 class 还原成 Java 源码，用于人工确认规则命中是否成立。
 *
 * <p>用 CFR 而不是另起 Node 子进程：CFR 已经在运行期依赖 {@code java-chains-cli} 里，
 * 直接调用等于零新增依赖、零进程开销（实测整包 84 个类 3.5 秒）。
 * 外部反编译器（如 jar-analyzer/jsd）需要 Node 环境与一个 276KB 的 bundle，
 * 只有在「本机没有 JVM」这类场景才有优势，不作为默认路径。
 *
 * <p>反编译产物**只写不读**：输出到独立目录，供使用者用编辑器打开，
 * 本工具不会去解析这些源码。
 */
public final class Decompiler {

    private Decompiler() {
    }

    /** 一次反编译的结果。 */
    public static final class Result {
        /** 反编译出的源码文件数。 */
        public final int classCount;
        /** 输出目录。 */
        public final Path directory;
        /** 耗时（毫秒）。 */
        public final long millis;
        /** 失败原因；成功时为空串。 */
        public final String error;

        Result(int classCount, Path directory, long millis, String error) {
            this.classCount = classCount;
            this.directory = directory;
            this.millis = millis;
            this.error = error == null ? "" : error;
        }

        /** 是否成功。 */
        public boolean ok() {
            return error.isEmpty();
        }
    }

    /**
     * 反编译整个 jar / 目录到指定目录。
     *
     * @param target   jar 文件或目录
     * @param output   输出目录；不存在时自动创建
     * @param timeoutSeconds 超时上限；反编译大包可能很慢，必须能中断
     */
    public static Result decompile(Path target, Path output, int timeoutSeconds) {
        long started = System.currentTimeMillis();
        try {
            Files.createDirectories(output);
        } catch (IOException error) {
            Log.error("无法创建反编译输出目录 " + output + "：" + error.getMessage(), error);
            return new Result(0, output, 0, "无法创建输出目录：" + error.getMessage());
        }
        try {
            Map<String, String> options = new HashMap<String, String>();
            options.put("outputdir", output.toAbsolutePath().toString());
            options.put("silent", "true");
            // 关掉 CFR 自己写的统计 / 注释头，产物更干净，也避免多次运行混入时间戳
            options.put("comments", "false");
            org.benf.cfr.reader.api.CfrDriver driver =
                    new org.benf.cfr.reader.api.CfrDriver.Builder().withOptions(options).build();
            driver.analyse(Collections.singletonList(target.toAbsolutePath().toString()));
        } catch (RuntimeException | LinkageError failure) {
            Log.error("整包反编译失败：" + failure.getMessage(), failure);
            return new Result(0, output, System.currentTimeMillis() - started,
                    "反编译失败：" + failure.getMessage());
        }
        long millis = System.currentTimeMillis() - started;
        int count;
        try {
            count = countJavaFiles(output);
        } catch (IOException error) {
            Log.warn("统计反编译产物失败：" + error.getMessage(), error);
            return new Result(0, output, millis, "统计产物失败：" + error.getMessage());
        }
        if (count == 0) {
            return new Result(0, output, millis, "没有产出任何源码，确认目标是否包含 class 文件。");
        }
        // 超时是调用方的责任：CFR 是同步 API，无法中途打断，
        // 因此这里在完成后告知是否已超出预算，由界面决定给出提示还是丢弃结果
        if (timeoutSeconds > 0 && millis > timeoutSeconds * 1000L) {
            return new Result(count, output, millis,
                    "反编译耗时 " + (millis / 1000) + " 秒，超出预算 " + timeoutSeconds + " 秒。");
        }
        return new Result(count, output, millis, "");
    }

    /** 反编译单个类，用于「这条规则到底成不成立」时的定点确认。 */
    public static Result decompileClass(Path jar, String className, Path output, int timeoutSeconds) {
        if (className == null || className.trim().isEmpty()) {
            return new Result(0, output, 0, "请填写要反编译的类名。");
        }
        if (jar == null || !Files.isRegularFile(jar)) {
            return new Result(0, output, 0, "请先选择一个 jar 文件。");
        }
        try {
            Files.createDirectories(output);
        } catch (IOException error) {
            Log.error("无法创建反编译输出目录 " + output + "：" + error.getMessage(), error);
            return new Result(0, output, 0, "无法创建输出目录：" + error.getMessage());
        }
        long started = System.currentTimeMillis();
        try {
            Map<String, String> options = new HashMap<String, String>();
            options.put("outputdir", output.toAbsolutePath().toString());
            options.put("silent", "true");
            options.put("comments", "false");
            // 把 jar 加进 CFR 的 extraclasspath，否则被反编译的类引用到同包其它类时
            // CFR 解析不到父类 / 接口，只能输出残缺源码
            options.put("extraclasspath", jar.toAbsolutePath().toString());
            org.benf.cfr.reader.api.CfrDriver driver =
                    new org.benf.cfr.reader.api.CfrDriver.Builder().withOptions(options).build();
            driver.analyse(Collections.singletonList(
                    className.trim().replace('/', '.').replace(".class", "")));
        } catch (RuntimeException | LinkageError failure) {
            Log.error("反编译类 " + className + " 失败：" + failure.getMessage(), failure);
            return new Result(0, output, System.currentTimeMillis() - started,
                    "反编译失败：" + failure.getMessage());
        }
        long millis = System.currentTimeMillis() - started;
        try {
            int count = countJavaFiles(output);
            if (count == 0) {
                return new Result(0, output, millis, "没有找到类 " + className + "，确认类名与 jar 是否匹配。");
            }
            return new Result(count, output, millis, "");
        } catch (IOException error) {
            Log.warn("统计反编译产物失败：" + error.getMessage(), error);
            return new Result(0, output, millis, "统计产物失败：" + error.getMessage());
        }
    }

    /** 统计输出目录下的 .java 文件数。 */
    private static int countJavaFiles(Path directory) throws IOException {
        java.util.stream.Stream<Path> walk = Files.walk(directory);
        try {
            return (int) walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .count();
        } finally {
            walk.close();
        }
    }
}
