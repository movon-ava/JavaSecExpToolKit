package analyzer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import util.Log;

/**
 * 定位外部分析后端 jar-analyzer 的安装位置，并给出可直接执行的命令行。
 *
 * <p>为什么要有这个类：本工具**不再自己实现**字节码分析与调用链推导，
 * 而是把 jar-analyzer 当作外部分析后端复用它的结果（设计口径见
 * {@code docs/DESIGN-analyze.md}）。这样做的直接后果是「同一件事有两种启动形态」：
 *
 * <ul>
 *   <li>完整发行包（解压出来的 windows-full 目录）：用 {@code -cp <jar> <主类> build}
 *       启动，且包内自带 JRE，用它比用当前 JVM 更稳；它**没有**快速模式参数。</li>
 *   <li>单独的引擎 fat jar：用 {@code -jar <jar>} 启动，有 {@code --quick} 与
 *       {@code --fix-class}。</li>
 * </ul>
 *
 * <p>把「找得到吗、属于哪种形态、到底该怎么起」集中在一处，界面与编排层只拿到一个
 * 可直接使用的 {@link Install}，不必各自判断用户装的是哪一种。参数名写错只会表现为
 * 「后端没反应」，排查成本极高，因此命令行拼装也放在这里，由自检逐项断言。
 */
public final class ToolkitLocator {

    /** 完整发行包的主类（6.x 实测）。 */
    public static final String MAIN_CLASS = "me.n1ar4.jar.analyzer.starter.Application";

    /** 发行包主 jar 相对包根目录的位置：{@code lib/jar-analyzer-<版本>.jar}。 */
    private static final String LIB_DIR = "lib";

    /** 发行包自带 JRE 的可执行文件相对路径。 */
    private static final String JRE_BIN = "jre/bin/java.exe";

    /** 环境变量：使用者不想写配置页时可以用它指定安装根目录。 */
    public static final String ENV_HOME = "JSETK_JAR_ANALYZER_HOME";

    /** 在候选目录里递归查找的最大层数：再深就不是「安装目录」而是整个磁盘了。 */
    private static final int MAX_SCAN_DEPTH = 2;

    /** 一次定位的结果：一种可执行的启动形态。 */
    public static final class Install {
        /** 启动用的 jar。 */
        public final Path jar;
        /** 发行包根目录；单独引擎 jar 时为 jar 所在目录。 */
        public final Path home;
        /** 发行包的 JVM 主类；引擎 fat jar 为 null（表示用 -jar 启动）。 */
        public final String mainClass;
        /** 启动用的 java 可执行文件。 */
        public final Path javaExe;
        /** 定位来源，用于在界面上说明「是从哪儿找到的」。 */
        public final String origin;

        Install(Path jar, Path home, String mainClass, Path javaExe, String origin) {
            this.jar = jar;
            this.home = home;
            this.mainClass = mainClass;
            this.javaExe = javaExe;
            this.origin = origin == null ? "" : origin;
        }

        /** 是否为完整发行包（有主类、可能自带 JRE）。 */
        public boolean isDistribution() {
            return mainClass != null;
        }

        /** 版本号：从 jar 文件名里取，取不到返回空串。 */
        public String version() {
            String name = jar.getFileName().toString();
            int start = name.lastIndexOf('-');
            if (start < 0 || !name.endsWith(".jar")) return "";
            return name.substring(start + 1, name.length() - 4);
        }

        /** 一行摘要：形态、版本、来源。 */
        public String describe() {
            StringBuilder text = new StringBuilder();
            text.append(isDistribution() ? "完整发行包" : "引擎 fat jar");
            String version = version();
            if (!version.isEmpty()) text.append(" ").append(version);
            text.append("　").append(jar);
            if (!origin.isEmpty()) text.append("　（来自 ").append(origin).append("）");
            return text.toString();
        }

