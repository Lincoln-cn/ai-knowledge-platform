#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""branch_hygiene.py -- 已合并分支清理 + 「有问题的上位合并」梳理

背景（本仓实测，2026-10-16）
    主线 `origin/main` 是**纯 squash 历史**（170 个提交、0 个 merge 提交）；
    于是 `git branch --merged origin/main` **只认得 main 自己** —— 42 条已经合并过的分支
    在 git 眼里全是"未合并"。这就是本工具存在的理由：判定已合并**不能只靠 ancestry**。

判定口径（证据梯度，强 -> 弱）
    E1 ancestry      分支 tip 是 base 的祖先                    -> `git branch -d` 就能删（真合并）
    E2 squash-by-pr  PR 已合并 且 分支 tip == PR 的 headOid     -> tip 的全部内容确定进了主线（需 -D）
    E3 content-only  `git cherry <base> <branch>` 无 '+'（补丁全等价）-> 内容已在主线，但没有 PR 记录
    E4 其余                                                     -> 一律不自动删

「有问题的上位合并」（P 类，**只梳理、默认一律禁删**）
    P1  SQUASH_BLIND      squash 合并让 ancestry 永久失效（本仓的常态）
    P2  TIP_AFTER_MERGE   PR 合并之后分支又被推了新提交（tip != PR headOid）
    P3  STACKED_BASE      PR 的 base 不是主线（叠加 PR）；该 base 已被 squash 合并/废弃
    P4  UPSTREAM_GONE     upstream 已消失（[gone]）：远端已删、本地残留
    P5  UPSTREAM_IS_MAIN  upstream 指向主线 -> 一条 `git push` 就可能直推主线
    P6  HELD_BY_WORKTREE  分支被别的 worktree 检出（删不掉，先 worktree remove）
    P7  ORPHAN_UNMERGED   有独有提交、且主线里没有等价补丁（真未合并 / 孤儿工作）
    P8  MERGED_INTO_OTHER 分支 tip 是某个**非主线**分支的祖先（上位合并到了别的分支上）
    P9  MERGE_COMMITS     分支自身带 merge 提交（上位合并痕迹：将来 cherry-pick 会复活旧改动）
    P10 BASE_BEHIND       本地主线落后 origin（先 fetch/fast-forward 再判定，否则读数假阴）
    P11 REMOTE_LEFT       已合并 PR 的 head 分支还留在 origin（本仓实测 39 条）
    P12 STALE             最后提交超过 --stale-days

安全网
    ★ 删除前默认先打归档 tag：本地分支 -> `archive/branch/<name>/<时间戳>`，
      远端分支 -> `archive/remote/<name>/<时间戳>`（远端那一条尤其重要：远端 ref 一删，
      那个 commit 在本仓可能就只剩这条 tag 引用了）。
      ⇒ 删除是可逆的（`git branch <name> <tag>` 一条命令还原），
        所以 `-D` 才是可以接受的；`--no-archive` 会关掉这层保护，请三思。

用法
    python tools/branch_hygiene.py                       # 体检 + 出报告（不改任何东西）
    python tools/branch_hygiene.py --check               # 闸门模式：有阻断项则退出码 1
    python tools/branch_hygiene.py --apply               # 真删 E1（ancestry 真合并）
    python tools/branch_hygiene.py --apply --force       # 连 E2（squash 合并）一起删
    python tools/branch_hygiene.py --apply --force --prune-remote   # 连远端 head 分支一起删
    python tools/branch_hygiene.py --selftest             # 造 12 类样本，证明判定能红

退出码
    0 = 正常（体检模式恒 0，除非自身出错）
    1 = `--check` 模式下存在阻断项 / `--selftest` 有类别没被检出
    2 = 环境或参数错误（不是 git 仓库、base 不存在、gh 不可用且未给 --offline）
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

GIT = "git"
GH = "gh"
SEP = "\x1f"

DEFAULT_PROTECT = ["main", "master", "develop", "release/*", "backup/*", "archive/*"]

# 证据梯度
E_ANCESTRY = "E1-ancestry"
E_SQUASH_PR = "E2-squash-by-pr"
E_CONTENT = "E3-content-only"
E_NONE = "E4-unproven"

# P 类问题（"有问题的上位合并"）
#   P_BLOCKING = 命中即禁删（内容可能还没进主线）
#   P_ADVISORY = 只提示（内容已确定进了主线，但姿势有问题 / 值得记一笔）
P_BLOCKING = [
    "P2-TIP_AFTER_MERGE",
    "P3-STACKED_BASE",
    "P7-ORPHAN_UNMERGED",
]
P_ADVISORY = [
    "P1-SQUASH_BLIND",
    "P4-UPSTREAM_GONE",
    "P5-UPSTREAM_IS_MAIN",
    "P6-HELD_BY_WORKTREE",
    "P8-MERGED_INTO_OTHER",
    "P9-MERGE_COMMITS",
    "P10-BASE_BEHIND",
    "P11-REMOTE_LEFT",
    "P12-STALE",
]


