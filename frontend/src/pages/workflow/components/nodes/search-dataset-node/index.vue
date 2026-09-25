<template>
    <NodeContainer :nodeModel="nodeModel">
        <!--
          「必要信息」只读摘要：检索类节点在画布上最该一眼看到的是「查哪几个库」。
          知识库数远多于 2 个时只列前 2 个 + 「等 N 个」，避免把卡片撑宽。
        -->
        <template #essential>
            知识库 ·
            <template v-if="form_data.dataset_id_list?.length">
                {{ form_data.dataset_id_list.slice(0, 2)
                    .map((i: any) => (relatedObject(datasetList, i, 'id') as any)?.name)
                    .filter(Boolean).join('、')
                }}<template v-if="form_data.dataset_id_list.length > 2"> 等 {{ form_data.dataset_id_list.length }} 个</template>
            </template>
            <template v-else>未选择</template>
        </template>
        <h5 class="title-decoration-1 mb-8">节点设置</h5>
        <el-card shadow="never" class="card-never">
            <el-form @submit.prevent :model="form_data" label-position="top" require-asterisk-position="right"
                label-width="auto" ref="DatasetNodeFormRef">
                <el-form-item label="选择知识库">
                    <template #label>
                        <div class="flex-between">
                            <span>选择知识库</span>
                            <el-button type="primary" link @click="openDatasetDialog">
                                <el-icon>
                                    <Plus />
                                </el-icon>
                            </el-button>
                        </div>
                    </template>
                    <div class="w-full">
                        <el-text type="info" v-if="form_data.dataset_id_list?.length === 0">
                            关联的知识库展示在这里
                        </el-text>
                        <template v-for="item in form_data.dataset_id_list" :key="item" v-else>
                            <div class="flex-between border border-r-4 white-bg mb-4" style="padding: 5px 8px">
                                <div class="flex align-center" style="line-height: 20px">
                                    <!--
                                      图标分流：契约里 `KnowledgeBase.icon` 是 **emoji 字符**（`📘`），
                                      不是 URL。只有 http 开头的才当图片，其余当文字直接渲染。
                                      而且**不能套 `avatar-*` 色块** —— emoji 自身有色彩，
                                      压在紫色底上很脏（用户报的「背景图也有问题」就是它）。
                                    -->
                                    <img v-if="iconOf(item).startsWith('http')" class="mr-8 kb-icon" :src="iconOf(item)"
                                        alt="" />
                                    <span v-else class="mr-8 kb-emoji">{{ iconOf(item) || '📘' }}</span>

                                    <div class="ellipsis" :title="(relatedObject(datasetList, item, 'id') as any)?.name">
                                        {{ (relatedObject(datasetList, item, 'id') as any)?.name }}
                                    </div>
                                </div>
                                <el-button text @click="removeDataset(item)">
                                    <el-icon>
                                        <Close />
                                    </el-icon>
                                </el-button>
                            </div>
                        </template>
                    </div>
                </el-form-item>
                <el-form-item label="检索参数">
                    <template #label>
                        <div class="flex-between">
                            <span>检索参数</span>
                            <el-button type="primary" link @click="openParamSettingDialog">
                                <el-icon>
                                    <Setting />
                                </el-icon>
                            </el-button>
                        </div>
                    </template>
                    <div class="w-full">
                        <el-row>
                            <el-col :span="12" class="color-secondary lighter">检索模式</el-col>
                            <el-col :span="12" class="lighter">
                                {{
        SearchMode[form_data.dataset_setting.search_mode as keyof typeof SearchMode]
    }}</el-col>
                            <el-col :span="12" class="color-secondary lighter"> 相似度高于</el-col>
                            <el-col :span="12" class="lighter">
                                {{ form_data.dataset_setting.similarity?.toFixed(3) }}</el-col>
                            <el-col :span="12" class="color-secondary lighter"> 引用分段 Top</el-col>
                            <el-col :span="12" class="lighter"> {{ form_data.dataset_setting.top_n }}</el-col>
                            <el-col :span="12" class="color-secondary lighter"> 最大引用字符数</el-col>
                            <el-col :span="12" class="lighter">
                                {{ form_data.dataset_setting.max_paragraph_char_number }}</el-col>
                        </el-row>
                    </div>
                </el-form-item>
                <el-form-item label="检索问题" prop="question_reference_address" :rules="{
        message: '请选择检索问题',
        trigger: 'blur',
        required: true
    }">
                    <NodeCascader ref="nodeCascaderRef" :nodeModel="nodeModel" class="w-full" placeholder="请选择检索问题"
                        v-model="form_data.question_reference_address" />
                </el-form-item>
            </el-form>
        </el-card>
        <ParamSettingDialog ref="ParamSettingDialogRef" @refresh="refreshParam" />
        <AddDatasetDialog ref="AddDatasetDialogRef" @addData="addDataset" :data="datasetList" @refresh="refresh"
            :loading="datasetLoading" />
    </NodeContainer>
