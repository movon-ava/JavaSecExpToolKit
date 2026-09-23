"""分析数据库查询：读取 jar-analyzer 产出的 SQLite 并输出可用的结论。

本工具不自己实现字节码分析，而是把 jar-analyzer 当作外部分析后端复用它的结果：
它一次建库、之后任意组合查询都是秒级响应，结果精确到具体类与方法。
本脚本负责把「库里的表」翻译成**漏洞利用要用的答案**——不只是列事实，
还要回答「这条 sink 有没有外部入口可达」「入口调的接口方法到底落在哪个实现类」。

为什么放在 Python 侧：SQLite 是文件格式而不是 JDK 能力，Java 侧要读就得引第三方
JDBC 驱动，而本项目禁止新增第三方依赖。Python 标准库自带 sqlite3，
且「Java 收集参数 → Python 执行」本来就是本项目既有的通道，
这条查询正好复用它，不必在 Java 里复制一份 SQL。

库表结构来自 jar-analyzer 的 DATABASE.md 与其 6.x 实测产物（19 张表）：

  基础事实（engine / 早期版本文档均有）
    jar_table / class_table / class_file_table / member_table / method_table /
    anno_table / interface_table / method_call_table / method_impl_table /
    string_table / spring_controller_table / spring_method_table /
    spring_interceptor_table / java_web_table

  6.x 新增（实测存在，旧库可能没有；缺表时各查询会给出可读提示而不是崩）
    dfs_result_table / dfs_result_list_table    调用链 DFS 的结果
    note_favorite_table / note_history_table    用户在 GUI 里的收藏与历史

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
# sink 分类 -> 本工具里该接着做的动作（功能页 key + 按钮文字）。
#
# 这是「分析为利用服务」的落点：报告不能停在「这里有个危险调用」，
# 而要告诉使用者「接下来该开哪个功能」。键必须与界面导航 key 一致，
# 不一致时界面会显示「打开 <key>」而不是可读的按钮名。
CATEGORY_ACTION = {
    "命令执行": ("payload.build", "去生成载荷"),
    "代码执行": ("payload.build", "去生成载荷"),
    "反序列化": ("payload.build", "去生成载荷"),
    "JNDI": ("service.servers", "去恶意服务器"),
    "SQL": ("capture", "去抓包转换"),
    "SSRF": ("capture", "去抓包转换"),
    "文件": ("capture", "去抓包转换"),
    "其它": ("payload.build", "去生成载荷"),
}

ENTRY_TABLES = (
    ("spring_method_table", "Spring 路由", ("path", "restful_type")),
    ("java_web_table", "JavaWeb 组件", ("type_name",)),
)


# 库结构契约：查什么功能依赖哪些表 / 列。
#
# 为什么要显式声明：后端升级会改 schema，缺表时若只是「查不到」，报告看起来
# 和「目标干净」完全一样——这是最危险的一类静默错误。这里把依赖写死，
# 由 check_schema() 逐项核对，缺哪项就明确说哪项不可用。
REQUIRED = {
    "summary": ("jar_table", "class_table", "method_table", "method_call_table"),
    "entries": ("class_table",),
    "sinks": ("method_call_table",),
    "paths": ("method_call_table",),
    "impls": ("method_impl_table",),
    "strings": ("string_table",),
    "components": ("jar_table",),
}

# 关键列：表在但列被改名，同样会让查询静默返回空
REQUIRED_COLUMNS = {
    "method_call_table": ("caller_class_name", "caller_method_name",
                          "callee_class_name", "callee_method_name"),
    "class_table": ("class_name",),
    "jar_table": ("jar_name",),
    "method_impl_table": ("class_name", "method_name", "impl_class_name"),
    "string_table": ("value", "class_name", "method_name"),
}


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


def columns_of(connection, table):
    """某张表的列名集合；表不存在时返回空集。"""
    try:
        return set(row[1] for row in connection.execute('PRAGMA table_info("%s")' % table))
    except sqlite3.Error:
        return set()


# 缺了就**会给出误导性结论**的表：这些必须判为不兼容。
#
# 判据是「报告会不会看起来正常但结论相反」，不是「表是否重要」：
#   * method_call_table 缺了，sinks / paths 会输出「没有命中 sink」——
#     使用者读到的意思是「目标没有危险调用」，而事实是**根本没查**；
#   * string_table 缺了，strings 会说明「快速模式不生成字符串表」——
#     这是自解释的正常降级，使用者能看懂，不必拦下；
#   * method_impl_table 缺了，impls 会说明「可能是旧版引擎产出的库」——同理。
#
# 把「自解释的降级」也拦掉会让快速模式与旧库完全不可用，反而逼使用者
# 去猜为什么查询被拒。
MISLEADING_IF_MISSING = {
    "sinks": ("method_call_table",),
    "paths": ("method_call_table",),
}


def check_schema(connection, present, query=None):
    """核对库结构与本次要跑的查询是否匹配。

    返回 (缺失项清单, 是否 incompatible)。判断口径：

    * 缺的表会让本次查询输出**误导性结论** -> incompatible；
    * 其它缺失 -> 只作为提醒（对应的查询自己会说明怎么降级了）。
    """
    missing = []
    for name, tables in sorted(REQUIRED.items()):
        for table in tables:
            if table not in present:
                missing.append("%s（%s 查询依赖）" % (table, name))
    for table, columns in sorted(REQUIRED_COLUMNS.items()):
        if table not in present:
            continue
        have = columns_of(connection, table)
        for column in columns:
            if column not in have:
                missing.append("%s.%s（列缺失）" % (table, column))
    fatal = False
    for table in MISLEADING_IF_MISSING.get(query, ()):
        if table not in present:
            fatal = True
            continue
        # 表在但列被改名时，查询同样会安静地返回空——和缺表一样属于误导性结论
        have = columns_of(connection, table)
        for column in REQUIRED_COLUMNS.get(table, ()):
            if column not in have:
                fatal = True
    return missing, fatal


def has_classes(connection, present):
    """库里有没有类：空 jar / 纯资源 jar 建出的库类数为 0。

    这个判断只用于提醒「目标可能选错了」——否则后续查询全是「未找到」，
    容易被读成「目标很干净」。
    """
    if "class_table" not in present:
        return False
    return scalar(connection, "SELECT COUNT(*) FROM class_table") > 0


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
    lines.append("  多态实现关系  : %d" % scalar(connection, "SELECT COUNT(*) FROM method_impl_table"))
    dfs = scalar(connection, "SELECT COUNT(*) FROM dfs_result_table")
    if dfs:
        # 这是 GUI 里跑过 DFS 才算出来的结果，有它就说明可以直接看现成的调用链
        lines.append("  DFS 调用链    : %d（GUI 里跑过 DFS 才有，可直接用于利用路径确认）" % dfs)
    else:
        lines.append("  DFS 调用链    : 0（在 jar-analyzer GUI 里对目标跑一次 DFS 即会写入）")
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
    lines.append("提示：后端只记录 jar 名与路径，不解析 Maven 坐标；"
                 "精确版本与受影响区间请以「组件与漏洞」页的本地扫描结果为准"
                 "（两者对照，不一致本身就是线索）。")
    return lines


def callers_of(connection, class_name, method_name):
    """取「谁调用了它」的直接上一层。只查调用边表，不加载任何类。"""
    return connection.execute(
        "SELECT caller_class_name, caller_method_name, caller_method_desc "
        "FROM method_call_table WHERE callee_class_name = ? AND callee_method_name = ?",
        (class_name, method_name)).fetchall()


def callees_of(connection, class_name, method_name):
    """取它调用了谁（下一层）。"""
    return connection.execute(
        "SELECT callee_class_name, callee_method_name, callee_method_desc "
        "FROM method_call_table WHERE caller_class_name = ? AND caller_method_name = ?",
        (class_name, method_name)).fetchall()


def is_entry(connection, present, class_name, method_name):
    """这个方法是外部可达入口吗（Spring 路由 / JavaWeb 组件）。"""
    if "spring_method_table" in present:
        row = connection.execute(
            "SELECT path, restful_type FROM spring_method_table "
            "WHERE class_name = ? AND method_name = ? LIMIT 1",
            (class_name, method_name)).fetchone()
        if row is not None:
            return "%s %s" % (row["restful_type"], row["path"])
    if "java_web_table" in present:
        row = connection.execute(
            "SELECT type_name FROM java_web_table WHERE class_name = ? LIMIT 1",
            (class_name,)).fetchone()
        if row is not None:
            return "JavaWeb %s" % row["type_name"]
    return ""


def walk_up(connection, present, class_name, method_name, depth, max_depth, seen):
    """沿调用边向上回溯，收集所有能走到该 sink 的调用者。

    这是本功能最有价值的一层：上游只告诉我们「java/lang/Runtime.exec 被谁调了」，
    而利用时要回答的是「有没有一条从外部入口通到它的路」。这里做的是逆向可达性
    搜索，命中入口点的那条路径就是可以直接下手的位置。

    本函数只回答「有哪些调用者」，不回答「它们怎么连起来」；需要完整链路时用
    ``find_entry_paths``，后者保留路径序列与断点信息。
    """
    if depth >= max_depth:
        return []
    key = (class_name, method_name)
    if key in seen:
        return []
    seen.add(key)
    results = []
    for row in callers_of(connection, class_name, method_name):
        caller_class = row["caller_class_name"]
        caller_method = row["caller_method_name"]
        entry = is_entry(connection, present, caller_class, caller_method)
        node = {
            "class": caller_class,
            "method": caller_method,
            "desc": row["caller_method_desc"],
            "entry": entry,
            "depth": depth + 1,
        }
        results.append(node)
        results.extend(walk_up(connection, present, caller_class, caller_method,
                               depth + 1, max_depth, seen))
    return results


def has_callers(connection, class_name, method_name):
    """还有没有更上一层的调用者：用于标出「链路在此被深度截断」。"""
    return bool(callers_of(connection, class_name, method_name))


def find_entry_paths(connection, present, sink_class, sink_method, sink_desc,
                     max_depth, limit):
    """找出「入口 → … → sink」的完整路径序列。

    为什么单独一个函数：``walk_up`` 给出的是一个扁平集合，读的人无法判断
    「com/example/Service.run」和「POST /api/demo」到底是不是同一条链上的两跳。
    astra 的建议里明确要求展示入口到 Sink 的**路径及路径长度 / 断点**，
    因此这里做的是带路径记录的深度优先搜索，并把截断位置标出来。

    返回若干条路径；每条路径含：
      nodes  从入口到 sink 的节点序列（入口在前，含 sink 本身）
      length 跳数（节点数 - 1）
      entry  入口描述文本（Spring 路由 / JavaWeb 组件）
      cut    被深度上限截断的位置描述；未截断时为空串
    """
    paths = []
    sink_node = {"class": sink_class, "method": sink_method, "desc": sink_desc}

    def climb(class_name, method_name, method_desc, depth, seen, chain):
        """向上找入口。

        chain 是「当前节点及其以下」的序列（自 sink 向上的顺序），
        命中入口时把入口放在最前面，就是可直接下手的完整链路。
        """
        if len(paths) >= limit or depth > max_depth:
            return
        for row in callers_of(connection, class_name, method_name):
            caller_class = row["caller_class_name"]
            caller_method = row["caller_method_name"]
            key = (caller_class, caller_method)
            # 调用图里存在递归与环，不去重会在环上无限展开
            if key in seen:
                continue
            node = {
                "class": caller_class,
                "method": caller_method,
                "desc": row["caller_method_desc"],
            }
            entry = is_entry(connection, present, caller_class, caller_method)
            if entry:
                nodes = [node] + chain
                paths.append({
                    "nodes": nodes,
                    "length": len(nodes) - 1,
                    "entry": entry,
                    "cut": "",
                })
                if len(paths) >= limit:
                    return
                continue
            if depth >= max_depth:
                # 到达深度上限却还没接到入口：如实标出「往上还有调用者」，
                # 否则使用者会把「没找到入口」读成「外部不可达」
                if has_callers(connection, caller_class, caller_method):
                    nodes = [node] + chain
                    paths.append({
                        "nodes": nodes,
                        "length": len(nodes) - 1,
                        "entry": "",
                        "cut": "%s.%s（第 %d 层，往上还有调用者，"
                               "加大「回溯层数」可继续看）"
                               % (caller_class, caller_method, depth + 1),
                    })
                    if len(paths) >= limit:
                        return
                continue
            seen.add(key)
            climb(caller_class, caller_method, row["caller_method_desc"],
                  depth + 1, seen, [node] + chain)
            seen.discard(key)
            if len(paths) >= limit:
                return

    climb(sink_class, sink_method, sink_desc, 1, set(), [sink_node])
    return paths


def describe_path(path):
    """把一条路径渲染成可读序列：入口 → … → sink（带每跳方法签名）。"""
    parts = []
    for node in path["nodes"]:
        label = "%s.%s" % (node["class"], node["method"])
        parts.append(label)
    return " → ".join(parts)


def query_paths(connection, present, args):
    """利用路径：把 sink 命中反推成「入口 → … → sink」的候选路径。

    为什么要有这个查询：sink 命中只说明「调用了敏感方法」，使用者还得自己一层层点开
    调用关系去找入口。这里直接把向上回溯的结果按「是否命中入口点」排序给出，
    命中的排在前面——那才是可以直接构造请求的位置。
    """
    lines = ["===== 利用路径（sink 反推入口）====="]
    if "method_call_table" not in present:
        lines.append("  数据库没有 method_call_table，无法回溯调用链。")
        return lines
    where, params = sink_conditions()
    sql = ("SELECT DISTINCT callee_class_name, callee_method_name, callee_method_desc "
           "FROM method_call_table WHERE " + where + " LIMIT ?")
    try:
        sinks = connection.execute(sql, params + [args.limit]).fetchall()
    except sqlite3.Error as error:
        lines.append("  查询失败：%s" % error)
        return lines
    if not sinks:
        lines.append("  调用图里没有命中内置 sink 清单，也就没有可回溯的起点。")
        return lines

    notes = {}
    for category, class_name, method_name, note in SINKS:
        notes[(class_name, method_name)] = (category, note)

    reachable = 0
    ordered_categories = []
    for sink in sinks:
        sink_class = sink["callee_class_name"]
        sink_method = sink["callee_method_name"]
        sink_desc = sink["callee_method_desc"]
        category, note = notes.get((sink_class, sink_method), ("其它", ""))
        nodes = walk_up(connection, present, sink_class, sink_method, 0,
                        args.depth, set())
        if not nodes:
            continue
        if category not in ordered_categories:
            ordered_categories.append(category)
        reachable += 1
        lines.append("")
        lines.append("[%s] %s.%s" % (category, sink_class, sink_method))
        lines.append("    说明: %s" % note)

        # 完整链路优先：只给「有哪些调用者」无法判断它们是不是同一条链上的两跳
        paths = find_entry_paths(connection, present, sink_class, sink_method,
                                 sink_desc, args.depth, max(1, args.limit))
        entries = [path for path in paths if path["entry"]]
        cuts = [path for path in paths if path["cut"]]
        if entries:
            lines.append("    ★ 外部可达：从入口到本 sink 共找到 %d 条路径"
                         "（★ 行给出完整链路，含路径长度与断点）" % len(entries))
            for path in entries:
                lines.append("      ★ %s" % path["entry"])
                lines.append("         %s" % describe_path(path))
                lines.append("         路径长度: %d 跳（入口计 0 跳，本 sink 为第 %d 跳）"
                             % (path["length"], path["length"]))
                lines.append("         断点: 无（完整连到入口）")
        else:
            # 未接到入口时给出断点位置：只说「没找到」会让人以为已经排除，
            # 而实际原因常常只是回溯深度不够
            lines.append("    外部可达入口：%d 层内未找到。" % args.depth)
            for path in cuts[:max(1, args.limit)]:
                lines.append("         断点: %s" % path["cut"])
                lines.append("               可达至此: %s" % describe_path(path))
                lines.append("               路径长度: %d 跳" % path["length"])
            if not cuts:
                lines.append("         说明: 这条调用链没有更上层的调用者，可能是内部工具类，")
                lines.append("               或入口未被识别（非 Web 应用）。")
            lines.append("         以上断点不代表外部不可达：本引擎只做静态调用图回溯，")
            lines.append("         不判断参数可控性，也不做污点分析；")
            lines.append("         加大「回溯层数」或先在 jar-analyzer GUI 里跑一次 DFS 调用链再查。")

        # 未接入口的调用者仍要列出来：排查时常常需要看它们是谁
        others = [node for node in nodes if not node["entry"]]
        listed = set()
        for path in entries + cuts:
            for node in path["nodes"]:
                listed.add((node["class"], node["method"]))
        for node in others[:max(0, args.limit - len(entries))]:
            key = (node["class"], node["method"])
            if key in listed:
                continue
            lines.append("        第 %d 层调用者: %s.%s" % (node["depth"], node["class"], node["method"]))
    lines.append("")
    if reachable == 0:
        lines.append("  所有 sink 都没有找到调用者（可能是叶子方法）。")
    else:
        lines.append("提示：★ 标出的是「同一条调用路径上存在 Spring / JavaWeb 入口」，")
        lines.append("      说明这条链至少是外部可触达的；参数是否可控仍需看具体代码。")
        lines.append("      本引擎不产出污点分析，因此不声称某条链一定能被利用。")
    # 机器可读的后续动作提示：界面据此渲染成跳转按钮。
    # 走固定前缀而不是让界面反解中文报告：报告措辞一改，界面按钮就会失效。
    seen = []
    for category in ordered_categories:
        action = CATEGORY_ACTION.get(category)
        if action and action not in seen:
            seen.append(action)
    for nav_key, label in seen:
        lines.append("NAV|%s|%s" % (nav_key, label))
    return lines


def query_impls(connection, present, limit):
    """多态实现：接口 / 父类方法的真实实现类。

    利用时经常卡在这里：入口调的是接口方法，实际执行哪个实现类决定了能不能走到 sink。
    上游已经把方法实现关系存进 method_impl_table，直接用，不必自己解析字节码。
    """
    lines = ["===== 多态实现（接口 → 实现类）====="]
    if "method_impl_table" not in present:
        lines.append("  数据库没有 method_impl_table（可能是旧版引擎产出的库）。")
        return lines
    try:
        rows = connection.execute(
            "SELECT class_name, method_name, impl_class_name FROM method_impl_table "
            "ORDER BY class_name, method_name LIMIT ?", (limit,)).fetchall()
    except sqlite3.Error as error:
        lines.append("  查询失败：%s" % error)
        return lines
    if not rows:
        lines.append("  没有记录到方法实现关系。")
        return lines
    current = None
    for row in rows:
        key = "%s.%s" % (row["class_name"], row["method_name"])
        if key != current:
            current = key
            lines.append("")
            lines.append("  %s" % key)
        lines.append("      ← %s" % row["impl_class_name"])
    lines.append("")
    lines.append("提示：入口调用接口方法时，真正执行的是这里的实现类；"
                 "顺着实现类往下看才能判断链是否可达。")
    return lines


QUERIES = {
    "summary": lambda connection, present, args: query_summary(connection, present),
    "entries": lambda connection, present, args: query_entries(connection, present, args.limit),
    "sinks": lambda connection, present, args: query_sinks(connection, present, args.limit),
    "paths": lambda connection, present, args: query_paths(connection, present, args),
    "impls": lambda connection, present, args: query_impls(connection, present, args.limit),
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
    parser.add_argument("-d", "--depth", type=int, default=6,
                        help="利用路径向上回溯的最大层数，默认 6；上限 20")
    args = parser.parse_args(argv)
    # 回溯深度是递归调用的层数上限，放得太大会在超大库上把时间耗在无意义的外围调用上
    if args.depth < 1:
        args.depth = 1
    if args.depth > 20:
        args.depth = 20
    return args


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
        # 结构核对放在查询前面：缺表时查询会安静地返回空，
        # 而「查不到」与「目标干净」在报告里长得一模一样
        missing, fatal = check_schema(connection, present, args.query)
        if fatal:
            print("查询未执行：数据库结构与「%s」查询不兼容。" % args.query)
            print("缺失项：")
            for item in missing:
                print("  - %s" % item)
            print("")
            print("这**不代表目标没有问题**，只代表这次查询没有意义。")
            print("常见原因：后端版本与预期不符，或该库是快速模式 / 早期版本产出的。")
            print("请用与当前工具配套的 jar-analyzer 版本重新建库后重试。")
            return 4
        if missing:
            print("注意：库里有 %d 项结构缺失，部分查询会不可用（本次查询不受影响）："
                  % len(missing))
            for item in missing:
                print("  - %s" % item)
            print("")
        if not has_classes(connection, present):
            # 空库的结果全是「未找到」，容易被读成「目标干净」，因此先明确提示一句；
            # 但**不提前返回**：使用者仍应看到查询本身确实跑过了
            print("注意：数据库里没有记录到任何类（目标可能是空 jar、纯资源 jar，"
                  "或选错了文件）。")
            print("")
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
