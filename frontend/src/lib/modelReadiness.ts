/**
 * 「模型够不够用」的单一判据 —— 工作台引导条的入口条件（`ROADMAP §五` F2 的第一步）。
 *
 * <h3>为什么要有这一层</h3>
 * <p>「没有可用模型」这句话此前**只散落在局部**：建库向导筛 embedding 候选一处
 * （`pages/knowledge/components/CreateWizard.vue:502`）、应用发布前置检查一处
 * （`mock/handlers/assistants.ts:89`）、编排节点校验一处（`pages/workflow/lib/validate.ts:93`）。
 * 每一处都只看自己那一个动作，于是**没有一个地方回答「这个平台现在能不能干活」**。
 * 全新用户进来看到的是一个信息密度很高的工作台，却不知道第一步缺什么。
 *
 * <p>本文件把这个判断收敛成一份，供工作台引导复用；`CreateWizard` 也改用它，
 * 免得两处口径慢慢漂开（`#40` 的教训：同一状态四处各写一份，漏改一处就是假绿）。
 *
 * <h3>★ 两个用途的「能用」不是同一个条件</h3>
 * <ul>
 *   <li><b>对话</b>：必须 {@code LOADED}。发布前置检查就是这么判的
 *       （`mock/handlers/assistants.ts:89`：`model.status === 'LOADED'`），
 *       而且几十亿参数的模型加载是分钟级 —— 不能把它藏进一次提问里。</li>
 *   <li><b>向量化</b>：{@code LOADED} **或** {@code NOT_LOADED}。本地 ONNX 是首次调用才加载
 *       （`RoutingEmbeddingAdapter` 里 `ready()` 为假就触发加载），
 *       所以「登记了但还没预热」是**可用**状态。这条与 `CreateWizard.vue:502`
 *       的既有取法一致 —— 这里把它写下来，是因为它长得像 bug。
 *       ★ 别为了「整齐」把两者统一成一个条件：那会让建库候选凭空变空，
 *       或者让工作台对着一个永远加载不完的对话模型说「可以用」。</li>
 * </ul>
 *
 * <h3>零依赖</h3>
 * <p>只认 {@code kind} 与 {@code status} 两个字段，不引 `@/api/models` ——
 * 与 `lib/degrade.ts` 同一个理由：判据要能脱离契约层被单测直接跑。
 */

/** 两个用途。名字与 `ModelKind` 的小写形式对应，便于阅读引导文案 */
export type ModelUse = 'chat' | 'embedding'

/**
 * 判定只需要这两个字段。
 * ★ 用字面量联合而不是引契约类型：契约一旦改了 `ModelStatus`，
 * 这里会以「类型不兼容」的方式报出来，而不是悄悄放宽。
 */
export interface ModelLike {
  kind: 'CHAT' | 'EMBEDDING'
  status: 'NOT_LOADED' | 'LOADING' | 'LOADED' | 'LOAD_FAILED'
}

export interface ModelReadiness {
  /** 该用途已有可用的模型 */
  ready: ModelUse[]
  /** 该用途有模型正在加载 —— 现在用不了，但不是「缺」 */
  loading: ModelUse[]
  /** 该用途一条可用的都没有，也没有在加载的 */
  missing: ModelUse[]
}

/** 对话模型能用 = 已加载。见文件头「两个用途的「能用」不是同一个条件」 */
export function isUsableChatModel(model: ModelLike): boolean {
  return model.kind === 'CHAT' && model.status === 'LOADED'
}

/**
 * 向量模型能用 = 已加载**或**登记了尚未预热（本地 ONNX 首次调用懒加载）。
 * ★ 判据出处：`pages/knowledge/components/CreateWizard.vue:502`。
 * `LOAD_FAILED` 与 `LOADING` 都不算 —— 前者要人去看原因，后者还没轮到能用。
 */
export function isUsableEmbeddingModel(model: ModelLike): boolean {
  return model.kind === 'EMBEDDING' && (model.status === 'LOADED' || model.status === 'NOT_LOADED')
}

