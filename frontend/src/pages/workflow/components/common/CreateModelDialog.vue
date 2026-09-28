<template>
    <el-dialog v-model="dialogVisible" width="820px" :close-on-click-modal="false" :close-on-press-escape="false"
        :destroy-on-close="true" :before-close="close" append-to-body>
        <template #header>
            <el-breadcrumb separator=">">
                <el-breadcrumb-item>
                    <span @click="toSelectProvider" class="select-provider">选择供应商</span></el-breadcrumb-item>
                <el-breadcrumb-item><span class="active-breadcrumb">{{ `添加 ${supplierLabel}` }}</span></el-breadcrumb-item>
            </el-breadcrumb>
        </template>
        <ModelCreateForm ref="formRef" :supplier="supplierFilter" />
        <template #footer>
            <span class="dialog-footer">
                <el-button @click="close">取消</el-button>
                <el-button type="primary" @click="submit" :loading="loading"> 添加 </el-button>
            </span>
        </template>
    </el-dialog>
</template>
<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import type { Provider } from '@/api/type/model'
import type { Supplier } from '@/api/models'
import ModelCreateForm from '@/pages/models/components/ModelCreateForm.vue'

const formRef = ref<InstanceType<typeof ModelCreateForm>>()
const emit = defineEmits(['change', 'submit'])
const loading = ref<boolean>(false)
const dialogVisible = ref<boolean>(false)

/**
 * 旧 `Provider` 与字典项是两种形状（`{provider,name,icon}` vs `{key,value}`）。
 * 这里只认「供应商名」这一件事 —— 两边都能推出它，新契约的 `Supplier` 只接受这两个值。
 */
const supplierNameOf = (p: unknown): Supplier | undefined => {
    const raw = p as Record<string, unknown> | null | undefined
    const name = String(raw?.value ?? raw?.key ?? raw?.name ?? raw?.provider ?? '')
    return name === 'LOCAL_INFERENCE' || name === 'EXTERNAL_API' ? name : undefined
}

const providerValue = ref<Provider>()
const supplierFilter = computed(() => supplierNameOf(providerValue.value))
const supplierLabel = computed(() => supplierNameOf(providerValue.value) ?? '')

const open = async (provider: Provider) => {
    providerValue.value = provider
    dialogVisible.value = true
    // destroy-on-close：内容要等下一拍才挂上，不能同步 ref
    await nextTick()
    await formRef.value?.load()
}

const close = () => {
    formRef.value?.reset()
    dialogVisible.value = false
}

const submit = async () => {
    loading.value = true
    try {
        const ok = await formRef.value?.submit()
        if (ok) {
            close()
            // 旧调用点用 submit 刷新模型下拉（getAiList）
            emit('submit')
        }
    } finally {
        loading.value = false
    }
}

const toSelectProvider = () => {
    close()
    emit('change')
}
defineExpose({ open, close })
</script>
<style lang="scss" scoped>
.select-provider {
    font-size: var(--text-md);
    color: var(--color-muted);
    font-weight: 400;
    line-height: var(--leading-tight);
    cursor: pointer;

    &:hover {
        color: var(--color-accent);
    }
}

.active-breadcrumb {
    font-size: var(--text-md);
    color: var(--color-ink);
    font-weight: 500;
    line-height: var(--leading-tight);
}
</style>
