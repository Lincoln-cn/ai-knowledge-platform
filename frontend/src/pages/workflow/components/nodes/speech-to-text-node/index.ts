import SpeechToTextNodeVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'

class SpeechToTextNode extends AppNode {
  constructor(props: any) {
    super(props, SpeechToTextNodeVue)
  }
}

export default {
  type: 'SPEECH_TO_TEXT_NODE',
  model: AppNodeModel,
  view: SpeechToTextNode,
}
