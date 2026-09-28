<template>
    <NodeContainer :nodeModel="nodeModel">
        <!-- 「必要信息」只读摘要，见 ai-chat-node 里的同款说明。 -->
        <template #essential>
            AI 模型 · {{ modelOptions?.find((m: any) => m.id === form_data.model_id)?.modelName || '未选择' }}
        </template>
        <h5 class="title-decoration-1 mb-8">节点设置</h5>
        <el-card shadow="never" class="card-never" style="--el-card-padding: 12px">
            <el-form @submit.prevent :model="form_data" label-position="top" require-asterisk-position="right"
                class="mb-24" label-width="auto" ref="questionNodeFormRef" hide-required-asterisk>
                <el-form-item label="AI 模型" prop="model_id" :rules="{
        required: true,
        message: '请选择 AI 模型',
        trigger: 'change'
    }">
                    <template #label>
                        <div class="flex-between">
                            <div>
                                <span>AI 模型<span class="danger">*</span></span>
                            </div>
                            <!-- <el-button type="primary" link :disabled="!form_data.model_id"
                                @click="openAIParamSettingDialog(form_data.model_id)" @refreshForm="refreshParam">
                                {{ $t('views.application.applicationForm.form.paramSetting') }}
                            </el-button> -->
                        </div>
                    </template>
                    <el-select @change="model_change" @wheel="wheel" v-model="form_data.model_id"
                        placeholder="请选择 AI 模型" class="w-full" popper-class="select-model" :clearable="true">
                        <el-option v-for="item in modelOptions" :key="item.id" :label="item.modelName" :value="item.id"
                            :disabled="item.status !== 'LOADED'" class="flex-between">
                            <span>{{ item.modelName }}</span>
                            <!-- 不是「已加载」的选了也调不动，所以禁用 + 写明原因，而不是藏起来 -->
                            <span v-if="item.status !== 'LOADED'" class="model-status">{{ labelOf(MODEL_STATUS_LABELS, item.status) }}</span>
                            <el-icon class="check-icon" v-else-if="item.id === form_data.model_id">
                                <Check />
                            </el-icon>
                        </el-option>
                        <template #footer>
                            <div class="w-full text-left cursor" @click="openCreateModel()">
                                <el-button type="primary" link>
                                    <el-icon class="mr-4">
                                        <Plus />
                                    </el-icon>
                                    添加模型
                                </el-button>
                            </div>
                        </template>
                    </el-select>
                </el-form-item>
                <el-form-item label="角色设定">
                    <MdEditorMagnify title="角色设定" v-model="form_data.system" style="height: 100px"
                        @submitDialog="submitSystemDialog" placeholder="角色设定" />
                </el-form-item>
                <el-form-item label="提示词" prop="prompt" :rules="{
        required: true,
        message: '请输入提示词',
        trigger: 'blur'
    }">
                    <template #label>
                        <div class="flex align-center">
                            <div class="mr-4">
                                <span>提示词<span class="danger">*</span></span>
                            </div>
                            <el-tooltip effect="dark" placement="right" popper-class="max-w-200">
                                <template #content>通过调整提示词内容，可以引导大模型聊天方向，该提示词会被固定在上下文的开头，可以使用变量。</template>
                                <WorkFlowIcon iconName="app-warning" class="app-warning-icon"></WorkFlowIcon>
                                <el-icon>
                                    <EditPen />
                                </el-icon>
                            </el-tooltip>
                        </div>
                    </template>
                    <MdEditorMagnify @wheel="wheel" title="提示词" v-model="form_data.prompt" style="height: 150px"
                        @submitDialog="submitDialog" />
                </el-form-item>
                <el-form-item label="历史聊天记录">
                    <el-input-number v-model="form_data.dialogue_number" :min="0" :value-on-clear="0"
                        controls-position="right" class="w-full" :step="1" :step-strictly="true" />
                </el-form-item>
                <el-form-item label="返回内容" @click.prevent>
                    <template #label>
                        <div class="flex align-center">
                            <div class="mr-4">
                                <span>返回内容<span class="danger">*</span></span>
                            </div>
                            <el-tooltip effect="dark" placement="right" popper-class="max-w-200">
                                <template #content>
                                    关闭后该节点的内容则不输出给用户。
                                    如果你想让用户看到该节点的输出内容，请打开开关。
                                </template>
                                <WorkFlowIcon iconName="app-warning" class="app-warning-icon"></WorkFlowIcon>
                            </el-tooltip>
                        </div>
                    </template>
                    <el-switch size="small" v-model="form_data.is_result" />
                </el-form-item>
            </el-form>
        </el-card>

        <!-- 添加模版 -->
        <CreateModelDialog ref="createModelRef" @submit="getModel" @change="openCreateModel($event)">
        </CreateModelDialog>
        <SelectProviderDialog ref="selectProviderRef" @change="openCreateModel($event)" />
        <AIModeParamSettingDialog ref="AIModeParamSettingDialogRef" @refresh="refreshParam" />
    </NodeContainer>
