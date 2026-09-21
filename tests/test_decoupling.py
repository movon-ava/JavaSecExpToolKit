"""依赖边界自检：把「是否被正确解耦」变成可机械判定的断言。

判定依据来自 openspec/specs 中 codebase/dependency-boundary 规格：

1. 包级依赖必须无环；
2. 包级依赖必须符合声明分层，叶子层（config / proxy / util）不得有出边；
   组合根（默认包中的 Main）允许装配任意模块；
3. 通用组件不得依赖具体功能模块（通用链引擎不得引用 Shiro 专用类）；
4. 共享内核（util / config）只被依赖，不反向依赖上层。

本模块只用标准库实现源码静态扫描，不引入任何第三方依赖。
扫描前会剥离注释与字符串字面量，避免把它们当中真实引用而误报。
"""

import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")

# 允许的包级依赖方向：键为依赖方，值为被允许依赖的包集合。
# 未列出的依赖方向一律视为越界。
#
# "<default>" 是组合根（Main），职责就是装配全部模块，允许依赖任何包；
# "ui" 是界面层，可以依赖它要展示的功能模块。
ALLOWED_EDGES = {
    "<default>": {"probe", "proxy", "shiro", "config", "util", "ui"},
    "ui": {"probe", "proxy", "shiro", "config", "util"},
    "probe": {"util", "config"},
    "shiro": {"util", "config"},
    "proxy": {"util", "config"},
    "config": set(),
    "util": set(),
}

# 层内不允许反向依赖的包：它们必须是叶子，只能被依赖
LEAF_PACKAGES = ("config", "proxy", "util")

# 共享内核：被依赖方，不得依赖上层
KERNEL_PACKAGES = ("util", "config")

# 通用组件：文件 -> 该文件不允许出现的具体功能模块标识符
GENERIC_MODULES = {
    os.path.join("src", "shiro", "ChainsEngine.java"): ("ShiroEngine",),
}


def strip_comments_and_strings(text):
    """剥离注释与字符串字面量，避免把说明文字当作真实引用。"""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    text = re.sub(r"'(?:\\.|[^'\\])*'", "''", text)
    return text


def java_sources():
    """返回 [(绝对路径, 包名, 剥离注释与字符串后的源码)]。"""
    found = []
    for dirpath, _dirnames, filenames in os.walk(SRC):
        for name in filenames:
            if not name.endswith(".java"):
                continue
            full = os.path.join(dirpath, name)
            with open(full, encoding="utf-8") as handle:
                raw = handle.read()
            match = re.search(r"^package\s+([\w.]+)\s*;", raw, re.M)
            pkg = match.group(1) if match else "<default>"
            found.append((full, pkg, strip_comments_and_strings(raw)))
    return found


def class_index():
    """类名 -> 包名，用于把 import 与限定名解析成包级依赖。"""
    index = {}
    for full, pkg, _cleaned in java_sources():
        index[os.path.basename(full)[:-5]] = pkg
    return index


def package_edges():
    """构建包级依赖图：{(依赖方, 被依赖方): [证据, ...]}。"""
    index = class_index()
    edges = {}
    for full, pkg, cleaned in java_sources():
        display = os.path.relpath(full, ROOT).replace("\\", "/")
        for number, line in enumerate(cleaned.split("\n"), 1):
            for cls, target in index.items():
                if target == pkg:
                    continue
                hit = re.match(r"^\s*import\s+" + re.escape(target) + r"\." + re.escape(cls) + r"\s*;", line)
                if hit is None:
                    hit = re.search(r"\b" + re.escape(target) + r"\." + re.escape(cls) + r"\b", line)
                if hit is None:
                    continue
                edges.setdefault((pkg, target), []).append(
                    "%s:%d 引用 %s.%s" % (display, number, target, cls)
                )
    return edges


def find_cycles(edges):
    """返回互相依赖的包对：[(a, b), ...]，每对只报一次。"""
    pairs = set()
    for (a, b) in edges:
        if (b, a) in edges and a != b:
            pairs.add(tuple(sorted((a, b))))
    return sorted(pairs)


