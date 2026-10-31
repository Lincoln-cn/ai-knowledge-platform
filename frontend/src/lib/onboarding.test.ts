/**
 * `onboarding` 单测。跑法：`node --test src/lib/onboarding.test.ts`（不需要起服务、不需要端口）。
 *
 * ★ 这里断言的不是「有四个步骤」，而是**三态**与**什么时候不该显示**：
 *   ① `unknown` 不许被算成 `todo`（接口挂了 ≠ 你没做 —— 会引导重复建库）；
 *   ② `unknown` 也不许被算成 `done`（会让人以为万事俱备）；
 *   ③ 全做完就整块消失（引导条不许变成常驻横幅）；
 *   ④ 不归你管的步骤整条不出现，且它不会把整块"顶"出来。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  buildOnboarding,
  ONBOARDING_STEP_PATHS,
  type OnboardingCounts,
  type OnboardingStepKey
} from './onboarding.ts'

const all = (_key: OnboardingStepKey) => true

const none: OnboardingCounts = { kbCount: 0, docCount: 0, appCount: 0, askCount: 0 }

test('全新环境（四个计数都是 0）⇒ 四步全是 todo，顺序就是最短路径', () => {
  const v = buildOnboarding(none, all)
  assert.ok(v)
  assert.deepEqual(
    v.steps.map((s) => s.key),
    ['kb', 'document', 'assistant', 'ask']
  )
  assert.deepEqual(
    v.steps.map((s) => s.status),
    ['todo', 'todo', 'todo', 'todo']
  )
  assert.equal(v.todoCount, 4)
  assert.equal(v.doneCount, 0)
  assert.equal(v.totalCount, 4)
})

test('★ 全做完了 ⇒ 整块消失（引导条不是常驻横幅）', () => {
  const v = buildOnboarding({ kbCount: 2, docCount: 30, appCount: 1, askCount: 5 }, all)
  assert.equal(v, null)
})

test('★ 接口全挂（四个都是 null）⇒ 不显示，不许凭空造出「去建库」', () => {
  // 这是最容易写错的一条：把 null 当成 0，就会给一个健康平台顶出四条"未完成"。
  const v = buildOnboarding(
    { kbCount: null, docCount: null, appCount: null, askCount: null },
    all
  )
  assert.equal(v, null)
})

test('★ unknown 不算 todo：已做三步 + 一步没测到 ⇒ 仍然不显示', () => {
  const v = buildOnboarding({ kbCount: 1, docCount: 8, appCount: 1, askCount: null }, all)
  assert.equal(v, null)
})

test('★ unknown 也不算 done：没测到的那一步自己带着 unknown 状态', () => {
  const v = buildOnboarding({ kbCount: null, docCount: 0, appCount: 0, askCount: 0 }, all)
  assert.ok(v)
  const kb = v.steps.find((s) => s.key === 'kb')
  assert.ok(kb)
  assert.equal(kb.status, 'unknown')
  assert.equal(kb.count, null)
  // 它既没算进 done，也没算进 todo
  assert.equal(v.doneCount, 0)
  assert.equal(v.todoCount, 3)
})

test('★ 不归你管的步骤整条不出现（看到"你没有知识库"却无权建库，是个解不开的暗号）', () => {
  const v = buildOnboarding(none, (key) => key === 'assistant')
  assert.ok(v)
  assert.deepEqual(
    v.steps.map((s) => s.key),
    ['assistant']
  )
  // 分母只数可见步骤
  assert.equal(v.totalCount, 1)
  assert.equal(v.todoCount, 1)
})

test('★ 可见步骤都已做完、只剩不可见步骤没做 ⇒ 不显示（不能靠"看不见的 todo"把整块顶出来）', () => {
  const v = buildOnboarding({ kbCount: 1, docCount: 3, appCount: 1, askCount: 0 }, (key) => key !== 'ask')
  assert.equal(v, null)
})

test('一步都没有权限 ⇒ 不显示', () => {
  assert.equal(buildOnboarding(none, () => false), null)
})

test('计数用真实读数：1 条就算完成，不要求"够多"', () => {
  const v = buildOnboarding({ kbCount: 1, docCount: 1, appCount: 0, askCount: 0 }, all)
  assert.ok(v)
  assert.deepEqual(
    v.steps.filter((s) => s.status === 'done').map((s) => s.key),
    ['kb', 'document']
  )
  assert.equal(v.doneCount, 2)
  assert.equal(v.totalCount, 4)
})

test('★ 四步都落在三个真实页面上（工作台靠这份常量摘掉重复入口）', () => {
  // 摘重的前提是这份常量与步骤同源；万一以后改路径漏了常量，
  // 表现会是「建知识库」在页面上出现两次 —— 这条断言把它挡在门口。
  assert.deepEqual([...new Set(ONBOARDING_STEP_PATHS)], ['/knowledge', '/assistants', '/conversation'])
  const v = buildOnboarding(none, all)
  assert.ok(v)
  for (const step of v.steps) {
    assert.ok(
      ONBOARDING_STEP_PATHS.includes(step.path),
      `步骤 ${step.key} 的路径 ${step.path} 不在路径常量里`
    )
  }
})
