const icons: any = import.meta.glob('./**.vue', { eager: true })

/**
 * 节点类型取值 → 图标组件。
 *
 * ★ 这里正好是**两套词汇相遇的唯一一处**，所以映射只写在这里：
 *   节点类型是**取值**（`START_NODE`，规范 14 §2.3），被 `===` 比较、进 JSON、进 `nodeDict` 的键；
 *   图标是**文件名**（`start-node-icon.vue`），规范 14 §三 明确「文件名 / 目录名 / 组件名不动」。
 *   两者形如 `_` ↔ `-`、大写 ↔ 小写，但不是同一个东西 —— 别把图标文件改名去迁就取值，
 *   也别在调用点各自 `toLowerCase()`，否则又是一处"改一半、静默取到 null（图标消失）」。
 *
 * `global` 是级联里的伪类型（全局变量分组），不是节点类型，原样透传。
 */
export function nodeIcon(type: string | undefined): any {
    if (!type) return null
    return iconComponent(`${type.toLowerCase().replace(/_/g, '-')}-icon`)
}

export function iconComponent(name: string) {
    const url = `./${name}.vue`
    return icons[url]?.default || null
}
