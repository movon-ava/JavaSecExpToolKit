package analyzer;

/**
 * 一个被识别出来的第三方依赖。
 *
 * <p>只描述「库里有什么」，不判断「有没有漏洞」：判定在
 * {@link VulnerabilityAnalyzer} 里，两者分开是为了让同一份依赖清单可以
 * 分别喂给本地规则与外部引擎，而不用重复解析。
 *
 * <p>{@code evidence} 记录这条依赖是从哪读出来的（如
 * {@code META-INF/maven/com.alibaba/fastjson/pom.properties}）。
 * 依赖版本判断错了，整份结论都会跟着错，所以必须能回溯来源。
 */
public final class Dependency {

    /** 依赖来源：决定可信度，pom 元数据最准，jar 文件名最不准。 */
    public enum Source {
        /** META-INF/maven/**&#47;pom.properties，带 groupId / artifactId / version。 */
        POM_PROPERTIES("pom.properties"),
        /** META-INF/maven/**&#47;pom.xml，同上但需要解析 XML。 */
        POM_XML("pom.xml"),
        /** MANIFEST.MF 的 Implementation-Title / Version 等条目。 */
        MANIFEST("MANIFEST"),
        /** 没有任何元数据时按 jar 文件名推断，版本可能不准。 */
        FILE_NAME("文件名");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        /** 界面显示用的中文说明。 */
        public String label() {
            return label;
        }
    }

    /** Maven 坐标，缺失时为空串。 */
    public final String groupId;
    public final String artifactId;
    /** 原始版本号，缺失时为空串。 */
    public final String version;
    /** 来源与出处，用于界面展示与排查。 */
    public final Source source;
    public final String evidence;

    public Dependency(String groupId, String artifactId, String version, Source source, String evidence) {
        this.groupId = groupId == null ? "" : groupId.trim();
        this.artifactId = artifactId == null ? "" : artifactId.trim();
        this.version = version == null ? "" : version.trim();
        this.source = source == null ? Source.FILE_NAME : source;
        this.evidence = evidence == null ? "" : evidence.trim();
    }

    /** Maven 坐标文本，形如 {@code com.alibaba:fastjson:1.2.24}。 */
    public String coordinate() {
        StringBuilder text = new StringBuilder();
        if (!groupId.isEmpty()) text.append(groupId).append(':');
        text.append(artifactId.isEmpty() ? "(未知)" : artifactId);
        text.append(':').append(version.isEmpty() ? "(未知)" : version);
        return text.toString();
    }

    /**
     * 用于去重的键。
     *
     * <p>同一个组件会在多个 jar 里出现（例如 fat jar 与依赖目录各有一份），
     * 去重必须按「坐标 + 版本」而不是按 jar：否则同一组件会被列出多次，
     * 使用者会以为真的引入了两份不同版本。
     */
    public String key() {
        return (groupId + ':' + artifactId + ':' + version).toLowerCase(java.util.Locale.ROOT);
    }

    /** 是否包含可判定的版本号。 */
    public boolean hasVersion() {
        return !version.isEmpty();
    }

    @Override
    public String toString() {
        return coordinate();
    }
}
