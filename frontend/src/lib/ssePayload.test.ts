/**
 * `chatStreamViaSse` 的**请求体**必须带齐 `mode` / `targetMessageId`。
 *
 * ## 为什么这条测试是"读源码"而不是"跑一遍"
 *
 * 这个 bug 的形状是「**字段压根没写进 body**」：`useChatSession` 把 `mode` /
 * `targetMessageId` 算好了传进来，`mock/stream.ts` 也用了它们，唯独真栈这条链在
 * `JSON.stringify({...})` 里漏掉了两格。结果是**点重新生成会多出一轮问答**，
 * 而界面上看起来只是"重生成了一次"。
 *
 * 要端到端断这件事，得起一个真后端 + 真模型，成本远超收益；而这个 bug 的唯一成因就是
 * "那两行不在 body 里"，所以直接钉住 body 的字面形状最划算 —— 删掉任一行，
 * 下面的断言立刻红。
 *
 * ★ 不钉成"整段 body 快照"：那会把字段顺序也钉死，改一个无关字段都要来改测试，
 *   久而久之就没人看它为什么红了。
 */
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const source = readFileSync(new URL('./sse.ts', import.meta.url), 'utf8')

/** 取出 `/conversations/chat` 那次 fetch 的 `JSON.stringify({ ... })` 片段 */
function chatBodyBlock(): string {
  const marker = '`${API_PREFIX}/conversations/chat`'
  const at = source.indexOf(marker)
  assert.ok(at >= 0, '没找到 /conversations/chat 的 fetch —— 本测试的前提不成立')
  const start = source.indexOf('JSON.stringify({', at)
  assert.ok(start >= 0, '那次 fetch 里没有 JSON.stringify —— 请求体可能被改写成了别的形状')
  const end = source.indexOf('})', start)
  assert.ok(end > start, 'JSON.stringify 的括号没闭合')
  return source.slice(start, end)
}

test('chat 请求体带 mode：重新生成 / 续写才不会被当成新一轮', () => {
  const block = chatBodyBlock()
  assert.match(block, /\bmode:\s*options\.mode\b/, '请求体里没有 mode ⇒ 服务端一律按 NEW 处理')
})

test('chat 请求体带 targetMessageId：覆盖的是哪一条回答', () => {
  const block = chatBodyBlock()
  assert.match(
    block,
    /\btargetMessageId:\s*options\.targetMessageId\b/,
    '请求体里没有 targetMessageId ⇒ REGENERATE / CONTINUE 没有目标，只能新增消息'
  )
})

test('chat 请求体带 rerankListwise：请求级重排开关（三态，null 是"跟随"）', () => {
  const block = chatBodyBlock()
  assert.match(
    block,
    /\brerankListwise:\s*options\.rerankListwise\b/,
    '请求体里没有 rerankListwise ⇒ 对话页那个三态开关点了也不生效'
  )
})

test('chat 请求体仍带三格老字段（回归保护，不是本条的主判据）', () => {
  const block = chatBodyBlock()
  for (const key of ['appId', 'conversationId', 'question']) {
    assert.match(block, new RegExp(`\\b${key}:`), `请求体丢了 ${key}`)
  }
})

/** 取出 `case 'refs':` 那一段（到下一个 `break`） */
function refsCaseBlock(): string {
  const at = source.indexOf("case 'refs':")
  assert.ok(at >= 0, '没找到 refs 分支 —— 契约的 refs 帧可能被改名了')
  const end = source.indexOf('break', at)
  assert.ok(end > at, 'refs 分支没有 break')
  return source.slice(at, end)
}

/**
 * ★ 零命中的原因（`notice`）必须在**传输层**就被带下去。
 *
 * 红在哪：把 `options.onRefs?.(...)` 的第二个实参删掉 ⇒ 界面永远拿不到原因，
 * 于是「没开召回 / 阈值滤光 / 密级拦下 / 库里确实没有」四种情况又长回同一个样子。
 * 这条与后端 `StreamAssemblerSequenceTest#emptyRefsCarryTheReasonWhyThereAreNone` 是同一判据的两端。
 */
test('refs 帧把 notice 交给 onRefs：零命中的原因不许在传输层丢掉', () => {
  const block = refsCaseBlock()
  assert.match(block, /raw\?\.notice/, 'refs 分支没读 notice ⇒ 后端给了原因也到不了界面')
  assert.match(
    block,
    /options\.onRefs\?\.\([\s\S]*?Array\.isArray\(raw\)\s*\?\s*null\s*:\s*\(raw\?\.notice/,
    'onRefs 的第二个实参必须是「裸数组形状 ⇒ null；包络形状 ⇒ notice」'
  )
})
