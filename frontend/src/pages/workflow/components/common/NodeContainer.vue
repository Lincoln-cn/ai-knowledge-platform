<template>
    <!--
      ⚠️ 这里**不再挂 `p-16`**。
      原来 `.workflow-node-container` 与内层 `.step-container` 各有一层 16px 内边距，
      而 foreignObject 的宽度是 `get_width()` 定死的 ⇒ 外层那 16px 在画布上表现为
      每侧 16px 的**不可见空隙**（卡片实际只有 footprint 的 32px 窄），
      既是"卡片套卡片"的味道，也白白吃掉画布宽度。
      去掉后卡片铺满自己的 footprint，视觉上反而更饱满。
    -->
    <div @mousedown="onCardDown" class="workflow-node-container">
        <!--
          画布上的卡片 = 标题行 + 「必要信息」只读摘要。
          ⚠️ 整张配置表单**不在这里**，它搬进了下面的 el-drawer —— 点卡片打开。
          为什么：一张卡塞 3–8 个表单字段时单卡渲染高到 952px，9 张卡把 fitView
          压到 scale 0.248（字读不了），限高又会变成"卡内小滚动条"（难看难用）。
          拆成「卡上只放一眼要看的、其余点开配」之后，卡高回到标题量级，
          图自然变小 ⇒ 缩放回到可读区间，且配置有整块空间。
        -->
        <div class="step-container app-card p-16"
            :class="{ isSelected: props.nodeModel.isSelected, error: node_status !== 200, 'is-clickable': true }"
            style="overflow: visible">
            <div v-resize="resizeStepContainer">
                <div class="flex-between node-head">
                    <!--
                      名字区用 `flex: 1; min-width: 0` 吃掉剩余宽度，**不再用写死的
                      `maxWidth: calc(100% - 55px)`**。
                      那个 55px 是按"两个按钮"估的，而带「执行条件」的节点按钮组实测是
                      76px ⇒ 名字区被允许伸到按钮底下 ⇒ **卡片的水平中心落到按钮上**，
                      点击被按钮的 `@mousedown.stop` 吃掉 ⇒ 那类节点"点卡片没反应"
                      （实测：风险研判/风险分流/一般结论/兜底回复 点不开，其余正常）。
                      交给 flex 之后，名字区宽度 = 真实剩余宽度，不可能压到按钮上。
                    -->
                    <div class="flex align-center node-head-main">
                        <component :is="iconComponent(`${nodeModel.type}-icon`)" class="mr-8" :size="24"
                            :item="nodeModel?.properties.node_data" />
                        <!--
                          ⚠️ 这里**不再用 `WorkFlowReadWrite` 做双击改名**。
                          它带 `@mousedown.stop`，会把 mousedown 吃掉 ⇒ 「点卡片打开配置」
                          在能改名的节点上失效。重命名本来就在 ⋮ 菜单里，去掉不丢能力。
                        -->
                        <h4 :title="nodeModel.properties.stepName">{{ nodeModel.properties.stepName }}</h4>
                    </div>

                    <!--
                      `@mousedown.stop` **已去掉**：留着它会让 onCardDown 收不到 mousedown，
                      「点卡片开配置」在这些按钮上直接静默失败。
                      改由 document 上的 mouseup 处理器按"落点是不是交互控件"来判 ——
                      点按钮走按钮自己的事，点卡的其余部分开配置。
                    -->
                    <div class="node-head-actions" @mousemove.stop @keydown.stop @click.stop>
                        <el-button text title="展开/收起参数输出" @click="showNode = !showNode">
                            <el-icon class="arrow-icon color-secondary" :class="showNode ? 'rotate-180' : ''">
                                <ArrowDownBold />
                            </el-icon>
                        </el-button>
                        <!--
                          节点操作菜单：**对每个节点都渲染**。
                          旧代码是 `v-if="showOperate(type)"`，而 showOperate 把 Base/Start 排除了
                          ⇒ 开始节点与基本信息节点连复制/删除入口都没有（用户报的「节点无法删除」）。
                          showOperate 现在只用来决定「标题能不能双击改名」，与菜单无关。

                          ☠ **不能写 `:teleported="false"`**：那会把 popper 塞进 LogicFlow 节点的
                          `foreignObject` 里，Element Plus 在那里面算不出位置 ⇒ 菜单在 DOM 里但
                          `rect` 恒为 0×0、永远不显示（用户看到的就是「有 ⋮ 但点了没反应」）。
                          弹出到 body 才正常。
                        -->
                        <!--
                          触发方式：**不依赖原生 click**。
                          LogicFlow 节点上 mousedown/mouseup 落到不同元素（拖拽前会重渲染节点 DOM）
                          ⇒ 浏览器不派发 click ⇒ `trigger="click"` 对真人鼠标永远打不开。
                          正解与「点卡片开配置」同一套：document 捕获阶段 mouseup + 落点判定，
                          命中 ⋮ 按钮时手动 `handleOpen()`。`trigger` 留空，避免 click 与手动开合打架；
                          键盘（Enter/Space）仍走 dropdown 自己的 triggerKeys。
                        -->
                        <el-dropdown ref="nodeMenuRef" :trigger="[]" @visible-change="onNodeMenuVisible">
                            <el-button text>
                                <el-icon class="color-secondary">
                                    <MoreFilled />
                                </el-icon>
                            </el-button>
                            <template #dropdown>
                                <el-dropdown-menu style="min-width: 132px">
                                    <el-dropdown-item v-for="it in nodeMenuItems" :key="it.key"
                                        :class="['p-8', { 'is-disabled-item': it.disabled, 'border-t': it.key === 'delete' }]"
                                        @click="it.run()">
                                        {{ it.label }}
                                        <!-- 禁用也保持可点：点它由动作层弹出原因，而不是静默无反应 -->
                                        <span v-if="it.disabled" class="menu-reason">{{ it.disabledReason }}</span>
                                    </el-dropdown-item>
                                </el-dropdown-menu>
                            </template>
                        </el-dropdown>
                    </div>
                </div>

                <!--
                  必要信息：**只读摘要**，一眼要看的那一两项（如「AI 模型 · qwen3:8b」）。
                  各节点类型用 `<template #essential>` 按需提供；**不提供就只显示标题行**，
                  所以这是一个可以逐个节点增量落地的口子，不需要一次改完 8 种节点。
                  为什么摘要不做成输入框：输入框需要 el-form 上下文与校验引用，
                  而校验上下文在弹窗那张表单里 —— 两处各放一个可编辑控件会让必填校验
                  在两棵 DOM 树里各判一次。摘要只读，就没有这个问题。
                -->
                <div v-if="$slots.essential" class="node-essential">
                    <slot name="essential"></slot>
                </div>

                <el-collapse-transition>
                    <!-- 展开时在卡内追加「参数输出」（只读信息，不是配置）。 -->
                    <div v-show="showNode" class="mt-8 node-outputs">
                        <el-alert v-if="node_status != 200" class="mb-16" title="该函数不可用" type="error" show-icon
                            :closable="false" />
                        <template v-if="nodeFields.length > 0">
                            <h5 class="title-decoration-1 mb-8 mt-8">参数输出</h5>
                            <template v-for="(item, index) in nodeFields" :key="index">
                                <div class="flex-between border-r-4 p-8-12 mb-8 layout-bg lighter"
                                    @mouseenter="showicon = index" @mouseleave="showicon = null">
                                    <span style="max-width: 92%">{{ item.label }} {{ '{' + item.value + '}' }}</span>
                                    <el-tooltip effect="dark" content="复制参数" placement="top" v-if="showicon === index">
                                        <el-button link @click="copyClick(item.globeLabel)" style="padding: 0">
                                            <WorkFlowIcon iconName="app-copy"></WorkFlowIcon>
                                        </el-button>
                                    </el-tooltip>
                                </div>
                            </template>
                        </template>
                    </div>
                </el-collapse-transition>
            </div>
        </div>

        <!--
          配置弹窗：整张表单在这里（节点组件交上来的默认插槽）。
          `append-to-body` 是必须的 —— 这个组件跑在 LogicFlow 节点的 foreignObject 里，
          留在原地会被 foreignObject 裁掉。与上面下拉菜单同一个道理。
        -->
        <el-drawer v-model="showConfig" append-to-body :size="480" class="node-config-drawer"
            :title="nodeModel.properties.stepName">
            <div @mousedown.stop @keydown.stop @click.stop>
                <el-alert v-if="node_status != 200" class="mb-16" title="该函数不可用" type="error" show-icon
                    :closable="false" />
                <!--
                  「执行条件」搬到这里 —— 它本身就是配置，原本挂在卡片标题行里当第三个按钮，
                  把按钮组从 55px 撑到 76px，进而让卡片的水平中心落进按钮区、吞掉点击。
                -->
                <template v-if="showOperate(nodeModel.type)">
                    <h5 class="title-decoration-1 mb-8">执行条件</h5>
                    <p class="mb-16 node-cond">
                        <span>前置</span>
                        <el-select v-model="condition" size="small" style="width: 72px; margin: 0 8px">
                            <el-option label="所有" value="AND" />
                            <el-option label="任一" value="OR" />
                        </el-select>
                        <span>连线节点执行完，执行当前节点</span>
                    </p>
                </template>
                <slot></slot>
            </div>
        </el-drawer>

        <el-collapse-transition>
            <DropdownMenu v-if="showAnchor" @mousemove.stop @mousedown.stop @click.stop @wheel="handleWheel"
                :show="showAnchor" :id="id" style="left: 100%; top: 50%; transform: translate(0, -50%)"
                @clickNodes="clickNodes" />
        </el-collapse-transition>
    </div>
