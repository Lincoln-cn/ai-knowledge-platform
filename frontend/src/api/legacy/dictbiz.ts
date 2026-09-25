/**
 * ⚠️ **搬迁适配层** —— 旧 `api/system/dictbiz`（BladeX 运行时字典）。
 *
 * 新工程没有字典体系：字典值本来就是**代码常量**（见 `constants/permissions.ts`
 * 的同款理由）。这里的值取自新契约的 `Supplier` 联合类型（`api/models.ts`），
 * 不再由后端下发 —— 否则"字典表里少一条"会表现成"下拉框少一个选项"，
 * 而且没人知道该去哪补。
 */
const wrap = <T>(payload: T) => ({ data: { data: payload } })

const TABLES: Record<string, string[]> = {
  // 旧调用点会 `.slice(1)` 丢掉第一项（约定的「全部」），故「全部」排最前
  supplier: ['全部', '本地推理', '外部API']
}

export const getDictionary = (params: { code: string }) =>
  Promise.resolve(wrap((TABLES[params.code] ?? []).map((v) => ({ key: v, value: v }))))
