"""Fastjson 探测引擎测试：识别 / 版本 / 期望类 / DNS 探针。"""

import json
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))
import fj_probe  # noqa: E402
from fj_probe import run  # noqa: E402


class FastjsonLikeHandler(BaseHTTPRequestHandler):
    """模拟 1.2.68 语境下的 Fastjson 端点，用于离线验证各探测模式。"""

    def _body(self):
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length).decode("utf-8", errors="replace")

    def _reply(self, payload, status=200):
        encoded = payload.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_POST(self):
        body = self._body()
        if body.strip() == '{"@type":':
            return self._reply("com.alibaba.fastjson.JSONException: syntax error, expect {", 400)
        if "Test.TestException" in body:
            return self._reply("com.alibaba.fastjson.JSONException: autoType is not support", 400)
        if "support.geo.Feature" in body:
            return self._reply("autoType is not support. com.alibaba.fastjson.support.geo.Feature", 400)
        if '"@type":"whatever"' in body:
            return self._reply("autoType is not support. whatever", 400)
        if "new a(1)" in body:
            return self._reply('{"a":1,"b":"EQ==","c":[{}]}')
        if "$ref" in body:
            return self._reply('{"ext":"blue","name":"blue"}')
        if '"test":{{{}:{}' in body:
            return self._reply('{"ok":true}')
        if "{}:{}" in body:
            return self._reply("com.alibaba.fastjson.JSONException: syntax error, expect {, actual {", 400)
        if body.startswith('{"@type":"java.lang.AutoCloseable"'):
            return self._reply(
                "com.alibaba.fastjson.JSONException: syntax error, fastjson-version 1.2.68", 400
            )
        if "java.io.ByteArrayOutputStream" in body:
            return self._reply('{"ok":true}')
        if "java.lang.Exception" in body:
            return self._reply("com.alibaba.fastjson.JSONException: autoType is not support", 400)
        if "JdbcRowSetImpl" in body:
            return self._reply(
                "autoType is not support. com.sun.rowset.JdbcRowSetImpl", 400
            )
        if "Random.String" in body:
            return self._reply("autoType is not support. Random.String", 400)
        if body.startswith('{"age":20,"name":"Bob"') and not body.endswith("}"):
            return self._reply("com.alibaba.fastjson.JSONException: syntax error, expect {", 400)
        return self._reply('{"ok":true}')

    def log_message(self, format, *args):
        return


