#!/usr/bin/env node
/**
 * 数据来源口径的守卫（`#340`）
 *
 * 为什么需要它：`frontend/.env.development` 的默认值是 `VITE_USE_MOCK=true`，
 * 于是 `npm run dev` 打开的界面全跑在 `src/mock/**` 的夹具上，而界面本身毫无提示 ——
 * 读数是"能用"，真因是"夹具能用"。0.7.3 的界面走查就因此把前半段的读数当成了
 * "前后端打通"的证据（`docs/ROADMAP.md §11.42.3` 记了这次翻车）。
 *
 * 处置不是"以后小心"，而是让口径**只写一份**并且**能被扫出来**：
 *   1. 说法只有一处：`src/config/env.ts` 的 `DATA_MODE_TEXT` / `DATA_MODE_BADGE` / `DATA_MODE_HINT`；
 *   2. 登录页与外壳顶栏**都从它取**，不许各自成句（登录页曾在本地写死过一份）；
 *   3. 顺手扫一类会静默失效的写法：`var(--不存在)` —— `var()` 解析失败不会报错，
 *      只会回落成继承色/空值。本文写作时真踩了两次（`--color-fill`、`--color-warning`，
 *      正确名字分别是 `--color-sunken` / `--color-warn`）。
 *
 * 判据（失败即非零退出）：
 *   - env.ts 定义了三个常量；
 *   - `_shell/Index.vue` 里有 `data-mock-badge` 且显示条件是 `USE_MOCK`；
 *   - `login/Index.vue` 里不再出现 `? '当前走 mock' :` 这种本地成句；
 *   - `src/**` 里 `var(--xxx)` 引用的每个 token 都在 `tokens.css`（或样式目录）里定义过。
 */

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '..')

const checks = []
const failures = []

function check(name, ok, detail) {
  checks.push({ name, ok })
  if (!ok) failures.push(detail ? `${name} —— ${detail}` : name)
}

const read = (relativePath) => readFileSync(join(ROOT, relativePath), 'utf8')

/* ── 1. 口径只有一份 ── */
const env = read('src/config/env.ts')
check('env.ts 定义 DATA_MODE_TEXT', /export\s+const\s+DATA_MODE_TEXT\b/.test(env))
check('env.ts 定义 DATA_MODE_BADGE', /export\s+const\s+DATA_MODE_BADGE\b/.test(env))
check('env.ts 定义 DATA_MODE_HINT', /export\s+const\s+DATA_MODE_HINT\b/.test(env))

const shell = read('src/pages/_shell/Index.vue')
check(
  '外壳顶栏有常驻角标（登录之后也看得见）',
  /data-mock-badge/.test(shell),
  '`_shell/Index.vue` 里找不到 `data-mock-badge`'
)
check(
  '角标只在走夹具时出现（显示条件 USE_MOCK）',
  /v-if="USE_MOCK"/.test(shell)
)
check(
  '外壳从 env 取口径，不自己成句',
  /DATA_MODE_BADGE/.test(shell) && !/'当前走 mock'/.test(shell),
  '外壳里出现了「当前走 mock」字面量'
)

const login = read('src/pages/login/Index.vue')
check(
  '登录页不再本地成句（同一事实不许写两份）',
  !/\?\s*'\s*当前走 mock\s*'\s*:/.test(login),
  '登录页里又写了一份「当前走 mock」的三元表达式'
)
check('登录页从 env 取口径', /DATA_MODE_TEXT/.test(login))

/* ── 2. 引用了未定义的 CSS token（var() 解析失败不报错，只会静默回落） ── */
function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) {
      walk(full, out)
    } else if (/\.(vue|css)$/.test(name)) {
      out.push(full)
    }
  }
  return out
}

const DECLARATION_SOURCES = ['tokens.css', 'src/styles/element-plus.css', 'src/styles/index.css']
const declared = new Set()
for (const source of DECLARATION_SOURCES) {
  let text
  try {
    text = read(source)
  } catch {
    continue // 文件不在就跳过：缺文件本身由别的闸门管
  }
  for (const match of text.matchAll(/(--[a-zA-Z0-9-]+)\s*:/g)) declared.add(match[1])
}

const sourceFiles = walk(join(ROOT, 'src'))

/* 页面级变量（`conversation/Index.vue` 定义 `--history-w`，两个子组件读它）是正常写法：
   变量在**另一个文件**里声明。所以"声明"要按全仓收集，不能只看同一个文件。 */
for (const full of sourceFiles) {
  const text = readFileSync(full, 'utf8')
  for (const match of text.matchAll(/(--[a-zA-Z0-9-]+)\s*:/g)) declared.add(match[1])
}

const usedBy = new Map()
for (const full of sourceFiles) {
  // 注释里提到 `var(--color-warning)` 不算"使用"：那是解释为什么不能用它。
  // 所以先剥掉块注释再扫（行内 `//` 注释在 CSS 里不存在，`.vue` 的 script 段有，一并剥）。
  const text = readFileSync(full, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\/[^\n]*/g, '')
  for (const match of text.matchAll(/var\(\s*(--[a-zA-Z0-9-]+)/g)) {
    const token = match[1]
    // `--el-*` 是 Element Plus 自己注入的；`--token` 是"变量名本身"的占位写法（模板拼接用）
    if (token.startsWith('--el-') || token === '--token') continue
    if (!usedBy.has(token)) usedBy.set(token, new Set())
    usedBy.get(token).add(relative(ROOT, full).replaceAll('\\', '/'))
  }
}

const missing = [...usedBy.entries()].filter(([token]) => !declared.has(token))
check(
  '没有引用未定义的 CSS token',
  missing.length === 0,
  missing.map(([token, files]) => `${token}（${[...files].join(', ')}）`).join(' · ')
)

/* ── 输出 ── */
for (const { name, ok } of checks) {
  console.log(`${ok ? 'OK  ' : 'FAIL'} ${name}`)
}

if (failures.length > 0) {
  console.error(`\n${failures.length} 项未通过：`)
  for (const failure of failures) console.error(`  - ${failure}`)
  process.exit(1)
}

console.log(`\n${checks.length} 项全部通过`)
