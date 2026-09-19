package config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 配置页的持久化载体：读写用户目录下的 config.properties。
 *
 * <p>文件格式与 Python 侧共用（{@code python/fj_probe.py} 会直接读同一个文件），
 * 因此这里必须用 {@code Properties.store} 的转义规则与 UTF-8 编码，不能自造格式。
 */
public final class AppConfig {

    /** 配置文件名固定，Python 引擎按同一路径读取。 */
    public static final Path FILE =
            Paths.get(System.getProperty("user.home"), ".JavaSecExpToolKit", "config.properties");

    private AppConfig() {
    }

    /** 读取配置；文件不存在时返回空配置而不是抛异常（首次启动属正常情况）。 */
    public static void load(Properties values) {
        if (!Files.isRegularFile(FILE)) return;
        try (InputStream input = Files.newInputStream(FILE)) {
            values.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("读取配置失败：" + e.getMessage(), e);
        }
    }

    /** 写入配置；目录不存在时自动创建。 */
    public static void save(Properties values) throws IOException {
        Files.createDirectories(FILE.getParent());
        try (OutputStream output = Files.newOutputStream(FILE)) {
            values.store(new OutputStreamWriter(output, StandardCharsets.UTF_8), "JavaSecExpToolKit");
        }
    }
}

