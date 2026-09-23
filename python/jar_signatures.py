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
import datetime
import hashlib
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
# 漏洞类型 -> 本工具里该接着做的动作（功能页 key + 按钮文字）。
#
# 分析结果必须能接上利用：报告停在「可能是命令执行」没有价值，要告诉使用者
# 「接下来开哪个页面、用什么功能」。键与界面导航 key 一致，界面据此渲染跳转按钮。
TYPE_ACTION = {
    "command": ("payload.build", "去生成载荷"),
    "codegen": ("payload.build", "去生成载荷"),
    "deserialization": ("payload.build", "去生成载荷"),
    "jndi": ("service.servers", "去恶意服务器"),
    "expression": ("payload.tostring", "去 toString 链"),
    "template": ("payload.build", "去生成载荷"),
    "sqli": ("capture", "去抓包转换"),
    "ssrf": ("capture", "去抓包转换"),
    "path": ("capture", "去抓包转换"),
    "upload": ("tools.upload", "去文件上传"),
    "xxe": ("capture", "去抓包转换"),
    "exposure": ("capture", "去抓包转换"),
    "credential": ("capture", "去抓包转换"),
    "dos": ("capture", "去抓包转换"),
}

KIND_LABEL = {"strings": "常量", "classes": "类", "methods": "方法", "sinks": "调用"}

# 证据来源的中文名，供筛选下拉框显示。
SECTION_LABEL = {"strings": "字符串常量", "classes": "类名", "methods": "方法名", "sinks": "sink 调用"}

# 机器可读报告的 schema 标识：比对时靠它确认「基线确实是同一份契约」。
SCHEMA = "jsetk.analyze.signatures/1"


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
        # 来源与标签是规则治理的一部分：结论要能被追溯到「依据哪份文档写的」，
        # 标签用于按命中事实筛选绕过手法，而不是按漏洞大类整段贴出
        self.source = raw.get("source", "")
        self.tag = raw.get("tag", "generic")
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
    """一种漏洞类型：名称、触发条件、成立前提、反证条件、常用绕过手法。

    为什么除了「触发条件」还要有前提与反证：静态特征命中只能得出**疑似**。
    报告必须同时说清「还缺什么才算成立」（prerequisites）与
    「什么情况会推翻它」（refutations），否则「出现了特征」会被读成「有漏洞」。
    """

    def __init__(self, raw):
        self.id = raw["id"]
        self.name = raw.get("name", raw["id"])
        self.trigger = raw.get("trigger", "")
        self.prerequisites = list(raw.get("prerequisites") or [])
        self.refutations = list(raw.get("refutations") or [])
        # 绕过手法是结构化的：每条都带来源、适用版本与前置条件。
        # 无差别地贴技巧清单会把「某版本才成立的手法」说成通用结论，
        # 而使用者据此构造的载荷大概率打不通，还会以为是目标不存在漏洞。
        self.bypasses = [item for item in (raw.get("bypasses") or []) if isinstance(item, dict)]
        self.applicable = raw.get("applicable", "")
        self.references = raw.get("references", "")

    def bypasses_for(self, tags):
        """按命中事实筛选绕过手法。

        tags 是本次实际命中的组件/场景标签；只有与之相交的条目才列出，
        generic 表示「与具体组件无关，任何命中都适用」。
        """
        if not tags:
            return [item for item in self.bypasses if "generic" in item.get("tags", [])]
        selected = []
        for item in self.bypasses:
            item_tags = set(item.get("tags") or ["generic"])
            if "generic" in item_tags or item_tags & tags:
                selected.append(item)
        return selected


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

    # 类型引用先核对：错误提示要指向「写错的这一条」，而不是先撞上后面的元数据校验，
    # 否则最小签名库的失败信息会指向与实际问题无关的方向。
    for signature in compiled:
        if signature.type not in types:
            raise SignatureError("签名 %s 指向了未登记的漏洞类型 %s"
                                 % (signature.id, signature.type))

    # 再核对类型元数据：前提 / 反证是报告的核心交付物，缺了它们结论只能
    # 停在「可疑」，使用者无从判断该证实还是排除，因此按「缺了就不合法」处理。
    # 这段必须在校验之后、返回之前执行：曾把它写在 return 之后，等于从未生效。
    for type_id, vuln in sorted(types.items()):
        if not vuln.prerequisites:
            raise SignatureError("漏洞类型 %s 没有登记成立前提（prerequisites）。" % type_id)
        if not vuln.refutations:
            raise SignatureError("漏洞类型 %s 没有登记反证条件（refutations）。" % type_id)
        if not vuln.references:
            raise SignatureError("漏洞类型 %s 没有登记参考来源（references）。" % type_id)
        if not vuln.applicable:
            raise SignatureError("漏洞类型 %s 没有登记适用范围（applicable）。" % type_id)
    for signature in compiled:
        if not signature.source:
            raise SignatureError("特征 %s 没有登记来源（source）。" % signature.id)
    return (data.get("version", "未知"), types, compiled,
            data.get("ruleVersion", data.get("version", "未知")),
            data.get("maintained", ""))


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


