package analyzer;

import java.nio.file.Path;

/**
 * 把外部引擎产出的 SQLite 数据库交给 Python 侧查询。
 *
 * <p>为什么不直接在 Java 里读：SQLite 是文件格式而不是 JDK 能力，
 * 要读它就得引第三方 JDBC 驱动，而本仓库禁止新增依赖。
 * Python 标准库自带 {@code sqlite3}，且本项目的探测引擎本来就是「Java 收集参数 →
 * Python 执行」，这条既有通道正好适合承接查询。
 *
 * <p>本类只负责把「哪张表、要什么」翻译成命令行，不在 Java 侧复制一份 SQL。
 */
public final class ReportReader {

    /** 内置查询名：与 {@code python/jar_report.py} 里的查询一一对应。 */
    public enum Query {
        /** 总览：类 / 方法 / 调用边数量。 */
        SUMMARY("summary", "总览"),
        /** 入口点：Spring Controller / Servlet / Filter / Listener。 */
        ENTRIES("entries", "入口点"),
        /** 命中 sink 的方法（按内置 sink 清单匹配调用）。 */
        SINKS("sinks", "Sink 命中"),
        /**
         * 利用路径：把 sink 命中沿调用图**向上反推**，找出能走到它的调用者，
         * 并标出其中哪些落在 Spring / JavaWeb 入口上。
         *
         * <p>这是「分析结果二次利用」最关键的一步：后端只给出「谁调用了 Runtime.exec」，
         * 而使用者要的是「有没有一条从外部入口通到它的路」。
         */
        PATHS("paths", "利用路径"),
        /** 多态实现：接口 / 父类方法对应的真实实现类。入口调接口时要靠它判断链往哪走。 */
        IMPLS("impls", "多态实现"),
        /** 字符串常量搜索（SQL、URL、密钥等敏感信息）。 */
        STRINGS("strings", "字符串常量"),
        /** 组件与版本（引擎侧的口径，可与本地扫描结果对照）。 */
        COMPONENTS("components", "组件清单"),
        /**
         * 漏洞特征匹配：把库里的代码特征（字符串常量 / 类名 / 方法名 / sink 调用）
         * 与内置签名库对照，得出可能的漏洞类型与绕过手法。
         *
         * <p>走另一个脚本（jar_signatures.py）：事实查询与判定的输出契约不同，
         * 合在一个脚本里会让两类需求互相牵制。
         */
        SIGNATURES("signatures", "漏洞特征匹配");

        private final String id;
        private final String label;

        Query(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** 命令行用的标识。 */
        public String id() {
            return id;
        }

        /** 界面显示名。 */
        public String label() {
            return label;
        }

        /** 按标识取查询；找不到返回 null。 */
        public static Query of(String id) {
            if (id == null) return null;
            for (Query query : values()) {
                if (query.id.equalsIgnoreCase(id.trim())) return query;
            }
            return null;
        }
    }

    private ReportReader() {
    }

    /** 数据库文件是否可读。 */
    public static boolean available(Path database) {
        return database != null && java.nio.file.Files.isRegularFile(database);
    }
}
