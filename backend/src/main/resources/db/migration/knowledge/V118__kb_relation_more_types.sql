-- 0.9.9：受控关系从四类扩到七类（任职 / 属于 / 依据）。
--
-- 为什么必须是一条新迁移：`kb_relation.type` 的允许值由 `chk_kb_relation_type` 钉死
-- （见 `V96`）。那条迁移已经应用过，改它就是改历史账本 —— Flyway 会报
-- `Migration checksum mismatch`（这坑在 `V96` 上踩过一次，见 `fbbde33b`）。
--
-- 新增三类的判据（与 `EntityExtractor#SHAPES` 一致）：
--   · WORKS_AT     人 → 组织。句子形如「张三任信息中心主任」，方向与句子一致。
--   · PART_OF      组织 → 组织，且**方向与 CONTAINS 相反**：
--                  「信息中心下设运维科」= CONTAINS（整体 → 部分）；
--                  「运维科属于信息中心」= PART_OF（部分 → 整体）。
--   · REFERENCES   谁 → 哪份文书。右端必须是**书名号**里的文书名（`EntityExtractor#DOC`）。
--
-- ★ 三类都不会"凭空多出来"：标记词由字典给（`kb_entity_dict` 的 `RELATION_MARK` 行），
--   而且两端都必须已经抽成实体 —— 抽不到就还是没有边。
alter table kb_relation drop constraint chk_kb_relation_type;

alter table kb_relation
  add constraint chk_kb_relation_type
  check (type in ('BELONGS_TO', 'CONTAINS', 'PART_OF', 'RESPONSIBLE_FOR', 'DEFINES',
                  'WORKS_AT', 'REFERENCES'));

comment on table kb_relation is
  '实体间的受控关系（七类）。不是知识图谱，但检索链路会消费它：自 2026-11-11 起 RelationRecallPort 把命中实体参与过的关系段并入候选（见 RetrievalService）。';

comment on column kb_relation.type is
  '受控类型七类：BELONGS_TO（隶属）/ CONTAINS（包含）/ PART_OF（属于）/ RESPONSIBLE_FOR（负责）/ DEFINES（定义）/ WORKS_AT（任职）/ REFERENCES（依据）。放开成任意字符串会让"受控关系"变成"自由关系"，下游没法按类型推理。';

-- ★ 存量一行要留意：`#377` 记录的那次方向错误 —— 内置字典曾把「下属」映射成
--   `BELONGS_TO`，于是「信息中心下属运维科」被抽成"信息中心隶属运维科"（反了）。
--   字典已改为映射 `CONTAINS`（上级包含下级，方向正确）。**存量不动**：
--   单凭 `from/to` 判断不出哪一行是「下属」抽出来的，硬改会把真正的"隶属"也改错。
--   存量若出现在某人库里，由人工在界面上确认后处理；本项目的验证库里那几行已经手工改过。
