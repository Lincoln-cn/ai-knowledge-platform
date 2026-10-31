<template>
    <div class="workflow-page">
        <div class="top">
            <div class="flex align-center gap-8">
                <back-button @click="router.push('/assistants')"></back-button>
                <h4 class="top-title">{{ detail?.name }}</h4>
                <div v-if="showHistory && disablePublic">
                    <el-text type="info" class="ml-8 num">预览版本：
                        {{ currentVersion.name || datetimeFormat(currentVersion.update_time) }}</el-text>
                </div>
                <el-text type="info" class="ml-8 num" v-else-if="saveTime">保存时间：{{ datetimeFormat(saveTime)
                    }}</el-text>
            </div>
            <div v-if="showHistory && disablePublic" class="flex align-center">
                <el-button type="primary" class="mr-8" @click="refreshVersion()"> 恢复版本 </el-button>
                <span class="top-sep"></span>
                <el-button text @click="closeHistory">
                    <el-icon>
                        <Close />
                    </el-icon>
                </el-button>
            </div>
            <div v-else class="top-actions">
                <!-- 文字按钮 + 发丝线分隔组：添加/保存是一组，发布是唯一主按钮，更多操作单独一组 -->
                <el-button text @click="showPopover = !showPopover">
                    <el-icon class="mr-4"><Plus /></el-icon>
                    添加组件
                </el-button>
                <el-button text @click="saveApplication(true)">
                    <el-icon class="mr-4"><DocumentChecked /></el-icon>
                    保存
                </el-button>
                <span class="top-sep"></span>
                <el-button type="primary" @click="publicHandle"> 发布 </el-button>
                <span class="top-sep"></span>
                <el-dropdown trigger="click">
                    <el-button text @click.stop>
                        <el-icon class="rotate-90">
                            <MoreFilled />
                        </el-icon>
                    </el-button>
                    <template #dropdown>
                        <el-dropdown-menu>
                            <el-dropdown-item @click="openHistory">
                                <el-icon class="mr-4"><Clock /></el-icon>
                                发布历史
                            </el-dropdown-item>
                            <el-dropdown-item>
                                <el-icon class="mr-4"><FolderChecked /></el-icon>
                                自动保存
                                <div class="ml-4">
                                    <el-switch size="small" v-model="isSave" @change="changeSave" />
                                </div>
                            </el-dropdown-item>
                        </el-dropdown-menu>
                    </template>
                </el-dropdown>
            </div>
        </div>

        <!-- 主画布：flex:1 吃掉剩余高度，不用 100vh（页面嵌在 shell 里，100vh 会顶出滚动条） -->
        <div class="workflow-main" ref="workflowMainRef" @mousedown.capture="onCanvasMouseDown">
            <Workflow ref="workflowRef" v-if="detail" :data="detail?.workFlow" />
            <!-- 发布历史是画布区的右侧抽屉：挂在 .workflow-main 里，才能贴满画布区而不越界 -->
            <PublishHistory
                v-if="showHistory"
                @click="checkVersion"
                @close="closeHistory"
                @refreshVersion="refreshVersion"
                v-click-outside="clickoutsideHistory"
            />
        </div>
        <!-- 下拉框是绝对定位的浮层，不参与高度计算 -->
        <el-collapse-transition>
            <DropdownMenu :show="showPopover" :id="id" v-click-outside="clickoutside" @clickNodes="clickNodes"
                :workflowRef="workflowRef" />
        </el-collapse-transition>
    </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, getCurrentInstance } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessageBox, ClickOutside as vClickOutside } from 'element-plus'
