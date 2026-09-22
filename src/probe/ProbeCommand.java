package probe;

import java.util.ArrayList;
import java.util.List;
import util.Platform;

/**
 * 探测引擎的命令行构造：把界面参数翻译成 {@code fj_probe.py} 的启动参数。
 *
 * <p>集中在类里而不是散落在界面上，是为了让「界面字段」与「引擎参数」的对应关系
 * 一眼可见，也避免各处自行拼串时漏掉转义（Windows 下的引号必须交给
 * {@link Platform#commandArg}）。
 */
public final class ProbeCommand {

    /** 一次探测 / 抓包 / 转换所需的全部参数；空串表示该参数不传。 */
    public static final class Options {
        public String python = "python";
        public String script = "";
        public String target = "";
        public String timeout = "8";
        public String mode = "detect";
        public String probeMethod = "POST";
        /** 抓包 / 转换使用的 HTTP 方法，与探测方法分开。 */
        public String method = "POST";
        public String baseBody = "";
        public String headers = "";
        public String sessionCookie = "";
        public String dnslogHost = "";
        public String dnsWait = "";
        public String dnsFilter = "";
        public String ceyeToken = "";
        public String ceyeDomain = "";
        public String contentType = "";
        public String body = "";
        public String captureUrl = "";
        public String convertTargets = "";
        public String pastedRequest = "";
        /** 文件上传：目标 URL、要上传的本地文件、表单字段名与附加普通字段（JSON）。 */
        public String uploadUrl = "";
        public List<String> uploadFiles = new ArrayList<String>();
        public String uploadField = "file";
        public String uploadFields = "";
        /** 报告详细度：brief 只给结论与关键判定，detail 附探针明细。 */
        public String report = "brief";
        public boolean capture = true;
    }

    private ProbeCommand() {
    }

    /** 探测模式（识别 / 版本 / 期望类 / DNS / CEYE）的启动参数。 */
    public static List<String> probe(Options options) {
        List<String> command = new ArrayList<String>();
        command.add(options.python);
        command.add(Platform.commandArg(options.script));
        command.add("--timeout");
        command.add(options.timeout);
        command.add("--mode");
        command.add(options.mode);
        command.add("--probe-method");
        command.add(options.probeMethod);
        addText(command, "--base-body", options.baseBody);
        addText(command, "--headers", options.headers);
        addText(command, "--session-cookie", options.sessionCookie);
        command.add(Platform.commandArg(options.target));
        // 界面直接展示引擎渲染的中文报告，JSON 仍可用 CLI --format json 获取
        command.add("--format");
        command.add("text");
        command.add("--report");
        command.add("detail".equalsIgnoreCase(options.report) ? "detail" : "brief");

        // 五个模式各自独立执行；detect/version/expect 不带 DNS/CEYE，避免全选时重复探测
        boolean dnsMode = "dns".equals(options.mode);
        boolean ceyeMode = "ceye".equals(options.mode);
        if (dnsMode) {
            addText(command, "--dnslog-host", options.dnslogHost);
            addText(command, "--dns-wait", options.dnsWait);
            command.add("--dns");
        } else {
            command.add("--no-dns");
        }
        if (ceyeMode) addText(command, "--dns-filter", options.dnsFilter);
        command.add(ceyeMode ? "--ceye" : "--no-ceye");
        if (ceyeMode) {
            addText(command, "--ceye-token", options.ceyeToken);
            addText(command, "--ceye-domain", options.ceyeDomain);
        }
        return command;
    }

    /** 抓包 / 转换模式的启动参数（两者共用同一套请求描述）。 */
    public static List<String> capture(Options options) {
        List<String> command = new ArrayList<String>();
        command.add(options.python);
        command.add(Platform.commandArg(options.script));
        command.add("--timeout");
        command.add(options.timeout);
        command.add("--method");
        command.add(options.method);
        addText(command, "--content-type", options.contentType);
        addText(command, "--headers", options.headers);
        addText(command, "--body", options.body);
        addText(command, "--capture-url", options.captureUrl);
        if (options.convertTargets != null && !options.convertTargets.isEmpty()) {
            command.add("--convert-targets");
            command.add(options.convertTargets);
        }
        command.add("--mode");
        command.add(options.capture ? "capture" : "convert");
        command.add("--format");
        command.add("text");
        if (!options.capture) addText(command, "--pasted-request", options.pastedRequest);
        return command;
    }

    /**
     * 文件上传模式的启动参数。
     *
     * <p>上传是「一次请求 + 记录原始响应」，内容即结果，因此固定要求详细报告；
     * 目标 URL 与文件都走 {@code addText}，为空即不传，由引擎给出可读的失败结论
     * （「上传目标 URL 不能为空」/「未选择文件」），而不是在这里静默放过。
     */
    public static List<String> upload(Options options) {
        List<String> command = new ArrayList<String>();
        command.add(options.python);
        command.add(Platform.commandArg(options.script));
        command.add("--timeout");
        command.add(options.timeout);
        command.add("--mode");
        command.add("upload");
        addText(command, "--upload-url", options.uploadUrl);
        addText(command, "--upload-field", options.uploadField);
        addText(command, "--upload-fields", options.uploadFields);
        addText(command, "--headers", options.headers);
        for (String file : options.uploadFiles) addText(command, "--upload-file", file);
        addText(command, "--report", "detail");
        command.add("--format");
        command.add("text");
        return command;
    }

    private static void addText(List<String> command, String flag, String value) {
        if (value == null || value.trim().isEmpty()) return;
        command.add(flag);
        command.add(Platform.commandArg(value.trim()));
    }
}
