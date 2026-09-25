<template>
    <div v-show="show" class="workflow-dropdown-menu">
        <el-tabs v-model="activeName" class="workflow-dropdown-tabs">
            <el-tab-pane label="基础组件" name="base">
                <el-scrollbar height="420">
                    <el-input v-model="search_text" class="mb-8" placeholder="按名称搜索" clearable />
                    <template v-if="groupedNodes.length > 0">
                        <template v-for="group in groupedNodes" :key="group.category">
                            <div class="category-title label">{{ group.category }}</div>
                            <template v-for="item in group.items" :key="item.type">
                                <div
                                    class="workflow-dropdown-item"
                                    :class="
                                        item.implemented && !isExhausted(item) ? 'cursor' : 'is-planned'
                                    "
                                    :title="item.description"
                                    @click.stop="item.implemented && clickCatalog(item)"
                                >
                                    <component
                                        :is="iconComponent(`${item.type}-icon`)"
                                        class="item-icon"
                                        :size="32"
                                    />
                                    <div class="item-body">
                                        <div class="item-name">
                                            {{ item.label }}
                                            <el-tag v-if="!item.implemented" size="small" type="info" class="ml-4">
                                                规划中
                                            </el-tag>
                                            <el-tag v-else-if="isExhausted(item)" size="small" type="warning" class="ml-4">
                                                已存在
                                            </el-tag>
                                        </div>
                                        <div class="item-desc ellipsis">{{ item.description }}</div>
                                    </div>
                                </div>
                            </template>
                        </template>
                    </template>
                    <div v-else class="picker-empty">
                        <el-text type="info">没有找到相关结果</el-text>
                    </div>
                </el-scrollbar>
            </el-tab-pane>
        </el-tabs>
    </div>
</template>
<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { cloneDeep } from 'lodash-es'
import { listNodeTypes } from '@/api/workflow'
import type { NodeTypeMeta } from '@/api/workflow'
import { nodeDict } from '@/pages/workflow/components/common/data'
import { iconComponent } from '@/pages/workflow/components/icons/utils'
import {
    rejectAddIfSingleton,
    isSingletonType,
    toNodeList
} from '@/pages/workflow/lib/node-actions'

const search_text = ref<string>('')
const props = defineProps({
    show: {
        type: Boolean,
        default: false
    },
    id: {
        type: String,
        default: ''
    },
    workflowRef: Object
})

const emit = defineEmits(['clickNodes'])

const activeName = ref('base')
/** 契约节点目录（17 项）。**唯一真相源** —— 不再用 data.ts 的 menuNodes */
const catalog = ref<NodeTypeMeta[]>([])

/**
 * 画布里已存在的 type 集合。
 * 用 `workflowRef.getGraphData()` 读（Canvas 已 defineExpose），不新开状态通道；
 * 面板每次打开时刷新，加完节点也刷新，保证「已存在」标记跟得上。
 */
const existingTypes = ref<Set<string>>(new Set())

function refreshExistingTypes() {
    try {
        const nodes = toNodeList(props.workflowRef?.getGraphData?.())
        existingTypes.value = new Set(nodes.map((n) => String(n.type ?? '')))
    } catch {
        existingTypes.value = new Set()
    }
}

watch(
    () => props.show,
    (visible) => {
        if (visible) refreshExistingTypes()
    }
)

/** 单例且图里已有 → 不可再加（列表里给「已存在」标记，点击会弹原因） */
function isExhausted(item: NodeTypeMeta): boolean {
    const singleton = item.singleton || isSingletonType(item.type)
    return singleton && existingTypes.value.has(item.type)
}

/** category 出现顺序，与契约下发顺序一致 */
const categoryOrder = ['基础', '预处理', '数据', '模型', '逻辑', '输出', '扩展', '多模态']

