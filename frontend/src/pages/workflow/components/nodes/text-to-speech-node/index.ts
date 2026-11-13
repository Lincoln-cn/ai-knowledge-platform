import TextToSpeechNodeVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'

class TextToSpeechNode extends AppNode {
  constructor(props: any) {
    super(props, TextToSpeechNodeVue)
  }
}

export default {
  type: 'TEXT_TO_SPEECH_NODE',
  model: AppNodeModel,
  view: TextToSpeechNode,
}