import Workflow from './components/Canvas.vue'
import { MsgSuccess, MsgError } from '@/utils/message'
import { pendingEndpointHint } from '@/lib/pendingEndpoint'
import PublishHistory from '@/pages/workflow/components/common/PublishHistory.vue'
import DropdownMenu from '@/pages/workflow/components/common/DropdownMenu.vue'
import BackButton from '@/pages/workflow/components/back-button/index.vue'
import AppAvatar from '@/pages/workflow/components/app-avatar/index.vue'
import WorkFlowIcon from '@/pages/workflow/components/icons/WorkFlowIcon.vue'
import WorkFlowReadWrite from '@/pages/workflow/components/read-write/index.vue'
import WorkFlowAutoTooltip from '@/pages/workflow/components/auto-tooltip/index.vue'
import CommonList from '@/pages/workflow/components/common-list/index.vue'
import MdEditorMagnify from '@/pages/workflow/components/common/MdEditorMagnify.vue'
import { datetimeFormat } from '@/utils/time'
import { toLogicFlow, fromLogicFlow } from './lib/graph-format'
import { setAppId } from './lib/context'
import { validateGraph, hasBlockingError, type ValidateContext } from './lib/validate'
import { getAssistant } from '@/api/assistants'
import { modelsApi, knowledgeApi } from '@/api'
import { detail as getDetail, addSubmit, workFlowPublish, getVersionGraph } from '@/api/legacy/setting'
import type { WorkflowGraph } from '@/api/workflow'

// 主应用上下文里也要能解析这些组件/指令（节点子应用另有一套 install）。
// ★ 指令不能漏：漏了 `v-click-outside` 只会**静默失效**（Vue 只打一条 warn），
//   症状就是「点外面关不掉浮层」——发布历史与「添加组件」下拉都踩这个。
const _app = getCurrentInstance()?.appContext.app
if (_app) {
    _app.component('BackButton', BackButton)
    _app.component('AppAvatar', AppAvatar)
    _app.component('WorkFlowIcon', WorkFlowIcon)
    _app.component('WorkFlowReadWrite', WorkFlowReadWrite)
    _app.component('WorkFlowAutoTooltip', WorkFlowAutoTooltip)
    _app.component('CommonList', CommonList)
    _app.component('MdEditorMagnify', MdEditorMagnify)
    _app.directive('click-outside', vClickOutside)
}

let interval: any
const detail = ref<any>(null)
const workflowRef = ref()
const workflowMainRef = ref();
const showHistory = ref(false)
const disablePublic = ref(false)
const saveTime = ref<any>('')
const currentVersion = ref<any>({})
const isSave = ref(false)
const showPopover = ref(false)
const router = useRouter()
const route = useRoute()
/* 路由参数名必须与 `pages/workflow/route.ts` 声明的 `:appId` 一致 ——
   写错不会报错，只会让 id 变成 undefined，然后草稿拉不到、画布空白。 */
const id = route.params.appId as string

onMounted(() => {
    // 必须先写上下文：画布节点跑在各自的 Vue 子应用里，拿不到 router，
    // 靠这个模块拿 appId（见 lib/context.ts）
    setAppId(id)
    loadValidateCtx()
    getWokFlowDetail();
    const workflowAutoSave = localStorage.getItem('workflowAutoSave')
    isSave.value = workflowAutoSave === 'true' ? true : false;
    if (isSave.value) {
        initInterval()
    }
})
onBeforeUnmount(() => {
    // 清除定时任务
    closeInterval()
    workflowRef.value?.clearGraphData()
})
function clickNodes(_item: any) {
    showPopover.value = false
}
function closeHistory() {
    getWokFlowDetail()
    if (isSave.value) {
        initInterval()
    }
    showHistory.value = false
    disablePublic.value = false
}
function changeSave(bool: boolean) {
    bool ? initInterval() : closeInterval()
    localStorage.setItem('workflowAutoSave', bool.toString())
}
function openHistory() {
    showHistory.value = true
}
function clickoutside() {
    showPopover.value = false
}

