import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 自检期间的副作用清理：把自检过程中弹出来的计算器进程关掉。
 *
 * <p>java-chains 的 {@code Clojure} / {@code Exec} 一类节点在**构建期**就会执行参数里的命令，
 * 而上游给这两个节点登记的默认参数值就是 {@code calc}。界面自检点一次「生成」、
 * 预设链自检点一次「生成」，测试机上就会各弹出一个计算器窗口，测试跑完窗口还留在桌面上。
 *
 * <p>做法是在自检开始时给「当前已经存在的计算器进程」拍一次快照，注册 JVM 退出钩子，
 * 退出时只关掉快照之后新起的那些。这样使用者自己开着的计算器不会被误杀，
 * 而自检弹出的窗口不会留在桌面上。用退出钩子而不是 try/finally，是因为自检结尾会调用
 * {@code System.exit}，finally 块在那条路径上不执行；用快照比对而不是直接按名字杀，
 * 是因为「按名字杀全部」会连使用者自己开的计算器一起关掉。
 *
 * <p>判定按进程映像名匹配（Windows 上为 {@code CalculatorApp.exe}），不依赖父子关系：
 * 命令由引擎内部转手启动，自检进程不一定是它的父进程。
 */
public final class TestProcessGuard {

    /** 进程映像名关键字：Windows 计算器为 CalculatorApp.exe。 */
    private static final String NEEDLE = "calculator";

    /** 退出钩子里扫描新进程的轮次：命令执行到窗口出现有延迟，只扫一次可能扫不到。 */
    private static final int SWEEP_ROUNDS = 5;

    /** 每轮之间的等待毫秒数。 */
    private static final long SWEEP_INTERVAL_MS = 300L;

    private TestProcessGuard() {
    }

    /** 装上下线清理：只清理本方法调用之后新起的计算器进程。 */
    public static void install(final String label) {
        // 快照必须现在拍：等到退出钩子里再拍，本次自检弹出来的进程就已经被当成「本来就有的」了
        final List<long[]> before = snapshot();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                List<Long> closed = closeSpawned(before);
                if (closed.isEmpty()) {
                    System.out.println("[calc-guard] " + label + "：本轮未弹出计算器进程。");
                } else {
                    System.out.println("[calc-guard] " + label + "：已关闭自检期间弹出的 "
                            + closed.size() + " 个计算器进程 " + closed + "。");
                }
            }
        }, "calc-guard"));
    }

    /** 当前计算器进程快照：每项为 {pid, 启动毫秒}；取不到启动时间时记 -1。 */
    private static List<long[]> snapshot() {
        final List<long[]> rows = new ArrayList<long[]>();
        ProcessHandle.allProcesses().forEach(new java.util.function.Consumer<ProcessHandle>() {
            @Override
            public void accept(ProcessHandle handle) {
                if (!isCalculator(handle)) return;
                rows.add(new long[]{handle.pid(), startMillis(handle)});
            }
        });
        return rows;
    }

    /**
     * 关掉快照之后新出现的计算器进程，返回被关掉的 pid。
     *
     * <p>每轮都按同一份「自检前快照」重扫，因此不需要记录上一轮关了谁：
     * 上一轮没杀掉的会在下一轮再次命中，已经退出的自然扫不到。
     */
    private static List<Long> closeSpawned(final List<long[]> before) {
        Set<Long> closed = new LinkedHashSet<Long>();
        for (int round = 0; round < SWEEP_ROUNDS; round++) {
            if (round > 0) {
                try {
                    Thread.sleep(SWEEP_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            final List<Long> batch = new ArrayList<Long>();
            ProcessHandle.allProcesses().forEach(new java.util.function.Consumer<ProcessHandle>() {
                @Override
                public void accept(ProcessHandle handle) {
                    if (!isCalculator(handle)) return;
                    long start = startMillis(handle);
                    for (long[] row : before) {
                        if (row[0] == handle.pid() && row[1] == start) return;
                    }
                    handle.destroyForcibly();
                    batch.add(Long.valueOf(handle.pid()));
                }
            });
            if (batch.isEmpty()) break;
            closed.addAll(batch);
        }
        return new ArrayList<Long>(closed);
    }

    private static boolean isCalculator(ProcessHandle handle) {
        String command = handle.info().command().orElse("").toLowerCase(java.util.Locale.ROOT);
        return command.contains(NEEDLE);
    }

    private static long startMillis(ProcessHandle handle) {
        java.util.Optional<java.time.Instant> start = handle.info().startInstant();
        return start.isPresent() ? start.get().toEpochMilli() : -1L;
    }
}
