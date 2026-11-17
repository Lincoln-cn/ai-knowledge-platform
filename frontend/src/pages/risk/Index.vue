<template>
  <div class="page">
    <PageHeader
      title="风险提示"
      desc="磁盘、内存、慢回答、失败回答的即时读数 —— 只读体检，不发通知"
    >
      <template #actions>
        <span v-if="snapshot" class="micro muted">采集于 {{ formatTime(snapshot.at) }}</span>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="load">重新体检</el-button>
      </template>
    </PageHeader>

    <div v-if="error" class="notice err">
      <b>体检失败：</b>{{ error }}
      <el-button size="small" type="primary" plain class="retry" @click="load">重试</el-button>
    </div>

    <div v-loading="loading" class="grid">
      <div v-for="item in snapshot?.items ?? []" :key="item.key" class="card" :class="toneOf(item.level)">
        <div class="card-head">
          <span class="card-label">{{ item.label }}</span>
          <span class="dot" />
        </div>
        <div class="card-value num">{{ item.value }}</div>
        <div class="card-detail micro">{{ item.detail }}</div>
        <div v-if="item.hint" class="card-hint">{{ item.hint }}</div>
      </div>
    </div>

    <p class="foot micro muted">
      ★ 这一页<b>不是告警</b>：不推送、不落历史、不判 SLA。真正的阈值告警要有时序库、通知渠道与静默期，
      记在 <code>#73</code> 里。所以「这里全绿」只说明刚才那一刻没异常，不说明过去没出事。
    </p>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { opsZoneApi } from '@/api'
import type { RiskSnapshot } from '@/api/ops-zone'

const snapshot = ref<RiskSnapshot | null>(null)
const loading = ref(false)
const error = ref('')

/** 等级 → 卡片色调。`UNKNOWN` 单独一档：读不出来既不是"没事"也不是"出事"。 */
const toneOf = (level: string) =>
  level === 'CRITICAL' ? 'crit' : level === 'WARN' ? 'warn' : level === 'UNKNOWN' ? 'unknown' : 'ok'

const formatTime = (iso: string) => {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN')
}

async function load() {
  loading.value = true
  try {
    snapshot.value = await opsZoneApi.getRiskSnapshot()
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.notice {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-md);
  font-size: 13px;
  margin-bottom: var(--space-md);
}
.notice.err {
  background: var(--color-danger-bg, #fef2f2);
  color: var(--color-danger, #b91c1c);
}
.retry {
  margin-left: auto;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: var(--space-md);
}
.card {
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-elevated);
  border-left-width: 3px;
}
.card.ok {
  border-left-color: var(--color-success, #15803d);
}
.card.warn {
  border-left-color: var(--color-warning, #b45309);
}
.card.crit {
  border-left-color: var(--color-danger, #b91c1c);
}
.card.unknown {
  border-left-color: var(--color-border-strong, #9ca3af);
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-label {
  font-size: 13px;
  color: var(--color-text-secondary);
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-success, #15803d);
}
.card.warn .dot {
  background: var(--color-warning, #b45309);
}
.card.crit .dot {
  background: var(--color-danger, #b91c1c);
}
.card.unknown .dot {
  background: var(--color-border-strong, #9ca3af);
}
.card-value {
  margin-top: var(--space-xs);
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
}
.card-detail {
  margin-top: var(--space-xs);
  color: var(--color-text-secondary);
  line-height: 1.6;
}
.card-hint {
  margin-top: var(--space-sm);
  padding-top: var(--space-sm);
  border-top: 1px solid var(--color-border);
  font-size: 12px;
  line-height: 1.6;
  color: var(--color-warning, #b45309);
}
.card.crit .card-hint {
  color: var(--color-danger, #b91c1c);
}
.foot {
  margin-top: var(--space-md);
  line-height: 1.6;
}
</style>
