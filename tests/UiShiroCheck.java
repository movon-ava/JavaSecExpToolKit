import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Shiro 页面自检：控件齐备、一键检测可跑通、链生成有输出。
 *
 * 用法：java --add-opens java.xml/com.sun.org.apache.xalan.internal.xsltc.trax=ALL-UNNAMED
 *              --add-opens java.xml/com.sun.org.apache.xalan.internal.xsltc.runtime=ALL-UNNAMED
 *              -cp "lib/java-chains-cli-2.0.0-beta4.jar;target/ui-check;target/classes" UiShiroCheck
 */
public final class UiShiroCheck {

    private static final String GOOD_KEY = "kPH+bIxk5D2deZiIxcaaaA==";

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new MockShiroHandler());
        server.start();
        int port = server.getAddress().getPort();

        final Object main = newMain();
        final JFrame frame = (JFrame) field(main, "frame");
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() { frame.setVisible(true); }
        });
        Thread.sleep(400);

        onEdt(new Runnable() {
            public void run() { invoke(main, "selectNav", new Class<?>[]{String.class}, "shiro.exploit"); }
        });
        Thread.sleep(400);

        check("Shiro 页含目标 URL 输入框", field(main, "shiroUrl") instanceof JTextField);
        check("Shiro 页含请求方法下拉框", field(main, "shiroRequestMethod") instanceof JComboBox);
        check("Shiro 页含 Cookie 名输入框", field(main, "shiroCookieName") instanceof JTextField);
        check("Shiro 页含密钥输入框", field(main, "shiroKey") instanceof JTextField);
        check("Shiro 页含 GCM 勾选框", field(main, "shiroGcm") instanceof AbstractButton);
        check("Shiro 页含利用链下拉框", field(main, "shiroChain") instanceof JComboBox);
        check("Shiro 页含回显请求头输入框", field(main, "shiroEchoHeader") instanceof JTextField);
        check("Shiro 页含命令输入框", field(main, "shiroCommand") instanceof JTextField);
        check("Shiro 页含附加请求头文本域", field(main, "shiroHeaders") instanceof JTextArea);
        check("Shiro 页含检测按钮", field(main, "shiroDetect") instanceof AbstractButton);
        check("Shiro 页含爆破按钮", field(main, "shiroCrack") instanceof AbstractButton);
        check("Shiro 页含停止按钮", field(main, "shiroStop") instanceof AbstractButton);
        check("Shiro 页含生成 Payload 按钮", field(main, "shiroBuild") instanceof AbstractButton);
        check("Shiro 页含执行命令按钮", field(main, "shiroRun") instanceof AbstractButton);
        check("Shiro 页含指纹检测回显框", field(main, "shiroDetectOutput") instanceof JTextArea);
        check("Shiro 页含密钥爆破回显框", field(main, "shiroCrackOutput") instanceof JTextArea);
        check("Shiro 页含生成 Payload 回显框", field(main, "shiroBuildOutput") instanceof JTextArea);
        check("Shiro 页含执行命令回显框", field(main, "shiroRunOutput") instanceof JTextArea);
        javax.swing.JTabbedPane tabs = (javax.swing.JTabbedPane) field(main, "shiroOutputTabs");
        check("Shiro 四个功能各有独立回显页签", tabs.getTabCount() == 4);

        JComboBox<?> chain = (JComboBox<?>) field(main, "shiroChain");
        check("利用链下拉框有 3 个选项", chain.getItemCount() == 3);

        final String url = "http://127.0.0.1:" + port + "/index";
        onEdt(new Runnable() {
            public void run() {
                ((JTextField) fieldQuiet(main, "shiroUrl")).setText(url);
                ((JTextField) fieldQuiet(main, "shiroKey")).setText(GOOD_KEY);
            }
        });
        onEdt(new Runnable() {
            public void run() { ((AbstractButton) fieldQuiet(main, "shiroDetect")).doClick(); }
        });
        String output = waitFor(main, "shiroDetectOutput", "确认存在 Shiro", 30000);
        check("一键检测在界面上确认 Shiro", output.contains("确认存在 Shiro"));

        onEdt(new Runnable() {
            public void run() { ((AbstractButton) fieldQuiet(main, "shiroBuild")).doClick(); }
        });
        output = waitFor(main, "shiroBuildOutput", "payload 生成", 60000);
        check("界面可生成 Shiro 回显链", output.contains("payload 生成成功"));
        check("生成的 payload 为 Base64 文本", output.contains("字符。"));
        check("生成 Payload 不会写进指纹检测回显框",
                !((JTextArea) fieldQuiet(main, "shiroDetectOutput")).getText().contains("payload 生成成功"));
        check("指纹检测结论仍保留在自己的回显框里",
                ((JTextArea) fieldQuiet(main, "shiroDetectOutput")).getText().contains("确认存在 Shiro"));

        snapshot(frame, "target/ui-check/07-shiro.png");
        server.stop(0);
        System.out.println("Shiro 界面自检通过");
        System.exit(0);
    }

    private static String waitFor(Object main, String field, String needle, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String text = "";
        while (System.currentTimeMillis() < deadline) {
            text = ((JTextArea) fieldQuiet(main, field)).getText();
            if (text.contains(needle)) return text;
            Thread.sleep(200);
        }
        String status = String.valueOf(((javax.swing.JLabel) fieldQuiet(main, "shiroStatus")).getText());
        System.err.println("等待超时: " + needle + " 当前状态=" + status + " 输出=" + text);
        System.exit(1);
        return text;
    }

    private static final class MockShiroHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            String rememberMe = cookieValue(cookie);
            boolean ok = rememberMe == null || decrypts(rememberMe);
            if (!ok) {
                exchange.getResponseHeaders().add("Set-Cookie", "rememberMe=deleteMe; Path=/; Max-Age=0");
            }
            byte[] body = ("{\"ok\":" + ok + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            OutputStream output = exchange.getResponseBody();
            output.write(body);
            output.close();
        }

        private boolean decrypts(String value) {
            try {
                byte[] cipher = Base64.getDecoder().decode(value);
                byte[] key = shiro.ShiroEngine.decodeBase64(GOOD_KEY);
                return shiro.ShiroEngine.decrypt(cipher, key, false) != null
                        || shiro.ShiroEngine.decrypt(cipher, key, true) != null;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        private String cookieValue(String header) {
            if (header == null) return null;
            for (String part : header.split(";")) {
                String text = part.trim();
                if (text.startsWith("rememberMe=")) return text.substring("rememberMe=".length());
            }
            return null;
        }
    }

    private static Object newMain() throws Exception {
        Constructor<?> constructor = Class.forName("Main").getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
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

    private static Object invoke(Object target, String name, Class<?>[] types, Object argument) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, argument);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private static void check(String message, boolean condition) {
        if (!condition) {
            System.err.println("自检失败: " + message);
            System.exit(1);
        }
        System.out.println("  [ok] " + message);
    }

    private static void snapshot(final JFrame frame, String path) throws Exception {
        File file = new File(path);
        file.getParentFile().mkdirs();
        final BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
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