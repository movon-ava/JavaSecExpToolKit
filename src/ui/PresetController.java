package ui;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.DefaultComboBoxModel;

import payload.PayloadEngine;
import payload.PayloadResult;
import preset.PresetCatalogService;
import preset.PresetItem;

/**
 * 预设链页的行为：挑链 → 渲染步骤与输入 → 把输入映射成引擎参数 → 生成载荷。
 *
 * <p>与 {@code PayloadController} 的分工：那条路径是「从空链自己搭」，
 * 这条路径是「用现成模板出载荷」，两者共用 {@link PayloadEngine} 这一个构建入口。
 */
public final class PresetController implements ActionListener {

    /** 页面回写：状态栏与输出区由界面层提供。 */
    public interface View {
        void setStatus(String text);

        void setOutput(String text);
    }

    private final PresetPage.Widgets widgets;
    private final UiKit.FontSink sink;
    private final View view;

    /** 全部预设，按载入顺序。 */
    private final List<PresetItem> all = new ArrayList<PresetItem>();
    /** 分类筛选后的子集，行号与清单控件一一对应。 */
    private final List<PresetItem> visible = new ArrayList<PresetItem>();

    private String lastPayload = "";
    private boolean loading;

    public PresetController(PresetPage.Widgets widgets, UiKit.FontSink sink, View view) {
        this.widgets = widgets;
        this.sink = sink;
        this.view = view;
        all.addAll(PresetCatalogService.load());
        wire();
        loadCategories();
    }

