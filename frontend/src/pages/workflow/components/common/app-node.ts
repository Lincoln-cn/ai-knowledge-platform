// import Components from '@/components'
import ElementPlus from 'element-plus'
import * as ElementPlusIcons from '@element-plus/icons-vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { HtmlResize } from '@logicflow/extension'
import Components from '@/pages/workflow/components'
import { h as lh } from '@logicflow/core'
import { createApp, h } from 'vue'
import { cloneDeep } from 'lodash-es'
import directives from '@/pages/workflow/directives'
import { WorkflowType } from '@/pages/workflow/enums/workflow'
import { nodeDict } from '@/pages/workflow/components/common/data'

/**
 * 节点卡宽度上限（画布逻辑单位）。
 *
 * 为什么有这一条：9 个节点在画布上铺成一条链，卡宽直接决定 fitView 的缩放 ——
 * base-node 原本硬编码 600，其余 340，实测逻辑跨度 5606px，而画布只有 1400px，
 * 缩放被压到 0.248（字读不了）。收到 255 后跨度 ≈2935px ⇒ 缩放 ≈0.45。
 * 推导见 AppNodeModel.get_width()。
 */
const NODE_MAX_WIDTH = 250

/**
 * 节点卡的"卡面装饰高度"（画布逻辑单位）。
 *
 * `v-resize` 量的是 `.step-container` **内部**那层的内容高，而卡片自己还有
 * 上下各 16px 内边距 + 1px 边框 ⇒ 模型框要补上这部分才正好裹住卡面。
 * 补得多了就是每张卡下面的死空白（直接抬高整图纵向跨度、压低 fitView 缩放），
 * 补得少了 foreignObject 会把卡片底边裁掉。
 */
const CARD_CHROME_HEIGHT = 34

class AppNode extends HtmlResize.view {
    isMounted
    r
    app

    constructor(props: any, VueNode: any) {
        super(props)
        this.isMounted = false
        this.r = h(VueNode, {
            properties: props.model.properties,
            nodeModel: props.model
        })

        this.app = createApp({
            render: () => this.r
        })
        this.app.use(ElementPlus, {
            locale: zhCn
        })

        this.app.use(Components)
        this.app.use(directives)
        for (const [key, component] of Object.entries(ElementPlusIcons)) {
            this.app.component(key, component)
        }

        if (props.model.properties.noRender) {
            delete props.model.properties.noRender
        } else {
            const filterNodes = props.graphModel.nodes.filter((v: any) => v.type === props.model.type)
            if (filterNodes.length - 1 > 0) {
                props.model.properties.stepName = props.model.properties.stepName + (filterNodes.length - 1)
            }
        }
        // cloneDeep：nodeDict 里的 config 是共享对象，直接赋值会让多个节点互相污染
        props.model.properties.config = cloneDeep(nodeDict[props.model.type]?.properties?.config ?? {})
        if (props.model.properties.height) {
            props.model.height = props.model.properties.height
        }
    }

