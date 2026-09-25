<template>
    <el-tooltip v-bind="$attrs" :disabled="!(containerWeight > contentWeight)" effect="dark" placement="bottom"
        popper-class="auto-tooltip-popper">
        <div ref="tagLabel" :class="['auto-tooltip', className]" :style="style">
            <slot></slot>
        </div>
    </el-tooltip>
</template>
<script setup lang="ts">
import { ref, onMounted, nextTick, onBeforeUnmount } from 'vue'
defineOptions({ name: 'WorkFlowAutoTooltip' })
defineProps({ className: String, style: Object })
const tagLabel = ref()
const containerWeight = ref(0)
const contentWeight = ref(0)

const onResize = () => {
    containerWeight.value = tagLabel.value?.scrollWidth
    contentWeight.value = tagLabel.value?.clientWidth
}

onMounted(() => {
    nextTick(onResize)
    window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
    window.removeEventListener('resize', onResize)
})
</script>
<style lang="scss" scoped>
.auto-tooltip {
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
}
</style>
