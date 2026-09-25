import { listKnowledgeBases } from '@/api/knowledge'

/**
 * 知识库列表（旧 `allList`，旧名沿用"dataset"）。
 *
 * **改名在这一层做完**：旧组件读 `baseName`（契约叫 `name`）。
 * 不改名的话「添加关联知识库」弹窗里 `props.data.filter(v => v.baseName.includes(val))`
 * 会因 `baseName === undefined` **直接抛 TypeError**，列表根本出不来。
 * `name` / `icon` 照样保留（`search-dataset-node` 读的是这两个）。
 */
const wrap = <T>(payload: T) => ({ data: { data: payload } })

export const allList = () =>
  listKnowledgeBases({ size: 200 }).then((r) =>
    wrap(
      (r.list ?? []).map((k) => ({
        ...k,
        baseName: k.name,
        knowledgeIcon: k.icon
      }))
    )
  )
