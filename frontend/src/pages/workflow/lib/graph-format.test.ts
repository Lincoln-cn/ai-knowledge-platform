/**
 * 「契约 ↔ 画布」这层翻译的读数（`#337` 第二步 B1）。
 *
 * <h3>为什么这组测试存在</h3>
 *
 * <p>`graph-format.ts` 是契约与画布之间**唯一**的翻译层，但它此前一条测试都没有 ——
 * 于是「写的时候用名字 A、读的时候用名字 B」这类错误在仓库里活了很久：两边各自自洽，
 * 只有把真实数据喂进去过一次才会暴露。B1 修的就是三处这样的不一致，所以这里逐条钉住。
 *
 * <p>★ 这些测试都直接调 `toLogicFlow` / `fromLogicFlow`，不走画布实例 —— 因为它们要守的是
 * **数据形状**，不是组件渲染。渲染再好看，形状不对就是「重开面板一片空」。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { fromLogicFlow, toLogicFlow } from './graph-format.ts'

/** 造一张只有节点、没有边的图：B1 关心的全是节点配置的往返。 */
function graphOf(nodes: Array<Record<string, unknown>>): never {
  return { nodes, edges: [] } as never
}

function nodeOf(type: string, config: Record<string, unknown>, id = `n-${type}`) {
  return { id, type, title: type, position: { x: 0, y: 0 }, config }
}

function only<T>(list: T[]): T {
  assert.equal(list.length, 1, `期望恰好一个节点，实际 ${list.length} 个`)
  return list[0]
}

test('基础节点的两张字段表落在 node_data 里，不再挂 properties 顶层', () => {
  const lf = toLogicFlow(
    graphOf([
      nodeOf('BASE_NODE', {
        fields: [
          { field: 'contract_type', label: '合同类型', type: 'TEXT', required: true, placeholder: '' }
        ],
        apiFields: [{ key: 'userId', label: '用户标识', required: true, defaultValue: 'u-1' }],
        allowFileUpload: false,
        fileAccept: []
      })
    ])
  )

  const props = only(lf.nodes).properties as Record<string, unknown>
  assert.equal(props.user_input_field_list, undefined, '顶层不该再有 user_input_field_list')
  assert.equal(props.api_input_field_list, undefined, '顶层不该再有 api_input_field_list')

  const nodeData = props.node_data as Record<string, unknown>
  assert.ok(Array.isArray(nodeData.fields), `node_data.fields 应为数组，实际 ${typeof nodeData.fields}`)
  assert.ok(Array.isArray(nodeData.apiFields), `node_data.apiFields 应为数组，实际 ${typeof nodeData.apiFields}`)
  assert.equal((nodeData.apiFields as Array<Record<string, unknown>>)[0].variable, 'userId')
})

test('基础节点的字段表往返不丢内容（含接口传参的四个字段）', () => {
  const config = {
    fields: [
      {
        field: 'clause',
        label: '待审条款',
        type: 'TEXT',
        required: true,
        placeholder: '粘贴原文'
      }
    ],
    apiFields: [{ key: 'userId', label: '用户标识', required: true, defaultValue: 'u-1' }],
    allowFileUpload: false,
    fileAccept: ['.pdf']
  }

  const back = fromLogicFlow(toLogicFlow(graphOf([nodeOf('BASE_NODE', config)])))
  const got = only(back.nodes).config as unknown as Record<string, unknown>

  assert.deepEqual(got.apiFields, config.apiFields, '接口传参表应逐字段还原')
  const fields = got.fields as Array<Record<string, unknown>>
  assert.equal(fields.length, 1)
  assert.equal(fields[0].field, 'clause')
  assert.equal(fields[0].required, true)
})

