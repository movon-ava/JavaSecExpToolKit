package ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 侧边栏的一个导航项：一级分类可以带若干子功能。
 *
 * <p>展开状态属于每个分组自己，而不是整棵树的全局开关——同时展开「代理」与
 * 「Shiro」时，收起其中一个不能让另一个的子项跟着消失。
 */
public final class NavItem {

    public final String key;
    public final String label;
    public final List<NavItem> children;
    public NavItem parent;
    public boolean expanded;

    public NavItem(String key, String label) {
        this(key, label, Collections.<NavItem>emptyList());
    }

    public NavItem(String key, String label, List<NavItem> children) {
        this.key = key;
        this.label = label;
        this.children = new ArrayList<NavItem>(children);
        for (NavItem child : this.children) child.parent = this;
    }

    /** 缩进取决于层级而不是 key 文案：子项 key 未必带点号。 */
    public boolean isChild() {
        return parent != null;
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    /** 纯文本表示：箭头在文字右侧，与界面渲染保持一致。 */
    public String displayLabel() {
        return hasChildren() ? label + (expanded ? "  \u25be" : "  \u25b8") : label;
    }
}

