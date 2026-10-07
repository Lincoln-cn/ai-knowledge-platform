-- ════════════════════════════════════════════════════════════════════════════
-- knowledge 模块：kb_segment.classification（冗余密级，继承文档）—— 第 11 周 · 0.3.3
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 这是 `13 §6.4` 说的「分段冗余密级」，也是 `13 §8.3` 的 M2 验收项
--   （原文："**kb_segment 上可直接过滤（不 join）**"）。为什么必须冗余：
--
--   ① **密级必须能在 segment 表上直接生效** —— `content_tsv` 就在这张表上，
--      而两条检索路（向量 / 全文）都要带同一个密级谓词。谓词只长在 `kb_document` 上时，
--      "两路同谓词"就退化成"两处 join 写法必须一致"—— 而两处写法迟早会漂。
--   ② 与 `kb_segment.kb_id` 是**同一个理由**（`02` 原文："冗余，免连表"）。
--
-- ★ 为什么现在补（而不是"等以后再加"）：`13 §6.3` 把它列为最贵的两处耦合点之一 ——
--   "后加 ⇒ 回填全部历史分段（**几十万行**）并全量巡检一致性"。现在开发库只有几百行，
--   同样的 DDL 在这里是一次没人看得见的秒级操作；量产数据上则是要停机的事。
--
-- ★ 一致性怎么守（冗余列的代价，必须给出应对）：
--   · 写入侧**只有一个构造点**（`DocumentService.segmentAndPersist` 从文档密级传入），
--     且列是 `not null` 无默认值 ⇒ 漏传会在插入时**报错**，不会静默落一个 PUBLIC。
--   · `kb_segment_classification_drift` 巡检：文档密级 ≠ 分段密级 ⇒ 告警（见 §六）。
--   · 搬移分段（`Segment.moveTo`）必须**连密级一起搬** —— 搬过去之后它属于另一篇文档，
--     留着源文档的密级就是一次静默越权。
--
-- ★ 生成列口径与 `V21` 的 `kb_document.classification_rank` **逐字相同**：
--   两边必须同口径，否则"文档说能看、分段说不能看"会被算出来而不是被发现。
-- ════════════════════════════════════════════════════════════════════════════

-- ① 先加可空列，再回填（既有行不能凭空得到密级 —— 必须从它的文档继承）
alter table kb_segment
  add column classification varchar(16);

update kb_segment s
   set classification = d.classification
  from kb_document d
 where d.id = s.document_id;

-- ② 回填完才收紧成 not null：任何"没继承到密级"的分段都会在这一步暴露
alter table kb_segment
  alter column classification set not null;

alter table kb_segment
  add constraint chk_segment_classification
  check (classification in ('PUBLIC', 'INTERNAL', 'SECRET', 'CONFIDENTIAL'));

-- ③ 排序映射（与 V21 同一个 CASE，见文件头）
alter table kb_segment
  add column classification_rank smallint
    generated always as (
      case classification
        when 'PUBLIC' then 0
        when 'INTERNAL' then 1
        when 'SECRET' then 2
        when 'CONFIDENTIAL' then 3
        else 99
      end) stored;

-- ④ 检索谓词的支撑索引：`(kb_id, classification_rank)` ——
--    两条路的过滤都是「kb_id in (…) and classification_rank <= ?」，正是这个顺序。
--    为什么带上 kb_id：密级只有 4 档（低基数），单独给 rank 建索引不会被用到；
--    而检索**永远**先限定 kb_id（可见范围），所以前导列是它才有选择性。
create index idx_segment_kb_classification on kb_segment (kb_id, classification_rank);

comment on column kb_segment.classification is
  '分段密级。★ 强制继承所属文档（13 §2.1 ②），不允许单独下调 —— 允许的话"文档密级"就形同虚设（有人把机密文档的分段全标成公开即可绕过）';
comment on column kb_segment.classification_rank is
  '密级排序（0=PUBLIC … 3=CONFIDENTIAL，与 Java ClassificationLevel.ordinal() 逐位一致）。★ 检索两条路都靠它做同一个密级谓词（13 §三 硬要求）；未知取值落 99 = 对所有人都不可见（fail-closed）';
