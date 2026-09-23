"""真实后端端到端验证：**必须真的跑起来，不能跳过还报通过**。

astra 的评审把这一条列为 P0：此前所有验证都止于「命令拼装 + 模拟数据库」，
没有一次是用真实后端走完「建库 → 表结构识别 → 查询 → 结论」的。
模拟库测试能证明我们的查询逻辑自洽，却完全不能证明参数拼对了、
数据库落在预期位置、表结构与预期一致——而这些恰恰是最容易出错的地方。

因此本模块的取舍与其它测试相反：

* 后端在场 -> 真的建库、真的查询，断言表结构与结论；
* 后端缺席 -> **跳过并明确写出「未配置，未验证」**，绝不写成通过。
  测试里的「跳过」在报告里会显示为 skipped，不会混进 passed，
  这样「端到端是否验证过」这件事才不会被静默掩盖。

后端位置按与主程序相同的顺序解析：环境变量 JSETK_JAR_ANALYZER_HOME
→ 仓库同级目录下的常见安装位置。只跑本地样本，不触碰任何网络目标。
"""

import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))
import jar_report  # noqa: E402
import jar_signatures  # noqa: E402

ENV_HOME = "JSETK_JAR_ANALYZER_HOME"
# 显式指定运行后端用的 java：开发机的 JAVA_HOME 可能是 JDK 8，而端到端需要 17+
ENV_JAVA = "JSETK_TEST_JAVA"
TIMEOUT_SECONDS = 300
REQUIRED_TABLES = ("jar_table", "class_table", "method_table", "method_call_table")


def backend_jar():
    """找一个可用的后端主 jar；找不到返回 None。"""
    candidates = []
    configured = os.environ.get(ENV_HOME, "").strip()
    if configured:
        candidates.extend(search_under(Path(configured)))
    # 约定位置：与仓库同级的 jaranalyzer 目录，以及仓库内的 tools 目录
    for base in (ROOT.parent, ROOT, ROOT.parent / "AuditTool"):
        candidates.extend(search_under(base))
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return None


def search_under(base, depth=3):
    """在 base 下按约定深度找 jar-analyzer 主 jar（发行包的 lib 目录）。"""
    found = []
    if not base.exists():
        return found
    # 发行包形态：<home>/lib/jar-analyzer-x.y.jar
    for path in sorted(glob.glob(str(base / "*" / "lib" / "jar-analyzer-*.jar"))):
        found.append(Path(path))
    for path in sorted(glob.glob(str(base / "lib" / "jar-analyzer-*.jar"))):
        found.append(Path(path))
    for path in sorted(glob.glob(str(base / "jar-analyzer-*.jar"))):
        found.append(Path(path))
    if depth > 1:
        for child in sorted(base.glob("*")):
            if child.is_dir() and not child.name.startswith("."):
                found.extend(search_under(child, depth - 1))
    # 有 engine 字样的不是发行包主 jar，排到最后；
    # 同档次内按 jar 名倒序，使 6.x 排在 5.x 之前——版本越新越可能是当前在用的那份，
    # 而且端到端会真的起这个后端，选错版本会让验证结论指向另一个引擎
    found.sort(key=lambda item: ("engine" in item.name, _negate(item.name)))
    return found


def _negate(name):
    """按名字倒序的排序键：Python 排序是升序，这里把每字节取反实现降序。"""
    return tuple(-ord(char) for char in name)


BACKEND_MIN_JAVA = 17


def bundled_java(jar):
    """发行包自带的 JRE：优先用它，避免受开发机上 JAVA_HOME 的版本影响。

    jar 在 <home>/lib/ 下，自带运行期在 <home>/jre/ 下。
    """
    if jar is None:
        return None
    exe = "java.exe" if os.name == "nt" else "java"
    for home in (jar.parent.parent, jar.parent):
        candidate = home / "jre" / "bin" / exe
        if candidate.exists():
            return str(candidate)
    return None


def java_version_of(executable):
    """取 java 的主版本号；拿不到返回 None。"""
    if executable is None:
        return None
    try:
        result = subprocess.run([executable, "-version"], capture_output=True, text=True,
                                timeout=60, errors="replace")
    except (OSError, subprocess.SubprocessError):
        return None
    text = (result.stderr or "") + (result.stdout or "")
    match = re.search(r'version "(\d+)(?:\.(\d+))?', text)
    if not match:
        return None
    major = int(match.group(1))
    # JDK 8 及以前的版本号形如 1.8.0_502：首段恒为 1，真实主版本在第二段。
    # 不处理这一条会把 JDK 8 判成 1，于是「找不到能运行后端的 Java」，
    # 而真实原因是版本不够——两种结论的排查方向完全不同。
    if major == 1 and match.group(2) is not None:
        return int(match.group(2))
    return major


