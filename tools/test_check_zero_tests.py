"""`check_zero_tests.py` 的自测：证明这道闸门真的会红。

Issue #50 的教训是「判据本身可能失效」——所以闸门不能只靠读代码相信，必须有一条
**故意造出来的坏输入**让它红。这里用四份临时报告覆盖：

1. 空目录（一次测试都没跑）
2. 有报告但没有 `Tests run:` 行（`-Dtest=A,B` 静默不跑 / 报告被截断）
3. `Tests run: 0`（`@BeforeAll` 的 assume 中止整个容器）
4. `Tests run: 1, Failures: 0, Errors: 0`（正常绿）

运行：`python tools/test_check_zero_tests.py`
"""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check_zero_tests.py")


def run_gate(target: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), str(target)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


class CheckZeroTestsGateTest(unittest.TestCase):
    def test_empty_directory_is_red(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            result = run_gate(Path(tmp))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("没有 surefire 报告", result.stderr + result.stdout)

    def test_report_without_tests_run_line_is_red(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "SomeTest.txt"
            report.write_text("no readout here\n", encoding="utf-8")
            result = run_gate(Path(tmp))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("没有 `Tests run:`", result.stdout)
        self.assertIn("SomeTest.txt", result.stdout)

    def test_zero_tests_is_red(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "SomeTest.txt"
            report.write_text(
                "Tests run: 0, Failures: 0, Errors: 0, Skipped: 0 -- in SomeTest\n",
                encoding="utf-8",
            )
            result = run_gate(Path(tmp))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Tests run: 0", result.stdout)

    def test_real_readout_is_green(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "SomeTest.txt"
            report.write_text(
                "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in SomeTest\n",
                encoding="utf-8",
            )
            result = run_gate(Path(tmp))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("真的跑了用例", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
