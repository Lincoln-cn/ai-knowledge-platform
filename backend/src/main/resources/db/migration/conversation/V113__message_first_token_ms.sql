-- 首字延迟：B4（≤10s）此前没有任何读数，因为这一列不存在。
--
-- 为什么必须是独立的一列，而不是从 duration_ms + tokens 推算：
--   · `duration_ms` 是**整轮**耗时（含检索、生成、收尾），首字延迟只到"第一个字"；
--   · 两者在长回答上相差一个数量级，拿 duration 冒充首字就是 B4 永远"达标"。
--
-- 为什么是 integer（毫秒）而不是 timestamp：
--   · 要的是"从提问到第一个字"的差值，落差值就不必在查询时做时区/时钟换算；
--   · 上限 24.6 天，远大于任何合理的首字延迟，够用且比 bigint 省一半。
--
-- 语义：空 = 这一轮**没有**可测的首字（未生成、被拒答、上游首字前就失败）。
--       空不等于 0 —— 统计 P50 时必须把这批排除，否则 0 会被算成"极快"。
alter table cv_message
  add column if not exists first_token_ms integer;

comment on column cv_message.first_token_ms is
  '从提问到收到第一个正文增量的毫秒数；空 = 这一轮没有可测的首字（未生成/拒答/首字前失败），不是 0。B4 的读数来源。';
