"""漏洞特征匹配：把调用图数据库里的代码特征与内置签名库对照，给出漏洞类型与绕过手法。

为什么不并进 jar_report.py：那个脚本回答的是「库里有什么」（总览 / 入口 / sink /
字符串 / 组件），属于事实查询，输出即结论；本脚本回答的是「这些事实像什么漏洞」，
属于判定，必须带严重度、命中依据、漏洞类型、绕过手法与免责说明。
两者的输出契约与演进节奏都不同。

四类证据全部来自数据库，都能直接读到：
  strings  字符串常量（string_table）——硬编码密钥、JNDI 地址、表达式特征
  classes  类名（class_table）——反序列化实现类、入口类、模板引擎类
  methods  方法名（method_table）——readObject、lookup、doGet 一类回调与入口
  sinks    方法调用边（method_call_table）——Runtime.exec、ObjectInputStream.readObject 一类危险调用

为什么不在这里判组件版本：数据库只记 jar 名与路径，不含 Maven 坐标，
而版本区间判定需要坐标，那部分由 Java 侧按依赖元数据完成。

只用标准库。所有查询都是只读 SELECT。
"""

import argparse
import json
import os
import re
import sqlite3
import sys

# 签名库里的四个分节，分别对应四类证据。
SECTIONS = ("strings", "classes", "methods", "sinks")

SEVERITY_RANK = {"high": 0, "medium": 1, "low": 2}
SEVERITY_LABEL = {"high": "高", "medium": "中", "low": "低"}

# 每条特征最多列出的命中样例数：命中上百处时全列出来只会淹没有用信息。
SAMPLES_PER_SIGNATURE = 5

# 证据类型的中文名，用于样例行前缀。
KIND_LABEL = {"strings": "常量", "classes": "类", "methods": "方法", "sinks": "调用"}


class SignatureError(Exception):
    """签名库或数据库不可用：文件缺失、格式不符、正则非法，都归到这一类。"""


class Signature(object):
    """一条编译好的特征：分节 + 元信息 + 正则。"""

    def __init__(self, section, raw, regex, owner="", method=""):
        self.section = section
        self.id = raw["id"]
        self.severity = raw.get("severity", "low")
        self.category = raw.get("category", "其它")
        self.type = raw.get("type", "")
        self.title = raw.get("title", "")
        self.detail = raw.get("detail", "")
        self.regex = regex
        self.owner = owner
        self.method = method

    @property
    def rank(self):
        return SEVERITY_RANK.get(self.severity, 9)

    @property
    def label(self):
        return SEVERITY_LABEL.get(self.severity, self.severity)


class VulnType(object):
    """一种漏洞类型：名称、触发条件、常用绕过手法。"""

    def __init__(self, raw):
        self.id = raw["id"]
        self.name = raw.get("name", raw["id"])
        self.trigger = raw.get("trigger", "")
        self.bypasses = list(raw.get("bypasses") or [])


def load_signatures(path):
    """读并编译签名库；任何一处不合法都直接报错，不静默跳过。

    静默跳过单条签名会让「漏报」看起来像「没有命中」——使用者会以为目标干净。
    返回 (版本, 漏洞类型表, 签名列表)。
    """
    if not path or not os.path.isfile(path):
        raise SignatureError("找不到签名库文件：%s" % path)
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except ValueError as error:
        raise SignatureError("签名库不是合法的 JSON：%s" % error)
    if not isinstance(data, dict):
        raise SignatureError("签名库顶层必须是 JSON 对象。")

    types = {}
    for raw in data.get("vulnerabilityTypes") or []:
        if not raw.get("id"):
            raise SignatureError("漏洞类型条目缺少 id。")
        types[raw["id"]] = VulnType(raw)
    if not types:
        raise SignatureError("签名库没有登记任何漏洞类型。")

    compiled = []
    for section in SECTIONS:
        entries = data.get(section)
        if entries is None:
            continue
        if not isinstance(entries, list):
            raise SignatureError("签名库的 %s 分节必须是数组。" % section)
        for raw in entries:
            if not isinstance(raw, dict):
                raise SignatureError("%s 分节里出现非对象的条目。" % section)
            for required in ("id", "title"):
                if not raw.get(required):
                    raise SignatureError("%s 分节里有条目缺少 %s 字段。" % (section, required))
            if section == "sinks":
                owner = raw.get("owner", "")
                method = raw.get("method", "")
                if not owner or not method:
                    raise SignatureError("sink 条目 %s 缺少 owner 或 method。" % raw["id"])
                # sink 按「类名 + 方法名」精确匹配调用边，不走正则：
                # 正则匹配会把 readObjectInternal 这类无关方法也算进来
                compiled.append(Signature(section, raw, None, owner, method))
                continue
            if not raw.get("pattern"):
                raise SignatureError("%s 分节里有条目缺少 pattern 字段。" % section)
            try:
                regex = re.compile(raw["pattern"])
            except re.error as error:
                raise SignatureError("签名 %s 的正则非法：%s" % (raw["id"], error))
            compiled.append(Signature(section, raw, regex))
    if not compiled:
        raise SignatureError("签名库没有任何可用的特征。")

    for signature in compiled:
        if signature.type not in types:
            raise SignatureError("签名 %s 指向了未登记的漏洞类型 %s"
                                 % (signature.id, signature.type))
    return data.get("version", "未知"), types, compiled


