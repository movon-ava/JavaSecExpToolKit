package ui;

import java.awt.Font;
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
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import payload.PayloadBuild;
import payload.PayloadCodec;
import payload.PayloadContextEntry;
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
    /** 最近一次成功生成的载荷，形态与当前编码一致；下游拿到的就是这一份。 */
    private String lastPayload = "";
    /** 最近一次成功的原始构建结果：切换编码时不必重新构建（重建会换一套随机类名）。 */
    private PayloadResult lastResult;
    /** 最近一次构建的上下文条目。 */
    private List<PayloadContextEntry> lastContext = new ArrayList<PayloadContextEntry>();
    /** 最近一次构建的逐步产物（仅调试生成时非空）。 */
    private List<payload.PayloadBuild.Step> lastSteps = new ArrayList<payload.PayloadBuild.Step>();
    /** 最近一次编码结果。 */
    private PayloadCodec.Encoded lastEncoded;
    /**
     * 上下文条目当前渲染进列表的行号到清单下标的映射。
     *
     * <p>列表会被搜索框过滤，行号不再等于清单下标；不记这份映射就会把
     * 过滤后的第一行当成第一条条目（实测踩到：搜索后点条目看到的是别人的内容）。
     */
    private final List<Integer> contextRows = new ArrayList<Integer>();
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
        widgets.buildDebug.addActionListener(this);
        widgets.copy.addActionListener(this);
        widgets.export.addActionListener(this);
        widgets.toCapture.addActionListener(this);
        widgets.expand.addActionListener(this);
        widgets.copyContext.addActionListener(this);
        widgets.edit.addActionListener(event -> toggleEdit());
        widgets.contextSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { renderContext(); }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { renderContext(); }

            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { renderContext(); }
        });
        widgets.contextList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) return;
            showContextEntry(widgets.contextList.getSelectedIndex());
        });
        // 编码按钮与 URL 开关：切换后按现有载荷立即重出，不重新构建
        for (PayloadCodec.Option option : PayloadCodec.Option.values()) {
            javax.swing.JToggleButton button = widgets.encode.get(option);
            if (button == null) continue;
            final PayloadCodec.Option target = option;
            button.addActionListener(event -> {
                if (loading) return;
                selectEncoding(target);
            });
        }
        widgets.urlEncode.addActionListener(event -> {
            if (loading) return;
            selectEncoding(encoding());
        });
        // 自动生成开关：打开时立即按当前链生成一次，否则使用者会以为开关没生效
        widgets.autoBuild.addActionListener(event -> {
            if (loading || !widgets.autoBuild.isSelected()) return;
            if (editor.isBuildable()) build(false);
        });
        widgets.autoCopy.addActionListener(event -> {
            if (loading || !widgets.autoCopy.isSelected()) return;
            if (lastResult != null && lastResult.success) copy();
        });
        // 展开所有：打开后立即按最后一次载荷展开一次，否则使用者会以为开关没生效
        widgets.autoExpand.addActionListener(event -> {
            if (loading || !widgets.autoExpand.isSelected()) return;
            if (lastEncoded != null && !widgets.expanded) expand();
        });
        widgets.hoverSelect.addActionListener(event -> {
            if (loading) return;
            widgets.selector.setHoverSelect(widgets.hoverSelect.isSelected());
            view.setStatus(widgets.hoverSelect.isSelected()
                    ? "已开启悬停选链：鼠标滑过候选即等于点击。"
                    : "已关闭悬停选链，改回点击选取。");
        });
        // 默认选中 Base64：与网页版 Generate 页进入时的默认编码一致
        javax.swing.JToggleButton base64 = widgets.encode.get(PayloadCodec.Option.BASE64);
        if (base64 != null) base64.setSelected(true);
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
            build(false);
        } else if (source == widgets.buildDebug) {
            build(true);
        } else if (source == widgets.expand) {
            expand();
        } else if (source == widgets.copyContext) {
            copyContextEntry();
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

    /** 链变化后统一刷新：链文本、链路徽标、各列候选、参数行。 */
    private void refreshChainView() {
        widgets.chain.setText(editor.display());
        widgets.selector.render(PayloadColumns.of(editor));
        rebuildChainChips();
        rebuildParams();
        updateChainMeta();
        if (editor.isEmpty()) {
            view.setStatus("没有可用的载荷载体：请确认 java-chains 已就绪。");
        } else if (editor.nodeCount() == 0) {
            view.setStatus("已选载体 " + editor.head() + "，请在下方列中继续选择节点。");
        } else {
            view.setStatus("当前链：" + editor.nodeCount() + " 个节点，末端"
                    + (candidates().isEmpty() ? "没有可继续追加的节点。" : "还可继续选择。"));
        }
        if (widgets.autoBuild != null && widgets.autoBuild.isSelected() && editor.isBuildable()) {
            build(false);
        }
    }

    /**
     * 重建链路徽标行：每节一个按钮，点击定位到对应列。
     *
     * <p>对应网页版链路条上那串「JavaNativePayload → CommonsBeanutils1 → …」的短标签。
     * 它只是定位入口，不改变链：点中间某一节不会像列表那样截断链，
     * 因为这里点击的语义是「跳到那一列看看」，不是「从那里重开」。
     */
    private void rebuildChainChips() {
        widgets.chainChips.removeAll();
        List<String> chain = editor.snapshot();
        for (int index = 0; index < chain.size(); index++) {
            if (index > 0) {
                JLabel arrow = UiKit.label("\u2192", Font.PLAIN, 12, UiKit.MUTED, sink);
                widgets.chainChips.add(arrow);
                widgets.chainChips.add(Box.createHorizontalStrut(6));
            }
            final int column = index;
            JButton chip = PayloadPage.chipButton(PayloadPage.chipText(PayloadColumns.label(chain.get(index))),
                    chain.get(index) + "（点击定位到第 " + (index + 1) + " 列）", sink, 12);
            chip.addActionListener(event -> widgets.selector.focusColumn(column));
            widgets.chainChips.add(chip);
            widgets.chainChips.add(Box.createHorizontalStrut(6));
        }
        if (chain.isEmpty()) {
            widgets.chainChips.add(UiKit.label("（未选择载体）", Font.PLAIN, 12, UiKit.MUTED, sink));
        }
        widgets.chainChips.revalidate();
        widgets.chainChips.repaint();
    }

    /**
     * 编辑开关：打开后输出区可手改，关闭时按编码重新渲染一次。
     *
     * <p>对应网页版 OUTPUT 的 Edit。改后的内容会成为复制 / 导出 / 填入抓包页的来源，
     * 因此这里把 {@code lastPayload} 一并换成界面上的文本——否则使用者改完再复制，
     * 拿到的还是旧载荷，看起来像「改了没用」。
     */
    private void toggleEdit() {
        widgets.editing = widgets.edit.isSelected();
        if (widgets.editing) {
            widgets.output.setEditable(true);
            widgets.edit.setText("完成");
            view.setStatus("已进入编辑：改动会用于复制 / 导出 / 填入抓包页。");
            return;
        }
        widgets.output.setEditable(false);
        widgets.edit.setText("编辑");
        lastPayload = widgets.output.getText() == null ? "" : widgets.output.getText();
        view.setStatus("已退出编辑，当前正文已作为交付内容。");
    }

    /**
     * 本页可用的候选：与选链列同一份判据（见 {@link PayloadColumns}）。
     *
     * <p>状态栏与链信息行要跟着列里实际能点到的节点走：直接读编辑器会把已经挪到
     * toString 页的触发节点也算进候选数，出现「写着还可选择、列里却没有」的自相矛盾。
     */
    private List<String> candidates() {
        return payload.ChainScope.genericCandidates(editor.candidates());
    }

    /** 链信息行：节点数、末端可否继续、每级候选数——网页版列头数字的等价物。 */
    private void updateChainMeta() {
        if (editor.isEmpty()) {
            widgets.chainMeta.setText("未选择载体");
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append("载体 ").append(PayloadColumns.label(editor.head()));
        if (editor.nodeCount() > 0) text.append("  ·  节点 ").append(editor.nodeCount()).append(" 个");
        text.append("  ·  候选 ").append(candidates().size()).append(" 个");
        if (editor.atEnd()) {
            text.append("  ·  末端节点（END），无可接后续");
        } else if (editor.nodeCount() > 0 && candidates().isEmpty()) {
            text.append("  ·  该节点之后没有可接的节点");
        }
        widgets.chainMeta.setText(text.toString());
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

    /**
     * 生成载荷。
     *
     * <p>编码与输出分成两步：先按选中编码把原始字节转成可投放形态，再把这一形态
     * 交付到输出区、剪贴板与抓包页。下游拿到的永远是「已经编好码的那一份」，
     * 不会出现「界面显示 Base64、复制出去的却是原始字节」这种不一致。
     *
     * @param debug 是否采集逐步产物（调试生成）
     */
    private void build(boolean debug) {
        if (editor.isEmpty()) {
            view.setStatus("请先选择载荷载体。");
            return;
        }
        if (!editor.isBuildable()) {
            view.setStatus("请先追加至少一个节点：载体自身不是完整利用链。");
            return;
        }
        PayloadBuild build = PayloadEngine.buildDetailed(editor.head(), editor.gadgets(),
                currentParams(), debug);
        if (!build.success()) {
            lastPayload = "";
            lastContext = new ArrayList<PayloadContextEntry>();
            renderContext();
            leaveEdit();
            view.setStatus("生成失败。");
            view.setOutput("生成失败：" + build.message() + "\n");
            widgets.outputSize.setText("-");
            return;
        }
        lastResult = build.result;
        lastContext = build.context;
        // 钩子按「内层先完成」的顺序回调，直接展示会得到倒序的步骤表；
        // 按步骤号升序排成「载体 → 第 1 级 → …」，与链的阅读顺序一致
        lastSteps = new ArrayList<payload.PayloadBuild.Step>(build.steps);
        java.util.Collections.sort(lastSteps, new java.util.Comparator<payload.PayloadBuild.Step>() {
            @Override
            public int compare(payload.PayloadBuild.Step left, payload.PayloadBuild.Step right) {
                return Integer.compare(left.index, right.index);
            }
        });
        PayloadCodec.Encoded encoded = PayloadCodec.encode(build.result.bytes, encoding(),
                widgets.urlEncode.isSelected());
        lastPayload = PayloadCodec.portable(encoded);
        lastEncoded = encoded;
        widgets.outputSize.setText(PayloadOutputText.sizeText(build, encoded));
        // 重新生成后退出编辑态：正文已被新载荷替换，再留着编辑开关会让人以为
        // 编辑的是新载荷，而实际上是旧文本
        leaveEdit();
        renderContext();
        view.setStatus(PayloadOutputText.buildStatus(build, encoded, widgets.autoCopy.isSelected()));
        // 初始展开态跟「展开所有」开关走：开着就直接给完整正文，
        // 并把按钮文字同步成「收起」，否则按钮状态与内容对不上
        widgets.expanded = widgets.autoExpand.isSelected();
        widgets.expand.setText(widgets.expanded ? "收起" : "展开");
        if (widgets.expanded) {
            view.setOutput(PayloadCodec.portable(encoded));
        } else {
            view.setOutput(PayloadOutputText.of(build, encoded, debug, editor.head(),
                        PayloadColumns.label(editor.head()), editor.display(), lastSteps));
        }
        if (widgets.autoCopy.isSelected()) copy();
    }



    /** 程序性写回输出区前退出编辑态，使「可编辑」与「正文来源」始终一致。 */
    private void leaveEdit() {
        if (!widgets.editing) return;
        widgets.editing = false;
        widgets.edit.setSelected(false);
        widgets.edit.setText("编辑");
        widgets.output.setEditable(false);
    }

    /**
     * 把上下文与逐步产物渲染到 CONTEXT 面板。
     *
     * <p>条目顺序按构建顺序给出，与网页版一致：先看到载体写入的标记，再看到各节点的产物。
     * 调试生成的逐步产物也并入这张清单——它们本来就是同一件事的两种粒度。
     */
    private void renderContext() {
        String keyword = widgets.contextSearch.getText() == null
                ? "" : widgets.contextSearch.getText().trim().toLowerCase(java.util.Locale.ROOT);
        javax.swing.DefaultListModel<String> model = new javax.swing.DefaultListModel<String>();
        contextRows.clear();
        for (int index = 0; index < lastContext.size(); index++) {
            PayloadContextEntry entry = lastContext.get(index);
            if (!keyword.isEmpty() && !matchesContext(entry, keyword)) continue;
            StringBuilder line = new StringBuilder();
            line.append("#").append(index).append("  ");
            line.append(entry.binary ? "BIN" : "OBJ");
            line.append("   ").append(entry.key);
            if (!entry.source.isEmpty()) line.append("   ").append(entry.source);
            String preview = entry.preview(24);
            if (!preview.isEmpty()) line.append("   ").append(preview);
            model.addElement(line.toString());
            contextRows.add(Integer.valueOf(index));
        }
        if (model.isEmpty()) {
            model.addElement(lastContext.isEmpty()
                    ? "（本次构建没有写入上下文）" : "（没有匹配该关键字的条目）");
        }
        widgets.contextTitle.setText("CONTEXT (" + lastContext.size() + ")");
        widgets.contextList.setModel(model);
        // 重建模型会清掉选中项：这里把第一行选上并同步内容区，
        // 否则搜索后条目内容停在旧条目上，与列表所示不一致
        if (!contextRows.isEmpty()) widgets.contextList.setSelectedIndex(0);
        showContextEntry(widgets.contextList.getSelectedIndex());
    }

    /** 上下文条目是否命中关键字：键、来源与预览任一命中即可。 */
    private static boolean matchesContext(PayloadContextEntry entry, String keyword) {
        return entry.key.toLowerCase(java.util.Locale.ROOT).contains(keyword)
                || entry.source.toLowerCase(java.util.Locale.ROOT).contains(keyword)
                || entry.preview(0).toLowerCase(java.util.Locale.ROOT).contains(keyword);
    }

    /** 选中上下文条目后，把它的内容显示到下方（二进制条目给出十六进制）。 */
    private void showContextEntry(int row) {
        PayloadContextEntry entry = contextEntryAt(row);
        if (entry == null) {
            widgets.contextDetail.setText("");
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append("键：").append(entry.key).append("\n");
        if (!entry.source.isEmpty()) text.append("来源：").append(entry.source).append("\n");
        text.append("类型：").append(entry.binary ? "二进制" : "文本")
                .append("   ").append(entry.sizeBytes()).append(" 字节\n\n");
        text.append(entry.copyable());
        widgets.contextDetail.setText(text.toString());
    }

    private void copyContextEntry() {
        PayloadContextEntry entry = contextEntryAt(widgets.contextList.getSelectedIndex());
        if (entry == null) {
            view.setStatus("请先在 CONTEXT 里选一条要复制的条目。");
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(entry.copyable()), null);
        view.setStatus("已复制上下文条目「" + entry.key + "」（" + entry.copyable().length() + " 字符）。");
    }

    /** 列表第 row 行对应的上下文条目；过滤掉的或越界的行返回 null。 */
    private PayloadContextEntry contextEntryAt(int row) {
        if (row < 0 || row >= contextRows.size()) return null;
        int index = contextRows.get(row).intValue();
        return index >= 0 && index < lastContext.size() ? lastContext.get(index) : null;
    }

    /** 当前选中的编码；四个按钮同属一组，取选中项即可。 */
    private PayloadCodec.Option encoding() {
        for (PayloadCodec.Option option : PayloadCodec.Option.values()) {
            javax.swing.JToggleButton button = widgets.encode.get(option);
            if (button != null && button.isSelected()) return option;
        }
        return PayloadCodec.Option.BASE64;
    }

    private void selectEncoding(PayloadCodec.Option option) {
        javax.swing.JToggleButton button = widgets.encode.get(option);
        if (button != null) button.setSelected(true);
        // 已有载荷时立即按新编码重出，网页版点编码按钮也是这个行为
        if (lastResult != null && lastResult.success) {
            PayloadCodec.Encoded encoded = PayloadCodec.encode(lastResult.bytes, option,
                    widgets.urlEncode.isSelected());
            lastEncoded = encoded;
            lastPayload = PayloadCodec.portable(encoded);
            widgets.outputSize.setText(PayloadOutputText.bytesText(lastResult.byteLength())
                    + " \u2192 " + PayloadOutputText.bytesText(encoded.length()));
            view.setStatus("已切换编码：" + option.label + "。");
            leaveEdit();
            view.setOutput(PayloadOutputText.fromLast(encoded, editor.display()));
        }
    }


    private void export() {
        if (lastPayload.isEmpty()) {
            view.setStatus("还没有可导出的载荷，请先生成。");
            return;
        }
        PayloadExporter.Result written = PayloadExporter.write(lastPayload, widgets.exportDirectory,
                widgets.fileName.getText(), editor.head(),
                lastEncoded != null && lastEncoded.option == PayloadCodec.Option.RAW
                        && !lastEncoded.urlEncoded);
        view.setStatus(written.ok ? "已导出到 " + written.text : "导出失败：" + written.text);
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
     * 展开所有：把输出区里的载荷正文按完整长度重新渲染一次。
     *
     * <p>输出区默认对超长载荷截断（几十兆文本塞进文本框会拖垮界面），
     * 展开是使用者明确要求「我就是要看完」时才做的动作，因此单独一个按钮承载。
     */
    private void expand() {
        if (lastEncoded == null) {
            view.setStatus("还没有可展开的载荷，请先生成。");
            return;
        }
        widgets.expanded = !widgets.expanded;
        widgets.expand.setText(widgets.expanded ? "收起" : "展开");
        if (widgets.expanded) {
            leaveEdit();
            view.setOutput(PayloadCodec.portable(lastEncoded));
            view.setStatus("已展开完整载荷（" + lastPayload.length() + " 字符）。");
        } else {
            view.setOutput(PayloadOutputText.fromLast(lastEncoded, editor.display()));
            view.setStatus("已收起为预览。");
        }
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
