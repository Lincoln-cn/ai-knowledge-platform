#!/usr/bin/env node
/**
 * 从后端节点目录生成前端那份"节点类型清单"。
 *
 * 两个真源：
 *   1. `backend/src/main/resources/workflow/node-types.json` —— 类型名、显示名、分类、两个布尔、字段清单、输出变量
 *   2. `frontend/src/pages/workflow/components/nodes/<kebab>/index.ts` —— 前端是否已就绪（有模块就算就绪）
 *
 * ★ 为什么"就绪"由目录决定而不是 json 里再加一个布尔：json 已经有两个布尔（catalogEnabled / executable），
 *   再加一个"前端就绪"就是第三个维度写进数据，而它本来可以由"文件在不在"直接回答 —— 能由文件回答的事
 *   不要写进数据，否则删掉模块时那个布尔会留在 json 里骗人（#337）。
 *
 * 用法：
 *   node --run gen:node-types      # 重新生成
 *   node --run check:node-types    # 比对，漂了 exit 1（CI 用）
 */
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const frontend = resolve(here, '..');
const repo = resolve(frontend, '..');
const catalogPath = join(repo, 'backend/src/main/resources/workflow/node-types.json');
const nodesDir = join(frontend, 'src/pages/workflow/components/nodes');
const outPath = join(frontend, 'src/api/nodeTypes.generated.ts');

const HEADER = `/**
 * ⚠️ 本文件由 \`frontend/scripts/generate-node-types.mjs\` 生成，不要手改。
 *
 * 真源：
 *   · backend/src/main/resources/workflow/node-types.json（类型名 / 显示名 / 分类 / 字段 / 输出变量）
 *   · frontend/src/pages/workflow/components/nodes/<kebab>/index.ts（前端是否已就绪）
 *
 * 重新生成：\`node --run gen:node-types\`
 * 校验漂移：\`node --run check:node-types\`
 */`;

const s = (value) => JSON.stringify(value);

function readCatalog() {
  return JSON.parse(readFileSync(catalogPath, 'utf8'));
}

