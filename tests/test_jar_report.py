"""调用链数据库查询测试：只用标准库造一个模拟库，逐类查询验证结论。

不依赖也不下载 jar-analyzer-engine：本模块验证的是**我们自己的查询逻辑**
（表结构映射、sink 匹配、失败路径），外部引擎的输出格式由它自己的文档约定，
用模拟库固定下来即可，这样测试可以离线、秒级、结果可复现。
"""

import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))
import jar_report  # noqa: E402


SCHEMA = """
CREATE TABLE jar_table (jid INTEGER PRIMARY KEY, jar_name TEXT, jar_abs_path TEXT);
CREATE TABLE class_table (cid INTEGER PRIMARY KEY, jar_id INT, jar_name TEXT, version INT,
                          access INT, class_name TEXT, super_class_name TEXT, is_interface INT);
CREATE TABLE method_table (method_id INTEGER PRIMARY KEY, method_name TEXT, method_desc TEXT,
                           is_static INT, class_name TEXT, access INT, line_number INT, jar_id INT);
CREATE TABLE method_call_table (mc_id INTEGER PRIMARY KEY, caller_method_name TEXT,
                                caller_class_name TEXT, caller_method_desc TEXT, caller_jar_id INT,
                                callee_method_name TEXT, callee_method_desc TEXT,
                                callee_class_name TEXT, callee_jar_id INT, op_code INT);
CREATE TABLE string_table (sid INTEGER PRIMARY KEY, value TEXT, access INT, method_desc TEXT,
                           method_name TEXT, class_name TEXT, jar_name TEXT, jar_id INT);
CREATE TABLE spring_method_table (sm_id INTEGER PRIMARY KEY, class_name TEXT, method_name TEXT,
                                  method_desc TEXT, restful_type TEXT, path TEXT, jar_id INT);
CREATE TABLE java_web_table (jw_id INTEGER PRIMARY KEY, type_name TEXT, class_name TEXT, jar_id INT);
"""


def build(database, calls=(), strings=(), quick=False):
    """造一个模拟数据库；quick=True 时省掉 string_table，模拟快速模式。"""
    connection = sqlite3.connect(database)
    script = SCHEMA
    if quick:
        script = script.replace(
            "CREATE TABLE string_table (sid INTEGER PRIMARY KEY, value TEXT, access INT, "
            "method_desc TEXT,\n                           method_name TEXT, class_name TEXT, "
            "jar_name TEXT, jar_id INT);", "")
    connection.executescript(script)
    connection.execute("INSERT INTO jar_table VALUES (1,'app.jar','/opt/app.jar')")
    connection.execute(
        "INSERT INTO spring_method_table VALUES (1,'com/example/Demo','handle','()V','POST','/api/demo',1)")
    connection.execute("INSERT INTO java_web_table VALUES (1,'Filter','com/example/AuthFilter',1)")
    connection.executemany(
        "INSERT INTO method_call_table (caller_method_name,caller_class_name,caller_method_desc,"
        "caller_jar_id,callee_method_name,callee_method_desc,callee_class_name,callee_jar_id,op_code)"
        " VALUES (?,?,?,1,?,?,?,0,0)", calls)
    if not quick:
        # sid 交给自增，只绑定 value 一列：占位符个数必须与传入元组一致
        connection.executemany(
            "INSERT INTO string_table (value, access, method_desc, method_name, class_name,"
            " jar_name, jar_id) VALUES (?,1,'()V','init','com/example/Demo','app.jar',1)", strings)
    connection.commit()
    connection.close()


class CaptureOutputTest(unittest.TestCase):
    """把 stdout 收进字符串，便于断言输出内容。"""

    def run_query(self, database, query, **kwargs):
        import io as _io
        import contextlib
        buffer = _io.StringIO()
        argv = ["-db", str(database), "-q", query]
        for key, value in kwargs.items():
            argv.extend(["--" + key, str(value)])
        with contextlib.redirect_stdout(buffer):
            code = jar_report.main(argv)
        return code, buffer.getvalue()


