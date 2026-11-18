-- 常用提示词种子（对话页「提示词」气泡与提示词管理页共用）。
--
-- ★ 为什么现在写 seed：`as_prompt_config` 一直是空的（V47 当时刻意不写，理由见下），
--   于是「提示词」这个功能在全新部署里永远显示空态 —— 功能是对的，只是没有人来填第一批。
--   V47 的原话是「避免库里一份、页面又建一份」的双真源；那说的是**类型**（分类法要单一来源），
--   而**模板正文**本来就该随产品一起给出一批可用的起点，不然每个部署方都要从零手写。
--
-- ★ 类型不新增，只用已有四个（写作辅助 / 制度咨询 / 合同法务 / 通用）：
--   类型的权威源在「基础数据 → 提示词类型」（V48 把字典搬过来的那条），
--   这里再建一个类型就是第二份分类法 ⇒ 管理员在基础数据里看不到它。
--
-- ★ 幂等靠 role_name 判：`uq` 没有建在 role_name 上（允许同名不同类），
--   但种子重复执行时必须不产生第二份，所以下面每条都在 `not exists` 里查自己。
--
-- ★ `call_word` 是**提示词正文**（`as_prompt_config.call_word` 的列注释：被应用选用后写入 role_prompt），
--   所以这里写的是给模型的操作指令，不是"提示词的名字"。

insert into as_prompt_config (id, role_name, call_word, type_id, description, icon, created_at, updated_at)
select gen_random_uuid(),
       '合同条款审查清单',
       '请逐条审查下面这份合同，按以下顺序给出结论：① 主体资格是否具备（名称、签章、授权代表）；② 标的与价款是否明确（金额、币种、含税口径、付款节点）；③ 履约期限与交付标准是否可执行；④ 违约责任是否对等（谁违约、怎么赔、上限多少）；⑤ 争议解决（管辖法院或仲裁机构是否唯一、是否可执行）。每条只写"合同原文怎么写的 + 是否有问题 + 依据"，原文没有覆盖的写「合同未约定」，不要替它补条款。',
       t.id,
       '逐项过一遍常见风险点，缺失的条款单独列出',
       '📑',
       now(),
       now()
from as_prompt_type t
where t.type_name = '合同法务'
  and not exists (select 1 from as_prompt_config c where c.role_name = '合同条款审查清单');

insert into as_prompt_config (id, role_name, call_word, type_id, description, icon, created_at, updated_at)
select gen_random_uuid(),
       '违约责任对等性检查',
       '请只看违约责任条款，判断双方义务是否对等：把甲方与乙方的违约情形、违约金比例（或计算方式）、赔偿上限、免责事由列成两列对照，指出不对等的地方。重点检查：只约束一方的条款、上限明显低于/高于对价、违约金超过法定上限的风险、"包括但不限于"这类开放式表述是否扩大到无限责任。结论只依据给出的合同原文，推断的地方标明「推断」。',
       t.id,
       '两侧违约条款并排对照，指出不对等之处',
       '⚖️',
       now(),
       now()
from as_prompt_type t
where t.type_name = '合同法务'
  and not exists (select 1 from as_prompt_config c where c.role_name = '违约责任对等性检查');

insert into as_prompt_config (id, role_name, call_word, type_id, description, icon, created_at, updated_at)
select gen_random_uuid(),
       '争议解决条款评估',
       '请评估这份合同的争议解决条款：① 约定的是诉讼还是仲裁，机构名称是否具体到可执行（仲裁机构写错名称会导致条款无效）；② 是否存在"或裁或审"这类无效约定；③ 管辖地与合同履行地、被告住所地是否有关联；④ 适用法律是否明确。给出结论时引用条款原文，并说明"改了会更稳妥"的具体替代表述。',
       t.id,
       '判断管辖与仲裁约定是否可执行',
       '🧭',
       now(),
       now()
from as_prompt_type t
where t.type_name = '合同法务'
  and not exists (select 1 from as_prompt_config c where c.role_name = '争议解决条款评估');