def render(connection, present, signatures, vuln_types, version, show_bypass=True,
           rule_version="", maintained="", filters=()):
    """渲染报告：总算 → 按漏洞类型聚合（含绕过手法）→ 命中明细 → 未命中清单。

    filters 是本次生效的筛选说明。必须印在报告里：筛过之后「未命中清单」只覆盖
    被选中的特征，不写明口径读者会以为「整个签名库都没命中」。
    """
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
    if rule_version or maintained:
        lines.append("  规则版本      : %s　维护日期: %s" % (rule_version or version, maintained))
    lines.append("  命中特征      : %d 条（高危 %d、中危 %d、低危 %d）"
                 % (len(hit_ids), counts["high"], counts["medium"], counts["low"]))
    lines.append("  外部可达类    : %d 个（Spring 路由 / JavaWeb 组件）" % len(entries))
    if filters:
        lines.append("  本次筛选      : %s" % "；".join(filters))
        lines.append("                  （下列「未命中清单」只覆盖筛选后的特征，"
                     "不代表签名库里其余特征未命中）")
    lines.append("")

    if not hit_ids:
        lines.append("  未命中任何特征。这只代表「签名库里的特征没有出现在库中」，")
        lines.append("  不代表目标没有漏洞——未收录的特征与业务逻辑缺陷都需要人工确认。")

    # ---- 按漏洞类型聚合：这是报告的最终落点 ----
    by_type = {}
    type_order = []
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
            lines.append("   状态: 疑似（静态特征命中，未验证外部可控；"
                         "本引擎不产出污点分析，故不声称「可绕过」）")
            if vuln.applicable:
                lines.append("   适用范围: %s" % vuln.applicable)
            lines.append("   命中依据: %d 条特征、共 %d 处%s"
                         % (len(items), total,
                            "" if entry_total == 0
                            else "，其中 %d 处在外部可达类里" % entry_total))
            for item in items:
                lines.append("     - [%s] %s（%d 处）"
                             % (item["signature"].label, item["signature"].title,
                                item["count"]))
            # 成立前提与反证条件是「结论能不能被推翻」的答案：
            # 缺了它们，使用者只能看到「可疑」，无从下手去证实或排除
            if vuln.prerequisites:
                lines.append("   成立还需要（缺一不可）:")
                for need in vuln.prerequisites:
                    lines.append("     ? %s" % need)
            if vuln.refutations:
                lines.append("   以下任一成立则推翻本结论:")
                for refutation in vuln.refutations:
                    lines.append("     x %s" % refutation)
            if show_bypass:
                # 只列出与本次命中事实相关的条目：把整类技巧清单无差别贴出来，
                # 会让人以为「这些手法在这里都能用」，而实际上多数手法依赖的组件根本不在目标里
                hit_tags = set()
                for item in items:
                    hit_tags.add(item["signature"].tag)
                selected = vuln.bypasses_for(hit_tags)
                if selected:
                    lines.append("   绕过手法（按本次命中筛选；每条都标了适用版本与前置条件）:")
                    for bypass in selected:
                        lines.append("     * %s" % bypass.get("text", ""))
                        lines.append("         来源: %s" % bypass.get("source", ""))
                        lines.append("         适用: %s" % bypass.get("appliesTo", ""))
                        lines.append("         前提: %s" % bypass.get("requires", ""))
                else:
                    lines.append("   绕过手法：本次命中的组件与本类已登记的手法都对不上，")
                    lines.append("             故不列出——不做无差别贴出，避免给出不适用的技巧。")
            if vuln.references:
                lines.append("   参考来源: %s" % vuln.references)
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
    lines.append("提示：本报告全部结论的状态都是「疑似」——命中只代表「出现了这类特征」，")
    lines.append("      不代表已证明外部可控。要确认或排除，请对照每类下面的")
    lines.append("      「成立还需要」与「以下任一成立则推翻本结论」两栏逐项核对。")
    lines.append("")
    lines.append("===== 本次分析的能力边界 =====")
    lines.append("  · 数据来源：外部工具的调用图数据库（类 / 方法 / 调用边 / 字符串常量），只读。")
    lines.append("  · 没有做的事：本引擎不产出污点分析，因此不能判断某个参数是否受外部控制，")
    lines.append("    也不能据此声称「过滤器可被绕过」。报告里出现的绕过手法是知识提示，")
    lines.append("    每条都带适用版本与前置条件，需人工对照目标代码核对。")
    lines.append("  · 状态口径：observed（已观察）/ suspected（疑似，本报告的结论）/")
    lines.append("    confirmed（人工验证成立）/ not-reproduced（未能复现）/ unknown（未知）。")
    lines.append("    静态特征命中一律记为 suspected；查询失败一律记为 unknown，")
    lines.append("    一律记为「未知」，不得记为「目标干净」。")
    lines.append("  · 严重度、置信度与可达性相互独立，未合并成单一评分。")
    # 机器可读的后续动作：界面据此渲染跳转按钮，直接从这里开始构造利用。
    # 用固定前缀而不是让界面反解中文报告，避免措辞一改按钮就失效。
    if by_type:
        lines.append("")
        lines.append("===== 下一步可以做什么 =====")
        # 按「动作 -> 触发它的漏洞类型」聚合：同一页可能被多类结论指向，
        # 说明行要列全，否则使用者只看到最后一个类型，会以为按钮只对应那一类
        actions = []
        for type_id in type_order:
            action = TYPE_ACTION.get(type_id)
            if not action:
                continue
            for existing in actions:
                if existing[0] == action:
                    existing[1].append(vuln_types[type_id].name)
                    break
            else:
                actions.append((action, [vuln_types[type_id].name]))
        for (nav_key, label), names in actions:
            lines.append("NAV|%s|%s" % (nav_key, label))
            lines.append("  %s：%s" % (label, "、".join(names)))
    return lines


