/**
 * 画布内部存储的键名必须是契约名（`#337` 第二步 B3）。
 *
 * 为什么这组测试必须存在：`node_data` 的类型是 `Record<string, any>`（`graph-format.ts` 顶部
 * 那一版 `LegacyBundle` 的注释写过为什么），所以**键名写错，TypeScript 一声不响**。
 * 面板用自己的名字喂自己永远是绿的，转换层用自己的名字写自己也是绿的；
 * 只有把「面板读写的键」和「转换层写进画布 / 从画布读出的键」摆在一起比，才会红。
 *
 * 旧画布用的是 `model_id` / `system` / `dialogue_number` / `is_result` / `dataset_id_list` /
 * `dataset_setting` / `question_reference_address`。这些名字在**读存量草稿**时必须仍然认
 * （已经保存的草稿与已发布版本的图里就是它们），但在**写**的时候一个都不许再出现。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { fromLogicFlow, toLogicFlow } from './graph-format.ts'

function graphOf(nodes: Array<Record<string, unknown>>): never {
  return { nodes, edges: [] } as never
}

function nodeOf(type: string, config: Record<string, unknown>) {
  return { id: `n-${type}`, type, title: type, position: { x: 0, y: 0 }, config }
}

function nodeDataOf(type: string, config: Record<string, unknown>): Record<string, unknown> {
  const lf = toLogicFlow(graphOf([nodeOf(type, config)]))
  const node = lf.nodes[0] as unknown as { properties: Record<string, unknown> }
  return node.properties.node_data as Record<string, unknown>
}

const LEGACY_ONLY = [
  'model_id',
  'dataset_id_list',
  'dataset_setting',
  'question_reference_address',
  'dialogue_number',
  'is_result',
  'model_params_setting',
  'prompt_ids',
  'thinking_enabled'
]

function assertNoLegacyKeys(data: Record<string, unknown>, where: string) {
  const found = LEGACY_ONLY.filter((key) => key in data)
  assert.deepEqual(found, [], `${where} 里不该再出现旧画布键名，实际出现：${found.join('、')}`)
}

test('AI 对话节点写进画布的是契约键名，一个旧名都不留', () => {
  const data = nodeDataOf('AI_CHAT_NODE', {
    modelId: 'm-1',
    rolePrompt: '你是助理',
    promptIds: [],
    kbIds: ['kb-1'],
    retrieval: { mode: 'HYBRID', threshold: 0.5, topN: 4, noRefStrategy: 'REFUSE' },
    thinkingEnabled: true,
    multiTurn: true,
    dialogueNumber: 3,
    asResult: false,
    prompt: '已知信息：\n{{知识库检索.data}}'
  })

  assertNoLegacyKeys(data, 'AI 对话节点')
  assert.equal(data.modelId, 'm-1')
  assert.equal(data.rolePrompt, '你是助理', '角色设定要落在 rolePrompt 上，不是 system')
  assert.equal(data.dialogueNumber, 3)
  assert.deepEqual(data.kbIds, ['kb-1'])
  assert.deepEqual(data.retrieval, { mode: 'HYBRID', threshold: 0.5, topN: 4, noRefStrategy: 'REFUSE' })
})

test('检索节点写进画布的是 kbIds / retrieval / questionReferenceAddress', () => {
  const data = nodeDataOf('SEARCH_DATASET_NODE', {
    kbIds: ['kb-1', 'kb-2'],
    retrieval: { mode: 'VECTOR', threshold: 0.7, topN: 8, noRefStrategy: 'FREE_ANSWER' },
    questionReferenceAddress: ['开始.question']
  })

  assertNoLegacyKeys(data, '检索节点')
  assert.deepEqual(data.kbIds, ['kb-1', 'kb-2'])
  assert.equal((data.retrieval as Record<string, unknown>).topN, 8, '后端 SearchDatasetNodeExecutor 读的就是 retrieval.topN')
  assert.deepEqual(data.questionReferenceAddress, ['开始.question'])
})

test('问题改写节点写进画布的是 modelId / rewritePrompt / defaultAnswer', () => {
  const data = nodeDataOf('QUESTION_NODE', {
    modelId: 'm-2',
    rewritePrompt: '请改写：{{开始.question}}',
    defaultAnswer: '定时任务默认答复'
  })

  assertNoLegacyKeys(data, '问题改写节点')
  assert.equal(data.modelId, 'm-2')
  assert.equal(data.rewritePrompt, '请改写：{{开始.question}}', '改写提示词要落在 rewritePrompt 上，不是 prompt')
  assert.equal(data.defaultAnswer, '定时任务默认答复', '后端 QuestionNodeExecutor 读的就是 defaultAnswer')
})

test('存量草稿：画布里还是旧键名时，读出来要等价', () => {
  const lf = {
    nodes: [
      {
        id: 'n-old',
        type: 'AI_CHAT_NODE',
        title: 'AI 对话',
        position: { x: 0, y: 0 },
        properties: {
          node_data: {
            model_id: 'm-old',
            system: '旧的角色设定',
            prompt: '旧的提示词',
            dialogue_number: 2,
            is_result: true,
            dataset_id_list: ['kb-old'],
            dataset_setting: { search_mode: 'embedding', similarity: 0.42, top_n: 6 },
            thinking_enabled: true
          }
        }
      }
    ],
    edges: []
  }

  const graph = fromLogicFlow(lf as never)
  const config = (graph.nodes[0] as unknown as { config: Record<string, unknown> }).config

  assert.equal(config.modelId, 'm-old')
  assert.equal(config.rolePrompt, '旧的角色设定')
  assert.equal(config.prompt, '旧的提示词')
  assert.equal(config.dialogueNumber, 2)
  assert.equal(config.asResult, true)
  assert.equal(config.thinkingEnabled, true)
  assert.deepEqual(config.kbIds, ['kb-old'])
  assert.equal((config.retrieval as Record<string, unknown>).topN, 6)
  assert.equal((config.retrieval as Record<string, unknown>).threshold, 0.42)
})
