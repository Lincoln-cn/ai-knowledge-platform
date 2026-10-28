#!/usr/bin/env python3
"""生成 P1 形状的合成测试语料。

设计要点
--------
1. **一份文档模型，四种渲染**：正文先构建成 ``Block`` 序列（标题/段落/表格），
   再分别渲染为 Markdown、TXT、DOCX、PDF。四种格式的字符数因此严格一致，
   字数核对才有意义。
2. **确定性**：所有随机数来自 ``random.Random(SEED + 文档序号)``，同一 seed
   下重复运行的产物逐字节一致（PDF 的创建时间戳除外）。
3. **问答锚点内建**：每份文档在指定章节插入 2–4 条"锚点句"，同时产出
   ``expected_snippet``（该句的精确子串）与 ``question``。锚点是独立短段，
   长度不超过 30 字，避免 PDF 换行把子串截断。
4. **不是 P1 语料**：这些文档由模板合成，不是真实业务文档，不能用来出具
   B1–B7 的正式结论。详见 ``corpus/synthetic/README.md``。

用法::

    python tools/gen_corpus.py                # 全量 70 份
    python tools/gen_corpus.py --docs 3       # 冒烟：只生成前 3 份
    python tools/gen_corpus.py --no-pdf       # 跳过 PDF（无需 Chrome）
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import corpus_content as C  # noqa: E402

SEED = 20261028
ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "corpus" / "synthetic"
DOC_DIR_NAME = "documents"
CHROME_CANDIDATES = (
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
)

#: 归口部门 -> 业务系统名称
SYSTEM_BY_DEPT = {
    "综合管理部": "协同办公平台",
    "财务资产部": "财务共享平台",
    "人力资源部": "人力资源管理平台",
    "采购供应部": "供应链协同平台",
    "法务合规部": "合同管理系统",
    "信息技术部": "统一运维管理平台",
    "安全环保部": "安全生产管理平台",
    "质量管理部": "质量管理系统",
    "生产制造部": "生产执行系统",
    "市场发展部": "客户关系管理系统",
    "审计部": "审计管理系统",
    "保密办公室": "涉密载体管理系统",
}

RISK_BY_FAMILY = {
    "制度": ("制度执行不到位", "台账记录缺失"),
    "规程": ("操作不规范导致设备损坏", "人员伤害"),
    "预案": ("响应不及时", "信息报告不畅"),
    "方案": ("需求变更频繁", "进度延期"),
    "合同": ("交付逾期", "质量不符合约定"),
    "报告": ("隐患整改不彻底", "指标数据失真"),
    "教材": ("操作失误", "安全意识淡薄"),
    "规范": ("交付物质量不达标", "评审流于形式"),
    "手册": ("设备故障", "误操作"),
    "纪要": ("议定事项落实不到位", "跨部门协同不畅"),
}

#: 格式分配模板（每 15 份循环一次）：md x10 / docx x2 / pdf x2 / txt x1
FORMAT_CYCLE = ("md",) * 10 + ("docx", "docx", "pdf", "pdf", "txt")

#: 各文档族的目标字数区间（真实公文里合同、纪要远短于制度手册）
PARA_TARGET_BY_FAMILY = {
    "制度": (34000, 44000),
    "规程": (32000, 40000),
    "预案": (30000, 38000),
    "方案": (28000, 36000),
    "合同": (9000, 12000),
    "报告": (22000, 28000),
    "教材": (22000, 28000),
    "规范": (18000, 24000),
    "手册": (20000, 26000),
    "纪要": (3500, 5000),
}

#: 开头句本身已是整句的条款类型：只能"1 开头 + 1 动作 + 1 结果"，否则句尾叠加
LEAD_SENTENCE_TYPES = frozenset({"purpose", "scope"})

#: 每份文档内允许出现的次数上限（超限回落到 FALLBACK_TYPE），避免通篇"为……"
TYPE_BUDGET = {"purpose": 2, "scope": 3}
FALLBACK_TYPE = "principle"

#: 整句类型里"一段拼两句"的族：这些族段落短、需要组合才能铺满一章
MULTI_SENTENCE_TYPES = frozenset(
    name for name in C.SENTENCES if name.startswith("contract_") or name.startswith("minutes_")
)

CN_DIGITS = "零一二三四五六七八九"


def cn_number(n: int) -> str:
    """1..999 的中文数字，用于"第 X 条 / 第 X 章"。"""
    if n < 10:
        return CN_DIGITS[n]
    if n < 20:
        return "十" + (CN_DIGITS[n % 10] if n % 10 else "")
    if n < 100:
        tens, rest = divmod(n, 10)
        return CN_DIGITS[tens] + "十" + (CN_DIGITS[rest] if rest else "")
    if n < 1000:
        hundreds, rest = divmod(n, 100)
        head = CN_DIGITS[hundreds] + "百"
        if rest == 0:
            return head
        if rest < 10:
            return head + "零" + CN_DIGITS[rest]
        if rest < 20:
            return head + "一十" + (CN_DIGITS[rest % 10] if rest % 10 else "")
        return head + cn_number(rest)
    raise ValueError(f"条数超出中文数字范围: {n}")


# ---------------------------------------------------------------------------
# 文档上下文：槽位与随机数
# ---------------------------------------------------------------------------


class DocContext:
    """一份文档的槽位与随机源。"""

    def __init__(self, subject: C.Subject, rng: random.Random, code: str) -> None:
        self.subject = subject
        self.rng = rng
        self.code = code
        self.dept = subject.dept
        self.family = subject.family

        dept = subject.dept
        roles = list(C.ROLES)
        role1, role2 = rng.sample(roles, 2)
        risk1, risk2 = RISK_BY_FAMILY[subject.family]
        persons = rng.sample(list(C.PEOPLE), 2)

        self.base: dict[str, str] = {
            "company": C.COMPANY,
            "subject": subject.short,
            "title": subject.title,
            "dept": dept,
            "doctype": C.DOCTYPE_BY_FAMILY[subject.family],
            "basis": C.BASIS_BY_DEPT.get(dept, "中华人民共和国公司法"),
            "system": SYSTEM_BY_DEPT.get(dept, "综合管理平台"),
            "risk1": risk1,
            "risk2": risk2,
            "committee": rng.choice(C.COMMITTEES),
            "top": rng.choice(C.TOP_ROLES),
            "role1": role1,
            "role2": role2,
            "date": f"2026年{rng.randint(1, 12)}月{rng.randint(1, 28)}日",
            "old": f"{subject.short}管理暂行办法",
            "city1": rng.choice(C.CITIES),
            "city2": rng.choice(C.CITIES),
            "person1": persons[0][0],
            "person2": persons[1][0],
            "vendor": rng.choice(C.VENDORS),
        }
        for i in range(1, 6):
            self.base[f"obj{i}"] = subject.objects[(i - 1) % len(subject.objects)]
            self.base[f"act{i}"] = subject.actions[(i - 1) % len(subject.actions)]
        for i in range(1, 4):
            self.base[f"record{i}"] = subject.records[(i - 1) % len(subject.records)]

        # ★★ 记录保存期限必须是**文档级常量**，不能每段现抽。
        #   实测踩过：`anchor_record` 自己抽 `randint(3, 30)`、而记录类通用池也各抽各的
        #   ⇒ 同一份文档里《固定资产验收单》一会儿 14 年、一会儿 16 年，**自相矛盾**；
        #   而且一轮跑出"保存期限不少于22年""不少于26年"这种不合常理的数。
        #   档期取常见档位（3 / 5 / 10 / 30 年），操作日志另用一组短档（1 / 3 / 5 年）。
        self.base["ret"] = str(rng.choice([3, 5, 10, 30]))
        self.base["logret"] = str(rng.choice([1, 3, 5]))

    # -- 便捷读取 ---------------------------------------------------------
    @property
    def obj1(self) -> str:
        return self.base["obj1"]

    @property
    def act1(self) -> str:
        return self.base["act1"]

    def draw(self) -> dict[str, str]:
        """一段文字用的数字槽位。``amt2 > amt`` 等约束在这里保证。"""
        rng = self.rng
        n = rng.randint(2, 20)
        n2 = n + rng.randint(1, 10)
        amt = rng.choice([3, 5, 8, 10, 15, 20, 30, 50, 80, 100])
        amt2 = amt * rng.choice([2, 3, 4])
        pct = rng.choice([5, 10, 15, 20, 25, 30, 40, 50, 60, 70, 80, 90, 95])
        pct2 = rng.choice([x for x in (5, 10, 15, 20, 25, 30, 40, 50, 60, 70, 80, 90) if x != pct])
        return {
            "n": str(n),
            "n2": str(n2),
            "n3": str(rng.randint(2, 12)),
            "n4": str(rng.randint(600, 3200)),
            "amt": str(amt),
            "amt2": str(amt2),
            "amt3": str(rng.choice([10, 20, 30, 50])),
            "amt4": str(rng.choice([10, 20, 30, 50])),
            "amt_p1": str(int(amt * 0.55)),
            "amt_p2": str(int(amt * 0.25)),
            "pct": str(pct),
            "pct2": str(pct2),
            "pct3": str(rng.choice([6, 9, 13])),
            "pct4": str(rng.choice([1, 2, 3, 5])),
            "score": str(rng.randint(1, 10)),
        }

    def fill(self, template: str) -> str:
        values = dict(self.base)
        values.update(self.draw())
        return template.format(**values)

    # -- 牌堆抽取 ---------------------------------------------------------
    #: 独立随机抽样的重复率太高（一份合同里同一句话出现三次），改成"洗好后逐张发"：
    #: 发完一轮才重洗，同一份文档内句子分布均匀，且相邻段落不会撞句。
    _decks: dict[str, list]

    def deck_draw(self, key: str, pool) -> str:
        decks: dict[str, list] = self.__dict__.setdefault("_decks", {})
        deck = decks.get(key) or []
        if not deck:
            deck = list(pool)
            self.rng.shuffle(deck)
            decks[key] = deck
        return deck.pop()

    def deck_take(self, key: str, pool, count: int) -> list[str]:
        picked: list[str] = []
        for _ in range(count):
            for _ in range(20):
                item = self.deck_draw(key, pool)
                if item not in picked:
                    break
            picked.append(item)
        return picked


# ---------------------------------------------------------------------------
# 段落与章节
# ---------------------------------------------------------------------------


#: 条文起始标记。条号必须在全文拼装完成后统一重排（补字数会跨章追加条文，
#: 边加边编号会得到"第九章 第一百三十五条、第十章 第五十一条"这种错乱）。
ARTICLE_MARK = "\u0001"


def renumber_articles(chapters: list[dict]) -> int:
    """按章顺序重排条号，返回总条数。"""
    no = 1
    for chapter in chapters:
        blocks = chapter["blocks"]
        for index, block in enumerate(blocks):
            if block[0] == "p" and block[1].startswith(ARTICLE_MARK):
                body = block[1][len(ARTICLE_MARK):]
                blocks[index] = ("p", f"第{cn_number(no)}条 {body}")
                no += 1
    return no - 1


def make_paragraph(ctx: DocContext, clause_type: str, seen: set[str]) -> str:
    """按条款类型拼一段话：整句类取 1–2 句；通用类拼"开头 + 动作 + 结果"。"""
    if clause_type in C.SENTENCES:
        candidates = list(C.SENTENCES[clause_type])
        # 合同的每一条按惯例要写满 3 句（否则一份 1.9 万字的合同会堆出 200 多条）
        if clause_type.startswith("contract_"):
            take = 4
        elif clause_type.startswith("minutes_"):
            take = 3
        elif clause_type in MULTI_SENTENCE_TYPES:
            take = 2
        else:
            take = 1
        picks: list[str] = []
        for _ in range(10):
            picks = ctx.deck_take(clause_type, candidates, min(take, len(candidates)))
            text = ctx.fill("".join(picks))
            if text not in seen:
                seen.add(text)
                return text
        return ctx.fill("".join(picks))

    p_pool, a_pool, c_pool = C.POOLS[clause_type]
    if clause_type in LEAD_SENTENCE_TYPES:
        # 开头句与结尾句都是完整句，多个结尾并列就会读成"施行。制定本办法。"
        for _ in range(10):
            text = ctx.fill(
                ctx.deck_draw(f"{clause_type}:P", p_pool)
                + ctx.deck_draw(f"{clause_type}:A", a_pool)
                + ctx.deck_draw(f"{clause_type}:C", c_pool)
            )
            if text not in seen:
                seen.add(text)
                return text
        return text

    for _ in range(10):
        p = ctx.deck_draw(f"{clause_type}:P", p_pool)
        actions = ctx.deck_take(f"{clause_type}:A", a_pool, min(3, len(a_pool)))
        ends = ctx.deck_take(f"{clause_type}:C", c_pool, min(2, len(c_pool)))
        text = ctx.fill(p + "".join(actions) + "".join(ends))
        if text not in seen:
            seen.add(text)
            return text
    return ctx.fill(p + "".join(actions) + "".join(ends))


def build_chapter_articles(
    ctx: DocContext,
    types: tuple[str, ...],
    count: int,
    start_no: int,
    seen: set[str],
    budget: dict[str, int] | None = None,
) -> tuple[list[tuple], int]:
    blocks: list[tuple] = []
    no = start_no
    for _ in range(count):
        clause_type = ctx.rng.choice(types)
        if clause_type == "case":
            text = ctx.fill(ctx.deck_draw("case", C.CASE_TEMPLATES))
            blocks.append(("p", ARTICLE_MARK + text))
            no += 1
            continue
        if clause_type == "supplement":
            text = ctx.fill(ctx.deck_draw("supplement", C.SENTENCES["supplement"]))
            blocks.append(("p", ARTICLE_MARK + text))
            no += 1
            continue
        if budget is not None and clause_type in budget and budget[clause_type] <= 0:
            clause_type = FALLBACK_TYPE
        if budget is not None and clause_type in budget:
            budget[clause_type] -= 1
        if clause_type in LEAD_SENTENCE_TYPES or clause_type in MULTI_SENTENCE_TYPES:
            # 合同的条、纪要的议题都是一段话一条，撑成 3–4 段会读成重复发言
            para_count = 1
        else:
            para_count = ctx.rng.choices([2, 3, 4], weights=(3, 5, 2))[0]
        for index in range(para_count):
            text = make_paragraph(ctx, clause_type, seen)
            prefix = ARTICLE_MARK if index == 0 else ""
            blocks.append(("p", prefix + text))
        no += 1
    return blocks, no


# ---------------------------------------------------------------------------
# 表格
# ---------------------------------------------------------------------------


def build_tables(ctx: DocContext) -> list[tuple[str, tuple]]:
    """返回 [(章节关键字, 表格 block)]。"""
    rng = ctx.rng
    dept = ctx.dept
    subject = ctx.subject.short
    obj1, obj2, obj3 = ctx.base["obj1"], ctx.base["obj2"], ctx.base["obj3"]
    act1, act2 = ctx.base["act1"], ctx.base["act2"]
    rec1, rec2, rec3 = ctx.base["record1"], ctx.base["record2"], ctx.base["record3"]
    committee = ctx.base["committee"]
    role1, role2, top = ctx.base["role1"], ctx.base["role2"], ctx.base["top"]
    city1, city2 = ctx.base["city1"], ctx.base["city2"]
    events: list[tuple[str, tuple]] = []

    if ctx.family == "制度":
        low = rng.choice([3, 5, 8, 10])
        mid = low * rng.choice([2, 3])
        events.append((
            "流程与审批",
            (
                "table",
                "表1  分级审批权限表",
                ["事项类别", "金额区间", "审批人", "办理时限"],
                [
                    [f"{obj1}{act1}", f"{low}万元以下", role1, f"{rng.randint(3, 10)}个工作日"],
                    [f"{obj2}{act1}", f"{low}万元至{mid}万元（含）", role2, f"{rng.randint(5, 15)}个工作日"],
                    [f"{obj3}{act1}", f"{mid}万元以上", committee, f"{rng.randint(10, 30)}个工作日"],
                    [f"{obj1}的{act2}", "不涉及金额", dept, f"{rng.randint(2, 8)}个工作日"],
                ],
            ),
        ))
        events.append((
            "记录与归档",
            (
                "table",
                "表2  主要记录清单",
                ["记录名称", "形成环节", "保存部门", "保存期限"],
                [
                    # ★ 与正文、锚句同源（`ctx.base["ret"]`）：表格与正文各抽一个年份
                    #   会让"记录清单"和"记录管理"章对同一条记录给出两个期限。
                    [rec1, f"{act1}办理", dept, f"{ctx.base['ret']}年"],
                    [rec2, "审核审批", dept, f"{ctx.base['ret']}年"],
                    [rec3, "归档移交", "综合管理部", "永久"],
                ],
            ),
        ))
    elif ctx.family == "规程":
        events.append((
            "操作步骤",
            (
                "table",
                "表1  标准作业步骤",
                ["序号", "操作步骤", "操作要点", "质量标准"],
                [
                    ["1", f"确认{obj1}状态", "停机、断电、挂牌", "无残余能量"],
                    ["2", f"检查{obj2}", "外观完好、连接紧固", "无渗漏、无松动"],
                    ["3", f"执行{act1}", "按规程顺序操作", "参数在允许范围"],
                    ["4", f"记录{rec1}", "要素齐全、签字确认", "可追溯"],
                ],
            ),
        ))
        events.append((
            "异常处置",
            (
                "table",
                "表2  异常情况处置对照表",
                ["异常现象", "可能原因", "处置措施", "报告对象"],
                [
                    ["参数超出允许范围", f"{obj1}异常", "立即停机并隔离", "值班长"],
                    ["出现异味或异响", "部件磨损", "断电检查，联系维修", dept],
                    ["记录缺失", "交接不清", "当日补录并说明", role1],
                ],
            ),
        ))
    elif ctx.family == "预案":
        events.append((
            "分级响应",
            (
                "table",
                "表1  应急响应分级表",
                ["响应级别", "启动条件", "指挥机构", "首次报告时限"],
                [
                    ["Ⅰ级", "造成人员伤亡或重大财产损失", "公司应急指挥部", "30分钟内"],
                    ["Ⅱ级", "影响范围超出单个部门", "分管领导牵头", "1小时内"],
                    ["Ⅲ级", "影响范围限于本部门", dept, "2小时内"],
                ],
            ),
        ))
        rows = []
        for person, title in rng.sample(list(C.PEOPLE), 4):
            rows.append([person, title, f"{rng.choice(C.DEPARTMENTS)}", f"138****{rng.randint(1000, 9999)}"])
        events.append((
            "组织指挥体系与职责",
            ("table", "表2  应急联络表", ["姓名", "职务", "所在部门", "联系电话"], rows),
        ))
    elif ctx.family == "方案":
        start_month = rng.randint(1, 6)
        events.append((
            "实施计划",
            (
                "table",
                "表1  项目实施里程碑",
                ["阶段", "时间", "主要交付物", "责任部门"],
                [
                    ["需求调研", f"2026年{start_month}月", "需求规格说明书", dept],
                    ["方案设计", f"2026年{start_month + 1}月", "总体设计文档", dept],
                    ["开发实施", f"2026年{start_month + 2}月至{start_month + 4}月", f"{obj1}部署", "信息技术部"],
                    ["联调测试", f"2026年{start_month + 5}月", "测试报告", "质量管理部"],
                    ["上线运行", f"2026年{start_month + 6}月", "上线确认书", dept],
                ],
            ),
        ))
        total = rng.choice([300, 500, 800, 1200, 1600])
        events.append((
            "投资估算",
            (
                "table",
                "表2  投资估算表",
                ["费用科目", "金额（万元）", "占比", "说明"],
                [
                    ["设备购置费", int(total * 0.45), "45%", f"{obj1}及配套"],
                    ["软件采购费", int(total * 0.25), "25%", f"{obj2}"],
                    ["实施服务费", int(total * 0.20), "20%", "开发、集成与培训"],
                    ["预备费", total - int(total * 0.45) - int(total * 0.25) - int(total * 0.20), "10%", "不可预见费用"],
                    ["合计", total, "100%", ""],
                ],
            ),
        ))
    elif ctx.family == "合同":
        total = rng.choice([120, 180, 260, 350, 480, 600])
        rows = []
        for i in range(4):
            qty = rng.randint(5, 80)
            price = rng.randint(1200, 26000)
            rows.append([f"{obj1}（{i + 1}）", f"YH-{rng.randint(100, 999)}", qty, price, qty * price])
        events.append((
            "标的与范围",
            (
                "table",
                "附表一  供货清单",
                ["名称", "规格型号", "数量", "单价（元）", "金额（元）"],
                rows + [["合计", "", sum(r[2] for r in rows), "", sum(r[4] for r in rows)]],
            ),
        ))
        p1 = rng.choice([20, 30, 40])
        p2 = rng.choice([40, 50, 60])
        events.append((
            "合同价款与支付",
            (
                "table",
                "附表二  付款计划",
                ["付款节点", "付款比例", "金额（万元）", "触发条件"],
                [
                    ["预付款", f"{p1}%", round(total * p1 / 100, 2), "合同签订并收到发票"],
                    ["验收款", f"{p2}%", round(total * p2 / 100, 2), "验收合格并收到发票"],
                    ["质保金", f"{100 - p1 - p2}%", round(total * (100 - p1 - p2) / 100, 2), "质保期满且无质量问题"],
                    ["合计", "100%", total, ""],
                ],
            ),
        ))
    elif ctx.family == "报告":
        events.append((
            "主要指标",
            (
                "table",
                "表1  主要指标完成情况",
                ["指标名称", "本期", "上年同期", "同比"],
                [
                    [f"{subject}投入（万元）", rng.choice([260, 380, 520, 860]), rng.choice([200, 310, 450, 700]), f"+{rng.randint(3, 26)}%"],
                    [f"{act1}完成数（项）", rng.randint(40, 260), rng.randint(30, 220), f"+{rng.randint(2, 18)}%"],
                    [f"{obj1}达标率", f"{rng.randint(88, 99)}%", f"{rng.randint(80, 92)}%", f"+{rng.randint(1, 8)}个百分点"],
                    [f"{obj2}事故起数", rng.randint(0, 4), rng.randint(2, 8), f"-{rng.randint(10, 60)}%"],
                ],
            ),
        ))
        events.append((
            "重点工作开展情况",
            (
                "table",
                "表2  重点项目推进情况",
                ["项目名称", "投资（万元）", "进度", "责任部门"],
                [
                    [f"{obj1}专项提升", rng.randint(60, 400), f"{rng.randint(70, 100)}%", dept],
                    [f"{obj2}改造", rng.randint(100, 800), f"{rng.randint(50, 95)}%", "信息技术部"],
                    [f"{subject}能力建设", rng.randint(30, 200), f"{rng.randint(60, 100)}%", "人力资源部"],
                ],
            ),
        ))
    elif ctx.family == "教材":
        events.append((
            "课程说明",
            (
                "table",
                "表1  课时安排",
                ["模块", "主要内容", "学时", "授课方式"],
                [
                    ["模块一", f"{obj1}基础知识", rng.choice([2, 3, 4]), "讲授"],
                    ["模块二", f"{obj2}操作要点", rng.choice([3, 4, 6]), "讲授+演示"],
                    ["模块三", "典型案例分析", rng.choice([2, 3, 4]), "案例研讨"],
                    ["模块四", "实操与考核", rng.choice([2, 4]), "实操"],
                ],
            ),
        ))
        events.append((
            "考核要求",
            (
                "table",
                "表2  考核方式与权重",
                ["考核项目", "权重", "合格标准", "考核方式"],
                [
                    ["理论考试", "40%", "80分及以上", "闭卷"],
                    ["实操考核", "40%", "按评分表达到80分", "现场操作"],
                    ["课堂表现", "20%", "出勤率100%", "考勤与提问"],
                ],
            ),
        ))
    elif ctx.family == "规范":
        events.append((
            "技术要求",
            (
                "table",
                "表1  主要技术指标",
                ["指标项", "单位", "标准值", "允许偏差"],
                [
                    [f"{obj1}完整率", "%", "≥98", "±1"],
                    [f"{obj2}响应时间", "秒", "≤3", "±0.5"],
                    [f"{act1}一次通过率", "%", "≥90", "±2"],
                    ["记录可追溯率", "%", "100", "0"],
                ],
            ),
        ))
        events.append((
            "检验方法",
            (
                "table",
                "表2  检验项目与判定",
                ["检验项目", "技术要求", "检验方法", "判定"],
                [
                    ["外观检查", "无破损、标识清晰", "目测", "合格/不合格"],
                    ["功能检验", f"{act1}功能正常", "实际操作", "合格/不合格"],
                    ["性能检验", "满足表1指标", "仪器测量", "合格/不合格"],
                    ["文档检验", "齐全、签署完整", "查阅", "合格/不合格"],
                ],
            ),
        ))
    elif ctx.family == "手册":
        events.append((
            "主要技术参数",
            (
                "table",
                "表1  主要技术参数",
                ["参数名称", "单位", "额定值", "允许范围"],
                [
                    ["额定功率", "kW", rng.choice([5, 8, 12, 20, 35]), "±5%"],
                    ["额定电压", "V", "380", "±10%"],
                    ["运行温度", "℃", rng.choice([18, 22, 25]), "±5"],
                    ["相对湿度", "%", "50", "30～70"],
                    ["防护等级", "IP", rng.choice(["IP21", "IP31", "IP54"]), "—"],
                ],
            ),
        ))
        events.append((
            "故障排除",
            (
                "table",
                "表2  常见故障代码",
                ["故障代码", "故障现象", "可能原因", "处理方法"],
                [
                    [f"E{rng.randint(10, 99)}", f"{obj1}温度过高", "散热不良", "清理过滤网、检查风机"],
                    [f"E{rng.randint(10, 99)}", "通讯中断", "线缆松动或地址冲突", "检查线缆与地址设置"],
                    [f"E{rng.randint(10, 99)}", "输出电压异常", "电池组老化", "更换电池组"],
                ],
            ),
        ))
    elif ctx.family == "纪要":
        rows = []
        for person, title in rng.sample(list(C.PEOPLE), 6):
            rows.append([person, title, rng.choice(C.DEPARTMENTS), "参会"])
        events.append((
            "会议基本情况",
            ("table", "表1  参会人员名单", ["姓名", "职务", "所在部门", "备注"], rows),
        ))
        rows = []
        for i in range(4):
            rows.append([
                str(i + 1),
                f"{obj1 if i % 2 == 0 else obj2}的{act1 if i % 2 == 0 else act2}",
                rng.choice([p[0] for p in C.PEOPLE]),
                f"2026年{rng.randint(1, 12)}月{rng.randint(1, 28)}日",
            ])
        events.append((
            "任务分工与时限",
            ("table", "表2  会议议定事项分工表", ["序号", "工作事项", "牵头人", "完成时限"], rows),
        ))
    return events


# ---------------------------------------------------------------------------
# 问答锚点
# ---------------------------------------------------------------------------
# 每个锚点返回 (章节关键字, 段落文本, snippet, question, topic)。


def anchor_duty(ctx: DocContext) -> tuple[str, str, str, str, str]:
    snippet = f"{ctx.dept}是公司{ctx.subject.short}的归口管理部门"
    para = snippet + f"，负责{ctx.base['act1']}、{ctx.base['act2']}和{ctx.base['act3']}的组织实施与监督检查。"
    question = f"{ctx.subject.short}的归口管理部门是哪个部门？"
    return "职责", para, snippet, question, f"{ctx.subject.short}/归口部门"


def anchor_approval(ctx: DocContext) -> tuple[str, str, str, str, str]:
    amount = ctx.rng.choice([50, 80, 100, 120, 150, 200])
    snippet = f"单笔金额超过{amount}万元的{ctx.subject.short}{ctx.base['act1']}事项"
    para = snippet + f"，须经{ctx.base['committee']}审议后报{ctx.base['top']}批准。"
    question = f"{ctx.subject.short}单笔金额超过多少万元的事项须经{ctx.base['committee']}审议？"
    return "审批", para, snippet, question, f"{ctx.subject.short}/审批权限"


def anchor_record(ctx: DocContext) -> tuple[str, str, str, str, str]:
    record = ctx.base["record1"]
    # ★ 用文档级常量 `ret`，不要在这里现抽年份：现抽会让同一份文档对同一条记录
    #   写出两个不同的保存期限（实测出现过 14 年 / 16 年并存）。
    years = ctx.base["ret"]
    snippet = f"《{record}》的保存期限不少于{years}年"
    para = f"公司对{ctx.subject.short}相关记录实行统一管理，{snippet}，到期按规定鉴定销毁。"
    question = f"《{record}》的保存期限是多少年？"
    return "记录", para, snippet, question, f"{ctx.subject.short}/记录保存"


def anchor_deadline(ctx: DocContext) -> tuple[str, str, str, str, str]:
    action = ctx.base["act1"]
    days = ctx.rng.randint(3, 20)
    snippet = f"{action}申请自受理之日起{days}个工作日内办结"
    para = snippet + f"，逾期由{ctx.dept}督办。"
    question = f"{ctx.subject.short}的{action}申请应在几个工作日内办结？"
    return "时限", para, snippet, question, f"{ctx.subject.short}/办理时限"


def anchor_sop(ctx: DocContext) -> tuple[str, str, str, str, str]:
    target = ctx.base["obj1"]
    # ★★ 不能写死"停机并切断电源"：这条锚句对**规程族的所有对象**生效，
    #   而对象里有消火栓、电缆、接地网这类根本没有电源的东西 ⇒ 实测产出
    #   "作业前须确认消火栓已停机并切断电源"，读起来是错的。
    #   改成不绑定动力的通用表述（"退出运行 + 隔离 + 警示牌"对机电、消防、管网都成立）。
    snippet = f"作业前须确认{target}已退出运行并落实隔离措施"
    para = snippet + f"，悬挂“禁止操作”警示牌，并确认{ctx.base['obj2']}处于安全状态。"
    question = f"{ctx.subject.short}作业前，{target}应处于什么状态？"
    return "步骤", para, snippet, question, f"{ctx.subject.short}/作业前提"


def anchor_param(ctx: DocContext) -> tuple[str, str, str, str, str]:
    target = ctx.base["obj1"]
    low = ctx.rng.randint(16, 24)
    high = low + ctx.rng.randint(3, 8)
    snippet = f"{target}的正常运行温度应保持在{low}℃至{high}℃"
    para = snippet + f"，相对湿度不大于{ctx.rng.choice([60, 65, 70, 75])}%，并做好运行记录。"
    question = f"{target}的正常运行温度应保持在什么范围？"
    return "参数", para, snippet, question, f"{ctx.subject.short}/运行参数"


def anchor_level(ctx: DocContext) -> tuple[str, str, str, str, str]:
    minutes = ctx.rng.choice([5, 10, 15, 30])
    snippet = f"Ⅰ级响应启动后{minutes}分钟内完成首次报告"
    para = f"发生特别重大{ctx.base['risk1']}时，启动Ⅰ级响应，{snippet}，并同步报告{ctx.base['top']}。"
    question = f"{ctx.subject.short}预案中，Ⅰ级响应启动后应在多少分钟内完成首次报告？"
    return "分级", para, snippet, question, f"{ctx.subject.short}/响应时限"


def anchor_drill(ctx: DocContext) -> tuple[str, str, str, str, str]:
    times = ctx.rng.choice([1, 2, 3, 4])
    snippet = f"应急演练每年不少于{times}次"
    para = snippet + f"，其中综合演练不少于1次，专项演练不少于{times + ctx.rng.randint(0, 2)}次。"
    question = f"{ctx.subject.short}应急预案要求每年至少组织多少次应急演练？"
    return "演练", para, snippet, question, f"{ctx.subject.short}/演练要求"


def anchor_invest(ctx: DocContext) -> tuple[str, str, str, str, str]:
    total = ctx.rng.choice([300, 500, 800, 1200, 1500, 2000, 2600])
    snippet = f"项目总投资{total}万元"
    part1 = int(total * 0.55)
    part2 = int(total * 0.25)
    para = f"经测算，{snippet}，其中设备费{part1}万元、软件费{part2}万元、实施服务费{total - part1 - part2}万元。"
    question = f"{ctx.subject.short}方案的项目总投资是多少万元？"
    return "投资", para, snippet, question, f"{ctx.subject.short}/投资估算"


def anchor_period(ctx: DocContext) -> tuple[str, str, str, str, str]:
    months = ctx.rng.choice([8, 10, 12, 15, 18, 24])
    snippet = f"项目实施周期为{months}个月"
    para = snippet + f"，分{ctx.rng.randint(2, 3)}期建设，一期计划于2026年{ctx.rng.randint(6, 12)}月前上线试运行。"
    question = f"{ctx.subject.short}方案的项目实施周期是多少个月？"
    return "实施计划", para, snippet, question, f"{ctx.subject.short}/实施周期"


def anchor_price(ctx: DocContext) -> tuple[str, str, str, str, str]:
    total = ctx.rng.choice([120, 180, 260, 350, 480, 600])
    snippet = f"本合同总价为人民币{total}万元"
    para = snippet + f"（含税），其中{ctx.base['obj1']}费用{int(total * 0.6)}万元，服务费用{int(total * 0.2)}万元。"
    question = f"{ctx.subject.short}合同的总价是多少万元？"
    return "价款", para, snippet, question, f"{ctx.subject.short}/合同价款"


def anchor_breach(ctx: DocContext) -> tuple[str, str, str, str, str]:
    per_mille = ctx.rng.choice(["0.5", "1", "1.5", "2", "3"])
    cap = ctx.rng.choice([10, 15, 20, 30])
    snippet = f"每逾期一日按合同总价的{per_mille}‰支付违约金"
    para = f"乙方逾期交付的，{snippet}，累计不超过合同总价的{cap}%。"
    question = f"{ctx.subject.short}合同中，乙方逾期交付的违约金按什么标准计算？"
    return "违约", para, snippet, question, f"{ctx.subject.short}/违约责任"


def anchor_warranty(ctx: DocContext) -> tuple[str, str, str, str, str]:
    months = ctx.rng.choice([12, 18, 24, 36])
    snippet = f"质保期为验收合格之日起{months}个月"
    para = f"乙方保证所供{ctx.base['obj1']}为全新未使用的原厂正品，{snippet}。"
    question = f"{ctx.subject.short}合同的质保期是多少个月？"
    return "质量", para, snippet, question, f"{ctx.subject.short}/质保期"


def anchor_metric(ctx: DocContext) -> tuple[str, str, str, str, str]:
    amount = ctx.rng.choice([120, 260, 380, 450, 600, 880, 1200])
    pct = ctx.rng.choice([5, 8, 12, 15, 18, 22, 25])
    snippet = f"累计投入{amount}万元，同比增长{pct}%"
    para = f"报告期内，公司{ctx.subject.short}累计投入{amount}万元，同比增长{pct}%，完成年度预算的{ctx.rng.choice([85, 92, 96, 100, 105])}%。"
    question = f"报告期内公司{ctx.subject.short}累计投入多少万元？"
    return "指标", para, snippet, question, f"{ctx.subject.short}/投入指标"


def anchor_check(ctx: DocContext) -> tuple[str, str, str, str, str]:
    times = ctx.rng.randint(4, 24)
    snippet = f"共组织{ctx.subject.short}专项检查{times}次"
    para = snippet + f"，发现并整改问题{times + ctx.rng.randint(3, 20)}项，整改完成率{ctx.rng.choice([90, 95, 98, 100])}%。"
    question = f"报告期内共组织{ctx.subject.short}专项检查多少次？"
    return "重点", para, snippet, question, f"{ctx.subject.short}/检查频次"


def anchor_hours(ctx: DocContext) -> tuple[str, str, str, str, str]:
    hours = ctx.rng.choice([4, 6, 8, 12, 16])
    theory = ctx.rng.randint(1, hours - 1)
    snippet = f"本课程共{hours}学时"
    para = snippet + f"，其中理论{theory}学时、实操{hours - theory}学时，适用于新入职员工和转岗人员。"
    question = f"{ctx.subject.short}课程共安排多少学时？"
    return "课程", para, snippet, question, f"{ctx.subject.short}/课程学时"


def anchor_pass(ctx: DocContext) -> tuple[str, str, str, str, str]:
    score = ctx.rng.choice([60, 70, 80, 85, 90])
    snippet = f"考核成绩{score}分及以上为合格"
    para = snippet + f"，不合格者须在{ctx.rng.randint(5, 15)}个工作日内完成补考。"
    question = f"{ctx.subject.short}培训考核的合格分数线是多少分？"
    return "考核", para, snippet, question, f"{ctx.subject.short}/考核标准"


def anchor_spec_limit(ctx: DocContext) -> tuple[str, str, str, str, str]:
    count = ctx.rng.choice([3, 5, 8, 10, 20])
    snippet = f"每批次抽取{count}个样品"
    para = f"检验方法：按GB/T {ctx.rng.randint(1000, 9999)}规定的试验方法进行，{snippet}。"
    question = f"{ctx.subject.short}规范要求每批次抽取多少个样品？"
    return "检验", para, snippet, question, f"{ctx.subject.short}/抽样数量"


def anchor_fault(ctx: DocContext) -> tuple[str, str, str, str, str]:
    code = ctx.rng.randint(10, 99)
    target = ctx.base["obj1"]
    snippet = f"故障代码E{code}表示{target}温度过高"
    para = snippet + f"，应检查{ctx.base['obj2']}是否堵塞、风机是否正常运转。"
    question = f"故障代码E{code}表示什么故障？"
    return "故障", para, snippet, question, f"{ctx.subject.short}/故障代码"


def anchor_decision_person(ctx: DocContext) -> tuple[str, str, str, str, str]:
    person = ctx.rng.choice([p[0] for p in C.PEOPLE])
    snippet = f"由{person}牵头"
    para = f"会议明确，{snippet}，{ctx.dept}配合，于{ctx.base['date']}前完成{ctx.base['obj1']}的{ctx.base['act1']}工作。"
    question = f"会议明确{ctx.base['obj1']}的{ctx.base['act1']}工作由谁牵头？"
    return "决定", para, snippet, question, f"{ctx.subject.short}/牵头人"


def anchor_decision_date(ctx: DocContext) -> tuple[str, str, str, str, str]:
    snippet = f"{ctx.base['date']}前完成{ctx.base['obj1']}的{ctx.base['act1']}"
    para = f"会议明确，由{ctx.rng.choice([p[0] for p in C.PEOPLE])}牵头，{snippet}，{ctx.dept}配合。"
    question = f"会议明确{ctx.base['obj1']}的{ctx.base['act1']}工作何时完成？"
    return "分工", para, snippet, question, f"{ctx.subject.short}/完成时限"


#: 每个文档族的问答锚点：(生成器, 章节关键字覆盖)。
#: 关键字覆盖用于"这一族没有同名章节"的情形，避免锚点落到随机章节里读起来很怪。
ANCHORS_BY_FAMILY = {
    "制度": (
        (anchor_duty, None),
        (anchor_approval, None),
        (anchor_record, None),
        (anchor_deadline, None),
    ),
    "规程": ((anchor_sop, None), (anchor_record, None), (anchor_duty, None)),
    "预案": ((anchor_level, None), (anchor_drill, None), (anchor_duty, None)),
    "方案": ((anchor_invest, None), (anchor_period, None), (anchor_duty, "保障")),
    "合同": ((anchor_price, None), (anchor_breach, None), (anchor_warranty, None)),
    "报告": ((anchor_metric, None), (anchor_check, None)),
    "教材": ((anchor_hours, None), (anchor_pass, None), (anchor_sop, "操作要点")),
    "规范": ((anchor_spec_limit, None), (anchor_record, None)),
    "手册": ((anchor_param, None), (anchor_fault, None), (anchor_record, "维护保养")),
    "纪要": ((anchor_decision_person, None), (anchor_decision_date, None)),
}


# ---------------------------------------------------------------------------
# 文档模型
# ---------------------------------------------------------------------------


def block_chars(block: tuple) -> int:
    kind = block[0]
    if kind in ("h1", "h2", "h3", "p"):
        return len(block[1])
    if kind == "list":
        return sum(len(item) for item in block[1])
    if kind == "table":
        _, title, header, rows = block
        return len(title) + sum(len(str(cell)) for cell in header) + sum(
            len(str(cell)) for row in rows for cell in row
        )
    return 0


def uniquify_snippet(ctx: DocContext, para: str, snippet: str) -> tuple[str, str]:
    """让锚点句在全语料内唯一，返回同步改写后的 (para, snippet)。

    不同制度常共用同一台设备/同一个表单（如"高压开关柜"），若锚句只引用对象名，
    两份文档就会产出逐字相同的 snippet ⇒ QA 的 expected_document 出现歧义。
    这里给对象名加上本文档自己的主题限定，既保持自然语感，又保证跨文档唯一。
    """
    for name in ("obj1", "obj2", "obj3"):
        obj = ctx.base.get(name)
        if obj and obj in snippet and obj in para:
            new_snippet = snippet.replace(obj, f"{ctx.subject.short}所涉{obj}", 1)
            return para.replace(snippet, new_snippet, 1), new_snippet
    new_snippet = f"{ctx.subject.short}：{snippet}"
    return para.replace(snippet, new_snippet, 1), new_snippet


def build_document(
    subject: C.Subject,
    index: int,
    used_snippets: set[str] | None = None,
) -> tuple[list[tuple], list[dict], dict]:
    """构建一份文档的 block 序列、问答锚点与元信息。"""
    rng = random.Random(SEED + index)
    code = f"YH-{C.CODE_BY_FAMILY[subject.family]}-2026-{index + 1:03d}"
    ctx = DocContext(subject, rng, code)
    seen: set[str] = set()
    anchors: list[dict] = []

    chapters: list[dict] = []
    article_no = 1
    budget = dict(TYPE_BUDGET)
    for order, (title, types, base_count) in enumerate(C.section_types(subject.family), start=1):
        blocks, article_no = build_chapter_articles(
            ctx, types, base_count, article_no, seen, budget
        )
        chapters.append({"order": order, "title": title, "blocks": blocks})

    # 合同的第一条照例先写当事人，避免开篇就是"乙方承诺其提交的营业执照……"
    if subject.family == "合同":
        parties = (
            f"甲方：{C.COMPANY}，法定代表人：{ctx.base['person1']}，"
            f"住所地：{ctx.base['city1']}市高新区科技大道{ctx.rng.randint(1, 99)}号。"
            f"乙方：{ctx.base['vendor']}，法定代表人：{ctx.base['person2']}，"
            f"住所地：{ctx.base['city2']}市经济技术开发区创业路{ctx.rng.randint(1, 99)}号。"
            f"甲乙双方就{subject.short}有关事项，经平等协商一致，订立本合同。"
        )
        chapters[0]["blocks"].insert(0, ("p", ARTICLE_MARK + parties))

    # 补足字数：只在中间章节轮流追加条文（总则与附则不参与稀释）
    low, high = PARA_TARGET_BY_FAMILY[subject.family]
    target = rng.randint(low, high)
    total = sum(block_chars(b) for ch in chapters for b in ch["blocks"])
    growable = chapters[1:-1] or chapters
    cursor = 0
    while total < target:
        chapter = growable[cursor % len(growable)]
        types = C.section_types(subject.family)[chapter["order"] - 1][1]
        blocks, article_no = build_chapter_articles(
            ctx, types, 1, article_no, seen, budget
        )
        chapter["blocks"].extend(blocks)
        total += sum(block_chars(b) for b in blocks)
        cursor += 1

    # 插表格
    for keyword, table in build_tables(ctx):
        for chapter in chapters:
            if keyword in chapter["title"] or (keyword == "职责" and "职责" in chapter["title"]):
                chapter["blocks"].append(table)
                break
        else:
            chapters[-1]["blocks"].append(table)

    # 插锚点
    for spec in ANCHORS_BY_FAMILY[subject.family]:
        maker, keyword_override = spec
        keyword, para, snippet, question, topic = maker(ctx)
        if used_snippets is not None:
            if snippet in used_snippets:
                para, snippet = uniquify_snippet(ctx, para, snippet)
            if snippet in used_snippets:
                raise AssertionError(
                    f"{subject.title}: 锚点句去重后仍重复 -> {snippet}"
                )
            used_snippets.add(snippet)
        if keyword_override:
            keyword = keyword_override
        target_chapter = None
        for chapter in chapters:
            if keyword in chapter["title"]:
                target_chapter = chapter
                break
        if target_chapter is None:
            target_chapter = chapters[len(chapters) // 2]
        position = rng.randint(0, len(target_chapter["blocks"]))
        target_chapter["blocks"].insert(position, ("p", para))
        anchors.append({
            "snippet": snippet,
            "question": question,
            "topic": topic,
            "chapter": target_chapter["title"],
        })

    # 条号统一重排（必须在全部追加、插表、插锚点之后）
    article_total = renumber_articles(chapters)

    # 组装：封面 + 目录 + 正文
    secrecy = rng.choice(C.SECRECY_LEVELS)
    version = "V1.0"
    issue_date = ctx.base["date"]
    cover: list[tuple] = [
        ("h1", C.COMPANY),
        ("h1", subject.title),
        ("p", f"文件编号：{code}"),
        ("p", f"版　　本：{version}"),
        ("p", f"密　　级：{secrecy}"),
        ("p", f"归口部门：{ctx.dept}"),
        ("p", f"发布日期：{issue_date}"),
        ("table", "文件控制信息", ["项目", "内容"], [
            ["文件名称", subject.title],
            ["文件编号", code],
            ["版　　本", version],
            ["密　　级", secrecy],
            ["归口部门", ctx.dept],
            ["编制部门", ctx.dept],
            ["审 核 人", rng.choice([p[0] for p in C.PEOPLE])],
            ["批 准 人", rng.choice([p[0] for p in C.PEOPLE])],
            ["生效日期", issue_date],
            ["修订状态", rng.choice(C.REVISION_STATES)],
            ["适用单位", f"{C.COMPANY}总部及所属各单位"],
        ]),
        ("h2", "目　录"),
        ("list", [f"第{cn_number(ch['order'])}章　{ch['title']}" for ch in chapters]),
    ]
    body: list[tuple] = []
    for chapter in chapters:
        body.append(("h2", f"第{cn_number(chapter['order'])}章　{chapter['title']}"))
        body.extend(chapter["blocks"])

    blocks = cover + body
    chars = sum(block_chars(b) for b in blocks)
    stats = {
        "code": code,
        "title": subject.title,
        "family": subject.family,
        "subject": subject.short,
        "dept": ctx.dept,
        "chars": chars,
        "chapters": len(chapters),
        "articles": article_total,
        "secrecy": secrecy,
        "version": version,
    }
    return blocks, anchors, stats


# ---------------------------------------------------------------------------
# 渲染
# ---------------------------------------------------------------------------


def render_markdown(blocks: list[tuple]) -> str:
    out: list[str] = []
    for block in blocks:
        kind = block[0]
        if kind == "h1":
            out.append(f"# {block[1]}\n")
        elif kind == "h2":
            out.append(f"## {block[1]}\n")
        elif kind == "h3":
            out.append(f"### {block[1]}\n")
        elif kind == "p":
            out.append(f"{block[1]}\n")
        elif kind == "list":
            out.extend(f"- {item}\n" for item in block[1])
            out.append("")
        elif kind == "table":
            _, title, header, rows = block
            out.append(f"**{title}**\n")
            out.append("| " + " | ".join(header) + " |")
            out.append("| " + " | ".join("---" for _ in header) + " |")
            for row in rows:
                out.append("| " + " | ".join(str(cell) for cell in row) + " |")
            out.append("")
    return "\n".join(out)


def render_text(blocks: list[tuple]) -> str:
    out: list[str] = []
    for block in blocks:
        kind = block[0]
        if kind == "h1":
            out.append(block[1])
            out.append("=" * min(40, max(8, len(block[1]) * 2)))
        elif kind == "h2":
            out.append("")
            out.append(block[1])
            out.append("-" * 30)
        elif kind == "h3":
            out.append(block[1])
        elif kind == "p":
            out.append(block[1])
            out.append("")
        elif kind == "list":
            out.extend(f"  {item}" for item in block[1])
            out.append("")
        elif kind == "table":
            _, title, header, rows = block
            out.append(title)
            out.append(" | ".join(header))
            for row in rows:
                out.append(" | ".join(str(cell) for cell in row))
            out.append("")
    return "\n".join(out) + "\n"


def render_docx(blocks: list[tuple], path: Path, title: str) -> None:
    from docx import Document
    from docx.enum.text import WD_ALIGN_PARAGRAPH
    from docx.oxml.ns import qn
    from docx.shared import Pt

    document = Document()
    normal = document.styles["Normal"]
    normal.font.name = "宋体"
    normal.font.size = Pt(10.5)
    normal.element.rPr.rFonts.set(qn("w:eastAsia"), "宋体")

    for block in blocks:
        kind = block[0]
        if kind == "h1":
            paragraph = document.add_paragraph()
            run = paragraph.add_run(block[1])
            run.bold = True
            run.font.size = Pt(18)
            paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
        elif kind == "h2":
            paragraph = document.add_heading(level=1)
            run = paragraph.add_run(block[1])
            run.font.size = Pt(15)
            run.font.name = "黑体"
            run.element.rPr.rFonts.set(qn("w:eastAsia"), "黑体")
            run.font.color.rgb = None
        elif kind == "h3":
            paragraph = document.add_heading(level=2)
            run = paragraph.add_run(block[1])
            run.font.size = Pt(12)
        elif kind == "p":
            paragraph = document.add_paragraph(block[1])
            paragraph.paragraph_format.first_line_indent = Pt(21)
            paragraph.paragraph_format.line_spacing = 1.5
        elif kind == "list":
            for item in block[1]:
                document.add_paragraph(item, style="List Bullet")
        elif kind == "table":
            _, table_title, header, rows = block
            document.add_paragraph(table_title)
            table = document.add_table(rows=1, cols=len(header))
            table.style = "Table Grid"
            for index, name in enumerate(header):
                cell = table.rows[0].cells[index]
                cell.text = str(name)
                for run in cell.paragraphs[0].runs:
                    run.bold = True
            for row in rows:
                cells = table.add_row().cells
                for index, value in enumerate(row):
                    cells[index].text = str(value)

    document.core_properties.title = title
    document.core_properties.author = C.COMPANY
    document.save(str(path))


PDF_CSS = """
@page { size: A4; margin: 2.0cm 2.2cm; }
body { font-family: "Microsoft YaHei", "SimSun", serif; font-size: 11.5pt;
       line-height: 1.9; text-align: left; color: #111; }
h1 { font-size: 17pt; text-align: center; margin: 6pt 0 10pt; }
h2 { font-size: 13.5pt; margin: 18pt 0 8pt; }
p  { margin: 0 0 6pt; text-indent: 2em; }
table { border-collapse: collapse; width: 100%; font-size: 9.5pt; margin: 6pt 0 10pt; }
caption { caption-side: top; text-align: left; font-weight: bold; padding-bottom: 3pt; }
th, td { border: 1px solid #333; padding: 3pt 5pt; line-height: 1.5; text-indent: 0; }
ul { margin: 0 0 8pt 1.4em; }
li { margin: 2pt 0; }
"""


def _html_escape(text: str) -> str:
    return (
        str(text)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


def render_pdf(blocks: list[tuple], path: Path, title: str, chrome: str, workdir: Path) -> None:
    parts: list[str] = []
    for block in blocks:
        kind = block[0]
        if kind == "h1":
            parts.append(f"<h1>{_html_escape(block[1])}</h1>")
        elif kind == "h2":
            parts.append(f"<h2>{_html_escape(block[1])}</h2>")
        elif kind == "h3":
            parts.append(f"<h3>{_html_escape(block[1])}</h3>")
        elif kind == "p":
            parts.append(f"<p>{_html_escape(block[1])}</p>")
        elif kind == "list":
            items = "".join(f"<li>{_html_escape(item)}</li>" for item in block[1])
            parts.append(f"<ul>{items}</ul>")
        elif kind == "table":
            _, table_title, header, rows = block
            head = "".join(f"<th>{_html_escape(cell)}</th>" for cell in header)
            body = "".join(
                "<tr>" + "".join(f"<td>{_html_escape(cell)}</td>" for cell in row) + "</tr>"
                for row in rows
            )
            parts.append(
                f"<table><caption>{_html_escape(table_title)}</caption>"
                f"<thead><tr>{head}</tr></thead><tbody>{body}</tbody></table>"
            )
    html = (
        "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
        f"<title>{_html_escape(title)}</title><style>{PDF_CSS}</style></head><body>"
        + "".join(parts)
        + "</body></html>"
    )
    html_path = workdir / (path.stem + ".html")
    html_path.write_text(html, encoding="utf-8")
    # 用户浏览器在跑时，新起的 chrome.exe 会把命令转交给既有实例并立刻返回 1，
    # 真正干活的子进程稍后才写出 PDF —— 所以既要独立 profile，也要轮询产物。
    profile_dir = workdir / "chrome-profile"
    command = [
        chrome,
        "--headless=new",
        "--disable-gpu",
        "--no-pdf-header-footer",
        "--disable-extensions",
        f"--user-data-dir={profile_dir}",
        "--virtual-time-budget=5000",
        f"--print-to-pdf={path}",
        html_path.as_uri(),
    ]
    completed = subprocess.run(command, capture_output=True, timeout=300)
    deadline = time.time() + 180
    last_size = -1
    while time.time() < deadline:
        if path.exists():
            size = path.stat().st_size
            if size > 0 and size == last_size:
                break
            last_size = size
        time.sleep(0.5)
    if not path.exists() or path.stat().st_size == 0:
        raise RuntimeError(
            f"Chrome 生成 PDF 失败（{path.name}）："
            f"exit={completed.returncode} "
            f"{completed.stderr.decode('utf-8', 'replace')[:500]}"
        )
    html_path.unlink(missing_ok=True)


# ---------------------------------------------------------------------------
# 校验
# ---------------------------------------------------------------------------


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def squash(text: str) -> str:
    """去掉全部空白后再比对。

    PDF 文本层会在行尾、字间插入空白（中文正文里没有有意义空格的），
    因此"逐字子串"判据必须按去空白后比对 —— `corpus/README.md` 写的也是这一条。
    """
    return re.sub(r"\s+", "", text)


def pdf_text(path: Path) -> str:
    from pypdf import PdfReader

    reader = PdfReader(str(path), strict=False)
    return "\n".join((page.extract_text() or "") for page in reader.pages)


def docx_text(path: Path) -> str:
    from docx import Document

    document = Document(str(path))
    chunks: list[str] = []
    for paragraph in document.paragraphs:
        chunks.append(paragraph.text)
    for table in document.tables:
        for row in table.rows:
            chunks.append("\t".join(cell.text for cell in row.cells))
    return "\n".join(chunks)


def find_chrome() -> str:
    for candidate in CHROME_CANDIDATES:
        if Path(candidate).exists():
            return candidate
    found = shutil.which("chrome") or shutil.which("chromium")
    if found:
        return found
    raise RuntimeError("未找到 Chrome，可用 --no-pdf 跳过 PDF 生成")


def format_for(index: int, total: int, rng: random.Random) -> str:
    return FORMAT_CYCLE[index % len(FORMAT_CYCLE)]


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------


def synthetic_readme(manifest: dict) -> str:
    """给生成目录写一份说明。

    ★ 为什么生成物里也要放一份：这个目录**会被拿去当"语料"用**，
      而它长得和真实语料一模一样。不给它贴标签，迟早有人把它的读数写进验收台账。
    """
    totals = manifest["totals"]
    by_format = totals["by_format"]
    return f"""# 合成语料（**不是** P1 语料）

> 本目录由 `tools/gen_corpus.py` 生成，**不要手工修改** —— 改了就与 `manifest.json` 的
> 字数/哈希对不上，`tools/check_corpus.py` 会红。

- 虚构主体：{C.COMPANY}（与任何真实企业无关）
- 随机种子：`{manifest["seed"]}`（同种子重跑得到同一批内容）
- 规模：**{totals["documents"]} 份 / {totals["chars"]:,} 字 / {totals["questions"]} 条标注问题**
- 格式：{", ".join(f"{k} {v} 份" for k, v in sorted(by_format.items()))}

## 它是什么

`docs/系统设计方案/04-MVP最小验证系统设计.md §6.1` 要的是 **P1 真实语料**（≥50 份真实业务文档 +
≥50 个真实问题 + ≥2 名业务评审人）。真实语料收齐之前，先用一批**同形状的合成文档**把
「上传 → 解析 → 切分 → 向量化 → 检索 → 引用比对」这条链路跑起来。

## ★ 读数纪律

用它跑出来的任何数字，**必须写成「合成语料读数」**，不许当成 B1–B7 的验收结论：

- **B1 命中率会虚高**：问题与文档出自同一套模板，措辞高度雷同；
- **B2 答案可用率读不出**：那是人工判定，需要业务评审人；
- **B3** 只能验"比对口径实现对了"，验不了"该不该引这一段"。

## 怎么用

```bash
python tools/check_corpus.py                                # 离线校验（字数/格式/snippet 逐字且唯一）
python scripts/parse_corpus.py --corpus corpus/synthetic    # 真栈：上传 → 解析 → 向量化 → 问答核对
```

## 文件

| 文件 | 说明 |
|---|---|
| `documents/` | {totals["documents"]} 份文档，`qa.jsonl` 的 `expected_document` 与这里的文件名逐字一致 |
| `qa.jsonl` | {totals["questions"]} 条，字段与 `corpus/qa.sample.jsonl` 同口径（`id` 形如 `SYN-001-1`） |
| `manifest.json` | 每份文档的 `sha256` / 字数 / 格式 / 锚点；`"kind": "synthetic"`、`"is_p1_corpus": false` |
| `.build/` | 生成过程的临时目录（Chrome profile 等），可以整个删掉 |

## 已知缺陷

模板句池有限（合同 8–10 句/类、纪要 9 句/类），同一份文档内会出现"同一模板换数字"的近似重复。
对"链路通不通"无影响，对"答得好不好"有影响。
"""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="生成 P1 形状的合成测试语料")
    parser.add_argument("--docs", type=int, default=0, help="只生成前 N 份（冒烟用）")
    parser.add_argument("--no-pdf", action="store_true", help="跳过 PDF 生成")
    parser.add_argument("--out", default=str(OUT_DIR), help="输出目录")
    args = parser.parse_args(argv)

    out_dir = Path(args.out)
    doc_dir = out_dir / DOC_DIR_NAME
    if doc_dir.exists():
        shutil.rmtree(doc_dir)
    doc_dir.mkdir(parents=True, exist_ok=True)
    workdir = out_dir / ".build"
    workdir.mkdir(parents=True, exist_ok=True)

    chrome = None if args.no_pdf else find_chrome()

    subjects = list(C.SUBJECTS)
    if args.docs:
        subjects = subjects[: args.docs]

    qa_rows: list[dict] = []
    manifest_docs: list[dict] = []
    snippet_owner: dict[str, str] = {}
    snippet_seen: set[str] = set()
    total_chars = 0
    per_format: dict[str, int] = {}

    for index, subject in enumerate(subjects):
        rng = random.Random(SEED + index)
        blocks, anchors, stats = build_document(subject, index, snippet_seen)
        fmt = format_for(index, len(C.SUBJECTS), rng)
        if args.no_pdf and fmt == "pdf":
            fmt = "md"
        filename = f"{stats['code']}_{subject.title}.{fmt}"
        path = doc_dir / filename

        if fmt == "md":
            path.write_text(render_markdown(blocks), encoding="utf-8")
        elif fmt == "txt":
            path.write_text(render_text(blocks), encoding="utf-8")
        elif fmt == "docx":
            render_docx(blocks, path, subject.title)
        elif fmt == "pdf":
            render_pdf(blocks, path, subject.title, chrome or "", workdir)
        else:
            raise ValueError(fmt)

        # -- 自检：正文内的锚点必须存在 -----------------------------------
        body_text = squash("\n".join(
            block[1] if block[0] in ("h1", "h2", "h3", "p") else ""
            for block in blocks
        ))
        for anchor in anchors:
            if squash(anchor["snippet"]) not in body_text:
                raise AssertionError(
                    f"{filename}: 锚点不在正文中 -> {anchor['snippet']}"
                )
            if len(anchor["snippet"]) > 40:
                raise AssertionError(
                    f"{filename}: 锚点过长（{len(anchor['snippet'])} 字） -> {anchor['snippet']}"
                )
            if anchor["snippet"] in snippet_owner:
                raise AssertionError(
                    f"{filename}: 锚点与其他文档重复 -> {anchor['snippet']}"
                    f"（已属于 {snippet_owner[anchor['snippet']]}）"
                )
            snippet_owner[anchor["snippet"]] = filename

        # -- 抽取校验 -----------------------------------------------------
        extracted = None
        if fmt == "pdf":
            extracted = pdf_text(path)
        elif fmt == "docx":
            extracted = docx_text(path)
        else:
            extracted = path.read_text(encoding="utf-8")
        for anchor in anchors:
            if squash(anchor["snippet"]) not in squash(extracted):
                raise AssertionError(
                    f"{filename}: 抽取文本中未找到锚点 -> {anchor['snippet']}"
                    "（渲染路径丢了字或换了字形，下游 B3 会误判）"
                )

        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        stats.update({
            "name": filename,
            "format": fmt,
            "bytes": path.stat().st_size,
            "sha256": digest,
        })
        total_chars += stats["chars"]
        per_format[fmt] = per_format.get(fmt, 0) + 1
        manifest_docs.append({**stats, "anchors": [a["snippet"] for a in anchors]})

        base = stats["code"]
        for anchor_index, anchor in enumerate(anchors, start=1):
            qa_rows.append({
                "id": f"SYN-{stats['code'].split('-')[-1]}-{anchor_index}",
                "question": anchor["question"],
                "expected_document": filename,
                "expected_snippet": anchor["snippet"],
                "expected_topic": anchor["topic"],
                "notes": f"合成语料；锚点位于「{anchor['chapter']}」；seed={SEED}",
            })
        print(f"[{index + 1:02d}/{len(subjects):02d}] {filename}  {stats['chars']:,} 字  "
              f"{stats['chapters']} 章 / {stats['articles']} 条 / {len(anchors)} 问答")

    # -- 写出 qa.jsonl / manifest.json -----------------------------------
    with (out_dir / "qa.jsonl").open("w", encoding="utf-8", newline="\n") as handle:
        for row in qa_rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")

    manifest = {
        "kind": "synthetic",
        "is_p1_corpus": False,
        "generator": "tools/gen_corpus.py",
        "content_library": "tools/corpus_content.py",
        "seed": SEED,
        "company": f"{C.COMPANY}（虚构主体，与任何真实企业无关）",
        "note": "合成语料，仅用于打通链路与回归读数，不能替代 docs/系统设计方案/04 §6.1 的 P1 真实语料",
        "totals": {
            "documents": len(manifest_docs),
            "chars": total_chars,
            "questions": len(qa_rows),
            "by_format": per_format,
        },
        "documents": manifest_docs,
    }
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (out_dir / "README.md").write_text(
        synthetic_readme(manifest), encoding="utf-8", newline="\n"
    )

    print()
    print(f"文档 {len(manifest_docs)} 份 / 合计 {total_chars:,} 字 / 问答 {len(qa_rows)} 条")
    print(f"格式分布：{per_format}")
    print(f"输出目录：{out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