</template>
<script setup lang="ts">
import { set } from 'lodash-es'
import NodeContainer from '@/pages/workflow/components/common/NodeContainer.vue'
import CreateModelDialog from '@/pages/workflow/components/common/CreateModelDialog.vue'
import SelectProviderDialog from '@/pages/workflow/components/common/SelectProviderDialog.vue'
import AIModeParamSettingDialog from '@/pages/workflow/components/common/AIModeParamSettingDialog.vue'
import type { FormInstance } from 'element-plus'
import { ref, computed, onMounted } from 'vue'
import type { Provider } from '@/api/type/model'
import { isLastNode } from '@/pages/workflow/components/common/data'
const AIModeParamSettingDialogRef = ref<InstanceType<typeof AIModeParamSettingDialog>>()
import { getAiList } from '@/api/legacy/setting'
import { getAppId } from '@/pages/workflow/lib/context'
import { MODEL_STATUS_LABELS, labelOf } from '@/constants/labels'

const wheel = (e: any) => {
    if (e.ctrlKey === true) {
        e.preventDefault()
        return true
    } else {
        e.stopPropagation()
        return true
    }
}

const model_change = (model_id?: string) => {
    if (model_id) {
        AIModeParamSettingDialogRef.value?.reset_default(model_id, id)
    } else {
        refreshParam({})
    }
}
function submitDialog(val: string) {
    set(props.nodeModel.properties.node_data, 'prompt', val)
}

function submitSystemDialog(val: string) {
    set(props.nodeModel.properties.node_data, 'system', val)
}
const id = getAppId()

// @ts-ignore
const defaultPrompt = `根据上下文优化和完善用户问题：{{开始.question}}
请输出一个优化后的问题。`
const form = {
    model_id: '',
    system: '你是一个问题优化大师',
    prompt: defaultPrompt,
    dialogue_number: 1,
    is_result: false
}
function refreshParam(data: any) {
    set(props.nodeModel.properties.node_data, 'model_params_setting', data)
}

const form_data = computed({
    get: () => {
        if (props.nodeModel.properties.node_data) {
            return props.nodeModel.properties.node_data
        } else {
            set(props.nodeModel.properties, 'node_data', form)
        }
        return props.nodeModel.properties.node_data
    },
    set: (value) => {
        set(props.nodeModel.properties, 'node_data', value)
    }
})
const props = defineProps<{ nodeModel: any }>()

const questionNodeFormRef = ref<FormInstance>()
const createModelRef = ref<InstanceType<typeof CreateModelDialog>>()
const selectProviderRef = ref<InstanceType<typeof SelectProviderDialog>>()

const modelOptions = ref<any>(null)

const validate = () => {
    return questionNodeFormRef.value?.validate().catch((err) => {
        return Promise.reject({ node: props.nodeModel, errMessage: err })
    })
}

function getModel() {
    getAiList().then(res => {
        modelOptions.value = res?.data.data;
    })
}

const openCreateModel = (provider?: Provider) => {
    if (provider && provider.provider) {
        createModelRef.value?.open(provider)
    } else {
        selectProviderRef.value?.open()
    }
}

onMounted(() => {
    getModel()
    if (typeof props.nodeModel.properties.node_data?.is_result === 'undefined') {
        if (isLastNode(props.nodeModel)) {
            set(props.nodeModel.properties.node_data, 'is_result', true)
        }
    }
    set(props.nodeModel, 'validate', validate)
})
</script>
<style lang="scss" scoped>
/* 未加载模型的状态标记：禁用了但写清原因，不让用户以为“模型丢了” */
.model-status {
    margin-left: var(--space-xs);
    font-size: var(--text-2xs);
    color: var(--el-text-color-placeholder);
}
</style>