test('存量草稿：字段表还挂在 properties 顶层时仍能读出来', () => {
  // 这是 B1 之前的真实形状。改结构不能把老图的字段弄丢 —— 那等于让用户重填一遍。
  const legacy = {
    nodes: [
      {
        id: 'n-base',
        type: 'BASE_NODE',
        x: 0,
        y: 0,
        properties: {
          stepName: '基本信息',
          node_data: { name: '', desc: '', prologue: '' },
          user_input_field_list: [{ field: 'title', label: '标题', input_type: 'TextInput' }],
          api_input_field_list: [{ variable: 'uid', name: '用户', is_required: true }]
        }
      }
    ],
    edges: []
  }

  const got = only(fromLogicFlow(legacy as never).nodes).config as unknown as Record<string, unknown>
  const fields = got.fields as Array<Record<string, unknown>>
  assert.equal(fields.length, 1)
  assert.equal(fields[0].field, 'title')

  const apiFields = got.apiFields as Array<Record<string, unknown>>
  assert.equal(apiFields.length, 1)
  assert.equal(apiFields[0].key, 'uid')
  assert.equal(apiFields[0].required, true)
})

test('表单节点的三个键与面板读写的位置同名', () => {
  // ★ 这条是本批修的那个 bug 的直接反证：以前 `formToLegacy` 写 `form_field_list`，
  //   而面板读 `node_data.formFields` —— 画布加载后重开面板是空表。
  const config = {
    formFields: [
      { key: 'name', label: '姓名', type: 'text', required: true }
    ],
    contentFormat: '请填写：\n{{form}}',
    asResult: true
  }

  const lf = toLogicFlow(graphOf([nodeOf('FORM_NODE', config)]))
  const nodeData = (only(lf.nodes).properties as Record<string, unknown>).node_data as Record<
    string,
    unknown
  >

  assert.deepEqual(nodeData.formFields, config.formFields, '写进画布的名字必须是 formFields')
  assert.equal(nodeData.form_field_list, undefined, '不该再写旧画布名 form_field_list')
  assert.equal(nodeData.contentFormat, config.contentFormat)
  assert.equal(nodeData.asResult, true)

  // 面板写 node_data.formFields 之后保存，契约里也要是 formFields
  const back = fromLogicFlow(lf)
  assert.deepEqual((only(back.nodes).config as unknown as Record<string, unknown>).formFields, config.formFields)
})

test('开场白以基础节点上的编辑为准，写回开始节点的契约字段', () => {
  const start = nodeOf('START_NODE', { prologue: '旧开场白', quickQuestions: [] }, 'n-start')
  const base = nodeOf(
    'BASE_NODE',
    {
      fields: [],
      apiFields: [],
      allowFileUpload: false,
      fileAccept: [],
      prologue: '新开场白'
    },
    'n-base'
  )

  const back = fromLogicFlow(toLogicFlow(graphOf([start, base])))
  const startBack = back.nodes.find((n) => n.type === 'START_NODE')
  assert.ok(startBack, '开始节点不该在往返里消失')
  assert.equal((startBack.config as unknown as Record<string, unknown>).prologue, '新开场白')
})

test('回复节点把「是否返回」写成契约名 asResult，旧画布的 is_result 仍能读出来', () => {
  // ★ 这条钉的是 B2 的一处具体迁移：面板上的开关以前直接读写 node_data.is_result
  //   （契约里根本没有这一项），现在与表单节点同名，都叫 asResult。
  const lf = toLogicFlow(
    graphOf([nodeOf('REPLY_NODE', { content: '结论如下', replyType: 'content', asResult: false })])
  )
  const nodeData = (only(lf.nodes).properties as Record<string, unknown>).node_data as Record<
    string,
    unknown
  >

  assert.equal(nodeData.asResult, false, '写进画布的名字必须是 asResult')
  assert.equal(nodeData.is_result, undefined, '不该再写旧画布名 is_result')
  assert.equal(nodeData.replyType, 'content', '回复形态也写契约名')

  // 往返保住取值（不是恒真的 true）
  const back = fromLogicFlow(lf)
  assert.equal((only(back.nodes).config as unknown as Record<string, unknown>).asResult, false)

  // 存量草稿只有旧名时，读回来要等价
  const legacy = {
    nodes: [
      {
        id: 'n-reply',
        type: 'REPLY_NODE',
        x: 0,
        y: 0,
        properties: { node_data: { content: '旧草稿', reply_type: 'content', is_result: true } }
      }
    ],
    edges: []
  }
  assert.equal((only(fromLogicFlow(legacy as never).nodes).config as unknown as Record<string, unknown>).asResult, true)
})