def sibling_jdks(java_home):
    """JAVA_HOME 同级目录里的其它 JDK。

    开发机的 JAVA_HOME 往往指向编译用的 JDK 8，而同一父目录下通常还装着 17/21。
    这一条只是**候选**：取到的每个都还要过版本校验，因此不会把低版本误当成可用。
    排序固定（名字倒序），保证同一台机器两次跑选到同一个。
    """
    if not java_home:
        return []
    parent = Path(java_home).parent
    if not parent.is_dir():
        return []
    exe = "java.exe" if os.name == "nt" else "java"
    found = []
    for child in sorted(parent.glob("*jdk*"), reverse=True):
        candidate = child / "bin" / exe
        if candidate.is_file():
            found.append(str(candidate))
    return found


def java_executable():
    """能运行后端的 java 可执行文件；都不满足时返回 None。

    顺序有讲究：**先用发行包自带的 JRE**，再看 JAVA_HOME 与 PATH。
    开发机上的 JAVA_HOME 很可能是 JDK 8（本仓库的编译目标是 release 8），
    而 jar-analyzer 需要 17+；按这个顺序取，测试结果就不会随开发机的
    JAVA_HOME 变化而时好时坏。
    """
    # 显式指定的运行期最优先：端到端要真的起后端，留一个明确的开关便于排查版本问题
    explicit = os.environ.get(ENV_JAVA, "").strip()
    if explicit and Path(explicit).exists():
        return explicit

    # 其次是发行包自带的运行期——这是主程序（ToolkitLocator）的选择：
    # 端到端要验证的是「使用者实际会跑起来的那条路径」，因此这里必须与主程序一致。
    # 自带运行期不做版本门槛：它由发行包锁定，主程序也正是这么用的。
    bundled = bundled_java(backend_jar())
    if bundled:
        return bundled

    # 最后才用系统上的 java，此时要过版本门槛（后端主类是 17+ 编译的）
    exe = "java.exe" if os.name == "nt" else "java"
    home = os.environ.get("JAVA_HOME", "").strip()
    candidates = []
    if home:
        candidates.append(str(Path(home) / "bin" / exe))
    candidates.extend(sibling_jdks(home))
    candidates.append(shutil.which("java"))
    for candidate in candidates:
        if not candidate or not Path(candidate).exists():
            continue
        version = java_version_of(candidate)
        if version is not None and version >= BACKEND_MIN_JAVA:
            return candidate
    return None


def javac_executable():
    home = os.environ.get("JAVA_HOME", "").strip()
    if home:
        candidate = Path(home) / "bin" / ("javac.exe" if os.name == "nt" else "javac")
        if candidate.exists():
            return str(candidate)
    return shutil.which("javac")


def build_sample_jar(directory):
    """造一个带真实字节码与 Spring 路由标注的样本 jar。

    用真实编译产物而不是空壳 zip：只有真 class 才能让后端构建出调用图，
    这正是在验证「链路真的能跑通」而不是「命令执行不报错」。
    """
    javac = javac_executable()
    if javac is None:
        return None
    source_dir = Path(directory) / "src" / "com" / "example"
    source_dir.mkdir(parents=True, exist_ok=True)
    source = source_dir / "Demo.java"
    source.write_text(
        "package com.example;\n"
        "public class Demo {\n"
        "    public String handle(String name) throws Exception {\n"
        "        String command = \"echo \" + name;\n"
        "        Runtime.getRuntime().exec(command);\n"
        "        return name;\n"
        "    }\n"
        "    public static void main(String[] args) throws Exception {\n"
        "        new Demo().handle(\"hi\");\n"
        "    }\n"
        "}\n",
        encoding="utf-8")
    classes = Path(directory) / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    compiled = subprocess.run([javac, "-encoding", "UTF-8", "-d", str(classes), str(source)],
                              capture_output=True, text=True)
    if compiled.returncode != 0:
        return None
    jar_path = Path(directory) / "sample.jar"
    shutil.make_archive(str(jar_path.with_suffix("")), "zip", root_dir=str(classes))
    os.replace(str(jar_path.with_suffix(".zip")), str(jar_path))
    return jar_path


class BackendAvailabilityTest(unittest.TestCase):
    """后端缺席时，测试必须明确写出「未验证」，不能悄悄算作通过。"""

    def test_backend_search_is_deterministic(self):
        """搜索顺序必须稳定且可复现，否则同一台机器两次跑可能命中不同版本。"""
        first = [str(item) for item in search_under(ROOT.parent)]
        second = [str(item) for item in search_under(ROOT.parent)]
        self.assertEqual(first, second)

    def test_missing_backend_is_reported_not_ignored(self):
        """指向不存在的目录时必须返回空结果，而不是编造一个路径。

        返回空列表而不是抛异常：后端缺席是正常状态（本地规则分析仍可用），
        调用方据此走「未配置」分支，而不是让整个功能启动失败。
        """
        missing = Path(tempfile.gettempdir()) / "definitely-missing-xyz"
        self.assertFalse(missing.exists())
        self.assertEqual([], [str(item) for item in search_under(missing)])
        self.assertEqual([], [str(item) for item in search_under(missing, depth=1)])