</template>
<script lang="ts">
/**
 * 跨 NodeContainer 实例共享的 ⋮ 菜单互斥状态。
 *
 * 为什么在普通 `<script>`（模块作用域）而不是 `<script setup>`：
 * LogicFlow 给每个节点 `createApp()` 建**独立 Vue 子应用**（见 app-node.ts），
 * `<script setup>` 里的变量是每实例一份，只有模块级变量能跨节点共享
 * （同 context.ts 里的 collapseAll）。
 */
type MenuController = { close: () => void }

const menuControllers = new Set<MenuController>()
let pendingOnUp: ((e: MouseEvent) => void) | null = null

function clearPendingOnUp() {
    if (!pendingOnUp) return
    document.removeEventListener('mouseup', pendingOnUp, true)
    pendingOnUp = null
}

/** 收起所有节点的 ⋮ 菜单。开新菜单前先调它，保证同一时刻 ≤1 个可见。 */
function closeAllNodeMenus() {
    for (const c of menuControllers) c.close()
}

/**
 * Escape 兜底收起。
 * ElDropdown 自己的 Escape 依赖焦点落在菜单内；焦点被 LogicFlow 抢走时关不掉
 * （实测同一菜单要按两次 Escape）。这里在捕获阶段无条件收，不 stopPropagation，
 * 不影响抽屉/对话框各自的 Escape。
 */
