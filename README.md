# ai-plantform

知识助理平台。前端已成工程（9 个模块可直接跑），后端待实现。

```
ai-plantform/
├─ frontend/             前端（Vue 3 + TS + Vite + Element Plus + Pinia）
│                        无 avue / 无 vuex / 无 moment / 无 BladeX
├─ backend/              后端（规划中，见 backend/README.md 的模块 ↔ 契约对应表）
├─ docs/                 可对外分发的设计文档（需求规格 + 系统设计 + ROADMAP）
├─ innerdoc/             ⛔ 内部材料，已被 .gitignore 忽略，不进公开仓库
└─ _archive/             ⛔ 老代码归档，已被 .gitignore 忽略（体量 + 许可双重原因）
```

---

## 启动前端

```powershell
cd D:\IdeaProjects\ai-plantform\frontend
npm install
npm run dev                  # → http://localhost:5280/
```

dev 模式自动走 mock（`.env.development` 的 `VITE_USE_MOCK=true`），**不需要后端**。

登录账号：`admin / admin`（管理员）、`zhangyun / 123456`（知识运营）、`liwei / 123456`（应用配置）、`chenjun / 123456`（普通用户）。后三个角色权限不同，可观察按钮显隐差异。

其它命令见 `frontend/README.md`。

---

## docs/ 索引

| 文件 | 内容 |
|---|---|
| `需求规格说明书/`（11 册） | **现状基线**：这个平台要解决什么、角色与边界在哪 |
| `系统设计方案/`（7 册） | **目标设计**：应该做成什么样（DDD · Spring Modulith · Spring AI） |
| `ROADMAP.md` | **下一步做什么**：从当前状态到可对外发布的路线与验收口径 |

---

## 目录约定

| 目录 | 说明 |
|---|---|
| `frontend/` | 前端工程（Vue 3 + Element Plus + Vite）。构建命令都在这个目录下执行 |
| `backend/` | 后端工程。当前只有 `README.md`（模块 ↔ 前端契约对应表） |
| `docs/` | 可对外分发的设计文档 |
| `innerdoc/` | **内部材料，整目录被 `.gitignore` 忽略**，不进公开仓库 |
| `_archive/` | **老系统归档，整目录被 `.gitignore` 忽略**（体量与许可双重原因，见 `.gitignore` 首段） |