        /**
         * 构建命令行。
         *
         * <p>发行包与引擎 jar 的参数集**不同**，因此在这里分叉而不是让调用方传一堆开关：
         * 发行包没有 {@code --quick} 与 {@code --fix-class}，多传会被它当成未知命令而直接失败。
         *
         * @param target    待分析的 jar 或目录
         * @param innerJars 是否解析嵌套 jar（Spring Boot fat jar 必须开）
         * @param quick     是否快速模式（仅引擎 jar 支持；发行包忽略）
         * @param delExist  构建前删除已有数据库（发行包支持）
         * @param delCache  构建前删除缓存（发行包支持）
         */
        public List<String> command(Path target, boolean innerJars, boolean quick,
                                    boolean delExist, boolean delCache) {
            List<String> command = new ArrayList<String>();
            command.add(javaExe == null ? "java" : javaExe.toString());
            command.add("-Dfile.encoding=UTF-8");
            if (isDistribution()) {
                command.add("-cp");
                command.add(jar.toAbsolutePath().toString());
                command.add(mainClass);
                command.add("build");
                command.add("--jar");
                command.add(target.toAbsolutePath().toString());
                if (innerJars) command.add("--inner-jars");
                if (delExist) command.add("--del-exist");
                if (delCache) command.add("--del-cache");
                // 发行包的 build 命令没有 --quick：请求快速模式时不静默忽略，
                // 由上层在报告里明确写出「完整发行包不支持快速模式」
                return command;
            }
            command.add("-jar");
            command.add(jar.toAbsolutePath().toString());
            command.add("--jar");
            command.add(target.toAbsolutePath().toString());
            if (quick) command.add("--quick");
            if (innerJars) {
                command.add("--inner-jars");
                // fat jar 的 class 在 BOOT-INF/classes 下，不修正类名会全部解析失败
                command.add("--fix-class");
            }
            return command;
        }

        /** 该形态是否支持快速模式。 */
        public boolean supportsQuick() {
            return !isDistribution();
        }
    }

    private ToolkitLocator() {
    }

    /**
     * 定位安装位置。
     *
     * @param configured 配置页填写的路径（安装目录或 jar 文件）；可空
     * @return 定位结果；找不到返回 null，由调用方给出「去哪儿找过」的可读提示
     */
    public static Install locate(String configured) {
        Install direct = resolve(configured, "配置页");
        if (direct != null) return direct;
        Install env = resolve(System.getenv(ENV_HOME), "环境变量 " + ENV_HOME);
        if (env != null) return env;
        for (Path root : defaultRoots()) {
            Install found = scan(root, root.toString());
            if (found != null) return found;
        }
        return null;
    }

    /**
     * 找不到时的提示：把**找过哪些地方**如实列出来。
     *
     * <p>只说「没找到」会让使用者反复怀疑是不是版本不对；列出候选路径才能一眼看出
     * 「它其实在别处」或「配置页里那行填错了」。
     */
    public static List<String> searchedPlaces(String configured) {
        List<String> places = new ArrayList<String>();
        if (configured != null && !configured.trim().isEmpty()) {
            places.add("配置页: " + configured.trim());
        } else {
            places.add("配置页: （未填写）");
        }
        String env = System.getenv(ENV_HOME);
        places.add("环境变量 " + ENV_HOME + ": " + (env == null || env.isEmpty() ? "（未设置）" : env));
        for (Path root : defaultRoots()) {
            places.add("约定位置: " + root);
        }
        return places;
    }

    /** 解析一个候选路径：既可以是安装目录，也可以是 jar 文件。 */
    private static Install resolve(String candidate, String origin) {
        if (candidate == null || candidate.trim().isEmpty()) return null;
        Path path;
        try {
            path = Paths.get(candidate.trim());
        } catch (RuntimeException invalid) {
            return null;
        }
        if (Files.isRegularFile(path)) {
            return fromJar(path, path.getParent(), origin);
        }
        if (Files.isDirectory(path)) {
            return scan(path, origin);
        }
        return null;
    }

    /**
     * 约定位置：只扫这几处，不做全盘搜索。
     *
     * <p>全盘遍历会在使用者的机器上产生难以解释的卡顿，而且「装了但没配」本来就该由
     * 配置页明确指定。这里只覆盖「顺手放进来」的几种常见布局。
     */
    private static List<Path> defaultRoots() {
        List<Path> roots = new ArrayList<Path>();
        addRoot(roots, System.getProperty("user.dir"));
        Path code = codeLocation();
        if (code != null) {
            addRoot(roots, code.toString());
            addRoot(roots, code.resolve("tools").toString());
        }
        addRoot(roots, Paths.get(System.getProperty("user.home", "."),
                ".JavaSecExpToolKit", "tools").toString());
        return roots;
    }

    private static void addRoot(List<Path> roots, String candidate) {
        if (candidate == null || candidate.isEmpty()) return;
        try {
            Path path = Paths.get(candidate).toAbsolutePath().normalize();
            if (Files.isDirectory(path) && !roots.contains(path)) roots.add(path);
        } catch (RuntimeException invalid) {
            Log.debug("候选目录不可用：" + candidate + "（" + invalid.getMessage() + "）");
        }
    }

