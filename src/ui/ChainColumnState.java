package ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 每列的筛选状态：关键字、已选标签与标签匹配方式。
 *
 * <p>从 {@link PayloadChainSelector} 拆出来独立成类，原因是这一块是「数据」而不是「控件」：
 * 选择器只负责把状态渲染成过滤框与标签按钮，状态本身（哪列筛了什么、按交集还是并集）
 * 与列构建、渲染、事件处理无关，单独放着才看得清「过滤到底记了哪些东西」。
 *
 * <p>三个清单都按列下标对齐：列数变化时由 {@link #resize} 一次对齐，
 * 已有的列保留下标与内容——换载体后同一列位置上的标签筛选不该被静默清掉。
 */
final class ChainColumnState {

    private final List<String> filters = new ArrayList<String>();
    private final List<List<String>> tagFilters = new ArrayList<List<String>>();
    private final List<Boolean> tagIntersect = new ArrayList<Boolean>();

    /** 列数变化时对齐三个清单的长度。 */
    void resize(int columnCount) {
        while (filters.size() < columnCount) filters.add("");
        while (filters.size() > columnCount) filters.remove(filters.size() - 1);
        while (tagFilters.size() < columnCount) tagFilters.add(new ArrayList<String>());
        while (tagFilters.size() > columnCount) tagFilters.remove(tagFilters.size() - 1);
        while (tagIntersect.size() < columnCount) tagIntersect.add(Boolean.FALSE);
        while (tagIntersect.size() > columnCount) tagIntersect.remove(tagIntersect.size() - 1);
    }

    /** 第 index 列的过滤关键字；越界返回空串。 */
    String filter(int index) {
        return index < 0 || index >= filters.size() ? "" : filters.get(index);
    }

    /** 设置第 index 列的过滤关键字。 */
    void setFilter(int index, String text) {
        if (index < 0 || index >= filters.size()) return;
        filters.set(index, text == null ? "" : text);
    }

    /**
     * 第 index 列已选标签的活引用。
     *
     * <p>刻意返回引用而不是副本：标签菜单的勾选项直接在这个清单上增删，再回调刷新。
     * 返回副本会让菜单的改动落空，两处各存一份又必然漂移，因此只此一份。
     */
    List<String> chosenTags(int index) {
        return index < 0 || index >= tagFilters.size() ? new ArrayList<String>() : tagFilters.get(index);
    }

    /** 第 index 列已选标签的副本，供自检核对。 */
    List<String> tags(int index) {
        return new ArrayList<String>(chosenTags(index));
    }

    /** 整列替换第 index 列的标签筛选，不做叠加。 */
    void setTags(int index, List<String> tags) {
        if (index < 0 || index >= tagFilters.size()) return;
        List<String> target = tagFilters.get(index);
        target.clear();
        if (tags == null) return;
        for (String tag : tags) {
            if (tag != null && !tag.trim().isEmpty()) target.add(tag.trim());
        }
    }

    /** 第 index 列是否按交集匹配；越界返回 false（并集）。 */
    boolean intersect(int index) {
        return index >= 0 && index < tagIntersect.size() && Boolean.TRUE.equals(tagIntersect.get(index));
    }

    /** 设置第 index 列的标签匹配方式；返回是否真的发生了变化。 */
    boolean setIntersect(int index, boolean value) {
        if (index < 0 || index >= tagIntersect.size()) return false;
        if (Boolean.valueOf(value).equals(tagIntersect.get(index))) return false;
        tagIntersect.set(index, Boolean.valueOf(value));
        return true;
    }

    /** 标签筛选按钮的文字：未选标签时提示可筛，选了就给出数量与匹配方式。 */
    String tagSummary(int index) {
        List<String> chosen = chosenTags(index);
        if (chosen.isEmpty()) return "标签 \u25be";
        return "标签 " + chosen.size() + (intersect(index) ? " \u2229" : " \u222a") + " \u25be";
    }

    /** 取第 index 列按当前筛选条件保留的行。 */
    List<Integer> rows(int index, ChainColumn data) {
        return ChainColumnFilter.rows(data, filter(index), chosenTags(index), intersect(index));
    }
}
