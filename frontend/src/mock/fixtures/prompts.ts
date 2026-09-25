import type { PromptConfig, PromptType } from '@/api/prompts'

/**
 * 提示词数据。
 *
 * 覆盖 7 合 1 后的两个实体：类型（旧 markedType）与配置（旧 markedConfig）。
 * 刻意让「合同类」下有多条配置、「通用」下有跨场景条目，便于验证类型筛选；
 * 一条配置被应用引用（usageCount > 0），用于验证删除保护。
 */
export const promptTypes: PromptType[] = [
  { id: 'pt1', typeName: '通用', icon: '💬', promptCount: 2 },
  { id: 'pt2', typeName: '合同法务', icon: '📜', promptCount: 3 },
  { id: 'pt3', typeName: '制度咨询', icon: '📋', promptCount: 2 },
  { id: 'pt4', typeName: '写作辅助', icon: '✍️', promptCount: 1 }
]

export const promptConfigs: PromptConfig[] = [
  {
    id: 'pc1',
    roleName: '合同审查助手',
    callWord: '你是企业合同审查助手。请严格依据提供的制度文件逐条列出风险，标注依据编号，并给出可执行的修改建议。不要编造不在引用中的条款。',
    typeId: 'pt2',
    typeName: '合同法务',
    description: '采购/销售合同风险审查，输出结构化风险清单',
    icon: '⚖️',
    usageCount: 2,
    createdAt: '2026-01-12T09:20:00Z'
  },
  {
    id: 'pc2',
    roleName: '条款解释',
    callWord: '用通俗语言解释合同条款的含义与潜在义务，指出对己方不利的表述，并给出谈判话术建议。',
    typeId: 'pt2',
    typeName: '合同法务',
    description: '面向业务人员的条款白话解释',
    icon: '🔍',
    usageCount: 0,
    createdAt: '2026-01-15T11:02:00Z'
  },
  {
    id: 'pc3',
    roleName: '违约责任分析',
    callWord: '聚焦违约责任、争议解决与赔偿上限三类条款，给出风险评级（高/中/低）与修改优先级。',
    typeId: 'pt2',
    typeName: '合同法务',
    description: '违约与争议解决专项分析',
    icon: '⚠️',
    usageCount: 0,
    createdAt: '2026-01-18T14:40:00Z'
  },
  {
    id: 'pc4',
    roleName: '报销制度顾问',
    callWord: '你是财务制度咨询助手。依据《报销管理制度》回答差旅、招待、采购报销的限额与流程问题。超出制度范围时明确说明并建议咨询财务。',
    typeId: 'pt3',
    typeName: '制度咨询',
    description: '报销限额、票据与审批流程答疑',
    icon: '🧾',
    usageCount: 1,
    createdAt: '2026-01-20T10:15:00Z'
  },
  {
    id: 'pc5',
    roleName: '入职引导',
    callWord: '依据人事制度回答入职材料、试用期、社保公积金等问题。语气亲切，分步骤说明，每步标注制度依据。',
    typeId: 'pt3',
    typeName: '制度咨询',
    description: '新员工入职流程与政策答疑',
    icon: '👋',
    usageCount: 0,
    createdAt: '2026-02-01T08:30:00Z'
  },
  {
    id: 'pc6',
    roleName: '通用问答',
    callWord: '你是企业知识助理。优先依据知识库回答；知识库没有覆盖时，明确说明并给出通用建议，不编造公司制度。',
    typeId: 'pt1',
    typeName: '通用',
    description: '默认角色设定，适合大多数知识库应用',
    icon: '🤖',
    usageCount: 3,
    createdAt: '2026-01-08T16:00:00Z'
  },
  {
    id: 'pc7',
    roleName: '摘要提炼',
    callWord: '将用户提供的长文本或引用分段压缩为 3–5 条要点，保留关键数字与条款编号，不添加原文没有的信息。',
    typeId: 'pt1',
    typeName: '通用',
    description: '长文/多分段摘要',
    icon: '📝',
    usageCount: 0,
    createdAt: '2026-02-03T13:22:00Z'
  },
  {
    id: 'pc8',
    roleName: '公文润色',
    callWord: '将用户草稿润色为正式公文语气：结构清晰、用词规范、避免口语。保留原意与关键数据，输出修改说明。',
    typeId: 'pt4',
    typeName: '写作辅助',
    description: '通知、请示、报告等公文润色',
    icon: '📄',
    usageCount: 0,
    createdAt: '2026-02-05T09:45:00Z'
  }
]
