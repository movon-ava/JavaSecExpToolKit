package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.util.function.Consumer;

/**
 * 主页：标题区 + 各功能入口卡片。
 *
 * <p>卡片只负责「跳到哪个导航项」，跳转动作由界面层注入的回调完成，
 * 页面本身不持有导航状态。
 */
public final class HomePage {

    private static final String HERO_SUBTITLE = "选择功能分类，开始已授权的安全测试工作。";

    private HomePage() {
    }

    /**
     * @param onOpen 点击卡片时回调目标导航项的 key
     */
    public static JPanel build(UiKit.FontSink sink, Consumer<String> onOpen) {
        JPanel page = UiKit.page();
        JPanel hero = new JPanel();
        hero.setOpaque(false);
        hero.setLayout(new BoxLayout(hero, BoxLayout.Y_AXIS));
        JLabel eyebrow = UiKit.label("WORKSPACE", Font.BOLD, 12, UiKit.ACCENT, sink);
        JLabel title = UiKit.label("安全测试工具箱", Font.BOLD, 32, UiKit.TEXT, sink);
        JLabel subtitle = UiKit.label(HERO_SUBTITLE, Font.PLAIN, 16, UiKit.MUTED, sink);
        title.setBorder(BorderFactory.createEmptyBorder(7, 0, 7, 0));
        hero.add(eyebrow);
        hero.add(title);
        hero.add(subtitle);
        page.add(hero, BorderLayout.NORTH);

        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(38, 0, 0, 0));
        GridBagConstraints c = UiKit.constraints();
        c.weighty = 1;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        body.add(card("检测分类", "FastJson",
                "Fastjson 相关探测功能，从左侧 FastJson 分组展开进入。",
                "打开探测", "fastjson.detect", sink, onOpen), c);
        c.weighty = 0;
        c.insets = new Insets(18, 0, 0, 0);
        body.add(card("代理工具", "代理抓包",
                "本地 HTTP 代理，浏览器 / 插件流量指过来即可实时看到请求与响应。",
                "打开代理", "proxy.mitm", sink, onOpen), c);
        c.insets = new Insets(18, 0, 0, 0);
        body.add(card("抓包工具", "抓包与转换",
                "发送一次请求并保留原始响应，解析粘贴报文，导出 Cookie / cURL / JSON 等格式。",
                "打开抓包", "capture", sink, onOpen), c);
        page.add(body, BorderLayout.CENTER);
        return page;
    }

    private static JPanel card(String tagText, String titleText, String detailText, String buttonText,
                              final String navKey, UiKit.FontSink sink, final Consumer<String> onOpen) {
        JPanel card = UiKit.surface(new GridBagLayout());
        card.setPreferredSize(new Dimension(580, 200));
        GridBagConstraints c = UiKit.constraints();
        c.gridy = 0; card.add(UiKit.label(tagText, Font.BOLD, 12, UiKit.ACCENT, sink), c);
        c.gridy = 1; c.insets = new Insets(8, 0, 7, 0);
        card.add(UiKit.label(titleText, Font.BOLD, 20, UiKit.TEXT, sink), c);
        c.gridy = 2; c.insets = new Insets(0, 0, 22, 0);
        card.add(UiKit.label(detailText, Font.PLAIN, 14, UiKit.MUTED, sink), c);
        c.gridy = 3; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        JButton open = UiKit.primaryButton(buttonText, sink);
        open.addActionListener(e -> onOpen.accept(navKey));
        card.add(open, c);
        return card;
    }
}
