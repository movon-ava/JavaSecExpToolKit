package ui;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import javax.swing.SwingUtilities;

import payload.JarPreset;
import service.OobJarService;
import service.PublicationResult;

/**
 * HTTP 带外 Jar 页的行为：选类型与动作 → 生成 Jar 并托管到本地 HTTP 服务 → 交回地址。
 *
 * <p>构建与托管都放到后台线程：生成 Jar 要跑字节码生成，托管要真实 bind 端口，
 * 两者都不适合放在事件分发线程上。
 *
 * <p>「托管」与「停止」必须作用在同一个服务实例上：上游的 HTTP 服务在发布载荷时
 * 按请求回落到自己绑定的端口，换个实例发布会让返回的地址指向别的端口（实测
 * 会回落到上游默认的 50000），地址打不开。这个约束收在 {@link OobJarService} 里，
 * 本类只保证自始至终用的是同一个实例。
 *
 * <p>本类不构造任何控件：控件与默认值都在 {@link OobJarPage#defaults()} 里建好。
 */
public final class OobJarController implements ActionListener {

    /** 页面回写：状态栏与输出区由界面层提供。 */
    public interface View {
        void setStatus(String text);

        void setOutput(String text);
    }

    private final OobJarPage.Widgets widgets;
    private final UiKit.FontSink sink;
    private final View view;
    /** 托管实例一次创建、长期持有：换实例会让已发布地址失效。 */
    private final OobJarService service = new OobJarService();

    private final List<JarPreset.Kind> kinds = JarPreset.kinds();
    private final List<JarPreset.Action> actions = JarPreset.actions();

    /** 最近一次得到的地址，供「复制地址」使用。 */
    private String lastUrl = "";
    private boolean loading;

    public OobJarController(OobJarPage.Widgets widgets, UiKit.FontSink sink, View view) {
        this.widgets = widgets;
        this.sink = sink;
        this.view = view;
        wire();
        OobJarPage.fillKinds(widgets, kinds);
        OobJarPage.fillActions(widgets, actions);
        loading = true;
        try {
            applyAction();
        } finally {
            loading = false;
        }
    }

    private void wire() {
        widgets.kindCombo.addActionListener(this);
        widgets.actionCombo.addActionListener(this);
        widgets.host.addActionListener(this);
        widgets.stop.addActionListener(this);
        widgets.copyUrl.addActionListener(this);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        if (loading) return;
        Object source = event.getSource();
        if (source == widgets.actionCombo) {
            applyAction();
        } else if (source == widgets.host) {
            host();
        } else if (source == widgets.stop) {
            stop();
        } else if (source == widgets.copyUrl) {
            copyUrl();
        }
    }

    /**
     * 下发配置页保存的默认值。
     *
     * <p>每次进页都下发：端口与默认 URL 改完应当立刻可用，只在创建控制器时下发一次
     * 会让改动看起来「没保存成功」。默认值不覆盖使用者已经填好的同类值。
     */
    public void applyDefaults(String bindHost, String port, String url, String path, String command) {
        if (bindHost != null && !bindHost.trim().isEmpty()) widgets.bindHost.setText(bindHost.trim());
        if (port != null && !port.trim().isEmpty()) widgets.port.setText(port.trim());
        if (url != null && !url.trim().isEmpty() && widgets.url.getText().trim().isEmpty()) {
            widgets.url.setText(url.trim());
        }
        if (path != null && !path.trim().isEmpty() && widgets.path.getText().trim().isEmpty()) {
            widgets.path.setText(path.trim());
        }
        if (command != null && !command.trim().isEmpty() && widgets.command.getText().trim().isEmpty()) {
            widgets.command.setText(command.trim());
        }
    }

    /** 当前动作；取不到时给 null，由调用方提示。 */
    private JarPreset.Action selectedAction() {
        int index = widgets.actionCombo.getSelectedIndex();
        return index >= 0 && index < actions.size() ? actions.get(index) : null;
    }

    /** 当前 Jar 类型；取不到时给 null。 */
    private JarPreset.Kind selectedKind() {
        int index = widgets.kindCombo.getSelectedIndex();
        return index >= 0 && index < kinds.size() ? kinds.get(index) : null;
    }

    /** 动作变化后重塑标签与可用性。 */
    private void applyAction() {
        JarPreset.Action action = selectedAction();
        OobJarPage.applyAction(widgets, action, sink);
        if (action != null) view.setStatus("已选择 " + action.name + "：" + action.summary);
    }

