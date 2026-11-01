/**
 * `issuesFromDetails` 的读数（Issue #260）。
 *
 * 重点是**坏形状**那几条：这个函数唯一的本事就是"不合格的不让过"，
 * 只测好形状等于没测（它退化成 `return details as WorkflowValidationIssue[]` 也全绿）。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { issuesFromDetails } from './publishIssues.ts'

test('合格清单原样保留五个字段', () => {
  const got = issuesFromDetails([
    {
      level: 'ERROR',
      code: 'EMPTY_GRAPH',
      message: '流程里没有任何节点',
      hint: '至少放一个开始节点',
      nodeId: null
    },
    { level: 'WARNING', code: 'UNREACHABLE', message: '有节点走不到', hint: '连一条边', nodeId: 'n9' }
  ])

  assert.equal(got.length, 2)
  assert.deepEqual(got[0], {
    level: 'ERROR',
    code: 'EMPTY_GRAPH',
    message: '流程里没有任何节点',
    hint: '至少放一个开始节点',
    nodeId: null
  })
  assert.equal(got[1].nodeId, 'n9')
})

test('details 不是数组时返回空数组', () => {
  assert.deepEqual(issuesFromDetails(undefined), [])
  assert.deepEqual(issuesFromDetails(null), [])
  assert.deepEqual(issuesFromDetails({ issues: [] }), [])
  assert.deepEqual(issuesFromDetails('EMPTY_GRAPH'), [])
})

test('丢掉 level 不在契约取值里的行（否则面板渲染出既不红也不黄的空壳）', () => {
  const got = issuesFromDetails([
    { level: 'INFO', code: 'X', message: '提示' },
    { level: 'error', code: 'Y', message: '小写' },
    { level: 'ERROR', code: 'Z', message: '这条留着' }
  ])
  assert.deepEqual(
    got.map((i) => i.code),
    ['Z']
  )
})

test('丢掉 code / message 缺失或空白的行', () => {
  const got = issuesFromDetails([
    { level: 'ERROR', code: '', message: '空 code' },
    { level: 'ERROR', code: 42, message: 'code 不是字符串' },
    { level: 'ERROR', message: '没有 code' },
    { level: 'ERROR', code: 'NO_MSG', message: '   ' },
    { level: 'ERROR', code: 'OK', message: '这条留着' }
  ])
  assert.deepEqual(
    got.map((i) => i.code),
    ['OK']
  )
})

test('hint / nodeId 非字符串一律收敛成 null（不是原样透传）', () => {
  const [issue] = issuesFromDetails([
    { level: 'ERROR', code: 'C', message: 'M', hint: 123, nodeId: { id: 'x' } }
  ])
  assert.equal(issue.hint, null)
  assert.equal(issue.nodeId, null)
  // 空串 nodeId 当"没有节点"：`''` 拿去 focusNode 会静默什么都不做
  const [blank] = issuesFromDetails([{ level: 'ERROR', code: 'C', message: 'M', nodeId: '' }])
  assert.equal(blank.nodeId, null)
})

test('数组里的非对象项被跳过，而不是让整个清单作废', () => {
  const got = issuesFromDetails([
    null,
    'EMPTY_GRAPH',
    [1, 2],
    { level: 'ERROR', code: 'GOOD', message: '留着我' }
  ])
  assert.equal(got.length, 1)
  assert.equal(got[0].code, 'GOOD')
})