    override getAnchorShape(anchorData: any) {
        const { x, y, type } = anchorData
        let isConnect = false

        if (type == 'left') {
            isConnect = this.props.graphModel.edges.some((edge) => edge.targetAnchorId == anchorData.id)
        } else {
            isConnect = this.props.graphModel.edges.some((edge) => edge.sourceAnchorId == anchorData.id)
        }

        return lh(
            'foreignObject',
            {
                ...anchorData,
                x: x - 10,
                y: y - 12,
                width: 30,
                height: 30
            },
            [
                lh('div', {
                    style: { zindex: 0 },
                    onClick: () => {
                        if (type == 'right') {
                            this.props.model.openNodeMenu(anchorData)
                        }
                    },
                    dangerouslySetInnerHTML: {
                        __html: isConnect
                            ? `<svg width="100%" height="100%" viewBox="0 0 42 42" fill="none" xmlns="http://www.w3.org/2000/svg">
              <g filter="url(#filter0_d_5119_232585)">
              <path d="M20.9998 29.8333C28.0875 29.8333 33.8332 24.0876 33.8332 17C33.8332 9.91231 28.0875 4.16663 20.9998 4.16663C13.9122 4.16663 8.1665 9.91231 8.1665 17C8.1665 24.0876 13.9122 29.8333 20.9998 29.8333Z" fill="white"/>
              <path fill-rule="evenodd" clip-rule="evenodd" d="M20.9998 27.5C26.7988 27.5 31.4998 22.799 31.4998 17C31.4998 11.201 26.7988 6.49996 20.9998 6.49996C15.2008 6.49996 10.4998 11.201 10.4998 17C10.4998 22.799 15.2008 27.5 20.9998 27.5ZM33.8332 17C33.8332 24.0876 28.0875 18.799 20.9998 24.0876C13.9122 18.799 8.1665 24.0876 8.1665 17C8.1665 9.91231 13.9122 4.16663 20.9998 4.16663C28.0875 4.16663 33.8332 9.91231 33.8332 17Z" fill="#3370FF"/>
              </g>
              <defs>
              <filter id="filter0_d_5119_232585" x="-1" y="-1" width="44" height="44" filterUnits="userSpaceOnUse" color-interpolation-filters="sRGB">
              <feFlood flood-opacity="0" result="BackgroundImageFix"/>
              <feColorMatrix in="SourceAlpha" type="matrix" values="0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0" result="hardAlpha"/>
              <feOffset dy="4"/>
              <feGaussianBlur stdDeviation="4"/>
              <feComposite in2="hardAlpha" operator="out"/>
              <feColorMatrix type="matrix" values="0 0 0 0 0.2 0 0 0 0 0.439216 0 0 0 0 1 0 0 0 0 0.1 0"/>
              <feBlend mode="normal" in2="BackgroundImageFix" result="effect1_dropShadow_5119_232585"/>
              <feBlend mode="normal" in="SourceGraphic" in2="effect1_dropShadow_5119_232585" result="shape"/>
              </filter>
              </defs>
              </svg>
              `
                            : `<svg width="100%" height="100%" viewBox="0 0 42 42" fill="none" xmlns="http://www.w3.org/2000/svg">
        <g filter="url(#filter0_d_5199_166905)">
        <path d="M20.9998 29.8333C28.0875 29.8333 33.8332 24.0876 33.8332 17C33.8332 9.91231 28.0875 4.16663 20.9998 4.16663C13.9122 4.16663 8.1665 9.91231 8.1665 17C8.1665 24.0876 13.9122 29.8333 20.9998 29.8333Z" fill="#3370FF"/>
        <path d="M19.8332 11.75C19.8332 11.4278 20.0943 11.1666 20.4165 11.1666H21.5832C21.9053 11.1666 22.1665 11.4278 22.1665 11.75V15.8333H26.2498C26.572 15.8333 26.8332 16.0945 26.8332 16.4166V17.5833C26.8332 17.9055 26.572 18.1666 26.2498 18.1666H22.1665V22.25C22.1665 22.572 21.9053 22.8333 21.5832 22.8333H20.4165C20.0943 22.8333 19.8332 22.572 19.8332 22.25V18.1666H15.7498C15.4277 18.1666 15.1665 17.9055 15.1665 17.5833V16.4166C15.1665 16.0945 15.4277 15.8333 15.7498 15.8333H19.8332V11.75Z" fill="white"/>
        </g>
        <defs>
        <filter id="filter0_d_5199_166905" x="-1" y="-1" width="44" height="44" filterUnits="userSpaceOnUse" color-interpolation-filters="sRGB">
        <feFlood flood-opacity="0" result="BackgroundImageFix"/>
        <feColorMatrix in="SourceAlpha" type="matrix" values="0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0" result="hardAlpha"/>
        <feOffset dy="4"/>
        <feGaussianBlur stdDeviation="4"/>
        <feComposite in2="hardAlpha" operator="out"/>
        <feColorMatrix type="matrix" values="0 0 0 0 0.2 0 0 0 0 0.439216 0 0 0 0 1 0 0 0 0 0.1 0"/>
        <feBlend mode="normal" in2="BackgroundImageFix" result="effect1_dropShadow_5199_166905"/>
        <feBlend mode="normal" in="SourceGraphic" in2="effect1_dropShadow_5199_166905" result="shape"/>
        </filter>
        </defs>
        </svg>`
                    }
                })
            ]
        )
    }

    override setHtml(rootEl: HTMLElement) {
        if (!this.isMounted) {
            this.isMounted = true
            const node = document.createElement('div')
            rootEl.appendChild(node)
            this.app?.mount(node)
        } else {
            if (this.r && this.r.component) {
                this.r.component.props.properties = this.props.model.getProperties()
            }
        }
    }
}

class AppNodeModel extends HtmlResize.model {
    refreshDeges() {
        // 更新节点连接边的path
        this.incoming.edges.forEach((edge: any) => {
            edge.updatePathByAnchor()
        })
        this.outgoing.edges.forEach((edge: any) => {
            edge.updatePathByAnchor()
        })
    }
    set_position(position: { x?: number; y?: number }) {
        const { x, y } = position
        if (x) {
            this.x = x
        }
        if (y) {
            this.y = y
        }
        this.refreshDeges()
    }
    override getResizeOutlineStyle() {
        const style = super.getResizeOutlineStyle()
        style.stroke = 'none'
        return style
    }
    override getControlPointStyle() {
        const style = super.getControlPointStyle()
        style.stroke = 'none'
        style.fill = 'none'
        return style
    }
    override getNodeStyle() {
        return {
            overflow: 'visible'
        }
    }
    override getOutlineStyle() {
        const style = super.getOutlineStyle()
        style.stroke = 'none'
        if (style.hover) {
            style.hover.stroke = 'none'
        }
        return style
    }
    override getAnchorStyle(anchorInfo: any) {
        const style = super.getAnchorStyle(anchorInfo)
        if (anchorInfo.type === 'left') {
            style.fill = 'red'
            style.hover.fill = 'transparent'
            style.hover.stroke = 'transpanrent'
            style.className = 'lf-hide-default'
        } else {
            style.fill = 'green'
        }
        return style
    }

