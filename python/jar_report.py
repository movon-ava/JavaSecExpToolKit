"""调用链数据库查询：读取 jar-analyzer-engine 产出的 SQLite 并输出报告。

为什么放在 Python 侧：SQLite 是文件格式而不是 JDK 能力，Java 侧要读就得引第三方
JDBC 驱动，而本项目禁止新增第三方依赖。Python 标准库自带 sqlite3，
且「Java 收集参数 → Python 执行引擎」本来就是本项目既有的通道，
这条查询正好复用它，不必在 Java 里复制一份 SQL。

库表结构来自 jar-analyzer-engine 的 DATABASE.md（14 张表）：
  jar_table / class_table / class_file_table / member_table / method_table /
  anno_table / interface_table / method_call_table / method_impl_table /
  string_table / spring_controller_table / spring_method_table /
  spring_interceptor_table / java_web_table

只用标准库。所有查询都是只读 SELECT，绝不写库。
"""

import argparse
import sqlite3
import sys

# 内置 sink 清单：方法级「危险调用」判据。
#
# 这张表是**我们自己写的**：上游 jar-analyzer 是 GPLv3，把它的 dfs-sink.json
# 直接搬进本仓库会有许可问题。这里只收录「命中后在本工具里能接上后续动作」的
# sink——判定出来却无处可去对使用者没有价值。
#
# 每项是 (分类, 类名, 方法名, 说明)。类名用 JVM 内部格式（/ 分隔）。
SINKS = (
    ("命令执行", "java/lang/Runtime", "exec", "Runtime.exec 执行系统命令"),
    ("命令执行", "java/lang/ProcessBuilder", "start", "ProcessBuilder.start 启动进程"),
    ("命令执行", "javax/script/ScriptEngine", "eval", "脚本引擎求值"),
    ("代码执行", "java/lang/ClassLoader", "defineClass", "运行时定义类"),
    ("代码执行", "java/lang/ClassLoader", "loadClass", "加载类（配合字节码可控即 RCE）"),
    ("JNDI", "javax/naming/Context", "lookup", "JNDI lookup（配合 ldap/rmi 协议即 RCE）"),
    ("JNDI", "javax/naming/InitialContext", "lookup", "InitialContext.lookup"),
    ("反序列化", "java/io/ObjectInputStream", "readObject", "Java 原生反序列化入口"),
    ("反序列化", "java/io/ObjectInputStream", "readUnshared", "readUnshared 反序列化"),
    ("反序列化", "com/alibaba/fastjson/JSON", "parseObject", "Fastjson 解析（autoType 可控即 RCE）"),
    ("反序列化", "com/alibaba/fastjson/JSON", "parse", "Fastjson 解析"),
    ("反序列化", "com/fasterxml/jackson/databind/ObjectMapper", "readValue", "Jackson 反序列化"),
    ("反序列化", "org/yaml/snakeyaml/Yaml", "load", "SnakeYAML 反序列化"),
    ("反序列化", "org/yaml/snakeyaml/Yaml", "loadAs", "SnakeYAML 反序列化"),
    ("反序列化", "com/thoughtworks/xstream/XStream", "fromXML", "XStream 反序列化"),
    ("反序列化", "com/caucho/hessian/io/HessianInput", "readObject", "Hessian 反序列化"),
    ("反序列化", "java/beans/XMLDecoder", "readObject", "XMLDecoder 反序列化"),
    ("SQL", "java/sql/Statement", "execute", "SQL 语句执行（拼接即注入）"),
    ("SQL", "java/sql/Statement", "executeQuery", "SQL 查询（拼接即注入）"),
    ("SQL", "java/sql/Statement", "executeUpdate", "SQL 更新（拼接即注入）"),
    ("文件", "java/io/FileInputStream", "<init>", "按路径读文件（路径可控即任意读）"),
    ("文件", "java/io/FileOutputStream", "<init>", "按路径写文件（路径可控即任意写）"),
    ("文件", "java/io/RandomAccessFile", "<init>", "随机读写文件"),
    ("文件", "java/io/File", "delete", "删除文件"),
    ("SSRF", "java/net/URL", "openConnection", "URL 连接（URL 可控即 SSRF）"),
    ("SSRF", "java/net/HttpURLConnection", "connect", "发起 HTTP 连接"),
)

# Spring / JavaWeb 入口表：这些类里的方法就是外部可达路径
ENTRY_TABLES = (
    ("spring_method_table", "Spring 路由", ("path", "restful_type")),
    ("java_web_table", "JavaWeb 组件", ("type_name",)),
)


