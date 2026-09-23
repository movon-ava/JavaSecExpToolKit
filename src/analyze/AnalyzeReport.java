package analyze;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import analyzer.Finding;

/**
 * 一次分析动作的产出：报告文本 + 可跳转的建议动作。
 *
 * <p>把「文本」与「建议」分开装，是因为界面要拿建议渲染成跳转按钮，
 * 而文本要整体复制。若只给一段拼接好的字符串，界面就得反向解析文本才能知道
 * 有哪些按钮——那等于把报告格式变成接口协议，改一次措辞就会让按钮失效。
 */
public final class AnalyzeReport {

    /** 报告标题，例如「本地依赖分析」。 */
    public final String title;
    /** 报告正文，可直接显示与复制。 */
    public final String text;
    /** 本次动作的成功与否；失败时 text 里含原因。 */
    public final boolean ok;
    /** 耗时（毫秒），用于判断是否需要提示「太慢」。 */
    public final long millis;
    /** 结论清单；可能为空（例如只是查一次数据库）。 */
    public final List<Finding> findings;
    /** 额外的可跳转入口：功能页 key → 按钮文字。 */
    public final Map<String, String> jumps;
    /**
     * 交接给利用功能页的上下文：这条建议从哪来、依据是什么。
     *
     * <p>为空表示本次动作没有产生可交接的结论（例如只是一次失败）。
     * 界面据此显示「由哪条分析结论带入」，并把它写进按钮提示；
     * 上下文只用于**展示**，不预填命令 / 回连地址这类高风险参数。
     */
    public final AnalysisContext context;

    private AnalyzeReport(String title, String text, boolean ok, long millis,
                          List<Finding> findings, Map<String, String> jumps) {
        this(title, text, ok, millis, findings, jumps, null);
    }

    private AnalyzeReport(String title, String text, boolean ok, long millis,
                          List<Finding> findings, Map<String, String> jumps,
                          AnalysisContext context) {
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        this.ok = ok;
        this.millis = millis;
        this.findings = Collections.unmodifiableList(
                findings == null ? new ArrayList<Finding>() : new ArrayList<Finding>(findings));
        this.jumps = Collections.unmodifiableMap(
                jumps == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(jumps));
        this.context = context;
    }

    /** 构造一个只带文本的结果（数据库查询、反编译等多走这条）。 */
    public static AnalyzeReport text(String title, String text, boolean ok, long millis) {
        return new AnalyzeReport(title, text, ok, millis, null, jumpTable(null));
    }

    /** 构造带结论的结果；跳转表由结论里的 navKey 去重得到。 */
    public static AnalyzeReport of(String title, String text, boolean ok, long millis,
                                   List<Finding> findings) {
        return new AnalyzeReport(title, text, ok, millis, findings, jumpTable(findings));
    }

    /** 失败结果：界面据此把状态行标红。 */
    public static AnalyzeReport failed(String title, String reason) {
        return new AnalyzeReport(title, reason, false, 0, null, jumpTable(null));
    }

    /**
     * 从结论里汇总跳转入口。
     *
     * <p>按 navKey 去重，但**保留按钮文字**：同一个页面可能被多条结论指向，
     * 只留第一个说明即可，避免界面出现一排同名按钮。
     */
    private static Map<String, String> jumpTable(List<Finding> findings) {
        Map<String, String> jumps = new LinkedHashMap<String, String>();
        if (findings == null) return jumps;
        for (Finding finding : findings) {
            if (!finding.hasNav() || jumps.containsKey(finding.navKey)) continue;
            jumps.put(finding.navKey, AnalyzeEngine.navLabel(finding.navKey));
        }
        return jumps;
    }

    /** 是否有可跳转的建议。 */
    public boolean hasJumps() {
        return !jumps.isEmpty();
    }

    /** 是否带上了可审阅的分析上下文。 */
    public boolean hasContext() {
        return context != null;
    }

    /** 附加分析上下文；返回新实例而不是改本对象，避免报告被就地改出两种状态。 */
    public AnalyzeReport withContext(AnalysisContext extra) {
        return new AnalyzeReport(title, text, ok, millis, findings, jumps, extra);
    }
}
