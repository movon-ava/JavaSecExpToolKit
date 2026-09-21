package ui;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 界面控件的稳定门面：把「控件叫什么」与「控件声明在哪个类」解耦。
 *
 * <p>自检需要读取界面层持有的控件（断言可见性、默认值、联动状态）。此前这些断言直接
 * 反射界面类的字段，于是任何一次拆分都必须把字段留在原处，模块化无法推进。
 * 本门面把「界面层暴露哪些控件」变成一份显式清单：
 * 界面层按名字登记控件，自检按名字取用，控件实际声明在哪个类不再影响断言。
 *
 * <p>查找顺序：先查界面层登记表，再回落到反射本类字段（用于界面层自己的壳字段，
 * 例如窗口、内容区、导航模型）。两处都没有时抛 {@link IllegalStateException}，
 * 与旧的反射失败行为一致——宁可让自检报错，也不静默返回 null 让断言变成恒真。
 */
public final class UiHandle {

    /** 控件登记表的提供方：按名字暴露界面控件。 */
    public interface Source {
        Map<String, Object> namedWidgets();
    }

    private UiHandle() {
    }

    /** 取控件：先查登记表，再反射本类字段。 */
    public static Object get(Object target, String name) {
        if (target instanceof Source) {
            Object found = ((Source) target).namedWidgets().get(name);
            // 取值器用于「同一个名字在运行期指向不同对象」的控件（例如展开后的导航序列）：
            // 直接登记对象会把那一刻的快照固化下来，后续断言读到的就是过期数据。
            if (found instanceof java.util.function.Supplier) {
                return ((java.util.function.Supplier<?>) found).get();
            }
            if (found != null) return found;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // 继续向父类查找
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("未登记的控件: " + name);
    }

    /** 判断控件是否存在：先查登记表，再沿继承链查字段。 */
    public static boolean declares(Object target, String name) {
        if (target instanceof Source && ((Source) target).namedWidgets().containsKey(name)) return true;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                type.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException ignored) {
                // 继续向父类查找
            }
        }
        return false;
    }

    /** 登记表的构建辅助：按键值对依次登记，顺序稳定，便于排查。 */
    public static Map<String, Object> registry() {
        return new LinkedHashMap<String, Object>();
    }
}