class DependencyBoundaryTest(unittest.TestCase):
    """依赖边界规则；任一断言失败即说明解耦被打断。"""

    def test_sources_are_discovered(self):
        """确保扫描真的读到了源码，避免规则因扫不到文件而恒真通过。"""
        packages = {pkg for _full, pkg, _c in java_sources()}
        for expected in ("ui", "probe", "proxy", "shiro", "config", "util"):
            self.assertIn(expected, packages, "未扫描到包 %s" % expected)

    def test_no_package_cycle(self):
        """包级依赖必须无环。"""
        edges = package_edges()
        cycles = find_cycles(edges)
        detail = []
        for a, b in cycles:
            detail.append("%s <-> %s" % (a, b))
            for evidence in (edges.get((a, b), []) + edges.get((b, a), []))[:4]:
                detail.append("    " + evidence)
        self.assertEqual([], cycles, "存在包级循环依赖:\n" + "\n".join(detail))

    def test_edges_match_declared_layers(self):
        """每个包只允许依赖声明过的下层包。"""
        offenders = []
        for (source, target), evidence in sorted(package_edges().items()):
            allowed = ALLOWED_EDGES.get(source)
            if allowed is None:
                offenders.append("未声明的包 %s -> %s (%s)" % (source, target, evidence[0]))
                continue
            if target not in allowed:
                offenders.append("%s -> %s 不在允许集合 %s 内 (%s)"
                                 % (source, target, sorted(allowed), evidence[0]))
        self.assertEqual([], offenders, "依赖方向越界:\n" + "\n".join(offenders))

    def test_leaf_packages_have_no_outgoing_edges(self):
        """叶子层不得依赖任何其它项目包。"""
        offenders = []
        for (source, target), evidence in sorted(package_edges().items()):
            if source in LEAF_PACKAGES:
                offenders.append("%s -> %s (%s)" % (source, target, evidence[0]))
        self.assertEqual([], offenders, "叶子层出现了出边:\n" + "\n".join(offenders))

    def test_shared_kernel_does_not_depend_on_layers_above(self):
        """共享内核只被依赖，不反向依赖上层。"""
        offenders = []
        for (source, target), evidence in sorted(package_edges().items()):
            if source in KERNEL_PACKAGES and target not in KERNEL_PACKAGES:
                offenders.append("%s -> %s (%s)" % (source, target, evidence[0]))
        self.assertEqual([], offenders, "共享内核反向依赖:\n" + "\n".join(offenders))

    def test_generic_engine_does_not_reference_feature_module(self):
        """通用链引擎不得依赖 Shiro 专用模块。"""
        for relative, forbidden in sorted(GENERIC_MODULES.items()):
            full = os.path.join(ROOT, relative.replace("/", os.sep))
            self.assertTrue(os.path.exists(full), "找不到通用组件 %s" % relative)
            with open(full, encoding="utf-8") as handle:
                cleaned = strip_comments_and_strings(handle.read())
            for name in forbidden:
                self.assertNotIn(
                    name, cleaned,
                    "%s 不应引用具体功能模块 %s（通用组件需可跨功能复用）" % (relative, name)
                )

    def test_codec_is_reusable_kernel(self):
        """共享内核 Codec 必须自身无项目内依赖。"""
        edges = package_edges()
        offenders = ["%s -> %s (%s)" % (a, b, e[0])
                     for (a, b), e in edges.items() if a == "util"]
        self.assertEqual([], offenders, "util 包存在项目内依赖:\n" + "\n".join(offenders))


class CycleDetectorSelfTest(unittest.TestCase):
    """反向用例：证明环检测器能真的发现环，而不是恒真通过。"""

    def test_detector_reports_cycle(self):
        fake = {
            ("a", "b"): ["a/X.java:1 引用 b.Y"],
            ("b", "a"): ["b/Y.java:2 引用 a.X"],
            ("b", "c"): ["b/Y.java:3 引用 c.Z"],
        }
        self.assertEqual([("a", "b")], find_cycles(fake))

    def test_detector_accepts_acyclic_graph(self):
        fake = {
            ("ui", "shiro"): ["ui/P.java:1"],
            ("shiro", "util"): ["shiro/E.java:2"],
        }
        self.assertEqual([], find_cycles(fake))


class EdgeScannerSelfTest(unittest.TestCase):
    """反向用例：证明扫描器能识别真实引用，且不会把注释当引用。"""

    def test_comment_and_string_are_ignored(self):
        sample = '// import shiro.ShiroEngine;\nString note = "shiro.ShiroEngine";\n'
        cleaned = strip_comments_and_strings(sample)
        self.assertNotIn("ShiroEngine", cleaned)

    def test_real_reference_is_kept(self):
        sample = "import shiro.ShiroEngine;\nShiroEngine.base64(bytes);\n"
        cleaned = strip_comments_and_strings(sample)
        self.assertIn("ShiroEngine", cleaned)


if __name__ == "__main__":
    unittest.main(verbosity=2)