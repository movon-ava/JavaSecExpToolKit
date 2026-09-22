import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;

/**
 * 界面自检：验证侧边栏分类可下拉展开 / 收起，并输出各页面离屏截图。
 *
 * 用法：java -cp target\classes UiNavigationCheck
 */
public final class UiNavigationCheck {

    public static void main(String[] args) throws Exception {
        // 载荷构建会真实执行 Exec / Clojure 节点里的命令（上游默认值是 calc）：
        // 本自检点过生成按钮，退出时必须把弹出的计算器进程收掉
        TestProcessGuard.install("UiNavigationCheck");
        // 自检会真实写配置（启动代理会记住监听端口、保存探测报告详细度）：
        // 先把 user.home 指向临时目录，避免污染使用者真实的 config.properties。
        java.io.File isolatedHome = java.nio.file.Files.createTempDirectory("javasec-ui-check").toFile();
        isolatedHome.deleteOnExit();
        System.setProperty("user.home", isolatedHome.getAbsolutePath());

        final Object main = newMain();
        final JFrame frame = (JFrame) field(main, "frame");
        onEdt(new Runnable() {
            public void run() { frame.setVisible(true); }
        });
        Thread.sleep(400);

        final JList<?> list = (JList<?>) field(main, "navigationList");
        List<?> collapsed = (List<?>) field(main, "navigationItems");
        System.out.println("初始导航项: " + labels(collapsed));
        // 一级分类顺序对齐网页版 java-chains，并在其后追加本项目自有的「漏洞分析」与「小工具」。
        check("初始应为 8 项（主页/Payload/服务/代理/FastJson/漏洞分析/小工具/配置）",
                collapsed.size() == 8);
        check("初始第二项为 Payload 分类", labels(collapsed).get(1).startsWith("Payload"));
        check("初始含服务分类", labels(collapsed).get(2).startsWith("服务"));
        check("初始含代理分类", labels(collapsed).get(3).startsWith("代理"));
        check("初始含 FastJson 分类", labels(collapsed).get(4).startsWith("FastJson"));
        check("初始含漏洞分析分类", labels(collapsed).get(5).startsWith("漏洞分析"));
        check("初始含小工具分类", labels(collapsed).get(6).startsWith("小工具"));
        check("末项为配置", "配置".equals(labels(collapsed).get(7)));
        check("初始各分类都显示收起箭头",
                labels(collapsed).get(1).endsWith("\u25b8")
                        && labels(collapsed).get(2).endsWith("\u25b8")
                        && labels(collapsed).get(3).endsWith("\u25b8")
                        && labels(collapsed).get(4).endsWith("\u25b8")
                        && labels(collapsed).get(5).endsWith("\u25b8")
                        && labels(collapsed).get(6).endsWith("\u25b8"));
        check("展开箭头位于文字右侧",
                labels(collapsed).get(4).indexOf("\u25b8") > labels(collapsed).get(4).indexOf("FastJson"));

        select(main, "fastjson");
        toggle(main);
        List<?> expanded = (List<?>) field(main, "navigationItems");
        System.out.println("展开后导航项: " + labels(expanded));
        check("展开后应为 9 项（含 Fastjson 探测）", expanded.size() == 9);
        check("展开后 FastJson 应显示展开箭头", labels(expanded).get(4).endsWith("\u25be"));
        check("展开后子项紧跟其分类", "Fastjson 探测".equals(labels(expanded).get(5)));
        System.out.println("展开后选中项: " + displayFor(list.getSelectedValue()));
        snapshot(frame, "target/ui-check/01-expanded.png");

        toggle(main);
        List<?> recollapsed = (List<?>) field(main, "navigationItems");
        System.out.println("收起后导航项: " + labels(recollapsed));
        check("再次点击应收起回 8 项", recollapsed.size() == 8);
        check("收起后 FastJson 应显示收起箭头", labels(recollapsed).get(4).endsWith("\u25b8"));

        select(main, "fastjson.detect");
        List<?> afterJump = (List<?>) field(main, "navigationItems");
        System.out.println("跳转探测页后: " + labels(afterJump));
        check("跳转二级功能应自动展开分类", afterJump.size() == 9);
        check("跳转后选中二级功能", "Fastjson 探测".equals(plain(list.getSelectedValue())));

        check("识别默认勾选", ((AbstractButton) field(main, "modeDetect")).isSelected());
        check("版本识别默认勾选", ((AbstractButton) field(main, "modeVersion")).isSelected());
        check("期望类默认勾选", ((AbstractButton) field(main, "modeExpect")).isSelected());
        check("DNS 探针并入探测模式且默认勾选", ((AbstractButton) field(main, "dnsEnabled")).isSelected());
        check("CEYE 确认并入探测模式且默认勾选", ((AbstractButton) field(main, "ceyeEnabled")).isSelected());
        check("模式文字不带“启用”前缀",
                "Fastjson 识别".equals(((AbstractButton) field(main, "modeDetect")).getText())
                        && "版本识别".equals(((AbstractButton) field(main, "modeVersion")).getText())
                        && "期望类".equals(((AbstractButton) field(main, "modeExpect")).getText())
                        && "DNS 探针".equals(((AbstractButton) field(main, "dnsEnabled")).getText())
                        && "CEYE 确认".equals(((AbstractButton) field(main, "ceyeEnabled")).getText()));
        check("默认五个模式全选",
                modeKeys(main).equals(java.util.Arrays.asList("detect", "version", "expect", "dns", "ceye")));
        check("探测页含请求方法下拉框", field(main, "probeMethod") instanceof JComboBox);
        check("探测方法默认为 POST", "POST".equals(String.valueOf(((JComboBox<?>) field(main, "probeMethod")).getSelectedItem())));
        check("期望类勾选时业务参数框可用", ((JTextField) field(main, "baseBody")).isEnabled());
        check("默认勾选时 DNSLog 主机输入框可用", ((JTextField) field(main, "dnslogHost")).isEnabled());
        check("默认勾选时 DNS 等待输入框可用", ((JTextField) field(main, "dnsWait")).isEnabled());
        check("默认勾选时 CEYE Filter 输入框可用", ((JTextField) field(main, "dnsFilter")).isEnabled());
        check("默认勾选时请求头输入框可用且为空", ((JTextField) field(main, "requestHeaders")).isEnabled()
                && ((JTextField) field(main, "requestHeaders")).getText().isEmpty());
        snapshot(frame, "target/ui-check/02-probe.png");

        swing(main, new Runnable() {
            public void run() {
                ((AbstractButton) fieldQuiet(main, "dnsEnabled")).doClick();
            }
        });
        check("取消 DNS 探针后 DNSLog 主机输入框禁用", !((JTextField) fieldQuiet(main, "dnslogHost")).isEnabled());
        check("取消 DNS 探针后 DNS 等待输入框禁用", !((JTextField) fieldQuiet(main, "dnsWait")).isEnabled());
        check("取消 DNS 探针不影响 CEYE Filter", ((JTextField) fieldQuiet(main, "dnsFilter")).isEnabled());

        swing(main, new Runnable() {
            public void run() { ((AbstractButton) fieldQuiet(main, "ceyeEnabled")).doClick(); }
        });
        check("CEYE 确认可取消勾选", !((AbstractButton) fieldQuiet(main, "ceyeEnabled")).isSelected());
        check("取消 CEYE 确认后其输入框禁用", !((JTextField) fieldQuiet(main, "dnsFilter")).isEnabled());

        swing(main, new Runnable() {
            public void run() { ((AbstractButton) fieldQuiet(main, "modeExpect")).doClick(); }
        });
        check("取消期望类后业务参数框禁用", !((JTextField) fieldQuiet(main, "baseBody")).isEnabled());

        check("取消三项后仅剩识别与版本模式",
                modeKeys(main).equals(java.util.Arrays.asList("detect", "version")));

        // 点击 Shiro 二级项必须打开 Shiro 页，而不是回落到主页
        select(main, "shiro.exploit");
        List<String> shiroTexts = contentLabels(main);
        System.out.println("Shiro 页标题: " + shiroTexts);
        check("跳转 Shiro 二级项打开 Shiro 页", shiroTexts.contains("Shiro 漏洞利用"));
        check("跳转 Shiro 二级项不回落到主页", !shiroTexts.contains("安全测试工具箱"));
        check("Shiro 页选中项正确", "Shiro 漏洞利用".equals(plain(list.getSelectedValue())));
        // 表单约 480px 高：按钮与输出区必须留在可视区内，否则页面等于不可用
        check("Shiro 页操作按钮可见", ((javax.swing.AbstractButton) fieldQuiet(main, "shiroDetect")).isShowing());
        check("Shiro 页输出区有可用高度",
                ((javax.swing.JTextArea) fieldQuiet(main, "shiroDetectOutput")).getParent().getHeight() > 100);
        check("Shiro 四个功能各有独立回显框",
                fieldQuiet(main, "shiroCrackOutput") instanceof JTextArea
                        && fieldQuiet(main, "shiroBuildOutput") instanceof JTextArea
                        && fieldQuiet(main, "shiroRunOutput") instanceof JTextArea);

        select(main, "config");
        check("跳转配置页不回落到主页", !contentLabels(main).contains("安全测试工具箱"));
        check("配置页含默认请求头输入框", fieldQuiet(main, "configHeaders") instanceof JTextField);
        // 配置页要把各功能可长期保存的参数都收进来，否则代理端口、Shiro 密钥这类
        // 每次都要重填的默认值只能写在代码里。
        List<String> configTexts = contentLabels(main);
        System.out.println("配置页分组: " + configTexts);
        check("配置页含探测报告配置分组", configTexts.contains("探测报告配置"));
        check("配置页含代理配置分组", configTexts.contains("代理配置"));
        check("配置页含抓包转换配置分组", configTexts.contains("抓包转换配置"));
        check("配置页含 Shiro 配置分组", configTexts.contains("Shiro 配置"));
        check("配置页含 Payload 生成配置分组", configTexts.contains("Payload 生成配置"));
        check("配置页含 Payload 默认导出目录输入框",
                fieldQuiet(main, "configPayloadExportDir") instanceof JTextField);
        // 生成页上的每一项可持久化默认值都必须能在配置页改到：只有写进配置页，
        // 使用者才不用每次进页重设。漏一项就等于「页面上的开关只能靠手点」。
        check("配置页含 Payload 默认编码下拉框",
                fieldQuiet(main, "configPayloadEncode") instanceof JComboBox);
        check("配置页含 Payload URL 编码 / 自动复制 / 自动生成勾选框",
                fieldQuiet(main, "configPayloadUrlEncode") instanceof AbstractButton
                        && fieldQuiet(main, "configPayloadAutoCopy") instanceof AbstractButton
                        && fieldQuiet(main, "configPayloadAutoBuild") instanceof AbstractButton);
        check("配置页含 Payload 展开载荷 / 悬停选链勾选框",
                fieldQuiet(main, "configPayloadAutoExpand") instanceof AbstractButton
                        && fieldQuiet(main, "configPayloadHoverSelect") instanceof AbstractButton);
        check("配置页 Payload 默认编码含四档",
                ((JComboBox<?>) fieldQuiet(main, "configPayloadEncode")).getItemCount() == 4);
        check("配置页含报告详细度下拉框", fieldQuiet(main, "configProbeReport") instanceof JComboBox);
        check("报告详细度默认精简",
                "精简".equals(String.valueOf(((JComboBox<?>) fieldQuiet(main, "configProbeReport")).getSelectedItem())));
        check("配置页含代理监听地址输入框", fieldQuiet(main, "configProxyBindHost") instanceof JTextField);
        check("配置页含代理监听端口输入框", fieldQuiet(main, "configProxyPort") instanceof JTextField);
        check("代理监听端口默认为 8899", "8899".equals(((JTextField) fieldQuiet(main, "configProxyPort")).getText()));
        check("配置页含代理拦截勾选框", fieldQuiet(main, "configProxyIntercept") instanceof AbstractButton);
        check("配置页含抓包默认方法下拉框", fieldQuiet(main, "configCaptureMethod") instanceof JComboBox);
        check("配置页含抓包默认 Content-Type 输入框",
                fieldQuiet(main, "configCaptureContentType") instanceof JTextField);
        check("配置页含抓包默认转换目标下拉框", fieldQuiet(main, "configCaptureTarget") instanceof JComboBox);
        check("配置页含 Shiro 目标 URL 输入框", fieldQuiet(main, "configShiroUrl") instanceof JTextField);
        check("配置页含 Shiro Cookie 名输入框", fieldQuiet(main, "configShiroCookieName") instanceof JTextField);
        check("配置页含 Shiro 密钥输入框", fieldQuiet(main, "configShiroKey") instanceof JTextField);
        check("配置页含 Shiro GCM 勾选框", fieldQuiet(main, "configShiroGcm") instanceof AbstractButton);
        check("配置页含 Shiro 回显请求头输入框", fieldQuiet(main, "configShiroEchoHeader") instanceof JTextField);
        check("配置页含 Shiro 利用链下拉框", fieldQuiet(main, "configShiroChain") instanceof JComboBox);
        check("配置页含 Shiro 命令输入框", fieldQuiet(main, "configShiroCommand") instanceof JTextField);
        check("配置页含 Shiro 请求体输入框", fieldQuiet(main, "configShiroBody") instanceof JTextField);
        check("配置页含恶意服务器配置分组", configTexts.contains("恶意服务器配置"));
        check("配置页含预设链配置分组", configTexts.contains("预设链配置"));
        check("配置页含恶意服务器绑定地址输入框",
                fieldQuiet(main, "configServerBindHost") instanceof JTextField);
        check("配置页含恶意服务器公布地址输入框",
                fieldQuiet(main, "configServerAdvertiseHost") instanceof JTextField);
        check("配置页含五类服务端口输入框",
                fieldQuiet(main, "configServerJndiLdap") instanceof JTextField
                        && fieldQuiet(main, "configServerJndiRmi") instanceof JTextField
                        && fieldQuiet(main, "configServerJndiHttp") instanceof JTextField
                        && fieldQuiet(main, "configServerHttp") instanceof JTextField
                        && fieldQuiet(main, "configServerJrmp") instanceof JTextField
                        && fieldQuiet(main, "configServerMysql") instanceof JTextField
                        && fieldQuiet(main, "configServerTcp") instanceof JTextField);
        check("恶意服务器端口默认值与上游一致",
                "50389".equals(((JTextField) fieldQuiet(main, "configServerJndiLdap")).getText())
                        && "50000".equals(((JTextField) fieldQuiet(main, "configServerHttp")).getText())
                        && "3308".equals(((JTextField) fieldQuiet(main, "configServerMysql")).getText()));
        check("配置页含预设链默认分类下拉框",
                fieldQuiet(main, "configPresetCategory") instanceof JComboBox);
        check("预设链默认分类含「全部分类」",
                "全部分类".equals(String.valueOf(
                        ((JComboBox<?>) fieldQuiet(main, "configPresetCategory")).getSelectedItem())));
        snapshot(frame, "target/ui-check/03-config.png");

        select(main, "capture");
        // 代理 / FastJson / Shiro 三个分类此前都已展开（5 个一级项 + 2 + 1 + 1）
        System.out.println("跳转抓包页后: " + labels(navItems(main)));
        check("抓包转换已并入代理分类且跳转会展开", labels(navItems(main)).contains("抓包转换")
                && labels(navItems(main)).size() == expectedNavSize());
        check("抓包页含目标 URL 输入框", fieldQuiet(main, "captureUrl") instanceof JTextField);
        check("抓包页含请求方法下拉框", fieldQuiet(main, "captureMethod") instanceof JComboBox);
        check("抓包页含请求头输入框", fieldQuiet(main, "captureHeaders") instanceof JTextField);
        check("抓包页含请求体文本域", fieldQuiet(main, "captureBody") instanceof JTextArea);
        check("抓包页含粘贴原始请求文本域", fieldQuiet(main, "pastedRequest") instanceof JTextArea);
        check("抓包页含转换目标下拉框", fieldQuiet(main, "captureTarget") instanceof JComboBox);
        check("抓包页含抓包按钮", fieldQuiet(main, "captureRun") instanceof JButton);
        check("抓包页含转换按钮", fieldQuiet(main, "captureConvert") instanceof JButton);
        check("抓包页含填入探测页按钮", fieldQuiet(main, "captureToProbe") instanceof JButton);
        check("抓包页含一键发送目标下拉框", fieldQuiet(main, "captureSendTo") instanceof JComboBox);
        check("抓包页含一键发送按钮", fieldQuiet(main, "captureSend") instanceof JButton);
        check("一键发送目标含 Fastjson 与 Shiro",
                comboOptions(main, "captureSendTo").contains("Fastjson 探测")
                        && comboOptions(main, "captureSendTo").contains("Shiro 漏洞利用"));
        snapshot(frame, "target/ui-check/05-capture.png");

        // 上一步跳转抓包页时已展开代理分类；这里验证跳二级项会保持展开并选中
        select(main, "proxy.mitm");
        check("代理分类保持展开且含两个二级项", labels(navItems(main)).contains("代理抓包")
                && labels(navItems(main)).contains("抓包转换"));
        check("代理抓包为选中项", "代理抓包".equals(plain(list.getSelectedValue())));
        check("代理页含监听地址输入框", fieldQuiet(main, "proxyBindHost") instanceof JTextField);
        check("代理监听地址默认非空", !((JTextField) fieldQuiet(main, "proxyBindHost")).getText().trim().isEmpty());
        check("代理页含监听端口输入框", fieldQuiet(main, "proxyPort") instanceof JTextField);
        check("代理页含启动按钮", fieldQuiet(main, "proxyToggle") instanceof JButton);
        check("代理页含清空记录按钮", fieldQuiet(main, "proxyClear") instanceof JButton);
        check("代理页含转发到抓包转换按钮", fieldQuiet(main, "proxyExport") instanceof JButton);
        check("代理页含拦截请求勾选框", fieldQuiet(main, "proxyIntercept") instanceof AbstractButton);
        check("代理页含放行按钮", fieldQuiet(main, "proxyForward") instanceof JButton);
        check("代理页含丢弃按钮", fieldQuiet(main, "proxyDrop") instanceof JButton);
        check("代理页含请求包文本域", fieldQuiet(main, "proxyRequestText") instanceof JTextArea);
        check("代理页含返回包文本域", fieldQuiet(main, "proxyResponseText") instanceof JTextArea);
        check("代理页含状态标签", fieldQuiet(main, "proxyStatus") instanceof javax.swing.JLabel);
        check("代理默认端口为 8899", "8899".equals(((JTextField) fieldQuiet(main, "proxyPort")).getText()));
        check("代理页不再显示流量列表", !hasDeclaredField(main, "proxyTable") && !hasDeclaredField(main, "proxyFlows"));
        check("代理页不再提供获取联网 IP 按钮", !hasDeclaredField(main, "proxyRestoreLan"));
        check("未启动时放行与丢弃不可用", !((JButton) fieldQuiet(main, "proxyForward")).isEnabled()
                && !((JButton) fieldQuiet(main, "proxyDrop")).isEnabled());
        snapshot(frame, "target/ui-check/06-proxy.png");

        // Payload 生成页：导航项 + 控件 + 端到端生成 + 填入抓包页
        select(main, "payload.build");
        List<String> payloadTexts = contentLabels(main);
        System.out.println("Payload 页标题: " + payloadTexts);
        check("跳转 Payload 二级项打开 Payload 页", payloadTexts.contains("Payload 生成"));
        check("跳转 Payload 二级项不回落到主页", !payloadTexts.contains("安全测试工具箱"));
        check("Payload 页选中项正确", "Payload 生成".equals(plain(list.getSelectedValue())));
        check("Payload 分类自动展开且含二级项", labels(navItems(main)).contains("Payload 生成")
                && labels(navItems(main)).size() == expectedNavSize());

        // java-chains 的字节码类 gadget 要访问 JDK 内部 xalan 实现，Java 17 下必须
        // 由启动参数开放（同 run.ps1）。缺参数时后续端到端断言会集体失败在
        // 「生成失败」上，看不出真正原因；这里先把它单独判出来。
        check("java-chains 引擎已就绪（需 --add-opens xalan，见 run.ps1）",
                payload.PayloadEngine.isReady());

        Object widgets = fieldQuiet(main, "payloadWidgets");
        Object selectorObject = fieldQuiet(main, "payloadSelector");
        check("Payload 页不再有分组下拉框", !hasDeclaredField(widgets, "group"));
        check("Payload 页不再有载体下拉框", !hasDeclaredField(widgets, "kind"));
        check("Payload 页不再有追加节点下拉框", !hasDeclaredField(widgets, "next"));
        check("Payload 页不再有追加节点按钮", !hasDeclaredField(widgets, "addNode"));
        check("Payload 页含列式链选择器", selectorObject instanceof ui.PayloadChainSelector);
        check("Payload 页含链文本框", fieldQuiet(widgets, "chain") instanceof JTextField);
        check("Payload 页链文本只读", !((JTextField) fieldQuiet(widgets, "chain")).isEditable());
        check("Payload 页含删除末节点 / 清空链按钮",
                fieldQuiet(widgets, "undo") instanceof JButton
                        && fieldQuiet(widgets, "clear") instanceof JButton);
        check("Payload 页含参数面板", fieldQuiet(widgets, "params") instanceof javax.swing.JPanel);
        check("Payload 页含生成 / 复制 / 导出 / 填入抓包页按钮",
                fieldQuiet(widgets, "build") instanceof JButton
                        && fieldQuiet(widgets, "copy") instanceof JButton
                        && fieldQuiet(widgets, "export") instanceof JButton
                        && fieldQuiet(widgets, "toCapture") instanceof JButton);
        check("Payload 页含状态标签", fieldQuiet(widgets, "status") instanceof javax.swing.JLabel);
        check("Payload 页含载荷输出文本域", fieldQuiet(widgets, "output") instanceof JTextArea);
        check("Payload 页输出区只读", !((JTextArea) fieldQuiet(widgets, "output")).isEditable());
        check("Payload 页生成前输出为空",
                ((JTextArea) fieldQuiet(widgets, "output")).getText().trim().isEmpty());
        java.awt.Container payloadViewport = ((JTextArea) fieldQuiet(widgets, "output")).getParent();
        System.out.println("Payload 输出区可视高度: " + payloadViewport.getHeight() + "px");
        // 输出区必须能一次看到多行 Base64，而不是被表单挤成一条细线
        check("Payload 页输出区有可用高度", payloadViewport.getHeight() > 120);
        check("Payload 页生成按钮可见",
                ((javax.swing.AbstractButton) fieldQuiet(widgets, "build")).isShowing());

        final ui.PayloadChainSelector chainSelector = (ui.PayloadChainSelector) selectorObject;
        System.out.println("Payload 列数（刚进页）: " + chainSelector.columnCount());
        check("第一列列出全部 28 个载体",
                chainSelector.listAt(0) != null && chainSelector.listAt(0).getModel().getSize() == 28);
        check("每个载体都在第一列里",
                chainSelector.valuesAt(0).containsAll(payload.PayloadEngine.payloadIds()));
        // 载体自身没有后继（实测 nextNodes(载体) 为空），首节点候选是逐个校验出来的；
        // 因此「刚进页」就该有第二列，否则使用者会以为没有可接节点。
        check("刚进页即有「载体列 + 首节点候选列」两列", chainSelector.columnCount() == 2);
        check("链文本与第一列选中项一致",
                ((JTextField) fieldQuiet(widgets, "chain")).getText().trim()
                        .equals(chainSelector.valuesAt(0).get(0)));
        check("首节点候选列与引擎结论一致",
                payload.PayloadEngine.firstNodes(chainSelector.valuesAt(0).get(0))
                        .containsAll(chainSelector.valuesAt(1)));
        check("列内展示可读显示名而不是裸标识", hasDisplayName(chainSelector, 0));

        // 换载体：点第一列的另一项，链从载体重开
        selectColumn(chainSelector, 0, "javanativepayload");
        check("点第一列换载体后链只剩载体",
                "javanativepayload".equals(((JTextField) fieldQuiet(widgets, "chain")).getText().trim()));
        check("换载体后仍是两列", chainSelector.columnCount() == 2);
        check("换载体后候选列换成该载体的首节点",
                payload.PayloadEngine.firstNodes("javanativepayload").containsAll(chainSelector.valuesAt(1)));
        // toString 触发节点已单独成页（payload.tostring），生成页的候选里不应再出现：
        // 两处都留着会让同一批节点被暴露两次，且通用页给的链路与 toString 页的模板对不上
        check("生成页候选不含 toString 触发节点",
                !chainSelector.valuesAt(1).contains("caseinsensitivemap3tostring")
                        && !chainSelector.valuesAt(1).contains("gstringcomparetotostring")
                        && !chainSelector.valuesAt(1).contains("eventlistenerlisttostring"));
        check("生成页候选确实少于引擎首节点集合（证明过滤生效）",
                chainSelector.valuesAt(1).size()
                        < payload.PayloadEngine.firstNodes("javanativepayload").size());
        check("过滤只针对 toString 节点，其余候选与引擎结论一致",
                payload.PayloadEngine.firstNodes("javanativepayload")
                        .containsAll(chainSelector.valuesAt(1)));
        check("被收走的候选数等于该载体的 toString 触发节点数",
                payload.PayloadEngine.firstNodes("javanativepayload").size()
                        - chainSelector.valuesAt(1).size()
                        == payload.ChainScope.availableToStringNodes(
                                payload.PayloadEngine.firstNodes("javanativepayload")).size());

        // 逐级展开：clojure 是已知叶子（实测无后继），选中后不应多出一列空列表
        selectColumn(chainSelector, 1, "clojure");
        check("点第二列候选后链变为两段",
                "javanativepayload -> clojure".equals(
                        ((JTextField) fieldQuiet(widgets, "chain")).getText().trim()));
        check("叶子节点不再展开空列", chainSelector.columnCount() == 2);
        check("链上节点的必填参数已渲染", hasParamField(widgets, "Clojure.cmd"));

        // 换成一个有后继的节点：aspectjweaver -> storeablecachingmap（实测）
        selectColumn(chainSelector, 1, "aspectjweaver");
        System.out.println("Payload 列数（一个节点）: " + chainSelector.columnCount());
        check("选中非叶子节点后展开第三列", chainSelector.columnCount() == 3);
        check("第三列与引擎的后继结论一致",
                payload.PayloadEngine.nextNodes("aspectjweaver").containsAll(chainSelector.valuesAt(2)));
        check("第二列当前选中项就是链上第二项",
                "aspectjweaver".equals(chainSelector.valuesAt(1).get(
                        chainSelector.listAt(1).getSelectedIndex())));
        check("列内展示的显示名取自引擎", hasDisplayName(chainSelector, 2));

        selectColumn(chainSelector, 2, "storeablecachingmap");
        check("点第三列候选后链变为三段",
                "javanativepayload -> aspectjweaver -> storeablecachingmap".equals(
                        ((JTextField) fieldQuiet(widgets, "chain")).getText().trim()));
        check("三段链的末端无后继，不再展开第四列", chainSelector.columnCount() == 3);

        // 点回第二列换一项：该列之后的节点必须被丢弃
        selectColumn(chainSelector, 1, "clojure");
        check("点回第二列换一项后链只剩两项",
                "javanativepayload -> clojure".equals(
                        ((JTextField) fieldQuiet(widgets, "chain")).getText().trim()));
        check("被换掉节点之后的列已丢弃", chainSelector.columnCount() == 2);

        // 重复点当前选中项：链不得变化，也不得出现重复节点
        selectColumn(chainSelector, 1, "clojure");
        check("重复点同一候选不改变链",
                "javanativepayload -> clojure".equals(
                        ((JTextField) fieldQuiet(widgets, "chain")).getText().trim()));

        // 关键字过滤：只收敛该列，且当前选中项始终保留
        int beforeFilter = chainSelector.listAt(1).getModel().getSize();
        setColumnFilter(chainSelector, 1, "zzzz-no-such-node");
        check("无匹配关键字时该列只剩当前选中项",
                chainSelector.listAt(1).getModel().getSize() == 1
                        && "clojure".equals(chainSelector.valuesAt(1).get(0)));
        setColumnFilter(chainSelector, 1, "");
        check("清空关键字后候选恢复",
                chainSelector.listAt(1).getModel().getSize() == beforeFilter);
        setColumnFilter(chainSelector, 1, "beanshell");
        int filtered = chainSelector.listAt(1).getModel().getSize();
        System.out.println("Payload 第二列过滤 beanshell 后项数: " + filtered);
        check("过滤命中时候选收敛", filtered > 0 && filtered < beforeFilter);
        // 当前选中项（clojure）不匹配 beanshell，但按规格必须保留，因此断言的是
        // 「除保留项外都命中关键字」——把保留项也算进去会让断言要么过宽要么恒假
        check("除保留的选中项外，过滤结果都命中关键字",
                allValuesMatch(chainSelector.valuesAt(1), "beanshell")
                        || onlyKeptSelectedMatches(chainSelector.valuesAt(1), "clojure", "beanshell"));
        setColumnFilter(chainSelector, 1, "");
        check("过滤不影响链状态",
                "javanativepayload -> clojure".equals(
                        ((JTextField) fieldQuiet(widgets, "chain")).getText().trim()));
        check("过滤框控件可按列取到", chainSelector.filterFieldAt(1) instanceof JTextField);

        // 每列计数与标签筛选（网页版每个 Gadget 列头右侧的数字与 Tags 下拉）
        check("每列给出候选计数", chainSelector.countAt(1) > 0);
        int tagCount = chainSelector.tagCountAt(1);
        System.out.println("Payload 第二列可筛标签数: " + tagCount);
        check("每列给出可筛标签", tagCount > 0);
        int beforeTagFilter = chainSelector.listAt(1).getModel().getSize();
        String firstTag = chainSelector.tagAt(1, 0);
        setColumnTagFilter(chainSelector, 1, firstTag);
        int afterTagFilter = chainSelector.listAt(1).getModel().getSize();
        System.out.println("按标签 " + firstTag + " 收敛: " + beforeTagFilter + " -> " + afterTagFilter);
        check("按标签收敛后候选减少",
                afterTagFilter < beforeTagFilter || afterTagFilter == beforeTagFilter);
        // 与关键字过滤同一规则：当前选中项无条件保留，因此断言的是
        // 「除保留的选中项外都带该标签」——把保留项也算进去会让断言恒假
        check("按标签收敛后除保留的选中项外都带该标签",
                allTagged(chainSelector, 1, firstTag)
                        || onlyKeptSelectedTagged(chainSelector, 1, firstTag));
        setColumnTagFilter(chainSelector, 1, null);
        check("清除标签筛选后候选恢复",
                chainSelector.listAt(1).getModel().getSize() == beforeTagFilter);
        check("选中项带 END 标记时链信息行会说明末端",
                !chainSelector.valuesAt(1).isEmpty());

        // 列数随链增长（每级一列），超过可视宽度时必须能横向滚动到最后一列，
        // 否则后面的列会被裁掉、使用者以为「没有可接的节点」
        check("选择器外层可横向滚动",
                chainSelector.component().getHorizontalScrollBarPolicy()
                        != javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        check("选择器内部已挂上各列的候选列表",
                chainSelector.component().getViewport().getView() != null);
        check("三列时列宽按列数横向排布",
                chainSelector.columnCount() == 2
                        || chainSelector.component().getViewport().getView().getPreferredSize().width > 0);

        clickButton(widgets, "build");
        String payloadOutput = ((JTextArea) fieldQuiet(widgets, "output")).getText();
        String payloadStatus = ((javax.swing.JLabel) fieldQuiet(widgets, "status")).getText();
        System.out.println("Payload 生成状态: " + payloadStatus);
        check("生成后状态栏报告成功", payloadStatus.startsWith("生成成功"));
        check("生成后输出区含 Base64 段", payloadOutput.contains("Base64："));
        check("生成后输出区含链序", payloadOutput.contains("clojure"));
        check("生成后输出区含摘要", payloadOutput.contains("摘要："));
        check("生成后载荷非空", payloadOutput.length() > 200);

        // 对齐网页版 Generate 的三块面板：OUTPUT 体积徽标 / ENCODE 四个编码 /
        // BEHAVIOR 两个开关 / CONTEXT 上下文 + 条目内容
        String sizeBadge = ((javax.swing.JLabel) fieldQuiet(main, "payloadOutputSize")).getText();
        System.out.println("Payload 体积徽标: " + sizeBadge);
        check("输出体积徽标给出原始与编码后体积", sizeBadge.contains("\u2192"));
        check("ENCODE 四档齐全（Raw/Base64/Hex/Gzip）",
                payloadEncodeButton(main, "raw") != null && payloadEncodeButton(main, "base64") != null
                        && payloadEncodeButton(main, "hex") != null && payloadEncodeButton(main, "gzip_base64") != null);
        System.out.println("编码按钮选中态: raw=" + payloadEncodeButton(main, "raw").isSelected()
                + " base64=" + payloadEncodeButton(main, "base64").isSelected()
                + " hex=" + payloadEncodeButton(main, "hex").isSelected()
                + " gzip=" + payloadEncodeButton(main, "gzip_base64").isSelected());
        check("默认选中 Base64", payloadEncodeButton(main, "base64").isSelected());
        check("含 URL 编码开关", fieldQuiet(main, "payloadUrlEncode") instanceof javax.swing.AbstractButton);
        check("含 BEHAVIOR 自动生成 / 自动复制开关",
                fieldQuiet(main, "payloadAutoBuild") instanceof javax.swing.AbstractButton
                        && fieldQuiet(main, "payloadAutoCopy") instanceof javax.swing.AbstractButton);
        check("含调试生成按钮", fieldQuiet(main, "payloadBuildDebug") instanceof JButton);
        check("含 OUTPUT 文件名输入框", fieldQuiet(main, "payloadFileName") instanceof JTextField);
        check("含 CONTEXT 列表", fieldQuiet(main, "payloadContext") instanceof JList);
        check("含条目内容区与复制该条按钮",
                fieldQuiet(main, "payloadContextDetail") instanceof JTextArea
                        && fieldQuiet(main, "payloadCopyContext") instanceof JButton);
        check("含展开按钮", fieldQuiet(main, "payloadExpand") instanceof JButton);
        check("链信息行给出候选数与末端状态",
                ((javax.swing.JLabel) fieldQuiet(main, "payloadChainMeta")).getText().contains("END"));

        // 切换编码：Hex 的产物应是纯十六进制，且载荷随之改变（下游拿到的必须是新编码）
        String base64Payload = ((JTextArea) fieldQuiet(widgets, "output")).getText();
        clickEncode(main, "hex");
        String hexStatus = ((javax.swing.JLabel) fieldQuiet(widgets, "status")).getText();
        String hexOutput = ((JTextArea) fieldQuiet(widgets, "output")).getText();
        System.out.println("切换 Hex 后状态: " + hexStatus);
        check("切换编码后状态栏报告新编码", hexStatus.contains("Hex"));
        check("切换编码后输出区不再等于 Base64 版本", !hexOutput.equals(base64Payload));
        check("切换编码不改变当前链",
                "javanativepayload -> clojure".equals(
                        ((JTextField) fieldQuiet(widgets, "chain")).getText().trim()));
        clickEncode(main, "base64");
        check("切回 Base64 后输出回到 Base64 段",
                ((JTextArea) fieldQuiet(widgets, "output")).getText().contains("Base64："));

        // 调试生成：逐步产物必须写进输出区（网页版「调试生成」的核心价值）
        clickButton(widgets, "buildDebug");
        String debugOutput = ((JTextArea) fieldQuiet(widgets, "output")).getText();
        System.out.println("调试生成输出片段: "
                + debugOutput.substring(0, Math.min(120, debugOutput.length())));
        check("调试生成后输出区含逐步产物段", debugOutput.contains("逐步产物"));
        check("调试生成后逐步产物含载体这一步", debugOutput.contains("1."));

        // 展开所有：把输出区换成完整载荷正文
        clickButton(widgets, "expand");
        check("展开后按钮变为收起",
                "收起".equals(((javax.swing.AbstractButton) fieldQuiet(widgets, "expand")).getText()));
        clickButton(widgets, "expand");
        check("再次点击收起后回到预览",
                "展开".equals(((javax.swing.AbstractButton) fieldQuiet(widgets, "expand")).getText()));

        snapshot(frame, "target/ui-check/07-payload.png");

        // 手工回退路径：清空链后只剩载体，且候选列回到首节点
        clickButton(widgets, "clear");
        check("清空链后只剩载体",
                "javanativepayload".equals(((JTextField) fieldQuiet(widgets, "chain")).getText().trim()));
        check("清空链后回到载体与首节点候选两列", chainSelector.columnCount() == 2);
        clickButton(widgets, "toCapture");
        String captureFromPayload = ((JTextArea) fieldQuiet(main, "captureBody")).getText();
        check("填入抓包页后请求体带上生成的载荷",
                !captureFromPayload.trim().isEmpty() && payloadOutput.contains(captureFromPayload.trim()));
        check("填入抓包页后跳转到抓包页", "抓包转换".equals(plain(list.getSelectedValue())));

        // 预设链页：分类 / 清单 / 步骤 / 输入 / 生成
        select(main, "payload.preset");
        List<String> presetTexts = contentLabels(main);
        System.out.println("预设链页标题: " + presetTexts);
        check("跳转预设链二级项打开预设链页", presetTexts.contains("预设链"));
        check("预设链页选中项正确", "预设链".equals(plain(list.getSelectedValue())));
        check("预设链分类自动展开且含二级项", labels(navItems(main)).contains("预设链")
                && labels(navItems(main)).size() == expectedNavSize());
        Object presetWidgets = fieldQuiet(main, "presetWidgets");
        check("预设链页含分类下拉框", fieldQuiet(presetWidgets, "category") instanceof JComboBox);
        check("预设链页分类非空", ((JComboBox<?>) fieldQuiet(presetWidgets, "category")).getItemCount() > 0);
        check("预设链页含预设清单", fieldQuiet(presetWidgets, "presetList") instanceof JList);
        check("预设链页清单已载入内置预设",
                ((javax.swing.DefaultListModel<?>) fieldQuiet(presetWidgets, "presets")).size() > 0);
        check("预设链页含步骤容器", fieldQuiet(presetWidgets, "steps") instanceof javax.swing.JPanel);
        check("预设链页含输入容器", fieldQuiet(presetWidgets, "inputs") instanceof javax.swing.JPanel);
        check("预设链页含生成 / 复制 / 发到服务器 / 填入抓包页按钮",
                fieldQuiet(presetWidgets, "build") instanceof JButton
                        && fieldQuiet(presetWidgets, "copy") instanceof JButton
                        && fieldQuiet(presetWidgets, "toServers") instanceof JButton
                        && fieldQuiet(presetWidgets, "toCapture") instanceof JButton);
        check("预设链页含载荷输出文本域", fieldQuiet(presetWidgets, "output") instanceof JTextArea);
        check("预设链页输出区只读", !((JTextArea) fieldQuiet(presetWidgets, "output")).isEditable());
        // 端到端：内置预设必须真的能构建出载荷，只渲染不生成等于模板库不可用
        clickButton(presetWidgets, "build");
        String presetOutput = ((JTextArea) fieldQuiet(presetWidgets, "output")).getText();
        String presetStatus = ((javax.swing.JLabel) fieldQuiet(presetWidgets, "status")).getText();
        System.out.println("预设链生成状态: " + presetStatus);
        check("预设链生成后状态栏报告成功", presetStatus.startsWith("生成成功"));
        check("预设链生成后输出含 Base64", presetOutput.contains("Base64："));
        check("预设链生成后输出含载体", presetOutput.contains("载体："));
        snapshot(frame, "target/ui-check/08-preset.png");

        // toString 链页：模板清单 / 自定义输入 / 生成 / 复制模板
        select(main, "payload.tostring");
        List<String> tostringTexts = contentLabels(main);
        System.out.println("toString 链页标题: " + tostringTexts);
        check("跳转 toString 链二级项打开对应页面", tostringTexts.contains("toString 链"));
        check("toString 链页不回落到主页", !tostringTexts.contains("安全测试工具箱"));
        check("toString 链页选中项正确", "toString 链".equals(plain(list.getSelectedValue())));
        check("Payload 分类含四个二级项", labels(navItems(main)).contains("toString 链")
                && labels(navItems(main)).contains("HTTP 带外 Jar")
                && labels(navItems(main)).size() == expectedNavSize());
        Object tostringWidgets = fieldQuiet(main, "tostringWidgets");
        check("toString 页含模板清单", fieldQuiet(tostringWidgets, "templateList") instanceof JList);
        check("toString 页模板清单已载入全部模板",
                ((javax.swing.DefaultListModel<?>) fieldQuiet(tostringWidgets, "templates")).size()
                        == payload.ToStringPreset.templates().size());
        check("toString 页含自定义目标类输入框",
                fieldQuiet(tostringWidgets, "targetClass") instanceof JTextField);
        check("toString 页含末端命令输入框", fieldQuiet(tostringWidgets, "command") instanceof JTextField);
        check("toString 页含生成 / 复制模板 / 复制 / 填入抓包页按钮",
                fieldQuiet(tostringWidgets, "build") instanceof JButton
                        && fieldQuiet(tostringWidgets, "copyTemplate") instanceof JButton
                        && fieldQuiet(tostringWidgets, "copy") instanceof JButton
                        && fieldQuiet(tostringWidgets, "toCapture") instanceof JButton);
        check("toString 页含链步骤容器", fieldQuiet(tostringWidgets, "steps") instanceof javax.swing.JPanel);
        check("toString 页含载荷输出文本域", fieldQuiet(tostringWidgets, "output") instanceof JTextArea);
        check("toString 页输出区只读", !((JTextArea) fieldQuiet(tostringWidgets, "output")).isEditable());
        check("toString 页含状态标签", fieldQuiet(tostringWidgets, "status") instanceof javax.swing.JLabel);
        check("toString 页目标类默认留空（用引擎随机类名）",
                ((JTextField) fieldQuiet(tostringWidgets, "targetClass")).getText().trim().isEmpty());
        // 步骤行是「序号 + 节点标识 + 显示名」拼在一个标签里，因此按整段文字子串判定，
        // 不能按标签逐个相等去比
        check("toString 页已渲染链步骤（含触发节点标识）",
                joined(labels(tostringWidgets, "steps")).contains("caseinsensitivemap3tostring"));
        check("选中模板后状态栏给出链序",
                ((javax.swing.JLabel) fieldQuiet(tostringWidgets, "status")).getText()
                        .contains("javanativepayload"));
        // 目标类按用例填写：留空会用引擎随机类名，断言就看不出「自定义」是否真的生效
        setText(main, "tostringTargetClass", "com.example.User");
        setText(main, "tostringCommand", "whoami");
        clickButton(tostringWidgets, "build");
        String tostringStatus = awaitStatus(main, "tostringStatus", "正在生成载荷…", 60000);
        System.out.println("toString 生成状态: " + tostringStatus);
        String tostringOutput = ((JTextArea) fieldQuiet(tostringWidgets, "output")).getText();
        check("toString 页生成后状态栏报告成功", tostringStatus.startsWith("生成成功"));
        check("toString 页输出含 Base64 段", tostringOutput.contains("Base64："));
        check("toString 页输出含触发节点", tostringOutput.contains("caseinsensitivemap3tostring"));
        check("toString 页输出回显自定义目标类", tostringOutput.contains("com.example.User"));
        check("toString 页输出回显末端命令", tostringOutput.contains("whoami"));
        check("toString 页输出链序含触发节点与中继",
                tostringOutput.contains("jacksontostring") && tostringOutput.contains("exec"));
        clickButton(tostringWidgets, "toCapture");
        check("toString 页填入抓包页后跳转", "抓包转换".equals(plain(list.getSelectedValue())));

        // 带外 Jar 页：类型 / 动作联动 / 参数校验（真实托管放到端到端自检）
        select(main, "payload.oobjar");
        List<String> oobTexts = contentLabels(main);
        System.out.println("带外 Jar 页标题: " + oobTexts);
        check("跳转带外 Jar 二级项打开对应页面", oobTexts.contains("HTTP 带外 Jar"));
        check("带外 Jar 页选中项正确", "HTTP 带外 Jar".equals(plain(list.getSelectedValue())));
        Object oobWidgets = fieldQuiet(main, "oobJarWidgets");
        check("带外 Jar 页含 Jar 类型下拉框", fieldQuiet(oobWidgets, "kindCombo") instanceof JComboBox);
        check("带外 Jar 页类型下拉框已载入模板",
                ((JComboBox<?>) fieldQuiet(oobWidgets, "kindCombo")).getItemCount()
                        == payload.JarPreset.kinds().size());
        check("带外 Jar 页含末端动作下拉框", fieldQuiet(oobWidgets, "actionCombo") instanceof JComboBox);
        check("带外 Jar 页动作下拉框已载入模板",
                ((JComboBox<?>) fieldQuiet(oobWidgets, "actionCombo")).getItemCount()
                        == payload.JarPreset.actions().size());
        check("带外 Jar 页含 URL / 命令 / 路径输入框",
                fieldQuiet(oobWidgets, "url") instanceof JTextField
                        && fieldQuiet(oobWidgets, "command") instanceof JTextField
                        && fieldQuiet(oobWidgets, "path") instanceof JTextField);
        check("带外 Jar 页含目标类与前缀输入框",
                fieldQuiet(oobWidgets, "targetClass") instanceof JTextField
                        && fieldQuiet(oobWidgets, "classNamePrefix") instanceof JTextField);
        check("带外 Jar 页含绑定地址与端口输入框",
                fieldQuiet(oobWidgets, "bindHost") instanceof JTextField
                        && fieldQuiet(oobWidgets, "port") instanceof JTextField);
        check("带外 Jar 页含可执行 Jar 开关", fieldQuiet(oobWidgets, "executable") instanceof AbstractButton);
        check("带外 Jar 页含托管 / 停止 / 复制地址按钮",
                fieldQuiet(oobWidgets, "host") instanceof JButton
                        && fieldQuiet(oobWidgets, "stop") instanceof JButton
                        && fieldQuiet(oobWidgets, "copyUrl") instanceof JButton);
        check("带外 Jar 页含输出文本域", fieldQuiet(oobWidgets, "output") instanceof JTextArea);
        check("带外 Jar 页输出区只读", !((JTextArea) fieldQuiet(oobWidgets, "output")).isEditable());
        // 动作联动：执行命令不需要 URL，回连 HTTP 需要 URL
        selectComboItem(main, oobWidgets, "actionCombo", "执行命令");
        check("选择「执行命令」后 URL 与路径输入框被禁用",
                !((JTextField) fieldQuiet(oobWidgets, "url")).isEnabled()
                        && !((JTextField) fieldQuiet(oobWidgets, "path")).isEnabled());
        check("选择「执行命令」后命令输入框可用",
                ((JTextField) fieldQuiet(oobWidgets, "command")).isEnabled());
        selectComboItem(main, oobWidgets, "actionCombo", "回连 HTTP 请求");
        check("选择「回连 HTTP 请求」后 URL 输入框可用",
                ((JTextField) fieldQuiet(oobWidgets, "url")).isEnabled());
        check("选择「回连 HTTP 请求」后命令输入框被禁用",
                !((JTextField) fieldQuiet(oobWidgets, "command")).isEnabled());
        // 未填 URL 就托管：必须给可读提示，而不是拿空地址去建载荷
        setText(main, "oobJarUrl", "");
        clickButton(oobWidgets, "host");
        check("端口框进页即有可用默认值（50001）",
                "50001".equals(((JTextField) fieldQuiet(oobWidgets, "port")).getText().trim()));
        check("未填必填 URL 时给出可读提示",
                ((javax.swing.JLabel) fieldQuiet(oobWidgets, "status")).getText().contains("不能为空"));
        // 端口非法：同样必须拦在绑定之前
        setText(main, "oobJarUrl", "http://127.0.0.1:1/x");
        setText(main, "oobJarPort", "70000");
        clickButton(oobWidgets, "host");
        check("端口越界时给出可读提示",
                ((javax.swing.JLabel) fieldQuiet(oobWidgets, "status")).getText().contains("1-65535"));
        setText(main, "oobJarPort", "50001");
        snapshot(frame, "target/ui-check/11-oobjar.png");

        // 恶意服务器页：五类服务 / 端口表单 / 发布 / 状态
        select(main, "service.servers");
        List<String> serviceTexts = contentLabels(main);
        System.out.println("恶意服务器页标题: " + serviceTexts);
        check("跳转恶意服务器二级项打开服务页", serviceTexts.contains("恶意服务器"));
        check("恶意服务器页选中项正确", "恶意服务器".equals(plain(list.getSelectedValue())));
        check("服务分类自动展开且含二级项", labels(navItems(main)).contains("Shiro 漏洞利用")
                && labels(navItems(main)).size() == expectedNavSize());
        Object serviceWidgets = fieldQuiet(main, "serviceWidgets");
        check("服务页含服务清单", fieldQuiet(serviceWidgets, "serviceList") instanceof JList);
        check("服务页列出五类服务",
                ((javax.swing.DefaultListModel<?>) fieldQuiet(serviceWidgets, "services")).size() == 5);
        check("服务页含端口容器", fieldQuiet(serviceWidgets, "portPanel") instanceof javax.swing.JPanel);
        check("服务页含绑定地址输入框", fieldQuiet(serviceWidgets, "bindHost") instanceof JTextField);
        check("服务页含对外地址输入框", fieldQuiet(serviceWidgets, "advertiseHost") instanceof JTextField);
        check("服务页含启动 / 停止 / 发布 / 刷新按钮",
                fieldQuiet(serviceWidgets, "start") instanceof JButton
                        && fieldQuiet(serviceWidgets, "stop") instanceof JButton
                        && fieldQuiet(serviceWidgets, "publish") instanceof JButton
                        && fieldQuiet(serviceWidgets, "refresh") instanceof JButton);
        check("服务页含复制地址与填入抓包页按钮",
                fieldQuiet(serviceWidgets, "copyAddress") instanceof JButton
                        && fieldQuiet(serviceWidgets, "toCapture") instanceof JButton);
        check("服务页含载荷编辑控件（分组 / 载体 / 链 / 参数）",
                fieldQuiet(serviceWidgets, "group") instanceof JComboBox
                        && fieldQuiet(serviceWidgets, "kind") instanceof JComboBox
                        && fieldQuiet(serviceWidgets, "chain") instanceof JTextField
                        && fieldQuiet(serviceWidgets, "params") instanceof javax.swing.JPanel);
        check("服务页含运行输出文本域", fieldQuiet(serviceWidgets, "output") instanceof JTextArea);
        check("服务页输出区只读", !((JTextArea) fieldQuiet(serviceWidgets, "output")).isEditable());
        // 未选服务时不能启动：启动按钮必须是被状态驱动的，而不是恒可点
        check("未启动时停止按钮不可用", !((JButton) fieldQuiet(serviceWidgets, "stop")).isEnabled());
        // 端口输入框的初值必须来自配置页保存的默认值，而不是代码里的常量
        check("服务页端口初值来自配置",
                ((JTextField) ((java.util.Map<?, ?>) fieldQuiet(serviceWidgets, "portFields"))
                        .get("ldap")).getText().equals("50389"));
        snapshot(frame, "target/ui-check/09-servers.png");

        // 小工具 - 文件上传页：导航 + 控件 + 默认值 + 失败路径
        select(main, "tools.upload");
        List<String> toolsTexts = contentLabels(main);
        System.out.println("文件上传页标题: " + toolsTexts);
        check("跳转小工具二级项打开文件上传页", toolsTexts.contains("文件上传"));
        check("跳转文件上传页不回落到主页", !toolsTexts.contains("安全测试工具箱"));
        check("小工具分类自动展开且含二级项", labels(navItems(main)).contains("文件上传")
                && labels(navItems(main)).size() == expectedNavSize());
        check("文件上传页选中项正确", "文件上传".equals(plain(list.getSelectedValue())));
        check("文件上传页含目标 URL 输入框", fieldQuiet(main, "toolsUploadUrl") instanceof JTextField);
        check("文件上传页含本地文件输入框且只读",
                fieldQuiet(main, "toolsUploadFile") instanceof JTextField
                        && !((JTextField) fieldQuiet(main, "toolsUploadFile")).isEditable());
        check("文件上传页含选择文件按钮", fieldQuiet(main, "toolsUploadChoose") instanceof JButton);
        check("文件上传页含表单字段名输入框", fieldQuiet(main, "toolsUploadField") instanceof JTextField);
        check("文件上传页默认字段名为 file",
                "file".equals(((JTextField) fieldQuiet(main, "toolsUploadField")).getText()));
        check("文件上传页含附加表单字段与请求头输入框",
                fieldQuiet(main, "toolsUploadFields") instanceof JTextField
                        && fieldQuiet(main, "toolsUploadHeaders") instanceof JTextField);
        check("文件上传页含上传按钮", fieldQuiet(main, "toolsUploadRun") instanceof JButton);
        check("文件上传页含复制结果按钮", fieldQuiet(main, "toolsUploadCopy") instanceof JButton);
        check("文件上传页含状态标签", fieldQuiet(main, "toolsUploadStatus") instanceof javax.swing.JLabel);
        check("文件上传页含结果文本域", fieldQuiet(main, "toolsUploadResult") instanceof JTextArea);
        check("文件上传页结果区只读", !((JTextArea) fieldQuiet(main, "toolsUploadResult")).isEditable());
        // 未填参数时给出可读提示而不是静默失败
        setText(main, "toolsUploadUrl", "");
        clickButton(main, "toolsUploadRun");
        check("未填目标 URL 时提示且不发请求",
                ((JTextArea) fieldQuiet(main, "toolsUploadResult")).getText().contains("目标 URL 不能为空"));
        setText(main, "toolsUploadUrl", "http://127.0.0.1:1/upload");
        clickButton(main, "toolsUploadRun");
        check("未选择文件时提示",
                ((JTextArea) fieldQuiet(main, "toolsUploadResult")).getText().contains("请先选择"));
        snapshot(frame, "target/ui-check/10-tools-upload.png");

        // 漏洞分析 · 组件与漏洞：导航 + 控件 + 端到端本地分析
        select(main, "analyze.scan");
        List<String> analyzeTexts = contentLabels(main);
        System.out.println("漏洞分析页标题: " + analyzeTexts);
        check("跳转漏洞分析二级项打开分析页", analyzeTexts.contains("漏洞分析"));
        check("跳转分析页不回落到主页", !analyzeTexts.contains("安全测试工具箱"));
        check("漏洞分析分类自动展开且含两个二级项",
                labels(navItems(main)).contains("组件与漏洞") && labels(navItems(main)).contains("调用链查询")
                        && labels(navItems(main)).size() == expectedNavSize());
        check("组件与漏洞为选中项", "组件与漏洞".equals(plain(list.getSelectedValue())));
        check("分析页含目标输入框", fieldQuiet(main, "analyzeTarget") instanceof JTextField);
        check("分析页含选择目标按钮", fieldQuiet(main, "analyzeChooseTarget") instanceof JButton);
        check("分析页含 pom.xml 输入框", fieldQuiet(main, "analyzePom") instanceof JTextField);
        check("分析页含特殊类名输入框", fieldQuiet(main, "analyzeClassName") instanceof JTextField);
        check("分析页含反编译输出目录输入框", fieldQuiet(main, "analyzeOutputDir") instanceof JTextField);
        check("分析页含调用链超时输入框", fieldQuiet(main, "analyzeTimeout") instanceof JTextField);
        check("分析页含本地依赖分析按钮", fieldQuiet(main, "analyzeLocal") instanceof JButton);
        check("分析页含调用链分析按钮", fieldQuiet(main, "analyzeRunEngine") instanceof JButton);
        check("分析页含快速模式与解析嵌套 jar 开关",
                fieldQuiet(main, "analyzeQuick") instanceof AbstractButton
                        && fieldQuiet(main, "analyzeInnerJars") instanceof AbstractButton);
        check("分析页默认勾选解析嵌套 jar", ((AbstractButton) fieldQuiet(main, "analyzeInnerJars")).isSelected());
        check("分析页含数据库查询下拉框", fieldQuiet(main, "analyzeQueryKind") instanceof JComboBox);
        check("分析页查询候选与引擎查询一一对应",
                comboOptions(main, "analyzeQueryKind").size() == analyzer.ReportReader.Query.values().length);
        check("分析页含关键字输入框与查询按钮",
                fieldQuiet(main, "analyzeKeyword") instanceof JTextField
                        && fieldQuiet(main, "analyzeQuery") instanceof JButton);
        check("分析页含反编译与打开输出目录按钮",
                fieldQuiet(main, "analyzeDecompile") instanceof JButton
                        && fieldQuiet(main, "analyzeOpenOutput") instanceof JButton);
        check("分析页含报告文本域且只读",
                fieldQuiet(main, "analyzeOutput") instanceof JTextArea
                        && !((JTextArea) fieldQuiet(main, "analyzeOutput")).isEditable());
        check("分析页含跳转建议容器与状态标签",
                fieldQuiet(main, "analyzeJumps") instanceof javax.swing.JPanel
                        && fieldQuiet(main, "analyzeStatus") instanceof javax.swing.JLabel);

        // 失败路径：没选目标时给出可读提示而不是静默失败
        setText(main, "analyzeTarget", "");
        setText(main, "analyzePom", "");
        clickButton(main, "analyzeLocal");
        Thread.sleep(200);
        check("未选目标时提示而不是静默失败",
                ((javax.swing.JLabel) fieldQuiet(main, "analyzeStatus")).getText().contains("请先选择"));

        // 端到端：造一个带 Maven 元数据的 jar，真实跑一次本地分析
        java.nio.file.Path jarDir = java.nio.file.Files.createTempDirectory("javasec-analyze");
        java.nio.file.Path jar = jarDir.resolve("fastjson-1.2.24.jar");
        java.util.zip.ZipOutputStream zip =
                new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(jar));
        zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/maven/com.alibaba/fastjson/pom.properties"));
        zip.write("version=1.2.24\ngroupId=com.alibaba\nartifactId=fastjson\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.putNextEntry(new java.util.zip.ZipEntry("com/alibaba/fastjson/JSON.class"));
        zip.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        zip.closeEntry();
        zip.close();

        setText(main, "analyzeTarget", jar.toAbsolutePath().toString());
        clickButton(main, "analyzeLocal");
        // 状态在点击处理内同步置位，因此这里等到它变化时，报告已经写完
        String analyzeDone = awaitStatus(main, "analyzeStatus", "本地依赖分析 执行中…", 15000);
        System.out.println("本地分析状态: " + analyzeDone);
        String analyzeReport = ((JTextArea) fieldQuiet(main, "analyzeOutput")).getText();
        System.out.println("本地分析报告(截断): "
                + analyzeReport.substring(0, Math.min(420, analyzeReport.length())));
        check("端到端本地分析给出报告", analyzeReport.contains("组件与漏洞分析报告"));
        check("报告识别出 fastjson 组件", analyzeReport.contains("com.alibaba:fastjson:1.2.24"));
        check("报告命中 autoType 规则", analyzeReport.contains("Fastjson 反序列化"));
        check("报告标注可信度为高（来自 pom.properties）", analyzeReport.contains("[高]"));
        check("报告给出判定依据（来源与命中区间）",
                analyzeReport.contains("pom.properties") && analyzeReport.contains("<= 1.2.24"));
        check("报告给出建议动作", analyzeReport.contains("建议:"));
        // 建议按钮与状态在同一个 EDT 任务里写入，这里轮询等待，避免读到中间状态
        long jumpDeadline = System.currentTimeMillis() + 5000;
        int jumpCount = 0;
        while (System.currentTimeMillis() < jumpDeadline) {
            jumpCount = ((javax.swing.JPanel) fieldQuiet(main, "analyzeJumps")).getComponentCount();
            if (jumpCount > 0) break;
            Thread.sleep(100);
        }
        check("命中后生成可跳转的建议按钮", jumpCount > 0);

        // 调用链查询：未构建数据库时必须给出可读提示
        select(main, "analyze.chain");
        clickButton(main, "analyzeQuery");
        Thread.sleep(300);
        String chainStatus = ((javax.swing.JLabel) fieldQuiet(main, "analyzeStatus")).getText();
        check("未构建数据库时查询给出可读提示",
                chainStatus.contains("还没有可查询的数据库") || chainStatus.contains("找不到"));
        snapshot(frame, "target/ui-check/11-analyze.png");

        // 配置页新增「小工具配置」分组
        select(main, "config");
        check("配置页含小工具配置分组", contentLabels(main).contains("小工具配置"));
        check("配置页含默认上传 URL 输入框", fieldQuiet(main, "configUploadUrl") instanceof JTextField);
        check("配置页含默认表单字段名输入框", fieldQuiet(main, "configUploadField") instanceof JTextField);
        check("配置页含上传超时输入框", fieldQuiet(main, "configUploadTimeout") instanceof JTextField);
        check("上传超时默认 30 秒",
                "30".equals(((JTextField) fieldQuiet(main, "configUploadTimeout")).getText()));
        check("配置页含 toString 链配置分组", contentLabels(main).contains("toString 链配置"));
        check("配置页含默认链模板下拉框", fieldQuiet(main, "configTostringTemplate") instanceof JComboBox);
        check("默认链模板候选项来自工具栏模板表",
                ((javax.swing.JComboBox<?>) fieldQuiet(main, "configTostringTemplate")).getItemCount()
                        == payload.ToStringPreset.templates().size());
        check("配置页含默认末端命令输入框",
                fieldQuiet(main, "configTostringCommand") instanceof JTextField);
        check("配置页含带外 Jar 配置分组", contentLabels(main).contains("带外 Jar 配置"));
        check("配置页含带外 Jar 绑定地址与端口输入框",
                fieldQuiet(main, "configOobJarBindHost") instanceof JTextField
                        && fieldQuiet(main, "configOobJarPort") instanceof JTextField);
        check("带外 Jar 默认端口为 50001",
                "50001".equals(((JTextField) fieldQuiet(main, "configOobJarPort")).getText()));
        check("配置页含带外 Jar 默认 URL / 路径 / 执行参数输入框",
                fieldQuiet(main, "configOobJarDefaultUrl") instanceof JTextField
                        && fieldQuiet(main, "configOobJarDefaultPath") instanceof JTextField
                        && fieldQuiet(main, "configOobJarDefaultCommand") instanceof JTextField);
        check("配置页含漏洞分析配置分组", contentLabels(main).contains("漏洞分析配置"));
        check("配置页含默认扫描目标输入框",
                fieldQuiet(main, "configAnalyzeScanTarget") instanceof JTextField);
        check("配置页含外部引擎 JAR 输入框",
                fieldQuiet(main, "configAnalyzeEngineJar") instanceof JTextField);
        check("配置页含引擎工作目录输入框",
                fieldQuiet(main, "configAnalyzeWorkDir") instanceof JTextField);
        check("配置页含分析超时输入框且默认 300 秒",
                fieldQuiet(main, "configAnalyzeTimeout") instanceof JTextField
                        && "300".equals(((JTextField) fieldQuiet(main, "configAnalyzeTimeout")).getText()));
        check("配置页含反编译输出目录输入框",
                fieldQuiet(main, "configAnalyzeDecompileDir") instanceof JTextField);
        check("带外 Jar 默认落地路径与上游预设一致",
                "/tmp/payload.bin".equals(
                        ((JTextField) fieldQuiet(main, "configOobJarDefaultPath")).getText()));

        select(main, "home");
        snapshot(frame, "target/ui-check/04-home.png");

        System.out.println("全部界面自检通过");
        System.exit(0);
    }

    /**
     * 侧边栏应有的项数：6 个一级项 + 每个已展开分组各自的子项。
     *
     * <p>直接读 {@code Main.NAV_ITEMS} 的展开标志现算，而不是把数字写死：
     * 导航顺序调整过一次就够痛了——写死的数字与索引在每次调整后都要重新数一遍。
     * 这条断言真正校验的是「rebuildNavigation 是否把每个已展开分组的子项都补全」。
     */
    /** 点某一列的候选：触发该列的选中事件，与真实点击走同一段回调。 */
    private static void selectColumn(final ui.PayloadChainSelector selector, final int column,
                                     final String value) throws Exception {
        onEdt(new Runnable() {
            public void run() {
                int row = selector.valuesAt(column).indexOf(value);
                if (row < 0) throw new IllegalStateException("列中找不到候选: " + value);
                if (selector.listAt(column).getSelectedIndex() == row) {
                    // Swing 对「选中同一项」不发事件：这里显式走一次回调入口，
                    // 使断言验证的是「重复选中不改变链」这条规则，而不是 Swing 的空操作
                    selector.choose(column, value);
                    return;
                }
                selector.listAt(column).setSelectedIndex(row);
            }
        });
    }

    /** 设置某一列的过滤关键字。 */
    private static void setColumnFilter(final ui.PayloadChainSelector selector, final int column,
                                        final String keyword) throws Exception {
        onEdt(new Runnable() {
            public void run() {
                selector.filterFieldAt(column).setText(keyword);
            }
        });
    }

    /** 列内展示的是显示名（与裸标识不同），而不是把所有项都退化成标识。 */
    private static boolean hasDisplayName(ui.PayloadChainSelector selector, int column) {
        javax.swing.JList<String> list = selector.listAt(column);
        java.util.List<String> values = selector.valuesAt(column);
        if (list == null) return false;
        for (int index = 0; index < list.getModel().getSize() && index < values.size(); index++) {
            String shown = list.getModel().getElementAt(index);
            if (shown != null && !shown.equals(values.get(index))) return true;
        }
        return false;
    }

    /** 过滤后的一列是否每一项都命中关键字（值与显示名任一命中即算，与选择器判据同源）。 */
    private static boolean allValuesMatch(java.util.List<String> values, String keyword) {
        if (values.isEmpty()) return false;
        for (String value : values) {
            if (!matchesKeyword(value, keyword)) return false;
        }
        return true;
    }

    /** 除第一个（被保留的选中项）以外都命中关键字。 */
    private static boolean onlyKeptSelectedMatches(java.util.List<String> values, String kept,
                                                  String keyword) {
        if (values.isEmpty()) return false;
        boolean sawKept = false;
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (index == 0 && kept.equals(value)) {
                sawKept = true;
                continue;
            }
            if (!matchesKeyword(value, keyword)) return false;
        }
        return sawKept;
    }

