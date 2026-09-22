package analyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 版本号比较：只服务于「这个版本是否落在受影响区间」这一个问题。
 *
 * <p>不能用字符串比较：{@code "1.2.80" < "1.2.9"} 在字典序下为真，
 * 而 fastjson 的受影响区间恰恰跨过这两个值，用字符串比较会把 1.2.80
 * 判成「低于 1.2.9」，结论完全反过来。因此按段拆开逐段数值比较。
 *
 * <p>无法解析的版本一律返回「不可比」，调用方据此给出「版本无法判定」而不是猜一个。
 */
public final class Version implements Comparable<Version> {

    /** 拆好的段：数字段按数值比较，其余段按不区分大小写的字典序比较。 */
    private final List<Object> segments = new ArrayList<Object>();
    private final String raw;

    private Version(String raw) {
        this.raw = raw == null ? "" : raw.trim();
        for (String part : this.raw.split("[.\\-_+]")) {
            if (part.isEmpty()) continue;
            if (part.matches("\\d+")) {
                try {
                    segments.add(Long.valueOf(Long.parseLong(part)));
                    continue;
                } catch (NumberFormatException overflow) {
                    // 段长到溢出：当字符串处理，避免整条版本变得不可比
                }
            }
            segments.add(part.toLowerCase(Locale.ROOT));
        }
    }

    /** 解析版本；内容为空时返回 null，调用方走「版本未知」分支。 */
    public static Version parse(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        Version version = new Version(text);
        return version.segments.isEmpty() ? null : version;
    }

    /** 比较两个版本；任一方为 null 返回 null 表示不可比。 */
    public static Integer compareNullable(String left, String right) {
        Version a = parse(left);
        Version b = parse(right);
        if (a == null || b == null) return null;
        return Integer.valueOf(a.compareTo(b));
    }

    /**
     * 段数不足时用的哨兵。
     *
     * <p>不能简单按数字 {@code 0} 补齐：{@code 1.0-rc1} 拆出三段，{@code 1.0} 只有两段，
     * 若把缺失段补成 0，就会拿 {@code "rc1"} 与数字 {@code 0} 相比，
     * 结果是「预发布版大于正式版」——版本判定的结论会完全反过来。
     * 缺失段的真实语义是「此处是正式发布」，它大于任何预发布标记。
     */
    private static final Object MISSING = new Object();

    @Override
    public int compareTo(Version other) {
        int size = Math.max(segments.size(), other.segments.size());
        for (int index = 0; index < size; index++) {
            Object mine = index < segments.size() ? segments.get(index) : MISSING;
            Object theirs = index < other.segments.size() ? other.segments.get(index) : MISSING;
            int compared = compareSegment(mine, theirs);
            if (compared != 0) return compared;
        }
        return 0;
    }

    /**
     * 逐段比较。
     *
     * <p>规则有三条，都是为了贴近「版本号」而不是「字符串」的语义：
     * 数字段之间按数值比（1.2.80 &gt; 1.2.9）；数字段与预发布标记相遇时数字段更大
     * （1.0 &gt; 1.0-rc1）；两侧缺段相同则相等，一侧缺段时按「正式发布」处理。
     */
    private static int compareSegment(Object mine, Object theirs) {
        if (mine == MISSING && theirs == MISSING) return 0;
        if (mine == MISSING || theirs == MISSING) {
            Object present = mine == MISSING ? theirs : mine;
            // 缺段一侧是「正式发布」，因此有预发布标记的一侧更小：
            // mine 缺段 → mine 更大（+1）；theirs 缺段 → mine 更小（-1）
            int sign = mine == MISSING ? 1 : -1;
            if (present instanceof Long) {
                // 缺段按数值 0 参与比较：1.0 等于 1.0.0，但小于 1.0.1
                return sign * Long.valueOf(0L).compareTo((Long) present);
            }
            // 缺段是「正式发布」，大于任何预发布标记
            return sign;
        }
        boolean myNumber = mine instanceof Long;
        boolean theirNumber = theirs instanceof Long;
        if (myNumber && theirNumber) {
            return ((Long) mine).compareTo((Long) theirs);
        }
        if (myNumber != theirNumber) return myNumber ? 1 : -1;
        String myText = (String) mine;
        String theirText = (String) theirs;
        // 常见预发布后缀排序：alpha < beta < milestone < rc < snapshot < release
        int rank = Integer.compare(preReleaseRank(myText), preReleaseRank(theirText));
        if (rank != 0) return rank;
        return myText.compareTo(theirText);
    }

    private static int preReleaseRank(String segment) {
        String lower = segment.toLowerCase(Locale.ROOT);
        if (lower.startsWith("alpha") || lower.startsWith("a")) return 0;
        if (lower.startsWith("beta") || lower.startsWith("b")) return 1;
        if (lower.startsWith("milestone") || lower.startsWith("m")) return 2;
        if (lower.startsWith("rc") || lower.startsWith("cr")) return 3;
        if (lower.startsWith("snapshot")) return 4;
        if (lower.startsWith("release") || lower.startsWith("final") || lower.startsWith("ga")) return 6;
        return 5;
    }

    @Override
    public String toString() {
        return raw;
    }
}
