// ★ 直接引生成物而不是 `@/api/workflow` 门面：门面会连带拉起 http / mock 那一整条链，
//   而这里要的只是五张纯常量表。单测能跑（node --test 不认 `@/lib/http` 的下游）。
import {
  NODE_DEFAULT_DATA,
  NODE_OUTPUT_FIELDS,
  NODE_TYPE_DESCRIPTIONS,
  NODE_TYPE_LABELS,
  NODE_TYPE_ORDER,
} from '@/api/nodeTypes.generated';
import type { NodeVariableField, WorkflowNodeTypeName } from '@/api/nodeTypes.generated';

/**
 * 画布节点目录（`nodeDict`）。
 *
 * ★ 这里<b>不再手写 18 条节点定义</b>（`#337` 第二步 B5）。显示名、描述、变量目录、新建节点
 *   的默认配置全部来自 `node-types.json` → `node-types.generated.ts`。
 *
 * ★ 从前每条都手写一份，结果最严重的一处不是"重复"，而是<b>对不上</b>：检索节点的变量目录写着
 *   `paragraph_list` / `is_hit_handling_method_list` / `data` / `directly_return` 四个键，
 *   而 `SearchDatasetNodeExecutor` 实际只写 `retrieval_result`；问题改写节点写的是 `answer`，
 *   而执行器写 `query`。下游在变量选择器里选到的是一个<b>永远取不到值</b>的字段，
 *   而两边都能编译、都能单测通过 —— 只有真跑一遍才看得见。
 *
 * ★ 只剩两件事留在前端：画布视觉高度（属布局，不进后端目录）与这段拼装逻辑。
 */

/** 画布节点高度（布局数据；没有列出的按 {@link DEFAULT_HEIGHT}）。 */
const NODE_HEIGHTS: Partial<Record<WorkflowNodeTypeName, number>> = {
  START_NODE: 200,
  BASE_NODE: 200,
  AI_CHAT_NODE: 340,
  SEARCH_DATASET_NODE: 355,
  QUESTION_NODE: 345,
  CONDITION_NODE: 175,
  NUMBER_CONDITION_NODE: 175,
  REPLY_NODE: 210,
  FUNCTION_LIB_NODE: 170,
  FUNCTION_NODE: 260,
  APPLICATION_NODE: 260,
};

const DEFAULT_HEIGHT = 252;

/**
 * 开始节点没有配置字段，但它的「全局变量」是画布级数据（`properties.config.globalFields`），
 * 不是节点配置 —— 目录里只描述"节点自己读什么"，所以这一格留在这里。
 */
const START_GLOBAL_FIELDS = [{ value: 'time', label: '当前时间' }];

interface NodeDictEntry {
  type: WorkflowNodeTypeName;
  text: string;
  label: string;
  height: number;
  properties: {
    stepName: string;
    node_data?: Record<string, unknown>;
    config: { fields: { label: string; value: string }[]; globalFields?: { value: string; label: string }[] };
  };
}

function entry(type: WorkflowNodeTypeName): NodeDictEntry {
  const label = NODE_TYPE_LABELS[type] ?? type;
  const fields: readonly NodeVariableField[] = NODE_OUTPUT_FIELDS[type] ?? [];
  const dict: NodeDictEntry = {
    type,
    text: NODE_TYPE_DESCRIPTIONS[type] ?? '',
    label,
    height: NODE_HEIGHTS[type] ?? DEFAULT_HEIGHT,
    properties: {
      stepName: label,
      // structuredClone：每个节点实例都要一份独立的默认值，共用同一个对象会让拖第二个节点时
      // 改到第一个节点的数据。
      node_data: structuredClone(NODE_DEFAULT_DATA[type] ?? {}),
      config: {
        fields: fields.map((field) => ({ label: field.label, value: field.value })),
      },
    },
  };
  // 开始节点以前只有 config.fields（用户问题）与 config.globalFields（全局变量），没有 node_data。
  if (type === 'START_NODE') {
    dict.properties.config.globalFields = structuredClone(START_GLOBAL_FIELDS);
    delete dict.properties.node_data;
  }
  return dict;
}

/** 类型 → 画布节点定义。三个消费方：`graph-format` 读变量目录、`app-node` 补默认属性、菜单克隆新建。 */
export const nodeDict: Record<string, NodeDictEntry> = Object.fromEntries(
  NODE_TYPE_ORDER.map((type) => [type, entry(type)])
);

export const compareList = [
  { value: 'is_null', label: '为空' },
  { value: 'is_not_null', label: '不为空' },
  { value: 'contain', label: '包含' },
  { value: 'not_contain', label: '不包含' },
  { value: 'eq', label: '等于' },
  { value: 'ge', label: '大于等于' },
  { value: 'gt', label: '大于' },
  { value: 'le', label: '小于等于' },
  { value: 'lt', label: '小于' },
  { value: 'len_eq', label: '长度等于' },
  { value: 'len_ge', label: '长度大于等于' },
  { value: 'len_gt', label: '长度大于' },
  { value: 'len_le', label: '长度小于等于' },
  { value: 'len_lt', label: '长度小于' },
];

export function isWorkFlow(type: string | undefined) {
  return type === 'WORK_FLOW';
}

export function isLastNode(nodeModel: any) {
  const incoming = nodeModel.graphModel.getNodeIncomingNode(nodeModel.id);
  const outcomming = nodeModel.graphModel.getNodeOutgoingNode(nodeModel.id);
  if (incoming.length > 0 && outcomming.length === 0) {
    return true;
  } else {
    return false;
  }
}
