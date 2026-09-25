/** 随机 id（旧实现：4 位以内数字字符串）。画布里用于临时节点/参数标识。 */
export const randomId = () => Math.floor(Math.random() * 10000) + ''

/** 从数组里按属性值取第一个匹配项，取不到返回 null。 */
export function relatedObject<T>(list: T[], val: unknown, attr: keyof T): T | null {
  return list.filter((item) => item[attr] === val)[0] ?? null
}