document.addEventListener(
    'keydown',
    (e) => {
        if (e.key === 'Escape') closeAllNodeMenus()
    },
    true
)
</script>
<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import DropdownMenu from '@/pages/workflow/components/common/DropdownMenu.vue'
import { set } from 'lodash-es'
import { iconComponent } from '../icons/utils'
import { copyClick } from '@/utils/clipboard'
import { WorkflowType } from '@/pages/workflow/enums/workflow'
import { getAppId, collapseAll, collapseTick } from '@/pages/workflow/lib/context'
import { buildNodeMenuItems } from '@/pages/workflow/lib/node-actions'

const id = getAppId()

const height = ref<{
    stepContainerHeight: number
    inputContainerHeight: number
    outputContainerHeight: number
}>({
    stepContainerHeight: 0,
    inputContainerHeight: 0,
    outputContainerHeight: 0
})
const showAnchor = ref<boolean>(false)
const anchorData = ref<any>()
const condition = ref('AND')
const handleWheel = (event: any) => {
    const isCombinationKeyPressed = event.ctrlKey || event.metaKey
    if (!isCombinationKeyPressed) {
        event.stopPropagation()
    }
}
const node_status = computed(() => {
    if (props.nodeModel.properties.status) {
        return props.nodeModel.properties.status
    }
    return 200
})
const mousedown = () => {
    props.nodeModel.graphModel.clearSelectElements()
    set(props.nodeModel, 'isSelected', true)
    set(props.nodeModel, 'isHovered', true)
    props.nodeModel.graphModel.toFront(props.nodeModel.id)
}