class ServerMixin(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), FastjsonLikeHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = "http://127.0.0.1:{0}/json".format(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join(timeout=2)
        cls.server.server_close()


class DetectTest(ServerMixin):
    def test_fastjson_fingerprint(self):
        result = run(self.url, "detect", 5.0)
        self.assertTrue(result["is_fastjson"])
        self.assertGreaterEqual(result["confidence"], 0.8)
        self.assertTrue(any(item["matched"] for item in result["evidence"]))
        json.dumps(result, ensure_ascii=False)

    def test_legacy_detect_helper(self):
        from fj_probe import detect

        result = detect(self.url, 5.0)
        self.assertTrue(result["is_fastjson"])


class VersionTest(ServerMixin):
    def test_version_range(self):
        result = run(self.url, "version", 5.0)
        self.assertEqual(result["reported_version"], "1.2.68")
        self.assertEqual(result["version_detail"], "1.2.48-1.2.68")
        self.assertEqual(result["version_range"], "<=1.2.68")
        self.assertFalse(result["autotype_enabled"])
        self.assertIn("flags", result)
        json.dumps(result, ensure_ascii=False)

    def test_version_without_ceye_keeps_dns_unknown(self):
        result = run(self.url, "version", 5.0, dnslog_host="demo.ceye.io")
        self.assertTrue(result["dns_filter"])
        self.assertTrue(any("CEYE" in note or "DNSLog" in note for note in result["notes"]))


class ExpectTest(ServerMixin):
    def test_expected_class_detected(self):
        result = run(self.url, "expect", 5.0, base_body='{"age":20,"name":"Bob"}')
        self.assertTrue(result["has_expect_class"])
        self.assertTrue(result["expect_not_map"])
        self.assertGreaterEqual(result["confidence"], 0.8)
        json.dumps(result, ensure_ascii=False)

    def test_invalid_base_body(self):
        with self.assertRaises(ValueError):
            run(self.url, "expect", 5.0, base_body="not-json")


class DnsTest(ServerMixin):
    def test_dns_probe_without_ceye(self):
        result = run(self.url, "dns", 5.0, dnslog_host="demo.ceye.io")
        self.assertEqual(result["dnslog_host"], "demo.ceye.io")
        self.assertEqual(len(result["evidence"]), 4)
        self.assertTrue(any("CEYE" in note for note in result["notes"]))

    def test_dns_probe_without_host(self):
        result = run(self.url, "dns", 5.0)
        self.assertEqual(len(result["evidence"]), 1)

    def test_ceye_requires_token(self):
        with self.assertRaises(ValueError):
            run(self.url, "ceye", 5.0)


class ModeValidationTest(ServerMixin):
    def test_unknown_mode(self):
        with self.assertRaises(ValueError):
            run(self.url, "nope", 5.0)


class ConfigFileTest(unittest.TestCase):
    """配置页写入的 config.properties 应能被引擎读取为 CEYE 配置。"""

    def setUp(self):
        self.original_cache = fj_probe._config_cache
        self.original_file = fj_probe.CONFIG_FILE
        self.env_backup = {
            key: os.environ.pop(key)
            for key in ("CEYE_TOKEN", "CEYE_DOMAIN", "CEYE_API")
            if key in os.environ
        }
        self.folder = tempfile.mkdtemp()
        fj_probe.CONFIG_FILE = os.path.join(self.folder, "config.properties")
        fj_probe._config_cache = None

    def tearDown(self):
        fj_probe._config_cache = self.original_cache
        fj_probe.CONFIG_FILE = self.original_file
        os.environ.update(self.env_backup)
        shutil.rmtree(self.folder, ignore_errors=True)

    def test_config_file_parser(self):
        Path(fj_probe.CONFIG_FILE).write_text(
            "# JavaSecExpToolKit\n"
            "! another comment\n"
            "ceye_token=abc123\n"
            "ceye_domain=demo.ceye.io\n",
            encoding="utf-8",
        )
        values = fj_probe._config_file()
        self.assertEqual(values["ceye_token"], "abc123")
        self.assertEqual(values["ceye_domain"], "demo.ceye.io")
        self.assertNotIn("#", values)
        self.assertNotIn("!", values)

    def test_java_properties_escapes_are_restored(self):
        Path(fj_probe.CONFIG_FILE).write_text(
            "ceye_api=http\\://api.ceye.io/v1/records\n"
            "python=C\\:\\\\Python313\\\\python.exe\n"
            "dnslog_host=abc.ceye.io\n",
            encoding="utf-8",
        )
        values = fj_probe._config_file()
        self.assertEqual(values["ceye_api"], "http://api.ceye.io/v1/records")
        self.assertEqual(values["python"], "C:\\Python313\\python.exe")
        self.assertEqual(values["dnslog_host"], "abc.ceye.io")

    def test_long_value_continuation_is_joined(self):
        long_value = "A" * 90
        Path(fj_probe.CONFIG_FILE).write_text(
            "base_body=" + long_value + "\n", encoding="utf-8"
        )
        self.assertEqual(fj_probe._config_file()["base_body"], long_value)

    def test_missing_config_file_is_tolerated(self):
        fj_probe.CONFIG_FILE = os.path.join(self.folder, "missing.properties")
        fj_probe._config_cache = None
        self.assertEqual(fj_probe._config_file(), {})

    def test_config_file_supplies_ceye_token(self):
        Path(fj_probe.CONFIG_FILE).write_text(
            "ceye_token=tok123\nceye_domain=demo.ceye.io\n", encoding="utf-8"
        )
        fj_probe._config_cache = None
        config = fj_probe._ceye_config({"extras": {}})
        self.assertIsNotNone(config)
        self.assertEqual(config["token"], "tok123")
        self.assertEqual(config["domain"], "demo.ceye.io")

    def test_explicit_argument_overrides_config_file(self):
        Path(fj_probe.CONFIG_FILE).write_text("ceye_token=tok123\n", encoding="utf-8")
        fj_probe._config_cache = None
        config = fj_probe._ceye_config({"extras": {"ceye_token": "cli-token"}})
        self.assertEqual(config["token"], "cli-token")


class StageSwitchTest(ServerMixin):
    """界面上的 DNS 探针 / CEYE 确认开关对应引擎的 dns_enabled / ceye_enabled。"""

    def test_dns_and_ceye_disabled_skips_both_stages(self):
        result = run(
            self.url, "detect", 5.0,
            dnslog_host="demo.ceye.io",
            extras={"dns_enabled": False, "ceye_enabled": False},
        )
        self.assertFalse(result["stages"]["dns"])
        self.assertFalse(result["stages"]["ceye"])
        self.assertNotIn("dns", result)
        self.assertTrue(any("均已关闭" in note for note in result["notes"]))
        self.assertIn("已跳过", result["summary"])

    def test_ceye_without_dns_is_skipped_with_reason(self):
        result = run(
            self.url, "detect", 5.0,
            dnslog_host="demo.ceye.io",
            extras={"dns_enabled": False, "ceye_enabled": True},
        )
        self.assertFalse(result["stages"]["dns"])
        self.assertFalse(result["stages"]["ceye"])
        self.assertTrue(any("依赖" in note for note in result["notes"]))

    def test_dns_enabled_without_host_is_skipped_without_error(self):
        result = run(self.url, "detect", 5.0, extras={"dns_enabled": True, "ceye_enabled": True})
        self.assertFalse(result["stages"]["dns"])
        self.assertTrue(any("未填写 DNSLog 主机" in note for note in result["notes"]))

    def test_dns_enabled_ceye_disabled_probes_without_ceye(self):
        result = run(
            self.url, "detect", 5.0,
            dnslog_host="demo.ceye.io",
            extras={"dns_enabled": True, "ceye_enabled": False},
        )
        self.assertTrue(result["stages"]["dns"])
        self.assertFalse(result["stages"]["ceye"])
        self.assertEqual(len(result["dns"]["evidence"]), 4)
        self.assertIn("DNS 探针", result["summary"])

    def test_version_mode_respects_dns_switch(self):
        with_dns = run(
            self.url, "version", 5.0,
            dnslog_host="demo.ceye.io",
            extras={"dns_enabled": True, "ceye_enabled": False},
        )
        without_dns = run(
            self.url, "version", 5.0,
            dnslog_host="demo.ceye.io",
            extras={"dns_enabled": False, "ceye_enabled": False},
        )
        self.assertTrue(with_dns["stages"]["dns"])
        self.assertFalse(with_dns["stages"]["ceye"])
        self.assertFalse(without_dns["stages"]["dns"])

    def test_dns_mode_respects_switch(self):
        skipped = run(self.url, "dns", 5.0, extras={"dns_enabled": False})
        self.assertEqual(skipped["evidence"], [])
        self.assertIn("已跳过", skipped["summary"])

    def test_ceye_mode_respects_switch(self):
        skipped = run(self.url, "ceye", 5.0, extras={"ceye_enabled": False})
        self.assertFalse(skipped["confirmed"])
        self.assertEqual(skipped["count"], 0)
        self.assertIn("已跳过", skipped["summary"])

    def test_stage_flag_parsing(self):
        self.assertTrue(fj_probe._flag({}, "dns_enabled", True))
        self.assertFalse(fj_probe._flag({"dns_enabled": "false"}, "dns_enabled", True))
        self.assertTrue(fj_probe._flag({"dns_enabled": "on"}, "dns_enabled", False))
        self.assertFalse(fj_probe._flag({"dns_enabled": False}, "dns_enabled", True))

    def test_cli_parses_dns_and_ceye_switches(self):
        parser = fj_probe._build_parser()
        default_args = parser.parse_args(["http://127.0.0.1:1/api"])
        self.assertTrue(default_args.dns_enabled)
        self.assertTrue(default_args.ceye_enabled)
        off_args = parser.parse_args(["http://127.0.0.1:1/api", "--no-dns", "--no-ceye"])
        self.assertFalse(off_args.dns_enabled)
        self.assertFalse(off_args.ceye_enabled)
        on_args = parser.parse_args(["http://127.0.0.1:1/api", "--dns", "--ceye"])
        self.assertTrue(on_args.dns_enabled)
        self.assertTrue(on_args.ceye_enabled)


class MethodNotAllowedHandler(BaseHTTPRequestHandler):
    """模拟只接受 GET 的登录页：任何 POST 都回 405，且响应体为空。"""

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        self.rfile.read(length)
        self.send_response(405)
        self.send_header("Allow", "GET, HEAD")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, format, *args):
        return


