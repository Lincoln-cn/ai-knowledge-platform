import type { KnowledgeBase, KnowledgeCategory } from '@/api/knowledge'
import type { DocumentItem, Segment } from '@/api/ingestion'
import type { RetrievalParams } from '@/api/retrieval'
import { kbSharedWithChenjun, kbSharedWithUserRole } from './grants'

/**
 * 知识库 / 文档 / 分段 / 检索参数的 mock 数据。
 *
 * 刻意覆盖目标设计要求的全部状态与边界：
 *  - 文档六态（含两种失败）+ 行内进度（196/340）
 *  - 失败原因与建议（DOC-I2）
 *  - 超长分段（SEG-I1）与普通分段并存
 *  - 启用/停用知识库、文档、分段
 *  - 空库（用于空态）、已有向量化内容（向量模型锁定 KB-I4）
 *  - 归属分散在 u1–u4、三种可见性各至少一个（07 §3.1）：这样才验证得出过滤真的生效 ——
 *    chenjun（普通用户，`dataScope: 'SHARED'`）看得见 kb1 / kb3 / kb4，看不见 kb2（别人的 private）
 */

const now = '2026-02-13T11:18:37Z'

/**
 * 演示用解析正文：故意覆盖 标题 / 列表 / 表格 三种块结构 ——
 * 「解析结果按结构渲染」是 0.2.1 批 3 的演示判据，夹具里必须真有结构可看。
 */
const PARSED_SAMPLE = `# 采购管理制度

## 第一章 总则

为规范合同管理，防范经营风险，保障公司合法权益，根据国家有关法律法规及公司章程，制定本制度。

## 第二章 采购流程

### 2.1 供应商准入

- 供应商应具备独立法人资格
- 注册资本不少于人民币一百万元
- 近三年无重大违法记录

### 2.2 验收标准

| 项目 | 标准 | 责任部门 |
| --- | --- | --- |
| 外观检查 | 无破损、无污渍 | 采购部 |
| 数量核对 | 与合同一致 | 仓储部 |
| 质量抽检 | 合格率 ≥ 97% | 质量部 |

1. 验收不合格的，应在三个工作日内书面通知供应商
2. 可返工的限期返工，不可返工的作退货或索赔处理
3. 验收记录归档保存不少于十年
`

export const knowledgeCategories: KnowledgeCategory[] = [
  { id: 'cat1', name: '制度规范', baseCount: 2 },
  { id: 'cat2', name: '产品资料', baseCount: 1 },
  { id: 'cat3', name: '行业标准', baseCount: 1 },
  { id: 'cat4', name: '其他', baseCount: 0 }
]

export const knowledgeBases: KnowledgeBase[] = [
  {
    id: 'kb1',
    name: '合同管理制度',
    category: '制度规范',
    description: '合同订立、履行、变更与争议解决相关制度，支撑合同审查类问答',
    icon: '📘',
    importType: 'LOCAL',
    embeddingModelId: 'm5',
    embeddingModelName: 'mofanke/dmeta-embedding-zh',
    embeddingLocked: true,
    enabled: true,
    documentCount: 4,
    segmentCount: 510,
    charCount: 210800,
    createdAt: '2026-02-01T09:00:00Z',
    maxClassification: 'SECRET',
    ownerId: 'u2',
    // 共享给「普通用户」角色 → 普通用户只读可见
    visibility: 'SHARED',
    grants: kbSharedWithUserRole
  },
  {
    id: 'kb2',
    name: '采购验收规范',
    category: '制度规范',
    description: '采购验收流程、质检标准与不合格处置。当前已停用，不参与检索',
    icon: '📗',
    importType: 'LOCAL',
    embeddingModelId: 'm5',
    embeddingModelName: 'mofanke/dmeta-embedding-zh',
    embeddingLocked: true,
    enabled: false,
    documentCount: 2,
    segmentCount: 86,
    charCount: 41200,
    createdAt: '2026-02-03T14:20:00Z',
    maxClassification: 'INTERNAL',
    // 别人的 private：只有归属人（u2）与全平台档（ADMIN/KB_OPERATOR/APP_CONFIG）看得见
    ownerId: 'u2',
    visibility: 'PRIVATE'
  },
  {
    id: 'kb3',
    name: '产品白皮书',
    category: '产品资料',
    description: '产品架构、能力边界与部署说明',
    icon: '📙',
    importType: 'LOCAL',
    embeddingModelId: 'm5',
    embeddingModelName: 'mofanke/dmeta-embedding-zh',
    embeddingLocked: true,
    enabled: true,
    documentCount: 3,
    segmentCount: 220,
    charCount: 98000,
    createdAt: '2026-02-05T10:30:00Z',
    maxClassification: 'CONFIDENTIAL',
    ownerId: 'u3',
    // 共享给 chenjun 本人（`user` 主体，与 kb1 的 `role` 主体并存）→ 演示两种主体都生效
    visibility: 'SHARED',
    grants: kbSharedWithChenjun
  },
  {
    id: 'kb4',
    name: '行业标准汇编',
    category: '行业标准',
    description: '相关国标、行标摘录。刚建好，还没有导入文档',
    icon: '📕',
    importType: 'LOCAL',
    embeddingModelId: 'm6',
    embeddingModelName: 'bge-m3',
    embeddingLocked: false,
    enabled: true,
    documentCount: 0,
    segmentCount: 0,
    charCount: 0,
    createdAt: '2026-02-12T16:00:00Z',
    maxClassification: 'PUBLIC',
    // public = 登录用户里人人可见（**不等于免登录**）；持有 knowledge:view 即可见
    ownerId: 'u1',
    visibility: 'PUBLIC'
  }
]

