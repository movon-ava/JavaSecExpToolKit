package analyzer;

/**
 * 一条分析结论：哪个组件、因为什么、可能是什么问题、建议去哪里进一步确认。
 *
 * <p>结论里必须有 {@code evidence} 与 {@code confidence} 两栏，这是刻意的：
 * 这个功能的输入是**别人的构建产物**，元数据可能缺失、可能是被改过的。
 * 只给「有漏洞」而不给「依据什么判定、有多可信」，会让人把推测当成事实。
 *
 * <p>{@code navKey} 让结论可以一键跳到对应功能页（探测 / 载荷 / Shiro / 服务），
 * 分析因此是起点而不是终点。
 */
public final class Finding {

    /** 可信度：由依赖元数据的来源决定，不靠主观估计。 */
    public enum Confidence {
        HIGH("高", "坐标来自 pom 元数据"),
        MEDIUM("中", "坐标来自 MANIFEST"),
        LOW("低", "坐标来自文件名推断");

        private final String label;
        private final String reason;

        Confidence(String label, String reason) {
            this.label = label;
            this.reason = reason;
        }

        /** 界面显示用的中文级别。 */
        public String label() {
            return label;
        }

        /** 该级别是怎么来的。 */
        public String reason() {
            return reason;
        }
    }

    /**
     * 结论的状态：把「观察到的事实」与「推断出的假设」分开。
     *
     * <p>为什么要单独一栏而不是用可信度兼任：可信度说的是**来源有多可靠**，
     * 状态说的是**验证走到哪一步**。静态规则命中属于「疑似」，
     * 即使用的是最可靠的 pom 元数据也一样——组件确实在，漏洞未必成立。
     * 两者压成一个分数会让人把「高可信的推测」读成「已确认的漏洞」。
     */
    public enum Status {
        OBSERVED("已观察", "事实来自构建产物元数据本身"),
        SUSPECTED("疑似", "由规则推断，尚未验证"),
        CONFIRMED("已确认", "已通过实际验证成立"),
        NOT_REPRODUCED("未能复现", "验证过但未成立"),
        UNKNOWN("未知", "证据或工具状态不足以判断");

        private final String label;
        private final String reason;

        Status(String label, String reason) {
            this.label = label;
            this.reason = reason;
        }

        /** 界面显示用的中文状态名。 */
        public String label() {
            return label;
        }

        /** 该状态的含义。 */
        public String reason() {
            return reason;
        }
    }

    /** 组件坐标，形如 {@code com.alibaba:fastjson:1.2.24}。 */
    public final String component;
    /** 规则标识，形如 {@code FJ-AUTOTYPE}；用于去重与对照规则表。 */
    public final String ruleId;
    /** 一句话结论。 */
    public final String title;
    /** 影响面或利用方式说明。 */
    public final String detail;
    /** 建议动作：指向本工具已有的哪个功能页。 */
    public final String suggestion;
    public final Confidence confidence;
    /** 判定依据：版本值、来源文件等。 */
    public final String evidence;
    /** 可跳转的功能页 key；为空表示本结论没有对应页面。 */
    public final String navKey;
    /** 验证状态；见 {@link Status}。 */
    public final Status status;
    /** 还缺哪些证据才能把状态从「疑似」推进到「已确认」。 */
    public final java.util.List<String> missingEvidence;

    public Finding(String component, String ruleId, String title, String detail, String suggestion,
                   Confidence confidence, String evidence, String navKey) {
        this(component, ruleId, title, detail, suggestion, confidence, evidence, navKey,
                Status.SUSPECTED, java.util.Collections.<String>emptyList());
    }

    /**
     * 带验证状态与缺失证据的结论。
     *
     * @param status          验证走到哪一步
     * @param missingEvidence 还缺什么证据才能确认；空表示这条结论本身不需要更多证据
     */
    public Finding(String component, String ruleId, String title, String detail, String suggestion,
                   Confidence confidence, String evidence, String navKey,
                   Status status, java.util.List<String> missingEvidence) {
        this.component = component == null ? "" : component;
        this.ruleId = ruleId == null ? "" : ruleId;
        this.title = title == null ? "" : title;
        this.detail = detail == null ? "" : detail;
        this.suggestion = suggestion == null ? "" : suggestion;
        this.confidence = confidence == null ? Confidence.LOW : confidence;
        this.evidence = evidence == null ? "" : evidence;
        this.navKey = navKey == null ? "" : navKey;
        this.status = status == null ? Status.UNKNOWN : status;
        this.missingEvidence = missingEvidence == null
                ? java.util.Collections.<String>emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(missingEvidence));
    }

    /** 是否可以直接跳到某个功能页。 */
    public boolean hasNav() {
        return !navKey.isEmpty();
    }

    /** 单行文本，供复制与日志使用。含状态，避免把「疑似」当成结论抄进工单。 */
    public String line() {
        return "[" + confidence.label() + "/" + status.label() + "] " + component + " — " + title
                + "（" + evidence + "）";
    }

    @Override
    public String toString() {
        return line();
    }
}
