package preset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.vulhub.javachains.web.preset.PresetCatalog;
import org.vulhub.javachains.web.preset.PresetCatalogRepository;
import org.vulhub.javachains.web.preset.PresetDefinition;
import org.vulhub.javachains.web.preset.PresetInput;
import org.vulhub.javachains.web.preset.PresetInputTarget;
import org.vulhub.javachains.web.preset.PresetStep;

/**
 * 预设利用链的读取：把 java-chains 内置的预设文件解析成界面能直接渲染的纯数据。
 *
 * <p>与 {@code service.ServiceManager} 一样，本类是本项目里唯一引用上游预设模型的类，
 * 目的是让界面不随上游结构调整而改动。
 *
 * <p>数据源是 jar 内的 {@code default-chains.yaml}（实测 52 条），
 * 通过上游自己的 {@link PresetCatalogRepository} 读取——不自己解析 YAML，
 * 避免「本地解析器与上游升级后的格式不同步」这类问题。
 */
public final class PresetCatalogService {

    private PresetCatalogService() {
    }

    /**
     * 读取中文内置预设。
     *
     * <p>读取失败时返回空清单而不是抛异常：预设只是辅助入口，
     * 缺了它界面上其它功能仍应可用，由调用方把原因显示在状态栏。
     */
    public static List<PresetItem> load() {
        try {
            PresetCatalogRepository repository = new PresetCatalogRepository();
            PresetCatalog catalog = repository.loadBuiltin(Locale.SIMPLIFIED_CHINESE);
            if (catalog == null || catalog.getPresets() == null) return new ArrayList<PresetItem>();
            List<PresetItem> items = new ArrayList<PresetItem>();
            for (PresetDefinition definition : catalog.getPresets()) {
                PresetItem item = convert(definition);
                if (item != null) items.add(item);
            }
            return items;
        } catch (Throwable error) {
            return new ArrayList<PresetItem>();
        }
    }

    /** 读取失败的原因，供界面提示；失败时返回空串。 */
    public static String loadError() {
        try {
            PresetCatalogRepository repository = new PresetCatalogRepository();
            PresetCatalog catalog = repository.loadBuiltin(Locale.SIMPLIFIED_CHINESE);
            if (catalog == null) return "java-chains 预设目录为空。";
            return "";
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            String message = cause.getMessage();
            return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        }
    }

    /** 全部分类，按首次出现顺序；用于分类筛选条。 */
    public static List<String> categories(List<PresetItem> items) {
        List<String> categories = new ArrayList<String>();
        if (items == null) return categories;
        for (PresetItem item : items) {
            if (!categories.contains(item.category)) categories.add(item.category);
        }
        return categories;
    }

    private static PresetItem convert(PresetDefinition definition) {
        if (definition == null) return null;
        if (Boolean.FALSE.equals(definition.getEnabled())) return null;

        List<String> payloads = new ArrayList<String>();
        if (definition.getPayloads() != null) {
            for (String payload : definition.getPayloads()) {
                if (payload != null && !payload.trim().isEmpty()) payloads.add(payload.trim());
            }
        }
        List<PresetItem.Step> steps = new ArrayList<PresetItem.Step>();
        if (definition.getChain() != null) {
            for (PresetStep step : definition.getChain()) {
                if (step == null || step.getGadget() == null) continue;
                Map<String, String> args = new LinkedHashMap<String, String>();
                if (step.getArgs() != null) {
                    for (Map.Entry<String, Object> entry : step.getArgs().entrySet()) {
                        if (entry.getKey() == null) continue;
                        args.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                    }
                }
                steps.add(new PresetItem.Step(step.getId(), step.getGadget(), args));
            }
        }
        List<PresetItem.Input> inputs = new ArrayList<PresetItem.Input>();
        if (definition.getInputs() != null) {
            for (PresetInput input : definition.getInputs()) {
                if (input == null) continue;
                Map<String, String> targets = new LinkedHashMap<String, String>();
                if (input.getTargets() != null) {
                    for (PresetInputTarget target : input.getTargets()) {
                        if (target == null) continue;
                        // kind=gadget 时 target.step 指向步骤 id，target.field 是要写入的参数名；
                        // 少数预设直接给 gadget 名而不是步骤 id，两种都收，由解析侧再兜底。
                        String step = target.getStep() == null ? "" : target.getStep().trim();
                        String field = target.getField() == null ? "" : target.getField().trim();
                        if (step.isEmpty() || field.isEmpty()) continue;
                        targets.put(step, field);
                    }
                }
                Map<String, String> choices = new LinkedHashMap<String, String>();
                if (input.getChoices() != null) {
                    for (Map.Entry<String, String> entry : input.getChoices().entrySet()) {
                        if (entry.getKey() == null) continue;
                        choices.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
                    }
                }
                inputs.add(new PresetItem.Input(input.getKey(), input.getLabel(), input.getDescription(),
                        input.getType(), input.getDefaultValue() == null ? "" : String.valueOf(input.getDefaultValue()),
                        Boolean.TRUE.equals(input.getRequired()), targets, choices));
            }
        }
        return new PresetItem(definition.getId(), definition.getName(), definition.getCategory(),
                definition.getDescription(), safe(definition.getTags()), payloads, steps, inputs);
    }

    private static List<String> safe(List<String> values) {
        List<String> items = new ArrayList<String>();
        if (values == null) return items;
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) items.add(value.trim());
        }
        return Collections.unmodifiableList(items);
    }
}
