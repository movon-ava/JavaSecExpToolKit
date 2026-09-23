package ui;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JPanel;

import service.ServiceDefaults;

/**
 * 载荷工作台五页的装配：Payload 生成、预设链、toString 链、HTTP 带外 Jar、恶意服务器。
 *
 * <p>这三页放在一起，是因为它们的控制器都必须「进过一次就一直复用」：
 * Payload 页持有使用者刚配好的链，预设链页持有已解析的 YAML，
 * 恶意服务器页持有正在监听端口的服务——重建控制器等于把这些状态丢掉。
 * 把「何时创建、何时复用」集中在一处，避免组合根里散落三段看着相似、
 * 细节各异的懒加载代码。
 *
 * <p>三页互相之间还有两条既有联动：预设链页可以把链交给恶意服务器页，
 * Payload / 预设链页可以把载荷填入抓包页。联动只走控件与回调，不直接调用对方，
 * 因此任一方重排界面都不会影响另一方。
 */
public final class WorkbenchPages implements ConfigController.View {

    private final UiKit.FontSink fonts;
    private final ConfigController config;
    private final CapturePage.Widgets capture;
    private final Consumer<String> navigator;

    /** 三页的控件：与其它页面一样由装配方持有，反复进出不累积状态。 */
    public final PayloadPage.Widgets payloadWidgets = new PayloadPage.Widgets();
    /** 列式链选择器：载荷生成页的选链主体，随控件一起只创建一次。 */
    public final PayloadChainSelector payloadSelector;
    public final PresetPage.Widgets presetWidgets = new PresetPage.Widgets();
    public final ServicePage.Widgets serviceWidgets = new ServicePage.Widgets();
    /** toString 链页与带外 Jar 页的控件：同样由装配方持有，反复进出不累积状态。 */
    public final PayloadToStringPage.Widgets tostringWidgets = PayloadToStringPage.defaults();
    public final OobJarPage.Widgets oobJarWidgets = OobJarPage.defaults();
    /**
     * 漏洞分析的控件：两个二级项各有自己的控件集，因此各自只建一次。
     *
     * <p>为什么必须分开：两页原先共用一个视图，点哪个二级项看到的都一样，
     * 使用者无法判断自己在看依赖结论还是代码结论。拆开后控件也分开，
     * 目标框可以各填各的（依赖口径看构建产物、代码口径看同一个 jar 或目录）。
     */
    public final AnalyzeScanPage.Widgets analyzeScanWidgets = AnalyzeScanPage.defaults();
    public final AnalyzeChainPage.Widgets analyzeChainWidgets = AnalyzeChainPage.defaults();

    private PayloadController payloadController;
    private PresetController presetController;
    private ServiceController serviceController;
    private PayloadToStringController tostringController;
    private OobJarController oobJarController;
    private AnalyzeScanController analyzeScanController;
    private AnalyzeController analyzeChainController;

    public WorkbenchPages(UiKit.FontSink fonts, ConfigController config, CapturePage.Widgets capture,
                          Consumer<String> navigator) {
        this.fonts = fonts;
        this.config = config;
        this.capture = capture;
        this.navigator = navigator;
        payloadSelector = new PayloadChainSelector(fonts);
        payloadWidgets.selector = payloadSelector;
        payloadWidgets.onSendToCapture = payload -> {
            capture.body.setText(payload);
            open("capture");
        };
        presetWidgets.onSendToCapture = payload -> {
            capture.body.setText(payload);
            open("capture");
        };
        presetWidgets.onSendToServers = (payloadId, gadgets, params) -> sendTaskToServers(payloadId, gadgets, params);
        tostringWidgets.onSendToCapture = payload -> {
            capture.body.setText(payload);
            open("capture");
        };
    }

    /** Payload 生成页：控制器只有第一次进页时创建，否则使用者刚配好的链会被清空。 */
    public JPanel payload() {
        payloadWidgets.exportDirectory = config.property("payload_export_dir", "").trim();
        applyPayloadDefaults();
        if (payloadController == null) {
            payloadController = new PayloadController(payloadWidgets, fonts, new PayloadController.View() {
                @Override public void setStatus(String text) { payloadWidgets.status.setText(text); }

                @Override public void setOutput(String text) {
                    payloadWidgets.output.setText(text);
                    // setText 会把插入符留在末尾，视口随即滚到载荷尾部；
                    // 生成后要看的正是开头（载体与编码信息），因此显式回到起点
                    payloadWidgets.output.setCaretPosition(0);
                }
            });
        }
        return PayloadPage.build(payloadWidgets, fonts);
    }

    /** 预设链页：控制器持有已载入的预设清单，每次进页都重建会重新解析一遍 YAML。 */
    public JPanel preset() {
        if (presetController == null) {
            presetController = new PresetController(presetWidgets, fonts, new PresetController.View() {
                @Override public void setStatus(String text) { presetWidgets.status.setText(text); }

                @Override public void setOutput(String text) { presetWidgets.output.setText(text); }
            });
        }
        presetController.applyDefaultCategory(config.property("preset_category", "").trim());
        return PresetPage.build(presetWidgets, fonts);
    }

