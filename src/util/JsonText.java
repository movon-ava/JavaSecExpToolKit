package util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 界面内部搬运请求头用的「扁平 JSON」解析：对象里每个值都是字符串。
 *
 * <p>刻意不引入 JSON 库：这里只需要处理界面自产的
 * {@code {"Cookie":"JWT=xxx"}} 这类结构，支持 {@code \"} {@code \\} {@code \n}
 * {@code \r} {@code \t} 这几种转义即可，不值得为此增加依赖。
 */
public final class JsonText {

    private JsonText() {
    }

    /** 解析扁平的「字符串 → 字符串」JSON 对象，失败返回 null。 */
    public static Map<String, String> flatObject(String text) {
        if (text == null) return null;
        String source = text.trim();
        if (source.isEmpty()) return new LinkedHashMap<String, String>();
        if (!source.startsWith("{") || !source.endsWith("}")) return null;
        Map<String, String> entries = new LinkedHashMap<String, String>();
        int index = 1;
        int end = source.length() - 1;
        while (true) {
            while (index < end && Character.isWhitespace(source.charAt(index))) index++;
            if (index >= end) break;
            if (source.charAt(index) == ',') { index++; continue; }
            if (source.charAt(index) != '"') return null;
            StringBuilder name = new StringBuilder();
            index = readString(source, index, name);
            if (index < 0) return null;
            while (index < end && Character.isWhitespace(source.charAt(index))) index++;
            if (index >= end || source.charAt(index) != ':') return null;
            index++;
            while (index < end && Character.isWhitespace(source.charAt(index))) index++;
            if (index >= end || source.charAt(index) != '"') return null;
            StringBuilder value = new StringBuilder();
            index = readString(source, index, value);
            if (index < 0) return null;
            entries.put(name.toString(), value.toString());
        }
        return entries;
    }

    /** 从 {@code index} 处的引号开始读一个 JSON 字符串；返回结束引号之后的下标，失败返回 -1。 */
    public static int readString(String source, int index, StringBuilder out) {
        if (index >= source.length() || source.charAt(index) != '"') return -1;
        index++;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '\\') {
                if (index + 1 >= source.length()) return -1;
                char escaped = source.charAt(index + 1);
                if (escaped == 'n') out.append('\n');
                else if (escaped == 'r') out.append('\r');
                else if (escaped == 't') out.append('\t');
                else if (escaped == 'b') out.append('\b');
                else if (escaped == 'f') out.append('\f');
                else out.append(escaped);
                index += 2;
                continue;
            }
            if (current == '"') return index + 1;
            out.append(current);
            index++;
        }
        return -1;
    }

    /** 取扁平 JSON 对象里某个字段（不区分大小写），拿不到时返回空串。 */
    public static String flatValue(String json, String key) {
        Map<String, String> entries = flatObject(json);
        if (entries == null) return "";
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return "";
    }

    /** 从报告文本里提取某个 JSON 字段（如 url / cookie-header）。 */
    public static String field(String report, String key) {
        Matcher matcher = Pattern
                .compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(report == null ? "" : report);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** 从原始报文里取请求行的目标（路径 / URL）。 */
    public static String requestTarget(String report) {
        Matcher matcher = Pattern
                .compile("(?m)^\\s*(?:GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+(\\S+)\\s+HTTP/")
                .matcher(report == null ? "" : report);
        return matcher.find() ? matcher.group(1) : "";
    }
}