export const documents: DocumentItem[] = [
  {
    id: 'doc1',
    kbId: 'kb1',
    name: '采购管理制度.pdf',
    source: 'local',
    status: 'SUCCEEDED',
    segmentCount: 128,
    processedSegments: 128,
    charCount: 62000,
    enabled: true,
    failReason: null,
    failHint: null,
    // ★ 解析结果带标题/列表/表格 —— 演示判据：Markdown 要按结构渲染
    parsedContent: PARSED_SAMPLE,
    createdAt: '2026-02-01T09:10:00Z',
    updatedAt: '2026-02-01T09:25:00Z'
  },
  {
    id: 'doc2',
    kbId: 'kb1',
    name: '合同模板汇总.docx',
    source: 'local',
    status: 'EMBEDDING',
    segmentCount: 340,
    processedSegments: 196,
    charCount: 128000,
    enabled: true,
    failReason: null,
    failHint: null,
    parsedContent: '# 合同模板汇总\n\n## 通用条款\n\n- 标的、数量、价款须明确\n- 履行期限不得空白\n\n| 条款 | 要求 |\n| --- | --- |\n| 付款 | 对公账户 |\n| 违约 | 约定计算方式 |\n',
    createdAt: '2026-02-01T09:10:00Z',
    updatedAt: now
  },
  {
    id: 'doc3',
    kbId: 'kb1',
    name: '扫描件_履约材料.pdf',
    source: 'local',
    status: 'SEGMENT_FAILED',
    segmentCount: 0,
    processedSegments: 0,
    charCount: 0,
    enabled: true,
    // ★ 失败态：failReason + failHint 必须原文可见（0 字符/扫描件提示）
    failReason: '该 PDF 为扫描件，未检测到文本层，无法提取内容。',
    failHint: '建议：转为可检索 PDF 或改用 Markdown 格式后重新上传。',
    // 扫描件无文本层 ⇒ 解析正文为空串（V14 口径：「连半个字都没解出来」）
    parsedContent: '',
    createdAt: '2026-02-01T09:10:00Z',
    updatedAt: '2026-02-01T09:12:00Z'
  },
  {
    id: 'doc4',
    kbId: 'kb1',
    name: '供应商准入条件.md',
    source: 'local',
    status: 'PENDING_EMBEDDING',
    segmentCount: 42,
    processedSegments: 0,
    charCount: 18000,
    enabled: true,
    failReason: null,
    failHint: null,
    parsedContent: '# 供应商准入条件\n\n- 独立法人资格\n- 注册资本不少于一百万元\n- 近三年无重大违法记录\n',
    createdAt: '2026-02-01T09:10:00Z',
    updatedAt: '2026-02-01T09:18:00Z'
  },
  {
    id: 'doc5',
    kbId: 'kb2',
    name: '验收标准摘录.docx',
    source: 'local',
    status: 'SUCCEEDED',
    segmentCount: 54,
    processedSegments: 54,
    charCount: 26000,
    enabled: true,
    failReason: null,
    failHint: null,
    parsedContent: '# 验收标准摘录\n\n1. 按百分之五比例抽样\n2. 批量不足一百件至少抽样五件\n\n| 场景 | 处置 |\n| --- | --- |\n| 抽样不合格率 > 3% | 整批拒收 |\n',
    createdAt: '2026-02-03T14:30:00Z',
    updatedAt: '2026-02-03T14:40:00Z'
  },
  {
    id: 'doc6',
    kbId: 'kb2',
    name: '不合格品处置流程.pdf',
    source: 'local',
    status: 'EMBEDDING_FAILED',
    segmentCount: 32,
    processedSegments: 12,
    charCount: 15200,
    enabled: true,
    failReason: '向量服务连接超时（3 次重试均失败）。',
    failHint: '建议：检查向量模型是否已加载，然后点「重试」从断点继续。',
    // 向量化失败不影响解析结果 —— 正文仍在，可读
    parsedContent: '# 不合格品处置流程\n\n- 三个工作日内书面通知供应商\n- 可返工限期返工\n- 不可返工作退货或索赔\n',
    createdAt: '2026-02-03T14:30:00Z',
    updatedAt: '2026-02-03T14:50:00Z'
  },
  {
    id: 'doc7',
    kbId: 'kb3',
    name: '产品白皮书_v2.pdf',
    source: 'local',
    status: 'SUCCEEDED',
    segmentCount: 96,
    processedSegments: 96,
    charCount: 45000,
    enabled: true,
    failReason: null,
    failHint: null,
    parsedContent: '# 产品白皮书\n\n## 产品定位\n\n面向企业内网部署的知识助理平台。\n\n| 能力 | 说明 |\n| --- | --- |\n| 检索 | 向量/全文/混合 |\n| 问答 | 带引用 |\n',
    createdAt: '2026-02-05T10:40:00Z',
    updatedAt: '2026-02-05T10:55:00Z'
  },
  {
    id: 'doc8',
    kbId: 'kb3',
    name: '部署指南.md',
    source: 'local',
    status: 'SEGMENTING',
    segmentCount: 0,
    processedSegments: 0,
    charCount: 22000,
    enabled: true,
    failReason: null,
    failHint: null,
    parsedContent: '# 部署指南\n\n1. 准备 JDK 21\n2. 配置数据库\n3. 启动服务\n',
    createdAt: now,
    updatedAt: now
  },
  {
    id: 'doc9',
    kbId: 'kb3',
    name: '能力边界说明.md',
    source: 'local',
    status: 'PENDING_PARSE',
    segmentCount: 0,
    processedSegments: 0,
    charCount: 8000,
    enabled: true,
    failReason: null,
    failHint: null,
    // 待解析：尚无解析结果
    parsedContent: '',
    createdAt: now,
    updatedAt: now
  }
]

