package preset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条预设利用链的纯数据形态，界面直接渲染。
 *
 * <p>与上游的预设模型分开定义：上游的类带着 snakeyaml 绑定注解与可选字段，
 * 界面若直接依赖它，预设文件一改结构就要连界面一起改。这里只保留展示与生成需要的字段。
 */
public final class PresetItem {

    /** 链上一步：节点名与它的默认参数。 */
    public static final class Step {
        public final String id;
        public final String gadget;
        public final Map<String, String> args;

        Step(String id, String gadget, Map<String, String> args) {
            this.id = id == null ? "" : id;
            this.gadget = gadget == null ? "" : gadget;
            this.args = Collections.unmodifiableMap(new LinkedHashMap<String, String>(args));
        }
    }

    /** 一个可填写输入：界面对应一个控件。 */
    public static final class Input {
        public final String key;
        public final String label;
        public final String description;
        /** 取值类型，来自预设文件（string / boolean 等）。 */
        public final String type;
        public final String defaultValue;
        public final boolean required;
        /** 步骤 id -> 该输入要写进这一步的哪个参数。 */
        public final Map<String, String> targets;
        /** 可选值：键是取值，值是展示名；无约束时为空。 */
        public final Map<String, String> choices;

        Input(String key, String label, String description, String type, String defaultValue,
              boolean required, Map<String, String> targets, Map<String, String> choices) {
            this.key = key == null ? "" : key;
            this.label = label == null || label.trim().isEmpty() ? this.key : label;
            this.description = description == null ? "" : description;
            this.type = type == null ? "string" : type;
            this.defaultValue = defaultValue == null ? "" : defaultValue;
            this.required = required;
            this.targets = Collections.unmodifiableMap(new LinkedHashMap<String, String>(targets));
            this.choices = Collections.unmodifiableMap(new LinkedHashMap<String, String>(choices));
        }

        /**
         * 可选值的键，逗号分隔；没有可选值时空串。
         *
         * <p>界面据此决定渲染下拉框还是文本框——预设里写了候选值却给出文本框，
         * 使用者只能照着文档猜哪些拼写是合法的。
         */
        public String choicesText() {
            StringBuilder text = new StringBuilder();
            for (String value : choices.keySet()) {
                if (text.length() > 0) text.append(",");
                text.append(value);
            }
            return text.toString();
        }
    }

    public final String id;
    public final String name;
    public final String category;
    public final String description;
    public final List<String> tags;
    /** 可作为链首的载体名（上游的写法是大驼峰类名）。 */
    public final List<String> payloads;
    public final List<Step> steps;
    public final List<Input> inputs;

    PresetItem(String id, String name, String category, String description, List<String> tags,
               List<String> payloads, List<Step> steps, List<Input> inputs) {
        this.id = id == null ? "" : id;
        this.name = name == null || name.trim().isEmpty() ? this.id : name;
        this.category = category == null || category.trim().isEmpty() ? "其他" : category;
        this.description = description == null ? "" : description;
        this.tags = Collections.unmodifiableList(new ArrayList<String>(tags));
        this.payloads = Collections.unmodifiableList(new ArrayList<String>(payloads));
        this.steps = Collections.unmodifiableList(new ArrayList<Step>(steps));
        this.inputs = Collections.unmodifiableList(new ArrayList<Input>(inputs));
    }

    /** 链上节点名序列，用于列表行预览。 */
    public String chainText() {
        StringBuilder text = new StringBuilder();
        for (Step step : steps) {
            if (text.length() > 0) text.append(" -> ");
            text.append(step.gadget);
        }
        return text.toString();
    }

    /** 是否带可填写输入；没有输入的预设直接用默认参数生成。 */
    public boolean hasInputs() {
        return !inputs.isEmpty();
    }
}
