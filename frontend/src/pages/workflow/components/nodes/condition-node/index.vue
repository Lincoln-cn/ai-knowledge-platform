<template>
    <NodeContainer :nodeModel="nodeModel">
        <el-form :model="form_data" label-position="top" require-asterisk-position="right" label-width="auto"
            ref="ConditionNodeFormRef" @submit.prevent>
            <template v-for="(item, index) in form_data.branch" :key="item.id">
                <el-card v-resize="(wh: any) => resizeCondition(wh, item, index)" shadow="never" class="card-never mb-8"
                    style="--el-card-padding: 12px">
                    <div class="flex-between lighter">
                        <span>
                            {{ branchTypeLabel(item.type) }}
                            <el-tag v-if="isFallback(item)" size="small" type="warning" class="ml-4">兜底</el-tag>
                        </span>
                        <el-switch v-model="item._isFallback" size="small" active-text="兜底"
                            @change="(v: any) => changeFallback(item, v)" />
                    </div>
                    <div class="mt-8">
                        <el-form-item label="分支名">
                            <el-input v-model="item.label" placeholder="例如：高风险 / 一般" maxlength="20" />
                        </el-form-item>
                        <el-form-item label="判定条件（自然语言）" :prop="'branch.' + index + '.expression'" :rules="{
                            required: true,
                            message: '请填写判定条件',
                            trigger: 'blur'
                        }">
                            <el-input v-model="item.expression" type="textarea" :rows="3"
                                placeholder="用一句话描述命中该分支的条件，由模型判定。例如：研判结论为高风险，或存在违约/付款重大隐患" />
                        </el-form-item>
                        <el-text type="info" size="small">
                            条件由模型按语义判定，不支持手写脚本表达式。
                        </el-text>
                    </div>
                    <el-button v-if="form_data.branch.length > 1" link type="info" class="mt-4"
                        @click="deleteBranch(index)">
                        <el-icon class="mr-4">
                            <Delete />
                        </el-icon> 删除分支
                    </el-button>
                </el-card>
            </template>
            <el-button link type="primary" @click="addBranch">
                <el-icon class="mr-4">
                    <Plus />
                </el-icon> 添加分支
            </el-button>
        </el-form>
    </NodeContainer>
</template>
<script setup lang="ts">
import { cloneDeep, set } from 'lodash-es'
import NodeContainer from '@/pages/workflow/components/common/NodeContainer.vue'
import type { FormInstance } from 'element-plus'
import { ref, computed, onMounted } from 'vue'
import { randomId } from '@/utils/utils'
import { branchTypeLabel } from '@/constants/labels'

/**
 * 语义判断节点编辑器。
 *
 * 每个分支只填两样：分支名 + 自然语言判定条件（expression），命中与否由模型判定。
 * **不再要求选引用变量** —— 结构化「变量/操作符/值」判定在数值判断节点里。
 * 出边仍靠分支 id 挂锚（refreshBranchAnchor / branch_condition_list）。
 */
const props = defineProps<{ nodeModel: any }>()

const makeBranch = (label: string, expression: string, isFallback: boolean) => ({
    id: randomId(),
    label,
    expression,
    type: isFallback ? 'ELSE' : 'IF',
    // 旧属性字段：结构化条件在语义判断里不用，保留空数组保证格式可逆
    condition: 'and',
    conditions: [],
    _isFallback: isFallback
})

