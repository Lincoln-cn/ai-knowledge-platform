/**
 * 命中高亮（0.9.0）—— 把分段正文里与查询相关的那几截标出来。
 *
 * ★ 为什么是"片段匹配"而不是"分词上色"：
 *   中文没有空格，前端上分词器等于把后端的分词规则抄一份（两处规则必然漂）。
 *   这里用的是最笨也最稳的办法 —— **拿查询里的连续汉字串去正文里找原样出现的位置**。
 *   它不会把"工作日"和"工作时间"混为一谈（那需要语义），但也不会把不存在的词画上去。
 *
 * ★ 长度下限取 2、上限取 12：单字的命中（"的""了""年"）在正文里到处都是，
 *   高亮出来等于整段变色 —— 那不是"命中"，是噪声。12 是"一句话片段"的经验上限。
 *
 * ★ 重叠合并：按长度降序匹配，长的先占位，短的只填空隙 ——
 *   否则"打卡申请"与"申请"会互相切断，渲染成一堆碎 `<mark>`。
 */

/** 只认汉字：拉丁字母与数字走不进来（英文词用整词匹配会误伤，数字匹配没有语义） */
const HAN = /\p{Script=Han}/u

const MIN_LEN = 2
const MAX_LEN = 12

/** 高频虚词：它们在任何一段里都能命中，标出来只增加噪声 */
const STOP = new Set([
  '什么',
  '怎么',
  '如何',
  '多少',
  '几个',
  '哪些',
  '是否',
  '可以',
  '应该',
  '需要',
  '我们',
  '你们',
  '他们',
  '这个',
  '那个',
  '这些',
  '那些',
  '以及',
  '并且',
  '或者',
  '但是',
  '因为',
  '所以',
  '如果',
  '就是',
  '还是'
])

/** 把查询切成"候选片段"：连续汉字串，再去掉长度不合规与停用词 */
export function queryFragments(query: string): string[] {
  const raw = String(query ?? '')
  const runs: string[] = []
  let buf = ''
  for (const ch of raw) {
    if (HAN.test(ch)) {
      buf += ch
    } else if (buf) {
      runs.push(buf)
      buf = ''
    }
  }
  if (buf) runs.push(buf)

  const out: string[] = []
  const seen = new Set<string>()
  for (const run of runs) {
    // 沿串取所有长度在 [MIN_LEN, MAX_LEN] 的连续子串 —— 查询常是"整句"，
    // 而正文里出现的是其中一截（"打卡申请…办结"）。
    for (let len = Math.min(MAX_LEN, run.length); len >= MIN_LEN; len--) {
      for (let start = 0; start + len <= run.length; start++) {
        const frag = run.slice(start, start + len)
        if (STOP.has(frag) || seen.has(frag)) continue
        seen.add(frag)
        out.push(frag)
      }
    }
  }
  // 长的先匹配：短的只填空隙，避免互相切断
  return out.sort((a, b) => b.length - a.length)
}

export interface HighlightPart {
  /** 片段文本 */
  text: string
  /** 是否为命中片段 */
  hit: boolean
}

/**
 * 把正文切成命中 / 非命中片段，供模板渲染 `<mark>`。
 *
 * @param content 分段正文
 * @param query 用户输入的查询
 * @param maxFragments 参与匹配的片段数上限（默认 400 —— 长查询的 n-gram 会平方增长）
 */
export function highlightParts(content: string, query: string, maxFragments = 400): HighlightPart[] {
  const text = String(content ?? '')
  if (!text) return []
  const all = queryFragments(query)
  // ★ 只要长度 ≥ 4 的片段：2–3 字的多是"申请""办结""管理"这类通用词，
  //   它们在一段里能出现七八次，标出来就是整段花掉（实测 107 个 `<mark>`）。
  //   长片段才是"这句话跟查询有关"的证据；查询本身短于 4 字时才退回短片段。
  const strong = all.filter((f) => f.length >= 4)
  const frags = (strong.length ? strong : all).slice(0, maxFragments)
  if (!frags.length) return [{ text, hit: false }]

  const n = text.length
  const marked = new Uint8Array(n)
  for (const frag of frags) {
    if (frag.length < MIN_LEN) continue
    let from = 0
    for (;;) {
      const at = text.indexOf(frag, from)
      if (at < 0) break
      let clean = true
      for (let i = at; i < at + frag.length; i++) {
        if (marked[i]) {
          clean = false
          break
        }
      }
      if (clean) marked.fill(1, at, at + frag.length)
      from = at + 1
    }
  }

  const parts: HighlightPart[] = []
  let start = 0
  for (let i = 1; i <= n; i++) {
    if (i === n || marked[i] !== marked[start]) {
      parts.push({ text: text.slice(start, i), hit: marked[start] === 1 })
      start = i
    }
  }
  return parts
}
