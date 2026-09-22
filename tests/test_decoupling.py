"""依赖边界自检：把「是否被正确解耦」变成可机械判定的断言。

判定依据来自 openspec/specs 中 codebase/dependency-boundary 规格：

1. 包级依赖必须无环；
2. 包级依赖必须符合声明分层；允许的边集合见 ALLOWED_EDGES，
   叶子层（config / proxy / util）不得有出边；
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
    "<default>": {"probe", "proxy", "shiro", "payload", "config", "util", "ui", "service", "preset"},
    "ui": {"probe", "proxy", "shiro", "payload", "config", "util", "service", "preset"},
    "probe": {"util"},
    "shiro": {"payload", "util"},
    "payload": {"util"},
    # service 只依赖载荷构建这一件事：它把链交给引擎，再把结果发布到端口上。
    # 反向依赖（payload -> service）会让你拿到载荷字节就必须连服务端适配器一起加载。
    "service": {"payload"},
    # preset 是纯读取层：只碰 java-chains 的预设模型与自己的数据类，不依赖任何项目包。
    "preset": set(),
    "proxy": set(),
    "config": set(),
    "util": set(),
}

# 层内不允许反向依赖的包：它们必须是叶子，只能被依赖
LEAF_PACKAGES = ("config", "proxy", "util", "preset")

# 共享内核：被依赖方，不得依赖上层
KERNEL_PACKAGES = ("util", "config")

# 通用组件：文件 -> 该文件不允许出现的具体功能模块标识符
# 具体功能模块里的类名：通用组件一旦引用它们，就无法跨功能复用了。
FEATURE_MODULE_CLASSES = (
    "ShiroEngine", "ShiroExploit",
    "ProbeEngine", "ProbeCommand", "CaptureBridge",
    "ProxyServer",
    "CapturePage", "ConfigPage", "FlowRenderer", "HomePage", "NavigationRenderer",
    "NavItem", "PayloadController", "PayloadPage", "ProbePage", "ProxyPage",
    "ShiroPage", "UiKit",
    "ChainEditor", "PresetPage", "PresetController", "ServicePage", "ServiceController",
    "ServiceManager", "ServiceSpec", "ServiceEndpoint", "PublicationResult",
    "PresetItem", "PresetCatalogService",
)

GENERIC_MODULES = {
    os.path.join("src", "shiro", "ChainsEngine.java"): ("ShiroEngine",),
    # 载荷生成包是通用组件：Shiro 只是它的一个使用者，反过来引用会让它绑死在某个功能上。
    os.path.join("src", "payload", "PayloadEngine.java"): FEATURE_MODULE_CLASSES,
    os.path.join("src", "payload", "PayloadCatalog.java"): FEATURE_MODULE_CLASSES,
    os.path.join("src", "payload", "PayloadResult.java"): FEATURE_MODULE_CLASSES,
}


def strip_comments_and_strings(text):
    """单遍扫描剥离注释与字符串字面量。

    必须用状态机而不是依次套正则：若先删行注释，Java 字符串里的 ``"http://x"``
    会被当成注释起点，把该行后面的真实引用一并删掉，导致漏报。
    字符字面量同理，注释里的单个引号（如 ``// don't``）也会让正则失配。
    """
    out = []
    index = 0
    length = len(text)
    while index < length:
        char = text[index]
        nxt = text[index + 1] if index + 1 < length else ""
        if char == "/" and nxt == "/":
            while index < length and text[index] != "\n":
                index += 1
        elif char == "/" and nxt == "*":
            index += 2
            while index + 1 < length and not (text[index] == "*" and text[index + 1] == "/"):
                index += 1
            index = min(index + 2, length)
        elif char == '"' or char == "'":
            quote = char
            index += 1
            while index < length:
                if text[index] == "\\":
                    index += 2
                    continue
                if text[index] == quote:
                    index += 1
                    break
                if text[index] == "\n":
                    break
                index += 1
            out.append('""' if quote == '"' else "''")
        else:
            out.append(char)
            index += 1
    return "".join(out)


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
    """用 DFS 找出全部依赖环，返回 [[a, b, c, ...], ...]（每个环的节点序列）。

    规格写的是「不含任何环」，因此不能只检测 a<->b 这类二元环：
    a -> b -> c -> a 同样是环，而只比较双向边会漏掉它。
    """
    graph = {}
    for (source, target) in edges:
        graph.setdefault(source, set()).add(target)

    cycles = []
    seen_signatures = set()
    path = []
    on_path = set()
    done = set()

    def walk(node):
        path.append(node)
        on_path.add(node)
        for nxt in sorted(graph.get(node, ())):
            if nxt in on_path:
                cycle = path[path.index(nxt):]
                signature = tuple(sorted(cycle))
                if signature not in seen_signatures:
                    seen_signatures.add(signature)
                    cycles.append(list(cycle))
            elif nxt not in done:
                walk(nxt)
        path.pop()
        on_path.discard(node)
        done.add(node)

    for node in sorted(graph):
        if node not in done:
            walk(node)
    return cycles


class DependencyBoundaryTest(unittest.TestCase):
    """依赖边界规则；任一断言失败即说明解耦被打断。"""

    def test_sources_are_discovered(self):
        """确保扫描真的读到了源码，避免规则因扫不到文件而恒真通过。"""
        packages = {pkg for _full, pkg, _c in java_sources()}
        for expected in ("ui", "probe", "proxy", "shiro", "payload", "config", "util"):
            self.assertIn(expected, packages, "未扫描到包 %s" % expected)

    def test_no_package_cycle(self):
        """包级依赖必须无环。"""
        edges = package_edges()
        cycles = find_cycles(edges)
        detail = []
        for cycle in cycles:
            detail.append("环: " + " -> ".join(cycle + [cycle[0]]))
            for i in range(len(cycle)):
                a, b = cycle[i], cycle[(i + 1) % len(cycle)]
                for evidence in edges.get((a, b), [])[:2]:
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


class FileSizeBoundaryTest(unittest.TestCase):
    """界面文件规模上限：防止再次出现「一个文件承担整个页面职责」的退化。

    规格见 openspec/specs/codebase/dependency-boundary：单个界面源文件不超过 600 行，
    组合根不超过 400 行。行数只是表象，它咬住的是「职责是否又被堆回一个文件」。
    """

    UI_MAX_LINES = 600
    MAIN_MAX_LINES = 400

    def _lines(self, path):
        with open(path, encoding="utf-8") as handle:
            return len(handle.read().splitlines())

    def test_main_within_limit(self):
        """组合根不超过 400 行。"""
        main = os.path.join(ROOT, "src", "Main.java")
        count = self._lines(main)
        self.assertLessEqual(count, self.MAIN_MAX_LINES,
                             "src/Main.java 现有 %d 行，超出组合根上限 %d 行" % (count, self.MAIN_MAX_LINES))

    def test_ui_files_within_limit(self):
        """src/ui 下每个文件都不超过 600 行。"""
        ui = os.path.join(ROOT, "src", "ui")
        offenders = []
        for name in sorted(os.listdir(ui)):
            if not name.endswith(".java"):
                continue
            count = self._lines(os.path.join(ui, name))
            if count > self.UI_MAX_LINES:
                offenders.append("%s(%d 行)" % (name, count))
        self.assertEqual([], offenders,
                         "这些界面文件超出 %d 行上限: %s" % (self.UI_MAX_LINES, offenders))


class CycleDetectorSelfTest(unittest.TestCase):
    """反向用例：证明环检测器能真的发现环，而不是恒真通过。"""

    def test_detector_reports_two_node_cycle(self):
        fake = {
            ("a", "b"): ["a/X.java:1 引用 b.Y"],
            ("b", "a"): ["b/Y.java:2 引用 a.X"],
            ("b", "c"): ["b/Y.java:3 引用 c.Z"],
        }
        cycles = find_cycles(fake)
        self.assertEqual(1, len(cycles), "应恰好报出一个环，实际 %s" % cycles)
        self.assertEqual(["a", "b"], sorted(cycles[0]))

    def test_detector_reports_multi_node_cycle(self):
        """三元环也是环，不能被漏掉（规格要求不含任何环）。"""
        fake = {
            ("a", "b"): ["a/X.java:1"],
            ("b", "c"): ["b/Y.java:2"],
            ("c", "a"): ["c/Z.java:3"],
        }
        cycles = find_cycles(fake)
        self.assertEqual(1, len(cycles), "三元环应被检出，实际 %s" % cycles)
        self.assertEqual(["a", "b", "c"], sorted(cycles[0]))

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

    def test_url_in_string_does_not_swallow_following_code(self):
        """字符串里的 // 不能被当成行注释，否则会删掉该行之后的真实引用。"""
        sample = 'String u = "http://a/b";\nimport shiro.ShiroEngine;\n'
        cleaned = strip_comments_and_strings(sample)
        self.assertIn("ShiroEngine", cleaned, "字符串中的 // 不应吞掉后续代码")

    def test_apostrophe_in_comment_does_not_break_scan(self):
        """注释里的撇号不能让扫描器把后续代码吞进字符串。"""
        sample = "// don't do this\nimport shiro.ShiroEngine;\n"
        cleaned = strip_comments_and_strings(sample)
        self.assertIn("ShiroEngine", cleaned, "注释中的撇号不应吞掉后续代码")

    def test_block_comment_marker_in_string_is_kept_as_code(self):
        sample = 'String s = "/* not a comment */";\nimport shiro.ShiroEngine;\n'
        cleaned = strip_comments_and_strings(sample)
        self.assertIn("ShiroEngine", cleaned)


if __name__ == "__main__":
    unittest.main(verbosity=2)
