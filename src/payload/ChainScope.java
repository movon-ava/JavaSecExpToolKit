package payload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 节点归属规则：哪些节点属于「toString 链」功能，通用生成页不再列出。
 *
 * <p>本类存在的唯一理由是「一份规则两处用」：通用 Payload 生成页要按它把 toString 触发节点
 * 从候选里排除，toString 链功能页要按它确定可用模板。若两边各写一份清单，新增或改名一个
 * 触发节点时必然出现「一边有、一边没有」的漂移——那正是这次把 toString 链单独成页要解决的问题。
 *
 * <p>清单来自上游元数据实测（节点名以 {@code ToString} 结尾的触发节点，见
 * docs/DESIGN-payload.md 的实测表），不是按字符串猜的：{@code hessian2tostringpayload}
 * 是载体而不是触发节点，{@code GStringCompareToToString} 是按名字识别不出来的，
 * 二者都在这里显式登记。
 */
public final class ChainScope {

    /**
     * toString 触发节点：这些节点被单独放到「toString 链」功能里。
     *
     * <p>显式列出而不是按名字后缀判断：上游命名并不统一
     * （{@code GStringCompareToToString} 不以 ToString 结尾，{@code hessian2tostringpayload}
     * 虽是 toString 字样却是载体），按名字猜一定会漏或误伤。
     */
    private static final List<String> TOSTRING_NODES = Collections.unmodifiableList(Arrays.asList(
            "badattributevalueexpexceptiontostring",
            "caseinsensitivemap3tostring",
            "caseinsensitivemap4tostring",
            "eventlistenerlisttostring",
            "eventlistenerlisttostringhighjdk",
            "textandmnemonichashmaptostring",
            "textandmnemonichashmaptostringhighjdk",
            "textandmnemonichashtabletostring",
            "gstringcomparetotostring",
            "jacksontostring",
            "fastjsontostring1",
            "fastjsontostring2",
            "xbeantostring",
            "rometostringbean1",
            "rometostringbean2",
            "xstringtostring1",
            "xstringtostring2",
            "xstringtostring3",
            "xalanxstringtostring1",
            "xalanxstringtostring2",
            "xalanxstringtostring3",
            "audioformat$encodingtostring",
            "audiofileformat$typetostring"
    ));

    private ChainScope() {
    }

    /** 是否属于 toString 功能节点。 */
    public static boolean isToStringNode(String nodeId) {
        if (nodeId == null) return false;
        return TOSTRING_NODES.contains(nodeId.trim().toLowerCase(Locale.ROOT));
    }

    /** 全部 toString 触发节点，按登记顺序。 */
    public static List<String> toStringNodes() {
        return TOSTRING_NODES;
    }

    /** 运行时确实存在、且属于 toString 功能的节点；供界面显示「本次可用几条」。 */
    public static List<String> availableToStringNodes(List<String> runtimeNodeIds) {
        List<String> found = new ArrayList<String>();
        if (runtimeNodeIds == null) return found;
        for (String id : runtimeNodeIds) {
            if (isToStringNode(id)) found.add(id);
        }
        return found;
    }

    /** 登记了但运行时不存在（或反之）的说明清单，空表示一致。 */
    public static List<String> issues(List<String> runtimeNodeIds) {
        List<String> problems = new ArrayList<String>();
        if (runtimeNodeIds == null) return problems;
        List<String> runtime = new ArrayList<String>();
        for (String id : runtimeNodeIds) {
            if (id != null) runtime.add(id.trim().toLowerCase(Locale.ROOT));
        }
        for (String id : TOSTRING_NODES) {
            if (!runtime.contains(id)) problems.add("toString 清单里的节点不在运行时目录：" + id);
        }
        return problems;
    }

    /**
     * 通用生成页的候选过滤：剔除 toString 功能节点。
     *
     * <p>只过滤「候选」，不过滤载体，也不改动链的合法性判断：链是否成立仍由引擎决定，
     * 这里只决定「界面上把哪些节点收进另一个功能页」。
     */
    public static List<String> genericCandidates(List<String> nodeIds) {
        List<String> kept = new ArrayList<String>();
        if (nodeIds == null) return kept;
        for (String id : nodeIds) {
            if (!isToStringNode(id)) kept.add(id);
        }
        return kept;
    }
}