/**
 * 点画布空白处关闭浮层。
 *
 * 为什么不能用 `v-click-outside` 就完事：那个指令（Element Plus 的 ClickOutside）
 * 把监听挂在 `document` 的**冒泡阶段**，而 **LogicFlow 会在 mousedown 里
 * `stopPropagation` 把事件吞掉** ⇒ 点画布时 document 收不到，浮层关不掉
 * （点侧栏、点工具栏却可以，所以这个坑很隐蔽）。
 *
 * 这里用**捕获阶段**（`.capture`）：捕获是从根往下走、先于目标处理器，
 * 所以 LogicFlow 吞得到却没机会先吞。
 */
function onCanvasMouseDown(e: MouseEvent) {
    const target = e.target as HTMLElement | null
    if (showHistory.value && !target?.closest('.workflow-publish-history')) {
        closeHistory()
    }
    if (showPopover.value && !target?.closest('.workflow-dropdown-menu')) {
        showPopover.value = false
    }
}
function clickoutsideHistory() {
    // 一律关闭。旧代码在 `disablePublic` 为 true（预览过某版本）时**拒绝关闭**，
    // 而那个状态下模板恰好又没有关闭按钮 ⇒ 面板关不掉，只能刷新页面。
    // 现在面板自己带 ✕，点外部也关，两个入口都通。
    showHistory.value = false
    disablePublic.value = false
}
function refreshVersion(item?: any) {
    if (item) {
        previewVersion(item)
    }
    if (isSave.value) {
        initInterval()
    }
    showHistory.value = false
    disablePublic.value = false
}

/**
 * 把契约格式的图交给画布。
 *
 * `graph` 一律是**契约格式**（`WorkflowGraph`）—— 转换只在这一处发生，
 * 免得画布里混进两种坐标/标题命名。
 *
 * 不再命令式地喊画布重绘：`Canvas.vue` 监听 `data` 属性，
 * `detail.value.workFlow` 一变就重画（单向数据流）。
 */
function renderGraphData(graph: WorkflowGraph) {
    const lf = toLogicFlow(graph)
    // noRender：让画布不要给同名节点自动补序号（沿用旧画布的语义）
    lf.nodes.forEach((v) => {
        ;(v.properties as Record<string, unknown>).noRender = true
    })
    // 开场白来自 start-node 的 config（契约字段）
    const prologue =
        (graph.nodes.find((n) => n.type === 'START_NODE')?.config as { prologue?: string } | undefined)
            ?.prologue || ''
    detail.value = { ...(detail.value ?? {}), workFlow: lf, prologue }
}

