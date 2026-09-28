<template>
  <div class="global-progress panel" :class="{ running: progress.running, failed: !!loadError }">
    <div class="gp-head">
      <span class="gp-title">
        <el-icon v-if="loadError" class="is-fail"><WarningFilled /></el-icon>
        <el-icon v-else-if="progress.running" class="is-loading"><Loading /></el-icon>
        <el-icon v-else-if="progress.failed"><WarningFilled /></el-icon>
        <el-icon v-else><CircleCheckFilled /></el-icon>
        {{ headline }}
      </span>
      <span v-if="!loadError" class="micro muted">
        已完成 <b class="num">{{ progress.done }}</b> / {{ progress.total }}
        <template v-if="progress.failed"> · 失败 {{ progress.failed }}</template>
      </span>
      <el-button v-else link size="small" type="primary" @click="emit('retry')">重试</el-button>
    </div>

    <div v-if="!loadError" class="gp-bar">
      <div class="gp-fill" :style="{ width: percent + '%' }" />
    </div>

    <div v-if="!loadError" class="gp-stages micro">
      <span v-for="s in visibleStages" :key="s.status" class="gp-stage">
        <i class="dot" :class="STAGE_DOT[s.status]" />
        {{ labelOf(DOCUMENT_STATUS_LABELS, s.status) }}
        <b class="num">{{ s.count }}</b>
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { CircleCheckFilled, Loading, WarningFilled } from '@element-plus/icons-vue'
import type { DocumentStatus, IngestionProgress } from '@/api/ingestion'
import { DOCUMENT_STATUS_LABELS, labelOf } from '@/constants/labels'

const props = defineProps<{
  progress: IngestionProgress
  /** P1 进度接口失败时的可读原因。有它就走错误态，不许伪装成「暂无文档」 */
  loadError?: string | null
}>()

const emit = defineEmits<{ retry: [] }>()

const STAGE_DOT: Record<DocumentStatus, string> = {
  PENDING_PARSE: '',
  SEGMENTING: 'warn',
  PENDING_EMBEDDING: 'warn',
  EMBEDDING: 'warn',
  SUCCEEDED: 'ok',
  SEGMENT_FAILED: 'err',
  EMBEDDING_FAILED: 'err'
}

const percent = computed(() => {
  if (!props.progress.total) return 0
  return Math.round((props.progress.done / props.progress.total) * 100)
})

const runningCount = computed(() =>
  props.progress.stages
    .filter((s) => s.status === 'PENDING_PARSE' || s.status === 'SEGMENTING' || s.status === 'EMBEDDING')
    .reduce((sum, s) => sum + s.count, 0)
)

const headline = computed(() => {
  // 错误态优先于空态 —— 这是 P1 点名的"加载失败被显示成暂无文档"
  if (props.loadError) return '进度加载失败'
  if (props.progress.running) return `正在处理 ${runningCount.value} 个文档`
  if (props.progress.failed && !props.progress.done && props.progress.total) return '处理中断，有文档失败'
  if (!props.progress.total) return '暂无文档'
  return '全部处理完成'
})

/** 只显示有计数的阶段，避免一排 0 刷屏 */
const visibleStages = computed(() => props.progress.stages.filter((s) => s.count > 0))
</script>

<style scoped>
.global-progress {
  padding: var(--space-sm) var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.global-progress.running {
  border-color: var(--color-accent-line);
}

.global-progress.failed {
  border-color: color-mix(in oklch, var(--color-danger) 30%, var(--color-rule));
}

.gp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
}

.gp-title {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  font-size: var(--text-sm);
  font-weight: 500;
  line-height: var(--leading-tight);
  color: var(--color-ink);
}

.gp-title .is-fail {
  color: var(--color-danger);
}

.gp-head .num {
  font-size: var(--text-2xs);
  font-variant-numeric: tabular-nums;
  color: var(--color-ink-2);
}

.gp-bar {
  height: 4px;
  border-radius: var(--radius-pill);
  background: var(--color-sunken);
  overflow: hidden;
}

.gp-fill {
  height: 100%;
  border-radius: var(--radius-pill);
  background: var(--color-accent);
  transition: width var(--dur-short) var(--ease-out);
}

.gp-stages {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  line-height: var(--leading-tight);
}

.gp-stage {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
}

.gp-stage .num {
  font-size: var(--text-2xs);
  font-variant-numeric: tabular-nums;
}
</style>
