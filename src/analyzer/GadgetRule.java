package analyzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 一条 gadget 判定规则：**哪些依赖同时存在**时，这条链才可能被构建出来。
 *
 * <p>与上游工具（jar-analyzer）的机制对照——我们只借鉴它的判定思路，规则内容全部自研，
 * 原因是上游为 GPLv3，把它的规则文件搬进本仓库会有许可问题：
 *
 * <p>它的做法是「一串 jar 文件名必须全部出现在扫描目录里」（AND 语义），本类保留 AND 语义，
 * 但把判定口径从「文件名」换成「Maven 坐标 + 版本区间」，并把匹配能力补了三点：
 * <ul>
 *   <li>文件名不承载版本约束：{@code commons-collections-3.2.2.jar} 在 3.2.2 上已经打过补丁，
 *       只看「存在这个 jar」会把它当成可用链，这是实打实的误报。本类带版本区间与排除版本。</li>
 *   <li>artifactId 支持通配符：同一条链在 fork 与改名包上（如 {@code *-collections}）也能命中，
 *       上游只支持精确名与 {@code *} 全通配两档。</li>
 *   <li>缺失项逐条列出：上游只报「凑齐了什么」，本类同时报「还差什么」，
 *       使用者才知道要补哪一个依赖才能让链成立。</li>
 * </ul>
 *
 * <p>判定边界：本类只回答「classpath 上是否具备构建该链的组件」，
 * **不回答链是否可达**。可达性取决于有没有反序列化入口、入口参数能否被外部控制，
 * 那需要调用链分析（sink 命中 + 特征匹配）继续往上追。
 */
public final class GadgetRule {

    /** 规则类型：与上游的 NATIVE / HESSIAN / JDBC / FASTJSON 同构，并按本项目覆盖面扩档。 */
    public static final class Category {
        public static final String NATIVE = "原生反序列化";
        public static final String HESSIAN = "Hessian";
        public static final String JDBC = "JDBC / 数据库驱动";
        public static final String FASTJSON = "fastjson";
        public static final String JACKSON = "Jackson";
        public static final String TOSTRING = "toString 触发";
        public static final String TEMPLATE = "模板与表达式";
        public static final String BCL = "类加载器";

        private Category() {
        }
    }

    /**
     * 版本区间：下界与上界都可为空（空表示该侧不限），并可列出明确排除的版本。
     *
     * <p>为什么需要「排除版本」：同一组件的修复通常只落在某个小版本上
     * （例如 CommonsCollections 3.2.2 修补了 InvokerTransformer 的可用性），
     * 用「低于某个上界」表达不了「中间一个版本已修复、之后又出现新变体」这种情况。
     */
    public static final class Range {
        public final String lower;
        public final String upper;
        public final List<String> excluded;

        public Range(String lower, String upper, String... excluded) {
            this.lower = lower == null ? "" : lower.trim();
            this.upper = upper == null ? "" : upper.trim();
            List<String> list = new ArrayList<String>();
            if (excluded != null) {
                for (String version : excluded) {
                    if (version != null && !version.trim().isEmpty()) list.add(version.trim());
                }
            }
            this.excluded = Collections.unmodifiableList(list);
        }

        /** 不限版本：只要组件在 classpath 上即满足。 */
        public static Range any() {
            return new Range("", "");
        }

        /** 版本是否落在区间内；无法比较时返回 false（宁可不报，不可误报）。 */
        public boolean contains(String version) {
            if (lower.isEmpty() && upper.isEmpty() && excluded.isEmpty()) return true;
            for (String exclude : excluded) {
                Integer equal = Version.compareNullable(version, exclude);
                if (equal != null && equal.intValue() == 0) return false;
            }
            if (!upper.isEmpty()) {
                Integer toUpper = Version.compareNullable(version, upper);
                if (toUpper == null || toUpper.intValue() > 0) return false;
            }
            if (!lower.isEmpty()) {
                Integer toLower = Version.compareNullable(version, lower);
                if (toLower == null || toLower.intValue() < 0) return false;
            }
            return true;
        }

        /** 区间文本，用于报告里展示判定依据。 */
        public String describe() {
            StringBuilder text = new StringBuilder();
            if (!lower.isEmpty() && !upper.isEmpty()) {
                text.append(lower).append(" ~ ").append(upper);
            } else if (!upper.isEmpty()) {
                text.append("<= ").append(upper);
            } else if (!lower.isEmpty()) {
                text.append(">= ").append(lower);
            } else {
                text.append("不限版本");
            }
            if (!excluded.isEmpty()) {
                text.append("（排除 ").append(join(excluded)).append("）");
            }
            return text.toString();
        }
    }

    /** 一条依赖需求：哪些坐标、什么版本区间才算满足。 */
    public static final class Requirement {
        /** 匹配的 artifactId；含 {@code *} 时按通配匹配（不区分大小写）。 */
        public final String artifactId;
        /** groupId 前缀；空串表示不限制 groupId。 */
        public final String groupPrefix;
        public final Range range;

        public Requirement(String artifactId, String groupPrefix, Range range) {
            this.artifactId = artifactId == null ? "" : artifactId.trim().toLowerCase(Locale.ROOT);
            this.groupPrefix = groupPrefix == null ? "" : groupPrefix.trim().toLowerCase(Locale.ROOT);
            this.range = range == null ? Range.any() : range;
        }

        public Requirement(String artifactId, Range range) {
            this(artifactId, "", range);
        }

        public Requirement(String artifactId) {
            this(artifactId, "", Range.any());
        }