# --------------------------------------------------------------------------
# 进程层
# --------------------------------------------------------------------------
def run(args, cwd=None):
    """跑一条命令，返回 (rc, stdout, stderr)；解码失败不抛异常（中文分支名/路径要稳）。"""
    p = subprocess.run(args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    out = p.stdout.decode("utf-8", "replace")
    err = p.stderr.decode("utf-8", "replace")
    return p.returncode, out, err


def git(repo, *args):
    return run([GIT, "-C", repo, *args])


def git_out(repo, *args):
    rc, out, _ = git(repo, *args)
    return out.strip() if rc == 0 else ""


def git_ok(repo, *args):
    rc, _, _ = git(repo, *args)
    return rc == 0


# --------------------------------------------------------------------------
# 采集
# --------------------------------------------------------------------------
def find_repo(start):
    rc, out, _ = run([GIT, "-C", start, "rev-parse", "--show-toplevel"])
    return out.strip() if rc == 0 else None


def resolve_base(repo, wanted):
    for cand in (wanted, "origin/main", "main", "origin/master", "master"):
        if cand and git_ok(repo, "rev-parse", "--verify", "--quiet", cand + "^{commit}"):
            return cand
    return None


def list_local_refs(repo):
    fmt = SEP.join([
        "%(refname:short)", "%(objectname)", "%(upstream:short)",
        "%(upstream:track)", "%(committerdate:short)", "%(subject)",
    ])
    rc, out, _ = git(repo, "for-each-ref", "--sort=refname", "--format=" + fmt, "refs/heads/")
    refs = []
    if rc != 0:
        return refs
    for line in out.splitlines():
        if not line.strip():
            continue
        parts = (line.split(SEP) + [""] * 6)[:6]
        refs.append({
            "name": parts[0], "tip": parts[1], "upstream": parts[2],
            "track": parts[3], "last_commit_date": parts[4], "subject": parts[5],
        })
    return refs


def list_remote_refs(repo, remote):
    prefix = "refs/remotes/%s/" % remote
    fmt = SEP.join(["%(refname)", "%(objectname)", "%(committerdate:short)"])
    rc, out, _ = git(repo, "for-each-ref", "--sort=refname", "--format=" + fmt, prefix)
    refs = []
    if rc != 0:
        return refs
    for line in out.splitlines():
        if not line.strip():
            continue
        parts = (line.split(SEP) + [""] * 3)[:3]
        full, tip, date = parts
        if full.endswith("/HEAD"):
            continue
        # ★ 名字从完整 refname 上切，不用 %(refname:short) —— 后者在同名本地分支存在时
        #   可能被 git 归一成别的形状（实测出现过空串），会把远端分支名读错。
        name = full[len(prefix):] if full.startswith(prefix) else full
        if not name:
            continue
        refs.append({
            "name": name, "ref": full, "tip": tip, "last_commit_date": date,
        })
    return refs


def list_worktrees(repo):
    rc, out, _ = git(repo, "worktree", "list", "--porcelain")
    trees, cur = [], None
    if rc != 0:
        return trees
    for line in out.splitlines():
        if line.startswith("worktree "):
            if cur:
                trees.append(cur)
            cur = {"path": line[len("worktree "):], "branch": None, "detached": False}
        elif line.startswith("branch ") and cur is not None:
            b = line[len("branch "):]
            cur["branch"] = b[len("refs/heads/"):] if b.startswith("refs/heads/") else b
        elif line.startswith("detached") and cur is not None:
            cur["detached"] = True
    if cur:
        trees.append(cur)
    return trees


def current_branch(repo):
    return git_out(repo, "rev-parse", "--abbrev-ref", "HEAD")


def gh_pr_list(repo, state, limit=1000):
    """用 gh 拉 PR；失败返回 (None, 原因)。"""
    args = [GH, "pr", "list", "--state", state, "--limit", str(limit),
            "--json", "number,headRefName,baseRefName,mergedAt,closedAt,url,headRefOid,isDraft"]
    rc, out, err = run(args, cwd=repo)
    if rc != 0:
        return None, (err.strip() or "gh pr list 退出码 %d" % rc)
    try:
        return json.loads(out), None
    except Exception as exc:  # noqa: BLE001
        return None, "解析 gh 输出失败：%s" % exc


def gh_repo_settings(repo):
    """取 delete_branch_on_merge 等设置；失败返回 (None, 原因)。"""
    rc, out, _ = run([GH, "repo", "view", "--json", "nameWithOwner,defaultBranchRef"], cwd=repo)
    if rc != 0:
        return None, "gh repo view 失败"
    try:
        info = json.loads(out)
    except Exception as exc:  # noqa: BLE001
        return None, "解析 gh repo view 失败：%s" % exc
    owner = info.get("nameWithOwner", "")
    rc2, out2, _ = run([GH, "api", "repos/%s" % owner,
                        "--jq", "{delete_branch_on_merge,allow_squash_merge,allow_merge_commit}"], cwd=repo)
    settings = {}
    if rc2 == 0:
        try:
            settings = json.loads(out2)
        except Exception:  # noqa: BLE001
            settings = {}
    settings["nameWithOwner"] = owner
    settings["defaultBranch"] = (info.get("defaultBranchRef") or {}).get("name")
    return settings, None


# --------------------------------------------------------------------------
# PR 证据缓存（gh 调用是本工具最慢的一段：两三条命令要好几秒，缓存让它只付一次）
# --------------------------------------------------------------------------
def load_pr_cache(path, ttl, repo_key):
    try:
        with open(path, encoding="utf-8") as fh:
            c = json.load(fh)
    except Exception:  # noqa: BLE001
        return None
    if c.get("repo_key") != repo_key:
        return None
    if ttl >= 0:
        try:
            age = (datetime.now() - datetime.fromisoformat(c["fetched_at"])).total_seconds()
        except Exception:  # noqa: BLE001
            return None
        if age > ttl:
            return None
    return c


def save_pr_cache(path, repo_key, merged, opened, settings):
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({
                "fetched_at": datetime.now().isoformat(timespec="seconds"),
                "repo_key": repo_key, "merged": merged, "open": opened,
                "settings": settings or {},
            }, fh, ensure_ascii=False, indent=2)
    except Exception:  # noqa: BLE001
        pass


# --------------------------------------------------------------------------
# 每条分支的事实与判定
# --------------------------------------------------------------------------
def branch_facts(repo, base, ref, merged_prs, remote):
    name, tip = ref["name"], ref["tip"]
    # ahead/behind 一次问清（`--left-right --count a...b` 比两次 rev-list 省一半进程）
    lr = git_out(repo, "rev-list", "--left-right", "--count", "%s...%s" % (base, name)).split()
    behind, ahead = (int(lr[0]), int(lr[1])) if len(lr) == 2 else (0, 0)
    f = {
        "tip": tip,
        "is_ancestor_of_base": git_ok(repo, "merge-base", "--is-ancestor", tip, base),
        "ahead": ahead,
        "behind": behind,
        "merge_commits": len([x for x in git_out(repo, "log", "--merges", "--oneline",
                                                 "%s..%s" % (base, name)).splitlines() if x.strip()]),
    }
    # git cherry：'-' = 主线已有等价补丁，'+' = 主线里没有
    rc, out, _ = git(repo, "cherry", base, name)
    plus, minus = [], []
    if rc == 0:
        for line in out.splitlines():
            if line.startswith("+"):
                plus.append(line[2:].strip())
            elif line.startswith("-"):
                minus.append(line[2:].strip())
    f["cherry_plus"] = plus
    f["cherry_minus"] = minus
    f["unique_commits"] = len(plus)

    pr = merged_prs.get(name)
    f["pr_number"] = pr.get("number") if pr else None
    f["pr_url"] = pr.get("url") if pr else None
    f["pr_base"] = pr.get("baseRefName") if pr else None
    f["pr_merged_at"] = pr.get("mergedAt") if pr else None
    f["pr_head_oid"] = pr.get("headRefOid") if pr else None
    f["tip_moved_after_merge"] = bool(pr and pr.get("headRefOid") and pr["headRefOid"] != tip)

    # 该分支的 tip 是否是「别的（非主线）分支」的祖先 —— 上位合并到了别的分支上
    f["_merged_into_other"] = None  # 由 analyze 统一算，避免 O(n^2) 放这里
    return f


def evidence_of(f):
    if f["is_ancestor_of_base"]:
        return E_ANCESTRY
    if f["pr_number"] and not f["tip_moved_after_merge"]:
        return E_SQUASH_PR
    if f["cherry_plus"] == [] and f["cherry_minus"]:
        return E_CONTENT
    return E_NONE


def problems_of(ref, f, ctx):
    """返回该分支命中的 P 类清单。★ P10（本地主线落后）是**全局**条件，不挂在每条分支上。"""
    problems = []
    name = ref["name"]

    if name in ctx["worktree_branches"]:
        if name != ctx["current_branch"]:
            problems.append("P6-HELD_BY_WORKTREE")
    if ref["track"] and "gone" in ref["track"]:
        problems.append("P4-UPSTREAM_GONE")
    if ref["upstream"] in (ctx["base"], ctx["base_short"]):
        problems.append("P5-UPSTREAM_IS_MAIN")
    if f["tip_moved_after_merge"]:
        problems.append("P2-TIP_AFTER_MERGE")
    if f["pr_base"] and f["pr_base"] not in (ctx["base"], ctx["base_short"]):
        problems.append("P3-STACKED_BASE")
    if f["merge_commits"] > 0:
        problems.append("P9-MERGE_COMMITS")
    if f["_merged_into_other"]:
        problems.append("P8-MERGED_INTO_OTHER")
    if (not f["is_ancestor_of_base"]
            and f["unique_commits"] > 0
            and not (f["pr_number"] and not f["tip_moved_after_merge"])):
        problems.append("P7-ORPHAN_UNMERGED")
    if ref["last_commit_date"] and ctx["stale_before"] and ref["last_commit_date"] < ctx["stale_before"]:
        problems.append("P12-STALE")
    if ctx["squash_only_base"] and f["is_ancestor_of_base"] is False:
        problems.append("P1-SQUASH_BLIND")
    return problems


def analyze(repo, base, remote, opts):
    merged_err = open_err = settings_err = None
    settings = {}
    repo_key = os.path.realpath(repo)
    cache = None
    if not opts.offline and not getattr(opts, "refresh_pr", False) and getattr(opts, "cache_path", None):
        cache = load_pr_cache(opts.cache_path, getattr(opts, "cache_ttl", 600), repo_key)
    if cache is not None:
        merged_prs_list = cache.get("merged") or []
        open_prs_list = cache.get("open") or []
        settings = cache.get("settings") or {}
        pr_source = "cache(%s)" % cache.get("fetched_at", "?")
    elif opts.offline:
        merged_prs_list, open_prs_list = [], []
        pr_source = "offline"
        settings_err = "offline"
    else:
        merged_prs_list, merged_err = gh_pr_list(repo, "merged")
        open_prs_list, open_err = gh_pr_list(repo, "open")
        settings, settings_err = gh_repo_settings(repo)
        pr_source = "gh"
        if merged_prs_list is not None and getattr(opts, "cache_path", None):
            save_pr_cache(opts.cache_path, repo_key, merged_prs_list, open_prs_list or [], settings)
    merged_prs = {p["headRefName"]: p for p in (merged_prs_list or []) if p.get("headRefName")}
    open_prs = {p["headRefName"]: p for p in (open_prs_list or []) if p.get("headRefName")}

    refs = list_local_refs(repo)
    worktrees = list_worktrees(repo)
    cur = current_branch(repo)
    base_short = base.split("/")[-1]
    base_behind = int(git_out(repo, "rev-list", "--count", "%s..%s" % (base_short, base)) or 0) \
        if git_ok(repo, "rev-parse", "--verify", base_short) else 0

    # 主线是否"纯 squash"（0 个 merge 提交）= P1 的背景
    main_merges = len([x for x in git_out(repo, "log", "--merges", "--oneline", base).splitlines() if x.strip()])

    stale_before = None
    if opts.stale_days > 0:
        stale_before = datetime.now().strftime("%Y-%m-%d")
        import datetime as _dt
        stale_before = (_dt.date.today() - _dt.timedelta(days=opts.stale_days)).isoformat()

    ctx = {
        "base": base, "base_short": base_short, "base_behind": base_behind,
        "current_branch": cur,
        "worktree_branches": {w["branch"] for w in worktrees if w["branch"]},
        "stale_before": stale_before,
        "squash_only_base": main_merges == 0,
    }

    # P8：谁的上位合并落在了别的分支上（tip 被某个非主线分支包含）
    #     用 `git branch --contains` 一次问清；★ 但只对"可能有独有内容"的分支问
    #     （等于主线 tip 或没有独有提交的分支，语义上不可能"合并到了别处"）
    def merged_into_other(pair):
        ref, f = pair
        if f["is_ancestor_of_base"] or f["unique_commits"] == 0:
            return None
        rc, out, _ = git(repo, "branch", "--contains", ref["tip"], "--format=%(refname:short)")
        if rc != 0:
            return None
        for line in out.splitlines():
            b = line.strip()
            if b.startswith("*"):
                b = b[1:].strip()
            if not b or b.startswith("(") or "detached" in b:
                continue
            if b != ref["name"] and b != base_short and b != "main":
                return b
        return None

    with ThreadPoolExecutor(max_workers=16) as pool:
        facts = list(pool.map(lambda r: branch_facts(repo, base, r, merged_prs, remote), refs))
    with ThreadPoolExecutor(max_workers=16) as pool:
        others = list(pool.map(merged_into_other, zip(refs, facts)))

    records = []
    for ref, f, other in zip(refs, facts, others):
        # P8 只在「确实有独有提交」时才成立（否则任何等于主线 tip 的分支都会被误判成"合并到了别处"）
        f["_merged_into_other"] = None if (f["is_ancestor_of_base"] or f["unique_commits"] == 0) else other
        ev = evidence_of(f)
        probs = problems_of(ref, f, ctx)
        protected = any(fnmatch.fnmatch(ref["name"], p) for p in opts.protect)
        blockers = []
        if ref["name"] == cur:
            blockers.append("CURRENT")
        if ref["name"] in ctx["worktree_branches"]:
            blockers.append("WORKTREE")
        if protected:
            blockers.append("PROTECTED")
        if ref["name"] in open_prs:
            blockers.append("OPEN_PR")
        if ev == E_NONE:
            blockers.append("NO_EVIDENCE")
        if f["tip_moved_after_merge"] and f["unique_commits"] > 0:
            blockers.append("TIP_AFTER_MERGE")
        if "P3-STACKED_BASE" in probs:
            blockers.append("STACKED_BASE")
        if "P7-ORPHAN_UNMERGED" in probs:
            blockers.append("ORPHAN")

        deletable = (not blockers) and ev in (E_ANCESTRY, E_SQUASH_PR, E_CONTENT)
        records.append({
            "name": ref["name"], "tip": ref["tip"], "evidence": ev,
            "ahead": f["ahead"], "behind": f["behind"],
            "is_ancestor_of_base": f["is_ancestor_of_base"],
            "unique_commits": f["unique_commits"], "merge_commits": f["merge_commits"],
            "cherry_plus": f["cherry_plus"],
            "last_commit_date": ref["last_commit_date"], "subject": ref["subject"],
            "upstream": ref["upstream"], "track": ref["track"],
            "pr_number": f["pr_number"], "pr_url": f["pr_url"], "pr_base": f["pr_base"],
            "pr_merged_at": f["pr_merged_at"], "pr_head_oid": f["pr_head_oid"],
            "tip_moved_after_merge": f["tip_moved_after_merge"],
            "merged_into_other": f["_merged_into_other"],
            "problems": probs, "blockers": blockers, "deletable": deletable,
            "protected": protected,
            "open_pr": open_prs.get(ref["name"], {}).get("number"),
        })

    remote_records = analyze_remote(repo, base, remote, merged_prs, refs)

    return {
        "repo": repo, "base": base, "remote": remote, "generated_at": datetime.now().isoformat(timespec="seconds"),
        "current_branch": cur, "base_behind": base_behind, "base_merge_commits": main_merges,
        "worktrees": worktrees,
        "offline": opts.offline, "pr_source": pr_source,
        "gh_merged_error": merged_err, "gh_open_error": open_err, "gh_settings_error": settings_err,
        "merged_pr_count": len(merged_prs_list or []), "open_pr_count": len(open_prs_list or []),
        "repo_settings": settings or {},
        "local": records, "remote_branches": remote_records,
    }


def analyze_remote(repo, base, remote, merged_prs, local_refs):
    local_tips = {r["name"]: r["tip"] for r in local_refs}
    out = []
    for r in list_remote_refs(repo, remote):
        name = r["name"]
        pr = merged_prs.get(name)
        if pr and pr.get("headRefOid") == r["tip"]:
            verdict = "PRUNABLE"          # 已合并，且远端就是 PR 当时那一下
        elif pr:
            verdict = "MOVED_AFTER_MERGE"  # 合并后远端又被推过
        elif git_ok(repo, "merge-base", "--is-ancestor", r["tip"], base):
            verdict = "ANCESTRY_MERGED"
        else:
            rc, o, _ = git(repo, "cherry", base, r["ref"])
            plus = [x for x in o.splitlines() if x.startswith("+")] if rc == 0 else []
            verdict = "CONTENT_IN_BASE" if (rc == 0 and not plus) else "UNMERGED"
        out.append({
            "name": name, "tip": r["tip"], "last_commit_date": r["last_commit_date"],
            "verdict": verdict, "pr_number": pr.get("number") if pr else None,
            "has_local": name in local_tips and local_tips[name] == r["tip"],
        })
    return out


# --------------------------------------------------------------------------
# 报告
# --------------------------------------------------------------------------
def cell(s, limit=60):
    s = (s or "").replace("|", "\\|").replace("\n", " ").strip()
    return s if len(s) <= limit else s[: limit - 1] + "…"


def render_report(a, opts):
    L = []
    add = L.append
    loc = a["local"]
    deletable = [r for r in loc if r["deletable"]]
    protected_not_deletable = [r for r in loc if not r["deletable"]]
    e1 = [r for r in deletable if r["evidence"] == E_ANCESTRY]
    e2 = [r for r in deletable if r["evidence"] == E_SQUASH_PR]
    e3 = [r for r in deletable if r["evidence"] == E_CONTENT]
    flagged = [r for r in loc if any(p in P_BLOCKING for p in r["problems"])]
    remote_prunable = [r for r in a["remote_branches"] if r["verdict"] == "PRUNABLE"]
    remote_other = [r for r in a["remote_branches"] if r["verdict"] not in ("PRUNABLE", "ANCESTRY_MERGED")]

    add("# 分支体检报告（branch_hygiene）")
    add("")
    add("- 仓库：`%s`" % a["repo"])
    add("- 判定基线：`%s`　远端：`%s`　生成时间：%s" % (a["base"], a["remote"], a["generated_at"]))
    add("- 本地分支 **%d** 条 / 远端分支 **%d** 条（PR 证据：merged %d 条，来源 `%s`）" % (
        len(loc), len(a["remote_branches"]), a["merged_pr_count"], a.get("pr_source", "?")))
    add("")

    # ---- 摘要
    add("## 一、结论摘要")
    add("")
    add("| 类别 | 条数 | 含义 |")
    add("|---|---|---|")
    add("| 可安全清理（E1 真合并） | %d | `git branch -d` 直接可删 |" % len(e1))
    add("| 可安全清理（E2 squash 合并） | %d | 需 `-D`（默认先打归档 tag） |" % len(e2))
    add("| 可安全清理（E3 内容已在主线） | %d | 无 PR 记录、但补丁全部等价 |" % len(e3))
    add("| ★ 有问题、**禁删** | %d | 命中 P2/P3/P7，见第四节 |" % len(flagged))
    add("| 其他保留 | %d | 当前分支/worktree 占用/保护模式/开放 PR/证据不足 |" % (len(protected_not_deletable) - len(flagged)))
    add("| 已合并但远端 head 仍在 | %d | 见第五节（`delete_branch_on_merge=%s`） |" % (
        len(remote_prunable), a["repo_settings"].get("delete_branch_on_merge", "?")))
    add("")
    if a["base_behind"] > 0:
        add("> ⚠️ **本地 `%s` 落后 `%s` %d 个提交** ⇒ 先 fetch/fast-forward 再清理，否则判定会假阴。"
            % (a["base"].split("/")[-1], a["base"], a["base_behind"]))
        add("")
    if a["base_merge_commits"] == 0:
        add("> ⚠️ **主线是纯 squash 历史（0 个 merge 提交）** ⇒ `git branch --merged` 只认得 main 自己，"
            "「已合并」必须以 PR 状态 + patch-id 为准（这条就是本工具存在的理由）。")
        add("")

    # ---- 口径
    add("## 二、判定口径（证据梯度）")
    add("")
    add("| 证据 | 判据 | 删除方式 |")
    add("|---|---|---|")
    add("| `E1-ancestry` | 分支 tip 是 `%s` 的祖先 | `git branch -d` |" % a["base"])
    add("| `E2-squash-by-pr` | PR 已合并 **且** 分支 tip == PR 的 `headOid` | `git branch -D`（先归档） |")
    add("| `E3-content-only` | `git cherry <base> <branch>` 无 `+` | `git branch -D`（先归档） |")
    add("| `E4-unproven` | 以上都不成立 | **不自动删** |")
    add("")
    add("★ E2 是最强的一条：**tip 等于 PR 当时推送的那一下** ⇒ 这个 tip 的全部内容都进了那次 squash 提交，"
        "不会因为「切换分支」或「后来 rebase」而漏。")
    add("")

    # ---- 可清理
    add("## 三、可以安全清理（%d 条）" % len(deletable))
    add("")
    if not deletable:
        add("（无）")
    else:
        add("| 分支 | 证据 | PR | 独有提交 | 落后主线 | 最后提交 | 备注 |")
        add("|---|---|---|---|---|---|---|")
        for r in sorted(deletable, key=lambda x: x["name"]):
            note = []
            if r["tip_moved_after_merge"]:
                note.append("tip 已挪动")
            if r["problems"]:
                note.append(",".join(p.split("-")[0] for p in r["problems"]
                                     if p not in ("P1-SQUASH_BLIND", "P8-MERGED_INTO_OTHER")))
            add("| `%s` | %s | %s | %d | %d | %s | %s |" % (
                r["name"], r["evidence"].split("-")[0],
                ("#%s" % r["pr_number"]) if r["pr_number"] else "-",
                r["unique_commits"], r["behind"], r["last_commit_date"], cell(" ".join(note), 40)))
    add("")

    # ---- 禁删
    add("## 四、禁删清单（%d 条；其中 ★ 有问题 %d 条）" % (len(protected_not_deletable), len(flagged)))
    add("")
    add("★ = 命中 P2/P3/P7（内容可能还没进主线）；其余是保护模式 / worktree 占用 / 开放 PR / 证据不足。")
    add("")
    for r in sorted(protected_not_deletable, key=lambda x: (x not in flagged, x["name"])):
        tags = ",".join(p for p in r["problems"] if p != "P1-SQUASH_BLIND") or "（无 P 类）"
        mark = "★ " if r in flagged else ""
        add("### %s`%s`" % (mark, r["name"]))
        add("")
        add("- 判定：`%s`；阻断原因：%s" % (r["evidence"], ", ".join(r["blockers"])))
        add("- 命中问题：%s" % tags)
        add("- 独有提交 **%d** 条（`git cherry` 的 `+`）/ 落后主线 %d；最后提交 %s" % (
            r["unique_commits"], r["behind"], r["last_commit_date"]))
        if r["pr_number"]:
            add("- PR：#%s（base=`%s`，mergedAt=%s，headOid=%s%s）" % (
                r["pr_number"], r["pr_base"], r["pr_merged_at"],
                (r["pr_head_oid"] or "")[:8],
                "，**≠ 本地 tip " + r["tip"][:8] + "**" if r["tip_moved_after_merge"] else ""))
        if r["merged_into_other"]:
            add("- 上位合并去向：`%s`（**非主线**）" % r["merged_into_other"])
        if r["cherry_plus"]:
            add("- `+` 提交（主线里没有等价补丁，最多列 10 条）：")
            for sha in r["cherry_plus"][:10]:
                subj = git_out(a["repo"], "log", "-1", "--format=%s", sha)
                add("  - `%s` %s" % (sha[:8], cell(subj, 80)))
            if len(r["cherry_plus"]) > 10:
                add("  - …另 %d 条" % (len(r["cherry_plus"]) - 10))
        add("- 看它还剩什么没进主线：")
        add("  ```")
        add("  git diff --stat %s...%s" % (a["base"], r["name"]))
        add("  ```")
        add("")

    # ---- 隐患
    add("## 五、隐患与远端清理面")
    add("")
    up_main = [r for r in loc if "P5-UPSTREAM_IS_MAIN" in r["problems"]]
    gone = [r for r in loc if "P4-UPSTREAM_GONE" in r["problems"]]
    held = [r for r in loc if "P6-HELD_BY_WORKTREE" in r["problems"]]
    add("| 隐患 | 条数 | 明细 |")
    add("|---|---|---|")
    add("| upstream 指向主线（`git push` 事故面） | %d | %s |" % (
        len(up_main), cell(", ".join("`%s`" % r["name"] for r in up_main), 160) or "-"))
    add("| upstream 已消失（`[gone]`） | %d | %s |" % (
        len(gone), cell(", ".join("`%s`" % r["name"] for r in gone), 160) or "-"))
    add("| 被 worktree 占用（删不掉） | %d | %s |" % (
        len(held), cell(", ".join("`%s`" % r["name"] for r in held), 160) or "-"))
    add("| 本地主线落后 origin（先 ff 再判） | %d | `%s` 落后 `%s` %d 个提交 |" % (
        a["base_behind"], a["base"].split("/")[-1], a["base"], a["base_behind"]))
    add("| 已合并 PR 的远端 head 仍在 | %d | %s |" % (
        len(remote_prunable), cell(", ".join("`%s`" % r["name"] for r in remote_prunable), 160) or "-"))
    add("| 远端 head 与 PR 当时不一致 | %d | %s |" % (
        len([r for r in a["remote_branches"] if r["verdict"] == "MOVED_AFTER_MERGE"]),
        cell(", ".join("`%s`" % r["name"] for r in remote_other if r["verdict"] == "MOVED_AFTER_MERGE"), 160) or "-"))
    add("| 远端仍未合并 | %d | %s |" % (
        len([r for r in a["remote_branches"] if r["verdict"] == "UNMERGED"]),
        cell(", ".join("`%s`" % r["name"] for r in remote_other if r["verdict"] == "UNMERGED"), 160) or "-"))
    add("")
    if a["worktrees"]:
        add("worktree 明细：")
        add("")
        for w in a["worktrees"]:
            add("- `%s` -> `%s`%s" % (w["path"], w["branch"] or "(detached)", "（当前）" if w["branch"] == a["current_branch"] else ""))
        add("")

    # ---- 给后续开发的提示
    add("## 六、给后续开发的提示")
    add("")
    for h in build_hints(a, deletable, flagged, remote_prunable, up_main, gone, held):
        add(h)
        add("")

    # ---- 执行计划
    add("## 七、执行计划（本次为 %s）" % ("**真删**" if opts.apply else "**dry-run，什么都没删**"))
    add("")
    plan = build_plan(a, deletable, remote_prunable, opts)
    if not plan:
        add("（无）")
    else:
        add("```")
        for cmd in plan:
            add(cmd)
        add("```")
    add("")
    add("---")
    add("")
    add("复现：`python tools/branch_hygiene.py --base %s`　（本报告由 `tools/branch_hygiene.py` 生成）" % a["base"])
    return "\n".join(L) + "\n"


def build_hints(a, deletable, flagged, remote_prunable, up_main, gone, held):
    """只对**实际命中**的类别出提示（避免正确的废话）。"""
    H = []
    H.append("### H1 squash 合并让 ancestry 永久失效（本仓常态）\n"
             "- 现象：`git branch --merged origin/main` 只列出 main 自己，而 GitHub 上已合并 PR **%d** 条。\n"
             "- 危害：**`git branch -d` 会拒绝删除** ⇒ 人要手动 `-D`；而 `-D` 一旦用错，独有提交就永久丢失"
             "（本仓「混批提交单元」那条教训正是这么来的）。\n"
             "- 规矩：删分支前**三件套**齐了才准 `-D` —— ① PR 状态 = MERGED ② 分支 tip == PR `headOid` "
             "③ 删前先打 `archive/branch/<name>/<时间戳>` 归档 tag。本脚本默认就是这么做的。" % a["merged_pr_count"])

    if remote_prunable:
        H.append("### H2 远端 head 分支没有被自动删除（%d 条）\n"
                 "- 实测：`delete_branch_on_merge=%s` ⇒ 每次合并都在 `origin` 上留一条永远不用的分支。\n"
                 "- 一次性修掉（推荐，一次设置省掉一半清理工作）：\n"
                 "  ```\n  gh repo edit --delete-branch-on-merge\n  ```\n"
                 "- 存量清理（只动远端、本地一条不碰）：\n"
                 "  ```\n  python tools/branch_hygiene.py --apply --prune-remote --only-remote\n  ```"
                 % (len(remote_prunable), a["repo_settings"].get("delete_branch_on_merge", "?")))

    p3 = [r for r in flagged if "P3-STACKED_BASE" in r["problems"]]
    if p3:
        detail = "；".join("`%s`（PR #%s，base=`%s`）" % (r["name"], r["pr_number"], r["pr_base"]) for r in p3)
        H.append("### H3 ★ 叠加 PR（stacked PR）落在已被合并的 base 上 —— 本轮最值得记的一条\n"
                 "- 现象：%s。\n"
                 "- 危害：PR 的合并提交进了**那条 base 分支**，而 base 分支随后被 squash 合并并废弃 ⇒ "
                 "它的合并提交**不是主线的祖先**，`git cherry` 也认不出等价补丁（`+` 提交仍在）。"
                 "**子 PR 的内容是否真的进了主线，必须人核**，自动判定在这里不可信。\n"
                 "- 规矩：① PR 的 base **只许是 main**；② 真要用叠加 PR，父 PR 合并后**立刻**把子 PR "
                 "`gh pr edit --base main` 重定基，别让它挂在一棵死树上。" % detail)

    p2 = [r for r in flagged if "P2-TIP_AFTER_MERGE" in r["problems"]]
    if p2:
        detail = "；".join("`%s`（PR #%s 的 headOid=`%s`，本地 tip=`%s`）" % (
            r["name"], r["pr_number"], (r["pr_head_oid"] or "")[:8], r["tip"][:8]) for r in p2)
        H.append("### H4 合并之后分支又被推了新提交（tip 已挪动）\n"
                 "- 命中：%s。\n"
                 "- 危害：**这部分提交不在任何 PR 里**。看着「分支已合并」，删掉就没了。\n"
                 "- 处置：要么给它补一个 PR 走完整流程，要么确认是废弃实验后用 "
                 "`git branch -D`（先归档）并写下理由。" % detail)

    p7 = [r for r in flagged if "P7-ORPHAN_UNMERGED" in r["problems"]]
    if p7:
        total = sum(r["unique_commits"] for r in p7)
        H.append("### H5 孤儿分支：有独有提交、主线里没有等价补丁\n"
                 "- 命中 %d 条、合计 **%d** 个 `+` 提交：%s。\n"
                 "- ★ **`+` ≠ 一定丢了**：它只说明「补丁不等价」，同一件事换个写法重做一遍也是 `+`（本仓大量如此）。"
                 "判「要不要留」要看内容：\n"
                 "  ```\n  git diff --stat origin/main...<分支>\n  ```\n"
                 "- 处置三选一（**不许「顺手删掉」**）：① 还有用 -> 补 PR 合掉；② 已用别的方式做过 -> "
                 "写一条 Issue 说明「该内容已在主线，以某 PR 形式落地」，然后归档删除；③ 是实验 -> "
                 "打归档 tag 后删。" % (len(p7), total, ", ".join("`%s`" % r["name"] for r in p7)))

    if up_main:
        H.append("### H6 upstream 指向主线的分支（%d 条）—— `git push` 的事故面\n"
                 "- 命中：%s。\n"
                 "- 常见成因：`git checkout -b <分支> origin/main` 或 `git branch -u origin/main` —— "
                 "刚建分支时它看着无害（等于主线），**但你一旦提交，裸 `git push` 就可能把这些提交推上 main**。\n"
                 "- 危害：① 在裸 `git push` 下**有可能直推主线**；② `git status` 的 `ahead N` 会被误读成"
                 "「这条分支领先主线」，实际是「本地游离提交」。\n"
                 "- 处置：`git branch --unset-upstream <分支>`（该分支没有自己的远端时，正确状态就是**没有 upstream**；"
                 "第一次推送时用 `git push -u origin <分支>` 建立正确的 upstream）。"
                 % (len(up_main), ", ".join("`%s`" % r["name"] for r in up_main)))

    if gone:
        H.append("### H7 upstream 已消失（`[gone]`）\n"
                 "- 命中：%s。这通常意味着 PR 已合并 + 远端分支已删 ⇒ 本地这条是残留。\n"
                 "- 处置：先跑 `git fetch --prune`，再按本次报告的证据梯度判可删与否。"
                 % ", ".join("`%s`" % r["name"] for r in gone))

    if held:
        H.append("### H8 被 worktree 占用的分支删不掉\n"
                 "- 命中：%s。\n"
                 "- 处置：先 `git worktree remove <路径>`（或 `git worktree prune`），分支才会释放。" % ", ".join(
                     "`%s`" % r["name"] for r in held))

    H.append("### H9 日常纪律（把上面的问题挡在发生之前）\n"
             "- **合并即删**：PR 合并后当天清分支，别攒（本次体检：本地 %d 条分支里 **%d 条可清理**、"
             "远端还留着 **%d** 条已合并 head）。\n"
             "- **常跑 `git fetch --prune`**：`[gone]` 会被及时发现。\n"
             "- **分支命名承载意图**：`feat/` `fix/` `docs/` `chore/` + 期次/Issue 号，脚本据此分档与排序。\n"
             "- **`backup/*` 只增不改**：仓库默认保护它；要清就显式 `--protect` 覆盖。\n"
             "- **归档优先于删除**：`archive/branch/<name>/<时间戳>` tag 是本地安全网，"
             "`git branch <name> <tag>` 一条命令还原。"
             % (len(a["local"]), len(deletable), len(remote_prunable)))
    return H


def build_plan(a, deletable, remote_prunable, opts):
    cmds = []
    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    if a["base_behind"] > 0:
        cmds.append("# 0) 先把本地主线快进（否则判定会假阴）—— 三行分开跑，别用 &&（Windows PowerShell 5.1 不认）")
        cmds.append("git fetch %s --prune" % a["remote"])
        cmds.append("git checkout %s" % a["base"].split("/")[-1])
        cmds.append("git merge --ff-only %s" % a["base"])
        cmds.append("git checkout %s" % a["current_branch"])
    for r in sorted(deletable, key=lambda x: x["name"]):
        if opts.apply:
            continue  # --apply 时真删走 apply_actions，这里不重复列
        if r["evidence"] == E_ANCESTRY:
            cmds.append("git branch -d %s" % r["name"])
        elif r["evidence"] == E_SQUASH_PR:
            cmds.append('git tag -f -a archive/branch/%s/%s %s -m "branch_hygiene 删除前归档：%s"'
                        % (r["name"], stamp, r["tip"][:8], r["name"]))
            cmds.append("git branch -D %s" % r["name"])
        elif r["evidence"] == E_CONTENT:
            cmds.append("# E3 内容等价：%s（要删得加 --force --allow-content-only，脚本才会动手）" % r["name"])
    if remote_prunable and not opts.apply:
        cmds.append("# 远端（要真删：--apply --prune-remote）")
        for r in remote_prunable:
            cmds.append("git push %s --delete %s" % (a["remote"], r["name"]))
    if cmds:
        cmds.insert(0, "# 时间戳 %s；下面的归档 tag 是本脚本 --apply 时会自动做的同一件事" % stamp)
    return cmds


# --------------------------------------------------------------------------
# 执行
# --------------------------------------------------------------------------
def archive_tag(repo, name, tip, prefix="archive/branch"):
    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    tag = "%s/%s/%s" % (prefix, name, stamp)
    rc, _, err = git(repo, "tag", "-f", "-a", tag, tip, "-m", "branch_hygiene 删除前归档：%s" % name)
    return tag if rc == 0 else None


def delete_remote(repo, remote, names, chunk=20):
    """批量删远端分支（一次 push 带多个 ref）；整批失败才逐条重试，好定位是谁的问题。

    返回 (ok, failed)：ok = [name, ...]；failed = [(name, 原因), ...]
    """
    ok, failed = [], []
    for i in range(0, len(names), chunk):
        group = names[i:i + chunk]
        rc, _, _ = git(repo, "push", remote, "--delete", *group)
        if rc == 0:
            ok.extend(group)
            continue
        # 整批失败：可能只是其中一条（比如远端已不存在），逐条重试才能给出准确读数
        for name in group:
            rc2, _, err2 = git(repo, "push", remote, "--delete", name)
            if rc2 == 0:
                ok.append(name)
            else:
                failed.append((name, err2.strip()[:120]))
    return ok, failed


def apply_actions(a, opts):
    repo = a["repo"]
    done, skipped = [], []
    cur = a["current_branch"]
    rows = [] if getattr(opts, "only_remote", False) else sorted(a["local"], key=lambda x: x["name"])
    for r in rows:
        if not r["deletable"]:
            continue
        name, ev = r["name"], r["evidence"]
        if name == cur:
            skipped.append((name, "当前分支"))
            continue
        if ev == E_SQUASH_PR and not opts.force:
            skipped.append((name, "squash 合并需 --force（才允许 -D）"))
            continue
        if ev == E_CONTENT and not (opts.force and opts.allow_content_only):
            skipped.append((name, "E3 内容等价需 --force --allow-content-only"))
            continue
        tag = None
        if opts.archive:
            tag = archive_tag(repo, name, r["tip"])
            if not tag:
                skipped.append((name, "归档 tag 失败，**拒绝删除**（安全网优先）"))
                continue
        flag = "-d" if ev == E_ANCESTRY else "-D"
        rc, _, err = git(repo, "branch", flag, name)
        if rc == 0:
            done.append((name, flag, tag))
        else:
            skipped.append((name, "git branch %s 失败：%s" % (flag, err.strip()[:120])))
    remote_done, remote_skipped = [], []
    if opts.prune_remote:
        # ★ 护栏：默认分支 / 保护模式 / 当前分支 / 被 worktree 占用的分支，一律不删远端
        protected_remote = {"main", "master", "HEAD"}
        default_branch = (a.get("repo_settings") or {}).get("defaultBranch")
        if default_branch:
            protected_remote.add(default_branch)
        held = {w["branch"] for w in a.get("worktrees", []) if w.get("branch")}
        targets = []
        for r in a["remote_branches"]:
            if r["verdict"] != "PRUNABLE":
                continue
            why = None
            if r["name"] in protected_remote:
                why = "默认/受保护分支，跳过"
            elif r["name"] == a["current_branch"]:
                why = "当前分支，跳过"
            elif r["name"] in held:
                why = "被 worktree 占用，跳过"
            elif any(fnmatch.fnmatch(r["name"], p) for p in opts.protect):
                why = "命中保护模式，跳过"
            if why:
                remote_skipped.append((r["name"], why))
            else:
                targets.append((r["name"], r["tip"]))
        # ★ 删远端前先把**远端 tip** 在本仓打归档 tag：远端 ref 一旦删掉，那个 commit
        #   在本仓可能就没有 ref 引用了（只留下 archive 这一条命）—— 安全网要和本地同一套
        names = []
        for n, t in targets:
            if opts.archive and not archive_tag(repo, n, t, prefix="archive/remote"):
                remote_skipped.append((n, "归档 tag 失败，拒绝删除远端（安全网优先）"))
                continue
            names.append(n)
        ok, failed = delete_remote(repo, a["remote"], names)
        remote_done.extend((n, "") for n in ok)
        remote_skipped.extend(failed)
    return done, skipped, remote_done, remote_skipped


# --------------------------------------------------------------------------
# 自检：证明判定能红（造 12 类样本）
# --------------------------------------------------------------------------
def run_selftest(verbose=True):
    parent = tempfile.mkdtemp(prefix="bh-selftest-")
    repo = os.path.join(parent, "work")
    bare = os.path.join(parent, "origin.git")
    os.makedirs(repo)
    env = ["-c", "user.name=selftest", "-c", "user.email=selftest@example.invalid",
           "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main"]

    def g(*args, cwd=repo, ok=True):
        rc, out, err = run([GIT, *env, "-C", cwd, *args])
        if ok and rc != 0:
            raise RuntimeError("git %s 失败：%s" % (" ".join(args), err.strip()))
        return out

    g("init", "-q", ".")
    run([GIT, "init", "-q", "--bare", bare])
    with open(os.path.join(repo, "README.md"), "w", encoding="utf-8") as fh:
        fh.write("# selftest\n")
    g("add", "-A"); g("commit", "-q", "-m", "base")
    g("remote", "add", "origin", bare)
    g("push", "-q", "-u", "origin", "main")
    g("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")

    def commit_file(branch, path, text, msg):
        g("checkout", "-q", "-b", branch, "main")
        with open(os.path.join(repo, path), "w", encoding="utf-8") as fh:
            fh.write(text)
        g("add", "-A"); g("commit", "-q", "-m", msg)

    # E1 真合并（用 fast-forward：让 main 保持线性，这样 P1「纯 squash 主线」在样本里也成立）
    commit_file("feat/ancestry", "a.txt", "a\n", "feat: ancestry")
    g("checkout", "-q", "main"); g("merge", "-q", "--ff-only", "feat/ancestry")

    # P9 先埋一个分支；等 main 往前走之后（下面 content-dup 之后）再把它合回来 ——
    #    这样 merge 提交落在**分支**上，main 仍然是线性的
    commit_file("feat/p9", "i.txt", "i\n", "feat: p9")
    g("checkout", "-q", "main")

    # E2 squash 合并（有 PR 语义：tip == PR headOid 由 selftest 直接构造）
    commit_file("feat/squashed", "b.txt", "b\n", "feat: squashed")
    squash_tip = g("rev-parse", "feat/squashed").strip()
    g("checkout", "-q", "main"); g("merge", "-q", "--squash", "feat/squashed")
    g("commit", "-q", "-m", "squash: feat/squashed (#1)")

    # E3 内容等价但无 PR
    commit_file("feat/content-dup", "c.txt", "c\n", "feat: content-dup")
    g("checkout", "-q", "main"); g("merge", "-q", "--squash", "feat/content-dup")
    g("commit", "-q", "-m", "squash: feat/content-dup")

    # P9 在这里合：feat/p9 把已经往前走的 main 合进来 -> 分支自带 merge 提交
    g("checkout", "-q", "feat/p9")
    g("merge", "-q", "--no-ff", "main", "-m", "merge main into feat/p9")
    g("checkout", "-q", "main")

    # P7 孤儿（有独有提交、主线无等价补丁）
    commit_file("feat/orphan", "d.txt", "d\n", "feat: orphan work")

    # P4 upstream gone
    commit_file("feat/gone", "e.txt", "e\n", "feat: gone")
    g("push", "-q", "-u", "origin", "feat/gone")
    g("push", "-q", "origin", "--delete", "feat/gone")
    g("fetch", "-q", "--prune", "origin")

    # P5 upstream 指向主线
    g("branch", "feat/upstream-main", "main")
    g("branch", "--set-upstream-to=origin/main", "feat/upstream-main")

    # P6 worktree 占用（该分支本身是干净的）
    g("branch", "feat/held", "main")
    g("worktree", "add", "-q", os.path.join(parent, "wt"), "feat/held")

    # P8 上位合并到非主线分支
    commit_file("feat/other-base", "f.txt", "f\n", "feat: other base")
    commit_file("feat/child", "g.txt", "g\n", "feat: child")
    g("checkout", "-q", "feat/other-base")
    g("merge", "-q", "--no-ff", "feat/child", "-m", "merge feat/child into feat/other-base")
    g("checkout", "-q", "main")

    # P3 叠加 PR（用假 PR 证据喂进去，因为 selftest 不联网）
    g("push", "-q", "-u", "origin", "feat/squashed", ok=False)
    # ★ 关键：把 main 推到 origin，否则 base(origin/main) 还停在最初那一版，所有判定都会假阴
    g("push", "-q", "origin", "main")
    g("fetch", "-q", "--prune", "origin")

    class O:
        offline = True
        protect = list(DEFAULT_PROTECT)
        stale_days = 0
        cache_path = None
        cache_ttl = 0
        refresh_pr = False
    a = analyze(repo, "origin/main", "origin", O())
    by = {r["name"]: r for r in a["local"]}

    checks = [
        ("E1 真合并被认成可删", by["feat/ancestry"]["evidence"] == E_ANCESTRY and by["feat/ancestry"]["deletable"]),
        ("E3 内容等价被认成可删", by["feat/content-dup"]["evidence"] == E_CONTENT and by["feat/content-dup"]["deletable"]),
        ("P7 孤儿被拦住", "P7-ORPHAN_UNMERGED" in by["feat/orphan"]["problems"] and not by["feat/orphan"]["deletable"]),
        ("P4 upstream gone 被认出", "P4-UPSTREAM_GONE" in by["feat/gone"]["problems"]),
        ("P5 upstream 指向主线被认出", "P5-UPSTREAM_IS_MAIN" in by["feat/upstream-main"]["problems"]),
        ("P6 worktree 占用被认出", "P6-HELD_BY_WORKTREE" in by["feat/held"]["problems"]),
        ("P8 上位合并到非主线被认出", "P8-MERGED_INTO_OTHER" in by["feat/child"]["problems"]),
        ("P9 分支自带 merge 提交被认出（且不再算祖先）", 
         "P9-MERGE_COMMITS" in by["feat/p9"]["problems"]
         and not by["feat/p9"]["is_ancestor_of_base"]),
        ("P1 纯 squash 主线被认出", any("P1-SQUASH_BLIND" in r["problems"] for r in a["local"])),
    ]
    # 用假 PR 证据再验一次 E2 / P2 / P3
    fake_merged = {
        "feat/squashed": {"number": 1, "url": "u", "baseRefName": "main", "mergedAt": "x",
                          "headRefOid": squash_tip},
        "feat/child": {"number": 2, "url": "u2", "baseRefName": "feat/other-base", "mergedAt": "y",
                       "headRefOid": g("rev-parse", "feat/child").strip()},
        "feat/orphan": {"number": 3, "url": "u3", "baseRefName": "main", "mergedAt": "z",
                        "headRefOid": "0" * 40},
    }
    a2 = analyze_with_fake_prs(repo, "origin/main", "origin", O(), fake_merged)
    by2 = {r["name"]: r for r in a2["local"]}
    checks += [
        ("E2 squash-by-pr 被认成可删", by2["feat/squashed"]["evidence"] == E_SQUASH_PR and by2["feat/squashed"]["deletable"]),
        ("P2 合并后 tip 挪动被认出", "P2-TIP_AFTER_MERGE" in by2["feat/orphan"]["problems"]),
        ("P3 叠加 PR base 非主线被认出", "P3-STACKED_BASE" in by2["feat/child"]["problems"]),
        ("P11 已合并 PR 的远端 head 仍在，被认出",
         any(r["name"] == "feat/squashed" and r["verdict"] == "PRUNABLE" for r in a2["remote_branches"])),
    ]

    # ---- 真删路径（只在样本仓库里做，绝不碰真实分支）----
    class OA(O):
        apply = True
        archive = True
        force = True
        allow_content_only = True
        prune_remote = False
    tip_before = git_out(repo, "rev-parse", "feat/squashed")
    done, _skipped, _rd, _rs = apply_actions(a2, OA())
    deleted = [n for n, _, _ in done]
    live = set(git_out(repo, "for-each-ref", "--format=%(refname:short)", "refs/heads/").splitlines())
    tags = git_out(repo, "tag", "--list", "archive/branch/*").splitlines()
    sq_tag = [t for t in tags if t.startswith("archive/branch/feat/squashed/")]
    restored = git_out(repo, "rev-parse", "%s^{commit}" % sq_tag[0]) if sq_tag else ""
    checks += [
        ("apply：E2 分支被删掉", "feat/squashed" in deleted and "feat/squashed" not in live),
        ("apply：孤儿分支没被碰", "feat/orphan" not in deleted and "feat/orphan" in live),
        ("apply：worktree 占用的分支没被碰", "feat/held" not in deleted and "feat/held" in live),
        ("apply：删除前先写了归档 tag", bool(sq_tag)),
        ("apply：归档 tag 能还原到原 tip（删了也可逆）", restored == tip_before),
    ]

    # ---- 远端删除路径（样本仓库里的 bare origin，同样不碰真实远端）----
    class OB(OA):
        prune_remote = True
        only_remote = True
    _d3, _s3, r_done, r_skip = apply_actions(a2, OB())
    remote_now = git_out(repo, "for-each-ref", "--format=%(refname:short)", "refs/remotes/origin/").splitlines()
    checks += [
        ("远端清理：已合并 PR 的 head 被删掉",
         "feat/squashed" in [n for n, _ in r_done] and "origin/feat/squashed" not in remote_now),
        ("远端清理：主线没被删（护栏生效）", "origin/main" in remote_now),
        ("远端清理：本地已删的分支没被远端清理连带影响", "feat/orphan" in live),
        ("远端清理：删远端前也打了归档 tag（本地可还原远端 tip）",
         bool(git_out(repo, "tag", "--list", "archive/remote/feat/squashed/*"))),
    ]

    failed = [name for name, ok in checks if not ok]
    if verbose:
        print("== branch_hygiene --selftest ==")
        for name, ok in checks:
            print("  [%s] %s" % ("PASS" if ok else "FAIL", name))
        print("  样本仓库：%s" % repo)
        if failed:
            print("  未通过：%s" % ", ".join(failed))
    return 0 if not failed else 1


def analyze_with_fake_prs(repo, base, remote, opts, fake_merged):
    """selftest 专用：喂假 PR 证据跑一遍判定（不碰网络）。"""
    orig = globals()["gh_pr_list"]
    orig_settings = globals()["gh_repo_settings"]
    def fake(_repo, state, limit=1000):
        return ([dict(v, headRefName=k) for k, v in fake_merged.items()] if state == "merged" else []), None
    def fake_settings(_repo):
        return {"nameWithOwner": "selftest/local", "delete_branch_on_merge": False}, None
    globals()["gh_pr_list"] = fake
    globals()["gh_repo_settings"] = fake_settings
    try:
        opts.offline = False
        return analyze(repo, base, remote, opts)
    finally:
        globals()["gh_pr_list"] = orig
        globals()["gh_repo_settings"] = orig_settings
        opts.offline = True


# --------------------------------------------------------------------------
# 入口
# --------------------------------------------------------------------------
def main(argv=None):
    ap = argparse.ArgumentParser(
        description="已合并分支清理 + 「有问题的上位合并」梳理（默认 dry-run）",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", default=".", help="仓库路径（默认当前目录）")
    ap.add_argument("--base", default="origin/main", help="判定基线（默认 origin/main）")
    ap.add_argument("--remote", default="origin", help="远端名（默认 origin）")
    ap.add_argument("--stale-days", type=int, default=30, help="超过多少天没提交算陈旧（0=不算，默认 30）")
    ap.add_argument("--protect", action="append", default=[], help="额外保护的分支 glob（可重复）；默认已含 %s" % ",".join(DEFAULT_PROTECT))
    ap.add_argument("--offline", action="store_true", help="不调用 gh（只用 ancestry + patch-id 判定）")
    ap.add_argument("--cache-ttl", type=int, default=600, help="PR 证据缓存有效期（秒，默认 600；-1=永不过期）")
    ap.add_argument("--refresh-pr", action="store_true", help="忽略缓存，重新问 gh")
    ap.add_argument("--apply", action="store_true", help="真的删分支（默认只出计划）")
    ap.add_argument("--force", action="store_true", help="允许对 E2/E3 用 `git branch -D`")
    ap.add_argument("--allow-content-only", action="store_true", help="允许删 E3（无 PR 记录、仅内容等价）")
    ap.add_argument("--prune-remote", action="store_true", help="连远端已合并 head 分支一起删（需 --apply）")
    ap.add_argument("--only-remote", action="store_true", help="只清远端，本地分支一条都不动（配 --apply --prune-remote）")
    ap.add_argument("--no-archive", action="store_true", help="★ 不打归档 tag（关掉可逆性，慎用）")
    ap.add_argument("--out-dir", default=os.path.join("innerdoc", "branch-hygiene"), help="报告输出目录")
    ap.add_argument("--no-report", action="store_true", help="不落盘报告")
    ap.add_argument("--json", action="store_true", help="把完整数据打到 stdout")
    ap.add_argument("--check", action="store_true", help="闸门模式：有阻断项则退出码 1")
    ap.add_argument("--selftest", action="store_true", help="造样本证明判定能红")
    opts = ap.parse_args(argv)
    opts.archive = not opts.no_archive
    opts.protect = list(DEFAULT_PROTECT) + opts.protect

    if opts.selftest:
        return run_selftest()

    repo = find_repo(os.path.abspath(opts.repo))
    if not repo:
        print("不是 git 仓库：%s" % opts.repo, file=sys.stderr)
        return 2
    base = resolve_base(repo, opts.base)
    if not base:
        print("找不到判定基线（试过 %s / origin/main / main）" % opts.base, file=sys.stderr)
        return 2

    out_dir_abs = opts.out_dir if os.path.isabs(opts.out_dir) else os.path.join(repo, opts.out_dir)
    opts.cache_path = os.path.join(out_dir_abs, "pr-cache.json")

    a = analyze(repo, base, opts.remote, opts)

    if not opts.offline and a["gh_merged_error"]:
        print("⚠️  gh 不可用（%s）⇒ 已退化为 offline 判定：E2/P2/P3 不可信。" % a["gh_merged_error"], file=sys.stderr)

    report = render_report(a, opts)
    # ★ --json 时 stdout 只留 JSON（给脚本用），人看的摘要走 stderr
    say_out = sys.stderr if opts.json else sys.stdout
    def say(*args):
        print(*args, file=say_out)
    if opts.json:
        print(json.dumps(a, ensure_ascii=False, indent=2))
    if not opts.no_report:
        out_dir = out_dir_abs
        os.makedirs(out_dir, exist_ok=True)
        stamp = datetime.now().strftime("%Y%m%d-%H%M")
        path = os.path.join(out_dir, "report-%s.md" % stamp)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(report)
        latest = os.path.join(out_dir, "latest.md")
        with open(latest, "w", encoding="utf-8") as fh:
            fh.write(report)
        with open(os.path.join(out_dir, "state-%s.json" % stamp), "w", encoding="utf-8") as fh:
            json.dump(a, fh, ensure_ascii=False, indent=2)
        say("报告已写入：%s" % os.path.relpath(path, repo))
        say("最新一份：  %s" % os.path.relpath(latest, repo))

    loc = a["local"]
    deletable = [r for r in loc if r["deletable"]]
    flagged = [r for r in loc if any(p in P_BLOCKING for p in r["problems"])]
    remote_prunable = [r for r in a["remote_branches"] if r["verdict"] == "PRUNABLE"]
    say("本地 %d 条：可清理 %d（E1 %d / E2 %d / E3 %d）｜★ 有问题禁删 %d｜远端可清 %d" % (
        len(loc), len(deletable),
        len([r for r in deletable if r["evidence"] == E_ANCESTRY]),
        len([r for r in deletable if r["evidence"] == E_SQUASH_PR]),
        len([r for r in deletable if r["evidence"] == E_CONTENT]),
        len(flagged), len(remote_prunable)))

    if opts.apply:
        done, skipped, rdone, rskipped = apply_actions(a, opts)
        say("\n已删除本地分支 %d 条（归档 tag：%s）：" % (
            len(done), "开" if opts.archive else "**关**"))
        for name, flag, tag in done:
            say("  %s %s  <- %s" % (flag, name, tag or "(未归档)"))
        if skipped:
            say("跳过 %d 条：" % len(skipped))
            for name, why in skipped:
                say("  %s：%s" % (name, why))
        if opts.prune_remote:
            say("已删远端分支 %d 条：%s" % (len(rdone), ", ".join(n for n, _ in rdone) or "-"))
            for name, err in rskipped:
                say("  远端删除失败 %s：%s" % (name, err))

    if opts.check:
        blockers = []
        if flagged:
            blockers.append("有问题的上位合并 %d 条" % len(flagged))
        if a["base_behind"] > 0:
            blockers.append("本地 %s 落后 %s %d 个提交" % (base.split("/")[-1], base, a["base_behind"]))
        if remote_prunable:
            blockers.append("已合并的远端 head 仍在 %d 条" % len(remote_prunable))
        if blockers:
            say("\n[check] 阻断：%s" % "；".join(blockers))
            return 1
        say("\n[check] 通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
