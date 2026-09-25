<template>
    <el-dialog v-model="dialogVisible" width="600px" :close-on-click-modal="false" :close-on-press-escape="false"
        :destroy-on-close="true" :before-close="close" append-to-body>
        <template #header>
            <el-breadcrumb separator=">">
                <el-breadcrumb-item>
                    <span @click="toSelectProvider" class="select-provider">选择供应商</span></el-breadcrumb-item>
                <el-breadcrumb-item><span class="active-breadcrumb">{{
        `添加 ${(providerValue as any)?.dictValue ?? (providerValue as any)?.name ?? ''}`
    }}</span></el-breadcrumb-item>
            </el-breadcrumb>
        </template>
        <el-tabs v-model="activeName">
            <el-tab-pane label="基础信息" name="base-info">
                <DynamicsForm v-model="form_data" :fields="model_form_field"
                    ref="dynamicsFormRef" class="mb-24">
                    <template #default>
                        <el-form-item prop="name" :rules="base_form_data_rule.name">
                            <template #label>
                                <div class="flex align-center" style="display: inline-flex">
                                    <div class="mr-4">
                                        <span>模型名称 </span>
                                    </div>
                                    <el-tooltip effect="dark" placement="right">
                                        <template #content>
                                            <p>自定义的模型名称</p>
                                        </template>
                                        <WorkFlowIcon iconName="app-warning" class="app-warning-icon"></WorkFlowIcon>
                                    </el-tooltip>
                                </div>
                            </template>
                            <el-input v-model="base_form_data.name" maxlength="64" show-word-limit
                                placeholder="请给基础模型设置一个名称" />
                        </el-form-item>
                        <el-form-item prop="permission_type" :rules="base_form_data_rule.permission_type">
                            <template #label>
                                <span>权限</span>
                            </template>
                            <el-radio-group v-model="base_form_data.permission_type" class="card__radio">
                                <el-row :gutter="16">
                                    <template v-for="(value, key) of PermissionType" :key="key">
                                        <el-col :span="12">
                                            <el-card shadow="never" class="mb-16"
                                                :class="base_form_data.permission_type === key ? 'active' : ''">
                                                <el-radio :value="key" size="large">
                                                    <p class="mb-4">{{ value }}</p>
                                                    <el-text type="info">
                                                        {{ PermissionDesc[key] }}
                                                    </el-text>
                                                </el-radio>
                                            </el-card>
                                        </el-col>
                                    </template>
                                </el-row>
                            </el-radio-group>
                        </el-form-item>
                        <el-form-item prop="model_type" :rules="base_form_data_rule.model_type">
                            <template #label>
                                <div class="flex align-center" style="display: inline-flex">
                                    <span class="mr-4">模型类型 </span>
                                    <!-- <el-tooltip effect="dark" placement="right">
                                        <template #content>
                                            <p>大语言模型：在应用中与AI对话的推理模型。</p>
                                            <p>
                                                向量模型：在知识库中导入文档进行向量化和向量检索召回分段时使用的向量模型。
                                            </p>
                                            <p>
                                                重排模型：在二次召回中根据召回的候选分段和用户问题的匹配度重新排序，从而得到更精确的结果。
                                            </p>
                                            <p>语音识别：在应用中开启语音识别后用于语音转文字的模型。</p>
                                            <p>语音合成：在应用中开启语音播放后用于文字转语音的模型。</p>
                                        </template>
                <WorkFlowIcon iconName="app-warning" class="app-warning-icon"></WorkFlowIcon>
                </el-tooltip> -->
                                </div>
                            </template>
                            <el-select v-loading="model_type_loading" @change="list_base_model($event, true)"
                                v-model="base_form_data.model_type" class="w-full m-2" placeholder="请选择模型类型">
                                <el-option v-for="item in model_type_list" :key="item.value" :label="item.key"
                                    :value="item.value"></el-option>
                            </el-select>
                        </el-form-item>

                        <el-form-item prop="model_name" :rules="base_form_data_rule.model_name">
                            <template #label>
                                <div class="flex align-center" style="display: inline-flex">
                                    <div class="mr-4">
                                        <span>基础模型 </span>
                                        <!-- <span class="danger">列表中未列出的模型，直接输入模型名称，回车即可添加</span> -->
                                    </div>
                                </div>
                            </template>
                            <el-select @change="getModelForm($event)" v-loading="base_model_loading"
                                v-model="base_form_data.model_name" class="w-full m-2" placeholder="自定义输入基础模型后回车即可"
                                default-first-option>
                                <el-option v-for="item in base_model_list" :key="item.name" :value="(item as any).modelName">
                                    <template #default>
                                        <div class="flex align-center" style="display: inline-flex">
                                            <div class="flex-between mr-4">
                                                <span>{{ (item as any).modelName }} </span>
                                            </div>
                                            <!-- <el-tooltip effect="dark" placement="right" v-if="item.desc">
                                                <template #content>
                                                    <p class="w-280">{{ item.desc }}</p>
                                                </template>
                        <WorkFlowIcon iconName="app-warning" class="app-warning-icon">
                        </WorkFlowIcon>
                        </el-tooltip> -->
                                        </div>
                                    </template>
                                </el-option>
                            </el-select>
                        </el-form-item>
                    </template>
                </DynamicsForm>
            </el-tab-pane>
            <el-tab-pane label="高级设置" name="advanced-info">
                <div class="flex-between mb-8">
                    <h5>模型参数</h5>
                    <el-button type="primary" link @click.stop="openAddDrawer()">
                        <WorkFlowIcon iconName="Plus" class="add-icon" />添加
                    </el-button>
                </div>
                <el-table :data="base_form_data.model_params_form" v-if="base_form_data.model_params_form?.length > 0"
                    class="mb-16">
                    <el-table-column prop="label" label="显示名称" show-overflow-tooltip>
                        <template #default="{ row }">
                            <span v-if="row.label && row.label.input_type === 'TooltipLabel'">{{
        row.label.label
    }}</span>
                            <span v-else>{{ row.label }}</span>
                        </template>
                    </el-table-column>
                    <el-table-column prop="field" label="参数" show-overflow-tooltip />
                    <el-table-column label="组件类型" width="110px">
                        <template #default="{ row }">
                            <el-tag type="info" class="info-tag">{{
        input_type_list.find((item) => item.value === row.input_type)?.label
    }}</el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column prop="default_value" label="默认值" show-overflow-tooltip />
                    <el-table-column label="必填">
                        <template #default="{ row }">
                            <div @click.stop>
                                <el-switch disabled size="small" v-model="row.required" />
                            </div>
                        </template>
                    </el-table-column>

                    <el-table-column label="操作" align="left" width="80">
                        <template #default="{ row, $index }">
                            <span class="mr-4">
                                <el-tooltip effect="dark" content="修改" placement="top">
                                    <el-button type="primary" text @click.stop="openAddDrawer(row, $index)">
                                        <el-icon>
                                            <EditPen />
                                        </el-icon>
                                    </el-button>
                                </el-tooltip>
                            </span>
                            <el-tooltip effect="dark" content="删除" placement="top">
                                <el-button type="primary" text @click="deleteParam($index)">
                                    <el-icon>
                                        <Delete />
                                    </el-icon>
                                </el-button>
                            </el-tooltip>
                        </template>
                    </el-table-column>
                </el-table>
            </el-tab-pane>
        </el-tabs>
        <template #footer>
            <span class="dialog-footer">
                <el-button @click="close">取消</el-button>
                <el-button type="primary" @click="submit" :loading="loading"> 添加 </el-button>
            </span>
        </template>
    </el-dialog>
    <AddParamDrawer ref="AddParamRef" @refresh="refresh" />
