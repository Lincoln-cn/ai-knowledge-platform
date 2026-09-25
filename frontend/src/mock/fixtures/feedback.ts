import type { FeedbackItem } from '@/api/feedback'

/**
 * 反馈数据。
 *
 * 刻意覆盖目标设计里的每条路径，不是随手编的：
 *  - 状态机四态都要有实例（待处理 / 处理中 / 已处理 / 已忽略）
 *  - 赞 / 踩都要有；踩的那几条是「优化答案」的主场景
 *  - 有的带会话、有的不带（免登录公开问答也可能产生反馈）
 *  - 有的已沉淀为知识库问题，用来验证沉淀结果的回显
 *  - 处理人 / 处理时间 / 处理说明在已办理记录上齐全，验证新字段真的落盘
 */
export const feedbacks: FeedbackItem[] = [
  {
    id: 'fb1',
    appId: 'as1',
    appName: '合同审查助手',
    conversationId: 'c3',
    conversationTitle: '付款比例与尾款节点咨询',
    type: 'down',
    question: '采购合同的前期付款比例最高能到多少？超过多少需要特批？',
    answer: '根据《合同管理制度》的相关规定：前期付款比例原则上不超过合同总额的 30%，尾款需在验收合格后支付。超过 30% 需要业务部门负责人特批。',
    comment: '特批流程说错了，制度里写的是分管领导审批，不是业务部门负责人。而且没提紧急采购的例外。',
    status: '待处理',
    assignee: null,
    resolution: '',
    promotedToKbId: null,
    promotedToKbName: null,
    createdAt: '2026-02-13 09:18',
    handledAt: null
  },
  {
    id: 'fb2',
    appId: 'as1',
    appName: '合同审查助手',
    conversationId: 'c3',
    conversationTitle: '付款比例与尾款节点咨询',
    type: 'down',
    question: '如果对方不同意 3:7 的付款比例怎么办？',
    answer: '若对方不同意，建议先争取验收后付款；实在无法达成一致时，可以放宽到 5:5，但需在合同评审会记录原因。',
    comment: '5:7 这个比例制度里根本没有，是不是模型编的？',
    status: '待处理',
    assignee: null,
    resolution: '',
    promotedToKbId: null,
    promotedToKbName: null,
    createdAt: '2026-02-13 09:22',
    handledAt: null
  },
  {
    id: 'fb3',
    appId: 'as2',
    appName: '采购问答',
    conversationId: 'c1',
    conversationTitle: '供应商准入材料',
    type: 'down',
    question: '新供应商准入需要提交哪些材料？',
    answer: '一般需要营业执照、法人身份证、开户许可证、最近一年审计报告。',
    comment: '漏了「供应商调查表」和「廉洁承诺书」，这两份在现场考察前就要收。',
    status: '处理中',
    assignee: '张云',
    resolution: '已定位到《供应商管理办法》3.2 节，检索未命中该分段，准备补一条问答对。',
    promotedToKbId: null,
    promotedToKbName: null,
    createdAt: '2026-02-12 16:40',
    handledAt: null
  },
  {
    id: 'fb4',
    appId: 'as3',
    appName: '制度通',
    conversationId: null,
    conversationTitle: null,
    type: 'down',
    question: '年假可以跨年休吗？',
    answer: '年假原则上当年清零，特殊情况经部门负责人批准后可跨年，但最晚不超过次年 3 月 31 日。',
    comment: '我是通过公开链接问的，答案里的日期不对，新办法已经改成 6 月 30 日了。',
    status: '处理中',
    assignee: '管理员',
    resolution: '公开链接会话无登录态，需人工比对人事制度修订记录后更新知识库。',
    promotedToKbId: null,
    promotedToKbName: null,
    createdAt: '2026-02-12 11:05',
    handledAt: null
  },
  {
    id: 'fb5',
    appId: 'as1',
    appName: '合同审查助手',
    conversationId: 'c1',
    conversationTitle: '违约金上限咨询',
    type: 'down',
    question: '违约金最高能约定到多少？',
    answer: '违约金一般不超过合同总额的 20%，超过部分法院可能不予支持。',
    comment: '答案太笼统，最好能直接引用《民法典》条文号。',
    status: '已处理',
    assignee: '张云',
    resolution: '已沉淀为知识库问题并关联《合同管理制度》对应分段；同时在应用提示词中要求引用条文号。',
    promotedToKbId: 'kb1',
    promotedToKbName: '合同管理制度',
    createdAt: '2026-02-11 14:30',
    handledAt: '2026-02-12 10:15'
  },
  {
    id: 'fb6',
    appId: 'as2',
    appName: '采购问答',
    conversationId: 'c5',
    conversationTitle: '招标限额标准',
    type: 'down',
    question: '多少金额以上必须公开招标？',
    answer: '施工单项合同估算价 400 万元以上、货物 200 万元以上、服务 100 万元以上须公开招标。',
    comment: '这是国家的限额标准，我们集团内部标准更严，应该回答内部的。',
    status: '已处理',
    assignee: '李伟',
    resolution: '确认是知识缺失：知识库未收录集团内部招标限额。已沉淀问答对并关联采购制度分段。',
    promotedToKbId: 'kb2',
    promotedToKbName: '采购与供应商',
    createdAt: '2026-02-10 09:50',
    handledAt: '2026-02-11 15:40'
  },
  {
    id: 'fb7',
    appId: 'as3',
    appName: '制度通',
    conversationId: 'c3',
    conversationTitle: '差旅住宿标准',
    type: 'down',
    question: '去深圳出差住宿标准是多少？',
    answer: '一线城市住宿标准为每人每晚 500 元。',
    comment: '深圳是 550，而且旺季（高交会期间）可上浮 20%。',
    status: '已忽略',
    assignee: '张云',
    resolution: '用户所述为征求意见稿内容，尚未生效。现行制度仍是 500 元，暂不处理。',
    promotedToKbId: null,
    promotedToKbName: null,
    createdAt: '2026-02-09 17:12',
    handledAt: '2026-02-10 09:30'
  },
  {
    id: 'fb8',
    appId: 'as1',
    appName: '合同审查助手',
    conversationId: 'c2',
    conversationTitle: '质保金比例',
    type: 'up',
    question: '质保金一般扣多少？',
    answer: '质保金一般为合同总额的 3%，缺陷责任期满无质量问题后无息返还。',
    comment: '很准确，还给出了返还条件。',
    status: '已处理',
    assignee: '张云',
    resolution: '正向反馈，无需修改知识。归档备查。',
    promotedToKbId: null,
    promotedToKbName: null,
    createdAt: '2026-02-09 10:20',
    handledAt: '2026-02-09 18:00'
  },
  {
    id: 'fb9',
    appId: 'as2',
    appName: '采购问答',
    conversationId: 'c2',
    conversationTitle: '单一来源采购条件',
    type: 'down',
    question: '什么情况可以用单一来源采购？',
    answer: '只能从唯一供应商处采购的、发生了不可预见的紧急情况的、必须保证与原采购项目一致性的，可以采用单一来源。',
    comment: '没提「流标后转单一来源」的审批要求，我们单位踩过坑。',
    status: '待处理',
    assignee: null,
    resolution: '',
    promotedToKbId: null,
    promotedToKbName: null,
    createdAt: '2026-02-13 11:45',
    handledAt: null
  },
  {
    id: 'fb10',
    appId: 'as3',
    appName: '制度通',
    conversationId: 'c4',
    conversationTitle: '加班调休有效期',
    type: 'down',
    question: '加班调休的有效期是多久？',
    answer: '加班调休应在加班发生后 3 个月内休完，逾期作废。',
    comment: '我们部门是 6 个月，希望答案能按部门区分。',
    status: '待处理',
    assignee: '李伟',
    resolution: '',
    promotedToKbId: null,
    promotedToKbName: null,
    createdAt: '2026-02-13 13:02',
    handledAt: null
  }
]

/**
 * 「优化答案」可沉淀的目标知识库（FR-03-12 问答对）。
 *
 * 页面优先走 knowledgeApi.listKnowledgeBases；接口不可用时退回本表作为兜底目标。
 * id 与页面侧演示列表保持一致，保证沉淀回显的名称能对上。
 */
export const promotableKnowledgeBases = [
  { id: 'kb1', name: '合同管理制度' },
  { id: 'kb2', name: '采购与供应商' },
  { id: 'kb3', name: '人事制度' }
]