/**
 * 按下的位置 + 是否按在卡片上。
 *
 * ⚠️ **不能靠 `@click` 打开配置弹窗。** 实测：LogicFlow 在拖拽过程中会重渲染节点 DOM，
 *    `mousedown` 与 `mouseup` 落到**不同元素**上 ⇒ 浏览器根本不派发 `click`。
 *    （诊断证据：`[DIAG down]` 触发、`[DIAG click]` 一次都没有。）
 * 正解 = 在 `document` 上以捕获阶段自己收 `mouseup`，用位移判据区分「点击」与「拖拽」。
 *   捕获阶段的好处是不受 LogicFlow 的 stopPropagation 影响。
 *
 * 同一套判据也用来驱动 ⋮ 操作菜单：落点在 `.el-dropdown` 触发器上时手动开/关，
 * 不依赖 `el-dropdown` 的 `trigger="click"`（那个 click 在 LF 节点上同样永不触发）。
 *
 * ⚠️ 开新菜单前必须 `closeAllNodeMenus()`：原生 click 在 LF 节点上不派发 ⇒
 * el-dropdown 的 onClickOutside（挂在 window 的 click 上）也永不触发，
 * 指望它在切换菜单时收起上一个是靠不住的（实测会两个菜单叠加）。
 */
const downPos = { x: 0, y: 0 }
let pressedOnCard = false

const nodeMenuRef = ref<any>(null)
const nodeMenuOpen = ref(false)
function onNodeMenuVisible(v: boolean) {
    nodeMenuOpen.value = v
}

/** 本实例菜单的收起入口，挂进模块级 registry 供互斥逻辑调用。 */
const menuController: MenuController = {
    close: () => {
        // 无条件关：nodeMenuOpen 可能与真实可见态不同步（visible-change 不一定 100% 触发），
        // 用它当闸门会漏关，导致两个菜单同时可见。
        nodeMenuOpen.value = false
        nodeMenuRef.value?.handleClose?.()
        // 见下方 openNodeActionMenu 的「二次确认」：click 若真的派发了，onClickOutside
        // 的 onClose 会和 handleClose 抢同一个 useDelayedToggle 槽，再关一次兜底。
        setTimeout(() => {
            nodeMenuOpen.value = false
            nodeMenuRef.value?.handleClose?.()
        }, 0)
    }
}

/**
 * 打开本节点 ⋮ 操作菜单（不是锚点上的「添加组件」菜单 openNodeMenu）。
 *
 * 为什么要「二次确认开」：el-dropdown 的 onClickOutside 挂在 window 的 click 上。
 * LogicFlow 节点上多数时候 click 不派发（toFront 换掉 down/up 的公共祖先），
 * 但**不是 100%** —— click 一旦派发，会在我们 mouseup 里的 handleOpen 之后跑
 * onClose，把同一个 useDelayedToggle 槽里的 show 取消掉 ⇒ 菜单看起来「没打开」。
 * 把 handleOpen 再往宏任务队列末尾推一次，就能盖掉 click 引发的那次 hide。
 */
function openNodeActionMenu() {
    closeAllNodeMenus()
    const assertOpen = () => {
        nodeMenuOpen.value = true
        nodeMenuRef.value?.handleOpen?.()
    }
    assertOpen()
    setTimeout(assertOpen, 0)
    setTimeout(assertOpen, 50)
}

