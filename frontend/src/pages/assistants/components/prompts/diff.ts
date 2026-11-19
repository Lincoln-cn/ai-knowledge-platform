/**
 * 逐行 diff（LCS）—— 只为一个抽屉服务，所以自己写，不引 diff 库。
 *
 * `frontend/README.md` 的依赖白名单里没有 diff 库，为一个版本对比弹窗加一条依赖要给理由；
 * 而这套逻辑 40 行就够，且能直接单测（见 `diff.test.ts`）。
 *
 * 输出是"按行"的扁平序列：`same` 原样、`added` 只在右侧出现、`removed` 只在左侧出现。
 * 界面上前两者正常排、后面的加底色 —— 不做并排双栏，因为提示词改动通常是"改一句话"，
 * 并排会让一句话在两边各占一行、看着像改了两处。
 */

export type DiffLineKind = 'same' | 'added' | 'removed'

export interface DiffLine {
  kind: DiffLineKind
  text: string
}

/** 按行切；保留空行（空行也是内容），末尾换行不多切出一个空元素。 */
function splitLines(text: string): string[] {
  if (!text) {
    return []
  }
  const normalized = text.replace(/\r\n/g, '\n')
  const lines = normalized.split('\n')
  if (lines.length > 0 && lines[lines.length - 1] === '') {
    lines.pop()
  }
  return lines
}

/**
 * 比较两段文本，返回逐行差异。
 *
 * @param before 旧版正文
 * @param after 新版正文
 */
export function diffLines(before: string, after: string): DiffLine[] {
  const a = splitLines(before)
  const b = splitLines(after)
  const n = a.length
  const m = b.length

  // lcs[i][j] = a[i..] 与 b[j..] 的最长公共子序列长度。
  // 行数在这个场景里最多几百，二维表完全够用，换来的是"没有递归、不会栈溢出"。
  const lcs: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1])
    }
  }

  const out: DiffLine[] = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({ kind: 'same', text: a[i] })
      i++
      j++
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      out.push({ kind: 'removed', text: a[i] })
      i++
    } else {
      out.push({ kind: 'added', text: b[j] })
      j++
    }
  }
  while (i < n) {
    out.push({ kind: 'removed', text: a[i++] })
  }
  while (j < m) {
    out.push({ kind: 'added', text: b[j++] })
  }
  return out
}

/** 有变化吗（用来决定要不要在版本列表上标"与当前不同"）。 */
export function hasDiff(before: string, after: string): boolean {
  return diffLines(before, after).some((line) => line.kind !== 'same')
}