/**
 * 盘点「这个平台能不能干活」。
 *
 * 优先级：`ready` > `loading` > `missing`。
 * ★ `loading` 单独一档不是啰嗦：一个 30B 模型正在加载时提示「你还没有模型」，
 * 用户会去新建第二条，然后两条一起抢显存 —— 这是真实会发生的操作。
 *
 * @param models 已登记的模型（只用到 `kind` / `status`）
 */
export function assessModelReadiness(models: readonly ModelLike[]): ModelReadiness {
  const result: ModelReadiness = { ready: [], loading: [], missing: [] }
  const uses: ModelUse[] = ['chat', 'embedding']

  for (const use of uses) {
    const kind: ModelLike['kind'] = use === 'chat' ? 'CHAT' : 'EMBEDDING'
    const ofKind = models.filter((m) => m.kind === kind)
    const usable = use === 'chat' ? isUsableChatModel : isUsableEmbeddingModel

    if (ofKind.some(usable)) {
      result.ready.push(use)
    } else if (ofKind.some((m) => m.status === 'LOADING')) {
      result.loading.push(use)
    } else {
      result.missing.push(use)
    }
  }
  return result
}

/** 引导条的语气。`warn` = 现在干不了活，`info` = 等一下就好 */
export type ModelReadinessTone = 'warn' | 'info'

export interface ModelReadinessNotice {
  tone: ModelReadinessTone
  /** 一句话说清「缺什么 / 会怎样」，链接文字在组件里拼（这里不管导航） */
  text: string
}

const USE_LABEL: Record<ModelUse, string> = { chat: '对话', embedding: '向量' }

/** 「缺了会怎样」按用途分开写 —— 对话缺了提问失败，向量缺了是建库/入库失败 */
const MISSING_CONSEQUENCE: Record<ModelUse, string> = {
  chat: '现在提问会失败',
  embedding: '建知识库、传文档会失败'
}

/**
 * 工作台引导条：**该不该显示、显示哪一句**。
 *
 * <p>抽成纯函数而不是写进模板 `v-if`，是为了它能被 `node --test` 直接钉住 ——
 * 这条引导最容易漏的不是文案，而是**两个"不该显示"的理由**：</p>
 * <ul>
 *   <li><b>拉取失败（`readiness === null`）不显示</b>。本工程的通则：没测到显示 `—`，
 *       不显示 0。「不知道有没有模型」≠「没有模型」，后者会让一个健康平台顶着
 *       一条假的故障提示 —— 那是 `silent-failure` 的镜像（假警报同样让人关掉提示）。</li>
 *   <li><b>用户修不了（`canFix === false`）不显示</b>。`canFix` 由调用方算
 *       （`canReach(router,'/models') && session.can(PERM.modelManage)`），
 *       这一层不 import router/session 才能保持零依赖。理由同工作台既有注释：
 *       **用户看到通向 403 的入口，比没有入口更坏**。</li>
 * </ul>
 *
 * <p>★ 第三档是「正在加载」：缺与不缺之间还有一段时间，此时**既不能说缺**
 * （会引导用户建第二条，两条抢显存），**也不能什么都不说**（用户提问失败却
 * 没有任何提示）。所以给它一句 `info`。</p>
 */
export function modelReadinessNotice(
  readiness: ModelReadiness | null,
  canFix: boolean
): ModelReadinessNotice | null {
  if (!readiness || !canFix) return null

  if (readiness.missing.length >= 2) {
    return {
      tone: 'warn',
      text: '还没有可用模型 —— 对话模型与向量模型都要先在模型管理里登记，否则问答和知识库都用不了'
    }
  }

  const firstMissing = readiness.missing[0]
  if (firstMissing) {
    return {
      tone: 'warn',
      text: `还没有可用的${USE_LABEL[firstMissing]}模型 —— ${MISSING_CONSEQUENCE[firstMissing]}`
    }
  }

  if (readiness.loading.length > 0) {
    const names = readiness.loading.map((use) => `${USE_LABEL[use]}模型`).join('、')
    return { tone: 'info', text: `${names}正在加载，加载完就能用（大模型可能要几分钟）` }
  }

  return null
}
