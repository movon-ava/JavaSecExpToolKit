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

    public Finding(String component, String ruleId, String title, String detail, String suggestion,
                   Confidence confidence, String evidence, String navKey) {
        this.component = component == null ? "" : component;
        this.ruleId = ruleId == null ? "" : ruleId;
        this.title = title == null ? "" : title;
        this.detail = detail == null ? "" : detail;
        this.suggestion = suggestion == null ? "" : suggestion;
        this.confidence = confidence == null ? Confidence.LOW : confidence;
        this.evidence = evidence == null ? "" : evidence;
        this.navKey = navKey == null ? "" : navKey;
    }

    /** 是否可以直接跳到某个功能页。 */
    public boolean hasNav() {
        return !navKey.isEmpty();
    }

    /** 单行文本，供复制与日志使用。 */
    public String line() {
        return "[" + confidence.label() + "] " + component + " — " + title + "（" + evidence + "）";
    }

    @Override
    public String toString() {
        return line();
    }
}