    /** 生成 Jar 并托管；地址与产物信息一并回到界面。 */
    private void host() {
        final JarPreset.Kind kind = selectedKind();
        final JarPreset.Action action = selectedAction();
        if (kind == null || action == null) {
            view.setStatus("请先选择 Jar 类型与末端动作。");
            return;
        }
        final int port;
        try {
            port = Integer.parseInt(widgets.port.getText().trim());
        } catch (NumberFormatException error) {
            view.setStatus("监听端口必须是 1-65535 之间的整数。");
            return;
        }
        if (port < 1 || port > 65535) {
            view.setStatus("监听端口必须是 1-65535 之间的整数。");
            return;
        }
        if (action.usesUrl() && widgets.url.getText().trim().isEmpty()) {
            view.setStatus(action.urlLabel + " 不能为空：" + action.name + " 需要该地址。");
            return;
        }
        final String bindHost = widgets.bindHost.getText().trim();
        final String url = widgets.url.getText().trim();
        final String command = widgets.command.getText().trim();
        final String path = widgets.path.getText().trim();
        final String targetClass = widgets.targetClass.getText().trim();
        final String prefix = widgets.classNamePrefix.getText().trim();
        final boolean executable = widgets.executable.isSelected();

        widgets.host.setEnabled(false);
        view.setStatus("正在生成载荷并托管…");
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                // 结果收在数组里返回：在 try / catch 两处给同一个 final 局部变量赋值，
                // 编译器会判定「可能已赋值」（try 里赋值后就抛异常的情形），因此不那样写
                final String[] outcome = attempt(kind, action, bindHost, port, url, command, path,
                        targetClass, prefix, executable);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        widgets.host.setEnabled(true);
                        view.setStatus(outcome[0]);
                        view.setOutput(outcome[1]);
                        if (!outcome[1].startsWith("托管失败")) lastUrl = outcome[2];
                    }
                });
            }
        }, "oobjar-host");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 生成并托管，给出「状态行 + 输出文本 + 地址」。
     *
     * <p>引擎与端口绑定的异常都在这里转成可读文本：构建期会真实执行节点参数里的命令，
     * 端口被占用也会在 bind 时抛出，让它们冒到事件分发线程会把界面打崩。
     *
     * @return 长度为 3 的数组：{状态行, 输出文本, 可复制的地址（失败时为空串）}
     */
    private String[] attempt(JarPreset.Kind kind, JarPreset.Action action, String bindHost,
                             int port, String url, String command, String path, String targetClass,
                             String prefix, boolean executable) {
        try {
            OobJarService.Hosted hosted = service.host(bindHost, bindHost, port, kind.nodeId,
                    action.nodeId, url, command, path, targetClass, prefix, executable);
            if (!hosted.success) {
                return new String[]{"托管失败。", "托管失败：" + hosted.error + "\n", ""};
            }
            return new String[]{"托管成功：" + hosted.url,
                    render(kind, action, hosted, url, command, path, targetClass, prefix), hosted.url};
        } catch (Throwable error) {
            String reason = error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : "：" + error.getMessage());
            return new String[]{"托管失败：" + error.getClass().getSimpleName(),
                    "托管失败：" + reason + "\n", ""};
        }
    }

    /** 输出文本：地址与产物信息，字段与其余页面保持同一套写法。 */
    private String render(JarPreset.Kind kind, JarPreset.Action action, OobJarService.Hosted hosted,
                          String url, String command, String path, String targetClass, String prefix) {
        StringBuilder text = new StringBuilder();
        text.append("地址：").append(hosted.url).append("\n");
        text.append("监听：").append(hosted.endpoint).append("\n");
        text.append("Jar 类型：").append(kind.name).append("（").append(kind.nodeId).append("）\n");
        text.append("末端动作：").append(action.name).append("（").append(action.nodeId).append("）\n");
        text.append("链：").append(JarPreset.chainText(kind.nodeId, action.nodeId)).append("\n");
        text.append("产物：").append(hosted.byteLength).append(" 字节");
        text.append(hosted.jarLike ? "，Zip 魔数校验通过\n" : "，警告：产物不是合法 Zip\n");
        if (action.usesUrl()) text.append("URL：").append(url).append("\n");
        if (action.usesCommand() && !command.isEmpty()) {
            text.append(action.commandLabel).append("：").append(command).append("\n");
        }
        if (action.usesPath() && !path.isEmpty()) text.append("落地路径：").append(path).append("\n");
        if (!targetClass.isEmpty()) text.append("自定义目标类：").append(targetClass).append("\n");
        if (!prefix.isEmpty()) text.append("类名前缀：").append(prefix).append("\n");
        text.append("\n把上面的地址交给目标即可；载荷由本机 HTTP 服务提供，停止托管后地址立即失效。\n");
        return text.toString();
    }

    /** 停止托管并释放端口；失败也给可读原因，不静默。 */
    private void stop() {
        PublicationResult result = service.stop();
        if (!result.success) {
            view.setStatus("停止托管失败：" + result.error);
            return;
        }
        view.setStatus("已停止托管，端口已释放。");
    }

    private void copyUrl() {
        if (lastUrl.isEmpty()) {
            view.setStatus("还没有可复制的地址，请先托管 Jar。");
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(lastUrl), null);
        view.setStatus("地址已复制到剪贴板：" + lastUrl);
    }

    /** 退出前停掉托管；失败也不抛出，避免关不掉窗口。 */
    public void shutdown() {
        if (!service.isRunning()) return;
        try {
            service.stop();
        } catch (RuntimeException ignored) {
            // 关闭阶段的异常不该拦住退出
        }
    }
}