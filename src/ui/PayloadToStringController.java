package ui;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import javax.swing.SwingUtilities;

import payload.PayloadResult;
import payload.ToStringPreset;

/**
 * toString 链页的行为：挑模板 → 渲染步骤 → 把自定义输入映射成引擎参数 → 生成载荷。
 *
 * <p>与 {@link PresetController} 的分工：那条路径生成的是 java-chains 内置预设，
 * 参数由预设自己的输入声明决定；这条路径只服务 toString 触发链，参数就两个
 * （自定义目标类与末端命令），因此不渲染通用参数行，直接把这两个值交给
 * {@link ToStringPreset#params}。
 *
 * <p>本类不构造任何控件：控件与默认值都在 {@link PayloadToStringPage#defaults()} 里建好，
 * 与仓库既有的视图 / 行为分离约定一致。
 */
public final class PayloadToStringController implements ActionListener {

    /** 页面回写：状态栏与输出区由界面层提供。 */
    public interface View {
        void setStatus(String text);

        void setOutput(String text);
    }

    private final PayloadToStringPage.Widgets widgets;
    private final UiKit.FontSink sink;
    private final View view;

    /** 全部模板，按载入顺序；行号与清单控件一一对应。 */
    private final List<ToStringPreset.Template> templates = ToStringPreset.templates();

    private String lastPayload = "";
    private boolean loading;

    public PayloadToStringController(PayloadToStringPage.Widgets widgets, UiKit.FontSink sink,
                                     View view) {
        this.widgets = widgets;
        this.sink = sink;
        this.view = view;
        wire();
        PayloadToStringPage.fillTemplates(widgets, templates);
        loading = true;
        try {
            selectCurrent();
        } finally {
            loading = false;
        }
    }