class TransportBlockTest(unittest.TestCase):
    """传输层被拒绝（405 空响应）时不得当成解析器报错，更不能推出假版本区间。"""

    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), MethodNotAllowedHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = "http://127.0.0.1:{0}/index/fastjson".format(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join(timeout=2)
        cls.server.server_close()

    def test_detect_refuses_false_positive(self):
        result = run(self.url, "detect", 5.0, extras={"dns_enabled": False, "ceye_enabled": False})
        self.assertFalse(result["is_fastjson"])
        self.assertEqual(result["confidence"], 0.0)
        self.assertIn("无法探测", result["summary"])
        self.assertEqual(result["stages"], {"dns": False, "ceye": False})
        json.dumps(result, ensure_ascii=False)

    def test_version_does_not_invent_range(self):
        result = run(self.url, "version", 5.0, extras={"dns_enabled": False, "ceye_enabled": False})
        self.assertIsNone(result["reported_version"])
        self.assertIsNone(result["version_detail"])
        self.assertIsNone(result["version_range"])
        self.assertEqual(result["confidence"], 0.0)
        self.assertIsNone(result["autotype_enabled"])
        self.assertIsNone(result["safemode_enabled"])
        self.assertIn("版本未能收敛", result["summary"])

    def test_expect_refuses_false_positive(self):
        result = run(self.url, "expect", 5.0, base_body='{"age":20,"name":"Bob"}',
                     extras={"dns_enabled": False, "ceye_enabled": False})
        self.assertIsNone(result["has_expect_class"])
        self.assertEqual(result["confidence"], 0.0)
        self.assertIn("未能完成探测", result["summary"])

    def test_transport_block_detection_rules(self):
        self.assertIsNone(fj_probe._transport_block([]))
        blocked = fj_probe._transport_block(
            [("a", 405, "", None), ("b", 405, "", None)]
        )
        self.assertIsNotNone(blocked)
        self.assertIn("405", blocked)
        # 只要观察到解析器特征，即便状态码是 4xx 也不判定为传输层失败
        self.assertIsNone(
            fj_probe._transport_block(
                [("a", 400, "com.alibaba.fastjson.JSONException: syntax error", None)]
            )
        )
        # 混合了连接失败与状态码时不贸然判定，交由上层按既有逻辑解读
        self.assertIsNone(
            fj_probe._transport_block([("a", 405, "", None), ("b", None, "", "URLError")])
        )
        self.assertIsNotNone(
            fj_probe._transport_block([("a", None, "", "URLError: timed out")])
        )


class UnknownResponseTest(unittest.TestCase):
    """没拿到响应的探针必须算「未知」，不能当成「未报错」参与布尔差分。

    根因回归：并发发送（或目标抖动）时个别探针会连接中断。旧实现把它记成
    `errored=False`，于是「基线正常、离线探针不报错」的假象同时成立，
    `_infer_version()` 直接推出 `1.2.70-1.2.80` 这种彻头彻尾的假区间；
    `expect` 模式则给出 `confidence=0.45` 的假结论。
    """

    def test_version_errored_is_unknown_when_no_response(self):
        self.assertIsNone(fj_probe._version_response_errored(None, "", None))

    def test_expect_errored_is_unknown_when_no_response(self):
        self.assertIsNone(fj_probe._expect_errored(None, ""))

    def test_5xx_is_unknown_not_parser_error(self):
        """501/502/503 是网关或方法层面的整体拒绝，与 payload 无关，不能算解析器报错。"""
        self.assertIsNone(fj_probe._version_response_errored(501, "<html>not supported</html>"))
        self.assertIsNone(fj_probe._version_response_errored(503, "Service Unavailable"))
        self.assertIsNone(fj_probe._expect_errored(501, "<html>not supported</html>"))

    def test_5xx_with_parser_marker_still_counts_as_error(self):
        """统一错误页若真的包了解析器异常，仍要算命中，不能因为 5xx 就一律丢弃。"""
        self.assertIs(
            fj_probe._version_response_errored(
                500, "com.alibaba.fastjson.JSONException: autoType is not support"
            ),
            True,
        )

    def test_infer_version_refuses_range_when_any_probe_unknown(self):
        flags = {
            "offline_exception": True,
            "offline_autocloseable": None,
            "offline_class_jdbc": True,
            "offline_jdbc": True,
        }
        inferred = fj_probe._infer_version(flags, None)
        self.assertIsNone(inferred["version_detail"])
        self.assertIsNone(inferred["version_range"])
        self.assertEqual(inferred["confidence"], 0.0)

    def test_infer_autotype_unknown_when_probe_missing(self):
        """一条探针没响应时不能推出 AutoType 开关状态。"""
        self.assertIsNone(fj_probe._infer_autotype({"autotype_class": None, "autotype_random": False}))
        self.assertIsNone(fj_probe._infer_autotype({"autotype_class": True, "autotype_random": None}))

    def test_probe_result_varied_ignores_connection_failures(self):
        """连接失败不该让「同一份响应」看起来有差异，否则换方法逻辑会误报成功。"""
        same_body = {
            "evidence": [
                {"status": 501, "response_excerpt": "<html>same</html>", "error": None},
                {"status": 501, "response_excerpt": "<html>same</html>", "error": None},
                {"status": None, "response_excerpt": "", "error": "ConnectionAbortedError"},
            ]
        }
        self.assertFalse(fj_probe._probe_result_varied(same_body))
        mixed = {
            "evidence": [
                {"status": 200, "response_excerpt": '{"ok":true}', "error": None},
                {"status": 400, "response_excerpt": "syntax error", "error": None},
            ]
        }
        self.assertTrue(fj_probe._probe_result_varied(mixed))


class ProbeConcurrencyTest(unittest.TestCase):
    """探针并发：探测时间不应再随探针数线性增长，且结果必须与原顺序一致。"""

    def test_probe_concurrency_is_bounded(self):
        self.assertGreaterEqual(fj_probe.PROBE_CONCURRENCY, 1)
        self.assertLessEqual(fj_probe.PROBE_CONCURRENCY, 8)

    def test_send_probes_preserves_order(self):
        """并发发送后必须按原下标回填，否则 evidence 与探针表会错位。"""
        probes = [
            ("first", "desc", '{"probe":1}'),
            ("second", "desc", '{"probe":2}'),
            ("third", "desc", '{"probe":3}'),
        ]
        seen = []

        def fake_request(target, payload, timeout, headers, content_type, method):
            # 故意让先提交的探针最慢，验证结果不是按「完成先后」收集的
            delay = 0.15 if "1" in payload else 0.01
            time.sleep(delay)
            seen.append(payload)
            return 200, 1.0, payload, None

        original = fj_probe._request
        fj_probe._request = fake_request
        try:
            results = fj_probe._send_probes(probes, "http://x/", 1.0, {}, "application/json", "POST")
        finally:
            fj_probe._request = original

        self.assertEqual(len(results), 3)
        self.assertEqual([row[2] for row in results], ['{"probe":1}', '{"probe":2}', '{"probe":3}'])

    def test_send_probes_handles_variable_arity(self):
        """探针表既有 3 元组也有 4 元组，payload 都在末位，不能按下标 3 硬取。"""
        probes = [
            ("a", "desc", '{"x":1}'),
            ("b", "cat", "desc", '{"x":2}'),
        ]
        captured = []

        def fake_request(target, payload, timeout, headers, content_type, method):
            captured.append(payload)
            return 200, 1.0, "", None

        original = fj_probe._request
        fj_probe._request = fake_request
        try:
            results = fj_probe._send_probes(probes, "http://x/", 1.0, {}, "application/json", "POST")
        finally:
            fj_probe._request = original

        self.assertEqual(sorted(captured), ['{"x":1}', '{"x":2}'])
        self.assertTrue(all(row[0] == 200 for row in results))

    def test_send_probes_empty(self):
        self.assertEqual(fj_probe._send_probes([], "http://x/", 1.0, {}, "c", "POST"), [])

    def test_send_probes_converts_exception_to_failure(self):
        def boom(*args, **kwargs):
            raise RuntimeError("网络炸了")

        original = fj_probe._request
        fj_probe._request = boom
        try:
            results = fj_probe._send_probes(
                [("a", "desc", "{}")], "http://x/", 1.0, {}, "application/json", "POST"
            )
        finally:
            fj_probe._request = original
        self.assertIsNone(results[0][0])
        self.assertIn("RuntimeError", results[0][3])


class ReportReadabilityTest(unittest.TestCase):
    """回显只给结论：空响应不逐条打印，通用限制合并成一行。"""

    def _detect_result(self):
        return {
            "mode": "detect",
            "target": "http://127.0.0.1:1/api",
            "request_method": "POST",
            "is_fastjson": False,
            "confidence": 0.0,
            "scores": {"fastjson": 0.25},
            "summary": "无法探测：所有探针均返回 HTTP 405，未观察到任何 JSON 解析器特征。请确认 URL。",
            "evidence": [
                {
                    "probe_id": "baseline",
                    "description": "标准 JSON 基线",
                    "status": 405,
                    "elapsed_ms": 1.0,
                    "matched": [],
                    "response_excerpt": "",
                    "error": None,
                },
                {
                    "probe_id": "broken_json",
                    "description": "损坏 JSON",
                    "status": 405,
                    "elapsed_ms": 1.0,
                    "matched": [],
                    "response_excerpt": "",
                    "error": None,
                },
            ],
            "limitations": ["仅限授权目标。"],
            "stages": {"dns": False, "ceye": False},
        }

    def test_empty_response_is_not_repeated_per_probe(self):
        report = fj_probe.format_report(self._detect_result(), "detect")
        self.assertNotIn("（空响应）", report)
        # 每条探针仍要有一行结论（否则使用者无法确认探针真的跑过）
        self.assertIn("标准 JSON", report)
        self.assertIn("残缺 JSON", report)

    def test_failure_excerpt_is_still_shown(self):
        result = self._detect_result()
        result["evidence"][0]["response_excerpt"] = "com.alibaba.fastjson.JSONException: syntax error"
        report = fj_probe.format_report(result, "detect")
        self.assertIn("syntax error", report)

    def test_conclusion_is_split_into_two_segments(self):
        report = fj_probe.format_report(self._detect_result(), "detect")
        conclusion = [line for line in report.splitlines() if line.startswith("探测结论:")][0]
        # 结论行只保留第一个分句，原因与建议另起一行缩进
        self.assertNotIn("请确认 URL", conclusion)
        self.assertIn("请确认 URL", report)

    def test_notes_duplicated_in_summary_are_not_repeated(self):
        result = self._detect_result()
        result["notes"] = [result["summary"][:20] + "（重复）"]
        report = fj_probe.format_report(result, "detect")
        self.assertNotIn("提示:", report)

    def test_notes_with_different_label_but_same_body_are_not_repeated(self):
        """notes 与 summary 挂着不同标签时，同一条原因同样不能打印两次。

        真实场景：notes 写成「探测未生效：所有探针均返回 HTTP 405…」，
        summary 写成「无法探测：所有探针均返回 HTTP 405…」。只按前 12 字比对会把
        标签算进去而漏判，结果区就留下一条与结论逐字相同的提示。
        """
        result = self._detect_result()
        body = result["summary"].split("：", 1)[1][:20]
        result["notes"] = ["探测未生效：" + body]
        report = fj_probe.format_report(result, "detect")
        self.assertNotIn("提示:", report)

    def test_strip_label_keeps_body_without_label(self):
        self.assertEqual(fj_probe._strip_label("无法探测：所有探针均返回 405"), "所有探针均返回 405")
        self.assertEqual(fj_probe._strip_label("没有冒号的提示"), "没有冒号的提示")
        # 标签过长说明那是正文里的冒号，不能当成标签剥掉
        long_prefix = "这是一段很长的前缀文字：后面才是正文"
        self.assertEqual(fj_probe._strip_label(long_prefix), long_prefix)

    def test_common_limitations_are_one_line(self):
        result = self._detect_result()
        result["limitations"] = [
            "结果是远程响应指纹，不等同于漏洞确认。",
            "仅适用于已获授权的测试目标。",
        ]
        report = fj_probe.format_report(result, "detect")
        limitation_lines = [
            line for line in report.splitlines()[report.splitlines().index("已知限制:") + 1:]
        ]
        self.assertEqual(len([line for line in limitation_lines if line.strip().startswith("-")]), 2)


class FormatReportTest(unittest.TestCase):
    """文本报告与请求头参数的 CLI 契约。"""

    def test_cli_parses_format_and_headers(self):
        parser = fj_probe._build_parser()
        default_args = parser.parse_args(["http://127.0.0.1:1/api"])
        self.assertEqual(default_args.format, "json")
        text_args = parser.parse_args(
            ["http://127.0.0.1:1/api", "--format", "text",
             "--headers", '{"Cookie":"JWT=abc"}']
        )
        self.assertEqual(text_args.format, "text")
        self.assertEqual(json.loads(text_args.headers)["Cookie"], "JWT=abc")

    def test_format_report_sections(self):
        detect = {
            "mode": "detect",
            "target": "http://127.0.0.1:1/api",
            "is_fastjson": True,
            "confidence": 0.806,
            "scores": {"fastjson": 7.25},
            "summary": "响应包含 Fastjson 特征",
            "evidence": [],
            "limitations": [],
        }
        report = fj_probe.format_report(detect, "detect")
        self.assertIn("===== Fastjson 识别 =====", report)
        self.assertIn("目标: http://127.0.0.1:1/api", report)
        self.assertIn("是否 Fastjson: 是", report)
        self.assertNotIn("\ufffd", report)

    def test_format_report_renders_error_and_version(self):
        report = fj_probe.format_report({"error": "ValueError: 缺少 Token"}, "ceye")
        self.assertIn("探测失败", report)
        version = {
            "mode": "version",
            "target": "http://127.0.0.1:1/api",
            "reported_version": None,
            "version_detail": None,
            "version_range": None,
            "confidence": 0.0,
            "autotype_enabled": None,
            "safemode_enabled": None,
            "summary": "版本未能收敛",
            "evidence": [],
            "limitations": [],
            "stages": {"dns": False, "ceye": False},
        }
        text = fj_probe.format_report(version, "version")
        self.assertIn("===== 版本识别 =====", text)
        self.assertIn("回显版本: 未回显", text)
        self.assertIn("布尔探针区间: 未能收敛", text)


class PostOnlyJsonHandler(BaseHTTPRequestHandler):
    """只接受 POST 的 JSON 端点：其他方法一律 405 空响应。

    用于验证「探针被 HTTP 层整体拒绝时自动换方法重试」。
    """

    def _body(self):
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length).decode("utf-8", errors="replace")

    def _reject(self):
        self.send_response(405)
        self.send_header("Allow", "POST")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        self._reject()

    def do_PUT(self):
        self._reject()

    def do_PATCH(self):
        self._reject()

    def do_POST(self):
        body = self._body()
        if body.strip() in ('{"@type":', '{"a":'):
            return self._json("com.alibaba.fastjson.JSONException: syntax error, expect {", 400)
        if "Test.TestException" in body:
            return self._json("com.alibaba.fastjson.JSONException: autoType is not support", 400)
        if "$ref" in body:
            return self._json('{"ext":"blue","name":"blue"}')
        return self._json('{"ok":true}')

    def _json(self, payload, status=200):
        encoded = payload.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format, *args):
        return