insert into as_prompt_config (id, role_name, call_word, type_id, description, icon, created_at, updated_at)
select gen_random_uuid(),
       '合同要点摘要',
       '请把这份合同压缩成一页要点：当事人、标的、金额与付款方式、起止时间、双方主要义务、违约责任上限、解除条件、争议解决方式。每项后面标注出自第几条（不确定就写「未找到明确条款」）。不要评价合同好坏，只做提取。',
       t.id,
       '把长合同压成一页，每项标条款号',
       '🗂️',
       now(),
       now()
from as_prompt_type t
where t.type_name = '合同法务'
  and not exists (select 1 from as_prompt_config c where c.role_name = '合同要点摘要');

insert into as_prompt_config (id, role_name, call_word, type_id, description, icon, created_at, updated_at)
select gen_random_uuid(),
       '制度条款溯源',
       '请回答问题时严格只依据检索到的制度文档，答案里每个结论后面都用「《制度名》第 X 条」标注出处。如果检索到的内容不足以回答，直接说「现有制度里没有覆盖这一点」，并列出你检索到的相关条款供我判断，不要用常识或行业惯例补答。',
       t.id,
       '每个结论都要能落回具体条款',
       '🔖',
       now(),
       now()
from as_prompt_type t
where t.type_name = '制度咨询'
  and not exists (select 1 from as_prompt_config c where c.role_name = '制度条款溯源');

insert into as_prompt_config (id, role_name, call_word, type_id, description, icon, created_at, updated_at)
select gen_random_uuid(),
       '多制度冲突对比',
       '下面这个问题可能涉及多份制度。请先列出所有相关条款，再逐项比较它们的差异（适用对象、标准、生效时间、例外情形）。如果两份制度对同一件事给的标准不同，如实说明两处分别怎么规定、各自的适用范围，**不要替我选一个**；如果无法从文档判断哪份优先，明确写出「需确认哪份是新版」。',
       t.id,
       '两份制度说法不一致时，先摆事实再让用户判断',
       '🔀',
       now(),
       now()
from as_prompt_type t
where t.type_name = '制度咨询'
  and not exists (select 1 from as_prompt_config c where c.role_name = '多制度冲突对比');

insert into as_prompt_config (id, role_name, call_word, type_id, description, icon, created_at, updated_at)
select gen_random_uuid(),
       '适用情形判断',
       '请根据制度原文判断我的情形是否适用：先复述你认为的制度适用条件（引用条款），再逐条对照我的情况（满足/不满足/无法判断），最后给结论。任何一条「无法判断」都要说明缺什么信息，不要用最接近的条款硬套。',
       t.id,
       '先列适用条件，再逐条对照，不许硬套',
       '🎯',
       now(),
       now()
from as_prompt_type t
where t.type_name = '制度咨询'
  and not exists (select 1 from as_prompt_config c where c.role_name = '适用情形判断');

insert into as_prompt_config (id, role_name, call_word, type_id, description, icon, created_at, updated_at)
select gen_random_uuid(),
       '流程步骤拆解',
       '请把制度里描述的流程拆成可执行步骤：每一步写明「谁做、做什么、用什么材料、交给谁、多久」。凡制度没写清的责任人或时限，标「制度未明确」，不要补。最后列一份需要的材料清单。',
       t.id,
       '把制度里的流程变成能照着做的步骤',
       '🪜',
       now(),
       now()
from as_prompt_type t
where t.type_name = '制度咨询'
  and not exists (select 1 from as_prompt_config c where c.role_name = '流程步骤拆解');

insert into as_prompt_config (id, role_name, call_word, type_id, description, icon, created_at, updated_at)
select gen_random_uuid(),
       '公文润色',
       '请润色下面这段公文，保持原意与事实不变，只改表达：把口语改成书面语、合并重复句、把"我们认为/大概"这类含糊表述改确定、长句拆短。逐句给出「原句 → 改后 + 改动理由」，最后再给一份完整改后稿。不要新增原文没有的事实、数字或依据。',
       t.id,
       '只改表达不改事实，逐句给理由',
       '✒️',
       now(),
       now()
