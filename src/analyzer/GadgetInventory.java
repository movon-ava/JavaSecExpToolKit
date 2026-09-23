package analyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Gadget 可用性清单：把「识别到的依赖」翻译成「哪些链在 classpath 上齐备」。
 *
 * <p>与 {@link VulnerabilityRules} 的分工：规则表回答「这个组件本身有没有已知漏洞」，
 * 本类回答「这些依赖凑在一起，本工具里哪几条链能构建出来」。两者都是依赖口径，
 * 但前者判的是组件自身，后者判的是组件之间的组合能力。
 *
 * <p>判定口径是 <b>Maven 坐标 + 版本区间</b>，不是 jar 文件名：文件名既不带 groupId，
 * 也无法表达「这个版本已经打过补丁」。上游 {@code jar-analyzer} 的 gadget 分析只按
 * 文件名是否出现来判定，同名不同源、已修复版本都会被算成可用，
 * 这正是本项目在其思路上做的扩展（见 {@link GadgetRule} 的类注释）。
 *
 * <p>规则来源有三处，按优先级合并：
 * <ol>
 *   <li>内置规则表 {@link GadgetRules}；</li>
 *   <li>使用者通过配置页指定的外部规则文件 {@link GadgetRuleFile}（下一轮接入界面）；</li>
 *   <li>同目录下与内置规则同 id 的外部规则，覆盖内置规则（便于临时改判）。</li>
 * </ol>
 *
 * <p>结论边界必须说清：依赖口径只能证明「gadget 在 classpath 上」，
 * **不能证明它可达**。是否可达取决于有没有反序列化入口、入口的参数能否被外部控制，
 * 那要靠调用链分析（Sink 命中 + 特征匹配）继续往上追。
 */
public final class GadgetInventory {

    /** 一条判定结果：规则 + 是否具备 + 提供它的依赖（具备时非空）。 */
    public static final class Entry {
        public final GadgetRule rule;
        public final boolean available;
        /** 满足全部依赖需求的那些依赖；具备时非空。 */
        public final List<Dependency> providers;
        /** 未满足的依赖需求描述；具备时为空。 */
        public final List<String> missing;

        Entry(GadgetRule rule, boolean available, List<Dependency> providers, List<String> missing) {
            this.rule = rule;
            this.available = available;
            this.providers = Collections.unmodifiableList(providers);
            this.missing = Collections.unmodifiableList(missing);
        }