class ReportError(Exception):
    """查询期间的错误：数据库缺失、表结构不符等，都归到这一类。"""


def connect(database):
    """只读方式打开数据库。

    用 mode=ro 而不是普通连接：本功能只查询，绝不允许因为拼错 SQL 把
    使用者的分析库改坏。uri 形式下参数不会被当成 SQL 执行。
    """
    try:
        connection = sqlite3.connect("file:%s?mode=ro" % database.replace("?", "%3f"), uri=True)
    except sqlite3.Error as error:
        raise ReportError("无法打开数据库 %s：%s" % (database, error))
    connection.row_factory = sqlite3.Row
    return connection


def tables(connection):
    """已存在的表名集合。"""
    rows = connection.execute(
        "SELECT name FROM sqlite_master WHERE type='table'").fetchall()
    return set(row["name"] for row in rows)


def scalar(connection, sql, params=()):
    """取单个数值；表不存在或查询失败时返回 0。"""
    try:
        row = connection.execute(sql, params).fetchone()
    except sqlite3.Error:
        return 0
    if row is None:
        return 0
    value = row[0]
    return 0 if value is None else value


def query_summary(connection, present):
    """总览：库有多大、有哪些入口。"""
    lines = ["===== 数据库总览 ====="]
    lines.append("  JAR 文件      : %d" % scalar(connection, "SELECT COUNT(*) FROM jar_table"))
    lines.append("  类            : %d" % scalar(connection, "SELECT COUNT(*) FROM class_table"))
    lines.append("  方法          : %d" % scalar(connection, "SELECT COUNT(*) FROM method_table"))
    lines.append("  方法调用边    : %d" % scalar(connection, "SELECT COUNT(*) FROM method_call_table"))
    lines.append("  字符串常量    : %d" % scalar(connection, "SELECT COUNT(*) FROM string_table"))
    lines.append("  Spring 路由   : %d" % scalar(connection, "SELECT COUNT(*) FROM spring_method_table"))
    lines.append("  JavaWeb 组件  : %d" % scalar(connection, "SELECT COUNT(*) FROM java_web_table"))
    return lines


def query_entries(connection, present, limit):
    """入口点：外部可达的方法，是判断链是否真的能触发的前提。"""
    lines = ["===== 入口点 ====="]
    total = 0
    if "spring_method_table" in present:
        rows = connection.execute(
            "SELECT class_name, method_name, restful_type, path FROM spring_method_table "
            "ORDER BY path LIMIT ?", (limit,)).fetchall()
        total += len(rows)
        lines.append("Spring 路由 %d 条：" % len(rows))
        for row in rows:
            lines.append("  %-8s %-46s %s" % (row["restful_type"], row["path"], row["class_name"]))
    if "java_web_table" in present:
        rows = connection.execute(
            "SELECT type_name, class_name FROM java_web_table ORDER BY type_name, class_name "
            "LIMIT ?", (limit,)).fetchall()
        total += len(rows)
        lines.append("JavaWeb 组件 %d 个：" % len(rows))
        for row in rows:
            lines.append("  %-12s %s" % (row["type_name"], row["class_name"]))
    if total == 0:
        lines.append("  未识别到 Spring / JavaWeb 入口（可能是非 Web 应用，或用了快速模式）。")
    return lines


def sink_conditions():
    """把 sink 清单翻译成 SQL 条件与参数。"""
    conditions = []
    params = []
    for _category, class_name, method_name, _note in SINKS:
        conditions.append("(callee_class_name = ? AND callee_method_name = ?)")
        params.extend([class_name, method_name])
    return " OR ".join(conditions), params


def query_sinks(connection, present, limit):
    """Sink 命中：调用图里出现了危险调用，以及是谁调的。

    注意：**命中不等于漏洞**。这里只回答「调用了敏感方法」，
    参数是否可控要靠调用链继续往上看，因此报告里同时给出调用方类名，
    方便顺着往上追。
    """
    lines = ["===== Sink 命中（按调用方聚合）====="]
    where, params = sink_conditions()
    sql = ("SELECT caller_class_name, callee_class_name, callee_method_name, COUNT(*) AS hits "
           "FROM method_call_table WHERE " + where +
           " GROUP BY caller_class_name, callee_class_name, callee_method_name "
           " ORDER BY hits DESC, caller_class_name LIMIT ?")
    try:
        rows = connection.execute(sql, params + [limit]).fetchall()
    except sqlite3.Error as error:
        lines.append("  查询失败：%s" % error)
        return lines
    if not rows:
        lines.append("  调用图里没有命中内置 sink 清单。")
        return lines

    notes = {}
    for category, class_name, method_name, note in SINKS:
        notes[(class_name, method_name)] = (category, note)
    by_category = {}
    for row in rows:
        key = (row["callee_class_name"], row["callee_method_name"])
        category, note = notes.get(key, ("其它", ""))
        by_category.setdefault(category, []).append((row, note))

    for category in sorted(by_category):
        entries = by_category[category]
        lines.append("[%s] %d 组" % (category, len(entries)))
        for row, note in entries[:limit]:
            lines.append("  %s -> %s.%s  (%d 处)"
                         % (row["caller_class_name"], row["callee_class_name"],
                            row["callee_method_name"], row["hits"]))
            lines.append("      说明: %s" % note)
    lines.append("")
    lines.append("提示：命中只代表「调用了敏感方法」，是否可被外部控制需沿调用方继续往上追。")
    return lines


