/**
 * 迁移总览数据。
 *
 * 不是业务数据，是**迁移进度表**：每一行对应 qa-ui-3 的一个目标模块，
 * 数字来自搬迁前对源工程的实测分类（516 文件 / 91,545 行）。
 * 模块迁移完成后从本表移除，表空即迁移完成。
 */
export interface MigrationModule {
  /** 目标路由 */
  path: string
  name: string
  /** qa-ui-3 的源目录 */
  source: string
  /** 复用行数 */
  reuseLines: number
  /** 需合并的重复组 */
  mergeGroups: number
  /** 是否含 avue 页面需要重写 */
  hasAvue: boolean
  status: '待迁移' | '进行中' | '已完成'
}

export const migrationModules: MigrationModule[] = [
  {
    path: '/models',
    name: '模型管理',
    source: 'views/modelManager/',
    reuseLines: 1022,
    mergeGroups: 0,
    hasAvue: true,
    status: '已完成'
  },
  {
    path: '/knowledge',
    name: '知识库',
    source: 'views/knowledgeBase/ + views/detailKnowledge/ + views/paragraph/',
    reuseLines: 6216,
    mergeGroups: 3,
    hasAvue: false,
    status: '已完成'
  },
  {
    path: '/knowledge/hittest',
    name: '命中测试',
    source: 'views/detailKnowledge/hitTest.vue + views/adhibition/component/HitTest.vue',
    reuseLines: 477,
    mergeGroups: 1,
    hasAvue: false,
    status: '已完成'
  },
  {
    path: '/assistants',
    name: '应用管理',
    source: 'views/adhibition/',
    reuseLines: 5329,
    mergeGroups: 0,
    hasAvue: false,
    status: '已完成'
  },
  {
    path: '/assistants/prompts',
    name: '提示词',
    source: 'views/markedManager/ + views/markedSystem/ + views/workBench/markedSystem/',
    reuseLines: 1996,
    mergeGroups: 1,
    hasAvue: true,
    status: '已完成'
  },
  {
    path: '/conversation',
    name: '问答会话',
    source: 'views/chat/ + views/application/ + views/conversation/ + views/floaWin/ + page/chat/',
    reuseLines: 9583,
    mergeGroups: 2,
    hasAvue: false,
    status: '已完成'
  },
  {
    path: '/feedback',
    name: '反馈处理',
    source: 'views/workBench/userFeedback/',
    reuseLines: 220,
    mergeGroups: 0,
    hasAvue: true,
    status: '已完成'
  },
  {
    path: '/analytics',
    name: '运营看板',
    source: 'views/workBench/statistics/',
    reuseLines: 2095,
    mergeGroups: 0,
    hasAvue: true,
    status: '已完成'
  },
  {
    path: '/admin',
    name: '账号与权限',
    source: 'views/system/ + views/authority/（需重建，非复用）',
    reuseLines: 0,
    mergeGroups: 0,
    hasAvue: true,
    status: '已完成'
  }
]
