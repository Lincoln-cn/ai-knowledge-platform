import NumberConditionNodeVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'
class NumberConditionNode extends AppNode {
    constructor(props: any) {
        super(props, NumberConditionNodeVue)
    }
}
const get_up_index_height = (condition_list: Array<any>, index: number) => {
    return condition_list
        .filter((_item, i) => i < index)
        .map((item) => (item.height ?? 12) + 8)
        .reduce((x, y) => x + y, 0)
}
/**
 * 数值判断节点模型。分支锚点逻辑与 condition-node 同构：
 * 出边靠 `${id}_${branchId}_right` 挂分支 id，删分支时要同步删边。
 */
class NumberConditionModel extends AppNodeModel {
    refreshBranch() {
        // 更新节点连接边的path
        this.incoming.edges.forEach((edge: any) => {
            edge.updatePathByAnchor()
        })
        this.outgoing.edges.forEach((edge: any) => {
            edge.updatePathByAnchor()
        })
    }
    override getDefaultAnchor() {
        const {
            id,
            x,
            y,
            width,
            height,
            properties: { branch_condition_list }
        } = this
        if (this.height === undefined) {
            this.height = 200
        }
        const showNode = this.properties.showNode === undefined ? true : this.properties.showNode
        const anchors: any = []
        anchors.push({
            x: x - width / 2 + 10,
            y: showNode ? y : y - 15,
            id: `${id}_left`,
            edgeAddable: false,
            type: 'left'
        })

        // 没有 branch_condition_list 时也要给一个右锚，否则出边拿不到 endAnchor
        if (branch_condition_list && branch_condition_list.length > 0) {
            for (let index = 0; index < branch_condition_list.length; index++) {
                const element = branch_condition_list[index]
                const h = get_up_index_height(branch_condition_list, index)
                anchors.push({
                    x: x + width / 2 - 10,
                    y: showNode ? y - height / 2 + 75 + h + (element.height ?? 12) / 2 : y - 15,
                    id: `${id}_${element.id}_right`,
                    type: 'right'
                })
            }
        } else {
            anchors.push({
                x: x + width / 2 - 10,
                y: showNode ? y : y - 15,
                id: `${id}_right`,
                type: 'right'
            })
        }

        return anchors
    }
}
export default {
    type: 'NUMBER_CONDITION_NODE',
    model: NumberConditionModel,
    view: NumberConditionNode
}
