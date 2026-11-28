-- 实体类型加 MISSION（职责）：让「机构 → 职责」能成为图谱上的一条边。
--
-- 为什么需要这一类（2026-11-28，真实语料实测）：
--   20 篇真实法规、17.6 万字里，机构名后面跟的绝大多数是「应当 / 可以」（66 / 10 次），
--   「负责」只有 5 次 —— 法规是义务规范，不是组织关系网。要把这类材料里的东西抽出来，
--   靠的不是给标记词表加词，而是承认「机构 + 动词 + 宾语」这一侧本身就是一个关系：
--   `财政部主管全国企业会计工作` ⇒ `财政部 --RESPONSIBLE_FOR--> 全国企业会计工作`。
--
-- 为什么不把动宾短语直接塞进 kb_relation.to_entity：
--   边只由抽取产出、且 to_entity 是 kb_entity 的外键。把一串短语放进去，要么破外键，
--   要么每条边都新造一个没有类型的实体 —— 后者等于把类型约束悄悄取消了。

alter table kb_entity drop constraint kb_entity_type_check;

alter table kb_entity
    add constraint kb_entity_type_check
    check (type in ('ORG', 'PERSON', 'DOC', 'TERM', 'MISSION', 'OTHER'));

comment on column kb_entity.type is
    'ORG 组织 · PERSON 人名 · DOC 文书 · TERM 术语 · MISSION 职责（动宾短语的宾语，如「全国企业会计工作」）· OTHER 其他';
