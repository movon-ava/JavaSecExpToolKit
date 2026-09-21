"""依赖边界审计工具：输出可复现的包级依赖基线，供监督角色与维护者核对。

与 tests/test_decoupling.py 的区别：
  * 测试文件把规则固化为断言，跑失败即告警，用于 CI/回归；
  * 本工具只做只读统计并打印结果，用于人工核对与写进报告。

同时给出两种口径的计数，避免「同一个数字换个算法就对不上」：
  * import-only：只认 `import a.b.C;` 这类导入，最严格；
  * 含限定名：额外认 `a.b.C` 形式的全限定引用，最宽松。
真实依赖数介于两者之间：仅被全限定名引用的边会被 import-only 漏掉。

用法：python tools/audit_boundary.py
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")

# 允许的依赖方向，与 tests/test_decoupling.py 的 ALLOWED_EDGES 保持一致
ALLOWED_EDGES = {
    "<default>": {"probe", "proxy", "shiro", "config", "util", "ui"},
    "ui": {"probe", "proxy", "shiro", "config", "util"},
    "probe": {"util"},
    "shiro": {"util"},
    "proxy": set(),
    "config": set(),
    "util": set(),
}

LEAF_PACKAGES = ("config", "proxy", "util")
KERNEL_PACKAGES = ("util", "config")
UP_LAYERS = ("probe", "proxy", "shiro", "ui", "config")

# 通用组件：文件 -> 不得出现的具体功能模块标识符
GENERIC_MODULES = {
    "src/shiro/ChainsEngine.java": ("ShiroEngine",),
}


def strip_comments_and_strings(text):
    """单遍状态机剥离注释与字符串，避免把说明文字当成真实引用。"""
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
        elif char in ('"', "'"):
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


def load_sources():
    classes = {}
    for dirpath, _dirs, files in os.walk(SRC):
        for name in files:
            if not name.endswith(".java"):
                continue
            full = os.path.join(dirpath, name)
            with open(full, encoding="utf-8") as handle:
                raw = handle.read()
            match = re.search(r"^package\s+([\w.]+)\s*;", raw, re.M)
            classes[name[:-5]] = (
                match.group(1) if match else "<default>",
                full,
                strip_comments_and_strings(raw),
            )
    return classes


def collect_edges(classes, include_qualified):
    """返回 {(源包, 目标包): [证据, ...]}。"""
    edges = {}
    for cls, (pkg, path, cleaned) in classes.items():
        display = os.path.relpath(path, ROOT).replace("\\", "/")
        for number, line in enumerate(cleaned.split("\n"), 1):
            for other, (opkg, _p, _t) in classes.items():
                if opkg == pkg:
                    continue
                pattern = re.escape(opkg) + r"\." + re.escape(other) + r"\b"
                found = re.match(r"^\s*import\s+(?:static\s+)?" + pattern + r"\s*;", line)
                if found is None and include_qualified:
                    found = re.search(r"\b" + pattern, line)
                if found is None:
                    continue
                edges.setdefault((pkg, opkg), []).append("%s:%d" % (display, number))
    return edges


def find_cycles(edges):
    """DFS 检出全部环，返回 [[a, b, c], ...]。"""
    graph = {}
    for (source, target) in edges:
        graph.setdefault(source, set()).add(target)
    cycles, signatures, path, on_path, done = [], set(), [], set(), set()

    def walk(node):
        path.append(node)
        on_path.add(node)
        for nxt in sorted(graph.get(node, ())):
            if nxt in on_path:
                cycle = path[path.index(nxt):]
                signature = tuple(sorted(cycle))
                if signature not in signatures:
                    signatures.add(signature)
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


def main():
    classes = load_sources()
    if not classes:
        print("未扫描到任何 Java 源文件，检查目录：%s" % SRC)
        return 1

    print("扫描范围：%d 个 Java 文件（%s）" % (len(classes), os.path.relpath(SRC, ROOT)))
    print()

    results = {}
    for label, qualified in (("import-only", False), ("含限定名", True)):
        edges = collect_edges(classes, qualified)
        results[label] = edges
        print("== 包级依赖（口径：%s）==" % label)
        for (a, b) in sorted(edges):
            print("  %-12s -> %-12s %3d 处   例: %s"
                  % (a, b, len(edges[(a, b)]), edges[(a, b)][0]))
        print()

    print("== 两种口径的计数对比 ==")
    print("  %-24s %-12s %-12s" % ("依赖边", "import-only", "含限定名"))
    all_edges = sorted(set(results["import-only"]) | set(results["含限定名"]))
    for edge in all_edges:
        a, b = edge
        print("  %-24s %-12d %-12d"
              % ("%s -> %s" % (a, b),
                 len(results["import-only"].get(edge, [])),
                 len(results["含限定名"].get(edge, []))))
    print()

    # 以较宽松的口径做合规判定：宁可多报
    edges = results["含限定名"]

    print("== 规则判定（口径：含限定名）==")
    cycles = find_cycles(edges)
    print("  包级环            : %s" % ([" -> ".join(c + [c[0]]) for c in cycles] if cycles else "无"))

    offenders = []
    for (a, b) in sorted(edges):
        allowed = ALLOWED_EDGES.get(a)
        if allowed is None:
            offenders.append("未声明的包 %s -> %s" % (a, b))
        elif b not in allowed:
            offenders.append("%s -> %s 不在允许集合 %s 内" % (a, b, sorted(allowed)))
    print("  越界依赖方向      : %s" % (offenders if offenders else "无"))

    leaf = ["%s -> %s" % (a, b) for (a, b) in sorted(edges) if a in LEAF_PACKAGES]
    print("  叶子层出边        : %s" % (leaf if leaf else "无"))

    kernel = ["%s -> %s" % (a, b) for (a, b) in sorted(edges)
              if a in KERNEL_PACKAGES and b in UP_LAYERS]
    print("  共享内核反向依赖  : %s" % (kernel if kernel else "无"))

    for relative, forbidden in sorted(GENERIC_MODULES.items()):
        full = os.path.join(ROOT, relative.replace("/", os.sep))
        if not os.path.exists(full):
            print("  通用组件 %s: 文件不存在" % relative)
            continue
        with open(full, encoding="utf-8") as handle:
            body = strip_comments_and_strings(handle.read())
        hits = [name for name in forbidden if name in body]
        print("  通用组件 %s: %s" % (relative, "命中 %s" % hits if hits else "未引用具体功能模块"))

    problems = bool(cycles or offenders or leaf or kernel)
    print()
    print("结论：%s" % ("存在违规" if problems else "全部通过"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())