</template>
<script setup lang="ts">
import { ref, computed } from 'vue'
import type { Provider, BaseModel } from '@/api/type/model'
import type { Dict, KeyValue } from '@/api/type/common'
import { getAiList, getModelFormItem, getModelParams } from '@/api/legacy/setting'
import type { FormField } from '@/components/dynamic-form/types'
import DynamicsForm from '@/components/dynamic-form/DynamicForm.vue'
import type { FormRules } from 'element-plus'
import { MsgError, MsgWarning } from '@/utils/message'
import { PermissionType, PermissionDesc } from '@/pages/workflow/enums/model'
import { input_type_list } from '@/components/dynamic-form/constructor/data'
import AddParamDrawer from '@/pages/workflow/components/common/AddParamDrawer.vue'

const providerValue = ref<Provider>()
const dynamicsFormRef = ref<InstanceType<typeof DynamicsForm>>()
const emit = defineEmits(['change', 'submit'])
const loading = ref<boolean>(false)
const model_type_loading = ref<boolean>(false)
const base_model_loading = ref<boolean>(false)
const model_type_list = ref<Array<KeyValue<string, string>>>([])

const base_model_list = ref<Array<BaseModel>>()
const model_form_field = ref<Array<FormField>>([])
const dialogVisible = ref<boolean>(false)
const activeName = ref('base-info')
const AddParamRef = ref()