def connect(database):
    """只读打开数据库：本脚本绝不写库。"""
    if not os.path.isfile(database):
        raise SignatureError(
            "找不到数据库文件：%s\n请先执行一次「调用链分析」构建它。" % database)
    try:
        connection = sqlite3.connect("file:%s?mode=ro" % database.replace("?", "%3f"), uri=True)
    except sqlite3.Error as error:
        raise SignatureError("无法打开数据库 %s：%s" % (database, error))
    connection.row_factory = sqlite3.Row
    return connection


def tables(connection):
    rows = connection.execute(
        "SELECT name FROM sqlite_master WHERE type='table'").fetchall()
    return set(row["name"] for row in rows)


def rows_of(connection, present, table, sql, params=()):
    """取一批行；表不存在或查询失败时返回空列表（由调用方在报告里说明）。"""
    if table not in present:
        return []
    try:
        return connection.execute(sql, params).fetchall()
    except sqlite3.Error:
        return []


def simple_name(class_name):
    """JVM 内部名取末段，用于展示。"""
    if not class_name:
        return ""
    return class_name.rsplit("/", 1)[-1]


def entry_classes(connection, present):
    """外部可达的类集合：Spring 路由与 JavaWeb 组件。

    用来给命中打「入口点」标记——同一处危险调用，若其所在类本身就是外部入口，
    可达性判定的代价完全不同，这个标记比命中数本身更有用。
    """
    names = set()
    for table in ("spring_method_table", "java_web_table"):
        for row in rows_of(connection, present, table,
                           "SELECT DISTINCT class_name FROM " + table):
            value = row["class_name"]
            if value:
                names.add(value)
    return names


def clip(text, limit=140):
    """长常量截断展示：完整值太长会把样例行挤到看不见。"""
    flat = str(text).replace("\n", " ").replace("\r", " ")
    return flat if len(flat) <= limit else flat[:limit - 3] + "..."


def record(hit, kind, display, location, is_entry):
    """记一次命中：累计计数，并保留前若干个样例。"""
    hit["count"] += 1
    if is_entry:
        hit["entryCount"] += 1
    if len(hit["samples"]) < SAMPLES_PER_SIGNATURE:
        hit["samples"].append((kind, display, location, is_entry))