        /**
         * 指定 groupId 前缀与任意版本。
         *
         * <p>用静态工厂而不是再加重载构造器：{@code (String, String)} 与
         * {@code (String, Range)} 在调用点上无法一眼区分，写错一个参数就会静默
         * 变成「按 artifactId 精确匹配、groupId 不限」，而结论看起来完全正常。
         */
        public static Requirement in(String artifactId, String groupPrefix) {
            return new Requirement(artifactId, groupPrefix, Range.any());
        }

        /** 这份依赖是否满足本需求（坐标 + 版本区间）。 */
        public boolean satisfied(Dependency dependency) {
            if (dependency == null) return false;
            String artifact = dependency.artifactId.toLowerCase(Locale.ROOT);
            if (artifact.isEmpty()) return false;
            if (!artifactIdWildcards() && !artifact.equals(artifactId)) return false;
            if (artifactIdWildcards() && !glob(artifactId, artifact)) return false;
            if (!groupPrefix.isEmpty()) {
                String group = dependency.groupId.toLowerCase(Locale.ROOT);
                // groupId 缺失（只有文件名来源）时放行：宁可多报一条候选，
                // 也不要因为打包工具的元数据缺失而漏掉一条真实可用的链。
                // 可信度由报告里的来源列自行判断。
                if (!group.isEmpty() && !group.startsWith(groupPrefix)) return false;
            }
            if (!dependency.hasVersion()) {
                // 不确定版本时只在「不限版本」的需求上放行
                return range.lower.isEmpty() && range.upper.isEmpty();
            }
            return range.contains(dependency.version);
        }

        private boolean artifactIdWildcards() {
            return artifactId.indexOf('*') >= 0;
        }

        /** 展示文本：{@code com.alibaba:fastjson <= 1.2.24}。 */
        public String describe() {
            StringBuilder text = new StringBuilder();
            if (!groupPrefix.isEmpty()) text.append(groupPrefix).append(":");
            text.append(artifactId.isEmpty() ? "(未知)" : artifactId);
            text.append(" ").append(range.describe());
            return text.toString();
        }
    }

    public final String id;
    public final String category;
    /** 展示名。 */
    public final String name;
    /** 必须**同时满足**的依赖需求（AND 语义）。 */
    public final List<Requirement> requires;
    /** 满足后能做什么。 */
    public final String capability;
    /** 在本工具里接着做什么。 */
    public final String suggestion;
    /** 建议动作对应的功能页 key；为空表示只在报告里提示。 */
    public final String navKey;

    public GadgetRule(String id, String category, String name, List<Requirement> requires,
                      String capability, String suggestion, String navKey) {
        this.id = id == null ? "" : id;
        this.category = category == null ? "" : category;
        this.name = name == null ? "" : name;
        this.requires = Collections.unmodifiableList(new ArrayList<Requirement>(
                requires == null ? Collections.<Requirement>emptyList() : requires));
        this.capability = capability == null ? "" : capability;
        this.suggestion = suggestion == null ? "" : suggestion;
        this.navKey = navKey == null ? "" : navKey;
    }

    /** 便捷构造：单依赖规则。 */
    public static GadgetRule of(String id, String category, String name, Requirement requirement,
                                String capability, String suggestion, String navKey) {
        return new GadgetRule(id, category, name, Arrays.asList(requirement),
                capability, suggestion, navKey);
    }

    /** 全部依赖需求是否都被满足。 */
    public boolean satisfiedBy(java.util.Collection<Dependency> dependencies) {
        return missing(dependencies).isEmpty();
    }

    /**
     * 逐条核对需求，返回**没有被满足**的展示文本。
     *
     * <p>空列表表示这条链具备；非空列表既用于判定，也直接展示给使用者
     * （「还差 commons-collections4」比「链不可用」有用得多）。
     */
    public List<String> missing(java.util.Collection<Dependency> dependencies) {
        List<String> absent = new ArrayList<String>();
        for (Requirement requirement : requires) {
            boolean found = false;
            if (dependencies != null) {
                for (Dependency dependency : dependencies) {
                    if (requirement.satisfied(dependency)) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) absent.add(requirement.describe());
        }
        return absent;
    }

    /** 逐条找出满足需求的依赖，作为报告里的依据。 */
    public List<Dependency> providers(java.util.Collection<Dependency> dependencies) {
        List<Dependency> found = new ArrayList<Dependency>();
        if (dependencies == null) return found;
        for (Requirement requirement : requires) {
            for (Dependency dependency : dependencies) {
                if (requirement.satisfied(dependency)) {
                    found.add(dependency);
                    break;
                }
            }
        }
        return found;
    }

    /** 通配匹配：{@code *} 在模式里表示任意长度；不区分大小写。这里是 glob，不是正则。 */
    static boolean glob(String pattern, String text) {
        if (pattern.isEmpty()) return false;
        int patternIndex = 0;
        int textIndex = 0;
        int star = -1;
        int resume = 0;
        while (textIndex < text.length()) {
            if (patternIndex < pattern.length()
                    && (pattern.charAt(patternIndex) == text.charAt(textIndex))) {
                patternIndex++;
                textIndex++;
            } else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                star = patternIndex;
                resume = textIndex;
                patternIndex++;
            } else if (star >= 0) {
                patternIndex = star + 1;
                resume++;
                textIndex = resume;
            } else {
                return false;
            }
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') patternIndex++;
        return patternIndex == pattern.length();
    }

    static String join(List<String> values) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) text.append("、");
            text.append(values.get(index));
        }
        return text.toString();
    }
}