import ReplyNodeVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'
class ReplyNode extends AppNode {
    constructor(props: any) {
        super(props, ReplyNodeVue)
    }
}
export default {
    type: 'REPLY_NODE',
    model: AppNodeModel,
    view: ReplyNode
}
