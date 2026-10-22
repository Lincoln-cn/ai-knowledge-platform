-- 提示词类型与模板（#196）。
--
-- 设计册把提示词当作“应用的配置资产”：它不单列模块，但需要两实体——
--   1. 类型 = 分类法（前端“类别”下拉）；
--   2. 模板 = 可复用的角色指令（role_name + call_word），被 as_assistant_prompt 引用。
--
-- 本迁移只建表，不写 seed：默认分类由管理员在页面上建，避免“库里有一份、页面又建一份”的双真源。

create table as_prompt_type
(
    id         uuid         not null
        constraint pk_as_prompt_type primary key,
    type_name  varchar(64)  not null,
    icon       varchar(16)  not null,
    created_at timestamptz  not null,
    updated_at timestamptz  not null,
    constraint uq_as_prompt_type_name unique (type_name)
);

comment on table as_prompt_type is '提示词类型（分类法）；promptCount = as_prompt_config 中该类型的模板数。';

create table as_prompt_config
(
    id          uuid          not null
        constraint pk_as_prompt_config primary key,
    role_name   varchar(64)   not null,
    call_word   varchar(1000) not null,
    type_id     uuid          not null
        constraint fk_as_prompt_config_type references as_prompt_type (id),
    description varchar(500)  not null default '',
    icon        varchar(16)   not null,
    created_at  timestamptz   not null,
    updated_at  timestamptz   not null
);

comment on table as_prompt_config is '可复用的提示词模板；as_assistant_prompt.prompt_config_id 引用它。';
comment on column as_prompt_config.call_word is '提示词正文（角色指令），被应用选用后写入 role_prompt。';

create index idx_as_prompt_config_type on as_prompt_config (type_id);
create index idx_as_prompt_config_created on as_prompt_config (created_at desc);