class MethodNotAllowedHandlerRedirecting(BaseHTTPRequestHandler):
    """对任何方法都返回同一份静态登录页：换方法也不该被判为探测成功。"""

    PAGE = "<!DOCTYPE html><html><body>请先登录</body></html>"

    def _reply(self):
        payload = self.PAGE.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html;charset=UTF-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self._reply()

    def do_POST(self):
        self._reply()

    def do_PUT(self):
        self._reply()

    def do_PATCH(self):
        self._reply()

    def log_message(self, format, *args):
        return


class CaptureHandler(BaseHTTPRequestHandler):
    """模拟需要 Cookie 的 JSON 接口，并主动下发 Set-Cookie。"""

    def _body(self):
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length).decode("utf-8", errors="replace")

    def do_POST(self):
        body = self._body()
        if not self.headers.get("Cookie"):
            self.send_response(401)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        payload = ("com.alibaba.fastjson.JSONException: syntax error, pos 1, json : " + body).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain;charset=UTF-8")
        self.send_header("Set-Cookie", "SESSION=abc123; Path=/; HttpOnly")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format, *args):
        return


class CaptureModeTest(unittest.TestCase):
    """独立抓包模式：保留原始响应、记录 Set-Cookie、输出格式转换。"""

    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), CaptureHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = "http://127.0.0.1:{0}/vul".format(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join(timeout=2)
        cls.server.server_close()

    def test_capture_without_cookie_reports_401(self):
        result = run("", "capture", 5.0, None, extras={
            "capture_url": self.url, "method": "POST", "body": "{\"age\":20}",
            "content_type": "application/json",
        })
        self.assertEqual(result["status"], 401)
        self.assertIsNone(result["error"])
        self.assertTrue(any("Cookie" in note for note in result["notes"]))
        json.dumps(result, ensure_ascii=False)

    def test_capture_records_request_and_response(self):
        result = run("", "capture", 5.0, None, extras={
            "capture_url": self.url, "method": "POST", "body": "{\"age\":20}",
            "content_type": "application/json",
            "request_headers": {"Cookie": "JWT_TOKEN=tok; JSESSIONID=sid"},
            "convert_targets": ["cookie-json", "curl"],
        })
        self.assertEqual(result["status"], 200)
        self.assertIn("fastjson", result["body"].lower())
        self.assertEqual(result["request_cookies"]["JWT_TOKEN"], "tok")
        self.assertEqual(result["response_cookies"]["SESSION"], "abc123")
        self.assertIn("content-type", {key.lower() for key in result["response_headers"]})
        self.assertEqual(sorted(result["conversions"]), ["cookie-json", "curl"])
        self.assertIn("JWT_TOKEN", result["conversions"]["cookie-json"])
        report = fj_probe.format_report(result, "capture")
        self.assertIn("===== HTTP 抓包 =====", report)
        self.assertIn("格式转换:", report)
        self.assertNotIn("\ufffd", report)

    def test_capture_keeps_raw_30x_response(self):
        result = run("", "capture", 5.0, None, extras={
            "capture_url": "http://127.0.0.1:{0}/missing".format(self.server.server_port),
            "method": "GET",
        })
        self.assertIsNotNone(result["status"])


class ProbeMethodTest(unittest.TestCase):
    """探测请求方法：默认 POST、可切换、被 405 拒绝时自动换方法，且不把静态页当成功。"""

    @classmethod
    def setUpClass(cls):
        cls.post_only = HTTPServer(("127.0.0.1", 0), PostOnlyJsonHandler)
        cls.post_thread = threading.Thread(target=cls.post_only.serve_forever, daemon=True)
        cls.post_thread.start()
        cls.post_url = "http://127.0.0.1:{0}/json".format(cls.post_only.server_port)

        cls.reject_all = HTTPServer(("127.0.0.1", 0), MethodNotAllowedHandlerRedirecting)
        cls.reject_thread = threading.Thread(target=cls.reject_all.serve_forever, daemon=True)
        cls.reject_thread.start()
        cls.reject_url = "http://127.0.0.1:{0}/index/fastjson".format(cls.reject_all.server_port)

    @classmethod
    def tearDownClass(cls):
        for server, thread in ((cls.post_only, cls.post_thread), (cls.reject_all, cls.reject_thread)):
            server.shutdown()
            thread.join(timeout=2)
            server.server_close()

    def test_default_method_is_post(self):
        result = run(self.post_url, "detect", 5.0)
        self.assertEqual(result["request_method"], "POST")
        self.assertTrue(result["is_fastjson"])

    def test_probe_method_can_be_switched(self):
        """指定 GET 时先按 GET 探测；被 405 整体拒绝后自动改用 POST 并留下痕迹。"""
        result = run(self.post_url, "detect", 5.0, extras={"probe_method": "GET"})
        self.assertEqual(result["request_method"], "POST")
        self.assertTrue(any("自动改用 POST" in note for note in result.get("notes") or []))
        self.assertTrue(result["is_fastjson"])

    def test_invalid_method_falls_back_to_post(self):
        result = run(self.post_url, "detect", 5.0, extras={"probe_method": "BREW"})
        self.assertEqual(result["request_method"], "POST")

    def test_text_report_shows_method(self):
        result = run(self.post_url, "detect", 5.0)
        report = fj_probe.format_report(result, "detect")
        self.assertIn("探测方法: POST", report)

    def test_get_payload_is_url_encoded(self):
        """GET 探测要把 JSON 转义后放进查询串，否则请求行含空格会直接抛 InvalidURL。"""
        result = run(self.post_url, "detect", 5.0, extras={"probe_method": "GET"})
        self.assertNotIn("InvalidURL", result.get("summary", ""))
        for item in result["evidence"]:
            self.assertIsNone(item.get("error"))

    def test_static_page_is_not_treated_as_success(self):
        """所有方法都返回同一登录页时（HTTP 200 也一样），不能当成探测成功。"""
        result = run(self.reject_url, "detect", 5.0)
        self.assertFalse(result["is_fastjson"])
        self.assertEqual(result["confidence"], 0.0)
        self.assertIn("未到达 JSON 解析器", result["summary"])


class ConvertModeTest(unittest.TestCase):
    """报文转换：解析粘贴的原始请求，导出 Cookie 与其他格式。"""

    PASTED = (
        "POST /api/json HTTP/1.1\r\n"
        "Host: 127.0.0.1:8080\r\n"
        "Content-Type: application/json\r\n"
        "Cookie: JWT_TOKEN=abc.def; JSESSIONID=xyz\r\n"
        "\r\n"
        '{"age":20}'
    )

    def test_parse_pasted_request(self):
        parsed = fj_probe.parse_pasted_request(self.PASTED)
        self.assertEqual(parsed["method"], "POST")
        self.assertEqual(parsed["url"], "/api/json")
        self.assertEqual(parsed["headers"]["Cookie"], "JWT_TOKEN=abc.def; JSESSIONID=xyz")
        self.assertEqual(parsed["body"], '{"age":20}')

    def test_convert_exports_cookie_formats(self):
        result = run("", "convert", 5.0, None, extras={
            "pasted_request": self.PASTED,
            "convert_targets": ["cookie-json", "cookie-header", "cookie-netscape", "curl", "raw", "json"],
        })
        self.assertEqual(result["method"], "POST")
        self.assertEqual(
            json.loads(result["conversions"]["cookie-json"]),
            {"JWT_TOKEN": "abc.def", "JSESSIONID": "xyz"},
        )
        self.assertEqual(result["conversions"]["cookie-header"], "JWT_TOKEN=abc.def; JSESSIONID=xyz")
        self.assertIn("JWT_TOKEN", result["conversions"]["cookie-netscape"])
        self.assertIn("curl", result["conversions"]["curl"])
        self.assertIn("http://127.0.0.1:8080/api/json", result["conversions"]["curl"])
        self.assertIn("POST /api/json HTTP/1.1", result["conversions"]["raw"])
        self.assertEqual(json.loads(result["conversions"]["json"])["url"], "http://127.0.0.1:8080/api/json")

    def test_convert_without_target_is_offline(self):
        result = run("", "convert", 5.0, None, extras={
            "pasted_request": self.PASTED, "convert_targets": ["cookie-header"],
        })
        self.assertEqual(result["conversions"]["cookie-header"], "JWT_TOKEN=abc.def; JSESSIONID=xyz")

    def test_cli_parses_capture_arguments(self):
        parser = fj_probe._build_parser()
        args = parser.parse_args([
            "--mode", "capture", "--method", "PUT", "--capture-url", "http://127.0.0.1:1/api",
            "--body", '{"a":1}', "--convert-targets", "curl,cookie-json",
        ])
        self.assertEqual(args.method, "PUT")
        self.assertEqual(args.capture_url, "http://127.0.0.1:1/api")
        self.assertEqual(args.body, '{"a":1}')
        self.assertTrue("capture" in fj_probe.MODE_LABELS)
        self.assertTrue("convert" in fj_probe.MODE_LABELS)


class LoginGatedHandler(BaseHTTPRequestHandler):
    """模拟带登录拦截的靶场：未登录请求被转发到登录视图。

    复刻 Hello-Java-Sec 的 LoginHandlerInterceptor 行为：
    POST 落到只支持 GET 的登录视图 → 405 空响应；GET 返回登录页 HTML。
    登录页的 password 表单在正文深处（超过 evidence 的 500 字截断），
    必须用完整响应体才能识别，否则只会把 405 读成「该路径只接受 GET」。
    """

    PAGE = (
        "<!DOCTYPE html><html><head><title>登录</title></head><body>"
        "<form action=\"/user/login\" method=\"post\">"
        "<input name=\"username\" /><input name=\"password\" />"
        "<img src=\"/captcha\" />"
        + "<div style=\"display:none\">" + ("<!-- filler -->" * 60) + "</div>"
        + "请先登录</form></body></html>"
    )

    def _login_page(self):
        payload = self.PAGE.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html;charset=UTF-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _reject(self):
        self.send_response(405)
        self.send_header("Allow", "GET, HEAD")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        self._login_page()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        self.rfile.read(length)
        self._reject()

    def do_PUT(self):
        self._reject()

    def do_PATCH(self):
        self._reject()

    def log_message(self, format, *args):
        return


class SessionAuthHandler(BaseHTTPRequestHandler):
    """放宽版：带上会话 Cookie 后才是真正的 JSON 端点，未携带会话时返回登录页。"""

    def _body(self):
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length).decode("utf-8", errors="replace")

    def _login_page(self):
        payload = LoginGatedHandler.PAGE.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html;charset=UTF-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self._login_page()

    def do_POST(self):
        body = self._body()
        if "JWT_TOKEN=" not in (self.headers.get("Cookie") or ""):
            self.send_response(405)
            self.send_header("Allow", "GET, HEAD")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if body.strip() in ('{"@type":', '{"a":'):
            return self._json("com.alibaba.fastjson.JSONException: syntax error, expect {", 400)
        if "$ref" in body:
            return self._json('{"ext":"blue","name":"blue"}')
        return self._json('{"ok":true}')

    def _json(self, payload, status=200):
        encoded = payload.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format, *args):
        return


