<template>
    <el-card shadow="always" class="workflow-control-card">
        <div class="ctrl-col">
            <el-button link title="放大" @click="zoomIn">
                <el-icon :size="16"><ZoomIn /></el-icon>
            </el-button>
            <el-button link title="缩小" @click="zoomOut">
                <el-icon :size="16"><ZoomOut /></el-icon>
            </el-button>
            <el-button link title="适应视图" @click="fitView">
                <el-icon :size="16"><FullScreen /></el-icon>
            </el-button>
        </div>
        <el-divider direction="vertical" />
        <el-button link type="primary" title="自动排版（重叠会自动展开）" @click="autoLayout">
            自动排版
        </el-button>
        <el-divider direction="vertical" />
        <el-button link @click="retract">
            <el-tooltip effect="dark" content="收起全部节点" placement="top">
                <el-icon :size="16"><Fold /></el-icon>
            </el-tooltip>
        </el-button>
        <el-button link @click="extend">
            <el-tooltip effect="dark" content="展开全部节点" placement="top">
                <el-icon :size="16"><Expand /></el-icon>
            </el-tooltip>
        </el-button>
    </el-card>
</template>

<script setup lang="ts">
import { collapseEvery, expandEvery } from '@/pages/workflow/lib/context'

const props = defineProps({
    lf: Object as any,
    /** 画布传入的自动排版（带重叠自检） */
    onAutoLayout: Function
})

/** LogicFlow 自带缩放，不手搓 */
function zoomIn() {
    props.lf?.zoom(true)
}
function zoomOut() {
    props.lf?.zoom(false)
}
function fitView() {
    props.lf?.resetZoom()
    props.lf?.resetTranslate()
    props.lf?.fitView(40)
}

function autoLayout() {
    if (props.onAutoLayout) {
        props.onAutoLayout()
        return
    }
    // 兜底：直接跑 dagre（无自检）
    props.lf?.extension?.dagre?.layout?.({ rankdir: 'LR', nodesep: 80, ranksep: 120 })
    props.lf?.fitView?.(24)
}

/**
 * 批量收起/展开：只改共享的 Vue ref。
 * 各 NodeContainer watch 它后更新本地展开态 —— LogicFlow 模型不是响应式对象，
 * 直接改 `properties.showNode` 界面不会动。
 */
const retract = () => {
    collapseEvery()
}
const extend = () => {
    expandEvery()
}
</script>
<style scoped>
.ctrl-col {
    display: inline-flex;
    align-items: center;
}
.workflow-control-card {
    --el-card-padding: 8px 12px;
    --el-card-border-radius: 8px;
}
</style>
