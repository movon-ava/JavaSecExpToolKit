package payload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个节点（载体或 gadget）的只读元信息：显示名、标签、别名、依赖与适用模式。
 *
 * <p>界面要在候选列表里给出标签徽标（网页版每个候选右侧的 Tag）、按标签过滤候选、
 * 标出末端节点（END），这些信息全部来自 java-chains 自己的元数据。本类只做搬运，
 * 不推断任何上游没有给出的结论——推断出来的标签会与引擎的实际判据不一致。
 */
public final class NodeInfo {

    /** 节点标识，与引擎注册的 id 一致。 */
    public final String id;
    /** 可读显示名；上游没登记时为空串。 */
    public final String label;
    /** 节点种类：payload / gadget。 */
    public final String kind;
    /** 节点说明；上游没写时为空串。 */
    public final String description;
    /** 标签，按上游给出的顺序。 */
    public final List<String> tags;
    /** 别名（cb1 这类简写），可能为空。 */
    public final List<String> aliases;
    /** 运行时依赖，例如 commons-collections:commons-collections:all。 */
    public final List<String> dependencies;
    /** 适用模式，例如 Generate / JNDI；gadget 通常为空。 */
    public final List<String> modes;
    /** 是否 payload 载体。 */
    public final boolean payload;
    /** 是否末端节点：带 END 标签的节点之后没有可接的节点。 */
    public final boolean end;

    NodeInfo(String id, String label, String kind, String description, List<String> tags,
             List<String> aliases, List<String> dependencies, List<String> modes,
             boolean payload, boolean end) {
        this.id = id == null ? "" : id;
        this.label = label == null ? "" : label;
        this.kind = kind == null ? "" : kind;
        this.description = description == null ? "" : description;
        this.tags = Collections.unmodifiableList(tags == null ? new ArrayList<String>() : tags);
        this.aliases = Collections.unmodifiableList(aliases == null ? new ArrayList<String>() : aliases);
        this.dependencies =
                Collections.unmodifiableList(dependencies == null ? new ArrayList<String>() : dependencies);
        this.modes = Collections.unmodifiableList(modes == null ? new ArrayList<String>() : modes);
        this.payload = payload;
        this.end = end;
    }

    /** 上游目录里没有这个节点时的占位信息：界面据此回退展示标识，而不是显示空白。 */
    public static NodeInfo unknown(String id) {
        return new NodeInfo(id, "", "", "", new ArrayList<String>(), new ArrayList<String>(),
                new ArrayList<String>(), new ArrayList<String>(), false, false);
    }

    /** 显示名；为空时回退成标识，保证界面不会出现空行。 */
    public String display() {
        return label.trim().isEmpty() ? id : label;
    }

    public boolean hasTag(String tag) {
        return tag != null && tags.contains(tag);
    }

    /** 标签里第一个别名式短标签，供界面在名称右侧显示一个紧凑徽标；没有则返回空串。 */
    public String shortTag() {
        return tags.isEmpty() ? "" : tags.get(0);
    }
}
