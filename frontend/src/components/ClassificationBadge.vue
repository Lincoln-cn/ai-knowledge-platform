<template>
  <!--
    密级上限徽标 —— ★ 全平台**唯一**的密级展示件（`13 §5.1` 规则 4："让用户知道自己正在读
    什么级别的内容"，这是防误用的最后一道人工防线）。

    ★ 刻意带「密级上限」前缀而不是只显示「秘密」：
      `ResourceVisibility` 的 `PUBLIC` 中文也叫「公开」（= 登录用户里人人可见），
      而密级档位里有一档也叫「公开」（= 对外可公开，`mock/fixtures/dictionaries.ts` 的原值）。
      两个"公开"指的不是一件事（一个是**可见性**，一个是**密级**）—— 只显示一个词，
      用户会把"这条链接是公开的"读成"这条链接的内容都是公开级别"。前缀就是那道区分。
  -->
  <span class="cls-badge" :class="[`cls-${level.toLowerCase()}`, compact ? 'is-compact' : '']">
    <span v-if="variant === 'full'" class="cls-label">密级上限</span>
    <span class="cls-value">{{ text }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ClassificationLevel } from '@/api/types'
import { CLASSIFICATION_LABELS } from '@/utils/classification'

const props = withDefaults(
  defineProps<{
    level: ClassificationLevel
    /**
     * 取值 → 展示名。省略时用 `CLASSIFICATION_LABELS` 兜底。
     * ★ 登录面请传 `useDictionary('CLASSIFICATION')` 的结果（密级展示名以字典为真源）；
     *   未登录的公开面读不到字典，只能走兜底 —— 两者都在这里收口，页面不各写一套。
     */
    labels?: Record<string, string>
    /** `full` = 「密级上限 秘密」；`inline` = 只给档位名（表格列里用） */
    variant?: 'full' | 'inline'
    compact?: boolean
  }>(),
  { variant: 'full', compact: false }
)

const text = computed(() => props.labels?.[props.level] ?? CLASSIFICATION_LABELS[props.level] ?? props.level)
</script>

<style scoped>
.cls-badge {
  display: inline-flex;
  align-items: baseline;
  gap: var(--space-2xs);
  padding: 0 var(--space-xs);
  height: 22px;
  border: 1px solid var(--color-rule-strong);
  border-radius: var(--radius-sm);
  background: var(--color-sunken);
  color: var(--color-ink-2);
  font-size: var(--text-2xs);
  line-height: var(--leading-tight);
  white-space: nowrap;
  vertical-align: middle;
}

.is-compact {
  height: 20px;
  padding: 0 var(--space-2xs);
}

.cls-label {
  color: var(--color-muted);
}

.cls-value {
  font-weight: 500;
  color: var(--color-ink);
}

/* 档位越高越"重"，但**只用边框与文字**，不铺色块：
   密级不是状态告警，大面积填色会让整张表变成红黄绿噪声。 */
.cls-secret {
  border-color: color-mix(in oklch, var(--color-warn) 45%, var(--color-rule));
  background: var(--color-warn-soft);
}

.cls-confidential {
  border-color: color-mix(in oklch, var(--color-danger) 45%, var(--color-rule));
  background: var(--color-danger-soft);
}
</style>
