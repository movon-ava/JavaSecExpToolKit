package ui;

import java.util.ArrayList;
import java.util.List;

import payload.PayloadEngine;


/**
 * 把当前链换算成列式选择器要的数据。
 *
 * <p>从 {@link PayloadController} 拆出来独立成类，原因是这一块是「链 → 每列候选」的纯换算，
 * 不碰控件也不持状态；控制器只负责把结果交给选择器渲染。候选集合只来自引擎
 * （{@code firstNodes} / {@code nextNodes}），界面不维护任何节点关系表。
 */
final class PayloadColumns {

    private PayloadColumns() {
    }

    /**
     * 把当前链换算成列：第 0 列是载体目录，第 K 列（K≥1）是第 K 项的可选后继。
     *
     * <p>末端没有后继时不再新增列——多出一列空列表会让人以为「还没加载出来」；
     * 带 END 标签的节点同样不展开，引擎已声明它之后没有可接节点。
     */
    static List<ChainColumn> of(ChainEditor editor) {
        List<ChainColumn> columns = new ArrayList<ChainColumn>();
        List<String> payloads = PayloadEngine.payloadIds();
        columns.add(column("载体 / Payload", payloads, editor.head()));
        List<String> chain = editor.snapshot();
        for (int index = 1; index < chain.size(); index++) {
            // 第 1 级节点的候选不能查「后继」：实测载体自身没有后继，
            // 首节点是按「载体 + 候选」逐个校验出来的（与 ChainEditor 同一判据）。
            List<String> options = index == 1
                    ? PayloadEngine.firstNodes(chain.get(0))
                    : PayloadEngine.nextNodes(chain.get(index - 1));
            columns.add(column("Gadget" + index, options, chain.get(index)));
        }
        List<String> candidates = editor.candidates();
        if (!candidates.isEmpty() && !editor.atEnd()) {
            int level = Math.max(1, chain.size());
            columns.add(column("Gadget" + level, candidates, ""));
        }
        return columns;
    }

    /** 组一列的展示数据：显示名、标签与末端标记全部取自引擎元数据。 */
    private static ChainColumn column(String title, List<String> ids, String selected) {
        List<String> labels = new ArrayList<String>();
        List<String> tags = new ArrayList<String>();
        List<Boolean> ends = new ArrayList<Boolean>();
        for (String id : ids) {
            payload.NodeInfo info = PayloadEngine.nodeInfo(id);
            labels.add(info.display());
            tags.add(join(info.tags));
            ends.add(Boolean.valueOf(info.end));
        }
        return new ChainColumn(title, ids, labels, tags, ends,
                PayloadEngine.tagsOf(ids), selected);
    }

    private static String join(List<String> values) {
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            if (text.length() > 0) text.append(", ");
            text.append(value);
        }
        return text.toString();
    }

    /** 单个节点的可读名，取不到时回退成标识。 */
    static String label(String nodeId) {
        String name = PayloadEngine.nodeLabel(nodeId);
        return name == null || name.trim().isEmpty() ? nodeId : name;
    }
}

