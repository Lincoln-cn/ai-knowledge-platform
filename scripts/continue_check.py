"""断点续写（CONTINUE）链路的一条命令验收（#371 第 41 条 / ROADMAP §11.65.3 ③b）。

跑法：
    python scripts/continue_check.py                 # 自动挑第一个可见应用
    python scripts/continue_check.py --app <appId>    # 指定执行环境

它验的是「续写」这一条链，四条判据一条都不许退让：

1. **同一段答案里接着写**：续写前后 `cv_message` 的行数不变（续写不新增消息）；
2. **前半段还在**：续写后的正文以续写前的正文开头，而且更长；
3. **引用不变**：续写不重新检索 ⇒ 引用条数与 segmentId 集合都不变；
4. **能连续续写**：第二次再续一次，上面三条仍成立。

怎么造出「已停止」这个前置状态：先开一条流，读到第一段增量就调 stop，然后继续把流读完 ——
`done` 帧会给出 `STOPPED`。生成太快（`done` 先到、状态是 `GENERATED`）时重试，最多三轮。

★ 为什么要在这里验而不是只跑单测：`Message.resume()` 的守卫、`prepareContinue` 的落库、
`ChatService` 的「续写不重新检索」三件事分别有单测，但它们拼起来是不是「点继续就接着写」，
只有真栈能回答 —— 单测里那条流的 refs 帧是假的。
"""

import json
import sys
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:18088/api/v1"
TIMEOUT = 30

OK = []
BAD = []


def check(passed, label, detail=""):
    line = ("[PASS] " if passed else "[FAIL] ") + label + (f" —— {detail}" if detail else "")
    print(line)
    (OK if passed else BAD).append(label)
    return passed


