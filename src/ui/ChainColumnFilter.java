package ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 列式选链的候选过滤：关键字与标签两种收敛方式，以及它们的匹配判据。
 *
 * <p>从 {@link PayloadChainSelector} 拆出来独立成类，原因是这部分不碰控件、不持状态，
 * 只是「给定一列候选与筛选条件，算出保留哪些项」。放在选择器里会和列构建、渲染、
 * 事件处理混在一起，而它恰恰是最需要单独读清楚的一段判定逻辑。
 *
 * <p>两种过滤是「与」的关系：先按标签收敛范围，再按关键字收敛。
 */
final class ChainColumnFilter {

    private ChainColumnFilter() {
    }

    /**
     * 按关键字 + 标签过滤一列，返回保留项在原始值序列中的下标。
     *
     * <p>当前选中项无条件保留：它与筛选条件不匹配时若被过滤掉，
     * 使用者会看到「链上有这一项、列表里却没有」，进而以为链坏了。
     */
    static List<Integer> rows(ChainColumn data, String keyword, List<String> tags,
                              boolean intersect) {
        List<Integer> rows = new ArrayList<Integer>();
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        boolean tagFilter = tags != null && !tags.isEmpty();
        for (int index = 0; index < data.values.size(); index++) {
            String value = data.values.get(index);
            if (value == null || value.trim().isEmpty()) continue;
            if (tagFilter && !matchesTags(data, index, tags, intersect)) continue;
            if (needle.isEmpty() || matches(data, index, needle)) rows.add(Integer.valueOf(index));
        }
        int selected = indexOf(data.values, data.selected);
        if (selected >= 0 && !rows.contains(Integer.valueOf(selected))) {
            rows.add(0, Integer.valueOf(selected));
        }
        return rows;
    }

    /**
     * 第 index 项是否命中标签筛选。
     *
     * <p>并集只要命中一个即可，交集要求全部命中；标签串为空的项在两种模式下都不算命中
     * （否则「按标签筛选」会把没有标签的项全部放行，筛选等于没做）。
     */
    static boolean matchesTags(ChainColumn data, int index, List<String> tags,
                               boolean intersect) {
        String raw = data.tagAt(index);
        if (raw == null || raw.trim().isEmpty()) return false;
        boolean sawAny = false;
        for (String tag : tags) {
            if (tag == null || tag.trim().isEmpty()) continue;
            boolean hit = containsTag(raw, tag.trim());
            if (hit) {
                sawAny = true;
                if (!intersect) return true;
            } else if (intersect) {
                return false;
            }
        }
        return intersect ? sawAny : false;
    }

    /** 标签串里按逗号分隔查找，避免 "Test" 命中 "Testing" 这类子串误判。 */
    static boolean containsTag(String raw, String wanted) {
        for (String piece : raw.split(",")) {
            if (piece.trim().equalsIgnoreCase(wanted)) return true;
        }
        return false;
    }

    static int indexOf(List<String> values, String wanted) {
        if (wanted == null || wanted.isEmpty()) return -1;
        for (int index = 0; index < values.size(); index++) {
            if (wanted.equals(values.get(index))) return index;
        }
        return -1;
    }

    /** 关键字匹配：值与显示名任一命中即可，便于按中文名或英文标识查找。 */
    static boolean matches(ChainColumn data, int index, String needle) {
        String value = data.values.get(index);
        String label = label(data, index);
        return (value != null && value.toLowerCase(Locale.ROOT).contains(needle))
                || (label != null && label.toLowerCase(Locale.ROOT).contains(needle));
    }

    static String label(ChainColumn data, int index) {
        if (index < 0 || index >= data.values.size()) return "";
        String value = data.values.get(index);
        String label = index < data.labels.size() ? data.labels.get(index) : null;
        // 上游实测有 3 个节点没有显示名：回退成标识，不能让列里出现空行
        return label == null || label.trim().isEmpty() ? value : label;
    }
}