def collect_hits(connection, present, signatures, entries):
    """把四类证据逐条与签名对照，按签名 id 聚合命中。"""
    by_id = {}
    for signature in signatures:
        by_id[signature.id] = {"signature": signature, "count": 0, "samples": [],
                               "entryCount": 0}
    by_section = {}
    for signature in signatures:
        by_section.setdefault(signature.section, []).append(signature)

    # ---- 字符串常量 ----
    for row in rows_of(connection, present, "string_table",
                       "SELECT value, class_name, method_name FROM string_table "
                       "WHERE value IS NOT NULL"):
        value = row["value"]
        if not value:
            continue
        class_name = row["class_name"]
        for signature in by_section.get("strings", ()):
            if not signature.regex.search(value):
                continue
            record(by_id[signature.id], KIND_LABEL["strings"], clip(value),
                   "%s.%s" % (class_name, row["method_name"]), class_name in entries)

    # ---- 类名 ----
    for row in rows_of(connection, present, "class_table",
                       "SELECT class_name FROM class_table WHERE class_name IS NOT NULL"):
        class_name = row["class_name"]
        simple = simple_name(class_name)
        for signature in by_section.get("classes", ()):
            if not signature.regex.search(simple):
                continue
            record(by_id[signature.id], KIND_LABEL["classes"], simple, class_name,
                   class_name in entries)

    # ---- 方法名 ----
    for row in rows_of(connection, present, "method_table",
                       "SELECT class_name, method_name, method_desc FROM method_table "
                       "WHERE method_name IS NOT NULL"):
        class_name = row["class_name"]
        name = row["method_name"]
        display = "%s.%s%s" % (simple_name(class_name), name, row["method_desc"] or "")
        for signature in by_section.get("methods", ()):
            if not signature.regex.search(name):
                continue
            record(by_id[signature.id], KIND_LABEL["methods"], display, class_name,
                   class_name in entries)

    # ---- sink 调用边：按「类名 + 方法名」精确匹配 ----
    for signature in by_section.get("sinks", ()):
        rows = rows_of(connection, present, "method_call_table",
                       "SELECT caller_class_name, COUNT(*) AS hits FROM method_call_table "
                       "WHERE callee_class_name = ? AND callee_method_name = ? "
                       "GROUP BY caller_class_name ORDER BY hits DESC",
                       (signature.owner, signature.method))
        for row in rows:
            caller = row["caller_class_name"]
            record(by_id[signature.id], KIND_LABEL["sinks"],
                   "%s -> %s（%d 处）" % (simple_name(caller), signature.title, row["hits"]),
                   caller, caller in entries)

    return by_id


