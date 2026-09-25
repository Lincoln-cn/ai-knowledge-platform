import { DagreLayout, type DagreLayoutOptions } from '@antv/layout'

export default class Dagre {
  static pluginName = 'dagre'
  lf: any
  option: DagreLayoutOptions | any
  render(lf: any) {
    this.lf = lf
  }

  /**
   * option: {
   *   rankdir: "TB" | "LR", // 布局方向
   *   nodesep, ranksep,      // 节点间距 / 层间距（由调用方按真实尺寸推导）
   *   align: "DR",
   *   begin: [120, 120],
   * }
   */
  layout(option: Record<string, any> = {}) {
    const { nodes, edges } = this.lf.graphModel
    // 间距由调用方按卡片真实尺寸传入；这里只给兜底
    this.option = {
      type: 'dagre',
      rankdir: 'LR',
      align: 'DR',
      nodesep: 80,
      ranksep: 120,
      begin: [120, 120],
      ...option
    }
    const layoutInstance = new DagreLayout(this.option)
    const layoutData = layoutInstance.layout({
      nodes: nodes.map((node: any) => ({
        id: node.id,
        size: {
          width: node.width,
          height: node.height
        },
        model: node
      })),
      edges: edges.map((edge: any) => ({
        source: edge.sourceNodeId,
        target: edge.targetNodeId,
        model: edge
      }))
    })

    layoutData.nodes?.forEach((node: any) => {
      const { model } = node
      model.set_position({ x: node.x, y: node.y })
    })
    // fitView 由调用方统一收尾，这里不调（避免两次 fit 打架）
  }
}
