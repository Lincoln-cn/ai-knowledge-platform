/**
 * mock 删除守卫（`#301` 判据②）：有引用时删除返 **409 且与真后端同形**。
 *
 * ★ 它钉住的是「mock 与真栈同一个读数」这件事本身 —— mock 少一道检查，
 *   409 分支在 mock 模式下永远走不到，"mock 绿"就成了假绿（#277 同族）。
 * ★ 同形的判据按后端逐字段核：code=409、message/hint 措辞逐字对齐
 *   `ModelService.delete` / `ModelProviderService.delete`，details 是 `ModelReference`
 *   的完整形状（apps/knowledgeBases/documents/defaultKeys/models/modelNames）。
 * ★ 断言方式：直接调 `*Routes` 里的 handler（不经 dispatchMock —— 它的路由自动发现
 *   在 node:test 下没有 import.meta.glob）。顺带断言路由存在（漏挂路由也是一种坏）。
 * ★ fixture 是模块级共享状态：每个会删东西的用例自己恢复，不留残留。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ApiError, ErrorCode } from '../api/types.ts'
import type { ModelReference } from '../api/models'
import { modelRoutes } from './handlers/models.ts'
import { providerRoutes } from './handlers/providers.ts'
import { models } from './fixtures/models.ts'
import type { MockContext, MockRoute } from './dispatch.ts'

const findRoute = (routes: MockRoute[], method: string, pattern: string) => {
  const route = routes.find((r) => r[0] === method && r[1] === pattern)
  assert.ok(route, `路由必须存在：${method} ${pattern}`)
  return route[2]
}

const call = (
  routes: MockRoute[],
  method: string,
  pattern: string,
  ctx: { path?: Record<string, string>; query?: Record<string, unknown>; body?: unknown }
) =>
  findRoute(routes, method, pattern)({
    path: ctx.path ?? {},
    query: ctx.query ?? {},
    body: ctx.body,
    token: ''
  } as MockContext)

/** 捕获 ApiError 并返回；没有抛就失败 */
const expectApiError = (fn: () => unknown): ApiError => {
  try {
    fn()
  } catch (error) {
    assert.ok(error instanceof ApiError, `应抛 ApiError，实际：${String(error)}`)
    return error
  }
  assert.fail('应当抛 ApiError，但正常返回了')
}

/** 被删的模型/服务商用完就放回去（对象引用还在，push 回即可） */
const restore = (victim: (typeof models)[number]) => {
  if (!models.includes(victim)) models.push(victim)
}

test('判据②-1 有引用的模型不带 force ⇒ 409，且与真后端 ModelService.delete 同形', () => {
  // m2（Qwen3-8B）被应用「合同审查助手」引用（fixtures/modelReferences 显式登记）
  const victim = models.find((m) => m.id === 'm2')
  assert.ok(victim, 'fixture 里必须有 m2')

  const err = expectApiError(() =>
    call(modelRoutes, 'DELETE', '/models/:id', { path: { id: 'm2' }, query: {} })
  )

  // 状态码面：409 CONFLICT（旧 mock 是"直接删"，删完才发现引用丢了）
  assert.equal(err.code, ErrorCode.CONFLICT)

  // message 逐字对齐后端：「name」仍被 N 处引用，不能直接删除：summary
  assert.equal(
    err.message,
    `「${victim.name}」仍被 1 处引用，不能直接删除：1 个应用`,
    'message 必须与 ModelService.delete 同形'
  )

  // hint 逐字对齐后端
  assert.equal(
    err.hint,
    '删掉它会让上面这些引用变成悬空指针（应用无法提问、知识库检索不到）。确认要删请用「强制删除」'
  )

  // details = ModelReference 完整形状（缺字段 = 弹框里的明细渲染不出来）
  const details = err.details as ModelReference
  assert.ok(details, '409 必须带 details（引用面）')
  assert.deepEqual(
    Object.keys(details).sort(),
    ['apps', 'defaultKeys', 'documents', 'knowledgeBases', 'modelNames', 'models'].sort(),
    'details 必须与后端 ModelReference 同字段'
  )
  assert.deepEqual(details.apps, [{ id: 'as-contract-review', name: '合同审查助手' }])
  assert.equal(details.knowledgeBases.length, 0)
  assert.equal(details.documents, 0)
  assert.equal(details.defaultKeys.length, 0)
  assert.equal(details.models, 0)
  assert.deepEqual(details.modelNames, [])

  // 被拒的删除不许有副作用
  assert.ok(models.includes(victim), '409 之后模型必须还在')
})

test('判据②-2 同一个模型带 force=true ⇒ 真删（引用登记不动，与后端一致）', () => {
  const victim = models.find((m) => m.id === 'm2')
  assert.ok(victim)
  const before = models.length

  try {
    const result = call(modelRoutes, 'DELETE', '/models/:id', {
      path: { id: 'm2' },
      query: { force: true }
    })

    assert.equal(result, undefined)
    assert.equal(models.length, before - 1)
    assert.ok(!models.includes(victim), 'force=true 必须真的删掉')

    // 删了之后 references 查不到（404）—— 与后端一致：引用面跟着模型走，不留"生前档案"
    const err = expectApiError(() =>
      call(modelRoutes, 'GET', '/models/:id/references', { path: { id: 'm2' } })
    )
    assert.equal(err.code, ErrorCode.NOT_FOUND)
  } finally {
    restore(victim)
  }
})