def render(connection, present, signatures, vuln_types, version, show_bypass=True):
    """渲染报告：总算 → 按漏洞类型聚合（含绕过手法）→ 命中明细 → 未命中清单。"""
    entries = entry_classes(connection, present)
    by_id = collect_hits(connection, present, signatures, entries)

    hit_ids = [key for key, value in by_id.items() if value["count"] > 0]
    ordered = sorted(hit_ids, key=lambda key: (by_id[key]["signature"].rank, key))
    counts = {"high": 0, "medium": 0, "low": 0}
    for key in hit_ids:
        severity = by_id[key]["signature"].severity
        counts[severity] = counts.get(severity, 0) + 1

    lines = ["===== 漏洞特征匹配 ====="]
    lines.append("  签名库        : %s（%d 条特征 / %d 种漏洞类型）"
                 % (version, len(signatures), len(vuln_types)))
    lines.append("  命中特征      : %d 条（高危 %d、中危 %d、低危 %d）"
                 % (len(hit_ids), counts["high"], counts["medium"], counts["low"]))
    lines.append("  外部可达类    : %d 个（Spring 路由 / JavaWeb 组件）" % len(entries))
    lines.append("")

    if not hit_ids:
        lines.append("  未命中任何特征。这只代表「签名库里的特征没有出现在库中」，")
        lines.append("  不代表目标没有漏洞——未收录的特征与业务逻辑缺陷都需要人工确认。")

    # ---- 按漏洞类型聚合：这是报告的最终落点 ----
    by_type = {}
    for key in ordered:
        signature = by_id[key]["signature"]
        by_type.setdefault(signature.type, []).append(by_id[key])
    if by_type:
        # 标题随开关变化：关掉绕过手法后标题里还写着「与绕过手法」会让使用者
        # 以为输出被截断了，反复重跑确认
        lines.append("===== 可能的漏洞类型与绕过手法 =====" if show_bypass
                     else "===== 可能的漏洞类型 =====")
        lines.append("")
        type_order = sorted(by_type.keys(), key=lambda type_id: (
            min(item["signature"].rank for item in by_type[type_id]),
            vuln_types[type_id].name))
        for index, type_id in enumerate(type_order, start=1):
            items = by_type[type_id]
            vuln = vuln_types[type_id]
            severity = items[0]["signature"].severity
            total = sum(item["count"] for item in items)
            entry_total = sum(item["entryCount"] for item in items)
            lines.append("%d. %s（最高严重度：%s）" % (index, vuln.name,
                                                    SEVERITY_LABEL.get(severity, severity)))
            lines.append("   触发条件: %s" % vuln.trigger)
            lines.append("   命中依据: %d 条特征、共 %d 处%s"
                         % (len(items), total,
                            "" if entry_total == 0
                            else "，其中 %d 处在外部可达类里" % entry_total))
            for item in items:
                lines.append("     - [%s] %s（%d 处）"
                             % (item["signature"].label, item["signature"].title,
                                item["count"]))
            if show_bypass:
                lines.append("   绕过手法（该类型下实战常见，供构造与验证时参考）:")
                for bypass in vuln.bypasses:
                    lines.append("     * %s" % bypass)
            lines.append("")

    # ---- 命中明细 ----
    if ordered:
        lines.append("===== 命中明细 =====")
        for key in ordered:
            hit = by_id[key]
            signature = hit["signature"]
            lines.append("[%s] %s" % (signature.label, signature.title))
            lines.append("    特征: %s（%s / %s / 漏洞类型 %s）"
                         % (signature.id, signature.section, signature.category,
                            vuln_types[signature.type].name))
            lines.append("    命中: %d 处%s"
                         % (hit["count"],
                            "" if hit["entryCount"] == 0
                            else "，其中 %d 处在外部可达类里" % hit["entryCount"]))
            for kind, display, location, is_entry in hit["samples"]:
                lines.append("      [%s] %s%s"
                             % (kind, display, "  [入口点]" if is_entry else ""))
                lines.append("           %s" % location)
            lines.append("    说明: %s" % signature.detail)
            lines.append("")

    missed = [s.id for s in signatures if by_id[s.id]["count"] == 0]
    if missed:
        lines.append("未命中的特征（%d 条，列出以便确认「确实跑过」而不是漏跑）：" % len(missed))
        for index in range(0, len(missed), 4):
            lines.append("  " + "  ".join(missed[index:index + 4]))
        lines.append("")
    lines.append("提示：命中只代表「出现了这类特征」，是否可利用取决于参数能否被外部控制，")
    lines.append("      以及目标是否真的解析这段数据。请结合命中处的调用方继续往上追。")
    return lines


def parse_args(argv):
    parser = argparse.ArgumentParser(
        prog="jar_signatures.py",
        description="把调用图数据库里的代码特征与内置签名库对照（只读）")
    parser.add_argument("-db", "--database", required=True, help="jar-analyzer.db 路径")
    parser.add_argument("-s", "--signatures", default="",
                        help="签名库路径；留空则在脚本同目录下找 vuln_signatures.json")
    parser.add_argument("--min-severity", default="", choices=["", "high", "medium", "low"],
                        help="只保留不低于该严重度的特征")
    parser.add_argument("--no-bypass", action="store_true",
                        help="不输出绕过手法，只看命中清单")
    return parser.parse_args(argv)


def default_signatures(script_path):
    """签名库的默认位置：与脚本同目录。"""
    return os.path.join(os.path.dirname(os.path.abspath(script_path)), "vuln_signatures.json")


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    path = args.signatures or default_signatures(__file__)
    try:
        version, vuln_types, signatures = load_signatures(path)
    except SignatureError as error:
        print(str(error))
        return 2
    if args.min_severity:
        limit = SEVERITY_RANK[args.min_severity]
        signatures = [s for s in signatures if s.rank <= limit]
        if not signatures:
            print("按最低严重度 %s 过滤后没有剩下任何特征。" % args.min_severity)
            return 2
    try:
        connection = connect(args.database)
    except SignatureError as error:
        print(str(error))
        return 2
    try:
        present = tables(connection)
        print("数据库: %s" % args.database)
        for line in render(connection, present, signatures, vuln_types, version,
                            show_bypass=not args.no_bypass):
            print(line)
        return 0
    except sqlite3.Error as error:
        print("查询失败：%s" % error)
        return 3
    finally:
        connection.close()


if __name__ == "__main__":
    sys.exit(main())