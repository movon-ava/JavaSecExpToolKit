package analyzer;

import java.lang.reflect.Method;
import java.util.Iterator;

import util.Log;

/**
 * 终止进程及其全部子进程。
 *
 * <p>为什么用反射而不是直接调 {@code Process#descendants()}：那是 Java 9 才加入的 API，
 * 而本项目的源码级别锁在 release 8（见 {@code src/pom.xml} 的
 * {@code maven.compiler.release}）。直接调用让自检那套 {@code javac}（不带
 * {@code --release}）照样通过，只有走 {@code build.ps1} 的 Maven 构建才会报
 * 「找不到符号」——这个约束因此必须由代码本身守住，不能靠人工记得。
 *
 * <p>Java 8 运行时没有等价能力（{@code ProcessHandle} 本身也是 9+），
 * 所以这里的能力是**按运行期可用性降级**：能取到 {@code descendants()} 就收掉整棵树，
 * 取不到就只强杀父进程——总好过编译不过。
 *
 * <p>所有步骤都不抛出：终止发生在超时与退出路径上，那里再抛异常会盖掉真正的原因。
 */
public final class ProcessTree {

    /** Java 9+ 的 {@code ProcessHandle.destroyForcibly}；Java 8 运行期为 null。 */
    private static final Method HANDLE_DESTROY = lookupHandleDestroy();

    /** Java 9+ 的 {@code Process#descendants}；Java 8 运行期为 null。 */
    private static final Method DESCENDANTS = lookupDescendants();

    private ProcessTree() {
    }

    /**
     * 终止进程树。
     *
     * @param process 目标进程；为 null 时直接返回
     */
    public static void kill(Process process) {
        if (process == null) return;
        killDescendants(process);
        try {
            process.destroyForcibly();
        } catch (RuntimeException ended) {
            // 已退出：无需处理
            Log.debug("进程已退出，无需强杀：" + ended.getMessage());
        }
    }

    /** 逐个强杀子进程；能力不可用或实现不支持时静默跳过。 */
    private static void killDescendants(Process process) {
        if (DESCENDANTS == null) return;
        Object stream = null;
        try {
            stream = DESCENDANTS.invoke(process);
        } catch (ReflectiveOperationException unavailable) {
            return;
        } catch (RuntimeException unavailable) {
            return;
        }
        if (!(stream instanceof java.util.stream.Stream)) return;
        Iterator<?> handles = ((java.util.stream.Stream<?>) stream).iterator();
        while (handles.hasNext()) {
            destroyForcibly(handles.next());
        }
    }

    private static void destroyForcibly(Object handle) {
        if (handle == null || HANDLE_DESTROY == null) return;
        try {
            HANDLE_DESTROY.invoke(handle);
        } catch (ReflectiveOperationException ended) {
            // 子进程已退出，或该实现不支持强杀
            Log.debug("子进程强杀失败：" + ended);
        } catch (RuntimeException ended) {
            // 同上：终止路径不抛出
            Log.debug("子进程强杀异常：" + ended);
        }
    }

    private static Method lookupDescendants() {
        try {
            return Process.class.getMethod("descendants");
        } catch (NoSuchMethodException java8) {
            return null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static Method lookupHandleDestroy() {
        try {
            return Class.forName("java.lang.ProcessHandle").getMethod("destroyForcibly");
        } catch (ClassNotFoundException java8) {
            return null;
        } catch (NoSuchMethodException unavailable) {
            return null;
        }
    }
}