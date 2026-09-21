package ui;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import payload.PayloadCatalog;
import payload.PayloadEngine;
import service.PublicationResult;
import service.ServiceEndpoint;
import service.ServiceManager;
import service.ServiceSpec;

/**
 * 恶意服务器页的行为：服务启停、状态刷新、载荷发布。
 *
 * <p>与 {@code PayloadController} 共用 {@link ChainEditor}：两个页面的链规则
 * （载体不可移除、首节点靠合法性校验得到）必须一致，各写一份迟早会不一样。
 *
 * <p>所有涉及网络与引擎的操作都放到后台线程：启动服务要真实 bind 端口，
 * 构建载荷要跑字节码生成，两者都不适合放在 EDT 上。
 */
public final class ServiceController implements ActionListener {

    /** 页面回写：状态栏与输出区由界面层提供。 */
    public interface View {
        void setStatus(String text);

        void setOutput(String text);

        void appendOutput(String text);
    }

    private final ServicePage.Widgets widgets;
    private final UiKit.FontSink sink;
    private final View view;
    private final ServiceManager manager = new ServiceManager();
    /** 配置页保存的默认监听参数；配置保存后可重新下发。 */
    private service.ServiceDefaults defaults;
    private final ChainEditor editor = new ChainEditor();

    /** 最近一次生成的发布地址，供「复制地址」使用。 */
    private String lastAddress = "";
    /** 当前渲染出的参数行。 */
    private final List<PayloadPage.ParamField> fields = new ArrayList<PayloadPage.ParamField>();
    private boolean loading;
    /** 是否已把预设页传来的链写进编辑器；只消费一次，避免反复覆盖使用者的改动。 */
    private boolean taskConsumed = true;
    private String taskPayload = "";
    private List<String> taskGadgets = new ArrayList<String>();
    private Map<String, Object> taskParams = new LinkedHashMap<String, Object>();

    public ServiceController(ServicePage.Widgets widgets, UiKit.FontSink sink, View view) {
        this(widgets, sink, view, service.ServiceDefaults.empty());
    }

    /**
     * @param defaults 配置页保存的默认监听地址与端口；服务页初值取它，
     *                 端口输入框也用它预填，避免每次开机都要重填一遍
     */
    public ServiceController(ServicePage.Widgets widgets, UiKit.FontSink sink, View view,
                             service.ServiceDefaults defaults) {
        this.widgets = widgets;
        this.sink = sink;
        this.view = view;
        this.defaults = defaults == null ? service.ServiceDefaults.empty() : defaults;
        if (!this.defaults.bindHost.isEmpty()) widgets.bindHost.setText(this.defaults.bindHost);
        if (!this.defaults.advertiseHost.isEmpty()) widgets.advertiseHost.setText(this.defaults.advertiseHost);
        wire();
        ServicePage.fillServices(widgets, ServiceSpec.all());
        ServicePage.fillGroups(widgets, PayloadCatalog.groups());
        if (!widgets.services.isEmpty()) widgets.serviceList.setSelectedIndex(0);
        widgetGroupList();
        selectService();
        refresh();
    }

