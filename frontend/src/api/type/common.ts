/** 通用类型（自旧工程 `api/type/common` 原样保留，被编排画布多处引用）。 */

export interface KeyValue<K, V> {
  key: K
  value: V
}

export interface Dict<V> {
  [propName: string]: V
}

export interface pageRequest {
  current_page: number
  page_size: number
}
