import type {
  AiChatNodeConfig,
  BaseNodeConfig,
  ConditionNodeConfig,
  ImplementedNodeType,
  NumberConditionNodeConfig,
  QuestionNodeConfig,
  ReplyNodeConfig,
  SearchDatasetNodeConfig,
  StartNodeConfig,
  UserInputField,
  WorkflowNode
} from '@/api/workflow'

/**
 * 八种已实现节点的默认配置。
 * 新建节点时用它落盘，保证 config 形状与 NodeConfigOf<T> 对齐。
 */

export function defaultConfigFor(type: ImplementedNodeType): WorkflowNode['config'] {
  switch (type) {
    case 'START_NODE': {
      const config: StartNodeConfig = {
        prologue: '你好，我是本应用的助理。请描述你的问题，我会结合业务知识为你解答。',
        quickQuestions: ['这个流程怎么走？', '需要准备哪些材料？']
      }
      return config
    }
    case 'BASE_NODE': {
      const config: BaseNodeConfig = {
        fields: [
          {
            field: 'title',
            label: '标题',
            type: 'TEXT',
            required: true,
            placeholder: '一句话概括你的诉求'
          }
        ],
        allowFileUpload: false,
        fileAccept: ['.pdf', '.docx', '.md']
      }
      return config
    }
    case 'AI_CHAT_NODE': {
      const config: AiChatNodeConfig = {
        modelId: '',
        rolePrompt: '你是企业知识助理，回答需给出依据，不确定时明确说明。',
        promptIds: [],
        kbIds: [],
        retrieval: { mode: 'HYBRID', threshold: 0.6, topN: 5, noRefStrategy: 'REFUSE' },
        thinkingEnabled: false,
        multiTurn: false
      }
      return config
    }
    case 'SEARCH_DATASET_NODE': {
      const config: SearchDatasetNodeConfig = {
        kbIds: [],
        retrieval: { mode: 'HYBRID', threshold: 0.6, topN: 5, noRefStrategy: 'REFUSE' }
      }
      return config
    }
    case 'QUESTION_NODE': {
      const config: QuestionNodeConfig = {
        modelId: null,
        rewritePrompt: '结合对话上下文，把用户问题改写成独立、完整、可检索的一句话。只输出改写结果。'
      }
      return config
    }
    case 'CONDITION_NODE': {
      const config: ConditionNodeConfig = {
        branches: [
          { id: 'b1', label: '条件 A', expression: '用户问题包含「风险」或「违约」' },
          { id: 'b2', label: '条件 B', expression: '用户问题包含「流程」或「步骤」' }
        ],
        fallbackBranchId: 'b-fallback'
      }
      // 兜底分支本身也在 branches 里，便于画布统一画出边
      config.branches.push({ id: 'b-fallback', label: '兜底', expression: '以上条件都不命中' })
      return config
    }
    case 'NUMBER_CONDITION_NODE': {
      const config: NumberConditionNodeConfig = {
        branches: [
          {
            id: 'nb1',
            label: '金额较大',
            logic: 'AND',
            conditions: [{ variable: null, operator: 'GT', value: '100' }]
          },
          {
            id: 'nb-fallback',
            label: '兜底',
            logic: 'OR',
            conditions: [{ variable: null, operator: 'IS_NULL', value: null }]
          }
        ],
        fallbackBranchId: 'nb-fallback'
      }
      return config
    }
    case 'REPLY_NODE': {
      const config: ReplyNodeConfig = {
        content: '已为你整理如下：\n\n{{search-dataset-node-1.snippets}}\n\n如需进一步说明，请补充背景信息。'
      }
      return config
    }
  }
}

/** 节点默认标题（放入画布时的初始 title） */
export const defaultTitleFor: Record<ImplementedNodeType, string> = {
  'START_NODE': '开始',
  'BASE_NODE': '基本信息收集',
  'AI_CHAT_NODE': 'AI 对话',
  'SEARCH_DATASET_NODE': '知识库检索',
  'QUESTION_NODE': '问题改写',
  'CONDITION_NODE': '语义判断',
  'NUMBER_CONDITION_NODE': '数值判断',
  'REPLY_NODE': '指定回复'
}

/** type → id 前缀，保证同类型多实例 id 可读且唯一 */
const idPrefix: Record<ImplementedNodeType, string> = {
  'START_NODE': 'start',
  'BASE_NODE': 'base',
  'AI_CHAT_NODE': 'ai-chat',
  'SEARCH_DATASET_NODE': 'search',
  'QUESTION_NODE': 'question',
  'CONDITION_NODE': 'condition',
  'NUMBER_CONDITION_NODE': 'num-condition',
  'REPLY_NODE': 'reply'
}

export function makeNodeId(type: ImplementedNodeType, existing: Iterable<string>): string {
  const prefix = idPrefix[type]
  const taken = new Set(existing)
  let n = 1
  while (taken.has(`${prefix}-${n}`)) n += 1
  return `${prefix}-${n}`
}

/** 新字段的默认值（基本信息收集节点） */
export function defaultUserField(seq: number): UserInputField {
  return {
    field: `field_${seq}`,
    label: `字段 ${seq}`,
    type: 'TEXT',
    required: false,
    placeholder: ''
  }
}

export { idPrefix as nodeIdPrefix }
