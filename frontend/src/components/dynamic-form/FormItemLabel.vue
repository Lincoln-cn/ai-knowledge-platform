<template>
  <span class="form-item-label">{{ text }}</span>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { FormField } from './types'
import { labelText } from './utils'

const props = defineProps<{
  formField: FormField
}>()

const text = computed(() => labelText(props.formField.label))
</script>
<style scoped>
.form-item-label {
  white-space: nowrap;
}
</style>