    setHeight(height: number) {
        // v-show 收起时 getComputedStyle 会给出空串→NaN，不能写回模型
        if (!Number.isFinite(height)) return
        const sourceHeight = this.height
        // 量到的是 `.step-container` **里面**那层的内容高（v-resize 挂在那里），
        // 而卡片自己还有上下 16px 内边距 + 1px 边框 ⇒ 补 34px 让模型框正好裹住卡面。
        //
        // ⚠️ 这里原本是 `height + 100`。那在当时是"卡片里塞着一整张表单、还要留
        //    展开余量"的余量；配置搬进弹窗后卡片只剩标题行，这 100px 就成了
        //    每张卡下面 66px 的死空白 —— 9 张卡叠起来的纵向跨度直接决定 fitView 缩放。
        const targetHeight = height + CARD_CHROME_HEIGHT
        this.height = targetHeight
        this.properties['height'] = targetHeight
        this.move(0, (targetHeight - sourceHeight) / 2)
        this.outgoing.edges.forEach((edge: any) => {
            edge.updatePathByAnchor()
        })
        this.incoming.edges.forEach((edge: any) => {
            edge.updatePathByAnchor()
        })
    }
    get_width() {
        // ── 节点卡宽度的**上限** ────────────────────────────────────────────
        // 9 个节点在画布上铺成一条链，卡宽直接决定 fitView 的缩放：
        //   卡宽 600/340（base-node 硬编码 600）⇒ 逻辑跨度 5606px，画布只有 1400px
        //   ⇒ 缩放被压到 0.248，字读不了（而那还是"一页装下"的代价）。
        // autoLayout 的 ranksep = max(80, maxWidth×0.25) 也是被这个 maxWidth 抬上去的。
        //
        // 收到 255 后的实测推导：maxWidth=255 ⇒ ranksep=80
        //   ⇒ 跨度 = 9×255 + 8×80 = 2935 ⇒ 缩放 ≈ 0.45，字基本可读，
        //      且不违背「一页装下、不要滚动条」。
        //
        // ⚠️ 只夹**上限**，不改各类型自己的宽度语义；也不动 autoLayout / dagre 那套
        //    （那是 R22–R31 反复修过的区域）。
        const declared = this.properties?.width || 340
        return Math.min(declared, NODE_MAX_WIDTH)
    }

    override setAttributes() {
        this.width = this.get_width()
        const isLoop = (node_id: string, target_node_id: string) => {
            const up_node_list = this.graphModel.getNodeIncomingNode(node_id)
            for (const index in up_node_list) {
                const item = up_node_list[index]
                if (item.id === target_node_id) {
                    return true
                } else {
                    const result = isLoop(item.id, target_node_id)
                    if (result) {
                        return true
                    }
                }
            }
            return false
        }
        const circleOnlyAsTarget = {
            message: '只允许从右边的锚点连出',
            validate: (_sourceNode: any, _targetNode: any, sourceAnchor: any) => {
                return sourceAnchor.type === 'right'
            }
        }
        this.sourceRules.push({
            message: '不可循环连线',
            validate: (sourceNode: any, targetNode: any) => {
                return !isLoop(sourceNode.id, targetNode.id)
            }
        })

        this.sourceRules.push(circleOnlyAsTarget)
        this.targetRules.push({
            message: '只允许连接左边的锚点',
            validate: (_sourceNode: any, _targetNode: any, _sourceAnchor: any, targetAnchor: any) => {
                return targetAnchor.type === 'left'
            }
        })
    }
    override getDefaultAnchor() {
        const { id, x, y, width } = this
        const showNode = this.properties.showNode === undefined ? true : this.properties.showNode
        const anchors: any = []

        // 所有节点都要有锚点。旧实现把 base-node 整段排除在外，
        // 结果 start→base、base→question 这类边拿不到 endAnchor。
        if (this.type !== WorkflowType.Start) {
            anchors.push({
                x: x - width / 2 + 10,
                y: showNode ? y : y - 15,
                id: `${id}_left`,
                edgeAddable: false,
                type: 'left'
            })
        }
        anchors.push({
            x: x + width / 2 - 10,
            y: showNode ? y : y - 15,
            id: `${id}_right`,
            type: 'right'
        })

        return anchors
    }
}
export { AppNodeModel, AppNode }
