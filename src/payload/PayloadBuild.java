package payload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次构建的完整结果：载荷本体 + 上下文 + 指标 + 逐步产物。
 *
 * <p>网页版 Generate 页在输出载荷之外还给出三块旁证，本类就是它们的载体：
 * CONTEXT 面板（每一步写进上下文的数据）、OUTPUT 元信息（耗时 / 大小 / MD5）、
 * 以及调试模式下每一步的中间产物。三者都只在构建成功时有意义，
 * 因此与 {@link PayloadResult} 一起返回，避免调用方为了拿上下文再构建一次——
 * 再构建一次会得到不同的随机类名，上下文与载荷就对不上了。
 */
public final class PayloadBuild {

    /** 载荷本体；失败时 {@code success} 为 false 且不携带载荷。 */
    public final PayloadResult result;
    /** 上下文条目，按上游给出的顺序；失败时为空。 */
    public final List<PayloadContextEntry> context;
    /** 构建耗时（毫秒）；上游没给指标时为 0。 */
    public final long durationMs;
    /** 原始载荷字节数；拿不到时为 -1。 */
    public final long rawSize;
    /** 原始载荷的 MD5；拿不到时为空串。 */
    public final String md5;
    /** 逐步产物，仅在开启调试生成时非空。 */
    public final List<Step> steps;

    /** 调试模式下的一步：第几步、哪个节点、产出了什么。 */
    public static final class Step {
        /** 步骤序号，0 是载体。 */
        public final int index;
        /** 节点显示名。 */
        public final String node;
        /** 产物类型描述，例如 byte[]、TemplatesImpl。 */
        public final String artifact;
        /** 产物字节数；不是字节形态时为 -1。 */
        public final int size;

        Step(int index, String node, String artifact, int size) {
            this.index = index;
            this.node = node == null ? "" : node;
            this.artifact = artifact == null ? "" : artifact;
            this.size = size;
        }
    }

    private PayloadBuild(PayloadResult result, List<PayloadContextEntry> context, long durationMs,
                         long rawSize, String md5, List<Step> steps) {
        this.result = result;
        this.context = Collections.unmodifiableList(context == null ? new ArrayList<PayloadContextEntry>() : context);
        this.durationMs = durationMs;
        this.rawSize = rawSize;
        this.md5 = md5 == null ? "" : md5;
        this.steps = Collections.unmodifiableList(steps == null ? new ArrayList<Step>() : steps);
    }

    /** 失败结果：只带原因，其余字段为空。 */
    public static PayloadBuild fail(String message) {
        return new PayloadBuild(PayloadResult.fail(message), null, 0L, -1L, "", null);
    }

    static PayloadBuild ok(PayloadResult result, List<PayloadContextEntry> context, long durationMs,
                           long rawSize, String md5, List<Step> steps) {
        return new PayloadBuild(result, context, durationMs, rawSize, md5, steps);
    }

    public boolean success() {
        return result != null && result.success;
    }

    public String message() {
        return result == null ? "" : result.message;
    }
}
