-- 第 1 层机械断言的两处落点（ROADMAP §11.63.2 / §11.63.5 判据 3）
--
-- 为什么用 text 存 JSON 而不是 jsonb：
--   断言定义与判定结果都只按「这道题 / 这条答案」整块读写，没有一处需要按断言内容做库侧查询或聚合。
--   用 text 让实体映射、迁移与单测都简单（Hibernate 不需要 JSON 类型支持，单测也不依赖容器）。
--   将来真要按断言统计时再换 jsonb 并加表达式索引 —— 那时是一次新建列的迁移，不算返工。
--
-- 为什么默认值是空数组而不是 null：
--   空数组 = 「这道题没设断言 / 这条答案没跑断言」，界面上必须显示「未设断言」，
--   不许显示「0 通过 0 失败」—— 后者会把"没测"读成"测了且都好"（本项目头号病 silent-failure）。
--
-- 为什么断言定义挂在题上、判定结果挂在答案上：
--   题是输入（可以随时改），答案是产出（一次运行一份快照）。
--   判定结果存快照而不是每次按当前定义重算：改了断言定义不该改写历史运行的读数，
--   要看新定义的结果就重新跑一次 —— 与 draft_text 整段存正文是同一条理由。

alter table as_prompt_eval_item
    add column if not exists assertions text not null default '[]';

alter table as_prompt_eval_answer
    add column if not exists assertion_results text not null default '[]';

comment on column as_prompt_eval_item.assertions is
    '这道题的机械断言定义（JSON 数组：[{id,label,kind,value}]）；空数组 = 没设断言';
comment on column as_prompt_eval_answer.assertion_results is
    '这条答案上每条断言的判定快照（JSON 数组：[{assertionId,label,kind,passed,detail}]）；空数组 = 跑时没设断言';