        /** 判定依据：命中的坐标，形如 {@code com.alibaba:fastjson:1.2.24}。 */
        public String evidence() {
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < providers.size(); index++) {
                if (index > 0) text.append(" + ");
                text.append(providers.get(index).coordinate());
            }
            return text.toString();
        }
    }

    private GadgetInventory() {
    }

    /** 全部内置规则条目，只读。 */
    public static List<GadgetRule> rules() {
        return GadgetRules.all();
    }

    /** 规则条数。 */
    public static int ruleCount() {
        return GadgetRules.count();
    }

    /** 按依赖清单判定每条规则（只按内置规则表）。 */
    public static List<Entry> inspect(java.util.Collection<Dependency> dependencies) {
        return inspect(dependencies, Collections.<GadgetRule>emptyList());
    }

    /**
     * 按依赖清单判定每条规则。
     *
     * @param extraRules 外部规则；与内置规则同 id 时覆盖内置规则
     */
    public static List<Entry> inspect(java.util.Collection<Dependency> dependencies,
                                      java.util.List<GadgetRule> extraRules) {
        List<Entry> entries = new ArrayList<Entry>();
        for (GadgetRule rule : merged(extraRules)) {
            List<String> missing = rule.missing(dependencies);
            entries.add(new Entry(rule, missing.isEmpty(), rule.providers(dependencies), missing));
        }
        return entries;
    }

    /** 合并内置与外部规则；外部同 id 覆盖内置，额外规则追加在后。 */
    /**
     * 合并内置与外部规则（对包外可见：自检要验证「同 id 覆盖」这条语义）。
     */
    public static List<GadgetRule> merged(java.util.List<GadgetRule> extraRules) {
        if (extraRules == null || extraRules.isEmpty()) return GadgetRules.all();
        List<GadgetRule> merged = new ArrayList<GadgetRule>();
        for (GadgetRule builtin : GadgetRules.all()) {
            GadgetRule replacement = null;
            for (GadgetRule extra : extraRules) {
                if (extra.id.equalsIgnoreCase(builtin.id)) {
                    replacement = extra;
                    break;
                }
            }
            merged.add(replacement == null ? builtin : replacement);
        }
        for (GadgetRule extra : extraRules) {
            if (GadgetRules.of(extra.id) == null) merged.add(extra);
        }
        return merged;
    }

    /** 具备的 gadget 条数。 */
    public static int availableCount(java.util.Collection<Dependency> dependencies) {
        int count = 0;
        for (Entry entry : inspect(dependencies)) {
            if (entry.available) count++;
        }
        return count;
    }

    /**
     * 渲染 gadget 段。
     *
     * <p>缺失项也要列出来：使用者需要知道「哪些链在这份依赖上根本不可用、还差哪个组件」，
     * 否则会在「Payload 生成」里反复试一条注定构造不出来的链。
     * 按类型分组，同一类别的可用链排在一起，读的人先看到最可能用得上的那一档。
     */
    public static String render(java.util.Collection<Dependency> dependencies) {
        return render(dependencies, Collections.<GadgetRule>emptyList());
    }

    /** 渲染 gadget 段，支持外部规则。 */
    public static String render(java.util.Collection<Dependency> dependencies,
                                java.util.List<GadgetRule> extraRules) {
        List<Entry> entries = inspect(dependencies, extraRules);
        int available = 0;
        for (Entry entry : entries) {
            if (entry.available) available++;
        }
        String newline = System.lineSeparator();
        StringBuilder text = new StringBuilder();
        text.append("===== 可用 gadget（依赖口径）=====").append(newline);
        text.append("  具备 ").append(available).append(" 项／共 ").append(entries.size())
                .append(" 项").append(newline);

        List<String> categories = new ArrayList<String>();
        for (Entry entry : entries) {
            if (!categories.contains(entry.rule.category)) categories.add(entry.rule.category);
        }
        for (String category : categories) {
            boolean header = false;
            for (Entry entry : entries) {
                if (!entry.available || !category.equals(entry.rule.category)) continue;
                if (!header) {
                    text.append(newline).append("-- ").append(category).append(" --").append(newline);
                    header = true;
                }
                text.append("[可用] ").append(entry.rule.name).append(newline);
                text.append("    依据: ").append(entry.evidence()).append(newline);
                text.append("    能力: ").append(entry.rule.capability).append(newline);
                text.append("    下一步: ").append(entry.rule.suggestion).append(newline);
                if (!entry.rule.navKey.isEmpty()) {
                    text.append("    直达: ").append(entry.rule.navKey).append(newline);
                }
            }
        }

        text.append(newline).append("-- 缺失（补齐后可用）--").append(newline);
        boolean anyMissing = false;
        for (Entry entry : entries) {
            if (entry.available) continue;
            anyMissing = true;
            text.append("[缺失] ").append(entry.rule.name)
                    .append("　需要 ").append(GadgetRule.join(entry.missing)).append(newline);
        }
        if (!anyMissing) {
            text.append("  内置规则全部具备。").append(newline);
        }

        text.append(newline);
        text.append("注意：以上只说明「gadget 在 classpath 上」，不等于可达。")
                .append("是否可达取决于是否存在反序列化入口、入口参数能否被外部控制——")
                .append("到「调用链查询」看 Sink 命中与漏洞特征匹配，沿调用方继续往上追。")
                .append(newline);
        return text.toString();
    }

    /** 一句话摘要，供界面状态行与自检使用。 */
    public static String summary(java.util.Collection<Dependency> dependencies) {
        int available = availableCount(dependencies);
        return "可用 gadget " + available + " 项／共 " + GadgetRules.count() + " 项";
    }
}