-- 向量模型的**单段字符预算**（`#172`）。
--
-- ★★ 为什么需要新列，而不是复用 `context_window`：
--   本表 `context_window` 的注释写死了「**对话模型**：上下文窗口。**向量模型恒为 null**」，
--   而向量模型真正被登记的是 `dimension`（输出维度）。
--   ⇒ 「这个向量模型一次能安全吃多少**字**」这件事，**原本没有任何列承载**。
--
-- ★ 这条缺口造成过一个静默失效（已修）：`MaxCharsResolver` 一直在问向量模型的
--   `context_window`，拿到的永远是 null ⇒ 一路回落配置 1200 字 ⇒
--   **「本地 ONNX 必须被压到 400 字」这条口径在代码里从来没有生效过**，只是配置在起作用。
--
-- ★ 为什么单位是"字"而不是 token：分段与上限在业务侧都是**字数**（`SEGMENT_MAX_CHARS`，
--   界面显示"该段 N 字"）。把 token 换算成字的比例（0.78）是**估算**，估错了不会报错、
--   只会让尾部正文静默不进向量 —— 所以让"知道确切值的人"（登记模型的人）直接写这个数。
--
-- ★ 空值语义 = "没登记" ⇒ 解析时依次退：本列 → `context_window × 0.78` → 配置回落值。
--   不许把空值当成 0：0 会让所有分段都超长、入库全红。
alter table ai_model
    add column if not exists max_chars integer;

comment on column ai_model.max_chars is
    '向量模型的单段字符预算（字）。★ 空 = 未登记，解析时依次退：本列 → context_window×0.78 → 配置回落值。'
        '单位是**字**不是 token —— 与 SEGMENT_MAX_CHARS / 界面上的"N 字"同口径（#172）';

-- 本机本地 ONNX 模型（bge-small-zh-v1.5，窗口 512 token ⇒ 安全 399 字）的登记值。
-- ★ 用 update 而不是 insert：模型行由管理员在界面上录入，这里只补一列的值；
--   匹配条件用 base_model（同一基础模型可能注册多份配置，都该是同一个预算）。
update ai_model
set max_chars = 399
where base_model = 'bge-small-zh-v1.5'
  and max_chars is null;
