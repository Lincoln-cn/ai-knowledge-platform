import type { App } from 'vue'
export default {
  install: (app: App) => {
    app.directive('resize', {
      created(el: any, binding: any) {
        // 记录长宽
        let width = ''
        let height = ''
        function getSize() {
          const style = (document.defaultView as any).getComputedStyle(el)
          const w = parseFloat(style.width)
          const h = parseFloat(style.height)
          // display:none / 未布局时 parseFloat 会得 NaN，别当尺寸变化上报
          if (!Number.isFinite(w) || !Number.isFinite(h)) return
          // 如果当前长宽和历史长宽不同
          if (width !== style.width || height !== style.height) {
            binding.value({
              width: w,
              height: h
            })
          }
          width = style.width
          height = style.height
        }

        // 立即测一次，再周期性复测 —— 否则高度要等 500ms 才回写，
        // dagre 用默认尺寸布局就会重叠
        getSize()
        ;(el as any).__vueDomResize__ = setInterval(getSize, 500)
      },
      unmounted(el: any) {
        clearInterval((el as any).__vueDomResize__)
      }
    })
  }
}
