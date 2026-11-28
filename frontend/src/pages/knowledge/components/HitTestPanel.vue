<template>
  <div class="hit-panel">
    <div class="panel config">
      <div class="cfg-modes">
        <button
          v-for="m in MODES"
          :key="m.value"
          type="button"
          class="mode-card"
          :class="{ on: form.mode === m.value }"
          @click="form.mode = m.value"
        >
          <div class="mc-title">{{ m.label }}</div>
          <div class="mc-desc micro muted">{{ m.desc }}</div>
        </button>
      </div>

      <div class="cfg-params">
        <div class="param">
          <span class="param-label">相似度高于</span>
          <el-input-number v-model="form.threshold" :min="0" :max="1" :step="0.05" :precision="2" size="small" controls-position="right" />
        </div>
        <div class="param">
          <span class="param-label">返回分段数 TopN</span>
          <el-input-number v-model="form.topN" :min="1" :max="20" size="small" controls-position="right" />
        </div>
        <div class="param">
          <span class="param-label">无引用策略</span>
          <el-radio-group v-model="form.noRefStrategy" size="small">
            <!-- ★ value 用机器码（契约 NoRefStrategy）而不是中文标签，否则"打开时不高亮"（#28）。
                 命中测试这一侧后端不解析它，所以症状只有不高亮 —— 但同一处写法在设置页会让保存 400。 -->
            <el-radio-button value="REFUSE">拒答</el-radio-button>
            <el-radio-button value="FREE_ANSWER">自由回答</el-radio-button>
          </el-radio-group>
        </div>
        <div class="param">
          <span class="param-label">查询理解条数</span>
          <el-input-number v-model="form.queryCount" :min="0" :max="5" size="small" controls-position="right" />
          <span class="micro muted">0 = 不做，1 = 只改写</span>
        </div>
        <div class="param">
          <span class="param-label">拆分子问题</span>
          <el-switch v-model="form.decomposeEnabled" size="small" />
        </div>
        <div class="param">
          <span class="param-label">多跳迭代</span>
          <el-switch v-model="form.multiHopEnabled" size="small" />
          <!-- 轮数只在开了之后出现：它只在开了之后有意义，常驻会让"这个数在影响什么"变模糊 -->
          <el-input-number
            v-if="form.multiHopEnabled"
            v-model="form.maxHops"
            :min="1"
            :max="3"
            size="small"
            controls-position="right"
          />
          <span class="micro muted">命中不足时再检一轮（答案跨两段）</span>
        </div>
      </div>

      <!-- ★ 第 11 周（0.3.3）起密级隔离已生效 —— 这句话对应 13 §8.4 的界面口径（M3 起改口径）。
           它不是"顺手加一句提示"：它是一句**可被证伪的声明**，守着它的有两个闸门
           （后端 RetrievalSecurityTest 的判据①②、真栈 check_classification_e2e.py）。
           哪天密级过滤退化了，红的是那两个闸门 —— 而不是靠这句话本身提醒谁。 -->
      <div class="micro muted clearance-hint">
        结果已按你的密级过滤：高于你的密级的内容不会出现；换一个密级更高的账号登录，同一个库能看到更多。
      </div>

      <div class="ask-row">
        <el-input
          v-model="question"
          size="large"
          placeholder="输入测试问题，按回车或点「测试」"
          clearable
          @keyup.enter="runSingle"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button type="primary" size="large" :loading="testing" @click="runSingle">测试</el-button>
        <el-button size="large" :loading="comparing" @click="runCompare">对比模式</el-button>
      </div>
    </div>

    <!-- 单模式结果 -->
    <div v-if="singleResult && !compareResults.length" class="results">
      <div class="res-head">
        <span class="res-title">{{ labelOf(RETRIEVAL_MODE_LABELS, singleResult.mode) }} · 命中 <b class="num">{{ singleResult.items.length }}</b> 条</span>
        <span class="micro muted">耗时 <b class="num">{{ singleResult.elapsedMs }}</b>ms · 阈值 <b class="num">{{ form.threshold }}</b> · TopN <b class="num">{{ form.topN }}</b></span>
      </div>
      <!-- trace 是可选的：老后端/老 mock 不返回它，这一行就整个不出现（不显示占位、不显示 0） -->
      <div v-if="singleResult.trace" class="micro muted trace-line">{{ traceText(singleResult.trace) }}</div>

      <!-- ★ 为什么命中：问句里认出了哪些实体、命中了哪几条关系（0.9.9）。
           没有这一块时，实体扩召回与关系扩召回这两路在界面上完全不可见 ——
           它们进了 RRF，但融合之后只剩段 id，谁被哪一路捞上来无从得知。 -->
      <div v-if="singleResult.detected" class="detected micro">
        <template v-if="singleResult.detected.entities.length">
          <span class="d-label">问句里的实体</span>
          <span
            v-for="e in singleResult.detected.entities"
            :key="e.name"
            class="d-entity"
          >{{ e.name }}<span class="muted"> · {{ entityTypeLabel(e.type) }} · {{ e.mentions }} 段</span></span>
        </template>
        <template v-if="singleResult.detected.relations.length">
          <span class="d-label">关系命中</span>
          <button
            v-for="(r, i) in singleResult.detected.relations"
            :key="i"
            type="button"
            class="d-relation"
            :title="'点开看这条关系是从哪一段读出来的'"
            @click="emit('goto-segment', r.segmentId)"
          >{{ r.from }} —{{ relationLabel(r.type) }}→ {{ r.to }}</button>
        </template>
        <template v-if="singleResult.detected.qaMatches?.length">
          <span class="d-label">问答对命中</span>
          <component
            :is="q.segmentId ? 'button' : 'span'"
            v-for="q in singleResult.detected.qaMatches"
            :key="q.id"
            :type="q.segmentId ? 'button' : undefined"
            class="d-qa"
            :class="{ direct: q.directAnswer }"
            :title="q.directAnswer
              ? '直答型：命中它就直接把它当回答，不再走生成'
              : (q.segmentId ? '点开看它挂在哪一段上' : '没有出处分段：只能作为答案候选')"
            @click="q.segmentId && emit('goto-segment', q.segmentId)"
          >{{ q.question }}<span class="muted"> · {{ q.directAnswer ? '直答' : '召回' }}{{ q.segmentId ? '' : ' · 无出处' }} · {{ q.score.toFixed(2) }}</span></component>
        </template>
        <span v-if="singleResult.detected.note" class="d-note">{{ singleResult.detected.note }}</span>
      </div>
      <div v-if="!singleResult.items.length" class="notice warn">
        没有命中任何分段。可降低相似度阈值、改用混合检索，或确认知识库已启用且分段已向量化。
      </div>
      <div v-else class="hit-grid">
        <div v-for="item in singleResult.items" :key="item.segmentId" class="hit-card panel">
          <div class="hc-head">
            <span class="hc-rank num">{{ item.rank }}</span>
            <span class="hc-title ellipsis">{{ item.title }}</span>
            <span class="grow" />
            <span v-if="item.channels?.length" class="hc-channels">
              <span v-for="c in item.channels" :key="c" class="chan" :class="c">{{ channelLabel(c) }}</span>
            </span>
            <span class="hc-score num">{{ item.score.toFixed(2) }}</span>
          </div>
          <p class="hc-content" data-hit-content>
            <template v-for="(part, i) in highlightParts(item.content, submittedQuery)" :key="i">
              <mark v-if="part.hit" class="hit">{{ part.text }}</mark>
              <span v-else>{{ part.text }}</span>
            </template>
          </p>
          <div class="hc-foot micro muted">
            {{ item.docName }} · {{ item.kbName }}
          </div>
        </div>
      </div>
    </div>

    <!-- 对比模式：三模式并列 -->
    <div v-if="compareResults.length" class="compare">
      <div class="res-head">
        <span class="res-title">三模式对比 · 「{{ question }}」</span>
        <el-button link size="small" @click="compareResults = []">退出对比</el-button>
      </div>
      <div class="compare-grid">
        <div v-for="result in compareResults" :key="result.mode" class="compare-col">
          <div class="cc-head">
            <span class="cc-mode">{{ labelOf(RETRIEVAL_MODE_LABELS, result.mode) }}</span>
            <span class="micro muted"><b class="num">{{ result.items.length }}</b> 条 · <b class="num">{{ result.elapsedMs }}</b>ms</span>
          </div>
          <div v-if="!result.items.length" class="cc-empty micro muted">无命中</div>
          <div
            v-for="item in result.items"
            :key="item.segmentId"
            class="cc-item"
            :class="{ top: item.rank === 1 }"
          >
            <div class="cc-item-head">
              <span class="cc-rank num">{{ item.rank }}</span>
              <span class="ellipsis">{{ item.title }}</span>
              <span class="grow" />
              <span class="cc-score num">{{ item.score.toFixed(2) }}</span>
            </div>
            <p class="cc-item-content" data-hit-content>
              <template v-for="(part, i) in highlightParts(item.content, submittedQuery)" :key="i">
                <mark v-if="part.hit" class="hit">{{ part.text }}</mark>
                <span v-else>{{ part.text }}</span>
              </template>
            </p>
            <div class="micro muted">{{ item.docName }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { retrievalApi } from '@/api'
import { ApiError } from '@/api/types'
import type { HitTestResult, HitTestTrace, RetrievalMode, RetrievalParams } from '@/api/retrieval'
import { RETRIEVAL_MODE_LABELS, labelOf } from '@/constants/labels'
import { highlightParts } from '@/utils/highlight'

const props = defineProps<{ kbId?: string }>()
/** 点关系要能回到原文那一段 —— 与「实体与关系」面板同一个出口（父级切到分段视图）。 */
const emit = defineEmits<{ (e: 'goto-segment', segmentId: string): void }>()

/**
 * 渠道 → 中文。四个渠道对应检索链路里四条真实的召回路径：
 * 向量（ANN）/ 关键词（全文）/ 实体（问题里的实体被提到过的段）/ 关系（实体参与过关系陈述的段）。
 *
 * ★ 不认识的渠道原样显示：后端将来加了一路，界面先显示原值比显示「未知」更有用。
 */
const CHANNEL_LABELS: Record<string, string> = {
  vector: '向量',
  text: '关键词',
  entity: '实体',
  relation: '关系',
  qa: '问答对'
}
const channelLabel = (c: string) => CHANNEL_LABELS[c] ?? c

const ENTITY_TYPE_LABELS: Record<string, string> = { ORG: '组织', PERSON: '人名', DOC: '文档', TERM: '术语' }
const entityTypeLabel = (t: string) => ENTITY_TYPE_LABELS[t] ?? t

const RELATION_LABELS: Record<string, string> = {
  BELONGS_TO: '隶属',
  CONTAINS: '包含',
  PART_OF: '属于',
  RESPONSIBLE_FOR: '负责',
  WORKS_AT: '任职',
  REFERENCES: '依据',
  DEFINES: '定义'
}
const relationLabel = (t: string) => RELATION_LABELS[t] ?? t

const MODES: Array<{ value: RetrievalMode; label: string; desc: string }> = [
  { value: 'VECTOR', label: '向量检索', desc: '按语义相似度召回，适合同义改写' },
  { value: 'FULLTEXT', label: '全文检索', desc: '按关键词精确匹配，适合专有名词' },
  { value: 'HYBRID', label: '混合检索', desc: 'RRF 融合向量与全文，召回更稳（推荐）' }
]

/**
 * 界面上的表单值。查询理解两项在界面上**一定有值**（3 / 开）：
 * 契约里的可选只是「不传时由后端按默认值处理」，而 v-model 不接受 boolean | undefined。
 */
type RetrievalForm = RetrievalParams &
  Required<Pick<RetrievalParams, 'queryCount' | 'decomposeEnabled'>> & {
    /* 多跳两项：契约上是 HitTestRequest 的**可选**字段（不传 = 后端默认：关 / 2），
       而界面上一定有值 —— v-model 不接受 boolean | undefined（与上面两项同一个理由） */
    multiHopEnabled: boolean
    maxHops: number
  }

const form = reactive<RetrievalForm>({
  mode: 'HYBRID',
  threshold: 0.6,
  topN: 5,
  noRefStrategy: 'REFUSE',
  queryCount: 3,
  decomposeEnabled: true,
  multiHopEnabled: false,
  maxHops: 2
})

const question = ref('')
/**
 * 最近一次**实际发出**的查询 —— 命中高亮跟它走，不跟输入框走。
 *
 * ★ 空串表示"还没有结果"：此时 `highlightParts` 返回整段未命中，不会画错。
 */
const submittedQuery = ref('')
const testing = ref(false)
const comparing = ref(false)
const singleResult = ref<HitTestResult | null>(null)
const compareResults = ref<HitTestResult[]>([])

const loadParams = async () => {
  if (!props.kbId) return
  try {
    const params = await retrievalApi.getRetrievalParams(props.kbId)
    form.mode = params.mode
    form.threshold = params.threshold
    form.topN = params.topN
    form.noRefStrategy = params.noRefStrategy
    // 老后端不返回这两个字段：不能直写，undefined 会把输入框清空成空值
    if (params.queryCount !== undefined) form.queryCount = params.queryCount
    if (params.decomposeEnabled !== undefined) form.decomposeEnabled = params.decomposeEnabled
  } catch {
    // 知识库可能刚建好还没有检索参数，用默认值
  }
}

/** 头部那行 trace 文案：降级原因只在真有原因时追加 */
const traceText = (trace: HitTestTrace) =>
  `查询理解 D${trace.degradeLevel} · 子问题 ${trace.subQuestionCount} × 查询 ${trace.queryCount} · 共 ${trace.pathCount} 路` +
  (trace.degradeReason ? ` · 降级原因：${trace.degradeReason}` : '') +
  // ★ 第 14 周：多跳那一层。「轮次 + 停止原因」正是 ROADMAP 第 14 周演示要看的两个数（10 §7.1 的埋点口径）。
  //   老后端不返回这些字段 ⇒ hopRounds 是 undefined ⇒ 整段不出现
  //   （不显示占位、也不显示 0 —— 0 会被读成"跑过但没带来新东西"，与"没这一层"是两件事）
  (trace.hopRounds === undefined
    ? ''
    : ` · 多跳 ${trace.hopRounds} 轮 · 停止：${trace.hopStopReason ?? '（未给出原因）'}` +
      (trace.hopNewSegments ? ` · 新增 ${trace.hopNewSegments} 段` : ''))

const runSingle = async () => {
  if (!question.value.trim()) {
    ElMessage.warning('请输入测试问题')
    return
  }
  testing.value = true
  compareResults.value = []
  try {
    const asked = question.value.trim()
    singleResult.value = await retrievalApi.hitTest({
      ...form,
      kbIds: props.kbId ? [props.kbId] : undefined,
      question: asked
    })
    // ★ 快照"这一次实际发出去的查询"：高亮必须跟结果走，不能跟输入框走 ——
    //   否则用户改一个字还没回车，上一次结果里的高亮就飘了。
    submittedQuery.value = asked
  } catch (error) {
    // P1 失败必须清掉上一次结果 —— 否则旧结果会被当成这次的结论
    singleResult.value = null
    ElMessage.error(error instanceof ApiError ? error.message : '命中测试失败')
  } finally {
    testing.value = false
  }
}

const runCompare = async () => {
  if (!question.value.trim()) {
    ElMessage.warning('请输入测试问题')
    return
  }
  comparing.value = true
  singleResult.value = null
  try {
    const asked = question.value.trim()
    compareResults.value = await retrievalApi.hitTestCompare({
      threshold: form.threshold,
      topN: form.topN,
      noRefStrategy: form.noRefStrategy,
      // 对比模式也要带上查询理解参数，否则同一个表单在两个按钮下检的不是同一件事
      queryCount: form.queryCount,
      decomposeEnabled: form.decomposeEnabled,
      // 多跳同理：它改的是"检第几轮"，不带就等于两个按钮跑的不是同一件事
      multiHopEnabled: form.multiHopEnabled,
      maxHops: form.maxHops,
      kbIds: props.kbId ? [props.kbId] : undefined,
      question: asked
    })
    submittedQuery.value = asked
  } catch (error) {
    // P1 同上：失败清空，不残留上一次对比结果
    compareResults.value = []
    ElMessage.error(error instanceof ApiError ? error.message : '对比测试失败')
  } finally {
    comparing.value = false
  }
}

watch(() => props.kbId, loadParams)

onMounted(loadParams)
</script>

<style scoped>
.hit-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.config {
  padding: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

/* ── 0.9.9：为什么命中 ─────────────────────────────────────────
   ★ 只被「关系」路捞上来的那一条，现在一眼能看出来（以前它与「向量命中」长得一模一样）。 */
.detected {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-xs);
  margin: var(--space-xs) 0 var(--space-sm);
}
.d-label {
  color: var(--color-muted);
}
.d-entity {
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--color-warn-soft);
  color: var(--color-warn);
  border: 1px solid var(--color-warn);
}
.d-relation {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--color-ok-soft);
  color: var(--color-ok);
  border: 1px solid var(--color-ok);
  font: inherit;
  cursor: pointer;
}
.d-relation:hover {
  text-decoration: underline;
}
/* 问答对命中：召回型与直答型要一眼分开 —— 前者只是候选，后者会把生成整个跳过 */
.d-qa {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--color-surface-2);
  color: var(--color-ink);
  border: 1px solid var(--color-rule);
  font: inherit;
  cursor: default;
}
.d-qa.direct {
  background: var(--color-warn-soft);
  color: var(--color-warn);
  border-color: var(--color-warn);
}
button.d-qa {
  cursor: pointer;
}
button.d-qa:hover {
  text-decoration: underline;
}
.d-note {
  color: var(--color-muted);
}
.hc-channels {
  display: inline-flex;
  flex-wrap: nowrap;
  gap: 4px;
  margin-right: var(--space-xs);
}
.chan {
  padding: 0 6px;
  border-radius: 999px;
  font-size: var(--text-2xs);
  line-height: 1.6;
  /* ★ 不许换行：flex 行里被压缩时「向量」会竖成两行（两个字一列），比不显示还难读 */
  white-space: nowrap;
  flex: 0 0 auto;
  border: 1px solid var(--color-rule);
  color: var(--color-ink-2);
  background: var(--color-surface-2);
}
.chan.entity {
  border-color: var(--color-warn);
  color: var(--color-warn);
  background: var(--color-warn-soft);
}
.chan.relation {
  border-color: var(--color-ok);
  color: var(--color-ok);
  background: var(--color-ok-soft);
}

