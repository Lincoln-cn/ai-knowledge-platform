/**
 * http-error 纯逻辑单测。跑法：node --test src/lib/http-error.test.ts
 * （不引 axios / 路径别名 —— 只测文案与包络判定，拦截器本身由 E2E 覆盖）
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { httpFallbackMessage, isApiEnvelope } from './http-error.ts'

test('无包络 500 指向「后端未起/被代理拦下」，不是「后端异常」', () => {
  const msg = httpFallbackMessage(500)
  assert.match(msg, /后端可能未启动/)
  assert.match(msg, /被代理\/网关拦下/)
  assert.match(msg, /HTTP 500/)
  assert.doesNotMatch(msg, /后端异常/)
})

test('无响应（网络不通）仍指向检查后端是否启动', () => {
  assert.match(httpFallbackMessage(undefined), /无法连接后端/)
  assert.match(httpFallbackMessage(undefined), /后端是否启动/)
})

test('404 / 501 保留「接口不存在」的专门文案', () => {
  assert.match(httpFallbackMessage(404), /接口不存在/)
  assert.match(httpFallbackMessage(501), /接口不存在/)
})

test('包络判定：有 code+message 才算', () => {
  assert.equal(isApiEnvelope({ code: 500, message: 'x' }), true)
  assert.equal(isApiEnvelope({ code: 0, message: 'ok', hint: 'h' }), true)
  // vite 代理错误可能带 message 但没有 code —— 不是包络，不能拿它当后端的话
  assert.equal(isApiEnvelope({ message: 'connect ECONNREFUSED' }), false)
  assert.equal(isApiEnvelope({ code: 500 }), false)
  assert.equal(isApiEnvelope('Internal Server Error'), false)
  assert.equal(isApiEnvelope(null), false)
  assert.equal(isApiEnvelope([1, 2]), false)
})