/**
 * 分段样本。
 * charCount=1680 / 1420 的两条故意超过向量模型上限（1200 字），
 * 用于验证 SEG-I1「超长标红 + 建议」而不是静默截断。
 */
export const segments: Segment[] = [
  {
    id: 'seg1',
    documentId: 'doc1',
    ordinal: 0,
    title: '第一条 总则',
    content:
      '为规范合同管理，防范经营风险，保障公司合法权益，根据国家有关法律法规及公司章程，制定本制度。本制度适用于公司及所属各单位对外签订的各类合同，包括但不限于采购合同、销售合同、服务合同、租赁合同等。',
    charCount: 142,
    enabled: true,
    embedding: 'EMBEDDED'
  },
  {
    id: 'seg2',
    documentId: 'doc1',
    ordinal: 1,
    title: '第二条 合同订立',
    content:
      '合同订立前应完成对方主体资格审查，包括营业执照、资质证书、法定代表人身份证明等。重大合同应当组织法律、财务、业务联合评审，并留存评审记录。',
    charCount: 118,
    enabled: true,
    embedding: 'EMBEDDED'
  },
  {
    id: 'seg3',
    documentId: 'doc1',
    ordinal: 2,
    title: '第三条 付款条款要求',
    content:
      '付款方式应明确约定付款比例、付款节点与发票要求。前期付款比例原则上不超过合同总额的百分之三十，尾款须在验收合格后支付。付款节点应与履约进度挂钩，不得以单纯时间节点作为唯一付款依据。逾期付款应约定违约责任与计算方式。合同金额超过五十万元的，应约定分期付款并设置质量保证金。付款账户必须是对公账户，不接受个人账户收款要求。涉及外币结算的，应明确汇率确定方式与汇率波动分担机制。预付款项须提供等额预付款保函或公司认可的担保方式。财务部门有权对付款条款的合规性提出异议并要求修改。（本段落刻意写得很长，用于验证超长分段的标红与建议）',
    charCount: 1680,
    enabled: true,
    embedding: 'EMBEDDED'
  },
  {
    id: 'seg4',
    documentId: 'doc1',
    ordinal: 3,
    title: '第四条 验收标准与争议解决',
    content:
      '验收标准应量化、可检测，避免「符合要求」等模糊表述。争议解决方式优先协商，协商不成的，约定向公司所在地人民法院提起诉讼。',
    charCount: 96,
    enabled: true,
    embedding: 'EMBEDDED'
  },
  {
    id: 'seg5',
    documentId: 'doc1',
    ordinal: 4,
    title: '第五条 档案管理',
    content:
      '合同原件由档案管理部门统一保管，业务部门留存复印件。合同履行完毕后应及时归档，保存期限不少于十年。',
    charCount: 72,
    enabled: false,
    embedding: 'EMBEDDED'
  },
  {
    id: 'seg6',
    documentId: 'doc2',
    ordinal: 0,
    title: '采购合同模板 · 通用条款',
    content:
      '本模板适用于公司常规采购业务。使用前须根据具体业务场景调整标的、数量、价款、履行期限等要素，不得直接套用未经审查的空白条款。',
    charCount: 98,
    enabled: true,
    embedding: 'EMBEDDED'
  },
  {
    id: 'seg7',
    documentId: 'doc2',
    ordinal: 1,
    title: '服务合同模板 · 服务范围',
    content:
      '服务范围应以附件形式列明服务内容、服务标准、服务期限与服务地点。变更服务范围须签订补充协议。（本段同样超长，验证多条超长并存的界面表现。）' +
      '详细描述：乙方应按照甲方确认的服务方案提供服务，服务方案作为本合同不可分割的附件。服务过程中如需调整服务内容，应提前五个工作日书面通知甲方并取得甲方书面同意。甲方有权对服务质量进行检查与考核，考核结果作为付款依据。乙方应配备足够数量的专业人员，主要人员变更须经甲方同意。服务期间产生的所有工作成果知识产权归甲方所有，乙方不得擅自使用或转让给第三方。保密义务在合同终止后继续有效，期限为三年。乙方应建立完善的服务记录制度，按月向甲方提交服务报告。甲方对服务报告有异议的，应在收到报告后五个工作日内提出。因乙方原因导致服务中断的，应按照中断时长扣减相应服务费用。不可抗力情形下，双方应及时通知对方并采取措施减少损失。',
    charCount: 1420,
    enabled: true,
    embedding: 'PENDING_EMBEDDING'
  },
  {
    id: 'seg8',
    documentId: 'doc2',
    ordinal: 2,
    title: '保密协议要点',
    content: '双方对在合同签订与履行过程中知悉的对方商业秘密负有保密义务，保密期限自合同生效之日起三年。',
    charCount: 68,
    enabled: true,
    embedding: 'PENDING_EMBEDDING'
  },
  {
    id: 'seg9',
    documentId: 'doc4',
    ordinal: 0,
    title: '供应商准入条件',
    content:
      '供应商应具备独立法人资格，注册资本不少于人民币一百万元，近三年无重大违法记录，具备与所提供产品或服务相匹配的资质证书。',
    charCount: 92,
    enabled: true,
    embedding: 'PENDING_EMBEDDING'
  },
  {
    id: 'seg10',
    documentId: 'doc5',
    ordinal: 0,
    title: '验收抽样规则',
    content: '同一批次到货按百分之五比例抽样，批量不足一百件的至少抽样五件。抽样不合格率超过百分之三的，该批次整批拒收。',
    charCount: 88,
    enabled: true,
    embedding: 'EMBEDDED'
  },
  {
    id: 'seg11',
    documentId: 'doc5',
    ordinal: 1,
    title: '不合格品处置',
    content: '验收不合格的，应在三个工作日内书面通知供应商。可返工的限期返工，不可返工的作退货或索赔处理。',
    charCount: 72,
    enabled: true,
    embedding: 'EMBEDDED'
  },
  {
    id: 'seg12',
    documentId: 'doc7',
    ordinal: 0,
    title: '产品定位',
    content:
      '本产品是面向企业内网部署的知识助理平台，核心能力是把业务文档变成可检索、可引用的知识，并支撑带引用的问答。',
    charCount: 78,
    enabled: true,
    embedding: 'EMBEDDED'
  }
]

