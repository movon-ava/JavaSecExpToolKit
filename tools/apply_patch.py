"""从 UTF-8 补丁文件调用 codex 内置 apply_patch。

用法：python tools/apply_patch.py <补丁文件路径>

Windows 下 PowerShell 无法用 `<` 重定向，且中文补丁容易出现编码丢失，
因此统一改为先把补丁写成 UTF-8（无 BOM）文件，再交给 apply_patch 执行。
"""

import io
import os
import subprocess
import sys
from pathlib import Path

CODEX_BIN = Path(
    os.environ.get("CODEX_BIN")
    or Path(os.environ.get("APPDATA", ""))
    / "npm/node_modules/@openai/codex/node_modules/@openai/codex-win32-x64"
    / "vendor/x86_64-pc-windows-msvc/bin/codex.exe"
)


def apply(patch_path: Path) -> int:
    """把补丁文件内容交给 apply_patch 执行，返回进程退出码。"""
    if not patch_path.is_file():
        raise FileNotFoundError("补丁文件不存在: {0}".format(patch_path))
    if not CODEX_BIN.is_file():
        raise FileNotFoundError("未找到 apply_patch，可设置 CODEX_BIN 环境变量: {0}".format(CODEX_BIN))
    patch = io.open(patch_path, encoding="utf-8").read()
    result = subprocess.run(
        [str(CODEX_BIN), "--codex-run-as-apply-patch", patch],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    sys.stdout.write(result.stdout or "")
    sys.stderr.write(result.stderr or "")
    return result.returncode


def main(argv):
    if len(argv) != 2:
        print(__doc__.strip())
        return 1
    return apply(Path(argv[1]).resolve())


if __name__ == "__main__":
    sys.exit(main(sys.argv))