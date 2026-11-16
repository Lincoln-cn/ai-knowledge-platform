<template>
    <el-dialog align-center title="参数设置" class="aiMode-param-dialog" v-model="dialogVisible" width="560px"
        append-to-body :close-on-click-modal="false" :close-on-press-escape="false">
        <DynamicsForm v-model="form_data" :fields="model_form_field" ref="dynamicsFormRef">
        </DynamicsForm>

        <template #footer>
            <span class="dialog-footer p-16">
                <el-button @click.prevent="dialogVisible = false">取消</el-button>
                <el-button type="primary" @click="submit" :loading="loading">确认</el-button>
            </span>
        </template>
    </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { FormField } from '@/components/dynamic-form/types'
import DynamicsForm from '@/components/dynamic-form/DynamicForm.vue'
const model_form_field = ref<Array<FormField>>([])
const emit = defineEmits(['refresh'])
const dynamicsFormRef = ref<InstanceType<typeof DynamicsForm>>()
const form_data = ref<any>({})
const dialogVisible = ref(false)
const loading = ref(false)
/** 模型参数表单接口尚未接入；先占位，打开弹窗时不填字段 */
const getApi = (_model_id: string, _application_id?: string) => {
    return Promise.resolve([] as FormField[])
}
const open = (model_id: string, application_id?: string, model_setting_data?: any) => {
    form_data.value = model_setting_data && typeof model_setting_data === 'object' ? { ...model_setting_data } : {}
    void getApi(model_id, application_id)
    dialogVisible.value = true
}

const reset_default = (model_id: string, application_id?: string) => {
    void getApi(model_id, application_id)
}

const submit = async () => {
    dynamicsFormRef.value?.validate().then(() => {
        emit('refresh', form_data.value)
        dialogVisible.value = false
    }).catch(() => {})
}

defineExpose({ open, reset_default })
</script>

<style lang="scss" scoped>
/* 弹窗的头/正文/底三段留白与分割线走全局口径（src/styles/element-plus.css），
   这里只保留内容区高度与滑块排版。 */
.aiMode-param-dialog {
  .dialog-max-height {
    height: 550px;
  }

  .custom-slider {
    .el-input-number.is-without-controls .el-input__wrapper {
      padding: 0 !important;
    }
  }
}
</style>