    /** 本工具自身 class 所在目录（开发期是 target/classes，打包后是 jar 所在目录）。 */
    private static Path codeLocation() {
        try {
            java.net.URL url = ToolkitLocator.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            if (url == null) return null;
            return Paths.get(url.toURI());
        } catch (java.net.URISyntaxException broken) {
            return null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * 在一个目录里找可用的安装：先看目录本身，再看它的子目录（最多两层）。
     *
     * <p>子目录这一层是必要的：解压得到的目录名通常带版本号与平台后缀
     * （{@code jar-analyzer-6.1-windows-full}），使用者往往把上一级目录填进配置。
     */
    private static Install scan(Path root, String origin) {
        Install here = scanOne(root, origin);
        if (here != null) return here;
        return scanChildren(root, origin, 1);
    }

    private static Install scanChildren(Path root, String origin, int depth) {
        if (depth > MAX_SCAN_DEPTH) return null;
        List<Path> children = new ArrayList<Path>();
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(root);
            try {
                for (Path child : stream) {
                    if (Files.isDirectory(child)) children.add(child);
                }
            } finally {
                stream.close();
            }
        } catch (IOException unreadable) {
            Log.debug("目录不可读：" + root + "（" + unreadable.getMessage() + "）");
            return null;
        }
        for (Path child : children) {
            Install found = scanOne(child, origin);
            if (found != null) return found;
        }
        for (Path child : children) {
            Install found = scanChildren(child, origin, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** 判断一个目录本身是不是安装：{@code lib/jar-analyzer-*.jar} 或直接放着引擎 jar。 */
    private static Install scanOne(Path dir, String origin) {
        Path lib = dir.resolve(LIB_DIR);
        Install fromLib = jarIn(lib, origin, true);
        if (fromLib != null) return fromLib;
        return jarIn(dir, origin, false);
    }

    /**
     * 在一个目录里挑出合适的 jar。
     *
     * <p>优先选发行包主 jar（{@code jar-analyzer-<版本>.jar}）：一个目录里可能同时存在
     * 主 jar 与若干插件 jar，选错的表现是「启动了但 build 命令不存在」。
     */
    private static Install jarIn(Path dir, String origin, boolean distribution) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        List<Path> candidates = new ArrayList<Path>();
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar");
            try {
                for (Path file : stream) {
                    String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (!name.contains("jar-analyzer")) continue;
                    candidates.add(file);
                }
            } finally {
                stream.close();
            }
        } catch (IOException unreadable) {
            Log.debug("目录不可读：" + dir + "（" + unreadable.getMessage() + "）");
            return null;
        }
        if (candidates.isEmpty()) return null;
        java.util.Collections.sort(candidates);
        Path main = null;
        for (Path file : candidates) {
            if (isMainJar(file)) {
                main = file;
                break;
            }
        }
        if (main == null) main = candidates.get(candidates.size() - 1);
        return fromJar(main, dir, origin);
    }

    /** 主 jar：{@code jar-analyzer-<版本>.jar}，而不是 {@code -engine} / {@code -sources} 等附加产物。 */
    private static boolean isMainJar(Path jar) {
        String name = jar.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!name.startsWith("jar-analyzer-")) return false;
        if (name.contains("sources") || name.contains("javadoc")) return false;
        return true;
    }

    /** 由一个 jar 补齐形态信息：主类、JRE、根目录。 */
    private static Install fromJar(Path jar, Path home, String origin) {
        String name = jar.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        boolean engine = name.contains("engine");
        Path javaExe = engine ? null : bundledJava(home);
        if (engine) {
            return new Install(jar, home, null,
                    javaExe != null ? javaExe : Paths.get(EngineRunner.currentJava()), origin);
        }
        if (javaExe == null) javaExe = Paths.get(EngineRunner.currentJava());
        return new Install(jar, home, MAIN_CLASS, javaExe, origin);
    }

    /** 发行包自带的 JRE；没有则返回 null（由调用方回落到当前 JVM）。 */
    private static Path bundledJava(Path home) {
        if (home == null) return null;
        Path candidate = home.resolve(JRE_BIN);
        if (Files.isRegularFile(candidate)) return candidate;
        Path unix = home.resolve("jre/bin/java");
        return Files.isRegularFile(unix) ? unix : null;
    }
}