const onCardDown = (event: MouseEvent) => {
    // 先清掉任何残留的 mouseup 监听：down/up 不配对时（如在窗口外松开）旧闭包会
    // 留在 document 上，下一次 mouseup 会先跑旧节点的 handleOpen，把旧菜单弹回来。
    clearPendingOnUp()
    downPos.x = event.clientX
    downPos.y = event.clientY
    pressedOnCard = true
    // 快照「按下那一刻」菜单是否开着：blur / onClickOutside 可能在 down/up 之间
    // 先把菜单收了（visible-change 已翻成 false），若用当下的 nodeMenuOpen 判开关，点 ⋮ 收不掉。
    const menuWasOpen = nodeMenuOpen.value
    mousedown()

    const onUp = (e: MouseEvent) => {
        clearPendingOnUp()
        if (!pressedOnCard) return
        pressedOnCard = false
        // 位移 > 4px 视为拖拽节点，不弹配置
        if (Math.abs(e.clientX - downPos.x) > 4 || Math.abs(e.clientY - downPos.y) > 4) return
        const target = e.target as HTMLElement | null
        // 落点是 ⋮ 触发器 ⇒ 手动开/关操作菜单（原生 click 在 LF 节点上永不触发）。
        // 必须在「交互控件」那条 return 之前判 —— 否则 ⋮ 会掉进那条 return，
        // 菜单打不开；同时这里 return 也保住了「点 ⋮ 不弹配置抽屉」的语义。
        if (target?.closest('.el-dropdown')) {
            if (menuWasOpen) menuController.close()
            else openNodeActionMenu()
            return
        }
        // 落点在卡片其它地方：先收起可能开着的 ⋮ 菜单，再决定要不要弹配置。
        closeAllNodeMenus()
        // 落点是其它交互控件 ⇒ 走它自己的事，不要抢着开配置
        // （按钮组的 @mousedown.stop 已去掉，所以这里必须自己判 ——
        //   否则点 ⋮ 会同时弹出操作菜单与配置抽屉。）
        if (target?.closest('button, a, input, textarea, select, .el-select, [role="button"]')) return
        showConfig.value = true
    }
    pendingOnUp = onUp
    document.addEventListener('mouseup', onUp, true)
}

onMounted(() => {
    menuControllers.add(menuController)
})
onBeforeUnmount(() => {
    menuControllers.delete(menuController)
    clearPendingOnUp()
    pressedOnCard = false
})

/** 配置弹窗的开关 —— 点卡片打开。 */
const showConfig = ref(false)
const showicon = ref<number | null>(null)
const resizeStepContainer = (wh: any) => {
    if (wh.height && Number.isFinite(wh.height)) {
        if (!props.nodeModel.virtual) {
            height.value.stepContainerHeight = wh.height
            props.nodeModel.setHeight(height.value.stepContainerHeight)
        }
    }
}

function clickNodes(item: any) {
    const width = item.properties.width ? item.properties.width : 214
    const nodeModel = props.nodeModel.graphModel.addNode({
        type: item.type,
        properties: item.properties,
        x: anchorData.value?.x + width / 2 + 200,
        y: anchorData.value?.y - item.height
    })
    props.nodeModel.graphModel.addEdge({
        type: 'app-edge',
        sourceNodeId: props.nodeModel.id,
        sourceAnchorId: anchorData.value?.id,
        targetNodeId: nodeModel.id
    })

    closeNodeMenu()
}

const props = defineProps<{
    nodeModel: any
}>()

/**
 * 展开/收起的**真相源是本地 ref**，不是 `nodeModel.properties.showNode`：
 * LogicFlow 模型不是 Vue 响应式对象，computed 读它算一次就缓存，外部改不会重算。
 * 批量按钮走 `collapseAll`（Vue ref）→ watch 同步到这里；单节点箭头直接改这里并回写 properties。
 *
 * ⚠️ **默认必须是「收起」**（原本是 `!== false`，即默认展开）。
 * 展开态会把「参数输出」整列列在卡上，卡片又长回 2–3 倍高 —— 那就白拆了：
 * 画布上一眼该看的是"这是什么节点、配了什么"，不是每个节点的输出变量清单。
 * 输出清单是随用随看的，按需展开。
 */
const localShow = ref<boolean>(props.nodeModel.properties.showNode === true)

set(props.nodeModel.properties, 'showNode', localShow.value)

watch([collapseAll, collapseTick], () => {
    localShow.value = !collapseAll.value
    set(props.nodeModel.properties, 'showNode', localShow.value)
})

const showNode = computed({
    get: () => localShow.value,
    set: (v: boolean) => {
        localShow.value = v
        set(props.nodeModel.properties, 'showNode', v)
    }
})

/**
 * 节点操作菜单项。规则（单例、删除可用性、复制可用性）全在 `lib/node-actions`，
 * 与「添加组件」面板共用同一份 —— 不在这里另写一套判断。
 */
const nodeMenuItems = computed(() =>
    buildNodeMenuItems({
        expanded: localShow.value,
        graphModel: props.nodeModel.graphModel,
        nodeId: props.nodeModel.id
    })
)

