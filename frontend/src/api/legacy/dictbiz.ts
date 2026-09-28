/**
 * 字典适配层 —— 服务于从旧工程整体搬过来的调用方。
 *
 * 新工程没有「后端下发字典」这一体系：字典值本来就是**代码常量**（见 `constants/permissions.ts`
 * 的同款理由）。这里的值取自新契约的 `Supplier` 联合类型（`api/models.ts`），
 * 不再由后端下发 —— 否则"字典表里少一条"会表现成"下拉框少一个选项"，
 * 而且没人知道该去哪补。
 *
 * 字典项**同时带旧字段名**（`dictValue`/`name`/`dictKey`/`provider`）：
 * 旧调用点读的是这些名字，形状差异在本层吸收，不去改画布组件。
 *
 * 它是**唯一的删除点**：把调用方正规化到 `@/api/*` 之后，整个 `api/legacy/` 一次删掉即可。
 */
const wrap = <T>(payload: T) => ({ data: { data: payload } })

const TABLES: Record<string, string[]> = {
  // 旧调用点会 `.slice(1)` 丢掉第一项（约定的「全部」），故「全部」排最前
  supplier: ['全部', 'LOCAL_INFERENCE', 'EXTERNAL_API']
}

export const getDictionary = (params: { code: string }) =>
  Promise.resolve(
    wrap(
      (TABLES[params.code] ?? []).map((v) => ({
        key: v,
        value: v,
        // 旧调用点读 dictValue / name / dictKey / provider —— 同值的别名
        dictValue: v,
        name: v,
        dictKey: v,
        provider: v
      }))
    )
  )
