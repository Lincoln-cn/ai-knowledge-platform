/**
 * `modelReadiness` 单测。跑法：`node --test src/lib/modelReadiness.test.ts`（不需要起服务、不需要端口）。
 *
 * ★ 本文件断言的是**判据的形状**，不是「有这个函数」：
 *   ① 空列表 / 全部失败 ⇒ 两个用途都缺；
 *   ② `LOADING` 不是「能用」—— 一个 30B 模型正在加载时提示「你还没有模型」，
 *      用户会去建第二条，两条抢显存；
 *   ③ ★ **对话与向量的「能用」条件不同，且不许被统一**：
 *      对话要 `LOADED`，向量 `NOT_LOADED` 也算（本地 ONNX 懒加载）。
 *
 * ★ 每条都问过「它有没有可能为假」：只要有人把 `LOADING` 当 ready、
 *   把 `NOT_LOADED` 从向量判据里删掉、或顺手把两者合并成一个函数，这里都会红。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  assessModelReadiness,
  isUsableChatModel,
  isUsableEmbeddingModel,
  modelReadinessNotice,
  type ModelLike
} from './modelReadiness.ts'

/** 造模型，只填判据关心的两个字段 */
const chat = (status: ModelLike['status']): ModelLike => ({ kind: 'CHAT', status })
const embed = (status: ModelLike['status']): ModelLike => ({ kind: 'EMBEDDING', status })

test('★ 一条模型都没有 ⇒ 两个用途都缺（全新用户的真实起点）', () => {
  const r = assessModelReadiness([])
  assert.deepEqual(r.missing, ['chat', 'embedding'])
  assert.deepEqual(r.ready, [])
  assert.deepEqual(r.loading, [])
})

test('★ 正在加载不是「能用」—— 现在提问会失败', () => {
  const r = assessModelReadiness([chat('LOADING'), embed('LOADED')])
  assert.deepEqual(r.ready, ['embedding'])
  assert.deepEqual(r.loading, ['chat'])
  assert.deepEqual(r.missing, [])
})

test('★ 加载中也不许被算成「缺」—— 那会引导用户去建第二条', () => {
  const r = assessModelReadiness([embed('LOADING')])
  assert.deepEqual(r.missing, ['chat'])
  assert.deepEqual(r.loading, ['embedding'])
})

test('加载失败 = 缺（它需要人去看原因，不是等着就好）', () => {
  const r = assessModelReadiness([chat('LOAD_FAILED'), embed('LOAD_FAILED')])
  assert.deepEqual(r.missing, ['chat', 'embedding'])
})

test('★ 向量模型「登记了但没预热」算能用（本地 ONNX 首次调用才加载）', () => {
  assert.equal(isUsableEmbeddingModel(embed('NOT_LOADED')), true)
  const r = assessModelReadiness([chat('LOADED'), embed('NOT_LOADED')])
  assert.deepEqual(r.missing, [])
})

test('★ 同一条规则**不许**套到对话模型上 —— 对话要真的加载完', () => {
  assert.equal(isUsableChatModel(chat('NOT_LOADED')), false)
  const r = assessModelReadiness([chat('NOT_LOADED'), embed('LOADED')])
  assert.deepEqual(r.missing, ['chat'])
})

test('一个用途有可用的，就不会因为同用途另一条在加载/失败而报缺', () => {
  const r = assessModelReadiness([
    chat('LOADED'),
    chat('LOADING'),
    chat('LOAD_FAILED'),
    embed('LOADED'),
    embed('LOAD_FAILED')
  ])
  assert.deepEqual(r.ready, ['chat', 'embedding'])
  assert.deepEqual(r.missing, [])
})

test('用途之间互不影响：只有对话模型时，缺的是向量而不是两个都缺', () => {
  const r = assessModelReadiness([chat('LOADED')])
  assert.deepEqual(r.missing, ['embedding'])
  assert.deepEqual(r.ready, ['chat'])
})

test('判据不认 kind 之外的字段（混合列表按 kind 分流）', () => {
  assert.equal(isUsableChatModel(embed('LOADED')), false)
  assert.equal(isUsableEmbeddingModel(chat('LOADED')), false)
})

/* ── 引导条：该不该显示、显示哪一句 ──────────────────────────────────────
   这部分才是 F2 的「一条能红的断言」。判据本身对了、却把它显示给错的人
   或说反后果，仍然是一个坏的引导。 */

/** 用一组模型造出引导条 */
const noticeOf = (models: ModelLike[], canFix = true) =>
  modelReadinessNotice(assessModelReadiness(models), canFix)

test('★ 拉取失败（readiness 为 null）不显示 —— 「不知道」≠「没有」', () => {
  // 后端没起来 / 接口 500 时，页面拿不到模型列表。
  // 此时若能显示「你还没有模型」，一个健康平台会顶着一条假故障提示。
  assert.equal(modelReadinessNotice(null, true), null)
})

test('★ 用户修不了就不显示 —— 通向 403 的入口比没有入口更坏', () => {
  // 两个用途都缺，但这个用户没有 model:manage ⇒ 一个字符都不许出现。
  // 断言用「一条模型都没有」这个最该提示的极端输入，否则这条测试可能形同虚设。
  const empty = assessModelReadiness([])
  assert.deepEqual(empty.missing, ['chat', 'embedding'])
  assert.equal(modelReadinessNotice(empty, false), null)
  // 同一份输入，有权限时必须有话说 —— 否则上面那条 assert 可能因为「永远返回 null」而假绿。
  assert.notEqual(modelReadinessNotice(empty, true), null)
})

test('两个用途都缺 ⇒ warn，且说的是「要登记」而不是某一个用途的后果', () => {
  const n = noticeOf([])
  assert.ok(n)
  assert.equal(n.tone, 'warn')
  assert.ok(n.text.includes('登记'))
})

test('★ 只缺对话 vs 只缺向量：文案不许写反', () => {
  // 缺对话（向量已有）—— 提问会失败
  const chatMissing = noticeOf([embed('LOADED')])
  assert.ok(chatMissing)
  assert.equal(chatMissing.tone, 'warn')
  assert.ok(chatMissing.text.includes('对话'))
  assert.ok(chatMissing.text.includes('提问'))
  assert.ok(!chatMissing.text.includes('向量'), '只缺对话时不许提向量：' + chatMissing.text)

  // 缺向量（对话已加载）—— 建库/入库会失败
  const embedMissing = noticeOf([chat('LOADED')])
  assert.ok(embedMissing)
  assert.equal(embedMissing.tone, 'warn')
  assert.ok(embedMissing.text.includes('向量'))
  assert.ok(embedMissing.text.includes('建知识库'))
  assert.ok(!embedMissing.text.includes('对话'), '只缺向量时不许提对话：' + embedMissing.text)
})

test('★ 正在加载给 info，不是 warn —— 此时让用户去建第二条会抢显存', () => {
  const n = noticeOf([chat('LOADING'), embed('LOADED')])
  assert.ok(n)
  assert.equal(n.tone, 'info')
  assert.ok(n.text.includes('加载'))
})

test('什么都不缺、也没有在加载 ⇒ 不显示（别把引导条变成常驻横幅）', () => {
  assert.equal(noticeOf([chat('LOADED'), embed('LOADED')]), null)
  // 向量「登记了没预热」也算能用 ⇒ 同样不显示
  assert.equal(noticeOf([chat('LOADED'), embed('NOT_LOADED')]), null)
})
