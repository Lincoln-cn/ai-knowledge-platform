<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

/**
 * 读数跳动 —— ★ 第三轮新增（技能 canon 的 "Number tick"）。
 *
 * <p>它回答的是"这个数字是**刚算出来的**，还是一直挂在那儿的"。
 * 数据密集的界面完全静止会读成一张截图；一个 400ms 的计数把"数据到了"这件事说清楚，
 * 而它不需要任何额外的装饰。
 *
 * <h3>四条纪律（都来自技能的 canon，不是自选）</h3>
 * <ol>
 *   <li>缓动加在**值**上，不加在元素上（元素不动，只有数字变）。</li>
 *   <li>`null` / `undefined` = **没测到** ⇒ 渲染「—」且**不跳**。
 *       把"没数据"演成"从 0 涨到 0"是拿动效撒谎（本工程的头号病是静默，不是沉默）。</li>
 *   <li>辅助技术只播报**终值**一次（`aria-live="polite"`），不播报每一跳 ——
 *       所以跳动的数字对 AT `aria-hidden`，另起一个 `.sr-only` 区域承载终值。</li>
 *   <li>`prefers-reduced-motion: reduce` ⇒ **跳过动画，直接给终值**（不是"跳得快一点"）。</li>
 * </ol>
 *
 * <p>⚠️ 用法上有一条约束：它是**编排式原语**，每页的编排预算只有 3 个
 * （见 `design.md` § Motion）。同页再叠一个入场 + 一个导航切换就到顶了。
 *
 * <p>⚠️ **DOM 结构与断言**：根 `<span>` 里有两个孩子 ——
 * 可见数字（`[aria-hidden="true"]`）与播报终值的 `.sr-only`。
 * 所以根元素的 `innerText` 会**出现两份数字**（实测 `"723\n723"`）。
 * 要读这个数字的断言/探针请取 `[aria-hidden="true"]` 那一个，
 * 别读根元素 —— 否则会拿到重复值（本项目已有"读数先怀疑探针"的先例）。
 */
const props = withDefaults(
  defineProps<{
    /** 要显示的数值。`null` / `undefined` 表示没测到（渲染「—」，不动画） */
    value: number | null | undefined
    /** 格式化函数。默认向下取整 + locale 千分位；百分比请从这里传进来 */
    format?: (n: number) => string
    /** 时长（毫秒）。默认 400ms（canon 的"编排式入场 / 读数跳动"档） */
    duration?: number
  }>(),
  { format: undefined, duration: 400 }
)

const defaultFormat = (n: number) => Math.round(n).toLocaleString()
const fmt = computed(() => props.format ?? defaultFormat)

/** 「有没有测到」与「现在显示到几」是两件事：前者静态，后者动画 */
const missing = computed(() => props.value == null)

/** 当前显示到的值（动画过程中它一直在变） */
const shown = ref(0)

let frame = 0

const prefersReduced = () =>
  typeof window !== 'undefined' &&
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches

/** 近似 `--ease-out`（cubic-bezier(0.16, 1, 0.3, 1)）的 cubic ease-out */
const easeOut = (t: number) => 1 - Math.pow(1 - t, 3)

function run(target: number) {
  cancelAnimationFrame(frame)
  if (prefersReduced() || props.duration <= 0) {
    shown.value = target
    return
  }
  const started = performance.now()
  const step = (now: number) => {
    const t = Math.min(1, (now - started) / props.duration)
    shown.value = t < 1 ? target * easeOut(t) : target
    if (t < 1) frame = requestAnimationFrame(step)
  }
  frame = requestAnimationFrame(step)
}

watch(
  () => props.value,
  (next) => {
    if (next == null) {
      // 没测到：停掉可能在跑的那一跳，并归零 —— 下一次有数据时仍然是从 0 涨上来
      cancelAnimationFrame(frame)
      shown.value = 0
      return
    }
    run(next)
  },
  { immediate: true }
)

onBeforeUnmount(() => cancelAnimationFrame(frame))

/** 屏幕上看到的（跳动中） */
const display = computed(() => (missing.value ? '—' : fmt.value(shown.value)))
/** 播报给 AT 的（终值，只在数据真的变了时才变） */
const spoken = computed(() => (missing.value ? '—' : fmt.value(props.value as number)))
</script>

<template>
  <span>
    <span aria-hidden="true">{{ display }}</span>
    <span class="sr-only" aria-live="polite">{{ spoken }}</span>
  </span>
</template>
