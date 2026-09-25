<template>
  <div class="global-progress panel" :class="{ running: progress.running }">
    <div class="gp-head">
      <span class="gp-title">
        <el-icon v-if="progress.running" class="is-loading"><Loading /></el-icon>
        <el-icon v-else-if="progress.failed"><WarningFilled /></el-icon>
        <el-icon v-else><CircleCheckFilled /></el-icon>
        {{ headline }}
      </span>
      <span class="micro muted">
        已完成 <b class="num">{{ progress.done }}</b> / {{ progress.total }}
        <template v-if="progress.failed"> · 失败 {{ progress.failed }}</template>
      </span>
    </div>

    <div class="gp-bar">
      <div class="gp-fill" :style="{ width: percent + '%' }" />
    </div>

    <div class="gp-stages micro">
      <span v-for="s in visibleStages" :key="s.status" class="gp-stage">
        <i class="dot" :class="STAGE_DOT[s.status]" />
        {{ s.status }}
        <b class="num">{{ s.count }}</b>
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { CircleCheckFilled, Loading, WarningFilled } from '@element-plus/icons-vue'
import type { DocumentStatus, IngestionProgress } from '@/api/ingestion'

const props = defineProps<{ progress: IngestionProgress }>()

const STAGE_DOT: Record<DocumentStatus, string> = {
  待解析: '',
  分段中: 'warn',
  待向量化: 'warn',
  向量化中: 'warn',
  成功: 'ok',
  分段失败: 'err',
  向量化失败: 'err'
}

const percent = computed(() => {
  if (!props.progress.total) return 0
  return Math.round((props.progress.done / props.progress.total) * 100)
})

const runningCount = computed(() =>
  props.progress.stages
    .filter((s) => s.status === '待解析' || s.status === '分段中' || s.status === '向量化中')
    .reduce((sum, s) => sum + s.count, 0)
)

const headline = computed(() => {
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
