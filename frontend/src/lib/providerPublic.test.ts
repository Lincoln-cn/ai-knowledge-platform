/**
 * providerPublic 纯逻辑单测（Issue #264）。跑法：node --test src/lib/providerPublic.test.ts
 *
 * ★ 断的是「形状」，不是「值」：
 *   契约原话是「只回“有没有”，绝不回明文」，所以判据必须是
 *   `'apiKey' in result === false` —— 回空串 / null / undefined 都算没做到，
 *   因为键还在，客户端 `'apiKey' in x` 依然为真，人也会以为"字段存在只是没填"。
 *
 * ★ 反向验证（本文件存在的理由）：
 *   把 `toPublicProvider` 改回 `{ ...p, modelCount, hasApiKey }` 后，
 *   下面第一条用例必须红在 `'apiKey' in result` 那一行。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { toPublicProvider, type ProviderWithSecret } from './providerPublic.ts'

/** 金丝雀：真出现在响应里，`in` 和 `JSON.stringify` 两路都能抓到 */
const CANARY = 'sk-canary-264-do-not-leak'

/**
 * 内部记录。故意让内部 `hasApiKey: false` 与 `apiKey: CANARY` 互相矛盾 ——
 * 这样"直接透传内部的 hasApiKey"和"按密钥现算"两种实现会给出不同答案。
 */
const internal = (patch: Partial<ProviderWithSecret> = {}): ProviderWithSecret => ({
  id: 'provider-1',
  name: 'JAiRouter（本机）',
  kind: 'GATEWAY',
  apiBase: 'http://localhost:8080/v1',
  authStyle: 'X_API_KEY',
  egress: 'EXTERNAL',
  enabled: true,
  hasApiKey: false,
  modelCount: 0,
  remark: '本地网关',
  apiKey: CANARY,
  ...patch
})

test('★★ 有明文密钥时，公开形状里 `apiKey` 这个键根本不存在', () => {
  const result = toPublicProvider(internal(), 3)
  // 这一行就是 Issue #264 的判据：改回 `{ ...p }` 会红在这里
  assert.equal('apiKey' in result, false, `响应体里出现了 apiKey 键：${Object.keys(result).join(',')}`)
  assert.equal((result as unknown as Record<string, unknown>).apiKey, undefined)
})

test('★ 没密钥时也不许用「键存在但值为 undefined」充数', () => {
  assert.equal('apiKey' in toPublicProvider(internal({ apiKey: undefined }), 0), false)
  const noKeyField = internal()
  delete noKeyField.apiKey
  assert.equal('apiKey' in toPublicProvider(noKeyField, 0), false)
})

test('★ 密钥原文不会顺着序列化漏出去（金丝雀）', () => {
  const result = toPublicProvider(internal(), 1)
  const wire = JSON.stringify(result)
  assert.equal(wire.includes(CANARY), false)
  assert.equal(wire.includes('apiKey'), false)
})

test('hasApiKey 由内部密钥现算，不照抄内部那个可能过期的同名字段', () => {
  // 内部记录里写着 hasApiKey: false，但 apiKey 有值 ⇒ 公开侧必须说 true
  assert.equal(toPublicProvider(internal(), 0).hasApiKey, true)
  assert.equal(toPublicProvider(internal({ apiKey: undefined }), 0).hasApiKey, false)
  // 空串等于没配（mock 的 setProviderKey 也会 trim 后拒绝空串）
  assert.equal(toPublicProvider(internal({ apiKey: '' }), 0).hasApiKey, false)
})

test('契约字段逐个透传，modelCount 用调用方现算的值', () => {
  const result = toPublicProvider(internal(), 7)
  assert.equal(result.id, 'provider-1')
  assert.equal(result.name, 'JAiRouter（本机）')
  assert.equal(result.kind, 'GATEWAY')
  assert.equal(result.apiBase, 'http://localhost:8080/v1')
  assert.equal(result.authStyle, 'X_API_KEY')
  assert.equal(result.egress, 'EXTERNAL')
  assert.equal(result.enabled, true)
  assert.equal(result.remark, '本地网关')
  assert.equal(result.modelCount, 7)
})

test('没有备注时连 `remark` 键都不出现（可选字段不伪造）', () => {
  const result = toPublicProvider(internal({ remark: undefined }), 0)
  assert.equal('remark' in result, false)
})

test('★ 键集合是白名单 —— 内部以后再加字段也不会默认跟着下发', () => {
  const result = toPublicProvider(internal({ remark: undefined }), 2)
  assert.deepEqual(Object.keys(result).sort(), [
    'apiBase',
    'authStyle',
    'egress',
    'enabled',
    'hasApiKey',
    'id',
    'kind',
    'modelCount',
    'name'
  ])
})

test('不改动入参（纯函数，mock 的内存表不能被顺手改写）', () => {
  const source = internal()
  const before = JSON.parse(JSON.stringify(source))
  toPublicProvider(source, 5)
  assert.deepEqual(source, before)
  assert.equal(source.apiKey, CANARY)
  assert.equal(source.modelCount, 0)
})