    private void wire() {
        widgets.serviceList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || loading) return;
            selectService();
        });
        widgets.start.addActionListener(this);
        widgets.stop.addActionListener(this);
        widgets.refresh.addActionListener(this);
        widgets.publish.addActionListener(this);
        widgets.copyAddress.addActionListener(this);
        widgets.toCapture.addActionListener(this);
        widgets.group.addActionListener(this);
        widgets.kind.addActionListener(this);
        widgets.addNode.addActionListener(this);
        widgets.undo.addActionListener(this);
        widgets.clearChain.addActionListener(this);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        if (loading) return;
        Object source = event.getSource();
        if (source == widgets.start) {
            startService();
        } else if (source == widgets.stop) {
            stopService();
        } else if (source == widgets.refresh) {
            refresh();
        } else if (source == widgets.publish) {
            publish();
        } else if (source == widgets.copyAddress) {
            copyAddress();
        } else if (source == widgets.toCapture) {
            sendToCapture();
        } else if (source == widgets.group) {
            reloadKinds();
        } else if (source == widgets.kind) {
            resetChain();
        } else if (source == widgets.addNode) {
            appendSelected();
        } else if (source == widgets.undo) {
            removeLast();
        } else if (source == widgets.clearChain) {
            resetChain();
        }
    }

    /**
     * 接收预设链页传来的链。
     *
     * <p>只暂存不立即生效：使用者可能还没切到本页，此时控件尚未挂到窗口上，
     * 立刻写控件会看不到效果。真正写入发生在下次进页的 {@link #consumeTask()}。
     */
    public void setTask(String payloadId, List<String> gadgets, Map<String, Object> params) {
        taskPayload = payloadId == null ? "" : payloadId;
        taskGadgets = gadgets == null ? new ArrayList<String>() : new ArrayList<String>(gadgets);
        taskParams = params == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(params);
        taskConsumed = false;
    }

    /** 把暂存的链写进编辑器；没有待处理任务时什么都不做。 */
    public void consumeTask() {
        if (taskConsumed || taskPayload.isEmpty()) return;
        taskConsumed = true;
        String group = PayloadCatalog.groupOf(taskPayload);
        widgets.group.setSelectedItem(group);
        loading = true;
        try {
            for (int index = 0; index < widgets.kind.getItemCount(); index++) {
                if (taskPayload.equals(String.valueOf(widgets.kind.getItemAt(index)))) {
                    widgets.kind.setSelectedIndex(index);
                    break;
                }
            }
        } finally {
            loading = false;
        }
        editor.reset(taskPayload);
        for (String gadget : taskGadgets) editor.append(gadget);
        refreshChainView();
        applyTaskParams();
        view.setStatus("已接收预设链：" + editor.display());
    }

    /** 把预设页传来的参数填进刚重建出的参数行。 */
    private void applyTaskParams() {
        for (PayloadPage.ParamField field : fields) {
            String value = null;
            for (Map.Entry<String, Object> entry : taskParams.entrySet()) {
                String key = entry.getKey();
                // 预设页给出的是「节点名.字段名」，参数行的键也是同一形式，直接比对即可
                if (key != null && key.equalsIgnoreCase(field.key)) {
                    value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                    break;
                }
            }
            if (value == null || value.isEmpty()) continue;
            if (field.field instanceof JTextField) {
                ((JTextField) field.field).setText(value);
            } else if (field.field instanceof javax.swing.JComboBox) {
                ((javax.swing.JComboBox<?>) field.field).setSelectedItem(value);
            }
        }
    }

    private void widgetGroupList() {
        ServicePage.fillGroups(widgets, PayloadCatalog.groups());
    }

    // ------------------------------------------------------------------
    // 服务启停与状态
    // ------------------------------------------------------------------

    /**
     * 重新下发配置页保存的默认监听参数。
     *
     * <p>与代理页的处理方式一致：配置保存后覆盖到页面上，使用者不必重开程序。
     * 端口输入框按当前选中服务重建，因此切换服务后拿到的也是新默认值。
     */
    public void applyDefaults(service.ServiceDefaults values) {
        this.defaults = values == null ? service.ServiceDefaults.empty() : values;
        if (!this.defaults.bindHost.isEmpty()) widgets.bindHost.setText(this.defaults.bindHost);
        if (!this.defaults.advertiseHost.isEmpty()) widgets.advertiseHost.setText(this.defaults.advertiseHost);
        selectService();
    }

    /** 切换选中服务：重塑端口行与标题，并刷新一次状态。 */
    private void selectService() {
        String key = widgets.serviceList.getSelectedValue();
        widgets.currentKey = key == null ? "" : key;
        ServiceSpec spec = ServiceSpec.byKey(widgets.currentKey);
        widgets.title.setText(spec == null ? "未选择服务" : spec.title);
        widgets.summary.setText(spec == null ? "" : spec.summary);
        ServicePage.rebuildPorts(widgets, spec, defaults, sink);
        refresh();
    }

    private void startService() {
        final String key = widgets.currentKey;
        if (key.isEmpty()) {
            view.setStatus("请先选择要启动的服务。");
            return;
        }
        final String bindHost = widgets.bindHost.getText().trim();
        final String advertiseHost = widgets.advertiseHost.getText().trim();
        final Map<String, Integer> ports = readPorts();
        if (ports == null) {
            view.setStatus("端口必须是 1-65535 之间的整数。");
            return;
        }
        view.setStatus("正在启动 " + key + " …");
        runAsync(() -> {
            PublicationResult result = manager.start(key, bindHost, advertiseHost, ports);
            return result.success
                    ? "服务已启动：" + key
                    : "启动失败：" + result.error;
        });
    }

    private void stopService() {
        final String key = widgets.currentKey;
        if (key.isEmpty()) {
            view.setStatus("请先选择要停止的服务。");
            return;
        }
        view.setStatus("正在停止 " + key + " …");
        runAsync(() -> {
            PublicationResult result = manager.stop(key);
            return result.success ? "服务已停止：" + key : "停止失败：" + result.error;
        });
    }

    /** 读取端口输入框；任一非法时返回 null，由调用方提示。 */
    private Map<String, Integer> readPorts() {
        Map<String, Integer> ports = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, JTextField> entry : widgets.portFields.entrySet()) {
            String raw = entry.getValue().getText().trim();
            if (raw.isEmpty()) continue;
            int port;
            try {
                port = Integer.parseInt(raw);
            } catch (NumberFormatException error) {
                return null;
            }
            if (port < 1 || port > 65535) return null;
            ports.put(entry.getKey(), Integer.valueOf(port));
        }
        return ports;
    }

    /** 刷新五个服务的状态到界面：标题旁的胶囊与输出区的一行摘要。 */
    private void refresh() {
        StringBuilder text = new StringBuilder();
        String currentState = "STOPPED";
        for (ServiceEndpoint endpoint : manager.endpoints()) {
            text.append(String.format("%-6s %-9s %s", endpoint.key, endpoint.state, endpoint.summary()));
            if (endpoint.publicationCount > 0) {
                text.append("  载荷 ").append(endpoint.publicationCount).append(" 条");
            }
            if (!endpoint.lastError.isEmpty()) text.append("  最近错误：").append(endpoint.lastError);
            text.append("\n");
            if (endpoint.key.equals(widgets.currentKey)) currentState = endpoint.state;
        }
        view.setOutput(text.toString());
        updateChip(currentState);
        widgets.start.setEnabled(!isRunning(currentState));
        widgets.stop.setEnabled(isRunning(currentState));
    }

    private void updateChip(String state) {
        boolean running = isRunning(state);
        widgets.stateChip.setText(running ? "运行中" : stateLabel(state));
        widgets.stateChip.setForeground(running ? new java.awt.Color(21, 128, 61) : UiKit.MUTED);
    }

    private static boolean isRunning(String state) {
        return "READY".equals(state) || "DEGRADED".equals(state) || "STARTING".equals(state);
    }

    private static String stateLabel(String state) {
        if ("ERROR".equals(state)) return "启动出错";
        if ("STOPPING".equals(state)) return "停止中";
        return "未启动";
    }

    // ------------------------------------------------------------------
    // 载荷发布
    // ------------------------------------------------------------------

    private void reloadKinds() {
        loading = true;
        try {
            String group = (String) widgets.group.getSelectedItem();
            List<String> runtime = PayloadEngine.payloadIds();
            List<String> available = new ArrayList<String>();
            for (String id : PayloadCatalog.membersOf(group)) {
                if (runtime.contains(id)) available.add(id);
            }
            if (available.isEmpty()) available.addAll(runtime);
            widgets.kind.setModel(new DefaultComboBoxModel<String>(available.toArray(new String[0])));
        } finally {
            loading = false;
        }
        resetChain();
    }

    private void resetChain() {
        editor.reset((String) widgets.kind.getSelectedItem());
        refreshChainView();
    }

    private void appendSelected() {
        if (!editor.append((String) widgets.next.getSelectedItem())) {
            view.setStatus("没有可追加的节点。");
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

    private void refreshChainView() {
        widgets.chain.setText(editor.display());
        List<String> candidates = editor.candidates();
        widgets.next.setModel(new DefaultComboBoxModel<String>(candidates.toArray(new String[0])));
        rebuildParams();
    }

    private void rebuildParams() {
        Map<String, String> previous = ChainEditor.readValues(fields);
        fields.clear();
        fields.addAll(editor.paramFields());
        widgets.fields = fields;
        PayloadPage.renderParams(widgets.params, fields, sink);
        for (PayloadPage.ParamField field : fields) {
            String value = previous.get(field.key);
            if (value == null || value.isEmpty()) continue;
            if (field.field instanceof JTextField) {
                ((JTextField) field.field).setText(value);
            } else if (field.field instanceof javax.swing.JComboBox) {
                ((javax.swing.JComboBox<?>) field.field).setSelectedItem(value);
            }
        }
    }

    private void publish() {
        final String key = widgets.currentKey;
        if (key.isEmpty()) {
            view.setStatus("请先选择服务。");
            return;
        }
        if (editor.isEmpty()) {
            view.setStatus("请先选择载荷载体。");
            return;
        }
        if (!editor.isBuildable()) {
            view.setStatus("请先追加至少一个节点：载体自身不是完整利用链。");
            return;
        }
        final String payloadId = editor.head();
        final List<String> gadgets = editor.gadgets();
        final Map<String, Object> params = ChainEditor.toParams(ChainEditor.readValues(fields));
        view.setStatus("正在构建载荷并发布…");
        runAsync(() -> {
            PublicationResult result = manager.publish(key, payloadId, gadgets, params);
            if (!result.success) return "发布失败：" + result.error;
            lastAddress = result.message;
            return "发布成功（" + result.byteLength + " 字节）：\n" + result.message;
        });
    }

    private void copyAddress() {
        if (lastAddress.isEmpty()) {
            view.setStatus("还没有可复制的地址，请先发布载荷。");
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(lastAddress), null);
        view.setStatus("地址已复制到剪贴板。");
    }

    private void sendToCapture() {
        if (lastAddress.isEmpty()) {
            view.setStatus("还没有可发送的地址，请先发布载荷。");
            return;
        }
        if (widgets.onSendToCapture == null) {
            view.setStatus("当前没有可填充的抓包页。");
            return;
        }
        // 多行地址（JNDI 会给出 ldap / rmi / http 三个入口）取第一行，
        // 抓包页的目标 URL 只接受一个地址。
        String first = lastAddress;
        int newline = first.indexOf('\n');
        if (newline >= 0) first = first.substring(0, newline);
        widgets.onSendToCapture.send(first.trim());
        view.setStatus("已填入抓包页目标 URL。");
    }

    /** 后台执行并把结果写回界面；状态栏与输出区始终在 EDT 上更新。 */
    private void runAsync(final Task task) {
        widgets.start.setEnabled(false);
        widgets.stop.setEnabled(false);
        new Thread(() -> {
            String message;
            try {
                message = task.run();
            } catch (Throwable error) {
                message = "操作失败：" + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : "：" + error.getMessage());
            }
            final String finalMessage = message;
            SwingUtilities.invokeLater(() -> {
                view.appendOutput(finalMessage);
                view.setStatus(finalMessage.replace("\n", " "));
                refresh();
            });
        }, "service-" + widgets.currentKey).start();
    }

    /**
     * 退出前停掉全部服务。
     *
     * <p>服务一旦启动就真实占用端口，进程退出前不释放的话，下次启动会撞上
     * 「端口被占用」，使用者会以为工具坏了。这里逐个停止，单个失败不影响其余。
     */
    public void shutdown() {
        for (ServiceSpec spec : ServiceSpec.all()) {
            try {
                manager.stop(spec.key);
            } catch (RuntimeException ignored) {
                // 停止阶段吞掉异常：退出流程不能被单个服务拖住
            }
        }
    }

    /** 后台任务：返回要展示的一行或一段文本。 */
    private interface Task {
        String run() throws Exception;
    }
}
