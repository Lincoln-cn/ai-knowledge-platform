-- ════════════════════════════════════════════════════════════════════════════
-- modelcatalog 模块：枚举取值改机器码 —— ai_model.kind / status / supplier
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么不改 V8（旧迁移）：Flyway 对已执行的迁移做校验和比对，改了旧文件
--   会让所有环境的迁移历史失效（"checksum mismatch"）。旧文件一律不动，改用新增迁移。
--   与 V7 的口径一致。
--
-- ★ 为什么必须翻译存量数据：开发库/测试库的 ai_model 里可能已有旧码
--   （'chat'/'embedding'、'本地推理'/'外部API'、'未加载'/'加载中'/'已加载'/'加载失败'）。
--   若只改约束不改数据，旧码会被新约束拒掉。翻译是"改约束"的前置条件。
--
-- ★ 执行顺序：先 drop 旧约束 → 翻译数据 → 再加新约束。
--
-- ★ thinking / tools 的约束不变（已是 SCREAMING_SNAKE_CASE），本迁移不动它们。
--
-- ★ 新码口径（14 册 §2.1）：
--   ModelKind:    CHAT / EMBEDDING
--   ModelStatus:  NOT_LOADED / LOADING / LOADED / LOAD_FAILED
--   Supplier:     LOCAL_INFERENCE / EXTERNAL_API
-- ════════════════════════════════════════════════════════════════════════════

-- ── 1. 先 drop 旧约束 ──

alter table ai_model drop constraint chk_ai_model_kind;
alter table ai_model drop constraint chk_ai_model_supplier;
alter table ai_model drop constraint chk_ai_model_status;
alter table ai_model drop constraint chk_ai_model_chat_dimension;
alter table ai_model drop constraint chk_ai_model_embedding_context;

-- ── 2. 翻译存量数据 ──

update ai_model set kind = 'CHAT'      where kind = 'chat';
update ai_model set kind = 'EMBEDDING' where kind = 'embedding';

update ai_model set supplier = 'LOCAL_INFERENCE' where supplier = '本地推理';
update ai_model set supplier = 'EXTERNAL_API'    where supplier = '外部API';

update ai_model set status = 'NOT_LOADED'  where status = '未加载';
update ai_model set status = 'LOADING'     where status = '加载中';
update ai_model set status = 'LOADED'      where status = '已加载';
update ai_model set status = 'LOAD_FAILED' where status = '加载失败';

-- ── 3. 加新约束 ──

alter table ai_model
  add constraint chk_ai_model_kind check (kind in ('CHAT', 'EMBEDDING')),
  add constraint chk_ai_model_supplier check (supplier in ('LOCAL_INFERENCE', 'EXTERNAL_API')),
  add constraint chk_ai_model_status
    check (status in ('NOT_LOADED', 'LOADING', 'LOADED', 'LOAD_FAILED')),
  add constraint chk_ai_model_chat_dimension check (kind <> 'CHAT' or dimension is null),
  add constraint chk_ai_model_embedding_context check (kind <> 'EMBEDDING' or context_window is null);

-- ── 4. 更新列注释 ──

comment on column ai_model.status is
  '加载生命周期：NOT_LOADED / LOADING / LOADED / LOAD_FAILED（与 enabled 启用位正交）';
comment on column ai_model.kind is
  '模型种类：CHAT / EMBEDDING（决定 contextWindow 还是 dimension，两者互斥）';
comment on column ai_model.supplier is
  '供应商：LOCAL_INFERENCE / EXTERNAL_API（外部 API 必须带 apiBase/apiKey）';
