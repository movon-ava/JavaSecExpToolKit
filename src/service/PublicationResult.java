package service;

/**
 * 一次载荷发布的结果：纯数据，界面据此显示发布地址或失败原因。
 */
public final class PublicationResult {

    public final boolean success;
    /** 成功时是可直接展示的发布说明（含地址或凭据）；失败时为空串。 */
    public final String message;
    /** 失败原因；成功时为空串。 */
    public final String error;
    /** 发布标识：HTTP 是内容哈希，TCP / JRMP 固定为 current，其余是随机令牌。 */
    public final String publicationId;
    /** 载荷字节长度；未知为 0。 */
    public final int byteLength;

    private PublicationResult(boolean success, String message, String error, String publicationId, int byteLength) {
        this.success = success;
        this.message = message == null ? "" : message;
        this.error = error == null ? "" : error;
        this.publicationId = publicationId == null ? "" : publicationId;
        this.byteLength = byteLength;
    }

    public static PublicationResult ok(String message, String publicationId, int byteLength) {
        return new PublicationResult(true, message, "", publicationId, byteLength);
    }

    public static PublicationResult fail(String error) {
        return new PublicationResult(false, "", error, "", 0);
    }
}