class SessionCookieTest(unittest.TestCase):
    """登录拦截识别与会话 Cookie 注入。"""

    @classmethod
    def setUpClass(cls):
        cls.gated = HTTPServer(("127.0.0.1", 0), LoginGatedHandler)
        cls.gated_thread = threading.Thread(target=cls.gated.serve_forever, daemon=True)
        cls.gated_thread.start()
        cls.gated_url = "http://127.0.0.1:{0}/vulnapi/Fastjson/vul".format(cls.gated.server_port)

        cls.auth = HTTPServer(("127.0.0.1", 0), SessionAuthHandler)
        cls.auth_thread = threading.Thread(target=cls.auth.serve_forever, daemon=True)
        cls.auth_thread.start()
        cls.auth_url = "http://127.0.0.1:{0}/vulnapi/Fastjson/vul".format(cls.auth.server_port)

    @classmethod
    def tearDownClass(cls):
        for server, thread in ((cls.gated, cls.gated_thread), (cls.auth, cls.auth_thread)):
            server.shutdown()
            thread.join(timeout=2)
            server.server_close()

    def test_detect_reports_login_gate_instead_of_get_only(self):
        result = run(self.gated_url, "detect", 5.0,
                     extras={"dns_enabled": False, "ceye_enabled": False})
        self.assertTrue(result.get("login_gate"))
        self.assertIn("登录", result["summary"])
        # 不能再给出「该路径只接受 GET」这类误导性结论
        self.assertNotIn("只接受 GET）重试", result["summary"])
        self.assertEqual(result["confidence"], 0.0)

    def test_version_and_expect_report_login_gate(self):
        for mode, keyword in (("version", "版本未能收敛"), ("expect", "未能完成探测")):
            result = run(self.gated_url, mode, 5.0, extras={"dns_enabled": False, "ceye_enabled": False})
            self.assertTrue(result.get("login_gate"), mode)
            self.assertIn(keyword, result["summary"])
            self.assertEqual(result["confidence"], 0.0)

    def test_session_cookie_unlocks_real_endpoint(self):
        blocked = run(self.auth_url, "detect", 5.0,
                      extras={"dns_enabled": False, "ceye_enabled": False})
        self.assertTrue(blocked.get("login_gate"))
        self.assertFalse(blocked["is_fastjson"])

        unlocked = run(self.auth_url, "detect", 5.0, extras={
            "dns_enabled": False, "ceye_enabled": False, "session_cookie": "JWT_TOKEN=tok123",
        })
        self.assertFalse(unlocked.get("login_gate"))
        self.assertTrue(unlocked["is_fastjson"])

    def test_session_cookie_merges_with_existing_cookie_header(self):
        merged, added = fj_probe._merge_session_cookie({"Cookie": "JWT_TOKEN=a"}, "JSESSIONID=b")
        self.assertTrue(added)
        self.assertIn("JWT_TOKEN=a", merged["Cookie"])
        self.assertIn("JSESSIONID=b", merged["Cookie"])

    def test_existing_cookie_wins_over_session_cookie(self):
        merged, added = fj_probe._merge_session_cookie({"Cookie": "JWT_TOKEN=fresh"}, "JWT_TOKEN=stale")
        self.assertFalse(added)
        self.assertEqual(merged["Cookie"], "JWT_TOKEN=fresh")

    def test_session_cookie_accepts_full_header_line(self):
        self.assertEqual(fj_probe._clean_session_cookie("Cookie: a=1; b=2"), "a=1; b=2")
        self.assertEqual(fj_probe._clean_session_cookie("  a=1  "), "a=1")

    def test_cli_parses_session_cookie(self):
        parser = fj_probe._build_parser()
        args = parser.parse_args(["http://127.0.0.1:1/api", "--session-cookie", "JWT_TOKEN=x"])
        self.assertEqual(args.session_cookie, "JWT_TOKEN=x")
if __name__ == "__main__":
    unittest.main()
