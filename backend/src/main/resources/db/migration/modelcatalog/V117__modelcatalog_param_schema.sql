-- 0.9.8 §11.65.3 ③c：模型暴露给调用方的采样参数声明。
--
-- ★ 为什么用 text 存 JSON 而不是 jsonb：与 as_prompt_eval_item.assertions 同一条取舍 ——
--   这里只按"这个模型"整块读写，没有"按参数名查询/统计"的需求。将来真要按下发参数做统计，
--   换 jsonb 是一次新建列迁移，不算返工。
--
-- ★ 默认 '[]' 的含义是**这个模型没有暴露可调参数**，界面必须这样显示，
--   而不是渲染一张空表 —— 后者会被读成"可以调，只是还没填"。
--
-- ★★ 这张声明的唯一用途是让参数**真的进入上游请求**。只渲染表单、只落库、调用时不带，
--   比空表单更糟（用户以为调了，其实没生效）。
alter table ai_model
  add column if not exists param_schema text not null default '[]';

comment on column ai_model.param_schema is
  '模型可调参数声明（JSON 数组）：[{key,label,type,default,min,max,options,help}]。'
  'type ∈ number/integer/boolean/enum/string；空数组 = 没有暴露可调参数。';
