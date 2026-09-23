"""日志内核的纯规则单测：文件名解析与保留天数边界。

与 tests/LogCheck.java 的分工：Java 自检负责「真的写文件、真的跨天换文件」，
这里只覆盖不依赖文件系统的纯计算，跑得快、能覆盖大量边界组合。
之所以两边都要有，是因为保留天数算错时表现是「文件确实被删了」，
只靠一边的少量用例很难穷尽跨月与跨年这类边界。
"""

import os
import re
import unittest


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG_FILES = os.path.join(ROOT, "src", "util", "LogFiles.java")
LOG = os.path.join(ROOT, "src", "util", "Log.java")


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


# 与 src/util/LogFiles.java 保持一致的规则（这里按规格独立实现，不复用被验方代码）
PREFIX = "app-"
SUFFIX = ".log"
NAME_PATTERN = re.compile(r"^app-(\d{4})-(\d{2})-(\d{2})\.log$")


def parse_date(name):
    """按规格解析文件名中的日期；不合法返回 None。"""
    match = NAME_PATTERN.match(name or "")
    if match is None:
        return None
    year, month, day = (int(part) for part in match.groups())
    if not (1 <= month <= 12):
        return None
    # 格里高利历的每月天数；二月按闰年判定
    days = [31, 29 if is_leap(year) else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    if not (1 <= day <= days[month - 1]):
        return None
    return (year, month, day)


def is_leap(year):
    return year % 4 == 0 and (year % 100 != 0 or year % 400 == 0)


def expired(names, today, keep_days):
    """按规格给出应删除的文件名，返回按名称升序的列表。"""
    days = max(1, keep_days)
    year, month, day = today
    oldest = to_ordinal(year, month, day) - (days - 1)
    victims = []
    for name in names or []:
        parsed = parse_date(name)
        if parsed is None:
            continue
        if to_ordinal(*parsed) < oldest:
            victims.append(name)
    return sorted(victims)


def to_ordinal(year, month, day):
    """把日期转成可比大小的整数；只用于单测内部比较。"""
    total = 0
    for y in range(1, year):
        total += 366 if is_leap(y) else 365
    days = [31, 29 if is_leap(year) else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    for m in range(1, month):
        total += days[m - 1]
    return total + day


class FileNameRuleTest(unittest.TestCase):
    """文件名规则：严格 ISO 日期，不合规一律不认。"""

    def test_valid_name_is_parsed(self):
        self.assertEqual((2026, 9, 23), parse_date("app-2026-09-23.log"))

    def test_wrong_prefix_is_rejected(self):
        self.assertIsNone(parse_date("log-2026-09-23.log"))

    def test_wrong_suffix_is_rejected(self):
        self.assertIsNone(parse_date("app-2026-09-23.txt"))

    def test_unpadded_month_is_rejected(self):
        """月份不补零也拒绝：否则同一天会有多种文件名，清理时容易被漏掉。"""
        self.assertIsNone(parse_date("app-2026-9-23.log"))

    def test_invalid_month_is_rejected(self):
        self.assertIsNone(parse_date("app-2026-13-01.log"))

    def test_invalid_day_is_rejected(self):
        self.assertIsNone(parse_date("app-2026-02-30.log"))

    def test_leap_day_is_accepted(self):
        self.assertEqual((2024, 2, 29), parse_date("app-2024-02-29.log"))

    def test_non_leap_day_is_rejected(self):
        self.assertIsNone(parse_date("app-2023-02-29.log"))

    def test_empty_and_none_are_rejected(self):
        self.assertIsNone(parse_date(""))
        self.assertIsNone(parse_date(None))

    def test_implementation_uses_same_prefix_and_suffix(self):
        """规格与实现的字面量保持一致，避免两边各自改一处导致漂移。"""
        text = read(LOG_FILES)
        self.assertIn('PREFIX = "%s"' % PREFIX, text)
        self.assertIn('SUFFIX = "%s"' % SUFFIX, text)


class RetentionRuleTest(unittest.TestCase):
    """保留策略：语义为「最近 N 天含今天」。"""

    NAMES = [
        "app-2026-09-15.log",
        "app-2026-09-16.log",
        "app-2026-09-17.log",
        "app-2026-09-18.log",
        "app-2026-09-23.log",
        "notes.txt",
        "app-backup.log",
    ]

    def test_keeps_today_and_recent(self):
        victims = expired(self.NAMES, (2026, 9, 23), 7)
        # 保留 9/17 至 9/23（含当天共 7 天）
        self.assertEqual(["app-2026-09-15.log", "app-2026-09-16.log"], victims)

    def test_non_log_names_never_expire(self):
        victims = expired(self.NAMES, (2026, 9, 23), 7)
        self.assertNotIn("notes.txt", victims)
        self.assertNotIn("app-backup.log", victims)

    def test_zero_days_keeps_only_today(self):
        victims = expired(self.NAMES, (2026, 9, 23), 0)
        self.assertNotIn("app-2026-09-23.log", victims, "当天文件必须保留")
        self.assertIn("app-2026-09-18.log", victims)

    def test_negative_days_keeps_only_today(self):
        self.assertEqual(expired(self.NAMES, (2026, 9, 23), 0),
                         expired(self.NAMES, (2026, 9, 23), -9))

    def test_large_retention_keeps_everything(self):
        self.assertEqual([], expired(self.NAMES, (2026, 9, 23), 3650))

    def test_month_boundary(self):
        victims = expired(["app-2026-08-25.log", "app-2026-08-26.log"], (2026, 9, 1), 7)
        self.assertEqual(["app-2026-08-25.log"], victims)

    def test_year_boundary(self):
        victims = expired(["app-2025-12-26.log", "app-2025-12-27.log"], (2026, 1, 2), 7)
        self.assertEqual(["app-2025-12-26.log"], victims)

    def test_leap_year_boundary(self):
        victims = expired(["app-2024-02-23.log", "app-2024-02-24.log"], (2024, 3, 1), 7)
        self.assertEqual(["app-2024-02-23.log"], victims)

    def test_empty_input(self):
        self.assertEqual([], expired([], (2026, 9, 23), 7))
        self.assertEqual([], expired(None, (2026, 9, 23), 7))

    def test_result_is_sorted(self):
        names = ["app-2026-09-16.log", "app-2026-09-15.log", "app-2026-09-14.log"]
        self.assertEqual(sorted(names), expired(names, (2026, 9, 23), 7))


class ImplementationContractTest(unittest.TestCase):
    """实现契约：这些性质错了不会报错，只会让日志静默失效。"""

    def test_log_writes_never_throw_upward(self):
        """写日志必须兜住全部异常：日志是旁路，不能影响主流程。"""
        text = read(LOG)
        self.assertIn("Throwable", text, "写入路径必须兜住 Throwable 而不只是 Exception")
        self.assertIn("catch (Throwable ignored)", text,
                      "写入失败必须静默丢弃这一条")

    def test_log_has_uncaught_handler(self):
        """未捕获异常处理器是本功能对排错价值最高的一项。"""
        self.assertIn("installUncaughtHandler", read(LOG))
        self.assertIn("setDefaultUncaughtExceptionHandler", read(LOG))

    def test_cleanup_only_targets_own_names(self):
        """清理必须先按命名过滤，再校验目标在日志目录内。"""
        text = read(LOG_FILES)
        self.assertIn("dateOf(name)", text, "清理候选必须按命名规则过滤")
        self.assertIn("isRegularFile", text, "只有常规文件才进入候选")
        self.assertIn("getParent()", text, "删除前必须校验目标位于日志目录之内")

    def test_daily_file_name_is_used(self):
        """按日期分文件的落点：写入方必须用当天的文件名。"""
        self.assertIn("LogFiles.fileName(", read(LOG))

    def test_kernel_has_no_project_outgoing_dependency(self):
        """内核必须自包含：只能依赖 JDK 与同包内核类。"""
        for path in (LOG, LOG_FILES):
            for line in read(path).splitlines():
                stripped = line.strip()
                if not stripped.startswith("import "):
                    continue
                target = stripped[len("import "):].rstrip(";").strip()
                if target.startswith("java.") or target.startswith("javax."):
                    continue
                self.assertIn(target, ("util.Log", "util.LogFiles", "util.Platform"),
                              "%s 出现非预期的对外依赖：%s" % (os.path.basename(path), target))


if __name__ == "__main__":
    unittest.main(verbosity=2)