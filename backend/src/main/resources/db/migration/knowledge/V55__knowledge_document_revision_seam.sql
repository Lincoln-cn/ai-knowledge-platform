-- #86 文档版本：只留字段位置，不建版本链、不实现失效传播
--
-- ★ 为什么现在只留列：文档版本真正的成本不在"多两列"，而在"引用按版本解析"
--   （检索侧要知道一条引用指的是哪一版），那是 0.5.x 之后的事。
--   但**字段位置**现在不定，等做的时候就要动 kb_document 的写入路径与全部映射，返工面更大。
--
-- 口径（见 02 §5.1.2 与 12 §十一 新增小节）：
--   revision      —— 同一逻辑文档的第几版；今天恒为 1（没有代码写它）
--   supersedes_id —— 这一版取代的那一版（自引用）；今天恒为 NULL（没有代码写它）
--
-- ★ 不加自引用外键：留缝阶段的列不参与约束 —— 加了之后"彻底删除旧版本"会被外键挡住，
--   而彻底删除正是 #75 第二段要做的。等真做版本链时再决定是"级联删"还是"删前先断链"。
alter table kb_document add column revision int not null default 1;
alter table kb_document add column supersedes_id uuid;

comment on column kb_document.revision is
  '#86 留缝：同一逻辑文档的版本号。当前恒为 1 —— 没有代码读取或写入它（DocumentRevisionSeamTest 钉住这一点）';
comment on column kb_document.supersedes_id is
  '#86 留缝：本版取代的上一版（自引用）。
   当前恒为 NULL —— 重新上传同一份文档只会新增一条独立记录，两者之间今天没有任何关系';
