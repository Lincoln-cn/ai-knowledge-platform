import ChatNodeVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'
class ChatNode extends AppNode {
    constructor(props: any) {
        super(props, ChatNodeVue)
    }
}
export default {
    type: 'ai-chat-node',
    model: AppNodeModel,
    view: ChatNode
}
