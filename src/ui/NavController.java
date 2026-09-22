package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;

/**
 * 侧边栏导航的行为：一级分类展开 / 收起、二级功能选中、页面路由。
 *
 * <p>从 {@code Main} 抽出的第一件事就是导航，因为它与任何功能页都无关：只依赖
 * {@link NavItem} 树与 {@link NavigationRenderer}。控件仍由界面层持有并注入，
 * 本类不 new 任何控件，因此反复进出页面不会累积状态（与各功能页的既有做法一致）。
 *
 * <p>路由本身不实现页面：目标页面由 {@link Routes} 回调界面层，导航只负责
 * 「选中哪一个 key」，避免导航反向依赖各功能页的装配代码。
 */
public final class NavController {

    /** 页面装配回调：key → 打开对应页面；界面层在装配时注入。 */
    public interface Routes {
        void open(String key);
    }

    private final JPanel sidebar;
    private final JList<NavItem> list;
    private final Routes routes;

    /** 导航树：一级分类的展开状态存在 NavItem 自己身上，跨次重建得以保留。 */
    private final List<NavItem> tree;

    /** 当前列表数据（展开后为「分类 + 其子项」的扁平序列）。 */
    private List<NavItem> items = new ArrayList<NavItem>();

    public NavController(JPanel sidebar, JList<NavItem> list, List<NavItem> tree, Routes routes) {
        this.sidebar = sidebar;
        this.list = list;
        this.tree = tree;
        this.routes = routes;
        this.items = new ArrayList<NavItem>(tree);
        // 列表数据在构造时一次挂上：JList 由界面层创建（不带数据），
        // 若等到第一次展开才 setListData，首屏侧边栏会是空的。
        list.setListData(this.items.toArray(new NavItem[0]));
    }

    /** 侧边栏宽度：随窗口缩放重算，由界面层在窗口尺寸变化时调用。 */
    public void scale(double scale) {
        if (sidebar != null) sidebar.setPreferredSize(UiKit.scaledSidebar(scale));
        if (list != null) list.setFixedCellHeight(UiKit.scaledNavRowHeight(scale));
    }

    /** 构建侧边栏面板并接好鼠标与选中监听。 */
    public static JPanel build(JList<NavItem> list, List<NavItem> tree, UiKit.FontSink sink,
                               NavController[] holder, Routes routes) {
        JPanel panel = new JPanel(new BorderLayout(0, 22));
        panel.setBackground(UiKit.SIDEBAR);
        panel.setBorder(BorderFactory.createEmptyBorder(28, 20, 24, 20));
        panel.setPreferredSize(new Dimension(UiKit.SIDEBAR_WIDTH, 0));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel brand = UiKit.label("JavaSec", Font.BOLD, 22, Color.WHITE, sink);
        JLabel product = UiKit.label("EXP TOOLKIT", Font.PLAIN, 11, new Color(148, 163, 184), sink);
        product.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        header.add(brand);
        header.add(product);
        panel.add(header, BorderLayout.NORTH);

        final NavController controller = new NavController(panel, list, tree, routes);
        holder[0] = controller;

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        list.setBackground(UiKit.SIDEBAR);
        list.setForeground(new Color(226, 232, 240));
        list.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        list.setFixedCellHeight(UiKit.NAV_ROW_HEIGHT);
        list.setCellRenderer(new NavigationRenderer());
        sink.track(list, Font.PLAIN, 15);
        list.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (index < 0 || index >= controller.items.size()) return;
                NavItem hit = controller.items.get(index);
                if (!hit.hasChildren()) return;
                // locationToIndex 会把列表下方空白回落到最后一行，必须先确认命中的是真实单元格
                Rectangle bounds = list.getCellBounds(index, index);
                if (bounds == null || !bounds.contains(event.getPoint())) return;
                // 一级分类：点击该行展开二级子功能，再次点击收起
                controller.toggleGroup(hit);
            }
        });
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) controller.openSelected();
        });
        panel.add(list, BorderLayout.CENTER);

        JLabel footer = UiKit.label("v0.1  |  授权探测", Font.PLAIN, 12, new Color(148, 163, 184), sink);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    /** 展开 / 收起一级分类。分组项本身不是页面，因此只由鼠标点击触发。 */
    public void toggleGroup(NavItem group) {
        NavItem existing = findNavItem(group.key);
        if (existing != null) group = existing;
        if (group.children.isEmpty()) return;
        group.expanded = !group.expanded;
        rebuild(group);
    }

    public NavItem findNavItem(String key) {
        return findNavItem(tree, key);
    }

    private static NavItem findNavItem(List<NavItem> items, String key) {
        for (NavItem item : items) {
            if (item.key.equals(key)) return item;
            NavItem found = findNavItem(item.children, key);
            if (found != null) return found;
        }
        return null;
    }

    /** 重建列表数据：展开状态属于每个分组自己，逐个判断而不是只补当前分组。 */
    public void rebuild(NavItem group) {
        String selectedKey = list.getSelectedValue() == null ? "home" : list.getSelectedValue().key;
        List<NavItem> rebuilt = new ArrayList<NavItem>();
        for (NavItem item : tree) {
            rebuilt.add(item);
            if (item.expanded) rebuilt.addAll(item.children);
        }
        items = rebuilt;
        list.setListData(items.toArray(new NavItem[0]));
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).key.equals(selectedKey)) {
                list.setSelectedIndex(index);
                break;
            }
        }
        list.revalidate();
        list.repaint();
    }

    /** 按 key 选中导航项；目标在收起的分类里时先把分类展开。 */
    public void selectNav(String key) {
        NavItem target = findNavItem(key);
        if (target != null && target.parent != null && !target.parent.expanded) {
            target.parent.expanded = true;
            rebuild(target.parent);
        }
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).key.equals(key)) {
                list.setSelectedIndex(index);
                return;
            }
        }
    }

    /** 当前扁平序列，供界面层断言与调试使用。 */
    public List<NavItem> items() {
        return items;
    }

    /** 二级功能 / 叶子项被选中时打开对应页面。 */
    private void openSelected() {
        NavItem item = list.getSelectedValue();
        if (item == null) return;
        if (item.hasChildren()) {
            // 分组项本身不是页面：展开/收起只由鼠标点击触发，避免同一次点击被切换两次
            return;
        }
        if (routes != null) routes.open(item.key);
    }

    /** 默认导航树：一级分类顺序对齐网页版 java-chains。 */
    public static List<NavItem> defaultTree() {
        return Arrays.asList(
                new NavItem("home", "主页"),
                new NavItem("payload", "Payload", Arrays.asList(
                        new NavItem("payload.build", "Payload 生成"),
                        new NavItem("payload.preset", "预设链"))),
                new NavItem("service", "服务", Arrays.asList(
                        new NavItem("service.servers", "恶意服务器"),
                        new NavItem("shiro.exploit", "Shiro 漏洞利用"))),
                new NavItem("proxy", "代理", Arrays.asList(
                        new NavItem("proxy.mitm", "代理抓包"),
                        new NavItem("capture", "抓包转换"))),
                new NavItem("fastjson", "FastJson", Arrays.asList(
                        new NavItem("fastjson.detect", "Fastjson 探测"))),
                new NavItem("tools", "小工具", Arrays.asList(
                        new NavItem("tools.upload", "文件上传"))),
                new NavItem("config", "配置"));
    }
}
