"""漏洞特征匹配测试：签名库格式、与 jar_report 的 sink 一致性、过滤与失败路径。

为什么单独一个模块而不是并进 test_jar_report.py：那一个管「库里有什么」（事实查询），
本模块管「这些事实像什么漏洞」（判定）。两者的输出契约不同，混在一起会让
「失败时到底是查询坏了还是签名库坏了」变得难以判断。

最关键的一条断言是 **sink 特征与 jar_report.SINKS 逐条一致**：
签名库里的 sink 是按「类名 + 方法名」精确匹配调用边的，一旦两处漂移，
特征匹配会静默地少报或多报一类漏洞，而报告看起来完全正常。
"""

import io as _io
import contextlib
import json
import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))
# 复用模拟库构造器：本模块的端到端用例同样不需要真实引擎，
# 而且必须与「库里有什么」那一侧共用同一份表结构，否则两侧的模拟库会慢慢走偏
sys.path.insert(0, str(Path(__file__).resolve().parent))
import jar_report  # noqa: E402
import jar_signatures  # noqa: E402
from test_jar_report import build  # noqa: E402

LIBRARY = ROOT / "python" / "vuln_signatures.json"


def run_signatures(database, library=None, **kwargs):
    """执行 jar_signatures.main 并收集 stdout，返回 (退出码, 输出)。"""
    buffer = _io.StringIO()
    argv = ["-db", str(database)]
    if library is not None:
        argv.extend(["-s", str(library)])
    for key, value in kwargs.items():
        flag = "--" + key.replace("_", "-")
        # 布尔开关只能单独传：当成「--flag True」传会让 argparse 直接退出，
        # 那样测的就不是被测逻辑而是 argparse 的参数校验
        if value is True:
            argv.append(flag)
        elif value is not False:
            argv.extend([flag, str(value)])
    with contextlib.redirect_stdout(buffer):
        code = jar_signatures.main(argv)
    return code, buffer.getvalue()


class LibraryFormatTest(unittest.TestCase):
    """签名库自身的格式契约：任何一处不合法都必须能被测出来。"""

    def setUp(self):
        with open(LIBRARY, encoding="utf-8") as handle:
            self.data = json.load(handle)

    def test_library_is_json_object(self):
        self.assertIsInstance(self.data, dict)

    def test_every_signature_declares_known_type(self):
        """每条特征都必须指向一个已登记的漏洞类型，否则类型页会挂空。"""
        known = {item["id"] for item in self.data["vulnerabilityTypes"]}
        self.assertTrue(known)
        offenders = []
        for section in ("strings", "classes", "methods", "sinks"):
            for raw in self.data.get(section) or []:
                if raw.get("type") not in known:
                    offenders.append("%s/%s -> %s" % (section, raw.get("id"), raw.get("type")))
        self.assertEqual([], offenders, "存在未登记的漏洞类型: %s" % offenders)

    def test_every_vulnerability_type_is_reachable(self):
        """每种漏洞类型至少要有一条特征指向它，否则该类型永远不会被报出来。"""
        used = set()
        for section in ("strings", "classes", "methods", "sinks"):
            for raw in self.data.get(section) or []:
                used.add(raw.get("type"))
        for item in self.data["vulnerabilityTypes"]:
            self.assertIn(item["id"], used, "漏洞类型 %s 没有任何特征指向" % item["id"])

    def test_every_type_has_bypasses(self):
        """绕过手法是这份报告的核心交付物：没有绕过手法的类型不该出现在结论里。"""
        for item in self.data["vulnerabilityTypes"]:
            self.assertTrue(item.get("bypasses"), "漏洞类型 %s 没有登记绕过手法" % item["id"])
            self.assertTrue(item.get("trigger"), "漏洞类型 %s 没有触发条件" % item["id"])

    def test_ids_are_unique(self):
        seen = []
        for section in ("strings", "classes", "methods", "sinks"):
            for raw in self.data.get(section) or []:
                seen.append(raw["id"])
        self.assertEqual(len(seen), len(set(seen)), "特征 id 有重复")

    def test_sink_signatures_match_jar_report(self):
        """逐条比对：签名库的 sink 必须与 jar_report.SINKS 完全一致。

        两处的作用不同但数据必须同源——jar_report 用它找「库里有这些调用」，
        jar_signatures 用它判「这些调用像什么漏洞」。漂移的后果是静默的：
        报告只会少一类漏洞，不会报错。
        """
        expected = sorted((owner, method) for _category, owner, method, _detail in jar_report.SINKS)
        actual = sorted((raw["owner"], raw["method"]) for raw in self.data["sinks"])
        self.assertEqual(expected, actual,
                         "sink 特征与 jar_report.SINKS 不一致，缺少 %s，多出 %s"
                         % (sorted(set(expected) - set(actual)), sorted(set(actual) - set(expected))))


