-- 审计记录的**留存期清理**（`#75` 判据 ② 的一半：留存期不只是数字，还得有一条能执行的清理通道）。
--
-- 为什么需要它：`V56` 把 `pf_audit_log` 钉成"只允许插入"，于是它既不能改、也不能删 —— 这是对的，
-- 但"永远不删"本身不是企业级：`13 §2.4` / `deploy/README.md` 写的留存期（审计类 ≥6 个月、默认 12 个月）
-- 到了就得能删，否则留存期的数字只是文档里的一句话。
--
-- 三条设计：
--   ① 删除**只能**经由本函数，且只有它会把会话变量 `pf.allow_purge` 置为 `on`；
--      直接 `delete from pf_audit_log` 仍然被触发器挡住（这条由集成测试钉住）。
--   ② 允许删除的同时**仍然禁止改**：留存期需要的是"按时清掉旧的"，从来不需要"改历史"。
--   ③ 函数自己不做审计 —— 谁清了、清了多少，由调用方的业务事务写一条 `audit.purge`。
--      理由：审计写入走应用层（`AuditLogPort`），在库里再写一条会让"审计的写入点"变成两处。

create or replace function pf_audit_log_append_only() returns trigger as
$$
begin
  -- ★ 只有 DELETE 且显式放行时才通过。UPDATE 无论什么开关一律拒绝：
  --   "改一条历史记录"在任何场景下都不是留存期清理要做的事。
  if tg_op = 'DELETE' and current_setting('pf.allow_purge', true) = 'on' then
    return old;
  end if;
  raise exception 'pf_audit_log 只允许插入：审计记录不可修改、不可删除（13 §7.2 第 3 条）';
end;
$$ language plpgsql;

comment on function pf_audit_log_append_only() is
  '只追加守卫：UPDATE 一律拒绝；DELETE 仅在 pf.allow_purge=on（由 pf_audit_log_purge 设置）时放行';

-- 清理入口。返回真正删掉的行数 —— 调用方要把它写进审计，所以"删了几行"必须是返回值而不是由调用方再数一遍
-- （数一遍会与删除不在同一个语句里，中间可能又有新行过期）。
create or replace function pf_audit_log_purge(before_at timestamptz) returns bigint as
$$
declare
  removed bigint;
begin
  if before_at is null then
    raise exception '留存期清理必须给出截止时间（拒绝"删掉全部"这种调用）';
  end if;
  if before_at > now() then
    raise exception '截止时间不能在未来（% 晚于现在）', before_at;
  end if;

  -- true = 只在这个事务内有效：即使连接回到连接池，也不会把"可以删审计"的状态带出去。
  perform set_config('pf.allow_purge', 'on', true);
  delete from pf_audit_log where created_at < before_at;
  get diagnostics removed = row_count;
  perform set_config('pf.allow_purge', 'off', true);

  return removed;
end;
$$ language plpgsql;

comment on function pf_audit_log_purge(timestamptz) is
  '按截止时间清理审计记录（#75）：唯一允许删除的入口；返回删除行数；不接受 null 与未来时间';
