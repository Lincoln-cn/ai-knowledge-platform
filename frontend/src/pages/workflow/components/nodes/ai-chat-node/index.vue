<template>
    <NodeContainer :nodeModel="nodeModel">
        <!--
          「必要信息」：画布上一眼看的那一项。只读摘要，编辑仍在点开的配置弹窗里。
          不写进标题行是为了让标题保持纯粹；不写成输入框是因为输入框要 el-form 上下文，
          而校验上下文在弹窗那张表单里，两处各放一个可编辑控件会让必填在两棵 DOM 树里各判一次。
        -->
        <template #essential>
            AI 模型 · {{ modelOptions?.find((m: any) => m.id === chat_data.model_id)?.modelName || '未选择' }}
        </template>
        <h5 class="title-decoration-1 mb-8">节点设置</h5>
        <el-card shadow="never" class="card-never" style="--el-card-padding: 12px">
            <el-form @submit.prevent :model="chat_data" label-position="top" require-asterisk-position="right"
                class="mb-24" label-width="auto" ref="aiChatNodeFormRef" hide-required-asterisk>
                <el-form-item label="AI 模型" prop="model_id" :rules="{
        required: true,
        message: '请选择 AI 模型',
        trigger: 'change'
    }">
                    <template #label>
                        <div class="flex-between w-full">
                            <div>
                                <span>AI 模型<span class="danger">*</span></span>
                            </div>
                            <!-- <el-button :disabled="!chat_data.model_id" type="primary" link
                                @click="openAIParamSettingDialog(chat_data.model_id)" @refreshForm="refreshParam">
                                参数设置
                            </el-button> -->
                        </div>
                    </template>
                    <el-select @change="model_change" @wheel="wheel" v-model="chat_data.model_id"
                        placeholder="请选择 AI 模型" class="w-full" popper-class="select-model" :clearable="true">
                        <el-option v-for="item in modelOptions" :key="item.id" :label="item.modelName" :value="item.id"
                            :disabled="item.status !== '已加载'" class="flex-between">
                            <span>{{ item.modelName }}</span>
                            <!-- 不是「已加载」的选了也调不动，所以禁用 + 写明原因，而不是藏起来 -->
                            <span v-if="item.status !== '已加载'" class="model-status">{{ item.status }}</span>
                            <el-icon class="check-icon" v-else-if="item.id === chat_data.model_id">
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
                    <MdEditorMagnify title="角色设定" v-model="chat_data.system" style="height: 100px"
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
                                <template #content>通过调整提示词内容，可以引导大模型聊天方向，该提示词会被固定在上下文的开头，可以使用变量。
                                </template>
                                <WorkFlowIcon iconName="app-warning" class="app-warning-icon"></WorkFlowIcon>
                            </el-tooltip>
                        </div>
                    </template>
                    <MdEditorMagnify @wheel="wheel" title="提示词" v-model="chat_data.prompt" style="height: 150px"
                        @submitDialog="submitDialog" />
                </el-form-item>
                <el-form-item label="历史聊天记录">
                    <template #label>
                        <div class="flex-between">
                            <div>历史聊天记录</div>
                            <el-select v-model="chat_data.dialogue_type" type="small" style="width: 100px">
                                <el-option label="节点" value="NODE" />
                                <el-option label="工作流" value="WORKFLOW" />
                            </el-select>
                        </div>
                    </template>
                    <el-input-number v-model="chat_data.dialogue_number" :min="0" :value-on-clear="0"
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
                    <el-switch size="small" v-model="chat_data.is_result" />
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
import type { FormInstance } from 'element-plus'
import { ref, computed, onMounted } from 'vue'

import type { Provider } from '@/api/type/model'
import { isLastNode } from '@/pages/workflow/components/common/data'
import AIModeParamSettingDialog from '@/pages/workflow/components/common/AIModeParamSettingDialog.vue'
import { getAiList } from '@/api/legacy/setting'
import { getAppId } from '@/pages/workflow/lib/context'

const wheel = (e: any) => {
    if (e.ctrlKey === true) {
        e.preventDefault()
        return true
    } else {
        e.stopPropagation()
        return true
    }
}

function submitSystemDialog(val: string) {
    set(props.nodeModel.properties.node_data, 'system', val)
}

function submitDialog(val: string) {
    set(props.nodeModel.properties.node_data, 'prompt', val)
}

const model_change = (model_id?: string) => {
    if (model_id) {
        AIModeParamSettingDialogRef.value?.reset_default(model_id, id)
    } else {
        refreshParam({})
    }
}
const id = getAppId()

// @ts-ignore
const defaultPrompt = `已知信息：
{{知识库检索.data}}
问题：
{{开始.question}}`
const form = {
    model_id: '',
    system: '',
    prompt: defaultPrompt,
    dialogue_number: 1,
    is_result: false,
    temperature: null,
    max_tokens: null,
    dialogue_type: 'WORKFLOW'
}

const chat_data = computed({
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

const aiChatNodeFormRef = ref<FormInstance>()
const createModelRef = ref<InstanceType<typeof CreateModelDialog>>()
const selectProviderRef = ref<InstanceType<typeof SelectProviderDialog>>()

const modelOptions = ref<any>(null)
const AIModeParamSettingDialogRef = ref<InstanceType<typeof AIModeParamSettingDialog>>()
const validate = () => {
    return aiChatNodeFormRef.value?.validate().catch((err) => {
        return Promise.reject({ node: props.nodeModel, errMessage: err })
    })
}

function getModel() {
    getAiList().then(res => {
        modelOptions.value = res?.data.data;
    })
}

const openCreateModel = (provider?: Provider) => {
    if (provider) {
        createModelRef.value?.open(provider)
    } else {
        selectProviderRef.value?.open()
    }
}

function refreshParam(data: any) {
    set(props.nodeModel.properties.node_data, 'model_params_setting', data)
}

onMounted(() => {
    getModel()
    if (typeof props.nodeModel.properties.node_data?.is_result === 'undefined') {
        if (isLastNode(props.nodeModel)) {
            set(props.nodeModel.properties.node_data, 'is_result', true)
        }
    }
    set(props.nodeModel, 'validate', validate)
    if (!chat_data.value.dialogue_type) {
        chat_data.value.dialogue_type = 'WORKFLOW'
    }
})
</script>
<style lang="scss" scoped>
/* 未加载模型的状态标记：禁用了但写清原因，不让用户以为“模型丢了” */
.model-status {
    margin-left: var(--space-xs);
    font-size: var(--text-2xs);
    color: var(--el-text-color-placeholder);
}

:deep(.el-form-item--label-top .el-form-item__label) {
    padding-right: 0;
}
</style>