def sha256_of(path):
    """算文件的 SHA-256；读不了返回空串。

    为什么记这个而不是只记文件名：同名不同内容的 jar 极为常见（重新打包、
    带不带 shade），只记路径无法判断「两次分析是不是同一个输入」。
    """
    try:
        digest = hashlib.sha256()
        with open(path, "rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()
    except OSError:
        return ""


def collect_findings(connection, present, signatures, vuln_types,
                     rule_version, maintained):
    """把命中事实整理成结构化的 finding 列表。

    文本报告与机器可读导出共用这一份：两处各写一遍必然漂移，
    表现为「报告里的条数与 JSON 里的条数不一致」，使用者对不上账。
    """
    entries = entry_classes(connection, present)
    by_id = collect_hits(connection, present, signatures, entries)

    findings = []
    for signature in signatures:
        hit = by_id[signature.id]
        if hit["count"] == 0:
            continue
        vuln = vuln_types[signature.type]
        hit_tags = set([signature.tag])
        findings.append({
            "id": signature.id,
            "title": signature.title,
            "section": signature.section,
            "type": signature.type,
            "typeName": vuln.name,
            "severity": signature.severity,
            # 静态特征命中一律记 suspected：本引擎没有污点分析，
            # 不能把「出现了特征」写成 confirmed，那会让人直接对生产目标发包
            "status": "suspected",
            "confidence": {
                "level": "low",
                "basis": "静态特征命中，未验证外部可控",
            },
            "hitCount": hit["count"],
            "entryHitCount": hit["entryCount"],
            "evidence": [
                {"kind": kind, "display": display, "location": location, "entry": bool(is_entry)}
                for kind, display, location, is_entry in hit["samples"]
            ],
            "detail": signature.detail,
            "rule": {
                "id": signature.id,
                "source": signature.source,
                "ruleVersion": rule_version,
                "maintained": maintained,
            },
            "prerequisites": vuln.prerequisites,
            "refutations": vuln.refutations,
            "missingEvidence": [
                "未证明该特征所在方法可由外部入口触发",
                "未证明相关参数受外部控制",
            ],
            "affectedComponent": None,
            "suggestedAction": (TYPE_ACTION.get(signature.type) or ("", ""))[1],
            "references": vuln.references,
            "bypasses": [
                {
                    "text": item.get("text", ""),
                    "source": item.get("source", ""),
                    "appliesTo": item.get("appliesTo", ""),
                    "requires": item.get("requires", ""),
                    "tags": item.get("tags", []),
                }
                for item in vuln.bypasses_for(hit_tags)
            ],
        })
    return findings


def write_json(args, connection, present, signatures, vuln_types, version,
               rule_version, maintained, filters=(), changes=None, baseline_path=""):
    """把结论另存为机器可读 JSON。

    为什么除了文本还要一份 JSON：文本框适合人读，却不适合被后续流程消费
    （按严重度筛选、按状态统计、接入工单）。把数据契约固化下来，
    界面措辞怎么改都不会影响下游解析。

    文件里同时写入数据源状态、任务元数据与能力边界：只给一份 findings 数组，
    读的人无法判断「零命中」是目标干净还是查询没跑成，也无从重跑比对。
    """
    findings = collect_findings(connection, present, signatures, vuln_types,
                                rule_version, maintained)

    sources = {
        "callGraphDatabase": {
            "path": args.database,
            "available": True,
            "mode": "read-only",
            "tables": sorted(present),
            "tableCount": len(present),
        },
        "signatureLibrary": {
            "path": args.signatures or default_signatures(__file__),
            "available": True,
            "version": version,
            "ruleVersion": rule_version,
            "maintained": maintained,
            "signatureCount": len(signatures),
            "typeCount": len(vuln_types),
        },
    }

    payload = {
        "schema": SCHEMA,
        "engine": "JavaSecExpToolKit 漏洞特征匹配",
        "generatedAt": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        # 任务元数据：同一输入要能重跑并比对，就必须知道「这次是谁、拿什么、什么时候跑的」
        "task": {
            "toolVersion": args.tool_version or "（未提供）",
            "databasePath": args.database,
            "databaseSha256": sha256_of(args.database),
            "databaseSize": (os.path.getsize(args.database)
                             if os.path.isfile(args.database) else 0),
            "databaseModified": (datetime.datetime.fromtimestamp(
                os.path.getmtime(args.database)).strftime("%Y-%m-%d %H:%M:%S")
                if os.path.isfile(args.database) else ""),
        },
        "signatureLibraryVersion": version,
        "ruleVersion": rule_version,
        "maintained": maintained,
        "status": "ok",
        # 筛选口径必须写进导出：下游只看到 findings 数组时，
        # 无法判断「条数少」是因为筛选还是因为目标干净
        "filters": list(filters),
        # 与基线的差异：没有基线时为 null，读的人据此知道「这次没有比对」而不是「没有变化」
        "changes": changes,
        "baselinePath": baseline_path,
        "statusValues": ["observed", "suspected", "confirmed", "not-reproduced", "unknown"],
        "sources": sources,
        "findingCount": len(findings),
        "findings": findings,
        # 三个维度分别给出，不压成一个分数
        "dimensions": ["severity（严重度）", "confidence（置信度）",
                       "reachability（可达性，本引擎不产出）"],
        "limitations": [
            "本引擎不产出污点分析，无法判断参数是否受外部控制，也因此不声称过滤器可被绕过。",
            "静态特征命中不等于漏洞成立；查询失败或结构不兼容一律记为 unknown，不得记为「目标干净」。",
            "绕过手法为知识提示，每条都带适用版本与前置条件，需人工对照目标代码核对。",
        ],
    }

    with open(args.json_out, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("")
    print("机器可读报告已写入: %s（%d 条 finding）" % (args.json_out, len(findings)))


def split_values(raw):
    """把 `a,b` 形式的取值拆成列表；空串与空白项一律丢弃。"""
    if not raw:
        return []
    return [item.strip() for item in raw.split(",") if item.strip()]


def parse_filters(args, vuln_types, signatures):
    """把命令行筛选条件解析成 (签名列表, 说明清单)。

    为什么筛选做在脚本侧而不是让界面过滤文本：报告的聚合、绕过手法筛选与
    「未命中清单」都依赖「本次参与了哪些特征」这个输入。界面按行过滤会把
    命中的计数与清单割裂，使用者对不上账。

    未知取值一律报错并列出可选值，不静默忽略：静默忽略会让「筛完没有结论」
    看起来像「目标干净」。
    """
    selected = list(signatures)
    notes = []

    if args.min_severity:
        limit = SEVERITY_RANK[args.min_severity]
        selected = [s for s in selected if s.rank <= limit]
        notes.append("最低严重度 %s" % SEVERITY_LABEL.get(args.min_severity, args.min_severity))

    wanted_types = split_values(args.only_type)
    if wanted_types:
        unknown = [value for value in wanted_types if value not in vuln_types]
        if unknown:
            raise SignatureError("未知的漏洞类型：%s\n可选值：%s"
                                 % ("、".join(unknown), "、".join(sorted(vuln_types))))
        selected = [s for s in selected if s.type in wanted_types]
        notes.append("漏洞类型 %s" % "、".join(wanted_types))

    wanted_sections = split_values(args.only_section)
    if wanted_sections:
        unknown = [value for value in wanted_sections if value not in SECTIONS]
        if unknown:
            raise SignatureError("未知的证据来源：%s\n可选值：%s"
                                 % ("、".join(unknown), "、".join(SECTIONS)))
        selected = [s for s in selected if s.section in wanted_sections]
        notes.append("证据来源 %s" % "、".join(wanted_sections))

    wanted_tags = split_values(args.only_tag)
    if wanted_tags:
        known = set(signature.tag for signature in signatures)
        unknown = [value for value in wanted_tags if value not in known]
        if unknown:
            raise SignatureError("未知的命中标签：%s\n可选值：%s"
                                 % ("、".join(unknown), "、".join(sorted(known))))
        selected = [s for s in selected if s.tag in wanted_tags]
        notes.append("命中标签 %s" % "、".join(wanted_tags))

    return selected, notes


def parse_args(argv):
    parser = argparse.ArgumentParser(
        prog="jar_signatures.py",
        description="把调用图数据库里的代码特征与内置签名库对照（只读）")
    # 不设为 required：--list-filters 只读签名库、不碰数据库，
    # 界面在「还没有建库」时也要能把筛选下拉框填出来
    parser.add_argument("-db", "--database", default="", help="jar-analyzer.db 路径")
    parser.add_argument("-s", "--signatures", default="",
                        help="签名库路径；留空则在脚本同目录下找 vuln_signatures.json")
    parser.add_argument("--min-severity", default="", choices=["", "high", "medium", "low"],
                        help="只保留不低于该严重度的特征")
    parser.add_argument("--no-bypass", action="store_true",
                        help="不输出绕过手法，只看命中清单")
    parser.add_argument("--json-out", default="",
                        help="把同一份结论另存为机器可读 JSON（供后续脚本或工单系统消费）")
    # astra 建议要求支持按严重度 / 状态 / 组件维度筛选。取值未知时报错并列出可选值，
    # 不静默忽略——静默忽略会让「筛完没有结论」看起来像「目标干净」。
    parser.add_argument("--only-type", default="",
                        help="只保留这些漏洞类型（逗号分隔，取值为类型 id）")
    parser.add_argument("--only-section", default="",
                        help="只保留这些证据来源（strings / classes / methods / sinks）")
    parser.add_argument("--only-tag", default="",
                        help="只保留这些命中标签（例如 fastjson、xstream）")
    # 可复现：记录本次是哪个工具版本、哪个目标（SHA-256）跑的，
    # 并允许指定上一次的导出做同输入比对
    parser.add_argument("--baseline", default="",
                        help="上一次导出的机器可读报告；给出后额外输出同输入比对")
    parser.add_argument("--tool-version", default="",
                        help="调用方（本工具）的版本号，写进导出便于对账")
    parser.add_argument("--list-filters", action="store_true",
                        help="只输出可用筛选取值（供界面渲染下拉框），不做匹配")
    return parser.parse_args(argv)


def list_filters(vuln_types, signatures):
    """输出可用的筛选取值，供界面渲染下拉框。

    为什么由脚本给出而不是在 Java 侧写死：取值来自签名库，库一改界面就跟不上，
    表现为「用户看到的下拉项与脚本接受的值不一致」，只能靠报错发现。
    用固定前缀 FILTER| 便于界面解析，不必反解中文。
    """
    lines = []
    for section in SECTIONS:
        lines.append("FILTER|section|%s|%s" % (section, SECTION_LABEL.get(section, section)))
    for type_id in sorted(vuln_types):
        lines.append("FILTER|type|%s|%s" % (type_id, vuln_types[type_id].name))
    tags = sorted(set(signature.tag for signature in signatures))
    for tag in tags:
        lines.append("FILTER|tag|%s|%s" % (tag, tag))
    return lines


def read_baseline(path):
    """读上一次导出的机器可读报告，用于同一输入的重跑比对。

    返回 None 表示没有可比对的基线（首次运行、文件损坏或 schema 不同）。
    不报错：比对是附加信息，缺它不该让本次分析失败。
    """
    if not path or not os.path.isfile(path):
        return None
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (ValueError, OSError):
        return None
    if not isinstance(data, dict) or data.get("schema") != SCHEMA:
        return None
    return data


def compare_with(baseline, findings):
    """把本次结论与基线逐条比对，返回差异描述。

    为什么值得做：静态分析的结果会随规则库与目标版本变化，「这次和上次不一样」
    本身是重要信号——可能换了目标，也可能规则库刚刚更新。只给一份当前结果，
    使用者无从判断结论为何变化。
    """
    if baseline is None:
        return None
    before = {}
    for item in baseline.get("findings") or []:
        before[item.get("id")] = item
    after = {}
    for item in findings:
        after[item["id"]] = item

    added = sorted(set(after) - set(before))
    removed = sorted(set(before) - set(after))
    changed = []
    for key in sorted(set(after) & set(before)):
        old_hits = before[key].get("hitCount", 0)
        new_hits = after[key].get("hitCount", 0)
        if old_hits != new_hits:
            changed.append((key, old_hits, new_hits))
    return {
        "baselineGeneratedAt": baseline.get("generatedAt", ""),
        "added": added,
        "removed": removed,
        "hitCountChanged": [
            {"id": key, "before": old_hits, "after": new_hits}
            for key, old_hits, new_hits in changed
        ],
    }


def render_changes(changes, baseline_path):
    """把差异渲染成报告里的一段。"""
    lines = ["===== 与上一次同输入的比对 ====="]
    if baseline_path:
        lines.append("  基线: %s" % baseline_path)
    if not changes:
        lines.append("  本次未做比对（未找到可用的基线报告，或已关闭比对）。")
        lines.append("")
        return lines
    lines.append("  基线生成时间: %s" % (changes.get("baselineGeneratedAt") or "（未记录）"))
    added = changes.get("added") or []
    removed = changes.get("removed") or []
    changed = changes.get("hitCountChanged") or []
    if not added and not removed and not changed:
        lines.append("  结论与基线一致（命中集合与命中数都没有变化）。")
        lines.append("")
        return lines
    if added:
        lines.append("  本次新增命中（%d 条）: %s" % (len(added), "、".join(added)))
    if removed:
        lines.append("  本次不再命中（%d 条）: %s" % (len(removed), "、".join(removed)))
    for item in changed:
        lines.append("  命中数变化: %s %d -> %d"
                     % (item["id"], item["before"], item["after"]))
    lines.append("  说明：变化可能来自目标本身、规则库更新或筛选条件不同，")
    lines.append("        不能单凭差异判定漏洞新增或消失。")
    lines.append("")
    return lines


def default_signatures(script_path):
    """签名库的默认位置：与脚本同目录。"""
    return os.path.join(os.path.dirname(os.path.abspath(script_path)), "vuln_signatures.json")


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    path = args.signatures or default_signatures(__file__)
    try:
        version, vuln_types, signatures, rule_version, maintained = load_signatures(path)
    except SignatureError as error:
        print(str(error))
        return 2
    if args.list_filters:
        # 只列取值不连库：界面在「还没建库」时也要能把下拉框填出来
        for line in list_filters(vuln_types, signatures):
            print(line)
        return 0
    try:
        signatures, filters = parse_filters(args, vuln_types, signatures)
    except SignatureError as error:
        print(str(error))
        return 2
    if not signatures:
        # 筛空与「目标干净」是两件事：不说清楚会被读成后者
        print("按当前筛选条件（%s）没有剩下任何特征，未执行匹配。" % "；".join(filters))
        print("这**不代表目标干净**，只代表本次没有可对照的特征。")
        return 2
    if not args.database:
        # 缺库时不能安静地继续：后面的查询会全部落空，看起来像「目标干净」
        print("必须指定 -db / --database（除非只用 --list-filters）。")
        return 2
    try:
        connection = connect(args.database)
    except SignatureError as error:
        print(str(error))
        return 2
    try:
        present = tables(connection)
        print("数据库: %s" % args.database)
        if "class_table" in present:
            count = connection.execute("SELECT COUNT(*) FROM class_table").fetchone()[0]
            if not count:
                # 空库里什么都匹配不到，容易被读成「目标干净」，因此先提示一句；
                # 但不提前返回：报告里的「确实跑过」这一信息同样重要
                print("注意：数据库里没有记录到任何类（目标可能是空 jar、纯资源 jar，"
                      "或选错了文件）。")
                print("")
        lines = render(connection, present, signatures, vuln_types, version,
                       show_bypass=not args.no_bypass, rule_version=rule_version,
                       maintained=maintained, filters=filters)
        baseline = read_baseline(args.baseline)
        changes = None
        if args.baseline:
            changes = compare_with(baseline, collect_findings(
                connection, present, signatures, vuln_types, rule_version, maintained))
        # 比对结果属于同一份报告：先并入再打印，否则使用者会以为报告到「未命中清单」就结束了
        lines.extend(render_changes(changes, args.baseline) if args.baseline
                     else render_changes(None, ""))
        for line in lines:
            print(line)
        if args.json_out:
            write_json(args, connection, present, signatures, vuln_types, version,
                       rule_version, maintained, filters, changes, args.baseline)
        return 0
    except sqlite3.Error as error:
        print("查询失败：%s" % error)
        return 3
    finally:
        connection.close()


if __name__ == "__main__":
    sys.exit(main())