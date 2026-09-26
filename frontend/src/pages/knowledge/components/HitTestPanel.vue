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
            <el-radio-button value="拒答">拒答</el-radio-button>
            <el-radio-button value="自由回答">自由回答</el-radio-button>
          </el-radio-group>
        </div>
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
        <span class="res-title">{{ singleResult.mode }} · 命中 <b class="num">{{ singleResult.items.length }}</b> 条</span>
        <span class="micro muted">耗时 <b class="num">{{ singleResult.elapsedMs }}</b>ms · 阈值 <b class="num">{{ form.threshold }}</b> · TopN <b class="num">{{ form.topN }}</b></span>
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
            <span class="hc-score num">{{ item.score.toFixed(2) }}</span>
          </div>
          <p class="hc-content">{{ item.content }}</p>
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
            <span class="cc-mode">{{ result.mode }}</span>
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
            <p class="cc-item-content">{{ item.content }}</p>
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
import type { HitTestResult, RetrievalMode, RetrievalParams } from '@/api/retrieval'

const props = defineProps<{ kbId?: string }>()

const MODES: Array<{ value: RetrievalMode; label: string; desc: string }> = [
  { value: '向量检索', label: '向量检索', desc: '按语义相似度召回，适合同义改写' },
  { value: '全文检索', label: '全文检索', desc: '按关键词精确匹配，适合专有名词' },
  { value: '混合检索', label: '混合检索', desc: 'RRF 融合向量与全文，召回更稳（推荐）' }
]

const form = reactive<RetrievalParams>({
  mode: '混合检索',
  threshold: 0.6,
  topN: 5,
  noRefStrategy: '拒答'
})

const question = ref('')
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
  } catch {
    // 知识库可能刚建好还没有检索参数，用默认值
  }
}

const runSingle = async () => {
  if (!question.value.trim()) {
    ElMessage.warning('请输入测试问题')
    return
  }
  testing.value = true
  compareResults.value = []
  try {
    singleResult.value = await retrievalApi.hitTest({
      ...form,
      kbIds: props.kbId ? [props.kbId] : undefined,
      question: question.value.trim()
    })
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
    compareResults.value = await retrievalApi.hitTestCompare({
      threshold: form.threshold,
      topN: form.topN,
      noRefStrategy: form.noRefStrategy,
      kbIds: props.kbId ? [props.kbId] : undefined,
      question: question.value.trim()
    })
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
