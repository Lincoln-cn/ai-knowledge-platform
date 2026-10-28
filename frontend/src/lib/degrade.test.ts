/**
 * degrade 纯逻辑单测。跑法：node --test src/lib/degrade.test.ts
 *
 * ★ 这里断的是两件相反的事，缺一条这个降级器就会从"隔离依赖"变成"吞错误"：
 *   ① 接口不存在 ⇒ 空数据 + 一条 WARN（同一个端点只念一次）；
 *   ② 其它错误 ⇒ 原样抛（否则数据层从此安静地回 0）。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { degradeOnMissingEndpoint, isEndpointMissing, resetDegradeWarnings } from './degrade.ts'

/** 后端 `NoResourceFoundException` 处理器的真实形状（`#160` 的现场逐字） */
const missingEndpoint = () => ({
  code: 404,
  message: '接口不存在：api/v1/analytics/assistants'
})

test('接口不存在认得出来：404/501 + 「接口不存在」两段都对', () => {
  assert.equal(isEndpointMissing(missingEndpoint()), true)
  assert.equal(isEndpointMissing({ code: 501, message: '接口不存在：x' }), true)
})

test('★ 只有 code 对了不算 —— 「资源不存在」不是「端点不存在」', () => {
  assert.equal(isEndpointMissing({ code: 404, message: '应用不存在' }), false)
})

test('其它状态码与非法输入一律不算', () => {
  assert.equal(isEndpointMissing({ code: 500, message: '接口不存在：x' }), false)
  assert.equal(isEndpointMissing({ code: 403, message: '接口不存在：x' }), false)
  assert.equal(isEndpointMissing(new Error('接口不存在')), false)
  assert.equal(isEndpointMissing(null), false)
  assert.equal(isEndpointMissing('接口不存在'), false)
})

test('接口不存在 ⇒ 空数据，而不是把页面炸掉', async () => {
  resetDegradeWarnings()
  const value = await degradeOnMissingEndpoint(Promise.reject(missingEndpoint()), [] as string[], 'x')
  assert.deepEqual(value, [])
})

test('同一个端点只念一条 WARN（一个页面不该刷出五条同样的噪音）', async (t) => {
  resetDegradeWarnings()
  const warn = t.mock.method(console, 'warn', () => {})
  const attempt = () => degradeOnMissingEndpoint(Promise.reject(missingEndpoint()), [], 'GET /a')
  await attempt()
  await attempt()
  await attempt()
  assert.equal(warn.mock.callCount(), 1)
  assert.match(String(warn.mock.calls[0].arguments[0]), /#160/)
})

test('★ 其它错误必须原样抛出 —— 降级器不许变成吞错误的袋子', async () => {
  resetDegradeWarnings()
  const boom = { code: 500, message: '向量模型不可用' }
  await assert.rejects(
    () => degradeOnMissingEndpoint(Promise.reject(boom), [], 'GET /b'),
    (error: unknown) => error === boom
  )
})

test('成功时不做任何事（值原样透传）', async () => {
  const value = await degradeOnMissingEndpoint(Promise.resolve([1, 2]), [], 'GET /c')
  assert.deepEqual(value, [1, 2])
})
