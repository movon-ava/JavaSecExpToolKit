package analyzer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C 方案的核心：只读依赖坐标，不执行任何目标代码。
 *
 * <p>为什么以 {@code pom.properties} 为主：jar 文件名是**推测**，
 * 同一个组件会有 {@code fastjson-1.2.24.jar}、{@code fastjson.jar}、
 * {@code com.alibaba.fastjson-1.2.24.jar} 等多种写法，靠文件名猜版本必然出错，
 * 而这个功能的全部结论都建立在版本之上。{@code META-INF/maven/} 下的
 * pom.properties 是打包时写进去的**事实**，优先采信。
 *
 * <p>读取全部走 {@link JarFile} 的条目枚举，不解压、不落盘、不加载类：
 * 目标 jar 可能是任意来源的产物，解析期间不得让它获得执行机会。
 */
public final class DependencyScanner {

    /**
     * pom.properties 里打包时会写入的 Maven 坐标键。
     *
     * <p>必须是小写：读取时为了兼容大小写不一致的打包工具，已把键统一转成小写再存入，
     * 这里若写成驼峰的 {@code groupId} 就永远取不到值——整份依赖清单会静默退化成
     * 「按文件名推断」，版本判定随之全部失真。
     */
    private static final String KEY_GROUP = "groupid";
    private static final String KEY_ARTIFACT = "artifactid";
    private static final String KEY_VERSION = "version";

    /** pom.xml 里的坐标；只做标签取值，不解析完整 XML。 */
    private static final Pattern POM_GROUP = Pattern.compile("<groupId>([^<]+)</groupId>");
    private static final Pattern POM_ARTIFACT = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern POM_VERSION = Pattern.compile("<version>([^<]+)</version>");

    /** 文件名推断：{@code name-1.2.3.jar} 取最后一段形如版本的部分。 */
    private static final Pattern FILE_VERSION =
            Pattern.compile("-(\\d+(?:\\.\\d+)*(?:[-.][A-Za-z0-9]+)?)\\.jar$", Pattern.CASE_INSENSITIVE);

    /** 单个 jar 内扫描的元数据条目上限，避免畸形包把内存吃满。 */
    private static final int MAX_METADATA_ENTRIES = 4096;

    private DependencyScanner() {
    }

    /**
     * 扫描一个 jar 内的依赖声明。
     *
     * <p>返回按坐标去重后的清单，顺序稳定（先 pom 元数据，后清单推断）。
     */
    public static List<Dependency> scan(Path jar) throws IOException {
        Map<String, Dependency> found = new LinkedHashMap<String, Dependency>();
        String fileName = jar.getFileName() == null ? "" : jar.getFileName().toString();
        try (JarFile file = new JarFile(jar.toFile())) {
            scanPomMetadata(file, fileName, found);
            if (found.isEmpty()) scanManifest(file, fileName, found);
        }
        if (found.isEmpty()) {
            Dependency guessed = fromFileName(fileName, jar.toString());
            if (guessed != null) found.put(guessed.key(), guessed);
        }
        return Collections.unmodifiableList(new ArrayList<Dependency>(found.values()));
    }

    /** pom.properties 优先，其次 pom.xml：同一个 jar 里两者都有时只取属性文件。 */
    private static void scanPomMetadata(JarFile file, String jarName, Map<String, Dependency> found)
            throws IOException {
        List<String> properties = new ArrayList<String>();
        List<String> poms = new ArrayList<String>();
        int seen = 0;
        java.util.Enumeration<JarEntry> entries = file.entries();
        while (entries.hasMoreElements() && seen < MAX_METADATA_ENTRIES) {
            JarEntry entry = entries.nextElement();
            seen++;
            String name = entry.getName();
            if (!name.startsWith("META-INF/maven/")) continue;
            if (name.endsWith("/pom.properties")) {
                properties.add(name);
            } else if (name.endsWith("/pom.xml")) {
                poms.add(name);
            }
        }
        for (String entry : properties) {
            String text = readText(file, entry);
            Dependency dependency = fromProperties(text, entry);
            if (dependency != null) found.put(dependency.key(), dependency);
        }
        if (!found.isEmpty()) return;
        for (String entry : poms) {
            String text = readText(file, entry);
            Dependency dependency = fromPom(text, entry);
            if (dependency != null) found.put(dependency.key(), dependency);
        }
    }

