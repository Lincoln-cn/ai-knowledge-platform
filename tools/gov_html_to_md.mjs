#!/usr/bin/env node
/**
 * 国务院公报（gov.cn/gongbao）HTML → markdown。
 *
 * 为什么单独一个工具：`tools/wikitext_to_md.mjs` 吃的是维基文本，gov.cn 是排版 HTML，
 * 两者的正文定位方式完全不同（这里靠 `class="pages_content"` / `id="UCAP-CONTENT"` 这个容器，
 * 维基那边靠 `{{header}}` 模板）。语料源不同、清洗规则不同，但**下游三个阶段完全复用**
 * （见 `.dsh/skills/corpus-pipeline`「扩展点 1」）。
 *
 * 用法：
 *   node tools/gov_html_to_md.mjs --in .build/gov-raw --out corpus/real [--dry]
 *
 * 输出文件名取 HTML 文件名（`10-中央企业合规管理办法.html` → `corpus/real/10-中央企业合规管理办法.md`），
 * 编号由抓取侧决定，**本脚本不重编号** —— 重编号会让 `corpus/real/qa-labeled.jsonl` 里
 * 的 `expected_document` 集体失配。
 */
import { readFileSync, writeFileSync, readdirSync, mkdirSync, existsSync } from "node:fs";
import { join, basename } from "node:path";

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const at = args.indexOf(`--${name}`);
  return at >= 0 && args[at + 1] ? args[at + 1] : fallback;
};
const IN = opt("in", ".build/gov-raw");
const OUT = opt("out", "corpus/real");
const DRY = args.includes("--dry");

/** 正文容器的几种写法（按命中优先级）。 */
const CONTAINERS = ['class="pages_content"', 'id="UCAP-CONTENT"', 'class="content"'];
/** 正文之后的页脚信号：见到任何一个就截断。 */
const TAIL_MARKS = ["相关稿件", "扫一扫在手机打开当前页", "var currentPage", "网站标识码"];

const ENTITIES = [
  ["&nbsp;", " "],
  ["&amp;", "&"],
  ["&lt;", "<"],
  ["&gt;", ">"],
  ["&quot;", '"'],
  ["&#39;", "'"],
  ["&ldquo;", "“"],
  ["&rdquo;", "”"],
  ["&mdash;", "—"],
  ["&middot;", "·"],
];

/** 剥标签 + 收空白。行内标签直接丢，段落边界由调用方给。 */
function textOf(html) {
  let s = html
    .replace(/<script[\s\S]*?<\/script>/gi, "")
    .replace(/<style[\s\S]*?<\/style>/gi, "")
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<[^>]+>/g, "");
  for (const [from, to] of ENTITIES) s = s.split(from).join(to);
  return s
    .replace(/\u00a0/g, " ")
    .replace(/[ \t]+/g, " ")
    .replace(/^\s+|\s+$/g, "");
}

/** 只要"像正文的一段"：长度够、不是导航、不是页码。 */
function isProse(line) {
  if (!line || line.length < 8) return false;
  if (/^(打印本页|关闭窗口|字号|分享到|【字体|返回顶部)/.test(line)) return false;
  if (/^(首页|简|繁|EN|登录|注册|退出|邮箱|无障碍)$/.test(line)) return false;
  if (/^\d{1,4}$/.test(line)) return false;
  return /[\u4e00-\u9fa5]/.test(line);
}

/** 章节标题进 markdown 标题层级，让检索文本带上结构（`§11.49.6` 第 2 条要查的就是这个）。 */
function shape(line) {
  if (/^第[一二三四五六七八九十百零]+章/.test(line)) return `## ${line}`;
  if (/^第[一二三四五六七八九十百零]+节/.test(line)) return `### ${line}`;
  return line;
}

function convert(name, html) {
  let start = -1;
  for (const marker of CONTAINERS) {
    const at = html.indexOf(marker);
    if (at >= 0) {
      start = at;
      break;
    }
  }
  if (start < 0) return { note: "找不到正文容器", body: "" };

  let slice = html.slice(start);
  for (const mark of TAIL_MARKS) {
    const at = slice.indexOf(mark);
    if (at > 0) slice = slice.slice(0, at);
  }

  const paragraphs = [];
  // ★ 一个 <p> 里可能用 <br> 塞了好几条（2006–2010 那批公报页面就是），
  //   所以 textOf 之后还要按行再切一次 —— 否则整篇会变成一个段落，
  //   而段落是后续切分与标注的最小单位。
  for (const hit of slice.matchAll(/<p[^>]*>([\s\S]*?)<\/p>/gi)) {
    for (const line of textOf(hit[1]).split("\n")) {
      const one = line.trim();
      if (isProse(one)) paragraphs.push(one);
    }
  }
  // 旧版公报（2004 年左右）是表格排版，正文在 <td class="b12c"> 里，没有 <p>。
  if (paragraphs.length < 5) {
    for (const hit of slice.matchAll(/<td[^>]*class="[^"]*b12c[^"]*"[^>]*>([\s\S]*?)<\/td>/gi)) {
      for (const piece of textOf(hit[1]).split("\n")) {
        if (isProse(piece)) paragraphs.push(piece);
      }
    }
  }

  // 同一段落被拆成多段（老页面一个 <p> 一行字）的情形：短碎片并回上一段。
  // 但"第X条 / 第X章 /（一）"开头的一律不并 —— 它们是结构边界。
  const merged = [];
  for (const line of paragraphs) {
    const prev = merged[merged.length - 1];
    const startsNew = /^第.+[章节条]/.test(line) || /^（[一二三四五六七八九十]+）/.test(line);
    if (prev && line.length < 24 && !startsNew && !prev.endsWith("。")) {
      merged[merged.length - 1] = `${prev}${line}`;
    } else {
      merged.push(line);
    }
  }

  const title = basename(name, ".html").replace(/^\d+-/, "");
  const body = merged.map(shape).join("\n\n");
  return { note: "", body: `# ${title}\n\n${body}\n` };
}

if (!existsSync(OUT)) mkdirSync(OUT, { recursive: true });

const files = readdirSync(IN).filter((f) => f.endsWith(".html"));
let written = 0;
for (const file of files) {
  const html = readFileSync(join(IN, file), "utf8");
  const { note, body } = convert(file, html);
  const target = join(OUT, `${basename(file, ".html")}.md`);
  const chars = body.replace(/\s/g, "").length;
  const clauses = (body.match(/^第[一二三四五六七八九十百零]+条/gm) || []).length;
  if (note) {
    console.log(`  ✗ ${file}  ${note}`);
    continue;
  }
  if (chars < 1500) {
    console.log(`  ✗ ${file}  提取过短（${chars} 字），疑正文不在容器里`);
    continue;
  }
  if (!DRY) writeFileSync(target, body, "utf8");
  written++;
  console.log(`  ✓ ${basename(file, ".html")}  ${chars} 字 · ${clauses} 条`);
}
console.log(`\n${DRY ? "（干跑）" : ""}写入 ${written} / ${files.length} 篇 → ${OUT}`);
