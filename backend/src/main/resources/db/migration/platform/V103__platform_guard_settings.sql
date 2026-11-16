-- ════════════════════════════════════════════════════════════════════════════
-- platform 模块：安全围栏的分类词表与运行开关（0.9.2 · 安全围栏配置面）
-- ════════════════════════════════════════════════════════════════════════════
--
-- 契约：`GET|PUT /guard/settings` → `GuardSettings`
--       `{ inputEnabled, defaultRefusalText, advisorsEnabled, categoryRefusalEnabled, updatedAt }`
--       `GET|POST|PUT /guard/categories` → `GuardCategory[]`
--       `GET|POST|PUT /guard/rules`      → `GuardRule[]`
--       `GET /guard/hits`                → `GuardHitSummary[]`（只回计数，不回词面）
--
-- ★★ 为什么词表必须落库、而且要**分类型**：
--   今天唯一的围栏是 `SafeGuardChatAdvisor`，词表来自 `app.chat.guard.sensitive-words`
--   环境变量 —— 它的类注释自己写着「把词表写死在代码里会让"哪些话不能问"变成改代码
--   才能变的事，而它是运营口径」。但环境变量本身同样是"改配置 + 重启"，运营改不了。
--   分类型不是为了好看：**拒答口径与留痕要求跟着类型走**（政治类要留证、色情类要当场拒、
--   暴力类要看上下文），一条平铺的词表没法回答"这次拦的是什么性质的问题"。
--   `V100` 的那条纪律在这里同样成立：凡是界面上能开、读数能看到的策略开关，
--   都必须在持久化的参数里有格子。
--
-- ★ 为什么 `kind` 只有 KEYWORD / QUESTION 两个值：
--   `QUESTION` 是**敏感问题集**（整句接近才算命中，比"包含某个词"更难被绕过，也更容易
--   误伤）。SEMANTIC / REGEX 这些以后再说的值**不在 check 约束里穷举**，而是留一个
--   `kind` 的 varchar 值域 —— 加值时只改代码里的枚举，不改表结构。★ 但今天**没有**
--   第三种的匹配实现，`GuardRuleKind` 里只声明这两个，写别的值会在服务层 400。
--
-- ★ 为什么审计/命中留痕**不在这张表里**：
--   命中记录要按"分类 + 类型 + 规则 id + 归一化内容哈希前 8 位"记，**不记词面** ——
--   记了词面，围栏日志本身就成了"哪些话会被拦"的清单（与 REFUSAL 不复述命中词同一个理由）。
--   留痕走既有的操作审计，本表只管"规则是什么"。
--
-- ★ 为什么拒答话术默认**统一**（`category_refusal_enabled` 默认 false）：
--   按分类给不同话术会让攻击者用"回的是哪句话"反推自己撞到了哪一类，把一个黑盒变成
--   分类器。默认统一；开启它时要接受这个代价。
-- ════════════════════════════════════════════════════════════════════════════

create table pf_guard_category (
  id uuid primary key,
  -- 机器码：进审计、进契约、不许改（改了历史留痕就对不上）
  code varchar(32) not null unique,
  -- 展示名：运营可改
  name varchar(32) not null,
  sort_order integer not null default 0,
  -- 停用 = 该分类下的规则**全部不参与匹配**，但规则行还在（可恢复）
  enabled boolean not null default true,
  -- 内置分类不许删（删了历史审计里那个 code 就没有显示名可对照）；允许停用与改名
  builtin boolean not null default false,
  -- 该分类的专属拒答话术。★ 可空 = 用 pf_guard_settings.default_refusal_text 兜底。
  --   ★★ 它只在 `category_refusal_enabled = true` 时才被采用，默认关 —— 给不同分类不同话术，
  --   等于告诉攻击者"你撞到的是哪一类"（见 settings 表里那条注释）。
  refusal_text varchar(200),
  created_at timestamptz not null default now()
);