test('判据②-3 无引用的模型（对照组）不带 force 也删得掉', () => {
  // m3（Qwen3-30B-A3B）没有登记任何引用
  const victim = models.find((m) => m.id === 'm3')
  assert.ok(victim)

  try {
    const result = call(modelRoutes, 'DELETE', '/models/:id', {
      path: { id: 'm3' },
      query: {}
    })

    assert.equal(result, undefined)
    assert.ok(!models.includes(victim), '无引用模型不该被守卫拦下')
  } finally {
    restore(victim)
  }
})

test('判据②-4 query.force 的字符串形态也认（query 是原始对象，两种都真实存在）', () => {
  const victim = models.find((m) => m.id === 'm2')
  assert.ok(victim)

  try {
    const result = call(modelRoutes, 'DELETE', '/models/:id', {
      path: { id: 'm2' },
      query: { force: 'true' }
    })

    assert.equal(result, undefined)
  } finally {
    restore(victim)
  }
})

test('references 端点存在且与后端 GET /models/:id/references 同形', () => {
  const refs = call(modelRoutes, 'GET', '/models/:id/references', { path: { id: 'm2' } }) as ModelReference
  assert.deepEqual(
    Object.keys(refs).sort(),
    ['apps', 'defaultKeys', 'documents', 'knowledgeBases', 'modelNames', 'models'].sort()
  )
  assert.deepEqual(refs.apps, [{ id: 'as-contract-review', name: '合同审查助手' }])

  // 没登记的模型 = 空引用面（不是 404，与后端 ofModel 返回 NONE 同语义）
  const empty = call(modelRoutes, 'GET', '/models/:id/references', { path: { id: 'm3' } }) as ModelReference
  assert.deepEqual(empty.apps, [])
  assert.equal(empty.documents, 0)
})

test('内置模型仍然先被拒（BAD_REQUEST，与后端 ModelService.delete 同序）', () => {
  const err = expectApiError(() =>
    call(modelRoutes, 'DELETE', '/models/:id', { path: { id: 'm1' }, query: { force: true } })
  )
  assert.equal(err.code, ErrorCode.BAD_REQUEST)
})

test('服务商侧：有模型不带 force ⇒ 409（与 ModelProviderService.delete 同形）', () => {
  // fixture：m2/m3 绑在 provider-jairouter 下 ⇒ 2 个模型
  const err = expectApiError(() =>
    call(providerRoutes, 'DELETE', '/model-providers/:id', {
      path: { id: 'provider-jairouter' },
      query: {}
    })
  )
  assert.equal(err.code, ErrorCode.CONFLICT)
  assert.equal(err.message, '服务商「JAiRouter（本机）」下还有 2 个模型，无法删除')
  assert.equal(
    err.hint,
    '请先把这些模型改绑到其它服务商，或删除它们后再删服务商；确认要连模型一起删，请用「强制删除」'
  )
  const details = err.details as ModelReference
  assert.equal(details.models, 2)
  // fixtures 里 m2/m3 的 name 是模型 tag（qwen3:8b / qwen3:30b-a3b）；后端 order by name
  assert.deepEqual([...details.modelNames].sort(), ['qwen3:30b-a3b', 'qwen3:8b'])
})

test('★ 服务商侧：模型有下游引用时 force 也 409 并列出引用者（issue 判据 1 的“force 仍 409”）', () => {
  // m2 被「合同审查助手」引用 ⇒ 删服务商会连带弄坏它，force 也拦
  const err = expectApiError(() =>
    call(providerRoutes, 'DELETE', '/model-providers/:id', {
      path: { id: 'provider-jairouter' },
      query: { force: true }
    })
  )
  assert.equal(err.code, ErrorCode.CONFLICT)
  assert.equal(
    err.message,
    '服务商「JAiRouter（本机）」下的 2 个模型仍被引用：1 个应用，不能连它们一起强制删除'
  )
  assert.equal(
    err.hint,
    '请先把这些应用/知识库/平台默认键改绑到别的模型，再回来删服务商（改绑不是删除，数据不会丢）'
  )
  assert.deepEqual((err.details as ModelReference).apps, [
    { id: 'as-contract-review', name: '合同审查助手' }
  ])
})

test('服务商侧：force=true 且模型无下游引用 ⇒ 连模型一起删（后端 force 语义）', () => {
  // 组一个"有模型、模型无引用"的临时服务商：m3 挪到新建服务商下
  const created = call(providerRoutes, 'POST', '/model-providers', {
    body: { name: `临时-${Date.now()}`, kind: 'OPENAI' }
  }) as { id: string; name: string }
  const victim = models.find((m) => m.id === 'm3')
  assert.ok(victim)
  const originalProvider = victim.providerId
  victim.providerId = created.id

  try {
    // 无 force 先拒一次（守卫在）
    const denied = expectApiError(() =>
      call(providerRoutes, 'DELETE', '/model-providers/:id', {
        path: { id: created.id },
        query: {}
      })
    )
    assert.equal(denied.code, ErrorCode.CONFLICT)
    assert.equal(denied.message, `服务商「${created.name}」下还有 1 个模型，无法删除`)

    // force ⇒ 服务商和它下面的模型一起消失
    const before = models.length
    const result = call(providerRoutes, 'DELETE', '/model-providers/:id', {
      path: { id: created.id },
      query: { force: true }
    })
    assert.equal(result, undefined)
    assert.equal(models.length, before - 1, 'force 必须一并删掉下面的模型')
    assert.ok(!models.includes(victim))
  } finally {
    restore(victim)
    victim.providerId = originalProvider
  }
})
