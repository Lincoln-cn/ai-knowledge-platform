#!/usr/bin/env python3
"""跨场景整链闸门（A1 的唯一缺口）：S1 建的库，真的被 S3 的会话用上了吗？

★ 为什么需要这一条（`docs/验收台账.md` 2. 里 A1 的「还差什么」）：
  今天已经有 S1 / S2 / S3 三条各自的整链读数，但它们各自造自己的夹具 ——
  三条链**互不相接**。于是"每页能开 + 每链能通"都成立，而"从 S1 建的库直接进 S3 的会话"
  这个**产品承诺**没有任何一条读数。读数上它长得像"过了"，其实是"没测"。

这条闸门只做一件事，但把三件事串起来：
  ① 用 **S1 的路径**建库、上传真文档、解析、向量化（不手搓 SQL、不塞假向量）；
  ② 用 **S2 的路径**建一个应用并把它绑到**那个库**上；
  ③ 用 **S3 的路径**开会话提问，然后断言引用三元组里的 `kbId` **就是 ① 那个库的 id**，
     且 `segmentId` 能回读 200 —— 引用必须"挂得住"，不能只是一串好看的 id。

判据（每条都能红）：
  1. 引用里出现 `kbId == ①` 的库 ⇒ 去掉应用与库的绑定，这条必须红；
  2. `segmentId` 回读 200 且 `documentId` 是 ① 上传的那份文档 ⇒ 引用是"这份文档的这段"，
     不是"任意一段"；
  3. 回答正文非空且终态 `GENERATED` ⇒ 链没断在半路。

用法：
    python check_cross_scenario_e2e.py                       # 默认 http://localhost:5280
    python check_cross_scenario_e2e.py http://127.0.0.1:5280

前置：后端 + 前端 dev server（真栈）已起；11434 有对话模型（CI 用桩，本地用 Ollama）。
"""

from __future__ import annotations

import os
import sys
import tempfile
import time

# ★ 复用 S3 那条链的夹具与解析：手写第二套 multipart / SSE 解析只会多一处与真相对不上。
import check_s3_conversation_chain_e2e as s3

STAMP = time.strftime("%m%d%H%M%S")


def login() -> str:
    """登录 admin 拿 token（S3 模块里这段写在 main 里，这里抽出来一处用）。"""
    status, payload = s3.call(
        "POST", "/auth/login", body={"username": s3.USER, "password": s3.PASSWORD}
    )
    if status != 200:
        raise SystemExit(f"登录失败（{status}）：{payload[:200]}")
    token = str(s3.data_of(payload).get("accessToken") or "")
    if not token:
        raise SystemExit("登录成功但没拿到 accessToken")
    print("  已登录（admin）")
    return token


def check(ok: bool, message: str) -> bool:
    mark = "✓" if ok else "✗"
    print(f"  {mark} {message}")
    return ok


def main() -> int:
    print("== 跨场景整链闸门：S1 建的库 → S2 绑库的应用 → S3 会话里的引用 ==")
    failures = 0

    token = login()
    app_id = ""
    kb_id = ""
    created_model = False

    with tempfile.TemporaryDirectory(prefix="aip-cross-") as workdir:
        try:
            # ① S1 的路径：建库 + 真文档 + 解析 + 向量化
            kb_id, doc_id, model_id, created_model = s3.build_kb(token, STAMP, workdir)
            s3.note(f"① S1 路径建好的库 = {kb_id}（文档 {doc_id}）")

            # ② S2 的路径：建应用并绑**这个**库
            app_id = s3.create_assistant(token, model_id, kb_id, STAMP)
            s3.note(f"② S2 路径建好的应用 = {app_id}（绑库 {kb_id}）")

            # ③ S3 的路径：开会话提问
            conversation_id = s3.create_conversation(token, app_id)
            question = os.environ.get("AIP_CROSS_QUESTION") or "这份资料主要讲了什么？请引用原文作答。"
            status, frames, raw = s3.chat(
                token,
                {
                    "appId": app_id,
                    "conversationId": conversation_id,
                    "question": question,
                    "thinkingEnabled": False,
                },
            )
            if status != 200:
                print(f"  ✗ 提问返回 {status}：{raw[:300]}")
                return 1

            text = s3.text_of(frames)
            done = s3.done_of(frames)
            refs = s3.refs_of(frames)
            s3.note(f"③ 正文 {len(text)} 字 · 引用 {len(refs)} 条 · 终态 {done.get('status')}")

            failures += 0 if check(len(text) > 0, "回答正文非空") else 1
            failures += 0 if check(done.get("status") == "GENERATED", f"终态 GENERATED（实得 {done.get('status')}）") else 1
            failures += 0 if check(bool(refs), "拿到引用（没有引用就谈不上跨场景）") else 1

            # 判据 1：引用里的 kbId 必须**就是** ① 那个库
            hit = [r for r in refs if str((r or {}).get("kbId") or "") == kb_id]
            failures += 0 if check(
                bool(hit),
                f"引用指向 ① 建的库（{kb_id}）；实得 kbId = {sorted({str((r or {}).get('kbId')) for r in refs})}",
            ) else 1

            # 判据 2：segmentId 挂得住 —— 回读 200 且属于 ① 那份文档
            for ref in (hit or refs)[:1]:
                sid = str((ref or {}).get("segmentId") or "")
                code, payload = s3.call("GET", f"/segments/{sid}", token)
                body = s3.data_of(payload) if code == 200 else {}
                ok = code == 200 and str(body.get("documentId") or "") == doc_id
                failures += 0 if check(
                    ok,
                    f"引用可回读且属于 ① 的文档（segment {sid} → {code} · documentId={body.get('documentId')}）",
                ) else 1
        finally:
            # 清理：先删应用（否则库删不掉），再删库与临时模型
            if app_id:
                s3.call("DELETE", f"/assistants/{app_id}", token)
            if kb_id:
                s3.call("DELETE", f"/knowledge-bases/{kb_id}", token)
            if created_model:
                s3.call("DELETE", f"/models/{model_id}", token)

    if failures:
        print(f"\n== 跨场景整链：{failures} 条不成立 ==")
        return 1
    print("\n== 跨场景整链：全部成立 ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
