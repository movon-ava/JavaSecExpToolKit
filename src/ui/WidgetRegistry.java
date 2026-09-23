package ui;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;

/**
 * 自检门面的登记表：把界面层持有的控件按稳定名字登记下来（见 {@link UiHandle}）。
 *
 * <p>从组合根抽出来独立成类，原因是这份清单与「装配」无关，只与「自检要看什么」有关：
 * 它随断言增长而增长（当前 90 余项），留在组合根会让主窗口文件被断言清单淹没，
 * 而它本身没有任何行为，只是名字到控件的映射。
 *
 * <p>可变的部分（展开后的导航序列、列表本身）用取值器登记：直接登记对象会把那一刻的
 * 快照固化下来，展开 / 收起之后断言读到的就是过期数据。
 */
public final class WidgetRegistry {

    /** 窗口与内容区：自检用它截图与遍历标签。 */
    public JFrame frame;
    public JPanel content;
    /** 导航列表与当前扁平序列：序列每次展开 / 收起都会换对象，因此用取值器。 */
    public JList<NavItem> navigationList;
    public Supplier<List<NavItem>> navigationItems;

    public ProbePage.Widgets probe;
    public CapturePage.Widgets capture;
    public ProxyPage.Widgets proxy;
    public ShiroPage.Widgets shiro;
    public ToolsUploadPage.Widgets toolsUpload;
    public ConfigForm configForm;

    public PayloadPage.Widgets payload;
    /** 列式链选择器：载荷生成页的选链主体，自检直接读它的列与选中项。 */
    public PayloadChainSelector payloadSelector;
    public PresetPage.Widgets preset;
    public ServicePage.Widgets service;
    public PayloadToStringPage.Widgets tostring;
    public OobJarPage.Widgets oobJar;
    /** 漏洞分析 · 组件与漏洞页：自检直接读它的目标框、按钮与报告区。 */
    public AnalyzeScanPage.Widgets analyzeScan;
    /** 漏洞分析 · 调用链查询页：控件与上一页完全独立，自检分别断言。 */
    public AnalyzeChainPage.Widgets analyzeChain;

