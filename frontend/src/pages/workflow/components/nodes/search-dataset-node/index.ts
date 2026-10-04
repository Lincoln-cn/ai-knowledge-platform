import SearchDatasetVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'
class SearchDatasetNode extends AppNode {
    constructor(props: any) {
        super(props, SearchDatasetVue)
    }
}
export default {
    type: 'SEARCH_DATASET_NODE',
    model: AppNodeModel,
    view: SearchDatasetNode
}
