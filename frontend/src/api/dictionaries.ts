import { request } from '@/lib/http'

/* ────────────────────────── 契约类型 ────────────────────────── */

/**
 * 字典种类（08 §十）。
 *
 * ★ 取值是 SCREAMING_SNAKE 机器码，与后端 `DictionaryKind` 枚举逐一对应
 * （标识符 = 线上值 = 库存值，展示文案只出现在 label 里）。
 *
 * 种类是**代码里要 switch 的东西**（每一类有各自的规则与消费者），所以它是枚举而不是
 * "又一张字典表" —— "种类"本身没人会去加，做成数据只是把编译期错误挪到运行期。
 */
export type DictionaryKind = 'CLASSIFICATION' | 'DOC_TYPE' | 'PROMPT_TYPE' | 'KB_CATEGORY'

/**
 * 一条字典项。
 *
 * ★ **业务键是 `(kind, code)`** —— 契约里没有行 id，改与停用也按它定位。
 * 少一个 id 就少一处"拿 id 当业务键"的机会。
 */
export interface DictionaryItem {
  kind: DictionaryKind
  /** 机器码。★ 已经写进历史数据（`kb_document.doc_type`）与召回前缀，所以它不可改 */
  code: string
  /** 展示文案。可随时改，不进库也不进前缀 —— 所以改它不触发重新向量化 */
  label: string
  /** 下拉顺序。密级那一类的顺序由库里生成列固化，见 createDictionary */
  sort: number
  /** 停用 = 不再出现在新的下拉里；既有数据照旧可读 */
  enabled: boolean
  remark: string | null
}

export interface CreateDictionaryBody {
  kind: DictionaryKind
  code: string
  label: string
  /** 不传 = 排到本类末尾（后端取当前最大 sort + 1，不占位也不留空洞） */
  sort?: number
  remark?: string
}

/**
 * 改一项。★ 契约是 **Partial**：不传的字段保持原值（后端按 `coalesce` 落库）。
 *
 * 所以 `code` 在这里只是**定位键**，不是可改字段 —— 它已经写进历史数据与召回前缀，
 * 改了等于让既有数据找不到展示名。要"换名字"就改 `label`，那正是文案与机器码分开的意义。
 */
export interface UpdateDictionaryBody {
  kind: DictionaryKind
  code: string
  label?: string
  sort?: number
  enabled?: boolean
  remark?: string
}

/* ────────────────────────── 接口 ────────────────────────── */

/**
 * 取某一类字典项。
 *
 * ★ 读**登录即可**：建档/上传/筛选的下拉都要读它，把读也卡上管理权限，
 * 等于让所有人点上传对话框时撞 403。
 *
 * @param includeDisabled 维护页传 true（否则看不到自己停用过什么）；消费方的下拉不传，只拿启用的
 */
export const listDictionaries = (kind: DictionaryKind, includeDisabled = false) =>
  request<DictionaryItem[]>({
    method: 'GET',
    url: '/dictionaries',
    query: { kind, includeDisabled }
  })

/**
 * 新增一项。需要 `platform:dict:manage`。
 *
 * ★ 密级那一类后端会**明确拒绝**（它是排序比较轴，档位写死在 `classification_rank` 生成列里），
 * 前端因此不提供密级的新增入口。这里不复制一份"密级不许新增"的前端校验 ——
 * 同一条规则写两处，迟早漂移成"界面拦了但接口能过"或反过来。
 */
export const createDictionary = (body: CreateDictionaryBody) =>
  request<DictionaryItem>({ method: 'POST', url: '/dictionaries', body })

/**
 * 改 / 停用一项（按 `(kind, code)` 定位）。需要 `platform:dict:manage`。
 *
 * ★ 与之配套的是**没有** DELETE：字典项只停用不删除。`code` 一旦写进历史数据，
 * 删掉会让那些数据的展示回落成机器码而**没有任何地方报错** ——
 * `enabled=false` 才表达得出"新数据不要再选它，既有数据照旧可读"。
 */
export const updateDictionary = (body: UpdateDictionaryBody) =>
  request<DictionaryItem>({ method: 'PUT', url: '/dictionaries', body })