const base_form_data_rule = ref<FormRules>({
    name: { required: true, trigger: 'blur', message: '模型名称不能为空' },
    permission_type: { required: true, trigger: 'change', message: '权限不能为空' },
    model_type: { required: true, trigger: 'change', message: '模型类型不能为空' },
    model_name: { required: true, trigger: 'change', message: '基础模型不能为空' }
})

const base_form_data = ref<{
    name: string
    permission_type: string
    model_type: string
    model_name: string
    model_params_form: any
}>({ name: '', model_type: '', model_name: '', permission_type: 'PRIVATE', model_params_form: [] })

const credential_form_data = ref<Dict<any>>({})

const form_data = computed({
    get: () => {
        return {
            ...credential_form_data.value,
            name: base_form_data.value.name,
            model_type: base_form_data.value.model_type,
            model_name: base_form_data.value.model_name,
            permission_type: base_form_data.value.permission_type,
            model_params_form: base_form_data.value.model_params_form
        }
    },
    set: (event: any) => {
        credential_form_data.value = event
    }
})

const getModelForm = (_model_name: string) => {
    if (!form_data.value.model_type) {
        MsgWarning('请先选择模型类型')
        base_form_data.value.model_name = ''
        return
    }
    if (providerValue.value) {
        const supplier = (providerValue.value as any).dictKey ?? (providerValue.value as any).provider
        getModelFormItem({ supplier, modelType: form_data.value.model_type }).then((res) => {
            model_form_field.value = (res.data.data as any)?.form_item ?? []
        })

        getModelParams({ supplier, modelType: form_data.value.model_type }).then((res) => {
            base_form_data.value.model_params_form = res.data.data
        })
    }
}

const open = (provider: Provider) => {
    // ModelApi.listModelType(provider.provider, model_type_loading).then((ok) => {
    //     model_type_list.value = ok.data
    // })
    let tmpData = [
        {
            "key": "文本模型",
            "value": "text"
        },
        {
            "key": "向量模型",
            "value": "embedding"
        },
        // {
        //     "key": "图片合成",
        //     "value": "image"
        // },
        // {
        //     "key": "重拍模型",
        //     "value": "rerank"
        // },
    ]
    model_type_list.value = tmpData;
    providerValue.value = provider
    dialogVisible.value = true
    activeName.value = 'base-info'
}

const list_base_model = (model_type: any, change?: boolean) => {
    console.log(model_type);

    if (change) {
        base_form_data.value.model_name = ''
    }
    if (providerValue.value) {
        // getAiList 契约签名无参；supplier/modelType 过滤待接口补齐
        void model_type
        getAiList().then((res) => {
            base_model_list.value = res.data.data as any
        })
    }
}

const close = () => {
    base_form_data.value = {
        name: '',
        model_type: '',
        model_name: '',
        permission_type: 'PRIVATE',
        model_params_form: []
    }
    credential_form_data.value = {}
    model_form_field.value = []
    base_model_list.value = []
    dialogVisible.value = false
}
const submit = () => {
    let tmp = {
        ...base_form_data.value,
        credential: credential_form_data.value,
        provider: (providerValue.value as any)?.dictKey ?? (providerValue.value as any)?.provider
    }
    console.log(tmp);

    dynamicsFormRef.value?.validate().then(() => {
        if (providerValue.value) {
            // ModelApi.createModel(
            //     {
            //         ...base_form_data.value,
            //         credential: credential_form_data.value,
            //         provider: providerValue.value.provider
            //     },
            //     loading
            // ).then((ok) => {
            //     close()
            //     MsgSuccess('创建模型成功')
            //     emit('submit')
            // })
        }
    })
}

function openAddDrawer(data?: any, index?: any) {
    AddParamRef.value?.open(data, index)
}

function deleteParam(index: any) {
    base_form_data.value.model_params_form.splice(index, 1)
}

function refresh(data: any, index: any) {
    for (let i = 0; i < base_form_data.value.model_params_form.length; i++) {
        let field = base_form_data.value.model_params_form[i].field
        let label = base_form_data.value.model_params_form[i].label
        if (label && label.input_type === 'TooltipLabel') {
            label = label.label
        }
        let label2 = data.label
        if (label2 && label2.input_type === 'TooltipLabel') {
            label2 = label2.label
        }

        if (field === data.field && index !== i) {
            MsgError('变量已存在: ' + data.field)
            return
        }
        if (label === label2 && index !== i) {
            MsgError('变量已存在: ' + label)
            return
        }
    }
    if (index !== null) {
        base_form_data.value.model_params_form.splice(index, 1, data)
    } else {
        base_form_data.value.model_params_form.push(data)
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