.cfg-modes {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-xs);
}

.mode-card {
  padding: var(--space-sm) var(--space-md);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  background: var(--color-surface);
  cursor: pointer;
  text-align: left;
  font-family: inherit;
  transition:
    border-color var(--dur-micro) var(--ease-out),
    background var(--dur-micro) var(--ease-out);
}

.mode-card:hover {
  border-color: var(--color-rule-strong);
  background: var(--color-hover);
}

.mode-card.on {
  border-color: var(--color-accent-line);
  background: var(--color-accent-soft);
  box-shadow: inset 2px 0 0 var(--color-accent);
}

.mc-title {
  font-size: var(--text-sm);
  font-weight: 500;
  line-height: var(--leading-tight);
  color: var(--color-ink);
  margin-bottom: var(--space-3xs);
}

.mode-card.on .mc-title {
  color: var(--color-accent);
}

.mc-desc {
  line-height: var(--leading-tight);
}

.cfg-params {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-lg);
  align-items: center;
}

.param {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.param-label {
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  font-weight: 500;
  letter-spacing: var(--tracking-label);
  text-transform: uppercase;
  color: var(--color-muted);
}

.ask-row {
  display: flex;
  gap: var(--space-xs);
  align-items: center;
}

.ask-row .el-input {
  flex: 1;
}

.results,
.compare {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.res-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  padding-bottom: var(--space-xs);
  border-bottom: 1px solid var(--color-rule);
}

.res-title {
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--color-ink);
  line-height: var(--leading-tight);
}

