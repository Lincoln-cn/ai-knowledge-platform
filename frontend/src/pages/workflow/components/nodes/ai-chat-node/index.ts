import ChatNodeVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'
class ChatNode extends AppNode {
    constructor(props: any) {
        super(props, ChatNodeVue)
    }
}
export default {
    type: 'AI_CHAT_NODE',
    model: AppNodeModel,
    view: ChatNode
}
