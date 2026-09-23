package analyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外部 gadget 规则文件：让「分析更多 gadget」不需要改代码。
 *
 * <p>为什么需要它：内置规则表只覆盖本工具能接上后续动作的那些链，
 * 而实际遇到的目标可能在用别的组件。如果扩展规则必须重新编译，
 * 使用者就只能干等；提供一个规则文件入口，新链可以立刻加进来。
 *
 * <p>格式沿用上游 {@code jar-analyzer} 的 {@code gadget.dat} 形态
 * ——每行 {@code jar名1,jar名2|类型|结果}，{@code #} 开头是注释，按 {@code |} 分三段。
 * 采用这个格式有两个好处：使用者手里已有的规则文件可以直接用；
 * 而且**只读格式、不读它的数据文件**，因此不涉及 GPLv3 数据的分发问题。
 *
 * <p>与上游的关键差异（也是本项目的拓展点）：jar 名里的多段式会被解析成
 * <b>artifactId + 版本区间</b>并以 Maven 坐标判定，而不是按 jar 文件名逐字比对：
 * <ul>
 *   <li>{@code commons-collections-3.2.1.jar} \u2192 artifactId {@code commons-collections}，版本 {@code <= 3.2.1}</li>
 *   <li>{@code commons-collections-!3.2.2.jar} \u2192 同上并**排除** 3.2.2（上游语法里 {@code !} 表示版本黑名单）</li>
 *   <li>{@code *-core.jar} \u2192 artifactId 通配 {@code *-core}，任意版本</li>
 *   <li>{@code commons-collections4.jar} \u2192 artifactId {@code commons-collections4}，任意版本</li>
 * </ul>
 *
 * <p>解析失败的行**逐行报告并跳过**，不静默丢弃：一条写错的规则会让使用者
 * 以为「这个 gadget 判定过了、目标没有」，而实际上它根本没被加载。
 */
public final class GadgetRuleFile {

    /** 从 jar 名尾部提取版本：{@code name-1.2.3.jar} 里的 {@code 1.2.3}。 */
    private static final Pattern TRAILING_VERSION =
            Pattern.compile("-(\\d[0-9A-Za-z._]*)$");

    private GadgetRuleFile() {
    }

    /** 解析结果：规则 + 逐行问题说明（空表示全部解析成功）。 */
    public static final class Parsed {
        public final List<GadgetRule> rules;
        /** 无法解析的行，形如「第 12 行：只剩两段」。 */
        public final List<String> problems;

        public Parsed(List<GadgetRule> rules, List<String> problems) {
            this.rules = Collections.unmodifiableList(rules);
            this.problems = Collections.unmodifiableList(problems);
        }

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /** 解析一个规则文件；文件不存在或读不了时抛 IOException，由调用方给出可读提示。 */
    public static Parsed parse(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("规则文件不存在：" + file);
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<GadgetRule> rules = new ArrayList<GadgetRule>();
        List<String> problems = new ArrayList<String>();
        int number = 0;
        for (String line : lines) {
            number++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\|");
            if (parts.length != 3) {
                problems.add("第 " + number + " 行：需要「jar名,…|类型|结果」三段，实际 "
                        + parts.length + " 段");
                continue;
            }
            List<GadgetRule.Requirement> requires = new ArrayList<GadgetRule.Requirement>();
            boolean usable = true;
            String[] jars = parts[0].split(",");
            for (String jar : jars) {
                String name = jar.trim();
                if (name.isEmpty()) continue;
                GadgetRule.Requirement requirement = requirement(name);
                if (requirement == null) {
                    problems.add("第 " + number + " 行：无法解析依赖名「" + name + "」");
                    usable = false;
                    break;
                }
                requires.add(requirement);
            }
            if (!usable) continue;
            if (requires.isEmpty()) {
                problems.add("第 " + number + " 行：没有声明任何依赖");
                continue;
            }
            String category = parts[1].trim();
            String result = parts[2].trim();
            rules.add(new GadgetRule("EXT-" + number, category,
                    result.isEmpty() ? ("外部规则 " + number) : result,
                    requires, result, "由外部规则文件声明：" + file.getFileName(), ""));
        }
        return new Parsed(rules, problems);
    }

    /**
     * 把一个依赖名解析成需求。
     *
     * <p>为什么通配只放在 artifactId 而不放在版本：版本通配（{@code 1.2.*}）
     * 需要区间语法表达，而区间已经在 {@link GadgetRule.Range} 里有了；
     * 用两个语法做同一件事，使用者必然会在注释里问「到底以哪个为准」。
     */
    static GadgetRule.Requirement requirement(String raw) {
        String name = raw.trim();
        if (name.isEmpty()) return null;
        if (name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        }
        String excluded = "";
        int bang = name.lastIndexOf('!');
        if (bang >= 0) {
            excluded = name.substring(bang + 1).trim();
            name = name.substring(0, bang).trim();
        }
        if (name.isEmpty()) return null;

        String artifact = name;
        String upper = "";
        // 带通配符时不再猜版本：`*` 与版本段在同一段名里无法可靠区分
        if (name.indexOf('*') < 0) {
            Matcher matcher = TRAILING_VERSION.matcher(name);
            if (matcher.find()) {
                upper = matcher.group(1);
                artifact = name.substring(0, name.length() - upper.length() - 1);
            }
        }
        if (artifact.isEmpty()) return null;
        String group = "";
        int colon = artifact.indexOf(':');
        if (colon >= 0) {
            group = artifact.substring(0, colon).trim();
            artifact = artifact.substring(colon + 1).trim();
        }
        if (artifact.isEmpty()) return null;
        GadgetRule.Range range = (upper.isEmpty() && excluded.isEmpty())
                ? GadgetRule.Range.any()
                : new GadgetRule.Range("", upper, excluded.isEmpty() ? new String[0]
                        : new String[]{excluded});
        return new GadgetRule.Requirement(artifact, group, range);
    }
}