/** 每个知识库的检索参数。命中测试页会读取并允许调参 */
export const retrievalParams: Record<string, RetrievalParams> = {
  kb1: { mode: 'HYBRID', threshold: 0.6, topN: 5, noRefStrategy: 'REFUSE' },
  kb2: { mode: 'VECTOR', threshold: 0.5, topN: 5, noRefStrategy: 'REFUSE' },
  kb3: { mode: 'FULLTEXT', threshold: 0.4, topN: 8, noRefStrategy: 'FREE_ANSWER' },
  kb4: { mode: 'HYBRID', threshold: 0.6, topN: 5, noRefStrategy: 'REFUSE' }
}

/** 命中测试语料：按问题关键字给出不同命中集合，三种模式分数不同 */
export const hitCorpus = [
  {
    keywords: ['付款', '违约', '逾期'],
    items: [
      {
        segmentId: 'seg3',
        title: '第三条 付款条款要求',
        docName: '采购管理制度.pdf',
        kbName: '合同管理制度',
        content:
          '付款方式应明确约定付款比例、付款节点与发票要求。前期付款比例原则上不超过合同总额的百分之三十，尾款须在验收合格后支付。付款节点应与履约进度挂钩……',
        base: 0.87
      },
      {
        segmentId: 'seg1',
        title: '第一条 总则',
        docName: '采购管理制度.pdf',
        kbName: '合同管理制度',
        content: '为规范合同管理，防范经营风险，保障公司合法权益……',
        base: 0.72
      },
      {
        segmentId: 'seg6',
        title: '采购合同模板 · 通用条款',
        docName: '合同模板汇总.docx',
        kbName: '合同管理制度',
        content: '本模板适用于公司常规采购业务。使用前须根据具体业务场景调整标的、数量、价款……',
        base: 0.65
      }
    ]
  },
  {
    keywords: ['验收', '抽样', '不合格'],
    items: [
      {
        segmentId: 'seg10',
        title: '验收抽样规则',
        docName: '验收标准摘录.docx',
        kbName: '采购验收规范',
        content: '同一批次到货按百分之五比例抽样，批量不足一百件的至少抽样五件……',
        base: 0.91
      },
      {
        segmentId: 'seg11',
        title: '不合格品处置',
        docName: '验收标准摘录.docx',
        kbName: '采购验收规范',
        content: '验收不合格的，应在三个工作日内书面通知供应商……',
        base: 0.84
      },
      {
        segmentId: 'seg4',
        title: '第四条 验收标准与争议解决',
        docName: '采购管理制度.pdf',
        kbName: '合同管理制度',
        content: '验收标准应量化、可检测，避免「符合要求」等模糊表述……',
        base: 0.78
      }
    ]
  },
  {
    keywords: ['供应商', '准入', '资质'],
    items: [
      {
        segmentId: 'seg9',
        title: '供应商准入条件',
        docName: '供应商准入条件.md',
        kbName: '合同管理制度',
        content:
          '供应商应具备独立法人资格，注册资本不少于人民币一百万元，近三年无重大违法记录……',
        base: 0.89
      },
      {
        segmentId: 'seg2',
        title: '第二条 合同订立',
        docName: '采购管理制度.pdf',
        kbName: '合同管理制度',
        content: '合同订立前应完成对方主体资格审查，包括营业执照、资质证书……',
        base: 0.76
      }
    ]
  }
]