    /** 构建登记表：名字与控件一一对应，顺序稳定，便于对照断言排查。 */
    public Map<String, Object> build() {
        Map<String, Object> entries = UiHandle.registry();
        entries.put("frame", frame);
        entries.put("content", content);
        entries.put("navigationList", navigationList);
        entries.put("navigationItems", navigationItems);
        entries.put("payloadWidgets", payload);
        entries.put("payloadSelector", payloadSelector);
        entries.put("payloadOutput", payload.output);
        entries.put("payloadOutputSize", payload.outputSize);
        entries.put("payloadChainMeta", payload.chainMeta);
        entries.put("payloadContext", payload.contextList);
        entries.put("payloadContextDetail", payload.contextDetail);
        entries.put("payloadCopyContext", payload.copyContext);
        entries.put("payloadExpand", payload.expand);
        entries.put("payloadBuildDebug", payload.buildDebug);
        entries.put("payloadUrlEncode", payload.urlEncode);
        entries.put("payloadAutoCopy", payload.autoCopy);
        entries.put("payloadAutoBuild", payload.autoBuild);
        entries.put("payloadFileName", payload.fileName);
        entries.put("payloadEdit", payload.edit);
        entries.put("payloadContextTitle", payload.contextTitle);
        entries.put("payloadContextSearch", payload.contextSearch);
        entries.put("payloadChainChips", payload.chainChips);
        entries.put("payloadAutoExpand", payload.autoExpand);
        entries.put("payloadHoverSelect", payload.hoverSelect);
        for (java.util.Map.Entry<payload.PayloadCodec.Option, javax.swing.JToggleButton> entry
                : payload.encode.entrySet()) {
            entries.put("payloadEncode-" + entry.getKey().id, entry.getValue());
        }
        entries.put("presetWidgets", preset);
        entries.put("serviceWidgets", service);
        entries.put("tostringWidgets", tostring);
        entries.put("tostringTemplateList", tostring.templateList);
        entries.put("tostringTitle", tostring.title);
        entries.put("tostringMeta", tostring.meta);
        entries.put("tostringSummary", tostring.summary);
        entries.put("tostringSteps", tostring.steps);
        entries.put("tostringTargetClass", tostring.targetClass);
        entries.put("tostringCommand", tostring.command);
        entries.put("tostringBuild", tostring.build);
        entries.put("tostringCopyTemplate", tostring.copyTemplate);
        entries.put("tostringCopy", tostring.copy);
        entries.put("tostringToCapture", tostring.toCapture);
        entries.put("tostringStatus", tostring.status);
        entries.put("tostringOutput", tostring.output);
        entries.put("oobJarWidgets", oobJar);
        entries.put("oobJarKind", oobJar.kindCombo);
        entries.put("oobJarAction", oobJar.actionCombo);
        entries.put("oobJarActionHint", oobJar.actionHint);
        entries.put("oobJarUrl", oobJar.url);
        entries.put("oobJarCommand", oobJar.command);
        entries.put("oobJarPath", oobJar.path);
        entries.put("oobJarTargetClass", oobJar.targetClass);
        entries.put("oobJarClassNamePrefix", oobJar.classNamePrefix);
        entries.put("oobJarBindHost", oobJar.bindHost);
        entries.put("oobJarPort", oobJar.port);
        entries.put("oobJarExecutable", oobJar.executable);
        entries.put("oobJarHost", oobJar.host);
        entries.put("oobJarStop", oobJar.stop);
        entries.put("oobJarCopyUrl", oobJar.copyUrl);
        entries.put("oobJarStatus", oobJar.status);
        entries.put("oobJarOutput", oobJar.output);
        if (analyzeScan != null) {
            entries.put("analyzeTarget", analyzeScan.target);
            entries.put("analyzeChooseTarget", analyzeScan.chooseTarget);
            entries.put("analyzePom", analyzeScan.pomPath);
            entries.put("analyzeLocal", analyzeScan.run);
            entries.put("analyzeCopy", analyzeScan.copy);
            entries.put("analyzeStatus", analyzeScan.status);
            entries.put("analyzeOutput", analyzeScan.output);
            entries.put("analyzeJumps", analyzeScan.jumps);
        }
        if (analyzeChain != null) {
            entries.put("analyzeChainTarget", analyzeChain.target);
            entries.put("analyzeRunEngine", analyzeChain.runEngine);
            entries.put("analyzeQuick", analyzeChain.quickMode);
            entries.put("analyzeInnerJars", analyzeChain.innerJars);
            entries.put("analyzeTimeout", analyzeChain.timeoutSeconds);
            entries.put("analyzeQueryKind", analyzeChain.queryKind);
            entries.put("analyzeKeyword", analyzeChain.keyword);
            entries.put("analyzeQuery", analyzeChain.query);
            entries.put("analyzeSignatures", analyzeChain.signatures);
            entries.put("analyzeMinSeverity", analyzeChain.minSeverity);
            entries.put("analyzeClassName", analyzeChain.className);
            entries.put("analyzeOutputDir", analyzeChain.outputDir);
            entries.put("analyzeDecompile", analyzeChain.decompile);
            entries.put("analyzeOpenOutput", analyzeChain.openOutput);
            entries.put("analyzeChainCopy", analyzeChain.copy);
            entries.put("analyzeChainStatus", analyzeChain.status);
            entries.put("analyzeChainOutput", analyzeChain.output);
            entries.put("analyzeChainJumps", analyzeChain.jumps);
        }
        entries.put("target", probe.target);
        entries.put("timeout", probe.timeout);
        entries.put("modeDetect", probe.modeDetect);
        entries.put("modeVersion", probe.modeVersion);
        entries.put("modeExpect", probe.modeExpect);
        entries.put("dnsEnabled", probe.dnsEnabled);
        entries.put("ceyeEnabled", probe.ceyeEnabled);
        entries.put("probeMethod", probe.probeMethod);
        entries.put("baseBody", probe.baseBody);
        entries.put("requestHeaders", probe.requestHeaders);
        entries.put("sessionCookie", probe.sessionCookie);
        entries.put("dnslogHost", probe.dnslogHost);
        entries.put("dnsFilter", probe.dnsFilter);
        entries.put("dnsWait", probe.dnsWait);
        entries.put("detect", probe.detect);
        entries.put("status", probe.status);
        entries.put("result", probe.result);

        entries.put("captureUrl", capture.url);
        entries.put("captureMethod", capture.method);
        entries.put("captureContentType", capture.contentType);
        entries.put("captureHeaders", capture.headers);
        entries.put("captureBody", capture.body);
        entries.put("captureTarget", capture.convertTarget);
        entries.put("pastedRequest", capture.pastedRequest);
        entries.put("captureResult", capture.result);
        entries.put("captureRun", capture.run);
        entries.put("captureConvert", capture.convert);
        entries.put("captureCopy", capture.copy);
        entries.put("captureToProbe", capture.toProbe);
        entries.put("captureSendTo", capture.sendTo);
        entries.put("captureSend", capture.send);
        entries.put("captureStatus", capture.status);

        entries.put("proxyBindHost", proxy.bindHost);
        entries.put("proxyPort", proxy.port);
        entries.put("proxyToggle", proxy.toggle);
        entries.put("proxyClear", proxy.clear);
        entries.put("proxyExport", proxy.export);
        entries.put("proxyIntercept", proxy.intercept);
        entries.put("proxyForward", proxy.forward);
        entries.put("proxyDrop", proxy.drop);
        entries.put("proxyStatus", proxy.status);
        entries.put("proxyRequestText", proxy.requestText);
        entries.put("proxyResponseText", proxy.responseText);

        entries.put("shiroUrl", shiro.url);
        entries.put("shiroRequestMethod", shiro.requestMethod);
        entries.put("shiroCookieName", shiro.cookieName);
        entries.put("shiroKey", shiro.key);
        entries.put("shiroGcm", shiro.gcm);
        entries.put("shiroChain", shiro.chain);
        entries.put("shiroEchoHeader", shiro.echoHeader);
        entries.put("shiroCommand", shiro.command);
        entries.put("shiroHeaders", shiro.headers);
        entries.put("shiroBody", shiro.body);
        entries.put("shiroDetect", shiro.detect);
        entries.put("shiroCrack", shiro.crack);
        entries.put("shiroStop", shiro.stop);
        entries.put("shiroBuild", shiro.build);
        entries.put("shiroRun", shiro.run);
        entries.put("shiroProgress", shiro.progress);
        entries.put("shiroStatus", shiro.status);
        entries.put("shiroOutputTabs", shiro.outputTabs);
        entries.put("shiroDetectOutput", shiro.detectOutput);
        entries.put("shiroCrackOutput", shiro.crackOutput);
        entries.put("shiroBuildOutput", shiro.buildOutput);
        entries.put("shiroRunOutput", shiro.runOutput);

        entries.put("toolsUploadUrl", toolsUpload.url);
        entries.put("toolsUploadFile", toolsUpload.filePath);
        entries.put("toolsUploadChoose", toolsUpload.choose);
        entries.put("toolsUploadField", toolsUpload.field);
        entries.put("toolsUploadFields", toolsUpload.fields);
        entries.put("toolsUploadHeaders", toolsUpload.headers);
        entries.put("toolsUploadRun", toolsUpload.run);
        entries.put("toolsUploadCopy", toolsUpload.copy);
        entries.put("toolsUploadStatus", toolsUpload.status);
        entries.put("toolsUploadResult", toolsUpload.result);

        entries.put("configStatus", configForm.status);
        entries.put("configCeyeDomain", configForm.ceyeDomain);
        entries.put("configCeyeToken", configForm.ceyeToken);
        entries.put("configPython", configForm.python);
        entries.put("configTimeout", configForm.timeout);
        entries.put("configProbeMethod", configForm.probeMethod);
        entries.put("configDnsWait", configForm.dnsWait);
        entries.put("configBaseBody", configForm.baseBody);
        entries.put("configHeaders", configForm.headers);
        entries.put("configSessionCookie", configForm.sessionCookie);
        entries.put("configDnslogHost", configForm.dnslogHost);
        entries.put("configDnsFilter", configForm.dnsFilter);
        entries.put("configProbeReport", configForm.probeReport);
        entries.put("configProxyBindHost", configForm.proxyBindHost);
        entries.put("configProxyPort", configForm.proxyPort);
        entries.put("configProxyIntercept", configForm.proxyIntercept);
        entries.put("configCaptureMethod", configForm.captureMethod);
        entries.put("configCaptureContentType", configForm.captureContentType);
        entries.put("configCaptureTarget", configForm.captureTarget);
        entries.put("configShiroUrl", configForm.shiroUrl);
        entries.put("configShiroCookieName", configForm.shiroCookieName);
        entries.put("configShiroKey", configForm.shiroKey);
        entries.put("configShiroGcm", configForm.shiroGcm);
        entries.put("configShiroEchoHeader", configForm.shiroEchoHeader);
        entries.put("configShiroChain", configForm.shiroChain);
        entries.put("configShiroCommand", configForm.shiroCommand);
        entries.put("configShiroBody", configForm.shiroBody);
        entries.put("configPayloadExportDir", configForm.payloadExportDir);
        entries.put("configPayloadEncode", configForm.payloadEncode);
        entries.put("configPayloadUrlEncode", configForm.payloadUrlEncode);
        entries.put("configPayloadAutoCopy", configForm.payloadAutoCopy);
        entries.put("configPayloadAutoBuild", configForm.payloadAutoBuild);
        entries.put("configPayloadAutoExpand", configForm.payloadAutoExpand);
        entries.put("configPayloadHoverSelect", configForm.payloadHoverSelect);
        entries.put("configPresetCategory", configForm.presetCategory);
        entries.put("configTostringTemplate", configForm.tostringTemplate);
        entries.put("configTostringCommand", configForm.tostringCommand);
        entries.put("configOobJarBindHost", configForm.oobjarBindHost);
        entries.put("configOobJarPort", configForm.oobjarPort);
        entries.put("configOobJarDefaultUrl", configForm.oobjarDefaultUrl);
        entries.put("configOobJarDefaultPath", configForm.oobjarDefaultPath);
        entries.put("configOobJarDefaultCommand", configForm.oobjarDefaultCommand);
        entries.put("configAnalyzeScanTarget", configForm.analyzeScanTarget);
        entries.put("configAnalyzeEngineJar", configForm.analyzeEngineJar);
        entries.put("configAnalyzeWorkDir", configForm.analyzeWorkDir);
        entries.put("configAnalyzeTimeout", configForm.analyzeTimeout);
        entries.put("configAnalyzeDecompileDir", configForm.analyzeDecompileDir);
        entries.put("configAnalyzeGadgetRules", configForm.analyzeGadgetRules);
        entries.put("configLogEnabled", configForm.logEnabled);
        entries.put("configLogLevel", configForm.logLevel);
        entries.put("configLogDir", configForm.logDir);
        entries.put("configLogKeepDays", configForm.logKeepDays);
        entries.put("configLogConsole", configForm.logConsole);
        entries.put("configUploadUrl", configForm.uploadUrl);
        entries.put("configUploadField", configForm.uploadField);
        entries.put("configUploadTimeout", configForm.uploadTimeout);
        entries.put("configServerBindHost", configForm.serverBindHost);
        entries.put("configServerAdvertiseHost", configForm.serverAdvertiseHost);
        entries.put("configServerJndiLdap", configForm.serverJndiLdap);
        entries.put("configServerJndiRmi", configForm.serverJndiRmi);
        entries.put("configServerJndiHttp", configForm.serverJndiHttp);
        entries.put("configServerHttp", configForm.serverHttp);
        entries.put("configServerJrmp", configForm.serverJrmp);
        entries.put("configServerMysql", configForm.serverMysql);
        entries.put("configServerTcp", configForm.serverTcp);
        return entries;
    }
}
