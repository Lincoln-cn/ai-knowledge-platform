<template>
    <div class="common-list">
        <ul v-if="data.length > 0">
            <template v-for="(item, index) in data" :key="index">
                <li @click.stop="clickHandle(item)" :class="current === item[props.valueKey] ? 'active' : ''"
                    class="cursor" @mouseenter.stop="mouseenter(item)" @mouseleave.stop="mouseleave()">
                    <slot :row="item" :index="index"> </slot>
                </li>
            </template>
        </ul>
        <slot name="empty" v-else>
            <el-empty description="暂无数据" />
        </slot>
    </div>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'

defineOptions({ name: 'CommonList' })

const props = withDefaults(
    defineProps<{
        data: Array<any>
        defaultActive?: string
        valueKey?: string // 唯一标识的键名
    }>(),
    {
        data: () => [],
        defaultActive: '',
        valueKey: 'id'
    }
)

const current = ref<Number | String>(0)

watch(
    () => props.defaultActive,
    (val) => {
        current.value = val
    },
    { immediate: true }
)

const emit = defineEmits(['click', 'mouseenter', 'mouseleave'])

function mouseenter(row: any) {
    emit('mouseenter', row)
}
function mouseleave() {
    emit('mouseleave')
}

function clickHandle(row: any) {
    current.value = row[props.valueKey]
    emit('click', row)
}

function clearCurrent() {
    current.value = 0
}
defineExpose({
    clearCurrent
})
</script>
<style lang="scss" scoped>
/* 通用 ui li样式 */
.common-list {
    /* 去掉 <ul> 的默认标记与缩进 —— 否则每行左侧会多一个圆点 */
    ul {
        list-style: none;
        margin: 0;
        padding: 0;
    }

    li {
        padding: var(--space-xs) var(--space-md);
        font-weight: 400;
        color: var(--color-ink-2);
        font-size: var(--text-sm);

        &.active {
            background: var(--color-accent-soft);
            border-radius: var(--radius);
            color: var(--color-accent);
            font-weight: 500;

            &:hover {
                background: var(--color-accent-soft);
            }
        }

        &:hover {
            border-radius: var(--radius);
            background: var(--color-hover);
        }
    }
}
</style>
