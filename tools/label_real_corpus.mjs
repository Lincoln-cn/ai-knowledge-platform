// 真实语料的两轮独立标注：第一轮逐段出题，第二轮给整篇 + 问题独立指认答案段，
// 只保留两轮指向同一段、且答案句逐字可在该段找到的条目。
//
// 用法：node tools/label_real_corpus.mjs --per-doc 4 --out corpus/real/qa-labeled.jsonl
//       --in 可以指向另一份导出（例如只导新抓的几篇，避免把已标注过的批次重算一遍）
import { execFileSync } from 'node:child_process'
import { createDecipheriv } from 'node:crypto'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'

const args = process.argv.slice(2)
function arg(name, dflt) {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && i + 1 < args.length ? args[i + 1] : dflt
}
const PER_DOC = Number(arg('per-doc', '4'))
const OUT = arg('out', 'D:/IdeaProjects/ai-plantform/corpus/real/qa-labeled.jsonl')
const SEG_FILE = arg('in', 'D:/IdeaProjects/ai-plantform/.build/real-corpus/segments.jsonl')
const SECRET = 'ZGV2LW9ubHktYXBpLWtleS1zZWNyZXQtMzJieXRlcyE='

function deepseekKey() {
  const out = execFileSync(
    'docker',
    ['exec', 'aip-postgres', 'psql', '-U', 'aiplatform', '-d', 'aiplatform', '-t', '-A', '-c',
      "select api_key_cipher || '|' || api_key_iv from ai_model_provider where kind = 'DEEPSEEK' and enabled"],
    { encoding: 'utf8' },
  )
  const [cipherB64, ivB64] = out.trim().split('|')
  const key = Buffer.from(SECRET, 'base64')
  const raw = Buffer.from(cipherB64, 'base64')
  const d = createDecipheriv('aes-256-gcm', key, Buffer.from(ivB64, 'base64'))
  d.setAuthTag(raw.subarray(raw.length - 16))
  return Buffer.concat([d.update(raw.subarray(0, raw.length - 16)), d.final()]).toString('utf8')
}

const KEY = deepseekKey()

async function chat(messages, maxTokens = 700) {
  const r = await fetch('https://api.deepseek.com/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}` },
    body: JSON.stringify({
      model: 'deepseek-chat',
      messages,
      temperature: 0,
      max_tokens: maxTokens,
      response_format: { type: 'json_object' },
    }),
  })
  if (!r.ok) throw new Error(`DS ${r.status}: ${(await r.text()).slice(0, 200)}`)
  const b = await r.json()
  const text = b.choices?.[0]?.message?.content ?? ''
  try {
    return JSON.parse(text)
  } catch {
    const m = text.match(/\{[\s\S]*\}/)
    if (!m) throw new Error('模型没返回 JSON：' + text.slice(0, 120))
    return JSON.parse(m[0])
  }
}

// 出题：只给这一段
const GEN_SYS = `你是企业制度知识库的评测题编写者。给你一段法规或制度正文，你要出一道"人在查制度时会怎么问"的问题，答案必须完全落在这一段里。
输出 json：{"question": "问题", "answer": "答案原文片段"}
硬性要求：
1. answer 必须从原文逐字复制一段连续文字（可以省略前后文，但不能改写、不能概括、不能补字）；
2. question 里不得出现 answer 中的关键数字或关键名词，否则问题等于把答案念出来了；
3. 问题要具体（问"多少天""由谁审批""保存几年"这类），不要问"这段话讲了什么"；
4. question 用自然口语，不要带"根据第X条"这种位置提示。`

// 验证：给整篇所有段落 + 问题，让它独立指认
const VERIFY_SYS = `你是文档检索的校验员。给你一个问题和一份文件被切分后的若干段落（每段有编号），你要指出哪一段包含该问题的答案，并逐字抄出答案句。
输出 json：{"ordinal": 编号, "answer": "答案原文片段"}
规则：
1. 只能选一个段落；哪一段都回答不了时输出 {"ordinal": -1, "answer": ""}；
2. answer 必须从你选中的那一段里逐字复制；
3. 判断依据是"这段直接回答了问题"，而不是"这段提到了相关概念"。`

function stripMd(s) {
  return s.replace(/\*\*/g, '').replace(/^#+\s*/gm, '').replace(/\\\|/g, '|')
}

function contains(hay, needle) {
  const h = stripMd(hay).replace(/\s+/g, '')
  const n = stripMd(needle).replace(/\s+/g, '')
  return n.length > 0 && h.includes(n)
}

const docs = readFileSync(SEG_FILE, 'utf8').split(/\r?\n/).filter(Boolean).map((l) => JSON.parse(l))
let total = 0
let kept = 0
const rows = []

for (const doc of docs) {
  const segs = doc.segments
  const picked = []
  for (const s of segs) {
    if (s.content.length < 180) continue
    if (s.content.startsWith('# ')) continue // 标题段不出题
    picked.push(s)
    if (picked.length >= PER_DOC) break
  }

  for (const s of picked) {
    total++
    let gen
    try {
      gen = await chat([
        { role: 'system', content: GEN_SYS },
        { role: 'user', content: `文件：《${doc.name}》\n段落：\n${stripMd(s.content)}` },
      ])
    } catch (e) {
      console.log(`  ✗ 出题失败 ${doc.name}#${s.ordinal}: ${e.message}`)
      continue
    }
    if (!gen?.question || !gen?.answer) {
      console.log(`  ✗ 出题缺字段 ${doc.name}#${s.ordinal}`)
      continue
    }

    const listing = segs
      .map((x) => `[${x.ordinal}] ${stripMd(x.content).replace(/\s+/g, ' ').slice(0, 500)}`)
      .join('\n')
    let ver
    try {
      ver = await chat(
        [
          { role: 'system', content: VERIFY_SYS },
          { role: 'user', content: `问题：${gen.question}\n\n文件《${doc.name}》的段落：\n${listing}` },
        ],
        400,
      )
    } catch (e) {
      console.log(`  ✗ 验证失败 ${doc.name}#${s.ordinal}: ${e.message}`)
      continue
    }

    const agree = Number(ver?.ordinal) === s.ordinal
    const answerInSeg = contains(s.content, gen.answer)
    if (agree && answerInSeg) {
      kept++
      rows.push({
        id: `${doc.name.replace(/\.md$/, '').replace(/^\d+-/, '')}-${s.ordinal}`,
        question: gen.question,
        expected_document: doc.name,
        expected_snippet: gen.answer,
        answer_ordinal: s.ordinal,
        source: 'two-round-agreed',
        doc_id: doc.documentId,
      })
      console.log(`  ✓ ${doc.name}#${s.ordinal}  ${gen.question}`)
    } else {
      console.log(
        `  ✗ ${doc.name}#${s.ordinal} 一致=${agree}(验证=${ver?.ordinal}) 答案在段内=${answerInSeg}`,
      )
    }
  }
}

writeFileSync(OUT, rows.map((r) => JSON.stringify(r)).join('\n') + (rows.length ? '\n' : ''), 'utf8')
console.log(`\n出题 ${total} 条 / 保留 ${kept} 条（一致率 ${total ? Math.round((kept / total) * 100) : 0}%）→ ${OUT}`)
