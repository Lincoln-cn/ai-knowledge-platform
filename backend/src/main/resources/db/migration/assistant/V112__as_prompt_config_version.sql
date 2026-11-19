-- 提示词版本历史（ROADMAP §11.62）
--
-- 现象：as_prompt_config 只有 created_at / updated_at，updateConfig 最后一句是
--   configs.save(config) —— 原地覆盖。改一次，上一版永久消失。
-- 危害：不是"少个功能"，而是改动代价不对等 —— 修改很轻，回退不可能；
--   人一旦知道"改了就回不去"，就会倾向于不改，指令于是停在第一版。
--
-- 形状选择：新增版本表，**不把 as_prompt_config 改成"版本表 + 当前指针"**。
--   理由：所有读路径（应用配置选词、导出包、对话页提示词内核）都读当前表，
--   改指针形状会牵连一大片；让当前表继续当"最新一版"，版本表只追加。

create table if not exists as_prompt_config_version (
  id          uuid primary key default gen_random_uuid(),
  -- ★ on delete cascade：历史依附于配置。配置被删时它的历史无从查起（列表入口随配置一起消失），
  --   留着只会变成永远读不到的行。实测不加会直接挡住删除：FK violation on as_prompt_config。
  config_id   uuid not null references as_prompt_config (id) on delete cascade,
  version_no  int  not null,
  role_name   varchar(64)   not null,
  call_word   varchar(1000) not null,
  type_id     uuid          not null,
  description varchar(500)  not null default '',
  icon        varchar(16)   not null,
  kind        varchar(16)   not null default 'ROLE',
  app_id      uuid,
  saved_at    timestamptz   not null default now(),
  saved_by    uuid,
  constraint uq_as_prompt_config_version unique (config_id, version_no)
);

create index if not exists idx_as_prompt_config_version
  on as_prompt_config_version (config_id, version_no desc);

comment on table as_prompt_config_version is
  '提示词的历史版本（只追加）。as_prompt_config 始终是"最新一版"。';
comment on column as_prompt_config_version.version_no is
  '同一 config_id 内从 1 递增；数字大的是新的。';
comment on column as_prompt_config_version.saved_at is
  '这一版**被换下来的时刻**（即新版本的保存时间），不是它被创建的时刻。';
comment on column as_prompt_config_version.saved_by is
  '触发这次保存的操作人；迁移初始化的 v1 为 null。';

-- 把现存 26 条（14 条 ROLE + 12 条 QUESTION）初始化成 v1。
-- ★ 不初始化的话第一次点「历史」永远是空的 —— 用户会以为功能坏了。
--   时间取 created_at：那一版确实是当初建出来的样子。
insert into as_prompt_config_version
  (config_id, version_no, role_name, call_word, type_id, description, icon, kind, app_id, saved_at)
select c.id, 1, c.role_name, c.call_word, c.type_id, c.description, c.icon, c.kind, c.app_id, c.created_at
from as_prompt_config c
where not exists (
  select 1 from as_prompt_config_version v where v.config_id = c.id and v.version_no = 1
);