.res-title .num,
.res-head .num {
  font-size: var(--text-2xs);
  font-variant-numeric: tabular-nums;
  color: var(--color-ink-2);
}

.trace-line {
  line-height: var(--leading-tight);
}

.hit-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: var(--space-sm);
}

.hit-card {
  padding: var(--space-sm) var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.hc-head {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.hc-rank {
  flex-shrink: 0;
  min-width: 1.5em;
  font-size: var(--text-2xs);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  color: var(--color-muted);
  text-align: right;
}

.hc-title {
  font-size: var(--text-sm);
  font-weight: 500;
  line-height: var(--leading-tight);
  color: var(--color-ink);
  min-width: 0;
}

.hc-score {
  flex-shrink: 0;
  min-width: 2.75em;
  text-align: right;
  font-size: var(--text-sm);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  color: var(--color-accent);
}

.hc-content {
  margin: 0;
  font-size: var(--text-2xs);
  color: var(--color-ink-2);
  line-height: var(--leading-body);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  flex: 1;
}

/* 命中片段：只加底色与字重，不加下划线 —— 连续多段命中时下划线会连成一条横线，
   看起来像链接而不是"这几截是命中的"。底色用 warn-soft：它在浅色主题里对比度最低，
   高亮 10 段也不会让整段读不下去。 */
.hc-content .hit,
.cc-item-content .hit {
  background: var(--color-warn-soft);
  color: inherit;
  font-weight: 600;
  border-radius: 2px;
  padding: 0 1px;
}

.hc-foot {
  padding-top: var(--space-xs);
  border-top: 1px solid var(--color-rule-2);
  line-height: var(--leading-tight);
}

.compare-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-sm);
}

