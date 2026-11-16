<template>
  <div ref="el" class="guard-chart" />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { GuardTrendPoint } from '@/api/guard'

const props = defineProps<{ points: GuardTrendPoint[] }>()

const el = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

/** 令牌是 oklch，echarts 只认 rgb/hex ⇒ 用一次性探针取浏览器算出来的值 */
const cssColor = (token: string): string => {
  const probe = document.createElement('span')
  probe.style.color = `var(${token})`
  document.documentElement.appendChild(probe)
  const resolved = getComputedStyle(probe).color
  probe.remove()
  return resolved
}

const cssVar = (token: string): string =>
  getComputedStyle(document.documentElement).getPropertyValue(token).trim()

const render = () => {
  if (!chart) return
  const accent = cssColor('--color-accent')
  const rule = cssColor('--color-rule')
  const rule2 = cssColor('--color-rule-2')
  const muted = cssColor('--color-muted')
  const ink = cssColor('--color-ink')
  const surface = cssColor('--color-surface')
  const fontBody = cssVar('--font-body')

  chart.setOption(
    {
      color: [accent],
      textStyle: { fontFamily: fontBody },
      tooltip: {
        trigger: 'axis',
        backgroundColor: surface,
        borderColor: rule,
        borderWidth: 1,
        padding: [8, 12],
        textStyle: { color: ink, fontFamily: fontBody, fontSize: 12 },
        axisPointer: { lineStyle: { color: rule2 } }
      },
      grid: { left: 8, right: 12, top: 16, bottom: 4, containLabel: true },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        axisLine: { lineStyle: { color: rule } },
        axisTick: { lineStyle: { color: rule } },
        axisLabel: { color: muted, fontSize: 12, fontFamily: fontBody },
        data: props.points.map((p) => p.day.slice(5))
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        name: '拦截',
        nameTextStyle: { color: muted, fontSize: 12, fontFamily: fontBody },
        axisLabel: { color: muted, fontSize: 12, fontFamily: fontBody },
        axisLine: { show: false },
        splitLine: { lineStyle: { color: rule2 } }
      },
      series: [
        {
          name: '拦截次数',
          type: 'line',
          smooth: true,
          showSymbol: false,
          lineStyle: { width: 2 },
          areaStyle: { opacity: 0.1 },
          data: props.points.map((p) => p.hits)
        }
      ]
    },
    true
  )
}

const resize = () => chart?.resize()

onMounted(() => {
  if (!el.value) return
  chart = echarts.init(el.value)
  render()
  window.addEventListener('resize', resize)
})

watch(() => props.points, render, { deep: true })

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
/* 高度跟父容器走（并排时它要吃掉卡片里剩下的高度）；父容器没给高度时退回 220px */
.guard-chart {
  width: 100%;
  height: 100%;
  min-height: 220px;
}
</style>
