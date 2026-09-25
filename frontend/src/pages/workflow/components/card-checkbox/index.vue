<template>
    <el-card shadow="hover" class="card-checkbox cursor" :class="modelValue.includes(toModelValue) ? 'active' : ''"
        @click="checked">
        <div class="flex-between">
            <div class="flex align-center">
                <slot name="icon">
                    <!--
                      知识库图标是 **emoji 字符**（如 `📘`），不是 URL。
                      旧代码直接塞进 `<img src>` ⇒ 加载失败显示裂图，而且底下还压着
                      `avatar-purple` 的紫色底 ⇒ 看起来就是「图标没显示 + 背景不对」。
                      这里按「http 开头的才当图片，其余当文字」分流。
                    -->
                    <img v-if="isUrlIcon" class="mr-8 kb-icon" :src="data.knowledgeIcon" alt="" />
                    <span v-else class="mr-8 kb-emoji">{{ data.knowledgeIcon || '📘' }}</span>
                </slot>
                <slot></slot>
            </div>
            <el-checkbox v-bind:modelValue="modelValue.includes(toModelValue)" @change="checkboxChange">
            </el-checkbox>
        </div>
    </el-card>
</template>
<script setup lang="ts">
import { computed } from 'vue'
defineOptions({ name: 'CardCheckbox' })
const props = defineProps<{
    data: any

    modelValue: Array<any>

    valueField?: string
}>()

/** 只有 http(s) 开头的才当图片；emoji 等一律当文字渲染 */
const isUrlIcon = computed(
    () => typeof props.data?.knowledgeIcon === 'string' && props.data.knowledgeIcon.startsWith('http')
)

const toModelValue = computed(() => (props.valueField ? props.data[props.valueField] : props.data))

// const isChecked = computed({
//   get: () => props.modelValue.includes(toModelValue.value)),
//   set: (val) => val
// })

const emit = defineEmits(['update:modelValue', 'change'])

const checked = () => {
    const value = props.modelValue ? props.modelValue : []
    if (props.modelValue.includes(toModelValue.value)) {
        emit(
            'update:modelValue',
            value.filter((item) => item !== toModelValue.value)
        )
    } else {
        emit('update:modelValue', [...value, toModelValue.value])
    }
    checkboxChange()
}

function checkboxChange() {
    emit('change')
}
</script>
<style lang="scss">
/* emoji 图标：不套色块。
   以前外面是 `avatar-purple`（紫色底），emoji 自身有色彩，压在色块上很脏，
   而且一旦它被当图片加载失败就只剩一块紫底 + 裂图。 */
.kb-emoji {
    display: inline-block;
    width: 32px;
    font-size: var(--text-xl);
    line-height: 32px;
    text-align: center;
}

.kb-icon {
    width: 24px;
    height: 24px;
    object-fit: contain;
}

.card-checkbox {
    &.active {
        border: 1px solid var(--el-color-primary);
    }

    input.checkbox[type='checkbox'] {
        -webkit-appearance: none;
        -moz-appearance: none;
        appearance: none;
        height: 14px;
        width: 14px;
        position: relative;

        &::after {
            position: absolute;
            top: 0;
            background-color: var(--color-surface);
            color: var(--color-ink);
            height: 13px;
            width: 13px;
            visibility: visible;
            text-align: center;
            box-sizing: border-box;
            border: var(--el-border);
            border-radius: var(--el-border-radius-small);
            box-sizing: content-box;
            content: '';
        }

        &:checked::after {
            content: '✓';
            color: var(--color-accent-ink);
            border-color: var(--el-color-primary);
            background: var(--el-color-primary);
        }
    }
}
</style>