def call(path, token=None, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def call_json(path, token=None, method="GET", body=None):
    st, txt = call(path, token, method, body)
    try:
        return st, json.loads(txt)
    except Exception:
        return st, {"_raw": txt[:400]}


def page_rows(response):
    """分页包络取列表：后端的 PageResult 用 `list`，个别端点历史上用过 `records`/`items`。"""
    data = response.get("data") or {}
    return data.get("list") or data.get("records") or data.get("items") or []


def login():
    st, r = call_json("/auth/login", method="POST", body={"username": "admin", "password": "admin"})
    if st != 200:
        print(f"[FAIL] 登录失败：{st} {r}")
        sys.exit(1)
    data = r.get("data") or {}
    token = data.get("accessToken") or data.get("token")
    if not token:
        print(f"[FAIL] 登录响应里没有令牌：{r}")
        sys.exit(1)
    return token


def pick_app(token, wanted):
    if wanted:
        return wanted
    st, r = call_json("/assistants?page=1&size=20", token)
    rows = page_rows(r)
    for row in rows:
        if row.get("id"):
            return row["id"]
    return None


def stream_chat(token, body, on_meta=None, on_delta=None, stop_after=None):
    """发一次聊天流，返回 (events, done_payload)。

    `stop_after`：累计增量到这么多字时调用 on_delta 之外的停止动作（由调用方在 on_delta 里做）。
    """
    req = urllib.request.Request(BASE + "/conversations/chat", data=json.dumps(body).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "text/event-stream")
    req.add_header("Authorization", "Bearer " + token)

    done = None
    events = []
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        event = None
        for raw in r:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if not line:
                event = None
                continue
            if line.startswith(":"):
                continue
            if line.startswith("event: "):
                event = line[len("event: "):]
                continue
            if line.startswith("data: "):
                try:
                    payload = json.loads(line[len("data: "):])
                except Exception:
                    continue
                events.append((event, payload))
                if event == "meta" and on_meta:
                    on_meta(payload)
                elif event == "delta" and on_delta:
                    on_delta(payload.get("text", ""))
                elif event == "done":
                    done = payload
    return events, done


def messages_of(token, conversation_id):
    st, r = call_json(f"/conversations/{conversation_id}/messages?page=1&size=200", token)
    return page_rows(r)


def ask_then_stop(token, conversation_id, app_id, question):
    """问到一半停止 ⇒ 得到一条 STOPPED 的助手消息。"""
    got = {"messageId": None, "text": "", "stopped": False}

    def on_meta(p):
        got["messageId"] = p.get("messageId")

    def on_delta(chunk):
        if got["stopped"]:
            return
        got["text"] += chunk
        if len(got["text"]) >= 20 and got["messageId"]:
            got["stopped"] = True
            call(f"/conversations/{conversation_id}/messages/{got['messageId']}/stop", token, method="POST")

    body = {"conversationId": conversation_id, "question": question}
    if app_id:
        body["appId"] = app_id
    _, done = stream_chat(token, body, on_meta=on_meta, on_delta=on_delta)
    return got["messageId"], (done or {}).get("status"), got["text"]


def main():
    args = sys.argv[1:]
    wanted = None
    if "--app" in args:
        wanted = args[args.index("--app") + 1]

    token = login()
    app_id = pick_app(token, wanted)
    print(f"已登录 admin（{BASE}）· 应用 {app_id or '（通用问答）'}")

    st, conv = call_json("/conversations", token, "POST", {"appId": app_id} if app_id else {})
    if st not in (200, 201):
        print(f"[FAIL] 建会话失败：{st} {conv}")
        return 1
    conversation_id = (conv.get("data") or {}).get("id")
    print(f"会话：{conversation_id}")

    # ---------- 造一条「已停止」的回答 ----------
    message_id, status, head = None, None, ""
    for attempt in range(3):
        message_id, status, head = ask_then_stop(
            token, conversation_id, app_id, "用三句话说明向量检索是怎么找相似内容的。"
        )
        if status == "STOPPED":
            break
        print(f"  第 {attempt + 1} 轮没停下来（status={status}），重试")
        # 下一轮换一条会话，免得上一轮的 GENERATING/GENERATED 干扰
        st, conv = call_json("/conversations", token, "POST", {"appId": app_id} if app_id else {})
        conversation_id = (conv.get("data") or {}).get("id")

    if not check(message_id is not None and status == "STOPPED", "前置：拿到一条「已停止」的回答",
                 f"messageId={message_id} status={status}"):
        return 1

    before = messages_of(token, conversation_id)
    target = next((m for m in before if m.get("id") == message_id), None)
    if not check(target is not None, "前置：这条消息在消息列表里", f"共 {len(before)} 条"):
        return 1
    head_text = target.get("content") or ""
    head_refs = {r.get("segmentId") or r.get("id") for r in (target.get("refs") or [])}
    print(f"  前半段 {len(head_text)} 字 · 引用 {len(head_refs)} 条 · 会话里 {len(before)} 条消息")

    # ---------- 续写 ----------
    body = {"conversationId": conversation_id, "mode": "CONTINUE", "targetMessageId": message_id}
    if app_id:
        body["appId"] = app_id
    events, done = stream_chat(token, body)
    kinds = [e for e, _ in events]
    deltas = sum(len(p.get("text", "")) for e, p in events if e == "delta")
    print(f"  续写流：{len(events)} 帧（delta {kinds.count('delta')} 个 · {deltas} 字）· done={done}")
    for e, p in events:
        if e in ("error", "refs"):
            print(f"    {e} 帧载荷：{json.dumps(p, ensure_ascii=False)[:300]}")

    check(done is not None, "续写有收尾帧", f"事件序列 {kinds[:3]}…{kinds[-3:] if kinds else ''}")
    errors = [p for e, p in events if e == "error"]
    check(not errors, "续写流里没有 error 帧", json.dumps(errors, ensure_ascii=False)[:200] if errors else "")

    # ★ 续写**必须**发一次 refs 帧（装配器要求第一个 delta 之前 refs 已到），但内容必须是原来那批：
    #   发空数组 = 把引用栏清掉，发新的一批 = 它偷偷重新检索了。两种都不是「沿用」。
    refs_frames = [p for e, p in events if e == "refs"]
    if check(len(refs_frames) == 1, "续写恰好发一次引用帧", f"收到 {len(refs_frames)} 次"):
        sent = {r.get("segmentId") or r.get("id") for r in (refs_frames[0].get("refs") or [])}
        check(sent == head_refs, "续写沿用的是原来那批引用", f"发 {len(sent)} 条 · 原 {len(head_refs)} 条")

    after = messages_of(token, conversation_id)
    check(len(after) == len(before), "续写不新增消息",
          f"续写前 {len(before)} 条 · 续写后 {len(after)} 条")

    target2 = next((m for m in after if m.get("id") == message_id), None)
    if not check(target2 is not None, "续写后同一条消息还在", f"id={message_id}"):
        return 1
    text2 = target2.get("content") or ""
    refs2 = {r.get("segmentId") or r.get("id") for r in (target2.get("refs") or [])}
    check(text2.startswith(head_text), "前半段原样保留（续写接在后面）",
          f"前半段 {len(head_text)} 字 · 现在 {len(text2)} 字")
    check(len(text2) > len(head_text), "正文变长了", f"+{len(text2) - len(head_text)} 字")
    check(refs2 == head_refs, "引用不变", f"续写前 {len(head_refs)} 条 · 续写后 {len(refs2)} 条")
    check(target2.get("status") in ("GENERATED", "STOPPED"), "状态回到终态", f"status={target2.get('status')}")

    # ---------- 再续一次（第二次续写要能接上） ----------
    if target2.get("status") == "STOPPED":
        third = dict(body)
        events3, done3 = stream_chat(token, third)
        after3 = messages_of(token, conversation_id)
        target3 = next((m for m in after3 if m.get("id") == message_id), None)
        text3 = (target3 or {}).get("content") or ""
        check(done3 is not None and text3.startswith(text2) and len(text3) > len(text2),
              "第二次续写仍接在同一段后", f"{len(text2)} → {len(text3)} 字")
        check(len(after3) == len(before), "两次续写都不新增消息", f"{len(before)} → {len(after3)}")
    else:
        print("[SKIP] 第二次续写：这一轮已经生成完（GENERATED），没有可续的「已停止」状态")

    # ---------- 清理 ----------
    st, _ = call(f"/conversations/{conversation_id}", token, method="DELETE")
    print(("已清理会话 " if st in (200, 204) else f"清理会话返回 {st} ") + str(conversation_id))

    print()
    print(f"通过 {len(OK)} 条 · 失败 {len(BAD)} 条")
    if BAD:
        print("失败项：" + " / ".join(BAD))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