from as_prompt_type t
where t.type_name = '写作辅助'
  and not exists (select 1 from as_prompt_config c where c.role_name = '公文润色');

insert into as_prompt_config (id, role_name, call_word, type_id, description, icon, created_at, updated_at)
select gen_random_uuid(),
       '会议纪要整理',
       '请把下面的会议记录整理成纪要：一、结论（已达成的决定，逐条）；二、待办（事项 / 负责人 / 截止时间，三项缺一就标「待定」）；三、悬而未决的分歧（各方观点分别写）。只写记录里出现过的信息，人名与时间对不上时标出来，不要替我补全。',
       t.id,
       '结论 / 待办 / 分歧三段，缺的标待定',
       '🗒️',
       now(),
       now()
from as_prompt_type t
where t.type_name = '写作辅助'
  and not exists (select 1 from as_prompt_config c where c.role_name = '会议纪要整理');

insert into as_prompt_config (id, role_name, call_word, type_id, description, icon, created_at, updated_at)
select gen_random_uuid(),
       '邮件回复撰写',
       '请帮我写一封回复邮件。先判断对方诉求有几点，逐点回应（同意 / 不同意并给理由 / 需要更多信息）。语气正式克制，不承诺我没有授权的条件；需要我提供的信息用【待补：…】标出，不要编造日期、金额或审批结果。给出一版全文，再附一句"如果对方追问 X，可以这样答"。',
       t.id,
       '逐点回应，未定的事标【待补】',
       '📧',
       now(),
       now()
from as_prompt_type t
where t.type_name = '写作辅助'
  and not exists (select 1 from as_prompt_config c where c.role_name = '邮件回复撰写');

insert into as_prompt_config (id, role_name, call_word, type_id, description, icon, created_at, updated_at)
select gen_random_uuid(),
       '要点提取',
       '请从下面这段内容里提取要点：用不超过 8 条，每条一句完整的话，按重要性排序。原文里的数字、日期、专有名词原样保留，不要换算或简称。提取不出要点时直接说「这段内容主要是背景铺陈，没有可执行要点」。',
       t.id,
       '最多 8 条，数字与专名原样保留',
       '🔍',
       now(),
       now()
from as_prompt_type t
where t.type_name = '通用'
  and not exists (select 1 from as_prompt_config c where c.role_name = '要点提取');

insert into as_prompt_config (id, role_name, call_word, type_id, description, icon, created_at, updated_at)
select gen_random_uuid(),
       '术语解释',
       '请解释我给的术语，分三层：① 一句话定义；② 在这个组织/行业里的具体含义与常见误用；③ 一个能对上现实工作的例子。如果检索到的资料里对这个术语的定义与通用定义不一致，以资料为准并指出来。资料里没有就明说「资料中未解释该术语」，给的通用解释要标明「非本组织资料」。',
       t.id,
       '一句定义 + 本地含义 + 例子，资料没有要标明',
       '💡',
       now(),
       now()
from as_prompt_type t
where t.type_name = '通用'
  and not exists (select 1 from as_prompt_config c where c.role_name = '术语解释');

insert into as_prompt_config (id, role_name, call_word, type_id, description, icon, created_at, updated_at)
select gen_random_uuid(),
       '多文档对比',
       '请对比下面几份文档：先给一张对照表（行是维度，列是文档），再总结"一致的地方 / 明确冲突的地方 / 只有一份提到的地方"。冲突项要引用双方原文，让我自己判断；不要把"没提到"当成"不同意"。',
       t.id,
       '对照表 + 一致/冲突/单向提及三栏',
       '📊',
       now(),
       now()
from as_prompt_type t
where t.type_name = '通用'
  and not exists (select 1 from as_prompt_config c where c.role_name = '多文档对比');
