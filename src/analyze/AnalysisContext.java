package analyze;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 从分析结论交接给利用功能页的上下文：**这条建议是从哪来的、依据是什么**。
 *
 * <p>为什么需要它：只切换页面而不说来源，使用者到了载荷页就得自己回想
 * 「刚才报告的哪一条指向这里」。带上下文以后，目标页能显示「由哪条分析结论带入」，
 * 并且可以一键回到分析结果继续看。
 *
 * <p>三条硬约束（刻意写进本类的行为里）：
 * <ul>
 *   <li><b>只预填有明确映射的字段</b>：类名、常量、字符串都是**推测性**信息，
 *       不能自动塞进命令、回连地址、目标 URL 这类高风险参数——一旦自动填进去，
 *       使用者点一下「生成」就等于按推测向目标发包了。本类只提供展示文本，
 *       不提供任何「直接拿去执行」的字段。</li>
 *   <li><b>不自动执行</b>：本类不发起网络请求、不生成载荷、不启动服务，
 *       一切动作仍由使用者在目标页显式触发。</li>
 *   <li><b>不伪造预填</b>：没有明确映射时宁可只显示建议文字，也不猜一个值填进去。</li>
 * </ul>
 *
 * <p>本类不碰界面控件，可在无界面环境下被自检直接构造与断言。
 */
public final class AnalysisContext {

    /** 结论来源：哪一次分析动作产生的。 */
    public final String source;
    /** 目标标识：被分析的 jar / 目录 / 数据库路径。 */
    public final String target;
    /** 证据摘要：命中了什么（类、方法、特征 id）。 */
    public final List<String> evidence;
    /** 结论摘要：一句话说明为什么建议下一步去那个功能页。 */
    public final String summary;
    /** 数据来源状态：本次分析跑在哪份库上、是否完整。 */
    public final String dataSource;
    /** 产生本上下文的功能页导航 key；界面据此提供「返回分析结论」。 */
    public final String originKey;
    /** 生成时间（毫秒时间戳），用于判断上下文是否已过期。 */
    public final long generatedAt;
    /** 局限性说明：本类对应的分析没做什么，避免把推测读成结论。 */
    public final List<String> limitations;

    private AnalysisContext(String source, String originKey, String target, List<String> evidence,
                            String summary, String dataSource, List<String> limitations) {
        this.source = source == null ? "" : source;
        this.originKey = originKey == null ? "" : originKey;
        this.target = target == null ? "" : target;
        this.evidence = Collections.unmodifiableList(
                evidence == null ? new ArrayList<String>() : new ArrayList<String>(evidence));
        this.summary = summary == null ? "" : summary;
        this.dataSource = dataSource == null ? "" : dataSource;
        this.generatedAt = System.currentTimeMillis();
        this.limitations = Collections.unmodifiableList(
                limitations == null ? new ArrayList<String>() : new ArrayList<String>(limitations));
    }

    /**
     * 构造一个上下文。
     *
     * @param evidence 命中的证据；空列表表示这次只有建议、没有具体证据
     */
    public static AnalysisContext of(String source, String originKey, String target,
                                     List<String> evidence, String summary, String dataSource,
                                     List<String> limitations) {
        return new AnalysisContext(source, originKey, target, evidence, summary, dataSource,
                limitations);
    }

    /** 状态模型中本上下文对应的结论状态：交接的是假设，不是已确认的漏洞。 */
    public String status() {
        return "疑似（未验证）";
    }

    /** 生成时间是否超过给定分钟数：超时的上下文只提示、不再当作当前依据。 */
    public boolean olderThanMinutes(long minutes) {
        return System.currentTimeMillis() - generatedAt > minutes * 60L * 1000L;
    }

    /** 单行摘要，供界面横幅显示。 */
    public String banner() {
        StringBuilder text = new StringBuilder();
        text.append("由分析结论带入：").append(source.isEmpty() ? "漏洞分析" : source);
        if (!summary.isEmpty()) text.append("　·　").append(summary);
        if (!target.isEmpty()) text.append("　·　目标 ").append(target);
        return text.toString();
    }

    /** 多行详情，供界面展开查看或写进日志。 */
    public List<String> describe() {
        List<String> lines = new ArrayList<String>();
        lines.add("来源: " + (source.isEmpty() ? "（未记录）" : source));
        lines.add("结论状态: " + status());
        if (!originKey.isEmpty()) lines.add("返回位置: " + originKey);
        if (!target.isEmpty()) lines.add("分析目标: " + target);
        if (!summary.isEmpty()) lines.add("结论摘要: " + summary);
        if (!dataSource.isEmpty()) lines.add("数据来源: " + dataSource);
        if (evidence.isEmpty()) {
            lines.add("证据: （本次只有建议，没有可引用的具体证据）");
        } else {
            lines.add("证据:");
            for (String item : evidence) lines.add("  - " + item);
        }
        if (!limitations.isEmpty()) {
            lines.add("本次分析的局限:");
            for (String item : limitations) lines.add("  - " + item);
        }
        lines.add("预填策略: 只展示、不预填高风险参数（命令 / 回连地址 / 目标 URL 需由使用者显式填写）");
        return lines;
    }

    /** 是否没有任何可引用的证据。 */
    public boolean hasEvidence() {
        return !evidence.isEmpty();
    }
}