/** 前端已就绪的类型：扫 nodes 目录里每个 index.ts 声明的那一行。 */
function readReadyTypes() {
  const ready = new Map();
  for (const entry of readdirSync(nodesDir, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const index = join(nodesDir, entry.name, 'index.ts');
    if (!existsSync(index)) continue;
    const match = /type:\s*'([A-Z0-9_]+)'/.exec(readFileSync(index, 'utf8'));
    if (!match) {
      throw new Error(
        `nodes/${entry.name}/index.ts 里找不到 type: '...'（画布注册名）—— 这个模块无法被生成器识别`
      );
    }
    ready.set(match[1], entry.name);
  }
  return ready;
}

function build() {
  const catalog = readCatalog();
  const ready = readReadyTypes();
  const order = catalog.map((meta) => meta.type);

  const unknown = [...ready.keys()].filter((type) => !order.includes(type));
  if (unknown.length > 0) {
    throw new Error(
      `画布上有目录里不存在的类型：${unknown.join(', ')} —— 先把它加进 node-types.json（否则发布校验会报未知类型）`
    );
  }

  const implemented = order.filter((type) => ready.has(type));
  const planned = order.filter((type) => !ready.has(type));
  const byType = new Map(catalog.map((meta) => [meta.type, meta]));

  // 后端已能跑、前端还没就绪：不是错，但必须让它可见（FORM_NODE 今天就是这一格，#337）。
  const backendOnly = planned.filter((type) => byType.get(type).executable === true);

  // 前端就绪但后端跑不了 = 画布能拖、发布必拒。这是 #331 的中间态，允许存在但要在生成物里点出来。
  const readyButNotExecutable = implemented.filter((type) => byType.get(type).executable !== true);

  const lines = [];
  lines.push(HEADER);
  lines.push('');

  lines.push('/** 画布上已经具备完整前端能力的节点类型（有 nodes/<kebab>/index.ts 就算就绪）。 */');
  lines.push('export const IMPLEMENTED_NODE_TYPES = [');
  for (const type of implemented) lines.push(`  ${s(type)},`);
  lines.push('] as const;');
  lines.push('');
  lines.push('export type ImplementedNodeType = (typeof IMPLEMENTED_NODE_TYPES)[number];');
  lines.push('');

  lines.push('/** 后端目录里有、前端还没进画布的类型。 */');
  lines.push('export const PLANNED_NODE_TYPES = [');
  for (const type of planned) lines.push(`  ${s(type)},`);
  lines.push('] as const;');
  lines.push('');
  lines.push('export type PlannedNodeType = (typeof PLANNED_NODE_TYPES)[number];');
  lines.push('');

  lines.push(`/** 目录顺序（18 种）。菜单与校验都按它排。 */`);
  lines.push('export const NODE_TYPE_ORDER = [');
  for (const type of order) lines.push(`  ${s(type)},`);
  lines.push('] as const;');
  lines.push('');

  lines.push('/** 类型 → 显示名（node-types.json 的 label）。 */');
  lines.push('export const NODE_TYPE_LABELS: Record<WorkflowNodeTypeName, string> = {');
  for (const meta of catalog) lines.push(`  ${meta.type}: ${s(meta.label)},`);
  lines.push('};');
  lines.push('');

  lines.push('/** 画布节点菜单里的类型（就绪的那一批，按目录顺序）。 */');
  lines.push('export const WORKFLOW_NODE_TYPE_VALUES: ImplementedNodeType[] = [');
  lines.push('  ...IMPLEMENTED_NODE_TYPES,');
  lines.push('];');
  lines.push('');

  lines.push('/** 目录里声明的配置字段（node-types.json 的 configFields）。 */');
  lines.push('export interface NodeConfigField {');
  lines.push('  readonly key: string;');
  lines.push('  readonly label: string;');
  lines.push('  /** 字段类型：string / uuid / string-list / number / object / branch-list … */');
  lines.push('  readonly type: string;');
  lines.push('  readonly required: boolean;');
  lines.push('  /** 只给面板用：执行器不读它（#336 的悬空字段就靠这一格表达）。 */');
  lines.push('  readonly panelOnly: boolean;');
  lines.push('  /** 长文本的编辑形态：`markdown` 用 Markdown 编辑器，缺省是普通输入框。 */');
  lines.push('  readonly editor?: string;');
  lines.push('  /** 选项从哪来：`knowledge-bases`（全部知识库）/ `documents`（所选库里的文档）。 */');
  lines.push('  readonly optionsSource?: string;');
  lines.push('  /** 候选项依赖哪个字段（文档依赖库）。依赖为空 ⇒ 候选也为空。 */');
  lines.push('  readonly dependsOn?: string;');
  lines.push('  /** 固定选项（如回复形态只有一种取值）。 */');
  lines.push('  readonly options?: readonly string[];');
  lines.push('  /** 面板上的说明文字。 */');
  lines.push('  readonly hint?: string;');
  lines.push('}');
  lines.push('');
  lines.push(
    'export const NODE_CONFIG_FIELDS: Record<ImplementedNodeType, readonly NodeConfigField[]> = {'
  );
  for (const type of implemented) {
    const fields = byType.get(type).configFields ?? [];
    if (fields.length === 0) {
      lines.push(`  ${type}: [],`);
      continue;
    }
    lines.push(`  ${type}: [`);
    for (const field of fields) {
      const extras = [];
      if (field.editor) extras.push(`editor: ${s(field.editor)}`);
      if (field.optionsSource) extras.push(`optionsSource: ${s(field.optionsSource)}`);
      if (field.dependsOn) extras.push(`dependsOn: ${s(field.dependsOn)}`);
      if (Array.isArray(field.options) && field.options.length > 0) {
        extras.push(`options: [${field.options.map(s).join(', ')}]`);
      }
      if (field.hint) extras.push(`hint: ${s(field.hint)}`);
      const tail = extras.length > 0 ? `, ${extras.join(', ')}` : '';
      lines.push(
        `    { key: ${s(field.key)}, label: ${s(field.label)}, type: ${s(field.type)}, required: ${
          field.required === true
        }, panelOnly: ${field.panelOnly === true}${tail} },`
      );
    }
    lines.push('  ],');
  }
  lines.push('};');
  lines.push('');

  lines.push('/** 目录里声明的流程变量（node-types.json 的 outputs）。 */');
  lines.push('export const NODE_OUTPUT_FIELDS: Record<ImplementedNodeType, readonly string[]> = {');
  for (const type of implemented) {
    const outputs = byType.get(type).outputs ?? [];
    lines.push(`  ${type}: [${outputs.map((key) => s(key)).join(', ')}],`);
  }
  lines.push('};');
  lines.push('');

  lines.push('/**');
  lines.push(' * 全部 18 种类型名。');
  lines.push(' *');
  lines.push(' * ★ 单独给一个"名字联合"而不是直接用 `ImplementedNodeType | PlannedNodeType`，是因为');
  lines.push(' *   目录条目（NodeTypeMeta）覆盖全部 18 种，而画布模块只覆盖已就绪的那一批。');
  lines.push(' */');
  lines.push('export type WorkflowNodeTypeName = (typeof NODE_TYPE_ORDER)[number];');
  lines.push('');

  if (backendOnly.length > 0) {
    lines.push('/**');
    lines.push(' * ★ 后端已能执行、前端还没就绪的类型 —— 这不是错，但必须让它可见。');
    lines.push(' *   今天落在这里的是：' + backendOnly.join(', ') + '。');
    lines.push(' */');
    lines.push('export const BACKEND_ONLY_NODE_TYPES = [' + backendOnly.map((t) => s(t)).join(', ') + '] as const;');
    lines.push('');
  }
  if (readyButNotExecutable.length > 0) {
    lines.push('/**');
    lines.push(' * ★ 前端已就绪、后端发布校验会拒的类型（#331 的中间态：能拖进来，点发布才报错）。');
    lines.push(' *   今天落在这里的是：' + readyButNotExecutable.join(', ') + '。');
    lines.push(' */');
    lines.push(
      'export const READY_BUT_NOT_EXECUTABLE = [' +
        readyButNotExecutable.map((t) => s(t)).join(', ') +
        '] as const;'
    );
    lines.push('');
  }

  return lines.join('\n');
}

function main() {
  const check = process.argv.includes('--check');
  const generated = build();

  if (check) {
    const current = existsSync(outPath) ? readFileSync(outPath, 'utf8') : '';
    if (current !== generated) {
      console.error('[node-types] 生成物与真源不一致。跑 `node --run gen:node-types` 后提交。');
      process.exit(1);
    }
    console.log('[node-types] 一致。');
    return;
  }

  const before = existsSync(outPath) ? readFileSync(outPath, 'utf8') : '';
  writeFileSync(outPath, generated, 'utf8');
  console.log(before === generated ? '[node-types] 无变化。' : `[node-types] 已写入 ${outPath}`);
}

main();
