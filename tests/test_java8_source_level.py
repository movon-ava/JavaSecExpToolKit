"""源码级别的机械门禁：src/ 不得使用 Java 9+ 的 API 与语法。

背景（这是本模块存在的唯一理由）：`src/pom.xml` 的 `maven.compiler.release` 是 8，
但自检那套编译命令（README 与 run.ps1 里手写的 `javac`）**没有 `--release 8`**，
于是源码里混进 Java 9+ 的 API 时：

* `javac`（默认按当前 JDK 17 编译）编译通过，自检全绿；
* 只有走 `build.ps1` 的 Maven 构建才报「找不到符号」。

也就是说这个错误**只在最后一刻现形**，而且看起来像构建环境问题。
本轮就真的踩到了：`src/analyzer/EngineRunner.java` 与 `ScriptRunner.java` 直接调了
`Process#descendants()`（Java 9 新增），Maven 构建失败而七套自检全绿。

因此把「不许用 9+ 语法与 API」变成可机械判定的断言，而不是靠人记得。
判据是**显式黑名单**：命中即失败，并且报出文件与行号，便于直接定位。

误报控制：扫描前先剥离注释与字符串字面量（复用 `tests/test_decoupling.py` 的状态机，
它是同一仓库里已经过验证的实现，不在这里再写第二份），
因此文档里提到 `List.of` 之类的说明文字不会让断言失败。
"""

import importlib.util
import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")
DECOUPLING = os.path.join(ROOT, "tests", "test_decoupling.py")


def _load_stripper():
    """复用依赖边界自检里的注释 / 字符串剥离器，避免同仓库两份实现漂移。"""
    spec = importlib.util.spec_from_file_location("jsetk_test_decoupling", DECOUPLING)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.strip_comments_and_strings


strip_comments_and_strings = _load_stripper()

# 每条是 (说明, 正则)。正则只匹配剥离注释与字符串之后的代码。
FORBIDDEN = (
    ("List.of / Set.of / Map.of（Java 9+ 集合工厂）",
     re.compile(r"\b(?:List|Set|Map)\.of\s*\(")),
    ("var 局部变量类型推断（Java 10+）", re.compile(r"(?:^|[;{(]\s*)var\s+[A-Za-z_]")),
    ("Process#descendants（Java 9+）", re.compile(r"\.descendants\s*\(\s*\)")),
    ("Process#pid（Java 9+）", re.compile(r"\.pid\s*\(\s*\)")),
    ("ProcessHandle（Java 9+）", re.compile(r"(?<![\w.])ProcessHandle\b")),
    ("Process#toHandle（Java 9+）", re.compile(r"\.toHandle\s*\(\s*\)")),
    ("String#isBlank / strip（Java 11+）", re.compile(r"\.(?:isBlank|strip|stripLeading|stripTrailing)\s*\(\s*\)")),
    ("String#repeat（Java 11+）", re.compile(r"\.repeat\s*\(\s*[^)]*\)")),
    ("Stream#toList（Java 16+）", re.compile(r"\.toList\s*\(\s*\)")),
    ("String#lines（Java 11+）", re.compile(r"\.lines\s*\(\s*\)")),
    ("Files.readString / writeString（Java 11+）", re.compile(r"Files\.(?:readString|writeString)\s*\(")),
    ("InputStream#readAllBytes（Java 9+）", re.compile(r"\.readAllBytes\s*\(\s*\)")),
    ("readNBytes / transferTo（Java 9+）", re.compile(r"\.(?:readNBytes|transferTo)\s*\(")),
    ("Map#ofEntries / Map.entry（Java 9+）", re.compile(r"\bMap\.(?:ofEntries|entry)\s*\(")),
    ("java.util.stream.Stream#iterate 三参（Java 9+）", re.compile(r"Stream\.iterate\s*\([^;]*,[^;]*,[^;]*\)")),
    ("instanceof 模式匹配（Java 16+）",
     re.compile(r"instanceof\s+[A-Z][\w.]*\s+[a-z]\w*\s*[)&|]")),
    ("switch 表达式箭头（Java 14+）", re.compile(r"case\s+[^:;\n]+->")),
    ("文本块三引号（Java 15+）", re.compile(r'"""')),
    ("record 声明（Java 16+）", re.compile(r"(?:^|[;{]\s*)record\s+[A-Z]\w*\s*\(")),
    ("密封类 sealed / permits（Java 17+）", re.compile(r"\b(?:sealed|permits)\b")),
)


def java_sources():
    """src/ 下全部 Java 源文件。"""
    for current, _dirs, files in os.walk(SRC):
        for name in sorted(files):
            if name.endswith(".java"):
                yield os.path.join(current, name)


class Java8SourceLevelTest(unittest.TestCase):
    """src/ 必须能按 release 8 编译：不许出现 9+ 的 API 与语法。"""

    def test_no_java9_apis_or_syntax(self):
        """逐文件扫描黑名单，命中即报出文件与行号。"""
        offenders = []
        for path in java_sources():
            with open(path, encoding="utf-8") as handle:
                cleaned = strip_comments_and_strings(handle.read())
            for line_number, line in enumerate(cleaned.splitlines(), start=1):
                for reason, pattern in FORBIDDEN:
                    if pattern.search(line):
                        offenders.append("%s:%d 命中「%s」\n    %s"
                                         % (os.path.relpath(path, ROOT), line_number,
                                            reason, line.strip()))
        self.assertEqual([], offenders,
                         "src/ 出现 Java 9+ 的 API 或语法（构建会失败但自检看不出来）：\n  "
                         + "\n  ".join(offenders))

    def test_scanner_sees_real_code_but_not_comments(self):
        """反向用例：证明剥离器真的在工作，断言不是恒真。

        注释与字符串里的 `List.of` 不算违规，真实代码里的必须被扫出来——
        把这两件事写进同一条用例，才能证明上面那条「未命中」不只是一个恒真断言。
        """
        cleaning_proof = ("// List.of 在注释里不算违规\n"
                          "String note = \"List.of(a)\";\n")
        cleaned_comment_only = strip_comments_and_strings(cleaning_proof)
        self.assertNotIn("List.of", cleaned_comment_only,
                         "注释与字符串里的 List.of 不应被判为违规")

        sample = cleaning_proof + "List<String> real = List.of(\"a\");\n"
        cleaned = strip_comments_and_strings(sample)
        hits = [reason for reason, pattern in FORBIDDEN if pattern.search(cleaned)]
        self.assertIn("List.of / Set.of / Map.of（Java 9+ 集合工厂）", hits,
                      "真实代码里的 List.of 必须被扫出来")

    def test_src_has_sources(self):
        """护栏：扫描目标为空时上面两条断言会变成恒真。"""
        self.assertGreater(len(list(java_sources())), 50,
                           "src/ 下 Java 源文件数异常，扫描可能指向了错误目录")


if __name__ == "__main__":
    unittest.main(verbosity=2)