/* 外部入口要收起/展开单个节点时，通过事件过来（模型不是响应式的，必须走这里） */
function onToggleCollapse(nodeId: string) {
    if (nodeId !== props.nodeModel.id) return
    showNode.value = !localShow.value
}
onMounted(() => {
    props.nodeModel.graphModel?.eventCenter?.on?.('toggle_node_collapse', onToggleCollapse)
})
onBeforeUnmount(() => {
    props.nodeModel.graphModel?.eventCenter?.off?.('toggle_node_collapse', onToggleCollapse)
})

const nodeFields = computed(() => {
    if (props.nodeModel.properties.config.fields) {
        const fields = props.nodeModel.properties.config.fields?.map((field: any) => {
            return {
                label: field.label,
                value: field.value,
                globeLabel: `{{${props.nodeModel.properties.stepName}.${field.value}}}`,
                globeValue: `{{context['${props.nodeModel.id}'].${field.value}}}`
            }
        })
        return fields
    }
    return []
})

function showOperate(type: string) {
    return type !== WorkflowType.Base && type !== WorkflowType.Start
}
const openNodeMenu = (anchorValue: any) => {
    showAnchor.value = true
    anchorData.value = anchorValue
}
const closeNodeMenu = () => {
    showAnchor.value = false
    anchorData.value = undefined
}
onMounted(() => {
    set(props.nodeModel, 'openNodeMenu', (anchorData: any) => {
        showAnchor.value ? closeNodeMenu() : openNodeMenu(anchorData)
    })
})
</script>
<style lang="scss" scoped>
/* 菜单里的禁用项：**看起来**不可用，但**仍然可点** —— 点它由动作层弹出原因。
   用 el-dropdown-item 的 disabled 会把点击吃掉，用户只会觉得“点了没反应”。 */
:deep(.is-disabled-item) {
    color: var(--el-text-color-disabled) !important;
    cursor: not-allowed;
}

:deep(.menu-reason) {
    margin-left: var(--space-xs);
    font-size: var(--text-2xs);
    color: var(--el-text-color-placeholder);
}

.workflow-node-container {
    .step-container {
        border: 1px solid var(--color-rule-strong) !important;
        box-sizing: border-box;
        background: var(--color-surface);
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow-whisper) !important;

        &:hover {
            box-shadow: var(--shadow-float) !important;
        }

        &.isSelected {
            border: 2px solid var(--color-accent) !important;
        }

        &.error {
            border: 1px solid var(--color-danger) !important;
        }
    }

    /* 节点名**永不换行**，宁可省略号。
       卡宽收到 210 之后，6 字中文名（如「改写检索问题」）在标题行里刚好放不下，
       会断成「改写检索问 / 题」—— 断词比截断难读得多，而且两行标题会把卡片
       高度再顶起来、间接压低 fitView 缩放。
       `min-width: 0` 是必须的：flex 子项默认 min-width:auto，不给 0 的话
       text-overflow 不会生效。 */
    h4 {
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        min-width: 0;
    }

    .arrow-icon {
        transition: transform var(--dur-micro) var(--ease-out);
    }

    /* 卡头：名字区吃掉剩余宽度、按钮区不压缩 —— 这样名字区**永远不会**伸到按钮底下，
       卡片的水平中心也就不会落在按钮上（那曾让"点卡片开配置"在部分节点上静默失效）。 */
    .node-head-main {
        flex: 1;
        min-width: 0;
    }

    .node-head-actions {
        flex-shrink: 0;
    }

    /* 卡上的「必要信息」只读摘要：一行、12px、次要色。
       它是"一眼要看的"，不承担编辑职责 —— 编辑在弹窗里。 */
    .node-essential {
        margin-top: var(--space-2xs);
        font-size: var(--text-2xs);
        line-height: var(--leading-tight);
        color: var(--color-muted);

        &:empty {
            display: none;
        }
    }

    /* 抽屉里的「执行条件」说明行 */
    .node-cond {
        font-size: var(--text-xs);
        color: var(--color-muted);
    }

    /* 卡片可点开配置 —— 给一个能看出来的提示，但不用彩色（accent 预算留给选中态）。 */
    .step-container.is-clickable {
        cursor: pointer;
    }
}

:deep(.el-card) {
    overflow: visible;
}
</style>
