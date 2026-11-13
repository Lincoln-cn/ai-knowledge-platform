import DocumentExtractNodeVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'
class DocumentExtractNode extends AppNode {
  constructor(props: any) {
    super(props, DocumentExtractNodeVue)
  }
}
export default {
  type: 'DOCUMENT_EXTRACT_NODE',
  model: AppNodeModel,
  view: DocumentExtractNode,
}