    /** 值与显示名任一命中关键字即算命中（与选择器的过滤判据一致）。 */
    private static boolean matchesKeyword(String value, String keyword) {
        if (value == null) return false;
        String needle = keyword.toLowerCase(java.util.Locale.ROOT);
        if (value.toLowerCase(java.util.Locale.ROOT).indexOf(needle) >= 0) return true;
        String label = payload.PayloadEngine.nodeLabel(value);
        return label != null && label.toLowerCase(java.util.Locale.ROOT).indexOf(needle) >= 0;
    }

    /** 取编码按钮：编码标识见 payload.PayloadCodec.Option#id。 */
    private static javax.swing.AbstractButton payloadEncodeButton(Object main, String id) {
        try {
            return (javax.swing.AbstractButton) fieldQuiet(main, "payloadEncode-" + id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 点击某个编码按钮：必须走真实点击路径，否则动作监听器不会触发。 */
    private static void clickEncode(Object main, final String id) throws Exception {
        final javax.swing.AbstractButton button = payloadEncodeButton(main, id);
        if (button == null) throw new IllegalStateException("找不到编码按钮: " + id);
        onEdt(new Runnable() {
            public void run() { button.doClick(); }
        });
    }

    /** 设置或清除某一列的标签筛选；传 null 表示清除全部。 */
    private static void setColumnTagFilter(final ui.PayloadChainSelector selector, final int column,
                                           final String tag) throws Exception {
        onEdt(new Runnable() {
            public void run() {
                if (tag == null) {
                    selector.clearTagFilter(column);
                } else {
                    selector.setTagFilter(column, java.util.Collections.singletonList(tag));
                }
            }
        });
    }

    /** 除第一个（被保留的选中项）以外，过滤结果里的每一项是否都带指定标签。 */
    private static boolean onlyKeptSelectedTagged(ui.PayloadChainSelector selector, int column,
                                                  String tag) {
        java.util.List<String> values = selector.valuesAt(column);
        if (values.isEmpty()) return false;
        for (int index = 1; index < values.size(); index++) {
            if (!payload.PayloadEngine.nodeInfo(values.get(index)).hasTag(tag)) return false;
        }
        return values.size() > 1;
    }

    /** 过滤结果里的每一项是否都带指定标签。 */
    private static boolean allTagged(ui.PayloadChainSelector selector, int column, String tag) {
        java.util.List<String> values = selector.valuesAt(column);
        if (values.isEmpty()) return false;
        for (String value : values) {
            if (!payload.PayloadEngine.nodeInfo(value).hasTag(tag)) return false;
        }
        return true;
    }

    private static int expectedNavSize() throws Exception {
        Field field = Class.forName("Main").getDeclaredField("NAV_ITEMS");
        field.setAccessible(true);
        List<?> groups = (List<?>) field.get(null);
        int total = 0;
        for (Object group : groups) {
            total += 1;
            Field expanded = group.getClass().getDeclaredField("expanded");
            expanded.setAccessible(true);
            if (!((Boolean) expanded.get(group)).booleanValue()) continue;
            Field children = group.getClass().getDeclaredField("children");
            children.setAccessible(true);
            total += ((List<?>) children.get(group)).size();
        }
        return total;
    }

    private static void select(Object main, String key) throws Exception {
        final Object target = main;
        final String navKey = key;
        onEdt(new Runnable() {
            public void run() { invoke(target, "selectNav", new Class<?>[]{String.class}, navKey); }
        });
    }

    private static void toggle(Object main) throws Exception {
        final Object target = main;
        final Object group = findNavItem(target);
        onEdt(new Runnable() {
            public void run() { invoke(target, "toggleGroup", new Class<?>[]{group.getClass()}, group); }
        });
    }

    private static void swing(Object main, Runnable action) throws Exception {
        onEdt(action);
    }

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private static Object newMain() throws Exception {
        Constructor<?> constructor = Class.forName("Main").getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Object findNavItem(Object main) {
        return invoke(main, "findNavItem", new Class<?>[]{String.class}, "fastjson");
    }

    private static Object field(Object target, String name) throws Exception {
        return fieldQuiet(target, name);
    }

    /** 取控件：走 ui.UiHandle 稳定门面，界面内部拆分不再影响断言。 */
    private static Object fieldQuiet(Object target, String name) {
        return ui.UiHandle.get(target, name);
    }

    /** 写入文本控件（输入框或文本域）：在 EDT 上落到稳定门面取到的控件上。 */
    private static void setText(Object target, String name, String value) throws Exception {
        Object component = fieldQuiet(target, name);
        final String text = value;
        if (component instanceof JTextArea) {
            final JTextArea area = (JTextArea) component;
            onEdt(new Runnable() {
                public void run() { area.setText(text); }
            });
            return;
        }
        final JTextField field = (JTextField) component;
        onEdt(new Runnable() {
            public void run() { field.setText(text); }
        });
    }

    /** 读取下拉框的全部选项文字，用于断言「一键发送」的目标清单。 */
    @SuppressWarnings("unchecked")
    private static java.util.List<String> comboOptions(Object target, String name) {
        javax.swing.JComboBox<Object> combo = (javax.swing.JComboBox<Object>) fieldQuiet(target, name);
        java.util.List<String> options = new java.util.ArrayList<String>();
        for (int index = 0; index < combo.getItemCount(); index++) {
            Object item = combo.getItemAt(index);
            options.add(item == null ? "" : String.valueOf(item));
        }
        return options;
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object argument) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, argument);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<?> navItems(Object main) {
        return (List<?>) fieldQuiet(main, "navigationItems");
    }

    private static List<String> labels(List<?> items) {
        List<String> labels = new ArrayList<String>();
        for (Object item : items) {
            labels.add(displayFor(item));
        }
        return labels;
    }

    private static String displayFor(Object item) {
        try {
            Method method = item.getClass().getDeclaredMethod("displayLabel");
            method.setAccessible(true);
            return String.valueOf(method.invoke(item));
        } catch (Exception e) {
            return "<unknown>";
        }
    }

    private static String plain(Object item) {
        try {
            Field field = item.getClass().getDeclaredField("label");
            field.setAccessible(true);
            return String.valueOf(field.get(item));
        } catch (Exception e) {
            return "<unknown>";
        }
    }

    /** 收集当前内容区所有标签文字，用于判断实际打开的是哪个页面。 */
    private static List<String> contentLabels(Object main) {
        javax.swing.JPanel content = (javax.swing.JPanel) fieldQuiet(main, "content");
        List<String> texts = new ArrayList<String>();
        collectLabels(content, texts);
        return texts;
    }

    private static void collectLabels(java.awt.Container container, List<String> texts) {
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof javax.swing.JLabel) {
                String text = ((javax.swing.JLabel) child).getText();
                if (text != null && !text.isEmpty()) texts.add(text);
            }
            if (child instanceof java.awt.Container) collectLabels((java.awt.Container) child, texts);
        }
    }

    private static boolean hasDeclaredField(Object target, String name) {
        return ui.UiHandle.declares(target, name);
    }

    private static List<String> modeKeys(Object main) {
        try {
            Method method = main.getClass().getDeclaredMethod("modeKeys");
            method.setAccessible(true);
            return castList(method.invoke(main));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        return (List<String>) value;
    }

    /** 选中下拉框中指定的一项；不存在时保持原状，由调用处的断言暴露问题。 */
    private static void selectComboItem(Object main, Object widgets, String name, String wanted)
            throws Exception {
        final javax.swing.JComboBox<?> combo = (javax.swing.JComboBox<?>) fieldQuiet(widgets, name);
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (wanted.equals(String.valueOf(combo.getItemAt(index)))) {
                final int target = index;
                onEdt(new Runnable() {
                    public void run() { combo.setSelectedIndex(target); }
                });
                return;
            }
        }
    }

    /** 点击页面上的某个按钮：必须走真实点击路径，否则动作监听器不会触发。 */
    /** 把标签文字拼成一段，便于按子串判定某一行是否渲染出来。 */
    private static String joined(List<String> texts) {
        StringBuilder builder = new StringBuilder();
        for (String text : texts) builder.append(text).append('\n');
        return builder.toString();
    }

    /** 收集某个容器控件内的全部标签文字，用于判断渲染出了哪些行。 */
    private static List<String> labels(Object target, String name) {
        List<String> texts = new ArrayList<String>();
        collectLabels((java.awt.Container) fieldQuiet(target, name), texts);
        return texts;
    }

    /**
     * 等到状态标签不再是「进行中」那一条。
     *
     * <p>生成载荷在后台线程里跑，点完按钮立刻读状态只会读到「正在生成…」。
     * 这里按固定间隔轮询，超时后把最后一次读到的文本交回，由调用处的断言暴露问题。
     */
    private static String awaitStatus(Object main, String field, String inProgress, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String text = "";
        while (System.currentTimeMillis() < deadline) {
            text = ((javax.swing.JLabel) fieldQuiet(main, field)).getText();
            if (text != null && !text.equals(inProgress)) return text;
            Thread.sleep(150);
        }
        return text == null ? "" : text;
    }

    private static void clickButton(Object widgets, String name) throws Exception {
        final javax.swing.AbstractButton button =
                (javax.swing.AbstractButton) fieldQuiet(widgets, name);
        onEdt(new Runnable() {
            public void run() { button.doClick(); }
        });
    }

    /** 参数行里是否存在指定引擎键（例如 Clojure.cmd）。 */
    private static boolean hasParamField(Object widgets, String key) throws Exception {
        java.util.List<?> fields = (java.util.List<?>) fieldQuiet(widgets, "fields");
        for (Object field : fields) {
            java.lang.reflect.Field declared = field.getClass().getDeclaredField("key");
            declared.setAccessible(true);
            if (key.equals(String.valueOf(declared.get(field)))) return true;
        }
        return false;
    }

    private static void check(String message, boolean condition) {
        if (!condition) {
            System.err.println("自检失败: " + message);
            // AWT 事件线程不是守护线程：失败后必须显式退出，否则进程会一直挂着
            System.exit(1);
        }
        System.out.println("  [ok] " + message);
    }

    private static void snapshot(final JFrame frame, String path) throws Exception {
        File file = new File(path);
        file.getParentFile().mkdirs();
        final BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        // 截图必须在 EDT 上、且排在已排队的重绘之后：在其他线程直接
        // frame.paint() 会与 EDT 的重绘交错，把上一次导航状态叠在新状态上（重影）。
        onEdt(new Runnable() {
            public void run() {
                frame.repaint();
                frame.paint(image.getGraphics());
            }
        });
        ImageIO.write(image, "png", file);
        System.out.println("  截图: " + path);
    }
}