class QueryTest(CaptureOutputTest):
    """各查询的输出必须含关键结论，且不得因缺表而崩。"""

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="jar-report-")
        self.database = os.path.join(self.dir, "jar-analyzer.db")
        build(self.database, calls=[
            ("handle", "com/example/Demo", "()V", "exec",
             "(Ljava/lang/String;)Ljava/lang/Process;", "java/lang/Runtime"),
            ("handle", "com/example/Demo", "()V", "parseObject",
             "(Ljava/lang/String;)Lcom/alibaba/fastjson/JSONObject;", "com/alibaba/fastjson/JSON"),
            ("safe", "com/example/Other", "()V", "trim", "()Ljava/lang/String;", "java/lang/String"),
        ], strings=[
            ("jdbc:mysql://10.0.0.5:3306/db?password=P@ssw0rd",),
            ("/admin/download",),
        ])

    def test_summary_counts(self):
        code, out = self.run_query(self.database, "summary")
        self.assertEqual(0, code)
        self.assertIn("方法调用边    : 3", out)
        self.assertIn("Spring 路由   : 1", out)
        self.assertIn("JavaWeb 组件  : 1", out)

    def test_entries_lists_route_and_component(self):
        code, out = self.run_query(self.database, "entries")
        self.assertEqual(0, code)
        self.assertIn("/api/demo", out)
        self.assertIn("com/example/Demo", out)
        self.assertIn("AuthFilter", out)

    def test_sinks_reports_only_real_hits(self):
        """只报命中内置清单的调用：普通方法（String.trim）不得出现。"""
        code, out = self.run_query(self.database, "sinks")
        self.assertEqual(0, code)
        self.assertIn("Runtime.exec", out)
        self.assertIn("parseObject", out)
        self.assertNotIn("trim", out)

    def test_sinks_classified_by_category(self):
        code, out = self.run_query(self.database, "sinks")
        self.assertIn("[命令执行]", out)
        self.assertIn("[反序列化]", out)

    def test_strings_filters_by_keyword(self):
        code, out = self.run_query(self.database, "strings", k="password")
        self.assertEqual(0, code)
        self.assertIn("P@ssw0rd", out)
        self.assertNotIn("/admin/download", out)

    def test_components_lists_jar(self):
        code, out = self.run_query(self.database, "components")
        self.assertEqual(0, code)
        self.assertIn("app.jar", out)


class MissingTableTest(CaptureOutputTest):
    """缺表时给可读提示，而不是抛异常或静默给 0。"""

    def test_strings_without_table(self):
        directory = tempfile.mkdtemp(prefix="jar-report-quick-")
        database = os.path.join(directory, "jar-analyzer.db")
        build(database, quick=True)
        code, out = self.run_query(database, "strings")
        self.assertEqual(0, code)
        self.assertIn("没有 string_table", out)
        self.assertIn("快速模式", out)

    def test_summary_without_optional_tables(self):
        """总览在缺表时也应给出 0，而不是崩掉。"""
        directory = tempfile.mkdtemp(prefix="jar-report-min-")
        database = os.path.join(directory, "jar-analyzer.db")
        connection = sqlite3.connect(database)
        connection.executescript(
            "CREATE TABLE jar_table (jid INTEGER PRIMARY KEY, jar_name TEXT, jar_abs_path TEXT);"
            "CREATE TABLE class_table (cid INTEGER PRIMARY KEY, class_name TEXT);"
            "CREATE TABLE method_table (method_id INTEGER PRIMARY KEY, method_name TEXT);"
            "CREATE TABLE method_call_table (mc_id INTEGER PRIMARY KEY, caller_class_name TEXT,"
            " callee_class_name TEXT, callee_method_name TEXT);")
        connection.commit()
        connection.close()
        code, out = self.run_query(database, "summary")
        self.assertEqual(0, code)
        self.assertIn("字符串常量    : 0", out)


class FailurePathTest(CaptureOutputTest):
    """失败路径必须可读：数据库不存在时不能抛栈。"""

    def test_missing_database(self):
        code, out = self.run_query(os.path.join(tempfile.mkdtemp(), "nope.db"), "summary")
        self.assertEqual(2, code)
        self.assertIn("无法打开数据库", out)

    def test_readonly_connection_rejects_write(self):
        """连接必须是只读的：拼错 SQL 也不能改坏使用者的分析库。"""
        directory = tempfile.mkdtemp(prefix="jar-report-ro-")
        database = os.path.join(directory, "jar-analyzer.db")
        build(database)
        connection = jar_report.connect(database)
        try:
            with self.assertRaises(sqlite3.Error):
                connection.execute("DELETE FROM jar_table")
        finally:
            connection.close()


class SinkRuleTest(unittest.TestCase):
    """sink 清单自身的机械约束：否则查询会静默漏报。"""

    def test_sink_entries_are_wellformed(self):
        for category, class_name, method_name, note in jar_report.SINKS:
            self.assertTrue(category, "sink 分类不能为空")
            self.assertIn("/", class_name, "类名应使用 JVM 内部格式: %s" % class_name)
            self.assertTrue(method_name, "方法名不能为空")
            self.assertTrue(note, "每个 sink 都要有一句说明: %s" % class_name)

    def test_sink_conditions_count_matches(self):
        where, params = jar_report.sink_conditions()
        self.assertEqual(len(jar_report.SINKS) * 2, len(params))
        self.assertEqual(len(jar_report.SINKS) - 1, where.count(" OR "))

    def test_queries_cover_every_declared_query(self):
        """Java 侧声明的查询标识与 Python 侧实现必须一一对应。"""
        expected = {"summary", "entries", "sinks", "strings", "components"}
        self.assertEqual(expected, set(jar_report.QUERIES.keys()))


if __name__ == "__main__":
    unittest.main()