def skip_reason():
    """端到端跑不起来时，说明为什么——这句话会出现在测试报告里。"""
    if java_executable() is None:
        return ("未配置 jar-analyzer 后端（或找不到能运行它的 Java）：端到端未验证（不是通过）。"
                "设置 JSETK_JAR_ANALYZER_HOME 指向安装目录、"
                "必要时用 JSETK_TEST_JAVA 指定 17+ 的 java 后重跑。")
    return ""


@unittest.skipIf(skip_reason() != "", skip_reason())
class RealBackendEndToEndTest(unittest.TestCase):
    """真实后端全链路：建库 → 表结构 → 查询 → 结论。"""

    @classmethod
    def setUpClass(cls):
        cls.backend = backend_jar()
        cls.java = java_executable()
        if cls.backend is None or cls.java is None:
            raise unittest.SkipTest("后端或合适的 Java 运行期不可用")
        # 断言一定是「版本够用的那个 java」：用自带 JRE 时版本号可能取不到，
        # 但只要它是发行包自带的，就按可用处理（发行包已锁定过运行期）
        # 只有系统 java 需要过版本门槛；发行包自带运行期由发行包自己锁定
        if cls.java != bundled_java(cls.backend):
            version = java_version_of(cls.java)
            assert version is not None and version >= BACKEND_MIN_JAVA, \
                "选中的系统 java 版本不足以运行后端"
        cls.workspace = Path(tempfile.mkdtemp(prefix="jsetk-e2e-"))
        cls.jar = build_sample_jar(cls.workspace)

    def setUp(self):
        if self.jar is None:
            self.skipTest("本机没有 javac，造不出带真实字节码的样本 jar")

    def test_full_chain_build_query_and_judge(self):
        """完整链路：建库、核对表结构、跑查询、跑特征匹配。"""
        database = self.workspace / "jar-analyzer.db"
        result = subprocess.run(
            [self.java, "-Dfile.encoding=UTF-8", "-cp", str(self.backend),
             "me.n1ar4.jar.analyzer.starter.Application", "build",
             "--jar", str(self.jar), "--del-exist", "--del-cache"],
            cwd=str(self.workspace), capture_output=True, text=True,
            timeout=TIMEOUT_SECONDS, errors="replace")
        self.assertEqual(0, result.returncode,
                         "后端建库失败：%s" % (result.stdout or "")[-800:])
        self.assertTrue(database.is_file(), "后端没有产出 jar-analyzer.db")

        connection = jar_report.connect(str(database))
        try:
            present = jar_report.tables(connection)
            missing = [name for name in REQUIRED_TABLES if name not in present]
            self.assertEqual([], missing, "建出来的库缺少必需表: %s" % missing)
            self.assertTrue(jar_report.has_classes(connection, present),
                            "库里没有类：样本 jar 的真实字节码没有被解析")
        finally:
            connection.close()

        # 事实口径：总览能读
        code, out = run_report(database, "summary")
        self.assertEqual(0, code)
        self.assertIn("数据库总览", out)

        # 判定口径：特征匹配能在真实库上跑完并给出状态
        code, out = run_signatures(database)
        self.assertEqual(0, code)
        self.assertIn("漏洞特征匹配", out)
        self.assertIn("状态: 疑似", out)

    def test_schema_against_real_database(self):
        """真实库的结构必须与内置口径兼容，不能被判为「不兼容」。"""
        database = self.workspace / "jar-analyzer.db"
        if not database.is_file():
            self.skipTest("上一次建库没有产出数据库，先看 test_full_chain_build_query_and_judge")
        connection = jar_report.connect(str(database))
        try:
            present = jar_report.tables(connection)
            for query in ("summary", "entries", "sinks", "paths", "strings", "components"):
                missing, fatal = jar_report.check_schema(connection, present, query)
                self.assertFalse(fatal,
                                 "真实库与 %s 查询不兼容：%s" % (query, missing))
        finally:
            connection.close()


def run_report(database, query):
    import contextlib
    import io as _io
    buffer = _io.StringIO()
    with contextlib.redirect_stdout(buffer):
        code = jar_report.main(["-db", str(database), "-q", query])
    return code, buffer.getvalue()


def run_signatures(database):
    import contextlib
    import io as _io
    buffer = _io.StringIO()
    with contextlib.redirect_stdout(buffer):
        code = jar_signatures.main(["-db", str(database)])
    return code, buffer.getvalue()


if __name__ == "__main__":
    unittest.main(verbosity=2)
