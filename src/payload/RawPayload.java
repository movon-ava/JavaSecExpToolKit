package payload;

/**
 * 载荷的原始对象形态：字节与对象两种载体都保留。
 *
 * <p>JRMP 之类的服务要求发布的对象本身可序列化（上游契约只接受 OBJECT），
 * 而 HTTP / TCP / JNDI 接受字节。若只保留 Base64 文本，JRMP 方向就无法发布；
 * 若只保留字节，写文件与粘贴又得自己再转一次。因此两者都留着，由调用方按需取用。
 *
 * <p>本类只是数据容器，不引用任何引擎类型。
 */
public final class RawPayload {

    /** 是否构建成功。 */
    public final boolean success;
    /** 失败原因；成功时为空串。 */
    public final String message;
    /** 字节形态；无法取字节时为空数组。 */
    public final byte[] bytes;
    /** 原始对象形态；载体只产出字节时为 null。 */
    public final Object object;

    private RawPayload(boolean success, String message, byte[] bytes, Object object) {
        this.success = success;
        this.message = message == null ? "" : message;
        this.bytes = bytes == null ? new byte[0] : bytes;
        this.object = object;
    }

    public static RawPayload ok(byte[] bytes, Object object) {
        return new RawPayload(true, "", bytes, object);
    }

    public static RawPayload fail(String message) {
        return new RawPayload(false, message, new byte[0], null);
    }

    /** 是否带可发布的原始对象。 */
    public boolean hasObject() {
        return object != null;
    }
}
