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

        <!-- 发布前校验的问题清单：真端点 `validateWorkflow` 的结构化 issues，或后端 400 的二次校验明细。
             刻意不做成弹窗：弹窗一关就看不见了，而这是「照着改」的清单，要一直摆着。
             每条按级别着色（ERROR 红 / WARNING 黄），点一行把画布移到那个节点并选中。 -->
        <div v-if="publishIssues.length || publishIssueSummary" class="publish-issues">
            <div class="publish-issues-head">
                <span class="publish-issues-title">{{ publishIssueTitle }}</span>
                <el-button text @click="clearPublishIssues">关闭</el-button>
            </div>
            <ul v-if="publishIssues.length" class="publish-issues-list">
                <li v-for="(issue, index) in publishIssues"
                    :key="`${issue.code}-${issue.nodeId ?? 'graph'}-${index}`"
                    class="publish-issue" :class="issue.level === 'ERROR' ? 'is-error' : 'is-warning'">
                    <button type="button" class="publish-issue-main" @click="locateIssue(issue)">
                        <span class="publish-issue-level">{{ issue.level === 'ERROR' ? '错误' : '警告' }}</span>
                        <span class="publish-issue-message">{{ issue.message }}</span>
                    </button>
                    <!-- 下一步动作也摆出来 —— 只说「哪里不对」不说「怎么办」，用户只能一个个试 -->
                    <p v-if="issue.hint" class="publish-issue-hint">→ {{ issue.hint }}</p>
                </li>
            </ul>
            <!-- 后端 400 的二次校验：包络里**没有**结构化 issues（明细被摊成 message + hint 文本），
                 所以这里原文显示、不解析 —— 解析后端文案会在它改措辞时静默退化成空清单。 -->
            <p v-else class="publish-issues-text">{{ publishIssueSummary }}</p>
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
import { ref, computed, onMounted, onBeforeUnmount, getCurrentInstance } from 'vue'
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
import { detail as getDetail, addSubmit, getVersionGraph } from '@/api/legacy/setting'
import {
    validateWorkflow,
    publishWorkflow,
    type WorkflowGraph,
    type WorkflowValidationIssue
} from '@/api/workflow'
import { ErrorCode } from '@/api/types'

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
/* ────────────────────────────────────────────────────────────────────────────
 * 发布：先校验、通过了才发布。
 *
 * 三次往返的顺序就是判据：
 *  ① 本地图级校验（零往返）——挡掉明显的结构问题，不让用户白等一次网络；
 *  ② `validateWorkflow` 真端点 —— **权威**。后端比前端严，发布时还会再验一次；
 *  ③ `publishWorkflow` —— 只有它真的返回了一个版本，才写成功提示。
 *
 * 两类失败在这里被刻意分开，因为下一步动作不同：
 *  - **校验没过** ⇒ 不发布，把 issues 列在页面上（ERROR 红 / WARNING 黄），点一行定位到节点；
 *  - **校验请求本身失败**（网络 / 500 / 404）⇒ 同样不发布，并且把失败说出来 ——
 *    把"没校验成功"读成"校验通过"正是本工程的头号病（silent failure）。
 * ──────────────────────────────────────────────────────────────────────────── */

/** 真端点 / 后端 400 报出的问题清单（面板一直在，用户照着改） */
const publishIssues = ref<WorkflowValidationIssue[]>([])
/** 没有结构化 issues 时的原文说明（后端 400 只把明细摊进 message + hint） */
const publishIssueSummary = ref('')

const publishIssueTitle = computed(() => {
    if (!publishIssues.value.length) return '发布没有成功'
    const errors = publishIssues.value.filter((i) => i.level === 'ERROR').length
    const warnings = publishIssues.value.length - errors
    const parts = [`${errors} 个错误`]
    // 只有错误会拦住发布；警告不拦，所以要写清"不拦"，否则用户会以为还得改
    if (warnings > 0) parts.push(`${warnings} 个警告（不拦发布）`)
    return `发布前校验没通过：${parts.join(' · ')}`
})

function clearPublishIssues() {
    publishIssues.value = []
    publishIssueSummary.value = ''
}

/** 错误对象上能给人看的那句话。`ApiError.message` 一定非空（网络失败时是 http 兜底文案） */
function errorText(error: unknown): string | null {
    const message = (error as { message?: unknown } | null)?.message
    return typeof message === 'string' && message.trim().length > 0 ? message : null
}

/**
 * 点一条 issue：把画布移到那个节点并选中。
 *
 * 找不到就**如实说** —— 静默什么也不做，用户会以为按钮坏了（与 `#91` 同族）。
 */
function locateIssue(issue: WorkflowValidationIssue) {
    if (!issue.nodeId) return
    const found = workflowRef.value?.focusNode(issue.nodeId)
    if (!found) {
        MsgError('这条问题关联的节点不在当前画布上（可能是另一版报的），请按提示在图上找对应节点')
    }
}

