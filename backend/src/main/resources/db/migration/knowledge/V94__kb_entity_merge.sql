-- 实体合并的第三档：把「A 与 B 是同一个」这件事记录下来（#328 之后的 0.6.1 收尾）
--
-- ★ 为什么不是「把 B 删掉、把 A 的名字改掉」：
--   合并会改权限归属（实体带着 kb_id），而人工判断可能错。所以合并只做两件事：
--     ① 写一行别名（kb_entity_alias，A 的所有名字都列在那）
--     ② 在 B 上记 merged_into = A（B 的行**不删**，审计要能回答「谁合过谁」）
--   撤回合并 = 把 B 的 merged_into 清空 + 删掉那一行别名，A/B 都不受影响。
--
-- ★ 三档合并（见 #326 与 0.6 计划）：
--     强键相等（code 相同）     ⇒ 可以自动合
--     属性高度重叠              ⇒ 只生成候选，等人确认
--     只有名字相似（财务部/财务资产部）⇒ 只标疑似，不合（自动合会直接越权）
--   这一张迁移服务的是「人确认之后」那一步。

alter table kb_entity
    add column merged_into uuid references kb_entity (id);

comment on column kb_entity.merged_into is
    '合并到哪个实体上。null = 未合并。合并**不删行**：审计要能回答"谁合过谁"，撤回只是把这一列清空。';

-- 检索与列表都只看未合并的实体；部分索引让这个过滤不必扫全表
create index idx_kb_entity_active on kb_entity (kb_id, type) where deleted_at is null and merged_into is null;

-- 同一库里，一个别名只许指向一个实体（这是整个合并机制的地基：
-- 没有它，两次合并会让同一个名字同时属于两个实体，检索时返回谁的段全凭运气）
--
-- ★ 它是 V92 里用 `create unique index` 建的（不是 `constraint`），
--   所以注释要挂在 index 上 —— 写成 `comment on constraint` 会让这条迁移直接失败。
comment on index uq_kb_entity_alias_name is
    '同一库内别名唯一 —— 合并机制的地基。';
