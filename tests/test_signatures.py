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

    def test_every_type_has_prerequisites_and_refutations(self):
        """每种类型都必须能回答「还缺什么」与「什么会推翻它」。

        这是报告从「列特征」升级为「可验证假设」的关键：只有触发条件时，
        使用者看到「可能是命令执行」却无从判断该证实还是排除。
        """
        for item in self.data["vulnerabilityTypes"]:
            self.assertTrue(item.get("prerequisites"),
                            "漏洞类型 %s 没有成立前提" % item["id"])
            self.assertTrue(item.get("refutations"),
                            "漏洞类型 %s 没有反证条件" % item["id"])

    def test_every_signature_has_source_and_tag(self):
        """每条特征都要登记来源与标签。

        来源是规则治理的最低要求：一条判定说不出「依据哪份文档写的」，
        使用者就无法判断该不该按它行动。标签用于按命中事实筛选绕过手法。
        """
        offenders = []
        for section in ("strings", "classes", "methods", "sinks"):
            for raw in self.data.get(section) or []:
                if not raw.get("source"):
                    offenders.append("%s/%s 缺 source" % (section, raw.get("id")))
                if not raw.get("tag"):
                    offenders.append("%s/%s 缺 tag" % (section, raw.get("id")))
        self.assertEqual([], offenders)

    def test_library_declares_rule_version_and_maintained(self):
        """规则库要能回答「这是哪一版、什么时候维护的」，否则结论无法对账。"""
        self.assertTrue(self.data.get("ruleVersion"), "缺少 ruleVersion")
        self.assertRegex(self.data.get("maintained", ""), r"^\d{4}-\d{2}-\d{2}$")

    def test_every_type_declares_applicable_and_references(self):
        """每种类型都要写明适用范围与参考来源，不能只给一个名字。"""
        for item in self.data["vulnerabilityTypes"]:
            self.assertTrue(item.get("applicable"), "类型 %s 缺 applicable" % item["id"])
            self.assertTrue(item.get("references"), "类型 %s 缺 references" % item["id"])

    def test_bypasses_are_traced_and_conditional(self):
        """绕过手法必须是结构化的：带来源、适用版本、前置条件与筛选标签。

        无差别地贴技巧清单会把「某版本才成立的手法」说成通用结论，
        使用者据此构造的载荷大概率打不通，还会以为是目标不存在漏洞。
        """
        for item in self.data["vulnerabilityTypes"]:
            self.assertTrue(item.get("bypasses"), "类型 %s 没有绕过手法" % item["id"])
            for bypass in item["bypasses"]:
                self.assertIsInstance(bypass, dict,
                                      "类型 %s 的绕过手法应为结构化对象" % item["id"])
                for field in ("text", "source", "appliesTo", "requires", "tags"):
                    self.assertTrue(bypass.get(field),
                                    "类型 %s 的一条绕过手法缺 %s" % (item["id"], field))

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

    def test_report_shows_prerequisites_and_refutations(self):
        """报告正文必须把成立前提与反证条件都印出来，不能只写在库里。"""
        code, out = run_signatures(self.database)
        self.assertEqual(0, code)
        self.assertIn("成立还需要（缺一不可）", out)
        self.assertIn("以下任一成立则推翻本结论", out)
        self.assertIn("状态: 疑似", out)

    def test_report_never_claims_confirmed(self):
        """静态特征匹配不得给出「已确认」这类措辞。

        把特征命中说成确认漏洞，是本模块最严重的误报形态：
        使用者会据此直接对生产目标发包。
        """
        code, out = run_signatures(self.database)
        self.assertEqual(0, code)
        for wording in ("已确认", "确认存在漏洞", "确定存在"):
            self.assertNotIn(wording, out, "报告出现「%s」这类确定性措辞" % wording)

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

    def test_report_shows_bypass_source_and_conditions(self):
        """报告里的绕过手法要带来源与前置条件，不能只列一句技巧。"""
        code, out = run_signatures(self.database)
        self.assertEqual(0, code)
        self.assertIn("绕过手法（按本次命中筛选", out)
        self.assertIn("来源:", out)
        self.assertIn("适用:", out)
        self.assertIn("前提:", out)

    def test_report_states_capability_boundary(self):
        """报告必须写明「不产出污点分析」，否则「疑似」会被读成「可绕过」。"""
        code, out = run_signatures(self.database)
        self.assertEqual(0, code)
        self.assertIn("本次分析的能力边界", out)
        self.assertIn("不产出污点分析", out)
        self.assertIn("suspected", out)
        self.assertIn("不得", out)

    def test_report_shows_rule_version_and_maintained_date(self):
        code, out = run_signatures(self.database)
        self.assertEqual(0, code)
        self.assertIn("规则版本", out)
        with open(LIBRARY, encoding="utf-8") as handle:
            data = json.load(handle)
        self.assertIn(data["ruleVersion"], out)
        self.assertIn(data["maintained"], out)

    def test_bypass_filtered_by_hit_facts(self):
        """绕过手法按命中事实筛选：没命中对应组件的手法不出现在报告里。

        这是 astra 建议里明确反对的一点——按漏洞大类整段贴出技巧清单，
        会让人以为「这些手法在这里都能用」。
        """
        code, out = run_signatures(self.database)
        self.assertEqual(0, code)
        # 模拟库只命中 Runtime.exec / InitialContext.lookup / fastjson，
        # 与 Struts2、Freemarker 这些组件无关的手法不应出现
        self.assertNotIn("Struts2 下先", out)
        self.assertNotIn("Freemarker：<#assign", out)

    def test_json_export_is_machine_readable(self):
        """机器可读导出：只给一份 findings 数组不够，必须带数据源与局限。"""
        import tempfile as _tempfile
        target = os.path.join(_tempfile.mkdtemp(prefix="jar-signatures-json-"),
                              "report.json")
        code, out = run_signatures(self.database, json_out=target)
        self.assertEqual(0, code)
        self.assertIn("机器可读报告已写入", out)
        with open(target, encoding="utf-8") as handle:
            payload = json.load(handle)
        self.assertEqual("jsetk.analyze.signatures/1", payload["schema"])
        self.assertTrue(payload["findings"])
        self.assertTrue(payload["limitations"])
        self.assertIn("reachability", "".join(payload["dimensions"]))
        for finding in payload["findings"]:
            # 静态特征命中一律 suspected：写成 confirmed 会让人直接对生产目标发包
            self.assertEqual("suspected", finding["status"])
            self.assertTrue(finding["prerequisites"])
            self.assertTrue(finding["refutations"])
            self.assertTrue(finding["rule"]["source"])
            self.assertTrue(finding["rule"]["ruleVersion"])

    def test_json_export_always_flags_suspected(self):
        """导出里不得出现 confirmed：本引擎没有验证能力。"""
        import tempfile as _tempfile
        target = os.path.join(_tempfile.mkdtemp(prefix="jar-signatures-json2-"),
                              "report.json")
        code, out = run_signatures(self.database, json_out=target)
        self.assertEqual(0, code)
        with open(target, encoding="utf-8") as handle:
            raw = handle.read()
        self.assertNotIn('"status": "confirmed"', raw)
        self.assertNotIn("confirmed（", raw)

    def test_filters_narrow_the_signature_set(self):
        """筛选必须真的收窄参与匹配的特征，并且报告写明本次口径。

        不写明口径时「未命中清单」会被读成「整个签名库都没命中」，
        与筛选后的实际范围不符。
        """
        code, out = run_signatures(self.database, only_type="command")
        self.assertEqual(0, code)
        self.assertIn("本次筛选", out)
        self.assertIn("漏洞类型 command", out)
        self.assertIn("不代表签名库里其余特征未命中", out)

    def test_unknown_filter_value_fails_readably(self):
        """未知取值必须报错并列出可选值：静默忽略会被读成「目标干净」。"""
        code, out = run_signatures(self.database, only_type="nope")
        self.assertEqual(2, code)
        self.assertIn("未知的漏洞类型", out)
        self.assertIn("可选值", out)

    def test_unknown_section_and_tag_fail_readably(self):
        code, out = run_signatures(self.database, only_section="nope")
        self.assertEqual(2, code)
        self.assertIn("未知的证据来源", out)
        code, out = run_signatures(self.database, only_tag="nope")
        self.assertEqual(2, code)
        self.assertIn("未知的命中标签", out)

    def test_filter_that_selects_nothing_says_so(self):
        """筛空与「目标干净」是两件事，必须显式区分。"""
        code, out = run_signatures(self.database, only_section="strings", no_bypass=True,
                                   min_severity="high", only_type="deserialization")
        self.assertEqual(0, code, out)
        self.assertIn("命中特征", out)

    def test_list_filters_works_without_database(self):
        """界面在还没建库时也要能填下拉框，因此列出取值不能依赖数据库。"""
        buf = _io.StringIO()
        argv = ["--list-filters"]
        with contextlib.redirect_stdout(buf):
            code = jar_signatures.main(argv)
        out = buf.getvalue()
        self.assertEqual(0, code, out)
        self.assertIn("FILTER|section|sinks|", out)
        self.assertIn("FILTER|type|command|", out)
        self.assertIn("FILTER|tag|", out)

    def test_export_records_task_metadata(self):
        """可复现要求：导出要能回答「谁、拿什么、什么时候跑的」。"""
        import tempfile as _tempfile
        target = os.path.join(_tempfile.mkdtemp(prefix="jsetk-task-"), "report.json")
        code, out = run_signatures(self.database, json_out=target, tool_version="0.1.0")
        self.assertEqual(0, code)
        with open(target, encoding="utf-8") as handle:
            payload = json.load(handle)
        task = payload["task"]
        self.assertEqual("0.1.0", task["toolVersion"])
        self.assertEqual(64, len(task["databaseSha256"]))
        self.assertTrue(task["databaseModified"])
        self.assertTrue(payload["generatedAt"])
        self.assertEqual([], payload["filters"])
        self.assertIsNone(payload["changes"])
        self.assertEqual("", payload["baselinePath"])

    def test_baseline_comparison_reports_changes(self):
        """同输入重跑要能看出差异：报告与导出都要带上比对结果。"""
        import tempfile as _tempfile
        work = _tempfile.mkdtemp(prefix="jsetk-baseline-")
        first = os.path.join(work, "first.json")
        code, out = run_signatures(self.database, json_out=first)
        self.assertEqual(0, code)
        second = os.path.join(work, "second.json")
        code, out = run_signatures(self.database, json_out=second, baseline=first)
        self.assertEqual(0, code)
        self.assertIn("与上一次同输入的比对", out)
        self.assertIn("结论与基线一致", out)
        with open(second, encoding="utf-8") as handle:
            payload = json.load(handle)
        self.assertEqual(first, payload["baselinePath"])
        self.assertEqual([], payload["changes"]["added"])
        self.assertEqual([], payload["changes"]["removed"])

    def test_baseline_change_is_detected(self):
        """基线里的命中数变化与消失的条目都必须被报出来（而不是静默一致）。"""
        import tempfile as _tempfile
        work = _tempfile.mkdtemp(prefix="jsetk-baseline2-")
        baseline = os.path.join(work, "baseline.json")
        with open(baseline, "w", encoding="utf-8") as handle:
            json.dump({
                "schema": "jsetk.analyze.signatures/1",
                "generatedAt": "2026-09-23 00:00:00",
                "findings": [
                    {"id": "SNK-10", "hitCount": 99},
                    {"id": "SIG-THAT-DISAPPEARED", "hitCount": 1},
                ],
            }, handle)
        code, out = run_signatures(self.database, baseline=baseline)
        self.assertEqual(0, code)
        # SNK-10 本次确实命中，命中数与基线不同 -> 报「命中数变化」
        self.assertIn("命中数变化", out)
        self.assertIn("SNK-10", out)
        # 基线独有、本次未命中 -> 报「不再命中」
        self.assertIn("本次不再命中", out)
        self.assertIn("SIG-THAT-DISAPPEARED", out)

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


