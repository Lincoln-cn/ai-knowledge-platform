/**
 * 逐行 diff 纯逻辑单测。跑法：node --test src/pages/assistants/components/prompts/diff.test.ts
 *
 * ★ 断的是三件事：
 *   ① 只改一句 ⇒ 恰好一行删、一行增，其余同（提示词的典型改动，不能被渲染成"整段重写"）；
 *   ② 纯新增/纯删除各只有一种 kind（否则界面会把"加了一句"显示成"改了一句"）；
 *   ③ 相同文本 ⇒ 全 same（空 diff 要让抽屉说"正文没有变化"）。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { diffLines, hasDiff } from './diff.ts'

test('改一句：一行删、一行增，其余原样', () => {
  const before = ['你是合同审查助手。', '逐条核对违约责任。', '不确定的写"未约定"。'].join('\n')
  const after = ['你是合同审查助手。', '逐条核对违约责任与赔偿上限。', '不确定的写"未约定"。'].join('\n')
  const rows = diffLines(before, after)
  assert.equal(rows.filter((r) => r.kind === 'removed').length, 1)
  assert.equal(rows.filter((r) => r.kind === 'added').length, 1)
  assert.equal(
    rows.filter((r) => r.kind === 'same').length,
    2,
    '其余两行必须判成 same —— 判成删除+新增就意味着界面把整段都标了色'
  )
  assert.deepEqual(
    rows.find((r) => r.kind === 'added')?.text,
    '逐条核对违约责任与赔偿上限。'
  )
})

test('末尾追加一句：只有 added', () => {
  const rows = diffLines('第一行', '第一行\n第二行')
  assert.deepEqual(
    rows.map((r) => r.kind),
    ['same', 'added']
  )
})

test('删掉一句：只有 removed', () => {
  const rows = diffLines('第一行\n第二行', '第一行')
  assert.deepEqual(
    rows.map((r) => r.kind),
    ['same', 'removed']
  )
})

test('完全相同 ⇒ 全 same，且 hasDiff 为 false', () => {
  const text = 'a\nb\nc'
  const rows = diffLines(text, text)
  assert.equal(
    rows.every((r) => r.kind === 'same'),
    true
  )
  assert.equal(hasDiff(text, text), false)
})

test('空文本与 CRLF 都按行处理，不多切出空行', () => {
  assert.deepEqual(diffLines('', ''), [])
  const rows = diffLines('a\r\nb', 'a\nb')
  assert.equal(
    rows.every((r) => r.kind === 'same'),
    true,
    'Windows 换行不该被当成内容差异'
  )
})
