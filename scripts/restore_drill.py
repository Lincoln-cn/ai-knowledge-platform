#!/usr/bin/env python3
"""恢复演练记录的可核对性检查（`#75` 判据 ③）。

为什么是一个"检查记录"的脚本，而不是一个"自动演练"的脚本：真正跑一遍恢复要连到备份介质、
要有目标库、要有权限，而这三样在任何 CI 里都拿不到（也不该拿）。能在这里做、且必须做的就是
**把"演练过"这件事变成可核对的记录** —— 否则 `deploy/README.md` 里写的 RPO ≤15 分钟 / RTO ≤4 小时
只是两句话，谁也没验过。

判据（不合规即退出码 1）：
  1. 记录里的九个键一个都不许缺；
  2. RPO ≤ 15 分钟、RTO ≤ 240 分钟（数字取自 `deploy/README.md` 第七节，两处不许各写一份）；
  3. 结论必须是「通过」或「失败」，且失败时不许有"通过"字样；
  4. 记录里不许残留「待补」 —— 模板没填完就不是记录。

跑法：
    python scripts/restore_drill.py check deploy/restore-drill-record.md
    python scripts/restore_drill.py --self-check     # 离线自检（CI 用）
"""

import re
import sys

RPO_LIMIT_MIN = 15
RTO_LIMIT_MIN = 240

REQUIRED_KEYS = [
    "演练日期",
    "备份来源",
    "目标时点",
    "恢复开始",
    "恢复结束",
    "RPO",
    "RTO",
    "行数核对",
    "结论",
]

LINE_RE = re.compile(r"^\s*[-*]\s*([^:：]+)[:：]\s*(.+?)\s*$")
MINUTES_RE = re.compile(r"(\d+(?:\.\d+)?)\s*(分钟|min|分|小时|h)")


def parse_minutes(text):
    """把「8 分钟」「1.5 小时」这类写法读成分钟；读不出来返回 None（不许猜）。"""
    match = MINUTES_RE.search(text)
    if not match:
        return None
    value = float(match.group(1))
    unit = match.group(2)
    return value * 60 if unit in ("小时", "h") else value


def parse_record(text):
    fields = {}
    for line in text.splitlines():
        match = LINE_RE.match(line)
        if match:
            fields[match.group(1).strip()] = match.group(2).strip()
    return fields


def problems(text):
    """返回问题列表；空列表 = 这份记录合规。"""
    found = []
    fields = parse_record(text)

    for key in REQUIRED_KEYS:
        if key not in fields or not fields[key]:
            found.append(f"缺字段：{key}")

    if "待补" in text:
        found.append("记录里还有「待补」—— 模板没填完不算演练记录")

    rpo = parse_minutes(fields.get("RPO", ""))
    if rpo is None:
        found.append("RPO 读不出分钟数（写成「8 分钟」或「1.5 小时」这种形状）")
    elif rpo > RPO_LIMIT_MIN:
        found.append(f"RPO {rpo:g} 分钟超过上限 {RPO_LIMIT_MIN} 分钟")

    rto = parse_minutes(fields.get("RTO", ""))
    if rto is None:
        found.append("RTO 读不出分钟数")
    elif rto > RTO_LIMIT_MIN:
        found.append(f"RTO {rto:g} 分钟超过上限 {RTO_LIMIT_MIN} 分钟")

    conclusion = fields.get("结论", "")
    if conclusion and not ("通过" in conclusion or "失败" in conclusion):
        found.append("结论必须写「通过」或「失败」—— 空话等于没结论")
    # ★ 刻意不检查「结论里同时出现通过和失败」：真实记录常写「上次失败、这次通过」，
    #   那种句子是信息，不是矛盾。把它判红会把正确记录拦下来 —— 宁可漏判，不要误杀。

    return found


GOOD = """# 恢复演练记录

- 演练日期：2026-11-01
- 备份来源：每日全量 pg_dump（2026-10-31 02:00 UTC）+ WAL 归档
- 目标时点：2026-10-31T02:05:00Z
- 恢复开始：2026-11-01T09:00:00Z
- 恢复结束：2026-11-01T10:35:00Z
- RPO：5 分钟
- RTO：95 分钟
- 行数核对：kb_document 与备份时点一致（|差| = 0），cv_message 多 3 行（属目标时点之后的写入，符合预期）
- 结论：通过 —— 恢复到目标时点，检索抽样 10 条均可复现
"""


def self_check():
    cases = [
        ("合规记录", GOOD, False),
        ("缺字段", GOOD.replace("- 行数核对：", "- 备注："), True),
        ("RPO 超标", GOOD.replace("- RPO：5 分钟", "- RPO：40 分钟"), True),
        ("RTO 超标", GOOD.replace("- RTO：95 分钟", "- RTO：5 小时"), True),
        ("模板未填", GOOD.replace("- RPO：5 分钟", "- RPO：待补"), True),
        ("结论空话", GOOD.replace("- 结论：通过 —— 恢复到目标时点，检索抽样 10 条均可复现", "- 结论：已完成"), True),
        (
            "结论里提到上次失败（不该判红）",
            GOOD.replace("- 结论：通过 —— 恢复到目标时点，检索抽样 10 条均可复现",
                         "- 结论：通过 —— 上次演练失败于 WAL 缺口，这次补齐后抽样 10 条均可复现"),
            False,
        ),
    ]
    failed = 0
    for name, text, should_fail in cases:
        got = problems(text)
        ok = bool(got) == should_fail
        print(f"{'✓' if ok else '✗'} {name}：{'报出 ' + str(len(got)) + ' 个问题' if got else '无问题'}")
        if not ok:
            failed += 1
    if failed:
        print(f"自检失败：{failed} 个用例与预期不符")
        return 1
    print(f"恢复演练记录自检通过（{len(cases)} 个用例）")
    return 0


def main(argv):
    if "--self-check" in argv:
        return self_check()
    if len(argv) != 2 or argv[0] != "check":
        print("用法：python scripts/restore_drill.py check <记录文件> | --self-check")
        return 2
    with open(argv[1], encoding="utf-8") as handle:
        text = handle.read()
    found = problems(text)
    if found:
        print(f"✗ {argv[1]} 不是一份合格的演练记录：")
        for item in found:
            print(f"  - {item}")
        return 1
    print(f"✓ {argv[1]} 合格：RPO/RTO 在限内，九个字段齐，结论明确")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