/** 预览某一版：版本列表**不带图**，所以按需取该版的图（契约里的单版本接口） */
function previewVersion(item: any) {
    getVersionGraph(id, item.version)
        .then((graph) => {
            saveTime.value = item.publishedAt
            renderGraphData(graph)
        })
        .catch((error: unknown) => {
            // 同上（`#160`）：这个端点后端没实现，用户点「预览某一版」原本毫无反应。
            const pending = pendingEndpointHint(error, '预览历史版本', '#198')
            MsgError(pending ?? '预览历史版本失败，请稍后重试')
        })
}
function checkVersion(item: any) {
    disablePublic.value = true
    currentVersion.value = item
    previewVersion(item)
    closeInterval()
}
const initInterval = () => {
    // 先清再设：本函数在 onMounted / closeHistory / changeSave / refreshVersion
    // 四处都会被调到，不清就会积起多个定时器 —— 症状是「保存」一分钟被触发 N 次。
    closeInterval()
    interval = setInterval(() => {
        saveApplication()
    }, 60000)
}
const closeInterval = () => {
    if (interval) {
        clearInterval(interval)
        interval = null
    }
}
async function publicHandle() {
    await saveApplication()
    // 后执行发布
    workflowRef.value
        ?.validate()
        .then(async () => {
            const graph = fromLogicFlow(getGraphData())
            /*
             * 图级校验**与 mock/后端共用同一份规则**（`lib/validate.ts`）。
             * 旧代码用的是 `components/common/validate.ts` 的 WorkFlowInstance —— 它按
             * `node.id === 'start-node'` 找开始节点（旧系统的单例节点 id 就是类型字符串），
             * 而我们的 id 是 `start-1` ⇒ **无论怎么填都报「开始节点必填」**，
             * 这就是用户「手填永远过不了发布验证」的原因。那份已删除。
             */
            const issues = validateGraph(graph, validateCtx)
            if (hasBlockingError(issues)) {
                showBlockingIssues(issues)
                return
            }
            // 发布说明是契约必填（发布历史里要显示"这一版改了什么"），
            // 旧界面没有这个输入，所以在这里问一次 —— 不拿一个假说明糊弄过去。
            const { value } = await ElMessageBox.prompt('这一版改了什么？', '发布说明', {
                confirmButtonText: '发布',
                cancelButtonText: '取消',
                inputPlaceholder: '例如：补充风险分流与三路回复'
            })
            await workFlowPublish({ applicationId: id, graph, note: value })
            saveTime.value = new Date()
            MsgSuccess('发布成功')
        })
        .catch((res: any) => {
            // 用户取消发布说明对话框：ElMessageBox 会 reject 一个字符串，不是校验失败
            if (typeof res === 'string') return
            const node = res?.node
            const err_message = res?.errMessage
            if (typeof err_message == 'string') {
                MsgError(node?.properties?.stepName + '节点 ' + err_message)
            } else if (err_message) {
                const keys = Object.keys(err_message)
                MsgError(node?.properties?.stepName + '节点 ' + err_message[keys[0]]?.[0]?.message)
            } else {
                MsgError(res?.message ?? '发布失败')
            }
        })
}
function getWokFlowDetail() {
    // 应用名来自 assistant（图里没有名称），流程来自草稿
    getAssistant(id).then((app) => {
        detail.value = { ...(detail.value ?? {}), name: app.name, applyName: app.name }
    })
    getDetail({ id: id })
        .then((res) => {
            const graph = res.data.data as WorkflowGraph
            if (!graph?.nodes) return
            detail.value = { ...(detail.value ?? {}), workFlow: null }
            renderGraphData(graph)
        })
        .catch((error: unknown) => {
            // ★ 草稿端点已经实现（`5203269` 起）。这里仍然要 catch，但理由变了 ——
            //   不再是"后端没有这个端点"，而是"读失败时画布会是空的"：用户会以为这个应用没有流程。
            //   ★ 刻意**不**伪造一张空图当降级：那样用户会在一张空画布上编辑并保存，
            //   第一次保存就把真实草稿覆盖成空图（把失败伪装成成功）。
            const pending = pendingEndpointHint(error, '读取编排草稿', '#198')
            MsgError(pending ?? '读取编排草稿失败，请稍后重试')
        })
}
function getGraphData() {
    return workflowRef.value?.getGraphData()
}

/**
 * 图级校验的上下文：已存在的模型 / 知识库 / 已停用的知识库。
 *
 * 页面自己拉一次，而不是让后端在校验时回查 —— 这样**界面报的错与后端一致**
 * （用户在界面上被拦下的原因，就是发布时会失败的原因），不用等一次往返。
 */
const validateCtx: ValidateContext = {
    modelIds: new Set<string>(),
    kbIds: new Set<string>(),
    disabledKbIds: new Set<string>()
}

async function loadValidateCtx() {
    try {
        const [models, kbs] = await Promise.all([
            modelsApi.listModels({ size: 200 }),
            knowledgeApi.listKnowledgeBases({ size: 200 })
        ])
        validateCtx.modelIds = new Set(models.list.map((m) => m.id))
        validateCtx.kbIds = new Set(kbs.list.map((k) => k.id))
        validateCtx.disabledKbIds = new Set(kbs.list.filter((k) => !k.enabled).map((k) => k.id))
    } catch {
        // 拉不到就按「全都存在」处理，把拦截交给后端 —— 不能因为拉列表失败就挡住发布
        validateCtx.modelIds = new Set<string>()
        validateCtx.kbIds = new Set<string>()
        validateCtx.disabledKbIds = new Set<string>()
    }
}