const form = {
    branch: [makeBranch('条件 A', '', false), makeBranch('兜底', '以上条件都不命中', true)],
    _fallbackBranchId: ''
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
void wheel

const resizeCondition = (wh: any, row: any, index: number) => {
    const branch_condition_list = cloneDeep(
        props.nodeModel.properties.branch_condition_list
            ? props.nodeModel.properties.branch_condition_list
            : []
    )
    const new_branch_condition_list = branch_condition_list.map((item: any) => {
        if (item.id === row.id) {
            return { ...item, height: wh.height, index: index }
        }
        return item
    })
    set(props.nodeModel.properties, 'branch_condition_list', new_branch_condition_list)
    refreshBranchAnchor(props.nodeModel.properties.node_data.branch, true)
}

function isFallback(item: any): boolean {
    return props.nodeModel.properties.node_data?._fallbackBranchId === item.id
}

function syncFallbackTypes() {
    const nd = props.nodeModel.properties.node_data
    if (!nd) return
    const fallbackId = nd._fallbackBranchId
    let ifIndex = 0
    nd.branch.forEach((b: any) => {
        b._isFallback = b.id === fallbackId
        if (b.id === fallbackId) {
            b.type = 'ELSE'
        } else {
            b.type = ifIndex === 0 ? 'IF' : `ELSE IF ${ifIndex}`
            ifIndex += 1
        }
    })
}

const form_data = computed({
    get: () => {
        if (props.nodeModel.properties.node_data) {
            return props.nodeModel.properties.node_data
        } else {
            set(props.nodeModel.properties, 'node_data', form)
            form._fallbackBranchId = form.branch[1].id
            syncFallbackTypes()
            refreshBranchAnchor(form.branch, true)
        }
        return props.nodeModel.properties.node_data
    },
    set: (value) => {
        set(props.nodeModel.properties, 'node_data', value)
    }
})

function changeFallback(item: any, on: boolean | string | number) {
    const nd = props.nodeModel.properties.node_data
    if (!nd) return
    if (on) {
        nd._fallbackBranchId = item.id
    } else if (nd._fallbackBranchId === item.id) {
        // 关掉当前兜底时改指最后一条，保证始终有兜底出口
        const last = nd.branch[nd.branch.length - 1]
        nd._fallbackBranchId = last && last.id !== item.id ? last.id : (nd.branch[0]?.id ?? null)
    }
    syncFallbackTypes()
}

const ConditionNodeFormRef = ref<FormInstance>()
const validate = () => {
    return ConditionNodeFormRef.value?.validate().catch((err) => {
        return Promise.reject({ node: props.nodeModel, errMessage: err })
    })
}

function addBranch() {
    const list = cloneDeep(props.nodeModel.properties.node_data.branch)
    const obj = makeBranch(`分支 ${list.length}`, '', false)
    // 新分支插在兜底之前
    const fallbackIndex = list.findIndex((b: any) => b.id === props.nodeModel.properties.node_data._fallbackBranchId)
    const at = fallbackIndex >= 0 ? fallbackIndex : list.length
    list.splice(at, 0, obj)
    set(props.nodeModel.properties.node_data, 'branch', list)
    syncFallbackTypes()
    refreshBranchAnchor(list, true)
}

function deleteBranch(index: number) {
    const list = cloneDeep(props.nodeModel.properties.node_data.branch)
    const removed = list.splice(index, 1)[0]
    if (!removed) return
    // 分支没了，挂在它上面的出边一并删掉
    const deletedAnchorId = `${props.nodeModel.id}_${removed.id}_right`
    props.nodeModel.graphModel.eventCenter.emit(
        'delete_edge',
        props.nodeModel.outgoing.edges
            .filter((item: any) => item.sourceAnchorId === deletedAnchorId)
            .map((item: any) => item.id)
    )
    if (props.nodeModel.properties.node_data._fallbackBranchId === removed.id) {
        props.nodeModel.properties.node_data._fallbackBranchId = list[list.length - 1]?.id ?? null
    }
    set(props.nodeModel.properties.node_data, 'branch', list)
    syncFallbackTypes()
    refreshBranchAnchor(list, false)
}

function refreshBranchAnchor(list: Array<any>, is_add: boolean) {
    const branch_condition_list = cloneDeep(
        props.nodeModel.properties.branch_condition_list
            ? props.nodeModel.properties.branch_condition_list
            : []
    )
    const new_branch_condition_list = list
        .map((item, index) => {
            const find = branch_condition_list.find((b: any) => b.id === item.id)
            if (find) {
                return { index: index, height: find.height, id: item.id }
            } else {
                if (is_add) {
                    return { index: index, height: 12, id: item.id }
                }
            }
        })
        .filter((item) => item)

    set(props.nodeModel.properties, 'branch_condition_list', new_branch_condition_list)
    props.nodeModel.refreshBranch()
}

onMounted(() => {
    // 兜底标记与 type 展示对齐一次（可能是从旧图/契约映射进来的）
    if (props.nodeModel.properties.node_data) {
        const nd = props.nodeModel.properties.node_data
        if (!nd._fallbackBranchId) {
            const byType = nd.branch?.find((b: any) => b.type === 'ELSE')
            nd._fallbackBranchId = byType?.id ?? nd.branch?.[nd.branch.length - 1]?.id ?? null
        }
        syncFallbackTypes()
        refreshBranchAnchor(nd.branch ?? [], true)
    }
    set(props.nodeModel, 'validate', validate)
})
</script>
<style lang="scss" scoped></style>
