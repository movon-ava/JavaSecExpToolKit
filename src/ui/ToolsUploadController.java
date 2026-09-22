package ui;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;

import probe.ProbeCommand;
import probe.ProbeEngine;
import util.Platform;

/**
 * 小工具 - 文件上传页的行为：选文件 → 拼 multipart 参数 → 调用引擎 → 回填响应。
 *
 * <p>配置是只读依赖：本类只按配置里的默认 URL / 字段名 / 附加字段 / 请求头拼启动参数，
 * 配置的读写入口统一留在 {@link ConfigController}，本类不写配置。
 */
public final class ToolsUploadController {

    private final ToolsUploadPage.Widgets widgets;
    private final ConfigController config;

    public ToolsUploadController(ToolsUploadPage.Widgets widgets, ConfigController config) {
        this.widgets = widgets;
        this.config = config;
        wire();
    }

    private void wire() {
        widgets.choose.addActionListener(e -> chooseFile());
        widgets.run.addActionListener(e -> startUpload());
        widgets.onChoose = this::chooseFile;
        widgets.onCopy = this::copyResult;
    }

    /** 打开文件选择器；取消选择时保持原有路径不变。 */
    public void chooseFile() {
        int outcome = widgets.chooser.showOpenDialog(widgets.choose);
        if (outcome != JFileChooser.APPROVE_OPTION) return;
        File selected = widgets.chooser.getSelectedFile();
        if (selected == null) return;
        widgets.filePath.setText(selected.getAbsolutePath());
        widgets.status.setText("已选择 " + selected.getName());
    }

    /**
     * 把配置页里的默认值下发到本页控件。
     *
     * <p>只在启动与保存后各下发一次（与代理页、抓包页一致）：每次进页都覆盖会让
     * 使用者刚填好的 URL 在切页后丢失。留空的配置项不下发，保持控件当前值。
     */
    public void applyDefaults() {
        String url = config.property("upload_url", "").trim();
        if (!url.isEmpty()) widgets.url.setText(url);
        String contentTypeField = config.property("upload_field", "").trim();
        if (!contentTypeField.isEmpty()) widgets.field.setText(contentTypeField);
        String headers = config.property("upload_headers", "").trim();
        if (!headers.isEmpty()) widgets.headers.setText(headers);
    }

    /** 上传：本地校验 → 后台线程跑引擎 → 结果回填。 */
    public void startUpload() {
        final String url = widgets.url.getText().trim();
        final String file = widgets.filePath.getText().trim();
        final String field = widgets.field.getText().trim();
        final String fields = widgets.fields.getText().trim();
        final String headers = widgets.headers.getText().trim();
        if (url.isEmpty()) { widgets.result.setText("目标 URL 不能为空。\n"); return; }
        if (file.isEmpty()) { widgets.result.setText("请先选择要上传的文件。\n"); return; }
        if (!new File(file).isFile()) {
            widgets.result.setText("本地文件不存在或不可读：" + file + System.lineSeparator());
            return;
        }
        widgets.run.setEnabled(false);
        widgets.status.setText("上传中...");
        widgets.result.setText("正在发送 multipart 请求并等待响应...\n");
        Thread worker = new Thread(() -> {
            String output;
            try {
                output = runUpload(url, file, field, fields, headers);
            } catch (Exception e) {
                output = "启动上传失败: " + e + System.lineSeparator();
            }
            final String finalOutput = output;
            SwingUtilities.invokeLater(() -> {
                widgets.result.setText(finalOutput);
                widgets.result.setCaretPosition(0);
                widgets.run.setEnabled(true);
                widgets.status.setText("上传完成");
            });
        }, "tools-upload");
        worker.setDaemon(true);
        worker.start();
    }

    /** 单次上传：拼参数并落进程。抽成公开方法供自检直接驱动，不依赖按钮点击。 */
    public String runUpload(String url, String file, String field, String fields, String headers)
            throws Exception {
        ProbeCommand.Options options = new ProbeCommand.Options();
        options.python = ProbeEngine.resolvePython(config.python());
        options.script = ProbeEngine.extractScript().toString();
        options.timeout = Platform.valueOr(config.property("upload_timeout", ""), "30");
        options.uploadUrl = url;
        options.uploadFiles.add(file);
        options.uploadField = Platform.valueOr(field, "file");
        options.uploadFields = fields;
        options.headers = headers;
        return ProbeEngine.run(ProbeCommand.upload(options));
    }

    /** 复制结果区内容到剪贴板。 */
    public void copyResult() {
        String text = widgets.result.getText();
        if (text == null || text.isEmpty()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
        widgets.status.setText("已复制结果到剪贴板");
    }
}
