package ui;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

import probe.CaptureBridge;
import probe.ProbeCommand;
import probe.ProbeEngine;
import proxy.ProxyServer;
import util.HttpText;
import util.JsonText;
import util.Platform;

/**
 * 抓包转换页的行为：抓包 / 格式转换 / 结果复制 / 一键发送到其它功能页。
 *
 * <p>它是「流量 → 其它功能」的中转站，因此依赖三个页面的控件：探测页（填目标与参数）、
 * Shiro 页（填 URL 与请求头）、以及自己的输入区。依赖只走控件注入，不存在页面之间的
 * 直接调用，任何一方都不会因为页面重排而失效。
 */
public final class CaptureController implements ActionListener {

    private final CapturePage.Widgets widgets;
    private final ProbePage.Widgets probe;
    private final ShiroPage.Widgets shiro;
    private final ConfigController config;
    private final Consumer<String> navigator;

    public CaptureController(CapturePage.Widgets widgets, ProbePage.Widgets probe, ShiroPage.Widgets shiro,
                             ConfigController config, Consumer<String> navigator) {
        this.widgets = widgets;
        this.probe = probe;
        this.shiro = shiro;
        this.config = config;
        this.navigator = navigator;
        wire();
    }

    private void wire() {
        widgets.run.addActionListener(this);
        widgets.convert.addActionListener(this);
        widgets.toProbe.addActionListener(this);
        widgets.send.addActionListener(this);
        widgets.onCopy = this::copyResult;
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        Object source = event.getSource();
        if (source == widgets.run) startCapture();
        else if (source == widgets.convert) startConvert();
        else if (source == widgets.toProbe) fillProbeFromCapture();
        else if (source == widgets.send) sendCaptureTo();
    }

    public void startCapture() {
        final String url = widgets.url.getText().trim();
        if (url.isEmpty()) { widgets.result.setText("目标 URL 不能为空。\n"); return; }
        final String method = String.valueOf(widgets.method.getSelectedItem());
        final String contentType = widgets.contentType.getText().trim();
        final String headers = widgets.headers.getText().trim();
        final String body = widgets.body.getText();
        final String target = String.valueOf(widgets.convertTarget.getSelectedItem());
        widgets.run.setEnabled(false);
        widgets.status.setText("抓包中...");
        widgets.result.setText("正在发送 " + method + " 请求并等待响应...\n");
        Thread worker = new Thread(() -> {
            String output;
            try {
                output = runCapture(url, method, contentType, headers, body, target);
            } catch (Exception e) {
                output = "启动抓包失败: " + e + System.lineSeparator();
            }
            final String finalOutput = output;
            SwingUtilities.invokeLater(() -> {
                widgets.result.setText(finalOutput);
                widgets.result.setCaretPosition(0);
                widgets.run.setEnabled(true);
                widgets.status.setText("抓包完成");
            });
        }, "capture-probe");
        worker.setDaemon(true);
        worker.start();
    }

    public void startConvert() {
        final String pasted = widgets.pastedRequest.getText().trim();
        final String target = String.valueOf(widgets.convertTarget.getSelectedItem());
        final String url = widgets.url.getText().trim();
        final String headers = widgets.headers.getText().trim();
        final String body = widgets.body.getText();
        final String method = String.valueOf(widgets.method.getSelectedItem());
        final String contentType = widgets.contentType.getText().trim();
        widgets.convert.setEnabled(false);
        widgets.status.setText("转换中...");
        Thread worker = new Thread(() -> {
            String output;
            try {
                output = runConvert(pasted, url, method, contentType, headers, body, target);
            } catch (Exception e) {
                output = "转换失败: " + e + System.lineSeparator();
            }
            final String finalOutput = output;
            SwingUtilities.invokeLater(() -> {
                widgets.result.setText(finalOutput);
                widgets.result.setCaretPosition(0);
                widgets.convert.setEnabled(true);
                widgets.status.setText("转换完成");
            });
        }, "convert-probe");
        worker.setDaemon(true);
        worker.start();
    }