.compare-col {
  background: var(--color-surface);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-lg);
  padding: var(--space-sm);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.cc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-xs);
  padding-bottom: var(--space-xs);
  border-bottom: 1px solid var(--color-rule-2);
}

.cc-mode {
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--color-ink);
  line-height: var(--leading-tight);
}

.cc-empty {
  padding: var(--space-lg) 0;
  text-align: center;
}

.cc-item {
  padding: var(--space-xs) var(--space-sm);
  background: var(--color-sunken);
  border-radius: var(--radius);
  border-left: 2px solid transparent;
  transition:
    background var(--dur-micro) var(--ease-out),
    border-color var(--dur-micro) var(--ease-out);
}

.cc-item.top {
  border-left-color: var(--color-accent);
  background: var(--color-accent-soft);
}

.cc-item-head {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
  font-size: var(--text-2xs);
  font-weight: 500;
  line-height: var(--leading-tight);
  margin-bottom: var(--space-2xs);
}

.cc-rank {
  flex-shrink: 0;
  min-width: 1.5em;
  font-variant-numeric: tabular-nums;
  color: var(--color-muted);
  text-align: right;
}

.cc-item.top .cc-rank {
  color: var(--color-accent);
}

.cc-score {
  flex-shrink: 0;
  min-width: 2.75em;
  text-align: right;
  font-variant-numeric: tabular-nums;
  color: var(--color-ink);
}

.cc-item.top .cc-score {
  color: var(--color-accent);
}

.cc-item-content {
  margin: 0 0 var(--space-2xs);
  font-size: var(--text-2xs);
  color: var(--color-ink-2);
  line-height: var(--leading-body);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