/**
 * 发布失败的三种处置**必须分开**（下一步动作完全不同）。
 *
 * - `409 CONFLICT`：有人在同时发布 ⇒ 稍后重试就行，改图没有用；
 *   说成通用失败会让人去改一张本来没错的图。
 * - `6001 PUBLISH_CHECK_FAILED`：后端二次校验更严 ⇒ 明细摆进面板原文显示。
 *   ★ 后端的 400 包络里**没有**结构化 issues 数组（`ApiResponse.from(BusinessException)`
 *   把 `data` 置 null，明细被 `WorkflowPublishService` 摊成 message + hint 文本），
 *   所以这里展示文本、**不解析**：解析后端文案会在它改措辞时静默退化成空清单。
 * - 其余（网络 / 500 / 404）：说清"这次没有发布"，绝不让界面看起来像成功。
 */
function handlePublishFailure(error: unknown) {
    const code = (error as { code?: unknown } | null)?.code
    if (code === ErrorCode.CONFLICT) {
        MsgError('有人同时在发布这个应用，请重试（这次没有发布）')
        return
    }
    if (code === ErrorCode.PUBLISH_CHECK_FAILED) {
        const hint = (error as { hint?: unknown } | null)?.hint
        publishIssueSummary.value = [
            errorText(error) ?? '后端复核这张图还不能发布',
            typeof hint === 'string' && hint.trim().length > 0 ? hint : ''
        ]
            .filter((line) => line.trim().length > 0)
            .join('\n')
        return
    }
    const pending = pendingEndpointHint(error, '发布编排', '#198')
    MsgError(pending ?? errorText(error) ?? '发布失败，请稍后重试')
}

async function publicHandle() {
    // 上一轮的清单必须先清掉：否则用户会对着上一轮的 issue 改这一轮的图
    clearPublishIssues()

    // ★ 空画布直接拦住。`fromLogicFlow` 里是 `graph.nodes.map(...)`，拿到 undefined 会抛
    //   TypeError —— 在 async 函数里那是一个没人接的 rejection，界面表现就是「点发布毫无反应」
    //   （本项目头号病）。草稿读失败时画布正是空的，所以这个守卫是这条链上的第一道网。
    const graphData = getGraphData()
    if (!graphData?.nodes?.length) {
        MsgError('画布是空的，没有可发布的内容（草稿可能还没加载出来，请刷新页面重试）')
        return
    }

    // ★ 以前这里是 `await saveApplication()` —— 而 saveApplication 没有 return，
    //   所以那是个空等：保存没成功也照样往下发布。现在它返回 Promise，这一行才真的等。
    await saveApplication()

    const graph = fromLogicFlow(graphData)

    // ① 本地图级校验：**与 mock / 后端共用同一份规则**（`lib/validate.ts`），
    //    所以不是"两套校验各说各的话"，而是一遍零成本的预演。
    //    旧代码用的 `components/common/validate.ts` 按 `node.id === 'start-node'` 找开始节点，
    //    而我们的 id 是 `start-1` ⇒ 无论怎么填都报「开始节点必填」（那份已删除）。
    const localIssues = validateGraph(graph, validateCtx)
    if (hasBlockingError(localIssues)) {
        publishIssues.value = localIssues
        return
    }

    // ② 节点自身的配置校验（画布 `validate()`）：漏了它，配置缺字段的节点会直接被发出去
    try {
        await workflowRef.value?.validate()
    } catch (rejected: unknown) {
        // 用户取消某个对话框时 ElMessageBox reject 一个字符串 —— 那不是校验失败，别报错
        if (typeof rejected === 'string') return
        const node = (rejected as { node?: { properties?: { stepName?: string } } } | null)?.node
        const stepName = node?.properties?.stepName ?? '这个'
        const errMessage = (rejected as { errMessage?: unknown } | null)?.errMessage
        if (typeof errMessage === 'string') {
            MsgError(`${stepName}节点 ${errMessage}`)
        } else if (errMessage) {
            const keys = Object.keys(errMessage)
            const detail = (errMessage as Record<string, Array<{ message?: string }>>)[keys[0]]?.[0]?.message
            MsgError(`${stepName}节点 ${detail ?? '配置校验没通过'}`)
        } else {
            MsgError(errorText(rejected) ?? '节点配置校验没通过，这次没有发布')
        }
        return
    }

    // ③ 真端点校验（权威）。它没过就**一条都不发**。
    let result: { passed: boolean; issues: WorkflowValidationIssue[] }
    try {
        result = await validateWorkflow(id, graph)
    } catch (error: unknown) {
        // ★ 这里最容易犯的错：把"没校验成功"当成"校验通过"继续发布。
        //   请求失败时界面必须说清楚这次没有发布。
        const pending = pendingEndpointHint(error, '发布前校验', '#198')
        MsgError(pending ?? errorText(error) ?? '发布前校验的请求失败了，这次没有发布，请稍后重试')
        return
    }
    if (!result.passed) {
        const issues = result.issues ?? []
        publishIssues.value = issues
        if (!issues.length) {
            // 后端说没过却说不出是哪条：这本身要可见，不能静默停在"点了没反应"
            publishIssueSummary.value =
                '后端判定这张图还不能发布，但没有返回具体问题 —— 请再点一次「发布」；仍然如此请联系管理员'
        }
        return
    }

    // ④ 发布说明是契约必填（发布历史里要显示"这一版改了什么"），不拿假说明糊弄
    let note: string
    try {
        const { value } = await ElMessageBox.prompt('这一版改了什么？', '发布说明', {
            confirmButtonText: '发布',
            cancelButtonText: '取消',
            inputPlaceholder: '例如：补充风险分流与三路回复'
        })
        note = String(value ?? '')
    } catch {
        return // 用户主动取消：什么都没发生，不需要提示
    }

    // ⑤ 发布。成功提示必须带**可核对**的读数（版本号 + 节点数）——
    //    只说"发布成功"，用户无法判断发的是不是手上这一版。
    try {
        const version = await publishWorkflow(id, { graph, note })
        saveTime.value = new Date()
        MsgSuccess(`发布成功：v${version.version}，共 ${version.nodeCount} 个节点`)
    } catch (error: unknown) {
        handlePublishFailure(error)
    }
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

/* 旧的 `showBlockingIssues`（ElMessageBox.alert + dangerouslyUseHTMLString）已删除：
   它弹出的 HTML 里要拼后端返回的 `message`/`hint` 文本，等于把服务端文本当 HTML 渲染；
   而现在的面板用 `{{ }}` 插值渲染，且关掉弹窗后清单还在（弹窗一关就没法照着改了）。 */
/**
 * 保存草稿。
 *
 * ★ 返回 Promise 是这次接线修掉的既有缺陷：原先 `.then().catch()` 之后**没有 return**，
 *   所以 `publicHandle` 里的 `await saveApplication()` 是个空等（保存还没回来就去发布了）。
 *   调用方可以无视返回值（`@click` / 定时器），但**发布前必须等它**。
 */
function saveApplication(bool?: boolean) {
    // 只存流程本身。应用名/描述属 `Assistant` 实体（在应用管理里改），
    // 开场白属 start-node 的 config（已在图里）—— 旧代码把它们从 base-node 里
    // 抠出来写回应用，在新模型里是跨实体写入，不照搬。
    const graph = fromLogicFlow(getGraphData())
    return addSubmit({ applicationId: id, graph })
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

/* ── 发布前校验的问题清单 ──────────────────────────────────────────────────
 * 位置：顶栏与画布之间的一条可滚动区域，**不悬浮在画布上**（浮层会挡住要改的节点）。
 * 颜色全部走令牌：ERROR 红 / WARNING 黄，且两者的 "soft" 底与正文色是一对的。
 */
.publish-issues {
    flex: none;
    max-height: 34%;
    overflow-y: auto;
    background: var(--color-surface);
    border-bottom: 1px solid var(--color-rule);
    padding: var(--space-sm) var(--space-md);
    display: flex;
    flex-direction: column;
    gap: var(--space-xs);
}

.publish-issues-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-sm);
}