    /**
     * toString 链页：控制器持有模板清单与当前选择，每次进页都重建会重置使用者填好的
     * 目标类与命令，因此与其余工作台页面一样只创建一次。
     */
    public JPanel tostring() {
        if (tostringController == null) {
            tostringController = new PayloadToStringController(tostringWidgets, fonts,
                    new PayloadToStringController.View() {
                        @Override public void setStatus(String text) { tostringWidgets.status.setText(text); }

                        @Override public void setOutput(String text) { tostringWidgets.output.setText(text); }
                    });
        }
        tostringController.applyDefaults(config.property("tostring_default_template", "").trim(),
                config.property("tostring_default_command", "").trim());
        return PayloadToStringPage.build(tostringWidgets, fonts);
    }

    /**
     * HTTP 带外 Jar 页：控制器持有正在监听的 HTTP 服务，
     * 重建控制器等于把已经托管出去的地址丢掉，界面上再也停不掉它。
     */
    public JPanel oobJar() {
        if (oobJarController == null) {
            oobJarController = new OobJarController(oobJarWidgets, fonts, new OobJarController.View() {
                @Override public void setStatus(String text) { oobJarWidgets.status.setText(text); }

                @Override public void setOutput(String text) { oobJarWidgets.output.setText(text); }
            });
        }
        oobJarController.applyDefaults(config.property("oobjar_bind_host", "").trim(),
                // 配置里没写过该键时回落到 50001：端口框留空会让首次托管必然报「端口非法」
                config.property("oobjar_port", "50001").trim(),
                config.property("oobjar_default_url", "").trim(),
                config.property("oobjar_default_path", "").trim(),
                config.property("oobjar_default_command", "").trim());
        return OobJarPage.build(oobJarWidgets, fonts);
    }

    /** 漏洞分析 · 组件与漏洞：依赖口径分析（秒级、纯本地）。 */
    public JPanel analyzeScan() {
        ensureAnalyzeScan();
        analyzeScanController.applyDefaults();
        return AnalyzeScanPage.build(analyzeScanWidgets, fonts);
    }

    /**
     * 漏洞分析 · 调用链查询：代码口径分析（需外部引擎，建库是分钟级）。
     *
     * <p>与「组件与漏洞」是**两个独立页面**：前者读依赖坐标，后者读调用图，
     * 两者的代价、输入与结论口径都不同。共用一页会让「点哪个二级项都一样」，
     * 使用者无法判断结论来自哪一侧。
     */
    public JPanel analyzeChain() {
        ensureAnalyzeChain();
        analyzeChainController.applyDefaults();
        return AnalyzeChainPage.build(analyzeChainWidgets, fonts);
    }

    /**
     * 「组件与漏洞」页的控制器：只做依赖口径分析，无跨次保留的重状态。
     */
    private void ensureAnalyzeScan() {
        if (analyzeScanController != null) return;
        analyzeScanWidgets.onCopy = copyAction(analyzeScanWidgets.output, analyzeScanWidgets.status);
        analyzeScanController = new AnalyzeScanController(analyzeScanWidgets, fonts,
                new AnalyzeWorker.View() {
                    @Override public void setStatus(String text) {
                        analyzeScanWidgets.status.setText(text);
                    }

                    @Override public void setOutput(String text) {
                        analyzeScanWidgets.output.setText(text);
                        analyzeScanWidgets.output.setCaretPosition(0);
                    }

                    @Override public void setBusy(boolean busy) {
                        analyzeScanWidgets.progress.setVisible(busy);
                        analyzeScanWidgets.run.setEnabled(!busy);
                    }

                    @Override public void showJumps(java.util.Map<String, String> jumps,
                                                    java.util.function.Consumer<String> onJump) {
                        AnalyzeScanController.fillJumps(analyzeScanWidgets, jumps, onJump, fonts);
                    }
                }, navigator, config);
        analyzeScanController.bind();
    }

    /**
     * 「调用链查询」页的控制器：持有已释放的脚本与已构建的数据库路径，
     * 因此只在首次进页时创建，否则每次切页都要重新释放一次脚本资源。
     */
    private void ensureAnalyzeChain() {
        if (analyzeChainController != null) return;
        analyzeChainWidgets.onCopy = copyAction(analyzeChainWidgets.output, analyzeChainWidgets.status);
        analyzeChainController = new AnalyzeController(analyzeChainWidgets, fonts,
                new AnalyzeWorker.View() {
                    @Override public void setStatus(String text) {
                        analyzeChainWidgets.status.setText(text);
                    }

                    @Override public void setOutput(String text) {
                        analyzeChainWidgets.output.setText(text);
                        // 报告开头是最重要的部分（结论与绕过手法），显式回到起点
                        analyzeChainWidgets.output.setCaretPosition(0);
                    }

                    @Override public void setBusy(boolean busy) {
                        analyzeChainWidgets.progress.setVisible(busy);
                        analyzeChainWidgets.runEngine.setEnabled(!busy);
                        analyzeChainWidgets.query.setEnabled(!busy);
                        analyzeChainWidgets.signatures.setEnabled(!busy);
                        analyzeChainWidgets.decompile.setEnabled(!busy);
                    }

                    @Override public void showJumps(java.util.Map<String, String> jumps,
                                                    java.util.function.Consumer<String> onJump) {
                        AnalyzeController.fillJumps(analyzeChainWidgets, jumps, onJump, fonts);
                    }
                }, navigator, config);
        analyzeChainController.bind();
    }

