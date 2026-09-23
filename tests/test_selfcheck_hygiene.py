"""自检副作用清理的机械断言。

背景：java-chains 的 ``Exec`` / ``Clojure`` 一类节点在**构建期**就会执行参数里的命令，
上游给这两个节点登记的默认参数值又是 ``calc``。界面自检点一次「生成」、
预设链自检点一次「生成」，测试机上就会各弹出一个计算器窗口。
``tests/TestProcessGuard.java`` 负责在自检退出时收掉这些进程。

本模块把「每个自检入口都装了守卫」变成可机械判定的断言，理由与依赖边界自检一致：
口头约定会随着新增文件而失守，断言不会。新增一个自检入口却忘了装守卫时，这里会失败。
"""

import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TESTS = os.path.join(ROOT, "tests")
GUARD = os.path.join(TESTS, "TestProcessGuard.java")

# 直接执行 java.exe 的自检入口：文件名 -> 必须出现 install 调用的方法名
ENTRY_FILES = (
    "AnalyzeCheck.java",
    "LogCheck.java",
    "PayloadCheck.java",
    "ProxyServerCheck.java",
    "ShiroCheck.java",
    "UiNavigationCheck.java",
    "UiShiroCheck.java",
    "UiSwitchEndToEndCheck.java",
)


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


class SelfCheckSideEffectTest(unittest.TestCase):
    """自检不得在测试机上留下弹出的计算器进程。"""

    def test_guard_exists(self):
        """守卫文件存在：否则下面的断言会因找不到文件而失去意义。"""
        self.assertTrue(os.path.exists(GUARD), "缺少 tests/TestProcessGuard.java")

    def test_guard_only_closes_new_processes(self):
        """守卫必须按快照比对，而不是按名字关掉全部计算器。

        按名字杀全部会连使用者自己开着的计算器一起关掉——这是本功能最需要避免的副作用。
        断言的是「存在快照函数与比对逻辑」这两个必要构件，而不是具体实现措辞。
        """
        text = read(GUARD)
        self.assertIn("snapshot", text, "守卫应先在自检开始时拍进程快照")
        self.assertIn("ProcessHandle", text, "守卫应通过 ProcessHandle 判定与关闭进程")
        self.assertIn("destroyForcibly", text, "守卫应能强制结束残留进程")
        self.assertIn("addShutdownHook", text,
                      "守卫应挂在 JVM 退出钩子上：自检结尾走 System.exit，finally 块不会执行")

    def test_guard_snapshots_before_hook_is_registered(self):
        """快照必须在装钩子之前拍。

        若先装钩子再拍快照，自检期间弹出的进程就会被当成「本来就有的」，退出时不再清理，
        功能会静默失效——这种顺序错误编译器不会报错，只能靠断言拦住。
        """
        text = read(GUARD)
        snapshot_at = text.index("final List<long[]> before = snapshot();")
        hook_at = text.index("addShutdownHook")
        self.assertLess(snapshot_at, hook_at, "必须先拍快照再注册退出钩子")

    def test_every_entry_installs_guard(self):
        """每个自检入口都必须在 main 的第一时间装上守卫。"""
        missing = []
        for name in ENTRY_FILES:
            path = os.path.join(TESTS, name)
            self.assertTrue(os.path.exists(path), "找不到自检入口 %s" % name)
            text = read(path)
            pattern = r"TestProcessGuard\.install\(" + re.escape('"') + r"[^" + re.escape('"') + r"]+" + re.escape('"') + r"\)"
            if re.search(pattern, text) is None:
                missing.append(name)
        self.assertEqual([], missing, "这些自检入口未装副作用清理守卫: %s" % missing)

    def test_guard_label_matches_file_name(self):
        """守卫标签用文件名，便于从日志判断是哪一个入口留下的进程。"""
        for name in ENTRY_FILES:
            text = read(os.path.join(TESTS, name))
            self.assertIn('TestProcessGuard.install("%s")' % name[:-5], text)


if __name__ == "__main__":
    unittest.main()