</template>
<script setup lang="ts">
import { set } from 'lodash-es'
import NodeContainer from '@/pages/workflow/components/common/NodeContainer.vue'
import NodeCascader from '@/pages/workflow/components/common/NodeCascader.vue'
import AddDatasetDialog from '@/pages/workflow/components/common/AddDatasetDialog.vue'
import ParamSettingDialog from '@/pages/workflow/components/common/ParamSettingDialog.vue'
import type { FormInstance } from 'element-plus'
import { ref, computed, onMounted } from 'vue'
import { relatedObject } from '@/utils/utils'
import { SearchMode } from '@/pages/workflow/enums/application'
import { allList } from '@/api/legacy/document';

const props = defineProps<{ nodeModel: any }>()
const nodeCascaderRef = ref()
const form = {
    dataset_id_list: [],
    dataset_setting: {
        top_n: 3,
        similarity: 0.6,
        max_paragraph_char_number: 5000,
        search_mode: 'embedding'
    },
    question_reference_address: []
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

const DatasetNodeFormRef = ref<FormInstance>()
const ParamSettingDialogRef = ref<InstanceType<typeof ParamSettingDialog>>()
const AddDatasetDialogRef = ref<InstanceType<typeof AddDatasetDialog>>()
const datasetList = ref<any>([])

/** 取某知识库的图标字符串（契约里是 emoji，不是 URL） */
const iconOf = (id: string): string => {
    const icon = (relatedObject(datasetList.value, id, 'id') as any)?.icon
    return typeof icon === 'string' ? icon : ''
}
const datasetLoading = ref(false)

function refreshParam(data: any) {
    set(props.nodeModel.properties.node_data, 'dataset_setting', data.dataset_setting)
}

const openParamSettingDialog = () => {
    ParamSettingDialogRef.value?.open(form_data.value, 'WORK_FLOW')
}

function removeDataset(id: any) {
    const list = props.nodeModel.properties.node_data.dataset_id_list.filter((v: any) => v !== id)
    set(props.nodeModel.properties.node_data, 'dataset_id_list', list)
}

function addDataset(val: Array<string>) {
    set(props.nodeModel.properties.node_data, 'dataset_id_list', val)
}

function openDatasetDialog() {
    if (AddDatasetDialogRef.value) {
        AddDatasetDialogRef.value.open(form_data.value.dataset_id_list)
    }
}

function getDataset() {
    // if (id) {
    //     // application.asyncGetApplicationDataset(id, datasetLoading).then((res: any) => {
    //     //     datasetList.value = res.data
    //     // })
    // } else {
    //     // dataset.asyncGetAllDataset(datasetLoading).then((res: any) => {
    //     //     datasetList.value = res.data?.filter((v: any) => v.user_id === user.userInfo?.id)
    //     // })
    // }
    allList().then((res: any) => {
        datasetList.value = res.data.data
    })
}
function refresh() {
    getDataset()
}

const validate = () => {
    return Promise.all([
        nodeCascaderRef.value.validate(),
        DatasetNodeFormRef.value?.validate()
    ]).catch((err) => {
        return Promise.reject({ node: props.nodeModel, errMessage: err })
    })
}

onMounted(() => {
    getDataset()
    set(props.nodeModel, 'validate', validate)
})
</script>
<style lang="scss" scoped>
:deep(.el-form-item--label-top .el-form-item__label) {
    padding-right: 0;
}
</style>
