package ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 列式选链里的一列：标题、候选值、候选显示名、标签与末端标记、当前选中值。
 *
 * <p>从 {@link PayloadChainSelector} 提出来独立成类，有两个原因：一是它是纯数据，
 * 不依赖任何控件，单独一个文件更容易看清「一列到底有哪些字段」；二是它作为选择器的
 * 嵌套类型时无法直接用于 {@code ListCellRenderer<String>}（泛型嵌套类型不能作类型实参）。
 */
public final class ChainColumn {

    public final String title;
    public final List<String> values;
    public final List<String> labels;
    /** 与 values 一一对应的标签（逗号分隔展示用），可为空。 */
    public final List<String> tags;
    /** 与 values 一一对应的末端标记。 */
    public final List<Boolean> ends;
    /** 本列候选出现过的全部标签，供标签筛选器列出可选项。 */
    public final List<String> availableTags;
    public final String selected;

    public ChainColumn(String title, List<String> values, List<String> labels, String selected) {
        this(title, values, labels, null, null, null, selected);
    }

    public ChainColumn(String title, List<String> values, List<String> labels, List<String> tags,
                       List<Boolean> ends, List<String> availableTags, String selected) {
        this.title = title;
        this.values = values == null ? new ArrayList<String>() : values;
        this.labels = labels == null ? new ArrayList<String>() : labels;
        this.tags = tags == null ? new ArrayList<String>() : tags;
        this.ends = ends == null ? new ArrayList<Boolean>() : ends;
        this.availableTags = availableTags == null ? new ArrayList<String>() : availableTags;
        this.selected = selected;
    }

    /** 第 index 项的标签文本；没有标签时为空串。 */
    public String tagAt(int index) {
        return index >= 0 && index < tags.size() && tags.get(index) != null ? tags.get(index) : "";
    }

    /** 第 index 项是否为末端节点。 */
    public boolean endAt(int index) {
        return index >= 0 && index < ends.size() && Boolean.TRUE.equals(ends.get(index));
    }
}
