package ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动期一次性初始化：把「第一次进某个页面才付的代价」提前到启动时付完。
 *
 * <p>背景（实测）：java-chains 的 {@code ChainsRuntime.start()} 里最重的一步是
 * {@code MetadataRegistry.init()}，单独量约 1074 ms，连同插件与 gadget 注册合计约 1.2 s。
 * 这一步原先发生在**第一次进入 Payload 生成页**时，而且是在事件分发线程上同步执行，
 * 于是「第一次点 Payload / 恶意服务器 / 预设链」会整窗卡住一到两秒。
 *
 * <p>本类把那几步集中到启动期执行一次，页面之后无论进多少次都不再触发初始化：
 * <ul>
 *   <li>java-chains 引擎（节点与载体目录）；</li>
 *   <li>内置预设链（解析 jar 内的 {@code default-chains.yaml}）；</li>
 *   <li>恶意服务器适配器（上游协议适配器与生命周期服务的类加载）。</li>
 * </ul>
 *
 * <p>幂等：{@link #warmUp} 可以被启动画面线程与组合根各调用一次，第二次直接返回。
 * 每一步都单独兜住异常：预热失败不该让程序起不来，页面进页时会给出可读原因。
 */
public final class StartupWarmup {

    /** 进度回显：启动画面用；传 null 表示静默。 */
    public interface View {
        void step(String text);
    }

    private static final Object LOCK = new Object();

    private static volatile boolean done;
    private static volatile String summary = "尚未开始初始化。";
    /** 每一步的名字与耗时，供启动画面与自检查看。 */
    private static final List<String> STEPS = new ArrayList<String>();

    private StartupWarmup() {
    }

    /** 是否已完成初始化。 */
    public static boolean isDone() {
        return done;
    }

    /** 一行汇总：每步的名字与耗时，便于排查「启动变慢是哪一步」。 */
    public static String summary() {
        synchronized (LOCK) {
            return summary;
        }
    }

    /** 各步的名字与耗时，顺序与执行顺序一致。 */
    public static List<String> steps() {
        synchronized (LOCK) {
            return new ArrayList<String>(STEPS);
        }
    }

    /**
     * 执行启动期初始化；已执行过则立即返回。
     *
     * <p>加锁而不是只靠 volatile：两个线程同时首次进入时，后到的那个应当等待前一个做完，
     * 而不是自己也跑一遍（那样会重复付 1.2 s，还会让引擎初始化两次）。
     */
    public static void warmUp(View view) {
        synchronized (LOCK) {
            if (done) return;
            STEPS.clear();
            long startedAt = System.currentTimeMillis();

            report(view, "正在加载 java-chains 节点与载体…");
            step("java-chains 引擎", new Task() {
                @Override
                public void run() {
                    payload.PayloadEngine.init();
                }
            });

            report(view, "正在读取内置预设链…");
            step("内置预设链", new Task() {
                @Override
                public void run() {
                    PRESET_COUNT = preset.PresetCatalogService.load().size();
                }
            });

            report(view, "正在准备恶意服务器适配器…");
            step("恶意服务器适配器", new Task() {
                @Override
                public void run() {
                    new service.ServiceManager().endpoints();
                }
            });

            done = true;
            summary = "启动初始化完成，用时 " + (System.currentTimeMillis() - startedAt)
                    + " ms（" + join(STEPS) + "）。";
        }
    }

    /** 预热时读到的内置预设条数；读取失败时为 0。 */
    private static volatile int PRESET_COUNT;

    /** 预热时读到的内置预设条数。 */
    public static int presetCount() {
        return PRESET_COUNT;
    }

    /** 一步初始化：允许抛异常，由 {@link #step} 统一兜住并记录。 */
    private interface Task {
        void run() throws Exception;
    }

    private static void step(String name, Task task) {
        long startedAt = System.currentTimeMillis();
        try {
            task.run();
            STEPS.add(name + " " + (System.currentTimeMillis() - startedAt) + " ms");
        } catch (Exception error) {
            // 预热失败不能让程序起不来：进页时引擎自己会给出可读原因
            STEPS.add(name + " 失败：" + error.getClass().getSimpleName());
        }
    }

    private static void report(View view, String text) {
        if (view != null) view.step(text);
    }

    private static String join(List<String> items) {
        StringBuilder text = new StringBuilder();
        for (String item : items) {
            if (text.length() > 0) text.append("；");
            text.append(item);
        }
        return text.toString();
    }
}
