-- 触发器：把 V88 留的两列真正接上（#295 / 0.7.3）。
--
-- V88 加 trigger_type / trigger_cron 时写的是「V1.0 预留，今日无写入方与读取方」。现在改成实体，
-- 需要三样东西才能让「定时触发」可解释：
--   trigger_next_at —— 下次该跑的时刻。没有它，调度器只能每次全表扫 `cron` 再算，且无法回答
--                      「这一班错过了没有」；有了它，「到期」是一个可索引的谓词。
--   trigger_last_at —— 上次真的跑起来的时刻（不是"上次扫到"）。排障时第一问是"它到底跑过没"。
--   trigger_missed —— 错过次数。服务停机跨过若干个周期时，正确答案是「跑一次 + 记下错过了几次」，
--                      而不是把期间每一次都补跑一遍（补跑会在启动瞬间把外部模型打满）。
--
-- 约束写成「要么全空、要么三者齐全」：半配置（有 cron 没 next_at）会让调度器永远扫不到它，
-- 而界面上又显示"已启用定时" —— 那是静默失败。
alter table as_assistant
  add column trigger_next_at timestamptz,
  add column trigger_last_at timestamptz,
  add column trigger_missed integer not null default 0;

alter table as_assistant
  add constraint chk_as_assistant_trigger_type
  check (trigger_type is null or trigger_type in ('CRON'));

alter table as_assistant
  add constraint chk_as_assistant_trigger_cron
  check (
    (trigger_type is null and trigger_cron is null and trigger_next_at is null)
    or (trigger_type = 'CRON' and trigger_cron is not null and trigger_next_at is not null)
  );

-- 调度器唯一的查询形状：`trigger_next_at <= now()` 且没被删。部分索引让"绝大多数应用没开触发器"时
-- 这次扫描不碰主表的大多数行。
create index idx_as_assistant_trigger_due
  on as_assistant (trigger_next_at)
  where trigger_next_at is not null and deleted_at is null;

-- 运行记录要能回答「这一跑是谁发起的」。用 conversation_id 为空来区分"不是会话"是不够的：
-- 试用调试也是空的，而试用和定时是两件完全不同的事（前者有人盯着、后者没人）。
alter table as_workflow_record
  add column trigger_source varchar(16) not null default 'MANUAL';

alter table as_workflow_record
  add constraint chk_as_workflow_record_trigger_source
  check (trigger_source in ('MANUAL', 'CRON'));

comment on column as_assistant.trigger_type is '触发方式；null = 不自动触发。目前只有 CRON';
comment on column as_assistant.trigger_cron is '5 段 UNIX cron（分 时 日 月 周）；与 trigger_type 同时为 null 或同时有值';
comment on column as_assistant.trigger_next_at is '下次该跑的时刻；调度器据此判断到期，也是乐观锁的比对基准';
comment on column as_assistant.trigger_last_at is '上次真的跑起来的时刻（不是"上次扫到"）';
comment on column as_assistant.trigger_missed is '服务停机跨过的周期数；每次只补跑一次，这里记被跳过几次';
comment on column as_workflow_record.trigger_source is '这一跑的发起方：MANUAL = 人点试用，CRON = 定时触发';
