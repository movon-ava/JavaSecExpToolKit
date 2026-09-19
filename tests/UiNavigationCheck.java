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
        final Object main = newMain();
        final JFrame frame = (JFrame) field(main, "frame");
        onEdt(new Runnable() {
            public void run() { frame.setVisible(true); }
        });
        Thread.sleep(400);

        final JList<?> list = (JList<?>) field(main, "navigationList");
        List<?> collapsed = (List<?>) field(main, "navigationItems");
        System.out.println("初始导航项: " + labels(collapsed));
        check("初始应为 5 项（主页/配置/代理/FastJson/Shiro）", collapsed.size() == 5);
        check("初始含代理分类", labels(collapsed).get(2).startsWith("代理"));
        check("初始代理分类应显示收起箭头", labels(collapsed).get(2).endsWith("\u25b8"));
        check("初始 FastJson 应显示收起箭头", labels(collapsed).get(3).endsWith("\u25b8"));
        check("展开箭头位于文字右侧", labels(collapsed).get(3).indexOf("\u25b8") > labels(collapsed).get(3).indexOf("FastJson"));

        select(main, "fastjson");
        toggle(main);
        List<?> expanded = (List<?>) field(main, "navigationItems");
        System.out.println("展开后导航项: " + labels(expanded));
        check("展开后应为 6 项（含 Fastjson 探测）", expanded.size() == 6);
        check("展开后 FastJson 应显示展开箭头", labels(expanded).get(3).endsWith("\u25be"));
        System.out.println("展开后选中项: " + displayFor(list.getSelectedValue()));
        snapshot(frame, "target/ui-check/01-expanded.png");

        toggle(main);
        List<?> recollapsed = (List<?>) field(main, "navigationItems");
        System.out.println("收起后导航项: " + labels(recollapsed));
        check("再次点击应收起回 5 项", recollapsed.size() == 5);
        check("收起后 FastJson 应显示收起箭头", labels(recollapsed).get(3).endsWith("\u25b8"));

        select(main, "fastjson.detect");
        List<?> afterJump = (List<?>) field(main, "navigationItems");
        System.out.println("跳转探测页后: " + labels(afterJump));
        check("跳转二级功能应自动展开分类", afterJump.size() == 6);
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
        snapshot(frame, "target/ui-check/03-config.png");

        select(main, "capture");
        // 代理 / FastJson / Shiro 三个分类此前都已展开（5 个一级项 + 2 + 1 + 1）
        System.out.println("跳转抓包页后: " + labels(navItems(main)));
        check("抓包转换已并入代理分类且跳转会展开", labels(navItems(main)).contains("抓包转换")
                && labels(navItems(main)).size() == 9);
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

        select(main, "home");
        snapshot(frame, "target/ui-check/04-home.png");

        System.out.println("全部界面自检通过");
        System.exit(0);
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

    private static Object fieldQuiet(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                type.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException ignored) {
                // 继续向父类查找
            }
        }
        return false;
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