    /** 复制报告：把报告区内容整段送进剪贴板，并把光标复位，避免下一次复制只带选中片段。 */
    private static Runnable copyAction(final javax.swing.JTextArea area, final javax.swing.JLabel status) {
        return () -> {
            area.selectAll();
            area.copy();
            area.setSelectionStart(0);
            area.setSelectionEnd(0);
            status.setText("报告已复制到剪贴板");
        };
    }

    /**
     * 恶意服务器页：控制器持有 java-chains 的服务生命周期，
     * 重建控制器等于把已经监听端口的服务丢掉，界面上再也停不掉它们。
     */
    public JPanel servers() {
        ensureServers();
        JPanel panel = ServicePage.build(serviceWidgets, fonts);
        // 进页时才消费预设页交来的链：此前控件还没挂上窗口，写进去看不到效果
        serviceController.consumeTask();
        return panel;
    }

    /**
     * 把配置页里的生成默认值下发到控件。
     *
     * <p>每次进页都下发：使用者在配置页改完默认编码后回到本页应当立刻生效，
     * 只在创建控制器时下发一次会让改动看起来「没保存成功」。
     */
    private void applyPayloadDefaults() {
        payload.PayloadCodec.Option option =
                payload.PayloadCodec.Option.of(config.property("payload_encode", "base64"));
        javax.swing.JToggleButton button = payloadWidgets.encode.get(option);
        if (button != null) button.setSelected(true);
        payloadWidgets.urlEncode.setSelected(flag(config.property("payload_url_encode", "false")));
        payloadWidgets.autoCopy.setSelected(flag(config.property("payload_auto_copy", "false")));
        payloadWidgets.autoBuild.setSelected(flag(config.property("payload_auto_build", "false")));
        payloadWidgets.autoExpand.setSelected(flag(config.property("payload_auto_expand", "false")));
        payloadWidgets.hoverSelect.setSelected(flag(config.property("payload_hover_select", "false")));
        payloadSelector.setHoverSelect(payloadWidgets.hoverSelect.isSelected());
    }

    private static boolean flag(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(value) || "1".equals(value) || "yes".equals(value);
    }

    /** 退出前停掉全部服务并释放端口；失败也不阻塞退出，避免关不掉窗口。 */
    public void shutdown() {
        if (analyzeScanController != null) analyzeScanController.shutdown();
        if (analyzeChainController != null) analyzeChainController.shutdown();
        if (oobJarController != null) oobJarController.shutdown();
        if (serviceController == null) return;
        try {
            serviceController.shutdown();
        } catch (RuntimeException failed) {
            // 关闭阶段的异常不该拦住退出，但要留痕：否则表现为「端口没释放」
            util.Log.warn("关闭恶意服务器失败：" + failed.getMessage(), failed);
        }
    }

    /** 预设链页交来的任务：先建控制器，写入任务，再跳到服务器页消费。 */
    private void sendTaskToServers(String payloadId, List<String> gadgets, Map<String, Object> params) {
        ensureServers();
        serviceController.setTask(payloadId, gadgets, params);
        open("service.servers");
        serviceController.consumeTask();
    }

    private void ensureServers() {
        if (serviceController != null) return;
        serviceWidgets.onSendToCapture = address -> {
            capture.url.setText(address);
            open("capture");
        };
        serviceController = new ServiceController(serviceWidgets, fonts, new ServiceController.View() {
            @Override public void setStatus(String text) { serviceWidgets.status.setText(text); }

            @Override public void setOutput(String text) { serviceWidgets.output.setText(text); }

            @Override public void appendOutput(String text) {
                serviceWidgets.output.append(text);
                if (!text.endsWith(System.lineSeparator())) {
                    serviceWidgets.output.append(System.lineSeparator());
                }
                serviceWidgets.output.setCaretPosition(serviceWidgets.output.getDocument().getLength());
            }
        }, config.serverDefaults());
    }

    private void open(String key) {
        if (navigator != null) navigator.accept(key);
    }

    // ------------------------------------------------------------------
    // 配置下发：只作用于已创建的控制器，没进过页的页面进页时自会取默认值
    // ------------------------------------------------------------------

    @Override
    public void applyServiceDefaults(ServiceDefaults defaults) {
        if (serviceController != null) serviceController.applyDefaults(defaults);
    }

    @Override
    public void applyPresetCategory(String category) {
        if (presetController != null) presetController.applyDefaultCategory(category);
    }
}
