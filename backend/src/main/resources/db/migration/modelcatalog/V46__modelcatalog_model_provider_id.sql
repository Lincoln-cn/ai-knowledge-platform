-- ════════════════════════════════════════════════════════════════════════════
-- modelcatalog 模块：ai_model.provider_id —— 模型引用服务商（V45 的配套）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 可空：null = 旧式直连 / 默认上游。已有模型**不自动迁移** ——
--    旧 key 是每个模型各自 AES-GCM 加密（随机 IV），SQL 无法安全比较/合并；
--    自动解密迁移风险更高。保留 legacy 回退，由管理员手动「绑定到服务商」。
--
-- ★ on delete restrict：有模型引用时不允许删服务商；服务层还会先给可读的 400。
-- ════════════════════════════════════════════════════════════════════════════

alter table ai_model
  add column provider_id uuid;

alter table ai_model
  add constraint fk_ai_model_provider
    foreign key (provider_id) references ai_model_provider (id) on delete restrict;

create index idx_ai_model_provider on ai_model (provider_id);

comment on column ai_model.provider_id is
  '绑定的服务商；null = 旧式直连 / 默认上游。有值时 apiBase/apiKey/egress 以服务商为准';
