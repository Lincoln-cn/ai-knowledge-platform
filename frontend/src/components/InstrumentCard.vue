<script lang="ts">
/**
 * 仪器面板（design.md § 仪器面板 · Cobalt 签名 4 + 8）。
 *
 * 它回答「**这台机器此刻在做什么**」，是本系统的焦点元素：
 * 深色石墨底 + mono 标签 + 等宽读数 + 右上角四态胶囊。
 *
 * 三条纪律（由本组件与调用方共同承担）：
 *  1. **每视口一个**，不按页算 —— 同一屏里出现第二个，焦点就没有了。
 *  2. **数据必须真实**（现成契约：`analyticsApi` / `ingestionApi` / `assistantsApi`）。
 *     本组件**不自己造数据**：`rows` 原样渲染，空数组就什么都不显示。
 *     编造指标是本系统最严重的违规。
 *  3. 状态只许四态（`OK` / `RUNNING` / `WARN` / `ERROR`），不许自造第五节。
 */
export type InstrumentStatus = 'OK' | 'RUNNING' | 'WARN' | 'ERROR'

export interface InstrumentRow {
  /** 读数名。渲染成 mono 全大写小标签 */
  label: string
  /** 读数本身。mono + tabular-nums，由调用方保证是真实值 */
  value: string | number
  /** 单位 / 口径 / 对比基线等说明，body 面小字 */
  hint?: string
}
</script>

<script setup lang="ts">
withDefaults(
  defineProps<{
    /** 面板标题：这一段读数在讲什么 */
    title: string
    status?: InstrumentStatus
    rows?: InstrumentRow[]
  }>(),
  { status: undefined, rows: () => [] }
)
</script>

<template>
  <section class="instrument">
    <span class="grain" aria-hidden="true" />

    <header class="ins-head">
      <h3 class="ins-name">{{ title }}</h3>
      <span v-if="status" class="pill" :class="status.toLowerCase()">
        <span class="pill-dot" aria-hidden="true" />
        {{ status }}
      </span>
    </header>

    <!-- 想让调用方自己排版时给 #rows；否则用 rows 数组 -->
    <slot v-if="$slots.rows" name="rows" />
    <dl v-else-if="rows.length" class="ins-rows">
      <div v-for="row in rows" :key="row.label" class="ins-row">
        <dt class="ins-key">{{ row.label }}</dt>
        <dd class="ins-val">
          <span class="ins-reading">{{ row.value }}</span>
          <span v-if="row.hint" class="ins-hint">{{ row.hint }}</span>
        </dd>
      </div>
    </dl>

    <div v-if="$slots.default" class="ins-body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
/* 石墨底 + 10px 圆角（比卡片松一档 —— Cobalt 对代码/回执件的口径）
   + 只一条极轻抬起。深度仍主要靠线与色阶。 */
.instrument {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  padding: var(--space-md) var(--space-lg);
  background: var(--color-graphite);
  border: 1px solid var(--color-graphite-rule);
  border-radius: var(--radius-instrument);
  box-shadow: var(--shadow-whisper);
  color: var(--color-graphite-ink);
}

/* 颗粒：art 层的四处合法表面之一。不透明度走令牌（硬上限 0.15），不许在页面里再调高。 */
.grain {
  position: absolute;
  inset: 0;
  background-image: var(--art-grain);
  background-repeat: repeat;
  opacity: var(--art-opacity-grain);
  pointer-events: none;
}

/* 内容都要 position: relative —— 否则会被上面那层定位元素盖住 */
.ins-head,
.ins-rows,
.ins-body {
  position: relative;
}

.ins-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
}

.ins-name {
  margin: 0;
  font-family: var(--font-body);
  font-size: var(--text-md);
  font-weight: 600;
  line-height: var(--leading-title);
  letter-spacing: var(--tracking-body); /* 中文标题不给负字距 */
  color: var(--color-graphite-ink);
}

/* ── 状态胶囊：四态 · 1px 同色描边 + 同色文字，不做大面积填充 ── */
.pill {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: var(--space-2xs);
  height: 22px;
  padding: 0 var(--space-xs);
  border: 1px solid currentColor;
  border-radius: var(--radius-sm);
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  font-weight: 500;
  letter-spacing: var(--tracking-label-instrument);
  text-transform: uppercase;
}

.pill-dot {
  width: 5px;
  height: 5px;
  border-radius: 1px;
  background: currentColor;
}

.pill.ok {
  color: var(--color-ok);
}

.pill.running {
  color: var(--color-accent);
}

.pill.warn {
  color: var(--color-warn);
}

.pill.error {
  color: var(--color-danger);
}

/* ── 读数行：左 mono 标签 / 右 mono 数字，行间一条石墨发丝线 ── */
.ins-rows {
  margin: 0;
  border-top: 1px solid var(--color-graphite-rule);
}

.ins-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: baseline;
  gap: var(--space-sm);
  padding: var(--space-xs) 0;
}

.ins-row + .ins-row {
  border-top: 1px solid var(--color-graphite-rule);
}

.ins-key {
  margin: 0;
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  font-weight: 500;
  letter-spacing: var(--tracking-label-instrument);
  text-transform: uppercase;
  color: var(--color-graphite-faint);
  overflow-wrap: anywhere;
}

.ins-val {
  margin: 0;
  display: flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: var(--space-xs);
  text-align: right;
}

.ins-reading {
  font-family: var(--font-mono);
  font-size: var(--text-md);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: var(--leading-tight);
  color: var(--color-graphite-ink);
  white-space: nowrap;
}

.ins-hint {
  font-family: var(--font-body);
  font-size: var(--text-2xs);
  line-height: var(--leading-tight);
  color: var(--color-graphite-ink-2);
}

.ins-body {
  font-size: var(--text-xs);
  line-height: var(--leading-body);
  color: var(--color-graphite-ink-2);
}
</style>
