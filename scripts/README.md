# `scripts/` —— 本机启动器（断供期的人工读数要靠它们）

> ★ **为什么这些 `.cmd` 在仓库里而不是 `%TEMP%`**：`%TEMP%` 会被清理（**2026-10-15 早上就发现三个启动器全没了**），
> 而且没有版本记录 —— 于是"用户点启动器、agent 只做只读检查"这条纪律**当天就失效了**（用户点不到）。
> ⇒ **仓库里这一份是真源**；`%TEMP%` 下只留**薄包装**（两行，`call` 到这里的脚本），保证两边不会漂移。

## 四个启动器

| 脚本 | 起什么 | 端口 | 谁需要它 |
|---|---|---|---|
| `aip-fe-live.cmd` | 前端 dev（**mock** 模式 = `.env.development` 默认 `VITE_USE_MOCK=true`） | **5280** | ★ **11 道浏览器闸门**（`tools\run-frontend-gates.cmd`） |
| `aip-fe-real.cmd` | 前端 dev，**`VITE_USE_MOCK=false`** | **5280** | `verify_features.py` / `smoke_test.py`（真栈两道） |
| `aip-be-live.cmd` | 后端 Spring Boot（`SERVER_PORT=18088`） | **18088** | 真栈两道 + 任何端到端手工验收 |
| `aip-stub.cmd` | OpenAI 兼容假模型（`backend/tools/openai_stub.py`） | **11434** | 真栈两道 + ★ **三个桩依赖测试类**（`FullTextSearchTest` / `RetrievalHitTestTest` / `RetrievalSecurityTest`） |

## 用法

```
双击              —— 直接起（会开一个新窗口，关窗口即停）
cmd /c "scripts\aip-fe-live.cmd" --check     —— ★ 只验路径与前置，不起服务
```

★ **`--check` 是刻意加的**：agent **不许起服务**（纪律：服务由用户起），但"路径对不对、npm/python 在不在、包装脚本在不在"
这些**可以不起服务就验** —— 今天四个启动器就是这么验过的（全绿）。

## 两条硬约束（改这些 `.cmd` 之前先读）

| 约束 | 后果（本项目已实测踩到） |
|---|---|
| ★ **行尾必须 CRLF** | LF-only 的 `.cmd` 会被 cmd.exe 逐字节误读 ⇒ `'ayedexpansion' is not recognized`（它把 `setlocal enabledelayedexpansion` 读断了） |
| ★ **内容必须纯 ASCII** | 非 ASCII 注释被按 **GBK** 解析 ⇒ **吃掉后续行**（本项目栽过：中文注释连带吃掉了 `set VITE_USE_MOCK=false`，前端静默跑成 mock） |

★ 同族第三条（针对 `tools\run-frontend-gates.cmd` 里的内联 Python）：
**`python -c` 不许用 `\` 续行** —— cmd.exe 不认反斜杠续行，会把字面 `\` 交给 Python ⇒ `SyntaxError`，
★ 而这个错**看起来像"5280 没起"**（伪装成环境问题）。三条都写进了 `docs/无CI期开发与验证计划.md` §八。

## `aip-be-live.cmd` 的 18088 是**刻意**的

`frontend/.env.development` 里 `VITE_API_TARGET=http://127.0.0.1:18088` ⇒ **dev server 的代理目标与后端必须一致**。
★ 要改就**两边一起改**，只改一边的症状是"前端一切都好、请求全 502/超时"。