.publish-issues-title {
    font-size: var(--text-sm);
    font-weight: 600;
    line-height: 1.6;
    color: var(--color-ink);
}

.publish-issues-list {
    margin: 0;
    padding: 0;
    list-style: none;
    display: flex;
    flex-direction: column;
    gap: var(--space-2xs);
}

.publish-issue {
    padding: var(--space-2xs) var(--space-xs);
    border-radius: var(--radius-sm);
    line-height: 1.6;
}

/* 左侧 2px 色条：3px 起会被 `check_design.py` 判成 SIDE_STRIPE（那是另一套视觉语言） */
.publish-issue.is-error {
    background: var(--color-danger-soft);
    border-left: 2px solid var(--color-danger);
}

.publish-issue.is-warning {
    background: var(--color-warn-soft);
    border-left: 2px solid var(--color-warn);
}

.publish-issue-main {
    display: flex;
    align-items: baseline;
    gap: var(--space-xs);
    width: 100%;
    padding: 0;
    border: 0;
    background: transparent;
    text-align: left;
    cursor: pointer;
    color: var(--color-ink-2);
    font-size: var(--text-sm);
    line-height: 1.6;
}

.publish-issue-level {
    flex: none;
    font-weight: 600;
}

.is-error .publish-issue-level {
    color: var(--color-danger);
}

.is-warning .publish-issue-level {
    color: var(--color-warn);
}

.publish-issue-message {
    flex: 1 1 auto;
}

/* 「下一步」用次一级的颜色与字号：它跟 message 不是同一层信息 */
.publish-issue-hint {
    margin: var(--space-3xs) 0 0;
    color: var(--color-muted);
    font-size: var(--text-xs);
    line-height: 1.6;
}

/* 后端 400 的文本明细：`hint()` 里是按编号排的多条，要保住换行 */
.publish-issues-text {
    margin: 0;
    white-space: pre-line;
    color: var(--color-ink-2);
    font-size: var(--text-sm);
    line-height: 1.6;
}
</style>