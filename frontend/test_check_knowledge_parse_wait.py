"""`wait_document` 的自测：证明解析闸门等的是终态，不是固定时长（Issue #170）。

运行：`python frontend/test_check_knowledge_parse_wait.py`
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_knowledge_parse as gate  # noqa: E402


def response(status: str, **extra) -> dict:
    row = {"id": "doc-1", "status": status, **extra}
    return {"data": {"list": [row]}}


class WaitDocumentTest(unittest.TestCase):
    def test_waits_until_succeeded(self) -> None:
        states = [response("PENDING_PARSE"), response("PENDING_PARSE"), response("SUCCEEDED")]
        calls: list[int] = []

        def fake_api(*_args, **_kwargs):
            calls.append(1)
            return states.pop(0)

        with (
            patch.object(gate, "api_json", side_effect=fake_api),
            patch.object(gate.time, "sleep", lambda _seconds: None),
        ):
            result = gate.wait_document("kb-1", "doc-1", "token", expect_success=True, timeout=1)

        self.assertEqual(result["status"], "SUCCEEDED")
        self.assertEqual(len(calls), 3, "不能在 PENDING 状态提前返回")

    def test_failure_terminal_with_reason_is_returned(self) -> None:
        with (
            patch.object(
                gate,
                "api_json",
                return_value=response("SEGMENT_FAILED", failReason="解析失败", failHint="请重试"),
            ),
            patch.object(gate.time, "sleep", lambda _seconds: None),
        ):
            result = gate.wait_document("kb-1", "doc-1", "token", expect_success=False, timeout=1)

        self.assertEqual(result["status"], "SEGMENT_FAILED")
        self.assertEqual(result["failReason"], "解析失败")

    def test_timeout_returns_last_readout(self) -> None:
        calls: list[int] = []

        def fake_api(*_args, **_kwargs):
            calls.append(1)
            return response("PENDING_PARSE")

        with (
            patch.object(gate, "api_json", side_effect=fake_api),
            patch.object(gate.time, "sleep", lambda _seconds: None),
        ):
            result = gate.wait_document("kb-1", "doc-1", "token", expect_success=True, timeout=0.02)

        self.assertEqual(result["status"], "PENDING_PARSE")
        self.assertGreater(len(calls), 0, "超时也要至少轮询一次，不能直接空手返回")


if __name__ == "__main__":
    unittest.main(verbosity=2)