class BaselineTest(unittest.TestCase):
    """?????????????????????????????????"""

    def test_compare_without_baseline_returns_none(self):
        self.assertIsNone(jar_signatures.compare_with(None, [{"id": "X", "hitCount": 1}]))

    def test_read_baseline_missing_file_returns_none(self):
        self.assertIsNone(jar_signatures.read_baseline(""))
        self.assertIsNone(jar_signatures.read_baseline(
            os.path.join(tempfile.gettempdir(), "no-such-baseline.json")))

    def test_read_baseline_broken_json_returns_none(self):
        """???????????????????????????"""
        work = tempfile.mkdtemp(prefix="jsetk-broken-baseline-")
        broken = os.path.join(work, "broken.json")
        with open(broken, "w", encoding="utf-8") as handle:
            handle.write("{ this is not json")
        self.assertIsNone(jar_signatures.read_baseline(broken))

    def test_read_baseline_other_schema_returns_none(self):
        """schema ????????????????????????????"""
        work = tempfile.mkdtemp(prefix="jsetk-other-schema-")
        other = os.path.join(work, "other.json")
        with open(other, "w", encoding="utf-8") as handle:
            json.dump({"schema": "something-else/9", "findings": []}, handle)
        self.assertIsNone(jar_signatures.read_baseline(other))

    def test_read_baseline_accepts_own_export(self):
        """???????????????????????????"""
        work = tempfile.mkdtemp(prefix="jsetk-own-baseline-")
        own = os.path.join(work, "own.json")
        with open(own, "w", encoding="utf-8") as handle:
            json.dump({"schema": "jsetk.analyze.signatures/1", "findings": []}, handle)
        self.assertIsNotNone(jar_signatures.read_baseline(own))


if __name__ == "__main__":
    unittest.main(verbosity=2)