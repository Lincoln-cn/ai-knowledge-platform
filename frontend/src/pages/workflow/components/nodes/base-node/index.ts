import BaseNodeVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'

class BaseNode extends AppNode {
    constructor(props: any) {
        super(props, BaseNodeVue)
    }
}

class BaseModel extends AppNodeModel {
    constructor(data: any, graphModel: any) {
        super(data, graphModel)
    }
    /*
     * 这里原本是 `override get_width() { return 600 }` —— 硬编码 600。
     *
     * 它是画布缩放偏低的主因：autoLayout 的 ranksep = max(80, maxWidth × 0.25)，
     * 而 maxWidth 取全图最宽节点 ⇒ 这一个 600 把 ranksep 从 80 抬到 150，
     * 再把整图逻辑跨度抬到 5606px（画布只有 1400px）⇒ 缩放被压到 0.248。
     * 更隐蔽的是它**绕过**了基类里的宽度上限（`override` 直接返回常量），
     * 所以在基类加 Math.min 对它无效 —— 第一版收窄就是这么失效的。
     *
     * 现在不再覆写：宽度由 AppNodeModel.get_width() 的**唯一一处上限**统一决定。
     * 「基本信息」节点排布本来就与其它节点同宽更整齐，不需要单独加宽。
     */
}
export default {
    type: 'BASE_NODE',
    model: BaseModel,
    view: BaseNode
}
