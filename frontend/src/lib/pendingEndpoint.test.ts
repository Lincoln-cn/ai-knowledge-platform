/**
 * pendingEndpoint 纯逻辑单测。跑法：node --test src/lib/pendingEndpoint.test.ts
 *
 * ★ 这里断的是两件相反的事：
 *   ① 端点还没实现 ⇒ 文案里必须有**欠账号**（否则用户无法判断"该等还是该改"）；
 *   ② 其它错误 ⇒ 必须返回 null（否则 500/越权会被说成"没接后端"，把排查方向带偏）。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { pendingEndpointHint } from './pendingEndpoint.ts'

/** 后端 `NoResourceFoundException` 处理器的真实形状（`#160` 的现场逐字） */
const MISSING = { code: 404, message: '接口不存在：api/v1/assistants/x/workflow/draft' }

test('端点没实现 ⇒ 文案点名欠账号，并说清不是用户的错', () => {
  const text = pendingEndpointHint(MISSING, '在编排里保存', '#198')
  assert.ok(text)
  assert.match(text, /#198/)
  assert.match(text, /在编排里保存/)
  assert.match(text, /不是你的操作有问题/)
})

test('★ 501 也算「还没实现」（网关侧的同一件事）', () => {
  assert.ok(pendingEndpointHint({ code: 501, message: '接口不存在：x' }, '预览', '#198'))
})

test('★ 500 不认 —— 那是真坏了，必须走原有提示', () => {
  assert.equal(pendingEndpointHint({ code: 500, message: '接口不存在：x' }, '保存', '#198'), null)
})

test('★ 404 但文案不是「接口不存在」也不认 —— 那是资源真的不在（例如已删除）', () => {
  assert.equal(
    pendingEndpointHint({ code: 404, message: '文档不存在' }, '保存', '#198'),
    null
  )
})

test('不是对象（字符串/Error 实例/undefined）一律不认', () => {
  assert.equal(pendingEndpointHint('接口不存在', '保存', '#198'), null)
  assert.equal(pendingEndpointHint(new Error('接口不存在'), '保存', '#198'), null)
  assert.equal(pendingEndpointHint(undefined, '保存', '#198'), null)
})

test('文案里的动作名与欠账号都是调用方给的 —— 同一个判据可以服务多个入口', () => {
  const a = pendingEndpointHint(MISSING, '查看历史版本', '#198')
  const b = pendingEndpointHint(MISSING, '上传会话附件', '#187')
  assert.ok(a && b)
  assert.match(a, /查看历史版本/)
  assert.match(b, /#187/)
  assert.notEqual(a, b)
})
