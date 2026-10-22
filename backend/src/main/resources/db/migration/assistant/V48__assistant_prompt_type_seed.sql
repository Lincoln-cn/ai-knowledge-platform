-- 把「基础数据 → 提示词类型」里已有的四个类型映到 as_prompt_type（#196）。
--
-- 为什么需要这一步：类型的管理入口在「基础数据」字典页（V24 起就在那里），
-- 而提示词页的类型下拉读的是 `/prompt-types`。两处如果不做一次初始对齐，
-- 管理员明明在基础数据里看到四个类型，提示词页的下拉却是空的。
--
-- 只做一次初始 seed，不建立双向同步：后续新增类型仍以「基础数据」为准，
-- 由运维/接口把它补进 as_prompt_type（或等两套模型合并）。
insert into as_prompt_type (id, type_name, icon, created_at, updated_at)
select gen_random_uuid(), d.label, '📄', now(), now()
from platform_dict_item d
where d.kind = 'PROMPT_TYPE'
  and not exists (
      select 1 from as_prompt_type t
      where lower(t.type_name) = lower(d.label)
  );
