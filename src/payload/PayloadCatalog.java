package payload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 载体目录：按实际用途给 payload 载体分组。
 *
 * <p>运行时载体有二十余种，直接按字母序铺在界面上几乎无法挑选；
 * 按用途分组（序列化、JSON 解析器、JNDI、框架专用……）后才是可用的选择界面。
 *
 * <p>分组表只描述「怎么展示」，不参与构建：构建时用到的载体 id 仍以运行时目录为准。
 * 因此这里的分组与运行时不一致时，由 {@link PayloadEngine#groupIssues} 报出来，
 * 而不是让界面悄悄漏掉某个载体。
 */
public final class PayloadCatalog {

    /** 分组顺序即界面展示顺序。 */
    private static final String[] GROUPS = {
        "序列化（通用）",
        "JSON 解析器",
        "JNDI",
        "框架专用",
        "特殊协议",
        "MySQL 伪装",
        "其他"
    };

    private static final Map<String, List<String>> MEMBERS = buildMembers();

    private PayloadCatalog() {
    }

    private static Map<String, List<String>> buildMembers() {
        Map<String, List<String>> members = new LinkedHashMap<String, List<String>>();
        members.put("序列化（通用）", Arrays.asList(
                "javanativepayload", "hessianpayload", "hessian2payload", "hessian2tostringpayload",
                "objectpayload"));
        members.put("JSON 解析器", Arrays.asList("fastjsonpayload", "xstreampayload"));
        members.put("JNDI", Arrays.asList(
                "jndibasicpayload", "jndireferencepayload", "jndildapdeserializepayload",
                "jndirmideserializepayload", "jndibrutechainpayload", "jndirefbypasspayload",
                "jndiresourcerefpayload"));
        members.put("框架专用", Arrays.asList(
                "shiropayload", "xmldecoderpayload", "jsfpayload", "expressionpayload", "jdbcpayload"));
        members.put("特殊协议", Arrays.asList(
                "blazedsamf3ampayload", "blazedsamf3remotingpayload"));
        members.put("MySQL 伪装", Arrays.asList(
                "fakemysqlpayload", "fakemysqlreadpayload", "fakemysqlbrutechainpayload"));
        members.put("其他", Arrays.asList(
                "bytecodepayload", "jrmplistenerpayload", "usercustompayload", "otherpayload"));
        return Collections.unmodifiableMap(members);
    }

    /** 全部分组名，按展示顺序。 */
    public static List<String> groups() {
        return Collections.unmodifiableList(Arrays.asList(GROUPS));
    }

    /** 某分组下的载体，顺序与表中写法一致。 */
    public static List<String> membersOf(String group) {
        List<String> members = MEMBERS.get(group);
        return members == null ? Collections.<String>emptyList() : members;
    }

    /** 载体所属分组；不在表中时归入「其他」。 */
    public static String groupOf(String payloadId) {
        if (payloadId == null) return "其他";
        for (Map.Entry<String, List<String>> entry : MEMBERS.entrySet()) {
            if (entry.getValue().contains(payloadId)) return entry.getKey();
        }
        return "其他";
    }

    /** 表里登记的载体总数（允许出现同一载体登记在两个分组，这里按去重计数）。 */
    public static int cataloguedCount() {
        TreeSet<String> unique = new TreeSet<String>();
        for (List<String> members : MEMBERS.values()) unique.addAll(members);
        return unique.size();
    }

    /**
     * 目录与运行时不一致时的说明清单，空表示一致。
     *
     * <p>不一致必须能被发现：漏登记会让界面少一个可选载体，多登记会让界面出现
     * 一个点了就报错的项——两种都不该悄悄发生。
     */
    public static List<String> diff(List<String> runtimePayloadIds) {
        List<String> issues = new ArrayList<String>();
        List<String> runtime = new ArrayList<String>();
        if (runtimePayloadIds != null) {
            for (String id : runtimePayloadIds) {
                if (id != null && !id.trim().isEmpty()) runtime.add(id.trim());
            }
        }
        TreeSet<String> catalogued = new TreeSet<String>();
        for (List<String> members : MEMBERS.values()) catalogued.addAll(members);

        for (String id : runtime) {
            if (!catalogued.contains(id)) issues.add("运行时载体未登记分组：" + id);
        }
        for (String id : catalogued) {
            if (!runtime.contains(id)) issues.add("分组内载体不在运行时目录：" + id);
        }
        // 同一载体登记在两个分组时，界面会出现两个同名项，必须报出来
        for (Map.Entry<String, List<String>> entry : MEMBERS.entrySet()) {
            List<String> members = entry.getValue();
            for (int index = 0; index < members.size(); index++) {
                if (members.indexOf(members.get(index)) != index) {
                    issues.add("分组内载体重复：" + entry.getKey() + " / " + members.get(index));
                }
            }
        }
        return issues;
    }
}