const groupedNodes = computed(() => {
    const q = search_text.value.trim().toLocaleLowerCase()
    const filtered = catalog.value.filter(
        (item) =>
            !q ||
            item.label.toLocaleLowerCase().includes(q) ||
            item.type.toLocaleLowerCase().includes(q)
    )
    const map = new Map<string, NodeTypeMeta[]>()
    for (const item of filtered) {
        const list = map.get(item.category) ?? []
        list.push(item)
        map.set(item.category, list)
    }
    const keys = [...map.keys()].sort((a, b) => {
        const ia = categoryOrder.indexOf(a)
        const ib = categoryOrder.indexOf(b)
        return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib)
    })
    return keys.map((category) => ({ category, items: map.get(category)! }))
})

/**
 * 目录管「有哪些」，nodeDict 管「拖出来长什么样」。
 * 规划中的节点不构造 shape，点击直接忽略。
 * 单例节点若图里已有，走统一闸门拒绝并给出可读原因（不静默忽略）。
 */
function clickCatalog(meta: NodeTypeMeta) {
    if (!meta.implemented) return
    refreshExistingTypes()
    if (rejectAddIfSingleton(props.workflowRef?.getGraphData?.(), meta.type, meta.singleton)) return
    const dict = nodeDict[meta.type]
    if (!dict) return
    const shapeItem = {
        type: meta.type,
        text: meta.description,
        label: meta.label,
        height: dict.height,
        properties: cloneDeep(dict.properties ?? {})
    }
    props.workflowRef?.addNode(shapeItem)
    emit('clickNodes', shapeItem)
    refreshExistingTypes()
}

onMounted(() => {
    listNodeTypes()
        .then((list) => {
            catalog.value = list ?? []
        })
        .catch(() => {
            catalog.value = []
        })
})
</script>
<style lang="scss" scoped>
.workflow-dropdown-menu {
    -moz-user-select: none;
    -webkit-user-select: none;
    -ms-user-select: none;
    -khtml-user-select: none;
    -o-user-select: none;
    user-select: none;
    position: absolute;
    top: 56px;
    right: 134px;
    z-index: var(--z-dropdown);
    width: 300px;
    box-shadow: var(--shadow-float);
    background: var(--color-surface);
    border: 1px solid var(--color-rule);
    border-radius: var(--radius-lg);
    padding-bottom: var(--space-xs);

    /* 分类：mono 全大写小标签（.label 的口径，这里补上中文场景的字距） */
    .category-title {
        padding: var(--space-xs) var(--space-sm) var(--space-2xs);
        font-family: var(--font-mono);
        font-size: var(--text-2xs);
        font-weight: 500;
        letter-spacing: var(--tracking-label);
        color: var(--color-muted);
    }

    .workflow-dropdown-item {
        display: flex;
        align-items: flex-start;
        gap: var(--space-xs);
        padding: var(--space-xs) var(--space-sm);
        border-radius: var(--radius);
        transition: background var(--dur-micro) var(--ease-out);

        &:hover {
            background: var(--color-hover);
        }

        &.is-planned {
            cursor: not-allowed;
            opacity: 0.55;
        }

        .item-icon {
            flex-shrink: 0;
            margin-top: 2px;
        }

        .item-body {
            min-width: 0;
            flex: 1;
        }

        .item-name {
            display: flex;
            align-items: center;
            font-size: var(--text-sm);
            font-weight: 500;
            line-height: var(--leading-tight);
            color: var(--color-ink);
        }

        /* 一句说明：单行截断，12px 元信息档 */
        .item-desc {
            margin-top: var(--space-3xs);
            font-size: var(--text-2xs);
            line-height: var(--leading-tight);
            color: var(--color-muted);
        }
    }

    .picker-empty {
        padding: var(--space-sm) var(--space-md);
    }
}
</style>
<style lang="scss">
.workflow-dropdown-tabs {
    .el-tabs__nav-wrap {
        padding: 0 var(--space-sm);
    }
}
</style>