    private void wire() {
        widgets.templateList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || loading) return;
            selectCurrent();
        });
        widgets.build.addActionListener(this);
        widgets.copyTemplate.addActionListener(this);
        widgets.copy.addActionListener(this);
        widgets.toCapture.addActionListener(this);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        Object source = event.getSource();
        if (source == widgets.build) {
            build();
        } else if (source == widgets.copyTemplate) {
            copyTemplate();
        } else if (source == widgets.copy) {
            copy();
        } else if (source == widgets.toCapture) {
            sendToCapture();
        }
    }

    /**
     * 按配置页保存的默认值预选模板与命令。
     *
     * <p>只在模板确实存在时才切换：配置里留的是旧标识（模板升级后可能已被移除）时
     * 保持当前选择，而不是把页面清空让使用者以为模板读取失败。
     */
    public void applyDefaults(String templateId, String command) {
        String wanted = templateId == null ? "" : templateId.trim();
        if (!wanted.isEmpty()) {
            for (int index = 0; index < templates.size(); index++) {
                if (!templates.get(index).id.equalsIgnoreCase(wanted)) continue;
                loading = true;
                try {
                    widgets.templateList.setSelectedIndex(index);
                    selectCurrent();
                } finally {
                    loading = false;
                }
                break;
            }
        }
        String text = command == null ? "" : command.trim();
        if (!text.isEmpty()) widgets.command.setText(text);
    }

    /** 当前选中的模板；清单行是「显示名 + [JDK]」，因此按行号取。 */
    private ToStringPreset.Template selected() {
        int index = widgets.templateList.getSelectedIndex();
        return index >= 0 && index < templates.size() ? templates.get(index) : null;
    }

    private void selectCurrent() {
        ToStringPreset.Template item = selected();
        widgets.currentId = item == null ? "" : item.id;
        widgets.title.setText(item == null ? "未选择模板" : item.name);
        widgets.meta.setText(item == null ? "" : describeMeta(item));
        widgets.summary.setText(item == null ? "" : item.summary);
        PayloadToStringPage.renderSteps(widgets, item, sink);
        lastPayload = "";
        view.setOutput("");
        if (item != null) {
            view.setStatus("已选择 " + item.name + "：" + item.chainText());
        }
    }

    private String describeMeta(ToStringPreset.Template item) {
        StringBuilder text = new StringBuilder();
        text.append("载体 ").append(ToStringPreset.carrier());
        text.append("  ·  触发节点 ").append(item.trigger);
        text.append("  ·  适用 ").append(item.jdk);
        if (!item.dependency.isEmpty()) text.append("  ·  依赖 ").append(item.dependency);
        return text.toString();
    }

    /**
     * 生成载荷。
     *
     * <p>构建放到后台线程：引擎要生成字节码并实例化 TemplatesImpl，放在事件分发线程上
     * 会让界面在这段时间没有响应。
     */
    private void build() {
        ToStringPreset.Template item = selected();
        if (item == null) {
            view.setStatus(ToStringPreset.issue(""));
            return;
        }
        final String templateId = item.id;
        final List<String> gadgets = item.gadgets;
        final String command = widgets.command.getText().trim();
        final String targetClass = widgets.targetClass.getText().trim();
        widgets.build.setEnabled(false);
        view.setStatus("正在生成载荷…");
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                // 结果收在数组里返回：在 try / catch 两处给同一个 final 局部变量赋值，
                // 编译器会判定「可能已赋值」（try 里赋值后就抛异常的情形），因此不那样写
                final String[] outcome = attempt(templateId, gadgets, command, targetClass);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        widgets.build.setEnabled(true);
                        view.setStatus(outcome[0]);
                        view.setOutput(outcome[1]);
                        lastPayload = outcome[1].startsWith("生成失败") ? "" : payloadBody(outcome[1]);
                    }
                });
            }
        }, "payload-tostring");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 构建载荷并给出「状态行 + 输出文本」。
     *
     * <p>引擎抛出的任何异常都在这里转成可读文本：toString 链会真实生成字节码，
     * 目标类名写错、依赖缺失都会在构建期抛出来，让它们冒到事件分发线程会把界面打崩。
     *
     * @return 长度为 2 的数组：{状态行, 输出文本}
     */
    private String[] attempt(String templateId, List<String> gadgets, String command,
                             String targetClass) {
        try {
            PayloadResult result = payload.PayloadEngine.build(ToStringPreset.carrier(), gadgets,
                    ToStringPreset.params(templateId, command, targetClass));
            if (result.success) {
                return new String[]{"生成成功：" + result.byteLength() + " 字节。",
                        render(templateId, result, command, targetClass)};
            }
            return new String[]{"生成失败。", "生成失败：" + result.message + "\n"};
        } catch (Throwable error) {
            String reason = error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : "：" + error.getMessage());
            return new String[]{"生成失败：" + error.getClass().getSimpleName(), "生成失败：" + reason + "\n"};
        }
    }

    /**
     * 输出文本：与预设链页同一套字段，便于两个页面互相参照。
     *
     * <p>自定义输入一并回显：目标类名写错时链照样能生成，使用者只能靠这段文字
     * 确认自己填的值确实进了载荷，而不是被静默忽略。
     */
    private String render(String templateId, PayloadResult result, String command, String targetClass) {
        ToStringPreset.Template item = ToStringPreset.byId(templateId);
        StringBuilder text = new StringBuilder();
        text.append("模板：").append(item == null ? templateId : item.name)
                .append("（").append(templateId).append("）\n");
        text.append("载体：").append(ToStringPreset.carrier()).append("\n");
        text.append("链：").append(item == null ? "" : item.chainText()).append("\n");
        text.append("末端命令：").append(command == null || command.trim().isEmpty()
                ? ToStringPreset.commandDefault() : command.trim()).append("\n");
        text.append("自定义目标类：").append(targetClass == null || targetClass.trim().isEmpty()
                ? "（留空，使用引擎随机类名）" : targetClass.trim()).append("\n");
        text.append("长度：").append(result.byteLength()).append(" 字节\n");
        text.append("摘要：").append(result.digest).append("\n\n");
        text.append("Base64：\n").append(result.base64).append("\n");
        return text.toString();
    }

    /** 从输出文本里取回载荷正文：复制与填入抓包页都用它。 */
    private static String payloadBody(String output) {
        String marker = "Base64：\n";
        int at = output.indexOf(marker);
        if (at < 0) return "";
        return output.substring(at + marker.length()).trim();
    }

    /** 复制链模板文本：模板本身可复制，便于粘到别处或作为自建链的起点。 */
    private void copyTemplate() {
        ToStringPreset.Template item = selected();
        if (item == null) {
            view.setStatus("请先选择一条 toString 链模板。");
            return;
        }
        String text = item.chainText();
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
        view.setStatus("链模板已复制到剪贴板：" + text);
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
        view.setStatus("已填入抓包页请求体。");
    }
}