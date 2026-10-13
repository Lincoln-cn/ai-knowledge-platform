import type { ClassificationLevel } from '@/api/types'
import type { DictionaryItem } from '@/api/dictionaries'

/**
 * 密级的**比较轴**与**兜底文案**（工程惯例四级：公开 / 内部 / 秘密 / 机密）。
 *
 * ★ 为什么这里需要一份兜底文案，而其它枚举的文案都在 `constants/labels.ts`：
 *   密级的展示名是**可维护的字典**（`08 §十`，真源 = 基础数据页），所以正常路径是
 *   `useDictionary('CLASSIFICATION')`。但有两处读不到字典：
 *     ① 未登录的公开页 —— 读字典要登录态（`mock/handlers/dictionaries.ts` 与后端
 *        `anyRequest().authenticated()` 同形），拿不到就是 401；
 *     ② 字典接口失败。
 *   两处都**不能**把结果回落成机器码（`SECRET`）—— 本项目栽过「把状态码当文案渲染」。
 *   所以兜底取**后端种子里的原值**（`V24__platform_dictionary.sql`，与
 *   `mock/fixtures/dictionaries.ts` 逐字一致）。管理员改了展示名时，兜底会短暂落后于
 *   字典 —— 这是刻意的：**落后一句展示名，好过让人看见 `CONFIDENTIAL`**。
 *
 * ★ 档位与排序由数据库生成列（`classification_rank`，V21/V22）写死，
 *   所以 `CLASSIFICATION_RANK` 不是"另一处口径"，而是同一条迁移约束的前端镜像。
 *   加一档要连生成列一起迁移（见 `basedata/Index.vue` 的说明），届时本文件跟着改。
 */
export const CLASSIFICATION_LABELS: Record<ClassificationLevel, string> = {
  PUBLIC: '公开',
  INTERNAL: '内部',
  SECRET: '秘密',
  CONFIDENTIAL: '机密'
}

/** 比较轴：值越大越密。与 `classification_rank` 同序 */
export const CLASSIFICATION_RANK: Record<ClassificationLevel, number> = {
  PUBLIC: 1,
  INTERNAL: 2,
  SECRET: 3,
  CONFIDENTIAL: 4
}

/**
 * 算密级上限（`e` 指内容密级）。**只回答"能不能"，不回答"为什么"** ——
 * 引用只含已授权分段（`13 §5.1` 规则 3），所以界面上永远不存在
 * "有 N 条你看不到"这种话（那是侧信道，`13 §6.2`）。
 */
export const exceedsCeiling = (level: ClassificationLevel, ceiling: ClassificationLevel): boolean =>
  CLASSIFICATION_RANK[level] > CLASSIFICATION_RANK[ceiling]

/**
 * 把字典项翻成"取值 → 展示名"。
 * 未知码（字典里没有的档位）原样返回，避免界面出现 `undefined`。
 */
export function classificationLabelMap(
  items: Pick<DictionaryItem, 'code' | 'label'>[]
): Record<string, string> {
  const map: Record<string, string> = { ...CLASSIFICATION_LABELS }
  for (const item of items) {
    if (item?.code && item.label) map[item.code] = item.label
  }
  return map
}
