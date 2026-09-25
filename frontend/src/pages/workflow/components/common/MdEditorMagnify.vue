<template>
    <div class="md-editor-magnify">
        <el-input
            v-model="data"
            type="textarea"
            :rows="rows"
            :placeholder="placeholder"
            resize="none"
            class="md-editor-input"
        />
        <el-button text type="info" class="magnify-btn" @click="openDialog">
            <el-icon><FullScreen /></el-icon>
        </el-button>
        <el-dialog v-model="dialogVisible" :title="title" append-to-body align-center width="720px">
            <el-input v-model="cloneContent" type="textarea" :rows="16" resize="none" />
            <template #footer>
                <div class="dialog-footer mt-24">
                    <el-button type="primary" @click="submitDialog">确认</el-button>
                </div>
            </template>
        </el-dialog>
    </div>
</template>
<script setup lang="ts">
import { ref, computed } from 'vue'

/**
 * 旧工程的 MdEditorMagnify 是 md-editor-v3 的放大编辑器。
 * 本工程未引入 md-editor-v3，这里用 textarea + 放大弹窗替代，
 * 接口（v-model / title / submitDialog / placeholder）与旧组件对齐。
 */
defineOptions({ name: 'MdEditorMagnify' })
const props = defineProps<{
    title?: string
    modelValue?: string
    placeholder?: string
    rows?: number
}>()
const emit = defineEmits(['update:modelValue', 'submitDialog'])

const data = computed({
    get: () => props.modelValue ?? '',
    set: (value: string) => emit('update:modelValue', value)
})

const rows = computed(() => props.rows ?? 4)
const dialogVisible = ref(false)
const cloneContent = ref('')

function openDialog() {
    cloneContent.value = props.modelValue ?? ''
    dialogVisible.value = true
}
function submitDialog() {
    emit('submitDialog', cloneContent.value)
    emit('update:modelValue', cloneContent.value)
    dialogVisible.value = false
}
</script>
<style lang="scss" scoped>
.md-editor-magnify {
    position: relative;
    width: 100%;

    .magnify-btn {
        position: absolute;
        right: 4px;
        bottom: 4px;
        padding: 2px;
    }
}
</style>
