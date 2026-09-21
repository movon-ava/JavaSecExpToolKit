package ui;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import payload.PayloadEngine;
import payload.PayloadResult;

/**
 * Payload 生成页的行为：链状态 + 引擎调用 + 结果复制 / 导出 / 转交。
 *
 * <p>选链走列式交互：控制器把当前链换算成「每列展示什么」，交给
 * {@link PayloadChainSelector} 渲染；使用者点某一列的候选时，
 * 控制器按该列截断链并重算后续列（等价于网页版 Generate 的「点第 N 列重开」）。
 *
 * <p>链的权威仍是 {@link ChainEditor}：本类不另存候选，每次改链都从编辑器现算，
 * 避免与恶意服务器页（同样使用该编辑器）出现两套规则。
 *
 * <p>本类不 new 控件：控件由界面层持有并在每次进页时复用，
 * 因此反复进出页面不会累积状态（与 Shiro / 抓包页的既有做法一致）。
 */
public final class PayloadController implements ActionListener, PayloadChainSelector.SelectionSink {

    /** 页面回写：状态栏与输出区由界面层提供。 */
    public interface View {
        void setStatus(String text);

        void setOutput(String text);
    }

    private final PayloadPage.Widgets widgets;
    private final UiKit.FontSink sink;
    private final View view;

    /** 链状态与规则由共享编辑器持有：两个页面用同一套规则，避免实现漂移。 */
    private final ChainEditor editor = new ChainEditor();
    /** 当前渲染出的参数行。 */
    private final List<PayloadPage.ParamField> fields = new ArrayList<PayloadPage.ParamField>();
    /** 最近一次成功生成的载荷（Base64 文本）。 */
    private String lastPayload = "";
    /** 避免初始化期间控件事件互相触发。 */
    private boolean loading;

    public PayloadController(PayloadPage.Widgets widgets, UiKit.FontSink sink, View view) {
        this.widgets = widgets;
        this.sink = sink;
        this.view = view;
        widgets.selector.setSink(this);
        wire();
        reloadPayloads();
    }

    private void wire() {
        widgets.undo.addActionListener(this);
        widgets.clear.addActionListener(this);
        widgets.build.addActionListener(this);
        widgets.copy.addActionListener(this);
        widgets.export.addActionListener(this);
        widgets.toCapture.addActionListener(this);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        if (loading) return;
        Object source = event.getSource();
        if (source == widgets.undo) {
            removeLast();
        } else if (source == widgets.clear) {
            resetChain();
        } else if (source == widgets.build) {
            build();
        } else if (source == widgets.copy) {
            copy();
        } else if (source == widgets.export) {
            export();
        } else if (source == widgets.toCapture) {
            sendToCapture();
        }
    }

    /**
     * 点某一列的候选。
     *
     * <p>第 0 列是载体：点它等于换载体，链从载体重开。
     * 第 N 列（N≥1）点新候选时，链截断为「前 N-1 项 + 新候选」，
     * 该列之后的节点全部丢弃——它们的候选本来就依赖被换掉的那一项。
     */
    @Override
    public void select(int column, String value) {
        if (loading || value == null || value.trim().isEmpty()) return;
        List<String> chain = editor.snapshot();
        if (column == 0) {
            editor.reset(value);
        } else {
            // 允许 column == chain.size()：那是「在末尾列的候选里追加一项」。
            // 写成 column > chain.size() - 1 会把追加也挡掉，
            // 表现为「点第二列的候选毫无反应」（实测踩到，断言抓出）。
            if (column > chain.size()) return;
            editor.reset(chain.get(0));
            for (int index = 1; index < column; index++) editor.append(chain.get(index));
            editor.append(value);
        }
        refreshChainView();
    }

    /** 首列直接列出运行时载体目录：分组分类是「一维下拉框」时代的辅助，列式下不再需要。 */
    private void reloadPayloads() {
        loading = true;
        try {
            editor.reset(PayloadEngine.payloadIds().isEmpty() ? "" : PayloadEngine.payloadIds().get(0));
        } finally {
            loading = false;
        }
        refreshChainView();
    }

    /** 换载体即重开一条链：载体不同，可用节点必然不同。 */
    private void resetChain() {
        editor.reset(editor.head());
        refreshChainView();
    }

    private void removeLast() {
        if (!editor.removeLast()) {
            view.setStatus("载体不能移除：请改选载体，或点「清空链」。");
            return;
        }
        refreshChainView();
    }

    /** 链变化后统一刷新：链文本、各列候选、参数行。 */
    private void refreshChainView() {
        widgets.chain.setText(editor.display());
        widgets.selector.render(columns());
        rebuildParams();
        if (editor.isEmpty()) {
            view.setStatus("没有可用的载荷载体：请确认 java-chains 已就绪。");
        } else if (editor.nodeCount() == 0) {
            view.setStatus("已选载体 " + editor.head() + "，请在右侧列中继续选择节点。");
        } else {
            view.setStatus("当前链：" + editor.nodeCount() + " 个节点，末端"
                    + (editor.candidates().isEmpty() ? "没有可继续追加的节点。" : "还可继续选择。"));
        }
    }

