package ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import analyze.AnalyzeReport;

/**
 * 漏洞分析各页共用的后台执行器：把「不冻结界面 + 单动作互斥 + 结果回写」收在一处。
 *
 * <p>拆出来的理由：拆分「组件与漏洞」与「调用链查询」两页后，两页各自都需要这套逻辑。
 * 若各写一份，「同一时刻只允许一个动作」「状态行措辞」「建议按钮截断」这些细节
 * 会在两处慢慢走偏——而这套逻辑与页面本身无关，只与「怎么跑一次分析」有关。
 *
 * <p>互斥在**每个实例内部**生效：两页各有自己的动作集，同时跑不会互相干扰
 * （它们用的输入与产物都不同：一个只读 jar 元数据，一个写引擎数据库）。
 */
final class AnalyzeWorker {

    /** 页面回写：控制器不直接持有页面对象，与仓库既有约定一致。 */
    interface View {
        void setStatus(String text);
        void setOutput(String text);
        void setBusy(boolean busy);
        /** 按报告里的建议渲染跳转按钮。 */
        void showJumps(Map<String, String> jumps, Consumer<String> onJump);
    }

    /** 一次分析最多给出的建议按钮数：太多会让工具条挤成一片。 */
    private static final int MAX_JUMPS = 6;

    /** 一次可执行的动作。 */
    interface Task {
        AnalyzeReport run();
    }

    private final View view;
    private final Consumer<String> navigator;

    /** 是否有动作正在执行；用于拒绝并发。 */
    private volatile boolean busy;

    AnalyzeWorker(View view, Consumer<String> navigator) {
        this.view = view;
        this.navigator = navigator;
    }

    /** 是否正在执行；供控制器在动作开始前判断。 */
    boolean isBusy() {
        return busy;
    }

    /**
     * 在后台线程执行一个动作，并把结果回写到界面。
     *
     * <p>为什么全部走后台线程：选中的依赖目录可能有上百个 jar，调用链分析更是分钟级。
     * 放在事件分发线程会让界面假死，使用者会以为程序坏了。
     * 界面更新一律回到事件分发线程（{@code SwingUtilities.invokeLater}）。
     */
    void run(final String title, final Task task) {
        busy = true;
        view.setBusy(true);
        view.setStatus(title + " 执行中…");
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                AnalyzeReport report;
                try {
                    report = task.run();
                } catch (RuntimeException error) {
                    report = AnalyzeReport.failed(title, "执行失败：" + error.getMessage());
                }
                final AnalyzeReport result = report == null
                        ? AnalyzeReport.failed(title, "没有产生任何结果。") : report;
                javax.swing.SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        finish(title, result);
                    }
                });
            }
        }, "analyze-worker");
        worker.setDaemon(true);
        worker.start();
    }

    /** 停止本页占用的资源；当前没有常驻资源，保留接口以便后续扩展。 */
    void shutdown() {
        busy = false;
    }

    private void finish(String title, AnalyzeReport report) {
        busy = false;
        view.setBusy(false);
        String header = "===== " + title + " =====  " + (report.millis) + " ms"
                + System.lineSeparator() + System.lineSeparator();
        view.setOutput(header + report.text);
        if (report.ok) {
            view.setStatus(title + "完成（" + report.millis + " ms）"
                    + (report.hasJumps() ? "　可点击下方按钮继续利用" : ""));
        } else {
            view.setStatus(title + "未成功，原因见报告。");
        }
        view.showJumps(limit(report.jumps), new Consumer<String>() {
            @Override
            public void accept(String navKey) {
                if (navigator != null) navigator.accept(navKey);
            }
        });
    }

    /** 建议按钮按登记顺序截断，避免工具条被挤爆。 */
    static Map<String, String> limit(Map<String, String> jumps) {
        Map<String, String> limited = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : jumps.entrySet()) {
            if (limited.size() >= MAX_JUMPS) break;
            limited.put(entry.getKey(), entry.getValue());
        }
        return limited;
    }

    /** 建议按钮的文字与监听器分离，供各页的 fillJumps 使用。 */
    static List<String> labels(Map<String, String> jumps, List<java.awt.event.ActionListener> out,
                               final Consumer<String> onJump) {
        List<String> labels = new java.util.ArrayList<String>(jumps.values());
        for (String key : jumps.keySet()) {
            final String navKey = key;
            out.add(event -> onJump.accept(navKey));
        }
        return labels;
    }
}