/**
 * 让 `node --test` 能直接跑前端源码的路径别名解析器。
 *
 * <h3>为什么需要它</h3>
 *
 * <p>前端源码里到处是 `@/...`（vite 的 `resolve.alias`）。`node --test` 不认这个别名，
 * 于是所有 import 了 `@/` 的模块都进不了单测 —— `graph-format.ts` 就是这样一个模块，
 * 而它正是「契约 ↔ 画布」唯一的那层翻译。没有测试覆盖的翻译层，就是 `#335` / `#337` 里
 * 「两边各写一个名字、各自都绿」能活下来的地方。
 *
 * <p>做两件事：把 `@/x` 映射到 `src/x`；把相对路径里省略的 `.ts` 补上
 * （`workflow.ts` 写的是 `export … from './nodeTypes.generated'`，TS 允许省略扩展名，
 * node 的 ESM 解析不允许）。两件都按 `.ts` / `.tsx` / `index.ts` 补全。
 * 刻意不处理 `.vue`（node 解释不了 SFC），也不实现别的别名 —— 覆盖不到就应该报错，
 * 而不是悄悄换一个文件。
 */
import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const ROOT = path.resolve(import.meta.dirname, '..')
const SRC = path.join(ROOT, 'src')

function withExtensions(base) {
  const candidates = [base, `${base}.ts`, `${base}.tsx`, path.join(base, 'index.ts')]
  for (const candidate of candidates) {
    if (existsSync(candidate) && !candidate.endsWith('.vue')) return candidate
  }
  return null
}

function resolveInSrc(rest) {
  return withExtensions(path.join(SRC, rest))
}

function resolveRelative(specifier, parent) {
  if (!parent || !parent.startsWith('file:')) return null
  let base
  try {
    base = path.resolve(path.dirname(fileURLToPath(parent)), specifier)
  } catch {
    return null
  }
  // 文件真的存在就别动它（含 `.js` 这种带扩展名的正常引用）；
  // 不存在才尝试补 `.ts` —— TS 允许 `from './x'`，node 的 ESM 解析不允许。
  if (existsSync(base) && !base.endsWith('.vue')) return base
  return withExtensions(base)
}

export async function resolve(specifier, context, next) {
  if (specifier.startsWith('@/')) {
    const file = resolveInSrc(specifier.slice(2))
    if (file) return next(pathToFileURL(file).href, context)
    throw new Error(`[@loader] 别名「${specifier}」在 src 下找不到对应文件`)
  }
  if (specifier.startsWith('./') || specifier.startsWith('../')) {
    const file = resolveRelative(specifier, context.parentURL)
    if (file) return next(pathToFileURL(file).href, context)
  }
  return next(specifier, context)
}
