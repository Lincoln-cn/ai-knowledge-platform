"""#57 共享主体 `ROLE` 的**取值口径**静态断言 —— 断言两条路径解析到**同一个主体键**。

纯静态：不启 dev server、不开浏览器、不联网。
用法：python check_role_subject_key.py
退出码：0 = 全绿；1 = 有红。

━━━ 为什么要有这一条 ━━━
同一条共享授权里 `subject.kind = ROLE` 时，`subject.id` 放**角色 code** 还是**角色 id**，
本仓一度给出两个答案：库列注释（`V12`）、平台值对象 `KnowledgeBaseGrant`、后端校验
`GrantSubjectAdapter` 三处写 **code**；而前端 mock 的判定侧（`policy.ts` 把角色主体建成
`id: role.id`）、授权夹具（`r4`）、界面（`ShareDialog` 传 `r.id`）三处写 **id**。

★ 症状是**静默不命中**：主体键是 `kind:id`，界面按 code 传、判定按 id 比 ⇒
「共享给角色」永远不生效，而它的表现与「没共享过」一模一样（本项目头号病 `silent-failure`）。

━━━ 判据（对应 Issue #57 的四条）━━━
  ① 判定侧构造角色主体用 `role.code`（`subjectKey` 是 `kind:id` ⇒ 这就是"解析到哪个主体"）；
  ② 界面回写与选项值用 code（不是 `r.id`）、且**变量名也不叫 `roleIds`**（口径要能一眼看出来）；
  ③ 授权夹具里的 `ROLE` 主体 id 是**角色 code**（与 `roles` 夹具里某个角色的 `code` 对得上）；
  ④ mock 的写侧存在性校验也按 `code` —— 否则界面传 code 会被它 400 拒掉（比静默好，但同样是错的）。

★ 为什么不是"跑一遍看能不能匹配"：那需要后端助手写端点（`/assistants` 整个域仍未实现）。
本闸门钉的是**口径**这一层 —— 它正是"两端各自完成、合起来不工作"能发生的地方。
★ 不许为了让这条变绿而削弱它：任一档被删，都得先说清"为什么这个判据不再成立"。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"

POLICY = SRC / "mock" / "policy.ts"
ASSISTANTS_MOCK = SRC / "mock" / "handlers" / "assistants.ts"
GRANTS_FIXTURE = SRC / "mock" / "fixtures" / "grants.ts"
ROLES_FIXTURE = SRC / "mock" / "fixtures" / "identity.ts"
SHARE_DIALOG = SRC / "pages" / "assistants" / "components" / "ShareDialog.vue"

problems: list[str] = []


def fail(msg: str) -> None:
    problems.append(msg)


def read(path: Path) -> str:
    if not path.is_file():
        fail(f"读不到文件：{path.relative_to(ROOT)} —— 本闸门不接受『文件不在』")
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def strip_comments(text: str) -> str:
    """去掉 `//`、`/* */` 与 `<!-- -->`，保留行号。

    ★ 必须做：判据是"代码里怎么写的"。注释里写一句 `role.code` 就把红判成绿，
      那正是"为了变绿而削弱断言"的形状（`check_deeplink_segments.py` 同款做法）。
    """
    text = re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def main() -> int:
    # ── ① 判定侧：角色主体必须用 code ──
    policy = strip_comments(read(POLICY))
    if not re.search(r"kind:\s*'ROLE'\s*,\s*id:\s*role\.code", policy):
        fail(
            "① 判定侧没有用 `role.code` 构造角色主体（`policy.ts` 的 `subjectsOf`）"
            " —— `subjectKey` 是 `kind:id`，用 `role.id` 会让界面按 code 传的授权**静默不命中**"
        )
    if re.search(r"kind:\s*'ROLE'\s*,\s*id:\s*role\.id", policy):
        fail("① 判定侧仍在用 `role.id` 构造角色主体 —— 那正是 #57 的根因")

    # ── ② 界面：选项值/回写用 code，且变量名不叫 roleIds ──
    dialog = strip_comments(read(SHARE_DIALOG))
    if not re.search(r":value=\"r\.code\"", dialog):
        fail("② 共享对话框的角色选项没有绑 `r.code`（`ShareDialog.vue`）—— 传 `r.id` 是 #57 的界面侧")
    if re.search(r":value=\"r\.id\"", dialog):
        fail("② 角色选项仍绑着 `r.id`")
    if re.search(r"\broleIds\b", dialog):
        fail("② 变量名仍叫 `roleIds` —— 口径要能一眼看出来；改成 `roleCodes`（否则下一个人会照抄按 id 传）")

    # ── ③ 授权夹具：ROLE 主体的 id 必须是真角色 code ──
    fixture = strip_comments(read(GRANTS_FIXTURE))
    roles_src = strip_comments(read(ROLES_FIXTURE))
    codes = set(re.findall(r"code:\s*'([A-Z_]+)'", roles_src))
    role_ids = set(re.findall(r"\bid:\s*'(r\d+)'", roles_src))
    fixture_role_ids = re.findall(r"kind:\s*'ROLE'\s*,\s*id:\s*'([^']+)'", fixture)
    if not fixture_role_ids:
        fail("③ 授权夹具里找不到任何 `kind: 'ROLE'` 主体 —— 夹具形状变了，本闸门失去落点")
    for value in fixture_role_ids:
        if value in role_ids:
            fail(
                f"③ 授权夹具里的角色主体 id = `{value}`，而它是**mock 内部角色 id**，不是角色 code"
                f"（真 code 形如 {sorted(codes)[:3]}）—— 这会让共享给角色静默不命中"
            )
        elif codes and value not in codes:
            fail(f"③ 授权夹具里的角色主体 id = `{value}` 不是 `roles` 夹具里的任何 code（{sorted(codes)}）")

    # ── ④ mock 写侧：存在性校验按 code ──
    mock = strip_comments(read(ASSISTANTS_MOCK))
    if not re.search(r"roles\.some\(\(r\)\s*=>\s*r\.code\s*===\s*id\)", mock):
        fail(
            "④ mock 的共享写侧没有按 `r.code` 校验角色存在（`assistants.ts`）"
            " —— 它会 400 拒掉界面按 code 传的授权"
        )
    if re.search(r"roles\.some\(\(r\)\s*=>\s*r\.id\s*===\s*id\)", mock):
        fail("④ mock 的共享写侧仍在按 `r.id` 校验角色")

    print("== #57 共享主体 ROLE 取值口径 ==")
    print(f"  角色夹具里的 code：{sorted(codes)}")
    print(f"  授权夹具里的 ROLE 主体 id：{fixture_role_ids}")
    if problems:
        print(f"\n结果：红 —— {len(problems)} 条问题")
        for p in problems:
            print(f"  [FAIL] {p}")
        return 1
    print("\n结果：绿 —— 判定侧与界面/夹具/mock 都在**角色 code** 这一个口径上")
    return 0


if __name__ == "__main__":
    sys.exit(main())