class RenderTest(unittest.TestCase):
    """端到端：模拟库 -> 特征匹配 -> 报告含漏洞类型与绕过手法。"""

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="jar-signatures-")
        self.database = os.path.join(self.dir, "jar-analyzer.db")
        build(self.database, calls=[
            ("handle", "com/example/Demo", "()V", "exec",
             "(Ljava/lang/String;)Ljava/lang/Process;", "java/lang/Runtime"),
            ("handle", "com/example/Demo", "()V", "parseObject",
             "(Ljava/lang/String;)Lcom/alibaba/fastjson/JSONObject;", "com/alibaba/fastjson/JSON"),
            ("handle", "com/example/Demo", "()V", "lookup",
             "(Ljava/lang/String;)Ljava/lang/Object;", "javax/naming/InitialContext"),
            ("safe", "com/example/Other", "()V", "trim", "()Ljava/lang/String;", "java/lang/String"),
        ], strings=[
            ("jdbc:mysql://10.0.0.5:3306/db?password=P@ssw0rd",),
        ])

    def test_hits_report_types_and_bypasses(self):
        code, out = run_signatures(self.database)
        self.assertEqual(0, code)
        self.assertIn("可能的漏洞类型与绕过手法", out)
        self.assertIn("绕过手法", out)
        self.assertIn("命中特征", out)
        # 只报真正命中的：普通方法不得出现在命中明细里
        self.assertNotIn("String.trim", out)

    def test_library_version_is_reported(self):
        code, out = run_signatures(self.database)
        self.assertEqual(0, code)
        with open(LIBRARY, encoding="utf-8") as handle:
            version = json.load(handle)["version"]
        self.assertIn(version, out)

    def test_missing_database_fails_readably(self):
        code, out = run_signatures(os.path.join(self.dir, "no-such.db"))
        self.assertEqual(2, code)
        self.assertIn("找不到", out)

    def test_min_severity_filters(self):
        high_code, high_out = run_signatures(self.database, min_severity="high")
        low_code, low_out = run_signatures(self.database, min_severity="low")
        self.assertEqual(0, high_code)
        self.assertEqual(0, low_code)
        self.assertLessEqual(len(high_out.splitlines()), len(low_out.splitlines()),
                             "仅高危的报告行数不应多于含低危的报告")

    def test_no_bypass_omits_bypass_lines_and_title_says_so(self):
        """关掉绕过手法时标题也必须跟着改：标题不实会让人以为输出被截断了。"""
        code, out = run_signatures(self.database, no_bypass=True)
        self.assertEqual(0, code)
        self.assertIn("===== 可能的漏洞类型 =====", out)
        self.assertNotIn("绕过手法（", out)
        # 漏洞类型与命中依据仍必须保留
        self.assertIn("触发条件:", out)
        self.assertIn("命中依据:", out)

    def test_missing_library_fails_readably(self):
        code, out = run_signatures(self.database, library=os.path.join(self.dir, "nope.json"))
        self.assertEqual(2, code)
        self.assertIn("找不到签名库文件", out)

    def test_broken_json_fails_readably(self):
        broken = os.path.join(self.dir, "broken.json")
        with open(broken, "w", encoding="utf-8") as handle:
            handle.write("{ not json")
        code, out = run_signatures(self.database, library=broken)
        self.assertEqual(2, code)
        self.assertIn("JSON", out)

    def test_broken_regex_fails_readably(self):
        """写错的正则必须报错而不是静默跳过：静默会让漏报看起来像「没有命中」。"""
        broken = os.path.join(self.dir, "bad-regex.json")
        with open(broken, "w", encoding="utf-8") as handle:
            json.dump({
                "version": "test",
                "vulnerabilityTypes": [{"id": "x", "name": "x", "trigger": "t",
                                        "bypasses": ["b"]}],
                "strings": [{"id": "S1", "title": "坏正则", "pattern": "(",
                             "severity": "high", "type": "x"}],
            }, handle)
        code, out = run_signatures(self.database, library=broken)
        self.assertEqual(2, code)
        self.assertIn("正则", out)

    def test_unknown_type_fails_readably(self):
        """特征指向未登记类型时必须报错：否则该结论在报告里会挂在一个不存在的类型上。"""
        broken = os.path.join(self.dir, "bad-type.json")
        with open(broken, "w", encoding="utf-8") as handle:
            json.dump({
                "version": "test",
                "vulnerabilityTypes": [{"id": "x", "name": "x", "trigger": "t",
                                        "bypasses": ["b"]}],
                "strings": [{"id": "S1", "title": "越界类型", "pattern": "abc",
                             "severity": "high", "type": "nope"}],
            }, handle)
        code, out = run_signatures(self.database, library=broken)
        self.assertEqual(2, code)
        self.assertIn("nope", out)

    def test_quick_database_without_string_table(self):
        """快速模式下没有 string_table：字符串类特征必须跳过而不是让整次匹配失败。"""
        quick = os.path.join(self.dir, "quick.db")
        build(quick, calls=[
            ("handle", "com/example/Demo", "()V", "exec",
             "(Ljava/lang/String;)Ljava/lang/Process;", "java/lang/Runtime"),
        ], quick=True)
        code, out = run_signatures(quick)
        self.assertEqual(0, code)
        self.assertIn("命中特征", out)


if __name__ == "__main__":
    unittest.main(verbosity=2)