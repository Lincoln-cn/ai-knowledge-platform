<template>
  <div ref="el" class="trend-chart" />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { TrendPoint } from '@/api/analytics'

const props = defineProps<{ points: TrendPoint[] }>()

const el = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

/**
 * 令牌是 oklch，echarts 的色表只认 rgb/hex。
 * 用一次性探针把 var(--token) 解析成浏览器计算后的 rgb()，再交给 echarts。
 */
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
  const ok = cssColor('--color-ok')
  const rule = cssColor('--color-rule')
  const rule2 = cssColor('--color-rule-2')
  const muted = cssColor('--color-muted')
  const ink = cssColor('--color-ink')
  const surface = cssColor('--color-surface')
  const fontBody = cssVar('--font-body')

  chart.setOption(
    {
      color: [accent, ok],
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
      legend: {
        data: ['提问数', '用户数'],
        top: 0,
        itemWidth: 10,
        itemHeight: 6,
        textStyle: { color: muted, fontSize: 12, fontFamily: fontBody }
      },
      grid: { left: 8, right: 12, top: 40, bottom: 4, containLabel: true },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        axisLine: { lineStyle: { color: rule } },
        axisTick: { lineStyle: { color: rule } },
        axisLabel: { color: muted, fontSize: 12, fontFamily: fontBody },
        data: props.points.map((p) => p.day.slice(5))
      },
      yAxis: [
        {
          type: 'value',
          name: '提问',
          nameTextStyle: { color: muted, fontSize: 12, fontFamily: fontBody },
          axisLabel: { color: muted, fontSize: 12, fontFamily: fontBody },
          axisLine: { show: false },
          splitLine: { lineStyle: { color: rule2 } }
        },
        {
          type: 'value',
          name: '用户',
          nameTextStyle: { color: muted, fontSize: 12, fontFamily: fontBody },
          axisLabel: { color: muted, fontSize: 12, fontFamily: fontBody },
          axisLine: { show: false },
          splitLine: { show: false }
        }
      ],
      series: [
        {
          name: '提问数',
          type: 'line',
          smooth: true,
          showSymbol: false,
          lineStyle: { width: 2 },
          areaStyle: { opacity: 0.1 },
          data: props.points.map((p) => p.questions)
        },
        {
          name: '用户数',
          type: 'line',
          smooth: true,
          showSymbol: false,
          yAxisIndex: 1,
          lineStyle: { width: 2 },
          data: props.points.map((p) => p.users)
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
.trend-chart {
  width: 100%;
  height: 280px;
}
</style>
