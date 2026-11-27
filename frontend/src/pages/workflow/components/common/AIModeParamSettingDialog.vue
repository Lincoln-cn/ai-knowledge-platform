<template>
    <el-dialog align-center title="参数设置" class="aiMode-param-dialog" v-model="dialogVisible" width="560px"
        append-to-body :close-on-click-modal="false" :close-on-press-escape="false">
        <div v-if="loadingFields" class="param-loading micro muted">正在读这个模型的参数声明…</div>

        <div v-else-if="model_form_field.length === 0" class="param-empty">
            <p class="param-empty-title">这个模型没有暴露可调参数</p>
            <p class="micro muted">
                可调参数由管理员在「模型管理 → 编辑模型 → 可调参数」里声明，声明什么这里就显示什么；
                没声明时这里不做假表单 —— 填了也送不到上游。
            </p>
            <p v-if="loadError" class="micro param-error">{{ loadError }}</p>
        </div>

        <DynamicsForm v-else v-model="form_data" :fields="model_form_field" ref="dynamicsFormRef">
        </DynamicsForm>

        <template #footer>
            <span class="dialog-footer p-16">
                <el-button @click.prevent="dialogVisible = false">取消</el-button>
                <el-button type="primary" @click="submit" :loading="loading" :disabled="model_form_field.length === 0">
                    确认
                </el-button>
            </span>
        </template>
    </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { FormField } from '@/components/dynamic-form/types'
import DynamicsForm from '@/components/dynamic-form/DynamicForm.vue'
import { getModel, type ModelParamDecl } from '@/api/models'

const model_form_field = ref<Array<FormField>>([])
const emit = defineEmits(['refresh'])
const dynamicsFormRef = ref<InstanceType<typeof DynamicsForm>>()
const form_data = ref<any>({})
const dialogVisible = ref(false)
const loading = ref(false)
const loadingFields = ref(false)
const loadError = ref('')
/** 当前这条模型的声明；`submit` 用它把界面上的字符串值还原成 number / boolean */
const decls = ref<Array<ModelParamDecl>>([])

/** 解析 `paramSchema`：坏 JSON 当"没有声明"，但把原因留在 loadError 里，不静默。 */
const parseDecls = (raw: string | undefined): Array<ModelParamDecl> => {
    if (!raw) return []
    try {
        const parsed = JSON.parse(raw)
        return Array.isArray(parsed) ? (parsed as Array<ModelParamDecl>) : []
    } catch (e: any) {
        loadError.value = `这条模型的参数声明不是合法 JSON：${e?.message || 'parse error'}`
        return []
    }
}

const rangeText = (d: ModelParamDecl): string => {
    const bits: Array<string> = []
    if (d.min !== undefined && d.min !== null) bits.push(`最小 ${d.min}`)
    if (d.max !== undefined && d.max !== null) bits.push(`最大 ${d.max}`)
    if (d.default !== undefined && d.default !== null) bits.push(`默认 ${String(d.default)}`)
    return bits.join(' · ')
}

/**
 * 声明 → 动态表单字段。
 *
 * ★ 只有四种输入组件：开关（boolean）、下拉（enum）、文本（其余）。
 *   不在这一层发明"数字输入框"—— 数字用文本框输入、`submit` 时按声明转成数字，
 *   这样界面与后端只有一处归一（`ModelParamSchema.split`），不会出现"界面看着对、请求里是字符串"。
 */
const toFields = (list: Array<ModelParamDecl>): Array<FormField> =>
    list.map((d) => {
        const label = d.label && d.label.trim() ? d.label.trim() : d.key
        const hint = [d.help, rangeText(d)].filter(Boolean).join(' · ')
        if (d.type === 'boolean') {
            return {
                field: d.key,
                input_type: 'SwitchInput',
                label,
                default_value: typeof d.default === 'boolean' ? d.default : false
            }
        }
        if (d.type === 'enum') {
            return {
                field: d.key,
                input_type: 'SingleSelect',
                label,
                default_value: d.default,
                option_list: (d.options || []).map((o) => ({ label: o, value: o })),
                attrs: hint ? { placeholder: hint } : {}
            }
        }
        return {
            field: d.key,
            input_type: 'TextInput',
            label,
            default_value: d.default,
            attrs: {
                placeholder:
                    d.type === 'integer'
                        ? [hint, '整数'].filter(Boolean).join(' · ')
                        : [hint, '数字'].filter(Boolean).join(' · ')
            }
        }
    })

/** 界面值 → 请求值：按声明把文本框里的字符串还原成数字/布尔，未填的不带。 */
const coerce = (values: Record<string, any>, list: Array<ModelParamDecl>): Record<string, any> => {
    const out: Record<string, any> = {}
    for (const d of list) {
        const raw = values?.[d.key]
        if (raw === undefined || raw === null || raw === '') continue
        if (d.type === 'number' || d.type === 'integer') {
            const n = Number(raw)
            if (!Number.isFinite(n)) continue
            out[d.key] = d.type === 'integer' ? Math.round(n) : n
        } else if (d.type === 'boolean') {
            out[d.key] = raw === true || raw === 'true'
        } else {
            out[d.key] = raw
        }
    }
    return out
}

const loadParams = async (model_id: string) => {
    loadingFields.value = true
    loadError.value = ''
    try {
        const model = await getModel(model_id)
        decls.value = parseDecls(model.paramSchema)
        model_form_field.value = toFields(decls.value)
    } catch (e: any) {
        decls.value = []
        model_form_field.value = []
        loadError.value = `取模型参数声明失败：${e?.message || '未知原因'}`
    } finally {
        loadingFields.value = false
    }
}

const open = (model_id: string, _application_id?: string, model_setting_data?: any) => {
    form_data.value = model_setting_data && typeof model_setting_data === 'object' ? { ...model_setting_data } : {}
    dialogVisible.value = true
    if (model_id) void loadParams(model_id)
    else {
        decls.value = []
        model_form_field.value = []
        loadError.value = '这个节点还没有选模型'
    }
}

const reset_default = (model_id: string, _application_id?: string) => {
    if (model_id) void loadParams(model_id)
}

const submit = async () => {
    if (model_form_field.value.length === 0) {
        dialogVisible.value = false
        return
    }
    dynamicsFormRef.value?.validate().then(() => {
        emit('refresh', coerce(form_data.value, decls.value))
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

  .param-loading {
    padding: 16px 0;
  }

  /* 空态要给出"为什么是空的 + 去哪声明"，而不是一片空白 ——
     否则人会以为弹窗坏了（`#371` 第 135 条）。 */
  .param-empty {
    padding: 8px 0;

    .param-empty-title {
      margin: 0 0 6px;
      font-size: 14px;
      color: var(--color-ink);
    }

    .micro {
      margin: 0;
      line-height: 1.6;
    }

    .param-error {
      margin-top: 8px;
      color: var(--color-danger);
    }
  }
}
</style>
