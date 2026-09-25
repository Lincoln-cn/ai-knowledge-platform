<template>
  <div class="echarts-rander">
    <div ref="chartEl" class="charts-container" :style="chartStyle" />
    <p v-if="errorMessage" class="chart-err">{{ errorMessage }}</p>
  </div>
</template>
<script lang="ts" setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

/**
 * ECharts 渲染。只接受 JSON option。
 * 旧版支持 actionType=EVAL + eval() 构造 option —— 已删除。
 */
defineOptions({ name: 'EchartsRander' })

const props = defineProps<{
  /** JSON 字符串或已解析对象：ECharts option，或 { style?, option } */
  option: string | Record<string, any>
}>()

const chartEl = ref<HTMLDivElement>()
const chartStyle = ref<Record<string, string>>({ height: '220px', width: '100%' })
const errorMessage = ref('')
let chart: echarts.ECharts | null = null

/**
 * 令牌探针 —— echarts/zrender 不认 oklch()，
 * 直接 getPropertyValue 拿到的是 oklch(…) 字符串，喂进去会静默空白。
 * 用一个临时元素让浏览器把 oklch 解析成 rgb() 再读回来。
 */
function resolveColor(token: string): string {
  const probe = document.createElement('span')
  probe.style.color = getComputedStyle(document.documentElement).getPropertyValue(token)
  document.body.appendChild(probe)
  const rgb = getComputedStyle(probe).color
  probe.remove()
  return rgb
}

let cachedTheme: Record<string, any> | null = null
function echartsTheme(): Record<string, any> {
  if (!cachedTheme) {
    const t = {
      accent: resolveColor('--color-accent'),
      accentHover: resolveColor('--color-accent-hover'),
      ok: resolveColor('--color-ok'),
      warn: resolveColor('--color-warn'),
      danger: resolveColor('--color-danger'),
      ink: resolveColor('--color-ink'),
      ink2: resolveColor('--color-ink-2'),
      muted: resolveColor('--color-muted'),
      rule: resolveColor('--color-rule'),
      rule2: resolveColor('--color-rule-2'),
      surface: resolveColor('--color-surface')
    }
    cachedTheme = {
      color: [t.accent, t.ok, t.warn, t.danger, t.muted, t.accentHover],
      textStyle: { color: t.ink2 },
      legend: { textStyle: { color: t.muted } },
      tooltip: {
        backgroundColor: t.surface,
        borderColor: t.rule,
        textStyle: { color: t.ink }
      },
      categoryAxis: {
        axisLine: { lineStyle: { color: t.rule } },
        axisTick: { lineStyle: { color: t.rule } },
        axisLabel: { color: t.muted },
        splitLine: { lineStyle: { color: t.rule2 } }
      },
      valueAxis: {
        axisLine: { lineStyle: { color: t.rule } },
        axisTick: { lineStyle: { color: t.rule } },
        axisLabel: { color: t.muted },
        splitLine: { lineStyle: { color: t.rule2 } }
      }
    }
  }
  return cachedTheme
}

function parseOption(raw: string | Record<string, any>): Record<string, any> | null {
  let obj: Record<string, any>
  if (typeof raw === 'string') {
    try {
      obj = JSON.parse(raw)
    } catch {
      errorMessage.value = '图表 option 不是合法 JSON'
      return null
    }
  } else {
    obj = raw || {}
  }
  if (obj && obj.actionType === 'EVAL') {
    errorMessage.value = '不支持 EVAL 型图表 option（已移除 eval）'
    return null
  }
  if (obj && obj.style && typeof obj.style === 'object') {
    chartStyle.value = { ...chartStyle.value, ...obj.style }
  }
  if (obj && obj.option && typeof obj.option === 'object') {
    return obj.option
  }
  return obj
}

function render() {
  if (!chartEl.value) return
  if (!chart) chart = echarts.init(chartEl.value, echartsTheme())
  const option = parseOption(props.option)
  if (!option) return
  errorMessage.value = ''
  chart.setOption(option, true)
}

function resize() {
  chart?.resize()
}

watch(() => props.option, render, { deep: true })

onMounted(() => {
  render()
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})
</script>
<style scoped>
.charts-container {
  overflow-x: auto;
  min-height: var(--space-xl);
}

.chart-err {
  margin: var(--space-xs) 0 0;
  color: var(--color-danger);
  font-size: var(--text-2xs);
}
</style>