    /**
     * 把当前链换算成列：第 0 列是载体目录，第 K 列（K≥1）是第 K 项的可选后继。
     *
     * <p>候选集合只来自引擎（{@link ChainEditor#candidates()} 内部走
     * {@code firstNodes} / {@code nextNodes}），界面不维护任何节点关系表。
     * 末端没有后继时不再新增列——多出一列空列表会让人以为「还没加载出来」。
     */
    private List<PayloadChainSelector.Column> columns() {
        List<PayloadChainSelector.Column> columns = new ArrayList<PayloadChainSelector.Column>();
        List<String> payloads = PayloadEngine.payloadIds();
        columns.add(new PayloadChainSelector.Column("载荷载体", payloads, labels(payloads), editor.head()));
        List<String> chain = editor.snapshot();
        for (int index = 1; index < chain.size(); index++) {
            // 第 1 级节点的候选不能查「后继」：实测载体自身没有后继，
            // 首节点是按「载体 + 候选」逐个校验出来的（与 ChainEditor 同一判据）。
            List<String> options = index == 1
                    ? PayloadEngine.firstNodes(chain.get(0))
                    : PayloadEngine.nextNodes(chain.get(index - 1));
            columns.add(new PayloadChainSelector.Column("第 " + index + " 级节点",
                    options, labels(options), chain.get(index)));
        }
        List<String> candidates = editor.candidates();
        if (!candidates.isEmpty()) {
            int level = Math.max(1, chain.size());
            columns.add(new PayloadChainSelector.Column("第 " + level + " 级节点",
                    candidates, labels(candidates), ""));
        }
        return columns;
    }

    /** 列内展示显示名：引擎没登记名字的节点回退成标识，避免出现空行。 */
    private static List<String> labels(List<String> ids) {
        List<String> labels = new ArrayList<String>();
        for (String id : ids) {
            String label = PayloadEngine.nodeLabel(id);
            labels.add(label == null || label.trim().isEmpty() ? id : label);
        }
        return labels;
    }

    /**
     * 按链上全部节点重建参数行。
     *
     * <p>先把已填的值取回来，重建后再写回去：参数行随链重建，不做这一步的话，
     * 追加第二个节点会把前一个节点已填的命令清空。
     */
    private void rebuildParams() {
        Map<String, String> previous = currentValues();
        fields.clear();
        fields.addAll(editor.paramFields());
        widgets.fields = fields;
        PayloadPage.renderParams(widgets.params, fields, sink, true);
        for (PayloadPage.ParamField field : fields) {
            String value = previous.get(field.key);
            if (value == null || value.isEmpty()) continue;
            if (field.field instanceof JTextField) {
                ((JTextField) field.field).setText(value);
            } else if (field.field instanceof JComboBox) {
                ((JComboBox<?>) field.field).setSelectedItem(value);
            }
        }
    }

    private Map<String, String> currentValues() {
        return ChainEditor.readValues(fields);
    }

    private Map<String, Object> currentParams() {
        return ChainEditor.toParams(currentValues());
    }

    private void build() {
        if (editor.isEmpty()) {
            view.setStatus("请先选择载荷载体。");
            return;
        }
        if (!editor.isBuildable()) {
            view.setStatus("请先追加至少一个节点：载体自身不是完整利用链。");
            return;
        }
        PayloadResult result = PayloadEngine.build(editor.head(), editor.gadgets(), currentParams());
        if (!result.success) {
            lastPayload = "";
            view.setStatus("生成失败。");
            view.setOutput("生成失败：" + result.message + "\n");
            return;
        }
        lastPayload = result.base64;
        view.setStatus("生成成功：" + result.byteLength() + " 字节。");
        StringBuilder text = new StringBuilder();
        text.append("载体：").append(editor.head()).append("\n");
        text.append("链：").append(editor.display()).append("\n");
        text.append("长度：").append(result.byteLength()).append(" 字节\n");
        text.append("摘要：").append(result.digest).append("\n\n");
        text.append("Base64：\n").append(result.base64).append("\n");
        view.setOutput(text.toString());
    }

    private void copy() {
        if (lastPayload.isEmpty()) {
            view.setStatus("还没有可复制的载荷，请先生成。");
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(lastPayload), null);
        view.setStatus("载荷已复制到剪贴板（" + lastPayload.length() + " 字符）。");
    }

    /**
     * 导出到文件。
     *
     * <p>目录取配置页的「默认导出目录」，留空则落到用户目录；文件名带载体与时间戳，
     * 避免多次导出互相覆盖。
     */
    private void export() {
        if (lastPayload.isEmpty()) {
            view.setStatus("还没有可导出的载荷，请先生成。");
            return;
        }
        String directory = widgets.exportDirectory == null || widgets.exportDirectory.trim().isEmpty()
                ? System.getProperty("user.home") : widgets.exportDirectory.trim();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        Path target = Paths.get(directory, "payload-" + editor.head() + "-" + stamp + ".txt");
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(target, lastPayload.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            view.setStatus("导出失败：" + error.getMessage());
            return;
        }
        view.setStatus("已导出到 " + target);
    }

    /** 把载荷交给抓包页：复用该页已有的请求体输入框，不另建投递逻辑。 */
    private void sendToCapture() {
        if (lastPayload.isEmpty()) {
            view.setStatus("还没有可发送的载荷，请先生成。");
            return;
        }
        if (widgets.onSendToCapture == null) {
            view.setStatus("当前没有可填充的抓包页。");
            return;
        }
        widgets.onSendToCapture.send(lastPayload);
        view.setStatus("已填入抓包页请求体，可在那里发送。");
    }
}