/** 把阻断项一次列全（只报第一条会让人来回试很多次）。文案都是代码常量，不含用户输入 */
function showBlockingIssues(issues: ReturnType<typeof validateGraph>) {
    const errors = issues.filter((i) => i.level === 'ERROR')
    const rows = errors
        .map((e, i) => {
            const hint = e.hint ? `<br/><span style="color:var(--color-muted)">→ ${e.hint}</span>` : ''
            return `${i + 1}. ${e.message}${hint}`
        })
        .join('<br/>')
    ElMessageBox.alert(rows, `发布前校验未通过（${errors.length} 项）`, {
        dangerouslyUseHTMLString: true,
        confirmButtonText: '知道了'
    }).catch(() => {
        /* 关掉弹窗 */
    })
}
function saveApplication(bool?: boolean) {
    // 只存流程本身。应用名/描述属 `Assistant` 实体（在应用管理里改），
    // 开场白属 start-node 的 config（已在图里）—— 旧代码把它们从 base-node 里
    // 抠出来写回应用，在新模型里是跨实体写入，不照搬。
    const graph = fromLogicFlow(getGraphData())
    addSubmit({ applicationId: id, graph })
        .then(() => {
            saveTime.value = new Date()
            if (bool) {
                MsgSuccess('保存成功')
            }
        })
        .catch((error: unknown) => {
            // ★ 端点已实现（`5203269`），但保存失败仍然是**静默**的默认结局：原先没有 catch ⇒
            //   rejection 无人接，页面表现为「点了保存没反应」（`#160`）。
            //   ★ 每分钟的定时保存与用户点保存走的是同一条路径，所以文案不带「你」——
            //   自动保存失败时用户并没有做任何操作。
            const pending = pendingEndpointHint(error, '在编排里保存', '#198')
            MsgError(pending ?? '保存失败，请稍后重试')
        })
}
</script>

<style scoped lang="scss">
/* 填满 shell 的 main 容器：用 flex 自算高度，禁止 100vh（会顶出滚动条） */
.workflow-page {
    height: 100%;
    min-height: 0;
    overflow: hidden;
    display: flex;
    flex-direction: column;
}

.top {
    flex: none;
    background: var(--color-surface);
    padding: var(--space-sm) var(--space-md);
    border-bottom: 1px solid var(--color-rule);
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.top-title {
    font-size: var(--text-md);
    font-weight: 500;
    color: var(--color-ink);
}

/* 组间发丝线：竖线只做分隔，不抢信号色 */
.top-sep {
    display: inline-block;
    width: 1px;
    height: 16px;
    margin: 0 var(--space-xs);
    background: var(--color-rule);
    vertical-align: middle;
}

.top-actions {
    display: flex;
    align-items: center;
    gap: var(--space-2xs);
}

.workflow-main {
    flex: 1;
    min-height: 0;
    box-sizing: border-box;
    /* 发布历史抽屉锚在这里 */
    position: relative;
}

.workflow-debug-container {
    z-index: var(--z-modal);
    position: relative;
    border-radius: var(--radius-lg);
    border: 1px solid var(--color-rule);
    background: var(--color-surface);
    box-shadow: var(--shadow-float);
    position: fixed;
    bottom: var(--space-md);
    right: var(--space-md);
    overflow: hidden;
    width: 450px;
    height: 600px;

    .workflow-debug-header {
        background: var(--color-sunken);
        height: var(--app-header-height);
        line-height: var(--app-header-height);
        box-sizing: border-box;
        border-bottom: 1px solid var(--color-rule);
    }

    .scrollbar-height {
        height: calc(100% - var(--app-header-height) - 24px);
        padding-top: 24px;
    }

    &.enlarge {
        width: 50% !important;
        height: 100% !important;
        bottom: 0 !important;
        right: 0 !important;
    }

    .chat-width {
        max-width: 100% !important;
        margin: 0 auto;
    }
}
</style>