    /**
     * 从 pom.properties 文本取出坐标；缺 artifactId 时视为无效条目。
     *
     * <p>对包外可见是刻意的：这是不带任何 IO 的纯函数，公开它才能被自检直接验证
     * 「键名大小写」「注释行」「缺字段」这些分支——包级可见时这些分支只能靠造文件覆盖。
     */
    public static Dependency fromProperties(String text, String evidence) {
        if (text == null || text.isEmpty()) return null;
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int split = trimmed.indexOf('=');
            if (split <= 0) continue;
            values.put(trimmed.substring(0, split).trim().toLowerCase(Locale.ROOT),
                    trimmed.substring(split + 1).trim());
        }
        return build(values.get(KEY_GROUP), values.get(KEY_ARTIFACT), values.get(KEY_VERSION),
                Dependency.Source.POM_PROPERTIES, evidence);
    }

    /**
     * 从 pom.xml 文本取出坐标。
     *
     * <p>只认「第一个匹配」：pom.xml 里 {@code <dependencies>} 段也含 groupId / artifactId，
     * 按出现顺序取会把某个传递依赖当成这个 jar 自己的坐标，
     * 因此只取 {@code </parent>} 或文件开头附近、且在 dependencies 段之前的那一组。
     */
    public static Dependency fromPom(String text, String evidence) {
        if (text == null || text.isEmpty()) return null;
        String head = text;
        int dependencies = text.indexOf("<dependencies>");
        if (dependencies > 0) head = text.substring(0, dependencies);
        String group = firstGroup(POM_GROUP, head);
        String artifact = firstGroup(POM_ARTIFACT, head);
        String version = firstGroup(POM_VERSION, head);
        return build(group, artifact, version, Dependency.Source.POM_XML, evidence);
    }

    /** MANIFEST.MF 的 Implementation-* 条目，少量老包只有它可用。 */
    private static void scanManifest(JarFile file, String jarName, Map<String, Dependency> found)
            throws IOException {
        Manifest manifest = file.getManifest();
        if (manifest == null) return;
        Attributes attributes = manifest.getMainAttributes();
        String title = attributes.getValue("Implementation-Title");
        String version = attributes.getValue("Implementation-Version");
        String vendor = attributes.getValue("Implementation-Vendor-Id");
        Dependency dependency = build(vendor, title, version, Dependency.Source.MANIFEST,
                jarName + "!/META-INF/MANIFEST.MF");
        if (dependency != null) found.put(dependency.key(), dependency);
    }

    /** 从 jar 文件名推断；只有形如 name-1.2.3.jar 时才给出版本。纯函数，同上对包外可见。 */
    public static Dependency fromFileName(String jarName, String evidence) {
        if (jarName == null || jarName.isEmpty()) return null;
        String lower = jarName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jar")) return null;
        String name = jarName.substring(0, jarName.length() - 4);
        Matcher matcher = FILE_VERSION.matcher(jarName);
        if (!matcher.find()) {
            return build("", name, "", Dependency.Source.FILE_NAME, evidence);
        }
        String version = matcher.group(1);
        String artifact = name.substring(0, name.length() - version.length() - 1);
        return build("", artifact, version, Dependency.Source.FILE_NAME, evidence);
    }

    private static Dependency build(String group, String artifact, String version,
                                    Dependency.Source source, String evidence) {
        String name = artifact == null ? "" : artifact.trim();
        if (name.isEmpty()) return null;
        return new Dependency(group, name, version, source, evidence);
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /** 读 jar 内文本条目；条目过大或非文本时返回空串而不是抛异常。 */
    private static String readText(JarFile file, String entryName) {
        JarEntry entry = file.getJarEntry(entryName);
        if (entry == null) return "";
        try (InputStream input = file.getInputStream(entry)) {
            return new String(readAll(input), StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "";
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while ((count = input.read(chunk)) >= 0) buffer.write(chunk, 0, count);
        return buffer.toByteArray();
    }

    /**
     * 扫描目录下的全部 jar（含子目录）。
     *
     * <p>目录形式用于「lib 依赖目录」这一典型场景：一次把整个 lib 目录丢进来，
     * 比逐个 jar 点选更贴近实际排查方式。
     */
    public static List<Path> jarsIn(Path root) throws IOException {
        Set<Path> jars = new LinkedHashSet<Path>();
        if (root == null) return new ArrayList<Path>();
        if (java.nio.file.Files.isRegularFile(root)) {
            jars.add(root);
            return new ArrayList<Path>(jars);
        }
        if (!java.nio.file.Files.isDirectory(root)) return new ArrayList<Path>();
        java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(root);
        try {
            walk.filter(java.nio.file.Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
        } finally {
            walk.close();
        }
        return new ArrayList<Path>(jars);
    }
}
