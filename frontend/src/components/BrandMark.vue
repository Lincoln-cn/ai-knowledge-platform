<template>
  <!--
    平台字标 —— 与仪器面板同源：一圈发丝线框 + 一个信号点，右下留缺口给信号点。
    几何照抄 public/favicon.svg（同一套形状，改一处必须改两处）；
    色改走令牌：框用 currentColor（由调用方定色），点是唯一的钴蓝信号。

    两种底都能用：浅色纸上给深色（`color: var(--color-ink)`），
    深色石墨面上给浅色（`color: var(--color-graphite-ink)`）。
    信号点恒为 --color-accent —— 它是本系统的唯一信号色，不随底改色。
  -->
  <svg
    class="brand-mark"
    :width="size"
    :height="size"
    viewBox="0 0 32 32"
    fill="none"
    aria-hidden="true"
    focusable="false"
  >
    <g transform="translate(4 4)">
      <path
        class="brand-mark-frame"
        d="M6.5 0.9 H17.5 A5.6 5.6 0 0 1 23.1 6.5 V17.5 A5.6 5.6 0 0 1 17.5 23.1 H14"
        stroke-width="1.9"
        stroke-linecap="round"
      />
      <circle class="brand-mark-signal" cx="6.6" cy="17.6" r="3.5" />
    </g>
  </svg>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ size?: number }>(), { size: 24 })
</script>

<style scoped>
.brand-mark {
  display: block;
  flex-shrink: 0;
}

.brand-mark-frame {
  fill: none;
  stroke: currentColor;
}

.brand-mark-signal {
  fill: var(--color-accent);
}
</style>