    /** 复制结果到剪贴板；结果为空时保持原状态提示，避免「复制了个空」还提示成功。 */
    public void copyResult() {
        String text = widgets.result.getText();
        if (text.isEmpty()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        widgets.status.setText("已复制到剪贴板");
    }

    /**
     * 把代理页抓到的一条流量导进来：URL、方法、请求头、请求体成套带入抓包页。
     *
     * <p>跳转头与原请求的 Content-Length 一律过滤：前者只对原连接有效，
     * 后者会让目标一直等一个不会到来的请求体。
     */
    public void importFromProxy(ProxyServer.HttpFlow flow, String raw) {
        widgets.url.setText(flow.url());
        widgets.method.setSelectedItem(flow.method);
        widgets.pastedRequest.setText(raw);
        StringBuilder headers = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> header : flow.requestHeaders.entrySet()) {
            if (!HttpText.forwardable(header.getKey())) continue;
            if (!first) headers.append(",");
            first = false;
            headers.append("\"").append(header.getKey()).append("\":\"")
                    .append(header.getValue().replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        headers.append("}");
        widgets.headers.setText(headers.toString());
        widgets.body.setText(new String(HttpText.bodyBytes(raw), StandardCharsets.UTF_8));
        if (navigator != null) navigator.accept("capture");
        widgets.status.setText("已从代理请求包导入，可直接转换或抓包");
    }

    /** 把抓包得到的 URL / 方法 / 请求头 / 请求体回填到 Fastjson 探测页。 */
    public void fillProbeFromCapture() {
        String url = probeUrl();
        if (url.isEmpty()) { widgets.status.setText("请先填写或抓取一个 URL"); return; }
        probe.target.setText(url);
        probe.probeMethod.setSelectedItem(String.valueOf(widgets.method.getSelectedItem()));
        String headers = CaptureBridge.probeHeaders(input());
        if (!headers.isEmpty()) probe.requestHeaders.setText(headers);
        String bodyText = widgets.body.getText().trim();
        if (!bodyText.isEmpty()) probe.baseBody.setText(bodyText);
        if (navigator != null) navigator.accept("fastjson.detect");
        widgets.status.setText("已填入探测页");
    }

    private String probeUrl() {
        String typed = widgets.url.getText().trim();
        if (!typed.isEmpty()) return typed;
        String parsed = JsonText.field(widgets.result.getText(), "url");
        if (!parsed.isEmpty()) return parsed;
        return JsonText.requestTarget(widgets.result.getText());
    }

    /**
     * 一键发送：把刚抓到的请求原样交给选中的功能，不做自动探测。
     *
     * <p>与「填入探测页」的区别是这里不再手工复制粘贴，而是直接把 URL、方法、请求头、
     * 请求体整套搬过去并跳到对应页面，由使用者确认参数后再自己点「开始探测」；
     * 抓包阶段已经带上了真实 Cookie，因此需要登录态的接口可以直接用。
     *
     * <p>刻意**不**自动开跑：抓包得到的报文往往还需要调整探测模式、业务参数或期望类，
     * 一进来就发请求既浪费一次往返，也容易在参数还没确认时就把流量打到目标上。
     * 新增目标时只需在这里加一个分支。
     */
    public void sendCaptureTo() {
        String url = probeUrl();
        if (url.isEmpty()) {
            widgets.status.setText("请先填写或抓取一个 URL");
            return;
        }
        String destination = String.valueOf(widgets.sendTo.getSelectedItem());
        String method = methodOf();
        String cookieHeader = CaptureBridge.cookieHeader(input());
        if ("Shiro 漏洞利用".equals(destination)) {
            sendToShiro(url, method, cookieHeader);
            return;
        }
        sendToFastjson(url, method, cookieHeader);
    }

    /** 抓包结果送往 Fastjson 探测页，等待使用者确认参数后自己开跑。 */
    public void sendToFastjson(String url, String method, String cookieHeader) {
        probe.target.setText(url);
        probe.probeMethod.setSelectedItem(method);
        String headers = CaptureBridge.probeHeaders(input());
        if (headers.isEmpty() && !cookieHeader.isEmpty()) headers = CaptureBridge.headerJson(cookieHeader);
        if (!headers.isEmpty()) probe.requestHeaders.setText(headers);
        String bodyText = widgets.body.getText().trim();
        if (!bodyText.isEmpty()) probe.baseBody.setText(bodyText);
        if (navigator != null) navigator.accept("fastjson.detect");
        widgets.status.setText("已发送到 Fastjson 探测，确认参数后点「开始探测」");
    }

    /**
     * 抓包结果送往 Shiro 页，等待使用者确认参数后自己开跑。
     *
     * <p>抓到的会话 Cookie 会连同其它请求头一起写进「附加请求头」，这样需要登录态的
     * 接口也能直接检测；不带任何抓包结果时会退回当前页面上已有的配置。
     */
    public void sendToShiro(String url, String method, String cookieHeader) {
        shiro.url.setText(url);
        shiro.requestMethod.setSelectedItem(method);
        String lines = CaptureBridge.headerLines(input(), cookieHeader);
        if (!lines.isEmpty()) shiro.headers.setText(lines);
        // 请求体必须一并带过去：抓包得到的 POST 体通常就是接口的业务参数，
        // 不带上则目标只会返回校验错误，探测结论不可信。
        shiro.body.setText(CaptureBridge.requestBody(input()));
        widgets.status.setText("已发送到 Shiro 漏洞利用，确认参数后点「一键检测」");
    }

    /** 抓包页当前输入（请求头 JSON / 粘贴报文 / 抓包结果），供参数转换复用。 */
    public CaptureBridge.Input input() {
        CaptureBridge.Input input = new CaptureBridge.Input();
        input.headersJson = widgets.headers.getText();
        input.pastedRequest = widgets.pastedRequest.getText();
        input.captureResult = widgets.result.getText();
        input.body = widgets.body.getText();
        return input;
    }

    /** 抓包页当前选择的请求方法（探测页 / Shiro 页按同一方法发请求）。 */
    public String methodOf() {
        Object selected = widgets.method.getSelectedItem();
        String method = selected == null ? "" : String.valueOf(selected).trim();
        return method.isEmpty() ? "POST" : method;
    }

    public String runCapture(String url, String method, String contentType, String headers,
                             String body, String target) throws Exception {
        return ProbeEngine.run(captureCommand(url, method, contentType, headers, body, target, true));
    }

    public String runConvert(String pasted, String url, String method, String contentType,
                             String headers, String body, String target) throws Exception {
        List<String> command = captureCommand(url, method, contentType, headers, body, target, false);
        if (!pasted.isEmpty()) {
            command.add("--pasted-request");
            command.add(Platform.commandArg(pasted));
        }
        command.add("--mode");
        command.add("convert");
        return ProbeEngine.run(command);
    }

    /** 抓包 / 转换共用的启动参数；具体拼装交给 {@link ProbeCommand}。 */
    private List<String> captureCommand(String url, String method, String contentType, String headers,
                                        String body, String target, boolean capture) throws Exception {
        ProbeCommand.Options options = new ProbeCommand.Options();
        options.python = ProbeEngine.resolvePython(config.python());
        options.script = ProbeEngine.extractScript().toString();
        options.timeout = Platform.valueOr(probe.timeout.getText(), "8");
        options.method = method;
        options.contentType = contentType;
        options.headers = headers;
        options.body = body;
        options.captureUrl = url;
        options.convertTargets = target;
        options.capture = capture;
        return ProbeCommand.capture(options);
    }
}