def query_strings(connection, present, keyword, limit):
    """字符串常量搜索：找硬编码密钥、内网地址、SQL 片段等。"""
    lines = ["===== 字符串常量检索 ====="]
    if "string_table" not in present:
        lines.append("  数据库没有 string_table（快速模式不生成字符串表）。")
        return lines
    params = []
    sql = "SELECT value, class_name, method_name FROM string_table"
    if keyword:
        sql += " WHERE value LIKE ?"
        params.append("%" + keyword + "%")
    sql += " ORDER BY class_name LIMIT ?"
    params.append(limit)
    try:
        rows = connection.execute(sql, params).fetchall()
    except sqlite3.Error as error:
        lines.append("  查询失败：%s" % error)
        return lines
    if not rows:
        lines.append("  没有匹配的字符串常量。")
        return lines
    for row in rows:
        value = row["value"]
        if value is None:
            continue
        display = value if len(value) <= 120 else value[:117] + "..."
        lines.append("  %s" % display.replace("\n", " "))
        lines.append("      %s.%s" % (row["class_name"], row["method_name"]))
    return lines


def query_components(connection, present, limit):
    """组件清单：引擎侧口径的 jar 与类版本，可与本地扫描结果对照。"""
    lines = ["===== 组件（引擎口径）====="]
    rows = connection.execute(
        "SELECT jar_name, jar_abs_path FROM jar_table ORDER BY jar_name LIMIT ?",
        (limit,)).fetchall()
    if not rows:
        lines.append("  数据库里没有 jar 记录。")
        return lines
    for row in rows:
        lines.append("  %s" % row["jar_name"])
        lines.append("      %s" % row["jar_abs_path"])
    lines.append("")
    lines.append("提示：引擎只记录 jar 名与路径，不解析 Maven 坐标；"
                 "精确版本请以「本地分析」页读到的 pom 元数据为准。")
    return lines


QUERIES = {
    "summary": lambda connection, present, args: query_summary(connection, present),
    "entries": lambda connection, present, args: query_entries(connection, present, args.limit),
    "sinks": lambda connection, present, args: query_sinks(connection, present, args.limit),
    "strings": lambda connection, present, args: query_strings(
        connection, present, args.keyword, args.limit),
    "components": lambda connection, present, args: query_components(
        connection, present, args.limit),
}


def parse_args(argv):
    """命令行：-db 必填，-q 选查询，-k 供字符串检索，-n 限制条数。"""
    parser = argparse.ArgumentParser(
        prog="jar_report.py",
        description="查询 jar-analyzer-engine 产出的调用链数据库（只读）")
    parser.add_argument("-db", "--database", required=True, help="jar-analyzer.db 路径")
    parser.add_argument("-q", "--query", default="summary",
                        choices=sorted(QUERIES.keys()), help="查询类型，默认 summary")
    parser.add_argument("-k", "--keyword", default="", help="字符串检索关键字")
    parser.add_argument("-n", "--limit", type=int, default=40, help="每节最多输出条数，默认 40")
    return parser.parse_args(argv)


def main(argv=None):
    """入口：打开数据库并执行一个查询，输出到 stdout。"""
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        connection = connect(args.database)
    except ReportError as error:
        print(str(error))
        return 2
    try:
        present = tables(connection)
        handler = QUERIES.get(args.query)
        if handler is None:
            print("未知查询：%s" % args.query)
            return 2
        print("数据库: %s" % args.database)
        for line in handler(connection, present, args):
            print(line)
        return 0
    except sqlite3.Error as error:
        print("查询失败：%s" % error)
        return 3
    finally:
        connection.close()


if __name__ == "__main__":
    sys.exit(main())
