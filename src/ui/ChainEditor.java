package ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.vulhub.javachains.common.GadgetParam;

import payload.PayloadEngine;

/**
 * 利用链的编辑状态：载体选择、逐级追加、可选后继与参数行。
 *
 * <p>Payload 生成页与恶意服务器页都需要「选载体 → 追加节点 → 配参数」这套交互。
 * 状态若各自维护一份，两边对「载体算不算链的一部分」「末节点能否删除」这类规则
 * 迟早会出现不一致。因此这里把状态与规则收在一处，两个页面只负责把控件接上来。
 *
 * <p>本类不持有任何 Swing 控件：控件的读写由调用方完成，
 * 这样同一份状态可以适配两个结构不同的页面。
 */
public final class ChainEditor {

    /** 当前链：第 0 个元素是载体 id，其后依次是 gadget 节点。 */
    private final List<String> chain = new ArrayList<String>();

    /** 换载体即重开一条链：载体不同，可用节点必然不同。 */
    public void reset(String payloadId) {
        chain.clear();
        if (payloadId != null && !payloadId.trim().isEmpty()) chain.add(payloadId.trim());
    }

    /** 追加一个节点；节点为空时返回 false（调用方据此提示）。 */
    public boolean append(String nodeId) {
        if (nodeId == null || nodeId.trim().isEmpty()) return false;
        if (chain.isEmpty()) return false;
        chain.add(nodeId.trim());
        return true;
    }

    /**
     * 删除末节点。
     *
     * <p>载体不能被移除：没有载体的链不成立，少了载体只会得到一句引擎报错，
     * 不如直接拒绝并让调用方提示改选载体。
     */
    public boolean removeLast() {
        if (chain.size() <= 1) return false;
        chain.remove(chain.size() - 1);
        return true;
    }

    /** 链是否已具备可生成的最小长度（载体 + 至少一个节点）。 */
    public boolean isBuildable() {
        return chain.size() > 1;
    }

    public boolean isEmpty() {
        return chain.isEmpty();
    }

    public String head() {
        return chain.isEmpty() ? "" : chain.get(0);
    }

    public String tail() {
        return chain.isEmpty() ? "" : chain.get(chain.size() - 1);
    }

    /** 追加节点序列（不含载体），供引擎调用。 */
    public List<String> gadgets() {
        return chain.size() <= 1
                ? new ArrayList<String>() : new ArrayList<String>(chain.subList(1, chain.size()));
    }

    /** 只读的完整链快照。 */
    public List<String> snapshot() {
        return new ArrayList<String>(chain);
    }

    public int nodeCount() {
        return Math.max(0, chain.size() - 1);
    }

    /**
     * 当前可追加的候选节点。
     *
     * <p>载体之后的首节点不能查「后继」：实测载体自身没有后继，
     * 必须按「载体 + 候选节点」逐个校验合法性，这层判据在
     * {@link PayloadEngine#firstNodes} 里。
     */
    public List<String> candidates() {
        if (chain.isEmpty()) return new ArrayList<String>();
        return chain.size() <= 1 ? PayloadEngine.firstNodes(head()) : PayloadEngine.nextNodes(tail());
    }

    /** 链的可读表示，用于界面上的一行预览。 */
    public String display() {
        if (chain.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < chain.size(); index++) {
            if (index > 0) text.append(" -> ");
            text.append(chain.get(index));
        }
        return text.toString();
    }

    /**
     * 按链上全部节点构造参数行。
     *
     * <p>载体与每个 gadget 的参数都会被收集：Shiro 密钥、Fastjson 选项这类参数
     * 挂在载体上，命令、URL 这类挂在末端节点上，少收集任何一侧都会漏掉必填项。
     */
    public List<PayloadPage.ParamField> paramFields() {
        List<PayloadPage.ParamField> fields = new ArrayList<PayloadPage.ParamField>();
        for (String node : chain) {
            for (GadgetParam param : PayloadEngine.paramsOf(node)) {
                String key = param.getKey();
                if (key == null || key.trim().isEmpty()) continue;
                if (containsKey(fields, key)) continue;
                String label = param.getName() == null || param.getName().trim().isEmpty()
                        ? key : param.getName();
                fields.add(new PayloadPage.ParamField(
                        key, label, describe(param), toChoices(param), Boolean.TRUE.equals(param.getRequired())));
            }
        }
        return fields;
    }

    /**
     * 当前可追加的候选节点，按标签收敛。
     *
     * <p>标签筛选放在编辑器而不是界面：载荷生成页与恶意服务器页都从候选里挑节点，
     * 两边各写一套筛选会让「同一个链在两个页面里候选不一致」。
     *
     * @param tags 选中的标签；为空表示不过滤
     */
    public List<String> candidates(List<String> tags) {
        if (tags == null || tags.isEmpty()) return candidates();
        List<String> filtered = new ArrayList<String>();
        for (String node : candidates()) {
            if (payload.PayloadEngine.filterByTags(java.util.Collections.singletonList(node), tags)
                    .contains(node)) {
                filtered.add(node);
            }
        }
        return filtered;
    }

    /** 当前末节点是否为末端节点：是的话不再有可追加的候选。 */
    public boolean atEnd() {
        return !chain.isEmpty() && payload.PayloadEngine.isEndNode(tail());
    }

    /** 从已渲染出的控件读回参数值，键为引擎认识的完整形式。 */
    public static Map<String, String> readValues(List<PayloadPage.ParamField> fields) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (fields == null) return values;
        for (PayloadPage.ParamField field : fields) {
            if (field.field instanceof javax.swing.JTextField) {
                values.put(field.key, ((javax.swing.JTextField) field.field).getText().trim());
            } else if (field.field instanceof javax.swing.JComboBox) {
                Object selected = ((javax.swing.JComboBox<?>) field.field).getSelectedItem();
                values.put(field.key, selected == null ? "" : String.valueOf(selected));
            }
        }
        return values;
    }

    /** 参数值转成引擎需要的映射：空值不传，避免覆盖引擎默认值。 */
    public static Map<String, Object> toParams(Map<String, String> values) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        if (values == null) return params;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
            params.put(entry.getKey(), entry.getValue());
        }
        return params;
    }

    private static boolean containsKey(List<PayloadPage.ParamField> fields, String key) {
        for (PayloadPage.ParamField field : fields) {
            if (field.key.equals(key)) return true;
        }
        return false;
    }

    private static String[] toChoices(GadgetParam param) {
        Map<String, String> choices = param.getChoices();
        if (choices == null || choices.isEmpty()) return new String[0];
        return choices.keySet().toArray(new String[0]);
    }

    private static String describe(GadgetParam param) {
        String description = param.getDescription();
        if (description == null) return "";
        String flat = description.replace("\r", " ").replace("\n", " ")
                .replaceAll("\\s+", " ").trim();
        return flat.length() > 48 ? flat.substring(0, 48) + "…" : flat;
    }
}
