-- 模板中心（#295 的另一半）。
--
-- 为什么模板是**只读投影**而不是"应用的另一种形态"：
--   应用（as_assistant）会因为使用而被改（发布、计数、满意度），模板不会。
--   模板存的是"建这个应用时那一次的快照"，用它可以**再建一个新应用**，而之后两者各走各的。
--   ⇒ 所以这里存 payload（jsonb 快照），不做外键指向 as_assistant 的可变字段。
--
-- 为什么不像触发器那样只"留缝"：
--   `ROADMAP §11.7.1` 的准入判据是「§二 清单里有它的位置」或「现在不做会返工」。
--   模板中心两条都不满足严格版，但用户 2026-11-09 明确把它排进批次 2（m17795「批1-4 按你的规划开发」），
--   所以这一版是**做实体**，不是留缝。
--
-- ★ payload 里**只放"建应用需要的那几格"**，不放 callCount / satisfaction / createdAt 这些运行时计数 ——
--   模板一旦带上别的应用的统计值，克隆出来的应用会背上一个假的"已有 N 次调用"。

CREATE TABLE IF NOT EXISTS as_template (
    id           uuid PRIMARY KEY,
    name         varchar(100) NOT NULL,
    description  varchar(500) NOT NULL DEFAULT '',
    category     varchar(32)  NOT NULL DEFAULT 'GENERAL',
    -- 从哪个应用抽出来的；内置模板为 null
    source_app_id uuid,
    payload      jsonb        NOT NULL,
    builtin      boolean      NOT NULL DEFAULT false,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   uuid,
    deleted_at   timestamptz
);

COMMENT ON TABLE as_template IS '应用模板：payload 是"建一个这样的应用"所需的配置快照，克隆后与模板脱钩';
COMMENT ON COLUMN as_template.category IS 'GENERAL/LEGAL/HR/IT/FINANCE —— 列表页按它分组';
COMMENT ON COLUMN as_template.source_app_id IS '从哪个应用抽出来；内置模板为 null。不设外键：应用被删了模板应留下';
COMMENT ON COLUMN as_template.builtin IS '内置模板不允许删除与修改';

-- 列表页按"分类 + 名称"翻，且只翻未删的
CREATE INDEX IF NOT EXISTS idx_as_template_live
    ON as_template (category, name)
    WHERE deleted_at IS NULL;

-- 同名模板在未删范围内唯一（避免列表页出现两个一模一样的东西）
CREATE UNIQUE INDEX IF NOT EXISTS uq_as_template_name_live
    ON as_template (name)
    WHERE deleted_at IS NULL;
