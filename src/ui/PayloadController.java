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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import org.vulhub.javachains.common.GadgetParam;
import payload.PayloadCatalog;
import payload.PayloadEngine;
import payload.PayloadResult;

/**
 * Payload 生成页的行为：链状态 + 引擎调用 + 结果复制 / 导出 / 转交。
 *
 * <p>放在界面包而不是 {@code Main} 里，是因为这部分逻辑自成一体：载体与节点选择、
 * 参数随链重建、结果取舍，与主窗口的装配无关；{@code Main} 只做接线。
 *
 * <p>本类不 new 控件：控件由 {@code Main} 持有并在每次进页时复用，
 * 因此反复进出页面不会累积状态（与 Shiro / 抓包页的既有做法一致）。
 */
public final class PayloadController implements ActionListener {

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
        widgetGroupList();
        wire();
        reloadKinds();
    }

    private void wire() {
        widgets.group.addActionListener(this);
        widgets.kind.addActionListener(this);
        widgets.addNode.addActionListener(this);
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
        if (source == widgets.group) {
            reloadKinds();
        } else if (source == widgets.kind) {
            resetChain();
        } else if (source == widgets.addNode) {
            appendSelected();
        } else if (source == widgets.undo) {
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

    private void widgetGroupList() {
        List<String> groups = PayloadCatalog.groups();
        widgets.group.setModel(new DefaultComboBoxModel<String>(groups.toArray(new String[0])));
    }

    /** 按当前分组刷新载体下拉框，并重开一条链。 */
    private void reloadKinds() {
        loading = true;
        try {
            String group = (String) widgets.group.getSelectedItem();
            List<String> runtime = PayloadEngine.payloadIds();
            List<String> available = new ArrayList<String>();
            for (String id : PayloadCatalog.membersOf(group)) {
                // 分组表是静态的，运行时目录才是权威：表里多写的项不放进下拉框，
                // 否则使用者选中一个运行时不存在的载体，点生成只会得到一句报错。
                if (runtime.contains(id)) available.add(id);
            }
            if (available.isEmpty()) available.addAll(runtime);
            widgets.kind.setModel(new DefaultComboBoxModel<String>(available.toArray(new String[0])));
        } finally {
            loading = false;
        }
        resetChain();
    }

    /** 换载体即重开一条链：载体不同，可用节点必然不同。 */
    private void resetChain() {
        editor.reset((String) widgets.kind.getSelectedItem());
        refreshChainView();
    }

    private void appendSelected() {
        if (!editor.append((String) widgets.next.getSelectedItem())) {
            view.setStatus(editor.isEmpty() ? "请先选择载荷载体。" : "没有可追加的节点。");
            return;
        }
        refreshChainView();
    }

    private void removeLast() {
        if (!editor.removeLast()) {
            view.setStatus("载体不能移除：请改选载体，或点「清空链」。");
            return;
        }
        refreshChainView();
    }

    /** 链变化后统一刷新：链文本、候选节点、参数行。 */
    private void refreshChainView() {
        widgets.chain.setText(editor.display());
        List<String> candidates = editor.candidates();
        widgets.next.setModel(new DefaultComboBoxModel<String>(candidates.toArray(new String[0])));
        rebuildParams();
        if (candidates.isEmpty() && editor.nodeCount() > 0) {
            view.setStatus("当前链已到末端，没有可继续追加的节点。");
        } else {
            view.setStatus("当前链：" + editor.nodeCount() + " 个节点，可选后继 " + candidates.size() + " 个。");
        }
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
        PayloadPage.renderParams(widgets.params, fields, sink);
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
