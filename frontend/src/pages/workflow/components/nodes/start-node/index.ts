import StartNodeVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'
class StartNode extends AppNode {
    constructor(props: any) {
        super(props, StartNodeVue)
    }
}
export default {
    type: 'START_NODE',
    model: AppNodeModel,
    view: StartNode
}