    private void wire() {
        widgets.category.addActionListener(this);
        widgets.presetList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || loading) return;
            selectPreset();
        });
        widgets.build.addActionListener(this);
        widgets.copy.addActionListener(this);
        widgets.toServers.addActionListener(this);
        widgets.toCapture.addActionListener(this);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        if (loading) return;
        Object source = event.getSource();
        if (source == widgets.category) {
            applyFilter();
        } else if (source == widgets.build) {
            build();
        } else if (source == widgets.copy) {
            copy();
        } else if (source == widgets.toServers) {
            sendToServers();
        } else if (source == widgets.toCapture) {
            sendToCapture();
        }
    }

    /**
     * 按配置页保存的默认分类预选。
     *
     * <p>只在分类确实存在时才切换：配置里留的是旧分类名（预设升级后可能已被移除）时
     * 保持当前筛选，而不是把清单清空让使用者以为预设读取失败。
     */
    public void applyDefaultCategory(String name) {
        String wanted = name == null ? "" : name.trim();
        if (wanted.isEmpty()) return;
        for (int index = 0; index < widgets.category.getItemCount(); index++) {
            if (wanted.equals(String.valueOf(widgets.category.getItemAt(index)))) {
                widgets.category.setSelectedIndex(index);
                return;
            }
        }
    }

    /** 当前分类下拉框里的全部候选项，供配置页填充。 */
    public static java.util.List<String> categoryNames(List<PresetItem> items) {
        java.util.List<String> names = new ArrayList<String>();
        names.add("全部分类");
        names.addAll(PresetCatalogService.categories(items));
        return names;
    }

    private void loadCategories() {
        loading = true;
        try {
            List<String> categories = new ArrayList<String>();
            categories.add("全部分类");
            categories.addAll(PresetCatalogService.categories(all));
            widgets.category.setModel(new DefaultComboBoxModel<String>(categories.toArray(new String[0])));
        } finally {
            loading = false;
        }
        if (all.isEmpty()) {
            view.setStatus("预设链读取失败：" + PresetCatalogService.loadError());
        }
        applyFilter();
    }

    private void applyFilter() {
        String category = (String) widgets.category.getSelectedItem();
        visible.clear();
        for (PresetItem item : all) {
            if (category == null || "全部分类".equals(category) || category.equals(item.category)) {
                visible.add(item);
            }
        }
        loading = true;
        try {
            PresetPage.fillList(widgets, visible);
        } finally {
            loading = false;
        }
        selectPreset();
    }

    /** 清单选中项变化后重渲染右侧：标题、说明、步骤、输入、输出。 */
    private void selectPreset() {
        int index = widgets.presetList.getSelectedIndex();
        PresetItem item = index >= 0 && index < visible.size() ? visible.get(index) : null;
        widgets.current = item;
        widgets.title.setText(item == null ? "未选择预设" : item.name);
        widgets.meta.setText(item == null ? "" : describeMeta(item));
        widgets.description.setText(item == null ? "" : item.description);
        PresetPage.renderSteps(widgets, item, sink);
        PresetPage.renderInputs(widgets, item, sink);
        lastPayload = "";
        widgets.output.setText("");
        if (item != null) {
            view.setStatus("已选择 " + item.name + "：" + item.chainText());
        }
    }

    private String describeMeta(PresetItem item) {
        StringBuilder text = new StringBuilder();
        text.append("分类 ").append(item.category);
        text.append("  ·  可用载体 ").append(item.payloads.isEmpty() ? "无" : join(item.payloads));
        if (!item.tags.isEmpty()) text.append("  ·  标签 ").append(join(item.tags));
        return text.toString();
    }

    /**
     * 生成载荷。
     *
     * <p>载体取预设声明的可选载体里的第一个：预设把「这条链能挂在哪些载体上」
     * 列全了，第一个是它的默认选择。载体名为上游的大驼峰写法，而引擎按小写注册，
     * 因此这里统一转小写后再查。
     */
    private void build() {
        PresetItem item = widgets.current;
        if (item == null) {
            view.setStatus("请先选择一条预设链。");
            return;
        }
        if (item.payloads.isEmpty()) {
            view.setStatus("该预设没有声明可用载体，无法生成。");
            return;
        }
        String payloadId = item.payloads.get(0).trim().toLowerCase(Locale.ROOT);
        List<String> gadgets = new ArrayList<String>();
        for (PresetItem.Step step : item.steps) {
            gadgets.add(step.gadget.trim().toLowerCase(Locale.ROOT));
        }
        if (gadgets.isEmpty()) {
            view.setStatus("该预设没有链步骤，无法生成。");
            return;
        }
        Map<String, Object> params = collectParams(item);
        PayloadResult result = PayloadEngine.build(payloadId, gadgets, params);
        if (!result.success) {
            lastPayload = "";
            view.setStatus("生成失败。");
            view.setOutput("生成失败：" + result.message + "\n");
            return;
        }
        lastPayload = result.base64;
        view.setStatus("生成成功：" + result.byteLength() + " 字节。");
        StringBuilder text = new StringBuilder();
        text.append("预设：").append(item.name).append("（").append(item.id).append("）\n");
        text.append("载体：").append(payloadId).append("\n");
        text.append("链：").append(item.chainText()).append("\n");
        text.append("长度：").append(result.byteLength()).append(" 字节\n");
        text.append("摘要：").append(result.digest).append("\n\n");
        text.append("Base64：\n").append(result.base64).append("\n");
        view.setOutput(text.toString());
    }

    /**
     * 把界面输入映射成引擎参数。
     *
     * <p>预设用「步骤 id + 字段名」描述一个输入该写到哪一步；引擎要的是
     * 「节点名.字段名」这种带原始大小写的完整键。因此这里先按步骤 id 反查节点名，
     * 再拼出完整键——直接用步骤 id 拼会被引擎判为未知参数。
     */
    private Map<String, Object> collectParams(PresetItem item) {
        Map<String, String> stepGadget = new LinkedHashMap<String, String>();
        for (PresetItem.Step step : item.steps) {
            // 步骤 id 可以为空：空 id 的步骤只能靠默认参数，不存在可映射的输入
            if (step.id == null || step.id.trim().isEmpty()) continue;
            stepGadget.put(step.id.trim(), step.gadget.trim());
        }
        Map<String, String> values = PresetPage.readInputs(widgets);

        Map<String, Object> params = new LinkedHashMap<String, Object>();
        for (PresetItem.Input input : item.inputs) {
            String value = values.get(input.key);
            if (value == null || value.isEmpty()) value = input.defaultValue;
            if (value == null || value.isEmpty()) continue;
            if (input.targets.isEmpty()) {
                // 没有映射目标的输入按「节点名.字段名」的通用写法兜底，
                // 让使用者填的值不至于被静默丢弃。
                params.put(input.key, value);
                continue;
            }
            for (Map.Entry<String, String> target : input.targets.entrySet()) {
                String gadget = stepGadget.get(target.getKey());
                String field = target.getValue();
                if (gadget == null || field == null || field.trim().isEmpty()) continue;
                params.put(gadget + "." + field, value);
            }
        }
        // 预设步骤自带的默认参数在界面上没有对应输入时也要带上，
        // 否则像 BytecodeConvert 这类需要固定开关的节点会缺参数。
        for (PresetItem.Step step : item.steps) {
            for (Map.Entry<String, String> arg : step.args.entrySet()) {
                String key = step.gadget + "." + arg.getKey();
                if (!params.containsKey(key)) params.put(key, arg.getValue());
            }
        }
        return params;
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

    /** 把当前预设的载体与链交给恶意服务器页，省去在那里重搭一遍链。 */
    private void sendToServers() {
        PresetItem item = widgets.current;
        if (item == null || item.payloads.isEmpty()) {
            view.setStatus("请先选择一条预设链。");
            return;
        }
        if (widgets.onSendToServers == null) {
            view.setStatus("当前没有可用的恶意服务器页。");
            return;
        }
        String payloadId = item.payloads.get(0).trim().toLowerCase(Locale.ROOT);
        List<String> gadgets = new ArrayList<String>();
        for (PresetItem.Step step : item.steps) {
            gadgets.add(step.gadget.trim().toLowerCase(Locale.ROOT));
        }
        widgets.onSendToServers.send(payloadId, gadgets, collectParams(item));
        view.setStatus("已发到恶意服务器页，选好服务后点「发布载荷」。");
    }

    /** 供自检读取：当前可见的预设条数。 */
    public int visibleCount() {
        return visible.size();
    }

    private static String join(List<String> items) {
        StringBuilder text = new StringBuilder();
        for (String item : items) {
            if (text.length() > 0) text.append(" / ");
            text.append(item);
        }
        return text.toString();
    }
}