comment on table pf_guard_category is
  '安全围栏的分类（政治/色情/暴力…）。拒答口径与留痕要求跟着分类走，一条平铺词表回答不了"拦的是哪一类"。';

create table pf_guard_rule (
  id uuid primary key,
  category_id uuid not null references pf_guard_category (id) on delete cascade,
  -- 匹配类型：KEYWORD = 归一化后"包含"；QUESTION = 归一化后"包含"但去掉标点（敏感问题集）
  kind varchar(16) not null check (kind in ('KEYWORD', 'QUESTION')),
  -- 原始写法（运营看到的那个）
  content text not null,
  -- 归一化结果（去空白、全角转半角、小写；QUESTION 另去标点）。★ 落库而不是每次算：
  -- 匹配发生在每次请求上，而"怎么归一化"是代码 —— 落库让两边看到的是同一份字符串。
  normalized text not null,
  enabled boolean not null default true,
  note text,
  created_by uuid,
  updated_at timestamptz,
  -- 同一分类同一类型下不许有两条归一化后相同的规则（否则删一条还剩一条，看起来"删不掉"）
  unique (category_id, kind, normalized)
);

create index idx_pf_guard_rule_category on pf_guard_rule (category_id);
create index idx_pf_guard_rule_enabled on pf_guard_rule (category_id, enabled);

comment on table pf_guard_rule is
  '安全围栏的规则（关键词 / 敏感问题集）。normalized 是落库的归一化结果，匹配只用它。';

create table pf_guard_settings (
  -- 单行表：第二行插不进去（同 `cv_chat_settings` / `pf_retrieval_settings`，不靠"约定只有一行"）
  id boolean primary key check (id),
  -- 输入侧总开关。★ 关掉 = 整个围栏不参与匹配 —— 它必须存在（部署方可能要求"先只观察"），
  -- 但审计里要能看出"这段时间是关着的"。
  input_enabled boolean not null default true,
  -- 统一拒答话术。默认值与 `SafeGuardChatAdvisor.REFUSAL` 一字不差（两边不一致的症状是
  -- "界面上看到的和用户收到的不是一句"）
  default_refusal_text varchar(200) not null
    default '抱歉，这个问题不在可回答范围内。',
  -- advisor 链总开关（与 `app.chat.advisors.enabled` 对齐：关着上线等于安全闸门不生效）
  advisors_enabled boolean not null default true,
  -- 按分类给不同拒答话术。默认关：开了就等于告诉攻击者"你撞到的是哪一类"
  category_refusal_enabled boolean not null default false,
  updated_at timestamptz,
  updated_by uuid
);

insert into pf_guard_settings (id) values (true);

comment on table pf_guard_settings is
  '安全围栏的运行开关（单行）。库是运行时真源，application.yml 只在库读不到时兜底。';
comment on column pf_guard_settings.category_refusal_enabled is
  '按分类给不同拒答话术。默认 false：开启后攻击者可用"回的是哪句话"反推撞到哪一类。';

-- ★ 内置分类：用户 2026-11-16 原话「安全词典需要分类型存储，比如政治、色情、暴力、这样的」。
--   「未分类」是给 `CHAT_GUARD_SENSITIVE_WORDS` 播种用的落点（播种出来的词不知道该归哪类）。
--   ★ 不预置任何规则：默认空不是"忘了配"，而是由部署方按自己的合规要求填
--     （与 `application.yml` 那句注释同一个理由）。
insert into pf_guard_category (id, code, name, sort_order, builtin)
values
  ('11111111-1111-4111-8111-111111111111', 'POLITICS', '政治', 10, true),
  ('22222222-2222-4222-8222-222222222222', 'PORN', '色情', 20, true),
  ('33333333-3333-4333-8333-333333333333', 'VIOLENCE', '暴力', 30, true),
  ('00000000-0000-4000-8000-000000000000', 'UNCLASSIFIED', '未分类', 999, true);
