package ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

import probe.ProbeCommand;
import probe.ProbeEngine;
import util.Platform;

/**
 * Fastjson 探测页的行为：模式勾选 → 参数拼装 → 引擎调用 → 结果回填。
 *
 * <p>配置是只读依赖：本类不写配置，只按配置里的 Python 解释器、报告详细度、
 * CEYE 凭据拼启动参数；配置的读写入口统一留在 {@code ConfigController}。
 */
public final class ProbeController implements ActionListener {

    private final ProbePage.Widgets widgets;
    private final ConfigController config;

    public ProbeController(ProbePage.Widgets widgets, ConfigController config) {
        this.widgets = widgets;
        this.config = config;
        widgets.detect.addActionListener(this);
        // 模式勾选状态同时决定对应输入框是否可用（与原主窗口的接线一致）
        widgets.dnsEnabled.addActionListener(e -> applyStageFieldState());
        widgets.ceyeEnabled.addActionListener(e -> applyStageFieldState());
        widgets.modeExpect.addActionListener(e -> applyStageFieldState());
        widgets.onModeChanged = this::applyStageFieldState;
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        if (event.getSource() == widgets.detect) startDetection();
    }

    public void startDetection() {
        String url = widgets.target.getText().trim();
        final String probeTimeout = widgets.timeout.getText().trim();
        final List<String> modes = modeKeys();
        if (url.isEmpty()) { widgets.result.setText("目标 URL 不能为空。\n"); return; }
        if (modes.isEmpty()) { widgets.result.setText("请至少勾选一个探测模式。\n"); return; }
        if (modes.contains("expect") && widgets.baseBody.getText().trim().isEmpty()) {
            widgets.result.setText("期望类探测需要填写业务参数，例如 {\"age\":20,\"name\":\"Bob\"}。\n");
            return;
        }
        widgets.detect.setEnabled(false);
        widgets.status.setText("探测中...");
        widgets.result.setText("正在发送无害识别请求，请等待结果...\n");
        Thread worker = new Thread(() -> {
            StringBuilder text = new StringBuilder();
            for (String mode : modes) {
                // 引擎已按模式输出中文报告（含分段标题），这里只负责按固定顺序拼接
                String segment;
                try {
                    segment = runProbe(url, probeTimeout, mode);
                } catch (Exception e) {
                    segment = "启动探测失败: " + e;
                }
                text.append(segment);
                // 报告自带换行；仅在缺失时补一个，避免段间空行挤占结果区
                if (!segment.endsWith("\n")) text.append(System.lineSeparator());
            }
            final String finalOutput = text.toString();
            SwingUtilities.invokeLater(() -> {
                widgets.result.setText(finalOutput);
                widgets.detect.setEnabled(true);
                widgets.status.setText("探测完成");
            });
        }, "fastjson-probe");
        worker.setDaemon(true);
        worker.start();
    }

    /** 勾选的探测模式，按固定顺序返回对应的引擎 mode（含 DNS 探针 / CEYE 确认）。 */
    public List<String> modeKeys() {
        List<String> keys = new ArrayList<String>();
        if (widgets.modeDetect.isSelected()) keys.add("detect");
        if (widgets.modeVersion.isSelected()) keys.add("version");
        if (widgets.modeExpect.isSelected()) keys.add("expect");
        if (widgets.dnsEnabled.isSelected()) keys.add("dns");
        if (widgets.ceyeEnabled.isSelected()) keys.add("ceye");
        return keys;
    }

    /** 单模式探测：拼参数并落进程。抽成公开方法供自检直接驱动，不依赖按钮点击。 */
    public String runProbe(String url, String seconds, String mode) throws Exception {
        if ("ceye".equals(mode) && !hasCeyeCredential()) {
            return "CEYE 确认需要 Token：请在「配置」页填写 CEYE Token，或设置 CEYE_TOKEN 环境变量后重试。"
                    + System.lineSeparator();
        }
        ProbeCommand.Options options = new ProbeCommand.Options();
        options.python = ProbeEngine.resolvePython(config.python());
        options.script = ProbeEngine.extractScript().toString();
        options.target = url;
        options.timeout = seconds;
        options.mode = mode;
        options.probeMethod = String.valueOf(widgets.probeMethod.getSelectedItem());
        options.baseBody = widgets.baseBody.getText().trim();
        options.headers = widgets.requestHeaders.getText().trim();
        options.sessionCookie = widgets.sessionCookie.getText().trim();
        options.report = config.probeReport();
        options.dnslogHost = widgets.dnslogHost.getText().trim();
        options.dnsWait = widgets.dnsWait.getText().trim();
        options.dnsFilter = widgets.dnsFilter.getText().trim();
        options.ceyeToken = config.ceyeToken();
        options.ceyeDomain = config.ceyeDomain();
        return ProbeEngine.run(ProbeCommand.probe(options));
    }

    /** CEYE Token 来源与 Python 侧一致：配置页写值 > CEYE_TOKEN / FJ_CEYE_TOKEN 环境变量。 */
    public boolean hasCeyeCredential() {
        if (!config.ceyeToken().isEmpty()) return true;
        return !Platform.env("CEYE_TOKEN").isEmpty() || !Platform.env("FJ_CEYE_TOKEN").isEmpty();
    }

    /** 按模式勾选刷新输入框可用性：未勾选的模式不允许再输入该阶段参数。 */
    public void applyStageFieldState() {
        boolean dnsStage = widgets.dnsEnabled.isSelected();
        boolean ceyeStage = widgets.ceyeEnabled.isSelected();
        if (widgets.dnslogHost == null) return;
        setFieldEnabled(widgets.baseBody, widgets.modeExpect.isSelected());
        setFieldEnabled(widgets.dnslogHost, dnsStage);
        setFieldEnabled(widgets.dnsWait, dnsStage);
        setFieldEnabled(widgets.dnsFilter, ceyeStage);
    }

    /** 输入框可用性的统一样式：禁用时也要把底色与文字一起改，否则看起来仍像可输入。 */
    private static void setFieldEnabled(javax.swing.JTextField field, boolean enabled) {
        field.setEnabled(enabled);
        field.setBackground(enabled ? Color.WHITE : new Color(241, 245, 249));
        field.setForeground(enabled ? UiKit.TEXT : UiKit.MUTED);
        field.setDisabledTextColor(UiKit.MUTED);
        field.setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.TEXT_CURSOR : Cursor.DEFAULT_CURSOR));
    }
}
