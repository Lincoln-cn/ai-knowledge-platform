<template>
    <NodeContainer :nodeModel="nodeModel">
        <!-- {{ nodeModel.properties }} -->
        <el-form @submit.prevent :model="form_data" label-position="top" require-asterisk-position="right" class="mb-24"
            label-width="auto" ref="baseNodeFormRef">
            <!--
              名称/描述**不再必填**。
              旧系统靠流程里的这两个字段给应用命名（发布时把它写回应用实体）；
              新模型里应用名称与描述属于 `Assistant`（在「应用管理」里改和维护），
              流程节点再要求一遍就会变成「怎么填都发布不了」的陷阱。
              这两个输入框先留着（旧流程数据里可能带值），但不再拦发布。
            -->
            <el-form-item label="应用名称">
                <el-input v-model="form_data.name" maxlength="20" placeholder="（可选，正式名称请在「应用管理」里改）"
                    show-word-limit @blur="form_data.name = form_data.name?.trim()" />
            </el-form-item>
            <el-form-item label="应用描述">
                <el-input v-model="form_data.desc" placeholder="（可选，正式描述请在「应用管理」里改）" :rows="3" type="textarea"
                    maxlength="200" show-word-limit />
            </el-form-item>
            <el-form-item label="开场白">
                <MdEditorMagnify @wheel="wheel" title="开场白" v-model="form_data.prologue" style="height: 150px"
                    @submitDialog="submitDialog" />
            </el-form-item>
            <el-form-item>
                <template #label>
                    <div class="flex-between">
                        <div class="flex align-center">
                            <span class="mr-4">文件上传</span>
                            <el-tooltip effect="dark" content="开启后，问答页面会显示上传文件的按钮。" placement="right">
                                <WorkFlowIcon iconName="app-warning" class="app-warning-icon"></WorkFlowIcon>
                            </el-tooltip>
                        </div>
                        <div>
                            <!-- <el-button v-if="form_data.file_upload_enable" type="primary" link
                                @click="openFileUploadSettingDialog" class="mr-4">
                                <el-icon class="mr-4">
                                    <Setting />
                                </el-icon>
                            </el-button> -->
                            <el-switch size="small" v-model="form_data.file_upload_enable" @change="switchFileUpload" />
                        </div>
                    </div>
                </template>
            </el-form-item>
            <UserInputFieldTable ref="UserInputFieldTableFef" :node-model="nodeModel" />
            <ApiInputFieldTable ref="ApiInputFieldTableFef" :node-model="nodeModel" />

        </el-form>
        <!-- <TTSModeParamSettingDialog ref="TTSModeParamSettingDialogRef" @refresh="refreshTTSForm" /> -->
        <FileUploadSettingDialog ref="FileUploadSettingDialogRef" :node-model="nodeModel"
            @refresh="refreshFileUploadForm" />
    </NodeContainer>
</template>
<script setup lang="ts">
import { set } from 'lodash-es'
import NodeContainer from '@/pages/workflow/components/common/NodeContainer.vue'
import type { FormInstance } from 'element-plus'
import { ref, computed, onMounted } from 'vue'
import ApiInputFieldTable from './component/ApiInputFieldTable.vue'
import UserInputFieldTable from './component/UserInputFieldTable.vue'
import FileUploadSettingDialog from '@/pages/workflow/components/nodes/base-node/component/FileUploadSettingDialog.vue'

const props = defineProps<{ nodeModel: any }>()

const UserInputFieldTableFef = ref()
const ApiInputFieldTableFef = ref()
const FileUploadSettingDialogRef = ref<InstanceType<typeof FileUploadSettingDialog>>()

const form = {
    name: '',
    desc: '',
    prologue: '您好，我是 XXX 智能体，您可以向我提出 XXX 使用问题。\n- XXX 主要功能有什么？\n- XXX 如何收费？\n- 需要转人工服务'
}

const wheel = (e: any) => {
    if (e.ctrlKey === true) {
        e.preventDefault()
        return true
    } else {
        e.stopPropagation()
        return true
    }
}

function submitDialog(val: string) {
    set(props.nodeModel.properties.node_data, 'prologue', val)
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

const baseNodeFormRef = ref<FormInstance>()

const validate = () => {
    if (
        form_data.value.tts_model_enable &&
        !form_data.value.tts_model_id &&
        form_data.value.tts_type === 'TTS'
    ) {
        return Promise.reject({ node: props.nodeModel, errMessage: '请选择语音播放模型' })
    }
    if (form_data.value.stt_model_enable && !form_data.value.stt_model_id) {
        return Promise.reject({ node: props.nodeModel, errMessage: '请选择语音输入模型' })
    }
    return baseNodeFormRef.value?.validate().catch((err) => {
        return Promise.reject({ node: props.nodeModel, errMessage: err })
    })
}

function getProvider() {
    // 旧 store 的供应商列表已去掉；新建模型弹窗自己拉目录
}

const openFileUploadSettingDialog = () => {
    FileUploadSettingDialogRef.value?.open(form_data.value.file_upload_setting)
}
// 模板里设置按钮暂被注释，保留函数引用避免 TS6133
void openFileUploadSettingDialog

const refreshFileUploadForm = (data: any) => {
    form_data.value.file_upload_setting = data
}

const switchFileUpload = () => {
    const default_upload_setting = {
        maxFiles: 3,
        fileLimit: 50,
        document: true,
        image: false,
        audio: false,
        video: false
    }

    if (form_data.value.file_upload_enable) {
        form_data.value.file_upload_setting = form_data.value.file_upload_setting || default_upload_setting
    }
    props.nodeModel.graphModel.eventCenter.emit('refreshFileUploadConfig')
}

onMounted(() => {
    set(props.nodeModel, 'validate', validate)
    if (!props.nodeModel.properties.node_data.tts_type) {
        set(props.nodeModel.properties.node_data, 'tts_type', 'BROWSER')
    }
    getProvider()
})
</script>
<style lang="scss" scoped>
:deep(.el-form-item__label) {
    display: block;
}

:deep(.el-form-item--label-top .el-form-item__label) {
    padding-right: 0;
}
</style>
