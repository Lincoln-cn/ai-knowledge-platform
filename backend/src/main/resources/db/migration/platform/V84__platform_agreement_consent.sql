-- ★ #83 能力 2：服务协议与适用人群公示（第九条 / 第十条）的「同意留痕」落库。
--
-- 为什么要落库（不是纯前端弹窗）：「用户在什么时候、对哪个版本的服务协议点了同意」
-- 是举证记录 —— 弹窗本身没法回填历史，晚建表就只能从建表那天起算。
-- ★ 同意留痕是实现选择（法条要求的是「公示」与「防未成年人沉迷」，未规定同意留痕形式）：
--   留痕是为了采购与审计能回答「用户见过哪一版协议」。
-- 一用户一版本一行（unique(user_id, agreement_version)）⇒ 协议改版后旧版本的同意记录保留，
-- 新版本未同意 ⇒ 前端重新弹窗确认。

create table platform_agreement_consent (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  agreement_version text not null,
  agreed_at timestamptz not null,
  constraint platform_agreement_consent_uq unique (user_id, agreement_version)
);

create index platform_agreement_consent_user_idx on platform_agreement_consent (user_id, agreed_at desc);
